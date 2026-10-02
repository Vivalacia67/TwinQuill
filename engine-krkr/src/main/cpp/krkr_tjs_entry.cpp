/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include <android/log.h>

#include <chrono>
#include <cstdint>
#include <fstream>
#include <mutex>
#include <stdexcept>
#include <string>

#include "tjs.h"
#include "tjsError.h"
#include "krkr_tjs_entry.h"
#include "krkr_tjs_execution.h"
#include "krkr_tjs_text.h"
#include "krkr_tjs_session.h"
#include "krkr_xp3.h"

namespace {

constexpr char kLogTag[] = "TwinQuill/Krkr";

// TJS2 contains process-global caches; concurrent broker workers must not
// construct overlapping engines or clear another engine's globals. Persistent
// sessions use this same mutex for startup, callbacks, and shutdown.
std::mutex g_tjs_mutex;

class AndroidConsoleOutput final : public TJS::iTJSConsoleOutput {
public:
    void ExceptionPrint(const TJS::tjs_char* message) override {
        print(ANDROID_LOG_ERROR, message);
    }

    void Print(const TJS::tjs_char* message) override {
        print(ANDROID_LOG_INFO, message);
    }

private:
    static void print(int priority, const TJS::tjs_char* message) {
        try {
            std::u16string text;
            if (message != nullptr) {
                while (*message != 0) {
                    text.push_back(static_cast<char16_t>(*message++));
                }
            }
            const std::string utf8 = twinquill::krkr::encode_tjs_utf8(text);
            __android_log_print(priority, kLogTag, "%s", utf8.c_str());
        } catch (...) {
            __android_log_print(priority, kLogTag, "Unable to encode TJS diagnostic");
        }
    }
};

class TjsSession final {
public:
    TjsSession() : engine_(new TJS::tTJS()) {}
    ~TjsSession() noexcept {
        twinquill::krkr::ExecutionScope cleanup(nullptr, std::chrono::seconds(2), true);
        try {
            engine_->Shutdown();
        } catch (...) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "TJS shutdown failed");
        }
        engine_->Release();
    }
    TjsSession(const TjsSession&) = delete;
    TjsSession& operator=(const TjsSession&) = delete;
    TJS::tTJS* engine() const { return engine_; }
private:
    TJS::tTJS* engine_;
};

}  // namespace

std::mutex& twinquill::krkr::tjs_engine_mutex() { return g_tjs_mutex; }

int twinquill::krkr::run_tjs_source(std::string_view source) noexcept {
    try {
        const std::u16string script = decode_tjs_source(source);
        std::lock_guard<std::mutex> lock(g_tjs_mutex);
        if (twinquill::krkr::tjs_session_active()) return 10;
        AndroidConsoleOutput output;
        TjsSession session;
        session.engine()->SetConsoleOutput(&output);
        const std::basic_string<TJS::tjs_char> tjs_script(script.begin(), script.end());
        int script_result = 0;
        twinquill::krkr::ExecutionScope execution(nullptr, std::chrono::seconds(5));
        try {
            session.engine()->ExecScript(
                tjs_script.c_str(),
                nullptr,
                nullptr,
                TJS_W("startup.tjs"));
        } catch (const TJS::eTJSSilent&) {
            output.ExceptionPrint(TJS_W("Script execution deadline exceeded"));
            script_result = 20;
        } catch (const TJS::eTJS& exception) {
            // Script errors retain their script block and engine. Destroy the
            // exception before the session and while the engine lock is held.
            output.ExceptionPrint(exception.GetMessage().c_str());
            script_result = 20;
        }
        return script_result;
    } catch (const TJS::eTJS& exception) {
        AndroidConsoleOutput output;
        output.ExceptionPrint(exception.GetMessage().c_str());
        return 20;
    } catch (const std::invalid_argument& exception) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", exception.what());
        return 20;
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", exception.what());
        return 21;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Unknown TJS startup failure");
        return 22;
    }
}

namespace TJS {

void TVPConsoleLog(const tjs_char* message) {
    AndroidConsoleOutput output;
    output.Print(message);
}

}  // namespace TJS

ttstr TVPGetMessageByLocale(const std::string& key) {
    const auto text = twinquill::krkr::decode_tjs_source(key);
    return ttstr(std::basic_string<TJS::tjs_char>(text.begin(), text.end()));
}

TJS::tjs_uint32 TVPGetRoughTickCount32() {
    using namespace std::chrono;
    return static_cast<TJS::tjs_uint32>(
        duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count());
}

extern "C" __attribute__((visibility("default")))
int twinquill_engine_krkr_run_loose_startup(const char* startup_path) {
    if (startup_path == nullptr || startup_path[0] == '\0') {
        return 10;
    }

    try {
        std::ifstream input(startup_path, std::ios::binary | std::ios::ate);
        if (!input) {
            return 11;
        }
        const auto size = input.tellg();
        if (size <= 0) return 11;
        if (size > static_cast<std::streamoff>(twinquill::krkr::kStartupSourceLimit)) return 20;
        input.seekg(0, std::ios::beg);
        std::string source(static_cast<std::size_t>(size), '\0');
        if (!input.read(source.data(), static_cast<std::streamsize>(source.size()))) return 41;
        return twinquill::krkr::run_tjs_source(source);
    } catch (const TJS::eTJS& exception) {
        AndroidConsoleOutput output;
        output.ExceptionPrint(exception.GetMessage().c_str());
        return 20;
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", exception.what());
        return 21;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Unknown loose startup failure");
        return 22;
    }
}

extern "C" __attribute__((visibility("default")))
int twinquill_engine_krkr_run_xp3_startup(const char* archive_path) {
    try {
        std::string source;
        const int read_result = twinquill::krkr::read_raw_xp3_startup(archive_path, &source);
        if (read_result != 0) {
            return read_result;
        }
        return twinquill::krkr::run_tjs_source(source);
    } catch (const TJS::eTJS& exception) {
        AndroidConsoleOutput output;
        output.ExceptionPrint(exception.GetMessage().c_str());
        return 20;
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", exception.what());
        return 21;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Unknown XP3 startup failure");
        return 22;
    }
}

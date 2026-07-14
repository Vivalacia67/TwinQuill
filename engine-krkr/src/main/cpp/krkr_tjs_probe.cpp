/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include <android/log.h>

#include <chrono>
#include <cstdint>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <string>

#include "tjs.h"
#include "tjsError.h"

namespace {

constexpr char kLogTag[] = "TwinQuill/Krkr";

std::u16string ascii_to_tjs(const std::string& text) {
    std::u16string converted;
    converted.reserve(text.size());
    for (const unsigned char character : text) {
        if (character > 0x7f) {
            throw std::invalid_argument("M0 TJS probe accepts ASCII source only");
        }
        converted.push_back(static_cast<char16_t>(character));
    }
    return converted;
}

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
        std::string narrow;
        if (message != nullptr) {
            while (*message != 0) {
                const auto character = static_cast<std::uint16_t>(*message++);
                narrow.push_back(character <= 0x7f ? static_cast<char>(character) : '?');
            }
        }
        __android_log_print(priority, kLogTag, "%s", narrow.c_str());
    }
};

}  // namespace

namespace TJS {

void TVPConsoleLog(const tjs_char* message) {
    AndroidConsoleOutput output;
    output.Print(message);
}

}  // namespace TJS

ttstr TVPGetMessageByLocale(const std::string& key) {
    return ttstr(ascii_to_tjs(key).c_str());
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
        std::ifstream input(startup_path, std::ios::binary);
        if (!input) {
            return 11;
        }
        const std::string source(
            (std::istreambuf_iterator<char>(input)),
            std::istreambuf_iterator<char>());
        const std::u16string script = ascii_to_tjs(source);

        AndroidConsoleOutput output;
        TJS::tTJS* engine = new TJS::tTJS();
        engine->SetConsoleOutput(&output);
        int result_code = 0;
        try {
            engine->ExecScript(
                reinterpret_cast<const TJS::tjs_char*>(script.c_str()),
                nullptr,
                nullptr,
                TJS_W("startup.tjs"));
            TJS::tTJSVariant result;
            engine->EvalExpression(TJS_W("global.twinQuillM0Result"), &result);
            if (result.AsInteger() != 42) {
                result_code = 12;
            }
            engine->Shutdown();
            engine->Release();
        } catch (...) {
            engine->Shutdown();
            engine->Release();
            throw;
        }
        return result_code;
    } catch (const TJS::eTJS& exception) {
        AndroidConsoleOutput output;
        output.ExceptionPrint(exception.GetMessage().c_str());
        return 20;
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", exception.what());
        return 21;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Unknown TJS probe failure");
        return 22;
    }
}

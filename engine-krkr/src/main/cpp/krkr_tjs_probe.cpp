/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <utility>

#include "krkr_kag_probe.h"
#include "krkr_kag_platform.h"
#include "krkr_storage.h"
#include "krkr_storage_registry.h"
#include "StorageIntf.h"
#include "tjs.h"
#include "tjsError.h"

namespace {

constexpr char kLogTag[] = "TwinQuill/Krkr";
constexpr std::uint64_t kMaxLooseStartupSize = 8 * 1024 * 1024;
constexpr std::uint64_t kMaxXp3StartupSize = 8 * 1024 * 1024;

std::mutex& runtime_mutex() {
    static std::mutex mutex;
    return mutex;
}

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

class ScopedStorageMediaRegistration final {
public:
    explicit ScopedStorageMediaRegistration(
        std::unique_ptr<twinquill::krkr::StorageRegistry> media)
        : media_(media.get()) {
        TVPRegisterStorageMedia(media_);
        static_cast<void>(media.release());
    }

    ~ScopedStorageMediaRegistration() noexcept {
        if (media_ == nullptr) {
            return;
        }
        try {
            TVPUnregisterStorageMedia(media_);
        } catch (...) {
        }
        try {
            media_->Release();
        } catch (...) {
        }
    }

    ScopedStorageMediaRegistration(const ScopedStorageMediaRegistration&) = delete;
    ScopedStorageMediaRegistration& operator=(const ScopedStorageMediaRegistration&) = delete;

    twinquill::krkr::StorageRegistry* get() const {
        return media_;
    }

private:
    twinquill::krkr::StorageRegistry* media_;
};

class ScopedTjsEngine final {
public:
    explicit ScopedTjsEngine(TJS::tTJS* engine) noexcept : engine_(engine) {
    }

    ~ScopedTjsEngine() noexcept {
        if (engine_ == nullptr) {
            return;
        }
        try {
            engine_->Shutdown();
        } catch (...) {
        }
        try {
            engine_->Release();
        } catch (...) {
        }
    }

    ScopedTjsEngine(const ScopedTjsEngine&) = delete;
    ScopedTjsEngine& operator=(const ScopedTjsEngine&) = delete;

    TJS::tTJS* get() const {
        return engine_;
    }

private:
    TJS::tTJS* engine_;
};

bool fits_size_t(std::uint64_t value) {
    return value <= static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max());
}

int read_stream_to_string(
    tTJSBinaryStream* stream,
    std::uint64_t maximum_size,
    std::string* output) {
    if (stream == nullptr || output == nullptr) {
        return -1;
    }
    const std::uint64_t source_size = stream->GetSize();
    if (source_size > maximum_size || !fits_size_t(source_size)) {
        return -1;
    }
    const std::size_t checked_size = static_cast<std::size_t>(source_size);
    output->assign(checked_size, '\0');
    char* cursor = output->empty() ? nullptr : output->data();
    std::size_t remaining = checked_size;
    while (remaining > 0) {
        const std::size_t request = std::min(
            remaining,
            static_cast<std::size_t>(std::numeric_limits<TJS::tjs_uint>::max()));
        const TJS::tjs_uint count = stream->Read(cursor, static_cast<TJS::tjs_uint>(request));
        if (count == 0 || static_cast<std::size_t>(count) > request) {
            output->clear();
            return -1;
        }
        cursor += count;
        remaining -= static_cast<std::size_t>(count);
    }
    return 0;
}

int map_registered_startup_failure(
    twinquill::krkr::StorageRegistry* media,
    int not_found_code,
    int malformed_code) {
    if (media == nullptr) {
        return malformed_code;
    }
    std::unique_ptr<twinquill::krkr::ReadOnlyStream> ignored;
    const twinquill::krkr::StorageRegistryResult result = media->Open("startup.tjs", &ignored);
    if (result == twinquill::krkr::StorageRegistryResult::kNotFound) {
        return not_found_code;
    }
    if (result == twinquill::krkr::StorageRegistryResult::kProtected) {
        return 35;
    }
    return malformed_code;
}

int read_registered_startup(
    twinquill::krkr::StorageRegistry* media,
    std::uint64_t maximum_size,
    int not_found_code,
    int malformed_code,
    std::string* source) {
    std::unique_ptr<tTJSBinaryStream> startup(
        TVPCreateStream(ttstr(twinquill::krkr::kTwinQuillStartupStorageName), TJS_BS_READ));
    if (startup == nullptr) {
        return map_registered_startup_failure(media, not_found_code, malformed_code);
    }
    return read_stream_to_string(startup.get(), maximum_size, source) == 0
        ? 0
        : malformed_code;
}

std::string tjs_string_to_utf8(const TJS::tTJSVariant& value) {
    TJS::tTJSString converted(value);
    return converted.AsNarrowStdString();
}

TJS::tTJSVariant get_optional_kag_probe_scenario(TJS::tTJS* engine) {
    TJS::tTJSVariant scenario;
    if (engine == nullptr) {
        return scenario;
    }
    try {
        engine->EvalExpression(TJS_W("global.twinQuillM3KagProbeScenario"), &scenario);
    } catch (const TJS::eTJS&) {
        scenario.Clear();
    }
    return scenario;
}

}  // namespace

int run_tjs_source(const std::string& source) {
    const std::u16string script = ascii_to_tjs(source);

    AndroidConsoleOutput output;
    ScopedTjsEngine engine(new TJS::tTJS());
    engine.get()->SetConsoleOutput(&output);
    twinquill::krkr::KagRuntimeScope kag_runtime(
        engine.get(),
        engine.get()->GetGlobalNoAddRef());
    int result_code = 0;
    engine.get()->ExecScript(
        reinterpret_cast<const TJS::tjs_char*>(script.c_str()),
        nullptr,
        nullptr,
        TJS_W("startup.tjs"));
    TJS::tTJSVariant result;
    engine.get()->EvalExpression(TJS_W("global.twinQuillM0Result"), &result);
    if (result.AsInteger() != 42) {
        result_code = 12;
    } else {
        TJS::tTJSVariant scenario = get_optional_kag_probe_scenario(engine.get());
        if (scenario.Type() == TJS::tvtString) {
            const std::string scenario_name = tjs_string_to_utf8(scenario);
            if (!scenario_name.empty()) {
                result_code = twinquill::krkr::probe_kag_scenario(scenario_name);
            }
        } else if (scenario.Type() != TJS::tvtVoid) {
            result_code = 41;
        }
    }
    return result_code;
}

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

int run_loose_startup(twinquill::krkr::StorageSpec startup) {
    std::lock_guard<std::mutex> lock(runtime_mutex());
    try {
        auto media = std::unique_ptr<twinquill::krkr::StorageRegistry>(
            new twinquill::krkr::StorageRegistry());
        if (media->RegisterLoose("startup.tjs", std::move(startup)) !=
            twinquill::krkr::StorageRegistryResult::kOk) {
            return 11;
        }
        ScopedStorageMediaRegistration registration(std::move(media));
        std::string source;
        const int read_result = read_registered_startup(
            registration.get(),
            kMaxLooseStartupSize,
            11,
            11,
            &source);
        if (read_result != 0) {
            return read_result;
        }
        return run_tjs_source(source);
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

int run_xp3_startup(twinquill::krkr::StorageSpec archive) {
    std::lock_guard<std::mutex> lock(runtime_mutex());
    try {
        auto media = std::unique_ptr<twinquill::krkr::StorageRegistry>(
            new twinquill::krkr::StorageRegistry());
        const int register_result = media->RegisterArchive(std::move(archive));
        if (register_result != 0) {
            return register_result;
        }
        ScopedStorageMediaRegistration registration(std::move(media));
        std::string source;
        const int read_result = read_registered_startup(
            registration.get(),
            kMaxXp3StartupSize,
            31,
            34,
            &source);
        if (read_result != 0) {
            return read_result;
        }
        return run_tjs_source(source);
    } catch (const TJS::eTJS& exception) {
        AndroidConsoleOutput output;
        output.ExceptionPrint(exception.GetMessage().c_str());
        return 20;
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", exception.what());
        return 21;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Unknown XP3 probe failure");
        return 22;
    }
}

extern "C" __attribute__((visibility("default")))
int twinquill_engine_krkr_run_loose_startup(const char* startup_path) {
    if (startup_path == nullptr || startup_path[0] == '\0') {
        return 10;
    }
    return run_loose_startup(twinquill::krkr::StorageSpec::LocalFile(startup_path));
}

extern "C" __attribute__((visibility("default")))
int twinquill_engine_krkr_run_loose_startup_fd(int descriptor) {
    if (descriptor < 0) {
        return 10;
    }
    return run_loose_startup(twinquill::krkr::StorageSpec::OwnedFileDescriptor(descriptor));
}

extern "C" __attribute__((visibility("default")))
int twinquill_engine_krkr_run_xp3_startup(const char* archive_path) {
    if (archive_path == nullptr || archive_path[0] == '\0') {
        return 30;
    }
    return run_xp3_startup(twinquill::krkr::StorageSpec::LocalFile(archive_path));
}

extern "C" __attribute__((visibility("default")))
int twinquill_engine_krkr_run_xp3_startup_fd(int descriptor) {
    if (descriptor < 0) {
        return 30;
    }
    return run_xp3_startup(twinquill::krkr::StorageSpec::OwnedFileDescriptor(descriptor));
}

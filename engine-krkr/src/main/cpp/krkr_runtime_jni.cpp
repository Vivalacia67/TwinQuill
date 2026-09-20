/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_runtime_state.h"

#include <jni.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <limits>
#include <utility>
#include <string>

namespace {

using twinquill::krkr_runtime::RuntimeHandle;

constexpr jint kJniExceptionResult = 22;

void clear_pending_exception(JNIEnv* environment) noexcept {
    if (environment != nullptr && environment->ExceptionCheck() == JNI_TRUE) {
        environment->ExceptionClear();
    }
}

class UtfChars final {
 public:
    UtfChars(JNIEnv* environment, jstring value) noexcept
        : environment_(environment), value_(value) {
        if (environment_ == nullptr || value_ == nullptr) {
            return;
        }
        chars_ = environment_->GetStringUTFChars(value_, nullptr);
        if (environment_->ExceptionCheck() == JNI_TRUE) {
            clear_pending_exception(environment_);
            chars_ = nullptr;
        }
    }

    ~UtfChars() noexcept {
        if (chars_ != nullptr) {
            environment_->ReleaseStringUTFChars(value_, chars_);
        }
    }

    UtfChars(const UtfChars&) = delete;
    UtfChars& operator=(const UtfChars&) = delete;

    const char* get() const noexcept { return chars_; }
    bool valid() const noexcept { return chars_ != nullptr; }

 private:
    JNIEnv* environment_ = nullptr;
    jstring value_ = nullptr;
    const char* chars_ = nullptr;
};

bool copy_string(JNIEnv* environment, jstring value, std::string* output) {
    if (environment == nullptr || value == nullptr || output == nullptr) {
        return false;
    }
    UtfChars chars(environment, value);
    if (!chars.valid()) {
        return false;
    }
    output->assign(chars.get());
    if (environment->ExceptionCheck() == JNI_TRUE) {
        clear_pending_exception(environment);
        return false;
    }
    return !output->empty();
}

template <typename Result, typename Call>
Result jni_boundary(JNIEnv* environment, Result failure, Call&& call) noexcept {
    try {
        return std::forward<Call>(call)();
    } catch (const std::exception&) {
        clear_pending_exception(environment);
        return failure;
    } catch (...) {
        clear_pending_exception(environment);
        return failure;
    }
}

RuntimeHandle from_jlong(jlong value) noexcept {
    return value <= 0 ? 0U : static_cast<RuntimeHandle>(value);
}

jlong to_jlong(std::uint64_t value) noexcept {
    constexpr std::uint64_t kMaxJlong =
        static_cast<std::uint64_t>(std::numeric_limits<jlong>::max());
    return static_cast<jlong>(value > kMaxJlong ? kMaxJlong : value);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_nativeCreate(
    JNIEnv* environment,
    jclass,
    jint source_kind,
    jstring source,
    jstring save_directory,
    jstring game_id) {
    return jni_boundary<jlong>(environment, 0, [&]() {
        std::string source_utf8;
        std::string save_utf8;
        std::string game_utf8;
        if (!copy_string(environment, source, &source_utf8) ||
            !copy_string(environment, save_directory, &save_utf8) ||
            !copy_string(environment, game_id, &game_utf8)) {
            return static_cast<jlong>(0);
        }
        const RuntimeHandle handle = twinquill::krkr_runtime::create(
            source_kind, source_utf8, save_utf8, game_utf8);
        return to_jlong(handle);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_nativeSurfaceCreated(
    JNIEnv* environment, jclass, jlong handle) {
    return jni_boundary<jint>(environment, kJniExceptionResult, [&]() {
        return static_cast<jint>(twinquill::krkr_runtime::surface_created(
            from_jlong(handle)));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_nativeSurfaceChanged(
    JNIEnv* environment, jclass, jlong handle, jint width, jint height) {
    return jni_boundary<jint>(environment, kJniExceptionResult, [&]() {
        return static_cast<jint>(twinquill::krkr_runtime::surface_changed(
            from_jlong(handle), width, height));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_nativeDrawFrame(
    JNIEnv* environment, jclass, jlong handle) {
    return jni_boundary<jint>(environment, kJniExceptionResult, [&]() {
        return static_cast<jint>(twinquill::krkr_runtime::draw_frame(
            from_jlong(handle)));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_nativePause(
    JNIEnv* environment, jclass, jlong handle) {
    return jni_boundary<jint>(environment, kJniExceptionResult, [&]() {
        return static_cast<jint>(twinquill::krkr_runtime::pause(
            from_jlong(handle)));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_nativeResume(
    JNIEnv* environment, jclass, jlong handle) {
    return jni_boundary<jint>(environment, kJniExceptionResult, [&]() {
        return static_cast<jint>(twinquill::krkr_runtime::resume(
            from_jlong(handle)));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_nativeLowMemory(
    JNIEnv* environment, jclass, jlong handle) {
    return jni_boundary<jint>(environment, kJniExceptionResult, [&]() {
        return static_cast<jint>(twinquill::krkr_runtime::low_memory(
            from_jlong(handle)));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_nativeSurfaceLost(
    JNIEnv* environment, jclass, jlong handle) {
    return jni_boundary<jint>(environment, kJniExceptionResult, [&]() {
        return static_cast<jint>(twinquill::krkr_runtime::surface_lost(
            from_jlong(handle)));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_nativeDestroy(
    JNIEnv* environment, jclass, jlong handle) {
    return jni_boundary<jint>(environment, kJniExceptionResult, [&]() {
        return static_cast<jint>(twinquill::krkr_runtime::destroy(
            from_jlong(handle)));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_nativeTouch(
    JNIEnv* environment,
    jclass,
    jlong handle,
    jint action,
    jint pointer_id,
    jfloat x,
    jfloat y,
    jlong event_time) {
    return jni_boundary<jint>(environment, kJniExceptionResult, [&]() {
        return static_cast<jint>(twinquill::krkr_runtime::touch(
            from_jlong(handle), action, pointer_id, x, y, event_time));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_nativeKey(
    JNIEnv* environment,
    jclass,
    jlong handle,
    jboolean down,
    jint key_code,
    jint unicode_code_point,
    jint meta_state,
    jint repeat_count,
    jlong event_time) {
    return jni_boundary<jint>(environment, kJniExceptionResult, [&]() {
        return static_cast<jint>(twinquill::krkr_runtime::key(
            from_jlong(handle), down == JNI_TRUE, key_code,
            unicode_code_point, meta_state, repeat_count, event_time));
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_twinquill_engine_krkr_KrkrRuntimeRenderer_nativeCounters(
    JNIEnv* environment, jclass, jlong handle) {
    return jni_boundary<jlongArray>(environment, nullptr, [&]() {
        if (environment == nullptr) {
            return static_cast<jlongArray>(nullptr);
        }
        std::array<std::uint64_t, twinquill::krkr_runtime::kCounterCount> values{};
        if (!twinquill::krkr_runtime::counters(
                from_jlong(handle), values.data(), values.size())) {
            return static_cast<jlongArray>(nullptr);
        }
        jlongArray output = environment->NewLongArray(
            static_cast<jsize>(twinquill::krkr_runtime::kCounterCount));
        if (output == nullptr || environment->ExceptionCheck() == JNI_TRUE) {
            clear_pending_exception(environment);
            return static_cast<jlongArray>(nullptr);
        }
        std::array<jlong, twinquill::krkr_runtime::kCounterCount> converted{};
        for (std::size_t index = 0U; index < values.size(); ++index) {
            converted[index] = to_jlong(values[index]);
        }
        environment->SetLongArrayRegion(
            output, 0, static_cast<jsize>(converted.size()), converted.data());
        if (environment->ExceptionCheck() == JNI_TRUE) {
            environment->DeleteLocalRef(output);
            clear_pending_exception(environment);
            return static_cast<jlongArray>(nullptr);
        }
        return output;
    });
}

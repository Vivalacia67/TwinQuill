/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include <jni.h>

extern "C" int twinquill_engine_krkr_run_loose_startup(const char* startup_path);
extern "C" int twinquill_engine_krkr_run_loose_startup_fd(int descriptor);
extern "C" int twinquill_engine_krkr_run_xp3_startup(const char* archive_path);
extern "C" int twinquill_engine_krkr_run_xp3_startup_fd(int descriptor);

namespace {

using PathRunner = int (*)(const char*);
using DescriptorRunner = int (*)(int);

class ScopedUtfChars final {
public:
    ScopedUtfChars(JNIEnv* environment, jstring value)
        : environment_(environment), value_(value) {
        if (value_ != nullptr) {
            chars_ = environment_->GetStringUTFChars(value_, nullptr);
        }
    }

    ~ScopedUtfChars() {
        if (chars_ != nullptr) {
            environment_->ReleaseStringUTFChars(value_, chars_);
        }
    }

    ScopedUtfChars(const ScopedUtfChars&) = delete;
    ScopedUtfChars& operator=(const ScopedUtfChars&) = delete;

    const char* get() const {
        return chars_;
    }

private:
    JNIEnv* environment_;
    jstring value_;
    const char* chars_ = nullptr;
};

jint run_path_target(
    JNIEnv* environment,
    jstring path,
    PathRunner runner,
    jint invalid_code) {
    if (path == nullptr || runner == nullptr) {
        return invalid_code;
    }

    ScopedUtfChars native_path(environment, path);
    if (native_path.get() == nullptr) {
        return 13;
    }
    return runner(native_path.get());
}

jint run_descriptor_target(jint descriptor, DescriptorRunner runner, jint invalid_code) {
    if (descriptor < 0 || runner == nullptr) {
        return invalid_code;
    }
    return runner(descriptor);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrEngineActivity_nativeRunLooseStartup(
    JNIEnv* environment,
    jclass,
    jstring startup_path) {
    return run_path_target(
        environment,
        startup_path,
        twinquill_engine_krkr_run_loose_startup,
        10);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrEngineActivity_nativeRunLooseStartupDescriptor(
    JNIEnv*,
    jclass,
    jint descriptor) {
    return run_descriptor_target(
        descriptor,
        twinquill_engine_krkr_run_loose_startup_fd,
        10);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrEngineActivity_nativeRunXp3Startup(
    JNIEnv* environment,
    jclass,
    jstring archive_path) {
    return run_path_target(
        environment,
        archive_path,
        twinquill_engine_krkr_run_xp3_startup,
        30);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrEngineActivity_nativeRunXp3StartupDescriptor(
    JNIEnv*,
    jclass,
    jint descriptor) {
    return run_descriptor_target(
        descriptor,
        twinquill_engine_krkr_run_xp3_startup_fd,
        30);
}

/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include <jni.h>

extern "C" int twinquill_engine_krkr_run_loose_startup(const char* startup_path);
extern "C" int twinquill_engine_krkr_run_xp3_startup(const char* archive_path);

namespace {

jint run_path(JNIEnv* environment, jstring path, int (*runner)(const char*)) {
    if (path == nullptr) {
        return 10;
    }

    const char* native_path = environment->GetStringUTFChars(path, nullptr);
    if (native_path == nullptr) {
        return 13;
    }
    const int result = runner(native_path);
    environment->ReleaseStringUTFChars(path, native_path);
    return result;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrEngineActivity_nativeRunLooseStartup(
    JNIEnv* environment,
    jclass,
    jstring startup_path) {
    return run_path(environment, startup_path, twinquill_engine_krkr_run_loose_startup);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrEngineActivity_nativeRunXp3Startup(
    JNIEnv* environment,
    jclass,
    jstring archive_path) {
    return run_path(environment, archive_path, twinquill_engine_krkr_run_xp3_startup);
}

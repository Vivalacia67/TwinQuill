/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include <jni.h>

extern "C" int twinquill_engine_krkr_run_loose_startup(const char* startup_path);

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrEngineActivity_nativeRunLooseStartup(
    JNIEnv* environment,
    jclass,
    jstring startup_path) {
    if (startup_path == nullptr) {
        return 10;
    }

    const char* path = environment->GetStringUTFChars(startup_path, nullptr);
    if (path == nullptr) {
        return 13;
    }
    const int result = twinquill_engine_krkr_run_loose_startup(path);
    environment->ReleaseStringUTFChars(startup_path, path);
    return result;
}

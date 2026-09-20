/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include <jni.h>

#include "twinquill_vfs.h"

extern "C" int twinquill_engine_krkr_run_loose_startup(const char* startup_path);
extern "C" int twinquill_engine_krkr_run_xp3_startup(const char* archive_path);
int run_tqsaf_startup(const char* tree_uri_utf8);

namespace {

// Keep a relocation to the public VFS ABI in this shared object. This is a
// build/link probe only; it does not invoke the bridge or alter runtime
// startup behavior, while ensuring the ELF records libtwinquill_native_vfs.so
// as a real dynamic dependency.
#if defined(__GNUC__)
__attribute__((used))
#endif
volatile auto kNativeVfsBuildProbe = &twinquill_native_vfs_build_probe;

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

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrEngineActivity_nativeRunSafStartup(
    JNIEnv* environment,
    jclass,
    jstring tree_uri) {
    return run_path(environment, tree_uri, run_tqsaf_startup);
}

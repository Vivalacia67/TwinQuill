/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include <jni.h>
#include <cmath>
#include <stdexcept>
#include "krkr_tjs_session.h"
#include "krkr_tjs_text.h"
#include "krkr_runtime_state.h"
#include "krkr_tvp_visual.h"
#include <android/asset_manager_jni.h>

extern "C" JNIEXPORT void JNICALL
Java_io_github_twinquill_engine_krkr_KrkrScriptSession_nativeSetAssets(
    JNIEnv* env, jclass, jobject assets) {
    twinquill::krkr::set_visual_assets(AAssetManager_fromJava(env, assets));
}

namespace {
std::string source_text(JNIEnv* env, jstring value) {
    if (value == nullptr) throw std::invalid_argument("Missing script source");
    const jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) throw std::runtime_error("Unable to read Java string");
    std::u16string text;
    try { text.assign(chars, chars + length); }
    catch (...) { env->ReleaseStringChars(value, chars); throw; }
    env->ReleaseStringChars(value, chars);
    return twinquill::krkr::encode_tjs_utf8(text);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_twinquill_engine_krkr_KrkrScriptSession_nativeStart(
    JNIEnv* env, jclass, jint kind, jstring source) {
    try { return twinquill::krkr::start_tjs_session(kind, source_text(env, source)); }
    catch (const std::invalid_argument&) { return -10; }
    catch (...) { return -41; }
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_twinquill_engine_krkr_KrkrScriptSession_nativePrepare(
    JNIEnv* env, jclass, jint kind, jstring source) {
    try { return twinquill::krkr::start_tjs_session(kind, source_text(env, source), true); }
    catch (const std::invalid_argument&) { return -10; }
    catch (...) { return -41; }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrScriptSession_nativeActivate(
    JNIEnv*, jclass, jlong handle, jint width, jint height) {
    try { return twinquill::krkr::activate_tjs_session(handle, width, height); }
    catch (...) { return 21; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_twinquill_engine_krkr_KrkrScriptSession_nativeCancel(
    JNIEnv*, jclass, jlong handle) {
    try { twinquill::krkr::cancel_tjs_session(handle); } catch (...) { }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrScriptSession_nativeEvent(
    JNIEnv* env, jclass, jlong handle, jint event, jdoubleArray args) {
    try {
        if (args == nullptr) return 10;
        const jsize length = env->GetArrayLength(args);
        if (length > 6) return 10;
        std::vector<double> values(static_cast<std::size_t>(length));
        env->GetDoubleArrayRegion(args, 0, length, values.data());
        if (env->ExceptionCheck()) return 41;
        for (double value : values) if (!std::isfinite(value)) return 10;
        return twinquill::krkr::dispatch_tjs_event(handle, event, values);
    } catch (...) { return 21; }
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_engine_krkr_KrkrScriptSession_nativePoll(
    JNIEnv*, jclass, jlong handle, jlong runtime) {
    try {
        std::int64_t color = -1;
        const int result = twinquill::krkr::poll_tjs_session(handle, &color);
        if (result != 0 || runtime == 0) return result;
        return twinquill::krkr_runtime::set_color(runtime, color);
    } catch (...) { return 21; }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_twinquill_engine_krkr_KrkrScriptSession_nativeClose(
    JNIEnv*, jclass, jlong handle) {
    try { twinquill::krkr::close_tjs_session(handle); } catch (...) { }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_twinquill_engine_krkr_KrkrScriptSession_nativeStats(
    JNIEnv* env, jclass, jlong handle) {
    try {
        std::int64_t values[5]{};
        twinquill::krkr::tjs_session_stats(handle, values);
        jlong converted[5];
        for (int i = 0; i < 5; ++i) converted[i] = values[i];
        jlongArray result = env->NewLongArray(5);
        if (result != nullptr) env->SetLongArrayRegion(result, 0, 5, converted);
        return result;
    } catch (...) { return nullptr; }
}

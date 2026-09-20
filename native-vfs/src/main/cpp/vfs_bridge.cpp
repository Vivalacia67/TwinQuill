/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "twinquill_vfs.h"

#include <jni.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <vector>

namespace {

JavaVM* g_vm = nullptr;
jclass g_backend = nullptr;
jmethodID g_open = nullptr;
jmethodID g_read = nullptr;
jmethodID g_seek = nullptr;
jmethodID g_close = nullptr;
jmethodID g_stat = nullptr;
jmethodID g_list_public = nullptr;
jmethodID g_list_framed = nullptr;

jmethodID g_mkdir = nullptr;
jmethodID g_rename = nullptr;
jmethodID g_delete = nullptr;
std::mutex g_install_mutex;

class AttachedEnv {
public:
    AttachedEnv() {
        if (g_vm == nullptr) {
            return;
        }
        void* raw = nullptr;
        const jint status = g_vm->GetEnv(&raw, JNI_VERSION_1_6);
        if (status == JNI_OK) {
            env_ = static_cast<JNIEnv*>(raw);
        } else if (status == JNI_EDETACHED &&
                   g_vm->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
            attached_ = true;
        }
    }

    ~AttachedEnv() {
        if (attached_) {
            g_vm->DetachCurrentThread();
        }
    }

    JNIEnv* get() const {
        return env_;
    }

private:
    JNIEnv* env_ = nullptr;
    bool attached_ = false;
};

jbyteArray bytes(JNIEnv* env, const char* value) {
    if (value == nullptr) {
        return nullptr;
    }
    const std::size_t length = std::strlen(value);
    if (length > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(length));
    if (result != nullptr && length != 0) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(length),
            reinterpret_cast<const jbyte*>(value));
    }
    return result;
}

bool ready(JNIEnv* env) {
    return env != nullptr && g_backend != nullptr;
}

int64_t call_open(JNIEnv* env, jbyteArray tree, jbyteArray path, int flags) {
    if (!ready(env) || tree == nullptr || path == nullptr) {
        return TQ_VFS_INVALID;
    }
    return env->CallStaticLongMethod(g_backend, g_open, tree, path, flags);
}

int call_mutation(
    JNIEnv* env,
    jmethodID method,
    jbyteArray tree,
    jbyteArray path) {
    if (!ready(env) || tree == nullptr || path == nullptr) {
        return TQ_VFS_INVALID;
    }
    return env->CallStaticIntMethod(g_backend, method, tree, path);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_twinquill_nativevfs_NativeVfs_nativeInstall(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_install_mutex);
    if (g_backend != nullptr) {
        return;
    }
    jclass local = env->FindClass("io/github/twinquill/nativevfs/SafVfsBackend");
    if (local == nullptr) {
        return;
    }
    g_backend = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    g_open = env->GetStaticMethodID(g_backend, "open", "([B[BI)J");
    g_read = env->GetStaticMethodID(g_backend, "read", "(J[BI)I");
    g_seek = env->GetStaticMethodID(g_backend, "seek", "(JJI)J");
    g_close = env->GetStaticMethodID(g_backend, "close", "(J)I");
    g_stat = env->GetStaticMethodID(g_backend, "stat", "([B[B)[J");
    g_list_public = env->GetStaticMethodID(
        g_backend,
        "list",
        "([B[B)[[B");
    g_list_framed = env->GetStaticMethodID(
        g_backend,
        "listFramed",
        "([B[B)[[B");
    g_mkdir = env->GetStaticMethodID(g_backend, "mkdir", "([B[B)I");
    g_rename = env->GetStaticMethodID(g_backend, "rename", "([B[B[B)I");
    g_delete = env->GetStaticMethodID(g_backend, "delete", "([B[B)I");
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_twinquill_nativevfs_NativeVfs_nativeOpen(
    JNIEnv* env,
    jclass,
    jbyteArray tree,
    jbyteArray path,
    jint flags) {
    return call_open(env, tree, path, flags);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_nativevfs_NativeVfs_nativeRead(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray output,
    jint offset,
    jint size) {
    if (!ready(env) || output == nullptr || offset < 0 || size < 0 ||
        offset > env->GetArrayLength(output) - size) {
        return TQ_VFS_INVALID;
    }
    jbyteArray temporary = env->NewByteArray(size);
    if (temporary == nullptr) {
        return TQ_VFS_ERROR;
    }
    const jint count =
        env->CallStaticIntMethod(g_backend, g_read, handle, temporary, size);
    if (count > 0) {
        jbyte* target = env->GetByteArrayElements(output, nullptr);
        jbyte* source = env->GetByteArrayElements(temporary, nullptr);
        if (target == nullptr || source == nullptr) {
            if (target != nullptr) {
                env->ReleaseByteArrayElements(output, target, JNI_ABORT);
            }
            if (source != nullptr) {
                env->ReleaseByteArrayElements(temporary, source, JNI_ABORT);
            }
            env->DeleteLocalRef(temporary);
            return TQ_VFS_ERROR;
        }
        std::memcpy(target + offset, source, static_cast<std::size_t>(count));
        env->ReleaseByteArrayElements(temporary, source, JNI_ABORT);
        env->ReleaseByteArrayElements(output, target, 0);
    }
    env->DeleteLocalRef(temporary);
    return count;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_twinquill_nativevfs_NativeVfs_nativeSeek(
    JNIEnv* env,
    jclass,
    jlong handle,
    jlong offset,
    jint whence) {
    if (!ready(env)) {
        return TQ_VFS_INVALID;
    }
    return env->CallStaticLongMethod(g_backend, g_seek, handle, offset, whence);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_nativevfs_NativeVfs_nativeClose(
    JNIEnv* env,
    jclass,
    jlong handle) {
    return ready(env)
        ? env->CallStaticIntMethod(g_backend, g_close, handle)
        : TQ_VFS_INVALID;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_twinquill_nativevfs_NativeVfs_nativeStat(
    JNIEnv* env,
    jclass,
    jbyteArray tree,
    jbyteArray path) {
    return ready(env)
        ? static_cast<jlongArray>(
            env->CallStaticObjectMethod(g_backend, g_stat, tree, path))
        : nullptr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_twinquill_nativevfs_NativeVfs_nativeList(
    JNIEnv* env,
    jclass,
    jbyteArray tree,
    jbyteArray path) {
    return ready(env)
        ? static_cast<jobjectArray>(
            env->CallStaticObjectMethod(g_backend, g_list_public, tree, path))
        : nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_nativevfs_NativeVfs_nativeMkdir(
    JNIEnv* env,
    jclass,
    jbyteArray tree,
    jbyteArray path) {
    return call_mutation(env, g_mkdir, tree, path);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_nativevfs_NativeVfs_nativeRename(
    JNIEnv* env,
    jclass,
    jbyteArray tree,
    jbyteArray path,
    jbyteArray new_name) {
    if (!ready(env) || tree == nullptr || path == nullptr || new_name == nullptr) {
        return TQ_VFS_INVALID;
    }
    return env->CallStaticIntMethod(g_backend, g_rename, tree, path, new_name);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_twinquill_nativevfs_NativeVfs_nativeDelete(
    JNIEnv* env,
    jclass,
    jbyteArray tree,
    jbyteArray path) {
    return call_mutation(env, g_delete, tree, path);
}

extern "C" TQ_VFS_API int64_t tq_vfs_open(
    const char* tree_uri_utf8,
    const char* relative_path_utf8,
    int flags) {
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    if (!ready(env)) {
        return TQ_VFS_INVALID;
    }
    jbyteArray tree = bytes(env, tree_uri_utf8);
    jbyteArray path = bytes(env, relative_path_utf8);
    const int64_t result = call_open(env, tree, path, flags);
    env->DeleteLocalRef(tree);
    env->DeleteLocalRef(path);
    return result;
}

extern "C" TQ_VFS_API int64_t tq_vfs_read(
    int64_t handle,
    void* output,
    size_t size) {
    if (output == nullptr || size > static_cast<size_t>(std::numeric_limits<jint>::max())) {
        return TQ_VFS_INVALID;
    }
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    if (!ready(env)) {
        return TQ_VFS_INVALID;
    }
    jbyteArray temporary = env->NewByteArray(static_cast<jsize>(size));
    if (temporary == nullptr) {
        return TQ_VFS_ERROR;
    }
    const jint count = env->CallStaticIntMethod(
        g_backend,
        g_read,
        static_cast<jlong>(handle),
        temporary,
        static_cast<jint>(size));
    if (count > 0) {
        env->GetByteArrayRegion(
            temporary,
            0,
            count,
            static_cast<jbyte*>(output));
    }
    env->DeleteLocalRef(temporary);
    return count;
}

extern "C" TQ_VFS_API int64_t tq_vfs_seek(
    int64_t handle,
    int64_t offset,
    int whence) {
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    return ready(env)
        ? env->CallStaticLongMethod(g_backend, g_seek, handle, offset, whence)
        : TQ_VFS_INVALID;
}

extern "C" TQ_VFS_API int tq_vfs_close(int64_t handle) {
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    return ready(env)
        ? env->CallStaticIntMethod(g_backend, g_close, handle)
        : TQ_VFS_INVALID;
}

extern "C" TQ_VFS_API int tq_vfs_stat_path(
    const char* tree_uri_utf8,
    const char* relative_path_utf8,
    tq_vfs_stat* output) {
    if (output == nullptr) {
        return TQ_VFS_INVALID;
    }
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    if (!ready(env)) {
        return TQ_VFS_INVALID;
    }
    jbyteArray tree = bytes(env, tree_uri_utf8);
    jbyteArray path = bytes(env, relative_path_utf8);
    jlongArray result = static_cast<jlongArray>(
        env->CallStaticObjectMethod(g_backend, g_stat, tree, path));
    env->DeleteLocalRef(tree);
    env->DeleteLocalRef(path);
    if (result == nullptr || env->GetArrayLength(result) != 6) {
        return TQ_VFS_ERROR;
    }
    jlong values[6]{};
    env->GetLongArrayRegion(result, 0, 6, values);
    env->DeleteLocalRef(result);
    if (values[0] != 0) {
        return static_cast<int>(values[0]);
    }
    output->exists = static_cast<int>(values[1]);
    output->directory = static_cast<int>(values[2]);
    output->size = values[3];
    output->modified_millis = values[4];
    output->provider_flags = values[5];
    return TQ_VFS_OK;
}

extern "C" TQ_VFS_API int tq_vfs_list(
    const char* tree_uri_utf8,
    const char* relative_path_utf8,
    tq_vfs_list_callback callback,
    void* user_data) {
    if (callback == nullptr) {
        return TQ_VFS_INVALID;
    }
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    if (!ready(env)) {
        return TQ_VFS_INVALID;
    }
    jbyteArray tree = bytes(env, tree_uri_utf8);
    jbyteArray path = bytes(env, relative_path_utf8);
    jobjectArray names = static_cast<jobjectArray>(
        env->CallStaticObjectMethod(g_backend, g_list_framed, tree, path));
    env->DeleteLocalRef(tree);
    env->DeleteLocalRef(path);
    if (names == nullptr || env->GetArrayLength(names) == 0) {
        return TQ_VFS_ERROR;
    }
    jbyteArray frame = static_cast<jbyteArray>(env->GetObjectArrayElement(names, 0));
    if (frame == nullptr || env->GetArrayLength(frame) != 4) {
        if (frame != nullptr) env->DeleteLocalRef(frame);
        env->DeleteLocalRef(names);
        return TQ_VFS_ERROR;
    }
    jbyte status_bytes[4]{};
    env->GetByteArrayRegion(frame, 0, 4, status_bytes);
    env->DeleteLocalRef(frame);
    const std::uint32_t unsigned_status =
        (static_cast<std::uint32_t>(status_bytes[0]) & 0xffU) << 24 |
        (static_cast<std::uint32_t>(status_bytes[1]) & 0xffU) << 16 |
        (static_cast<std::uint32_t>(status_bytes[2]) & 0xffU) << 8 |
        (static_cast<std::uint32_t>(status_bytes[3]) & 0xffU);
    const int status = static_cast<int>(static_cast<std::int32_t>(unsigned_status));
    if (status != TQ_VFS_OK && status != TQ_VFS_ERROR && status != TQ_VFS_PERMISSION &&
        status != TQ_VFS_NOT_FOUND && status != TQ_VFS_INVALID) {
        env->DeleteLocalRef(names);
        return TQ_VFS_ERROR;
    }
    if (status != TQ_VFS_OK) {
        env->DeleteLocalRef(names);
        return status;
    }
    const jsize count = env->GetArrayLength(names);
    for (jsize index = 1; index < count; ++index) {
        jbyteArray name =
            static_cast<jbyteArray>(env->GetObjectArrayElement(names, index));
        if (name == nullptr) {
            env->DeleteLocalRef(names);
            return TQ_VFS_ERROR;
        }
        const jsize length = env->GetArrayLength(name);
        std::vector<char> utf8(static_cast<std::size_t>(length) + 1, '\0');
        if (length > 0) {
            env->GetByteArrayRegion(
                name,
                0,
                length,
                reinterpret_cast<jbyte*>(utf8.data()));
        }
        if (std::find(utf8.begin(), utf8.end() - 1, '\0') != utf8.end() - 1) {
            env->DeleteLocalRef(name);
            env->DeleteLocalRef(names);
            return TQ_VFS_INVALID;
        }
        const int callback_status = callback(utf8.data(), user_data);
        env->DeleteLocalRef(name);
        if (callback_status != 0) {
            env->DeleteLocalRef(names);
            return callback_status;
        }
    }
    env->DeleteLocalRef(names);
    return TQ_VFS_OK;
}

extern "C" TQ_VFS_API int tq_vfs_mkdir(
    const char* tree_uri_utf8,
    const char* relative_path_utf8) {
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    jbyteArray tree = ready(env) ? bytes(env, tree_uri_utf8) : nullptr;
    jbyteArray path = ready(env) ? bytes(env, relative_path_utf8) : nullptr;
    const int result = call_mutation(env, g_mkdir, tree, path);
    if (tree != nullptr) env->DeleteLocalRef(tree);
    if (path != nullptr) env->DeleteLocalRef(path);
    return result;
}

extern "C" TQ_VFS_API int tq_vfs_rename(
    const char* tree_uri_utf8,
    const char* relative_path_utf8,
    const char* new_name_utf8) {
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    if (!ready(env)) {
        return TQ_VFS_INVALID;
    }
    jbyteArray tree = bytes(env, tree_uri_utf8);
    jbyteArray path = bytes(env, relative_path_utf8);
    jbyteArray name = bytes(env, new_name_utf8);
    const int result = tree == nullptr || path == nullptr || name == nullptr
        ? TQ_VFS_INVALID
        : env->CallStaticIntMethod(g_backend, g_rename, tree, path, name);
    if (tree != nullptr) env->DeleteLocalRef(tree);
    if (path != nullptr) env->DeleteLocalRef(path);
    if (name != nullptr) env->DeleteLocalRef(name);
    return result;
}

extern "C" TQ_VFS_API int tq_vfs_delete(
    const char* tree_uri_utf8,
    const char* relative_path_utf8) {
    AttachedEnv attached;
    JNIEnv* env = attached.get();
    jbyteArray tree = ready(env) ? bytes(env, tree_uri_utf8) : nullptr;
    jbyteArray path = ready(env) ? bytes(env, relative_path_utf8) : nullptr;
    const int result = call_mutation(env, g_delete, tree, path);
    if (tree != nullptr) env->DeleteLocalRef(tree);
    if (path != nullptr) env->DeleteLocalRef(path);
    return result;
}

extern "C" TQ_VFS_API const char* twinquill_native_vfs_build_probe() {
    return "twinquill-native-vfs-source-build";
}

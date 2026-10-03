/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#ifndef TWINQUILL_VFS_H
#define TWINQUILL_VFS_H

#include <stddef.h>
#include <stdint.h>

#if defined(__GNUC__)
#define TQ_VFS_API __attribute__((visibility("default")))
#else
#define TQ_VFS_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

enum {
    TQ_VFS_OK = 0,
    TQ_VFS_ERROR = -1,
    TQ_VFS_PERMISSION = -2,
    TQ_VFS_NOT_FOUND = -3,
    TQ_VFS_UNSUPPORTED = -4,
    TQ_VFS_INVALID = -5,
};

enum {
    TQ_VFS_OPEN_READ = 1,
    // Same read-only access; cap non-seekable provider spooling at 128 MiB.
    TQ_VFS_OPEN_READ_BOUNDED = 3,
};

enum {
    TQ_VFS_SEEK_SET = 0,
    TQ_VFS_SEEK_CUR = 1,
    TQ_VFS_SEEK_END = 2,
};

typedef struct tq_vfs_stat {
    int exists;
    int directory;
    int64_t size;
    int64_t modified_millis;
    int64_t provider_flags;
} tq_vfs_stat;

typedef int (*tq_vfs_list_callback)(const char* utf8_name, void* user_data);

// Optional, same-thread read lookup scope. Share bounded directory snapshots
// only until end_lookup; document stat/open still checks current access.
TQ_VFS_API int tq_vfs_begin_lookup(void);
TQ_VFS_API void tq_vfs_end_lookup(void);

TQ_VFS_API int64_t tq_vfs_open(
    const char* tree_uri_utf8,
    const char* relative_path_utf8,
    int flags);
TQ_VFS_API int64_t tq_vfs_read(int64_t handle, void* output, size_t size);
TQ_VFS_API int64_t tq_vfs_seek(int64_t handle, int64_t offset, int whence);
TQ_VFS_API int tq_vfs_close(int64_t handle);
TQ_VFS_API int tq_vfs_stat_path(
    const char* tree_uri_utf8,
    const char* relative_path_utf8,
    tq_vfs_stat* output);
TQ_VFS_API int tq_vfs_list(
    const char* tree_uri_utf8,
    const char* relative_path_utf8,
    tq_vfs_list_callback callback,
    void* user_data);
// Read-only enumeration capped before the Java result frame is allocated.
TQ_VFS_API int tq_vfs_list_bounded(
    const char* tree_uri_utf8,
    const char* relative_path_utf8,
    tq_vfs_list_callback callback,
    void* user_data);
TQ_VFS_API int tq_vfs_mkdir(
    const char* tree_uri_utf8,
    const char* relative_path_utf8);
TQ_VFS_API int tq_vfs_rename(
    const char* tree_uri_utf8,
    const char* relative_path_utf8,
    const char* new_name_utf8);
TQ_VFS_API int tq_vfs_delete(
    const char* tree_uri_utf8,
    const char* relative_path_utf8);

TQ_VFS_API const char* twinquill_native_vfs_build_probe(void);

#ifdef __cplusplus
}
#endif

#endif

/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_xp3.h"

#include <algorithm>
#include <cstdint>
#include <limits>
#include <memory>
#include <string>

#include "krkr_xp3_archive.h"

namespace twinquill::krkr {
namespace {

constexpr std::uint64_t kMaxStartupSize = 8 * 1024 * 1024;

bool fits_size_t(std::uint64_t value) {
    return value <= static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max());
}

int read_stream_to_string(ReadOnlyStream* stream, std::string* source) {
    if (stream == nullptr || source == nullptr) {
        return 34;
    }
    const std::uint64_t source_size = stream->GetSize();
    if (source_size > kMaxStartupSize || !fits_size_t(source_size)) {
        return 34;
    }
    const std::size_t checked_size = static_cast<std::size_t>(source_size);
    source->assign(checked_size, '\0');
    char* cursor = source->empty() ? nullptr : source->data();
    std::size_t remaining = checked_size;
    while (remaining > 0) {
        const std::size_t request = std::min(
            remaining,
            static_cast<std::size_t>(std::numeric_limits<TJS::tjs_uint>::max()));
        const TJS::tjs_uint count = stream->Read(cursor, static_cast<TJS::tjs_uint>(request));
        if (count == 0 || static_cast<std::size_t>(count) > request) {
            source->clear();
            return 34;
        }
        cursor += count;
        remaining -= static_cast<std::size_t>(count);
    }
    return 0;
}

}  // namespace

int read_xp3_startup(const StorageSpec& archive, std::string* source) {
    if (source == nullptr) {
        return 30;
    }
    std::shared_ptr<Xp3Archive> xp3;
    const int open_result = Xp3Archive::Open(archive, &xp3);
    if (open_result != 0) {
        return open_result;
    }
    std::unique_ptr<ReadOnlyStream> startup;
    const int entry_result = xp3->OpenEntryStreamByName("startup.tjs", &startup);
    if (entry_result != 0) {
        return entry_result;
    }
    return read_stream_to_string(startup.get(), source);
}

int read_xp3_startup(const char* archive_path, std::string* source) {
    if (archive_path == nullptr) {
        return 30;
    }
    return read_xp3_startup(StorageSpec::LocalFile(archive_path), source);
}

}  // namespace twinquill::krkr

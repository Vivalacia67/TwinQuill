/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include "tjs.h"

namespace twinquill::krkr {

enum class StorageBackend {
    kLocalFile,
    kOwnedFileDescriptor,
};

struct StorageSpec {
    StorageBackend backend;
    std::string path;
    int descriptor;

    static StorageSpec LocalFile(std::string path);
    static StorageSpec OwnedFileDescriptor(int descriptor);
};

class ReadOnlyStream : public TJS::tTJSBinaryStream {
public:
    virtual ~ReadOnlyStream() = default;
};

int open_read_only_stream(
    const StorageSpec& spec,
    std::unique_ptr<ReadOnlyStream>* output);

int read_storage_file(
    const StorageSpec& spec,
    std::uint64_t maximum_size,
    std::string* output);

}  // namespace twinquill::krkr

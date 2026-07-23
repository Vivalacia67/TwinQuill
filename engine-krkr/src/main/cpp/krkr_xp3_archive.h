/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "krkr_storage.h"

namespace twinquill::krkr {

struct Xp3Segment {
    std::uint32_t method = 0;
    std::uint64_t archive_offset = 0;
    std::uint64_t original_size = 0;
    std::uint64_t archived_size = 0;
};

struct Xp3Entry {
    std::string name;
    std::uint32_t flags = 0;
    bool has_hash = false;
    std::uint32_t hash = 0;
    std::uint64_t original_size = 0;
    std::uint64_t archived_size = 0;
    std::vector<Xp3Segment> segments;

    bool IsProtected() const;

    std::string lookup_name;
};

class Xp3Archive final : public std::enable_shared_from_this<Xp3Archive> {
public:
    static int Open(const StorageSpec& spec, std::shared_ptr<Xp3Archive>* output);

    std::size_t EntryCount() const;
    const Xp3Entry* EntryAt(std::size_t index) const;
    const Xp3Entry* FindEntryByName(const std::string& name) const;

    int OpenEntryStream(const Xp3Entry& entry, std::unique_ptr<ReadOnlyStream>* output) const;
    int OpenEntryStreamByIndex(std::size_t index, std::unique_ptr<ReadOnlyStream>* output) const;
    int OpenEntryStreamByName(const std::string& name, std::unique_ptr<ReadOnlyStream>* output) const;
    bool ReadArchiveAt(std::uint64_t offset, void* output, std::size_t size) const;

private:
    Xp3Archive() = default;

    friend class Xp3EntryStream;

    int Parse();
    std::shared_ptr<ReadOnlyStream> stream_;
    std::uint64_t file_size_ = 0;
    std::vector<Xp3Entry> entries_;
    mutable std::mutex stream_mutex_;
};

}  // namespace twinquill::krkr


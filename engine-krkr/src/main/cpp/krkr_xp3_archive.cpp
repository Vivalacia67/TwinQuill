/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_xp3_archive.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstring>
#include <limits>
#include <unordered_set>
#include <utility>
#include <vector>

#include "zlib.h"

namespace twinquill::krkr {
namespace {

constexpr std::array<std::uint8_t, 11> kXp3Mark = {
    0x58, 0x50, 0x33, 0x0d, 0x0a, 0x20, 0x0a, 0x1a, 0x8b, 0x67, 0x01,
};
constexpr std::uint64_t kMaxIndexSize = 16 * 1024 * 1024;
constexpr std::uint64_t kMaxCompressedIndexSize = 64 * 1024 * 1024;
constexpr std::uint64_t kMaxEntrySize = 4ULL * 1024 * 1024 * 1024;
constexpr std::uint64_t kMaxArchivedEntrySize = 4ULL * 1024 * 1024 * 1024;
constexpr std::uint64_t kMaxInflatedSegmentSize = 64 * 1024 * 1024;
constexpr std::uint64_t kMaxCompressedSegmentSize = 128 * 1024 * 1024;
constexpr std::size_t kXp3IndexBaseHeaderSize = 9;
constexpr std::size_t kMaxXp3IndexBlocks = 64;
constexpr std::size_t kMaxEntries = 65536;
constexpr std::size_t kMaxSegmentsPerEntry = 8192;
constexpr std::uint32_t kProtectedFile = 1U << 31U;
constexpr std::uint32_t kXp3MethodMask = 0x07U;
constexpr std::uint32_t kXp3Continuation = 0x80U;
constexpr std::uint32_t kXp3IndexSupportedFlags = kXp3Continuation | kXp3MethodMask;

std::uint16_t read_u16(const std::uint8_t* data) {
    return static_cast<std::uint16_t>(data[0]) |
        (static_cast<std::uint16_t>(data[1]) << 8U);
}

std::uint32_t read_u32(const std::uint8_t* data) {
    return static_cast<std::uint32_t>(data[0]) |
        (static_cast<std::uint32_t>(data[1]) << 8U) |
        (static_cast<std::uint32_t>(data[2]) << 16U) |
        (static_cast<std::uint32_t>(data[3]) << 24U);
}

std::uint64_t read_u64(const std::uint8_t* data) {
    std::uint64_t value = 0;
    for (unsigned int index = 0; index < 8; ++index) {
        value |= static_cast<std::uint64_t>(data[index]) << (index * 8U);
    }
    return value;
}

bool add_fits(std::uint64_t left, std::uint64_t right, std::uint64_t limit) {
    return left <= limit && right <= limit - left;
}

bool fits_size_t(std::uint64_t value) {
    return value <= static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max());
}

bool fits_zlib_uint(std::size_t value) {
    return value <= static_cast<std::size_t>(std::numeric_limits<uInt>::max());
}

bool fits_tjs_int64(std::uint64_t value) {
    return value <= static_cast<std::uint64_t>(std::numeric_limits<TJS::tjs_int64>::max());
}

bool bounded_size(std::uint64_t value, std::uint64_t maximum, std::size_t* output) {
    if (output == nullptr || value > maximum || !fits_size_t(value)) {
        return false;
    }
    *output = static_cast<std::size_t>(value);
    return true;
}

bool append_utf8(std::uint32_t code_point, std::string* output) {
    if (code_point <= 0x7f) {
        output->push_back(static_cast<char>(code_point));
        return true;
    }
    if (code_point <= 0x7ff) {
        output->push_back(static_cast<char>(0xc0U | (code_point >> 6U)));
        output->push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
        return true;
    }
    if (code_point <= 0xffff) {
        output->push_back(static_cast<char>(0xe0U | (code_point >> 12U)));
        output->push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3fU)));
        output->push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
        return true;
    }
    if (code_point <= 0x10ffff) {
        output->push_back(static_cast<char>(0xf0U | (code_point >> 18U)));
        output->push_back(static_cast<char>(0x80U | ((code_point >> 12U) & 0x3fU)));
        output->push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3fU)));
        output->push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
        return true;
    }
    return false;
}

bool decode_utf16le_name(const std::uint8_t* data, std::uint16_t length, std::string* output) {
    if (data == nullptr || output == nullptr) {
        return false;
    }
    output->clear();
    for (std::uint16_t index = 0; index < length; ++index) {
        std::uint32_t character = read_u16(data + static_cast<std::size_t>(index) * 2);
        if (character >= 0xd800 && character <= 0xdbff) {
            if (index + 1 >= length) {
                return false;
            }
            const std::uint32_t low = read_u16(data + static_cast<std::size_t>(index + 1) * 2);
            if (low < 0xdc00 || low > 0xdfff) {
                return false;
            }
            character = 0x10000U + ((character - 0xd800U) << 10U) + (low - 0xdc00U);
            ++index;
        } else if (character >= 0xdc00 && character <= 0xdfff) {
            return false;
        }
        if (!append_utf8(character == '\\' ? '/' : character, output)) {
            return false;
        }
    }
    return true;
}

std::string lookup_name_for(const std::string& name) {
    std::string lookup;
    lookup.reserve(name.size());
    std::size_t position = 0;
    while (position < name.size() && (name[position] == '/' || name[position] == '\\')) {
        ++position;
    }
    for (; position < name.size(); ++position) {
        unsigned char character = static_cast<unsigned char>(name[position]);
        if (character == '\\') {
            character = '/';
        } else if (character <= 0x7f) {
            character = static_cast<unsigned char>(std::tolower(character));
        }
        lookup.push_back(static_cast<char>(character));
    }
    return lookup;
}

bool read_payload(
    const Xp3Archive& archive,
    std::uint64_t offset,
    std::uint64_t size,
    std::uint64_t maximum,
    std::vector<std::uint8_t>* output);

bool inflate_exact(
    const std::vector<std::uint8_t>& compressed,
    std::uint64_t expected_size,
    std::uint64_t maximum_size,
    std::vector<std::uint8_t>* output) {
    std::size_t checked_size = 0;
    if (output == nullptr ||
        !bounded_size(expected_size, maximum_size, &checked_size) ||
        !fits_zlib_uint(compressed.size()) ||
        !fits_zlib_uint(checked_size)) {
        return false;
    }

    output->assign(checked_size, 0);
    std::array<Bytef, 1> empty_output{};
    z_stream stream{};
    stream.next_in = const_cast<Bytef*>(compressed.empty() ? nullptr : compressed.data());
    stream.avail_in = static_cast<uInt>(compressed.size());
    stream.next_out = checked_size == 0 ? empty_output.data() : output->data();
    stream.avail_out = checked_size == 0 ? static_cast<uInt>(empty_output.size()) :
        static_cast<uInt>(checked_size);

    if (inflateInit(&stream) != Z_OK) {
        return false;
    }
    const int result = inflate(&stream, Z_FINISH);
    const int end_result = inflateEnd(&stream);
    if (result != Z_STREAM_END || end_result != Z_OK) {
        return false;
    }
    return stream.total_in == static_cast<uLong>(compressed.size()) &&
        stream.total_out == static_cast<uLong>(checked_size) &&
        (checked_size == 0 || stream.avail_out == 0);
}

struct Chunk {
    std::array<char, 4> name{};
    const std::uint8_t* data = nullptr;
    std::size_t size = 0;
};

bool next_chunk(
    const std::uint8_t* data,
    std::size_t size,
    std::size_t* position,
    Chunk* chunk) {
    if (data == nullptr || position == nullptr || chunk == nullptr ||
        *position > size || size - *position < 12) {
        return false;
    }
    const std::uint8_t* header = data + *position;
    const std::uint64_t chunk_size = read_u64(header + 4);
    if (chunk_size > size - *position - 12) {
        return false;
    }
    std::memcpy(chunk->name.data(), header, 4);
    chunk->data = header + 12;
    chunk->size = static_cast<std::size_t>(chunk_size);
    *position += 12 + chunk->size;
    return true;
}

bool chunk_named(const Chunk& chunk, const char* name) {
    return std::memcmp(chunk.name.data(), name, 4) == 0;
}

int read_index_payload(
    const Xp3Archive& archive,
    std::uint64_t file_size,
    std::uint64_t index_offset,
    std::uint8_t* flags,
    std::vector<std::uint8_t>* index,
    std::uint64_t* next_offset_position) {
    if (flags == nullptr || index == nullptr || next_offset_position == nullptr) {
        return 34;
    }
    std::array<std::uint8_t, 17> header{};
    if (!add_fits(index_offset, 9, file_size) ||
        !archive.ReadArchiveAt(index_offset, header.data(), 9)) {
        return 34;
    }
    *flags = header[0];
    if ((*flags & ~kXp3IndexSupportedFlags) != 0) {
        return 33;
    }
    const std::uint32_t method = *flags & kXp3MethodMask;
    if (method == 0) {
        const std::uint64_t raw_size = read_u64(header.data() + 1);
        if (!add_fits(index_offset + 9, raw_size, file_size) ||
            !read_payload(archive, index_offset + 9, raw_size, kMaxIndexSize, index)) {
            return 34;
        }
        *next_offset_position = index_offset + 9 + raw_size;
        return 0;
    }
    if (method != 1) {
        return 33;
    }

    if (!add_fits(index_offset, 17, file_size) ||
        !archive.ReadArchiveAt(index_offset + 9, header.data() + 9, 8)) {
        return 34;
    }
    const std::uint64_t compressed_size = read_u64(header.data() + 1);
    const std::uint64_t uncompressed_size = read_u64(header.data() + 9);
    if (!add_fits(index_offset + 17, compressed_size, file_size)) {
        return 34;
    }
    std::vector<std::uint8_t> compressed;
    if (!read_payload(
            archive,
            index_offset + 17,
            compressed_size,
            kMaxCompressedIndexSize,
            &compressed) ||
        !inflate_exact(compressed, uncompressed_size, kMaxIndexSize, index)) {
        return 34;
    }
    *next_offset_position = index_offset + 17 + compressed_size;
    return 0;
}

bool compute_seek_position(
    std::uint64_t cursor,
    std::uint64_t size,
    TJS::tjs_int64 offset,
    TJS::tjs_int whence,
    std::uint64_t* output) {
    if (output == nullptr) {
        return false;
    }

    std::uint64_t base = 0;
    if (whence == TJS_BS_SEEK_SET) {
        base = 0;
    } else if (whence == TJS_BS_SEEK_CUR) {
        base = cursor;
    } else if (whence == TJS_BS_SEEK_END) {
        base = size;
    } else {
        return false;
    }

    std::uint64_t position = 0;
    if (offset >= 0) {
        const auto positive = static_cast<std::uint64_t>(offset);
        if (!add_fits(base, positive, std::numeric_limits<std::uint64_t>::max())) {
            return false;
        }
        position = base + positive;
    } else {
        const auto negative = static_cast<std::uint64_t>(-(offset + 1)) + 1;
        if (negative > base) {
            return false;
        }
        position = base - negative;
    }

    if (position > size || !fits_tjs_int64(position)) {
        return false;
    }
    *output = position;
    return true;
}

class Xp3EntryStream final : public ReadOnlyStream {
public:
    Xp3EntryStream(std::shared_ptr<const Xp3Archive> archive, const Xp3Entry* entry)
        : archive_(std::move(archive)), entry_(entry) {
    }

    TJS::tjs_uint64 TJS_INTF_METHOD Seek(
        TJS::tjs_int64 offset,
        TJS::tjs_int whence) override {
        if (entry_ == nullptr) {
            return 0;
        }
        std::uint64_t position = 0;
        if (compute_seek_position(cursor_, entry_->original_size, offset, whence, &position)) {
            cursor_ = position;
        }
        return static_cast<TJS::tjs_uint64>(cursor_);
    }

    TJS::tjs_uint TJS_INTF_METHOD Read(void* buffer, TJS::tjs_uint read_size) override {
        if (archive_ == nullptr || entry_ == nullptr ||
            (buffer == nullptr && read_size != 0) ||
            read_size == 0 || cursor_ >= entry_->original_size) {
            return 0;
        }

        auto* output = static_cast<std::uint8_t*>(buffer);
        std::uint64_t segment_start = 0;
        TJS::tjs_uint total_read = 0;
        std::uint64_t remaining = std::min<std::uint64_t>(
            read_size,
            entry_->original_size - cursor_);
        for (std::size_t index = 0; index < entry_->segments.size() && remaining > 0; ++index) {
            const Xp3Segment& segment = entry_->segments[index];
            const std::uint64_t segment_end = segment_start + segment.original_size;
            if (cursor_ >= segment_end) {
                segment_start = segment_end;
                continue;
            }

            const std::uint64_t offset_in_segment = cursor_ - segment_start;
            const std::uint64_t bytes_to_read = std::min(
                remaining,
                segment.original_size - offset_in_segment);
            std::size_t checked_size = 0;
            if (!bounded_size(
                    bytes_to_read,
                    std::numeric_limits<TJS::tjs_uint>::max(),
                    &checked_size)) {
                break;
            }

            if (!ReadSegment(index, offset_in_segment, output, checked_size)) {
                break;
            }

            output += checked_size;
            cursor_ += checked_size;
            remaining -= checked_size;
            total_read += static_cast<TJS::tjs_uint>(checked_size);
            segment_start = segment_end;
        }
        return total_read;
    }

    TJS::tjs_uint TJS_INTF_METHOD Write(
        const void* /*buffer*/,
        TJS::tjs_uint /*write_size*/) override {
        return 0;
    }

    TJS::tjs_uint64 TJS_INTF_METHOD GetSize() override {
        return entry_ == nullptr ? 0 : static_cast<TJS::tjs_uint64>(entry_->original_size);
    }

private:
    bool ReadSegment(
        std::size_t segment_index,
        std::uint64_t offset,
        std::uint8_t* output,
        std::size_t size) {
        if (segment_index >= entry_->segments.size() || (output == nullptr && size != 0)) {
            return false;
        }
        const Xp3Segment& segment = entry_->segments[segment_index];
        if (!add_fits(offset, size, segment.original_size)) {
            return false;
        }
        if (segment.method == 0) {
            return size == 0 || archive_->ReadArchiveAt(segment.archive_offset + offset, output, size);
        }
        if (segment.method != 1 || !LoadCompressedSegment(segment_index)) {
            return false;
        }
        if (size != 0) {
            std::memcpy(output, segment_cache_.data() + offset, size);
        }
        return true;
    }

    bool LoadCompressedSegment(std::size_t segment_index) {
        if (cached_segment_index_ == segment_index) {
            return true;
        }
        if (segment_index >= entry_->segments.size()) {
            return false;
        }
        const Xp3Segment& segment = entry_->segments[segment_index];
        std::vector<std::uint8_t> compressed;
        if (!read_payload(
                *archive_,
                segment.archive_offset,
                segment.archived_size,
                kMaxCompressedSegmentSize,
                &compressed) ||
            !inflate_exact(
                compressed,
                segment.original_size,
                kMaxInflatedSegmentSize,
                &segment_cache_)) {
            cached_segment_index_ = kNoCachedSegment;
            segment_cache_.clear();
            return false;
        }
        cached_segment_index_ = segment_index;
        return true;
    }

    static constexpr std::size_t kNoCachedSegment = std::numeric_limits<std::size_t>::max();

    std::shared_ptr<const Xp3Archive> archive_;
    const Xp3Entry* entry_;
    std::uint64_t cursor_ = 0;
    std::size_t cached_segment_index_ = kNoCachedSegment;
    std::vector<std::uint8_t> segment_cache_;
};

bool read_payload(
    const Xp3Archive& archive,
    std::uint64_t offset,
    std::uint64_t size,
    std::uint64_t maximum,
    std::vector<std::uint8_t>* output) {
    std::size_t checked_size = 0;
    if (output == nullptr || !bounded_size(size, maximum, &checked_size)) {
        return false;
    }
    output->assign(checked_size, 0);
    return checked_size == 0 || archive.ReadArchiveAt(offset, output->data(), checked_size);
}

int parse_file_chunk(const Chunk& file, Xp3Entry* entry) {
    if (entry == nullptr) {
        return 34;
    }
    const Chunk* info = nullptr;
    const Chunk* segments = nullptr;
    const Chunk* hash = nullptr;
    std::vector<Chunk> subchunks;
    std::size_t position = 0;
    while (position < file.size) {
        Chunk chunk{};
        if (!next_chunk(file.data, file.size, &position, &chunk)) {
            return 34;
        }
        subchunks.push_back(chunk);
    }
    for (const Chunk& chunk : subchunks) {
        if (chunk_named(chunk, "info")) {
            info = &chunk;
        } else if (chunk_named(chunk, "segm")) {
            segments = &chunk;
        } else if (chunk_named(chunk, "adlr")) {
            hash = &chunk;
        }
    }
    if (info == nullptr || segments == nullptr || info->size < 22 ||
        segments->size % 28 != 0) {
        return 34;
    }

    entry->flags = read_u32(info->data);
    if ((entry->flags & ~kProtectedFile) != 0) {
        return 33;
    }
    entry->original_size = read_u64(info->data + 4);
    entry->archived_size = read_u64(info->data + 12);
    if (entry->original_size > kMaxEntrySize ||
        entry->archived_size > kMaxArchivedEntrySize ||
        !fits_tjs_int64(entry->original_size)) {
        return 34;
    }
    const std::uint16_t name_length = read_u16(info->data + 20);
    const std::uint64_t name_bytes = static_cast<std::uint64_t>(name_length) * 2;
    if (!add_fits(22, name_bytes, info->size) ||
        !decode_utf16le_name(info->data + 22, name_length, &entry->name)) {
        return 34;
    }
    entry->lookup_name = lookup_name_for(entry->name);

    if (hash != nullptr) {
        if (hash->size != 4) {
            return 34;
        }
        entry->has_hash = true;
        entry->hash = read_u32(hash->data);
    }

    const std::size_t segment_count = segments->size / 28;
    if (segment_count > kMaxSegmentsPerEntry ||
        (segment_count == 0 && (entry->original_size != 0 || entry->archived_size != 0))) {
        return 34;
    }
    entry->segments.reserve(segment_count);
    std::uint64_t total_original = 0;
    std::uint64_t total_archived = 0;
    for (std::size_t offset = 0; offset < segments->size; offset += 28) {
        const std::uint8_t* data = segments->data + offset;
        Xp3Segment segment{};
        const std::uint32_t flags = read_u32(data);
        if ((flags & ~kXp3MethodMask) != 0) {
            return 33;
        }
        segment.method = flags & kXp3MethodMask;
        if (segment.method != 0 && segment.method != 1) {
            return 33;
        }
        segment.archive_offset = read_u64(data + 4);
        segment.original_size = read_u64(data + 12);
        segment.archived_size = read_u64(data + 20);
        if (segment.method == 0 && segment.original_size != segment.archived_size) {
            return 33;
        }
        if (!add_fits(total_original, segment.original_size, kMaxEntrySize) ||
            !add_fits(total_archived, segment.archived_size, kMaxArchivedEntrySize)) {
            return 34;
        }
        total_original += segment.original_size;
        total_archived += segment.archived_size;
        entry->segments.push_back(segment);
    }
    if (total_original != entry->original_size || total_archived != entry->archived_size) {
        return 34;
    }
    return 0;
}

int parse_index(const std::vector<std::uint8_t>& index, std::vector<Xp3Entry>* entries) {
    if (entries == nullptr) {
        return 34;
    }
    std::size_t position = 0;
    while (position < index.size()) {
        Chunk chunk{};
        if (!next_chunk(index.data(), index.size(), &position, &chunk)) {
            return 34;
        }
        if (!chunk_named(chunk, "File")) {
            continue;
        }
        if (entries->size() >= kMaxEntries) {
            return 34;
        }
        Xp3Entry entry{};
        const int result = parse_file_chunk(chunk, &entry);
        if (result != 0) {
            return result;
        }
        entries->push_back(std::move(entry));
    }
    return 0;
}

}  // namespace

bool Xp3Entry::IsProtected() const {
    return (flags & kProtectedFile) != 0;
}

int Xp3Archive::Open(const StorageSpec& spec, std::shared_ptr<Xp3Archive>* output) {
    if (output == nullptr) {
        return 30;
    }
    output->reset();
    std::unique_ptr<ReadOnlyStream> input;
    if (open_read_only_stream(spec, &input) != 0 || input == nullptr) {
        return 30;
    }
    std::shared_ptr<Xp3Archive> archive(new Xp3Archive());
    archive->stream_ = std::shared_ptr<ReadOnlyStream>(std::move(input));
    archive->file_size_ = archive->stream_->GetSize();
    const int result = archive->Parse();
    if (result != 0) {
        return result;
    }
    *output = std::move(archive);
    return 0;
}

std::size_t Xp3Archive::EntryCount() const {
    return entries_.size();
}

const Xp3Entry* Xp3Archive::EntryAt(std::size_t index) const {
    return index < entries_.size() ? &entries_[index] : nullptr;
}

const Xp3Entry* Xp3Archive::FindEntryByName(const std::string& name) const {
    const std::string lookup = lookup_name_for(name);
    for (const Xp3Entry& entry : entries_) {
        if (entry.lookup_name == lookup) {
            return &entry;
        }
    }
    return nullptr;
}

int Xp3Archive::OpenEntryStream(
    const Xp3Entry& entry,
    std::unique_ptr<ReadOnlyStream>* output) const {
    if (output == nullptr) {
        return 34;
    }
    output->reset();
    const Xp3Entry* stored_entry = nullptr;
    for (const Xp3Entry& candidate : entries_) {
        if (&candidate == &entry) {
            stored_entry = &candidate;
            break;
        }
    }
    if (stored_entry == nullptr) {
        return 34;
    }
    if (stored_entry->IsProtected()) {
        return 35;
    }
    output->reset(new Xp3EntryStream(shared_from_this(), stored_entry));
    return 0;
}

int Xp3Archive::OpenEntryStreamByIndex(
    std::size_t index,
    std::unique_ptr<ReadOnlyStream>* output) const {
    const Xp3Entry* entry = EntryAt(index);
    return entry == nullptr ? 34 : OpenEntryStream(*entry, output);
}

int Xp3Archive::OpenEntryStreamByName(
    const std::string& name,
    std::unique_ptr<ReadOnlyStream>* output) const {
    const Xp3Entry* entry = FindEntryByName(name);
    return entry == nullptr ? 31 : OpenEntryStream(*entry, output);
}

int Xp3Archive::Parse() {
    if (file_size_ < 19) {
        return 32;
    }
    std::array<std::uint8_t, 19> header{};
    if (!ReadArchiveAt(0, header.data(), header.size()) ||
        !std::equal(kXp3Mark.begin(), kXp3Mark.end(), header.begin())) {
        return 32;
    }

    std::uint64_t index_offset = read_u64(header.data() + kXp3Mark.size());
    std::unordered_set<std::uint64_t> visited_index_offsets;
    std::size_t index_blocks_read = 0;
    for (;;) {
        if (index_blocks_read >= kMaxXp3IndexBlocks ||
            !visited_index_offsets.insert(index_offset).second ||
            !add_fits(index_offset, kXp3IndexBaseHeaderSize, file_size_)) {
            return 34;
        }
        ++index_blocks_read;

        std::uint8_t flags = 0;
        std::vector<std::uint8_t> index;
        std::uint64_t next_offset_position = 0;
        const int payload_result = read_index_payload(
            *this,
            file_size_,
            index_offset,
            &flags,
            &index,
            &next_offset_position);
        if (payload_result != 0) {
            return payload_result;
        }
        const std::size_t old_entry_count = entries_.size();
        const int index_result = parse_index(index, &entries_);
        if (index_result != 0) {
            return index_result;
        }
        for (std::size_t entry_index = old_entry_count; entry_index < entries_.size(); ++entry_index) {
            for (const Xp3Segment& segment : entries_[entry_index].segments) {
                if (!add_fits(segment.archive_offset, segment.archived_size, file_size_)) {
                    return 34;
                }
            }
        }

        if ((flags & kXp3Continuation) == 0) {
            return 0;
        }
        std::array<std::uint8_t, 8> next_offset{};
        if (!ReadArchiveAt(next_offset_position, next_offset.data(), next_offset.size())) {
            return 34;
        }
        const std::uint64_t next_index_offset = read_u64(next_offset.data());
        if (!add_fits(next_index_offset, kXp3IndexBaseHeaderSize, file_size_) ||
            visited_index_offsets.find(next_index_offset) != visited_index_offsets.end()) {
            return 34;
        }
        index_offset = next_index_offset;
    }
}

bool Xp3Archive::ReadArchiveAt(std::uint64_t offset, void* output, std::size_t size) const {
    if (stream_ == nullptr ||
        (output == nullptr && size != 0) ||
        !add_fits(offset, size, file_size_) ||
        !fits_tjs_int64(offset)) {
        return false;
    }
    std::lock_guard<std::mutex> lock(stream_mutex_);
    if (stream_->Seek(static_cast<TJS::tjs_int64>(offset), TJS_BS_SEEK_SET) != offset) {
        return false;
    }

    auto* cursor = static_cast<unsigned char*>(output);
    std::size_t remaining = size;
    while (remaining > 0) {
        const std::size_t request = std::min(
            remaining,
            static_cast<std::size_t>(std::numeric_limits<TJS::tjs_uint>::max()));
        const TJS::tjs_uint count = stream_->Read(cursor, static_cast<TJS::tjs_uint>(request));
        if (count == 0 || static_cast<std::size_t>(count) > request) {
            return false;
        }
        cursor += count;
        remaining -= static_cast<std::size_t>(count);
    }
    return true;
}

}  // namespace twinquill::krkr

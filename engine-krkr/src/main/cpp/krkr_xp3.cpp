/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_xp3.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <string>
#include <unordered_set>
#include <vector>

#include "zlib.h"

namespace twinquill::krkr {
namespace {

constexpr std::array<std::uint8_t, 11> kXp3Mark = {
    0x58, 0x50, 0x33, 0x0d, 0x0a, 0x20, 0x0a, 0x1a, 0x8b, 0x67, 0x01,
};
constexpr std::uint64_t kMaxIndexSize = 16 * 1024 * 1024;
constexpr std::uint64_t kMaxStartupSize = 8 * 1024 * 1024;
constexpr std::uint64_t kMaxCompressedIndexSize = 64 * 1024 * 1024;
constexpr std::uint64_t kMaxCompressedStartupSize = 64 * 1024 * 1024;
constexpr std::size_t kXp3IndexBaseHeaderSize = 9;
constexpr std::size_t kMaxXp3IndexBlocks = 64;
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

bool read_exact_at(
    ReadOnlyStream* input,
    std::uint64_t file_size,
    std::uint64_t offset,
    void* output,
    std::size_t size) {
    if (input == nullptr ||
        (output == nullptr && size != 0) ||
        !add_fits(offset, size, file_size) ||
        !fits_tjs_int64(offset)) {
        return false;
    }
    if (input->Seek(static_cast<TJS::tjs_int64>(offset), TJS_BS_SEEK_SET) != offset) {
        return false;
    }

    auto* cursor = static_cast<unsigned char*>(output);
    std::size_t remaining = size;
    while (remaining > 0) {
        const std::size_t request = std::min(
            remaining,
            static_cast<std::size_t>(std::numeric_limits<TJS::tjs_uint>::max()));
        const TJS::tjs_uint count = input->Read(cursor, static_cast<TJS::tjs_uint>(request));
        if (count == 0 || static_cast<std::size_t>(count) > request) {
            return false;
        }
        cursor += count;
        remaining -= static_cast<std::size_t>(count);
    }
    return true;
}

bool bounded_size(std::uint64_t value, std::uint64_t maximum, std::size_t* output) {
    if (value > maximum || !fits_size_t(value)) {
        return false;
    }
    *output = static_cast<std::size_t>(value);
    return true;
}

bool read_payload(
    ReadOnlyStream* input,
    std::uint64_t file_size,
    std::uint64_t offset,
    std::uint64_t size,
    std::uint64_t maximum,
    std::vector<std::uint8_t>* output) {
    std::size_t checked_size = 0;
    if (!bounded_size(size, maximum, &checked_size)) {
        return false;
    }
    output->assign(checked_size, 0);
    return checked_size == 0 || read_exact_at(input, file_size, offset, output->data(), checked_size);
}

bool inflate_exact(
    const std::vector<std::uint8_t>& compressed,
    std::uint64_t expected_size,
    std::uint64_t maximum_size,
    std::vector<std::uint8_t>* output) {
    std::size_t checked_size = 0;
    if (!bounded_size(expected_size, maximum_size, &checked_size) ||
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
    std::array<char, 4> name;
    const std::uint8_t* data;
    std::size_t size;
};

bool next_chunk(
    const std::uint8_t* data,
    std::size_t size,
    std::size_t* position,
    Chunk* chunk) {
    if (*position > size || size - *position < 12) {
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

bool startup_name(const std::uint8_t* utf16, std::uint16_t length) {
    constexpr char expected[] = "startup.tjs";
    if (length != sizeof(expected) - 1) {
        return false;
    }
    for (std::size_t index = 0; index < length; ++index) {
        const std::uint16_t character = read_u16(utf16 + index * 2);
        if (character > 0x7f ||
            std::tolower(static_cast<unsigned char>(character)) != expected[index]) {
            return false;
        }
    }
    return true;
}

int append_segment(
    const std::uint8_t* data,
    ReadOnlyStream* input,
    std::uint64_t file_size,
    std::uint64_t original_size,
    std::string* source) {
    const std::uint32_t flags = read_u32(data);
    if ((flags & ~kXp3MethodMask) != 0) {
        return 33;
    }
    const std::uint32_t method = flags & kXp3MethodMask;
    const std::uint64_t offset = read_u64(data + 4);
    const std::uint64_t unpacked_size = read_u64(data + 12);
    const std::uint64_t archived_size = read_u64(data + 20);
    if (!add_fits(source->size(), unpacked_size, original_size) ||
        unpacked_size > kMaxStartupSize ||
        archived_size > kMaxCompressedStartupSize ||
        !fits_size_t(unpacked_size)) {
        return 34;
    }

    const std::size_t old_size = source->size();
    const std::size_t checked_unpacked_size = static_cast<std::size_t>(unpacked_size);
    if (method == 0) {
        if (unpacked_size != archived_size) {
            return 33;
        }
        source->resize(old_size + checked_unpacked_size);
        if (checked_unpacked_size != 0 &&
            !read_exact_at(input, file_size, offset, source->data() + old_size, checked_unpacked_size)) {
            return 34;
        }
        return 0;
    }
    if (method != 1) {
        return 33;
    }

    std::vector<std::uint8_t> compressed;
    if (!read_payload(input, file_size, offset, archived_size, kMaxCompressedStartupSize, &compressed)) {
        return 34;
    }
    std::vector<std::uint8_t> unpacked;
    if (!inflate_exact(compressed, unpacked_size, kMaxStartupSize, &unpacked)) {
        return 34;
    }
    source->append(reinterpret_cast<const char*>(unpacked.data()), unpacked.size());
    return source->size() == old_size + checked_unpacked_size ? 0 : 34;
}

int read_file_chunk(
    const Chunk& file,
    ReadOnlyStream* input,
    std::uint64_t file_size,
    std::string* source) {
    const Chunk* info = nullptr;
    const Chunk* segments = nullptr;
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
        }
    }
    if (info == nullptr || segments == nullptr || info->size < 22) {
        return 34;
    }

    const std::uint32_t info_flags = read_u32(info->data);
    const std::uint16_t name_length = read_u16(info->data + 20);
    const std::uint64_t name_bytes = static_cast<std::uint64_t>(name_length) * 2;
    if (!add_fits(22, name_bytes, info->size) ||
        !startup_name(info->data + 22, name_length)) {
        return 31;
    }
    if ((info_flags & kProtectedFile) != 0) {
        return 35;
    }
    if ((info_flags & ~kProtectedFile) != 0) {
        return 33;
    }

    const std::uint64_t original_size = read_u64(info->data + 4);
    if (original_size > kMaxStartupSize || segments->size == 0 || segments->size % 28 != 0) {
        return 34;
    }
    source->clear();
    source->reserve(static_cast<std::size_t>(original_size));
    for (std::size_t segment = 0; segment < segments->size; segment += 28) {
        const int result = append_segment(
            segments->data + segment,
            input,
            file_size,
            original_size,
            source);
        if (result != 0) {
            return result;
        }
    }
    return source->size() == original_size ? 0 : 34;
}

int read_index(
    const std::vector<std::uint8_t>& index,
    ReadOnlyStream* input,
    std::uint64_t file_size,
    std::string* source) {
    std::size_t position = 0;
    while (position < index.size()) {
        Chunk chunk{};
        if (!next_chunk(index.data(), index.size(), &position, &chunk)) {
            return 34;
        }
        if (!chunk_named(chunk, "File")) {
            continue;
        }
        const int result = read_file_chunk(chunk, input, file_size, source);
        if (result != 31) {
            return result;
        }
    }
    return 31;
}

int read_index_payload(
    ReadOnlyStream* input,
    std::uint64_t file_size,
    std::uint64_t index_offset,
    std::uint8_t* flags,
    std::vector<std::uint8_t>* index,
    std::uint64_t* next_offset_position) {
    std::array<std::uint8_t, 17> header{};
    if (!read_exact_at(input, file_size, index_offset, header.data(), 9)) {
        return 34;
    }
    *flags = header[0];
    if ((*flags & ~kXp3IndexSupportedFlags) != 0) {
        return 33;
    }
    const std::uint32_t method = *flags & kXp3MethodMask;
    if (method == 0) {
        const std::uint64_t raw_size = read_u64(header.data() + 1);
        if (!add_fits(index_offset, 9, file_size) ||
            !add_fits(index_offset + 9, raw_size, file_size) ||
            !read_payload(input, file_size, index_offset + 9, raw_size, kMaxIndexSize, index)) {
            return 34;
        }
        *next_offset_position = index_offset + 9 + raw_size;
        return 0;
    }
    if (method != 1) {
        return 33;
    }

    if (!read_exact_at(input, file_size, index_offset + 9, header.data() + 9, 8)) {
        return 34;
    }
    const std::uint64_t compressed_size = read_u64(header.data() + 1);
    const std::uint64_t uncompressed_size = read_u64(header.data() + 9);
    if (!add_fits(index_offset, 17, file_size) ||
        !add_fits(index_offset + 17, compressed_size, file_size)) {
        return 34;
    }
    std::vector<std::uint8_t> compressed;
    if (!read_payload(
            input,
            file_size,
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

}  // namespace

int read_xp3_startup(const StorageSpec& archive, std::string* source) {
    if (source == nullptr) {
        return 30;
    }
    std::unique_ptr<ReadOnlyStream> input;
    if (open_read_only_stream(archive, &input) != 0 || input == nullptr) {
        return 30;
    }
    const std::uint64_t file_size = input->GetSize();
    if (file_size < 19) {
        return 32;
    }
    std::array<std::uint8_t, 19> header{};
    if (!read_exact_at(input.get(), file_size, 0, header.data(), header.size()) ||
        !std::equal(kXp3Mark.begin(), kXp3Mark.end(), header.begin())) {
        return 32;
    }

    std::uint64_t index_offset = read_u64(header.data() + kXp3Mark.size());
    std::unordered_set<std::uint64_t> visited_index_offsets;
    std::size_t index_blocks_read = 0;
    for (;;) {
        if (index_blocks_read >= kMaxXp3IndexBlocks ||
            !visited_index_offsets.insert(index_offset).second ||
            !add_fits(index_offset, kXp3IndexBaseHeaderSize, file_size)) {
            return 34;
        }
        ++index_blocks_read;

        std::uint8_t flags = 0;
        std::vector<std::uint8_t> index;
        std::uint64_t next_offset_position = 0;
        const int payload_result = read_index_payload(
            input.get(),
            file_size,
            index_offset,
            &flags,
            &index,
            &next_offset_position);
        if (payload_result != 0) {
            return payload_result;
        }
        const int result = read_index(index, input.get(), file_size, source);
        if (result != 31) {
            return result;
        }
        if ((flags & kXp3Continuation) == 0) {
            return 31;
        }
        std::array<std::uint8_t, 8> next_offset{};
        if (!read_exact_at(input.get(), file_size, next_offset_position, next_offset.data(), next_offset.size())) {
            return 34;
        }
        const std::uint64_t next_index_offset = read_u64(next_offset.data());
        if (!add_fits(next_index_offset, kXp3IndexBaseHeaderSize, file_size) ||
            visited_index_offsets.find(next_index_offset) != visited_index_offsets.end()) {
            return 34;
        }
        index_offset = next_index_offset;
    }
}

int read_xp3_startup(const char* archive_path, std::string* source) {
    if (archive_path == nullptr) {
        return 30;
    }
    return read_xp3_startup(StorageSpec::LocalFile(archive_path), source);
}

}  // namespace twinquill::krkr

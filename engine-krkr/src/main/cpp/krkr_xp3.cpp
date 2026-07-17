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
#include <fstream>
#include <limits>
#include <string>
#include <vector>

namespace twinquill::krkr {
namespace {

constexpr std::array<std::uint8_t, 11> kXp3Mark = {
    0x58, 0x50, 0x33, 0x0d, 0x0a, 0x20, 0x0a, 0x1a, 0x8b, 0x67, 0x01,
};
constexpr std::uint64_t kMaxIndexSize = 16 * 1024 * 1024;
constexpr std::uint64_t kMaxStartupSize = 8 * 1024 * 1024;
constexpr std::uint32_t kProtectedFile = 1U << 31U;

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

bool read_at(
    std::ifstream* input,
    std::uint64_t file_size,
    std::uint64_t offset,
    void* output,
    std::size_t size) {
    if (!add_fits(offset, size, file_size) ||
        offset > static_cast<std::uint64_t>(std::numeric_limits<std::streamoff>::max())) {
        return false;
    }
    input->clear();
    input->seekg(static_cast<std::streamoff>(offset), std::ios::beg);
    input->read(static_cast<char*>(output), static_cast<std::streamsize>(size));
    return input->good() || input->gcount() == static_cast<std::streamsize>(size);
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

int read_file_chunk(
    const Chunk& file,
    std::ifstream* input,
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

    const std::uint16_t name_length = read_u16(info->data + 20);
    const std::uint64_t name_bytes = static_cast<std::uint64_t>(name_length) * 2;
    if (!add_fits(22, name_bytes, info->size) ||
        !startup_name(info->data + 22, name_length)) {
        return 31;
    }
    if ((read_u32(info->data) & kProtectedFile) != 0) {
        return 35;
    }

    const std::uint64_t original_size = read_u64(info->data + 4);
    if (original_size > kMaxStartupSize || segments->size == 0 || segments->size % 28 != 0) {
        return 34;
    }
    source->clear();
    source->reserve(static_cast<std::size_t>(original_size));
    for (std::size_t segment = 0; segment < segments->size; segment += 28) {
        const std::uint8_t* data = segments->data + segment;
        const std::uint32_t flags = read_u32(data);
        const std::uint64_t offset = read_u64(data + 4);
        const std::uint64_t unpacked_size = read_u64(data + 12);
        const std::uint64_t archived_size = read_u64(data + 20);
        if ((flags & 0x07U) != 0 || unpacked_size != archived_size) {
            return 33;
        }
        if (!add_fits(source->size(), unpacked_size, original_size) ||
            unpacked_size > std::numeric_limits<std::size_t>::max()) {
            return 34;
        }
        const std::size_t old_size = source->size();
        source->resize(old_size + static_cast<std::size_t>(unpacked_size));
        if (!read_at(input, file_size, offset, source->data() + old_size,
                     static_cast<std::size_t>(unpacked_size))) {
            return 34;
        }
    }
    return source->size() == original_size ? 0 : 34;
}

int read_index(
    const std::vector<std::uint8_t>& index,
    std::ifstream* input,
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

}  // namespace

int read_raw_xp3_startup(const char* archive_path, std::string* source) {
    if (archive_path == nullptr || source == nullptr) {
        return 30;
    }
    std::ifstream input(archive_path, std::ios::binary | std::ios::ate);
    if (!input) {
        return 30;
    }
    const std::streamoff end = input.tellg();
    if (end < 19) {
        return 32;
    }
    const std::uint64_t file_size = static_cast<std::uint64_t>(end);
    std::array<std::uint8_t, 19> header{};
    if (!read_at(&input, file_size, 0, header.data(), header.size()) ||
        !std::equal(kXp3Mark.begin(), kXp3Mark.end(), header.begin())) {
        return 32;
    }

    std::uint64_t index_offset = read_u64(header.data() + kXp3Mark.size());
    for (;;) {
        std::array<std::uint8_t, 9> index_header{};
        if (!read_at(&input, file_size, index_offset, index_header.data(), index_header.size())) {
            return 34;
        }
        const std::uint8_t flags = index_header[0];
        if ((flags & 0x07U) != 0) {
            return 33;
        }
        const std::uint64_t index_size = read_u64(index_header.data() + 1);
        if (index_size > kMaxIndexSize || !add_fits(index_offset, 9 + index_size, file_size)) {
            return 34;
        }
        std::vector<std::uint8_t> index(static_cast<std::size_t>(index_size));
        if (!read_at(&input, file_size, index_offset + 9, index.data(), index.size())) {
            return 34;
        }
        const int result = read_index(index, &input, file_size, source);
        if (result != 31) {
            return result;
        }
        if ((flags & 0x80U) == 0) {
            return 31;
        }
        const std::uint64_t next_offset_position = index_offset + 9 + index_size;
        std::array<std::uint8_t, 8> next_offset{};
        if (!read_at(&input, file_size, next_offset_position, next_offset.data(), next_offset.size())) {
            return 34;
        }
        index_offset = read_u64(next_offset.data());
    }
}

}  // namespace twinquill::krkr

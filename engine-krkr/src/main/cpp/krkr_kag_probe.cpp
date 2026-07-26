/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_kag_probe.h"

#include "krkr_storage_registry.h"
#include "StorageIntf.h"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <limits>
#include <memory>
#include <string>

namespace twinquill::krkr {
namespace {

constexpr std::uint64_t kMaxScenarioSize = 1024 * 1024;

// Keep this as a bounded structural preflight: it validates UTF-8, confirms at
// least one label, and accepts basic @tag/[tag ...] forms without parsing KAG.
bool fits_size_t(std::uint64_t value) {
    return value <= static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max());
}

bool is_space(unsigned char character) {
    return character == ' ' || character == '\t';
}

bool is_newline(unsigned char character) {
    return character == '\r' || character == '\n';
}

bool is_name_character(unsigned char character) {
    return (character >= 'A' && character <= 'Z') ||
        (character >= 'a' && character <= 'z') ||
        (character >= '0' && character <= '9') ||
        character == '_' || character == '.';
}

bool validate_utf8(const std::string& text) {
    std::size_t index = 0;
    while (index < text.size()) {
        const unsigned char first = static_cast<unsigned char>(text[index]);
        if (first == 0) {
            return false;
        }
        if (first < 0x20 && !is_space(first) && !is_newline(first)) {
            return false;
        }
        if (first <= 0x7f) {
            ++index;
            continue;
        }

        std::uint32_t code_point = 0;
        std::size_t length = 0;
        if (first >= 0xc2 && first <= 0xdf) {
            code_point = first & 0x1fU;
            length = 2;
        } else if (first >= 0xe0 && first <= 0xef) {
            code_point = first & 0x0fU;
            length = 3;
        } else if (first >= 0xf0 && first <= 0xf4) {
            code_point = first & 0x07U;
            length = 4;
        } else {
            return false;
        }
        if (length > text.size() - index) {
            return false;
        }
        for (std::size_t offset = 1; offset < length; ++offset) {
            const unsigned char next = static_cast<unsigned char>(text[index + offset]);
            if ((next & 0xc0U) != 0x80U) {
                return false;
            }
            code_point = (code_point << 6U) | (next & 0x3fU);
        }
        if ((length == 3 && code_point < 0x800U) ||
            (length == 4 && code_point < 0x10000U) ||
            (code_point >= 0xd800U && code_point <= 0xdfffU) ||
            code_point > 0x10ffffU) {
            return false;
        }
        index += length;
    }
    return true;
}

int read_stream_to_string(tTJSBinaryStream* stream, std::string* output) {
    if (stream == nullptr || output == nullptr) {
        return 41;
    }
    const std::uint64_t size = stream->GetSize();
    if (size == 0 || size > kMaxScenarioSize || !fits_size_t(size)) {
        return 41;
    }
    output->assign(static_cast<std::size_t>(size), '\0');
    char* cursor = output->data();
    std::size_t remaining = output->size();
    while (remaining > 0) {
        const std::size_t request = std::min(
            remaining,
            static_cast<std::size_t>(std::numeric_limits<TJS::tjs_uint>::max()));
        const TJS::tjs_uint count = stream->Read(cursor, static_cast<TJS::tjs_uint>(request));
        if (count == 0 || static_cast<std::size_t>(count) > request) {
            output->clear();
            return 41;
        }
        cursor += count;
        remaining -= static_cast<std::size_t>(count);
    }
    return validate_utf8(*output) ? 0 : 41;
}

std::size_t skip_bom(const std::string& source) {
    if (source.size() >= 3 &&
        static_cast<unsigned char>(source[0]) == 0xef &&
        static_cast<unsigned char>(source[1]) == 0xbb &&
        static_cast<unsigned char>(source[2]) == 0xbf) {
        return 3;
    }
    return 0;
}

std::size_t skip_to_line_end(const std::string& source, std::size_t position) {
    while (position < source.size() &&
        !is_newline(static_cast<unsigned char>(source[position]))) {
        ++position;
    }
    return position;
}

bool parse_command_name(
    const std::string& source,
    std::size_t* position,
    bool bracketed) {
    if (position == nullptr || *position >= source.size()) {
        return false;
    }
    std::size_t cursor = *position;
    while (cursor < source.size() && is_space(static_cast<unsigned char>(source[cursor]))) {
        ++cursor;
    }
    const std::size_t start = cursor;
    while (cursor < source.size() &&
        is_name_character(static_cast<unsigned char>(source[cursor]))) {
        ++cursor;
    }
    if (cursor == start) {
        return false;
    }
    if (bracketed) {
        while (cursor < source.size() &&
            source[cursor] != ']' &&
            !is_newline(static_cast<unsigned char>(source[cursor]))) {
            ++cursor;
        }
        if (cursor >= source.size() || source[cursor] != ']') {
            return false;
        }
        ++cursor;
    } else {
        cursor = skip_to_line_end(source, cursor);
    }
    *position = cursor;
    return true;
}

bool parse_label(const std::string& source, std::size_t* position) {
    if (position == nullptr || *position >= source.size() || source[*position] != '*') {
        return false;
    }
    std::size_t cursor = *position + 1;
    const std::size_t start = cursor;
    while (cursor < source.size()) {
        const unsigned char character = static_cast<unsigned char>(source[cursor]);
        if (is_space(character) || is_newline(character) || character == '|') {
            break;
        }
        if (character < 0x20 || character == '[' || character == ']') {
            return false;
        }
        ++cursor;
    }
    if (cursor == start) {
        return false;
    }
    *position = skip_to_line_end(source, cursor);
    return true;
}

int probe_structure(const std::string& source) {
    bool saw_label = false;
    std::size_t position = skip_bom(source);
    bool at_line_start = true;
    while (position < source.size()) {
        const unsigned char character = static_cast<unsigned char>(source[position]);
        if (is_newline(character)) {
            ++position;
            at_line_start = true;
            continue;
        }
        if (at_line_start && is_space(character)) {
            ++position;
            continue;
        }
        if (at_line_start && character == ';') {
            position = skip_to_line_end(source, position);
            continue;
        }
        if (at_line_start && character == '*') {
            if (!parse_label(source, &position)) {
                return 41;
            }
            saw_label = true;
            at_line_start = false;
            continue;
        }
        if (character == '[') {
            ++position;
            if (!parse_command_name(source, &position, true)) {
                return 41;
            }
            at_line_start = false;
            continue;
        }
        if (at_line_start && character == '@') {
            ++position;
            if (!parse_command_name(source, &position, false)) {
                return 41;
            }
            at_line_start = false;
            continue;
        }
        ++position;
        at_line_start = false;
    }
    return saw_label ? 0 : 41;
}

}  // namespace

int probe_kag_scenario(const std::string& scenario_name) {
    if (scenario_name.empty()) {
        return 40;
    }
    const std::string storage_name = scenario_name.find("://") == std::string::npos
        ? std::string(kTwinQuillStorageMediaName) + "://./" + scenario_name
        : scenario_name;
    const ttstr tjs_storage_name(storage_name.c_str());
    if (!TVPIsExistentStorageNoSearch(tjs_storage_name)) {
        return 40;
    }

    std::unique_ptr<tTJSBinaryStream> stream(TVPCreateStream(tjs_storage_name, TJS_BS_READ));
    if (stream == nullptr) {
        return 41;
    }

    std::string source;
    const int read_result = read_stream_to_string(stream.get(), &source);
    if (read_result != 0) {
        return read_result;
    }
    return probe_structure(source);
}

}  // namespace twinquill::krkr

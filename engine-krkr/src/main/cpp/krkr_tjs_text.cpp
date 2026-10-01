/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_tjs_text.h"

#include <cstdint>
#include <stdexcept>

namespace twinquill::krkr {
namespace {

void append_utf16(std::u16string& output, std::uint32_t scalar) {
    if (scalar == 0 || scalar > 0x10ffff || (scalar >= 0xd800 && scalar <= 0xdfff)) {
        throw std::invalid_argument("Invalid Unicode scalar in startup.tjs");
    }
    if (scalar <= 0xffff) {
        output.push_back(static_cast<char16_t>(scalar));
    } else {
        scalar -= 0x10000;
        output.push_back(static_cast<char16_t>(0xd800 | (scalar >> 10)));
        output.push_back(static_cast<char16_t>(0xdc00 | (scalar & 0x3ff)));
    }
}

std::uint32_t utf16_scalar(std::u16string_view text, std::size_t& position) {
    const std::uint32_t first = text[position++];
    if (first >= 0xd800 && first <= 0xdbff) {
        if (position == text.size() || text[position] < 0xdc00 || text[position] > 0xdfff) {
            throw std::invalid_argument("Unpaired UTF-16 high surrogate");
        }
        return 0x10000 + ((first - 0xd800) << 10) + (text[position++] - 0xdc00);
    }
    if (first >= 0xdc00 && first <= 0xdfff) {
        throw std::invalid_argument("Unpaired UTF-16 low surrogate");
    }
    return first;
}

}  // namespace

std::u16string decode_tjs_source(std::string_view source) {
    if (source.size() > kStartupSourceLimit) {
        throw std::invalid_argument("startup.tjs exceeds the 8 MiB source limit");
    }
    const auto byte = [&](std::size_t position) {
        return static_cast<unsigned char>(source[position]);
    };
    if (source.size() >= 2 && ((byte(0) == 0xff && byte(1) == 0xfe)
        || (byte(0) == 0xfe && byte(1) == 0xff))) {
        if ((source.size() - 2) % 2 != 0) {
            throw std::invalid_argument("Truncated UTF-16 startup.tjs");
        }
        const bool little_endian = byte(0) == 0xff;
        std::u16string text;
        text.reserve((source.size() - 2) / 2);
        for (std::size_t position = 2; position < source.size(); position += 2) {
            const auto unit = little_endian
                ? byte(position) | (byte(position + 1) << 8)
                : (byte(position) << 8) | byte(position + 1);
            text.push_back(static_cast<char16_t>(unit));
        }
        for (std::size_t position = 0; position < text.size();) {
            if (utf16_scalar(text, position) == 0) {
                throw std::invalid_argument("Embedded NUL in startup.tjs");
            }
        }
        return text;
    }

    std::size_t position = source.size() >= 3 && byte(0) == 0xef
        && byte(1) == 0xbb && byte(2) == 0xbf ? 3 : 0;
    std::u16string text;
    text.reserve(source.size() - position);
    while (position < source.size()) {
        const unsigned char first = byte(position++);
        std::uint32_t scalar = first;
        std::uint32_t minimum = 0;
        std::size_t continuation_count = 0;
        if (first >= 0xc2 && first <= 0xdf) {
            scalar = first & 0x1f;
            minimum = 0x80;
            continuation_count = 1;
        } else if (first >= 0xe0 && first <= 0xef) {
            scalar = first & 0x0f;
            minimum = 0x800;
            continuation_count = 2;
        } else if (first >= 0xf0 && first <= 0xf4) {
            scalar = first & 0x07;
            minimum = 0x10000;
            continuation_count = 3;
        } else if (first >= 0x80) {
            throw std::invalid_argument("Invalid UTF-8 lead byte in startup.tjs");
        }
        if (continuation_count > source.size() - position) {
            throw std::invalid_argument("Truncated UTF-8 startup.tjs");
        }
        for (std::size_t index = 0; index < continuation_count; ++index) {
            const unsigned char next = byte(position++);
            if ((next & 0xc0) != 0x80) {
                throw std::invalid_argument("Invalid UTF-8 continuation in startup.tjs");
            }
            scalar = (scalar << 6) | (next & 0x3f);
        }
        if (scalar < minimum) {
            throw std::invalid_argument("Overlong UTF-8 in startup.tjs");
        }
        append_utf16(text, scalar);
    }
    return text;
}

std::string encode_tjs_utf8(std::u16string_view text) {
    std::string output;
    for (std::size_t position = 0; position < text.size();) {
        const std::uint32_t scalar = utf16_scalar(text, position);
        if (scalar <= 0x7f) {
            output.push_back(static_cast<char>(scalar));
        } else if (scalar <= 0x7ff) {
            output.push_back(static_cast<char>(0xc0 | (scalar >> 6)));
            output.push_back(static_cast<char>(0x80 | (scalar & 0x3f)));
        } else if (scalar <= 0xffff) {
            output.push_back(static_cast<char>(0xe0 | (scalar >> 12)));
            output.push_back(static_cast<char>(0x80 | ((scalar >> 6) & 0x3f)));
            output.push_back(static_cast<char>(0x80 | (scalar & 0x3f)));
        } else {
            output.push_back(static_cast<char>(0xf0 | (scalar >> 18)));
            output.push_back(static_cast<char>(0x80 | ((scalar >> 12) & 0x3f)));
            output.push_back(static_cast<char>(0x80 | ((scalar >> 6) & 0x3f)));
            output.push_back(static_cast<char>(0x80 | (scalar & 0x3f)));
        }
    }
    return output;
}

}  // namespace twinquill::krkr

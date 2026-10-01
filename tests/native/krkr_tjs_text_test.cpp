/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_tjs_text.h"

#include <cstdio>
#include <stdexcept>
#include <string>
#include <vector>

using twinquill::krkr::decode_tjs_source;
using twinquill::krkr::encode_tjs_utf8;

int main() {
    const std::u16string expected = u"A\u4e2d\U0001f600";
    const std::string utf8 = "A\xe4\xb8\xad\xf0\x9f\x98\x80";
    const std::vector<std::string> valid = {
        utf8,
        std::string("\xef\xbb\xbf", 3) + utf8,
        std::string("\xff\xfe\x41\x00\x2d\x4e\x3d\xd8\x00\xde", 10),
        std::string("\xfe\xff\x00\x41\x4e\x2d\xd8\x3d\xde\x00", 10),
    };
    for (const auto& source : valid) {
        if (decode_tjs_source(source) != expected) {
            std::fprintf(stderr, "Unicode decoding mismatch\n");
            return 1;
        }
    }
    if (encode_tjs_utf8(expected) != utf8 || decode_tjs_source("ascii") != u"ascii"
        || !decode_tjs_source("").empty()) {
        std::fprintf(stderr, "UTF-8 round trip or ASCII decoding failed\n");
        return 2;
    }
    const std::vector<std::string> invalid = {
        std::string("A\0B", 3),              // Must not truncate at NUL.
        "\x80", "\xc0\xaf", "\xc1\xbf",   // Bad lead / overlong forms.
        "\xc2", "\xe4\xb8", "\xf0\x9f\x98", // Truncated sequences.
        "\xe4\x41\xad", "\xe0\x80\x80", // Bad continuation / overlong.
        "\xed\xa0\x80", "\xf4\x90\x80\x80", "\xf5\x80\x80\x80",
        std::string("\xff\xfe\x41", 3),
        std::string("\xff\xfe\x00\x00", 4),
        std::string("\xff\xfe\x00\xd8", 4),
        std::string("\xff\xfe\x00\xdc", 4),
        std::string("\xff\xfe\x00\xd8\x41\x00", 6),
        std::string("\xfe\xff\xd8\x00", 4),
        std::string("\xfe\xff\xdc\x00", 4),
        std::string("\xfe\xff\xd8\x00\x00\x41", 6),
        std::string(twinquill::krkr::kStartupSourceLimit + 1, 'a'),
    };
    for (std::size_t index = 0; index < invalid.size(); ++index) {
        try {
            (void)decode_tjs_source(invalid[index]);
            std::fprintf(stderr, "Accepted invalid source case %zu\n", index);
            return 3;
        } catch (const std::invalid_argument&) {
            // Expected: the decoder must reject the entire input.
        }
    }
    for (const auto unit : {char16_t(0xd800), char16_t(0xdc00)}) {
        try {
            (void)encode_tjs_utf8(std::u16string(1, unit));
            std::fprintf(stderr, "Encoded an unpaired surrogate\n");
            return 4;
        } catch (const std::invalid_argument&) {
        }
    }
    const std::string boundary(twinquill::krkr::kStartupSourceLimit, 'a');
    if (decode_tjs_source(boundary).size() != boundary.size()) return 5;
    std::printf("TJS text checks passed: Unicode round trips, %zu invalid inputs, source limit\n",
        invalid.size());
    return 0;
}

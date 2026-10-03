/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_game_text.h"
#include "krkr_tjs_text.h"
#include <algorithm>
#include <stdexcept>
namespace twinquill::krkr {
namespace {
LegacyTextDecoder cp932_decoder;
std::string trim(std::string text) {
    const auto first = text.find_first_not_of(" \t\r");
    if (first == std::string::npos) return {};
    return text.substr(first, text.find_last_not_of(" \t\r") - first + 1);
}
}
void set_cp932_decoder(LegacyTextDecoder decoder) { cp932_decoder = std::move(decoder); }
std::string text_encoding(std::string name) {
    for (char& c : name) if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
    if (name.empty() || name == "utf-8" || name == "utf8" || name == "unicode") return "utf-8";
    if (name == "cp932" || name == "windows-31j" || name == "shift-jis"
        || name == "shift_jis" || name == "sjis") return "cp932";
    throw std::invalid_argument("Unsupported explicit text encoding");
}
std::string game_text_encoding(std::string_view configuration) {
    (void)decode_tjs_source(configuration); // Strict UTF-8/Unicode, never auto-detect legacy text.
    if (configuration.size() > 4096) throw std::invalid_argument("Configuration exceeds 4 KiB");
    std::string input = encode_tjs_utf8(decode_tjs_source(configuration));
    std::string encoding = "utf-8";
    bool seen = false;
    for (std::size_t start = 0; start < input.size();) {
        const auto end = input.find('\n', start);
        const auto line = trim(input.substr(start, end - start));
        if (!line.empty() && line.front() != '#') {
            const auto equals = line.find('=');
            if (equals == std::string::npos || trim(line.substr(0, equals)) != "textEncoding" || seen)
                throw std::invalid_argument("Invalid or duplicate game configuration key");
            const auto value = trim(line.substr(equals + 1));
            if (value.empty()) throw std::invalid_argument("Missing game text encoding");
            encoding = text_encoding(value); seen = true;
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return encoding;
}
std::u16string decode_game_text(std::string_view source, const std::string& encoding) {
    if (source.size() > kStartupSourceLimit) throw std::invalid_argument("Text exceeds 8 MiB");
    const bool bom = source.substr(0, 3) == std::string_view("\xef\xbb\xbf", 3)
        || source.substr(0, 2) == std::string_view("\xff\xfe", 2)
        || source.substr(0, 2) == std::string_view("\xfe\xff", 2);
    const auto checked = text_encoding(encoding);
    if (checked == "utf-8" || bom) return decode_tjs_source(source);
    if (!cp932_decoder) throw std::invalid_argument("CP932 decoder is unavailable");
    auto decoded = cp932_decoder(source);
    // Android's decoder reports malformed/unmappable input; revalidate NUL and
    // surrogate rules with exactly the existing strict Unicode contract.
    return decode_tjs_source(encode_tjs_utf8(decoded));
}
}

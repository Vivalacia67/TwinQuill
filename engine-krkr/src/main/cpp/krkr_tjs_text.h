/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

#include <cstddef>
#include <string>
#include <string_view>

namespace twinquill::krkr {

constexpr std::size_t kStartupSourceLimit = 8U * 1024U * 1024U;

// UTF-8 (optional BOM) or BOM-marked UTF-16LE/BE. Invalid text and embedded
// NULs throw invalid_argument rather than silently truncating a TJS script.
std::u16string decode_tjs_source(std::string_view source);
std::string encode_tjs_utf8(std::u16string_view text);

}  // namespace twinquill::krkr

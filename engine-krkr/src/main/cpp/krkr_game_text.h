/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once
#include <functional>
#include <string>
#include <string_view>
namespace twinquill::krkr {
using LegacyTextDecoder = std::function<std::u16string(std::string_view)>;
void set_cp932_decoder(LegacyTextDecoder decoder);
std::string text_encoding(std::string name);
std::string game_text_encoding(std::string_view configuration);
std::u16string decode_game_text(std::string_view source, const std::string& encoding);
}

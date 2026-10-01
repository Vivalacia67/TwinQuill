/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

#include <string_view>

namespace twinquill::krkr {

// Execute startup.tjs once in a fresh TJS2 engine. Returns 0 on successful
// completion, 20 for script/text errors, and 21/22 for native failures.
int run_tjs_source(std::string_view source) noexcept;

}  // namespace twinquill::krkr

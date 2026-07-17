/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

#include <string>

namespace twinquill::krkr {

// Reads an unprotected, raw XP3 root startup.tjs into memory.
// Compressed indices and segments are deliberately deferred beyond M0.
int read_raw_xp3_startup(const char* archive_path, std::string* source);

}  // namespace twinquill::krkr

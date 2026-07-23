/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

#include <string>

#include "krkr_storage.h"

namespace twinquill::krkr {

// Reads an unprotected XP3 root startup.tjs through the XP3 archive stream layer.
int read_xp3_startup(const StorageSpec& archive, std::string* source);
int read_xp3_startup(const char* archive_path, std::string* source);

}  // namespace twinquill::krkr

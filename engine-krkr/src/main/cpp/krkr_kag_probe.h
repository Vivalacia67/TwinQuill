/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

#include <string>

namespace twinquill::krkr {

// Bounded structural KAG preflight for M3 startup diagnostics.
// This is intentionally not a full KAGParser and never scans real game files.
int probe_kag_scenario(const std::string& scenario_name);

}  // namespace twinquill::krkr

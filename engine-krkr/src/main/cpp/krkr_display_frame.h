/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once
#include <cstdint>
#include <memory>
#include <vector>

namespace twinquill::krkr {
// Immutable worker-to-GL snapshot; it contains no TJS objects or GL names.
struct DisplayFrame {
    int width = 0, height = 0;
    std::uint64_t generation = 0;
    std::vector<std::uint8_t> rgba;
};
std::shared_ptr<const DisplayFrame> display_frame();
}

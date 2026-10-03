/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace twinquill::krkr {
// Positive opaque handle on success; negative existing startup diagnostic on error.
std::int64_t start_tjs_session(int source_kind, const std::string& source, bool deferred = false,
                               const std::string& save_directory = "");
int activate_tjs_session(std::uint64_t handle, int width, int height);
void cancel_tjs_session(std::uint64_t handle);
int dispatch_tjs_event(std::uint64_t handle, int event, const std::vector<double>& args);
int poll_tjs_session(std::uint64_t handle, std::int64_t* color);
void close_tjs_session(std::uint64_t handle);
bool tjs_session_active();
// active sessions, successful startups, releases, dispatched callbacks, status.
void tjs_session_stats(std::uint64_t handle, std::int64_t* output);
}  // namespace twinquill::krkr

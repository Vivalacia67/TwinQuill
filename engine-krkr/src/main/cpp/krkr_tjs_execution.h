/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

#include <atomic>
#include <chrono>

namespace twinquill::krkr {
// All nested Scripts.exec/eval calls share their outer operation's deadline.
// A silent TJS exception bypasses script catch blocks and unwinds VM frames.
class ExecutionScope final {
public:
    ExecutionScope(const std::atomic<bool>* cancelled, std::chrono::milliseconds duration,
                   bool cleanup = false);
    ~ExecutionScope();
    ExecutionScope(const ExecutionScope&) = delete;
    ExecutionScope& operator=(const ExecutionScope&) = delete;
    bool cancelled() const;
    bool cleanup() const { return cleanup_; }
private:
    friend void check_execution();
    const std::atomic<bool>* cancelled_;
    std::chrono::steady_clock::time_point deadline_;
    ExecutionScope* previous_;
    unsigned checkpoints_ = 0;
    bool cleanup_;
};
void check_execution();
bool execution_is_cleanup();
}  // namespace twinquill::krkr

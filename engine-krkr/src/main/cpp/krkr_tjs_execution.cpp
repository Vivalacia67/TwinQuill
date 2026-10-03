/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_tjs_execution.h"
#include "tjsError.h"
#include <android/log.h>

namespace twinquill::krkr {
namespace { thread_local ExecutionScope* current_scope = nullptr; }

ExecutionScope::ExecutionScope(const std::atomic<bool>* cancelled,
                              std::chrono::milliseconds duration, bool cleanup)
    : cancelled_(cancelled), deadline_(std::chrono::steady_clock::now() + duration),
      previous_(current_scope), cleanup_(cleanup) {
    current_scope = this;
}

ExecutionScope::~ExecutionScope() { current_scope = previous_; }
bool ExecutionScope::cancelled() const { return cancelled_ != nullptr && cancelled_->load(); }
bool execution_is_cleanup() { return current_scope != nullptr && current_scope->cleanup(); }

void check_execution() {
    if (current_scope == nullptr) return;
    // Check on entry and every 1024 opcodes/tokens; no clock read per opcode.
    if ((current_scope->checkpoints_++ & 1023U) != 0U) return;
    if (current_scope->cancelled()
        || std::chrono::steady_clock::now() >= current_scope->deadline_) {
        throw TJS::eTJSSilent();
    }
}
}  // namespace twinquill::krkr

namespace TJS {
namespace { thread_local unsigned script_call_depth = 0; }
void TJSEnterScriptCall() {
    if (script_call_depth >= 128) TJS_eTJSError(TJS_W("Script call depth exceeds 128"));
    ++script_call_depth;
}
void TJSLeaveScriptCall() { --script_call_depth; }
// Called only by the recorded patch in the generated TJS2 copy.
void TJSCheckExecutionBudget() { twinquill::krkr::check_execution(); }
bool TJSHostIsShuttingDown() { return twinquill::krkr::execution_is_cleanup(); }
void TJSHostReportFinalizerFailure() {
    __android_log_print(ANDROID_LOG_WARN, "TwinQuill/Krkr",
        "Finalizer failed or exceeded shutdown budget; releasing native members");
}
}  // namespace TJS

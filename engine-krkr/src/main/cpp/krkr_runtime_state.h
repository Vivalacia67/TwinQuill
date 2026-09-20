/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#ifndef TWINQUILL_KRKR_RUNTIME_STATE_H
#define TWINQUILL_KRKR_RUNTIME_STATE_H

#include <cstddef>
#include <cstdint>
#include <string>

namespace twinquill::krkr_runtime {

using RuntimeHandle = std::uint64_t;

// The Java renderer may submit at most one event for each pointer.  Keeping
// the storage fixed makes callback latency and memory use independent of input
// bursts.
constexpr std::size_t kInputQueueCapacity = 64U;
constexpr std::size_t kCounterCount = 11U;

enum RuntimeResult : int {
    kRuntimeOk = 0,
    kRuntimeInvalidArgument = 10,
    kRuntimeStaleHandle = 11,
    kRuntimeSurfaceNotReady = 12,
    kRuntimeGraphicsError = 13,
    kRuntimeInputRejected = 14,
};

// The order is part of the private Java/native diagnostic contract:
// frame,input-generation,pause,resume,low-memory,surface-generation,
// surface-loss,global-destroy,dropped-input,queued-input,rejected-operation.
struct RuntimeCounters {
    std::uint64_t frame_count = 0U;
    std::uint64_t input_generation = 0U;
    std::uint64_t pause_count = 0U;
    std::uint64_t resume_count = 0U;
    std::uint64_t low_memory_count = 0U;
    std::uint64_t surface_generation = 0U;
    std::uint64_t surface_loss_count = 0U;
    std::uint64_t dropped_input_count = 0U;
    std::uint64_t queued_input_count = 0U;

    void write_to(std::uint64_t* output,
                  std::size_t count,
                  std::uint64_t global_destroy_count,
                  std::uint64_t rejected_operation_count) const noexcept;
};

RuntimeHandle create(int source_kind,
                     const std::string& source,
                     const std::string& save_directory,
                     const std::string& game_id);

int surface_created(RuntimeHandle handle);
int surface_changed(RuntimeHandle handle, int width, int height);
int draw_frame(RuntimeHandle handle);
int pause(RuntimeHandle handle);
int resume(RuntimeHandle handle);
int low_memory(RuntimeHandle handle);
int surface_lost(RuntimeHandle handle);
int destroy(RuntimeHandle handle);
int touch(RuntimeHandle handle,
          int action,
          int pointer_id,
          float x,
          float y,
          std::int64_t event_time);
int key(RuntimeHandle handle,
        bool down,
        int key_code,
        int unicode_code_point,
        int meta_state,
        int repeat_count,
        std::int64_t event_time);

// Returns false for a stale/destroyed handle.  A valid handle always writes
// exactly kCounterCount values in the frozen order above.
bool counters(RuntimeHandle handle,
              std::uint64_t* output,
              std::size_t count);

}  // namespace twinquill::krkr_runtime

#endif  // TWINQUILL_KRKR_RUNTIME_STATE_H

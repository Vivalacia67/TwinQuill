/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_runtime_state.h"

#include "krkr_cocos_runtime.h"

#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>

namespace twinquill::krkr_runtime {
namespace {

enum class InputKind : std::uint8_t { kTouch, kKey };

struct InputEvent {
    InputKind kind = InputKind::kTouch;
    int action = 0;
    int pointer_id = 0;
    float x = 0.0F;
    float y = 0.0F;
    bool key_down = false;
    int key_code = 0;
    int unicode_code_point = 0;
    int meta_state = 0;
    int repeat_count = 0;
    std::int64_t event_time = 0;
};

bool valid_text(const std::string& text) noexcept {
    return !text.empty() && text.find('\0') == std::string::npos;
}

class RuntimeState;
std::mutex g_registry_mutex;
std::unordered_map<RuntimeHandle, std::shared_ptr<RuntimeState>> g_registry;
RuntimeHandle g_next_handle = 1U;
std::uint64_t g_global_destroy_count = 0U;
std::uint64_t g_rejected_operation_count = 0U;

void record_rejected_operation() {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    ++g_rejected_operation_count;
}

struct GlobalCounterSnapshot {
    std::uint64_t destroy_count = 0U;
    std::uint64_t rejected_operation_count = 0U;
};

GlobalCounterSnapshot global_counter_snapshot() {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    return {g_global_destroy_count, g_rejected_operation_count};
}

class RuntimeState final {
 public:
    RuntimeState(int source_kind,
                 std::string source,
                 std::string save_directory,
                 std::string game_id)
        : source_kind_(source_kind),
          source_(std::move(source)),
          save_directory_(std::move(save_directory)),
          game_id_(std::move(game_id)) {}

    int surface_created() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (surface_active_) {
            // A second callback replaces the current context.  Preserve the
            // idempotent callback result while recording the duplicate and
            // loss; CocosRuntime abandons old GL names without GL calls.
            record_rejected_operation();
            ++counters_.surface_loss_count;
        }
        surface_active_ = false;
        const int result = renderer_.surface_created();
        if (result == kRuntimeOk) {
            surface_active_ = true;
            ++counters_.surface_generation;
        }
        return result;
    }

    int surface_changed(int width, int height) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!surface_active_) {
            record_rejected_operation();
            return kRuntimeSurfaceNotReady;
        }
        const int result = renderer_.surface_changed(width, height);
        if (result == kRuntimeInvalidArgument) {
            record_rejected_operation();
        }
        return result;
    }

    int draw_frame() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!surface_active_) {
            record_rejected_operation();
            return kRuntimeSurfaceNotReady;
        }
        if (paused_) {
            return kRuntimeOk;
        }

        // Drain at most the fixed queue capacity. Every consumed input makes
        // the proof frame use color B and advances the observable generation.
        while (queue_size_ != 0U) {
            const InputEvent& event = queue_[queue_head_];
            (void)event;
            queue_head_ = (queue_head_ + 1U) % kInputQueueCapacity;
            --queue_size_;
            ++counters_.input_generation;
            alternate_color_ = true;
        }
        counters_.queued_input_count = queue_size_;

        const int result = renderer_.draw_frame(alternate_color_);
        if (result == kRuntimeOk) {
            ++counters_.frame_count;
        }
        return result;
    }

    int pause() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (paused_) {
            record_rejected_operation();
            return kRuntimeOk;
        }
        paused_ = true;
        ++counters_.pause_count;
        return kRuntimeOk;
    }

    int resume() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!paused_) {
            record_rejected_operation();
            return kRuntimeOk;
        }
        paused_ = false;
        ++counters_.resume_count;
        return kRuntimeOk;
    }

    int low_memory() {
        std::lock_guard<std::mutex> lock(mutex_);
        ++counters_.low_memory_count;
        return kRuntimeOk;
    }

    int surface_lost() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!surface_active_) {
            record_rejected_operation();
            return kRuntimeOk;
        }
        const int result = renderer_.surface_lost();
        surface_active_ = false;
        ++counters_.surface_loss_count;
        return result;
    }

    int touch(int action,
              int pointer_id,
              float x,
              float y,
              std::int64_t event_time) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!std::isfinite(x) || !std::isfinite(y)) {
            ++counters_.dropped_input_count;
            record_rejected_operation();
            return kRuntimeInvalidArgument;
        }
        if (queue_size_ >= kInputQueueCapacity) {
            ++counters_.dropped_input_count;
            record_rejected_operation();
            return kRuntimeInputRejected;
        }
        InputEvent& event = queue_[(queue_head_ + queue_size_) % kInputQueueCapacity];
        event = InputEvent{};
        event.kind = InputKind::kTouch;
        event.action = action;
        event.pointer_id = pointer_id;
        event.x = x;
        event.y = y;
        event.event_time = event_time;
        ++queue_size_;
        counters_.queued_input_count = queue_size_;
        return kRuntimeOk;
    }

    int key(bool down,
            int key_code,
            int unicode_code_point,
            int meta_state,
            int repeat_count,
            std::int64_t event_time) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (queue_size_ >= kInputQueueCapacity) {
            ++counters_.dropped_input_count;
            record_rejected_operation();
            return kRuntimeInputRejected;
        }
        InputEvent& event = queue_[(queue_head_ + queue_size_) % kInputQueueCapacity];
        event = InputEvent{};
        event.kind = InputKind::kKey;
        event.key_down = down;
        event.key_code = key_code;
        event.unicode_code_point = unicode_code_point;
        event.meta_state = meta_state;
        event.repeat_count = repeat_count;
        event.event_time = event_time;
        ++queue_size_;
        counters_.queued_input_count = queue_size_;
        return kRuntimeOk;
    }

    bool snapshot(std::uint64_t* output, std::size_t count) const {
        if (output == nullptr || count < kCounterCount) {
            return false;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        RuntimeCounters copy = counters_;
        copy.queued_input_count = queue_size_;
        const GlobalCounterSnapshot global = global_counter_snapshot();
        copy.write_to(output, count, global.destroy_count,
                      global.rejected_operation_count);
        return true;
    }

 private:
    const int source_kind_;
    const std::string source_;
    const std::string save_directory_;
    const std::string game_id_;
    mutable std::mutex mutex_;
    CocosRuntime renderer_;
    bool surface_active_ = false;
    bool paused_ = false;
    bool alternate_color_ = false;
    std::array<InputEvent, kInputQueueCapacity> queue_{};
    std::size_t queue_head_ = 0U;
    std::size_t queue_size_ = 0U;
    RuntimeCounters counters_{};
};

std::shared_ptr<RuntimeState> find_state(RuntimeHandle handle) {
    if (handle == 0U) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    const auto found = g_registry.find(handle);
    return found == g_registry.end() ? nullptr : found->second;
}

template <typename Call>
int call_state(RuntimeHandle handle, Call&& call) {
    const std::shared_ptr<RuntimeState> state = find_state(handle);
    if (state == nullptr) {
        record_rejected_operation();
        return kRuntimeStaleHandle;
    }
    return call(*state);
}

}  // namespace

void RuntimeCounters::write_to(std::uint64_t* output,
                               std::size_t count,
                               std::uint64_t global_destroy_count,
                               std::uint64_t rejected_operation_count) const noexcept {
    if (output == nullptr || count < kCounterCount) {
        return;
    }
    output[0] = frame_count;
    output[1] = input_generation;
    output[2] = pause_count;
    output[3] = resume_count;
    output[4] = low_memory_count;
    output[5] = surface_generation;
    output[6] = surface_loss_count;
    output[7] = global_destroy_count;
    output[8] = dropped_input_count;
    output[9] = queued_input_count;
    output[10] = rejected_operation_count;
}

RuntimeHandle create(int source_kind,
                     const std::string& source,
                     const std::string& save_directory,
                     const std::string& game_id) {
    if (source_kind < 1 || source_kind > 3 || !valid_text(source) ||
        !valid_text(save_directory) || !valid_text(game_id)) {
        return 0U;
    }

    std::shared_ptr<RuntimeState> state;
    try {
        state = std::make_shared<RuntimeState>(source_kind, source, save_directory,
                                               game_id);
    } catch (...) {
        return 0U;
    }

    std::lock_guard<std::mutex> lock(g_registry_mutex);
    constexpr RuntimeHandle kMaxJniHandle =
        static_cast<RuntimeHandle>(std::numeric_limits<std::int64_t>::max());
    if (g_next_handle == 0U || g_next_handle > kMaxJniHandle) {
        return 0U;
    }
    const RuntimeHandle handle = g_next_handle++;
    g_registry.emplace(handle, std::move(state));
    return handle;
}

int surface_created(RuntimeHandle handle) {
    return call_state(handle, [](RuntimeState& state) { return state.surface_created(); });
}

int surface_changed(RuntimeHandle handle, int width, int height) {
    return call_state(handle, [width, height](RuntimeState& state) {
        return state.surface_changed(width, height);
    });
}

int draw_frame(RuntimeHandle handle) {
    return call_state(handle, [](RuntimeState& state) { return state.draw_frame(); });
}

int pause(RuntimeHandle handle) {
    return call_state(handle, [](RuntimeState& state) { return state.pause(); });
}

int resume(RuntimeHandle handle) {
    return call_state(handle, [](RuntimeState& state) { return state.resume(); });
}

int low_memory(RuntimeHandle handle) {
    return call_state(handle, [](RuntimeState& state) { return state.low_memory(); });
}

int surface_lost(RuntimeHandle handle) {
    return call_state(handle, [](RuntimeState& state) { return state.surface_lost(); });
}

int destroy(RuntimeHandle handle) {
    std::shared_ptr<RuntimeState> retired_state;
    {
        std::lock_guard<std::mutex> lock(g_registry_mutex);
        const auto found = g_registry.find(handle);
        if (found == g_registry.end()) {
            ++g_rejected_operation_count;
            return kRuntimeStaleHandle;
        }
        retired_state = found->second;
        g_registry.erase(found);
        ++g_global_destroy_count;
    }
    return kRuntimeOk;
}

int touch(RuntimeHandle handle,
          int action,
          int pointer_id,
          float x,
          float y,
          std::int64_t event_time) {
    return call_state(handle, [=](RuntimeState& state) {
        return state.touch(action, pointer_id, x, y, event_time);
    });
}

int key(RuntimeHandle handle,
        bool down,
        int key_code,
        int unicode_code_point,
        int meta_state,
        int repeat_count,
        std::int64_t event_time) {
    return call_state(handle, [=](RuntimeState& state) {
        return state.key(down, key_code, unicode_code_point, meta_state,
                         repeat_count, event_time);
    });
}

bool counters(RuntimeHandle handle,
              std::uint64_t* output,
              std::size_t count) {
    if (output == nullptr || count < kCounterCount) {
        return false;
    }
    if (handle == 0U) {
        const GlobalCounterSnapshot global = global_counter_snapshot();
        for (std::size_t index = 0U; index < kCounterCount; ++index) {
            output[index] = 0U;
        }
        output[7] = global.destroy_count;
        output[10] = global.rejected_operation_count;
        return true;
    }
    const std::shared_ptr<RuntimeState> state = find_state(handle);
    if (state == nullptr) {
        record_rejected_operation();
        return false;
    }
    return state->snapshot(output, count);
}

}  // namespace twinquill::krkr_runtime

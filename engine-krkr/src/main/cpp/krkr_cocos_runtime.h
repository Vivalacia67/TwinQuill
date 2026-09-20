/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#ifndef TWINQUILL_KRKR_COCOS_RUNTIME_H
#define TWINQUILL_KRKR_COCOS_RUNTIME_H

namespace twinquill::krkr_runtime {

// A deliberately small first-party GLES2 host. It owns only the program and
// shader objects needed by the B1 render proof; no external lifecycle object
// or Java reference is retained.
class CocosRuntime final {
 public:
    CocosRuntime() = default;
    ~CocosRuntime();

    CocosRuntime(const CocosRuntime&) = delete;
    CocosRuntime& operator=(const CocosRuntime&) = delete;
    CocosRuntime(CocosRuntime&&) = delete;
    CocosRuntime& operator=(CocosRuntime&&) = delete;

    int surface_created();
    int surface_changed(int width, int height);
    int draw_frame(bool alternate_color);
    int surface_lost();
    // The Android surface callback may replace a GL context without giving
    // this object a current context in which to delete the old objects.  This
    // method only abandons CPU-side names and is safe from destructors/UI
    // teardown paths.
    int abandon_context() noexcept;
    int destroy();

    bool ready() const noexcept { return program_ != 0U; }

 private:
    unsigned int program_ = 0U;
    int position_attribute_ = -1;
    int color_attribute_ = -1;
    int mvp_uniform_ = -1;
    int width_ = 0;
    int height_ = 0;
};

}  // namespace twinquill::krkr_runtime

#endif  // TWINQUILL_KRKR_COCOS_RUNTIME_H

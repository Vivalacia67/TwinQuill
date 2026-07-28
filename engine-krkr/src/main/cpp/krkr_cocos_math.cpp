/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_cocos_math.h"

#include <cmath>

#include "math/Mat4.h"
#include "math/Vec3.h"

namespace {

bool nearly_equal(float left, float right) {
    return std::fabs(left - right) <= 0.0001F;
}

}  // namespace

bool twinquill_krkr_cocos_math_ready() noexcept {
    cocos2d::Mat4 translation;
    cocos2d::Mat4::createTranslation(4.0F, -2.0F, 1.0F, &translation);
    const cocos2d::Vec3 source(2.0F, 3.0F, 4.0F);
    cocos2d::Vec3 result;
    translation.transformPoint(source, &result);
    return nearly_equal(result.x, 6.0F)
        && nearly_equal(result.y, 1.0F)
        && nearly_equal(result.z, 5.0F);
}

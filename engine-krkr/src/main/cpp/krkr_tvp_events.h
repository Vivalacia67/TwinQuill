/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once
#include "tjs.h"
void TVPAndroidRegisterAsync(TJS::iTJSDispatch2* owner);
void TVPAndroidUnregisterAsync(TJS::iTJSDispatch2* owner);
namespace twinquill::krkr {
void pump_tvp_events();
void reset_tvp_timer_clocks();
void clear_tvp_events();
void shutdown_tvp_timers();
void shutdown_tvp_asyncs();
}

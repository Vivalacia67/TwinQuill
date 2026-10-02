/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once
#include "TimerIntf.h"

// Android platform half of the pinned upstream TimerIntf class. All calls run
// on the session worker; Android/GL threads never touch TJS timer objects.
class tTJSNI_Timer : public tTJSNI_BaseTimer {
public:
    tjs_error TJS_INTF_METHOD Construct(tjs_int count, tTJSVariant** args,
                                       iTJSDispatch2* owner) override;
    void TJS_INTF_METHOD Invalidate() override;
    void SetInterval(tjs_uint64 interval);
    tjs_uint64 GetInterval() const { return interval_; }
    void SetEnabled(bool enabled);
    bool GetEnabled() const { return enabled_; }
    void Pump(tjs_uint64 now);
    void ResetClock();
    void ShutdownOwner();
private:
    tjs_uint64 interval_ = 1000ULL << TVP_SUBMILLI_FRAC_BITS;
    tjs_uint64 next_ = 0;
    bool enabled_ = false;
};

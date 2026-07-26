/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

#include "tjs.h"
#include "tjsNative.h"

#include <string>

class tTVPCompactEventCallbackIntf {
public:
    virtual ~tTVPCompactEventCallbackIntf() = default;
    virtual void TJS_INTF_METHOD OnCompact(tjs_int level) = 0;
};

constexpr tjs_int TVP_COMPACT_LEVEL_DEACTIVATE = 10;

extern const tjs_char* TVPInternalError;

TJS::iTJSTextReadStream* TVPCreateTextStreamForRead(const ttstr& name, const ttstr& modestr);
TJS::iTJSTextWriteStream* TVPCreateTextStreamForWrite(const ttstr& name, const ttstr& modestr);
void TVPSetDefaultReadEncoding(const ttstr& encoding);
const tjs_char* TVPGetDefaultReadEncoding();

void TVPAddLog(const ttstr& line);
void TVPAddImportantLog(const ttstr& line);

void TVPThrowExceptionMessage(const tjs_char* message);
void TVPThrowExceptionMessage(const tjs_char* message, const ttstr& parameter);
void TVPThrowExceptionMessage(
    const tjs_char* message,
    const ttstr& parameter1,
    const ttstr& parameter2);
void TVPThrowExceptionMessage(
    const tjs_char* message,
    const ttstr& parameter1,
    tjs_int parameter2);
ttstr TVPFormatMessage(const tjs_char* message, const ttstr& parameter);
ttstr TVPFormatMessage(
    const tjs_char* message,
    const ttstr& parameter1,
    const ttstr& parameter2);

void TVPAddCompactEventHook(tTVPCompactEventCallbackIntf* callback);
void TVPRemoveCompactEventHook(tTVPCompactEventCallbackIntf* callback);

namespace twinquill::krkr {

class KagRuntimeScope final {
public:
    KagRuntimeScope(TJS::tTJS* engine, TJS::iTJSDispatch2* context);
    ~KagRuntimeScope() noexcept;

    KagRuntimeScope(const KagRuntimeScope&) = delete;
    KagRuntimeScope& operator=(const KagRuntimeScope&) = delete;

    static TJS::tTJS* current_engine();
    static TJS::iTJSDispatch2* current_context();

private:
    bool active_ = false;
};

}  // namespace twinquill::krkr
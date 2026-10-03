/* SPDX-License-Identifier: GPL-2.0-or-later */
#pragma once
#include "../krkr_tvp_visual.h"
#include "tjs.h"
#include "tjsNative.h"
#include "tjsArray.h"
#include "tjsDictionary.h"
#include "tjsError.h"
#include "tjsUtils.h"
#include "tjsHashSearch.h"
#include <vector>
using namespace TJS;
namespace TJS { void TJSCheckExecutionBudget(); }
inline constexpr const tjs_char* TVPInternalError = TJS_W("Internal KAG error");
iTJSTextReadStream* TVPCreateTextStreamForRead(const ttstr&, const ttstr&);
void TVPExecuteExpression(const ttstr&, iTJSDispatch2*, tTJSVariant*);
void TVPAddLog(const ttstr&);
inline ttstr TVPExtractStorageName(const ttstr& name) {
    const tjs_char* last = name.c_str();
    for (const tjs_char* p=last; *p; ++p) if (*p=='/' || *p=='>') last=p+1;
    return ttstr(last);
}
template<class A> void TVPThrowExceptionMessage(const tjs_char* message, const A& a) {
    ttstr formatted(message); formatted.Replace(TJS_W("%1"), ttstr(a)); TJS_eTJSError(formatted);
}
template<class A, class B> void TVPThrowExceptionMessage(const tjs_char* message, const A& a, const B& b) {
    ttstr formatted(message); formatted.Replace(TJS_W("%1"), ttstr(a)); formatted.Replace(TJS_W("%2"), ttstr(b)); TJS_eTJSError(formatted);
}
extern iTJSDispatch2* TVPCreateNativeClass_KAGParser();
extern void TVPClearScnearioCache();

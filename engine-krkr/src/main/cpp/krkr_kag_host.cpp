/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "tjs.h"
#include "tjsError.h"
#include "krkr_kag_host_script.h"
namespace { TJS::tTJS* kag_engine = nullptr; }
void TVPRegisterAndroidKagHost(TJS::tTJS* engine) {
    kag_engine = engine;
    ttstr name(TJS_W("AndroidKagHost.tjs"));
    engine->ExecScript(kAndroidKagHost, nullptr, nullptr, &name);
}

iTJSDispatch2* TVPCreateAndroidMenu(iTJSDispatch2* owner) {
    tTJSVariant menu;
    if (!kag_engine || TJS_FAILED(kag_engine->GetGlobalNoAddRef()->PropGet(0,
            TJS_W("MenuItem"), nullptr, &menu, kag_engine->GetGlobalNoAddRef())))
        TJS::TJS_eTJSError(TJS_W("KAG menu host is unavailable"));
    tTJSVariant target(owner, owner), caption(TJS_W("")), *args[] = {&target, &caption};
    iTJSDispatch2* object = nullptr;
    if (TJS_FAILED(menu.AsObjectClosureNoAddRef().CreateNew(0, nullptr, nullptr,
            &object, 2, args, nullptr))) TJS::TJS_eTJSError(TJS_W("KAG menu creation failed"));
    return object;
}

void TVPShutdownAndroidKagHost() { kag_engine = nullptr; }

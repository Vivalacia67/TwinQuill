/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_kag_probe.h"

#include "krkr_kag_platform.h"
#include "krkr_storage_registry.h"
#include "KAGParser.h"
#include "StorageIntf.h"
#include "tjsError.h"

#include <android/log.h>

#include <cstddef>
#include <string>

void TVPClearScnearioCache();

namespace twinquill::krkr {
namespace {

constexpr char kLogTag[] = "TwinQuill/Krkr";
constexpr std::size_t kMaxKagTags = 65536;

class ScopedDispatch final {
public:
    explicit ScopedDispatch(iTJSDispatch2* dispatch) noexcept : dispatch_(dispatch) {}
    ~ScopedDispatch() noexcept { if (dispatch_ != nullptr) { dispatch_->Release(); } }
    ScopedDispatch(const ScopedDispatch&) = delete;
    ScopedDispatch& operator=(const ScopedDispatch&) = delete;
    iTJSDispatch2* get() const { return dispatch_; }
private:
    iTJSDispatch2* dispatch_;
};

class ScopedScenarioCacheClear final {
public:
    ScopedScenarioCacheClear() { TVPClearScnearioCache(); }
    ~ScopedScenarioCacheClear() noexcept { try { TVPClearScnearioCache(); } catch (...) {} }
    ScopedScenarioCacheClear(const ScopedScenarioCacheClear&) = delete;
    ScopedScenarioCacheClear& operator=(const ScopedScenarioCacheClear&) = delete;
};

class ScopedKagParser final {
public:
    explicit ScopedKagParser(iTJSDispatch2* owner) {
        if (owner == nullptr) {
            throw TJS::eTJSError(TJS_W("KAG parser owner is unavailable"));
        }
        const tjs_error construct_result = parser_.Construct(0, nullptr, owner);
        if (TJS_FAILED(construct_result)) {
            TJS::TJSThrowFrom_tjs_error(construct_result);
        }
        constructed_ = true;
        parser_.SetDebugLevel(tkdlNone);
    }
    ~ScopedKagParser() noexcept { if (constructed_) { try { parser_.Invalidate(); } catch (...) {} } }
    ScopedKagParser(const ScopedKagParser&) = delete;
    ScopedKagParser& operator=(const ScopedKagParser&) = delete;
    tTJSNI_KAGParser& get() { return parser_; }
private:
    tTJSNI_KAGParser parser_;
    bool constructed_ = false;
};

int parse_kag_scenario(const ttstr& storage_name) {
    if (KagRuntimeScope::current_engine() == nullptr || KagRuntimeScope::current_context() == nullptr) {
        return 41;
    }
    try {
        ScopedScenarioCacheClear scenario_cache_clear;
        ScopedKagParser parser(KagRuntimeScope::current_context());
        parser.get().LoadScenario(storage_name);
        bool emitted_tag = false;
        for (std::size_t tag_index = 0; tag_index < kMaxKagTags; ++tag_index) {
            ScopedDispatch dispatch(parser.get().GetNextTag());
            if (dispatch.get() == nullptr) {
                return emitted_tag ? 0 : 41;
            }
            emitted_tag = true;
        }
    } catch (const TJS::eTJS& exception) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "KAG parser rejected scenario: %s", exception.GetMessage().AsNarrowStdString().c_str());
        return 41;
    } catch (const std::exception& exception) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", exception.what());
        return 41;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Unknown KAG parser failure");
        return 41;
    }
    return 41;
}

}  // namespace

int probe_kag_scenario(const std::string& scenario_name) {
    if (scenario_name.empty()) {
        return 40;
    }
    const std::string storage_name = scenario_name.find("://") == std::string::npos
        ? std::string(kTwinQuillStorageMediaName) + "://./" + scenario_name
        : scenario_name;
    const ttstr tjs_storage_name(storage_name.c_str());
    if (!TVPIsExistentStorageNoSearch(tjs_storage_name)) {
        return 40;
    }
    return parse_kag_scenario(tjs_storage_name);
}

}  // namespace twinquill::krkr
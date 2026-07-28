/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_kag_runtime.h"

#include "tjsError.h"
#include "tjsNative.h"
#include "math/CCAffineTransform.h"
#include "math/CCGeometry.h"

#include <algorithm>
#include <cstddef>

namespace {

constexpr std::size_t kMaxTags = 65536;
constexpr std::size_t kMaxTextCodeUnits = 1024 * 1024;
constexpr float kGlyphAdvance = 8.0F;
constexpr float kLineHeight = 16.0F;
constexpr float kLayoutOffsetX = 4.0F;
constexpr float kLayoutOffsetY = -2.0F;

#undef TJS_NATIVE_SET_ClassID
#define TJS_NATIVE_SET_ClassID ClassID_TwinQuillKagRuntime = TJS_NCM_CLASSID;
TJS::tjs_int32 ClassID_TwinQuillKagRuntime = -1;

[[noreturn]] void throw_runtime_error(const TJS::tjs_char* message) {
    throw TJS::eTJSError(message);
}

bool get_string_member(
    TJS::tTJSVariantClosure object,
    const TJS::tjs_char* name,
    bool required,
    TJS::ttstr* value) {
    if (object.Object == nullptr || value == nullptr) {
        throw_runtime_error(TJS_W("KAG runtime requires a tag dictionary"));
    }
    TJS::tTJSVariant member;
    const TJS::tjs_error result = object.PropGet(
        TJS_MEMBERMUSTEXIST,
        name,
        nullptr,
        &member,
        object.SelectObjectNoAddRef());
    if (result == TJS_E_MEMBERNOTFOUND && !required) {
        value->Clear();
        return false;
    }
    if (TJS_FAILED(result)) {
        TJS::TJSThrowFrom_tjs_error(result, name);
    }
    if (member.Type() != TJS::tvtString) {
        throw_runtime_error(TJS_W("KAG runtime tag fields must be strings"));
    }
    *value = TJS::ttstr(member);
    if (required && value->IsEmpty()) {
        throw_runtime_error(TJS_W("KAG runtime tag fields must not be empty"));
    }
    return true;
}

class KagRuntimeInstance final : public TJS::tTJSNativeInstance {
public:
    void Consume(const TJS::tTJSVariant& tag) {
        if (finished_) {
            throw_runtime_error(TJS_W("KAG runtime is already finished"));
        }
        if (tag.Type() != TJS::tvtObject) {
            throw_runtime_error(TJS_W("KAG runtime requires a tag dictionary"));
        }
        if (tag_count_ >= kMaxTags) {
            throw_runtime_error(TJS_W("KAG runtime tag limit exceeded"));
        }

        TJS::tTJSVariantClosure object = tag.AsObjectClosureNoAddRef();
        TJS::ttstr tag_name;
        get_string_member(object, TJS_W("tagname"), true, &tag_name);

        TJS::ttstr text;
        TJS::ttstr wait_time;
        TJS::ttstr wait_can_skip;
        const bool is_character = tag_name == TJS_W("ch");
        const bool is_line_break = tag_name == TJS_W("r");
        const bool is_wait = tag_name == TJS_W("wait");
        std::size_t next_line_code_units = current_line_code_units_;
        std::size_t next_max_line_code_units = max_line_code_units_;
        if (is_character) {
            get_string_member(object, TJS_W("text"), true, &text);
            const std::size_t text_length = static_cast<std::size_t>(text.GetLen());
            const std::size_t current_length = static_cast<std::size_t>(text_.GetLen());
            if (text_length > kMaxTextCodeUnits - current_length) {
                throw_runtime_error(TJS_W("KAG runtime text limit exceeded"));
            }
            next_line_code_units += text_length;
            next_max_line_code_units = std::max(next_max_line_code_units, next_line_code_units);
        } else if (is_wait) {
            get_string_member(object, TJS_W("time"), true, &wait_time);
            get_string_member(object, TJS_W("canskip"), false, &wait_can_skip);
        }

        ++tag_count_;
        last_tag_name_ = tag_name;
        if (is_character) {
            text_ += text;
            current_line_code_units_ = next_line_code_units;
            max_line_code_units_ = next_max_line_code_units;
        } else if (is_line_break) {
            max_line_code_units_ = std::max(max_line_code_units_, current_line_code_units_);
            current_line_code_units_ = 0;
            ++line_break_count_;
        } else if (is_wait) {
            ++wait_count_;
            last_wait_time_ = wait_time;
            last_wait_can_skip_ = wait_can_skip;
        }
    }

    void Finish() {
        if (finished_) {
            throw_runtime_error(TJS_W("KAG runtime is already finished"));
        }
        if (tag_count_ == 0) {
            throw_runtime_error(TJS_W("KAG runtime cannot finish without tags"));
        }

        const std::size_t layout_line_width =
            std::max(max_line_code_units_, current_line_code_units_);
        const std::size_t layout_line_count = line_break_count_ + 1;
        const cocos2d::Size layout_size(
            static_cast<float>(layout_line_width) * kGlyphAdvance,
            static_cast<float>(layout_line_count) * kLineHeight);
        const cocos2d::Rect layout_bounds(0.0F, 0.0F, layout_size.width, layout_size.height);
        const cocos2d::AffineTransform layout_transform = cocos2d::AffineTransformTranslate(
            cocos2d::AffineTransform::IDENTITY,
            kLayoutOffsetX,
            kLayoutOffsetY);
        const cocos2d::Rect transformed_bounds =
            cocos2d::RectApplyAffineTransform(layout_bounds, layout_transform);

        cocos_layout_bounds_ = transformed_bounds;
        cocos_layout_ready_ = true;
        finished_ = true;
    }

    TJS::tjs_int64 TagCount() const { return static_cast<TJS::tjs_int64>(tag_count_); }
    const TJS::ttstr& Text() const { return text_; }
    TJS::tjs_int64 LineBreakCount() const {
        return static_cast<TJS::tjs_int64>(line_break_count_);
    }
    TJS::tjs_int64 WaitCount() const { return static_cast<TJS::tjs_int64>(wait_count_); }
    const TJS::ttstr& LastWaitTime() const { return last_wait_time_; }
    const TJS::ttstr& LastWaitCanSkip() const { return last_wait_can_skip_; }
    const TJS::ttstr& LastTagName() const { return last_tag_name_; }
    bool Finished() const { return finished_; }
    bool CocosLayoutReady() const { return cocos_layout_ready_; }
    double CocosLayoutX() const { return static_cast<double>(cocos_layout_bounds_.origin.x); }
    double CocosLayoutY() const { return static_cast<double>(cocos_layout_bounds_.origin.y); }
    double CocosLayoutWidth() const { return static_cast<double>(cocos_layout_bounds_.size.width); }
    double CocosLayoutHeight() const { return static_cast<double>(cocos_layout_bounds_.size.height); }

private:
    std::size_t tag_count_ = 0;
    TJS::ttstr text_;
    std::size_t line_break_count_ = 0;
    std::size_t wait_count_ = 0;
    TJS::ttstr last_wait_time_;
    TJS::ttstr last_wait_can_skip_;
    TJS::ttstr last_tag_name_;
    std::size_t current_line_code_units_ = 0;
    std::size_t max_line_code_units_ = 0;
    cocos2d::Rect cocos_layout_bounds_;
    bool cocos_layout_ready_ = false;
    bool finished_ = false;
};

TJS::iTJSNativeInstance* TJS_INTF_METHOD create_runtime_instance() {
    return new KagRuntimeInstance();
}

}  // namespace

TJS::iTJSDispatch2* TVPCreateNativeClass_TwinQuillKagRuntime() {
    TJS::tTJSNativeClassForPlugin* class_object = TJS::TJSCreateNativeClassForPlugin(
        TJS_W("TwinQuillKagRuntime"),
        create_runtime_instance);

#undef TJS_NCM_REG_THIS
#define TJS_NCM_REG_THIS class_object

    TJS_BEGIN_NATIVE_MEMBERS(TwinQuillKagRuntime)
    TJS_DECL_EMPTY_FINALIZE_METHOD

    TJS_BEGIN_NATIVE_CONSTRUCTOR_DECL(
        instance,
        KagRuntimeInstance,
        TwinQuillKagRuntime) {
        return TJS_S_OK;
    }
    TJS_END_NATIVE_CONSTRUCTOR_DECL(TwinQuillKagRuntime)

    TJS_BEGIN_NATIVE_METHOD_DECL(consume) {
        TJS_GET_NATIVE_INSTANCE(instance, KagRuntimeInstance);
        if (numparams != 1) {
            return TJS_E_BADPARAMCOUNT;
        }
        instance->Consume(*param[0]);
        return TJS_S_OK;
    }
    TJS_END_NATIVE_METHOD_DECL(consume)

    TJS_BEGIN_NATIVE_METHOD_DECL(finish) {
        TJS_GET_NATIVE_INSTANCE(instance, KagRuntimeInstance);
        if (numparams != 0) {
            return TJS_E_BADPARAMCOUNT;
        }
        instance->Finish();
        return TJS_S_OK;
    }
    TJS_END_NATIVE_METHOD_DECL(finish)

#define TWINQUILL_READ_ONLY_PROPERTY(name, expression) \
    TJS_BEGIN_NATIVE_PROP_DECL(name) { \
        TJS_BEGIN_NATIVE_PROP_GETTER { \
            TJS_GET_NATIVE_INSTANCE(instance, KagRuntimeInstance); \
            *result = (expression); \
            return TJS_S_OK; \
        } TJS_END_NATIVE_PROP_GETTER \
        TJS_DENY_NATIVE_PROP_SETTER \
    } TJS_END_NATIVE_PROP_DECL(name)

    TWINQUILL_READ_ONLY_PROPERTY(tagCount, instance->TagCount())
    TWINQUILL_READ_ONLY_PROPERTY(text, instance->Text())
    TWINQUILL_READ_ONLY_PROPERTY(lineBreakCount, instance->LineBreakCount())
    TWINQUILL_READ_ONLY_PROPERTY(waitCount, instance->WaitCount())
    TWINQUILL_READ_ONLY_PROPERTY(lastWaitTime, instance->LastWaitTime())
    TWINQUILL_READ_ONLY_PROPERTY(lastWaitCanSkip, instance->LastWaitCanSkip())
    TWINQUILL_READ_ONLY_PROPERTY(lastTagName, instance->LastTagName())
    TWINQUILL_READ_ONLY_PROPERTY(finished, static_cast<TJS::tjs_int>(instance->Finished()))
    TWINQUILL_READ_ONLY_PROPERTY(
        cocosLayoutReady,
        static_cast<TJS::tjs_int>(instance->CocosLayoutReady()))
    TWINQUILL_READ_ONLY_PROPERTY(cocosLayoutX, instance->CocosLayoutX())
    TWINQUILL_READ_ONLY_PROPERTY(cocosLayoutY, instance->CocosLayoutY())
    TWINQUILL_READ_ONLY_PROPERTY(cocosLayoutWidth, instance->CocosLayoutWidth())
    TWINQUILL_READ_ONLY_PROPERTY(cocosLayoutHeight, instance->CocosLayoutHeight())

#undef TWINQUILL_READ_ONLY_PROPERTY

    TJS_END_NATIVE_MEMBERS
    return class_object;
}

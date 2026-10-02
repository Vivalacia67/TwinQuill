/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once
#include "tjsCommHead.h"
#include "tjsNative.h"
#include "tjsError.h"
#include "EventIntf.h"
#include "ComplexRect.h"
#include "drawable.h"
#include "krkr_display_frame.h"
#include <android/asset_manager.h>
#include <functional>
#include <string>
#include <vector>

// Platform classes for the explicitly admitted upstream Window/Layer/Font
// bindings. Unsupported members throw in patch 0006 instead of silently acting.
enum tTVPDrawFace { dfAlpha = 0, dfOpaque = 1, dfMask = 2, dfProvince = 3,
                   dfAddAlpha = 4, dfAuto = 128 };
constexpr tjs_uint32 clNone = 0x1fffffff;
inline const tjs_char* TVPSpecifyLayer = TJS_W("Specify a valid Layer");
inline const tjs_char* TVPWindowHasNoLayer = TJS_W("Window has no primary Layer");
inline void TVPThrowExceptionMessage(const tjs_char* message) { TJS::TJS_eTJSError(message); }
inline tTVPRect TVPAndroidRect(tjs_int x, tjs_int y, tjs_int width, tjs_int height) {
    if (x < -32768 || x > 32768 || y < -32768 || y > 32768
            || width < 0 || width > 2048 || height < 0 || height > 2048)
        TVPThrowExceptionMessage(TJS_W("Rectangle exceeds supported range"));
    return tTVPRect(x, y, x + width, y + height);
}

class tTJSNI_Window;
class tTJSNI_Layer;
using tTJSNI_BaseLayer = tTJSNI_Layer;
using iTVPLayerTreeOwner = tTJSNI_Window;

class tTJSNC_Window : public tTJSNativeClass {
public:
    static tjs_uint32 ClassID;
    tTJSNC_Window();
    tTJSNativeInstance* CreateNativeInstance() override;
};
class tTJSNC_Layer : public tTJSNativeClass {
public:
    static tjs_uint32 ClassID;
    tTJSNC_Layer();
    tTJSNativeInstance* CreateNativeInstance() override;
};
class tTJSNC_Font : public tTJSNativeClass {
public:
    static tjs_uint32 ClassID;
    tTJSNC_Font();
    tTJSNativeInstance* CreateNativeInstance() override;
};

class tTJSNI_Window : public tTJSNativeInstance {
public:
    tjs_error TJS_INTF_METHOD Construct(tjs_int, tTJSVariant**, iTJSDispatch2*) override;
    void TJS_INTF_METHOD Invalidate() override;
    void Close();
    void OnCloseQueryCalled(bool allowed) { close_allowed = allowed; }
    void SetInnerSize(tjs_int width, tjs_int height);
    void SetSize(tjs_int width, tjs_int height) { SetInnerSize(width, height); }
    void SetWidth(tjs_int value) { SetInnerSize(value, height); }
    void SetHeight(tjs_int value) { SetInnerSize(width, value); }
    tjs_int GetWidth() const { return width; }
    tjs_int GetHeight() const { return height; }
    tjs_int GetInnerWidth() const { return width; }
    tjs_int GetInnerHeight() const { return height; }
    void SetInnerWidth(tjs_int value) { SetWidth(value); }
    void SetInnerHeight(tjs_int value) { SetHeight(value); }
    bool GetVisible() const { return visible; }
    void SetVisible(bool value);
    void GetCaption(ttstr& value) const { value = caption; }
    void SetCaption(const ttstr& value) { caption = value; }
    iTJSDispatch2* GetOwnerNoAddRef() const { return owner; }
    tTJSNI_Window* GetDrawDevice() { return this; }
    tTJSNI_Layer* GetPrimaryLayer() const { return primary; }
    tTJSNI_Layer* GetFocusedLayer() const { return focused; }
    void SetFocusedLayer(tTJSNI_Layer* value);

    iTJSDispatch2* owner = nullptr;
    tTJSNI_Layer* primary = nullptr;
    tTJSNI_Layer* focused = nullptr;
    int width = 640, height = 480;
    bool visible = false, close_allowed = true;
    ttstr caption;
};
extern tTJSNI_Window* TVPMainWindow;

class tTJSNI_Layer : public tTJSNativeInstance {
public:
    tjs_error TJS_INTF_METHOD Construct(tjs_int, tTJSVariant**, iTJSDispatch2*) override;
    void TJS_INTF_METHOD Invalidate() override;
    void SetSize(tjs_uint width, tjs_uint height);
    void SetBounds(const tTVPRect& value);
    void SetPosition(tjs_int x, tjs_int y);
    void SetLeft(tjs_int value) { SetPosition(value, top); }
    void SetTop(tjs_int value) { SetPosition(left, value); }
    void SetWidth(tjs_uint value) { SetSize(value, height); }
    void SetHeight(tjs_uint value) { SetSize(width, value); }
    tjs_int GetLeft() const { return left; }
    tjs_int GetTop() const { return top; }
    tjs_int GetWidth() const { return width; }
    tjs_int GetHeight() const { return height; }
    void SetImageSize(tjs_uint width, tjs_uint height);
    void SetImageWidth(tjs_uint value) { SetImageSize(value, image_height); }
    void SetImageHeight(tjs_uint value) { SetImageSize(image_width, value); }
    tjs_int GetImageWidth() const { return image_width; }
    tjs_int GetImageHeight() const { return image_height; }
    void SetImagePosition(tjs_int x, tjs_int y);
    void SetImageLeft(tjs_int value) { SetImagePosition(value, image_top); }
    void SetImageTop(tjs_int value) { SetImagePosition(image_left, value); }
    tjs_int GetImageLeft() const { return image_left; }
    tjs_int GetImageTop() const { return image_top; }
    void SetVisible(bool value);
    bool GetVisible() const { return visible; }
    bool GetNodeVisible() const;
    void SetEnabled(bool value) { enabled = value; }
    bool GetEnabled() const { return enabled; }
    void SetOpacity(tjs_int value);
    tjs_int GetOpacity() const { return opacity; }
    void SetType(tTVPLayerType value);
    tTVPLayerType GetType() const { return type; }
    void SetFace(tTVPDrawFace value);
    tTVPDrawFace GetFace() const { return face; }
    void SetHoldAlpha(bool value) { hold_alpha = value; }
    bool GetHoldAlpha() const { return hold_alpha; }
    void SetName(const ttstr& value) { name = value; }
    const ttstr& GetName() const { return name; }
    bool IsPrimary() const { return window && window->primary == this; }
    tTJSNI_Layer* GetParent() const { return parent; }
    void SetParent(tTJSNI_Layer* value);
    int GetOrderIndex() const;
    void SetOrderIndex(tjs_int value);
    void BringToFront();
    void BringToBack();
    iTJSDispatch2* GetOwnerNoAddRef() const { return owner; }
    iTVPLayerTreeOwner* GetLayerTreeOwner() const { return window; }
    tTJSVariantClosure GetActionOwnerNoAddRef() const;
    iTJSDispatch2* GetFontObjectNoAddRef();
    iTJSDispatch2* LoadImages(const ttstr& name, tjs_uint32 key);
    void FillRect(const tTVPRect& rect, tjs_uint32 color);
    void DrawText(tjs_int x, tjs_int y, const ttstr& text, tjs_uint32 color,
                  tjs_int opacity, bool aa, tjs_int shadow, tjs_uint32 shadow_color,
                  tjs_int shadow_width, tjs_int shadow_x, tjs_int shadow_y);
    tjs_uint32 GetMainPixel(tjs_int x, tjs_int y) const;
    void SetMainPixel(tjs_int x, tjs_int y, tjs_uint32 value);
    void UpdateByScript();
    void UpdateByScript(const tTVPRect&) { UpdateByScript(); }
    void SetClip(tjs_int x, tjs_int y, tjs_int width, tjs_int height);
    void ResetClip();
    tjs_int GetClipLeft() const { return clip.left; }
    tjs_int GetClipTop() const { return clip.top; }
    tjs_int GetClipWidth() const { return clip.right - clip.left; }
    tjs_int GetClipHeight() const { return clip.bottom - clip.top; }
    void SetClipLeft(tjs_int value) { SetClip(value, clip.top, GetClipWidth(), GetClipHeight()); }
    void SetClipTop(tjs_int value) { SetClip(clip.left, value, GetClipWidth(), GetClipHeight()); }
    void SetClipWidth(tjs_int value) { SetClip(clip.left, clip.top, value, GetClipHeight()); }
    void SetClipHeight(tjs_int value) { SetClip(clip.left, clip.top, GetClipWidth(), value); }

    iTJSDispatch2* owner = nullptr;
    iTJSDispatch2* font_object = nullptr;
    tTJSNI_Window* window = nullptr;
    tTJSNI_Layer* parent = nullptr;
    std::vector<tTJSNI_Layer*> children;
    std::vector<tjs_uint32> pixels;
    int left = 0, top = 0, width = 32, height = 32;
    int image_left = 0, image_top = 0, image_width = 32, image_height = 32;
    int opacity = 255, font_height = 24;
    bool visible = false, enabled = true, hold_alpha = true;
    tTVPLayerType type = ltAlpha;
    tTVPDrawFace face = dfAuto;
    tTVPRect clip{0, 0, 32, 32};
    ttstr name;
};

class tTJSNI_Font : public tTJSNativeInstance {
public:
    tjs_error TJS_INTF_METHOD Construct(tjs_int, tTJSVariant**, iTJSDispatch2*) override;
    void TJS_INTF_METHOD Invalidate() override { layer = nullptr; retained_layer.Clear(); }
    ttstr GetFontFace() const;
    void SetFontFace(const ttstr& value);
    tjs_int GetFontHeight() const;
    void SetFontHeight(tjs_int value);
    tjs_int GetTextWidth(const ttstr& text) const;
    tjs_int GetTextHeight(const ttstr& text) const;
private:
    tTJSNI_Layer* layer = nullptr;
    tTJSVariant retained_layer;
    int height = 24;
};

tTJSNativeClass* TVPCreateNativeClass_Window();
tTJSNativeClass* TVPCreateNativeClass_Layer();
tTJSNativeClass* TVPCreateNativeClass_Font();
namespace twinquill::krkr {
void set_visual_assets(AAssetManager* assets);
void begin_visual_session(std::function<std::string(const ttstr&)> read,
                          std::function<void()> exit, int width, int height);
void shutdown_visual_session();
void end_visual_session();
void publish_visual_frame();
void visual_event(int kind, const std::vector<double>& args);
}

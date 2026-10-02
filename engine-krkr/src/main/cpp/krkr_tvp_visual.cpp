/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_tvp_visual.h"
#include "krkr_tjs_execution.h"
#include "krkr_tjs_text.h"
#include "tjsDictionary.h"
#include "cocos/platform/CCImage.h"
#include <ft2build.h>
#include FT_FREETYPE_H
#include <algorithm>
#include <atomic>
#include <cmath>
#include <map>
#include <mutex>

tTJSNI_Window* TVPMainWindow = nullptr;
namespace {
using namespace twinquill::krkr;
constexpr int kSide = 2048, kPosition = 32768, kLayerLimit = 64;
constexpr std::size_t kPixelBudget = 8U * 1024U * 1024U;
std::atomic<AAssetManager*> asset_manager{nullptr};
std::mutex frame_mutex;
std::shared_ptr<const DisplayFrame> latest_frame;
std::uint64_t generation = 0;
int surface_width = 0, surface_height = 0;

struct VisualContext {
    std::function<std::string(const ttstr&)> read;
    std::function<void()> exit;
    std::vector<tTJSVariant> owners;
    std::vector<tTJSNI_Layer*> layers;
    std::map<int, tTJSVariant> captures;
    int primary_pointer = -1;
    int game_width = 640, game_height = 480;
    bool had_window = false;
    tTJSNativeClass* font_class = nullptr; // VM owns it.
    FT_Library library = nullptr;
    FT_Face font = nullptr;
    std::vector<unsigned char> font_data;
    bool dirty = false, closing = false;
    ~VisualContext() {
        if (font) FT_Done_Face(font);
        if (library) FT_Done_FreeType(library);
    }
};
std::unique_ptr<VisualContext> visual;

void error(const tjs_char* text) { TJS::TJS_eTJSError(text); }
void dirty() { if (visual) visual->dirty = true; }
void dimensions(tjs_uint width, tjs_uint height) {
    if (width == 0 || height == 0 || width > kSide || height > kSide)
        error(TJS_W("Display/image dimensions must be 1..2048"));
}
void position(int x, int y) {
    if (x < -kPosition || x > kPosition || y < -kPosition || y > kPosition)
        error(TJS_W("Layer position exceeds supported range"));
}
void allocation(tTJSNI_Layer* replacing, std::size_t area, bool image) {
    std::size_t used = area;
    for (auto* layer : visual->layers) {
        if (layer == replacing) continue;
        used += image ? layer->pixels.size() : std::size_t(layer->width) * layer->height;
    }
    if (used > kPixelBudget) error(TJS_W("Layer pixel budget exceeded"));
}
void forget(iTJSDispatch2* owner) {
    if (!visual || !owner) return;
    TVPCancelSourceEvents(owner);
    for (auto it = visual->captures.begin(); it != visual->captures.end();) {
        if (it->second.AsObjectNoAddRef() == owner) it = visual->captures.erase(it);
        else ++it;
    }
    visual->owners.erase(std::remove_if(visual->owners.begin(), visual->owners.end(),
        [owner](const tTJSVariant& value) { return value.AsObjectNoAddRef() == owner; }),
        visual->owners.end());
}
tTJSVariant call(iTJSDispatch2* owner, const tjs_char* name,
                 std::vector<tTJSVariant> values = {}) {
    tTJSVariant result;
    if (!owner || owner->IsValid(0, nullptr, nullptr, owner) != TJS_S_TRUE) return result;
    std::vector<tTJSVariant*> args;
    for (auto& value : values) args.push_back(&value);
    const auto status = owner->FuncCall(0, name, nullptr, &result,
        static_cast<tjs_int>(args.size()), args.data(), owner);
    if (status != TJS_E_MEMBERNOTFOUND && TJS_FAILED(status)) error(TJS_W("Window/Layer event failed"));
    return result;
}
template<class Instance> Instance* instance(const tTJSVariant& value, tjs_uint32 class_id) {
    auto* object = value.AsObjectNoAddRef();
    Instance* result = nullptr;
    if (!object || TJS_FAILED(object->NativeInstanceSupport(TJS_NIS_GETINSTANCE,
            class_id, reinterpret_cast<iTJSNativeInstance**>(&result))))
        error(TJS_W("Invalid Window/Layer object"));
    return result;
}
void detach(tTJSNI_Layer* layer) {
    if (layer->parent) {
        auto& list = layer->parent->children;
        list.erase(std::remove(list.begin(), list.end(), layer), list.end());
        layer->parent = nullptr;
    }
}
unsigned subtree_depth(tTJSNI_Layer* layer) {
    unsigned depth = 1;
    for (auto* child : layer->children) depth = std::max(depth, 1 + subtree_depth(child));
    return depth;
}
void font_size(int height) {
    if (height < 8 || height > 128) error(TJS_W("Font height must be 8..128"));
}
FT_Face font_face(int height) {
    const auto size = height < 0 ? -std::int64_t(height) : height;
    font_size(size);
    if (!visual->font) {
        auto* manager = asset_manager.load();
        if (!manager) error(TJS_W("Android font assets are unavailable"));
        auto* asset = AAssetManager_open(manager,
            "noto-sans-cjk-sc-2.004/NotoSansCJKsc-Regular.otf", AASSET_MODE_BUFFER);
        if (!asset) error(TJS_W("Audited fallback font is missing"));
        struct AssetCloser { AAsset* value; ~AssetCloser() { AAsset_close(value); } } closer{asset};
        const auto length = AAsset_getLength64(asset);
        if (length <= 0 || length > 32 * 1024 * 1024) error(TJS_W("Invalid font size"));
        visual->font_data.resize(static_cast<std::size_t>(length));
        std::size_t offset = 0;
        while (offset < visual->font_data.size()) {
            const auto count = AAsset_read(asset, visual->font_data.data() + offset,
                visual->font_data.size() - offset);
            if (count <= 0) error(TJS_W("Unable to read fallback font"));
            offset += static_cast<std::size_t>(count);
        }
        if (FT_Init_FreeType(&visual->library)
            || FT_New_Memory_Face(visual->library, visual->font_data.data(),
                static_cast<FT_Long>(length), 0, &visual->font))
            error(TJS_W("Unable to open audited font"));
    }
    if (FT_Set_Pixel_Sizes(visual->font, 0, static_cast<FT_UInt>(size)))
        error(TJS_W("Unable to set font height"));
    return visual->font;
}
std::vector<std::uint32_t> codepoints(const ttstr& text) {
    if (text.GetLen() > 4096) error(TJS_W("Text exceeds 4096 UTF-16 units"));
    std::vector<std::uint32_t> result;
    for (int i = 0; i < text.GetLen(); ++i) {
        auto point = static_cast<std::uint32_t>(text.c_str()[i]);
        if (point >= 0xd800 && point <= 0xdbff) {
            if (++i >= text.GetLen()) error(TJS_W("Invalid text surrogate"));
            const auto low = static_cast<std::uint32_t>(text.c_str()[i]);
            if (low < 0xdc00 || low > 0xdfff) error(TJS_W("Invalid text surrogate"));
            point = 0x10000 + ((point - 0xd800) << 10) + low - 0xdc00;
        } else if (point >= 0xdc00 && point <= 0xdfff) error(TJS_W("Invalid text surrogate"));
        result.push_back(point);
    }
    return result;
}
std::uint32_t blend(std::uint32_t destination, std::uint32_t source, int opacity) {
    const unsigned alpha = ((source >> 24) * static_cast<unsigned>(opacity) + 127) / 255;
    const unsigned dest_alpha = destination >> 24;
    const unsigned out_alpha = alpha + (dest_alpha * (255 - alpha) + 127) / 255;
    if (!out_alpha) return 0;
    std::uint32_t result = out_alpha << 24;
    for (unsigned shift : {0U, 8U, 16U}) {
        const unsigned channel = (((source >> shift) & 255) * alpha * 255
            + ((destination >> shift) & 255) * dest_alpha * (255 - alpha)
            + out_alpha * 127) / (out_alpha * 255);
        result |= std::min(255U, channel) << shift;
    }
    return result;
}
std::vector<std::uint32_t> compose(tTJSNI_Layer* layer) {
    std::vector<std::uint32_t> result(std::size_t(layer->width) * layer->height, 0);
    for (int y = 0; y < layer->height; ++y) {
        check_execution();
        const auto iy = y - layer->image_top;
        if (iy < 0 || iy >= layer->image_height) continue;
        for (int x = 0; x < layer->width; ++x) {
            const auto ix = x - layer->image_left;
            if (ix < 0 || ix >= layer->image_width) continue;
            auto pixel = layer->pixels[std::size_t(iy) * layer->image_width + ix];
            if (layer->type == ltOpaque) pixel |= 0xff000000;
            result[std::size_t(y) * layer->width + x] = pixel;
        }
    }
    for (auto* child : layer->children) {
        if (!child->visible || child->opacity == 0) continue;
        auto pixels = compose(child);
        for (int y = std::max(0, child->top); y < std::min(layer->height, child->top + child->height); ++y) {
            check_execution();
            for (int x = std::max(0, child->left); x < std::min(layer->width, child->left + child->width); ++x) {
                auto& output = result[std::size_t(y) * layer->width + x];
                output = blend(output, pixels[std::size_t(y - child->top) * child->width
                    + x - child->left], child->opacity);
            }
        }
    }
    return result;
}
tTJSNI_Layer* hit(tTJSNI_Layer* layer, int x, int y) {
    if (!layer || !layer->visible || !layer->enabled || x < 0 || y < 0
        || x >= layer->width || y >= layer->height) return nullptr;
    for (auto it = layer->children.rbegin(); it != layer->children.rend(); ++it) {
        if (auto* found = hit(*it, x - (*it)->left, y - (*it)->top)) return found;
    }
    const int ix = x - layer->image_left, iy = y - layer->image_top;
    if (layer->type == ltOpaque || (ix >= 0 && iy >= 0 && ix < layer->image_width
        && iy < layer->image_height && (layer->pixels[std::size_t(iy) * layer->image_width + ix] >> 24) > 16))
        return layer;
    return nullptr;
}
std::pair<int, int> local(tTJSNI_Layer* layer, int x, int y) {
    for (auto* node = layer; node && node->parent; node = node->parent) {
        x -= node->left; y -= node->top;
    }
    return {x, y};
}
int virtual_key(int key) {
    if (key >= 29 && key <= 54) return key - 29 + 'A';
    if (key >= 7 && key <= 16) return key - 7 + '0';
    switch (key) {
        case 19: return 38; case 20: return 40; case 21: return 37; case 22: return 39;
        case 23: case 66: return 13; case 61: return 9; case 62: return 32;
        case 67: return 8; case 111: return 27; case 112: return 46;
        default: return 0;
    }
}
}

tjs_error TJS_INTF_METHOD tTJSNI_Window::Construct(tjs_int, tTJSVariant**, iTJSDispatch2* object) {
    if (execution_is_cleanup()) error(TJS_W("Cannot create Window during shutdown"));
    if (!visual || TVPMainWindow) error(TJS_W("This Android host supports one Window per session"));
    owner = object;
    TVPMainWindow = this;
    visual->had_window = true;
    visual->owners.emplace_back(owner, owner);
    dirty();
    return TJS_S_OK;
}
void TJS_INTF_METHOD tTJSNI_Window::Invalidate() {
    if (!owner) return;
    for (auto* layer : visual->layers) if (layer->window == this) layer->window = nullptr;
    primary = focused = nullptr;
    if (TVPMainWindow == this) TVPMainWindow = nullptr;
    auto* previous = owner;
    owner = nullptr;
    forget(previous);
    dirty();
}
void tTJSNI_Window::Close() {
    if (!owner || visual->closing) return;
    tTJSVariant retained(owner, owner);
    visual->closing = true;
    struct Reset { ~Reset() { if (visual) visual->closing = false; } } reset;
    close_allowed = true;
    auto result = call(owner, TJS_W("onCloseQuery"), {tTJSVariant(1)});
    if (result.Type() != tvtVoid) close_allowed = result.operator bool();
    if (!close_allowed) return;
    visual->exit();
}
void tTJSNI_Window::SetInnerSize(tjs_int w, tjs_int h) {
    dimensions(w, h);
    width = w; height = h; dirty();
    visual->game_width = w; visual->game_height = h;
}
void tTJSNI_Window::SetVisible(bool value) { visible = value; dirty(); }
void tTJSNI_Window::SetFocusedLayer(tTJSNI_Layer* value) {
    if (value && value->window != this) error(TJS_W("Focused Layer belongs to another Window"));
    focused = value;
}

tjs_error TJS_INTF_METHOD tTJSNI_Layer::Construct(tjs_int count, tTJSVariant** args, iTJSDispatch2* object) {
    if (execution_is_cleanup()) error(TJS_W("Cannot create Layer during shutdown"));
    if (count < 2) return TJS_E_BADPARAMCOUNT;
    if (!visual || visual->layers.size() >= kLayerLimit) error(TJS_W("Layer limit exceeded"));
    auto* requested = instance<tTJSNI_Window>(*args[0], tTJSNC_Window::ClassID);
    tTJSNI_Layer* requested_parent = nullptr;
    if (args[1]->AsObjectNoAddRef()) requested_parent = instance<tTJSNI_Layer>(*args[1], tTJSNC_Layer::ClassID);
    if (requested_parent && requested_parent->window != requested) error(TJS_W("Layer belongs to another Window"));
    unsigned depth = 0;
    for (auto* node = requested_parent; node; node = node->parent)
        if (++depth >= 32) error(TJS_W("Layer tree depth exceeds 32"));
    if (!requested_parent && requested->primary) error(TJS_W("Window already has a primary Layer"));
    owner = object; window = requested; parent = requested_parent;
    pixels.resize(32 * 32, 0);
    if (parent) parent->children.push_back(this);
    else { window->primary = this; type = ltOpaque; }
    visual->layers.push_back(this);
    visual->owners.emplace_back(owner, owner);
    dirty();
    return TJS_S_OK;
}
void TJS_INTF_METHOD tTJSNI_Layer::Invalidate() {
    if (!owner) return;
    detach(this);
    for (auto* child : children) child->parent = nullptr;
    children.clear();
    if (window && window->primary == this) window->primary = nullptr;
    if (window && window->focused == this) window->focused = nullptr;
    window = nullptr;
    if (font_object) {
        font_object->Invalidate(0, nullptr, nullptr, font_object);
        font_object->Release(); font_object = nullptr;
    }
    visual->layers.erase(std::remove(visual->layers.begin(), visual->layers.end(), this), visual->layers.end());
    pixels.clear();
    auto* previous = owner; owner = nullptr;
    forget(previous); dirty();
}
void tTJSNI_Layer::SetSize(tjs_uint w, tjs_uint h) {
    dimensions(w, h); allocation(this, std::size_t(w) * h, false);
    width = w; height = h; dirty();
}
void tTJSNI_Layer::SetBounds(const tTVPRect& value) {
    position(value.left, value.top);
    const auto w = std::int64_t(value.right) - value.left;
    const auto h = std::int64_t(value.bottom) - value.top;
    if (w < 1 || h < 1 || w > kSide || h > kSide) error(TJS_W("Invalid Layer bounds"));
    SetSize(w, h);
    SetPosition(value.left, value.top);
}
void tTJSNI_Layer::SetPosition(tjs_int x, tjs_int y) {
    position(x, y);
    if (IsPrimary() && (x != 0 || y != 0)) error(TJS_W("Primary Layer position must be zero"));
    left = x; top = y; dirty();
}
void tTJSNI_Layer::SetImageSize(tjs_uint w, tjs_uint h) {
    dimensions(w, h); allocation(this, std::size_t(w) * h, true);
    std::vector<tjs_uint32> replacement(std::size_t(w) * h, 0);
    for (int y = 0; y < std::min<int>(h, image_height); ++y)
        std::copy_n(pixels.data() + std::size_t(y) * image_width,
            std::min<int>(w, image_width), replacement.data() + std::size_t(y) * w);
    pixels.swap(replacement); image_width = w; image_height = h; ResetClip(); dirty();
}
void tTJSNI_Layer::SetImagePosition(tjs_int x, tjs_int y) { position(x, y); image_left = x; image_top = y; dirty(); }
void tTJSNI_Layer::SetVisible(bool value) { visible = value; dirty(); }
bool tTJSNI_Layer::GetNodeVisible() const {
    for (auto* node = this; node; node = node->parent) if (!node->visible) return false;
    return window && window->visible;
}
void tTJSNI_Layer::SetOpacity(tjs_int value) {
    if (value < 0 || value > 255) error(TJS_W("Layer opacity must be 0..255"));
    opacity = value; dirty();
}
void tTJSNI_Layer::SetType(tTVPLayerType value) {
    if (value != ltOpaque && value != ltAlpha) error(TJS_W("Only opaque/alpha Layers are supported in M2"));
    type = value; dirty();
}
void tTJSNI_Layer::SetFace(tTVPDrawFace value) {
    if (value != dfAuto && value != dfOpaque && value != dfAlpha) error(TJS_W("Unsupported draw face"));
    face = value;
}
void tTJSNI_Layer::SetParent(tTJSNI_Layer* value) {
    if (IsPrimary() || !value || value->window != window) error(TJS_W("Cannot detach or reparent this Layer"));
    unsigned depth = 0;
    for (auto* node = value; node; node = node->parent) {
        if (node == this || ++depth > 32) error(TJS_W("Layer parent cycle or excessive depth"));
    }
    if (depth + subtree_depth(this) > 32) error(TJS_W("Layer tree depth exceeds 32"));
    detach(this); parent = value; parent->children.push_back(this); dirty();
}
int tTJSNI_Layer::GetOrderIndex() const {
    if (!parent) return 0;
    return static_cast<int>(std::find(parent->children.begin(), parent->children.end(), this) - parent->children.begin());
}
void tTJSNI_Layer::SetOrderIndex(tjs_int value) {
    if (!parent) { if (value != 0) error(TJS_W("Primary Layer order must be zero")); return; }
    if (value < 0 || std::size_t(value) >= parent->children.size()) error(TJS_W("Invalid Layer order"));
    auto& list = parent->children;
    list.erase(std::find(list.begin(), list.end(), this));
    list.insert(list.begin() + value, this); dirty();
}
void tTJSNI_Layer::BringToFront() { if (parent) SetOrderIndex(parent->children.size() - 1); }
void tTJSNI_Layer::BringToBack() { SetOrderIndex(0); }
tTJSVariantClosure tTJSNI_Layer::GetActionOwnerNoAddRef() const {
    return window ? tTJSVariantClosure(window->owner, window->owner) : tTJSVariantClosure(nullptr, nullptr);
}
iTJSDispatch2* tTJSNI_Layer::GetFontObjectNoAddRef() {
    if (!font_object) {
        tTJSVariant arg(owner), *args = &arg;
        if (TJS_FAILED(visual->font_class->CreateNew(0, nullptr, nullptr,
                &font_object, 1, &args, visual->font_class))) error(TJS_W("Unable to construct Layer font"));
    }
    return font_object;
}
iTJSDispatch2* tTJSNI_Layer::LoadImages(const ttstr& name, tjs_uint32 key) {
    if (key != clNone) error(TJS_W("Image color keys are not supported in M2"));
    const auto bytes = visual->read(name);
    cocos2d::Image image;
    if (!image.initWithImageData(reinterpret_cast<const unsigned char*>(bytes.data()), bytes.size()))
        error(TJS_W("Unable to decode PNG/JPEG image"));
    dimensions(image.getWidth(), image.getHeight());
    const auto count = std::size_t(image.getWidth()) * image.getHeight();
    allocation(this, count, true);
    const auto length = image.getDataLen();
    const int channels = image.hasAlpha() ? 4 : 3;
    if (length != static_cast<ssize_t>(count * channels)) error(TJS_W("Unsupported image pixel format"));
    std::vector<tjs_uint32> replacement(count);
    const auto* source = image.getData();
    for (std::size_t i = 0; i < count; ++i) {
        auto r = source[i * channels], g = source[i * channels + 1], b = source[i * channels + 2];
        const unsigned a = channels == 4 ? source[i * channels + 3] : 255;
        if (image.hasPremultipliedAlpha() && a && a != 255) {
            r = std::min(255U, unsigned(r) * 255 / a);
            g = std::min(255U, unsigned(g) * 255 / a);
            b = std::min(255U, unsigned(b) * 255 / a);
        }
        replacement[i] = (a << 24) | (unsigned(r) << 16) | (unsigned(g) << 8) | b;
    }
    pixels.swap(replacement); image_width = image.getWidth(); image_height = image.getHeight();
    ResetClip(); dirty();
    return TJS::TJSCreateDictionaryObject();
}
void tTJSNI_Layer::FillRect(const tTVPRect& rect, tjs_uint32 color) {
    const bool opaque = face == dfOpaque || (face == dfAuto && type == ltOpaque);
    for (int y = std::max(rect.top, clip.top); y < std::min(rect.bottom, clip.bottom); ++y) {
        check_execution();
        for (int x = std::max(rect.left, clip.left); x < std::min(rect.right, clip.right); ++x) {
            auto& pixel = pixels[std::size_t(y) * image_width + x];
            pixel = opaque && hold_alpha ? ((pixel & 0xff000000) | (color & 0xffffff)) : color;
        }
    }
    dirty();
}
void tTJSNI_Layer::DrawText(tjs_int x, tjs_int y, const ttstr& text, tjs_uint32 color,
        tjs_int opa, bool aa, tjs_int shadow, tjs_uint32, tjs_int shadow_width,
        tjs_int shadow_x, tjs_int shadow_y) {
    if (opa < 0 || opa > 255 || shadow || shadow_width || shadow_x || shadow_y)
        error(TJS_W("M2 text supports 0..255 opacity and no shadow"));
    position(x, y);
    auto* font = font_face(font_height);
    const int start_x = x;
    int baseline = y + (font->size->metrics.ascender >> 6);
    FT_UInt previous = 0;
    for (auto point : codepoints(text)) {
        check_execution();
        if (point == '\n') { x = start_x; baseline += font->size->metrics.height >> 6; previous = 0; continue; }
        const auto glyph = FT_Get_Char_Index(font, point);
        if (previous && glyph && FT_HAS_KERNING(font)) {
            FT_Vector kerning{}; FT_Get_Kerning(font, previous, glyph, FT_KERNING_DEFAULT, &kerning);
            x += kerning.x >> 6;
        }
        if (FT_Load_Glyph(font, glyph, FT_LOAD_DEFAULT) || FT_Render_Glyph(font->glyph,
                aa ? FT_RENDER_MODE_NORMAL : FT_RENDER_MODE_MONO)) error(TJS_W("Unable to render glyph"));
        const auto& bitmap = font->glyph->bitmap;
        for (unsigned row = 0; row < bitmap.rows; ++row) {
            const int dy = baseline - font->glyph->bitmap_top + row;
            if (dy < clip.top || dy >= clip.bottom) continue;
            const auto* line = bitmap.buffer + (bitmap.pitch >= 0 ? row : bitmap.rows - 1 - row) * std::abs(bitmap.pitch);
            for (unsigned col = 0; col < bitmap.width; ++col) {
                const int dx = x + font->glyph->bitmap_left + col;
                if (dx < clip.left || dx >= clip.right) continue;
                const unsigned coverage = bitmap.pixel_mode == FT_PIXEL_MODE_MONO
                    ? ((line[col / 8] & (0x80 >> (col % 8))) ? 255 : 0) : line[col];
                auto& pixel = pixels[std::size_t(dy) * image_width + dx];
                const auto old_alpha = pixel & 0xff000000;
                const bool opaque = face == dfOpaque || (face == dfAuto && type == ltOpaque);
                pixel = blend(opaque ? pixel | 0xff000000 : pixel,
                    (color & 0xffffff) | (coverage << 24), opa);
                if (hold_alpha && opaque)
                    pixel = (pixel & 0xffffff) | old_alpha;
            }
        }
        x += font->glyph->advance.x >> 6; previous = glyph;
    }
    dirty();
}
tjs_uint32 tTJSNI_Layer::GetMainPixel(tjs_int x, tjs_int y) const {
    if (x < 0 || y < 0 || x >= image_width || y >= image_height) error(TJS_W("Pixel outside image"));
    return pixels[std::size_t(y) * image_width + x] & 0xffffff;
}
void tTJSNI_Layer::SetMainPixel(tjs_int x, tjs_int y, tjs_uint32 value) {
    (void)GetMainPixel(x, y);
    auto& pixel = pixels[std::size_t(y) * image_width + x];
    pixel = (pixel & 0xff000000) | (value & 0xffffff); dirty();
}
void tTJSNI_Layer::UpdateByScript() { call(owner, TJS_W("onPaint")); dirty(); }
void tTJSNI_Layer::SetClip(tjs_int x, tjs_int y, tjs_int w, tjs_int h) {
    if (x < 0 || y < 0 || w < 0 || h < 0 || x > image_width || y > image_height
        || w > image_width - x || h > image_height - y) error(TJS_W("Clip outside image"));
    clip = tTVPRect(x, y, x + w, y + h);
}
void tTJSNI_Layer::ResetClip() { clip = tTVPRect(0, 0, image_width, image_height); }

tjs_error TJS_INTF_METHOD tTJSNI_Font::Construct(tjs_int count, tTJSVariant** args, iTJSDispatch2*) {
    if (execution_is_cleanup()) error(TJS_W("Cannot create Font during shutdown"));
    if (count > 0 && args[0]->AsObjectNoAddRef()) {
        layer = instance<tTJSNI_Layer>(*args[0], tTJSNC_Layer::ClassID);
        retained_layer = tTJSVariant(layer->owner, layer->owner);
    }
    return TJS_S_OK;
}
ttstr tTJSNI_Font::GetFontFace() const { return TJS_W("Noto Sans CJK SC"); }
void tTJSNI_Font::SetFontFace(const ttstr& value) {
    if (value != TJS_W("Noto Sans CJK SC") && value != TJS_W("NotoSansCJKsc-Regular"))
        error(TJS_W("M2 uses the audited Noto Sans CJK SC fallback font"));
}
tjs_int tTJSNI_Font::GetFontHeight() const {
    if (layer && !layer->owner) error(TJS_W("Font Layer has been invalidated"));
    return layer ? layer->font_height : height;
}
void tTJSNI_Font::SetFontHeight(tjs_int value) {
    if (layer && !layer->owner) error(TJS_W("Font Layer has been invalidated"));
    const auto size = value < 0 ? -std::int64_t(value) : value;
    font_size(size);
    if (layer) layer->font_height = value; else height = value;
}
tjs_int tTJSNI_Font::GetTextWidth(const ttstr& text) const {
    auto* font = font_face(GetFontHeight());
    int width = 0, largest = 0; FT_UInt previous = 0;
    for (auto point : codepoints(text)) {
        check_execution();
        if (point == '\n') { largest = std::max(largest, width); width = 0; previous = 0; continue; }
        const auto glyph = FT_Get_Char_Index(font, point);
        if (previous && glyph && FT_HAS_KERNING(font)) {
            FT_Vector kerning{}; FT_Get_Kerning(font, previous, glyph, FT_KERNING_DEFAULT, &kerning);
            width += kerning.x >> 6;
        }
        if (FT_Load_Glyph(font, glyph, FT_LOAD_DEFAULT)) error(TJS_W("Unable to measure text"));
        width += font->glyph->advance.x >> 6; previous = glyph;
    }
    return std::max(largest, width);
}
tjs_int tTJSNI_Font::GetTextHeight(const ttstr& text) const {
    auto* font = font_face(GetFontHeight());
    const auto points = codepoints(text);
    return (1 + std::count(points.begin(), points.end(), '\n')) * (font->size->metrics.height >> 6);
}
tTJSNativeInstance* tTJSNC_Window::CreateNativeInstance() { return new tTJSNI_Window(); }
tTJSNativeInstance* tTJSNC_Layer::CreateNativeInstance() { return new tTJSNI_Layer(); }
tTJSNativeInstance* tTJSNC_Font::CreateNativeInstance() { return new tTJSNI_Font(); }
tTJSNativeClass* TVPCreateNativeClass_Window() { return new tTJSNC_Window(); }
tTJSNativeClass* TVPCreateNativeClass_Layer() { return new tTJSNC_Layer(); }
tTJSNativeClass* TVPCreateNativeClass_Font() { return visual->font_class = new tTJSNC_Font(); }

namespace twinquill::krkr {
void set_visual_assets(AAssetManager* assets) { asset_manager.store(assets); }
void begin_visual_session(std::function<std::string(const ttstr&)> read,
        std::function<void()> exit, int width, int height) {
    visual = std::make_unique<VisualContext>();
    visual->read = std::move(read); visual->exit = std::move(exit);
    surface_width = width; surface_height = height;
    std::lock_guard<std::mutex> lock(frame_mutex); latest_frame.reset();
}
void shutdown_visual_session() {
    if (!visual) return;
    visual->captures.clear();
    while (!visual->owners.empty()) {
        tTJSVariant owner = visual->owners.back();
        auto* object = owner.AsObjectNoAddRef();
        object->Invalidate(0, nullptr, nullptr, object);
    }
    TVPMainWindow = nullptr;
    std::lock_guard<std::mutex> lock(frame_mutex); latest_frame.reset();
}
void end_visual_session() { visual.reset(); }
std::shared_ptr<const DisplayFrame> display_frame() {
    std::lock_guard<std::mutex> lock(frame_mutex); return latest_frame;
}
void publish_visual_frame() {
    if (!visual || !visual->dirty) return;
    if (!TVPMainWindow && !visual->had_window) {
        std::lock_guard<std::mutex> lock(frame_mutex); latest_frame.reset();
        visual->dirty = false; return;
    }
    auto* window = TVPMainWindow;
    auto frame = std::make_shared<DisplayFrame>();
    frame->width = window ? window->width : visual->game_width;
    frame->height = window ? window->height : visual->game_height;
    frame->generation = ++generation;
    frame->rgba.resize(std::size_t(frame->width) * frame->height * 4, 0);
    std::vector<std::uint32_t> pixels;
    auto* primary = window ? window->primary : nullptr;
    if (window && window->visible && primary && primary->visible) pixels = compose(primary);
    for (int y = 0; y < frame->height; ++y) {
        check_execution();
        for (int x = 0; x < frame->width; ++x) {
            auto color = 0xff000000U;
            if (!pixels.empty() && x < primary->width && y < primary->height)
                color = blend(color, pixels[std::size_t(y) * primary->width + x], primary->opacity);
            auto* output = frame->rgba.data() + (std::size_t(y) * frame->width + x) * 4;
            output[0] = (color >> 16) & 255; output[1] = (color >> 8) & 255;
            output[2] = color & 255; output[3] = 255;
        }
    }
    std::lock_guard<std::mutex> lock(frame_mutex);
    latest_frame = std::move(frame); visual->dirty = false;
}
void visual_event(int kind, const std::vector<double>& args) {
    if (kind == 5 && args.size() == 2) { surface_width = args[0]; surface_height = args[1]; }
    auto* window = TVPMainWindow;
    if (!visual || !window || !window->visible) return;
    tTJSVariant retained(window->owner, window->owner);
    if (kind == 2 || kind == 3) {
        if (kind == 2) visual->captures.clear();
        call(window->owner, kind == 2 ? TJS_W("onDeactivate") : TJS_W("onActivate"));
    } else if (kind == 5) call(window->owner, TJS_W("onResize"));
    else if (kind == 0 && args.size() == 5) {
        // Letterboxing uses the same centered fit as the GLES frame uploader.
        const auto frame = display_frame();
        if (!frame) return;
        const double scale = std::min(double(surface_width) / window->width,
                                     double(surface_height) / window->height);
        if (scale <= 0) return;
        const auto game_x = (args[2] - (surface_width - window->width * scale) / 2) / scale;
        const auto game_y = (args[3] - (surface_height - window->height * scale) / 2) / scale;
        if (game_x < -kPosition || game_x > kPosition || game_y < -kPosition || game_y > kPosition) return;
        const int x = std::floor(game_x), y = std::floor(game_y);
        const int raw_action = args[0], id = args[1];
        const int action = raw_action == 5 ? 0 : raw_action == 6 ? 1 : raw_action;
        if (raw_action == 0) { visual->captures.clear(); visual->primary_pointer = id; }
        const bool outside = x < 0 || y < 0 || x >= window->width || y >= window->height;
        if (outside && visual->captures.find(id) == visual->captures.end()) return;
        auto* target = hit(window->primary, x, y);
        tTJSVariant target_owner = target ? tTJSVariant(target->owner, target->owner) : tTJSVariant();
        if (action == 0 && target && visual->captures.size() < 10)
            visual->captures.insert_or_assign(id, tTJSVariant(target->owner, target->owner));
        const auto captured = visual->captures.find(id);
        tTJSVariant capture = captured == visual->captures.end() ? tTJSVariant() : captured->second;
        if (capture.Type() == tvtObject) target = instance<tTJSNI_Layer>(capture, tTJSNC_Layer::ClassID);
        if (action == 3) { visual->captures.erase(id); return; }
        const tjs_char* touch_name = action == 0 ? TJS_W("onTouchDown")
            : action == 1 ? TJS_W("onTouchUp") : TJS_W("onTouchMove");
        call(window->owner, touch_name, {tTJSVariant(x), tTJSVariant(y), tTJSVariant(0), tTJSVariant(0), tTJSVariant(id)});
        if (target && target->owner) {
            const auto xy = local(target, x, y);
            call(target->owner, touch_name, {tTJSVariant(xy.first), tTJSVariant(xy.second), tTJSVariant(0), tTJSVariant(0), tTJSVariant(id)});
        }
        if (id == visual->primary_pointer && action <= 2) {
            const auto* mouse = action == 0 ? TJS_W("onMouseDown")
                : action == 1 ? TJS_W("onMouseUp") : TJS_W("onMouseMove");
            std::vector<tTJSVariant> values{tTJSVariant(x), tTJSVariant(y)};
            if (action != 2) values.emplace_back(0); // mbLeft
            values.emplace_back(action != 1 && capture.Type() == tvtObject ? 8 : 0); // TVP_SS_LEFT
            call(window->owner, mouse, values);
            if (target && target->owner) {
                const auto xy = local(target, x, y);
                values[0] = xy.first; values[1] = xy.second;
                call(target->owner, mouse, values);
                if (action == 1 && hit(window->primary, x, y) == target)
                    call(target->owner, TJS_W("onClick"), {tTJSVariant(xy.first), tTJSVariant(xy.second)});
            }
        }
        if (action == 1) visual->captures.erase(id);
    } else if (kind == 1 && args.size() == 6) {
        const auto key = virtual_key(args[1]);
        if (!key) return;
        const bool down = args[0] != 0;
        const auto* name = down ? TJS_W("onKeyDown") : TJS_W("onKeyUp");
        // Native Android meta bits do not match TVP shift flags.
        const int meta = args[3];
        const int shift = ((meta & 1) ? 1 : 0) | ((meta & 2) ? 2 : 0)
            | ((meta & 0x1000) ? 4 : 0) | (args[4] > 0 ? 128 : 0);
        call(window->owner, name, {tTJSVariant(key), tTJSVariant(shift)});
        if (window->focused && window->focused->owner)
            call(window->focused->owner, name, {tTJSVariant(key), tTJSVariant(shift), tTJSVariant(1)});
        const int code = args[2];
        if (down && code > 0 && code <= 0x10ffff && !(code >= 0xd800 && code <= 0xdfff)) {
            std::basic_string<tjs_char> text;
            if (code <= 0xffff) text.push_back(code);
            else { text.push_back(0xd800 + ((code - 0x10000) >> 10)); text.push_back(0xdc00 + ((code - 0x10000) & 1023)); }
            const tTJSVariant character(ttstr(text.c_str()));
            call(window->owner, TJS_W("onKeyPress"), {character});
            if (window->focused && window->focused->owner)
                call(window->focused->owner, TJS_W("onKeyPress"), {character, tTJSVariant(1)});
        }
    }
}
}

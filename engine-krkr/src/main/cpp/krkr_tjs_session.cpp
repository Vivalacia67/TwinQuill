/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_tjs_session.h"
#include "krkr_tjs_entry.h"
#include "krkr_tjs_execution.h"
#include "krkr_tvp_events.h"
#include "krkr_tvp_visual.h"
#include "krkr_tjs_text.h"
#include "krkr_vfs_storage.h"
#include "krkr_xp3.h"
#include "krkr_resource.h"
#include "krkr_private_storage.h"
#include "krkr_game_text.h"
#include "tjsArray.h"
#include "tjs.h"
#include "tjsError.h"
#include "tjsNative.h"
#include "TimerIntf.h"
#include "krkr_kag_support.h"
#include "krkr_kag_audio.h"

#include <android/log.h>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <functional>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>

void TVPRegisterAndroidKagHost(TJS::tTJS*);
void TVPRecoverAndroidKagSaves(TJS::tTJS*);
void TVPShutdownAndroidKagHost();

namespace twinquill::krkr {
namespace {
using namespace TJS;
constexpr int kNormalExit = 100;
std::mutex g_session_registry_mutex;
std::atomic<std::int64_t> g_startups{0}, g_releases{0};
std::uint64_t g_next_session = 1;

ttstr tjs_text(std::string_view source) {
    const auto decoded = decode_tjs_source(source);
    return ttstr(std::basic_string<tjs_char>(decoded.begin(), decoded.end()));
}

std::string utf8_text(const ttstr& value) {
    return encode_tjs_utf8(std::u16string_view(value.c_str(), value.GetLen()));
}

class Console final : public iTJSConsoleOutput {
public:
    void ExceptionPrint(const tjs_char* text) override { log(ANDROID_LOG_ERROR, text); }
    void Print(const tjs_char* text) override { log(ANDROID_LOG_INFO, text); }
private:
    static void log(int priority, const tjs_char* text) noexcept {
        try {
            const std::string message = utf8_text(ttstr(text == nullptr ? TJS_W("") : text));
            __android_log_print(priority, "TwinQuill/Krkr", "%s", message.c_str());
        } catch (...) { __android_log_print(priority, "TwinQuill/Krkr", "Invalid TJS diagnostic"); }
    }
};

using Method = std::function<tjs_error(tTJSVariant*, tjs_int, tTJSVariant**)>;
class BoundMethod final : public tTJSDispatch {
public:
    explicit BoundMethod(Method method) : method_(std::move(method)) {}
    tjs_error TJS_INTF_METHOD FuncCall(tjs_uint32, const tjs_char* member,
        tjs_uint32*, tTJSVariant* result, tjs_int count, tTJSVariant** args,
        iTJSDispatch2*) override {
        if (member != nullptr) return TJS_E_MEMBERNOTFOUND;
        try { return method_(result, count, args); }
        catch (const StorageError& error) {
            const ttstr message = tjs_text(std::string("Storage error: ") + error.what());
            TJS_eTJSError(message);
        }
        catch (const std::invalid_argument& error) {
            TJS_eTJSError(tjs_text(error.what()));
        }
        return TJS_E_FAIL;
    }
private:
    Method method_;
};

class DataPathProperty final : public tTJSNativeClassProperty {
public:
    DataPathProperty() : tTJSNativeClassProperty(nullptr, nullptr) {}
    tjs_error TJS_INTF_METHOD PropGet(tjs_uint32, const tjs_char* member,
        tjs_uint32*, tTJSVariant* result, iTJSDispatch2*) override {
        if (member) return TJS_E_MEMBERNOTFOUND;
        if (result) *result = ttstr(kDataPath);
        return TJS_S_OK;
    }
};
class FlagProperty final : public tTJSNativeClassProperty {
public:
    FlagProperty(bool* value, std::function<void(bool)> setter)
        : tTJSNativeClassProperty(nullptr, nullptr), value_(value), setter_(std::move(setter)) {}
    tjs_error TJS_INTF_METHOD PropGet(tjs_uint32, const tjs_char* member,
            tjs_uint32*, tTJSVariant* result, iTJSDispatch2*) override {
        if (member) return TJS_E_MEMBERNOTFOUND;
        if (result) *result = *value_;
        return TJS_S_OK;
    }
    tjs_error TJS_INTF_METHOD PropSet(tjs_uint32, const tjs_char* member,
            tjs_uint32*, const tTJSVariant* value, iTJSDispatch2*) override {
        if (member) return TJS_E_MEMBERNOTFOUND;
        setter_(value->operator bool());
        return TJS_S_OK;
    }
private:
    bool* value_;
    std::function<void(bool)> setter_;
};
class ScreenProperty final : public tTJSNativeClassProperty {
public:
    explicit ScreenProperty(const int* value) : tTJSNativeClassProperty(nullptr, nullptr), value_(value) {}
    tjs_error TJS_INTF_METHOD PropGet(tjs_uint32, const tjs_char* member,
            tjs_uint32*, tTJSVariant* result, iTJSDispatch2*) override {
        if (member) return TJS_E_MEMBERNOTFOUND;
        if (result) *result = *value_;
        return TJS_S_OK;
    }
private:
    const int* value_;
};
class TextReader final : public iTJSTextReadStream {
public:
    explicit TextReader(ttstr text) : text_(std::move(text)) {}
    tjs_uint TJS_INTF_METHOD Read(ttstr& target, tjs_uint size) override {
        const auto available = static_cast<tjs_uint>(text_.GetLen()) - position_;
        const auto count = size == 0 ? available : std::min(size, available);
        target = ttstr(text_.c_str() + position_, static_cast<tjs_int>(count));
        position_ += count;
        return count;
    }
    void TJS_INTF_METHOD Destruct() override { delete this; }
private:
    ttstr text_;
    tjs_uint position_ = 0;
};
class AtomicTextWriter final : public iTJSTextWriteStream {
public:
    explicit AtomicTextWriter(std::function<void(std::string_view)> commit) : commit_(std::move(commit)) {}
    void TJS_INTF_METHOD Write(const ttstr& text) override {
        try {
            const auto bytes = utf8_text(text);
            if (bytes.size() > kResourceReadLimit - bytes_.size())
                TJS_eTJSError(TJS_W("Private text serialization exceeds 32 MiB"));
            bytes_ += bytes;
        } catch (...) { failed_ = true; throw; }
    }
    void TJS_INTF_METHOD Abort() override { delete this; }
    void TJS_INTF_METHOD Destruct() override {
        std::unique_ptr<AtomicTextWriter> self(this);
        if (!failed_) {
            try { commit_(bytes_); }
            catch (const StorageError&) { TJS_eTJSError(TJS_W("Atomic private text commit failed")); }
        }
    }
private:
    std::function<void(std::string_view)> commit_;
    std::string bytes_;
    bool failed_ = false;
};
class BinaryReader final : public tTJSBinaryStream {
public:
    explicit BinaryReader(std::shared_ptr<ByteSource> source) : source_(std::move(source)) {}
    tjs_uint64 Seek(tjs_int64 offset, tjs_int whence) override {
        const auto base = whence == TJS_BS_SEEK_SET ? 0 : whence == TJS_BS_SEEK_CUR ? position_ : source_->size();
        if (whence < TJS_BS_SEEK_SET || whence > TJS_BS_SEEK_END)
            TJS_eTJSError(TJS_W("Invalid storage seek origin"));
        const auto magnitude = offset < 0 ? static_cast<std::uint64_t>(-(offset + 1)) + 1
            : static_cast<std::uint64_t>(offset);
        if ((offset < 0 && magnitude > base) || (offset >= 0 && magnitude > source_->size() - base))
            TJS_eTJSError(TJS_W("Storage seek exceeds stream extent"));
        position_ = offset < 0 ? base - magnitude : base + magnitude;
        return position_;
    }
    tjs_uint Read(void* output, tjs_uint size) override {
        const auto count = static_cast<tjs_uint>(std::min<std::uint64_t>(size, source_->size() - position_));
        try { source_->read(position_, output, count); }
        catch (const StorageError&) { TJS_eTJSError(TJS_W("Game binary stream read failed")); }
        position_ += count; return count;
    }
    tjs_uint Write(const void*, tjs_uint) override {
        TJS_eTJSError(TJS_W("Game binary streams are read-only")); return 0;
    }
    void SetEndOfStorage() override { TJS_eTJSError(TJS_W("Game binary streams are read-only")); }
    tjs_uint64 GetSize() override { return source_->size(); }
private:
    std::shared_ptr<ByteSource> source_;
    std::uint64_t position_ = 0;
};
tTJSNativeClass* create_kag_parser() { return static_cast<tTJSNativeClass*>(TVPCreateNativeClass_KAGParser()); }
class Session;
Session* g_text_session = nullptr;

class Session final {
public:
    Session(int kind, std::string source, const std::string& save)
        : kind_(kind), source_(std::move(source)), resources_(game_backend(kind_, source_)), private_(save),
          encoding_(game_text_encoding(resources_.configuration())) {}
    // Every engine operation and destruction is performed under tjs_engine_mutex().
    ~Session() noexcept {
        if (host_ != nullptr) host_->Release();
        if (engine_ != nullptr) {
            ExecutionScope cleanup(nullptr, std::chrono::seconds(2), true);
            continuous_handlers_.clear();
            TVPClearScnearioCache();
            clear_tvp_events();
            shutdown_visual_session();
            shutdown_tvp_timers();
            shutdown_tvp_asyncs();
            TVPShutdownAndroidKagHost();
            try { engine_->Shutdown(); } catch (...) { }
            engine_->Release();
            try { TVPControlAndroidAudio(0,11,0); } catch (...) {}
            if (g_text_session == this) {
                TJSCreateTextStreamForRead = old_text_read_;
                TJSCreateTextStreamForWrite = old_text_write_;
                TJSCreateBinaryStreamForRead = old_binary_read_;
                TJSCreateBinaryStreamForWrite = old_binary_write_;
                g_text_session = nullptr;
            }
            end_visual_session();
            ++g_releases;
        }
    }

    int prepare() {
        std::string source;
        const int result = read("startup.tjs", &source, true);
        if (result != 0) return result;
        // Decode before constructing the engine so malformed source owns no VM resources.
        script_ = game_text(source);
        kag_host_ = resources_.exists("system/Initialize.tjs");
        engine_ = new tTJS();
        engine_->SetConsoleOutput(&console_);
        engine_->SetPPValue(TJS_W("kirikiriz"), 0);
        return 0;
    }

    int activate(int width, int height) {
        if (width <= 0 || height <= 0) return 10;
        width_ = width;
        height_ = height;
        if (cancelled.load()) return kNormalExit;
        if (activated_) return status.load();
        activated_ = true;
        const int result = guarded([&] {
            begin_visual_session([this](const ttstr& name) {
                std::string bytes;
                const int result = read(utf8_text(name), &bytes);
                if (result != 0) storage_failure(result);
                return bytes;
            }, [this] { status.store(kNormalExit); }, width, height);
            register_classes();
            if (kag_host_) TVPRegisterAndroidKagHost(engine_);
            engine_->ExecScript(script_, nullptr, nullptr, &startup_name_);
            if (kag_host_) TVPRecoverAndroidKagSaves(engine_);
            script_.Clear();
        }, std::chrono::seconds(kag_host_ ? 20 : 5));
        if (result == 0) ++g_startups;
        return result;
    }

    int event(int event, const std::vector<double>& args) {
        static const tjs_char* names[] = {
            TJS_W("onTouch"), TJS_W("onKey"), TJS_W("onPause"),
            TJS_W("onResume"), TJS_W("onLowMemory"), TJS_W("onSurfaceChanged")
        };
        if (event < 0 || event > 6 || args.size() > 6) return 10;
        if (event == 5 && (args.size() != 2 || args[0] < 1 || args[1] < 1
            || args[0] > std::numeric_limits<int>::max()
            || args[1] > std::numeric_limits<int>::max())) return 10;
        if (status.load() != 0) return status.load();
        if (!activated_) return 0;
        return guarded([&] {
            if (event == 6) {
                if (!paused_ && !event_disabled_) {
                    pump_tvp_events();
                    const auto handlers = continuous_handlers_;
                    for (const auto& handler : handlers) {
                        if (std::none_of(continuous_handlers_.begin(), continuous_handlers_.end(),
                                [&](const auto& value) { return value.AsObjectNoAddRef() == handler.AsObjectNoAddRef(); })) continue;
                        if (TJS_FAILED(handler.AsObjectClosureNoAddRef().FuncCall(0, nullptr, nullptr,
                                nullptr, 0, nullptr, nullptr))) TJS_eTJSError(TJS_W("Continuous handler failed"));
                    }
                }
                return;
            }
            if (event == 2) { paused_ = true; TVPDeliverCompactEvent(TVP_COMPACT_LEVEL_DEACTIVATE); }
            if (event == 4) TVPDeliverCompactEvent(TVP_COMPACT_LEVEL_MAX);
            if (event == 3) {
                paused_ = false;
                TVPControlAndroidAudio(0,10,0);
                reset_tvp_timer_clocks();
            }
            if (event == 2) TVPControlAndroidAudio(0,9,0);
            if (event == 5 && args.size() == 2) {
                width_ = static_cast<int>(args[0]);
                height_ = static_cast<int>(args[1]);
            }
            if (event_disabled_ && (event == 0 || event == 1)) return;
            visual_event(event, args);
            tTJSVariant callback;
            const tjs_error get = host_->PropGet(0, names[event], nullptr, &callback, host_);
            if (get == TJS_E_MEMBERNOTFOUND || callback.Type() == tvtVoid) return;
            if (TJS_FAILED(get) || callback.Type() != tvtObject) {
                TJS_eTJSError(TJS_W("Host callback must be a function or void"));
            }
            std::vector<tTJSVariant> values;
            std::vector<tTJSVariant*> parameters;
            values.reserve(args.size());
            for (double arg : args) values.emplace_back(static_cast<tjs_real>(arg));
            for (auto& value : values) parameters.push_back(&value);
            const auto closure = callback.AsObjectClosureNoAddRef();
            const tjs_error result = closure.FuncCall(0, nullptr, nullptr, nullptr,
                static_cast<tjs_int>(parameters.size()), parameters.data(), host_);
            if (TJS_FAILED(result)) TJS_eTJSError(TJS_W("Host callback failed"));
            ++events;
        });
    }

    void evaluate_kag(const ttstr& expression, iTJSDispatch2* context, tTJSVariant* result) {
        engine_->EvalExpression(expression, result, context);
    }

    std::atomic<int> status{0};
    std::atomic<bool> cancelled{false};
    std::atomic<std::int64_t> color{-1}, events{0};
    const int kind_;
    const std::string source_;

private:
    template<class Call> int guarded(Call call,
            std::chrono::milliseconds duration = std::chrono::milliseconds(0)) {
        // A KAG Conductor callback may synchronously load several assets and
        // draw its first page before yielding, including real SAF/Binder work.
        if (duration.count() == 0) duration = std::chrono::seconds(kag_host_ ? 10 : 2);
        ExecutionScope execution(&cancelled, duration);
        try { call(); publish_visual_frame(); }
        catch (const eTJSSilent&) {
            status.store(execution.cancelled() ? kNormalExit : 20);
            if (!execution.cancelled()) {
                console_.ExceptionPrint(TJS_W("Script execution deadline exceeded"));
            }
        }
        catch (const eTJS& error) {
            console_.ExceptionPrint(error.GetMessage().c_str());
            if (const auto* script_error = dynamic_cast<const eTJSScriptError*>(&error))
                console_.ExceptionPrint(script_error->GetTrace().c_str());
            if (status.load() == 0 || status.load() == kNormalExit) status.store(20);
        } catch (const std::invalid_argument& error) {
            __android_log_print(ANDROID_LOG_ERROR, "TwinQuill/Krkr", "%s", error.what());
            status.store(20);
        } catch (...) { status.store(21); }
        // The exception and its script-block references are gone before VM shutdown.
        return status.load() == kNormalExit ? 0 : status.load();
    }

    ttstr game_text(std::string_view bytes, const std::string& mode = "") {
        const auto decoded = decode_game_text(bytes, mode.empty() ? encoding_ : mode);
        return ttstr(std::basic_string<tjs_char>(decoded.begin(), decoded.end()));
    }
    int read(const std::string& name, std::string* output, bool startup = false) {
        try {
            if (PrivateStorage::owns(name)) *output = private_.read(name);
            else *output = read_source(*resources_.open(name), startup ? kStartupSourceLimit : kResourceReadLimit);
            return startup && output->empty() ? 11 : 0;
        } catch (const StorageError& error) {
            __android_log_print(ANDROID_LOG_ERROR, "TwinQuill/Krkr", "%s", error.what());
            return error.status;
        }
    }

    void storage_failure(int result) {
        if (result == 40 || result == 41) status.store(result);
        TJS_eTJSError(TJS_W("Unable to read game storage"));
    }

    tTJSNativeClass* add_class(const tjs_char* name) {
        auto* object = new tTJSNativeClass(ttstr(name));
        tTJSVariant value(object, nullptr);
        object->Release();
        if (TJS_FAILED(engine_->GetGlobalNoAddRef()->PropSet(TJS_MEMBERENSURE,
                name, nullptr, &value, engine_->GetGlobalNoAddRef()))) {
            TJS_eTJSError(TJS_W("Unable to register host class"));
        }
        return object;
    }

    static void method(tTJSNativeClass* object, const tjs_char* name, Method call) {
        object->RegisterNCM(name, new BoundMethod(std::move(call)),
            object->GetClassName().c_str(), nitMethod, TJS_STATICMEMBER);
    }

    void execute(bool expression, bool storage, tTJSVariant* result,
                 tjs_int count, tTJSVariant** args) {
        if (++depth_ > 32) {
            --depth_;
            TJS_eTJSError(TJS_W("Script nesting exceeds 32"));
        }
        struct Depth { int& value; ~Depth() { --value; } } depth{depth_};
        ttstr script = *args[0];
        ttstr name = TJS_W("(Scripts)");
        tjs_int line = 0;
        iTJSDispatch2* context = nullptr;
        if (storage) {
            name = script;
            const std::string mode = count >= 2 && args[1]->Type() != tvtVoid
                ? utf8_text(*args[1]) : "";
            if (count >= 3 && args[2]->Type() != tvtVoid) context = args[2]->AsObjectNoAddRef();
            std::string bytes;
            const int read_result = read(utf8_text(name), &bytes);
            if (read_result != 0) storage_failure(read_result);
            script = game_text(bytes, mode.empty() && PrivateStorage::owns(utf8_text(name)) ? "utf-8" : mode);
        } else {
            if (count >= 2 && args[1]->Type() != tvtVoid) name = *args[1];
            if (count >= 3 && args[2]->Type() != tvtVoid) line = *args[2];
            if (count >= 4 && args[3]->Type() != tvtVoid) context = args[3]->AsObjectNoAddRef();
            // Apply the same size/NUL/Unicode rules to dynamically supplied source.
            (void)decode_tjs_source(utf8_text(script));
        }
        if (expression) engine_->EvalExpression(script, result, context, &name, line);
        else engine_->ExecScript(script, result, context, &name, line);
    }

    void register_classes() {
        for (const auto& item : {
                std::make_pair(TJS_W("ltOpaque"), 1), std::make_pair(TJS_W("ltAlpha"), 2),
                std::make_pair(TJS_W("ltTransparent"), 2), std::make_pair(TJS_W("dfAuto"), 128),
                std::make_pair(TJS_W("dfOpaque"), 1), std::make_pair(TJS_W("dfAlpha"), 0),
                std::make_pair(TJS_W("atmNormal"), 0), std::make_pair(TJS_W("atmExclusive"), 1),
                std::make_pair(TJS_W("atmAtIdle"), 2), std::make_pair(TJS_W("mbLeft"), 0)}) {
            tTJSVariant value(item.second);
            if (TJS_FAILED(engine_->GetGlobalNoAddRef()->PropSet(TJS_MEMBERENSURE,
                    item.first, nullptr, &value, engine_->GetGlobalNoAddRef())))
                TJS_eTJSError(TJS_W("Unable to register TVP constant"));
        }
        for (const auto& item : {
                std::make_pair(TJS_W("KAGParser"), create_kag_parser),
                std::make_pair(TJS_W("Font"), TVPCreateNativeClass_Font),
                std::make_pair(TJS_W("AsyncTrigger"), TVPCreateNativeClass_AsyncTrigger),
                std::make_pair(TJS_W("Window"), TVPCreateNativeClass_Window),
                std::make_pair(TJS_W("Layer"), TVPCreateNativeClass_Layer)}) {
            auto* object = item.second();
            tTJSVariant value(object, nullptr);
            object->Release();
            if (TJS_FAILED(engine_->GetGlobalNoAddRef()->PropSet(TJS_MEMBERENSURE,
                    item.first, nullptr, &value, engine_->GetGlobalNoAddRef())))
                TJS_eTJSError(TJS_W("Unable to register TVP display class"));
        }
        auto* timer = TVPCreateNativeClass_Timer();
        tTJSVariant timerValue(timer, nullptr);
        timer->Release();
        if (TJS_FAILED(engine_->GetGlobalNoAddRef()->PropSet(TJS_MEMBERENSURE,
                TJS_W("Timer"), nullptr, &timerValue, engine_->GetGlobalNoAddRef()))) {
            TJS_eTJSError(TJS_W("Unable to register TVP Timer"));
        }
        auto* scripts = add_class(TJS_W("Scripts"));
        for (const auto& item : {std::make_pair(TJS_W("exec"), 0),
                std::make_pair(TJS_W("eval"), 1), std::make_pair(TJS_W("execStorage"), 2),
                std::make_pair(TJS_W("evalStorage"), 3)}) {
            method(scripts, item.first, [this, mode = item.second](auto* result, auto count, auto** args) {
                if (count < 1) return TJS_E_BADPARAMCOUNT;
                execute((mode & 1) != 0, (mode & 2) != 0, result, count, args);
                return TJS_S_OK;
            });
        }
        auto* storages = add_class(TJS_W("Storages"));
        method(storages, TJS_W("isExistentStorage"), [this](auto* result, auto count, auto** args) {
            if (count < 1) return TJS_E_BADPARAMCOUNT;
            const std::string name = utf8_text(*args[0]);
            const bool exists = PrivateStorage::owns(name) ? private_.exists(name) : resources_.exists(name);
            if (result) *result = static_cast<tjs_int>(exists);
            return TJS_S_OK;
        });
        for (const auto& item : {std::make_pair(TJS_W("extractStorageName"), false),
                std::make_pair(TJS_W("chopStorageExt"), true)}) {
            method(storages, item.first, [extension = item.second](auto* result, auto count, auto** args) {
                if (count < 1) return TJS_E_BADPARAMCOUNT;
                std::string name = utf8_text(*args[0]);
                const auto separator = name.find_last_of("/\\>");
                if (!extension) name = name.substr(separator == std::string::npos ? 0 : separator + 1);
                else {
                    const auto dot = name.find_last_of('.');
                    if (dot != std::string::npos && (separator == std::string::npos || dot > separator))
                        name.resize(dot);
                }
                if (result) *result = tjs_text(name);
                return TJS_S_OK;
            });
        }
        method(storages, TJS_W("getPlacedPath"), [this](auto* result, auto count, auto** args) {
            if (count < 1) return TJS_E_BADPARAMCOUNT;
            if (result) *result = tjs_text(resources_.placed(utf8_text(*args[0])));
            return TJS_S_OK;
        });
        for (const auto& item : {std::make_pair(TJS_W("addAutoPath"), true),
                std::make_pair(TJS_W("removeAutoPath"), false)}) {
            method(storages, item.first, [this, add = item.second](auto*, auto count, auto** args) {
                if (count < 1) return TJS_E_BADPARAMCOUNT;
                if (add) resources_.add_path(utf8_text(*args[0]));
                else resources_.remove_path(utf8_text(*args[0]));
                return TJS_S_OK;
            });
        }
        method(storages, TJS_W("clearAutoPathCache"), [this](auto*, auto, auto**) {
            resources_.clear_cache(); return TJS_S_OK;
        });
        method(storages, TJS_W("getListAt"), [this](auto* result, auto count, auto** args) {
            if (count < 1) return TJS_E_BADPARAMCOUNT;
            const auto names = resources_.list(utf8_text(*args[0]));
            auto* array = TJSCreateArrayObject();
            tTJSVariant value(array, array); array->Release();
            for (std::size_t i = 0; i < names.size(); ++i) {
                tTJSVariant name(tjs_text(names[i]));
                if (TJS_FAILED(array->PropSetByNum(TJS_MEMBERENSURE, static_cast<tjs_int>(i), &name, array)))
                    TJS_eTJSError(TJS_W("Storage list allocation failed"));
            }
            if (result) *result = value;
            return TJS_S_OK;
        });
        method(storages, TJS_W("readText"), [this](auto* result, auto count, auto** args) {
            if (count < 1) return TJS_E_BADPARAMCOUNT;
            std::string bytes;
            const auto error = read(utf8_text(*args[0]), &bytes);
            if (error) storage_failure(error);
            const auto text = game_text(bytes, count >= 2 && args[1]->Type() != tvtVoid
                ? utf8_text(*args[1]) : PrivateStorage::owns(utf8_text(*args[0])) ? "utf-8" : "");
            if (result) *result = text;
            return TJS_S_OK;
        });
        method(storages, TJS_W("readBytes"), [this](auto* result, auto count, auto** args) {
            if (count < 1) return TJS_E_BADPARAMCOUNT;
            const auto name = utf8_text(*args[0]);
            auto source = PrivateStorage::owns(name) ? memory_source(private_.read(name)) : resources_.open(name);
            const tjs_int64 offset = count >= 2 && args[1]->Type() != tvtVoid ? static_cast<tjs_int64>(*args[1]) : 0;
            const tjs_int64 length = count >= 3 && args[2]->Type() != tvtVoid ? static_cast<tjs_int64>(*args[2])
                : offset >= 0 && static_cast<std::uint64_t>(offset) <= source->size()
                    ? static_cast<tjs_int64>(source->size() - offset) : -1;
            if (offset < 0 || length < 0 || static_cast<std::uint64_t>(offset) > source->size()
                || static_cast<std::uint64_t>(length) > source->size() - offset || length > kResourceReadLimit)
                TJS_eTJSError(TJS_W("Invalid or excessive resource read range"));
            std::string bytes(static_cast<std::size_t>(length), '\0');
            source->read(offset, bytes.data(), bytes.size());
            if (result) *result = tTJSVariant(reinterpret_cast<const tjs_uint8*>(bytes.data()), static_cast<tjs_uint>(bytes.size()));
            return TJS_S_OK;
        });
        method(storages, TJS_W("writeText"), [this](auto*, auto count, auto** args) {
            if (count < 2) return TJS_E_BADPARAMCOUNT;
            const auto bytes = utf8_text(*args[1]);
            (void)decode_tjs_source(bytes);
            private_.write(utf8_text(*args[0]), bytes);
            return TJS_S_OK;
        });
        method(storages, TJS_W("writeBytes"), [this](auto*, auto count, auto** args) {
            if (count < 2) return TJS_E_BADPARAMCOUNT;
            const auto* octet = args[1]->AsOctetNoAddRef();
            if (!octet) return TJS_E_INVALIDPARAM;
            private_.write(utf8_text(*args[0]), std::string_view(reinterpret_cast<const char*>(octet->GetData()), octet->GetLength()));
            return TJS_S_OK;
        });
        method(storages, TJS_W("createFolders"), [this](auto*, auto count, auto** args) {
            if (count < 1) return TJS_E_BADPARAMCOUNT;
            private_.create_folders(utf8_text(*args[0])); return TJS_S_OK;
        });
        old_text_read_ = TJSCreateTextStreamForRead;
        old_text_write_ = TJSCreateTextStreamForWrite;
        old_binary_read_ = TJSCreateBinaryStreamForRead;
        old_binary_write_ = TJSCreateBinaryStreamForWrite;
        g_text_session = this;
        TJSCreateTextStreamForRead = [](const ttstr& name, const ttstr& mode) -> iTJSTextReadStream* {
            if (!mode.IsEmpty()) TJS_eTJSError(TJS_W("Text stream read modes are unsupported; use explicit readText encoding"));
            std::string bytes;
            const int error = g_text_session->read(utf8_text(name), &bytes);
            if (error) g_text_session->storage_failure(error);
            return new TextReader(g_text_session->game_text(bytes, PrivateStorage::owns(utf8_text(name)) ? "utf-8" : ""));
        };
        TJSCreateTextStreamForWrite = [](const ttstr& name, const ttstr& mode) -> iTJSTextWriteStream* {
            if (!mode.IsEmpty()) TJS_eTJSError(TJS_W("Private text stream modes are unsupported"));
            const auto filename = utf8_text(name);
            if (!PrivateStorage::owns(filename)) TJS_eTJSError(TJS_W("Text writes require System.dataPath"));
            return new AtomicTextWriter([filename](std::string_view bytes) { g_text_session->private_.write(filename, bytes); });
        };
        TJSCreateBinaryStreamForRead = [](const ttstr& name, const ttstr& mode) -> tTJSBinaryStream* {
            if (!mode.IsEmpty() && mode != TJS_W("b")) TJS_eTJSError(TJS_W("Unsupported binary stream mode"));
            try {
                const auto path = utf8_text(name);
                return new BinaryReader(PrivateStorage::owns(path)
                    ? memory_source(g_text_session->private_.read(path)) : g_text_session->resources_.open(path));
            }
            catch (const StorageError&) { TJS_eTJSError(TJS_W("Unable to open game binary stream")); }
            return nullptr;
        };
        TJSCreateBinaryStreamForWrite = [](const ttstr&, const ttstr&) -> tTJSBinaryStream* {
            TJS_eTJSError(TJS_W("Binary object serialization is not admitted; use Storages.writeBytes"));
            return nullptr;
        };
        auto* system = add_class(TJS_W("System"));
        system->RegisterNCM(TJS_W("eventDisabled"), new FlagProperty(&event_disabled_, [this](bool value) {
            if (event_disabled_ && !value) reset_tvp_timer_clocks();
            event_disabled_ = value;
        }), system->GetClassName().c_str(), nitProperty, TJS_STATICMEMBER);
        method(system, TJS_W("getKeyState"), [](auto* result, auto count, auto** args) {
            if (count < 1) return TJS_E_BADPARAMCOUNT;
            if (result) *result = TVPAndroidKeyState(static_cast<tjs_int>(*args[0]));
            return TJS_S_OK;
        });
        for (const auto& item : {std::make_pair(TJS_W("addContinuousHandler"), true),
                std::make_pair(TJS_W("removeContinuousHandler"), false)}) {
            method(system, item.first, [this, add = item.second](auto*, auto count, auto** args) {
                if (count < 1 || args[0]->Type() != tvtObject) return TJS_E_BADPARAMCOUNT;
                auto closure = args[0]->AsObjectClosureNoAddRef();
                if (!closure.Object) return TJS_E_INVALIDPARAM;
                auto found = std::find_if(continuous_handlers_.begin(), continuous_handlers_.end(),
                    [&](const auto& value) { const auto other = value.AsObjectClosureNoAddRef();
                        return other.Object == closure.Object && other.ObjThis == closure.ObjThis; });
                if (add && found == continuous_handlers_.end()) {
                    if (continuous_handlers_.size() >= 32) TJS_eTJSError(TJS_W("Continuous handler limit exceeded"));
                    continuous_handlers_.push_back(*args[0]);
                } else if (!add && found != continuous_handlers_.end()) continuous_handlers_.erase(found);
                return TJS_S_OK;
            });
        }
        system->RegisterNCM(TJS_W("screenWidth"), new ScreenProperty(&width_),
            system->GetClassName().c_str(), nitProperty, TJS_STATICMEMBER);
        system->RegisterNCM(TJS_W("screenHeight"), new ScreenProperty(&height_),
            system->GetClassName().c_str(), nitProperty, TJS_STATICMEMBER);
        system->RegisterNCM(TJS_W("dataPath"), new DataPathProperty(),
            system->GetClassName().c_str(), nitProperty, TJS_STATICMEMBER);
        method(system, TJS_W("getTickCount"), [](auto* result, auto, auto**) {
            using namespace std::chrono;
            if (result != nullptr) *result = static_cast<tjs_int64>(
                duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count());
            return TJS_S_OK;
        });
        method(system, TJS_W("exit"), [this](auto*, auto count, auto** args) {
            if (count > 0 && args[0]->Type() != tvtVoid && static_cast<tjs_int>(*args[0]) != 0) {
                TJS_eTJSError(TJS_W("Only normal System.exit(0) is supported"));
            }
            status.store(kNormalExit);
            return TJS_S_OK;
        });
        method(system, TJS_W("_audioOpen"), [this](auto* result, auto count, auto** args) {
            if(count!=1) return TJS_E_BADPARAMCOUNT;
            std::string bytes; const int error=read(utf8_text(*args[0]),&bytes);
            if(error) storage_failure(error);
            const auto id = TVPOpenAndroidWave(bytes);
            if (result) *result=id; return TJS_S_OK;
        });
        method(system, TJS_W("_audioControl"), [](auto* result, auto count, auto** args) {
            if(count!=3) return TJS_E_BADPARAMCOUNT;
            const auto value = TVPControlAndroidAudio(static_cast<tjs_int>(*args[0]),static_cast<tjs_int>(*args[1]),static_cast<tjs_int>(*args[2]));
            if (result) *result=value;
            return TJS_S_OK;
        });
        auto* debug = add_class(TJS_W("Debug"));
        method(debug, TJS_W("message"), [this](auto*, auto count, auto** args) {
            for (tjs_int index = 0; index < count; ++index) {
                const ttstr text = *args[index];
                console_.Print(text.c_str());
            }
            return TJS_S_OK;
        });
        host_ = add_class(TJS_W("TwinQuillHost"));
        host_->AddRef();
        method(host_, TJS_W("setColor"), [this](auto*, auto count, auto** args) {
            if (count != 3) return TJS_E_BADPARAMCOUNT;
            std::int64_t packed = 0;
            for (int index = 0; index < 3; ++index) {
                const tjs_real component = *args[index];
                if (!(component >= 0 && component <= 255)) return TJS_E_INVALIDPARAM;
                packed = (packed << 8) | static_cast<tjs_int>(component);
            }
            color.store(packed);
            return TJS_S_OK;
        });
        method(host_, TJS_W("getSurfaceWidth"), [this](auto* result, auto, auto**) {
            if (result != nullptr) *result = width_;
            return TJS_S_OK;
        });
        method(host_, TJS_W("getSurfaceHeight"), [this](auto* result, auto, auto**) {
            if (result != nullptr) *result = height_;
            return TJS_S_OK;
        });
    }

    ResourceStore resources_;
    PrivateStorage private_;
    std::string encoding_;
    decltype(TJSCreateTextStreamForRead) old_text_read_ = nullptr;
    decltype(TJSCreateTextStreamForWrite) old_text_write_ = nullptr;
    decltype(TJSCreateBinaryStreamForRead) old_binary_read_ = nullptr;
    decltype(TJSCreateBinaryStreamForWrite) old_binary_write_ = nullptr;
    Console console_;
    tTJS* engine_ = nullptr;
    tTJSNativeClass* host_ = nullptr;
    ttstr startup_name_{TJS_W("startup.tjs")};
    ttstr script_;
    std::vector<tTJSVariant> continuous_handlers_;
    bool event_disabled_ = false;
    bool kag_host_ = false;
    bool activated_ = false;
    bool paused_ = false;
    int width_ = 0, height_ = 0;
    int depth_ = 0;
};

// TJS2 owns process-global caches. One live session is admitted per :krkr process.
std::unique_ptr<Session> g_session;
std::uint64_t g_session_handle = 0;

Session* find_session(std::uint64_t handle) {
    std::lock_guard<std::mutex> lock(g_session_registry_mutex);
    return handle != 0 && handle == g_session_handle ? g_session.get() : nullptr;
}
}  // namespace

bool tjs_session_active() {
    std::lock_guard<std::mutex> lock(g_session_registry_mutex);
    return g_session != nullptr;
}

std::int64_t start_tjs_session(int source_kind, const std::string& source, bool deferred, const std::string& save) {
    if (source_kind < 1 || source_kind > 3 || source.empty()
        || source.find('\0') != std::string::npos) return -10;
    std::lock_guard<std::mutex> engine_lock(tjs_engine_mutex());
    if (tjs_session_active()) return -10;
    try {
        auto session = std::make_unique<Session>(source_kind, source, save);
        int result = session->prepare();
        if (result == 0 && !deferred) result = session->activate(1, 1);
        if (result != 0) return -result;
        std::lock_guard<std::mutex> lock(g_session_registry_mutex);
        if (g_next_session > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) return -21;
        g_session_handle = g_next_session++;
        g_session = std::move(session);
        return static_cast<std::int64_t>(g_session_handle);
    } catch (const StorageError& error) {
        __android_log_print(ANDROID_LOG_ERROR, "TwinQuill/Krkr", "%s", error.what());
        return -error.status;
    } catch (const eTJS& error) {
        Console output;
        output.ExceptionPrint(error.GetMessage().c_str());
        return -20;
    } catch (const std::invalid_argument&) { return -20; }
    catch (...) { return -21; }
}

int activate_tjs_session(std::uint64_t handle, int width, int height) {
    std::lock_guard<std::mutex> engine_lock(tjs_engine_mutex());
    const auto session = find_session(handle);
    return session == nullptr ? 11 : session->activate(width, height);
}

void cancel_tjs_session(std::uint64_t handle) {
    // Cancellation must never wait for the VM mutex held by an infinite script.
    std::lock_guard<std::mutex> lock(g_session_registry_mutex);
    if (handle != 0 && handle == g_session_handle && g_session != nullptr) {
        g_session->cancelled.store(true);
    }
}

int dispatch_tjs_event(std::uint64_t handle, int event, const std::vector<double>& args) {
    std::lock_guard<std::mutex> engine_lock(tjs_engine_mutex());
    const auto session = find_session(handle);
    return session == nullptr ? 11 : session->event(event, args);
}

int poll_tjs_session(std::uint64_t handle, std::int64_t* color) {
    std::lock_guard<std::mutex> lock(g_session_registry_mutex);
    const auto* session = handle != 0 && handle == g_session_handle ? g_session.get() : nullptr;
    if (session == nullptr) return 11;
    if (color != nullptr) *color = session->color.load();
    return session->status.load();
}

void close_tjs_session(std::uint64_t handle) {
    std::lock_guard<std::mutex> engine_lock(tjs_engine_mutex());
    std::unique_ptr<Session> retired;
    {
        std::lock_guard<std::mutex> lock(g_session_registry_mutex);
        if (handle != g_session_handle) return;
        retired = std::move(g_session);
        g_session_handle = 0;
    }
}

void tjs_session_stats(std::uint64_t handle, std::int64_t* output) {
    std::lock_guard<std::mutex> lock(g_session_registry_mutex);
    const auto* session = handle != 0 && handle == g_session_handle ? g_session.get() : nullptr;
    output[0] = g_session != nullptr ? 1 : 0;
    output[1] = g_startups.load();
    output[2] = g_releases.load();
    output[3] = session == nullptr ? 0 : session->events.load();
    output[4] = session == nullptr ? 11 : session->status.load();
}
}  // namespace twinquill::krkr

iTJSTextReadStream* TVPCreateTextStreamForRead(const ttstr& name, const ttstr& mode) {
    return TJSCreateTextStreamForRead(name, mode);
}
void TVPExecuteExpression(const ttstr& expression, iTJSDispatch2* context, tTJSVariant* result) {
    twinquill::krkr::g_text_session->evaluate_kag(expression, context, result);
}
void TVPAddLog(const ttstr& message) {
    const auto bytes = twinquill::krkr::utf8_text(message);
    __android_log_print(ANDROID_LOG_INFO, "TwinQuill/Krkr", "%s", bytes.c_str());
}

namespace { std::vector<tTVPCompactEventCallbackIntf*> kag_compact_hooks; }
void TVPAddCompactEventHook(tTVPCompactEventCallbackIntf* hook) {
    if (std::find(kag_compact_hooks.begin(), kag_compact_hooks.end(), hook) == kag_compact_hooks.end())
        kag_compact_hooks.push_back(hook);
}

void TVPDeliverCompactEvent(tjs_int level) {
    for (auto* hook : kag_compact_hooks) hook->OnCompact(level);
}
void TVPRemoveCompactEventHook(tTVPCompactEventCallbackIntf* hook) {
    kag_compact_hooks.erase(std::remove(kag_compact_hooks.begin(), kag_compact_hooks.end(), hook), kag_compact_hooks.end());
}

/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_tjs_session.h"
#include "krkr_tjs_entry.h"
#include "krkr_tjs_text.h"
#include "krkr_vfs_storage.h"
#include "krkr_xp3.h"
#include "tjs.h"
#include "tjsError.h"
#include "tjsNative.h"

#include <android/log.h>
#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <functional>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>

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
        catch (const std::invalid_argument&) {
            TJS_eTJSError(TJS_W("Invalid Unicode or source text"));
        }
        return TJS_E_FAIL;
    }
private:
    Method method_;
};

std::string relative_name(const ttstr& name) {
    std::string text = utf8_text(name);
    if (text.rfind("./", 0) == 0) text.erase(0, 2);
    if (text.empty() || text.front() == '/' || text.back() == '/'
        || text.find_first_of(":\\") != std::string::npos || text.find('\0') != std::string::npos) {
        TJS_eTJSError(TJS_W("Storage name must be relative to the game root"));
    }
    std::size_t start = 0;
    while (start < text.size()) {
        const std::size_t end = text.find('/', start);
        const std::string part = text.substr(start, end - start);
        if (part.empty() || part == "." || part == "..") {
            TJS_eTJSError(TJS_W("Storage traversal is not permitted"));
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return text;
}

class Session final {
public:
    Session(int kind, std::string source) : kind_(kind), source_(std::move(source)) {}
    // Every engine operation and destruction is performed under tjs_engine_mutex().
    ~Session() noexcept {
        if (host_ != nullptr) host_->Release();
        if (engine_ != nullptr) {
            try { engine_->Shutdown(); } catch (...) { }
            engine_->Release();
            ++g_releases;
        }
    }

    int start() {
        std::string source;
        const int result = read("startup.tjs", &source, true);
        if (result != 0) return result;
        // Decode before constructing the engine so malformed source owns no VM resources.
        const ttstr script = tjs_text(source);
        engine_ = new tTJS();
        engine_->SetConsoleOutput(&console_);
        return guarded([&] {
            register_classes();
            engine_->ExecScript(script, nullptr, nullptr, &startup_name_);
        });
    }

    int event(int event, const std::vector<double>& args) {
        static const tjs_char* names[] = {
            TJS_W("onTouch"), TJS_W("onKey"), TJS_W("onPause"),
            TJS_W("onResume"), TJS_W("onLowMemory"), TJS_W("onSurfaceChanged")
        };
        if (event < 0 || event >= 6 || args.size() > 6) return 10;
        if (status.load() != 0) return status.load();
        return guarded([&] {
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

    std::atomic<int> status{0};
    std::atomic<std::int64_t> color{-1}, events{0};
    const int kind_;
    const std::string source_;

private:
    template<class Call> int guarded(Call call) {
        try { call(); }
        catch (const eTJS& error) {
            console_.ExceptionPrint(error.GetMessage().c_str());
            if (status.load() == 0 || status.load() == kNormalExit) status.store(20);
        } catch (const std::invalid_argument& error) {
            __android_log_print(ANDROID_LOG_ERROR, "TwinQuill/Krkr", "%s", error.what());
            status.store(20);
        } catch (...) { status.store(21); }
        // The exception and its script-block references are gone before VM shutdown.
        return status.load() == kNormalExit ? 0 : status.load();
    }

    std::filesystem::path local_path(const std::string& name) const {
        const auto root = std::filesystem::canonical(std::filesystem::path(source_).parent_path());
        const auto path = std::filesystem::weakly_canonical(root / name);
        auto left = root.begin(), right = path.begin();
        for (; left != root.end(); ++left, ++right) {
            if (right == path.end() || *left != *right) {
                TJS_eTJSError(TJS_W("Storage path escapes game root"));
            }
        }
        return path;
    }

    int read(const std::string& name, std::string* output, bool startup = false) {
        if (kind_ == 1) return read_tqsaf_script(source_, name, output, startup);
        if (kind_ == 3 && name == "startup.tjs") return read_raw_xp3_startup(source_.c_str(), output);
        const auto path = local_path(name);
        std::ifstream input(path, std::ios::binary | std::ios::ate);
        if (!input) return 11;
        const auto length = input.tellg();
        if (length < 0) return 41;
        if (startup && length == 0) return 11;
        if (length > static_cast<std::streamoff>(kStartupSourceLimit)) return 20;
        output->assign(static_cast<std::size_t>(length), '\0');
        input.seekg(0);
        if (length != 0 && !input.read(output->data(), length)) return 41;
        return 0;
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
            if (count >= 2 && args[1]->Type() != tvtVoid && ((ttstr)*args[1]).GetLen() != 0) {
                TJS_eTJSError(TJS_W("Explicit storage encoding modes are not supported"));
            }
            if (count >= 3 && args[2]->Type() != tvtVoid) context = args[2]->AsObjectNoAddRef();
            std::string bytes;
            const int read_result = read(relative_name(name), &bytes);
            if (read_result != 0) storage_failure(read_result);
            script = tjs_text(bytes);
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
            const std::string name = relative_name(*args[0]);
            bool exists = false;
            if (kind_ == 1) {
                const int status = exists_tqsaf_script(source_, name, &exists);
                if (status != 0) storage_failure(status);
            } else if (kind_ == 3 && name == "startup.tjs") exists = true;
            else exists = std::filesystem::is_regular_file(local_path(name));
            if (result != nullptr) *result = static_cast<tjs_int>(exists);
            return TJS_S_OK;
        });
        auto* system = add_class(TJS_W("System"));
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
    }

    Console console_;
    tTJS* engine_ = nullptr;
    tTJSNativeClass* host_ = nullptr;
    ttstr startup_name_{TJS_W("startup.tjs")};
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

std::int64_t start_tjs_session(int source_kind, const std::string& source) {
    if (source_kind < 1 || source_kind > 3 || source.empty()
        || source.find('\0') != std::string::npos) return -10;
    std::lock_guard<std::mutex> engine_lock(tjs_engine_mutex());
    if (tjs_session_active()) return -10;
    try {
        auto session = std::make_unique<Session>(source_kind, source);
        const int result = session->start();
        if (result != 0) return -result;
        std::lock_guard<std::mutex> lock(g_session_registry_mutex);
        if (g_next_session > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max())) return -21;
        g_session_handle = g_next_session++;
        g_session = std::move(session);
        ++g_startups;
        return static_cast<std::int64_t>(g_session_handle);
    } catch (const eTJS& error) {
        Console output;
        output.ExceptionPrint(error.GetMessage().c_str());
        return -20;
    } catch (const std::invalid_argument&) { return -20; }
    catch (...) { return -21; }
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

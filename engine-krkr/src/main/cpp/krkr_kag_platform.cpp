/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_kag_platform.h"

#include "krkr_kag_runtime.h"

#include "KAGParser.h"
#include "StorageIntf.h"
#include "TextStream.h"
#include "tjsError.h"

#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>
#include <memory>
#include <string>
#include <vector>

void TVPExecuteExpression(const ttstr& content, iTJSDispatch2* context, tTJSVariant* result);

namespace {

constexpr char kLogTag[] = "TwinQuill/Krkr";
constexpr std::uint64_t kMaxKagTextBytes = 1024 * 1024;
constexpr tjs_int kMaxFormattedMessageChars = 4096;
const tjs_char kDefaultReadEncoding[] = TJS_W("UTF-8");

struct KagRuntimeState {
    TJS::tTJS* engine = nullptr;
    TJS::iTJSDispatch2* context = nullptr;
    bool executing_expression = false;
};

thread_local KagRuntimeState g_runtime_state;

class ScopedExpressionExecution final {
public:
    ScopedExpressionExecution() {
        if (g_runtime_state.engine == nullptr || g_runtime_state.context == nullptr) {
            throw TJS::eTJSError(TJS_W("KAG expression execution has no active TJS engine"));
        }
        if (g_runtime_state.executing_expression) {
            throw TJS::eTJSError(TJS_W("Nested KAG expression execution is unsupported"));
        }
        g_runtime_state.executing_expression = true;
    }

    ~ScopedExpressionExecution() noexcept {
        g_runtime_state.executing_expression = false;
    }

    ScopedExpressionExecution(const ScopedExpressionExecution&) = delete;
    ScopedExpressionExecution& operator=(const ScopedExpressionExecution&) = delete;
};

bool fits_size_t(std::uint64_t value) {
    return value <= static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max());
}

bool read_stream_to_bytes(tTJSBinaryStream* stream, std::vector<std::uint8_t>* output) {
    if (stream == nullptr || output == nullptr) {
        return false;
    }
    const std::uint64_t size = stream->GetSize();
    if (size > kMaxKagTextBytes || !fits_size_t(size)) {
        return false;
    }
    output->assign(static_cast<std::size_t>(size), 0);
    std::uint8_t* cursor = output->empty() ? nullptr : output->data();
    std::size_t remaining = output->size();
    while (remaining > 0) {
        const std::size_t request = std::min(
            remaining,
            static_cast<std::size_t>(std::numeric_limits<TJS::tjs_uint>::max()));
        const TJS::tjs_uint count = stream->Read(cursor, static_cast<TJS::tjs_uint>(request));
        if (count == 0 || static_cast<std::size_t>(count) > request) {
            output->clear();
            return false;
        }
        cursor += count;
        remaining -= static_cast<std::size_t>(count);
    }
    return true;
}

void append_utf16_code_point(std::uint32_t code_point, std::u16string* output) {
    if (code_point <= 0xffffU) {
        output->push_back(static_cast<char16_t>(code_point));
        return;
    }
    code_point -= 0x10000U;
    output->push_back(static_cast<char16_t>(0xd800U + (code_point >> 10U)));
    output->push_back(static_cast<char16_t>(0xdc00U + (code_point & 0x3ffU)));
}

bool decode_utf8_bytes(const std::vector<std::uint8_t>& bytes, std::u16string* output) {
    if (output == nullptr) {
        return false;
    }
    output->clear();
    std::size_t index = 0;
    if (bytes.size() >= 3 && bytes[0] == 0xef && bytes[1] == 0xbb && bytes[2] == 0xbf) {
        index = 3;
    }
    while (index < bytes.size()) {
        const std::uint8_t first = bytes[index];
        std::uint32_t code_point = 0;
        std::size_t length = 0;
        if (first <= 0x7fU) {
            code_point = first;
            length = 1;
        } else if (first >= 0xc2U && first <= 0xdfU) {
            code_point = first & 0x1fU;
            length = 2;
        } else if (first >= 0xe0U && first <= 0xefU) {
            code_point = first & 0x0fU;
            length = 3;
        } else if (first >= 0xf0U && first <= 0xf4U) {
            code_point = first & 0x07U;
            length = 4;
        } else {
            return false;
        }
        if (length > bytes.size() - index) {
            return false;
        }
        for (std::size_t offset = 1; offset < length; ++offset) {
            const std::uint8_t next = bytes[index + offset];
            if ((next & 0xc0U) != 0x80U) {
                return false;
            }
            code_point = (code_point << 6U) | (next & 0x3fU);
        }
        if (code_point == 0 ||
            (length == 3 && code_point < 0x800U) ||
            (length == 4 && code_point < 0x10000U) ||
            (code_point >= 0xd800U && code_point <= 0xdfffU) ||
            code_point > 0x10ffffU) {
            return false;
        }
        append_utf16_code_point(code_point, output);
        index += length;
    }
    return true;
}

bool decode_utf16le_bytes(const std::vector<std::uint8_t>& bytes, std::u16string* output) {
    if (output == nullptr || bytes.size() < 2 || bytes[0] != 0xff || bytes[1] != 0xfe ||
        ((bytes.size() - 2U) % 2U) != 0) {
        return false;
    }
    output->clear();
    for (std::size_t index = 2; index < bytes.size(); index += 2) {
        const std::uint16_t code_unit = static_cast<std::uint16_t>(bytes[index]) |
            (static_cast<std::uint16_t>(bytes[index + 1]) << 8U);
        if (code_unit == 0) {
            return false;
        }
        if (code_unit >= 0xd800U && code_unit <= 0xdbffU) {
            if (index + 3 >= bytes.size()) {
                return false;
            }
            const std::uint16_t low = static_cast<std::uint16_t>(bytes[index + 2]) |
                (static_cast<std::uint16_t>(bytes[index + 3]) << 8U);
            if (low < 0xdc00U || low > 0xdfffU) {
                return false;
            }
            output->push_back(static_cast<char16_t>(code_unit));
            output->push_back(static_cast<char16_t>(low));
            index += 2;
            continue;
        }
        if (code_unit >= 0xdc00U && code_unit <= 0xdfffU) {
            return false;
        }
        output->push_back(static_cast<char16_t>(code_unit));
    }
    return true;
}

bool decode_text_bytes(const std::vector<std::uint8_t>& bytes, std::u16string* output) {
    if (bytes.size() >= 2 && bytes[0] == 0xff && bytes[1] == 0xfe) {
        return decode_utf16le_bytes(bytes, output);
    }
    return decode_utf8_bytes(bytes, output);
}

std::string utf8_from_tjs(const ttstr& text) {
    std::string output;
    const tjs_char* cursor = text.c_str();
    while (cursor != nullptr && *cursor != 0) {
        std::uint32_t code_point = static_cast<std::uint32_t>(*cursor++);
        if (code_point >= 0xd800U && code_point <= 0xdbffU && cursor != nullptr) {
            const std::uint32_t low = static_cast<std::uint32_t>(*cursor);
            if (low >= 0xdc00U && low <= 0xdfffU) {
                ++cursor;
                code_point = 0x10000U + (((code_point - 0xd800U) << 10U) | (low - 0xdc00U));
            }
        }
        if (code_point <= 0x7fU) {
            output.push_back(static_cast<char>(code_point));
        } else if (code_point <= 0x7ffU) {
            output.push_back(static_cast<char>(0xc0U | (code_point >> 6U)));
            output.push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
        } else if (code_point <= 0xffffU) {
            output.push_back(static_cast<char>(0xe0U | (code_point >> 12U)));
            output.push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3fU)));
            output.push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
        } else {
            output.push_back(static_cast<char>(0xf0U | (code_point >> 18U)));
            output.push_back(static_cast<char>(0x80U | ((code_point >> 12U) & 0x3fU)));
            output.push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3fU)));
            output.push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
        }
    }
    return output;
}

void append_bounded(ttstr* output, const ttstr& value) {
    if (output == nullptr || output->GetLen() >= kMaxFormattedMessageChars) {
        return;
    }
    const tjs_int remaining = kMaxFormattedMessageChars - output->GetLen();
    if (value.GetLen() <= remaining) {
        *output += value;
    } else {
        *output += ttstr(value.c_str(), remaining);
    }
}

ttstr format_message(const tjs_char* message, const ttstr& parameter1, const ttstr& parameter2) {
    ttstr output;
    if (message == nullptr) {
        return output;
    }
    for (const tjs_char* cursor = message; *cursor != 0 && output.GetLen() < kMaxFormattedMessageChars; ++cursor) {
        if (*cursor == TJS_W('%') && cursor[1] == TJS_W('1')) {
            append_bounded(&output, parameter1);
            ++cursor;
        } else if (*cursor == TJS_W('%') && cursor[1] == TJS_W('2')) {
            append_bounded(&output, parameter2);
            ++cursor;
        } else {
            output += *cursor;
        }
    }
    return output;
}

class BoundedTextReadStream final : public TJS::iTJSTextReadStream {
public:
    explicit BoundedTextReadStream(std::u16string content) : content_(std::move(content)) {}

    tjs_uint TJS_INTF_METHOD Read(tTJSString& target, tjs_uint size) override {
        const std::size_t remaining = content_.size() - position_;
        std::size_t requested = size == 0 ? remaining : std::min<std::size_t>(remaining, size);
        if (requested > static_cast<std::size_t>(std::numeric_limits<tjs_uint>::max())) {
            requested = static_cast<std::size_t>(std::numeric_limits<tjs_uint>::max());
        }
        if (requested == 0) {
            target.Clear();
            return 0;
        }
        tjs_char* buffer = target.AllocBuffer(static_cast<tjs_uint>(requested));
        std::memcpy(buffer, content_.data() + position_, requested * sizeof(tjs_char));
        buffer[requested] = 0;
        target.FixLen();
        position_ += requested;
        return static_cast<tjs_uint>(requested);
    }

    void TJS_INTF_METHOD Destruct() override { delete this; }

private:
    std::u16string content_;
    std::size_t position_ = 0;
};

}  // namespace

const tjs_char* TVPInternalError = TJS_W("Internal error occurred: at %1 line %2");

twinquill::krkr::KagRuntimeScope::KagRuntimeScope(TJS::tTJS* engine, TJS::iTJSDispatch2* context) {
    if (engine == nullptr || context == nullptr) {
        throw TJS::eTJSError(TJS_W("KAG runtime scope requires an active TJS engine"));
    }
    if (g_runtime_state.engine != nullptr || g_runtime_state.context != nullptr) {
        throw TJS::eTJSError(TJS_W("Nested KAG runtime scopes are unsupported"));
    }
    const auto register_class = [context](
        const tjs_char* name,
        iTJSDispatch2* (*factory)()) {
        iTJSDispatch2* class_dispatch = factory();
        if (class_dispatch == nullptr) {
            throw TJS::eTJSError(TJS_W("Unable to create KAG runtime native class"));
        }
        try {
            TJS::tTJSVariant class_value(class_dispatch, nullptr);
            class_dispatch->Release();
            class_dispatch = nullptr;
            const tjs_error result = context->PropSet(
                TJS_MEMBERENSURE | TJS_IGNOREPROP,
                name,
                nullptr,
                &class_value,
                context);
            if (TJS_FAILED(result)) {
                TJS::TJSThrowFrom_tjs_error(result);
            }
        } catch (...) {
            if (class_dispatch != nullptr) {
                class_dispatch->Release();
            }
            throw;
        }
    };
    register_class(TJS_W("KAGParser"), TVPCreateNativeClass_KAGParser);
    register_class(
        TJS_W("TwinQuillKagRuntime"),
        TVPCreateNativeClass_TwinQuillKagRuntime);
    g_runtime_state.engine = engine;
    g_runtime_state.context = context;
    active_ = true;
}

twinquill::krkr::KagRuntimeScope::~KagRuntimeScope() noexcept {
    if (active_) { g_runtime_state = KagRuntimeState{}; }
}

TJS::tTJS* twinquill::krkr::KagRuntimeScope::current_engine() { return g_runtime_state.engine; }

TJS::iTJSDispatch2* twinquill::krkr::KagRuntimeScope::current_context() { return g_runtime_state.context; }

TJS::iTJSTextReadStream* TVPCreateTextStreamForRead(const ttstr& name, const ttstr&) {
    std::unique_ptr<tTJSBinaryStream> stream(TVPCreateStream(name, TJS_BS_READ));
    if (stream == nullptr) { return nullptr; }
    std::vector<std::uint8_t> bytes;
    if (!read_stream_to_bytes(stream.get(), &bytes)) {
        throw TJS::eTJSError(TJS_W("KAG text stream read failed"));
    }
    std::u16string decoded;
    if (!decode_text_bytes(bytes, &decoded)) {
        throw TJS::eTJSError(TJS_W("KAG text stream encoding is unsupported or malformed"));
    }
    return new BoundedTextReadStream(std::move(decoded));
}

TJS::iTJSTextWriteStream* TVPCreateTextStreamForWrite(const ttstr&, const ttstr&) {
    throw TJS::eTJSError(TJS_W("KAG parser text writes are unsupported"));
}

void TVPSetDefaultReadEncoding(const ttstr& encoding) {
    if (!encoding.IsEmpty() && encoding != TJS_W("UTF-8") && encoding != TJS_W("utf-8")) {
        throw TJS::eTJSError(TJS_W("Only UTF-8 default read encoding is supported"));
    }
}

const tjs_char* TVPGetDefaultReadEncoding() { return kDefaultReadEncoding; }

void TVPAddLog(const ttstr& line) {
    const std::string message = utf8_from_tjs(line);
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "%s", message.c_str());
}

void TVPAddImportantLog(const ttstr& line) {
    const std::string message = utf8_from_tjs(line);
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "%s", message.c_str());
}

ttstr TVPFormatMessage(const tjs_char* message, const ttstr& parameter) {
    return format_message(message, parameter, ttstr());
}

ttstr TVPFormatMessage(const tjs_char* message, const ttstr& parameter1, const ttstr& parameter2) {
    return format_message(message, parameter1, parameter2);
}

void TVPThrowExceptionMessage(const tjs_char* message) {
    throw TJS::eTJSError(message == nullptr ? TJS_W("") : message);
}

void TVPThrowExceptionMessage(const tjs_char* message, const ttstr& parameter) {
    throw TJS::eTJSError(TVPFormatMessage(message, parameter));
}

void TVPThrowExceptionMessage(const tjs_char* message, const ttstr& parameter1, const ttstr& parameter2) {
    throw TJS::eTJSError(TVPFormatMessage(message, parameter1, parameter2));
}

void TVPThrowExceptionMessage(const tjs_char* message, const ttstr& parameter1, tjs_int parameter2) {
    throw TJS::eTJSError(TVPFormatMessage(message, parameter1, ttstr(parameter2)));
}

ttstr TVPExtractStorageName(const ttstr& name) {
    const tjs_char* text = name.c_str();
    if (text == nullptr || *text == 0) { return ttstr(); }
    const tjs_char* base = text;
    for (const tjs_char* cursor = text; *cursor != 0; ++cursor) {
        if (*cursor == TJS_W('/') || *cursor == TJS_W('\\') || *cursor == TVPArchiveDelimiter) {
            base = cursor + 1;
        }
    }
    return ttstr(base);
}

void TVPExecuteExpression(const ttstr& content, tTJSVariant* result) {
    TVPExecuteExpression(content, g_runtime_state.context, result);
}

void TVPExecuteExpression(const ttstr& content, iTJSDispatch2* context, tTJSVariant* result) {
    ScopedExpressionExecution scope;
    iTJSDispatch2* effective_context = context != nullptr ? context : g_runtime_state.context;
    if (effective_context == nullptr) {
        throw TJS::eTJSError(TJS_W("KAG expression execution has no context"));
    }
    g_runtime_state.engine->EvalExpression(content, result, effective_context);
}

void TVPExecuteExpression(const ttstr& content, const ttstr&, tjs_int, tTJSVariant* result) {
    TVPExecuteExpression(content, result);
}

void TVPExecuteExpression(const ttstr& content, const ttstr&, tjs_int, iTJSDispatch2* context, tTJSVariant* result) {
    TVPExecuteExpression(content, context, result);
}

void TVPAddCompactEventHook(tTVPCompactEventCallbackIntf*) {
    // M3 B4 has no Android compact-event pump yet; KAGParser also clears the
    // scenario cache explicitly around each isolated startup parse.
}

void TVPRemoveCompactEventHook(tTVPCompactEventCallbackIntf*) {}
/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_vfs_storage.h"

#include <algorithm>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

#include "tjs.h"
#include "tjsError.h"

namespace {

constexpr std::uint64_t kProbeSourceLimit = 16U * 1024U * 1024U;

class TqVfsException final : public std::runtime_error {
public:
    explicit TqVfsException(int status)
        : std::runtime_error("tqsaf VFS operation failed"), status_(status) {}
    int status() const { return status_; }
private:
    int status_;
};

void append_utf8(std::string& out, std::uint32_t cp) {
    if (cp <= 0x7f) {
        out.push_back(static_cast<char>(cp));
    } else if (cp <= 0x7ff) {
        out.push_back(static_cast<char>(0xc0 | (cp >> 6)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else if (cp <= 0xffff) {
        out.push_back(static_cast<char>(0xe0 | (cp >> 12)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else if (cp <= 0x10ffff) {
        out.push_back(static_cast<char>(0xf0 | (cp >> 18)));
        out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (cp & 0x3f)));
    } else {
        throw std::invalid_argument("invalid Unicode code point");
    }
}

std::string strict_utf8(const ttstr& value) {
    std::string out;
    const tjs_char* chars = value.c_str();
    const tjs_int length = value.GetLen();
    for (tjs_int index = 0; index < length; ++index) {
        std::uint32_t cp = static_cast<std::uint32_t>(chars[index]);
        if (cp == 0) {
            throw std::invalid_argument("embedded NUL");
        }
        if (cp >= 0xd800 && cp <= 0xdbff) {
            if (index + 1 >= length) {
                throw std::invalid_argument("unpaired UTF-16 surrogate");
            }
            const std::uint32_t low = static_cast<std::uint32_t>(chars[++index]);
            if (low < 0xdc00 || low > 0xdfff) {
                throw std::invalid_argument("unpaired UTF-16 surrogate");
            }
            cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00);
        } else if (cp >= 0xdc00 && cp <= 0xdfff) {
            throw std::invalid_argument("unpaired UTF-16 surrogate");
        }
        append_utf8(out, cp);
    }
    return out;
}

std::uint32_t decode_utf8(const unsigned char*& input) {
    const unsigned char first = *input++;
    if (first < 0x80) {
        if (first == 0) throw std::invalid_argument("embedded NUL");
        return first;
    }
    int count = 0;
    std::uint32_t cp = 0;
    if (first >= 0xc2 && first <= 0xdf) { count = 1; cp = first & 0x1f; }
    else if (first >= 0xe0 && first <= 0xef) { count = 2; cp = first & 0x0f; }
    else if (first >= 0xf0 && first <= 0xf4) { count = 3; cp = first & 0x07; }
    else throw std::invalid_argument("invalid UTF-8 lead byte");
    for (int index = 0; index < count; ++index) {
        const unsigned char continuation = *input++;
        if ((continuation & 0xc0) != 0x80) throw std::invalid_argument("invalid UTF-8 continuation");
        cp = (cp << 6) | (continuation & 0x3f);
    }
    if ((count == 2 && cp < 0x800) || (count == 3 && cp < 0x10000) ||
        cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) {
        throw std::invalid_argument("non-strict UTF-8 code point");
    }
    return cp;
}

ttstr strict_ttstr(const char* utf8) {
    if (utf8 == nullptr) throw std::invalid_argument("null UTF-8 name");
    std::basic_string<tjs_char> out;
    const auto* cursor = reinterpret_cast<const unsigned char*>(utf8);
    while (*cursor != 0) {
        const std::uint32_t cp = decode_utf8(cursor);
        if (cp <= 0xffff) {
            out.push_back(static_cast<tjs_char>(cp));
        } else {
            const std::uint32_t scalar = cp - 0x10000;
            out.push_back(static_cast<tjs_char>(0xd800 | (scalar >> 10)));
            out.push_back(static_cast<tjs_char>(0xdc00 | (scalar & 0x3ff)));
        }
    }
    return ttstr(out);
}

void validate_path_only(const std::string& path) {
    if (path.empty() || path[0] != '/' || path.find('\\') != std::string::npos ||
        path.find('\0') != std::string::npos) {
        throw std::invalid_argument("invalid tqsaf storage path");
    }
    std::size_t start = 1;
    while (start < path.size()) {
        const std::size_t end = path.find('/', start);
        const std::string segment = path.substr(start, end - start);
        if (segment.empty() || segment == "." || segment == "..") {
            throw std::invalid_argument("traversal or ambiguous tqsaf path");
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
}

std::string relative_path(const ttstr& value) {
    const std::string text = strict_utf8(value);
    const std::size_t slash = text.find('/');
    if (slash == std::string::npos) {
        if (text == ".") throw std::invalid_argument("tqsaf file path is required");
        throw std::invalid_argument("tqsaf name must include dot domain");
    }
    if (text.substr(0, slash) != ".") {
        throw std::invalid_argument("tqsaf domain must be dot");
    }
    const std::string path = text.substr(slash);
    validate_path_only(path);
    if (path == "/") throw std::invalid_argument("tqsaf file path is required");
    if (path.back() == '/') {
        throw std::invalid_argument("directory path is not a file path");
    }
    return path.substr(1);
}

std::string relative_directory_path(const ttstr& value) {
    const std::string text = strict_utf8(value);
    const std::size_t slash = text.find('/');
    if (slash == std::string::npos || text.substr(0, slash) != ".") {
        throw std::invalid_argument("tqsaf name must include dot domain");
    }
    const std::string path = text.substr(slash);
    validate_path_only(path);
    if (path == "/") return {};
    const std::size_t length = path.back() == '/' ? path.size() - 1 : path.size();
    return path.substr(1, length - 1);
}
int status_from_result(std::int64_t result) {
    if (result == TQ_VFS_PERMISSION) return TQ_VFS_PERMISSION;
    if (result == TQ_VFS_NOT_FOUND) return TQ_VFS_NOT_FOUND;
    if (result == TQ_VFS_INVALID) return TQ_VFS_INVALID;
    if (result == TQ_VFS_UNSUPPORTED) return TQ_VFS_UNSUPPORTED;
    return TQ_VFS_ERROR;
}

class TqSafStream final : public tTJSBinaryStream {
public:
    TqSafStream(std::int64_t handle, std::uint64_t size)
        : handle_(handle), size_(size) {}
    ~TqSafStream() override {
        if (handle_ >= 0) {
            tq_vfs_close(handle_);
            handle_ = -1;
        }
    }
    tjs_uint64 Seek(tjs_int64 offset, tjs_int whence) override {
        if (handle_ < 0 || (whence != TJS_BS_SEEK_SET && whence != TJS_BS_SEEK_CUR &&
                            whence != TJS_BS_SEEK_END)) {
            throw TqVfsException(TQ_VFS_INVALID);
        }
        const std::int64_t position = tq_vfs_seek(handle_, offset, whence);
        if (position < 0) {
            throw TqVfsException(status_from_result(position));
        }
        return static_cast<tjs_uint64>(position);
    }
    tjs_uint Read(void* buffer, tjs_uint read_size) override {
        if (handle_ < 0 || buffer == nullptr) {
            throw TqVfsException(TQ_VFS_INVALID);
        }
        const std::int64_t result = tq_vfs_read(handle_, buffer, read_size);
        if (result < 0 || static_cast<std::uint64_t>(result) > read_size) {
            throw TqVfsException(status_from_result(result));
        }
        return static_cast<tjs_uint>(result);
    }
    tjs_uint Write(const void*, tjs_uint) override {
        throw TqVfsException(TQ_VFS_UNSUPPORTED);
    }
    void SetEndOfStorage() override {
        throw TqVfsException(TQ_VFS_UNSUPPORTED);
    }
    tjs_uint64 GetSize() override { return size_; }
private:
    std::int64_t handle_;
    std::uint64_t size_;
};
bool is_startup_ascii_casefold(const std::string& name) {
    static constexpr char kStartup[] = "startup.tjs";
    if (name.size() != sizeof(kStartup) - 1) return false;
    for (std::size_t index = 0; index < name.size(); ++index) {
        char character = name[index];
        if (character >= 'A' && character <= 'Z') character = static_cast<char>(character + ('a' - 'A'));
        if (character != kStartup[index]) return false;
    }
    return true;
}
class ListCollector final : public iTVPStorageLister {
public:
    void Add(const ttstr& file) override {
        std::string name = strict_utf8(file);
        if (is_startup_ascii_casefold(name)) {
            ++startup_matches;
            if (name == "startup.tjs") has_exact_startup = true;
        }
        names.push_back(std::move(name));
    }
    bool has_startup() const { return has_exact_startup && startup_matches == 1; }
    bool startup_ambiguous() const { return startup_matches > 1; }
    std::vector<std::string> names;
private:
    int startup_matches = 0;
    bool has_exact_startup = false;
};

}  // namespace

TqSafMedia::TqSafMedia(std::string tree_uri) : tree_uri_(std::move(tree_uri)) {
    if (tree_uri_.empty() || tree_uri_.find('\0') != std::string::npos) {
        throw std::invalid_argument("invalid SAF tree URI");
    }
}

void TqSafMedia::GetName(ttstr& name) { name = ttstr("tqsaf"); }
void TqSafMedia::NormalizeDomainName(ttstr& name) {
    if (strict_utf8(name) != ".") throw std::invalid_argument("tqsaf domain must be dot");
}
void TqSafMedia::NormalizePathName(ttstr& name) {
    validate_path_only(strict_utf8(name));
}

bool TqSafMedia::CheckExistentStorage(const ttstr& name) {
    tq_vfs_stat stat{};
    try {
        const std::string path = relative_path(name);
        last_status_ = tq_vfs_stat_path(tree_uri_.c_str(), path.c_str(), &stat);
    } catch (const std::invalid_argument&) {
        last_status_ = TQ_VFS_INVALID;
        return false;
    }
    return last_status_ == TQ_VFS_OK && stat.exists != 0 && stat.directory == 0;
}

tTJSBinaryStream* TqSafMedia::Open(const ttstr& name, tjs_uint32 flags) {
    if ((flags & TJS_BS_ACCESS_MASK) != TJS_BS_READ) {
        last_status_ = TQ_VFS_UNSUPPORTED;
        throw TqVfsException(TQ_VFS_UNSUPPORTED);
    }
    const std::string path = relative_path(name);
    const std::int64_t handle = tq_vfs_open(tree_uri_.c_str(), path.c_str(), TQ_VFS_OPEN_READ);
    if (handle < 0) {
        last_status_ = status_from_result(handle);
        throw TqVfsException(last_status_);
    }
    tq_vfs_stat stat{};
    const int stat_status = tq_vfs_stat_path(tree_uri_.c_str(), path.c_str(), &stat);
    if (stat_status != TQ_VFS_OK) {
        tq_vfs_close(handle);
        last_status_ = stat_status;
        throw TqVfsException(stat_status);
    }
    std::int64_t size = stat.size;
    if (size < 0) {
        const std::int64_t end_position = tq_vfs_seek(handle, 0, TQ_VFS_SEEK_END);
        const std::int64_t restore_position = tq_vfs_seek(handle, 0, TQ_VFS_SEEK_SET);
        if (end_position < 0 || restore_position < 0) {
            tq_vfs_close(handle);
            const std::int64_t failure = end_position < 0 ? end_position : restore_position;
            last_status_ = status_from_result(failure);
            throw TqVfsException(last_status_);
        }
        size = end_position;
    }
    try {
        TqSafStream* stream = new TqSafStream(handle, static_cast<std::uint64_t>(size));
        last_status_ = TQ_VFS_OK;
        return stream;
    } catch (...) {
        tq_vfs_close(handle);
        throw;
    }
}

void TqSafMedia::GetListAt(const ttstr& name, iTVPStorageLister* lister) {
    if (lister == nullptr) {
        last_status_ = TQ_VFS_INVALID;
        throw std::invalid_argument("tqsaf list callback is required");
    }
    const std::string path = relative_directory_path(name);
    struct Context {
        iTVPStorageLister* lister;
        int callback_status;
    } context{lister, TQ_VFS_OK};
    const int status = tq_vfs_list(
        tree_uri_.c_str(), path.c_str(),
        [](const char* utf8_name, void* user_data) -> int {
            auto* context = static_cast<Context*>(user_data);
            try {
                context->lister->Add(strict_ttstr(utf8_name));
                return TQ_VFS_OK;
            } catch (const std::invalid_argument&) {
                context->callback_status = TQ_VFS_INVALID;
                return TQ_VFS_INVALID;
            } catch (...) {
                context->callback_status = TQ_VFS_ERROR;
                return TQ_VFS_ERROR;
            }
        },
        &context);
    last_status_ = context.callback_status != TQ_VFS_OK ? context.callback_status : status;
    if (last_status_ != TQ_VFS_OK) throw TqVfsException(last_status_);
}

void TqSafMedia::GetLocallyAccessibleName(ttstr& name) { name = ttstr(""); }

extern int run_tjs_source(const std::string& source);

int run_tqsaf_startup(const char* tree_uri_utf8) {
    if (tree_uri_utf8 == nullptr || tree_uri_utf8[0] == '\0') return 10;
    try {
        TqSafMedia media(tree_uri_utf8);
        ListCollector collector;
        media.GetListAt(ttstr("./"), &collector);
        if (collector.startup_ambiguous()) return 10;
        if (!collector.has_startup()) return 11;
        if (!media.CheckExistentStorage(ttstr("./startup.tjs"))) {
            if (media.last_status() == TQ_VFS_PERMISSION) return 40;
            if (media.last_status() == TQ_VFS_INVALID) return 10;
            return media.last_status() == TQ_VFS_NOT_FOUND ? 11 : 41;
        }
        std::unique_ptr<tTJSBinaryStream> stream(media.Open(ttstr("./startup.tjs"), TJS_BS_READ));
        const tjs_uint64 size = stream->GetSize();
        if (size == 0 || size > kProbeSourceLimit || size > std::numeric_limits<std::size_t>::max()) return 11;
        if (stream->Seek(0, TJS_BS_SEEK_SET) != 0) return 41;
        std::string source(static_cast<std::size_t>(size), '\0');
        std::size_t offset = 0;
        while (offset < source.size()) {
            const tjs_uint chunk = stream->Read(
                source.data() + offset,
                static_cast<tjs_uint>(std::min<std::size_t>(source.size() - offset, 64 * 1024)));
            if (chunk == 0) return 41;
            offset += chunk;
        }
        int script_result = 0;
        try {
            script_result = run_tjs_source(source);
        } catch (const std::invalid_argument&) {
            return 20;
        }
        return script_result == 0 ? 0 : 20;
    } catch (const std::invalid_argument&) {
        return 10;
    } catch (const TJS::eTJS&) {
        return 20;
    } catch (const TqVfsException& exception) {
        if (exception.status() == TQ_VFS_PERMISSION) return 40;
        if (exception.status() == TQ_VFS_NOT_FOUND) return 11;
        if (exception.status() == TQ_VFS_INVALID) return 10;
        return 41;
    } catch (const std::exception&) {
        return 21;
    } catch (...) {
        return 22;
    }
}

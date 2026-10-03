/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_resource.h"
#include "krkr_tjs_text.h"
#include <algorithm>
#include <fstream>

namespace twinquill::krkr {
namespace {
class LocalSource final : public ByteSource {
public:
    explicit LocalSource(const std::string& path) : input_(path, std::ios::binary | std::ios::ate) {
        if (!input_) throw StorageError(11, "Game file not found");
        const auto end = input_.tellg();
        if (end < 0 || static_cast<std::uint64_t>(end) > kArchiveLimit)
            throw StorageError(20, "File exceeds 4 GiB stream limit");
        size_ = static_cast<std::uint64_t>(end);
    }
    std::uint64_t size() const override { return size_; }
    void read(std::uint64_t offset, void* output, std::size_t count) override {
        if (offset > size_ || count > size_ - offset) throw StorageError(41, "Read outside file");
        input_.clear();
        input_.seekg(static_cast<std::streamoff>(offset));
        if (count && !input_.read(static_cast<char*>(output), static_cast<std::streamsize>(count)))
            throw StorageError(41, "Truncated game file");
    }
private:
    std::ifstream input_;
    std::uint64_t size_ = 0;
};
std::pair<std::string, std::string> split(const std::string& path) {
    const auto delimiter = path.find('>');
    return delimiter == std::string::npos ? std::make_pair(path, std::string())
        : std::make_pair(path.substr(0, delimiter), path.substr(delimiter + 1));
}
}
std::shared_ptr<ByteSource> open_local_source(const std::string& path) {
    return std::make_shared<LocalSource>(path);
}
std::shared_ptr<ByteSource> memory_source(std::string bytes) {
    class MemorySource final : public ByteSource {
    public:
        explicit MemorySource(std::string bytes) : bytes_(std::move(bytes)) {}
        std::uint64_t size() const override { return bytes_.size(); }
        void read(std::uint64_t offset, void* output, std::size_t count) override {
            if (offset > bytes_.size() || count > bytes_.size() - offset)
                throw StorageError(41, "Read outside memory stream");
            if (count) std::copy_n(bytes_.data() + offset, count, static_cast<char*>(output));
        }
    private:
        std::string bytes_;
    };
    return std::make_shared<MemorySource>(std::move(bytes));
}
std::string read_source(ByteSource& source, std::size_t limit) {
    if (source.size() > limit) throw StorageError(20, "Resource exceeds whole-read budget");
    std::string output(static_cast<std::size_t>(source.size()), '\0');
    for (std::size_t offset = 0; offset < output.size();) {
        const auto take = std::min<std::size_t>(65536, output.size() - offset);
        source.read(offset, output.data() + offset, take);
        offset += take;
    }
    return output;
}
std::string storage_path(std::string name, bool folder) {
    if (name.size() > 4096 || name.find('\0') != std::string::npos)
        throw StorageError(10, "Invalid storage name");
    try { (void)decode_tjs_source(name); }
    catch (const std::invalid_argument&) { throw StorageError(10, "Invalid storage name Unicode"); }
    std::replace(name.begin(), name.end(), '\\', '/');
    if (name.rfind("./", 0) == 0) name.erase(0, 2);
    if (name.find(':') != std::string::npos || (!name.empty() && name.front() == '/'))
        throw StorageError(10, "Storage must be relative to game root");
    const auto delimiter = name.find('>');
    if (delimiter != std::string::npos) {
        if (name.find('>', delimiter + 1) != std::string::npos)
            throw StorageError(10, "Nested archives are unsupported");
        return storage_path(name.substr(0, delimiter)) + '>'
            + archive_path(name.substr(delimiter + 1), folder);
    }
    std::string normalized;
    for (std::size_t start = 0; start < name.size();) {
        const auto slash = name.find('/', start);
        const auto part = name.substr(start, slash - start);
        if (part == ".." || part == ".") throw StorageError(10, "Storage traversal is forbidden");
        if (!part.empty()) {
            if (!normalized.empty()) normalized += '/';
            normalized += part;
        }
        if (slash == std::string::npos) break;
        start = slash + 1;
    }
    if (!folder && (normalized.empty() || (!name.empty() && name.back() == '/')))
        throw StorageError(10, "A storage file name is required");
    if (folder && !normalized.empty()) normalized += '/';
    return normalized;
}
std::string archive_path(std::string name, bool folder) {
    name = storage_path(std::move(name), folder);
    if (name.find('>') != std::string::npos) throw StorageError(10, "Invalid archive member path");
    for (char& c : name) if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
    return name;
}

ResourceStore::ResourceStore(std::unique_ptr<ResourceBackend> backend) : backend_(std::move(backend)) {
    auto names = backend_->list("");
    std::sort(names.begin(), names.end(), [](const auto& left, const auto& right) {
        const auto a = archive_path(left), b = archive_path(right);
        return a == b ? left < right : a < b;
    });
    int startup_count = 0;
    for (const auto& name : names) {
        // Lists may include directory names with a trailing slash.
        if (name.empty() || name.back() == '/') continue;
        const auto folded = archive_path(name);
        if (folded == "startup.tjs") ++startup_count;
        if (folded.size() >= 4 && folded.substr(folded.size() - 4) == ".xp3") {
            if (paths_.size() >= 32) throw StorageError(20, "More than 32 root XP3 archives");
            paths_.push_back(name + '>');
        }
    }
    if (startup_count > 1) throw StorageError(10, "Ambiguous root startup.tjs");
}
std::shared_ptr<Xp3Archive> ResourceStore::archive(const std::string& name) {
    const auto stat = backend_->stat(name); // Always refresh permission and metadata.
    if (!stat.exists || stat.directory) throw StorageError(11, "Archive not found");
    auto it = archives_.find(name);
    if (it != archives_.end() && stat.size >= 0 && stat.modified >= 0
        && it->second.stat.size == stat.size && it->second.stat.modified == stat.modified) {
        it->second.touched = ++clock_;
        return it->second.archive;
    }
    // Providers with unknown size/time are deliberately not trusted for caching.
    auto parsed = std::make_shared<Xp3Archive>(backend_->open(name));
    if (it == archives_.end() && archives_.size() >= 4) {
        const auto oldest = std::min_element(archives_.begin(), archives_.end(),
            [](const auto& a, const auto& b) { return a.second.touched < b.second.touched; });
        archives_.erase(oldest);
    }
    archives_[name] = {stat, parsed, ++clock_};
    return parsed;
}
bool ResourceStore::direct_exists(const std::string& name) {
    const auto [file, member] = split(name);
    const auto stat = backend_->stat(file);
    if (!stat.exists || stat.directory) return false;
    if (name.find('>') != std::string::npos) return archive(file)->contains(member);
    return stat.exists && !stat.directory;
}
std::string ResourceStore::placed(const std::string& name) {
    // An explicit ./ names the game root. KAG's executable-root archive probes
    // must not fan out across every directory registered for short filenames.
    const bool root_qualified = name.rfind("./", 0) == 0 || name.rfind(".\\", 0) == 0;
    const auto normalized = storage_path(name);
    backend_->begin_lookup();
    struct LookupScope {
        ResourceBackend* backend;
        ~LookupScope() { backend->end_lookup(); }
    } lookup{backend_.get()};
    // Refresh each archive once per lookup. Weak references preserve the four
    // index cache limit while avoiding repeated SAF metadata queries for every
    // candidate directory in the same atomic resolution operation.
    std::map<std::string, std::weak_ptr<Xp3Archive>> refreshed;
    const auto exists = [&](const std::string& candidate) {
        if (candidate.find('>') == std::string::npos) return direct_exists(candidate);
        const auto [file, member] = split(candidate);
        auto index = refreshed[file].lock();
        if (!index) {
            const auto info = backend_->stat(file);
            if (!info.exists || info.directory) return false;
            index = archive(file);
            refreshed[file] = index;
        }
        return index->contains(member);
    };
    if (exists(normalized)) return normalized;
    if (normalized.find('>') != std::string::npos) return {};
    const auto slash = normalized.find_last_of('/');
    const auto basename = normalized.substr(slash == std::string::npos ? 0 : slash + 1);
    // Later-added paths win, as in upstream TVPAutoPathTable.Add.
    for (auto it = paths_.rbegin(); it != paths_.rend(); ++it) {
        if (root_qualified && it->back() != '>') continue;
        const auto candidate = storage_path(*it + (it->back() == '>' ? archive_path(normalized) : basename));
        if (exists(candidate)) return candidate;
        // KAG registers ordinary directories even when the complete game lives
        // in root XP3 archives. Resolve that directory within each mounted root
        // without trusting stale permissions or changing loose-file precedence.
        if (it->find('>') == std::string::npos) {
            for (auto root = paths_.rbegin(); root != paths_.rend(); ++root) {
                if (root->back() != '>') continue;
                const auto member = *root + archive_path(candidate);
                if (exists(member)) return member;
            }
        }
        // Explicitly registered archive subdirectories use basename semantics.
        if (it->back() != '>' && it->find('>') != std::string::npos) {
            const auto by_name = storage_path(*it + archive_path(basename));
            if (exists(by_name)) return by_name;
        }
    }
    return {};
}
bool ResourceStore::exists(const std::string& name) { return !placed(name).empty(); }
std::shared_ptr<ByteSource> ResourceStore::open(const std::string& name) {
    const auto resolved = placed(name);
    if (resolved.empty()) throw StorageError(11, "Game storage not found");
    const auto [file, member] = split(resolved);
    return resolved.find('>') == std::string::npos ? backend_->open(file)
        : archive(file)->open(member, backend_->open(file));
}
std::vector<std::string> ResourceStore::list(const std::string& directory) {
    const auto normalized = storage_path(directory, true);
    const auto [file, member] = split(normalized);
    if (normalized.find('>') != std::string::npos) return archive(file)->list(member);
    std::map<std::string, std::string> names;
    bool found = false;
    const auto append = [&](const std::vector<std::string>& children) {
        for (const auto& child : children) {
            names.emplace(archive_path(child, !child.empty() && child.back() == '/'), child);
            if (names.size() > 4096) throw StorageError(20, "Merged directory exceeds 4096 entries");
        }
    };
    const auto info = backend_->stat(file);
    if (info.exists) {
        if (!info.directory) throw StorageError(10, "Storage directory is a file");
        found = true; append(backend_->list(file));
    }
    for (auto root = paths_.rbegin(); root != paths_.rend(); ++root) {
        if (root->back() != '>') continue;
        const auto children = archive(root->substr(0, root->size() - 1))->list(normalized);
        if (!children.empty()) { found = true; append(children); }
    }
    if (!found) throw StorageError(11, "Game directory not found");
    std::vector<std::string> children;
    for (const auto& [key, value] : names) children.push_back(value);
    return children;
}
void ResourceStore::add_path(const std::string& directory) {
    const auto normalized = storage_path(directory, true);
    if (normalized.empty()) throw StorageError(10, "Root is already searched directly");
    if (std::find(paths_.begin(), paths_.end(), normalized) != paths_.end()) return;
    if (paths_.size() >= 128) throw StorageError(20, "More than 128 search paths");
    // Registering a physical directory needs its current type and permission,
    // not every child's metadata. Enumerate only for virtual XP3 directories;
    // GetListAt still enforces the directory-entry limit when actually called.
    if (normalized.find('>') == std::string::npos) {
        const auto info = backend_->stat(normalized.substr(0, normalized.size() - 1));
        if (info.exists && !info.directory) throw StorageError(10, "Storage directory is a file");
        if (!info.exists) (void)list(normalized);
    } else {
        (void)list(normalized);
    }
    paths_.push_back(normalized);
    clear_cache();
}
void ResourceStore::remove_path(const std::string& directory) {
    const auto normalized = storage_path(directory, true);
    paths_.erase(std::remove(paths_.begin(), paths_.end(), normalized), paths_.end());
    clear_cache();
}
void ResourceStore::clear_cache() { archives_.clear(); }
std::string ResourceStore::configuration() {
    const std::string name = "twinquill-krkr.conf";
    if (!backend_->stat(name).exists) return {};
    return read_source(*backend_->open(name), 4096);
}
}  // namespace twinquill::krkr

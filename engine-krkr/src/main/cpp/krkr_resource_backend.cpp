/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_resource.h"
#include "twinquill_vfs.h"
#include <algorithm>
#include <filesystem>

namespace twinquill::krkr {
namespace {
void vfs_check(std::int64_t status) {
    if (status >= 0) return;
    if (status == TQ_VFS_UNSUPPORTED)
        throw StorageError(20, "SAF stream or directory exceeds Krkr storage limits");
    throw StorageError(status == TQ_VFS_PERMISSION ? 40 : status == TQ_VFS_NOT_FOUND ? 11
        : status == TQ_VFS_INVALID ? 10 : status == TQ_VFS_UNSUPPORTED ? 20 : 41, "SAF storage operation failed");
}
class SafSource final : public ByteSource {
public:
    SafSource(std::string tree, std::string path) : tree_(std::move(tree)), path_(std::move(path)) {
        handle_ = tq_vfs_open(tree_.c_str(), path_.c_str(), TQ_VFS_OPEN_READ_BOUNDED);
        vfs_check(handle_);
        const auto end = tq_vfs_seek(handle_, 0, TQ_VFS_SEEK_END);
        if (end < 0 || static_cast<std::uint64_t>(end) > kArchiveLimit) {
            tq_vfs_close(handle_); handle_ = -1;
            throw StorageError(20, "SAF stream exceeds 4 GiB or cannot seek");
        }
        size_ = static_cast<std::uint64_t>(end);
    }
    ~SafSource() override { if (handle_ >= 0) tq_vfs_close(handle_); }
    std::uint64_t size() const override { return size_; }
    void read(std::uint64_t offset, void* output, std::size_t count) override {
        tq_vfs_stat stat{};
        vfs_check(tq_vfs_stat_path(tree_.c_str(), path_.c_str(), &stat));
        if (!stat.exists || stat.directory) throw StorageError(11, "SAF file disappeared");
        if (stat.size >= 0 && static_cast<std::uint64_t>(stat.size) != size_)
            throw StorageError(41, "SAF file changed during read");
        if (offset > size_ || count > size_ - offset) throw StorageError(41, "Read outside SAF stream");
        vfs_check(tq_vfs_seek(handle_, static_cast<std::int64_t>(offset), TQ_VFS_SEEK_SET));
        auto* bytes = static_cast<char*>(output);
        while (count) {
            const auto read = tq_vfs_read(handle_, bytes, std::min<std::size_t>(count, 65536));
            vfs_check(read);
            if (read == 0 || static_cast<std::uint64_t>(read) > count)
                throw StorageError(41, "Truncated SAF stream");
            bytes += read; count -= static_cast<std::size_t>(read);
        }
    }
private:
    std::string tree_, path_;
    std::int64_t handle_ = -1;
    std::uint64_t size_ = 0;
};
class SafBackend final : public ResourceBackend {
public:
    explicit SafBackend(std::string tree) : tree_(std::move(tree)) {}
    void begin_lookup() override { vfs_check(tq_vfs_begin_lookup()); }
    void end_lookup() noexcept override { tq_vfs_end_lookup(); }
    StorageStat stat(const std::string& path) override {
        tq_vfs_stat info{};
        const int result = tq_vfs_stat_path(tree_.c_str(), path.c_str(), &info);
        if (result == TQ_VFS_NOT_FOUND) return {};
        vfs_check(result);
        return {info.exists != 0, info.directory != 0, info.size, info.modified_millis};
    }
    std::vector<std::string> list(const std::string& directory) override {
        struct Context { std::vector<std::string> names; int status = 0; } context;
        const auto status = tq_vfs_list_bounded(tree_.c_str(), directory.c_str(), [](const char* name, void* data) -> int {
            auto* c = static_cast<Context*>(data);
            try {
                if (c->names.size() >= 4096) { c->status = 20; return TQ_VFS_ERROR; }
                c->names.push_back(storage_path(name));
                return TQ_VFS_OK;
            } catch (...) { c->status = 10; return TQ_VFS_INVALID; }
        }, &context);
        if (context.status) throw StorageError(context.status, "Invalid or excessive SAF directory entries");
        vfs_check(status);
        // Native VFS returns child names; TVP GetListAt lists regular files.
        std::vector<std::string> files;
        for (const auto& name : context.names) {
            const auto info = stat(directory + name);
            if (info.exists && !info.directory) files.push_back(name);
        }
        return files;
    }
    std::shared_ptr<ByteSource> open(const std::string& path) override {
        return std::make_shared<SafSource>(tree_, storage_path(path));
    }
private:
    std::string tree_;
};
class LocalBackend final : public ResourceBackend {
public:
    explicit LocalBackend(const std::string& source)
        : root_(std::filesystem::canonical(std::filesystem::path(source).parent_path())) {}
    StorageStat stat(const std::string& name) override {
        const auto path = checked(name);
        if (!std::filesystem::exists(path)) return {};
        const bool directory = std::filesystem::is_directory(path);
        return {true, directory, directory ? -1 : static_cast<std::int64_t>(std::filesystem::file_size(path)),
            static_cast<std::int64_t>(std::filesystem::last_write_time(path).time_since_epoch().count())};
    }
    std::vector<std::string> list(const std::string& directory) override {
        std::vector<std::string> result;
        const auto path = checked(directory);
        if (!std::filesystem::is_directory(path)) throw StorageError(11, "Directory not found");
        std::size_t entries = 0;
        for (const auto& item : std::filesystem::directory_iterator(path)) {
            if (++entries > 4096) throw StorageError(20, "Excessive directory entries");
            if (item.is_regular_file()) result.push_back(item.path().filename().string());
        }
        return result;
    }
    std::shared_ptr<ByteSource> open(const std::string& path) override {
        return open_local_source(checked(path).string());
    }
private:
    std::filesystem::path checked(const std::string& name) const {
        const auto path = std::filesystem::weakly_canonical(root_ / name);
        auto left = root_.begin(), right = path.begin();
        for (; left != root_.end(); ++left, ++right)
            if (right == path.end() || *left != *right) throw StorageError(10, "Game path escapes root");
        return path;
    }
    std::filesystem::path root_;
};
}
std::unique_ptr<ResourceBackend> game_backend(int kind, const std::string& source) {
    if (kind == 1) return std::make_unique<SafBackend>(source);
    if (kind == 2 || kind == 3) return std::make_unique<LocalBackend>(source);
    throw StorageError(10, "Unknown game storage kind");
}
}  // namespace twinquill::krkr

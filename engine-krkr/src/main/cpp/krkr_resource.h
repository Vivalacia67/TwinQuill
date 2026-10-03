/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace twinquill::krkr {
constexpr std::uint64_t kArchiveLimit = 4ULL * 1024 * 1024 * 1024;
constexpr std::size_t kResourceReadLimit = 32U * 1024 * 1024;
constexpr std::size_t kIndexLimit = 16U * 1024 * 1024;

struct StorageError : std::runtime_error {
    StorageError(int status, const char* message) : std::runtime_error(message), status(status) {}
    int status;
};

// Seekable, read-only media. Reads are exact and bounded by size(); implementations
// recheck access before using a retained descriptor or an archive index cache.
class ByteSource {
public:
    virtual ~ByteSource() = default;
    virtual std::uint64_t size() const = 0;
    virtual void read(std::uint64_t offset, void* output, std::size_t count) = 0;
};
std::shared_ptr<ByteSource> open_local_source(const std::string& path);
std::shared_ptr<ByteSource> memory_source(std::string bytes);
std::string read_source(ByteSource& source, std::size_t limit = kResourceReadLimit);
std::string storage_path(std::string name, bool folder = false);
std::string archive_path(std::string name, bool folder = false);

class Xp3Archive {
public:
    explicit Xp3Archive(std::shared_ptr<ByteSource> source);
    bool contains(const std::string& name) const;
    std::vector<std::string> list(const std::string& directory) const;
    std::shared_ptr<ByteSource> open(const std::string& name,
                                    std::shared_ptr<ByteSource> source) const;
private:
    struct Segment { std::uint32_t flags; std::uint64_t offset, size, packed; };
    struct Entry {
        std::uint32_t flags = 0;
        std::uint64_t size = 0, packed = 0;
        std::vector<Segment> segments;
    };
    class MemberSource;
    std::map<std::string, Entry> entries_;
};

struct StorageStat {
    bool exists = false, directory = false;
    std::int64_t size = -1, modified = -1;
};
class ResourceBackend {
public:
    virtual ~ResourceBackend() = default;
    virtual StorageStat stat(const std::string& path) = 0;
    virtual std::vector<std::string> list(const std::string& directory) = 0;
    virtual std::shared_ptr<ByteSource> open(const std::string& path) = 0;
    virtual void begin_lookup() {}
    virtual void end_lookup() noexcept {}
};
std::unique_ptr<ResourceBackend> game_backend(int source_kind, const std::string& source);

class ResourceStore {
public:
    explicit ResourceStore(std::unique_ptr<ResourceBackend> backend);
    std::shared_ptr<ByteSource> open(const std::string& name);
    std::string placed(const std::string& name);
    bool exists(const std::string& name);
    std::vector<std::string> list(const std::string& directory);
    void add_path(const std::string& directory);
    void remove_path(const std::string& directory);
    void clear_cache();
    std::string configuration();
private:
    struct CachedArchive {
        StorageStat stat;
        std::shared_ptr<Xp3Archive> archive;
        std::uint64_t touched;
    };
    std::shared_ptr<Xp3Archive> archive(const std::string& name);
    bool direct_exists(const std::string& name);
    std::unique_ptr<ResourceBackend> backend_;
    std::vector<std::string> paths_;
    std::map<std::string, CachedArchive> archives_;
    std::uint64_t clock_ = 0;
};
}  // namespace twinquill::krkr

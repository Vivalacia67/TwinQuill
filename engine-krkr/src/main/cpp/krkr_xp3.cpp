/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 * XP3 wire format and ASCII archive normalization follow the pinned
 * Kirikiroid2 XP3Archive.cpp / StorageIntf.cpp; see KRKR_M3_STORAGE_CONTRACT.md.
 */
#include "krkr_xp3.h"
#include "krkr_resource.h"
#include "krkr_tjs_text.h"
#include <algorithm>
#include <array>
#include <cstring>
#include <set>
#include <zlib.h>

namespace twinquill::krkr {
namespace {
constexpr unsigned char kMark[] = {0x58,0x50,0x33,0x0d,0x0a,0x20,0x0a,0x1a,0x8b,0x67,0x01};
std::uint16_t u16(const unsigned char* p) { return p[0] | (p[1] << 8); }
std::uint32_t u32(const unsigned char* p) {
    std::uint32_t n = 0;
    for (int i = 0; i < 4; ++i) n |= std::uint32_t(p[i]) << (8*i);
    return n;
}
std::uint64_t u64(const unsigned char* p) {
    std::uint64_t n = 0;
    for (int i = 0; i < 8; ++i) n |= std::uint64_t(p[i]) << (8*i);
    return n;
}
void require(bool valid, const char* message) {
    if (!valid) throw StorageError(34, message);
}
void range(ByteSource& input, std::uint64_t offset, std::uint64_t size) {
    require(offset <= input.size() && size <= input.size() - offset, "XP3 extent outside archive");
}
std::string inflate_exact(std::string_view packed, std::size_t size) {
    std::string output(size + 1, '\0');
    z_stream stream{};
    if (inflateInit(&stream) != Z_OK) throw StorageError(34, "Unable to initialize XP3 inflater");
    struct End { z_stream& stream; ~End() { inflateEnd(&stream); } } end{stream};
    stream.next_in = reinterpret_cast<Bytef*>(const_cast<char*>(packed.data()));
    stream.avail_in = static_cast<uInt>(packed.size());
    stream.next_out = reinterpret_cast<Bytef*>(output.data());
    stream.avail_out = static_cast<uInt>(output.size());
    const int result = inflate(&stream, Z_FINISH);
    require(result == Z_STREAM_END && stream.total_out == size
        && stream.total_in == packed.size(), "XP3 zlib length or checksum mismatch");
    output.resize(size);
    return output;
}
struct Chunk { const unsigned char* body; std::size_t size; std::string tag; };
Chunk chunk(const std::string& bytes, std::size_t& cursor, std::size_t end) {
    require(cursor <= end && end - cursor >= 12, "Truncated XP3 chunk header");
    const auto* p = reinterpret_cast<const unsigned char*>(bytes.data()) + cursor;
    const auto size = u64(p + 4);
    require(size <= end - cursor - 12, "Truncated XP3 chunk body");
    cursor += 12 + static_cast<std::size_t>(size);
    return {p + 12, static_cast<std::size_t>(size), std::string(reinterpret_cast<const char*>(p), 4)};
}
}  // namespace

Xp3Archive::Xp3Archive(std::shared_ptr<ByteSource> input) {
    require(input != nullptr && input->size() >= 19 && input->size() <= kArchiveLimit,
            "XP3 archive size outside supported range");
    unsigned char header[19];
    input->read(0, header, sizeof(header));
    if (std::memcmp(header, kMark, sizeof(kMark)) != 0) throw StorageError(32, "Invalid XP3 signature");
    std::uint64_t offset = u64(header + 11);
    std::set<std::uint64_t> visited;
    std::size_t index_total = 0, segment_total = 0;
    for (;;) {
        require(visited.size() < 32 && visited.insert(offset).second && offset >= 19,
                "Cyclic or excessive XP3 index chain");
        unsigned char block[17]{};
        range(*input, offset, 9);
        input->read(offset, block, 9);
        const unsigned int flags = block[0];
        if ((flags & 7) > 1) throw StorageError(33, "Unsupported XP3 compression");
        require((flags & ~0x81U) == 0, "Unsupported XP3 index flags");
        const std::uint64_t packed = u64(block + 1);
        std::uint64_t size = packed;
        std::uint64_t data_offset = offset + 9;
        if (flags & 1) {
            range(*input, offset, 17);
            input->read(offset + 9, block + 9, 8);
            size = u64(block + 9);
            data_offset += 8;
        }
        require(packed <= kIndexLimit && size <= kIndexLimit - index_total,
                "XP3 index exceeds 16 MiB budget");
        index_total += static_cast<std::size_t>(size);
        range(*input, data_offset, packed);
        std::string bytes(static_cast<std::size_t>(packed), '\0');
        if (packed) input->read(data_offset, bytes.data(), bytes.size());
        if (flags & 1) bytes = inflate_exact(bytes, static_cast<std::size_t>(size));
        std::size_t cursor = 0;
        while (cursor < bytes.size()) {
            const Chunk file = chunk(bytes, cursor, bytes.size());
            if (file.tag != "File") continue;
            require(entries_.size() < 65536, "Too many XP3 members");
            Entry entry;
            std::string name;
            bool info_seen = false, segments_seen = false, adler_seen = false;
            std::size_t sub = cursor - file.size;
            const auto end = cursor;
            while (sub < end) {
                const Chunk part = chunk(bytes, sub, end);
                const auto* p = part.body;
                if (part.tag == "info") {
                    require(!info_seen && part.size >= 22, "Invalid XP3 info chunk");
                    info_seen = true;
                    entry.flags = u32(p);
                    entry.size = u64(p + 4);
                    entry.packed = u64(p + 12);
                    const auto length = u16(p + 20);
                    require(length > 0 && length <= 2048 && 22U + 2U*length <= part.size,
                            "Invalid XP3 member name extent");
                    std::string utf16("\xff\xfe", 2);
                    utf16.append(reinterpret_cast<const char*>(p + 22), 2U*length);
                    try { name = archive_path(encode_tjs_utf8(decode_tjs_source(utf16))); }
                    catch (const std::invalid_argument&) { throw StorageError(34, "Invalid XP3 member Unicode"); }
                    catch (const StorageError&) { throw StorageError(34, "Unsafe XP3 member path"); }
                    require(entry.size <= 512ULL*1024*1024, "XP3 member exceeds 512 MiB");
                } else if (part.tag == "segm") {
                    require(!segments_seen && part.size % 28 == 0 && part.size/28 <= 4096,
                            "Invalid XP3 segment table");
                    segments_seen = true;
                    segment_total += part.size/28;
                    require(segment_total <= 262144, "Too many XP3 segments");
                    for (std::size_t i = 0; i < part.size; i += 28) {
                        Segment segment{u32(p+i), u64(p+i+4), u64(p+i+12), u64(p+i+20)};
                        if (segment.flags > 1) throw StorageError(33, "Unsupported XP3 segment flags");
                        range(*input, segment.offset, segment.packed);
                        require(segment.flags != 0 || segment.size == segment.packed,
                                "Raw XP3 segment length mismatch");
                        require(segment.flags == 0 || (segment.size <= kResourceReadLimit
                            && segment.packed <= kResourceReadLimit), "Compressed XP3 segment exceeds 32 MiB");
                        entry.segments.push_back(segment);
                    }
                } else if (part.tag == "adlr") {
                    require(!adler_seen && part.size == 4, "Invalid XP3 adlr chunk");
                    adler_seen = true;
                }
            }
            require(info_seen && segments_seen && adler_seen, "XP3 member lacks info, segm or adlr");
            std::uint64_t original = 0, packed_total = 0;
            for (const auto& segment : entry.segments) {
                require(segment.size <= entry.size - original
                    && segment.packed <= entry.packed - packed_total, "XP3 segment totals overflow");
                original += segment.size;
                packed_total += segment.packed;
            }
            require(original == entry.size && packed_total == entry.packed, "XP3 segment totals mismatch");
            require(entries_.emplace(name, std::move(entry)).second, "Ambiguous XP3 member name");
        }
        if (!(flags & 0x80)) break;
        range(*input, data_offset + packed, 8);
        input->read(data_offset + packed, block, 8);
        offset = u64(block);
    }
}

class Xp3Archive::MemberSource final : public ByteSource {
public:
    MemberSource(Entry entry, std::shared_ptr<ByteSource> source)
        : entry_(std::move(entry)), source_(std::move(source)) {}
    std::uint64_t size() const override { return entry_.size; }
    void read(std::uint64_t offset, void* output, std::size_t count) override {
        range(*this, offset, count);
        auto* destination = static_cast<char*>(output);
        std::uint64_t base = 0;
        for (std::size_t i = 0; count && i < entry_.segments.size(); ++i) {
            const auto& segment = entry_.segments[i];
            if (offset >= base + segment.size) { base += segment.size; continue; }
            const auto position = offset - base;
            const auto take = static_cast<std::size_t>(std::min<std::uint64_t>(count, segment.size - position));
            if (!segment.flags) source_->read(segment.offset + position, destination, take);
            else {
                // Also touch the underlying medium on a cache hit, so revocation
                // cannot be concealed by an already decompressed segment.
                unsigned char check;
                source_->read(segment.offset, &check, segment.packed ? 1 : 0);
                if (cached_index_ != i) {
                    std::string packed(static_cast<std::size_t>(segment.packed), '\0');
                    if (!packed.empty()) source_->read(segment.offset, packed.data(), packed.size());
                    cache_ = inflate_exact(packed, static_cast<std::size_t>(segment.size));
                    cached_index_ = i;
                }
                std::memcpy(destination, cache_.data() + position, take);
            }
            count -= take; destination += take; offset += take; base += segment.size;
        }
        require(count == 0, "XP3 read did not cover requested extent");
    }
private:
    Entry entry_;
    std::shared_ptr<ByteSource> source_;
    std::size_t cached_index_ = static_cast<std::size_t>(-1);
    std::string cache_;
};

bool Xp3Archive::contains(const std::string& name) const { return entries_.count(archive_path(name)) != 0; }
std::vector<std::string> Xp3Archive::list(const std::string& directory) const {
    const std::string prefix = archive_path(directory, true);
    std::set<std::string> children;
    for (const auto& [name, entry] : entries_) {
        if (name.compare(0, prefix.size(), prefix) != 0) continue;
        const std::string rest = name.substr(prefix.size());
        const auto slash = rest.find('/');
        children.insert(slash == std::string::npos ? rest : rest.substr(0, slash + 1));
    }
    return {children.begin(), children.end()};
}
std::shared_ptr<ByteSource> Xp3Archive::open(const std::string& name, std::shared_ptr<ByteSource> source) const {
    const auto found = entries_.find(archive_path(name));
    if (found == entries_.end()) throw StorageError(31, "XP3 member not found");
    if (found->second.flags & 0x80000000U) throw StorageError(35, "Protected XP3 member is unsupported");
    return std::make_shared<MemberSource>(found->second, std::move(source));
}

int read_raw_xp3_startup(const char* path, std::string* output) {
    if (!path || !output) return 10;
    try {
        auto source = open_local_source(path);
        Xp3Archive archive(source);
        auto member = archive.open("startup.tjs", source);
        *output = read_source(*member, kStartupSourceLimit);
        return 0;
    } catch (const StorageError& error) { return error.status; }
    catch (...) { return 34; }
}
}  // namespace twinquill::krkr

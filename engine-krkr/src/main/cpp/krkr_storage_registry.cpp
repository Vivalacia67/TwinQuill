/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_storage_registry.h"

#include <algorithm>
#include <cerrno>
#include <cctype>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <sys/types.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace twinquill::krkr {
namespace {

constexpr char kTwinQuillStoragePrefix[] = "twinquill://";
constexpr char kTwinQuillStorageDomain[] = ".";
constexpr char kTwinQuillStorageDomainPrefix[] = "./";

void close_ignoring_result(int descriptor) {
    if (descriptor >= 0) {
        static_cast<void>(close(descriptor));
    }
}

bool is_ascii_alpha(unsigned char character) {
    return (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z');
}

bool valid_utf8_continuation(unsigned char character) {
    return (character & 0xc0U) == 0x80U;
}

bool validate_utf8(const std::string& text) {
    std::size_t index = 0;
    while (index < text.size()) {
        const unsigned char first = static_cast<unsigned char>(text[index]);
        if (first == 0) {
            return false;
        }
        if (first <= 0x7fU) {
            ++index;
            continue;
        }

        std::uint32_t code_point = 0;
        std::size_t length = 0;
        if (first >= 0xc2U && first <= 0xdfU) {
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

        if (length > text.size() - index) {
            return false;
        }
        for (std::size_t offset = 1; offset < length; ++offset) {
            const unsigned char next = static_cast<unsigned char>(text[index + offset]);
            if (!valid_utf8_continuation(next)) {
                return false;
            }
            code_point = (code_point << 6U) | (next & 0x3fU);
        }
        if ((length == 3 && code_point < 0x800U) ||
            (length == 4 && code_point < 0x10000U) ||
            (code_point >= 0xd800U && code_point <= 0xdfffU) ||
            code_point > 0x10ffffU) {
            return false;
        }
        index += length;
    }
    return true;
}

char ascii_lower(char character) {
    const unsigned char value = static_cast<unsigned char>(character);
    return value <= 0x7fU ? static_cast<char>(std::tolower(value)) : character;
}

bool ascii_case_equal(char left, char right) {
    return ascii_lower(left) == ascii_lower(right);
}

bool has_prefix(const std::string& text, const char* prefix) {
    const std::size_t prefix_size = std::char_traits<char>::length(prefix);
    return text.size() >= prefix_size && text.compare(0, prefix_size, prefix) == 0;
}

bool has_ascii_case_prefix(const std::string& text, const char* prefix) {
    const std::size_t prefix_size = std::char_traits<char>::length(prefix);
    if (text.size() < prefix_size) {
        return false;
    }
    for (std::size_t index = 0; index < prefix_size; ++index) {
        if (!ascii_case_equal(text[index], prefix[index])) {
            return false;
        }
    }
    return true;
}

std::string slash_normalized(std::string text) {
    std::replace(text.begin(), text.end(), '\\', '/');
    return text;
}

std::string ascii_lowered(std::string text) {
    std::transform(text.begin(), text.end(), text.begin(), [](unsigned char character) {
        return character <= 0x7fU ? static_cast<char>(std::tolower(character)) :
            static_cast<char>(character);
    });
    return text;
}

bool storage_spec_owns_descriptor(const StorageSpec& spec) {
    return spec.backend == StorageBackend::kOwnedFileDescriptor && spec.descriptor >= 0;
}

void close_if_owned(StorageSpec* spec) {
    if (spec != nullptr && storage_spec_owns_descriptor(*spec)) {
        close_ignoring_result(spec->descriptor);
        spec->descriptor = -1;
    }
}

class ScopedStorageSpec final {
public:
    explicit ScopedStorageSpec(StorageSpec spec) : spec_(std::move(spec)) {
    }

    ~ScopedStorageSpec() {
        close_if_owned(&spec_);
    }

    ScopedStorageSpec(const ScopedStorageSpec&) = delete;
    ScopedStorageSpec& operator=(const ScopedStorageSpec&) = delete;

    StorageSpec& get() {
        return spec_;
    }

    StorageSpec release() {
        StorageSpec released = std::move(spec_);
        spec_.descriptor = -1;
        return released;
    }

private:
    StorageSpec spec_;
};

int duplicate_descriptor(int descriptor) {
    if (descriptor < 0) {
        return -1;
    }
#ifdef F_DUPFD_CLOEXEC
    int cloexec_duplicate = fcntl(descriptor, F_DUPFD_CLOEXEC, 0);
    if (cloexec_duplicate >= 0 || errno != EINVAL) {
        return cloexec_duplicate;
    }
#endif
    int duplicate = fcntl(descriptor, F_DUPFD, 0);
    if (duplicate < 0) {
        return -1;
    }
    const int flags = fcntl(duplicate, F_GETFD);
    if (flags < 0 || fcntl(duplicate, F_SETFD, flags | FD_CLOEXEC) < 0) {
        close_ignoring_result(duplicate);
        return -1;
    }
    return duplicate;
}

StorageSpec storage_spec_for_open(const StorageSpec& spec) {
    if (spec.backend == StorageBackend::kLocalFile) {
        return StorageSpec::LocalFile(spec.path);
    }
    return StorageSpec::OwnedFileDescriptor(duplicate_descriptor(spec.descriptor));
}

StorageSpec archive_open_spec_from(StorageSpec* spec) {
    if (spec == nullptr) {
        return StorageSpec::OwnedFileDescriptor(-1);
    }
    if (spec->backend == StorageBackend::kLocalFile) {
        return StorageSpec::LocalFile(spec->path);
    }
    const int duplicate = duplicate_descriptor(spec->descriptor);
    close_if_owned(spec);
    return StorageSpec::OwnedFileDescriptor(duplicate);
}

std::string utf8_from_ttstr(const ttstr& text) {
    std::string output;
    const tjs_char* cursor = text.c_str();
    while (cursor != nullptr && *cursor != 0) {
        std::uint32_t code_point = static_cast<std::uint32_t>(*cursor++);
        if (code_point >= 0xd800U && code_point <= 0xdbffU &&
            cursor != nullptr && *cursor >= 0xdc00 && *cursor <= 0xdfff) {
            const std::uint32_t low = static_cast<std::uint32_t>(*cursor++);
            code_point = 0x10000U + ((code_point - 0xd800U) << 10U) + (low - 0xdc00U);
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
        } else if (code_point <= 0x10ffffU) {
            output.push_back(static_cast<char>(0xf0U | (code_point >> 18U)));
            output.push_back(static_cast<char>(0x80U | ((code_point >> 12U) & 0x3fU)));
            output.push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3fU)));
            output.push_back(static_cast<char>(0x80U | (code_point & 0x3fU)));
        }
    }
    return output;
}

ttstr ttstr_from_utf8(const std::string& text) {
    std::basic_string<tjs_char> output;
    for (std::size_t index = 0; index < text.size();) {
        const unsigned char first = static_cast<unsigned char>(text[index]);
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
            return ttstr();
        }
        if (length > text.size() - index) {
            return ttstr();
        }
        for (std::size_t offset = 1; offset < length; ++offset) {
            const unsigned char next = static_cast<unsigned char>(text[index + offset]);
            if (!valid_utf8_continuation(next)) {
                return ttstr();
            }
            code_point = (code_point << 6U) | (next & 0x3fU);
        }
        if ((length == 3 && code_point < 0x800U) ||
            (length == 4 && code_point < 0x10000U) ||
            (code_point >= 0xd800U && code_point <= 0xdfffU) ||
            code_point > 0x10ffffU) {
            return ttstr();
        }
        if (code_point <= 0xffffU) {
            output.push_back(static_cast<tjs_char>(code_point));
        } else {
            code_point -= 0x10000U;
            output.push_back(static_cast<tjs_char>(0xd800U | (code_point >> 10U)));
            output.push_back(static_cast<tjs_char>(0xdc00U | (code_point & 0x3ffU)));
        }
        index += length;
    }
    return ttstr(output);
}

StorageRegistryResult normalize_domain_path_name(
    const std::string& name,
    std::string* normalized_domain_path,
    std::string* lookup_name) {
    if (normalized_domain_path != nullptr) {
        normalized_domain_path->clear();
    }
    if (lookup_name != nullptr) {
        lookup_name->clear();
    }
    const std::string slashed = slash_normalized(name);
    if (!has_prefix(slashed, kTwinQuillStorageDomainPrefix)) {
        return StorageRegistryResult::kMalformed;
    }
    std::string lookup;
    const StorageRegistryResult result = normalize_storage_name(slashed.substr(2), &lookup);
    if (result != StorageRegistryResult::kOk) {
        return result;
    }
    if (normalized_domain_path != nullptr) {
        *normalized_domain_path = std::string(kTwinQuillStorageDomainPrefix) + lookup;
    }
    if (lookup_name != nullptr) {
        *lookup_name = std::move(lookup);
    }
    return StorageRegistryResult::kOk;
}

}  // namespace

StorageRegistry::~StorageRegistry() {
    for (LooseEntry& entry : loose_entries_) {
        close_if_owned(&entry.spec);
    }
}

StorageRegistryResult normalize_storage_name(
    const std::string& name,
    std::string* lookup_name) {
    if (lookup_name == nullptr) {
        return StorageRegistryResult::kMalformed;
    }
    lookup_name->clear();
    const std::string slashed = slash_normalized(name);
    if (slashed.empty() || !validate_utf8(slashed)) {
        return StorageRegistryResult::kMalformed;
    }
    if (slashed.size() >= 2 && is_ascii_alpha(static_cast<unsigned char>(slashed[0])) &&
        slashed[1] == ':') {
        return StorageRegistryResult::kMalformed;
    }

    std::vector<std::string> segments;
    std::string segment;
    for (const unsigned char raw_character : slashed) {
        if (raw_character != '/') {
            segment.push_back(static_cast<char>(raw_character));
            continue;
        }
        if (segment.empty() || segment == ".") {
            segment.clear();
            continue;
        }
        if (segment == "..") {
            if (segments.empty()) {
                return StorageRegistryResult::kMalformed;
            }
            segments.pop_back();
            segment.clear();
            continue;
        }
        segments.push_back(ascii_lowered(std::move(segment)));
        segment.clear();
    }
    if (!segment.empty() && segment != ".") {
        if (segment == "..") {
            if (segments.empty()) {
                return StorageRegistryResult::kMalformed;
            }
            segments.pop_back();
        } else {
            segments.push_back(ascii_lowered(std::move(segment)));
        }
    }
    if (segments.empty()) {
        return StorageRegistryResult::kMalformed;
    }

    for (std::size_t index = 0; index < segments.size(); ++index) {
        if (index != 0) {
            lookup_name->push_back('/');
        }
        lookup_name->append(segments[index]);
    }
    return StorageRegistryResult::kOk;
}

StorageRegistryResult normalize_twinquill_storage_name(
    const std::string& name,
    std::string* normalized_name,
    std::string* lookup_name) {
    if (normalized_name != nullptr) {
        normalized_name->clear();
    }
    if (!has_ascii_case_prefix(name, kTwinQuillStoragePrefix)) {
        return StorageRegistryResult::kMalformed;
    }
    std::string normalized_domain_path;
    const StorageRegistryResult result = normalize_domain_path_name(
        name.substr(std::char_traits<char>::length(kTwinQuillStoragePrefix)),
        &normalized_domain_path,
        lookup_name);
    if (result != StorageRegistryResult::kOk) {
        return result;
    }
    if (normalized_name != nullptr) {
        *normalized_name = std::string(kTwinQuillStoragePrefix) + normalized_domain_path;
    }
    return StorageRegistryResult::kOk;
}

void StorageRegistry::AddRef() {
    const int old_count = ref_count_.fetch_add(1, std::memory_order_relaxed);
    if (old_count <= 0) {
        ref_count_.fetch_sub(1, std::memory_order_relaxed);
        throw std::runtime_error("Storage media reference count is invalid");
    }
}

void StorageRegistry::Release() {
    const int old_count = ref_count_.fetch_sub(1, std::memory_order_acq_rel);
    if (old_count <= 0) {
        ref_count_.fetch_add(1, std::memory_order_relaxed);
        throw std::runtime_error("Storage media reference count underflow");
    }
    if (old_count == 1) {
        delete this;
    }
}

void StorageRegistry::GetName(ttstr& name) {
    name = TJS_W("twinquill");
}

void StorageRegistry::NormalizeDomainName(ttstr& name) {
    name = utf8_from_ttstr(name) == kTwinQuillStorageDomain ? TJS_W(".") : ttstr();
}

void StorageRegistry::NormalizePathName(ttstr& name) {
    std::string normalized_domain_path;
    if (normalize_domain_path_name(
            std::string(kTwinQuillStorageDomainPrefix) + utf8_from_ttstr(name),
            &normalized_domain_path,
            nullptr) == StorageRegistryResult::kOk) {
        name = ttstr_from_utf8(normalized_domain_path.substr(2));
    } else {
        name = ttstr();
    }
}

bool StorageRegistry::CheckExistentStorage(const ttstr& name) {
    std::unique_ptr<ReadOnlyStream> ignored;
    return Open(utf8_from_ttstr(name), &ignored) == StorageRegistryResult::kOk;
}

tTJSBinaryStream* StorageRegistry::Open(const ttstr& name, tjs_uint32 flags) {
    if (flags != TJS_BS_READ) {
        return nullptr;
    }
    std::unique_ptr<ReadOnlyStream> stream;
    return Open(utf8_from_ttstr(name), &stream) == StorageRegistryResult::kOk
        ? stream.release()
        : nullptr;
}

void StorageRegistry::GetListAt(const ttstr& /*name*/, iTVPStorageLister* /*lister*/) {
}

void StorageRegistry::GetLocallyAccessibleName(ttstr& name) {
    name = ttstr();
}

bool StorageRegistry::has_registered_lookup_name_locked(const std::string& lookup_name) const {
    return std::find(
        registered_lookup_names_.begin(),
        registered_lookup_names_.end(),
        lookup_name) != registered_lookup_names_.end();
}

StorageRegistryResult StorageRegistry::RegisterLoose(std::string name, StorageSpec spec) {
    ScopedStorageSpec retained(std::move(spec));
    std::string lookup_name;
    StorageRegistryResult result = normalize_storage_name(name, &lookup_name);
    if (result != StorageRegistryResult::kOk) {
        return result;
    }

    std::string registered_name = lookup_name;
    std::string loose_name = lookup_name;
    std::lock_guard<std::mutex> lock(mutex_);
    if (has_registered_lookup_name_locked(lookup_name)) {
        return StorageRegistryResult::kMalformed;
    }
    loose_entries_.reserve(loose_entries_.size() + 1);
    registered_lookup_names_.reserve(registered_lookup_names_.size() + 1);
    registered_lookup_names_.push_back(std::move(registered_name));
    loose_entries_.push_back(LooseEntry{std::move(loose_name), retained.release()});
    return StorageRegistryResult::kOk;
}

int StorageRegistry::RegisterArchive(StorageSpec spec) {
    ScopedStorageSpec retained(std::move(spec));
    StorageSpec open_spec = archive_open_spec_from(&retained.get());
    if (open_spec.backend == StorageBackend::kOwnedFileDescriptor && open_spec.descriptor < 0) {
        return 30;
    }

    std::shared_ptr<Xp3Archive> archive;
    const int result = Xp3Archive::Open(open_spec, &archive);
    if (result != 0 || archive == nullptr) {
        return result != 0 ? result : 34;
    }

    std::vector<ArchiveEntry> archive_entries;
    std::vector<std::string> archive_names;
    archive_entries.reserve(archive->EntryCount());
    archive_names.reserve(archive->EntryCount());
    for (std::size_t index = 0; index < archive->EntryCount(); ++index) {
        const Xp3Entry* entry = archive->EntryAt(index);
        if (entry == nullptr) {
            return 34;
        }
        std::string lookup_name;
        if (normalize_storage_name(entry->name, &lookup_name) != StorageRegistryResult::kOk) {
            return 34;
        }
        if (std::find(archive_names.begin(), archive_names.end(), lookup_name) !=
            archive_names.end()) {
            return 34;
        }
        archive_names.push_back(lookup_name);
        archive_entries.push_back(ArchiveEntry{std::move(lookup_name), entry});
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (archive_ != nullptr) {
        return 34;
    }
    for (const std::string& name : archive_names) {
        if (has_registered_lookup_name_locked(name)) {
            return 34;
        }
    }
    std::vector<std::string> combined_names = registered_lookup_names_;
    combined_names.reserve(combined_names.size() + archive_names.size());
    for (std::string& name : archive_names) {
        combined_names.push_back(std::move(name));
    }
    archive_entries_ = std::move(archive_entries);
    archive_ = std::move(archive);
    registered_lookup_names_ = std::move(combined_names);
    return 0;
}

StorageRegistryResult StorageRegistry::Open(
    const std::string& name,
    std::unique_ptr<ReadOnlyStream>* output) {
    if (output == nullptr) {
        return StorageRegistryResult::kMalformed;
    }
    output->reset();

    std::string lookup_name;
    StorageRegistryResult result = name.find("://") == std::string::npos
        ? normalize_domain_path_name(
            has_prefix(slash_normalized(name), kTwinQuillStorageDomainPrefix)
                ? name
                : std::string(kTwinQuillStorageDomainPrefix) + name,
            nullptr,
            &lookup_name)
        : normalize_twinquill_storage_name(name, nullptr, &lookup_name);
    if (result != StorageRegistryResult::kOk) {
        return result;
    }

    StorageSpec loose_open_spec = StorageSpec::OwnedFileDescriptor(-1);
    std::shared_ptr<Xp3Archive> archive;
    const Xp3Entry* archive_entry = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        for (const LooseEntry& entry : loose_entries_) {
            if (entry.lookup_name == lookup_name) {
                loose_open_spec = storage_spec_for_open(entry.spec);
                break;
            }
        }
        if (loose_open_spec.backend == StorageBackend::kOwnedFileDescriptor &&
            loose_open_spec.descriptor < 0 && archive_ != nullptr) {
            for (const ArchiveEntry& entry : archive_entries_) {
                if (entry.lookup_name == lookup_name) {
                    archive = archive_;
                    archive_entry = entry.entry;
                    break;
                }
            }
        }
    }

    if (loose_open_spec.backend == StorageBackend::kLocalFile || loose_open_spec.descriptor >= 0) {
        const int open_result = open_read_only_stream(loose_open_spec, output);
        return open_result == 0 && *output != nullptr
            ? StorageRegistryResult::kOk
            : StorageRegistryResult::kMalformed;
    }

    if (archive == nullptr || archive_entry == nullptr) {
        return StorageRegistryResult::kNotFound;
    }
    const int archive_result = archive->OpenEntryStream(*archive_entry, output);
    if (archive_result == 0 && *output != nullptr) {
        return StorageRegistryResult::kOk;
    }
    return archive_result == 35
        ? StorageRegistryResult::kProtected
        : StorageRegistryResult::kMalformed;
}

}  // namespace twinquill::krkr

namespace {

struct StorageMediaRecord {
    std::string name;
    iTVPStorageMedia* media;
};

std::mutex g_storage_media_mutex;
std::vector<StorageMediaRecord> g_storage_media;

std::string media_name_from(iTVPStorageMedia* media) {
    if (media == nullptr) {
        return std::string();
    }
    ttstr media_name;
    media->GetName(media_name);
    std::string name = twinquill::krkr::utf8_from_ttstr(media_name);
    if (!twinquill::krkr::validate_utf8(name)) {
        return std::string();
    }
    return twinquill::krkr::ascii_lowered(std::move(name));
}

iTVPStorageMedia* acquire_storage_media(const std::string& name) {
    std::lock_guard<std::mutex> lock(g_storage_media_mutex);
    for (const StorageMediaRecord& record : g_storage_media) {
        if (record.name == name && record.media != nullptr) {
            record.media->AddRef();
            return record.media;
        }
    }
    return nullptr;
}

class ScopedStorageMediaRef final {
public:
    explicit ScopedStorageMediaRef(iTVPStorageMedia* media) noexcept : media_(media) {
    }

    ~ScopedStorageMediaRef() noexcept {
        if (media_ != nullptr) {
            try {
                media_->Release();
            } catch (...) {
            }
        }
    }

    ScopedStorageMediaRef(const ScopedStorageMediaRef&) = delete;
    ScopedStorageMediaRef& operator=(const ScopedStorageMediaRef&) = delete;

    iTVPStorageMedia* get() const {
        return media_;
    }

private:
    iTVPStorageMedia* media_;
};

}  // namespace

tjs_char TVPArchiveDelimiter = TJS_W('>');

void TVPRegisterStorageMedia(iTVPStorageMedia* media) {
    if (media == nullptr) {
        throw std::invalid_argument("Storage media is null");
    }
    std::string name = media_name_from(media);
    if (name.empty()) {
        throw std::invalid_argument("Storage media name is invalid");
    }

    bool release_media = false;
    try {
        std::lock_guard<std::mutex> lock(g_storage_media_mutex);
        for (const StorageMediaRecord& record : g_storage_media) {
            if (record.media == media || record.name == name) {
                throw std::runtime_error("Storage media is already registered");
            }
        }
        media->AddRef();
        release_media = true;
        g_storage_media.push_back(StorageMediaRecord{std::move(name), media});
        release_media = false;
    } catch (...) {
        if (release_media) {
            media->Release();
        }
        throw;
    }
}

void TVPUnregisterStorageMedia(iTVPStorageMedia* media) {
    if (media == nullptr) {
        throw std::invalid_argument("Storage media is null");
    }
    iTVPStorageMedia* removed = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_storage_media_mutex);
        const auto position = std::find_if(
            g_storage_media.begin(),
            g_storage_media.end(),
            [media](const StorageMediaRecord& record) {
                return record.media == media;
            });
        if (position == g_storage_media.end()) {
            throw std::runtime_error("Storage media is not registered");
        }
        removed = position->media;
        g_storage_media.erase(position);
    }
    removed->Release();
}

ttstr TVPNormalizeStorageName(const ttstr& name) {
    std::string normalized;
    if (twinquill::krkr::normalize_twinquill_storage_name(
            twinquill::krkr::utf8_from_ttstr(name),
            &normalized,
            nullptr) != twinquill::krkr::StorageRegistryResult::kOk) {
        return ttstr();
    }
    return twinquill::krkr::ttstr_from_utf8(normalized);
}

bool TVPIsExistentStorageNoSearchNoNormalize(const ttstr& name) {
    std::string lookup_name;
    if (twinquill::krkr::normalize_twinquill_storage_name(
            twinquill::krkr::utf8_from_ttstr(name),
            nullptr,
            &lookup_name) != twinquill::krkr::StorageRegistryResult::kOk) {
        return false;
    }
    ScopedStorageMediaRef media(
        acquire_storage_media(twinquill::krkr::kTwinQuillStorageMediaName));
    return media.get() != nullptr &&
        media.get()->CheckExistentStorage(ttstr(("./" + lookup_name).c_str()));
}

bool TVPIsExistentStorageNoSearch(const ttstr& name) {
    const ttstr normalized = TVPNormalizeStorageName(name);
    return !normalized.IsEmpty() && TVPIsExistentStorageNoSearchNoNormalize(normalized);
}

tTJSBinaryStream* TVPCreateStream(const ttstr& name, tjs_uint32 flags) {
    const ttstr normalized = TVPNormalizeStorageName(name);
    if (normalized.IsEmpty()) {
        return nullptr;
    }
    std::string lookup_name;
    if (twinquill::krkr::normalize_twinquill_storage_name(
            twinquill::krkr::utf8_from_ttstr(normalized),
            nullptr,
            &lookup_name) != twinquill::krkr::StorageRegistryResult::kOk) {
        return nullptr;
    }
    ScopedStorageMediaRef media(
        acquire_storage_media(twinquill::krkr::kTwinQuillStorageMediaName));
    return media.get() == nullptr
        ? nullptr
        : media.get()->Open(ttstr(("./" + lookup_name).c_str()), flags);
}
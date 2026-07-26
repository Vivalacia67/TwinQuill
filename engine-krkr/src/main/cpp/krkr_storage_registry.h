/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "krkr_storage.h"
#include "krkr_xp3_archive.h"
#include "StorageIntf.h"

namespace twinquill::krkr {

constexpr char kTwinQuillStorageMediaName[] = "twinquill";
constexpr char kTwinQuillStartupStorageName[] = "twinquill://./startup.tjs";

enum class StorageRegistryResult {
    kOk,
    kNotFound,
    kMalformed,
    kProtected,
};

struct StorageRegistration {
    std::string name;
    StorageSpec spec;
};

// Reduced read-only platform subset of Kirikiri's iTVPStorageMedia contract.
class StorageRegistry final : public iTVPStorageMedia {
public:
    StorageRegistry() = default;
    ~StorageRegistry();

    StorageRegistry(const StorageRegistry&) = delete;
    StorageRegistry& operator=(const StorageRegistry&) = delete;

    void TJS_INTF_METHOD AddRef() override;
    void TJS_INTF_METHOD Release() override;
    void TJS_INTF_METHOD GetName(ttstr& name) override;
    void TJS_INTF_METHOD NormalizeDomainName(ttstr& name) override;
    void TJS_INTF_METHOD NormalizePathName(ttstr& name) override;
    bool TJS_INTF_METHOD CheckExistentStorage(const ttstr& name) override;
    tTJSBinaryStream* TJS_INTF_METHOD Open(const ttstr& name, tjs_uint32 flags) override;
    void TJS_INTF_METHOD GetListAt(const ttstr& name, iTVPStorageLister* lister) override;
    void TJS_INTF_METHOD GetLocallyAccessibleName(ttstr& name) override;

    StorageRegistryResult RegisterLoose(std::string name, StorageSpec spec);
    int RegisterArchive(StorageSpec spec);

    StorageRegistryResult Open(
        const std::string& name,
        std::unique_ptr<ReadOnlyStream>* output);

private:
    struct LooseEntry {
        std::string lookup_name;
        StorageSpec spec;
    };

    struct ArchiveEntry {
        std::string lookup_name;
        const Xp3Entry* entry;
    };

    bool has_registered_lookup_name_locked(const std::string& lookup_name) const;

    std::atomic<int> ref_count_{1};
    mutable std::mutex mutex_;
    std::vector<LooseEntry> loose_entries_;
    std::vector<std::string> registered_lookup_names_;
    std::vector<ArchiveEntry> archive_entries_;
    std::shared_ptr<Xp3Archive> archive_;
};

StorageRegistryResult normalize_storage_name(
    const std::string& name,
    std::string* lookup_name);

StorageRegistryResult normalize_twinquill_storage_name(
    const std::string& name,
    std::string* normalized_name,
    std::string* lookup_name);

}  // namespace twinquill::krkr
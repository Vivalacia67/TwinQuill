/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#ifndef TWINQUILL_KRKR_VFS_STORAGE_H
#define TWINQUILL_KRKR_VFS_STORAGE_H

#include <cstdint>
#include <string>

#include "StorageIntf.h"
#include "twinquill_vfs.h"

class TqSafMedia final : public tTVPStorageMedia {
public:
    explicit TqSafMedia(std::string tree_uri);

    void GetName(ttstr& name) override;
    void NormalizeDomainName(ttstr& name) override;
    void NormalizePathName(ttstr& name) override;
    bool CheckExistentStorage(const ttstr& name) override;
    tTJSBinaryStream* Open(const ttstr& name, tjs_uint32 flags) override;
    void GetListAt(const ttstr& name, iTVPStorageLister* lister) override;
    void GetLocallyAccessibleName(ttstr& name) override;

    int last_status() const { return last_status_; }

private:
    std::string tree_uri_;
    int last_status_ = TQ_VFS_OK;
};

int run_tqsaf_startup(const char* tree_uri_utf8);
int read_tqsaf_script(const std::string& tree_uri, const std::string& relative_path,
                     std::string* output, bool startup = false);
int exists_tqsaf_script(const std::string& tree_uri, const std::string& relative_path,
                       bool* exists);

#endif

/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once
#include <string>
#include "krkr_resource.h"
namespace twinquill::krkr {
constexpr const char* kDataPath = "tqsave://./";
class PrivateStorage {
public:
    explicit PrivateStorage(const std::string& validated_game_save_directory);
    ~PrivateStorage();
    PrivateStorage(const PrivateStorage&) = delete;
    PrivateStorage& operator=(const PrivateStorage&) = delete;
    static bool owns(const std::string& name);
    std::string read(const std::string& name);
    bool exists(const std::string& name);
    void create_folders(const std::string& name);
    void write(const std::string& name, std::string_view bytes);
private:
    std::string relative(const std::string& name, bool folder = false) const;
    int parent(const std::string& relative, bool create);
    int root_ = -1;
};
}

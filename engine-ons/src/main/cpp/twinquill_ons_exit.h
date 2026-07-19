/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#pragma once

class TwinQuillOnsExit final {
public:
    explicit TwinQuillOnsExit(int status) : status_(status) {
    }

    int status() const {
        return status_;
    }

private:
    int status_;
};

[[noreturn]] inline void twinquillOnsExit(int status) {
    throw TwinQuillOnsExit(status);
}

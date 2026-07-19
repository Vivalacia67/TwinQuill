/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

data class PersistableUriGrant(val uri: Uri, val flags: Int)

class OpenGameTreeContract : ActivityResultContract<Unit, PersistableUriGrant?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): PersistableUriGrant? {
        if (resultCode != Activity.RESULT_OK) return null
        val uri = intent?.data ?: return null
        return PersistableUriGrant(uri, intent.flags)
    }
}

class OpenCoverContract : ActivityResultContract<Unit, PersistableUriGrant?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

    override fun parseResult(resultCode: Int, intent: Intent?): PersistableUriGrant? {
        if (resultCode != Activity.RESULT_OK) return null
        val uri = intent?.data ?: return null
        return PersistableUriGrant(uri, intent.flags)
    }
}

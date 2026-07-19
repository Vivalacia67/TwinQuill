/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.github.twinquill.engine.api.EngineContract
import io.github.twinquill.engine.api.EngineLaunchRequest
import io.github.twinquill.engine.api.EngineType
import io.github.twinquill.engine.krkr.KrkrEngineActivity
import io.github.twinquill.engine.ons.OnsEngineActivity
import io.github.twinquill.launcher.data.GameEntity
import java.io.File

object EngineRouter {
    fun createIntent(context: Context, game: GameEntity, engine: EngineType): Intent {
        require(engine != EngineType.AUTO)
        val saveDirectory = File(context.filesDir, "saves/${game.id}")
        check(saveDirectory.isDirectory || saveDirectory.mkdirs()) {
            "Unable to create private save directory"
        }
        val request =
            EngineLaunchRequest(
                game.id,
                Uri.parse(game.directoryUri),
                saveDirectory.absolutePath,
                engine,
                Bundle.EMPTY,
            )
        val activity =
            when (engine) {
                EngineType.ONS -> OnsEngineActivity::class.java
                EngineType.KRKR -> KrkrEngineActivity::class.java
                EngineType.AUTO -> error("AUTO cannot be launched")
            }
        return Intent(context, activity).putExtra(EngineContract.EXTRA_LAUNCH_REQUEST, request)
    }
}

/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF5B4A8D),
        secondary = Color(0xFF73546F),
        tertiary = Color(0xFF8A4F45),
        surface = Color(0xFFFFF8FC),
        surfaceVariant = Color(0xFFE8E0EC),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFCFBDFE),
        secondary = Color(0xFFE2BADA),
        tertiary = Color(0xFFFFB4A6),
    )

@Composable
fun TwinQuillTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

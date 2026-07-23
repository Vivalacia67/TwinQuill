/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import io.github.twinquill.engine.api.EngineResult;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class MainActivityResultMessageInstrumentedTest {
    @Test
    public void formatsKnownKrkrDiagnosticCodes() {
        assertEquals(
            "引擎返回：TJS脚本错误（20）",
            MainActivityKt.engineResultMessage(EngineResult.SCRIPT_ERROR, 20)
        );
        assertEquals(
            "引擎返回：所有XP3均无startup.tjs（31）",
            MainActivityKt.engineResultMessage(EngineResult.SCRIPT_ERROR, 31)
        );
        assertEquals(
            "引擎返回：受保护XP3当前尚未支持（35）",
            MainActivityKt.engineResultMessage(EngineResult.SCRIPT_ERROR, 35)
        );
    }

    @Test
    public void formatsUnknownKrkrDiagnosticCode() {
        assertEquals(
            "引擎返回：Krkr诊断码：99",
            MainActivityKt.engineResultMessage(EngineResult.SCRIPT_ERROR, 99)
        );
    }

    @Test
    public void keepsGenericMessageWhenKrkrDiagnosticIsAbsentOrNotScriptError() {
        assertEquals(
            "引擎返回：SCRIPT_ERROR",
            MainActivityKt.engineResultMessage(EngineResult.SCRIPT_ERROR, null)
        );
        assertEquals(
            "引擎返回：NORMAL_EXIT",
            MainActivityKt.engineResultMessage(EngineResult.NORMAL_EXIT, 20)
        );
    }
}

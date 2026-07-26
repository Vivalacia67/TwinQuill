/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.twinquill.engine.api.EngineContract;
import io.github.twinquill.engine.api.EngineLaunchRequest;
import io.github.twinquill.engine.api.EngineResult;
import io.github.twinquill.engine.api.EngineType;
import io.github.twinquill.engine.krkr.KrkrEngineActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class KrkrCompressedXp3InstrumentedTest {
    private static final int UNSUPPORTED_XP3_DIAGNOSTIC = 33;
    private static final int MALFORMED_XP3_DIAGNOSTIC = 34;
    private static final int KAG_SCENARIO_MISSING_DIAGNOSTIC = 40;
    private static final int KAG_SCENARIO_MALFORMED_DIAGNOSTIC = 41;

    private Instrumentation instrumentation;
    private Context context;
    private EngineProtocolTestHostActivity host;

    @Before
    public void startHost() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        host = createHost();
    }

    @After
    public void finishHost() {
        if (host != null) {
            instrumentation.runOnMainSync(host::finish);
        }
    }

    @Test
    public void runsKrkrStartupFromCompressedXp3() throws Exception {
        assertNormalExit(
            "krkr-xp3-ok-",
            KrkrXp3FixtureBuilder.compressedStartupArchive()
        );
    }

    @Test
    public void runsKrkrStartupWithKagAttributesAndControlExpressionFromXp3()
        throws Exception {
        assertNormalExit(
            "krkr-xp3-kag-ok-",
            KrkrXp3FixtureBuilder.archiveWithKagScenario(
                "scenario/intro.ks",
                "scenario/intro.ks"
            )
        );
    }

    @Test
    public void runsRegisteredKagParserStartupFromXp3AcrossLaunches()
        throws Exception {
        assertNormalExitAcrossLaunches(
            "krkr-xp3-registered-kag-ok-",
            KrkrXp3FixtureBuilder.archiveWithRegisteredKagParserScenario(
                "scenario/intro.ks",
                "scenario/intro.ks"
            ),
            2
        );
    }

    @Test
    public void runsKrkrStartupWithUtf8BomKagScenarioFromXp3()
        throws Exception {
        assertNormalExit(
            "krkr-xp3-kag-utf8-bom-",
            KrkrXp3FixtureBuilder.archiveWithKagScenario(
                "scenario/utf8-bom.ks",
                "scenario/utf8-bom.ks",
                KrkrXp3FixtureBuilder.utf8BomKagScenarioSource()
            )
        );
    }

    @Test
    public void normalizesKagScenarioStorageNameWhenReadingXp3Entry()
        throws Exception {
        assertNormalExit(
            "krkr-xp3-kag-normalized-",
            KrkrXp3FixtureBuilder.archiveWithKagScenario(
                ".\\Scenario\\./Chapter01.KS",
                "scenario/chapter01.ks"
            )
        );
    }

    @Test
    public void returnsMissingScenarioDiagnosticForExplicitKagScenario()
        throws Exception {
        assertScriptErrorResult(
            "krkr-xp3-kag-missing-",
            KrkrXp3FixtureBuilder.archiveWithMissingKagScenario("scenario/missing.ks"),
            KAG_SCENARIO_MISSING_DIAGNOSTIC
        );
    }

    @Test
    public void acceptsKagScenarioWithoutLabelWhenUpstreamParserEmitsTag()
        throws Exception {
        assertNormalExit(
            "krkr-xp3-kag-no-label-ok-",
            KrkrXp3FixtureBuilder.archiveWithKagScenario(
                "scenario/no-label.ks",
                "scenario/no-label.ks",
                KrkrXp3FixtureBuilder.noLabelKagScenarioSource()
            )
        );
    }

    @Test
    public void returnsMalformedScenarioDiagnosticForUnclosedQuotedAttribute()
        throws Exception {
        assertScriptErrorResult(
            "krkr-xp3-kag-unclosed-quote-",
            KrkrXp3FixtureBuilder.archiveWithKagScenario(
                "scenario/unclosed-quote.ks",
                "scenario/unclosed-quote.ks",
                KrkrXp3FixtureBuilder.malformedQuotedAttributeKagScenarioSource()
            ),
            KAG_SCENARIO_MALFORMED_DIAGNOSTIC
        );
    }

    @Test
    public void returnsMalformedScenarioDiagnosticForInvalidControlExpression()
        throws Exception {
        assertScriptErrorResult(
            "krkr-xp3-kag-invalid-expression-",
            KrkrXp3FixtureBuilder.archiveWithKagScenario(
                "scenario/invalid-expression.ks",
                "scenario/invalid-expression.ks",
                KrkrXp3FixtureBuilder.invalidControlExpressionKagScenarioSource()
            ),
            KAG_SCENARIO_MALFORMED_DIAGNOSTIC
        );
    }

    @Test
    public void returnsMalformedScenarioDiagnosticForInvalidUtf8Kag()
        throws Exception {
        assertScriptErrorResult(
            "krkr-xp3-kag-invalid-utf8-",
            KrkrXp3FixtureBuilder.archiveWithKagScenario(
                "scenario/invalid.ks",
                "scenario/invalid.ks",
                new byte[] {0x2a, 0x73, 0x74, 0x61, 0x72, 0x74, 0x0a, (byte) 0xc3, 0x28, 0x0a}
            ),
            KAG_SCENARIO_MALFORMED_DIAGNOSTIC
        );
    }

    @Test
    public void runsKrkrStartupFromRawIndexWhenStartupIsNotFirstEntry()
        throws Exception {
        assertNormalExit(
            "krkr-xp3-startup-second-entry-",
            KrkrXp3FixtureBuilder.archiveWithStartupAsSecondEntry()
        );
    }

    @Test
    public void runsKrkrStartupAcrossRawAndCompressedSegments()
        throws Exception {
        assertNormalExit(
            "krkr-xp3-raw-zlib-segments-",
            KrkrXp3FixtureBuilder.archiveWithRawAndCompressedStartupSegments()
        );
    }

    @Test
    public void runsKrkrStartupFromContinuedCompressedXp3Index() throws Exception {
        assertNormalExit(
            "krkr-xp3-continued-index-ok-",
            KrkrXp3FixtureBuilder.archiveWithContinuedCompressedIndex()
        );
    }

    @Test
    public void runsKrkrStartupFromSecondContinuedXp3IndexBlock()
        throws Exception {
        assertNormalExit(
            "krkr-xp3-second-continued-index-block-",
            KrkrXp3FixtureBuilder.archiveWithStartupInSecondContinuedIndexBlock()
        );
    }

    @Test
    public void rejectsKrkrXp3WithCorruptCompressedIndex() throws Exception {
        assertScriptErrorResult(
            "krkr-xp3-bad-index-",
            KrkrXp3FixtureBuilder.archiveWithCorruptCompressedIndex(),
            MALFORMED_XP3_DIAGNOSTIC
        );
    }

    @Test
    public void rejectsKrkrXp3WithCorruptCompressedSegment() throws Exception {
        assertScriptErrorResult(
            "krkr-xp3-bad-segment-",
            KrkrXp3FixtureBuilder.archiveWithCorruptCompressedSegment(),
            MALFORMED_XP3_DIAGNOSTIC
        );
    }

    @Test
    public void rejectsKrkrXp3WithSegmentOffsetBeyondArchive() throws Exception {
        assertScriptErrorResult(
            "krkr-xp3-segment-offset-beyond-archive-",
            KrkrXp3FixtureBuilder.archiveWithSegmentOffsetBeyondArchive(),
            MALFORMED_XP3_DIAGNOSTIC
        );
    }

    @Test
    public void rejectsKrkrXp3WithEntryOriginalSizeMismatch() throws Exception {
        assertScriptErrorResult(
            "krkr-xp3-entry-original-size-mismatch-",
            KrkrXp3FixtureBuilder.archiveWithInfoOriginalSizeMismatch(),
            MALFORMED_XP3_DIAGNOSTIC
        );
    }

    @Test
    public void rejectsKrkrXp3WithRawSegmentArchivedSizeMismatch()
        throws Exception {
        assertScriptErrorResult(
            "krkr-xp3-raw-segment-size-mismatch-",
            KrkrXp3FixtureBuilder.archiveWithRawSegmentSizeMismatch(),
            UNSUPPORTED_XP3_DIAGNOSTIC
        );
    }

    @Test
    public void rejectsKrkrXp3WithSelfLoopingIndexContinuation() throws Exception {
        assertScriptErrorResult(
            "krkr-xp3-loop-index-",
            KrkrXp3FixtureBuilder.archiveWithSelfLoopingIndexContinuation(),
            MALFORMED_XP3_DIAGNOSTIC
        );
    }

    private EngineProtocolTestHostActivity createHost() {
        Intent intent = new Intent(context, EngineProtocolTestHostActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return (EngineProtocolTestHostActivity) instrumentation.startActivitySync(intent);
    }

    private void assertNormalExit(String gameIdPrefix, byte[] archive)
        throws Exception {
        Fixture fixture = createFixture(gameIdPrefix, archive);
        try {
            Intent result = launchAndAwait(requestIntent(fixture));
            assertEquals(Activity.RESULT_OK, host.engineResultCode());
            assertEquals(
                EngineResult.NORMAL_EXIT.code(),
                result.getIntExtra(EngineContract.EXTRA_RESULT, -1)
            );
            assertEquals(
                0,
                result.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1)
            );
        } finally {
            fixture.delete();
        }
    }

    private void assertNormalExitAcrossLaunches(
        String gameIdPrefix,
        byte[] archive,
        int launchCount
    ) throws Exception {
        Fixture fixture = createFixture(gameIdPrefix, archive);
        try {
            for (int attempt = 0; attempt < launchCount; attempt++) {
                Intent result = launchAndAwait(requestIntent(fixture));
                assertEquals(Activity.RESULT_OK, host.engineResultCode());
                assertEquals(
                    EngineResult.NORMAL_EXIT.code(),
                    result.getIntExtra(EngineContract.EXTRA_RESULT, -1)
                );
                assertEquals(
                    0,
                    result.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1)
                );
            }
        } finally {
            fixture.delete();
        }
    }

    private void assertScriptErrorResult(
        String gameIdPrefix,
        byte[] archive,
        int diagnostic
    ) throws Exception {
        Fixture fixture = createFixture(gameIdPrefix, archive);
        try {
            Intent result = launchAndAwait(requestIntent(fixture));
            assertEquals(Activity.RESULT_CANCELED, host.engineResultCode());
            assertEquals(
                EngineResult.SCRIPT_ERROR.code(),
                result.getIntExtra(EngineContract.EXTRA_RESULT, -1)
            );
            assertEquals(
                diagnostic,
                result.getIntExtra(KrkrEngineActivity.EXTRA_RESULT_CODE, -1)
            );
        } finally {
            fixture.delete();
        }
    }

    private Fixture createFixture(String gameIdPrefix, byte[] archive)
        throws Exception {
        String gameId = gameIdPrefix + android.os.SystemClock.elapsedRealtime();
        File root = new File(context.getFilesDir(), "krkr-xp3-fixtures/" + gameId);
        File saveDirectory = new File(context.getFilesDir(), "saves/" + gameId);
        deleteTree(root);
        deleteTree(saveDirectory);
        assertTrue(root.mkdirs());
        Files.write(new File(root, "data.xp3").toPath(), archive);
        return new Fixture(gameId, root, saveDirectory);
    }

    private Intent requestIntent(Fixture fixture) {
        EngineLaunchRequest request = new EngineLaunchRequest(
            fixture.gameId,
            Uri.fromFile(fixture.root),
            fixture.saveDirectory.getAbsolutePath(),
            EngineType.KRKR,
            Bundle.EMPTY
        );
        return new Intent(context, KrkrEngineActivity.class)
            .putExtra(EngineContract.EXTRA_LAUNCH_REQUEST, request);
    }

    private Intent launchAndAwait(Intent intent) throws Exception {
        instrumentation.runOnMainSync(() -> host.launchEngine(intent));
        assertTrue(host.awaitEngineResult(15, TimeUnit.SECONDS));
        Intent data = host.engineResultData();
        assertNotNull(data);
        return data;
    }

    private static void deleteTree(File file) throws Exception {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        Files.delete(file.toPath());
    }

    private static final class Fixture {
        final String gameId;
        final File root;
        final File saveDirectory;

        Fixture(String gameId, File root, File saveDirectory) {
            this.gameId = gameId;
            this.root = root;
            this.saveDirectory = saveDirectory;
        }

        void delete() throws Exception {
            deleteTree(root);
            deleteTree(saveDirectory);
        }
    }
}

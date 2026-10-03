/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.twinquill.engine.api.EngineContract;
import io.github.twinquill.engine.api.EngineLaunchRequest;
import io.github.twinquill.engine.api.EngineType;
import io.github.twinquill.engine.krkr.KrkrEngineActivity;
import io.github.twinquill.engine.krkr.KrkrRuntimeActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class KrkrBrokerLifecycleInstrumentedTest {
    private Instrumentation instrumentation;
    private Context context;

    @Before
    public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        assertEquals("Rebuild and reinstall the test APK with -PtwinquillKrkrRuntimeInstrumentation=true",
            "io.github.twinquill:krkr", instrumentation.getProcessName());
        context = instrumentation.getTargetContext();
    }

    @Test
    public void recreatingKrkrBrokerDoesNotLaunchRuntimeTwice() throws Exception {
        assertEquals("io.github.twinquill:krkr", instrumentation.getProcessName());
        Instrumentation.ActivityMonitor runtimeMonitor = instrumentation.addMonitor(
            KrkrRuntimeActivity.class.getName(),
            null,
            false
        );
        Application application = (Application) context.getApplicationContext();
        AtomicInteger brokerCreationCount = new AtomicInteger();
        AtomicReference<Activity> latestBroker = new AtomicReference<>();
        Application.ActivityLifecycleCallbacks lifecycleCallbacks =
            new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle state) {
                    if (activity instanceof KrkrEngineActivity) {
                        brokerCreationCount.incrementAndGet();
                        latestBroker.set(activity);
                    }
                }

                @Override
                public void onActivityStarted(Activity activity) {}

                @Override
                public void onActivityResumed(Activity activity) {}

                @Override
                public void onActivityPaused(Activity activity) {}

                @Override
                public void onActivityStopped(Activity activity) {}

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

                @Override
                public void onActivityDestroyed(Activity activity) {}
            };
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks);
        try {
            String gameId = "krkr-recreate-" + android.os.SystemClock.elapsedRealtime();
            EngineLaunchRequest request = new EngineLaunchRequest(
                gameId,
                EngineProcessProtocolInstrumentedTest.grantFixture(
                    context,
                    LauncherFixtureDocumentsProvider.KRKR_ROOT_ID
                ),
                new File(context.getFilesDir(), "saves/" + gameId).getAbsolutePath(),
                EngineType.KRKR,
                Bundle.EMPTY
            );
            Intent launch = new Intent(context, KrkrEngineActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EngineContract.EXTRA_LAUNCH_REQUEST, request);

            Activity broker = instrumentation.startActivitySync(launch);
            assertNotNull("Krkr broker Activity was not launched in :krkr", broker);
            assertEquals(1, brokerCreationCount.get());
            assertTrue("Krkr broker lifecycle callback missed the launch", latestBroker.get() == broker);
            instrumentation.runOnMainSync(broker::recreate);

            long recreationDeadline = android.os.SystemClock.elapsedRealtime() + 10_000;
            while ((brokerCreationCount.get() < 2 || latestBroker.get() == broker)
                && android.os.SystemClock.elapsedRealtime() < recreationDeadline) {
                Thread.sleep(50);
            }
            assertEquals("Krkr broker was not recreated", 2, brokerCreationCount.get());
            Activity recreatedBroker = latestBroker.get();
            assertNotNull(recreatedBroker);
            assertTrue("Krkr broker recreation retained the old Activity", recreatedBroker != broker);

            Activity runtime = instrumentation.waitForMonitorWithTimeout(
                runtimeMonitor,
                15_000
            );
            assertNotNull("Krkr runtime Activity was not launched", runtime);
            assertEquals("Krkr runtime launched more than once", 1, runtimeMonitor.getHits());
            instrumentation.runOnMainSync(runtime::onBackPressed);

            long finishDeadline = android.os.SystemClock.elapsedRealtime() + 5_000;
            Activity currentBroker = latestBroker.get();
            while (currentBroker != null && !currentBroker.isFinishing()
                && android.os.SystemClock.elapsedRealtime() < finishDeadline) {
                Thread.sleep(50);
                currentBroker = latestBroker.get();
            }
            assertNotNull(currentBroker);
            assertTrue("Krkr broker did not finish after runtime exit", currentBroker.isFinishing());
        } finally {
            try {
                finishActivity(runtimeMonitor.getLastActivity());
            } finally {
                try {
                    finishActivity(latestBroker.get());
                } finally {
                    application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
                    instrumentation.removeMonitor(runtimeMonitor);
                }
            }
        }
    }

    private void finishActivity(Activity activity) {
        if (activity != null) {
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}

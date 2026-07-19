/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import io.github.twinquill.engine.api.EngineType;
import io.github.twinquill.launcher.data.GameDao;
import io.github.twinquill.launcher.data.GameEntity;
import io.github.twinquill.launcher.data.GameRepository;
import io.github.twinquill.launcher.data.TwinQuillDatabase;
import io.github.twinquill.launcher.storage.UriPermissionManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class GamePersistenceInstrumentedTest {
    private static final int PICKER_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    private Context context;
    private ContentResolver resolver;
    private GameDao games;
    private GameRepository repository;
    private Uri treeUri;

    @Before
    public void prepareFixture() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        resolver = context.getContentResolver();
        games = TwinQuillDatabase.get(context).games();
        repository = new GameRepository(context);
        treeUri = LauncherFixtureDocumentsProvider.treeUri();

        GameEntity stale = findByDirectoryUri(treeUri.toString());
        if (stale != null) {
            games.deleteById(stale.id);
        }
        UriPermissionManager.releaseReadPermission(resolver, treeUri);
    }

    @After
    public void cleanUpFixture() {
        if (repository != null) {
            repository.close();
        }
        GameEntity stale = findByDirectoryUri(treeUri.toString());
        if (stale != null) {
            games.deleteById(stale.id);
        }
        UriPermissionManager.releaseReadPermission(resolver, treeUri);
    }

    @Test
    public void restoresRoomEntryAndTracksPersistedGrantLoss() throws Exception {
        Bundle grant = resolver.call(
            Uri.parse("content://" + LauncherGrantBrokerProvider.AUTHORITY),
            LauncherGrantBrokerProvider.METHOD_GRANT,
            null,
            null
        );
        assertNotNull(grant);

        CallbackResult<GameEntity> added = new CallbackResult<>();
        repository.addGame(treeUri, PICKER_FLAGS, added::complete);
        GameEntity game = added.await();
        assertEquals("持久化测试", game.name);
        assertEquals(EngineType.ONS.name(), game.detectedEngine);
        assertTrue(game.permissionValid);
        assertTrue(UriPermissionManager.hasReadPermission(resolver, treeUri));

        repository.close();
        repository = new GameRepository(context);
        GameEntity restored = games.findById(game.id);
        assertNotNull(restored);
        assertEquals(treeUri.toString(), restored.directoryUri);
        assertTrue(restored.permissionValid);

        resolver.releasePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        );
        CallbackResult<Integer> revoked = new CallbackResult<>();
        repository.refreshPermissions(revoked::complete);
        assertEquals(Integer.valueOf(1), revoked.await());
        assertFalse(games.findById(game.id).permissionValid);

        grant = resolver.call(
            Uri.parse("content://" + LauncherGrantBrokerProvider.AUTHORITY),
            LauncherGrantBrokerProvider.METHOD_GRANT,
            null,
            null
        );
        assertNotNull(grant);
        resolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        );
        CallbackResult<Integer> restoredGrant = new CallbackResult<>();
        repository.refreshPermissions(restoredGrant::complete);
        assertEquals(Integer.valueOf(1), restoredGrant.await());
        restored = games.findById(game.id);
        assertTrue(restored.permissionValid);

        CallbackResult<Boolean> deleted = new CallbackResult<>();
        repository.deleteGame(restored, deleted::complete);
        assertTrue(deleted.await());
        assertNull(games.findById(game.id));
        assertFalse(UriPermissionManager.hasReadPermission(resolver, treeUri));
    }

    private GameEntity findByDirectoryUri(String uri) {
        for (GameEntity game : games.getAll()) {
            if (uri.equals(game.directoryUri)) {
                return game;
            }
        }
        return null;
    }

    private static final class CallbackResult<T> {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<T> value = new AtomicReference<>();
        private final AtomicReference<Exception> error = new AtomicReference<>();

        void complete(T result, Exception exception) {
            value.set(result);
            error.set(exception);
            latch.countDown();
        }

        T await() throws Exception {
            assertTrue("Repository callback timed out", latch.await(15, TimeUnit.SECONDS));
            if (error.get() != null) {
                throw error.get();
            }
            return value.get();
        }
    }
}

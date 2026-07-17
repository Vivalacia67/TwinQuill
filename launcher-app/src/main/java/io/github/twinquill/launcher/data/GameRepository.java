/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher.data;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.twinquill.engine.api.DetectionResult;
import io.github.twinquill.engine.api.EngineType;
import io.github.twinquill.engine.api.GameDetector;
import io.github.twinquill.launcher.storage.SafGameDirectoryProbe;
import io.github.twinquill.launcher.storage.UriPermissionManager;

/** Asynchronous boundary around Room, URI grants, and directory detection. */
public final class GameRepository implements AutoCloseable {
    public interface Callback<T> {
        void complete(T value, Exception error);
    }

    private final ContentResolver resolver;
    private final GameDao games;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public GameRepository(Context context) {
        resolver = context.getApplicationContext().getContentResolver();
        games = TwinQuillDatabase.get(context).games();
    }

    public LiveData<List<GameEntity>> observeGames() {
        return games.observeAll();
    }

    public void addGame(Uri treeUri, int resultFlags, Callback<GameEntity> callback) {
        executor.execute(() -> {
            try {
                UriPermissionManager.takeReadPermission(resolver, treeUri, resultFlags);
                SafGameDirectoryProbe probe = new SafGameDirectoryProbe(resolver, treeUri);
                DetectionResult detection = GameDetector.detect(probe);
                GameEntity game = GameEntity.create(
                    probe.displayName(),
                    treeUri.toString(),
                    detection
                );
                games.insert(game);
                deliver(callback, game, null);
            } catch (Exception exception) {
                if (games.countByDirectoryUri(treeUri.toString()) == 0) {
                    UriPermissionManager.releaseReadPermission(resolver, treeUri);
                }
                deliver(callback, null, exception);
            }
        });
    }

    public void deleteGame(GameEntity game, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                boolean deleted = games.deleteById(game.id) != 0;
                if (deleted && games.countByDirectoryUri(game.directoryUri) == 0) {
                    UriPermissionManager.releaseReadPermission(
                        resolver,
                        Uri.parse(game.directoryUri)
                    );
                }
                if (deleted && game.coverUri != null
                    && games.countByCoverUri(game.coverUri) == 0) {
                    UriPermissionManager.releaseReadPermission(
                        resolver,
                        Uri.parse(game.coverUri)
                    );
                }
                deliver(callback, deleted, null);
            } catch (Exception exception) {
                deliver(callback, false, exception);
            }
        });
    }

    public void setOverride(String id, EngineType engine, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                deliver(callback, games.setOverride(id, engine.name()) != 0, null);
            } catch (Exception exception) {
                deliver(callback, false, exception);
            }
        });
    }

    public void setCover(String id, Uri coverUri, int resultFlags, Callback<Boolean> callback) {
        executor.execute(() -> {
            try {
                UriPermissionManager.takeReadPermission(resolver, coverUri, resultFlags);
                GameEntity game = games.findById(id);
                if (game == null) {
                    throw new IllegalArgumentException("Unknown game");
                }
                boolean updated = games.setCover(id, coverUri.toString()) != 0;
                if (updated && game.coverUri != null
                    && !game.coverUri.equals(coverUri.toString())
                    && games.countByCoverUri(game.coverUri) == 0) {
                    UriPermissionManager.releaseReadPermission(
                        resolver,
                        Uri.parse(game.coverUri)
                    );
                }
                deliver(callback, updated, null);
            } catch (Exception exception) {
                if (games.countByCoverUri(coverUri.toString()) == 0) {
                    UriPermissionManager.releaseReadPermission(resolver, coverUri);
                }
                deliver(callback, false, exception);
            }
        });
    }

    public void markPlayed(String id) {
        executor.execute(() -> games.markPlayed(id, System.currentTimeMillis()));
    }

    public void refreshPermissions() {
        executor.execute(() -> {
            List<GameEntity> snapshot = games.getAll();
            for (GameEntity game : snapshot) {
                boolean valid = UriPermissionManager.hasReadPermission(
                    resolver,
                    Uri.parse(game.directoryUri)
                );
                if (valid != game.permissionValid) {
                    games.setPermissionValid(game.id, valid);
                }
            }
        });
    }

    private <T> void deliver(Callback<T> callback, T value, Exception error) {
        if (callback != null) {
            main.post(() -> callback.complete(value, error));
        }
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}

/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface GameDao {
    @Query(
        "SELECT * FROM games "
            + "ORDER BY CASE WHEN lastPlayedAt IS NULL THEN 1 ELSE 0 END, "
            + "lastPlayedAt DESC, createdAt DESC"
    )
    LiveData<List<GameEntity>> observeAll();

    @Query("SELECT * FROM games")
    List<GameEntity> getAll();

    @Query("SELECT * FROM games WHERE id = :id LIMIT 1")
    GameEntity findById(String id);

    @Query("SELECT COUNT(*) FROM games WHERE directoryUri = :directoryUri")
    int countByDirectoryUri(String directoryUri);

    @Query("SELECT COUNT(*) FROM games WHERE coverUri = :coverUri")
    int countByCoverUri(String coverUri);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(GameEntity game);

    @Query("DELETE FROM games WHERE id = :id")
    int deleteById(String id);

    @Query("UPDATE games SET overrideEngine = :engine WHERE id = :id")
    int setOverride(String id, String engine);

    @Query("UPDATE games SET coverUri = :coverUri WHERE id = :id")
    int setCover(String id, String coverUri);

    @Query("UPDATE games SET lastPlayedAt = :timestamp WHERE id = :id")
    int markPlayed(String id, long timestamp);

    @Query("UPDATE games SET permissionValid = :valid WHERE id = :id")
    int setPermissionValid(String id, boolean valid);
}

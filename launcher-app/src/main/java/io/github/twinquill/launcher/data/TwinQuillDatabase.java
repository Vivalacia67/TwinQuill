/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {GameEntity.class}, version = 1, exportSchema = true)
public abstract class TwinQuillDatabase extends RoomDatabase {
    private static volatile TwinQuillDatabase instance;

    public abstract GameDao games();

    public static TwinQuillDatabase get(Context context) {
        TwinQuillDatabase current = instance;
        if (current == null) {
            synchronized (TwinQuillDatabase.class) {
                current = instance;
                if (current == null) {
                    current = Room.databaseBuilder(
                        context.getApplicationContext(),
                        TwinQuillDatabase.class,
                        "twinquill.db"
                    ).build();
                    instance = current;
                }
            }
        }
        return current;
    }
}

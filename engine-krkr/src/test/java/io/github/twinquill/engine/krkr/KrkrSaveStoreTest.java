/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.krkr;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class KrkrSaveStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void snapshotRestoreAndClearPreserveOnsAndOtherGames() throws Exception {
        File files = temporary.newFolder("files");
        Path parent = files.toPath().resolve("saves/game");
        Files.createDirectories(parent.resolve("krkr/nested"));
        write(parent.resolve("ons.dat"), "ONS");
        write(parent.resolve("krkr/data0.kdt"), "slot");
        write(parent.resolve("krkr/nested/system.ksd"), "flags");
        Path other = files.toPath().resolve("saves/other/krkr");
        Files.createDirectories(other);
        write(other.resolve("data0.kdt"), "OTHER");
        KrkrSaveStore store = new KrkrSaveStore(files, "game");
        try (KrkrSaveStore.Lease lease = store.acquire()) {
            assertEquals(2, lease.list().size());
            assertEquals(9, lease.bytes());
            KrkrSaveStore.Snapshot snapshot = lease.snapshot();
            snapshot.files().get("data0.kdt")[0] = '!';
            lease.clear();
            assertTrue(lease.list().isEmpty());
            lease.restore(snapshot);
            assertEquals("slot", read(parent.resolve("krkr/data0.kdt")));
            assertEquals("flags", read(parent.resolve("krkr/nested/system.ksd")));
        }
        assertEquals("ONS", read(parent.resolve("ons.dat")));
        assertEquals("OTHER", read(other.resolve("data0.kdt")));
    }

    @Test public void rejectsBusyPathsAndWrongGameBeforeReplacingData() throws Exception {
        File files = temporary.newFolder("files");
        KrkrSaveStore store = new KrkrSaveStore(files, "game");
        try (KrkrSaveStore.Lease lease = store.acquire()) {
            lease.restore(new KrkrSaveStore.Snapshot("game", Map.of("data0.kdt", bytes("GOOD"))));
            assertThrows(IOException.class, store::acquire);
            assertThrows(IOException.class, () -> lease.restore(new KrkrSaveStore.Snapshot("other", Map.of())));
            assertThrows(IOException.class, () -> new KrkrSaveStore.Snapshot("game", Map.of("../outside", bytes("BAD"))));
            assertThrows(IOException.class, () -> new KrkrSaveStore.Snapshot("game", Map.of("nested\\outside", bytes("BAD"))));
            assertThrows(IOException.class, () -> lease.restore(new KrkrSaveStore.Snapshot("game",
                Map.of("file", bytes("A"), "file/child", bytes("B")))));
            assertEquals("GOOD", new String(lease.snapshot().files().get("data0.kdt"), StandardCharsets.UTF_8));
            assertEquals(1, lease.list().size());
        }
        assertThrows(IllegalArgumentException.class, () -> new KrkrSaveStore(files, "../game"));
    }

    @Test public void recoversInterruptedRestoreWithoutLosingEitherCommittedTree() throws Exception {
        File files = temporary.newFolder("files");
        Path parent = files.toPath().resolve("saves/game");
        Files.createDirectories(parent.resolve(".krkr-previous"));
        write(parent.resolve(".krkr-previous/data0.kdt"), "OLD");
        Files.createDirectories(parent.resolve(".krkr-restore-test"));
        write(parent.resolve(".krkr-restore-test/data0.kdt"), "NEW");
        write(parent.resolve(".krkr-restore.pending"), ".krkr-restore-test");
        KrkrSaveStore store = new KrkrSaveStore(files, "game");
        try (KrkrSaveStore.Lease lease = store.acquire()) {
            assertEquals("OLD", new String(lease.snapshot().files().get("data0.kdt"), StandardCharsets.UTF_8));
        }
        assertFalse(Files.exists(parent.resolve(".krkr-restore.pending")));
        Files.move(parent.resolve("krkr"), parent.resolve(".krkr-previous"));
        Files.createDirectories(parent.resolve("krkr"));
        write(parent.resolve("krkr/data0.kdt"), "NEW");
        write(parent.resolve(".krkr-restore.pending"), ".krkr-restore-test");
        try (KrkrSaveStore.Lease lease = store.acquire()) {
            assertEquals("NEW", new String(lease.snapshot().files().get("data0.kdt"), StandardCharsets.UTF_8));
        }
        assertFalse(Files.exists(parent.resolve(".krkr-previous")));
        // Death during marker staging must leave the committed root usable.
        write(parent.resolve(".krkr-marker.tmp"), "partial");
        Files.createDirectories(parent.resolve(".krkr-restore-orphan"));
        write(parent.resolve(".krkr-restore-orphan/data0.kdt"), "UNCOMMITTED");
        try (KrkrSaveStore.Lease lease = store.acquire()) {
            assertEquals("NEW", new String(lease.snapshot().files().get("data0.kdt"), StandardCharsets.UTF_8));
        }
        assertFalse(Files.exists(parent.resolve(".krkr-marker.tmp")));
        assertFalse(Files.exists(parent.resolve(".krkr-restore-orphan")));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test public void boundsSnapshotDirectoriesAndRejectsUseAfterClosing() throws Exception {
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 2049; i++) files.put("directory" + i + "/slot", bytes("A"));
        assertThrows(IOException.class, () -> new KrkrSaveStore.Snapshot("game", files));
        assertThrows(IOException.class, () -> new KrkrSaveStore.Snapshot("game",
            Map.of(".tq-1-2", bytes("A"))));
        KrkrSaveStore.Lease lease = new KrkrSaveStore(temporary.newFolder("files"), "game").acquire();
        lease.close();
        assertThrows(IOException.class, lease::list);
        assertThrows(IOException.class, lease::clear);
    }
    private static void write(Path path, String value) throws IOException { Files.write(path, bytes(value)); }
    private static String read(Path path) throws IOException { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
}

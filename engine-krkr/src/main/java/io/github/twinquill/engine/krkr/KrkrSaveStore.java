/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.engine.krkr;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline Krkr save access; acquire a lease before querying or changing files. */
public final class KrkrSaveStore {
    public static final int FORMAT_VERSION = 1;
    public static final long MAX_FILE_BYTES = 32L * 1024 * 1024;
    public static final long MAX_SNAPSHOT_BYTES = 128L * 1024 * 1024;
    public static final int MAX_FILES = 4096;
    private final String gameId;
    private final Path parent;
    private static final java.util.Set<String> OWNERS = new java.util.HashSet<>();
    static synchronized boolean reserve(String path) { return path.isEmpty() || OWNERS.add(path); }
    static synchronized void release(String path) { if (!path.isEmpty()) OWNERS.remove(path); }

    public KrkrSaveStore(File appFilesDirectory, String gameId) throws IOException {
        validateId(gameId);
        this.gameId = gameId;
        File directory = new File(appFilesDirectory.getCanonicalFile(), "saves/" + gameId).getAbsoluteFile();
        if (!directory.equals(directory.getCanonicalFile())) throw new IOException("Save path contains a symbolic link");
        parent = directory.toPath();
    }

    public Lease acquire() throws IOException {
        Files.createDirectories(parent);
        if (!parent.toFile().getCanonicalFile().equals(parent.toFile())) throw new IOException("Save path changed");
        if (!reserve(parent.toString())) throw new IOException("Krkr saves are in use");
        FileChannel channel = null;
        FileLock lock = null;
        try {
            channel = FileChannel.open(parent.resolve(".krkr-session.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            try { lock = channel.tryLock(0, 1, false); }
            catch (OverlappingFileLockException busy) { throw new IOException("Krkr saves are in use", busy); }
            if (lock == null) throw new IOException("Krkr saves are in use");
            Lease lease = new Lease(channel, lock);
            lease.recover();
            lease.cleanStaging();
            return lease;
        } catch (IOException | RuntimeException error) {
            try { if (lock != null) lock.release(); }
            finally { try { if (channel != null) channel.close(); } finally { release(parent.toString()); } }
            throw error;
        }
    }

    public static final class Entry {
        public final String path;
        public final long bytes;
        private Entry(String path, long bytes) { this.path = path; this.bytes = bytes; }
    }

    /** An engine-specific file set; M7 supplies its external manifest/checksum format. */
    public static final class Snapshot {
        public final String gameId;
        public final int formatVersion = FORMAT_VERSION;
        private final Map<String, byte[]> files;
        public Snapshot(String gameId, Map<String, byte[]> files) throws IOException {
            validateId(gameId);
            this.gameId = gameId;
            if (files.size() > MAX_FILES) throw new IOException("Too many save files");
            Map<String, byte[]> copy = new LinkedHashMap<>();
            java.util.Set<String> directories = new java.util.HashSet<>();
            long total = 0;
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                validateName(entry.getKey());
                byte[] data = entry.getValue();
                if (data.length > MAX_FILE_BYTES || (total += data.length) > MAX_SNAPSHOT_BYTES)
                    throw new IOException("Save snapshot exceeds its byte budget");
                copy.put(entry.getKey(), data.clone());
                String name = entry.getKey();
                for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1))
                    directories.add(name.substring(0, slash));
            }
            if (copy.size() + directories.size() > MAX_FILES)
                throw new IOException("Save entry budget exceeded");
            for (String directory : directories) if (copy.containsKey(directory))
                throw new IOException("Save file conflicts with a directory");
            this.files = Collections.unmodifiableMap(copy);
        }
        public Map<String, byte[]> files() {
            Map<String, byte[]> copy = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : files.entrySet()) copy.put(entry.getKey(), entry.getValue().clone());
            return Collections.unmodifiableMap(copy);
        }
    }

    public final class Lease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;
        private Lease(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

        public synchronized List<Entry> list() throws IOException {
            requireOpen();
            List<Entry> entries = new ArrayList<>();
            Path root = parent.resolve("krkr");
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) collect(root, root, entries, 0, new int[] {0});
            entries.sort((a, b) -> a.path.compareTo(b.path));
            return Collections.unmodifiableList(entries);
        }
        public synchronized long bytes() throws IOException {
            long total = 0;
            for (Entry entry : list()) total += entry.bytes;
            return total;
        }
        public synchronized Snapshot snapshot() throws IOException {
            Map<String, byte[]> files = new LinkedHashMap<>();
            long total = 0;
            for (Entry entry : list()) {
                if ((total += entry.bytes) > MAX_SNAPSHOT_BYTES) throw new IOException("Save snapshot exceeds its byte budget");
                files.put(entry.path, Files.readAllBytes(parent.resolve("krkr").resolve(entry.path)));
            }
            return new Snapshot(gameId, files);
        }
        public synchronized void restore(Snapshot snapshot) throws IOException {
            requireOpen();
            if (!gameId.equals(snapshot.gameId) || snapshot.formatVersion != FORMAT_VERSION)
                throw new IOException("Save snapshot belongs to another game or version");
            list(); // Validate the existing tree before replacing it or removing it.
            Path stage = Files.createTempDirectory(parent, ".krkr-restore-");
            Path marker = parent.resolve(".krkr-restore.pending");
            try {
                for (Map.Entry<String, byte[]> entry : snapshot.files.entrySet()) {
                    Path destination = stage.resolve(entry.getKey());
                    Files.createDirectories(destination.getParent());
                    writeDurably(destination, entry.getValue());
                }
                // No destructive step precedes complete staging and a durable recovery marker.
                Path markerTemporary = parent.resolve(".krkr-marker.tmp");
                writeDurably(markerTemporary, stage.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                Files.move(markerTemporary, marker, StandardCopyOption.ATOMIC_MOVE);
                Path current = parent.resolve("krkr");
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS))
                    Files.move(current, parent.resolve(".krkr-previous"), StandardCopyOption.ATOMIC_MOVE);
                Files.move(stage, current, StandardCopyOption.ATOMIC_MOVE);
                recover();
            } catch (IOException | RuntimeException error) {
                try { recover(); } catch (IOException recoveryError) { error.addSuppressed(recoveryError); }
                throw error;
            } finally {
                if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    removeTree(stage);
                    Files.deleteIfExists(parent.resolve(".krkr-marker.tmp"));
                }
            }
        }
        public synchronized void clear() throws IOException { restore(new Snapshot(gameId, Collections.emptyMap())); }
        private void requireOpen() throws IOException { if (closed) throw new IOException("Save lease is closed"); }
        private void cleanStaging() throws IOException {
            Files.deleteIfExists(parent.resolve(".krkr-marker.tmp"));
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(parent)) {
                for (Path child : children) if (child.getFileName().toString().matches("\\.krkr-restore-[A-Za-z0-9.-]+"))
                    removeTree(child);
            }
        }
        private void recover() throws IOException {
            Path marker = parent.resolve(".krkr-restore.pending");
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) return;
            BasicFileAttributes attributes = Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() < 1 || attributes.size() > 128)
                throw new IOException("Invalid save recovery marker");
            String name = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8);
            if (!name.matches("\\.krkr-restore-[A-Za-z0-9.-]+")) throw new IOException("Invalid save staging name");
            Path current = parent.resolve("krkr"), previous = parent.resolve(".krkr-previous"), stage = parent.resolve(name);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                Path candidate = Files.exists(previous, LinkOption.NOFOLLOW_LINKS) ? previous : stage;
                if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Save recovery data is missing");
                Files.move(candidate, current, StandardCopyOption.ATOMIC_MOVE);
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid save root");
            removeTree(previous);
            removeTree(stage);
            Files.delete(marker);
        }
        @Override public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            try { lock.release(); }
            finally { try { channel.close(); } finally { release(parent.toString()); } }
        }
    }

    private static void collect(Path root, Path directory, List<Entry> entries, int depth, int[] visited) throws IOException {
        if (depth > 16 || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid save directory");
        try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (++visited[0] > MAX_FILES) throw new IOException("Save entry budget exceeded");
                BasicFileAttributes attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isDirectory()) collect(root, child, entries, depth + 1, visited);
                else if (attributes.isRegularFile()) {
                    String name = root.relativize(child).toString().replace(File.separatorChar, '/');
                    if (child.getFileName().toString().matches("\\.tq-[0-9]+-[0-9]+")) continue;
                    validateName(name);
                    if (attributes.size() > MAX_FILE_BYTES || entries.size() >= MAX_FILES) throw new IOException("Save file budget exceeded");
                    entries.add(new Entry(name, attributes.size()));
                } else throw new IOException("Save tree contains a symbolic link or unsupported entry");
            }
        }
    }
    private static void writeDurably(Path file, byte[] data) throws IOException {
        try (FileChannel output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
    }
    private static void removeTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) removeTree(child);
            }
        }
        Files.delete(path);
    }
    private static void validateId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("Invalid game ID");
    }
    private static void validateName(String name) throws IOException {
        if (name == null || name.length() > 1024 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('>') >= 0
                || name.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(name))
            throw new IOException("Invalid save entry name");
        String[] parts = name.split("/", -1);
        if (parts.length > 17) throw new IOException("Save path exceeds its depth budget");
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..") || part.matches("\\.tq-[0-9]+-[0-9]+"))
            throw new IOException("Invalid save path segment");
    }
}

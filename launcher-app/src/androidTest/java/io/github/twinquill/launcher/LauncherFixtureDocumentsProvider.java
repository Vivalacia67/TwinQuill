/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Adler32;

/** SAF fixture that lives only in the launcher instrumentation APK. */
public final class LauncherFixtureDocumentsProvider extends DocumentsProvider {
    static final String AUTHORITY = "io.github.twinquill.test.documents";
    static final String ROOT_ID = "root";
    static final String ONS_UTF8_ROOT_ID = "ons-utf8";
    static final String ONS_GBK_ROOT_ID = "ons-gbk";
    static final String ONS_SJIS_ROOT_ID = "ons-sjis";
    static final String ONS_SAVE_ROOT_ID = "ons-save";
    static final String ONS_NSA_ROOT_ID = "ons-nsa";
    static final String ONS_SAR_ROOT_ID = "ons-sar";
    static final String ONS_LUA_ROOT_ID = "ons-lua";
    static final String ONS_AUDIO_ROOT_ID = "ons-audio";
    static final String ONS_VIDEO_ROOT_ID = "ons-video";
    static final String KRKR_ROOT_ID = "krkr";
    static final String KRKR_MISSING_ROOT_ID = "krkr-missing";
    static final String KRKR_REVOKED_ROOT_ID = "krkr-revoked";
    static final String KRKR_AMBIGUOUS_ROOT_ID = "krkr-ambiguous";
    static final String KRKR_UTF8_ROOT_ID = "krkr-utf8";
    static final String KRKR_VISUAL_ROOT_ID = "krkr-visual";
    static final String KRKR_UTF8_BOM_ROOT_ID = "krkr-utf8-bom";
    static final String KRKR_UTF16_LE_ROOT_ID = "krkr-utf16-le";
    static final String KRKR_UTF16_BE_ROOT_ID = "krkr-utf16-be";
    static final String KRKR_SCRIPT_ERROR_ROOT_ID = "krkr-script-error";
    static final String KRKR_SYNTAX_ERROR_ROOT_ID = "krkr-syntax-error";
    static final String KRKR_ENCODING_ERROR_ROOT_ID = "krkr-encoding-error";

    // These scripts deliberately omit the M0 sentinel; their assertions prove
    // actual execution of functions and decoded Unicode literals.
    static final String KRKR_UNICODE_SCRIPT =
        "var greeting = \"中文\";\n"
            + "function add(a, b) { return a + b; }\n"
            + "if (greeting != \"\\x4e2d\\x6587\" || add(2, 5) != 7) "
            + "throw new Exception(\"Unicode execution failed\");\n"
            + "global.twinQuillM1Value = add(2, 5);\n";

    private static final byte[] UTF8_SCRIPT = script(
        "UTF-8 中文測試",
        StandardCharsets.UTF_8
    );
    private static final byte[] GBK_SCRIPT = script(
        "GBK 简体中文测试",
        Charset.forName("GBK")
    );
    private static final byte[] SJIS_SCRIPT = script(
        "Shift-JIS 日本語テスト",
        Charset.forName("Shift_JIS")
    );
    private static final byte[] SAVE_SCRIPT = (
        "*define\n"
            + "game\n"
            + "*start\n"
            + "savefileexist %0,1\n"
            + "if %0=1 goto *restore\n"
            + "mov %1,123\n"
            + "savegame 1\n"
            + "savefileexist %2,2\n"
            + "if %2=0 end\n"
            + "if %1=123 savegame 3\n"
            + "end\n"
            + "*restore\n"
            + "savegame 2\n"
            + "mov %1,999\n"
            + "loadgame 1\n"
            + "end\n"
    ).getBytes(StandardCharsets.UTF_8);
    private static final byte[] NSA_SCRIPT = archiveScript("nsa");
    private static final byte[] SAR_SCRIPT = archiveScript("sar");
    private static final byte[] LUA_SCRIPT = (
        "*define\n"
            + "luasub luaproof\n"
            + "game\n"
            + "*start\n"
            + "luaproof\n"
            + "if %10=321 savegame 5\n"
            + "end\n"
    ).getBytes(StandardCharsets.UTF_8);
    private static final byte[] LUA_SYSTEM_SCRIPT = (
        "function NSCOM_luaproof()\n"
            + "  NSSetIntValue(10, 321)\n"
            + "end\n"
    ).getBytes(StandardCharsets.UTF_8);
    private static final byte[] AUDIO_SCRIPT = (
        "*define\n"
            + "game\n"
            + "*start\n"
            + "fileexist %0,\"tone.wav\"\n"
            + "if %0=0 end\n"
            + "dwave 0,\"tone.wav\"\n"
            + "delay 250\n"
            + "dwavestop 0\n"
            + "savegame 6\n"
            + "end\n"
    ).getBytes(StandardCharsets.UTF_8);
    private static final byte[] KRKR_SCRIPT =
        "global.twinQuillM0Result = 42;".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] VIDEO_SCRIPT = (
        "*define\n"
            + "game\n"
            + "*start\n"
            + "fileexist %0,\"clip.mp4\"\n"
            + "if %0=0 end\n"
            + "movie \"clip.mp4\"\n"
            + "savegame 7\n"
            + "end\n"
    ).getBytes(StandardCharsets.UTF_8);
    private static final byte[] AUDIO_TONE = toneWave();
    private static final byte[] BITMAP = bitmap();
    private static final byte[] NSA_ARCHIVE = archive(BITMAP, true);
    private static final byte[] SAR_ARCHIVE = archive(BITMAP, false);
    private static final AtomicInteger NSA_ARCHIVE_OPENS = new AtomicInteger();
    private static final AtomicInteger SAR_ARCHIVE_OPENS = new AtomicInteger();
    private static final AtomicInteger AUDIO_OPENS = new AtomicInteger();
    private static final AtomicInteger VIDEO_OPENS = new AtomicInteger();
    private static final AtomicInteger VISUAL_IMAGE_OPENS = new AtomicInteger();
    private static volatile Context fixtureContext;
    private static final String[] DOCUMENT_PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS
    };
    private static final String[] ROOT_PROJECTION = {
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
    };

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        fixtureContext = context.getApplicationContext();
        return true;
    }

    static Uri treeUri() {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID);
    }

    static Uri treeUri(String rootId) {
        if (!isKnownRoot(rootId)) {
            throw new IllegalArgumentException("Unknown fixture root");
        }
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, rootId);
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor result = new MatrixCursor(
            projection == null ? ROOT_PROJECTION : projection
        );
        MatrixCursor.RowBuilder row = result.newRow();
        for (String column : result.getColumnNames()) {
            switch (column) {
                case DocumentsContract.Root.COLUMN_ROOT_ID:
                case DocumentsContract.Root.COLUMN_DOCUMENT_ID:
                    row.add(ROOT_ID);
                    break;
                case DocumentsContract.Root.COLUMN_TITLE:
                    row.add("TwinQuill launcher fixtures");
                    break;
                case DocumentsContract.Root.COLUMN_FLAGS:
                    row.add(0);
                    break;
                case DocumentsContract.Root.COLUMN_AVAILABLE_BYTES:
                    row.add(Long.MAX_VALUE);
                    break;
                default:
                    row.add(null);
                    break;
            }
        }
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
        throws FileNotFoundException {
        MatrixCursor result = documentCursor(projection);
        addDocument(result, documentId);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(
        String parentDocumentId,
        String[] projection,
        String sortOrder
    ) throws FileNotFoundException {
        if (!isKnownRoot(parentDocumentId)) {
            throw new FileNotFoundException(parentDocumentId);
        }
        MatrixCursor result = documentCursor(projection);
        if (!KRKR_MISSING_ROOT_ID.equals(parentDocumentId)) {
            addDocument(result, scriptId(parentDocumentId));
            if (KRKR_AMBIGUOUS_ROOT_ID.equals(parentDocumentId)) {
                addDocument(result, krkrUpperScriptId());
            }
        }
        if (isArchiveRoot(parentDocumentId)) {
            addDocument(result, archiveId(parentDocumentId));
        }
        if (ONS_LUA_ROOT_ID.equals(parentDocumentId)) {
            addDocument(result, luaId(parentDocumentId));
        }
        if (ONS_AUDIO_ROOT_ID.equals(parentDocumentId)) {
            addDocument(result, audioId(parentDocumentId));
        }
        if (ONS_VIDEO_ROOT_ID.equals(parentDocumentId)) {
            addDocument(result, videoId(parentDocumentId));
        }
        if (KRKR_UTF8_ROOT_ID.equals(parentDocumentId)) {
            addDocument(result, "krkr-helper");
            addDocument(result, "krkr-value");
        }
        if (KRKR_VISUAL_ROOT_ID.equals(parentDocumentId)) {
            addDocument(result, "krkr-visual-png");
            addDocument(result, "krkr-visual-jpeg");
        }
        return result;
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        requireKnown(documentId);
        if (isKnownRoot(documentId)) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }
        return documentMimeType(documentId);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return isKnownRoot(parentDocumentId)
            && (
                (KRKR_UTF8_ROOT_ID.equals(parentDocumentId) && isKrkrHelper(documentId))
                    || (KRKR_VISUAL_ROOT_ID.equals(parentDocumentId) && isVisualImage(documentId))
                    ||
                scriptId(parentDocumentId).equals(documentId)
                    || (
                        isArchiveRoot(parentDocumentId)
                            && archiveId(parentDocumentId).equals(documentId)
                    )
                    || (
                        ONS_LUA_ROOT_ID.equals(parentDocumentId)
                            && luaId(parentDocumentId).equals(documentId)
                    )
                    || (
                        ONS_AUDIO_ROOT_ID.equals(parentDocumentId)
                            && audioId(parentDocumentId).equals(documentId)
                    )
                    || (
                        ONS_VIDEO_ROOT_ID.equals(parentDocumentId)
                            && videoId(parentDocumentId).equals(documentId)
                    )
                    || (
                        KRKR_AMBIGUOUS_ROOT_ID.equals(parentDocumentId)
                            && krkrUpperScriptId().equals(documentId)
                    )
            );
    }

    @Override
    public ParcelFileDescriptor openDocument(
        String documentId,
        String mode,
        CancellationSignal signal
    ) throws FileNotFoundException {
        byte[] contents = documentBytes(documentId);
        if (!"r".equals(mode)) {
            throw new UnsupportedOperationException("Fixture provider is read-only");
        }
        String archiveRoot = rootForArchive(documentId);
        if (archiveRoot != null) {
            archiveOpenCounter(archiveRoot).incrementAndGet();
        }
        if (rootForAudio(documentId) != null) {
            AUDIO_OPENS.incrementAndGet();
        }
        if (rootForVideo(documentId) != null) {
            VIDEO_OPENS.incrementAndGet();
        }
        if (isVisualImage(documentId)) VISUAL_IMAGE_OPENS.incrementAndGet();
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(() -> {
                try (
                    ParcelFileDescriptor.AutoCloseOutputStream output =
                        new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                ) {
                    output.write(contents);
                } catch (IOException ignored) {
                    // Closing a reader early is a valid cancellation path.
                }
            }, "TwinQuill-launcher-fixture");
            writer.start();
            return pipe[0];
        } catch (IOException exception) {
            throw new FileNotFoundException(exception.getMessage());
        }
    }

    private static MatrixCursor documentCursor(String[] projection) {
        return new MatrixCursor(
            projection == null ? DOCUMENT_PROJECTION : projection
        );
    }

    private static void addDocument(MatrixCursor cursor, String documentId)
        throws FileNotFoundException {
        requireKnown(documentId);
        boolean root = isKnownRoot(documentId);
        byte[] contents = root ? null : documentBytes(documentId);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            switch (column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                    row.add(documentId);
                    break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                    row.add(
                        root
                            ? rootDisplayName(documentId)
                            : documentDisplayName(documentId)
                    );
                    break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    row.add(
                        root
                            ? DocumentsContract.Document.MIME_TYPE_DIR
                            : documentMimeType(documentId)
                    );
                    break;
                case DocumentsContract.Document.COLUMN_SIZE:
                    row.add(root ? 0L : contents.length);
                    break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED:
                    row.add(0L);
                    break;
                case DocumentsContract.Document.COLUMN_FLAGS:
                    row.add(0);
                    break;
                default:
                    row.add(null);
                    break;
            }
        }
    }

    private static void requireKnown(String documentId) throws FileNotFoundException {
        if (!isKnownRoot(documentId)
            && rootForScript(documentId) == null
            && rootForArchive(documentId) == null
            && rootForLua(documentId) == null
            && rootForAudio(documentId) == null
            && rootForVideo(documentId) == null
            && rootForUpperScript(documentId) == null
            && !isKrkrHelper(documentId) && !isVisualImage(documentId)) {
            throw new FileNotFoundException(documentId);
        }
    }

    private static boolean isKnownRoot(String documentId) {
        return ROOT_ID.equals(documentId)
            || ONS_UTF8_ROOT_ID.equals(documentId)
            || ONS_GBK_ROOT_ID.equals(documentId)
            || ONS_SJIS_ROOT_ID.equals(documentId)
            || ONS_SAVE_ROOT_ID.equals(documentId)
            || ONS_NSA_ROOT_ID.equals(documentId)
            || ONS_SAR_ROOT_ID.equals(documentId)
            || ONS_LUA_ROOT_ID.equals(documentId)
            || ONS_AUDIO_ROOT_ID.equals(documentId)
            || ONS_VIDEO_ROOT_ID.equals(documentId)
            || KRKR_ROOT_ID.equals(documentId)
            || KRKR_MISSING_ROOT_ID.equals(documentId)
            || KRKR_REVOKED_ROOT_ID.equals(documentId)
            || KRKR_AMBIGUOUS_ROOT_ID.equals(documentId)
            || KRKR_UTF8_ROOT_ID.equals(documentId)
            || KRKR_VISUAL_ROOT_ID.equals(documentId)
            || KRKR_UTF8_BOM_ROOT_ID.equals(documentId)
            || KRKR_UTF16_LE_ROOT_ID.equals(documentId)
            || KRKR_UTF16_BE_ROOT_ID.equals(documentId)
            || KRKR_SCRIPT_ERROR_ROOT_ID.equals(documentId)
            || KRKR_SYNTAX_ERROR_ROOT_ID.equals(documentId)
            || KRKR_ENCODING_ERROR_ROOT_ID.equals(documentId);
    }

    private static String scriptId(String rootId) {
        return rootId + "-script";
    }

    private static boolean isKrkrHelper(String id) {
        return "krkr-helper".equals(id) || "krkr-value".equals(id);
    }
    private static boolean isVisualImage(String id) {
        return "krkr-visual-png".equals(id) || "krkr-visual-jpeg".equals(id);
    }

    static void resetVisualImageOpenCount() { VISUAL_IMAGE_OPENS.set(0); }
    static int visualImageOpenCount() { return VISUAL_IMAGE_OPENS.get(); }

    private static byte[] visualAsset(String name) throws FileNotFoundException {
        try (java.io.InputStream source = fixtureContext.getAssets().open("visual/" + name)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        } catch (IOException failure) {
            FileNotFoundException wrapped = new FileNotFoundException("Missing visual fixture: " + name);
            wrapped.initCause(failure);
            throw wrapped;
        }
    }

    private static String krkrUpperScriptId() {
        return KRKR_AMBIGUOUS_ROOT_ID + "-startup-upper";
    }

    private static String archiveId(String rootId) {
        return rootId + "-archive";
    }

    private static String luaId(String rootId) {
        return rootId + "-system-lua";
    }

    private static String audioId(String rootId) {
        return rootId + "-tone";
    }

    private static String videoId(String rootId) {
        return rootId + "-clip";
    }

    private static boolean isArchiveRoot(String rootId) {
        return ONS_NSA_ROOT_ID.equals(rootId) || ONS_SAR_ROOT_ID.equals(rootId);
    }

    private static String rootDisplayName(String rootId) {
        return ROOT_ID.equals(rootId)
            ? "持久化测试"
            : "TwinQuill ONS 测试";
    }

    private static String rootForUpperScript(String documentId) {
        return krkrUpperScriptId().equals(documentId)
            ? KRKR_AMBIGUOUS_ROOT_ID
            : null;
    }

    private static String rootForScript(String documentId) {
        for (String rootId : new String[] {
            ROOT_ID,
            ONS_UTF8_ROOT_ID,
            ONS_GBK_ROOT_ID,
            ONS_SJIS_ROOT_ID,
            ONS_SAVE_ROOT_ID,
            ONS_NSA_ROOT_ID,
            ONS_SAR_ROOT_ID,
            ONS_LUA_ROOT_ID,
            ONS_AUDIO_ROOT_ID,
            ONS_VIDEO_ROOT_ID,
            KRKR_ROOT_ID,
            KRKR_MISSING_ROOT_ID,
            KRKR_REVOKED_ROOT_ID,
            KRKR_AMBIGUOUS_ROOT_ID,
            KRKR_UTF8_ROOT_ID,
            KRKR_VISUAL_ROOT_ID,
            KRKR_UTF8_BOM_ROOT_ID,
            KRKR_UTF16_LE_ROOT_ID,
            KRKR_UTF16_BE_ROOT_ID,
            KRKR_SCRIPT_ERROR_ROOT_ID,
            KRKR_SYNTAX_ERROR_ROOT_ID,
            KRKR_ENCODING_ERROR_ROOT_ID
        }) {
            if (scriptId(rootId).equals(documentId)) {
                return rootId;
            }
        }
        return null;
    }

    private static String rootForLua(String documentId) {
        return luaId(ONS_LUA_ROOT_ID).equals(documentId)
            ? ONS_LUA_ROOT_ID
            : null;
    }

    private static String rootForAudio(String documentId) {
        return audioId(ONS_AUDIO_ROOT_ID).equals(documentId)
            ? ONS_AUDIO_ROOT_ID
            : null;
    }

    private static String rootForVideo(String documentId) {
        return videoId(ONS_VIDEO_ROOT_ID).equals(documentId)
            ? ONS_VIDEO_ROOT_ID
            : null;
    }

    private static String rootForArchive(String documentId) {
        for (String rootId : new String[] {
            ONS_NSA_ROOT_ID,
            ONS_SAR_ROOT_ID
        }) {
            if (archiveId(rootId).equals(documentId)) {
                return rootId;
            }
        }
        return null;
    }

    private static String documentDisplayName(String documentId)
        throws FileNotFoundException {
        if ("krkr-helper".equals(documentId)) return "辅助.tjs";
        if ("krkr-value".equals(documentId)) return "value.tjs";
        if ("krkr-visual-png".equals(documentId)) return "checker.png";
        if ("krkr-visual-jpeg".equals(documentId)) return "sample.jpg";
        String archiveRoot = rootForArchive(documentId);
        if (ONS_NSA_ROOT_ID.equals(archiveRoot)) {
            return "arc.nsa";
        }
        if (ONS_SAR_ROOT_ID.equals(archiveRoot)) {
            return "arc.sar";
        }
        if (rootForLua(documentId) != null) {
            return "system.lua";
        }
        if (rootForAudio(documentId) != null) {
            return "tone.wav";
        }
        if (rootForVideo(documentId) != null) {
            return "clip.mp4";
        }
        if (rootForUpperScript(documentId) != null) {
            return "STARTUP.TJS";
        }
        String scriptRoot = rootForScript(documentId);
        if (KRKR_ROOT_ID.equals(scriptRoot)
            || KRKR_REVOKED_ROOT_ID.equals(scriptRoot)
            || KRKR_AMBIGUOUS_ROOT_ID.equals(scriptRoot)
            || KRKR_UTF8_ROOT_ID.equals(scriptRoot)
            || KRKR_VISUAL_ROOT_ID.equals(scriptRoot)
            || KRKR_UTF8_BOM_ROOT_ID.equals(scriptRoot)
            || KRKR_UTF16_LE_ROOT_ID.equals(scriptRoot)
            || KRKR_UTF16_BE_ROOT_ID.equals(scriptRoot)
            || KRKR_SCRIPT_ERROR_ROOT_ID.equals(scriptRoot)
            || KRKR_SYNTAX_ERROR_ROOT_ID.equals(scriptRoot)
            || KRKR_ENCODING_ERROR_ROOT_ID.equals(scriptRoot)) {
            return "startup.tjs";
        }
        if (scriptRoot != null) {
            return "0.txt";
        }
        throw new FileNotFoundException(documentId);
    }

    private static byte[] documentBytes(String documentId)
        throws FileNotFoundException {
        if ("krkr-helper".equals(documentId)) return "global.fromSaf = \"中文\";".getBytes(StandardCharsets.UTF_8);
        if ("krkr-value".equals(documentId)) return "6*7".getBytes(StandardCharsets.UTF_8);
        if ("krkr-visual-png".equals(documentId)) return visualAsset("checker.png");
        if ("krkr-visual-jpeg".equals(documentId)) return visualAsset("sample.jpg");
        String archiveRoot = rootForArchive(documentId);
        if (ONS_NSA_ROOT_ID.equals(archiveRoot)) {
            return NSA_ARCHIVE;
        }
        if (ONS_SAR_ROOT_ID.equals(archiveRoot)) {
            return SAR_ARCHIVE;
        }
        if (rootForLua(documentId) != null) {
            return LUA_SYSTEM_SCRIPT;
        }
        if (rootForAudio(documentId) != null) {
            return AUDIO_TONE;
        }
        if (rootForVideo(documentId) != null) {
            try {
                Context context = fixtureContext;
                if (context == null) {
                    throw new IOException("Fixture provider is not initialized");
                }
                return LauncherVideoFixture.bytes(context);
            } catch (IOException exception) {
                FileNotFoundException failure =
                    new FileNotFoundException(exception.getMessage());
                failure.initCause(exception);
                throw failure;
            }
        }
        if (rootForUpperScript(documentId) != null) {
            return KRKR_SCRIPT;
        }
        return scriptBytes(documentId);
    }

    private static byte[] scriptBytes(String documentId)
        throws FileNotFoundException {
        String rootId = rootForScript(documentId);
        if (rootId == null) {
            throw new FileNotFoundException(documentId);
        }
        switch (rootId) {
            case KRKR_VISUAL_ROOT_ID:
                return (new String(visualAsset("startup.tjs"), StandardCharsets.UTF_8)
                    + "\nvar exitTicks=0; var exitTimer=new Timer(function(){"
                    + "if(++exitTicks==4) window.close();},''); exitTimer.interval=300; exitTimer.enabled=true;\n")
                    .getBytes(StandardCharsets.UTF_8);
            case ONS_GBK_ROOT_ID:
                return GBK_SCRIPT;
            case ONS_SJIS_ROOT_ID:
                return SJIS_SCRIPT;
            case ONS_SAVE_ROOT_ID:
                return SAVE_SCRIPT;
            case ONS_NSA_ROOT_ID:
                return NSA_SCRIPT;
            case ONS_SAR_ROOT_ID:
                return SAR_SCRIPT;
            case ONS_LUA_ROOT_ID:
                return LUA_SCRIPT;
            case ONS_AUDIO_ROOT_ID:
                return AUDIO_SCRIPT;
            case ONS_VIDEO_ROOT_ID:
                return VIDEO_SCRIPT;
            case KRKR_ROOT_ID:
            case KRKR_REVOKED_ROOT_ID:
            case KRKR_AMBIGUOUS_ROOT_ID:
                return KRKR_SCRIPT;
            case KRKR_UTF8_ROOT_ID:
                return (KRKR_UNICODE_SCRIPT
                    + "Scripts.execStorage(\"辅助.tjs\");\n"
                    + "if (fromSaf != \"\\x4e2d\\x6587\" || !Storages.isExistentStorage(\"辅助.tjs\") "
                    + "|| Storages.isExistentStorage(\"missing.tjs\") || Scripts.evalStorage(\"value.tjs\") != 42) "
                    + "throw new Exception(\"SAF native classes\"); Debug.message(fromSaf);\n"
                    + "TwinQuillHost.onKey = function(down,key,unicode,meta,repeat,time) { "
                    + "if (down && key == 66) { Scripts.execStorage(\"辅助.tjs\"); "
                    + "if (fromSaf != \"\\x4e2d\\x6587\") throw new Exception(\"SAF callback read\"); "
                    + "System.exit(); } };\n")
                    .getBytes(StandardCharsets.UTF_8);
            case KRKR_UTF8_BOM_ROOT_ID:
                return ("\ufeff" + KRKR_UNICODE_SCRIPT).getBytes(StandardCharsets.UTF_8);
            case KRKR_UTF16_LE_ROOT_ID:
                return ("\ufeff" + KRKR_UNICODE_SCRIPT).getBytes(StandardCharsets.UTF_16LE);
            case KRKR_UTF16_BE_ROOT_ID:
                return ("\ufeff" + KRKR_UNICODE_SCRIPT).getBytes(StandardCharsets.UTF_16BE);
            case KRKR_SCRIPT_ERROR_ROOT_ID:
                return "throw new Exception(\"脚本错误\");".getBytes(StandardCharsets.UTF_8);
            case KRKR_SYNTAX_ERROR_ROOT_ID:
                return "var value = ;".getBytes(StandardCharsets.US_ASCII);
            case KRKR_ENCODING_ERROR_ROOT_ID:
                return new byte[] {(byte) 0xe4, (byte) 0xb8};
            case ROOT_ID:
            case ONS_UTF8_ROOT_ID:
                return UTF8_SCRIPT;
            default:
                throw new FileNotFoundException(documentId);
        }
    }

    private static byte[] script(String caption, Charset encoding) {
        return (
            "*define\n"
                + "game\n"
                + "*start\n"
                + "caption \"" + caption + "\"\n"
                + "end\n"
        ).getBytes(encoding);
    }

    static void resetArchiveOpenCount(String rootId) {
        archiveOpenCounter(rootId).set(0);
    }

    static int archiveOpenCount(String rootId) {
        return archiveOpenCounter(rootId).get();
    }

    static void resetAudioOpenCount() {
        AUDIO_OPENS.set(0);
    }

    static int audioOpenCount() {
        return AUDIO_OPENS.get();
    }

    static void resetVideoOpenCount() {
        VIDEO_OPENS.set(0);
    }

    static int videoOpenCount() {
        return VIDEO_OPENS.get();
    }

    private static String documentMimeType(String documentId) {
        if (rootForArchive(documentId) != null) {
            return "application/octet-stream";
        }
        if (rootForAudio(documentId) != null) {
            return "audio/wav";
        }
        if (rootForVideo(documentId) != null) {
            return "video/mp4";
        }
        return "text/plain";
    }

    private static AtomicInteger archiveOpenCounter(String rootId) {
        if (ONS_NSA_ROOT_ID.equals(rootId)) {
            return NSA_ARCHIVE_OPENS;
        }
        if (ONS_SAR_ROOT_ID.equals(rootId)) {
            return SAR_ARCHIVE_OPENS;
        }
        throw new IllegalArgumentException("Not an archive fixture root");
    }

    private static byte[] archiveScript(String command) {
        return (
            "*define\n"
                + command + "\n"
                + "game\n"
                + "*start\n"
                + "bg \"fixture.bmp\",1\n"
                + "fileexist %0,\"fixture.bmp\"\n"
                + "if %0=1 savegame 4\n"
                + "end\n"
        ).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] archive(byte[] contents, boolean nsa) {
        byte[] name = "fixture.bmp".getBytes(StandardCharsets.US_ASCII);
        int entryHeaderLength = name.length + 1 + (nsa ? 13 : 8);
        int baseOffset = 6 + entryHeaderLength;
        ByteArrayOutputStream output =
            new ByteArrayOutputStream(baseOffset + contents.length);
        writeBigEndianShort(output, 1);
        writeBigEndianInt(output, baseOffset);
        output.write(name, 0, name.length);
        output.write(0);
        if (nsa) {
            output.write(0);
        }
        writeBigEndianInt(output, 0);
        writeBigEndianInt(output, contents.length);
        if (nsa) {
            writeBigEndianInt(output, contents.length);
        }
        output.write(contents, 0, contents.length);
        return output.toByteArray();
    }

    private static byte[] bitmap() {
        ByteArrayOutputStream output = new ByteArrayOutputStream(58);
        output.write('B');
        output.write('M');
        writeLittleEndianInt(output, 58);
        writeLittleEndianShort(output, 0);
        writeLittleEndianShort(output, 0);
        writeLittleEndianInt(output, 54);
        writeLittleEndianInt(output, 40);
        writeLittleEndianInt(output, 1);
        writeLittleEndianInt(output, 1);
        writeLittleEndianShort(output, 1);
        writeLittleEndianShort(output, 24);
        writeLittleEndianInt(output, 0);
        writeLittleEndianInt(output, 4);
        writeLittleEndianInt(output, 2_835);
        writeLittleEndianInt(output, 2_835);
        writeLittleEndianInt(output, 0);
        writeLittleEndianInt(output, 0);
        output.write(0x20);
        output.write(0x80);
        output.write(0xff);
        output.write(0);
        return output.toByteArray();
    }

    private static byte[] toneWave() {
        int sampleRate = 8_000;
        int sampleCount = sampleRate / 5;
        ByteArrayOutputStream output =
            new ByteArrayOutputStream(44 + sampleCount);
        writeAscii(output, "RIFF");
        writeLittleEndianInt(output, 36 + sampleCount);
        writeAscii(output, "WAVE");
        writeAscii(output, "fmt ");
        writeLittleEndianInt(output, 16);
        writeLittleEndianShort(output, 1);
        writeLittleEndianShort(output, 1);
        writeLittleEndianInt(output, sampleRate);
        writeLittleEndianInt(output, sampleRate);
        writeLittleEndianShort(output, 1);
        writeLittleEndianShort(output, 8);
        writeAscii(output, "data");
        writeLittleEndianInt(output, sampleCount);
        for (int sample = 0; sample < sampleCount; sample++) {
            double phase = 2.0 * Math.PI * 440.0 * sample / sampleRate;
            output.write((int) Math.round(128.0 + 48.0 * Math.sin(phase)));
        }
        return output.toByteArray();
    }

    static byte[] rawKrkrXp3(byte[] script) {
        byte[] name = "startup.tjs".getBytes(StandardCharsets.UTF_16LE);
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        writeLittleEndianInt(info, 0);
        writeLittleEndianLong(info, script.length);
        writeLittleEndianLong(info, script.length);
        writeLittleEndianShort(info, name.length / 2);
        info.write(name, 0, name.length);

        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        writeLittleEndianInt(segment, 0);
        writeLittleEndianLong(segment, 19);
        writeLittleEndianLong(segment, script.length);
        writeLittleEndianLong(segment, script.length);

        Adler32 checksum = new Adler32();
        checksum.update(script);
        ByteArrayOutputStream adler = new ByteArrayOutputStream();
        writeLittleEndianInt(adler, (int) checksum.getValue());
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        writeXp3Chunk(file, "info", info.toByteArray());
        writeXp3Chunk(file, "segm", segment.toByteArray());
        writeXp3Chunk(file, "adlr", adler.toByteArray());
        ByteArrayOutputStream index = new ByteArrayOutputStream();
        writeXp3Chunk(index, "File", file.toByteArray());

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        byte[] magic = {0x58, 0x50, 0x33, 0x0d, 0x0a, 0x20, 0x0a, 0x1a,
            (byte) 0x8b, 0x67, 0x01};
        archive.write(magic, 0, magic.length);
        writeLittleEndianLong(archive, 19L + script.length);
        archive.write(script, 0, script.length);
        archive.write(0); // Raw, final index block.
        writeLittleEndianLong(archive, index.size());
        byte[] indexBytes = index.toByteArray();
        archive.write(indexBytes, 0, indexBytes.length);
        return archive.toByteArray();
    }

    private static void writeXp3Chunk(ByteArrayOutputStream output, String tag, byte[] data) {
        writeAscii(output, tag);
        writeLittleEndianLong(output, data.length);
        output.write(data, 0, data.length);
    }

    private static void writeLittleEndianLong(ByteArrayOutputStream output, long value) {
        for (int index = 0; index < 8; ++index) {
            output.write((int) ((value >>> (index * 8)) & 0xff));
        }
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.write(bytes, 0, bytes.length);
    }

    private static void writeBigEndianShort(
        ByteArrayOutputStream output,
        int value
    ) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeBigEndianInt(
        ByteArrayOutputStream output,
        int value
    ) {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeLittleEndianShort(
        ByteArrayOutputStream output,
        int value
    ) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeLittleEndianInt(
        ByteArrayOutputStream output,
        int value
    ) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }
}

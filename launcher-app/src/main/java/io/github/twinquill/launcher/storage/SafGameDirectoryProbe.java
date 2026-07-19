/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
package io.github.twinquill.launcher.storage;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.twinquill.engine.api.GameDirectoryProbe;

/** Read-only detector view backed by a persisted Storage Access Framework tree. */
public final class SafGameDirectoryProbe implements GameDirectoryProbe {
    private static final String[] CHILD_PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    };

    private final ContentResolver resolver;
    private final Uri rootUri;
    private final Map<String, Uri> rootFiles = new HashMap<>();
    private boolean loaded;

    public SafGameDirectoryProbe(ContentResolver resolver, Uri rootUri) {
        this.resolver = resolver;
        this.rootUri = rootUri;
    }

    @Override
    public List<String> rootFileNames() throws IOException {
        loadChildren();
        return List.copyOf(rootFiles.keySet());
    }

    @Override
    public boolean archiveContainsRootStartup(String archiveName) throws IOException {
        loadChildren();
        Uri archive = rootFiles.get(archiveName);
        return archive != null && Xp3ArchiveProbe.containsRootStartup(resolver, archive);
    }

    public String displayName() throws IOException {
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(
            rootUri,
            DocumentsContract.getTreeDocumentId(rootUri)
        );
        try (Cursor cursor = resolver.query(
            documentUri,
            new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME},
            null,
            null,
            null
        )) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new FileNotFoundException("Selected directory is unavailable");
            }
            String name = cursor.getString(0);
            return name == null || name.isBlank() ? "Untitled game" : name;
        } catch (SecurityException exception) {
            throw new FileNotFoundException("Directory permission was revoked");
        }
    }

    private void loadChildren() throws IOException {
        if (loaded) {
            return;
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            rootUri,
            DocumentsContract.getTreeDocumentId(rootUri)
        );
        try (Cursor cursor = resolver.query(
            childrenUri,
            CHILD_PROJECTION,
            null,
            null,
            null
        )) {
            if (cursor == null) {
                throw new FileNotFoundException("Provider returned no directory cursor");
            }
            List<String> names = new ArrayList<>();
            while (cursor.moveToNext()) {
                String documentId = cursor.getString(0);
                String name = cursor.getString(1);
                String mimeType = cursor.getString(2);
                if (documentId != null && name != null
                    && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    Uri child = DocumentsContract.buildDocumentUriUsingTree(
                        rootUri,
                        documentId
                    );
                    rootFiles.putIfAbsent(name, child);
                    names.add(name);
                }
            }
            loaded = true;
        } catch (SecurityException exception) {
            throw new FileNotFoundException("Directory permission was revoked");
        }
    }
}

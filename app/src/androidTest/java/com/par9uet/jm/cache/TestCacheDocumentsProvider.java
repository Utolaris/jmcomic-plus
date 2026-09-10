package com.par9uet.jm.cache;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Test APK components run without the target APK's Kotlin runtime. */
public class TestCacheDocumentsProvider extends DocumentsProvider {
    private File root() {
        File root = new File(getContext().getCacheDir(), "documents");
        root.mkdirs();
        return root;
    }
    private File file(String id) { return id.equals("root") ? root() : new File(root(), id.substring(5)); }
    private String id(File file) {
        return file.equals(root()) ? "root" : "root/" + file.getPath().substring(root().getPath().length() + 1);
    }
    @Override public boolean onCreate() { return true; }
    @Override public boolean isChildDocument(String parent, String child) { return child.startsWith(parent + "/"); }
    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(projection == null ? new String[0] : projection); }
    private MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection == null ? new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE
        } : projection);
    }
    private void row(MatrixCursor cursor, File file) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            switch (column) {
                case Document.COLUMN_DOCUMENT_ID: value = id(file); break;
                case Document.COLUMN_DISPLAY_NAME: value = file.getName(); break;
                case Document.COLUMN_MIME_TYPE: value = file.isDirectory() ? Document.MIME_TYPE_DIR : "application/octet-stream"; break;
                // Exercise providers that omit COLUMN_SIZE for metadata files.
                case Document.COLUMN_SIZE: value = "cover.webp".equals(file.getName()) ? null : file.length(); break;
                case Document.COLUMN_FLAGS: value = Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_DIR_SUPPORTS_CREATE; break;
            }
            row.add(value);
        }
    }
    @Override public Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = cursor(projection);
        File file = file(id);
        if (file.exists()) row(cursor, file);
        return cursor;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sortOrder) {
        MatrixCursor cursor = cursor(projection);
        File[] children = file(id).listFiles();
        if (children != null) for (File child : children) row(cursor, child);
        return cursor;
    }
    @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
        File file = new File(file(parent), name);
        try {
            if (Document.MIME_TYPE_DIR.equals(mime)) file.mkdirs(); else file.createNewFile();
        } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
        return id(file);
    }
    private void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        if (!file.delete()) throw new IllegalStateException("Cannot delete " + file);
    }
    @Override public void deleteDocument(String id) { delete(file(id)); }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.parseMode(mode));
    }
}

package de.two4app.downloader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.OpenableColumns;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only content provider for APKs downloaded into this app's private external directory. */
public final class ApkFileProvider extends ContentProvider {
    static final String AUTHORITY = "de.two4app.downloader.files";

    static Uri uriFor(String filename) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("apk").appendPath(filename).build();
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) || uri.getPathSegments().size() != 2 ||
                !"apk".equals(uri.getPathSegments().get(0))) {
            throw new FileNotFoundException("Unsupported path or mode");
        }
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,160}\\.apk") ||
                name.contains("..")) throw new FileNotFoundException("Bad filename");
        File directory = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) throw new FileNotFoundException("Storage unavailable");
        File file = new File(directory, name);
        if (!file.isFile()) throw new FileNotFoundException("Not found");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        String filename = uri.getLastPathSegment();
        File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File file = dir == null || filename == null ? null : new File(dir, filename);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = filename;
            else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file == null ? 0 : file.length();
        }
        cursor.addRow(values);
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}

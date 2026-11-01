package com.geminiapp.chat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Отдаёт установщику скачанный APK по адресу content://com.geminiapp.chat.updates/update.apk.
 * Нужен с Android 7: прямой доступ к файлу через file:// запрещён. Ничего другого провайдер не отдаёт.
 * Без androidx: FileProvider из support-библиотеки не используется.
 */
public class UpdateFileProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    private File target(Uri uri) {
        if (!Updater.UPDATE_FILE.equals(uri.getLastPathSegment())) return null;
        File f = getContext().getFileStreamPath(Updater.UPDATE_FILE);
        return f.exists() ? f : null;
    }

    @Override
    public String getType(Uri uri) {
        return Updater.APK_MIME;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f = target(uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection : new String[] {"_display_name", "_size"};
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if ("_display_name".equals(cols[i])) row[i] = Updater.APK_NAME;
            else if ("_size".equals(cols[i])) row[i] = Long.valueOf(f.length());
        }
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(row);
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = target(uri);
        if (f == null) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}

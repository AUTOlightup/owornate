package dev.codex.watchworkshop;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Grants the system installer read access to one verified manager APK, never a directory. */
public final class UpdateApkProvider extends ContentProvider {
    static File apk(Context context) { return new File(context.getFilesDir(), "updates/manager.apk"); }
    static Uri uri(Context context) {
        return Uri.parse("content://" + context.getPackageName() + ".updates/manager.apk");
    }

    static PackageInfo validate(Context context, File file) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo current = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        PackageInfo next = pm.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (next == null) throw new IOException("无法读取下载的 APK");
        UpdatePolicy.requireApk(context.getPackageName(), current.versionCode, signers(current),
                next.packageName, next.versionCode, signers(next));
        return next;
    }

    private static List<String> signers(PackageInfo info) {
        ArrayList<String> values = new ArrayList<>();
        if (info.signatures != null) for (Signature signature : info.signatures) values.add(signature.toCharsString());
        return values;
    }

    private File checked(Uri value) throws FileNotFoundException {
        if (!uri(getContext()).equals(value)) throw new FileNotFoundException("Unknown update file");
        File file = apk(getContext());
        if (!file.isFile()) throw new FileNotFoundException("Update APK missing");
        return file;
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(checked(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File file = checked(uri);
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "OWOrnate-update.apk";
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            cursor.addRow(values);
            return cursor;
        } catch (FileNotFoundException error) { throw new IllegalArgumentException(error); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}

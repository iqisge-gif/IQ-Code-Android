package com.iqge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Locale;

/**
 * Read-only content provider used when IQ Code hands a locally built APK or another file to
 * Android. It intentionally exposes only explicitly supported roots and rejects path traversal.
 */
public final class IqFileProvider extends ContentProvider {
    public static final String AUTHORITY = "com.iqge.fileprovider";

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        File file = resolve(uri, false);
        String name = file == null ? lastSegment(uri) : file.getName();
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        int dot = lower.lastIndexOf('.');
        String extension = dot < 0 ? "" : lower.substring(dot + 1);
        String detected = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return detected == null ? "application/octet-stream" : detected;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        File file = resolve(uri, true);
        String[] columns = projection == null || projection.length == 0
            ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
            : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            else row[i] = null;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode == null || !("r".equals(mode) || "rt".equals(mode))) {
            throw new FileNotFoundException("IQ Code FileProvider is read-only");
        }
        File file = resolve(uri, true);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    /** Convert a supported local path to a grantable content URI. */
    public static Uri uriForFile(Context context, File requested) throws Exception {
        if (context == null || requested == null) throw new IllegalArgumentException("file is required");
        File file = requested.getCanonicalFile();
        if (!file.isFile()) throw new FileNotFoundException("文件不存在：" + file);

        File external = Environment.getExternalStorageDirectory().getCanonicalFile();
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH).getCanonicalFile();
        File cache = context.getCacheDir().getCanonicalFile();
        Uri.Builder out = new Uri.Builder().scheme("content").authority(AUTHORITY);
        if (under(file, external)) {
            out.appendPath("storage").appendPath("shared");
            appendRelative(out, external, file);
        } else if (under(file, home)) {
            out.appendPath("home");
            appendRelative(out, home, file);
        } else if (under(file, cache)) {
            out.appendPath("cache");
            appendRelative(out, cache, file);
        } else {
            throw new SecurityException("只允许分享手机共享存储、IQ Code HOME 或缓存中的文件：" + file);
        }
        return out.build();
    }

    private File resolve(Uri uri, boolean requireFile) {
        try {
            if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())
                || !AUTHORITY.equals(uri.getAuthority())) throw new SecurityException("无效 IQ Code content URI");
            List<String> segments = uri.getPathSegments();
            if (segments == null || segments.isEmpty()) throw new FileNotFoundException("URI 缺少路径");
            Context context = getContext();
            if (context == null) throw new IllegalStateException("Provider context unavailable");

            File root;
            int firstRelative;
            if (segments.size() >= 2 && "storage".equals(segments.get(0)) && "shared".equals(segments.get(1))) {
                root = Environment.getExternalStorageDirectory(); firstRelative = 2;
            } else if ("home".equals(segments.get(0))) {
                root = new File(TermuxConstants.TERMUX_HOME_DIR_PATH); firstRelative = 1;
            } else if ("cache".equals(segments.get(0))) {
                root = context.getCacheDir(); firstRelative = 1;
            } else {
                throw new SecurityException("不允许的共享根目录");
            }

            File canonicalRoot = root.getCanonicalFile();
            File candidate = canonicalRoot;
            for (int i = firstRelative; i < segments.size(); i++) candidate = new File(candidate, segments.get(i));
            candidate = candidate.getCanonicalFile();
            if (!under(candidate, canonicalRoot)) throw new SecurityException("路径越界");
            if (requireFile && !candidate.isFile()) throw new FileNotFoundException("文件不存在：" + candidate);
            return candidate;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private static boolean under(File file, File root) {
        String base = root.getPath();
        String path = file.getPath();
        return path.equals(base) || path.startsWith(base + File.separator);
    }

    private static void appendRelative(Uri.Builder out, File root, File file) {
        String relative = file.getPath().substring(root.getPath().length());
        while (relative.startsWith(File.separator)) relative = relative.substring(1);
        if (relative.isEmpty()) return;
        for (String part : relative.split("/")) if (!part.isEmpty()) out.appendPath(part);
    }

    private static String lastSegment(Uri uri) {
        String value = uri == null ? null : uri.getLastPathSegment();
        return value == null ? "file" : value;
    }
}

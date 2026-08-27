package com.iqge;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/** Read-only project DocumentsProvider discoverable by document-aware file managers. */
public final class IqDocumentsProvider extends DocumentsProvider {
    private static final String ROOT_ID = "iq-projects";
    private static final String ROOT_DOCUMENT_ID = "projects";

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[]{
            DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        } : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID);
        row.add(DocumentsContract.Root.COLUMN_TITLE, "IQ Code 项目");
        row.add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
        row.add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, root().getUsableSpace());
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(documentColumns(projection));
        include(cursor, fileForId(documentId), documentId);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        File parent = fileForId(parentDocumentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("不是目录：" + parentDocumentId);
        MatrixCursor cursor = new MatrixCursor(documentColumns(projection));
        File[] files = parent.listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File file : files) {
                if (file.getName().equals(".iq")) continue;
                try { include(cursor, canonicalUnderRoot(file), parentDocumentId + "/" + file.getName()); }
                catch (FileNotFoundException ignored) { }
            }
        }
        return cursor;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode, android.os.CancellationSignal signal) throws FileNotFoundException {
        if (!"r".equals(mode) && !"rt".equals(mode)) throw new FileNotFoundException("IQ Code 项目仅允许读取");
        File file = fileForId(documentId);
        if (!file.isFile()) throw new FileNotFoundException("不是文件：" + documentId);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            File parent = fileForId(parentDocumentId);
            File child = fileForId(documentId);
            return child.getPath().startsWith(parent.getPath() + File.separator) || child.equals(parent);
        } catch (Exception ignored) { return false; }
    }

    private static String[] documentColumns(String[] projection) {
        return projection == null ? new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
        } : projection;
    }

    private void include(MatrixCursor cursor, File file, String id) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, ROOT_DOCUMENT_ID.equals(id) ? "项目" : file.getName());
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : mime(file));
        row.add(DocumentsContract.Document.COLUMN_FLAGS, 0);
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.isFile() ? file.length() : null);
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
    }

    private static String mime(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1));
        return type == null ? "application/octet-stream" : type;
    }

    private static File root() {
        File projects = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "projects");
        if (!projects.isDirectory()) projects.mkdirs();
        return projects;
    }

    private static File fileForId(String id) throws FileNotFoundException {
        if (id == null || !id.equals(ROOT_DOCUMENT_ID) && !id.startsWith(ROOT_DOCUMENT_ID + "/")) throw new FileNotFoundException("无效文档 ID");
        String relative = id.equals(ROOT_DOCUMENT_ID) ? "" : id.substring(ROOT_DOCUMENT_ID.length() + 1);
        return canonicalUnderRoot(new File(root(), relative));
    }

    private static File canonicalUnderRoot(File candidate) throws FileNotFoundException {
        try {
            File base = root().getCanonicalFile();
            File file = candidate.getCanonicalFile();
            if (!file.equals(base) && !file.getPath().startsWith(base.getPath() + File.separator)) throw new FileNotFoundException("路径越界");
            return file;
        } catch (FileNotFoundException e) { throw e; }
        catch (Exception e) { throw new FileNotFoundException(e.getMessage()); }
    }
}

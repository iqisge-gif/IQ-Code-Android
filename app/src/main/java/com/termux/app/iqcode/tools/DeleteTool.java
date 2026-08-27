package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

public final class DeleteTool implements IQTool {
    @Override public String name() { return "Delete"; }
    @Override public String description() { return "Delete a file or, only when recursive=true, a directory tree. Use carefully."; }
    @Override public PermissionKind permissionKind() { return PermissionKind.WRITE; }

    @Override public JSONObject inputSchema() {
        try {
            return ToolSchemas.object(new JSONObject()
                .put("path", ToolSchemas.string("Path to delete."))
                .put("recursive", ToolSchemas.bool("Required for non-empty directories.")), "path");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        String requestedPath = input.getString("path");
        File file = PathPolicy.resolve(config.projectDirectory, requestedPath);
        File project = new File(config.projectDirectory).getCanonicalFile();
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH).getCanonicalFile();
        if (file.equals(project) || file.equals(home) || file.getAbsolutePath().equals("/")
            || file.getAbsolutePath().equals(TermuxConstants.TERMUX_PREFIX_DIR_PATH)) {
            return ToolExecutionResult.error("Refusing to delete protected root: " + file);
        }
        if (!file.exists()) return ToolExecutionResult.ok("Already absent: " + file);
        boolean recursive = input.optBoolean("recursive", false);
        if (file.isDirectory() && !recursive) {
            String[] children = file.list();
            if (children != null && children.length > 0) return ToolExecutionResult.error("Directory is not empty; set recursive=true explicitly.");
        }

        UnifiedDiff.Result diff = new UnifiedDiff.Result("", 0, 0);
        if (file.isFile() && file.length() <= 5L * 1024L * 1024L) {
            diff = UnifiedDiff.deleted(requestedPath, read(file));
        }
        if (!remove(file, recursive)) return ToolExecutionResult.error("Delete failed: " + file);
        return ToolExecutionResult.okWithDiff("Deleted " + file, diff.text, diff.additions, diff.deletions);
    }

    private static String read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65_536]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private boolean remove(File file, boolean recursive) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null && children.length > 0 && !recursive) return false;
            if (children != null) for (File child : children) if (!remove(child, true)) return false;
        }
        return file.delete();
    }
}

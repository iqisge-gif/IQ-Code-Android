package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class WriteTool implements IQTool {
    @Override public String name() { return "Write"; }
    @Override public String description() { return "Create or overwrite a UTF-8 text file. Parent directories are created automatically."; }
    @Override public PermissionKind permissionKind() { return PermissionKind.WRITE; }

    @Override public JSONObject inputSchema() {
        JSONObject p = new JSONObject();
        try {
            p.put("file_path", ToolSchemas.string("Absolute or project-relative destination path."));
            p.put("content", ToolSchemas.string("Complete UTF-8 file contents."));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return ToolSchemas.object(p, "file_path", "content");
    }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        File file = PathPolicy.resolve(config.projectDirectory, input.optString("file_path", ""));
        boolean existed = file.isFile();
        if (existed && file.length() > 20L * 1024L * 1024L) {
            return ToolExecutionResult.error("Refusing to overwrite a file larger than 20 MiB: " + file);
        }
        String before = existed ? read(file) : "";
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return ToolExecutionResult.error("Could not create parent directory: " + parent);
        }
        String content = input.optString("content", "");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 20L * 1024L * 1024L) return ToolExecutionResult.error("Refusing to write more than 20 MiB.");
        UnifiedDiff.Result diff = UnifiedDiff.create(input.optString("file_path", file.getPath()), before, content, existed);
        try (FileOutputStream out = new FileOutputStream(file, false)) { out.write(bytes); }
        return ToolExecutionResult.okWithDiff("Wrote " + bytes.length + " bytes to " + file.getAbsolutePath(),
            diff.text, diff.additions, diff.deletions);
    }

    private static String read(File file) throws Exception {
        byte[] raw = new byte[(int)file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int pos = 0, n;
            while (pos < raw.length && (n = in.read(raw, pos, raw.length - pos)) > 0) pos += n;
        }
        return new String(raw, StandardCharsets.UTF_8);
    }
}

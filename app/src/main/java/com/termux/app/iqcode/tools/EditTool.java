package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class EditTool implements IQTool {
    @Override public String name() { return "Edit"; }
    @Override public String description() { return "Replace an exact string in a UTF-8 text file. By default the old string must occur exactly once; set replace_all to replace every occurrence."; }
    @Override public PermissionKind permissionKind() { return PermissionKind.WRITE; }

    @Override public JSONObject inputSchema() {
        JSONObject p = new JSONObject();
        try {
            p.put("file_path", ToolSchemas.string("Absolute or project-relative file path."));
            p.put("old_string", ToolSchemas.string("Exact text to replace."));
            p.put("new_string", ToolSchemas.string("Replacement text."));
            p.put("replace_all", ToolSchemas.bool("Replace every occurrence instead of requiring exactly one."));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return ToolSchemas.object(p, "file_path", "old_string", "new_string");
    }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        File file = PathPolicy.resolve(config.projectDirectory, input.optString("file_path", ""));
        if (!file.isFile()) return ToolExecutionResult.error("File does not exist: " + file);
        if (file.length() > 20L * 1024L * 1024L) return ToolExecutionResult.error("Refusing to edit a file larger than 20 MiB.");
        byte[] raw = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int pos = 0, n;
            while (pos < raw.length && (n = in.read(raw, pos, raw.length - pos)) > 0) pos += n;
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        String oldString = input.optString("old_string", "");
        String newString = input.optString("new_string", "");
        if (oldString.isEmpty()) return ToolExecutionResult.error("old_string must not be empty");
        int count = countOccurrences(text, oldString);
        if (count == 0) return ToolExecutionResult.error("old_string was not found in " + file.getAbsolutePath());
        boolean replaceAll = input.optBoolean("replace_all", false);
        if (!replaceAll && count != 1) return ToolExecutionResult.error("old_string occurs " + count + " times; provide more context or set replace_all=true");
        String updated = replaceAll ? text.replace(oldString, newString) : replaceFirstLiteral(text, oldString, newString);
        UnifiedDiff.Result diff = UnifiedDiff.create(input.optString("file_path", file.getPath()), text, updated, true);
        try (FileOutputStream out = new FileOutputStream(file, false)) { out.write(updated.getBytes(StandardCharsets.UTF_8)); }
        return ToolExecutionResult.okWithDiff("Updated " + file.getAbsolutePath() + " (" + (replaceAll ? count : 1) + " replacement(s))",
            diff.text, diff.additions, diff.deletions);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0, from = 0;
        while (true) {
            int i = text.indexOf(needle, from);
            if (i < 0) return count;
            count++;
            from = i + needle.length();
        }
    }

    private static String replaceFirstLiteral(String text, String oldString, String newString) {
        int i = text.indexOf(oldString);
        return text.substring(0, i) + newString + text.substring(i + oldString.length());
    }
}

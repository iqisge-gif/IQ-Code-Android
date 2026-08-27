package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates and composes every edit in memory, then commits each affected file once. */
public final class MultiEditTool implements IQTool {
    @Override public String name() { return "MultiEdit"; }
    @Override public String description() { return "Apply multiple exact old_text -> new_text edits across one or more files atomically after validating every requested match. Useful for coordinated refactors."; }
    @Override public PermissionKind permissionKind() { return PermissionKind.WRITE; }

    @Override public JSONObject inputSchema() {
        try {
            JSONObject editProps = new JSONObject();
            editProps.put("path", ToolSchemas.string("File path."));
            editProps.put("old_text", ToolSchemas.string("Exact text that must exist."));
            editProps.put("new_text", ToolSchemas.string("Replacement text."));
            editProps.put("replace_all", ToolSchemas.bool("Replace every occurrence instead of exactly one."));
            JSONObject editSchema = ToolSchemas.object(editProps, "path", "old_text", "new_text");
            JSONObject p = new JSONObject().put("edits", new JSONObject().put("type", "array").put("minItems", 1).put("items", editSchema));
            return ToolSchemas.object(p, "edits");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        JSONArray edits = input.getJSONArray("edits");
        Map<File, String> originals = new LinkedHashMap<>();
        Map<File, String> working = new LinkedHashMap<>();
        Map<File, String> displayPaths = new LinkedHashMap<>();

        for (int i = 0; i < edits.length(); i++) {
            JSONObject edit = edits.getJSONObject(i);
            String requestedPath = edit.getString("path");
            File file = PathPolicy.resolve(config.projectDirectory, requestedPath).getCanonicalFile();
            if (!file.isFile()) return ToolExecutionResult.error("Not a file: " + file);
            if (PathPolicy.isDangerousPseudoFile(file)) return ToolExecutionResult.error("Refusing pseudo-file: " + file);
            if (file.length() > 20L * 1024L * 1024L) return ToolExecutionResult.error("Refusing to edit a file larger than 20 MiB: " + file);

            if (!working.containsKey(file)) {
                String source = read(file);
                originals.put(file, source);
                working.put(file, source);
                displayPaths.put(file, requestedPath);
            }
            String source = working.get(file);
            String oldText = edit.getString("old_text");
            String newText = edit.getString("new_text");
            if (oldText.isEmpty()) return ToolExecutionResult.error("old_text must not be empty for edit #" + (i + 1));
            int count = countOccurrences(source, oldText);
            if (count == 0) return ToolExecutionResult.error("old_text not found in " + file + " for edit #" + (i + 1));
            boolean replaceAll = edit.optBoolean("replace_all", false);
            if (!replaceAll && count != 1) {
                return ToolExecutionResult.error("old_text appears " + count + " times in " + file + "; make edit #" + (i + 1) + " more specific or set replace_all=true");
            }
            working.put(file, replaceAll ? source.replace(oldText, newText) : replaceFirst(source, oldText, newText));
        }

        List<UnifiedDiff.Result> fileDiffs = new ArrayList<>();
        for (Map.Entry<File, String> entry : working.entrySet()) {
            File file = entry.getKey();
            fileDiffs.add(UnifiedDiff.create(displayPaths.get(file), originals.get(file), entry.getValue(), true));
        }
        // No file is touched until every edit above has validated successfully. If a later
        // write fails, restore every original so callers never observe a half-applied batch.
        List<File> committed = new ArrayList<>();
        try {
            for (Map.Entry<File, String> entry : working.entrySet()) {
                write(entry.getKey(), entry.getValue());
                committed.add(entry.getKey());
            }
        } catch (Exception failure) {
            for (File file : committed) {
                try { write(file, originals.get(file)); } catch (Exception ignored) { }
            }
            throw failure;
        }

        UnifiedDiff.Result combined = UnifiedDiff.combine(fileDiffs);
        return ToolExecutionResult.okWithDiff("Applied " + edits.length() + " edit(s) across " + working.size() + " file(s).",
            combined.text, combined.additions, combined.deletions);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0, from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }

    private static String replaceFirst(String source, String oldText, String newText) {
        int at = source.indexOf(oldText);
        return source.substring(0, at) + newText + source.substring(at + oldText.length());
    }

    private static String read(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[65_536]; int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}

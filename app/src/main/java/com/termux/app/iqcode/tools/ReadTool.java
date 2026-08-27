package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class ReadTool implements IQTool {
    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_LINE_CHARS = 12000;

    @Override public String name() { return "Read"; }
    @Override public String description() { return "Read a UTF-8 text file with line numbers. Use offset and limit for large files."; }
    @Override public PermissionKind permissionKind() { return PermissionKind.READ; }

    @Override public JSONObject inputSchema() {
        JSONObject p = new JSONObject();
        try {
            p.put("file_path", ToolSchemas.string("Absolute or project-relative file path."));
            p.put("offset", ToolSchemas.integer("1-based first line to return.", 1));
            p.put("limit", ToolSchemas.integer("Maximum number of lines to return.", 1));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return ToolSchemas.object(p, "file_path");
    }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        File file = PathPolicy.resolve(config.projectDirectory, input.optString("file_path", ""));
        if (PathPolicy.isDangerousPseudoFile(file)) return ToolExecutionResult.error("Refusing to read blocking/infinite device: " + file);
        if (!file.isFile()) return ToolExecutionResult.error("File does not exist or is not a regular file: " + file);
        if (file.length() > 20L * 1024L * 1024L) return ToolExecutionResult.error("File is larger than 20 MiB; use Grep or Bash to inspect a bounded range.");

        int offset = Math.max(1, input.optInt("offset", 1));
        int limit = Math.max(1, Math.min(input.optInt("limit", DEFAULT_LIMIT), 5000));
        StringBuilder out = new StringBuilder();
        int lineNo = 0;
        int emitted = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo < offset) continue;
                if (emitted >= limit) break;
                if (line.length() > MAX_LINE_CHARS) line = line.substring(0, MAX_LINE_CHARS) + " …[line truncated]";
                out.append(String.format("%6d\t%s\n", lineNo, line));
                emitted++;
            }
        }
        if (emitted == 0) out.append("(no lines in requested range)\n");
        out.append("\n[file: ").append(file.getAbsolutePath()).append(", returned ").append(emitted).append(" line(s)]");
        return ToolExecutionResult.ok(out.toString());
    }
}

package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public final class ListTool implements IQTool {
    @Override public String name() { return "LS"; }
    @Override public String description() { return "List the immediate contents of a directory with type and size information."; }
    @Override public PermissionKind permissionKind() { return PermissionKind.READ; }

    @Override public JSONObject inputSchema() {
        JSONObject p = new JSONObject();
        try { p.put("path", ToolSchemas.string("Directory path. Defaults to the active project.")); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return ToolSchemas.object(p);
    }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        File dir = PathPolicy.resolve(config.projectDirectory, input.optString("path", config.projectDirectory));
        if (!dir.isDirectory()) return ToolExecutionResult.error("Not a directory: " + dir);
        File[] files = dir.listFiles();
        if (files == null) return ToolExecutionResult.error("Cannot list: " + dir);
        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        StringBuilder out = new StringBuilder();
        for (File f : files) {
            out.append(f.isDirectory() ? "d " : "f ")
                .append(String.format("%10d ", f.length()))
                .append(f.getName())
                .append(f.isDirectory() ? "/" : "")
                .append('\n');
        }
        return ToolExecutionResult.ok(out.length() == 0 ? "(empty directory)" : out.toString());
    }
}

package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.tasks.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TaskGetTool implements IQTool {
    @Override public String name() { return "TaskGet"; }
    @Override public String description() { return "Get a task by ID from the current project task list."; }
    @Override public PermissionKind permissionKind() { return PermissionKind.INTERNAL; }
    @Override public JSONObject inputSchema() {
        JSONObject p = new JSONObject();
        try { p.put("taskId", ToolSchemas.string("Task ID.")); } catch (Exception e) { throw new IllegalStateException(e); }
        return ToolSchemas.object(p, "taskId");
    }
    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        JSONObject t = new TaskStore(config.projectDirectory, config.workflowId).get(input.optString("taskId", ""));
        if (t == null) return ToolExecutionResult.ok("Task not found");
        StringBuilder out = new StringBuilder();
        out.append("Task #").append(t.optString("id")).append(": ").append(t.optString("subject")).append('\n');
        out.append("Status: ").append(t.optString("status")).append('\n');
        out.append("Description: ").append(t.optString("description"));
        JSONArray blockedBy = t.optJSONArray("blockedBy");
        JSONArray blocks = t.optJSONArray("blocks");
        if (blockedBy != null && blockedBy.length() > 0) out.append("\nBlocked by: ").append(joinIds(blockedBy));
        if (blocks != null && blocks.length() > 0) out.append("\nBlocks: ").append(joinIds(blocks));
        return ToolExecutionResult.ok(out.toString());
    }
    private static String joinIds(JSONArray a) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < a.length(); i++) { if (i > 0) out.append(", "); out.append('#').append(a.optString(i)); }
        return out.toString();
    }
}

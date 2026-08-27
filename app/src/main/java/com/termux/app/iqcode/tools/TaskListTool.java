package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.tasks.TaskStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class TaskListTool implements IQTool {
    @Override public String name() { return "TaskList"; }
    @Override public String description() { return "List structured tasks and dependency status for the current coding project."; }
    @Override public PermissionKind permissionKind() { return PermissionKind.INTERNAL; }
    @Override public JSONObject inputSchema() { return ToolSchemas.object(new JSONObject()); }
    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) {
        List<JSONObject> tasks = new TaskStore(config.projectDirectory, config.workflowId).list();
        if (tasks.isEmpty()) return ToolExecutionResult.ok("No tasks found");
        Set<String> completed = new HashSet<>();
        for (JSONObject t : tasks) if ("completed".equals(t.optString("status"))) completed.add(t.optString("id"));
        StringBuilder out = new StringBuilder();
        for (JSONObject t : tasks) {
            if (t.optJSONObject("metadata") != null && t.optJSONObject("metadata").optBoolean("_internal", false)) continue;
            out.append('#').append(t.optString("id")).append(" [").append(t.optString("status")).append("] ").append(t.optString("subject"));
            String owner = t.optString("owner", "");
            if (!owner.isEmpty()) out.append(" (").append(owner).append(')');
            JSONArray blocked = t.optJSONArray("blockedBy");
            if (blocked != null) {
                StringBuilder open = new StringBuilder();
                for (int i = 0; i < blocked.length(); i++) {
                    String id = blocked.optString(i, "");
                    if (id.isEmpty() || completed.contains(id)) continue;
                    if (open.length() > 0) open.append(", ");
                    open.append('#').append(id);
                }
                if (open.length() > 0) out.append(" [blocked by ").append(open).append(']');
            }
            out.append('\n');
        }
        return ToolExecutionResult.ok(out.toString());
    }
}

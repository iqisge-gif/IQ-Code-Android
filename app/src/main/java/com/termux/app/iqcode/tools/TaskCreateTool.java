package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.tasks.TaskStore;
import org.json.JSONObject;

public final class TaskCreateTool implements IQTool {
    @Override public String name() { return "TaskCreate"; }
    @Override public String description() { return "Create a structured task for the current coding project."; }
    @Override public PermissionKind permissionKind() { return PermissionKind.INTERNAL; }
    @Override public JSONObject inputSchema() {
        JSONObject p = new JSONObject();
        try {
            p.put("subject", ToolSchemas.string("Brief actionable task title."));
            p.put("description", ToolSchemas.string("What needs to be done."));
            p.put("activeForm", ToolSchemas.string("Optional present-continuous label while in progress."));
            p.put("metadata", ToolSchemas.freeObject("Optional task metadata."));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return ToolSchemas.object(p, "subject", "description");
    }
    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        JSONObject task = new TaskStore(config.projectDirectory, config.workflowId).create(
            input.optString("subject", ""), input.optString("description", ""),
            input.optString("activeForm", ""), input.optJSONObject("metadata"));
        return ToolExecutionResult.ok("Task #" + task.getString("id") + " created successfully: " + task.getString("subject"));
    }
}

package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.tasks.TaskStore;
import org.json.JSONObject;

public final class TaskUpdateTool implements IQTool {
    @Override public String name() { return "TaskUpdate"; }
    @Override public String description() { return "Update a task's status/details/dependencies or delete it."; }
    @Override public PermissionKind permissionKind() { return PermissionKind.INTERNAL; }
    @Override public JSONObject inputSchema() {
        JSONObject p = new JSONObject();
        try {
            p.put("taskId", ToolSchemas.string("Task ID."));
            p.put("subject", ToolSchemas.string("New task subject."));
            p.put("description", ToolSchemas.string("New task description."));
            p.put("activeForm", ToolSchemas.string("New active form."));
            p.put("status", ToolSchemas.enumString("New status.", "pending", "in_progress", "completed", "deleted"));
            p.put("owner", ToolSchemas.string("Optional owner."));
            p.put("addBlocks", ToolSchemas.stringArray("Task IDs that this task blocks."));
            p.put("addBlockedBy", ToolSchemas.stringArray("Task IDs that block this task."));
            p.put("metadata", ToolSchemas.freeObject("Metadata keys to merge; null removes a key."));
        } catch (Exception e) { throw new IllegalStateException(e); }
        return ToolSchemas.object(p, "taskId");
    }
    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) throws Exception {
        String id = input.optString("taskId", "");
        JSONObject result = new TaskStore(config.projectDirectory, config.workflowId).update(id, input);
        if (result == null) return ToolExecutionResult.error("Task not found: #" + id);
        if (result.optBoolean("deleted", false)) return ToolExecutionResult.ok("Task #" + id + " deleted");
        return ToolExecutionResult.ok("Task #" + id + " updated: [" + result.optString("status") + "] " + result.optString("subject"));
    }
}

package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.termux.TermuxShellExecutor;
import org.json.JSONObject;

/** Structured read-only-ish git overview for the agent and Changes pane. */
public final class GitStatusTool implements IQTool {
    private final TermuxShellExecutor shell; public GitStatusTool(TermuxShellExecutor s){shell=s;}
    @Override public String name(){return "GitStatus";}
    @Override public String description(){return "Inspect repository branch, porcelain status, changed-file summary and recent commits without editing files.";}
    @Override public PermissionKind permissionKind(){return PermissionKind.READ;}
    @Override public JSONObject inputSchema(){return ToolSchemas.object(new JSONObject());}
    @Override public ToolExecutionResult execute(SessionConfig c,JSONObject in)throws Exception{
        String cmd="git rev-parse --show-toplevel 2>/dev/null || exit 2; printf '\\n-- status --\\n'; git status --short --branch; printf '\\n-- diff stat --\\n'; git diff --stat; printf '\\n-- staged stat --\\n'; git diff --cached --stat; printf '\\n-- recent --\\n'; git log -5 --oneline --decorate 2>/dev/null || true";
        TermuxShellExecutor.Result r=shell.execute(cmd,c.projectDirectory,30000);return ToolExecutionResult.command(r.exitCode,r.combined());
    }
}

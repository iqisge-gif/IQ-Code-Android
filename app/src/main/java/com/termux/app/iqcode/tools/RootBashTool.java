package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.termux.TermuxShellExecutor;

import org.json.JSONObject;

import java.io.File;

/** Explicit, opt-in superuser command surface backed by Magisk/KernelSU su. */
public final class RootBashTool implements IQTool {
    private final TermuxShellExecutor executor;
    public RootBashTool(TermuxShellExecutor executor){this.executor=executor;}

    @Override public String name(){return "Root";}
    @Override public String description(){return "Run an Android uid-0 command via su; only when Bash is insufficient; never for pkg/apt.";}
    @Override public PermissionKind permissionKind(){return PermissionKind.SYSTEM;}

    @Override public JSONObject inputSchema(){
        JSONObject p=new JSONObject();
        try{
            p.put("command",ToolSchemas.string("Command to execute as Android root (uid 0)."));
            p.put("cwd",ToolSchemas.string("Optional working directory. Absolute root-only paths are allowed; relative paths resolve from the active project."));
            p.put("timeout_ms",ToolSchemas.integer("Timeout in milliseconds (maximum 3600000).",1000));
        }catch(Exception e){throw new IllegalStateException(e);}
        return ToolSchemas.object(p,"command");
    }

    @Override public ToolExecutionResult execute(SessionConfig config,JSONObject input)throws Exception{return execute(config,input,null);}

    @Override public ToolExecutionResult execute(SessionConfig config,JSONObject input,ProgressListener progress)throws Exception{
        if(config==null||!config.rootExecutionEnabled)return ToolExecutionResult.error("Agent Root is disabled in Settings");
        String command=input.optString("command","").trim();if(command.isEmpty())return ToolExecutionResult.error("Root command is empty");
        String cwdRaw=input.optString("cwd",config.projectDirectory);if(cwdRaw==null||cwdRaw.trim().isEmpty())cwdRaw=config.projectDirectory;
        File cwdFile=new File(cwdRaw);if(!cwdFile.isAbsolute())cwdFile=new File(config.projectDirectory,cwdRaw);
        int timeout=Math.max(1000,Math.min(3_600_000,input.optInt("timeout_ms",TermuxShellExecutor.DEFAULT_TIMEOUT_MS)));
        String verified="if [ \"$(id -u)\" != 0 ]; then echo '[IQGE] su did not grant uid 0' >&2; exit 126; fi; "+command;
        TermuxShellExecutor.OutputListener live=progress==null?null:(chunk,stderr,elapsed)->progress.onProgress(chunk,stderr,elapsed);
        TermuxShellExecutor.Result result=executor.executeAsRoot(verified,cwdFile.getAbsolutePath(),timeout,live);
        return ToolExecutionResult.command(result.timedOut?124:result.exitCode,result.combined());
    }
}

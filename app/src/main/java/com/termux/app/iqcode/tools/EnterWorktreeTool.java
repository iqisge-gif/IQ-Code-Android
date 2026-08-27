package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.termux.TermuxShellExecutor;
import com.termux.shared.termux.TermuxConstants;
import org.json.JSONObject;
import java.io.File;import java.util.UUID;

public final class EnterWorktreeTool implements IQTool {
 private final TermuxShellExecutor shell; public EnterWorktreeTool(TermuxShellExecutor shell){this.shell=shell;}
 @Override public String name(){return "EnterWorktree";} @Override public String description(){return "Create and enter an isolated git worktree for subsequent IQ Code file and shell operations.";} @Override public PermissionKind permissionKind(){return PermissionKind.SHELL;}
 @Override public JSONObject inputSchema(){JSONObject p=new JSONObject();try{p.put("name",ToolSchemas.string("Optional short worktree label."));}catch(Exception e){throw new IllegalStateException(e);}return ToolSchemas.object(p);}
 @Override public ToolExecutionResult execute(SessionConfig c,JSONObject in)throws Exception{
   if(c.worktreePath!=null&&!c.worktreePath.isEmpty()&&new File(c.worktreePath).isDirectory())return ToolExecutionResult.ok("Already in worktree: "+c.worktreePath);
   String root=new File(c.projectDirectory).getCanonicalPath();String label=in.optString("name","").replaceAll("[^A-Za-z0-9._-]","-");if(label.isEmpty())label="iq";
   String path=new File(TermuxConstants.TERMUX_HOME_DIR_PATH,".iq/worktrees/"+label+"-"+UUID.randomUUID().toString().substring(0,8)).getAbsolutePath();
   String cmd="git -C "+q(root)+" rev-parse --is-inside-work-tree >/dev/null && mkdir -p "+q(new File(path).getParent())+" && git -C "+q(root)+" worktree add --detach "+q(path)+" HEAD";
   TermuxShellExecutor.Result r=shell.execute(cmd,root,120000);if(r.exitCode!=0)return ToolExecutionResult.command(r.exitCode,r.combined());
   c.worktreeOriginalDirectory=root;c.worktreePath=path;c.projectDirectory=path;return ToolExecutionResult.ok("Entered isolated worktree: "+path+"\nAll subsequent file/shell tools now operate there until ExitWorktree.");
 }
 private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}
}

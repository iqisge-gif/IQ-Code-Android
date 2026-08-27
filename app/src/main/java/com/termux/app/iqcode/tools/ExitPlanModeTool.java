package com.termux.app.iqcode.tools;
import com.termux.app.iqcode.model.*;import org.json.JSONObject;
public final class ExitPlanModeTool implements IQTool{
 @Override public String name(){return "ExitPlanMode";}@Override public String description(){return "Present a plan for user approval; approval does not change the user-owned permission mode.";}@Override public PermissionKind permissionKind(){return PermissionKind.INTERNAL;}
 @Override public JSONObject inputSchema(){JSONObject p=new JSONObject();try{p.put("plan",ToolSchemas.string("Concise implementation plan being proposed."));}catch(Exception e){throw new IllegalStateException(e);}return ToolSchemas.object(p);}
 @Override public ToolExecutionResult execute(SessionConfig c,JSONObject i){return ToolExecutionResult.ok("Plan approval is managed by the IQ Code engine.");}
}

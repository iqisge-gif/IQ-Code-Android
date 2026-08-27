package com.termux.app.iqcode.tools;
import com.termux.app.iqcode.model.*;import org.json.JSONObject;
public final class EnterPlanModeTool implements IQTool{
 @Override public String name(){return "EnterPlanMode";}@Override public String description(){return "Enter a read-only plan overlay before exploring and designing an implementation; it cannot change the user-owned permission mode.";}@Override public PermissionKind permissionKind(){return PermissionKind.INTERNAL;}
 @Override public JSONObject inputSchema(){return ToolSchemas.object(new JSONObject());}
 @Override public ToolExecutionResult execute(SessionConfig c,JSONObject i){return ToolExecutionResult.ok("Plan mode transition is managed by the IQ Code engine.");}
}

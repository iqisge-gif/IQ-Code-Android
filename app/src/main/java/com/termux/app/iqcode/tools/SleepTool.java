package com.termux.app.iqcode.tools;
import com.termux.app.iqcode.model.*;import org.json.JSONObject;
public final class SleepTool implements IQTool{
 @Override public String name(){return "Sleep";}@Override public String description(){return "Wait briefly before continuing, useful for polling background processes.";}@Override public PermissionKind permissionKind(){return PermissionKind.INTERNAL;}
 @Override public JSONObject inputSchema(){JSONObject p=new JSONObject();try{p.put("seconds",ToolSchemas.integer("Seconds to wait (maximum 60).",0));}catch(Exception e){throw new IllegalStateException(e);}return ToolSchemas.object(p,"seconds");}
 @Override public ToolExecutionResult execute(SessionConfig c,JSONObject i)throws Exception{int sec=Math.max(0,Math.min(60,i.optInt("seconds",1)));Thread.sleep(sec*1000L);return ToolExecutionResult.ok("Waited "+sec+" second"+(sec==1?"":"s"));}
}

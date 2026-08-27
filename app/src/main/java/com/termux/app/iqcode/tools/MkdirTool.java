package com.termux.app.iqcode.tools;
import com.termux.app.iqcode.model.SessionConfig;import com.termux.app.iqcode.model.ToolExecutionResult;import org.json.JSONObject;import java.io.File;
public final class MkdirTool implements IQTool{
 public String name(){return "Mkdir";} public String description(){return "Create a directory, including missing parent directories.";}
 public JSONObject inputSchema(){try{return ToolSchemas.object(new JSONObject().put("path",ToolSchemas.string("Directory path.")),"path");}catch(Exception e){throw new IllegalStateException(e);}}
 public PermissionKind permissionKind(){return PermissionKind.WRITE;}
 public ToolExecutionResult execute(SessionConfig c,JSONObject in)throws Exception{File f=PathPolicy.resolve(c.projectDirectory,in.getString("path"));if(f.isDirectory())return ToolExecutionResult.ok("Directory already exists: "+f);if(!f.mkdirs())return ToolExecutionResult.error("Could not create directory: "+f);return ToolExecutionResult.ok("Created "+f);}
}

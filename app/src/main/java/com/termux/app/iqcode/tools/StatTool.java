package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import org.json.JSONObject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class StatTool implements IQTool {
    @Override public String name(){return "Stat";}
    @Override public String description(){return "Inspect a path's canonical location, type, size, permissions, modified time and child count.";}
    @Override public PermissionKind permissionKind(){return PermissionKind.READ;}
    @Override public JSONObject inputSchema(){try{return ToolSchemas.object(new JSONObject().put("path",ToolSchemas.string("File or directory path.")),"path");}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public ToolExecutionResult execute(SessionConfig c,JSONObject in)throws Exception{
        File f=PathPolicy.resolve(c.projectDirectory,in.getString("path"));
        StringBuilder o=new StringBuilder();
        o.append("path: ").append(f.getAbsolutePath()).append('\n');
        o.append("exists: ").append(f.exists()).append('\n');
        if(!f.exists()) return ToolExecutionResult.ok(o.toString());
        o.append("type: ").append(f.isDirectory()?"directory":f.isFile()?"file":"other").append('\n');
        o.append("size: ").append(f.length()).append(" bytes\n");
        o.append("readable: ").append(f.canRead()).append('\n');
        o.append("writable: ").append(f.canWrite()).append('\n');
        o.append("executable: ").append(f.canExecute()).append('\n');
        o.append("modified: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z",Locale.US).format(new Date(f.lastModified()))).append('\n');
        if(f.isDirectory()){String[] x=f.list();o.append("children: ").append(x==null?"unavailable":Integer.toString(x.length)).append('\n');}
        return ToolExecutionResult.ok(o.toString());
    }
}

package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Copy files or directory trees without requiring java.nio.file (keeps Android 7 compatibility). */
public final class CopyTool implements IQTool {
    @Override public String name(){ return "Copy"; }
    @Override public String description(){ return "Copy a file or directory tree inside the workspace. Parent directories are created automatically."; }
    @Override public PermissionKind permissionKind(){ return PermissionKind.WRITE; }
    @Override public JSONObject inputSchema(){ try {
        JSONObject p=new JSONObject();
        p.put("source",ToolSchemas.string("Source path."));
        p.put("destination",ToolSchemas.string("Destination path."));
        p.put("overwrite",ToolSchemas.bool("Replace existing destination files."));
        return ToolSchemas.object(p,"source","destination");
    } catch(Exception e){ throw new IllegalStateException(e); } }
    @Override public ToolExecutionResult execute(SessionConfig c,JSONObject in)throws Exception{
        File src=PathPolicy.resolve(c.projectDirectory,in.getString("source"));
        File dst=PathPolicy.resolve(c.projectDirectory,in.getString("destination"));
        if(!src.exists()) return ToolExecutionResult.error("Source does not exist: "+src);
        if(src.equals(dst)) return ToolExecutionResult.error("Source and destination are the same path.");
        copy(src,dst,in.optBoolean("overwrite",false));
        return ToolExecutionResult.ok("Copied "+src+" -> "+dst);
    }
    static void copy(File src,File dst,boolean overwrite)throws Exception{
        if(src.isDirectory()){
            if(dst.exists()&&!dst.isDirectory()) throw new IllegalArgumentException("Destination exists and is not a directory: "+dst);
            if(!dst.exists()&&!dst.mkdirs()) throw new IllegalStateException("Cannot create directory: "+dst);
            File[] xs=src.listFiles(); if(xs!=null) for(File x:xs) copy(x,new File(dst,x.getName()),overwrite);
        }else{
            if(dst.exists()&&!overwrite) throw new IllegalStateException("Destination already exists: "+dst);
            File p=dst.getParentFile(); if(p!=null&&!p.exists()&&!p.mkdirs()) throw new IllegalStateException("Cannot create parent: "+p);
            try(FileInputStream in=new FileInputStream(src);FileOutputStream out=new FileOutputStream(dst,false)){
                byte[] b=new byte[64*1024];int n;while((n=in.read(b))>=0)out.write(b,0,n);
            }
            dst.setExecutable(src.canExecute(),true); dst.setReadable(src.canRead(),true); dst.setWritable(src.canWrite(),true);
            dst.setLastModified(src.lastModified());
        }
    }
}

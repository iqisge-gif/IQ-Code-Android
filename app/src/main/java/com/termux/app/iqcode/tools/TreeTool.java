package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import org.json.JSONObject;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public final class TreeTool implements IQTool {
    @Override public String name(){return "Tree";}
    @Override public String description(){return "Show a bounded recursive project directory tree. Hidden files may be included and common build/cache directories can be skipped.";}
    @Override public PermissionKind permissionKind(){return PermissionKind.READ;}
    @Override public JSONObject inputSchema(){try{
        JSONObject p=new JSONObject();p.put("path",ToolSchemas.string("Directory path."));p.put("depth",ToolSchemas.integer("Maximum recursion depth (1-8).",1));p.put("include_hidden",ToolSchemas.bool("Include dotfiles/directories."));
        return ToolSchemas.object(p);
    }catch(Exception e){throw new IllegalStateException(e);}}
    @Override public ToolExecutionResult execute(SessionConfig c,JSONObject in)throws Exception{
        File root=PathPolicy.resolve(c.projectDirectory,in.optString("path",c.projectDirectory));if(!root.isDirectory())return ToolExecutionResult.error("Not a directory: "+root);
        int depth=Math.max(1,Math.min(in.optInt("depth",4),8));boolean hidden=in.optBoolean("include_hidden",true);StringBuilder out=new StringBuilder();int[] count={0};
        out.append(root.getAbsolutePath()).append("/\n");walk(root,"",0,depth,hidden,out,count);if(count[0]>=5000)out.append("…tree truncated at 5000 entries…\n");return ToolExecutionResult.ok(out.toString());
    }
    private void walk(File dir,String indent,int level,int max,boolean hidden,StringBuilder out,int[] count){if(level>=max||count[0]>=5000)return;File[] xs=dir.listFiles();if(xs==null)return;Arrays.sort(xs,Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));
        for(File f:xs){if(count[0]>=5000)return;String n=f.getName();if(!hidden&&n.startsWith("."))continue;if(f.isDirectory()&&(n.equals("node_modules")||n.equals(".gradle")||n.equals("build")||n.equals("dist"))&&level>0){out.append(indent).append("├─ ").append(n).append("/  […]\n");count[0]++;continue;}out.append(indent).append("├─ ").append(n).append(f.isDirectory()?"/":"").append('\n');count[0]++;if(f.isDirectory())walk(f,indent+"│  ",level+1,max,hidden,out,count);}
    }
}

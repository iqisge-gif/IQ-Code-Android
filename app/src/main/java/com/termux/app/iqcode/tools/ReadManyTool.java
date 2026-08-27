package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded multi-file read for repository-wide reasoning without many agent round trips. */
public final class ReadManyTool implements IQTool {
    @Override public String name(){return "ReadMany";}
    @Override public String description(){return "Read several UTF-8 source files in one call. Each file is line-numbered and bounded; useful for understanding related code before editing.";}
    @Override public PermissionKind permissionKind(){return PermissionKind.READ;}
    @Override public JSONObject inputSchema(){try{
        JSONObject p=new JSONObject();
        p.put("paths",ToolSchemas.stringArray("File paths to read; maximum 24."));
        p.put("limit_per_file",ToolSchemas.integer("Maximum lines per file.",1));
        return ToolSchemas.object(p,"paths");
    }catch(Exception e){throw new IllegalStateException(e);}}
    @Override public ToolExecutionResult execute(SessionConfig c,JSONObject in)throws Exception{
        JSONArray a=in.getJSONArray("paths"); if(a.length()==0)return ToolExecutionResult.error("paths is empty");
        int n=Math.min(a.length(),24),limit=Math.max(1,Math.min(in.optInt("limit_per_file",500),2000));
        ReadTool read=new ReadTool(); StringBuilder out=new StringBuilder();
        for(int i=0;i<n;i++){
            String path=a.getString(i); out.append("===== ").append(path).append(" =====\n");
            JSONObject req=new JSONObject().put("file_path",path).put("offset",1).put("limit",limit);
            ToolExecutionResult r=read.execute(c,req); out.append(r.content).append("\n\n");
            if(out.length()>1_800_000){out.append("…multi-file output truncated…\n");break;}
        }
        if(a.length()>n)out.append("[skipped ").append(a.length()-n).append(" file(s); max 24]\n");
        return ToolExecutionResult.ok(out.toString());
    }
}

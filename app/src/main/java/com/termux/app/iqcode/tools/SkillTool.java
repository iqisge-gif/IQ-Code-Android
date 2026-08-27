package com.termux.app.iqcode.tools;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.shared.termux.TermuxConstants;
import org.json.JSONObject;
import java.io.File;import java.io.FileInputStream;import java.nio.charset.StandardCharsets;

public final class SkillTool implements IQTool {
 @Override public String name(){return "Skill";} @Override public String description(){return "Load a IQ Code skill's SKILL.md instructions from the project or user skill library.";} @Override public PermissionKind permissionKind(){return PermissionKind.READ;}
 @Override public JSONObject inputSchema(){JSONObject p=new JSONObject();try{p.put("skill",ToolSchemas.string("Skill name to load."));p.put("args",ToolSchemas.string("Optional arguments for the skill."));}catch(Exception e){throw new IllegalStateException(e);}return ToolSchemas.object(p,"skill");}
 @Override public ToolExecutionResult execute(SessionConfig c,JSONObject in)throws Exception{String n=in.optString("skill","").trim();if(n.isEmpty()||n.contains("..")||n.contains("/"))return ToolExecutionResult.error("Invalid skill name");File[] candidates={new File(c.projectDirectory,".iq/skills/"+n+"/SKILL.md"),new File(TermuxConstants.TERMUX_HOME_DIR_PATH,".iq/skills/"+n+"/SKILL.md")};for(File f:candidates)if(f.isFile()){String body=read(f);String args=in.optString("args","");return ToolExecutionResult.ok("Loaded skill `"+n+"` from "+f.getAbsolutePath()+"\n\n"+body+(args.isEmpty()?"":"\n\nSkill arguments: "+args));}return ToolExecutionResult.error("Skill not found: "+n);}
 private static String read(File f)throws Exception{byte[]b=new byte[(int)f.length()];try(FileInputStream x=new FileInputStream(f)){int o=0,k;while(o<b.length&&(k=x.read(b,o,b.length-o))>0)o+=k;}return new String(b,StandardCharsets.UTF_8);}
}

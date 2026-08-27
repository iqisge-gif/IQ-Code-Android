package com.termux.app.iqcode.agents;

import android.content.Context;

import com.termux.app.iqcode.api.ModelProvider;
import com.termux.app.iqcode.core.IQCodeEngine;
import com.termux.app.iqcode.core.PermissionModePolicy;
import com.termux.app.iqcode.core.PermissionGate;
import com.termux.app.iqcode.core.QuestionGate;
import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolCall;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.termux.TermuxShellExecutor;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** IQ Code Agent/Task runtime: independent subagent contexts plus foreground/background task lifecycle. */
public final class SubagentManager {
    public interface PermissionRelay { void onPermissionRequest(PermissionGate.PermissionRequest request); }
    public interface QuestionRelay { void onQuestionRequest(QuestionGate.QuestionRequest request); }
    private final Context context;
    private final ModelProvider providerOverride;
    private final PermissionRelay permissionRelay;
    private final QuestionRelay questionRelay;
    private final ExecutorService executor=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"iq-subagent");t.setDaemon(true);return t;});
    private final Map<String,AgentTask> tasks=new ConcurrentHashMap<>();

    public SubagentManager(Context context, ModelProvider providerOverride, PermissionRelay permissionRelay, QuestionRelay questionRelay){this.context=context.getApplicationContext();this.providerOverride=providerOverride;this.permissionRelay=permissionRelay;this.questionRelay=questionRelay;}

    public JSONArray apiSchemas(){
        JSONArray a=new JSONArray();
        a.put(toolSchema("Agent","Launch a specialized IQ Code subagent in an independent context. Subagents cannot spawn other subagents.",agentInputSchema()));
        a.put(toolSchema("TaskOutput","Read the current or final output of a background subagent task.",taskOutputSchema()));
        a.put(toolSchema("TaskStop","Stop a running background subagent task.",taskStopSchema()));
        return a;
    }

    public ToolExecutionResult execute(SessionConfig parent, String parentEffectiveMode, ToolCall call) throws Exception {
        if("Agent".equals(call.name)||"Task".equals(call.name))return launch(parent,parentEffectiveMode,call.input);
        if("TaskOutput".equals(call.name))return output(call.input);
        if("TaskStop".equals(call.name))return stop(call.input);
        return ToolExecutionResult.error("Unknown subagent tool: "+call.name);
    }

    public List<AgentDefinition> definitions(String project){return AgentDefinitionLoader.loadAll(project);}
    public List<AgentTask> tasks(){List<AgentTask> l=new ArrayList<>(tasks.values());Collections.sort(l,(a,b)->Long.compare(b.startedAt,a.startedAt));return l;}

    private ToolExecutionResult launch(SessionConfig parent,String parentEffectiveMode,JSONObject input)throws Exception{
        String prompt=input.optString("prompt","").trim(); if(prompt.isEmpty())return ToolExecutionResult.error("Agent prompt is required");
        String type=input.optString("subagent_type","general-purpose").trim(); if(type.isEmpty())type="general-purpose";
        AgentDefinition def=AgentDefinitionLoader.resolve(parent.projectDirectory,type);
        if(def==null)return ToolExecutionResult.error("Unknown subagent type '"+type+"'. Available: "+joinAgentNames(parent.projectDirectory));
        String cwd=input.optString("cwd","").trim(); String isolation=input.optString("isolation",def.isolation).trim();
        if(!cwd.isEmpty()&&!isolation.isEmpty())return ToolExecutionResult.error("cwd and isolation are mutually exclusive");
        boolean background=input.has("run_in_background")?input.optBoolean("run_in_background",false):def.background;
        String id=UUID.randomUUID().toString().substring(0,8);
        String desc=input.optString("description",def.name).trim(); if(desc.isEmpty())desc=def.name;
        AgentTask task=new AgentTask(id,desc,def.name); tasks.put(id,task); persist(task);
        final String finalCwd=cwd; final String finalIsolation=isolation;
        Runnable r=()->runTask(parent,parentEffectiveMode,input,def,task,prompt,finalCwd,finalIsolation);
        task.future=executor.submit(r);
        if(background)return ToolExecutionResult.ok("Background agent started.\ntask_id: "+id+"\nagent: "+def.name+"\ndescription: "+desc+"\nUse TaskOutput with task_id='"+id+"' to read progress/result, or TaskStop to cancel it.");
        task.done.await();
        if("failed".equals(task.status))return ToolExecutionResult.error("Agent "+def.name+" failed: "+(task.error==null?task.output:task.error));
        if("cancelled".equals(task.status))return ToolExecutionResult.error("Agent "+def.name+" was cancelled.\n"+task.output);
        return ToolExecutionResult.ok("Agent "+def.name+" completed.\ntask_id: "+id+"\n\n"+task.output.toString().trim()+worktreeNotice(task));
    }

    private void runTask(SessionConfig parent,String parentEffectiveMode,JSONObject invocation,AgentDefinition def,AgentTask task,String prompt,String cwd,String isolation){
        String worktree="";
        try{
            SessionConfig child=parent.copy(); child.renewTransportSession();
            if(!cwd.isEmpty())child.projectDirectory=validateCwd(cwd);
            else if("worktree".equalsIgnoreCase(isolation)){worktree=createWorktree(parent.projectDirectory,task.id);child.projectDirectory=worktree;task.worktreePath=worktree;}
            applyModel(parent,child,def,invocation.optString("model",""));
            if(!def.effort.isEmpty())child.effort=def.effort;
            child.permissionMode=PermissionModePolicy.capSubagent(parentEffectiveMode,def.permissionMode);
            if(def.maxTurns>0)child.maxAgentTurns=def.maxTurns;
            LinkedHashSet<String> allowed=resolveAllowedTools(def);
            String suffix=buildSystemSuffix(def,prompt);
            final StringBuilder finalAnswer=task.output;
            IQCodeEngine.Listener listener=new IQCodeEngine.Listener(){
                @Override public void onSessionStarted(SessionConfig c){task.progress="Thinking…";persist(task);}
                @Override public void onTextDelta(String text){synchronized(finalAnswer){finalAnswer.append(text);}task.progress="Responding…";persistThrottled(task);}
                @Override public void onResponseRetry(){synchronized(finalAnswer){finalAnswer.setLength(0);}task.progress="连接中断，正在重试…";persist(task);}
                @Override public void onThinkingDelta(String thinking){task.progress="Thinking…";}
                @Override public void onToolUse(ToolCall call){task.progress="Using "+call.name+"…";persistThrottled(task);}
                @Override public void onToolResult(ToolCall call,ToolExecutionResult result){task.progress=(result.isError?"Tool failed: ":"Used ")+call.name;persistThrottled(task);}
                @Override public void onPermissionRequest(PermissionGate.PermissionRequest request){if(permissionRelay!=null)permissionRelay.onPermissionRequest(request);}
                @Override public void onQuestionRequest(QuestionGate.QuestionRequest request){if(questionRelay!=null)questionRelay.onQuestionRequest(request);}
                @Override public void onUsage(long input,long output){}
                @Override public void onStatus(String status){task.progress=status;persistThrottled(task);}
                @Override public void onTurnComplete(String reason){task.status="cancelled".equals(reason)?"cancelled":"completed";task.finishedAt=System.currentTimeMillis();task.progress=reason;task.sessionFile=task.engine==null?null:task.engine.getSessionFile();persist(task);task.done.countDown();}
                @Override public void onError(String message,Throwable error){task.status="failed";task.error=error;task.finishedAt=System.currentTimeMillis();task.progress=message;persist(task);task.done.countDown();}
            };
            IQCodeEngine childEngine=IQCodeEngine.createSubagent(context,listener,providerOverride,allowed,suffix);
            task.engine=childEngine; childEngine.configure(child); childEngine.sendPrompt(prompt);
            task.done.await();
            if("worktree".equalsIgnoreCase(isolation))cleanupWorktreeIfClean(parent.projectDirectory,worktree);
        }catch(InterruptedException e){Thread.currentThread().interrupt();task.status="cancelled";task.finishedAt=System.currentTimeMillis();task.progress="Cancelled";if(task.engine!=null)task.engine.cancel();persist(task);task.done.countDown();}
        catch(Throwable e){task.status="failed";task.error=e;task.finishedAt=System.currentTimeMillis();task.progress=e.getMessage()==null?e.toString():e.getMessage();persist(task);task.done.countDown();}
        finally{if(task.engine!=null&&!("running".equals(task.status)))task.engine.shutdown();}
    }

    private ToolExecutionResult output(JSONObject input)throws Exception{
        String id=input.optString("task_id",input.optString("taskId","")).trim(); AgentTask t=tasks.get(id); if(t==null)return ToolExecutionResult.error("Unknown agent task: "+id);
        boolean block=input.optBoolean("block",true); int timeout=Math.max(0,input.optInt("timeout",30000));
        if(block&&"running".equals(t.status))t.done.await(Math.min(timeout,600000),TimeUnit.MILLISECONDS);
        StringBuilder b=new StringBuilder();b.append("task_id: ").append(t.id).append("\nstatus: ").append(t.status).append("\nagent: ").append(t.agentType).append("\nprogress: ").append(t.progress);
        synchronized(t.output){if(t.output.length()>0)b.append("\n\n").append(t.output);}
        if(t.error!=null)b.append("\n\nerror: ").append(t.error);
        return "failed".equals(t.status)?ToolExecutionResult.error(b.toString()):ToolExecutionResult.ok(b.toString());
    }

    private ToolExecutionResult stop(JSONObject input){String id=input.optString("task_id",input.optString("taskId","")).trim();AgentTask t=tasks.get(id);if(t==null)return ToolExecutionResult.error("Unknown agent task: "+id);if(!"running".equals(t.status))return ToolExecutionResult.ok("Task "+id+" is already "+t.status);if(t.engine!=null)t.engine.cancel();if(t.future!=null)t.future.cancel(true);t.status="cancelled";t.finishedAt=System.currentTimeMillis();t.progress="Cancelled";persist(t);t.done.countDown();return ToolExecutionResult.ok("Stopped agent task "+id);}

    public boolean stop(String id){AgentTask t=tasks.get(id);if(t==null)return false;JSONObject o=new JSONObject();try{o.put("task_id",id);}catch(Exception ignored){}stop(o);return true;}

    public boolean respondPermission(String requestId,boolean allow){for(AgentTask t:tasks.values()){IQCodeEngine e=t.engine;if(e!=null&&e.respondPermissionLocal(requestId,allow))return true;}return false;}

    public boolean respondQuestion(String requestId,JSONObject answers){for(AgentTask t:tasks.values()){IQCodeEngine e=t.engine;if(e!=null&&e.respondQuestion(requestId,answers))return true;}return false;}

    private LinkedHashSet<String> resolveAllowedTools(AgentDefinition d){
        LinkedHashSet<String> all=new LinkedHashSet<>(Arrays.asList("Bash","Root","Sandbox","GitStatus","TermuxDoctor","TermuxRepair","EnterWorktree","ExitWorktree","Read","ReadMany","Stat","Tree","Write","Copy","Edit","MultiEdit","Mkdir","Move","Delete","Glob","Grep","LS","TaskCreate","TaskGet","TaskList","TaskUpdate","Skill","Sleep","TodoWrite","AskUserQuestion"));
        LinkedHashSet<String> result=new LinkedHashSet<>(); if(d.inheritsAllTools())result.addAll(all); else for(String t:d.tools)if(all.contains(normalizeTool(t)))result.add(normalizeTool(t));
        for(String t:d.disallowedTools)result.remove(normalizeTool(t)); return result;
    }
    private static String normalizeTool(String t){if(t==null)return "";String v=t.trim();if("FileRead".equalsIgnoreCase(v))return "Read";if("FileWrite".equalsIgnoreCase(v))return "Write";if("FileEdit".equalsIgnoreCase(v))return "Edit";if("List".equalsIgnoreCase(v))return "LS";return v;}

    private String buildSystemSuffix(AgentDefinition d,String prompt){
        StringBuilder b=new StringBuilder("\n\n# Subagent mode\nYou are running as the `").append(d.name).append("` subagent in a separate context window. Subagents cannot spawn other subagents. Return only the result needed by the parent agent.\n");
        if(!d.prompt.isEmpty())b.append("\n# Subagent instructions\n").append(d.prompt).append('\n');
        if(!d.skills.isEmpty())for(String skill:d.skills){String body=readSkill(skill);if(!body.isEmpty())b.append("\n# Preloaded skill: ").append(skill).append("\n").append(body).append('\n');}
        if(!d.memory.isEmpty()&&!"none".equalsIgnoreCase(d.memory))b.append("\nPersistent memory scope requested: ").append(d.memory).append(". Store concise durable findings under ~/.iq/agent-memory/").append(d.name).append(" when useful.\n");
        return b.toString();
    }
    private String readSkill(String name){for(File f:new File[]{new File(TermuxConstants.TERMUX_HOME_DIR_PATH,".iq/skills/"+name+"/SKILL.md")}){try{if(f.isFile())return readFileText(f);}catch(Exception ignored){}}return "";}

    private static void applyModel(SessionConfig parent,SessionConfig child,AgentDefinition def,String invocation){String requested=invocation==null||invocation.isEmpty()?def.model:invocation;if(requested==null||requested.isEmpty()||"inherit".equalsIgnoreCase(requested))return;if(Arrays.asList("haiku","sonnet","opus").contains(requested.toLowerCase(Locale.US))) child.model=parent.model; else child.model=requested;}
    private static String validateCwd(String cwd)throws Exception{File f=new File(cwd).getCanonicalFile();if(!f.isDirectory())throw new IllegalArgumentException("Subagent cwd does not exist: "+cwd);return f.getAbsolutePath();}

    private String createWorktree(String project,String id)throws Exception{String root=new File(TermuxConstants.TERMUX_HOME_DIR_PATH,".iq/worktrees/"+id).getAbsolutePath();String q=shellQuote(root);String cmd="git -C "+shellQuote(project)+" rev-parse --is-inside-work-tree >/dev/null && mkdir -p "+shellQuote(new File(root).getParent())+" && git -C "+shellQuote(project)+" worktree add --detach "+q+" HEAD";TermuxShellExecutor.Result r=new TermuxShellExecutor(context).execute(cmd,project,120000);if(r.exitCode!=0)throw new IllegalStateException("Failed to create agent worktree: "+r.combined());return root;}
    private void cleanupWorktreeIfClean(String project,String wt){if(wt==null||wt.isEmpty())return;try{TermuxShellExecutor sh=new TermuxShellExecutor(context);TermuxShellExecutor.Result dirty=sh.execute("git status --porcelain",wt,30000);if(dirty.exitCode==0&&dirty.combined().trim().isEmpty())sh.execute("git -C "+shellQuote(project)+" worktree remove --force "+shellQuote(wt),project,60000);}catch(Exception ignored){}}
    private static String shellQuote(String s){return "'"+(s==null?"":s.replace("'","'\\''"))+"'";}
    private static String worktreeNotice(AgentTask t){return t.worktreePath==null||t.worktreePath.isEmpty()?"":"\n\nworktree: "+t.worktreePath;}
    private String joinAgentNames(String project){StringBuilder b=new StringBuilder();for(AgentDefinition d:definitions(project)){if(b.length()>0)b.append(", ");b.append(d.name);}return b.toString();}

    private static String readFileText(File f)throws Exception{byte[]b=new byte[(int)f.length()];try(java.io.FileInputStream in=new java.io.FileInputStream(f)){int o=0,n;while(o<b.length&&(n=in.read(b,o,b.length-o))>0)o+=n;}return new String(b,StandardCharsets.UTF_8);}

    private void persist(AgentTask t){try{File dir=new File(TermuxConstants.TERMUX_HOME_DIR_PATH,".iq/agent-tasks");dir.mkdirs();File f=new File(dir,t.id+".json");try(FileOutputStream o=new FileOutputStream(f,false)){o.write(t.toJson().toString(2).getBytes(StandardCharsets.UTF_8));}}catch(Exception ignored){}}
    private volatile long lastPersist;
    private void persistThrottled(AgentTask t){long now=System.currentTimeMillis();if(now-lastPersist>500){lastPersist=now;persist(t);}}

    private static JSONObject toolSchema(String name,String description,JSONObject input){JSONObject o=new JSONObject();try{o.put("name",name).put("description",description).put("input_schema",input);}catch(Exception e){throw new IllegalStateException(e);}return o;}
    private static JSONObject agentInputSchema(){try{JSONObject p=new JSONObject();p.put("description",str("A short 3-5 word task description."));p.put("prompt",str("The task for the subagent to perform."));p.put("subagent_type",str("Specialized agent type, such as Explore, Plan, general-purpose, verification, or a custom agent name."));p.put("model",str("Optional model override: inherit, sonnet, opus, haiku, or a full model ID."));p.put("run_in_background",bool("Run in background and return a task_id immediately."));p.put("isolation",enm("Optional isolation mode.","worktree"));p.put("cwd",str("Optional absolute working directory; mutually exclusive with isolation."));return obj(p,"description","prompt");}catch(Exception e){throw new IllegalStateException(e);}}
    private static JSONObject taskOutputSchema(){try{JSONObject p=new JSONObject();p.put("task_id",str("Background agent task ID."));p.put("block",bool("Wait for completion if still running. Default true."));p.put("timeout",integer("Maximum wait in milliseconds (max 600000)."));return obj(p,"task_id");}catch(Exception e){throw new IllegalStateException(e);}}
    private static JSONObject taskStopSchema(){try{JSONObject p=new JSONObject();p.put("task_id",str("Background agent task ID."));return obj(p,"task_id");}catch(Exception e){throw new IllegalStateException(e);}}
    private static JSONObject obj(JSONObject p,String...req)throws Exception{JSONObject o=new JSONObject().put("type","object").put("properties",p).put("additionalProperties",false);JSONArray a=new JSONArray();for(String r:req)a.put(r);if(a.length()>0)o.put("required",a);return o;}
    private static JSONObject str(String d)throws Exception{return new JSONObject().put("type","string").put("description",d);}private static JSONObject bool(String d)throws Exception{return new JSONObject().put("type","boolean").put("description",d);}private static JSONObject integer(String d)throws Exception{return new JSONObject().put("type","integer").put("minimum",0).put("description",d);}private static JSONObject enm(String d,String...v)throws Exception{JSONArray a=new JSONArray();for(String x:v)a.put(x);return new JSONObject().put("type","string").put("enum",a).put("description",d);}
}

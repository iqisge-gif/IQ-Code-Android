package com.termux.app.iqcode.agents;

import com.termux.app.iqcode.core.IQCodeEngine;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

public final class AgentTask {
    public final String id;
    public final String description;
    public final String agentType;
    public final StringBuilder output = new StringBuilder();
    public final long startedAt = System.currentTimeMillis();
    public final CountDownLatch done = new CountDownLatch(1);
    public volatile String status = "running";
    public volatile String progress = "Starting…";
    public volatile long finishedAt;
    public volatile Throwable error;
    public volatile IQCodeEngine engine;
    public volatile Future<?> future;
    public volatile File sessionFile;
    public volatile String worktreePath = "";

    AgentTask(String id,String description,String agentType){this.id=id;this.description=description;this.agentType=agentType;}

    public JSONObject toJson(){
        JSONObject o=new JSONObject();try{
            o.put("task_id",id).put("description",description).put("agent_type",agentType).put("status",status).put("progress",progress)
             .put("started_at",startedAt).put("finished_at",finishedAt).put("output",output.toString()).put("worktree",worktreePath);
            if(sessionFile!=null)o.put("session_file",sessionFile.getAbsolutePath());
            if(error!=null)o.put("error",String.valueOf(error));
        }catch(Exception ignored){}return o;
    }
}

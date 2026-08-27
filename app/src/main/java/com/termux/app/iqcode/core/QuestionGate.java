package com.termux.app.iqcode.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Interactive AskUserQuestion broker. The agent worker blocks while Android UI collects answers. */
public final class QuestionGate {
    public static final class QuestionRequest {
        public final String requestId=UUID.randomUUID().toString();
        public final JSONArray questions;
        final CountDownLatch latch=new CountDownLatch(1);
        volatile JSONObject answers;
        QuestionRequest(JSONArray questions){this.questions=questions==null?new JSONArray():questions;}
    }
    private final Map<String,QuestionRequest> pending=new ConcurrentHashMap<>();
    public JSONObject ask(JSONArray questions,java.util.function.Consumer<QuestionRequest> notify)throws InterruptedException{
        QuestionRequest r=new QuestionRequest(questions);pending.put(r.requestId,r);if(notify!=null)notify.accept(r);boolean ok=r.latch.await(30,TimeUnit.MINUTES);pending.remove(r.requestId);return ok&&r.answers!=null?r.answers:new JSONObject();
    }
    public boolean respond(String id,JSONObject answers){QuestionRequest r=pending.get(id);if(r==null)return false;r.answers=answers==null?new JSONObject():answers;r.latch.countDown();return true;}
    public void cancelAll(){for(QuestionRequest r:pending.values())r.latch.countDown();pending.clear();}
}

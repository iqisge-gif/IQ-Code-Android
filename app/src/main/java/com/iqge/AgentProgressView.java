package com.iqge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.app.iqcode.model.PlanWorkflowState;
import com.termux.app.iqcode.tasks.TaskStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Compact Claude Code-style live activity and task panel. */
public final class AgentProgressView extends LinearLayout {
    private static final long COMPLETED_HOLD_MS=5000L;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final TextView status;
    private final TextView reasoning;
    private final SphereView sphere;
    private final LinearLayout tasks;
    private boolean animating;
    private long completedHoldUntil;
    private int textColor=0xffeeeeee,mutedColor=0xff999999,accentColor=0xff9a7cff,greenColor=0xff68c58a,surfaceColor=0xff191816;
    private String fallbackStatus="正在处理…";
    private TaskStore.Snapshot snapshot;
    private PlanWorkflowState planState=PlanWorkflowState.idle();
    private long renderedTaskVersion=Long.MIN_VALUE;
    private long renderedPlanRevision=Long.MIN_VALUE;
    private boolean renderedBusy;
    private String renderedActivity="";
    private int outputBacklog;

    public AgentProgressView(Context context){
        super(context);setOrientation(HORIZONTAL);setGravity(Gravity.TOP|Gravity.CENTER_VERTICAL);setPadding(dp(14),dp(7),dp(14),dp(7));
        GradientDrawable surface=new GradientDrawable();surface.setColor(surfaceColor);surface.setCornerRadius(dp(16));setBackground(surface);
        sphere=new SphereView(context);LayoutParams sphereLp=new LayoutParams(dp(24),dp(24));sphereLp.setMargins(0,dp(1),dp(9),0);addView(sphere,sphereLp);
        LinearLayout content=new LinearLayout(context);content.setOrientation(VERTICAL);content.setGravity(Gravity.CENTER_VERTICAL);
        status=new TextView(context);status.setTextSize(12f);status.setTypeface(Typeface.DEFAULT,Typeface.BOLD);status.setGravity(Gravity.CENTER_VERTICAL);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(status,new LayoutParams(-1,dp(21)));
        reasoning=new TextView(context);reasoning.setTextSize(10.5f);reasoning.setTextColor(mutedColor);reasoning.setTypeface(Typeface.DEFAULT);reasoning.setMaxLines(2);reasoning.setEllipsize(android.text.TextUtils.TruncateAt.END);reasoning.setVisibility(GONE);reasoning.setGravity(Gravity.START);reasoning.setPadding(0,0,dp(4),dp(2));
        content.addView(reasoning,new LayoutParams(-1,-2));
        tasks=new LinearLayout(context);tasks.setOrientation(VERTICAL);tasks.setPadding(0,dp(1),0,0);content.addView(tasks,new LayoutParams(-1,-2));
        addView(content,new LayoutParams(0,-2,1));setVisibility(GONE);
    }

    public void setPalette(int text,int muted,int accent,int green){setPalette(text,muted,accent,green,surfaceColor);}
    public void setPalette(int text,int muted,int accent,int green,int surface){textColor=text;mutedColor=muted;accentColor=accent;greenColor=green;surfaceColor=surface;GradientDrawable background=new GradientDrawable();background.setColor(surfaceColor);background.setCornerRadius(dp(16));setBackground(background);reasoning.setTextColor(muted);sphere.setColor(accent);render();}

    public void setOutputBacklog(int codePoints){
        outputBacklog=Math.max(0,Math.min(240,codePoints));
        sphere.setSpeed(outputBacklog==0?.55f:Math.min(1.8f,.55f+outputBacklog*.018f));
    }

    public void setReasoningPreview(String value){
        if(Looper.myLooper()!=Looper.getMainLooper()){post(()->setReasoningPreview(value));return;}
        reasoning.setText("");
        reasoning.setVisibility(GONE);
    }

    public void update(String activity,TaskStore.Snapshot next,PlanWorkflowState plan,boolean busy){
        String nextActivity=activity==null||activity.trim().isEmpty()?"正在处理…":activity.trim();
        boolean acceptedSnapshot=next!=null&&(snapshot==null||next.version>=snapshot.version);
        boolean newlyCompleted=acceptedSnapshot&&hasNewlyCompleted(snapshot,next);
        boolean completedVisibilityChanged=false;
        if(newlyCompleted){completedHoldUntil=System.currentTimeMillis()+COMPLETED_HOLD_MS;completedVisibilityChanged=true;}
        if(busy&&!newlyCompleted&&completedHoldUntil>0L){completedHoldUntil=0L;handler.removeCallbacks(tick);completedVisibilityChanged=true;}
        if(acceptedSnapshot)snapshot=next;
        if(plan!=null&&(planState==null||plan.revision>=planState.revision))planState=plan.copy();
        boolean taskChanged=snapshot!=null&&snapshot.version!=renderedTaskVersion;
        boolean planChanged=planState!=null&&planState.revision!=renderedPlanRevision;
        boolean statusChanged=busy!=renderedBusy||!nextActivity.equals(renderedActivity)||planChanged;
        fallbackStatus=nextActivity;
        boolean hasOpenTasks=hasOpenTasks(snapshot);
        boolean holdingCompleted=completedHoldUntil>System.currentTimeMillis()&&hasCompletedTasks(snapshot);
        boolean visible=busy||holdingCompleted||(planState!=null&&(planState.isPlanning()||planState.isAwaitingApproval()));
        renderedBusy=busy;renderedActivity=nextActivity;renderedTaskVersion=snapshot==null?Long.MIN_VALUE:snapshot.version;renderedPlanRevision=planState==null?Long.MIN_VALUE:planState.revision;
        setVisibility(visible?VISIBLE:GONE);animating=busy||planState.isPlanning();sphere.setAnimating(animating);sphere.setAlpha(animating?1f:.28f);
        handler.removeCallbacks(tick);
        if(holdingCompleted&&isAttachedToWindow())handler.postDelayed(tick,Math.max(1L,completedHoldUntil-System.currentTimeMillis()));
        if(statusChanged||taskChanged||completedVisibilityChanged)renderStatus();if(taskChanged||completedVisibilityChanged)renderTasks();
    }

    public void clear(){snapshot=null;planState=PlanWorkflowState.idle();animating=false;outputBacklog=0;sphere.setSpeed(.55f);sphere.setAnimating(false);completedHoldUntil=0L;handler.removeCallbacks(tick);tasks.removeAllViews();renderedTaskVersion=Long.MIN_VALUE;renderedPlanRevision=Long.MIN_VALUE;setVisibility(GONE);}

    @Override protected void onDetachedFromWindow(){handler.removeCallbacks(tick);sphere.setAnimating(false);super.onDetachedFromWindow();}
    @Override protected void onAttachedToWindow(){
        super.onAttachedToWindow();
        if(completedHoldUntil>System.currentTimeMillis())handler.postDelayed(tick,completedHoldUntil-System.currentTimeMillis());
    }

    private final Runnable tick=new Runnable(){@Override public void run(){
        if(completedHoldUntil>0L&&System.currentTimeMillis()>=completedHoldUntil){
            completedHoldUntil=0L;
            renderTasks();renderStatus();
            if(!renderedBusy&&!hasOpenTasks(snapshot)&&!(planState!=null&&(planState.isPlanning()||planState.isAwaitingApproval())))setVisibility(GONE);
        }
    }};

    private void render(){renderStatus();renderTasks();}

    private void renderStatus(){
        String verb=currentVerb();
        boolean awaiting=planState!=null&&planState.isAwaitingApproval();
        boolean open=hasOpenTasks(snapshot);
        status.setText(verb);status.setTextColor(awaiting||animating?accentColor:open?textColor:mutedColor);
    }

    private String currentVerb(){
        if(planState!=null&&planState.isAwaitingApproval())return "计划等待确认…";
        if(planState!=null&&planState.isPlanning())return "正在规划…";
        return hasCompletedTasks(snapshot)&&completedHoldUntil>System.currentTimeMillis()?"已完成":fallbackStatus;
    }

    private void renderTasks(){
        tasks.removeAllViews();if(snapshot==null)return;
        List<JSONObject> ordered=new ArrayList<>();Set<String> completed=new HashSet<>();for(JSONObject task:snapshot.tasks)if("completed".equals(task.optString("status")))completed.add(task.optString("id"));
        for(JSONObject task:snapshot.tasks)if("in_progress".equals(task.optString("status")))ordered.add(task);
        for(JSONObject task:snapshot.tasks)if("pending".equals(task.optString("status")))ordered.add(task);
        if(hasOpenTasks(snapshot)||completedHoldUntil>System.currentTimeMillis())for(JSONObject task:snapshot.tasks)if("completed".equals(task.optString("status")))ordered.add(task);
        int shown=Math.min(5,ordered.size());
        for(int i=0;i<shown;i++)tasks.addView(taskRow(ordered.get(i),completed,i==shown-1),new LayoutParams(-1,dp(27)));
        if(ordered.size()>shown){TextView more=rowText("└  另有 "+(ordered.size()-shown)+" 项",mutedColor);tasks.addView(more,new LayoutParams(-1,dp(24)));}
    }

    private static boolean hasOpenTasks(TaskStore.Snapshot value){
        if(value==null)return false;
        for(JSONObject task:value.tasks)if(!"completed".equals(task.optString("status")))return true;
        return false;
    }

    private static boolean hasCompletedTasks(TaskStore.Snapshot value){
        if(value==null)return false;
        for(JSONObject task:value.tasks)if("completed".equals(task.optString("status")))return true;
        return false;
    }

    private static boolean hasNewlyCompleted(TaskStore.Snapshot previous,TaskStore.Snapshot next){
        if(previous==null||next==null)return false;
        Set<String> oldCompleted=new HashSet<>();
        for(JSONObject task:previous.tasks)if("completed".equals(task.optString("status")))oldCompleted.add(task.optString("id"));
        for(JSONObject task:next.tasks)if("completed".equals(task.optString("status"))&&!oldCompleted.contains(task.optString("id")))return true;
        return false;
    }

    private View taskRow(JSONObject task,Set<String> completed,boolean last){
        String state=task.optString("status","pending");String subject=task.optString("subject","任务");
        JSONArray dependencies=task.optJSONArray("blockedBy");JSONArray openDependencies=new JSONArray();if(dependencies!=null)for(int i=0;i<dependencies.length();i++){String id=dependencies.optString(i,"");if(!id.isEmpty()&&!completed.contains(id))openDependencies.put(id);}boolean blocked=openDependencies.length()>0;
        String icon=last?"└─":"├─";
        String shown="in_progress".equals(state)&&!task.optString("activeForm","").trim().isEmpty()?task.optString("activeForm"):subject;
        if(blocked&&"pending".equals(state))shown+=" · 等待 "+join(openDependencies);
        int color="completed".equals(state)?greenColor:"in_progress".equals(state)?accentColor:blocked?mutedColor:textColor;
        TextView view=rowText(icon+"  "+shown,color);
        if("completed".equals(state)){SpannableString span=new SpannableString(view.getText());span.setSpan(new StrikethroughSpan(),4,span.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);view.setText(span);view.setAlpha(.72f);}
        view.setContentDescription("任务 "+task.optString("id","")+"，"+state+"，"+shown);return view;
    }

    private TextView rowText(String value,int color){TextView t=new TextView(getContext());t.setText(value);t.setTextSize(10.7f);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setTypeface(Typeface.DEFAULT);t.setSingleLine(true);t.setEllipsize(android.text.TextUtils.TruncateAt.END);return t;}
    private static String join(JSONArray array){StringBuilder b=new StringBuilder();if(array!=null)for(int i=0;i<array.length();i++){if(b.length()>0)b.append(',');b.append('#').append(array.optString(i));}return b.toString();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private static final class SphereView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] points;
        private final Runnable frame=new Runnable(){@Override public void run(){if(!animating)return;invalidate();postDelayed(this,33L);}};
        private boolean animating;
        private long startedAt;
        private float speed=.55f;
        private int color=0xff9a7cff;

        SphereView(Context context){
            super(context);
            int count=128;points=new float[count*3];
            for(int i=0;i<count;i++){double theta=(Math.PI*2.0*i)/count;points[i*3]=(float)Math.cos(theta);points[i*3+1]=(float)Math.sin(theta);points[i*3+2]=0f;}
        }

        void setColor(int value){color=value;invalidate();}
        void setSpeed(float value){speed=Math.max(.35f,Math.min(2f,value));invalidate();}
        void setAnimating(boolean value){
            if(animating==value)return;
            animating=value;
            if(value){startedAt=System.nanoTime();postOnAnimation(frame);}
            else{removeCallbacks(frame);invalidate();}
        }

        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()/2f,r=Math.min(getWidth(),getHeight())*.38f;
            double seconds=(System.nanoTime()-(startedAt==0?System.nanoTime():startedAt))/1_000_000_000.0;
            double phase=seconds*speed,cos=Math.cos(phase),sin=Math.sin(phase);
            paint.setColor(color);
            for(int i=0;i<points.length;i+=3){float rx=(float)(points[i]*cos-points[i+1]*sin);float ry=(float)(points[i]*sin+points[i+1]*cos);float glow=(rx+1f)*.5f;float px=cx+rx*r;float py=cy-ry*r;paint.setAlpha(70+(int)(glow*185));paint.setStrokeWidth(1.2f+glow*1.5f);canvas.drawPoint(px,py,paint);}
        }
    }
}

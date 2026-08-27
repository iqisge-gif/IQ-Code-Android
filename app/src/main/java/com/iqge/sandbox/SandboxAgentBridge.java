package com.iqge.sandbox;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import top.niunaijun.blackbox.app.BActivityThread;

/**
 * Cross-process control plane used by Sandbox and Debug tools.
 *
 * UI actions are handled only by the process owning the resumed guest Activity.
 * proc_* actions are handled by the explicitly selected guest PID, which allows the Agent
 * to inspect modules/base addresses, threads, memory and load a debug .so without ptracing
 * unrelated Android processes.
 */
public final class SandboxAgentBridge {
    public static final String ACTION_CONTROL="com.iqge.sandbox.AGENT_CONTROL";
    private static final long MAX_CAPTURE_PIXELS=1_000_000L;
    private static final long MAX_SCREENSHOT_BYTES=5L*1024L*1024L;
    private static final long SCREENSHOT_MAX_AGE_MS=60L*60L*1000L;
    private static final int MAX_RETAINED_SCREENSHOTS=8;
    private static final long REQUEST_ARTIFACT_MAX_AGE_MS=2L*60L*1000L;
    private static final ExecutorService CAPTURE_IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"iq-sandbox-capture-io");t.setDaemon(true);return t;});
    private static final AtomicReference<String> ACTIVE_CAPTURE=new AtomicReference<>();
    private static volatile boolean registered;
    private SandboxAgentBridge(){}

    public static synchronized void register(Context context){
        if(registered)return; registered=true;
        IntentFilter f=new IntentFilter(ACTION_CONTROL);
        BroadcastReceiver receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){handle(c,i);}};
        if(Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,f,Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver,f);
    }

    public static JSONObject request(Context context,String action,String target,JSONObject payload,int timeoutMs)throws Exception{
        int pid=payload==null?-1:payload.optInt("pid",-1);
        return request(context,action,target,pid,payload,timeoutMs);
    }

    public static JSONObject request(Context context,String action,String target,int targetPid,JSONObject payload,int timeoutMs)throws Exception{
        File dir=resultDir(context);pruneRequestArtifacts(dir);String id=Long.toHexString(System.nanoTime())+"-"+Process.myPid();
        File result=new File(dir,id+".json"),claim=new File(dir,id+".claim");if(result.exists())result.delete();
        long end=SystemClock.uptimeMillis()+Math.max(500,timeoutMs);
        boolean screenshot="screenshot".equals(action);
        Intent intent=new Intent(ACTION_CONTROL).setPackage(context.getPackageName());
        intent.putExtra("request_id",id).putExtra("action",action).putExtra("target_package",target==null?"":target.trim())
            .putExtra("target_pid",targetPid).putExtra("deadline_uptime_ms",end).putExtra("payload",payload==null?"{}":payload.toString());
        context.sendBroadcast(intent);
        long nextSend=SystemClock.uptimeMillis()+250L;
        while(SystemClock.uptimeMillis()<end){
            if(result.isFile()){
                String text=readFile(result);result.delete();return new JSONObject(text);
            }
            long now=SystemClock.uptimeMillis();
            if(screenshot&&now>=nextSend&&!claim.isFile()){context.sendBroadcast(intent);nextSend=now+250L;}
            Thread.sleep(35);
        }
        String kind=action!=null&&action.startsWith("proc_")?"目标沙箱进程":screenshot?"截图桥（Guest Activity 尚未进入前台或截图超时）":"前台沙箱 Activity";
        return new JSONObject().put("ok",false).put("error",kind+"未响应；确认 Guest 已启动且 package 仍然有效");
    }

    private static void handle(Context context,Intent intent){
        String action=intent.getStringExtra("action");
        String id=intent.getStringExtra("request_id");
        String target=intent.getStringExtra("target_package");target=target==null?"":target.trim();
        int targetPid=intent.getIntExtra("target_pid",-1);
        long deadline=intent.getLongExtra("deadline_uptime_ms",SystemClock.uptimeMillis()+5000L);
        final String guestPkg=safeVirtualPackage();
        final int currentPid=Process.myPid();
        if(targetPid>0&&targetPid!=currentPid)return;
        if(!target.isEmpty()&&!target.equals(guestPkg))return;
        JSONObject payload; try{payload=new JSONObject(intent.getStringExtra("payload"));}catch(Throwable e){payload=new JSONObject();}
        final JSONObject p=payload;

        if(action!=null&&action.startsWith("proc_")){
            // Ignore the host/main process. Raw process debugging is only valid inside a bound guest.
            if(guestPkg.isEmpty()||guestPkg.equals(context.getPackageName()))return;
            new Thread(()->{
                JSONObject out;
                try{out=SandboxProcessDebug.dispatch(context,action,p);}
                catch(Throwable e){out=error(e);}
                writeResult(context,id,out);
            },"iq-sandbox-proc-debug").start();
            return;
        }

        final Activity a=IQSandboxEngine.resumedActivity();final String pkg=IQSandboxEngine.resumedPackage();
        if(a==null||pkg==null||pkg.isEmpty())return;
        if(!target.isEmpty()&&!target.equals(pkg))return;
        if("screenshot".equals(action)){
            try{a.runOnUiThread(()->{
                if(!screenshotActivityReady(a,pkg)||SystemClock.uptimeMillis()>=deadline)return;
                if(!claimScreenshot(context,id,deadline))return;
                if(!ACTIVE_CAPTURE.compareAndSet(null,id)){JSONObject busy=error(new IllegalStateException("截图桥正在处理另一个请求"));CAPTURE_IO.execute(()->writeResult(context,id,busy));return;}
                new CaptureSession(a,context,id,pkg,deadline).start();
            });}catch(Throwable e){SandboxDebugLog.event("截图桥调度失败: "+id+" / "+e);}
            return;
        }
        a.runOnUiThread(()->{
            JSONObject out=new JSONObject();
            try{
                out.put("ok",true).put("package",pkg).put("activity",a.getClass().getName()).put("pid",Process.myPid());
                switch(action==null?"":action){
                    case "dump_ui": out.put("ui",dumpUi(a)); break;
                    case "click_node": findByPath(a,p.optString("node","")).performClick(); break;
                    case "long_click_node": findByPath(a,p.optString("node","")).performLongClick(); break;
                    case "set_text": setText(findByPath(a,p.optString("node","")),p.optString("text","")); break;
                    case "tap": tap(a,(float)p.optDouble("x",0d),(float)p.optDouble("y",0d)); break;
                    case "swipe": swipe(a,(float)p.optDouble("x1",0d),(float)p.optDouble("y1",0d),(float)p.optDouble("x2",0d),(float)p.optDouble("y2",0d),Math.max(80,p.optInt("duration_ms",320))); break;
                    case "input_text": inputText(a,p.optString("text","")); break;
                    case "back": a.onBackPressed(); break;
                    case "finish": a.finish(); break;
                    default: out.put("ok",false).put("error","未知控制动作: "+action);
                }
            }catch(Throwable e){out=error(e);}
            writeResult(context,id,out);
        });
    }

    private static JSONObject error(Throwable e){JSONObject out=new JSONObject();try{out.put("ok",false).put("error",e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));}catch(Exception ignored){}return out;}
    private static String safeVirtualPackage(){try{String p=BActivityThread.getAppPackageName();return p==null?"":p;}catch(Throwable ignored){return "";}}

    private static JSONArray dumpUi(Activity a)throws Exception{
        JSONArray out=new JSONArray(); View root=a.getWindow().getDecorView(); walk(root,"0",out,0); return out;
    }
    private static void walk(View v,String path,JSONArray out,int depth)throws Exception{
        if(v==null||depth>40||out.length()>1800)return;
        if(SandboxFloatingController.OVERLAY_TAG.equals(v.getTag()))return;
        int[] xy=new int[2]; v.getLocationOnScreen(xy); JSONObject o=new JSONObject();
        o.put("node",path).put("class",v.getClass().getName()).put("bounds",xy[0]+","+xy[1]+","+(xy[0]+v.getWidth())+","+(xy[1]+v.getHeight()))
            .put("visible",v.getVisibility()==View.VISIBLE).put("enabled",v.isEnabled()).put("clickable",v.isClickable()).put("focusable",v.isFocusable());
        CharSequence desc=v.getContentDescription(); if(desc!=null&&desc.length()>0)o.put("content_desc",trim(desc.toString(),240));
        if(v instanceof TextView){CharSequence t=((TextView)v).getText();if(t!=null&&t.length()>0)o.put("text",trim(t.toString(),400));}
        out.put(o);
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)walk(g.getChildAt(i),path+"/"+i,out,depth+1);}
    }
    private static View findByPath(Activity a,String path){
        if(path==null||path.trim().isEmpty())throw new IllegalArgumentException("node 不能为空");
        String[] parts=path.split("/"); View current=a.getWindow().getDecorView();
        for(int i=1;i<parts.length;i++){if(!(current instanceof ViewGroup))throw new IllegalArgumentException("node 路径不是容器: "+path);int idx=Integer.parseInt(parts[i]);ViewGroup g=(ViewGroup)current;if(idx<0||idx>=g.getChildCount())throw new IllegalArgumentException("node 已失效: "+path);current=g.getChildAt(idx);}return current;
    }
    private static void setText(View v,String text){
        if(v instanceof TextView){((TextView)v).setText(text);return;}
        v.requestFocus(); EditorInfo info=new EditorInfo(); InputConnection ic=v.onCreateInputConnection(info); if(ic==null)throw new IllegalStateException("目标 View 不接受文本输入"); ic.commitText(text,1);
    }
    private static void tap(Activity a,float x,float y){View root=a.getWindow().getDecorView();long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);MotionEvent up=MotionEvent.obtain(now,now+45,MotionEvent.ACTION_UP,x,y,0);try{root.dispatchTouchEvent(down);root.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}}
    private static void swipe(Activity a,float x1,float y1,float x2,float y2,int duration){View root=a.getWindow().getDecorView();long start=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(start,start,MotionEvent.ACTION_DOWN,x1,y1,0);root.dispatchTouchEvent(down);down.recycle();int steps=12;for(int i=1;i<=steps;i++){float f=i/(float)steps;long t=start+(long)(duration*f);MotionEvent m=MotionEvent.obtain(start,t,i==steps?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,x1+(x2-x1)*f,y1+(y2-y1)*f,0);root.dispatchTouchEvent(m);m.recycle();}}
    private static void inputText(Activity a,String text){View v=a.getCurrentFocus();if(v==null)throw new IllegalStateException("当前没有获得焦点的输入控件");EditorInfo info=new EditorInfo();InputConnection ic=v.onCreateInputConnection(info);if(ic==null)throw new IllegalStateException("当前焦点不接受文本输入");ic.commitText(text,1);}
    private static boolean screenshotActivityReady(Activity activity,String pkg){
        if(activity==null||activity.isFinishing()||(Build.VERSION.SDK_INT>=17&&activity.isDestroyed())||activity.getWindow()==null)return false;
        View root=activity.getWindow().getDecorView();return activity==IQSandboxEngine.resumedActivity()&&pkg.equals(IQSandboxEngine.resumedPackage())&&root.isAttachedToWindow()&&root.getWidth()>0&&root.getHeight()>0;
    }

    /** Capture the composed Guest window. PixelCopy includes SurfaceView/GL/Vulkan layers that View.draw() misses. */
    private static final class CaptureSession {
        private final Activity activity;private final Context context;private final String id,pkg;private final long deadline;
        private final Handler main=new Handler(Looper.getMainLooper());private final AtomicBoolean completed=new AtomicBoolean(),overlayRestored=new AtomicBoolean();
        private View root,overlay;private int oldOverlayVisibility=View.VISIBLE;private HandlerThread pixelThread;
        private final Runnable timeout=()->fail(new IllegalStateException("截图内部超时；Guest 窗口可能已暂停或失效"),"timeout");

        CaptureSession(Activity activity,Context context,String id,String pkg,long deadline){this.activity=activity;this.context=context;this.id=id;this.pkg=pkg;this.deadline=deadline;}

        void start(){
            try{
                if(!activityUsable())throw new IllegalStateException("Guest Activity 已失效或不在前台");
                root=activity.getWindow().getDecorView();int w=root.getWidth(),h=root.getHeight();
                if(w<=0||h<=0)throw new IllegalStateException("窗口尚未完成布局");
                long remaining=deadline-SystemClock.uptimeMillis();if(remaining<=250L)throw new IllegalStateException("截图请求已过期");
                main.postDelayed(timeout,Math.max(100L,remaining-200L));
                overlay=root.findViewWithTag(SandboxFloatingController.OVERLAY_TAG);oldOverlayVisibility=overlay==null?View.VISIBLE:overlay.getVisibility();
                if(overlay!=null)overlay.setVisibility(View.INVISIBLE);
                root.postOnAnimation(this::captureFrame);
            }catch(Throwable e){fail(e,"start");}
        }

        private void captureFrame(){
            if(completed.get())return;
            try{
                if(!activityUsable()||root==null||!root.isAttachedToWindow())throw new IllegalStateException("截图前 Guest 窗口已离开前台");
                int sourceW=root.getWidth(),sourceH=root.getHeight();int[] size=captureSize(sourceW,sourceH);
                if(Build.VERSION.SDK_INT<26){captureFallback("api<26",sourceW,sourceH,size[0],size[1]);return;}
                Bitmap bitmap=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
                pixelThread=new HandlerThread("iq-sandbox-pixelcopy");pixelThread.start();
                try{
                    PixelCopy.request(activity.getWindow(),bitmap,result->{
                        stopPixelThread();
                        if(completed.get()){recycle(bitmap);return;}
                        if(expired()){recycle(bitmap);fail(new IllegalStateException("PixelCopy 在截止时间后返回"),"expired-pixelcopy");return;}
                        if(result==PixelCopy.SUCCESS){restoreOverlay();encode(bitmap,sourceW,sourceH,size[0],size[1],"pixelcopy",null);}
                        else{recycle(bitmap);main.post(()->captureFallback("PixelCopy error "+result,sourceW,sourceH,size[0],size[1]));}
                    },new Handler(pixelThread.getLooper()));
                }catch(Throwable e){recycle(bitmap);stopPixelThread();captureFallback(e.getClass().getSimpleName()+": "+e.getMessage(),sourceW,sourceH,size[0],size[1]);}
            }catch(Throwable e){fail(e,"capture");}
        }

        private void captureFallback(String reason,int sourceW,int sourceH,int outW,int outH){
            if(completed.get())return;
            if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->captureFallback(reason,sourceW,sourceH,outW,outH));return;}
            Bitmap bitmap=null;boolean handedOff=false;
            try{
                if(expired())throw new IllegalStateException("fallback 已超过截图截止时间");
                if(!activityUsable()||root==null||!root.isAttachedToWindow())throw new IllegalStateException("fallback 时 Guest 窗口已失效");
                bitmap=Bitmap.createBitmap(outW,outH,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
                canvas.scale(outW/(float)sourceW,outH/(float)sourceH);root.draw(canvas);restoreOverlay();
                SandboxDebugLog.event("截图桥 fallback: "+id+" / "+reason);
                encode(bitmap,sourceW,sourceH,outW,outH,"view_draw_fallback",reason);handedOff=true;
            }catch(Throwable e){fail(e,"fallback");}
            finally{if(!handedOff)recycle(bitmap);}
        }

        private void encode(Bitmap bitmap,int sourceW,int sourceH,int outW,int outH,String method,String reason){
            CAPTURE_IO.execute(()->{
                if(completed.get()){recycle(bitmap);return;}
                if(expired()){recycle(bitmap);fail(new IllegalStateException("截图编码开始前已过期"),"expired-encode");return;}
                File file=null;
                try{
                    file=saveBitmap(context,id,bitmap);
                    if(expired()){file.delete();fail(new IllegalStateException("截图编码完成时已过期"),"expired-encode");return;}
                    JSONObject out=new JSONObject().put("ok",true).put("package",pkg).put("activity",activity.getClass().getName()).put("pid",Process.myPid())
                        .put("path",file.getAbsolutePath()).put("width",outW).put("height",outH).put("source_width",sourceW).put("source_height",sourceH).put("capture_method",method);
                    if(reason!=null)out.put("fallback_reason",reason);
                    if(!finish(out,"success")&&file.isFile())file.delete();
                }catch(Throwable e){if(file!=null)file.delete();fail(e,"encode");}
            });
        }

        private boolean activityUsable(){return screenshotActivityReady(activity,pkg);}
        private boolean expired(){return SystemClock.uptimeMillis()>=deadline;}
        private void restoreOverlay(){
            if(!overlayRestored.compareAndSet(false,true))return;
            Runnable restore=()->{try{if(overlay!=null)overlay.setVisibility(oldOverlayVisibility);}catch(Throwable e){SandboxDebugLog.event("截图桥恢复浮层失败: "+id+" / "+e);}};
            if(Looper.myLooper()==Looper.getMainLooper())restore.run();else main.post(restore);
        }
        private void stopPixelThread(){HandlerThread thread=pixelThread;pixelThread=null;if(thread!=null)thread.quitSafely();}
        private void fail(Throwable error,String stage){finish(SandboxAgentBridge.error(error),stage);}
        private boolean finish(JSONObject out,String stage){
            if(!completed.compareAndSet(false,true))return false;
            main.removeCallbacks(timeout);stopPixelThread();restoreOverlay();
            CAPTURE_IO.execute(()->{writeResult(context,id,out);ACTIVE_CAPTURE.compareAndSet(id,null);SandboxDebugLog.event("截图桥 "+stage+": "+id+" / "+out.optString("error","ok"));});
            return true;
        }
    }

    private static int[] captureSize(int width,int height){
        if(width<=0||height<=0)return new int[]{1,1};long pixels=(long)width*(long)height;
        if(pixels<=MAX_CAPTURE_PIXELS)return new int[]{width,height};double scale=Math.sqrt(MAX_CAPTURE_PIXELS/(double)pixels);
        return new int[]{Math.max(1,(int)Math.floor(width*scale)),Math.max(1,(int)Math.floor(height*scale))};
    }
    private static void recycle(Bitmap bitmap){if(bitmap!=null&&!bitmap.isRecycled())try{bitmap.recycle();}catch(Throwable ignored){}}
    private static File saveBitmap(Context context,String id,Bitmap bitmap)throws Exception{
        File dir=screenshotDir(context);pruneScreenshots(dir);File tmp=new File(dir,id+".png.tmp"),dst=new File(dir,id+".png");if(tmp.exists())tmp.delete();
        try{
            try(FileOutputStream out=new FileOutputStream(tmp)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("PNG 编码失败");out.flush();out.getFD().sync();}
        }catch(Throwable e){tmp.delete();if(e instanceof Exception)throw (Exception)e;throw (Error)e;}
        finally{recycle(bitmap);}
        if(tmp.length()<=0||tmp.length()>MAX_SCREENSHOT_BYTES){long size=tmp.length();tmp.delete();throw new IllegalStateException("截图 PNG 大小异常: "+size+" bytes");}
        if(dst.exists()&&!dst.delete()){tmp.delete();throw new IllegalStateException("旧截图文件清理失败: "+dst);}
        if(!tmp.renameTo(dst)){tmp.delete();throw new IllegalStateException("截图文件原子发布失败: "+dst);}
        return dst;
    }
    private static File screenshotDir(Context c){Context host=IQSandboxEngine.hostContext();if(host==null)host=c;File dir=new File(host.getFilesDir(),"sandbox/screenshots");ensureDirectory(dir);return dir;}
    private static void pruneScreenshots(File dir){
        File[] files=dir.listFiles();if(files==null)return;long cutoff=System.currentTimeMillis()-SCREENSHOT_MAX_AGE_MS;int retained=0;
        for(File file:files){String name=file.getName();if(name.endsWith(".png.tmp")){if(file.lastModified()<cutoff)file.delete();continue;}if(!name.endsWith(".png"))continue;if(file.lastModified()<cutoff)file.delete();else retained++;}
        while(retained>=MAX_RETAINED_SCREENSHOTS){File oldest=null;files=dir.listFiles();if(files==null)break;for(File file:files)if(file.getName().endsWith(".png")&&(oldest==null||file.lastModified()<oldest.lastModified()))oldest=file;if(oldest==null||!oldest.delete())break;retained--;}
    }
    private static boolean claimScreenshot(Context c,String id,long deadline){
        if(id==null||id.isEmpty()||SystemClock.uptimeMillis()>=deadline)return false;
        try{File claim=new File(resultDir(c),id+".claim");boolean won=claim.createNewFile();if(won)claim.setLastModified(System.currentTimeMillis());return won;}
        catch(Throwable e){SandboxDebugLog.event("截图桥 claim 失败: "+id+" / "+e);JSONObject out=error(e);CAPTURE_IO.execute(()->writeResult(c,id,out));return false;}
    }
    private static File resultDir(Context c){Context host=IQSandboxEngine.hostContext();if(host==null)host=c;File dir=new File(host.getFilesDir(),"sandbox/agent-results");ensureDirectory(dir);return dir;}
    private static void ensureDirectory(File dir){if(!dir.isDirectory()&&!dir.mkdirs()&&!dir.isDirectory())throw new IllegalStateException("无法创建目录: "+dir);}
    private static void pruneRequestArtifacts(File dir){
        File[] files=dir.listFiles();if(files==null)return;long cutoff=System.currentTimeMillis()-REQUEST_ARTIFACT_MAX_AGE_MS;
        for(File file:files){String name=file.getName();if((name.endsWith(".claim")||name.endsWith(".tmp")||name.endsWith(".json"))&&file.lastModified()<cutoff)file.delete();}
    }
    private static String readFile(File f)throws Exception{try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    private static void writeResult(Context c,String id,JSONObject out){
        if(id==null||id.isEmpty())return;File tmp=null;
        try{File dir=resultDir(c);tmp=new File(dir,id+".tmp");File dst=new File(dir,id+".json");byte[] data=out.toString().getBytes(StandardCharsets.UTF_8);
            try(FileOutputStream stream=new FileOutputStream(tmp)){stream.write(data);stream.flush();stream.getFD().sync();}
            if(dst.exists()&&!dst.delete())throw new IllegalStateException("旧结果清理失败: "+dst);
            if(!tmp.renameTo(dst))throw new IllegalStateException("结果原子发布失败: "+dst);
        }catch(Throwable e){if(tmp!=null)tmp.delete();SandboxDebugLog.event("桥接结果写入失败: "+id+" / "+e);}
    }
    private static String trim(String s,int max){return s.length()<=max?s:s.substring(0,max)+"…";}
}

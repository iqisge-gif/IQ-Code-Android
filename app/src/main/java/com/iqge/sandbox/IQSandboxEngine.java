package com.iqge.sandbox;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.app.ActivityManager;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.entity.am.RunningAppProcessInfo;
import top.niunaijun.blackbox.fake.frameworks.BActivityManager;

/** One real BlackBox virtual Android runtime embedded directly in com.iqge. */
public final class IQSandboxEngine {
    public static final int USER_ID=0;
    private static final AtomicBoolean ATTACHED=new AtomicBoolean();
    private static final AtomicBoolean CREATE_STARTED=new AtomicBoolean();
    private static final AtomicBoolean CREATED=new AtomicBoolean();
    private static final CountDownLatch READY_LATCH=new CountDownLatch(1);
    private static final Object LIFECYCLE_LOCK=new Object();
    private static volatile Throwable initError;
    private static volatile Context appContext;
    private static volatile WeakReference<Activity> resumedActivity=new WeakReference<>(null);
    private static volatile String resumedPackage="";
    private IQSandboxEngine(){}

    public static void attach(Context context){
        if(!ATTACHED.compareAndSet(false,true))return;
        try{
            Context app=context.getApplicationContext()==null?context:context.getApplicationContext();
            appContext=app;
            BlackBoxCore.get().doAttachBaseContext(context,new ClientConfiguration(){
                @Override public String getHostPackageName(){return app.getPackageName();}
                @Override public boolean isHideRoot(){return SandboxSettingsStore.isRootHidden(app);}
                @Override public boolean isEnableDaemonService(){return false;}
                @Override public boolean isEnableLauncherActivity(){return false;}
                @Override public boolean isUseVpnNetwork(){return false;}
                @Override public boolean requestInstallPackage(File file,int userId){return false;}
                @Override public String getLogSenderChatId(){return null;}
            });
        }catch(Throwable e){initError=e;SandboxDebugLog.event("引擎 attach 失败: "+e);}
    }

    public static void create(){
        if(!CREATE_STARTED.compareAndSet(false,true))return;
        if(initError!=null){READY_LATCH.countDown();return;}
        try{
            SandboxDebugLog.event("IQ Sandbox create 开始: "+SandboxProcessRole.processName(appContext));
            BlackBoxCore.get().addAppLifecycleCallback(new AppLifecycleCallback(){
                @Override public void beforeMainLaunchApk(String pkg,int uid){SandboxDebugLog.event("准备启动虚拟应用: "+pkg+" user="+uid);}
                @Override public void beforeCreateApplication(String pkg,String process,Context c,int uid){SandboxDebugLog.event("创建虚拟进程: "+pkg+" / "+process);}
                @Override public void beforeApplicationOnCreate(String pkg,String process,Application a,int uid){
                    Context host=appContext==null?a:appContext;
                    if(FridaRuntimeManager.isAutoAttachEnabled(host,pkg)){
                        try{SandboxFridaBridge.load(host);SandboxDebugLog.event("Frida auto-attach 已在 Application.onCreate 前加载: "+pkg+" / "+process);}
                        catch(Throwable e){SandboxDebugLog.event("Frida auto-attach 失败: "+pkg+" / "+process+" / "+e);}
                    }
                }
                @Override public void afterApplicationOnCreate(String pkg,String process,Application a,int uid){SandboxDebugLog.event("虚拟 Application 已启动: "+pkg+" / "+process);}
                @Override public void onActivityResumed(Activity activity){
                    String pkg=virtualPackage();
                    if(pkg.isEmpty())pkg=activity.getPackageName();
                    if(pkg.isEmpty())pkg="guest";
                    resumedActivity=new WeakReference<>(activity); resumedPackage=pkg;
                    SandboxDebugLog.event("Activity resumed: "+pkg+" / "+activity.getClass().getName());
                    if (SandboxSettingsStore.isFloatingLogEnabled(appContext)) SandboxFloatingController.attach(activity,pkg);
                    else SandboxFloatingController.detach(activity);
                }
                @Override public void onActivityPaused(Activity activity){
                    Activity current=resumedActivity.get(); if(current==activity){resumedActivity=new WeakReference<>(null);resumedPackage="";}
                }
            });
            BlackBoxCore.get().doCreate();
            CREATED.set(true);
            SandboxDebugLog.event("IQ Sandbox 引擎已初始化；网络使用宿主机直连，VPN 网络模式已禁用");
        }catch(Throwable e){
            initError=e;
            SandboxDebugLog.event("引擎 create 失败: "+SandboxDebugLog.stackTrace(e));
        }finally{
            READY_LATCH.countDown();
        }
    }

    /**
     * ContentProvider is published before Application.onCreate(). A caller from the
     * IQ Code process can therefore reach :iqsandbox a few milliseconds before
     * create() runs. Schedule creation on the controller main looper and let Binder
     * callers wait for the real result instead of reporting a fake backend failure.
     */
    public static void ensureCreateScheduled(){
        if(CREATED.get()||CREATE_STARTED.get()||initError!=null)return;
        try{
            Handler h=new Handler(Looper.getMainLooper());
            h.post(IQSandboxEngine::create);
        }catch(Throwable e){
            SandboxDebugLog.event("调度沙箱 create 失败: "+e);
            create();
        }
    }

    public static void awaitReady(long timeoutMs){
        if(isReady())return;
        if(initError!=null)throw new IllegalStateException(status(),initError);
        ensureCreateScheduled();
        try{
            if(!READY_LATCH.await(Math.max(250L,timeoutMs), TimeUnit.MILLISECONDS))
                throw new IllegalStateException("引擎初始化超时（"+timeoutMs+"ms）");
        }catch(InterruptedException e){
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待引擎初始化被中断",e);
        }
        if(!isReady())throw new IllegalStateException(status(),initError);
    }

    public static boolean isReady(){return ATTACHED.get()&&CREATED.get()&&initError==null;}
    public static String status(){
        Throwable e=initError;
        if(e!=null)return "引擎异常: "+e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage());
        if(CREATED.get())return "引擎就绪 · 用户 "+USER_ID;
        return CREATE_STARTED.get()?"引擎正在初始化":"引擎等待 Application.onCreate";
    }
    public static InstallResult install(File apk){ensureReady();return BlackBoxCore.get().installPackageAsUser(apk,USER_ID);}
    public static List<ApplicationInfo> installedApplications(){ensureReady();List<ApplicationInfo> r=BlackBoxCore.get().getInstalledApplications(0,USER_ID);return r==null? Collections.emptyList():r;}
    public static List<ActivityManager.RunningAppProcessInfo> runningProcesses(String pkg){
        ensureReady();
        if(pkg==null||pkg.trim().isEmpty())return Collections.emptyList();
        try{
            RunningAppProcessInfo info=BActivityManager.get().getRunningAppProcesses(pkg.trim(),USER_ID);
            return info==null||info.mAppProcessInfoList==null?Collections.emptyList():new java.util.ArrayList<>(info.mAppProcessInfoList);
        }catch(Throwable e){throw new IllegalStateException("读取沙箱进程失败: "+e.getMessage(),e);}
    }
    public static boolean isRootHidden(){Context c=appContext;return c==null||SandboxSettingsStore.isRootHidden(c);}
    public static boolean isFloatingLogEnabled(){Context c=appContext;return c!=null&&SandboxSettingsStore.isFloatingLogEnabled(c);}
    public static boolean setFloatingLogEnabled(boolean enabled)throws Exception{
        ensureReady();
        Context c=appContext;
        if(c==null)throw new IllegalStateException("沙箱 Context 尚未就绪");
        SandboxSettingsStore.setFloatingLogEnabled(c,enabled);
        Activity a=resumedActivity.get();
        if(a!=null){if(enabled)SandboxFloatingController.attach(a,resumedPackage);else SandboxFloatingController.detach(a);}
        SandboxDebugLog.event("日志悬浮窗已"+(enabled?"开启":"关闭"));
        return SandboxSettingsStore.isFloatingLogEnabled(c);
    }
    public static boolean setRootHidden(boolean hidden)throws Exception{
        ensureReady();
        Context c=appContext;
        if(c==null)throw new IllegalStateException("沙箱 Context 尚未就绪");
        synchronized(LIFECYCLE_LOCK){
            boolean current=SandboxSettingsStore.isRootHidden(c);
            if(current==hidden)return false;
            List<ApplicationInfo> apps=installedApplications();
            SandboxGuardService.stop(c);
            for(ApplicationInfo ai:apps)BlackBoxCore.get().stopPackage(ai.packageName,USER_ID);
            long deadline=SystemClock.uptimeMillis()+1500L;
            boolean stopped=allGuestsStopped(apps);
            while(!stopped&&SystemClock.uptimeMillis()<deadline){SystemClock.sleep(50L);stopped=allGuestsStopped(apps);}
            if(!stopped)throw new IllegalStateException("仍有 Guest 进程在运行，Root 隐藏设置未变更");
            SandboxSettingsStore.setRootHidden(c,hidden);
            SandboxDebugLog.event("Root 隐藏已"+(hidden?"开启":"关闭")+"；已停止全部 Guest");
            return true;
        }
    }
    private static boolean allGuestsStopped(List<ApplicationInfo> apps){
        for(ApplicationInfo ai:apps)if(!runningProcesses(ai.packageName).isEmpty())return false;
        return true;
    }
    public static boolean launch(String pkg){
        synchronized(LIFECYCLE_LOCK){
            ensureReady();Context c=appContext;if(c!=null)SandboxGuardService.start(c);boolean ok=BlackBoxCore.get().launchApk(pkg,USER_ID);if(!ok&&c!=null)SandboxGuardService.stop(c);return ok;
        }
    }
    public static void stop(String pkg){ensureReady();BlackBoxCore.get().stopPackage(pkg,USER_ID);Context c=appContext;if(c!=null)SandboxGuardService.stop(c);}
    public static void clearData(String pkg){ensureReady();BlackBoxCore.get().clearPackage(pkg,USER_ID);}
    public static void uninstall(String pkg){ensureReady();BlackBoxCore.get().uninstallPackageAsUser(pkg,USER_ID);Context c=appContext;if(c!=null)SandboxGuardService.stop(c);}
    public static boolean hasAllFilesAccess(){ensureReady();return BlackBoxCore.get().hasAllFilesAccess();}
    public static void requestAllFilesAccess(Activity a){ensureReady();BlackBoxCore.get().requestAllFilesAccess(a);}
    public static Activity resumedActivity(){return resumedActivity.get();}
    public static String resumedPackage(){return resumedPackage;}
    static Context hostContext(){return appContext;}

    private static String virtualPackage(){
        try{String p=BActivityThread.getAppPackageName();return p==null?"":p;}catch(Throwable ignored){return "";}
    }
    private static void ensureReady(){if(!isReady())throw new IllegalStateException(status());}
}

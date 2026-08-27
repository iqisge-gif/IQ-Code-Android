package com.iqge.sandbox;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Shared cross-process event log for the sandbox host and virtual app processes. */
public final class SandboxDebugLog {
    private static final String TAG = "IQSandbox";
    private static final int MAX_TEXT = 420_000;
    private static final Object LOCK = new Object();
    private static volatile File eventFile;
    private SandboxDebugLog() {}

    public static void init(Context context) {
        File dir = new File(context.getFilesDir(), "sandbox/debug");
        if (!dir.exists()) dir.mkdirs();
        eventFile = new File(dir, "events.log");
    }

    public static void event(String message) {
        String line = timestamp() + " pid=" + Process.myPid() + "  " + message;
        Log.i(TAG, message);
        File target = eventFile;
        if (target == null) return;
        synchronized (LOCK) {
            try (FileOutputStream out = new FileOutputStream(target, true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {}
        }
    }

    public static String snapshot(Context context) {
        StringBuilder text = new StringBuilder();
        text.append("IQ Sandbox 调试快照\n")
            .append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            .append("Host: ").append(context.getPackageName()).append(" uid=").append(Process.myUid()).append('\n')
            .append("Time: ").append(timestamp()).append("\n\n===== IQ SANDBOX EVENTS =====\n")
            .append(readEventTail()).append("\n===== APP-UID LOGCAT =====\n").append(readLogcat());
        return tail(text.toString(), MAX_TEXT);
    }

    public static void clear() {
        synchronized (LOCK) { File f=eventFile; if(f!=null&&f.exists()) f.delete(); }
        event("沙箱日志已清空");
    }

    public static String stackTrace(Throwable error) {
        java.io.StringWriter b=new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(b)); return b.toString();
    }

    private static String readEventTail() {
        File target=eventFile; if(target==null||!target.exists()) return "(暂无事件)\n";
        synchronized (LOCK) {
            try (RandomAccessFile input=new RandomAccessFile(target,"r")) {
                long start=Math.max(0,input.length()-MAX_TEXT/2L); input.seek(start);
                byte[] data=new byte[(int)(input.length()-start)]; input.readFully(data);
                String v=new String(data,StandardCharsets.UTF_8); int first=start==0?0:v.indexOf('\n')+1;
                return v.substring(Math.max(0,first));
            } catch(Throwable e){ return "读取事件失败: "+e+"\n"; }
        }
    }

    private static String readLogcat() {
        StringBuilder out=new StringBuilder();
        try {
            java.lang.Process p=new ProcessBuilder("logcat","-d","-v","threadtime").redirectErrorStream(true).start();
            try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
                String line; while((line=r.readLine())!=null){ out.append(line).append('\n'); if(out.length()>MAX_TEXT)out.delete(0,out.length()-MAX_TEXT); }
            } p.waitFor();
        } catch(Throwable e){out.append("读取 logcat 失败: ").append(e).append('\n');}
        return out.length()==0?"(暂无 logcat)\n":out.toString();
    }

    private static String timestamp(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date());}
    private static String tail(String v,int max){return v.length()<=max?v:v.substring(v.length()-max);}
}

package com.iqge.sandbox;

import android.content.Context;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.storage.ApiSettingsStore;
import com.termux.app.iqcode.termux.TermuxShellExecutor;
import com.termux.app.iqcode.tools.IQDebugTool;
import com.termux.app.iqcode.tools.IQSandboxTool;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Same-UID file bridge that makes IQ Sandbox/Debug callable from the embedded Termux shell.
 * It does not require adb, an exported Android component, a network socket, or root.
 */
public final class SandboxTermuxBridge {
    private static final AtomicBoolean STARTED=new AtomicBoolean();
    private static volatile Context app;
    private SandboxTermuxBridge(){}

    public static void start(Context context){
        app=context.getApplicationContext();
        if(!isMainHostProcess(app))return;
        ensureDirs(app); ensureCliInstalled(app);
        if(!STARTED.compareAndSet(false,true))return;
        Thread t=new Thread(SandboxTermuxBridge::loop,"iq-termux-sandbox-bridge");t.setDaemon(true);t.start();
    }

    public static void ensureCliInstalled(Context context){
        try{
            File bin=new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);if(!bin.isDirectory())return;
            writeScript(new File(bin,"iqsandbox"),"Sandbox");writeScript(new File(bin,"iqdebug"),"Debug");
        }catch(Throwable e){SandboxDebugLog.event("Termux 沙箱 CLI 安装失败: "+e);}
    }

    public static String bridgeDir(Context context){return new File(context.getFilesDir(),"sandbox/termux-bridge").getAbsolutePath();}

    private static void loop(){
        while(true){
            try{
                Context c=app;if(c==null){Thread.sleep(100);continue;}ensureDirs(c);ensureCliInstalled(c);
                File reqDir=new File(bridgeDir(c),"requests");File[] files=reqDir.listFiles((d,n)->n.endsWith(".req"));
                if(files!=null&&files.length>0){Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(File f:files)handle(c,f);}
                Thread.sleep(files!=null&&files.length>0?15:60);
            }catch(InterruptedException e){Thread.currentThread().interrupt();return;}catch(Throwable e){SandboxDebugLog.event("Termux 沙箱桥异常: "+e);try{Thread.sleep(250);}catch(InterruptedException x){Thread.currentThread().interrupt();return;}}
        }
    }

    private static void handle(Context c,File req){
        String id=req.getName().substring(0,req.getName().length()-4);File resDir=new File(bridgeDir(c),"responses");File response=new File(resDir,id+".res");
        try{
            String text=read(req);String[] lines=text.split("\\n",4);String tool=lines.length>0?lines[0].trim():"Sandbox";String action=lines.length>1?lines[1].trim():"status";String target=lines.length>2?lines[2].trim():"";String payloadText=lines.length>3?lines[3].trim():"{}";
            JSONObject input;try{input=payloadText.isEmpty()?new JSONObject():new JSONObject(payloadText);}catch(Throwable e){throw new IllegalArgumentException("payload 不是有效 JSON: "+e.getMessage());}
            input.put("action",action);
            if(!target.isEmpty()){
                if("Debug".equalsIgnoreCase(tool)&&target.matches("[0-9]+"))input.put("pid",Integer.parseInt(target));
                else input.put("package",target);
            }
            ToolExecutionResult result;
            if("Debug".equalsIgnoreCase(tool)){
                // Reuse the same persisted Root toggle as the Agent so terminal and Agent have one permission model.
                SessionConfig cfg=new ApiSettingsStore(c).load();
                if(cfg.projectDirectory==null||cfg.projectDirectory.trim().isEmpty())cfg.projectDirectory=TermuxConstants.TERMUX_HOME_DIR_PATH;
                result=new IQDebugTool(c,new TermuxShellExecutor(c)).execute(cfg,input);
            }else{
                result=new IQSandboxTool(c).execute(new SessionConfig(),input);
            }
            writeResponse(response,result.isError?1:0,result.content);
        }catch(Throwable e){writeResponse(response,1,e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));}
        finally{try{req.delete();}catch(Throwable ignored){}}
    }

    private static boolean isMainHostProcess(Context c){try{String cmd=read(new File("/proc/self/cmdline")).replace('\0',' ').trim();return c.getPackageName().equals(cmd);}catch(Throwable e){return true;}}
    private static void ensureDirs(Context c){new File(bridgeDir(c),"requests").mkdirs();new File(bridgeDir(c),"responses").mkdirs();}
    private static void writeResponse(File dst,int code,String body){try{File tmp=new File(dst.getParentFile(),dst.getName()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write((Integer.toString(code)+"\n"+(body==null?"":body)).getBytes(StandardCharsets.UTF_8));}if(!tmp.renameTo(dst)){try(FileOutputStream out=new FileOutputStream(dst)){out.write((Integer.toString(code)+"\n"+(body==null?"":body)).getBytes(StandardCharsets.UTF_8));}tmp.delete();}}catch(Throwable ignored){}}
    private static String read(File f)throws Exception{try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}}

    private static void writeScript(File file,String tool)throws Exception{
        String root="/data/user/0/com.iqge/files/sandbox/termux-bridge";
        String script="#!/data/data/com.iqge/files/usr/bin/bash\n"+
            "set -u\n"+
            "TOOL='"+tool+"'\n"+
            "ROOT=\"${IQGE_SANDBOX_BRIDGE_DIR:-"+root+"}\"\n"+
            "ACTION=\"${1:-"+("Debug".equals(tool)?"process_list":"status")+"}\"\n"+
            "TARGET=\"${2:-}\"\n"+
            "PAYLOAD=\"${3:-{}}\"\n"+
            "ID=\"$(date +%s 2>/dev/null)-$$-${RANDOM:-0}\"\n"+
            "REQ=\"$ROOT/requests/$ID.req\"; RES=\"$ROOT/responses/$ID.res\"\n"+
            "mkdir -p \"$ROOT/requests\" \"$ROOT/responses\"\n"+
            "TMP=\"$REQ.tmp.$$\"\n"+
            "printf '%s\\n%s\\n%s\\n%s' \"$TOOL\" \"$ACTION\" \"$TARGET\" \"$PAYLOAD\" >\"$TMP\" && mv \"$TMP\" \"$REQ\"\n"+
            "i=0; while [ $i -lt 240 ]; do if [ -f \"$RES\" ]; then CODE=$(head -n 1 \"$RES\" 2>/dev/null || echo 1); tail -n +2 \"$RES\" 2>/dev/null; rm -f \"$RES\"; case \"$CODE\" in 0) exit 0;; *) exit 1;; esac; fi; sleep 0.05; i=$((i+1)); done\n"+
            "rm -f \"$REQ\" \"$RES\"; echo 'IQ Sandbox bridge timeout' >&2; exit 124\n";
        byte[] data=script.getBytes(StandardCharsets.UTF_8);
        if(file.isFile()){
            try{if(read(file).equals(script)){file.setExecutable(true,false);return;}}catch(Throwable ignored){}
        }
        try(FileOutputStream out=new FileOutputStream(file)){out.write(data);}file.setReadable(true,false);file.setWritable(true,true);file.setExecutable(true,false);
    }
}

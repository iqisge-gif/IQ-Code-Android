package com.termux.app.iqcode.tools;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Base64;

import com.iqge.sandbox.SandboxAgentBridge;
import com.iqge.sandbox.SandboxHostClient;
import com.iqge.sandbox.SandboxDebugLog;
import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;


/** Agent-facing virtual Android control surface. */
public final class IQSandboxTool implements IQTool {
    private static final int MAX_SCREENSHOT_BYTES=5*1024*1024;
    private final Context context;
    public IQSandboxTool(Context context){this.context=context.getApplicationContext();}
    @Override public String name(){return "Sandbox";}
    @Override public String description(){return "Control IQ Sandbox apps/UI; install targets sandbox only, never the phone.";}
    @Override public PermissionKind permissionKind(){return PermissionKind.SYSTEM;}
    @Override public JSONObject inputSchema(){
        try{JSONObject p=new JSONObject();
            p.put("action",ToolSchemas.string("status, list, install, launch, stop, clear_data, uninstall, debug_snapshot, clear_debug, dump_ui, screenshot, click_node, long_click_node, set_text, tap, swipe, input_text, back"));
            p.put("path",ToolSchemas.string("Absolute .apk path for install."));
            p.put("package",ToolSchemas.string("Sandbox package name. Required for launch/stop/clear_data/uninstall and recommended for UI control."));
            p.put("node",ToolSchemas.string("Node path returned by dump_ui, for example 0/1/0."));
            p.put("text",ToolSchemas.string("Text for set_text or input_text."));
            p.put("x",ToolSchemas.integer("tap X coordinate in screenshot/window pixels.",0));
            p.put("y",ToolSchemas.integer("tap Y coordinate in screenshot/window pixels.",0));
            p.put("x1",ToolSchemas.integer("swipe start X.",0));p.put("y1",ToolSchemas.integer("swipe start Y.",0));p.put("x2",ToolSchemas.integer("swipe end X.",0));p.put("y2",ToolSchemas.integer("swipe end Y.",0));
            p.put("duration_ms",ToolSchemas.integer("swipe duration in milliseconds.",80));
            return ToolSchemas.object(p,"action");
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    @Override public ToolExecutionResult execute(SessionConfig config,JSONObject in)throws Exception{
        String action=in.optString("action","").trim().toLowerCase();
        try{
            switch(action){
                case "status": { JSONObject r=host("status",new JSONObject()); return result(r,"IQ Sandbox 状态"); }
                case "list": return ToolExecutionResult.ok(listApps());
                case "install": return install(in.optString("path",""));
                case "launch": {String p=reqPkg(in);JSONObject r=host("launch",new JSONObject().put("package",p));return r.optBoolean("ok")&&r.optBoolean("success")?ToolExecutionResult.ok("已启动沙箱应用: "+p):ToolExecutionResult.error(r.optString("error",r.optString("message","沙箱没有找到可启动 Activity: "+p)));}
                case "stop": {String p=reqPkg(in);return result(host("stop",new JSONObject().put("package",p)),"已停止沙箱应用: "+p);}
                case "clear_data": {String p=reqPkg(in);return result(host("clear_data",new JSONObject().put("package",p)),"已清除沙箱数据: "+p);}
                case "uninstall": {String p=reqPkg(in);return result(host("uninstall",new JSONObject().put("package",p)),"已从沙箱卸载: "+p);}
                case "debug_snapshot": return ToolExecutionResult.ok(SandboxDebugLog.snapshot(context));
                case "clear_debug": SandboxDebugLog.clear();return ToolExecutionResult.ok("沙箱调试日志已清空");
                case "dump_ui": case "screenshot": case "back": case "click_node": case "long_click_node": case "set_text": case "tap": case "swipe": case "input_text":
                    return control(action,in);
                default:return ToolExecutionResult.error("未知 Sandbox action: "+action);
            }
        }catch(Throwable e){SandboxDebugLog.event("Agent Sandbox 失败: "+action+" / "+e);return ToolExecutionResult.error("Sandbox "+action+" 失败: "+e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));}
    }
    private ToolExecutionResult install(String path)throws Exception{
        if(path==null||path.trim().isEmpty())return ToolExecutionResult.error("install 需要 path");File apk=new File(path);if(!apk.isFile())return ToolExecutionResult.error("APK 不存在: "+path);
        PackageInfo pi=context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_ACTIVITIES);if(pi==null||pi.packageName==null)return ToolExecutionResult.error("不是有效普通 APK: "+path);
        if(context.getPackageName().equals(pi.packageName))return ToolExecutionResult.error("不能把 IQ Code 自己安装进 IQ Sandbox");
        JSONObject r=host("install",new JSONObject().put("path",apk.getAbsolutePath()));
        if(!r.optBoolean("ok"))return ToolExecutionResult.error(r.optString("error",r.toString()));
        return r.optBoolean("success")?ToolExecutionResult.ok("已安装到 IQ Sandbox: "+r.optString("package",pi.packageName)):ToolExecutionResult.error("沙箱安装失败: "+r.optString("message",r.toString()));
    }
    private String listApps()throws Exception{JSONObject r=host("list",new JSONObject());if(!r.optBoolean("ok"))throw new IllegalStateException(r.optString("error",r.toString()));JSONArray apps=r.optJSONArray("apps");StringBuilder b=new StringBuilder();int n=apps==null?0:apps.length();b.append("IQ Sandbox 已安装 ").append(n).append(" 个应用\n");for(int i=0;i<n;i++){String pkg=apps.optJSONObject(i).optString("package","");b.append("- ").append(pkg).append('\n');}return b.toString();}
    private JSONObject host(String action,JSONObject payload){return SandboxHostClient.call(context,action,payload);}
    private static ToolExecutionResult result(JSONObject r,String success){return r.optBoolean("ok",false)?ToolExecutionResult.ok(success+"\n"+r.toString()):ToolExecutionResult.error(r.optString("error",r.toString()));}
    private ToolExecutionResult control(String action,JSONObject in)throws Exception{
        JSONObject payload=new JSONObject();if(in.has("node"))payload.put("node",in.optString("node",""));if(in.has("text"))payload.put("text",in.optString("text",""));
        for(String k:new String[]{"x","y","x1","y1","x2","y2","duration_ms"})if(in.has(k))payload.put(k,in.optInt(k));
        JSONObject r=SandboxAgentBridge.request(context,action,in.optString("package",""),payload,"screenshot".equals(action)?12000:4500);
        if(!r.optBoolean("ok",false))return ToolExecutionResult.error(r.optString("error",r.toString()));
        String display=r.toString(2);
        return "screenshot".equals(action)?screenshotResult(r,display):ToolExecutionResult.ok(display);
    }
    private ToolExecutionResult screenshotResult(JSONObject result,String display){
        try{
            File root=new File(context.getFilesDir(),"sandbox/screenshots").getCanonicalFile();File image=new File(result.optString("path","")).getCanonicalFile();
            if(!image.getAbsolutePath().startsWith(root.getAbsolutePath()+File.separator)||!image.getName().endsWith(".png"))throw new IllegalArgumentException("截图路径不在沙箱私有目录");
            if(!image.isFile()||image.length()<=0)throw new IllegalStateException("截图文件不存在或为空");
            byte[] bytes=readScreenshot(image);String data=Base64.encodeToString(bytes,Base64.NO_WRAP);
            JSONObject source=new JSONObject().put("type","base64").put("media_type","image/png").put("data",data);
            JSONArray additional=new JSONArray().put(new JSONObject().put("type","image").put("source",source).put("name",image.getName()));
            return ToolExecutionResult.okWithAdditionalContent(display,additional);
        }catch(Throwable e){SandboxDebugLog.event("截图像素桥接失败: "+e);return ToolExecutionResult.error("截图已生成，但图像桥接失败: "+e.getMessage()+"\n"+display);}
    }
    private static byte[] readScreenshot(File image)throws Exception{
        long length=image.length();if(length<=0||length>MAX_SCREENSHOT_BYTES)throw new IllegalStateException("截图大小超出 5 MiB 限制: "+length);
        byte[] bytes=new byte[(int)length];int offset=0;
        try(FileInputStream in=new FileInputStream(image)){while(offset<bytes.length){int n=in.read(bytes,offset,bytes.length-offset);if(n<0)break;offset+=n;}if(offset!=bytes.length||in.read()!=-1)throw new IllegalStateException("截图读取期间发生变化");}
        if(bytes.length<8||(bytes[0]&0xff)!=0x89||bytes[1]!='P'||bytes[2]!='N'||bytes[3]!='G')throw new IllegalStateException("截图不是有效 PNG");return bytes;
    }
    private static String reqPkg(JSONObject in){String p=in.optString("package","").trim();if(p.isEmpty())throw new IllegalArgumentException("需要 package");return p;}
}

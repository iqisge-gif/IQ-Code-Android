package com.termux.app.iqcode.tools;

import android.app.ActivityManager;
import android.content.Context;

import com.iqge.sandbox.FridaRuntimeManager;
import com.iqge.sandbox.SandboxAgentBridge;
import com.iqge.sandbox.SandboxHostClient;
import com.iqge.sandbox.SandboxDebugLog;
import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;
import com.termux.app.iqcode.termux.TermuxShellExecutor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Agent-facing process/native debugging surface for IQ Sandbox plus opt-in rooted host inspection. */
public final class IQDebugTool implements IQTool {
    private final Context context;
    private final TermuxShellExecutor shell;
    public IQDebugTool(Context context, TermuxShellExecutor shell){this.context=context.getApplicationContext();this.shell=shell;}

    @Override public String name(){return "Debug";}
    @Override public String description(){return "Inspect/debug IQ Sandbox processes via Frida; scope=host requires Root.";}
    @Override public PermissionKind permissionKind(){return PermissionKind.SYSTEM;}

    @Override public JSONObject inputSchema(){
        try{
            JSONObject p=new JSONObject();
            p.put("scope",ToolSchemas.string("sandbox (default) or host."));
            p.put("action",ToolSchemas.string("process_list, process_info, maps, modules, threads, memory_read, memory_write, load_library, thread_dump, gc, signal, frida_runtime_status, frida_install, frida_auto_attach, frida_status, frida_load, frida_modules, frida_ranges, frida_read, frida_write, frida_scan, frida_protect, frida_patch, frida_export, frida_watch, frida_watch_stop, frida_watch_stop_all, frida_eval, frida_events, frida_detach_all"));
            p.put("package",ToolSchemas.string("Guest package for sandbox scope, or Android package for host PID resolution."));
            p.put("pid",ToolSchemas.integer("Concrete process id. For sandbox this must be one of the package's IQ Sandbox guest PIDs.",1));
            p.put("filter",ToolSchemas.string("Optional substring filter for maps/modules."));
            p.put("address",ToolSchemas.string("Hex virtual address. frida_scan uses address together with size when module is omitted."));
            p.put("size",ToolSchemas.integer("Byte count. memory_read is capped at 65536; frida_scan accepts a larger scan window split across current readable mappings.",1));
            p.put("data",ToolSchemas.string("Hex or base64 bytes for memory_write."));
            p.put("format",ToolSchemas.string("hex (default) or base64."));
            p.put("path",ToolSchemas.string("Absolute .so path in IQ Code private storage for sandbox load_library."));
            p.put("signal",ToolSchemas.integer("Linux signal number for rooted host signal action.",1));
            p.put("max_lines",ToolSchemas.integer("Maximum maps lines.",1));
            p.put("max_modules",ToolSchemas.integer("Maximum module rows.",1));
            p.put("pattern",ToolSchemas.string("Frida memory scan pattern, e.g. 13 37 ?? ff. Scans only current readable mappings and may return partial matches with complete=false and errors."));
            p.put("module",ToolSchemas.string("Frida module name used for module-scoped scan/export. For frida_scan, module is mutually exclusive with address/size."));
            p.put("protection",ToolSchemas.string("Frida memory protection such as r--, rw-, r-x or rwx."));
            p.put("name",ToolSchemas.string("Export/symbol name for frida_export."));
            p.put("script",ToolSchemas.string("JavaScript body for frida_eval inside the selected IQ Sandbox Guest. Memory.scanSync is translated to a bounded async scan; prefer Debug frida_scan or await IQ.scan(options) IQ.emit(value) appends events; IQ.hooks retains Interceptor handles."));
            p.put("max",ToolSchemas.integer("Maximum Frida rows/matches. frida_scan is hard-capped at 2048; default 256 to prevent hit explosion.",1));
            p.put("chunk_size",ToolSchemas.integer("frida_scan chunk bytes. Runtime clamps to 64 KiB..8 MiB; default 4 MiB so long scans yield between chunks.",65536));
            p.put("max_chars",ToolSchemas.integer("Maximum Frida event log characters.",1));
            p.put("timeout_ms",ToolSchemas.integer("Frida command timeout in milliseconds.",1000));
            p.put("enabled",ToolSchemas.bool("Enable/disable Frida auto-attach before Guest Application.onCreate for frida_auto_attach."));
            p.put("volatile",ToolSchemas.bool("For frida_read/frida_write. Defaults true and uses Frida volatile access for safer live-process memory I/O."));
            p.put("watch_id",ToolSchemas.string("Stable identifier for frida_watch/frida_watch_stop."));
            p.put("interval_ms",ToolSchemas.integer("Memory watch polling interval; 20-60000 ms.",20));
            return ToolSchemas.object(p,"action");
        }catch(Exception e){throw new IllegalStateException(e);}
    }

    @Override public ToolExecutionResult execute(SessionConfig config,JSONObject in)throws Exception{
        String scope=in.optString("scope","sandbox").trim().toLowerCase(Locale.US);
        String action=in.optString("action","").trim().toLowerCase(Locale.US);
        if(action.isEmpty())return ToolExecutionResult.error("Debug action 不能为空");
        try{
            if("host".equals(scope))return host(config,action,in);
            if(!"sandbox".equals(scope)&&!scope.isEmpty())return ToolExecutionResult.error("未知 Debug scope: "+scope);
            return sandbox(action,in);
        }catch(Throwable e){SandboxDebugLog.event("Debug 失败: "+scope+"/"+action+" / "+e);return ToolExecutionResult.error("Debug "+scope+"/"+action+" 失败: "+e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));}
    }

    private ToolExecutionResult sandbox(String action,JSONObject in)throws Exception{
        if("process_list".equals(action))return ToolExecutionResult.ok(sandboxProcessList(in.optString("package","")).toString(2));
        if("frida_runtime_status".equals(action))return ToolExecutionResult.ok(FridaRuntimeManager.status(context).toString(2));
        if("frida_install".equals(action))return ToolExecutionResult.ok(FridaRuntimeManager.install(context,shell,com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH).toString(2));
        String pkg=in.optString("package","").trim();
        if("frida_auto_attach".equals(action)){
            if(pkg.isEmpty())return ToolExecutionResult.error("frida_auto_attach 需要 package");
            boolean enabled=in.optBoolean("enabled",true);
            JSONObject state=FridaRuntimeManager.setAutoAttach(context,pkg,enabled);
            if(enabled&&FridaRuntimeManager.isInstalled(context)){
                try{int livePid=resolveSandboxPid(pkg,in.optInt("pid",-1));JSONObject rr=SandboxAgentBridge.request(context,"proc_frida_load",pkg,livePid,new JSONObject().put("pid",livePid),15000);state.put("live_load",rr);}catch(Throwable e){state.put("live_load","deferred: "+e.getMessage());}
            }
            return ToolExecutionResult.ok(state.toString(2));
        }
        if(pkg.isEmpty())return ToolExecutionResult.error("sandbox 调试需要 package；先启动 Guest 或传入 package");
        int pid=resolveSandboxPid(pkg,in.optInt("pid",-1));
        String bridgeAction;
        switch(action){
            case "process_info": bridgeAction="proc_info"; break;
            case "maps": bridgeAction="proc_maps"; break;
            case "modules": bridgeAction="proc_modules"; break;
            case "threads": bridgeAction="proc_threads"; break;
            case "memory_read": bridgeAction="proc_memory_read"; break;
            case "memory_write": bridgeAction="proc_memory_write"; break;
            case "load_library": bridgeAction="proc_load_library"; break;
            case "thread_dump": bridgeAction="proc_thread_dump"; break;
            case "gc": bridgeAction="proc_gc"; break;
            case "frida_status": bridgeAction="proc_frida_status"; break;
            case "frida_load": bridgeAction="proc_frida_load"; break;
            case "frida_events": bridgeAction="proc_frida_events"; break;
            case "frida_modules": case "frida_ranges": case "frida_read": case "frida_write": case "frida_scan": case "frida_protect": case "frida_patch": case "frida_export": case "frida_watch": case "frida_watch_stop": case "frida_watch_stop_all": case "frida_eval": case "frida_detach_all": bridgeAction="proc_frida_command"; break;
            case "signal": return ToolExecutionResult.error("sandbox signal 请使用 Sandbox stop；原始 signal 不直接开放，避免误杀整个 IQ Code 虚拟进程池");
            default:return ToolExecutionResult.error("未知 sandbox Debug action: "+action);
        }
        JSONObject payload=copyPayload(in); payload.put("pid",pid);
        if("proc_frida_command".equals(bridgeAction)){
            int requestedTimeoutMs=Math.max(1000,Math.min(120000,in.optInt("timeout_ms",("frida_scan".equals(action)||"frida_eval".equals(action))?60000:12000)));
            int runtimeTimeoutMs=Math.min(118000,requestedTimeoutMs);
            int commandTimeoutMs=Math.min(120000,runtimeTimeoutMs+1500);
            JSONObject fridaPayload=fridaPayload(in);
            fridaPayload.put("timeout_ms",runtimeTimeoutMs);
            payload.put("op",fridaOp(action));
            payload.put("frida_payload",fridaPayload);
            payload.put("timeout_ms",commandTimeoutMs);
        }
        JSONObject r=SandboxAgentBridge.request(context,bridgeAction,pkg,pid,payload,("memory_write".equals(action)||"load_library".equals(action)||action.startsWith("frida_"))?Math.max(8000,payload.optInt("timeout_ms",12000)+2000):5000);
        return r.optBoolean("ok",false)?ToolExecutionResult.ok(r.toString(2)):ToolExecutionResult.error(r.optString("error",r.toString()));
    }

    private JSONArray sandboxProcessList(String pkgFilter)throws Exception{
        JSONObject payload=new JSONObject();if(pkgFilter!=null&&!pkgFilter.trim().isEmpty())payload.put("package",pkgFilter.trim());
        JSONObject r=SandboxHostClient.call(context,"process_list",payload);
        if(!r.optBoolean("ok",false))throw new IllegalStateException(r.optString("error",r.toString()));
        JSONArray a=r.optJSONArray("processes");return a==null?new JSONArray():a;
    }
    private int resolveSandboxPid(String pkg,int requested)throws Exception{
        JSONArray list=sandboxProcessList(pkg);
        if(list.length()==0)throw new IllegalStateException("沙箱应用当前没有运行进程: "+pkg);
        if(requested>0){for(int i=0;i<list.length();i++){JSONObject p=list.optJSONObject(i);if(p!=null&&p.optInt("pid",-1)==requested)return requested;}throw new SecurityException("PID "+requested+" 不属于当前 IQ Sandbox 包 "+pkg);}
        for(int i=0;i<list.length();i++){JSONObject p=list.optJSONObject(i);if(p!=null&&pkg.equals(p.optString("process","")))return p.optInt("pid",-1);}
        return list.optJSONObject(0).optInt("pid",-1);
    }

    private ToolExecutionResult host(SessionConfig config,String action,JSONObject in)throws Exception{
        if(config==null||!config.rootExecutionEnabled)return ToolExecutionResult.error("host 进程调试需要先在 IQ Code 设置中开启 Agent Root");
        if("memory_read".equals(action)||"memory_write".equals(action)||"load_library".equals(action))
            return ToolExecutionResult.error("真机 raw memory/远程库注入不由 Debug 自动执行。该能力仅对 IQ Sandbox Guest 内置开放；如确实要调试自有真机进程，请显式使用 Root + 你安装的 lldb/gdb/frida 工具链。");
        if("process_list".equals(action))return rootCommand("ps -A -o PID,UID,NAME,ARGS 2>/dev/null || ps -A",config.projectDirectory,20_000);
        int pid=resolveHostPid(config,in);
        switch(action){
            case "process_info": return rootCommand("printf '%s\\n' '--- status ---'; cat /proc/"+pid+"/status; printf '%s\\n' '--- cmdline ---'; tr '\\000' ' ' </proc/"+pid+"/cmdline; echo; printf '%s\\n' '--- exe ---'; readlink /proc/"+pid+"/exe || true",config.projectDirectory,20_000);
            case "maps": {
                String filter=in.optString("filter","").trim(); String cmd="cat /proc/"+pid+"/maps";
                if(!filter.isEmpty())cmd+=" | grep -F -- "+q(filter)+" || true";return rootCommand(cmd,config.projectDirectory,20_000);
            }
            case "modules": {
                ToolExecutionResult raw=rootCommand("cat /proc/"+pid+"/maps",config.projectDirectory,20_000); if(raw.exitCode!=0)return raw;
                return ToolExecutionResult.ok(hostModules(raw.content,in.optString("filter","")).toString(2));
            }
            case "threads": return rootCommand("for d in /proc/"+pid+"/task/[0-9]*; do [ -d \"$d\" ] || continue; printf '%s ' \"${d##*/}\"; cat \"$d/comm\" 2>/dev/null || echo '?'; done",config.projectDirectory,20_000);
            case "thread_dump": return rootCommand("kill -3 "+pid+"; echo 'SIGQUIT sent to PID "+pid+"; inspect logcat/tombstone for runtime dump'",config.projectDirectory,20_000);
            case "gc": return ToolExecutionResult.error("host gc 没有通用安全接口；对沙箱 Guest 使用 scope=sandbox action=gc");
            case "signal": {int sig=Math.max(1,Math.min(64,in.optInt("signal",3)));return rootCommand("kill -"+sig+" "+pid,config.projectDirectory,20_000);}
            default:return ToolExecutionResult.error("未知 host Debug action: "+action);
        }
    }

    private int resolveHostPid(SessionConfig config,JSONObject in)throws Exception{
        int pid=in.optInt("pid",-1);if(pid>1)return pid;
        String pkg=in.optString("package","").trim();if(pkg.isEmpty())throw new IllegalArgumentException("host 调试需要 pid 或 package");
        TermuxShellExecutor.Result r=shell.executeAsRoot("pidof "+q(pkg)+" 2>/dev/null | awk '{print $1}'",config.projectDirectory,10_000,null);
        String s=r.stdout.trim(); if(r.exitCode!=0||s.isEmpty())throw new IllegalStateException("找不到真机进程: "+pkg);
        String first=s.split("\\s+")[0];return Integer.parseInt(first);
    }

    private ToolExecutionResult rootCommand(String cmd,String cwd,int timeout)throws Exception{
        String verified="if [ \"$(id -u)\" != 0 ]; then echo '[IQGE] su did not grant uid 0' >&2; exit 126; fi; "+cmd;
        TermuxShellExecutor.Result r=shell.executeAsRoot(verified,cwd,timeout,null);return ToolExecutionResult.command(r.timedOut?124:r.exitCode,r.combined());
    }

    private static JSONArray hostModules(String maps,String filter)throws Exception{
        String needle=filter==null?"":filter.trim().toLowerCase(Locale.US);Map<String,long[]> grouped=new LinkedHashMap<>();
        for(String line:maps.split("\\n")){
            String[] parts=line.trim().split("\\s+",6);if(parts.length<6)continue;String path=parts[5];if(path.startsWith("[")||path.isEmpty())continue;if(!needle.isEmpty()&&!path.toLowerCase(Locale.US).contains(needle))continue;
            String[] range=parts[0].split("-",2);if(range.length!=2)continue;try{long start=Long.parseUnsignedLong(range[0],16),end=Long.parseUnsignedLong(range[1],16),off=Long.parseUnsignedLong(parts[2],16),base=start-off;long[] v=grouped.get(path);if(v==null)grouped.put(path,new long[]{base,start,end});else{if(Long.compareUnsigned(base,v[0])<0)v[0]=base;if(Long.compareUnsigned(start,v[1])<0)v[1]=start;if(Long.compareUnsigned(end,v[2])>0)v[2]=end;}}catch(Throwable ignored){}
        }
        JSONArray out=new JSONArray();for(Map.Entry<String,long[]> e:grouped.entrySet()){long[] v=e.getValue();out.put(new JSONObject().put("path",e.getKey()).put("base","0x"+Long.toUnsignedString(v[0],16)).put("start","0x"+Long.toUnsignedString(v[1],16)).put("end","0x"+Long.toUnsignedString(v[2],16)).put("size",v[2]-v[1]));}return out;
    }

    private static String fridaOp(String action){
        switch(action){case "frida_modules":return "modules";case "frida_ranges":return "ranges";case "frida_read":return "read";case "frida_write":return "write";case "frida_scan":return "scan";case "frida_protect":return "protect";case "frida_patch":return "patch";case "frida_export":return "export";case "frida_watch":return "watch_start";case "frida_watch_stop":return "watch_stop";case "frida_watch_stop_all":return "watch_stop_all";case "frida_eval":return "eval";case "frida_detach_all":return "hook_detach_all";default:return action;}
    }
    private static JSONObject fridaPayload(JSONObject in)throws Exception{
        JSONObject o=new JSONObject();for(String k:new String[]{"filter","address","size","data","pattern","module","protection","name","script","max","chunk_size","coalesce","volatile","watch_id","interval_ms"})if(in.has(k))o.put(k,in.get(k));return o;
    }
    private static JSONObject copyPayload(JSONObject in)throws Exception{JSONObject o=new JSONObject();for(String k:new String[]{"filter","address","size","data","format","path","max_lines","max_modules","max_chars","timeout_ms"})if(in.has(k))o.put(k,in.get(k));return o;}
    private static String q(String s){return "'"+(s==null?"":s.replace("'","'\\''"))+"'";}
}

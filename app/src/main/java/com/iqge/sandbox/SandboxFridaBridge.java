package com.iqge.sandbox;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * In-process Frida Gadget bridge for one IQ Sandbox guest PID.
 *
 * Gadget runs in autonomous Script mode. A tiny resident bridge polls a private command file,
 * executes Frida-native operations in the guest process, and writes JSON replies. This gives the
 * Agent dynamic memory/instrumentation without requiring adb, frida-server, ptrace, or a Python
 * Frida client on the phone.
 */
public final class SandboxFridaBridge {
    private static volatile boolean loaded;
    private static volatile File sessionDir;
    private SandboxFridaBridge() {}

    public static synchronized JSONObject load(Context context) throws Exception {
        Context host = IQSandboxEngine.hostContext(); if (host == null) host = context;
        if (loaded && sessionDir != null) return status(host);
        File master = FridaRuntimeManager.masterGadget(host);
        if (!FridaRuntimeManager.isInstalled(host)) throw new IllegalStateException("Frida Gadget 未安装；先执行 Debug action=frida_install");
        File dir = new File(FridaRuntimeManager.root(host), "sessions/" + Process.myPid());
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("无法创建 Frida Guest session: " + dir);
        File gadget = new File(dir, "libiqfrida.so");
        File config = new File(dir, "libiqfrida.config");
        File script = new File(dir, "iq-agent.js");
        copy(master, gadget);
        JSONObject interaction = new JSONObject().put("type", "script").put("path", script.getName()).put("on_change", "ignore");
        write(config, new JSONObject().put("interaction", interaction).put("runtime", "qjs").put("teardown", "minimal").toString(2));
        write(script, bridgeScript(dir));
        new File(dir, "command.json").delete();
        new File(dir, "ready.json").delete();
        System.load(gadget.getAbsolutePath());
        long end = SystemClock.uptimeMillis() + 6000;
        File ready = new File(dir, "ready.json");
        while (SystemClock.uptimeMillis() < end && !ready.isFile()) Thread.sleep(25);
        if (!ready.isFile()) throw new IllegalStateException("Frida Gadget 已加载但脚本桥未就绪");
        loaded = true; sessionDir = dir;
        SandboxDebugLog.event("Frida Gadget 已进入 Guest pid=" + Process.myPid());
        return status(context);
    }

    public static JSONObject status(Context context) throws Exception {
        Context host = IQSandboxEngine.hostContext(); if (host == null) host = context;
        JSONObject o = new JSONObject()
            .put("installed", FridaRuntimeManager.isInstalled(host))
            .put("version", FridaRuntimeManager.VERSION)
            .put("loaded", loaded)
            .put("pid", Process.myPid());
        if (sessionDir != null) o.put("session_dir", sessionDir.getAbsolutePath()).put("ready", new File(sessionDir, "ready.json").isFile());
        return o;
    }

    public static synchronized JSONObject command(Context context, String op, JSONObject payload, int timeoutMs) throws Exception {
        if (!loaded || sessionDir == null) load(context);
        long seq = System.nanoTime();
        String id = Long.toHexString(seq) + "-" + Process.myPid();
        JSONObject cmd = new JSONObject().put("id", id).put("op", op == null ? "" : op).put("payload", payload == null ? new JSONObject() : payload);
        File command = new File(sessionDir, "command.json");
        File tmp = new File(sessionDir, "command.tmp");
        File response = new File(sessionDir, "response-" + id + ".json");
        write(tmp, cmd.toString());
        if (!tmp.renameTo(command)) { write(command, cmd.toString()); tmp.delete(); }
        long end = SystemClock.uptimeMillis() + Math.max(800, timeoutMs);
        while (SystemClock.uptimeMillis() < end) {
            if (response.isFile()) {
                String text = read(response); response.delete();
                JSONObject r = new JSONObject(text);
                if (!r.optBoolean("ok", false)) throw new IllegalStateException(r.optString("error", r.toString()));
                return r;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("Frida command timeout: " + op);
    }

    public static String events(int maxChars) throws Exception {
        if (sessionDir == null) return "";
        File f = new File(sessionDir, "events.log"); if (!f.isFile()) return "";
        String s = read(f); int max = Math.max(1024, Math.min(1_000_000, maxChars));
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private static String bridgeScript(File dir) {
        String base = js(dir.getAbsolutePath());
        return "rpc.exports={\n" +
            "init(){let I=\"" + base + "\";\n" +
            "const CMD=I+'/command.json'; const READY=I+'/ready.json'; const EVENTS=I+'/events.log';\n" +
            "const hooks=new Map(); const watches=new Map(); const active=new Map(); let lastId=''; const MAX_SCAN_MATCHES=2048; const DEFAULT_SCAN_MATCHES=256; const SCAN_SYNC_ERROR='Memory.scanSync is disabled in frida_eval; use Debug action=frida_scan or await IQ.scan(options).';\n" +
            "function jsonSafe(v,depth,seen){ depth=depth||0; seen=seen||new Set(); if(v===undefined||v===null)return null; if(typeof v==='bigint')return v.toString(); if(typeof v==='string')return v.length>262144?v.slice(0,262144)+'…[truncated]':v; if(typeof v!=='object')return v; if(v&&v.constructor&&v.constructor.name==='NativePointer')return v.toString(); if(depth>=8)return '[depth-limit]'; if(seen.has(v))return '[circular]'; seen.add(v); try{ if(Array.isArray(v)){const n=Math.min(v.length,2048),a=[];for(let i=0;i<n;i++)a.push(jsonSafe(v[i],depth+1,seen));if(v.length>n)a.push('[+'+(v.length-n)+' more]');return a;} const o={}; let count=0; for(const k in v){if(count++>=256){o.__truncated__=true;break;}try{o[k]=jsonSafe(v[k],depth+1,seen);}catch(e){o[k]='[unserializable]';}} return o;} finally{seen.delete(v);} }\n" +
            "function emit(v){ try{ const f=new File(EVENTS,'a'); f.write(JSON.stringify({ts:Date.now(),value:jsonSafe(v)})+'\\n'); f.flush(); f.close(); }catch(_){} }\n" +
            "const IQ={emit, hooks, watches, ptr:(v)=>ptr(v), module:(n)=>Process.getModuleByName(n)};\n" +
            "function hex(ab){ if(ab===null)return ''; const a=new Uint8Array(ab); let s=''; for(let i=0;i<a.length;i++)s+=a[i].toString(16).padStart(2,'0'); return s;}\n" +
            "function bytes(s){ s=String(s||'').replace(/0x/g,'').replace(/[^0-9a-f]/gi,''); if(s.length%2)throw new Error('hex length must be even'); const a=new Uint8Array(s.length/2); for(let i=0;i<a.length;i++)a[i]=parseInt(s.substr(i*2,2),16); return a;}\n" +
            "function pointerDistance(start,end){const n=parseInt(end.sub(start).toString(),16);if(!Number.isSafeInteger(n)||n<0)throw new Error('scan range is too large');return n;}\n" +
            "function legacyScan(a,n,pattern,deadline){return safeScan({address:a,size:n,pattern:pattern},deadline).then(r=>{if(!r.complete)throw new Error('Memory.scanSync compatibility scan stopped: '+r.stop_reason);return r.matches;});}\n" +
            "function rewriteLegacyScan(source){const token='Memory.scanSync';let out='',cursor=0;for(;;){const i=source.indexOf(token,cursor);if(i<0){out+=source.slice(cursor);break;}const open=source.indexOf('(',i+token.length);if(open<0){out+=source.slice(cursor);break;}let depth=0,quote='',escaped=false,end=-1,args=[],last=open+1;for(let j=open+1;j<source.length;j++){const ch=source[j];if(quote){if(escaped)escaped=false;else if(ch===String.fromCharCode(92))escaped=true;else if(ch===quote)quote='';continue;}if(ch===String.fromCharCode(34)||ch===String.fromCharCode(39)||ch===String.fromCharCode(96)){quote=ch;continue;}if(ch==='('||ch==='['||ch==='{'){depth++;continue;}if(ch===')'){if(depth===0){args.push(source.slice(last,j));end=j;break;}depth--;continue;}if(ch===','&&depth===0){args.push(source.slice(last,j));last=j+1;}}if(end<0||args.length!==3){out+=source.slice(cursor,i+token.length);cursor=i+token.length;continue;}out+=source.slice(cursor,i)+'(await __legacyScan('+args[0]+','+args[1]+','+args[2]+'))';cursor=end+1;}return out;}\n" +
            "function boundedInteger(value,fallback,min,max){if(value===undefined||value===null||value===''||typeof value==='boolean')return fallback;const n=Number(value);return Number.isFinite(n)?Math.max(min,Math.min(max,Math.floor(n))):fallback;}\n" +
            "function validateScanPattern(pattern){const parts=pattern.split(':');if(parts.length>2)throw new Error('invalid scan pattern');const bytes=parts[0].trim().split(/\\s+/);if(bytes.length===0||bytes.some(v=>!/[0-9a-f?]{2}/i.test(v)||v.length!==2))throw new Error('invalid scan pattern');if(parts.length===2){const mask=parts[1].trim().split(/\\s+/);if(mask.length!==bytes.length||mask.some(v=>!/[0-9a-f]{2}/i.test(v)||v.length!==2))throw new Error('invalid scan pattern mask');}return bytes.length;}\n" +
            "function resolveScanTarget(p){const moduleName=String(p.module||'').trim();const hasAddress=p.address!==undefined&&p.address!==null&&String(p.address).trim()!=='';const hasSize=p.size!==undefined&&p.size!==null&&String(p.size).trim()!=='';if(moduleName&&(hasAddress||hasSize))throw new Error('scan accepts either module or address/size, not both');let base,size;if(moduleName){const m=Process.getModuleByName(moduleName);base=m.base;size=Number(m.size);}else{if(!hasAddress||!hasSize)throw new Error('scan requires module or address with size');base=ptr(p.address);size=Number(p.size);}if(!Number.isSafeInteger(size)||size<=0)throw new Error('scan size must be a positive safe integer');const end=base.add(size);if(end.compare(base)<=0)throw new Error('scan range overflow');return {base,end,size,module:moduleName};}\n" +
            "function moduleUnchanged(target){if(!target.module)return true;try{const m=Process.getModuleByName(target.module);return m.base.compare(target.base)===0&&Number(m.size)===target.size;}catch(_){return false;}}\n" +
            "function readableSlices(start,end){const ranges=Process.enumerateRanges({protection:'r--',coalesce:false}).slice().sort((a,b)=>a.base.compare(b.base));const out=[];let floor=start;for(const range of ranges){const rangeEnd=range.base.add(range.size);if(rangeEnd.compare(floor)<=0||range.base.compare(end)>=0)continue;const sliceStart=range.base.compare(floor)<0?floor:range.base;const sliceEnd=rangeEnd.compare(end)>0?end:rangeEnd;if(sliceStart.compare(sliceEnd)<0){out.push({base:sliceStart,size:pointerDistance(sliceStart,sliceEnd)});floor=sliceEnd;if(floor.compare(end)>=0)break;}}return out;}\n" +
            "function scanBlock(base,size,pattern,state){return new Promise(resolve=>{let settled=false;try{Memory.scan(base,size,pattern,{onMatch(address,matchSize){const key=address.toString()+':'+matchSize;if(state.seen.has(key))state.duplicateMatches++;else{state.seen.add(key);state.matches.push({address:address.toString(),size:matchSize});}if(state.matches.length>=state.max){state.capped=true;return 'stop';}},onError(reason){if(!settled){settled=true;resolve({ok:false,error:String(reason)});}},onComplete(){if(!settled){settled=true;resolve({ok:true});}}});}catch(error){if(!settled){settled=true;resolve({ok:false,error:String(error)});}}});}\n" +
            "async function safeScan(options,outerDeadline){const p=options||{};const target=resolveScanTarget(p);const pattern=String(p.pattern||'').trim();if(!pattern)throw new Error('scan pattern is empty');const patternSize=validateScanPattern(pattern);if(patternSize>8388608)throw new Error('scan pattern is too large');const max=boundedInteger(p.max,DEFAULT_SCAN_MATCHES,1,MAX_SCAN_MATCHES);const requestedChunk=boundedInteger(p.chunk_size,4194304,65536,8388608);const chunkSize=Math.min(8388608,Math.max(requestedChunk,patternSize*2));const timeoutMs=boundedInteger(p.timeout_ms,30000,1000,120000);const localDeadline=Date.now()+timeoutMs;const deadline=Number.isFinite(outerDeadline)?Math.min(localDeadline,outerDeadline):localDeadline;const state={seen:new Set(),matches:[],max,capped:false,duplicateMatches:0};const errors=[];let errorCount=0,attempted=0,scanned=0,skipped=0,failedSize=0;let cursor=target.base,timedOut=false,mappingChanged=false;while(cursor.compare(target.end)<0&&!state.capped){if(Date.now()>=deadline){timedOut=true;break;}if(!moduleUnchanged(target)){mappingChanged=true;break;}const slices=readableSlices(cursor,target.end);if(slices.length===0){skipped+=pointerDistance(cursor,target.end);cursor=target.end;break;}let refresh=false;for(const slice of slices){if(cursor.compare(slice.base)<0){skipped+=pointerDistance(cursor,slice.base);cursor=slice.base;}const sliceEnd=slice.base.add(slice.size);let firstBlock=true;while(cursor.compare(sliceEnd)<0){if(Date.now()>=deadline){timedOut=true;break;}if(!moduleUnchanged(target)){mappingChanged=true;break;}const overlap=firstBlock?0:patternSize-1;const blockBase=overlap>0?cursor.sub(overlap):cursor;const part=Math.min(chunkSize,pointerDistance(blockBase,sliceEnd));const nextCursor=blockBase.add(part);const advance=pointerDistance(cursor,nextCursor);if(advance<=0)throw new Error('scan chunk made no progress');attempted+=part;const result=await scanBlock(blockBase,part,pattern,state);cursor=nextCursor;firstBlock=false;if(result.ok){if(!state.capped)scanned+=advance;}else{failedSize+=advance;errorCount++;if(errors.length<128)errors.push({address:blockBase.toString(),size:part,error:result.error});refresh=true;}if(state.capped||timedOut||mappingChanged||refresh)break;await new Promise(resolve=>setImmediate(resolve));}if(state.capped||timedOut||mappingChanged||refresh)break;}if(state.capped||timedOut||mappingChanged)break;if(refresh){await new Promise(resolve=>setImmediate(resolve));continue;}if(cursor.compare(target.end)<0){skipped+=pointerDistance(cursor,target.end);cursor=target.end;}}const stopReason=state.capped?'max_matches':timedOut?'timeout':mappingChanged?'mapping_changed':errorCount>0?'scan_errors':'complete';return {base:target.base.toString(),size:target.size,module:target.module||null,pattern_size:patternSize,chunk_size:chunkSize,chunk_overlap:patternSize-1,timeout_ms:timeoutMs,max_matches:max,attempted,scanned,skipped,failed_size:failedSize,match_count:state.matches.length,duplicate_matches:state.duplicateMatches,error_count:errorCount,errors_truncated:errorCount>errors.length,errors,complete:stopReason==='complete'&&cursor.compare(target.end)>=0,stop_reason:stopReason,matches:state.matches};}\n" +
            "IQ.scan=(options)=>safeScan(options||{});\n" +
            "async function run(c){ const p=c.payload||{}; switch(c.op){\n" +
            "case 'ping': return {pid:Process.id,arch:Process.arch,platform:Process.platform,page_size:Process.pageSize};\n" +
            "case 'modules': return Process.enumerateModules().map(m=>({name:m.name,base:m.base.toString(),size:m.size,path:m.path}));\n" +
            "case 'ranges': return Process.enumerateRanges({protection:p.protection||'r--',coalesce:p.coalesce!==false}).slice(0,p.max||4000).map(r=>({base:r.base.toString(),size:r.size,protection:r.protection,file:r.file?{path:r.file.path,offset:r.file.offset,size:r.file.size}:null}));\n" +
            "case 'read': { const n=Math.max(1,Math.min(1048576,Number(p.size||64))); const a=ptr(p.address); const data=p.volatile===false?a.readByteArray(n):a.readVolatile(n); return {address:a.toString(),size:n,protection:Memory.queryProtection(a),volatile:p.volatile!==false,hex:hex(data)}; }\n" +
            "case 'write': { const a=ptr(p.address); const b=bytes(p.data); if(p.volatile===false)a.writeByteArray(b.buffer);else a.writeVolatile(b.buffer); return {address:a.toString(),written:b.byteLength,volatile:p.volatile!==false,protection:Memory.queryProtection(a)}; }\n" +
            "case 'protect': { const a=ptr(p.address); const n=Math.max(1,Number(p.size||Process.pageSize)); return {address:a.toString(),size:n,protection:p.protection,success:Memory.protect(a,n,String(p.protection||'rw-'))}; }\n" +
            "case 'patch': { const a=ptr(p.address); const b=bytes(p.data); Memory.patchCode(a,b.byteLength,code=>code.writeByteArray(b.buffer)); return {address:a.toString(),patched:b.byteLength}; }\n" +
            "case 'scan': return safeScan(p);\n" +
            "case 'watch_start': { const id=String(p.watch_id||('watch-'+Date.now())); if(watches.has(id))throw new Error('watch already exists: '+id); const a=ptr(p.address),n=Math.max(1,Math.min(65536,Number(p.size||4))),ms=Math.max(20,Math.min(60000,Number(p.interval_ms||100))); let last=null; const timer=setInterval(()=>{try{const cur=hex(a.readVolatile(n));if(cur!==last){emit({type:'memory_watch',watch_id:id,address:a.toString(),size:n,previous:last,data:cur});last=cur;}}catch(e){emit({type:'memory_watch_error',watch_id:id,error:String(e)});}},ms); watches.set(id,timer); return {watch_id:id,address:a.toString(),size:n,interval_ms:ms}; }\n" +
            "case 'watch_stop': { const id=String(p.watch_id||''); const timer=watches.get(id); if(timer!==undefined){clearInterval(timer);watches.delete(id);} return {watch_id:id,stopped:timer!==undefined}; }\n" +
            "case 'watch_stop_all': { for(const timer of watches.values())try{clearInterval(timer);}catch(_){} const count=watches.size; watches.clear(); return {stopped:count}; }\n" +
            "case 'export': { const m=p.module?Process.getModuleByName(String(p.module)):null; const a=m?m.findExportByName(String(p.name)):Module.findGlobalExportByName(String(p.name)); return {address:a?a.toString():null}; }\n" +
            "case 'hook_detach_all': { for(const h of hooks.values()){try{h.detach();}catch(_){}} hooks.clear(); Interceptor.detachAll(); return {detached:true}; }\n" +
            "case 'eval': { const source=rewriteLegacyScan(String(p.script||'')); const AsyncFunction=Object.getPrototypeOf(async function(){}).constructor; const fn=new AsyncFunction('IQ','Process','Module','Memory','MemoryAccessMonitor','Interceptor','Stalker','Thread','DebugSymbol','Backtracer','NativeFunction','NativeCallback','CModule','ptr','__legacyScan','\"use strict\";\\n'+source); const ms=Math.max(1000,Math.min(120000,Number(p.timeout_ms||30000))); const deadline=Date.now()+ms; const evalIQ=Object.assign({},IQ,{scan:(options)=>safeScan(options||{},deadline)}); const evalMemory=new Proxy(Memory,{get(target,property){if(property==='scanSync')return (a,n,pattern)=>legacyScan(a,n,pattern,deadline);return target[property];},set(target,property,value){target[property]=value;return true;}}); let timer; try{const value=await Promise.race([Promise.resolve(fn(evalIQ,Process,Module,evalMemory,MemoryAccessMonitor,Interceptor,Stalker,Thread,DebugSymbol,Backtracer,NativeFunction,NativeCallback,CModule,ptr,(a,n,pattern)=>legacyScan(a,n,pattern,deadline))),new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('frida_eval deadline exceeded: '+ms+'ms')),ms);})]); return {value:jsonSafe(value)};} finally{if(timer!==undefined)clearTimeout(timer);} }\n" +
            "default: throw new Error('unknown Frida op: '+c.op); }}\n" +
            "function finish(c,out){ try{File.writeAllText(I+'/response-'+c.id+'.json',JSON.stringify(out));}catch(e){emit({type:'response_write_error',id:c.id,error:String(e)});} active.delete(c.id); }\nfunction start(c){ if(active.has(c.id))return; active.set(c.id,true); const out={id:c.id,ok:true}; Promise.resolve().then(()=>run(c)).then(v=>{out.result=jsonSafe(v);},e=>{out.ok=false;out.error=String(e&&e.stack?e.stack:e);}).then(()=>finish(c,out),e=>{out.ok=false;out.error=String(e);finish(c,out);}); }\nfunction tick(){ let c; try{c=JSON.parse(File.readAllText(CMD));}catch(_){return;} if(!c||!c.id||c.id===lastId)return; lastId=c.id; start(c); }\n" +
            "File.writeAllText(READY,'1');\n" +
            "setInterval(tick,30);}};\n";
    }

    private static void copy(File src, File dst) throws Exception {
        if (dst.isFile() && dst.length() == src.length() && isElf(dst)) return;
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] b = new byte[131072]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }
        tmp.setReadable(true, false); tmp.setExecutable(true, false); tmp.setWritable(true, true);
        if (!tmp.renameTo(dst)) { if (dst.exists()) dst.delete(); if (!tmp.renameTo(dst)) throw new IllegalStateException("无法放置 Frida Gadget: " + dst); }
    }
    private static boolean isElf(File f) { try (FileInputStream in = new FileInputStream(f)) { byte[] h=new byte[4]; return in.read(h)==4&&(h[0]&0xff)==0x7f&&h[1]=='E'&&h[2]=='L'&&h[3]=='F'; } catch(Throwable e){return false;} }
    private static void write(File f,String s)throws Exception{File p=f.getParentFile();if(p!=null&&!p.isDirectory())p.mkdirs();try(FileOutputStream out=new FileOutputStream(f)){out.write(s.getBytes(StandardCharsets.UTF_8));}}
    private static String read(File f)throws Exception{try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}}
    private static String js(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n");}
}

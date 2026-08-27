import java.nio.file.*;

public final class FridaDeadlockRegressionTest {
    private static void require(boolean c,String m){if(!c)throw new AssertionError(m);}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String bridge=Files.readString(root.resolve("src/com/iqge/sandbox/SandboxFridaBridge.java"));
        String tool=Files.readString(root.resolve("src/com/termux/app/iqcode/tools/IQDebugTool.java"));
        String prompt=Files.readString(root.resolve("src/com/termux/app/iqcode/core/SystemPromptBuilder.java"));
        require(!bridge.contains("Memory.scanSync(a,n,String(p.pattern))"),"dedicated scan must not use scanSync");
        require(bridge.contains("async function safeScan(options,outerDeadline)")
                && bridge.contains("case 'scan': return safeScan(p)")
                && bridge.contains("Memory.scan(base,size,pattern"),
            "dedicated scan must use the shared asynchronous scanner");
        require(bridge.contains("Process.enumerateRanges({protection:'r--',coalesce:false})")
                && bridge.contains("readableSlices(cursor,target.end)"),
            "scan calls must stay inside current readable mappings");
        require(bridge.contains("onError(reason){if(!settled){settled=true;resolve({ok:false")
                && !bridge.contains("reject(new Error(String(e)))"),
            "one inaccessible block must return a partial error instead of failing the command");
        require(bridge.contains("errors_truncated")&&bridge.contains("failed_size")
                && bridge.contains("stop_reason:stopReason"),
            "partial scan diagnostics must be returned");
        require(bridge.contains("MAX_SCAN_MATCHES=2048"),"scan hard cap missing");
        require(bridge.contains("DEFAULT_SCAN_MATCHES=256"),"safe default match cap missing");
        require(bridge.contains("return 'stop'"),"scan must stop when match cap is reached");
        require(bridge.contains("boundedInteger(p.chunk_size,4194304,65536,8388608)"),"bounded 4 MiB scan chunk default missing");
        require(bridge.contains("const overlap=firstBlock?0:patternSize-1")
                && bridge.contains("scanned+=advance")
                && bridge.contains("chunk_overlap:patternSize-1"),
            "adjacent chunks must overlap without inflating unique scanned bytes");
        require(bridge.contains("state.seen.has(key)")&&bridge.contains("duplicate_matches"),"overlapping or refreshed mappings must deduplicate matches");
        require(!bridge.contains("if(busy)return"),"global busy gate must not serialize long async commands");
        require(bridge.contains("const active=new Map()"),"inflight command tracking missing");
        require(bridge.contains("public static synchronized JSONObject command"),"the single command-file mailbox must serialize Java callers");
        require(bridge.contains("rewriteLegacyScan")
                && bridge.contains("Memory.scanSync compatibility scan stopped")
                && bridge.contains("if(property==='scanSync')"),
            "eval must translate legacy scanSync calls to bounded async scans");
        require(bridge.contains("IQ.scan=(options)=>safeScan(options||{})")
                && bridge.contains("Object.assign({},IQ,{scan:(options)=>safeScan(options||{},deadline)})"),
            "eval must expose the same scanner under its own deadline");
        require(bridge.contains("frida_eval deadline exceeded"),"eval deadline missing");
        require(bridge.contains("[circular]"),"bounded/circular JSON serialization missing");
        require(tool.contains("hard-capped at 2048")&&tool.contains("await IQ.scan(options)"),"tool schema must describe the scan cap and safe eval API");
        require(tool.contains("fridaPayload.put(\"timeout_ms\",runtimeTimeoutMs)")
                && tool.contains("commandTimeoutMs=Math.min(120000,runtimeTimeoutMs+1500)"),
            "the Java command timeout must leave room for a structured Frida deadline response");
        require(prompt.contains("always use Debug action=frida_scan instead of Memory.scanSync")
                && prompt.contains("await IQ.scan(options)")
                && prompt.contains("Legacy Memory.scanSync calls are translated")
                && prompt.contains("complete, stop_reason, and errors"),
            "Agent prompt must teach bounded async scan compatibility");
        System.out.println("FridaDeadlockRegressionTest PASS");
    }
}

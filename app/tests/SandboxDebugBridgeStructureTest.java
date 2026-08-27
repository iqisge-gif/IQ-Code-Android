import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SandboxDebugBridgeStructureTest {
    static void require(boolean v,String m){if(!v)throw new AssertionError(m);} static String read(Path r,String p)throws Exception{return new String(Files.readAllBytes(r.resolve(p)),StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String registry=read(root,"src/com/termux/app/iqcode/tools/ToolRegistry.java");
        String tool=read(root,"src/com/termux/app/iqcode/tools/IQDebugTool.java");
        String bridge=read(root,"src/com/iqge/sandbox/SandboxAgentBridge.java");
        String proc=read(root,"src/com/iqge/sandbox/SandboxProcessDebug.java");
        String termux=read(root,"src/com/iqge/sandbox/SandboxTermuxBridge.java");
        String prompt=read(root,"src/com/termux/app/iqcode/core/SystemPromptBuilder.java");
        require(registry.contains("new IQDebugTool(context, shell)"),"Debug tool must be registered");
        require(tool.contains("process_list")&&tool.contains("memory_read")&&tool.contains("memory_write")&&tool.contains("load_library"),"Debug tool must expose process/native debug actions");
        require(tool.contains("PID ")||tool.contains("不属于当前 IQ Sandbox 包"),"sandbox PID ownership must be validated");
        require(bridge.contains("target_pid")&&bridge.contains("proc_")&&bridge.contains("SandboxProcessDebug.dispatch"),"process-targeted cross-process debug bridge must exist");
        require(proc.contains("/proc/self/maps")&&proc.contains("/proc/self/mem")&&proc.contains("findMapping")&&proc.contains("System.load(canonical)"),"guest debugger must implement maps/base, bounded memory and controlled .so load");
        require(proc.contains("单次最多写入")&&proc.contains("目标内存页不可写")&&proc.contains("IQ Code 私有目录"),"memory/injection safety bounds must remain enforced");
        require(termux.contains("iqsandbox")&&termux.contains("iqdebug")&&termux.contains("termux-bridge")&&termux.contains("ApiSettingsStore"),"Termux must share the sandbox/debug backend and Root toggle");
        require(prompt.contains("Native/process debugging is unified through Debug")&&prompt.contains("iqsandbox <action>")&&prompt.contains("stale base address"),"Agent prompt must understand the unified debug model");
        System.out.println("SandboxDebugBridgeStructureTest PASS");
    }
}

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class RootCapabilityStructureTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String read(Path root,String relative)throws Exception{return new String(Files.readAllBytes(root.resolve(relative)),StandardCharsets.UTF_8);}
    public static void main(String[]args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String config=read(root,"src/com/termux/app/iqcode/model/SessionConfig.java");
        String settings=read(root,"src/com/termux/app/iqcode/storage/ApiSettingsStore.java");
        String registry=read(root,"src/com/termux/app/iqcode/tools/ToolRegistry.java");
        String rootTool=read(root,"src/com/termux/app/iqcode/tools/RootBashTool.java");
        String executor=read(root,"src/com/termux/app/iqcode/termux/TermuxShellExecutor.java");
        String engine=read(root,"src/com/termux/app/iqcode/core/IQCodeEngine.java");
        String agents=read(root,"src/com/termux/app/iqcode/agents/SubagentManager.java");
        String ui=read(root,"src/com/iqge/MainActivity.java");
        require(config.contains("rootExecutionEnabled = false")&&settings.contains("root_execution_enabled"),"root capability must default off and persist explicitly");
        require(registry.contains("new RootBashTool(shell)")&&rootTool.contains("PermissionKind.SYSTEM")&&rootTool.contains("id -u"),"Root must be a separate SYSTEM tool that verifies uid 0");
        require(executor.contains("executeAsRoot")&&executor.contains("findSuBinary()")&&executor.contains("Magisk/KernelSU")&&executor.contains("terminateExecution(asRoot"),"root executor must use su and retain timeout/cancel cleanup");
        require(engine.contains("Root tool is disabled in IQ Code settings")&&engine.contains("if (\"Root\".equals(name)")&&agents.contains("\"Bash\",\"Root\""),"Root schema must be hidden while off and available to general-purpose subagents when on");
        require(ui.contains("Agent Root 权限")&&ui.contains("/root")&&ui.contains("checkRootAccess()")&&ui.contains("ROOT · UID 0"),"settings, slash command, probe and high-risk permission UI must exist");
        System.out.println("RootCapabilityStructureTest PASS");
    }
}

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SubagentPermissionStructureTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String read(Path root, String relative) throws Exception {
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        String engine = read(root, "src/com/termux/app/iqcode/core/IQCodeEngine.java");
        String manager = read(root, "src/com/termux/app/iqcode/agents/SubagentManager.java");
        String gate = read(root, "src/com/termux/app/iqcode/core/PermissionGate.java");

        require(engine.contains("subagents.execute(executionConfig, getEffectivePermissionMode(), call)"),
            "agent launches must receive the parent effective permission policy");
        require(manager.contains("execute(SessionConfig parent, String parentEffectiveMode, ToolCall call)")
                && manager.contains("PermissionModePolicy.capSubagent(parentEffectiveMode,def.permissionMode)")
                && !manager.contains("resolvePermissionMode"),
            "subagent definitions must be capped by their parent instead of escalating permissions");
        require(gate.contains("require(String effectiveMode, IQTool tool, ToolCall call, Listener listener)")
                && gate.contains("PermissionModePolicy.normalize(effectiveMode)"),
            "ordinary tool authorization must use the engine effective policy rather than mutable session config");

        System.out.println("SubagentPermissionStructureTest PASS");
    }
}

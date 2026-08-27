import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class BashCompletionStructureTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String read(Path root, String relative) throws Exception {
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static String region(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        require(from >= 0 && to > from, "missing source region: " + start);
        return source.substring(from, to);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        String executor = read(root, "src/com/termux/app/iqcode/termux/TermuxShellExecutor.java");
        String coordinator = read(root, "src/com/termux/app/iqcode/termux/BashCompletionCoordinator.java");
        String ui = read(root, "src/com/iqge/MainActivity.java");

        require(executor.contains("UUID.randomUUID().toString().replace(\"-\", \"\")")
                && executor.contains("iqge-complete-")
                && executor.contains("printf '%s:%s\\\\n'")
                && executor.contains("new ControlWatcher(controlFile, controlToken, completion)"),
            "Bash completion must use a per-execution private tokenized control record");
        require(executor.contains("createPrivateControlFile(controlFile)")
                && executor.contains("file.setReadable(true, true)")
                && executor.contains("file.setWritable(true, true)"),
            "completion control files must be private to the app UID");
        require(executor.contains("completion.publishProcessExit(child.waitFor())")
                && !executor.contains("finally { exited.countDown(); }")
                && coordinator.contains("processExit.countDown()"),
            "only a real waitFor result may publish process completion");
        require(executor.contains("completion.source() == BashCompletionCoordinator.Source.CONTROL")
                && executor.contains("completion.awaitProcessExit(CONTROL_EXIT_GRACE_MS)")
                && executor.contains("readPidWithGrace(pidFile)")
                && executor.contains("Bash ended without a trusted exit status"),
            "control-first completion must get a short wrapper-exit grace period and never return an unknown exit code");
        require(executor.contains("closeProcessStreams(child)")
                && executor.contains("stdout.close()")
                && executor.contains("stderr.close()")
                && executor.contains("joinThread(outThread, THREAD_JOIN_MS)")
                && executor.contains("joinThread(errThread, THREAD_JOIN_MS)"),
            "completion must close inherited capture pipes and reclaim capture workers");
        require(executor.contains("setsid.getAbsolutePath(), \"--wait\"")
                && executor.contains("android.system.Os.kill(-pid, android.system.OsConstants.SIGTERM)")
                && executor.contains("terminateProcessTree(pid,fallback,null,null)"),
            "timeouts and cancellation must terminate the isolated process group with tree-walk fallback");
        require(!executor.contains("BUILD SUCCESSFUL")
                && !executor.contains("Build complete")
                && !executor.contains("build complete"),
            "Bash completion must not infer success from build log text");
        require(ui.contains("public void onToolResult(ToolCall call,ToolExecutionResult result){ handleToolResult(rt,call,result); }")
                && !ui.contains("BUILD SUCCESSFUL") && !ui.contains("Build complete"),
            "the UI must receive Bash completion only through the executor result callback");

        System.out.println("BashCompletionStructureTest PASS");
    }
}

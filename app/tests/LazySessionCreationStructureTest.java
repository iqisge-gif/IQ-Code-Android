import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LazySessionCreationStructureTest {
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
        String engine = read(root, "src/com/termux/app/iqcode/core/IQCodeEngine.java");
        String ui = read(root, "src/com/iqge/MainActivity.java");

        String reset = region(engine, "public void resetConversation()", "public void resumeConversation");
        require(reset.contains("sessionStore = null") && !reset.contains("new SessionStore"),
            "opening an empty conversation must not create a persisted JSONL row");
        require(engine.contains("private synchronized void ensureSessionStore()")
                && engine.contains("sessionStore = new SessionStore(config.projectDirectory)")
                && engine.indexOf("ensureSessionStore();", engine.indexOf("public void sendPrompt")) >= 0,
            "the first real prompt must lazily create the session file");

        String fresh = region(ui, "private SessionRuntime createFreshRuntime()", "private SessionRuntime createRuntimeForExisting");
        require(fresh.contains("rt.file = null") && fresh.contains("rt.nextConfig.workflowId = java.util.UUID.randomUUID().toString()") && !fresh.contains("registerRuntime(rt)"),
            "a fresh blank runtime must use a new task workflow and stay out of the saved-session map");
        String activate = region(ui, "private void activateRuntime", "private String runtimeDisplayStatus");
        require(activate.contains("if(rt.file!=null)settingsStore.setLastSessionFile(rt.file)"),
            "activating a blank runtime must not restore a deleted history path");
        String started = region(ui, "private void handleSessionStarted", "private void handleTextDelta");
        require(started.contains("rt.engine.getSessionFile()")
                && started.contains("registerRuntime(rt)")
                && started.contains("settingsStore.setLastSessionFile(sessionFile)"),
            "once the first prompt creates a file, the runtime and last-session preference must bind to it");
        String deletion = region(ui, "private void confirmDeleteSession", "private void showSessionHistoryActions");
        require(deletion.contains("settingsStore.setLastSessionFile(null); newSession()")
                && deletion.contains("sessionRowSummaries.remove(key)"),
            "deleting the active history must leave a clean unsaved composer without a stale home row");
        System.out.println("LazySessionCreationStructureTest PASS");
    }
}

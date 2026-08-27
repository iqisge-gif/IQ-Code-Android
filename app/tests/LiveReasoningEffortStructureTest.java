import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class LiveReasoningEffortStructureTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String read(Path root, String relative) throws Exception {
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static String region(String source, String start, String end) {
        int from=source.indexOf(start);int to=source.indexOf(end,from+start.length());
        require(from>=0&&to>from,"missing source region: "+start);return source.substring(from,to);
    }

    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String engine=read(root,"src/com/termux/app/iqcode/core/IQCodeEngine.java");
        String ui=read(root,"src/com/iqge/MainActivity.java");

        String updater=region(engine,"public synchronized void updateReasoningEffort","private synchronized void ensureSessionStore");
        require(updater.contains("config.effort=nextEffort")&&updater.contains("activeTurnConfig.effort=nextEffort"),
            "live effort changes must update both the baseline and active turn snapshot");
        String loop=region(engine,"private void runAgent","private ToolBatch buildToolBatch");
        require(loop.contains("SessionConfig requestConfig = turnConfig.copy()")
                && loop.contains("ModelProviders.forConfig(requestConfig)")
                && loop.contains("requestModelWithRetry(requestConfig"),
            "each model request must snapshot the latest effort at its protocol boundary");

        String picker=region(ui,"private void showEffortPicker","private void showSettings");
        require(picker.contains("applyReasoningEffort(values[which],true)")
                && picker.contains("rt.nextConfig.effort=selected")
                && picker.contains("rt.engine.updateReasoningEffort(selected)")
                && picker.contains("下一次模型请求生效"),
            "the effort picker must sync UI, active turn, and the runtime's next-turn configuration");
        String chips=region(ui,"private void updateComposerChips","private static int parseTokenCount");
        require(chips.contains("composerEffortChip.setEnabled(true)")
                && chips.contains("composerEffortChip.setAlpha(1f)"),
            "the live effort chip must never inherit the permission lock");
        require(ui.contains("case \"/effort\": if (!arg.isEmpty()) applyReasoningEffort(arg,true)"),
            "the slash command must use the same synchronized live update path");
        String started=region(ui,"private void handleSessionStarted","private void handleTextDelta");
        require(started.contains("String selectedEffort=rt.nextConfig")&&started.contains("rt.nextConfig.effort=selectedEffort"),
            "turn-start callbacks must not overwrite an effort selected during startup");
        System.out.println("LiveReasoningEffortStructureTest PASS");
    }
}

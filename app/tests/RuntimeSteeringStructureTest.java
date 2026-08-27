import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class RuntimeSteeringStructureTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String read(Path root,String relative)throws Exception{return new String(Files.readAllBytes(root.resolve(relative)),StandardCharsets.UTF_8);}
    private static void requireOrdered(String source,String first,String second,String message){require(source.indexOf(first)>=0&&source.indexOf(second)>source.indexOf(first),message);}
    public static void main(String[]args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String engine=read(root,"src/com/termux/app/iqcode/core/IQCodeEngine.java");
        String provider=read(root,"src/com/termux/app/iqcode/api/ModelProvider.java");
        String responses=read(root,"src/com/termux/app/iqcode/api/OpenAIResponsesProvider.java");
        String ui=read(root,"src/com/iqge/MainActivity.java");
        String bash=read(root,"src/com/termux/app/iqcode/tools/BashTool.java");
        String prompt=read(root,"src/com/termux/app/iqcode/core/SystemPromptBuilder.java");
        String manifest=read(root,"AndroidManifest.xml");
        require(engine.contains("ArrayDeque<PendingPrompt> steeringQueue")&&engine.contains("steerPrompt(String prompt, JSONArray extraContent)")&&engine.contains("drainSteeringPrompts"),"running turns must accept queued user steering");
        require(engine.contains("已预输入，将在当前工具完成后处理")&&engine.contains("appendSkippedToolResults")&&engine.contains("onQueuedPromptApplied"),"queued input must wait for the active step and preserve matched tool results");
        require(engine.contains("drainSteeringPrompts(\"after-model\")")&&engine.contains("已加载 ")&&provider.contains("cancelRequest(Thread worker)")&&responses.contains("requests.cancel(worker)"),"queued input must load only after the current model response reaches a safe boundary");
        require(engine.contains("class ToolBatch")&&engine.contains("onToolBatchStarted(ToolBatch batch)")&&engine.contains("onToolBatchCompleted(ToolBatch batch)")&&engine.contains("buildToolBatch(assistant)"),"a model response with tool calls must expose one immutable UI batch");
        String toolBatchLoop=engine.substring(engine.indexOf("ToolBatch toolBatch = buildToolBatch(assistant);"));
        requireOrdered(toolBatchLoop,"batchListener.onToolBatchStarted(toolBatch)","if (hasPendingSteering())","tool batch start must precede steering-skipped results");
        requireOrdered(toolBatchLoop,"try {\n                    // A correction", "batchListener.onToolBatchCompleted(toolBatch)","tool batch completion must be protected by a finally path");
        require(engine.contains("PARTIAL_WAKE_LOCK")&&manifest.contains("android.permission.WAKE_LOCK"),"active agent work must keep the CPU awake instead of silently stalling when the screen sleeps");
        require(engine.contains("isInterruptedFailure")&&engine.contains("thread interrupted")&&engine.contains("onTurnComplete(\"cancelled\")"),"transport thread interruption must become a clean cancellation instead of a visible error card");
        require(engine.contains("requestModelWithRetry")&&engine.contains("transportRetryAttempted")&&engine.contains("onResponseRetry()")&&engine.contains("Thread.sleep(250L)"),"transient model stream failures must retry once before surfacing an error");
        require(engine.contains("canReplayModelRequest")&&engine.contains("codex-responses")&&engine.contains("native"),"native server-side tool requests must not be blindly replayed");
        require(engine.contains("shouldContinueAfterStopReason")&&engine.contains("max_output_tokens")&&engine.contains("appendInternalContinuation")&&engine.contains("emptyEndTurnRecoveries <= 2"),"truncated/empty model output must recover with a bounded retry instead of silently completing or looping forever");
        require(ui.contains("sendButton.setOnClickListener(v -> sendPrompt())")&&ui.contains("长按停止")&&ui.contains("已预输入")&&ui.contains("handleQueuedPromptApplied"),"busy composer must queue input, finish the current streamed bubble, and keep hard-cancel separate");
        require(ui.contains("手动添加 / 切换项目路径")&&ui.contains("switchProjectPath(String requestedProject"),"manual project path entry must be visible outside settings");
        require(ui.contains("built-in fast /init maintenance command")&&ui.contains("Do not enter plan mode, create tasks"),"/init must use a bounded direct path instead of a slow planning workflow");
        require(bash.contains("isLongRunningBuildCommand")&&bash.contains("15 * 60 * 1000"),"known builds/tests must not die from tiny agent-generated timeouts");
        require(prompt.contains("stable completion state")&&prompt.contains("authoritative queued input"),"system prompt must require completion and respect queued input");
        require(manifest.contains("versionCode=\"2280\"")&&manifest.contains("versionName=\"0.21.18\""),"release version must be 0.21.18");
        System.out.println("RuntimeSteeringStructureTest PASS");
    }
}

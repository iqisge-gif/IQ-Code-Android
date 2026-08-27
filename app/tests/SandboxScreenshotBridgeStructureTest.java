import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SandboxScreenshotBridgeStructureTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String read(Path root, String file) throws Exception {
        return new String(Files.readAllBytes(root.resolve(file)), StandardCharsets.UTF_8);
    }

    private static void requireOrdered(String source, String first, String second, String message) {
        int a = source.indexOf(first), b = source.indexOf(second);
        require(a >= 0 && b > a, message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        String bridge = read(root, "src/com/iqge/sandbox/SandboxAgentBridge.java");
        String tool = read(root, "src/com/termux/app/iqcode/tools/IQSandboxTool.java");
        String result = read(root, "src/com/termux/app/iqcode/model/ToolExecutionResult.java");
        String engine = read(root, "src/com/termux/app/iqcode/core/IQCodeEngine.java");
        String chat = read(root, "src/com/termux/app/iqcode/api/OpenAIChatCompletionsProvider.java");
        String responses = read(root, "src/com/termux/app/iqcode/api/OpenAIResponsesProvider.java");
        String script = read(root, "test-source-no-build.sh");

        require(bridge.contains("deadline_uptime_ms") && bridge.contains("nextSend=now+250L")
                        && bridge.contains("context.sendBroadcast(intent)"),
                "screenshot requests must retry the same deadline-bound broadcast");
        require(bridge.contains(".claim") && bridge.contains("claim.createNewFile()")
                        && bridge.contains("pruneRequestArtifacts"),
                "repeated and competing screenshot receivers must use an atomic claim");
        requireOrdered(bridge, "screenshotActivityReady(a,pkg)", "claimScreenshot(context,id,deadline)",
                "an unlaid-out Activity must not permanently claim a retryable screenshot request");
        require(bridge.contains("class CaptureSession") && bridge.contains("AtomicBoolean completed")
                        && bridge.contains("ACTIVE_CAPTURE.compareAndSet"),
                "capture must have one process-local session and one terminal result");
        require(bridge.contains("main.postDelayed(timeout") && bridge.contains("activityUsable()")
                        && bridge.contains("overlayRestored.compareAndSet(false,true)"),
                "capture timeout and Activity loss must restore the overlay exactly once");
        require(bridge.contains("expired-pixelcopy") && bridge.contains("expired-encode")
                        && bridge.contains("pruneScreenshots"),
                "late callbacks and retained screenshot files must be bounded");
        require(bridge.contains("MAX_CAPTURE_PIXELS") && bridge.contains("canvas.scale(")
                        && bridge.contains("CAPTURE_IO.execute"),
                "PixelCopy and fallback must share bounded dimensions and background encoding");
        require(bridge.contains("MAX_SCREENSHOT_BYTES") && bridge.contains("getFD().sync()")
                        && bridge.contains("renameTo(dst)") && bridge.contains("SandboxDebugLog.event"),
                "screenshot and result files must be bounded, atomically published, and diagnosable");

        require(tool.contains("?12000:4500") && tool.contains("readScreenshot(image)")
                        && tool.contains("Base64.NO_WRAP") && tool.contains("\"type\",\"image\""),
                "Sandbox screenshot must attach validated PNG pixels to model content");
        require(tool.contains("sandbox/screenshots") && tool.contains("MAX_SCREENSHOT_BYTES"),
                "the tool must accept images only from the bounded private screenshot directory");
        require(result.contains("okWithAdditionalContent") && result.contains("JSONArray additionalContent")
                        && result.contains("new JSONArray(content.toString())"),
                "tool results must deep-copy optional model-only content");
        require(engine.contains("JSONArray additionalToolContent") && engine.contains("result.additionalContent()"),
                "the engine must collect model-only tool content separately");
        requireOrdered(engine, "toolResults.put(block)", "additionalToolContent.put",
                "each protocol tool result must be recorded before its image");
        requireOrdered(engine, "for (int i = 0; i < additionalToolContent.length()", "appendMessage(\"user\", toolResults)",
                "all tool results and then images must be persisted in one user message");
        require(chat.contains("\"type\", \"image_url\"") && responses.contains("\"type\", \"input_image\""),
                "existing OpenAI transports must continue mapping normalized screenshot images");
        require(script.contains("SandboxScreenshotBridgeStructureTest.java")
                        && script.contains("SandboxScreenshotBridgeStructureTest \"$PROJECT_ROOT\""),
                "the canonical source suite must run the screenshot bridge regression");

        System.out.println("SandboxScreenshotBridgeStructureTest PASS");
    }
}

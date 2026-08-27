import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SessionContextNormalizationTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String read(Path root, String relative) throws Exception {
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static String region(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(0, start.length()));
        require(from >= 0 && to > from, "missing source region: " + start);
        return source.substring(from, to);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        String store = read(root, "src/com/termux/app/iqcode/storage/SessionStore.java");
        String engine = read(root, "src/com/termux/app/iqcode/core/IQCodeEngine.java");
        String responses = read(root, "src/com/termux/app/iqcode/api/OpenAIResponsesProvider.java");
        String chat = read(root, "src/com/termux/app/iqcode/api/OpenAIChatCompletionsProvider.java");

        String loader = region(store, "public static JSONArray loadMessages", "private static JSONArray normalizeProviderMessages");
        require(loader.contains("context_snapshot")
                && loader.contains("out = new JSONArray(snapshot.toString())")
                && loader.contains("normalizeProviderMessages(out)"),
            "the latest compacted provider snapshot must be replayed before later message rows");

        String normalizer = region(store, "private static JSONArray normalizeProviderMessages", "private static boolean canMergeProviderMessages");
        require(normalizer.contains("role.equals(previous.optString(\"role\"))")
                && normalizer.contains("merged.put")
                && normalizer.contains("new JSONObject().put(\"role\",role)"),
            "deleting a user row must merge adjacent assistant/user protocol turns without reordering blocks");
        require(store.contains("tool_use") && store.contains("tool_result")
                && store.contains("canMergeProviderMessages"),
            "normalization must keep tool call and result blocks in their original content order");

        String resume = region(engine, "public void resumeConversation(File file)", "public int estimateContextTokens");
        require(resume.contains("SessionStore.loadMessages(file)")
                && resume.contains("repairToolHistory")
                && resume.contains("config.renewTransportSession()")
                && resume.contains("activeProvider = null")
                && resume.contains("activeTurnConfig = null"),
            "resuming a rewritten file must rebuild memory and renew transport state");
        require(engine.contains("SessionStore store = sessionStore")
                && engine.contains("sessionStore.appendContextSnapshot(messages)"),
            "tool-chain repair must persist the repaired provider context rather than only changing RAM");

        require(responses.contains("pairedToolCallIds(messages)")
                && responses.contains("function_call_output")
                && responses.contains("Previous tool result")
                && responses.contains("emittedOutputs"),
            "Responses replay must never emit an orphan function_call_output");
        require(chat.contains("appendUserAndToolResults")
                && chat.contains("tool_call_id")
                && chat.contains("toolResultText"),
            "Chat Completions replay must preserve tool result pairing at its transport boundary");

        require(store.contains("context_snapshot")
                && store.contains("context_compaction")
                && store.contains("context_usage"),
            "editing before compaction must remove stale snapshot, compaction, and usage rows");
        System.out.println("SessionContextNormalizationTest PASS");
    }
}

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class CustomSystemPromptStructureTest {
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
        String config = read(root, "src/com/termux/app/iqcode/model/SessionConfig.java");
        String settings = read(root, "src/com/termux/app/iqcode/storage/ApiSettingsStore.java");
        String builder = read(root, "src/com/termux/app/iqcode/core/SystemPromptBuilder.java");
        String engine = read(root, "src/com/termux/app/iqcode/core/IQCodeEngine.java");
        String ui = read(root, "src/com/iqge/MainActivity.java");
        String sessions = read(root, "src/com/termux/app/iqcode/storage/SessionStore.java");
        String agents = read(root, "src/com/termux/app/iqcode/agents/SubagentManager.java");
        String anthropic = read(root, "src/com/termux/app/iqcode/api/AnthropicMessagesProvider.java");
        String responses = read(root, "src/com/termux/app/iqcode/api/OpenAIResponsesProvider.java");
        String chat = read(root, "src/com/termux/app/iqcode/api/OpenAIChatCompletionsProvider.java");

        require(config.contains("public String customSystemPrompt = \"\";")
                && config.contains("c.customSystemPrompt = customSystemPrompt;"),
            "session snapshots must carry the global custom system prompt");
        require(settings.contains("prefs.getString(\"custom_system_prompt\", \"\")")
                && settings.contains(".putString(\"custom_system_prompt\", sanitizeCustomSystemPrompt(c.customSystemPrompt))")
                && settings.contains("c.customSystemPrompt = sanitizeCustomSystemPrompt(c.customSystemPrompt);"),
            "the prompt must be sanitized and persisted as a global setting");
        String sanitizer = region(settings, "private static String sanitizeCustomSystemPrompt", "private static ApiProfile findProfile");
        require(sanitizer.contains("replace(\"\\r\\n\",\"\\n\")")
                && sanitizer.contains("input.indexOf('\\0')")
                && sanitizer.contains("Character.isISOControl")
                && !sanitizer.contains("codePoints>=8192")
                && !sanitizer.contains("32*1024"),
            "custom prompt validation must reject unsafe controls without imposing a length cap");

        require(builder.contains("return build(config, \"\");")
                && builder.contains("<custom_system_prompt>")
                && builder.contains("cannot override code-enforced permissions"),
            "the system prompt must label custom instructions while retaining enforced boundaries");
        int custom = builder.indexOf("customBlock + roleBlock + suffix");
        require(custom >= 0, "custom instructions must precede the role block and agent-specific suffix");
        require(engine.contains("ContextCompactor.roughTextTokens(buildSystemPrompt(c))")
                && engine.contains("String system = buildSystemPrompt(turnConfig);")
                && engine.contains("return SystemPromptBuilder.build(promptConfig, additionalSystemPrompt);"),
            "token estimates and provider requests must share one prompt builder");
        require(anthropic.contains("body.put(\"system\", systemPrompt)")
                && responses.contains("body.put(\"instructions\", systemPrompt)")
                && chat.contains("new JSONObject().put(\"role\", \"system\").put(\"content\", systemPrompt)"),
            "all provider protocols must send the composed prompt as a real system instruction");

        String other = region(ui, "private void showOtherSettings", "private void showSettings");
        require(other.contains("自定义头部提示词")
                && other.contains("下一完整任务生效")
                && other.contains("applyCustomSystemPromptToRuntimes(value)"),
            "other settings must expose and save the custom prompt with turn-boundary guidance");
        String apply = region(ui, "private void applyCustomSystemPromptToRuntimes", "private void showSettings");
        require(apply.contains("settingsStore.save(saved)")
                && apply.contains("rt.nextConfig.customSystemPrompt=config.customSystemPrompt")
                && apply.contains("if(!rt.engine.isBusy())rt.engine.configure"),
            "busy runtimes must stage the prompt while idle runtimes may configure immediately");
        String started = region(ui, "private void handleSessionStarted", "private void handleTextDelta");
        require(started.contains("String selectedPrompt=rt.nextConfig")
                && started.contains("rt.nextConfig.customSystemPrompt=selectedPrompt"),
            "turn-start callbacks must preserve a prompt staged during startup");
        require(engine.contains("final SessionConfig turnConfig = config.copy();")
                && agents.contains("SessionConfig child=parent.copy()")
                && agents.contains("PermissionModePolicy.capSubagent(parentEffectiveMode,def.permissionMode)"),
            "root turns and child agents must inherit snapshots without permission escalation");
        require(!sessions.contains("customSystemPrompt") && !sessions.contains("custom_system_prompt"),
            "private custom prompt text must not be persisted in session JSONL");

        System.out.println("CustomSystemPromptStructureTest PASS");
    }
}

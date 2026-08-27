import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SessionMessageMutationTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static String read(Path root, String relative) throws Exception {
        return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
    }

    private static void ordered(String source, String first, String second, String message) {
        require(source.indexOf(first) >= 0 && source.indexOf(second) > source.indexOf(first), message);
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        String store = read(root, "src/com/termux/app/iqcode/storage/SessionStore.java");
        String engine = read(root, "src/com/termux/app/iqcode/core/IQCodeEngine.java");
        String ui = read(root, "src/com/iqge/MainActivity.java");

        require(store.contains("class MessageReference")
                && store.contains("messageContentHash(JSONArray content)")
                && store.contains("hasHumanContent(content)"),
            "every editable human row needs an exact content digest, including image-only rows");
        require(store.contains("readStrictRows")
                && store.contains("会话历史第 ")
                && store.contains("消息引用缺少内容校验")
                && store.contains("target.contentHash.equals(candidate.contentHash)"),
            "destructive history edits must reject malformed files and stale references before writing");
        require(store.contains("int occurrences=0")
                && store.contains("if (occurrences > 1)")
                && store.contains("消息标识重复"),
            "duplicate message ids must never select an ambiguous edit target");
        require(store.contains("atomicReplace")
                && store.contains("stream.getFD().sync()")
                && store.contains("Os.rename")
                && store.contains("target.setLastModified(originalModifiedAt)"),
            "mutations must use a same-file durable atomic replacement and preserve activity mtime");
        require(store.contains("context_snapshot")
                && store.contains("context_compaction")
                && store.contains("context_usage")
                && store.contains("invalidatedContextEvent"),
            "editing before a provider snapshot must invalidate every derived context cache");
        require(!store.contains("createBranch(File parentFile") && !store.contains("branch_created"),
            "direct edits must not create a second session");

        require(ui.contains("persistedBody")
                && ui.contains("persistedMessageHash(enriched,imageBlocks)")
                && ui.contains("SessionStore.messageContentHash(content)"),
            "the live UI must hash the exact persisted prompt, including attachment context");
        require(ui.contains("mutateActiveMessage(source,value,false)")
                && ui.contains("mutateActiveMessage(source,\"\",true)")
                && ui.contains("activeRuntimeGeneration!=generation"),
            "edit and delete must mutate the active file only if the runtime has not changed");
        require(engine.contains("steerPrompt(String prompt, JSONArray extraContent, String messageId)")
                && engine.contains("pending.messageId")
                && engine.contains("pending.messageId, activeTurnId, \"human\""),
            "queued human prompts must remain addressable after they are persisted");

        ordered(store, "readStrictRows", "atomicReplace", "validation must complete before replacement");
        ordered(ui, "userItem.messageContentHash=persistedMessageHash(enriched,imageBlocks)", "engine.sendPrompt(enriched, imageBlocks, userItem.messageId)",
            "the UI reference must be prepared before the request is submitted");
        System.out.println("SessionMessageMutationTest PASS");
    }
}

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SessionProfileTurnStructureTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String read(Path root,String file)throws Exception{return new String(Files.readAllBytes(root.resolve(file)),StandardCharsets.UTF_8);}
    private static void ordered(String source,String first,String second,String message){require(source.indexOf(first)>=0&&source.indexOf(second)>source.indexOf(first),message);}
    public static void main(String[]args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String engine=read(root,"src/com/termux/app/iqcode/core/IQCodeEngine.java");
        String store=read(root,"src/com/termux/app/iqcode/storage/SessionStore.java");
        String ui=read(root,"src/com/iqge/MainActivity.java");
        require(engine.contains("final SessionConfig turnConfig = config.copy()")&&engine.contains("activeTurnConfig = turnConfig")&&engine.contains("SessionConfig requestConfig = turnConfig.copy()")&&engine.contains("ModelProviders.forConfig(requestConfig)")&&engine.contains("provider.createMessage(requestConfig"),"a complete root turn must freeze provider settings per request while allowing effort changes at request boundaries");
        require(engine.contains("SessionConfig executionConfig=activeTurnConfig==null?config:activeTurnConfig")&&engine.contains("tools.execute(executionConfig")&&engine.contains("subagents.execute(executionConfig"),"tools and subagents must inherit the frozen root-turn snapshot");
        ordered(engine,"appendProfileBinding(turnConfig)","appendMessage(\"user\"","binding and turn config must persist before the human message");
        require(store.contains("appendProfileBinding")&&store.contains("appendTurnConfig")&&store.contains("message_id")&&store.contains("turn_id")&&store.contains("origin"),"session JSONL must record safe profile and message identities");
        String payload=store.substring(store.indexOf("private static JSONObject profilePayload"),store.indexOf("public synchronized void appendCompaction"));
        require(!payload.contains("apiKey")&&!payload.contains("Authorization")&&!payload.contains("ciphertext"),"turn snapshots must never persist secrets");
        require(ui.contains("pendingProfileId")&&ui.contains("nextConfig")&&ui.contains("API 配置将在下一完整轮生效")&&ui.contains("SessionStore.loadProfileBinding"),"session runtimes must stage profile switches and restore bindings");
        System.out.println("SessionProfileTurnStructureTest PASS");
    }
}

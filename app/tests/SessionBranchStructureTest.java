import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SessionBranchStructureTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String read(Path root,String file)throws Exception{return new String(Files.readAllBytes(root.resolve(file)),StandardCharsets.UTF_8);}
    public static void main(String[]args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String store=read(root,"src/com/termux/app/iqcode/storage/SessionStore.java");
        String ui=read(root,"src/com/iqge/MainActivity.java");
        require(store.contains("class MessageReference")&&store.contains("messageReference(JSONObject row, int rowIndex)")&&store.contains("message_id")&&store.contains("messageContentHash(content)"),"new and legacy user messages need stable validated references");
        require(store.contains("editHumanMessage(File file")&&store.contains("deleteHumanMessage(File file")&&store.contains("readStrictRows")&&store.contains("消息标识重复")&&store.contains("历史消息已变化"),"message mutations must validate strict UUID/hash or legacy references");
        require(store.contains("atomicReplace")&&store.contains("stream.getFD().sync()")&&store.contains("Os.rename")&&store.contains("target.setLastModified(originalModifiedAt)"),"message mutations must atomically replace the same JSONL without creating a branch");
        require(store.contains("invalidatedContextEvent")&&store.contains("type.startsWith(\"context_\")")&&store.contains("normalizeProviderMessages")&&store.contains("canMergeProviderMessages"),"message mutations must invalidate stale snapshots and normalize retained provider history");
        require(!store.contains("createBranch(File parentFile")&&!store.contains("branch_created"),"direct edits must not create a second session branch");
        require(ui.contains("编辑此消息")&&ui.contains("删除此消息")&&ui.contains("showEditMessageDialog")&&ui.contains("confirmDeleteMessage")&&ui.contains("SessionStore.editHumanMessage")&&ui.contains("SessionStore.deleteHumanMessage"),"human message actions must edit or delete the current session directly");
        require(ui.contains("reloadSessionRuntimeAfterMutation")&&ui.contains("buildRuntimeForExisting(file)")&&ui.contains("sessionRuntimes.put(key,replacement)")&&ui.contains("old.engine.shutdown()"),"successful mutations must replace the current runtime without destroying the fallback before reload succeeds");
        require(ui.contains("后续回答会保留")&&ui.contains("副作用不会回滚"),"direct mutation UI must warn about non-causal retained answers and external side effects");
        require(ui.contains("engine.sendPrompt(enriched, imageBlocks, userItem.messageId)")&&ui.contains("item.messageContentHash=reference.contentHash"),"new sends and restored legacy rows must preserve mutation references");
        System.out.println("SessionBranchStructureTest PASS");
    }
}

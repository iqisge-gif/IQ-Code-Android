import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SessionMetadataStructureTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String read(Path root,String file)throws Exception{return new String(Files.readAllBytes(root.resolve(file)),StandardCharsets.UTF_8);}
    public static void main(String[]args)throws Exception{
        Path root=Paths.get(args.length==0?".":args[0]).toAbsolutePath().normalize();
        String store=read(root,"src/com/termux/app/iqcode/storage/SessionStore.java");
        String ui=read(root,"src/com/iqge/MainActivity.java");
        require(store.contains("class SessionMetadata")&&store.contains("session_metadata")&&store.contains("updateSessionMetadata")&&store.contains("title_override")&&store.contains("note"),"session notes must use append-only metadata snapshots with latest-wins summary parsing");
        require(store.contains("FILE_LOCKS")&&store.contains("fileLock(target)")&&store.contains("appendRowSynced")&&store.contains("canonical.setLastModified(modified)")&&store.contains("activityModifiedAt"),"metadata and engine appends must share canonical locks without changing activity ordering");
        String payload=store.substring(store.indexOf("public static void updateSessionMetadata"),store.indexOf("public synchronized void appendTurnConfig"));
        require(!payload.contains("apiKey")&&!payload.contains("Authorization")&&!payload.contains("ciphertext"),"session metadata must not include credentials");
        require(ui.contains("showSessionHistoryActions")&&ui.contains("showSessionNoteEditor")&&ui.contains("清除备注")&&ui.contains("编辑备注")&&ui.contains("historyDisplayLabel"),"both session history presentations must expose note edit/clear actions and render the note as the primary label");
        int sidebarStart=ui.indexOf("private String sessionLabel");int sidebarEnd=ui.indexOf("private String historyDisplayLabel",sidebarStart);String sidebar=sidebarStart<0||sidebarEnd<0?"":ui.substring(sidebarStart,sidebarEnd);
        int pickerStart=ui.indexOf("private String historyPickerLabel");int pickerEnd=ui.indexOf("private void refreshHistoryPickerRow",pickerStart);String picker=pickerStart<0||pickerEnd<0?"":ui.substring(pickerStart,pickerEnd);
        require(sidebar.contains("return historyDisplayLabel(summary,34)")&&!sidebar.contains("runtimeDisplayStatus")&&!sidebar.contains("已完成")&&!sidebar.contains("出错")&&ui.contains("sessionLabel(summary, active, rt), 14f")&&ui.contains("historyPickerLabel(s),14,TEXT"),"sidebar and picker rows must show only a larger note/title label");
        require(!picker.contains("relativeSessionTime")&&!picker.contains("messageCount"),"history picker rows must show only the note/title label");
        require(ui.contains("row.setOnLongClickListener(historyActions)")&&ui.contains("r.setOnLongClickListener")&&ui.contains("confirmDeleteSession(summary)"),"long press must open metadata actions while the explicit delete button keeps whole-session deletion");
        System.out.println("SessionMetadataStructureTest PASS");
    }
}

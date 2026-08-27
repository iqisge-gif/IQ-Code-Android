package com.termux.app.iqcode.storage;

import com.termux.app.iqcode.model.PlanWorkflowState;
import com.termux.app.iqcode.model.SessionConfig;
import com.termux.shared.termux.TermuxConstants;

import android.system.Os;
import android.system.OsConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent IQ Code Android sessions. Like Claude Code, every canonical project path owns its
 * own history namespace under ~/.iq/projects/<path-key>; JSONL stays human-readable and portable.
 */
public final class SessionStore {
    private static final ConcurrentHashMap<String,Object> FILE_LOCKS = new ConcurrentHashMap<>();
    private static final int MAX_NOTE_CHARS = 500;

    public static final class SessionMetadata {
        public final String note;
        public final String titleOverride;

        public SessionMetadata(String note, String titleOverride) {
            this.note = sanitizeMetadata(note, MAX_NOTE_CHARS);
            this.titleOverride = sanitizeMetadata(titleOverride, 120).replace('\n', ' ');
        }
    }

    public static final class SessionSummary {
        public final File file;
        public final String project;
        public final String title;
        public final String note;
        public final String titleOverride;
        public final long createdAt;
        /** Last non-metadata event; editing a note must not move this activity timestamp. */
        public final long activityModifiedAt;
        /** Compatibility alias used by older UI callers. */
        public final long modifiedAt;
        public final int messageCount;

        SessionSummary(File file, String project, String title, String note, String titleOverride, long createdAt, long activityModifiedAt, int messageCount) {
            this.file = file;
            this.project = project == null ? "" : project;
            this.title = title == null || title.trim().isEmpty() ? file.getName() : title.trim();
            this.note = note == null ? "" : note;
            this.titleOverride = titleOverride == null ? "" : titleOverride;
            this.createdAt = createdAt;
            this.activityModifiedAt = activityModifiedAt;
            this.modifiedAt = activityModifiedAt;
            this.messageCount = messageCount;
        }
    }

    public static final class MessageReference {
        public final String messageId;
        public final int legacyRowIndex;
        public final String contentHash;

        public MessageReference(String messageId, int legacyRowIndex, String contentHash) {
            this.messageId = messageId == null ? "" : messageId;
            this.legacyRowIndex = legacyRowIndex;
            this.contentHash = contentHash == null ? "" : contentHash;
        }
    }

    private static final class StrictRow {
        final String raw;
        final JSONObject json;
        final int jsonIndex;

        StrictRow(String raw, JSONObject json, int jsonIndex) {
            this.raw = raw;
            this.json = json;
            this.jsonIndex = jsonIndex;
        }
    }

    public static final class ProfileBinding {
        public final String profileId;
        public final int profileRevision;
        public final int credentialRevision;
        public final String protocol;
        public final String baseUrl;
        public final String model;

        ProfileBinding(JSONObject payload) {
            profileId = payload.optString("profile_id", "");
            profileRevision = Math.max(1, payload.optInt("profile_revision", 1));
            credentialRevision = Math.max(1, payload.optInt("credential_revision", 1));
            protocol = payload.optString("protocol", "");
            baseUrl = payload.optString("base_url", "");
            model = payload.optString("model", "");
        }
    }

    private final File sessionFile;

    public SessionStore(String projectDirectory) {
        String project = canonicalProject(projectDirectory);
        File dir = sessionDirectory(project);
        if (!dir.isDirectory()) dir.mkdirs();
        String date = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        sessionFile = new File(dir, date + "-" + UUID.randomUUID().toString().substring(0, 8) + ".jsonl");
        JSONObject meta = new JSONObject();
        try {
            meta.put("type", "session_start");
            meta.put("project", project);
            meta.put("project_key", projectKey(project));
            meta.put("created_at", System.currentTimeMillis());
            append(meta);
        } catch (Exception ignored) { }
    }

    private SessionStore(File existing) {
        sessionFile = existing;
    }

    public static SessionStore resume(File existing) {
        if (existing == null || !existing.isFile()) throw new IllegalArgumentException("Session file does not exist");
        return new SessionStore(existing);
    }

    public synchronized String appendMessage(String role, JSONArray content) {
        return appendMessage(role, content, "", "", "");
    }

    public synchronized String appendMessage(String role, JSONArray content, String messageId, String turnId, String origin) {
        String id = messageId == null || messageId.trim().isEmpty() ? UUID.randomUUID().toString() : messageId;
        try {
            JSONObject row = new JSONObject();
            row.put("type", "message");
            row.put("role", role);
            row.put("content", content);
            row.put("message_id", id);
            if (turnId != null && !turnId.trim().isEmpty()) row.put("turn_id", turnId);
            if (origin != null && !origin.trim().isEmpty()) row.put("origin", origin);
            row.put("timestamp", System.currentTimeMillis());
            append(row);
        } catch (Exception ignored) { }
        return id;
    }

    public synchronized void appendProfileBinding(SessionConfig config) {
        appendEvent("profile_binding", profilePayload(config));
    }

    public static void updateSessionMetadata(File file,String note,String titleOverride)throws Exception{
        if(file==null||!file.isFile())throw new IllegalArgumentException("会话不存在");
        File canonical=file.getCanonicalFile();
        SessionMetadata metadata=new SessionMetadata(note,titleOverride);
        JSONObject row=new JSONObject().put("type","session_metadata").put("payload",new JSONObject().put("note",metadata.note).put("title_override",metadata.titleOverride)).put("timestamp",System.currentTimeMillis());
        synchronized(fileLock(canonical)){
            long modified=canonical.lastModified();
            appendRowSynced(canonical,row);
            canonical.setLastModified(modified);
            fsyncDirectory(canonical.getParentFile());
        }
    }

    public synchronized void appendTurnConfig(SessionConfig config, String turnId) {
        try {
            JSONObject payload = profilePayload(config);
            payload.put("turn_id", turnId == null ? "" : turnId);
            appendEvent("turn_config", payload);
        } catch (Exception ignored) { }
    }

    public synchronized void appendEvent(String type, JSONObject payload) {
        try {
            JSONObject row = new JSONObject();
            row.put("type", type);
            row.put("payload", payload == null ? new JSONObject() : payload);
            row.put("timestamp", System.currentTimeMillis());
            append(row);
        } catch (Exception ignored) { }
    }

    private static JSONObject profilePayload(SessionConfig config) {
        JSONObject payload = new JSONObject();
        try {
            if (config != null) {
                payload.put("profile_id", config.profileId == null ? "" : config.profileId);
                payload.put("profile_revision", config.profileRevision);
                payload.put("credential_revision", config.credentialRevision);
                payload.put("protocol", config.protocol == null ? "" : config.protocol);
                payload.put("base_url", config.baseUrl == null ? "" : config.baseUrl);
                payload.put("model", config.model == null ? "" : config.model);
            }
        } catch (Exception ignored) { }
        return payload;
    }

    public synchronized void appendCompaction(String summary, int removedMessages, int retainedMessages) {
        appendCompaction(summary, removedMessages, retainedMessages, -1, -1, "legacy", "");
    }

    public synchronized void appendCompaction(String summary, int removedMessages, int retainedMessages,
                                              int beforeTokens, int afterTokens, String trigger, String model) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("summary", summary == null ? "" : summary);
            payload.put("removed_messages", removedMessages);
            payload.put("retained_messages", retainedMessages);
            payload.put("strategy", "model_semantic_api_round_v2");
            payload.put("trigger", trigger == null ? "unknown" : trigger);
            payload.put("model", model == null ? "" : model);
            if (beforeTokens >= 0) payload.put("before_tokens", beforeTokens);
            if (afterTokens >= 0) payload.put("after_tokens", afterTokens);
            appendEvent("context_compaction", payload);
        } catch (Exception ignored) { }
    }

    /** Persist the provider-facing compacted context while keeping the full human transcript in message rows. */
    public synchronized void appendContextSnapshot(JSONArray messages) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("messages", messages == null ? new JSONArray() : new JSONArray(messages.toString()));
            appendEvent("context_snapshot", payload);
        } catch (Exception ignored) { }
    }

    public File getSessionFile() { return sessionFile; }

    /** Legacy pre-v0.20.10 global directory, retained only for startup migration compatibility. */
    public static File sessionDirectory() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".iq/sessions");
    }

    public static File projectsDirectory() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".iq/projects");
    }

    public static File sessionDirectory(String projectDirectory) {
        String project=canonicalProject(projectDirectory);
        File dir=new File(projectsDirectory(),projectKey(project));
        if(!dir.isDirectory())dir.mkdirs();
        writeProjectIndex(dir,project);
        return dir;
    }

    public static String canonicalProject(String projectDirectory) {
        String raw=projectDirectory==null||projectDirectory.trim().isEmpty()?TermuxConstants.TERMUX_HOME_DIR_PATH:projectDirectory.trim();
        try{return new File(raw).getCanonicalPath();}catch(Exception ignored){return new File(raw).getAbsolutePath();}
    }

    /** Readable path slug plus a hash suffix prevents collisions between punctuation/symlink variants. */
    public static String projectKey(String projectDirectory) {
        String canonical=canonicalProject(projectDirectory);
        String slug=canonical.replaceAll("[^A-Za-z0-9._-]+","-").replaceAll("-+","-");
        if(slug.length()>72)slug=slug.substring(slug.length()-72);
        if(slug.isEmpty())slug="project";
        return slug+"-"+shortHash(canonical);
    }


    /** Permanently delete one saved local conversation. */
    public static boolean deleteSession(File file) {
        if (file == null) return false;
        try {
            File legacy = sessionDirectory().getCanonicalFile();
            File projects = projectsDirectory().getCanonicalFile();
            File target = file.getCanonicalFile();
            boolean legacySession=target.getParentFile()!=null&&target.getParentFile().equals(legacy);
            boolean projectSession=under(projects,target)&&target.getParentFile()!=null&&target.getParentFile().getParentFile()!=null&&target.getParentFile().getParentFile().equals(projects);
            if ((!legacySession&&!projectSession) || !target.getName().endsWith(".jsonl")) return false;
            synchronized(fileLock(target)){return !target.exists()||target.delete();}
        } catch (Exception e) { return false; }
    }

    public static List<SessionSummary> listSessions() {
        List<SessionSummary> out = new ArrayList<>();
        addSessions(out,sessionDirectory());
        File[] projectDirs=projectsDirectory().listFiles(File::isDirectory);
        if(projectDirs!=null)for(File dir:projectDirs)addSessions(out,dir);
        Collections.sort(out, (a, b) -> Long.compare(b.activityModifiedAt, a.activityModifiedAt));
        return out;
    }

    /** Return only the history bound to the selected canonical project path. */
    public static List<SessionSummary> listSessions(String projectDirectory) {
        String wanted=canonicalProject(projectDirectory);
        List<SessionSummary> out=new ArrayList<>();
        addSessions(out,sessionDirectory(wanted));
        // If startup migration could not move a legacy file, keep it discoverable under its project.
        File[] legacy=sessionDirectory().listFiles((d,n)->n.endsWith(".jsonl"));
        if(legacy!=null)for(File file:legacy)try{SessionSummary s=summarize(file);if(sameProject(wanted,s.project))out.add(s);}catch(Exception ignored){}
        Collections.sort(out,(a,b)->Long.compare(b.modifiedAt,a.modifiedAt));
        return out;
    }

    /**
     * One-time safe migration from ~/.iq/sessions. Called before any engine opens a session, so
     * moving files cannot invalidate a live runtime. Returns the new path for the saved last session.
     */
    public static synchronized File migrateLegacySessions(File preferred) {
        File[] files=sessionDirectory().listFiles((d,n)->n.endsWith(".jsonl"));
        if(files==null||files.length==0)return preferred;
        File resolvedPreferred=preferred;
        for(File source:files){
            try{
                SessionSummary summary=summarize(source);if(summary.project.trim().isEmpty())continue;
                File target=new File(sessionDirectory(summary.project),source.getName());
                if(target.exists())target=new File(target.getParentFile(),source.getName().replace(".jsonl","-legacy-"+UUID.randomUUID().toString().substring(0,6)+".jsonl"));
                boolean moved=source.renameTo(target);
                if(!moved){File partial=new File(target.getParentFile(),target.getName()+".migrating");try{copyFile(source,partial);moved=partial.isFile()&&partial.length()==source.length()&&partial.renameTo(target)&&source.delete();if(!moved){partial.delete();target.delete();}}catch(Exception copyError){partial.delete();target.delete();moved=false;}}
                if(moved&&preferred!=null&&sameFile(preferred,source))resolvedPreferred=target;
            }catch(Exception ignored){}
        }
        return resolvedPreferred;
    }

    public static SessionSummary summarize(File file) throws Exception {
        String project = "";
        String title = "";
        String note = "";
        String titleOverride = "";
        long created = file.lastModified();
        long activity = file.lastModified();
        int count = 0;
        JSONArray rows = readRows(file);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String type = row.optString("type", "");
            long timestamp = row.optLong("timestamp", 0L);
            if (!"session_metadata".equals(type) && timestamp > activity) activity = timestamp;
            if ("session_start".equals(type)) {
                project = row.optString("project", project);
                created = row.optLong("created_at", created);
            } else if ("session_metadata".equals(type)) {
                JSONObject payload=row.optJSONObject("payload");
                if(payload!=null){note=sanitizeMetadata(payload.optString("note",""),MAX_NOTE_CHARS);titleOverride=sanitizeMetadata(payload.optString("title_override",""),120).replace('\n',' ');}
            } else if ("message".equals(type)) {
                count++;
                if (title.isEmpty() && "user".equals(row.optString("role"))) title = firstHumanText(row.optJSONArray("content"));
            }
        }
        if(!titleOverride.isEmpty())title=titleOverride;
        if (title.isEmpty()) {
            String folder = project.isEmpty() ? "Session" : new File(project).getName();
            title = folder == null || folder.isEmpty() ? "Session" : folder;
        }
        return new SessionSummary(file, project, compactTitle(title), note, titleOverride, created, activity, count);
    }

    public static JSONArray readRows(File file) throws Exception {
        JSONArray rows = new JSONArray();
        if (file == null || !file.isFile()) return rows;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                try { rows.put(new JSONObject(s)); } catch (Exception ignored) { }
            }
        }
        return rows;
    }

    /**
     * Rebuilds provider messages. A context_snapshot resets the provider-facing history
     * to the compacted state; message rows written after that snapshot are then appended.
     * The original rows remain in the file so the UI can still render the full transcript.
     */
    public static JSONArray loadMessages(File file) throws Exception {
        JSONArray out = new JSONArray();
        JSONArray rows = readRows(file);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            if ("context_snapshot".equals(row.optString("type"))) {
                JSONObject payload = row.optJSONObject("payload");
                JSONArray snapshot = payload == null ? null : payload.optJSONArray("messages");
                if (snapshot != null) out = new JSONArray(snapshot.toString());
                continue;
            }
            if (!"message".equals(row.optString("type"))) continue;
            JSONArray content = row.optJSONArray("content");
            if (content == null) continue;
            out.put(new JSONObject().put("role", row.optString("role", "user")).put("content", new JSONArray(content.toString())));
        }
        return normalizeProviderMessages(out);
    }

    private static JSONArray normalizeProviderMessages(JSONArray input) throws Exception {
        JSONArray normalized=new JSONArray();
        for(int i=0;i<input.length();i++){
            JSONObject message=input.optJSONObject(i);if(message==null)continue;
            String role=message.optString("role","user");JSONArray content=message.optJSONArray("content");if(content==null)continue;
            JSONObject previous=normalized.length()==0?null:normalized.optJSONObject(normalized.length()-1);
            if(previous!=null&&role.equals(previous.optString("role"))&&canMergeProviderMessages(previous.optJSONArray("content"),content)){
                JSONArray merged=previous.optJSONArray("content");for(int j=0;j<content.length();j++)merged.put(new JSONObject(content.getJSONObject(j).toString()));
            }else normalized.put(new JSONObject().put("role",role).put("content",new JSONArray(content.toString())));
        }
        return normalized;
    }

    private static boolean canMergeProviderMessages(JSONArray left,JSONArray right){
        // Content arrays are concatenated without reordering, so tool_use/tool_result blocks retain
        // their relative position while adjacent same-role messages become one provider turn.
        return left!=null&&right!=null;
    }

    public static String loadWorkflowId(File file) {
        try {
            JSONArray rows = readRows(file);
            String workflow = "";
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i); if (row == null) continue;
                if ("session_start".equals(row.optString("type"))) workflow = row.optString("workflow_id", workflow);
                JSONObject payload = row.optJSONObject("payload");
                if (payload != null && ("config".equals(row.optString("type")) || row.optString("type").startsWith("plan_")))
                    workflow = payload.optString("workflow_id", workflow);
            }
            if (!workflow.trim().isEmpty()) return workflow;
        } catch (Exception ignored) { }
        try { return "session-" + shortHash(file.getCanonicalPath()); }
        catch (Exception e) { return "session-" + shortHash(file == null ? "session" : file.getAbsolutePath()); }
    }

    public static ProfileBinding loadProfileBinding(File file) {
        try {
            JSONArray rows = readRows(file);
            ProfileBinding binding = null;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String type = row.optString("type", "");
                if (!"profile_binding".equals(type) && !"turn_config".equals(type)) continue;
                JSONObject payload = row.optJSONObject("payload");
                if (payload != null && !payload.optString("profile_id", "").isEmpty()) binding = new ProfileBinding(payload);
            }
            return binding;
        } catch (Exception ignored) { return null; }
    }

    /** Stable digest of the exact persisted content array used to validate a UI edit target. */
    public static String messageContentHash(JSONArray content) {
        return shortHash(content == null ? "[]" : content.toString());
    }

    public static MessageReference messageReference(JSONObject row, int rowIndex) {
        if (row == null || !"message".equals(row.optString("type", ""))) return null;
        String role = row.optString("role", "");
        if (!"user".equals(role) && !"assistant".equals(role)) return null;
        if ("user".equals(role)) {
            String origin = row.optString("origin", "");
            if (!origin.isEmpty() && !"human".equals(origin)) return null;
        }
        JSONArray content = row.optJSONArray("content");
        if ("user".equals(role) && !hasHumanContent(content)) return null;
        if ("assistant".equals(role) && !hasEditableContent(content, role)) return null;
        return new MessageReference(row.optString("message_id", ""), rowIndex, messageContentHash(content));
    }

    public static void editHumanMessage(File file,MessageReference target,String newText)throws Exception{
        String replacement=newText==null?"":newText.trim();
        if(replacement.isEmpty())throw new IllegalArgumentException("消息不能为空");
        mutateHumanMessage(file,target,replacement,false);
    }

    public static void deleteHumanMessage(File file,MessageReference target)throws Exception{
        mutateHumanMessage(file,target,"",true);
    }

    /** Edit persisted user, assistant text, or assistant thinking/reasoning content. */
    public static void editMessage(File file, MessageReference target, String replacement) throws Exception {
        String value = replacement == null ? "" : replacement.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("消息内容不能为空");
        mutateHumanMessage(file, target, value, false);
    }

    public static void deleteMessage(File file, MessageReference target) throws Exception {
        mutateHumanMessage(file, target, "", true);
    }

    private static void mutateHumanMessage(File file,MessageReference target,String replacement,boolean delete)throws Exception{
        if(file==null||!file.isFile()||target==null)throw new IllegalArgumentException("无效的会话消息");
        File canonical=file.getCanonicalFile();
        synchronized(fileLock(canonical)){
            List<StrictRow> rows=readStrictRows(canonical);
            int targetListIndex=findStrictTarget(rows,target);
            if(targetListIndex<0)throw new IllegalStateException("要修改的历史消息已变化，请重新打开会话");
            ArrayList<String> output=new ArrayList<>();
            for(int i=0;i<rows.size();i++){
                StrictRow row=rows.get(i);
                if(i==targetListIndex){
                    if(!delete)output.add(rewriteHumanText(row.json,replacement).toString());
                    continue;
                }
                if(i>targetListIndex&&row.json!=null&&invalidatedContextEvent(row.json.optString("type","")))continue;
                output.add(row.raw);
            }
            atomicReplace(canonical,output,canonical.lastModified());
        }
    }

    private static int findStrictTarget(List<StrictRow> rows,MessageReference target)throws Exception{
        if (target == null) throw new IllegalArgumentException("无效的会话消息引用");
        if (target.contentHash.trim().isEmpty()) throw new IllegalArgumentException("消息引用缺少内容校验");
        boolean idReference=!target.messageId.trim().isEmpty();
        if (!idReference && target.legacyRowIndex < 0) throw new IllegalArgumentException("消息引用缺少稳定位置");

        if (idReference) {
            int occurrences=0;
            for (StrictRow strict : rows) {
                if (strict.json != null && "message".equals(strict.json.optString("type", ""))
                    && target.messageId.equals(strict.json.optString("message_id", ""))) occurrences++;
            }
            if (occurrences > 1) throw new IllegalStateException("消息标识重复，已拒绝修改");
        }

        int match=-1;
        for(int i=0;i<rows.size();i++){
            StrictRow strict=rows.get(i);if(strict.json==null)continue;
            MessageReference candidate=messageReference(strict.json,strict.jsonIndex);if(candidate==null)continue;
            boolean matches;
            if(idReference) {
                matches=target.messageId.equals(candidate.messageId)&&target.contentHash.equals(candidate.contentHash);
            } else {
                matches=candidate.messageId.isEmpty()&&target.legacyRowIndex==candidate.legacyRowIndex&&target.contentHash.equals(candidate.contentHash);
            }
            if(!matches)continue;
            if(match>=0)throw new IllegalStateException("消息标识重复，已拒绝修改");
            match=i;
        }
        return match;
    }

    private static JSONObject rewriteHumanText(JSONObject original,String replacement)throws Exception{
        JSONObject rewritten=new JSONObject(original.toString());
        JSONArray before=original.optJSONArray("content");if(before==null)throw new IllegalStateException("消息内容无效");
        JSONArray after=new JSONArray();boolean inserted=false;
        for(int i=0;i<before.length();i++){
            JSONObject block=before.optJSONObject(i);if(block==null)continue;
            String type=block.optString("type","");String text=block.optString("text","");
            String thinking=block.optString("thinking","");
            boolean editable=("text".equals(type)&&!text.trim().startsWith("<context_summary>")&&!text.trim().startsWith("<iq_internal_continue>"))
                || (("thinking".equals(type)||"reasoning".equals(type))&&!thinking.trim().isEmpty());
            if(editable){
                if(!inserted){
                    JSONObject edited=new JSONObject(block.toString());
                    if("text".equals(type)) edited.put("text",replacement); else edited.put("thinking",replacement);
                    after.put(edited); inserted=true;
                }
                continue;
            }
            after.put(new JSONObject(block.toString()));
        }
        if(!inserted)after.put(new JSONObject().put("type","text").put("text",replacement));
        rewritten.put("content",after);return rewritten;
    }

    private static boolean invalidatedContextEvent(String type){
        return "context_snapshot".equals(type)
            || "context_compaction".equals(type)
            || "context_usage".equals(type)
            || "context_compaction_failed".equals(type)
            || (type != null && type.startsWith("context_"));
    }

    private static List<StrictRow> readStrictRows(File file)throws Exception{
        ArrayList<StrictRow> rows=new ArrayList<>();int jsonIndex=0;int physicalLine=0;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){
            String line;while((line=reader.readLine())!=null){physicalLine++;String trimmed=line.trim();if(trimmed.isEmpty()){rows.add(new StrictRow(line,null,-1));continue;}
                try{rows.add(new StrictRow(line,new JSONObject(trimmed),jsonIndex++));}
                catch(Exception error){throw new IllegalStateException("会话历史第 "+physicalLine+" 行损坏，已拒绝修改",error);}
            }
        }
        return rows;
    }

    private static void atomicReplace(File target,List<String> rows,long originalModifiedAt)throws Exception{
        File parent=target.getParentFile();if(parent==null)throw new IllegalStateException("会话目录无效");
        File temp=new File(parent,target.getName()+".rewrite-"+UUID.randomUUID().toString()+".tmp");
        try(FileOutputStream stream=new FileOutputStream(temp,false)){
            for(String row:rows){stream.write(row.getBytes(StandardCharsets.UTF_8));stream.write('\n');}
            stream.flush();stream.getFD().sync();
        }catch(Exception error){temp.delete();throw error;}
        try{Os.rename(temp.getAbsolutePath(),target.getAbsolutePath());target.setLastModified(originalModifiedAt);fsyncDirectory(parent);}
        catch(Exception error){temp.delete();throw error;}
    }

    private static void fsyncDirectory(File directory){
        FileDescriptor descriptor=null;
        try{descriptor=Os.open(directory.getAbsolutePath(),OsConstants.O_RDONLY,0);Os.fsync(descriptor);}
        catch(Exception ignored){}
        finally{if(descriptor!=null)try{Os.close(descriptor);}catch(Exception ignored){}}
    }

    public static PlanWorkflowState loadPlanState(File file) {
        String workflow = loadWorkflowId(file);
        PlanWorkflowState state = PlanWorkflowState.idle();
        try {
            JSONArray rows = readRows(file);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i); if (row == null) continue;
                String type = row.optString("type", "");
                if (!type.startsWith("plan_")) continue;
                JSONObject p = row.optJSONObject("payload"); if (p == null) continue;
                String status = p.optString("status", "");
                PlanWorkflowState.Status parsed;
                try { parsed = PlanWorkflowState.Status.valueOf(status); }
                catch (Exception ignored) {
                    parsed = "plan_entered".equals(type) ? PlanWorkflowState.PLANNING
                        : "plan_proposed".equals(type) ? PlanWorkflowState.AWAITING_APPROVAL
                        : "plan_approved".equals(type) ? PlanWorkflowState.EXECUTING
                        : "plan_cancelled".equals(type) ? PlanWorkflowState.CANCELLED
                        : PlanWorkflowState.PLANNING;
                }
                state = PlanWorkflowState.restore(parsed, p.optString("workflow_id", workflow),
                    p.optLong("revision", state.revision), p.optString("previous_permission_mode", state.previousPermissionMode),
                    p.optString("approved_permission_mode", state.approvedPermissionMode), p.optString("plan_file", state.planFile),
                    p.optString("plan_text", state.planText), p.optString("feedback", state.feedback),
                    p.optLong("updated_at", row.optLong("timestamp", System.currentTimeMillis())));
            }
        } catch (Exception ignored) { }
        return state;
    }

    private static boolean hasHumanContent(JSONArray content) {
        return hasEditableContent(content, "user");
    }

    private static boolean hasEditableContent(JSONArray content, String role) {
        if (content == null) return false;
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            String type = block.optString("type", "");
            if ("user".equals(role) && "image".equals(type)) return true;
            if ("text".equals(type) || ("assistant".equals(role) && ("thinking".equals(type) || "reasoning".equals(type)))) {
                String key = "text".equals(type) ? "text" : "thinking";
                if (!block.optString(key, "").trim().isEmpty()) return true;
            }
        }
        return false;
    }

    private static String firstHumanText(JSONArray content) {
        if (content == null) return "";
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            if ("text".equals(block.optString("type"))) {
                String t = block.optString("text", "").trim();
                if (!t.isEmpty() && !t.startsWith("<context_summary>") && !t.startsWith("<iq_internal_continue>")) return t;
            }
        }
        return "";
    }

    private static String compactTitle(String s) {
        String x = s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
        return x.length() <= 48 ? x : x.substring(0, 47) + "…";
    }

    private static String sanitizeMetadata(String value,int maxChars){
        String input=value==null?"":value.replace("\r\n","\n").replace('\r','\n');StringBuilder clean=new StringBuilder();
        for(int i=0;i<input.length()&&clean.length()<maxChars;i++){char c=input.charAt(i);if(c=='\n'||c=='\t'||!Character.isISOControl(c))clean.append(c);}
        return clean.toString().trim();
    }

    private static void addSessions(List<SessionSummary> out,File dir){File[] files=dir.listFiles((d,n)->n.endsWith(".jsonl"));if(files!=null)for(File file:files)try{out.add(summarize(file));}catch(Exception ignored){}}
    private static boolean sameProject(String a,String b){return canonicalProject(a).equals(canonicalProject(b));}
    private static boolean sameFile(File a,File b){try{return a.getCanonicalFile().equals(b.getCanonicalFile());}catch(Exception e){return a.getAbsolutePath().equals(b.getAbsolutePath());}}
    private static boolean under(File root,File child){try{String r=root.getCanonicalPath()+File.separator;return child.getCanonicalPath().startsWith(r);}catch(Exception e){return false;}}
    private static void copyFile(File from,File to)throws Exception{File parent=to.getParentFile();if(parent!=null)parent.mkdirs();try(FileInputStream in=new FileInputStream(from);FileOutputStream out=new FileOutputStream(to,false)){byte[]buf=new byte[32768];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);out.getFD().sync();}}
    private static String shortHash(String value){try{byte[]d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(int i=0;i<6;i++)b.append(String.format(Locale.US,"%02x",d[i]&255));return b.toString();}catch(Exception e){return Integer.toHexString(value.hashCode());}}
    private static void writeProjectIndex(File dir,String project){try{File f=new File(dir,"project.json");JSONObject o=new JSONObject().put("version",1).put("project",project).put("display_name",new File(project).getName()).put("updated_at",System.currentTimeMillis());try(FileOutputStream out=new FileOutputStream(f,false)){out.write(o.toString(2).getBytes(StandardCharsets.UTF_8));}}catch(Exception ignored){}}

    private void append(JSONObject row) throws Exception {
        File target=sessionFile.getCanonicalFile();
        synchronized(fileLock(target)){appendRow(target,row);}
    }

    private static void appendRow(File target,JSONObject row)throws Exception{
        File parent=target.getParentFile();if(parent!=null&&!parent.isDirectory())parent.mkdirs();
        try(FileOutputStream out=new FileOutputStream(target,true)){
            out.write(row.toString().getBytes(StandardCharsets.UTF_8));out.write('\n');out.flush();
        }
    }

    private static void appendRowSynced(File target,JSONObject row)throws Exception{
        File parent=target.getParentFile();if(parent!=null&&!parent.isDirectory())parent.mkdirs();
        try(FileOutputStream out=new FileOutputStream(target,true)){
            out.write(row.toString().getBytes(StandardCharsets.UTF_8));out.write('\n');out.flush();out.getFD().sync();
        }
    }

    private static Object fileLock(File file)throws Exception{
        String key=file.getCanonicalPath();Object created=new Object();Object existing=FILE_LOCKS.putIfAbsent(key,created);return existing==null?created:existing;
    }
}

package com.termux.app.iqcode.tasks;

import com.termux.app.iqcode.storage.SessionStore;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** File-backed task list compatible with IQ Code's TaskCreate/Get/List/Update semantics. */
public final class TaskStore {
    public interface Listener {
        void onTasksChanged(Snapshot snapshot);
    }

    public static final class Subscription implements AutoCloseable {
        private final String key;
        private final Listener listener;
        private boolean active = true;

        private Subscription(String key, Listener listener) {
            this.key = key;
            this.listener = listener;
        }

        public void unsubscribe() {
            synchronized (SUBSCRIBERS_LOCK) {
                if (!active) return;
                active = false;
                List<Listener> listeners = SUBSCRIBERS.get(key);
                if (listeners == null) return;
                listeners.remove(listener);
                if (listeners.isEmpty()) SUBSCRIBERS.remove(key);
            }
        }

        @Override public void close() {
            unsubscribe();
        }
    }

    public static final class Snapshot {
        public final long version;
        public final String workflowId;
        public final List<JSONObject> tasks;

        public Snapshot(long version, String workflowId, List<JSONObject> tasks) {
            this.version = version;
            this.workflowId = normalizeWorkflowId(workflowId);
            this.tasks = Collections.unmodifiableList(deepCopyTasks(tasks));
        }

        public long getVersion() { return version; }
        public String getWorkflowId() { return workflowId; }
        public List<JSONObject> getTasks() { return tasks; }
    }

    private interface LockedAction<T> {
        T run() throws Exception;
    }

    private static final class Mutation<T> {
        final T result;
        final Snapshot snapshot;

        Mutation(T result, Snapshot snapshot) {
            this.result = result;
            this.snapshot = snapshot;
        }
    }

    private static final Object DIRECTORY_LOCKS_LOCK = new Object();
    private static final Map<String, Object> DIRECTORY_LOCKS = new HashMap<>();
    private static final Object SUBSCRIBERS_LOCK = new Object();
    private static final Map<String, List<Listener>> SUBSCRIBERS = new HashMap<>();

    private final File dir;
    private final File highWater;
    private final File versionFile;
    private final File lockFile;
    private final String directoryKey;
    private final String workflowId;
    private final String subscriberKey;

    public TaskStore(String projectDirectory) {
        this(projectDirectory, "");
    }

    public TaskStore(String projectDirectory, String workflowId) {
        String canonicalProject = SessionStore.canonicalProject(projectDirectory);
        String key = SessionStore.projectKey(canonicalProject);
        dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".iq/tasks/" + key);
        highWater = new File(dir, ".highwatermark");
        versionFile = new File(dir, ".version");
        lockFile = new File(dir, ".lock");
        directoryKey = canonicalPath(dir);
        this.workflowId = normalizeWorkflowId(workflowId);
        subscriberKey = directoryKey + '\n' + this.workflowId;
    }

    public static Subscription subscribe(String projectDirectory, String workflowId, Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener == null");
        TaskStore store = new TaskStore(projectDirectory, workflowId);
        synchronized (SUBSCRIBERS_LOCK) {
            List<Listener> listeners = SUBSCRIBERS.get(store.subscriberKey);
            if (listeners == null) {
                listeners = new ArrayList<>();
                SUBSCRIBERS.put(store.subscriberKey, listeners);
            }
            listeners.add(listener);
        }
        return new Subscription(store.subscriberKey, listener);
    }

    public JSONObject create(String subject, String description, String activeForm, JSONObject metadata) throws Exception {
        Mutation<JSONObject> mutation = withDirectoryLock(() -> {
            int id = Math.max(readHighWaterUnlocked(), highestExistingIdUnlocked()) + 1;
            long now = System.currentTimeMillis();
            JSONObject task = new JSONObject()
                .put("id", String.valueOf(id))
                .put("subject", subject)
                .put("description", description)
                .put("status", "pending")
                .put("blocks", new JSONArray())
                .put("blockedBy", new JSONArray())
                .put("workflow_id", workflowId)
                .put("created_at", now)
                .put("updated_at", now);
            if (activeForm != null && !activeForm.isEmpty()) task.put("activeForm", activeForm);
            if (metadata != null) task.put("metadata", new JSONObject(metadata.toString()));
            writeTaskUnlocked(task);
            writeText(highWater, String.valueOf(id));
            long version = incrementVersionUnlocked();
            return new Mutation<>(task, snapshotUnlocked(version));
        });
        notifySubscribers(mutation.snapshot);
        return mutation.result;
    }

    public JSONObject get(String id) {
        try {
            return withDirectoryLock(() -> readTaskUnlocked(id));
        } catch (Exception e) {
            return null;
        }
    }

    public List<JSONObject> list() {
        try {
            return withDirectoryLock(this::listUnlocked);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public Snapshot snapshot() {
        try {
            return withDirectoryLock(() -> snapshotUnlocked(readVersionUnlocked()));
        } catch (Exception e) {
            return new Snapshot(0, workflowId, Collections.emptyList());
        }
    }

    public JSONObject update(String id, JSONObject patch) throws Exception {
        Mutation<JSONObject> mutation = withDirectoryLock(() -> updateUnlocked(id, patch));
        if (mutation == null) return null;
        notifySubscribers(mutation.snapshot);
        return mutation.result;
    }

    private Mutation<JSONObject> updateUnlocked(String id, JSONObject patch) throws Exception {
        JSONObject task = readTaskUnlocked(id);
        if (task == null) return null;
        if (patch.has("status") && "deleted".equals(patch.optString("status"))) {
            if (!taskFile(id).delete()) throw new IllegalStateException("Failed to delete task #" + id);
            long version = incrementVersionUnlocked();
            JSONObject result = new JSONObject().put("id", id).put("deleted", true);
            return new Mutation<>(result, snapshotUnlocked(version));
        }

        String before = task.toString();
        long now = System.currentTimeMillis();
        if (!task.has("workflow_id")) task.put("workflow_id", workflowId);
        if (!task.has("created_at")) {
            long createdAt = taskFile(id).lastModified();
            task.put("created_at", createdAt > 0 ? createdAt : now);
        }
        copyStringIfPresent(patch, task, "subject");
        copyStringIfPresent(patch, task, "description");
        copyStringIfPresent(patch, task, "activeForm");
        copyStringIfPresent(patch, task, "owner");
        if (patch.has("status")) {
            String status = patch.optString("status", "");
            if (!"pending".equals(status) && !"in_progress".equals(status) && !"completed".equals(status))
                throw new IllegalArgumentException("Invalid task status: " + status);
            task.put("status", status);
        }
        addUnique(task, "blocks", patch.optJSONArray("addBlocks"));
        addUnique(task, "blockedBy", patch.optJSONArray("addBlockedBy"));
        JSONObject metadata = patch.optJSONObject("metadata");
        if (metadata != null) {
            JSONObject merged = task.optJSONObject("metadata");
            if (merged == null) merged = new JSONObject();
            JSONArray names = metadata.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i, "");
                if (metadata.isNull(key)) merged.remove(key); else merged.put(key, metadata.get(key));
            }
            task.put("metadata", merged);
        }
        if (before.equals(task.toString())) return new Mutation<>(task, null);

        task.put("updated_at", now);
        writeTaskUnlocked(task);
        long version = incrementVersionUnlocked();
        return new Mutation<>(task, snapshotUnlocked(version));
    }

    private <T> T withDirectoryLock(LockedAction<T> action) throws Exception {
        Object jvmLock = directoryLock(directoryKey);
        synchronized (jvmLock) {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
                throw new IllegalStateException("Cannot create task directory " + dir);
            try (RandomAccessFile lockAccess = new RandomAccessFile(lockFile, "rw");
                 FileChannel channel = lockAccess.getChannel();
                 FileLock ignored = channel.lock()) {
                return action.run();
            }
        }
    }

    private JSONObject readTaskUnlocked(String id) throws Exception {
        File file = taskFile(id);
        if (!file.isFile()) return null;
        JSONObject task = new JSONObject(readText(file));
        return belongsToWorkflow(task) ? task : null;
    }

    private List<JSONObject> listUnlocked() {
        List<JSONObject> out = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.matches("\\d+\\.json"));
        if (files == null) return out;
        for (File file : files) {
            try {
                JSONObject task = new JSONObject(readText(file));
                if (belongsToWorkflow(task)) out.add(task);
            } catch (Exception ignored) { }
        }
        out.sort(Comparator.comparingInt(o -> parseId(o.optString("id", "0"))));
        return out;
    }

    private Snapshot snapshotUnlocked(long version) {
        return new Snapshot(version, workflowId, listUnlocked());
    }

    private boolean belongsToWorkflow(JSONObject task) {
        if (!task.has("workflow_id") || task.isNull("workflow_id")) return workflowId.isEmpty();
        return workflowId.equals(task.optString("workflow_id", ""));
    }

    private void writeTaskUnlocked(JSONObject task) throws Exception {
        File target = taskFile(task.getString("id"));
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        writeText(tmp, task.toString(2));
        if (target.exists() && !target.delete()) throw new IllegalStateException("Cannot replace " + target);
        if (!tmp.renameTo(target)) {
            // Cross-filesystem rename is not expected in the same Termux directory, but keep a fallback.
            writeText(target, task.toString(2));
            tmp.delete();
        }
    }

    private File taskFile(String id) {
        String safe = id == null ? "" : id.replaceAll("[^0-9]", "");
        if (safe.isEmpty()) throw new IllegalArgumentException("Invalid task ID: " + id);
        return new File(dir, safe + ".json");
    }

    private int highestExistingIdUnlocked() {
        int max = 0;
        File[] files = dir.listFiles((d, name) -> name.matches("\\d+\\.json"));
        if (files != null) for (File file : files)
            max = Math.max(max, parseId(file.getName().replace(".json", "")));
        return max;
    }

    private int readHighWaterUnlocked() {
        try { return parseId(readText(highWater).trim()); } catch (Exception e) { return 0; }
    }

    private long readVersionUnlocked() {
        try { return Long.parseLong(readText(versionFile).trim()); } catch (Exception e) { return 0; }
    }

    private long incrementVersionUnlocked() throws Exception {
        long version = readVersionUnlocked() + 1;
        writeText(versionFile, String.valueOf(version));
        return version;
    }

    private static void copyStringIfPresent(JSONObject from, JSONObject to, String key) throws Exception {
        if (from.has(key) && !from.isNull(key)) to.put(key, from.optString(key, ""));
    }

    private static void addUnique(JSONObject task, String key, JSONArray values) throws Exception {
        if (values == null) return;
        JSONArray current = task.optJSONArray(key);
        if (current == null) current = new JSONArray();
        for (int i = 0; i < values.length(); i++) {
            String value = values.optString(i, "");
            if (value.isEmpty()) continue;
            boolean found = false;
            for (int j = 0; j < current.length(); j++) {
                if (value.equals(current.optString(j))) {
                    found = true;
                    break;
                }
            }
            if (!found) current.put(value);
        }
        task.put(key, current);
    }

    private void notifySubscribers(Snapshot snapshot) {
        if (snapshot == null) return;
        List<Listener> listeners;
        synchronized (SUBSCRIBERS_LOCK) {
            List<Listener> registered = SUBSCRIBERS.get(subscriberKey);
            if (registered == null || registered.isEmpty()) return;
            listeners = new ArrayList<>(registered);
        }
        for (Listener listener : listeners) {
            try { listener.onTasksChanged(snapshot); } catch (RuntimeException ignored) { }
        }
    }

    private static Object directoryLock(String key) {
        synchronized (DIRECTORY_LOCKS_LOCK) {
            Object lock = DIRECTORY_LOCKS.get(key);
            if (lock == null) {
                lock = new Object();
                DIRECTORY_LOCKS.put(key, lock);
            }
            return lock;
        }
    }

    private static List<JSONObject> deepCopyTasks(List<JSONObject> tasks) {
        List<JSONObject> copy = new ArrayList<>();
        if (tasks == null) return copy;
        for (JSONObject task : tasks) {
            if (task == null) continue;
            try { copy.add(new JSONObject(task.toString())); }
            catch (Exception e) { throw new IllegalArgumentException("Cannot copy task snapshot", e); }
        }
        return copy;
    }

    private static int parseId(String value) {
        try { return Integer.parseInt(value); } catch (Exception e) { return 0; }
    }

    private static String readText(File file) throws Exception {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int pos = 0, n;
            while (pos < data.length && (n = in.read(data, pos, data.length - pos)) > 0) pos += n;
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void writeText(File file, String text) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String canonicalPath(File file) {
        try { return file.getCanonicalPath(); }
        catch (Exception e) { return file.getAbsolutePath(); }
    }

    private static String normalizeWorkflowId(String workflowId) {
        return workflowId == null ? "" : workflowId;
    }
}

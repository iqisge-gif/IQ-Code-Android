package com.termux.app.iqcode.storage;

import android.system.ErrnoException;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Stores project-scoped Markdown plans under ~/.iq/projects/&lt;project-key&gt;/plans. */
public final class PlanStore {
    private static final int MAX_WORKFLOW_ID_LENGTH = 128;

    private final File directory;

    public PlanStore(String projectDirectory) {
        directory = new File(SessionStore.projectsDirectory(),
            SessionStore.projectKey(projectDirectory) + File.separator + "plans");
    }

    public File getDirectory() {
        return directory;
    }

    public File getPlanFile(String workflowId) {
        return new File(directory, requireWorkflowId(workflowId) + ".md");
    }

    /**
     * Durably writes a complete plan and atomically publishes it with POSIX rename. Readers observe
     * either the preceding revision or the complete new revision, never a partially written file.
     */
    public synchronized File write(String workflowId, String planText) throws IOException {
        ensureDirectory();
        File target = getPlanFile(workflowId);
        File temporary = new File(directory,
            "." + target.getName() + "." + UUID.randomUUID().toString() + ".tmp");
        boolean published = false;
        try {
            byte[] data = (planText == null ? "" : planText).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(data);
                output.flush();
                output.getFD().sync();
            }
            try {
                Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
            } catch (ErrnoException e) {
                throw new IOException("Unable to publish plan " + target, e);
            }
            published = true;
            return target;
        } finally {
            if (!published && temporary.exists()) temporary.delete();
        }
    }

    public String read(String workflowId) throws IOException {
        File file = getPlanFile(workflowId);
        if (!file.isFile()) return "";
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                 file.length() > 0L && file.length() <= Integer.MAX_VALUE ? (int) file.length() : 1024)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public boolean exists(String workflowId) {
        return getPlanFile(workflowId).isFile();
    }

    public synchronized boolean delete(String workflowId) {
        File file = getPlanFile(workflowId);
        return !file.exists() || file.delete();
    }

    private void ensureDirectory() throws IOException {
        if (directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Unable to create plan directory " + directory);
        }
    }

    private static String requireWorkflowId(String workflowId) {
        String id = workflowId == null ? "" : workflowId.trim();
        if (id.isEmpty() || id.length() > MAX_WORKFLOW_ID_LENGTH
            || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
            || ".".equals(id) || "..".equals(id)) {
            throw new IllegalArgumentException("Invalid plan workflow ID: " + workflowId);
        }
        return id;
    }
}

package com.iqge.sandbox;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Cross-process settings shared by the sandbox controller and Guest processes. */
final class SandboxSettingsStore {
    private static final Object WRITE_LOCK = new Object();
    private static final int VERSION = 1;
    private static final int MAX_SETTINGS_BYTES = 64 * 1024;

    private SandboxSettingsStore() {}

    static boolean isRootHidden(Context context) {
        AtomicFile settings = atomicFile(context);
        if (!settings.getBaseFile().isFile()) return true;
        try (FileInputStream in = settings.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_SETTINGS_BYTES) return true;
                out.write(buffer, 0, read);
            }
            JSONObject value = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            if (value.optInt("version", 0) != VERSION) return true;
            return value.optBoolean("hide_root", true);
        } catch (Exception ignored) {
            return true;
        }
    }

    static boolean isFloatingLogEnabled(Context context) {
        return read(context).optBoolean("show_floating_log", true);
    }

    static void setFloatingLogEnabled(Context context, boolean enabled) throws Exception {
        synchronized (WRITE_LOCK) {
            JSONObject value = read(context);
            value.put("version", VERSION).put("show_floating_log", enabled);
            write(context, value);
        }
    }

    static void setRootHidden(Context context, boolean hidden) throws Exception {
        synchronized (WRITE_LOCK) {
            JSONObject value = read(context);
            value.put("version", VERSION).put("hide_root", hidden);
            write(context, value);
        }
    }

    private static JSONObject empty() {
        JSONObject value = new JSONObject();
        try { value.put("version", VERSION); } catch (Exception ignored) {}
        return value;
    }

    private static JSONObject read(Context context) {
        AtomicFile settings = atomicFile(context);
        if (!settings.getBaseFile().isFile()) return empty();
        try (FileInputStream in = settings.openRead(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_SETTINGS_BYTES) return empty();
                out.write(buffer, 0, read);
            }
            JSONObject value = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            return value.optInt("version", 0) == VERSION ? value : empty();
        } catch (Exception ignored) {
            return empty();
        }
    }

    private static void write(Context context, JSONObject value) throws Exception {
        AtomicFile settings = atomicFile(context);
        File parent = settings.getBaseFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IllegalStateException("Failed to create sandbox settings directory: " + parent);
        }
        byte[] data = value.toString(2).getBytes(StandardCharsets.UTF_8);
        FileOutputStream out = null;
        try {
            out = settings.startWrite();
            out.write(data);
            settings.finishWrite(out);
        } catch (Exception error) {
            if (out != null) settings.failWrite(out);
            throw error;
        }
    }

    private static AtomicFile atomicFile(Context context) {
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        return new AtomicFile(new File(app.getFilesDir(), "sandbox/settings.json"));
    }
}

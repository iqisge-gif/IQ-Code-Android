package com.iqge.sandbox;

import android.app.ActivityManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

import top.niunaijun.blackbox.entity.pm.InstallResult;

/**
 * Private same-UID RPC provider hosted in :iqsandbox. This is the only high-level
 * BlackBox control surface used by IQ Code's main process.
 */
public final class SandboxControlProvider extends ContentProvider {
    @Override public boolean onCreate() {
        // Providers are created before Application.onCreate(). BlackBox doCreate() must
        // stay in IQCodeApplication.onCreate(), matching the original IQSandbox order.
        // Running it here used to initialize the runtime too early on Android 15.
        SandboxDebugLog.event("SandboxControlProvider 已创建，等待 Application.onCreate 初始化引擎");
        // Provider publication happens before Application.onCreate. Queue a main-loop
        // initialization now so an immediate IPC from the dashboard cannot win the race.
        IQSandboxEngine.ensureCreateScheduled();
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle b = new Bundle();
        JSONObject out;
        try {
            if (!"control".equals(method)) throw new IllegalArgumentException("unknown method: " + method);
            JSONObject in = new JSONObject(extras == null ? "{}" : extras.getString("payload", "{}"));
            out = dispatch(arg == null ? "" : arg, in);
        } catch (Throwable e) {
            out = new JSONObject();
            try { out.put("ok", false).put("error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())); } catch (Throwable ignored) {}
            SandboxDebugLog.event("SandboxControlProvider 失败: " + e);
        }
        b.putString("result", out.toString());
        return b;
    }

    private JSONObject dispatch(String action, JSONObject in) throws Exception {
        // The provider can receive Binder calls before Application.onCreate(). Wait for
        // the actual BlackBox controller result instead of returning “引擎初始化中”.
        IQSandboxEngine.awaitReady(12000);
        JSONObject o = new JSONObject().put("ok", true).put("process", SandboxProcessRole.processName(getContext()));
        switch (action) {
            case "status":
                return o.put("status", IQSandboxEngine.status()).put("hide_root", IQSandboxEngine.isRootHidden())
                        .put("show_floating_log", SandboxSettingsStore.isFloatingLogEnabled(getContext()));
            case "set_show_floating_log": {
                if (!in.has("show_floating_log")) throw new IllegalArgumentException("需要 show_floating_log");
                boolean enabled = in.getBoolean("show_floating_log");
                return o.put("show_floating_log", IQSandboxEngine.setFloatingLogEnabled(enabled));
            }
            case "set_hide_root": {
                if (!in.has("hide_root")) throw new IllegalArgumentException("需要 hide_root");
                boolean hidden = in.getBoolean("hide_root");
                boolean changed = IQSandboxEngine.setRootHidden(hidden);
                return o.put("hide_root", IQSandboxEngine.isRootHidden()).put("changed", changed);
            }
            case "list": {
                JSONArray a = new JSONArray();
                for (ApplicationInfo ai : IQSandboxEngine.installedApplications()) a.put(new JSONObject().put("package", ai.packageName));
                return o.put("apps", a);
            }
            case "install": {
                File apk = new File(in.optString("path", ""));
                if (!apk.isFile()) throw new IllegalArgumentException("APK 不存在: " + apk);
                InstallResult r = IQSandboxEngine.install(apk);
                if (r == null) throw new IllegalStateException("沙箱安装没有返回结果");
                return o.put("success", r.success).put("package", r.packageName == null ? "" : r.packageName).put("message", r.msg == null ? "" : r.msg);
            }
            case "launch": {
                String pkg = requirePackage(in);
                boolean ok = IQSandboxEngine.launch(pkg);
                return o.put("success", ok).put("package", pkg);
            }
            case "stop": { String pkg=requirePackage(in); IQSandboxEngine.stop(pkg); return o.put("package",pkg); }
            case "clear_data": { String pkg=requirePackage(in); IQSandboxEngine.clearData(pkg); return o.put("package",pkg); }
            case "uninstall": { String pkg=requirePackage(in); IQSandboxEngine.uninstall(pkg); return o.put("package",pkg); }
            case "process_list": {
                String filter = in.optString("package", "").trim();
                JSONArray rows = new JSONArray();
                if (!filter.isEmpty()) appendProcesses(rows, filter);
                else for (ApplicationInfo ai : IQSandboxEngine.installedApplications()) appendProcesses(rows, ai.packageName);
                return o.put("processes", rows);
            }
            default: throw new IllegalArgumentException("未知 IQSandbox RPC action: " + action);
        }
    }

    private void appendProcesses(JSONArray out, String pkg) throws Exception {
        List<ActivityManager.RunningAppProcessInfo> list = IQSandboxEngine.runningProcesses(pkg);
        for (ActivityManager.RunningAppProcessInfo p : list) {
            out.put(new JSONObject().put("package",pkg).put("pid",p.pid).put("uid",p.uid).put("process",p.processName).put("importance",p.importance));
        }
    }

    private static String requirePackage(JSONObject in) {
        String p = in.optString("package", "").trim();
        if (p.isEmpty()) throw new IllegalArgumentException("需要 package");
        return p;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}

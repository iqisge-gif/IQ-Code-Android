package com.termux.app.iqcode.tools;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.iqge.IqFileProvider;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Starts Android activities from IQ Code's own application process.
 *
 * Embedded Termux binaries execute as Linux subprocesses. Calling Android's `am` implementation
 * from there can fail when its caller-package identity does not match the custom com.iqge UID.
 * This bridge deliberately performs Context.startActivity() in the IQ Code Java process instead.
 */
public final class AndroidIntentBridge {
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String INSTALL_PREFS = "iqge_pending_install";
    private static final String PENDING_APK_URI = "apk_uri";
    private static final AtomicReference<Intent> PENDING_APK_INSTALL = new AtomicReference<>();
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AndroidIntentBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public ToolExecutionResult execute(JSONObject input) {
        try {
            String operation = input.optString("operation", "intent").trim().toLowerCase(Locale.US);
            Intent intent;
            if ("launch_app".equals(operation) || "open_app".equals(operation)) {
                String pkg = input.optString("package", "").trim();
                String appName = input.optString("app_name", input.optString("app", "")).trim();
                if (pkg.isEmpty() && !appName.isEmpty()) pkg = findLaunchablePackageByLabel(appName);
                if (pkg.isEmpty()) return ToolExecutionResult.error("请提供 package 或 app_name");
                intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent == null) return ToolExecutionResult.error("找不到可启动的应用：" + (appName.isEmpty() ? pkg : appName + " (" + pkg + ")"));
            } else {
                String action = input.optString("action", Intent.ACTION_VIEW).trim();
                if (action.isEmpty()) action = Intent.ACTION_VIEW;
                String uri = input.optString("path", input.optString("uri", input.optString("data", ""))).trim();
                String mime = input.optString("mime_type", input.optString("type", "")).trim();
                if ("install_apk".equals(operation)) {
                    action = Intent.ACTION_VIEW;
                    mime = APK_MIME;
                }
                if (!uri.isEmpty() && !mime.isEmpty()) intent = new Intent(action).setDataAndType(Uri.parse(uri), mime);
                else if (!uri.isEmpty()) intent = new Intent(action, Uri.parse(uri));
                else intent = new Intent(action);

                String pkg = input.optString("package", "").trim();
                if (!pkg.isEmpty()) intent.setPackage(pkg);
                String component = input.optString("component", "").trim();
                if (!component.isEmpty()) {
                    ComponentName cn = ComponentName.unflattenFromString(normalizeComponent(component));
                    if (cn == null) return ToolExecutionResult.error("无效 component：" + component);
                    intent.setComponent(cn);
                }
                JSONArray categories = input.optJSONArray("categories");
                if (categories != null) for (int i = 0; i < categories.length(); i++) {
                    String c = categories.optString(i, "").trim(); if (!c.isEmpty()) intent.addCategory(c);
                }
                JSONObject extras = input.optJSONObject("extras");
                if (extras != null) putExtras(intent, extras);
                int flags = input.optInt("flags", 0);
                if (flags != 0) intent.addFlags(flags);
            }
            if (isApkInstallIntent(intent)) return installApk(intent);
            return start(intent);
        } catch (Throwable e) {
            return ToolExecutionResult.error("Android Intent 失败：" + readable(e));
        }
    }

    /** Called by MainActivity.onResume after the user returns from “Install unknown apps”. */
    public static ToolExecutionResult resumePendingApkInstall(Context context) {
        Intent pending = PENDING_APK_INSTALL.get();
        if (pending == null && context != null) pending = restorePendingApkIntent(context);
        if (pending == null || context == null) return null;
        if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) return null;
        Intent inMemory = PENDING_APK_INSTALL.getAndSet(null);
        if (inMemory != null) pending = inMemory;
        clearRememberedApk(context);
        try {
            ToolExecutionResult result = new AndroidIntentBridge(context).start(pending);
            if (result.isError) return result;
            return ToolExecutionResult.ok("已获得安装权限，正在打开 Android 系统安装器");
        } catch (Throwable error) {
            return ToolExecutionResult.error("继续安装 APK 失败：" + readable(error));
        }
    }

    private ToolExecutionResult installApk(Intent source) {
        try {
            Intent install = new Intent(source);
            Uri uri = install.getData();
            if (uri == null) return ToolExecutionResult.error("安装 APK 需要 path 或 uri");
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isEmpty() || "file".equalsIgnoreCase(scheme)) {
                String path = "file".equalsIgnoreCase(scheme) ? uri.getPath() : uri.toString();
                if (path == null || path.trim().isEmpty()) return ToolExecutionResult.error("APK 路径为空");
                uri = IqFileProvider.uriForFile(context, new File(path));
            } else if ("content".equalsIgnoreCase(scheme) && IqFileProvider.AUTHORITY.equals(uri.getAuthority())) {
                // Verify our URI before handing it to another process; this produces an actionable
                // error instead of a silent Package Installer failure for a missing APK.
                try (android.content.res.AssetFileDescriptor ignored = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                    if (ignored == null) return ToolExecutionResult.error("无法读取 APK：" + uri);
                }
            }

            install.setAction(Intent.ACTION_VIEW);
            install.setDataAndType(uri, APK_MIME);
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.setClipData(ClipData.newRawUri("IQ Code APK", uri));

            if (Build.VERSION.SDK_INT >= 26 && !context.getPackageManager().canRequestPackageInstalls()) {
                rememberPendingApk(context, install);
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
                settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ToolExecutionResult opened = start(settings);
                if (opened.isError) {
                    PENDING_APK_INSTALL.set(null);
                    clearRememberedApk(context);
                    return ToolExecutionResult.error("无法打开“安装未知应用”授权页：" + opened.content);
                }
                return ToolExecutionResult.ok("已打开“允许来自此来源的应用”授权页；开启后返回 IQ Code，将自动继续安装 APK。");
            }
            return start(install);
        } catch (Throwable error) {
            return ToolExecutionResult.error("安装 APK 失败：" + readable(error));
        }
    }

    private static boolean isApkInstallIntent(Intent intent) {
        if (intent == null) return false;
        if (Intent.ACTION_INSTALL_PACKAGE.equals(intent.getAction())) return true;
        if (APK_MIME.equalsIgnoreCase(intent.getType())) return true;
        Uri data = intent.getData();
        String path = data == null ? "" : String.valueOf(data.getPath()).toLowerCase(Locale.US);
        return path.endsWith(".apk");
    }

    private static void rememberPendingApk(Context context, Intent install) {
        PENDING_APK_INSTALL.set(new Intent(install));
        Uri uri = install.getData();
        if (uri != null && IqFileProvider.AUTHORITY.equals(uri.getAuthority())) {
            context.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE).edit()
                .putString(PENDING_APK_URI, uri.toString()).apply();
        }
    }

    private static Intent restorePendingApkIntent(Context context) {
        String value = context.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE)
            .getString(PENDING_APK_URI, "");
        if (value == null || value.trim().isEmpty()) return null;
        Uri uri = Uri.parse(value);
        if (!IqFileProvider.AUTHORITY.equals(uri.getAuthority())) {
            clearRememberedApk(context);
            return null;
        }
        Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME);
        install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        install.setClipData(ClipData.newRawUri("IQ Code APK", uri));
        return install;
    }

    private static void clearRememberedApk(Context context) {
        context.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE).edit()
            .remove(PENDING_APK_URI).apply();
    }

    /** Return null when this is not a simple `am start` command that IQ should intercept. */
    public ToolExecutionResult tryExecuteAmStart(String command) {
        if (command == null) return null;
        String trimmed = command.trim();
        if (!trimmed.matches("(?s)^am\\s+(?:start|start-activity)\\b.*")) return null;
        // Keep shell semantics for compound commands. Intercept only one direct activity launch.
        if (containsShellControl(trimmed)) return null;
        try {
            List<String> a = shellWords(trimmed);
            if (a.size() < 2) return null;
            JSONObject in = new JSONObject();
            in.put("operation", "intent");
            JSONArray cats = new JSONArray();
            JSONObject extras = new JSONObject();
            int i = 2;
            while (i < a.size()) {
                String x = a.get(i++);
                if ("-W".equals(x) || "--wait".equals(x)) continue;
                if ("--user".equals(x)) { if (i < a.size()) i++; continue; }
                if (("-a".equals(x) || "--action".equals(x)) && i < a.size()) in.put("action", a.get(i++));
                else if (("-d".equals(x) || "--data".equals(x)) && i < a.size()) in.put("uri", a.get(i++));
                else if (("-t".equals(x) || "--type".equals(x)) && i < a.size()) in.put("mime_type", a.get(i++));
                else if (("-p".equals(x) || "--package".equals(x)) && i < a.size()) in.put("package", a.get(i++));
                else if (("-n".equals(x) || "--component".equals(x)) && i < a.size()) in.put("component", a.get(i++));
                else if (("-c".equals(x) || "--category".equals(x)) && i < a.size()) cats.put(a.get(i++));
                else if (("-f".equals(x) || "--flags".equals(x)) && i < a.size()) in.put("flags", parseInt(a.get(i++)));
                else if ("--es".equals(x) && i + 1 < a.size()) extras.put(a.get(i++), a.get(i++));
                else if (("--ei".equals(x) || "--el".equals(x)) && i + 1 < a.size()) extras.put(a.get(i++), Long.parseLong(a.get(i++)));
                else if ("--ez".equals(x) && i + 1 < a.size()) extras.put(a.get(i++), Boolean.parseBoolean(a.get(i++)));
                else if (!x.startsWith("-")) {
                    // Android am also accepts a bare URI at the end.
                    if (!in.has("uri") && (x.contains(":") || x.startsWith("/"))) in.put("uri", x);
                }
            }
            if (cats.length() > 0) in.put("categories", cats);
            if (extras.length() > 0) in.put("extras", extras);
            ToolExecutionResult result = execute(in);
            if (result.isError) return result;
            return ToolExecutionResult.ok("[IQGE Android Intent bridge]\n已将 Termux `am start` 转交给 com.iqge 应用进程执行。\n" + result.content);
        } catch (Throwable e) {
            return ToolExecutionResult.error("[IQGE Android Intent bridge]\n无法解析 am start：" + readable(e));
        }
    }

    private String findLaunchablePackageByLabel(String wanted) {
        String needle = wanted == null ? "" : wanted.trim().toLowerCase(Locale.US);
        if (needle.isEmpty()) return "";
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = context.getPackageManager().queryIntentActivities(probe, 0);
        if (apps == null) apps = Collections.emptyList();
        String fuzzy = "";
        for (ResolveInfo info : apps) {
            if (info == null || info.activityInfo == null) continue;
            CharSequence labelCs = info.loadLabel(context.getPackageManager());
            String label = labelCs == null ? "" : labelCs.toString().trim();
            String pkg = info.activityInfo.packageName == null ? "" : info.activityInfo.packageName.trim();
            if (pkg.isEmpty()) continue;
            if (label.toLowerCase(Locale.US).equals(needle) || pkg.toLowerCase(Locale.US).equals(needle)) return pkg;
            if (fuzzy.isEmpty() && (label.toLowerCase(Locale.US).contains(needle) || needle.contains(label.toLowerCase(Locale.US)))) fuzzy = pkg;
        }
        return fuzzy;
    }

    private ToolExecutionResult start(final Intent intent) throws Exception {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        Runnable task = () -> {
            try { context.startActivity(intent); }
            catch (Throwable e) { failure.set(e); }
            finally { done.countDown(); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) task.run(); else main.post(task);
        if (!done.await(8, TimeUnit.SECONDS)) return ToolExecutionResult.error("Android Intent 启动超时");
        Throwable error = failure.get();
        if (error != null) {
            if (error instanceof ActivityNotFoundException) return ToolExecutionResult.error("没有应用可以处理这个 Android Intent：" + intent.toUri(0));
            return ToolExecutionResult.error("Android Intent 失败：" + readable(error));
        }
        return ToolExecutionResult.ok("已通过 IQ Code 应用进程启动：" + intent.toUri(0));
    }

    private static void putExtras(Intent intent, JSONObject extras) {
        JSONArray names = extras.names(); if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i, ""); Object v = extras.opt(key); if (key.isEmpty() || v == null || v == JSONObject.NULL) continue;
            if (v instanceof Boolean) intent.putExtra(key, (Boolean) v);
            else if (v instanceof Integer) intent.putExtra(key, (Integer) v);
            else if (v instanceof Long) intent.putExtra(key, (Long) v);
            else if (v instanceof Double) intent.putExtra(key, (Double) v);
            else intent.putExtra(key, String.valueOf(v));
        }
    }

    private static String normalizeComponent(String raw) {
        String x = raw.trim();
        int slash = x.indexOf('/');
        if (slash > 0 && slash + 1 < x.length() && x.charAt(slash + 1) == '.') return x.substring(0, slash + 1) + x.substring(0, slash) + x.substring(slash + 1);
        return x;
    }

    private static boolean containsShellControl(String s) {
        boolean single = false, dbl = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (!dbl && c == '\'') { single = !single; continue; }
            if (!single && c == '"') { dbl = !dbl; continue; }
            if (!single && !dbl && (c == ';' || c == '|' || c == '&' || c == '`' || c == '\n' || c == '\r')) return true;
        }
        return false;
    }

    private static List<String> shellWords(String s) {
        ArrayList<String> out = new ArrayList<>(); StringBuilder b = new StringBuilder();
        boolean single = false, dbl = false, esc = false, token = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) { b.append(c); esc = false; token = true; continue; }
            if (c == '\\' && !single) { esc = true; token = true; continue; }
            if (c == '\'' && !dbl) { single = !single; token = true; continue; }
            if (c == '"' && !single) { dbl = !dbl; token = true; continue; }
            if (Character.isWhitespace(c) && !single && !dbl) { if (token) { out.add(b.toString()); b.setLength(0); token = false; } continue; }
            b.append(c); token = true;
        }
        if (esc || single || dbl) throw new IllegalArgumentException("引号或转义未闭合");
        if (token) out.add(b.toString());
        return out;
    }

    private static int parseInt(String raw) {
        String x = raw.trim().toLowerCase(Locale.US);
        if (x.startsWith("0x")) return (int) Long.parseLong(x.substring(2), 16);
        return Integer.parseInt(x);
    }

    private static String readable(Throwable e) {
        String m = e.getMessage(); return e.getClass().getSimpleName() + (m == null || m.trim().isEmpty() ? "" : ": " + m);
    }
}

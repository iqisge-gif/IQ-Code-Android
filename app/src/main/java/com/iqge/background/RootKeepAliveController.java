package com.iqge.background;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.app.iqcode.termux.TermuxShellExecutor;

/** Applies only the fixed, user-enabled Root policy used by the foreground keep-alive service. */
public final class RootKeepAliveController {
    private static final String PREFS = "iqge_keep_alive";
    private static final String ADDED_DEVICEIDLE = "added_deviceidle";
    private static final String PREVIOUS_RUN_IN_BACKGROUND = "previous_run_in_background";
    private static final String PREVIOUS_RUN_ANY_IN_BACKGROUND = "previous_run_any_in_background";
    private static final String PACKAGE = "com.iqge";

    private RootKeepAliveController() {}

    public static String apply(Context context) {
        try {
            TermuxShellExecutor shell = new TermuxShellExecutor(context);
            TermuxShellExecutor.Result check = shell.executeAsRoot("id -u", context.getFilesDir().getAbsolutePath(), 15_000);
            if (check.exitCode != 0 || check.timedOut || !"0".equals(check.stdout.trim())) return "Root 未授予 uid 0";
            TermuxShellExecutor.Result before = shell.executeAsRoot("cmd deviceidle whitelist; cmd appops get " + PACKAGE + " RUN_IN_BACKGROUND 2>/dev/null; cmd appops get " + PACKAGE + " RUN_ANY_IN_BACKGROUND 2>/dev/null", context.getFilesDir().getAbsolutePath(), 15_000);
            boolean alreadyListed = containsPackage(before.combined());
            String previousRun=parseAppOp(before.combined(),"RUN_IN_BACKGROUND");
            String previousAny=parseAppOp(before.combined(),"RUN_ANY_IN_BACKGROUND");
            String command = "cmd deviceidle whitelist +" + PACKAGE + "; "
                + "cmd appops set " + PACKAGE + " RUN_IN_BACKGROUND allow 2>/dev/null || true; "
                + "cmd appops set " + PACKAGE + " RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true";
            TermuxShellExecutor.Result result = shell.executeAsRoot(command, context.getFilesDir().getAbsolutePath(), 20_000);
            if (result.exitCode != 0 || result.timedOut) return "Root 保活策略执行失败：" + result.combined();
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(ADDED_DEVICEIDLE, !alreadyListed).putString(PREVIOUS_RUN_IN_BACKGROUND,previousRun).putString(PREVIOUS_RUN_ANY_IN_BACKGROUND,previousAny).apply();
            return alreadyListed ? "Root 保活已加强（系统白名单原本已存在）" : "Root 保活已加强";
        } catch (Exception e) {
            return "Root 保活不可用：" + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    public static void revoke(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean removeWhitelist=prefs.getBoolean(ADDED_DEVICEIDLE,false);
        String previousRun=safeMode(prefs.getString(PREVIOUS_RUN_IN_BACKGROUND,""));
        String previousAny=safeMode(prefs.getString(PREVIOUS_RUN_ANY_IN_BACKGROUND,""));
        if(!removeWhitelist&&previousRun.isEmpty()&&previousAny.isEmpty())return;
        StringBuilder command=new StringBuilder();
        if(removeWhitelist)command.append("cmd deviceidle whitelist -").append(PACKAGE).append("; ");
        if(!previousRun.isEmpty())command.append("cmd appops set ").append(PACKAGE).append(" RUN_IN_BACKGROUND ").append(previousRun).append("; ");
        if(!previousAny.isEmpty())command.append("cmd appops set ").append(PACKAGE).append(" RUN_ANY_IN_BACKGROUND ").append(previousAny).append("; ");
        command.append("true");
        try {
            TermuxShellExecutor.Result result=new TermuxShellExecutor(context).executeAsRoot(command.toString(),context.getFilesDir().getAbsolutePath(),20_000);
            if(result.exitCode==0&&!result.timedOut)prefs.edit().remove(ADDED_DEVICEIDLE).remove(PREVIOUS_RUN_IN_BACKGROUND).remove(PREVIOUS_RUN_ANY_IN_BACKGROUND).apply();
        } catch (Exception ignored) { }
    }

    private static String parseAppOp(String output,String operation){
        if(output==null)return "";for(String line:output.split("\\r?\\n")){String trimmed=line.trim();if(!trimmed.contains(operation))continue;int colon=trimmed.lastIndexOf(':');if(colon>=0)return safeMode(trimmed.substring(colon+1).trim().split("\\s+")[0]);}return "";
    }

    private static String safeMode(String mode){String value=mode==null?"":mode.trim();return "default".equals(value)||"allow".equals(value)||"ignore".equals(value)||"deny".equals(value)?value:"";}

    private static boolean containsPackage(String value) {
        if (value == null) return false;
        for (String line : value.split("\\r?\\n")) if (line.trim().equals(PACKAGE)) return true;
        return false;
    }
}

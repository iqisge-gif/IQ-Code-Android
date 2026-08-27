package com.iqge.sandbox;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/** Keeps BlackBox completely out of IQ Code's main process. */
public final class SandboxProcessRole {
    private SandboxProcessRole() {}

    public static String processName(Context context) {
        try {
            File f = new File("/proc/self/cmdline");
            try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                int b;
                while ((b = in.read()) != -1 && b != 0) out.write(b);
                String s = out.toString("UTF-8").trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {}
        return context == null ? "" : context.getPackageName();
    }

    public static boolean isMainProcess(Context context) {
        if (context == null) return false;
        return context.getPackageName().equals(processName(context));
    }

    /** Only the dedicated sandbox controller, server and proxy Guest processes own BlackBox. */
    public static boolean shouldAttachBlackBox(Context context) {
        if (context == null) return false;
        String pkg = context.getPackageName();
        String proc = processName(context);
        if (proc.equals(pkg + ":iqsandbox") || proc.equals(pkg + ":black")) return true;
        String prefix = pkg + ":p";
        if (!proc.startsWith(prefix)) return false;
        try {
            int index = Integer.parseInt(proc.substring(prefix.length()));
            return index >= 0 && index < 50;
        } catch (Throwable ignored) {
            return false;
        }
    }
}

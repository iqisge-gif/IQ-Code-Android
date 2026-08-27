package com.iqge.sandbox;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;

/** Synchronous IPC from IQ Code/Agent into the isolated :iqsandbox host process. */
public final class SandboxHostClient {
    public static final String AUTHORITY = "com.iqge.sandbox.control";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY);
    private SandboxHostClient() {}

    public static JSONObject call(Context context, String action, JSONObject payload) {
        try {
            Bundle in = new Bundle();
            in.putString("payload", payload == null ? "{}" : payload.toString());
            Bundle out = context.getContentResolver().call(URI, "control", action, in);
            if (out == null) return error("IQSandbox 进程没有返回结果");
            String raw = out.getString("result", "");
            if (raw.isEmpty()) return error("IQSandbox 返回空结果");
            return new JSONObject(raw);
        } catch (Throwable e) {
            return error("IQSandbox 独立进程不可用: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
    }

    private static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try { o.put("ok", false).put("error", message); } catch (Throwable ignored) {}
        return o;
    }
}

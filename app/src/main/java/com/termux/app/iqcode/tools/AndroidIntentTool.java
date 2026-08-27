package com.termux.app.iqcode.tools;

import android.content.Context;
import android.content.Intent;

import com.termux.app.iqcode.model.SessionConfig;
import com.termux.app.iqcode.model.ToolExecutionResult;

import org.json.JSONObject;

/** Android-native external-app/URL launcher. */
public final class AndroidIntentTool implements IQTool {
    private final AndroidIntentBridge bridge;
    public AndroidIntentTool(Context context) { this(new AndroidIntentBridge(context)); }
    AndroidIntentTool(AndroidIntentBridge bridge) { this.bridge = bridge; }

    @Override public String name() { return "AndroidIntent"; }

    @Override public String description() {
        return "Open Android intents/apps/URLs or install an APK; use instead of shell am start.";
    }

    @Override public JSONObject inputSchema() {
        try {
            JSONObject p = new JSONObject();
            p.put("operation", ToolSchemas.string("intent (default), launch_app, or install_apk"));
            p.put("action", ToolSchemas.string("Android intent action, default android.intent.action.VIEW"));
            p.put("uri", ToolSchemas.string("Optional data URI such as https://www.google.com, tel:, geo:, package:, or content:"));
            p.put("path", ToolSchemas.string("Absolute local path for install_apk, normally under /storage/emulated/0 or IQ Code HOME."));
            p.put("package", ToolSchemas.string("Optional target package for launch_app or explicit intents"));
            p.put("app_name", ToolSchemas.string("Optional visible app name for launch_app when package is unknown"));
            p.put("component", ToolSchemas.string("Optional flattened component package/.Activity"));
            p.put("mime_type", ToolSchemas.string("Optional MIME type"));
            p.put("categories", ToolSchemas.stringArray("Optional Android intent categories"));
            p.put("extras", ToolSchemas.freeObject("Optional primitive Android intent extras"));
            p.put("flags", ToolSchemas.integer("Optional Android Intent flags bitmask", 0));
            return ToolSchemas.object(p);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Override public PermissionKind permissionKind() { return PermissionKind.SYSTEM; }
    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input) { return bridge.execute(input); }
}

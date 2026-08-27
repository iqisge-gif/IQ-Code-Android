package com.termux.app.iqcode.core;

import com.termux.app.iqcode.model.ToolCall;
import com.termux.app.iqcode.tools.IQTool;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** UI-mediated permission gate modelled after IQ Code permission modes. */
public final class PermissionGate {
    public static final class PermissionRequest {
        public final String requestId;
        public final ToolCall call;
        public final IQTool.PermissionKind kind;
        public final String mode;
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Boolean allowed;

        PermissionRequest(ToolCall call, IQTool.PermissionKind kind, String mode) {
            this.requestId = UUID.randomUUID().toString();
            this.call = call;
            this.kind = kind;
            this.mode = mode;
        }
    }

    public interface Listener {
        void onPermissionRequested(PermissionRequest request);
    }

    private final Map<String, PermissionRequest> pending = new ConcurrentHashMap<>();

    public boolean require(String effectiveMode, IQTool tool, ToolCall call, Listener listener) throws InterruptedException {
        String mode = PermissionModePolicy.normalize(effectiveMode);
        IQTool.PermissionKind kind = tool.permissionKind();

        if (kind == IQTool.PermissionKind.INTERNAL || kind == IQTool.PermissionKind.READ || kind == IQTool.PermissionKind.NETWORK) return true;
        if ("bypassPermissions".equals(mode)) return true;
        if ("plan".equals(mode)) return false;
        if ("dontAsk".equals(mode)) return false;
        if ("acceptEdits".equals(mode) && kind == IQTool.PermissionKind.WRITE) return true;
        if ("auto".equals(mode) && kind == IQTool.PermissionKind.WRITE) return true;

        PermissionRequest request = new PermissionRequest(call, kind, mode);
        pending.put(request.requestId, request);
        if (listener != null) listener.onPermissionRequested(request);
        boolean answered = request.latch.await(30, TimeUnit.MINUTES);
        pending.remove(request.requestId);
        return answered && Boolean.TRUE.equals(request.allowed);
    }

    public boolean respond(String requestId, boolean allow) {
        PermissionRequest request = pending.get(requestId);
        if (request == null) return false;
        request.allowed = allow;
        request.latch.countDown();
        return true;
    }

    public void cancelAll() {
        for (PermissionRequest request : pending.values()) {
            request.allowed = false;
            request.latch.countDown();
        }
        pending.clear();
    }
}

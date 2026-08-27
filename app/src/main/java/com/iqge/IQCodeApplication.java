package com.iqge;

import android.app.Application;
import android.content.Context;

import com.iqge.sandbox.IQSandboxEngine;
import com.iqge.sandbox.SandboxAgentBridge;
import com.iqge.sandbox.SandboxDebugLog;
import com.iqge.sandbox.SandboxProcessRole;
import com.iqge.sandbox.SandboxTermuxBridge;

/**
 * IQ Code main process stays clean. BlackBox is attached only inside sandbox-owned
 * processes (:iqsandbox, :black and :pN) so a sandbox/native hook failure cannot
 * kill the editor/Agent process during app startup.
 */
public final class IQCodeApplication extends Application {
    private boolean sandboxProcess;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        SandboxDebugLog.init(base);
        installCrashLogger(); // before ContentProvider creation
        sandboxProcess = SandboxProcessRole.shouldAttachBlackBox(base);
        SandboxDebugLog.event("Application attach: " + SandboxProcessRole.processName(base) + " sandbox=" + sandboxProcess);
        if (sandboxProcess) IQSandboxEngine.attach(base);
    }

    @Override public void onCreate() {
        super.onCreate();
        if (sandboxProcess) {
            SandboxAgentBridge.register(this);
            IQSandboxEngine.create();
        } else if (SandboxProcessRole.isMainProcess(this)) {
            SandboxTermuxBridge.start(this);
        }
    }

    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            SandboxDebugLog.event("未捕获异常 [" + SandboxProcessRole.processName(this) + "/" + thread.getName() + "]: " + SandboxDebugLog.stackTrace(error));
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }
}

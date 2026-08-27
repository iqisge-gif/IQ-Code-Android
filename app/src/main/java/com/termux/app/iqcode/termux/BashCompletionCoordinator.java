package com.termux.app.iqcode.termux;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Coordinates a trusted wrapper completion record with the real Process exit. */
public final class BashCompletionCoordinator {
    public enum Source { NONE, CONTROL, PROCESS }

    private final CountDownLatch resolved = new CountDownLatch(1);
    private final CountDownLatch processExit = new CountDownLatch(1);
    private boolean hasExitCode;
    private boolean processExited;
    private int exitCode;
    private Source source = Source.NONE;

    public static Integer parseControlLine(String line, String token) {
        if (line == null || token == null || token.isEmpty()) return null;
        int separator = line.indexOf(':');
        if (separator != token.length() || separator <= 0) return null;
        if (!token.equals(line.substring(0, separator))) return null;
        String rawCode = line.substring(separator + 1);
        if (rawCode.isEmpty()) return null;
        try {
            int code = Integer.parseInt(rawCode);
            return code >= 0 && code <= 255 ? Integer.valueOf(code) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public synchronized boolean publishControl(int code) {
        if (code < 0 || code > 255 || hasExitCode) return false;
        hasExitCode = true;
        exitCode = code;
        source = Source.CONTROL;
        resolved.countDown();
        return true;
    }

    public synchronized boolean publishProcessExit(int code) {
        processExited = true;
        processExit.countDown();
        if (hasExitCode) return false;
        hasExitCode = true;
        exitCode = code;
        source = Source.PROCESS;
        resolved.countDown();
        return true;
    }

    public boolean await(long timeoutMs) throws InterruptedException {
        return resolved.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
    }

    public boolean awaitProcessExit(long timeoutMs) throws InterruptedException {
        return processExit.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
    }

    public synchronized boolean hasExitCode() { return hasExitCode; }
    public synchronized int exitCode() { return exitCode; }
    public synchronized Source source() { return source; }
    public synchronized boolean processExited() { return processExited; }
}

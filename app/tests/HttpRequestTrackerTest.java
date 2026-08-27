package com.termux.app.iqcode.api;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class HttpRequestTrackerTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class FakeConnection extends HttpURLConnection {
        int disconnectCount;

        FakeConnection() throws Exception {
            super(new URL("http://localhost"));
        }

        @Override public void disconnect() { disconnectCount++; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }

    public static void main(String[] args) throws Exception {
        configuresAndCleansUp();
        cancelsRegisteredRequest();
        cancelsFromAnotherThread();
        preservesNewerRequestOnOldCleanup();
        classifiesFailuresByStage();
        rejectsAlreadyInterruptedWorker();
        System.out.println("HttpRequestTrackerTest PASS");
    }

    private static void configuresAndCleansUp() throws Exception {
        HttpRequestTracker tracker = new HttpRequestTracker();
        FakeConnection connection = new FakeConnection();
        HttpRequestTracker.Scope scope = tracker.begin(connection);
        require(connection.getConnectTimeout() == HttpRequestTracker.CONNECT_TIMEOUT_MS,
                "connect timeout must be configured centrally");
        require(connection.getReadTimeout() == HttpRequestTracker.READ_IDLE_TIMEOUT_MS,
                "read timeout must bound idle response waits");
        require(tracker.activeCount() == 1, "request must be registered before network I/O");
        scope.close();
        require(tracker.activeCount() == 0 && connection.disconnectCount == 1,
                "normal cleanup must unregister and disconnect exactly once");
    }

    private static void cancelsRegisteredRequest() throws Exception {
        HttpRequestTracker tracker = new HttpRequestTracker();
        FakeConnection connection = new FakeConnection();
        HttpRequestTracker.Scope scope = tracker.begin(connection);
        tracker.cancel(Thread.currentThread());
        require(tracker.activeCount() == 0 && connection.disconnectCount == 1,
                "cancel must disconnect the active worker request");
        scope.close();
        require(connection.disconnectCount == 1, "cleanup after cancel must be idempotent");
    }

    private static void cancelsFromAnotherThread() throws Exception {
        HttpRequestTracker tracker = new HttpRequestTracker();
        FakeConnection connection = new FakeConnection();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            HttpRequestTracker.Scope scope;
            try {
                scope = tracker.begin(connection);
            } catch (Throwable error) {
                failure.set(error);
                registered.countDown();
                return;
            }
            registered.countDown();
            try {
                finish.await();
                scope.close();
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "tracker-test-worker");
        worker.start();
        registered.await();
        tracker.cancel(worker);
        finish.countDown();
        worker.join();
        require(failure.get() == null, "cross-thread cancellation must not corrupt worker cleanup");
        require(connection.disconnectCount == 1 && tracker.activeCount() == 0,
                "controller thread must disconnect the registered worker request exactly once");
    }

    private static void preservesNewerRequestOnOldCleanup() throws Exception {
        HttpRequestTracker tracker = new HttpRequestTracker();
        FakeConnection first = new FakeConnection();
        FakeConnection second = new FakeConnection();
        HttpRequestTracker.Scope firstScope = tracker.begin(first);
        HttpRequestTracker.Scope secondScope = tracker.begin(second);
        require(first.disconnectCount == 1, "a replacement request must close the stale connection");
        firstScope.close();
        require(tracker.activeCount() == 1, "stale cleanup must not remove the newer request");
        secondScope.close();
        require(tracker.activeCount() == 0 && second.disconnectCount == 1,
                "newer request must retain exact cleanup ownership");
    }

    private static void classifiesFailuresByStage() throws Exception {
        HttpRequestTracker tracker = new HttpRequestTracker();
        HttpRequestTracker.Scope scope = tracker.begin(new FakeConnection());
        require("request_timeout".equals(scope.failureCode(new SocketTimeoutException("slow headers"))),
                "pre-response timeout must be retryable as request_timeout");
        require("connection_reset".equals(scope.failureCode(new SocketException("connection reset"))),
                "a clear pre-response socket reset must be retryable");
        require(scope.failureCode(new IOException("certificate rejected")).isEmpty(),
                "unclassified pre-response I/O failures must not be retried blindly");
        scope.markResponseStarted();
        require("stream_read_error".equals(scope.failureCode(new SocketTimeoutException("silent stream"))),
                "post-response timeout must be classified as a stalled stream");
        scope.close();
    }

    private static void rejectsAlreadyInterruptedWorker() throws Exception {
        HttpRequestTracker tracker = new HttpRequestTracker();
        FakeConnection connection = new FakeConnection();
        Thread.currentThread().interrupt();
        try {
            tracker.begin(connection);
            throw new AssertionError("interrupted worker must not start network I/O");
        } catch (InterruptedException expected) {
            require(connection.disconnectCount == 1 && tracker.activeCount() == 0,
                    "interrupted registration must disconnect and unregister");
        } finally {
            Thread.interrupted();
        }
    }
}

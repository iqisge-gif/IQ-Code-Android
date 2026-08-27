package com.termux.app.iqcode.api;

import java.io.EOFException;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tracks one blocking HTTP request per provider worker and bounds silent network waits. */
final class HttpRequestTracker {
    static final int CONNECT_TIMEOUT_MS = 30_000;
    static final int READ_IDLE_TIMEOUT_MS = 300_000;

    private final ConcurrentHashMap<Thread, Scope> active = new ConcurrentHashMap<>();

    Scope begin(HttpURLConnection connection) throws InterruptedException {
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_IDLE_TIMEOUT_MS);

        Thread worker = Thread.currentThread();
        Scope scope = new Scope(worker, connection);
        Scope previous = active.put(worker, scope);
        if (previous != null) previous.cancel();
        if (worker.isInterrupted() || scope.isClosed()) {
            active.remove(worker, scope);
            scope.cancel();
            throw new InterruptedException("IQ request interrupted");
        }
        return scope;
    }

    void cancel(Thread worker) {
        if (worker == null) return;
        Scope scope = active.remove(worker);
        if (scope != null) scope.cancel();
    }

    int activeCount() {
        return active.size();
    }

    final class Scope implements AutoCloseable {
        private final Thread worker;
        private final HttpURLConnection connection;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean responseStarted;

        private Scope(Thread worker, HttpURLConnection connection) {
            this.worker = worker;
            this.connection = connection;
        }

        void markResponseStarted() {
            responseStarted = true;
        }

        String failureCode(IOException error) {
            if (responseStarted) return "stream_read_error";
            if (error instanceof SocketTimeoutException) return "request_timeout";
            if (error instanceof EOFException) return "unexpected_eof";
            String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.US);
            if (error instanceof SocketException && (message.contains("reset") || message.contains("closed") || message.contains("broken pipe"))) {
                return "connection_reset";
            }
            return "";
        }

        String failureMessage(IOException error) {
            String code = failureCode(error);
            String message = error.getMessage();
            return (code.isEmpty() ? "network_error" : code) + ": "
                    + (message == null || message.isEmpty() ? "network I/O failed" : message);
        }

        boolean isClosed() {
            return closed.get();
        }

        private void cancel() {
            if (closed.compareAndSet(false, true)) connection.disconnect();
        }

        @Override public void close() {
            active.remove(worker, this);
            cancel();
        }
    }
}

package com.termux.zerocore.ccs;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Process-wide bounds also apply when an Activity is recreated with a stuck provider. */
final class CcsSqlImportTask {
    static final long TIMEOUT_MS = 30_000L;
    private static final ThreadFactory THREADS = runnable -> {
        Thread thread = new Thread(runnable, "ccs-sql-import");
        thread.setDaemon(true);
        return thread;
    };
    private static final ThreadPoolExecutor COPIES = new ThreadPoolExecutor(
        0, 2, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), THREADS);
    // Provider cancel/close itself may block. Never run it on the WebView/UI thread.
    private static final ThreadPoolExecutor CLOSERS = new ThreadPoolExecutor(
        2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), THREADS);
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, THREADS);
    static {
        TIMER.setRemoveOnCancelPolicy(true);
        CLOSERS.allowCoreThreadTimeOut(true);
    }

    private final List<AutoCloseable> handles = new ArrayList<>();
    private Runnable providerCancellation;
    private Future<?> future;
    private ScheduledFuture<?> deadline;
    private boolean cancelled;
    private boolean finished;

    synchronized boolean submit(Runnable work, long timeoutMs, Runnable onTimeout) {
        if (cancelled || finished || future != null) return false;
        FutureTask<Void> submitted = new FutureTask<Void>(work, null) {
            @Override public void run() {
                try { super.run(); } finally { finished(); }
            }
        };
        future = submitted;
        try {
            COPIES.execute(submitted);
            deadline = TIMER.schedule(() -> {
                if (cancel()) onTimeout.run();
            }, timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException e) {
            submitted.cancel(false);
            future = null;
            return false;
        }
    }

    synchronized boolean isCancelled() { return cancelled; }

    synchronized void onProviderCancel(Runnable action) {
        providerCancellation = action;
        if (cancelled) closeLater(action);
    }

    synchronized <T extends AutoCloseable> T track(T handle) throws InterruptedIOException {
        if (handle == null) return null;
        if (cancelled) {
            closeLater(() -> close(handle));
            throw new InterruptedIOException("SQL copy cancelled");
        }
        handles.add(handle);
        return handle;
    }

    synchronized void untrack(AutoCloseable handle) { handles.remove(handle); }

    synchronized void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("SQL copy cancelled");
        }
    }

    boolean cancel() {
        List<AutoCloseable> closing;
        Runnable provider;
        Future<?> running;
        synchronized (this) {
            if (cancelled || finished) return false;
            cancelled = true;
            closing = new ArrayList<>(handles);
            handles.clear();
            provider = providerCancellation;
            running = future;
            if (deadline != null) deadline.cancel(false);
        }
        if (running != null) running.cancel(true);
        // Close streams/descriptors independently of a potentially stalled Binder cancel.
        for (AutoCloseable handle : closing) closeLater(() -> close(handle));
        if (provider != null) closeLater(provider);
        return true;
    }

    synchronized void finished() {
        finished = true;
        if (deadline != null) deadline.cancel(false);
        providerCancellation = null;
        handles.clear();
    }

    static void reapPeriodically(Runnable action) {
        TIMER.scheduleWithFixedDelay(action, 30, 30, TimeUnit.SECONDS);
    }

    private static void closeLater(Runnable action) {
        try {
            CLOSERS.execute(() -> {
                try { action.run(); } catch (RuntimeException ignored) { /* copy reports its failure */ }
            });
        } catch (RejectedExecutionException ignored) {
            // Bounded fail-closed fallback: the worker still owns the handle in its finally.
            // Its reservation remains charged until that worker actually exits.
        }
    }

    private static void close(AutoCloseable handle) {
        try { handle.close(); } catch (Exception ignored) { /* also closed by the worker */ }
    }
}

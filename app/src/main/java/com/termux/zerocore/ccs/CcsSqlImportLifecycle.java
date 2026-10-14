package com.termux.zerocore.ccs;

/** Selector callbacks have no nonce; retain the old slot until the actual result arrives. */
final class CcsSqlImportLifecycle {
    static final class Request {
        final String id;
        final Object page;
        final CcsSqlImportTask task = new CcsSqlImportTask();
        Request(String id, Object page) { this.id = id; this.page = page; }
    }

    private final CcsSqlImportCache cache;
    private Object page = new Object();
    private Request selector;
    private Request copying;
    private boolean destroyed;

    CcsSqlImportLifecycle(CcsSqlImportCache cache) { this.cache = cache; }

    synchronized Request request(String id) { return new Request(id, page); }

    synchronized Request select(String id) { return select(request(id)); }

    synchronized Request select(Request request) {
        if (!current(request) || selector != null || copying != null) return null;
        selector = request;
        return selector;
    }

    synchronized boolean hasSelector() { return selector != null; }

    synchronized void restoreSelector() { selector = new Request("", new Object()); }

    synchronized boolean begin(String path) { return !destroyed && cache.begin(path, page); }

    synchronized CcsSqlImportCache.Reservation reserve(Request request) throws java.io.IOException {
        if (!current(request) || copying != request || request.task.isCancelled()) {
            throw new java.io.InterruptedIOException("SQL request invalidated");
        }
        return cache.reserve(page);
    }

    synchronized Request selected() {
        Request result = selector;
        selector = null;
        if (!current(result)) return null;
        copying = result;
        return result;
    }

    synchronized void selectionFailed(Request request) {
        if (selector == request) selector = null;
    }

    synchronized boolean current(Request request) {
        return request != null && !destroyed && request.page == page;
    }

    synchronized boolean finishCopy(Request request) {
        // A cancelled worker cannot consume the timeout's explicit error callback.
        return !request.task.isCancelled() && current(request) && finish(request);
    }

    synchronized boolean finish(Request request) {
        if (copying != request) return false;
        copying = null;
        return current(request);
    }

    synchronized void reload() {
        Object old = page;
        page = new Object();
        if (copying != null) {
            copying.task.cancel();
            copying = null;
        }
        cache.releaseOwner(old);
        // Keep an outstanding selector as a tombstone. Do not bind its URI to a new page.
    }

    synchronized void destroy() {
        reload();
        destroyed = true;
    }
}

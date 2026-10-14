package com.termux.zerocore.ccs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InterruptedIOException;
import static org.junit.Assert.*;

public class CcsSqlImportLifecycleTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private CcsSqlImportCache cache() throws Exception {
        return new CcsSqlImportCache(temporary.newFolder("cache"), 8, 16, 1000, () -> 0);
    }

    @Test public void oldSelectorCannotBeReplacedOrBindItsUriToTheNewPage() throws Exception {
        CcsSqlImportLifecycle life = new CcsSqlImportLifecycle(cache());
        CcsSqlImportLifecycle.Request old = life.select("old-page-nonce-1");
        assertNotNull(old);
        life.reload();
        assertFalse(life.current(old));
        assertNull(life.select("new-page-nonce-1"));
        assertNull(life.selected());
        CcsSqlImportLifecycle.Request next = life.select("new-page-nonce-2");
        assertEquals("new-page-nonce-2", next.id);
        assertSame(next, life.selected());
    }

    @Test public void cancelledOldCopyDoesNotHoldTheNewPagesSelectionSlot() throws Exception {
        CcsSqlImportLifecycle life = new CcsSqlImportLifecycle(cache());
        CcsSqlImportLifecycle.Request old = life.select("old-page-nonce-1");
        assertSame(old, life.selected());
        life.reload();
        assertTrue(old.task.isCancelled());
        assertFalse(life.finish(old));
        try { life.reserve(old); fail("Old generation must not reserve cache"); }
        catch (InterruptedIOException expected) { /* stale */ }
        CcsSqlImportLifecycle.Request next = life.select("new-page-nonce-1");
        assertSame(next, life.selected());
        assertFalse(life.finish(old));
        life.reserve(next).abort();
        assertTrue(life.finish(next));
    }

    @Test public void binderRequestCapturedBeforeReloadCannotAcquireNewPageSlot() throws Exception {
        CcsSqlImportLifecycle life = new CcsSqlImportLifecycle(cache());
        CcsSqlImportLifecycle.Request queued = life.request("old-nonce-1");
        life.reload();
        assertNull(life.select(queued));
        assertNotNull(life.select("new-nonce-1"));
    }

    @Test public void restoredActivityRetainsSelectorTombstoneUntilActualResult() throws Exception {
        CcsSqlImportLifecycle life = new CcsSqlImportLifecycle(cache());
        life.restoreSelector();
        life.reload();
        assertTrue(life.hasSelector());
        assertNull(life.select("new-nonce-1"));
        assertNull(life.selected());
        assertFalse(life.hasSelector());
        assertNotNull(life.select("new-nonce-2"));
    }

    @Test public void cancellingAReplacementKeepsThePreviousCopyUsable() throws Exception {
        CcsSqlImportCache cache = cache();
        CcsSqlImportLifecycle life = new CcsSqlImportLifecycle(cache);
        CcsSqlImportLifecycle.Request first = life.select("page-nonce-1");
        life.selected();
        File file = life.reserve(first).copy(new ByteArrayInputStream(new byte[]{1}),
            "backup.sql", first.task);
        life.finish(first);
        CcsSqlImportLifecycle.Request replacement = life.select("page-nonce-2");
        assertSame(replacement, life.selected());
        assertTrue(life.finish(replacement)); // User cancelled the system picker.
        assertTrue(file.exists());
        assertTrue(life.begin(file.getAbsolutePath()));
        cache.release(file.getAbsolutePath()); // A real clear/replacement releases it.
        assertTrue(file.exists());
        cache.end(file.getAbsolutePath());
        assertFalse(file.exists());
    }

    @Test public void pageDestroyPreservesUnknownRpcPinsAndRejectsNewBegin() throws Exception {
        CcsSqlImportCache cache = cache();
        CcsSqlImportLifecycle life = new CcsSqlImportLifecycle(cache);
        CcsSqlImportLifecycle.Request first = life.select("page-nonce-1");
        life.selected();
        File file = life.reserve(first).copy(new ByteArrayInputStream(new byte[]{1}),
            "backup.sql", first.task);
        life.finish(first);
        assertTrue(life.begin(file.getAbsolutePath()));
        life.destroy();
        cache.expire();
        assertTrue(file.exists());
        assertFalse(life.begin(file.getAbsolutePath()));
        assertNull(life.select("new-nonce-1"));
        cache.end(file.getAbsolutePath());
        assertFalse(file.exists());
    }

    @Test public void cancelledWorkerCannotConsumeTimeoutErrorCallback() throws Exception {
        CcsSqlImportLifecycle life = new CcsSqlImportLifecycle(cache());
        CcsSqlImportLifecycle.Request request = life.select("page-nonce-1");
        life.selected();
        request.task.cancel();
        assertFalse(life.finishCopy(request));
        assertNull(life.select("page-nonce-2"));
        assertTrue(life.finish(request)); // timeout still owns and resolves this request
        assertNotNull(life.select("page-nonce-2"));
    }

    @Test public void selectionFailureOnlyClearsItsOwnRequest() throws Exception {
        CcsSqlImportLifecycle life = new CcsSqlImportLifecycle(cache());
        CcsSqlImportLifecycle.Request old = life.select("old-nonce-1");
        life.selectionFailed(old);
        CcsSqlImportLifecycle.Request next = life.select("new-nonce-1");
        life.selectionFailed(old);
        assertSame(next, life.selected());
    }
}

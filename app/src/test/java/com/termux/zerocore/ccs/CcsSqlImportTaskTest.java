package com.termux.zerocore.ccs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class CcsSqlImportTaskTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static void await(CountDownLatch latch) throws Exception {
        assertTrue("Worker did not reach its expected state", latch.await(3, TimeUnit.SECONDS));
    }

    private static void submit(CcsSqlImportTask task, Runnable work, long timeout, Runnable expired)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!task.submit(work, timeout, expired)) {
            if (System.nanoTime() >= deadline) fail("Copy pool did not become available");
            Thread.sleep(5);
        }
    }

    private static final class BlockedStream extends InputStream {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch gate = new CountDownLatch(1);
        private final boolean respondsToClose;
        BlockedStream(boolean respondsToClose) { this.respondsToClose = respondsToClose; }
        @Override public int read() throws IOException {
            reading.countDown();
            while (true) {
                try { gate.await(); break; }
                catch (InterruptedException ignored) { /* emulate a provider ignoring interruption */ }
            }
            throw new IOException("Synthetic provider closed");
        }
        @Override public void close() { if (respondsToClose) gate.countDown(); }
    }

    @Test public void cancelClosesBlockingStreamAndRemovesOnlyItsPartialCopy() throws Exception {
        File root = temporary.newFolder("cache");
        CcsSqlImportCache cache = new CcsSqlImportCache(root, 8, 16, 1000, () -> 0);
        File good = cache.reserve(new Object()).copy(new ByteArrayInputStream(new byte[]{7}),
            "keep.sql", new CcsSqlImportTask());
        Object owner = new Object();
        CcsSqlImportTask task = new CcsSqlImportTask();
        BlockedStream input = new BlockedStream(true);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        submit(task, () -> {
            CcsSqlImportCache.Reservation reservation = null;
            try (InputStream stream = input) {
                task.track(stream);
                reservation = cache.reserve(owner);
                reservation.copy(stream, "partial.sql", task);
                unexpected.set(new AssertionError("Cancelled read unexpectedly succeeded"));
            } catch (IOException expected) { /* close unblocks the read */ }
            finally {
                if (reservation != null) reservation.abort();
                stopped.countDown();
            }
        }, 3000, () -> unexpected.set(new AssertionError("Cancel should beat the deadline")));
        try {
            await(input.reading);
            long start = System.nanoTime();
            task.cancel();
            assertTrue("Cancel blocks the caller", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
            await(stopped);
            assertNull(unexpected.get());
            assertTrue(good.exists());
            assertEquals(1, root.list().length);
            cache.reserve(owner).abort();
        } finally { input.gate.countDown(); }
    }

    @Test public void stuckProviderTimesOutKeepsQuotaAndDoesNotOccupyTheOnlyCopyThread() throws Exception {
        File root = temporary.newFolder("cache");
        CcsSqlImportCache cache = new CcsSqlImportCache(root, 8, 16, 1000, () -> 0);
        BlockedStream first = new BlockedStream(false);
        BlockedStream second = new BlockedStream(false);
        CcsSqlImportTask old = new CcsSqlImportTask();
        CcsSqlImportTask another = new CcsSqlImportTask();
        CountDownLatch timeout = new CountDownLatch(1);
        CountDownLatch oldExited = new CountDownLatch(1);
        CountDownLatch anotherExited = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        Runnable oldWork = blockedCopy(cache, old, first, oldExited, unexpected);
        Runnable anotherWork = blockedCopy(cache, another, second, anotherExited, unexpected);
        try {
            submit(old, oldWork, 50, timeout::countDown);
            await(first.reading);
            await(timeout);
            assertEquals(1, oldExited.getCount()); // provider ignored both cancel and close
            CountDownLatch nextFinished = new CountDownLatch(1);
            CcsSqlImportTask next = new CcsSqlImportTask();
            submit(next, () -> {
                CcsSqlImportCache.Reservation reservation = null;
                try {
                    reservation = cache.reserve(new Object());
                    File file = reservation.copy(new ByteArrayInputStream(new byte[]{1}), "next.sql", next);
                    cache.release(file.getAbsolutePath());
                } catch (Exception e) { unexpected.set(e); }
                finally { if (reservation != null) reservation.abort(); nextFinished.countDown(); }
            }, 3000, () -> unexpected.set(new AssertionError("Fresh copy timed out")));
            await(nextFinished);
            assertNull(unexpected.get());
            submit(another, anotherWork, 3000, () -> {});
            await(second.reading);
            assertFalse(new CcsSqlImportTask().submit(() -> {}, 3000, () -> {}));
            try { cache.reserve(new Object()); fail("Cancelled stuck reservations must still count"); }
            catch (IOException expected) { /* both 8-byte slots reserved */ }
        } finally {
            old.cancel();
            another.cancel();
            first.gate.countDown();
            second.gate.countDown();
            await(oldExited);
            await(anotherExited);
        }
        assertNull(unexpected.get());
        assertEquals(0, root.list().length);
        cache.reserve(new Object()).abort();
    }

    private static Runnable blockedCopy(CcsSqlImportCache cache, CcsSqlImportTask task,
                                        BlockedStream input, CountDownLatch stopped,
                                        AtomicReference<Throwable> unexpected) {
        return () -> {
            CcsSqlImportCache.Reservation reservation = null;
            try {
                reservation = cache.reserve(new Object());
                task.track(input);
                reservation.copy(input, "blocked.sql", task);
                unexpected.set(new AssertionError("Blocked copy returned successfully"));
            } catch (IOException expected) { /* cancelled provider */ }
            catch (Throwable e) { unexpected.set(e); }
            finally { if (reservation != null) reservation.abort(); stopped.countDown(); }
        };
    }

    @Test public void staleProviderReturningBytesAfterReloadCannotPublishOrWriteThem() throws Exception {
        File root = temporary.newFolder("cache");
        CcsSqlImportCache cache = new CcsSqlImportCache(root, 8, 16, 1000, () -> 0);
        CcsSqlImportLifecycle life = new CcsSqlImportLifecycle(cache);
        CcsSqlImportLifecycle.Request old = life.select("old-nonce-1");
        life.selected();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        InputStream ignoresCancel = new InputStream() {
            @Override public int read() {
                reading.countDown();
                while (true) {
                    try { gate.await(); return 1; }
                    catch (InterruptedException ignored) { /* late provider result */ }
                }
            }
        };
        submit(old.task, () -> {
            CcsSqlImportCache.Reservation reservation = null;
            try {
                reservation = life.reserve(old);
                old.task.track(ignoresCancel);
                reservation.copy(ignoresCancel, "old.sql", old.task);
                unexpected.set(new AssertionError("Stale bytes were published"));
            } catch (IOException expected) { /* cancelled after the read returns */ }
            finally { if (reservation != null) reservation.abort(); stopped.countDown(); }
        }, 3000, () -> unexpected.set(new AssertionError("Reload should beat the timeout")));
        try {
            await(reading);
            life.reload();
            assertFalse(life.finishCopy(old));
            CcsSqlImportLifecycle.Request next = life.select("new-nonce-1");
            life.selected();
            File fresh = life.reserve(next).copy(new ByteArrayInputStream(new byte[]{9}),
                "new.sql", next.task);
            assertTrue(life.finishCopy(next));
            gate.countDown();
            await(stopped);
            assertNull(unexpected.get());
            assertEquals(1, root.list().length);
            assertArrayEquals(new byte[]{9}, java.nio.file.Files.readAllBytes(fresh.toPath()));
            cache.release(fresh.getAbsolutePath());
        } finally { gate.countDown(); }
    }

    @Test public void lateHandleRegistrationAfterCancellationClosesTheHandle() throws Exception {
        CcsSqlImportTask task = new CcsSqlImportTask();
        CountDownLatch closed = new CountDownLatch(1);
        task.cancel();
        try { task.track((AutoCloseable) closed::countDown); fail("Late handle must not be used"); }
        catch (IOException expected) { /* cancellation wins */ }
        await(closed);
    }

    @Test public void providerCancellationIsSavedAndDoesNotBlockTheCaller() throws Exception {
        CcsSqlImportTask task = new CcsSqlImportTask();
        CountDownLatch called = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        task.onProviderCancel(() -> {
            called.countDown();
            try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            task.cancel();
            await(called);
            CountDownLatch closed = new CountDownLatch(1);
            try { task.track((AutoCloseable) closed::countDown); fail("Late handle must be cancelled"); }
            catch (IOException expected) { /* another closer remains available */ }
            await(closed);
        } finally { gate.countDown(); }
    }
}

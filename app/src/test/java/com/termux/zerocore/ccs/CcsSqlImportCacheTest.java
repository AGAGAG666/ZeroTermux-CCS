package com.termux.zerocore.ccs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class CcsSqlImportCacheTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Object owner = new Object();
    private final AtomicLong now = new AtomicLong();

    private CcsSqlImportCache cache(File root) {
        return new CcsSqlImportCache(root, 8, 16, 100, now::get);
    }

    private File copy(CcsSqlImportCache cache, int bytes) throws Exception {
        return cache.reserve(owner).copy(new ByteArrayInputStream(new byte[bytes]),
            "backup.sql", new CcsSqlImportTask());
    }

    private void full(CcsSqlImportCache cache) throws Exception {
        try { cache.reserve(owner); fail("Expected quota rejection"); }
        catch (IOException expected) { /* fail closed */ }
    }

    @Test public void rootSymlinkIsRejectedBeforeCanonicalizationOrCopy() throws Exception {
        File parent = temporary.newFolder("parent");
        File original = temporary.newFolder("original");
        File linkedRoot = new File(parent, "ccs-sql-import");
        java.nio.file.Files.createSymbolicLink(linkedRoot.toPath(), original.toPath());
        CcsSqlImportCache cache = new CcsSqlImportCache(linkedRoot, 8, 16, 1000, () -> 0);
        try { cache.reserve(new Object()); fail("Linked root must be rejected"); }
        catch (java.io.IOException expected) { /* never create a copy outside staging */ }
        assertEquals(0, original.list().length);
    }

    @Test public void registeredFileSymlinkCannotBePinnedOrDeleteItsTarget() throws Exception {
        File root = temporary.newFolder("owned-root");
        CcsSqlImportCache cache = new CcsSqlImportCache(root, 8, 16, 1000, () -> 0);
        Object owner = new Object();
        File copy = cache.reserve(owner).copy(new java.io.ByteArrayInputStream(new byte[]{1}),
            "backup.sql", new CcsSqlImportTask());
        File original = new File(temporary.newFolder("original"), "backup.sql");
        java.nio.file.Files.write(original.toPath(), new byte[]{1});
        java.nio.file.Files.delete(copy.toPath());
        java.nio.file.Files.createSymbolicLink(copy.toPath(), original.toPath());
        assertFalse(cache.begin(copy.getAbsolutePath(), owner));
        cache.release(copy.getAbsolutePath());
        cache.expire();
        assertArrayEquals(new byte[]{1}, java.nio.file.Files.readAllBytes(original.toPath()));
        try { cache.reserve(owner); fail("Replaced cache cannot admit new copies"); }
        catch (java.io.IOException expected) { /* fail closed */ }
    }

    @Test public void registeredParentSymlinkCannotPinOrOverwriteOriginal() throws Exception {
        File root = temporary.newFolder("owned-root");
        CcsSqlImportCache cache = new CcsSqlImportCache(root, 8, 16, 1000, () -> 0);
        Object owner = new Object();
        File copy = cache.reserve(owner).copy(new java.io.ByteArrayInputStream(new byte[]{1}),
            "backup.sql", new CcsSqlImportTask());
        File originalDirectory = temporary.newFolder("original");
        File original = new File(originalDirectory, "backup.sql");
        java.nio.file.Files.write(original.toPath(), new byte[]{1});
        java.nio.file.Files.delete(copy.toPath());
        java.nio.file.Files.delete(copy.getParentFile().toPath());
        java.nio.file.Files.createSymbolicLink(copy.getParentFile().toPath(), originalDirectory.toPath());
        assertFalse(cache.begin(copy.getAbsolutePath(), owner));
        try {
            CcsSqlImportFile.copyTo(new java.io.ByteArrayInputStream(new byte[]{2}), copy, 8,
                new CcsSqlImportTask());
            fail("Replaced parent must not be written through");
        } catch (java.io.IOException expected) { /* original document untouched */ }
        cache.release(copy.getAbsolutePath());
        assertArrayEquals(new byte[]{1}, java.nio.file.Files.readAllBytes(original.toPath()));
    }

    @Test public void repeatedSelectionsAndReleasesStayBoundedAndPreserveOriginals() throws Exception {
        File root = temporary.newFolder("cache");
        File original = temporary.newFile("original.sql");
        Files.write(original.toPath(), new byte[]{42});
        CcsSqlImportCache cache = cache(root);
        for (int i = 0; i < 200; i++) {
            File file = copy(cache, 8);
            assertTrue(cache.begin(file.getAbsolutePath(), owner));
            cache.release(file.getAbsolutePath());
            assertTrue(file.exists());
            cache.end(file.getAbsolutePath());
            assertFalse(file.exists());
            assertEquals(0, root.list().length);
        }
        cache.release(original.getAbsolutePath());
        assertArrayEquals(new byte[]{42}, Files.readAllBytes(original.toPath()));
    }

    @Test public void releaseKeepsAllPinsUntilTheirRealEndAndRejectsRepin() throws Exception {
        CcsSqlImportCache cache = cache(temporary.newFolder("cache"));
        File file = copy(cache, 8);
        String path = file.getAbsolutePath();
        assertTrue(cache.begin(path, owner));
        assertTrue(cache.begin(path, owner));
        cache.release(path);
        cache.release(path);
        assertFalse(cache.begin(path, owner));
        cache.end(path);
        assertTrue(file.exists());
        cache.end(path);
        assertFalse(file.exists());
        cache.end(path);
    }

    @Test public void includesOutstandingReservationsInTotalQuota() throws Exception {
        CcsSqlImportCache cache = cache(temporary.newFolder("cache"));
        CcsSqlImportCache.Reservation first = cache.reserve(owner);
        CcsSqlImportCache.Reservation second = cache.reserve(owner);
        full(cache);
        cache.releaseOwner(owner);
        full(cache); // cancellation/release is not evidence that the writer exited
        first.abort();
        CcsSqlImportCache.Reservation next = cache.reserve(owner);
        second.abort();
        next.abort();
        cache.reserve(owner).abort();
    }

    @Test public void productionQuotaIncludesAnOldSparseCacheFileBeyondSixtyFourMiB() throws Exception {
        File root = temporary.newFolder("cache");
        File old = new File(root, "old-pinned.sql");
        try (java.io.RandomAccessFile sparse = new java.io.RandomAccessFile(old, "rw")) {
            sparse.setLength(CcsSqlImportFile.MAX_BYTES + 1);
        }
        assertEquals(64L * 1024 * 1024, CcsSqlImportFile.MAX_BYTES);
        assertEquals(128L * 1024 * 1024, CcsSqlImportCache.TOTAL_BYTES);
        CcsSqlImportCache production = new CcsSqlImportCache(root, CcsSqlImportFile.MAX_BYTES,
            CcsSqlImportCache.TOTAL_BYTES, 100, now::get);
        full(production); // old bytes plus the 64 MiB reservation exceeds 128 MiB
        production.expire();
        assertEquals(CcsSqlImportFile.MAX_BYTES + 1, old.length());
    }

    @Test public void oldUnknownCacheBytesCountAndAreNeverDeleted() throws Exception {
        File root = temporary.newFolder("cache");
        File old = new File(root, "old-pinned.sql");
        Files.write(old.toPath(), new byte[9]);
        CcsSqlImportCache cache = cache(root);
        full(cache);
        cache.release(old.getAbsolutePath());
        cache.end(old.getAbsolutePath());
        now.set(1000);
        cache.expire();
        assertTrue(old.exists());
        assertEquals(9, old.length());
    }

    @Test public void rebuildingManagerDoesNotForgetOrDeleteOldPins() throws Exception {
        File root = temporary.newFolder("cache");
        CcsSqlImportCache oldCache = cache(root);
        File pinned = copy(oldCache, 8);
        assertTrue(oldCache.begin(pinned.getAbsolutePath(), owner));
        oldCache.release(pinned.getAbsolutePath());
        CcsSqlImportCache rebuilt = cache(root);
        assertFalse(rebuilt.begin(pinned.getAbsolutePath(), owner));
        CcsSqlImportCache.Reservation second = rebuilt.reserve(new Object());
        full(rebuilt);
        rebuilt.release(pinned.getAbsolutePath());
        rebuilt.end(pinned.getAbsolutePath());
        now.set(1000);
        rebuilt.expire();
        assertTrue(pinned.exists());
        second.abort();
        oldCache.end(pinned.getAbsolutePath());
        assertFalse(pinned.exists());
    }

    @Test public void expiryNeverDeletesUnknownCompletionPinsOrFreesTheirQuota() throws Exception {
        File root = temporary.newFolder("cache");
        CcsSqlImportCache cache = cache(root);
        File first = copy(cache, 8);
        File second = copy(cache, 8);
        assertTrue(cache.begin(first.getAbsolutePath(), owner));
        assertTrue(cache.begin(second.getAbsolutePath(), owner));
        cache.releaseOwner(owner);
        now.set(Long.MAX_VALUE / 2);
        cache.expire();
        assertTrue(first.exists());
        assertTrue(second.exists());
        full(cache);
        cache.end(first.getAbsolutePath());
        assertFalse(first.exists());
        cache.reserve(owner).abort();
        assertTrue(second.exists());
        cache.end(second.getAbsolutePath());
    }

    @Test public void successfulUnpinnedCopiesExpireAndBecomeUnpinnable() throws Exception {
        CcsSqlImportCache cache = cache(temporary.newFolder("cache"));
        File file = copy(cache, 1);
        now.set(100);
        assertFalse(cache.begin(file.getAbsolutePath(), owner));
        assertFalse(file.exists());
        cache.reserve(owner).abort();
    }

    @Test public void rejectsUnownedAliasedAndWrongPagePaths() throws Exception {
        CcsSqlImportCache cache = cache(temporary.newFolder("cache"));
        File file = copy(cache, 1);
        assertFalse(cache.begin(file.getAbsolutePath(), new Object()));
        assertFalse(cache.begin(new File(file.getParentFile(), "./backup.sql").getPath(), owner));
        assertFalse(cache.begin(null, owner));
        assertTrue(file.exists());
        cache.release(file.getAbsolutePath());
    }

    @Test public void unknownFileCountIsBoundedEvenForZeroByteFiles() throws Exception {
        File root = temporary.newFolder("cache");
        for (int i = 0; i < CcsSqlImportCache.MAX_ENTRIES; i++) {
            assertTrue(new File(root, "unknown-" + i).createNewFile());
        }
        CcsSqlImportCache cache = cache(root);
        full(cache);
        cache.expire();
        assertEquals(CcsSqlImportCache.MAX_ENTRIES, root.list().length);
    }

    @Test public void symlinkedUnknownFilesFailClosedWithoutFollowingOrDeleting() throws Exception {
        File root = temporary.newFolder("cache");
        File external = temporary.newFile("external.sql");
        Files.write(external.toPath(), new byte[]{7});
        Files.createSymbolicLink(new File(root, "link.sql").toPath(), external.toPath());
        full(cache(root));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(external.toPath()));
        assertTrue(Files.isSymbolicLink(new File(root, "link.sql").toPath()));
    }

    @Test public void failedDirectoryDeletionKeepsChargeAndNeverRemovesForeignChildren() throws Exception {
        File root = temporary.newFolder("cache");
        CcsSqlImportCache cache = cache(root);
        File file = copy(cache, 8);
        File foreign = new File(file.getParentFile(), "foreign.txt");
        Files.write(foreign.toPath(), new byte[]{5});
        cache.release(file.getAbsolutePath());
        assertTrue(foreign.exists());
        full(cache); // failed cleanup retains 8 bytes, plus the unknown child
        assertTrue(foreign.exists());
    }
}

package com.termux.zerocore.ccs;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class CcsSqlImportFileTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void refusesCleanupThroughAReplacedParentSymlink() throws Exception {
        File cache = temporary.newFolder("cache");
        File original = temporary.newFolder("original");
        File backup = new File(original, "backup.sql");
        Files.write(backup.toPath(), new byte[]{1, 2, 3});
        File replaced = new File(cache, "replaced");
        Files.createSymbolicLink(replaced.toPath(), original.toPath());
        assertFalse(CcsSqlImportFile.discard(new File(replaced, "backup.sql")));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(backup.toPath()));
    }

    @Test public void copiesOnlyToPrivateStagingAndPreservesTheSqlName() throws Exception {
        File root = temporary.newFolder("cache");
        byte[] bytes = "-- synthetic SQL backup".getBytes("UTF-8");
        File copy = CcsSqlImportFile.copy(new ByteArrayInputStream(bytes), "我的备份.sql", root, 100);
        assertArrayEquals(bytes, Files.readAllBytes(copy.toPath()));
        assertEquals("我的备份.sql", copy.getName());
        assertEquals(root.getCanonicalFile(), copy.getParentFile().getParentFile().getCanonicalFile());
    }

    @Test public void preventsDisplayNamePathTraversal() throws Exception {
        assertEquals("backup.sql", CcsSqlImportFile.safeName("../../backup.sql"));
        assertEquals("backup.sql", CcsSqlImportFile.safeName("C:\\private\\backup.sql"));
        File root = temporary.newFolder("cache");
        File copy = CcsSqlImportFile.copy(new ByteArrayInputStream(new byte[]{1}), "../backup.sql", root, 8);
        assertTrue(copy.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator));
    }

    @Test public void removesControlCharactersAndBoundsUnicodeNames() throws Exception {
        assertEquals("backup.sql", CcsSqlImportFile.safeName("back\nup.sql"));
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 150; i++) name.append("备");
        String safe = CcsSqlImportFile.safeName(name + ".sql");
        assertTrue(safe.endsWith(".sql"));
        assertTrue(safe.getBytes("UTF-8").length <= 255);
    }

    @Test public void acceptsUnknownNamesAndCaseInsensitiveSqlExtensions() throws Exception {
        assertEquals("backup.sql", CcsSqlImportFile.safeName(null));
        assertEquals("BACKUP.SQL", CcsSqlImportFile.safeName("BACKUP.SQL"));
    }

    @Test public void rejectsNonSqlBeforeCreatingAFile() throws Exception {
        File root = temporary.newFolder("cache");
        try {
            CcsSqlImportFile.copy(new ByteArrayInputStream(new byte[]{1}), "payload.zip", root, 8);
            fail("Non-SQL files must be rejected");
        } catch (CcsSqlImportFile.InvalidFileException expected) {
            assertEquals(0, root.list().length);
        }
    }

    @Test public void rejectsEmptyBackupsAndRemovesOnlyThePartialCopy() throws Exception {
        File root = temporary.newFolder("cache");
        try {
            CcsSqlImportFile.copy(new ByteArrayInputStream(new byte[0]), "backup.sql", root, 8);
            fail("Empty backup must be rejected");
        } catch (CcsSqlImportFile.InvalidFileException expected) {
            assertEquals(0, root.list().length);
        }
    }

    @Test public void acceptsExactLimitButRejectsOverflowAndLeavesExistingFilesAlone() throws Exception {
        File root = temporary.newFolder("cache");
        File good = CcsSqlImportFile.copy(new ByteArrayInputStream(new byte[]{1,2,3}), "backup.sql", root, 3);
        try {
            CcsSqlImportFile.copy(new ByteArrayInputStream(new byte[]{1,2,3,4}), "backup.sql", root, 3);
            fail("Overflow must be rejected");
        } catch (CcsSqlImportFile.InvalidFileException expected) {
            assertTrue(good.isFile());
            assertEquals(1, root.list().length);
        }
    }

    @Test public void abortsInterruptedCopies() throws Exception {
        File root = temporary.newFolder("cache");
        Thread.currentThread().interrupt();
        try {
            CcsSqlImportFile.copy(new ByteArrayInputStream(new byte[]{1}), "backup.sql", root, 8);
            fail("Interrupted copy must abort");
        } catch (InterruptedIOException expected) {
            assertEquals(0, root.list().length);
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void makesProgressWhenAProviderReturnsAZeroLengthChunk() throws Exception {
        File root = temporary.newFolder("cache");
        InputStream unusual = new ByteArrayInputStream(new byte[]{1, 2}) {
            boolean zero = true;
            @Override public int read(byte[] data, int off, int len) {
                if (zero) { zero = false; return 0; }
                return super.read(data, off, len);
            }
        };
        File copy = CcsSqlImportFile.copy(unusual, "backup.sql", root, 8);
        assertArrayEquals(new byte[]{1, 2}, Files.readAllBytes(copy.toPath()));
    }

    @Test public void cleansPartialDataWhenTheProviderFailsMidRead() throws Exception {
        File root = temporary.newFolder("cache");
        InputStream failed = new InputStream() {
            int calls;
            @Override public int read() throws IOException {
                if (calls++ == 0) return 1;
                throw new IOException("synthetic read failure");
            }
        };
        try {
            CcsSqlImportFile.copy(failed, "backup.sql", root, 8);
            fail("Read failure must propagate");
        } catch (IOException expected) {
            assertEquals(0, root.list().length);
        }
    }
}

package com.termux.app.reference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ReferenceCopyTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private InputStream sample() { return new ByteArrayInputStream(new byte[]{1,2,3}); }

    @Test public void providerNamesCannotEscapeAndSameNamesDoNotCollide() throws Exception {
        File root = tmp.newFolder();
        for (String name : new String[]{"../outside", ".", "..", "a\\b", "x\ny", null}) {
            File f = ReferenceCopy.save(root, name, "png", sample(), () -> true);
            assertEquals("selection.png", f.getName());
            assertEquals(root, f.getParentFile().getParentFile());
            assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(f.toPath()));
        }
        File a = ReferenceCopy.save(root, "图 (1).png", null, sample(), () -> true);
        File b = ReferenceCopy.save(root, "图 (1).png", null, sample(), () -> true);
        assertEquals("图 (1).png", a.getName());
        assertNotEquals(a,b);
    }

    @Test public void cancellationDoesNotLeaveAnIncompleteCopy() throws Exception {
        File root = tmp.newFolder();
        try {
            ReferenceCopy.save(root, "file.txt", null, sample(), () -> false);
            fail();
        } catch (IOException expected) { assertEquals(0, root.list().length); }
    }
}

package com.termux.app.terminal;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class TerminalFileLinkTest {
    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    @Test public void fileOpensItsDirectoryWithLiteralDocumentIds() throws Exception {
        File home = tmp.newFolder("home");
        File shared = tmp.newFolder("shared");
        File dir = new File(home, "带空格 %2F");
        assertTrue(dir.mkdir());
        File apk = new File(dir, "app (测试).apk");
        assertTrue(apk.createNewFile());
        TerminalFileLink target = TerminalFileLink.resolve(apk.getPath(), home, shared);
        assertEquals(dir.getCanonicalFile(), target.directory);
        assertEquals(dir.getCanonicalPath(), target.documentId);
        assertEquals(apk.getCanonicalPath(), target.targetDocumentId);
        assertTrue(target.inHome);
    }

    @Test public void sharedDirectoryOrFileUsesPrimaryDocumentIds() throws Exception {
        File home = tmp.newFolder("home");
        File shared = tmp.newFolder("shared");
        File screenshots = new File(shared, "DCIM/Screenshots");
        assertTrue(screenshots.mkdirs());
        File image = new File(screenshots, "一 二.png");
        assertTrue(image.createNewFile());
        Files.createSymbolicLink(new File(home, "共享").toPath(), shared.toPath());
        TerminalFileLink file = TerminalFileLink.resolve(new File(home, "共享/DCIM/Screenshots/一 二.png").getPath(), home, shared);
        assertFalse(file.inHome);
        assertEquals("primary:DCIM/Screenshots", file.documentId);
        assertEquals("primary:DCIM/Screenshots/一 二.png", file.targetDocumentId);
        assertEquals(file.documentId, TerminalFileLink.resolve(screenshots.getPath(), home, shared).documentId);
        assertEquals("primary:", TerminalFileLink.resolve(shared.getPath(), home, shared).documentId);
    }

    @Test public void rejectsMissingTargetsAndEscapes() throws Exception {
        File home = tmp.newFolder("home");
        File shared = tmp.newFolder("shared");
        File outside = tmp.newFolder("home-other");
        File symlink = new File(home, "outside");
        Files.createSymbolicLink(symlink.toPath(), outside.toPath());
        for (String path : new String[]{null, "relative", new File(home, "missing").getPath(),
                outside.getPath(), symlink.getPath(), home.getPath() + "/x\ny"}) {
            assertNull(TerminalFileLink.resolve(path, home, shared));
        }
    }
}

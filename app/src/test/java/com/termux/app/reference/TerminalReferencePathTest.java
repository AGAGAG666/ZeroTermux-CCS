package com.termux.app.reference;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TerminalReferencePathTest {
    @Test public void acceptsOrdinaryPathsAndQuotesWhenNeeded() {
        assertEquals("/home/中文/截图,1.png", TerminalReferencePath.format("/home/中文/截图,1.png"));
        assertEquals("'/storage/DCIM/截图 (1).png'", TerminalReferencePath.format("/storage/DCIM/截图 (1).png"));
    }

    @Test public void rejectsAllTerminalControlCharacters() {
        for (String path : new String[] { "relative", "/tmp/a\ncmd", "/tmp/a\r", "/tmp/a\t",
                "/tmp/a\u001b", "/tmp/a\u009b", "/tmp/a\u007f", "/tmp/\ud800" }) {
            assertFalse(path, TerminalReferencePath.isSafe(path));
        }
    }

    @Test public void shellRoundTripsMetacharactersWithoutExecutingThem() throws Exception {
        for (String path : new String[] { "/home/截图 (1).png", "/home/it's $HOME `echo bad`.png",
                "/home/a;echo bad|cat.png", "/home/双\"引号\\路径.png" }) {
            String encoded = TerminalReferencePath.format(path);
            Process process = new ProcessBuilder("sh", "-c", "set -- " + encoded + "; printf %s \"$1\"").start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[512];
                int n;
                while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            assertEquals(0, process.waitFor());
            assertEquals(path, new String(out.toByteArray(), StandardCharsets.UTF_8));
            assertTrue(TerminalReferencePath.isSafe(path));
        }
    }
}

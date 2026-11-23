package com.termux.terminal;

public class HyperlinkTest extends TerminalTestCase {
    private String link(int x, int y) { return mTerminal.getScreen().getLocalFileLinkAt(x, y); }
    private void open(String target) { enterString("\033]8;id=sample;" + target + "\007"); }
    private void close() { enterString("\033]8;;\033\\"); }

    public void testWideWrapCombiningAndOverwrite() {
        withTerminalSized(5, 3);
        open("file:///sample/a%20b");
        enterString("ab中e\u0301X");
        close();
        for (int x = 0; x < 5; x++) assertEquals("/sample/a b", link(x, 0));
        assertEquals("/sample/a b", link(0, 1));
        placeCursorAndAssert(0, 3);
        enterString("Z");
        assertNull(link(2, 0));
        assertNull(link(3, 0));
        assertEquals("/sample/a b", link(4, 0));
    }

    public void testCopyClearHistoryAndResize() {
        withTerminalSized(8, 3);
        open("/synthetic"); enterString("A中 B"); close();
        mTerminal.getScreen().blockCopy(0, 0, 5, 1, 1, 1);
        for (int x = 1; x <= 5; x++) assertEquals("/synthetic", link(x, 1));
        mTerminal.getScreen().blockSet(2, 1, 1, 1, ' ', TextStyle.NORMAL);
        assertNull(link(2, 1)); assertNull(link(3, 1));
        enterString("\033[3;1H\n");
        assertEquals("/synthetic", link(0, -1));
        resize(6, 3);
        boolean found = false;
        TerminalBuffer b = mTerminal.getScreen();
        for (int y = -b.getActiveTranscriptRows(); y < 3; y++)
            for (int x = 0; x < 6; x++) found |= "/synthetic".equals(b.getLocalFileLinkAt(x, y));
        assertTrue(found);
    }

    public void testDisplayedUnderlinedPathWithoutOsc() {
        withTerminalSized(12, 4);
        enterString("\033[4m/sample/a b c.png\033[0m.");
        TerminalBuffer screen = mTerminal.getScreen();
        assertEquals("/sample/a b c.png", screen.getUnderlinedAbsolutePathAt(0, 1));
        assertNull(screen.getUnderlinedAbsolutePathAt(5, 1));
        enterString("\r\n\033[4mOpen APK\033[0m");
        assertNull(screen.getUnderlinedAbsolutePathAt(1, 2));
    }

    public void testInvalidAndAlternateAndReset() {
        withTerminalSized(15, 3);
        String[] invalid = {"https://example.org/", "file://remote/path", "file:relative", "//remote/path", "file:///bad%00", "file:///bad%xx"};
        for (String target : invalid) { open(target); enterString("x"); assertNull(link(mTerminal.getCursorCol() - 1, 0)); }
        open("/good"); enterString("a");
        enterString("\033[?1049h"); enterString("b"); assertNull(link(0, 0));
        open("/alt"); enterString("c");
        enterString("\033[?1049l"); enterString("d"); assertNull(link(mTerminal.getCursorCol() - 1, 0));
        open("/reset"); mTerminal.reset(); enterString("e"); assertNull(link(mTerminal.getCursorCol() - 1, 0));
    }

    public void testMalformedAndOversizedOsc() {
        withTerminalSized(10, 3);
        open("/old");
        enterString("\033]8;;/bad\001path\007x"); assertNull(link(0, 0));
        StringBuilder b = new StringBuilder("\033]8;;/");
        for (int i = 0; i < 9000; i++) b.append('a');
        enterString(b.append("\007y").toString()); assertNull(link(1, 0));
    }
    public void testReflowPreservesTextAfterCursor() {
        withTerminalSized(8, 3);
        open("/target"); enterString("abcdef"); close();
        placeCursorAndAssert(0, 1);
        resize(5, 3).assertCursorAt(0, 1).assertLinesAre("abcde", "f    ", "     ");
        TerminalBuffer b = mTerminal.getScreen();
        int count = 0;
        for (int y = -b.getActiveTranscriptRows(); y < 3; y++)
            for (int x = 0; x < 5; x++) if ("/target".equals(b.getLocalFileLinkAt(x, y))) count++;
        assertEquals(6, count);
    }

    public void testDecodedPathAndRejectedEncodedControls() {
        withTerminalSized(12, 2);
        open("file:///a/%E4%B8%AD%20x"); enterString("a"); close();
        assertEquals("/a/中 x", link(0, 0));
        for (String t : new String[]{"file:///x%FF", "file:///x%0A", "file:////host/x", "file:///x%5Cz"}) {
            open(t); enterString("b");
            assertNull(link(mTerminal.getCursorCol() - 1, 0));
        }
    }

    public void testReflowWrappedWideAndCombiningAfterCursor() {
        withTerminalSized(6, 4);
        open("/wide"); enterString("ab中e\u0301FGH"); close();
        placeCursorAndAssert(0, 1);
        resize(4, 4).assertCursorAt(0, 1);
        assertLinesAre("ab中", "e\u0301FGH", "    ", "    ");
        assertLineWraps(true, false, false, false);
        for (int row = 0; row < 2; row++)
            for (int col = 0; col < 4; col++) assertEquals("/wide", link(col, row));
        resize(8, 4).assertCursorAt(0, 1);
        assertLinesAre("ab中e\u0301FGH", "        ", "        ", "        ");
        for (int col = 0; col < 8; col++) assertEquals("/wide", link(col, 0));
    }

    public void testReflowCursorOnSecondHalfOfWideCharacter() {
        withTerminalSized(6, 3);
        open("/wide"); enterString("a中bc"); close();
        placeCursorAndAssert(0, 2);
        resize(4, 3).assertCursorAt(0, 2).assertLinesAre("a中b", "c   ", "    ");
        assertEquals("/wide", link(1, 0));
        assertEquals("/wide", link(2, 0));
    }

    public void testReflowPreservesLinkedTrailingSpaces() {
        withTerminalSized(8, 3);
        open("/spaces"); enterString("a   "); close();
        placeCursorAndAssert(0, 0);
        resize(3, 3).assertCursorAt(0, 0).assertLinesAre("a  ", "   ", "   ");
        assertEquals("/spaces", link(0, 0));
        assertEquals("/spaces", link(1, 0));
        assertEquals("/spaces", link(2, 0));
        assertEquals("/spaces", link(0, 1));
        assertNull(link(1, 1));
    }

    public void testLongWrappedLinkSharesTargetAcrossHistory() {
        withTerminalSized(80, 5);
        open("file:///synthetic/large%20file");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 16000; i++) text.append('x');
        enterString(text.toString()); close();
        TerminalBuffer b = mTerminal.getScreen();
        assertTrue(b.getActiveTranscriptRows() > 100);
        String target = b.getLocalFileLinkAt(0, 0);
        assertEquals("/synthetic/large file", target);
        for (int row = -b.getActiveTranscriptRows(); row < 5; row++)
            for (int col = 0; col < 80; col++) assertSame(target, b.getLocalFileLinkAt(col, row));
    }

}

package com.termux.app.terminal;

import java.io.File;
import java.io.IOException;

/** A local hyperlink resolves to an existing directory or the parent of a regular file. */
final class TerminalFileLink {
    final File target;
    final File directory;
    final String documentId;
    final String targetDocumentId;
    final boolean inHome;

    private TerminalFileLink(File target, File directory, File root, boolean inHome) {
        this.target = target;
        this.directory = directory;
        this.inHome = inHome;
        this.documentId = idFor(directory, root, inHome);
        this.targetDocumentId = idFor(target, root, inHome);
    }

    private static String idFor(File file, File root, boolean inHome) {
        if (inHome) return file.getPath();
        return "primary:" + (file.equals(root) ? "" : file.getPath().substring(root.getPath().length() + 1));
    }

    private static boolean within(File file, File root) {
        return file.equals(root) || file.getPath().startsWith(root.getPath() + File.separator);
    }

    static TerminalFileLink resolve(String path, File home, File shared) throws IOException {
        if (path == null || !new File(path).isAbsolute()) return null;
        for (int i = 0; i < path.length(); i++)
            if (Character.isISOControl(path.charAt(i))) return null;
        File target = new File(path).getCanonicalFile();
        home = home.getCanonicalFile();
        shared = shared.getCanonicalFile();
        boolean inHome = within(target, home);
        if (!inHome && !within(target, shared)) return null;
        File dir = target.isDirectory() ? target : target.isFile() ? target.getParentFile() : null;
        if (dir == null || !dir.canRead()) return null;
        return new TerminalFileLink(target, dir, inHome ? home : shared, inHome);
    }
}

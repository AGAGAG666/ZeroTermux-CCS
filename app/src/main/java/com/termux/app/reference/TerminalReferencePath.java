package com.termux.app.reference;

import java.io.File;

/** Formats one path as a shell-safe terminal paste. */
public final class TerminalReferencePath {
    private TerminalReferencePath() { }

    public static boolean isSafe(String path) {
        if (path == null || !path.startsWith("/")) return false;
        for (int offset = 0; offset < path.length();) {
            int c = path.codePointAt(offset);
            if (Character.isISOControl(c) || Character.getType(c) == Character.SURROGATE) return false;
            offset += Character.charCount(c);
        }
        return true;
    }

    public static boolean isReadableFile(File file) {
        return file != null && file.isAbsolute() && file.isFile() && file.canRead()
            && isSafe(file.getAbsolutePath());
    }

    static boolean isInsideHome(File file, File home) throws java.io.IOException {
        return file.getCanonicalPath().startsWith(home.getCanonicalPath() + File.separator);
    }

    public static String format(String path) {
        if (!isSafe(path)) throw new IllegalArgumentException("Invalid path");
        boolean quote = false;
        for (int offset = 0; offset < path.length();) {
            int c = path.codePointAt(offset);
            if (!(Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK
                    || Character.getType(c) == Character.COMBINING_SPACING_MARK
                    || c == '/' || c == '.' || c == '_' || c == '-' || c == ',')) quote = true;
            offset += Character.charCount(c);
        }
        return quote ? "'" + path.replace("'", "'\\''") + "'" : path;
    }
}

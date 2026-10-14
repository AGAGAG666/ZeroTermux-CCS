package com.termux.zerocore.ccs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

/** A private, bounded SQL copy; the original document is never changed. */
final class CcsSqlImportFile {
    static final long MAX_BYTES = 64L * 1024L * 1024L;

    static final class InvalidFileException extends IOException {
        private static final long serialVersionUID = 1L;
        InvalidFileException(String message) { super(message); }
    }

    private CcsSqlImportFile() {}

    static String safeName(String name) throws InvalidFileException {
        if (name == null || name.trim().isEmpty()) return "backup.sql";
        String normalized = name.replace('\\', '/');
        String leaf = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (!leaf.toLowerCase(Locale.ROOT).endsWith(".sql")) {
            throw new InvalidFileException("请选择 .sql 格式的 CC Switch 备份文件");
        }
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < leaf.length(); i++) {
            char c = leaf.charAt(i);
            if (c >= 0x20 && c != 0x7f && c != '/' && c != '\\') clean.append(c);
        }
        String result = clean.toString();
        if (result.length() > 80) result = result.substring(0, 76) + ".sql";
        return result.isEmpty() || result.equals(".sql") ? "backup.sql" : result;
    }

    static File create(File root, String displayName) throws IOException {
        String name = safeName(displayName);
        if (!root.getAbsolutePath().equals(root.getCanonicalPath())) {
            throw new IOException("Unsafe SQL cache root");
        }
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建 SQL 导入缓存");
        File directory = new File(root, UUID.randomUUID().toString());
        if (!directory.mkdir()) throw new IOException("无法创建 SQL 导入缓存");
        return new File(directory, name);
    }

    static File copy(InputStream input, String displayName, File root, long maximumBytes)
            throws IOException {
        File file = create(root, displayName);
        boolean complete = false;
        try {
            copyTo(input, file, maximumBytes, new CcsSqlImportTask());
            complete = true;
            return file;
        } finally {
            if (!complete) discard(file);
        }
    }

    static void copyTo(InputStream input, File file, long maximumBytes, CcsSqlImportTask task)
            throws IOException {
        if (input == null) throw new IOException("所选文件无法读取");
        if (maximumBytes <= 0 || maximumBytes > MAX_BYTES) {
            throw new IllegalArgumentException("Invalid SQL copy limit");
        }
        if (!ownedLocation(file)) throw new IOException("SQL staging location changed");
        long total = 0;
        byte[] buffer = new byte[8192];
        try (FileOutputStream output = new FileOutputStream(file)) {
            task.track(output);
            try {
                while (true) {
                    task.check();
                    int read = input.read(buffer);
                    task.check();
                    if (read == -1) break;
                    if (read == 0) {
                        int single = input.read();
                        task.check();
                        if (single == -1) break;
                        buffer[0] = (byte) single;
                        read = 1;
                    }
                    if (read > maximumBytes - total) {
                        throw new InvalidFileException("SQL 备份文件过大，最大支持 64 MiB");
                    }
                    output.write(buffer, 0, read);
                    total += read;
                }
                if (total == 0) throw new InvalidFileException("SQL 备份文件为空");
                task.check();
            } finally {
                task.untrack(output);
            }
        }
    }

    static boolean ownedLocation(File file) {
        try {
            File parent = file.getParentFile();
            return parent != null
                && parent.getAbsolutePath().equals(parent.getCanonicalPath())
                && file.getAbsolutePath().equals(file.getCanonicalPath());
        } catch (IOException e) {
            return false;
        }
    }

    /** Only receives copies created by this class, never an external document. */
    static boolean discard(File file) {
        if (file == null) return true;
        File parent = file.getParentFile();
        if (!ownedLocation(file)) return false;
        if (file.exists() && !file.delete()) return false;
        return parent == null || !parent.exists() || parent.delete();
    }
}

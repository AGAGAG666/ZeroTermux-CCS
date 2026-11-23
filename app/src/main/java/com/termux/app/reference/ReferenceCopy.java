package com.termux.app.reference;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

final class ReferenceCopy {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    interface Continue { boolean allowed(); }

    static File save(File root, String name, String ext, InputStream input, Continue copy) throws IOException {
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory()) throw new IOException();
        File dir = new File(root, UUID.randomUUID().toString());
        if (!dir.mkdir()) throw new IOException();
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.length() > 100
                || !TerminalReferencePath.isSafe("/" + name)) name = "selection";
        if (!name.contains(".") && ext != null && ext.matches("[a-zA-Z0-9]{1,10}")) name += "." + ext;
        File out = new File(dir, name);
        try (FileOutputStream stream = new FileOutputStream(out)) {
            byte[] buf = new byte[32768];
            long count = 0;
            for (int n; (n = input.read(buf)) != -1;) {
                count += n;
                if (!copy.allowed() || count > MAX_BYTES) throw new IOException();
                stream.write(buf, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            discard(out);
            throw e;
        }
        return out;
    }

    static void discard(File copy) {
        copy.delete();
        copy.getParentFile().delete();
    }
}

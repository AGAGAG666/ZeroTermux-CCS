package com.termux.app.reference;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Maps known system provider identifiers, never the displayed file name. */
final class SharedReferencePath {
    private SharedReferencePath() {}

    static File find(File root, boolean systemProvider, String pkg, String authority, List<String> parts) {
        if (!systemProvider || parts == null) return null;
        String relative;
        if ("com.android.externalstorage".equals(pkg) && "com.android.externalstorage.documents".equals(authority)) {
            boolean doc = parts.size() == 2 && "document".equals(parts.get(0));
            boolean treeDoc = parts.size() == 4 && "tree".equals(parts.get(0)) && "document".equals(parts.get(2));
            if (!doc && !treeDoc) return null;
            String id = parts.get(parts.size() - 1);
            if (!id.startsWith("primary:")) return null;
            relative = id.substring("primary:".length());
        } else if ("com.android.fileexplorer".equals(pkg)
                && ("com.android.fileexplorer.myprovider".equals(authority)
                    || "com.android.fileexplorer.lazyfileprovider".equals(authority))
                && parts.size() >= 2 && "external_files".equals(parts.get(0))) {
            StringBuilder path = new StringBuilder();
            for (int i = 1; i < parts.size(); i++) {
                String part = parts.get(i);
                if (part.isEmpty() || part.indexOf('/') >= 0) return null;
                if (i > 1) path.append('/');
                path.append(part);
            }
            relative = path.toString();
        } else return null;

        for (String part : relative.split("/", -1))
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return null;
        try {
            File base = root.getCanonicalFile();
            File file = new File(base, relative).getCanonicalFile();
            return file.getPath().startsWith(base.getPath() + File.separator)
                && TerminalReferencePath.isReadableFile(file) ? file : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}

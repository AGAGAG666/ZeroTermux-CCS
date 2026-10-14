package com.termux.zerocore.ccs;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Owns only files it creates. Unknown old cache files count toward quota, but are never deleted. */
final class CcsSqlImportCache {
    static final long TOTAL_BYTES = 128L * 1024L * 1024L;
    static final long LIFETIME_MS = 10L * 60L * 1000L;
    static final int MAX_ENTRIES = 32;
    interface Clock { long now(); }
    private static CcsSqlImportCache shared;

    static synchronized CcsSqlImportCache shared(File root) {
        if (shared == null) {
            // Context supplies a trusted parent, which can use Android's /data/user alias.
            // Normalize that parent only; never resolve the ccs-sql-import child through a link.
            File normalized = root.getAbsoluteFile();
            try {
                if (root.getParentFile() != null) {
                    normalized = new File(root.getParentFile().getCanonicalFile(), root.getName());
                }
            } catch (IOException ignored) { /* constructor fails closed */ }
            shared = new CcsSqlImportCache(normalized, CcsSqlImportFile.MAX_BYTES, TOTAL_BYTES,
                LIFETIME_MS, () -> System.nanoTime() / 1_000_000L);
            CcsSqlImportTask.reapPeriodically(shared::expire);
        }
        return shared;
    }

    private final File root;
    private final boolean safeRoot;
    private final long fileLimit;
    private final long totalLimit;
    private final long lifetime;
    private final Clock clock;
    private final Set<Reservation> entries = new HashSet<>();
    private final Map<String, Reservation> ready = new HashMap<>();

    CcsSqlImportCache(File root, long fileLimit, long totalLimit, long lifetime, Clock clock) {
        this.root = root.getAbsoluteFile();
        boolean safe = false;
        try { safe = this.root.equals(this.root.getCanonicalFile()); }
        catch (IOException ignored) { /* reject copy without breaking the rest of the CCS page */ }
        this.safeRoot = safe;
        this.fileLimit = fileLimit;
        this.totalLimit = totalLimit;
        this.lifetime = lifetime;
        this.clock = clock;
    }

    final class Reservation {
        private final Object owner;
        private File file;
        private long bytes;
        private long expires;
        private int pins;
        private boolean complete;
        private boolean released;

        private Reservation(Object owner) { this.owner = owner; bytes = fileLimit; }

        File copy(java.io.InputStream input, String name, CcsSqlImportTask task) throws IOException {
            task.check();
            synchronized (CcsSqlImportCache.this) {
                if (released || complete) throw new IOException("SQL copy released");
                file = CcsSqlImportFile.create(root, name);
            }
            CcsSqlImportFile.copyTo(input, file, fileLimit, task);
            task.check();
            synchronized (CcsSqlImportCache.this) {
                complete = true;
                bytes = file.length();
                expires = clock.now() + lifetime;
                if (released) {
                    removeReleased();
                    return null;
                }
                ready.put(file.getAbsolutePath(), this);
                return file;
            }
        }

        void abort() {
            synchronized (CcsSqlImportCache.this) {
                complete = true;
                released = true;
                // A failed deletion keeps its bytes and ownership for a later retry.
                bytes = file == null ? 0 : file.length();
                removeReleased();
            }
        }
    }

    synchronized Reservation reserve(Object owner) throws IOException {
        if (!safeRoot) {
            throw new CcsSqlImportFile.InvalidFileException("SQL 缓存目录不安全，已拒绝复制");
        }
        expire();
        long bytes = 0;
        Set<String> known = new HashSet<>();
        for (Reservation entry : entries) {
            bytes += entry.file == null ? entry.bytes : Math.max(entry.bytes, entry.file.length());
            if (entry.file != null) known.add(entry.file.getAbsolutePath());
        }
        long[] unknown = new long[3]; // bytes, files, visited nodes (bound even empty/orphan directories)
        scan(root, known, unknown, 0);
        if (entries.size() + unknown[1] >= MAX_ENTRIES
            || unknown[0] > totalLimit || bytes > totalLimit - unknown[0]
            || fileLimit > totalLimit - unknown[0] - bytes) {
            throw new CcsSqlImportFile.InvalidFileException("SQL 导入缓存已满（总量上限 128 MiB），请释放所选备份或等待导入结束");
        }
        Reservation reservation = new Reservation(owner);
        entries.add(reservation);
        return reservation;
    }

    private void scan(File file, Set<String> known, long[] usage, int depth) throws IOException {
        if (!file.exists()) return;
        if (++usage[2] > 256 || depth > 4
            || !file.getAbsolutePath().equals(file.getCanonicalPath())) {
            throw new CcsSqlImportFile.InvalidFileException("SQL 导入缓存无法安全计量，已拒绝新的复制");
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new CcsSqlImportFile.InvalidFileException("SQL 导入缓存无法计量，已拒绝新的复制");
            for (File child : children) scan(child, known, usage, depth + 1);
        } else if (!known.contains(file.getAbsolutePath())) {
            long length = file.length();
            if (length > totalLimit - usage[0]) usage[0] = totalLimit + 1;
            else usage[0] += length;
            usage[1]++;
        }
    }

    synchronized boolean begin(String path, Object owner) {
        Reservation entry = ready.get(path);
        if (entry == null || entry.owner != owner) return false;
        if (clock.now() >= entry.expires) entry.released = true;
        if (entry.released || !CcsSqlImportFile.ownedLocation(entry.file)
            || !entry.file.isFile() || entry.file.length() != entry.bytes) {
            removeReleased();
            return false;
        }
        entry.pins++;
        return true;
    }

    synchronized void end(String path) {
        Reservation entry = ready.get(path);
        if (entry == null || entry.pins == 0) return;
        entry.pins--;
        if (clock.now() >= entry.expires) entry.released = true;
        removeReleased();
    }

    synchronized void release(String path) {
        Reservation entry = ready.get(path);
        if (entry != null) entry.released = true;
        removeReleased();
    }

    synchronized void releaseOwner(Object owner) {
        for (Reservation entry : entries) if (entry.owner == owner) entry.released = true;
        removeReleased();
    }

    synchronized void expire() {
        long now = clock.now();
        for (Reservation entry : entries) {
            if (entry.complete && now >= entry.expires) entry.released = true;
        }
        removeReleased();
    }

    private void removeReleased() {
        Iterator<Reservation> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Reservation entry = iterator.next();
            // Copy reservations are held until the worker really finishes, even after cancellation.
            if (!entry.released || !entry.complete || entry.pins != 0) continue;
            if (entry.file != null && !CcsSqlImportFile.discard(entry.file)) continue;
            if (entry.file != null) ready.remove(entry.file.getAbsolutePath());
            iterator.remove();
        }
    }
}

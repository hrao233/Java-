package edu.files;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

final class DirectoryEvents {
    static final Duration QUIET = Duration.ofMillis(160);
    static final Duration MAX_WAIT = Duration.ofMillis(500);
    private static final int MAX_PATHS = 128;

    record Batch(Set<Path> entries, boolean full, long observedAt) {
        Batch { entries = Set.copyOf(entries); }

        BrowseImpact impact(Path directory) { return new BrowseImpact(Set.of(directory), entries); }

        boolean coveredBy(long readQueuedAt) { return readQueuedAt - observedAt >= 0; }

        Batch merge(Batch newer) {
            var paths = new HashSet<>(entries);
            paths.addAll(newer.entries);
            boolean all = full || newer.full || paths.size() > MAX_PATHS;
            return new Batch(all ? Set.of() : paths, all, Math.max(observedAt, newer.observedAt));
        }
    }

    private final Set<Path> entries = new HashSet<>();
    private boolean pending;
    private boolean full;
    private long first;
    private long last;

    void record(Path entry, long now) {
        if (!pending) { first = now; }
        pending = true;
        last = now;
        if (entry == null || (!full && entries.add(entry) && entries.size() > MAX_PATHS)) {
            full = true;
            entries.clear();
        }
    }

    long waitNanos(long now, long idle) {
        if (!pending) { return idle; }
        long remaining = Math.min(QUIET.toNanos() - (now - last), MAX_WAIT.toNanos() - (now - first));
        return Math.max(0, Math.min(idle, remaining));
    }

    Batch take(long now) {
        if (!pending || waitNanos(now, Long.MAX_VALUE) > 0) { return null; }
        var batch = new Batch(entries, full, last);
        clear();
        return batch;
    }

    void clear() {
        pending = false;
        full = false;
        entries.clear();
    }
}

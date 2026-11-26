package edu.files;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static edu.files.FileChanges.State.*;

final class FileOperationTask {
    enum Kind { COPY, MOVE, RENAME, RECYCLE, UNDO, OTHER }
    enum Phase { WAITING, PREPARING, EXECUTING, COPYING, ATTRIBUTES, PUBLISHING, CLEANUP, FINISHED }
    enum Result { COMPLETED, PARTIAL, CANCELLED, FAILED, UNKNOWN }

    record Issue(Path path, String detail, IOException cause) { }

    record Snapshot(UUID id, Kind kind, Phase phase, Result result, Instant started, Duration elapsed,
                    Path currentPath, Path target, int itemCount, int processed, int applied, int failed,
                    int unknown, int cancelled, long totalBytes, long transferredBytes, long publishedBytes,
                    double bytesPerSecond, Duration remaining) { }

    static final class Cancelled extends IOException {
        Cancelled() { super("用户已请求取消"); }
    }

    private static final long SAMPLE_INTERVAL = 100_000_000;
    private static final long SPEED_INTERVAL = 250_000_000;

    private final UUID id = UUID.randomUUID();
    private final Kind kind;
    private final List<FileChanges.Change> changes;
    private final Consumer<Snapshot> observer;
    private final List<Issue> issues = new ArrayList<>();
    private ActiveStaging staging = new ActiveStaging();
    private volatile Snapshot snapshot;
    private volatile boolean cancellationRequested;
    // The disk worker owns the fields below once run() starts.
    private Instant started;
    private long startNanos;
    private Phase phase = Phase.WAITING;
    private Path currentPath;
    private Path target;
    private int processed;
    private int applied;
    private int failed;
    private int unknown;
    private int cancelled;
    private long totalBytes;
    private long transferred;
    private long published;
    private long itemStartBytes;
    private long plannedItemBytes;
    private long lastSample;
    private long speedSample;
    private long speedBytes;
    private double speed;
    private Result result;

    FileOperationTask(Kind kind, List<FileChanges.Change> changes) { this(kind, changes, ignored -> {}); }

    FileOperationTask(Kind kind, List<FileChanges.Change> changes, Consumer<Snapshot> observer) {
        this.kind = kind;
        this.changes = List.copyOf(changes);
        this.observer = observer;
        totalBytes = totalBytes(kind, this.changes);
        snapshot = makeSnapshot(0);
    }

    FileChanges.Outcome run() {
        synchronized (this) {
            if (started != null) { throw new IllegalStateException("Task has already run"); }
            started = Instant.now();
            startNanos = System.nanoTime();
            speedSample = startNanos;
        }
        var outcome = FileChanges.execute(changes, this);
        result = finishResult(outcome);
        phase(Phase.FINISHED);
        return new FileChanges.Outcome(outcome.items(), issues);
    }

    Snapshot snapshot() { return snapshot; }

    void observeStagingWith(ActiveStaging visibleToReaders) { staging = visibleToReaders; }

    ActiveStaging staging() { return staging; }
    Duration elapsed() {
        Snapshot current = snapshot;
        return current.started() != null && current.result() == null
                ? Duration.ofNanos(System.nanoTime() - startNanos) : current.elapsed();
    }

    boolean cancellationRequested() { return cancellationRequested; }

    synchronized void requestCancellation() {
        if (snapshot.result() == null) { cancellationRequested = true; }
    }

    void checkCancellation() throws Cancelled {
        if (cancellationRequested) { throw new Cancelled(); }
    }

    void beforeCommit(Phase committing) throws Cancelled {
        Snapshot boundary;
        synchronized (this) {
            checkCancellation();
            // Accept submission and cancellation in a definite order.
            phase = committing;
            boundary = makeSnapshot(System.nanoTime());
            snapshot = boundary;
        }
        observer.accept(boundary);
    }

    void startItem(FileChanges.Change change) throws Cancelled {
        checkCancellation();
        currentPath = change.path();
        target = change.evidence() == null ? null : change.evidence().target();
        itemStartBytes = transferred;
        plannedItemBytes = change.copyBytes();
        phase(Phase.PREPARING);
    }

    void phase(Phase next) {
        phase = next;
        update(true);
    }

    void copying(Path path) {
        currentFile(path, Phase.COPYING);
    }

    void preparing(Path path) {
        currentFile(path, Phase.PREPARING);
    }

    private void currentFile(Path path, Phase next) {
        currentPath = path;
        if (phase == next) { update(false); }
        else { phase(next); }
    }

    void reportSize(long actual) {
        if (totalBytes >= 0 && plannedItemBytes != actual) {
            totalBytes = -1;
            update(true);
        }
    }

    void unknownSize() {
        if (totalBytes >= 0) {
            totalBytes = -1;
            update(true);
        }
    }

    void transfer(long delta) {
        if (delta <= 0) { return; }
        boolean firstBytes = transferred == 0;
        transferred += delta;
        update(firstBytes);
    }

    void issue(Path path, String detail, IOException error) {
        issues.add(new Issue(path, detail, error));
    }

    void finishedItem(FileChanges.State state) {
        if (state == NOT_RUN) { return; }
        processed++;
        switch (state) {
            case APPLIED -> {
                applied++;
                published += transferred - itemStartBytes;
            }
            case FAILED, REFUSED -> failed++;
            case UNKNOWN -> unknown++;
            case CANCELLED -> cancelled++;
            default -> { }
        }
        update(false);
    }

    private Result finishResult(FileChanges.Outcome outcome) {
        if (unknown > 0) { return Result.UNKNOWN; }
        if (failed > 0) { return applied > 0 ? Result.PARTIAL : Result.FAILED; }
        if (cancelled > 0 || outcome.count(NOT_RUN) > 0) {
            return applied > 0 ? Result.PARTIAL : Result.CANCELLED;
        }
        return Result.COMPLETED;
    }

    private void update(boolean immediate) {
        long now = System.nanoTime();
        if (!immediate && now - lastSample < SAMPLE_INTERVAL) { return; }
        long interval = now - speedSample;
        if (interval >= SPEED_INTERVAL) {
            double measured = (transferred - speedBytes) * 1_000_000_000.0 / interval;
            speed = speed == 0 ? measured : 0.35 * measured + 0.65 * speed;
            speedBytes = transferred;
            speedSample = now;
        }
        lastSample = now;
        snapshot = makeSnapshot(now);
        observer.accept(snapshot);
    }

    private Snapshot makeSnapshot(long now) {
        Duration elapsed = started == null ? Duration.ZERO : Duration.ofNanos(now - startNanos);
        double rate = phase == Phase.COPYING ? speed : 0;
        Duration remaining = totalBytes >= transferred && rate >= 1024
                ? Duration.ofSeconds((long) Math.ceil((totalBytes - transferred) / rate)) : null;
        return new Snapshot(id, kind, phase, result, started, elapsed, currentPath, target, changes.size(),
                processed, applied, failed, unknown, cancelled, totalBytes, transferred, published, rate, remaining);
    }

    private static long totalBytes(Kind kind, List<FileChanges.Change> changes) {
        if (kind != Kind.COPY) { return -1; }
        long total = 0;
        for (var change : changes) {
            long size = change.copyBytes();
            if (size < 0 || Long.MAX_VALUE - total < size) { return -1; }
            total += size;
        }
        return total;
    }
}

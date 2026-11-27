package edu.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static edu.files.FileChanges.State.*;
import static edu.files.FileOperationTask.Kind.*;
import static edu.files.FileOperationTask.Phase.*;
import static edu.files.TransferConflict.Strategy.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TransferConflictTest {
    @TempDir Path root;
    private Path from;
    private Path to;

    @BeforeEach
    void createDirectories() throws IOException {
        from = Files.createDirectory(root.resolve("来源"));
        to = Files.createDirectory(root.resolve("目标"));
    }

    @Test
    void skippingTheMiddleOfACopyBatchPreservesBothObjectsAndContinues() throws IOException {
        Path first = Files.writeString(from.resolve("a.txt"), "first");
        Path conflict = Files.writeString(from.resolve("b.txt"), "source");
        Path third = Files.writeString(from.resolve("c.txt"), "third");
        Path occupant = Files.writeString(to.resolve("b.txt"), "occupant");
        var task = task(COPY, List.of(change(first, false), change(conflict, false), change(third, false)), (worker, sample) -> {
            if (sample.phase() != WAITING_FOR_CONFLICT) { return; }
            var request = worker.pendingConflict();
            assertEquals(TransferConflict.Stage.COPY_CHECK, request.stage());
            assertEquals(conflict, request.source().path());
            assertEquals(occupant, request.target());
            assertEquals(8L, request.occupant().entry().size());
            assertTrue(worker.decideConflict(request, SKIP, false));
            assertFalse(worker.decideConflict(request, SKIP, false));
        });

        var outcome = task.run();
        assertStates(outcome, APPLIED, SKIPPED, APPLIED);
        assertEquals("已复制 2 项 · 已跳过 1 项", outcome.summary("已复制"));
        assertNull(outcome.error());
        assertEquals(1, task.snapshot().skipped());
        assertEquals(3, task.snapshot().processed());
        assertEquals(List.of(first, conflict, third), outcome.remainingCopy(List.of(first, conflict, third)));
        assertEquals("first", Files.readString(to.resolve("a.txt")));
        assertEquals("third", Files.readString(to.resolve("c.txt")));
        assertEquals("occupant", Files.readString(occupant));
        assertEquals("source", Files.readString(conflict));
        assertNoStaging();
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "报告.txt", "包.tar.gz", ".env"})
    void keepsBothUsingTheSharedCopyNameRule(String name) throws IOException {
        requireNative();
        Path source = Files.writeString(from.resolve(name), "origin");
        Path occupant = Files.writeString(to.resolve(name), "existing");
        int dot = name.lastIndexOf('.');
        String expected = dot > 0 ? name.substring(0, dot) + " - 副本" + name.substring(dot) : name + " - 副本";
        var outcome = choose(COPY, change(source, false), KEEP_BOTH).run();

        assertStates(outcome, APPLIED);
        assertEquals(to.resolve(expected), outcome.items().getFirst().receipt().target());
        assertEquals("origin", Files.readString(source));
        assertEquals("origin", Files.readString(to.resolve(expected)));
        assertEquals("existing", Files.readString(occupant));
        assertNoStaging();
    }

    @Test
    void keepsTwoCompleteDirectoriesWithoutCombiningTheirTrees() throws IOException {
        requireNative();
        Path source = Files.createDirectories(from.resolve("资料.v1/深层")).getParent();
        Files.writeString(source.resolve("深层/a.txt"), "source tree");
        Path occupant = Files.createDirectory(to.resolve("资料.v1"));
        Files.writeString(occupant.resolve("b.txt"), "target tree");
        var outcome = choose(COPY, change(source, false), KEEP_BOTH).run();
        Path published = to.resolve("资料.v1 - 副本");

        assertStates(outcome, APPLIED);
        assertEquals(published, outcome.items().getFirst().receipt().target());
        assertEquals("source tree", Files.readString(published.resolve("深层/a.txt")));
        assertEquals("target tree", Files.readString(occupant.resolve("b.txt")));
        assertFalse(Files.exists(occupant.resolve("深层")));
        assertFalse(Files.exists(published.resolve("b.txt")));
        assertNoStaging();
    }

    @Test
    void fileAndDirectoryNamesCanConflictInACopyOrMove() throws IOException {
        requireNative();
        Path directory = Files.createDirectory(from.resolve("混合.txt"));
        Files.writeString(directory.resolve("child.txt"), "nested");
        Path targetFile = Files.writeString(to.resolve("混合.txt"), "file");
        var copied = choose(COPY, change(directory, false), KEEP_BOTH).run();
        assertStates(copied, APPLIED);
        assertEquals("nested", Files.readString(to.resolve("混合.txt - 副本/child.txt")));
        assertEquals("file", Files.readString(targetFile));

        Path file = Files.writeString(from.resolve("文档.txt"), "moved file");
        Path targetDirectory = Files.createDirectory(to.resolve("文档.txt"));
        Files.writeString(targetDirectory.resolve("child.txt"), "existing directory");
        var moved = choose(MOVE, change(file, true), KEEP_BOTH).run();
        assertStates(moved, APPLIED);
        assertEquals("moved file", Files.readString(to.resolve("文档 - 副本.txt")));
        assertEquals("existing directory", Files.readString(targetDirectory.resolve("child.txt")));
        assertFalse(Files.exists(file));
    }

    @Test
    void anExistingHardLinkStillConflictsWithTheRequestedDirectoryEntry() throws IOException {
        requireNative();
        Path source = Files.writeString(from.resolve("linked.txt"), "linked bytes");
        Path occupant = Files.createLink(to.resolve("linked.txt"), source);
        var outcome = choose(MOVE, change(source, true), SKIP).run();
        assertStates(outcome, SKIPPED);
        assertTrue(Files.isSameFile(source, occupant));
        assertEquals("linked bytes", Files.readString(occupant));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anOccupiedMoveCandidateIsRetriedAndUndoUsesTheActualNameAndObject(boolean hardLink) throws IOException {
        requireNative();
        Path source = Files.writeString(from.resolve("档案.txt"), "original");
        var before = selection(source);
        Path occupant = Files.writeString(to.resolve("档案.txt"), "occupant");
        Path competing = to.resolve("档案 - 副本.txt");
        Path actual = to.resolve("档案 - 副本 (2).txt");
        var submissions = new AtomicInteger();
        var task = task(MOVE, List.of(change(source, true)), (worker, sample) -> {
            if (sample.phase() == WAITING_FOR_CONFLICT) {
                var request = worker.pendingConflict();
                assertEquals(TransferConflict.Stage.MOVE, request.stage());
                assertNotNull(request.occupant().nativeItem());
                assertEquals(before.nativeItem(), request.source().nativeItem());
                assertTrue(worker.decideConflict(request, KEEP_BOTH, false));
            }
            if (sample.phase() == EXECUTING && submissions.incrementAndGet() == 2) {
                io(() -> {
                    if (hardLink) { Files.createLink(competing, source); }
                    else { Files.writeString(competing, "won the name"); }
                });
            }
        });

        var outcome = task.run();
        assertStates(outcome, APPLIED);
        assertEquals(3, submissions.get());
        var receipt = outcome.items().getFirst().receipt();
        assertEquals(actual, receipt.target());
        assertEquals(before.nativeItem().id(), receipt.identity().id());
        assertFalse(Files.exists(source));
        assertEquals(hardLink, Files.isSameFile(actual, competing));
        assertTrue(BrowseImpact.from(MOVE, outcome).affectsEntry(actual));
        Files.writeString(actual, "latest content");
        Files.writeString(source, "new object at the original name");
        assertStates(FileChanges.execute(outcome.reversals()), FAILED);
        assertEquals("latest content", Files.readString(actual));
        assertEquals("new object at the original name", Files.readString(source));
        Files.move(source, from.resolve("preserved.txt"));
        assertStates(FileChanges.execute(outcome.reversals()), APPLIED);
        assertEquals("latest content", Files.readString(source));
        assertEquals("occupant", Files.readString(occupant));
        assertEquals(hardLink ? "latest content" : "won the name", Files.readString(competing));
        assertEquals("new object at the original name", Files.readString(from.resolve("preserved.txt")));
        assertFalse(Files.exists(actual));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void replacingTheSourceOrDestinationDirectoryWhileWaitingRejectsTheOldRequest(boolean replaceSource)
            throws IOException {
        requireNative();
        Path source = Files.writeString(from.resolve("a.txt"), "original");
        Path occupant = Files.writeString(to.resolve("a.txt"), "existing");
        Path saved = root.resolve("保存");
        var task = task(COPY, List.of(change(source, false)), (worker, sample) -> {
            if (sample.phase() != WAITING_FOR_CONFLICT) { return; }
            io(() -> {
                if (replaceSource) {
                    Files.move(source, saved);
                    Files.writeString(source, "replacement");
                } else {
                    Files.move(to, saved);
                    Files.createDirectory(to);
                    Files.writeString(occupant, "replacement directory");
                }
            });
            assertTrue(worker.decideConflict(worker.pendingConflict(), KEEP_BOTH, false));
        });
        var outcome = task.run();

        assertStates(outcome, FAILED);
        assertTrue(outcome.error().getMessage().contains("变化"));
        assertEquals("original", Files.readString(replaceSource ? saved : source));
        assertEquals("existing", Files.readString(replaceSource ? occupant : saved.resolve("a.txt")));
        assertEquals(replaceSource ? "replacement" : "replacement directory",
                Files.readString(replaceSource ? source : occupant));
        assertFalse(Files.exists(to.resolve("a - 副本.txt")));
        assertNoStaging();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void keepBothUsesASiblingEvenIfTheOriginalConflictDisappearsOrChanges(boolean replaceOccupant) throws IOException {
        requireNative();
        Path source = Files.writeString(from.resolve("a.txt"), "original");
        Path occupant = Files.writeString(to.resolve("a.txt"), "existing");
        Path saved = root.resolve("old.txt");
        var task = task(MOVE, List.of(change(source, true)), (worker, sample) -> {
            if (sample.phase() != WAITING_FOR_CONFLICT) { return; }
            io(() -> {
                Files.move(occupant, saved);
                if (replaceOccupant) { Files.writeString(occupant, "successor"); }
            });
            assertTrue(worker.decideConflict(worker.pendingConflict(), KEEP_BOTH, false));
        });

        var outcome = task.run();
        Path published = to.resolve("a - 副本.txt");
        assertStates(outcome, APPLIED);
        assertEquals(published, outcome.items().getFirst().receipt().target());
        assertEquals("original", Files.readString(published));
        assertEquals("existing", Files.readString(saved));
        if (replaceOccupant) { assertEquals("successor", Files.readString(occupant)); }
        else { assertFalse(Files.exists(occupant)); }
    }

    @Test
    void lateCopyConflictCleansBeforeWaitingAndOnlyCopiesOnceOnTheNewAttempt() throws IOException {
        requireNative();
        byte[] original = new byte[2 * 1024 * 1024];
        byte[] latest = original.clone();
        Arrays.fill(latest, (byte) 17);
        Path source = Files.write(from.resolve("late.bin"), original);
        Path occupant = to.resolve("late.bin");
        Path competing = to.resolve("late - 副本.bin");
        Path published = to.resolve("late - 副本 (2).bin");
        var submissions = new AtomicInteger();
        long[] timing = new long[2];
        var task = task(COPY, List.of(change(source, false)), (worker, sample) -> {
            if (sample.phase() == PUBLISHING) {
                int submission = submissions.incrementAndGet();
                if (submission == 1) { io(() -> Files.writeString(occupant, "late occupant")); }
                if (submission == 2) {
                    timing[1] = System.nanoTime();
                    io(() -> Files.writeString(competing, "late sibling"));
                }
            }
            if (sample.phase() == WAITING_FOR_CONFLICT) {
                var request = worker.pendingConflict();
                assertEquals(TransferConflict.Stage.COPY_PUBLISH, request.stage());
                assertEquals(original.length, sample.transferredBytes());
                assertEquals(0, sample.publishedBytes());
                io(() -> {
                    assertNoStaging();
                    Files.write(source, latest);
                });
                timing[0] = System.nanoTime();
                assertTrue(worker.decideConflict(request, KEEP_BOTH, false));
            }
        });

        var outcome = task.run();
        assertStates(outcome, APPLIED);
        assertEquals(3, submissions.get());
        assertEquals(latest.length * 2L, task.snapshot().transferredBytes());
        assertEquals(latest.length * 2L, task.snapshot().totalBytes());
        assertEquals(latest.length, task.snapshot().publishedBytes());
        assertEquals(published, outcome.items().getFirst().receipt().target());
        assertArrayEquals(latest, Files.readAllBytes(published));
        assertEquals("late occupant", Files.readString(occupant));
        assertEquals("late sibling", Files.readString(competing));
        assertNoStaging();
        System.out.printf("PHASE27 late copy retry to commit %.2f ms%n", (timing[1] - timing[0]) / 1_000_000.0);
    }

    @Test
    void cancellationWakesTheWaitingWorkerAndPreservesTheAppliedPrefix() throws Exception {
        Path first = Files.writeString(from.resolve("a.txt"), "first");
        Path blocked = Files.writeString(from.resolve("b.txt"), "source");
        Path last = Files.writeString(from.resolve("c.txt"), "last");
        Path occupant = Files.writeString(to.resolve("b.txt"), "existing");
        var entered = new CountDownLatch(1);
        var task = task(COPY, List.of(change(first, false), change(blocked, false), change(last, false)),
                (worker, sample) -> { if (sample.phase() == WAITING_FOR_CONFLICT) { entered.countDown(); } });
        var work = new FutureTask<>(task::run);
        Thread.ofPlatform().daemon().start(work);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(work.isDone());
            long requested = System.nanoTime();
            task.requestCancellation();
            var outcome = work.get(5, TimeUnit.SECONDS);
            System.out.printf("PHASE27 cancel to worker outcome %.2f ms%n", (System.nanoTime() - requested) / 1_000_000.0);
            assertStates(outcome, APPLIED, CANCELLED, NOT_RUN);
            assertNull(task.pendingConflict());
            assertEquals(FileOperationTask.Result.PARTIAL, task.snapshot().result());
            assertEquals("first", Files.readString(to.resolve("a.txt")));
            assertEquals("source", Files.readString(blocked));
            assertEquals("existing", Files.readString(occupant));
            assertFalse(Files.exists(to.resolve("c.txt")));
            assertNoStaging();
        } finally {
            task.requestCancellation();
        }
    }

    @Test
    void batchPoliciesMatchBothTypesAndExpireWithTheTask() throws IOException {
        requireNative();
        var changes = new ArrayList<FileChanges.Change>();
        for (String name : List.of("a.txt", "b.txt", "c.txt", "d.txt", "e.txt")) {
            Path source = Files.writeString(from.resolve(name), "source " + name);
            if (name.startsWith("c") || name.startsWith("e")) { Files.createDirectory(to.resolve(name)); }
            else { Files.writeString(to.resolve(name), "occupant " + name); }
            changes.add(change(source, false));
        }
        var prompted = new ArrayList<String>();
        var task = task(COPY, changes, (worker, sample) -> {
            if (sample.phase() != WAITING_FOR_CONFLICT) { return; }
            var request = worker.pendingConflict();
            String name = request.target().getFileName().toString();
            prompted.add(name);
            assertTrue(worker.decideConflict(request, name.equals("a.txt") ? KEEP_BOTH : SKIP, true));
        });
        assertStates(task.run(), APPLIED, APPLIED, SKIPPED, APPLIED, SKIPPED);
        assertEquals(List.of("a.txt", "c.txt"), prompted);
        assertEquals("source b.txt", Files.readString(to.resolve("b - 副本.txt")));
        assertEquals("source d.txt", Files.readString(to.resolve("d - 副本.txt")));
        assertEquals("occupant b.txt", Files.readString(to.resolve("b.txt")));
        assertEquals("source c.txt", Files.readString(from.resolve("c.txt")));
        assertTrue(Files.isDirectory(to.resolve("c.txt")));
        assertTrue(Files.isDirectory(to.resolve("e.txt")));
        assertFalse(Files.exists(to.resolve("e - 副本.txt")));
        assertStates(choose(COPY, changes.getFirst(), SKIP).run(), SKIPPED);
        assertFalse(Files.exists(to.resolve("a - 副本 (2).txt")));
    }

    @Test
    void skippedCutStaysPendingWhileUnknownWriteLeavesTheQueue() throws IOException {
        requireNative();
        Path first = Files.writeString(from.resolve("a.txt"), "first");
        Path skipped = Files.writeString(from.resolve("b.txt"), "skipped");
        Path lost = Files.writeString(from.resolve("c.txt"), "unknown");
        Path last = Files.writeString(from.resolve("d.txt"), "untouched");
        Files.writeString(to.resolve("b.txt"), "occupant");
        var actualChange = change(lost, true);
        var returnLost = new FileChanges.Change(lost, actualChange.folders(), actualChange.evidence(), -1, worker -> {
            var effect = actualChange.action().run(worker);
            assertEquals(APPLIED, effect.state());
            throw LocalFiles.uncertain("夹具已写入但回执丢失", new IOException("fixture"), lost, effect.receipt().target());
        });
        var task = task(MOVE, List.of(change(first, true), change(skipped, true), returnLost, change(last, true)),
                (worker, sample) -> {
                    if (sample.phase() == WAITING_FOR_CONFLICT) {
                        assertTrue(worker.decideConflict(worker.pendingConflict(), SKIP, false));
                    }
                });

        var outcome = task.run();
        assertStates(outcome, APPLIED, SKIPPED, UNKNOWN, NOT_RUN);
        assertEquals(List.of(skipped, last), outcome.remainingCut(List.of(first, skipped, lost, last)));
        assertEquals(List.of(lost, to.resolve("c.txt")), outcome.unknownOperations("移动").getFirst().paths());
        assertStates(FileChanges.execute(outcome.reversals()), APPLIED);
        assertEquals("first", Files.readString(first));
        assertEquals("skipped", Files.readString(skipped));
        assertEquals("unknown", Files.readString(to.resolve("c.txt")));
        assertEquals("occupant", Files.readString(to.resolve("b.txt")));
        assertEquals("untouched", Files.readString(last));
        assertFalse(Files.exists(lost));
    }

    @Test
    void aSharingFailureDoesNotBecomeAConflictJustBecauseTheNameIsOccupied() throws IOException {
        requireNative();
        Path source = Files.writeString(from.resolve("a.txt"), "source");
        Path occupant = Files.writeString(to.resolve("a.txt"), "existing");
        var task = task(MOVE, List.of(change(source, true)), (worker, sample) -> {
            assertNotEquals(WAITING_FOR_CONFLICT, sample.phase());
        });
        try (var held = Win32FileHandles.open(source, Win32FileHandles.READ_DATA, Win32FileHandles.SHARE_READ_WRITE)) {
            var outcome = task.run();
            assertStates(outcome, FAILED);
            assertInstanceOf(AccessDeniedException.class, outcome.error());
        }
        assertEquals("source", Files.readString(source));
        assertEquals("existing", Files.readString(occupant));
    }

    @Test
    void lackingTheNativeCommitStillAllowsSafeSkip() throws IOException {
        Path source = Files.writeString(from.resolve("a.txt"), "source");
        Path occupant = Files.writeString(to.resolve("a.txt"), "existing");
        var portable = FileChanges.transfer(LocalFiles.describe(source), to, false, true);
        var task = task(COPY, List.of(portable), (worker, sample) -> {
            if (sample.phase() != WAITING_FOR_CONFLICT) { return; }
            var request = worker.pendingConflict();
            assertFalse(request.canKeepBoth());
            assertFalse(worker.decideConflict(request, KEEP_BOTH, true));
            assertTrue(worker.decideConflict(request, SKIP, true));
        });
        assertStates(task.run(), SKIPPED);
        assertEquals("source", Files.readString(source));
        assertEquals("existing", Files.readString(occupant));
        assertNoStaging();
    }

    private FileChanges.Selection selection(Path file) throws IOException {
        return FileChanges.capture(LocalFiles.describe(file));
    }

    private FileChanges.Change change(Path file, boolean move) throws IOException {
        var source = selection(file);
        var target = FileChanges.destination(to);
        return FileChanges.transfer(source, target, move, source.needsPathConfirmation(target, move));
    }

    private void requireNative() throws IOException {
        assumeTrue(NtfsFileOperations.captureDirectory(from) != null && NtfsFileOperations.captureDirectory(to) != null,
                "requires local Windows x64 NTFS");
    }

    private static FileOperationTask choose(FileOperationTask.Kind kind, FileChanges.Change change,
                                            TransferConflict.Strategy strategy) {
        var prompts = new AtomicInteger();
        return task(kind, List.of(change), (worker, sample) -> {
            if (sample.phase() == WAITING_FOR_CONFLICT) {
                prompts.incrementAndGet();
                assertTrue(worker.decideConflict(worker.pendingConflict(), strategy, false));
            } else if (sample.phase() == FINISHED) {
                assertEquals(1, prompts.get());
            }
        });
    }

    private static FileOperationTask task(FileOperationTask.Kind kind, List<FileChanges.Change> changes,
                                          BiConsumer<FileOperationTask, FileOperationTask.Snapshot> observe) {
        var running = new AtomicReference<FileOperationTask>();
        var task = new FileOperationTask(kind, changes, sample -> observe.accept(running.get(), sample));
        running.set(task);
        task.enableConflictResolution();
        return task;
    }

    private void assertNoStaging() throws IOException {
        try (var paths = Files.list(to)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".file-manager-")));
        }
    }

    private static void assertStates(FileChanges.Outcome outcome, FileChanges.State... expected) {
        assertEquals(List.of(expected), outcome.items().stream().map(FileChanges.Item::state).toList(),
                () -> "Errors: " + outcome.items().stream().map(FileChanges.Item::error).toList());
    }

    @FunctionalInterface
    interface IoAction { void run() throws IOException; }

    private static void io(IoAction action) {
        try { action.run(); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}

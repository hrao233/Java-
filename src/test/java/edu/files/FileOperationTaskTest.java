package edu.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static edu.files.FileChanges.State.*;
import static edu.files.FileOperationTask.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class FileOperationTaskTest {
    @TempDir Path root;

    @Test
    void cancellationBeforeStartLeavesEveryItemUnexecuted() throws IOException {
        Path path = Files.writeString(root.resolve("first.txt"), "original");
        var item = new FileChanges.Change(path, List.of(root), () -> {
            Files.writeString(path, "changed");
            return FileChanges.Effect.written(null);
        });
        var task = new FileOperationTask(FileOperationTask.Kind.RENAME, List.of(item));
        assertEquals(WAITING, task.snapshot().phase());
        task.requestCancellation();
        var result = task.run();
        assertEquals(NOT_RUN, result.items().getFirst().state());
        assertEquals(FileOperationTask.Result.CANCELLED, task.snapshot().result());
        assertEquals("original", Files.readString(path));
        assertThrows(IllegalStateException.class, task::run);
    }

    @Test
    void cancellationDuringCommitPreservesTheAppliedPrefixAndLeavesTheRestPending() throws IOException {
        Path first = Files.writeString(root.resolve("a.txt"), "first");
        Path second = Files.writeString(root.resolve("b.txt"), "second");
        var running = new AtomicReference<FileOperationTask>();
        var changes = List.of(move(first, root.resolve("done.txt")), move(second, root.resolve("later.txt")));
        var task = new FileOperationTask(FileOperationTask.Kind.MOVE, changes, snapshot -> {
            if (snapshot.phase() == EXECUTING) { running.get().requestCancellation(); }
        });
        running.set(task);
        var outcome = task.run();
        assertEquals(List.of(APPLIED, NOT_RUN), outcome.items().stream().map(FileChanges.Item::state).toList());
        assertEquals(List.of(second), outcome.remainingCut(List.of(first, second)));
        assertEquals(FileOperationTask.Result.PARTIAL, task.snapshot().result());
        assertEquals(0, task.snapshot().transferredBytes());
        assertEquals(-1, task.snapshot().totalBytes());
        assertEquals("first", Files.readString(root.resolve("done.txt")));
        assertEquals("second", Files.readString(second));
    }

    @Test
    void byteUpdatesAreCoalescedAndTheFinalSnapshotContainsTheExactCount() {
        var samples = new ArrayList<FileOperationTask.Snapshot>();
        var action = new FileChanges.Change(root.resolve("bytes"), List.of(root), null, 10_000, task -> {
            task.copying(root.resolve("bytes"));
            for (int i = 0; i < 10_000; i++) { task.transfer(1); }
            task.beforeCommit(PUBLISHING);
            return FileChanges.Effect.written(null);
        });
        var task = new FileOperationTask(FileOperationTask.Kind.COPY, List.of(action), samples::add);
        assertEquals(APPLIED, task.run().items().getFirst().state());
        assertEquals(FINISHED, task.snapshot().phase());
        assertEquals(10_000, task.snapshot().transferredBytes());
        assertEquals(10_000, task.snapshot().publishedBytes());
        assertEquals(FileOperationTask.Result.COMPLETED, task.snapshot().result());
        assertTrue(samples.size() < 25 + task.snapshot().elapsed().toMillis() / 100);
    }

    private FileChanges.Change move(Path source, Path target) {
        return new FileChanges.Change(source, List.of(root), () -> {
            Files.move(source, target);
            return FileChanges.Effect.written(null);
        });
    }
}

package edu.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static edu.files.FileChanges.State.*;
import static edu.files.FileOperationTask.Phase.*;
import static edu.files.Win32FileHandles.READ_DATA;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class StagedCopyTest {
    @TempDir Path root;
    Path source;
    Path target;

    @BeforeEach
    void createFixtures() throws IOException {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null, "requires local Windows x64 NTFS");
        source = Files.createDirectory(root.resolve("source"));
        target = Files.createDirectory(root.resolve("target"));
    }

    @Test
    void copyReportsExactBytesAndPreservesAttributesAndAlternateData() throws IOException {
        Path first = Files.writeString(source.resolve("资料.txt"), "source data");
        Path empty = Files.createFile(source.resolve("empty.txt"));
        var sourceAttrs = Files.getFileAttributeView(first, DosFileAttributeView.class);
        sourceAttrs.setHidden(true);
        sourceAttrs.setReadOnly(true);
        Files.setLastModifiedTime(first, FileTime.from(Instant.parse("2024-04-13T10:22:12Z")));
        var alternate = Files.getFileAttributeView(first, UserDefinedFileAttributeView.class);
        byte[] stream = {1, 7, 42};
        sourceAttrs.setReadOnly(false);
        alternate.write("test-alternate", ByteBuffer.wrap(stream));
        sourceAttrs.setReadOnly(true);
        var aclView = Files.getFileAttributeView(first, AclFileAttributeView.class);
        var entries = new ArrayList<>(aclView.getAcl());
        var accessRule = AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(aclView.getOwner())
                .setPermissions(AclEntryPermission.WRITE_DATA).build();
        entries.addFirst(accessRule);
        aclView.setAcl(entries);
        var actualTime = Files.getLastModifiedTime(first);
        Path copied = target.resolve(first.getFileName());
        try {
            var task = copy(List.of(first, empty), (job, sample) -> {});
            var outcome = task.run();
            assertEquals(List.of(APPLIED, APPLIED), states(outcome), () -> String.valueOf(outcome.error()));
            assertTrue(outcome.issues().isEmpty(), () -> outcome.issues().toString());
            var finalState = task.snapshot();
            assertEquals(Files.size(first) + stream.length, finalState.transferredBytes());
            assertEquals(finalState.transferredBytes(), finalState.publishedBytes());
            assertEquals("source data", Files.readString(copied));
            var flags = Files.getFileAttributeView(copied, DosFileAttributeView.class).readAttributes();
            assertTrue(flags.isHidden());
            assertTrue(flags.isReadOnly());
            assertEquals(actualTime, flags.lastModifiedTime());
            assertEquals(Files.getOwner(first), Files.getOwner(copied));
            assertTrue(Files.getFileAttributeView(copied, AclFileAttributeView.class).getAcl().contains(accessRule));
            var bytes = ByteBuffer.allocate(stream.length);
            Files.getFileAttributeView(copied, UserDefinedFileAttributeView.class).read("test-alternate", bytes);
            assertArrayEquals(stream, bytes.array());
            assertEquals(0, Files.size(target.resolve("empty.txt")));
            assertNoStaging();
        } finally {
            sourceAttrs.setReadOnly(false);
            if (Files.exists(copied)) {
                Files.getFileAttributeView(copied, DosFileAttributeView.class).setReadOnly(false);
            }
        }
    }

    @Test
    void directoryStreamsWithUnknownTotalAndKeepsEmptyDirectories() throws IOException {
        Path folder = Files.createDirectory(source.resolve("tree"));
        Files.createDirectories(folder.resolve("empty/inner"));
        Path file = Files.writeString(folder.resolve("note.txt"), "directory bytes");
        var phases = new ArrayList<FileOperationTask.Phase>();
        var task = copy(List.of(folder), (job, sample) -> {
            assertEquals(-1, sample.totalBytes());
            phases.add(sample.phase());
        });
        assertEquals(List.of(APPLIED), states(task.run()));
        assertTrue(phases.containsAll(List.of(PREPARING, COPYING, ATTRIBUTES, PUBLISHING, CLEANUP, FINISHED)));
        assertEquals(Files.size(file), task.snapshot().transferredBytes());
        assertTrue(Files.isDirectory(target.resolve("tree/empty/inner")));
        assertEquals("directory bytes", Files.readString(target.resolve("tree/note.txt")));
        assertNoStaging();
    }

    @Test
    void fileThatGrowsAfterSelectionSwitchesToUnknownTotal() throws IOException {
        Path file = Files.writeString(source.resolve("live.txt"), "before");
        var task = copy(List.of(file), (job, sample) -> {});
        Files.writeString(file, "changed at the source before copy");
        assertEquals(List.of(APPLIED), states(task.run()));
        assertEquals(-1, task.snapshot().totalBytes());
        assertEquals(Files.size(file), task.snapshot().transferredBytes());
        assertEquals("changed at the source before copy", Files.readString(target.resolve("live.txt")));
        assertNoStaging();
    }

    @Test
    void treeCopyReadsTheContentsEncounteredDuringTraversal() throws IOException {
        Path folder = Files.createDirectory(source.resolve("live"));
        Path first = Files.writeString(folder.resolve("a.txt"), "initial a");
        Path second = Files.writeString(folder.resolve("b.txt"), "initial b");
        var changed = new AtomicReference<Path>();
        var task = copy(List.of(folder), (job, sample) -> {
            if (sample.phase() != COPYING || changed.get() != null) { return; }
            Path next = sample.currentPath().equals(first) ? second : first;
            try { Files.writeString(next, "edited while walking"); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
            changed.set(next);
        });
        assertEquals(List.of(APPLIED), states(task.run()));
        assertNotNull(changed.get());
        assertEquals(-1, task.snapshot().totalBytes());
        assertEquals("edited while walking", Files.readString(target.resolve("live").resolve(changed.get().getFileName())));
        assertNoStaging();
    }

    @Test
    void cancellationInsideNativeDataCallbackCleansOnlyItsTemporaryCopy() throws IOException {
        Path file = Files.write(source.resolve("large.bin"), new byte[24 * 1024 * 1024]);
        Path remaining = Files.writeString(source.resolve("later.txt"), "untouched");
        var task = copy(List.of(file, remaining), (job, sample) -> {
            if (sample.phase() == COPYING && sample.transferredBytes() > 0) { job.requestCancellation(); }
        });
        var outcome = task.run();
        assertEquals(List.of(CANCELLED, NOT_RUN), states(outcome), () -> String.valueOf(outcome.error()));
        assertTrue(task.snapshot().transferredBytes() < Files.size(file));
        assertTrue(task.snapshot().transferredBytes() > 0);
        assertEquals(0, task.snapshot().publishedBytes());
        assertEquals(Files.size(file), 24L * 1024 * 1024);
        assertEquals("untouched", Files.readString(remaining));
        assertFalse(Files.exists(target.resolve(file.getFileName())));
        assertTrue(outcome.issues().isEmpty(), () -> outcome.issues().toString());
        assertNoStaging();
    }

    @Test
    void cancellationAtAttributesLeavesFormalNameAvailable() throws IOException {
        Path file = Files.writeString(source.resolve("before-publish.txt"), "unchanged");
        var task = copy(List.of(file), (job, sample) -> {
            if (sample.phase() == ATTRIBUTES) { job.requestCancellation(); }
        });
        assertEquals(List.of(CANCELLED), states(task.run()));
        assertEquals(Files.size(file), task.snapshot().transferredBytes());
        assertEquals(0, task.snapshot().publishedBytes());
        assertEquals("unchanged", Files.readString(file));
        assertFalse(Files.exists(target.resolve(file.getFileName())));
        assertNoStaging();
    }

    @Test
    void cancellationOncePublishIsAcceptedOrDuringCleanupKeepsThePublishedBytes() throws IOException {
        Path first = Files.writeString(source.resolve("first.txt"), "keep");
        Path second = Files.writeString(source.resolve("then.txt"), "later");
        for (var boundary : List.of(PUBLISHING, CLEANUP)) {
            var task = copy(List.of(first, second), (job, sample) -> {
                if (sample.phase() == boundary) { job.requestCancellation(); }
            });
            var outcome = task.run();
            assertEquals(List.of(APPLIED, NOT_RUN), states(outcome), () -> String.valueOf(outcome.error()));
            assertEquals(Files.size(first), task.snapshot().publishedBytes());
            assertEquals("keep", Files.readString(target.resolve("first.txt")));
            assertEquals("keep", Files.readString(first));
            assertFalse(Files.exists(target.resolve("then.txt")));
            assertNoStaging();
            Files.delete(target.resolve("first.txt"));
        }
    }

    @Test
    void replacedStagingEntryAndAdditionalForeignFileSurviveCleanup() throws IOException {
        Path file = Files.writeString(source.resolve("note.txt"), "original");
        Path parked = root.resolve("parked.txt");
        var attacked = new AtomicReference<Path>();
        var task = copy(List.of(file), (job, sample) -> {
            if (sample.phase() != ATTRIBUTES || attacked.get() != null) { return; }
            try {
                Path stage = stagingPath();
                Path staged = stage.resolve("note.txt");
                Files.move(staged, parked);
                Files.writeString(staged, "other program");
                Files.writeString(stage.resolve("extra.txt"), "foreign");
                attacked.set(stage);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        var outcome = task.run();
        assertEquals(List.of(FAILED), states(outcome));
        assertTrue(outcome.error().getMessage().contains("身份变化"));
        Path stage = attacked.get();
        assertEquals("original", Files.readString(parked));
        assertEquals("other program", Files.readString(stage.resolve("note.txt")));
        assertEquals("foreign", Files.readString(stage.resolve("extra.txt")));
        assertEquals("original", Files.readString(file));
        assertFalse(Files.exists(target.resolve("note.txt")));
        assertTrue(outcome.issues().stream().anyMatch(issue -> issue.path().equals(stage)));
    }

    @Test
    void fileSharingFailureLeavesOriginalAndNoPublishedOrTemporaryFile() throws IOException {
        Path folder = Files.createDirectory(source.resolve("tree"));
        Path locked = Files.writeString(folder.resolve("locked.txt"), "original");
        Path neighbour = Files.writeString(folder.resolve("ok.txt"), "keep");
        try (var handle = Win32FileHandles.open(locked, READ_DATA, 0)) {
            var task = copy(List.of(folder), (job, sample) -> {});
            var result = task.run();
            assertEquals(List.of(FAILED), states(result), () -> String.valueOf(result.error()));
            assertFalse(Files.exists(target.resolve("tree")));
            assertNoStaging();
        }
        assertEquals("original", Files.readString(locked));
        assertEquals("keep", Files.readString(neighbour));
    }

    @Test
    void targetNameClaimedAtSubmissionKeepsTheOccupantAndCleansTheStagedCopy() throws IOException {
        Path file = Files.writeString(source.resolve("note.txt"), "original");
        var task = copy(List.of(file), (job, sample) -> {
            if (sample.phase() != PUBLISHING) { return; }
            try { Files.writeString(target.resolve("note.txt"), "occupant"); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        });
        assertEquals(List.of(FAILED), states(task.run()));
        assertEquals("occupant", Files.readString(target.resolve("note.txt")));
        assertEquals("original", Files.readString(file));
        assertNoStaging();
    }

    private FileOperationTask copy(List<Path> sources, BiConsumer<FileOperationTask, FileOperationTask.Snapshot> observer)
            throws IOException {
        var changes = new ArrayList<FileChanges.Change>();
        var destination = FileChanges.destination(target);
        for (Path path : sources) {
            changes.add(FileChanges.transfer(FileChanges.capture(LocalFiles.describe(path)), destination, false, false));
        }
        var running = new AtomicReference<FileOperationTask>();
        var task = new FileOperationTask(FileOperationTask.Kind.COPY, changes, sample -> observer.accept(running.get(), sample));
        running.set(task);
        return task;
    }

    private Path stagingPath() throws IOException {
        try (var paths = Files.list(target)) {
            return paths.filter(path -> path.getFileName().toString().startsWith(".file-manager-")).findFirst().orElseThrow();
        }
    }

    private void assertNoStaging() throws IOException {
        try (var paths = Files.list(target)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".file-manager-")));
        }
    }

    private static List<FileChanges.State> states(FileChanges.Outcome outcome) {
        return outcome.items().stream().map(FileChanges.Item::state).toList();
    }
}

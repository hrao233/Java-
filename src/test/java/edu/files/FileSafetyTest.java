package edu.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static edu.files.FileChanges.State.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileSafetyTest {
    @TempDir Path root;

    @Test
    void deepEditOfCopiedDirectorySurvivesUndo() throws IOException {
        Path source = Files.createDirectories(root.resolve("tree/inner"));
        Files.writeString(source.resolve("draft.txt"), "old");
        Path target = Files.createDirectory(root.resolve("target"));
        var copied = run(transfer(source.getParent(), target, false));
        assertEquals(APPLIED, state(copied));
        Path edit = Files.writeString(target.resolve("tree/inner/draft.txt"), "new");

        assertRefused(FileChanges.execute(copied.reversals()), "复制");
        assertTrue(Files.exists(edit), "undo must leave the edited tree in place");
        assertEquals("new", Files.readString(edit));
        assertEquals("old", Files.readString(source.resolve("draft.txt")));
    }

    @Test
    void sameSizeAndTimeEditOfCopiedFileSurvivesUndo() throws IOException {
        Path original = Files.writeString(root.resolve("note.txt"), "old");
        Path folder = Files.createDirectory(root.resolve("target"));
        var copied = run(transfer(original, folder, false));
        assertEquals(APPLIED, state(copied));
        Path target = folder.resolve("note.txt");
        replaceContentPreservingTime(target);

        assertRefused(FileChanges.execute(copied.reversals()), "复制");
        assertTrue(Files.exists(target), "metadata equality does not prove original content");
        assertEquals("new", Files.readString(target));
        assertEquals("old", Files.readString(original));
    }

    @Test
    void sameSizeAndTimeEditOfNewFileSurvivesUndo() throws IOException {
        var type = new NewItems.Type(NewItems.Kind.DATA, ".txt", "文本", "note",
                Base64.getEncoder().encodeToString("old".getBytes(UTF_8)));
        var created = NewItems.create(root, type);
        replaceContentPreservingTime(created.path());

        assertRefused(run(FileChanges.undoCreation(created.path())), "新建");
        assertTrue(Files.exists(created.path()), "a new file can acquire user data");
        assertEquals("new", Files.readString(created.path()));
        assertNoStagingFiles(root);
    }

    @Test
    void copiedFileWithoutEditsAlsoStaysInPlace() throws IOException {
        Path from = Files.writeString(root.resolve("note.txt"), "original");
        Path folder = Files.createDirectory(root.resolve("target"));
        var copied = run(transfer(from, folder, false));
        assertEquals(APPLIED, state(copied));
        assertRefused(FileChanges.execute(copied.reversals()), "复制");
        assertEquals("original", Files.readString(folder.resolve("note.txt")));
        assertEquals("original", Files.readString(from));
    }

    @Test
    void undoDoesNotMoveASameNameReplacement() throws IOException {
        Path source = Files.writeString(root.resolve("item.txt"), "original");
        Path folder = Files.createDirectory(root.resolve("target"));
        var original = LocalFiles.describe(source);
        var moved = run(FileChanges.transfer(original, folder, true, !original.hasIdentity()));
        assertEquals(APPLIED, state(moved));
        Path destination = folder.resolve("item.txt");
        Path elsewhere = Files.move(destination, folder.resolve("original.txt"));
        Files.writeString(destination, "replacement");

        var denied = FileChanges.execute(moved.reversals());
        assertDenied(denied, original, "项目已变化");
        assertFalse(Files.exists(source), "a replacement must not move to the original name");
        assertEquals("replacement", Files.readString(destination));
        assertEquals("original", Files.readString(elsewhere));
    }

    @Test
    void undoDoesNotRecreateDisappearedTarget() throws IOException {
        Path source = Files.writeString(root.resolve("item.txt"), "original");
        var original = LocalFiles.describe(source);
        Path folder = Files.createDirectory(root.resolve("target"));
        var moved = run(FileChanges.transfer(original, folder, true, !original.hasIdentity()));
        assertEquals(APPLIED, state(moved));
        Path movedAside = Files.move(folder.resolve("item.txt"), folder.resolve("elsewhere.txt"));

        var denied = FileChanges.execute(moved.reversals());
        assertDenied(denied, original, null);
        if (original.hasIdentity()) {
            assertInstanceOf(NoSuchFileException.class, denied.error());
            assertEquals(moved.reversals(), denied.pending());
        }
        assertFalse(Files.exists(source));
        assertFalse(Files.exists(folder.resolve("item.txt")));
        assertEquals("original", Files.readString(movedAside));
    }

    @Test
    void undoLeavesBothFilesWhenOriginalNameIsOccupied() throws IOException {
        Path source = Files.writeString(root.resolve("item.txt"), "original");
        var original = LocalFiles.describe(source);
        Path folder = Files.createDirectory(root.resolve("target"));
        var moved = run(FileChanges.transfer(original, folder, true, !original.hasIdentity()));
        assertEquals(APPLIED, state(moved));
        Files.writeString(source, "occupant");

        var denied = FileChanges.execute(moved.reversals());
        assertDenied(denied, original, null);
        if (original.hasIdentity()) {
            assertInstanceOf(FileAlreadyExistsException.class, denied.error());
        }
        assertEquals("occupant", Files.readString(source));
        assertEquals("original", Files.readString(folder.resolve("item.txt")));
    }

    @Test
    void undoesAnUnchangedMoveWhenTheProviderExposesStableKeys() throws IOException {
        Path source = Files.writeString(root.resolve("note.txt"), "retained");
        var original = LocalFiles.describe(source);
        assumeTrue(original.hasIdentity() && LocalFiles.describe(root).hasIdentity(), "提供者不公开文件身份");
        Path folder = Files.createDirectory(root.resolve("target"));
        var moved = run(FileChanges.transfer(original, folder, true, false));
        assertEquals(APPLIED, state(moved));

        var reversed = FileChanges.execute(moved.reversals());
        assertEquals(APPLIED, state(reversed));
        assertTrue(reversed.pending().isEmpty());
        assertFalse(Files.exists(folder.resolve("note.txt")));
        assertEquals("retained", Files.readString(source));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clipboardDoesNotSilentlyOperateOnReplacement(boolean move) throws IOException {
        Path source = Files.writeString(root.resolve("item.txt"), "selected");
        Path folder = Files.createDirectory(root.resolve("target"));
        var paste = transfer(source, folder, move);
        Path original = Files.move(source, root.resolve("original.txt"));
        Files.writeString(source, "different replacement");

        var outcome = run(paste);
        assertEquals(FAILED, state(outcome));
        assertTrue(outcome.error().getMessage().contains("重新选择"));
        assertFalse(Files.exists(folder.resolve("item.txt")), "clipboard must not transfer the replacement");
        assertEquals("selected", Files.readString(original));
        assertEquals("different replacement", Files.readString(source));
        assertNoStagingFiles(folder);
    }

    @Test
    void unknownIdentityCannotBeUsedWithoutConfirmation() throws IOException {
        Path source = Files.writeString(root.resolve("note.txt"), "safe");
        Path folder = Files.createDirectory(root.resolve("target"));
        var real = LocalFiles.describe(source);
        var noKey = new LocalFiles.Entry(real.path(), real.directory(), real.link(), real.size(), real.modified(),
                real.created(), null, real.other());

        var outcome = run(FileChanges.transfer(noKey, folder, false, false));
        assertEquals(FAILED, state(outcome));
        assertTrue(outcome.error().getMessage().contains("无法确认身份"));
        assertEquals("safe", Files.readString(source));
        assertFalse(Files.exists(folder.resolve("note.txt")));
    }

    @Test
    void removedSourceIsAKnownFailureBeforeCallingTheRecycleBin() throws IOException {
        Path selected = Files.writeString(root.resolve("selected.txt"), "original");
        var snapshot = LocalFiles.describe(selected);
        Path kept = Files.move(selected, root.resolve("elsewhere.txt"));
        var operation = FileChanges.recycle(snapshot, !snapshot.hasIdentity(), path -> fail("must not call the OS"));

        var outcome = run(operation);
        assertEquals(FAILED, state(outcome));
        assertInstanceOf(NoSuchFileException.class, outcome.error());
        assertEquals("original", Files.readString(kept));
    }

    @Test
    void renameAndRecycleRejectAChangedSelectionBeforeAnyWrite() throws IOException {
        Path selected = Files.writeString(root.resolve("selected.txt"), "original");
        var snapshot = LocalFiles.describe(selected);
        Path kept = Files.move(selected, root.resolve("elsewhere.txt"));
        Files.writeString(selected, "different replacement");
        Path newName = root.resolve("new name.txt");

        var rename = run(FileChanges.rename(snapshot, newName, !snapshot.hasIdentity()));
        var recycle = run(FileChanges.recycle(snapshot, !snapshot.hasIdentity(), path -> fail("must not call the OS")));

        assertEquals(FAILED, state(rename));
        assertEquals(FAILED, state(recycle));
        assertFalse(Files.exists(newName));
        assertEquals("original", Files.readString(kept));
        assertEquals("different replacement", Files.readString(selected));
    }

    @Test
    void expectedDesktopRuntimeFailureStillReturnsTheWrittenPrefix() throws IOException {
        Path a = Files.writeString(root.resolve("a.txt"), "first");
        Path b = Files.writeString(root.resolve("b.txt"), "second");
        Path c = Files.writeString(root.resolve("c.txt"), "third");
        Path bin = Files.createDirectory(root.resolve("fake-bin"));
        LocalFiles.RecycleBin fakeRecycle = path -> {
            Files.move(path, bin.resolve(path.getFileName()));
            if (path.equals(b)) { throw new IllegalArgumentException("path is no longer available"); }
            return true;
        };

        var outcome = FileChanges.execute(List.of(recycle(a, fakeRecycle), recycle(b, fakeRecycle), recycle(c, fakeRecycle)));
        assertEquals(List.of(APPLIED, UNKNOWN, NOT_RUN), states(outcome));
        assertInstanceOf(IllegalArgumentException.class, outcome.error().getCause());
        assertTrue(outcome.error().getMessage().contains("不存在"));
        assertTrue(outcome.summary("已移到回收站").contains("结果未知 1 项 · 未执行 1 项"));
        assertEquals(List.of(bin.getParent().resolve("a.txt")), outcome.reversals().stream().map(FileChanges.Change::path).toList());
        assertFalse(Files.exists(a));
        assertEquals("first", Files.readString(bin.resolve("a.txt")));
        assertEquals("second", Files.readString(bin.resolve("b.txt")));
        assertEquals("third", Files.readString(c));
        assertFalse(Files.exists(bin.resolve("c.txt")));
    }

    @Test
    void checkedConflictPreservesTheMovePrefixAndTheRemainingCutSelection() throws IOException {
        Path folder = Files.createDirectory(root.resolve("target"));
        Path a = Files.writeString(root.resolve("a.txt"), "first");
        Path b = Files.writeString(root.resolve("b.txt"), "second");
        Path c = Files.writeString(root.resolve("c.txt"), "third");
        Files.writeString(folder.resolve("b.txt"), "occupied");
        var sources = List.of(LocalFiles.describe(a), LocalFiles.describe(b), LocalFiles.describe(c));
        var changes = sources.stream().map(source -> FileChanges.transfer(source, folder, true, !source.hasIdentity())).toList();

        var outcome = FileChanges.execute(changes);
        assertEquals(List.of(APPLIED, FAILED, NOT_RUN), states(outcome));
        assertInstanceOf(FileAlreadyExistsException.class, outcome.error());
        assertEquals(List.of(sources.get(1), sources.get(2)), outcome.remainingCut(sources));
        assertEquals(List.of(changes.get(1), changes.get(2)), outcome.pending());
        assertEquals(1, outcome.reversals().size());
        assertFalse(Files.exists(a));
        assertEquals("first", Files.readString(folder.resolve("a.txt")));
        assertEquals("occupied", Files.readString(folder.resolve("b.txt")));
        assertEquals("second", Files.readString(b));
        assertEquals("third", Files.readString(c));
        assertFalse(Files.exists(folder.resolve("c.txt")));
    }

    @Test
    void uncertainCutItemIsRetainedEvenWhenAFailureFollowsAWrite() throws IOException {
        Path folder = Files.createDirectory(root.resolve("target"));
        Path file = Files.writeString(root.resolve("a.txt"), "first");
        var source = LocalFiles.describe(file);
        var change = new FileChanges.Change(file, List.of(root, folder), () -> {
            var written = Files.move(file, folder.resolve("a.txt"));
            throw new LocalFiles.UncertainWrite("结果需要检查：" + written, new IOException("provider failure"));
        });

        var outcome = run(change);
        assertEquals(UNKNOWN, state(outcome));
        assertEquals(List.of(source), outcome.remainingCut(List.of(source)));
        assertTrue(outcome.reversals().isEmpty());
        assertFalse(Files.exists(file));
        assertEquals("first", Files.readString(folder.resolve("a.txt")));
    }

    @Test
    void refusesOnlyTheUnsafeUndoAndStillRunsTheOtherReversal() throws IOException {
        Path a = Files.writeString(root.resolve("safe.txt"), "safe");
        Path newName = root.resolve("moved.txt");
        Path b = Files.writeString(root.resolve("copy.txt"), "kept");
        Path folder = Files.createDirectory(root.resolve("target"));
        var safe = new FileChanges.Change(a, List.of(root), () -> {
            Files.move(a, newName);
            return FileChanges.Effect.written(new FileChanges.Change(newName, List.of(root), () -> {
                Files.move(newName, a);
                return FileChanges.Effect.written(null);
            }));
        });
        var forward = FileChanges.execute(List.of(safe, transfer(b, folder, false)));

        var undo = FileChanges.execute(forward.reversals());
        assertEquals(List.of(REFUSED, APPLIED), states(undo));
        assertTrue(undo.summary("已撤消").contains("已撤消 1 项 · 已拒绝撤销 1 项"));
        assertTrue(undo.pending().isEmpty());
        assertEquals("safe", Files.readString(a));
        assertFalse(Files.exists(newName));
        assertEquals("kept", Files.readString(folder.resolve("copy.txt")));
    }

    @Test
    void failingReverseLeavesOtherReverseRecordsPending() throws IOException {
        Path source = Files.writeString(root.resolve("keep.txt"), "second");
        var unavailable = new FileChanges.Change(root.resolve("gone.txt"), List.of(root), () -> {
            throw new NoSuchFileException(root.resolve("gone.txt").toString());
        });
        var later = new FileChanges.Change(source, List.of(root), () -> fail("should not run after a failure"));

        var result = FileChanges.execute(List.of(unavailable, later));
        assertEquals(List.of(FAILED, NOT_RUN), states(result));
        assertEquals(List.of(unavailable, later), result.pending());
        assertEquals("second", Files.readString(source));
    }

    @Test
    void leavesNoStagingFilesWhenATreeCopyThrowsBeforePublishing() throws IOException {
        Path folder = Files.createDirectory(root.resolve("target"));
        Path source = root.resolve("missing.txt");
        assertThrows(IOException.class, () -> LocalFiles.copy(source, folder));
        assertFalse(Files.exists(folder.resolve("missing.txt")));
        assertNoStagingFiles(folder);
    }

    private static FileChanges.Change transfer(Path source, Path folder, boolean move) throws IOException {
        var entry = LocalFiles.describe(source);
        return FileChanges.transfer(entry, folder, move, !entry.hasIdentity());
    }

    private static FileChanges.Change recycle(Path source, LocalFiles.RecycleBin fake) throws IOException {
        var entry = LocalFiles.describe(source);
        return FileChanges.recycle(entry, !entry.hasIdentity(), fake);
    }

    private static FileChanges.Outcome run(FileChanges.Change change) {
        return FileChanges.execute(List.of(change));
    }

    private static FileChanges.State state(FileChanges.Outcome outcome) {
        return outcome.items().getFirst().state();
    }

    private static List<FileChanges.State> states(FileChanges.Outcome outcome) {
        return outcome.items().stream().map(FileChanges.Item::state).toList();
    }

    private static void assertRefused(FileChanges.Outcome result, String reason) {
        assertEquals(REFUSED, state(result));
        assertTrue(result.error().getMessage().contains(reason));
        assertEquals("已拒绝撤销 1 项", result.summary("已撤消"));
    }

    private static void assertDenied(FileChanges.Outcome denied, LocalFiles.Entry original, String reason) throws IOException {
        if (original.hasIdentity() && LocalFiles.describe(original.path().getParent()).hasIdentity()) {
            assertEquals(FAILED, state(denied));
            if (reason != null) {
                assertTrue(denied.error().getMessage().contains(reason));
            }
        } else {
            assertRefused(denied, "无法确认");
        }
    }

    private static void replaceContentPreservingTime(Path file) throws IOException {
        var modified = Files.getLastModifiedTime(file);
        long size = Files.size(file);
        Files.writeString(file, "new");
        Files.setLastModifiedTime(file, modified);
        assertEquals(size, Files.size(file));
        assertEquals(modified, Files.getLastModifiedTime(file));
    }

    private static void assertNoStagingFiles(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".file-manager-")));
        }
    }
}

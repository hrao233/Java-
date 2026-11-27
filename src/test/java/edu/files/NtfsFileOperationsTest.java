package edu.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static edu.files.NtfsFileOperations.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class NtfsFileOperationsTest {
    @TempDir Path root;

    @BeforeEach
    void requireNtfs() throws IOException {
        assumeTrue(captureDirectory(root) != null, "requires Windows x64 local NTFS");
    }

    @Test
    void refusesReplacementEvenWithTheSameBytesAndTimes() throws IOException {
        Path from = Files.writeString(root.resolve("document.txt"), "same bytes");
        Path newFolder = Files.createDirectory(root.resolve("target"));
        var observed = LocalFiles.describe(from);
        var selected = capture(observed);
        Path previous = Files.move(from, root.resolve("previous.txt"));
        Files.writeString(from, "same bytes");
        Files.getFileAttributeView(from, BasicFileAttributeView.class)
                .setTimes(observed.modified(), null, observed.created());

        assertEquals(observed.modified(), LocalFiles.describe(from).modified());
        assertEquals(observed.created(), LocalFiles.describe(from).created());
        assertThrows(IOException.class, () -> move(selected, captureDirectory(newFolder), "document.txt"));
        assertThrows(IOException.class, () -> copy(selected, newFolder, captureDirectory(newFolder)));
        assertEquals("same bytes", Files.readString(previous));
        assertEquals("same bytes", Files.readString(from));
        assertFalse(Files.exists(newFolder.resolve("document.txt")));
    }

    @Test
    void lateHardLinkIsRefusedOrReportsDetectedLinkLossWithoutUndo() throws IOException {
        Path source = Files.writeString(root.resolve("original.txt"), "same object");
        Path targetDir = Files.createDirectory(root.resolve("target"));
        Path target = targetDir.resolve("late.txt");
        var selected = capture(LocalFiles.describe(source));
        var destination = captureDirectory(targetDir);
        var change = new FileChanges.Change(source, List.of(root, targetDir), () -> {
            try (var directories = new PinnedDirectories();
                 var handle = Win32FileHandles.open(source, Win32FileHandles.READ_ATTRIBUTES | Win32FileHandles.DELETE_ACCESS,
                         Win32FileHandles.SHARE_READ_WRITE)) {
                var parent = directories.verify(destination);
                LocalFiles.requireAvailable(target);
                Files.createLink(target, source);
                var before = handle.info();
                try {
                    Win32FileHandles.rename(handle, parent, target.getFileName().toString());
                } catch (Win32FileHandles.Failure occupied) {
                    assertEquals(183, occupied.code());
                    throw new FileAlreadyExistsException(target.toString());
                }
                moveReceipt(selected, destination, target.getFileName().toString(), before, handle);
                return FileChanges.Effect.written(null);
            }
        });
        var outcome = FileChanges.execute(List.of(change));
        assertTrue(outcome.reversals().isEmpty());
        if (Files.exists(source)) {
            assertEquals(FileChanges.State.FAILED, outcome.items().getFirst().state());
            assertTrue(Files.isSameFile(source, target));
            System.out.println("PHASE30_LINK native name collision, source preserved");
        } else {
            assertEquals(FileChanges.State.UNKNOWN, outcome.items().getFirst().state());
            assertEquals(List.of(source, target), outcome.error() instanceof LocalFiles.UncertainWrite failure
                    ? failure.observations().stream().map(LocalFiles.Observation::path).toList() : List.of());
            System.out.println("PHASE30_LINK native fold, detected link loss isolated from undo");
        }
        assertEquals("same object", Files.readString(target));
    }

    @Test
    void sameObjectInAReplacedParentIsNotTheSelectedEntry() throws IOException {
        Path parent = Files.createDirectory(root.resolve("selected-parent"));
        Path source = Files.writeString(parent.resolve("item.txt"), "original");
        Path target = Files.createDirectory(root.resolve("target"));
        var selected = capture(LocalFiles.describe(source));
        Path old = Files.move(parent, root.resolve("previous-parent"));
        Files.createDirectory(parent);
        Path linked = Files.createLink(source, old.resolve("item.txt"));

        assertTrue(Files.isSameFile(linked, old.resolve("item.txt")));
        assertThrows(IOException.class, () -> move(selected, captureDirectory(target), "item.txt"));
        assertEquals("original", Files.readString(source));
        assertFalse(Files.exists(target.resolve("item.txt")));
    }

    @Test
    void refusesToWriteIntoAReplacedDestinationDirectory() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        var destination = captureDirectory(target);
        Path source = Files.writeString(root.resolve("item.txt"), "original");
        var item = capture(LocalFiles.describe(source));
        Path originalTarget = Files.move(target, root.resolve("previous-target"));
        Files.createDirectory(target);

        assertThrows(IOException.class, () -> move(item, destination, "item.txt"));
        assertEquals("original", Files.readString(source));
        assertFalse(Files.exists(target.resolve("item.txt")));
        assertFalse(Files.exists(originalTarget.resolve("item.txt")));
    }

    @Test
    void moveReceiptTargetsTheSameObjectAndItsLatestBytesCanBeMovedBack() throws IOException {
        Path parent = Files.createDirectory(root.resolve("original"));
        Path source = Files.writeString(parent.resolve("note.txt"), "before");
        Path target = Files.createDirectory(root.resolve("target"));
        var selected = capture(LocalFiles.describe(source));
        var moved = move(selected, captureDirectory(target), selected.name());

        assertNotNull(moved.result());
        assertEquals(target.resolve("note.txt"), moved.target());
        assertEquals(selected.id(), moved.result().id());
        Files.writeString(moved.target(), "updated by the user after moving");

        var restored = move(moved.result(), selected.parent(), selected.name());
        assertEquals(source, restored.target());
        assertEquals("updated by the user after moving", Files.readString(source));
        assertFalse(Files.exists(moved.target()));
    }

    @Test
    void handlesPreserveOccupiedTargetsAndReleaseAfterDeniedMoves() throws IOException {
        Path source = Files.writeString(root.resolve("item.txt"), "original");
        Path folder = Files.createDirectory(root.resolve("folder"));
        var selected = capture(LocalFiles.describe(source));
        var target = captureDirectory(folder);
        Path occupied = Files.writeString(folder.resolve("item.txt"), "occupant");

        try (var editor = Win32FileHandles.open(source, Win32FileHandles.READ_DATA, Win32FileHandles.SHARE_READ_WRITE)) {
            assertThrows(AccessDeniedException.class, () -> move(selected, target, "item.txt"));
        }
        assertThrows(FileAlreadyExistsException.class, () -> move(selected, target, "item.txt"));
        assertEquals("original", Files.readString(source));
        assertEquals("occupant", Files.readString(occupied));

        Path published = move(selected, target, "another.txt").target();
        assertEquals("original", Files.readString(published));
        assertFalse(Files.exists(source));
    }

    @Test
    void applicationRecordsTheActualTargetAndUndoesMoveAfterEditing() throws IOException {
        Path source = Files.writeString(root.resolve("note.txt"), "before");
        Path target = Files.createDirectory(root.resolve("target"));
        var selected = FileChanges.capture(LocalFiles.describe(source));
        var destination = FileChanges.destination(target);
        assertFalse(selected.needsPathConfirmation(destination, true));

        var resolved = FileChanges.execute(java.util.List.of(FileChanges.transfer(selected, destination, true, false)));
        var item = resolved.items().getFirst();
        assertEquals(FileChanges.State.APPLIED, item.state());
        Path published = item.receipt().target();
        assertEquals(target.resolve("note.txt"), published);
        assertEquals(selected.nativeItem().id(), item.receipt().identity().id());
        Files.writeString(published, "latest file content");

        var undone = FileChanges.execute(resolved.reversals());
        assertEquals(FileChanges.State.APPLIED, undone.items().getFirst().state());
        assertEquals("latest file content", Files.readString(source));
        assertFalse(Files.exists(published));
    }

    @Test
    void directoryUndoPreservesDeepEditsAndNewEntries() throws IOException {
        Path source = Files.createDirectory(root.resolve("资料😀"));
        Path nested = Files.createDirectories(source.resolve("inner/deep"));
        Files.writeString(nested.resolve("note.txt"), "before");
        Path target = Files.createDirectory(root.resolve("target"));

        var intent = FileChanges.capture(LocalFiles.describe(source));
        var moved = FileChanges.execute(List.of(
                FileChanges.transfer(intent, FileChanges.destination(target), true, false)));
        assertEquals(FileChanges.State.APPLIED, moved.items().getFirst().state(), () -> String.valueOf(moved.error()));
        Path published = moved.items().getFirst().receipt().target();
        assertEquals(target.resolve(source.getFileName()), published);
        Files.writeString(published.resolve("inner/deep/note.txt"), "latest edit");
        Files.writeString(published.resolve("inner/new.txt"), "new data");

        var undone = FileChanges.execute(moved.reversals());
        assertEquals(FileChanges.State.APPLIED, undone.items().getFirst().state(), () -> String.valueOf(undone.error()));
        assertEquals("latest edit", Files.readString(nested.resolve("note.txt")));
        assertEquals("new data", Files.readString(source.resolve("inner/new.txt")));
        assertFalse(Files.exists(published));
    }

    @Test
    void undoDoesNotMoveASameNameSameContentReplacement() throws IOException {
        Path source = Files.writeString(root.resolve("note.txt"), "same bytes");
        Path target = Files.createDirectory(root.resolve("target"));
        var selected = FileChanges.capture(LocalFiles.describe(source));
        var moved = FileChanges.execute(List.of(
                FileChanges.transfer(selected, FileChanges.destination(target), true, false)));
        assertEquals(FileChanges.State.APPLIED, moved.items().getFirst().state());
        Path published = moved.items().getFirst().receipt().target();
        var recorded = LocalFiles.describe(published);
        Path parked = Files.move(published, target.resolve("parked.txt"));
        Files.writeString(published, "same bytes");
        Files.getFileAttributeView(published, BasicFileAttributeView.class)
                .setTimes(recorded.modified(), null, recorded.created());
        assertTrue(recorded.sameMetadata(LocalFiles.describe(published)));

        var undone = FileChanges.execute(moved.reversals());
        assertEquals(FileChanges.State.FAILED, undone.items().getFirst().state());
        assertFalse(Files.exists(source));
        assertEquals("same bytes", Files.readString(parked));
        assertEquals("same bytes", Files.readString(published));
    }

    @Test
    void destinationAclDenialPreservesSourceAndAllowsRetryAfterRestoration() throws IOException {
        Path source = Files.writeString(root.resolve("note.txt"), "original");
        Path target = Files.createDirectory(root.resolve("target"));
        var selected = FileChanges.capture(LocalFiles.describe(source));
        var change = FileChanges.transfer(selected, FileChanges.destination(target), true, false);
        var view = Files.getFileAttributeView(target, AclFileAttributeView.class);
        var originalAcl = view.getAcl();
        var restricted = new ArrayList<>(originalAcl);
        restricted.addFirst(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(view.getOwner())
                .setPermissions(AclEntryPermission.ADD_FILE, AclEntryPermission.ADD_SUBDIRECTORY).build());

        try {
            view.setAcl(restricted);
            var denied = FileChanges.execute(List.of(change));
            assertEquals(FileChanges.State.FAILED, denied.items().getFirst().state());
            assertInstanceOf(AccessDeniedException.class, denied.error());
            assertTrue(denied.reversals().isEmpty());
            assertEquals("original", Files.readString(source));
            assertFalse(Files.exists(target.resolve("note.txt")));
        } finally {
            view.setAcl(originalAcl);
        }

        var applied = FileChanges.execute(List.of(change));
        assertEquals(FileChanges.State.APPLIED, applied.items().getFirst().state());
        assertEquals("original", Files.readString(target.resolve("note.txt")));
        assertFalse(Files.exists(source));
    }

    @Test
    void uncertainSecondMoveKeepsTheVerifiedPrefixAndRemainingCutItems() throws IOException {
        Path first = Files.writeString(root.resolve("first.txt"), "first");
        Path second = Files.writeString(root.resolve("second.txt"), "second");
        Path third = Files.writeString(root.resolve("third.txt"), "third");
        Path target = Files.createDirectory(root.resolve("target"));
        var destination = FileChanges.destination(target);
        var firstMove = FileChanges.transfer(FileChanges.capture(LocalFiles.describe(first)), destination, true, false);
        var secondMove = new FileChanges.Change(second, List.of(root, target), () -> {
            Path published = Files.move(second, target.resolve(second.getFileName()));
            throw LocalFiles.uncertain("夹具模拟写入后结果未知", new IOException("结果丢失"), second, published);
        });
        var thirdMove = FileChanges.transfer(FileChanges.capture(LocalFiles.describe(third)), destination, true, false);

        var result = FileChanges.execute(List.of(firstMove, secondMove, thirdMove));
        assertEquals(List.of(FileChanges.State.APPLIED, FileChanges.State.UNKNOWN, FileChanges.State.NOT_RUN),
                result.items().stream().map(FileChanges.Item::state).toList());
        assertEquals(List.of(third), result.remainingCut(List.of(first, second, third)));
        assertEquals(target.resolve("first.txt"), result.items().getFirst().receipt().target());
        assertEquals(1, result.reversals().size());
        assertEquals(FileChanges.State.APPLIED, FileChanges.execute(result.reversals()).items().getFirst().state());
        assertEquals("first", Files.readString(first));
        assertEquals("second", Files.readString(target.resolve("second.txt")));
        assertEquals("third", Files.readString(third));
        assertFalse(Files.exists(target.resolve("third.txt")));
    }

    @Test
    void applicationRestoresCaseOnlyRenameWithoutDeletingEdits() throws IOException {
        Path source = Files.writeString(root.resolve("name.txt"), "original");
        var selected = FileChanges.capture(LocalFiles.describe(source));
        Path upper = root.resolve("NAME.txt");

        var changed = FileChanges.execute(java.util.List.of(FileChanges.rename(selected, upper, false)));
        assertEquals(FileChanges.State.APPLIED, changed.items().getFirst().state());
        Files.writeString(upper, "edited");
        assertEquals(FileChanges.State.APPLIED, FileChanges.execute(changed.reversals()).items().getFirst().state());
        try (var entries = Files.list(root)) {
            assertEquals("name.txt", entries.findFirst().orElseThrow().getFileName().toString());
        }
        assertEquals("edited", Files.readString(source));
    }

    @Test
    void applicationCopiesWithAPinnedSourceAndStillRefusesDeleteBasedUndo() throws IOException {
        Path source = Files.writeString(root.resolve("note.txt"), "original");
        Path folder = Files.createDirectory(root.resolve("target"));
        var selected = FileChanges.capture(LocalFiles.describe(source));
        var target = FileChanges.destination(folder);
        assertFalse(selected.needsPathConfirmation(target, false));

        var copied = FileChanges.execute(java.util.List.of(FileChanges.transfer(selected, target, false, false)));
        var item = copied.items().getFirst();
        assertEquals(FileChanges.State.APPLIED, item.state(), () -> String.valueOf(copied.error()));
        assertEquals(folder.resolve("note.txt"), item.receipt().target());
        assertEquals(FileChanges.State.REFUSED, FileChanges.execute(copied.reversals()).items().getFirst().state());
        assertEquals("original", Files.readString(source));
        assertEquals("original", Files.readString(item.receipt().target()));
    }

    @Test
    void replacedOriginalParentAndOccupiedNameAreRefusedDuringUndo() throws IOException {
        Path origin = Files.createDirectory(root.resolve("origin"));
        Path source = Files.writeString(origin.resolve("note.txt"), "original");
        Path target = Files.createDirectory(root.resolve("target"));
        var selected = FileChanges.capture(LocalFiles.describe(source));
        var moved = FileChanges.execute(java.util.List.of(
                FileChanges.transfer(selected, FileChanges.destination(target), true, false)));
        assertEquals(FileChanges.State.APPLIED, moved.items().getFirst().state());

        Files.writeString(source, "occupied");
        var conflict = FileChanges.execute(moved.reversals());
        assertInstanceOf(FileAlreadyExistsException.class, conflict.error());
        assertEquals("occupied", Files.readString(source));
        assertEquals(moved.reversals(), conflict.pending());

        Files.move(origin, root.resolve("previous-origin"));
        Files.createDirectory(origin);
        var replaced = FileChanges.execute(conflict.pending());
        assertEquals(FileChanges.State.FAILED, replaced.items().getFirst().state());
        assertFalse(Files.exists(source));
        assertEquals("original", Files.readString(target.resolve("note.txt")));
        assertEquals("occupied", Files.readString(root.resolve("previous-origin/note.txt")));
    }

    @Test
    void pathFallbackRequiresConsentAndDoesNotCreateNativeUndo() throws IOException {
        Path source = Files.writeString(root.resolve("note.txt"), "original");
        Path folder = Files.createDirectory(root.resolve("target"));
        var selected = FileChanges.capture(LocalFiles.describe(source));
        var fallback = new FileChanges.Destination(folder, null);
        assertTrue(selected.needsPathConfirmation(fallback, true));

        var refused = FileChanges.execute(java.util.List.of(FileChanges.transfer(selected, fallback, true, false)));
        assertEquals(FileChanges.State.FAILED, refused.items().getFirst().state());
        assertEquals("original", Files.readString(source));
        var moved = FileChanges.execute(java.util.List.of(FileChanges.transfer(selected, fallback, true, true)));
        assertEquals(FileChanges.State.APPLIED, moved.items().getFirst().state());
        assertEquals(FileChanges.State.REFUSED, FileChanges.execute(moved.reversals()).items().getFirst().state());
        assertEquals("original", Files.readString(folder.resolve("note.txt")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pathFallbackStillRejectsAReplacedKnownDestination(boolean moving) throws IOException {
        Path source = Files.writeString(root.resolve("note.txt"), "original");
        Path target = Files.createDirectory(root.resolve("target"));
        var destination = FileChanges.destination(target);
        var selection = new FileChanges.Selection(LocalFiles.describe(source), null);
        Path previousTarget = Files.move(target, root.resolve("previous-target"));
        Files.createDirectory(target);

        var result = FileChanges.execute(List.of(FileChanges.transfer(selection, destination, moving, true)));
        assertEquals(FileChanges.State.FAILED, result.items().getFirst().state());
        assertEquals("original", Files.readString(source));
        assertFalse(Files.exists(target.resolve("note.txt")));
        assertFalse(Files.exists(previousTarget.resolve("note.txt")));
    }

    @Test
    void junctionIsRejectedAsAnAncestorAndAsADestination() throws Exception {
        Path direct = Files.createDirectory(root.resolve("direct"));
        Path file = Files.writeString(direct.resolve("original.txt"), "original");
        Path junction = root.resolve("junction");
        var command = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "New-Item -ItemType Junction -Path $env:PHASE_JUNCTION -Target $env:PHASE_TARGET -ErrorAction Stop | Out-Null")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        command.environment().put("PHASE_JUNCTION", junction.toString());
        command.environment().put("PHASE_TARGET", direct.toString());
        Process child = command.start();
        try {
            assertTrue(child.waitFor(8, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            assertNull(captureDirectory(junction));
            assertNull(capture(LocalFiles.describe(junction.resolve("original.txt"))));
            assertEquals("original", Files.readString(file));
        } finally {
            if (child.isAlive()) { child.destroyForcibly().waitFor(); }
            Files.deleteIfExists(junction);
        }
        assertEquals("original", Files.readString(file));
    }
}

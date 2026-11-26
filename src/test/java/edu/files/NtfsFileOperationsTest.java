package edu.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
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

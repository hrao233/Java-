package edu.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static edu.files.Win32FileHandles.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class Win32FileHandlesTest {
    @TempDir Path root;

    @BeforeEach
    void requireLocalNtfs() throws IOException {
        assumeTrue(com.sun.jna.Native.POINTER_SIZE == 8);
        assumeTrue(fixedDrive(root.toAbsolutePath().getRoot()));
        try (var handle = open(root, READ_ATTRIBUTES, SHARE_ALL)) {
            assumeTrue("NTFS".equals(handle.fileSystem()));
        }
    }

    @Test
    void volumeAndFileIdSurviveRenameButDistinguishSameNameSameContentReplacement() throws IOException {
        Path first = Files.writeString(root.resolve("sample.txt"), "same");
        Path alias = Files.createLink(root.resolve("link.txt"), first);
        var original = id(first);
        assertEquals(original, id(alias));

        Path renamed = Files.move(first, root.resolve("renamed.txt"));
        Path replacement = Files.writeString(first, "same");
        assertEquals(original, id(renamed));
        assertNotEquals(original, id(replacement));
        assertNotEquals(original, id(root));
    }

    @Test
    void renameMovesOnlyTheOpenedHardLinkAndChangesCase() throws IOException {
        Path source = Files.writeString(root.resolve("original.txt"), "keep");
        Path alias = Files.createLink(root.resolve("alias.txt"), source);
        Path folder = Files.createDirectory(root.resolve("destination"));

        try (var parent = open(folder, READ_ATTRIBUTES | TRAVERSE, SHARE_READ_WRITE);
             var item = open(source, READ_ATTRIBUTES | DELETE_ACCESS, SHARE_READ_WRITE)) {
            rename(item, parent, "文件😀.txt");
        }

        Path moved = folder.resolve("文件😀.txt");
        assertFalse(Files.exists(source));
        assertTrue(Files.isSameFile(moved, alias));
        try (var parent = open(folder, READ_ATTRIBUTES | TRAVERSE, SHARE_READ_WRITE);
             var item = open(moved, READ_ATTRIBUTES | DELETE_ACCESS, SHARE_READ_WRITE)) {
            rename(item, parent, "文件😀.TXT");
        }
        try (var children = Files.list(folder)) {
            assertEquals("文件😀.TXT", children.findFirst().orElseThrow().getFileName().toString());
        }
        assertEquals("keep", Files.readString(alias));
    }

    @Test
    void sourceAndDirectoryRemainBoundWhileCompetingActionsReachTheCommitBoundary() throws Exception {
        Path from = Files.createDirectory(root.resolve("from"));
        Path folder = Files.createDirectories(root.resolve("parent/destination"));
        Path source = Files.writeString(from.resolve("item.txt"), "source");
        Path existing = folder.resolve("item.txt");

        try (var parent = open(folder, READ_ATTRIBUTES | TRAVERSE, SHARE_READ_WRITE);
             var item = open(source, READ_ATTRIBUTES | DELETE_ACCESS, SHARE_READ_WRITE);
             var executor = Executors.newSingleThreadExecutor()) {
            // These operations run after both handles exist and before the rename call.
            executor.submit(() -> {
                assertThrows(IOException.class, () -> Files.move(source, from.resolve("moved-away.txt")));
                assertThrows(IOException.class, () -> Files.move(folder, root.resolve("old-destination")));
                assertThrows(IOException.class, () -> Files.move(folder.getParent(), root.resolve("old-parent")));
                Files.writeString(existing, "occupant");
                return null;
            }).get(5, TimeUnit.SECONDS);

            var conflict = assertThrows(Failure.class, () -> rename(item, parent, "item.txt"));
            assertEquals(183, conflict.code());
        }

        assertEquals("source", Files.readString(source));
        assertEquals("occupant", Files.readString(existing));
        assertFalse(Files.exists(from.resolve("moved-away.txt")));
    }

    @Test
    void shareDenialMakesOpeningForRenameFailAndReleasedHandlesPermitASecondOpen() throws IOException {
        Path source = Files.writeString(root.resolve("source.txt"), "keep");
        try (var editor = open(source, READ_ATTRIBUTES | 1, SHARE_READ_WRITE)) {
            assertThrows(Failure.class, () -> {
                try (var ignored = open(source, READ_ATTRIBUTES | DELETE_ACCESS, SHARE_READ_WRITE)) {
                    fail("an existing handle which disallows DELETE sharing must block the rename open");
                }
            });
        }
        try (var item = open(source, READ_ATTRIBUTES | DELETE_ACCESS, SHARE_READ_WRITE)) {
            assertFalse(item.info().directory());
        }
        assertEquals("keep", Files.readString(source));
    }

    @Test
    void repeatedQueriesReleaseTheirHandles() throws IOException {
        Path file = Files.writeString(root.resolve("example.txt"), "data");
        var expected = id(file);
        for (int i = 0; i < 32; i++) {
            assertEquals(expected, id(file));
        }
        int baseline = processHandleCount();
        for (int i = 0; i < 500; i++) {
            assertEquals(expected, id(file));
        }
        assertTrue(processHandleCount() <= baseline + 12, "process handle count grew after closing queries");
    }

    private static Id id(Path path) throws IOException {
        try (var handle = open(path, READ_ATTRIBUTES, SHARE_ALL)) {
            return handle.info().id();
        }
    }
}

package edu.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalFilesTest {
    @TempDir Path root;

    @Test
    void copiesAFileAndADirectoryWithTheirContents() throws IOException {
        Path from = Files.createDirectory(root.resolve("from"));
        Path to = Files.createDirectory(root.resolve("to"));
        Path file = Files.writeString(from.resolve("原件😀.txt"), "原文");
        Path directory = Files.createDirectories(from.resolve("tree/inner"));
        Files.writeString(directory.resolve("note.txt"), "nested");

        var copiedFile = LocalFiles.copy(file, to);
        var copiedTree = LocalFiles.copy(from.resolve("tree"), to);

        assertEquals(to.resolve("原件😀.txt"), copiedFile.path());
        assertEquals("原文", Files.readString(copiedFile.path()));
        assertEquals("原文", Files.readString(file));
        assertTrue(copiedTree.directory());
        assertEquals("nested", Files.readString(to.resolve("tree/inner/note.txt")));
        assertEquals("nested", Files.readString(directory.resolve("note.txt")));
        assertNoStagingFiles();
    }

    @Test
    void sameFolderCopyUsesDistinctSuffixesAndPreservesTheExtension() throws IOException {
        Path from = Files.writeString(root.resolve("a.txt"), "内容");
        var a = LocalFiles.copy(from, root);
        var b = LocalFiles.copy(from, root);

        assertEquals("a - 副本.txt", a.name());
        assertEquals("a - 副本 (2).txt", b.name());
        for (Path file : List.of(from, a.path(), b.path())) {
            assertEquals("内容", Files.readString(file));
        }
        assertNoStagingFiles();
    }

    @Test
    void existingTargetIsNeverOverwritten() throws IOException {
        Path folder = Files.createDirectory(root.resolve("to"));
        Path source = Files.writeString(root.resolve("a.txt"), "source data");
        Path target = Files.writeString(folder.resolve("a.txt"), "target data");

        assertThrows(FileAlreadyExistsException.class, () -> LocalFiles.copy(source, folder));
        assertThrows(FileAlreadyExistsException.class, () -> LocalFiles.move(source, folder));
        assertThrows(FileAlreadyExistsException.class, () -> LocalFiles.rename(source, target));
        assertEquals("source data", Files.readString(source));
        assertEquals("target data", Files.readString(target));
        assertNoStagingFiles();
    }

    @Test
    void refusesCopyToTheSourceDirectoryOrItsDescendant() throws IOException {
        Path inner = Files.createDirectories(root.resolve("tree/inner"));
        Path source = inner.getParent();
        Path file = Files.writeString(inner.resolve("keep.txt"), "keep");
        for (Path destination : List.of(source, inner)) {
            assertThrows(IOException.class, () -> LocalFiles.copy(source, destination));
            assertFalse(Files.exists(destination.resolve(source.getFileName())));
        }
        assertEquals("keep", Files.readString(file));
        assertNoStagingFiles();
    }

    @Test
    void renamesOnlyTheCaseOfAUnicodeNameAndKeepsTheBytes() throws IOException {
        Path file = Files.writeString(root.resolve("资料😀.TXT"), "数据");
        Path renamed = LocalFiles.childNamed(root, "资料😀.txt");
        LocalFiles.rename(file, renamed);
        try (var paths = Files.list(root)) {
            assertEquals(List.of("资料😀.txt"), paths.map(path -> path.getFileName().toString()).toList());
        }
        assertEquals("数据", Files.readString(renamed));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", " ", ".", "..", "../outside", "two/parts", "trailing.", "trailing " })
    void rejectsSpecialNamesAndLeavesExistingDataIntact(String name) throws IOException {
        Path kept = Files.writeString(root.resolve("keep.txt"), "keep");
        assertThrows(InvalidPathException.class, () -> LocalFiles.childNamed(root, name));
        assertEquals("keep", Files.readString(kept));
        try (var paths = Files.list(root)) {
            assertEquals(List.of(kept), paths.toList());
        }
    }

    private void assertNoStagingFiles() throws IOException {
        try (var paths = Files.walk(root)) {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString().startsWith(".file-manager-")));
        }
    }
}

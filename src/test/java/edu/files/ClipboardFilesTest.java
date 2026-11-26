package edu.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClipboardFilesTest {
    @TempDir Path root;

    @Test
    void wideDropFilesPreservesMixedNamesAndDoubleTerminator() throws Exception {
        var paths = List.of("C:\\隔离 夹具\\一.txt", "C:\\emoji 📄", "D:\\" + "目录\\".repeat(140) + "尾.txt");
        byte[] files = ClipboardFiles.encode(paths);
        var in = ByteBuffer.wrap(files).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(20, in.getInt());
        assertEquals(1, in.getInt(16));
        assertEquals(0, in.getInt(files.length - Integer.BYTES));
        assertEquals(paths, ClipboardFiles.decode(Arrays.copyOf(files, files.length + 16)));
    }

    @Test
    void rejectsTruncationBadOffsetsAnsiAndOversizedSelections() throws Exception {
        byte[] files = ClipboardFiles.encode(List.of("C:\\file.txt"));
        assertThrows(IOException.class, () -> ClipboardFiles.decode(Arrays.copyOf(files, files.length - 2)));
        var header = ByteBuffer.wrap(files).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> ClipboardFiles.decode(files));
        header.putInt(0, 20).putInt(16, 0);
        assertThrows(IOException.class, () -> ClipboardFiles.decode(files));
        assertThrows(IOException.class, () -> ClipboardFiles.encode(List.of("C:\\x\0other")));
        assertThrows(IOException.class, () -> ClipboardFiles.encode(List.of("C:\\" + '\uD800')));
        assertThrows(IOException.class, () -> ClipboardFiles.encode(
                java.util.Collections.nCopies(ClipboardFiles.MAX_ITEMS + 1, "C:\\file")));
        assertThrows(IOException.class, () -> ClipboardFiles.decode(new byte[ClipboardFiles.MAX_BYTES + 1]));
    }

    @Test
    void filtersDuplicatePathsAndRejectsNestedOrRelativeEntries() throws Exception {
        Path folder = root.resolve("parent");
        Path file = folder.resolve("child");
        assertEquals(List.of(file), ClipboardFiles.paths(List.of(file.toString(), file.toString())));
        assertThrows(IOException.class, () -> ClipboardFiles.paths(List.of(file.toString(), folder.toString())));
        assertThrows(IOException.class, () -> ClipboardFiles.paths(List.of("relative.txt")));
        assertThrows(IOException.class, () -> ClipboardFiles.paths(List.of(root.resolve("a/../b").toString())));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void validatesExtendedPathsAndRejectsDevicePaths() throws Exception {
        Path file = root.resolve("中文 资源.txt");
        assertEquals(List.of(file), ClipboardFiles.paths(List.of("\\\\?\\" + file)));
        assertEquals(List.of(Path.of("\\\\server\\share\\clip")), ClipboardFiles.paths(List.of("\\\\?\\UNC\\server\\share\\clip")));
        assertThrows(IOException.class, () -> ClipboardFiles.paths(List.of("\\\\.\\PhysicalDrive0")));
        assertThrows(IOException.class, () -> ClipboardFiles.paths(List.of("\\\\?\\GLOBALROOT\\Device\\other")));
        assertThrows(IOException.class, () -> ClipboardFiles.paths(List.of(file + ":stream")));
    }

    @Test
    void quotesEachPathAndSeparatesMultiplePathsByNewline() {
        Path first = root.resolve("我 的文件.txt");
        Path second = root.resolve("second");
        assertEquals("\"" + first + "\"", ClipboardFiles.quotedPaths(List.of(first)));
        assertEquals("\"" + first + "\"" + System.lineSeparator() + "\"" + second + "\"",
                ClipboardFiles.quotedPaths(List.of(first, second)));
    }

    @Test
    void moveIntentIsNeverTreatedAsCopy() throws Exception {
        assertFalse(WindowsClipboard.isMove(1));
        assertFalse(WindowsClipboard.isMove(5));
        assertTrue(WindowsClipboard.isMove(2));
        assertTrue(WindowsClipboard.isMove(3));
        assertThrows(IOException.class, () -> WindowsClipboard.isMove(0));
        assertThrows(IOException.class, () -> WindowsClipboard.isMove(4));
    }

    @Test
    void identicalNewPathsAreADifferentClipboardPublication() throws Exception {
        var clipboard = new MemoryClipboard(false);
        Path file = Files.writeString(root.resolve("same.txt"), "contents");
        var offer = new FileClipboard.Offer(UUID.randomUUID(), false, List.of(file));
        var original = clipboard.publish(offer);
        var transfer = new FileClipboard.Transfer(original, offer.token(), false,
                List.of(FileChanges.capture(LocalFiles.describe(file))));
        assertTrue(transfer.matches(clipboard.read()));
        var newer = new FileClipboard.Offer(UUID.randomUUID(), false, List.of(file));
        var elsewhere = clipboard.publish(newer);
        assertFalse(transfer.matches(clipboard.read()));
        assertFalse(clipboard.replace(original, offer.token(), null).applied());
        assertEquals(newer.token(), clipboard.read().token());
        assertEquals(elsewhere, clipboard.probe());
        assertFalse(clipboard.replace(elsewhere, offer.token(), null).applied());
    }

    @Test
    void internalCutHasNoPortableFileListAndCanClearOnlyItsVersion() throws Exception {
        var clipboard = new MemoryClipboard(false);
        Path file = Files.writeString(root.resolve("clip.txt"), "fixture");
        var offer = new FileClipboard.Offer(UUID.randomUUID(), true, List.of(file));
        var version = clipboard.publish(offer);
        assertEquals(FileClipboard.Kind.CUT, clipboard.probe().kind());
        assertEquals(offer.token(), clipboard.read().token());
        assertTrue(clipboard.read().paths().isEmpty());
        assertTrue(clipboard.replace(version, offer.token(), null).applied());
        assertEquals(FileClipboard.Kind.EMPTY, clipboard.probe().kind());
        assertEquals("fixture", Files.readString(file));
    }
}

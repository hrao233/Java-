package edu.files;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsClipboardFormatTest {
    @TempDir Path root;

    interface Shell extends StdCallLibrary {
        int SHParseDisplayName(WString path, Pointer bindingContext, PointerByReference pidl, int attributes,
                               Pointer observedAttributes);
        Pointer ILFindLastID(Pointer pidl);
        int SHCreateDataObject(Pointer folder, int count, Pointer children, Pointer innerObject, Pointer iid,
                               PointerByReference result);
        int DragQueryFileW(Pointer drop, int index, char[] name, int length);
    }

    interface Ole extends StdCallLibrary {
        int OleInitialize(Pointer reserved);
        void OleUninitialize();
        void CoTaskMemFree(Pointer pointer);
        void ReleaseStgMedium(Pointer storage);
    }

    @Test
    void nativeShellCanReadOurDropFilesBufferWithoutAccessingTheClipboard() throws Exception {
        assumeTrue(Native.POINTER_SIZE == 8);
        var shell = Native.load("shell32", Shell.class);
        var kernel = Native.load("kernel32", WindowsClipboard.Kernel.class);
        var paths = List.of(root.resolve("文件 one.txt"), root.resolve("目录 二"),
                root.resolve("中文-".repeat(36)).resolve("long-name-" + "l".repeat(80) + ".txt"));
        byte[] contents = ClipboardFiles.encode(paths.stream().map(Path::toString).toList());
        var block = kernel.GlobalAlloc(2, contents.length);
        assertNotNull(block);
        try {
            var data = kernel.GlobalLock(block);
            assertNotNull(data);
            try { data.write(0, contents, 0, contents.length); }
            finally { kernel.GlobalUnlock(block); }
            assertEquals(paths.size(), shell.DragQueryFileW(block, -1, null, 0));
            for (int index = 0; index < paths.size(); index++) {
                int length = shell.DragQueryFileW(block, index, null, 0);
                var name = new char[length + 1];
                assertEquals(length, shell.DragQueryFileW(block, index, name, name.length));
                assertEquals(paths.get(index).toString(), Native.toString(name));
            }
        } finally {
            assertNull(kernel.GlobalFree(block));
        }
    }

    @Test
    void javaDecoderReadsARealWindowsShellDataObject() throws Exception {
        assumeTrue(Native.POINTER_SIZE == 8);
        var shell = Native.load("shell32", Shell.class);
        var ole = Native.load("ole32", Ole.class);
        var kernel = Native.load("kernel32", WindowsClipboard.Kernel.class);
        Path file = Files.writeString(root.resolve("外部 实验.txt"), "source");
        Path folder = Files.createDirectory(root.resolve("外部 文件夹"));
        check(ole.OleInitialize(null));
        Pointer parent = null;
        Pointer first = null;
        Pointer second = null;
        try (var iid = new Memory(16); var children = new Memory(16);
             var format = new Memory(32); var storage = new Memory(24)) {
            parent = pidl(shell, root);
            first = pidl(shell, file);
            second = pidl(shell, folder);
            iid.clear();
            iid.setInt(0, 0x10e); // IID_IDataObject
            iid.setByte(8, (byte) 0xc0);
            iid.setByte(15, (byte) 0x46);
            children.setPointer(0, shell.ILFindLastID(first));
            children.setPointer(8, shell.ILFindLastID(second));
            var result = new PointerByReference();
            check(shell.SHCreateDataObject(parent, 2, children, null, iid, result));
            var object = result.getValue();
            assertNotNull(object);
            try {
                format.clear();
                format.setShort(0, (short) WindowsClipboard.CF_HDROP);
                format.setInt(16, 1); // DVASPECT_CONTENT
                format.setInt(20, -1);
                format.setInt(24, 1); // TYMED_HGLOBAL
                storage.clear();
                check(vtable(object, 3).invokeInt(new Object[]{object, format, storage}));
                try {
                    assertEquals(1, storage.getInt(0));
                    var block = storage.getPointer(8);
                    var data = kernel.GlobalLock(block);
                    assertNotNull(data);
                    byte[] bytes;
                    try { bytes = data.getByteArray(0, Math.toIntExact(kernel.GlobalSize(block))); }
                    finally { kernel.GlobalUnlock(block); }
                    assertEquals(List.of(file, folder), ClipboardFiles.paths(ClipboardFiles.decode(bytes)));
                } finally {
                    ole.ReleaseStgMedium(storage);
                }
            } finally {
                vtable(object, 2).invokeInt(new Object[]{object});
            }
        } finally {
            if (second != null) { ole.CoTaskMemFree(second); }
            if (first != null) { ole.CoTaskMemFree(first); }
            if (parent != null) { ole.CoTaskMemFree(parent); }
            ole.OleUninitialize();
        }
    }

    private static Pointer pidl(Shell shell, Path path) {
        var result = new PointerByReference();
        check(shell.SHParseDisplayName(new WString(path.toString()), null, result, 0, null));
        return result.getValue();
    }

    private static Function vtable(Pointer object, int slot) {
        return Function.getFunction(object.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE), Function.ALT_CONVENTION);
    }

    private static void check(int hresult) {
        assertTrue(hresult >= 0, () -> "HRESULT 0x%08x".formatted(hresult));
    }
}

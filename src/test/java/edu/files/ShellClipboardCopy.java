package edu.files;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.nio.file.Path;
import java.util.UUID;

/** Uses the Windows Shell receiver and file operation APIs inside the opt-in clipboard fixture. */
final class ShellClipboardCopy {
    interface Ole extends StdCallLibrary {
        int OleInitialize(Pointer reserved);
        void OleUninitialize();
        int OleGetClipboard(PointerByReference result);
        void CoTaskMemFree(Pointer memory);
        int CoCreateInstance(Pointer clsid, Pointer outer, int context, Pointer iid, PointerByReference result);
    }

    interface Shell extends StdCallLibrary {
        int SHCreateShellItemArrayFromDataObject(Pointer data, Pointer iid, PointerByReference result);
        int SHCreateItemFromParsingName(WString name, Pointer context, Pointer iid, PointerByReference result);
    }

    private ShellClipboardCopy() { }

    static void paste(Path destination, Path expectedSource, int expectedItems) {
        var ole = Native.load("ole32", Ole.class);
        var shell = Native.load("shell32", Shell.class);
        check("OleInitialize", ole.OleInitialize(null));
        var result = new PointerByReference();
        try (var itemArray = guid("b63ea76d-1f85-456f-a19c-48159efa858b");
             var item = guid("43826d1e-e718-42ee-bc55-a1e261c37bfe");
             var clsid = guid("3ad05575-8857-4850-9277-11b85bdb8e09");
             var operation = guid("947aab5f-0a5c-4c13-b4d6-4bf7836fc9f8")) {
            check("OleGetClipboard", ole.OleGetClipboard(result));
            try (var source = new Com(result.getValue())) {
                check("SHCreateShellItemArrayFromDataObject",
                        shell.SHCreateShellItemArrayFromDataObject(source.pointer, itemArray, result));
                try (var items = new Com(result.getValue())) {
                    var count = new IntByReference();
                    check("IShellItemArray.GetCount", items.call(7, count));
                    if (count.getValue() != expectedItems) { throw new AssertionError("Unexpected Shell item count"); }
                    for (int index = 0; index < count.getValue(); index++) {
                        check("IShellItemArray.GetItemAt", items.call(8, index, result));
                        try (var member = new Com(result.getValue())) {
                            check("IShellItem.GetDisplayName", member.call(5, 0x80058000, result));
                            Pointer pathName = result.getValue();
                            try {
                                var path = Path.of(pathName.getWideString(0)).normalize();
                                if (!expectedSource.equals(path.getParent())) {
                                    throw new AssertionError("Shell source outside the fixture");
                                }
                            } finally {
                                ole.CoTaskMemFree(pathName);
                            }
                        }
                    }
                    check("SHCreateItemFromParsingName",
                            shell.SHCreateItemFromParsingName(new WString(destination.toString()), null, item, result));
                    try (var target = new Com(result.getValue())) {
                        check("CoCreateInstance", ole.CoCreateInstance(clsid, null, 1, operation, result));
                        try (var fileOperation = new Com(result.getValue())) {
                            check("SetOperationFlags", fileOperation.call(5, 0x0010 | 0x0004 | 0x0200 | 0x0400));
                            check("CopyItems", fileOperation.call(17, items.pointer, target.pointer));
                            check("PerformOperations", fileOperation.call(21));
                            var aborted = new IntByReference();
                            check("GetAnyOperationsAborted", fileOperation.call(22, aborted));
                            if (aborted.getValue() != 0) { throw new AssertionError("Shell cancelled the fixture copy"); }
                        }
                    }
                }
            }
        } finally {
            ole.OleUninitialize();
        }
    }

    private static Memory guid(String value) {
        var uuid = UUID.fromString(value);
        long high = uuid.getMostSignificantBits();
        long low = uuid.getLeastSignificantBits();
        var memory = new Memory(16);
        memory.setInt(0, (int) (high >>> 32));
        memory.setShort(4, (short) (high >>> 16));
        memory.setShort(6, (short) high);
        for (int i = 0; i < 8; i++) { memory.setByte(8 + i, (byte) (low >>> ((7 - i) * 8))); }
        return memory;
    }

    private record Com(Pointer pointer) implements AutoCloseable {
        int call(int slot, Object... arguments) {
            var function = Function.getFunction(pointer.getPointer(0).getPointer((long) slot * Native.POINTER_SIZE),
                    Function.ALT_CONVENTION);
            var call = new Object[arguments.length + 1];
            call[0] = pointer;
            System.arraycopy(arguments, 0, call, 1, arguments.length);
            return function.invokeInt(call);
        }

        @Override public void close() { call(2); }
    }

    private static void check(String operation, int status) {
        if (status < 0) { throw new AssertionError(operation + " HRESULT 0x%08x".formatted(status)); }
    }
}

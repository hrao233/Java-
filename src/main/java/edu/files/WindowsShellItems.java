package edu.files;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class WindowsShellItems {
    interface Shell extends StdCallLibrary {
        int SHParseDisplayName(WString path, Pointer context, PointerByReference item, int attributes, Pointer observed);
        int ILGetSize(Pointer item);
    }

    interface Ole extends StdCallLibrary {
        int OleInitialize(Pointer reserved);
        void OleUninitialize();
        void CoTaskMemFree(Pointer item);
    }

    private static final class Api {
        static final Shell SHELL = Native.load("shell32", Shell.class);
        static final Ole OLE = Native.load("ole32", Ole.class);
    }

    private WindowsShellItems() { }

    /** Supplements CF_HDROP for Shell receivers that request CFSTR_SHELLIDLIST. */
    static byte[] encode(List<Path> paths) {
        if (paths.stream().anyMatch(path -> !Win32FileHandles.fixedDrive(path.getRoot()))) { return null; }
        if (Api.OLE.OleInitialize(null) < 0) { return null; }
        try {
            var ids = new ArrayList<byte[]>(paths.size());
            int parentOffset = Integer.BYTES * (paths.size() + 2);
            int length = parentOffset + Short.BYTES;
            for (Path path : paths) {
                var result = new PointerByReference();
                if (Api.SHELL.SHParseDisplayName(new WString(path.toString()), null, result, 0, null) < 0
                        || result.getValue() == null) { return null; }
                Pointer item = result.getValue();
                try {
                    int bytes = Api.SHELL.ILGetSize(item);
                    if (bytes < Short.BYTES || bytes > ClipboardFiles.MAX_BYTES - length) { return null; }
                    ids.add(item.getByteArray(0, bytes));
                    length += bytes;
                } finally {
                    Api.OLE.CoTaskMemFree(item);
                }
            }
            var list = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
            list.putInt(paths.size()).putInt(parentOffset);
            int childOffset = parentOffset + Short.BYTES;
            for (byte[] id : ids) {
                list.putInt(childOffset);
                childOffset += id.length;
            }
            // The empty parent PIDL represents the desktop. The entries are desktop-relative.
            list.putShort((short) 0);
            for (byte[] id : ids) { list.put(id); }
            return list.array();
        } finally {
            Api.OLE.OleUninitialize();
        }
    }
}

package edu.files;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_16LE;

final class Win32FileHandles {
    static final int READ_ATTRIBUTES = 0x80;
    static final int READ_DATA = 1;
    static final int TRAVERSE = 0x20;
    static final int DELETE_ACCESS = 0x10000;
    static final int SHARE_READ_WRITE = 3;
    static final int SHARE_ALL = 7;
    static final int DIRECTORY_ATTRIBUTE = 0x10;
    static final int REPARSE_POINT_ATTRIBUTE = 0x400;
    private static final int OPEN_EXISTING = 3;
    private static final int BACKUP_SEMANTICS = 0x02000000;
    private static final int OPEN_REPARSE_POINT = 0x00200000;
    private static final int FILE_ID_INFO = 18;

    interface Kernel extends StdCallLibrary {
        Pointer CreateFileW(WString path, int access, int sharing, Pointer security, int creation, int flags, Pointer template);
        boolean GetFileInformationByHandle(Pointer handle, Pointer result);
        boolean GetFileInformationByHandleEx(Pointer handle, int informationClass, Pointer result, int size);
        boolean GetVolumeInformationByHandleW(Pointer handle, Pointer name, int nameLength, Pointer serial,
                                               Pointer maximumComponentLength, Pointer flags, char[] system, int systemLength);
        int GetDriveTypeW(WString root);
        boolean CloseHandle(Pointer handle);
    }

    interface Nt extends StdCallLibrary {
        int NtSetInformationFile(Pointer handle, Pointer ioStatus, Pointer info, int length, int informationClass);
        int RtlNtStatusToDosError(int status);
    }

    private static final class Library {
        static final Nt NTDLL = Native.load("ntdll", Nt.class);
        static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
    }

    record Id(long volume, long low, long high, long creationTime) { }

    record Info(Id id, int attributes, long size, long modifiedTime, int links) {
        boolean directory() { return (attributes & DIRECTORY_ATTRIBUTE) != 0; }
        boolean reparsePoint() { return (attributes & REPARSE_POINT_ATTRIBUTE) != 0; }
    }

    static final class Failure extends IOException {
        private final int code;

        Failure(String operation, Path path, int code) {
            super(operation + "（Windows 错误 " + code + "）：" + path);
            this.code = code;
        }

        int code() { return code; }
    }

    static final class Handle implements AutoCloseable {
        private final Path path;
        private Pointer pointer;

        private Handle(Path path, Pointer pointer) {
            this.path = path;
            this.pointer = pointer;
        }

        Info info() throws IOException {
            try (Memory fileId = new Memory(24); Memory basic = new Memory(52)) {
                if (!Library.KERNEL.GetFileInformationByHandleEx(pointer, FILE_ID_INFO, fileId, (int) fileId.size())) {
                    throw failure("无法读取文件 ID", path);
                }
                if (!Library.KERNEL.GetFileInformationByHandle(pointer, basic)) {
                    throw failure("无法读取文件属性", path);
                }
                Id id = new Id(fileId.getLong(0), fileId.getLong(8), fileId.getLong(16), fileTime(basic, 4));
                long size = (Integer.toUnsignedLong(basic.getInt(32)) << 32) | Integer.toUnsignedLong(basic.getInt(36));
                return new Info(id, basic.getInt(0), size, fileTime(basic, 20), basic.getInt(40));
            }
        }

        String fileSystem() throws IOException {
            char[] system = new char[32];
            if (!Library.KERNEL.GetVolumeInformationByHandleW(pointer, null, 0, null, null, null, system, system.length)) {
                throw failure("无法读取文件系统类型", path);
            }
            return Native.toString(system);
        }

        int attributes() throws IOException {
            try (Memory basic = new Memory(52)) {
                if (!Library.KERNEL.GetFileInformationByHandle(pointer, basic)) {
                    throw failure("无法读取目录属性", path);
                }
                return basic.getInt(0);
            }
        }

        @Override
        public void close() throws IOException {
            if (pointer != null) {
                Pointer closing = pointer;
                pointer = null;
                if (!Library.KERNEL.CloseHandle(closing)) {
                    throw failure("无法关闭文件句柄", path);
                }
            }
        }
    }

    private Win32FileHandles() { }

    static boolean fixedDrive(Path root) {
        return Library.KERNEL.GetDriveTypeW(new WString(root.toString())) == 3;
    }

    static Handle open(Path path, int access, int sharing) throws IOException {
        String windowsPath = "\\\\?\\" + path.toAbsolutePath().normalize();
        Pointer handle = Library.KERNEL.CreateFileW(new WString(windowsPath), access, sharing, null, OPEN_EXISTING,
                BACKUP_SEMANTICS | OPEN_REPARSE_POINT, null);
        if (handle == null || Pointer.nativeValue(handle) == -1L) {
            throw failure("无法打开项目", path);
        }
        return new Handle(path, handle);
    }

    static void rename(Handle source, Handle targetParent, String name) throws IOException {
        Path target = targetParent.path.resolve(name);
        byte[] filename = name.getBytes(UTF_16LE);
        int parentOffset = Native.POINTER_SIZE;
        int lengthOffset = 2 * Native.POINTER_SIZE;
        int nameOffset = lengthOffset + Integer.BYTES;
        int size = nameOffset + filename.length + Character.BYTES + Native.POINTER_SIZE;
        try (Memory info = new Memory(size); Memory statusBlock = new Memory(2L * Native.POINTER_SIZE)) {
            info.clear();
            statusBlock.clear();
            // ReplaceIfExists stays false. The name is resolved relative to the open directory.
            info.setPointer(parentOffset, targetParent.pointer);
            info.setInt(lengthOffset, filename.length);
            info.write(nameOffset, filename, 0, filename.length);
            int status = Library.NTDLL.NtSetInformationFile(source.pointer, statusBlock, info, size, 10);
            if (status != 0) {
                throw new Failure("无法按句柄重命名 (NTSTATUS 0x" + Integer.toHexString(status) + ")",
                        target, Library.NTDLL.RtlNtStatusToDosError(status));
            }
        }
    }

    static boolean available() {
        NativeLibrary.getInstance("ntdll").getFunction("NtSetInformationFile");
        NativeLibrary.getInstance("kernel32").getFunction("GetFileInformationByHandleEx");
        return true;
    }

    private static long fileTime(Memory buffer, int offset) {
        return (Integer.toUnsignedLong(buffer.getInt(offset + 4)) << 32)
                | Integer.toUnsignedLong(buffer.getInt(offset));
    }

    private static Failure failure(String operation, Path path) {
        return new Failure(operation, path, Native.getLastError());
    }
}

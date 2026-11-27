package edu.files;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static edu.files.CopyDiagnostics.Part.*;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class Win32FileHandles {
    static final int READ_ATTRIBUTES = 0x80;
    static final int READ_DATA = 1;
    static final int TRAVERSE = 0x20;
    static final int DELETE_ACCESS = 0x10000;
    static final int SHARE_READ_WRITE = 3;
    static final int SHARE_ALL = 7;
    static final int SYNCHRONIZE = 0x00100000;
    static final int DIRECTORY_ATTRIBUTE = 0x10;
    static final int REPARSE_POINT_ATTRIBUTE = 0x400;
    private static final int OPEN_EXISTING = 3;
    private static final int BACKUP_SEMANTICS = 0x02000000;
    private static final int OPEN_REPARSE_POINT = 0x00200000;
    private static final int FILE_ID_INFO = 18;
    private static final int FILE_DISPOSITION_INFO_EX = 21;
    private static final int FILE_RENAME_INFORMATION = 10;
    private static final int FILE_RENAME_INFORMATION_EX = 65;
    private static final int FILE_RENAME_POSIX_SEMANTICS = 2;
    private static final int STATUS_INVALID_INFO_CLASS = 0xc0000003;
    private static final int STATUS_NOT_IMPLEMENTED = 0xc0000002;
    private static final int STATUS_NOT_SUPPORTED = 0xc00000bb;
    private static final int COPY_FILE_FAIL_IF_EXISTS = 1;
    private static final int COPY_FILE_COPY_SYMLINK = 0x800;
    private static final int COPY_FILE_NO_BUFFERING = 0x1000;
    private static final int SECURITY_INFORMATION = 7;

    interface CopyProgress extends StdCallLibrary.StdCallCallback {
        int invoke(long size, long transferred, long streamSize, long streamBytes, int stream, int reason,
                   Pointer source, Pointer target, Pointer data);
    }

    interface Kernel extends StdCallLibrary {
        Pointer CreateFileW(WString path, int access, int sharing, Pointer security, int creation, int flags, Pointer template);
        boolean GetFileInformationByHandle(Pointer handle, Pointer result);
        boolean GetFileInformationByHandleEx(Pointer handle, int informationClass, Pointer result, int size);
        boolean GetVolumeInformationByHandleW(Pointer handle, Pointer name, int nameLength, Pointer serial,
                                               Pointer maximumComponentLength, Pointer flags, char[] system, int systemLength);
        int GetDriveTypeW(WString root);
        boolean CopyFileExW(WString source, WString target, CopyProgress progress, Pointer data, Pointer cancel, int flags);
        boolean SetFileInformationByHandle(Pointer handle, int informationClass, Pointer info, int size);
        boolean CloseHandle(Pointer handle);
    }

    interface Nt extends StdCallLibrary {
        int NtSetInformationFile(Pointer handle, Pointer ioStatus, Pointer info, int length, int informationClass);
        int NtCreateFile(PointerByReference handle, int access, Pointer attributes, Pointer ioStatus,
                         Pointer allocation, int fileAttributes, int sharing, int disposition, int options,
                         Pointer eaBuffer, int eaLength);
        int RtlNtStatusToDosError(int status);
    }

    interface Security extends StdCallLibrary {
        boolean GetFileSecurityW(WString path, int requested, Pointer descriptor, int length, IntByReference needed);
        boolean SetFileSecurityW(WString path, int requested, Pointer descriptor);
    }

    private static final class Library {
        static final Nt NTDLL = Native.load("ntdll", Nt.class);
        static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
    }

    private static final class SecurityLibrary {
        static final Security ADVAPI = Native.load("advapi32", Security.class);
    }

    record ObjectId(long volume, long low, long high) { }
    record Id(long volume, long low, long high, long creationTime) {
        ObjectId objectId() { return new ObjectId(volume, low, high); }
    }

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
            ObjectId object = Win32FileHandles.objectId(pointer, path);
            try (Memory basic = new Memory(52)) {
                if (!Library.KERNEL.GetFileInformationByHandle(pointer, basic)) {
                    throw failure("无法读取文件属性", path);
                }
                Id id = new Id(object.volume(), object.low(), object.high(), fileTime(basic, 4));
                long size = (Integer.toUnsignedLong(basic.getInt(32)) << 32) | Integer.toUnsignedLong(basic.getInt(36));
                return new Info(id, basic.getInt(0), size, fileTime(basic, 20), basic.getInt(40));
            }
        }

        ObjectId objectId() throws IOException { return Win32FileHandles.objectId(pointer, path); }

        Path path() { return path; }

        void deleteOnClose() throws IOException {
            try (Memory flags = new Memory(Integer.BYTES)) {
                // Delete only the opened entry, allowing deletion of read-only copies without changing their flags.
                flags.setInt(0, 0x13);
                if (!Library.KERNEL.SetFileInformationByHandle(pointer, FILE_DISPOSITION_INFO_EX, flags, (int) flags.size())) {
                    throw failure("无法清理暂存项目", path);
                }
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
            long start = CopyDiagnostics.begin();
            try (Memory basic = new Memory(52)) {
                if (!Library.KERNEL.GetFileInformationByHandle(pointer, basic)) {
                    throw failure("无法读取目录属性", path);
                }
                return basic.getInt(0);
            } finally {
                CopyDiagnostics.end(ATTRIBUTES, start);
            }
        }

        @Override
        public void close() throws IOException {
            if (pointer != null) {
                Pointer closing = pointer;
                pointer = null;
                long start = CopyDiagnostics.begin();
                boolean closed = Library.KERNEL.CloseHandle(closing);
                CopyDiagnostics.end(CLOSE_HANDLE, start);
                if (!closed) {
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
        long start = CopyDiagnostics.begin();
        Pointer handle = Library.KERNEL.CreateFileW(windowsPath(path), access, sharing, null, OPEN_EXISTING,
                BACKUP_SEMANTICS | OPEN_REPARSE_POINT, null);
        CopyDiagnostics.end(OPEN_PATH, start);
        if (handle == null || Pointer.nativeValue(handle) == -1L) {
            throw failure("无法打开项目", path);
        }
        return new Handle(path, handle);
    }

    static Handle createDirectory(Handle parent, String name) throws IOException {
        return relative(parent, name, READ_ATTRIBUTES | DELETE_ACCESS | TRAVERSE | SYNCHRONIZE, 2, 1);
    }

    static Handle openRelative(Handle parent, String name, int access) throws IOException {
        return relative(parent, name, access | SYNCHRONIZE, 1, 0);
    }

    private static Handle relative(Handle parent, String name, int access, int disposition, int type) throws IOException {
        long start = CopyDiagnostics.begin();
        Path path = parent.path.resolve(name);
        byte[] filename = name.getBytes(UTF_16LE);
        var output = new PointerByReference();
        // This adapter runs only on Windows x64. The buffers hold UNICODE_STRING, OBJECT_ATTRIBUTES and IO_STATUS_BLOCK.
        try (Memory text = new Memory(filename.length + 2L); Memory unicode = new Memory(16);
             Memory attributes = new Memory(48); Memory io = new Memory(16)) {
            text.clear();
            unicode.clear();
            attributes.clear();
            io.clear();
            text.write(0, filename, 0, filename.length);
            unicode.setShort(0, (short) filename.length);
            unicode.setShort(2, (short) text.size());
            unicode.setPointer(8, text);
            attributes.setInt(0, (int) attributes.size());
            attributes.setPointer(8, parent.pointer);
            attributes.setPointer(16, unicode);
            attributes.setInt(24, 0x40);  // OBJ_CASE_INSENSITIVE
            int options = OPEN_REPARSE_POINT | 0x20 | type;  // FILE_SYNCHRONOUS_IO_NONALERT
            int status = Library.NTDLL.NtCreateFile(output, access, attributes, io, null, 0,
                    SHARE_READ_WRITE, disposition, options, null, 0);
            if (status != 0) {
                throw new Failure("无法打开暂存目录项", path, Library.NTDLL.RtlNtStatusToDosError(status));
            }
            return new Handle(path, output.getValue());
        } finally {
            CopyDiagnostics.end(OPEN_RELATIVE, start);
        }
    }

    static ObjectId objectId(Pointer pointer, Path path) throws IOException {
        long start = CopyDiagnostics.begin();
        try (Memory fileId = new Memory(24)) {
            if (!Library.KERNEL.GetFileInformationByHandleEx(pointer, FILE_ID_INFO, fileId, (int) fileId.size())) {
                throw failure("无法读取文件 ID", path);
            }
            return new ObjectId(fileId.getLong(0), fileId.getLong(8), fileId.getLong(16));
        } finally {
            CopyDiagnostics.end(FILE_ID, start);
        }
    }

    static void copyFile(Path source, Path target, long size, CopyProgress progress) throws IOException {
        int flags = COPY_FILE_FAIL_IF_EXISTS | COPY_FILE_COPY_SYMLINK;
        if (size > 300L * 1024 * 1024) { flags |= COPY_FILE_NO_BUFFERING; }
        long start = CopyDiagnostics.begin();
        boolean submitted = Library.KERNEL.CopyFileExW(windowsPath(source), windowsPath(target), progress, null, null, flags);
        CopyDiagnostics.end(COPY_DATA, start);
        if (!submitted) {
            throw failure("无法复制到暂存区", source);
        }
    }

    static final class SecurityDescriptors implements AutoCloseable {
        private final IntByReference length = new IntByReference();
        private Memory descriptor;

        void copy(Path source, Path target) throws IOException {
            if (descriptor == null) { descriptor = new Memory(1024); }
            WString from = windowsPath(source);
            length.setValue(0);
            if (!readSecurity(from, descriptor, (int) descriptor.size(), length)) {
                int required = length.getValue();
                if (Native.getLastError() != 122 || required <= descriptor.size()) {
                    throw failure("无法读取安全属性", source);
                }
                descriptor.close();
                descriptor = new Memory(required);
                if (!readSecurity(from, descriptor, required, length)) {
                    throw failure("无法读取安全属性", source);
                }
            }
            WString to = windowsPath(target);
            if (!writeSecurity(to, SECURITY_INFORMATION, descriptor)) {
                var error = failure("无法完整保留所有者、组和访问控制列表", target);
                if (!writeSecurity(to, 4, descriptor)) {
                    error.addSuppressed(failure("无法保留访问控制列表", target));
                }
                throw error;
            }
        }

        @Override
        public void close() {
            if (descriptor != null) { descriptor.close(); }
        }
    }

    private static boolean readSecurity(WString from, Pointer descriptor, int capacity, IntByReference length) {
        long start = CopyDiagnostics.begin();
        boolean read = SecurityLibrary.ADVAPI.GetFileSecurityW(from, SECURITY_INFORMATION, descriptor, capacity, length);
        CopyDiagnostics.end(SECURITY_READ, start);
        return read;
    }

    private static boolean writeSecurity(WString to, int information, Pointer descriptor) {
        long start = CopyDiagnostics.begin();
        boolean written = SecurityLibrary.ADVAPI.SetFileSecurityW(to, information, descriptor);
        CopyDiagnostics.end(SECURITY_WRITE, start);
        return written;
    }

    private static WString windowsPath(Path path) {
        String absolute = path.toAbsolutePath().normalize().toString();
        String extended = absolute.startsWith("\\\\") ? "\\\\?\\UNC\\" + absolute.substring(2) : "\\\\?\\" + absolute;
        return new WString(extended);
    }

    static void rename(Handle source, Handle targetParent, String name) throws IOException {
        Path target = targetParent.path.resolve(name);
        boolean changeCase = changesCaseOfSameEntry(source.path, target);
        byte[] filename = name.getBytes(UTF_16LE);
        int parentOffset = Native.POINTER_SIZE;
        int lengthOffset = 2 * Native.POINTER_SIZE;
        int nameOffset = lengthOffset + Integer.BYTES;
        int size = nameOffset + filename.length + Character.BYTES + Native.POINTER_SIZE;
        try (Memory info = new Memory(size); Memory statusBlock = new Memory(2L * Native.POINTER_SIZE)) {
            info.clear();
            statusBlock.clear();
            // Extended no-replacement flags. A verified case change uses the same entry.
            info.setInt(0, changeCase ? 0 : FILE_RENAME_POSIX_SEMANTICS);
            info.setPointer(parentOffset, targetParent.pointer);
            info.setInt(lengthOffset, filename.length);
            info.write(nameOffset, filename, 0, filename.length);
            int status = Library.NTDLL.NtSetInformationFile(source.pointer, statusBlock, info, size,
                    changeCase ? FILE_RENAME_INFORMATION : FILE_RENAME_INFORMATION_EX);
            if (status != 0) {
                if (status == STATUS_INVALID_INFO_CLASS || status == STATUS_NOT_IMPLEMENTED || status == STATUS_NOT_SUPPORTED) {
                    throw new IOException("此位置不支持严格的无覆盖改名：" + target);
                }
                throw new Failure("无法按句柄重命名 (NTSTATUS 0x" + Integer.toHexString(status) + ")",
                        target, Library.NTDLL.RtlNtStatusToDosError(status));
            }
        }
    }

    static boolean changesCaseOfSameEntry(Path source, Path target) throws IOException {
        String from = source.getFileName().toString();
        String to = target.getFileName().toString();
        if (!source.getParent().equals(target.getParent()) || from.equals(to) || !from.equalsIgnoreCase(to)) {
            return false;
        }
        try {
            // Compare the stored names, including case, to distinguish two hard links to the same ID.
            return source.toRealPath(NOFOLLOW_LINKS).toString().equals(target.toRealPath(NOFOLLOW_LINKS).toString());
        } catch (NoSuchFileException absent) {
            return false;
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

package edu.files;

import com.sun.jna.Native;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.SequencedMap;

import static edu.files.Win32FileHandles.*;

final class NtfsFileOperations {
    private static final long WINDOWS_EPOCH = 116_444_736_000_000_000L;
    private static final long TICKS_PER_SECOND = 10_000_000L;

    record Directory(Path path, Id id) { }

    record Item(Directory parent, String name, Id id, boolean directory) {
        Path path() { return parent.path().resolve(name); }
    }

    record Move(Path target, Item result) { }

    private static final class UnsupportedPath extends IOException {
        UnsupportedPath(Path path) { super("此位置不支持 NTFS 句柄操作：" + path); }
    }

    private static final class Availability {
        private static final boolean ENABLED = detect();

        private static boolean detect() {
            if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
                return false;
            }
            try {
                return Native.POINTER_SIZE == 8 && Win32FileHandles.available();
            } catch (LinkageError unavailable) {
                return false;
            }
        }
    }

    private NtfsFileOperations() { }

    static Item capture(LocalFiles.Entry selected) throws IOException {
        if (!Availability.ENABLED || selected.link() || selected.other()) { return null; }
        Path path = selected.path().toAbsolutePath().normalize();
        if (path.getParent() == null) { return null; }
        try (var directories = new PinnedDirectories()) {
            Handle parent = directories.openDirectory(path.getParent());
            try (Handle item = open(path, READ_ATTRIBUTES, SHARE_ALL)) {
                Info info = item.info();
                if (info.reparsePoint()) { return null; }
                if (selected.directory() != info.directory()
                        || !selected.created().equals(time(info.id().creationTime()))
                        || !selected.modified().equals(time(info.modifiedTime()))
                        || (!info.directory() && selected.size() != info.size())) {
                    throw changed(path);
                }
                return new Item(new Directory(path.getParent(), parent.info().id()), path.getFileName().toString(),
                        info.id(), info.directory());
            }
        } catch (UnsupportedPath unavailable) {
            return null;
        } catch (Failure failure) {
            throw mappedFailure(path, failure);
        }
    }

    static Directory captureDirectory(Path path) throws IOException {
        if (!Availability.ENABLED) { return null; }
        Path absolute = path.toAbsolutePath().normalize();
        try (var directories = new PinnedDirectories()) {
            return new Directory(absolute, directories.openDirectory(absolute).info().id());
        } catch (UnsupportedPath unavailable) {
            return null;
        } catch (Failure failure) {
            throw mappedFailure(path, failure);
        }
    }

    static boolean sameVolume(Item source, Directory destination) {
        return destination != null && source.id().volume() == destination.id().volume();
    }

    static void verify(Item selected) throws IOException {
        try (var directories = new PinnedDirectories()) {
            directories.verify(selected.parent());
            try (var item = open(selected.path(), READ_ATTRIBUTES, SHARE_ALL)) {
                verifyItem(selected, item);
            }
        } catch (Failure failure) {
            throw mappedFailure(selected.path(), failure);
        }
    }

    static LocalFiles.Entry copy(Item selected, Path directory, Directory target) throws IOException {
        try (var directories = new PinnedDirectories()) {
            directories.verify(selected.parent());
            if (target != null) { directories.verify(target); }
            try (var source = open(selected.path(), READ_ATTRIBUTES | READ_DATA, SHARE_READ_WRITE)) {
                verifyItem(selected, source);
                return LocalFiles.copy(selected.path(), directory);
            }
        } catch (Failure failure) {
            throw mappedFailure(selected.path(), failure);
        }
    }

    static Move move(Item selected, Directory destination, String name) throws IOException {
        Path target = LocalFiles.childNamed(destination.path(), name);
        if (!sameVolume(selected, destination)) { throw new UnsupportedPath(target); }
        if (selected.directory() && destination.path().startsWith(selected.path())) {
            throw new IOException("目标文件夹位于源文件夹内：" + destination.path());
        }
        boolean submitted = false;
        try (var directories = new PinnedDirectories()) {
            directories.verify(selected.parent());
            Handle parent = directories.verify(destination);
            try (var source = open(selected.path(), READ_ATTRIBUTES | DELETE_ACCESS, SHARE_READ_WRITE)) {
                verifyItem(selected, source);
                if (selected.parent().id().equals(destination.id()) && selected.name().equals(name)) {
                    return null;
                }
                try {
                    rename(source, parent, name);
                    submitted = true;
                } catch (Failure failure) {
                    if (failure.code() == 80 || failure.code() == 183) {
                        throw new FileAlreadyExistsException(target.toString());
                    }
                    if (failure.code() == 5 || failure.code() == 32 || failure.code() == 33) {
                        throw mappedFailure(target, failure);
                    }
                    throw LocalFiles.uncertain("无法确认句柄改名结果", failure, selected.path(), target);
                }
                try {
                    var result = new Item(destination, name, source.info().id(), selected.directory());
                    return new Move(target, result);
                } catch (IOException verificationFailure) {
                    // The native call succeeded. If its receipt cannot be read, keep the write without undo.
                    return new Move(target, null);
                }
            }
        } catch (Failure failure) {
            if (submitted) { throw LocalFiles.uncertain("改名后无法释放句柄", failure, selected.path(), target); }
            throw mappedFailure(selected.path(), failure);
        }
    }

    private static void verifyItem(Item selected, Handle item) throws IOException {
        Info current = item.info();
        if (current.reparsePoint() || current.directory() != selected.directory() || !current.id().equals(selected.id())) {
            throw changed(selected.path());
        }
    }

    private static IOException changed(Path path) {
        return new IOException("所选项目或所在目录已变化，请刷新后重新选择：" + path);
    }

    private static IOException mappedFailure(Path path, Failure failure) {
        IOException mapped = switch (failure.code()) {
            case 2, 3 -> new NoSuchFileException(path.toString());
            case 5, 32, 33 -> new AccessDeniedException(path.toString(), null, failure.getMessage());
            default -> failure;
        };
        if (mapped != failure) { mapped.initCause(failure); }
        return mapped;
    }

    private static FileTime time(long ticks) {
        long unixTicks = ticks - WINDOWS_EPOCH;
        return FileTime.from(Instant.ofEpochSecond(Math.floorDiv(unixTicks, TICKS_PER_SECOND),
                Math.floorMod(unixTicks, TICKS_PER_SECOND) * 100));
    }

    private static final class PinnedDirectories implements AutoCloseable {
        private final SequencedMap<Path, Handle> handles = new LinkedHashMap<>();

        Handle verify(Directory expected) throws IOException {
            Handle current = openDirectory(expected.path());
            if (!current.info().id().equals(expected.id())) { throw changed(expected.path()); }
            return current;
        }

        Handle openDirectory(Path path) throws IOException {
            Path root = path.getRoot();
            if (root == null || !root.toString().matches("(?i)[a-z]:\\\\") || !fixedDrive(root)) {
                throw new UnsupportedPath(path);
            }
            boolean firstVisit = !handles.containsKey(root);
            Handle current = pin(root);
            if (firstVisit && !"NTFS".equals(current.fileSystem())) { throw new UnsupportedPath(path); }
            Path part = root;
            for (Path segment : path) {
                part = part.resolve(segment);
                current = pin(part);
            }
            return current;
        }

        private Handle pin(Path path) throws IOException {
            Handle current = handles.get(path);
            if (current == null) {
                current = open(path, READ_ATTRIBUTES | TRAVERSE, SHARE_READ_WRITE);
                handles.put(path, current);
                int flags = current.attributes();
                if ((flags & DIRECTORY_ATTRIBUTE) == 0 || (flags & REPARSE_POINT_ATTRIBUTE) != 0) {
                    throw new UnsupportedPath(path);
                }
            }
            return current;
        }

        @Override
        public void close() throws IOException {
            IOException error = null;
            for (Handle handle : handles.sequencedValues().reversed()) {
                try {
                    handle.close();
                } catch (IOException failure) {
                    if (error == null) { error = failure; }
                    else { error.addSuppressed(failure); }
                }
            }
            if (error != null) { throw error; }
        }
    }
}

package edu.files;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static edu.files.FileOperationTask.Phase.*;
import static edu.files.Win32FileHandles.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;

final class StagedCopy {
    record Result(Path path, BasicFileAttributes attributes) { }

    private StagedCopy() { }

    static Result copy(Path source, Path target, boolean sameFolder, FileOperationTask task) throws IOException {
        task.checkCancellation();
        // Check before creating staging so a missing source leaves the destination untouched.
        var sourceAttributes = Files.readAttributes(source, BasicFileAttributes.class, NOFOLLOW_LINKS);
        Result result = null;
        try (var pins = new NtfsFileOperations.PinnedDirectories()) {
            Handle parent = NtfsFileOperations.copyTarget(pins, target.getParent());
            if (parent == null) { return portableCopy(source, target, sameFolder, sourceAttributes, task); }
            try (var stage = new Stage(parent, task)) {
                result = stage.copy(source, target, sameFolder);
            }
        } catch (IOException failure) {
            if (result == null) { throw failure; }
            task.issue(result.path(), "无法关闭复制目标使用的句柄", failure);
        }
        return result;
    }

    private static Result portableCopy(Path source, Path target, boolean sameFolder,
                                       BasicFileAttributes sourceAttributes, FileOperationTask task) throws IOException {
        task.unknownSize();
        Path staged;
        synchronized (task.staging()) {
            if (sourceAttributes.isDirectory()) {
                staged = Files.createTempDirectory(target.getParent(), ".file-manager-");
                task.staging().register(staged);
            } else {
                staged = target.getParent().resolve(".file-manager-" + UUID.randomUUID());
            }
        }
        boolean published = false;
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    task.checkCancellation();
                    task.preparing(directory);
                    rejectJunction(directory, attrs);
                    if (!source.equals(directory)) { Files.createDirectory(staged.resolve(source.relativize(directory))); }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    task.checkCancellation();
                    task.copying(file);
                    task.checkCancellation();
                    Files.copy(file, staged.resolve(source.relativize(file)), NOFOLLOW_LINKS, COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
            task.phase(ATTRIBUTES);
            var attrs = Files.readAttributes(staged, BasicFileAttributes.class, NOFOLLOW_LINKS);
            Path result = LocalFiles.publishCopy(target, attrs.isDirectory(), sameFolder, path -> {
                task.beforeCommit(PUBLISHING);
                return LocalFiles.moveWithoutReplacing(staged, path);
            });
            published = true;
            return new Result(result, attrs);
        } finally {
            try {
                task.phase(CLEANUP);
                if (!published && !Files.notExists(staged, NOFOLLOW_LINKS)) {
                    task.issue(staged, "当前文件系统无法按已验证的句柄清理暂存项目，已保留供核查", null);
                }
            } finally {
                task.staging().forget(staged);
            }
        }
    }

    private static void rejectJunction(Path directory, BasicFileAttributes attrs) throws IOException {
        if (attrs.isOther()) { throw new IOException("无法复制目录联接：" + directory); }
    }

    private static final class StagedEntry {
        final String name;
        final boolean directory;
        final Map<String, StagedEntry> children = new LinkedHashMap<>();
        ObjectId identity;
        boolean owned;

        StagedEntry(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }

        void verify(Handle observed) throws IOException {
            if (identity == null || !identity.equals(observed.objectId())) {
                throw new IOException("暂存对象身份变化：" + observed.path());
            }
            if (directory) {
                int attributes = observed.attributes();
                if ((attributes & DIRECTORY_ATTRIBUTE) == 0 || (attributes & REPARSE_POINT_ATTRIBUTE) != 0) {
                    throw new IOException("暂存目录类型变化：" + observed.path());
                }
            }
        }
    }

    private record OpenDirectory(Handle handle, StagedEntry entry, Handle source) { }

    private static final class Stage implements AutoCloseable {
        private final FileOperationTask task;
        private final Handle parent;
        private final Handle root;
        private final StagedEntry entries;
        private final ArrayDeque<OpenDirectory> opened = new ArrayDeque<>();
        private final SecurityDescriptors security = new SecurityDescriptors();
        private boolean published;

        Stage(Handle parent, FileOperationTask task) throws IOException {
            this.parent = parent;
            this.task = task;
            String name = ".file-manager-" + UUID.randomUUID();
            synchronized (task.staging()) {
                root = createDirectory(parent, name);
                try {
                    task.staging().register(root);
                } catch (IOException failure) {
                    try (root) { root.deleteOnClose(); }
                    catch (IOException cleanup) {
                        failure.addSuppressed(cleanup);
                        task.issue(root.path(), "无法清理身份读取失败的暂存目录", cleanup);
                    }
                    throw failure;
                }
            }
            entries = new StagedEntry(name, true);
            opened.addLast(new OpenDirectory(root, entries, null));
        }

        Result copy(Path source, Path target, boolean sameFolder) throws IOException {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    task.checkCancellation();
                    task.preparing(directory);
                    rejectJunction(directory, attrs);
                    openDirectory(directory);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    task.checkCancellation();
                    copyEntry(file, attrs);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    try {
                        closeDirectory(opened.removeLast());
                    } catch (IOException closeFailure) {
                        if (error == null) { throw closeFailure; }
                        error.addSuppressed(closeFailure);
                    }
                    if (error != null) { throw error; }
                    task.checkCancellation();
                    return FileVisitResult.CONTINUE;
                }
            });
            task.phase(ATTRIBUTES);
            task.checkCancellation();
            StagedEntry item = entries.children.values().iterator().next();
            Result result = null;
            try (Handle staged = openRelative(root, item.name, READ_ATTRIBUTES | DELETE_ACCESS)) {
                item.verify(staged);
                long checking = CopyDiagnostics.begin();
                try {
                    verifyChildren(item, staged);
                } finally {
                    CopyDiagnostics.end(CopyDiagnostics.Part.TREE_CHECK, checking);
                }
                var attrs = Files.readAttributes(staged.path(), BasicFileAttributes.class, NOFOLLOW_LINKS);
                Path path = LocalFiles.publishCopy(target, item.directory, sameFolder, candidate -> {
                    task.beforeCommit(PUBLISHING);
                    try {
                        rename(staged, parent, candidate.getFileName().toString());
                    } catch (Failure failure) {
                        if (failure.code() == 80 || failure.code() == 183) {
                            throw new FileAlreadyExistsException(candidate.toString());
                        }
                        if (failure.code() == 5 || failure.code() == 32 || failure.code() == 33) { throw failure; }
                        throw LocalFiles.uncertain("无法确认副本发布结果", failure, source, candidate, staged.path());
                    }
                    published = true;
                    return candidate;
                });
                result = new Result(path, attrs);
            } catch (IOException failure) {
                if (result == null) { throw failure; }
                task.issue(result.path(), "已发布副本，但无法释放提交句柄", failure);
            }
            return result;
        }

        private void openDirectory(Path source) throws IOException {
            var current = opened.getLast();
            Handle pinned = open(source, READ_ATTRIBUTES | TRAVERSE, SHARE_READ_WRITE);
            try {
                int flags = pinned.attributes();
                if ((flags & DIRECTORY_ATTRIBUTE) == 0 || (flags & REPARSE_POINT_ATTRIBUTE) != 0) {
                    throw new IOException("来源已变化或为目录联接：" + source);
                }
                String name = source.getFileName().toString();
                Handle target = createDirectory(current.handle(), name);
                var child = new StagedEntry(name, true);
                current.entry().children.put(name, child);
                opened.addLast(new OpenDirectory(target, child, pinned));
                child.owned = true;
                child.identity = target.objectId();
            } catch (IOException failure) {
                if (opened.getLast().source() != pinned) {
                    try { pinned.close(); }
                    catch (IOException closeError) { failure.addSuppressed(closeError); }
                }
                throw failure;
            }
        }

        private void copyEntry(Path source, BasicFileAttributes attributes) throws IOException {
            var current = opened.getLast();
            String name = source.getFileName().toString();
            var entry = new StagedEntry(name, false);
            current.entry().children.put(name, entry);
            Path target = current.handle().path().resolve(name);
            task.copying(source);
            task.checkCancellation();
            if (!attributes.isRegularFile()) {
                task.unknownSize();
                Files.copy(source, target, NOFOLLOW_LINKS, COPY_ATTRIBUTES);
                try (Handle copied = openRelative(current.handle(), name, READ_ATTRIBUTES)) {
                    entry.identity = copied.objectId();
                }
                return;
            }
            try (Handle pinned = open(source, READ_ATTRIBUTES | READ_DATA, SHARE_READ_WRITE)) {
                int flags = pinned.attributes();
                if ((flags & (DIRECTORY_ATTRIBUTE | REPARSE_POINT_ATTRIBUTE)) != 0) {
                    throw new IOException("来源已变化：" + source);
                }
                var progress = new Progress(task, entry, target);
                try {
                    copyFile(source, target, attributes.size(), progress);
                } catch (IOException failure) {
                    if (progress.error != null) { failure.addSuppressed(progress.error); }
                    if (progress.cancelled) { throw new FileOperationTask.Cancelled(); }
                    throw failure;
                }
                task.checkCancellation();
                if (!entry.owned) { throw new IOException("系统未提供暂存文件的身份：" + target); }
                task.phase(ATTRIBUTES);
                try (Handle copied = openRelative(current.handle(), name, READ_ATTRIBUTES)) {
                    entry.verify(copied);
                    try {
                        security.copy(source, target);
                    } catch (IOException metadata) {
                        task.issue(source, "无法完整复制安全属性", metadata);
                    }
                }
            }
        }

        private void verifyChildren(StagedEntry expected, Handle current) throws IOException {
            if (!expected.directory) { return; }
            try (var paths = Files.newDirectoryStream(current.path())) {
                for (Path path : paths) {
                    task.checkCancellation();
                    var child = expected.children.get(path.getFileName().toString());
                    if (child == null) { throw new IOException("暂存区发现其它项目：" + path); }
                }
            }
            for (var child : expected.children.values()) {
                task.checkCancellation();
                try (Handle actual = openRelative(current, child.name, READ_ATTRIBUTES | TRAVERSE)) {
                    child.verify(actual);
                    verifyChildren(child, actual);
                }
            }
        }

        @Override
        public void close() {
            long start = CopyDiagnostics.begin();
            task.phase(CLEANUP);
            while (opened.size() > 1) {
                try { closeDirectory(opened.removeLast()); }
                catch (IOException failure) { task.issue(root.path(), "无法关闭暂存目录句柄", failure); }
            }
            try (root; security) {
                if (!published) { removeChildren(entries, root); }
                root.deleteOnClose();
            } catch (IOException failure) {
                task.issue(root.path(), "暂存目录可能仍有残留", failure);
            } finally {
                task.staging().forget(root.path());
                CopyDiagnostics.end(CopyDiagnostics.Part.CLEANUP, start);
            }
        }

        private void removeChildren(StagedEntry expected, Handle current) {
            for (var child : expected.children.values()) {
                Path path = current.path().resolve(child.name);
                try (Handle actual = openRelative(current, child.name, READ_ATTRIBUTES | TRAVERSE | DELETE_ACCESS)) {
                    if (!child.owned) { throw new IOException("无法确认暂存对象的所有权：" + path); }
                    child.verify(actual);
                    if (child.directory) { removeChildren(child, actual); }
                    actual.deleteOnClose();
                } catch (Failure failure) {
                    if (failure.code() != 2 && failure.code() != 3) {
                        task.issue(path, "暂存项目可能仍有残留", failure);
                    }
                } catch (IOException failure) {
                    task.issue(path, "已保留无法确认的暂存项目", failure);
                }
            }
        }

        private static void closeDirectory(OpenDirectory directory) throws IOException {
            try (Handle source = directory.source(); Handle target = directory.handle()) {
                // Close both verified directory handles even when one close fails.
            }
        }
    }

    private static final class Progress implements CopyProgress {
        private final FileOperationTask task;
        private final StagedEntry entry;
        private final Path target;
        private long transferred;
        IOException error;
        boolean cancelled;

        Progress(FileOperationTask task, StagedEntry entry, Path target) {
            this.task = task;
            this.entry = entry;
            this.target = target;
        }

        @Override
        public int invoke(long size, long bytes, long streamSize, long streamBytes, int stream, int reason,
                          com.sun.jna.Pointer source, com.sun.jna.Pointer copied, com.sun.jna.Pointer data) {
            CopyDiagnostics.count(CopyDiagnostics.Part.PROGRESS_CALLBACK);
            try {
                if (!entry.owned) {
                    entry.identity = objectId(copied, target);
                    entry.owned = true;
                }
                task.reportSize(size);
                if (bytes > transferred) {
                    task.transfer(bytes - transferred);
                    transferred = bytes;
                }
                cancelled = task.cancellationRequested();
                // PROGRESS_STOP leaves the temporary file for identity-checked cleanup.
                return cancelled ? 2 : 0;
            } catch (IOException failure) {
                error = failure;
                return 2;
            }
        }
    }
}

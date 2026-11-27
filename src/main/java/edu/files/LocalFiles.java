package edu.files;

import java.awt.Desktop;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

final class LocalFiles {
    private static final int SEARCH_LIMIT = 2_000;
    private static final LinkOption NO_LINKS = LinkOption.NOFOLLOW_LINKS;
    private static final Comparator<Entry> ORDER = Comparator.comparing(Entry::directory).reversed()
            .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(entry -> entry.path().toString(), String.CASE_INSENSITIVE_ORDER);

    record Visibility(boolean hidden, boolean protectedSystem) {
        boolean includes(Path path, BasicFileAttributes attributes) {
            if (attributes instanceof DosFileAttributes dos) {
                if (dos.isHidden() && dos.isSystem()) {
                    return protectedSystem;
                }
                return hidden || !dos.isHidden();
            }
            return hidden || !path.getFileName().toString().startsWith(".");
        }
    }

    private static final Class<? extends BasicFileAttributes> ATTRIBUTES =
            File.separatorChar == '\\'
                    ? DosFileAttributes.class : BasicFileAttributes.class;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");

    record Entry(Path path, boolean directory, boolean link, long size, FileTime modified,
                 FileTime created, Object fileKey, boolean other) {
        boolean hasIdentity() {
            return fileKey != null && !link && !other;
        }

        boolean sameObject(Entry current) {
            return hasIdentity() && current.hasIdentity() && directory == current.directory
                    && fileKey.equals(current.fileKey) && created.equals(current.created);
        }

        boolean sameMetadata(Entry current) {
            return directory == current.directory && link == current.link && other == current.other
                    && size == current.size && modified.equals(current.modified) && created.equals(current.created)
                    && Objects.equals(fileKey, current.fileKey);
        }

        String name() {
            return path.getFileName() == null ? path.toString() : path.getFileName().toString();
        }

        String type() {
            if (link) {
                return "链接";
            }
            if (directory) {
                return "文件夹";
            }
            int dot = name().lastIndexOf('.');
            return dot > 0 && dot < name().length() - 1
                    ? name().substring(dot + 1).toUpperCase(Locale.ROOT) + " 文件"
                    : "文件";
        }
    }

    record Listing(List<Entry> entries, int unreadable, boolean limited) {
        Listing {
            entries.sort(ORDER);
            entries = List.copyOf(entries);
        }
    }

    private LocalFiles() {
    }

    static Listing readDirectory(Path directory, Visibility visibility, BooleanSupplier cancelled) throws IOException {
        return readDirectory(directory, visibility, cancelled, path -> false);
    }

    static Listing readDirectory(Path directory, Visibility visibility, BooleanSupplier cancelled,
                                 Predicate<Path> excluded) throws IOException {
        List<Entry> entries = new ArrayList<>();
        int unreadable = 0;
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                checkCancelled(cancelled);
                if (excluded.test(child)) { continue; }
                try {
                    BasicFileAttributes attributes = Files.readAttributes(child, ATTRIBUTES, NO_LINKS);
                    if (visibility.includes(child, attributes)) {
                        entries.add(entry(child, attributes));
                    }
                } catch (IOException exception) {
                    unreadable++;
                }
            }
        } catch (DirectoryIteratorException exception) {
            throw exception.getCause();
        }
        return new Listing(entries, unreadable, false);
    }

    static List<Path> recentlyModifiedFolders(Collection<Path> knownFolders, Map<Path, FileTime> recorded,
                                              List<Path> indexedItems, Visibility visibility, BooleanSupplier cancelled) {
        Map<Path, FileTime> activity = new HashMap<>();
        for (Path path : indexedItems) {
            checkCancelled(cancelled);
            BasicFileAttributes attributes = visibleAttributes(path, visibility);
            if (attributes != null) {
                if (attributes.isDirectory()) {
                    addFolderActivity(activity, path, attributes.lastModifiedTime());
                } else if (attributes.isRegularFile()) {
                    addFolderActivity(activity, path.getParent(), attributes.lastModifiedTime());
                }
            }
        }
        Set<Path> folders = new LinkedHashSet<>(knownFolders);
        folders.addAll(recorded.keySet());
        for (Path folder : folders) {
            checkCancelled(cancelled);
            BasicFileAttributes attributes = visibleAttributes(folder, visibility);
            if (attributes == null || !attributes.isDirectory()) { continue; }
            addFolderActivity(activity, folder, attributes.lastModifiedTime());
            FileTime recordedTime = recorded.get(folder);
            if (recordedTime != null) { addFolderActivity(activity, folder, recordedTime); }
            try (var children = Files.newDirectoryStream(folder)) {
                for (Path child : children) {
                    checkCancelled(cancelled);
                    BasicFileAttributes item = visibleAttributes(child, visibility);
                    if (item != null && item.isDirectory()) {
                        addFolderActivity(activity, child, item.lastModifiedTime());
                    } else if (item != null && item.isRegularFile()) {
                        addFolderActivity(activity, folder, item.lastModifiedTime());
                    }
                }
            } catch (IOException | DirectoryIteratorException | SecurityException exception) {
                // The folder can still contribute its own metadata when its contents are inaccessible.
            }
        }
        return activity.entrySet().stream()
                .sorted(Map.Entry.<Path, FileTime>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .filter(folder -> {
                    checkCancelled(cancelled);
                    var attributes = visibleAttributes(folder, visibility);
                    return attributes != null && attributes.isDirectory();
                }).toList();
    }

    private static void addFolderActivity(Map<Path, FileTime> activity, Path path, FileTime time) {
        if (path != null && path.getParent() != null) {
            activity.merge(path, time, (last, next) -> last.compareTo(next) >= 0 ? last : next);
        }
    }

    private static BasicFileAttributes visibleAttributes(Path path, Visibility visibility) {
        try {
            var attributes = Files.readAttributes(path, ATTRIBUTES, NO_LINKS);
            return path.getFileName() == null || visibility.includes(path, attributes) ? attributes : null;
        } catch (IOException | SecurityException exception) {
            return null;
        }
    }

    static Listing search(Path directory, String query, Visibility visibility, BooleanSupplier cancelled)
            throws IOException {
        return search(directory, query, visibility, cancelled, path -> false);
    }

    static Listing search(Path directory, String query, Visibility visibility, BooleanSupplier cancelled,
                          Predicate<Path> excluded) throws IOException {
        String keyword = query.toLowerCase(Locale.ROOT);
        return scan(directory, entry -> entry.name().toLowerCase(Locale.ROOT).contains(keyword), visibility, cancelled, excluded);
    }

    static Listing images(Path directory, String keyword, Visibility visibility, BooleanSupplier cancelled)
            throws IOException {
        return images(directory, keyword, visibility, cancelled, path -> false);
    }

    static Listing images(Path directory, String keyword, Visibility visibility, BooleanSupplier cancelled,
                          Predicate<Path> excluded) throws IOException {
        return scan(directory, entry -> !entry.directory() && isImage(entry.path())
                && entry.name().toLowerCase(Locale.ROOT).contains(keyword), visibility, cancelled, excluded);
    }

    static boolean isImage(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static double imageAspectRatio(Path path) {
        try (var input = new FileImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (readers.hasNext()) {
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width > 0 && height > 0) { return (double) width / height; }
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException exception) {
            // An unreadable image still has a place in the gallery.
        }
        return 1;
    }

    private static Listing scan(Path directory, Predicate<Entry> filter, Visibility visibility,
                                BooleanSupplier cancelled, Predicate<Path> excluded) throws IOException {
        Path root = directory.toRealPath();
        List<Entry> matches = new ArrayList<>();

        class SearchVisitor extends SimpleFileVisitor<Path> {
            private int unreadable;
            private boolean limited;

            private FileVisitResult match(Path path, BasicFileAttributes attributes) throws IOException {
                checkCancelled(cancelled);
                if (excluded.test(path)) {
                    return attributes.isDirectory() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                BasicFileAttributes details = ATTRIBUTES == BasicFileAttributes.class
                        ? attributes : Files.readAttributes(path, ATTRIBUTES, NO_LINKS);
                if (!visibility.includes(path, details)) {
                    return attributes.isDirectory() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                Entry result = entry(directory.resolve(root.relativize(path)), details);
                if (filter.test(result)) {
                    if (matches.size() == SEARCH_LIMIT) {
                        limited = true;
                        return FileVisitResult.TERMINATE;
                    }
                    matches.add(result);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                checkCancelled(cancelled);
                if (root.equals(path)) {
                    return FileVisitResult.CONTINUE;
                }
                FileVisitResult result;
                try {
                    result = match(path, attributes);
                } catch (IOException exception) {
                    unreadable++;
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // Windows directory junctions can report both isDirectory() and isOther().
                return result == FileVisitResult.CONTINUE && attributes.isOther()
                        ? FileVisitResult.SKIP_SUBTREE : result;
            }

            @Override
            public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                try {
                    return match(path, attributes);
                } catch (IOException exception) {
                    return visitFileFailed(path, exception);
                }
            }

            @Override
            public FileVisitResult visitFileFailed(Path path, IOException exception) throws IOException {
                checkCancelled(cancelled);
                if (path.equals(root)) {
                    throw exception;
                }
                unreadable++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException exception) throws IOException {
                return exception == null ? FileVisitResult.CONTINUE : visitFileFailed(path, exception);
            }
        }

        SearchVisitor visitor = new SearchVisitor();
        Files.walkFileTree(root, visitor);
        return new Listing(matches, visitor.unreadable, visitor.limited);
    }

    static Entry describe(Path path) throws IOException {
        return entry(path, Files.readAttributes(path, BasicFileAttributes.class, NO_LINKS));
    }

    static Entry verify(Entry expected, boolean confirmedCurrentPath) throws IOException {
        Path requested = expected.path();
        Path parent = requested.toAbsolutePath().getParent();
        Path actual = parent == null ? requested : parent.toRealPath().resolve(requested.getFileName());
        Entry current = describe(actual);
        if (expected.sameObject(current)
                || (confirmedCurrentPath && !expected.hasIdentity() && expected.sameMetadata(current))) {
            return current;
        }
        throw new IOException("项目已变化或无法确认身份，请刷新后重新选择：" + requested);
    }

    static Path childNamed(Path directory, String name) {
        Path child = Path.of(name);
        if (name.isBlank() || child.getRoot() != null || child.getNameCount() != 1
                || !name.equals(child.toString()) || name.equals(".") || name.endsWith(" ") || name.endsWith(".")) {
            throw new InvalidPathException(name, "请输入一个文件名，名称不能以空格或句点结尾");
        }
        return directory.resolve(child);
    }

    static void open(Path path) throws IOException {
        desktopFor(Desktop.Action.OPEN).open(path.toFile());
    }

    @FunctionalInterface
    interface RecycleBin { boolean moveToTrash(Path path) throws IOException; }

    static boolean desktopTrash(Path path) throws IOException {
        return desktopFor(Desktop.Action.MOVE_TO_TRASH).moveToTrash(path.toFile());
    }

    static void trash(Path path, RecycleBin recycleBin) throws IOException {
        boolean recycled;
        try {
            recycled = recycleBin.moveToTrash(path);
        } catch (IllegalArgumentException | SecurityException | UnsupportedOperationException exception) {
            // Desktop can reject a path that went away after verification.
            throw uncertain("无法确认回收结果", exception, path);
        } catch (IOException exception) {
            throw uncertain("无法确认回收结果", exception, path);
        }
        if (!recycled) {
            throw uncertain("无法确认回收结果", new IOException("系统未报告回收成功"), path);
        }
    }

    static void rename(Path source, Path target) throws IOException {
        if (!source.equals(target)) {
            moveWithoutReplacing(source, target);
        } else if (!source.toString().equals(target.toString()) && !source.toFile().renameTo(target.toFile())) {
            // Windows NIO treats case-only names as equal and returns without renaming.
            throw uncertain("无法确认改名结果", new IOException("无法更改名称的大小写"), source, target);
        }
    }

    static Entry copy(Path source, Path directory) throws IOException {
        return copy(source, directory, new FileOperationTask(FileOperationTask.Kind.COPY, List.of()));
    }

    static Entry copy(Path source, Path directory, FileOperationTask task) throws IOException {
        return copy(source, directory, null, false, task);
    }

    static Entry copy(Path source, Path directory, Win32FileHandles.Handle pinnedTarget, boolean keepBoth,
                      FileOperationTask task) throws IOException {
        Path destination = transferTarget(source, directory);
        boolean useCopyName = keepBoth || Files.isSameFile(source.toAbsolutePath().getParent(), destination.getParent());
        if (!useCopyName) {
            try {
                requireAvailable(destination);
            } catch (FileAlreadyExistsException occupied) {
                throw new TransferConflict.Collision(destination, TransferConflict.Stage.COPY_CHECK, occupied);
            }
        }
        var result = StagedCopy.copy(source, destination, useCopyName, pinnedTarget, task);
        return entry(result.path(), result.attributes());
    }

    @FunctionalInterface
    interface Publisher<T> { T publish(Path target) throws IOException; }

    static Path publishCopy(Path original, boolean directory, boolean useCopyName, Publisher<Path> publisher) throws IOException {
        if (useCopyName) { return publishSibling(original, directory, publisher); }
        try {
            return publisher.publish(original);
        } catch (FileAlreadyExistsException occupied) {
            throw new TransferConflict.Collision(original, TransferConflict.Stage.COPY_PUBLISH, occupied);
        }
    }

    static <T> T publishSibling(Path original, boolean directory, Publisher<T> publisher) throws IOException {
        String name = original.getFileName().toString();
        int split = extensionStart(name, directory);
        String stem = name.substring(0, split);
        String extension = name.substring(split);
        for (int number = 1; ; number++) {
            String suffix = number == 1 ? " - 副本" : " - 副本 (" + number + ")";
            Path candidate = original.resolveSibling(stem + suffix + extension);
            try {
                return publisher.publish(candidate);
            } catch (FileAlreadyExistsException occupied) {
                // The next candidate requires its own no-replacement commit.
            }
        }
    }

    static int extensionStart(String name, boolean directory) {
        int dot = name.lastIndexOf('.');
        return !directory && dot > 0 && dot < name.length() - 1 ? dot : name.length();
    }

    // Null indicates that the source already belongs to the destination directory.
    static Path move(Path source, Path directory) throws IOException {
        Path destination = transferTarget(source, directory);
        if (Files.isSameFile(source.toAbsolutePath().getParent(), destination.getParent())) {
            describe(source);
            return null;
        }
        return moveWithoutReplacing(source, destination);
    }

    static Path moveWithoutReplacing(Path source, Path target) throws IOException {
        requireAvailable(target);
        try {
            return Files.move(source, target);
        } catch (FileAlreadyExistsException conflict) {
            throw conflict;
        } catch (IOException | SecurityException failure) {
            throw uncertain("无法确认移动或发布结果", failure, source, target);
        }
    }

    static final class UncertainWrite extends IOException {
        private final List<Observation> observations;

        UncertainWrite(String message, Exception cause) { this(message, cause, List.of()); }

        UncertainWrite(String message, Exception cause, List<Observation> observations) {
            super(message, cause);
            this.observations = List.copyOf(observations);
        }

        List<Observation> observations() { return observations; }
    }

    static UncertainWrite uncertain(String message, Exception failure, Path... paths) {
        var observations = observe(List.of(paths));
        String reason = Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName());
        return new UncertainWrite(message + "。\n原因：" + reason
                + "\n检查时的路径状态：" + String.join("、", observations.stream().map(Observation::toString).toList()),
                failure, observations);
    }

    record Observation(Path path, String state) {
        @Override public String toString() { return path + "（" + state + "）"; }
    }

    static List<Observation> observe(List<Path> paths) {
        List<Observation> observations = new ArrayList<>();
        for (Path path : paths) {
            String state;
            try {
                describe(path);
                state = "存在";
            } catch (NoSuchFileException absent) {
                state = "不存在";
            } catch (IOException | SecurityException inaccessible) {
                state = "无法读取";
            }
            observations.add(new Observation(path, state));
        }
        return List.copyOf(observations);
    }

    static void requireAvailable(Path path) throws FileAlreadyExistsException {
        if (Files.exists(path, NO_LINKS)) {
            throw new FileAlreadyExistsException(path.toString());
        }
    }

    private static Path transferTarget(Path source, Path directory) throws IOException {
        Path targetDirectory = directory.toRealPath();
        Path destination = targetDirectory.resolve(source.getFileName());
        if (Files.isDirectory(source, NO_LINKS) && targetDirectory.startsWith(source.toRealPath())) {
            throw new IOException("目标文件夹位于源文件夹内");
        }
        return destination;
    }

    private static Desktop desktopFor(Desktop.Action action) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(action)) {
            throw new IOException("当前系统不支持此操作");
        }
        return Desktop.getDesktop();
    }

    private static Entry entry(Path path, BasicFileAttributes attributes) {
        return new Entry(path, attributes.isDirectory(), attributes.isSymbolicLink(),
                attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(),
                attributes.fileKey(), attributes.isOther());
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}

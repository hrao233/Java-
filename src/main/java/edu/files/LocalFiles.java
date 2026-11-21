package edu.files;

import java.awt.Desktop;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    record Entry(Path path, boolean directory, boolean link, long size, FileTime modified) {
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
        List<Entry> entries = new ArrayList<>();
        int unreadable = 0;
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                checkCancelled(cancelled);
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

    static Listing search(Path directory, String query, Visibility visibility, BooleanSupplier cancelled)
            throws IOException {
        String keyword = query.toLowerCase(Locale.ROOT);
        return scan(directory, entry -> entry.name().toLowerCase(Locale.ROOT).contains(keyword), visibility, cancelled);
    }

    static Listing images(Path directory, String keyword, Visibility visibility, BooleanSupplier cancelled)
            throws IOException {
        return scan(directory, entry -> !entry.directory() && isImage(entry.path())
                && entry.name().toLowerCase(Locale.ROOT).contains(keyword), visibility, cancelled);
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
                                BooleanSupplier cancelled) throws IOException {
        Path root = directory.toRealPath();
        List<Entry> matches = new ArrayList<>();

        class SearchVisitor extends SimpleFileVisitor<Path> {
            private int unreadable;
            private boolean limited;

            private FileVisitResult match(Path path, BasicFileAttributes attributes) throws IOException {
                checkCancelled(cancelled);
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

    static void trash(Path path) throws IOException {
        File file = path.toFile();
        if (!desktopFor(Desktop.Action.MOVE_TO_TRASH).moveToTrash(file)) {
            throw new IOException("无法移到回收站：" + path);
        }
    }

    static void rename(Path source, Path target) throws IOException {
        if (!source.equals(target)) {
            requireAvailable(target);
            Files.move(source, target);
        } else if (!source.toString().equals(target.toString()) && !source.toFile().renameTo(target.toFile())) {
            // Windows NIO treats case-only names as equal and returns without renaming.
            throw new FileSystemException(source.toString(), target.toString(), "无法更改名称的大小写");
        }
    }

    static void copy(Path source, Path directory) throws IOException {
        Path destination = transferTarget(source, directory);
        requireAvailable(destination);
        // Keep incomplete copies out of the destination name until the data is ready.
        Path staging = Files.createTempDirectory(destination.getParent(), ".file-manager-");
        Path stagedItem = staging.resolve(source.getFileName());
        try {
            copyTree(source, stagedItem);
            Files.move(stagedItem, destination);
        } catch (IOException | RuntimeException exception) {
            try {
                deleteStaging(staging);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        try {
            Files.delete(staging);
        } catch (IOException exception) {
            staging.toFile().deleteOnExit();
        }
    }

    static void move(Path source, Path directory) throws IOException {
        Path destination = transferTarget(source, directory);
        requireAvailable(destination);
        Files.move(source, destination);
    }

    private static void requireAvailable(Path path) throws FileAlreadyExistsException {
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

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (attributes.isOther()) {
                    throw new IOException("无法复制目录联接：" + directory);
                }
                Files.createDirectory(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), NO_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteStaging(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path path, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Desktop desktopFor(Desktop.Action action) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(action)) {
            throw new IOException("当前系统不支持此操作");
        }
        return Desktop.getDesktop();
    }

    private static Entry entry(Path path, BasicFileAttributes attributes) {
        return new Entry(path, attributes.isDirectory(), attributes.isSymbolicLink(),
                attributes.size(), attributes.lastModifiedTime());
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException();
        }
    }
}

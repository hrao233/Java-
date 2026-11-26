package edu.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

final class ActiveStaging {
    private record Root(Path path, Win32FileHandles.ObjectId ntfs, Object key) { }

    // The application's single writer holds at most one staging root at a time.
    private Root root;

    synchronized void register(Win32FileHandles.Handle handle) throws IOException {
        root = new Root(handle.path(), handle.objectId(), null);
    }

    synchronized void register(Path path) {
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (attributes.isDirectory() && attributes.fileKey() != null) {
                root = new Root(path, null, attributes.fileKey());
            }
        } catch (IOException unavailable) {
            // An unverified path stays visible.
        }
    }

    synchronized void forget(Path path) {
        if (root != null && root.path().equals(path)) { root = null; }
    }

    boolean contains(Path path) {
        Root observed;
        synchronized (this) { observed = root; }
        if (observed == null) { return false; }
        Path candidate = path.toAbsolutePath().normalize();
        while (candidate.getFileName() != null) {
            // A name match only selects candidates. The identity check also covers aliases of the parent.
            if (candidate.getFileName().equals(observed.path().getFileName()) && matches(candidate, observed)) {
                synchronized (this) { return root == observed; }
            }
            candidate = candidate.getParent();
        }
        return false;
    }

    private static boolean matches(Path path, Root observed) {
        try {
            if (observed.ntfs() != null) {
                try (var handle = Win32FileHandles.open(path, Win32FileHandles.READ_ATTRIBUTES, Win32FileHandles.SHARE_ALL)) {
                    return observed.ntfs().equals(handle.objectId())
                            && (handle.attributes() & Win32FileHandles.REPARSE_POINT_ATTRIBUTE) == 0;
                }
            }
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            return attributes.isDirectory() && observed.key().equals(attributes.fileKey());
        } catch (IOException unavailable) {
            return false;
        }
    }
}

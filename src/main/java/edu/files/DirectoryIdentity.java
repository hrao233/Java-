package edu.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

record DirectoryIdentity(NtfsFileOperations.Directory ntfs, Object key, FileTime created) {
    static DirectoryIdentity read(Path path, BasicFileAttributes attributes) throws IOException {
        if (!attributes.isDirectory()) { throw new NotDirectoryException(path.toString()); }
        NtfsFileOperations.Directory ntfs;
        try {
            ntfs = NtfsFileOperations.captureDirectory(path);
        } catch (IOException nativeUnavailable) {
            // A provider can allow listing while denying access to native identity handles.
            attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isDirectory()) { throw new NotDirectoryException(path.toString()); }
            ntfs = null;
        }
        return new DirectoryIdentity(ntfs, attributes.fileKey(),
                attributes.fileKey() == null ? null : attributes.creationTime());
    }

    static DirectoryIdentity read(Path path) throws IOException {
        return read(path, Files.readAttributes(path, BasicFileAttributes.class));
    }

    boolean known() { return ntfs != null || key != null; }
}

package edu.files;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;

record TransferConflict(FileOperationTask.Kind operation, FileChanges.Selection source, Path target,
                        FileChanges.Selection occupant, Stage stage, boolean canKeepBoth) {
    enum Stage { COPY_CHECK, COPY_PUBLISH, MOVE }
    enum Strategy { SKIP, KEEP_BOTH, CANCEL }
    enum Type { FILE, DIRECTORY, SPECIAL }
    record Category(FileOperationTask.Kind operation, Type source, Type target) { }

    static final class Collision extends FileAlreadyExistsException {
        private final Path target;
        private final Stage stage;

        Collision(Path target, Stage stage, FileAlreadyExistsException cause) {
            super(target.toString());
            this.target = target;
            this.stage = stage;
            initCause(cause);
        }
    }

    static TransferConflict observe(FileChanges.Selection source, FileChanges.Destination destination,
                                    boolean moving, Collision collision) {
        FileChanges.Selection occupant = null;
        try {
            var entry = LocalFiles.describe(collision.target);
            occupant = new FileChanges.Selection(entry, null);
            occupant = FileChanges.capture(entry);
        } catch (IOException | SecurityException unavailable) {
            // The collision was confirmed earlier. Current details may be unavailable.
        }
        boolean canKeepBoth = source.nativeItem() != null && destination.directory() != null
                && (!moving || NtfsFileOperations.sameVolume(source.nativeItem(), destination.directory()));
        return new TransferConflict(moving ? FileOperationTask.Kind.MOVE : FileOperationTask.Kind.COPY,
                source, collision.target, occupant, collision.stage, canKeepBoth);
    }

    Category category() {
        return occupant == null ? null : new Category(operation, type(source.entry()), type(occupant.entry()));
    }

    boolean allows(Strategy strategy) {
        return strategy != Strategy.KEEP_BOTH || canKeepBoth;
    }

    private static Type type(LocalFiles.Entry entry) {
        if (entry.link() || entry.other()) { return Type.SPECIAL; }
        return entry.directory() ? Type.DIRECTORY : Type.FILE;
    }
}

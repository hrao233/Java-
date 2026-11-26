package edu.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrowseImpactTest {
    @TempDir Path root;

    @Test
    void copiesInvalidateTheDestinationAndItsRecursiveParents() throws Exception {
        Path source = Files.writeString(root.resolve("note.txt"), "source");
        Path target = Files.createDirectories(root.resolve("album/sub"));
        var change = FileChanges.transfer(LocalFiles.describe(source), target, false, true);
        var result = FileChanges.execute(List.of(change));
        assertEquals(FileChanges.State.APPLIED, result.items().getFirst().state());
        var impact = BrowseImpact.from(FileOperationTask.Kind.COPY, result);
        assertTrue(impact.affectsDirectory(target, false));
        assertFalse(impact.affectsDirectory(target.getParent(), false));
        assertTrue(impact.affectsDirectory(target.getParent(), true));
        assertFalse(impact.affectsDirectory(root, false));
        assertFalse(impact.affectsEntry(source));
    }

    @Test
    void receiptOfMovedAncestorAndUnknownObservationsInvalidateDependentViews() throws Exception {
        Path folder = Files.createDirectories(root.resolve("source/ancestor"));
        Path target = Files.createDirectory(root.resolve("target"));
        Path pending = folder.resolve("deeper");
        var move = new FileChanges.Change(folder, List.of(folder.getParent(), target),
                () -> FileChanges.Effect.written(null, new FileChanges.Receipt(folder, target.resolve("ancestor"), null)));
        var result = FileChanges.execute(List.of(move));
        assertTrue(BrowseImpact.from(FileOperationTask.Kind.MOVE, result).affectsDirectory(pending, false));

        Path observed = target.resolve("new-name");
        var unknown = new FileChanges.Change(folder, List.of(folder.getParent()),
                () -> { throw LocalFiles.uncertain("丢失回执", new IOException("test"), observed); });
        var uncertain = FileChanges.execute(List.of(unknown));
        assertTrue(BrowseImpact.from(FileOperationTask.Kind.MOVE, uncertain).affectsDirectory(target, false));
    }
}

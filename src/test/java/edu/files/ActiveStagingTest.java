package edu.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static edu.files.Win32FileHandles.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
class ActiveStagingTest {
    @TempDir Path root;

    @Test
    void hidesTheOwnedRootAndItsChildrenButLeavesForeignAndRetiredDirectoriesVisible() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null, "requires local NTFS identity");
        var registry = new ActiveStaging();
        Path stage = Files.createDirectory(root.resolve(".file-manager-example"));
        Path child = Files.writeString(stage.resolve("part.txt"), "partial");
        Path otherParent = Files.createDirectory(root.resolve("unrelated"));
        Path foreign = Files.createDirectory(otherParent.resolve(stage.getFileName()));
        try (var handle = open(stage, READ_ATTRIBUTES, SHARE_ALL)) { registry.register(handle); }
        assertTrue(registry.contains(stage));
        assertTrue(registry.contains(child));
        assertFalse(registry.contains(foreign));
        var matches = LocalFiles.search(root, "part", new LocalFiles.Visibility(true, true), () -> false, registry::contains);
        assertTrue(matches.entries().isEmpty());

        Files.move(stage, root.resolve("parked"));
        Files.createDirectory(stage);
        assertFalse(registry.contains(stage));
        registry.forget(stage);
        assertFalse(registry.contains(root.resolve("parked/part.txt")));
    }
}

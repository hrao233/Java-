package edu.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WindowsPlacesTest {
    @TempDir Path root;

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void launchesTheSystemPowerShellWithNoDeveloperPath() throws Exception {
        var output = root.resolve("output.txt");
        var command = WindowsPlaces.powershell("""
                [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
                [Console]::Write('shell-ready')
                """);
        assertTrue(Path.of(command.command().getFirst()).isAbsolute());
        command.environment().put("Path", root.toString());
        var process = command.redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals("shell-ready", Files.readString(output));
        } finally {
            if (process.isAlive()) { process.destroy(); }
        }
    }

    @Test
    void malformedRecordsLeaveDefaultsAndLaterValidRecordsAvailable() {
        Path desktop = root.resolve("desktop");
        Path recent = root.resolve("recent.txt");
        var catalog = WindowsPlaces.catalog(List.of(
                "view\t1",
                "folder\tDesktop\t\0",
                "recent\t" + recent + "\tbad-date",
                "disk\tC:\t\t3\tbad-size\t\tNTFS\t\t",
                "shell\ttruncated\t",
                "folder\tDesktop\t" + desktop,
                "recent\t" + recent + "\t2026-11-28T00:00:00Z",
                "view\t1\t0\t1"));
        assertEquals(desktop, catalog.fixed().getFirst().path());
        assertEquals(WindowsPlaces.USER_HOME.resolve("Downloads"), catalog.fixed().get(1).path());
        assertEquals(List.of(recent), catalog.recent().stream().map(WindowsPlaces.Recent::path).toList());
        assertTrue(catalog.visibility().hidden());
        assertFalse(catalog.extensions());
    }
}

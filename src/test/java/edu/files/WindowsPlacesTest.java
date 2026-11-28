package edu.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WindowsPlacesTest {
    @TempDir Path root;

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

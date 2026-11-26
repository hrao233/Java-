package edu.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static edu.files.DirectoryWatcher.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DirectoryWatcherTest {
    @TempDir Path root;
    private final LinkedBlockingQueue<DirectoryWatcher.Notice> notices = new LinkedBlockingQueue<>();

    @Test
    void unsupportedProviderReportsItsStatusAndReleasesWorker() throws Exception {
        URI archive = URI.create("jar:" + root.resolve("fixture.zip").toUri());
        try (var zip = FileSystems.newFileSystem(archive, Map.of("create", "true"));
             var watcher = watcher(path -> fail("ZIP cannot register a watch"), path -> false)) {
            var target = watcher.watch(Files.createDirectory(zip.getPath("/test")));
            var notice = next(n -> n.target() == target && n.status() == UNAVAILABLE);
            assertNull(notice.changes());
            assertEquals(0, watcher.registrationCount());
            watcher.close();
            assertTrue(watcher.awaitClosed(Duration.ofSeconds(3)));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void ntfsChangesAlsoInvalidateUnderEditorStyleReplacement() throws Exception {
        Path folder = ntfsDirectory();
        try (var watcher = watcher(DirectoryIdentity::read, path -> false)) {
            var target = watcher.watch(folder);
            assertTrue(next(n -> n.target() == target && n.status() == ACTIVE).changes().full());
            Path first = Files.writeString(folder.resolve("实验.txt"), "old contents");
            next(n -> has(n, first));
            Files.writeString(first, "modified in place");
            next(n -> has(n, first));
            Path second = Files.move(first, folder.resolve("改名.txt"));
            var rename = next(n -> has(n, second));
            assertTrue(rename.changes().entries().contains(first));
            Path temporary = Files.writeString(folder.resolve("editor.tmp"), "new object");
            Files.move(temporary, second, StandardCopyOption.REPLACE_EXISTING);
            next(n -> has(n, second));
            assertEquals("new object", Files.readString(second));
            Files.delete(second);
            next(n -> has(n, second) && !Files.exists(second));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void movedDeletedAndRecreatedDirectoryRebindsToTheCurrentObject() throws Exception {
        Path folder = ntfsDirectory();
        try (var watcher = watcher(DirectoryIdentity::read, path -> false)) {
            var target = watcher.watch(folder);
            next(n -> n.target() == target && n.status() == ACTIVE);
            Path moved = Files.move(folder, root.resolve("moved"));
            assertTrue(next(n -> n.status() == WAITING).changes().full());
            Files.writeString(moved.resolve("old-key.txt"), "moved object");
            Files.createDirectory(folder);
            assertTrue(next(n -> n.status() == ACTIVE).changes().full());
            Path fresh = Files.writeString(folder.resolve("new-key.txt"), "current object");
            next(n -> has(n, fresh));
            Files.delete(fresh);
            Files.delete(folder);
            next(n -> n.status() == WAITING);
            assertEquals(1, watcher.registrationCount());
            Files.createDirectory(folder);
            next(n -> n.status() == ACTIVE);
            assertEquals(2, watcher.registrationCount());
            assertEquals("moved object", Files.readString(moved.resolve("old-key.txt")));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void registrationRaceAndNativeOverflowProduceFullInvalidations() throws Exception {
        Path folder = ntfsDirectory();
        var registered = new CountDownLatch(1);
        var consume = new CountDownLatch(1);
        var reads = new AtomicInteger();
        try (var watcher = watcher(path -> {
            if (reads.incrementAndGet() == 2) {
                registered.countDown();
                try {
                    if (!consume.await(20, TimeUnit.SECONDS)) { throw new IOException("watch fixture timed out"); }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            return DirectoryIdentity.read(path);
        }, path -> true)) {
            try {
                var target = watcher.watch(folder);
                assertTrue(registered.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 3_000; i++) { Files.createFile(folder.resolve("burst-" + i)); }
                consume.countDown();
                assertTrue(next(n -> n.target() == target && n.status() == ACTIVE).changes().full());
                // All path events are filtered. The second full notice requires an actual OVERFLOW.
                next(n -> n.target() == target && n.changes() != null && n.changes().full());
                try (var entries = Files.list(folder)) { assertEquals(3_000, entries.count()); }
            } finally {
                consume.countDown();
            }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void switchesHaveBoundedRegistrationsAndCloseStopsDelivery() throws Exception {
        Path first = ntfsDirectory();
        Path second = Files.createDirectory(root.resolve("second"));
        try (var watcher = watcher(DirectoryIdentity::read, path -> false)) {
            for (int i = 0; i < 24; i++) {
                var current = watcher.watch(i % 2 == 0 ? first : second);
                next(n -> n.target() == current && n.status() == ACTIVE);
                assertEquals(2, watcher.registrationCount());
            }
            var target = watcher.watch(second);
            next(n -> n.target() == target && n.status() == ACTIVE);
            Path stale = Files.writeString(first.resolve("stale.txt"), "old");
            Path live = Files.writeString(second.resolve("live.txt"), "new");
            next(n -> n.target() == target && has(n, live));
            assertTrue(notices.stream().noneMatch(n -> has(n, stale)));
            watcher.pause();
            var deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (watcher.registrationCount() != 0 && System.nanoTime() < deadline) { Thread.sleep(10); }
            assertEquals(0, watcher.registrationCount());
            watcher.close();
            assertTrue(watcher.awaitClosed(Duration.ofSeconds(3)));
            Files.delete(live);
            assertNull(notices.poll(250, TimeUnit.MILLISECONDS));
        }
    }

    private DirectoryWatcher watcher(DirectoryWatcher.IdentityReader reader, Predicate<Path> exclude) {
        return new DirectoryWatcher(reader, exclude, notices::add);
    }

    private Path ntfsDirectory() throws IOException {
        Path path = Files.createDirectory(root.resolve("watched"));
        assumeTrue(NtfsFileOperations.captureDirectory(path) != null, "requires Windows x64 local NTFS");
        return path;
    }

    private DirectoryWatcher.Notice next(Predicate<DirectoryWatcher.Notice> expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            var notice = notices.poll(100, TimeUnit.MILLISECONDS);
            if (notice != null && expected.test(notice)) { return notice; }
        }
        return fail("The expected directory notice did not arrive");
    }

    private static boolean has(DirectoryWatcher.Notice notice, Path entry) {
        return notice.changes() != null && notice.changes().entries().contains(entry);
    }
}

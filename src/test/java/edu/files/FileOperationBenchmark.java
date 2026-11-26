package edu.files;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in comparison. Run in a dedicated NTFS fixture via the audit instructions. */
final class FileOperationBenchmark {
    private static final int LARGE_MIB = 192;
    private static final int SMALL_COUNT = 1_000;
    private static final int SMALL_BYTES = 4 * 1024;
    private static final int SAMPLE_COUNT = 5;
    private static final int HANDLE_ROUNDS = 24;

    private FileOperationBenchmark() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 || !List.of("baseline", "engine").contains(arguments[0])) {
            throw new IllegalArgumentException("Usage: FileOperationBenchmark baseline|engine <fixture parent>");
        }
        Path root = Files.createTempDirectory(Path.of(arguments[1]).toRealPath(), "phase23-bench-");
        try {
            run(root, arguments[0].equals("engine"));
        } finally {
            deleteFixture(root);
        }
    }

    private static void run(Path root, boolean engine) throws Exception {
        Path incoming = Files.createDirectory(root.resolve("source"));
        Path large = incoming.resolve("large.bin");
        try (var output = Files.newOutputStream(large)) {
            var block = new byte[1024 * 1024];
            for (int number = 0; number < LARGE_MIB; number++) {
                block[0] = (byte) number;
                output.write(block);
            }
        }
        Path small = Files.createDirectory(incoming.resolve("small"));
        Path browse = Files.createDirectory(incoming.resolve("browse"));
        var data = new byte[SMALL_BYTES];
        for (int index = 0; index < SMALL_COUNT; index++) { Files.write(small.resolve("part-" + index), data); }
        for (int index = 0; index < 10_000; index++) { Files.createFile(browse.resolve("item-" + index)); }

        System.out.printf("%s JDK=%s FS=%s%n", engine ? "engine" : "baseline", Runtime.version(), Files.getFileStore(root).type());
        measureCopies("large-" + LARGE_MIB + "MiB", root, large, LARGE_MIB * 1024L * 1024, engine);
        measureCopies("small-" + SMALL_COUNT + "x4KiB", root, small, SMALL_COUNT * (long) SMALL_BYTES, engine);
        measure("prescan-1000", () -> require(scan(small) == SMALL_COUNT * (long) SMALL_BYTES, "Incomplete pre-scan"));
        var visibility = new LocalFiles.Visibility(false, false);
        measure("browse-10000", () -> require(LocalFiles.readDirectory(browse, visibility, () -> false)
                .entries().size() == 10_000, "Incomplete listing"));
        if (engine) {
            measureProgress(large);
            measureCancellation(root, large);
        }
        measureHandles(root, small, engine);
    }

    private static void measureCopies(String name, Path root, Path source, long bytes, boolean engine) throws Exception {
        double[] timings = new double[SAMPLE_COUNT];
        for (int number = -1; number < SAMPLE_COUNT; number++) {
            Path destination = Files.createTempDirectory(root, "copy-");
            long started = System.nanoTime();
            var copied = copy(source, destination, engine);
            long elapsed = System.nanoTime() - started;
            if (number >= 0) { timings[number] = millis(elapsed); }
            require(scan(copied) == bytes && Files.mismatch(source.resolve(smallestName(source)), copied.resolve(smallestName(source))) == -1,
                    "Incomplete copied fixture");
            deleteFixture(destination);
        }
        print(name, timings);
        System.out.printf(Locale.ROOT, "%s throughput=%.1f MiB/s%n",
                name, bytes / 1048576.0 / (timings[SAMPLE_COUNT / 2] / 1000));
    }

    private static String smallestName(Path source) {
        return Files.isDirectory(source) ? "part-0" : "";
    }

    private static Path copy(Path from, Path destination, boolean engine) throws Exception {
        var selection = FileChanges.capture(LocalFiles.describe(from));
        var target = FileChanges.destination(destination);
        var changes = List.of(FileChanges.transfer(selection, target, false, false));
        var result = engine ? new FileOperationTask(FileOperationTask.Kind.COPY, changes).run() : FileChanges.execute(changes);
        if (result.count(FileChanges.State.APPLIED) != 1) { throw new IOException("Fixture copy failed", result.error()); }
        return result.items().getFirst().receipt().target();
    }

    private static void measureProgress(Path source) throws Exception {
        int byteReports = 300_000;
        var notifications = new AtomicInteger();
        var change = new FileChanges.Change(source, List.of(source.getParent()), null, byteReports, task -> {
            task.copying(source);
            for (int i = 0; i < byteReports; i++) { task.transfer(1); }
            return FileChanges.Effect.written(null);
        });
        measure("progress-300000-reports", () -> {
            notifications.set(0);
            var task = new FileOperationTask(FileOperationTask.Kind.COPY, List.of(change), ignored -> notifications.incrementAndGet());
            task.run();
            require(task.snapshot().transferredBytes() == byteReports, "Dropped bytes");
        });
        System.out.printf("progress notifications in final sample=%d%n", notifications.get());
    }

    private static void measureCancellation(Path root, Path source) throws Exception {
        double[] stop = new double[SAMPLE_COUNT];
        double[] cleanup = new double[SAMPLE_COUNT];
        var requested = new AtomicLong();
        var cleanupStarted = new AtomicLong();
        for (int round = -1; round < SAMPLE_COUNT; round++) {
            requested.set(0);
            cleanupStarted.set(0);
            Path destination = Files.createTempDirectory(root, "cancel-");
            var selection = FileChanges.capture(LocalFiles.describe(source));
            var change = FileChanges.transfer(selection, FileChanges.destination(destination), false, false);
            var running = new AtomicReference<FileOperationTask>();
            var task = new FileOperationTask(FileOperationTask.Kind.COPY, List.of(change), sample -> {
                if (sample.phase() == FileOperationTask.Phase.COPYING && sample.transferredBytes() > 0
                        && requested.compareAndSet(0, System.nanoTime())) {
                    running.get().requestCancellation();
                }
                if (sample.phase() == FileOperationTask.Phase.CLEANUP) { cleanupStarted.set(System.nanoTime()); }
            });
            running.set(task);
            var result = task.run();
            long finished = System.nanoTime();
            require(result.count(FileChanges.State.CANCELLED) == 1 && result.issues().isEmpty(), "Copy did not cancel cleanly");
            if (round >= 0) {
                stop[round] = millis(cleanupStarted.get() - requested.get());
                cleanup[round] = millis(finished - cleanupStarted.get());
            }
            try (var paths = Files.list(destination)) { require(paths.findAny().isEmpty(), "Cancellation left a fixture"); }
            Files.delete(destination);
        }
        print("cancel-to-cleanup", stop);
        print("cleanup-to-terminal", cleanup);
    }

    private static void measureHandles(Path root, Path small, boolean engine) throws Exception {
        var checkpoints = new int[HANDLE_ROUNDS + 1];
        for (int round = -1; round < HANDLE_ROUNDS; round++) {
            for (int index = 0; index < 500; index++) {
                Path fixture = small.resolve("part-" + index);
                require(FileChanges.capture(LocalFiles.describe(fixture)).nativeItem() != null, "Missing identity");
            }
            Path destination = Files.createTempDirectory(root, "handles-");
            for (int index = 0; index < 20; index++) { copy(small.resolve("part-" + index), destination, engine); }
            deleteFixture(destination);
            checkpoints[round + 1] = Win32FileHandlesTest.processHandleCount();
        }
        System.out.println("handles warmup+" + HANDLE_ROUNDS
                + " (500 identities + 20 writes per round)=" + Arrays.toString(checkpoints));
    }

    private static long scan(Path source) throws IOException {
        var result = new AtomicLong();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                result.addAndGet(attributes.size());
                return FileVisitResult.CONTINUE;
            }
        });
        return result.get();
    }

    private static void deleteFixture(Path directory) throws IOException {
        List<Path> entries;
        try (var walk = Files.walk(directory)) { entries = walk.sorted(Comparator.reverseOrder()).toList(); }
        for (Path path : entries) { Files.delete(path); }
    }

    private static void measure(String name, Measurement measurement) throws Exception {
        measurement.run();
        double[] samples = new double[SAMPLE_COUNT];
        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            measurement.run();
            samples[i] = millis(System.nanoTime() - start);
        }
        print(name, samples);
    }

    private static void print(String name, double[] values) {
        Arrays.sort(values);
        System.out.printf(Locale.ROOT, "%s min=%.2f median=%.2f max=%.2f ms%n",
                name, values[0], values[values.length / 2], values[values.length - 1]);
    }

    private static double millis(long nanos) { return nanos / 1_000_000.0; }

    private static void require(boolean result, String message) throws IOException {
        if (!result) { throw new IOException(message); }
    }

    @FunctionalInterface
    private interface Measurement { void run() throws Exception; }
}

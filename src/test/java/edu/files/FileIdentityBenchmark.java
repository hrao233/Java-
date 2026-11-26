package edu.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Runs measurements with dedicated temporary directories and no timing assertions. */
final class FileIdentityBenchmark {
    private static final LocalFiles.Visibility VISIBLE = new LocalFiles.Visibility(false, false);
    private static final int BATCH_SIZE = 200;

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 || !List.of("baseline", "ntfs").contains(arguments[0])) {
            throw new IllegalArgumentException("Usage: FileIdentityBenchmark baseline|ntfs <benchmark parent>");
        }
        Path root = Files.createTempDirectory(Path.of(arguments[1]).toRealPath(), "phase22-benchmark-");
        try {
            run(root, arguments[0]);
        } finally {
            List<Path> created;
            try (var paths = Files.walk(root)) {
                created = paths.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path path : created) { Files.delete(path); }
        }
    }

    private static void run(Path root, String mode) throws IOException {
        boolean nativeMode = mode.equals("ntfs");
        Path listing = Files.createDirectory(root.resolve("listing"));
        for (int i = 0; i < 10_000; i++) {
            Files.createFile(listing.resolve("file-" + i + ".txt"));
        }
        System.out.println(mode + " " + Runtime.version() + " " + Files.getFileStore(root).type());

        measure("list 10000", () -> {
            if (list(listing).size() != 10_000) { throw new IOException("Incomplete directory listing"); }
        });

        if (nativeMode) {
            var selected = list(listing).subList(0, 500);
            measure("capture 500 identities", () -> {
                for (var item : selected) {
                    if (FileChanges.capture(item).nativeItem() == null) {
                        throw new IOException("NTFS identity not available");
                    }
                }
            });
            int before = Win32FileHandlesTest.processHandleCount();
            for (int i = 0; i < 5; i++) {
                for (var item : selected) {
                    FileChanges.capture(item);
                }
            }
            System.out.println("process handles before/after 2500 captures: " + before + "/"
                    + Win32FileHandlesTest.processHandleCount());
        }

        double[] times = new double[5];
        for (int run = -1; run < times.length; run++) {
            Path from = Files.createDirectory(root.resolve("from-" + run));
            Path to = Files.createDirectory(root.resolve("to-" + run));
            for (int i = 0; i < BATCH_SIZE; i++) {
                Files.writeString(from.resolve("file-" + i), "sample");
            }
            var entries = list(from);
            long begin = System.nanoTime();
            var moves = new ArrayList<FileChanges.Change>();
            if (nativeMode) {
                var destination = FileChanges.destination(to);
                for (var entry : entries) {
                    moves.add(FileChanges.transfer(FileChanges.capture(entry), destination, true, false));
                }
            } else {
                for (var entry : entries) {
                    moves.add(FileChanges.transfer(entry, to, true, true));
                }
            }
            var result = FileChanges.execute(moves);
            long elapsed = System.nanoTime() - begin;
            if (result.count(FileChanges.State.APPLIED) != BATCH_SIZE) {
                throw new IOException("Test move failed", result.error());
            }
            if (run >= 0) { times[run] = elapsed / 1_000_000.0; }
        }
        print("batch move 200 including identity capture", times);
    }

    private static List<LocalFiles.Entry> list(Path path) throws IOException {
        return LocalFiles.readDirectory(path, VISIBLE, () -> false).entries();
    }

    private static void measure(String name, Timing action) throws IOException {
        action.run();
        double[] times = new double[5];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            action.run();
            times[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        print(name, times);
    }

    private static void print(String name, double[] times) {
        Arrays.sort(times);
        System.out.printf(Locale.ROOT, "%s min=%.2f median=%.2f max=%.2f ms%n", name, times[0], times[2], times[4]);
    }

    private interface Timing { void run() throws IOException; }
}

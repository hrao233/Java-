package edu.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Opt-in benchmark for an isolated local NTFS directory. */
final class Phase24IoBenchmark {
    private static final int SAMPLES = 5;
    private static final LocalFiles.Visibility VISIBLE = new LocalFiles.Visibility(false, false);

    private Phase24IoBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !List.of("listing", "copy", "diagnose", "cancel").contains(args[0])) {
            throw new IllegalArgumentException("Use listing|copy|diagnose|cancel <fixture parent>");
        }
        Path fixture = Files.createTempDirectory(Path.of(args[1]).toRealPath(), "phase24-io-");
        try {
            System.out.printf("%s JDK=%s FS=%s%n", args[0], Runtime.version(), Files.getFileStore(fixture).type());
            switch (args[0]) {
                case "listing" -> listing(fixture);
                case "copy", "diagnose" -> copies(fixture, args[0].equals("diagnose"));
                case "cancel" -> cancellation(fixture);
            }
        } finally {
            deleteFixture(fixture);
        }
    }

    private static void listing(Path fixture) throws IOException {
        for (String scheme : List.of("file-%d.txt", "item-%d")) {
            Path directory = Files.createDirectory(fixture.resolve(scheme.equals("item-%d") ? "extensionless" : "text"));
            for (int i = 0; i < 10_000; i++) {
                Files.createFile(directory.resolve(String.format(Locale.ROOT, scheme, i)));
            }
            long[][] intervals = new long[5][SAMPLES];
            for (int trial = -1; trial < SAMPLES; trial++) {
                long t0 = System.nanoTime();
                var paths = new ArrayList<Path>(10_000);
                try (var children = Files.newDirectoryStream(directory)) { children.forEach(paths::add); }
                long t1 = System.nanoTime();
                var entries = new ArrayList<LocalFiles.Entry>(paths.size());
                for (Path path : paths) {
                    var attributes = Files.readAttributes(path, DosFileAttributes.class, NOFOLLOW_LINKS);
                    if (VISIBLE.includes(path, attributes)) {
                        entries.add(new LocalFiles.Entry(path, attributes.isDirectory(), attributes.isSymbolicLink(),
                                attributes.size(), attributes.lastModifiedTime(), attributes.creationTime(),
                                attributes.fileKey(), attributes.isOther()));
                    }
                }
                long t2 = System.nanoTime();
                var sorted = new LocalFiles.Listing(entries, 0, false);
                long t3 = System.nanoTime();
                var items = sorted.entries().stream().map(BrowserItem::file).toList();
                long t4 = System.nanoTime();
                require(items.size() == 10_000);
                require(LocalFiles.readDirectory(directory, VISIBLE, () -> false).entries().size() == 10_000);
                long t5 = System.nanoTime();
                if (trial >= 0) {
                    long[] durations = {t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4};
                    for (int i = 0; i < durations.length; i++) { intervals[i][trial] = durations[i]; }
                }
            }
            for (int i = 0; i < intervals.length; i++) {
                print(scheme + " " + List.of("enumerate", "attributes/filter/build", "sort", "browser-items", "whole-read").get(i),
                        intervals[i]);
            }
        }
    }

    private static void copies(Path fixture, boolean diagnostics) throws IOException {
        Path from = Files.createDirectory(fixture.resolve("tree"));
        for (int i = 0; i < 1_000; i++) { Files.write(from.resolve("part-" + i + ".txt"), new byte[4096]); }
        long[] times = new long[SAMPLES];
        for (int trial = -1; trial < SAMPLES; trial++) {
            Path to = Files.createTempDirectory(fixture, "output-");
            var change = request(from, to);
            long start = System.nanoTime();
            if (diagnostics) {
                try (var capture = CopyDiagnostics.capture()) {
                    copy(change);
                    if (trial == 2) { System.out.print(capture.report()); }
                }
            } else {
                copy(change);
            }
            long elapsed = System.nanoTime() - start;
            if (trial >= 0) { times[trial] = elapsed; }
            Path copy = to.resolve("tree");
            try (var files = Files.list(copy)) { require(files.count() == 1_000); }
            require(Files.size(copy.resolve("part-999.txt")) == 4096);
            deleteFixture(to);
        }
        print("1000-by-4096 copy and cleanup", times);
        System.out.printf(Locale.ROOT, "throughput_mib_s=%.3f%n", 1_000 * 4096.0 / 1048576 / (times[2] / 1e9));
    }

    private static FileChanges.Change request(Path source, Path directory) throws IOException {
        return FileChanges.transfer(FileChanges.capture(LocalFiles.describe(source)),
                FileChanges.destination(directory), false, false);
    }

    private static void copy(FileChanges.Change change) throws IOException {
        var outcome = new FileOperationTask(FileOperationTask.Kind.COPY, List.of(change)).run();
        if (outcome.count(FileChanges.State.APPLIED) != 1) {
            throw new IOException("Fixture copy failed", outcome.error());
        }
        require(outcome.issues().isEmpty());
    }

    private static void cancellation(Path fixture) throws IOException {
        Path file = Files.createFile(fixture.resolve("large.bin"));
        try (var stream = Files.newOutputStream(file)) {
            byte[] block = new byte[1024 * 1024];
            for (int i = 0; i < 64; i++) { stream.write(block); }
        }
        long[] stop = new long[SAMPLES];
        long[] clean = new long[SAMPLES];
        for (int trial = -1; trial < SAMPLES; trial++) {
            Path destination = Files.createTempDirectory(fixture, "stopping-");
            var ref = new AtomicReference<FileOperationTask>();
            var cancel = new AtomicLong();
            var cleanup = new AtomicLong();
            var task = new FileOperationTask(FileOperationTask.Kind.COPY, List.of(request(file, destination)), snapshot -> {
                if (snapshot.phase() == FileOperationTask.Phase.COPYING && snapshot.transferredBytes() > 0
                        && cancel.compareAndSet(0, System.nanoTime())) {
                    ref.get().requestCancellation();
                }
                if (snapshot.phase() == FileOperationTask.Phase.CLEANUP) { cleanup.set(System.nanoTime()); }
            });
            ref.set(task);
            var outcome = task.run();
            long end = System.nanoTime();
            require(outcome.count(FileChanges.State.CANCELLED) == 1 && outcome.issues().isEmpty());
            try (var children = Files.list(destination)) { require(children.findAny().isEmpty()); }
            if (trial >= 0) {
                stop[trial] = cleanup.get() - cancel.get();
                clean[trial] = end - cleanup.get();
            }
            Files.delete(destination);
        }
        print("cancel-to-cleanup", stop);
        print("cleanup-to-terminal", clean);
    }

    private static void print(String label, long[] samples) {
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT, "%s min=%.2f median=%.2f max=%.2f ms%n",
                label, samples[0] / 1e6, samples[samples.length / 2] / 1e6, samples[samples.length - 1] / 1e6);
    }

    private static void require(boolean condition) throws IOException {
        if (!condition) { throw new IOException("Unexpected fixture state"); }
    }

    private static void deleteFixture(Path parent) throws IOException {
        List<Path> paths;
        try (var walk = Files.walk(parent)) { paths = walk.sorted(Comparator.reverseOrder()).toList(); }
        for (Path path : paths) { Files.delete(path); }
    }
}

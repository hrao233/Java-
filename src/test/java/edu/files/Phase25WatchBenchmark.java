package edu.files;

import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Opt-in measurement against either production classpath, with identical Windows fixtures. */
final class Phase25WatchBenchmark {
    interface Kernel extends StdCallLibrary {
        boolean GetProcessHandleCount(com.sun.jna.Pointer process, IntByReference count);
        com.sun.jna.Pointer GetCurrentProcess();
    }

    private static final int SAMPLES = 5;
    private final boolean automatic;
    private final Path root;
    private final Kernel kernel = Native.load("kernel32", Kernel.class);
    private final CountDownLatch publishing = new CountDownLatch(1);
    private final CountDownLatch releaseCopy = new CountDownLatch(1);
    private final List<Path> changes = new java.util.ArrayList<>();
    private final List<Long> written = new java.util.ArrayList<>();
    private final List<Long> visible = new java.util.ArrayList<>();
    private volatile long commitAt;
    private volatile long renderNanos;
    private volatile int reads;
    private volatile int commits;
    private volatile Path timed;
    private volatile ReadGate blockedRead;
    private FileManagerApp app;
    private Stage stage;
    private Scene scene;
    private BrowserView browser;

    private record ReadGate(Path path, CountDownLatch reached, CountDownLatch release) { }

    private Phase25WatchBenchmark(boolean automatic, Path root) {
        this.automatic = automatic;
        this.root = root;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !List.of("baseline", "current").contains(args[0])) {
            throw new IllegalArgumentException("Use baseline|current <fixture parent>");
        }
        Path root = Files.createTempDirectory(Path.of(args[1]).toRealPath(), "phase25-watch-bench-");
        var benchmark = new Phase25WatchBenchmark(args[0].equals("current"), root);
        try {
            benchmark.run();
        } finally {
            benchmark.releaseCopy.countDown();
            if (benchmark.app != null) { benchmark.stop(); }
            Platform.exit();
            List<Path> paths;
            try (var tree = Files.walk(root)) { paths = tree.sorted(Comparator.reverseOrder()).toList(); }
            for (var path : paths) { Files.delete(path); }
        }
    }

    private void run() throws Exception {
        Path large = Files.createDirectory(root.resolve("large"));
        for (int i = 0; i < 10_000; i++) { Files.createFile(large.resolve("item-" + i)); }
        Path small = Files.createDirectory(root.resolve("small"));
        for (int i = 0; i < 100; i++) { Files.createFile(small.resolve("entry-" + i + ".txt")); }
        Path other = Files.createDirectory(root.resolve("other"));
        Path source = Files.createDirectory(root.resolve("source"));
        Files.write(source.resolve("payload.bin"), new byte[48 * 1024 * 1024]);
        if (NtfsFileOperations.captureDirectory(root) == null) { throw new IllegalStateException("Local NTFS required"); }
        var startup = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            startup.countDown();
        });
        require(startup.await(10, TimeUnit.SECONDS));
        fx(() -> {
            var preferences = new JavaFxFileOperationsTest.MemoryPreferences(null, "");
            preferences.putBoolean("automaticRefresh", automatic);
            app = new FileManagerApp(preferences, this::operation, new FileManagerApp.ReadObserver() {
                @Override public void ready(Path path, boolean preview) {
                    var gate = blockedRead;
                    if (preview || gate == null || !gate.path().equals(path)) { return; }
                    gate.reached().countDown();
                    try {
                        require(gate.release().await(10, TimeUnit.SECONDS));
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override public void started(Path path, String query, long queueNanos) {
                    if (path != null && path.equals(timed)) { reads++; }
                }

                @Override public void committed(Path path, long applyNanos) {
                    if (path == null || !path.equals(timed)) { return; }
                    commitAt = System.nanoTime();
                    renderNanos = applyNanos;
                    commits++;
                    if (!changes.isEmpty()) {
                        var names = browser.items().stream().map(BrowserItem::path).collect(java.util.stream.Collectors.toSet());
                        for (int i = 0; i < changes.size(); i++) {
                            if (visible.get(i) == 0 && names.contains(changes.get(i))) { visible.set(i, commitAt); }
                        }
                    }
                }
            });
            stage = new Stage();
            stage.setOpacity(0);
            app.start(stage);
            scene = stage.getScene();
            browser = (BrowserView) field(app, "browser");
            return null;
        });
        await(() -> field(app, "places") != null && idle());

        go(small);
        if (!automatic) {
            int previous = reads;
            Path quiet = Files.createFile(small.resolve("baseline-passive.txt"));
            Thread.sleep(1_200);
            require(reads == previous && !fx(() -> listed(quiet)));
            System.out.println("baseline passive change: 0 reads and no visible update over 1200 ms");
            refresh();
            await(() -> listed(quiet) && idle());
            Files.delete(quiet);
            refresh();
            await(() -> !listed(quiet) && idle());
        }
        listingSamples(small, "100 entry");
        go(large);
        listingSamples(large, "10000 entry");
        navigationWhileReading(large, other);
        go(small);
        burst(small);
        sustained(small);
        resources(small, other);
        go(source);
        fx(() -> {
            require(browser.reveal(source.resolve("payload.bin")));
            button("copy").fire();
            return null;
        });
        await(() -> field(app, "transfer") != null && field(app, "fileTask") == null);
        go(other);
        fx(() -> { button("paste").fire(); return null; });
        require(publishing.await(10, TimeUnit.SECONDS));
        long start = System.nanoTime();
        Path external = Files.createFile(other.resolve("while-copying.txt"));
        if (!automatic) { refresh(); }
        await(() -> listed(external) && idle());
        long lag = commitAt - start;
        require(!Files.exists(other.resolve("payload.bin")));
        long released = System.nanoTime();
        releaseCopy.countDown();
        await(() -> idle() && listed(other.resolve("payload.bin")));
        System.out.printf(Locale.ROOT, "copy paused: external visible %.2f ms, release to published view %.2f ms%n",
                lag / 1e6, (commitAt - released) / 1e6);
        stop();
    }

    private void listingSamples(Path directory, String caption) throws Exception {
        long[] lags = new long[SAMPLES];
        long[] builds = new long[SAMPLES];
        int initial = reads;
        for (int trial = -1; trial < SAMPLES; trial++) {
            long start = System.nanoTime();
            Path file = Files.createFile(directory.resolve("individual-" + (trial + 1) + ".txt"));
            if (!automatic) { refresh(); }
            await(() -> listed(file) && idle());
            fx(() -> { scene.snapshot(null); return null; });
            if (trial >= 0) {
                lags[trial] = commitAt - start;
                builds[trial] = renderNanos;
            }
        }
        Thread.sleep(750);
        print(caption + " disk change to JavaFX commit", lags);
        print(caption + " JavaFX commit work", builds);
        System.out.printf(Locale.ROOT, "%s: %d reads for %d changes (including warm-up)%n",
                caption, reads - initial, SAMPLES + 1);
    }

    private void burst(Path directory) throws Exception {
        int prior = reads;
        long started = System.nanoTime();
        Path last = null;
        for (int i = 0; i < 500; i++) { last = Files.createFile(directory.resolve("burst-" + i)); }
        Path finalEntry = last;
        if (!automatic) { refresh(); }
        await(() -> listed(finalEntry) && idle());
        double elapsed = (commitAt - started) / 1e6;
        Thread.sleep(750);
        System.out.printf(Locale.ROOT, "500-entry burst: %d reads, last entry visible %.2f ms after first write%n",
                reads - prior, elapsed);
    }

    private void navigationWhileReading(Path large, Path other) throws Exception {
        var gate = new ReadGate(large, new CountDownLatch(1), new CountDownLatch(1));
        blockedRead = gate;
        try {
            Files.createFile(large.resolve("before-navigation.txt"));
            if (!automatic) { refresh(); }
            require(gate.reached().await(10, TimeUnit.SECONDS));
            double lag = go(other);
            System.out.printf(Locale.ROOT, "navigation during held 10000-entry read: %.2f ms to commit%n", lag);
        } finally {
            blockedRead = null;
            gate.release().countDown();
        }
    }

    private void sustained(Path directory) throws Exception {
        int initial = reads;
        for (int i = 0; i < 45; i++) {
            Path path = directory.resolve("sustained-" + i);
            long time = System.nanoTime();
            fx(() -> { changes.add(path); written.add(time); visible.add(0L); return null; });
            Files.createFile(path);
            Thread.sleep(45);
        }
        if (!automatic) { refresh(); }
        await(() -> visible.stream().noneMatch(time -> time == 0));
        var lags = fx(() -> {
            long[] values = new long[written.size()];
            for (int i = 0; i < values.length; i++) { values[i] = visible.get(i) - written.get(i); }
            changes.clear();
            written.clear();
            visible.clear();
            return values;
        });
        print("45 continuous external changes to first visible commit", lags);
        System.out.println("continuous change reads: " + (reads - initial));
    }

    private void resources(Path small, Path other) throws Exception {
        go(other);
        int handleStart = handles();
        int handlePeak = handleStart;
        int maxKeys = 0;
        for (int i = 0; i < 24; i++) {
            go(i % 2 == 0 ? small : other);
            handlePeak = Math.max(handlePeak, handles());
            maxKeys = Math.max(maxKeys, keys());
        }
        int handleEnd = handles();
        long watcherThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("file-manager-watch")).count();
        int initial = reads;
        var cpu = ProcessHandle.current().info().totalCpuDuration().orElseThrow();
        Thread.sleep(5_000);
        Duration delta = ProcessHandle.current().info().totalCpuDuration().orElseThrow().minus(cpu);
        System.out.printf(Locale.ROOT, "24 navigations: %d keys, %d watch workers, process handles %d/%d/%d initial/peak/final%n",
                maxKeys, watcherThreads, handleStart, handlePeak, handleEnd);
        System.out.printf(Locale.ROOT, "5 s idle: %d listing reads, %.2f ms process CPU, %d process handles%n",
                reads - initial, delta.toNanos() / 1e6, handles());
    }

    private FileOperationTask operation(FileOperationTask.Kind kind, List<FileChanges.Change> changes) {
        return new FileOperationTask(kind, changes, sample -> {
            if (kind != FileOperationTask.Kind.COPY || sample.phase() != FileOperationTask.Phase.PUBLISHING) { return; }
            publishing.countDown();
            try {
                require(releaseCopy.await(12, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        });
    }

    private void stop() throws Exception {
        var watcher = automatic ? (DirectoryWatcher) field(app, "watcher") : null;
        fx(() -> { app.stop(); stage.hide(); return null; });
        app = null;
        if (watcher != null) {
            require(watcher.awaitClosed(Duration.ofSeconds(4)) && watcher.registrationCount() == 0);
            System.out.println("closed: 0 watch keys, worker joined, process handles " + handles());
        }
    }

    private double go(Path directory) throws Exception {
        timed = directory;
        int previous = commits;
        long start = System.nanoTime();
        fx(() -> {
            button("edit-address").fire();
            var editor = (TextField) scene.lookup("#address");
            editor.setText(directory.toString());
            editor.fireEvent(new ActionEvent());
            return null;
        });
        await(() -> idle() && commits > previous);
        double elapsed = (commitAt - start) / 1e6;
        if (automatic) {
            await(() -> idle() && commits >= previous + 2);
        }
        if (directory.getFileName().toString().equals("large")) {
            System.out.printf(Locale.ROOT, "10000 entries: navigation %.2f ms, settled %.2f ms%n",
                    elapsed, (commitAt - start) / 1e6);
        }
        return elapsed;
    }

    private int keys() throws Exception {
        return automatic ? ((DirectoryWatcher) field(app, "watcher")).registrationCount() : 0;
    }

    private int handles() {
        var value = new IntByReference();
        require(kernel.GetProcessHandleCount(kernel.GetCurrentProcess(), value));
        return value.getValue();
    }

    private boolean listed(Path path) { return browser.items().stream().anyMatch(item -> path.equals(item.path())); }
    private boolean idle() throws Exception { return ((BrowseContext) field(app, "context")).browseRequest == null; }
    private void refresh() throws Exception { fx(() -> { button("refresh").fire(); return null; }); }
    private Button button(String name) { return (Button) scene.lookup("#" + name); }
    private static Object field(FileManagerApp app, String name) throws Exception {
        var field = FileManagerApp.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(app);
    }

    private static void require(boolean condition) {
        if (!condition) { throw new AssertionError("Benchmark expectation failed"); }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var work = new FutureTask<>(action);
        Platform.runLater(work);
        return work.get(12, TimeUnit.SECONDS);
    }

    private static void await(Callable<Boolean> ready) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!fx(ready)) {
            if (System.nanoTime() > until) { throw new AssertionError("Timed out waiting for the benchmark UI"); }
            Thread.sleep(10);
        }
    }

    private static void print(String title, long[] samples) {
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT, "%s median %.2f ms, range %.2f–%.2f ms%n",
                title, samples[samples.length / 2] / 1e6, samples[0] / 1e6, samples[samples.length - 1] / 1e6);
    }
}

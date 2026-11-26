package edu.files;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in JavaFX measurements using the same controls on the baseline and the current build. */
final class Phase24FxBenchmark {
    private static final int SAMPLES = 5;
    private final boolean baseline;
    private final Path fixture;
    private final AtomicReference<Gate> copyGate = new AtomicReference<>();
    private final AtomicReference<Gate> searchGate = new AtomicReference<>();
    private final AtomicLong queueTime = new AtomicLong();
    private final AtomicLong applyTime = new AtomicLong();
    private FileManagerApp app;
    private Stage stage;
    private Scene scene;
    private Path listing;

    private Phase24FxBenchmark(boolean baseline, Path fixture) {
        this.baseline = baseline;
        this.fixture = fixture;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !List.of("baseline", "current").contains(args[0])) {
            throw new IllegalArgumentException("Use baseline|current <fixture parent>");
        }
        Path root = Files.createTempDirectory(Path.of(args[1]).toRealPath(), "phase24-fx-");
        var probe = new Phase24FxBenchmark(args[0].equals("baseline"), root);
        try {
            probe.run();
        } finally {
            probe.stop();
            List<Path> entries;
            try (var paths = Files.walk(root)) { entries = paths.sorted(Comparator.reverseOrder()).toList(); }
            for (Path path : entries) { Files.delete(path); }
        }
    }

    private void run() throws Exception {
        listing = Files.createDirectory(fixture.resolve("ten-thousand"));
        for (int number = 0; number < 10_000; number++) { Files.createFile(listing.resolve("item-" + number)); }
        Path small = Files.createDirectory(fixture.resolve("hundred"));
        for (int number = 0; number < 100; number++) { Files.createFile(small.resolve("small-" + number + ".txt")); }
        Path empty = Files.createDirectory(fixture.resolve("empty"));
        var startup = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            startup.countDown();
        });
        await(startup);
        fx(() -> {
            var preferences = new JavaFxFileOperationsTest.MemoryPreferences(null, "");
            app = baseline ? new FileManagerApp(preferences, this::operation)
                    : new FileManagerApp(preferences, this::operation, new FileManagerApp.ReadObserver() {
                        @Override
                        public void started(Path path, String query, long queueNanos) { queueTime.set(queueNanos); }

                        @Override
                        public void ready(Path path, boolean preview) {
                            var held = searchGate.get();
                            if (!preview && listing.equals(path) && held != null && searchGate.compareAndSet(held, null)) {
                                held.hold();
                            }
                        }

                        @Override
                        public void committed(Path path, long applyNanos) { applyTime.set(applyNanos); }
                    });
            stage = new Stage();
            stage.setOpacity(0);
            app.start(stage);
            scene = stage.getScene();
            return null;
        });
        waitState(() -> field(app, "places") != null && idle());
        long[] commit = new long[SAMPLES];
        long[] pixels = new long[SAMPLES];
        long[] queue = new long[SAMPLES];
        long[] apply = new long[SAMPLES];
        long[] smallIdle = new long[SAMPLES];
        for (int trial = -1; trial < SAMPLES; trial++) {
            go(empty);
            long start = System.nanoTime();
            go(listing);
            long elapsed = System.nanoTime() - start;
            long paint = fx(() -> {
                long begun = System.nanoTime();
                scene.snapshot(null);
                return System.nanoTime() - begun;
            });
            if (trial >= 0) {
                commit[trial] = elapsed;
                pixels[trial] = paint;
                queue[trial] = queueTime.get();
                apply[trial] = applyTime.get();
            }
            go(empty);
            long begun = System.nanoTime();
            go(small);
            if (trial >= 0) { smallIdle[trial] = System.nanoTime() - begun; }
        }
        print("idle 10000 navigation to committed", commit);
        print("idle 10000 layout plus rendered Scene snapshot", pixels);
        if (!baseline) {
            print("idle 10000 listing queue wait", queue);
            print("idle 10000 JavaFX apply", apply);
        }
        print("idle 100 navigation to committed", smallIdle);
        whileCopying(small, empty);
        whileSearching(small);
    }

    private FileOperationTask operation(FileOperationTask.Kind kind, List<FileChanges.Change> changes) {
        return new FileOperationTask(kind, changes, sample -> {
            var held = copyGate.get();
            if (kind == FileOperationTask.Kind.COPY && sample.transferredBytes() > 0
                    && held != null && copyGate.compareAndSet(held, null)) {
                held.hold();
            }
        });
    }

    private void whileCopying(Path small, Path empty) throws Exception {
        Path source = Files.createDirectory(fixture.resolve("source"));
        Path target = Files.createDirectory(fixture.resolve("target"));
        Path large = Files.write(source.resolve("copy.bin"), new byte[64 * 1024 * 1024]);
        go(source);
        fx(() -> {
            require(((BrowserView) field(app, "browser")).reveal(large));
            button("copy").fire();
            return null;
        });
        waitState(this::idle);
        go(target);
        var held = new Gate();
        copyGate.set(held);
        try {
            fx(() -> { button("paste").fire(); return null; });
            await(held.entered);
            if (baseline) {
                require(fx(() -> button("edit-address").isDisabled()));
                var marker = new FutureTask<>(System::nanoTime);
                long queued = System.nanoTime();
                ((ExecutorService) field(app, "disk")).execute(marker);
                require(!held.release.await(400, TimeUnit.MILLISECONDS));
                require(!marker.isDone());
                requestCancel();
                held.release.countDown();
                System.out.printf(Locale.ROOT, "copy address disabled; shared queue marker waited %.2f ms%n",
                        (marker.get(10, TimeUnit.SECONDS) - queued) / 1e6);
            } else {
                long[] values = new long[SAMPLES];
                long[] queue = new long[SAMPLES];
                for (int trial = -1; trial < SAMPLES; trial++) {
                    go(empty);
                    long start = System.nanoTime();
                    go(small);
                    if (trial >= 0) {
                        values[trial] = System.nanoTime() - start;
                        queue[trial] = queueTime.get();
                    }
                }
                print("during copy 100 navigation to committed", values);
                print("during copy 100 listing queue wait", queue);
                requestCancel();
                held.release.countDown();
            }
        } finally {
            held.release.countDown();
        }
        waitState(this::idle);
        try (var children = Files.list(target)) { require(children.findAny().isEmpty()); }
    }

    private void whileSearching(Path small) throws Exception {
        go(listing);
        var held = new Gate();
        if (!baseline) { searchGate.set(held); }
        try {
            boolean addressDisabled = fx(() -> {
                ((TextField) scene.lookup("#search-field")).setText("item-");
                button("search").fire();
                return button("edit-address").isDisabled();
            });
            if (baseline) {
                require(addressDisabled);
                System.out.println("search address disabled; concurrent navigation not admitted");
                fx(() -> { button("cancel-search").fire(); return null; });
            } else {
                require(!addressDisabled);
                await(held.entered);
                long start = System.nanoTime();
                go(small);
                System.out.printf(Locale.ROOT, "navigation superseding held search %.2f ms%n", (System.nanoTime() - start) / 1e6);
            }
        } finally {
            held.release.countDown();
        }
        waitState(this::idle);
    }

    private void requestCancel() throws Exception {
        fx(() -> {
            ((FileOperationTask) field(app, "activeFileOperation")).requestCancellation();
            return null;
        });
    }

    private void go(Path path) throws Exception {
        fx(() -> {
            require(!button("edit-address").isDisabled());
            button("edit-address").fire();
            var editor = (TextField) scene.lookup("#address");
            editor.setText(path.toString());
            editor.fireEvent(new ActionEvent());
            return null;
        });
        waitState(() -> (baseline ? idle() : field(app, "browseRequest") == null)
                && path.equals(pathOfLocation()));
    }

    private Path pathOfLocation() throws ReflectiveOperationException {
        var location = field(app, "location");
        return (Path) field(location, "path");
    }

    private boolean idle() throws ReflectiveOperationException {
        return baseline ? field(app, "activeTask") == null
                : field(app, "fileTask") == null && field(app, "browseRequest") == null;
    }

    private Button button(String id) { return (Button) scene.lookup("#" + id); }

    private void stop() throws Exception {
        if (app != null) {
            fx(() -> { app.stop(); stage.hide(); return null; });
        }
        Platform.exit();
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field property = object.getClass().getDeclaredField(name);
        property.setAccessible(true);
        return property.get(object);
    }

    private static <T> T fx(Callable<T> callback) throws Exception {
        var future = new FutureTask<>(callback);
        Platform.runLater(future);
        return future.get(15, TimeUnit.SECONDS);
    }

    private static void waitState(Callable<Boolean> state) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
        do {
            if (fx(state)) { return; }
            Thread.sleep(10);
        } while (System.nanoTime() < until);
        throw new IOException("Timed out waiting for JavaFX navigation");
    }

    private static void require(boolean condition) throws IOException {
        if (!condition) { throw new IOException("Unexpected JavaFX measurement state"); }
    }

    private static void await(CountDownLatch latch) throws Exception { require(latch.await(20, TimeUnit.SECONDS)); }

    private static void print(String label, long[] samples) {
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT, "%s min=%.2f median=%.2f max=%.2f ms%n", label,
                samples[0] / 1e6, samples[SAMPLES / 2] / 1e6, samples[SAMPLES - 1] / 1e6);
    }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        void hold() {
            entered.countDown();
            try {
                release.await(40, TimeUnit.SECONDS);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

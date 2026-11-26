package edu.files;

import com.sun.jna.Native;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelFormat;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Opt-in Windows system clipboard verification, invoked by the isolated station runner. */
public final class Phase26ClipboardProbe {
    interface Station extends StdCallLibrary {
        Pointer GetProcessWindowStation();
        boolean GetUserObjectInformationW(Pointer handle, int index, char[] name, int bytes, IntByReference length);
    }

    interface Kernel extends StdCallLibrary {
        Pointer GetCurrentProcess();
        boolean GetProcessHandleCount(Pointer process, IntByReference count);
    }

    interface ProcessMemory extends StdCallLibrary {
        boolean GetProcessMemoryInfo(Pointer process, Pointer counters, int bytes);
    }

    private final Path root;
    private final WindowsClipboard clipboard = new WindowsClipboard();
    private FileManagerApp app;
    private Stage stage;
    private Scene scene;

    private Phase26ClipboardProbe(Path root) { this.root = root; }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) { throw new IllegalArgumentException("Station, fixture root and action required"); }
        var station = Native.load("user32", Station.class);
        var name = new char[160];
        require(station.GetUserObjectInformationW(station.GetProcessWindowStation(), 2, name, name.length * 2,
                new IntByReference()), "cannot verify window station");
        var actual = Native.toString(name);
        require(!actual.equalsIgnoreCase("WinSta0") && actual.equals(args[0]), "isolated window station required");
        Path root = Path.of(args[1]).toRealPath();
        require(root.getFileName().toString().startsWith("javafx-phase26-interop-"), "isolated fixture required");
        var probe = new Phase26ClipboardProbe(root);
        switch (args[2]) {
            case "copy" -> probe.desktop(() -> probe.copy(probe.fixture(args[3]), args[4].equals("cut")));
            case "paste" -> probe.desktop(() -> probe.paste(probe.fixture(args[3]), probe.fixture(args[4]),
                    args.length == 6 ? probe.artifact(args[5]) : null));
            case "make-long-file" -> {
                Path file = probe.fixture(args[3]).resolve("long-" + "a".repeat(230) + ".txt");
                require(file.toString().length() > 260, "long path fixture is too short");
                Files.writeString(file, "long path contents");
            }
            case "move" -> probe.desktop(() -> probe.move(probe.fixture(args[3]), probe.fixture(args[4])));
            case "shell-paste" -> ShellClipboardCopy.paste(probe.fixture(args[3]), probe.fixture(args[4]),
                    Integer.parseInt(args[5]));
            case "inspect" -> {
                var payload = probe.clipboard.read();
                require(payload.externalMove() == Boolean.parseBoolean(args[3]), "unexpected Shell drop effect");
                require(payload.state().kind() == FileClipboard.Kind.FILES, "expected native file offer");
                System.out.printf("Shell offer is externalMove=%s, decodedPaths=%d%n",
                        payload.externalMove(), payload.paths().size());
            }
            case "benchmark" -> probe.benchmark();
            case "busy" -> probe.measureBusyClipboard();
            default -> throw new IllegalArgumentException(args[2]);
        }
    }

    private Path fixture(String name) throws Exception {
        var directory = root.resolve(name).toRealPath();
        require(directory.getParent().equals(root), "destination outside fixture");
        return directory;
    }

    private Path artifact(String name) {
        var path = root.resolve(name).normalize();
        require(path.getParent().equals(root), "artifact outside fixture");
        return path;
    }

    private void desktop(Work work) throws Exception {
        Platform.startup(() -> Platform.setImplicitExit(false));
        try {
            fx(() -> {
                app = new FileManagerApp(new JavaFxFileOperationsTest.MemoryPreferences(null, ""),
                        FileOperationTask::new, (path, preview) -> {}, clipboard);
                stage = new Stage();
                stage.setOpacity(0);
                app.start(stage);
                scene = stage.getScene();
                return null;
            });
            await(() -> field(app, "places") != null && idle());
            work.run();
        } finally {
            try {
                if (app != null) { fx(() -> { app.stop(); stage.hide(); return null; }); }
            } finally {
                Platform.exit();
            }
        }
    }

    private void copy(Path from, boolean cut) throws Exception {
        go(from);
        fx(() -> {
            var browser = (BrowserView) field(app, "browser");
            browser.selectAll();
            require(!button(cut ? "cut" : "copy").isDisabled(), "copy command unavailable");
            button(cut ? "cut" : "copy").fire();
            return null;
        });
        await(() -> idle() && field(app, "transfer") != null);
        var check = clipboard.read();
        if (cut) { require(check.state().kind() == FileClipboard.Kind.CUT && check.paths().isEmpty(), "cut leaked file list"); }
        else {
            try (var files = Files.list(from)) {
                require(check.paths().size() == files.count(), "some source items missing from CF_HDROP");
            }
        }
        System.out.println("JavaFX published " + check.state().kind());
    }

    private void paste(Path target, Path expectedSource, Path screenshot) throws Exception {
        var offered = clipboard.read();
        require(!offered.paths().isEmpty() && offered.paths().stream().allMatch(path -> expectedSource.equals(path.getParent())),
                "clipboard source outside the fixture");
        go(target);
        await(() -> !button("paste").isDisabled() && idle());
        fx(() -> { button("paste").fire(); return null; });
        await(() -> idle() && ((BrowserView) field(app, "browser")).items().size() > 0);
        try (var files = Files.walk(expectedSource)) {
            for (Path item : files.toList()) {
                Path copy = target.resolve(expectedSource.relativize(item));
                if (Files.isDirectory(item)) { require(Files.isDirectory(copy), "directory did not transfer"); }
                else { require(Files.mismatch(item, copy) == -1, "file bytes did not transfer"); }
            }
        }
        if (screenshot != null) {
            fx(() -> {
                var browser = ((SplitPane) scene.lookup("#workspace-split")).getItems().getLast();
                var crop = new SnapshotParameters();
                crop.setViewport(new javafx.geometry.Rectangle2D(0, 0, browser.getLayoutBounds().getWidth(), 142));
                var image = browser.snapshot(crop, null);
                int width = (int) image.getWidth();
                int height = (int) image.getHeight();
                var pixels = new int[width * height];
                image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
                var picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                picture.setRGB(0, 0, width, height, pixels, 0, width);
                require(ImageIO.write(picture, "png", screenshot.toFile()), "screenshot could not be written");
                return null;
            });
        }
        System.out.println("JavaFX pasted all items and checked file bytes");
    }

    private void move(Path from, Path target) throws Exception {
        var content = new java.util.LinkedHashMap<Path, byte[]>();
        try (var tree = Files.walk(from)) {
            for (var file : tree.filter(Files::isRegularFile).toList()) {
                content.put(from.relativize(file), Files.readAllBytes(file));
            }
        }
        copy(from, true);
        go(target);
        await(() -> idle() && !button("paste").isDisabled());
        fx(() -> { button("paste").fire(); return null; });
        await(() -> idle() && content.keySet().stream().allMatch(path -> Files.isRegularFile(target.resolve(path)))
                && button("paste").isDisabled());
        try (var remaining = Files.list(from)) {
            require(remaining.findAny().isEmpty(), "some source entries were not moved");
        }
        for (var file : content.entrySet()) {
            require(Arrays.equals(file.getValue(), Files.readAllBytes(target.resolve(file.getKey()))),
                    "the move changed file bytes");
        }
        require(clipboard.probe().kind() == FileClipboard.Kind.EMPTY, "private cut marker was not consumed");
        System.out.println("Same-window JavaFX cut moved a file and a directory and consumed its native marker");
    }

    private void benchmark() throws Exception {
        Path folder = Files.createDirectory(root.resolve("bench"));
        var paths = new java.util.ArrayList<Path>();
        for (int index = 0; index < 1_000; index++) { paths.add(Files.createFile(folder.resolve("索引-" + index + ".txt"))); }
        var kernel = Native.load("kernel32", Kernel.class);
        var memory = Native.load("psapi", ProcessMemory.class);
        int initial = handles(kernel);
        int peak = initial;
        long initialMemory = privateBytes(kernel, memory);
        long memoryPeak = initialMemory;
        for (int count : List.of(1, 1_000)) {
            var group = List.copyOf(paths.subList(0, count));
            int samples = count == 1 ? 5 : 3;
            long[] writes = new long[samples];
            long[] reads = new long[samples];
            long[] captures = new long[samples];
            for (int sample = -1; sample < samples; sample++) {
                var offer = new FileClipboard.Offer(UUID.randomUUID(), false, group);
                long start = System.nanoTime();
                var state = clipboard.publish(offer);
                long published = System.nanoTime();
                var received = clipboard.read();
                long decoded = System.nanoTime();
                require(state.equals(received.state()) && offer.token().equals(received.token()), "version changed after publication");
                for (var path : received.paths()) {
                    require(FileChanges.capture(LocalFiles.describe(path)).nativeItem() != null, "native identity unavailable");
                }
                long captured = System.nanoTime();
                require(clipboard.replace(state, offer.token(), null).applied(), "atomic release failed");
                if (sample >= 0) {
                    writes[sample] = published - start;
                    reads[sample] = decoded - published;
                    captures[sample] = captured - decoded;
                }
                peak = Math.max(peak, handles(kernel));
                memoryPeak = Math.max(memoryPeak, privateBytes(kernel, memory));
            }
            System.out.printf(Locale.ROOT, "items=%d publish=%.2f ms read=%.2f ms capture=%.2f ms (median of %d)%n",
                    count, median(writes), median(reads), median(captures), samples);
        }
        var sources = List.of(paths.getFirst());
        var oldCut = new FileClipboard.Offer(UUID.randomUUID(), true, sources);
        var oldState = clipboard.publish(oldCut);
        var replacement = new FileClipboard.Offer(UUID.randomUUID(), false, sources);
        var newState = clipboard.publish(replacement);
        require(!clipboard.replace(oldState, oldCut.token(), null).applied(), "obsolete cut cleared the new offer");
        require(replacement.token().equals(clipboard.read().token()), "new clipboard was not preserved");
        require(clipboard.replace(newState, replacement.token(), null).applied(), "new fixture clipboard did not clear");
        System.out.println("Native compare-and-replace preserved the new clipboard");
        System.out.printf(Locale.ROOT, "process handles initial/peak/final=%d/%d/%d%n", initial, peak, handles(kernel));
        System.out.printf(Locale.ROOT, "process private memory initial/peak/final=%.1f/%.1f/%.1f MiB%n",
                initialMemory / 1048576.0, memoryPeak / 1048576.0, privateBytes(kernel, memory) / 1048576.0);
    }

    private void measureBusyClipboard() throws Exception {
        long start = System.nanoTime();
        try {
            clipboard.read();
            throw new AssertionError("lock contention accepted");
        } catch (java.io.IOException busy) {
            require(busy.getMessage().contains("占用"), "wrong lock error");
        }
        System.out.printf(Locale.ROOT, "native lock timeout=%.2f ms%n", (System.nanoTime() - start) / 1e6);
    }

    private static long privateBytes(Kernel kernel, ProcessMemory memory) {
        try (var counters = new Memory(80)) {
            counters.clear();
            counters.setInt(0, 80);
            require(memory.GetProcessMemoryInfo(kernel.GetCurrentProcess(), counters, 80), "cannot sample process memory");
            return counters.getLong(72);
        }
    }

    private static int handles(Kernel kernel) {
        var count = new IntByReference();
        require(kernel.GetProcessHandleCount(kernel.GetCurrentProcess(), count), "cannot count process handles");
        return count.getValue();
    }

    private static double median(long[] times) {
        Arrays.sort(times);
        return times[times.length / 2] / 1e6;
    }

    private void go(Path path) throws Exception {
        fx(() -> {
            button("edit-address").fire();
            var editor = (TextField) scene.lookup("#address");
            editor.setText(path.toString());
            editor.fireEvent(new ActionEvent());
            return null;
        });
        await(() -> idle() && ((Label) scene.lookup("#status")).getText().contains("个项目"));
    }

    private Button button(String name) { return (Button) scene.lookup("#" + name); }
    private boolean idle() throws Exception {
        return field(app, "browseRequest") == null && field(app, "fileTask") == null && field(app, "clipboardCommand") == null;
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var work = new FutureTask<>(action);
        Platform.runLater(work);
        return work.get(10, TimeUnit.SECONDS);
    }

    private void await(Callable<Boolean> check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
        while (!fx(check)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(fx(() -> ((Label) scene.lookup("#status")).getText()
                        + " | " + ((Label) scene.lookup("#clipboard-status")).getText()));
            }
            Thread.sleep(20);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }

    @FunctionalInterface
    private interface Work { void run() throws Exception; }
}

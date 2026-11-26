package edu.files;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.AbstractPreferences;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.WINDOWS)
@EnabledIfSystemProperty(named = "javafx.integration", matches = "true")
class JavaFxFileOperationsTest {
    @TempDir Path root;
    private FileManagerApp app;
    private Stage stage;
    private Scene scene;
    private BrowserView browser;
    private final AtomicBoolean pauseNativeCopy = new AtomicBoolean();
    private final AtomicBoolean loseMoveReceipt = new AtomicBoolean();
    private final AtomicBoolean loseUndoReceipt = new AtomicBoolean();
    private final AtomicInteger injectedWrites = new AtomicInteger();
    private final CountDownLatch copyHasBytes = new CountDownLatch(1);
    private final CountDownLatch releaseCopy = new CountDownLatch(1);
    private final AtomicReference<ReadGate> heldRead = new AtomicReference<>();
    private final AtomicReference<Gate> heldPublish = new AtomicReference<>();
    private Path captures;

    @Test
    void windowsWorkflowRestoresLatestBytesAndKeepsPartialCutInSync() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null, "requires Windows x64 local NTFS");
        Path from = Files.createDirectory(root.resolve("from"));
        Path to = Files.createDirectory(root.resolve("to"));
        Path file = Files.writeString(from.resolve("sample.txt"), "preview text");

        var ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        try {
            fx(() -> {
                app = new FileManagerApp(new MemoryPreferences(null, ""), this::operation, this::readReady);
                stage = new Stage();
                stage.setOpacity(0);
                app.start(stage);
                scene = stage.getScene();
                browser = (BrowserView) field(app, "browser");
                return null;
            });
            await(() -> idle() && field(app, "places") != null, "home page", 35);

            go(from);
            select(file);
            menu("viewItems", "show-preview");
            await(() -> scene.lookup("#inspector") instanceof javafx.scene.layout.VBox inspector
                    && inspector.getChildren().stream().anyMatch(node -> node instanceof TextArea text
                        && text.getText().contains("preview text")), "text preview", 10);

            click("copy");
            await(() -> idle() && cutSources().equals(List.of(file)), "captured copy intent", 8);
            go(to);
            click("paste");
            Path copied = to.resolve(file.getFileName());
            await(() -> idle() && listed(copied), "paste without confirmation", 10);
            assertEquals("preview text", Files.readString(copied));
            assertEquals("preview text", Files.readString(file));

            select(copied);
            click("rename");
            await(() -> idle() && scene.lookup("#new-name-editor") != null, "inline editor", 10);
            fx(() -> {
                var editor = (TextField) scene.lookup("#new-name-editor");
                editor.setText("renamed.txt");
                Event.fireEvent(editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                        false, false, false, false));
                return null;
            });
            Path renamed = to.resolve("renamed.txt");
            await(() -> idle() && listed(renamed), "rename without confirmation", 10);
            Files.writeString(renamed, "latest edit");
            menu("moreItems", "undo");
            await(() -> idle() && listed(copied), "rename undo", 10);
            assertEquals("latest edit", Files.readString(copied));

            runPartialCut();
            runLongCopy();
            runUnknownMoveAndUndo();
            runReadRaces();
            runWriteAgainstNavigation(false);
            runWriteAgainstNavigation(true);
            runShutdownDuringCommit();
        } finally {
            releaseCopy.countDown();
            if (heldRead.get() != null) { heldRead.get().gate().release.countDown(); }
            if (heldPublish.get() != null) { heldPublish.get().release.countDown(); }
            fx(() -> {
                for (var window : List.copyOf(Window.getWindows())) {
                    if (window != stage && window.getScene().getRoot() instanceof DialogPane) { window.hide(); }
                }
                if (app != null) { app.stop(); }
                if (stage != null) { stage.hide(); }
                return null;
            });
            Platform.exit();
        }
    }

    private FileOperationTask operation(FileOperationTask.Kind kind, List<FileChanges.Change> changes) {
        boolean loseReceipt = switch (kind) {
            case MOVE -> loseMoveReceipt.getAndSet(false);
            case UNDO -> loseUndoReceipt.getAndSet(false);
            default -> false;
        };
        if (loseReceipt) {
            var change = changes.getFirst();
            var uncertain = new FileChanges.Change(change.path(), change.folders(), change.evidence(), change.copyBytes(), task -> {
                var effect = change.action().run(task);
                assertTrue(effect.changed());
                injectedWrites.incrementAndGet();
                throw LocalFiles.uncertain("夹具已执行写入并丢失回执", new IOException("test receipt"),
                        change.path(), effect.receipt().target());
            });
            return new FileOperationTask(kind, List.of(uncertain));
        }
        return new FileOperationTask(kind, changes, sample -> {
            Gate publish = heldPublish.get();
            if (kind == FileOperationTask.Kind.COPY && sample.phase() == FileOperationTask.Phase.PUBLISHING
                    && publish != null && heldPublish.compareAndSet(publish, null)) {
                publish.hold();
            }
            if (kind == FileOperationTask.Kind.COPY && sample.transferredBytes() > 0
                    && pauseNativeCopy.compareAndSet(true, false)) {
                copyHasBytes.countDown();
                try {
                    assertTrue(releaseCopy.await(45, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
        });
    }

    private void runLongCopy() throws Exception {
        Path from = Files.createDirectory(root.resolve("progress-source"));
        Path to = Files.createDirectory(root.resolve("progress-target"));
        Path browse = Files.createDirectory(root.resolve("browse-during-copy"));
        Path readable = Files.writeString(browse.resolve("阶段验证.txt"), "写入期间可以读取这个文件");
        Path foreign = Files.createDirectory(to.resolve(".file-manager-user-fixture"));
        Path file = Files.write(from.resolve("样本视频.bin"), new byte[48 * 1024 * 1024]);
        go(from);
        select(file);
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(file)), "copy selection", 10);
        go(to);
        pauseNativeCopy.set(true);
        click("paste");
        assertTrue(copyHasBytes.await(10, TimeUnit.SECONDS));
        await(() -> progressScene() != null && progressLabel("operation-bytes").contains("48.0 MiB"),
                "delayed JavaFX progress window", 10);
        assertFalse(fx(() -> button("edit-address").isDisabled()),
                "The address bar should accept navigation while the native copy is waiting");
        Path stageFile;
        try (var entries = Files.list(to)) {
            stageFile = entries.filter(path -> path.getFileName().toString().startsWith(".file-manager-"))
                    .filter(path -> !path.equals(foreign)).findFirst().orElseThrow();
        }
        click("refresh");
        await(() -> field(app, "browseRequest") == null && listed(foreign), "foreign staging prefix remains visible", 10);
        assertFalse(fx(() -> listed(stageFile)));
        go(browse);
        click("back");
        await(() -> browseAt(to), "back during copy", 10);
        click("forward");
        await(() -> browseAt(browse), "forward during copy", 10);
        fx(() -> {
            ((TextField) scene.lookup("#search-field")).setText("阶段验证");
            button("search").fire();
            return null;
        });
        await(() -> browseAt(browse) && "阶段验证".equals(field(app, "activeQuery")) && listed(readable),
                "search during copy", 10);
        select(readable);
        await(() -> previewText().contains("写入期间可以读取"), "preview during copy", 10);
        fx(() -> {
            for (String command : List.of("paste", "cut", "copy", "rename", "trash")) {
                assertTrue(button(command).isDisabled(), command);
            }
            var close = new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST);
            Event.fireEvent(stage, close);
            assertTrue(stage.isShowing());
            progressScene().getWindow().hide();
            button("file-operations").fire();
            return null;
        });
        assertFalse(fx(() -> ((FileOperationTask) field(app, "activeFileOperation")).cancellationRequested()));
        click("clear-search");
        await(() -> browseAt(browse) && "".equals(field(app, "activeQuery")) && listed(readable),
                "clear search during copy", 10);
        select(readable);
        await(() -> previewText().contains("写入期间可以读取"), "preview after leaving search", 10);
        captureBrowsing();
        capture("copying.png");
        assertFalse(Files.exists(to.resolve(file.getFileName())));
        long request = System.nanoTime();
        fx(() -> {
            ((Button) progressScene().lookup("#operation-action")).fire();
            assertTrue(((FileOperationTask) field(app, "activeFileOperation")).cancellationRequested());
            assertTrue(progressLabel("operation-stage").contains("取消"));
            return null;
        });
        long confirmed = System.nanoTime();
        releaseCopy.countDown();
        await(() -> idle() && "已取消".equals(progressLabel("operation-stage")), "cancelled result", 10);
        System.out.printf("PHASE23_GUI cancel_event_ms=%.2f cancel_to_final_ui_ms=%.2f%n",
                (confirmed - request) / 1e6, (System.nanoTime() - request) / 1e6);
        capture("cancelled.png");
        assertEquals(48L * 1024 * 1024, Files.size(file));
        try (var paths = Files.list(to)) { assertEquals(List.of(foreign), paths.toList()); }
        assertTrue(fx(() -> browseAt(browse)));
        fx(() -> {
            ((Button) progressScene().lookup("#operation-action")).fire();
            return null;
        });
    }

    private void runReadRaces() throws Exception {
        Path origin = Files.createDirectory(root.resolve("race-origin"));
        Path slow = Files.createDirectory(root.resolve("race-slow"));
        Path next = Files.createDirectory(root.resolve("race-next"));
        Path first = Files.writeString(next.resolve("a.txt"), "obsolete preview");
        Path second = Files.writeString(next.resolve("b.txt"), "current preview");
        go(origin);
        var listing = new Gate();
        heldRead.set(new ReadGate(slow, false, listing));
        try {
            enter(slow);
            assertTrue(listing.entered.await(8, TimeUnit.SECONDS));
            go(next);
            assertEquals(origin, fx(() -> call(((java.util.Deque<?>) field(app, "past")).peek(), "path")));
            listing.release.countDown();
            assertTrue(listing.returned.await(8, TimeUnit.SECONDS));
            assertTrue(fx(() -> browseAt(next)));
        } finally {
            listing.release.countDown();
        }

        var preview = new Gate();
        heldRead.set(new ReadGate(first, true, preview));
        try {
            select(first);
            assertTrue(preview.entered.await(8, TimeUnit.SECONDS));
            select(second);
            await(() -> previewText().contains("current preview"), "new preview wins", 8);
            preview.release.countDown();
            assertTrue(preview.returned.await(8, TimeUnit.SECONDS));
            assertTrue(fx(() -> previewText().contains("current preview")));
        } finally {
            preview.release.countDown();
        }

        int history = fx(() -> ((java.util.Deque<?>) field(app, "past")).size());
        enter(root.resolve("absent-directory"));
        acknowledgeFileError();
        assertTrue(fx(() -> browseAt(next) && listed(second)));
        assertEquals(history, fx(() -> ((java.util.Deque<?>) field(app, "past")).size()));

        Path replaced = Files.createDirectory(root.resolve("replaced-directory"));
        Files.writeString(replaced.resolve("old.txt"), "old object");
        var replace = new Gate();
        heldRead.set(new ReadGate(replaced, false, replace));
        try {
            enter(replaced);
            assertTrue(replace.entered.await(8, TimeUnit.SECONDS));
            Files.move(replaced, root.resolve("original-directory"));
            Files.createDirectory(replaced);
            Path newFile = Files.writeString(replaced.resolve("new.txt"), "new object");
            replace.release.countDown();
            acknowledgeFileError();
            assertTrue(fx(() -> browseAt(next)));
            go(replaced);
            assertTrue(fx(() -> listed(newFile)));
            Files.move(replaced, root.resolve("moved-replacement"));
            click("refresh");
            acknowledgeFileError();
            assertTrue(fx(() -> browseAt(replaced) && browser.items().isEmpty()
                    && status().contains("当前位置无法访问") && !button("back").isDisabled()));
            click("back");
            await(() -> browseAt(next), "recover via history", 10);
        } finally {
            replace.release.countDown();
        }
    }

    private void runWriteAgainstNavigation(boolean affected) throws Exception {
        Path sandbox = Files.createDirectory(root.resolve("write-refresh-" + affected));
        Path from = Files.createDirectory(sandbox.resolve("source"));
        Path to = Files.createDirectory(sandbox.resolve("target"));
        Path elsewhere = Files.createDirectory(sandbox.resolve("elsewhere"));
        Path source = Files.writeString(from.resolve("published.txt"), "completed copy");
        Path published = to.resolve(source.getFileName());
        go(from);
        select(source);
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(source)), "copy for refresh race", 8);
        go(to);
        var publish = new Gate();
        var pending = new Gate();
        heldPublish.set(publish);
        try {
            click("paste");
            assertTrue(publish.entered.await(8, TimeUnit.SECONDS));
            Path requested = affected ? to : elsewhere;
            heldRead.set(new ReadGate(requested, false, pending));
            enter(requested);
            assertTrue(pending.entered.await(8, TimeUnit.SECONDS));
            publish.release.countDown();
            if (affected) {
                await(() -> idle() && browseAt(to) && listed(published), "fresh read supersedes affected result", 10);
            } else {
                await(() -> field(app, "fileTask") == null && field(app, "browseRequest") != null,
                        "unrelated navigation still owns its request", 10);
            }
            pending.release.countDown();
            await(() -> idle() && browseAt(requested), "preserve latest navigation", 10);
            assertEquals("completed copy", Files.readString(published));
            assertEquals("completed copy", Files.readString(source));
        } finally {
            publish.release.countDown();
            pending.release.countDown();
        }
    }

    private record ReadGate(Path path, boolean preview, Gate gate) { }

    private void runShutdownDuringCommit() throws Exception {
        Path from = Files.createDirectory(root.resolve("exit-source"));
        Path to = Files.createDirectory(root.resolve("exit-target"));
        Path file = Files.writeString(from.resolve("closing.txt"), "accepted before closing");
        go(from);
        select(file);
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(file)), "copy for shutdown", 8);
        go(to);
        var publication = new Gate();
        heldPublish.set(publication);
        try {
            click("paste");
            assertTrue(publication.entered.await(8, TimeUnit.SECONDS));
            var writer = fx(() -> {
                app.stop();
                return (ExecutorService) field(app, "writes");
            });
            assertTrue(writer.isShutdown());
            assertFalse(writer.isTerminated());
            publication.release.countDown();
            assertTrue(writer.awaitTermination(8, TimeUnit.SECONDS));
            assertEquals("accepted before closing", Files.readString(to.resolve(file.getFileName())));
            assertEquals("accepted before closing", Files.readString(file));
            try (var children = Files.list(to)) { assertEquals(1, children.count()); }
        } finally {
            publication.release.countDown();
        }
    }

    private void readReady(Path path, boolean preview) {
        var pending = heldRead.get();
        if (pending != null && preview == pending.preview() && path.equals(pending.path())
                && heldRead.compareAndSet(pending, null)) {
            pending.gate().hold();
        }
    }

    private static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch returned = new CountDownLatch(1);

        void hold() {
            entered.countDown();
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        assertTrue(release.await(20, TimeUnit.SECONDS), "test gate was not released");
                        return;
                    } catch (InterruptedException cancelled) {
                        interrupted = true;
                    }
                }
            } finally {
                returned.countDown();
                if (interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void runUnknownMoveAndUndo() throws Exception {
        Path from = Files.createDirectory(root.resolve("unknown-source"));
        Path to = Files.createDirectory(root.resolve("unknown-target"));
        Path fresh = Files.createDirectory(root.resolve("fresh-target"));
        Path original = Files.writeString(from.resolve("待核查.txt"), "original object");
        Path published = to.resolve(original.getFileName());
        go(from);
        select(original);
        click("cut");
        await(() -> idle() && cutSources().equals(List.of(original)), "cut captured", 10);
        go(to);
        loseMoveReceipt.set(true);
        click("paste");
        acknowledgeFileError();
        await(() -> idle() && cutSources().isEmpty() && listed(published), "unknown move result", 12);
        Files.writeString(original, "replacement object");
        fx(() -> {
            assertTrue(button("paste").isDisabled());
            button("paste").fire();
            button("unknown-results").fire();
            return null;
        });
        await(() -> unknownDialog() != null, "read-only findings", 12);
        fx(() -> {
            var pane = unknownDialog();
            @SuppressWarnings("unchecked")
            var paths = (ComboBox<Path>) pane.lookup("#unknown-path");
            assertEquals(original, paths.getValue());
            assertTrue(((TextArea) pane.lookup("#unknown-details")).getText().contains("当前路径观测"));
            var open = pane.getButtonTypes().stream().filter(button -> button.getText().contains("重新选择"))
                    .findFirst().orElseThrow();
            ((Button) pane.lookupButton(open)).fire();
            return null;
        });
        await(() -> idle() && listed(original) && cutSources().isEmpty(), "reselect from current directory", 10);
        assertEquals("replacement object", Files.readString(original));
        assertEquals("original object", Files.readString(published));
        select(original);
        click("cut");
        await(() -> idle() && cutSources().equals(List.of(original)), "new cut request", 10);
        go(fresh);
        click("paste");
        Path moved = fresh.resolve(original.getFileName());
        await(() -> idle() && listed(moved), "new move applied", 10);
        assertEquals("replacement object", Files.readString(moved));

        loseUndoReceipt.set(true);
        menu("moreItems", "undo");
        acknowledgeFileError();
        await(() -> idle() && !listed(moved), "unknown undo result", 10);
        fx(() -> {
            call(app, "undo");
            return null;
        });
        assertEquals(2, injectedWrites.get());
        assertEquals("replacement object", Files.readString(original));
        assertEquals("original object", Files.readString(published));
        assertFalse(Files.exists(moved));
        assertTrue(fx(() -> ((List<?>) field(app, "undo")).isEmpty()));
    }

    private void acknowledgeFileError() throws Exception {
        await(() -> errorDialog() != null, "file error", 12);
        fx(() -> {
            ((Button) errorDialog().lookupButton(ButtonType.OK)).fire();
            return null;
        });
    }

    private Scene progressScene() {
        return Window.getWindows().stream().map(Window::getScene)
                .filter(scene -> scene != null && scene.lookup("#operation-progress") != null)
                .findFirst().orElse(null);
    }

    private String progressLabel(String id) {
        Scene progress = progressScene();
        return progress == null ? "" : ((Label) progress.lookup("#" + id)).getText();
    }

    private DialogPane unknownDialog() {
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window.getScene().getRoot() instanceof DialogPane pane && pane.lookup("#unknown-details") != null) {
                window.setOpacity(0);
                return pane;
            }
        }
        return null;
    }

    private void capture(String name) throws Exception {
        if (!Boolean.getBoolean("javafx.captureFileOperations")) { return; }
        if (captures == null) {
            captures = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "phase23-fx-images-");
            System.out.println("PHASE23_GUI captures=" + captures);
        }
        BufferedImage image = fx(() -> {
            var snapshot = progressScene().snapshot(null);
            int width = (int) snapshot.getWidth();
            int height = (int) snapshot.getHeight();
            var argb = new int[width * height];
            snapshot.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);
            var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            result.setRGB(0, 0, width, height, argb, 0, width);
            return result;
        });
        assertTrue(ImageIO.write(image, "png", captures.resolve(name).toFile()));
    }

    private void captureBrowsing() throws Exception {
        if (!Boolean.getBoolean("javafx.captureFileOperations")) { return; }
        if (captures == null) {
            captures = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "phase24-fx-images-");
            System.out.println("PHASE24_GUI captures=" + captures);
        }
        BufferedImage picture = fx(() -> {
            // Capture the actual file list and preview, independent of machine-specific sidebar entries.
            var workspace = scene.lookup("#workspace-split");
            var main = (javafx.scene.control.SplitPane) workspace;
            var progress = progressScene().snapshot(null);
            var filePane = main.getItems().getLast();
            var bounds = filePane.getLayoutBounds();
            var capture = new SnapshotParameters();
            capture.setViewport(new Rectangle2D(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), progress.getHeight()));
            var files = filePane.snapshot(capture, null);
            int width = (int) (files.getWidth() + progress.getWidth());
            int height = (int) Math.max(files.getHeight(), progress.getHeight());
            var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            copyPixels(files, result, 0);
            copyPixels(progress, result, (int) files.getWidth());
            return result;
        });
        assertTrue(ImageIO.write(picture, "png", captures.resolve("browse-during-copy.png").toFile()));
    }

    private static void copyPixels(javafx.scene.image.Image image, BufferedImage destination, int offset) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        var argb = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);
        destination.setRGB(offset, 0, width, height, argb, 0, width);
    }

    private void runPartialCut() throws Exception {
        Path from = Files.createDirectory(root.resolve("batch-from"));
        Path to = Files.createDirectory(root.resolve("batch-to"));
        Path first = Files.writeString(from.resolve("a.txt"), "first");
        Path second = Files.writeString(from.resolve("b.txt"), "second");
        Path third = Files.writeString(from.resolve("c.txt"), "third");
        Files.writeString(to.resolve("b.txt"), "occupant");
        go(from);
        fx(() -> { browser.selectAll(); return null; });
        click("cut");
        await(() -> idle() && cutSources().equals(List.of(first, second, third)), "captured cut intent", 8);

        go(to);
        click("paste");
        await(() -> errorDialog() != null, "conflict dialog", 12);
        fx(() -> {
            var pane = errorDialog();
            assertTrue(pane.getContentText().contains("b.txt"));
            ((Button) pane.lookupButton(ButtonType.OK)).fire();
            return null;
        });
        Path published = to.resolve("a.txt");
        await(() -> idle() && status().contains("已移动 1 项 · 失败 1 项 · 未执行 1 项"),
                "partial write summary", 12);
        assertEquals(List.of("a.txt", "b.txt"), fx(() -> browser.items().stream().map(BrowserItem::name).toList()));
        assertEquals(List.of(second, third), fx(this::cutSources));
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(to.resolve("c.txt")));
        assertEquals("occupant", Files.readString(to.resolve("b.txt")));

        Files.writeString(published, "new bytes after moving");
        menu("moreItems", "undo");
        await(() -> idle() && !listed(published) && status().contains("已撤消 1 项"), "partial batch undo", 10);
        assertEquals("new bytes after moving", Files.readString(first));
        assertEquals("second", Files.readString(second));
        assertEquals("third", Files.readString(third));
        assertEquals(List.of(second, third), fx(this::cutSources));
        assertEquals("occupant", Files.readString(to.resolve("b.txt")));
    }

    private void go(Path directory) throws Exception {
        enter(directory);
        await(() -> browseAt(directory), "directory " + directory, 12);
    }

    private boolean browseAt(Path directory) throws Exception {
        return field(app, "browseRequest") == null && directory.equals(call(field(app, "location"), "path"));
    }

    private void enter(Path directory) throws Exception {
        fx(() -> {
            button("edit-address").fire();
            var editor = (TextField) scene.lookup("#address");
            editor.setText(directory.toString());
            editor.fireEvent(new ActionEvent());
            return null;
        });
    }

    private String previewText() {
        if (!(scene.lookup("#inspector") instanceof javafx.scene.layout.VBox inspector)) { return ""; }
        return inspector.getChildren().stream().filter(TextArea.class::isInstance).map(TextArea.class::cast)
                .map(TextArea::getText).findFirst().orElse("");
    }

    private void select(Path file) throws Exception {
        assertTrue(fx(() -> browser.reveal(file)));
    }

    private Button button(String id) {
        return (Button) scene.lookup("#" + id);
    }

    private void click(String id) throws Exception {
        fx(() -> {
            assertFalse(button(id).isDisabled(), id);
            button(id).fire();
            return null;
        });
    }

    private void menu(String getter, String command) throws Exception {
        fx(() -> {
            for (Object item : (List<?>) call(app, getter)) {
                if (item instanceof MenuItem entry && command.equals(entry.getId())) {
                    assertFalse(entry.isDisable());
                    entry.fire();
                    return null;
                }
            }
            return fail("Missing command " + command);
        });
    }

    private DialogPane errorDialog() {
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window instanceof Stage dialog && "文件操作".equals(dialog.getTitle())
                    && dialog.getScene().getRoot() instanceof DialogPane pane) {
                dialog.setOpacity(0);
                return pane;
            }
        }
        return null;
    }

    private List<Path> cutSources() throws Exception {
        Object clipboard = field(app, "transfer");
        if (clipboard == null) { return List.of(); }
        return ((List<?>) call(clipboard, "sources")).stream()
                .map(item -> ((FileChanges.Selection) item).path()).toList();
    }

    private boolean listed(Path path) {
        return browser.items().stream().anyMatch(item -> path.equals(item.path()));
    }

    private boolean idle() throws Exception {
        return field(app, "fileTask") == null && field(app, "browseRequest") == null;
    }
    private String status() { return ((Label) scene.lookup("#status")).getText(); }

    private void await(Callable<Boolean> check, String phase, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        do {
            if (fx(check)) { return; }
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        fail(phase + " timed out. Current status: " + fx(this::status));
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var future = new FutureTask<>(action);
        Platform.runLater(future);
        return future.get(12, TimeUnit.SECONDS);
    }

    private static Object field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private static Object call(Object value, String name) throws Exception {
        Method method = value.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(value);
    }

    static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> settings = new LinkedHashMap<>();

        MemoryPreferences(AbstractPreferences parent, String name) { super(parent, name); }
        protected void putSpi(String key, String value) { settings.put(key, value); }
        protected String getSpi(String key) { return settings.get(key); }
        protected void removeSpi(String key) { settings.remove(key); }
        protected void removeNodeSpi() { settings.clear(); }
        protected String[] keysSpi() { return settings.keySet().toArray(String[]::new); }
        protected String[] childrenNamesSpi() { return new String[0]; }
        protected AbstractPreferences childSpi(String name) { return new MemoryPreferences(this, name); }
        protected void syncSpi() { }
        protected void flushSpi() { }
    }
}

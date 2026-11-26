package edu.files;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
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
                app = new FileManagerApp(new MemoryPreferences(null, ""));
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
        } finally {
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
        fx(() -> {
            button("edit-address").fire();
            var editor = (TextField) scene.lookup("#address");
            editor.setText(directory.toString());
            editor.fireEvent(new ActionEvent());
            return null;
        });
        await(() -> idle() && directory.equals(call(field(app, "location"), "path")), "directory " + directory, 12);
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

    private boolean idle() throws Exception { return field(app, "activeTask") == null; }
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

    private static final class MemoryPreferences extends AbstractPreferences {
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

package edu.files;

import com.sun.javafx.scene.SceneHelper;
import com.sun.javafx.scene.input.DragboardHelper;
import com.sun.javafx.tk.TKClipboard;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
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
    private final AtomicReference<ReadGate> heldAncestor = new AtomicReference<>();
    private final AtomicReference<Path> failedAncestor = new AtomicReference<>();
    private final AtomicReference<Gate> heldPublish = new AtomicReference<>();
    private final AtomicReference<Gate> heldMove = new AtomicReference<>();
    private final AtomicInteger browseCommits = new AtomicInteger();
    private final AtomicReference<CompletableFuture<Long>> timedCommit = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Long>> timedDragTask = new AtomicReference<>();
    private final AtomicReference<FileDrag.Intent> dragIntent = new AtomicReference<>(FileDrag.Intent.COPY);
    private Map<Path, CompletableFuture<Long>> timedPanes = Map.of();
    private Path captures;

    @Test
    void professionalLayoutsShareNavigationAndTheCurrentFileSelection() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path parent = Files.createDirectories(root.resolve("project/data"));
        Path left = Files.createDirectory(parent.resolve("branch-one"));
        Path right = Files.createDirectory(parent.resolve("branch-two"));
        Path file = Files.writeString(left.resolve("report.txt"), "source");
        Path child = Files.createDirectory(left.resolve("subdirectory"));
        openWindow(true);
        goWatched(left);
        menu("viewItems", "mode-large");
        pickLayout("columns");
        await(() -> branchEntries(parent).containsAll(List.of(left, right)), "ancestor siblings", 8);
        fx(() -> {
            var hierarchy = (ScrollPane) scene.lookup("#miller-columns");
            assertEquals(hierarchy.getHmax(), hierarchy.getHvalue());
            assertTrue(hierarchy.getContent().getBoundsInLocal().getWidth() > hierarchy.getViewportBounds().getWidth());
            return null;
        });
        select(file);
        await(this::hasSelectionProof, "verified current file", 8);
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(file)), "copy binds the current column", 8);
        fx(() -> {
            var branches = scene.getRoot().lookupAll(".miller-column").stream()
                    .filter(node -> parent.equals(node.getUserData())).findFirst().orElseThrow()
                    .lookup(".miller-branches");
            shortcut(branches, KeyCode.V, false);
            assertNull(field(app, "clipboardCommand"), "navigation cells cannot start a paste");
            clickColumnRow(".miller-branches .list-cell", right);
            assertTrue(button("copy").isDisabled());
            return null;
        });
        await(() -> browseAt(right) && browser.items().isEmpty(), "sibling branch", 8);
        clickOnPane(false, "back");
        await(() -> browseAt(left) && listed(file), "back from sibling", 8);
        clickOnPane(false, "forward");
        await(() -> browseAt(right), "forward to sibling", 8);
        go(left);
        fx(() -> {
            clickColumnRow(".miller-entries .selectable-entry", child);
            return null;
        });
        await(() -> browseAt(child), "single click in current column", 8);
        clickOnPane(false, "back");
        await(() -> browseAt(left), "back to file directory", 8);
        Path external = Files.writeString(left.resolve("external.txt"), "external change");
        await(() -> listed(external), "watch updates the current column", 10);
        pickLayout("hybrid");
        fx(() -> {
            assertNotNull(scene.lookup("#hybrid-browser"));
            assertTrue(branchEntries(left).contains(child));
            assertEquals(BrowserView.Mode.LARGE, currentBrowse().view().mode());
            assertTrue(scene.lookup(".browser-tiles").isVisible());
            var search = (TextField) scene.lookup("#search-field");
            search.setText("report");
            search.fireEvent(new ActionEvent());
            return null;
        });
        await(() -> "report".equals(currentBrowse().activeQuery) && scene.lookup("#miller-columns") == null,
                "traditional search results", 8);
        clickOnPane(false, "clear-search");
        await(() -> currentBrowse().activeQuery.isEmpty() && scene.lookup("#hybrid-browser") != null,
                "restore hybrid after search", 8);
        click("dual-pane");
        await(() -> paneAt(true, left) && scene.lookup("#miller-columns") == null, "traditional dual pane", 8);
        click("dual-pane");
        await(() -> scene.lookup("#hybrid-browser") != null, "restore hybrid after dual pane", 8);
        pickLayout("traditional");
        assertTrue(fx(() -> scene.lookup("#miller-columns") == null && browseAt(left)
                && currentBrowse().view().mode() == BrowserView.Mode.LARGE));
    }

    @Test
    void ancestorReadsRefreshFailAndDiscardResultsAfterTabChanges() throws Exception {
        Path parent = Files.createDirectory(root.resolve("first"));
        Path old = Files.createDirectory(parent.resolve("old"));
        Path next = Files.createDirectories(root.resolve("second/next"));
        openWindow(false);
        go(old);
        var delayed = new Gate();
        heldAncestor.set(new ReadGate(parent, false, delayed));
        pickLayout("columns");
        try {
            assertTrue(delayed.entered.await(8, TimeUnit.SECONDS));
            await(() -> scene.getRoot().lookupAll(".miller-message").stream().anyMatch(node ->
                    node.isVisible() && ((Label) node).getText().equals("正在读取…")), "loading ancestor", 8);
            var previous = fx(() -> tabs().getSelectionModel().getSelectedItem());
            click("new-tab");
            await(() -> browseAt(old) && currentBrowse() != owner(previous), "new tab", 8);
            fx(() -> { close(previous); return null; });
            go(next);
            failedAncestor.set(next.getParent());
            pickLayout("hybrid");
            await(() -> scene.getRoot().lookupAll(".miller-message").stream().anyMatch(node ->
                    node.isVisible() && ((Label) node).getText().contains("无法读取")), "ancestor read error", 8);
            delayed.release.countDown();
            assertTrue(delayed.returned.await(8, TimeUnit.SECONDS));
            assertTrue(fx(() -> scene.getRoot().lookupAll(".miller-column").stream().noneMatch(node -> parent.equals(node.getUserData()))));
            Path added = Files.createDirectory(next.getParent().resolve("new sibling"));
            clickOnPane(false, "refresh");
            await(() -> branchEntries(next.getParent()).contains(added), "refresh recovers ancestor", 8);
        } finally {
            delayed.release.countDown();
        }
    }

    private void pickLayout(String layout) throws Exception {
        fx(() -> {
            var button = (MenuButton) scene.lookup("#browse-layout");
            assertFalse(button.isDisabled());
            button.show();
            try {
                button.getItems().stream().filter(item -> ("layout-" + layout).equals(item.getId()))
                        .findFirst().orElseThrow().fire();
            } finally {
                button.hide();
            }
            return null;
        });
    }

    private List<Path> branchEntries(Path folder) {
        for (Node node : scene.getRoot().lookupAll(".miller-column")) {
            if (folder.equals(node.getUserData()) && node.lookup(".miller-branches") instanceof ListView<?> rows) {
                return rows.getItems().stream().map(BrowserItem.class::cast).map(BrowserItem::path).toList();
            }
        }
        return List.of();
    }

    private void clickColumnRow(String selector, Path path) {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        Node row = scene.getRoot().lookupAll(selector).stream().filter(node -> {
            Object item = node instanceof ListCell<?> cell ? cell.getItem() : node.getUserData();
            return item instanceof BrowserItem file && path.equals(file.path());
        }).findFirst().orElseThrow();
        Event.fireEvent(row, sceneMouse(MouseEvent.MOUSE_CLICKED, 3, 3, MouseButton.PRIMARY, false));
    }

    @Test
    void tabStripAndNameColumnAdaptToAvailableSpace() throws Exception {
        Files.writeString(root.resolve("layout.txt"), "fixture");
        openWindow(false);
        go(root);
        fx(() -> {
            stage.setWidth(1200);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            var tabs = (TabPane) scene.lookup("#browse-tabs");
            var add = button("new-tab");
            assertTrue(add.localToScene(add.getBoundsInLocal()).getMinX() < 400,
                    "new tab action follows the existing tab");
            @SuppressWarnings("unchecked")
            var table = (TableView<Object>) scene.lookup("#entries");
            double used = table.getVisibleLeafColumns().stream().mapToDouble(column -> column.getWidth()).sum();
            assertTrue(table.getWidth() - used < 25, "name column fills available width");
            var name = table.getVisibleLeafColumns().getFirst();
            double before = name.getWidth();
            table.resizeColumn(name, -50);
            assertTrue(name.getWidth() < before - 25, "manual column resize remains available");
            stage.setWidth(960);
            scene.getRoot().layout();
            assertEquals(root, call(field(currentBrowse(), "location"), "path"));
            assertTrue(tabs.getWidth() > 0);
            return null;
        });
    }

    @Test
    void themeSwitchUpdatesTaskAndConflictWindowsAndLaterDialogs() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path from = Files.createDirectory(root.resolve("theme-source"));
        Path to = Files.createDirectory(root.resolve("theme-target"));
        Path source = Files.writeString(from.resolve("collision.txt"), "source");
        Path target = Files.writeString(to.resolve("collision.txt"), "target");
        openWindow(false);
        go(from);
        select(source);
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(source)), "theme fixture copy request", 8);
        go(to);
        click("paste");
        await(() -> progressScene() != null && conflictScene() != null, "visible task and conflict windows", 10);
        fx(() -> {
            assertBackground((Region) scene.getRoot(), "#EDF1F7");
            fireTheme("theme-dark");
            assertBackground((Region) scene.getRoot(), "#222A36");
            assertBackground((Region) progressScene().getRoot(), "#202733");
            assertBackground((Region) conflictScene().getRoot(), "#202733");
            assertEquals(Color.web("#E6EBF4"), ((Label) conflictScene().lookup(".operation-heading")).getTextFill());
            return null;
        });
        chooseConflict("conflict-skip");
        await(() -> idle() && conflictScene() == null, "conflict skipped", 10);
        assertEquals("target", Files.readString(target));
        fx(() -> {
            var items = (List<?>) call(app, "moreItems");
            var options = items.stream().filter(MenuItem.class::isInstance).map(MenuItem.class::cast)
                    .filter(item -> "options".equals(item.getId())).findFirst().orElseThrow();
            Platform.runLater(options::fire);
            return null;
        });
        await(() -> dialog("文件管理器选项") != null, "new dialog in dark theme", 8);
        fx(() -> {
            DialogPane options = dialog("文件管理器选项");
            assertBackground(options, "#181E28");
            fireTheme("theme-light");
            assertBackground(options, "#FFFFFF");
            ((Button) options.lookupButton(ButtonType.CANCEL)).fire();
            return null;
        });
    }

    @Test
    void themeStylesTooltipsMenusAndAddressSuggestions() throws Exception {
        Path folder = Files.createDirectory(root.resolve("suggestion"));
        openWindow(false);
        go(folder);
        fx(() -> {
            Node anchor = button("edit-address");
            // These real popup windows stay open across a palette change.
            Tooltip tooltip = new Tooltip("编辑路径");
            ContextMenu menu = new ContextMenu(new MenuItem("复制"));
            menu.show(anchor, stage.getX() + 140, stage.getY() + 100);
            tooltip.show(anchor, stage.getX() + 140, stage.getY() + 210);
            try {
                fireTheme("theme-dark");
                Region menuSurface = (Region) menu.getSkin().getNode();
                Region tooltipSurface = (Region) tooltip.getSkin().getNode();
                assertBackground(menuSurface, "#181E28");
                assertBackground(tooltipSurface, "#181E28");
                assertEquals(Color.web("#E6EBF4"), ((Label) menuSurface.lookup(".label")).getTextFill());
            } finally {
                tooltip.hide();
                menu.hide();
            }
            return null;
        });
        fx(() -> {
            button("edit-address").fire();
            AddressBar bar = (AddressBar) field(pane(false), "address");
            Method history = AddressBar.class.getDeclaredMethod("showHistory", boolean.class);
            history.setAccessible(true);
            history.invoke(bar, false);
            return null;
        });
        AddressBar bar = fx(() -> (AddressBar) field(pane(false), "address"));
        Popup popup = fx(() -> (Popup) field(bar, "popup"));
        await(popup::isShowing, "address suggestions in dark theme", 8);
        fx(() -> {
            var choices = (ListView<?>) popup.getScene().lookup("#address-choices");
            assertFalse(choices.getItems().isEmpty());
            assertBackground(choices, "#181E28");
            fireTheme("theme-light");
            assertBackground(choices, "#FFFFFF");
            popup.hide();
            return null;
        });
    }

    @Test
    void themeAndDensityKeepTheSelectedFileInTheVirtualTable() throws Exception {
        Path file = Files.writeString(root.resolve("项目记录.txt"), "selected");
        openWindow(false);
        go(root);
        select(file);
        fx(() -> {
            TableView<?> table = (TableView<?>) scene.lookup("#entries");
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            assertEquals(32, table.getFixedCellSize());
            var row = (TableRow<?>) table.lookupAll(".table-row-cell").stream()
                    .filter(node -> node instanceof TableRow<?> visible && visible.isSelected())
                    .findFirst().orElseThrow();
            assertBackground(row, "#E2ECFF");
            fireTheme("theme-dark");
            assertBackground(row, "#293F67");
            return null;
        });
        fx(() -> {
            var menu = (javafx.scene.control.Menu) ((List<?>) call(activePane(), "viewItems")).getLast();
            var compact = (CheckMenuItem) menu.getItems().stream()
                    .filter(item -> "show-compact".equals(item.getId())).findFirst().orElseThrow();
            compact.setSelected(true);
            compact.fire();
            var table = (TableView<?>) scene.lookup("#entries");
            scene.getRoot().applyCss();
            assertEquals(25, table.getFixedCellSize());
            assertEquals(List.of(file), selectedPaths());
            return null;
        });
    }

    private void fireTheme(String id) throws Exception {
        var preview = (javafx.scene.control.Menu) call(app, "appearanceMenu");
        preview.getItems().stream().filter(item -> id.equals(item.getId())).findFirst().orElseThrow().fire();
    }

    private static void assertBackground(Region node, String hex) {
        assertNotNull(node);
        node.getScene().getRoot().applyCss();
        assertNotNull(node.getBackground(), node::toString);
        assertEquals(Color.web(hex), node.getBackground().getFills().getLast().getFill());
    }

    @BeforeAll
    static void startJavaFx() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
    }

    @AfterAll
    static void stopJavaFx() { Platform.exit(); }

    @AfterEach
    void closeWindow() throws Exception {
        releaseCopy.countDown();
        if (heldRead.get() != null) { heldRead.get().gate().release.countDown(); }
        if (heldPublish.get() != null) { heldPublish.get().release.countDown(); }
        if (heldMove.get() != null) { heldMove.get().release.countDown(); }
        fx(() -> {
            for (var window : List.copyOf(Window.getWindows())) {
                if (window != stage && window.getScene().getRoot() instanceof DialogPane) { window.hide(); }
            }
            if (app != null) { app.stop(); }
            if (stage != null) { stage.hide(); }
            return null;
        });
        if (app != null) {
            for (boolean right : new boolean[]{false, true}) {
                Object pane = pane(right);
                if (pane == null) { continue; }
                var watcher = (DirectoryWatcher) field(pane, "watcher");
                assertTrue(watcher.awaitClosed(Duration.ofSeconds(4)));
                assertEquals(0, watcher.registrationCount());
            }
            var reader = (ExecutorService) field(app, "clipboardWorker");
            assertTrue(reader.awaitTermination(4, TimeUnit.SECONDS));
        }
    }

    private void openWindow(boolean automaticRefresh) throws Exception {
        openWindow(automaticRefresh, new MemoryClipboard(false));
    }

    private void openWindow(boolean automaticRefresh, FileClipboard clipboard) throws Exception {
        var preferences = new MemoryPreferences(null, "");
        if (!automaticRefresh) { preferences.putBoolean("automaticRefresh", false); }
        openWindow(preferences, clipboard);
    }

    private void openWindow(MemoryPreferences preferences, FileClipboard clipboard) throws Exception {
        fx(() -> {
            if (Boolean.getBoolean("javafx.captureTabs") || Boolean.getBoolean("javafx.capturePanes")
                    || Boolean.getBoolean("javafx.captureDrag")) {
                preferences.putBoolean("navigation", false);
            }
            app = new FileManagerApp(preferences, this::operation, new FileManagerApp.ReadObserver() {
                @Override
                public void ready(Path path, boolean preview) { readReady(path, preview); }

                @Override
                public void ancestorReady(Path path) throws IOException {
                    Path failure = failedAncestor.get();
                    if (path.equals(failure) && failedAncestor.compareAndSet(failure, null)) {
                        throw new IOException("simulated ancestor read error");
                    }
                    var read = heldAncestor.get();
                    if (read != null && path.equals(read.path()) && heldAncestor.compareAndSet(read, null)) {
                        read.gate().hold();
                    }
                }

                @Override
                public void committed(Path path, long applyNanos) {
                    browseCommits.incrementAndGet();
                    var sample = timedCommit.getAndSet(null);
                    if (sample != null) { sample.complete(System.nanoTime()); }
                    if (path != null && !timedPanes.isEmpty()) {
                        var paneSample = timedPanes.get(path);
                        if (paneSample != null) { paneSample.complete(System.nanoTime()); }
                    }
                }
            }, clipboard, proposed -> dragIntent.get());
            stage = new Stage();
            stage.setOpacity(0);
            app.start(stage);
            scene = stage.getScene();
            browser = (BrowserView) field(pane(false), "browser");
            return null;
        });
        await(() -> idle() && field(app, "places") != null, "home page", 35);
    }

    @Test
    void newFolderRefusesAReplacedDestinationWhileWaitingForTheWriter() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path directory = Files.createDirectory(root.resolve("chosen"));
        Path former = root.resolve("former-directory");
        Path sample = Files.writeString(directory.resolve("sample.txt"), "keep");
        openWindow(false);
        go(directory);
        var gate = new Gate();
        try {
            ((ExecutorService) fx(() -> field(app, "writes"))).execute(gate::hold);
            assertTrue(gate.entered.await(8, TimeUnit.SECONDS));
            menu("newItems", "new-folder");
            assertNotNull(fx(() -> field(app, "fileTask")));
            Files.move(directory, former);
            Files.createDirectory(directory);
        } finally {
            gate.release.countDown();
        }
        await(this::idle, "created or rejected the queued request", 10);
        try (var entries = Files.list(directory)) { assertEquals(0, entries.count(), "replacement must stay empty"); }
        try (var entries = Files.list(former)) { assertEquals(List.of(former.resolve(sample.getFileName())), entries.toList()); }
        assertEquals("keep", Files.readString(former.resolve(sample.getFileName())));
        assertTrue(fx(() -> errorDialog() != null && errorDialog().getContentText().contains("已变化")));
        assertTrue(fx(() -> ((List<?>) field(app, "undo")).isEmpty()));
        acknowledgeFileError();

        go(former);
        menu("newItems", "new-folder");
        await(() -> idle() && browser.items().stream().anyMatch(item -> item.directory()),
                "creating in a newly selected directory succeeds", 10);
        assertEquals("keep", Files.readString(former.resolve(sample.getFileName())));
        try (var entries = Files.list(former)) {
            assertEquals(1, entries.filter(Files::isDirectory).count());
        }
    }

    @Test
    void malformedPreferencesDoNotBlockStartupOrDiscardValidHistory() throws Exception {
        Path folder = Files.createDirectory(root.resolve("资料"));
        Path sample = Files.writeString(folder.resolve("打开记录.txt"), "persisted");
        var preferences = new MemoryPreferences(null, "");
        preferences.put("pins", root + java.io.File.separator + "<invalid>\n" + folder);
        preferences.put("recentFiles", "bad-timestamp\t" + sample + "\n1234\t" + sample);
        openWindow(preferences, new TestClipboard());
        await(() -> listed(sample) && !currentBrowse().needsReload, "valid home entries still load", 7);
        assertTrue(fx(() -> ((Set<?>) field(app, "extraPins")).contains(folder)));
        assertTrue(fx(() -> ((Map<?, ?>) field(app, "recentFiles")).containsKey(sample)));
        assertNull(fx(this::errorDialog));
        go(folder);
        assertTrue(fx(() -> listed(sample)));
    }

    @Test
    void reopeningRestoresLayoutAndPreferencesWithAFreshFileSession() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("left"));
        Path right = Files.createDirectory(root.resolve("right"));
        Path sample = Files.writeString(left.resolve("session.txt"), "keep on disk");
        var preferences = new MemoryPreferences(null, "");
        var clipboard = new TestClipboard();
        openWindow(preferences, clipboard);
        goWatched(left);
        addWorkspaceLayout(right);
        toggleAutoRefresh(false);
        select(sample);
        click("cut");
        await(() -> cutSources().equals(List.of(sample)) && idle(), "session contains an unpublished cut", 8);
        var layout = fx(() -> {
            var display = javafx.stage.Screen.getPrimary().getVisualBounds();
            stage.setX(display.getMinX() + 32);
            stage.setY(display.getMinY() + 26);
            stage.setWidth(1080);
            stage.setHeight(645);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            browserSplit().setDividerPositions(0.57);
            scene.getRoot().layout();
            return new double[]{stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(),
                    browserSplit().getDividerPositions()[0]};
        });
        closeWindow();
        openWindow(preferences, clipboard);
        fx(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            assertEquals(2, browserSplit().getItems().size());
            assertEquals(layout[0], stage.getX(), 2);
            assertEquals(layout[1], stage.getY(), 2);
            assertEquals(layout[2], stage.getWidth(), 2);
            assertEquals(layout[3], stage.getHeight(), 2);
            assertEquals(layout[4], browserSplit().getDividerPositions()[0], 0.025);
            assertEquals(1, tabs().getTabs().size());
            assertEquals(BrowseContext.Page.HOME, paneContext(false).location.page());
            assertEquals(BrowseContext.Page.HOME, paneContext(true).location.page());
            assertFalse((boolean) field(app, "automaticRefresh"));
            assertTrue(cutSources().isEmpty());
            assertTrue(((List<?>) field(app, "undo")).isEmpty());
            return null;
        });
        assertEquals("keep on disk", Files.readString(sample));
    }

    private void addWorkspaceLayout(Path right) throws Exception {
        click("dual-pane");
        goPane(true, right);
        focusPane(false);
    }

    @Test
    void copiesASearchResultIntoAnotherTabWithoutChangingTheSearch() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path source = Files.createDirectory(root.resolve("来源"));
        Path child = Files.createDirectory(source.resolve("项目资料"));
        Path target = Files.createDirectory(root.resolve("归档"));
        Path match = Files.writeString(child.resolve("阶段报告.txt"), "reviewed");
        Files.writeString(child.resolve("draft.txt"), "unselected");
        openWindow(false, new TestClipboard());
        Tab destination = openAt(target);
        Tab searchTab = openAt(source);
        fx(() -> {
            var search = (TextField) scene.lookup("#search-field");
            search.setText("阶段报告");
            search.fireEvent(new ActionEvent());
            return null;
        });
        await(() -> idle() && listed(match) && browser.items().size() == 1, "directory search results", 10);
        select(match);
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(match)), "search result copied", 8);
        activate(destination);
        await(() -> browseAt(target), "destination tab", 10);
        click("paste");
        Path published = target.resolve(match.getFileName());
        await(() -> idle() && listed(published), "paste into the destination tab", 12);
        assertEquals(-1, Files.mismatch(match, published));
        assertFalse(Files.exists(target.resolve("draft.txt")));
        activate(searchTab);
        await(() -> idle() && "阶段报告".equals(currentBrowse().activeQuery) && listed(match),
                "original search and file remain available", 10);
    }

    private javafx.scene.control.SplitPane browserSplit() {
        return (javafx.scene.control.SplitPane) scene.lookup("#browser-split");
    }

    @Test
    void addressHistoryPopupSubmitsTypedPathOnEnterInBothPanes() throws Exception {
        Path left = Files.createDirectory(root.resolve("left"));
        Path right = Files.createDirectory(root.resolve("right"));
        Path recent = Files.createDirectory(root.resolve("用回车进入的目录"));
        openWindow(false);
        go(recent);
        go(left);
        click("dual-pane");
        goPane(true, right);
        enterFromHistoryPopup(false, recent);
        await(() -> paneAt(false, recent) && paneAt(true, right), "left typed address submitted from popup", 8);
        enterFromHistoryPopup(true, left);
        await(() -> paneAt(false, recent) && paneAt(true, left), "right typed address submitted from popup", 8);
    }

    private void enterFromHistoryPopup(boolean right, Path path) throws Exception {
        fx(() -> {
            var address = (AddressBar) field(pane(right), "address");
            address.beginEdit();
            var editor = (TextField) address.lookup("#address");
            editor.setText(path.toString());
            var popup = (javafx.stage.Popup) field(address, "popup");
            assertTrue(popup.isShowing(), "typed address shows a matching history suggestion");
            var suggestions = (javafx.scene.control.ListView<?>) field(address, "choices");
            assertTrue(suggestions.getSelectionModel().isEmpty());
            suggestions.requestFocus();
            SceneHelper.processKeyEvent(popup.getScene(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "",
                    KeyCode.ENTER, false, false, false, false));
            return null;
        });
    }

    @Test
    void sceneDragDetectionPublishesAFileOfferFromAPrimaryPress() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path sample = Files.writeString(root.resolve("drag.txt"), "drag source");
        openWindow(false);
        go(root);
        select(sample);
        await(this::hasSelectionProof, "visible drag source verified", 8);
        fx(() -> {
            Node picked = dragEntry(false, sample);
            Node row = picked;
            while (row != null && !(row instanceof TableRow<?>)) { row = row.getParent(); }
            assertNotNull(row);
            Node cell = row.lookupAll(".table-cell").stream()
                    .filter(node -> node instanceof javafx.scene.control.TableCell<?, ?> column
                            && column.isVisible() && sample.getFileName().toString().equals(column.getText()))
                    .findFirst().orElseThrow();
            var bounds = cell.localToScene(cell.getBoundsInLocal());
            double x = bounds.getMinX() + bounds.getWidth() / 2;
            double y = bounds.getMinY() + bounds.getHeight() / 2;
            var dispatcher = scene.getEventDispatcher();
            var offered = new AtomicReference<Dragboard>();
            var detected = new AtomicBoolean();
            scene.setEventDispatcher((event, tail) -> {
                try {
                    return dispatcher.dispatchEvent(event, tail);
                } finally {
                    if (event.getEventType() == MouseEvent.DRAG_DETECTED) {
                        detected.set(true);
                        try {
                            var gesture = field(scene, "dndGesture");
                            Field dragboardField = gesture.getClass().getDeclaredField("dragboard");
                            dragboardField.setAccessible(true);
                            offered.set((Dragboard) dragboardField.get(gesture));
                            // Stop before JavaFX enters the desktop OLE loop.
                            dragboardField.set(gesture, null);
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    }
                }
            });
            try {
                SceneHelper.processMouseEvent(scene, sceneMouse(MouseEvent.MOUSE_PRESSED, x, y, MouseButton.PRIMARY, true));
                assertNotNull(field(app, "dragPress"), "primary press reached the file row at " + bounds);
                SceneHelper.processMouseEvent(scene, sceneMouse(MouseEvent.MOUSE_DRAGGED, x + 24, y, MouseButton.PRIMARY, true));
                assertTrue(detected.get(), "JavaFX detected the drag threshold");
                var board = offered.get();
                assertNotNull(board, "the recognized gesture started JavaFX drag and drop");
                assertEquals(List.of(sample.toFile()), board.getFiles());
                assertTrue(board.hasContent(FileDrag.SESSION));
                finishSimulatedDrag(new SimulatedDrag(board, browser), null);
            } finally {
                scene.setEventDispatcher(dispatcher);
                SceneHelper.processMouseEvent(scene, sceneMouse(MouseEvent.MOUSE_RELEASED, x + 24, y, MouseButton.PRIMARY, false));
                if (offered.get() != null) { call(DragboardHelper.getPeer(offered.get()), "close"); }
            }
            return null;
        });
    }

    private static MouseEvent sceneMouse(javafx.event.EventType<MouseEvent> type, double x, double y,
                                         MouseButton button, boolean primaryDown) {
        return new MouseEvent(type, x, y, x, y, button, 1, false, false, false, false,
                primaryDown, false, false, false, false, true, null);
    }

    @Test
    void dragUsesFrozenMultiSelectionCopiesBothWaysAndMovesWithShift() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("资料"));
        Path right = Files.createDirectory(root.resolve("收件"));
        Path file = Files.writeString(left.resolve("中文 文件.txt"), "source file");
        Path folder = Files.createDirectory(left.resolve("目录"));
        Files.writeString(folder.resolve("nested.txt"), "inside directory");
        var clipboard = new TestClipboard();
        var clipboardState = clipboard.text("keep my clipboard");
        openWindow(false, clipboard);
        go(left);
        click("dual-pane");
        goPane(true, right);
        fx(() -> { browser.selectAll(); return null; });
        await(() -> paneContext(false).selectionProof.size() == 2, "mixed selection verified", 8);
        var multi = beginFileDrag(false, file);
        assertEquals(2, fx(() -> multi.board().getFiles().size()));
        fx(() -> { browser.clearSelection(); return null; });
        Node rightList = fx(() -> paneBrowser(true));
        assertTrue(dragOver(multi, rightList));
        assertEquals("复制 2 项 到「收件」", fx(this::dragStatusText));
        captureDrag("copy-across.png");
        assertTrue(dropFiles(multi, rightList));
        dragDone(multi, TransferMode.MOVE);
        Path copied = right.resolve(file.getFileName());
        await(() -> idle() && paneListed(true, copied), "frozen multi copy", 12);
        assertEquals("source file", Files.readString(file));
        assertEquals(-1, Files.mismatch(file, copied));
        assertEquals("inside directory", Files.readString(right.resolve("目录/nested.txt")));
        assertEquals(clipboardState, clipboard.probe());

        Path rightCopy = Files.writeString(right.resolve("回传.txt"), "copy back");
        goPane(true, right);
        var copiedBack = selectForDrag(true, rightCopy);
        assertTrue(dragOver(copiedBack, browser));
        assertTrue(dropFiles(copiedBack, browser));
        dragDone(copiedBack, TransferMode.COPY);
        await(() -> idle() && paneListed(false, left.resolve(rightCopy.getFileName())), "right-to-left copy", 12);
        assertEquals(-1, Files.mismatch(rightCopy, left.resolve(rightCopy.getFileName())));

        Path move = Files.writeString(right.resolve("同卷移动.txt"), "move me");
        goPane(true, right);
        var shifted = selectForDrag(true, move);
        dragIntent.set(FileDrag.Intent.MOVE);
        assertTrue(dragOver(shifted, browser));
        assertTrue(fx(this::dragStatusText).startsWith("移动 1 项"));
        captureDrag("move-across.png");
        assertTrue(dropFiles(shifted, browser));
        dragDone(shifted, TransferMode.COPY);
        Path moved = left.resolve(move.getFileName());
        await(() -> idle() && paneListed(false, moved) && !paneListed(true, move), "same-volume move", 12);
        assertFalse(Files.exists(move));
        Files.writeString(moved, "edited after move");
        focusPane(false);
        menu("moreItems", "undo");
        await(() -> idle() && paneListed(true, move) && !paneListed(false, moved), "drag move undo", 12);
        assertEquals("edited after move", Files.readString(move));
        assertEquals(clipboardState, clipboard.probe());
        assertEquals("", fx(this::dragStatusText));
    }

    @Test
    void dragFindsFolderIconsAndInactiveTabsAndRejectsOrdinaryFileTargets() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("source"));
        Path right = Files.createDirectory(root.resolve("target"));
        Path dormant = Files.createDirectory(root.resolve("tab-target"));
        Path file = Files.writeString(left.resolve("payload.txt"), "payload");
        Path folder = Files.createDirectory(right.resolve("接收目录"));
        Path ordinary = Files.writeString(right.resolve("file.txt"), "unchanged");
        openWindow(false);
        go(left);
        Tab initial = fx(() -> tabs().getSelectionModel().getSelectedItem());
        Tab target = openAt(dormant);
        activate(initial);
        await(() -> browseAt(left), "source tab restored", 10);
        click("dual-pane");
        goPane(true, right);
        focusPane(true);
        menu("viewItems", "mode-large");
        var drag = selectForDrag(false, file);
        Node wrong = fx(() -> dragEntry(true, ordinary));
        assertFalse(dragOver(drag, wrong));
        assertFalse(dropFiles(drag, wrong));
        assertEquals("unchanged", Files.readString(ordinary));
        assertFalse(Files.exists(right.resolve(file.getFileName())));
        var folderTarget = fx(() -> dragEntry(true, folder));
        await(() -> fireDrag(drag, folderTarget, DragEvent.DRAG_OVER).isAccepted(), "folder drag identity", 8);
        captureDrag("folder-target.png");
        assertTrue(dropFiles(drag, folderTarget));
        dragDone(drag, TransferMode.COPY);
        await(() -> idle() && Files.exists(folder.resolve(file.getFileName())), "drop directly into folder", 12);
        assertEquals(-1, Files.mismatch(file, folder.resolve(file.getFileName())));

        var toTab = selectForDrag(false, file);
        Node header = fx(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            return scene.lookup("#" + target.getId());
        });
        assertNotNull(header);
        dragIntent.set(FileDrag.Intent.MOVE);
        assertTrue(dragOver(toTab, header));
        assertTrue(dropFiles(toTab, header));
        dragDone(toTab, TransferMode.COPY);
        await(() -> idle() && Files.exists(dormant.resolve(file.getFileName())), "drop into inactive tab", 12);
        assertSame(initial, fx(() -> tabs().getSelectionModel().getSelectedItem()));
        assertEquals("payload", Files.readString(dormant.resolve(file.getFileName())));
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(right.resolve(file.getFileName())));
    }

    @Test
    void dragRefusesReplacedObjectsAndTargetsAndDoesNotRetargetAfterNavigation() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("source"));
        Path right = Files.createDirectory(root.resolve("target"));
        Path other = Files.createDirectory(root.resolve("elsewhere"));
        Path file = Files.writeString(left.resolve("replace.txt"), "same payload");
        Path contained = Files.createDirectory(right.resolve("subfolder"));
        openWindow(false);
        go(left);
        click("dual-pane");
        goPane(true, right);
        var moving = selectForDrag(false, file);
        var attributes = LocalFiles.describe(file);
        Path saved = Files.move(file, root.resolve("saved.txt"));
        Files.writeString(file, "same payload");
        Files.getFileAttributeView(file, BasicFileAttributeView.class)
                .setTimes(attributes.modified(), null, attributes.created());
        var rightList = fx(() -> paneBrowser(true));
        dragIntent.set(FileDrag.Intent.MOVE);
        assertTrue(dragOver(moving, rightList));
        assertTrue(dropFiles(moving, rightList));
        dragDone(moving, TransferMode.COPY);
        acknowledgeFileError();
        assertEquals(-1, Files.mismatch(file, saved));
        assertFalse(Files.exists(right.resolve(file.getFileName())));

        dragIntent.set(FileDrag.Intent.COPY);
        goPane(false, left);
        var folderRequest = selectForDrag(false, file);
        Node row = fx(() -> dragEntry(true, contained));
        await(() -> fireDrag(folderRequest, row, DragEvent.DRAG_OVER).isAccepted(), "verified folder before replacement", 8);
        Path oldFolder = Files.move(contained, root.resolve("old-subfolder"));
        Files.createDirectory(contained);
        assertTrue(dropFiles(folderRequest, row));
        dragDone(folderRequest, TransferMode.COPY);
        acknowledgeFileError();
        assertFalse(Files.exists(contained.resolve(file.getFileName())));
        assertFalse(Files.exists(oldFolder.resolve(file.getFileName())));

        var navigation = selectForDrag(false, file);
        assertTrue(dragOver(navigation, rightList));
        goPane(true, other);
        assertFalse(dropFiles(navigation, rightList));
        dragDone(navigation, null);
        assertFalse(Files.exists(right.resolve(file.getFileName())));
        assertFalse(Files.exists(other.resolve(file.getFileName())));
        assertEquals("same payload", Files.readString(file));
    }

    @Test
    void externalDragAllowsCopyOnlyAndIgnoresStaleSessionsAndText() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path source = Files.createDirectory(root.resolve("foreign"));
        Path destination = Files.createDirectory(root.resolve("target"));
        Path file = Files.writeString(source.resolve("外部 文件.txt"), "copied from foreign");
        Path sub = Files.createDirectory(source.resolve("外部子目录"));
        Files.writeString(sub.resolve("inside.txt"), "foreign directory");
        openWindow(false);
        go(destination);
        Map<DataFormat, Object> files = Map.of(DataFormat.FILES, List.of(file.toFile(), sub.toFile()));
        var external = fx(() -> dragboard(null, files, Set.of(TransferMode.COPY, TransferMode.MOVE)));
        dragIntent.set(FileDrag.Intent.MOVE);
        assertFalse(dragOver(external, browser));
        assertTrue(fx(this::dragStatusText).contains("仅支持复制"));
        assertFalse(dropFiles(external, browser));
        dragIntent.set(FileDrag.Intent.COPY);
        var moveOnly = fx(() -> dragboard(null, files, Set.of(TransferMode.MOVE)));
        assertFalse(dragOver(moveOnly, browser));
        assertFalse(dropFiles(moveOnly, browser));
        var forged = fx(() -> dragboard(null, Map.of(FileDrag.SESSION, "invalid token", DataFormat.FILES, List.of(file.toFile())),
                Set.of(TransferMode.COPY)));
        assertFalse(dragOver(forged, browser));
        assertFalse(dropFiles(forged, browser));
        var text = fx(() -> dragboard(null, Map.of(DataFormat.PLAIN_TEXT, file.toString()), Set.of(TransferMode.COPY)));
        assertFalse(dragOver(text, browser));
        assertFalse(dropFiles(text, browser));
        assertFalse(Files.exists(destination.resolve(file.getFileName())));
        assertTrue(dragOver(external, browser));
        assertTrue(dropFiles(external, browser));
        assertFalse(dropFiles(external, browser), "the same delivery has no second hover acceptance");
        await(() -> idle() && listed(destination.resolve(file.getFileName())), "external file copy", 12);
        assertEquals(-1, Files.mismatch(file, destination.resolve(file.getFileName())));
        assertEquals("foreign directory", Files.readString(destination.resolve("外部子目录/inside.txt")));
        assertTrue(Files.isDirectory(sub));
        assertTrue(fx(() -> field(app, "dragSource") == null && field(app, "dropBoard") == null));
    }

    @Test
    void acceptedDragKeepsTheClosedDestinationAndSupportsConflictsAndCancellation() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("source"));
        Path right = Files.createDirectory(root.resolve("destination"));
        Path copied = Files.writeString(left.resolve("a.txt"), "already applied");
        Path skipped = Files.writeString(left.resolve("b.txt"), "source b");
        Path kept = Files.writeString(left.resolve("c.txt"), "source c");
        Path targetB = Files.writeString(right.resolve("b.txt"), "original b");
        Path targetC = Files.writeString(right.resolve("c.txt"), "original c");
        Path file = left.resolve("large.bin");
        var clipboard = new TestClipboard();
        openWindow(false, clipboard);
        go(left);
        select(copied);
        click("cut");
        await(() -> idle() && cutSources().equals(List.of(copied)), "source cut staged", 8);
        var externalText = clipboard.text("from a different clipboard owner");
        await(() -> cutSources().isEmpty() && button("paste").isDisabled(), "clipboard takeover clears the cut", 8);
        click("dual-pane");
        goPane(true, right);
        var rightList = fx(() -> paneBrowser(true));
        fx(() -> { browser.selectAll(); return null; });
        await(() -> paneContext(false).selectionProof.size() == 3, "batch sources verified", 8);
        var batch = beginFileDrag(false, copied);
        assertTrue(dragOver(batch, rightList));
        assertTrue(dropFiles(batch, rightList));
        dragDone(batch, TransferMode.COPY);
        await(() -> conflictScene() != null && Files.exists(right.resolve(copied.getFileName())), "drag copy conflict", 12);
        click("dual-pane");
        chooseConflict("conflict-skip");
        await(() -> conflictScene() != null && progressLabel("operation-file").equals("c.txt"),
                "continue to next conflict", 10);
        chooseConflict("conflict-keep");
        Path published = right.resolve("c - 副本.txt");
        await(() -> idle() && Files.exists(published), "copy continues after destination closes", 12);
        assertEquals("original b", Files.readString(targetB));
        assertEquals("original c", Files.readString(targetC));
        assertEquals(-1, Files.mismatch(copied, right.resolve(copied.getFileName())));
        assertEquals(-1, Files.mismatch(kept, published));
        assertEquals("source b", Files.readString(skipped));
        assertEquals(externalText, clipboard.probe());
        assertEquals("from a different clipboard owner", clipboard.textValue());
        assertTrue(fx(() -> progressLabel("operation-details").contains("已跳过 1 项")));

        click("dual-pane");
        await(() -> paneAt(true, right), "reopen copy destination", 10);
        Files.write(file, new byte[12 * 1024 * 1024]);
        goPane(false, left);
        var request = selectForDrag(false, file);
        pauseNativeCopy.set(true);
        try {
            assertTrue(dragOver(request, rightList));
            assertTrue(dropFiles(request, rightList));
            dragDone(request, TransferMode.COPY);
            assertTrue(copyHasBytes.await(10, TimeUnit.SECONDS));
            await(() -> progressScene() != null, "cancellable drag copy progress", 10);
            fx(() -> { ((Button) progressScene().lookup("#operation-action")).fire(); return null; });
        } finally {
            releaseCopy.countDown();
        }
        await(() -> idle() && "已取消".equals(progressLabel("operation-stage")), "cancelled drag copy", 12);
        assertFalse(Files.exists(right.resolve(file.getFileName())));
        assertTrue(Files.exists(file));
        try (var entries = Files.list(right)) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".file-manager-")));
        }
    }

    @Test
    void sourceRefreshKeepsTheDragAndLostMoveReceiptIsOnlyAvailableForInspection() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("left"));
        Path right = Files.createDirectory(root.resolve("right"));
        Path file = Files.writeString(right.resolve("move.txt"), "move sample");
        openWindow(true);
        goWatched(left);
        click("dual-pane");
        goPane(true, right);
        await(() -> paneWatching(true), "right directory watched", 10);
        var source = selectForDrag(true, file);
        var readVersion = fx(() -> paneContext(true).listingVersion);
        Path external = Files.writeString(right.resolve("refresh.txt"), "visible from a new listing");
        await(() -> paneListed(true, external) && paneContext(true).listingVersion > readVersion,
                "automatic refresh during drag", 10);
        dragIntent.set(FileDrag.Intent.MOVE);
        loseMoveReceipt.set(true);
        assertTrue(dragOver(source, browser));
        assertTrue(dropFiles(source, browser));
        dragDone(source, TransferMode.MOVE);
        acknowledgeFileError();
        Path moved = left.resolve(file.getFileName());
        await(() -> idle() && paneListed(false, moved) && !paneListed(true, file), "uncertain drag moves still refresh both", 12);
        assertEquals("move sample", Files.readString(moved));
        assertFalse(Files.exists(file));
        assertEquals(1, injectedWrites.get());
        assertTrue(fx(() -> ((List<?>) field(app, "unknownOperations")).size() == 1
                && ((List<?>) field(app, "undo")).isEmpty()));
        assertFalse(dragOver(source, browser), "consumed session cannot be replayed");
    }

    @Test
    void unverifiedDragCanGatherCopyProofButCannotAdvertiseFilesOrMove() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("left"));
        Path right = Files.createDirectory(root.resolve("right"));
        Path sample = Files.writeString(left.resolve("unselected.txt"), "sample");
        openWindow(false);
        go(left);
        click("dual-pane");
        goPane(true, right);
        assertTrue(fx(() -> paneContext(false).selectionProof.isEmpty()));
        var quick = beginFileDrag(false, sample);
        assertFalse(fx(() -> quick.board().hasFiles()));
        Node to = fx(() -> paneBrowser(true));
        dragIntent.set(FileDrag.Intent.MOVE);
        assertFalse(dragOver(quick, to));
        assertTrue(fx(this::dragStatusText).contains("核验来源"));
        dragIntent.set(FileDrag.Intent.COPY);
        await(() -> fireDrag(quick, to, DragEvent.DRAG_OVER).isAccepted(), "source verified during drag", 10);
        assertTrue(dropFiles(quick, to));
        dragDone(quick, TransferMode.COPY);
        await(() -> idle() && Files.exists(right.resolve(sample.getFileName())), "quick drag copy", 10);
        assertEquals(-1, Files.mismatch(sample, right.resolve(sample.getFileName())));
    }

    @Test
    @EnabledIfSystemProperty(named = "javafx.dragBenchmark", matches = "true")
    void measureDragSessionAndHoverCosts() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path source = Files.createDirectory(root.resolve("source"));
        Path destination = Files.createDirectory(root.resolve("target"));
        var paths = new ArrayList<Path>();
        for (int index = 0; index < 1_200; index++) {
            paths.add(Files.createFile(source.resolve("sample-%04d.txt".formatted(index))));
        }
        openWindow(false);
        go(source);
        click("dual-pane");
        goPane(true, destination);
        fx(() -> { browser.selectAll(); return null; });
        var createSamples = fx(() -> {
            Node item = dragEntry(false, paths.getFirst());
            var measured = new ArrayList<Long>();
            for (int index = 0; index < 13; index++) {
                long start = System.nanoTime();
                SimulatedDrag drag = startSimulatedDrag(item);
                long elapsed = System.nanoTime() - start;
                assertEquals(1_200, ((FileDrag.Source) field(app, "dragSource")).items().size());
                finishSimulatedDrag(drag, null);
                if (index >= 3) { measured.add(elapsed); }
            }
            assertNull(field(app, "dragSourceRead"));
            return measured;
        });
        Node view = fx(() -> paneBrowser(true));
        var hover = selectForDrag(false, paths.getFirst());
        int workersBefore = workerThreads();
        var hoverSamples = fx(() -> {
            var measured = new ArrayList<Long>();
            for (int index = 0; index < 550; index++) {
                long start = System.nanoTime();
                var event = fireDrag(hover, view, DragEvent.DRAG_OVER);
                long elapsed = System.nanoTime() - start;
                assertTrue(event.isAccepted());
                if (index >= 50) { measured.add(elapsed); }
            }
            finishSimulatedDrag(hover, null);
            return measured;
        });
        var dropSamples = new ArrayList<Long>();
        for (Path path : paths.subList(0, 5)) {
            var offered = selectForDrag(false, path);
            assertTrue(dragOver(offered, view));
            var created = new CompletableFuture<Long>();
            long start = fx(() -> {
                timedDragTask.set(created);
                long now = System.nanoTime();
                assertTrue(fireDrag(offered, view, DragEvent.DRAG_DROPPED).isDropCompleted());
                return now;
            });
            dropSamples.add(created.get(10, TimeUnit.SECONDS) - start);
            dragDone(offered, TransferMode.COPY);
            await(() -> idle() && Files.exists(destination.resolve(path.getFileName())), "measured drag copy", 12);
        }
        await(() -> field(app, "dragSource") == null && field(app, "dropSite") == null
                && field(app, "dragSourceRead") == null && field(app, "dropFolderRead") == null, "no retained drag session", 8);
        var sortedHover = hoverSamples.stream().sorted().toList();
        System.out.printf(java.util.Locale.ROOT, "PHASE32_BENCH request=%s hoverMedianMs=%.4f hoverP95Ms=%.4f drop=%s workers=%d,%d%n",
                milliseconds(createSamples), sortedHover.get(249) / 1e6, sortedHover.get(474) / 1e6,
                milliseconds(dropSamples), workersBefore, workerThreads());
    }

    private SimulatedDrag selectForDrag(boolean right, Path file) throws Exception {
        focusPane(right);
        fx(() -> { assertTrue(paneBrowser(right).reveal(file)); return null; });
        await(() -> paneContext(right).selectionProof.keySet().stream().anyMatch(item -> item.path().equals(file)),
                "file selected and verified for drag", 8);
        return beginFileDrag(right, file);
    }

    private SimulatedDrag beginFileDrag(boolean right, Path path) throws Exception {
        return fx(() -> startSimulatedDrag(dragEntry(right, path)));
    }

    private SimulatedDrag startSimulatedDrag(Node item) throws Exception {
        Event.fireEvent(item, new MouseEvent(MouseEvent.MOUSE_PRESSED, 2, 2, 0, 0, MouseButton.PRIMARY,
                1, false, false, false, false, true, false, false, false, false, true,
                new PickResult(item, 2, 2)));
        var press = field(app, "dragPress");
        assertNotNull(press, "pressed a rendered file");
        var create = FileManagerApp.class.getDeclaredMethod("createFileDrag", press.getClass());
        create.setAccessible(true);
        var source = (FileDrag.Source) create.invoke(app, press);
        assertNotNull(source);
        return dragboard((Node) source.gesture(), FileManagerApp.dragContent(source), Set.of(TransferMode.COPY));
    }

    private Node dragEntry(boolean right, Path path) throws Exception {
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        var view = paneBrowser(right);
        for (Node entry : view.lookupAll(".selectable-entry")) {
            if (entry.getUserData() instanceof BrowserItem item && path.equals(item.path())) { return entry; }
        }
        for (Node entry : view.lookupAll(".table-row-cell")) {
            if (entry instanceof TableRow<?> row && row.getItem() != null) {
                var item = (BrowserItem) call(row.getItem(), "item");
                if (item != null && path.equals(item.path())) {
                    var cell = row.lookup(".table-cell");
                    return cell == null ? row : cell;
                }
            }
        }
        return fail("No rendered item at " + path);
    }

    private boolean dragOver(SimulatedDrag drag, Node target) throws Exception {
        return fx(() -> fireDrag(drag, target, DragEvent.DRAG_OVER).isAccepted());
    }

    private boolean dropFiles(SimulatedDrag drag, Node target) throws Exception {
        return fx(() -> fireDrag(drag, target, DragEvent.DRAG_DROPPED).isDropCompleted());
    }

    private void dragDone(SimulatedDrag drag, TransferMode result) throws Exception {
        fx(() -> {
            finishSimulatedDrag(drag, result);
            return null;
        });
    }

    private static void finishSimulatedDrag(SimulatedDrag drag, TransferMode result) {
        Event.fireEvent(drag.source(), new DragEvent(DragEvent.DRAG_DONE, drag.board(), 0, 0,
                0, 0, result, drag.source(), null, new PickResult(drag.source(), 0, 0)));
    }

    private static DragEvent fireDrag(SimulatedDrag drag, Node target, javafx.event.EventType<DragEvent> type) {
        var event = new DragEvent(type, drag.board(), 2, 2, 0, 0, TransferMode.MOVE,
                drag.source(), null, new PickResult(target, 2, 2));
        Event.fireEvent(target, event);
        return event;
    }

    private String dragStatusText() { return ((Label) scene.lookup("#drag-status")).getText(); }

    private void captureDrag(String name) throws Exception {
        if (!Boolean.getBoolean("javafx.captureDrag")) { return; }
        if (captures == null) { captures = Files.createTempDirectory("phase32-fx-"); }
        var snapshot = fx(() -> scene.snapshot(null));
        var png = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        copyPixels(snapshot, png, 0);
        Path file = captures.resolve(name);
        assertTrue(ImageIO.write(png, "png", file.toFile()));
        System.out.println("PHASE32_SCREENSHOT " + file);
    }

    private record SimulatedDrag(Dragboard board, Node source) { }

    private static SimulatedDrag dragboard(Node source, Map<DataFormat, Object> content, Set<TransferMode> modes) {
        return new SimulatedDrag(DragboardHelper.createDragboard(new TestDragboard(content, modes, source != null)), source);
    }

    @SuppressWarnings({"removal", "unchecked"})
    private record TestDragboard(Map<DataFormat, Object> content, Set<TransferMode> modes,
                                 boolean local) implements TKClipboard {
        @Override public void setSecurityContext(java.security.AccessControlContext context) { }
        @Override public Set<DataFormat> getContentTypes() { return content.keySet(); }
        @Override public boolean putContent(Pair<DataFormat, Object>... pairs) { throw new UnsupportedOperationException(); }
        @Override public Object getContent(DataFormat type) {
            assertTrue(local || type != FileDrag.SESSION, "foreign custom payload must not be read");
            return content.get(type);
        }
        @Override public boolean hasContent(DataFormat type) { return content.containsKey(type); }
        @Override public Set<TransferMode> getTransferModes() { return modes; }
        @Override public void setDragView(Image image) { throw new UnsupportedOperationException(); }
        @Override public void setDragViewOffsetX(double x) { throw new UnsupportedOperationException(); }
        @Override public void setDragViewOffsetY(double y) { throw new UnsupportedOperationException(); }
        @Override public Image getDragView() { return null; }
        @Override public double getDragViewOffsetX() { return 0; }
        @Override public double getDragViewOffsetY() { return 0; }
    }

    @Test
    void dualPaneKeepsIndependentNavigationPresentationAndCommandFocus() throws Exception {
        Path left = Files.createDirectory(root.resolve("项目资料"));
        Path child = Files.createDirectory(left.resolve("子目录"));
        Path right = Files.createDirectory(root.resolve("交付目录"));
        Path text = Files.writeString(left.resolve("设计说明.txt"), "left");
        Path log = Files.writeString(left.resolve("notes.log"), "filter this file");
        Path target = Files.writeString(right.resolve("交付清单.txt"), "right");
        openWindow(false);
        go(left);
        click("dual-pane");
        await(() -> paneAt(true, left), "right pane starts at the left directory", 12);
        select(text);
        assertTrue(fx(() -> button("copy-across").isDisabled()));
        goPane(true, right);
        focusPane(true);
        menu("sortItems", "sort-size");
        assertEquals(BrowserView.Field.SIZE, fx(() -> paneContext(true).view().sort()));
        assertEquals(BrowserView.Field.NAME, fx(() -> paneContext(false).view().sort()));
        clickOnPane(true, "back");
        await(() -> paneAt(true, left) && paneAt(false, left), "right history is independent", 10);
        clickOnPane(true, "forward");
        await(() -> paneAt(true, right), "right forward history", 10);
        goPane(false, child);
        clickOnPane(false, "up");
        await(() -> paneAt(false, left) && paneAt(true, right), "left up preserves right location", 10);
        fx(() -> {
            var filters = (ColumnFilters) field(browser, "filters");
            filters.select(BrowserView.Field.TYPE, new ColumnFilters.Bucket(0, "TXT 文件"), true);
            call(browser, "applyFilters");
            assertFalse(listed(log));
            assertFalse(paneBrowser(true).filtersActive());
            var search = (TextField) paneControl(true, "search-field");
            search.requestFocus();
            search.setText("交付");
            search.fireEvent(new ActionEvent());
            return null;
        });
        await(() -> activePane() == pane(true) && paneAt(true, right) && paneContext(true).activeQuery.equals("交付")
                && paneListed(true, target), "right search takes the window command focus", 12);
        assertTrue(fx(() -> browser.filtersActive() && paneContext(false).activeQuery.isEmpty()));
        focusPane(false);
        select(text);
        assertTrue(fx(() -> button("copy-across").isDisabled()), "search cannot serve as a direct destination");
        capturePanes("dual-pane.png");
        fx(() -> {
            var split = (javafx.scene.control.SplitPane) scene.lookup("#browser-split");
            split.setDividerPositions(0.58);
            button("details-pane").fire();
            stage.setWidth(stage.getMinWidth());
            return null;
        });
        await(() -> {
            var split = (javafx.scene.control.SplitPane) scene.lookup("#browser-split");
            return split.getItems().size() == 2 && split.getItems().get(0).getBoundsInParent().getWidth() >= 258
                    && split.getItems().get(1).getBoundsInParent().getWidth() >= 258
                    && paneControl(true, "search-field").getBoundsInParent().getWidth() >= 54
                    && paneControl(true, "address-bar").getBoundsInParent().getMaxX()
                        <= paneControl(true, "address-bar").getParent().getLayoutBounds().getWidth() + 1;
        }, "panes remain usable at minimum size", 8);
        click("new-tab");
        await(() -> paneAt(false, left) && tabs().getTabs().size() == 2 && paneAt(true, right), "left tabs still work", 10);
        click("dual-pane");
        assertTrue(fx(() -> scene.lookup("#right-pane") == null && paneAt(false, left)));
        click("dual-pane");
        await(() -> paneAt(true, right) && paneContext(true).activeQuery.equals("交付"), "right state is restored after reopening", 10);
    }

    @Test
    void bothReadRequestsOwnTheirPaneAndClosingDiscardsTheLateResult() throws Exception {
        Path first = Files.createDirectory(root.resolve("first"));
        Path second = Files.createDirectory(root.resolve("second"));
        Path leftNext = Files.createDirectory(root.resolve("left-next"));
        Path rightNext = Files.createDirectory(root.resolve("right-next"));
        Path leftFile = Files.writeString(leftNext.resolve("left.txt"), "first request");
        Path rightFile = Files.writeString(rightNext.resolve("right.txt"), "second request");
        openWindow(false);
        go(first);
        click("dual-pane");
        goPane(true, second);
        var leftGate = new Gate();
        var rightGate = new Gate();
        heldRead.set(new ReadGate(leftNext, false, leftGate));
        try {
            enterPane(false, leftNext);
            assertTrue(leftGate.entered.await(8, TimeUnit.SECONDS));
            heldRead.set(new ReadGate(rightNext, false, rightGate));
            enterPane(true, rightNext);
            assertTrue(rightGate.entered.await(8, TimeUnit.SECONDS));
            var pendingRight = fx(() -> paneContext(true).browseRequest);
            focusPane(true);
            leftGate.release.countDown();
            await(() -> paneAt(false, leftNext) && paneListed(false, leftFile), "inactive left pane commits its own read", 10);
            assertSame(pendingRight, fx(() -> paneContext(true).browseRequest));
            assertSame(fx(() -> pane(true)), fx(this::activePane));
            click("dual-pane");
            assertNull(fx(() -> paneContext(true).browseRequest));
            assertTrue(fx(() -> paneContext(true).listing.items().isEmpty()));
        } finally {
            leftGate.release.countDown();
            rightGate.release.countDown();
        }
        assertTrue(rightGate.returned.await(8, TimeUnit.SECONDS));
        click("dual-pane");
        await(() -> paneAt(false, leftNext) && paneAt(true, second) && !paneListed(true, rightFile),
                "reopening reads the last committed right directory", 12);
    }

    @Test
    void twoWatchersRefreshDifferentAndSharedDirectoriesAndKeepAnUnrelatedEdit() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("left"));
        Path right = Files.createDirectory(root.resolve("right"));
        Path file = Files.writeString(left.resolve("edit.txt"), "original");
        openWindow(true, new TestClipboard());
        goWatched(left);
        click("dual-pane");
        goPane(true, right);
        await(() -> paneWatching(false) && paneWatching(true), "both local watchers", 12);
        focusPane(false);
        select(file);
        click("rename");
        await(() -> scene.lookup("#left-pane").lookup("#new-name-editor") != null, "inline editor", 10);
        var editor = fx(() -> (TextField) scene.lookup("#left-pane").lookup("#new-name-editor"));
        fx(() -> { editor.setText("discard-this.txt"); return null; });
        Path outside = Files.writeString(right.resolve("outside.txt"), "new right file");
        await(() -> paneListed(true, outside), "inactive right watcher updates", 12);
        assertSame(editor, fx(() -> scene.lookup("#left-pane").lookup("#new-name-editor")));
        focusPane(true);
        assertEquals("original", Files.readString(file));
        assertFalse(Files.exists(left.resolve("discard-this.txt")));
        goPane(true, left);
        await(() -> paneWatching(true) && paneWatching(false), "separate registrations for one directory", 12);
        Path shared = Files.writeString(left.resolve("shared.txt"), "seen from both");
        await(() -> paneListed(false, shared) && paneListed(true, shared), "external change in both panes", 12);
        assertTrue(watcher(false).registrationCount() + watcher(true).registrationCount() <= 4);

        toggleAutoRefresh(false);
        focusPane(false);
        select(shared);
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(shared)), "shared source selected", 10);
        focusPane(true);
        click("paste");
        Path copy = left.resolve("shared - 副本.txt");
        await(() -> paneListed(false, copy) && paneListed(true, copy), "write receipt refreshes both with watching disabled", 12);
        assertEquals("seen from both", Files.readString(copy));
        toggleAutoRefresh(true);
        await(() -> paneWatching(false) && paneWatching(true), "re-enable both watches", 12);
        click("dual-pane");
        await(() -> watcher(true).registrationCount() == 0 && watcher(false).registrationCount() > 0,
                "closing right releases its registrations", 8);
        Path later = Files.writeString(left.resolve("later.txt"), "left still watches");
        await(() -> paneListed(false, later), "left retains its watcher", 10);
    }

    @Test
    void closingTheRightPaneDiscardsItsPreviewAndReopeningRechecksDirectoryIdentity() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("left"));
        Path right = Files.createDirectory(root.resolve("right"));
        Path first = Files.writeString(left.resolve("excerpt.txt"), "left preview");
        Path second = Files.writeString(right.resolve("excerpt.txt"), "right preview");
        openWindow(true);
        goWatched(left);
        click("dual-pane");
        goPane(true, right);
        await(() -> paneWatching(true) && paneAt(true, right), "right directory watched", 12);
        focusPane(false);
        select(first);
        menu("viewItems", "show-preview");
        await(() -> previewText().equals("left preview"), "left preview", 10);
        focusPane(true);
        fx(() -> { assertTrue(paneBrowser(true).reveal(second)); return null; });
        await(() -> !paneContext(true).selectionProof.isEmpty(), "right selection verified", 10);
        var oldIdentity = fx(() -> paneContext(true).directoryIdentity);
        var preview = new Gate();
        heldRead.set(new ReadGate(second, true, preview));
        try {
            menu("viewItems", "show-preview");
            assertTrue(preview.entered.await(8, TimeUnit.SECONDS));
            focusPane(false);
            assertEquals("left preview", fx(this::previewText));
            click("dual-pane");
            assertNull(fx(() -> paneContext(true).previewTask));
            assertEquals("left preview", fx(this::previewText));
        } finally {
            preview.release.countDown();
        }
        assertTrue(preview.returned.await(8, TimeUnit.SECONDS));
        await(() -> watcher(true).registrationCount() == 0, "closed right watcher released", 8);
        Path saved = Files.move(right, root.resolve("saved"));
        Files.createDirectory(right);
        Files.writeString(second, "replacement preview");
        click("dual-pane");
        await(() -> paneAt(true, right) && paneListed(true, second) && paneWatching(true)
                && !oldIdentity.equals(paneContext(true).directoryIdentity), "reopened right directory verified", 12);
        focusPane(true);
        assertTrue(fx(() -> paneBrowser(true).selection().isEmpty() && previewText().isEmpty()));
        assertEquals("right preview", Files.readString(saved.resolve("excerpt.txt")));
        fx(() -> { assertTrue(paneBrowser(true).reveal(second)); return null; });
        await(() -> previewText().equals("replacement preview"), "new deliberate selection", 10);
        focusPane(false);
        assertEquals("left preview", fx(this::previewText));
    }

    @Test
    void clipboardWorksInBothDirectionsAndDirectTransferKeepsItsVersion() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("left"));
        Path right = Files.createDirectory(root.resolve("right"));
        Path copyLeft = Files.writeString(left.resolve("left-copy.txt"), "left copy");
        Path cutLeft = Files.writeString(left.resolve("left-move.txt"), "left move");
        Path copyRight = Files.writeString(right.resolve("right-copy.txt"), "right copy");
        Path cutRight = Files.writeString(right.resolve("right-move.txt"), "right move");
        Path directCopy = Files.writeString(left.resolve("direct-copy.txt"), "direct copy");
        Path directMove = Files.writeString(right.resolve("direct-move.txt"), "direct move");
        var clipboard = new TestClipboard();
        openWindow(false, clipboard);
        go(left);
        click("dual-pane");
        goPane(true, right);
        clipboardAcross(false, copyLeft, false, right);
        clipboardAcross(true, copyRight, false, left);
        clipboardAcross(false, cutLeft, true, right);
        clipboardAcross(true, cutRight, true, left);
        var state = clipboard.text("clipboard before direct transfer");
        await(() -> state.equals(field(app, "clipboardState")), "window observes clipboard fixture", 10);
        focusPane(false);
        select(directCopy);
        fx(() -> { shortcut(browser, KeyCode.F5, true); return null; });
        Path copyResult = right.resolve(directCopy.getFileName());
        await(() -> idle() && paneListed(true, copyResult), "direct copy into inactive pane", 12);
        assertEquals(Files.readString(directCopy), Files.readString(copyResult));
        focusPane(true);
        fx(() -> { assertTrue(paneBrowser(true).reveal(directMove)); return null; });
        click("move-across");
        Path moveResult = left.resolve(directMove.getFileName());
        await(() -> idle() && paneListed(false, moveResult) && !paneListed(true, directMove), "direct move", 12);
        assertEquals(state, clipboard.probe());
        assertEquals("clipboard before direct transfer", clipboard.textValue());
        focusPane(false);
        menu("moreItems", "undo");
        await(() -> idle() && paneListed(true, directMove) && !paneListed(false, moveResult), "shared undo", 12);
        assertEquals("direct move", Files.readString(directMove));
        assertEquals(state, clipboard.probe());
    }

    @Test
    void transferKeepsFrozenTargetDuringPastePreparationAndClosingTheDestination() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("左侧资料"));
        Path right = Files.createDirectory(root.resolve("右侧目标"));
        Path elsewhere = Files.createDirectory(root.resolve("观察"));
        Path old = Files.writeString(left.resolve("准备.txt"), "paste payload");
        Path write = Files.writeString(left.resolve("后台复制.txt"), "direct payload");
        var clipboard = new TestClipboard();
        openWindow(false, clipboard);
        go(left);
        click("dual-pane");
        goPane(true, right);
        focusPane(false);
        select(old);
        click("copy");
        await(() -> cutSources().equals(List.of(old)) && idle(), "copy entries recorded", 10);
        focusPane(true);
        var reading = new Gate();
        clipboard.nextRead.set(() -> { reading.hold(); return clipboard.snapshot(); });
        try {
            click("paste");
            assertTrue(reading.entered.await(8, TimeUnit.SECONDS));
            focusPane(false);
        } finally {
            reading.release.countDown();
        }
        await(() -> idle() && paneListed(true, right.resolve(old.getFileName())), "paste retains right target from request", 12);
        select(write);
        var commit = new Gate();
        var preparation = new Gate();
        heldPublish.set(commit);
        try {
            ((ExecutorService) fx(() -> field(app, "writes"))).execute(preparation::hold);
            assertTrue(preparation.entered.await(8, TimeUnit.SECONDS));
            click("copy-across");
            goPane(true, elsewhere);
            focusPane(true);
            preparation.release.countDown();
            assertTrue(commit.entered.await(8, TimeUnit.SECONDS));
            assertTrue(fx(() -> paneAt(true, elsewhere)));
            goPane(true, right);
            capturePanes("dual-transfer.png");
            click("dual-pane");
            assertTrue(fx(() -> scene.lookup("#right-pane") == null));
        } finally {
            preparation.release.countDown();
            commit.release.countDown();
        }
        Path published = right.resolve(write.getFileName());
        await(() -> idle() && Files.exists(published), "task commits after target pane closes", 12);
        assertEquals("direct payload", Files.readString(published));
        assertEquals("paste payload", Files.readString(right.resolve(old.getFileName())));
        assertFalse(Files.exists(elsewhere.resolve(write.getFileName())));
        assertTrue(fx(() -> published.getParent().equals(right) && paneAt(false, left)));
        assertEquals(List.of(old), fx(this::cutSources));
    }

    @Test
    void crossPaneConflictRetainsPartialResultsAndRejectsDescendantTargets() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("left"));
        Path right = Files.createDirectory(root.resolve("right"));
        Path one = Files.writeString(left.resolve("a.txt"), "already succeeds");
        Path two = Files.writeString(left.resolve("b.txt"), "copy source");
        Path original = Files.writeString(right.resolve("b.txt"), "old target");
        Path descendant = Files.createDirectories(left.resolve("structure/child"));
        openWindow(false, new TestClipboard());
        go(left);
        click("dual-pane");
        goPane(true, right);
        focusPane(false);
        fx(() -> { browser.selectAll(); return null; });
        click("copy-across");
        await(() -> conflictScene() != null && Files.exists(right.resolve(one.getFileName())), "some files written before conflict", 12);
        focusPane(true);
        goPane(true, descendant);
        focusPane(false);
        chooseConflict("conflict-skip");
        await(() -> idle() && paneAt(true, descendant) && progressLabel("operation-details").contains("已跳过 1 项"),
                "navigation survives conflict resolution", 12);
        assertEquals("old target", Files.readString(original));
        assertEquals("copy source", Files.readString(two));
        assertTrue(Files.isDirectory(right.resolve("structure/child")));
        select(left.resolve("structure"));
        click("copy-across");
        acknowledgeFileError();
        assertFalse(Files.exists(descendant.resolve("structure")));
        assertTrue(fx(() -> paneAt(false, left) && paneAt(true, descendant)));
    }

    @Test
    void rightPaneCanCloseDuringDirectMoveAndWindowKeepsUndoAndUnknownResults() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("left"));
        Path right = Files.createDirectory(root.resolve("right"));
        Path source = Files.writeString(right.resolve("move.txt"), "source payload");
        Path target = left.resolve(source.getFileName());
        var clipboard = new TestClipboard();
        var originalClipboard = clipboard.text("from another application");
        openWindow(false, clipboard);
        go(left);
        click("dual-pane");
        goPane(true, right);
        focusPane(true);
        fx(() -> { assertTrue(paneBrowser(true).reveal(source)); return null; });
        var move = new Gate();
        heldMove.set(move);
        try {
            fx(() -> { shortcut(paneBrowser(true), KeyCode.F6, true); return null; });
            assertTrue(move.entered.await(8, TimeUnit.SECONDS));
            click("dual-pane");
        } finally {
            move.release.countDown();
        }
        await(() -> idle() && paneListed(false, target), "move survives closing the source pane", 12);
        assertFalse(Files.exists(source));
        assertEquals("source payload", Files.readString(target));
        Files.writeString(target, "latest payload");
        menu("moreItems", "undo");
        await(() -> idle() && !paneListed(false, target) && Files.exists(source), "undo moves the latest bytes back", 12);
        assertEquals("latest payload", Files.readString(source));
        click("dual-pane");
        await(() -> paneAt(true, right) && paneListed(true, source), "right rebuilds from disk after undo", 12);
        focusPane(true);
        fx(() -> { assertTrue(paneBrowser(true).reveal(source)); return null; });
        loseMoveReceipt.set(true);
        click("move-across");
        acknowledgeFileError();
        await(() -> idle() && paneListed(false, target) && !paneListed(true, source), "both panes reread uncertain move", 12);
        assertEquals("latest payload", Files.readString(target));
        assertFalse(Files.exists(source));
        assertEquals(1, injectedWrites.get());
        assertTrue(fx(() -> ((List<?>) field(app, "undo")).isEmpty()
                && ((List<?>) field(app, "unknownOperations")).size() == 1
                && activePane() == pane(true)));
        assertEquals(originalClipboard, clipboard.probe());
    }

    @Test
    void directCopyRefusesAReplacedTargetAndCanCancelActiveStaging() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path left = Files.createDirectory(root.resolve("left"));
        Path right = Files.createDirectory(root.resolve("right"));
        Path payload = Files.write(left.resolve("sample.bin"), new byte[12 * 1024 * 1024]);
        openWindow(false);
        go(left);
        click("dual-pane");
        goPane(true, right);
        focusPane(false);
        select(payload);
        var preparation = new Gate();
        try {
            ((ExecutorService) fx(() -> field(app, "writes"))).execute(preparation::hold);
            assertTrue(preparation.entered.await(8, TimeUnit.SECONDS));
            click("copy-across");
            Files.move(right, root.resolve("original-target"));
            Files.createDirectory(right);
        } finally {
            preparation.release.countDown();
        }
        acknowledgeFileError();
        assertFalse(Files.exists(right.resolve(payload.getFileName())));
        assertFalse(Files.exists(root.resolve("original-target").resolve(payload.getFileName())));

        Path foreign = Files.writeString(right.resolve(".file-manager-neighbor.txt"), "outside the copy task");
        goPane(true, right);
        focusPane(false);
        pauseNativeCopy.set(true);
        try {
            click("copy-across");
            assertTrue(copyHasBytes.await(10, TimeUnit.SECONDS));
            clickOnPane(true, "refresh");
            await(() -> paneAt(true, right) && paneListed(true, foreign) && progressScene() != null,
                    "target can be browsed during the staged copy", 10);
            assertEquals(List.of(foreign), fx(() -> paneBrowser(true).items().stream().map(BrowserItem::path).toList()));
            fx(() -> {
                ((Button) progressScene().lookup("#operation-action")).fire();
                assertTrue(((FileOperationTask) field(app, "activeFileOperation")).cancellationRequested());
                return null;
            });
        } finally {
            releaseCopy.countDown();
        }
        await(() -> idle() && "已取消".equals(progressLabel("operation-stage")), "cancelled direct copy", 10);
        try (var paths = Files.list(right)) { assertEquals(List.of(foreign), paths.toList()); }
        assertEquals("outside the copy task", Files.readString(foreign));
        assertEquals(12L * 1024 * 1024, Files.size(payload));
    }

    @Test
    void directMoveRefusesADifferentNtfsVolume() throws Exception {
        String fixtureParent = System.getProperty("javafx.secondaryNtfsParent");
        assumeTrue(fixtureParent != null, "requires -Djavafx.secondaryNtfsParent on a different NTFS volume");
        var local = NtfsFileOperations.captureDirectory(root);
        Path secondary = Path.of(fixtureParent);
        var other = NtfsFileOperations.captureDirectory(secondary);
        assumeTrue(local != null && other != null && local.id().volume() != other.id().volume(),
                "requires two local NTFS volumes");
        Path source = Files.writeString(root.resolve("cross-volume.txt"), "kept on the source volume");
        Path target = Files.createTempDirectory(secondary, "phase31-cross-volume-");
        Path unexpected = target.resolve(source.getFileName());
        try {
            openWindow(false);
            go(root);
            click("dual-pane");
            goPane(true, target);
            focusPane(false);
            select(source);
            click("move-across");
            acknowledgeFileError();
            assertEquals("kept on the source volume", Files.readString(source));
            assertFalse(Files.exists(unexpected));
            assertNull(fx(() -> field(app, "activeFileOperation")));
            var drag = selectForDrag(false, source);
            dragIntent.set(FileDrag.Intent.MOVE);
            Node otherVolume = fx(() -> paneBrowser(true));
            assertFalse(dragOver(drag, otherVolume));
            assertTrue(fx(this::dragStatusText).contains("同卷 NTFS"));
            assertFalse(dropFiles(drag, otherVolume));
            dragDone(drag, null);
            assertEquals("kept on the source volume", Files.readString(source));
            assertFalse(Files.exists(unexpected));
        } finally {
            Files.deleteIfExists(unexpected);
            Files.delete(target);
        }
    }

    private BrowseContext paneContext(boolean right) throws Exception { return (BrowseContext) field(pane(right), "context"); }
    private BrowserView paneBrowser(boolean right) throws Exception { return (BrowserView) field(pane(right), "browser"); }
    private DirectoryWatcher watcher(boolean right) throws Exception { return (DirectoryWatcher) field(pane(right), "watcher"); }

    private boolean paneAt(boolean right, Path directory) throws Exception {
        var context = paneContext(right);
        return context.browseRequest == null && !context.needsReload && directory.equals(context.location.path());
    }

    private boolean paneListed(boolean right, Path path) throws Exception {
        return paneBrowser(right).items().stream().anyMatch(item -> path.equals(item.path()));
    }

    private boolean paneWatching(boolean right) throws Exception {
        return "自动刷新已开启".equals(field(pane(right), "watchText"));
    }

    private javafx.scene.Node paneControl(boolean right, String id) throws Exception {
        return ((javafx.scene.Parent) field(pane(right), "navigation")).lookup("#" + id);
    }

    private void clickOnPane(boolean right, String id) throws Exception {
        fx(() -> { ((Button) paneControl(right, id)).fire(); return null; });
    }

    private void enterPane(boolean right, Path directory) throws Exception {
        fx(() -> { enterPaneAddress(right, directory); return null; });
    }

    private void enterPaneAddress(boolean right, Path directory) throws Exception {
        var bar = (AddressBar) field(pane(right), "address");
        bar.beginEdit();
        var editor = (TextField) bar.lookup("#address");
        editor.setText(directory.toString());
        editor.fireEvent(new ActionEvent());
    }

    private void goPane(boolean right, Path directory) throws Exception {
        enterPane(right, directory);
        await(() -> paneAt(right, directory), "pane reads " + directory, 12);
    }

    private void focusPane(boolean right) throws Exception {
        fx(() -> {
            if (activePane() != pane(right)) {
                Event.fireEvent(scene.getRoot(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F6,
                        false, false, false, false));
            }
            assertSame(pane(right), activePane());
            return null;
        });
    }

    private void clipboardAcross(boolean right, Path file, boolean move, Path target) throws Exception {
        String payload = Files.readString(file);
        focusPane(right);
        fx(() -> { assertTrue(paneBrowser(right).reveal(file)); return null; });
        click(move ? "cut" : "copy");
        await(() -> idle() && cutSources().equals(List.of(file)), "clipboard intent inherited by the other pane", 10);
        focusPane(!right);
        click("paste");
        Path result = target.resolve(file.getFileName());
        await(() -> idle() && paneListed(!right, result), "cross-pane clipboard result", 12);
        assertEquals(!move, Files.exists(file));
        assertEquals(payload, Files.readString(result));
    }

    private void capturePanes(String name) throws Exception {
        if (!Boolean.getBoolean("javafx.capturePanes")) { return; }
        if (captures == null) { captures = Files.createTempDirectory("phase31-fx-"); }
        var snapshot = fx(() -> scene.snapshot(null));
        var picture = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        copyPixels(snapshot, picture, 0);
        Path file = captures.resolve(name);
        assertTrue(ImageIO.write(picture, "png", file.toFile()));
        System.out.println("PHASE31_SCREENSHOT " + file);
    }

    @Test
    void tabCommandsKeepNavigationSearchAndTextInputSeparate() throws Exception {
        Path first = Files.createDirectory(root.resolve("工作目录"));
        Path child = Files.createDirectory(first.resolve("项目资料"));
        Path match = Files.writeString(child.resolve("needle.txt"), "a search match");
        Files.writeString(child.resolve("other.txt"), "excluded");
        openWindow(false);
        go(first);
        Tab original = fx(() -> tabs().getSelectionModel().getSelectedItem());
        context(child, "context-open-tab");
        await(() -> browseAt(child) && listed(match), "folder opened in a new tab", 10);
        Tab searched = fx(() -> tabs().getSelectionModel().getSelectedItem());
        assertTrue(owner(searched).past.isEmpty());
        fx(() -> {
            var search = (TextField) scene.lookup("#search-field");
            search.setText("needle");
            search.fireEvent(new ActionEvent());
            return null;
        });
        await(() -> "needle".equals(currentBrowse().activeQuery) && browser.items().size() == 1,
                "first committed search", 8);
        fx(() -> {
            var search = (TextField) scene.lookup("#search-field");
            var textShortcut = new AtomicInteger();
            search.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.V && event.isShortcutDown()) {
                    textShortcut.incrementAndGet();
                    event.consume(); // Exercise routing without opening the real system clipboard.
                }
            });
            shortcut(search, KeyCode.V, false);
            assertEquals(1, textShortcut.get());
            shortcut(search, KeyCode.T, false);
            return null;
        });
        await(() -> browseAt(child) && currentBrowse().activeQuery.isEmpty() && browser.items().size() == 2,
                "new tab from a search starts at its directory", 10);
        Tab unfiltered = fx(() -> tabs().getSelectionModel().getSelectedItem());
        fx(() -> {
            shortcut(browser, KeyCode.TAB, true);
            assertSame(searched, tabs().getSelectionModel().getSelectedItem());
            return null;
        });
        await(() -> browseAt(child) && browser.items().size() == 1, "search restored", 10);
        assertTrue(fx(() -> searched.getText().contains("needle") && searched.getGraphic() != null));
        captureTabs("independent-tabs.png");
        fx(() -> {
            searched.getContextMenu().getItems().getFirst().fire();
            return null;
        });
        await(() -> browseAt(child) && currentBrowse() != owner(searched) && "needle".equals(currentBrowse().activeQuery),
                "duplicate preserves current search", 10);
        Tab duplicate = fx(() -> tabs().getSelectionModel().getSelectedItem());
        fx(() -> {
            for (int i = 0; i < 20; i++) {
                tabs().getSelectionModel().select(original);
                tabs().getSelectionModel().select(unfiltered);
            }
            tabs().getSelectionModel().select(duplicate);
            return null;
        });
        await(() -> browseAt(child) && owner(duplicate) == currentBrowse()
                        && ((TextField) scene.lookup("#search-field")).getText().equals("needle") && browser.items().size() == 1,
                "rapid tab churn retains the active tab's search and listing", 12);
        go(first);
        assertEquals(child, fx(() -> currentBrowse().past.getFirst().path()));
        assertTrue(owner(searched).past.isEmpty());
        click("back");
        await(() -> browseAt(child) && !button("forward").isDisabled(), "duplicate follows its own history", 10);
        enter(root.resolve("missing"));
        acknowledgeFileError();
        assertTrue(fx(() -> browseAt(child) && !button("forward").isDisabled()));
        fx(() -> {
            shortcut(browser, KeyCode.TAB, false);
            assertSame(original, tabs().getSelectionModel().getSelectedItem());
            close(duplicate);
            close(unfiltered);
            close(searched);
            return null;
        });
        await(() -> browseAt(first) && tabs().getTabs().size() == 1, "the original tab keeps its history", 10);
        assertFalse(fx(() -> button("back").isDisabled()));
        fx(() -> { shortcut(browser, KeyCode.W, false); return null; });
        await(() -> idle() && currentBrowse().location.page() == BrowseContext.Page.HOME
                && owner(original).closed && tabs().getTabs().size() == 1, "last tab is replaced by Home", 10);
    }

    @Test
    void restoresPresentationAndVerifiedSelectionAndCancelsOldInlineEdit() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path directory = Files.createDirectory(root.resolve("资料库"));
        for (int i = 0; i < 160; i++) {
            Files.writeString(directory.resolve("row-%03d.txt".formatted(i)), "content " + i);
        }
        Path selected = directory.resolve("row-090.txt");
        Path filtered = Files.writeString(directory.resolve("excluded.dat"), "foreign type");
        openWindow(false);
        go(directory);
        Tab original = fx(() -> tabs().getSelectionModel().getSelectedItem());
        select(selected);
        menu("viewItems", "show-preview");
        await(() -> hasSelectionProof() && previewText().equals("content 90"), "NTFS selection and preview", 10);
        fx(() -> {
            var filters = (ColumnFilters) field(browser, "filters");
            filters.select(BrowserView.Field.TYPE, new ColumnFilters.Bucket(0, "TXT 文件"), true);
            call(browser, "applyFilters");
            entriesFlow().scrollToTop(75);
            return null;
        });
        await(() -> entriesFlow().getFirstVisibleCell().getIndex() >= 70, "scrolled before switching", 10);
        int row = fx(() -> entriesFlow().getFirstVisibleCell().getIndex());
        click("new-tab");
        await(() -> browseAt(directory) && listed(filtered) && !browser.filtersActive(), "same directory with another view", 10);
        Tab other = fx(() -> tabs().getSelectionModel().getSelectedItem());
        menu("viewItems", "mode-list");
        menu("sortItems", "sort-size");
        assertEquals(BrowserView.Mode.LIST, owner(other).view().mode());
        assertEquals(BrowserView.Field.SIZE, owner(other).view().sort());
        activate(original);
        await(() -> browseAt(directory) && selectedPaths().equals(List.of(selected)) && previewText().equals("content 90"),
                "selection and preview rebuilt from verified entry", 12);
        assertTrue(fx(() -> browser.filtersActive() && !listed(filtered)));
        assertEquals(BrowserView.Mode.DETAILS, owner(original).view().mode());
        assertEquals(BrowserView.Field.NAME, owner(original).view().sort());
        assertTrue(Math.abs(row - fx(() -> entriesFlow().getFirstVisibleCell().getIndex())) <= 2);

        click("rename");
        await(() -> scene.lookup("#new-name-editor") != null, "inline editor", 10);
        var editor = fx(() -> (TextField) scene.lookup("#new-name-editor"));
        fx(() -> {
            editor.setText("must-not-commit.txt");
            shortcut(editor, KeyCode.TAB, false);
            return null;
        });
        await(() -> browseAt(directory) && currentBrowse() == owner(other), "switch while editing", 10);
        assertTrue(Files.exists(selected));
        assertFalse(Files.exists(directory.resolve("must-not-commit.txt")));
        Path saved = Files.move(selected, root.resolve("saved.txt"));
        Files.writeString(selected, "replacement");
        activate(original);
        await(() -> browseAt(directory) && listed(selected) && selectedPaths().isEmpty() && previewText().isEmpty(),
                "replacement cannot inherit selection", 12);
        assertEquals("content 90", Files.readString(saved));
        assertEquals("replacement", Files.readString(selected));
        assertNull(fx(() -> scene.lookup("#new-name-editor")));
    }

    @Test
    void closedTabsDiscardLateListingsAndPreviews() throws Exception {
        Path first = Files.createDirectory(root.resolve("first"));
        Path later = Files.createDirectory(root.resolve("late"));
        Path second = Files.createDirectory(root.resolve("second"));
        Path late = Files.writeString(later.resolve("late.txt"), "old listing");
        Path oldPreview = Files.writeString(second.resolve("old.txt"), "old preview");
        Path third = Files.createDirectory(root.resolve("third"));
        Path fresh = Files.writeString(third.resolve("fresh.txt"), "current preview");
        openWindow(false);
        go(first);
        Tab a = fx(() -> tabs().getSelectionModel().getSelectedItem());
        Tab b = openAt(second);
        activate(a);
        await(() -> browseAt(first), "return before blocked read", 10);
        var listingGate = new Gate();
        heldRead.set(new ReadGate(later, false, listingGate));
        try {
            enter(later);
            assertTrue(listingGate.entered.await(8, TimeUnit.SECONDS));
            activate(b);
            await(() -> browseAt(second) && listed(oldPreview), "foreground reads while old request is returning", 10);
            fx(() -> { close(a); return null; });
            assertTrue(fx(() -> owner(a).closed && owner(a).browseRequest == null && owner(a).listing.items().isEmpty()));
        } finally {
            listingGate.release.countDown();
        }
        assertTrue(listingGate.returned.await(8, TimeUnit.SECONDS));
        assertTrue(fx(() -> browseAt(second) && !listed(late)));
        select(oldPreview);
        var previewGate = new Gate();
        heldRead.set(new ReadGate(oldPreview, true, previewGate));
        try {
            menu("viewItems", "show-preview");
            assertTrue(previewGate.entered.await(8, TimeUnit.SECONDS));
            openAt(third);
            fx(() -> { close(b); return null; });
            select(fresh);
            menu("viewItems", "show-preview");
            await(() -> previewText().equals("current preview"), "new preview while old tab is closed", 10);
        } finally {
            previewGate.release.countDown();
        }
        assertTrue(previewGate.returned.await(8, TimeUnit.SECONDS));
        assertTrue(fx(() -> previewText().equals("current preview") && owner(b).previewTask == null));
    }

    @Test
    void reactivationChecksDirectoryReplacementAndSameFolderWatchOwnership() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path directory = Files.createDirectory(root.resolve("watched"));
        Path other = Files.createDirectory(root.resolve("elsewhere"));
        Path selected = Files.writeString(directory.resolve("original.txt"), "old");
        openWindow(true);
        goWatched(directory);
        Tab first = fx(() -> tabs().getSelectionModel().getSelectedItem());
        select(selected);
        await(this::hasSelectionProof, "selected before deactivation", 8);
        var oldTarget = fx(() -> owner(first).watchTarget);
        click("new-tab");
        await(() -> browseAt(directory) && watching() && currentBrowse().watchTarget != oldTarget,
                "independent tab with a new watcher token for the same path", 12);
        assertNull(fx(() -> owner(first).watchTarget));
        signal(oldTarget, DirectoryWatcher.Status.WAITING, true);
        assertTrue(fx(this::watching));
        Path fresh = Files.writeString(directory.resolve("fresh.txt"), "watch event");
        await(() -> listed(fresh), "external update on the visible tab", 10);
        activate(first);
        await(() -> browseAt(directory) && listed(fresh) && selectedPaths().equals(List.of(selected)),
                "dormant same-directory tab reads the new entry", 12);
        Tab second = fx(() -> tabs().getTabs().get(1));
        activate(second);
        goWatched(other);
        Files.move(directory, root.resolve("prior"));
        Files.createDirectory(directory);
        Files.writeString(selected, "new identity");
        activate(first);
        await(() -> browseAt(directory) && watching() && selectedPaths().isEmpty() && !listed(fresh),
                "directory identity replaced while dormant", 15);
        assertEquals("new identity", Files.readString(selected));
        assertEquals("old", Files.readString(root.resolve("prior/original.txt")));
        var watcher = (DirectoryWatcher) field(pane(false), "watcher");
        assertTrue(watcher.registrationCount() <= 2);
    }

    @Test
    void pasteKeepsItsDestinationThroughTabChangesAndUndoIsWindowWide() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path source = Files.createDirectory(root.resolve("source"));
        Path target = Files.createDirectory(root.resolve("target"));
        Path other = Files.createDirectory(root.resolve("other"));
        Path copied = Files.writeString(source.resolve("copied.txt"), "copy");
        Path moved = Files.writeString(source.resolve("moved.txt"), "move");
        var clipboard = new TestClipboard();
        openWindow(false, clipboard);
        go(source);
        Tab a = fx(() -> tabs().getSelectionModel().getSelectedItem());
        select(copied);
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(copied)), "window copy", 10);
        Tab b = openAt(target);
        Tab c = openAt(other);
        activate(b);
        await(() -> browseAt(target), "paste destination ready", 10);
        var clipboardGate = new Gate();
        var publish = new Gate();
        clipboard.nextRead.set(() -> { clipboardGate.hold(); return clipboard.snapshot(); });
        heldPublish.set(publish);
        try {
            click("paste");
            assertTrue(clipboardGate.entered.await(8, TimeUnit.SECONDS));
            activate(c);
            clipboardGate.release.countDown();
            assertTrue(publish.entered.await(8, TimeUnit.SECONDS));
            fx(() -> { close(b); return null; });
            await(() -> browseAt(other) && currentBrowse() == owner(c), "active view stays in its own directory", 10);
        } finally {
            clipboardGate.release.countDown();
            publish.release.countDown();
        }
        await(() -> idle() && Files.exists(target.resolve(copied.getFileName())), "copy to original destination", 12);
        assertFalse(Files.exists(other.resolve(copied.getFileName())));
        assertEquals("copy", Files.readString(target.resolve(copied.getFileName())));
        activate(a);
        await(() -> browseAt(source), "back to cut source", 10);
        select(moved);
        click("cut");
        await(() -> idle() && cutSources().equals(List.of(moved)), "window cut", 10);
        Tab destination = openAt(target);
        fx(() -> { close(a); return null; });
        click("paste");
        await(() -> idle() && listed(target.resolve(moved.getFileName())), "closed source still moves by original identity", 12);
        assertFalse(Files.exists(moved));
        activate(c);
        await(() -> browseAt(other), "undo from a different tab", 10);
        menu("moreItems", "undo");
        await(() -> idle() && browseAt(other) && Files.exists(moved), "window undo", 10);
        assertFalse(Files.exists(target.resolve(moved.getFileName())));
        assertEquals("move", Files.readString(moved));
        activate(destination);
        await(() -> browseAt(target) && !listed(target.resolve(moved.getFileName())), "inactive destination refreshes after undo", 10);
    }

    @Test
    void conflictAndPartialResultOutliveTheirTabAndRefreshOtherDestinations() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path source = Files.createDirectory(root.resolve("source"));
        Path target = Files.createDirectory(root.resolve("target"));
        Path other = Files.createDirectory(root.resolve("other"));
        Path first = Files.writeString(source.resolve("a.txt"), "first");
        Path second = Files.writeString(source.resolve("b.txt"), "source");
        Path occupant = Files.writeString(target.resolve("b.txt"), "existing");
        var clipboard = new TestClipboard();
        openWindow(false, clipboard);
        go(source);
        Tab sourceTab = fx(() -> tabs().getSelectionModel().getSelectedItem());
        fx(() -> { browser.selectAll(); return null; });
        click("copy");
        await(() -> idle() && cutSources().size() == 2, "batch captured", 10);
        Tab observer = openAt(target);
        Tab c = openAt(other);
        Tab origin = openAt(target);
        click("paste");
        await(() -> conflictScene() != null && Files.exists(target.resolve(first.getFileName())),
                "confirmed batch prefix and visible conflict", 12);
        activate(c);
        fx(() -> { close(origin); return null; });
        await(() -> browseAt(other) && conflictScene() != null, "conflict remains available in another tab", 10);
        var newer = clipboard.text("new clipboard owner");
        chooseConflict("conflict-skip");
        await(() -> idle() && browseAt(other) && progressLabel("operation-details").contains("成功 1 项 · 已跳过 1 项"),
                "partial task ends with real results", 10);
        assertEquals("existing", Files.readString(occupant));
        assertEquals(newer, clipboard.probe());
        assertEquals("new clipboard owner", clipboard.textValue());
        activate(observer);
        await(() -> browseAt(target) && listed(target.resolve(first.getFileName())), "second destination context reads the write", 10);
        assertEquals("first", Files.readString(target.resolve(first.getFileName())));

        activate(sourceTab);
        await(() -> browseAt(source), "return to source for next transfer", 10);
        select(second);
        click("cut");
        await(() -> idle() && cutSources().equals(List.of(second)), "new cut", 10);
        Tab reviewTarget = openAt(Files.createDirectory(root.resolve("review")));
        loseMoveReceipt.set(true);
        click("paste");
        activate(observer);
        acknowledgeFileError();
        await(() -> !((List<?>) field(app, "unknownOperations")).isEmpty() && cutSources().isEmpty()
                && currentBrowse() == owner(observer), "unknown outcome is kept by the window", 12);
        fx(() -> { close(reviewTarget); return null; });
        assertFalse(Files.exists(second));
        assertEquals("source", Files.readString(root.resolve("review/b.txt")));
        assertTrue(fx(() -> scene.lookup("#unknown-results").isVisible()));
    }

    @Test
    @EnabledIfSystemProperty(named = "javafx.paneBenchmark", matches = "true")
    void measureDualPaneLatencyAndRetainedResources() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path leftSmall = Files.createDirectory(root.resolve("left-small"));
        Path rightSmall = Files.createDirectory(root.resolve("right-small"));
        Path leftLarge = Files.createDirectory(root.resolve("left-large"));
        Path rightLarge = Files.createDirectory(root.resolve("right-large"));
        for (int i = 0; i < 10_000; i++) {
            String name = "item-%05d.txt".formatted(i);
            Files.createFile(leftLarge.resolve(name));
            Files.createFile(rightLarge.resolve(name));
            if (i < 100) {
                Files.createFile(leftSmall.resolve(name));
                Files.createFile(rightSmall.resolve(name));
            }
        }
        Path payload = Files.write(leftSmall.resolve("sample.bin"), new byte[24 * 1024 * 1024]);
        openWindow(true, new TestClipboard());
        goWatched(leftLarge);
        long singleHeap = usedHeap();
        int singleWorkers = workerThreads();
        int singleWatches = watcher(false).registrationCount();
        click("dual-pane");
        goPane(true, rightSmall);
        for (int i = 0; i < 3; i++) {
            timePaneReads(leftSmall, rightSmall);
            timePaneReads(leftLarge, rightLarge);
        }
        var smallLeft = new ArrayList<Long>();
        var smallRight = new ArrayList<Long>();
        var largeLeft = new ArrayList<Long>();
        var largeRight = new ArrayList<Long>();
        for (int i = 0; i < 5; i++) {
            var small = timePaneReads(leftSmall, rightSmall);
            var large = timePaneReads(leftLarge, rightLarge);
            smallLeft.add(small.get(0));
            smallRight.add(small.get(1));
            largeLeft.add(large.get(0));
            largeRight.add(large.get(1));
        }
        await(() -> paneWatching(false) && paneWatching(true), "two large directories watched", 12);
        assertEquals(10_000, fx(() -> paneBrowser(false).items().size()));
        assertEquals(10_000, fx(() -> paneBrowser(true).items().size()));
        long dualHeap = usedHeap();
        int dualWorkers = workerThreads();
        int dualWatches = watcher(false).registrationCount() + watcher(true).registrationCount();
        var openSamples = new ArrayList<Long>();
        var closeSamples = new ArrayList<Long>();
        for (int i = 0; i < 10; i++) {
            closeSamples.add(timePaneToggle(false));
            await(() -> watcher(true).registrationCount() == 0, "right releases keys after closing", 10);
            assertTrue(fx(() -> paneContext(true).listing.items().isEmpty()));
            openSamples.add(timePaneToggle(true));
            await(() -> paneAt(true, rightLarge) && paneWatching(true), "right reopens with the current listing", 12);
        }
        int repeatedWorkers = workerThreads();
        int repeatedWatches = watcher(false).registrationCount() + watcher(true).registrationCount();
        assertEquals(dualWorkers, repeatedWorkers);
        assertEquals(dualWatches, repeatedWatches);
        assertEquals(1, fx(() -> java.util.Collections.frequency(
                ((javafx.scene.Node) field(pane(true), "navigation")).getStyleClass(), "split-navigation")));
        click("dual-pane");
        await(() -> watcher(true).registrationCount() == 0, "single pane registrations restored", 10);
        long closedHeap = usedHeap();
        int closedWatches = watcher(false).registrationCount() + watcher(true).registrationCount();
        assertEquals(singleWatches, closedWatches);
        click("dual-pane");
        goPane(false, leftSmall);
        goPane(true, rightSmall);
        focusPane(false);
        select(payload);
        var copy = new Gate();
        heldPublish.set(copy);
        List<Long> copying;
        try {
            click("copy-across");
            assertTrue(copy.entered.await(10, TimeUnit.SECONDS));
            copying = timePaneReads(leftLarge, rightLarge);
        } finally {
            copy.release.countDown();
        }
        Path copied = rightSmall.resolve(payload.getFileName());
        await(() -> idle() && Files.exists(copied), "copy completes in the original target", 12);
        assertEquals(-1, Files.mismatch(payload, copied));
        System.out.printf(java.util.Locale.ROOT,
                "PHASE31_BENCH open=%s close=%s smallLeft=%s smallRight=%s largeLeft=%s largeRight=%s copyBrowse=%s "
                        + "heapMiB=%.2f,%.2f,%.2f workers=%d,%d,%d registrations=%d,%d,%d,%d%n",
                milliseconds(openSamples), milliseconds(closeSamples), milliseconds(smallLeft), milliseconds(smallRight),
                milliseconds(largeLeft), milliseconds(largeRight), milliseconds(copying),
                singleHeap / 1048576.0, dualHeap / 1048576.0, closedHeap / 1048576.0,
                singleWorkers, dualWorkers, repeatedWorkers, singleWatches, dualWatches, repeatedWatches, closedWatches);
    }

    private List<Long> timePaneReads(Path left, Path right) throws Exception {
        var leftDone = new CompletableFuture<Long>();
        var rightDone = new CompletableFuture<Long>();
        long start = fx(() -> {
            timedPanes = Map.of(left, leftDone, right, rightDone);
            long now = System.nanoTime();
            enterPaneAddress(false, left);
            enterPaneAddress(true, right);
            return now;
        });
        try {
            return List.of(leftDone.get(20, TimeUnit.SECONDS) - start, rightDone.get(20, TimeUnit.SECONDS) - start);
        } finally {
            fx(() -> { timedPanes = Map.of(); return null; });
        }
    }

    private long timePaneToggle(boolean open) throws Exception {
        return fx(() -> {
            long start = System.nanoTime();
            button("dual-pane").fire();
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            long elapsed = System.nanoTime() - start;
            assertEquals(open, scene.lookup("#right-pane") != null);
            return elapsed;
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "javafx.tabBenchmark", matches = "true")
    void measureTabSwitchingAndRetainedResources() throws Exception {
        Path small = Files.createDirectory(root.resolve("测试文档"));
        Path sample = Files.writeString(small.resolve("样本.txt"), "copy while browsing");
        Path large = Files.createDirectory(root.resolve("一万项夹具"));
        for (int i = 0; i < 10_000; i++) {
            Files.createFile(large.resolve("item-%05d.txt".formatted(i)));
        }
        openWindow(false, new TestClipboard());
        go(small);
        Tab smallTab = fx(() -> tabs().getSelectionModel().getSelectedItem());
        Tab largeTab = openAt(large);
        activate(smallTab);
        await(() -> browseAt(small), "small directory ready", 15);
        for (int i = 0; i < 3; i++) {
            timeTabCommit(() -> tabs().getSelectionModel().select(largeTab));
            timeTabCommit(() -> tabs().getSelectionModel().select(smallTab));
        }
        var largeSamples = new java.util.ArrayList<Long>();
        var smallSamples = new java.util.ArrayList<Long>();
        for (int i = 0; i < 6; i++) {
            largeSamples.add(timeTabCommit(() -> tabs().getSelectionModel().select(largeTab)));
            assertEquals(10_000, fx(() -> browser.items().size()));
            smallSamples.add(timeTabCommit(() -> tabs().getSelectionModel().select(smallTab)));
        }
        long initialHeap = usedHeap();
        int initialThreads = workerThreads();
        var createSamples = new java.util.ArrayList<Long>();
        for (int i = 0; i < 20; i++) {
            createSamples.add(timeTabCommit(() -> button("new-tab").fire()));
        }
        assertEquals(22, fx(() -> tabs().getTabs().size()));
        assertTrue(fx(() -> {
            BrowseContext visible = currentBrowse();
            return tabs().getTabs().stream().map(JavaFxFileOperationsTest::owner)
                    .filter(owner -> owner != visible).allMatch(owner -> owner.listing.items().isEmpty()
                            && owner.browseRequest == null && owner.previewTask == null && owner.watchTarget == null);
        }));
        long manyHeap = usedHeap();
        int manyThreads = workerThreads();
        var manySamples = new java.util.ArrayList<Long>();
        for (int i = 0; i < 6; i++) {
            manySamples.add(timeTabCommit(() -> tabs().getSelectionModel().select(largeTab)));
            timeTabCommit(() -> tabs().getSelectionModel().select(smallTab));
        }
        fx(() -> {
            for (Tab tab : List.copyOf(tabs().getTabs())) {
                if (tab != smallTab && tab != largeTab) { close(tab); }
            }
            return null;
        });
        long finalHeap = usedHeap();
        int finalThreads = workerThreads();
        assertEquals(2, fx(() -> tabs().getTabs().size()));
        assertEquals(initialThreads, finalThreads);
        assertEquals(initialThreads, manyThreads);

        select(sample);
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(sample)), "benchmark copy ready", 10);
        activate(largeTab);
        await(() -> browseAt(large), "benchmark target ready", 15);
        var publish = new Gate();
        heldPublish.set(publish);
        long switchingDuringCopy;
        try {
            click("paste");
            assertTrue(publish.entered.await(8, TimeUnit.SECONDS));
            switchingDuringCopy = timeTabCommit(() -> tabs().getSelectionModel().select(smallTab));
            assertTrue(fx(() -> browseAt(small)));
            captureTabs("tabs-during-copy.png");
        } finally {
            publish.release.countDown();
        }
        await(() -> idle() && Files.exists(large.resolve(sample.getFileName())), "benchmark copy committed", 12);
        System.out.printf(java.util.Locale.ROOT,
                "PHASE30_BENCH create=%s small=%s large=%s largeWith22=%s copySwitch=%.2f heapMiB=%.2f,%.2f,%.2f workers=%d,%d,%d%n",
                milliseconds(createSamples), milliseconds(smallSamples), milliseconds(largeSamples), milliseconds(manySamples),
                switchingDuringCopy / 1e6, initialHeap / 1048576.0, manyHeap / 1048576.0, finalHeap / 1048576.0,
                initialThreads, manyThreads, finalThreads);
    }

    private long timeTabCommit(Runnable action) throws Exception {
        var completed = new CompletableFuture<Long>();
        long start = fx(() -> {
            assertTrue(timedCommit.compareAndSet(null, completed));
            long now = System.nanoTime();
            action.run();
            return now;
        });
        return completed.get(20, TimeUnit.SECONDS) - start;
    }

    private static List<String> milliseconds(List<Long> samples) {
        return samples.stream().map(value -> String.format(java.util.Locale.ROOT, "%.2f", value / 1e6)).toList();
    }

    private static long usedHeap() {
        System.gc();
        return java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static int workerThreads() {
        return Math.toIntExact(Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("file-manager-")).count());
    }

    private void captureTabs(String name) throws Exception {
        if (!Boolean.getBoolean("javafx.captureTabs")) { return; }
        if (captures == null) { captures = Files.createTempDirectory("phase30-fx-"); }
        var snapshot = fx(() -> scene.snapshot(null));
        var picture = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        copyPixels(snapshot, picture, 0);
        Path png = captures.resolve(name);
        assertTrue(ImageIO.write(picture, "png", png.toFile()));
        System.out.println("PHASE30_SCREENSHOT " + png);
    }

    private TabPane tabs() { return (TabPane) scene.lookup("#browse-tabs"); }

    private static BrowseContext owner(Tab tab) { return (BrowseContext) tab.getUserData(); }

    private void activate(Tab tab) throws Exception {
        fx(() -> { tabs().getSelectionModel().select(tab); return null; });
    }

    private static void close(Tab tab) { Event.fireEvent(tab, new Event(Tab.TAB_CLOSE_REQUEST_EVENT)); }

    private Tab openAt(Path path) throws Exception {
        click("new-tab");
        go(path);
        return fx(() -> tabs().getSelectionModel().getSelectedItem());
    }

    @Test
    void windowsWorkflowRestoresLatestBytesAndKeepsPartialCutInSync() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null, "requires Windows x64 local NTFS");
        Path from = Files.createDirectory(root.resolve("from"));
        Path to = Files.createDirectory(root.resolve("to"));
        Path file = Files.writeString(from.resolve("sample.txt"), "preview text");

        openWindow(false);

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
    }

    @Test
    void automaticRefreshRetainsVerifiedSelectionPreviewAndEditor() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null, "requires Windows x64 local NTFS");
        Path folder = Files.createDirectory(root.resolve("visible"));
        Path file = Files.writeString(folder.resolve("a-document.txt"), "first contents");
        openWindow(true);
        goWatched(folder);
        menu("viewItems", "show-preview");
        select(file);
        await(() -> previewText().equals("first contents") && hasSelectionProof(), "initial preview and proof", 10);
        var preview = fx(() -> scene.lookup("#inspector").lookup(".text-area"));
        Path external = folder.resolve("b-external.txt");
        var before = captureAutomaticPane();
        Files.writeString(external, "outside the application");
        await(() -> listed(external) && idle(), "external creation", 10);
        assertEquals(List.of(file), fx(this::selectedPaths));
        assertSame(preview, fx(() -> scene.lookup("#inspector").lookup(".text-area")));
        saveAutomaticCapture(before, captureAutomaticPane());

        Files.writeString(file, "second and updated contents");
        await(() -> idle() && previewText().equals("second and updated contents"), "preview reloaded from disk", 10);

        click("rename");
        await(() -> scene.lookup("#new-name-editor") != null, "original inline edit", 10);
        var editor = fx(() -> (TextField) scene.lookup("#new-name-editor"));
        fx(() -> {
            editor.setText("never-rename-the-replacement.txt");
            return null;
        });
        Path unrelated = Files.writeString(folder.resolve("c-unrelated.txt"), "new during editing");
        await(() -> field(currentBrowse(), "pendingChanges") != null, "unrelated change held during edit", 10);
        assertSame(editor, fx(() -> scene.lookup("#new-name-editor")));
        assertFalse(fx(() -> listed(unrelated)));
        Path old = Files.move(file, folder.resolve("old-object.txt"));
        Path temp = Files.copy(old, folder.resolve("temporary.txt"), StandardCopyOption.COPY_ATTRIBUTES);
        Files.move(temp, file);
        fx(() -> {
            assertEquals("never-rename-the-replacement.txt", editor.getText());
            Event.fireEvent(editor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        acknowledgeFileError();
        await(() -> listed(unrelated) && browser.selection().isEmpty() && idle(), "replacement has no inherited state", 10);
        assertFalse(Files.exists(folder.resolve("never-rename-the-replacement.txt")));
        assertEquals("second and updated contents", Files.readString(file));
        assertEquals("second and updated contents", Files.readString(old));

        select(file);
        await(this::hasSelectionProof, "new deliberate selection", 8);
        Files.delete(external);
        await(() -> !listed(external) && selectedPaths().equals(List.of(file)), "deletion and preserved selection", 8);
        Path replacement = Files.copy(file, folder.resolve("editor-save.tmp"), StandardCopyOption.COPY_ATTRIBUTES);
        Files.move(replacement, file, StandardCopyOption.REPLACE_EXISTING);
        await(() -> idle() && browser.selection().isEmpty() && previewText().isEmpty(), "editor-style replacement", 10);

        for (int i = 0; i < 130; i++) { Files.writeString(folder.resolve("visible-%03d.txt".formatted(i)), "row"); }
        await(() -> listed(folder.resolve("visible-129.txt")) && idle(), "additional rows", 10);
        fx(() -> {
            var filters = (ColumnFilters) field(browser, "filters");
            filters.select(BrowserView.Field.TYPE, new ColumnFilters.Bucket(0, "TXT 文件"), true);
            call(browser, "applyFilters");
            var pane = activePane();
            var sort = pane.getClass().getDeclaredMethod("sort", BrowserView.Field.class, boolean.class);
            sort.setAccessible(true);
            sort.invoke(pane, BrowserView.Field.NAME, true);
            var group = pane.getClass().getDeclaredMethod("group", BrowserView.Field.class);
            group.setAccessible(true);
            group.invoke(pane, BrowserView.Field.TYPE);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            entriesFlow().scrollToTop(55);
            scene.getRoot().layout();
            return null;
        });
        await(() -> entriesFlow().getFirstVisibleCell().getIndex() >= 50, "scroll away from first rows", 8);
        int firstRow = fx(() -> entriesFlow().getFirstVisibleCell().getIndex());
        Path above = Files.writeString(folder.resolve("zz-above.txt"), "row inserted above");
        Path filtered = Files.writeString(folder.resolve("zz-hidden.bin"), "hidden by type");
        await(() -> listed(above) && idle(), "refresh while scrolled, filtered and grouped", 10);
        assertTrue(fx(() -> browser.filtersActive() && browser.items().getFirst().path().equals(above) && !listed(filtered)));
        assertTrue(Math.abs(fx(() -> entriesFlow().getFirstVisibleCell().getIndex()) - firstRow) <= 2);
    }

    @Test
    void automaticRefreshCatchesRegistrationAndReadingGapsWithoutSupersedingNavigation() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null, "requires Windows x64 local NTFS");
        Path first = Files.createDirectory(root.resolve("first"));
        Path second = Files.createDirectory(root.resolve("second"));
        openWindow(true);
        var registration = new Gate();
        heldRead.set(new ReadGate(first, false, registration));
        Path initial;
        try {
            enter(first);
            assertTrue(registration.entered.await(8, TimeUnit.SECONDS));
            initial = Files.writeString(first.resolve("before-watch.txt"), "written after enumeration");
        } finally {
            registration.release.countDown();
        }
        await(() -> browseAt(first) && listed(initial) && watching(), "registration catch-up on disk", 10);

        var reading = new Gate();
        heldRead.set(new ReadGate(first, false, reading));
        Path additional;
        try {
            Files.writeString(first.resolve("first-event.txt"), "before the automatic read");
            assertTrue(reading.entered.await(8, TimeUnit.SECONDS));
            var inFlight = fx(() -> field(currentBrowse(), "browseRequest"));
            additional = Files.writeString(first.resolve("another-event.txt"), "during the automatic read");
            await(() -> field(currentBrowse(), "pendingChanges") != null, "new invalidation queued during read", 8);
            assertSame(inFlight, fx(() -> field(currentBrowse(), "browseRequest")));
        } finally {
            reading.release.countDown();
        }
        await(() -> browseAt(first) && listed(additional), "second read observes later change", 10);

        var staleWatch = fx(() -> (DirectoryWatcher.Target) field(currentBrowse(), "watchTarget"));
        var obsolete = new Gate();
        heldRead.set(new ReadGate(first, false, obsolete));
        try {
            signal(staleWatch, DirectoryWatcher.Status.ACTIVE, true);
            assertTrue(obsolete.entered.await(8, TimeUnit.SECONDS));
            goWatched(second);
            int committed = browseCommits.get();
            signal(staleWatch, DirectoryWatcher.Status.ACTIVE, true);
            obsolete.release.countDown();
            assertTrue(obsolete.returned.await(8, TimeUnit.SECONDS));
            assertTrue(fx(() -> browseAt(second) && !listed(additional)));
            assertEquals(committed, browseCommits.get());
        } finally {
            obsolete.release.countDown();
        }

        Path moved = Files.move(second, root.resolve("moved"));
        await(() -> watchText().contains("等待目录恢复") && status().contains("无法访问") && idle(),
                "silent status on moved directory", 10);
        assertTrue(fx(() -> browser.items().isEmpty() && !button("back").isDisabled()
                && !button("up").isDisabled() && errorDialog() == null));
        Files.createDirectory(second);
        Path replacement = Files.writeString(second.resolve("recovered.txt"), "the new directory");
        await(() -> browseAt(second) && watching() && listed(replacement), "automatic recovery", 10);
        Files.delete(replacement);
        Files.delete(second);
        await(() -> watchText().contains("等待目录恢复") && status().contains("无法访问"), "deleted directory", 10);
        Files.move(moved, second);
        await(() -> browseAt(second) && watching() && status().contains("0 个项目"), "directory restored", 10);

        var current = fx(() -> (DirectoryWatcher.Target) field(currentBrowse(), "watchTarget"));
        var watcher = (DirectoryWatcher) field(pane(false), "watcher");
        watcher.pause();
        await(() -> watcher.registrationCount() == 0, "pause native delivery for injected overflow", 8);
        Path missed = Files.writeString(second.resolve("lost-event.txt"), "only visible after a full reread");
        assertFalse(fx(() -> listed(missed)));
        signal(current, DirectoryWatcher.Status.ACTIVE, true);
        await(() -> browseAt(second) && listed(missed), "full invalidation recovers missing entry events", 10);

        signal(current, DirectoryWatcher.Status.UNAVAILABLE, false);
        assertTrue(fx(() -> watchText().contains("监听不可用") && !button("refresh").isDisabled()));
        toggleAutoRefresh(false);
        Path manual = Files.writeString(second.resolve("manual.txt"), "manual fallback");
        fx(() -> {
            scene.getAccelerators().get(new KeyCodeCombination(KeyCode.F5)).run();
            return null;
        });
        await(() -> browseAt(second) && listed(manual), "manual F5 fallback", 10);
        fx(() -> { app.stop(); stage.hide(); return null; });
        signal(current, DirectoryWatcher.Status.ACTIVE, true);
        assertTrue(fx(() -> watchText().contains("已关闭")));
    }

    @Test
    void automaticRefreshDuringCopyFiltersActiveStagingAndKeepsFileReceipts() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null, "requires Windows x64 local NTFS");
        Path from = Files.createDirectory(root.resolve("source"));
        Path to = Files.createDirectory(root.resolve("target"));
        Path source = Files.writeString(from.resolve("published.txt"), "contents from the source");
        Path prior = Files.writeString(to.resolve("original-name.txt"), "independent undo");
        openWindow(true);
        goWatched(to);
        select(prior);
        click("rename");
        await(() -> scene.lookup("#new-name-editor") != null, "rename before unrelated refresh", 10);
        fx(() -> {
            var edit = (TextField) scene.lookup("#new-name-editor");
            edit.setText("renamed.txt");
            Event.fireEvent(edit, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            return null;
        });
        Path renamed = to.resolve("renamed.txt");
        await(() -> idle() && listed(renamed), "rename receipt", 10);
        Object undo = fx(() -> field(app, "undo"));
        fx(() -> {
            int priorCommits = browseCommits.get();
            var target = (DirectoryWatcher.Target) field(currentBrowse(), "watchTarget");
            long read = (Long) field(currentBrowse(), "listingQueuedAt");
            deliver(target, DirectoryWatcher.Status.ACTIVE, new DirectoryEvents.Batch(Set.of(renamed), false, read - 1));
            assertNull(field(currentBrowse(), "browseRequest"));
            assertEquals(priorCommits, browseCommits.get());
            return null;
        });

        goWatched(from);
        select(source);
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(source)), "captured copy credentials", 8);
        Object clipboard = fx(() -> field(app, "transfer"));
        goWatched(to);
        var publish = new Gate();
        heldPublish.set(publish);
        Path external;
        try {
            click("paste");
            assertTrue(publish.entered.await(10, TimeUnit.SECONDS));
            List<Path> active;
            try (var paths = Files.list(to)) {
                active = paths.filter(((ActiveStaging) field(app, "staging"))::contains).toList();
            }
            assertEquals(1, active.size());
            external = Files.writeString(to.resolve(active.getFirst().getFileName() + "-external"), "not our staging file");
            await(() -> listed(external), "outside change while copy is held", 10);
            assertFalse(fx(() -> listed(active.getFirst()) || listed(to.resolve("published.txt"))));
            assertSame(clipboard, fx(() -> field(app, "transfer")));
            assertSame(undo, fx(() -> field(app, "undo")));
        } finally {
            publish.release.countDown();
        }
        Path published = to.resolve("published.txt");
        await(() -> idle() && listed(published), "publication receipt and watch converge", 10);
        assertEquals("contents from the source", Files.readString(published));
        assertEquals("not our staging file", Files.readString(external));
        try (var paths = Files.list(to)) { assertEquals(Set.of(published, external, renamed), Set.copyOf(paths.toList())); }
        assertEquals(List.of(source), fx(this::cutSources));
    }

    @Test
    void clipboardCutMarksRequireTheOriginalObjectAndFollowClipboardChanges() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        var clipboard = new TestClipboard();
        Path from = Files.createDirectory(root.resolve("source"));
        Path to = Files.createDirectory(root.resolve("target"));
        Path file = Files.writeString(from.resolve("a-cut-file.txt"), "original");
        Files.writeString(from.resolve("b-normal-file.txt"), "normal");
        openWindow(true, clipboard);
        goWatched(from);
        select(file);
        fx(() -> {
            var table = scene.lookup("#entries");
            table.requestFocus();
            shortcut(table, KeyCode.X, false);
            return null;
        });
        await(() -> idle() && hasCutMark(file), "verified cut mark", 12);
        saveCutCapture();

        long changeStarted = System.nanoTime();
        Path unrelated = Files.writeString(from.resolve("c-external.txt"), "external");
        await(() -> listed(unrelated) && hasCutMark(file), "cut survives a real refresh", 10);
        System.out.printf(java.util.Locale.ROOT, "PHASE26_GUI disk-change-to-cut-mark %.2f ms%n",
                (System.nanoTime() - changeStarted) / 1e6);
        Path original = Files.move(file, from.resolve("kept-original.txt"));
        Files.copy(original, file, StandardCopyOption.COPY_ATTRIBUTES);
        await(() -> idle() && listed(original) && !hasCutMark(file), "same-name replacement loses cut mark", 10);
        goWatched(to);
        click("paste");
        acknowledgeFileError();
        assertFalse(Files.exists(to.resolve(file.getFileName())));
        assertEquals("original", Files.readString(file));
        assertEquals("original", Files.readString(original));

        clipboard.text("text outside the program");
        await(() -> idle() && cutSources().isEmpty() && button("paste").isDisabled(), "text revokes local transfer", 10);
        goWatched(from);
        select(file);
        AtomicInteger delivered = new AtomicInteger();
        fx(() -> {
            TextField search = (TextField) scene.lookup("#search-field");
            search.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                delivered.incrementAndGet();
                event.consume(); // avoid materializing the real system clipboard in this fixture
            });
            for (KeyCode key : List.of(KeyCode.C, KeyCode.X, KeyCode.V)) { shortcut(search, key, false); }
            return null;
        });
        assertEquals(3, delivered.get());
        assertEquals("text outside the program", clipboard.textValue());
        context(file, "context-copy-path");
        await(() -> ("\"" + file + "\"").equals(clipboard.textValue()) && idle(), "quoted path text", 10);
        fx(() -> { browser.selectAll(); return null; });
        context(file, "context-copy-path");
        await(() -> clipboard.textValue().contains(System.lineSeparator())
                && clipboard.textValue().contains("\"" + original + "\""), "multiple quoted paths", 10);
    }

    @Test
    void externalFilesCopyAndRepeatWhileExternalCutKeepsTheSource() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        var clipboard = new TestClipboard();
        Path from = Files.createDirectory(root.resolve("external-source"));
        Path to = Files.createDirectory(root.resolve("target"));
        Path again = Files.createDirectory(root.resolve("repeat"));
        Path childTarget = Files.createDirectory(to.resolve("folder paste"));
        Path file = Files.writeString(from.resolve("有 空格.txt"), "external file");
        Path folder = Files.createDirectory(from.resolve("资料夹"));
        Files.writeString(folder.resolve("child.txt"), "external folder");
        openWindow(true, clipboard);
        goWatched(to);
        long changedAt = System.nanoTime();
        var version = clipboard.external(FileClipboard.Kind.FILES, List.of(file, folder), false);
        await(() -> !button("paste").isDisabled(), "external files enable paste", 10);
        System.out.printf(java.util.Locale.ROOT, "PHASE26_GUI clipboard-change-to-paste-enabled %.2f ms%n",
                (System.nanoTime() - changedAt) / 1e6);
        select(childTarget);
        context(childTarget, "context-paste-folder");
        await(() -> idle() && Files.exists(childTarget.resolve("资料夹/child.txt")), "copy into context folder", 15);
        assertEquals("external file", Files.readString(childTarget.resolve(file.getFileName())));
        assertEquals("external folder", Files.readString(childTarget.resolve("资料夹/child.txt")));
        goWatched(again);
        fx(() -> { browser.requestFocus(); shortcut(browser, KeyCode.V, false); return null; });
        await(() -> idle() && listed(again.resolve(file.getFileName())), "repeat external copy", 12);
        assertEquals(version, clipboard.probe());

        click("paste");
        await(() -> conflictScene() != null, "repeated external paste conflicts", 10);
        chooseConflict("conflict-skip");
        // File/file and directory/directory are separate categories.
        await(() -> conflictScene() != null && progressLabel("operation-file").equals(folder.getFileName().toString()),
                "folder conflict", 10);
        chooseConflict("conflict-skip");
        await(() -> idle() && status().contains("已跳过 2 项"), "external paste summary", 10);
        assertEquals("external file", Files.readString(again.resolve(file.getFileName())));
        assertEquals("external folder", Files.readString(again.resolve("资料夹/child.txt")));
        var cutVersion = clipboard.external(FileClipboard.Kind.FILES, List.of(file), true);
        goWatched(to);
        click("paste");
        await(() -> idle() && button("paste").isDisabled() && dialog("无法粘贴外部剪切") != null,
                "external cut shows a refusal dialog", 10);
        fx(() -> {
            var refusal = dialog("无法粘贴外部剪切");
            assertTrue(refusal.getContentText().contains("「复制」"));
            assertEquals("外部剪切请改用复制", ((Label) scene.lookup("#clipboard-status")).getText());
            ((Button) refusal.lookupButton(ButtonType.OK)).fire();
            return null;
        });
        assertFalse(Files.exists(to.resolve(file.getFileName())));
        assertEquals("external file", Files.readString(file));
        assertEquals(cutVersion, clipboard.probe());
        assertEquals(0, clipboard.replaceAttempts());

        clipboard.external(FileClipboard.Kind.UNSUPPORTED, List.of(), false);
        await(() -> button("paste").isDisabled()
                && ((Label) scene.lookup("#clipboard-status")).getText().contains("格式"), "virtual format refused", 10);
    }

    @Test
    void completingAMoveCannotReplaceAnotherClipboardOwner() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        var clipboard = new TestClipboard();
        Path from = Files.createDirectory(root.resolve("source"));
        Path to = Files.createDirectory(root.resolve("target"));
        Path file = Files.writeString(from.resolve("moving.txt"), "move payload");
        openWindow(false, clipboard);
        go(from);
        select(file);
        click("cut");
        await(() -> idle() && cutSources().equals(List.of(file)), "local cut pending", 8);
        go(to);
        fx(() -> { ((javafx.animation.Timeline) field(app, "clipboardTimer")).stop(); return null; });
        var gate = new Gate();
        heldMove.set(gate);
        try {
            click("paste");
            assertTrue(gate.entered.await(8, TimeUnit.SECONDS));
            clipboard.text("new owner");
        } finally {
            gate.release.countDown();
        }
        await(() -> idle() && listed(to.resolve(file.getFileName())), "moved with old request", 10);
        assertEquals("new owner", clipboard.textValue());
        assertEquals(1, clipboard.replaceAttempts());
        assertTrue(fx(() -> cutSources().isEmpty() && button("paste").isDisabled()));
        assertFalse(Files.exists(file));
    }

    @Test
    void conflictChoicesAllowBrowsingAndApplyOnlyToMatchingLaterItems() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path from = Files.createDirectory(root.resolve("待归档"));
        Path to = Files.createDirectory(root.resolve("项目归档"));
        Path elsewhere = Files.createDirectory(root.resolve("旁边的目录"));
        Path report = Files.writeString(from.resolve("01 工作报告.txt"), "工作报告的新内容");
        Path budget = Files.writeString(from.resolve("02 费用清单.txt"), "费用明细");
        Path list = Files.writeString(from.resolve("03 项目列表.txt"), "项目列表");
        Path oldReport = Files.writeString(to.resolve(report.getFileName()), "工作报告的旧内容");
        Path oldBudget = Files.writeString(to.resolve(budget.getFileName()), "费用清单的旧内容");
        Path oldList = Files.createDirectory(to.resolve(list.getFileName()));
        openWindow(true, new TestClipboard());
        go(from);
        fx(() -> { browser.selectAll(); return null; });
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(report, budget, list)), "copy request captured", 8);
        goWatched(to);
        long started = System.nanoTime();
        click("paste");
        await(() -> conflictScene() != null && progressLabel("operation-stage").equals("正在等待冲突处理"),
                "visible nonmodal decision", 10);
        double dialogMs = (System.nanoTime() - started) / 1e6;
        captureConflict("conflict-dialog.png", this::conflictScene);
        fx(() -> {
            assertEquals(javafx.stage.Modality.NONE, ((Stage) conflictScene().getWindow()).getModality());
            assertFalse(button("edit-address").isDisabled());
            progressScene().getWindow().hide();
            button("file-operations").fire();
            assertNotNull(conflictScene());
            return null;
        });

        goWatched(elsewhere);
        Path created = Files.writeString(elsewhere.resolve("外部新文件.txt"), "external change");
        await(() -> listed(created) && conflictScene() != null, "watch refresh during conflict", 10);
        goWatched(to);
        Path newReport = to.resolve("01 工作报告 - 副本.txt");
        Path newBudget = to.resolve("02 费用清单 - 副本.txt");
        fx(() -> {
            ((CheckBox) conflictScene().lookup("#conflict-apply")).setSelected(true);
            ((Button) conflictScene().lookup("#conflict-keep")).fire();
            return null;
        });
        await(() -> conflictScene() != null && progressLabel("operation-file").equals(list.getFileName().toString())
                && Files.exists(newReport) && Files.exists(newBudget), "next conflict with different types", 10);
        chooseConflict("conflict-skip");
        await(() -> idle() && listed(newReport) && listed(newBudget) && status().contains("已复制 2 项 · 已跳过 1 项"),
                "actual copies and skipped summary", 10);
        captureConflict("conflict-result.png", this::progressScene);
        assertTrue(fx(() -> conflictScene() == null && errorDialog() == null));
        assertTrue(fx(() -> progressLabel("operation-details").contains(newBudget.toString())));
        assertEquals(List.of(report, budget, list), fx(this::cutSources));
        assertEquals(Files.readString(report), Files.readString(newReport));
        assertEquals(Files.readString(budget), Files.readString(newBudget));
        assertEquals("工作报告的旧内容", Files.readString(oldReport));
        assertEquals("费用清单的旧内容", Files.readString(oldBudget));
        assertTrue(Files.isDirectory(oldList));
        assertFalse(Files.exists(to.resolve("03 项目列表 - 副本.txt")));
        System.out.printf("PHASE27_GUI paste to conflict %.2f ms%n", dialogMs);
    }

    @Test
    void closingConflictOrMainWindowCancelsTheWaitingOperation() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        Path source = Files.createDirectory(root.resolve("source"));
        Path target = Files.createDirectory(root.resolve("target"));
        Path file = Files.writeString(source.resolve("already.txt"), "source");
        Path old = Files.writeString(target.resolve("already.txt"), "occupant");
        Path later = Files.writeString(source.resolve("later.txt"), "later");
        openWindow(false, new TestClipboard());
        go(source);
        fx(() -> { browser.selectAll(); return null; });
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(file, later)), "copy request captured", 8);
        go(target);
        click("paste");
        await(() -> conflictScene() != null, "conflict before closing dialog", 10);
        long start = System.nanoTime();
        fx(() -> {
            Window window = conflictScene().getWindow();
            Event.fireEvent(window, new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST));
            return null;
        });
        await(() -> idle() && conflictScene() == null && progressLabel("operation-stage").equals("已取消"),
                "dialog close acknowledgement", 10);
        System.out.printf("PHASE27_GUI conflict close to final result %.2f ms%n", (System.nanoTime() - start) / 1e6);
        assertTrue(fx(() -> progressLabel("operation-details").contains("未执行 1 项 · 已取消 1 项")));

        click("paste");
        await(() -> conflictScene() != null, "conflict before closing main", 10);
        fx(() -> {
            Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
            assertTrue(stage.isShowing(), "the window remains open until the writing task settles");
            return null;
        });
        await(() -> idle() && conflictScene() == null && progressLabel("operation-stage").equals("已取消"),
                "main close cancels pending decision", 10);

        click("paste");
        await(() -> conflictScene() != null, "conflict before application stop", 10);
        var writer = fx(() -> {
            app.stop();
            stage.hide();
            assertNull(conflictScene());
            return (ExecutorService) field(app, "writes");
        });
        assertTrue(writer.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals("source", Files.readString(file));
        assertEquals("occupant", Files.readString(old));
        assertEquals("later", Files.readString(later));
        try (var paths = Files.list(target)) { assertEquals(List.of(old), paths.toList()); }
    }

    @Test
    void replacingTheClipboardDuringAConflictDoesNotRevokeTheRunningMoveOrNewOwner() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        var clipboard = new TestClipboard();
        Path from = Files.createDirectory(root.resolve("source"));
        Path to = Files.createDirectory(root.resolve("target"));
        Path file = Files.writeString(from.resolve("moving.txt"), "move payload");
        Path old = Files.writeString(to.resolve("moving.txt"), "original occupant");
        Path published = to.resolve("moving - 副本.txt");
        openWindow(false, clipboard);
        go(from);
        select(file);
        click("cut");
        await(() -> idle() && cutSources().equals(List.of(file)), "captured cut", 8);
        go(to);
        click("paste");
        await(() -> conflictScene() != null, "move waiting", 10);
        var newOwner = clipboard.text("new owner during conflict");
        await(() -> newOwner.equals(field(app, "clipboardState")) && conflictScene() != null && cutSources().isEmpty(),
                "new clipboard owner recognized while the accepted request waits", 10);
        chooseConflict("conflict-keep");
        await(() -> idle() && listed(published), "move published at chosen sibling", 10);
        assertEquals(newOwner, clipboard.probe());
        assertEquals("new owner during conflict", clipboard.textValue());
        assertEquals(0, clipboard.replaceAttempts());
        assertTrue(fx(() -> cutSources().isEmpty() && button("paste").isDisabled()));
        assertFalse(Files.exists(file));
        Files.writeString(published, "new edit after move");
        menu("moreItems", "undo");
        await(() -> idle() && !listed(published), "undo the actual move destination", 10);
        assertEquals("new edit after move", Files.readString(file));
        assertEquals("original occupant", Files.readString(old));
    }

    @Test
    void readsFailClosedAndCannotUseAReplacedPasteTarget() throws Exception {
        assumeTrue(NtfsFileOperations.captureDirectory(root) != null);
        var clipboard = new TestClipboard();
        Path from = Files.createDirectory(root.resolve("source"));
        Path to = Files.createDirectory(root.resolve("target"));
        Path file = Files.writeString(from.resolve("copy.txt"), "copy payload");
        openWindow(false, clipboard);
        go(from);
        select(file);
        click("copy");
        await(() -> idle() && cutSources().equals(List.of(file)), "original copy", 8);
        go(to);
        clipboard.nextRead.set(() -> { throw new IOException("剪贴板暂被占用"); });
        click("paste");
        await(() -> idle() && status().contains("暂被占用"), "clipboard failure", 10);
        assertFalse(Files.exists(to.resolve("copy.txt")));
        await(() -> !button("paste").isDisabled(), "retry after clipboard unlock", 10);

        var gate = new Gate();
        clipboard.nextRead.set(() -> {
            gate.hold();
            return clipboard.snapshot();
        });
        try {
            click("paste");
            assertTrue(gate.entered.await(8, TimeUnit.SECONDS));
            Path displaced = Files.move(to, root.resolve("original-directory"));
            Files.createDirectory(to);
            gate.release.countDown();
            acknowledgeFileError();
            assertFalse(Files.exists(displaced.resolve("copy.txt")));
            assertFalse(Files.exists(to.resolve("copy.txt")));
            assertEquals("copy payload", Files.readString(file));
        } finally {
            gate.release.countDown();
        }

        go(to);
        Path original = Files.move(file, from.resolve("kept-copy.txt"));
        Files.writeString(file, "substitute");
        click("paste");
        acknowledgeFileError();
        assertFalse(Files.exists(to.resolve("copy.txt")));
        assertEquals("copy payload", Files.readString(original));
        var newVersion = clipboard.external(FileClipboard.Kind.FILES, List.of(file), false);
        await(() -> field(app, "clipboardState").equals(newVersion), "new clipboard with identical path", 8);
        click("paste");
        await(() -> idle() && listed(to.resolve("copy.txt")), "new version captures the new object", 10);
        assertEquals("substitute", Files.readString(to.resolve("copy.txt")));
    }

    private boolean hasCutMark(Path path) throws Exception {
        return ((Set<?>) field(browser, "cutItems")).stream()
                .filter(BrowserItem.class::isInstance).map(BrowserItem.class::cast)
                .anyMatch(item -> path.equals(item.path()));
    }

    private void saveCutCapture() throws Exception {
        if (!Boolean.getBoolean("javafx.captureClipboard")) { return; }
        var output = Files.createTempDirectory("phase26-fx-");
        var image = fx(() -> {
            var node = ((javafx.scene.control.SplitPane) scene.lookup("#workspace-split")).getItems().getLast();
            var bounds = node.getLayoutBounds();
            var crop = new SnapshotParameters();
            crop.setViewport(new Rectangle2D(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), 130));
            return node.snapshot(crop, null);
        });
        var picture = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        copyPixels(image, picture, 0);
        var png = output.resolve("cut-mark.png");
        assertTrue(ImageIO.write(picture, "png", png.toFile()));
        System.out.println("PHASE26_SCREENSHOT " + png);
    }

    private static void shortcut(javafx.scene.Node owner, KeyCode key, boolean shift) {
        Event.fireEvent(owner, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, shift, true, false, false));
    }

    private void context(Path path, String id) throws Exception {
        fx(() -> {
            var clicked = browser.items().stream().filter(item -> path.equals(item.path())).findFirst().orElseThrow();
            Object pane = activePane();
            var build = FileManagerApp.class.getDeclaredMethod("contextMenu", pane.getClass(), BrowserItem.class);
            build.setAccessible(true);
            var items = ((ContextMenu) build.invoke(app, pane, clicked)).getItems();
            var command = items.stream().filter(item -> id.equals(item.getId())).findFirst().orElseThrow();
            assertFalse(command.isDisable(), id);
            command.fire();
            return null;
        });
    }

    private FileOperationTask operation(FileOperationTask.Kind kind, List<FileChanges.Change> changes) {
        var dragSample = timedDragTask.getAndSet(null);
        if (dragSample != null) { dragSample.complete(System.nanoTime()); }
        Gate move = kind == FileOperationTask.Kind.MOVE ? heldMove.getAndSet(null) : null;
        if (move != null) {
            var original = changes.getFirst();
            var blocked = new FileChanges.Change(original.path(), original.folders(), original.evidence(), original.copyBytes(), task -> {
                move.hold();
                return original.action().run(task);
            });
            return new FileOperationTask(kind, List.of(blocked));
        }
        boolean loseReceipt = switch (kind) {
            case MOVE -> loseMoveReceipt.getAndSet(false);
            case UNDO -> loseUndoReceipt.getAndSet(false);
            default -> false;
        };
        if (loseReceipt) {
            var change = changes.getFirst();
            var uncertain = new FileChanges.Change(change.path(), change.folders(), change.evidence(), change.copyBytes(), task -> {
                var effect = change.action().run(task);
                assertEquals(FileChanges.State.APPLIED, effect.state());
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
        await(() -> field(currentBrowse(), "browseRequest") == null && listed(foreign), "foreign staging prefix remains visible", 10);
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
        await(() -> browseAt(browse) && "阶段验证".equals(field(currentBrowse(), "activeQuery")) && listed(readable),
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
        await(() -> browseAt(browse) && "".equals(field(currentBrowse(), "activeQuery")) && listed(readable),
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
            assertEquals(origin, fx(() -> call(((java.util.Deque<?>) field(currentBrowse(), "past")).peek(), "path")));
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

        int history = fx(() -> ((java.util.Deque<?>) field(currentBrowse(), "past")).size());
        enter(root.resolve("absent-directory"));
        acknowledgeFileError();
        assertTrue(fx(() -> browseAt(next) && listed(second)));
        assertEquals(history, fx(() -> ((java.util.Deque<?>) field(currentBrowse(), "past")).size()));

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
                await(() -> field(app, "fileTask") == null && field(currentBrowse(), "browseRequest") != null,
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
            fireTheme("theme-dark");
            button("unknown-results").fire();
            return null;
        });
        await(() -> unknownDialog() != null, "read-only findings", 12);
        fx(() -> {
            var pane = unknownDialog();
            assertBackground(pane, "#181E28");
            @SuppressWarnings("unchecked")
            var paths = (ComboBox<Path>) pane.lookup("#unknown-path");
            assertEquals(original, paths.getValue());
            paths.show();
            try {
                Region choices = Window.getWindows().stream().filter(PopupWindow.class::isInstance)
                        .map(window -> window.getScene().lookup(".list-view"))
                        .filter(Region.class::isInstance).map(Region.class::cast)
                        .findFirst().orElseThrow();
                assertBackground(choices, "#181E28");
            } finally {
                paths.hide();
            }
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

    private Scene conflictScene() {
        return Window.getWindows().stream().map(Window::getScene)
                .filter(scene -> scene != null && "file-conflict".equals(scene.getRoot().getId()))
                .findFirst().orElse(null);
    }

    private void chooseConflict(String id) throws Exception {
        fx(() -> {
            var button = (Button) conflictScene().lookup("#" + id);
            assertFalse(button.isDisabled(), id);
            button.fire();
            return null;
        });
    }

    private void captureConflict(String name, Callable<Scene> window) throws Exception {
        if (!Boolean.getBoolean("javafx.captureConflicts")) { return; }
        if (captures == null) { captures = Files.createTempDirectory("phase27-fx-"); }
        var snapshot = fx(() -> window.call().snapshot(null));
        var picture = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        copyPixels(snapshot, picture, 0);
        Path png = captures.resolve(name);
        assertTrue(ImageIO.write(picture, "png", png.toFile()));
        System.out.println("PHASE27_SCREENSHOT " + png);
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

    private javafx.scene.image.Image captureAutomaticPane() throws Exception {
        if (!Boolean.getBoolean("javafx.captureDirectoryWatch")) { return null; }
        return fx(() -> {
            var node = ((javafx.scene.control.SplitPane) scene.lookup("#workspace-split")).getItems().getLast();
            var bounds = node.getLayoutBounds();
            var crop = new SnapshotParameters();
            crop.setViewport(new Rectangle2D(bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), 170));
            return node.snapshot(crop, null);
        });
    }

    private void saveAutomaticCapture(javafx.scene.image.Image before, javafx.scene.image.Image after) throws IOException {
        if (before == null) { return; }
        Path output = Files.createTempDirectory("phase25-fx-images-");
        int width = (int) before.getWidth();
        int height = (int) before.getHeight();
        var result = new BufferedImage(width * 2, height, BufferedImage.TYPE_INT_ARGB);
        copyPixels(before, result, 0);
        copyPixels(after, result, width);
        Path png = output.resolve("external-change.png");
        assertTrue(ImageIO.write(result, "png", png.toFile()));
        System.out.println("PHASE25_SCREENSHOT " + png);
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
        await(() -> conflictScene() != null && progressLabel("operation-file").equals("b.txt"), "conflict dialog", 12);
        chooseConflict("conflict-skip");
        Path published = to.resolve("a.txt");
        await(() -> idle() && status().contains("已移动 2 项 · 已跳过 1 项"), "partial write summary", 12);
        assertEquals(List.of("a.txt", "b.txt", "c.txt"), fx(() -> browser.items().stream().map(BrowserItem::name).toList()));
        assertEquals(List.of(second), fx(this::cutSources));
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(third));
        assertEquals("third", Files.readString(to.resolve("c.txt")));
        assertEquals("occupant", Files.readString(to.resolve("b.txt")));

        Files.writeString(published, "new bytes after moving");
        menu("moreItems", "undo");
        await(() -> idle() && !listed(published) && !listed(to.resolve("c.txt")) && status().contains("已撤消 2 项"),
                "partial batch undo", 10);
        assertEquals("new bytes after moving", Files.readString(first));
        assertEquals("second", Files.readString(second));
        assertEquals("third", Files.readString(third));
        assertEquals(List.of(second), fx(this::cutSources));
        assertEquals("occupant", Files.readString(to.resolve("b.txt")));
    }

    private void go(Path directory) throws Exception {
        enter(directory);
        await(() -> browseAt(directory), "directory " + directory, 12);
    }

    private void goWatched(Path directory) throws Exception {
        int prior = browseCommits.get();
        enter(directory);
        await(() -> browseAt(directory) && watching() && browseCommits.get() >= prior + 2,
                "registered directory and catch-up snapshot", 12);
    }

    private String watchText() { return ((Label) scene.lookup("#watch-status")).getText(); }
    private boolean watching() { return watchText().equals("自动刷新已开启"); }
    private boolean hasSelectionProof() throws Exception { return !((Map<?, ?>) field(currentBrowse(), "selectionProof")).isEmpty(); }
    private List<Path> selectedPaths() { return browser.selection().stream().map(BrowserItem::path).toList(); }
    private VirtualFlow<?> entriesFlow() { return (VirtualFlow<?>) scene.lookup("#entries").lookup(".virtual-flow"); }

    private void signal(DirectoryWatcher.Target target, DirectoryWatcher.Status status, boolean full) throws Exception {
        fx(() -> {
            var batch = full ? new DirectoryEvents.Batch(Set.of(), true, System.nanoTime()) : null;
            deliver(target, status, batch);
            return null;
        });
    }

    private void deliver(DirectoryWatcher.Target target, DirectoryWatcher.Status status, DirectoryEvents.Batch batch)
            throws Exception {
        var notify = FileManagerApp.class.getDeclaredMethod("directoryChanged", DirectoryWatcher.Notice.class);
        notify.setAccessible(true);
        notify.invoke(app, new DirectoryWatcher.Notice(target, status, batch));
    }

    private void toggleAutoRefresh(boolean enabled) throws Exception {
        fx(() -> {
            for (Object item : (List<?>) call(activePane(), "viewItems")) {
                if (item instanceof CheckMenuItem entry && "auto-refresh".equals(entry.getId())) {
                    entry.setSelected(enabled);
                    entry.fire();
                    return null;
                }
            }
            return fail("Auto-refresh setting not found");
        });
    }

    private boolean browseAt(Path directory) throws Exception {
        return field(currentBrowse(), "browseRequest") == null && directory.equals(call(field(currentBrowse(), "location"), "path"));
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
            Object owner = List.of("viewItems", "sortItems").contains(getter) ? activePane() : app;
            for (Object item : (List<?>) call(owner, getter)) {
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
        return dialog("文件操作");
    }

    private DialogPane dialog(String title) {
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window instanceof Stage dialog && dialog.getOwner() == stage && title.equals(dialog.getTitle())
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
        return field(app, "fileTask") == null && field(currentBrowse(), "browseRequest") == null
                && field(app, "clipboardCommand") == null;
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

    private BrowseContext currentBrowse() throws Exception { return (BrowseContext) field(activePane(), "context"); }

    private Object activePane() throws Exception { return field(app, "activePane"); }

    private Object pane(boolean right) throws Exception { return field(app, right ? "rightPane" : "leftPane"); }

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

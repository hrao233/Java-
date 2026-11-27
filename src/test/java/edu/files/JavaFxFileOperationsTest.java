package edu.files;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.geometry.Rectangle2D;
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
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final AtomicReference<Gate> heldMove = new AtomicReference<>();
    private final AtomicInteger browseCommits = new AtomicInteger();
    private Path captures;

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
            var watcher = (DirectoryWatcher) field(app, "watcher");
            assertTrue(watcher.awaitClosed(Duration.ofSeconds(4)));
            assertEquals(0, watcher.registrationCount());
            var reader = (ExecutorService) field(app, "clipboardWorker");
            assertTrue(reader.awaitTermination(4, TimeUnit.SECONDS));
        }
    }

    private void openWindow(boolean automaticRefresh) throws Exception {
        openWindow(automaticRefresh, new MemoryClipboard(false));
    }

    private void openWindow(boolean automaticRefresh, FileClipboard clipboard) throws Exception {
        fx(() -> {
            var preferences = new MemoryPreferences(null, "");
            if (!automaticRefresh) { preferences.putBoolean("automaticRefresh", false); }
            app = new FileManagerApp(preferences, this::operation, new FileManagerApp.ReadObserver() {
                @Override
                public void ready(Path path, boolean preview) { readReady(path, preview); }

                @Override
                public void committed(Path path, long applyNanos) { browseCommits.incrementAndGet(); }
            }, clipboard);
            stage = new Stage();
            stage.setOpacity(0);
            app.start(stage);
            scene = stage.getScene();
            browser = (BrowserView) field(app, "browser");
            return null;
        });
        await(() -> idle() && field(app, "places") != null, "home page", 35);
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
        await(() -> field(app, "pendingChanges") != null, "unrelated change held during edit", 10);
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
            var sort = FileManagerApp.class.getDeclaredMethod("sort", BrowserView.Field.class, boolean.class);
            sort.setAccessible(true);
            sort.invoke(app, BrowserView.Field.NAME, true);
            var group = FileManagerApp.class.getDeclaredMethod("group", BrowserView.Field.class);
            group.setAccessible(true);
            group.invoke(app, BrowserView.Field.TYPE);
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
            var inFlight = fx(() -> field(app, "browseRequest"));
            additional = Files.writeString(first.resolve("another-event.txt"), "during the automatic read");
            await(() -> field(app, "pendingChanges") != null, "new invalidation queued during read", 8);
            assertSame(inFlight, fx(() -> field(app, "browseRequest")));
        } finally {
            reading.release.countDown();
        }
        await(() -> browseAt(first) && listed(additional), "second read observes later change", 10);

        var staleWatch = fx(() -> (DirectoryWatcher.Target) field(app, "watchTarget"));
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

        var current = fx(() -> (DirectoryWatcher.Target) field(app, "watchTarget"));
        var watcher = (DirectoryWatcher) field(app, "watcher");
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
            var target = (DirectoryWatcher.Target) field(app, "watchTarget");
            long read = (Long) field(app, "listingQueuedAt");
            deliver(target, DirectoryWatcher.Status.ACTIVE, new DirectoryEvents.Batch(Set.of(renamed), false, read - 1));
            assertNull(field(app, "browseRequest"));
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
            var build = FileManagerApp.class.getDeclaredMethod("contextMenu", BrowserItem.class);
            build.setAccessible(true);
            var items = ((ContextMenu) build.invoke(app, clicked)).getItems();
            var command = items.stream().filter(item -> id.equals(item.getId())).findFirst().orElseThrow();
            assertFalse(command.isDisable(), id);
            command.fire();
            return null;
        });
    }

    private FileOperationTask operation(FileOperationTask.Kind kind, List<FileChanges.Change> changes) {
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
    private boolean hasSelectionProof() throws Exception { return !((Map<?, ?>) field(app, "selectionProof")).isEmpty(); }
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
            for (Object item : (List<?>) call(app, "viewItems")) {
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
        return field(app, "fileTask") == null && field(app, "browseRequest") == null
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

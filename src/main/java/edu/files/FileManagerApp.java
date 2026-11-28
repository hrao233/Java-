package edu.files;

import edu.files.BrowseContext.BrowseData;
import edu.files.BrowseContext.BrowseIntent;
import edu.files.BrowseContext.BrowseRequest;
import edu.files.BrowseContext.FileRefresh;
import edu.files.BrowseContext.HomeTab;
import edu.files.BrowseContext.Layout;
import edu.files.BrowseContext.Listing;
import edu.files.BrowseContext.Location;
import edu.files.BrowseContext.Page;
import edu.files.BrowseContext.Pane;
import edu.files.BrowseContext.SelectedItem;
import edu.files.BrowseContext.View;
import edu.files.BrowserView.Field;
import edu.files.BrowserView.Mode;
import edu.files.FileChanges.Change;
import edu.files.FileChanges.Outcome;
import edu.files.FileChanges.Selection;
import edu.files.FileChanges.State;
import edu.files.FileClipboard.Snapshot;
import edu.files.FileClipboard.Transfer;
import edu.files.FileOperationTask.Kind;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.concurrent.WorkerStateEvent;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

public final class FileManagerApp extends Application {
    private static final PseudoClass NAV_DIVIDER = PseudoClass.getPseudoClass("nav-divider");
    private static final PseudoClass ACTIVE_BROWSER = PseudoClass.getPseudoClass("active-browser");
    private static final PseudoClass DROP_COPY = PseudoClass.getPseudoClass("drop-copy");
    private static final PseudoClass DROP_MOVE = PseudoClass.getPseudoClass("drop-move");
    private static final double MIN_NAV_WIDTH = 146;
    private static final double MAX_NAV_WIDTH = 480;
    private static final double DEFAULT_NAV_WIDTH = 186;
    private static final int MAX_RETAINED_SELECTION = 64;

    private record SideNode(String label, Location location, BrowserItem item) {
        boolean divider() { return location == null && item == null && (label.isEmpty() || label.startsWith("§")); }
        String displayLabel() { return label.startsWith("§") ? label.substring(1) : label; }
    }
    private record SystemCatalog(WindowsPlaces.Places places, List<NewItems.Type> newTypes) { }
    private record PasteRequest(Snapshot snapshot, Transfer local, List<Path> external) {
        boolean move() { return local != null && local.move(); }
    }
    private record PasteTarget(Path path, DirectoryIdentity knownDirectory, BrowserItem folder,
                               Selection knownFolder) { }
    private record PreparedPaste(FileChanges.Destination destination, List<Selection> sources) { }
    private record DragPress(BrowserPane pane, BrowseContext owner, long version, List<BrowserItem> items,
                             Map<BrowserItem, Selection> proofs) { }
    private record DropSite(Node mark, BrowseContext owner, long version, PasteTarget target) { }
    private record ExternalExclusion(FileClipboard.State state, Set<Path> paths) { }
    private record CreatedItem(NewItems.Created created, Selection proof) { }
    private record PreviewData(Image image, String text) { }

    @FunctionalInterface
    private interface FileAction { void run() throws IOException; }

    @FunctionalInterface
    private interface Work<T> { T read(BooleanSupplier cancelled) throws Exception; }

    @FunctionalInterface
    interface ReadObserver {
        void ready(Path path, boolean preview);
        default void started(Path path, String query, long queueNanos) { }
        default void committed(Path path, long applyNanos) { }
        default void ancestorReady(Path path) throws IOException { }
    }

    private final ExecutorService writes = Executors.newSingleThreadExecutor(work -> new Thread(work, "file-manager-write"));
    private final ReadExecutor reads = new ReadExecutor();
    private final ExecutorService clipboardWorker = Executors.newSingleThreadExecutor(work -> {
        var thread = new Thread(work, "file-manager-clipboard");
        thread.setDaemon(true);
        return thread;
    });
    private final FileClipboard clipboard;
    private final Timeline clipboardTimer = new Timeline(new KeyFrame(Duration.millis(450), event -> pollClipboard()));
    private final PauseTransition clipboardDeadline = new PauseTransition(Duration.seconds(3));
    private final ActiveStaging staging = new ActiveStaging();
    private final Preferences preferences;
    private final BiFunction<Kind, List<Change>, FileOperationTask> operationFactory;
    private final ReadObserver readObserver;
    private final Function<TransferMode, FileDrag.Intent> dragIntent;
    private DragPress dragPress;
    private FileDrag.Source dragSource;
    private Task<List<Selection>> dragSourceRead;
    private List<Selection> dragProofs = List.of();
    private String dragSourceError;
    private Dragboard dropBoard;
    private Node dropSurface;
    private FileDrag.Payload dropPayload;
    private DropSite dropSite;
    private Task<Selection> dropFolderRead;
    private Selection dropFolderProof;
    private String dropFolderError;
    private FileDrag.Intent highlightedDrop;
    private long lastDragScroll;
    private long nextTabId;
    private BrowserPane leftPane;
    private BrowserPane rightPane;
    private BrowserPane activePane;
    private boolean dualPaneOpen;
    private double browserDivider = 0.5;
    private final TabPane tabs = new TabPane();
    private final Map<BrowseContext, Tab> tabHeaders = new LinkedHashMap<>();
    private final Map<Path, FileTime> folderHistory = new LinkedHashMap<>();
    private final Map<Path, FileTime> recentFiles = new LinkedHashMap<>();
    private final Map<Path, FileTime> folderEdits = new LinkedHashMap<>();
    private final Set<Path> extraPins = new LinkedHashSet<>();
    private final Set<Path> removedPins = new LinkedHashSet<>();
    private final Set<Path> favorites = new LinkedHashSet<>();
    private final Set<Path> networkLocations = new LinkedHashSet<>();
    private final Set<Path> galleryFolders = new LinkedHashSet<>();

    private final Label status = new Label();
    private final Label clipboardStatus = new Label();
    private final Label watchStatus = new Label();
    private final Label dragStatus = new Label();
    private final Label writeNotice = new Label();
    private final Button unknownResults = new Button("核查结果");
    private final List<FileChanges.UnknownOperation> unknownOperations = new ArrayList<>();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final VBox singleNavigation = new VBox();
    private final SplitPane browsers = new SplitPane();
    private final StackPane inspectorHost = new StackPane();
    private final TreeView<SideNode> tree = new TreeView<>();
    private final SplitPane workspace = new SplitPane();
    private final Map<String, Control> controls = new LinkedHashMap<>();

    private Stage stage;
    private SystemIcons icons;
    private final Map<String, Image> thumbnails = new LinkedHashMap<>(64, 0.75f, true);
    private WindowsPlaces.Places places;
    private List<NewItems.Type> newTypes = List.of();
    private TreeItem<SideNode> quickAccessStart;
    private TreeItem<SideNode> quickAccessEnd;
    private double navigationWidth;
    private List<Change> undo = List.of();
    private Transfer transfer;
    private UUID lastClipboardToken;
    private FileClipboard.State clipboardState;
    private FileClipboard.State unsupportedMove;
    private ExternalExclusion externalExclusion;
    private Task<FileClipboard.State> clipboardProbe;
    private Task<?> clipboardCommand;
    private boolean clipboardUnavailable;
    private boolean clipboardExpired;
    private FileOperationProgress operationProgress;
    private UiTheme theme = UiTheme.LIGHT;
    private FileOperationTask activeFileOperation;
    private LocalFiles.Visibility visibility;
    private Task<?> fileTask;
    private Task<SystemCatalog> catalogTask;
    private boolean closing;
    private boolean automaticRefresh;
    private final Map<TreeItem<SideNode>, Task<?>> treeReads = new HashMap<>();
    private long sidebarVersion;
    private Task<List<Path>> modifiedFoldersTask;
    private List<Path> modifiedFolders = List.of();
    private boolean modifiedFoldersRefreshQueued;
    private boolean writing;
    private boolean extensions = true;
    private boolean navigationPane = true;
    private boolean compact;
    private boolean checkboxes;

    public FileManagerApp() {
        this(Preferences.userNodeForPackage(FileManagerApp.class)
                        .node(System.getProperty("edu.files.preferencesNode", "explorer")), FileOperationTask::new,
                (path, preview) -> {}, FileClipboard.system());
    }

    FileManagerApp(Preferences preferences) {
        this(preferences, FileOperationTask::new);
    }

    FileManagerApp(Preferences preferences, BiFunction<Kind, List<Change>, FileOperationTask> operationFactory) {
        this(preferences, operationFactory, (path, preview) -> {});
    }

    FileManagerApp(Preferences preferences, BiFunction<Kind, List<Change>, FileOperationTask> operationFactory,
                   ReadObserver readObserver) {
        this(preferences, operationFactory, readObserver, new MemoryClipboard(false));
    }

    FileManagerApp(Preferences preferences, BiFunction<Kind, List<Change>, FileOperationTask> operationFactory,
                   ReadObserver readObserver, FileClipboard clipboard) {
        this(preferences, operationFactory, readObserver, clipboard, FileDrag::keyIntent);
    }

    FileManagerApp(Preferences preferences, BiFunction<Kind, List<Change>, FileOperationTask> operationFactory,
                   ReadObserver readObserver, FileClipboard clipboard, Function<TransferMode, FileDrag.Intent> dragIntent) {
        this.preferences = preferences;
        this.operationFactory = operationFactory;
        this.readObserver = readObserver;
        this.clipboard = clipboard;
        this.dragIntent = dragIntent;
    }

    @Override
    public void start(Stage window) {
        stage = window;
        automaticRefresh = preferences.getBoolean("automaticRefresh", true);
        icons = new SystemIcons(window::getOutputScaleX);
        leftPane = new BrowserPane("left-pane", "左栏");
        activePane = leftPane;
        singleNavigation.getChildren().add(leftPane.navigation);
        browsers.setId("browser-split");
        browsers.getStyleClass().add("browser-split");
        browsers.getItems().add(leftPane.content);
        HBox.setHgrow(browsers, Priority.ALWAYS);
        inspectorHost.visibleProperty().bind(inspectorHost.managedProperty());
        inspectorHost.setManaged(false);
        configureTree();
        navigationWidth = Math.clamp(layoutValue("navigationWidth", DEFAULT_NAV_WIDTH), MIN_NAV_WIDTH, MAX_NAV_WIDTH);
        navigationPane = preferences.getBoolean("navigation", true);
        workspace.setId("workspace-split");
        workspace.getStyleClass().add("workspace-split");
        workspace.getItems().add(new HBox(browsers, inspectorHost));
        SplitPane.setResizableWithParent(tree, false);
        syncNavigationPane();
        workspace.widthProperty().addListener((observable, previous, width) -> {
            if (previous.doubleValue() == 0 && navigationPane) { applyNavigationWidth(); }
        });

        BorderPane layout = new BorderPane();
        layout.setTop(new VBox(tabBar(), singleNavigation, commandBar()));
        layout.setCenter(workspace);
        Scene scene = new Scene(layout, 1200, 760);
        theme.applyTo(scene);
        stage.setScene(scene);
        operationProgress = new FileOperationProgress(stage, unknownResults, this::locateReportedPath);
        layout.setBottom(statusBar());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::tabKey);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::fileClipboardKey);
        scene.focusOwnerProperty().addListener((observable, previous, focused) -> {
            for (BrowserPane pane : visiblePanes()) {
                if (pane.contains(focused)) { activatePane(pane); break; }
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN),
                () -> activePane.address.beginEdit());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.D, KeyCombination.ALT_DOWN),
                () -> activePane.address.beginEdit());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), () -> activePane.refreshCommand());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F6), this::focusOtherPane);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN), () -> transferAcross(false));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F6, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN), () -> transferAcross(true));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                () -> {
                    if (!newTypes.isEmpty() && !activePane.hierarchy.isNavigationFocus(scene.getFocusOwner())) {
                        create(newTypes.getFirst());
                    }
                });
        stage.setTitle("本地文件管理器");
        stage.getIcons().add(new Image(getClass().getResource("application-icon.png").toExternalForm()));
        stage.setMinWidth(960);
        stage.setMinHeight(550);
        restoreWindowLayout();
        stage.focusedProperty().addListener((observable, previous, focused) -> {
            if (focused) {
                refreshModifiedFolders();
                pollClipboard();
            }
        });
        stage.setOnCloseRequest(event -> {
            if (writing) {
                event.consume();
                if (activeFileOperation != null && activeFileOperation.pendingConflict() != null) {
                    activeFileOperation.requestCancellation();
                    status.setText("正在取消剩余项目，请在任务结束后关闭");
                } else {
                    status.setText("正在处理文件，请等待操作结束");
                }
                if (activeFileOperation != null) { operationProgress.show(); }
            }
        });
        stage.show();
        stage.setMaximized(preferences.getBoolean("windowMaximized", false));
        clipboardTimer.setCycleCount(Animation.INDEFINITE);
        clipboardTimer.play();
        pollClipboard();
        loadPlaces(true);
    }

    @Override
    public void stop() {
        closing = true;
        finishFileDrag();
        clipboardTimer.stop();
        clipboardDeadline.stop();
        if (clipboardCommand != null) { clipboardCommand.cancel(true); }
        clipboardWorker.shutdownNow();
        if (leftPane != null) { leftPane.browser.dismissTransientControls(); }
        tabHeaders.keySet().forEach(tab -> {
            leftPane.releaseBrowse(tab);
            tab.closed = true;
            tab.selectionProof = Map.of();
        });
        tabHeaders.clear();
        if (leftPane != null) { leftPane.watcher.close(); }
        if (rightPane != null) {
            rightPane.browser.dismissTransientControls();
            rightPane.releaseBrowse(rightPane.context);
            rightPane.context.closed = true;
            rightPane.watcher.close();
        }
        thumbnails.clear();
        saveWindowLayout();
        if (fileTask != null && !writing) { fileTask.cancel(); }
        if (modifiedFoldersTask != null) { reads.cancel(modifiedFoldersTask); }
        reads.close();
        if (activeFileOperation != null) {
            activeFileOperation.requestCancellation();
            writes.shutdown();
        } else if (writing) {
            writes.shutdown();
        } else {
            writes.shutdownNow();
        }
        if (operationProgress != null) { operationProgress.close(); }
        if (icons != null) { icons.close(); }
    }

    private double layoutValue(String key, double fallback) {
        double value = preferences.getDouble(key, fallback);
        return Double.isFinite(value) ? value : fallback;
    }

    private void restoreWindowLayout() {
        browserDivider = Math.clamp(layoutValue("browserDivider", 0.5), 0, 1);
        setDualPane(preferences.getBoolean("dualPane", false));
        double x = layoutValue("windowX", Double.NaN);
        double y = layoutValue("windowY", Double.NaN);
        if (!Double.isFinite(x) || !Double.isFinite(y)) { return; }

        var screens = Screen.getScreensForRectangle(x, y, 1, 1);
        var bounds = (screens.isEmpty() ? Screen.getPrimary() : screens.getFirst()).getVisualBounds();
        double width = Math.clamp(layoutValue("windowWidth", 1200), stage.getMinWidth(),
                Math.max(stage.getMinWidth(), bounds.getWidth()));
        double height = Math.clamp(layoutValue("windowHeight", 760), stage.getMinHeight(),
                Math.max(stage.getMinHeight(), bounds.getHeight()));
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(Math.clamp(x, bounds.getMinX(), Math.max(bounds.getMinX(), bounds.getMaxX() - width)));
        stage.setY(Math.clamp(y, bounds.getMinY(), Math.max(bounds.getMinY(), bounds.getMaxY() - height)));
    }

    private void saveWindowLayout() {
        if (stage == null) { return; }
        rememberNavigationWidth();
        preferences.putDouble("navigationWidth", navigationWidth);
        preferences.putBoolean("dualPane", dualPaneOpen);
        double[] positions = browsers.getDividerPositions();
        preferences.putDouble("browserDivider", positions.length == 0 ? browserDivider : positions[0]);
        preferences.putBoolean("windowMaximized", stage.isMaximized());
        if (!stage.isMaximized() && !stage.isFullScreen() && !stage.isIconified()) {
            preferences.putDouble("windowX", stage.getX());
            preferences.putDouble("windowY", stage.getY());
            preferences.putDouble("windowWidth", stage.getWidth());
            preferences.putDouble("windowHeight", stage.getHeight());
        }
    }

    private HBox tabBar() {
        tabs.setId("browse-tabs");
        tabs.getStyleClass().add("browse-tabs");
        tabs.setMinWidth(0);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.setTabDragPolicy(TabPane.TabDragPolicy.FIXED);
        installFileDrop(tabs, this::tabDropSite);
        addTabHeader(leftPane.context);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (!closing && selected != null) {
                activatePane(leftPane);
                activateTab((BrowseContext) selected.getUserData());
            }
        });
        Button add = new Button(null, UiGlyph.ADD.graphic(17));
        add.getStyleClass().add("icon-button");
        add.setId("new-tab");
        add.setAccessibleText("新建标签页");
        add.setTooltip(new Tooltip("新建标签页 (Ctrl+T)"));
        add.setOnAction(event -> newTab());
        HBox bar = new HBox(6, tabs, add);
        tabs.setMaxWidth(Region.USE_PREF_SIZE);
        Runnable sizeTabs = () -> {
            double needed = 36;
            for (Tab tab : tabs.getTabs()) {
                var text = new javafx.scene.text.Text(tab.getText() == null ? "" : tab.getText());
                text.setFont(javafx.scene.text.Font.font("Microsoft YaHei UI", 13));
                needed += Math.clamp(text.getLayoutBounds().getWidth() + 57, 92, 190);
            }
            tabs.setPrefWidth(Math.min(needed, Math.max(92, bar.getWidth() - 70)));
        };
        bar.widthProperty().addListener((observable, oldWidth, width) -> sizeTabs.run());
        tabs.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> {
            while (change.next()) {
                for (Tab tab : change.getAddedSubList()) {
                    tab.textProperty().addListener((observable, oldText, text) -> sizeTabs.run());
                }
            }
            sizeTabs.run();
        });
        for (Tab tab : tabs.getTabs()) {
            tab.textProperty().addListener((observable, oldText, text) -> sizeTabs.run());
        }
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("browse-tab-bar");
        // Cancel an inline edit before a header can take keyboard focus.
        bar.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            activatePane(leftPane);
            if (leftPane.browser.editingName()) {
                leftPane.browser.cancelNewName();
                Platform.runLater(leftPane::flushWatchRefresh);
            }
        });
        return bar;
    }

    private Tab addTabHeader(BrowseContext owner) {
        Tab tab = new Tab();
        tab.setId("browse-tab-" + owner.id);
        tab.setUserData(owner);
        tab.setOnCloseRequest(event -> {
            event.consume();
            closeTab(owner);
        });
        tab.setContextMenu(new ContextMenu(
                menuItem("复制标签页", "duplicate-tab", () -> duplicateTab(owner)),
                menuItem("关闭标签页", "close-tab", () -> closeTab(owner))));
        tabHeaders.put(owner, tab);
        updateTabHeader(owner);
        tabs.getTabs().add(tab);
        return tab;
    }

    private void updateTabHeader(BrowseContext owner) {
        Tab tab = tabHeaders.get(owner);
        if (tab == null) { return; }
        Location location = owner.location;
        String title = location.label();
        String tooltip = location.path() == null ? location.label() : location.path().toString();
        if (!owner.activeQuery.isEmpty()) {
            title = "搜索 · " + owner.activeQuery;
            tooltip = title + "\n" + tooltip;
        }
        tab.setText(title);
        tab.setTooltip(new Tooltip(tooltip));
        tab.setGraphic(location.path() == null
                ? icons.namespaceGraphic(location.page().namespace, 16)
                : icons.graphic(BrowserItem.folder(location.path(), location.label()), 16));
    }

    private void newTab() {
        Location location = leftPane.context.location;
        openTab(location.path() == null ? Location.page(Page.HOME) : location);
    }

    private void openTab(Location location) {
        var owner = new BrowseContext(++nextTabId);
        if (location.page() != Page.HOME) { owner.opening = location; }
        tabs.getSelectionModel().select(addTabHeader(owner));
    }

    private void duplicateTab(BrowseContext original) {
        if (original.closed) { return; }
        if (original == leftPane.context) { leftPane.savePresentation(); }
        var duplicate = new BrowseContext(++nextTabId);
        duplicate.copyFrom(original);
        tabs.getSelectionModel().select(addTabHeader(duplicate));
    }

    private void closeTab(BrowseContext owner) {
        Tab tab = tabHeaders.get(owner);
        if (closing || tab == null) { return; }
        if (dropSite != null && dropSite.owner() == owner) { endDropHover(); }
        var headers = tabs.getTabs();
        if (headers.size() == 1) {
            openTab(Location.page(Page.HOME));
        } else if (owner == leftPane.context) {
            int index = headers.indexOf(tab);
            tabs.getSelectionModel().select(index == 0 ? 1 : index - 1);
        }
        owner.closed = true;
        owner.selectionProof = Map.of();
        owner.presentation = BrowserView.Presentation.DEFAULT;
        owner.past.clear();
        owner.future.clear();
        tabHeaders.remove(owner);
        headers.remove(tab);
    }

    private void tabKey(KeyEvent event) {
        if (closing || !event.isShortcutDown() || event.isAltDown()) { return; }
        switch (event.getCode()) {
            case T -> { if (event.isShiftDown()) { return; } newTab(); }
            case W -> {
                if (event.isShiftDown()) { return; }
                if (activePane == rightPane) { setDualPane(false); }
                else { closeTab(leftPane.context); }
            }
            case TAB -> {
                activatePane(leftPane);
                int index = tabs.getSelectionModel().getSelectedIndex();
                tabs.getSelectionModel().select(Math.floorMod(index + (event.isShiftDown() ? -1 : 1), tabs.getTabs().size()));
            }
            default -> { return; }
        }
        event.consume();
    }

    private void activateTab(BrowseContext owner) {
        if (owner == leftPane.context || owner.closed) { return; }
        leftPane.suspend();
        leftPane.context = owner;
        leftPane.restoreVisible();
    }

    private List<BrowserPane> visiblePanes() {
        return dualPaneOpen ? List.of(leftPane, rightPane) : List.of(leftPane);
    }

    private boolean visible(BrowserPane pane) {
        return !closing && (pane == leftPane || dualPaneOpen && pane == rightPane);
    }

    private void activatePane(BrowserPane pane) {
        if (!visible(pane) || activePane == pane) { return; }
        BrowserPane previous = activePane;
        previous.browser.dismissTransientControls();
        activePane = pane;
        previous.content.pseudoClassStateChanged(ACTIVE_BROWSER, false);
        pane.content.pseudoClassStateChanged(ACTIVE_BROWSER, dualPaneOpen);
        status.setText(pane.statusText);
        watchStatus.setText(pane.watchText);
        updateInspectorHost();
        markLocation();
        updateControls();
        previous.flushWatchRefresh();
    }

    private void focusOtherPane() {
        if (!dualPaneOpen) { return; }
        activatePane(activePane == leftPane ? rightPane : leftPane);
        activePane.browser.requestFocus();
    }

    private void setDualPane(boolean enabled) {
        if (closing || dualPaneOpen == enabled) { return; }
        if (enabled) {
            if (rightPane == null) {
                rightPane = new BrowserPane("right-pane", "右栏");
                rightPane.mountNavigation();
                Location location = leftPane.context.location;
                if (location.path() != null) { rightPane.context.opening = location; }
            }
            dualPaneOpen = true;
            singleNavigation.getChildren().clear();
            leftPane.mountNavigation();
            browsers.getItems().add(rightPane.content);
            browsers.setDividerPositions(browserDivider);
            activePane.content.pseudoClassStateChanged(ACTIVE_BROWSER, true);
            rightPane.restoreVisible();
        } else {
            double[] positions = browsers.getDividerPositions();
            if (positions.length > 0) { browserDivider = positions[0]; }
            dualPaneOpen = false;
            activatePane(leftPane);
            rightPane.suspend();
            browsers.getItems().remove(rightPane.content);
            leftPane.content.getChildren().remove(leftPane.navigation);
            leftPane.useSingleNavigation();
            singleNavigation.getChildren().setAll(leftPane.navigation);
            leftPane.content.pseudoClassStateChanged(ACTIVE_BROWSER, false);
        }
        updateInspectorHost();
        updateControls();
    }

    private void updateInspectorHost() {
        boolean show = activePane.context.pane != Pane.NONE;
        if (show) { inspectorHost.getChildren().setAll(activePane.inspector); }
        else { inspectorHost.getChildren().clear(); }
        inspectorHost.setManaged(show);
    }

    private HBox commandBar() {
        MenuButton create = menuButton("新建", "new", () -> newItems());
        commandGraphic(create, "新建", UiGlyph.ADD, true);
        MenuButton sort = menuButton("排序", "sort", () -> activePane.sortItems());
        commandGraphic(sort, "排序", UiGlyph.SORT, true);
        MenuButton view = menuButton("查看", "view", () -> activePane.viewItems());
        commandGraphic(view, "查看", UiGlyph.VIEW, true);
        MenuButton more = menuButton("···", "more", this::moreItems);
        commandGraphic(more, "更多命令", UiGlyph.MORE, false);
        more.getStyleClass().add("command-more");
        MenuButton layout = menuButton("布局", "browse-layout", () -> activePane.layoutItems());
        commandGraphic(layout, "浏览布局", UiGlyph.SPLIT, true);
        MenuButton sources = menuButton("图库来源", "gallery-sources", this::galleryItems);
        commandGraphic(sources, "图库来源", UiGlyph.GALLERY, true);
        sources.managedProperty().bind(sources.visibleProperty());
        Button details = button("详细信息", "details-pane", () -> activePane.changePane(Pane.DETAILS));
        commandGraphic(details, "详细信息", UiGlyph.DETAILS, true);
        Button dual = button("双栏", "dual-pane", () -> setDualPane(!dualPaneOpen));
        commandGraphic(dual, "切换单双栏", UiGlyph.SPLIT, true);
        Button copyAcross = button("复制到对栏", "copy-across", () -> transferAcross(false));
        Button moveAcross = button("移动到对栏", "move-across", () -> transferAcross(true));
        copyAcross.setTooltip(new Tooltip("复制到对栏（Ctrl+Shift+F5）"));
        moveAcross.setTooltip(new Tooltip("移动到对栏（Ctrl+Shift+F6）"));
        for (Button transfer : List.of(copyAcross, moveAcross)) {
            transfer.managedProperty().bind(transfer.visibleProperty());
            transfer.setVisible(false);
        }
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox bar = new HBox(5, create, commandDivider(),
                commandButton("剪切", "cut", UiGlyph.CUT, () -> rememberSelection(true)),
                commandButton("复制", "copy", UiGlyph.COPY, () -> rememberSelection(false)),
                commandButton("粘贴", "paste", UiGlyph.PASTE, this::paste),
                commandButton("重命名", "rename", UiGlyph.RENAME, this::renameSelected),
                commandButton("移到回收站", "trash", UiGlyph.DELETE, this::trashSelected),
                commandDivider(), sort, view, sources, more, space, layout, dual, copyAcross, moveAcross, details);
        bar.setId("command-bar");
        bar.getStyleClass().add("command-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private Button commandButton(String label, String id, UiGlyph glyph, Runnable action) {
        Button button = button(label, id, action);
        commandGraphic(button, label, glyph, false);
        return button;
    }

    private static void commandGraphic(Labeled control, String label, UiGlyph glyph, boolean showText) {
        control.setGraphic(glyph.graphic(17));
        control.setGraphicTextGap(7);
        control.setAccessibleText(label);
        control.setTooltip(new Tooltip(label));
        if (!showText) {
            control.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            control.getStyleClass().add("icon-command");
        }
    }

    private static Region commandDivider() {
        Region divider = new Region();
        divider.getStyleClass().add("command-divider");
        return divider;
    }

    private HBox statusBar() {
        progress.setPrefSize(16, 16);
        progress.setMaxSize(16, 16);
        progress.managedProperty().bind(progress.visibleProperty());
        status.setId("status");
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);
        clipboardStatus.setMaxWidth(280);
        clipboardStatus.setId("clipboard-status");
        watchStatus.setId("watch-status");
        watchStatus.setText(leftPane.watchText);
        unknownResults.setId("unknown-results");
        unknownResults.setVisible(false);
        unknownResults.managedProperty().bind(unknownResults.visibleProperty());
        unknownResults.setOnAction(event -> inspectUnknownResults());
        writeNotice.setId("write-notice");
        writeNotice.managedProperty().bind(writeNotice.visibleProperty());
        writeNotice.setVisible(false);
        dragStatus.setId("drag-status");
        dragStatus.getStyleClass().add("drag-status");
        dragStatus.setMaxWidth(450);
        dragStatus.managedProperty().bind(dragStatus.visibleProperty());
        dragStatus.setVisible(false);
        HBox bar = new HBox(8, progress, status, dragStatus, watchStatus, clipboardStatus, operationProgress.launcher(), writeNotice, unknownResults);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void loadPlaces(boolean initial) {
        if (closing || catalogTask != null) { return; }
        if (initial) { status.setText("正在读取系统位置…"); }
        var read = task(cancelled -> new SystemCatalog(WindowsPlaces.load(), NewItems.load()));
        catalogTask = read;
        readDetail(read, catalog -> {
            catalogTask = null;
            var result = catalog.places();
            places = result;
            newTypes = catalog.newTypes();
            if (initial) {
                visibility = new LocalFiles.Visibility(preferences.getBoolean("hidden", result.visibility().hidden()),
                        preferences.getBoolean("system", result.visibility().protectedSystem()));
                extensions = preferences.getBoolean("extensions", result.extensions());
                compact = preferences.getBoolean("compact", false);
                checkboxes = preferences.getBoolean("checkboxes", false);
                restorePreferences();
            }
            rebuildSidebar();
            refreshModifiedFolders();
            for (BrowserPane pane : visiblePanes()) {
                if (initial) { pane.restoreVisible(); }
                else if (pane.context.location.path() == null || pane.context.browseRequest != null) { pane.refresh(""); }
                else { pane.updateLocation(); }
            }
            updateControls();
        }, failure -> {
            catalogTask = null;
            status.setText("无法读取系统位置");
            showError(failure);
        });
    }

    private void restorePreferences() {
        extraPins.addAll(loadPaths("pins"));
        removedPins.addAll(loadPaths("unpinned"));
        favorites.addAll(loadPaths("favorites"));
        networkLocations.addAll(loadPaths("network"));
        galleryFolders.addAll(loadPaths("gallery"));
        if (galleryFolders.isEmpty()) { galleryFolders.add(places.fixed().get(3).path()); }
        loadVisits("recentFolders", folderHistory);
        loadVisits("recentFiles", recentFiles);
        loadVisits("folderEdits", folderEdits);
    }

    private List<String> addressHistory() {
        Map<Path, FileTime> visits = new LinkedHashMap<>();
        if (places != null) { places.recent().forEach(item -> visits.putIfAbsent(item.path(), item.time())); }
        folderHistory.forEach(visits::put);
        recentFiles.forEach(visits::put);
        return visits.entrySet().stream().sorted(Map.Entry.<Path, FileTime>comparingByValue().reversed())
                .limit(20).map(entry -> entry.getKey().toString()).toList();
    }

    private static Page containerPage(Path path) {
        String address = path.toString().toLowerCase(Locale.ROOT);
        if (address.startsWith("\\\\wsl.localhost\\") || address.startsWith("\\\\wsl$\\")) { return Page.LINUX; }
        return address.startsWith("\\\\") ? Page.NETWORK : Page.COMPUTER;
    }

    private BrowseData readBrowse(BrowseIntent intent, Work<Listing> work, Map<BrowserItem, Selection> selected,
                                  BooleanSupplier cancelled) throws Exception {
        Path path = intent.location().path();
        if (path == null) {
            Listing data = work.read(cancelled);
            return new BrowseData(data, null, null, verifySelection(selected, data.items(), cancelled));
        }
        requireVisible(path);
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (!attributes.isDirectory() && intent.openAddressFile()) {
            return new BrowseData(null, null, path, Map.of());
        }
        var before = DirectoryIdentity.read(path, attributes);
        var data = work.read(cancelled);
        readObserver.ready(path, false);
        if (cancelled.getAsBoolean()) { return null; }
        var verified = verifySelection(selected, data.items(), cancelled);
        var after = DirectoryIdentity.read(path);
        if ((before.known() || after.known()) && !before.equals(after)) {
            throw new IOException("读取期间目录身份已变化，请刷新：" + path);
        }
        return new BrowseData(data, after, null, verified);
    }

    private static Map<BrowserItem, SelectedItem> verifySelection(Map<BrowserItem, Selection> selected,
            List<BrowserItem> items, BooleanSupplier cancelled) {
        if (selected.isEmpty()) { return Map.of(); }
        var expected = new HashMap<Path, BrowserItem>();
        selected.forEach((item, proof) -> expected.put(item.path(), item));
        var verified = new HashMap<BrowserItem, SelectedItem>();
        for (BrowserItem item : items) {
            if (cancelled.getAsBoolean()) { break; }
            var previous = expected.get(item.path());
            if (previous == null) { continue; }
            Selection proof = observeSelection(item);
            if (proof != null && selected.get(previous).nativeItem().equals(proof.nativeItem())) {
                verified.put(previous, new SelectedItem(item, proof));
            }
        }
        return verified;
    }

    private Work<Listing> listingWork(Location target, String query, HomeTab tab) {
        LocalFiles.Visibility filters = visibility;
        String keyword = query.toLowerCase(Locale.ROOT);
        return switch (target.page()) {
            case DIRECTORY -> cancelled -> fromDisk(query.isEmpty()
                    ? readVisibleDirectory(target.path(), filters, cancelled)
                    : LocalFiles.search(target.path(), query, filters, cancelled, staging::contains));
            case COMPUTER -> staticListing(places.computer(), keyword);
            case NETWORK -> staticListing(networkItems(), keyword);
            case LINUX -> staticListing(places.linux(), keyword);
            case GALLERY -> galleryWork(keyword, filters);
            case HOME -> homeWork(keyword, tab);
        };
    }

    private static Work<Listing> staticListing(List<BrowserItem> source, String keyword) {
        return cancelled -> new Listing(source.stream()
                .filter(item -> item.name().toLowerCase(Locale.ROOT).contains(keyword)).toList(), 0, false);
    }

    private Work<Listing> galleryWork(String keyword, LocalFiles.Visibility filters) {
        List<Path> sources = List.copyOf(galleryFolders);
        return cancelled -> {
            Map<Path, BrowserItem> matches = new LinkedHashMap<>();
            int unreadable = 0;
            boolean limited = false;
            for (Path source : sources) {
                try {
                    if (staging.contains(source)) { continue; }
                    var result = LocalFiles.images(source, keyword, filters, cancelled, staging::contains);
                    result.entries().forEach(entry -> matches.putIfAbsent(entry.path(), BrowserItem.file(entry)));
                    unreadable += result.unreadable();
                    limited |= result.limited();
                    if (matches.size() > 2_000) { limited = true; break; }
                } catch (NoSuchFileException exception) {
                    // Default Pictures may not have been created yet.
                } catch (IOException exception) {
                    unreadable++;
                }
            }
            List<BrowserItem> items = matches.values().stream().limit(2_000).toList();
            Map<Path, Double> ratios = new HashMap<>();
            for (BrowserItem item : items) {
                if (cancelled.getAsBoolean()) { break; }
                ratios.put(item.path(), LocalFiles.imageAspectRatio(item.path()));
            }
            return new Listing(items, unreadable, limited, ratios);
        };
    }

    private Work<Listing> homeWork(String keyword, HomeTab tab) {
        if (tab == HomeTab.SHARED) { return staticListing(networkItems(), keyword); }
        Map<Path, FileTime> source = new LinkedHashMap<>();
        if (tab == HomeTab.FAVORITES) {
            favorites.forEach(path -> source.put(path, null));
        } else {
            source.putAll(recentFiles);
            places.recent().forEach(recent -> source.putIfAbsent(recent.path(), recent.time()));
        }
        boolean onlyFiles = tab == HomeTab.RECENT;
        return cancelled -> {
            List<BrowserItem> results = new ArrayList<>();
            for (var entry : source.entrySet()) {
                if (cancelled.getAsBoolean()) { break; }
                if (staging.contains(entry.getKey())) { continue; }
                try {
                    BrowserItem item = BrowserItem.file(LocalFiles.describe(entry.getKey())).visitedAt(entry.getValue());
                    if ((!onlyFiles || !item.directory()) && item.name().toLowerCase(Locale.ROOT).contains(keyword)) {
                        results.add(item);
                    }
                } catch (IOException exception) {
                    // Recent shortcuts can outlive the files they refer to.
                }
            }
            return new Listing(List.copyOf(results), 0, false);
        };
    }

    private static Listing fromDisk(LocalFiles.Listing result) {
        return new Listing(result.entries().stream().map(BrowserItem::file).toList(), result.unreadable(), result.limited());
    }

    private LocalFiles.Listing readVisibleDirectory(Path path, LocalFiles.Visibility filters, BooleanSupplier cancelled)
            throws IOException {
        requireVisible(path);
        return LocalFiles.readDirectory(path, filters, cancelled, staging::contains);
    }

    private void requireVisible(Path path) throws IOException {
        if (staging.contains(path)) { throw new IOException("该目录属于正在进行的文件任务：" + path); }
    }

    private List<MenuItem> newItems() {
        List<MenuItem> menu = new ArrayList<>();
        for (NewItems.Type type : newTypes) {
            String id = switch (type.kind()) {
                case FOLDER -> "new-folder";
                case SHORTCUT -> "new-shortcut";
                default -> "new-" + type.extension().substring(1);
            };
            if (menu.size() == 2 && WindowsPlaces.WINDOWS) { menu.add(new SeparatorMenuItem()); }
            menu.add(menuItem(type.label(), id, () -> create(type)));
        }
        return menu;
    }

    private void changeVisibility(LocalFiles.Visibility filters) {
        visibility = filters;
        preferences.putBoolean("hidden", filters.hidden());
        preferences.putBoolean("system", filters.protectedSystem());
        rebuildSidebar();
        refreshModifiedFolders();
        visiblePanes().forEach(pane -> pane.refresh(""));
    }

    private List<MenuItem> moreItems() {
        boolean windows = WindowsPlaces.WINDOWS;
        List<MenuItem> commands = new ArrayList<>();
        commands.add(iconMenu("撤消", "undo", UiGlyph.UNDO, this::undo, fileTask != null || undo.isEmpty()));
        BrowserItem drive = driveForTools();
        if (windows && (activePane.context.location.page() == Page.COMPUTER || drive != null)) {
            commands.add(new SeparatorMenuItem());
            commands.add(iconMenu("清理", "clean-drive", UiGlyph.CLEAN,
                    () -> driveTool(drive, "清理", () -> WindowsPlaces.cleanDrive(drive.path()), false), drive == null || fileTask != null));
            commands.add(iconMenu("优化", "optimize-drive", UiGlyph.DRIVE,
                    () -> driveTool(drive, "优化", WindowsPlaces::optimizeDrives, false), drive == null || fileTask != null));
            commands.add(iconMenu("格式化", "format-drive", UiGlyph.FORMAT,
                    () -> driveTool(drive, "格式化", () -> WindowsPlaces.formatDrive(drive.path()), true), drive == null || fileTask != null));
        }
        if (activePane.context.location.page() == Page.DIRECTORY) {
            commands.add(new SeparatorMenuItem());
            commands.add(pinItem(activePane.context.location.path()));
        }
        if (windows && (activePane.context.location.page() == Page.COMPUTER || activePane.context.location.page() == Page.NETWORK)) {
            commands.add(new SeparatorMenuItem());
            commands.add(iconMenu("连接到媒体服务器", "media-server", UiGlyph.MEDIA, this::connectMedia, false));
            commands.add(iconMenu("添加一个网络位置", "add-network", UiGlyph.NETWORK, this::addNetwork, false));
            commands.add(iconMenu("映射网络驱动器", "map-network", UiGlyph.DRIVE, this::mapNetworkDrive, fileTask != null));
            commands.add(iconMenu("断开网络驱动器的连接", "disconnect-network", UiGlyph.DISCONNECT,
                    this::disconnectNetworkDrive, fileTask != null));
        }
        commands.add(new SeparatorMenuItem());
        commands.add(iconMenu("全部选择", "select-all", UiGlyph.SELECT_ALL, activePane.browser::selectAll, activePane.browser.items().isEmpty()));
        commands.add(iconMenu("全部取消", "select-none", UiGlyph.SELECT_NONE, activePane.browser::clearSelection, activePane.browser.selection().isEmpty()));
        commands.add(iconMenu("反向选择", "invert-selection", UiGlyph.INVERT, activePane.browser::invertSelection, activePane.browser.items().isEmpty()));
        if (activePane.browser.filtersActive()) {
            commands.add(iconMenu("清除列筛选", "clear-column-filters", UiGlyph.CLOSE, activePane.browser::clearFilters, false));
        }
        commands.add(new SeparatorMenuItem());
        commands.add(iconMenu("属性", "properties", UiGlyph.PROPERTIES, this::showProperties, false));
        commands.add(iconMenu("选项", "options", UiGlyph.SETTINGS, this::options, false));
        return commands;
    }

    private BrowserItem driveForTools() {
        if (activePane.context.location.page() == Page.COMPUTER && activePane.browser.selection().size() == 1) {
            BrowserItem drive = activePane.selectedItem();
            return WindowsPlaces.supportsDriveTools(drive) ? drive : null;
        }
        if (activePane.context.location.page() == Page.DIRECTORY && activePane.context.location.path().getParent() == null) {
            return places.computer().stream().filter(item -> activePane.context.location.path().equals(item.path()))
                    .filter(WindowsPlaces::supportsDriveTools).findFirst().orElse(null);
        }
        return null;
    }

    private void driveTool(BrowserItem drive, String label, FileAction action, boolean reload) {
        if (drive == null || fileTask != null) { return; }
        var report = activePane.listingReporter();
        runFileTask(task(ignored -> {
            action.run();
            return null;
        }), "正在打开 Windows「" + label + "」…", false, ignored -> {
            if (reload) { loadPlaces(false); }
            else { report.accept("已打开 Windows「" + label + "」"); }
        });
    }

    private ContextMenu contextMenu(BrowserPane pane, BrowserItem item) {
        if (places == null || !visible(pane)) { return null; }
        boolean unavailable = fileCommandsUnavailable(pane);
        boolean transferUnavailable = unavailable || clipboardCommand != null;
        List<MenuItem> commands = new ArrayList<>();
        if (item == null) {
            commands.add(menuItem("刷新", "context-refresh", pane::refreshCommand));
            commands.add(submenu("排序", pane.sortItems()));
            commands.add(submenu("查看", pane.viewItems()));
            if (pane.context.location.path() != null) {
                commands.add(new SeparatorMenuItem());
                if (canCreate()) { commands.add(submenu("新建", newItems())); }
                commands.add(menuItem("粘贴", "context-paste", () -> paste(pane), transferUnavailable || !clipboardHasFiles()));
            }
        } else {
            commands.add(menuItem("打开", "context-open", () -> pane.open(item)));
            if (item.directory() && item.path() != null) {
                commands.add(menuItem("在新标签页中打开", "context-open-tab", () -> openTab(Location.folder(item.path()))));
            }
            if (item.entry() != null && (pane.context.location.path() == null || !pane.context.activeQuery.isEmpty())) {
                commands.add(menuItem("打开所在文件夹", "context-parent", () -> pane.navigate(Location.folder(item.path().getParent()))));
            }
            if (canModify(pane.browser.selection())) {
                commands.add(new SeparatorMenuItem());
                commands.add(menuItem("剪切", "context-cut", () -> rememberSelection(pane, true), transferUnavailable));
                commands.add(menuItem("复制", "context-copy", () -> rememberSelection(pane, false), transferUnavailable));
                commands.add(menuItem("重命名", "context-rename", () -> renameSelected(pane),
                        unavailable || pane.browser.selection().size() != 1));
                commands.add(menuItem("移到回收站", "context-trash", () -> trashSelected(pane), unavailable));
            }
            if (item.directory() && item.path() != null) {
                commands.add(menuItem("粘贴到此文件夹", "context-paste-folder", () -> pasteInto(pane, item.path(), item),
                        transferUnavailable || !clipboardHasFiles()));
            }
            if (item.path() != null) {
                commands.add(menuItem("复制为路径", "context-copy-path", () -> copyPaths(pane), transferUnavailable));
            }
            if (item.directory() && item.path() != null && item.kind() != BrowserItem.Kind.DRIVE) {
                commands.add(pinItem(item.path()));
            }
            if (item.entry() != null) {
                commands.add(menuItem(favorites.contains(item.path()) ? "从收藏夹移除" : "添加到收藏夹",
                        "context-favorite", () -> toggleFavorite(item.path())));
            }
            if (pane.context.location.page() == Page.NETWORK && networkLocations.contains(item.path())) {
                commands.add(menuItem("移除网络位置", "remove-network", () -> removeNetwork(item.path())));
            }
        }
        commands.add(new SeparatorMenuItem());
        commands.add(menuItem("属性", "context-properties",
                item == null ? () -> showLocationProperties(pane) : () -> showProperties(item)));
        return new ContextMenu(commands.toArray(MenuItem[]::new));
    }

    private void options() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle("文件管理器选项");
        CheckBox hidden = new CheckBox("显示隐藏的项目");
        hidden.setSelected(visibility.hidden());
        CheckBox protectedFiles = new CheckBox("显示受保护的系统文件");
        protectedFiles.setSelected(visibility.protectedSystem());
        CheckBox showExtensions = new CheckBox("显示文件扩展名");
        showExtensions.setSelected(extensions);
        CheckBox resetPins = new CheckBox("恢复六个默认固定文件夹");
        VBox content = new VBox(12, hidden, protectedFiles, showExtensions, resetPins);
        content.setPadding(new Insets(12));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.APPLY, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.APPLY) {
            if (resetPins.isSelected()) {
                extraPins.clear();
                removedPins.clear();
                savePaths("pins", extraPins);
                savePaths("unpinned", removedPins);
            }
            extensions = showExtensions.isSelected();
            preferences.putBoolean("extensions", extensions);
            changeVisibility(new LocalFiles.Visibility(hidden.isSelected(), protectedFiles.isSelected()));
        }
    }

    private void configureTree() {
        tree.setId("places-tree");
        tree.getStyleClass().add("sidebar");
        tree.setShowRoot(false);
        tree.setMinWidth(MIN_NAV_WIDTH);
        tree.setMaxWidth(MAX_NAV_WIDTH);
        tree.setCellFactory(ignored -> new TreeCell<>() {
            {
                addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    if (!isEmpty() && getItem().divider()) { event.consume(); }
                });
                setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1
                            && !isEmpty() && !getItem().divider()) {
                        for (Node target = event.getTarget() instanceof Node node ? node : null;
                                target != null && target != this; target = target.getParent()) {
                            if (target.getStyleClass().contains("tree-disclosure-node")) { return; }
                        }
                        openSide(getItem());
                    }
                });
                setOnContextMenuRequested(event -> {
                    if (!isEmpty() && getItem().location() != null && getItem().location().path() != null
                            && getItem().location().path().getParent() != null && fileTask == null) {
                        Path path = getItem().location().path();
                        new ContextMenu(pinItem(path)).show(this, event.getScreenX(), event.getScreenY());
                        event.consume();
                    }
                });
            }

            @Override
            protected void updateItem(SideNode node, boolean empty) {
                super.updateItem(node, empty);
                boolean isDivider = !empty && node != null && node.divider();
                pseudoClassStateChanged(NAV_DIVIDER, isDivider);
                setText(empty || node == null ? null : node.displayLabel());
                setGraphic(empty || node == null ? null : sideGraphic(node));
                Path path = node == null || node.location() == null ? null : node.location().path();
                setTooltip(path == null ? null : new Tooltip(path.toString()));
            }
        });
        tree.setOnKeyPressed(event -> {
            var item = tree.getSelectionModel().getSelectedItem();
            if (event.getCode() == KeyCode.ENTER && item != null) { openSide(item.getValue()); }
        });
    }

    private Node sideGraphic(SideNode node) {
        if (node.item() != null) { return icons.graphic(node.item(), 16); }
        return node.location() == null || node.location().page().namespace == null ? null
                : icons.namespaceGraphic(node.location().page().namespace, 16);
    }

    private void syncNavigationPane() {
        boolean attached = workspace.getItems().contains(tree);
        if (navigationPane && !attached) {
            workspace.getItems().addFirst(tree);
            applyNavigationWidth();
        } else if (!navigationPane && attached) {
            rememberNavigationWidth();
            workspace.getItems().remove(tree);
        }
    }

    private void applyNavigationWidth() {
        tree.setPrefWidth(navigationWidth);
        if (workspace.getWidth() > 0) { workspace.setDividerPosition(0, navigationWidth / workspace.getWidth()); }
    }

    private void rememberNavigationWidth() {
        if (workspace.getItems().contains(tree) && tree.getWidth() >= MIN_NAV_WIDTH) {
            navigationWidth = Math.min(tree.getWidth(), MAX_NAV_WIDTH);
        }
    }

    private void openSide(SideNode node) {
        if (node.location() != null) { activePane.navigate(node.location()); }
        else if (node.item() != null) { activePane.open(node.item()); }
    }

    private void rebuildSidebar() {
        sidebarVersion++;
        treeReads.values().forEach(reads::cancel);
        treeReads.clear();
        Set<Path> expanded = new HashSet<>();
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            var node = tree.getTreeItem(i);
            if (node != null && node.isExpanded() && node.getValue().location() != null
                    && node.getValue().location().path() != null) {
                expanded.add(node.getValue().location().path());
            }
        }
        var root = branch("", null);
        root.getChildren().addAll(List.of(sideHeading("位置"), pageItem(Page.HOME), pageItem(Page.GALLERY)));
        for (BrowserItem cloud : places.oneDrive()) { root.getChildren().add(directoryItem(cloud, expanded)); }
        quickAccessStart = sideHeading("快速访问与收藏");
        quickAccessEnd = sideHeading("设备与位置");
        root.getChildren().addAll(List.of(quickAccessStart, quickAccessEnd));
        places.computer().stream().filter(item -> item.kind() == BrowserItem.Kind.FOLDER)
                .forEach(item -> root.getChildren().add(directoryItem(item, expanded)));
        var computer = pageItem(Page.COMPUTER);
        computer.setExpanded(true);
        places.computer().stream().filter(item -> item.kind() == BrowserItem.Kind.DRIVE)
                .forEach(item -> computer.getChildren().add(directoryItem(item, expanded)));
        var network = pageItem(Page.NETWORK);
        networkItems().forEach(item -> network.getChildren().add(directoryItem(item, expanded)));
        var linux = pageItem(Page.LINUX);
        places.linux().forEach(item -> linux.getChildren().add(directoryItem(item, expanded)));
        root.getChildren().addAll(List.of(computer, network, linux));
        tree.setRoot(root);
        refreshQuickAccess();
        markLocation();
    }

    private TreeItem<SideNode> directoryItem(BrowserItem item, Set<Path> expanded) {
        Path path = item.path();
        long version = sidebarVersion;
        var branch = new TreeItem<>(new SideNode(item.name(), Location.folder(path), item));
        branch.getChildren().add(new TreeItem<>(new SideNode("正在读取…", null, null)));
        branch.expandedProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue) {
                var pending = treeReads.remove(branch);
                if (pending != null) { reads.cancel(pending); }
                return;
            }
            if (version != sidebarVersion || treeReads.containsKey(branch) || branch.getChildren().size() != 1
                    || !branch.getChildren().getFirst().getValue().label().equals("正在读取…")) { return; }
            LocalFiles.Visibility filter = visibility;
            var read = task(cancelled -> readVisibleDirectory(path, filter, cancelled));
            treeReads.put(branch, read);
            readDetail(read, result -> {
                if (treeReads.remove(branch, read) && version == sidebarVersion && branch.isExpanded()) {
                    branch.getChildren().setAll(result.entries().stream().filter(LocalFiles.Entry::directory)
                            .map(BrowserItem::file).map(child -> directoryItem(child, expanded)).toList());
                }
            }, failure -> {
                if (treeReads.remove(branch, read) && version == sidebarVersion && branch.isExpanded()) {
                    branch.getChildren().setAll(List.of(new TreeItem<>(new SideNode("无法读取此文件夹", null, null))));
                }
            });
        });
        if (expanded.contains(path)) { branch.setExpanded(true); }
        return branch;
    }

    private static TreeItem<SideNode> branch(String label, Location target) {
        var branch = new TreeItem<>(new SideNode(label, target, null));
        branch.setExpanded(true);
        return branch;
    }

    private static TreeItem<SideNode> sideHeading(String title) {
        return new TreeItem<>(new SideNode("§" + title, null, null));
    }

    private static TreeItem<SideNode> pageItem(Page page) {
        return new TreeItem<>(new SideNode(page.title, Location.page(page), null));
    }

    private void markLocation() {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            var item = tree.getTreeItem(i);
            if (item != null && activePane.context.location.equals(item.getValue().location())) {
                tree.getSelectionModel().select(item);
                return;
            }
        }
        tree.getSelectionModel().clearSelection();
    }

    private List<BrowserItem> pins() {
        return Stream.concat(places.fixed().stream().filter(item -> !removedPins.contains(item.path())),
                extraPins.stream().map(path -> BrowserItem.folder(path, Location.folder(path).label()))).toList();
    }

    private List<BrowserItem> latestFolders() {
        Set<Path> pinned = new HashSet<>();
        pins().forEach(item -> pinned.add(item.path().normalize()));
        return modifiedFolders.stream().filter(folder -> !pinned.contains(folder)).limit(4)
                .map(folder -> BrowserItem.folder(folder, Location.folder(folder).label())).toList();
    }

    private void refreshModifiedFolders() {
        if (places == null) { return; }
        if (modifiedFoldersTask != null) {
            modifiedFoldersRefreshQueued = true;
            return;
        }
        Set<Path> known = new LinkedHashSet<>();
        Stream.concat(places.fixed().stream(), pins().stream())
                .map(item -> item.path().normalize()).forEach(known::add);
        // Recent shortcut paths help locate folders outside the index. Only their real write times are ranked.
        places.recent().stream().map(recent -> recent.path().normalize().getParent())
                .filter(Objects::nonNull).forEach(known::add);
        Map<Path, FileTime> recorded = Map.copyOf(folderEdits);
        LocalFiles.Visibility filters = visibility;
        Task<List<Path>> scan = task(cancelled -> LocalFiles.recentlyModifiedFolders(
                known, recorded, WindowsPlaces.indexedModifiedItems(), filters, cancelled).stream()
                .filter(path -> !staging.contains(path)).toList());
        modifiedFoldersTask = scan;
        readDetail(scan, result -> {
            List<BrowserItem> previous = latestFolders();
            modifiedFolders = result;
            if (!previous.equals(latestFolders())) {
                refreshQuickAccess();
                markLocation();
                visiblePanes().stream().filter(BrowserPane::homeLayout).forEach(BrowserPane::refreshOverview);
            }
            finishModifiedFoldersRefresh();
        }, failure -> finishModifiedFoldersRefresh());
    }

    private void finishModifiedFoldersRefresh() {
        modifiedFoldersTask = null;
        if (modifiedFoldersRefreshQueued) {
            modifiedFoldersRefreshQueued = false;
            refreshModifiedFolders();
        }
    }

    private void refreshQuickAccess() {
        var children = tree.getRoot().getChildren();
        int start = children.indexOf(quickAccessStart) + 1;
        children.subList(start, children.indexOf(quickAccessEnd)).clear();
        children.addAll(start, Stream.concat(pins().stream(), latestFolders().stream()).map(this::quickLink).toList());
    }

    private TreeItem<SideNode> quickLink(BrowserItem item) {
        return new TreeItem<>(new SideNode(item.name(), Location.folder(item.path()), item));
    }

    private MenuItem pinItem(Path path) {
        boolean pinned = pins().stream().anyMatch(item -> item.path().equals(path));
        return iconMenu(pinned ? "取消固定" : "固定到快速访问", "pin-location", pinned ? UiGlyph.UNPIN : UiGlyph.PIN, () -> {
            boolean defaultPin = places.fixed().stream().anyMatch(item -> item.path().equals(path));
            if (pinned) {
                if (defaultPin) { removedPins.add(path); }
                else { extraPins.remove(path); }
            } else {
                if (defaultPin) { removedPins.remove(path); }
                else { extraPins.add(path); }
            }
            savePaths("pins", extraPins);
            savePaths("unpinned", removedPins);
            refreshQuickAccess();
            visiblePanes().stream().filter(BrowserPane::homeLayout).forEach(BrowserPane::refreshOverview);
            markLocation();
            refreshModifiedFolders();
        }, false);
    }

    private void visitFolder(Location target) {
        if (target.path() != null) {
            rememberVisit("recentFolders", folderHistory, target.path());
        }
    }

    private void toggleFavorite(Path path) {
        if (!favorites.remove(path)) { favorites.add(path); }
        savePaths("favorites", favorites);
        visiblePanes().stream().filter(pane -> pane.context.location.page() == Page.HOME
                && pane.context.homeTab == HomeTab.FAVORITES).forEach(pane -> pane.refresh(""));
    }

    private List<MenuItem> galleryItems() {
        List<MenuItem> choices = new ArrayList<>();
        choices.add(menuItem("添加文件夹…", "add-gallery", () -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("添加图片来源");
            var selected = chooser.showDialog(stage);
            if (selected != null && galleryFolders.add(selected.toPath())) {
                savePaths("gallery", galleryFolders);
                visiblePanes().stream().filter(pane -> pane.context.location.page() == Page.GALLERY)
                        .forEach(pane -> pane.refresh(""));
            }
        }));
        if (galleryFolders.size() > 1) {
            choices.add(submenu("移除来源", galleryFolders.stream().map(path -> menuItem(path.toString(), null, () -> {
                galleryFolders.remove(path);
                savePaths("gallery", galleryFolders);
                visiblePanes().stream().filter(pane -> pane.context.location.page() == Page.GALLERY)
                        .forEach(pane -> pane.refresh(""));
            })).toList()));
        }
        return choices;
    }

    private List<BrowserItem> networkItems() {
        return Stream.concat(networkLocations.stream().map(path -> BrowserItem.folder(path, Location.folder(path).label())),
                places.computer().stream().filter(item -> item.disk() != null && !item.disk().networkPath().isBlank())).toList();
    }

    private void addNetwork() {
        if (fileTask != null) { return; }
        chooseText("添加一个网络位置", "共享路径", "", input -> {
            Path path = WindowsPlaces.networkPath(input);
            if (networkLocations.add(path)) {
                savePaths("network", networkLocations);
                rebuildSidebar();
            }
            activePane.navigate(Location.page(Page.NETWORK));
        });
    }

    private void removeNetwork(Path path) {
        networkLocations.remove(path);
        savePaths("network", networkLocations);
        rebuildSidebar();
        activePane.refresh("");
    }

    private void mapNetworkDrive() {
        runFileTask(task(ignored -> { WindowsPlaces.mapNetworkDrive(); return null; }),
                "等待 Windows 网络驱动器对话框…", false, ignored -> loadPlaces(false));
    }

    private void disconnectNetworkDrive() {
        Map<String, Path> drives = new LinkedHashMap<>();
        for (BrowserItem item : places.computer()) {
            if (item.disk() != null && !item.disk().networkPath().isBlank()) {
                drives.put(item.name() + "  ·  " + item.disk().networkPath(), item.path());
            }
        }
        if (drives.isEmpty()) {
            info("断开网络驱动器的连接", "当前没有已映射的网络驱动器。");
            return;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(drives.keySet().iterator().next(), drives.keySet());
        dialog.initOwner(stage);
        dialog.setTitle("断开网络驱动器的连接");
        dialog.setHeaderText("选择要断开连接的驱动器");
        dialog.setResult(null);
        dialog.setOnHidden(event -> {
            Path drive = drives.get(dialog.getResult());
            if (drive != null) {
                runFileTask(task(ignored -> { WindowsPlaces.disconnectNetworkDrive(drive); return null; }),
                        "正在断开网络驱动器…", false, ignored -> loadPlaces(false));
            }
        });
        dialog.show();
    }

    private void connectMedia() {
        ChoiceDialog<String> dialog = new ChoiceDialog<>("浏览局域网设备", "浏览局域网设备", "输入媒体服务器网址");
        dialog.initOwner(stage);
        dialog.setTitle("连接到媒体服务器");
        dialog.setHeaderText(null);
        dialog.showAndWait().ifPresent(option -> {
            if (option.equals("浏览局域网设备")) {
                browseNetwork();
            } else {
                chooseText("媒体服务器", "HTTP 或 HTTPS 地址", "", input -> {
                    URI uri = URI.create(input.strip());
                    if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                            || "https".equalsIgnoreCase(uri.getScheme()))) {
                        throw new IllegalArgumentException("请输入有效的 HTTP 或 HTTPS 网址");
                    }
                    runFileTask(task(ignored -> { Desktop.getDesktop().browse(uri); return null; }),
                            "正在打开媒体服务器…", false, ignored -> status.setText("已在浏览器中打开"));
                });
            }
        });
    }

    private void browseNetwork() {
        runFileTask(task(ignored -> { WindowsPlaces.openNetworkDiscovery(); return null; }), "正在打开 Windows 网络…",
                false, ignored -> status.setText("已打开 Windows 网络"));
    }

    private static Selection observeSelection(BrowserItem item) {
        if (item.entry() == null) { return null; }
        try {
            var selection = FileChanges.capture(item.entry());
            return selection.nativeItem() == null ? null : selection;
        } catch (IOException | SecurityException unavailable) {
            return null;
        }
    }

    private static String propertiesText(BrowserItem item) {
        List<String> lines = new ArrayList<>(List.of("名称　" + item.name(), "类型　" + item.type()));
        if (item.path() != null) { lines.add("路径　" + item.path()); }
        if (item.disk() != null) {
            for (Field field : List.of(Field.SIZE, Field.AVAILABLE, Field.FILE_SYSTEM, Field.COMMENT, Field.NETWORK, Field.USED)) {
                lines.add((field == Field.SIZE ? "总大小" : field.label) + "　" + field.text(item, true));
            }
        } else if (item.entry() != null) {
            if (!item.directory()) { lines.add("大小　" + Field.SIZE.text(item, true)); }
            lines.add("修改时间　" + Field.MODIFIED.text(item, true));
        }
        return String.join("\n", lines);
    }

    private void showProperties() {
        if (activePane.browser.selection().size() > 1) {
            info("属性", "选中了 " + activePane.browser.selection().size() + " 个项目");
            return;
        }
        BrowserItem selected = activePane.selectedItem();
        if (selected != null) {
            showProperties(selected);
        } else {
            showLocationProperties(activePane);
        }
    }

    private void showLocationProperties(BrowserPane pane) {
        Path path = pane.context.location.path();
        if (path == null) {
            info("属性", pane.context.location.label() + " · " + pane.browser.items().size() + " 个项目");
        } else if (WindowsPlaces.WINDOWS) {
            showNativeProperties(path);
        } else {
            var drive = places.computer().stream().filter(item -> item.disk() != null && path.equals(item.path())).findFirst();
            if (drive.isPresent()) {
                showProperties(drive.get());
            } else {
                var owner = pane.context;
                long version = owner.listingVersion;
                readDetail(task(ignored -> BrowserItem.file(LocalFiles.describe(path))),
                        item -> { if (pane.shows(owner, version) && activePane == pane) { showProperties(item); } },
                        failure -> { if (pane.shows(owner, version) && activePane == pane) { showError(failure); } });
            }
        }
    }

    private void showProperties(BrowserItem item) {
        if (WindowsPlaces.WINDOWS && item.path() != null) { showNativeProperties(item.path()); }
        else { info("属性", propertiesText(item)); }
    }

    private void showNativeProperties(Path path) {
        Task<Void> properties = task(ignored -> {
            WindowsPlaces.showProperties(path);
            return null;
        });
        readDetail(properties, ignored -> {}, this::showError);
    }

    private void openFile(BrowserPane pane, Path path, String name) {
        BrowseContext owner = pane.context;
        readDetail(task(ignored -> { LocalFiles.open(path); return null; }), ignored -> {
            rememberVisit("recentFiles", recentFiles, path);
            if (path.getParent() != null) {
                rememberVisit("recentFolders", folderHistory, path.getParent());
            }
            for (BrowserPane visible : visiblePanes()) {
                if (visible.context.location.page() == Page.HOME && visible.context.homeTab == HomeTab.RECENT) {
                    visible.refresh(visible == pane ? "已打开「" + name + "」" : "");
                }
            }
            if (visible(pane) && pane.context == owner && owner.location.page() != Page.HOME) {
                pane.showStatus("已打开「" + name + "」");
            }
        }, this::showError);
    }

    private boolean canCreate() {
        return !fileCommandsUnavailable() && activePane.context.location.path() != null && activePane.context.activeQuery.isEmpty();
    }

    private void create(NewItems.Type type) {
        if (!canCreate() || places == null) { return; }
        BrowserPane pane = activePane;
        BrowseContext owner = pane.context;
        Path directory = owner.location.path();
        var target = transferTarget(pane, directory, null);
        var report = pane.listingReporter();
        switch (type.kind()) {
            case COMMAND -> runFileTask(task(cancelled -> {
                try (var directories = new NtfsFileOperations.PinnedDirectories()) {
                    verifyCreationDirectory(target, directories);
                    WindowsPlaces.launchNewCommand(directory, type.value());
                    return null;
                }
            }), "正在打开「" + type.label() + "」的创建向导…", false,
                    ignored -> report.accept("已打开「" + type.label() + "」的创建向导"));
            case SHORTCUT -> chooseText("创建快捷方式", "目标路径或网址", "", input -> {
                if (!visible(pane) || pane.context != owner || !directory.equals(owner.location.path())) {
                    showError(new IOException("新建目标已变化，请重新选择"));
                    return;
                }
                NewItems.Shortcut shortcut = NewItems.Shortcut.parse(directory, input);
                createItem(pane, target, type.label(), ignored -> NewItems.createShortcut(directory, type, shortcut));
            });
            default -> createItem(pane, target, type.label(), ignored -> NewItems.create(directory, type));
        }
    }

    private void verifyCreationDirectory(PasteTarget target, NtfsFileOperations.PinnedDirectories directories)
            throws IOException {
        var identity = target.knownDirectory();
        if (identity == null || !identity.known()) { throw new IOException("无法确认新建目标文件夹的身份，请重新选择"); }
        if (identity.ntfs() == null) {
            verifyDestination(target);
        } else {
            requireVisible(target.path());
            directories.verify(identity.ntfs());
        }
    }

    private void createItem(BrowserPane pane, PasteTarget target, String label, Work<NewItems.Created> work) {
        if (fileCommandsUnavailable(pane)) { return; }
        pane.deferWatchRead();
        BrowseContext owner = pane.context;
        Location origin = owner.location;
        long view = owner.listingVersion;
        runFileTask(task(cancelled -> {
            try (var directories = new NtfsFileOperations.PinnedDirectories()) {
                verifyCreationDirectory(target, directories);
                var created = work.read(cancelled);
                try {
                    return new CreatedItem(created, FileChanges.capture(created.entry()));
                } catch (IOException | SecurityException unavailable) {
                    return new CreatedItem(created, null);
                }
            }
        }), "正在新建" + label + "…", true, result -> {
            var created = result.created();
            undo = List.of(FileChanges.undoCreation(created.path()));
            recordFolderEdits(Stream.of(created.path().getParent()));
            rebuildSidebar();
            boolean mayEdit = activePane == pane && pane.shows(owner, view) && owner.browseRequest == null;
            refreshAffected(BrowseImpact.ofParent(created.path()), "已新建" + label, () -> {
                if (!mayEdit || activePane != pane || pane.context != owner || !owner.location.equals(origin)) { return; }
                if (pane.browser.filtersActive() && pane.browser.items().stream().noneMatch(item -> created.path().equals(item.path()))) {
                    pane.browser.clearFilters();
                    pane.updateListingStatus("已新建" + label);
                }
                if (result.proof() == null) {
                    pane.updateListingStatus("无法确认新建项目身份，请重新选择后改名");
                } else {
                    editName(pane, result.proof(), created.extension(), true);
                }
            });
        });
    }

    private void renameSelected() {
        renameSelected(activePane);
    }

    private void renameSelected(BrowserPane pane) {
        BrowserItem selected = pane.selectedItem();
        if (selected == null || selected.entry() == null || pane.browser.selection().size() != 1 || fileCommandsUnavailable(pane)) { return; }
        pane.deferWatchRead();
        String extension = selected.name().substring(LocalFiles.extensionStart(selected.name(), selected.directory()));
        BrowseContext owner = pane.context;
        long version = owner.listingVersion;
        Selection known = owner.selectionProof.get(selected);
        runFileTask(task(ignored -> captureSelected(selected, known)), "正在核对所选项目…", false, selection -> {
            if (pane.shows(owner, version)) { pane.updateListingStatus(""); }
            if (activePane == pane && pane.shows(owner, version) && owner.browseRequest == null && pane.selectedItem() == selected) {
                editName(pane, selection, extension, false);
            }
        });
    }

    private void editName(BrowserPane pane, Selection selection, String extension, boolean keepExtension) {
        if (pane.context.watchTarget != null && selection.needsPathConfirmation()) {
            pane.updateListingStatus("无法确认项目身份，已取消行内编辑");
            return;
        }
        BrowseContext owner = pane.context;
        pane.browser.editName(selection.path(), extension, keepExtension,
                name -> { if (activePane == pane && pane.context == owner) { renameEntry(pane, selection, name); } });
    }

    private void renameEntry(BrowserPane pane, Selection entry, String name) {
        if (fileCommandsUnavailable(pane) || name.isBlank()) { return; }
        try {
            Path target = LocalFiles.childNamed(entry.path().getParent(), name);
            if (entry.path().toString().equals(target.toString())
                    || !confirmCurrentPaths(List.of(entry), Selection::needsPathConfirmation, "重命名", false)) {
                pane.updateListingStatus("");
                return;
            }
            Change change = FileChanges.rename(entry, target, entry.needsPathConfirmation());
            BrowseContext owner = pane.context;
            Location origin = owner.location;
            applyChanges(Kind.RENAME, "正在重命名…", "已重命名", List.of(change), ignored -> { },
                    () -> { if (visible(pane) && pane.context == owner && owner.location.equals(origin)) { pane.browser.reveal(target); } });
        } catch (InvalidPathException exception) {
            showError(exception);
        }
    }

    private void rememberSelection(boolean move) {
        rememberSelection(activePane, move);
    }

    private void rememberSelection(BrowserPane pane, boolean move) {
        List<BrowserItem> selected = pane.browser.selection();
        if (!canModify(selected) || fileCommandsUnavailable(pane) || clipboardCommand != null) { return; }
        if (selected.size() > ClipboardFiles.MAX_ITEMS) {
            pane.updateListingStatus("一次最多可复制或剪切 " + ClipboardFiles.MAX_ITEMS + " 个项目");
            return;
        }
        var report = pane.listingReporter();
        captureSelection(pane, selected, sources -> {
            var token = UUID.randomUUID();
            lastClipboardToken = token;
            runClipboardTask(task(ignored -> {
                var eligible = new LinkedHashMap<Path, Selection>();
                for (var source : sources) { eligible.putIfAbsent(source.path(), source); }
                var paths = ClipboardFiles.paths(eligible.keySet().stream().map(Path::toString).toList());
                var offer = new FileClipboard.Offer(token, move, paths);
                var state = clipboard.publish(offer);
                return new Transfer(state, token, move, List.copyOf(eligible.values()));
            }), false, report, published -> {
                transfer = published;
                observeClipboard(published.state());
                report.accept(move ? "已剪切，可在当前窗口粘贴" : "已复制到文件剪贴板");
            });
        });
    }

    private void captureSelection(BrowserPane pane, List<BrowserItem> selected, Consumer<List<Selection>> ready) {
        var known = pane.context.selectionProof;
        runFileTask(task(ignored -> captureSelected(selected, known)), "正在核对所选项目…", false, ready);
    }

    private static List<Selection> captureSelected(List<BrowserItem> selected, Map<BrowserItem, Selection> known) throws IOException {
        List<Selection> result = new ArrayList<>();
        for (BrowserItem item : selected) { result.add(captureSelected(item, known.get(item))); }
        return List.copyOf(result);
    }

    private static Selection captureSelected(BrowserItem item, Selection known) throws IOException {
        if (known == null) { return FileChanges.capture(item.entry()); }
        NtfsFileOperations.verify(known.nativeItem());
        return known;
    }

    private void fileClipboardKey(KeyEvent event) {
        if (!event.isShortcutDown() || event.isAltDown()
                || event.getTarget() instanceof TextInputControl
                || stage.getScene().getFocusOwner() instanceof TextInputControl
                || event.getTarget() instanceof Node node && activePane.hierarchy.isNavigationFocus(node)
                || activePane.hierarchy.isNavigationFocus(stage.getScene().getFocusOwner())) { return; }
        switch (event.getCode()) {
            case C -> { if (event.isShiftDown()) { copyPaths(activePane); } else { fire("copy"); } }
            case X -> { if (event.isShiftDown()) { return; } fire("cut"); }
            case V -> { if (event.isShiftDown()) { return; } paste(); }
            default -> { return; }
        }
        event.consume();
    }

    private void copyPaths(BrowserPane pane) {
        if (fileCommandsUnavailable(pane) || clipboardCommand != null) { return; }
        var paths = pane.browser.selection().stream().map(BrowserItem::path).filter(Objects::nonNull).distinct().toList();
        if (paths.isEmpty()) { return; }
        var report = pane.listingReporter();
        runClipboardTask(task(ignored -> clipboard.text(ClipboardFiles.quotedPaths(paths))), false, report, state -> {
            observeClipboard(state);
            report.accept("已复制 " + paths.size() + " 个路径");
        });
    }

    private void pollClipboard() {
        if (closing || clipboardProbe != null || clipboardCommand != null) { return; }
        var probe = task(ignored -> clipboard.probe());
        clipboardProbe = probe;
        probe.setOnSucceeded(event -> {
            clipboardProbe = null;
            if (!closing) { observeClipboard(probe.getValue()); }
        });
        probe.setOnFailed(event -> {
            clipboardProbe = null;
            if (!closing) { clipboardReadFailed(); }
        });
        clipboardWorker.execute(probe);
    }

    private <T> void runClipboardTask(Task<T> command, boolean pasteRead, Consumer<String> report, Consumer<T> success) {
        if (closing || clipboardCommand != null) { return; }
        clipboardCommand = command;
        clipboardExpired = false;
        if (pasteRead) {
            clipboardDeadline.setOnFinished(event -> {
                clipboardExpired = true;
                clipboardReadFailed();
                report.accept("等待剪贴板超时，请稍后重试");
            });
            clipboardDeadline.playFromStart();
        }
        command.setOnSucceeded(event -> {
            boolean accept = !clipboardExpired;
            finishClipboardTask();
            if (closing) { return; }
            if (accept) { success.accept(command.getValue()); }
            else { pollClipboard(); }
            updateControls();
        });
        command.setOnFailed(event -> {
            finishClipboardTask();
            if (closing) { return; }
            clipboardReadFailed();
            Throwable failure = command.getException();
            report.accept(Objects.requireNonNullElse(failure.getMessage(), "剪贴板操作失败"));
        });
        updateControls();
        clipboardWorker.execute(command);
    }

    private void finishClipboardTask() {
        clipboardDeadline.stop();
        clipboardCommand = null;
    }

    private void observeClipboard(FileClipboard.State state) {
        if (!clipboardUnavailable && state.equals(clipboardState)) { return; }
        clipboardUnavailable = false;
        clipboardState = state;
        if (transfer != null && !transfer.state().equals(state)) { transfer = null; }
        if (externalExclusion != null && !externalExclusion.state().equals(state)) { externalExclusion = null; }
        if (unsupportedMove != null && !unsupportedMove.equals(state)) { unsupportedMove = null; }
        describeTransfer();
        verifyCutMarks();
        updateControls();
    }

    private void clipboardReadFailed() {
        if (clipboardUnavailable) { return; }
        clipboardUnavailable = true;
        describeTransfer();
        verifyCutMarks();
        updateControls();
    }

    private boolean clipboardHasFiles() {
        if (clipboardUnavailable || clipboardState == null || clipboardState.equals(unsupportedMove)) { return false; }
        if (transfer != null && transfer.state().equals(clipboardState)) { return !transfer.sources().isEmpty(); }
        return clipboardState.kind() == FileClipboard.Kind.FILES;
    }

    private void describeTransfer() {
        if (clipboardUnavailable) {
            clipboardStatus.setText("剪贴板暂不可读取");
            return;
        }
        if (clipboardState != null && clipboardState.equals(unsupportedMove)) {
            clipboardStatus.setText("外部剪切请改用复制");
            return;
        }
        if (transfer == null || transfer.sources().isEmpty()) {
            clipboardStatus.setText(clipboardState == null ? "" : switch (clipboardState.kind()) {
                case FILES -> transfer == null ? "系统剪贴板中的文件" : "此批次暂无可粘贴项目";
                case CUT -> "剪切仅限发起窗口";
                case UNSUPPORTED -> "此剪贴板格式不支持文件粘贴";
                case EMPTY -> "";
            });
            return;
        }
        String label = transfer.sources().size() == 1 ? transfer.sources().getFirst().entry().name()
                : transfer.sources().size() + " 个项目";
        clipboardStatus.setText((transfer.move() ? "已剪切（本窗口） · " : "待粘贴 · ") + label);
    }

    private void paste() {
        paste(activePane);
    }

    private void paste(BrowserPane pane) {
        if (pane.context.location.path() != null) { pasteInto(pane, pane.context.location.path(), null); }
    }

    private void pasteInto(BrowserPane pane, Path directory, BrowserItem folder) {
        if (fileCommandsUnavailable(pane) || clipboardCommand != null) { return; }
        var target = transferTarget(pane, directory, folder);
        var report = pane.listingReporter();
        report.accept("正在读取文件剪贴板…");
        runClipboardTask(task(ignored -> clipboard.read()), true, report, data -> {
            observeClipboard(data.state());
            var request = pasteRequest(data, report);
            if (request != null && pasteCanContinue(report)) { preparePaste(target, request, report); }
        });
    }

    private static PasteTarget transferTarget(BrowserPane pane, Path directory, BrowserItem folder) {
        BrowseContext owner = pane.context;
        return new PasteTarget(directory, directory.equals(owner.location.path()) ? owner.directoryIdentity : null,
                folder, folder == null ? null : owner.selectionProof.get(folder));
    }

    private void pressFileDrag(BrowserPane pane, MouseEvent event) {
        dragPress = null;
        if (event.getButton() != MouseButton.PRIMARY || pane.browser.editingName() || fileCommandsUnavailable(pane)) { return; }
        var hit = pane.browser.dragHit(event.getPickResult().getIntersectedNode());
        if (hit == null || hit.item() == null || !FileDrag.ordinary(hit.item())) { return; }
        var selection = pane.browser.selection();
        var owner = pane.context;
        dragPress = new DragPress(pane, owner, owner.listingVersion,
                selection.contains(hit.item()) ? selection : List.of(hit.item()), owner.selectionProof);
    }

    private FileDrag.Source createFileDrag(DragPress press) throws IOException {
        var pane = press.pane();
        if (!pane.shows(press.owner(), press.version()) || fileCommandsUnavailable(pane)) { return null; }
        Map<BrowserItem, Selection> proofs = new HashMap<>();
        for (BrowserItem item : press.items()) {
            var proof = press.proofs().get(item);
            if (proof != null) { proofs.put(item, proof); }
        }
        clearDragSource();
        dragSource = FileDrag.Source.create(pane.browser, press.items(), proofs);
        if (dragSource.verified()) { dragProofs = press.items().stream().map(proofs::get).toList(); }
        else { readDragSources(dragSource); }
        return dragSource;
    }

    private void readDragSources(FileDrag.Source source) {
        var read = task(cancelled -> {
            var entries = new ArrayList<Selection>(source.items().size());
            for (BrowserItem item : source.items()) {
                if (cancelled.getAsBoolean()) { throw new IOException("已取消拖放核验"); }
                var entry = captureSelected(item, source.proofs().get(item));
                if (entry.nativeItem() == null) { throw new IOException("拖放需要可验证的本地 NTFS 来源"); }
                entries.add(entry);
            }
            return List.copyOf(entries);
        });
        dragSourceRead = read;
        readDetail(read, entries -> {
            if (dragSourceRead == read) {
                dragSourceRead = null;
                dragProofs = entries;
            }
        }, failure -> {
            if (dragSourceRead == read) {
                dragSourceRead = null;
                dragSourceError = "来源已变化或无法核验，请刷新后重新选择";
            }
        });
    }

    private void startFileDrag(BrowserPane pane, MouseEvent event) {
        var press = dragPress;
        dragPress = null;
        // The primary press establishes ownership of the gesture.
        if (press == null || press.pane() != pane) { return; }
        try {
            var source = createFileDrag(press);
            if (source == null) { return; }
            var board = pane.browser.startDragAndDrop(TransferMode.COPY);
            if (board.setContent(dragContent(source))) {
                showDragStatus(dragHint());
                event.consume();
            } else {
                finishFileDrag();
            }
        } catch (IOException invalid) {
            pane.showStatus(invalid.getMessage());
        }
    }

    static ClipboardContent dragContent(FileDrag.Source source) {
        var content = new ClipboardContent();
        content.put(FileDrag.SESSION, source.token());
        if (source.verified()) { content.putFiles(source.paths().stream().map(Path::toFile).toList()); }
        return content;
    }

    private void finishFileDrag() {
        dragPress = null;
        clearDragSource();
        endDropHover();
    }

    private void clearDragSource() {
        if (dragSourceRead != null) { reads.cancel(dragSourceRead); }
        dragSourceRead = null;
        dragSource = null;
        dragProofs = List.of();
        dragSourceError = null;
    }

    private String dragHint() {
        if (dragSource == null) { return ""; }
        String count = "拖放 " + dragSource.items().size() + " 项";
        return count + (dragSource.verified() ? " · Shift 同卷移动，跨应用用 Ctrl 复制"
                : " · 窗口内可复制，移动或拖出前请先选中并核验");
    }

    private void showDragStatus(String text) {
        if (!dragStatus.getText().equals(text)) { dragStatus.setText(text); }
        dragStatus.setVisible(!text.isEmpty());
    }

    private void installFileDrop(Node surface, Function<Node, DropSite> locate) {
        surface.setOnDragOver(event -> fileDragOver(surface, locate, event));
        surface.setOnDragDropped(event -> fileDropped(locate, event));
        surface.setOnDragExited(event -> {
            if (event.getTarget() == surface && dropSurface == surface) { endDropHover(); }
        });
    }

    private DropSite tabDropSite(Node picked) {
        for (Node node = picked; node != null && node != tabs; node = node.getParent()) {
            if (node.getStyleClass().contains("tab-close-button")) { return null; }
            if (node.getStyleClass().contains("tab")) {
                String id = node.getId();
                for (var entry : tabHeaders.entrySet()) {
                    if (entry.getValue().getId().equals(id)) { return directoryDropSite(node, entry.getKey()); }
                }
                break;
            }
        }
        return null;
    }

    private DropSite directoryDropSite(Node mark, BrowseContext owner) {
        if (closing || fileTask != null || owner.closed || owner.directoryUnavailable || owner.location.page() != Page.DIRECTORY
                || !owner.activeQuery.isEmpty() || owner.directoryIdentity == null || owner.directoryIdentity.ntfs() == null
                || owner.browseRequest != null && !owner.browseRequest.intent().automatic()) { return null; }
        return new DropSite(mark, owner, 0, new PasteTarget(owner.location.path(), owner.directoryIdentity, null, null));
    }

    private DropSite browserDropSite(BrowserPane pane, Node picked) {
        if (fileCommandsUnavailable(pane) || pane.browser.editingName()) { return null; }
        var hit = pane.browser.dragHit(picked);
        if (hit == null) { return null; }
        var owner = pane.context;
        if (hit.item() == null) { return directoryDropSite(hit.mark(), owner); }
        var folder = hit.item();
        if (owner.location.page() != Page.DIRECTORY || owner.directoryIdentity == null
                || owner.directoryIdentity.ntfs() == null || !folder.directory() || !FileDrag.ordinary(folder)) { return null; }
        return new DropSite(hit.mark(), owner, owner.listingVersion, new PasteTarget(folder.path(), null, folder, null));
    }

    private FileDrag.Payload payload(DragEvent event) {
        var board = event.getDragboard();
        boolean marked = board.hasContent(FileDrag.SESSION);
        Object gesture = event.getGestureSource();
        boolean fromThisWindow = dragSource != null && dragSource.gesture() == gesture;
        return FileDrag.classify(dragSource, gesture, marked,
                marked && fromThisWindow ? board.getContent(FileDrag.SESSION) : null, !marked && board.hasFiles());
    }

    private void fileDragOver(Node surface, Function<Node, DropSite> locate, DragEvent event) {
        if (closing) { return; }
        if (dropBoard != event.getDragboard() || dropSurface != surface) {
            clearDropSite();
            dropBoard = event.getDragboard();
            dropSurface = surface;
            dropPayload = payload(event);
        }
        if (dropPayload == FileDrag.Payload.OTHER) { showDragStatus(dragHint()); return; }
        var site = locate.apply(event.getPickResult().getIntersectedNode());
        if (!Objects.equals(dropSite, site)) {
            clearDropSite();
            dropSite = site;
        }
        var intent = dragIntent.apply(event.getTransferMode());
        String restriction = dropRestriction(dropPayload, site, intent, event);
        if (restriction == null && site.target().folder() != null && dropFolderProof == null) {
            if (dropFolderError == null && dropFolderRead == null) { readDropFolder(site); }
            restriction = dropFolderError == null ? "正在核验目标文件夹…" : dropFolderError;
        }
        if (restriction == null) {
            event.acceptTransferModes(TransferMode.COPY);
            highlightDrop(event.isAccepted() ? intent : null);
            String count = dropPayload == FileDrag.Payload.INTERNAL ? " " + dragSource.items().size() + " 项" : "";
            String action = intent == FileDrag.Intent.MOVE ? "移动" : "复制";
            showDragStatus(event.isAccepted() ? action + count + " 到「" + Location.folder(site.target().path()).label() + "」"
                    : "来源不接受复制");
        } else {
            highlightDrop(null);
            showDragStatus(restriction);
        }
        if (surface instanceof BrowserView browser && dropPayload != FileDrag.Payload.INVALID
                && System.nanoTime() - lastDragScroll >= 90_000_000L) {
            browser.scrollDuringDrag(event.getScreenX(), event.getScreenY());
            lastDragScroll = System.nanoTime();
        }
        event.consume();
    }

    private String dropRestriction(FileDrag.Payload payload, DropSite site, FileDrag.Intent intent, DragEvent event) {
        if (payload == FileDrag.Payload.INVALID || payload == FileDrag.Payload.INTERNAL && dragSource == null) {
            return "拖放会话已失效，请重新拖动";
        }
        if (intent == FileDrag.Intent.LINK) { return "请选择复制或同卷移动"; }
        if (!event.getDragboard().getTransferModes().contains(TransferMode.COPY)
                || payload == FileDrag.Payload.EXTERNAL && intent == FileDrag.Intent.MOVE) {
            return "跨应用拖放仅支持复制，请按 Ctrl";
        }
        if (site == null) { return "请拖到普通目录、文件夹或有效目录标签"; }
        if (payload == FileDrag.Payload.INTERNAL) {
            var expected = site.target().knownDirectory();
            Long volume = expected != null ? Long.valueOf(expected.ntfs().id().volume()) : (
                    dropFolderProof == null ? null : Long.valueOf(dropFolderProof.nativeItem().id().volume()));
            String restriction = dragSource.restriction(site.target().path(), volume, intent);
            if (restriction != null) { return restriction; }
            if (dragProofs.isEmpty()) { return dragSourceError == null ? "正在核验拖放来源…" : dragSourceError; }
        }
        return null;
    }

    private void readDropFolder(DropSite site) {
        var folder = site.target().folder();
        var known = site.owner().selectionProof.get(folder);
        var listedIn = site.owner().directoryIdentity.ntfs();
        var read = task(cancelled -> {
            var proof = captureSelected(folder, known);
            if (proof.nativeItem() != null) {
                var parent = proof.nativeItem().parent();
                if (parent.path().equals(listedIn.path())) {
                    if (!parent.equals(listedIn)) { throw new IOException("目标文件夹的上级目录已变化"); }
                } else {
                    NtfsFileOperations.verify(listedIn);
                }
            }
            return proof;
        });
        dropFolderRead = read;
        readDetail(read, proof -> {
            if (dropFolderRead != read) { return; }
            dropFolderRead = null;
            if (proof.nativeItem() != null) { dropFolderProof = proof; }
            else { dropFolderError = "目标文件夹需要可验证的本地 NTFS 身份"; }
        }, failure -> {
            if (dropFolderRead == read) {
                dropFolderRead = null;
                dropFolderError = "目标文件夹已变化或无法核验";
            }
        });
    }

    private void fileDropped(Function<Node, DropSite> locate, DragEvent event) {
        var actual = payload(event);
        if (actual == FileDrag.Payload.OTHER) { return; }
        boolean accepted = false;
        try {
            var site = locate.apply(event.getPickResult().getIntersectedNode());
            var intent = dragIntent.apply(event.getTransferMode());
            if (closing || fileTask != null || actual != dropPayload || dropBoard != event.getDragboard()
                    || !Objects.equals(site, dropSite) || highlightedDrop != intent || site == null
                    || dropRestriction(actual, site, intent, event) != null) { return; }
            var target = site.target();
            if (target.folder() != null) {
                if (dropFolderProof == null) { return; }
                target = new PasteTarget(target.path(), null, target.folder(), dropFolderProof);
            }
            event.acceptTransferModes(TransferMode.COPY);
            if (!event.isAccepted()) { return; }
            var report = activePane.listingReporter();
            if (actual == FileDrag.Payload.INTERNAL) {
                var sources = dragProofs;
                clearDragSource();
                prepareDirectTransfer(target, intent == FileDrag.Intent.MOVE,
                        cancelled -> sources, report);
            } else {
                var files = event.getDragboard().getFiles();
                if (files == null || files.isEmpty() || files.size() > ClipboardFiles.MAX_ITEMS) {
                    throw new IOException("拖放的文件列表为空或超出数量限制");
                }
                var paths = ClipboardFiles.dragPaths(files.stream().map(File::getPath).toList());
                prepareDirectTransfer(target, false, cancelled -> captureExternalFiles(paths, cancelled), report);
            }
            accepted = true;
        } catch (IOException invalid) {
            activePane.showStatus(invalid.getMessage());
        } finally {
            endDropHover();
            event.setDropCompleted(accepted);
            event.consume();
        }
    }

    private void endDropHover() {
        clearDropSite();
        dropBoard = null;
        dropSurface = null;
        dropPayload = null;
        lastDragScroll = 0;
        showDragStatus(dragHint());
    }

    private void clearDropSite() {
        highlightDrop(null);
        dropSite = null;
        if (dropFolderRead != null) { reads.cancel(dropFolderRead); }
        dropFolderRead = null;
        dropFolderProof = null;
        dropFolderError = null;
    }

    private void highlightDrop(FileDrag.Intent intent) {
        if (dropSite != null && highlightedDrop != intent) {
            dropSite.mark().pseudoClassStateChanged(DROP_COPY, intent == FileDrag.Intent.COPY);
            dropSite.mark().pseudoClassStateChanged(DROP_MOVE, intent == FileDrag.Intent.MOVE);
        }
        highlightedDrop = intent;
    }

    private BrowserPane opposite(BrowserPane pane) {
        return pane == leftPane ? rightPane : leftPane;
    }

    private String transferRestriction(BrowserPane source) {
        if (!dualPaneOpen) { return "请先开启双栏"; }
        if (fileCommandsUnavailable(source)) { return "当前文件命令暂不可用"; }
        if (!canModify(source.browser.selection())) { return "请在活跃栏选择文件或文件夹"; }
        var target = opposite(source).context;
        if (target.location.page() != Page.DIRECTORY || !target.activeQuery.isEmpty()) {
            return "请在对栏打开普通目录";
        }
        if (target.directoryUnavailable || target.needsReload || target.directoryIdentity == null
                || target.browseRequest != null && !target.browseRequest.intent().automatic()) {
            return "对栏目录尚未核验，请等待读取或重新打开目录";
        }
        if (target.directoryIdentity.ntfs() == null) { return "对栏需要可验证的本地 NTFS 目录"; }
        if (source.context.activeQuery.isEmpty() && target.location.equals(source.context.location)) {
            return "两栏打开的是同一目录";
        }
        return null;
    }

    private void transferAcross(boolean move) {
        BrowserPane source = activePane;
        String restriction = transferRestriction(source);
        if (restriction != null) {
            source.showStatus(restriction);
            return;
        }
        BrowserPane opposite = opposite(source);
        PasteTarget target = transferTarget(opposite, opposite.context.location.path(), null);
        var selected = source.browser.selection();
        var known = source.context.selectionProof;
        prepareDirectTransfer(target, move, cancelled -> captureSelected(selected, known), source.listingReporter());
    }

    private void prepareDirectTransfer(PasteTarget target, boolean move, Work<List<Selection>> capture, Consumer<String> report) {
        runFileTask(task(cancelled -> {
            var destination = verifyDestination(target);
            var entries = capture.read(cancelled);
            validateDirectTransfer(entries, destination, move);
            return new PreparedPaste(destination, entries);
        }), "正在核对来源和目标…", false, prepared -> submitTransfer(prepared, move, report, ignored -> {}));
    }

    private static void validateDirectTransfer(List<Selection> sources, FileChanges.Destination destination,
                                               boolean move) throws IOException {
        if (destination.directory() == null) { throw new IOException("目标需要可验证的本地 NTFS 目录"); }
        Path target = destination.path().toRealPath();
        for (Selection source : sources) {
            if (source.nativeItem() == null) {
                throw new IOException("直接传输需要可验证的本地 NTFS 来源：" + source.path());
            }
            NtfsFileOperations.verify(source.nativeItem());
            if (Files.isSameFile(source.path().getParent(), target)) {
                throw new IOException("来源与目标目录相同：" + target);
            }
            if (source.entry().directory() && target.startsWith(source.path().toRealPath())) {
                throw new IOException("目标文件夹位于源文件夹内：" + target);
            }
            if (move && !NtfsFileOperations.sameVolume(source.nativeItem(), destination.directory())) {
                throw new IOException("直接移动需要同一 NTFS 卷上的可验证对象：" + source.path());
            }
        }
    }

    private PasteRequest pasteRequest(Snapshot data, Consumer<String> report) {
        if (transfer != null && transfer.matches(data)) {
            if (!transfer.sources().isEmpty()) { return new PasteRequest(data, transfer, List.of()); }
            report.accept("此批次暂无可粘贴项目，可在「核查结果」查看未知项");
            return null;
        }
        if (data.externalMove()) {
            unsupportedMove = data.state();
            describeTransfer();
            report.accept("未粘贴外部剪切项目");
            info("无法粘贴外部剪切", "请回到来源窗口，选中这些项目并使用「复制」，然后再粘贴。");
        } else if (data.state().kind() == FileClipboard.Kind.CUT) {
            report.accept("请返回发起剪切的窗口粘贴，跨窗口传输可使用复制");
        } else if (data.token() != null && data.token().equals(lastClipboardToken)) {
            report.accept("剪贴板会话已失效，请重新选择并复制");
        } else if (data.state().kind() == FileClipboard.Kind.FILES) {
            var paths = externalExclusion == null ? data.paths()
                    : data.paths().stream().filter(path -> !externalExclusion.paths().contains(path)).toList();
            if (!paths.isEmpty()) { return new PasteRequest(data, null, paths); }
            report.accept("此剪贴板批次的项目需要核查，请重新选择");
        } else {
            report.accept(data.state().kind() == FileClipboard.Kind.UNSUPPORTED
                    ? "仅支持有实际路径的文件和文件夹，请先保存此项目" : "剪贴板中没有可粘贴的文件");
        }
        return null;
    }

    private boolean pasteCanContinue(Consumer<String> report) {
        if (!closing && fileTask == null) { return true; }
        if (!closing) { report.accept("有其他文件任务正在执行，请重新发起粘贴"); }
        return false;
    }

    private void preparePaste(PasteTarget target, PasteRequest pending, Consumer<String> report) {
        runFileTask(task(cancelled -> {
            var destination = verifyDestination(target);
            if (pending.local() != null) { return new PreparedPaste(destination, pending.local().sources()); }
            return new PreparedPaste(destination, captureExternalFiles(pending.external(), cancelled));
        }), "正在核对粘贴来源和目标…", false, prepared -> {
            if (pasteCanContinue(report)) {
                submitTransfer(prepared, pending.move(), report, outcome -> clipboardAfterPaste(pending, prepared.sources(), outcome));
            }
        });
    }

    private static List<Selection> captureExternalFiles(List<Path> paths, BooleanSupplier cancelled) throws IOException {
        var sources = new ArrayList<Selection>();
        for (Path path : paths) {
            if (cancelled.getAsBoolean()) { throw new IOException("已取消传输准备"); }
            var entry = LocalFiles.describe(path);
            if (entry.link() || entry.other()) { throw new IOException("来源含链接或特殊项目，请重新选择：" + path); }
            sources.add(FileChanges.capture(entry));
        }
        return List.copyOf(sources);
    }

    private FileChanges.Destination verifyDestination(PasteTarget target) throws IOException {
        requireVisible(target.path());
        var destination = FileChanges.destination(target.path());
        var expected = target.knownDirectory();
        if (expected != null && expected.known() && (expected.ntfs() != null
                ? !expected.ntfs().equals(destination.directory())
                : !expected.equals(DirectoryIdentity.read(target.path())))) {
            throw new IOException("目标文件夹已变化，请重新选择");
        }
        if (target.folder() != null && target.folder().entry() != null) {
            var folder = captureSelected(target.folder(), target.knownFolder());
            if (folder.nativeItem() != null && (destination.directory() == null
                    || !folder.nativeItem().id().equals(destination.directory().id()))) {
                throw new IOException("目标文件夹已变化，请重新选择");
            }
        }
        return destination;
    }

    private void submitTransfer(PreparedPaste prepared, boolean move, Consumer<String> report, Consumer<Outcome> completed) {
        var destination = prepared.destination();
        var sources = prepared.sources();
        Predicate<Selection> unverified = source -> source.needsPathConfirmation(destination, move);
        if (!confirmCurrentPaths(sources, unverified, move ? "移动" : "复制", false)) {
            report.accept("");
            return;
        }
        List<Change> changes = sources.stream()
                .map(source -> FileChanges.transfer(source, destination, move, unverified.test(source))).toList();
        applyChanges(move ? Kind.MOVE : Kind.COPY, move ? "正在移动…" : "正在复制…",
                move ? "已移动" : "已复制", changes, completed, () -> {});
    }

    private void verifyCutMarks() {
        if (!closing) { visiblePanes().forEach(BrowserPane::verifyCutMarks); }
    }

    private void clipboardAfterPaste(PasteRequest request, List<Selection> sources, Outcome outcome) {
        Transfer local = request.local();
        if (local == null) {
            if (outcome.count(State.UNKNOWN) > 0 && request.snapshot().state().equals(clipboardState)) {
                var excluded = new HashSet<Path>();
                if (externalExclusion != null) { excluded.addAll(externalExclusion.paths()); }
                for (int i = 0; i < sources.size(); i++) {
                    if (outcome.items().get(i).state() == State.UNKNOWN) { excluded.add(sources.get(i).path()); }
                }
                externalExclusion = new ExternalExclusion(clipboardState, Set.copyOf(excluded));
            }
            return;
        }
        if (transfer != local) { return; }
        var remaining = local.after(outcome);
        transfer = remaining;
        describeTransfer();
        visiblePanes().forEach(pane -> pane.browser.cutItems(Set.of()));
        verifyCutMarks();
        if (remaining.sources().size() == local.sources().size()) { return; }
        // Partial cuts keep their private marker and update their in-memory list.
        if (local.move() && !remaining.sources().isEmpty()) { return; }
        FileClipboard.Offer replacement = remaining.sources().isEmpty() ? null : remaining.offer();
        runClipboardTask(task(ignored -> clipboard.replace(local.state(), local.token(), replacement)), false, clipboardStatus::setText, result -> {
            if (result.applied() && transfer == remaining) {
                transfer = remaining.sources().isEmpty() ? null
                        : new Transfer(result.state(), remaining.token(), remaining.move(), remaining.sources());
            }
            observeClipboard(result.state());
            describeTransfer();
        });
    }

    private void trashSelected() {
        trashSelected(activePane);
    }

    private void trashSelected(BrowserPane pane) {
        List<BrowserItem> selected = pane.browser.selection();
        if (!canModify(selected) || fileCommandsUnavailable(pane)) { return; }
        var report = pane.listingReporter();
        captureSelection(pane, selected, entries -> {
            if (!confirmCurrentPaths(entries, Selection::needsPathConfirmation, "移到回收站", true)) {
                report.accept("");
                return;
            }
            List<Change> changes = entries.stream()
                    .map(entry -> FileChanges.recycle(entry, entry.needsPathConfirmation(), LocalFiles::desktopTrash)).toList();
            applyChanges(Kind.RECYCLE, "正在移到回收站…", "已移到回收站", changes, ignored -> { }, () -> {});
        });
    }

    private boolean confirmCurrentPaths(List<Selection> entries, Predicate<Selection> needsApproval, String action, boolean recycle) {
        List<Selection> unverified = entries.stream().filter(needsApproval).toList();
        if (unverified.isEmpty() && !recycle) { return true; }
        String name = entries.size() == 1 ? "「" + entries.getFirst().entry().name() + "」" : entries.size() + " 个项目";
        String message = unverified.isEmpty()
                ? "将" + name + "移到回收站？"
                : "有 " + unverified.size() + " 个项目需要按路径处理。请确认要" + action + "下列路径当前的项目。";
        if (recycle) {
            message += "\n系统回收站按路径处理，确认后请避免同时改动这些项目。";
        }
        ButtonType proceed = new ButtonType(unverified.isEmpty() ? "移到回收站" : "按当前路径继续",
                ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, proceed);
        confirm.initOwner(stage);
        confirm.setTitle(action);
        confirm.setHeaderText(null);
        if (!unverified.isEmpty()) {
            TextArea paths = new TextArea(String.join("\n", unverified.stream().map(entry -> entry.path().toString()).toList()));
            paths.setEditable(false);
            paths.setPrefRowCount(Math.min(unverified.size(), 6));
            confirm.getDialogPane().setExpandableContent(paths);
            confirm.getDialogPane().setExpanded(true);
            ((Button) confirm.getDialogPane().lookupButton(proceed)).setDefaultButton(false);
            ((Button) confirm.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        }
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == proceed;
    }

    private void applyChanges(Kind kind, String running, String complete, List<Change> changes, Consumer<Outcome> callback,
                              Runnable reloaded) {
        runFileOperation(kind, changes, running, outcome -> {
            if (outcome.count(State.APPLIED) > 0) {
                undo = outcome.reversals();
            }
            callback.accept(outcome);
            afterWrite(kind, complete, outcome, reloaded);
        });
    }

    private void undo() {
        if (fileTask != null || closing || undo.isEmpty()) { return; }
        runFileOperation(Kind.UNDO, undo, "正在撤消…", outcome -> {
            undo = outcome.pending();
            afterWrite(Kind.UNDO, "已撤消", outcome, () -> {});
        });
    }

    private void runFileOperation(Kind kind, List<Change> changes, String running, Consumer<Outcome> complete) {
        if (fileTask != null || closing) { return; }
        var operation = operationFactory.apply(kind, changes);
        operation.observeStagingWith(staging);
        operation.enableConflictResolution();
        activeFileOperation = operation;
        operationProgress.start(operation);
        Task<Outcome> worker = task(ignored -> operation.run());
        worker.addEventHandler(WorkerStateEvent.WORKER_STATE_FAILED, event -> {
            activeFileOperation = null;
            if (!closing) { operationProgress.failed(worker.getException()); }
        });
        runFileTask(worker, running, true, outcome -> {
            operationProgress.finish(outcome);
            activeFileOperation = null;
            complete.accept(outcome);
        });
    }

    private void afterWrite(Kind kind, String completed, Outcome outcome, Runnable reloaded) {
        unknownOperations.addAll(outcome.unknownOperations(FileOperationProgress.operationTitle(kind)));
        unknownResults.setVisible(!unknownOperations.isEmpty());
        recordFolderEdits(outcome.items().stream().filter(item -> item.state() == State.APPLIED)
                .flatMap(item -> item.change().folders().stream()));
        IOException error = outcome.error();
        String message = outcome.summary(completed);
        if (!outcome.issues().isEmpty()) { message += " · 查看文件任务详情"; }
        refreshAffected(BrowseImpact.from(kind, outcome), message, error == null ? reloaded : () -> {});
        if (outcome.count(State.UNKNOWN) > 0) {
            showError(new IOException(error.getMessage() + "\n可用状态栏的「核查结果」查看路径并重新选择。", error));
        } else if (error != null) {
            showError(error);
        }
    }

    private void refreshAffected(BrowseImpact impact, String message, Runnable reloaded) {
        for (BrowseContext owner : tabHeaders.keySet()) {
            if (owner != leftPane.context && dependsOn(impact, owner.location, owner.activeQuery, owner.homeTab)) {
                owner.refreshOnReturn = message;
            }
        }
        if (!dualPaneOpen && rightPane != null) {
            BrowseContext owner = rightPane.context;
            if (dependsOn(impact, owner.location, owner.activeQuery, owner.homeTab)) {
                owner.refreshOnReturn = message;
            }
        }
        boolean sidebarAffected = false;
        for (int index = 0; index < tree.getExpandedItemCount(); index++) {
            var branch = tree.getTreeItem(index);
            if (branch != null && branch.isExpanded() && branch.getValue().location() != null) {
                Path path = branch.getValue().location().path();
                if (path != null && impact.affectsDirectory(path, false)) {
                    sidebarAffected = true;
                    break;
                }
            }
        }
        if (sidebarAffected) { rebuildSidebar(); }
        for (BrowserPane pane : visiblePanes()) { refreshAffected(pane, impact, message, reloaded); }
    }

    private void refreshAffected(BrowserPane pane, BrowseImpact impact, String message, Runnable reloaded) {
        BrowseContext owner = pane.context;
        boolean affectsCurrent = dependsOn(impact, owner.location, owner.activeQuery, owner.homeTab);
        if (affectsCurrent && (pane.browser.editingName()
                || owner.browseRequest != null && owner.browseRequest.intent().automatic())) {
            owner.pendingFileRefresh = new FileRefresh(message, reloaded);
            return;
        }
        if (affectsCurrent) { owner.pendingFileRefresh = null; }
        if (owner.browseRequest == null) {
            if (affectsCurrent && owner.needsReload) { pane.reloadContext(message); }
            else if (affectsCurrent) { pane.request(owner.location, owner.activeQuery, message, reloaded); }
            else if (pane == activePane) { pane.updateListingStatus(message); }
        } else {
            if (affectsCurrent) { owner.deferredRefresh = message; }
            BrowseIntent pending = owner.browseRequest.intent();
            if (dependsOn(impact, pending.location(), pending.query(), pending.tab())) {
                pane.startBrowse(pending);
            }
        }
    }

    private boolean dependsOn(BrowseImpact impact, Location target, String query, HomeTab tab) {
        return switch (target.page()) {
            case DIRECTORY -> impact.affectsDirectory(target.path(), !query.isEmpty());
            case GALLERY -> galleryFolders.stream().anyMatch(path -> impact.affectsDirectory(path, true));
            case HOME -> switch (tab) {
                case RECENT -> recentFiles.keySet().stream().anyMatch(impact::affectsEntry)
                        || places.recent().stream().anyMatch(recent -> impact.affectsEntry(recent.path()));
                case FAVORITES -> favorites.stream().anyMatch(impact::affectsEntry);
                case SHARED -> false;
            };
            default -> false;
        };
    }

    private void inspectUnknownResults() {
        if (closing || unknownOperations.isEmpty()) { return; }
        var records = List.copyOf(unknownOperations.reversed());
        readDetail(task(ignored -> {
            Map<FileChanges.UnknownOperation, List<LocalFiles.Observation>> observations = new LinkedHashMap<>();
            for (var operation : records) { observations.put(operation, LocalFiles.observe(operation.paths())); }
            return observations;
        }), observations -> UnknownResultsDialog.show(stage, observations)
                .ifPresent(this::locateReportedPath), this::showError);
    }

    private void locateReportedPath(Path path) {
        if (path.getParent() == null || closing) { return; }
        Location target = Location.folder(path.getParent());
        BrowserPane pane = activePane;
        BrowseContext owner = pane.context;
        Location previous = owner.location;
        pane.request(target, "", "请在当前目录核查项目", () -> {
            if (!target.equals(previous)) {
                owner.past.addFirst(previous);
                owner.future.clear();
            }
            pane.browser.reveal(path);
        });
    }

    private void directoryChanged(DirectoryWatcher.Notice notice) {
        if (!closing) { visiblePanes().forEach(pane -> pane.directoryChanged(notice)); }
    }

    private void flushWatchRefresh() {
        if (!closing) { visiblePanes().forEach(BrowserPane::flushWatchRefresh); }
    }

    private <T> void readDetail(Task<T> task, Consumer<T> success, Consumer<Throwable> failure) {
        if (closing) { return; }
        task.setOnSucceeded(event -> { if (!closing) { success.accept(task.getValue()); } });
        task.setOnFailed(event -> { if (!closing) { failure.accept(task.getException()); } });
        if (!reads.detail(task)) { failure.accept(new IOException("读取队列暂满，请稍后重试")); }
    }

    private <T> void runFileTask(Task<T> task, String running, boolean writesDisk, Consumer<T> success) {
        if (fileTask != null || closing) { return; }
        fileTask = task;
        writing = writesDisk;
        writeNotice.setText(running);
        updateControls();
        task.setOnSucceeded(event -> {
            finishFileTask();
            if (!closing) {
                success.accept(task.getValue());
                updateControls();
                flushWatchRefresh();
            }
        });
        task.setOnFailed(event -> {
            finishFileTask();
            if (!closing) {
                updateControls();
                showError(task.getException());
                flushWatchRefresh();
            }
        });
        task.setOnCancelled(event -> {
            finishFileTask();
            if (!closing) {
                updateControls();
                flushWatchRefresh();
            }
        });
        writes.execute(task);
    }

    private void finishFileTask() {
        fileTask = null;
        writing = false;
    }

    private void updateControls() {
        boolean unavailable = closing || places == null;
        boolean commandsUnavailable = fileCommandsUnavailable();
        boolean writableFolder = !commandsUnavailable && activePane.context.location.path() != null;
        unknownResults.setDisable(unavailable);
        writeNotice.setVisible(fileTask != null);
        List<BrowserItem> selected = activePane.browser.selection();
        boolean editable = !commandsUnavailable && canModify(selected);
        tree.setDisable(unavailable);
        syncNavigationPane();
        visiblePanes().forEach(pane -> pane.updateNavigationControls(unavailable));
        controls.get("new").setDisable(unavailable || !canCreate());
        controls.get("copy").setDisable(!editable || clipboardCommand != null);
        controls.get("cut").setDisable(!editable || clipboardCommand != null);
        controls.get("trash").setDisable(!editable);
        controls.get("rename").setDisable(!editable || selected.size() != 1);
        controls.get("paste").setDisable(!writableFolder || clipboardCommand != null || !clipboardHasFiles());
        for (String id : List.of("sort", "view", "more", "gallery-sources")) { controls.get(id).setDisable(unavailable); }
        controls.get("gallery-sources").setVisible(activePane.context.location.page() == Page.GALLERY);
        var layout = (MenuButton) controls.get("browse-layout");
        String scope = dualPaneOpen ? activePane.heading.getText() : "";
        layout.setText(scope + "布局 · " + activePane.effectiveLayout().label);
        layout.setDisable(unavailable || !activePane.supportsProfessionalLayout());
        layout.getTooltip().setText(activePane.supportsProfessionalLayout()
                ? "更改" + (dualPaneOpen ? scope : "当前标签页") + "的浏览布局" : "当前页面使用传统布局");
        controls.get("details-pane").setDisable(places == null);
        controls.get("dual-pane").setDisable(unavailable);
        ((Button) controls.get("dual-pane")).setText(dualPaneOpen ? "单栏" : "双栏");
        String restriction = transferRestriction(activePane);
        setAcrossCommand("copy-across", "复制到对栏（Ctrl+Shift+F5）", restriction);
        setAcrossCommand("move-across", "移动到对栏（Ctrl+Shift+F6）", restriction);
        progress.setVisible((activePane.context.browseRequest != null && !activePane.context.browseRequest.intent().automatic()) || fileTask != null || catalogTask != null);
    }

    private void setAcrossCommand(String id, String tooltip, String restriction) {
        Button button = (Button) controls.get(id);
        button.setVisible(dualPaneOpen);
        button.setDisable(restriction != null);
        button.getTooltip().setText(restriction == null ? tooltip : restriction);
    }

    private boolean fileCommandsUnavailable() {
        return fileCommandsUnavailable(activePane);
    }

    private boolean fileCommandsUnavailable(BrowserPane pane) {
        BrowseContext owner = pane.context;
        return !visible(pane) || places == null || fileTask != null || owner.directoryUnavailable
                || owner.needsReload
                || (owner.browseRequest != null && !owner.browseRequest.intent().automatic());
    }

    private static boolean canModify(List<BrowserItem> items) {
        return !items.isEmpty() && items.stream().allMatch(item -> item.entry() != null && item.path().getParent() != null);
    }

    private void chooseText(String caption, String label, String initial, Consumer<String> action) {
        Dialog<String> dialog = new Dialog<>();
        dialog.initOwner(stage);
        dialog.setTitle(caption);
        TextField input = new TextField(initial);
        input.setId("command-input");
        Label prompt = new Label(label);
        prompt.setLabelFor(input);
        HBox fields = new HBox(10, prompt, input);
        fields.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(input, Priority.ALWAYS);
        dialog.getDialogPane().setContent(fields);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> button == ButtonType.OK ? input.getText() : null);
        dialog.setOnShown(event -> Platform.runLater(() -> { input.requestFocus(); input.selectAll(); }));
        dialog.setOnHidden(event -> {
            if (dialog.getResult() != null) {
                try { action.accept(dialog.getResult()); }
                catch (IllegalArgumentException exception) { showError(exception); }
            }
        });
        dialog.show();
    }

    private void showError(Throwable exception) {
        String detail = switch (exception) {
            case FileAlreadyExistsException error -> "已存在同名项目：" + error.getFile();
            case NoSuchFileException error -> "找不到文件或文件夹：" + error.getFile();
            case AccessDeniedException error -> "没有访问权限：" + error.getFile();
            case FileSystemException error -> error.getMessage();
            default -> Objects.requireNonNullElse(exception.getMessage(), exception.getClass().getSimpleName());
        };
        Alert alert = new Alert(Alert.AlertType.ERROR, detail, ButtonType.OK);
        alert.initOwner(stage);
        alert.setTitle("文件操作");
        alert.setHeaderText("无法完成操作");
        alert.show();
    }

    private void info(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setResizable(true);
        alert.show();
    }

    private List<Path> loadPaths(String key) {
        return preferences.get(key, "").lines().map(FileManagerApp::savedPath).filter(Objects::nonNull).toList();
    }

    private static Path savedPath(String value) {
        try {
            Path path = Path.of(value);
            return path.isAbsolute() ? path : null;
        } catch (InvalidPathException malformed) {
            return null;
        }
    }

    private void savePaths(String key, Collection<Path> paths) {
        saveLines(key, paths.stream().map(Path::toString));
    }

    private void loadVisits(String key, Map<Path, FileTime> destination) {
        preferences.get(key, "").lines().forEach(line -> {
            int separator = line.indexOf('\t');
            if (separator <= 0) { return; }
            Path path = savedPath(line.substring(separator + 1));
            if (path == null) { return; }
            try {
                destination.put(path, FileTime.fromMillis(Long.parseLong(line.substring(0, separator))));
            } catch (NumberFormatException malformed) {
                // Keep the other valid history entries.
            }
        });
    }

    private void rememberVisit(String key, Map<Path, FileTime> visits, Path path) {
        visits.remove(path);
        visits.put(path, FileTime.from(Instant.now()));
        if (visits.size() > 30) { visits.remove(visits.keySet().iterator().next()); }
        saveLines(key, visits.entrySet().stream().map(visit -> visit.getValue().toMillis() + "\t" + visit.getKey()));
    }

    private void recordFolderEdits(Stream<Path> folders) {
        List<Path> paths = folders.map(path -> path.toAbsolutePath().normalize()).distinct().toList();
        if (paths.isEmpty()) { return; }
        FileTime time = FileTime.from(Instant.now());
        for (Path path : paths) {
            folderEdits.remove(path);
            folderEdits.put(path, time);
        }
        while (folderEdits.size() > 30) {
            var oldest = Collections.min(folderEdits.entrySet(), Map.Entry.comparingByValue());
            folderEdits.remove(oldest.getKey());
        }
        saveLines("folderEdits", folderEdits.entrySet().stream().sorted(Map.Entry.<Path, FileTime>comparingByValue().reversed())
                .map(edit -> edit.getValue().toMillis() + "\t" + edit.getKey()));
        refreshModifiedFolders();
    }

    private void saveLines(String key, Stream<String> lines) {
        StringBuilder value = new StringBuilder();
        lines.forEach(line -> {
            if (value.length() + line.length() + 1 <= Preferences.MAX_VALUE_LENGTH) { value.append(line).append('\n'); }
        });
        preferences.put(key, value.toString());
    }

    private Button button(String label, String id, Runnable action) {
        Button button = new Button(label);
        button.setId(id);
        button.setOnAction(event -> action.run());
        controls.put(id, button);
        return button;
    }

    private MenuButton menuButton(String label, String id, java.util.function.Supplier<List<MenuItem>> items) {
        MenuButton button = new MenuButton(label);
        button.setId(id);
        button.setOnShowing(event -> button.getItems().setAll(items.get()));
        controls.put(id, button);
        return button;
    }

    private void fire(String command) { ((Button) controls.get(command)).fire(); }

    private static Menu submenu(String name, List<MenuItem> items) {
        Menu menu = new Menu(name);
        menu.getItems().setAll(items);
        return menu;
    }

    private static MenuItem menuItem(String name, String id, Runnable action) { return menuItem(name, id, action, false); }

    private static MenuItem menuItem(String name, String id, Runnable action, boolean disabled) {
        MenuItem item = new MenuItem(name);
        item.setId(id);
        item.setDisable(disabled);
        item.setOnAction(event -> action.run());
        return item;
    }

    private static MenuItem iconMenu(String name, String id, UiGlyph glyph, Runnable action, boolean disabled) {
        MenuItem item = menuItem(name, id, action, disabled);
        item.setGraphic(glyph.graphic(15));
        return item;
    }

    private Menu appearanceMenu() {
        ToggleGroup themes = new ToggleGroup();
        MenuItem light = radio("浅色", themes, theme == UiTheme.LIGHT, () -> selectTheme(UiTheme.LIGHT));
        light.setId("theme-light");
        MenuItem dark = radio("深色", themes, theme == UiTheme.DARK, () -> selectTheme(UiTheme.DARK));
        dark.setId("theme-dark");
        return submenu("外观预览", List.of(light, dark));
    }

    private void selectTheme(UiTheme next) {
        if (theme != next) {
            theme = next;
            theme.applyTo(stage);
        }
    }

    private static MenuItem radio(String name, ToggleGroup group, boolean selected, Runnable action) {
        RadioMenuItem item = new RadioMenuItem(name);
        item.setToggleGroup(group);
        item.setSelected(selected);
        item.setOnAction(event -> action.run());
        return item;
    }

    private static MenuItem checkItem(String name, String id, boolean selected, Consumer<Boolean> action) {
        CheckMenuItem item = new CheckMenuItem(name);
        item.setId(id);
        item.setSelected(selected);
        item.setOnAction(event -> action.accept(item.isSelected()));
        return item;
    }

    private final class BrowserPane {
        final VBox content = new VBox();
        final VBox navigation = new VBox();
        final HBox addressRow = new HBox(10);
        final HBox searchRow = new HBox(8);
        final AddressBar address = new AddressBar(this::navigateFromAddress, FileManagerApp.this::addressHistory);
        final TextField searchField = new TextField();
        final Label heading;
        final VBox overview = new VBox(13);
        final StackPane browserHost = new StackPane();
        final VBox inspector = new VBox(10);
        final BrowserView browser;
        final MillerColumns hierarchy;
        final SplitPane hybrid = new SplitPane();
        Layout mountedLayout = Layout.TRADITIONAL;
        final Map<String, Button> navigationControls = new LinkedHashMap<>();
        final DirectoryWatcher watcher = new DirectoryWatcher(DirectoryIdentity::read, staging::contains,
                notice -> Platform.runLater(() -> FileManagerApp.this.directoryChanged(notice)));
        BrowseContext context = new BrowseContext(++nextTabId);
        String statusText = "";
        String watchText = automaticRefresh ? "手动刷新" : "自动刷新已关闭";

        BrowserPane(String id, String title) {
            heading = new Label(title);
            heading.getStyleClass().add("browser-heading");
            content.setId(id);
            content.getStyleClass().add("browser-pane");
            content.setMinWidth(260);
            browser = new BrowserView(icons, thumbnails, this::selectionChanged, this::open, this::sort,
                    item -> contextMenu(this, item), () -> updateListingStatus(""),
                    this::homeHeader, this::flushWatchRefresh);
            browser.setMinWidth(0);
            browser.setMinHeight(100);
            hierarchy = new MillerColumns(reads, this::readAncestor, path -> {
                browser.dismissTransientControls();
                browser.clearSelection();
                navigate(Location.folder(path));
            }, () -> {
                browser.cancelNewName();
                if (!browser.selection().isEmpty()) { browser.clearSelection(); }
            }, icons);
            hybrid.setId("hybrid-browser");
            hybrid.setMinWidth(0);
            hybrid.setOrientation(Orientation.VERTICAL);
            hybrid.getStyleClass().add("hybrid-browser");
            browser.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> pressFileDrag(this, event));
            browser.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> dragPress = null);
            // File cells can consume DRAG_DETECTED before it bubbles to the browser.
            browser.addEventFilter(MouseEvent.DRAG_DETECTED, event -> startFileDrag(this, event));
            browser.setOnDragDone(event -> {
                if (event.getGestureSource() == browser) { finishFileDrag(); }
            });
            installFileDrop(browser, picked -> browserDropSite(this, picked));
            configureNavigation();
            useSingleNavigation();
            overview.getStyleClass().add("overview");
            overview.managedProperty().bind(overview.visibleProperty());
            inspector.setId("inspector");
            inspector.getStyleClass().add("inspector");
            inspector.setMinWidth(174);
            inspector.setPrefWidth(238);
            browserHost.getChildren().add(browser);
            content.getChildren().addAll(overview, browserHost);
            VBox.setVgrow(browserHost, Priority.ALWAYS);
            content.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> activatePane(this));
            navigation.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> activatePane(this));
            browser.addEventFilter(KeyEvent.KEY_PRESSED, this::browserKey);
        }

        boolean contains(Node node) {
            for (Node current = node; current != null; current = current.getParent()) {
                if (current == content || current == navigation) { return true; }
            }
            return false;
        }

        private void browserKey(KeyEvent event) {
            if (event.getTarget() instanceof TextInputControl || !visible(this)) { return; }
            switch (event.getCode()) {
                case ENTER -> open(selectedItem());
                case F2 -> { activatePane(this); renameSelected(); }
                case DELETE -> { activatePane(this); trashSelected(); }
                case A -> { if (!event.isShortcutDown()) { return; } browser.selectAll(); }
                case Z -> { if (!event.isShortcutDown()) { return; } undo(); }
                default -> { return; }
            }
            event.consume();
        }

        private void savePresentation() {
            if (!context.needsReload) { context.presentation = browser.presentation(); }
        }

        private void suspend() {
            if (dragSource != null && dragSource.gesture() == browser) { finishFileDrag(); }
            else if (dropSite != null && dropSite.owner() == context) { endDropHover(); }
            browser.dismissTransientControls();
            savePresentation();
            releaseBrowse(context);
            watcher.pause();
            inspector.getChildren().clear();
            browser.cutItems(Set.of());
            if (places != null) { render(false); }
        }

        private void restoreVisible() {
            browser.usePresentation(context.presentation);
            browser.cutItems(Set.of());
            updateWatchStatus(automaticRefresh ? "手动刷新" : "自动刷新已关闭");
            if (places != null) {
                updateLocation();
                render(false);
                reloadContext(context.refreshOnReturn);
            }
            if (activePane == this) { updateInspectorHost(); }
        }

        private void showStatus(String message) {
            statusText = message;
            if (activePane == this) { status.setText(message); }
        }

        private Consumer<String> listingReporter() {
            BrowseContext owner = context;
            long version = owner.listingVersion;
            return message -> { if (shows(owner, version)) { updateListingStatus(message); } };
        }

        void mountNavigation() {
            addressRow.getChildren().remove(searchRow);
            addressRow.getChildren().add(navigationControls.get("toggle-search"));
            addressRow.setSpacing(6);
            address.setMinWidth(72);
            searchField.setPrefWidth(150);
            navigation.getStyleClass().add("split-navigation");
            navigation.getChildren().setAll(heading, addressRow, searchRow);
            if (!content.getChildren().contains(navigation)) { content.getChildren().addFirst(navigation); }
            updateSearchVisibility();
        }

        void useSingleNavigation() {
            navigation.getStyleClass().remove("split-navigation");
            navigation.getChildren().setAll(addressRow);
            addressRow.getChildren().remove(navigationControls.get("toggle-search"));
            addressRow.setSpacing(10);
            address.setMinWidth(180);
            searchField.setPrefWidth(192);
            if (!addressRow.getChildren().contains(searchRow)) { addressRow.getChildren().add(searchRow); }
            updateSearchVisibility();
        }

        Button button(String label, String id, Runnable action) {
            Button button = new Button(label);
            button.setId(id);
            button.setOnAction(event -> action.run());
            navigationControls.put(id, button);
            return button;
        }

        Button navButton(UiGlyph icon, String help, String id, Runnable action) {
            Button button = button(null, id, action);
            button.setGraphic(icon.graphic(17));
            button.getStyleClass().addAll("icon-button", "nav-button");
            button.setTooltip(new Tooltip(help));
            button.setAccessibleText(help);
            return button;
        }

        void refreshCommand() {
            if (places == null || closing) { return; }
            if (context.location.page() == Page.COMPUTER) { loadPlaces(false); }
            else {
                refreshModifiedFolders();
                refresh("");
            }
        }

        private void releaseBrowse(BrowseContext owner) {
            cancelBrowse(owner);
            if (owner == context) { hierarchy.clear(); }
            for (Task<?> task : new Task<?>[]{owner.addressRead, owner.previewTask, owner.selectionRead, owner.cutMarkRead}) {
                if (task != null) { reads.cancel(task); }
            }
            owner.addressRead = null;
            owner.previewTask = null;
            owner.selectionRead = null;
            owner.cutMarkRead = null;
            owner.previewLoaded = false;
            owner.watchTarget = null;
            owner.pendingChanges = null;
            owner.pendingFileRefresh = null;
            owner.deferredRefresh = null;
            owner.listing = Listing.EMPTY;
            owner.needsReload = true;
            owner.listingVersion++;
        }

        private void configureNavigation() {
            HBox arrows = new HBox(4,
                    navButton(UiGlyph.BACK, "后退", "back", () -> history(context.past, context.future)),
                    navButton(UiGlyph.FORWARD, "前进", "forward", () -> history(context.future, context.past)),
                    navButton(UiGlyph.UP, "上一级", "up", () -> navigate(parentLocation())),
                    navButton(UiGlyph.REFRESH, "刷新", "refresh", this::refreshCommand));
            HBox.setHgrow(address, Priority.ALWAYS);
            searchField.setId("search-field");
            searchField.setMinWidth(54);
            searchField.setOnAction(event -> search());
            navButton(UiGlyph.SEARCH, "展开搜索", "toggle-search", () -> {
                context.searchExpanded = !context.searchExpanded;
                updateSearchVisibility();
                if (context.searchExpanded) { searchField.requestFocus(); }
            });
            searchRow.managedProperty().bind(searchRow.visibleProperty());
            Button search = button("搜索", "search", this::search);
            search.setGraphic(UiGlyph.SEARCH.graphic(15));
            search.setMinWidth(Region.USE_PREF_SIZE);
            Button stop = button("取消搜索", "cancel-search", () -> {
                if (isSearching()) { cancelBrowse(); settleBrowse(""); }
            });
            stop.managedProperty().bind(stop.visibleProperty());
            Button clear = button("退出搜索", "clear-search", () -> request(context.location, "", "", () -> {}));
            clear.managedProperty().bind(clear.visibleProperty());
            HBox.setHgrow(searchField, Priority.ALWAYS);
            searchRow.getChildren().addAll(searchField, search, stop, clear);
            searchRow.setAlignment(Pos.CENTER_LEFT);
            addressRow.getChildren().addAll(arrows, address);
            addressRow.setAlignment(Pos.CENTER_LEFT);
            navigation.getStyleClass().add("navigation");
        }

        private void updateSearchVisibility() {
            searchRow.setVisible(!dualPaneOpen || context.searchExpanded || isSearching());
            Button toggle = navigationControls.get("toggle-search");
            String label = context.searchExpanded ? "收起搜索" : "展开搜索";
            toggle.setAccessibleText(label);
            toggle.getTooltip().setText(label);
        }

        private void updateNavigationControls(boolean unavailable) {
            overview.setDisable(unavailable);
            address.setDisable(unavailable);
            searchField.setDisable(unavailable);
            navigationControls.get("back").setDisable(unavailable || context.past.isEmpty());
            navigationControls.get("forward").setDisable(unavailable || context.future.isEmpty());
            navigationControls.get("up").setDisable(unavailable || parentLocation() == null);
            navigationControls.get("refresh").setDisable(unavailable);
            navigationControls.get("search").setDisable(unavailable);
            navigationControls.get("toggle-search").setDisable(unavailable || isSearching());
            updateSearchVisibility();
            navigationControls.get("cancel-search").setVisible(isSearching());
            navigationControls.get("clear-search").setVisible(!isSearching() && !context.activeQuery.isEmpty());
            navigationControls.get("clear-search").setDisable(unavailable);
        }

        private void navigateFromAddress(String input) {
            if (places == null || !visible(this)) { return; }
            String text = input.strip();
            try {
                if (text.isEmpty()) { throw new InvalidPathException(text, "请输入文件或文件夹路径"); }
                for (Page page : Page.values()) {
                    if (page.title.equalsIgnoreCase(text)) {
                        navigate(Location.page(page));
                        return;
                    }
                }
                if (WindowsPlaces.WINDOWS && text.matches("(?i)[a-z]:")) { text += "\\"; }
                Path target = Path.of(text);
                Path relativeTo = context.location.path() == null ? WindowsPlaces.USER_HOME : context.location.path();
                Path path = relativeTo.resolve(target).normalize();
                navigate(Location.folder(path), true);
            } catch (InvalidPathException exception) {
                showError(exception);
            }
        }

        private List<AddressBar.Crumb> breadcrumbs() {
            Path path = context.location.path();
            if (path == null) { return List.of(pageCrumb(context.location.page())); }
            List<AddressBar.Crumb> crumbs = new ArrayList<>();
            crumbs.add(pageCrumb(containerPage(path)));
            Path current = path.getRoot();
            crumbs.add(pathCrumb(current));
            for (Path segment : path) {
                current = current.resolve(segment);
                crumbs.add(pathCrumb(current));
            }
            return List.copyOf(crumbs);
        }

        private AddressBar.Crumb pageCrumb(Page page) {
            List<BrowserItem> folders = switch (page) {
                case COMPUTER -> places.computer();
                case HOME -> Stream.concat(pins().stream(), latestFolders().stream()).toList();
                case GALLERY -> galleryFolders.stream().map(path -> BrowserItem.folder(path, Location.folder(path).label())).toList();
                case NETWORK -> networkItems();
                case LINUX -> places.linux();
                case DIRECTORY -> List.of();
            };
            List<AddressBar.Choice> options = folders.stream().filter(BrowserItem::directory).map(this::folderChoice).toList();
            return new AddressBar.Crumb(page.title, page.namespace == null ? null : () -> icons.namespaceGraphic(page.namespace, 16),
                    () -> navigate(Location.page(page)),
                    options.isEmpty() ? null : complete -> complete.accept(new AddressBar.Children(options, "没有可显示的子文件夹")));
        }

        private AddressBar.Crumb pathCrumb(Path path) {
            Location target = Location.folder(path);
            String label = places.computer().stream().filter(item -> path.equals(item.path())).map(BrowserItem::name)
                    .findFirst().orElse(target.label());
            return new AddressBar.Crumb(label, null, () -> navigate(target), complete -> {
                LocalFiles.Visibility filter = visibility;
                BrowseContext owner = context;
                long version = owner.listingVersion;
                if (owner.addressRead != null) { reads.cancel(owner.addressRead); }
                var read = task(cancelled -> readVisibleDirectory(path, filter, cancelled));
                owner.addressRead = read;
                readDetail(read, result -> {
                    if (owner.addressRead == read && shows(owner, version)) {
                        owner.addressRead = null;
                        complete.accept(new AddressBar.Children(result.entries().stream()
                                .filter(LocalFiles.Entry::directory).map(BrowserItem::file).map(this::folderChoice).toList(),
                                "没有可显示的子文件夹"));
                    }
                }, failure -> {
                    if (owner.addressRead == read && shows(owner, version)) {
                        owner.addressRead = null;
                        complete.accept(new AddressBar.Children(List.of(), "无法读取子文件夹"));
                    }
                });
            });
        }

        private AddressBar.Choice folderChoice(BrowserItem item) {
            return new AddressBar.Choice(item.name(), () -> icons.graphic(item, 16), () -> navigate(Location.folder(item.path())));
        }

        private Location parentLocation() {
            if (context.location.path() == null) { return null; }
            Path path = context.location.path();
            if (path.getParent() != null) { return Location.folder(path.getParent()); }
            return Location.page(containerPage(path));
        }

        private void navigate(Location target) {
            navigate(target, false);
        }

        private void navigate(Location target, boolean openAddressFile) {
            if (target == null) { return; }
            BrowseContext owner = context;
            Location previous = owner.location;
            startBrowse(new BrowseIntent(target, "", owner.homeTab, "", openAddressFile, () -> {
                if (!previous.equals(target)) {
                    owner.past.push(previous);
                    owner.future.clear();
                }
                visitFolder(target);
            }));
        }

        private void history(Deque<Location> from, Deque<Location> to) {
            if (from.isEmpty()) { return; }
            Location previous = context.location;
            Location target = from.peek();
            request(target, "", "", () -> {
                from.pop();
                to.push(previous);
                visitFolder(target);
            });
        }

        private void search() {
            String query = searchField.getText().strip();
            request(context.location, query, "", () -> {});
        }

        private void refresh(String message) {
            if (context.browseRequest == null) {
                if (context.needsReload) { reloadContext(message); }
                else { request(context.location, context.activeQuery, message, () -> {}); }
            } else {
                var pending = context.browseRequest.intent();
                startBrowse(new BrowseIntent(pending.location(), pending.query(), pending.tab(), message,
                        pending.openAddressFile(), pending.committed(), null, pending.restoring()));
            }
        }

        private void reloadContext(String message) {
            Location opening = context.opening;
            Location target = opening == null ? context.location : opening;
            startBrowse(new BrowseIntent(target, context.activeQuery, context.homeTab, message, false,
                    () -> { if (opening != null) { visitFolder(opening); } }, null, opening == null));
        }

        private void request(Location target, String query, String message, Runnable loaded) {
            startBrowse(new BrowseIntent(target, query, context.homeTab, message, false, loaded));
        }

        private void startBrowse(BrowseIntent intent) {
            if (places == null || !visible(this)) { return; }
            BrowseContext owner = context;
            cancelBrowse(owner);
            Location target = intent.location();
            Work<Listing> work = listingWork(target, intent.query(), intent.tab());
            Map<BrowserItem, Selection> selected = intent.automatic() || intent.restoring() ? owner.selectionProof : Map.of();
            long queued = System.nanoTime();
            var worker = task(cancelled -> {
                readObserver.started(target.path(), intent.query(), System.nanoTime() - queued);
                return readBrowse(intent, work, selected, cancelled);
            });
            var request = new BrowseRequest(owner, intent, worker, queued);
            owner.browseRequest = request;
            if (!intent.automatic()) {
                showStatus(intent.query().isEmpty() ? "正在读取「" + target.label() + "」…"
                        : "正在搜索「" + intent.query() + "」…");
            }
            worker.setOnSucceeded(event -> {
                if (owns(request)) { commitBrowse(request, worker.getValue()); }
            });
            worker.setOnFailed(event -> {
                if (owns(request)) { browseFailed(request, worker.getException()); }
            });
            updateControls();
            if (!reads.listing(worker)) { browseFailed(request, new IOException("读取队列暂满，请稍后刷新")); }
        }

        private boolean owns(BrowseRequest request) {
            return visible(this) && context == request.owner() && !context.closed && context.browseRequest == request;
        }

        private boolean shows(BrowseContext owner, long version) {
            return visible(this) && owner == context && !owner.closed && owner.listingVersion == version;
        }

        private void cancelBrowse() { cancelBrowse(context); }

        private void cancelBrowse(BrowseContext owner) {
            var old = owner.browseRequest;
            owner.browseRequest = null;
            if (old != null) { reads.cancel(old.task()); }
        }

        private boolean isSearching() {
            return context.browseRequest != null && (!context.browseRequest.intent().query().isEmpty()
                    || context.browseRequest.intent().location().page() == Page.GALLERY);
        }

        private void commitBrowse(BrowseRequest request, BrowseData data) {
            long applyStarted = System.nanoTime();
            context.browseRequest = null;
            if (data.file() != null) {
                settleBrowse("");
                openFile(this, data.file(), data.file().getFileName().toString());
                return;
            }
            var intent = request.intent();
            context.deferredRefresh = null;
            boolean sameLocation = context.location.equals(intent.location());
            boolean sameView = sameLocation && context.activeQuery.equals(intent.query()) && context.homeTab == intent.tab();
            if (!sameView) {
                context.pendingFileRefresh = null;
            }
            boolean replaced = sameLocation && context.directoryIdentity != null && context.directoryIdentity.known()
                    && !context.directoryIdentity.equals(data.identity());
            if (!sameView || replaced) {
                browser.resetFilters();
            }
            var scroll = intent.restoring() && sameView && !replaced ? context.presentation.scroll() : null;
            boolean restore = intent.restoring() && sameView && !replaced;
            context.needsReload = false;
            context.opening = null;
            context.refreshOnReturn = "";
            context.location = intent.location();
            context.activeQuery = intent.query();
            context.homeTab = intent.tab();
            context.directoryIdentity = data.identity();
            context.directoryUnavailable = false;
            context.listing = data.listing();
            context.listingQueuedAt = request.queuedAt();
            context.listingVersion++;
            if (!sameView) { updateTabHeader(context); }
            browser.cutItems(Set.of());
            syncDirectoryWatch();
            if (intent.automatic() && !replaced) {
                renderAutomatic(data, intent.changes());
            } else {
                var retained = new LinkedHashMap<BrowserItem, Selection>();
                if (restore) {
                    for (BrowserItem previous : context.selectionProof.keySet()) {
                        var verified = data.verified().get(previous);
                        if (verified != null) { retained.put(verified.item(), verified.proof()); }
                    }
                }
                context.selectionProof = Map.copyOf(retained);
                updateLocation();
                render(false, restore ? List.copyOf(retained.keySet()) : null);
                if (scroll != null) {
                    browser.applyCss();
                    browser.layout();
                    browser.restoreScroll(scroll);
                }
            }
            intent.committed().run();
            verifyCutMarks();
            updateControls();
            updateListingStatus(replaced ? "目录身份已变化，已重新读取" : intent.message());
            readObserver.committed(context.location.path(), System.nanoTime() - applyStarted);
            flushWatchRefresh();
        }

        private void browseFailed(BrowseRequest request, Throwable failure) {
            context.browseRequest = null;
            if (request.intent().location().equals(context.opening)) { context.opening = null; }
            if (request.intent().location().equals(context.location) && context.location.path() != null
                    && (failure instanceof NoSuchFileException || failure instanceof NotDirectoryException
                        || failure instanceof AccessDeniedException)) {
                context.directoryUnavailable = true;
                context.listing = Listing.EMPTY;
                context.selectionProof = Map.of();
                context.listingVersion++;
                browser.cutItems(Set.of());
                render(false);
                syncDirectoryWatch();
            }
            boolean automatic = request.intent().automatic();
            String message = context.directoryUnavailable ? "当前位置无法访问，可返回或上移"
                    : automatic ? "自动刷新失败，可按 F5 重试" : "读取失败，当前页面已保留";
            if (automatic || request.intent().restoring()) {
                updateControls();
                updateListingStatus(message);
                flushWatchRefresh();
            } else {
                settleBrowse(message);
                if (activePane == this) { showError(failure); }
                if (context.needsReload && context.browseRequest == null && !context.directoryUnavailable) { reloadContext(""); }
            }
        }

        private void settleBrowse(String message) {
            if (!Objects.equals(searchField.getText(), context.activeQuery)) { searchField.setText(context.activeQuery); }
            updateControls();
            updateListingStatus(message);
            if (context.deferredRefresh != null) {
                String refresh = context.deferredRefresh;
                context.deferredRefresh = null;
                request(context.location, context.activeQuery, refresh, () -> {});
            }
            flushWatchRefresh();
        }

        private void syncDirectoryWatch() {
            if (!visible(this)) { return; }
            Path path = automaticRefresh && context.activeQuery.isEmpty() && context.directoryIdentity != null
                    && context.directoryIdentity.ntfs() != null ? context.location.path() : null;
            if (context.watchTarget != null && context.watchTarget.directory().equals(path)) { return; }
            context.pendingChanges = null;
            if (path != null) {
                context.watchTarget = watcher.watch(path);
                updateWatchStatus("正在启动自动刷新");
            } else {
                context.watchTarget = null;
                watcher.pause();
                updateWatchStatus(automaticRefresh ? "手动刷新" : "自动刷新已关闭");
            }
        }

        private void directoryChanged(DirectoryWatcher.Notice notice) {
            if (!visible(this) || notice.target() != context.watchTarget) { return; }
            updateWatchStatus(switch (notice.status()) {
                case ACTIVE -> "自动刷新已开启";
                case WAITING -> "等待目录恢复 · F5";
                case UNAVAILABLE -> "监听不可用 · F5";
            });
            var batch = notice.changes();
            if (batch != null && !batch.coveredBy(context.listingQueuedAt)) {
                queueWatchChanges(batch);
                flushWatchRefresh();
            }
        }

        private void queueWatchChanges(DirectoryEvents.Batch batch) {
            context.pendingChanges = context.pendingChanges == null ? batch : context.pendingChanges.merge(batch);
        }

        private void deferWatchRead() {
            if (context.browseRequest == null || !context.browseRequest.intent().automatic()) { return; }
            if (context.watchTarget != null) { queueWatchChanges(context.browseRequest.intent().changes()); }
            cancelBrowse();
        }

        private void updateWatchStatus(String message) {
            watchText = message;
            if (activePane == this) { watchStatus.setText(message); }
        }

        private void flushWatchRefresh() {
            if (!visible(this) || context.browseRequest != null || browser.editingName() || (fileTask != null && !writing)) { return; }
            var batch = context.pendingChanges;
            var written = context.pendingFileRefresh;
            context.pendingChanges = null;
            context.pendingFileRefresh = null;
            if (written != null) {
                request(context.location, context.activeQuery, written.message(), written.committed());
            } else if (batch != null && !batch.coveredBy(context.listingQueuedAt)
                    && dependsOn(batch.impact(context.location.path()), context.location, context.activeQuery, context.homeTab)) {
                startBrowse(new BrowseIntent(context.location, context.activeQuery, context.homeTab, "", false, () -> {}, batch));
            }
        }

        private void updateListingStatus(String message) {
            if (context.browseRequest != null) { return; }
            String total = Integer.toString(context.listing.items().size());
            String count = browser.filtersActive() ? "显示 " + browser.items().size() + " / " + total : total;
            count += context.activeQuery.isEmpty() ? " 个项目" : " 个搜索结果";
            if (context.listing.limited()) { count += " · 已达结果上限"; }
            if (context.listing.unreadable() != 0) { count += " · " + context.listing.unreadable() + " 项无法读取"; }
            if (context.directoryUnavailable && message.isEmpty()) { message = "当前位置无法访问，可返回或上移"; }
            showStatus(message.isEmpty() ? count : message + " · " + count);
        }

        private void updateLocation() {
            address.setLocation(context.location.path() == null ? context.location.label() : context.location.path().toString(), breadcrumbs());
            if (!Objects.equals(searchField.getText(), context.activeQuery)) { searchField.setText(context.activeQuery); }
            searchField.setPromptText("在「" + context.location.label() + "」中搜索");
            refreshOverview();
            if (activePane == this) { markLocation(); }
        }

        private View currentView() {
            return context.view();
        }

        private boolean homeLayout() {
            return context.location.page() == Page.HOME && context.activeQuery.isEmpty();
        }

        private void render(boolean preserveSelection) {
            render(preserveSelection, null);
        }

        private void render(boolean preserveSelection, List<BrowserItem> verifiedSelection) {
            if (dropSite != null && dropSite.owner() == context && dropSite.target().folder() != null) {
                clearDropSite();
            }
            View config = currentView();
            boolean computer = context.location.page() == Page.COMPUTER;
            boolean gallery = context.location.page() == Page.GALLERY;
            boolean recent = context.location.page() == Page.HOME && context.homeTab == HomeTab.RECENT;
            Field sort = config.sort() == Field.VISITED && !recent ? Field.NAME : config.sort();
            Layout layout = effectiveLayout();
            mountLayout(layout);
            browser.render(context.listing.items(), new BrowserView.Settings(
                    layout == Layout.COLUMNS ? Mode.LIST : config.mode(), sort, config.descending(), config.group(),
                    compact, checkboxes, extensions, computer, !context.activeQuery.isEmpty(), recent, homeLayout(), gallery,
                    context.listing.imageRatios(), layout == Layout.COLUMNS), preserveSelection, verifiedSelection);
            if (layout != Layout.TRADITIONAL) {
                hierarchy.showPath(context.location.path(), context.listingVersion, layout == Layout.HYBRID,
                        browser, context.listing.items(), context.directoryUnavailable);
            }
        }

        private boolean supportsProfessionalLayout() {
            return context.location.page() == Page.DIRECTORY && context.activeQuery.isEmpty();
        }

        private Layout effectiveLayout() {
            return supportsProfessionalLayout() && !context.needsReload ? context.layout : Layout.TRADITIONAL;
        }

        private List<MenuItem> layoutItems() {
            BrowseContext owner = context;
            var group = new ToggleGroup();
            return Arrays.stream(Layout.values()).map(layout -> {
                MenuItem item = radio(layout.label, group, layout == owner.layout, () -> {
                    if (!visible(this) || context != owner || !supportsProfessionalLayout() || layout == owner.layout) {
                        return;
                    }
                    browser.dismissTransientControls();
                    owner.layout = layout;
                    render(true);
                });
                item.setId("layout-" + layout.name().toLowerCase(Locale.ROOT));
                return item;
            }).toList();
        }

        private void mountLayout(Layout layout) {
            if (layout == mountedLayout) { return; }
            if (mountedLayout == Layout.HYBRID) { context.hierarchyHeight = hybrid.getDividerPositions()[0]; }
            hierarchy.clear();
            hybrid.getItems().clear();
            browserHost.getChildren().clear();
            browser.setVisible(true);
            browser.setManaged(true);
            mountedLayout = layout;
            switch (layout) {
                case TRADITIONAL -> browserHost.getChildren().add(browser);
                case COLUMNS -> browserHost.getChildren().add(hierarchy);
                case HYBRID -> {
                    hybrid.getItems().setAll(hierarchy, browser);
                    hybrid.setDividerPositions(context.hierarchyHeight);
                    browserHost.getChildren().add(hybrid);
                }
            }
        }

        private Task<List<BrowserItem>> readAncestor(Path path) {
            LocalFiles.Visibility filters = visibility;
            return task(cancelled -> {
                var entries = readVisibleDirectory(path, filters, cancelled).entries().stream()
                        .filter(LocalFiles.Entry::directory).map(BrowserItem::file).toList();
                readObserver.ancestorReady(path);
                return entries;
            });
        }

        private void renderAutomatic(BrowseData data, DirectoryEvents.Batch changes) {
            var selected = browser.selection();
            var retained = new LinkedHashMap<BrowserItem, Selection>();
            for (BrowserItem previous : selected) {
                var verified = data.verified().get(previous);
                if (verified != null) { retained.put(verified.item(), verified.proof()); }
            }
            BrowserItem previous = selected.size() == 1 ? selected.getFirst() : null;
            var verified = previous == null ? null : data.verified().get(previous);
            context.keepPreview = context.pane == Pane.PREVIEW && context.previewLoaded && verified != null
                    && previous.entry().sameMetadata(verified.item().entry())
                    && !changes.full() && !changes.impact(context.location.path()).affectsEntry(previous.path());
            context.selectionProof = Map.copyOf(retained);
            try {
                render(false, List.copyOf(retained.keySet()));
            } finally {
                context.keepPreview = false;
            }
        }

        private void sort(Field field, boolean descending) {
            View config = currentView();
            if (config.sort() != field || config.descending() != descending) {
                context.views.put(context.location.page(), new View(config.mode(), field, descending, config.group()));
                render(true);
            }
        }

        private void group(Field field) {
            View config = currentView();
            context.views.put(context.location.page(), new View(config.mode(), config.sort(), config.descending(), field));
            render(true);
        }

        private void mode(Mode mode) {
            View config = currentView();
            context.views.put(context.location.page(), new View(mode, config.sort(), config.descending(), config.group()));
            render(true);
        }

        private List<MenuItem> sortItems() {
            View config = currentView();
            List<MenuItem> commands = new ArrayList<>();
            List<Field> fields = availableFields();
            ToggleGroup keys = new ToggleGroup();
            List<MenuItem> advanced = new ArrayList<>();
            for (Field field : fields) {
                MenuItem choice = radio(fieldLabel(field), keys, config.sort() == field,
                        () -> sort(field, config.descending()));
                choice.setId("sort-" + field.name().toLowerCase(Locale.ROOT));
                if (fields.indexOf(field) >= 3 && context.location.page() == Page.COMPUTER) { advanced.add(choice); }
                else { commands.add(choice); }
            }
            if (!advanced.isEmpty()) { commands.add(submenu("更多", advanced)); }
            ToggleGroup directions = new ToggleGroup();
            commands.add(new SeparatorMenuItem());
            commands.add(radio("递增", directions, !config.descending(), () -> sort(config.sort(), false)));
            commands.add(radio("递减", directions, config.descending(), () -> sort(config.sort(), true)));
            commands.add(new SeparatorMenuItem());
            ToggleGroup groups = new ToggleGroup();
            List<MenuItem> choices = new ArrayList<>();
            for (Field field : fields) {
                MenuItem choice = radio(fieldLabel(field), groups, config.group() == field, () -> group(field));
                choice.setId("group-" + field.name().toLowerCase(Locale.ROOT));
                choices.add(choice);
            }
            choices.add(new SeparatorMenuItem());
            choices.add(radio("（无）", groups, config.group() == null, () -> group(null)));
            commands.add(submenu("分组依据", choices));
            return commands;
        }

        private List<Field> availableFields() {
            if (context.location.page() == Page.COMPUTER) {
                return List.of(Field.NAME, Field.TYPE, Field.SIZE, Field.AVAILABLE, Field.FILE_SYSTEM,
                        Field.COMMENT, Field.NETWORK, Field.USED);
            }
            List<Field> fields = new ArrayList<>(List.of(Field.NAME, Field.TYPE, Field.SIZE, Field.MODIFIED));
            if (context.location.page() == Page.HOME && context.homeTab == HomeTab.RECENT) { fields.add(Field.VISITED); }
            if (!context.activeQuery.isEmpty() || context.location.page() == Page.GALLERY || context.location.page() == Page.HOME) {
                fields.add(Field.LOCATION);
            }
            return fields;
        }

        private String fieldLabel(Field field) {
            return field == Field.SIZE && context.location.page() == Page.COMPUTER ? "总大小" : field.label;
        }

        private List<MenuItem> viewItems() {
            ToggleGroup modes = new ToggleGroup();
            List<MenuItem> result = new ArrayList<>();
            List<Mode> modesForPage = effectiveLayout() == Layout.COLUMNS ? List.of() : switch (context.location.page()) {
                case GALLERY -> List.of(Mode.HUGE, Mode.LARGE, Mode.MEDIUM, Mode.SMALL);
                case HOME -> homeLayout() ? List.of() : List.of(Mode.values());
                default -> List.of(Mode.values());
            };
            for (Mode mode : modesForPage) {
                MenuItem item = radio(mode.label, modes, currentView().mode() == mode, () -> mode(mode));
                item.setId("mode-" + mode.name().toLowerCase(Locale.ROOT));
                result.add(item);
            }
            if (!result.isEmpty()) { result.add(new SeparatorMenuItem()); }
            result.add(checkItem("详细信息窗格", "show-details", context.pane == Pane.DETAILS, enabled -> changePane(Pane.DETAILS)));
            result.add(checkItem("预览窗格", "show-preview", context.pane == Pane.PREVIEW, enabled -> changePane(Pane.PREVIEW)));
            result.add(checkItem("自动刷新", "auto-refresh", automaticRefresh, enabled -> {
                automaticRefresh = enabled;
                preferences.putBoolean("automaticRefresh", enabled);
                visiblePanes().forEach(BrowserPane::syncDirectoryWatch);
            }));
            result.add(new SeparatorMenuItem());
            result.add(appearanceMenu());
            result.add(submenu("显示", List.of(
                    checkItem("导航窗格", "show-navigation", navigationPane, value -> {
                        navigationPane = value;
                        preferences.putBoolean("navigation", value);
                        updateControls();
                    }),
                    checkItem("紧凑视图", "show-compact", compact, value -> {
                        compact = value;
                        preferences.putBoolean("compact", value);
                        visiblePanes().forEach(pane -> pane.render(true));
                    }),
                    new SeparatorMenuItem(),
                    checkItem("项目复选框", "show-checkboxes", checkboxes, value -> {
                        checkboxes = value;
                        preferences.putBoolean("checkboxes", value);
                        visiblePanes().forEach(pane -> pane.render(true));
                    }),
                    checkItem("文件扩展名", "show-extensions", extensions, value -> {
                        extensions = value;
                        preferences.putBoolean("extensions", value);
                        visiblePanes().forEach(pane -> pane.render(true));
                    }),
                    checkItem("隐藏的项目", "show-hidden", visibility.hidden(),
                            value -> changeVisibility(new LocalFiles.Visibility(value, visibility.protectedSystem()))),
                    checkItem("受保护的系统文件", "show-system", visibility.protectedSystem(),
                            value -> changeVisibility(new LocalFiles.Visibility(visibility.hidden(), value))))));
            return result;
        }

        private void refreshOverview() {
            overview.getChildren().clear();
            if (homeLayout()) {
                browser.refreshHomeHeader();
            } else if (context.location.page() == Page.NETWORK) {
                Button discover = new Button("浏览 Windows 网络");
                discover.setDisable(!WindowsPlaces.WINDOWS);
                discover.setOnAction(event -> browseNetwork());
                Button add = new Button("添加网络位置");
                add.setDisable(!WindowsPlaces.WINDOWS);
                add.setOnAction(event -> addNetwork());
                overview.getChildren().add(new HBox(8, discover, add));
            } else if (context.location.page() == Page.LINUX && context.listing.items().isEmpty()) {
                overview.getChildren().add(new Label("Windows 中没有可用的 WSL 发行版"));
            }
            overview.setVisible(!overview.getChildren().isEmpty());
        }

        private Region homeHeader() {
            BrowseContext owner = context;
            long version = owner.listingVersion;
            Button quickAccess = new Button("快速访问",
                    (context.quickAccessExpanded ? UiGlyph.DOWN : UiGlyph.RIGHT).graphic(12));
            quickAccess.setId("home-quick-access");
            quickAccess.getStyleClass().add("home-section");
            quickAccess.setOnAction(event -> {
                context.quickAccessExpanded = !context.quickAccessExpanded;
                browser.refreshHomeHeader();
                Platform.runLater(() -> {
                    if (activePane != this || !shows(owner, version)) { return; }
                    Node replacement = browser.lookup("#home-quick-access");
                    if (replacement != null) { replacement.requestFocus(); }
                });
            });

            VBox header = new VBox(9, quickAccess);
            header.getStyleClass().add("home-header");
            if (context.quickAccessExpanded) {
                TilePane links = new TilePane(12, 4);
                links.getStyleClass().add("quick-links");
                links.setTileAlignment(Pos.CENTER_LEFT);
                links.setPrefTileWidth(187);
                links.setPrefTileHeight(53);
                List<BrowserItem> pinned = pins();
                for (BrowserItem item : pinned) { links.getChildren().add(homeShortcut(item, true)); }
                for (BrowserItem item : latestFolders()) { links.getChildren().add(homeShortcut(item, false)); }
                header.getChildren().add(links);
            }
            boolean expanded = browser.homeFilesExpanded();
            Button expandFiles = new Button(null, (expanded ? UiGlyph.DOWN : UiGlyph.RIGHT).graphic(12));
            expandFiles.setId("home-expand-files");
            expandFiles.setAccessibleText(expanded ? "折叠文件列表" : "展开文件列表");
            expandFiles.getStyleClass().add("home-section");
            expandFiles.setOnAction(event -> {
                browser.toggleHomeFiles();
                Platform.runLater(() -> {
                    if (activePane != this || !shows(owner, version)) { return; }
                    Node replacement = browser.lookup("#home-expand-files");
                    if (replacement != null) { replacement.requestFocus(); }
                });
            });
            HBox tabs = new HBox(7, expandFiles, homeTab("最近使用的文件", HomeTab.RECENT, UiGlyph.CLOCK),
                    homeTab("收藏夹", HomeTab.FAVORITES, UiGlyph.FAVORITE), homeTab("已共享", HomeTab.SHARED, UiGlyph.PEOPLE));
            tabs.setAlignment(Pos.CENTER_LEFT);
            tabs.getStyleClass().add("home-tabs");
            header.getChildren().add(tabs);
            return header;
        }

        private Button homeShortcut(BrowserItem item, boolean pinned) {
            Label title = new Label(item.name());
            title.getStyleClass().add("quick-link-name");
            boolean knownFolder = places.fixed().stream().anyMatch(fixed -> fixed.path().equals(item.path()));
            Label subtitle = new Label(knownFolder ? "本地存储" : item.location());
            subtitle.getStyleClass().add("muted-label");
            HBox detail = new HBox(4, subtitle);
            detail.setAlignment(Pos.CENTER_LEFT);
            if (pinned) { detail.getChildren().addFirst(UiGlyph.PIN.graphic(10)); }
            VBox text = new VBox(1, title, detail);
            text.setMinWidth(0);
            HBox graphic = new HBox(10, icons.graphic(item, 36), text);
            graphic.setAlignment(Pos.CENTER_LEFT);
            Button shortcut = new Button(null, graphic);
            shortcut.getStyleClass().add("quick-link");
            shortcut.setAccessibleText(item.name());
            shortcut.setOnAction(event -> open(item));
            shortcut.setTooltip(new Tooltip(item.path().toString()));
            shortcut.setContextMenu(new ContextMenu(pinItem(item.path())));
            return shortcut;
        }

        private Button homeTab(String label, HomeTab tab, UiGlyph glyph) {
            Button button = new Button(label, glyph.graphic(13));
            button.setId("home-" + tab.name().toLowerCase(Locale.ROOT));
            button.getStyleClass().add(tab == context.homeTab ? "active-tab" : "home-tab");
            button.setOnAction(event -> {
                if (!browser.homeFilesExpanded()) { browser.toggleHomeFiles(); }
                if (tab == context.homeTab && context.browseRequest == null) { return; }
                startBrowse(new BrowseIntent(context.location, context.activeQuery, tab, "", false, () -> {}));
            });
            return button;
        }

        private void selectionChanged() {
            if (!visible(this)) { return; }
            updateControls();
            observeVisibleSelection();
            if (!context.keepPreview || browser.selection().size() != 1) { refreshInspector(); }
        }

        private void observeVisibleSelection() {
            if (context.needsReload) { return; }
            if (context.selectionRead != null) {
                reads.cancel(context.selectionRead);
                context.selectionRead = null;
            }
            var selected = browser.selection();
            if (selected.isEmpty() || selected.size() > MAX_RETAINED_SELECTION) {
                context.selectionProof = Map.of();
                return;
            }
            var retained = new HashMap<BrowserItem, Selection>();
            for (BrowserItem item : selected) {
                var proof = context.selectionProof.get(item);
                if (proof != null) { retained.put(item, proof); }
            }
            context.selectionProof = Map.copyOf(retained);
            var missing = selected.stream().filter(item -> !retained.containsKey(item)).toList();
            if (missing.isEmpty()) { return; }
            BrowseContext owner = context;
            long version = owner.listingVersion;
            var read = task(cancelled -> {
                var observed = new HashMap<>(retained);
                for (BrowserItem item : missing) {
                    if (cancelled.getAsBoolean()) { break; }
                    var proof = observeSelection(item);
                    if (proof != null) { observed.put(item, proof); }
                }
                return Map.copyOf(observed);
            });
            owner.selectionRead = read;
            readDetail(read, observed -> {
                if (owner.selectionRead == read && shows(owner, version)) {
                    owner.selectionRead = null;
                    owner.selectionProof = observed;
                }
            }, failure -> {
                if (owner.selectionRead == read) { owner.selectionRead = null; }
            });
        }

        private void changePane(Pane next) {
            context.pane = context.pane == next ? Pane.NONE : next;
            if (activePane == this) { updateInspectorHost(); }
            refreshInspector();
        }

        private void refreshInspector() {
            context.previewLoaded = false;
            if (context.previewTask != null) {
                reads.cancel(context.previewTask);
                context.previewTask = null;
            }
            inspector.getChildren().clear();
            if (context.pane == Pane.NONE) { return; }
            Label heading = new Label(context.pane == Pane.DETAILS ? "详细信息" : "预览");
            heading.getStyleClass().add("section-title");
            inspector.getChildren().add(heading);
            BrowserItem item = selectedItem();
            if (browser.selection().size() > 1) {
                inspector.getChildren().add(new Label("已选择 " + browser.selection().size() + " 个项目"));
            } else if (item == null) {
                inspector.getChildren().add(new Label("选择一个项目以查看"));
            } else if (context.pane == Pane.DETAILS) {
                Label text = new Label(propertiesText(item));
                text.setWrapText(true);
                inspector.getChildren().addAll(icons.graphic(item, 32), text);
            } else {
                preview(item);
            }
        }

        private void preview(BrowserItem item) {
            if (item.kind() != BrowserItem.Kind.FILE || item.entry() == null) {
                inspector.getChildren().add(new Label("此项目没有文件预览"));
                return;
            }
            Label hint = new Label("正在读取预览…");
            inspector.getChildren().add(hint);
            BrowseContext owner = context;
            long version = owner.listingVersion;
            Selection known = owner.selectionProof.get(item);
            var read = task(cancelled -> {
                requireVisible(item.path());
                var selection = known == null ? FileChanges.capture(item.entry()) : known;
                if (known != null) { NtfsFileOperations.verify(known.nativeItem()); }
                LocalFiles.verify(item.entry(), true);
                PreviewData data;
                try (var input = Files.newInputStream(item.path())) {
                    if (LocalFiles.isImage(item.path())) {
                        var image = new Image(input, 220, 340, true, true);
                        if (image.isError()) { throw new IOException("无法预览此图片", image.getException()); }
                        data = new PreviewData(image, null);
                    } else {
                        String text = new String(input.readNBytes(8192), StandardCharsets.UTF_8);
                        data = new PreviewData(null, text.indexOf('\0') >= 0 || text.indexOf('\ufffd') >= 0 ? null : text);
                    }
                }
                readObserver.ready(item.path(), true);
                if (!cancelled.getAsBoolean()) {
                    if (selection.nativeItem() != null) { NtfsFileOperations.verify(selection.nativeItem()); }
                    else { LocalFiles.verify(item.entry(), true); }
                }
                return data;
            });
            owner.previewTask = read;
            readDetail(read, result -> {
                if (owner.previewTask != read || !shows(owner, version) || selectedItem() != item) { return; }
                owner.previewTask = null;
                owner.previewLoaded = true;
                if (result.image() != null) {
                    var image = new ImageView(result.image());
                    image.setPreserveRatio(true);
                    image.setFitWidth(220);
                    image.setFitHeight(340);
                    inspector.getChildren().set(inspector.getChildren().indexOf(hint), image);
                    return;
                }
                String text = result.text();
                if (text == null) { hint.setText("此格式没有文本预览"); }
                else if (text.isEmpty()) { hint.setText("空文件"); }
                else {
                    TextArea preview = new TextArea(text);
                    preview.setEditable(false);
                    preview.setWrapText(true);
                    preview.setPrefWidth(220);
                    inspector.getChildren().set(inspector.getChildren().indexOf(hint), preview);
                    VBox.setVgrow(preview, Priority.ALWAYS);
                }
            }, failure -> {
                if (owner.previewTask == read && shows(owner, version)) {
                    owner.previewTask = null;
                    hint.setText("无法读取此文件，项目可能已变化");
                }
            });
        }

        private void open(BrowserItem item) {
            if (!visible(this) || item == null) { return; }
            if (item.directory()) {
                navigate(Location.folder(item.path()));
            } else if (item.kind() == BrowserItem.Kind.SHELL) {
                BrowseContext owner = context;
                long version = owner.listingVersion;
                readDetail(task(ignored -> { WindowsPlaces.openShortcut(item.shellTarget()); return null; }),
                        ignored -> { if (shows(owner, version)) { updateListingStatus("已打开「" + item.name() + "」"); } },
                        failure -> { if (shows(owner, version) && activePane == this) { showError(failure); } });
            } else {
                openFile(this, item.path(), item.name());
            }
        }

        private void verifyCutMarks() {
            if (context.cutMarkRead != null) {
                reads.cancel(context.cutMarkRead);
                context.cutMarkRead = null;
            }
            var current = transfer;
            if (clipboardUnavailable || current == null || !current.move() || current.sources().isEmpty()) {
                browser.cutItems(Set.of());
                return;
            }
            var wanted = new HashMap<Path, Selection>();
            for (var source : current.sources()) {
                if (source.nativeItem() != null) { wanted.put(source.path(), source); }
            }
            List<BrowserItem> candidates = context.listing.items().stream().filter(item -> wanted.containsKey(item.path())).toList();
            if (candidates.isEmpty()) {
                browser.cutItems(Set.of());
                return;
            }
            BrowseContext owner = context;
            long version = owner.listingVersion;
            var read = task(cancelled -> {
                Set<BrowserItem> verified = new HashSet<>();
                for (var item : candidates) {
                    if (cancelled.getAsBoolean()) { break; }
                    var proof = observeSelection(item);
                    if (proof != null && wanted.get(item.path()).nativeItem().equals(proof.nativeItem())) { verified.add(item); }
                }
                return Set.copyOf(verified);
            });
            owner.cutMarkRead = read;
            readDetail(read, marks -> {
                if (owner.cutMarkRead == read && transfer == current && shows(owner, version) && !clipboardUnavailable) {
                    owner.cutMarkRead = null;
                    browser.cutItems(marks);
                }
            }, failure -> {
                if (owner.cutMarkRead == read) { owner.cutMarkRead = null; }
            });
        }

        private BrowserItem selectedItem() { return browser.selection().stream().findFirst().orElse(null); }
    }

    private static <T> Task<T> task(Work<T> work) {
        return new Task<>() {
            @Override
            protected T call() throws Exception { return work.read(this::isCancelled); }
        };
    }

    public static void main(String[] args) { launch(args); }
}

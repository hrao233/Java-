package edu.files;

import edu.files.BrowseContext.BrowseData;
import edu.files.BrowseContext.BrowseIntent;
import edu.files.BrowseContext.BrowseRequest;
import edu.files.BrowseContext.FileRefresh;
import edu.files.BrowseContext.HomeTab;
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
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
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
import java.util.function.Predicate;
import java.util.prefs.Preferences;
import java.util.stream.Stream;

public final class FileManagerApp extends Application {
    private static final PseudoClass NAV_DIVIDER = PseudoClass.getPseudoClass("nav-divider");
    private static final double MIN_NAV_WIDTH = 146;
    private static final double MAX_NAV_WIDTH = 480;
    private static final int MAX_RETAINED_SELECTION = 64;

    private record SideNode(String label, Location location, BrowserItem item) {
        boolean divider() { return label.isEmpty(); }
    }
    private record SystemCatalog(WindowsPlaces.Places places, List<NewItems.Type> newTypes) { }
    private record PasteRequest(Snapshot snapshot, Transfer local, List<Path> external) {
        boolean move() { return local != null && local.move(); }
    }
    private record PasteTarget(Path path, DirectoryIdentity knownDirectory, BrowserItem folder,
                               Selection knownFolder) { }
    private record PreparedPaste(FileChanges.Destination destination, List<Selection> sources) { }
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
    private final DirectoryWatcher watcher = new DirectoryWatcher(DirectoryIdentity::read, staging::contains,
            notice -> Platform.runLater(() -> directoryChanged(notice)));
    private final Preferences preferences;
    private final BiFunction<Kind, List<Change>, FileOperationTask> operationFactory;
    private final ReadObserver readObserver;
    private long nextTabId;
    private BrowseContext context = new BrowseContext(++nextTabId);
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

    private final AddressBar address = new AddressBar(this::navigateFromAddress, this::addressHistory);
    private final TextField searchField = new TextField();
    private final Label status = new Label();
    private final Label clipboardStatus = new Label();
    private final Label watchStatus = new Label();
    private final Label writeNotice = new Label("文件写入命令暂不可用");
    private final Button unknownResults = new Button("核查结果");
    private final List<FileChanges.UnknownOperation> unknownOperations = new ArrayList<>();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final VBox overview = new VBox(13);
    private final VBox inspector = new VBox(10);
    private final TreeView<SideNode> tree = new TreeView<>();
    private final SplitPane workspace = new SplitPane();
    private final Map<String, Control> controls = new LinkedHashMap<>();

    private Stage stage;
    private SystemIcons icons;
    private BrowserView browser;
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
        this(Preferences.userNodeForPackage(FileManagerApp.class).node("explorer"), FileOperationTask::new,
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
        this.preferences = preferences;
        this.operationFactory = operationFactory;
        this.readObserver = readObserver;
        this.clipboard = clipboard;
    }

    @Override
    public void start(Stage window) {
        stage = window;
        automaticRefresh = preferences.getBoolean("automaticRefresh", true);
        operationProgress = new FileOperationProgress(window, unknownResults, this::locateReportedPath);
        icons = new SystemIcons(window::getOutputScaleX);
        browser = new BrowserView(icons, this::selectionChanged, this::open, this::sort, this::contextMenu,
                () -> updateListingStatus(""), this::homeHeader, this::flushWatchRefresh);
        browser.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getTarget() instanceof TextInputControl) { return; }
            KeyCode key = event.getCode();
            if (key == KeyCode.ENTER) {
                open(selectedItem());
            } else if (key == KeyCode.F2) {
                fire("rename");
            } else if (key == KeyCode.DELETE) {
                fire("trash");
            } else if (event.isShortcutDown() && key == KeyCode.A) {
                browser.selectAll();
            } else if (event.isShortcutDown() && key == KeyCode.Z) {
                undo();
            } else {
                return;
            }
            event.consume();
        });
        configureTree();
        navigationWidth = Math.clamp(preferences.getDouble("navigationWidth", 222), MIN_NAV_WIDTH, MAX_NAV_WIDTH);
        navigationPane = preferences.getBoolean("navigation", true);
        workspace.setId("workspace-split");
        workspace.getStyleClass().add("workspace-split");
        workspace.getItems().add(content());
        SplitPane.setResizableWithParent(tree, false);
        syncNavigationPane();
        workspace.widthProperty().addListener((observable, previous, width) -> {
            if (previous.doubleValue() == 0 && navigationPane) { applyNavigationWidth(); }
        });

        BorderPane layout = new BorderPane();
        layout.setTop(new VBox(tabBar(), navigation(), commandBar()));
        layout.setCenter(workspace);
        layout.setBottom(statusBar());
        Scene scene = new Scene(layout, 1200, 760);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::tabKey);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::fileClipboardKey);
        scene.getStylesheets().add(getClass().getResource("file-manager.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN), address::beginEdit);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.D, KeyCombination.ALT_DOWN), address::beginEdit);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), () -> fire("refresh"));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                () -> { if (!newTypes.isEmpty()) { create(newTypes.getFirst()); } });
        stage.setTitle("本地文件管理器");
        stage.setMinWidth(960);
        stage.setMinHeight(550);
        stage.setScene(scene);
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
        clipboardTimer.setCycleCount(Animation.INDEFINITE);
        clipboardTimer.play();
        pollClipboard();
        loadPlaces(true);
    }

    @Override
    public void stop() {
        closing = true;
        clipboardTimer.stop();
        clipboardDeadline.stop();
        if (clipboardCommand != null) { clipboardCommand.cancel(true); }
        clipboardWorker.shutdownNow();
        if (browser != null) { browser.dismissTransientControls(); }
        tabHeaders.keySet().forEach(tab -> {
            releaseBrowse(tab);
            tab.closed = true;
            tab.selectionProof = Map.of();
        });
        tabHeaders.clear();
        watcher.close();
        rememberNavigationWidth();
        preferences.putDouble("navigationWidth", navigationWidth);
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

    private HBox tabBar() {
        tabs.setId("browse-tabs");
        tabs.getStyleClass().add("browse-tabs");
        tabs.setMinWidth(0);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.setTabDragPolicy(TabPane.TabDragPolicy.FIXED);
        HBox.setHgrow(tabs, Priority.ALWAYS);
        addTabHeader(context);
        tabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (!closing && selected != null) { activateTab((BrowseContext) selected.getUserData()); }
        });
        Button add = new Button("+");
        add.setId("new-tab");
        add.setAccessibleText("新建标签页");
        add.setTooltip(new Tooltip("新建标签页 (Ctrl+T)"));
        add.setOnAction(event -> newTab());
        HBox bar = new HBox(6, tabs, add);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("browse-tab-bar");
        // Cancel an inline edit before a header can take keyboard focus.
        bar.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (browser.editingName()) {
                browser.cancelNewName();
                Platform.runLater(this::flushWatchRefresh);
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
        openTab(context.location.path() == null ? Location.page(Page.HOME) : context.location);
    }

    private void openTab(Location location) {
        var owner = new BrowseContext(++nextTabId);
        if (location.page() != Page.HOME) { owner.opening = location; }
        tabs.getSelectionModel().select(addTabHeader(owner));
    }

    private void duplicateTab(BrowseContext original) {
        if (original.closed) { return; }
        if (original == context) { savePresentation(); }
        var duplicate = new BrowseContext(++nextTabId);
        duplicate.copyFrom(original);
        tabs.getSelectionModel().select(addTabHeader(duplicate));
    }

    private void closeTab(BrowseContext owner) {
        Tab tab = tabHeaders.get(owner);
        if (closing || tab == null) { return; }
        var headers = tabs.getTabs();
        if (headers.size() == 1) {
            openTab(Location.page(Page.HOME));
        } else if (owner == context) {
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
            case W -> { if (event.isShiftDown()) { return; } closeTab(context); }
            case TAB -> {
                int index = tabs.getSelectionModel().getSelectedIndex();
                tabs.getSelectionModel().select(Math.floorMod(index + (event.isShiftDown() ? -1 : 1), tabs.getTabs().size()));
            }
            default -> { return; }
        }
        event.consume();
    }

    private void savePresentation() {
        if (!context.needsReload) { context.presentation = browser.presentation(); }
    }

    private void activateTab(BrowseContext owner) {
        if (owner == context || owner.closed) { return; }
        browser.dismissTransientControls();
        savePresentation();
        releaseBrowse(context);
        watcher.pause();
        context = owner;
        browser.usePresentation(owner.presentation);
        browser.cutItems(Set.of());
        inspector.setVisible(owner.pane != Pane.NONE);
        updateWatchStatus(automaticRefresh ? "手动刷新" : "自动刷新已关闭");
        if (places != null) {
            updateLocation();
            render(false);
            reloadContext(owner.refreshOnReturn);
        }
    }

    private void releaseBrowse(BrowseContext owner) {
        cancelBrowse(owner);
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

    private HBox navigation() {
        HBox arrows = new HBox(4,
                navButton("‹", "后退", "back", () -> history(context.past, context.future)),
                navButton("›", "前进", "forward", () -> history(context.future, context.past)),
                navButton("↑", "上一级", "up", () -> navigate(parentLocation())),
                navButton("⟳", "刷新", "refresh", () -> {
                    if (context.location.page() == Page.COMPUTER) { loadPlaces(false); }
                    else {
                        refreshModifiedFolders();
                        refresh("");
                    }
                }));
        HBox.setHgrow(address, Priority.ALWAYS);
        searchField.setId("search-field");
        searchField.setPrefWidth(224);
        searchField.setOnAction(event -> search());
        Button search = button("搜索", "search", this::search);
        search.getStyleClass().add("primary-button");
        Button stop = button("取消搜索", "cancel-search", () -> {
            if (isSearching()) { cancelBrowse(); settleBrowse(""); }
        });
        stop.managedProperty().bind(stop.visibleProperty());
        Button clear = button("退出搜索", "clear-search", () -> request(context.location, "", "", () -> {}));
        clear.managedProperty().bind(clear.visibleProperty());
        HBox row = new HBox(10, arrows, address, searchField, search, stop, clear);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("navigation");
        return row;
    }

    private HBox commandBar() {
        MenuButton create = menuButton("新建", "new", () -> newItems());
        commandGraphic(create, "新建", UiGlyph.ADD, true);
        MenuButton sort = menuButton("排序", "sort", this::sortItems);
        commandGraphic(sort, "排序", UiGlyph.SORT, true);
        MenuButton view = menuButton("查看", "view", this::viewItems);
        commandGraphic(view, "查看", UiGlyph.VIEW, true);
        MenuButton more = menuButton("···", "more", this::moreItems);
        commandGraphic(more, "更多命令", UiGlyph.MORE, false);
        more.getStyleClass().add("command-more");
        MenuButton sources = menuButton("图库来源", "gallery-sources", this::galleryItems);
        commandGraphic(sources, "图库来源", UiGlyph.GALLERY, true);
        sources.managedProperty().bind(sources.visibleProperty());
        Button details = button("详细信息", "details-pane", () -> changePane(Pane.DETAILS));
        commandGraphic(details, "详细信息", UiGlyph.DETAILS, true);
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox bar = new HBox(5, create, commandDivider(),
                commandButton("剪切", "cut", UiGlyph.CUT, () -> rememberSelection(true)),
                commandButton("复制", "copy", UiGlyph.COPY, () -> rememberSelection(false)),
                commandButton("粘贴", "paste", UiGlyph.PASTE, this::paste),
                commandButton("重命名", "rename", UiGlyph.RENAME, this::renameSelected),
                commandButton("移到回收站", "trash", UiGlyph.DELETE, this::trashSelected),
                commandDivider(), sort, view, sources, commandDivider(), more, space, details);
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
        control.setGraphic(glyph.graphic(17, "command-symbol"));
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

    private HBox content() {
        VBox files = new VBox(overview, browser);
        overview.getStyleClass().add("overview");
        overview.managedProperty().bind(overview.visibleProperty());
        VBox.setVgrow(browser, Priority.ALWAYS);
        HBox.setHgrow(files, Priority.ALWAYS);
        inspector.setId("inspector");
        inspector.getStyleClass().add("inspector");
        inspector.setMinWidth(238);
        inspector.setPrefWidth(255);
        inspector.managedProperty().bind(inspector.visibleProperty());
        inspector.setVisible(false);
        return new HBox(files, inspector);
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
        updateWatchStatus("手动刷新");
        unknownResults.setId("unknown-results");
        unknownResults.setVisible(false);
        unknownResults.managedProperty().bind(unknownResults.visibleProperty());
        unknownResults.setOnAction(event -> inspectUnknownResults());
        writeNotice.setId("write-notice");
        writeNotice.managedProperty().bind(writeNotice.visibleProperty());
        writeNotice.setVisible(false);
        HBox bar = new HBox(8, progress, status, watchStatus, clipboardStatus, operationProgress.launcher(), writeNotice, unknownResults);
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
            if (initial || context.location.path() == null || context.browseRequest != null) { refresh(""); }
            else { updateLocation(); updateControls(); }
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

    private void navigateFromAddress(String input) {
        if (places == null || closing) { return; }
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

    private List<String> addressHistory() {
        Map<Path, FileTime> visits = new LinkedHashMap<>();
        if (places != null) { places.recent().forEach(item -> visits.putIfAbsent(item.path(), item.time())); }
        folderHistory.forEach(visits::put);
        recentFiles.forEach(visits::put);
        return visits.entrySet().stream().sorted(Map.Entry.<Path, FileTime>comparingByValue().reversed())
                .limit(20).map(entry -> entry.getKey().toString()).toList();
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

    private static Page containerPage(Path path) {
        String address = path.toString().toLowerCase(Locale.ROOT);
        if (address.startsWith("\\\\wsl.localhost\\") || address.startsWith("\\\\wsl$\\")) { return Page.LINUX; }
        return address.startsWith("\\\\") ? Page.NETWORK : Page.COMPUTER;
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
        if (places == null || closing) { return; }
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
            status.setText(intent.query().isEmpty() ? "正在读取「" + target.label() + "」…"
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

    private boolean owns(BrowseRequest request) {
        return !closing && context == request.owner() && !context.closed && context.browseRequest == request;
    }

    private boolean shows(BrowseContext owner, long version) {
        return !closing && owner == context && !owner.closed && owner.listingVersion == version;
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
            openFile(data.file(), data.file().getFileName().toString());
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
            showError(failure);
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
        if (closing) { return; }
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
        if (closing || notice.target() != context.watchTarget) { return; }
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

    private void updateWatchStatus(String message) {
        if (!message.equals(watchStatus.getText())) { watchStatus.setText(message); }
    }

    private void flushWatchRefresh() {
        if (closing || context.browseRequest != null || browser.editingName() || (fileTask != null && !writing)) { return; }
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
        status.setText(message.isEmpty() ? count : message + " · " + count);
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

    private void updateLocation() {
        address.setLocation(context.location.path() == null ? context.location.label() : context.location.path().toString(), breadcrumbs());
        if (!Objects.equals(searchField.getText(), context.activeQuery)) { searchField.setText(context.activeQuery); }
        searchField.setPromptText("在「" + context.location.label() + "」中搜索");
        refreshOverview();
        markLocation();
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
        View config = currentView();
        boolean computer = context.location.page() == Page.COMPUTER;
        boolean gallery = context.location.page() == Page.GALLERY;
        boolean recent = context.location.page() == Page.HOME && context.homeTab == HomeTab.RECENT;
        Field sort = config.sort() == Field.VISITED && !recent ? Field.NAME : config.sort();
        browser.render(context.listing.items(), new BrowserView.Settings(config.mode(), sort, config.descending(), config.group(),
                compact, checkboxes, extensions, computer, !context.activeQuery.isEmpty(), recent, homeLayout(), gallery,
                context.listing.imageRatios()), preserveSelection, verifiedSelection);
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
        List<Mode> modesForPage = switch (context.location.page()) {
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
            syncDirectoryWatch();
            observeVisibleSelection();
        }));
        result.add(new SeparatorMenuItem());
        result.add(submenu("显示", List.of(
                checkItem("导航窗格", "show-navigation", navigationPane, value -> {
                    navigationPane = value;
                    preferences.putBoolean("navigation", value);
                    updateControls();
                }),
                checkItem("紧凑视图", "show-compact", compact, value -> {
                    compact = value;
                    preferences.putBoolean("compact", value);
                    render(true);
                }),
                new SeparatorMenuItem(),
                checkItem("项目复选框", "show-checkboxes", checkboxes, value -> {
                    checkboxes = value;
                    preferences.putBoolean("checkboxes", value);
                    render(true);
                }),
                checkItem("文件扩展名", "show-extensions", extensions, value -> {
                    extensions = value;
                    preferences.putBoolean("extensions", value);
                    render(true);
                }),
                checkItem("隐藏的项目", "show-hidden", visibility.hidden(),
                        value -> changeVisibility(new LocalFiles.Visibility(value, visibility.protectedSystem()))),
                checkItem("受保护的系统文件", "show-system", visibility.protectedSystem(),
                        value -> changeVisibility(new LocalFiles.Visibility(visibility.hidden(), value))))));
        return result;
    }

    private void changeVisibility(LocalFiles.Visibility filters) {
        visibility = filters;
        preferences.putBoolean("hidden", filters.hidden());
        preferences.putBoolean("system", filters.protectedSystem());
        rebuildSidebar();
        refreshModifiedFolders();
        refresh("");
    }

    private List<MenuItem> moreItems() {
        boolean windows = WindowsPlaces.WINDOWS;
        List<MenuItem> commands = new ArrayList<>();
        commands.add(iconMenu("撤消", "undo", UiGlyph.UNDO, this::undo, fileTask != null || undo.isEmpty()));
        BrowserItem drive = driveForTools();
        if (windows && (context.location.page() == Page.COMPUTER || drive != null)) {
            commands.add(new SeparatorMenuItem());
            commands.add(iconMenu("清理", "clean-drive", UiGlyph.CLEAN,
                    () -> driveTool(drive, "清理", () -> WindowsPlaces.cleanDrive(drive.path()), false), drive == null || fileTask != null));
            commands.add(iconMenu("优化", "optimize-drive", UiGlyph.DRIVE,
                    () -> driveTool(drive, "优化", WindowsPlaces::optimizeDrives, false), drive == null || fileTask != null));
            commands.add(iconMenu("格式化", "format-drive", UiGlyph.FORMAT,
                    () -> driveTool(drive, "格式化", () -> WindowsPlaces.formatDrive(drive.path()), true), drive == null || fileTask != null));
        }
        if (context.location.page() == Page.DIRECTORY) {
            commands.add(new SeparatorMenuItem());
            commands.add(pinItem(context.location.path()));
        }
        if (windows && (context.location.page() == Page.COMPUTER || context.location.page() == Page.NETWORK)) {
            commands.add(new SeparatorMenuItem());
            commands.add(iconMenu("连接到媒体服务器", "media-server", UiGlyph.MEDIA, this::connectMedia, false));
            commands.add(iconMenu("添加一个网络位置", "add-network", UiGlyph.NETWORK, this::addNetwork, false));
            commands.add(iconMenu("映射网络驱动器", "map-network", UiGlyph.DRIVE, this::mapNetworkDrive, fileTask != null));
            commands.add(iconMenu("断开网络驱动器的连接", "disconnect-network", UiGlyph.DISCONNECT,
                    this::disconnectNetworkDrive, fileTask != null));
        }
        commands.add(new SeparatorMenuItem());
        commands.add(iconMenu("全部选择", "select-all", UiGlyph.SELECT_ALL, browser::selectAll, browser.items().isEmpty()));
        commands.add(iconMenu("全部取消", "select-none", UiGlyph.SELECT_NONE, browser::clearSelection, browser.selection().isEmpty()));
        commands.add(iconMenu("反向选择", "invert-selection", UiGlyph.INVERT, browser::invertSelection, browser.items().isEmpty()));
        if (browser.filtersActive()) {
            commands.add(iconMenu("清除列筛选", "clear-column-filters", UiGlyph.CLOSE, browser::clearFilters, false));
        }
        commands.add(new SeparatorMenuItem());
        commands.add(iconMenu("属性", "properties", UiGlyph.PROPERTIES, this::showProperties, false));
        commands.add(iconMenu("选项", "options", UiGlyph.SETTINGS, this::options, false));
        return commands;
    }

    private BrowserItem driveForTools() {
        if (context.location.page() == Page.COMPUTER && browser.selection().size() == 1) {
            BrowserItem drive = selectedItem();
            return WindowsPlaces.supportsDriveTools(drive) ? drive : null;
        }
        if (context.location.page() == Page.DIRECTORY && context.location.path().getParent() == null) {
            return places.computer().stream().filter(item -> context.location.path().equals(item.path()))
                    .filter(WindowsPlaces::supportsDriveTools).findFirst().orElse(null);
        }
        return null;
    }

    private void driveTool(BrowserItem drive, String label, FileAction action, boolean reload) {
        if (drive == null || fileTask != null) { return; }
        runFileTask(task(ignored -> {
            action.run();
            return null;
        }), "正在打开 Windows「" + label + "」…", false, ignored -> {
            if (reload) { loadPlaces(false); }
            else { updateListingStatus("已打开 Windows「" + label + "」"); }
        });
    }

    private ContextMenu contextMenu(BrowserItem item) {
        if (places == null || closing) { return null; }
        boolean unavailable = fileCommandsUnavailable();
        boolean transferUnavailable = unavailable || clipboardCommand != null;
        List<MenuItem> commands = new ArrayList<>();
        if (item == null) {
            commands.add(menuItem("刷新", "context-refresh", () -> fire("refresh")));
            commands.add(submenu("排序", sortItems()));
            commands.add(submenu("查看", viewItems()));
            if (context.location.path() != null) {
                commands.add(new SeparatorMenuItem());
                if (canCreate()) { commands.add(submenu("新建", newItems())); }
                commands.add(menuItem("粘贴", "context-paste", this::paste, transferUnavailable || !clipboardHasFiles()));
            }
        } else {
            commands.add(menuItem("打开", "context-open", () -> open(item)));
            if (item.directory() && item.path() != null) {
                commands.add(menuItem("在新标签页中打开", "context-open-tab", () -> openTab(Location.folder(item.path()))));
            }
            if (item.entry() != null && (context.location.path() == null || !context.activeQuery.isEmpty())) {
                commands.add(menuItem("打开所在文件夹", "context-parent", () -> navigate(Location.folder(item.path().getParent()))));
            }
            if (canModify(browser.selection())) {
                commands.add(new SeparatorMenuItem());
                commands.add(menuItem("剪切", "context-cut", () -> rememberSelection(true), transferUnavailable));
                commands.add(menuItem("复制", "context-copy", () -> rememberSelection(false), transferUnavailable));
                commands.add(menuItem("重命名", "context-rename", this::renameSelected,
                        unavailable || browser.selection().size() != 1));
                commands.add(menuItem("移到回收站", "context-trash", this::trashSelected, unavailable));
            }
            if (item.directory() && item.path() != null) {
                commands.add(menuItem("粘贴到此文件夹", "context-paste-folder", () -> pasteInto(item.path(), item),
                        transferUnavailable || !clipboardHasFiles()));
            }
            if (item.path() != null) {
                commands.add(menuItem("复制为路径", "context-copy-path", this::copyPaths, transferUnavailable));
            }
            if (item.directory() && item.path() != null && item.kind() != BrowserItem.Kind.DRIVE) {
                commands.add(pinItem(item.path()));
            }
            if (item.entry() != null) {
                commands.add(menuItem(favorites.contains(item.path()) ? "从收藏夹移除" : "添加到收藏夹",
                        "context-favorite", () -> toggleFavorite(item.path())));
            }
            if (context.location.page() == Page.NETWORK && networkLocations.contains(item.path())) {
                commands.add(menuItem("移除网络位置", "remove-network", () -> removeNetwork(item.path())));
            }
        }
        commands.add(new SeparatorMenuItem());
        commands.add(menuItem("属性", "context-properties", item == null ? this::showLocationProperties : () -> showProperties(item)));
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
                setText(empty || node == null || isDivider ? null : node.label());
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
        if (node.location() != null) { navigate(node.location()); }
        else if (node.item() != null) { open(node.item()); }
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
        root.getChildren().addAll(List.of(pageItem(Page.HOME), pageItem(Page.GALLERY)));
        for (BrowserItem cloud : places.oneDrive()) { root.getChildren().add(directoryItem(cloud, expanded)); }
        quickAccessStart = new TreeItem<>(new SideNode("", null, null));
        quickAccessEnd = new TreeItem<>(new SideNode("", null, null));
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

    private static TreeItem<SideNode> pageItem(Page page) {
        return new TreeItem<>(new SideNode(page.title, Location.page(page), null));
    }

    private void markLocation() {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            var item = tree.getTreeItem(i);
            if (item != null && context.location.equals(item.getValue().location())) {
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
                if (homeLayout()) { refreshOverview(); }
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
            refreshOverview();
            markLocation();
            refreshModifiedFolders();
        }, false);
    }

    private void visitFolder(Location target) {
        if (target.path() != null) {
            rememberVisit("recentFolders", folderHistory, target.path());
        }
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
                (context.quickAccessExpanded ? UiGlyph.DOWN : UiGlyph.RIGHT).graphic(12, "command-symbol"));
        quickAccess.setId("home-quick-access");
        quickAccess.getStyleClass().add("home-section");
        quickAccess.setOnAction(event -> {
            context.quickAccessExpanded = !context.quickAccessExpanded;
            browser.refreshHomeHeader();
            Platform.runLater(() -> {
                if (!shows(owner, version)) { return; }
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
        Button expandFiles = new Button(null, (expanded ? UiGlyph.DOWN : UiGlyph.RIGHT).graphic(12, "command-symbol"));
        expandFiles.setId("home-expand-files");
        expandFiles.setAccessibleText(expanded ? "折叠文件列表" : "展开文件列表");
        expandFiles.getStyleClass().add("home-section");
        expandFiles.setOnAction(event -> {
            browser.toggleHomeFiles();
            Platform.runLater(() -> {
                if (!shows(owner, version)) { return; }
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
        if (pinned) { detail.getChildren().addFirst(UiGlyph.PIN.graphic(10, "command-symbol")); }
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
        Button button = new Button(label, glyph.graphic(13, "home-tab-icon"));
        button.setId("home-" + tab.name().toLowerCase(Locale.ROOT));
        button.getStyleClass().add(tab == context.homeTab ? "active-tab" : "home-tab");
        button.setOnAction(event -> {
            if (!browser.homeFilesExpanded()) { browser.toggleHomeFiles(); }
            if (tab == context.homeTab && context.browseRequest == null) { return; }
            startBrowse(new BrowseIntent(context.location, context.activeQuery, tab, "", false, () -> {}));
        });
        return button;
    }

    private void toggleFavorite(Path path) {
        if (!favorites.remove(path)) { favorites.add(path); }
        savePaths("favorites", favorites);
        if (context.location.page() == Page.HOME && context.homeTab == HomeTab.FAVORITES) { refresh(""); }
    }

    private List<MenuItem> galleryItems() {
        List<MenuItem> choices = new ArrayList<>();
        choices.add(menuItem("添加文件夹…", "add-gallery", () -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("添加图片来源");
            var selected = chooser.showDialog(stage);
            if (selected != null && galleryFolders.add(selected.toPath())) {
                savePaths("gallery", galleryFolders);
                refresh("");
            }
        }));
        if (galleryFolders.size() > 1) {
            choices.add(submenu("移除来源", galleryFolders.stream().map(path -> menuItem(path.toString(), null, () -> {
                galleryFolders.remove(path);
                savePaths("gallery", galleryFolders);
                refresh("");
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
            navigate(Location.page(Page.NETWORK));
        });
    }

    private void removeNetwork(Path path) {
        networkLocations.remove(path);
        savePaths("network", networkLocations);
        rebuildSidebar();
        refresh("");
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

    private void selectionChanged() {
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

    private static Selection observeSelection(BrowserItem item) {
        if (item.entry() == null) { return null; }
        try {
            var selection = FileChanges.capture(item.entry());
            return selection.nativeItem() == null ? null : selection;
        } catch (IOException | SecurityException unavailable) {
            return null;
        }
    }

    private void changePane(Pane next) {
        context.pane = context.pane == next ? Pane.NONE : next;
        inspector.setVisible(context.pane != Pane.NONE);
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
        if (browser.selection().size() > 1) {
            info("属性", "选中了 " + browser.selection().size() + " 个项目");
            return;
        }
        BrowserItem selected = selectedItem();
        if (selected != null) {
            showProperties(selected);
        } else {
            showLocationProperties();
        }
    }

    private void showLocationProperties() {
        Path path = context.location.path();
        if (path == null) {
            info("属性", context.location.label() + " · " + browser.items().size() + " 个项目");
        } else if (WindowsPlaces.WINDOWS) {
            showNativeProperties(path);
        } else {
            var drive = places.computer().stream().filter(item -> item.disk() != null && path.equals(item.path())).findFirst();
            if (drive.isPresent()) {
                showProperties(drive.get());
            } else {
                var owner = context;
                long version = owner.listingVersion;
                readDetail(task(ignored -> BrowserItem.file(LocalFiles.describe(path))),
                        item -> { if (shows(owner, version)) { showProperties(item); } },
                        failure -> { if (shows(owner, version)) { showError(failure); } });
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

    private void open(BrowserItem item) {
        if (closing || item == null) { return; }
        if (item.directory()) {
            navigate(Location.folder(item.path()));
        } else if (item.kind() == BrowserItem.Kind.SHELL) {
            readDetail(task(ignored -> { WindowsPlaces.openShortcut(item.shellTarget()); return null; }),
                    ignored -> updateListingStatus("已打开「" + item.name() + "」"), this::showError);
        } else {
            openFile(item.path(), item.name());
        }
    }

    private void openFile(Path path, String name) {
        readDetail(task(ignored -> { LocalFiles.open(path); return null; }), ignored -> {
            rememberVisit("recentFiles", recentFiles, path);
            if (path.getParent() != null) {
                rememberVisit("recentFolders", folderHistory, path.getParent());
            }
            if (context.location.page() == Page.HOME && context.homeTab == HomeTab.RECENT) { refresh("已打开「" + name + "」"); }
            else { status.setText("已打开「" + name + "」"); }
        }, this::showError);
    }

    private boolean canCreate() {
        return !fileCommandsUnavailable() && context.location.path() != null && context.activeQuery.isEmpty();
    }

    private void create(NewItems.Type type) {
        if (!canCreate() || places == null) { return; }
        Path directory = context.location.path();
        switch (type.kind()) {
            case COMMAND -> runFileTask(task(ignored -> {
                WindowsPlaces.launchNewCommand(directory, type.value());
                return null;
            }), "正在打开「" + type.label() + "」的创建向导…", false,
                    ignored -> status.setText("已打开「" + type.label() + "」的创建向导"));
            case SHORTCUT -> chooseText("创建快捷方式", "目标路径或网址", "", input -> {
                NewItems.Shortcut shortcut = NewItems.Shortcut.parse(directory, input);
                createItem(type.label(), ignored -> NewItems.createShortcut(directory, type, shortcut));
            });
            default -> createItem(type.label(), ignored -> NewItems.create(directory, type));
        }
    }

    private void createItem(String label, Work<NewItems.Created> work) {
        if (fileCommandsUnavailable()) { return; }
        BrowseContext owner = context;
        Location origin = owner.location;
        long view = owner.listingVersion;
        runFileTask(task(cancelled -> {
            var created = work.read(cancelled);
            try {
                return new CreatedItem(created, FileChanges.capture(created.entry()));
            } catch (IOException | SecurityException unavailable) {
                return new CreatedItem(created, null);
            }
        }), "正在新建" + label + "…", true, result -> {
            var created = result.created();
            undo = List.of(FileChanges.undoCreation(created.path()));
            recordFolderEdits(Stream.of(created.path().getParent()));
            rebuildSidebar();
            if (context.browseRequest != null || !shows(owner, view) || !context.location.equals(origin)) {
                refreshAffected(BrowseImpact.ofParent(created.path()), "已新建" + label, () -> {});
                return;
            }
            request(context.location, "", "已新建" + label, () -> {
                if (browser.filtersActive() && browser.items().stream().noneMatch(item -> created.path().equals(item.path()))) {
                    browser.clearFilters();
                    updateListingStatus("已新建" + label);
                }
                if (result.proof() == null) {
                    updateListingStatus("无法确认新建项目身份，请重新选择后改名");
                } else {
                    editName(result.proof(), created.extension(), true);
                }
            });
        });
    }

    private void renameSelected() {
        BrowserItem selected = selectedItem();
        if (selected == null || selected.entry() == null || browser.selection().size() != 1 || fileCommandsUnavailable()) { return; }
        String extension = selected.name().substring(LocalFiles.extensionStart(selected.name(), selected.directory()));
        BrowseContext owner = context;
        long version = owner.listingVersion;
        Selection known = owner.selectionProof.get(selected);
        runFileTask(task(ignored -> captureSelected(selected, known)), "正在核对所选项目…", false, selection -> {
            updateListingStatus("");
            if (shows(owner, version) && owner.browseRequest == null && selectedItem() == selected) {
                editName(selection, extension, false);
            }
        });
    }

    private void editName(Selection selection, String extension, boolean keepExtension) {
        if (context.watchTarget != null && selection.needsPathConfirmation()) {
            updateListingStatus("无法确认项目身份，已取消行内编辑");
            return;
        }
        browser.editName(selection.path(), extension, keepExtension, name -> renameEntry(selection, name));
    }

    private void renameEntry(Selection entry, String name) {
        if (fileCommandsUnavailable() || name.isBlank()) { return; }
        try {
            Path target = LocalFiles.childNamed(entry.path().getParent(), name);
            if (entry.path().toString().equals(target.toString())
                    || !confirmCurrentPaths(List.of(entry), Selection::needsPathConfirmation, "重命名", false)) {
                updateListingStatus("");
                return;
            }
            Change change = FileChanges.rename(entry, target, entry.needsPathConfirmation());
            BrowseContext owner = context;
            Location origin = owner.location;
            applyChanges(Kind.RENAME, "正在重命名…", "已重命名", List.of(change), ignored -> { },
                    () -> { if (context == owner && context.location.equals(origin)) { browser.reveal(target); } });
        } catch (InvalidPathException exception) {
            showError(exception);
        }
    }

    private void rememberSelection(boolean move) {
        List<BrowserItem> selected = browser.selection();
        if (!canModify(selected) || fileCommandsUnavailable() || clipboardCommand != null) { return; }
        if (selected.size() > ClipboardFiles.MAX_ITEMS) {
            updateListingStatus("一次最多可复制或剪切 " + ClipboardFiles.MAX_ITEMS + " 个项目");
            return;
        }
        captureSelection(selected, sources -> {
            var token = UUID.randomUUID();
            lastClipboardToken = token;
            runClipboardTask(task(ignored -> {
                var eligible = new LinkedHashMap<Path, Selection>();
                for (var source : sources) { eligible.putIfAbsent(source.path(), source); }
                var paths = ClipboardFiles.paths(eligible.keySet().stream().map(Path::toString).toList());
                var offer = new FileClipboard.Offer(token, move, paths);
                var state = clipboard.publish(offer);
                return new Transfer(state, token, move, List.copyOf(eligible.values()));
            }), published -> {
                transfer = published;
                observeClipboard(published.state());
                updateListingStatus(move ? "已剪切，可在当前窗口粘贴" : "已复制到文件剪贴板");
            });
        });
    }

    private void captureSelection(List<BrowserItem> selected, Consumer<List<Selection>> ready) {
        var known = context.selectionProof;
        runFileTask(task(ignored -> {
            List<Selection> result = new ArrayList<>();
            for (BrowserItem item : selected) {
                result.add(captureSelected(item, known.get(item)));
            }
            return List.copyOf(result);
        }), "正在核对所选项目…", false, ready);
    }

    private static Selection captureSelected(BrowserItem item, Selection known) throws IOException {
        if (known == null) { return FileChanges.capture(item.entry()); }
        NtfsFileOperations.verify(known.nativeItem());
        return known;
    }

    private void fileClipboardKey(KeyEvent event) {
        if (!event.isShortcutDown() || event.isAltDown()
                || event.getTarget() instanceof TextInputControl
                || stage.getScene().getFocusOwner() instanceof TextInputControl) { return; }
        switch (event.getCode()) {
            case C -> { if (event.isShiftDown()) { copyPaths(); } else { fire("copy"); } }
            case X -> { if (event.isShiftDown()) { return; } fire("cut"); }
            case V -> { if (event.isShiftDown()) { return; } paste(); }
            default -> { return; }
        }
        event.consume();
    }

    private void copyPaths() {
        if (fileCommandsUnavailable() || clipboardCommand != null) { return; }
        var paths = browser.selection().stream().map(BrowserItem::path).filter(Objects::nonNull).distinct().toList();
        if (paths.isEmpty()) { return; }
        runClipboardTask(task(ignored -> clipboard.text(ClipboardFiles.quotedPaths(paths))), state -> {
            observeClipboard(state);
            updateListingStatus("已复制 " + paths.size() + " 个路径");
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

    private <T> void runClipboardTask(Task<T> command, Consumer<T> success) {
        runClipboardTask(command, false, success);
    }

    private <T> void runClipboardTask(Task<T> command, boolean pasteRead, Consumer<T> success) {
        if (closing || clipboardCommand != null) { return; }
        clipboardCommand = command;
        clipboardExpired = false;
        if (pasteRead) {
            clipboardDeadline.setOnFinished(event -> {
                clipboardExpired = true;
                clipboardReadFailed();
                updateListingStatus("等待剪贴板超时，请稍后重试");
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
            updateListingStatus(Objects.requireNonNullElse(failure.getMessage(), "剪贴板操作失败"));
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
        if (context.location.path() != null) { pasteInto(context.location.path(), null); }
    }

    private void pasteInto(Path directory, BrowserItem folder) {
        if (fileCommandsUnavailable() || clipboardCommand != null) { return; }
        var target = new PasteTarget(directory, directory.equals(context.location.path()) ? context.directoryIdentity : null,
                folder, folder == null ? null : context.selectionProof.get(folder));
        updateListingStatus("正在读取文件剪贴板…");
        runClipboardTask(task(ignored -> clipboard.read()), true, data -> {
            observeClipboard(data.state());
            var request = pasteRequest(data);
            if (request != null && pasteCanContinue()) { preparePaste(target, request); }
        });
    }

    private PasteRequest pasteRequest(Snapshot data) {
        if (transfer != null && transfer.matches(data)) {
            if (!transfer.sources().isEmpty()) { return new PasteRequest(data, transfer, List.of()); }
            updateListingStatus("此批次暂无可粘贴项目，可在「核查结果」查看未知项");
            return null;
        }
        if (data.externalMove()) {
            unsupportedMove = data.state();
            describeTransfer();
            updateListingStatus("未粘贴外部剪切项目");
            info("无法粘贴外部剪切", "请回到来源窗口，选中这些项目并使用「复制」，然后再粘贴。");
        } else if (data.state().kind() == FileClipboard.Kind.CUT) {
            updateListingStatus("请返回发起剪切的窗口粘贴，跨窗口传输可使用复制");
        } else if (data.token() != null && data.token().equals(lastClipboardToken)) {
            updateListingStatus("剪贴板会话已失效，请重新选择并复制");
        } else if (data.state().kind() == FileClipboard.Kind.FILES) {
            var paths = externalExclusion == null ? data.paths()
                    : data.paths().stream().filter(path -> !externalExclusion.paths().contains(path)).toList();
            if (!paths.isEmpty()) { return new PasteRequest(data, null, paths); }
            updateListingStatus("此剪贴板批次的项目需要核查，请重新选择");
        } else {
            updateListingStatus(data.state().kind() == FileClipboard.Kind.UNSUPPORTED
                    ? "仅支持有实际路径的文件和文件夹，请先保存此项目" : "剪贴板中没有可粘贴的文件");
        }
        return null;
    }

    private boolean pasteCanContinue() {
        if (!closing && fileTask == null) { return true; }
        if (!closing) { updateListingStatus("有其他文件任务正在执行，请重新发起粘贴"); }
        return false;
    }

    private void preparePaste(PasteTarget target, PasteRequest pending) {
        runFileTask(task(cancelled -> {
            var destination = FileChanges.destination(target.path());
            var expected = target.knownDirectory();
            if (expected != null && expected.known() && (expected.ntfs() != null
                    ? !expected.ntfs().equals(destination.directory())
                    : !expected.equals(DirectoryIdentity.read(target.path())))) {
                throw new IOException("粘贴目标文件夹已变化，请重新选择");
            }
            if (target.folder() != null && target.folder().entry() != null) {
                var source = captureSelected(target.folder(), target.knownFolder());
                if (source.nativeItem() != null && (destination.directory() == null
                        || !source.nativeItem().id().equals(destination.directory().id()))) {
                    throw new IOException("粘贴目标文件夹已变化，请重新选择");
                }
            }
            if (pending.local() != null) { return new PreparedPaste(destination, pending.local().sources()); }
            var sources = new ArrayList<Selection>();
            for (Path path : pending.external()) {
                if (cancelled.getAsBoolean()) { throw new IOException("已取消粘贴准备"); }
                var entry = LocalFiles.describe(path);
                if (entry.link() || entry.other()) { throw new IOException("此剪贴板含链接或特殊项目，请重新选择：" + path); }
                sources.add(FileChanges.capture(entry));
            }
            return new PreparedPaste(destination, List.copyOf(sources));
        }), "正在核对粘贴来源和目标…", false, prepared -> {
            if (!pasteCanContinue()) { return; }
            var destination = prepared.destination();
            var sources = prepared.sources();
            Predicate<Selection> unverified = source -> source.needsPathConfirmation(destination, pending.move());
            if (!confirmCurrentPaths(sources, unverified, pending.move() ? "移动" : "复制", false)) {
                updateListingStatus("");
                return;
            }
            List<Change> changes = sources.stream()
                    .map(source -> FileChanges.transfer(source, destination, pending.move(), unverified.test(source))).toList();
            applyChanges(pending.move() ? Kind.MOVE : Kind.COPY, pending.move() ? "正在移动…" : "正在复制…",
                    pending.move() ? "已移动" : "已复制", changes, outcome -> clipboardAfterPaste(pending, sources, outcome),
                    () -> {});
        });
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
        browser.cutItems(Set.of());
        verifyCutMarks();
        if (remaining.sources().size() == local.sources().size()) { return; }
        // Partial cuts keep their private marker and update their in-memory list.
        if (local.move() && !remaining.sources().isEmpty()) { return; }
        FileClipboard.Offer replacement = remaining.sources().isEmpty() ? null : remaining.offer();
        runClipboardTask(task(ignored -> clipboard.replace(local.state(), local.token(), replacement)), result -> {
            if (result.applied() && transfer == remaining) {
                transfer = remaining.sources().isEmpty() ? null
                        : new Transfer(result.state(), remaining.token(), remaining.move(), remaining.sources());
            }
            observeClipboard(result.state());
            describeTransfer();
        });
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

    private void trashSelected() {
        List<BrowserItem> selected = browser.selection();
        if (!canModify(selected) || fileCommandsUnavailable()) { return; }
        captureSelection(selected, entries -> {
            if (!confirmCurrentPaths(entries, Selection::needsPathConfirmation, "移到回收站", true)) {
                updateListingStatus("");
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
            if (owner != context && dependsOn(impact, owner.location, owner.activeQuery, owner.homeTab)) {
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
        boolean affectsCurrent = dependsOn(impact, context.location, context.activeQuery, context.homeTab);
        if (affectsCurrent && context.browseRequest != null && context.browseRequest.intent().automatic()) {
            context.pendingFileRefresh = new FileRefresh(message, reloaded);
            return;
        }
        if (affectsCurrent) { context.pendingFileRefresh = null; }
        if (context.browseRequest == null) {
            if (affectsCurrent && context.needsReload) { reloadContext(message); }
            else if (affectsCurrent) { request(context.location, context.activeQuery, message, reloaded); }
            else { updateListingStatus(message); }
        } else {
            if (affectsCurrent) { context.deferredRefresh = message; }
            BrowseIntent pending = context.browseRequest.intent();
            if (dependsOn(impact, pending.location(), pending.query(), pending.tab())) {
                startBrowse(pending);
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
        Location previous = context.location;
        request(target, "", "请在当前目录核查项目", () -> {
            if (!target.equals(previous)) {
                context.past.addFirst(previous);
                context.future.clear();
            }
            browser.reveal(path);
        });
    }

    private <T> void readDetail(Task<T> task, Consumer<T> success, Consumer<Throwable> failure) {
        if (closing) { return; }
        task.setOnSucceeded(event -> { if (!closing) { success.accept(task.getValue()); } });
        task.setOnFailed(event -> { if (!closing) { failure.accept(task.getException()); } });
        if (!reads.detail(task)) { failure.accept(new IOException("读取队列暂满，请稍后重试")); }
    }

    private <T> void runFileTask(Task<T> task, String running, boolean writesDisk, Consumer<T> success) {
        if (fileTask != null || closing) { return; }
        if (context.browseRequest != null && context.browseRequest.intent().automatic()) {
            if (context.watchTarget != null) { queueWatchChanges(context.browseRequest.intent().changes()); }
            cancelBrowse();
        }
        fileTask = task;
        writing = writesDisk;
        if (context.browseRequest == null) { status.setText(running); }
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
                updateListingStatus("操作失败");
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
        boolean writableFolder = !commandsUnavailable && context.location.path() != null;
        unknownResults.setDisable(unavailable);
        writeNotice.setVisible(fileTask != null);
        List<BrowserItem> selected = browser.selection();
        boolean editable = !commandsUnavailable && canModify(selected);
        tree.setDisable(unavailable);
        syncNavigationPane();
        overview.setDisable(unavailable);
        address.setDisable(unavailable);
        searchField.setDisable(unavailable);
        controls.get("back").setDisable(unavailable || context.past.isEmpty());
        controls.get("forward").setDisable(unavailable || context.future.isEmpty());
        controls.get("up").setDisable(unavailable || parentLocation() == null);
        controls.get("refresh").setDisable(unavailable);
        controls.get("search").setDisable(unavailable);
        controls.get("cancel-search").setVisible(isSearching());
        controls.get("clear-search").setVisible(!isSearching() && !context.activeQuery.isEmpty());
        controls.get("clear-search").setDisable(unavailable);
        controls.get("new").setDisable(unavailable || !canCreate());
        controls.get("copy").setDisable(!editable || clipboardCommand != null);
        controls.get("cut").setDisable(!editable || clipboardCommand != null);
        controls.get("trash").setDisable(!editable);
        controls.get("rename").setDisable(!editable || selected.size() != 1);
        controls.get("paste").setDisable(!writableFolder || clipboardCommand != null || !clipboardHasFiles());
        for (String id : List.of("sort", "view", "more", "gallery-sources")) { controls.get(id).setDisable(unavailable); }
        controls.get("gallery-sources").setVisible(context.location.page() == Page.GALLERY);
        controls.get("details-pane").setDisable(places == null);
        progress.setVisible((context.browseRequest != null && !context.browseRequest.intent().automatic()) || fileTask != null || catalogTask != null);
    }

    private boolean fileCommandsUnavailable() {
        return closing || places == null || fileTask != null || context.directoryUnavailable
                || context.needsReload
                || (context.browseRequest != null && !context.browseRequest.intent().automatic());
    }

    private BrowserItem selectedItem() { return browser.selection().stream().findFirst().orElse(null); }

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
        return preferences.get(key, "").lines().filter(value -> !value.isBlank()).map(Path::of).toList();
    }

    private void savePaths(String key, Collection<Path> paths) {
        saveLines(key, paths.stream().map(Path::toString));
    }

    private void loadVisits(String key, Map<Path, FileTime> destination) {
        preferences.get(key, "").lines().forEach(line -> {
            int separator = line.indexOf('\t');
            if (separator > 0) {
                destination.put(Path.of(line.substring(separator + 1)),
                        FileTime.fromMillis(Long.parseLong(line.substring(0, separator))));
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

    private Button navButton(String label, String help, String id, Runnable action) {
        Button button = button(label, id, action);
        button.setTooltip(new Tooltip(help));
        button.setAccessibleText(help);
        button.getStyleClass().add("nav-button");
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
        item.setGraphic(glyph.graphic(15, "command-symbol"));
        return item;
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

    private static <T> Task<T> task(Work<T> work) {
        return new Task<>() {
            @Override
            protected T call() throws Exception { return work.read(this::isCancelled); }
        };
    }

    public static void main(String[] args) { launch(args); }
}

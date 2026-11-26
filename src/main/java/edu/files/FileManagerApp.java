package edu.files;

import edu.files.BrowserView.Field;
import edu.files.BrowserView.Mode;
import edu.files.FileChanges.Change;
import edu.files.FileChanges.Outcome;
import edu.files.FileChanges.Selection;
import edu.files.FileChanges.State;
import edu.files.FileOperationTask.Kind;
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

    private enum Page {
        HOME("主文件夹", SystemIcons.Namespace.HOME), GALLERY("图库", SystemIcons.Namespace.GALLERY),
        COMPUTER("此电脑", SystemIcons.Namespace.COMPUTER), NETWORK("网络", SystemIcons.Namespace.NETWORK),
        LINUX("Linux", SystemIcons.Namespace.LINUX), DIRECTORY("", null);
        final String title;
        final SystemIcons.Namespace namespace;

        Page(String title, SystemIcons.Namespace namespace) {
            this.title = title;
            this.namespace = namespace;
        }
    }

    private enum HomeTab { RECENT, FAVORITES, SHARED }
    private enum Pane { NONE, DETAILS, PREVIEW }

    private record Location(Page page, Path path) {
        static Location page(Page page) { return new Location(page, null); }
        static Location folder(Path path) { return new Location(Page.DIRECTORY, path.toAbsolutePath().normalize()); }

        String label() {
            if (path == null) { return page.title; }
            return path.getFileName() == null ? path.toString() : path.getFileName().toString();
        }
    }

    private record View(Mode mode, Field sort, boolean descending, Field group) { }
    private record SideNode(String label, Location location, BrowserItem item) {
        boolean divider() { return label.isEmpty(); }
    }
    private record Listing(List<BrowserItem> items, int unreadable, boolean limited, Map<Path, Double> imageRatios) {
        Listing(List<BrowserItem> items, int unreadable, boolean limited) {
            this(items, unreadable, limited, Map.of());
        }
    }
    private record SystemCatalog(WindowsPlaces.Places places, List<NewItems.Type> newTypes) { }
    private record Transfer(List<Selection> sources, boolean move) { }

    @FunctionalInterface
    private interface FileAction { void run() throws IOException; }

    @FunctionalInterface
    private interface Work<T> { T read(BooleanSupplier cancelled) throws Exception; }

    private final ExecutorService disk = Executors.newSingleThreadExecutor(work -> {
        return new Thread(work, "file-manager-disk");
    });
    private final Preferences preferences;
    private final BiFunction<Kind, List<Change>, FileOperationTask> operationFactory;
    private final Map<Page, View> views = new EnumMap<>(Page.class);
    private final Deque<Location> past = new ArrayDeque<>();
    private final Deque<Location> future = new ArrayDeque<>();
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
    private Location location = Location.page(Page.HOME);
    private HomeTab homeTab = HomeTab.RECENT;
    private boolean quickAccessExpanded = true;
    private Pane pane = Pane.NONE;
    private Listing listing = new Listing(List.of(), 0, false);
    private List<Change> undo = List.of();
    private Transfer transfer;
    private FileOperationProgress operationProgress;
    private FileOperationTask activeFileOperation;
    private LocalFiles.Visibility visibility;
    private Task<?> activeTask;
    private Task<?> previewTask;
    private Task<List<Path>> modifiedFoldersTask;
    private List<Path> modifiedFolders = List.of();
    private boolean modifiedFoldersRefreshQueued;
    private String activeQuery = "";
    private boolean writing;
    private boolean searching;
    private boolean extensions = true;
    private boolean navigationPane = true;
    private boolean compact;
    private boolean checkboxes;

    public FileManagerApp() {
        this(Preferences.userNodeForPackage(FileManagerApp.class).node("explorer"));
    }

    FileManagerApp(Preferences preferences) {
        this(preferences, FileOperationTask::new);
    }

    FileManagerApp(Preferences preferences, BiFunction<Kind, List<Change>, FileOperationTask> operationFactory) {
        this.preferences = preferences;
        this.operationFactory = operationFactory;
    }

    @Override
    public void start(Stage window) {
        stage = window;
        operationProgress = new FileOperationProgress(window, unknownResults);
        icons = new SystemIcons(window::getOutputScaleX);
        views.put(Page.HOME, new View(Mode.DETAILS, Field.VISITED, true, null));
        views.put(Page.COMPUTER, new View(Mode.TILES, Field.NAME, false, null));
        views.put(Page.GALLERY, new View(Mode.LARGE, Field.MODIFIED, true, Field.MODIFIED));
        browser = new BrowserView(icons, this::selectionChanged, this::open, this::sort, this::contextMenu,
                () -> updateListingStatus(""), this::homeHeader);
        browser.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getTarget() instanceof TextInputControl) { return; }
            KeyCode key = event.getCode();
            if (key == KeyCode.ENTER) {
                open(selectedItem());
            } else if (key == KeyCode.F2) {
                fire("rename");
            } else if (key == KeyCode.DELETE) {
                fire("trash");
            } else if (event.isShortcutDown() && key == KeyCode.C) {
                fire("copy");
            } else if (event.isShortcutDown() && key == KeyCode.X) {
                fire("cut");
            } else if (event.isShortcutDown() && key == KeyCode.V) {
                fire("paste");
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
        layout.setTop(new VBox(navigation(), commandBar()));
        layout.setCenter(workspace);
        layout.setBottom(statusBar());
        Scene scene = new Scene(layout, 1200, 760);
        scene.getStylesheets().add(getClass().getResource("file-manager.css").toExternalForm());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN), address::beginEdit);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.D, KeyCombination.ALT_DOWN), address::beginEdit);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                () -> { if (!newTypes.isEmpty()) { create(newTypes.getFirst()); } });
        stage.setTitle("本地文件管理器");
        stage.setMinWidth(960);
        stage.setMinHeight(550);
        stage.setScene(scene);
        stage.focusedProperty().addListener((observable, previous, focused) -> {
            if (focused) { refreshModifiedFolders(); }
        });
        stage.setOnCloseRequest(event -> {
            if (writing) {
                event.consume();
                status.setText("正在处理文件，请等待操作结束");
                if (activeFileOperation != null) { operationProgress.show(); }
            }
        });
        stage.show();
        loadPlaces(true);
    }

    @Override
    public void stop() {
        rememberNavigationWidth();
        preferences.putDouble("navigationWidth", navigationWidth);
        if (browser != null) { browser.cancelNewName(); }
        if (activeTask != null && !writing) { activeTask.cancel(); }
        if (previewTask != null) { previewTask.cancel(); }
        if (modifiedFoldersTask != null) { modifiedFoldersTask.cancel(); }
        if (activeFileOperation != null) {
            activeFileOperation.requestCancellation();
            disk.shutdown();
        } else if (writing) {
            disk.shutdown();
        } else {
            disk.shutdownNow();
        }
        if (operationProgress != null) { operationProgress.close(); }
        if (icons != null) { icons.close(); }
    }

    private HBox navigation() {
        HBox arrows = new HBox(4,
                navButton("‹", "后退", "back", () -> history(past, future)),
                navButton("›", "前进", "forward", () -> history(future, past)),
                navButton("↑", "上一级", "up", () -> navigate(parentLocation())),
                navButton("⟳", "刷新", "refresh", () -> {
                    if (location.page() == Page.COMPUTER) { loadPlaces(false); }
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
            if (searching && activeTask != null) { activeTask.cancel(); }
        });
        stop.managedProperty().bind(stop.visibleProperty());
        Button clear = button("退出搜索", "clear-search", () -> request(location, "", "", () -> {}));
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
        unknownResults.setId("unknown-results");
        unknownResults.setVisible(false);
        unknownResults.managedProperty().bind(unknownResults.visibleProperty());
        unknownResults.setOnAction(event -> inspectUnknownResults());
        HBox bar = new HBox(8, progress, status, clipboardStatus, operationProgress.launcher(), unknownResults);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void loadPlaces(boolean initial) {
        runTask(task(cancelled -> new SystemCatalog(WindowsPlaces.load(), NewItems.load())), "正在读取系统位置…", false, catalog -> {
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
            refresh("");
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
        if (activeTask != null || places == null) { return; }
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
            Path relativeTo = location.path() == null ? WindowsPlaces.USER_HOME : location.path();
            Path path = relativeTo.resolve(target).normalize();
            runTask(task(ignored -> Files.readAttributes(path, BasicFileAttributes.class).isDirectory()),
                    "正在打开地址…", false, directory -> {
                        if (directory) { navigate(Location.folder(path)); }
                        else { openFile(path, path.getFileName().toString()); }
                    });
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
        Path path = location.path();
        if (path == null) { return List.of(pageCrumb(location.page())); }
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
            var read = task(cancelled -> LocalFiles.readDirectory(path, filter, cancelled));
            read.setOnSucceeded(event -> complete.accept(new AddressBar.Children(read.getValue().entries().stream()
                    .filter(LocalFiles.Entry::directory).map(BrowserItem::file).map(this::folderChoice).toList(),
                    "没有可显示的子文件夹")));
            read.setOnFailed(event -> complete.accept(new AddressBar.Children(List.of(), "无法读取子文件夹")));
            disk.execute(read);
        });
    }

    private AddressBar.Choice folderChoice(BrowserItem item) {
        return new AddressBar.Choice(item.name(), () -> icons.graphic(item, 16), () -> navigate(Location.folder(item.path())));
    }

    private Location parentLocation() {
        if (location.path() == null) { return null; }
        Path path = location.path();
        if (path.getParent() != null) { return Location.folder(path.getParent()); }
        return Location.page(containerPage(path));
    }

    private void navigate(Location target) {
        if (target == null) { return; }
        Location previous = location;
        request(target, "", "", () -> {
            if (!previous.equals(target)) {
                past.push(previous);
                future.clear();
            }
            visitFolder(target);
        });
    }

    private void history(Deque<Location> from, Deque<Location> to) {
        if (from.isEmpty()) { return; }
        Location previous = location;
        Location target = from.peek();
        request(target, "", "", () -> {
            from.pop();
            to.push(previous);
            visitFolder(target);
        });
    }

    private void search() {
        String query = searchField.getText().strip();
        request(location, query, "", () -> {});
    }

    private void refresh(String message) {
        request(location, activeQuery, message, () -> {});
    }

    private void request(Location target, String query, String message, Runnable loaded) {
        if (places == null || activeTask != null) { return; }
        boolean cancellable = !query.isEmpty() || target.page() == Page.GALLERY;
        Work<Listing> work = listingWork(target, query);
        String loading = query.isEmpty() ? "正在读取「" + target.label() + "」…" : "正在搜索「" + query + "」…";
        runTask(task(work), loading, cancellable, result -> {
            if (!location.equals(target) || !activeQuery.equals(query)) { browser.resetFilters(); }
            location = target;
            activeQuery = query;
            listing = result;
            loaded.run();
            updateLocation();
            render(false);
            updateListingStatus(message);
        });
    }

    private void updateListingStatus(String message) {
        if (activeTask != null) { return; }
        String total = Integer.toString(listing.items().size());
        String count = browser.filtersActive() ? "显示 " + browser.items().size() + " / " + total : total;
        count += activeQuery.isEmpty() ? " 个项目" : " 个搜索结果";
        if (listing.limited()) { count += " · 已达结果上限"; }
        if (listing.unreadable() != 0) { count += " · " + listing.unreadable() + " 项无法读取"; }
        status.setText(message.isEmpty() ? count : message + " · " + count);
    }

    private Work<Listing> listingWork(Location target, String query) {
        LocalFiles.Visibility filters = visibility;
        String keyword = query.toLowerCase(Locale.ROOT);
        return switch (target.page()) {
            case DIRECTORY -> cancelled -> fromDisk(query.isEmpty()
                    ? LocalFiles.readDirectory(target.path(), filters, cancelled)
                    : LocalFiles.search(target.path(), query, filters, cancelled));
            case COMPUTER -> staticListing(places.computer(), keyword);
            case NETWORK -> staticListing(networkItems(), keyword);
            case LINUX -> staticListing(places.linux(), keyword);
            case GALLERY -> galleryWork(keyword, filters);
            case HOME -> homeWork(keyword);
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
                    var result = LocalFiles.images(source, keyword, filters, cancelled);
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

    private Work<Listing> homeWork(String keyword) {
        if (homeTab == HomeTab.SHARED) { return staticListing(networkItems(), keyword); }
        Map<Path, FileTime> source = new LinkedHashMap<>();
        if (homeTab == HomeTab.FAVORITES) {
            favorites.forEach(path -> source.put(path, null));
        } else {
            source.putAll(recentFiles);
            places.recent().forEach(recent -> source.putIfAbsent(recent.path(), recent.time()));
        }
        boolean onlyFiles = homeTab == HomeTab.RECENT;
        return cancelled -> {
            List<BrowserItem> results = new ArrayList<>();
            for (var entry : source.entrySet()) {
                if (cancelled.getAsBoolean()) { break; }
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

    private void updateLocation() {
        address.setLocation(location.path() == null ? location.label() : location.path().toString(), breadcrumbs());
        if (!Objects.equals(searchField.getText(), activeQuery)) { searchField.setText(activeQuery); }
        searchField.setPromptText("在「" + location.label() + "」中搜索");
        refreshOverview();
        markLocation();
    }

    private View currentView() {
        return views.getOrDefault(location.page(), new View(Mode.DETAILS, Field.NAME, false, null));
    }

    private boolean homeLayout() {
        return location.page() == Page.HOME && activeQuery.isEmpty();
    }

    private void render(boolean preserveSelection) {
        View config = currentView();
        boolean computer = location.page() == Page.COMPUTER;
        boolean gallery = location.page() == Page.GALLERY;
        boolean recent = location.page() == Page.HOME && homeTab == HomeTab.RECENT;
        Field sort = config.sort() == Field.VISITED && !recent ? Field.NAME : config.sort();
        browser.render(listing.items(), new BrowserView.Settings(config.mode(), sort, config.descending(), config.group(),
                compact, checkboxes, extensions, computer, !activeQuery.isEmpty(), recent, homeLayout(), gallery,
                listing.imageRatios()), preserveSelection);
    }

    private void sort(Field field, boolean descending) {
        View config = currentView();
        if (config.sort() != field || config.descending() != descending) {
            views.put(location.page(), new View(config.mode(), field, descending, config.group()));
            render(true);
        }
    }

    private void group(Field field) {
        View config = currentView();
        views.put(location.page(), new View(config.mode(), config.sort(), config.descending(), field));
        render(true);
    }

    private void mode(Mode mode) {
        View config = currentView();
        views.put(location.page(), new View(mode, config.sort(), config.descending(), config.group()));
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
            if (fields.indexOf(field) >= 3 && location.page() == Page.COMPUTER) { advanced.add(choice); }
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
        if (location.page() == Page.COMPUTER) {
            return List.of(Field.NAME, Field.TYPE, Field.SIZE, Field.AVAILABLE, Field.FILE_SYSTEM,
                    Field.COMMENT, Field.NETWORK, Field.USED);
        }
        List<Field> fields = new ArrayList<>(List.of(Field.NAME, Field.TYPE, Field.SIZE, Field.MODIFIED));
        if (location.page() == Page.HOME && homeTab == HomeTab.RECENT) { fields.add(Field.VISITED); }
        if (!activeQuery.isEmpty() || location.page() == Page.GALLERY || location.page() == Page.HOME) {
            fields.add(Field.LOCATION);
        }
        return fields;
    }

    private String fieldLabel(Field field) {
        return field == Field.SIZE && location.page() == Page.COMPUTER ? "总大小" : field.label;
    }

    private List<MenuItem> viewItems() {
        ToggleGroup modes = new ToggleGroup();
        List<MenuItem> result = new ArrayList<>();
        List<Mode> modesForPage = switch (location.page()) {
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
        result.add(checkItem("详细信息窗格", "show-details", pane == Pane.DETAILS, enabled -> changePane(Pane.DETAILS)));
        result.add(checkItem("预览窗格", "show-preview", pane == Pane.PREVIEW, enabled -> changePane(Pane.PREVIEW)));
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
        commands.add(iconMenu("撤消", "undo", UiGlyph.UNDO, this::undo, undo.isEmpty()));
        BrowserItem drive = driveForTools();
        if (windows && (location.page() == Page.COMPUTER || drive != null)) {
            commands.add(new SeparatorMenuItem());
            commands.add(iconMenu("清理", "clean-drive", UiGlyph.CLEAN,
                    () -> driveTool(drive, "清理", () -> WindowsPlaces.cleanDrive(drive.path()), false), drive == null));
            commands.add(iconMenu("优化", "optimize-drive", UiGlyph.DRIVE,
                    () -> driveTool(drive, "优化", WindowsPlaces::optimizeDrives, false), drive == null));
            commands.add(iconMenu("格式化", "format-drive", UiGlyph.FORMAT,
                    () -> driveTool(drive, "格式化", () -> WindowsPlaces.formatDrive(drive.path()), true), drive == null));
        }
        if (location.page() == Page.DIRECTORY) {
            commands.add(new SeparatorMenuItem());
            commands.add(pinItem(location.path()));
        }
        if (windows && (location.page() == Page.COMPUTER || location.page() == Page.NETWORK)) {
            commands.add(new SeparatorMenuItem());
            commands.add(iconMenu("连接到媒体服务器", "media-server", UiGlyph.MEDIA, this::connectMedia, false));
            commands.add(iconMenu("添加一个网络位置", "add-network", UiGlyph.NETWORK, this::addNetwork, false));
            commands.add(iconMenu("映射网络驱动器", "map-network", UiGlyph.DRIVE, this::mapNetworkDrive, false));
            commands.add(iconMenu("断开网络驱动器的连接", "disconnect-network", UiGlyph.DISCONNECT, this::disconnectNetworkDrive, false));
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
        if (location.page() == Page.COMPUTER && browser.selection().size() == 1) {
            BrowserItem drive = selectedItem();
            return WindowsPlaces.supportsDriveTools(drive) ? drive : null;
        }
        if (location.page() == Page.DIRECTORY && location.path().getParent() == null) {
            return places.computer().stream().filter(item -> location.path().equals(item.path()))
                    .filter(WindowsPlaces::supportsDriveTools).findFirst().orElse(null);
        }
        return null;
    }

    private void driveTool(BrowserItem drive, String label, FileAction action, boolean reload) {
        if (drive == null || activeTask != null) { return; }
        runTask(task(ignored -> {
            action.run();
            return null;
        }), "正在打开 Windows「" + label + "」…", false, ignored -> {
            if (reload) { loadPlaces(false); }
            else { updateListingStatus("已打开 Windows「" + label + "」"); }
        });
    }

    private ContextMenu contextMenu(BrowserItem item) {
        if (activeTask != null || places == null) { return null; }
        List<MenuItem> commands = new ArrayList<>();
        if (item == null) {
            commands.add(menuItem("刷新", "context-refresh", () -> fire("refresh")));
            commands.add(submenu("排序", sortItems()));
            commands.add(submenu("查看", viewItems()));
            if (location.path() != null) {
                commands.add(new SeparatorMenuItem());
                if (canCreate()) { commands.add(submenu("新建", newItems())); }
                commands.add(menuItem("粘贴", "context-paste", this::paste, transfer == null));
            }
        } else {
            commands.add(menuItem("打开", "context-open", () -> open(item)));
            if (item.entry() != null && (location.path() == null || !activeQuery.isEmpty())) {
                commands.add(menuItem("打开所在文件夹", "context-parent", () -> navigate(Location.folder(item.path().getParent()))));
            }
            if (canModify(browser.selection())) {
                commands.add(new SeparatorMenuItem());
                commands.add(menuItem("剪切", "context-cut", () -> rememberSelection(true)));
                commands.add(menuItem("复制", "context-copy", () -> rememberSelection(false)));
                if (item.directory()) {
                    commands.add(menuItem("粘贴到此文件夹", "context-paste-folder", () -> pasteInto(item.path()), transfer == null));
                }
                commands.add(menuItem("重命名", "context-rename", this::renameSelected, browser.selection().size() != 1));
                commands.add(menuItem("移到回收站", "context-trash", this::trashSelected));
            }
            if (item.directory() && item.path() != null && item.kind() != BrowserItem.Kind.DRIVE) {
                commands.add(pinItem(item.path()));
            }
            if (item.entry() != null) {
                commands.add(menuItem(favorites.contains(item.path()) ? "从收藏夹移除" : "添加到收藏夹",
                        "context-favorite", () -> toggleFavorite(item.path())));
            }
            if (location.page() == Page.NETWORK && networkLocations.contains(item.path())) {
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
                            && getItem().location().path().getParent() != null && activeTask == null) {
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
        var branch = new TreeItem<>(new SideNode(item.name(), Location.folder(path), item));
        branch.getChildren().add(new TreeItem<>(new SideNode("正在读取…", null, null)));
        branch.expandedProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue || branch.getChildren().size() != 1
                    || !branch.getChildren().getFirst().getValue().label().equals("正在读取…")) { return; }
            LocalFiles.Visibility filter = visibility;
            var task = task(cancelled -> LocalFiles.readDirectory(path, filter, cancelled));
            task.setOnSucceeded(event -> branch.getChildren().setAll(task.getValue().entries().stream()
                    .filter(LocalFiles.Entry::directory).map(BrowserItem::file)
                    .map(child -> directoryItem(child, expanded)).toList()));
            task.setOnFailed(event -> branch.getChildren().setAll(List.of(
                    new TreeItem<>(new SideNode("无法读取此文件夹", null, null)))));
            disk.execute(task);
        });
        if (expanded.contains(path)) { branch.setExpanded(true); }
        return branch;
    }

    private static TreeItem<SideNode> branch(String label, Location location) {
        var branch = new TreeItem<>(new SideNode(label, location, null));
        branch.setExpanded(true);
        return branch;
    }

    private static TreeItem<SideNode> pageItem(Page page) {
        return new TreeItem<>(new SideNode(page.title, Location.page(page), null));
    }

    private void markLocation() {
        for (int i = 0; i < tree.getExpandedItemCount(); i++) {
            var item = tree.getTreeItem(i);
            if (item != null && location.equals(item.getValue().location())) {
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
                known, recorded, WindowsPlaces.indexedModifiedItems(), filters, cancelled));
        modifiedFoldersTask = scan;
        scan.setOnSucceeded(event -> {
            List<BrowserItem> previous = latestFolders();
            modifiedFolders = scan.getValue();
            if (!previous.equals(latestFolders())) {
                refreshQuickAccess();
                markLocation();
                if (homeLayout()) { refreshOverview(); }
            }
            finishModifiedFoldersRefresh();
        });
        scan.setOnFailed(event -> finishModifiedFoldersRefresh());
        Thread.startVirtualThread(scan);
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
        } else if (location.page() == Page.NETWORK) {
            Button discover = new Button("浏览 Windows 网络");
            discover.setDisable(!WindowsPlaces.WINDOWS);
            discover.setOnAction(event -> browseNetwork());
            Button add = new Button("添加网络位置");
            add.setDisable(!WindowsPlaces.WINDOWS);
            add.setOnAction(event -> addNetwork());
            overview.getChildren().add(new HBox(8, discover, add));
        } else if (location.page() == Page.LINUX && listing.items().isEmpty()) {
            overview.getChildren().add(new Label("Windows 中没有可用的 WSL 发行版"));
        }
        overview.setVisible(!overview.getChildren().isEmpty());
    }

    private Region homeHeader() {
        Button quickAccess = new Button("快速访问",
                (quickAccessExpanded ? UiGlyph.DOWN : UiGlyph.RIGHT).graphic(12, "command-symbol"));
        quickAccess.setId("home-quick-access");
        quickAccess.getStyleClass().add("home-section");
        quickAccess.setOnAction(event -> {
            quickAccessExpanded = !quickAccessExpanded;
            browser.refreshHomeHeader();
            Platform.runLater(() -> {
                Node replacement = browser.lookup("#home-quick-access");
                if (replacement != null) { replacement.requestFocus(); }
            });
        });

        VBox header = new VBox(9, quickAccess);
        header.getStyleClass().add("home-header");
        if (quickAccessExpanded) {
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
        Label location = new Label(knownFolder ? "本地存储" : item.location());
        location.getStyleClass().add("muted-label");
        HBox detail = new HBox(4, location);
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
        button.getStyleClass().add(tab == homeTab ? "active-tab" : "home-tab");
        button.setOnAction(event -> {
            if (activeTask != null) { return; }
            if (!browser.homeFilesExpanded()) { browser.toggleHomeFiles(); }
            if (tab == homeTab) { return; }
            homeTab = tab;
            browser.resetFilters();
            refresh("");
        });
        return button;
    }

    private void toggleFavorite(Path path) {
        if (!favorites.remove(path)) { favorites.add(path); }
        savePaths("favorites", favorites);
        if (location.page() == Page.HOME && homeTab == HomeTab.FAVORITES) { refresh(""); }
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
        if (activeTask != null) { return; }
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
        runTask(task(ignored -> { WindowsPlaces.mapNetworkDrive(); return null; }),
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
                runTask(task(ignored -> { WindowsPlaces.disconnectNetworkDrive(drive); return null; }),
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
                    runTask(task(ignored -> { Desktop.getDesktop().browse(uri); return null; }),
                            "正在打开媒体服务器…", false, ignored -> status.setText("已在浏览器中打开"));
                });
            }
        });
    }

    private void browseNetwork() {
        runTask(task(ignored -> { WindowsPlaces.openNetworkDiscovery(); return null; }), "正在打开 Windows 网络…",
                false, ignored -> status.setText("已打开 Windows 网络"));
    }

    private void selectionChanged() {
        updateControls();
        refreshInspector();
    }

    private void changePane(Pane next) {
        pane = pane == next ? Pane.NONE : next;
        inspector.setVisible(pane != Pane.NONE);
        refreshInspector();
    }

    private void refreshInspector() {
        if (previewTask != null) {
            previewTask.cancel();
            previewTask = null;
        }
        inspector.getChildren().clear();
        if (pane == Pane.NONE) { return; }
        Label heading = new Label(pane == Pane.DETAILS ? "详细信息" : "预览");
        heading.getStyleClass().add("section-title");
        inspector.getChildren().add(heading);
        BrowserItem item = selectedItem();
        if (browser.selection().size() > 1) {
            inspector.getChildren().add(new Label("已选择 " + browser.selection().size() + " 个项目"));
        } else if (item == null) {
            inspector.getChildren().add(new Label("选择一个项目以查看"));
        } else if (pane == Pane.DETAILS) {
            Label text = new Label(propertiesText(item));
            text.setWrapText(true);
            inspector.getChildren().addAll(icons.graphic(item, 32), text);
        } else {
            preview(item);
        }
    }

    private void preview(BrowserItem item) {
        if (item.kind() != BrowserItem.Kind.FILE) {
            inspector.getChildren().add(new Label("此项目没有文件预览"));
            return;
        }
        if (LocalFiles.isImage(item.path())) {
            Image image = new Image(item.path().toUri().toString(), 220, 340, true, true, true);
            ImageView view = new ImageView(image);
            view.setPreserveRatio(true);
            view.setFitWidth(220);
            view.setFitHeight(340);
            Runnable imageError = () -> inspector.getChildren().setAll(new Label("无法预览此图片"));
            image.errorProperty().addListener((observable, previous, failed) -> {
                if (failed && inspector.getChildren().contains(view)) { imageError.run(); }
            });
            inspector.getChildren().add(view);
            if (image.isError()) { imageError.run(); }
            return;
        }
        Label hint = new Label("正在读取预览…");
        inspector.getChildren().add(hint);
        Task<String> task = task(cancelled -> {
            try (var input = Files.newInputStream(item.path())) {
                String text = new String(input.readNBytes(8192), StandardCharsets.UTF_8);
                if (text.indexOf('\0') >= 0 || text.indexOf('\ufffd') >= 0) { return null; }
                return text;
            }
        });
        previewTask = task;
        task.setOnSucceeded(event -> {
            if (previewTask != task) { return; }
            String text = task.getValue();
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
        });
        task.setOnFailed(event -> { if (previewTask == task) { hint.setText("无法读取此文件"); } });
        disk.execute(task);
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
        Path path = location.path();
        if (path == null) {
            info("属性", location.label() + " · " + browser.items().size() + " 个项目");
        } else if (WindowsPlaces.WINDOWS) {
            showNativeProperties(path);
        } else {
            var drive = places.computer().stream().filter(item -> item.disk() != null && path.equals(item.path())).findFirst();
            if (drive.isPresent()) {
                showProperties(drive.get());
            } else {
                runTask(task(ignored -> BrowserItem.file(LocalFiles.describe(path))), "正在读取属性…", false,
                        item -> { updateListingStatus(""); showProperties(item); });
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
        properties.setOnFailed(event -> showError(properties.getException()));
        Thread.startVirtualThread(properties);
    }

    private void open(BrowserItem item) {
        if (activeTask != null || item == null) { return; }
        if (item.directory()) {
            navigate(Location.folder(item.path()));
        } else if (item.kind() == BrowserItem.Kind.SHELL) {
            runTask(task(ignored -> { WindowsPlaces.openShortcut(item.shellTarget()); return null; }),
                    "正在打开快捷方式…", false, ignored -> status.setText("已打开「" + item.name() + "」"));
        } else {
            openFile(item.path(), item.name());
        }
    }

    private void openFile(Path path, String name) {
        runTask(task(ignored -> { LocalFiles.open(path); return null; }), "正在打开文件…", false, ignored -> {
            rememberVisit("recentFiles", recentFiles, path);
            if (path.getParent() != null) {
                rememberVisit("recentFolders", folderHistory, path.getParent());
            }
            if (location.page() == Page.HOME && homeTab == HomeTab.RECENT) { refresh("已打开「" + name + "」"); }
            else { status.setText("已打开「" + name + "」"); }
        });
    }

    private boolean canCreate() {
        return activeTask == null && location.path() != null && activeQuery.isEmpty();
    }

    private void create(NewItems.Type type) {
        if (!canCreate() || places == null) { return; }
        Path directory = location.path();
        switch (type.kind()) {
            case COMMAND -> runTask(task(ignored -> {
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
        writing = true;
        runTask(task(work), "正在新建" + label + "…", false, created -> {
            undo = List.of(FileChanges.undoCreation(created.path()));
            recordFolderEdits(Stream.of(created.path().getParent()));
            rebuildSidebar();
            request(location, "", "已新建" + label, () -> Platform.runLater(() -> {
                if (browser.filtersActive() && browser.items().stream().noneMatch(item -> created.path().equals(item.path()))) {
                    browser.clearFilters();
                    updateListingStatus("已新建" + label);
                }
                browser.editName(created.path(), created.extension(), true, name -> renameNewItem(created, name));
            }));
        });
    }

    private void renameNewItem(NewItems.Created created, String name) {
        if (activeTask != null || name.isBlank()) { return; }
        runTask(task(ignored -> FileChanges.capture(created.entry())), "正在核对新建项目…", false,
                selection -> renameEntry(selection, name));
    }

    private void renameSelected() {
        BrowserItem selected = selectedItem();
        if (selected == null || selected.entry() == null || browser.selection().size() != 1 || activeTask != null) { return; }
        String extension = selected.name().substring(LocalFiles.extensionStart(selected.name(), selected.directory()));
        runTask(task(ignored -> FileChanges.capture(selected.entry())), "正在核对所选项目…", false, selection -> {
            updateListingStatus("");
            browser.editName(selected.path(), extension, false, name -> renameEntry(selection, name));
        });
    }

    private void renameEntry(Selection entry, String name) {
        if (activeTask != null || name.isBlank()) { return; }
        try {
            Path target = LocalFiles.childNamed(entry.path().getParent(), name);
            if (entry.path().toString().equals(target.toString())
                    || !confirmCurrentPaths(List.of(entry), Selection::needsPathConfirmation, "重命名", false)) {
                updateListingStatus("");
                return;
            }
            Change change = FileChanges.rename(entry, target, entry.needsPathConfirmation());
            applyChanges(Kind.RENAME, "正在重命名…", "已重命名", List.of(change), ignored -> { },
                    () -> Platform.runLater(() -> browser.reveal(target)));
        } catch (InvalidPathException exception) {
            showError(exception);
        }
    }

    private void rememberSelection(boolean move) {
        List<BrowserItem> selected = browser.selection();
        if (!canModify(selected) || activeTask != null) { return; }
        captureSelection(selected, sources -> {
            transfer = new Transfer(sources, move);
            describeTransfer();
            updateListingStatus("");
        });
    }

    private void captureSelection(List<BrowserItem> selected, Consumer<List<Selection>> ready) {
        runTask(task(ignored -> {
            List<Selection> result = new ArrayList<>();
            for (BrowserItem item : selected) {
                result.add(FileChanges.capture(item.entry()));
            }
            return List.copyOf(result);
        }), "正在核对所选项目…", false, ready);
    }

    private void describeTransfer() {
        if (transfer == null) { clipboardStatus.setText(""); return; }
        String label = transfer.sources().size() == 1 ? transfer.sources().getFirst().entry().name()
                : transfer.sources().size() + " 个项目";
        clipboardStatus.setText((transfer.move() ? "已剪切 · " : "待粘贴 · ") + label);
    }

    private void paste() {
        if (location.path() != null) { pasteInto(location.path()); }
    }

    private void pasteInto(Path directory) {
        Transfer pending = transfer;
        if (activeTask != null || pending == null) { return; }
        runTask(task(ignored -> FileChanges.destination(directory)), "正在核对目标文件夹…", false, destination -> {
            Predicate<Selection> unverified = source -> source.needsPathConfirmation(destination, pending.move());
            if (!confirmCurrentPaths(pending.sources(), unverified, pending.move() ? "移动" : "复制", false)) {
                updateListingStatus("");
                return;
            }
            List<Change> changes = pending.sources().stream()
                    .map(source -> FileChanges.transfer(source, destination, pending.move(), unverified.test(source))).toList();
            applyChanges(pending.move() ? Kind.MOVE : Kind.COPY, pending.move() ? "正在移动…" : "正在复制…",
                    pending.move() ? "已移动" : "已复制", changes, outcome -> {
                List<Selection> remaining = pending.move() ? outcome.remainingCut(pending.sources())
                        : outcome.remainingCopy(pending.sources());
                transfer = remaining.isEmpty() ? null : new Transfer(remaining, pending.move());
                describeTransfer();
            }, () -> {});
        });
    }

    private void trashSelected() {
        List<BrowserItem> selected = browser.selection();
        if (!canModify(selected) || activeTask != null) { return; }
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
        if (activeTask != null || undo.isEmpty()) { return; }
        runFileOperation(Kind.UNDO, undo, "正在撤消…", outcome -> {
            undo = outcome.pending();
            afterWrite(Kind.UNDO, "已撤消", outcome, () -> {});
        });
    }

    private void runFileOperation(Kind kind, List<Change> changes, String running, Consumer<Outcome> complete) {
        if (activeTask != null) { return; }
        var operation = operationFactory.apply(kind, changes);
        activeFileOperation = operation;
        operationProgress.start(operation);
        writing = true;
        Task<Outcome> worker = task(ignored -> operation.run());
        worker.addEventHandler(WorkerStateEvent.WORKER_STATE_FAILED, event -> {
            activeFileOperation = null;
            operationProgress.failed(worker.getException());
        });
        runTask(worker, running, false, outcome -> {
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
        boolean mayHaveWritten = outcome.count(State.APPLIED) + outcome.count(State.UNKNOWN) > 0;
        if (mayHaveWritten) { rebuildSidebar(); }
        if (mayHaveWritten || outcome.count(State.FAILED) > 0 || !outcome.issues().isEmpty()) {
            request(location, activeQuery, message, error == null ? reloaded : () -> {});
        } else {
            updateListingStatus(message);
        }
        if (outcome.count(State.UNKNOWN) > 0) {
            showError(new IOException(error.getMessage() + "\n可用状态栏的「核查结果」查看路径并重新选择。", error));
        } else if (error != null) {
            showError(error);
        }
    }

    private void inspectUnknownResults() {
        if (activeTask != null || unknownOperations.isEmpty()) { return; }
        var records = List.copyOf(unknownOperations.reversed());
        runTask(task(ignored -> {
            Map<FileChanges.UnknownOperation, List<LocalFiles.Observation>> observations = new LinkedHashMap<>();
            for (var operation : records) { observations.put(operation, LocalFiles.observe(operation.paths())); }
            return observations;
        }), "正在只读核查路径…", false, observations -> UnknownResultsDialog.show(stage, observations)
                .filter(path -> path.getParent() != null).ifPresent(path -> {
                    Location target = Location.folder(path.getParent());
                    if (!target.equals(location)) {
                        past.addFirst(location);
                        future.clear();
                    }
                    request(target, "", "请检查当前项目并重新发起操作", () -> browser.reveal(path));
                }));
    }

    private <T> void runTask(Task<T> task, String running, boolean cancellable, Consumer<T> success) {
        if (activeTask != null) { return; }
        activeTask = task;
        searching = cancellable;
        status.setText(running);
        updateControls();
        task.setOnSucceeded(event -> {
            finishTask();
            success.accept(task.getValue());
            updateControls();
        });
        task.setOnFailed(event -> {
            finishTask();
            status.setText("操作失败");
            updateControls();
            showError(task.getException());
        });
        task.setOnCancelled(event -> {
            finishTask();
            status.setText("已取消读取");
            updateControls();
        });
        disk.execute(task);
    }

    private void finishTask() {
        activeTask = null;
        searching = false;
        writing = false;
    }

    private void updateControls() {
        boolean busy = activeTask != null;
        unknownResults.setDisable(busy);
        boolean unavailable = busy || places == null;
        boolean writableFolder = !unavailable && location.path() != null;
        List<BrowserItem> selected = browser.selection();
        boolean editable = !unavailable && canModify(selected);
        tree.setDisable(unavailable);
        syncNavigationPane();
        overview.setDisable(unavailable);
        address.setDisable(unavailable);
        searchField.setDisable(unavailable);
        controls.get("back").setDisable(unavailable || past.isEmpty());
        controls.get("forward").setDisable(unavailable || future.isEmpty());
        controls.get("up").setDisable(unavailable || parentLocation() == null);
        controls.get("refresh").setDisable(unavailable);
        controls.get("search").setDisable(unavailable);
        controls.get("cancel-search").setVisible(searching);
        controls.get("clear-search").setVisible(!searching && !activeQuery.isEmpty());
        controls.get("clear-search").setDisable(unavailable);
        controls.get("new").setDisable(unavailable || !canCreate());
        controls.get("copy").setDisable(!editable);
        controls.get("cut").setDisable(!editable);
        controls.get("trash").setDisable(!editable);
        controls.get("rename").setDisable(!editable || selected.size() != 1);
        controls.get("paste").setDisable(!writableFolder || transfer == null);
        for (String id : List.of("sort", "view", "more", "gallery-sources")) { controls.get(id).setDisable(unavailable); }
        controls.get("gallery-sources").setVisible(location.page() == Page.GALLERY);
        controls.get("details-pane").setDisable(places == null);
        progress.setVisible(busy);
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

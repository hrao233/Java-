package edu.files;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.event.EventTarget;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Skin;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.ListViewSkin;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class BrowserView extends StackPane {
    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("chosen");
    private static final PseudoClass COMPACT = PseudoClass.getPseudoClass("compact");
    private static final PseudoClass GROUP = PseudoClass.getPseudoClass("group");
    private static final PseudoClass FILTERED = PseudoClass.getPseudoClass("filtered");
    private static final double GALLERY_GAP = 8;
    private static final double GALLERY_HEADING_HEIGHT = 38;
    private static final int LIST_SIDE_SPACE = 48;
    private static final Line HOME_HEADER = new Line("home-header", List.of());
    private static final Line HOME_COLUMNS = new Line("home-columns", List.of());
    private static final Line HOME_EMPTY = new Line("home-empty", List.of());
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy 年 M 月")
            .withZone(ZoneId.systemDefault());

    enum Field {
        NAME("名称", BrowserItem::name), MODIFIED("修改日期", BrowserItem::modified),
        TYPE("类型", BrowserItem::type), SIZE("大小", BrowserItem::size), LOCATION("所在文件夹", BrowserItem::location),
        VISITED("访问日期", BrowserItem::visited), AVAILABLE("可用空间", item -> disk(item, BrowserItem.Disk::available)),
        FILE_SYSTEM("文件系统", item -> disk(item, BrowserItem.Disk::fileSystem)),
        COMMENT("备注", item -> disk(item, BrowserItem.Disk::comment)),
        NETWORK("网络位置", item -> disk(item, BrowserItem.Disk::networkPath)),
        USED("已用百分比", item -> disk(item, BrowserItem.Disk::used));

        final String label;
        final Function<BrowserItem, ? extends Comparable<?>> value;

        Field(String label, Function<BrowserItem, ? extends Comparable<?>> value) {
            this.label = label;
            this.value = value;
        }

        String text(BrowserItem item, boolean extensions) {
            if (this == NAME) {
                return item.displayName(extensions);
            }
            return switch (value.apply(item)) {
                case null -> "—";
                case FileTime time -> DATE.format(time.toInstant());
                case Long bytes -> formatSize(bytes);
                case Double ratio -> String.format(Locale.ROOT, "%.0f%%", 100 * ratio);
                case String text -> text.isBlank() ? "—" : text;
                default -> "—";
            };
        }

        String group(BrowserItem item, boolean extensions) {
            if (this == NAME) {
                String name = item.displayName(extensions);
                return name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
            }
            return switch (value.apply(item)) {
                case null -> "未指定";
                case FileTime time -> MONTH.format(time.toInstant());
                case Long bytes -> sizeGroup(bytes);
                case Double ratio -> {
                    int bucket = Math.clamp((int) (ratio * 10), 0, 9) * 10;
                    yield bucket + "–" + (bucket + 10) + "%";
                }
                default -> text(item, extensions);
            };
        }

        private static <T> T disk(BrowserItem item, Function<BrowserItem.Disk, T> value) {
            return item.disk() == null ? null : value.apply(item.disk());
        }
    }

    enum Mode {
        HUGE("超大图标", 216, 136), LARGE("大图标", 160, 92), MEDIUM("中图标", 118, 60),
        SMALL("小图标", 140, 18), LIST("列表", 205, 18), DETAILS("详细信息", 0, 0),
        TILES("平铺", 248, 40), CONTENT("内容", 0, 44);

        final String label;
        final int width;
        final int icon;

        Mode(String label, int width, int icon) {
            this.label = label;
            this.width = width;
            this.icon = icon;
        }
    }

    record Settings(Mode mode, Field sort, boolean descending, Field group, boolean compact,
                    boolean checkboxes, boolean extensions, boolean computer, boolean locations,
                    boolean recent, boolean home, boolean gallery, Map<Path, Double> imageRatios) { }

    private record Line(String heading, List<BrowserItem> items) {
        BrowserItem item() { return items.isEmpty() ? null : items.getFirst(); }
    }

    private record NameEdit(Path path, String text, int selectionEnd, String suffix, Consumer<String> commit) { }
    private record ScrollPosition(VirtualFlow<?> flow, Path firstPath, int index, double position) { }

    private final TableView<Line> table = new TableView<>();
    private final ListView<Line> tiles = new ListView<>() {
        @Override
        protected Skin<?> createDefaultSkin() {
            return new ListViewSkin<>(this) {
                @Override
                protected VirtualFlow<ListCell<Line>> createVirtualFlow() {
                    return new VirtualFlow<>() {
                        {
                            configureGalleryScrollBar(getVbar());
                        }
                    };
                }
            };
        }
    };
    private final Map<Field, TableColumn<Line, Line>> columns = new LinkedHashMap<>();
    private final Map<Field, Button> filterButtons = new LinkedHashMap<>();
    private final ColumnFilters filters = new ColumnFilters();
    private final TableColumn<Line, BrowserItem> checkColumn = new TableColumn<>();
    private final Set<BrowserItem> selected = new LinkedHashSet<>();
    private final Map<String, Image> thumbnails = new LinkedHashMap<>(64, 0.75f, true);
    private final SystemIcons icons;
    private final Runnable onSelection;
    private final Consumer<BrowserItem> onOpen;
    private final BiConsumer<Field, Boolean> onSort;
    private final Function<BrowserItem, ContextMenu> onContext;
    private final Runnable onFiltersChanged;
    private final Supplier<Region> homeHeader;
    private final Runnable onEditFinished;
    private final Label empty = new Label("没有可显示的项目");
    private final Label galleryDate = new Label();
    private List<Line> lines = List.of();
    private List<BrowserItem> ordered = List.of();
    private List<BrowserItem> source = List.of();
    private Settings settings;
    private BrowserItem anchor;
    private ContextMenu context;
    private Popup filterMenu;
    private NameEdit nameEdit;
    private boolean syncing;
    private int tileColumns;
    private int galleryWidth;
    private ScrollBar tileScrollBar;
    private boolean homeFilesExpanded = true;

    BrowserView(SystemIcons icons, Runnable onSelection, Consumer<BrowserItem> onOpen, BiConsumer<Field, Boolean> onSort,
                Function<BrowserItem, ContextMenu> onContext, Runnable onFiltersChanged, Supplier<Region> homeHeader,
                Runnable onEditFinished) {
        this.icons = icons;
        this.onSelection = onSelection;
        this.onOpen = onOpen;
        this.onSort = onSort;
        this.onContext = onContext;
        this.onFiltersChanged = onFiltersChanged;
        this.homeHeader = homeHeader;
        this.onEditFinished = onEditFinished;
        setId("browser-view");
        buildTable();
        buildTiles();
        empty.getStyleClass().add("empty-state");
        empty.setMouseTransparent(true);
        galleryDate.getStyleClass().add("gallery-date-hint");
        galleryDate.setManaged(false);
        galleryDate.setMouseTransparent(true);
        galleryDate.setVisible(false);
        getChildren().addAll(table, tiles, empty, galleryDate);
    }

    List<BrowserItem> selection() { return List.copyOf(selected); }
    List<BrowserItem> items() { return ordered; }
    boolean filtersActive() { return filters.active(); }
    boolean editingName() { return nameEdit != null; }

    boolean homeFilesExpanded() { return homeFilesExpanded; }

    void toggleHomeFiles() {
        homeFilesExpanded = !homeFilesExpanded;
        updateHomeRows();
    }

    void refreshHomeHeader() {
        if (settings != null && settings.home()) { tiles.refresh(); }
    }

    void resetFilters() {
        if (filterMenu != null) { filterMenu.hide(); }
        filters.clear();
    }

    void clearFilters() {
        resetFilters();
        applyFilters();
    }

    void render(List<BrowserItem> items, Settings next, boolean retainSelection, List<BrowserItem> verifiedSelection) {
        ScrollPosition scroll = verifiedSelection == null ? null : scrollPosition();
        boolean columnsChanged = !next.equals(settings);
        boolean layoutChanged = settings == null || next.home() != settings.home() || next.gallery() != settings.gallery();
        source = items;
        settings = next;
        galleryDate.setVisible(false);
        if (!retainSelection) {
            selected.clear();
            if (verifiedSelection != null) { selected.addAll(verifiedSelection); }
            anchor = null;
            nameEdit = null;
        }
        Comparator<BrowserItem> order = comparator(next.sort());
        if (next.descending()) {
            order = order.reversed();
        }
        if (!next.computer() && !next.gallery() && !next.recent() && next.group() == null) {
            order = Comparator.comparing(BrowserItem::directory).reversed().thenComparing(order);
        }
        LocalDate today = LocalDate.now();
        List<BrowserItem> sorted = items.stream().filter(item -> filters.includes(item, today)).sorted(order).toList();
        lines = buildLines(sorted);
        ordered = lines.stream().flatMap(line -> line.items().stream()).toList();
        if (retainSelection || verifiedSelection != null) { selected.retainAll(ordered); }
        empty.setText(filters.active() ? "没有符合筛选条件的项目" : "没有可显示的项目");
        empty.setVisible(ordered.isEmpty() && !next.home());
        boolean details = next.mode() == Mode.DETAILS && !next.home() && !next.gallery();
        table.setVisible(details);
        tiles.setVisible(!details);
        tiles.getStyleClass().removeAll("home-entries", "gallery-entries");
        if (next.home()) { tiles.getStyleClass().add("home-entries"); }
        if (next.gallery()) { tiles.getStyleClass().add("gallery-entries"); }
        pseudoClassStateChanged(COMPACT, next.compact());
        syncing = true;
        try {
            if (details) {
                if (columnsChanged) { updateColumns(); }
                filterButtons.forEach((field, button) -> button.pseudoClassStateChanged(FILTERED, filters.active(field)));
                table.getItems().setAll(lines);
                syncTable();
            } else if (next.home()) {
                updateHomeRows();
            } else {
                tileColumns = 0;
                galleryWidth = 0;
                updateTiles();
            }
            if (!details && layoutChanged) { tiles.scrollTo(0); }
            if (scroll != null) { restoreScroll(scroll); }
        } finally {
            syncing = false;
        }
        onSelection.run();
    }

    private ScrollPosition scrollPosition() {
        var control = table.isVisible() ? table : tiles;
        if (!(control.lookup(".virtual-flow") instanceof VirtualFlow<?> flow)) { return null; }
        var first = flow.getFirstVisibleCell();
        int index = first == null ? -1 : first.getIndex();
        var displayed = table.isVisible() ? table.getItems() : tiles.getItems();
        BrowserItem item = index >= 0 && index < displayed.size() ? displayed.get(index).item() : null;
        return new ScrollPosition(flow, item == null ? null : item.path(), index, flow.getPosition());
    }

    private void restoreScroll(ScrollPosition scroll) {
        if (scroll.firstPath() != null) {
            var displayed = table.isVisible() ? table.getItems() : tiles.getItems();
            for (int index = 0; index < displayed.size(); index++) {
                if (displayed.get(index).items().stream().anyMatch(item -> scroll.firstPath().equals(item.path()))) {
                    if (index == scroll.index()) { break; }
                    scroll.flow().scrollToTop(index);
                    return;
                }
            }
        }
        scroll.flow().setPosition(scroll.position());
    }

    void selectAll() { replaceSelection(ordered); }
    void clearSelection() { replaceSelection(List.of()); }
    void invertSelection() { replaceSelection(ordered.stream().filter(item -> !selected.contains(item)).toList()); }

    void editName(Path path, String extension, boolean keepExtension, Consumer<String> commit) {
        if (!reveal(path)) { return; }
        String fullName = path.getFileName().toString();
        int selectionEnd = fullName.length() - extension.length();
        boolean fixedSuffix = keepExtension || !settings.extensions();
        String text = fixedSuffix ? fullName.substring(0, selectionEnd) : fullName;
        String label = fixedSuffix && settings.extensions() ? extension : "";
        nameEdit = new NameEdit(path, text, selectionEnd, label,
                name -> commit.accept(fixedSuffix ? name + extension : name));
        refreshNameCells();
    }

    void cancelNewName() { nameEdit = null; }

    boolean reveal(Path path) {
        var item = ordered.stream().filter(candidate -> path.equals(candidate.path())).findFirst().orElse(null);
        if (item == null) { return false; }
        replaceSelection(List.of(item));
        List<Line> displayed = table.isVisible() ? table.getItems() : tiles.getItems();
        for (int i = 0; i < displayed.size(); i++) {
            if (displayed.get(i).items().contains(item)) {
                if (table.isVisible()) { table.scrollTo(i); }
                else { tiles.scrollTo(i); }
                break;
            }
        }
        return true;
    }

    private boolean editingName(BrowserItem item) {
        return nameEdit != null && nameEdit.path().equals(item.path());
    }

    private HBox inlineName() {
        NameEdit edit = nameEdit;
        TextField field = new TextField(edit.text());
        field.setId("new-name-editor");
        field.setAccessibleText("项目名称");
        field.getStyleClass().add("inline-name-editor");
        field.setMinWidth(32);
        field.setPrefColumnCount(9);
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.ESCAPE) {
                finishName(edit, field.getText(), event.getCode() == KeyCode.ENTER);
                if (table.isVisible()) { table.requestFocus(); }
                else { tiles.requestFocus(); }
                event.consume();
            }
        });
        field.focusedProperty().addListener((observable, focused, nowFocused) -> {
            if (focused && !nowFocused && field.getScene() != null) { finishName(edit, field.getText(), true); }
        });
        Platform.runLater(() -> {
            if (nameEdit == edit && field.getScene() != null) {
                field.requestFocus();
                field.selectRange(0, edit.selectionEnd());
            }
        });
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox box = new HBox(3, field);
        if (!edit.suffix().isEmpty()) { box.getChildren().add(new Label(edit.suffix())); }
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void finishName(NameEdit edit, String text, boolean commit) {
        if (nameEdit != edit) { return; }
        nameEdit = null;
        refreshNameCells();
        if (commit && !text.isBlank() && !edit.text().equals(text)) { edit.commit().accept(text); }
        onEditFinished.run();
    }

    private void refreshNameCells() {
        if (table.isVisible()) { table.refresh(); }
        else { tiles.refresh(); }
    }

    private List<Line> buildLines(List<BrowserItem> items) {
        Field group = settings.group();
        if (group == null) {
            return items.stream().map(item -> new Line(null, List.of(item))).toList();
        }
        // Group ordering uses the field's typed values, including numeric capacities and dates.
        Comparator<BrowserItem> groupOrder = comparator(group);
        if (settings.descending()) {
            groupOrder = groupOrder.reversed();
        }
        Map<String, List<BrowserItem>> groups = new LinkedHashMap<>();
        items.stream().sorted(groupOrder).forEach(item ->
                groups.putIfAbsent(group.group(item, settings.extensions()), new ArrayList<>()));
        for (BrowserItem item : items) {
            groups.get(group.group(item, settings.extensions())).add(item);
        }
        List<Line> result = new ArrayList<>();
        groups.forEach((label, groupItems) -> {
            result.add(new Line(settings.gallery() ? label : label + "  ·  " + groupItems.size(), List.of()));
            groupItems.forEach(item -> result.add(new Line(null, List.of(item))));
        });
        return result;
    }

    private void buildTable() {
        table.setId("entries");
        table.managedProperty().bind(table.visibleProperty());
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Region());
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().getSelectedItems().addListener((ListChangeListener<Line>) change -> {
            if (!syncing) {
                selected.clear();
                table.getSelectionModel().getSelectedItems().stream().map(Line::item)
                        .filter(item -> item != null).forEach(selected::add);
                refreshChecks(table);
                onSelection.run();
            }
        });
        table.setRowFactory(ignored -> new TableRow<>() {
            {
                addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    if (!hitsTableCell(event.getTarget(), this)) { event.consume(); }
                });
                setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                            && hitsTableCell(event.getTarget(), this)
                            && getItem() != null && getItem().item() != null && !editingName(getItem().item())) {
                        onOpen.accept(getItem().item());
                    }
                });
                setOnContextMenuRequested(event -> {
                    boolean hitsItem = event.isKeyboardTrigger() || hitsTableCell(event.getTarget(), this);
                    BrowserItem item = hitsItem && getItem() != null ? getItem().item() : null;
                    showContext(item, this, event);
                });
            }

            @Override
            protected void updateItem(Line line, boolean empty) {
                super.updateItem(line, empty);
                pseudoClassStateChanged(GROUP, line != null && line.heading() != null);
            }
        });
        table.setOnContextMenuRequested(event -> showContext(null, table, event));
        checkColumn.setMinWidth(37);
        checkColumn.setMaxWidth(37);
        checkColumn.setSortable(false);
        checkColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().item()));
        checkColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(BrowserItem item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(item == null ? null : check(item));
            }
        });
        table.getColumns().add(checkColumn);
        for (Field field : Field.values()) {
            TableColumn<Line, Line> column = new TableColumn<>(field.label);
            column.setReorderable(false);
            column.setMinWidth(field == Field.NAME ? 140 : field == Field.LOCATION ? 125 : 96);
            column.setPrefWidth(switch (field) {
                case NAME -> 280;
                case LOCATION -> 220;
                case MODIFIED, VISITED -> 150;
                default -> 116;
            });
            if (ColumnFilters.supports(field)) {
                Button filter = columnFilterButton(field);
                filterButtons.put(field, filter);
                column.setGraphic(filter);
            }
            column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
            column.setCellFactory(ignored -> new TableCell<>() {
                @Override
                protected void updateItem(Line row, boolean empty) {
                    super.updateItem(row, empty);
                    BrowserItem item = row == null ? null : row.item();
                    if (item != null && field == Field.NAME && editingName(item)) {
                        setText(null);
                        HBox name = new HBox(8, icons.graphic(item, 16), inlineName());
                        name.setAlignment(Pos.CENTER_LEFT);
                        setGraphic(name);
                        return;
                    }
                    setText(item == null ? (field == Field.NAME && row != null ? row.heading() : null)
                            : field.text(item, settings.extensions()));
                    setGraphic(item != null && field == Field.NAME ? icons.graphic(item, 16) : null);
                    setGraphicTextGap(9);
                }
            });
            columns.put(field, column);
            table.getColumns().add(column);
        }
        table.setSortPolicy(view -> {
            if (!syncing && !view.getSortOrder().isEmpty()) {
                var column = view.getSortOrder().getFirst();
                columns.forEach((field, candidate) -> {
                    if (candidate == column) {
                        boolean descending = column.getSortType() == TableColumn.SortType.DESCENDING;
                        javafx.application.Platform.runLater(() -> onSort.accept(field, descending));
                    }
                });
            }
            return true;
        });
    }

    private static boolean hitsTableCell(EventTarget target, TableRow<?> row) {
        for (Node node = target instanceof Node picked ? picked : null; node != null && node != row; node = node.getParent()) {
            if (node instanceof TableCell<?, ?>) { return true; }
        }
        return false;
    }

    private void updateColumns() {
        checkColumn.setVisible(settings.checkboxes());
        columns.forEach((field, column) -> {
            column.setText(field == Field.SIZE && settings.computer() ? "总大小" : field.label);
            Button filter = filterButtons.get(field);
            if (filter != null) {
                filter.setAccessibleText("筛选" + column.getText());
                filter.getTooltip().setText(filter.getAccessibleText());
            }
            column.setVisible(switch (field) {
                case NAME, TYPE, SIZE -> true;
                case MODIFIED -> !settings.computer() && !settings.recent();
                case VISITED -> settings.recent();
                case LOCATION -> settings.locations() || settings.recent();
                default -> settings.computer();
            });
        });
        TableColumn<Line, Line> sorted = columns.get(settings.sort());
        if (sorted.isVisible()) {
            sorted.setSortType(settings.descending() ? TableColumn.SortType.DESCENDING : TableColumn.SortType.ASCENDING);
            if (table.getSortOrder().size() != 1 || table.getSortOrder().getFirst() != sorted) {
                table.getSortOrder().setAll(List.of(sorted));
            }
        } else {
            table.getSortOrder().clear();
        }
        table.refresh();
    }

    private Button columnFilterButton(Field field) {
        Button button = new Button();
        button.setId("filter-" + field.name().toLowerCase(Locale.ROOT));
        button.setGraphic(UiGlyph.DOWN.graphic(9, "filter-symbol"));
        button.setTooltip(new Tooltip("筛选" + field.label));
        button.getStyleClass().add("column-filter");
        button.setOnAction(event -> openFilter(field, button));
        button.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                button.requestFocus();
                event.consume();
            }
        });
        button.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                if (button.contains(event.getX(), event.getY())) { button.fire(); }
                event.consume();
            }
        });
        return button;
    }

    private void openFilter(Field field, Button anchor) {
        if (filterMenu != null) {
            boolean toggled = filterMenu.isShowing() && filterMenu.getOwnerNode() == anchor;
            filterMenu.hide();
            if (toggled) { return; }
        }
        List<ColumnFilters.Bucket> options = filters.options(field, source, LocalDate.now());
        VBox checks = new VBox(4);
        checks.getStyleClass().add("filter-options");
        Button clear = new Button("清除此列筛选");
        clear.setDisable(!filters.active(field));
        clear.setOnAction(event -> {
            filters.clear(field);
            filterMenu.hide();
            applyFilters();
        });
        for (ColumnFilters.Bucket option : options) {
            CheckBox check = new CheckBox(option.label());
            check.setSelected(filters.selected(field, option));
            check.setMaxWidth(Double.MAX_VALUE);
            check.setOnAction(event -> {
                filters.select(field, option, check.isSelected());
                clear.setDisable(!filters.active(field));
                applyFilters();
            });
            checks.getChildren().add(check);
        }
        if (options.isEmpty()) { checks.getChildren().add(new Label("没有可用的筛选项")); }
        Node body = checks;
        if (options.size() > 10) {
            ScrollPane scroll = new ScrollPane(checks);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setPrefViewportHeight(285);
            body = scroll;
        }
        VBox panel = new VBox(8, body, clear);
        panel.setId("column-filter-options");
        panel.getStyleClass().add("filter-panel");
        panel.getStylesheets().add(getClass().getResource("file-manager.css").toExternalForm());
        var bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds == null) { return; }
        filterMenu = new Popup();
        filterMenu.setAutoHide(true);
        filterMenu.setConsumeAutoHidingEvents(false);
        filterMenu.getContent().add(panel);
        filterMenu.show(anchor, bounds.getMinX(), bounds.getMaxY() + 2);
    }

    private void applyFilters() {
        render(source, settings, true, null);
        onFiltersChanged.run();
    }

    private void buildTiles() {
        tiles.setId("visual-entries");
        tiles.getStyleClass().add("browser-tiles");
        tiles.managedProperty().bind(tiles.visibleProperty());
        // Each cell lays out a row. Selection belongs to its individual file and folder entries.
        tiles.setSelectionModel(null);
        tiles.setPlaceholder(new Region());
        tiles.widthProperty().addListener((observable, oldWidth, width) -> {
            if (tiles.isVisible() && settings != null && !settings.home()) {
                updateTiles();
            }
        });
        tiles.setOnContextMenuRequested(event -> showContext(null, tiles, event));
        tiles.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected double computePrefWidth(double height) {
                // The gallery fits its rows to the viewport width assigned by VirtualFlow.
                return galleryVisible() ? 0 : super.computePrefWidth(height);
            }

            @Override
            protected double computePrefHeight(double width) {
                return galleryVisible() && getItem() != null ? galleryRowHeight(getItem()) : super.computePrefHeight(width);
            }

            @Override
            protected void updateItem(Line line, boolean empty) {
                super.updateItem(line, empty);
                setText(null);
                if (line == null || empty || !tiles.isVisible()) {
                    setGraphic(null);
                } else if (line == HOME_HEADER) {
                    Region header = homeHeader.get();
                    header.setMinWidth(0);
                    header.prefWidthProperty().bind(tiles.widthProperty().subtract(LIST_SIDE_SPACE));
                    setGraphic(header);
                } else if (line == HOME_COLUMNS) {
                    setGraphic(homeColumns(new Label("名称"),
                            new Label(settings.recent() ? "访问日期" : "修改日期"), new Label("活动"), true));
                } else if (line == HOME_EMPTY) {
                    Label message = new Label(BrowserView.this.empty.getText());
                    message.getStyleClass().add("home-empty");
                    setGraphic(message);
                } else if (line.heading() != null) {
                    Label label = new Label(line.heading());
                    label.getStyleClass().add("group-label");
                    setGraphic(label);
                } else if (settings.home()) {
                    setGraphic(homeEntry(line.item()));
                } else if (settings.gallery()) {
                    HBox row = galleryRow(line.items());
                    row.prefWidthProperty().bind(widthProperty());
                    setGraphic(row);
                } else {
                    HBox row = new HBox(settings.compact() ? 3 : 8);
                    for (BrowserItem item : line.items()) {
                        row.getChildren().add(iconEntry(item));
                    }
                    setGraphic(row);
                }
            }
        });
    }

    private void updateTiles() {
        if (settings.gallery()) {
            updateGalleryRows();
            return;
        }
        int width = settings.mode().width;
        int count = width == 0 ? 1 : Math.max(1, (int) (tiles.getWidth() - 24) / (width + 8));
        if (count == tileColumns) {
            return;
        }
        tileColumns = count;
        List<Line> rows = new ArrayList<>();
        List<BrowserItem> row = new ArrayList<>();
        for (Line line : lines) {
            if (line.heading() != null) {
                appendRow(rows, row);
                rows.add(line);
            } else {
                row.add(line.item());
                if (row.size() == count) {
                    appendRow(rows, row);
                }
            }
        }
        appendRow(rows, row);
        tiles.getItems().setAll(rows);
        tiles.refresh();
    }

    private void updateHomeRows() {
        List<Line> rows = new ArrayList<>();
        rows.add(HOME_HEADER);
        if (homeFilesExpanded) {
            rows.add(HOME_COLUMNS);
            if (lines.isEmpty()) { rows.add(HOME_EMPTY); }
            else { rows.addAll(lines); }
        }
        tiles.getItems().setAll(rows);
        tiles.refresh();
    }

    private GridPane homeColumns(Node name, Node date, Node activity, boolean heading) {
        GridPane row = new GridPane();
        row.prefWidthProperty().bind(tiles.widthProperty().subtract(LIST_SIDE_SPACE));
        row.setMinWidth(0);
        row.getStyleClass().add(heading ? "home-columns" : "home-file");
        for (int width : new int[]{57, 26, 17}) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(width);
            row.getColumnConstraints().add(column);
        }
        row.addRow(0, name, date, activity);
        return row;
    }

    private Node homeEntry(BrowserItem item) {
        Label location = new Label(item.location());
        location.getStyleClass().add("muted-label");
        Node name = editingName(item) ? inlineName() : new Label(item.displayName(settings.extensions()));
        VBox text = new VBox(2, name, location);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox entry = new HBox(10, icons.graphic(item, 28), text);
        entry.setMinWidth(0);
        entry.setAlignment(Pos.CENTER_LEFT);
        if (settings.checkboxes()) { entry.getChildren().addFirst(check(item)); }
        Field date = settings.recent() ? Field.VISITED : Field.MODIFIED;
        Label timestamp = new Label(date.text(item, true));
        timestamp.getStyleClass().add("home-date");
        GridPane row = homeColumns(entry, timestamp, new Region(), false);
        return selectableEntry(row, item);
    }

    private void updateGalleryRows() {
        double barWidth = tileScrollBar == null ? 16 : tileScrollBar.prefWidth(-1);
        int width = Math.max(80, (int) (tiles.getWidth() - barWidth));
        if (galleryWidth == width) { return; }
        galleryWidth = width;
        List<Line> rows = new ArrayList<>();
        List<BrowserItem> row = new ArrayList<>();
        double used = 0;
        for (Line line : lines) {
            if (line.heading() != null) {
                appendRow(rows, row);
                rows.add(line);
                used = 0;
                continue;
            }
            double itemWidth = galleryItemWidth(line.item());
            double joinedWidth = used + GALLERY_GAP + itemWidth;
            if (!row.isEmpty() && joinedWidth > width && width - used <= joinedWidth - width) {
                appendRow(rows, row);
                used = 0;
            }
            used += (row.isEmpty() ? 0 : GALLERY_GAP) + itemWidth;
            row.add(line.item());
            if (used >= width) {
                appendRow(rows, row);
                used = 0;
            }
        }
        appendRow(rows, row);
        tiles.getItems().setAll(rows);
        tiles.refresh();
    }

    private double galleryHeight() {
        return switch (settings.mode()) {
            case HUGE -> 220;
            case MEDIUM -> 120;
            case SMALL -> 88;
            default -> 164;
        };
    }

    private double galleryItemWidth(BrowserItem item) {
        double ratio = settings.imageRatios().getOrDefault(item.path(), 1.0);
        return Math.clamp(Math.round(galleryHeight() * ratio), 26, galleryWidth);
    }

    private HBox galleryRow(List<BrowserItem> items) {
        HBox row = new HBox(GALLERY_GAP);
        row.getStyleClass().add("gallery-row");
        row.setSnapToPixel(false);
        double total = items.stream().mapToDouble(this::galleryItemWidth).sum();
        var available = Bindings.max(0, row.widthProperty().subtract(GALLERY_GAP * (items.size() - 1)));
        for (BrowserItem item : items) {
            Region frame = galleryEntry(item);
            frame.prefWidthProperty().bind(available.multiply(galleryItemWidth(item) / total));
            row.getChildren().add(frame);
        }
        return row;
    }

    private Region galleryEntry(BrowserItem item) {
        double height = galleryHeight();
        String key = item.path().toUri() + "@gallery" + height + ":" + item.modified();
        Image thumbnail = thumbnail(key, item, 1600, height * 1.5);
        ImageView image = new ImageView(thumbnail);
        image.setFitHeight(height);
        image.visibleProperty().bind(thumbnail.progressProperty().isEqualTo(1).and(thumbnail.errorProperty().not()));
        ImageView fallback = icons.graphic(item, 24);
        fallback.visibleProperty().bind(thumbnail.errorProperty());
        StackPane frame = new StackPane(image, fallback);
        frame.setMinSize(Region.USE_PREF_SIZE, height);
        frame.setPrefHeight(height);
        frame.setMaxSize(Region.USE_PREF_SIZE, height);
        image.fitWidthProperty().bind(frame.widthProperty());
        image.viewportProperty().bind(Bindings.createObjectBinding(
                () -> cropToFit(thumbnail, Math.max(1, frame.getWidth()) / height),
                thumbnail.widthProperty(), thumbnail.heightProperty(), frame.widthProperty()));
        frame.getStyleClass().add("gallery-image");
        Region outline = new Region();
        outline.getStyleClass().add("gallery-selection");
        outline.setMouseTransparent(true);
        frame.getChildren().add(outline);
        if (settings.checkboxes()) {
            CheckBox check = check(item);
            StackPane.setAlignment(check, Pos.TOP_RIGHT);
            frame.getChildren().add(check);
        }
        if (editingName(item)) {
            HBox editor = inlineName();
            StackPane.setAlignment(editor, Pos.BOTTOM_LEFT);
            frame.getChildren().add(editor);
        }
        Tooltip.install(frame, new Tooltip(item.name()));
        return selectableEntry(frame, item);
    }

    private boolean galleryVisible() {
        return settings != null && settings.gallery() && tiles.isVisible();
    }

    private double galleryRowHeight(Line line) {
        return line.items().isEmpty() ? GALLERY_HEADING_HEIGHT : galleryHeight() + GALLERY_GAP;
    }

    private void configureGalleryScrollBar(ScrollBar bar) {
        tileScrollBar = bar;
        bar.visibleProperty().addListener((observable, previous, visible) -> {
            if (!visible) { galleryDate.setVisible(false); }
        });
        bar.addEventFilter(MouseEvent.MOUSE_ENTERED, event -> showGalleryDate(bar, event));
        bar.addEventFilter(MouseEvent.MOUSE_MOVED, event -> showGalleryDate(bar, event));
        bar.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> showGalleryDate(bar, event));
        bar.addEventFilter(MouseEvent.MOUSE_EXITED, event -> {
            if (!event.isPrimaryButtonDown()) { galleryDate.setVisible(false); }
        });
        bar.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (!bar.contains(bar.sceneToLocal(event.getSceneX(), event.getSceneY()))) { galleryDate.setVisible(false); }
        });
    }

    private void showGalleryDate(ScrollBar bar, MouseEvent event) {
        if (!galleryVisible() || tiles.getItems().isEmpty()) { return; }
        Node track = bar.lookup(".track");
        if (track == null) { return; }
        Bounds trackBounds = track.localToScene(track.getBoundsInLocal());
        if (trackBounds.getHeight() <= 0) { return; }
        double position = Math.clamp((event.getSceneY() - trackBounds.getMinY()) / trackBounds.getHeight(), 0, 1);
        double length = tiles.getItems().stream().mapToDouble(this::galleryRowHeight).sum();
        double target = position * length;
        for (Line row : tiles.getItems()) {
            target -= galleryRowHeight(row);
            if (target <= 0 && row.item() != null) {
                FileTime modified = row.item().modified();
                galleryDate.setText(modified == null ? "日期未知" : MONTH.format(modified.toInstant()));
                galleryDate.applyCss();
                double width = galleryDate.prefWidth(-1);
                double height = galleryDate.prefHeight(width);
                double x = sceneToLocal(bar.localToScene(0, 0)).getX() - width - 12;
                double y = sceneToLocal(event.getSceneX(), event.getSceneY()).getY() - height / 2;
                galleryDate.resizeRelocate(Math.max(0, x), Math.clamp(y, 0, Math.max(0, getHeight() - height)), width, height);
                galleryDate.setVisible(true);
                return;
            }
        }
        galleryDate.setVisible(false);
    }

    private static Rectangle2D cropToFit(Image image, double ratio) {
        double width = image.getWidth();
        double height = image.getHeight();
        if (width <= 0 || height <= 0) { return null; }
        double visibleWidth = Math.min(width, height * ratio);
        double visibleHeight = Math.min(height, width / ratio);
        return new Rectangle2D((width - visibleWidth) / 2, (height - visibleHeight) / 2, visibleWidth, visibleHeight);
    }

    private static void appendRow(List<Line> rows, List<BrowserItem> row) {
        if (!row.isEmpty()) {
            rows.add(new Line(null, List.copyOf(row)));
            row.clear();
        }
    }

    private Node iconEntry(BrowserItem item) {
        Mode mode = settings.mode();
        Region content;
        Node name;
        if (editingName(item)) {
            name = inlineName();
        } else {
            Label label = new Label(item.displayName(settings.extensions()));
            label.getStyleClass().add("icon-name");
            name = label;
        }
        Node graphic = iconGraphic(item, mode);
        if (mode == Mode.HUGE || mode == Mode.LARGE || mode == Mode.MEDIUM) {
            VBox stack = new VBox(7, graphic, name);
            stack.setAlignment(Pos.CENTER);
            content = stack;
        } else {
            VBox text = new VBox(3, name);
            if (mode == Mode.TILES || mode == Mode.CONTENT) {
                Label description = new Label(item.disk() == null ? item.type()
                        : Field.AVAILABLE.text(item, true) + " 可用 · 共 " + Field.SIZE.text(item, true));
                description.getStyleClass().add("muted-label");
                text.getChildren().add(description);
                if (item.disk() != null && item.disk().used() != null) {
                    var meter = new javafx.scene.control.ProgressBar(item.disk().used());
                    meter.setPrefWidth(145);
                    text.getChildren().add(meter);
                } else if (mode == Mode.CONTENT && item.modified() != null) {
                    text.getChildren().add(new Label(Field.MODIFIED.text(item, true) + "  ·  " + item.location()));
                }
            }
            HBox row = new HBox(9, graphic, text);
            row.setAlignment(Pos.CENTER_LEFT);
            content = row;
        }
        StackPane frame = new StackPane(content);
        if (settings.checkboxes()) {
            CheckBox check = check(item);
            StackPane.setAlignment(check, Pos.TOP_RIGHT);
            frame.getChildren().add(check);
        }
        frame.getStyleClass().add("icon-entry");
        double width = mode.width == 0 ? Math.max(220, tiles.getWidth() - 34) : mode.width;
        frame.setMinWidth(width);
        frame.setPrefWidth(width);
        frame.setMaxWidth(width);
        return selectableEntry(frame, item);
    }

    private Region selectableEntry(Region entry, BrowserItem item) {
        entry.getStyleClass().add("selectable-entry");
        entry.setPickOnBounds(true);
        entry.setUserData(item);
        entry.setAccessibleText(item.name());
        entry.pseudoClassStateChanged(SELECTED, selected.contains(item));
        entry.setOnContextMenuRequested(event -> showContext(item, entry, event));
        entry.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && !editingName(item)) {
                event.consume();
                tiles.requestFocus();
                if (event.getClickCount() == 2 && item.equals(anchor) && selected.contains(item)
                        && !event.isShiftDown() && !event.isShortcutDown()) {
                    onOpen.accept(item);
                } else {
                    selectItem(item, event);
                }
            }
        });
        return entry;
    }

    private Node iconGraphic(BrowserItem item, Mode mode) {
        if (item.kind() != BrowserItem.Kind.FILE || mode.icon < 40 || !LocalFiles.isImage(item.path())) {
            return icons.graphic(item, mode.icon);
        }
        String key = item.path().toUri() + "@" + mode.icon + ":" + item.modified();
        Image thumbnail = thumbnail(key, item, mode.icon * 1.5, mode.icon);
        ImageView image = new ImageView(thumbnail);
        image.setPreserveRatio(true);
        image.setFitWidth(mode.icon * 1.5);
        image.setFitHeight(mode.icon);
        ImageView icon = icons.graphic(item, mode.icon);
        image.visibleProperty().bind(thumbnail.progressProperty().isEqualTo(1).and(thumbnail.errorProperty().not()));
        icon.visibleProperty().bind(image.visibleProperty().not());
        return new StackPane(icon, image);
    }

    private Image thumbnail(String key, BrowserItem item, double width, double height) {
        Image image = thumbnails.computeIfAbsent(key,
                ignored -> new Image(item.path().toUri().toString(), width, height, true, true, true));
        if (thumbnails.size() > 192) {
            thumbnails.remove(thumbnails.keySet().iterator().next());
        }
        return image;
    }

    private CheckBox check(BrowserItem item) {
        CheckBox check = new CheckBox();
        check.getStyleClass().add("item-check");
        check.setUserData(item);
        check.setAccessibleText("选择 " + item.name());
        check.setSelected(selected.contains(item));
        check.setOnAction(event -> toggle(item));
        check.setOnMouseClicked(MouseEvent::consume);
        return check;
    }

    private void selectItem(BrowserItem item, MouseEvent event) {
        if (event.isShiftDown() && anchor != null && ordered.contains(anchor)) {
            int start = ordered.indexOf(anchor);
            int end = ordered.indexOf(item);
            replaceSelection(ordered.subList(Math.min(start, end), Math.max(start, end) + 1));
        } else {
            anchor = item;
            if (event.isShortcutDown()) {
                toggle(item);
            } else {
                replaceSelection(List.of(item));
            }
        }
    }

    private void toggle(BrowserItem item) {
        if (!selected.remove(item)) {
            selected.add(item);
        }
        syncSelection();
    }

    private void replaceSelection(List<BrowserItem> items) {
        selected.clear();
        selected.addAll(items);
        syncSelection();
    }

    private void syncSelection() {
        if (table.isVisible()) {
            syncing = true;
            try {
                syncTable();
            } finally {
                syncing = false;
            }
        } else {
            for (Node node : tiles.lookupAll(".selectable-entry")) {
                node.pseudoClassStateChanged(SELECTED, selected.contains(node.getUserData()));
            }
        }
        refreshChecks(table.isVisible() ? table : tiles);
        onSelection.run();
    }

    private void syncTable() {
        table.getSelectionModel().clearSelection();
        if (!selected.isEmpty()) {
            int[] indexes = java.util.stream.IntStream.range(0, lines.size())
                    .filter(i -> selected.contains(lines.get(i).item())).toArray();
            if (indexes.length > 0) {
                table.getSelectionModel().selectIndices(indexes[0], java.util.Arrays.copyOfRange(indexes, 1, indexes.length));
            }
        }
    }

    private void refreshChecks(Node surface) {
        for (Node check : surface.lookupAll(".item-check")) {
            ((CheckBox) check).setSelected(selected.contains(check.getUserData()));
        }
    }

    private void showContext(BrowserItem item, Node owner, ContextMenuEvent event) {
        if (item != null && !selected.contains(item)) {
            replaceSelection(List.of(item));
        }
        if (context != null) {
            context.hide();
        }
        context = onContext.apply(item);
        if (context != null && !context.getItems().isEmpty()) {
            context.show(owner, event.getScreenX(), event.getScreenY());
        }
        event.consume();
    }

    private static Comparator<BrowserItem> comparator(Field field) {
        return (left, right) -> compare(field.value.apply(left), field.value.apply(right));
    }

    @SuppressWarnings("unchecked") // Each field compares values of its own single declared type.
    private static int compare(Comparable<?> left, Comparable<?> right) {
        if (left == null || right == null) {
            return left == right ? 0 : left == null ? 1 : -1;
        }
        if (left instanceof String name) {
            return name.compareToIgnoreCase((String) right);
        }
        return ((Comparable<Object>) left).compareTo(right);
    }

    private static String sizeGroup(long bytes) {
        if (bytes == 0) { return "空"; }
        if (bytes < (1L << 20)) { return "不足 1 MB"; }
        if (bytes < (1L << 30)) { return "1 MB–1 GB"; }
        if (bytes < (100L << 30)) { return "1–99 GB"; }
        if (bytes < (500L << 30)) { return "100–499 GB"; }
        if (bytes < (1L << 40)) { return "500 GB–1 TB"; }
        return "1 TB 及以上";
    }

    static String formatSize(long bytes) {
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB", "EB"};
        double size = bytes;
        int unit = 0;
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024;
            unit++;
        }
        return unit == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.1f %s", size, units[unit]);
    }
}

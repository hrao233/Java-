package edu.files;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Only holds the columns of the currently displayed path. */
final class MillerColumns extends ScrollPane {
    private static final PseudoClass PATH_BRANCH = PseudoClass.getPseudoClass("path-branch");
    private final HBox columns = new HBox();
    private final List<FolderColumn> folders = new ArrayList<>();
    private final ReadExecutor reads;
    private final Function<Path, Task<List<BrowserItem>>> loader;
    private final Consumer<Path> navigate;
    private final Runnable focusNavigation;
    private final SystemIcons icons;
    private final Label currentMessage = new Label();
    private final VBox current = new VBox();
    private Path displayed;
    private long version;
    private boolean hybrid;
    private Task<List<BrowserItem>> pending;

    MillerColumns(ReadExecutor reads, Function<Path, Task<List<BrowserItem>>> loader,
                  Consumer<Path> navigate, Runnable focusNavigation, SystemIcons icons) {
        this.reads = reads;
        this.loader = loader;
        this.navigate = navigate;
        this.focusNavigation = focusNavigation;
        this.icons = icons;
        setId("miller-columns");
        getStyleClass().add("miller-columns");
        setContent(columns);
        setFitToHeight(true);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.AS_NEEDED);
        setVbarPolicy(ScrollBarPolicy.NEVER);
        setMinHeight(110);
        configureColumn(current);
        current.getStyleClass().add("miller-current");
        currentMessage.getStyleClass().add("miller-message");
    }

    void showPath(Path path, long listingVersion, boolean directoriesOnly, BrowserView files,
                  List<BrowserItem> entries, boolean unavailable) {
        if (path.equals(displayed) && version == listingVersion && hybrid == directoriesOnly) { return; }
        boolean follow = !path.equals(displayed) || hybrid != directoriesOnly;
        cancelPending();
        displayed = path;
        version = listingVersion;
        hybrid = directoriesOnly;
        var chain = new ArrayList<Path>();
        for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) { chain.add(parent); }
        Collections.reverse(chain);
        if (directoriesOnly) { chain.add(path); }
        int shared = 0;
        while (shared < folders.size() && shared < chain.size() && folders.get(shared).path.equals(chain.get(shared))) {
            shared++;
        }
        folders.subList(shared, folders.size()).clear();
        for (int index = shared; index < chain.size(); index++) {
            folders.add(new FolderColumn(chain.get(index)));
        }
        for (int index = 0; index < folders.size(); index++) {
            var folder = folders.get(index);
            Path branch = index + 1 < chain.size() ? chain.get(index + 1)
                    : directoriesOnly ? null : path;
            folder.followBranch |= !Objects.equals(folder.branch, branch);
            folder.branch = branch;
            folder.rows.refresh();
        }
        var nodes = new ArrayList<VBox>(folders);
        if (directoriesOnly) {
            FolderColumn last = folders.getLast();
            if (unavailable) { last.failure(); }
            else { last.display(entries); }
        } else {
            if (current.getChildren().isEmpty() || follow) {
                current.getChildren().setAll(header(path), files, currentMessage);
                VBox.setVgrow(files, Priority.ALWAYS);
            }
            currentMessage.setText("无法读取此目录");
            currentMessage.setVisible(unavailable);
            currentMessage.setManaged(unavailable);
            files.setVisible(!unavailable);
            files.setManaged(!unavailable);
            nodes.add(current);
        }
        if (!columns.getChildren().equals(nodes)) { columns.getChildren().setAll(nodes); }
        readAncestor(directoriesOnly ? folders.size() - 2 : folders.size() - 1);
        if (follow) {
            Platform.runLater(() -> {
                if (path.equals(displayed) && hybrid == directoriesOnly && getScene() != null) {
                    applyCss();
                    layout();
                    setHvalue(getHmax());
                }
            });
        }
    }

    void clear() {
        cancelPending();
        folders.clear();
        columns.getChildren().clear();
        current.getChildren().clear();
        displayed = null;
    }

    boolean isNavigationFocus(Node node) {
        for (Node parent = node; parent != null && parent != this; parent = parent.getParent()) {
            if (parent.getStyleClass().contains("miller-branches")) { return true; }
        }
        return false;
    }

    private void readAncestor(int index) {
        if (index < 0) { return; }
        FolderColumn column = folders.get(index);
        Task<List<BrowserItem>> task = loader.apply(column.path);
        pending = task;
        task.setOnSucceeded(event -> {
            if (pending != task) { return; }
            pending = null;
            column.display(task.getValue());
            readAncestor(index - 1);
        });
        task.setOnFailed(event -> {
            if (pending != task) { return; }
            pending = null;
            column.failure();
            readAncestor(index - 1);
        });
        if (!reads.detail(task)) {
            pending = null;
            for (int remaining = index; remaining >= 0; remaining--) { folders.get(remaining).failure(); }
        }
    }

    private void cancelPending() {
        if (pending == null) { return; }
        var task = pending;
        pending = null;
        reads.cancel(task);
    }

    private static Label header(Path path) {
        var title = new Label(BrowseContext.Location.folder(path).label());
        title.getStyleClass().add("miller-heading");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setTooltip(new Tooltip(path.toString()));
        return title;
    }

    private static void configureColumn(VBox column) {
        column.getStyleClass().add("miller-column");
        column.setMinWidth(190);
        column.setPrefWidth(230);
        HBox.setHgrow(column, Priority.ALWAYS);
    }

    private final class FolderColumn extends VBox {
        final Path path;
        final ListView<BrowserItem> rows = new ListView<>();
        final Label message = new Label("正在读取…");
        Path branch;
        boolean followBranch;

        FolderColumn(Path path) {
            this.path = path;
            configureColumn(this);
            setUserData(path);
            rows.getStyleClass().add("miller-branches");
            rows.setAccessibleText("目录分支 " + path);
            rows.setFixedCellSize(32);
            rows.setPlaceholder(message);
            message.getStyleClass().add("miller-message");
            VBox.setVgrow(rows, Priority.ALWAYS);
            getChildren().addAll(header(path), rows);
            rows.focusedProperty().addListener((observable, previous, focused) -> {
                if (focused) { focusNavigation.run(); }
            });
            rows.setOnKeyPressed(event -> {
                BrowserItem selected = rows.getSelectionModel().getSelectedItem();
                if ((event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.RIGHT) && selected != null) {
                    navigate.accept(selected.path());
                    event.consume();
                }
            });
            rows.setCellFactory(list -> new ListCell<>() {
                {
                    setOnMouseClicked(event -> {
                        if (getItem() != null && event.getButton() == MouseButton.PRIMARY
                                && event.getClickCount() == 1) {
                            navigate.accept(getItem().path());
                            event.consume();
                        }
                    });
                }

                @Override
                protected double computePrefWidth(double height) { return 0; }

                @Override
                protected void updateItem(BrowserItem item, boolean empty) {
                    super.updateItem(item, empty);
                    pseudoClassStateChanged(PATH_BRANCH, item != null && item.path().equals(branch));
                    setText(item == null ? null : item.name());
                    setGraphic(item == null ? null : icons.graphic(item, 16));
                    setTooltip(item == null ? null : new Tooltip(item.name()));
                }
            });
        }

        void display(List<BrowserItem> entries) {
            message.setText("没有子目录");
            var items = entries.stream().filter(BrowserItem::directory)
                    .sorted(Comparator.comparing(BrowserItem::name, String.CASE_INSENSITIVE_ORDER)).toList();
            if (!rows.getItems().equals(items)) { rows.getItems().setAll(items); }
            if (branch != null && followBranch) {
                for (int index = 0; index < items.size(); index++) {
                    if (branch.equals(items.get(index).path())) {
                        followBranch = false;
                        rows.scrollTo(index);
                        break;
                    }
                }
            }
            rows.refresh();
        }

        void failure() {
            rows.getItems().clear();
            followBranch = true;
            message.setText("无法读取 · 按 F5 重试");
        }
    }
}

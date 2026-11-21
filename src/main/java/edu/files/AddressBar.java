package edu.files;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.stage.Popup;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class AddressBar extends StackPane {
    record Choice(String label, Supplier<Node> graphic, Runnable open) { }
    record Children(List<Choice> choices, String emptyMessage) { }
    record Crumb(String label, Supplier<Node> graphic, Runnable open, Consumer<Consumer<Children>> children) { }

    private static final int ROW_HEIGHT = 30;
    private static final int MAX_SEGMENT_WIDTH = 184;

    private final HBox trail = new HBox();
    private final HBox editRow = new HBox();
    private final TextField editor = new TextField();
    private final ListView<Choice> choices = new ListView<>();
    private final Popup popup = new Popup();
    private final Consumer<String> onAddress;
    private final Supplier<List<String>> history;
    private List<Crumb> crumbs = List.of();
    private String displayAddress = "";
    private Node anchor;
    private long popupRevision;

    AddressBar(Consumer<String> onAddress, Supplier<List<String>> history) {
        this.onAddress = onAddress;
        this.history = history;
        setId("address-bar");
        getStyleClass().add("address-bar");
        setMinWidth(180);
        trail.setAlignment(Pos.CENTER_LEFT);
        trail.getStyleClass().add("address-trail");
        trail.managedProperty().bind(trail.visibleProperty());
        editRow.setAlignment(Pos.CENTER_LEFT);
        editRow.getStyleClass().add("address-editor");
        editRow.managedProperty().bind(editRow.visibleProperty());
        editRow.setVisible(false);

        editor.setId("address");
        editor.setPromptText("输入位置或文件路径");
        editor.setAccessibleText("地址栏");
        editor.setMinWidth(0);
        editor.setOnAction(event -> {
            Choice selected = popup.isShowing() ? choices.getSelectionModel().getSelectedItem() : null;
            if (selected != null) { choose(selected); }
            else { submit(editor.getText()); }
        });
        editor.addEventFilter(KeyEvent.KEY_PRESSED, this::handleEditorKey);
        editor.textProperty().addListener((observable, previous, current) -> {
            if (editRow.isVisible()) { showHistory(!current.equals(displayAddress)); }
        });
        editor.focusedProperty().addListener((observable, previous, focused) -> {
            if (!focused) {
                Platform.runLater(() -> {
                    if (!editor.isFocused() && !popup.isShowing() && editRow.isVisible()) { endEdit(); }
                });
            }
        });
        HBox.setHgrow(editor, Priority.ALWAYS);
        editRow.getChildren().addAll(editor, iconButton("关闭地址编辑", UiGlyph.CLOSE, 10, this::endEdit));
        getChildren().addAll(trail, editRow);

        choices.setId("address-choices");
        choices.getStyleClass().add("address-popup");
        choices.getStylesheets().add(getClass().getResource("file-manager.css").toExternalForm());
        choices.setFixedCellSize(ROW_HEIGHT);
        choices.setCellFactory(ignored -> new ListCell<>() {
            {
                setOnMouseClicked(event -> {
                    if (event.getButton() == MouseButton.PRIMARY && getItem() != null) { choose(getItem()); }
                });
            }

            @Override
            protected void updateItem(Choice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
                setGraphic(empty || item == null || item.graphic() == null ? null : item.graphic().get());
                setTooltip(empty || item == null ? null : new Tooltip(item.label()));
            }
        });
        choices.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && choices.getSelectionModel().getSelectedItem() != null) {
                choose(choices.getSelectionModel().getSelectedItem());
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                endEdit();
                event.consume();
            }
        });
        popup.getContent().add(choices);
        popup.setAutoHide(true);
        popup.setConsumeAutoHidingEvents(false);
        popup.setOnAutoHide(event -> {
            if (editRow.isVisible()) { endEdit(); }
        });
        disabledProperty().addListener((observable, previous, disabled) -> {
            if (disabled) { endEdit(); }
        });
        widthProperty().addListener((observable, previous, width) -> {
            if (!editRow.isVisible()) {
                if (popup.isShowing()) { closePopup(); }
                renderTrail();
            } else if (anchor == this && popup.isShowing()) {
                choices.setPrefWidth(width.doubleValue());
            }
        });
        renderTrail();
    }

    void setLocation(String displayAddress, List<Crumb> crumbs) {
        this.displayAddress = displayAddress;
        this.crumbs = crumbs;
        endEdit();
        editor.setText(displayAddress);
        renderTrail();
    }

    void beginEdit() {
        if (isDisabled()) { return; }
        closePopup();
        editor.setText(displayAddress);
        trail.setVisible(false);
        editRow.setVisible(true);
        editor.requestFocus();
        editor.selectAll();
        showHistory(false);
    }

    private void endEdit() {
        closePopup();
        editRow.setVisible(false);
        trail.setVisible(true);
    }

    private void submit(String text) {
        endEdit();
        onAddress.accept(text);
    }

    private void choose(Choice choice) {
        endEdit();
        choice.open().run();
    }

    private void handleEditorKey(KeyEvent event) {
        KeyCode key = event.getCode();
        if (key == KeyCode.ESCAPE) {
            endEdit();
        } else if (key == KeyCode.DOWN || key == KeyCode.UP) {
            if (!popup.isShowing()) { showHistory(!editor.getText().equals(displayAddress)); }
            int count = choices.getItems().size();
            if (count != 0) {
                int selected = choices.getSelectionModel().getSelectedIndex();
                int next = selected < 0 ? (key == KeyCode.DOWN ? 0 : count - 1)
                        : Math.clamp(selected + (key == KeyCode.DOWN ? 1 : -1), 0, count - 1);
                choices.getSelectionModel().select(next);
                choices.scrollTo(next);
            }
        } else {
            return;
        }
        event.consume();
    }

    private void showHistory(boolean filter) {
        String query = filter ? editor.getText().strip().toLowerCase(Locale.ROOT) : "";
        List<Choice> options = history.get().stream()
                .filter(path -> path.toLowerCase(Locale.ROOT).contains(query))
                .map(path -> new Choice(path, null, () -> onAddress.accept(path))).toList();
        if (options.isEmpty()) {
            closePopup();
        } else {
            showPopup(this, Math.max(180, getWidth()), options, "");
        }
    }

    private void renderTrail() {
        trail.getChildren().clear();
        if (!crumbs.isEmpty()) {
            int start = crumbs.size() - 1;
            double used = segmentWidth(crumbs.get(start));
            double available = getWidth() - 36;
            while (start > 0 && used + segmentWidth(crumbs.get(start - 1)) + (start > 1 ? 26 : 0) <= available) {
                used += segmentWidth(crumbs.get(--start));
            }
            if (start > 0) {
                List<Choice> ancestors = crumbs.subList(0, start).stream()
                        .map(crumb -> new Choice(crumb.label(), crumb.graphic(), crumb.open())).toList();
                Button overflow = iconButton("显示上级路径", UiGlyph.MORE, 14, null);
                overflow.setOnAction(event -> togglePopup(overflow, ancestors));
                trail.getChildren().add(overflow);
            }
            for (int i = start; i < crumbs.size(); i++) {
                Crumb crumb = crumbs.get(i);
                Button name = new Button(crumb.label());
                name.getStyleClass().add("crumb-name");
                name.setMaxWidth(MAX_SEGMENT_WIDTH);
                name.setMinWidth(0);
                name.setTooltip(new Tooltip(crumb.label()));
                if (crumb.graphic() != null) { name.setGraphic(crumb.graphic().get()); }
                name.setOnAction(event -> { closePopup(); crumb.open().run(); });
                trail.getChildren().add(name);
                if (crumb.children() != null) {
                    Button arrow = iconButton("展开 " + crumb.label(), i == crumbs.size() - 1 ? UiGlyph.DOWN : UiGlyph.RIGHT, 9, null);
                    arrow.getStyleClass().add("crumb-arrow");
                    arrow.setOnAction(event -> openChildren(arrow, crumb));
                    trail.getChildren().add(arrow);
                }
            }
        }
        Region blank = new Region();
        blank.setId("address-blank");
        HBox.setHgrow(blank, Priority.ALWAYS);
        blank.setMaxHeight(Double.MAX_VALUE);
        blank.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) { beginEdit(); }
        });
        Button edit = iconButton("编辑路径（Ctrl+L）", UiGlyph.EDIT, 13, this::beginEdit);
        edit.setId("edit-address");
        trail.getChildren().addAll(blank, edit);
    }

    private double segmentWidth(Crumb crumb) {
        Text measure = new Text(crumb.label());
        measure.setFont(editor.getFont());
        return Math.min(MAX_SEGMENT_WIDTH, measure.getLayoutBounds().getWidth() + 14 + (crumb.graphic() == null ? 0 : 20))
                + (crumb.children() == null ? 0 : 20);
    }

    private void openChildren(Button arrow, Crumb crumb) {
        if (popup.isShowing() && anchor == arrow) {
            closePopup();
            return;
        }
        double width = Math.min(350, Math.max(245, getWidth() * 0.65));
        showPopup(arrow, width, List.of(), "正在读取子文件夹…");
        long revision = popupRevision;
        crumb.children().accept(result -> {
            if (revision == popupRevision && popup.isShowing()) {
                populate(width, result.choices(), result.emptyMessage());
            }
        });
    }

    private void togglePopup(Button button, List<Choice> ancestors) {
        if (popup.isShowing() && anchor == button) { closePopup(); }
        else { showPopup(button, 278, ancestors, ""); }
    }

    private void showPopup(Node target, double width, List<Choice> options, String emptyMessage) {
        Bounds bounds = target.localToScreen(target.getBoundsInLocal());
        if (bounds == null) { return; }
        if (popup.isShowing() && anchor == target) {
            populate(width, options, emptyMessage);
            return;
        }
        closePopup();
        populate(width, options, emptyMessage);
        anchor = target;
        popup.show(target, bounds.getMinX(), bounds.getMaxY() + 3);
    }

    private void populate(double width, List<Choice> options, String emptyMessage) {
        choices.getItems().setAll(options);
        choices.getSelectionModel().clearSelection();
        choices.setPlaceholder(new Label(emptyMessage));
        choices.setPrefSize(width, 6 + ROW_HEIGHT * Math.clamp(options.size(), 1, 11));
    }

    private void closePopup() {
        popupRevision++;
        popup.hide();
        anchor = null;
    }

    private static Button iconButton(String label, UiGlyph glyph, double size, Runnable handler) {
        Button button = new Button();
        button.setGraphic(glyph.graphic(size, "crumb-icon"));
        button.setAccessibleText(label);
        button.setTooltip(new Tooltip(label));
        button.getStyleClass().add("address-icon-button");
        if (handler != null) { button.setOnAction(event -> handler.run()); }
        return button;
    }
}

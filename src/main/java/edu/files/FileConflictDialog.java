package edu.files;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static edu.files.TransferConflict.Strategy.*;

final class FileConflictDialog {
    private static final DateTimeFormatter MODIFIED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final Stage window = new Stage();

    FileConflictDialog(Window owner, FileOperationTask task, TransferConflict conflict) {
        window.initOwner(owner);
        window.setTitle("处理同名冲突");
        window.setMinWidth(490);
        window.setMinHeight(365);

        Label title = new Label("目标位置有同名项目");
        title.getStyleClass().add("operation-heading");
        Label name = new Label(conflict.target().getFileName().toString());
        name.getStyleClass().add("operation-phase");
        name.setMaxWidth(Double.MAX_VALUE);
        name.setTooltip(new Tooltip(name.getText()));
        Label hint = new Label(conflict.canKeepBoth() ? "保留两者会为此次传输使用副本名称。"
                : "当前位置无法验证安全的副本提交。可跳过此项目或取消剩余操作。");
        hint.getStyleClass().add("operation-muted");
        hint.setWrapText(true);

        var occupant = conflict.occupant() == null ? null : conflict.occupant().entry();
        VBox paths = new VBox(8, project("来源", conflict.source().path(), conflict.source().entry()),
                project("目标", conflict.target(), occupant));
        ScrollPane details = new ScrollPane(paths);
        details.setFitToWidth(true);
        details.setPrefViewportHeight(172);
        details.getStyleClass().add("operation-detail-pane");
        VBox.setVgrow(details, Priority.ALWAYS);

        CheckBox remember = new CheckBox("对后续同类冲突应用相同策略");
        remember.setId("conflict-apply");
        remember.setTooltip(new Tooltip("仅应用于当前任务中来源和目标类型各自相同的冲突。"));
        remember.setDisable(conflict.category() == null);
        Button skip = command("跳过", "conflict-skip", task, conflict, SKIP, remember);
        skip.setDefaultButton(true);
        Button keep = command("保留两者", "conflict-keep", task, conflict, KEEP_BOTH, remember);
        keep.setDisable(!conflict.canKeepBoth());
        Button cancel = command("取消剩余操作", "conflict-cancel", task, conflict, CANCEL, remember);
        cancel.setCancelButton(true);
        HBox actions = new HBox(8, skip, keep, cancel);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox form = new VBox(10, title, name, details, hint, remember, actions);
        form.setId("file-conflict");
        form.getStyleClass().add("operation-window");
        form.setPadding(new Insets(21));
        Scene scene = new Scene(form, 620, 412);
        UiTheme.inherit(scene, owner);
        window.setScene(scene);
        window.setOnCloseRequest(event -> task.decideConflict(conflict, CANCEL, false));
    }

    private Button command(String title, String id, FileOperationTask task, TransferConflict conflict,
                           TransferConflict.Strategy strategy, CheckBox remember) {
        Button button = new Button(title);
        button.setId(id);
        button.setOnAction(event -> {
            task.decideConflict(conflict, strategy, remember.isSelected());
            window.hide();
        });
        return button;
    }

    private static VBox project(String role, Path path, LocalFiles.Entry entry) {
        Label heading = new Label(role);
        heading.getStyleClass().add("operation-phase");
        Label location = new Label(path.toString());
        location.setWrapText(true);
        String metadata = entry == null ? "当前项目详情不可读取"
                : entry.type() + (entry.directory() ? "" : " · " + entry.size() + " 字节")
                        + " · 修改于 " + MODIFIED.format(entry.modified().toInstant());
        Label attributes = new Label(metadata);
        attributes.getStyleClass().add("operation-muted");
        attributes.setWrapText(true);
        VBox card = new VBox(3, heading, location, attributes);
        card.getStyleClass().add("conflict-location");
        return card;
    }

    void show() { window.show(); }

    void close() { window.hide(); }
}

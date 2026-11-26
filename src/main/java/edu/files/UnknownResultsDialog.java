package edu.files;

import javafx.collections.FXCollections;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class UnknownResultsDialog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private UnknownResultsDialog() { }

    static Optional<Path> show(Window owner, Map<FileChanges.UnknownOperation, List<LocalFiles.Observation>> records) {
        Dialog<Path> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("核查未知结果");
        var operation = new ComboBox<>(FXCollections.observableArrayList(records.keySet()));
        operation.setId("unknown-operation");
        operation.setMaxWidth(Double.MAX_VALUE);
        var paths = new ComboBox<Path>();
        paths.setId("unknown-path");
        paths.setMaxWidth(Double.MAX_VALUE);
        var details = new TextArea();
        details.setId("unknown-details");
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefSize(550, 230);
        operation.valueProperty().addListener((observable, previous, selected) -> {
            paths.setItems(FXCollections.observableArrayList(selected.paths()));
            paths.getSelectionModel().selectFirst();
            var current = records.get(selected).stream().map(LocalFiles.Observation::toString).toList();
            details.setText("记录于 " + TIME.format(selected.recordedAt()) + "\n"
                    + selected.error().getMessage() + "\n\n当前路径观测\n" + String.join("\n", current));
        });
        operation.getSelectionModel().selectFirst();
        var open = new ButtonType("打开所在文件夹并重新选择", ButtonBar.ButtonData.OK_DONE);
        var note = new Label("路径观测仅供核查。请在文件列表中确认当前对象后发起新操作。");
        dialog.getDialogPane().setContent(new VBox(10, operation, details, note, paths));
        dialog.getDialogPane().getButtonTypes().addAll(open, ButtonType.CLOSE);
        dialog.setResultConverter(button -> button == open ? paths.getValue() : null);
        return dialog.showAndWait();
    }
}

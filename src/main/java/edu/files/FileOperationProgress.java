package edu.files;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Locale;
import java.nio.file.Path;
import java.util.function.Consumer;

import static edu.files.FileOperationTask.Kind.COPY;

final class FileOperationProgress implements AutoCloseable {
    private final Stage window = new Stage();
    private final Button launcher = new Button("文件任务");
    private final Label title = label("operation-title", "operation-heading");
    private final Label phase = label("operation-stage", "operation-phase");
    private final Label current = label("operation-file", "operation-current");
    private final Label items = label("operation-items", "operation-stat");
    private final Label data = label("operation-bytes", "operation-stat");
    private final Label timing = label("operation-timing", "operation-muted");
    private final Label details = label("operation-details", "operation-details");
    private final ProgressBar progress = new ProgressBar();
    private final Button action = new Button("取消");
    private final Button reviewButton = new Button("核查结果");
    private final Button issueLocation = new Button("打开问题位置");
    private final ScrollPane detailPane = new ScrollPane(details);
    private final PauseTransition delay = new PauseTransition(Duration.millis(350));
    private final Timeline refresh = new Timeline(new KeyFrame(Duration.millis(100), event -> render()));
    private FileOperationTask task;
    private FileChanges.Outcome outcome;
    private String unexpected;

    FileOperationProgress(Window owner, Button review, Consumer<Path> locate) {
        window.initOwner(owner);
        window.setTitle("文件任务");
        window.setMinWidth(460);
        window.setMinHeight(315);

        launcher.setId("file-operations");
        launcher.setVisible(false);
        launcher.managedProperty().bind(launcher.visibleProperty());
        launcher.setOnAction(event -> show());
        progress.setId("operation-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setAccessibleText("文件操作进度");
        action.setId("operation-action");
        action.setDefaultButton(true);
        action.setOnAction(event -> {
            if (task.snapshot().result() != null || unexpected != null) {
                window.hide();
            } else {
                task.requestCancellation();
                render();
            }
        });
        reviewButton.setId("operation-review");
        reviewButton.disableProperty().bind(review.disableProperty());
        reviewButton.setOnAction(event -> review.fire());
        reviewButton.managedProperty().bind(reviewButton.visibleProperty());
        issueLocation.managedProperty().bind(issueLocation.visibleProperty());
        issueLocation.setOnAction(event -> {
            var locations = outcome.issues().stream().map(FileOperationTask.Issue::path).distinct().toList();
            if (locations.size() == 1) {
                locate.accept(locations.getFirst());
            } else {
                var choose = new ChoiceDialog<>(locations.getFirst(), locations);
                choose.initOwner(window);
                choose.setTitle("打开问题位置");
                choose.setHeaderText(null);
                choose.showAndWait().ifPresent(locate);
            }
        });

        current.setWrapText(true);
        details.setWrapText(true);
        details.setMaxWidth(Double.MAX_VALUE);
        detailPane.setFitToWidth(true);
        detailPane.setMaxHeight(150);
        detailPane.getStyleClass().add("operation-detail-pane");
        detailPane.visibleProperty().bind(details.textProperty().isNotEmpty());
        detailPane.managedProperty().bind(detailPane.visibleProperty());
        HBox operations = new HBox(9, reviewButton, issueLocation, action);
        operations.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox content = new VBox(11, title, phase, current, progress, items, data, timing, detailPane, spacer, operations);
        content.setPadding(new Insets(24));
        content.getStyleClass().add("operation-window");
        VBox.setVgrow(detailPane, Priority.SOMETIMES);
        Scene scene = new Scene(content, 560, 395);
        scene.getStylesheets().add(getClass().getResource("file-manager.css").toExternalForm());
        window.setScene(scene);

        delay.setOnFinished(event -> {
            if (task.snapshot().result() == null && unexpected == null) { show(); }
        });
        refresh.setCycleCount(Timeline.INDEFINITE);
    }

    Button launcher() { return launcher; }

    void start(FileOperationTask next) {
        task = next;
        outcome = null;
        unexpected = null;
        launcher.setVisible(true);
        launcher.setText("文件任务 · 进行中");
        render();
        refresh.playFromStart();
        if (!window.isShowing()) { delay.playFromStart(); }
    }

    void finish(FileChanges.Outcome result) {
        outcome = result;
        stopUpdates();
        render();
    }

    void failed(Throwable failure) {
        unexpected = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        stopUpdates();
        render();
    }

    void show() {
        if (task == null) { return; }
        delay.stop();
        render();
        window.show();
        window.toFront();
    }

    private void render() {
        var sample = task.snapshot();
        var result = sample.result();
        boolean finished = result != null || unexpected != null;
        boolean copying = sample.kind() == COPY;
        boolean cancellation = task.cancellationRequested();
        title.setText(operationTitle(sample.kind()));
        phase.setText(unexpected != null ? "任务异常中断" : result != null ? resultText(result) : phaseText(sample, cancellation));
        current.setText(sample.currentPath() == null ? "等待磁盘任务开始" : sample.currentPath().getFileName().toString());
        if (sample.currentPath() != null) {
            String path = sample.currentPath().toString();
            if (current.getTooltip() == null || !path.equals(current.getTooltip().getText())) {
                current.setTooltip(new Tooltip(path));
            }
        } else {
            current.setTooltip(null);
        }
        items.setText(sample.processed() + " / " + sample.itemCount() + " 项已处理");
        data.setText(copying ? "已传输 " + bytes(sample.transferredBytes()) + "  /  "
                + (sample.totalBytes() < 0 ? "总量待定" : bytes(sample.totalBytes()))
                + "     已发布 " + bytes(sample.publishedBytes()) : "");
        setShown(data, copying);
        String performance = "";
        if (copying && !finished) {
            String speed = sample.bytesPerSecond() > 0 ? bytes((long) sample.bytesPerSecond()) + "/s" : "正在计算";
            String remaining = sample.remaining() == null ? "无法估算" : time(sample.remaining().toSeconds());
            performance = "速度 " + speed + "     剩余 " + remaining + "     ";
        }
        timing.setText(performance + "已用时 " + time(task.elapsed().toSeconds()));
        progress.setProgress(progress(sample, copying));
        action.setText(finished ? "关闭" : cancellation ? "正在取消…" : "取消");
        action.setDisable(!finished && cancellation);
        if (finished) { launcher.setText("文件任务 · " + phase.getText()); }
        reviewButton.setVisible(result == FileOperationTask.Result.UNKNOWN);
        issueLocation.setVisible(outcome != null && !outcome.issues().isEmpty());
        details.setText(describeOutcome());
    }

    static String operationTitle(FileOperationTask.Kind kind) {
        return switch (kind) {
            case COPY -> "复制文件";
            case MOVE -> "移动文件";
            case RENAME -> "重命名";
            case RECYCLE -> "移到回收站";
            case UNDO -> "撤销文件操作";
            default -> "文件任务";
        };
    }

    private static String phaseText(FileOperationTask.Snapshot sample, boolean cancellation) {
        if (cancellation) {
            return switch (sample.phase()) {
                case EXECUTING, PUBLISHING -> "已请求取消 · 等待系统提交";
                case CLEANUP -> "已请求取消 · 正在清理暂存";
                default -> "正在响应取消";
            };
        }
        return switch (sample.phase()) {
            case WAITING -> "等待执行";
            case PREPARING -> "正在检查文件和准备暂存";
            case EXECUTING -> "正在提交文件操作";
            case COPYING -> "正在复制数据";
            case ATTRIBUTES -> "正在处理属性并检查暂存";
            case PUBLISHING -> "正在发布副本";
            case CLEANUP -> "正在清理暂存";
            case FINISHED -> "";
        };
    }

    private static String resultText(FileOperationTask.Result result) {
        return switch (result) {
            case COMPLETED -> "已完成";
            case PARTIAL -> "部分完成";
            case CANCELLED -> "已取消";
            case FAILED -> "执行失败";
            case UNKNOWN -> "存在未知结果";
        };
    }

    private static double progress(FileOperationTask.Snapshot sample, boolean copying) {
        if (sample.result() == FileOperationTask.Result.COMPLETED) { return 1; }
        if (copying && sample.totalBytes() < 0 && sample.result() == null) { return -1; }
        if (copying && sample.totalBytes() > 0) {
            return Math.min(1, (double) sample.transferredBytes() / sample.totalBytes());
        }
        return sample.itemCount() == 0 ? 0 : (double) sample.processed() / sample.itemCount();
    }

    private String describeOutcome() {
        if (unexpected != null) { return unexpected; }
        if (outcome == null) { return ""; }
        var lines = new ArrayList<String>();
        if (outcome.count(FileChanges.State.CANCELLED) == 0 && task.snapshot().result() == FileOperationTask.Result.CANCELLED) {
            lines.add("取消请求生效于项目开始前。");
        }
        lines.add(outcome.summary("成功"));
        if (outcome.error() != null) { lines.add(outcome.error().getMessage()); }
        for (var issue : outcome.issues()) {
            lines.add(issue.detail() + "\n" + issue.path()
                    + (issue.cause() == null ? "" : "\n" + issue.cause().getMessage()));
        }
        return String.join("\n\n", lines);
    }

    private void stopUpdates() {
        refresh.stop();
        delay.stop();
    }

    @Override
    public void close() {
        stopUpdates();
        window.hide();
    }

    private static Label label(String id, String style) {
        var label = new Label();
        label.setId(id);
        label.getStyleClass().add(style);
        return label;
    }

    private static void setShown(Label label, boolean shown) {
        label.setVisible(shown);
        label.setManaged(shown);
    }

    private static String bytes(long amount) {
        String[] units = {"B", "KiB", "MiB", "GiB", "TiB", "PiB"};
        double scaled = amount;
        int unit = 0;
        while (scaled >= 1024 && unit < units.length - 1) {
            scaled /= 1024;
            unit++;
        }
        return unit == 0 ? amount + " B" : String.format(Locale.ROOT, "%.1f %s", scaled, units[unit]);
    }

    private static String time(long seconds) {
        if (seconds < 60) { return seconds + " 秒"; }
        if (seconds < 3600) { return seconds / 60 + " 分 " + seconds % 60 + " 秒"; }
        return seconds / 3600 + " 小时 " + seconds % 3600 / 60 + " 分";
    }
}

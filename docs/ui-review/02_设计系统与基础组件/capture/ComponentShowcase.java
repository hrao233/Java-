package edu.files;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class ComponentShowcase extends Application {
    private static final String TITLE = "JavaFX 基础组件";

    @Override
    public void start(Stage stage) {
        Map<String, String> arguments = getParameters().getNamed();
        boolean dark = "dark".equals(arguments.get("theme"));
        UiTheme theme = dark ? UiTheme.DARK : UiTheme.LIGHT;
        TextField search = new TextField("路线");
        search.setAccessibleText("搜索示例");
        search.setPrefColumnCount(13);
        TextField input = new TextField();
        input.setPromptText("输入名称");
        TextField inactiveInput = new TextField("不可编辑");
        inactiveInput.setDisable(true);

        Button primary = new Button("应用");
        primary.getStyleClass().add("primary-button");
        Button secondary = new Button("取消");
        Button disabled = new Button("不可用");
        disabled.setDisable(true);
        Button refresh = new Button(null, UiGlyph.REFRESH.graphic(17));
        refresh.getStyleClass().add("icon-button");
        refresh.setAccessibleText("刷新");
        refresh.setTooltip(new Tooltip("刷新 (F5)"));
        Button searchButton = new Button("搜索", UiGlyph.SEARCH.graphic(15));

        CheckBox checked = new CheckBox("显示扩展名");
        checked.setSelected(true);
        CheckBox unchecked = new CheckBox("隐藏的项目");
        ToggleGroup views = new ToggleGroup();
        RadioButton details = new RadioButton("详细信息");
        details.setToggleGroup(views);
        details.setSelected(true);
        RadioButton icons = new RadioButton("图标");
        icons.setToggleGroup(views);
        ComboBox<String> sorting = new ComboBox<>(FXCollections.observableArrayList("名称", "修改日期", "大小"));
        sorting.getSelectionModel().selectFirst();

        CheckMenuItem extensions = new CheckMenuItem("文件扩展名");
        extensions.setSelected(true);
        ToggleGroup themes = new ToggleGroup();
        RadioMenuItem lightOption = themeItem("浅色", themes, stage, UiTheme.LIGHT);
        RadioMenuItem darkOption = themeItem("深色", themes, stage, UiTheme.DARK);
        themes.selectToggle(dark ? darkOption : lightOption);
        MenuItem unavailable = new MenuItem("无可用选区");
        unavailable.setDisable(true);
        MenuButton menu = new MenuButton("查看", UiGlyph.VIEW.graphic(15), extensions,
                new SeparatorMenuItem(), lightOption, darkOption, unavailable);
        ProgressBar progress = new ProgressBar(0.64);
        progress.setPrefWidth(165);
        ProgressIndicator loading = new ProgressIndicator();
        loading.setPrefSize(18, 18);
        loading.setMaxSize(18, 18);

        TabPane tabs = browserSample();
        VBox.setVgrow(tabs, Priority.ALWAYS);
        VBox root = new VBox(14,
                section("按钮与输入", row(primary, secondary, disabled, refresh, new Separator(javafx.geometry.Orientation.VERTICAL),
                        input, search, searchButton, inactiveInput)),
                section("菜单与选择", row(menu, sorting, checked, unchecked, details, icons)),
                section("进度与加载", row(progress, new Label("64% 已传输"), loading, new Label("正在读取目录…"))),
                tabs);
        root.setPadding(new Insets(22));
        Scene scene = new Scene(root, 1120, 718);
        theme.applyTo(scene);
        stage.setTitle(TITLE);
        stage.setMinWidth(930);
        stage.setMinHeight(600);
        stage.setScene(scene);

        if (arguments.containsKey("output")) {
            var display = Screen.getPrimary().getVisualBounds();
            Stage backdrop = new Stage(StageStyle.UNDECORATED);
            backdrop.setScene(new Scene(new StackPane(), display.getWidth(), display.getHeight(), Color.WHITE));
            backdrop.setX(display.getMinX());
            backdrop.setY(display.getMinY());
            backdrop.setAlwaysOnTop(true);
            backdrop.show();
            stage.setX(display.getMinX() + 55);
            stage.setY(display.getMinY() + 42);
            stage.setAlwaysOnTop(true);
        }
        stage.show();
        search.requestFocus();
        if (arguments.containsKey("output")) {
            Thread.ofVirtual().start(() -> capture(arguments, menu, stage.getOutputScaleX()));
        }
    }

    private static RadioMenuItem themeItem(String text, ToggleGroup group, Stage stage, UiTheme theme) {
        RadioMenuItem item = new RadioMenuItem(text);
        item.setToggleGroup(group);
        item.setOnAction(event -> theme.applyTo(stage));
        return item;
    }

    private static TabPane browserSample() {
        TreeItem<String> active = new TreeItem<>("Project Aster");
        active.getChildren().setAll(List.of(new TreeItem<>("会议纪要"), new TreeItem<>("原始数据")));
        active.setExpanded(true);
        var root = new TreeItem<>("工作空间");
        root.getChildren().setAll(List.of(active, new TreeItem<>("课程资料"), new TreeItem<>("交付资料")));
        root.setExpanded(true);
        TreeView<String> navigation = new TreeView<>(root);
        navigation.getStyleClass().add("sidebar");
        navigation.setShowRoot(false);
        navigation.getSelectionModel().select(active);

        TableView<Sample> files = new TableView<>(FXCollections.observableArrayList(
                new Sample("01_会议纪要", "文件夹", "—"), new Sample("02_原始数据", "文件夹", "—"),
                new Sample("Budget Q4.xlsx", "工作表", "4.8 KB"), new Sample("Field Notes.txt", "文本文档", "273 B"),
                new Sample("Field Report.pdf", "PDF", "3.1 MB"), new Sample("River Survey.png", "图片", "9.6 MB"),
                new Sample("路线备选.png", "图片", "8.4 MB"), new Sample("RouteSpec.java", "Java 源文件", "52 KB"),
                new Sample("summary report.py", "Python 源文件", "1.2 KB"), new Sample("tasks.csv", "CSV", "1.6 KB")));
        var name = column("名称", Sample::name, 345);
        name.getStyleClass().add("name-cell");
        files.getColumns().setAll(List.of(name, column("类型", Sample::type, 140), column("大小", Sample::size, 115)));
        files.getSelectionModel().select(3);
        SplitPane split = new SplitPane(navigation, files);
        split.setDividerPositions(0.179);
        VBox empty = new VBox(new Label("没有可显示的项目"));
        empty.setAlignment(Pos.CENTER);
        empty.getStyleClass().add("browser-view");
        empty.getChildren().getFirst().getStyleClass().add("empty-state");
        TabPane tabs = new TabPane(new Tab("Project Aster", split), new Tab("空目录", empty));
        tabs.getStyleClass().add("browse-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return tabs;
    }

    private record Sample(String name, String type, String size) { }

    private static TableColumn<Sample, String> column(String title, Function<Sample, String> value, double width) {
        TableColumn<Sample, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private static HBox row(javafx.scene.Node... children) {
        HBox row = new HBox(9, children);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static VBox section(String title, HBox row) {
        Label heading = new Label(title);
        heading.getStyleClass().add("section-title");
        return new VBox(6, heading, row);
    }

    private static void capture(Map<String, String> args, MenuButton menu, double scale) {
        try {
            Thread.sleep(450);
            screenshot(args, "components.png", scale);
            Platform.runLater(menu::show);
            Thread.sleep(450);
            screenshot(args, "components-menu.png", scale);
        } catch (Exception failure) {
            failure.printStackTrace();
            System.exit(1);
        } finally {
            Platform.exit();
        }
    }

    private static void screenshot(Map<String, String> args, String file, double scale) throws IOException, InterruptedException {
        String destination = Path.of(args.get("output"), file).toString();
        var process = new ProcessBuilder(args.get("python"), args.get("helper"), "capture", destination,
                "--pid", Long.toString(ProcessHandle.current().pid()), "--title", TITLE).redirectErrorStream(true).start();
        String data = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0) { throw new IOException(data); }
        System.out.println("PNG " + file + "  scale " + scale + "  " + data);
    }

    public static void main(String[] args) { launch(args); }
}

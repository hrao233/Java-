package edu.files;

import com.sun.javafx.scene.input.DragboardHelper;
import com.sun.javafx.tk.TKClipboard;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.PopupWindow;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Pair;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;

import static edu.files.BrowseContext.Location;
import static edu.files.BrowseContext.Page;

public final class VisualAudit extends Application {
    @FunctionalInterface
    private interface Step { void run() throws Exception; }

    private final AtomicReference<CountDownLatch> nextCopy = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> heldCopy = new AtomicReference<>();
    private final AtomicReference<String> nextFault = new AtomicReference<>();
    private final AtomicReference<FileDrag.Intent> dragMode = new AtomicReference<>(FileDrag.Intent.COPY);
    private FileManagerApp app;
    private Scene scene;
    private Stage stage;
    private Path fixture;
    private Path workspace;
    private Path project;
    private Path delivery;
    private Path output;
    private Path python;
    private Path helper;
    private Set<String> wanted = Set.of();
    private int captured;

    @Override public void start(Stage window) throws Exception {
        List<String> args = getParameters().getRaw();
        fixture = Path.of(args.get(0)).toAbsolutePath().normalize();
        workspace = fixture.resolve("Workspace");
        project = workspace.resolve("Project Aster");
        delivery = workspace.resolve("交付资料");
        output = Path.of(args.get(1));
        python = Path.of(args.get(2));
        helper = Path.of(args.get(3));
        if (args.size() > 4) { wanted = Set.of(args.get(4).split(",")); }
        stage = window;
        var available = Screen.getPrimary().getVisualBounds();
        var backdrop = new Stage(StageStyle.UNDECORATED);
        backdrop.setTitle("Audit backdrop");
        backdrop.setScene(new Scene(new StackPane(), available.getWidth(), available.getHeight(), Color.WHITE));
        backdrop.setX(available.getMinX());
        backdrop.setY(available.getMinY());
        backdrop.setAlwaysOnTop(true);
        backdrop.show();
        stage.setAlwaysOnTop(true);
        var preferences = new AuditPreferences(null, "");
        app = new FileManagerApp(preferences, this::operation, (path, preview) -> {},
                new MemoryClipboard(false), ignored -> dragMode.get());
        // Defer the host catalog while creating the real window and supply isolated catalog data.
        put(app, "closing", true);
        app.start(stage);
        put(app, "closing", false);
        scene = stage.getScene();
        installCatalog();
        size(1280, 800);
        stage.setAlwaysOnTop(true);
        stage.toFront();
        stage.requestFocus();
        Thread.ofVirtual().start(() -> {
            try {
                captureAll();
                System.out.println("CAPTURED " + captured);
            } catch (Throwable error) {
                error.printStackTrace();
            } finally {
                releaseCopy();
                Platform.exit();
            }
        });
    }

    @Override public void stop() { if (app != null) { app.stop(); } }

    private void installCatalog() throws Exception {
        var fixed = new ArrayList<BrowserItem>();
        String[] directories = {"Desktop", "Downloads", "Documents", "Pictures", "Music", "Videos"};
        String[] titles = {"桌面", "下载", "文档", "图片", "音乐", "视频"};
        for (int i = 0; i < directories.length; i++) {
            fixed.add(BrowserItem.folder(fixture.resolve("Profile").resolve(directories[i]), titles[i]));
        }
        long gigabyte = 1024L * 1024 * 1024;
        var drive = new BrowserItem.Disk("本地磁盘", 512 * gigabyte, 354 * gigabyte, "NTFS", "演示卷", "");
        var removable = new BrowserItem.Disk("可移动磁盘", 64 * gigabyte, 49 * gigabyte, "exFAT", "演示卷", "");
        var drives = List.of(
                BrowserItem.drive(fixture.resolve("Volume"), "项目工作卷 (D:)", drive),
                BrowserItem.drive(fixture.resolve("Profile/Downloads"), "资料归档卷 (E:)", removable),
                BrowserItem.folder(project, "Project Aster"));
        FileTime date = FileTime.from(Instant.parse("2026-11-24T01:30:00Z"));
        var recent = List.of("项目计划.docx", "Field Report.pdf", "Budget Q4.xlsx", "Field Notes.txt",
                "路线概览.png", "RouteSpec.java").stream().map(name -> new WindowsPlaces.Recent(project.resolve(name), date)).toList();
        var visibility = new LocalFiles.Visibility(false, false);
        put(app, "places", new WindowsPlaces.Places(fixed, List.of(), drives, List.of(), recent, visibility, true));
        put(app, "visibility", visibility);
        put(app, "newTypes", NewItems.catalog(List.of(
                "EMPTY\t.txt\t文本文档\t新建文本文档\t",
                "TEMPLATE\t.docx\tWord 文档\t新建 Word 文档\t",
                "TEMPLATE\t.xlsx\tExcel 工作簿\t新建 Excel 工作簿\t"), true));
        put(app, "modifiedFolders", List.of(project, delivery, project.resolve("01_会议纪要")));
        // Suppress the Windows Search index callback, which could contain paths outside the fixture.
        put(app, "modifiedFoldersTask", new javafx.concurrent.Task<List<Path>>() {
            @Override protected List<Path> call() { return List.of(); }
        });
        @SuppressWarnings("unchecked")
        var pins = (Set<Path>) get(app, "extraPins");
        pins.add(project);
        pins.add(delivery);
        @SuppressWarnings("unchecked")
        var gallery = (Set<Path>) get(app, "galleryFolders");
        gallery.add(project);
        call(app, "rebuildSidebar");
        call(pane(false), "restoreVisible");
        call(app, "updateControls");
    }

    private void captureAll() throws Exception {
        waitFor("homepage", () -> settled(false));
        if ("dark".equals(System.getProperty("edu.files.audit.theme"))) { menu("view", "theme-dark"); }
        if (category("A")) { navigation(); }
        if (category("B")) { views(); }
        if (category("C")) { tabsAndPanes(); }
        if (category("D")) { commands(); }
        if (category("E")) { tasks(); }
        if (category("F")) { windows(); }
        if (category("P") && !wanted.isEmpty()) { professionalLayouts(); }
        if (category("Q") && !wanted.isEmpty()) { professionalPanes(); }
    }

    private void professionalPanes() throws Exception {
        Files.createDirectory(delivery.resolve("发布材料"));
        Files.createDirectory(delivery.resolve("归档"));
        Files.copy(project.resolve("Field Report.pdf"), delivery.resolve("调研报告.pdf"));
        go(false, project);
        button("dual-pane");
        waitFor("right panel", () -> settled(true));
        go(true, delivery);
        focus(true);
        menu("browse-layout", "layout-columns");
        waitForBranches(delivery.getParent());
        waitForIcons();
        shot("Q01", "传统与米勒列");

        focus(false);
        menu("browse-layout", "layout-columns");
        waitForBranches(project.getParent());
        focus(true);
        menu("browse-layout", "layout-hybrid");
        waitForBranches(delivery);
        waitForIcons();
        shot("Q02", "米勒列与混合");
        shot("Q03", "深色专业双栏");

        if (selected("Q04")) {
            focus(false);
            stageAction(() -> size(stage.getMinWidth(), stage.getMinHeight()));
            shot("Q04", "窄窗口专业双栏");
        }
    }

    private void professionalLayouts() throws Exception {
        Path data = project.resolve("02_原始数据");
        Path experiment = Files.createDirectory(data.resolve("实验一"));
        Files.createDirectory(data.resolve("实验二"));
        Files.createDirectory(experiment.resolve("参考资料"));
        Path samples = Files.createDirectory(experiment.resolve("路线测量"));
        Files.createDirectory(samples.resolve("图像样本"));
        Files.createDirectory(samples.resolve("历史记录"));
        Files.copy(project.resolve("Field Notes.txt"), samples.resolve("现场笔记.txt"));
        Files.copy(project.resolve("Field Report.pdf"), samples.resolve("路线调研报告.pdf"));
        Files.copy(project.resolve("Budget Q4.xlsx"), samples.resolve("测量记录.xlsx"));
        go(false, samples);
        menu("browse-layout", "layout-columns");
        waitForBranches(experiment);
        select(false, "路线调研报告.pdf");
        waitForIcons();
        shot("P01", "列式浏览");
        menu("browse-layout", "layout-hybrid");
        waitForBranches(samples);
        waitForIcons();
        shot("P02", "混合浏览");
        if (selected("P03")) {
            Path deep;
            try (var paths = Files.walk(workspace.resolve("编辑与评审工作目录"))) {
                deep = paths.filter(Files::isDirectory).max(Comparator.comparingInt(Path::getNameCount)).orElseThrow();
            }
            go(false, deep);
            menu("browse-layout", "layout-columns");
            waitForBranches(deep.getParent());
            shot("P03", "深层目录横向滚动");
        }
    }

    private void waitForBranches(Path folder) throws Exception {
        waitFor("专业布局目录分支", () -> scene.getRoot().lookupAll(".miller-column").stream()
                .anyMatch(node -> folder.equals(node.getUserData())
                        && node.lookup(".miller-branches") instanceof ListView<?> list && !list.getItems().isEmpty()));
    }

    private void navigation() throws Exception {
        go(false, "主文件夹");
        waitForIcons();
        shot("A01", "首页");
        go(false, "此电脑");
        waitForIcons();
        shot("A02", "此电脑");
        go(false, project);
        shot("A03", "普通目录详细信息");
        stageAction(() -> {
            address(false).beginEdit();
            var editor = (TextField) get(address(false), "editor");
            editor.setText(project.resolve("临时输入").toString());
            editor.positionCaret(editor.getLength());
            ((PopupWindow) get(address(false), "popup")).hide();
        });
        shot("A04", "编辑地址");
        stageAction(() -> {
            var editor = (TextField) get(address(false), "editor");
            editor.setText(project.toString());
            call(address(false), "showHistory", boolean.class, false);
        });
        awaitShot("A05", "地址栏历史建议", () -> ((PopupWindow) get(address(false), "popup")).isShowing());
        stageAction(() -> call(address(false), "endEdit"));
        go(false, workspace);
        stageAction(() -> {
            var tree = (TreeView<?>) get(app, "tree");
            var children = tree.getRoot().getChildren();
            for (TreeItem<?> branch : children) {
                var node = branch.getValue();
                if (call(node, "label").equals("Project Aster")) { branch.setExpanded(true); break; }
            }
        });
        shot("A06", "侧边栏展开");
        menu("view", "show-navigation");
        shot("A07", "侧边栏收起");
        menu("view", "show-navigation");
        go(false, project);
        search(false, "路线");
        shot("A08", "目录搜索结果");
        go(false, workspace.resolve("Empty folder"));
        shot("A09", "空目录");
        go(false, project);
        search(false, "zz-no-matching-file");
        shot("A10", "无搜索结果");
        go(false, workspace);
        go(false, project);
    }

    private void views() throws Exception {
        go(false, project);
        for (String[] view : List.of(
                new String[]{"B01", "details", "详细信息"}, new String[]{"B02", "list", "列表"},
                new String[]{"B03", "small", "小图标"}, new String[]{"B04", "medium", "中图标"},
                new String[]{"B05", "large", "大图标"}, new String[]{"B06a", "huge", "超大图标"},
                new String[]{"B06b", "tiles", "平铺"}, new String[]{"B06c", "content", "内容"})) {
            mode(view[1]);
            shot(view[0], view[2]);
        }
        go(false, "图库");
        waitFor("gallery images", () -> browser(false).items().stream().filter(item -> !item.directory()).count() == 3);
        shot("B06d", "图库");
        go(false, project);
        mode("details");
        menu("view", "show-compact");
        shot("B07", "紧凑视图");
        menu("view", "show-compact");
        menu("sort", "sort-modified");
        menu("sort", "group-type");
        shot("B08", "按修改时间排序和类型分组");
        stageAction(() -> call(pane(false), "group", BrowserView.Field.class, null));
        menu("sort", "sort-name");
        stageAction(() -> ((Button) scene.lookup("#filter-type")).fire());
        awaitShot("B09", "类型列筛选展开", () -> shown("#column-filter-options") != null);
        hidePopups();
        select(false, "Field Notes.txt");
        shot("B10a", "单选文本");
        select(false, "Budget Q4.xlsx", "Field Notes.txt", "Field Report.pdf");
        shot("B10b", "多选文件");
        menu("view", "show-preview");
        select(false, "路线概览.png");
        waitFor("image preview", () -> context(false).previewLoaded
                && inspector(false).getChildrenUnmodifiable().stream().anyMatch(ImageView.class::isInstance));
        shot("B11", "图片预览");
        select(false, "Field Notes.txt");
        awaitShot("B12", "文本预览", () -> context(false).previewLoaded && inspector(false).lookup(".text-area") != null);
        button("details-pane");
        shot("B13", "文件详细信息面板");
        button("details-pane");
        select(false, "项目调研阶段成果交接清单与版本变更记录_供项目组评审和归档_最终校对版.docx");
        shot("B14", "长文件名和混合文件类型");
        stageAction(() -> browser(false).clearSelection());
    }

    private void tabsAndPanes() throws Exception {
        go(false, project);
        openTab(delivery);
        openTab(workspace.resolve("Batch index"));
        openTab(project.resolve("01_会议纪要"));
        shot("C01", "多标签");
        var directories = List.of(project, delivery, workspace.resolve("Empty folder"), workspace.resolve("Batch index"),
                project.resolve("01_会议纪要"), project.resolve("02_原始数据"), project.resolve("03_素材参考"),
                project.resolve("04_备份"));
        for (int i = 0; i < 13; i++) { openTab(directories.get(i % directories.size())); }
        waitFor("tab overflow", () -> {
            Node overflow = scene.lookup(".tab-down-button");
            return overflow != null && overflow.isVisible() && overflow.localToScreen(overflow.getBoundsInLocal()) != null;
        });
        attempt("C02", () -> {
            stageAction(() -> {
                Node overflow = scene.lookup(".tab-down-button");
                mouse(overflow, MouseEvent.MOUSE_PRESSED);
                mouse(overflow, MouseEvent.MOUSE_RELEASED);
                if (Window.getWindows().stream().noneMatch(PopupWindow.class::isInstance)) {
                    mouse(overflow, MouseEvent.MOUSE_CLICKED);
                }
            });
            awaitShot("C02", "标签页溢出菜单", () -> Window.getWindows().stream()
                    .anyMatch(window -> window instanceof PopupWindow && window.isShowing()));
            hidePopups();
        });
        stageAction(() -> {
            TabPane tabs = (TabPane) scene.lookup("#browse-tabs");
            Tab active = tabs.getSelectionModel().getSelectedItem();
            for (Tab tab : List.copyOf(tabs.getTabs()).subList(4, tabs.getTabs().size())) {
                if (tab != active) { call(app, "closeTab", BrowseContext.class, tab.getUserData()); }
            }
        });
        waitFor("directory tab", () -> settled(false));
        stageAction(() -> {
            var tabs = (TabPane) scene.lookup("#browse-tabs");
            Tab active = tabs.getSelectionModel().getSelectedItem();
            Node header = scene.lookup("#" + active.getId());
            if (header == null) { header = tabs; }
            contextMenuEvent(header);
        });
        attempt("C03", () -> {
            awaitShot("C03", "标签页右键菜单", () -> Window.getWindows().stream()
                    .anyMatch(window -> window instanceof ContextMenu && window.isShowing()));
            hidePopups();
        });
        go(false, project);
        button("dual-pane");
        waitFor("initial right", () -> settled(true));
        go(true, delivery);
        focus(false);
        shot("C04", "双栏普通目录");
        focus(true);
        mode("medium");
        shot("C05", "双栏不同视图");
        focus(false);
        search(false, "路线");
        menu("view", "show-preview");
        select(false, "路线概览.png");
        awaitShot("C06", "双栏搜索和预览", () -> context(false).previewLoaded);
        menu("view", "show-preview");
        go(false, workspace);
        go(false, project);
        focus(false);
        shot("C07", "左栏活跃");
        focus(true);
        shot("C08", "右栏活跃");
        stageAction(() -> ((SplitPane) scene.lookup("#browser-split")).setDividerPositions(.64));
        shot("C09", "调整双栏分隔条");
        button("dual-pane");
    }

    private void commands() throws Exception {
        go(false, project);
        mode("details");
        attempt("D01", () -> {
            rowMenu("Field Notes.txt");
            awaitShot("D01", "文件右键菜单", this::contextMenuShown);
            hidePopups();
        });
        attempt("D02", () -> {
            rowMenu("01_会议纪要");
            awaitShot("D02", "文件夹右键菜单", this::contextMenuShown);
            hidePopups();
        });
        go(false, workspace.resolve("Empty folder"));
        stageAction(() -> contextMenuEvent((TableView<?>) get(browser(false), "table")));
        awaitShot("D03", "空白区域右键菜单", this::contextMenuShown);
        hidePopups();
        go(false, project);
        for (String[] state : List.of(
                new String[]{"D04", "new", "新建菜单"}, new String[]{"D05", "sort", "排序菜单"},
                new String[]{"D06", "view", "查看菜单"}, new String[]{"D07", "more", "更多操作菜单"})) {
            stageAction(() -> ((MenuButton) scene.lookup("#" + state[1])).show());
            awaitShot(state[0], state[2], this::contextMenuShown);
            hidePopups();
        }
        stageAction(() -> {
            var menu = (MenuButton) scene.lookup("#more");
            menu.show();
            MenuItem options = findItem(menu.getItems(), "options");
            menu.hide();
            require(options != null, "options menu item");
            Platform.runLater(options::fire);
        });
        awaitShot("D08", "文件夹选项", () -> shown(".dialog-pane") != null);
        dismissDialog(ButtonType.CANCEL);
        select(false, "Field Notes.txt");
        button("rename");
        awaitShot("D09", "行内改名", () -> scene.lookup("#new-name-editor") != null);
        stageAction(() -> browser(false).cancelNewName());
        select(false, "Field Notes.txt");
        button("cut");
        waitFor("cut mark", () -> {
            Set<?> cut = (Set<?>) get(browser(false), "cutItems");
            return !cut.isEmpty();
        });
        stageAction(() -> browser(false).clearSelection());
        shot("D10", "剪切文件淡显");
        select(false, "Field Notes.txt");
        stageAction(() -> {
            Button copy = (Button) scene.lookup("#copy");
            Bounds screen = copy.localToScreen(copy.getBoundsInLocal());
            copy.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true);
            copy.getTooltip().show(copy, screen.getMinX(), screen.getMaxY() + 5);
        });
        awaitShot("D11", "按钮悬停提示", () -> ((Button) scene.lookup("#copy")).getTooltip().isShowing());
        stageAction(() -> {
            Button copy = (Button) scene.lookup("#copy");
            copy.getTooltip().hide();
            copy.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), false);
        });
        attempt("D12-D13", this::dragScreens);
    }

    private void dragScreens() throws Exception {
        go(false, project);
        button("dual-pane");
        waitFor("right panel", () -> settled(true));
        go(true, delivery);
        focus(false);
        select(false, "Field Notes.txt");
        waitFor("drag proof", () -> !context(false).selectionProof.isEmpty());
        Drag drag = fx(() -> {
            List<BrowserItem> picked = browser(false).selection();
            Node surface = browser(false);
            var source = FileDrag.Source.create(surface, picked, context(false).selectionProof);
            put(app, "dragSource", source);
            put(app, "dragProofs", List.copyOf(source.proofs().values()));
            Dragboard board = DragboardHelper.createDragboard(new LocalDragboard(FileManagerApp.dragContent(source)));
            return new Drag(board, surface);
        });
        try {
            awaitShot("D12", "文件夹拖放高亮", () -> hover(drag, entry(false, "01_会议纪要")));
            awaitShot("D13a", "拖放复制反馈", () -> hover(drag, browser(true)));
            dragMode.set(FileDrag.Intent.MOVE);
            awaitShot("D13b", "拖放移动反馈", () -> hover(drag, browser(true)));
            dragMode.set(FileDrag.Intent.COPY);
            awaitShot("D13c", "禁止拖放反馈", () -> {
                hover(drag, entry(false, "Field Report.pdf"));
                return ((Label) scene.lookup("#drag-status")).getText().startsWith("请拖到");
            });
        } finally {
            stageAction(() -> call(app, "finishFileDrag"));
            button("dual-pane");
        }
    }

    private void tasks() throws Exception {
        Path from = fixture.resolve("Transfer/Source");
        Path transfer = from.getParent();
        String first = "01_交付说明.txt";
        String second = "02_路线观察.txt";
        String large = "03_路线原始样本.dat";

        taskShots("E01,E04", () -> {
            holdNextCopy();
            copyTo(from, transfer.resolve("Single"), large);
            waitFor("copy bytes", () -> heldCopy.get() != null);
            showProgress();
            shot("E01", "单文件复制进度");
            releaseCopy();
            waitResult(FileOperationTask.Result.COMPLETED);
            require(Files.mismatch(from.resolve(large), transfer.resolve("Single").resolve(large)) == -1, "single file copy");
            shot("E04", "文件任务完成");
            closeProgress();
        });
        taskShots("E02", () -> {
            holdNextCopy();
            copyTo(from, transfer.resolve("Batch"), first, second, large);
            waitFor("batch bytes", () -> heldCopy.get() != null && Files.exists(transfer.resolve("Batch").resolve(second)));
            showProgress();
            shot("E02", "三文件批量复制");
            releaseCopy();
            waitResult(FileOperationTask.Result.COMPLETED);
            closeProgress();
        });
        taskShots("E03,E03b", () -> {
            copyTo(from, transfer.resolve("Conflict"), first, second);
            awaitShot("E03", "同名冲突对话框", () -> shown("#file-conflict") != null);
            waitFor("conflict decision", () -> shown("#conflict-skip") != null);
            stageAction(() -> ((Button) shown("#conflict-skip")).fire());
            waitResult(FileOperationTask.Result.COMPLETED);
            require(Files.exists(transfer.resolve("Conflict").resolve(first)), "confirmed batch prefix");
            require(Files.readString(transfer.resolve("Conflict").resolve(second)).startsWith("同名目标"), "existing target retained");
            showProgress();
            shot("E03b", "同名跳过后的报告");
            closeProgress();
        });
        taskShots("E05", () -> {
            nextFault.set("partial");
            copyTo(from, transfer.resolve("Partial"), first, second);
            waitFor("partial failure alert", () -> shown(".dialog-pane") != null);
            dismissDialog(ButtonType.OK);
            waitResult(FileOperationTask.Result.PARTIAL);
            require(Files.mismatch(from.resolve(first), transfer.resolve("Partial").resolve(first)) == -1,
                    "the successful item in the batch");
            require(!Files.exists(transfer.resolve("Partial").resolve(second)), "the second item failed before writing");
            showProgress();
            shot("E05", "文件任务部分完成");
            closeProgress();
        });
        taskShots("E06", () -> {
            holdNextCopy();
            copyTo(from, transfer.resolve("Cancel"), large);
            waitFor("copy can be cancelled", () -> heldCopy.get() != null);
            showProgress();
            stageAction(() -> ((Button) shown("#operation-action")).fire());
            releaseCopy();
            waitResult(FileOperationTask.Result.CANCELLED);
            require(!Files.exists(transfer.resolve("Cancel").resolve(large)), "no published cancelled copy");
            shot("E06", "文件任务取消");
            closeProgress();
        });
        taskShots("E07", () -> {
            go(false, from);
            select(false, first);
            button("trash");
            awaitShot("E07", "回收确认", () -> shown(".dialog-pane") != null);
            dismissDialog(ButtonType.CANCEL);
        });
        taskShots("E08", () -> {
            nextFault.set("denied");
            copyTo(from, transfer.resolve("Failed"), first);
            awaitShot("E08", "文件操作失败提示", () -> shown(".dialog-pane") != null);
            dismissDialog(ButtonType.OK);
            closeProgress();
        });
        taskShots("E09", () -> {
            nextFault.set("receipt");
            copyTo(from, transfer.resolve("Unknown"), first);
            waitFor("unknown result alert", () -> shown(".dialog-pane") != null);
            dismissDialog(ButtonType.OK);
            closeProgress();
            Platform.runLater(() -> ((Button) scene.lookup("#unknown-results")).fire());
            awaitShot("E09", "未知结果核查", () -> shown("#unknown-details") != null);
            dismissDialog(ButtonType.CLOSE);
            stageAction(() -> {
                ((List<?>) get(app, "unknownOperations")).clear();
                ((Button) scene.lookup("#unknown-results")).setVisible(false);
            });
        });
        taskShots("E10", () -> {
            enter(false, workspace.resolve("Missing folder").toString());
            awaitShot("E10", "目录不存在提示", () -> shown(".dialog-pane") != null);
            dismissDialog(ButtonType.OK);
        });
    }

    private void windows() throws Exception {
        go(false, project);
        mode("details");
        stageAction(() -> stage.setMaximized(true));
        shot("F01", "单栏最大化");
        button("dual-pane");
        waitFor("right pane", () -> settled(true));
        go(true, delivery);
        shot("F02", "双栏最大化");
        button("dual-pane");
        stageAction(() -> {
            stage.setMaximized(false);
            size(1280, 800);
        });
        if (System.getProperty("glass.win.uiScale") == null) {
            shot("F03", "1280乘800逻辑普通窗口");
        } else {
            shot("F03b", "约1280乘800物理像素窗口");
        }
        stageAction(() -> size(stage.getMinWidth(), stage.getMinHeight()));
        shot("F04", "最小窗口");
        button("dual-pane");
        waitFor("right pane", () -> settled(true));
        go(true, delivery);
        shot("F05", "窄窗口双栏");
        button("dual-pane");
        Path deep;
        try (var paths = Files.walk(workspace.resolve("编辑与评审工作目录"))) {
            deep = paths.filter(Files::isDirectory).max(Comparator.comparingInt(Path::getNameCount)).orElseThrow();
        }
        go(false, deep);
        shot("F06", "长路径和长面包屑");
        stageAction(() -> size(1280, 800));
        go(false, workspace.resolve("Batch index"));
        waitFor("large fixture listing", () -> browser(false).items().size() == 1500);
        shot("F07", "1500项的目录");
        go(false, project);
        mode("large");
        select(false, "River Survey.png");
        if (fx(() -> context(false).pane != BrowseContext.Pane.DETAILS)) { button("details-pane"); }
        awaitShot("F08", "高DPI图标文字与详细信息", () -> scene.lookup("#inspector") != null);
    }

    private FileOperationTask operation(FileOperationTask.Kind kind, List<FileChanges.Change> changes) {
        CountDownLatch gate = kind == FileOperationTask.Kind.COPY ? nextCopy.getAndSet(null) : null;
        String fault = kind == FileOperationTask.Kind.COPY ? nextFault.getAndSet(null) : null;
        if (fault != null) {
            int index = fault.equals("partial") ? 1 : 0;
            var change = changes.get(index);
            var injected = new FileChanges.Change(change.path(), change.folders(), change.evidence(), change.copyBytes(), task -> {
                requireFixture(change.path());
                if (!fault.equals("receipt")) { throw new AccessDeniedException(change.path().toString()); }
                var result = change.action().run(task);
                if (result.state() != FileChanges.State.APPLIED) { return result; }
                throw LocalFiles.uncertain("夹具已写入，模拟写入回执丢失", new IOException("fixture"),
                        change.path(), result.receipt().target());
            });
            var taskChanges = new ArrayList<>(changes);
            taskChanges.set(index, injected);
            return new FileOperationTask(kind, taskChanges);
        }
        return new FileOperationTask(kind, changes, sample -> {
            if (gate != null && gate.getCount() != 0 && sample.transferredBytes() > 0 && sample.currentPath() != null
                    && sample.currentPath().getFileName().toString().endsWith(".dat")
                    && heldCopy.compareAndSet(null, gate)) {
                try { gate.await(25, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        });
    }

    private void copyTo(Path source, Path target, String... names) throws Exception {
        requireFixture(source);
        requireFixture(target);
        go(false, source);
        select(false, names);
        button("copy");
        waitFor("clipboard fixture", () -> get(app, "clipboardCommand") == null && get(app, "transfer") != null);
        go(false, target);
        button("paste");
    }

    private void waitResult(FileOperationTask.Result result) throws Exception {
        waitFor("operation " + result, () -> {
            Object progress = get(app, "operationProgress");
            FileOperationTask task = (FileOperationTask) get(progress, "task");
            return task != null && task.snapshot().result() == result && get(app, "fileTask") == null;
        });
    }

    private void holdNextCopy() { nextCopy.set(new CountDownLatch(1)); }

    private void releaseCopy() {
        CountDownLatch gate = heldCopy.getAndSet(null);
        if (gate != null) { gate.countDown(); }
        gate = nextCopy.getAndSet(null);
        if (gate != null) { gate.countDown(); }
    }

    private void showProgress() throws Exception {
        stageAction(() -> call(get(app, "operationProgress"), "show"));
        waitFor("progress window", () -> shown("#operation-action") != null);
    }

    private void closeProgress() throws Exception {
        stageAction(() -> {
            Stage progress = (Stage) get(get(app, "operationProgress"), "window");
            progress.hide();
        });
    }

    private boolean hover(Drag drag, Node target) {
        Bounds bounds = target.localToScreen(target.getBoundsInLocal());
        if (bounds == null) { return false; }
        double x = bounds.getMinX() + Math.min(bounds.getWidth() / 2, 28);
        double y = bounds.getMinY() + Math.min(bounds.getHeight() / 2, 14);
        var event = new DragEvent(DragEvent.DRAG_OVER, drag.board(), 2, 2, x, y, TransferMode.COPY,
                drag.source(), null, new PickResult(target, 2, 2));
        Event.fireEvent(target, event);
        return event.isAccepted();
    }

    private record Drag(Dragboard board, Node source) { }

    @SuppressWarnings({"removal", "unchecked"})
    private record LocalDragboard(Map<DataFormat, Object> content) implements TKClipboard {
        @Override public void setSecurityContext(java.security.AccessControlContext context) { }
        @Override public Set<DataFormat> getContentTypes() { return content.keySet(); }
        @Override public boolean putContent(Pair<DataFormat, Object>... pairs) { throw new UnsupportedOperationException(); }
        @Override public Object getContent(DataFormat type) { return content.get(type); }
        @Override public boolean hasContent(DataFormat type) { return content.containsKey(type); }
        @Override public Set<TransferMode> getTransferModes() { return Set.of(TransferMode.COPY); }
        @Override public void setDragView(Image image) { throw new UnsupportedOperationException(); }
        @Override public void setDragViewOffsetX(double x) { throw new UnsupportedOperationException(); }
        @Override public void setDragViewOffsetY(double y) { throw new UnsupportedOperationException(); }
        @Override public Image getDragView() { return null; }
        @Override public double getDragViewOffsetX() { return 0; }
        @Override public double getDragViewOffsetY() { return 0; }
    }

    private void requireFixture(Path path) {
        require(path.toAbsolutePath().normalize().startsWith(fixture), "path outside audit fixture");
    }

    private void go(boolean right, Path path) throws Exception { requireFixture(path); go(right, path.toString()); }

    private void go(boolean right, String address) throws Exception {
        enter(right, address);
        waitFor("open " + address, () -> settled(right) &&
                (address.equals(context(right).location.label()) || address.equals(Objects.toString(context(right).location.path(), "")))
                && context(right).activeQuery.isEmpty());
    }

    private void enter(boolean right, String input) throws Exception {
        stageAction(() -> {
            focusNow(right);
            AddressBar bar = address(right);
            bar.beginEdit();
            TextField editor = (TextField) get(bar, "editor");
            editor.setText(input);
            editor.fireEvent(new ActionEvent());
        });
    }

    private void search(boolean right, String query) throws Exception {
        stageAction(() -> {
            focusNow(right);
            TextField field = (TextField) get(pane(right), "searchField");
            field.setText(query);
            field.fireEvent(new ActionEvent());
        });
        waitFor("query " + query, () -> settled(right) && context(right).activeQuery.equals(query));
    }

    private void openTab(Path path) throws Exception {
        stageAction(() -> call(app, "openTab", Location.class, Location.folder(path)));
        waitFor("tab " + path.getFileName(), () -> settled(false) && path.equals(context(false).location.path()));
    }

    private void button(String id) throws Exception {
        stageAction(() -> {
            Button button = (Button) scene.lookup("#" + id);
            require(button != null && !button.isDisabled(), "button unavailable " + id);
            button.fire();
        });
    }

    private void mode(String id) throws Exception { menu("view", "mode-" + id); }

    private void menu(String id, String itemId) throws Exception {
        stageAction(() -> {
            var menu = (MenuButton) scene.lookup("#" + id);
            menu.show();
            MenuItem item = findItem(menu.getItems(), itemId);
            require(item != null && !item.isDisable(), "menu item unavailable " + itemId);
            if (item instanceof CheckMenuItem check) { check.setSelected(!check.isSelected()); }
            if (item instanceof RadioMenuItem radio) { radio.setSelected(true); }
            item.fire();
            menu.hide();
        });
    }

    private static MenuItem findItem(List<MenuItem> items, String id) {
        for (MenuItem item : items) {
            if (id.equals(item.getId())) { return item; }
            if (item instanceof Menu menu) {
                MenuItem found = findItem(menu.getItems(), id);
                if (found != null) { return found; }
            }
        }
        return null;
    }

    private void select(boolean right, String... names) throws Exception {
        stageAction(() -> {
            BrowserView view = browser(right);
            List<BrowserItem> selection = view.items().stream().filter(item -> List.of(names).contains(item.name())).toList();
            require(selection.size() == names.length, "selection " + Arrays.toString(names));
            focusNow(right);
            call(view, "replaceSelection", List.class, selection);
            TableView<?> table = (TableView<?>) get(view, "table");
            if (table.isVisible()) {
                for (int index = 0; index < table.getItems().size(); index++) {
                    if (selection.contains(call(table.getItems().get(index), "item"))) {
                        if (index >= 13) { table.scrollTo(Math.max(0, index - 10)); }
                        break;
                    }
                }
            }
        });
    }

    private Node entry(boolean right, String name) throws Exception {
        BrowserView view = browser(right);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        for (Node node : view.lookupAll(".selectable-entry")) {
            if (node.getUserData() instanceof BrowserItem item && item.name().equals(name)) { return node; }
        }
        for (Node node : view.lookupAll(".table-row-cell")) {
            if (node instanceof TableRow<?> row && row.getItem() != null
                    && call(row.getItem(), "item") instanceof BrowserItem item && item.name().equals(name)) {
                Node cell = row.lookup(".table-cell");
                return cell == null ? row : cell;
            }
        }
        throw new IllegalStateException("No visible entry " + name);
    }

    private void rowMenu(String name) throws Exception {
        select(false, name);
        stageAction(() -> contextMenuEvent(entry(false, name)));
    }

    private void contextMenuEvent(Node node) {
        Bounds bounds = node.localToScreen(node.getBoundsInLocal());
        double x = bounds.getMinX() + Math.min(38, bounds.getWidth() / 2);
        double y = bounds.getMinY() + Math.min(18, bounds.getHeight() / 2);
        Event.fireEvent(node, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                5, 5, x, y, false, new PickResult(node, 5, 5)));
    }

    private static void mouse(Node node, javafx.event.EventType<MouseEvent> type) {
        Bounds bounds = node.localToScreen(node.getBoundsInLocal());
        Event.fireEvent(node, new MouseEvent(type, 2, 2, bounds.getMinX() + 2, bounds.getMinY() + 2,
                MouseButton.PRIMARY, 1, false, false, false, false, type == MouseEvent.MOUSE_PRESSED,
                false, false, false, false, true, new PickResult(node, 2, 2)));
    }

    private boolean contextMenuShown() {
        return Window.getWindows().stream().anyMatch(window -> window instanceof ContextMenu && window.isShowing());
    }

    private void hidePopups() throws Exception {
        stageAction(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window instanceof PopupWindow popup) { popup.hide(); }
            }
            for (Node node : scene.getRoot().lookupAll(".menu-button")) {
                if (node instanceof MenuButton menu && menu.isShowing()) { menu.hide(); }
            }
        });
    }

    private void dismissDialog(ButtonType type) throws Exception {
        stageAction(() -> {
            DialogPane dialog = (DialogPane) shown(".dialog-pane");
            if (dialog == null) { return; }
            Node button = dialog.lookupButton(type);
            if (button == null) { button = dialog.lookupButton(dialog.getButtonTypes().getLast()); }
            ((ButtonBase) button).fire();
        });
        waitFor("close dialogue", () -> shown(".dialog-pane") == null);
    }

    private Node shown(String selector) {
        for (Window w : List.copyOf(Window.getWindows())) {
            if (w.isShowing() && w.getScene() != null) {
                Node found = w.getScene().lookup(selector);
                if (found != null) { return found; }
            }
        }
        return null;
    }

    private void focus(boolean right) throws Exception { stageAction(() -> focusNow(right)); }

    private void focusNow(boolean right) throws Exception {
        var pane = pane(right);
        call(app, "activatePane", pane.getClass(), pane);
        browser(right).requestFocus();
    }

    private void size(double width, double height) {
        var visual = Screen.getPrimary().getVisualBounds();
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(visual.getMinX() + Math.max(10, (visual.getWidth() - width) / 2));
        stage.setY(visual.getMinY() + Math.max(5, (visual.getHeight() - height) / 2));
    }

    private void awaitShot(String id, String name, Callable<Boolean> ready) throws Exception {
        if (selected(id)) { waitFor(id + " " + name, ready); }
        shot(id, name);
    }

    private void waitForIcons() throws Exception {
        stageAction(() -> {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
        waitFor("system icon queue", () -> ((Map<?, ?>) get(get(app, "icons"), "pending")).isEmpty());
    }

    private boolean category(String prefix) {
        return wanted.isEmpty() || wanted.stream().anyMatch(id -> id.startsWith(prefix));
    }

    private boolean selected(String id) { return wanted.isEmpty() || wanted.contains(id); }

    private void shot(String id, String name) throws Exception {
        if (!selected(id)) { return; }
        Thread.sleep(325);
        var destination = output.resolve(id.charAt(0) + "/" + id + "_" + name + ".png");
        String logical = fx(() -> Math.round(stage.getWidth()) + "\t" + Math.round(stage.getHeight())
                + "\t" + stage.getOutputScaleX());
        var arguments = new ArrayList<>(List.of(python.toString(), helper.toString(), "capture", destination.toString(),
                "--pid", Long.toString(ProcessHandle.current().pid())));
        if (id.equals("C02")) { arguments.add("--include-owned"); }
        var capture = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        try (var stdout = capture.getInputStream()) {
            String metadata = new String(stdout.readAllBytes(), StandardCharsets.UTF_8).strip();
            require(capture.waitFor() == 0, id + " " + metadata);
            Files.writeString(output.resolve("captures.tsv"), id + "\t" + name + "\t" + logical + "\t" + metadata + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            captured++;
            System.out.println("PNG " + id + " " + metadata.replace('\t', ' '));
        }
    }

    private void attempt(String ids, Step action) {
        try { action.run(); }
        catch (Throwable exception) {
            releaseCopy();
            System.err.println("MISSED " + ids + ": " + exception);
            exception.printStackTrace();
        }
    }

    private void taskShots(String ids, Step action) {
        if (Arrays.stream(ids.split(",")).anyMatch(this::selected)) { attempt(ids, action); }
    }

    private Object pane(boolean right) throws Exception { return get(app, right ? "rightPane" : "leftPane"); }
    private BrowseContext context(boolean right) throws Exception { return (BrowseContext) get(pane(right), "context"); }
    private BrowserView browser(boolean right) throws Exception { return (BrowserView) get(pane(right), "browser"); }
    private AddressBar address(boolean right) throws Exception { return (AddressBar) get(pane(right), "address"); }
    private javafx.scene.Parent inspector(boolean right) throws Exception { return (javafx.scene.Parent) get(pane(right), "inspector"); }

    private boolean settled(boolean right) throws Exception {
        return pane(right) != null && context(right).browseRequest == null && !context(right).needsReload;
    }

    private void stageAction(Step action) throws Exception {
        fx(() -> { action.run(); return null; });
    }

    private static void waitFor(String description, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!fx(condition)) {
            if (System.nanoTime() >= deadline) { throw new TimeoutException(description); }
            Thread.sleep(30);
        }
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        var future = new FutureTask<>(work);
        Platform.runLater(future);
        try { return future.get(12, TimeUnit.SECONDS); }
        catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) { throw cause; }
            throw new IllegalStateException(exception.getCause());
        }
    }

    private static Object get(Object object, String name) throws Exception {
        if (object == null) { return null; }
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void put(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    private static Object call(Object object, String name) throws Exception {
        return call(object, name, null, null);
    }

    private static Object call(Object object, String name, Class<?> parameter, Object value) throws Exception {
        var method = parameter == null ? object.getClass().getDeclaredMethod(name)
                : object.getClass().getDeclaredMethod(name, parameter);
        method.setAccessible(true);
        try { return parameter == null ? method.invoke(object) : method.invoke(object, value); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) { throw cause; }
            throw new IllegalStateException(exception.getCause());
        }
    }

    private static void require(boolean ok, String description) {
        if (!ok) { throw new IllegalStateException(description); }
    }

    private static final class AuditPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        AuditPreferences(AbstractPreferences parent, String name) { super(parent, name); }
        @Override protected void putSpi(String key, String value) { values.put(key, value); }
        @Override protected String getSpi(String key) { return values.get(key); }
        @Override protected void removeSpi(String key) { values.remove(key); }
        @Override protected void removeNodeSpi() { values.clear(); }
        @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        @Override protected String[] childrenNamesSpi() { return new String[0]; }
        @Override protected AbstractPreferences childSpi(String name) { return new AuditPreferences(this, name); }
        @Override protected void syncSpi() { }
        @Override protected void flushSpi() { }
    }

    public static void main(String[] args) { launch(args); }
}

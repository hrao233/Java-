package edu.files;

import edu.files.BrowserView.Field;
import edu.files.BrowserView.Mode;
import edu.files.FileChanges.Selection;
import javafx.concurrent.Task;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Navigation and presentation state for one browser, confined to the JavaFX thread. */
final class BrowseContext {
    enum Page {
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

    enum HomeTab { RECENT, FAVORITES, SHARED }
    enum Pane { NONE, DETAILS, PREVIEW }
    enum Layout {
        TRADITIONAL("传统"), COLUMNS("列式"), HYBRID("混合");

        final String label;

        Layout(String label) { this.label = label; }
    }

    record Location(Page page, Path path) {
        static Location page(Page page) { return new Location(page, null); }
        static Location folder(Path path) { return new Location(Page.DIRECTORY, path.toAbsolutePath().normalize()); }

        String label() {
            if (path == null) { return page.title; }
            return path.getFileName() == null ? path.toString() : path.getFileName().toString();
        }
    }

    record View(Mode mode, Field sort, boolean descending, Field group) { }

    record Listing(List<BrowserItem> items, int unreadable, boolean limited, Map<Path, Double> imageRatios) {
        static final Listing EMPTY = new Listing(List.of(), 0, false);

        Listing(List<BrowserItem> items, int unreadable, boolean limited) {
            this(items, unreadable, limited, Map.of());
        }
    }

    record BrowseIntent(Location location, String query, HomeTab tab, String message, boolean openAddressFile,
                        Runnable committed, DirectoryEvents.Batch changes, boolean restoring) {
        BrowseIntent(Location location, String query, HomeTab tab, String message, boolean openAddressFile,
                     Runnable committed, DirectoryEvents.Batch changes) {
            this(location, query, tab, message, openAddressFile, committed, changes, false);
        }

        BrowseIntent(Location location, String query, HomeTab tab, String message, boolean openAddressFile,
                     Runnable committed) {
            this(location, query, tab, message, openAddressFile, committed, null, false);
        }

        boolean automatic() { return changes != null; }
    }

    record SelectedItem(BrowserItem item, Selection proof) { }
    record BrowseData(Listing listing, DirectoryIdentity identity, Path file,
                      Map<BrowserItem, SelectedItem> verified) { }
    record BrowseRequest(BrowseContext owner, BrowseIntent intent, Task<BrowseData> task, long queuedAt) { }
    record FileRefresh(String message, Runnable committed) { }

    private static final Map<Page, View> DEFAULT_VIEWS = Map.of(
            Page.HOME, new View(Mode.DETAILS, Field.VISITED, true, null),
            Page.COMPUTER, new View(Mode.TILES, Field.NAME, false, null),
            Page.GALLERY, new View(Mode.LARGE, Field.MODIFIED, true, Field.MODIFIED));
    private static final View DIRECTORY_VIEW = new View(Mode.DETAILS, Field.NAME, false, null);

    final long id;
    final Deque<Location> past = new ArrayDeque<>();
    final Deque<Location> future = new ArrayDeque<>();
    final Map<Page, View> views = new EnumMap<>(Page.class);

    Location location = Location.page(Page.HOME);
    HomeTab homeTab = HomeTab.RECENT;
    String activeQuery = "";
    boolean searchExpanded;
    boolean quickAccessExpanded = true;
    Pane pane = Pane.NONE;
    Layout layout = Layout.TRADITIONAL;
    double hierarchyHeight = 0.33;
    BrowserView.Presentation presentation = BrowserView.Presentation.DEFAULT;
    Location opening;
    Listing listing = Listing.EMPTY;
    DirectoryIdentity directoryIdentity;
    boolean directoryUnavailable;
    boolean needsReload = true;
    boolean closed;
    BrowseRequest browseRequest;
    long listingVersion;
    long listingQueuedAt;
    DirectoryWatcher.Target watchTarget;
    DirectoryEvents.Batch pendingChanges;
    FileRefresh pendingFileRefresh;
    String deferredRefresh;
    String refreshOnReturn = "";
    Task<?> addressRead;
    Task<?> previewTask;
    boolean previewLoaded;
    boolean keepPreview;
    Task<Map<BrowserItem, Selection>> selectionRead;
    Map<BrowserItem, Selection> selectionProof = Map.of();
    Task<Set<BrowserItem>> cutMarkRead;

    BrowseContext(long id) { this.id = id; }

    View view() { return views.getOrDefault(location.page(), DEFAULT_VIEWS.getOrDefault(location.page(), DIRECTORY_VIEW)); }

    void copyFrom(BrowseContext original) {
        location = original.location;
        homeTab = original.homeTab;
        activeQuery = original.activeQuery;
        searchExpanded = original.searchExpanded;
        directoryIdentity = original.directoryIdentity;
        directoryUnavailable = original.directoryUnavailable;
        quickAccessExpanded = original.quickAccessExpanded;
        pane = original.pane;
        layout = original.layout;
        hierarchyHeight = original.hierarchyHeight;
        past.addAll(original.past);
        future.addAll(original.future);
        views.putAll(original.views);
        presentation = original.presentation;
        selectionProof = original.selectionProof;
    }
}

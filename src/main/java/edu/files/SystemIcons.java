package edu.files;

import javafx.concurrent.Task;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.filechooser.FileSystemView;
import javax.swing.plaf.metal.MetalIconFactory;
import java.awt.Canvas;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.DoubleSupplier;

final class SystemIcons implements AutoCloseable {
    private static final int CACHE_SIZE = 256;
    private static final String COMPUTER = "::{20D04FE0-3AEA-1069-A2D8-08002B30309D}";

    enum Namespace { HOME, GALLERY, COMPUTER, NETWORK, LINUX }

    private record Key(Path path, String shellTarget, String label, BrowserItem.Kind kind, int pixels,
                       Namespace namespace) { }
    private record Fallback(BrowserItem.Kind kind, int pixels) { }

    private final LinkedHashMap<Key, Image> cache = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Fallback, Image> fallbacks = new HashMap<>();
    private final Map<Key, List<WeakReference<ImageView>>> pending = new HashMap<>();
    private final Image emptyIcon = new WritableImage(1, 1);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "file-manager-icons");
        thread.setDaemon(true);
        return thread;
    });
    private final DoubleSupplier outputScale;
    private Map<String, File> shellItems;
    private Map<Namespace, BufferedImage> namespaceIcons;
    private boolean closed;

    SystemIcons(DoubleSupplier outputScale) {
        this.outputScale = outputScale;
    }

    ImageView graphic(BrowserItem item, int size) {
        return graphic(new Key(item.path(), item.shellTarget(), item.path() == null ? item.name() : null,
                item.kind(), pixels(size), null), size);
    }

    ImageView namespaceGraphic(Namespace namespace, int size) {
        return graphic(new Key(null, null, null, BrowserItem.Kind.SHELL, pixels(size), namespace), size);
    }

    private int pixels(int size) {
        return Math.max(size, (int) Math.ceil(size * outputScale.getAsDouble()));
    }

    private ImageView graphic(Key key, int size) {
        Image icon = cache.get(key);
        ImageView view = new ImageView(icon == null ? fallback(key) : icon);
        view.getStyleClass().add("system-file-icon");
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setMouseTransparent(true);
        if (icon == null && !closed) {
            List<WeakReference<ImageView>> waiting = pending.get(key);
            if (waiting == null) {
                waiting = new ArrayList<>();
                pending.put(key, waiting);
                request(key);
            }
            waiting.add(new WeakReference<>(view));
        }
        return view;
    }

    private void request(Key key) {
        Task<BufferedImage> task = new Task<>() {
            @Override
            protected BufferedImage call() {
                if (key.namespace() != null) {
                    if (namespaceIcons == null) { namespaceIcons = loadNamespaceIcons(); }
                    BufferedImage source = namespaceIcons.get(key.namespace());
                    return source == null ? null : render(new ImageIcon(source), key.pixels());
                }
                FileSystemView view = FileSystemView.getFileSystemView();
                File file = key.path() == null ? shellItem(view, key) : key.path().toFile();
                if (file == null) {
                    return null;
                }
                Icon icon = view.getSystemIcon(file, key.pixels(), key.pixels());
                return icon == null ? null : render(icon, key.pixels());
            }
        };
        task.setOnSucceeded(event -> finish(key, task.getValue()));
        task.setOnFailed(event -> finish(key, null));
        worker.execute(task);
    }

    private static Map<Namespace, BufferedImage> loadNamespaceIcons() {
        Map<Namespace, BufferedImage> result = new EnumMap<>(Namespace.class);
        for (String line : WindowsPlaces.readScriptOutput("windows-shell-icons.ps1")) {
            String[] fields = line.split("\t", 2);
            if (fields.length != 2) { continue; }
            try {
                var data = Base64.getDecoder().decode(fields[1]);
                BufferedImage icon = ImageIO.read(new ByteArrayInputStream(data));
                if (icon != null) { result.put(Namespace.valueOf(fields[0]), icon); }
            } catch (IOException | IllegalArgumentException exception) {
                // Keep icons that the shell returned successfully if another item was unavailable.
            }
        }
        return result;
    }

    private File shellItem(FileSystemView view, Key key) {
        if (shellItems == null) {
            shellItems = new HashMap<>();
            for (File root : view.getRoots()) {
                for (File item : view.getFiles(root, false)) {
                    index(view, item);
                    if (COMPUTER.equalsIgnoreCase(item.getPath())) {
                        for (File child : view.getFiles(item, false)) {
                            index(view, child);
                        }
                    }
                }
            }
        }
        File match = shellItems.get(key.shellTarget().toLowerCase(Locale.ROOT));
        if (match == null) {
            match = shellItems.get(key.label().toLowerCase(Locale.ROOT));
        }
        return match != null || key.shellTarget().startsWith("::") ? match : new File(key.shellTarget());
    }

    private void index(FileSystemView view, File item) {
        shellItems.put(item.getPath().toLowerCase(Locale.ROOT), item);
        String label = view.getSystemDisplayName(item);
        if (label != null) {
            shellItems.putIfAbsent(label.toLowerCase(Locale.ROOT), item);
        }
    }

    private void finish(Key key, BufferedImage nativeIcon) {
        List<WeakReference<ImageView>> waiting = pending.remove(key);
        if (waiting == null) {
            return;
        }
        Image image = nativeIcon == null ? fallback(key) : toImage(nativeIcon);
        cache.put(key, image);
        if (cache.size() > CACHE_SIZE) {
            cache.pollFirstEntry();
        }
        for (WeakReference<ImageView> reference : waiting) {
            ImageView view = reference.get();
            if (view != null) {
                view.setImage(image);
            }
        }
    }

    private Image fallback(Key item) {
        if (item.namespace() != null) { return emptyIcon; }
        return fallbacks.computeIfAbsent(new Fallback(item.kind(), item.pixels()), key -> {
            Icon icon = switch (key.kind()) {
                case FILE -> MetalIconFactory.getTreeLeafIcon();
                case FOLDER -> MetalIconFactory.getTreeFolderIcon();
                case DRIVE -> MetalIconFactory.getTreeHardDriveIcon();
                case SHELL -> MetalIconFactory.getTreeComputerIcon();
            };
            return toImage(render(icon, key.pixels()));
        });
    }

    private static BufferedImage render(Icon icon, int pixels) {
        int width = icon.getIconWidth();
        int height = icon.getIconHeight();
        double scale = (double) pixels / Math.max(width, height);
        BufferedImage image = new BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.translate((pixels - width * scale) / 2, (pixels - height * scale) / 2);
            graphics.scale(scale, scale);
            icon.paintIcon(new Canvas(), graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static Image toImage(BufferedImage source) {
        int size = source.getWidth();
        WritableImage image = new WritableImage(size, size);
        image.getPixelWriter().setPixels(0, 0, size, size, PixelFormat.getIntArgbInstance(),
                source.getRGB(0, 0, size, size, null, 0, size), 0, size);
        return image;
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
        pending.clear();
        cache.clear();
        fallbacks.clear();
    }
}

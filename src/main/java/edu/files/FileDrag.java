package edu.files;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.win32.StdCallLibrary;
import javafx.scene.input.DataFormat;
import javafx.scene.input.TransferMode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class FileDrag {
    static final DataFormat SESSION = new DataFormat("application/x-javafm-drag-session");

    enum Payload { INTERNAL, EXTERNAL, INVALID, OTHER }
    enum Intent { COPY, MOVE, LINK }

    interface Keys extends StdCallLibrary {
        short GetAsyncKeyState(int key);
    }

    private static final class WindowsKeys {
        static final Keys KEYS = Native.load("user32", Keys.class);
    }

    private FileDrag() { }

    static Intent keyIntent(TransferMode proposed) {
        if (!Platform.isWindows()) { return proposed == TransferMode.LINK ? Intent.LINK : Intent.COPY; }
        Keys keys = WindowsKeys.KEYS;
        var requested = keyIntent(keys.GetAsyncKeyState(0x11) < 0, keys.GetAsyncKeyState(0x10) < 0,
                keys.GetAsyncKeyState(0x12) < 0);
        return proposed == TransferMode.LINK ? Intent.LINK : requested;
    }

    static Intent keyIntent(boolean control, boolean shift, boolean alt) {
        if (alt || control && shift) { return Intent.LINK; }
        return shift ? Intent.MOVE : Intent.COPY;
    }

    static Payload classify(Source current, Object gesture, boolean marked, Object token, boolean files) {
        if (marked) {
            return current != null && current.gesture() == gesture && current.token().equals(token)
                    ? Payload.INTERNAL : Payload.INVALID;
        }
        return files ? (gesture == null ? Payload.EXTERNAL : Payload.INVALID) : Payload.OTHER;
    }

    static boolean ordinary(BrowserItem item) {
        return item.entry() != null && item.path().getParent() != null
                && !item.entry().link() && !item.entry().other();
    }

    record Source(Object gesture, String token, List<BrowserItem> items, Map<BrowserItem, FileChanges.Selection> proofs,
                  List<Path> paths, Set<Path> parents, Set<Path> directories, boolean verified, Long volume) {
        static Source create(Object gesture, List<BrowserItem> items, Map<BrowserItem, FileChanges.Selection> proofs)
                throws IOException {
            if (items.stream().anyMatch(item -> !ordinary(item))) {
                throw new IOException("请选择普通文件或文件夹");
            }
            var paths = ClipboardFiles.dragPaths(items.stream().map(item -> item.path().toString()).toList());
            if (paths.size() != items.size()) { throw new IOException("拖放选择包含重复项目"); }
            Set<Path> parents = new HashSet<>();
            Set<Path> directories = new HashSet<>();
            Long volume = null;
            boolean verified = true;
            boolean mixedVolumes = false;
            for (BrowserItem item : items) {
                parents.add(item.path().getParent());
                if (item.directory()) { directories.add(item.path()); }
                var proof = proofs.get(item);
                if (proof == null || proof.nativeItem() == null) {
                    verified = false;
                } else {
                    long itemVolume = proof.nativeItem().id().volume();
                    if (volume != null && volume != itemVolume) { mixedVolumes = true; }
                    volume = itemVolume;
                }
            }
            return new Source(gesture, UUID.randomUUID().toString(), List.copyOf(items), Map.copyOf(proofs),
                    paths, Set.copyOf(parents), Set.copyOf(directories), verified, verified && !mixedVolumes ? volume : null);
        }

        String restriction(Path target, Long targetVolume, Intent intent) {
            if (parents.contains(target)) { return "来源与目标目录相同"; }
            for (Path ancestor = target; ancestor != null; ancestor = ancestor.getParent()) {
                if (directories.contains(ancestor)) { return "目标位于所拖文件夹内"; }
            }
            if (intent == Intent.MOVE) {
                if (!verified) { return "移动需要先选中并核验来源"; }
                if (volume == null || targetVolume != null && !volume.equals(targetVolume)) {
                    return "拖放移动仅支持同卷 NTFS";
                }
            }
            return null;
        }
    }
}

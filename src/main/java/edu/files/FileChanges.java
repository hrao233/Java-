package edu.files;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static edu.files.FileChanges.State.*;

final class FileChanges {
    enum State {
        APPLIED, UNCHANGED, FAILED, UNKNOWN, NOT_RUN, REFUSED;

        boolean pending() {
            return this == FAILED || this == UNKNOWN || this == NOT_RUN;
        }
    }

    @FunctionalInterface
    interface Action { Effect run() throws IOException; }

    record Selection(LocalFiles.Entry entry, NtfsFileOperations.Item nativeItem) {
        Path path() { return entry.path(); }

        boolean needsPathConfirmation() {
            return nativeItem == null && !entry.hasIdentity();
        }

        boolean needsPathConfirmation(Destination destination, boolean moving) {
            if (nativeItem == null) { return needsPathConfirmation(); }
            return moving ? !NtfsFileOperations.sameVolume(nativeItem, destination.directory())
                    : destination.directory() == null;
        }
    }

    record Destination(Path path, NtfsFileOperations.Directory directory) { }
    record Receipt(Path source, Path target, NtfsFileOperations.Item identity) { }

    record Change(Path path, List<Path> folders, Action action) { }

    record Effect(boolean changed, Change reversal, Receipt receipt) {
        static final Effect UNCHANGED = new Effect(false, null, null);
        static Effect written(Change reversal) { return written(reversal, null); }
        static Effect written(Change reversal, Receipt receipt) { return new Effect(true, reversal, receipt); }
    }

    record Item(Change change, State state, Change reversal, Receipt receipt, IOException error) { }

    record Outcome(List<Item> items) {
        Outcome { items = List.copyOf(items); }

        long count(State state) {
            return items.stream().filter(item -> item.state() == state).count();
        }

        List<Change> reversals() {
            return items.reversed().stream().map(Item::reversal).filter(Objects::nonNull).toList();
        }

        List<Change> pending() {
            return items.stream().filter(item -> item.state().pending()).map(Item::change).toList();
        }

        <T> List<T> remainingCut(List<T> sources) {
            int completedPrefix = Math.toIntExact(count(APPLIED) + count(UNCHANGED));
            return List.copyOf(sources.subList(completedPrefix, sources.size()));
        }

        IOException error() {
            return items.stream().filter(item -> item.state() == FAILED || item.state() == UNKNOWN)
                    .map(Item::error).findFirst().orElseGet(() -> items.stream().map(Item::error)
                            .filter(Objects::nonNull).findFirst().orElse(null));
        }

        String summary(String completed) {
            if (count(UNCHANGED) == items.size()) {
                return "项目已在目标位置";
            }
            List<String> parts = new ArrayList<>();
            addCount(parts, APPLIED, completed);
            addCount(parts, UNCHANGED, "无需更改");
            addCount(parts, FAILED, "失败");
            addCount(parts, UNKNOWN, "结果未知");
            addCount(parts, NOT_RUN, "未执行");
            addCount(parts, REFUSED, "已拒绝撤销");
            return String.join(" · ", parts);
        }

        private void addCount(List<String> parts, State state, String label) {
            long count = count(state);
            if (count > 0) {
                parts.add(label + " " + count + " 项");
            }
        }
    }

    private static final class UndoRefused extends IOException {
        UndoRefused(String detail) { super(detail); }
    }

    private FileChanges() { }

    static Selection capture(LocalFiles.Entry entry) throws IOException {
        return new Selection(entry, NtfsFileOperations.capture(entry));
    }

    static Destination destination(Path path) throws IOException {
        return new Destination(path, NtfsFileOperations.captureDirectory(path));
    }

    static Change transfer(LocalFiles.Entry source, Path directory, boolean move, boolean confirmedCurrentPath) {
        return transfer(new Selection(source, null), new Destination(directory, null), move, confirmedCurrentPath);
    }

    static Change transfer(Selection source, Destination destination, boolean move, boolean confirmedCurrentPath) {
        Path directory = destination.path();
        List<Path> folders = move ? List.of(source.path().getParent(), directory) : List.of(directory);
        return new Change(source.path(), folders, () -> {
            if (source.needsPathConfirmation(destination, move) && !confirmedCurrentPath) {
                throw new IOException("无法确认身份，请刷新后重新选择：" + source.path());
            }
            var nativeItem = source.nativeItem();
            if (move && nativeItem != null && NtfsFileOperations.sameVolume(nativeItem, destination.directory())) {
                return moveByHandle(nativeItem, destination.directory(), nativeItem.name(), true);
            }
            if (!move) {
                Path copied;
                if (nativeItem != null) {
                    copied = NtfsFileOperations.copy(nativeItem, directory, destination.directory()).path();
                } else {
                    LocalFiles.Entry selected = LocalFiles.verify(source.entry(), confirmedCurrentPath);
                    copied = LocalFiles.copy(selected.path(), directory).path();
                }
                return Effect.written(refuseUndo(copied, "为保护副本及其中的后续修改，复制暂不支持自动撤销"),
                        new Receipt(source.path(), copied, null));
            }
            LocalFiles.Entry selected = verifyPathSource(source, confirmedCurrentPath);
            LocalFiles.Entry parent = originalParent(selected);
            Path target = LocalFiles.move(selected.path(), directory);
            return target == null ? Effect.UNCHANGED : Effect.written(reverseMove(selected, parent, target),
                    new Receipt(selected.path(), target, null));
        });
    }

    static Change rename(LocalFiles.Entry source, Path target, boolean confirmedCurrentPath) {
        return rename(new Selection(source, null), target, confirmedCurrentPath);
    }

    static Change rename(Selection source, Path target, boolean confirmedCurrentPath) {
        return new Change(source.path(), List.of(source.path().getParent()), () -> {
            var nativeItem = source.nativeItem();
            if (nativeItem != null) {
                return moveByHandle(nativeItem, nativeItem.parent(), target.getFileName().toString(), true);
            }
            LocalFiles.Entry selected = LocalFiles.verify(source.entry(), confirmedCurrentPath);
            Path destination = LocalFiles.childNamed(selected.path().getParent(), target.getFileName().toString());
            if (selected.path().toString().equals(destination.toString())) {
                return Effect.UNCHANGED;
            }
            LocalFiles.Entry parent = originalParent(selected);
            LocalFiles.rename(selected.path(), destination);
            return Effect.written(reverseMove(selected, parent, destination), new Receipt(selected.path(), destination, null));
        });
    }

    static Change recycle(LocalFiles.Entry source, boolean confirmedCurrentPath, LocalFiles.RecycleBin recycleBin) {
        return recycle(new Selection(source, null), confirmedCurrentPath, recycleBin);
    }

    static Change recycle(Selection source, boolean confirmedCurrentPath, LocalFiles.RecycleBin recycleBin) {
        return new Change(source.path(), List.of(source.path().getParent()), () -> {
            LocalFiles.Entry selected = verifyPathSource(source, confirmedCurrentPath);
            LocalFiles.trash(selected.path(), recycleBin);
            return Effect.written(refuseUndo(selected.path(), "请在系统回收站查看已回收的项目"),
                    new Receipt(selected.path(), null, null));
        });
    }

    private static LocalFiles.Entry verifyPathSource(Selection source, boolean confirmedCurrentPath) throws IOException {
        if (source.nativeItem() != null) {
            NtfsFileOperations.verify(source.nativeItem());
            return source.entry();
        }
        return LocalFiles.verify(source.entry(), confirmedCurrentPath);
    }

    private static Effect moveByHandle(NtfsFileOperations.Item source, NtfsFileOperations.Directory destination,
                                       String name, boolean keepReversal) throws IOException {
        var moved = NtfsFileOperations.move(source, destination, name);
        if (moved == null) { return Effect.UNCHANGED; }
        Change reversal = null;
        if (keepReversal) {
            if (moved.result() == null) {
                reversal = refuseUndo(moved.target(), "改名已完成，但无法取得目标身份，已禁用自动撤销");
            } else {
                reversal = new Change(moved.target(), List.of(source.parent().path(), destination.path()),
                        () -> moveByHandle(moved.result(), source.parent(), source.name(), false));
            }
        }
        return Effect.written(reversal, new Receipt(source.path(), moved.target(), moved.result()));
    }

    static Change undoCreation(Path path) {
        return refuseUndo(path, "为保护新建项目中的数据，新建暂不支持自动撤销");
    }

    private static LocalFiles.Entry originalParent(LocalFiles.Entry selected) throws IOException {
        return selected.hasIdentity() ? LocalFiles.describe(selected.path().getParent()) : null;
    }

    private static Change reverseMove(LocalFiles.Entry source, LocalFiles.Entry originalParent, Path target) {
        if (originalParent != null && originalParent.hasIdentity()) {
            try {
                LocalFiles.Entry moved = LocalFiles.describe(target);
                if (source.sameObject(moved)) {
                    return new Change(target, List.of(source.path().getParent(), target.getParent()), () -> {
                        LocalFiles.verify(originalParent, false);
                        LocalFiles.Entry current = LocalFiles.verify(moved, false);
                        LocalFiles.rename(current.path(), source.path());
                        return Effect.written(null);
                    });
                }
            } catch (IOException unreadable) {
                return refuseUndo(target, "无法读取操作后的项目身份，已禁用自动撤销");
            }
        }
        return refuseUndo(target, "无法确认操作后的项目身份，已禁用自动撤销");
    }

    private static Change refuseUndo(Path path, String reason) {
        return new Change(path, List.of(), () -> {
            throw new UndoRefused(reason + "：" + path);
        });
    }

    static Outcome execute(List<Change> changes) {
        List<Item> results = new ArrayList<>();
        boolean stopped = false;
        for (Change change : changes) {
            if (stopped) {
                results.add(new Item(change, NOT_RUN, null, null, null));
                continue;
            }
            try {
                Effect effect = change.action().run();
                results.add(new Item(change, effect.changed() ? APPLIED : UNCHANGED, effect.reversal(), effect.receipt(), null));
            } catch (UndoRefused refusal) {
                results.add(new Item(change, REFUSED, null, null, refusal));
            } catch (LocalFiles.UncertainWrite failure) {
                results.add(new Item(change, UNKNOWN, null, null, failure));
                stopped = true;
            } catch (IOException failure) {
                results.add(new Item(change, FAILED, null, null, failure));
                stopped = true;
            }
        }
        return new Outcome(results);
    }
}

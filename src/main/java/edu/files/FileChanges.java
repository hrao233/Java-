package edu.files;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static edu.files.FileChanges.State.*;

final class FileChanges {
    enum State {
        APPLIED, UNCHANGED, FAILED, UNKNOWN, NOT_RUN, CANCELLED, REFUSED;

        boolean pending() {
            return this == FAILED || this == NOT_RUN || this == CANCELLED;
        }
    }

    @FunctionalInterface
    interface Action { Effect run() throws IOException; }

    @FunctionalInterface
    interface TaskAction { Effect run(FileOperationTask task) throws IOException; }

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

    record Destination(Path path, NtfsFileOperations.Directory directory) {
        void verifyIfKnown() throws IOException {
            if (directory != null) { NtfsFileOperations.verify(directory); }
        }
    }
    record Receipt(Path source, Path target, NtfsFileOperations.Item identity) { }

    record Evidence(Path target, LocalFiles.Entry selected, NtfsFileOperations.Item source,
                    NtfsFileOperations.Directory destination) { }

    record Change(Path path, List<Path> folders, Evidence evidence, long copyBytes, TaskAction action) {
        Change(Path path, List<Path> folders, Action action) { this(path, folders, null, action); }
        Change(Path path, List<Path> folders, Evidence evidence, Action action) {
            this(path, folders, evidence, -1, task -> {
                task.beforeCommit(FileOperationTask.Phase.EXECUTING);
                return action.run();
            });
        }
    }

    record UnknownOperation(String operation, Instant recordedAt, Path path, Evidence evidence,
                            LocalFiles.UncertainWrite error) {
        List<Path> paths() {
            List<Path> paths = new ArrayList<>();
            paths.add(path);
            if (evidence != null && evidence.target() != null) { paths.add(evidence.target()); }
            error.observations().stream().map(LocalFiles.Observation::path).forEach(paths::add);
            return paths.stream().distinct().toList();
        }

        @Override
        public String toString() { return operation + " · " + path.getFileName(); }
    }

    record Effect(boolean changed, Change reversal, Receipt receipt) {
        static final Effect UNCHANGED = new Effect(false, null, null);
        static Effect written(Change reversal) { return written(reversal, null); }
        static Effect written(Change reversal, Receipt receipt) { return new Effect(true, reversal, receipt); }
    }

    record Item(Change change, State state, Change reversal, Receipt receipt, IOException error) { }

    record Outcome(List<Item> items, List<FileOperationTask.CleanupIssue> cleanupIssues) {
        Outcome(List<Item> items) { this(items, List.of()); }
        Outcome {
            items = List.copyOf(items);
            cleanupIssues = List.copyOf(cleanupIssues);
        }

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
            return remaining(sources, State::pending);
        }

        <T> List<T> remainingCopy(List<T> sources) {
            return remaining(sources, state -> state != UNKNOWN);
        }

        private <T> List<T> remaining(List<T> sources, java.util.function.Predicate<State> keep) {
            if (sources.size() != items.size()) { throw new IllegalArgumentException("Selection does not match batch"); }
            List<T> remaining = new ArrayList<>();
            for (int index = 0; index < items.size(); index++) {
                if (keep.test(items.get(index).state())) { remaining.add(sources.get(index)); }
            }
            return List.copyOf(remaining);
        }

        List<UnknownOperation> unknownOperations(String operation) {
            return items.stream().filter(item -> item.state() == UNKNOWN)
                    .map(item -> new UnknownOperation(operation, Instant.now(), item.change().path(),
                            item.change().evidence(), (LocalFiles.UncertainWrite) item.error())).toList();
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
            addCount(parts, CANCELLED, "已取消");
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
        var evidence = evidence(source, directory.resolve(source.path().getFileName()), destination.directory());
        return new Change(source.path(), folders, evidence, () -> {
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
                    destination.verifyIfKnown();
                    copied = LocalFiles.copy(selected.path(), directory).path();
                }
                return Effect.written(refuseUndo(copied, "为保护副本及其中的后续修改，复制暂不支持自动撤销"),
                        new Receipt(source.path(), copied, null));
            }
            LocalFiles.Entry selected = verifyPathSource(source, confirmedCurrentPath);
            LocalFiles.Entry parent = originalParent(selected);
            destination.verifyIfKnown();
            Path target = LocalFiles.move(selected.path(), directory);
            return target == null ? Effect.UNCHANGED : Effect.written(reverseMove(selected, parent, target),
                    new Receipt(selected.path(), target, null));
        });
    }

    static Change rename(LocalFiles.Entry source, Path target, boolean confirmedCurrentPath) {
        return rename(new Selection(source, null), target, confirmedCurrentPath);
    }

    static Change rename(Selection source, Path target, boolean confirmedCurrentPath) {
        var nativeParent = source.nativeItem() == null ? null : source.nativeItem().parent();
        return new Change(source.path(), List.of(source.path().getParent()), evidence(source, target, nativeParent), () -> {
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
        return new Change(source.path(), List.of(source.path().getParent()), evidence(source, null, null), () -> {
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

    private static Evidence evidence(Selection source, Path target, NtfsFileOperations.Directory destination) {
        return new Evidence(target, source.entry(), source.nativeItem(), destination);
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
                        new Evidence(source.path(), null, moved.result(), source.parent()),
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
                    var evidence = new Evidence(source.path(), moved, null, null);
                    return new Change(target, List.of(source.path().getParent(), target.getParent()), evidence, () -> {
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
        return new FileOperationTask(FileOperationTask.Kind.OTHER, changes).run();
    }

    static Outcome execute(List<Change> changes, FileOperationTask task) {
        List<Item> results = new ArrayList<>();
        boolean stopped = false;
        for (Change change : changes) {
            if (stopped || task.cancellationRequested()) {
                results.add(new Item(change, NOT_RUN, null, null, null));
                continue;
            }
            Item item;
            try {
                task.startItem(change);
                Effect effect = change.action().run(task);
                item = new Item(change, effect.changed() ? APPLIED : UNCHANGED, effect.reversal(), effect.receipt(), null);
            } catch (FileOperationTask.Cancelled cancel) {
                item = new Item(change, CANCELLED, null, null, null);
                stopped = true;
            } catch (UndoRefused refusal) {
                item = new Item(change, REFUSED, null, null, refusal);
            } catch (LocalFiles.UncertainWrite failure) {
                item = new Item(change, UNKNOWN, null, null, failure);
                stopped = true;
            } catch (IOException failure) {
                item = new Item(change, FAILED, null, null, failure);
                stopped = true;
            }
            results.add(item);
            task.finishedItem(item.state());
        }
        return new Outcome(results);
    }
}

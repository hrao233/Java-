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

    record Change(Path path, List<Path> folders, Action action) { }

    record Effect(boolean changed, Change reversal) {
        static final Effect UNCHANGED = new Effect(false, null);
        static Effect written(Change reversal) { return new Effect(true, reversal); }
    }

    record Item(Change change, State state, Change reversal, IOException error) { }

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

        List<LocalFiles.Entry> remainingCut(List<LocalFiles.Entry> sources) {
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

    static Change transfer(LocalFiles.Entry source, Path directory, boolean move, boolean confirmedCurrentPath) {
        List<Path> folders = move ? List.of(source.path().getParent(), directory) : List.of(directory);
        return new Change(source.path(), folders, () -> {
            LocalFiles.Entry selected = LocalFiles.verify(source, confirmedCurrentPath);
            if (!move) {
                Path copied = LocalFiles.copy(selected.path(), directory).path();
                return Effect.written(refuseUndo(copied, "为保护副本及其中的后续修改，复制暂不支持自动撤销"));
            }
            LocalFiles.Entry parent = originalParent(selected);
            Path target = LocalFiles.move(selected.path(), directory);
            return target == null ? Effect.UNCHANGED : Effect.written(reverseMove(selected, parent, target));
        });
    }

    static Change rename(LocalFiles.Entry source, Path target, boolean confirmedCurrentPath) {
        return new Change(source.path(), List.of(source.path().getParent()), () -> {
            LocalFiles.Entry selected = LocalFiles.verify(source, confirmedCurrentPath);
            Path destination = LocalFiles.childNamed(selected.path().getParent(), target.getFileName().toString());
            if (selected.path().toString().equals(destination.toString())) {
                return Effect.UNCHANGED;
            }
            LocalFiles.Entry parent = originalParent(selected);
            LocalFiles.rename(selected.path(), destination);
            return Effect.written(reverseMove(selected, parent, destination));
        });
    }

    static Change recycle(LocalFiles.Entry source, boolean confirmedCurrentPath, LocalFiles.RecycleBin recycleBin) {
        return new Change(source.path(), List.of(source.path().getParent()), () -> {
            LocalFiles.Entry selected = LocalFiles.verify(source, confirmedCurrentPath);
            LocalFiles.trash(selected.path(), recycleBin);
            return Effect.written(refuseUndo(selected.path(), "请在系统回收站查看已回收的项目"));
        });
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
                results.add(new Item(change, NOT_RUN, null, null));
                continue;
            }
            try {
                Effect effect = change.action().run();
                results.add(new Item(change, effect.changed() ? APPLIED : UNCHANGED, effect.reversal(), null));
            } catch (UndoRefused refusal) {
                results.add(new Item(change, REFUSED, null, refusal));
            } catch (LocalFiles.UncertainWrite failure) {
                results.add(new Item(change, UNKNOWN, null, failure));
                stopped = true;
            } catch (IOException failure) {
                results.add(new Item(change, FAILED, null, failure));
                stopped = true;
            }
        }
        return new Outcome(results);
    }
}

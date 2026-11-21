package edu.files;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

record BrowserItem(Path path, String name, Kind kind, LocalFiles.Entry entry,
                   Disk disk, String shellTarget, FileTime visited) {
    enum Kind { FILE, FOLDER, DRIVE, SHELL }

    record Disk(String type, Long capacity, Long available, String fileSystem, String comment, String networkPath) {
        Double used() {
            return capacity == null || available == null || capacity == 0 ? null : (capacity - available.doubleValue()) / capacity;
        }
    }

    static BrowserItem file(LocalFiles.Entry entry) {
        return new BrowserItem(entry.path(), entry.name(), entry.directory() ? Kind.FOLDER : Kind.FILE,
                entry, null, null, null);
    }

    static BrowserItem folder(Path path, String name) {
        return new BrowserItem(path, name, Kind.FOLDER, null, null, null, null);
    }

    static BrowserItem drive(Path path, String name, Disk disk) {
        return new BrowserItem(path, name, Kind.DRIVE, null, disk, null, null);
    }

    static BrowserItem shell(String name, String target) {
        return new BrowserItem(null, name, Kind.SHELL, null, null, target, null);
    }

    BrowserItem visitedAt(FileTime time) {
        return new BrowserItem(path, name, kind, entry, disk, shellTarget, time);
    }

    boolean directory() {
        return kind == Kind.FOLDER || kind == Kind.DRIVE;
    }

    String displayName(boolean extensions) {
        int dot = name.lastIndexOf('.');
        return !extensions && kind == Kind.FILE && dot > 0 ? name.substring(0, dot) : name;
    }

    String type() {
        return switch (kind) {
            case FILE -> entry.type();
            case FOLDER -> "文件夹";
            case DRIVE -> disk.type();
            case SHELL -> "系统快捷方式";
        };
    }

    Long size() {
        if (disk != null) {
            return disk.capacity();
        }
        return kind == Kind.FILE ? entry.size() : null;
    }

    FileTime modified() {
        return entry == null ? null : entry.modified();
    }

    String location() {
        if (path == null) {
            return "";
        }
        return path.getParent() == null ? path.toString() : path.getParent().toString();
    }
}

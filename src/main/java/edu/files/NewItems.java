package edu.files;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static java.nio.charset.StandardCharsets.UTF_8;

final class NewItems {
    enum Kind { FOLDER, SHORTCUT, EMPTY, TEMPLATE, DATA, COMMAND }

    record Type(Kind kind, String extension, String label, String defaultName, String value) { }

    record Shortcut(String target, String extension) {
        static Shortcut parse(Path directory, String input) {
            String value = input.strip();
            if (value.isEmpty()) { throw new IllegalArgumentException("请输入快捷方式指向的路径或网址"); }
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http:") || lower.startsWith("https:")) {
                URI uri = URI.create(value);
                if (uri.getHost() == null) { throw new IllegalArgumentException("请输入完整的网站地址"); }
                return new Shortcut(uri.toASCIIString(), ".url");
            }
            return new Shortcut(directory.resolve(Path.of(value)).normalize().toString(), ".lnk");
        }
    }

    record Created(LocalFiles.Entry entry, String extension) {
        Path path() { return entry.path(); }
    }

    @FunctionalInterface
    private interface CreateAt { void write(Path path) throws IOException; }

    private NewItems() { }

    static List<Type> load() {
        return catalog(WindowsPlaces.readScriptOutput("windows-new-items.ps1"), WindowsPlaces.WINDOWS);
    }

    static List<Type> catalog(List<String> registrations, boolean windows) {
        Type folder = new Type(Kind.FOLDER, "", "文件夹", "新建文件夹", "");
        Type shortcut = new Type(Kind.SHORTCUT, "", "快捷方式", "新建快捷方式", "");
        var files = new ArrayList<Type>();
        for (String line : registrations) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 5 || fields[2].isBlank()) { continue; }
            try {
                Kind kind = Kind.valueOf(fields[0]);
                String name = fields[3].isBlank() ? "新建 " + fields[2] : fields[3];
                String value = new String(Base64.getDecoder().decode(fields[4]), UTF_8);
                if (!fields[1].isEmpty() && name.toLowerCase(Locale.ROOT).endsWith(fields[1].toLowerCase(Locale.ROOT))) {
                    name = name.substring(0, name.length() - fields[1].length());
                }
                Type type = new Type(kind, fields[1], fields[2], name, value);
                switch (kind) {
                    case FOLDER -> folder = type;
                    case SHORTCUT -> shortcut = type;
                    default -> files.add(type);
                }
            } catch (IllegalArgumentException exception) {
                // An invalid registry entry does not affect the available types.
            }
        }
        files.sort(Comparator.comparing(Type::label, String.CASE_INSENSITIVE_ORDER));
        var menu = new ArrayList<Type>();
        menu.add(folder);
        if (windows) { menu.add(shortcut); }
        else { menu.add(new Type(Kind.EMPTY, ".txt", "文本文档", "新建文本文档", "")); }
        menu.addAll(files);
        return List.copyOf(menu);
    }

    static Created create(Path directory, Type type) throws IOException {
        String stem = type.defaultName();
        String suffix = type.extension();
        Path result = switch (type.kind()) {
            case FOLDER -> unique(directory, stem, "", Files::createDirectory);
            case EMPTY -> unique(directory, stem, suffix, Files::createFile);
            case TEMPLATE -> publish(directory, stem, suffix, path -> Files.copy(Path.of(type.value()), path));
            case DATA -> publish(directory, stem, suffix, path -> Files.write(path, Base64.getDecoder().decode(type.value())));
            default -> throw new IllegalArgumentException("该类型需要通过其创建向导启动");
        };
        return remember(result, suffix);
    }

    static Created createShortcut(Path directory, Type type, Shortcut shortcut) throws IOException {
        Path result = publish(directory, type.defaultName(), shortcut.extension(), path -> {
            if (shortcut.extension().equals(".url")) {
                Files.writeString(path, "[InternetShortcut]\r\nURL=" + shortcut.target() + "\r\n", UTF_8);
            } else {
                Path target = Path.of(shortcut.target());
                Files.readAttributes(target, BasicFileAttributes.class);
                WindowsPlaces.createShortcut(path, target);
            }
        });
        return remember(result, shortcut.extension());
    }

    private static Created remember(Path path, String extension) throws IOException {
        return new Created(LocalFiles.describe(path), extension);
    }

    private static Path publish(Path directory, String stem, String extension, CreateAt create) throws IOException {
        Path staging = Files.createTempDirectory(directory, ".file-manager-new-");
        Path prepared = staging.resolve("item" + extension);
        try {
            create.write(prepared);
            return unique(directory, stem, extension, destination -> Files.move(prepared, destination));
        } finally {
            Files.deleteIfExists(prepared);
            Files.deleteIfExists(staging);
        }
    }

    private static Path unique(Path directory, String stem, String extension, CreateAt create) throws IOException {
        for (int number = 1; ; number++) {
            String name = stem + (number == 1 ? "" : " (" + number + ")") + extension;
            Path target = LocalFiles.childNamed(directory, name);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { continue; }
            try {
                create.write(target);
                return target;
            } catch (FileAlreadyExistsException occupied) {
                // The create or move itself reserves the name, even when another window creates one concurrently.
            }
        }
    }
}

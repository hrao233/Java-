package edu.files;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

final class ClipboardFiles {
    static final int MAX_ITEMS = 4_096;
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int HEADER = 20;
    private static final int MAX_PATH_LENGTH = 32_767;

    private ClipboardFiles() { }

    static byte[] encode(List<String> paths) throws IOException {
        checkCount(paths.size());
        var names = new ArrayList<byte[]>(paths.size());
        int length = HEADER + Character.BYTES;
        for (String path : paths) {
            checkName(path);
            var encoded = StandardCharsets.UTF_16LE.newEncoder().encode(CharBuffer.wrap(path));
            byte[] name = new byte[encoded.remaining()];
            encoded.get(name);
            names.add(name);
            length += name.length + Character.BYTES;
            if (length > MAX_BYTES) { throw invalid(); }
        }
        var output = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0, HEADER);
        output.putInt(16, 1);
        output.position(HEADER);
        for (byte[] name : names) { output.put(name).putChar('\0'); }
        output.putChar('\0');
        return output.array();
    }

    static List<String> decode(byte[] bytes) throws IOException {
        if (bytes.length < HEADER + 4 || bytes.length > MAX_BYTES) { throw invalid(); }
        var input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int start = input.getInt();
        if (start < HEADER || start > bytes.length - 4 || (start & 1) != 0 || input.getInt(16) == 0) {
            throw invalid();
        }
        var paths = new ArrayList<String>();
        int nameStart = start;
        for (int offset = start; offset <= bytes.length - 2; offset += 2) {
            if (input.getChar(offset) != 0) { continue; }
            if (offset == nameStart) {
                checkCount(paths.size());
                return List.copyOf(paths);
            }
            if (paths.size() == MAX_ITEMS) { throw invalid(); }
            try {
                var name = StandardCharsets.UTF_16LE.newDecoder()
                        .decode(ByteBuffer.wrap(bytes, nameStart, offset - nameStart)).toString();
                checkName(name);
                paths.add(name);
            } catch (CharacterCodingException malformed) {
                throw invalid();
            }
            nameStart = offset + 2;
        }
        throw invalid();
    }

    static List<Path> paths(List<String> names) throws IOException {
        var paths = new LinkedHashSet<Path>();
        for (String name : names) {
            try {
                Path path = Path.of(conventionalPath(name));
                if (!path.isAbsolute() || path.getParent() == null || !path.equals(path.normalize())) { throw invalid(); }
                paths.add(path);
            } catch (InvalidPathException malformed) {
                throw invalid();
            }
        }
        checkCount(paths.size());
        for (Path path : paths) {
            for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) {
                if (paths.contains(parent)) { throw new IOException("剪贴板同时包含文件夹及其子项目，请在来源重新选择"); }
            }
        }
        return List.copyOf(paths);
    }

    static List<Path> dragPaths(List<String> names) throws IOException {
        if (names.isEmpty() || names.size() > MAX_ITEMS) { throw invalidDrag(); }
        int units = 1;
        for (String name : names) {
            try {
                checkName(name);
            } catch (IOException malformed) {
                throw invalidDrag();
            }
            units += name.length() + 1;
            if (HEADER + units * Character.BYTES > MAX_BYTES) { throw invalidDrag(); }
        }
        try {
            return paths(names);
        } catch (IOException malformed) {
            throw invalidDrag();
        }
    }

    private static IOException invalidDrag() { return new IOException("拖放的文件列表格式或数量不受支持"); }

    static String quotedPaths(List<Path> paths) {
        return String.join(System.lineSeparator(), paths.stream().map(path -> "\"" + path + "\"").toList());
    }

    private static String conventionalPath(String name) throws IOException {
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.startsWith("\\\\?\\UNC\\")) { return "\\\\" + name.substring(8); }
        if (upper.startsWith("\\\\?\\")) {
            String drive = name.substring(4);
            if (drive.matches("(?s)[a-zA-Z]:\\\\.*")) { return drive; }
            throw invalid();
        }
        if (upper.startsWith("\\\\.\\") || upper.startsWith("\\??\\")) { throw invalid(); }
        return name;
    }

    private static void checkName(String name) throws IOException {
        if (name.isEmpty() || name.length() >= MAX_PATH_LENGTH || name.indexOf('\0') >= 0
                || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) { throw invalid(); }
    }

    private static void checkCount(int count) throws IOException {
        if (count == 0 || count > MAX_ITEMS) { throw invalid(); }
    }

    private static IOException invalid() { return new IOException("剪贴板文件列表格式无效或超出大小限制"); }
}

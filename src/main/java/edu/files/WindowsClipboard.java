package edu.files;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class WindowsClipboard implements FileClipboard {
    static final int CF_HDROP = 15;
    static final int CF_UNICODETEXT = 13;
    private static final int GMEM_MOVEABLE = 2;
    private static final int DROPEFFECT_COPY = 1;
    private static final int DROPEFFECT_MOVE = 2;

    interface User extends StdCallLibrary {
        int GetClipboardSequenceNumber();
        int RegisterClipboardFormatW(WString name);
        boolean IsClipboardFormatAvailable(int format);
        boolean OpenClipboard(Pointer window);
        boolean CloseClipboard();
        boolean EmptyClipboard();
        Pointer GetClipboardData(int format);
        Pointer SetClipboardData(int format, Pointer block);
        Pointer CreateWindowExW(int extendedStyle, WString windowClass, WString title, int style, int x, int y,
                                int width, int height, Pointer parent, Pointer menu, Pointer instance, Pointer data);
        boolean DestroyWindow(Pointer window);
    }

    interface Kernel extends StdCallLibrary {
        Pointer GlobalAlloc(int flags, long bytes);
        Pointer GlobalLock(Pointer block);
        boolean GlobalUnlock(Pointer block);
        Pointer GlobalFree(Pointer block);
        long GlobalSize(Pointer block);
    }

    private record Formats(int copyToken, int cutToken, int effect, int fileDescriptor, int wideFileDescriptor,
                           int shellItems) { }

    private static final class NativeApi {
        static final User USER = Native.load("user32", User.class);
        static final Kernel KERNEL = Native.load("kernel32", Kernel.class);
    }

    private Formats registered;

    @Override
    public State probe() throws IOException {
        ensureFormats();
        for (int attempt = 0; attempt < 2; attempt++) {
            long first = version();
            Kind kind = kind();
            if (first == version()) { return new State(first, kind); }
        }
        throw new IOException("剪贴板正在变化，请重试");
    }

    @Override
    public Snapshot read() throws IOException {
        ensureFormats();
        byte[] files = null;
        boolean externalMove = false;
        UUID token = null;
        State state;
        try (var ignored = new Lock(null)) {
            Kind kind = kind();
            if (kind == Kind.CUT) { token = readToken(registered.cutToken()); }
            if (kind == Kind.FILES) {
                if (available(registered.copyToken())) { token = readToken(registered.copyToken()); }
                if (available(registered.effect())) {
                    byte[] bytes = readBytes(registered.effect(), 32);
                    if (bytes.length < Integer.BYTES) { throw new IOException("剪贴板的文件操作类型无效"); }
                    int effect = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    externalMove = isMove(effect);
                }
                if (!externalMove) { files = readBytes(CF_HDROP, ClipboardFiles.MAX_BYTES); }
            }
            // Delayed rendering can advance the version while GetClipboardData runs.
            state = new State(version(), kind);
        }
        List<Path> paths = files == null ? List.of() : ClipboardFiles.paths(ClipboardFiles.decode(files));
        return new Snapshot(state, token, paths, externalMove);
    }

    static boolean isMove(int effect) throws IOException {
        if ((effect & DROPEFFECT_MOVE) != 0) { return true; }
        if ((effect & DROPEFFECT_COPY) == 0) { throw new IOException("剪贴板未提供受支持的文件复制意图"); }
        return false;
    }

    @Override
    public State publish(Offer offer) throws IOException {
        ensureFormats();
        return write(prepare(offer), null, null).state();
    }

    @Override
    public Replacement replace(State expected, UUID token, Offer replacement) throws IOException {
        ensureFormats();
        return write(replacement == null ? Map.of() : prepare(replacement), expected, token);
    }

    @Override
    public State text(String value) throws IOException {
        ensureFormats();
        if (value.indexOf('\0') >= 0 || value.length() >= ClipboardFiles.MAX_BYTES / 2) {
            throw new IOException("复制的路径文本超出剪贴板大小限制");
        }
        return write(Map.of(CF_UNICODETEXT, (value + '\0').getBytes(StandardCharsets.UTF_16LE)), null, null).state();
    }

    private Map<Integer, byte[]> prepare(Offer offer) throws IOException {
        if (offer.cut()) { return Map.of(registered.cutToken(), tokenBytes(offer.token())); }
        var formats = new LinkedHashMap<Integer, byte[]>();
        formats.put(registered.copyToken(), tokenBytes(offer.token()));
        formats.put(registered.effect(), ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(DROPEFFECT_COPY).array());
        byte[] dropFiles = ClipboardFiles.encode(offer.paths().stream().map(Path::toString).toList());
        byte[] shellItems = WindowsShellItems.encode(offer.paths());
        if (shellItems != null) { formats.put(registered.shellItems(), shellItems); }
        formats.put(CF_HDROP, dropFiles);
        return formats;
    }

    private Replacement write(Map<Integer, byte[]> payload, State expected, UUID token) throws IOException {
        var blocks = new LinkedHashMap<Integer, Block>();
        try {
            for (var format : payload.entrySet()) { blocks.put(format.getKey(), new Block(format.getValue())); }
            try (var window = new OwnerWindow(); var ignored = new Lock(window.handle)) {
                if (expected != null && !matches(expected, token)) {
                    return new Replacement(false, new State(version(), kind()));
                }
                if (!NativeApi.USER.EmptyClipboard()) { throw error("无法清空剪贴板"); }
                for (var format : blocks.entrySet()) {
                    if (!format.getValue().publish(format.getKey())) {
                        IOException failure = error("无法发布完整的剪贴板内容");
                        if (!NativeApi.USER.EmptyClipboard()) { failure.addSuppressed(error("无法清除未完整发布的内容")); }
                        throw failure;
                    }
                }
                return new Replacement(true, new State(version(), kind()));
            }
        } finally {
            blocks.values().forEach(Block::close);
        }
    }

    private boolean matches(State expected, UUID token) throws IOException {
        if (version() != expected.version() || kind() != expected.kind()) { return false; }
        int format = expected.kind() == Kind.CUT ? registered.cutToken() : registered.copyToken();
        return available(format) && token.equals(readToken(format)) && version() == expected.version();
    }

    private void ensureFormats() throws IOException {
        if (registered != null) { return; }
        registered = new Formats(register("edu.files.FileCopy.Session.v1"), register("edu.files.LocalCut.Session.v1"),
                register("Preferred DropEffect"), register("FileGroupDescriptor"), register("FileGroupDescriptorW"),
                register("Shell IDList Array"));
    }

    private Kind kind() {
        if (available(registered.cutToken())) { return Kind.CUT; }
        if (available(CF_HDROP)) { return Kind.FILES; }
        if (available(registered.fileDescriptor()) || available(registered.wideFileDescriptor())
                || available(registered.shellItems())) { return Kind.UNSUPPORTED; }
        return Kind.EMPTY;
    }

    private static long version() { return Integer.toUnsignedLong(NativeApi.USER.GetClipboardSequenceNumber()); }
    private static boolean available(int format) { return NativeApi.USER.IsClipboardFormatAvailable(format); }

    private static int register(String name) throws IOException {
        int result = NativeApi.USER.RegisterClipboardFormatW(new WString(name));
        if (result == 0) { throw error("无法注册文件剪贴板格式"); }
        return result;
    }

    private static byte[] tokenBytes(UUID token) {
        return ByteBuffer.allocate(16).putLong(token.getMostSignificantBits()).putLong(token.getLeastSignificantBits()).array();
    }

    private static UUID readToken(int format) throws IOException {
        byte[] bytes = readBytes(format, 32);
        if (bytes.length < 16) { throw new IOException("剪贴板会话标记无效"); }
        var data = ByteBuffer.wrap(bytes);
        return new UUID(data.getLong(), data.getLong());
    }

    private static byte[] readBytes(int format, int max) throws IOException {
        Pointer block = NativeApi.USER.GetClipboardData(format);
        if (block == null) { throw error("无法从剪贴板读取文件格式"); }
        long length = NativeApi.KERNEL.GlobalSize(block);
        if (length <= 0 || length > max) { throw new IOException("剪贴板格式大小无效或超出限制"); }
        Pointer bytes = NativeApi.KERNEL.GlobalLock(block);
        if (bytes == null) { throw error("无法读取剪贴板内存"); }
        try {
            return bytes.getByteArray(0, (int) length);
        } finally {
            NativeApi.KERNEL.GlobalUnlock(block);
        }
    }

    private static IOException error(String message) {
        return new IOException(message + "（Windows 错误 " + Native.getLastError() + "）");
    }

    private static final class Lock implements AutoCloseable {
        Lock(Pointer window) throws IOException {
            for (int attempt = 0; attempt < 4; attempt++) {
                if (NativeApi.USER.OpenClipboard(window)) { return; }
                if (attempt == 3) { break; }
                try {
                    Thread.sleep(15L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("已取消等待剪贴板");
                }
            }
            throw error("剪贴板暂被其他应用占用，请稍后重试");
        }

        @Override public void close() throws IOException {
            if (!NativeApi.USER.CloseClipboard()) { throw error("无法释放剪贴板锁"); }
        }
    }

    private static final class OwnerWindow implements AutoCloseable {
        private final Pointer handle;

        OwnerWindow() throws IOException {
            handle = NativeApi.USER.CreateWindowExW(0, new WString("STATIC"), null, 0, 0, 0, 0, 0,
                    Pointer.createConstant(-3L), null, null, null); // HWND_MESSAGE is sign-extended on Windows x64.
            if (handle == null) { throw error("无法创建剪贴板所有者窗口"); }
        }

        @Override public void close() { NativeApi.USER.DestroyWindow(handle); }
    }

    private static final class Block implements AutoCloseable {
        private Pointer handle;

        Block(byte[] contents) throws IOException {
            handle = NativeApi.KERNEL.GlobalAlloc(GMEM_MOVEABLE, contents.length);
            if (handle == null) { throw error("无法分配剪贴板内存"); }
            Pointer memory = NativeApi.KERNEL.GlobalLock(handle);
            if (memory == null) {
                var failure = error("无法写入剪贴板内存");
                close();
                throw failure;
            }
            try {
                memory.write(0, contents, 0, contents.length);
            } finally {
                NativeApi.KERNEL.GlobalUnlock(handle);
            }
        }

        boolean publish(int format) {
            if (NativeApi.USER.SetClipboardData(format, handle) == null) { return false; }
            handle = null;
            return true;
        }

        @Override public void close() {
            if (handle != null) { NativeApi.KERNEL.GlobalFree(handle); }
            handle = null;
        }
    }
}

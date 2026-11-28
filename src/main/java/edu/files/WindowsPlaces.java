package edu.files;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class WindowsPlaces {
    static final boolean WINDOWS = File.separatorChar == '\\';
    static final Path USER_HOME = Path.of(System.getProperty("user.home"));
    private static final List<String> FOLDERS = List.of("Desktop", "Downloads", "Documents", "Pictures", "Music", "Videos");
    private static final List<String> LABELS = List.of("桌面", "下载", "文档", "图片", "音乐", "视频");

    record Recent(Path path, FileTime time) { }

    record Places(List<BrowserItem> fixed, List<BrowserItem> oneDrive, List<BrowserItem> computer,
                  List<BrowserItem> linux, List<Recent> recent, LocalFiles.Visibility visibility, boolean extensions) {
    }

    private WindowsPlaces() { }

    static Places load() {
        return catalog(readScriptOutput("windows-places.ps1"));
    }

    static Places catalog(List<String> records) {
        Map<String, Path> folders = new LinkedHashMap<>();
        FOLDERS.forEach(name -> folders.put(name, USER_HOME.resolve(name)));
        List<BrowserItem> oneDrive = new ArrayList<>();
        List<BrowserItem> linux = new ArrayList<>();
        List<Recent> recent = new ArrayList<>();
        Map<Path, String> driveNames = new LinkedHashMap<>();
        Map<Path, BrowserItem.Disk> disks = new LinkedHashMap<>();
        List<BrowserItem> shortcuts = new ArrayList<>();
        LocalFiles.Visibility visibility = new LocalFiles.Visibility(false, false);
        boolean extensions = true;

        for (String line : records) {
            String[] fields = line.split("\t", -1);
            int requiredFields = switch (fields[0]) {
                case "view", "shell" -> 4;
                case "folder", "recent" -> 3;
                case "disk" -> 9;
                default -> 2;
            };
            if (fields.length < requiredFields) { continue; }
            try {
                switch (fields[0]) {
                    case "view" -> {
                        visibility = new LocalFiles.Visibility(fields[1].equals("1"), fields[2].equals("1"));
                        extensions = !fields[3].equals("1");
                    }
                    case "folder" -> {
                        if (!fields[2].isBlank()) { folders.put(fields[1], Path.of(fields[2])); }
                    }
                    case "onedrive" -> oneDrive.add(BrowserItem.folder(Path.of(fields[1]), "OneDrive"));
                    case "linux" -> linux.add(BrowserItem.folder(Path.of("\\\\wsl.localhost\\" + fields[1] + "\\"), fields[1]));
                    case "recent" -> recent.add(new Recent(Path.of(fields[1]), FileTime.from(Instant.parse(fields[2]))));
                    case "shell" -> {
                        if (fields[2].matches("(?i)[a-z]:\\\\")) {
                            driveNames.put(Path.of(fields[2]), fields[1]);
                        } else if (!fields[2].isBlank()) {
                            shortcuts.add(fields[3].equals("1") ? BrowserItem.folder(Path.of(fields[2]), fields[1])
                                    : BrowserItem.shell(fields[1], fields[2]));
                        }
                    }
                    case "disk" -> disks.put(Path.of(fields[1] + "\\"), disk(fields));
                    default -> { }
                }
            } catch (IllegalArgumentException | DateTimeException malformed) {
                // Retain the defaults and the other valid records from this script output.
            }
        }
        List<BrowserItem> computer = new ArrayList<>();
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            BrowserItem.Disk disk = disks.containsKey(root) ? disks.get(root) : describeDisk(root);
            computer.add(BrowserItem.drive(root, driveNames.getOrDefault(root, root.toString()), disk));
        }
        computer.addAll(shortcuts);
        List<BrowserItem> fixed = new ArrayList<>();
        for (int i = 0; i < FOLDERS.size(); i++) {
            fixed.add(BrowserItem.folder(folders.get(FOLDERS.get(i)), LABELS.get(i)));
        }
        return new Places(List.copyOf(fixed), oneDrive.stream().distinct().toList(), List.copyOf(computer),
                List.copyOf(linux), List.copyOf(recent), visibility, extensions);
    }

    private static BrowserItem.Disk disk(String[] fields) {
        String type = switch (fields[3]) {
            case "2" -> "可移动磁盘";
            case "4" -> "网络驱动器";
            case "5" -> "光盘驱动器";
            case "6" -> "内存盘";
            default -> "本地磁盘";
        };
        return new BrowserItem.Disk(type, number(fields[4]), number(fields[5]), fields[6], fields[7], fields[8]);
    }

    private static BrowserItem.Disk describeDisk(Path root) {
        try {
            var store = Files.getFileStore(root);
            return new BrowserItem.Disk("驱动器", store.getTotalSpace(), store.getUsableSpace(), store.type(),
                    store.name(), "");
        } catch (IOException exception) {
            return new BrowserItem.Disk("驱动器", null, null, "", "", "");
        }
    }

    private static Long number(String value) {
        return value.isEmpty() ? null : Long.valueOf(value);
    }

    static List<Path> indexedModifiedItems() {
        List<Path> paths = new ArrayList<>();
        for (String line : readScriptOutput("windows-modified-items.ps1")) {
            try {
                Path path = Path.of(line).normalize();
                if (path.isAbsolute()) { paths.add(path); }
            } catch (InvalidPathException exception) {
                // Skip entries that do not resolve to a local filesystem path.
            }
        }
        return List.copyOf(paths);
    }

    static List<String> readScriptOutput(String resource) {
        if (!WINDOWS) {
            return List.of();
        }
        try (var script = WindowsPlaces.class.getResourceAsStream(resource)) {
            if (script == null) { return List.of(); }
            Process process = powershell(new String(script.readAllBytes(), StandardCharsets.UTF_8))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            CompletableFuture.delayedExecutor(12, TimeUnit.SECONDS).execute(() -> {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            });
            try (var output = process.inputReader(StandardCharsets.UTF_8)) {
                return output.lines().toList();
            }
        } catch (IOException | java.io.UncheckedIOException exception) {
            return List.of();
        }
    }

    static void openShortcut(String target) throws IOException {
        var command = powershell("""
                $shell = New-Object -ComObject Shell.Application
                $item = $shell.NameSpace(17).Items() | Where-Object Path -eq $env:JAVA_FILE_MANAGER_SHORTCUT | Select-Object -First 1
                if (!$item) { exit 1 }
                $item.InvokeVerb()
                """);
        command.environment().put("JAVA_FILE_MANAGER_SHORTCUT", target);
        waitFor(command, "无法打开系统快捷方式");
    }

    static void openNetworkDiscovery() throws IOException {
        new ProcessBuilder("explorer.exe", "shell:NetworkPlacesFolder").start();
    }

    static void showProperties(Path path) throws IOException {
        var command = powershell("""
                $ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName System.Windows.Forms
                Add-Type -ReferencedAssemblies 'System.Windows.Forms.dll' -TypeDefinition @'
                using System;
                using System.Collections.Generic;
                using System.Diagnostics;
                using System.Runtime.InteropServices;
                using System.Text;
                using System.Threading;
                using System.Windows.Forms;
                public static class ShellProperties {
                    private const uint OwnerWindow = 4;
                    private delegate bool WindowVisitor(IntPtr window, IntPtr data);

                    [DllImport("shell32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
                    private static extern bool SHObjectProperties(IntPtr owner, uint type, string name, string page);
                    [DllImport("user32.dll")]
                    private static extern bool EnumWindows(WindowVisitor callback, IntPtr data);
                    [DllImport("user32.dll")]
                    private static extern IntPtr GetWindow(IntPtr window, uint relationship);
                    [DllImport("user32.dll")]
                    private static extern uint GetWindowThreadProcessId(IntPtr window, out uint processId);
                    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
                    private static extern int GetClassName(IntPtr window, StringBuilder name, int capacity);
                    [DllImport("user32.dll")]
                    private static extern bool IsWindowVisible(IntPtr window);
                    [DllImport("user32.dll")]
                    private static extern bool IsWindow(IntPtr window);

                    public static bool Show(uint type, string target) {
                        using (var owner = new Form { ShowInTaskbar = false })
                        using (var process = Process.GetCurrentProcess()) {
                            IntPtr ownerHandle = owner.Handle;
                            if (!SHObjectProperties(ownerHandle, type, target, null)) return false;

                            var windows = new HashSet<IntPtr>();
                            var startup = Stopwatch.StartNew();
                            bool opened = false;
                            WindowVisitor capture = (window, data) => {
                                if (window != ownerHandle && IsWindowVisible(window)
                                        && IsPropertyWindow(window, ownerHandle, (uint) process.Id)) {
                                    windows.Add(window);
                                }
                                return true;
                            };
                            while (true) {
                                Application.DoEvents();
                                EnumWindows(capture, IntPtr.Zero);
                                windows.RemoveWhere(window => !IsWindow(window));
                                if (windows.Count > 0) {
                                    opened = true;
                                } else if (opened || startup.ElapsedMilliseconds >= 15000) {
                                    return true;
                                }
                                Thread.Sleep(80);
                            }
                        }
                    }

                    private static bool IsPropertyWindow(IntPtr window, IntPtr owner, uint process) {
                        if (GetWindow(window, OwnerWindow) == owner) return true;
                        uint windowProcess;
                        GetWindowThreadProcessId(window, out windowProcess);
                        if (windowProcess != process) return false;
                        var name = new StringBuilder(32);
                        GetClassName(window, name, name.Capacity);
                        return name.ToString() == "#32770";
                    }
                }
                '@
                $target = $env:JAVA_FILE_MANAGER_PROPERTIES
                $kind = 2
                if ($target -match '^[A-Za-z]:\\\\$') {
                    $kind = 4
                    $target = $target.Substring(0, 2)
                }
                if (![ShellProperties]::Show($kind, $target)) { exit 1 }
                """);
        command.command().add(1, "-STA");
        command.environment().put("JAVA_FILE_MANAGER_PROPERTIES", path.toAbsolutePath().toString());
        waitFor(command, "Windows 无法打开属性：" + path);
    }

    static boolean supportsDriveTools(BrowserItem item) {
        return WINDOWS && item.disk() != null && item.disk().networkPath().isBlank()
                && item.path().toString().matches("(?i)[a-z]:\\\\")
                && switch (item.disk().type()) {
                    case "本地磁盘", "可移动磁盘" -> true;
                    case "驱动器" -> item.disk().capacity() != null;
                    default -> false;
                };
    }

    static void cleanDrive(Path root) throws IOException {
        Path tool = Path.of(System.getenv("SystemRoot"), "System32", "cleanmgr.exe");
        new ProcessBuilder(tool.toString(), "/d", root.toString().substring(0, 1)).start();
    }

    static void optimizeDrives() throws IOException {
        Path tool = Path.of(System.getenv("SystemRoot"), "System32", "dfrgui.exe");
        Desktop.getDesktop().open(tool.toFile());
    }

    static void formatDrive(Path root) throws IOException {
        var command = powershell("""
                $ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName System.Windows.Forms
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public static class DriveFormatDialog {
                    [DllImport("shell32.dll")]
                    public static extern uint SHFormatDrive(IntPtr owner, uint drive, uint formatId, uint options);
                }
                '@
                $owner = New-Object System.Windows.Forms.Form
                try {
                    $drive = [int][char]$env:JAVA_FILE_MANAGER_DRIVE - [int][char]'A'
                    $result = [DriveFormatDialog]::SHFormatDrive($owner.Handle, $drive, 65535, 0)
                    if ($result -eq 4294967295 -or $result -eq 4294967293) { exit 1 }
                } finally {
                    $owner.Dispose()
                }
                """);
        command.environment().put("JAVA_FILE_MANAGER_DRIVE", root.toString().substring(0, 1).toUpperCase(Locale.ROOT));
        waitFor(command, "Windows 无法格式化此驱动器");
    }

    static void createShortcut(Path shortcut, Path target) throws IOException {
        var command = powershell("""
                $ErrorActionPreference = 'Stop'
                $shell = New-Object -ComObject WScript.Shell
                $link = $shell.CreateShortcut($env:JAVA_FILE_MANAGER_LINK)
                $link.TargetPath = $env:JAVA_FILE_MANAGER_LINK_TARGET
                $link.Save()
                """);
        command.environment().put("JAVA_FILE_MANAGER_LINK", shortcut.toString());
        command.environment().put("JAVA_FILE_MANAGER_LINK_TARGET", target.toString());
        waitFor(command, "无法创建 Windows 快捷方式");
    }

    static void launchNewCommand(Path directory, String registeredCommand) throws IOException {
        var command = powershell("""
                $ErrorActionPreference = 'Stop'
                $registered = [Environment]::ExpandEnvironmentVariables($env:JAVA_FILE_MANAGER_NEW_COMMAND)
                if ($registered -notmatch '^[ \t]*(?:"(?<app>[^"]+)"|(?<app>[^ \t]+))[ \t]*(?<parameters>.*)$') { exit 1 }
                $start = New-Object System.Diagnostics.ProcessStartInfo
                $start.FileName = $Matches['app']
                $start.Arguments = $Matches['parameters']
                $start.WorkingDirectory = $env:JAVA_FILE_MANAGER_NEW_DIRECTORY
                $start.UseShellExecute = $true
                [void][Diagnostics.Process]::Start($start)
                """);
        command.environment().put("JAVA_FILE_MANAGER_NEW_COMMAND", registeredCommand);
        command.environment().put("JAVA_FILE_MANAGER_NEW_DIRECTORY", directory.toString());
        waitFor(command, "无法打开该类型的创建向导");
    }

    static void mapNetworkDrive() throws IOException {
        waitFor(powershell("""
                Add-Type -TypeDefinition @'
                using System;
                using System.Runtime.InteropServices;
                public static class NetworkDialog {
                    [DllImport("mpr.dll")] public static extern int WNetConnectionDialog(IntPtr owner, int resourceType);
                }
                '@
                $result = [NetworkDialog]::WNetConnectionDialog([IntPtr]::Zero, 1)
                if ($result -ne 0 -and $result -ne -1 -and $result -ne 1223) { exit $result }
                """), "Windows 无法打开网络驱动器对话框");
    }

    static void disconnectNetworkDrive(Path drive) throws IOException {
        var command = powershell("""
                Add-Type -TypeDefinition @'
                using System.Runtime.InteropServices;
                public static class NetworkConnection {
                    [DllImport("mpr.dll", CharSet = CharSet.Unicode)]
                    public static extern int WNetCancelConnection2(string name, int flags, bool force);
                }
                '@
                exit ([NetworkConnection]::WNetCancelConnection2($env:JAVA_FILE_MANAGER_DRIVE, 1, $false))
                """);
        command.environment().put("JAVA_FILE_MANAGER_DRIVE", drive.toString().substring(0, 2));
        waitFor(command, "无法断开网络驱动器 " + drive);
    }

    static Path networkPath(String input) {
        Path path = Path.of(input.strip());
        if (!path.isAbsolute() || !path.toString().startsWith("\\\\")) {
            throw new InvalidPathException(input, "请输入网络共享路径，例如 \\\\服务器\\共享文件夹");
        }
        return path;
    }

    static ProcessBuilder powershell(String script) {
        String root = System.getenv("SystemRoot");
        String executable = root == null ? "powershell.exe"
                : Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString();
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return new ProcessBuilder(executable, "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
    }

    private static void waitFor(ProcessBuilder command, String failure) throws IOException {
        Process process = command.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            int result = process.waitFor();
            if (result != 0) { throw new IOException(failure + "（Windows 错误 " + result + "）"); }
        } catch (InterruptedException exception) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new IOException(failure, exception);
        }
    }
}

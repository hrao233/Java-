using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

public static class IsolatedClipboard
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct Startup
    {
        public int size;
        public string reserved, desktop, title;
        public int x, y, width, height, columns, rows, fill, flags;
        public short show, reservedLength;
        public IntPtr reservedBytes, input, output, error;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Child { public IntPtr process, thread; public uint pid, tid; }

    [DllImport("user32")]
    private static extern IntPtr GetProcessWindowStation();
    [DllImport("user32", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateWindowStation(string name, int flags, int access, IntPtr security);
    [DllImport("user32", SetLastError = true)]
    private static extern bool SetProcessWindowStation(IntPtr station);
    [DllImport("user32", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool GetUserObjectInformation(IntPtr handle, int kind, StringBuilder name, int bytes, out int used);
    [DllImport("user32", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateDesktop(string name, string device, IntPtr mode, int flags, int access, IntPtr security);
    [DllImport("user32")]
    private static extern bool CloseDesktop(IntPtr desktop);
    [DllImport("user32")]
    private static extern bool CloseWindowStation(IntPtr station);
    [DllImport("kernel32", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateProcess(string app, StringBuilder arguments, IntPtr processSecurity,
        IntPtr threadSecurity, bool inherit, uint flags, IntPtr env, string directory, ref Startup startup, out Child child);
    [DllImport("kernel32")]
    private static extern uint WaitForSingleObject(IntPtr handle, uint timeout);
    [DllImport("kernel32")]
    private static extern bool GetExitCodeProcess(IntPtr handle, out uint code);
    [DllImport("kernel32")]
    private static extern bool TerminateProcess(IntPtr handle, uint code);
    [DllImport("kernel32")]
    private static extern bool CloseHandle(IntPtr handle);

    [DllImport("user32", SetLastError = true)]
    private static extern bool OpenClipboard(IntPtr window);
    [DllImport("user32")]
    private static extern bool CloseClipboard();
    [DllImport("user32")]
    private static extern bool EmptyClipboard();
    [DllImport("user32", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateWindowEx(int exStyle, string className, string title, int style, int x, int y,
        int width, int height, IntPtr parent, IntPtr menu, IntPtr instance, IntPtr data);
    [DllImport("user32")]
    private static extern bool DestroyWindow(IntPtr window);
    [DllImport("user32")]
    private static extern int CountClipboardFormats();
    [DllImport("user32")]
    private static extern int EnumClipboardFormats(int format);
    [DllImport("user32", CharSet = CharSet.Unicode)]
    private static extern int GetClipboardFormatName(int format, StringBuilder name, int length);
    [DllImport("user32", CharSet = CharSet.Unicode)]
    private static extern int RegisterClipboardFormat(string name);
    [DllImport("user32")]
    private static extern IntPtr GetClipboardData(int format);
    [DllImport("kernel32")]
    private static extern IntPtr GlobalLock(IntPtr memory);
    [DllImport("kernel32")]
    private static extern bool GlobalUnlock(IntPtr memory);
    [DllImport("kernel32")]
    private static extern UIntPtr GlobalSize(IntPtr memory);
    [DllImport("shell32", CharSet = CharSet.Unicode)]
    private static extern uint DragQueryFile(IntPtr drop, uint item, StringBuilder path, int size);
    [DllImport("shell32")]
    private static extern IntPtr ILCombine(IntPtr parent, IntPtr child);
    [DllImport("shell32")]
    private static extern void ILFree(IntPtr item);
    [DllImport("shell32", CharSet = CharSet.Unicode)]
    private static extern bool SHGetPathFromIDListEx(IntPtr item, StringBuilder name, int capacity, uint flags);

    public static uint Run(string app, string arguments, string directory)
    {
        var original = GetProcessWindowStation();
        // The anonymous station belongs to this logon session and has a separate clipboard.
        var station = CreateWindowStation(null, 0, 0x37f, IntPtr.Zero);
        if (station == IntPtr.Zero) throw Error("CreateWindowStation");
        var desktop = IntPtr.Zero;
        try
        {
            var name = Name(station);
            if (IsInteractive(name)) throw new InvalidOperationException("Expected a noninteractive window station");
            string desktopName = "Phase26-" + Guid.NewGuid().ToString("N");
            if (!SetProcessWindowStation(station)) throw Error("SetProcessWindowStation");
            try
            {
                desktop = CreateDesktop(desktopName, null, IntPtr.Zero, 0, 0x1ff, IntPtr.Zero);
                if (desktop == IntPtr.Zero) throw Error("CreateDesktop");
            }
            finally
            {
                if (!SetProcessWindowStation(original)) throw Error("RestoreProcessWindowStation");
            }

            var startup = new Startup();
            startup.size = Marshal.SizeOf(typeof(Startup));
            startup.desktop = name + "\\" + desktopName;
            var command = new StringBuilder("\"" + app + "\" " + arguments + " -Station \"" + name + "\"");
            Child child;
            if (!CreateProcess(app, command, IntPtr.Zero, IntPtr.Zero, false, 0x08000000,
                    IntPtr.Zero, directory, ref startup, out child)) throw Error("CreateProcess");
            try
            {
                if (WaitForSingleObject(child.process, 240000) != 0)
                {
                    TerminateProcess(child.process, 124);
                    throw new TimeoutException("Isolated clipboard suite timed out");
                }
                uint status;
                if (!GetExitCodeProcess(child.process, out status)) throw Error("GetExitCodeProcess");
                return status;
            }
            finally
            {
                CloseHandle(child.thread);
                CloseHandle(child.process);
            }
        }
        finally
        {
            if (desktop != IntPtr.Zero) CloseDesktop(desktop);
            CloseWindowStation(station);
        }
    }

    public static int RequireEmpty(string station)
    {
        RequireStation(station);
        OpenForTest();
        try { return CountClipboardFormats(); }
        finally { CloseClipboard(); }
    }

    public static IntPtr HoldTestLock(string station)
    {
        RequireStation(station);
        var window = CreateWindowEx(0, "STATIC", null, 0, 0, 0, 0, 0, new IntPtr(-3), IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
        if (window == IntPtr.Zero) throw Error("CreateWindowEx");
        if (OpenClipboard(window)) return window;
        DestroyWindow(window);
        throw Error("OpenClipboard");
    }

    public static void ReleaseTestLock(string station, IntPtr window)
    {
        RequireStation(station);
        try { if (!CloseClipboard()) throw Error("CloseClipboard"); }
        finally { DestroyWindow(window); }
    }

    public static void ClearFixture(string station, string root)
    {
        RequireStation(station);
        OpenForTest();
        try
        {
            if (CountClipboardFormats() == 0) return;
            var prefix = Path.GetFullPath(root).TrimEnd('\\') + "\\";
            var fileList = GetClipboardData(15);
            if (fileList != IntPtr.Zero)
            {
                uint count = DragQueryFile(fileList, uint.MaxValue, null, 0);
                if (count == 0 || count > 4096) throw new InvalidOperationException("Unexpected file count");
                for (uint i = 0; i < count; i++)
                {
                    var path = new StringBuilder(32768);
                    DragQueryFile(fileList, i, path, path.Capacity);
                    RequireFixturePath(path, prefix);
                }
            }
            else
            {
                // The Shell may keep its ID list after dropping a delayed CF_HDROP registration.
                VerifyShellItems(prefix);
            }
            if (!EmptyClipboard()) throw Error("EmptyClipboard");
        }
        finally { CloseClipboard(); }
    }

    private static void VerifyShellItems(string prefix)
    {
        var block = GetClipboardData(RegisterClipboardFormat("Shell IDList Array"));
        if (block == IntPtr.Zero) throw new InvalidOperationException("No fixture file list to clear");
        ulong size = GlobalSize(block).ToUInt64();
        if (size < 14 || size > 4 * 1024 * 1024) throw new InvalidOperationException("Unexpected Shell list size");
        var data = GlobalLock(block);
        if (data == IntPtr.Zero) throw Error("GlobalLock");
        try
        {
            int count = Marshal.ReadInt32(data);
            if (count < 1 || count > 4096 || (ulong)((count + 2) * 4) > size)
                throw new InvalidOperationException("Unexpected Shell item count");
            var parent = ShellId(data, Marshal.ReadInt32(data, 4), (int)size);
            for (int i = 0; i < count; i++)
            {
                var child = ShellId(data, Marshal.ReadInt32(data, (i + 2) * 4), (int)size);
                var item = ILCombine(parent, child);
                if (item == IntPtr.Zero) throw Error("ILCombine");
                try
                {
                    var name = new StringBuilder(32768);
                    if (!SHGetPathFromIDListEx(item, name, name.Capacity, 0))
                        throw new InvalidOperationException("Shell item has no fixture path");
                    RequireFixturePath(name, prefix);
                }
                finally { ILFree(item); }
            }
        }
        finally { GlobalUnlock(block); }
    }

    private static IntPtr ShellId(IntPtr data, int offset, int size)
    {
        for (int at = offset; at >= 0 && at <= size - 2;)
        {
            int length = (ushort)Marshal.ReadInt16(data, at);
            if (length == 0) return IntPtr.Add(data, offset);
            if (length < 2 || length > size - at) break;
            at += length;
        }
        throw new InvalidOperationException("Invalid Shell item offset");
    }

    private static void RequireFixturePath(StringBuilder path, string prefix)
    {
        if (!Path.GetFullPath(path.ToString()).StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Clipboard contains items outside the fixture");
    }

    public static string Formats(string station)
    {
        RequireStation(station);
        OpenForTest();
        try
        {
            var output = new StringBuilder();
            for (int format = EnumClipboardFormats(0); format != 0; format = EnumClipboardFormats(format))
            {
                var name = new StringBuilder(160);
                GetClipboardFormatName(format, name, name.Capacity);
                if (output.Length != 0) output.Append(", ");
                output.Append(format == 15 ? "CF_HDROP" : name.Length == 0 ? format.ToString() : name.ToString());
                if (name.ToString().EndsWith("DropEffect", StringComparison.Ordinal) || name.ToString() == "Paste Succeeded")
                {
                    var block = GetClipboardData(format);
                    if (block == IntPtr.Zero || GlobalSize(block).ToUInt64() < 4) continue;
                    var memory = GlobalLock(block);
                    if (memory == IntPtr.Zero) continue;
                    try { output.Append("=").Append(Marshal.ReadInt32(memory)); }
                    finally { GlobalUnlock(block); }
                }
            }
            return output.ToString();
        }
        finally { CloseClipboard(); }
    }

    private static void RequireStation(string expected)
    {
        var actual = Name(GetProcessWindowStation());
        if (IsInteractive(actual) || actual != expected) throw new InvalidOperationException("Unexpected window station");
    }

    private static void OpenForTest()
    {
        for (int attempt = 0; attempt < 20; attempt++)
        {
            if (OpenClipboard(IntPtr.Zero)) return;
            System.Threading.Thread.Sleep(25);
        }
        throw Error("OpenClipboard");
    }

    private static bool IsInteractive(string name) { return name.Equals("WinSta0", StringComparison.OrdinalIgnoreCase); }

    private static string Name(IntPtr station)
    {
        var value = new StringBuilder(160);
        int used;
        if (!GetUserObjectInformation(station, 2, value, value.Capacity * 2, out used)) throw Error("GetUserObjectInformation");
        return value.ToString();
    }

    private static Win32Exception Error(string call) { return new Win32Exception(Marshal.GetLastWin32Error(), call); }
}

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

Add-Type -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;

public static class ExplorerNamespaceIcon {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ShellFileInfo {
        public IntPtr Icon;
        public int IconIndex;
        public uint Attributes;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string DisplayName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 80)] public string TypeName;
    }

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern int SHParseDisplayName(string name, IntPtr context, out IntPtr pidl,
                                                  uint attributes, out uint actualAttributes);

    [DllImport("shell32.dll", EntryPoint = "SHGetFileInfoW", CharSet = CharSet.Unicode)]
    private static extern UIntPtr SHGetFileInfo(IntPtr pidl, uint attributes, out ShellFileInfo info,
                                                uint infoSize, uint flags);

    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr icon);

    public static string Read(string shellName) {
        const uint SHGFI_PIDL = 0x0008;
        const uint SHGFI_ICON = 0x0100;
        IntPtr pidl;
        uint attributes;
        if (SHParseDisplayName(shellName, IntPtr.Zero, out pidl, 0, out attributes) < 0 || pidl == IntPtr.Zero) {
            return null;
        }

        try {
            ShellFileInfo info;
            var found = SHGetFileInfo(pidl, 0, out info, (uint)Marshal.SizeOf(typeof(ShellFileInfo)), SHGFI_ICON | SHGFI_PIDL);
            if (found == UIntPtr.Zero || info.Icon == IntPtr.Zero) { return null; }

            try {
                using (var icon = Icon.FromHandle(info.Icon))
                using (var bitmap = icon.ToBitmap())
                using (var output = new MemoryStream()) {
                    bitmap.Save(output, ImageFormat.Png);
                    return Convert.ToBase64String(output.ToArray());
                }
            } finally {
                DestroyIcon(info.Icon);
            }
        } finally {
            Marshal.FreeCoTaskMem(pidl);
        }
    }
}
'@ -ReferencedAssemblies 'System.Drawing.dll'

$locations = @{
    HOME = '::{F874310E-B6B7-47DC-BC84-B9E6B38F5903}'
    GALLERY = '::{E88865EA-0E1C-4E20-9AA6-EDCD0212C87C}'
    COMPUTER = '::{20D04FE0-3AEA-1069-A2D8-08002B30309D}'
    NETWORK = '::{F02C1A0D-BE21-4350-88B0-7367FC96EF3C}'
    LINUX = '::{B2B4A4D1-2754-4140-A2EB-9A76D9D7CDC6}'
}

foreach ($location in $locations.GetEnumerator()) {
    $icon = [ExplorerNamespaceIcon]::Read($location.Value)
    if ($icon) { "{0}`t{1}" -f $location.Key, $icon }
}

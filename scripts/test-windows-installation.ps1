#Requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$Setup,
    [Parameter(Mandatory)] [string]$Upgrade,
    [Parameter(Mandatory)] [string]$FixtureDirectory,
    [switch]$UseMsiPayload,
    [switch]$SkipShortcutLaunch
)

$ErrorActionPreference = 'Stop'
$Setup = (Resolve-Path -LiteralPath $Setup).Path
$Upgrade = (Resolve-Path -LiteralPath $Upgrade).Path
$root = [IO.Path]::GetFullPath($FixtureDirectory)
if (Test-Path -LiteralPath $root) { throw 'Choose a fresh fixture directory in a test Windows account' }
New-Item -ItemType Directory -Path $root | Out-Null
$msiexec = "$env:SystemRoot\System32\msiexec.exe"
Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;

public static class JPackageMsiResource {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr LoadLibraryExW(string file, IntPtr reserved, uint flags);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr FindResourceW(IntPtr image, string name, IntPtr type);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern int SizeofResource(IntPtr image, IntPtr resource);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern IntPtr LoadResource(IntPtr image, IntPtr resource);
    [DllImport("kernel32.dll")]
    static extern IntPtr LockResource(IntPtr resource);
    [DllImport("kernel32.dll")]
    static extern bool FreeLibrary(IntPtr image);
    [DllImport("msi.dll", CharSet = CharSet.Unicode)]
    static extern uint MsiEnumProductsEx(string productCode, string userSid, uint context,
        uint index, StringBuilder installedCode, out uint installedContext, StringBuilder installedSid,
        ref uint sidLength);

    public static bool IsInstalledForUser(string productCode, string userSid) {
        var sid = new StringBuilder(256);
        uint sidLength = (uint)sid.Capacity;
        uint result = MsiEnumProductsEx(productCode, userSid, 3, 0, new StringBuilder(39),
            out uint context, sid, ref sidLength);
        if (result == 259) return false;
        if (result != 0) throw new Win32Exception((int)result);
        return (context == 1 || context == 2) && sid.ToString() == userSid;
    }

    public static byte[] Read(string path) {
        IntPtr image = LoadLibraryExW(path, IntPtr.Zero, 2);
        if (image == IntPtr.Zero) throw new Win32Exception();
        try {
            IntPtr resource = FindResourceW(image, "msi", new IntPtr(10));
            if (resource == IntPtr.Zero) throw new Win32Exception();
            IntPtr data = LockResource(LoadResource(image, resource));
            int size = SizeofResource(image, resource);
            if (data == IntPtr.Zero || size <= 0) throw new Win32Exception();
            byte[] bytes = new byte[size];
            Marshal.Copy(data, bytes, 0, size);
            return bytes;
        } finally { FreeLibrary(image); }
    }
}

public static class WindowsShortcut {
    [ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IShellLinkW {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder path, int maxChars,
            IntPtr findData, uint flags);
    }

    public static string ReadTarget(string shortcut) {
        var shellLink = Type.GetTypeFromCLSID(new Guid("00021401-0000-0000-C000-000000000046"), true);
        var link = Activator.CreateInstance(shellLink);
        try {
            ((IPersistFile)link).Load(shortcut, 0);
            var path = new StringBuilder(32768);
            ((IShellLinkW)link).GetPath(path, path.Capacity, IntPtr.Zero, 0);
            return path.ToString();
        } finally { Marshal.FinalReleaseComObject(link); }
    }
}
'@
if ($UseMsiPayload) {
    # Read the exact MSI embedded by jpackage without executing its GUI bootstrap.
    $setupPayload = Join-Path $root 'setup.msi'
    $upgradePayload = Join-Path $root 'upgrade.msi'
    [IO.File]::WriteAllBytes($setupPayload, [JPackageMsiResource]::Read($Setup))
    [IO.File]::WriteAllBytes($upgradePayload, [JPackageMsiResource]::Read($Upgrade))
    $Setup = $setupPayload
    $Upgrade = $upgradePayload
    Write-Host 'Testing the exact embedded MSI payloads. The EXE bootstrap is a desktop acceptance item.'
}
# JDK 21's native launcher requires an executable path representable in the active Windows code page.
$install = Join-Path $root 'Install Directory'
$exe = Join-Path $install 'JavaFXFileManager.exe'
$fixture = Join-Path $root '用户 文件.txt'
Set-Content -LiteralPath $fixture -Value 'installer lifecycle fixture'
$hash = (Get-FileHash -LiteralPath $fixture).Hash

$uninstallPaths = @('HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall',
    'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall')
$userSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$profile = 'explorer-install-test-' + ([guid]::NewGuid().ToString('N'))
$preferences = "HKCU:\Software\JavaSoft\Prefs\edu\files\$profile"
# A fresh runner may not have created these known folders before the first install.
$startMenu = [Environment]::GetFolderPath('Programs', 'DoNotVerify')
$desktop = [Environment]::GetFolderPath('DesktopDirectory', 'DoNotVerify')

function InstalledProducts {
    foreach ($path in $uninstallPaths) {
        Get-ItemProperty -Path "$path\*" -ErrorAction SilentlyContinue |
            Where-Object DisplayName -eq 'JavaFXFileManager'
    }
}

function Invoke-Installer {
    param([string]$Executable, [string[]]$Arguments, [int[]]$Expected = @(0))
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -PassThru -WindowStyle Hidden
    if (-not $process.WaitForExit(120000)) {
        throw "$Executable did not finish within two minutes. See the MSI log under $root"
    }
    if ($process.ExitCode -notin $Expected) {
        throw "$Executable returned $($process.ExitCode). See the MSI log under $root"
    }
    $process.ExitCode
}

function Install-TestProduct {
    param([string]$Path, [string]$LogName, [int[]]$Expected = @(0))
    $log = Join-Path $root $LogName
    Write-Host "Running $LogName"
    try {
        $arguments = @('/qn', '/norestart', '/l*v', ('"' + $log + '"'),
            ('INSTALLDIR="' + $install + '"'), 'JP_INSTALL_STARTMENU_SHORTCUT=1', 'JP_INSTALL_DESKTOP_SHORTCUT=1')
        if ($UseMsiPayload) {
            Invoke-Installer $msiexec (@('/i', ('"' + $Path + '"')) + $arguments) $Expected
        } else {
            Invoke-Installer $Path $arguments $Expected
        }
    } catch {
        if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 30 | Write-Host }
        throw
    }
}

function Assert-Install {
    param([string]$Version)
    $records = @(InstalledProducts)
    if ($records.Count -ne 1 -or $records[0].DisplayVersion -ne $Version -or
        $records[0].InstallLocation.TrimEnd('\') -ne $install -or -not (Test-Path -LiteralPath $exe)) {
        throw "Expected one per-user installation of $Version in $install"
    }
    if (-not [JPackageMsiResource]::IsInstalledForUser($records[0].PSChildName, $userSid)) {
        throw 'Windows Installer did not register this product for the test account'
    }
    $records[0]
}

function Find-AppShortcuts {
    param([string]$Folder, [switch]$Recurse)
    if ($Folder -and (Test-Path -LiteralPath $Folder -PathType Container)) {
        Get-ChildItem -LiteralPath $Folder -Filter 'JavaFXFileManager.lnk' -File -Force -Recurse:$Recurse |
            Where-Object {
                $target = [WindowsShortcut]::ReadTarget($_.FullName)
                Write-Host "Shortcut $($_.FullName) -> $target"
                $target -eq $exe
            }
    }
}

function Test-ShortcutLaunch {
    param([string]$Shortcut)
    $pathBefore = $env:Path
    $javaBefore = $env:JAVA_HOME
    $optionsBefore = $env:JAVA_TOOL_OPTIONS
    try {
        $env:Path = "$env:SystemRoot\System32;$env:SystemRoot"
        $env:JAVA_HOME = $null
        $env:JAVA_TOOL_OPTIONS = "-Dedu.files.preferencesNode=$profile"
        Write-Host "Launching $Shortcut"
        Start-Process -FilePath $Shortcut -WorkingDirectory $root
        $deadline = [DateTime]::UtcNow.AddSeconds(35)
        do {
            $window = Get-Process -Name JavaFXFileManager -ErrorAction SilentlyContinue |
                Where-Object { $_.Path -eq $exe -and $_.MainWindowTitle -eq '本地文件管理器' } |
                Select-Object -First 1
            if ($window) {
                $jvm = $window.Modules | Where-Object ModuleName -eq 'jvm.dll'
                if (-not $jvm -or -not $jvm.FileName.StartsWith((Join-Path $install 'runtime\'), [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'The installed application loaded a JVM from outside the package'
                }
                if (-not $window.CloseMainWindow() -or -not $window.WaitForExit(20000)) {
                    throw 'The installed application did not close normally'
                }
                Write-Host 'The application opened and closed with the bundled JVM.'
                return
            }
            Start-Sleep -Milliseconds 150
        } until ([DateTime]::UtcNow -ge $deadline)
        throw "The application did not show a JavaFX window after opening $Shortcut"
    } finally {
        $env:Path = $pathBefore
        $env:JAVA_HOME = $javaBefore
        $env:JAVA_TOOL_OPTIONS = $optionsBefore
    }
}

function Test-InstalledStartup {
    param([string[]]$Shortcuts)
    if ($SkipShortcutLaunch) {
        # The hosted test has already verified the link targets. Smoke-test the installed runtime here.
        $java = Join-Path $install 'runtime\bin\java.exe'
        & $java --version | Write-Host
        if ($LASTEXITCODE -ne 0) { throw "The installed Java runtime returned $LASTEXITCODE" }
        return
    }
    foreach ($shortcut in $Shortcuts) {
        Test-ShortcutLaunch $shortcut
    }
}

function Uninstall-TestProduct {
    $product = @(InstalledProducts)
    if ($product.Count -ne 1 -or $product[0].InstallLocation.TrimEnd('\') -ne $install -or
        $product[0].PSChildName -notmatch '^\{[0-9a-fA-F-]{36}\}$') {
        throw 'Cannot identify the isolated MSI product to uninstall'
    }
    Invoke-Installer $msiexec @('/x', $product[0].PSChildName, '/qn', '/norestart',
        '/l*v', ('"' + (Join-Path $root 'uninstall.log') + '"')) | Out-Null
    if (@(InstalledProducts).Count -ne 0 -or (Test-Path -LiteralPath $exe)) { throw 'The MSI product survived uninstall' }
    if ((Get-FileHash -LiteralPath $fixture).Hash -ne $hash) { throw 'Uninstall changed the external file fixture' }
    if ((Get-ItemPropertyValue -LiteralPath $preferences -Name 'installation_fixture') -ne 'keep') {
        throw 'Uninstall changed the isolated Java Preferences'
    }
}

if (@(InstalledProducts).Count -ne 0) { throw 'Use a test account with no installed copy of this product' }
New-Item -Path $preferences -Force | Out-Null
New-ItemProperty -LiteralPath $preferences -Name 'installation_fixture' -Value 'keep' -PropertyType String | Out-Null
try {
    Install-TestProduct $Setup 'first-install.log' | Out-Null
    $initial = Assert-Install '1.0.0'
    $startLink = @(Find-AppShortcuts $startMenu -Recurse)
    $desktopLink = @(Find-AppShortcuts $desktop)
    if ($startLink.Count -ne 1 -or $desktopLink.Count -ne 1) {
        throw "Expected one application shortcut in '$startMenu' (found $($startLink.Count)) and '$desktop' (found $($desktopLink.Count))"
    }
    Test-InstalledStartup @($startLink[0].FullName, $desktopLink[0].FullName)

    $repeat = Install-TestProduct $Setup 'repeat-install.log' @(0, 1638)
    $sameVersion = Assert-Install '1.0.0'
    if ($sameVersion.PSChildName -ne $initial.PSChildName) { throw 'A second product replaced the first during same-version install' }

    Install-TestProduct $Upgrade 'upgrade.log' | Out-Null
    $updated = Assert-Install '1.0.1'
    if ($initial.PSChildName -eq $updated.PSChildName) { throw 'Expected a major upgrade with a new MSI product code' }
    $updatedStart = @(Find-AppShortcuts $startMenu -Recurse)
    if ($updatedStart.Count -ne 1) { throw 'Expected one Start menu entry after the upgrade' }
    Test-InstalledStartup @($updatedStart[0].FullName)
    Uninstall-TestProduct
    if ((Test-Path -LiteralPath $startLink[0].FullName) -or (Test-Path -LiteralPath $desktopLink[0].FullName)) {
        throw 'One or more shortcuts survived uninstall'
    }

    Install-TestProduct $Setup 'reinstall.log' | Out-Null
    Assert-Install '1.0.0' | Out-Null
    Uninstall-TestProduct
    "Passed: install, shortcut targets, repeat ($repeat), upgrade, uninstall and reinstall"
    if ($SkipShortcutLaunch) {
        'The bundled JVM ran. Desktop shortcut launch and close require desktop acceptance.'
    } else {
        'Start menu and desktop launch and normal close passed.'
    }
    'The external fixture hash and Java Preferences marker survived the lifecycle'
} catch {
    "Installer path exists: $(Test-Path -LiteralPath $exe)" | Write-Host
    foreach ($uninstall in $uninstallPaths) {
        $records = Get-ItemProperty -Path "$uninstall\*" -ErrorAction SilentlyContinue |
            Where-Object DisplayName -like '*JavaFXFileManager*'
        $records | Select-Object DisplayName, DisplayVersion, InstallLocation, PSPath | Format-List | Out-String | Write-Host
    }
    Get-ChildItem -LiteralPath $root -Filter '*.log' -File | ForEach-Object {
        Get-Content -LiteralPath $_.FullName |
            Select-String -Pattern 'Property\([CS]\): (ALLUSERS|MSIINSTALLPERUSER|INSTALLDIR|ARPINSTALLLOCATION|ProductName|ProductVersion|DesktopFolder|ProgramMenuFolder|JP_INSTALL_\w+_SHORTCUT) =|Executing op: ShortcutCreate|Installation completed|Installation failed' |
            ForEach-Object Line | Write-Host
    }
    throw
} finally {
    $records = @(InstalledProducts)
    if ($records.Count -eq 1 -and $records[0].InstallLocation.TrimEnd('\') -eq $install) {
        Uninstall-TestProduct
    }
}

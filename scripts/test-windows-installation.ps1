#Requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$Setup,
    [Parameter(Mandatory)] [string]$Upgrade,
    [Parameter(Mandatory)] [string]$FixtureDirectory
)

$ErrorActionPreference = 'Stop'
$Setup = (Resolve-Path -LiteralPath $Setup).Path
$Upgrade = (Resolve-Path -LiteralPath $Upgrade).Path
$root = [IO.Path]::GetFullPath($FixtureDirectory)
if (Test-Path -LiteralPath $root) { throw 'Choose a fresh fixture directory in a test Windows account' }
New-Item -ItemType Directory -Path $root | Out-Null
$install = Join-Path $root '安装 目录'
$exe = Join-Path $install 'JavaFXFileManager.exe'
$fixture = Join-Path $root '用户 文件.txt'
Set-Content -LiteralPath $fixture -Value 'installer lifecycle fixture'
$hash = (Get-FileHash -LiteralPath $fixture).Hash

$uninstallPath = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall'
$profile = 'explorer-install-test-' + ([guid]::NewGuid().ToString('N'))
$preferences = "HKCU:\Software\JavaSoft\Prefs\edu\files\$profile"
$startMenu = [Environment]::GetFolderPath('Programs')
$desktop = [Environment]::GetFolderPath('DesktopDirectory')
$shell = New-Object -ComObject WScript.Shell

function InstalledProducts {
    @(Get-ItemProperty -Path "$uninstallPath\*" -ErrorAction SilentlyContinue |
        Where-Object DisplayName -eq '本地文件管理器')
}

function Invoke-Installer {
    param([string]$Executable, [string[]]$Arguments, [int[]]$Expected = @(0))
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -Wait -PassThru -WindowStyle Hidden
    if ($process.ExitCode -notin $Expected) {
        throw "$Executable returned $($process.ExitCode). See the MSI log under $root"
    }
    $process.ExitCode
}

function Install-TestProduct {
    param([string]$Path, [string]$LogName, [int[]]$Expected = @(0))
    $log = Join-Path $root $LogName
    Invoke-Installer $Path @('/qn', '/norestart', '/l*v', ('"' + $log + '"'),
        ('INSTALLDIR="' + $install + '"'), 'JP_INSTALL_STARTMENU_SHORTCUT=1', 'JP_INSTALL_DESKTOP_SHORTCUT=1') $Expected
}

function Assert-Install {
    param([string]$Version)
    $records = @(InstalledProducts)
    if ($records.Count -ne 1 -or $records[0].DisplayVersion -ne $Version -or
        $records[0].InstallLocation.TrimEnd('\') -ne $install -or -not (Test-Path -LiteralPath $exe)) {
        throw "Expected one per-user installation of $Version in $install"
    }
    $records[0]
}

function Find-AppShortcuts {
    param([string]$Folder, [switch]$Recurse)
    if (Test-Path -LiteralPath $Folder) {
        Get-ChildItem -LiteralPath $Folder -Filter '*.lnk' -File -Recurse:$Recurse | Where-Object {
            $shell.CreateShortcut($_.FullName).TargetPath -eq $exe
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

function Uninstall-TestProduct {
    $product = @(InstalledProducts)
    if ($product.Count -ne 1 -or $product[0].InstallLocation.TrimEnd('\') -ne $install -or
        $product[0].PSChildName -notmatch '^\{[0-9a-fA-F-]{36}\}$') {
        throw 'Cannot identify the isolated MSI product to uninstall'
    }
    Invoke-Installer "$env:SystemRoot\System32\msiexec.exe" @('/x', $product[0].PSChildName, '/qn', '/norestart',
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
    if ($startLink.Count -ne 1 -or $desktopLink.Count -ne 1) { throw 'Expected both application shortcuts' }
    Test-ShortcutLaunch $startLink[0].FullName
    Test-ShortcutLaunch $desktopLink[0].FullName

    $repeat = Install-TestProduct $Setup 'repeat-install.log' @(0, 1638)
    $sameVersion = Assert-Install '1.0.0'
    if ($sameVersion.PSChildName -ne $initial.PSChildName) { throw 'A second product replaced the first during same-version install' }

    Install-TestProduct $Upgrade 'upgrade.log' | Out-Null
    $updated = Assert-Install '1.0.1'
    if ($initial.PSChildName -eq $updated.PSChildName) { throw 'Expected a major upgrade with a new MSI product code' }
    $updatedStart = @(Find-AppShortcuts $startMenu -Recurse)
    if ($updatedStart.Count -ne 1) { throw 'Expected one Start menu entry after the upgrade' }
    Test-ShortcutLaunch $updatedStart[0].FullName
    Uninstall-TestProduct
    if ((Test-Path -LiteralPath $startLink[0].FullName) -or (Test-Path -LiteralPath $desktopLink[0].FullName)) {
        throw 'One or more shortcuts survived uninstall'
    }

    Install-TestProduct $Setup 'reinstall.log' | Out-Null
    Assert-Install '1.0.0' | Out-Null
    Uninstall-TestProduct
    "Passed: install, Start menu and desktop launch, close, repeat ($repeat), upgrade, uninstall and reinstall"
    'The external fixture hash and Java Preferences marker survived the lifecycle'
} finally {
    $records = @(InstalledProducts)
    if ($records.Count -eq 1 -and $records[0].InstallLocation.TrimEnd('\') -eq $install) {
        Uninstall-TestProduct
    }
}

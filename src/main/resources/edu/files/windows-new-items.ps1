$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
using System.Text;

public static class ShellNewText {
    [DllImport("shlwapi.dll", CharSet = CharSet.Unicode)]
    private static extern int SHLoadIndirectString(string source, StringBuilder output, uint capacity, System.IntPtr reserved);

    public static string Resolve(string value) {
        if (string.IsNullOrEmpty(value) || value[0] != '@') { return value; }
        var output = new StringBuilder(1024);
        return SHLoadIndirectString(value, output, (uint)output.Capacity, System.IntPtr.Zero) == 0
            ? output.ToString() : null;
    }
}
'@

function Write-NewItem([string]$kind, [string]$extension, [string]$label, [string]$name, [string]$value) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($value))
    (@($kind, $extension, $label, $name, $encoded) | ForEach-Object { $_ -replace '[\t\r\n]', ' ' }) -join "`t"
}

function Resolve-Text($value) {
    if ($value) { [ShellNewText]::Resolve([Environment]::ExpandEnvironmentVariables([string]$value)) }
}

function Resolve-MenuText($value) {
    (Resolve-Text $value) -replace '\s*\(&[A-Za-z0-9]\)$', '' -replace '(?<!&)&(?!&)', '' -replace '&&', '&'
}

function Resolve-Template([string]$filename) {
    $filename = [Environment]::ExpandEnvironmentVariables($filename).Trim('"')
    if ([IO.Path]::IsPathRooted($filename)) { return $filename }
    $roots = @((Join-Path $env:APPDATA 'Microsoft\Windows\Templates'),
               (Join-Path $env:ProgramData 'Microsoft\Windows\Templates'),
               (Join-Path $env:windir 'ShellNew'))
    foreach ($directory in $roots) {
        $candidate = Join-Path $directory $filename
        if ([IO.File]::Exists($candidate)) { return $candidate }
    }
    Join-Path $roots[-1] $filename
}

function Is-OptIn($key) {
    $config = $key.OpenSubKey('Config')
    if (!$config) { return $false }
    try { $config.GetValueNames() -contains 'IsOptIn' } finally { $config.Dispose() }
}

$classes = [Microsoft.Win32.Registry]::ClassesRoot
foreach ($stock in @(@('FOLDER', 'Folder\ShellNew'), @('SHORTCUT', '.lnk\ShellNew'))) {
    $key = $classes.OpenSubKey($stock[1])
    if ($key) {
        try {
            Write-NewItem $stock[0] '' (Resolve-MenuText $key.GetValue('MenuText')) (Resolve-Text $key.GetValue('ItemName')) ''
        } finally { $key.Dispose() }
    }
}

foreach ($extension in $classes.GetSubKeyNames()) {
    if (!$extension.StartsWith('.') -or $extension -eq '.lnk') { continue }
    $extensionKey = $classes.OpenSubKey($extension)
    if (!$extensionKey) { continue }
    try { $progId = [string]$extensionKey.GetValue('') } finally { $extensionKey.Dispose() }
    if (!$progId) { continue }

    $progKey = $classes.OpenSubKey($progId)
    $description = $null
    if ($progKey) {
        try {
            $description = Resolve-Text $progKey.GetValue('FriendlyTypeName')
            if (!$description) { $description = Resolve-Text $progKey.GetValue('') }
        } finally { $progKey.Dispose() }
    }

    foreach ($path in @("$extension\$progId\ShellNew", "$extension\ShellNew", "$progId\ShellNew")) {
        $key = $classes.OpenSubKey($path)
        if (!$key) { continue }
        try {
            $names = $key.GetValueNames()
            if ($key.GetValue('Handler') -or (Is-OptIn $key)) { break }
            $label = Resolve-MenuText $key.GetValue('MenuText')
            if (!$label) { $label = $description }
            if (!$label) { continue }
            $name = Resolve-Text $key.GetValue('ItemName')
            if ($names -contains 'NullFile') {
                Write-NewItem 'EMPTY' $extension $label $name ''
            } elseif ($key.GetValue('FileName')) {
                Write-NewItem 'TEMPLATE' $extension $label $name (Resolve-Template $key.GetValue('FileName'))
            } elseif ($key.GetValue('Data') -is [byte[]]) {
                Write-NewItem 'DATA' $extension $label $name ([Convert]::ToBase64String($key.GetValue('Data')))
            } elseif ($key.GetValue('Command')) {
                $command = [Environment]::ExpandEnvironmentVariables([string]$key.GetValue('Command'))
                if ($command -match '%[1lL*]') { continue }
                Write-NewItem 'COMMAND' $extension $label $name $command
            } else { continue }
            break
        } finally { $key.Dispose() }
    }
}

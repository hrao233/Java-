$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

function Write-Row {
    ($args | ForEach-Object { ([string]$_) -replace '[\r\n\t]', ' ' }) -join "`t"
}

$advanced = Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\Advanced'
Write-Row 'view' $advanced.Hidden $advanced.ShowSuperHidden $advanced.HideFileExt

$userFolders = Get-ItemProperty -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders'
$downloads = $userFolders.'{374DE290-123F-4565-9164-39C4925E467B}'
if (!$downloads) { $downloads = Join-Path $env:USERPROFILE 'Downloads' }
Write-Row 'folder' 'Desktop' ([Environment]::GetFolderPath('DesktopDirectory'))
Write-Row 'folder' 'Downloads' ([Environment]::ExpandEnvironmentVariables($downloads))
Write-Row 'folder' 'Documents' ([Environment]::GetFolderPath('MyDocuments'))
Write-Row 'folder' 'Pictures' ([Environment]::GetFolderPath('MyPictures'))
Write-Row 'folder' 'Music' ([Environment]::GetFolderPath('MyMusic'))
Write-Row 'folder' 'Videos' ([Environment]::GetFolderPath('MyVideos'))
@($env:OneDrive; $env:OneDriveConsumer; $env:OneDriveCommercial; (Join-Path $env:USERPROFILE 'OneDrive')) |
    Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) } |
    Select-Object -Unique | ForEach-Object { Write-Row 'onedrive' $_ }

$shell = New-Object -ComObject Shell.Application
$computer = $shell.NameSpace(17)
if ($computer) {
    foreach ($item in $computer.Items()) {
        $directory = $item.IsFolder -and (Test-Path -LiteralPath $item.Path -PathType Container)
        Write-Row 'shell' $item.Name $item.Path ([int]$directory)
    }
}

Get-CimInstance Win32_LogicalDisk -OperationTimeoutSec 4 | ForEach-Object {
    Write-Row 'disk' $_.DeviceID $_.VolumeName $_.DriveType $_.Size $_.FreeSpace $_.FileSystem $_.Description $_.ProviderName
}

Get-ChildItem -LiteralPath 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Lxss' | ForEach-Object {
    $name = (Get-ItemProperty -LiteralPath $_.PSPath).DistributionName
    if ($name) { Write-Row 'linux' $name }
}

$shortcutReader = New-Object -ComObject WScript.Shell
$recent = [Environment]::GetFolderPath('Recent')
if ($recent) {
    Get-ChildItem -LiteralPath $recent -Filter '*.lnk' -File | Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 50 | ForEach-Object {
            $target = $shortcutReader.CreateShortcut($_.FullName).TargetPath
            if ($target -and $target -notlike '\\*' -and (Test-Path -LiteralPath $target)) {
                Write-Row 'recent' $target $_.LastWriteTimeUtc.ToString('o')
            }
        }
}

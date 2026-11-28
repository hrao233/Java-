#Requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$OutputDirectory,
    [string]$ArchiveCache
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$packaging = Join-Path $project 'packaging'
$lock = Get-Content -LiteralPath (Join-Path $packaging 'windows-lock.json') -Raw | ConvertFrom-Json -AsHashtable
[xml]$pom = Get-Content -LiteralPath (Join-Path $project 'pom.xml') -Raw
$version = [string]$pom.project.version
$output = (Resolve-Path -LiteralPath $OutputDirectory).Path
$staging = Join-Path $output 'build\third-party-sources'
if (-not $ArchiveCache) { $ArchiveCache = Join-Path $output 'build\source-downloads' }
New-Item -ItemType Directory -Force -Path $ArchiveCache | Out-Null
New-Item -ItemType Directory -Path $staging | Out-Null

foreach ($name in $lock.sources.Keys) {
    $provenance = $lock.sources[$name]
    $archive = Join-Path $ArchiveCache $name
    if (-not (Test-Path -LiteralPath $archive)) {
        $partial = "$archive.partial"
        Invoke-WebRequest -Uri $provenance.url -OutFile $partial -TimeoutSec 600
        if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $provenance.sha256) {
            throw "Unexpected upstream archive hash for $name"
        }
        Move-Item -LiteralPath $partial -Destination $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $provenance.sha256) {
        throw "Unexpected cached archive hash for $name"
    }
    Copy-Item -LiteralPath $archive -Destination $staging
}
Copy-Item -LiteralPath (Join-Path $packaging 'THIRD-PARTY-NOTICES.txt') -Destination $staging
$asset = Join-Path $output "JavaFXFileManager-$version-third-party-sources.zip"
[IO.Compression.ZipFile]::CreateFromDirectory($staging, $asset, 'NoCompression', $false)

$checksums = Join-Path $output 'SHA256SUMS.txt'
$files = @(Get-ChildItem -LiteralPath $output -File | Where-Object {
    $_.Name -match "^JavaFXFileManager-$([regex]::Escape($version))-(windows-x64-(portable|setup)|third-party-sources)\.(exe|zip)$"
})
$files | Sort-Object Name | ForEach-Object {
    (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $_.Name
} | Set-Content -LiteralPath $checksums -Encoding ascii
$asset

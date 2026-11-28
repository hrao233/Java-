#Requires -Version 7.2
[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [string]$MavenRepository,
    [string]$WixDirectory,
    [ValidateSet('Default', 'Desktop', 'Skip')]
    [string]$Tests = 'Default',
    [ValidatePattern('^\d+\.\d+\.\d+$')]
    [string]$InstallerTestVersion
)

$ErrorActionPreference = 'Stop'
$project = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$packaging = Join-Path $project 'packaging'
$lock = Get-Content -LiteralPath (Join-Path $packaging 'windows-lock.json') -Raw | ConvertFrom-Json -AsHashtable
$app = 'JavaFXFileManager'

function Invoke-Checked {
    param([string]$Tool, [string[]]$Arguments)
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool exited with code $LASTEXITCODE" }
}

function Copy-WindowsDependencies {
    param([string]$ResolvedJars, [string]$ApplicationInput, [string]$JavaFxInput)

    foreach ($name in $lock.dependencies.Keys) {
        $jar = Join-Path $ResolvedJars $name
        if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -ne $lock.dependencies[$name]) {
            throw "Unexpected Maven artifact hash for $name"
        }
        $destination = if ($name.StartsWith('javafx-')) { $JavaFxInput } else { $ApplicationInput }
        Copy-Item -LiteralPath $jar -Destination $destination
        if ($name.StartsWith('jna-')) {
            # Keep the original API and Windows x64 dispatch DLL. The upstream JAR has no JAR signature.
            $archive = [IO.Compression.ZipFile]::Open((Join-Path $destination $name), 'Update')
            try {
                foreach ($entry in @($archive.Entries)) {
                    if ($entry.FullName -match '/(?:lib)?jnidispatch\.' -and
                        $entry.FullName -ne 'com/sun/jna/win32-x86-64/jnidispatch.dll') {
                        $entry.Delete()
                    }
                }
            } finally { $archive.Dispose() }
        }
    }
}

if (-not $IsWindows -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw 'A Windows x64 build host is required'
}
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to the Microsoft OpenJDK version in packaging/windows-lock.json' }
$javaHome = (Resolve-Path -LiteralPath $env:JAVA_HOME).Path
$jdkRelease = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $javaHome 'release') -Raw)
if ($jdkRelease.JAVA_VERSION -ne ('"' + $lock.jdk.version + '"') -or
    $jdkRelease.OS_ARCH -ne '"x86_64"' -or
    $jdkRelease.IMPLEMENTOR -ne '"Microsoft"' -or
    $jdkRelease.SOURCE -ne ('".:git:' + $lock.jdk.source.Substring(0,12) + '"')) {
    throw "Use Microsoft OpenJDK $($lock.jdk.version) Windows x64 for this release"
}
if ($WixDirectory) {
    $WixDirectory = (Resolve-Path -LiteralPath $WixDirectory).Path
    foreach ($tool in @('candle.exe', 'light.exe')) {
        if ((Get-Item -LiteralPath (Join-Path $WixDirectory $tool)).VersionInfo.FileVersion -ne $lock.wix.version) {
            throw "Use WiX $($lock.wix.version) in -WixDirectory"
        }
    }
} elseif ($InstallerTestVersion) {
    throw 'Specify the portable WiX directory when building an installer upgrade fixture'
}

$originalPath = $env:Path
Push-Location $project
try {
    $env:Path = (Join-Path $javaHome 'bin') + ';' + $env:Path
    $maven = (Get-Command mvn.cmd -CommandType Application).Source
    $mavenVersion = & $maven -v | Select-Object -First 1
    if ($mavenVersion -notmatch ('Apache Maven ' + [regex]::Escape($lock.maven) + '\b')) {
        throw "Use Apache Maven $($lock.maven) for this release. Found $mavenVersion"
    }
    [xml]$pom = Get-Content -LiteralPath (Join-Path $project 'pom.xml') -Raw
    $version = [string]$pom.project.version
    $source = (Invoke-Checked 'git' @('rev-parse', 'HEAD')).Trim()

    if (-not $OutputDirectory) {
        $OutputDirectory = Join-Path $project ('target\windows-release\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
    }
    $output = [IO.Path]::GetFullPath($OutputDirectory)
    if (Test-Path -LiteralPath $output) { throw "Choose a new output directory. $output already exists" }
    $build = New-Item -ItemType Directory -Path (Join-Path $output 'build')
    $jars = New-Item -ItemType Directory -Path (Join-Path $build 'dependencies')
    $inputJars = New-Item -ItemType Directory -Path (Join-Path $build 'input')
    $fxJars = New-Item -ItemType Directory -Path (Join-Path $build 'fx')

    $mavenArgs = @('-B', '-ntp')
    if ($MavenRepository) { $mavenArgs += "-Dmaven.repo.local=$MavenRepository" }
    switch ($Tests) {
        'Desktop' { $mavenArgs += '-Djavafx.integration=true' }
        'Skip' { $mavenArgs += '-DskipTests' }
    }
    Invoke-Checked $maven ($mavenArgs + @('package', 'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
            '-DincludeScope=runtime', "-DoutputDirectory=$($jars.FullName)"))
    $appJar = "javafx-file-manager-$version.jar"
    Copy-Item -LiteralPath (Join-Path $project "target\$appJar") -Destination $inputJars.FullName
    Copy-WindowsDependencies $jars.FullName $inputJars.FullName $fxJars.FullName

    $jdkTools = Join-Path $javaHome 'bin'
    $classPath = (Get-ChildItem -LiteralPath $inputJars.FullName -Filter '*.jar' |
        Where-Object Name -ne $appJar).FullName -join ';'
    $detectedModules = Invoke-Checked (Join-Path $jdkTools 'jdeps.exe') @('--multi-release', '21', '--print-module-deps',
        '--module-path', $fxJars.FullName, '--class-path', $classPath, (Join-Path $inputJars.FullName $appJar))
    $modules = @($detectedModules -split ',' | Where-Object { $_ -notlike 'javafx.*' })
    # Keep modules used by diagnostics, non-English resources, and native code that jdeps cannot trace.
    $modules += @('java.management', 'jdk.charsets', 'jdk.localedata', 'jdk.unsupported')
    $modules = ($modules | Sort-Object -Unique) -join ','
    $runtime = Join-Path $build 'runtime'
    Invoke-Checked (Join-Path $jdkTools 'jlink.exe') @('--module-path', (Join-Path $javaHome 'jmods'),
        '--add-modules', $modules, '--output', $runtime)

    $icon = Join-Path $packaging 'file-manager.ico'
    $console = Join-Path $build 'diagnostics.properties'
    @('win-console=true', 'win-menu=false', 'win-shortcut=false') | Set-Content -LiteralPath $console -Encoding ascii
    $jpackage = Join-Path $jdkTools 'jpackage.exe'
    Invoke-Checked $jpackage @('--type', 'app-image', '--name', $app, '--app-version', $version,
        '--description', '本地文件管理器', '--vendor', 'hrao233', '--dest', $output, '--input', $inputJars.FullName,
        '--main-jar', $appJar, '--main-class', 'edu.files.FileManagerApp', '--icon', $icon,
        '--runtime-image', $runtime, '--add-launcher', "$app-Diagnostics=$console",
        '--java-options', '--module-path=$APPDIR\fx', '--java-options', '--add-modules=javafx.controls',
        '--java-options', '-Djna.nosys=true')

    $image = Join-Path $output $app
    Copy-Item -LiteralPath $fxJars.FullName -Destination (Join-Path $image 'app') -Recurse
    $legal = New-Item -ItemType Directory -Path (Join-Path $image 'licenses')
    Copy-Item -LiteralPath (Join-Path $project 'LICENSE') -Destination (Join-Path $legal 'FileManager-MIT.txt')
    Copy-Item -LiteralPath (Join-Path $packaging 'third-party') -Destination $legal.FullName -Recurse
    Copy-Item -LiteralPath (Join-Path $packaging 'THIRD-PARTY-NOTICES.txt') -Destination $legal.FullName

    [ordered]@{
        version = $version
        sourceCommit = $source
        sourceDirty = [bool](git status --porcelain --untracked-files=normal)
        windows = (Get-CimInstance Win32_OperatingSystem).BuildNumber
        maven = $lock.maven
        jdk = $lock.jdk.version
        modules = $modules
        dependencies = $lock.dependencies
        wix = if ($WixDirectory) { $lock.wix.version } else { $null }
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $image 'build-info.json') -Encoding utf8

    $prefix = "$app-$version-windows-x64"
    $zip = Join-Path $output "$prefix-portable.zip"
    [IO.Compression.ZipFile]::CreateFromDirectory($image, $zip, 'Optimal', $true)
    $assets = @($zip)
    if ($WixDirectory) {
        $env:Path = "$WixDirectory;$env:Path"
        $installerVersions = @($version)
        if ($InstallerTestVersion) {
            if ([version]$InstallerTestVersion -le [version]$version) { throw 'The upgrade fixture must have a greater version' }
            $installerVersions += $InstallerTestVersion
        }
        foreach ($installerVersion in $installerVersions) {
            $setup = Join-Path $build "setup-$installerVersion"
            Invoke-Checked $jpackage @('--type', 'exe', '--app-image', $image, '--name', '本地文件管理器',
                '--app-version', $installerVersion, '--description', '本地文件管理器', '--vendor', 'hrao233',
                '--dest', $setup, '--icon', $icon, '--win-per-user-install', '--win-dir-chooser',
                '--install-dir', $app, '--win-menu', '--win-menu-group', '本地文件管理器',
                '--win-shortcut', '--win-shortcut-prompt', '--win-upgrade-uuid', $lock.upgradeUuid)
            $built = @(Get-ChildItem -LiteralPath $setup -Filter '*.exe' -File)
            if ($built.Count -ne 1) { throw "Expected one installer under $setup" }
            $installerName = if ($installerVersion -eq $version) {
                "$prefix-setup.exe"
            } else { "$app-$installerVersion-windows-x64-upgrade-test.exe" }
            $installer = Join-Path $output $installerName
            Move-Item -LiteralPath $built[0].FullName -Destination $installer
            if ($installerVersion -eq $version) { $assets += $installer }
        }
    }

    $assets | ForEach-Object {
        $digest = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()
        $digest + '  ' + (Split-Path -Leaf $_)
    } | Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS.txt') -Encoding ascii
    "Windows build: $output"
} finally {
    $env:Path = $originalPath
    Pop-Location
}

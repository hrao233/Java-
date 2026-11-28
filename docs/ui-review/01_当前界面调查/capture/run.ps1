#Requires -Version 7.2
param(
    [Parameter(Mandatory)] [string]$PortableZip,
    [Parameter(Mandatory)] [string]$Jdk21,
    [Parameter(Mandatory)] [string]$FixtureDirectory,
    [string]$Ids,
    [ValidateSet('100%')] [string]$JavaFXScale
)

$ErrorActionPreference = 'Stop'
$fixture = [IO.Path]::GetFullPath($FixtureDirectory)
if (Test-Path -LiteralPath $fixture) { throw 'Use a fresh fixture directory' }
$python = (Get-Command python.exe -CommandType Application | Select-Object -First 1).Source
$pack = (Resolve-Path -LiteralPath $PortableZip).Path
$kit = (Resolve-Path -LiteralPath $Jdk21).Path
$helper = Join-Path $PSScriptRoot 'fixture.py'
$expected = '6c8b3b775c02be1da5d22248f891682dd0bdad8020ae4c9bf1b3125f1a7c2621'
if ((Get-FileHash -LiteralPath $pack).Hash -ne $expected) { throw 'Use the audited 1.0.0 portable ZIP' }
$release = Get-Content -LiteralPath (Join-Path $kit 'release')
if (-not ($release -match '^JAVA_VERSION="21[.]')) { throw 'Use JDK 21 to compile the capture helper' }

& $python $helper fixture $fixture
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$expanded = Join-Path $fixture 'Packaged'
[IO.Compression.ZipFile]::ExtractToDirectory($pack, $expanded)
$image = Join-Path $expanded 'JavaFXFileManager'
$app = Join-Path $image 'app'
$classes = (New-Item -ItemType Directory -Path (Join-Path $fixture 'Classes')).FullName
$output = (New-Item -ItemType Directory -Path (Join-Path $fixture 'Images')).FullName
$exports = @(
    '--add-exports=javafx.graphics/com.sun.javafx.scene.input=ALL-UNNAMED',
    '--add-exports=javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED'
)
& (Join-Path $kit 'bin\javac.exe') -encoding UTF-8 --module-path (Join-Path $app 'fx') --add-modules javafx.controls @exports -cp "$app\*" -d $classes (Join-Path $PSScriptRoot 'VisualAudit.java')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $kit 'bin\jar.exe') --create --file (Join-Path $app 'ui-audit.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Only the diagnostics launcher in the disposable copy uses the test entry point.
$launcherConfig = Join-Path $app 'JavaFXFileManager-Diagnostics.cfg'
$config = @(Get-Content -LiteralPath $launcherConfig -Encoding utf8)
$config = @($config | ForEach-Object {
    if ($_ -match '^app.mainclass=') {
        'app.mainclass=edu.files.VisualAudit'
        'app.classpath=$APPDIR\ui-audit.jar'
    } else { $_ }
})
$config += $exports | ForEach-Object { "java-options=$_" }
$config += "java-options=-Duser.home=$(Join-Path $fixture 'Profile')"
$config += "java-options=-Djava.io.tmpdir=$(Join-Path $fixture 'Temp')"
$config += 'java-options=-Dfile.encoding=UTF-8'
if ($JavaFXScale) { $config += "java-options=-Dglass.win.uiScale=$JavaFXScale" }
$config | Set-Content -LiteralPath $launcherConfig -Encoding utf8NoBOM

$arguments = @($fixture, $output, $python, $helper)
if ($Ids) { $arguments += $Ids }
$arguments = ($arguments | ForEach-Object { '"'+ $_ + '"' }) -join ' '
$startInfo = @{
    FilePath = Join-Path $image 'JavaFXFileManager-Diagnostics.exe'
    ArgumentList = $arguments
    WorkingDirectory = $fixture
    WindowStyle = 'Hidden'
    PassThru = $true
    RedirectStandardOutput = Join-Path $fixture 'capture.log'
    RedirectStandardError = Join-Path $fixture 'capture-errors.log'
}
$process = Start-Process @startInfo
Write-Output "Audit process $($process.Id). Captures and logs are under $fixture."
$process.WaitForExit()
Get-Content -LiteralPath (Join-Path $fixture 'capture.log') -Encoding utf8 | Write-Output
Get-Content -LiteralPath (Join-Path $fixture 'capture-errors.log') -Encoding utf8 | Write-Output
exit $process.ExitCode

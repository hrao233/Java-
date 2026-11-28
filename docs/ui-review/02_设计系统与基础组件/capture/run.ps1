#Requires -Version 7.2
param(
    [string]$Jdk21 = $env:JAVA_HOME,
    [ValidateSet('light', 'dark')] [string]$Theme = 'light',
    [ValidateSet('100%', '150%')] [string]$Scale,
    [string]$FixtureDirectory,
    [string]$ApplicationShots = 'A03,B09,C04,D06,E03'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$survey = (Resolve-Path (Join-Path $PSScriptRoot '../../01_当前界面调查/capture')).Path
if (-not $Jdk21) { throw 'Specify -Jdk21 or set JAVA_HOME to JDK 21.' }
$kit = (Resolve-Path -LiteralPath $Jdk21).Path
if (-not ((Get-Content -LiteralPath (Join-Path $kit 'release')) -match '^JAVA_VERSION="21[.]')) {
    throw 'Use JDK 21 for this capture.'
}

$env:JAVA_HOME = $kit
$classpathFile = Join-Path $repo 'target/ui-review-classpath.txt'
Push-Location $repo
try {
    & mvn -q compile dependency:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) { throw 'Build or dependency resolution failed.' }
} finally {
    Pop-Location
}
$dependencies = (Get-Content -Raw -LiteralPath $classpathFile).Trim().Split([IO.Path]::PathSeparator)
$modules = @($dependencies | Where-Object { [IO.Path]::GetFileName($_) -match '^javafx-.+-win(?:-x86|-aarch64)?\.jar$' })
$libraries = @($dependencies | Where-Object { [IO.Path]::GetFileName($_) -notlike 'javafx-*' })
$appClasses = Join-Path $repo 'target/classes'
$captureClasses = (New-Item -ItemType Directory -Force -Path (Join-Path $repo 'target/ui-review-classes')).FullName
$buildPath = (@($appClasses) + $libraries) -join [IO.Path]::PathSeparator
$runPath = (@($captureClasses, $appClasses) + $libraries) -join [IO.Path]::PathSeparator
$moduleOptions = @('--module-path', ($modules -join [IO.Path]::PathSeparator), '--add-modules', 'javafx.controls')
$exports = @(
    '--add-exports=javafx.graphics/com.sun.javafx.scene.input=ALL-UNNAMED',
    '--add-exports=javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED'
)
$sources = @(Join-Path $PSScriptRoot 'ComponentShowcase.java')
if ($FixtureDirectory) { $sources += Join-Path $survey 'VisualAudit.java' }
& (Join-Path $kit 'bin/javac.exe') -encoding UTF-8 @moduleOptions @exports -cp $buildPath -d $captureClasses @sources
if ($LASTEXITCODE -ne 0) { throw 'Capture entry points did not compile.' }

$java = Join-Path $kit 'bin/java.exe'
$options = @('-Dfile.encoding=UTF-8') + $moduleOptions + @('-cp', $runPath)
if ($Scale) { $options += "-Dglass.win.uiScale=$Scale" }
if (-not $FixtureDirectory) {
    & $java @options edu.files.ComponentShowcase "--theme=$Theme"
    exit $LASTEXITCODE
}

$python = (Get-Command python.exe -CommandType Application | Select-Object -First 1).Source
$helper = Join-Path $survey 'fixture.py'
$fixture = [IO.Path]::GetFullPath($FixtureDirectory)
if (Test-Path -LiteralPath $fixture) { throw 'Choose a fresh directory for the review fixture.' }
& $python $helper fixture $fixture
if ($LASTEXITCODE -ne 0) { throw 'Unable to create review fixture.' }
$images = Join-Path $fixture 'Images'
$options += "-Duser.home=$(Join-Path $fixture 'Profile')"
$options += "-Djava.io.tmpdir=$(Join-Path $fixture 'Temp')"
$javaw = Join-Path $kit 'bin/javaw.exe'

function Invoke-ReviewWindow([string]$Entry, [string[]]$Arguments) {
    $name = $Entry.Split('.')[-1]
    $log = Join-Path $fixture "$name.log"
    $errors = Join-Path $fixture "$name-errors.log"
    $process = Start-Process -FilePath $javaw -WorkingDirectory $fixture -WindowStyle Normal -PassThru `
        -ArgumentList (($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' ') `
        -RedirectStandardOutput $log -RedirectStandardError $errors
    $process.WaitForExit()
    Get-Content -LiteralPath $log -Encoding utf8
    Get-Content -LiteralPath $errors -Encoding utf8
    if ($process.ExitCode -ne 0) { throw "$Entry exited with code $($process.ExitCode)." }
}

Invoke-ReviewWindow 'edu.files.ComponentShowcase' ($options + @('edu.files.ComponentShowcase', "--theme=$Theme",
    "--output=$images", "--python=$python", "--helper=$helper"))
if ($ApplicationShots) {
    Invoke-ReviewWindow 'edu.files.VisualAudit' ($options + $exports + @("-Dedu.files.audit.theme=$Theme",
        'edu.files.VisualAudit', $fixture, $images, $python, $helper, $ApplicationShots))
    $captured = @(Get-Content -LiteralPath (Join-Path $images 'captures.tsv') | ForEach-Object { ($_ -split "`t")[0] })
    $missing = @($ApplicationShots.Split(',') | Where-Object { $_ -notin $captured })
    if ($missing.Count -gt 0) { throw "Missing application screenshots $($missing -join ', ')." }
}

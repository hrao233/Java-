[CmdletBinding()]
param(
    [string]$Root,
    [string]$Java,
    [string]$ClassPath,
    [string]$Station,
    [string]$PriorFixture,
    [switch]$Measure,
    [switch]$MeasureOnly,
    [switch]$Child
)

$ErrorActionPreference = 'Stop'
Add-Type -Path (Join-Path $PSScriptRoot 'IsolatedClipboard.cs')

if (-not $Child) {
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 installation' }
    $repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
    $reportPath = Join-Path $repo 'target/surefire-reports/TEST-edu.files.WindowsClipboardFormatTest.xml'
    $report = ([xml](Get-Content -Raw -Encoding utf8 -LiteralPath $reportPath)).testsuite
    $ClassPath = ($report.properties.property | Where-Object name -eq 'java.class.path').value
    $Java = Join-Path $env:JAVA_HOME 'bin/java.exe'
    $Root = [IO.Directory]::CreateTempSubdirectory('javafx-phase26-interop-').FullName
    $powershell = Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $arguments = '-NoProfile -Sta -ExecutionPolicy Bypass -File "' + $PSCommandPath + '" -Child' +
        ' -Root "' + $Root + '" -Java "' + $Java + '" -ClassPath "' + $ClassPath + '"'
    if ($PriorFixture) { $arguments += ' -PriorFixture "' + $PriorFixture + '"' }
    if ($Measure) { $arguments += ' -Measure' }
    if ($MeasureOnly) { $arguments += ' -MeasureOnly' }
    try {
        $exitCode = [IsolatedClipboard]::Run($powershell, $arguments, $Root)
    } finally {
        $logPath = Join-Path $Root 'results.txt'
        if (Test-Path -LiteralPath $logPath) { Get-Content -LiteralPath $logPath -Encoding utf8 }
        Write-Output ('Fixture ' + $Root)
    }
    if ($exitCode -ne 0) { throw "Isolated clipboard suite failed, exit code $exitCode" }
    return
}

function Log([string]$Line) {
    Add-Content -LiteralPath (Join-Path $Root 'results.txt') -Value $Line -Encoding utf8
}

function Invoke-Java([string]$Name, [string[]]$Invocation) {
    $stdout = Join-Path $Root ($Name + '.stdout')
    $stderr = Join-Path $Root ($Name + '.stderr')
    $args = '-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "' + $ClassPath + '" edu.files.Phase26ClipboardProbe "' + $Station + '" "' + $Root + '" ' +
        (($Invocation | ForEach-Object { '"' + $_ + '"' }) -join ' ')
    $launch = @{
        FilePath = $Java; ArgumentList = $args; WindowStyle = 'Hidden'; PassThru = $true
        RedirectStandardOutput = $stdout; RedirectStandardError = $stderr
    }
    $process = Start-Process @launch
    try {
        $null = $process.Handle
        $seconds = if ($Name -eq 'benchmark') { 130 } else { 45 }
        $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
        while (-not $process.HasExited -and [DateTime]::UtcNow -lt $deadline) {
            [Windows.Forms.Application]::DoEvents()
            Start-Sleep -Milliseconds 20
        }
        if (-not $process.HasExited) {
            $process.Kill()
            $process.WaitForExit()
            Get-Content -LiteralPath $stdout -Encoding utf8 | ForEach-Object { Log $_ }
            throw "Timed out in $Name"
        }
        $process.WaitForExit()
        if (Test-Path -LiteralPath $stdout) {
            Get-Content -LiteralPath $stdout -Encoding utf8 | ForEach-Object { Log $_ }
        }
        if ($process.ExitCode -ne 0) {
            Get-Content -LiteralPath $stderr -Encoding utf8 | Select-Object -Last 12 | ForEach-Object { Log $_ }
            throw "$Name failed with process exit code '$($process.ExitCode)'"
        }
    } finally {
        $process.Dispose()
    }
}

function Wait-Until([scriptblock]$Condition, [string]$Detail) {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (& $Condition) { return }
        [Windows.Forms.Application]::DoEvents()
        Start-Sleep -Milliseconds 30
    }
    throw $Detail
}

function Measure-Clipboard {
    Invoke-Java 'benchmark' @('benchmark')
    $window = [IsolatedClipboard]::HoldTestLock($Station)
    try {
        Invoke-Java 'clipboard-busy' @('busy')
    } finally {
        [IsolatedClipboard]::ReleaseTestLock($Station, $window)
    }
}

function Create-Source([string]$Name) {
    $from = [IO.Directory]::CreateDirectory((Join-Path $Root $Name)).FullName
    $otherName = [string][char]0x6587 + [char]0x4ef6 + ' one.txt'
    $folderName = [string][char]0x8d44 + [char]0x6599 + ' files'
    $folder = [IO.Directory]::CreateDirectory((Join-Path $from $folderName)).FullName
    [IO.File]::WriteAllText((Join-Path $from $otherName), 'checked file contents')
    [IO.File]::WriteAllText((Join-Path $folder 'child.txt'), 'checked folder contents')
    return $from
}

try {
    if ([IsolatedClipboard]::RequireEmpty($Station) -ne 0 -and $PriorFixture) {
        $prior = (Resolve-Path -LiteralPath $PriorFixture).Path
        $temp = [IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
        if (-not $prior.StartsWith($temp + 'javafx-phase26-', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Previous fixture is outside the test temp directory'
        }
        [IsolatedClipboard]::ClearFixture($Station, $prior)
    }
    if ([IsolatedClipboard]::RequireEmpty($Station) -ne 0) { throw 'Noninteractive station clipboard already contains data' }
    Add-Type -AssemblyName System.Windows.Forms
    Log 'Verified a noninteractive window station and an empty clipboard'
    if ($MeasureOnly) {
        Measure-Clipboard
        Log ('Clipboard formats after cleanup ' + [IsolatedClipboard]::RequireEmpty($Station))
        return
    }

    $outbound = Create-Source 'app-source'
    $shellTarget = [IO.Directory]::CreateDirectory((Join-Path $Root 'shell-target')).FullName
    [void][IO.Directory]::CreateDirectory((Join-Path $Root 'app-target'))
    $shell = New-Object -ComObject Shell.Application
    Invoke-Java 'application-copy' @('copy','app-source','copy')
    Log ('JavaFX exited with available formats ' + [IsolatedClipboard]::Formats($Station))
    Invoke-Java 'windows-shell-paste' @('shell-paste','shell-target','app-source','2')
    Wait-Until { (Get-ChildItem -LiteralPath $shellTarget -File -Recurse).Count -eq 2 } 'Shell did not paste app files'
    $digest = [Security.Cryptography.SHA256]::Create()
    try {
        foreach ($file in Get-ChildItem -LiteralPath $outbound -File -Recurse) {
            $relative = $file.FullName.Substring($outbound.Length + 1)
            $result = Join-Path $shellTarget $relative
            $before = [Convert]::ToBase64String($digest.ComputeHash([IO.File]::ReadAllBytes($file.FullName)))
            $after = [Convert]::ToBase64String($digest.ComputeHash([IO.File]::ReadAllBytes($result)))
            if ($before -cne $after) { throw 'Shell copy changed the bytes' }
        }
    } finally {
        $digest.Dispose()
    }
    Log 'JavaFX to Windows Shell after JavaFX process exit passed byte checks'
    Invoke-Java 'second-application' @('paste','app-target','app-source')
    Log 'Two independent JavaFX processes transferred a file and a directory'

    $inbound = Create-Source 'shell-source'
    [void][IO.Directory]::CreateDirectory((Join-Path $Root 'app-inbound'))
    $sourceFolder = $shell.NameSpace($inbound)
    $sourceFolder.Items().InvokeVerbEx('copy')
    Invoke-Java 'shell-copy-inspection' @('inspect','false')
    Log ('Windows Shell copied formats ' + [IsolatedClipboard]::Formats($Station))
    Invoke-Java 'shell-to-application' @('paste','app-inbound','shell-source','shell-to-javafx.png')
    Log 'Windows Shell to JavaFX passed byte checks'

    $sourceFolder.Items().InvokeVerbEx('cut')
    Invoke-Java 'shell-cut-inspection' @('inspect','true')
    Log ('Windows Shell cut formats ' + [IsolatedClipboard]::Formats($Station))
    [IsolatedClipboard]::ClearFixture($Station, $Root)

    [void][IO.Directory]::CreateDirectory((Join-Path $Root 'long-source'))
    [void][IO.Directory]::CreateDirectory((Join-Path $Root 'long-target'))
    Invoke-Java 'long-path-fixture' @('make-long-file','long-source')
    Invoke-Java 'long-path-copy' @('copy','long-source','copy')
    Invoke-Java 'long-path-paste' @('paste','long-target','long-source')
    Log 'Separate JavaFX processes transferred an actual path longer than 260 characters'
    [void](Create-Source 'move-source')
    [void][IO.Directory]::CreateDirectory((Join-Path $Root 'move-target'))
    Invoke-Java 'same-window-cut-paste' @('move','move-source','move-target')

    if ($Measure) { Measure-Clipboard }
    Log ('Clipboard formats after cleanup ' + [IsolatedClipboard]::RequireEmpty($Station))
} catch {
    Log ('FAILED ' + $_.Exception.Message)
    try { [IsolatedClipboard]::ClearFixture($Station, $Root) } catch { }
    exit 1
}

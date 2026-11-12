param(
    [Parameter(Mandatory = $true)][string]$AppPath,
    [Parameter(Mandatory = $true)][string]$ExpectedDescription
)

$ErrorActionPreference = 'Stop'
$executable = (Resolve-Path $AppPath).Path
$processName = [IO.Path]::GetFileNameWithoutExtension($executable) + "-host"
$hostExecutable = Join-Path (Split-Path $executable) "$processName.exe"
$description = [Diagnostics.FileVersionInfo]::GetVersionInfo($executable).FileDescription
if ($description -ne $ExpectedDescription) {
    throw "Unexpected native launcher description '$description'"
}

try {
    # Native bootstrap sanitizes the environment before the only JVM starts.
    Start-Process -FilePath $executable | Out-Null
    $deadline = (Get-Date).AddSeconds(45)
    $window = $null
    while ((Get-Date) -lt $deadline) {
        $window = Get-Process -Name $processName -ErrorAction SilentlyContinue |
            Where-Object { $_.Path -eq $hostExecutable -and $_.MainWindowHandle -ne 0 } |
            Select-Object -First 1
        if ($window) { break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $window) {
        throw 'The packaged application did not display a window in its branded native process'
    }
    $jvm = $window.Modules | Where-Object { $_.ModuleName -eq 'jvm.dll' } | Select-Object -First 1
    if (-not $jvm -or -not $jvm.FileName.StartsWith((Join-Path (Split-Path $executable) 'runtime'), [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The branded process did not load its bundled JVM'
    }
    Write-Output "Native window verified: process=$($window.ProcessName), description=$description, title=$($window.MainWindowTitle)"
} finally {
    Get-Process -Name $processName -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $hostExecutable } |
        Stop-Process -Force -ErrorAction SilentlyContinue
}

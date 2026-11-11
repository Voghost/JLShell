param(
    [Parameter(Mandatory = $true)][string]$AppPath,
    [Parameter(Mandatory = $true)][string]$ExpectedDescription
)

$ErrorActionPreference = 'Stop'
$executable = (Resolve-Path $AppPath).Path
$processName = [IO.Path]::GetFileNameWithoutExtension($executable)
$description = [Diagnostics.FileVersionInfo]::GetVersionInfo($executable).FileDescription
if ($description -ne $ExpectedDescription) {
    throw "Unexpected native launcher description '$description'"
}

try {
    # Bootstrap may replace the initial process to isolate the bundled DLL search path.
    Start-Process -FilePath $executable | Out-Null
    $deadline = (Get-Date).AddSeconds(45)
    $window = $null
    while ((Get-Date) -lt $deadline) {
        $window = Get-Process -Name $processName -ErrorAction SilentlyContinue |
            Where-Object { $_.Path -eq $executable -and $_.MainWindowHandle -ne 0 } |
            Select-Object -First 1
        if ($window) { break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $window) {
        throw 'The packaged application did not display a window in its branded native process'
    }
    Write-Output "Native window verified: process=$($window.ProcessName), description=$description, title=$($window.MainWindowTitle)"
} finally {
    Get-Process -Name $processName -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -eq $executable } |
        Stop-Process -Force -ErrorAction SilentlyContinue
}

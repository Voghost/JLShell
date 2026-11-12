param(
    [Parameter(Mandatory = $true)][string]$AppPath,
    [Parameter(Mandatory = $true)][string]$Description
)
$ErrorActionPreference = 'Stop'
$executable = (Resolve-Path $AppPath).Path
$directory = Split-Path $executable
$name = [IO.Path]::GetFileNameWithoutExtension($executable)
$hostExe = Join-Path $directory "$name-host.exe"
$repo = (Resolve-Path "$PSScriptRoot/../..").Path
$temporary = $env:RUNNER_TEMP
if (-not $temporary) { $temporary = [IO.Path]::GetTempPath() }
$build = Join-Path $temporary "jlshell-native-bootstrap-$name"
New-Item -ItemType Directory -Force $build | Out-Null
$icon = (Join-Path $repo 'app/src/main/resources/icons/app_icon.ico').Replace('\', '\\')
@"
#include <windows.h>
1 ICON "$icon"
1 VERSIONINFO
FILEVERSION 1,0,0,0
PRODUCTVERSION 1,0,0,0
FILETYPE VFT_APP
BEGIN
 BLOCK "StringFileInfo"
 BEGIN
  BLOCK "040904b0"
  BEGIN
   VALUE "FileDescription", "$Description\0"
   VALUE "ProductName", "$Description\0"
   VALUE "CompanyName", "JLShell\0"
   VALUE "OriginalFilename", "$name.exe\0"
  END
 END
 BLOCK "VarFileInfo"
 BEGIN
  VALUE "Translation", 0x409, 1200
 END
END
"@ | Set-Content -Encoding utf8 (Join-Path $build 'bootstrap.rc')
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$vs = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw 'Visual C++ compiler is required to build the native bootstrap' }
$vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'
$source = Join-Path $repo 'launcher/src/main/native/windows-bootstrap.cpp'
@"
@echo off
call "$vcvars" >nul
if errorlevel 1 exit /b 1
cd /d "$build"
rc /nologo bootstrap.rc
if errorlevel 1 exit /b 1
cl /nologo /O1 /MT /EHsc /std:c++17 "$source" bootstrap.res /Fe:bootstrap.exe /link /SUBSYSTEM:WINDOWS user32.lib
exit /b %errorlevel%
"@ | Set-Content -Encoding ascii (Join-Path $build 'build.cmd')
& cmd.exe /c (Join-Path $build 'build.cmd')
if ($LASTEXITCODE -ne 0) { throw 'Native bootstrap compilation failed' }
Move-Item $executable $hostExe
Copy-Item (Join-Path $directory "app/$name.cfg") (Join-Path $directory "app/$name-host.cfg")
Copy-Item (Join-Path $build 'bootstrap.exe') $executable

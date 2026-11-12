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
"1 ICON `"$icon`"" | Set-Content -Encoding ascii (Join-Path $build 'bootstrap.rc')
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
# Keep the exact jpackage version/branding block, including its translation table.
Add-Type @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class LauncherVersionResource {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr LoadLibraryEx(string file, IntPtr reserved, uint flags);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern IntPtr FindResource(IntPtr module, IntPtr name, IntPtr type);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern uint SizeofResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
    [DllImport("kernel32.dll")]
    static extern IntPtr LockResource(IntPtr resource);
    [DllImport("kernel32.dll")]
    static extern bool FreeLibrary(IntPtr module);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern IntPtr BeginUpdateResource(string file, bool deleteExisting);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool UpdateResource(IntPtr update, IntPtr type, IntPtr name, ushort language, byte[] data, uint size);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern bool EndUpdateResource(IntPtr update, bool discard);
    public static void Copy(string source, string target) {
        IntPtr module = LoadLibraryEx(source, IntPtr.Zero, 2);
        if (module == IntPtr.Zero) throw new Win32Exception();
        byte[] bytes;
        try {
            IntPtr resource = FindResource(module, (IntPtr)1, (IntPtr)16);
            uint size = SizeofResource(module, resource);
            if (resource == IntPtr.Zero || size == 0) throw new Win32Exception();
            bytes = new byte[size];
            Marshal.Copy(LockResource(LoadResource(module, resource)), bytes, 0, bytes.Length);
        } finally { FreeLibrary(module); }
        IntPtr update = BeginUpdateResource(target, false);
        if (update == IntPtr.Zero) throw new Win32Exception();
        if (!UpdateResource(update, (IntPtr)16, (IntPtr)1, 0, bytes, (uint)bytes.Length)) {
            EndUpdateResource(update, true);
            throw new Win32Exception();
        }
        if (!EndUpdateResource(update, false)) throw new Win32Exception();
    }
}
'@
$bootstrap = Join-Path $build 'bootstrap.exe'
[LauncherVersionResource]::Copy($executable, $bootstrap)
$actualDescription = [Diagnostics.FileVersionInfo]::GetVersionInfo($bootstrap).FileDescription
if ($actualDescription -ne $Description) { throw "Bootstrap branding mismatch '$actualDescription'" }
Move-Item $executable $hostExe
Copy-Item (Join-Path $directory "app/$name.cfg") (Join-Path $directory "app/$name-host.cfg")
Copy-Item $bootstrap $executable

@echo off
setlocal enabledelayedexpansion

set "JAVA_HOME=D:\Program Files\java\jdk-21.0.10_windows-x64_bin\jdk-21.0.10"

set SCRIPT_DIR=%~dp0
set PLUGINS_DIR=%USERPROFILE%\.jlshell\plugins

if "%1"=="" goto usage
if /i "%1"=="install" goto do_install
if /i "%1"=="uninstall" goto do_uninstall
if /i "%1"=="clean" goto do_clean
goto usage

:usage
echo Usage: %~nx0 ^<command^>
echo.
echo Commands:
echo   install    Build all plugins and install to ~/.jlshell/plugins/
echo   uninstall  Remove all installed plugins from ~/.jlshell/plugins/
echo   clean      Remove all installed plugins AND local build artifacts
goto end

:do_install
echo Building plugins...
cd /d "%SCRIPT_DIR%"
call mvn clean package -q

if not exist "%PLUGINS_DIR%" mkdir "%PLUGINS_DIR%"

set INSTALLED=0
for /r %%f in (*-fat.jar) do (
    copy /y "%%f" "%PLUGINS_DIR%\%%~nxf" >nul
    echo Installed: %%~nxf
    set /a INSTALLED+=1
)

if !INSTALLED!==0 (
    echo No plugin fat JARs found.
) else (
    echo Done. !INSTALLED! plugin(s^) installed to %PLUGINS_DIR%
)
goto end

:do_uninstall
if not exist "%PLUGINS_DIR%" (
    echo Plugin directory does not exist: %PLUGINS_DIR%
    goto end
)

set REMOVED=0
for %%f in ("%PLUGINS_DIR%\*-fat.jar") do (
    del "%%f"
    echo Removed: %%~nxf
    set /a REMOVED+=1
)

if !REMOVED!==0 (
    echo No plugins found in %PLUGINS_DIR%
) else (
    echo Done. !REMOVED! plugin(s^) removed from %PLUGINS_DIR%
)
goto end

:do_clean
call :do_uninstall
echo Cleaning build artifacts...
cd /d "%SCRIPT_DIR%"
call mvn clean -q
echo Done.
goto end

:end
endlocal
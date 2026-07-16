@echo off
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0
set PLUGINS_DIR=%USERPROFILE%\.jlshell\plugins
set PROGRAM_PLUGINS_DIR=%USERPROFILE%\.jlshell\program-plugins

if "%1"=="" goto usage
if /i "%1"=="install" goto do_install
if /i "%1"=="uninstall" goto do_uninstall
if /i "%1"=="clean" goto do_clean
goto usage

:usage
echo Usage: %~nx0 ^<command^>
echo.
echo Commands:
echo   install    Build demos under ^<plugin-id^> while preserving each JAR filename
echo   uninstall  Remove the four installed demo plugin directories
echo   clean      Uninstall demo plugins and remove local build artifacts
goto end

:do_install
echo Building plugins...
cd /d "%SCRIPT_DIR%"
call mvn clean package -q

if not exist "%PLUGINS_DIR%" mkdir "%PLUGINS_DIR%"
if not exist "%PROGRAM_PLUGINS_DIR%" mkdir "%PROGRAM_PLUGINS_DIR%"

del /q "%PROGRAM_PLUGINS_DIR%\plugin-program-demo-*-fat.jar" 2>nul
del /q "%PLUGINS_DIR%\plugin-session-demo-*-fat.jar" 2>nul
del /q "%PLUGINS_DIR%\plugin-demo-*-fat.jar" 2>nul
del /q "%PLUGINS_DIR%\plugin-sysmon-*-fat.jar" 2>nul

call :install_plugin plugin-program-demo com.jlshell.demo.program-host-tools "%PROGRAM_PLUGINS_DIR%"
if errorlevel 1 goto end
call :install_plugin plugin-session-demo com.jlshell.demo.session-tools "%PLUGINS_DIR%"
if errorlevel 1 goto end
call :install_plugin plugin-demo com.jlshell.demo.script-snippets "%PLUGINS_DIR%"
if errorlevel 1 goto end
call :install_plugin plugin-sysmon com.jlshell.sysmon "%PLUGINS_DIR%"
if errorlevel 1 goto end
echo Done. 4 demo plugins installed. Restart JLShell to reload plugin JARs.
goto end

:install_plugin
set "MODULE=%~1"
set "PLUGIN_ID=%~2"
set "ROOT=%~3"
set "FAT_JAR="
for %%f in ("%MODULE%\target\*-fat.jar") do if exist "%%f" if not defined FAT_JAR set "FAT_JAR=%%f"
if not defined FAT_JAR (
    echo Missing fat JAR for %MODULE%
    exit /b 1
)
set "PLUGIN_DIR=%ROOT%\%PLUGIN_ID%"
for %%f in ("%FAT_JAR%") do set "JAR_NAME=%%~nxf"
if not exist "%PLUGIN_DIR%" mkdir "%PLUGIN_DIR%"
set "PREVIOUS_DIR=%PLUGIN_DIR%\.previous"
if not exist "%PREVIOUS_DIR%" mkdir "%PREVIOUS_DIR%"
del /q "%PREVIOUS_DIR%\*.jar" 2>nul
for %%f in ("%PLUGIN_DIR%\*.jar") do if exist "%%f" if /i not "%%~nxf"=="previous-plugin.jar" (
    set "BACKUP_NAME=%%~nxf"
    if /i "!BACKUP_NAME!"=="plugin.jar" set "BACKUP_NAME=!JAR_NAME!"
    copy /y "%%f" "%PREVIOUS_DIR%\!BACKUP_NAME!" >nul
    del /q "%%f"
)
del /q "%PLUGIN_DIR%\previous-plugin.jar" 2>nul
copy /y "%FAT_JAR%" "%PLUGIN_DIR%\%JAR_NAME%" >nul
echo Installed %PLUGIN_ID%: %PLUGIN_DIR%\%JAR_NAME%
exit /b 0

:do_uninstall
set REMOVED=0
call :remove_plugin "%PROGRAM_PLUGINS_DIR%\com.jlshell.demo.program-host-tools"
call :remove_plugin "%PLUGINS_DIR%\com.jlshell.demo.session-tools"
call :remove_plugin "%PLUGINS_DIR%\com.jlshell.demo.script-snippets"
call :remove_plugin "%PLUGINS_DIR%\com.jlshell.sysmon"
del /q "%PROGRAM_PLUGINS_DIR%\plugin-program-demo-*-fat.jar" 2>nul
del /q "%PLUGINS_DIR%\plugin-session-demo-*-fat.jar" 2>nul
del /q "%PLUGINS_DIR%\plugin-demo-*-fat.jar" 2>nul
del /q "%PLUGINS_DIR%\plugin-sysmon-*-fat.jar" 2>nul

if !REMOVED!==0 (
    echo No demo plugin directories found.
) else (
    echo Done. !REMOVED! demo plugin(s^) removed.
)
goto end

:remove_plugin
if exist "%~1" (
    rmdir /s /q "%~1"
    echo Removed: %~1
    set /a REMOVED+=1
)
exit /b 0

:do_clean
call :do_uninstall
echo Cleaning build artifacts...
cd /d "%SCRIPT_DIR%"
call mvn clean -q
echo Done.
goto end

:end
endlocal

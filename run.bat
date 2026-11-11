@echo off
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME (
    echo Please set JAVA_HOME to a JDK 21 installation.
    exit /b 1
)
set "BUILD_GOAL=package"
if "%1"=="--build" set "BUILD_GOAL=clean package"
if "%1"=="-b" set "BUILD_GOAL=clean package"

call mvn %BUILD_GOAL% -Pwith-plugins -DskipTests -q
if errorlevel 1 exit /b %errorlevel%

set "APP_JAR="
for %%F in (app\target\jlshell-app-*.jar) do set "APP_JAR=%%F"
if not defined APP_JAR (
    echo Application JAR was not generated.
    exit /b 1
)
"%JAVA_HOME%\bin\java.exe" -Xms32m -Xmx512m --add-exports=java.desktop/sun.swing=ALL-UNNAMED --add-opens=java.desktop/sun.swing=ALL-UNNAMED --add-opens=java.desktop/javax.swing=ALL-UNNAMED -jar "%APP_JAR%"
exit /b %errorlevel%

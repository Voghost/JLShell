@echo off
setlocal

set "JAVA_HOME=D:\jdk\openjdk-21.0.2_windows-x64_bin\jdk-21.0.2"

if "%1"=="--build" goto do_build
if "%1"=="-b" goto do_build

echo installing modules ...
call mvn install -DskipTests -q
if %errorlevel% neq 0 (
    echo install failed
    exit /b %errorlevel%
)

echo starting JLShell ...
call mvn javafx:run -pl app
goto end

:do_build
echo JDK: %JAVA_HOME%
echo mvn clean install ...
call mvn clean install -DskipTests -q
if %errorlevel% neq 0 (
    echo install failed
    exit /b %errorlevel%
)

echo starting JLShell ...
call mvn javafx:run -pl app

:end
endlocal
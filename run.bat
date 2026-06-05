@echo off
setlocal

set "JAVA_HOME=D:\Program Files\java\jdk-21.0.10_windows-x64_bin\jdk-21.0.10"

if "%1"=="--build" goto do_build
if "%1"=="-b" goto do_build

echo starting JLShell ...
call mvn javafx:run -pl app -s settings-aliyun.xml
goto end

:do_build
echo JDK: %JAVA_HOME%
echo mvn clean install ...
call mvn clean install -DskipTests -q -s settings-aliyun.xml
if %errorlevel% neq 0 (
    echo install failed
    exit /b %errorlevel%
)

echo starting JLShell ...
call mvn javafx:run -pl app -s settings-aliyun.xml

:end
endlocal
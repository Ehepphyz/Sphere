@echo off
setlocal enabledelayedexpansion

:: ==============================================================================
:: SPHERE BUILD & PACKAGING SCRIPT FOR WINDOWS
:: Output: dist\Sphere\Sphere.exe (standalone executable with embedded JRE 26)
:: Version: 2026.1.0
:: ==============================================================================

echo [1/7] Cleaning previous build artifacts...
if exist bin rmdir /s /q bin
if exist custom-jre rmdir /s /q custom-jre
if exist dist rmdir /s /q dist
if exist dist_input rmdir /s /q dist_input
if exist sphere.jar del /f /q sphere.jar
if exist sources_list.txt del /f /q sources_list.txt

if "%JAVA_HOME%"=="" (
    echo [ERROR] JAVA_HOME is not set! Please set JAVA_HOME to your JDK 26 path.
    exit /b 1
)

echo Using JAVA_HOME: %JAVA_HOME%

echo [2/7] Compiling Java source files...
mkdir bin
dir /s /b src\*.java > sources_list.txt
"%JAVA_HOME%\bin\javac" -d bin @sources_list.txt
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Java compilation failed.
    exit /b %ERRORLEVEL%
)

echo [3/7] Embedding static resources into classes...
xcopy /E /I /Y src bin > nul
del /S /Q bin\*.java > nul

echo [4/7] Creating executable JAR...
"%JAVA_HOME%\bin\jar" --create --file sphere.jar --main-class com.sphere.Sphere -C bin .
if %ERRORLEVEL% neq 0 (
    echo [ERROR] JAR creation failed.
    exit /b %ERRORLEVEL%
)

echo [5/7] Generating minimal Java 26 runtime (custom-jre)...
"%JAVA_HOME%\bin\jlink" ^
  --module-path "%JAVA_HOME%\jmods" ^
  --add-modules java.base,java.desktop,java.logging,java.scripting,java.management ^
  --strip-debug ^
  --compress zip-6 ^
  --no-header-files ^
  --no-man-pages ^
  --output custom-jre
if %ERRORLEVEL% neq 0 (
    echo [ERROR] jlink execution failed.
    exit /b %ERRORLEVEL%
)

echo [6/7] Preparing runtime workspace and assets...
mkdir dist_input
copy sphere.jar dist_input\ > nul
echo 2026.1.0 > dist_input\VERSION

:: Copy OS-specific configuration for Windows
if exist settings.conf.windows (
    copy settings.conf.windows dist_input\settings.conf > nul
) else if exist settings.conf (
    copy settings.conf dist_input\settings.conf > nul
)

if exist rootbackend xcopy /E /I /Y rootbackend dist_input\rootbackend > nul
if exist snippets xcopy /E /I /Y snippets dist_input\snippets > nul
if exist themes xcopy /E /I /Y themes dist_input\themes > nul
if exist WorkSpace xcopy /E /I /Y WorkSpace dist_input\WorkSpace > nul
if exist images xcopy /E /I /Y images dist_input\images > nul

:: Preserve empty directories
powershell -Command "Get-ChildItem -Path dist_input -Recurse -Directory | Where-Object { (Get-ChildItem \$.FullName).Count -eq 0 } | ForEach-Object { New-Item -Path \"\$(\$_.FullName)\.gitkeep\" -ItemType File }" > nul

echo [7/7] Packaging native Windows executable (Sphere.exe v2026.1.0)...
"%JAVA_HOME%\bin\jpackage" ^
  --name Sphere ^
  --app-version 2026.1.0 ^
  --type app-image ^
  --input dist_input ^
  --main-jar sphere.jar ^
  --main-class com.sphere.Sphere ^
  --runtime-image custom-jre ^
  --dest dist ^
  --verbose
if %ERRORLEVEL% neq 0 (
    echo [ERROR] jpackage execution failed.
    exit /b %ERRORLEVEL%
)

rmdir /s /q dist_input

echo.
echo ==============================================================================
echo SUCCESS: Windows executable created at: %CD%\dist\Sphere\Sphere.exe
echo ==============================================================================
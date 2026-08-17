@echo off
REM Set target directories and environment configurations
set FOLDER_DIR=D:\CloudLHC\LHCDevHub\HepForge\JavaDev\Sphere
set JAVA_HOME=D:\Engineering\Java25\jdk

REM Navigate to the exact workspace root directory
cd /d "%FOLDER_DIR%"

echo Cleaning bin directory...
if exist bin rmdir /s /q bin
mkdir bin

echo Compiling Java source files...
dir /s /b src\*.java > sources.txt
"%JAVA_HOME%\bin\javac.exe" -Xlint:unchecked -Xlint:deprecation -encoding UTF-8 -d bin @sources.txt

if %ERRORLEVEL% NEQ 0 (
    echo [!] Java compilation failed. Aborting build process.
    if exist sources.txt del /q sources.txt
    exit /b %ERRORLEVEL%
)

echo Copying application assets and resources...
if exist src\com\sphere\icons (
    xcopy /s /e /y /i "src\com\sphere\icons" "bin\com\sphere\icons\" >nul
)

if exist src\com\sphere\fonts (
    xcopy /s /e /y /i "src\com\sphere\fonts" "bin\com\sphere\fonts\" >nul
)

:: Copy all C++ resources (sources, headers, CMake files) for runtime compilation
if exist src\com\sphere\core\rootbackend (
    xcopy /s /e /y /i "src\com\sphere\core\rootbackend\*" "bin\com\sphere\core\rootbackend\" >nul
)

echo Packaging executable JAR file...
"%JAVA_HOME%\bin\jar.exe" cvfe Sphere.jar com.sphere.Sphere -C bin .

if %ERRORLEVEL% NEQ 0 (
    echo [!] JAR packaging failed. Aborting process.
    if exist sources.txt del /q sources.txt
    exit /b %ERRORLEVEL%
)

echo Cleaning up temporary build artifacts...
if exist sources.txt del /q sources.txt

echo Launching application...
"%JAVA_HOME%\bin\java.exe" -jar Sphere.jar
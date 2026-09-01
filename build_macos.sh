#!/bin/bash
# ==============================================================================
# SPHERE BUILD & PACKAGING SCRIPT FOR macOS
# Output: dist/Sphere.app (macOS Application Bundle with embedded JRE 26)
# Version: 2026.1.0
# ==============================================================================

set -e

echo "[1/7] Cleaning previous build artifacts..."
rm -rf bin custom-jre dist dist_input sphere.jar sources_list.txt

if [ -z "$JAVA_HOME" ]; then
    echo "[ERROR] JAVA_HOME environment variable is not set!"
    exit 1
fi

echo "Using JAVA_HOME: $JAVA_HOME"

echo "[2/7] Compiling Java source files..."
mkdir -p bin
find src -name "*.java" > sources_list.txt
"$JAVA_HOME/bin/javac" -d bin @sources_list.txt

echo "[3/7] Embedding static resources into classes..."
cp -r src/* bin/
find bin -name "*.java" -delete

echo "[4/7] Creating executable JAR..."
"$JAVA_HOME/bin/jar" --create --file sphere.jar --main-class com.sphere.Sphere -C bin .

echo "[5/7] Generating minimal Java 26 runtime (custom-jre)..."
"$JAVA_HOME/bin/jlink" \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.desktop,java.logging,java.scripting,java.management \
  --strip-debug \
  --compress zip-6 \
  --no-header-files \
  --no-man-pages \
  --output custom-jre

echo "[6/7] Preparing runtime workspace and assets..."
mkdir -p dist_input
cp sphere.jar dist_input/
echo "2026.1.0" > dist_input/VERSION

# Copy OS-specific configuration for macOS (using Linux config)
if [ -f settings.conf.linux ]; then
    cp settings.conf.linux dist_input/settings.conf
elif [ -f settings.conf ]; then
    cp settings.conf dist_input/settings.conf
fi

[ -d rootbackend ] && cp -r rootbackend dist_input/
[ -d snippets ] && cp -r snippets dist_input/
[ -d themes ] && cp -r themes dist_input/
[ -d WorkSpace ] && cp -r WorkSpace dist_input/
[ -d images ] && cp -r images dist_input/

# Preserve empty directories
find dist_input -type d -empty -exec touch {}/.gitkeep \;

echo "[7/7] Packaging native macOS Application Bundle (.app v2026.1.0)..."
"$JAVA_HOME/bin/jpackage" \
  --name Sphere \
  --app-version 2026.1.0 \
  --type app-image \
  --input dist_input \
  --main-jar sphere.jar \
  --main-class com.sphere.Sphere \
  --runtime-image custom-jre \
  --dest dist \
  --verbose

rm -rf dist_input

echo "=============================================================================="
echo "SUCCESS: macOS Bundle created at: $(pwd)/dist/Sphere.app"
echo "=============================================================================="
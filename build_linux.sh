#!/bin/bash
# ==============================================================================
# SPHERE BUILD & PACKAGING SCRIPT FOR LINUX (.DEB)
# Output: dist/sphere_2026.1.0-1_amd64.deb
# Version: 2026.1.0
# ==============================================================================

set -e

echo "[1/6] Cleaning previous build artifacts..."
rm -rf bin custom-jre dist dist_input sphere.jar sources_list.txt

if [ -z "$JAVA_HOME" ]; then
    echo "[ERROR] JAVA_HOME environment variable is not set!"
    exit 1
fi

echo "Using JAVA_HOME: $JAVA_HOME"

echo "[2/6] Compiling Java source files..."
mkdir -p bin
find src -name "*.java" > sources_list.txt
"$JAVA_HOME/bin/javac" -d bin @sources_list.txt

echo "[3/6] Embedding static resources into classes..."
cp -r src/* bin/
find bin -name "*.java" -delete

echo "[4/6] Creating executable JAR & custom JRE 26..."
"$JAVA_HOME/bin/jar" --create --file sphere.jar --main-class com.sphere.Sphere -C bin .

"$JAVA_HOME/bin/jlink" \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules java.base,java.desktop,java.logging,java.scripting,java.management \
  --strip-debug \
  --compress zip-6 \
  --no-header-files \
  --no-man-pages \
  --output custom-jre

echo "[5/6] Preparing payload with assets at root level..."
mkdir -p dist_input
cp sphere.jar dist_input/
echo "2026.1.0" > dist_input/VERSION

if [ -f settings.conf.linux ]; then
    cp settings.conf.linux dist_input/settings.conf
elif [ -f settings.conf ]; then
    cp settings.conf dist_input/settings.conf
fi

[ -d rootbackend ] && cp -r rootbackend dist_input/
[ -d snippets ] && cp -r snippets dist_input/
[ -d themes ] && cp -r themes dist_input/
[ -d WorkSpace ] && cp -r WorkSpace dist_input/

# Preserve empty directories
find dist_input -type d -empty -exec touch {}/.gitkeep \;

echo "[6/6] Packaging Debian package (.deb)..."
"$JAVA_HOME/bin/jpackage" \
  --name sphere \
  --app-version 2026.1.0 \
  --type deb \
  --input dist_input \
  --main-jar sphere.jar \
  --main-class com.sphere.Sphere \
  --runtime-image custom-jre \
  --dest dist \
  --linux-shortcut \
  --verbose

rm -rf dist_input

echo "=============================================================================="
echo "SUCCESS: Debian package generated in dist/"
echo "=============================================================================="
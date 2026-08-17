#!/usr/bin/env bash

# System configuration and variables
FOLDER_DIR="/path/to/CloudLHC/LHCDevHub/HepForge/JavaDev/Sphere"

# 1. Automatic JDK Detection (Java 22+)
find_jdk() {
    local candidate_javac=""

    # Check if JAVA_HOME is set and points to Java 22+
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        candidate_javac="$JAVA_HOME/bin/javac"
    else
        # Fallback to system-default javac
        candidate_javac=$(which javac 2>/dev/null)
    fi

    if [ -z "$candidate_javac" ]; then
        echo "[!] Error: No Java compiler (javac) found in PATH or JAVA_HOME."
        exit 1
    fi

    # Extract major version number
    local version_str=$("$candidate_javac" -version 2>&1 | awk '{print $2}')
    local major_version=$(echo "$version_str" | cut -d'.' -f1)

    if [ "$major_version" -lt 22 ]; then
        echo "[!] Error: Java version 22 or higher is required. Found version: $version_str ($candidate_javac)"
        exit 1
    fi

    # Set binary paths relative to detected javac
    JDK_BIN_DIR=$(dirname "$candidate_javac")
    echo "Using JDK at: $(dirname "$JDK_BIN_DIR") (Version $version_str)"
}

find_jdk

# 2. Navigate to workspace root
cd "$FOLDER_DIR" || { echo "[!] Failed to enter directory $FOLDER_DIR"; exit 1; }

# 3. Clean bin directory
echo "Cleaning bin directory..."
rm -rf bin
mkdir -p bin

# 4. Compile Java source files
echo "Compiling Java source files..."
find src -type f -name "*.java" > sources.txt

"$JDK_BIN_DIR/javac" -Xlint:unchecked -Xlint:deprecation -encoding UTF-8 -d bin @sources.txt

if [ $? -ne 0 ]; then
    echo "[!] Java compilation failed. Aborting build process."
    rm -f sources.txt
    exit 1
fi

# 5. Copy ALL non-Java resources (C++, Python, headers, icons, fonts, etc.) to bin/
echo "Copying all resource files (C++, Python, assets) to bin..."

if command -v rsync >/dev/null 2>&1; then
    # Use rsync to mirror src/ into bin/, excluding .java sources
    rsync -av --exclude="*.java" src/ bin/ >/dev/null
else
    # Fallback with find + cp if rsync is not installed
    (cd src && find . -type f ! -name "*.java" -exec cp --parents {} ../bin/ \;)
fi

# 6. Package executable JAR file
echo "Packaging executable JAR file..."
"$JDK_BIN_DIR/jar" cvfe Sphere.jar com.sphere.Sphere -C bin .

if [ $? -ne 0 ]; then
    echo "[!] JAR packaging failed. Aborting process."
    rm -f sources.txt
    exit 1
fi

# Verification of resources inside the generated JAR
echo "Verifying contents of Sphere.jar..."
echo "--- Native/Script files included in JAR ---"
"$JDK_BIN_DIR/jar" tf Sphere.jar | grep -E "\.(cpp|hpp|c|h|py|cmake|txt)$" || echo "Note: No C++/Python files found in archive listing."
echo "-------------------------------------------"

# 7. Cleanup temporary build artifacts
echo "Cleaning up temporary build artifacts..."
rm -f sources.txt

# 8. Launch application
echo "Launching application..."
"$JDK_BIN_DIR/java" -jar Sphere.jar
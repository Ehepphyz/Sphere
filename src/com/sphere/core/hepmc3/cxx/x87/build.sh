#!/usr/bin/env bash
# Assembles bridge/lib/sphere_x87.S for the two x86-64 calling conventions (Win64, System V) and writes the
# bytes Sphere copies into executable memory to ../X87Code.java; with --lib, also builds the
# shared library of this system next to this script (for C++ and ROOT macros, Python's ctypes,
# Julia's ccall, Fortran's bind(c)).
#
#   ./build.sh            regenerate X87Code.java (needs gcc or clang, objcopy, nm, od)
#   ./build.sh --lib      and build sphere_x87.dll / libsphere_x87.so / libsphere_x87.dylib
#
# On Windows, run it from MSYS2 (UCRT64) or Git Bash with C:\msys64\ucrt64\bin on the PATH.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
src="$here/../../../bridge/lib/sphere_x87.S"   # the source lives with the other engines' bridge libraries
CC=${CC:-gcc}
OBJCOPY=${OBJCOPY:-objcopy}
NM=${NM:-nm}
out="$here/../X87Code.java"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

hex() { od -An -v -tx1 "$1" | tr -d ' \n'; }

symbols() {
    # "offset name" of the global functions, the leading underscore of Mach-O dropped
    "$NM" "$1" | awk '$2 == "T" { print $1, $3 }' | while read -r off name; do
        printf '%s:%d\n' "${name#_}" "$((16#$off))"
    done | sort -t: -k2 -n
}

for abi in WIN64 SYSV; do
    "$CC" -c -DSPHERE_X87_$abi -o "$tmp/$abi.o" "$src"
    "$OBJCOPY" -O binary -j .text "$tmp/$abi.o" "$tmp/$abi.bin"
    symbols "$tmp/$abi.o" > "$tmp/$abi.sym"
done

java_symbols() {
    local first=1
    while IFS= read -r s; do
        if [ $first -eq 1 ]; then first=0; else printf ', '; fi
        printf '"%s"' "$s"
    done < "$1"
}

java_hex() {
    # 96 hex digits (48 bytes) per line
    hex "$1" | fold -w 96 | sed 's/^/        + "/; s/$/"/' | sed '1s/^        + /        /'
}

sum=$(cat "$src" | (sha256sum 2>/dev/null || shasum -a 256) | cut -c1-16)
{
cat <<EOF
package com.sphere.core.hepmc3.cxx;

/**
 * The machine code of the Sphere x87 ABI, bridge/lib/sphere_x87.S assembled for the two x86-64
 * calling conventions by hepmc3/cxx/x87/build.sh ($("$CC" --version | head -1)): do not edit, run
 * x87/build.sh. The code is position independent and without relocations; X87Abi copies it
 * into executable memory.
 */
final class X87Code {

    private X87Code() {
    }

    /** The first 16 hex digits of the SHA-256 of the source assembled. */
    static final String SOURCE_SHA256 = "$sum";

    /** Windows (Win64 convention). */
    static final String WIN64 =
$(java_hex "$tmp/WIN64.bin");

    static final String[] WIN64_SYMBOLS = {$(java_symbols "$tmp/WIN64.sym")};

    /** Linux, macOS Intel, BSD (System V convention). */
    static final String SYSV =
$(java_hex "$tmp/SYSV.bin");

    static final String[] SYSV_SYMBOLS = {$(java_symbols "$tmp/SYSV.sym")};
}
EOF
} > "$out"
echo "wrote $out ($(stat -c %s "$tmp/WIN64.bin" 2>/dev/null || wc -c < "$tmp/WIN64.bin") + $(wc -c < "$tmp/SYSV.bin") bytes)"

if [ "${1:-}" = "--lib" ]; then
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) lib="$here/sphere_x87.dll"; "$CC" -shared -o "$lib" "$src" ;;
        Darwin*)              lib="$here/libsphere_x87.dylib"; "$CC" -shared -o "$lib" "$src" ;;
        *)                    lib="$here/libsphere_x87.so"; "$CC" -shared -fPIC -o "$lib" "$src" ;;
    esac
    echo "built $lib"
fi

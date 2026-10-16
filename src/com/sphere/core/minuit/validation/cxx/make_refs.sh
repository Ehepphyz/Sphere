#!/bin/bash
# Rebuilds the references of TMinuitTests (':minuit validate'): ROOT's TMinuit
# running tmprobe.cxx and the scenarios, its standard output, error and exit
# code, packed with the scenarios into ../refdata/tminuit-<platform>.zip.
#
#   make_refs.sh <ROOT source tree>   root-master's math/minuit/src/TMinuit.cxx,
#                                     compiled unchanged against tmroot.h
#   make_refs.sh --root               ROOT's own libMinuit (root-config on the PATH)
#
# Both give the same text (checked with ROOT 6.36 on Linux). g++ -O2
# -ffp-contract=off, as for the Minuit2 references; MSYS2/MinGW on Windows
# (UCRT), gcc on Linux. The C library's exp, sin... differ between platforms,
# hence one zip per platform. tmzero.cxx gives the programs memory that starts
# at zero, as Java's arrays do: TMinuit reads some of its work arrays before
# writing them, and the reference must not depend on what the heap held.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) PLATFORM=windows-mingw ;;
  Darwin) PLATFORM=macos-clang ;;
  *) PLATFORM=linux-gcc ;;
esac
[ $# -ge 1 ] || { echo "usage: $0 <ROOT source tree> | --root" >&2; exit 2; }
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
CXX="g++ -O2 -ffp-contract=off -std=c++17"
if [ "$1" = --root ]; then
  for p in tmprobe tmscript; do
    $CXX $(root-config --cflags) "$HERE/$p.cxx" "$HERE/tmzero.cxx" -o "$WORK/$p" $(root-config --libs) -lMinuit
  done
else
  SRC=$1/math/minuit
  mkdir -p "$WORK/shim"
  cp "$HERE/tmroot.h" "$WORK/shim/"
  for h in TROOT TList TMath TError TPluginManager TClass TNamed TMethodCall TString TObject Rtypes RtypesCore; do
    printf '#pragma once\n#include "tmroot.h"\n' > "$WORK/shim/$h.h"
  done
  $CXX -I"$WORK/shim" -I"$SRC/inc" -c "$SRC/src/TMinuit.cxx" -o "$WORK/TMinuit.o"
  for p in tmprobe tmscript; do
    $CXX -I"$WORK/shim" -I"$SRC/inc" "$HERE/$p.cxx" "$WORK/TMinuit.o" "$HERE/tmzero.cxx" -o "$WORK/$p"
  done
fi
R="$WORK/refs"
mkdir -p "$R"
set +e
"$WORK/tmprobe" < /dev/null > "$R/Probe.out" 2> "$R/Probe.err"; echo $? > "$R/Probe.exit"
for f in "$HERE"/scenarios/*.txt; do
  n=$(basename "$f" .txt)
  cp "$f" "$R/"
  "$WORK/tmscript" "$f" < /dev/null > "$R/$n.out" 2> "$R/$n.err"; echo $? > "$R/$n.exit"
done
set -e
ZIP="$HERE/../refdata/tminuit-$PLATFORM.zip"
mkdir -p "$HERE/../refdata"
rm -f "$ZIP"
if command -v zip > /dev/null; then
  (cd "$R" && zip -q -X "$ZIP" ./*)
else
  ZW=$ZIP
  command -v cygpath > /dev/null && ZW=$(cygpath -w "$ZIP")
  (cd "$R" && jar --create --no-manifest --file "$ZW" ./*)
fi
echo "$ZIP: $(ls "$R" | wc -l) files ($(cat "$R"/*.out | wc -l) lines of output)"

#!/bin/bash
# Rebuilds the references of ':clhep validate' (../refdata/clhep-refs.zip): CLHEP 2.4.7.2 built from its
# sources, then the reference programs (*_ref.cc, the C++ twins of validation.*Reference) and the CLHEP tests
# validation.ClhepTests ports, their outputs recorded for this platform:
#
#   make_refs.sh <CLHEP source tree> <work folder>    on Windows in MSYS2's UCRT64 shell, or in Git Bash with
#                                                     C:/msys64/ucrt64/bin first on PATH (g++, cmake, ninja,
#                                                     mingw-w64-ucrt-x86_64-gsl), and on Linux (g++, cmake,
#                                                     ninja, libgsl-dev), into the same work folder (under WSL,
#                                                     a Linux folder builds faster: its linux/refs and
#                                                     linux/tests copied next to mingw/ afterwards)
#   python3 pack_refs.py <work folder> ../refdata/clhep-refs.zip    once both platforms have run
#
# The source tree is CLHEP's archive unpacked whole: CMake configures its tests' scripts (*.sh.in) too.
#
# The C library's mathematical functions differ between the platforms in their last bits, and the port calls
# this machine's: MinGW's outputs are kept in full, Linux's as the lines that differ. CLHEP is built Release
# (-O3 -DNDEBUG), as its CMake does; the reference programs with g++ -O2 -DNDEBUG, CLHEP's tests without
# NDEBUG, as CLHEP builds them. On MinGW, drand48 is CLHEP's own (Random/src/drand48.src, linked into every
# library and program), M_PI needs _USE_MATH_DEFINES, and std::max and std::min are brought in as CLHEP's
# sources expect. A test that aborts (testRotation's failed assert on MinGW) has the status bash gives it: 127
# for the UCRT's fast fail, 134 for glibc's SIGABRT. testRotationAxis alone runs some 13 s.
#
# genfun_ref.cc and GenfunReference.java are written together by gen_genfun_ref.py, GeometryReference.java by
# gen_geometry_ref.py. CLHEP develop's adaptor templates (ParamToArgAdaptor...) cannot be instantiated, and
# its Fields package is not in its build: patch_adaptors.py makes copies the reference programs compile
# against, and XF.cc is compiled with them; HermitePolynomial.cc, missing from CLHEP's library, likewise.
set -e
# On Windows the folders are named as g++ and CMake read them (C:/...), not as the shell does (/c/..., /tmp/...)
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) PLATFORM=mingw; W=-W ;;
  Linux) PLATFORM=linux; W= ;;
  *) echo "The references are MinGW's and Linux's: run this on one of them."; exit 1 ;;
esac
HERE=$(cd "$(dirname "$0")" && pwd $W)
SRC=$(cd "$1" && pwd $W)
mkdir -p "$2"
WORK=$(cd "$2" && pwd $W)
B="$WORK/build-$PLATFORM"
OUT="$WORK/$PLATFORM"
P="$WORK/patch-$PLATFORM"
mkdir -p "$B" "$OUT/refs" "$OUT/tests" "$OUT/bin"

# 1. CLHEP, static, Release: the library alone (CLHEPS), as CMake would also build CLHEP's own test programs, of
#    which some cannot link on MinGW (thread_local members out of a DLL); the tests are compiled below
PRE=""
EXTRA=""
CMAKE_FLAGS=()
if [ "$PLATFORM" = mingw ]; then
  cp "$HERE/compat_prelude.h" "$HERE/drand48_shim.h" "$B/"
  printf '#include "%s/Random/src/drand48.src"\n' "$SRC" > "$B/drand48_impl.cc"
  g++ -O2 -c "$B/drand48_impl.cc" -o "$B/drand48_impl.o"
  ar rcs "$B/libdrand48shim.a" "$B/drand48_impl.o"
  PRE="-include $B/drand48_shim.h -include $B/compat_prelude.h -D_USE_MATH_DEFINES"
  EXTRA="$B/libdrand48shim.a"
  CMAKE_FLAGS=(-DCMAKE_CXX_FLAGS="-include $B/drand48_shim.h" -DCMAKE_CXX_STANDARD_LIBRARIES="$B/libdrand48shim.a")
fi
(cd "$B" && cmake -G Ninja -DCMAKE_BUILD_TYPE=Release -DCLHEP_BUILD_STATIC_LIBS=ON "${CMAKE_FLAGS[@]}" "$SRC" > cmake.log \
  && ninja CLHEPS > ninja.log)
LIB=$(ls "$B"/lib/libCLHEP-*.a | head -1)
echo "CLHEP: $LIB"

# 2. The adaptor templates and Fields' header, as the programs compile against them
python3 "$HERE/patch_adaptors.py" "$B" "$P"
mkdir -p "$P/CLHEP/Fields"
cp "$SRC/Fields/Fields/XF.h" "$P/CLHEP/Fields/"

# 3. The reference programs
for n in vector engines dists evaluator geometry matrix genfun randobj; do
  extra=""
  libs=""
  case $n in
    genfun) extra="$SRC/GenericFunctions/src/HermitePolynomial.cc"; libs="-lgsl -lgslcblas -lm" ;;
    randobj) extra="$SRC/Fields/src/XF.cc" ;;
  esac
  g++ -O2 -std=c++17 -DNDEBUG $PRE -I"$P" -I"$B" "$HERE/${n}_ref.cc" $extra "$LIB" $EXTRA $libs -o "$OUT/bin/${n}_ref"
  (cd "$OUT/refs" && "$OUT/bin/${n}_ref" > "${n}_ref.out" 2> "${n}_ref.err")
  echo "${n}_ref: $(wc -l < "$OUT/refs/${n}_ref.out") lines"
done

# 4. CLHEP's tests that ClhepTests ports, each in a folder of its own for the files it writes. They are compiled
#    from the root of the source tree, so that assert() names them as Vector/test/testRotation.cc on either
#    system; the Matrix tests with INSTALLATION_CHECK, as CTest builds them. The input is ours when inputs/ has
#    one (testEvaluator, testRandom), CLHEP's own otherwise; it is kept beside the outputs for pack_refs.py.
for pt in Geometry/testBug6740 Matrix/testBug104262 Matrix/testBug6176 Evaluator/testEvaluator \
          GenericFunctions/testGenericFunctions Units/testUnits Fields/testXF Random/testCLHEP155 Vector/eulerProb \
          Random/testBug73093 Random/testRandom Random/testZiggurat \
          Matrix/testBug6181 Matrix/testMatrix Matrix/testInversion \
          Vector/testThreeVector Vector/testLorentzVector Vector/testRotation Vector/testRotationAxis Vector/testSubscripts \
          Random/testSaveEngineStatus Random/testSaveSharedEngines Random/testStaticStreamSave Random/testInstanceRestore \
          Random/testAnonymousEngineRestore Random/testVectorSave Random/testEngineCopy Random/testRanecuSequence \
          Random/testDistCopy Random/testRandDists Geometry/testBasicVector3D RandomObjects/testVectorDists; do
  pkg=${pt%/*}
  t=${pt#*/}
  extra=""
  def=""
  [ "$pkg" = Fields ] && extra="$SRC/Fields/src/XF.cc"
  [ "$pkg" = Matrix ] && def="-DINSTALLATION_CHECK"
  (cd "$SRC" && g++ -O2 -std=c++17 $def $PRE -I"$P" -I"$B" -I"$SRC/$pkg/test" "$pkg/test/$t.cc" $extra "$LIB" $EXTRA -o "$OUT/bin/$t")
  input=/dev/null
  if [ -f "$HERE/inputs/$t.input" ]; then
    input="$HERE/inputs/$t.input"
  elif [ -f "$SRC/$pkg/test/$t.input" ]; then
    input="$SRC/$pkg/test/$t.input"
  fi
  rm -rf "$OUT/tests/$t.run" "$OUT/tests/$t.input"
  mkdir -p "$OUT/tests/$t.run"
  [ "$input" != /dev/null ] && cp "$input" "$OUT/tests/$t.input"
  (cd "$OUT/tests/$t.run" && { status=0; "$OUT/bin/$t" < "$input" > "../$t.out" 2> "../$t.err" || status=$?; echo $status > "../$t.exit"; })
  echo "$t: exit $(cat "$OUT/tests/$t.exit"), $(wc -l < "$OUT/tests/$t.out") lines, files: $(ls "$OUT/tests/$t.run" | tr '\n' ' ')"
done
echo "Outputs of $PLATFORM in $OUT; once both platforms have run: python3 $HERE/pack_refs.py $WORK $HERE/../refdata/clhep-refs.zip"

"""Packs refdata/clhep-refs.zip from the work folder make_refs.sh wrote on MinGW and on Linux: the reference programs'
outputs (mingw/<name>.out in full, linux/<name>.patch the lines that differ on Linux as "index<TAB>line") and the
tests ClhepTests ports (tests/<system>/<id>.out, .err, .exit, every file each wrote under tests/<system>/<id>/, and
tests/inputs/<id>.input, the standard input it was given), every line end "\n".

    python3 pack_refs.py <work folder> <clhep-refs.zip>
"""
import pathlib
import sys
import zipfile

WORK = pathlib.Path(sys.argv[1])
OUT = sys.argv[2]
NAMES = ["vector_ref", "engines_ref", "dists_ref", "evaluator_ref", "geometry_ref", "matrix_ref", "genfun_ref", "randobj_ref"]
TESTS = ["testBug6740", "testBug104262", "testBug6176", "testEvaluator", "testGenericFunctions", "testUnits", "testXF",
         "testCLHEP155", "eulerProb", "testBug73093", "testRandom", "testZiggurat",
         "testBug6181", "testMatrix", "testInversion",
         "testThreeVector", "testLorentzVector", "testRotation", "testRotationAxis", "testSubscripts",
         "testSaveEngineStatus", "testSaveSharedEngines", "testStaticStreamSave", "testInstanceRestore",
         "testAnonymousEngineRestore", "testVectorSave", "testEngineCopy", "testRanecuSequence",
         "testDistCopy", "testRandDists", "testBasicVector3D", "testVectorDists"]


def text(p):
    return p.read_bytes().replace(b"\r", b"")


def lines(p):
    out = text(p).decode("latin-1").split("\n")
    if out and out[-1] == "":
        out.pop()
    return out


with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    z.writestr("README.txt", "CLHEP 2.4.7.2: the outputs of the reference programs (validation.*Reference) and of CLHEP's own "
               "tests, built with MinGW-w64 (UCRT) under mingw/, with g++ on Linux (glibc) under linux/. Made by "
               "validation/cxx/make_refs.sh and pack_refs.py.\n")
    for n in NAMES:
        m = lines(WORK / "mingw" / "refs" / (n + ".out"))
        l = lines(WORK / "linux" / "refs" / (n + ".out"))
        z.writestr("mingw/%s.out" % n, ("\n".join(m) + "\n").encode("latin-1"))
        patch = ["%d\t%s" % (i, l[i]) for i in range(len(l)) if i >= len(m) or m[i] != l[i]]
        z.writestr("linux/%s.patch" % n, ("\n".join(patch) + ("\n" if patch else "")).encode("latin-1"))
    for t in TESTS:
        for system in ("mingw", "linux"):
            d = WORK / system / "tests"
            for ext in (".out", ".err", ".exit"):
                z.writestr("tests/%s/%s%s" % (system, t, ext), text(d / (t + ext)))
            for f in sorted((d / (t + ".run")).iterdir()):
                z.writestr("tests/%s/%s/%s" % (system, t, f.name), text(f))
        inp = WORK / "mingw" / "tests" / (t + ".input")
        if inp.exists():
            z.writestr("tests/inputs/%s.input" % t, text(inp))
print(pathlib.Path(OUT).stat().st_size, "bytes")

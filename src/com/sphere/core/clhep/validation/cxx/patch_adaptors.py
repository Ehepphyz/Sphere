"""CLHEP develop's three adaptor templates still define the _clone() and operator()(const AbsFunction&) that
FUNCTION_OBJECT_DEF no longer declares, so they cannot be instantiated. Copies for the reference program,
their clone() as the macro's (new X(*this)) and the stale definitions dropped."""
import pathlib
import re
import sys

src = pathlib.Path(sys.argv[1]) / "CLHEP" / "GenericFunctions"
dst = pathlib.Path(sys.argv[2]) / "CLHEP" / "GenericFunctions"
dst.mkdir(parents=True, exist_ok=True)
for name in ("ParamToArgAdaptor", "DoubleParamToArgAdaptor", "SymToArgAdaptor"):
    (dst / (name + ".hh")).write_text((src / (name + ".hh")).read_text(encoding="latin-1"), encoding="latin-1")
    t = (src / (name + ".icc")).read_text(encoding="latin-1")
    # each definition: optional 'template <class F>' + optional 'inline' + signature + body up to the closing brace at column 0
    t = re.sub(r"template\s*<class F>\s*(inline\s*)?\w+\s*%s<F>::operator\(\)\s*\(\s*const Abs(Function|Parameter)\s*&\s*\w+\)\s*const\s*\{.*?\n\}[ \t]*\n" % name, "", t, flags=re.S)
    t = re.sub(r"template\s*<class F>\s*(inline\s*)?AbsFunction\s*\*\s*%s<F>::_clone\s*\(\s*\)\s*const\s*\{.*?\n\}[ \t]*\n" % name, "", t, flags=re.S)
    t, n = re.subn(r"return\s*\(%s<F>\s*\*\)\s*_clone\(\);" % name, "return new %s<F>(*this);" % name, t)
    assert n == 1, name
    assert "_clone" not in t and "FunctionComposition" not in t, name
    (dst / (name + ".icc")).write_text(t, encoding="latin-1")
    print(name, "patched")

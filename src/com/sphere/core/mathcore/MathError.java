package com.sphere.core.mathcore;

import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.rootbase.TError;

/**
 * The messages of ROOT's mathematics libraries (math/mathcore Math/Error.h,
 * built with ROOT): MATH_INFO_MSG, MATH_WARN_MSG, MATH_ERROR_MSG and their
 * _VAL and _MSGVAL forms, which go through ROOT's error handler with the
 * location prefixed by "ROOT::Math::".
 */
public final class MathError {

    private MathError() {
    }

    private static String loc(String loc) {
        return "ROOT::Math::" + loc;
    }

    /** ROOT::Math::Util::ToString: what an ostream writes (6 significant digits for a double). */
    public static String toStr(Object x) {
        if (x instanceof Double || x instanceof Float) return CFormat.sprintf("%g", ((Number) x).doubleValue());
        return String.valueOf(x);
    }

    public static void infoMsg(String loc, String str) {
        TError.Info(loc(loc), "%s", str);
    }

    public static void warnMsg(String loc, String str) {
        TError.Warning(loc(loc), "%s", str);
    }

    public static void errorMsg(String loc, String str) {
        TError.Error(loc(loc), "%s", str);
    }

    /** MATH_..._VAL(loc, x): "x = value", name being the C++ expression. */
    public static void infoVal(String loc, String name, Object x) {
        TError.Info(loc(loc), "%s", name + " = " + toStr(x));
    }

    public static void warnVal(String loc, String name, Object x) {
        TError.Warning(loc(loc), "%s", name + " = " + toStr(x));
    }

    public static void errorVal(String loc, String name, Object x) {
        TError.Error(loc(loc), "%s", name + " = " + toStr(x));
    }

    /** MATH_..._MSGVAL(loc, txt, x): "txt; x = value". */
    public static void infoMsgVal(String loc, String txt, String name, Object x) {
        TError.Info(loc(loc), "%s", txt + "; " + name + " = " + toStr(x));
    }

    public static void warnMsgVal(String loc, String txt, String name, Object x) {
        TError.Warning(loc(loc), "%s", txt + "; " + name + " = " + toStr(x));
    }

    public static void errorMsgVal(String loc, String txt, String name, Object x) {
        TError.Error(loc(loc), "%s", txt + "; " + name + " = " + toStr(x));
    }
}

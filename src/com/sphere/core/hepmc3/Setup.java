package com.sphere.core.hepmc3;

import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * HepMC3's configuration, shared by every event and thread, and the printing
 * macros of Errors.h: an error goes to cerr as "ERROR::...", a warning to
 * cout as "WARNING::...", each only when printing is on and its level is at
 * most the level set (1000 for errors, 750 for warnings by default); debug
 * messages "DEBUG(n)::..." when n is at most the debug level (5).
 */
public final class Setup {

    /** Default maxUlps for AlmostEqual2sComplement (double precision). */
    public static final int DEFAULT_DOUBLE_ALMOST_EQUAL_MAXULPS = 10;

    /** Default threshold for comparing double variables. */
    public static final double DOUBLE_EPSILON = 10e-20;

    private static volatile boolean printingErrors = true;
    private static volatile boolean printingWarnings = true;
    private static volatile int debugLevel = 5;
    private static volatile int errorsLevel = 1000;
    private static volatile int warningsLevel = 750;

    private Setup() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static boolean printErrors() {
        return printingErrors;
    }

    public static void setPrintErrors(boolean flag) {
        printingErrors = flag;
    }

    public static int errorsLevel() {
        return errorsLevel;
    }

    public static void setErrorsLevel(int level) {
        errorsLevel = level;
    }

    public static boolean printWarnings() {
        return printingWarnings;
    }

    public static void setPrintWarnings(boolean flag) {
        printingWarnings = flag;
    }

    public static int warningsLevel() {
        return warningsLevel;
    }

    public static void setWarningsLevel(int level) {
        warningsLevel = level;
    }

    public static int debugLevel() {
        return debugLevel;
    }

    public static void setDebugLevel(int level) {
        debugLevel = level;
    }

    /* ---- the macros of Errors.h -------------------------------------- */

    /** HEPMC3_ERROR(message). */
    public static void error(String message) {
        if (printingErrors) StdStreams.cerr().println("ERROR::" + message);
    }

    /** HEPMC3_ERROR_LEVEL(level, message). */
    public static void error(int level, String message) {
        if (errorsLevel >= level && printingErrors) StdStreams.cerr().println("ERROR::" + message);
    }

    /** HEPMC3_WARNING(message). */
    public static void warning(String message) {
        if (printingWarnings) StdStreams.cout().println("WARNING::" + message);
    }

    /** HEPMC3_WARNING_LEVEL(level, message). */
    public static void warning(int level, String message) {
        if (warningsLevel >= level && printingWarnings) StdStreams.cout().println("WARNING::" + message);
    }

    /** HEPMC3_DEBUG(level, message): built in, as HepMC3 is unless HEPMC3_RELEASE_VERSION is defined. */
    public static void debug(int level, String message) {
        if (debugLevel >= level) StdStreams.cout().println("DEBUG(" + level + ")::" + message);
    }

    /** Whether a debug message of this level would print, to avoid building it otherwise. */
    public static boolean debugging(int level) {
        return debugLevel >= level;
    }
}

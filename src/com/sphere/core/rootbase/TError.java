package com.sphere.core.rootbase;

import com.sphere.core.hepmc3.cxx.CFormat;

import java.io.PrintStream;

/**
 * ROOT's error reporting (core/foundation TError, core/base
 * TErrorDefaultHandler): Info, Warning, Error, Break, SysError and Fatal with
 * a location and a printf format, ignored below {@link #gErrorIgnoreLevel},
 * and written by the default handler on the standard error as ROOT writes
 * them: "Warning in &lt;TMinuit::Contour&gt;: message".
 *
 * <p>Where ROOT aborts the program (Fatal, or a level at or above
 * {@link #gErrorAbortLevel}), this throws an {@link IllegalStateException}
 * after the message: Sphere keeps running.
 */
public final class TError {

    private TError() {
    }

    public static final int kUnset = -1;
    public static final int kPrint = 0;
    public static final int kInfo = 1000;
    public static final int kWarning = 2000;
    public static final int kError = 3000;
    public static final int kBreak = 4000;
    public static final int kSysError = 5000;
    public static final int kFatal = 6000;

    /** Messages below this level are ignored; kUnset means ROOT's default, Root.ErrorIgnoreLevel = Print. */
    public static volatile int gErrorIgnoreLevel = kUnset;

    /** Messages at or above this level abort (throw here). */
    public static volatile int gErrorAbortLevel = kSysError + 1;

    /** An error handler, as ROOT's SetErrorHandler takes. */
    @FunctionalInterface
    public interface Handler {
        void handle(int level, boolean abort, String location, String msg);
    }

    private static volatile Handler handler = TError::DefaultErrorHandler;
    private static volatile PrintStream output;

    /** Installs a handler; returns the previous one (null restores the default). */
    public static Handler SetErrorHandler(Handler h) {
        final Handler old = handler;
        handler = h == null ? TError::DefaultErrorHandler : h;
        return old;
    }

    public static Handler GetErrorHandler() {
        return handler;
    }

    /** Where the default handler writes (System.err when null). */
    public static void setOutput(PrintStream ps) {
        output = ps;
    }

    /** The default handler: the message on the standard error, flushed. */
    public static void DefaultErrorHandler(int level, boolean abort, String location, String msg) {
        if (gErrorIgnoreLevel == kUnset) gErrorIgnoreLevel = kPrint;
        if (level < gErrorIgnoreLevel) return;
        String type = "?";
        if (level >= kInfo) type = "Info";
        if (level >= kWarning) type = "Warning";
        if (level >= kError) type = "Error";
        if (level >= kBreak) type = "\n *** Break ***";
        if (level >= kSysError) type = "SysError";
        if (level >= kFatal) type = "Fatal";
        final String smsg;
        if (level >= kPrint && level < kInfo) smsg = msg;
        else if (level >= kBreak && level < kSysError) smsg = type + " " + msg;
        else if (location == null || location.isEmpty()) smsg = type + ": " + msg;
        else smsg = type + " in <" + location + ">: " + msg;
        System.out.flush();
        final PrintStream e = output != null ? output : System.err;
        e.print(smsg + "\n");
        e.flush();
        if (abort) {
            e.print("aborting\n");
            e.flush();
            throw new IllegalStateException(smsg);
        }
    }

    /** ErrorHandler(level, location, fmt, ...): formats and hands the message to the handler. */
    public static void ErrorHandler(int level, String location, String fmt, Object... args) {
        if (fmt == null) fmt = "no error message provided";
        final String msg = args.length == 0 ? CFormat.sprintf(fmt) : CFormat.sprintf(fmt, args);
        final boolean abort = level >= gErrorAbortLevel;
        handler.handle(level, abort, location, msg);
    }

    public static void Info(String location, String fmt, Object... args) {
        ErrorHandler(kInfo, location, fmt, args);
    }

    public static void Warning(String location, String fmt, Object... args) {
        ErrorHandler(kWarning, location, fmt, args);
    }

    public static void Error(String location, String fmt, Object... args) {
        ErrorHandler(kError, location, fmt, args);
    }

    public static void Break(String location, String fmt, Object... args) {
        ErrorHandler(kBreak, location, fmt, args);
    }

    public static void SysError(String location, String fmt, Object... args) {
        ErrorHandler(kSysError, location, fmt, args);
    }

    public static void Fatal(String location, String fmt, Object... args) {
        ErrorHandler(kFatal, location, fmt, args);
    }
}

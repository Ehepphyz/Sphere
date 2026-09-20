package com.sphere.components.variables;

/**
 * Collects a Python script's variables without the script knowing.
 *
 * Sphere builds the command line, so it can run the script inside a namespace of
 * its own and write down what is left in it once the script is done. The script
 * is not touched, imports nothing and calls nothing; it runs as __main__ with
 * its own arguments, exactly as it would have on its own.
 *
 * The namespace belongs to the wrapper rather than to runpy, so a script that
 * fails halfway still leaves behind what it had built up to that point.
 */
public final class PythonProbe {

    /** Where the file is written, read by the wrapper from the environment. */
    public static final String FOLDER_VARIABLE = "SPHERE_VARS";

    /** Whether a script is run inside the wrapper at all. */
    private static volatile boolean wrapping = true;

    public static boolean isWrapping() {
        return wrapping;
    }

    /** Turned off, a script is launched exactly as it was before, and says nothing. */
    public static void setWrapping(boolean on) {
        wrapping = on;
    }

    private PythonProbe() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The wrapper, given to the interpreter as -c, with the script after it. */
    public static final String WRAPPER = """
        import os, sys

        _sphere_path = sys.argv[1]
        sys.argv = sys.argv[1:]
        sys.path.insert(0, os.path.dirname(os.path.abspath(_sphere_path)))


        def _sphere_escape(text):
            return (str(text).replace("\\\\", "\\\\\\\\").replace("\\t", "\\\\t")
                    .replace("\\r", "").replace("\\n", "\\\\n"))


        def _sphere_dump(namespace):
            folder = os.environ.get("SPHERE_VARS") or "variables"
            try:
                os.makedirs(folder, exist_ok=True)
                lines = ["# name\\ttype\\tvalue"]
                for name in sorted(namespace):
                    if name.startswith("_"):
                        continue
                    value = namespace[name]
                    if callable(value) or type(value).__name__ == "module":
                        continue
                    try:
                        text = repr(value)
                    except Exception:
                        text = "<unreadable>"
                    if len(text) > 200:
                        text = text[:197] + "..."
                    lines.append("%s\\t%s\\t%s" % (_sphere_escape(name),
                                                  _sphere_escape(type(value).__name__),
                                                  _sphere_escape(text)))
                with open(os.path.join(folder, "python.vars"), "w",
                          encoding="utf-8") as out:
                    out.write("\\n".join(lines) + "\\n")
            except Exception:
                pass


        _sphere_namespace = {"__name__": "__main__", "__file__": _sphere_path,
                             "__builtins__": __builtins__}
        _sphere_status = 0
        try:
            with open(_sphere_path, "rb") as _sphere_source:
                _sphere_code = compile(_sphere_source.read(), _sphere_path, "exec")
            exec(_sphere_code, _sphere_namespace)
        except SystemExit:
            raise
        except BaseException as _sphere_error:
            # The script's own traceback, without the frame of this wrapper: an
            # error has to read the same as it would have without Sphere.
            import traceback
            _sphere_trace = _sphere_error.__traceback__
            if _sphere_trace is not None and _sphere_trace.tb_next is not None:
                _sphere_trace = _sphere_trace.tb_next
            traceback.print_exception(type(_sphere_error), _sphere_error, _sphere_trace)
            _sphere_status = 1
        finally:
            _sphere_dump(_sphere_namespace)

        if _sphere_status:
            sys.exit(_sphere_status)
        """;

    /** The folder the wrapper writes into, as an absolute path. */
    public static String folder() {
        try {
            return VariablesPanel.instance().folder().toString();
        } catch (Exception unreachable) {
            return null;
        }
    }
}

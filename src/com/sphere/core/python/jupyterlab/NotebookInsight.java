package com.sphere.core.python.jupyterlab;

import com.sphere.components.editor.EditorDiagnostic;
import com.sphere.components.editor.TokenKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the kernel's analysis of the notebook means for each cell: squiggles on
 * the names that will fail, and why; the live kinds the highlighter colors names
 * with; and the line a traceback points at.
 *
 * The analysis (sphere_kernel_tools.analyze) reads every code cell with Python's
 * own symbol tables, against the live namespace. It tells a name nothing defines
 * (a NameError whatever the order) from one defined only further down (Restart &
 * Run All stops there) and from one that exists only because of the order the
 * cells were run in, or of a cell since deleted: the reasons a notebook works
 * today and not after a restart.
 */
final class NotebookInsight {

    static final String SOURCE = "Notebook analysis";

    private static final Pattern IPYTHON_FRAME = Pattern.compile("Cell In\\[(\\d+)\\], line (\\d+)");
    private static final Pattern DRIVER_FRAME = Pattern.compile("File \"<cell (\\d+)>\", line (\\d+)");

    private NotebookInsight() {
    }

    /** The kinds the kernel reported, merged over the cells, as token kinds. */
    static Map<String, TokenKind> kinds(List<Map<String, Object>> results) {
        Map<String, TokenKind> out = new HashMap<>();
        for (Map<String, Object> r : results) {
            if (r.get("kinds") instanceof Map<?, ?> kinds) {
                for (Map.Entry<?, ?> e : kinds.entrySet()) {
                    TokenKind k = switch (String.valueOf(e.getValue())) {
                        case "module" -> TokenKind.NAMESPACE;
                        case "class" -> TokenKind.TYPE;
                        case "function" -> TokenKind.FUNCTION;
                        case "data" -> TokenKind.VARIABLE;
                        default -> null;
                    };
                    if (k != null) {
                        out.put(String.valueOf(e.getKey()), k);
                    }
                }
            }
        }
        return out;
    }

    /**
     * What the kernel holds under each name the cells use, for the semantic
     * colors: its kind, runtime family (tensor, array, frame, jagged array,
     * histogram, ROOT object, model, figure...), type and summary, why it is stale,
     * and the cell that made it. An older kernel tool sends kinds alone; the
     * family then stays unknown.
     */
    static Map<String, com.sphere.components.editor.PySemantics.Live> live(List<Map<String, Object>> results) {
        Map<String, com.sphere.components.editor.PySemantics.Live> out = new HashMap<>();
        for (Map<String, Object> r : results) {
            Map<?, ?> types = map(r.get("types"));
            Map<?, ?> stale = map(r.get("stale"));
            for (Map.Entry<?, ?> e : map(r.get("kinds")).entrySet()) {
                String name = String.valueOf(e.getKey());
                Map<?, ?> t = map(types.get(name));
                Object why = stale.get(name);
                out.put(name, new com.sphere.components.editor.PySemantics.Live(
                    String.valueOf(e.getValue()),
                    com.sphere.components.editor.PySemantics.Family.of(t.get("f") == null ? null : String.valueOf(t.get("f"))),
                    t.get("t") == null ? "" : String.valueOf(t.get("t")),
                    t.get("i") == null ? "" : String.valueOf(t.get("i")),
                    why == null ? null : String.valueOf(why),
                    t.get("c") instanceof Number n ? n.intValue() : -1));
            }
        }
        return out;
    }

    /** True when the cell would fail run top to bottom, or only works because of the run order. */
    static boolean fragile(Map<String, Object> r) {
        return r.containsKey("syntax") || nonEmpty(r.get("undefined")) || nonEmpty(r.get("later"))
               || nonEmpty(r.get("order"));
    }

    private static boolean nonEmpty(Object o) {
        return o instanceof List<?> l ? !l.isEmpty() : o instanceof Map<?, ?> m && !m.isEmpty();
    }

    /**
     * The squiggles of one cell. {@code isCode} says whether an offset is code (not
     * a string or a comment); {@code cellName} describes another cell by its index.
     */
    static List<EditorDiagnostic> diagnostics(String text, Map<String, Object> r, IntPredicate isCode,
                                              IntFunction<String> cellName) {
        List<EditorDiagnostic> out = new ArrayList<>();
        if (r.get("syntax") instanceof List<?> s && s.size() >= 3) {
            int line = number(s.get(0));
            int col = number(s.get(1));
            out.add(new EditorDiagnostic(Math.max(1, line), Math.max(1, col), 1, EditorDiagnostic.Severity.ERROR,
                                         "syntax", "SyntaxError: " + s.get(2), SOURCE));
            return out;
        }
        for (Object n : list(r.get("undefined"))) {
            mark(out, text, String.valueOf(n), isCode, EditorDiagnostic.Severity.ERROR, "undefined",
                 "'" + n + "' is not defined: no cell, no variable of the kernel and no builtin provides it.");
        }
        for (Map.Entry<?, ?> e : map(r.get("later")).entrySet()) {
            mark(out, text, String.valueOf(e.getKey()), isCode, EditorDiagnostic.Severity.WARNING, "defined-below",
                 "'" + e.getKey() + "' is only defined below, by " + cellName.apply(number(e.getValue()))
                 + ": run top to bottom (Restart & Run All), this cell stops here.");
        }
        for (Map.Entry<?, ?> e : map(r.get("order")).entrySet()) {
            int at = number(e.getValue());
            mark(out, text, String.valueOf(e.getKey()), isCode, EditorDiagnostic.Severity.WARNING, "hidden-state",
                 "'" + e.getKey() + "' exists only in the kernel: no cell above defines it"
                 + (at >= 0 ? " (" + cellName.apply(at) + ", below, does)" : " (no cell does any more)")
                 + ". The notebook will not give the same result after a restart.");
        }
        for (Map.Entry<?, ?> e : map(r.get("pending")).entrySet()) {
            mark(out, text, String.valueOf(e.getKey()), isCode, EditorDiagnostic.Severity.INFO, "not-run",
                 "'" + e.getKey() + "' is defined by " + cellName.apply(number(e.getValue())) + ", above, which has not run yet.");
        }
        for (Object n : list(r.get("latent"))) {
            mark(out, text, String.valueOf(n), isCode, EditorDiagnostic.Severity.WARNING, "undefined-when-called",
                 "'" + n + "' is used inside a function but nothing defines it: calling it raises NameError.");
        }
        return out;
    }

    /** Underlines the name where it is read: a word of its own, not an attribute, in code. */
    private static void mark(List<EditorDiagnostic> out, String text, String name, IntPredicate isCode,
                             EditorDiagnostic.Severity severity, String code, String message) {
        Matcher m = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "(?!\\w)").matcher(text);
        int found = 0;
        while (m.find() && found < 20) {
            int at = m.start();
            if (!isCode.test(at)) {
                continue;
            }
            int line = 1;
            int lineStart = 0;
            for (int i = 0; i < at; i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    lineStart = i + 1;
                }
            }
            out.add(new EditorDiagnostic(line, at - lineStart + 1, name.length(), severity, code, message, SOURCE));
            found++;
        }
    }

    /**
     * The line of this cell a traceback points at: IPython names a cell
     * "Cell In[n], line k", Sphere's driver "<cell id>". The deepest frame in the
     * cell is where it failed.
     */
    static EditorDiagnostic errorLine(String ename, String evalue, List<String> traceback, Integer count) {
        int line = -1;
        for (String raw : traceback) {
            String t = AnsiText.strip(raw);
            Matcher m = IPYTHON_FRAME.matcher(t);
            while (m.find()) {
                if (count == null || Integer.parseInt(m.group(1)) == count) {
                    line = Integer.parseInt(m.group(2));
                }
            }
            Matcher d = DRIVER_FRAME.matcher(t);
            while (d.find()) {
                line = Integer.parseInt(d.group(2));
            }
        }
        if (line < 1) {
            return null;
        }
        return new EditorDiagnostic(line, 0, 0, EditorDiagnostic.Severity.ERROR, ename,
                                    ename + ": " + AnsiText.strip(evalue), "Kernel");
    }

    private static int number(Object o) {
        return o instanceof Number n ? n.intValue() : -1;
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static Map<?, ?> map(Object o) {
        return o instanceof Map<?, ?> m ? m : Map.of();
    }
}

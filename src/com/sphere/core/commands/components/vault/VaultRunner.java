package com.sphere.components.vault;

import com.sphere.components.editor.LanguageSpec;
import com.sphere.components.vault.VaultAnalysis.Style;
import com.sphere.core.rootbackend.RootUserPipeline;
import com.sphere.utils.AppLogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns an item and what its form holds into the console line that runs it.
 *
 * The line is the one the user would type, and goes through the router the
 * same way, so a run from the vault and a run from the console are one thing.
 * The two sides of the bracket keep the meaning they have everywhere in Sphere:
 *
 *   ::py  -u -O  [@ gen.py  data.lhe --events 500 --plot]
 *         ^^^^^          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *         the interpreter       the script
 *
 * A ROOT macro has two ways to run. In the engine, the line is ROOT's own
 * .x macro.C(500, "out.root"), the call written in C++ by the vault, and the
 * macro's objects stay in the session. As a process, it is
 * ::root -b [@ macro.C 500 out.root], which RootMacroLauncher turns into the
 * same call in a root.exe of its own.
 */
public final class VaultRunner {

    public enum Mode {
        ENGINE("Engine", "In the ROOT engine: :root .x, the objects stay in the session"),
        PROCESS("root.exe", "In a root process of its own: ::root [@ ...], ROOT_DIR in settings.conf");

        public final String label;
        public final String tip;

        Mode(String label, String tip) {
            this.label = label;
            this.tip = tip;
        }
    }

    /** What the form holds. */
    public static final class Request {
        /** Outside the bracket: the interpreter's or the compiler's own options. */
        public String options = "";
        /** One per parameter, in order; empty means the default. */
        public final List<String> values = new ArrayList<>();
        /** Inside the bracket, after the parameters: anything the form does not know. */
        public String extra = "";
        public Mode mode = Mode.ENGINE;
        /** ACLiC's suffix: "", "+", "++", "+g", "++g". */
        public String aclic = "";

        String value(int index) {
            return index < values.size() && values.get(index) != null ? values.get(index) : "";
        }
    }

    private VaultRunner() {
    }

    // ---- what runs it ---------------------------------------------------------

    /** The console prefix that runs this item, or null when nothing does. */
    public static String prefix(VaultItem item) {
        final VaultAnalysis info = item.info;
        if (info.runner != null && !info.runner.isBlank()) return info.runner;
        if (info.header) return null;
        if (item.isRootMacro()) return "::root";
        final LanguageSpec spec = info.language;
        if (spec == LanguageSpec.PYTHON) return "::py";
        if (spec == LanguageSpec.JULIA) return "::jul";
        if (spec == LanguageSpec.FORTRAN) return "::fort";
        if (spec == LanguageSpec.JAVASCRIPT) return "::js";
        if (spec == LanguageSpec.CPP) return "::cpp";
        return null;
    }

    public static boolean runnable(VaultItem item) {
        return item != null && prefix(item) != null && item.info.lines > 0;
    }

    /** Whether the line goes to the running engine rather than a process of its own. */
    public static boolean usesEngine(VaultItem item, Request request) {
        return item.isRootMacro() && request.mode == Mode.ENGINE
            && (item.info.runner == null || item.info.runner.equals("::root"));
    }

    /** Whether the options field means anything for this run. */
    public static boolean takesOptions(VaultItem item, Request request) {
        return runnable(item) && !usesEngine(item, request);
    }

    /** What the options field is for, said as its placeholder. */
    public static String optionsHint(VaultItem item) {
        final String prefix = prefix(item);
        if (prefix == null) return "";
        return switch (prefix) {
            case "::py" -> "python options: -u -O -X dev";
            case "::root" -> "root options: -b -l -n";
            case "::cpp" -> "compiler flags: -O2 -g -std=c++20";
            case "::jul" -> "julia options: -t auto --project=.";
            case "::fort" -> "compiler flags: -O2 -fcheck=all";
            case "::js" -> "node options: --enable-source-maps";
            default -> "options for " + prefix;
        };
    }

    /** The options worth one click for this runner. */
    public static List<String> quickOptions(VaultItem item) {
        final String prefix = prefix(item);
        if (prefix == null) return List.of();
        return switch (prefix) {
            case "::py" -> List.of("-u", "-O", "-B");
            case "::root" -> List.of("-b", "-l", "-n");
            case "::cpp" -> List.of("-O2", "-g", "-std=c++20", "-Wall");
            case "::jul" -> List.of("-t auto", "-O3", "--project=.");
            case "::fort" -> List.of("-O2", "-g", "-fcheck=all");
            default -> List.of();
        };
    }

    // ---- the reference ---------------------------------------------------------

    /**
     * How the file is named inside the bracket: by its short name when the
     * console's own lookup finds this very file under it, by its full path
     * otherwise, so the line always runs what was chosen.
     */
    public static String reference(VaultItem item, String project) {
        try {
            final String found = com.sphere.core.snippets.SnippetResolver.resolve(item.relative, project);
            if (found != null && new File(found).getAbsoluteFile().toPath().normalize().equals(item.path)
                    && item.relative.indexOf(' ') < 0) {
                return item.relative;
            }
        } catch (RuntimeException ignored) {
            // The full path below is always right.
        }
        return item.path.toString();
    }

    /** The bare tag, [@ gen.py], which the console resolves to the file's path. */
    public static String tag(VaultItem item, String project) {
        return "[@ " + reference(item, project) + "]";
    }

    // ---- the arguments -----------------------------------------------------------

    /** What goes inside the bracket after the file, for a process run. */
    public static String arguments(VaultItem item, Request r) {
        final VaultAnalysis info = item.info;
        final List<String> out = new ArrayList<>();
        final List<VaultParam> params = info.params;
        switch (info.style) {
            case OPTIONS -> {
                for (int i = 0; i < params.size(); i++) {
                    final VaultParam p = params.get(i);
                    final String typed = r.value(i).strip();
                    if (!p.isOption()) {
                        if (!typed.isEmpty()) out.add(quote(typed));
                        continue;
                    }
                    if (p.type() == VaultParam.Type.BOOL) {
                        final String state = typed.isEmpty() ? p.defaultValue() : typed;
                        if (!state.equalsIgnoreCase(p.defaultValue())) out.add(p.flag());
                    } else if (p.cppType().equals("count")) {
                        final int times = parseInt(typed, 0);
                        for (int k = 0; k < Math.min(times, 9); k++) out.add(p.flag());
                    } else if (!typed.isEmpty() && !typed.equals(p.defaultValue())) {
                        out.add(p.flag());
                        out.add(quote(typed));
                    }
                }
            }
            case POSITIONAL, CALL -> {
                final int last = lastMeaningful(params, r);
                for (int i = 0; i <= last; i++) {
                    String value = effective(params.get(i), r.value(i));
                    // RootMacroLauncher writes it into a C++ string, where a
                    // backslash starts an escape; ROOT takes / on Windows too.
                    if (item.isRootMacro() && params.get(i).type() == VaultParam.Type.PATH) {
                        value = value.replace('\\', '/');
                    }
                    out.add(quote(value));
                }
            }
            case NONE -> { }
        }
        if (!r.extra.isBlank()) out.add(r.extra.strip());
        return String.join(" ", out);
    }

    /** The C++ call .x makes in the engine: "(500, \"out.root\")", or "" when none is needed. */
    public static String call(VaultItem item, Request r) {
        final List<VaultParam> params = item.info.params;
        final List<String> args = new ArrayList<>();
        if (item.info.style == Style.CALL) {
            final int last = lastMeaningful(params, r);
            for (int i = 0; i <= last; i++) {
                args.add(cppLiteral(params.get(i), effective(params.get(i), r.value(i))));
            }
        }
        if (!r.extra.isBlank()) args.add(r.extra.strip());
        return args.isEmpty() ? "" : "(" + String.join(", ", args) + ")";
    }

    /**
     * The last parameter that has to be written. Those after it keep their
     * defaults by being left out; one before it is written even when unchanged,
     * since C++ and argv read their arguments by position.
     */
    private static int lastMeaningful(List<VaultParam> params, Request r) {
        int last = -1;
        for (int i = 0; i < params.size(); i++) {
            final VaultParam p = params.get(i);
            final String typed = r.value(i).strip();
            if (p.defaultValue().isEmpty() && (p.required() || !typed.isEmpty())) last = i;
            else if (!typed.isEmpty() && !typed.equals(p.defaultValue())) last = i;
        }
        return last;
    }

    private static String effective(VaultParam p, String typed) {
        final String t = typed == null ? "" : typed.strip();
        return t.isEmpty() ? p.defaultValue() : t;
    }

    private static String cppLiteral(VaultParam p, String value) {
        return switch (p.type()) {
            case INT, REAL -> value.isEmpty() ? "0" : value;
            case BOOL -> value.equalsIgnoreCase("true") || value.equals("1") || value.equalsIgnoreCase("kTRUE")
                ? "true" : "false";
            case RAW -> value.isEmpty() ? "{}" : value;
            case PATH -> cppString(value.replace('\\', '/'));
            default -> cppString(value);
        };
    }

    static String cppString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** A token as the console's tokenizer reads it back: quoted only when it has to be. */
    static String quote(String value) {
        if (value.isEmpty()) return "\"\"";
        if (value.matches("[^\\s\"\\[\\]]+")) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    // ---- the lines ------------------------------------------------------------------

    /** The whole line that runs the item, or null when nothing runs it. */
    public static String command(VaultItem item, Request r, String project) {
        final String prefix = prefix(item);
        if (prefix == null) return null;
        if (usesEngine(item, r)) {
            return ":root .x " + RootUserPipeline.forCling(item.path) + r.aclic + call(item, r);
        }
        final StringBuilder line = new StringBuilder(prefix);
        if (!r.options.isBlank()) line.append(' ').append(r.options.strip());
        line.append(" [@ ").append(reference(item, project));
        if (item.isRootMacro()) line.append(r.aclic);
        final String args = arguments(item, r);
        if (!args.isEmpty()) line.append(' ').append(args);
        return line.append(']').toString();
    }

    /** The bracket alone, for a line the user has started typing. */
    public static String block(VaultItem item, Request r, String project) {
        final StringBuilder block = new StringBuilder("[@ ").append(reference(item, project));
        if (item.isRootMacro()) block.append(r.aclic);
        final String args = arguments(item, r);
        if (!args.isEmpty()) block.append(' ').append(args);
        return block.append(']').toString();
    }

    /** .L: the macro's functions defined in the engine, nothing run. */
    public static String load(VaultItem item, Request r) {
        return ":root .L " + RootUserPipeline.forCling(item.path) + r.aclic;
    }

    /** Compiled by ACLiC and loaded, so a mistake shows before a long run. */
    public static String compile(VaultItem item, Request r) {
        final String suffix = r.aclic.isEmpty() ? "+" : r.aclic;
        return ":root .L " + RootUserPipeline.forCling(item.path) + suffix;
    }

    /** What is wrong with the form, or null when it can run as it is. */
    public static String problem(VaultItem item, Request r) {
        final List<VaultParam> params = item.info.params;
        for (int i = 0; i < params.size(); i++) {
            final VaultParam p = params.get(i);
            final String typed = r.value(i);
            if (p.required() && typed.isBlank() && p.defaultValue().isEmpty()) {
                return p.label() + " is required";
            }
            if (!typed.isBlank() && !p.accepts(typed)) {
                return p.label() + " expects " + p.type().name().toLowerCase(Locale.ROOT)
                    + (p.type() == VaultParam.Type.CHOICE ? " " + String.join("|", p.choices()) : "");
            }
        }
        return null;
    }

    /**
     * Sends a line to the console as if it were typed, off the event thread.
     * Returns false when there is no console to send it to.
     */
    public static boolean submit(String command) {
        final com.sphere.core.CommandRouter router = com.sphere.core.CommandRouter.active();
        if (router == null || command == null) {
            return false;
        }
        AppLogger.recall(command);
        final Thread runner = new Thread(() -> {
            try {
                router.processInput(command);
            } catch (Exception failed) {
                AppLogger.error("Run failed: " + failed.getMessage());
            }
        }, "sphere-vault-run");
        runner.setDaemon(true);
        runner.start();
        return true;
    }
}

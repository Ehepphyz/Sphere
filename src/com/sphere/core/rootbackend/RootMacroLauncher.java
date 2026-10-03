package com.sphere.core.rootbackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The command line of "::root [flags] [@ macro.C [args]]".
 *
 * The two sides of the bracket mean what they mean for ::py and ::cpp. What
 * sits before it is for the root executable (-b, -l, -n, -t, --web=off). What
 * sits inside is the macro and its arguments, and a macro takes its arguments
 * the one way ROOT has: as the arguments of its function, macro.C(3, "abc").
 *
 * That call is not put on the command line. On Windows a double quote inside
 * an argument does not survive the trip to the new process, so string
 * arguments would arrive bare. A one-line launcher macro carries the call
 * instead, and the command line holds nothing but a file name.
 */
public final class RootMacroLauncher {

    /** An argument C++ reads as a number or a literal, and so is left unquoted. */
    private static final Pattern BARE = Pattern.compile(
        "[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?[fFlLuU]*|true|false|nullptr");

    private static final AtomicInteger RUNS = new AtomicInteger();

    private RootMacroLauncher() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /**
     * The command that runs the macro with its arguments.
     *
     * A -q is added when the flags have none: without it ROOT waits at its
     * prompt once the macro is done, and nothing in the console answers it,
     * so the run would never end.
     *
     * @param folder where the launcher is written; {@link #discard} removes it after the run
     */
    public static List<String> command(String root, List<String> flags, String macro,
                                       List<String> arguments, Path folder) throws IOException {
        return command(root, flags, macro, arguments, folder, null);
    }

    /**
     * The same, and every canvas the macro leaves open is then saved into
     * plots, the Plots tab's folder, as macro_canvas.png. A macro run in batch
     * draws its canvases and nobody sees them unless it saves them itself;
     * saved here, each one becomes a picture in the tab, editable like any
     * other. Run again, the same canvas replaces its picture.
     */
    public static List<String> command(String root, List<String> flags, String macro,
                                       List<String> arguments, Path folder, Path plots) throws IOException {
        final String name = "sphere_launch_" + RUNS.incrementAndGet();
        final Path launcher = folder.resolve(name + ".C");
        // "macro.C+c" asks ACLiC to build the library and nothing more: it is
        // loaded with .L then, since .x would try to run what was not loaded.
        final String suffix = RootAclic.split(macro)[1];
        final boolean buildOnly = RootAclic.compileOnly(suffix);
        if (buildOnly && !arguments.isEmpty()) {
            com.sphere.utils.AppLogger.warn(macro + " only builds the library; the arguments "
                + String.join(" ", arguments) + " are not used.");
        }
        final String line = buildOnly ? ".L " + call(macro, List.of()) : ".x " + call(macro, arguments);
        final String stem = Path.of(RootAclic.split(macro)[0]).getFileName().toString().replaceFirst("\\.[^.]+$", "");
        final String canvases = plots == null || buildOnly ? ""
            : "   // Every canvas the macro left, into the Plots tab's folder.\n"
            + "   TIter next(gROOT->GetListOfCanvases());\n"
            + "   while (TObject *o = next()) {\n"
            + "      if (TCanvas *c = dynamic_cast<TCanvas *>(o)) {\n"
            + "         c->SaveAs(TString::Format(\"%s/%s_%s.png\", "
            + literal(plots.toAbsolutePath().toString().replace('\\', '/')) + ", " + literal(stem)
            + ", c->GetName()));\n"
            + "      }\n"
            + "   }\n";
        Files.writeString(launcher,
            "// Written by Sphere for one run of " + Path.of(RootAclic.split(macro)[0]).getFileName()
            + "; removed after it.\n"
            + "#include \"TROOT.h\"\n#include \"TCanvas.h\"\n#include \"TString.h\"\n\n"
            + "void " + name + "() {\n"
            + "   gROOT->ProcessLine(" + literal(line) + ");\n"
            + canvases
            + "}\n", StandardCharsets.UTF_8);

        final List<String> command = new ArrayList<>();
        command.add(root);
        command.addAll(flags);
        if (!flags.contains("-q")) {
            command.add("-q");
        }
        command.add(launcher.toAbsolutePath().toString());
        return command;
    }

    /** macro.C(3, "abc"), or macro.C+(3, "abc") for ACLiC: what ".x" is given. */
    static String call(String macro, List<String> arguments) {
        final String[] parts = RootAclic.split(macro);
        final StringBuilder out = new StringBuilder(parts[0].replace('\\', '/')).append(parts[1]);
        if (arguments.isEmpty()) {
            return out.toString();
        }
        out.append('(');
        for (int k = 0; k < arguments.size(); k++) {
            if (k > 0) out.append(", ");
            final String a = arguments.get(k);
            out.append(BARE.matcher(a).matches() ? a : literal(a));
        }
        return out.append(')').toString();
    }

    /** A C++ string literal holding the text as it is. */
    static String literal(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Removes the launcher once the run is over. */
    public static void discard(Path folder) {
        if (folder == null) return;
        try (var walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException leftBehind) {
            // A temporary folder the system will collect on its own.
        }
    }
}

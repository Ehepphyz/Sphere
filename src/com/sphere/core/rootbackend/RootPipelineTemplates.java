package com.sphere.core.rootbackend;

/**
 * The C++ a pipeline starts life as.
 *
 * What the window generates already compiles and already declares itself, so
 * the user never faces an empty file and never has to remember the shape the
 * engine expects. Two marks divide the file: what is above the first and below
 * the second follows the form, and what lies between them belongs to the user
 * and is never rewritten.
 */
public final class RootPipelineTemplates {

    /** Below this line the file belongs to the user. */
    public static final String BODY_MARK =
        "// ---- your code below this line is kept when the form changes ----";

    /**
     * Below this line the file belongs to Sphere again.
     *
     * The timing function calls the entry point, so it can only be written once
     * the entry point exists. Leaving it inside the user's half would freeze it
     * at the signature it was first written for, and it would stop compiling the
     * moment an input was added.
     */
    public static final String TAIL_MARK =
        "// ---- written by Sphere below this line; do not edit ----";

    private RootPipelineTemplates() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The whole source for a new pipeline. */
    public static String source(RootPipelineManifest manifest) {
        return source(manifest, null);
    }

    /** The same, starting from a ready-made body rather than a placeholder. */
    public static String source(RootPipelineManifest manifest,
                                RootPipelineRecipes.Recipe recipe) {
        return header(manifest) + body(manifest, recipe) + tail(manifest);
    }

    /**
     * Everything below the user's code, which the form owns again: a
     * monitored pipeline's entry point and report, then the timing, the
     * inspector and the probe.
     */
    public static String tail(RootPipelineManifest manifest) {
        final String monitor = RootPipelineExtras.tail(manifest);
        if (!canBeTimed(manifest) && monitor.isEmpty()) {
            return "";
        }
        return "\n" + TAIL_MARK + "\n" + monitor + benchFunction(manifest)
             + watchFunction(manifest) + probeFunction(manifest);
    }

    /**
     * Everything above the user's code: the marker, the includes, the manifest
     * the engine asks for, and the entry point's declaration.
     */
    public static String header(RootPipelineManifest manifest) {
        final StringBuilder out = new StringBuilder(1024);
        out.append(manifest.markerLine()).append('\n');
        out.append("//\n");
        out.append("// ").append(manifest.description == null || manifest.description.isBlank()
                                 ? "A Sphere pipeline." : manifest.description).append('\n');
        out.append("//\n");
        out.append("// Built into includes/").append(libraryFileName(manifest))
           .append(" and loaded at startup.\n");
        if (manifest.threadSafe) {
            out.append("// Declared thread safe: keep no state between calls, so that\n");
            out.append("// :root mt on can run it on several entries at once.\n");
        } else {
            out.append("// NOT declared thread safe: :root mt on will say so before using it.\n");
        }
        out.append(RootPipelineExtras.usage(manifest));
        out.append('\n');

        out.append("#include <string>\n");
        if (canBeTimed(manifest) || RootPipelineExtras.monitored(manifest)) {
            // The timing needs a clock, and the health check needs to be able to
            // ask whether a number is one.
            out.append("#include <chrono>\n");
            out.append("#include <cmath>\n");
        }
        for (String header : RootPipelineModules.headersOf(modulesFor(manifest))) {
            // A header already in quotes is one Sphere writes beside the source,
            // and the compiler has to look there rather than on the system path.
            if (header.startsWith("\"")) {
                out.append("#include ").append(header).append('\n');
            } else {
                out.append("#include <").append(header).append(">\n");
            }
        }
        if (manifest.openMp) {
            out.append("#include <omp.h>\n");
        }
        out.append('\n');

        out.append("// What Sphere asks the library for once it is loaded. Written from\n");
        out.append("// the same form as the line at the top of this file. The pipeline's\n");
        out.append("// name is part of the symbol, so two loaded libraries cannot answer\n");
        out.append("// for each other.\n");
        out.append("extern \"C\" const char *").append(manifest.exportName()).append("() {\n");
        out.append("  return \"").append(escape(manifest.encode())).append("\";\n");
        out.append("}\n\n");

        if (canBeTimed(manifest)) {
            out.append(watchBlock(manifest));
        }
        out.append(RootPipelineExtras.header(manifest));

        out.append(BODY_MARK).append('\n');
        return out.toString();
    }

    /** The namespace a pipeline records its intermediate values in. */
    private static String watchSpace(RootPipelineManifest manifest) {
        return "sphere_watch_" + manifest.name;
    }

    /** The C symbol the variable inspector is exported under. */
    public static String watchName(RootPipelineManifest manifest) {
        return "sphere_pipeline_" + manifest.name + "_watch";
    }

    /** The C symbol the health check and the scan are exported under. */
    public static String probeName(RootPipelineManifest manifest) {
        return "sphere_pipeline_" + manifest.name + "_probe";
    }

    /**
     * What SPHERE_WATCH is, written above the user's code so it can be used.
     *
     * A pipeline is a function whose result is one number, and when that number
     * is wrong there is no way to see which step made it so. Printing would
     * write to a terminal that does not exist when Sphere runs from a jar, and
     * a debugger cannot easily be attached to a library the interpreter loaded.
     * So the value is named where it is computed, and Sphere reads the names
     * back. Off, it costs a comparison; it is never removed from the build, so
     * the code that is inspected is the code that runs.
     */
    private static String watchBlock(RootPipelineManifest manifest) {
        final String space = watchSpace(manifest);
        return "\n"
             + "// Name any value with SPHERE_WATCH(x) and the Inspect panel\n"
             + "// shows what it held. Nothing is recorded unless Sphere asks.\n"
             + "namespace " + space + " {\n"
             + "  struct Seen { const char *name; double value; };\n"
             + "  inline Seen seen[64];\n"
             + "  inline int count = 0;\n"
             + "  inline bool watching = false;\n"
             + "  inline void note(const char *what, double value) {\n"
             + "    if (watching && count < 64) {\n"
             + "      seen[count].name = what;\n"
             + "      seen[count].value = value;\n"
             + "      ++count;\n"
             + "    }\n"
             + "  }\n"
             + "}\n"
             + "#undef SPHERE_WATCH\n"
             + "#define SPHERE_WATCH(v) " + space + "::note(#v, (double) (v))\n";
    }

    /** Calls the pipeline once and hands back every value it named. */
    private static String watchFunction(RootPipelineManifest manifest) {
        if (!canBeTimed(manifest)) {
            return "";
        }
        final String space = watchSpace(manifest);
        final StringBuilder out = new StringBuilder(768);
        out.append("\nextern \"C\" const char *").append(watchName(manifest)).append('(');
        for (int i = 0; i < manifest.inputs.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append("double a").append(i);
        }
        out.append(") {\n");
        out.append("  ").append(space).append("::count = 0;\n");
        out.append("  ").append(space).append("::watching = true;\n");
        out.append("  const double answer = (double) (").append(RootPipelineExtras.callee(manifest)).append('(');
        for (int i = 0; i < manifest.inputs.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('(').append(manifest.inputs.get(i).type()).append(") a").append(i);
        }
        out.append("));\n");
        out.append("  ").append(space).append("::watching = false;\n");
        out.append("  static std::string text;\n");
        out.append("  text.clear();\n");
        out.append("  for (int i = 0; i < ").append(space).append("::count; ++i) {\n");
        out.append("    text += ").append(space).append("::seen[i].name;\n");
        out.append("    text += '=';\n");
        out.append("    text += std::to_string(").append(space).append("::seen[i].value);\n");
        out.append("    text += '\\n';\n");
        out.append("  }\n");
        out.append("  text += \"(returned)=\";\n");
        out.append("  text += std::to_string(answer);\n");
        out.append("  return text.c_str();\n");
        out.append("}\n");
        return out.toString();
    }

    /**
     * Two questions in one entry point: is it healthy, and what shape is it.
     *
     * A pipeline that answers NaN on one entry in ten thousand poisons every
     * histogram downstream and is almost impossible to find afterward, so the
     * inputs are swept and the bad answers counted. Sweeping one input while
     * the others are held is the other half: a step or a spike in that curve is
     * what a wrong branch of an expression looks like from outside.
     */
    private static String probeFunction(RootPipelineManifest manifest) {
        if (!canBeTimed(manifest)) {
            return "";
        }
        final int n = manifest.inputs.size();
        final StringBuilder out = new StringBuilder(1024);
        out.append("\nextern \"C\" const char *").append(probeName(manifest))
           .append("(int which, double low, double high, int points, double held) {\n");
        out.append("  static std::string text;\n");
        out.append("  text.clear();\n");
        out.append("  if (points < 1) {\n    points = 1;\n  }\n");
        out.append("  double argument[").append(Math.max(1, n)).append("];\n");
        out.append("  if (which < 0) {\n");
        out.append("    // Health: every input swept together, pseudo-randomly.\n");
        // 64 bits on every platform: unsigned long is 32 bits on Windows, where
        // (seed >> 11) / 2^53 then never leaves 1e-10 and the sweep tests one point.
        out.append("    unsigned long long seed = 88172645463325252ULL;\n");
        out.append("    long bad = 0, huge = 0;\n");
        out.append("    double smallest = 0.0, largest = 0.0, total = 0.0;\n");
        out.append("    bool first = true;\n");
        out.append("    for (int step = 0; step < points; ++step) {\n");
        out.append("      for (int i = 0; i < ").append(n).append("; ++i) {\n");
        out.append("        seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17;\n");
        out.append("        const double r = (double) (seed >> 11) * (1.0 / 9007199254740992.0);\n");
        out.append("        argument[i] = low + r * (high - low);\n");
        out.append("      }\n");
        out.append("      const double v = (double) (").append(RootPipelineExtras.callee(manifest)).append('(');
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('(').append(manifest.inputs.get(i).type()).append(") argument[")
               .append(i).append(']');
        }
        out.append("));\n");
        out.append("      if (std::isnan(v)) {\n        ++bad;\n        continue;\n      }\n");
        out.append("      if (std::isinf(v)) {\n        ++huge;\n        continue;\n      }\n");
        out.append("      if (first) {\n");
        out.append("        smallest = v;\n        largest = v;\n        first = false;\n");
        out.append("      } else {\n");
        out.append("        if (v < smallest) { smallest = v; }\n");
        out.append("        if (v > largest) { largest = v; }\n");
        out.append("      }\n");
        out.append("      total += v;\n");
        out.append("    }\n");
        out.append("    const long good = (long) points - bad - huge;\n");
        out.append("    text += \"tried=\" + std::to_string(points) + \"\\n\";\n");
        out.append("    text += \"nan=\" + std::to_string(bad) + \"\\n\";\n");
        out.append("    text += \"inf=\" + std::to_string(huge) + \"\\n\";\n");
        out.append("    text += \"min=\" + std::to_string(smallest) + \"\\n\";\n");
        out.append("    text += \"max=\" + std::to_string(largest) + \"\\n\";\n");
        out.append("    text += \"mean=\" + std::to_string(good > 0 ? total / (double) good : 0.0);\n");
        out.append("    text += \"\\n\";\n");
        out.append("    return text.c_str();\n");
        out.append("  }\n\n");
        out.append("  // Scan: one input walked across its range, the rest held.\n");
        out.append("  if (which >= ").append(n).append(") {\n    which = 0;\n  }\n");
        out.append("  for (int step = 0; step < points; ++step) {\n");
        out.append("    const double t = points == 1 ? 0.0 : (double) step / (double) (points - 1);\n");
        out.append("    const double x = low + t * (high - low);\n");
        out.append("    for (int i = 0; i < ").append(n).append("; ++i) {\n");
        out.append("      argument[i] = (i == which) ? x : held;\n");
        out.append("    }\n");
        out.append("    const double v = (double) (").append(RootPipelineExtras.callee(manifest)).append('(');
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('(').append(manifest.inputs.get(i).type()).append(") argument[")
               .append(i).append(']');
        }
        out.append("));\n");
        out.append("    text += std::to_string(x) + \" \" + std::to_string(v) + \"\\n\";\n");
        out.append("  }\n");
        out.append("  return text.c_str();\n");
        out.append("}\n");
        return out.toString();
    }

    /** The C symbol the timing entry point is exported under. */
    public static String benchName(RootPipelineManifest manifest) {
        return "sphere_pipeline_" + manifest.name + "_bench";
    }

    /**
     * True when this pipeline can be timed.
     *
     * Timing needs arguments the machine can invent. A number it can; a
     * collection, a string or a tree it cannot, so those are not offered a
     * measurement rather than given a meaningless one.
     */
    public static boolean canBeTimed(RootPipelineManifest manifest) {
        if (manifest == null
            || (manifest.kind != RootPipelineManifest.Kind.TRANSFORM
                && manifest.kind != RootPipelineManifest.Kind.FILTER)) {
            return false;
        }
        if (manifest.inputs.isEmpty()) {
            return false;
        }
        for (RootPipelineManifest.Input input : manifest.inputs) {
            if (sample(input.type()) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * A timing entry point, compiled beside the pipeline with the same flags.
     *
     * Timing through the interpreter would measure the interpreter. This loop
     * is inside the library, so what is measured is the optimized code itself.
     * The accumulator is volatile because a compiler at -O3 is entitled to
     * delete a call whose result nobody reads, and would then report a pipeline
     * that costs nothing.
     */
    public static String benchFunction(RootPipelineManifest manifest) {
        if (!canBeTimed(manifest)) {
            return "";
        }
        final StringBuilder out = new StringBuilder(768);
        out.append("\n// Written by Sphere so the pipeline can be timed as it is built.\n");
        out.append("// Not called by anything else, and harmless when unused.\n");
        out.append("extern \"C\" double ").append(benchName(manifest)).append("(long rounds) {\n");
        out.append("  if (rounds < 1) {\n    return 0.0;\n  }\n");
        // 64 bits on every platform, as in the probe above: a 32-bit seed would
        // time every call at inputs of about 1e-9 instead of across the range.
        out.append("  unsigned long long seed = 88172645463325252ULL;\n");
        out.append("  volatile double sink = 0.0;\n");
        out.append("  const std::chrono::steady_clock::time_point started =\n");
        out.append("      std::chrono::steady_clock::now();\n");
        out.append("  for (long n = 0; n < rounds; ++n) {\n");
        out.append("    seed ^= seed << 13;\n");
        out.append("    seed ^= seed >> 7;\n");
        out.append("    seed ^= seed << 17;\n");
        out.append("    const double r = (double) (seed >> 11) * (1.0 / 9007199254740992.0);\n");
        out.append("    sink = sink + (double) (").append(RootPipelineExtras.callee(manifest)).append('(');
        for (int i = 0; i < manifest.inputs.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(sample(manifest.inputs.get(i).type()));
        }
        out.append("));\n");
        out.append("  }\n");
        out.append("  const std::chrono::steady_clock::time_point ended =\n");
        out.append("      std::chrono::steady_clock::now();\n");
        out.append("  (void) sink;\n");
        out.append("  return std::chrono::duration<double, std::nano>(ended - started).count()\n");
        out.append("         / (double) rounds;\n");
        out.append("}\n");
        return out.toString();
    }

    /** An argument of this type built from r, or null when none can be. */
    private static String sample(String type) {
        if (type == null) {
            return null;
        }
        final String bare = type.replace("const", "").trim();
        return switch (bare) {
            case "double" -> "r * 100.0";
            case "float" -> "(float) (r * 100.0)";
            case "int" -> "(int) (r * 100.0)";
            case "long" -> "(long) (r * 100.0)";
            case "unsigned int" -> "(unsigned int) (r * 100.0)";
            case "bool" -> "(r > 0.5)";
            default -> null;
        };
    }

    /**
     * A program that times the pipeline, for comparing one set of flags with
     * another without touching the library the engine has loaded.
     *
     * The source is included rather than linked, so each set of flags applies
     * to the pipeline itself and not only to the loop around it.
     */
    public static String benchProgram(RootPipelineManifest manifest, java.nio.file.Path source) {
        return "// Written by Sphere to time " + manifest.name + ". Safe to delete.\n"
             + "#include \"" + source.toAbsolutePath().toString().replace("\\", "/") + "\"\n"
             + "#include <cstdio>\n"
             + "#include <cstdlib>\n\n"
             + "int main(int argc, char **argv) {\n"
             + "  const long rounds = argc > 1 ? atol(argv[1]) : 1000000L;\n"
             + "  printf(\"%.4f\\n\", " + benchName(manifest) + "(rounds));\n"
             + "  return 0;\n"
             + "}\n";
    }

    /**
     * A program that inspects the pipeline: one call, a health sweep, or a scan.
     *
     * Built and run outside the engine, like the timing one, so looking at a
     * pipeline can never disturb the library a session already has loaded.
     */
    public static String inspectProgram(RootPipelineManifest manifest,
                                        java.nio.file.Path source) {
        final int n = manifest.inputs.size();
        final StringBuilder out = new StringBuilder(1024);
        out.append("// Written by Sphere to inspect ").append(manifest.name)
           .append(". Safe to delete.\n");
        out.append("#include \"")
           .append(source.toAbsolutePath().toString().replace("\\", "/")).append("\"\n");
        out.append("#include <cstdio>\n#include <cstdlib>\n#include <cstring>\n#include <cmath>\n\n");
        out.append("int main(int argc, char **argv) {\n");
        out.append("  if (argc < 2) {\n    return 1;\n  }\n");
        out.append("  if (strcmp(argv[1], \"watch\") == 0) {\n");
        out.append("    if (argc < ").append(2 + n).append(") {\n      return 2;\n    }\n");
        out.append("    printf(\"%s\", ").append(watchName(manifest)).append('(');
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append("atof(argv[").append(2 + i).append("])");
        }
        out.append("));\n    return 0;\n  }\n");
        if (n > 0) {
            // Each input over a range of its own, the entry point called
            // directly: this mode lives in the program, not in the library's
            // tail, so a source written by an earlier Sphere has it too.
            out.append("  if (strcmp(argv[1], \"ranges\") == 0) {\n");
            out.append("    if (argc < ").append(3 + 2 * n).append(") {\n      return 4;\n    }\n");
            out.append("    const int points = atoi(argv[2]) < 1 ? 1 : atoi(argv[2]);\n");
            out.append("    double lo[").append(n).append("], hi[").append(n).append("], a[").append(n).append("];\n");
            out.append("    for (int i = 0; i < ").append(n).append("; ++i) {\n");
            out.append("      lo[i] = atof(argv[3 + 2 * i]);\n");
            out.append("      hi[i] = atof(argv[4 + 2 * i]);\n");
            out.append("    }\n");
            out.append("    unsigned long long seed = 88172645463325252ULL;\n");
            out.append("    long bad = 0, huge = 0;\n");
            out.append("    double smallest = 0.0, largest = 0.0, total = 0.0;\n");
            out.append("    bool first = true;\n");
            out.append("    for (int step = 0; step < points; ++step) {\n");
            out.append("      for (int i = 0; i < ").append(n).append("; ++i) {\n");
            out.append("        seed ^= seed << 13; seed ^= seed >> 7; seed ^= seed << 17;\n");
            out.append("        const double r = (double) (seed >> 11) * (1.0 / 9007199254740992.0);\n");
            out.append("        a[i] = lo[i] + r * (hi[i] - lo[i]);\n");
            out.append("      }\n");
            out.append("      const double v = (double) (").append(RootPipelineExtras.callee(manifest)).append('(');
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(castTo(manifest.inputs.get(i).type(), "a[" + i + "]"));
            }
            out.append("));\n");
            out.append("      if (std::isnan(v)) {\n        ++bad;\n        continue;\n      }\n");
            out.append("      if (std::isinf(v)) {\n        ++huge;\n        continue;\n      }\n");
            out.append("      if (first) {\n        smallest = v;\n        largest = v;\n        first = false;\n");
            out.append("      } else {\n        if (v < smallest) { smallest = v; }\n");
            out.append("        if (v > largest) { largest = v; }\n      }\n");
            out.append("      total += v;\n");
            out.append("    }\n");
            out.append("    const long good = (long) points - bad - huge;\n");
            out.append("    printf(\"tried=%d\\nnan=%ld\\ninf=%ld\\nmin=%.17g\\nmax=%.17g\\nmean=%.17g\\n\",\n");
            out.append("           points, bad, huge, smallest, largest, good > 0 ? total / (double) good : 0.0);\n");
            out.append("    return 0;\n");
            out.append("  }\n");
        }
        out.append("  if (argc < 6) {\n    return 3;\n  }\n");
        out.append("  printf(\"%s\", ").append(probeName(manifest))
           .append("(atoi(argv[2]), atof(argv[3]), atof(argv[4]),\n")
           .append("                     atoi(argv[5]), argc > 6 ? atof(argv[6]) : 1.0));\n");
        out.append("  return 0;\n}\n");
        return out.toString();
    }

    /** A double handed to an input of the form's type. */
    private static String castTo(String type, String value) {
        return switch (type == null ? "" : type.trim()) {
            case "float" -> "(float) (" + value + ")";
            case "int" -> "(int) (" + value + ")";
            case "long" -> "(long) (" + value + ")";
            case "unsigned int" -> "(unsigned int) (" + value + ")";
            case "bool" -> "(" + value + " > 0.5)";
            default -> value;
        };
    }

    /** The starting body, which the user then rewrites. */
    private static String body(RootPipelineManifest manifest,
                               RootPipelineRecipes.Recipe recipe) {
        final String ready = RootPipelineRecipes.bodyFor(recipe, manifest);
        if (ready != null && manifest.kind != RootPipelineManifest.Kind.LIBRARY) {
            // The recipe brought the kind, the inputs and the modules with it,
            // so the signature the form now shows is the one it was written for.
            // What the body needs above it -- helpers from a macro, the code
            // around a copied entry point -- goes first, in the user's half,
            // where a later change to the form keeps it.
            final String prelude = RootPipelineRecipes.preludeFor(recipe, manifest);
            return "\n" + (prelude == null ? "" : prelude + "\n\n")
                 + manifest.writtenSignature() + " {\n" + ready + "\n}\n";
        }
        final StringBuilder out = new StringBuilder(512);
        out.append('\n');
        switch (manifest.kind) {
            case TRANSFORM -> {
                out.append(manifest.writtenSignature()).append(" {\n");
                out.append("  // Return the value this entry should carry.\n");
                out.append("  // :root rdf define df ").append(manifest.name).append(' ')
                   .append(callExample(manifest)).append('\n');
                out.append("  return ").append(firstInputOrZero(manifest)).append(";\n");
                out.append("}\n");
            }
            case FILTER -> {
                out.append(manifest.writtenSignature()).append(" {\n");
                out.append("  // Return true for the entries that should be kept.\n");
                out.append("  // :root rdf filter df ").append(callExample(manifest)).append('\n');
                out.append("  return ").append(firstInputOrZero(manifest)).append(" > 0;\n");
                out.append("}\n");
            }
            case HISTOGRAM -> {
                out.append(manifest.signature()).append(" {\n");
                out.append("  // Build and return the histogram. Sphere puts it in gDirectory,\n");
                out.append("  // so :root hist draw ").append(manifest.name)
                   .append("_h finds it afterward.\n");
                out.append("  TH1D *out = new TH1D(\"").append(manifest.name)
                   .append("_h\", \"").append(manifest.name).append("\", 100, 0.0, 100.0);\n");
                out.append("  if (tree == nullptr) {\n");
                out.append("    return out;\n");
                out.append("  }\n");
                out.append("  // TTreeReader reader(tree);\n");
                out.append("  // TTreeReaderValue<Float_t> value(reader, \"branch\");\n");
                out.append("  // while (reader.Next()) { out->Fill(*value); }\n");
                out.append("  return out;\n");
                out.append("}\n");
            }
            case LIBRARY -> {
                out.append("// Write whatever the analysis needs. Every function here becomes\n");
                out.append("// callable from :root once the library is loaded.\n\n");
                out.append("double ").append(manifest.name).append("_example(double x) {\n");
                out.append("  return x;\n");
                out.append("}\n");
            }
            default -> out.append('\n');
        }
        return out.toString();
    }

    /** A test macro for user_scripts/, when the form asks for one. */
    public static String testMacro(RootPipelineManifest manifest) {
        final StringBuilder out = new StringBuilder(512);
        out.append("// Test for the ").append(manifest.name).append(" pipeline.\n");
        out.append("// Run it with :root script run ").append(manifest.name).append("_test.C\n");
        out.append("//\n");
        out.append("// The library is already loaded when Sphere starts, so the entry point\n");
        out.append("// is simply called by name.\n\n");
        out.append("void ").append(manifest.name).append("_test() {\n");
        switch (manifest.kind) {
            case TRANSFORM, FILTER -> {
                out.append("  std::cout << ").append(manifest.name).append('(')
                   .append(sampleArguments(manifest)).append(") << std::endl;\n");
                // What the extras add, called the way an analysis would.
                if (RootPipelineExtras.perObject(manifest)) {
                    out.append("  // Per object: two objects, one answer each.\n");
                    out.append("  std::cout << ").append(manifest.name).append('(');
                    for (int i = 0; i < manifest.inputs.size(); i++) {
                        out.append(i > 0 ? ", " : "").append("ROOT::RVec<double>{1.0, 2.0}");
                    }
                    out.append(") << std::endl;\n");
                }
                if (RootPipelineExtras.sigma(manifest)) {
                    out.append("  // Its uncertainty, every input known to 10%.\n");
                    out.append("  std::cout << ").append(RootPipelineExtras.sigmaName(manifest)).append('(');
                    int count = manifest.inputs.size();
                    for (var v : RootPipelineExtras.varied(manifest)) {
                        count += v.given() ? 0 : 1;
                    }
                    for (int i = 0; i < count; i++) {
                        out.append(i > 0 ? ", " : "").append(i < manifest.inputs.size() ? "1.0" : "0.1");
                    }
                    out.append(") << std::endl;\n");
                }
                if (RootPipelineExtras.monitored(manifest)) {
                    out.append("  // What the monitor saw of the calls above.\n");
                    out.append("  std::cout << ").append(RootPipelineExtras.reportName(manifest))
                       .append("() << std::endl;\n");
                }
            }
            case HISTOGRAM -> {
                // The tree comes first, then every other input the form lists.
                out.append("  TH1D *h = ").append(manifest.name).append("(nullptr")
                   .append(manifest.inputs.isEmpty() ? "" : ", " + sampleArguments(manifest)).append(");\n");
                out.append("  if (h == nullptr) {\n");
                out.append("    std::cout << \"").append(manifest.name)
                   .append(" gave no histogram for an empty tree\" << std::endl;\n");
                out.append("    return;\n");
                out.append("  }\n");
                out.append("  std::cout << h->GetName() << \"  \" << h->GetEntries() << std::endl;\n");
            }
            default ->
                out.append("  std::cout << ").append(manifest.name)
                   .append("_example(1.0) << std::endl;\n");
        }
        out.append("}\n");
        return out.toString();
    }

    /**
     * A new source that keeps the user's body and replaces everything above it.
     *
     * Regenerating the whole file would throw away the work; leaving the header
     * alone would let the declaration drift from the form.
     */
    public static String reheader(String existing, RootPipelineManifest manifest) {
        if (existing == null || !existing.contains(BODY_MARK)) {
            return source(manifest);
        }
        // From the line after the mark: the header ends on the mark's own line
        // break, and keeping that break as well added a blank line above the
        // user's code at every save.
        final int mark = existing.indexOf(BODY_MARK) + BODY_MARK.length();
        final int lineEnd = existing.indexOf('\n', mark);
        final int from = lineEnd < 0 ? existing.length() : lineEnd + 1;
        final int to = existing.indexOf(TAIL_MARK, from);
        String kept = to < 0 ? existing.substring(from)
                             : existing.substring(from, to);
        // The entry point's declaration opens the user's half, but the form owns
        // it: an input added in the form would otherwise reach the tail, which
        // calls the entry point, and not the declaration, and the source would
        // stop compiling. It is replaced only while it still reads as the form
        // wrote it, so a declaration the user rewrote by hand is left alone.
        // The monitor renames it too: on, the user's function becomes the inner
        // one the entry point at the end of the file calls; off, it is the
        // entry point again.
        final RootPipelineManifest before = RootPipelineManifest.fromText(existing);
        if (before != null && before.kind != RootPipelineManifest.Kind.LIBRARY
                && manifest.kind != RootPipelineManifest.Kind.LIBRARY) {
            final String was = before.writtenSignature() + " {";
            final int at = kept.indexOf(was);
            if (at >= 0) {
                kept = kept.substring(0, at) + manifest.writtenSignature() + " {"
                     + kept.substring(at + was.length());
            }
        }
        return header(manifest) + trimTrailing(kept) + tail(manifest);
    }

    /** The kept half without the blank lines a removed tail left behind. */
    private static String trimTrailing(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
            end--;
        }
        return text.substring(0, end) + "\n";
    }

    // -------------------------------------------------------------------------

    /**
     * The modules to include for, which is what the form asked for plus what
     * the signature makes unavoidable.
     *
     * An input typed ROOT::RVec<double> needs its header whether or not the box
     * was ticked, and a histogram needs a tree to read: forgetting either would
     * produce a source that cannot compile, from a form that looked complete.
     */
    private static java.util.List<RootPipelineModules> modulesFor(
            RootPipelineManifest manifest) {
        java.util.List<RootPipelineModules> out =
            new java.util.ArrayList<>(manifest.modules);
        for (RootPipelineManifest.Input input : manifest.inputs) {
            if (input.type() != null && input.type().contains("RVec")
                    && !out.contains(RootPipelineModules.VECTORS)) {
                out.add(RootPipelineModules.VECTORS);
            }
        }
        if (manifest.kind == RootPipelineManifest.Kind.HISTOGRAM) {
            if (!out.contains(RootPipelineModules.HIST)) {
                out.add(RootPipelineModules.HIST);
            }
            if (!out.contains(RootPipelineModules.TREE)) {
                out.add(RootPipelineModules.TREE);
            }
        }
        return out;
    }

    private static String firstInputOrZero(RootPipelineManifest manifest) {
        for (RootPipelineManifest.Input input : manifest.inputs) {
            if (input.type() != null && !input.type().contains("RVec")
                && !input.type().contains("char")) {
                return input.name();
            }
        }
        return "0.0";
    }

    /** How the command line would call it, for the comment that shows the way. */
    private static String callExample(RootPipelineManifest manifest) {
        final StringBuilder out = new StringBuilder(manifest.name).append('(');
        for (int i = 0; i < manifest.inputs.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(manifest.inputs.get(i).name());
        }
        return out.append(')').toString();
    }

    private static String sampleArguments(RootPipelineManifest manifest) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < manifest.inputs.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            final String type = manifest.inputs.get(i).type();
            if (type != null && type.contains("RVec")) {
                out.append("{}");
            } else if (type != null && type.contains("char")) {
                out.append("\"\"");
            } else if (type != null && type.contains("bool")) {
                out.append("true");
            } else {
                out.append("1.0");
            }
        }
        return out.toString();
    }

    /** The library file the compiler will produce, asked of the compiler itself. */
    public static String libraryFileName(RootPipelineManifest manifest) {
        return RootUserCompiler.libraryName(manifest.name, libraryExtension());
    }

    /** What this platform calls a shared library. */
    public static String libraryExtension() {
        final String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return ".dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return ".dylib";
        }
        return ".so";
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

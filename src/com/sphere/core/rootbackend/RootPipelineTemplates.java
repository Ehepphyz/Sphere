package com.sphere.core.rootbackend;

/**
 * The C++ a pipeline starts life as.
 *
 * What the window generates already compiles and already declares itself, so
 * the user never faces an empty file and never has to remember the shape the
 * engine expects. Everything above the body is regenerated when the form
 * changes; everything below the marked line is the user's and is kept.
 */
public final class RootPipelineTemplates {

    /** Below this line the file belongs to the user and is never rewritten. */
    public static final String BODY_MARK =
        "// ---- your code below this line is kept when the form changes ----";

    private RootPipelineTemplates() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The whole source for a new pipeline. */
    public static String source(RootPipelineManifest manifest) {
        return header(manifest) + body(manifest);
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
        out.append('\n');

        out.append("#include <string>\n");
        if (manifest.rootHeaders) {
            out.append("#include <TMath.h>\n");
            if (manifest.kind == RootPipelineManifest.Kind.HISTOGRAM) {
                out.append("#include <TH1.h>\n");
                out.append("#include <TTree.h>\n");
                out.append("#include <TTreeReader.h>\n");
                out.append("#include <TTreeReaderValue.h>\n");
            }
        }
        if (manifest.useRVec || usesRVec(manifest)) {
            out.append("#include <ROOT/RVec.hxx>\n");
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

        out.append(BODY_MARK).append('\n');
        return out.toString();
    }

    /** The starting body, which the user then rewrites. */
    private static String body(RootPipelineManifest manifest) {
        final StringBuilder out = new StringBuilder(512);
        out.append('\n');
        switch (manifest.kind) {
            case TRANSFORM -> {
                out.append(manifest.signature()).append(" {\n");
                out.append("  // Return the value this entry should carry.\n");
                out.append("  // :root rdf define df ").append(manifest.name).append(' ')
                   .append(callExample(manifest)).append('\n');
                out.append("  return ").append(firstInputOrZero(manifest)).append(";\n");
                out.append("}\n");
            }
            case FILTER -> {
                out.append(manifest.signature()).append(" {\n");
                out.append("  // Return true for the entries that should be kept.\n");
                out.append("  // :root rdf filter df ").append(callExample(manifest)).append('\n');
                out.append("  return ").append(firstInputOrZero(manifest)).append(" > 0;\n");
                out.append("}\n");
            }
            case HISTOGRAM -> {
                out.append(manifest.signature()).append(" {\n");
                out.append("  // Build and return the histogram. Sphere puts it in gDirectory,\n");
                out.append("  // so :root hist draw ").append(manifest.name)
                   .append("_h finds it afterwards.\n");
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
            case TRANSFORM, FILTER ->
                out.append("  std::cout << ").append(manifest.name).append('(')
                   .append(sampleArguments(manifest)).append(") << std::endl;\n");
            case HISTOGRAM -> {
                out.append("  TH1D *h = ").append(manifest.name).append("(nullptr);\n");
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
        final int at = existing.indexOf(BODY_MARK) + BODY_MARK.length();
        return header(manifest) + existing.substring(at);
    }

    // -------------------------------------------------------------------------

    private static boolean usesRVec(RootPipelineManifest manifest) {
        for (RootPipelineManifest.Input input : manifest.inputs) {
            if (input.type() != null && input.type().contains("RVec")) {
                return true;
            }
        }
        return false;
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

package com.sphere.core.rootbackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a user pipeline declares about itself.
 *
 * A shared library dropped in includes/ used to be loaded and then forgotten:
 * its symbols reached the interpreter, but nothing in Sphere knew what was in
 * it. This is the missing declaration. One line describes the pipeline, and
 * that line lives in exactly two places that are written together -- a marker
 * comment at the top of the source, and the string the compiled library hands
 * back through sphere_pipeline_manifest(). The window reads the first, the
 * engine reads the second, and neither can drift from the source.
 */
public final class RootPipelineManifest {

    /** The shape of a pipeline, which decides its signature and where it plugs in. */
    public enum Kind {
        TRANSFORM("transform", "double", "a value computed per entry, for :root rdf define"),
        FILTER("filter", "bool", "a yes or no per entry, for :root rdf filter"),
        HISTOGRAM("histogram", "TH1D *", "a histogram built from a tree"),
        LIBRARY("library", "void", "plain functions, with no contract");

        public final String token;
        public final String returnType;
        public final String explanation;

        Kind(String token, String returnType, String explanation) {
            this.token = token;
            this.returnType = returnType;
            this.explanation = explanation;
        }

        public static Kind of(String token) {
            for (Kind kind : values()) {
                if (kind.token.equalsIgnoreCase(token)) {
                    return kind;
                }
            }
            return LIBRARY;
        }
    }

    /** One typed input of the entry point. */
    public record Input(String name, String type) {
        @Override
        public String toString() {
            return name + ":" + type;
        }
    }

    /** The C++ types the window offers, in the order a physicist reaches for them. */
    public static final String[] TYPES = {
        "double", "float", "int", "long", "unsigned int", "bool",
        "const char *", "ROOT::RVec<double>", "ROOT::RVec<float>", "ROOT::RVec<int>"
    };

    /**
     * The marker, and the version of the line that follows it.
     *
     * Version 1 carried six fields and one boolean for headers. Version 2 adds
     * the modules the pipeline needs, which the boolean could not express. A
     * source written by an earlier Sphere is still read, so raising the version
     * costs nothing to whoever already has pipelines on disk.
     */
    private static final String PREFIX = "// sphere-pipeline ";
    private static final int VERSION = com.sphere.Sphere.ROOT_PIPELINE_MANIFEST_VERSION;
    private static final String MARKER = PREFIX + VERSION + " :: ";
    private static final String SEPARATOR = " :: ";

    // ---- what the form collects ---------------------------------------------
    public String name = "";
    public String description = "";
    public Kind kind = Kind.TRANSFORM;
    public final List<Input> inputs = new ArrayList<>();

    /** True for the folder beside Sphere, false for the active project's own. */
    public boolean global = true;

    /** The parts of ROOT this pipeline needs, for the includes and the -l flags. */
    public final List<RootPipelineModules> modules = new ArrayList<>();

    public boolean threadSafe = true;
    public boolean openMp = false;
    public boolean optimize = true;
    public boolean nativeArch = false;
    public boolean generateTest = false;
    public boolean showInHelp = true;
    public String extraFlags = "";

    // -------------------------------------------------------------------------

    /** The entry point as it is written in C++. */
    public String signature() {
        StringBuilder out = new StringBuilder();
        out.append(kind.returnType);
        if (!kind.returnType.endsWith("*")) {
            out.append(' ');
        }
        out.append(name.isBlank() ? "pipeline" : name).append('(');
        if (kind == Kind.HISTOGRAM) {
            out.append("TTree *tree");
            for (Input input : inputs) {
                out.append(", ").append(input.type()).append(' ').append(input.name());
            }
        } else {
            for (int i = 0; i < inputs.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(inputs.get(i).type()).append(' ').append(inputs.get(i).name());
            }
        }
        return out.append(')').toString();
    }

    /** The compiler flags the options ask for, on top of what the compiler already uses. */
    public List<String> flags() {
        List<String> out = new ArrayList<>();
        out.add(optimize ? "-O3" : "-O0");
        if (nativeArch) {
            out.add("-march=native");
        }
        if (openMp) {
            out.add("-fopenmp");
        }
        // A module that is included must also be linked, or the library loads
        // and then fails on a symbol, which reads as a mystery from the engine.
        out.addAll(RootPipelineModules.linkFlagsOf(modules));
        if (extraFlags != null && !extraFlags.isBlank()) {
            for (String flag : extraFlags.trim().split("\\s+")) {
                out.add(flag);
            }
        }
        return out;
    }

    /** The short options word that travels in the marker line. */
    private String options() {
        StringBuilder out = new StringBuilder();
        if (threadSafe) { out.append("threadsafe,"); }
        if (openMp)     { out.append("openmp,"); }
        if (optimize)   { out.append("O3,"); }
        if (nativeArch) { out.append("native,"); }
        if (showInHelp) { out.append("help,"); }
        return out.length() == 0 ? "-" : out.substring(0, out.length() - 1);
    }

    /**
     * The one line that describes this pipeline.
     *
     * Written twice into the source -- as a comment the window reads back, and
     * inside the function the engine calls -- so a build cannot produce a
     * library that describes something other than its own source.
     */
    public String encode() {
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) {
                args.append(',');
            }
            args.append(inputs.get(i));
        }
        return name + SEPARATOR
             + kind.token + SEPARATOR
             + kind.returnType + SEPARATOR
             + (args.length() == 0 ? "-" : args) + SEPARATOR
             + options() + SEPARATOR
             + RootPipelineModules.encode(modules) + SEPARATOR
             + (description == null ? "" : description.replace(SEPARATOR, " "));
    }

    /** The marker comment, as it is written at the top of the source. */
    public String markerLine() {
        return MARKER + encode();
    }

    /**
     * The C symbol the library exports its manifest under.
     *
     * The pipeline's own name is part of it. A single shared name would be
     * resolved by the loader to whichever library came first, so every pipeline
     * after the first would describe itself with its neighbor's line.
     */
    public String exportName() {
        return exportName(name);
    }

    /** The same symbol, from a name alone, for a library read without its source. */
    public static String exportName(String pipelineName) {
        return "sphere_pipeline_" + (pipelineName == null ? "" : pipelineName) + "_manifest";
    }

    /** Reads back what encode() wrote. Null when the line is not one of ours. */
    public static RootPipelineManifest decode(String line) {
        if (line == null) {
            return null;
        }
        String body = line.trim();
        // The version decides where the description sits, so it is read first.
        int version = VERSION;
        if (body.startsWith(PREFIX)) {
            final int at = body.indexOf(SEPARATOR, PREFIX.length());
            if (at < 0) {
                return null;
            }
            try {
                version = Integer.parseInt(body.substring(PREFIX.length(), at).trim());
            } catch (NumberFormatException notAVersion) {
                return null;
            }
            body = body.substring(at + SEPARATOR.length());
        }
        String[] part = body.split(SEPARATOR, -1);
        if (part.length < 5) {
            return null;
        }
        RootPipelineManifest manifest = new RootPipelineManifest();
        manifest.name = part[0].trim();
        manifest.kind = Kind.of(part[1].trim());
        // part[2] is the return type, which the kind already fixes
        if (!"-".equals(part[3].trim()) && !part[3].isBlank()) {
            for (String one : part[3].split(",")) {
                // The first colon, not the last: an input name is an identifier
                // and holds none, while a type such as ROOT::RVec<double> does.
                final int at = one.indexOf(':');
                if (at > 0) {
                    manifest.inputs.add(
                        new Input(one.substring(0, at).trim(), one.substring(at + 1).trim()));
                }
            }
        }
        final String options = part[4].trim().toLowerCase(Locale.ROOT);
        manifest.threadSafe = options.contains("threadsafe");
        manifest.openMp = options.contains("openmp");
        manifest.optimize = options.contains("o3");
        manifest.nativeArch = options.contains("native");
        manifest.showInHelp = options.contains("help");

        if (version >= 2) {
            manifest.modules.addAll(
                RootPipelineModules.decode(part.length > 5 ? part[5] : ""));
            manifest.description = part.length > 6 ? part[6].trim() : "";
        } else {
            // Version 1 said "ROOT headers" and "rvec". Those become the two
            // modules that stood behind them, so an old source keeps building.
            manifest.modules.add(RootPipelineModules.MATH);
            if (options.contains("rvec")) {
                manifest.modules.add(RootPipelineModules.VECTORS);
            }
            if (manifest.kind == Kind.HISTOGRAM) {
                manifest.modules.add(RootPipelineModules.TREE);
                manifest.modules.add(RootPipelineModules.HIST);
            }
            manifest.description = part.length > 5 ? part[5].trim() : "";
        }
        return manifest;
    }

    /**
     * The manifest a source carries, or null when it carries none.
     *
     * Only the first few lines are read: a source that is not a Sphere pipeline
     * should cost nothing to reject.
     */
    public static RootPipelineManifest fromSource(Path source) {
        if (source == null || !Files.isRegularFile(source)) {
            return null;
        }
        try {
            List<String> head = Files.readAllLines(source, StandardCharsets.UTF_8);
            final int limit = Math.min(head.size(), 20);
            for (int i = 0; i < limit; i++) {
                // The prefix, not the whole marker: a source written by an
                // earlier Sphere carries an earlier version number, and decode
                // reads it, so it must still be recognized here.
                if (head.get(i).startsWith(PREFIX)) {
                    return decode(head.get(i));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return null;
    }

    /** Why this manifest cannot be built yet, or null when it can. */
    public String validate(List<String> takenNames) {
        if (name == null || name.isBlank()) {
            return "The pipeline needs a name.";
        }
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return "'" + name + "' is not a C++ identifier.";
        }
        if (takenNames != null) {
            for (String taken : takenNames) {
                if (taken.equalsIgnoreCase(name)) {
                    return "A pipeline called '" + name + "' already exists.";
                }
            }
        }
        final List<String> seen = new ArrayList<>();
        for (Input input : inputs) {
            if (input.name() == null || !input.name().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return "'" + input.name() + "' is not a valid input name.";
            }
            if (seen.contains(input.name())) {
                return "Two inputs are both called '" + input.name() + "'.";
            }
            seen.add(input.name());
        }
        if (kind != Kind.HISTOGRAM && kind != Kind.LIBRARY && inputs.isEmpty()) {
            return "A " + kind.token + " needs at least one input.";
        }
        return null;
    }

    /** The file this pipeline is written to, inside a layer's includes/. */
    public Path sourceIn(Path layerRoot) {
        return layerRoot.resolve(RootUserPipeline.INCLUDES_DIR).resolve(name + ".cpp");
    }

    /** A copy, so the window can edit without touching what is on disk. */
    public RootPipelineManifest copy() {
        RootPipelineManifest other = decode(encode());
        if (other == null) {
            other = new RootPipelineManifest();
        }
        other.global = global;
        other.generateTest = generateTest;
        other.extraFlags = extraFlags;
        return other;
    }
}

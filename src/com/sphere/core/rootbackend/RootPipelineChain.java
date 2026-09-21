package com.sphere.core.rootbackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A pipeline as an analysis really is: a chain, not a function.
 *
 * One compiled pipeline computes one quantity. An analysis reads a tree,
 * defines a few columns, cuts on them, fills histograms and writes what is
 * left. That chain used to live in the user's head, retyped command by command
 * at every session. Here it is a list of stages that Sphere keeps, and from the
 * one list it produces two things: the Sphere commands that replay it now, and
 * a standalone RDataFrame macro that runs anywhere ROOT does, with or without
 * Sphere. Neither is the source of truth for the other; both come from the
 * stages, so they cannot disagree.
 */
public final class RootPipelineChain {

    /** What one stage of the chain does, and what it needs to do it. */
    public enum Step {
        DEFINE("define", "Add a computed column", "column", "expression"),
        FILTER("filter", "Keep the entries that pass", "expression"),
        RANGE("range", "Keep only a slice of the entries", "begin", "end"),
        CACHE("cache", "Hold what is left in memory", new String[0]),
        HISTO("histo", "Fill a histogram from a column",
              "histogram", "bins", "low", "high", "column"),
        HISTO2("histo2", "Fill a two-dimensional histogram",
               "histogram", "xbins", "xlow", "xhigh",
               "ybins", "ylow", "yhigh", "xcolumn", "ycolumn"),
        PROFILE("profile", "Fill a profile from two columns",
                "profile", "bins", "low", "high", "xcolumn", "ycolumn"),
        GRAPH("graph", "Build a graph from two columns", "graph", "xcolumn", "ycolumn"),
        MEAN("mean", "Mean of a column", "column"),
        SUM("sum", "Sum of a column", "column"),
        MIN("min", "Smallest value of a column", "column"),
        MAX("max", "Largest value of a column", "column"),
        STDDEV("stddev", "Spread of a column", "column"),
        COUNT("count", "How many entries are left", new String[0]),
        DISPLAY("display", "Print the first entries", "rows"),
        REPORT("report", "What each cut kept", new String[0]),
        SNAPSHOT("snapshot", "Write what is left to a new file", "tree", "file");

        public final String token;
        public final String explanation;
        private final String[] fields;

        Step(String token, String explanation, String... fields) {
            this.token = token;
            this.explanation = explanation;
            this.fields = fields;
        }

        public List<String> fields() {
            return List.of(fields);
        }

        /** What the window puts in the form when this step is chosen. */
        public String placeholder() {
            return String.join("  ", fields);
        }

        public static Step of(String token) {
            for (Step one : values()) {
                if (one.token.equalsIgnoreCase(token == null ? "" : token.trim())) {
                    return one;
                }
            }
            return null;
        }
    }

    /** One stage, with the words it was given. */
    public record Stage(Step step, String arguments) {

        public List<String> words() {
            // A Define's expression holds spaces and must survive as one piece,
            // so only the fields before the last are split off.
            final int wanted = step.fields().size();
            if (wanted <= 1) {
                return arguments.isBlank() ? List.of() : List.of(arguments.trim());
            }
            List<String> out = new ArrayList<>();
            String rest = arguments.trim();
            for (int i = 0; i < wanted - 1 && !rest.isEmpty(); i++) {
                final int at = rest.indexOf(' ');
                if (at < 0) {
                    out.add(rest);
                    rest = "";
                } else {
                    out.add(rest.substring(0, at));
                    rest = rest.substring(at + 1).trim();
                }
            }
            if (!rest.isEmpty()) {
                out.add(rest);
            }
            return out;
        }

        @Override
        public String toString() {
            return step.token + (arguments.isBlank() ? "" : "  " + arguments);
        }
    }

    private static final String MARKER = "# sphere-chain 1";

    public String name = "";
    public String tree = "Events";
    public String file = "";
    public final List<Stage> stages = new ArrayList<>();

    /** The frame's name inside Sphere, which the commands all refer to. */
    public String frame() {
        return name.isBlank() ? "df" : name;
    }

    /* ------------------------------------------------------------------ */
    /* What the chain becomes                                              */
    /* ------------------------------------------------------------------ */

    /**
     * The Sphere commands that replay this chain on the running engine.
     *
     * Every one of them already exists and is already tested; the chain only
     * decides their order, so replaying it cannot reach code nothing else does.
     */
    public List<String> commands() {
        List<String> out = new ArrayList<>();
        out.add(":root rdf open " + frame() + " " + tree + " " + file);
        for (Stage stage : stages) {
            out.add(":root rdf " + stage.step().token + " " + frame()
                    + (stage.arguments().isBlank() ? "" : " " + stage.arguments().trim()));
        }
        return out;
    }

    /**
     * A macro that runs this chain wherever ROOT does, Sphere or not.
     *
     * The node is declared as an RNode rather than kept in an auto: every
     * Define and every Filter returns a different type, so a chain assembled
     * one stage at a time can only be held by the type that erases them.
     */
    public String macro() {
        final String job = name.isBlank() ? "chain" : name;
        final StringBuilder out = new StringBuilder(1024);
        out.append("// ").append(MARKER.substring(2)).append(" :: ").append(job).append('\n');
        out.append("//\n");
        out.append("// Written by Sphere from the chain of the same name.\n");
        out.append("// Run it with :root script run ").append(job).append(".C,\n");
        out.append("// or anywhere else with root -l -b -q ").append(job).append(".C\n\n");
        out.append("#include <ROOT/RDataFrame.hxx>\n");
        out.append("#include <TFile.h>\n");
        out.append("#include <TH1.h>\n");
        out.append("#include <TH2.h>\n");
        out.append("#include <TProfile.h>\n");
        out.append("#include <TGraph.h>\n");
        out.append("#include <TDirectory.h>\n");
        out.append("#include <iostream>\n\n");

        out.append("void ").append(job).append("() {\n");
        out.append("  ROOT::RDF::RNode node =\n");
        out.append("      ROOT::RDataFrame(\"").append(tree).append("\", \"")
           .append(file).append("\");\n\n");

        final List<String> results = new ArrayList<>();
        final List<String> printed = new ArrayList<>();
        int anonymous = 0;
        for (Stage stage : stages) {
            final List<String> w = stage.words();
            switch (stage.step()) {
                case DEFINE -> {
                    if (w.size() >= 2) {
                        out.append("  node = node.Define(\"").append(w.get(0))
                           .append("\", \"").append(escape(w.get(1))).append("\");\n");
                    }
                }
                case FILTER -> {
                    if (!w.isEmpty()) {
                        // Naming the cut is what makes Report able to say what
                        // each one removed, so every cut is given a name.
                        out.append("  node = node.Filter(\"").append(escape(w.get(0)))
                           .append("\", \"cut").append(++anonymous).append("\");\n");
                    }
                }
                case RANGE -> {
                    if (w.size() >= 2) {
                        out.append("  node = node.Range(").append(w.get(0)).append(", ")
                           .append(w.get(1)).append(");\n");
                    } else if (w.size() == 1) {
                        out.append("  node = node.Range(").append(w.get(0)).append(");\n");
                    }
                }
                case CACHE -> out.append("  node = node.Cache();\n");
                case HISTO -> {
                    if (w.size() >= 5) {
                        final String handle = "h_" + safe(w.get(0));
                        out.append("  auto ").append(handle).append(" = node.Histo1D({\"")
                           .append(w.get(0)).append("\", \"").append(w.get(0)).append("\", ")
                           .append(w.get(1)).append(", ").append(w.get(2)).append(", ")
                           .append(w.get(3)).append("}, \"").append(w.get(4)).append("\");\n");
                        results.add(handle + "->GetName() << \"  \" << " + handle
                                    + "->GetEntries()");
                        out.append("  ").append(handle).append("->SetDirectory(gDirectory);\n");
                    }
                }
                case HISTO2 -> {
                    if (w.size() >= 9) {
                        final String handle = "h_" + safe(w.get(0));
                        out.append("  auto ").append(handle).append(" = node.Histo2D({\"")
                           .append(w.get(0)).append("\", \"").append(w.get(0)).append("\", ")
                           .append(w.get(1)).append(", ").append(w.get(2)).append(", ")
                           .append(w.get(3)).append(", ").append(w.get(4)).append(", ")
                           .append(w.get(5)).append(", ").append(w.get(6)).append("}, \"")
                           .append(w.get(7)).append("\", \"").append(w.get(8)).append("\");\n");
                        results.add(handle + "->GetName() << \"  \" << " + handle
                                    + "->GetEntries()");
                    }
                }
                case PROFILE -> {
                    if (w.size() >= 6) {
                        final String handle = "p_" + safe(w.get(0));
                        out.append("  auto ").append(handle).append(" = node.Profile1D({\"")
                           .append(w.get(0)).append("\", \"").append(w.get(0)).append("\", ")
                           .append(w.get(1)).append(", ").append(w.get(2)).append(", ")
                           .append(w.get(3)).append("}, \"").append(w.get(4))
                           .append("\", \"").append(w.get(5)).append("\");\n");
                        results.add(handle + "->GetName() << \"  \" << " + handle
                                    + "->GetEntries()");
                    }
                }
                case GRAPH -> {
                    if (w.size() >= 3) {
                        final String handle = safe(w.get(0));
                        out.append("  auto ").append(handle).append(" = node.Graph(\"")
                           .append(w.get(1)).append("\", \"").append(w.get(2)).append("\");\n");
                        results.add("\"" + w.get(0) + "\" << \"  \" << " + handle
                                    + "->GetN()");
                    }
                }
                case MEAN, SUM, MIN, MAX, STDDEV -> {
                    if (!w.isEmpty()) {
                        final String call = switch (stage.step()) {
                            case MEAN -> "Mean";
                            case SUM -> "Sum";
                            case MIN -> "Min";
                            case MAX -> "Max";
                            default -> "StdDev";
                        };
                        final String handle = stage.step().token + "_" + safe(w.get(0));
                        out.append("  auto ").append(handle).append(" = node.").append(call)
                           .append("(\"").append(w.get(0)).append("\");\n");
                        results.add("\"" + stage.step().token + " " + w.get(0)
                                    + "\" << \"  \" << *" + handle);
                    }
                }
                case COUNT -> {
                    final String handle = "count" + (++anonymous);
                    out.append("  auto ").append(handle).append(" = node.Count();\n");
                    results.add("\"entries\" << \"  \" << *" + handle);
                }
                case DISPLAY -> {
                    final String rows = w.isEmpty() ? "10" : w.get(0);
                    final String handle = "shown" + (++anonymous);
                    out.append("  auto ").append(handle).append(" = node.Display(\"\", ")
                       .append(rows).append(");\n");
                    results.add("\"\\n\" << " + handle + "->AsString()");
                }
                case REPORT -> {
                    final String handle = "report" + (++anonymous);
                    out.append("  auto ").append(handle).append(" = node.Report();\n");
                    // Reading it is what runs the loop, so it is printed with
                    // the other results rather than here.
                    printed.add(handle + "->Print();");
                }
                case SNAPSHOT -> {
                    if (w.size() >= 2) {
                        out.append("  node.Snapshot(\"").append(w.get(0)).append("\", \"")
                           .append(w.get(1)).append("\");\n");
                    }
                }
                default -> { }
            }
        }

        // Everything above only declared the work. Reading a result is what
        // makes RDataFrame loop, so the reads come last and in one pass.
        out.append('\n');
        if (results.isEmpty() && printed.isEmpty()) {
            out.append("  std::cout << \"entries  \" << *node.Count() << std::endl;\n");
        }
        for (String read : results) {
            out.append("  std::cout << ").append(read).append(" << std::endl;\n");
        }
        for (String one : printed) {
            out.append("  ").append(one).append('\n');
        }
        out.append("}\n");
        return out.toString();
    }

    /* ------------------------------------------------------------------ */
    /* On disk                                                             */
    /* ------------------------------------------------------------------ */

    /** The chain as a file a person can read and edit. */
    public String encode() {
        final StringBuilder out = new StringBuilder(512);
        out.append(MARKER).append('\n');
        out.append("name = ").append(name).append('\n');
        out.append("tree = ").append(tree).append('\n');
        out.append("file = ").append(file).append('\n');
        for (Stage stage : stages) {
            out.append(stage.step().token);
            if (!stage.arguments().isBlank()) {
                out.append(' ').append(stage.arguments().trim());
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** Reads back what encode() wrote. Null when the text is not a chain. */
    public static RootPipelineChain decode(String text) {
        if (text == null || !text.stripLeading().startsWith(MARKER)) {
            return null;
        }
        RootPipelineChain chain = new RootPipelineChain();
        for (String raw : text.split("\\R")) {
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final int equals = line.indexOf('=');
            if (equals > 0 && line.substring(0, equals).trim().matches("name|tree|file")) {
                final String value = line.substring(equals + 1).trim();
                switch (line.substring(0, equals).trim().toLowerCase(Locale.ROOT)) {
                    case "name" -> chain.name = value;
                    case "tree" -> chain.tree = value;
                    default -> chain.file = value;
                }
                continue;
            }
            final int space = line.indexOf(' ');
            final Step step = Step.of(space < 0 ? line : line.substring(0, space));
            if (step != null) {
                chain.stages.add(new Stage(step,
                    space < 0 ? "" : line.substring(space + 1).trim()));
            }
        }
        return chain;
    }

    /** Where a chain lives: beside the macros it turns into. */
    public static Path fileIn(Path layerRoot, String name) {
        return layerRoot.resolve(RootUserPipeline.SCRIPTS_DIR).resolve(name + ".chain");
    }

    /** Every chain of a layer, newest name order. */
    public static List<Path> chainsIn(Path layerRoot) {
        List<Path> found = new ArrayList<>();
        if (layerRoot == null) {
            return found;
        }
        Path dir = layerRoot.resolve(RootUserPipeline.SCRIPTS_DIR);
        if (!Files.isDirectory(dir)) {
            return found;
        }
        try (var entries = Files.list(dir)) {
            entries.filter(Files::isRegularFile)
                   .filter(one -> one.getFileName().toString().endsWith(".chain"))
                   .sorted()
                   .forEach(found::add);
        } catch (IOException unreadable) {
            return found;
        }
        return found;
    }

    /** The chain a file holds, or null when it holds something else. */
    public static RootPipelineChain read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return decode(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Why this chain cannot be run yet, or null when it can. */
    public String validate() {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return "The chain needs a name that is also a C++ identifier.";
        }
        if (tree == null || tree.isBlank()) {
            return "Which tree does it read?";
        }
        if (file == null || file.isBlank()) {
            return "Which file does it read?";
        }
        if (stages.isEmpty()) {
            return "A chain with no stage would only open the file.";
        }
        for (Stage stage : stages) {
            final int wanted = stage.step().fields().size();
            if (stage.words().size() < wanted) {
                return stage.step().token + " wants " + stage.step().placeholder();
            }
        }
        return null;
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String safe(String text) {
        return text == null ? "x" : text.replaceAll("[^A-Za-z0-9_]", "_");
    }
}

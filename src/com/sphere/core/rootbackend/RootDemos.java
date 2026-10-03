package com.sphere.core.rootbackend;

import com.sphere.utils.SettingsManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ROOT's demos, and any other tutorial, run so that what they draw is seen.
 *
 * root.exe's ".demo" opens a control bar of buttons, each running a tutorial
 * that draws in a window. Sphere's engine has no window, so each demo runs in
 * a ROOT of its own, in batch, and every canvas it leaves is written as a
 * picture: into the Plots tab for one demo, into a folder of its own for a run
 * of all of them. One process per demo costs a start of ROOT each time and
 * buys what matters when the point is to test the display: a demo that
 * crashes takes nothing else down, and the failure is its own.
 *
 * The list is read from the demos.C of the ROOT installed, so it follows that
 * version; the list of ROOT 6.36 stands in when the file cannot be found.
 */
public final class RootDemos {

    /** One button of demos.C: what it is called, the line it runs, what it shows. */
    public record Demo(String label, String action, String description) {

        /** The macro, relative to the tutorials folder; null when the button runs a statement. */
        public String macro() {
            final String a = action.strip();
            return a.startsWith(".x ") ? a.substring(3).strip() : null;
        }
    }

    /**
     * What one demo produced.
     *
     * @param pictures the canvases it left, written in every format asked for
     * @param errors   ROOT's "Error in" lines, and the reason when the process died
     * @param output   what it printed, ROOT's own chatter removed
     * @param note     why a demo that ran well left no canvas, when that can be told
     * @param scenes   the canvases again, as the numbers they were drawn from
     *                 (demo_&lt;label&gt;_&lt;canvas&gt;.sphere.json): what Sphere's TBrowser
     *                 turns, zooms and stretches instead of showing a picture
     */
    public record Outcome(Demo demo, boolean ok, double seconds, List<Path> pictures,
                          List<String> errors, String output, String note, List<Path> scenes) {
    }

    /** The extension of a scene file, which Sphere's TBrowser opens live. */
    public static final String SCENE = ".sphere.json";

    /** The buttons of ROOT 6.36's demos.C, used when the installed one cannot be read. */
    private static final List<Demo> BUILT_IN = List.of(
        new Demo("Help Demos", ".x demoshelp.C", "Click Here For Help on Running the Demos"),
        new Demo("browser", "new TBrowser;", "Start the ROOT Browser"),
        new Demo("framework", ".x visualisation/graphics/framework.C", "An Example of Object Oriented User Interface"),
        new Demo("first", ".x visualisation/graphics/first.C", "An Example of Slide with Root"),
        new Demo("hsimple", ".x hsimple.C", "An Example Creating Histograms/Ntuples on File"),
        new Demo("hsum", ".x hist/hist007_TH1_liveupdate.C", "Filling and live update of histogram"),
        new Demo("formula1", ".x visualisation/graphics/formula1.C", "Simple Formula and Functions"),
        new Demo("surfaces", ".x visualisation/graphics/surfaces.C", "Surface Drawing Options"),
        new Demo("fillrandom", ".x hist/hist001_TH1_fillrandom.C", "Histograms with Random Numbers from a Function"),
        new Demo("fit1", ".x math/fit/fit1.C", "A Simple Fitting Example"),
        new Demo("multifit", ".x math/fit/multifit.C", "Fitting in Subranges of Histograms"),
        new Demo("h1ReadAndDraw", ".x hist/hist015_TH1_read_and_draw.C", "Drawing Options for 1D Histograms"),
        new Demo("graph", ".x visualisation/graphs/gr001_simple.C", "Example of a Simple Graph"),
        new Demo("gerrors", ".x visualisation/graphs/gr002_errors.C", "Example of a Graph with Error Bars"),
        new Demo("tornado", ".x visualisation/graphics/tornado.C", "Examples of 3-D PolyMarkers"),
        new Demo("geometry", ".x visualisation/geom/rootgeom.C", "Example of TGeoManager drawing"),
        new Demo("file", ".x io/file.C", "The ROOT File Format"),
        new Demo("fildir", ".x io/fildir.C", "The ROOT File, Directories and Keys"),
        new Demo("tree", ".x legacy/tree/tree.C", "The Tree Data Structure"),
        new Demo("ntuple1", ".x io/tree/tree120_ntuple.C", "Ntuples and Selections"),
        new Demo("benchmarks", ".x legacy/benchmarks.C", "Runs several tests and produces an benchmark report"),
        new Demo("rootmarks", ".x legacy/rootmarks.C", "Prints an Estimated ROOTMARKS for Your Machine"));

    private static final Pattern BUTTON = Pattern.compile(
        "AddButton\\(\\s*\"([^\"]*)\"\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"([^\"]*)\"\\s*\\)");

    /** The formats a canvas may be written in, and that the Plots tab shows. */
    public static final List<String> FORMATS = List.of("png", "svg", "jpg", "tiff", "gif");

    /** How long one demo may take before it counts as hung. */
    private static final long DEMO_TIMEOUT_SECONDS = 600;

    private static final AtomicInteger RUNS = new AtomicInteger();

    private RootDemos() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /* ------------------------------------------------------------------ */
    /* Where things are                                                    */
    /* ------------------------------------------------------------------ */

    /** The root executable, as '::root' finds it. Null when ROOT is off or absent. */
    public static String rootExecutable() {
        return RootBridgeCompiler.rootExecutable(new SettingsManager());
    }

    /**
     * The tutorials folder of the ROOT installed: ROOT_TUTORIALS in
     * settings.conf, else what root-config says, else $ROOTSYS/tutorials.
     */
    public static Path tutorials() {
        final SettingsManager settings = new SettingsManager();
        final String declared = settings.getProperty("ROOT_TUTORIALS");
        if (declared != null && !declared.isBlank() && Files.isDirectory(Path.of(declared.strip()))) {
            return Path.of(declared.strip());
        }
        final String said = RootBridgeCompiler.getRootConfigOutput("--tutdir", settings);
        if (said != null && !said.isBlank() && Files.isDirectory(Path.of(said.strip()))) {
            return Path.of(said.strip());
        }
        final String rootDir = settings.getProperty("ROOT_DIR");
        for (String base : new String[]{rootDir, System.getenv("ROOTSYS")}) {
            if (base == null || base.isBlank()) continue;
            for (String sub : new String[]{"tutorials", "share/doc/root/tutorials"}) {
                final Path p = Path.of(base.strip(), sub);
                if (Files.isDirectory(p)) return p;
            }
        }
        return null;
    }

    /** The demos of the installed demos.C, in its order; ROOT 6.36's when it cannot be read. */
    public static List<Demo> demos() {
        final Path folder = tutorials();
        if (folder != null) {
            try {
                final List<Demo> read = parse(Files.readString(folder.resolve("demos.C")));
                if (!read.isEmpty()) return read;
            } catch (IOException | RuntimeException unreadable) {
                // the built-in list below
            }
        }
        return BUILT_IN;
    }

    /** The AddButton calls of a demos.C. */
    static List<Demo> parse(String source) {
        final List<Demo> out = new ArrayList<>();
        final Matcher m = BUTTON.matcher(source);
        while (m.find()) {
            out.add(new Demo(m.group(1).strip(), m.group(2).replace("\\\"", "\""), m.group(3).strip()));
        }
        return out;
    }

    /**
     * A demo by its label, or any tutorial by its path under the tutorials
     * folder or by its file name: "hsimple", "fit1", "math/fit/fit2.C",
     * "gr003_errors2".
     */
    public static Demo find(String name) {
        if (name == null || name.isBlank()) return null;
        final String n = name.strip();
        for (Demo d : demos()) {
            if (d.label().equalsIgnoreCase(n)) return d;
        }
        final Path folder = tutorials();
        if (folder == null) return null;
        final String relative = n.replace('\\', '/');
        if (Files.isRegularFile(folder.resolve(relative))) {
            return tutorial(folder, folder.resolve(relative));
        }
        final String file = relative.endsWith(".C") ? relative : relative + ".C";
        for (Path p : tutorialFiles(folder)) {
            if (p.getFileName().toString().equalsIgnoreCase(Path.of(file).getFileName().toString())) {
                return tutorial(folder, p);
            }
        }
        return null;
    }

    private static Demo tutorial(Path folder, Path file) {
        final String relative = folder.relativize(file).toString().replace('\\', '/');
        final String stem = file.getFileName().toString().replaceFirst("\\.C$", "");
        return new Demo(stem, ".x " + relative, summary(file));
    }

    /** Every C++ tutorial under the folder, sorted. */
    public static List<Path> tutorialFiles(Path folder) {
        try (Stream<Path> walk = Files.walk(folder)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".C") && Files.isRegularFile(p))
                .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * What a tutorial is about, from its Doxygen header: the \brief or
     * \preview line when there is one, which is the one-line summary the
     * reference guide shows, else the first line of text.
     */
    public static String summary(Path file) {
        // UTF-8 as the tutorials are written, a stray byte shown as such rather than refused.
        try (BufferedReader in = new BufferedReader(new InputStreamReader(Files.newInputStream(file),
                StandardCharsets.UTF_8))) {
            String line;
            String first = null;
            int read = 0;
            while ((line = in.readLine()) != null && read++ < 40) {
                final String t = line.strip();
                if (!t.startsWith("///")) {
                    if (read > 1 && !t.isEmpty()) break;
                    continue;
                }
                final String text = t.substring(3).strip();
                if (text.startsWith("\\brief ") || text.startsWith("\\preview ")) {
                    return shorten(text.substring(text.indexOf(' ') + 1).strip());
                }
                if (first == null && !text.isEmpty() && !text.startsWith("\\") && !text.startsWith("#")) {
                    first = text;
                }
            }
            if (first != null) return shorten(first);
        } catch (IOException | RuntimeException e) {
            // no summary
        }
        return "";
    }

    private static String shorten(String text) {
        return text.length() > 90 ? text.substring(0, 87) + "..." : text;
    }

    /* ------------------------------------------------------------------ */
    /* Canvas formats                                                      */
    /* ------------------------------------------------------------------ */

    /** ROOT_CANVAS_FORMATS in settings.conf, the formats canvases are written in; png when unset. */
    public static List<String> canvasFormats() {
        return formatsOf(new SettingsManager().getProperty("ROOT_CANVAS_FORMATS"));
    }

    /** The known formats in a list like "png svg" or "png,tiff"; "none" gives none, nothing gives png. */
    public static List<String> formatsOf(String spec) {
        final List<String> out = new ArrayList<>();
        if (spec != null) {
            for (String w : spec.toLowerCase(Locale.ROOT).split("[\\s,;]+")) {
                final String f = w.equals("jpeg") ? "jpg" : w.equals("tif") ? "tiff" : w;
                if (f.equals("none")) return List.of();
                if (FORMATS.contains(f) && !out.contains(f)) out.add(f);
            }
        }
        return out.isEmpty() ? List.of("png") : out;
    }

    /* ------------------------------------------------------------------ */
    /* Running                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Runs the demos, several ROOTs at a time, and reports each one as it ends.
     *
     * @param pictures where the canvases go, as demo_&lt;label&gt;_&lt;canvas&gt;.&lt;format&gt;
     * @param work     where they run, so files a demo writes (hsimple.root) stay together
     * @param jobs     how many run at once
     */
    public static List<Outcome> run(List<Demo> demos, Path pictures, Path work, List<String> formats, int jobs,
                                    Consumer<Outcome> each) throws IOException {
        final String root = rootExecutable();
        final Path folder = tutorials();
        if (root == null) {
            throw new IOException("ROOT was not found: set ROOT_DIR in settings.conf.");
        }
        if (folder == null) {
            throw new IOException("ROOT's tutorials folder was not found: set ROOT_TUTORIALS in settings.conf.");
        }
        Files.createDirectories(pictures);
        Files.createDirectories(work);
        final ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, jobs), r -> {
            final Thread t = new Thread(r, "sphere-root-demo");
            t.setDaemon(true);
            return t;
        });
        final List<Future<Outcome>> pending = new ArrayList<>();
        for (Demo d : demos) {
            pending.add(pool.submit(() -> {
                final Outcome o = runOne(root, folder, d, pictures, work, formats);
                if (each != null) each.accept(o);
                return o;
            }));
        }
        final List<Outcome> outcomes = new ArrayList<>();
        try {
            for (Future<Outcome> f : pending) outcomes.add(f.get());
        } catch (Exception interrupted) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IOException("The run was interrupted.");
        }
        pool.shutdown();
        return outcomes;
    }

    private static final String START = "@@sphere-demo-start";
    private static final String END = "@@sphere-demo-end";

    private static Outcome runOne(String root, Path folder, Demo demo, Path pictures, Path work,
                                  List<String> formats) {
        final long t0 = System.nanoTime();
        final List<String> errors = new ArrayList<>();
        if (demo.macro() == null) {
            errors.add("'" + demo.action() + "' needs a window of its own, which a ROOT in batch does not have.");
            return new Outcome(demo, false, 0, List.of(), errors, "", null, List.of());
        }
        final String name = "sphere_demo_" + ProcessHandle.current().pid() + "_" + RUNS.incrementAndGet();
        final Path launcher = work.resolve(name + ".C");
        final String safe = demo.label().replaceAll("[^A-Za-z0-9_.-]", "_");
        final StringBuilder output = new StringBuilder();
        boolean ended = false;
        int status = -1;
        try {
            Files.writeString(launcher, launcher(name, folder, demo, pictures, safe, formats), StandardCharsets.UTF_8);
            final ProcessBuilder pb = new ProcessBuilder(root, "-l", "-b", "-q", launcher.toString());
            pb.directory(work.toFile());
            pb.redirectErrorStream(true);
            com.sphere.core.bridge.Bridge.environment(pb.environment());
            final Process p = pb.start();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean inside = false;
                boolean crashed = false;
                // A demo that runs others (benchmarks) says "Processing X" before each:
                // the last one said is where a crash happened.
                String running = null;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith(START)) {
                        inside = true;
                    } else if (line.startsWith(END)) {
                        ended = true;
                        final String[] w = line.split("\\s+");
                        status = w.length > 1 ? parseInt(w[1]) : -1;
                        inside = false;
                    } else if (inside || !ended) {
                        if (line.startsWith("Processing ") && !line.contains("sphere_demo_")) {
                            running = line.substring(line.lastIndexOf(' ') + 1).replace("//", "/");
                        }
                        if (line.contains("*** Break ***")) {
                            crashed = true;
                            errors.add(line.strip() + (running == null ? "" : " in " + shortPath(folder, running)));
                        } else if (line.startsWith("Error in <")) {
                            errors.add(line.strip());
                        }
                        // After a crash ROOT prints its stack; the break line above says it.
                        if (!noise(line) && !(crashed && stackLine(line))) output.append(line).append('\n');
                    }
                }
            }
            if (!p.waitFor(DEMO_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                errors.add("stopped after " + DEMO_TIMEOUT_SECONDS + " s");
            }
        } catch (IOException | InterruptedException failed) {
            errors.add(failed.getMessage());
        } finally {
            try {
                Files.deleteIfExists(launcher);
            } catch (IOException ignored) {
                // left in the work folder
            }
        }
        if (!ended) {
            final boolean saidWhy = errors.stream().anyMatch(e -> e.contains("*** Break ***"));
            errors.add("ROOT stopped before the demo ended" + (saidWhy ? "." : lastLines(output)));
        } else if (status != 0) {
            errors.add("the interpreter reported error " + status);
        }
        final List<Path> written = picturesOf(pictures, "demo_" + safe + "_", formats);
        final List<Path> scenes = scenesOf(pictures, "demo_" + safe + "_");
        final double seconds = (System.nanoTime() - t0) / 1e9;
        final boolean ok = ended && status == 0 && errors.isEmpty();
        return new Outcome(demo, ok, seconds, written, errors, output.toString().strip(),
            ok && written.isEmpty() && scenes.isEmpty() ? whyNoCanvas(folder, demo) : null, scenes);
    }

    /**
     * Why a demo that ran without error left no canvas, read from its source:
     * it draws through OpenGL, which needs a window a ROOT in batch does not
     * have; or it only writes a file (the tutorial changed since demos.C
     * named it); or it simply leaves none.
     */
    static String whyNoCanvas(Path folder, Demo demo) {
        try {
            final String source = Files.readString(folder.resolve(effectiveMacro(folder, demo)),
                StandardCharsets.ISO_8859_1);
            if (source.contains("\"ogl") || source.contains("TGLViewer") || source.contains("TEveManager")) {
                return "it draws through OpenGL, which needs a window; a ROOT in batch has none";
            }
            if (!source.contains("Draw(") && (source.contains("Write") || source.contains("TFile::Open"))) {
                return "it only writes a file and draws nothing (the tutorial changed since demos.C named it)";
            }
        } catch (IOException | RuntimeException unreadable) {
            // the generic reason below
        }
        return "it left no canvas";
    }

    private static String shortPath(Path folder, String path) {
        final String base = slash(folder) + "/";
        return path.startsWith(base) ? path.substring(base.length()) : path;
    }

    /** A line of the stack trace ROOT prints after a crash. */
    private static boolean stackLine(String line) {
        final String t = line.strip();
        return t.startsWith("#") || t.startsWith("====") || t.startsWith("The lines below")
            || t.startsWith("from ") || t.contains("Taking a break from ROOT");
    }

    /**
     * A macro that runs the demo, then writes every canvas it left in each
     * format, quietly: SaveAs announces every file, and those lines are the
     * run's, not the demo's.
     */
    private static String launcher(String name, Path folder, Demo demo, Path pictures, String safe,
                                   List<String> formats) {
        final String tutorials = slash(folder);
        final StringBuilder fmts = new StringBuilder();
        for (String f : formats) fmts.append(fmts.length() == 0 ? "" : ", ").append('"').append(f).append('"');
        return "// Written by Sphere for one run of the ROOT demo " + demo.label() + "; removed after it.\n"
            + "#include \"TROOT.h\"\n#include \"TCanvas.h\"\n#include \"TError.h\"\n#include \"TString.h\"\n"
            + "#include \"TSystem.h\"\n#include <cstdio>\n#include <vector>\n\n"
            + "void " + name + "() {\n"
            + "   gROOT->SetMacroPath(TString::Format(\"%s:%s\", gROOT->GetMacroPath(), " + literal(tutorials) + "));\n"
            + "   std::printf(\"" + START + "\\n\"); std::fflush(stdout);\n"
            + "   Int_t error = 0;\n"
            + "   gROOT->ProcessLine(" + literal(".x " + tutorials + "/" + effectiveMacro(folder, demo)) + ", &error);\n"
            + "   std::fflush(stdout);\n"
            + "   const Int_t level = gErrorIgnoreLevel;\n"
            + "   gErrorIgnoreLevel = kWarning;\n"
            + "   const char *formats[] = {" + fmts + "};\n"
            + "   std::vector<TCanvas *> canvases;\n"
            + "   TIter next(gROOT->GetListOfCanvases());\n"
            + "   while (TObject *o = next()) if (auto *c = dynamic_cast<TCanvas *>(o)) canvases.push_back(c);\n"
            + "   for (TCanvas *c : canvases)\n"
            + "      for (const char *f : formats)\n"
            + "         c->SaveAs(TString::Format(\"%s/demo_%s_%s.%s\", " + literal(slash(pictures)) + ", "
            + literal(safe) + ", c->GetName(), f));\n"
            + "   gErrorIgnoreLevel = level;\n"
            + "   std::printf(\"" + END + " %d\\n\", error); std::fflush(stdout);\n"
            + sceneExport(pictures, safe)
            + "}\n";
    }

    /**
     * After the demo, every canvas again as a scene for Sphere's TBrowser, and
     * the geometry in memory when no canvas shows it (a ROOT in batch has no
     * OpenGL to draw one). Each step goes through the interpreter on its own,
     * so a ROOT that cannot read the header loses the scenes, never the demo;
     * it runs after the end marker, so nothing it says is taken for the demo's.
     */
    private static String sceneExport(Path pictures, String safe) {
        final Path header;
        try {
            header = com.sphere.core.bridge.Bridge.libraries().resolve("sphere_view3d.hpp");
        } catch (IOException | RuntimeException unavailable) {
            return "";
        }
        final String base = slash(pictures) + "/demo_" + safe + "_";
        return "   gErrorIgnoreLevel = kFatal;\n"
            + "   Int_t included = 0;\n"
            + "   gROOT->ProcessLine(" + literal("#include " + literal(slash(header))) + ", &included);\n"
            + "   if (included == 0) {\n"
            + "      bool geometry = false;\n"
            + "      for (TCanvas *c : canvases) {\n"
            + "         gROOT->ProcessLine(TString::Format(\"SphereView3D::Save((TCanvas *)0x%llx, \\\"%s%s"
            + SCENE + "\\\");\", (unsigned long long)c, " + literal(base) + ", c->GetName()));\n"
            + "         if (gROOT->ProcessLine(TString::Format(\"SphereView3D::ShowsGeometry((TCanvas *)0x%llx);\", "
            + "(unsigned long long)c)) != 0) geometry = true;\n"
            + "      }\n"
            + "      if (!geometry) gROOT->ProcessLine(" + literal("SphereView3D::Write(SphereView3D::GeometryScene(), "
            + literal(base + "geometry" + SCENE) + ");") + ");\n"
            + "   }\n"
            + "   gErrorIgnoreLevel = level;\n";
    }

    /** The scenes a demo wrote, sorted. */
    private static List<Path> scenesOf(Path folder, String prefix) {
        try (Stream<Path> list = Files.list(folder)) {
            return list.filter(p -> {
                final String n = p.getFileName().toString();
                return n.startsWith(prefix) && n.endsWith(SCENE);
            }).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * The macro to run for a demo. rootmarks turns the timings benchmarks left
     * in gBenchmark into ROOTMARKS; alone in a fresh ROOT it has nothing to
     * read, so it runs as benchmarks.C, which ends by calling it.
     */
    static String effectiveMacro(Path folder, Demo demo) {
        final String macro = demo.macro();
        if (macro != null && macro.endsWith("rootmarks.C")) {
            final String benchmarks = macro.replace("rootmarks.C", "benchmarks.C");
            if (Files.isRegularFile(folder.resolve(benchmarks))) return benchmarks;
        }
        return macro;
    }

    /** The pictures a demo wrote, in the order of the formats asked for. */
    private static List<Path> picturesOf(Path folder, String prefix, List<String> formats) {
        try (Stream<Path> list = Files.list(folder)) {
            return list.filter(p -> {
                final String n = p.getFileName().toString();
                final int dot = n.lastIndexOf('.');
                return n.startsWith(prefix) && dot > 0 && formats.contains(n.substring(dot + 1));
            }).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** ROOT's own chatter, which says nothing about the demo. */
    private static boolean noise(String line) {
        return line.startsWith("Processing ") && line.contains("sphere_demo_")
            || line.startsWith("Info in <TCanvas::Print>")
            || line.startsWith("Info in <TCanvas::MakeDefCanvas>")
            || line.isBlank();
    }

    private static String lastLines(StringBuilder output) {
        final String[] lines = output.toString().strip().split("\n");
        if (lines.length == 0 || lines[0].isBlank()) return ".";
        final int from = Math.max(0, lines.length - 3);
        return ": " + String.join(" | ", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String slash(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/');
    }

    static String literal(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

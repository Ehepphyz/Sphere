package com.sphere.core.commands;

import com.sphere.components.g4editor.G4CodeFrame;
import com.sphere.components.g4editor.G4EditorFrame;
import com.sphere.core.geant4.Budget;
import com.sphere.core.geant4.CppExport;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.GeometryFiles;
import com.sphere.core.geant4.Mass;
import com.sphere.core.geant4.Overlaps;
import com.sphere.core.geant4.Script;
import com.sphere.core.geant4.UiCommands;
import com.sphere.core.geant4.Units;
import com.sphere.core.geant4.Vec3;
import com.sphere.core.geantbackend.G4Json;
import com.sphere.core.geantbackend.Geant4AppBuilder;
import com.sphere.core.geantbackend.Geant4Bridge;
import com.sphere.core.geantbackend.Geant4BridgeCompiler;
import com.sphere.core.geantbackend.Geant4Locator;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * :g4editor and :g4 -- Geant4 in Sphere. The editor needs nothing installed;
 * the engine (geant4-bridge, built into geantbackend/) needs a Geant4, found
 * through settings.conf, the PATH, conda or a virtual environment, and never
 * ROOT.
 */
public final class G4Commands {

    private G4Commands() {
    }

    static Function<String, String> settings() {
        final SettingsManager s = new SettingsManager();
        return key -> {
            try {
                return s.getProperty(key);
            } catch (RuntimeException e) {
                return null;
            }
        };
    }

    private static Path local(String spec) {
        try {
            return com.sphere.core.fs.WorkingDirectory.get().resolve(spec).normalize();
        } catch (RuntimeException e) {
            return Path.of(spec);
        }
    }

    private static List<String> words(String input, String command) {
        final List<String> out = new ArrayList<>();
        for (String w : Handlers.args(input, command).split("\\s+")) {
            if (!w.isBlank()) {
                out.add(w);
            }
        }
        return out;
    }

    /** Opens G4editor, on a GDML file when one is given. */
    public static void openEditor(Path file) {
        SwingUtilities.invokeLater(() -> G4EditorFrame.show(file, settings()));
    }

    public static void editor(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4editor");
        final Path p = w.isEmpty() ? null : local(w.get(0));
        if (p != null && !Files.isRegularFile(p)) {
            AppLogger.error("No file " + p + ". :g4editor opens a GDML design or a ROOT geometry, or an empty design"
                + " without argument.");
            return;
        }
        openEditor(p);
        AppLogger.info("G4editor " + (p == null ? "opened" : "opened " + p.getFileName()));
    }

    /** :g4code: a Geant4 application in C++, its detector drawn as the code is typed (needs no Geant4 for that). */
    public static void code(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4code");
        final Path p = w.isEmpty() ? null : local(w.get(0));
        if (p != null && !Files.exists(p)) {
            AppLogger.error("No file or folder " + p + ". :g4code opens a Geant4 application folder (a DetectorConstruction"
                + " inside), one of its files, or a GDML file; :g4code new <folder> writes a new application.");
            return;
        }
        SwingUtilities.invokeLater(() -> G4CodeFrame.show(p, settings()));
        AppLogger.info("G4code " + (p == null ? "opened" : "opened " + p.getFileName()));
    }

    /** :g4code new: an application written by CppExport from a template or a GDML design, opened in :g4code. */
    public static void codeNew(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4code new");
        if (w.isEmpty()) {
            Handlers.usage(":g4code new <folder> [" + String.join("|", com.sphere.core.geant4.Templates.kinds())
                + "|design.gdml]");
            return;
        }
        final Path folder = local(w.get(0));
        final String from = w.size() > 1 ? w.get(1) : "calorimeter";
        Geometry g;
        if (from.toLowerCase(Locale.ROOT).endsWith(".gdml") || from.toLowerCase(Locale.ROOT).endsWith(".root")) {
            g = read(from);
            if (g == null) {
                return;
            }
        } else {
            g = Geometry.blank();
            try {
                com.sphere.core.geant4.Templates.build(g, from, Map.of(), "mm");
            } catch (Exception e) {
                AppLogger.error(e.getMessage());
                return;
            }
            g.rebuild();
        }
        try {
            if (Files.isDirectory(folder)) {
                try (var s = Files.list(folder)) {
                    if (s.findAny().isPresent()) {
                        AppLogger.error(folder + " is not empty: give a new folder");
                        return;
                    }
                }
            }
            g.setTitle(folder.getFileName().toString());
            // G4code's application: the detector is its C++, with no GDML file beside it
            final List<Path> files = new CppExport(g, CppExport.Options.defaults(g).cppOnly()).write(folder);
            AppLogger.success("Geant4 application written: " + files.size() + " files in " + folder);
        } catch (Exception e) {
            AppLogger.error("Cannot write the application: " + e.getMessage());
            return;
        }
        SwingUtilities.invokeLater(() -> G4CodeFrame.show(folder, settings()));
    }

    public static void overview(String input, CommandExecutionContext ctx) {
        AppLogger.result("Geant4 in Sphere: G4editor (works without Geant4) and geant4-bridge (needs Geant4, not ROOT)");
        AppLogger.raw("  :g4code [folder|file]                Geant4 in C++, three columns: the code drawn live in 3D");
        AppLogger.raw("  :g4code new <folder> [template]      a new Geant4 application from a template or a GDML design");
        AppLogger.raw("  :g4editor [design.gdml|geo.root]     the geometry editor: draw, dimension, analyze, run");
        AppLogger.raw("  :g4 convert <in> <out> [portable]    GDML <-> ROOT geometry (TGeoManager), without ROOT");
        AppLogger.raw("  :g4 locate                           Geant4 installations found (settings, PATH, conda, venv)");
        AppLogger.raw("  :g4 material <name> [thickness]      X0, lambda_I, Moliere radius of a NIST or GDML material");
        AppLogger.raw("  :g4 check <design.gdml> [points]     overlap check, as G4PVPlacement::CheckOverlaps");
        AppLogger.raw("  :g4 info <design.gdml>               volumes, masses, material budget along z");
        AppLogger.raw("  :g4 export <design.gdml> <folder>    a complete Geant4 C++ application (CMake)");
        AppLogger.raw("  :g4 do <design.gdml> <command...>    a G4editor console command, saved back to the file");
        AppLogger.raw("  :g4 bridge build|status|test|stop    the engine in geantbackend/");
        AppLogger.raw("  :g4 run <design.gdml> <events> [particle] [energy]   a run in Geant4");
        AppLogger.raw("  :g4 commands [/dir/|/command|word]   Geant4's UI commands: browse, help, search");
        AppLogger.raw("  :g4 ui <command>                     a Geant4 UI command, applied by the engine");
        AppLogger.raw("  :g4 macro <file.mac>                 check a macro against Geant4's commands");
        AppLogger.raw("  :g4 build <folder> [clean]           compile a Geant4 application (CMake, as the examples)");
        AppLogger.raw("  :g4 sim <folder> [macro args...]     compile if needed, then run it in batch | :g4 sim stop");
        AppLogger.raw("  :g4 bib <file.bib>                   the Geant4 papers to cite (Citations > Citation Geant4)");
        AppLogger.raw("  Geant4's units, constants, vectors and random engines are CLHEP's: ':clhep help'.");
    }

    /** The BibTeX of the papers Geant4 asks its users to cite. */
    public static void bib(String input, CommandExecutionContext ctx) {
        final String file = Handlers.args(input, ":g4 bib");
        if (file.isBlank()) {
            Handlers.usage(":g4 bib <file.bib>");
            return;
        }
        final Path out = Handlers.resolve(file.trim()).toPath();
        try {
            Files.writeString(out, com.sphere.core.geant4.Geant4Citations.bibtex(), java.nio.charset.StandardCharsets.UTF_8);
            AppLogger.result(com.sphere.core.geant4.Geant4Citations.bibliography().size() + " references written to " + out + ".");
        } catch (java.io.IOException e) {
            AppLogger.error("Cannot write " + out + ": " + e.getMessage());
        }
    }

    public static void locate(String input, CommandExecutionContext ctx) {
        final List<Geant4Locator.Install> all = Geant4Locator.find(settings());
        if (all.isEmpty()) {
            AppLogger.warn("No Geant4 found. Set GEANT4_DIR in settings.conf (on Windows a Linux path such as "
                + "/home/me/geant4 names a Geant4 inside WSL), put geant4-config on the PATH, or use a conda "
                + "environment (conda install -c conda-forge geant4). G4editor works without it.");
            return;
        }
        for (Geant4Locator.Install i : all) {
            AppLogger.raw("  " + i + (i.buildable() ? "" : "  (no CMake package: cannot build)")
                + ", " + i.datasets().size() + " data sets");
        }
    }

    public static void material(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4 material");
        if (w.isEmpty()) {
            Handlers.usage(":g4 material <name> [thickness]");
            return;
        }
        final Geometry g = Geometry.blank();
        final Script s = new Script(g, host());
        s.run("x0 " + String.join(" ", w));
    }

    private static Script.Host host() {
        return new Script.Host() {
            public void print(String t) {
                AppLogger.raw(t);
            }

            public void error(String t) {
                AppLogger.error(t);
            }
        };
    }

    /** A design from a GDML file or from the geometry of a ROOT file, recognised by its content. */
    private static Geometry read(String spec) {
        final Path p = local(spec);
        try {
            final GeometryFiles.Loaded r = GeometryFiles.read(p, null);
            for (String w : r.warnings()) {
                AppLogger.warn(w);
            }
            return r.geometry();
        } catch (Exception e) {
            AppLogger.error("Cannot read " + p + ": " + e.getMessage());
            return null;
        }
    }

    /** :g4 convert: GDML to ROOT, ROOT to GDML, without ROOT and without opening the editor. */
    public static void convert(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4 convert");
        if (w.size() < 2) {
            Handlers.usage(":g4 convert <in.gdml|in.root> <out.root|out.gdml> [portable]");
            return;
        }
        final Path in = local(w.get(0));
        final Path out = local(w.get(1));
        final GeometryFiles.Format f = GeometryFiles.byName(out) == GeometryFiles.Format.ROOT ? GeometryFiles.Format.ROOT
            : w.size() > 2 && w.get(2).startsWith("port") ? GeometryFiles.Format.GDML_PORTABLE : GeometryFiles.Format.GDML;
        try {
            final long t0 = System.nanoTime();
            final List<String> notes = GeometryFiles.convert(in, out, f);
            AppLogger.success(String.format(java.util.Locale.ROOT, "%s -> %s (%s, %.2f s)", in.getFileName(), out, f.label,
                (System.nanoTime() - t0) / 1e9));
            for (String n : notes) {
                AppLogger.raw("  " + n);
            }
        } catch (Exception e) {
            AppLogger.error("Cannot convert " + in + ": " + e.getMessage());
        }
    }

    public static void check(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4 check");
        if (w.isEmpty()) {
            Handlers.usage(":g4 check <design.gdml> [points]");
            return;
        }
        final Geometry g = read(w.get(0));
        if (g == null) {
            return;
        }
        final int res = w.size() > 1 ? Integer.parseInt(w.get(1)) : 1000;
        final long t0 = System.nanoTime();
        final List<Overlaps.Fault> f = Overlaps.check(g, res, 0, null);
        for (Overlaps.Fault x : f) {
            AppLogger.warn(x.describe());
        }
        final String msg = String.format(Locale.ROOT, "%d placements checked with %d points each in %.0f ms: %s",
            g.placements().size(), res, (System.nanoTime() - t0) / 1e6, f.isEmpty() ? "no overlap" : f.size() + " problems");
        if (f.isEmpty()) {
            AppLogger.success(msg);
        } else {
            AppLogger.error(msg);
        }
    }

    public static void info(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4 info");
        if (w.isEmpty()) {
            Handlers.usage(":g4 info <design.gdml>");
            return;
        }
        final Geometry g = read(w.get(0));
        if (g == null) {
            return;
        }
        AppLogger.result(g.volumes().size() + " volumes, " + g.placements().size() + " placements, " + g.instances().size()
            + " copies, " + g.defines().size() + " variables");
        for (Mass.Line l : Mass.report(g)) {
            AppLogger.raw(String.format(Locale.ROOT, "  %-24s %-26s x%-5d own %-12s total %s", l.lv().name(), l.lv().material(),
                l.placements(), Units.best(l.ownMass(), Units.Category.MASS), Units.best(l.totalMass(), Units.Category.MASS)));
        }
        final Budget.RayReport r = Budget.ray(g, Vec3.ZERO, Vec3.Z, true);
        AppLogger.raw(String.format(Locale.ROOT, "  along +z from the origin: %.4g X0, %.4g lambda_I over %s", r.x0(), r.lambda(),
            Units.best(r.path(), Units.Category.LENGTH)));
        for (Geometry.Issue i : g.issues()) {
            AppLogger.error(i.message());
        }
    }

    public static void export(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4 export");
        if (w.size() < 2) {
            Handlers.usage(":g4 export <design.gdml> <folder>");
            return;
        }
        final Geometry g = read(w.get(0));
        if (g == null) {
            return;
        }
        try {
            final List<Path> files = new CppExport(g, CppExport.Options.defaults(g)).write(local(w.get(1)));
            AppLogger.success("Geant4 application written: " + files.size() + " files in " + local(w.get(1)));
        } catch (Exception e) {
            AppLogger.error("Cannot export: " + e.getMessage());
        }
    }

    public static void doCommand(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4 do");
        if (w.size() < 2) {
            Handlers.usage(":g4 do <design.gdml> <G4editor command>");
            return;
        }
        final Path p = local(w.get(0));
        final Geometry g = Files.isRegularFile(p) ? read(w.get(0)) : Geometry.blank();
        if (g == null) {
            return;
        }
        final Script s = new Script(g, new Script.Host() {
            public void print(String t) {
                AppLogger.raw(t);
            }

            public void error(String t) {
                AppLogger.error(t);
            }

            public void edit(String label, Runnable r) {
                r.run();
                g.rebuild();
            }
        });
        s.run(String.join(" ", w.subList(1, w.size())));
        try {
            final GeometryFiles.Format f = Files.isRegularFile(p) ? GeometryFiles.detect(p) : GeometryFiles.byName(p);
            for (String n : GeometryFiles.write(g, p, f)) {
                AppLogger.warn(n);
            }
            AppLogger.success("saved " + p);
        } catch (Exception e) {
            AppLogger.error("Cannot save " + p + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // The engine
    // ------------------------------------------------------------------

    private static volatile Geant4Bridge bridge;

    public static void bridge(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4 bridge");
        final String what = w.isEmpty() ? "status" : w.get(0);
        switch (what) {
            case "build" -> new Thread(() -> {
                final Geant4BridgeCompiler.Result r = Geant4BridgeCompiler.getOrBuild(settings(), AppLogger::raw, true);
                if (r.ok()) {
                    AppLogger.success(r.message() + ": " + r.binary());
                } else {
                    AppLogger.error(r.message());
                }
            }, "g4-bridge-build").start();
            case "test" -> new Thread(() -> {
                // The channel without Geant4: the transport engine, both transports, a round trip each
                final Geant4BridgeCompiler.Result r = Geant4BridgeCompiler.buildTestEngine(settings(), AppLogger::debug);
                if (!r.ok()) {
                    AppLogger.error(r.message());
                    return;
                }
                for (Geant4Bridge.Transport t : Geant4Bridge.Transport.values()) {
                    final long t0 = System.nanoTime();
                    try (Geant4Bridge b = Geant4Bridge.start(List.of(r.binary().toString()), Map.of(),
                        Geant4BridgeCompiler.runtimeDir(), t, AppLogger::warn)) {
                        final Map<String, Object> pong = b.call("ping", Map.of()).get();
                        AppLogger.success(String.format(Locale.ROOT, "%s transport: %s in %.0f ms", t,
                            G4Json.str(pong, "engine", "?"), (System.nanoTime() - t0) / 1e6));
                    } catch (Exception e) {
                        AppLogger.error(t + " transport failed: " + e.getMessage());
                    }
                }
            }, "g4-bridge-test").start();
            case "stop" -> {
                if (bridge != null) {
                    bridge.close();
                    bridge = null;
                    AppLogger.info("geant4-bridge stopped");
                }
            }
            default -> {
                final Path bin = Geant4BridgeCompiler.binary();
                AppLogger.raw("  engine: " + (Files.isRegularFile(bin) ? bin : "not built (:g4 bridge build)"));
                AppLogger.raw("  running: " + (bridge != null && bridge.alive() ? bridge.description() : "no"));
                final Geant4Locator.Install i = Geant4Locator.best(settings());
                AppLogger.raw("  Geant4: " + (i == null ? "none found (:g4 locate)" : i.toString()));
            }
        }
    }

    public static void run(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4 run");
        if (w.size() < 2) {
            Handlers.usage(":g4 run <design.gdml> <events> [particle] [energy, e.g. 1GeV]");
            return;
        }
        final Path design = local(w.get(0));
        new Thread(() -> {
            try {
                if (bridge == null || !bridge.alive()) {
                    final Geant4BridgeCompiler.Result r = Geant4BridgeCompiler.getOrBuild(settings(), AppLogger::raw, false);
                    if (!r.ok()) {
                        AppLogger.error(r.message());
                        return;
                    }
                    bridge = Geant4BridgeCompiler.start(r, settings(), AppLogger::warn);
                } else {
                    bridge.close();
                    bridge = null;
                    run(input, ctx);
                    return;
                }
                bridge.call("load", Map.of("path", bridge.enginePath(design))).get();
                final Map<String, Object> args = new LinkedHashMap<>();
                args.put("events", Integer.parseInt(w.get(1)));
                args.put("particle", w.size() > 2 ? w.get(2) : "e-");
                args.put("energy_MeV", w.size() > 3 ? com.sphere.core.geant4.Expr.eval(com.sphere.core.geant4.Inputs.normalize(
                    w.get(3), Units.Category.ENERGY, "mm"), null) : 1000.0);
                args.put("trackEvents", 0);
                final Geometry g = read(w.get(0));
                if (g != null) {
                    args.put("physics", com.sphere.core.geant4.Advisor.recommendedPhysicsList(g));
                    args.put("position_mm", List.of(0, 0, -0.99 * g.world().solid().extent().maxZ()));
                }
                final Map<String, Object> r = bridge.call("run", args,
                    (f, m) -> AppLogger.stream(String.format(Locale.ROOT, "  %3.0f%% %s", 100 * f, m))).get();
                AppLogger.success(String.format(Locale.ROOT, "%d events in %.1f s (%s)", (int) G4Json.num(r, "events", 0),
                    G4Json.num(r, "time_ms", 0) / 1000, G4Json.str(r, "physics", "")));
                for (Map.Entry<String, Object> e : G4Json.obj(r, "edep").entrySet()) {
                    AppLogger.raw(String.format(Locale.ROOT, "  %-24s mean %s  rms %s", e.getKey(),
                        Units.best(G4Json.num(e.getValue(), "mean_MeV", 0), Units.Category.ENERGY),
                        Units.best(G4Json.num(e.getValue(), "rms_MeV", 0), Units.Category.ENERGY)));
                }
            } catch (Exception e) {
                AppLogger.error("geant4-bridge: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            }
        }, "g4-run").start();
    }

    // ------------------------------------------------------------------
    // Geant4's UI commands
    // ------------------------------------------------------------------

    private static UiCommands catalog() {
        try {
            return UiCommands.get();
        } catch (Exception e) {
            AppLogger.error("Cannot read the Geant4 command catalog: " + e.getMessage());
            return null;
        }
    }

    /** :g4 commands: the top directories, a directory's content, a command's help, or the commands holding a word. */
    public static void uiCommands(String input, CommandExecutionContext ctx) {
        final UiCommands u = catalog();
        if (u == null) {
            return;
        }
        final String q = Handlers.args(input, ":g4 commands");
        if (q.isEmpty() || q.equals("/")) {
            AppLogger.result(u.size() + " Geant4 UI commands (" + u.version() + "), by directory:");
            for (String d : u.subdirectories("/")) {
                listDirectory(u, d);
            }
            AppLogger.raw("  :g4 commands /run/   a directory     :g4 commands /run/beamOn   a command     "
                + ":g4 commands energy   a search");
            return;
        }
        if (q.startsWith("/")) {
            final UiCommands.Command c = u.find(q);
            if (c != null) {
                for (String l : u.describe(c)) {
                    AppLogger.raw(l);
                }
                return;
            }
            final UiCommands.Directory d = u.directory(q);
            if (d != null) {
                AppLogger.result(d.path() + (d.guidance().isEmpty() ? "" : "  " + d.guidance().get(0)));
                for (String sub : u.subdirectories(d.path())) {
                    listDirectory(u, sub);
                }
                for (UiCommands.Command x : u.in(d.path())) {
                    AppLogger.raw(String.format(Locale.ROOT, "  %-44s %s", x.path(), x.summary()));
                }
                return;
            }
            final List<String> near = u.complete(q);
            if (!near.isEmpty()) {
                AppLogger.result("Commands and directories beginning with " + q + ":");
                near.forEach(p -> AppLogger.raw("  " + p));
                return;
            }
        }
        final List<UiCommands.Command> found = u.search(q.startsWith("/") ? q.substring(q.lastIndexOf('/') + 1) : q);
        if (found.isEmpty()) {
            AppLogger.warn("No Geant4 command matches " + q);
            return;
        }
        AppLogger.result(found.size() + " commands match " + q + (found.size() > 60 ? " (the first 60)" : "") + ":");
        for (UiCommands.Command x : found.subList(0, Math.min(60, found.size()))) {
            AppLogger.raw(String.format(Locale.ROOT, "  %-44s %s", x.path(), x.summary()));
        }
    }

    private static void listDirectory(UiCommands u, String d) {
        final UiCommands.Directory dir = u.directory(d);
        AppLogger.raw(String.format(Locale.ROOT, "  %-34s %4d  %s", d, u.countUnder(d),
            dir == null || dir.guidance().isEmpty() ? "" : dir.guidance().get(0).strip()));
    }

    /** The engine, started when it is not running (built first when needed). */
    private static Geant4Bridge engine() throws Exception {
        if (bridge != null && bridge.alive()) {
            return bridge;
        }
        final Geant4BridgeCompiler.Result r = Geant4BridgeCompiler.getOrBuild(settings(), AppLogger::raw, false);
        if (!r.ok()) {
            throw new IllegalStateException(r.message());
        }
        bridge = Geant4BridgeCompiler.start(r, settings(), AppLogger::warn);
        return bridge;
    }

    /** :g4 ui: one Geant4 UI command applied by geant4-bridge, checked against the catalog first. */
    public static void ui(String input, CommandExecutionContext ctx) {
        final String command = Handlers.args(input, ":g4 ui");
        if (!command.startsWith("/")) {
            Handlers.usage(":g4 ui </directory/command> [parameters]   e.g. :g4 ui /run/verbose 1");
            return;
        }
        final UiCommands u = catalog();
        if (u != null) {
            for (UiCommands.Finding f : u.check(command)) {
                if (f.kind() != UiCommands.Finding.Kind.FOREIGN) {
                    AppLogger.warn(f.message());
                }
            }
        }
        new Thread(() -> {
            try {
                final Map<String, Object> r = engine().call("ui", Map.of("command", command)).get();
                final String out = G4Json.str(r, "output", "");
                if (!out.isBlank()) {
                    out.lines().forEach(AppLogger::stream);
                }
                final int code = (int) G4Json.num(r, "code", -1);
                if (code == 0) {
                    AppLogger.success(command);
                } else {
                    AppLogger.error(command + ": " + uiFailure(code));
                }
            } catch (Exception e) {
                AppLogger.error("geant4-bridge: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            }
        }, "g4-ui").start();
    }

    /** G4UImanager::ApplyCommand's code: the hundreds say what, the units which parameter. */
    private static String uiFailure(int code) {
        final String what = switch (code / 100) {
            case 1 -> "command not found";
            case 2 -> "not available in the current application state";
            case 3 -> "parameter out of range";
            case 4 -> "parameter unreadable";
            case 5 -> "parameter not among the candidates";
            case 6 -> "alias not found";
            default -> "failed (code " + code + ")";
        };
        return code % 100 != 0 && code / 100 >= 3 && code / 100 <= 5 ? what + " (parameter " + code % 100 + ")" : what;
    }

    /** :g4 macro: every line of a macro checked as G4UImanager would, without Geant4. */
    public static void macro(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4 macro");
        if (w.isEmpty()) {
            Handlers.usage(":g4 macro <file.mac>");
            return;
        }
        final UiCommands u = catalog();
        if (u == null) {
            return;
        }
        final Path p = local(w.get(0));
        checkMacro(u, p, true);
    }

    /** Problems of a macro, printed with their line numbers; the count of them. */
    private static int checkMacro(UiCommands u, Path p, boolean verbose) {
        final List<String> lines;
        try {
            lines = Files.readAllLines(p);
        } catch (Exception e) {
            AppLogger.error("Cannot read " + p + ": " + e.getMessage());
            return -1;
        }
        int problems = 0, commands = 0;
        final List<String> foreign = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            final String l = lines.get(i).strip();
            if (l.startsWith("/")) {
                commands++;
            }
            for (UiCommands.Finding f : u.check(l)) {
                if (f.error()) {
                    AppLogger.warn(p.getFileName() + ":" + (i + 1) + "  " + f.message());
                    problems++;
                } else if (verbose || f.kind() == UiCommands.Finding.Kind.NOTE) {
                    AppLogger.info(p.getFileName() + ":" + (i + 1) + "  " + f.message());
                } else {
                    foreign.add(UiCommands.tokens(l).get(0));
                }
            }
        }
        if (!foreign.isEmpty()) {
            AppLogger.info(p.getFileName() + ": not Geant4's, so the application's own: "
                + String.join(" ", new java.util.LinkedHashSet<>(foreign)));
        }
        if (problems == 0 && verbose) {
            AppLogger.success(p.getFileName() + ": " + commands + " commands, none Geant4 would refuse");
        } else if (problems > 0) {
            AppLogger.warn(p.getFileName() + ": " + problems + " problem(s) in " + commands + " commands");
        }
        return problems;
    }

    // ------------------------------------------------------------------
    // A Geant4 application in C++: compiled with CMake, run in batch
    // ------------------------------------------------------------------

    /** :g4 build: configure and compile an application folder against the Geant4 found. */
    public static void buildApp(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4 build");
        if (w.isEmpty()) {
            Handlers.usage(":g4 build <application folder> [clean]");
            return;
        }
        if (Geant4AppBuilder.running()) {
            AppLogger.error("A Geant4 build or run is going on (:g4 sim stop ends it)");
            return;
        }
        final Path folder = local(w.get(0));
        final boolean clean = w.size() > 1 && w.get(1).equalsIgnoreCase("clean");
        new Thread(() -> {
            final long t0 = System.nanoTime();
            final Geant4AppBuilder.Build b = Geant4AppBuilder.build(folder, clean, settings(), AppLogger::stream);
            if (b.ok()) {
                AppLogger.success(String.format(Locale.ROOT, "%s (%.0f s)", b.message(), (System.nanoTime() - t0) / 1e9));
            } else {
                AppLogger.error(b.message());
            }
        }, "g4-build").start();
    }

    /** :g4 sim: build (incrementally), then run the program in batch on a macro, from its build directory. */
    public static void sim(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":g4 sim");
        if (w.isEmpty()) {
            Handlers.usage(":g4 sim <application folder> [macro.mac] [arguments...]   |   :g4 sim stop");
            return;
        }
        if (w.get(0).equals("stop")) {
            if (Geant4AppBuilder.stop()) {
                AppLogger.info("Geant4 build or run stopped");
            } else {
                AppLogger.info("No Geant4 build or run is going on");
            }
            return;
        }
        if (Geant4AppBuilder.running()) {
            AppLogger.error("A Geant4 build or run is going on (:g4 sim stop ends it)");
            return;
        }
        final Path folder = local(w.get(0));
        final List<String> given = w.subList(1, w.size());
        new Thread(() -> {
            final Geant4AppBuilder.Build b = Geant4AppBuilder.build(folder, false, settings(), AppLogger::stream);
            if (!b.ok()) {
                AppLogger.error(b.message());
                return;
            }
            final List<String> args = new ArrayList<>(given);
            if (args.isEmpty()) {
                // Without a macro the examples start an interactive session, which Sphere's console cannot drive
                final String m = Geant4AppBuilder.defaultMacro(b);
                if (m == null) {
                    AppLogger.error("Give the macro to run: :g4 sim " + w.get(0) + " <macro.mac> (no run1.mac, run.mac"
                        + " nor " + b.program() + ".in in " + b.folder() + ")");
                    return;
                }
                args.add(m);
            }
            final UiCommands u = catalog();
            final Path macro = b.folder().resolve(args.get(0));
            if (u != null && Files.isRegularFile(macro) && (args.get(0).endsWith(".mac") || args.get(0).endsWith(".in"))) {
                checkMacro(u, macro, false);
            }
            if (b.g4().datasets().isEmpty() && System.getenv("G4LEDATA") == null) {
                AppLogger.warn("No Geant4 data sets found with " + b.g4() + ": a run needing physics data will stop at"
                    + " /run/initialize (GEANT4_DATA_DIR, or install them with the Geant4 build)");
            }
            final long start = System.currentTimeMillis();
            try {
                AppLogger.info("Running " + b.program() + " " + String.join(" ", args) + " in " + b.buildDir());
                final int code = Geant4AppBuilder.run(b, b.program(), args, settings(), AppLogger::stream);
                final String took = String.format(Locale.ROOT, "%.1f s", (System.currentTimeMillis() - start) / 1000.0);
                if (code == 0) {
                    AppLogger.success(b.program() + " finished in " + took);
                } else {
                    AppLogger.error(b.program() + " exited with code " + code + " after " + took);
                }
                for (Path out : Geant4AppBuilder.outputs(b, start)) {
                    AppLogger.raw("  wrote " + out);
                }
                if (b.inWsl()) {
                    final String distro = b.g4().wsl().distro();
                    AppLogger.raw("  its files are in " + b.buildDir() + (distro == null ? "" : ", from Windows \\\\wsl.localhost\\"
                        + distro + b.buildDir().replace('/', '\\')));
                }
            } catch (Exception e) {
                AppLogger.error("Cannot run " + b.program() + ": " + e.getMessage());
            }
        }, "g4-sim").start();
    }
}

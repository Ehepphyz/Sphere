package com.sphere.core.commands;

import com.sphere.components.rootview.RootPlotsPanel;
import com.sphere.core.rootbackend.RootDemos;
import com.sphere.core.rootbackend.RootDemos.Demo;
import com.sphere.core.rootbackend.RootDemos.Outcome;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * ROOT's ".demo", and what it becomes in Sphere: the demos run in ROOTs of
 * their own and every canvas they draw lands in the Plots tab; "all" runs
 * every one as a test of the display, with a contact sheet of what they drew
 * and what changed since the last run. Also the tutorials beyond the demos,
 * and the formats canvases are written in.
 */
final class RootDemoCommands {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** A difference smaller than this share of the pixels is antialiasing, not a change. */
    private static final double UNCHANGED = 0.001;

    /** Where the demos run, for the whole session, so hsimple.root and its kin stay together. */
    private static Path work;

    private RootDemoCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    static void register() {
        CommandDefinitions.register(":root demo", "ROOT's demos (as .demo), their canvases in the Plots tab. "
            + "Usage: :root demo [name|tutorial.C ...]", RootDemoCommands::demo);
        CommandDefinitions.register(":root demo list", "The demos of the ROOT installed, and what each shows",
            RootDemoCommands::list);
        CommandDefinitions.register(":root demo all", "Every demo, as a test of the display: report, contact sheet, "
            + "and what changed since the last run. Usage: :root demo all [--jobs n]", RootDemoCommands::all);
        CommandDefinitions.register(":root tutorials", "ROOT's tutorials, by folder, or those matching a word; "
            + "':root demo <name>' runs one. Usage: :root tutorials [word]", RootDemoCommands::tutorials);
        CommandDefinitions.register(":root browser", "Sphere's TBrowser: demos' canvases live in 3D, pictures, "
            + ".root files, analysis (as new TBrowser). Usage: :root browser [file.root|scene.sphere.json|picture ...]",
            RootDemoCommands::browser);
        com.sphere.components.spherebrowser.SphereBrowser.demoLauncher = () -> RootDemoBar.show(RootDemos.demos());
        CommandDefinitions.register(":root canvas formats", "The formats the canvases reach the Plots tab in. "
            + "Usage: :root canvas formats [png] [svg] [jpg] [tiff] [gif] | none", RootDemoCommands::formats);
    }

    /* ------------------------------------------------------------------ */
    /* .demo                                                               */
    /* ------------------------------------------------------------------ */

    static void demo(String i, CommandExecutionContext c) {
        final String[] names = Handlers.words(Handlers.args(i, ":root demo"));
        if (names.length > 0 && names[0].equalsIgnoreCase("all")) {
            all(i, c);
            return;
        }
        if (names.length > 0 && names[0].equalsIgnoreCase("list")) {
            list(i, c);
            return;
        }
        if (names.length == 0) {
            open(c);
            return;
        }
        final List<Demo> chosen = new ArrayList<>();
        for (String n : names) {
            final Demo d = RootDemos.find(n);
            if (d == null) {
                AppLogger.error("No demo or tutorial called " + n + ". ':root demo list' and ':root tutorials' name them.");
                return;
            }
            chosen.add(d);
        }
        runShown(chosen);
    }

    /** ROOT's control bar of demos, as a window of buttons; the list when there is no screen. */
    static void open(CommandExecutionContext c) {
        final List<Demo> demos = RootDemos.demos();
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            list(":root demo list", c);
            return;
        }
        RootDemoBar.show(demos);
        AppLogger.result("ROOT demos opened: each button runs one, its canvases land in the Plots tab.");
    }

    static void list(String i, CommandExecutionContext c) {
        final Path folder = RootDemos.tutorials();
        AppLogger.result("ROOT demos" + (folder == null ? " (tutorials folder not found; set ROOT_TUTORIALS)"
            : " from " + folder.resolve("demos.C")) + ":");
        for (Demo d : RootDemos.demos()) {
            AppLogger.raw(String.format("  %-14s %-44s %s", d.label(),
                d.macro() == null ? d.action() : d.macro(), d.description()));
        }
        AppLogger.raw("  ':root demo <name> ...' runs some, ':root demo all' all of them; any tutorial runs the same way.");
    }

    /**
     * Sphere's TBrowser, which is what ROOT's "new TBrowser" and the demos'
     * "browser" button open here.
     */
    static void browser(String i, CommandExecutionContext c) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            AppLogger.error("Sphere's TBrowser needs a screen.");
            return;
        }
        final List<Path> paths = new ArrayList<>();
        for (String w : Handlers.words(Handlers.args(i, ":root browser"))) {
            final Path p = com.sphere.core.fs.WorkingDirectory.get().resolve(w.replace("\"", ""));
            if (Files.exists(p)) paths.add(p);
            else AppLogger.warn("No such file: " + p);
        }
        com.sphere.components.spherebrowser.SphereBrowser.open(paths);
        AppLogger.result("Sphere's TBrowser opened: demos' canvases live in 3D, pictures, .root files, analysis.");
    }

    /** Whether a demo is ROOT's browser button rather than a macro. */
    static boolean isBrowser(Demo d) {
        return d.action().contains("TBrowser");
    }

    /** Runs demos chosen by name, their canvases straight into the Plots tab and live into the TBrowser. */
    static void runShown(List<Demo> demos) {
        final List<String> formats = demoFormats(false);
        if (demos.stream().anyMatch(RootDemoCommands::isBrowser)) browser(":root browser", null);
        demos = demos.stream().filter(d -> !isBrowser(d)).toList();
        if (demos.isEmpty()) return;
        try {
            RootDemos.run(demos, RootPlotsPanel.plotsFolder(), workFolder(), formats, jobs(null), o -> {
                report(o, true);
                for (Path p : o.pictures()) RootPlotsPanel.showFile(p.toFile());
                if (!o.scenes().isEmpty() || !o.pictures().isEmpty()) {
                    com.sphere.components.spherebrowser.SphereBrowser.showDemo(o.demo().label(), o.scenes(),
                        o.pictures());
                }
            });
        } catch (IOException e) {
            AppLogger.error(e.getMessage());
        }
    }

    /** One demo's outcome, as it ends. */
    static void report(Outcome o, boolean withOutput) {
        final int canvases = (int) o.pictures().stream()
            .map(p -> p.getFileName().toString().replaceFirst("\\.[^.]+$", "")).distinct().count();
        final String line = String.format(Locale.ROOT, "%-14s %5.1f s  %d canvas%s", o.demo().label(), o.seconds(),
            canvases, canvases == 1 ? "" : "es");
        final String live = o.scenes().isEmpty() ? "" : ", " + o.scenes().size() + " live in the TBrowser";
        if (o.ok()) {
            AppLogger.result(line + (canvases > 0 ? " -> Plots" + live : !live.isEmpty() ? " ->" + live.substring(1)
                : o.note() == null ? "" : ": " + o.note()));
        } else {
            AppLogger.error(line + ": " + (o.errors().isEmpty() ? "failed" : o.errors().get(0)));
            for (int k = 1; k < Math.min(o.errors().size(), 4); k++) AppLogger.raw("    " + o.errors().get(k));
        }
        if (withOutput && !o.output().isBlank()) {
            final String[] lines = o.output().split("\n");
            for (int k = 0; k < Math.min(lines.length, 40); k++) AppLogger.raw("    " + lines[k]);
            if (lines.length > 40) AppLogger.raw("    ... " + (lines.length - 40) + " more lines");
        }
    }

    /* ------------------------------------------------------------------ */
    /* all: the display, tested                                            */
    /* ------------------------------------------------------------------ */

    static void all(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root demo all"));
        Integer asked = null;
        for (int k = 0; k + 1 < w.length; k++) {
            if (w[k].equals("--jobs")) {
                try {
                    asked = Integer.parseInt(w[k + 1]);
                } catch (NumberFormatException ignored) {
                    // the default below
                }
            }
        }
        final List<Demo> demos = RootDemos.demos().stream().filter(d -> d.macro() != null).toList();
        final List<String> formats = demoFormats(true);
        final Path runs = RootPlotsPanel.plotsFolder().resolve("root-demos");
        final Path previous = latestRun(runs);
        final String stamp = LocalDateTime.now().format(STAMP);
        final Path run = runs.resolve(stamp);
        final int jobs = jobs(asked);
        AppLogger.info(String.format("Running %d demos, %d at a time, canvases as %s into %s ...",
            demos.size(), jobs, String.join(" ", formats), run));
        final long t0 = System.nanoTime();
        final List<Outcome> outcomes;
        try {
            outcomes = RootDemos.run(demos, run, workFolder(), formats, jobs, o -> report(o, false));
        } catch (IOException e) {
            AppLogger.error(e.getMessage());
            return;
        }
        final long ok = outcomes.stream().filter(Outcome::ok).count();
        final int pictures = outcomes.stream().mapToInt(o -> o.pictures().size()).sum();
        final String summary = String.format(Locale.ROOT, "%d of %d demos drew without error, %d pictures, in %.0f s.",
            ok, outcomes.size(), pictures, (System.nanoTime() - t0) / 1e9);
        if (ok == outcomes.size()) AppLogger.success(summary);
        else AppLogger.warn(summary);

        try {
            final BufferedImage sheet = RootDemoSheet.contactSheet(outcomes);
            if (sheet != null) {
                final Path file = run.resolve("contact_sheet.png");
                javax.imageio.ImageIO.write(sheet, "png", file.toFile());
                RootPlotsPanel.showRendered(sheet, "root-demos-" + stamp);
                AppLogger.raw("  contact sheet: " + file);
            }
        } catch (IOException | RuntimeException e) {
            AppLogger.warn("No contact sheet: " + e.getMessage());
        }
        compare(previous, run);
    }

    /** What changed since the run before: the same picture, pixel by pixel. */
    private static void compare(Path previous, Path run) {
        if (previous == null) {
            AppLogger.raw("  First run here: the next ':root demo all' will say what changed since this one.");
            return;
        }
        final Map<String, Path> before = pngs(previous);
        final Map<String, Path> now = pngs(run);
        int same = 0;
        final List<String> changed = new ArrayList<>();
        final List<String> gone = new ArrayList<>();
        for (Map.Entry<String, Path> e : before.entrySet()) {
            final Path mine = now.get(e.getKey());
            if (mine == null) {
                gone.add(e.getKey());
                continue;
            }
            try {
                final RootDemoSheet.Difference d = RootDemoSheet.difference(e.getValue(), mine);
                if (d.share() <= UNCHANGED) {
                    same++;
                } else {
                    final Path diff = run.resolve("diff_" + e.getKey());
                    javax.imageio.ImageIO.write(d.picture(), "png", diff.toFile());
                    changed.add(String.format(Locale.ROOT, "%s (%.1f%% of the pixels%s) -> %s", e.getKey(),
                        100 * d.share(), d.sizeChanged() ? ", new size" : "", diff.getFileName()));
                }
            } catch (IOException | RuntimeException unreadable) {
                changed.add(e.getKey() + " (unreadable: " + unreadable.getMessage() + ")");
            }
        }
        final List<String> added = now.keySet().stream().filter(k -> !before.containsKey(k)).sorted().toList();
        AppLogger.result("Against the run of " + previous.getFileName() + ": " + same + " pictures identical, "
            + changed.size() + " changed, " + added.size() + " new, " + gone.size() + " missing.");
        for (String s : changed) AppLogger.raw("  changed  " + s);
        for (String s : added) AppLogger.raw("  new      " + s);
        for (String s : gone) AppLogger.raw("  missing  " + s);
    }

    private static Map<String, Path> pngs(Path folder) {
        final Map<String, Path> out = new LinkedHashMap<>();
        try (Stream<Path> list = Files.list(folder)) {
            list.filter(p -> {
                final String n = p.getFileName().toString();
                return n.startsWith("demo_") && n.endsWith(".png");
            }).sorted().forEach(p -> out.put(p.getFileName().toString(), p));
        } catch (IOException e) {
            // an empty map
        }
        return out;
    }

    private static Path latestRun(Path runs) {
        if (!Files.isDirectory(runs)) return null;
        try (Stream<Path> list = Files.list(runs)) {
            return list.filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().matches("\\d{8}-\\d{6}"))
                .max(Path::compareTo).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* tutorials, formats                                                  */
    /* ------------------------------------------------------------------ */

    static void tutorials(String i, CommandExecutionContext c) {
        final Path folder = RootDemos.tutorials();
        if (folder == null) {
            AppLogger.error("ROOT's tutorials folder was not found: set ROOT_TUTORIALS in settings.conf.");
            return;
        }
        final String word = Handlers.args(i, ":root tutorials").strip().toLowerCase(Locale.ROOT);
        final List<Path> all = RootDemos.tutorialFiles(folder);
        if (word.isEmpty()) {
            final Map<String, Integer> byFolder = new java.util.TreeMap<>();
            for (Path p : all) {
                final Path rel = folder.relativize(p);
                final String top = rel.getNameCount() > 1 ? rel.getName(0).toString() : ".";
                final String sub = rel.getNameCount() > 2 ? top + "/" + rel.getName(1) : top;
                byFolder.merge(sub, 1, Integer::sum);
            }
            AppLogger.result(all.size() + " C++ tutorials in " + folder + ":");
            byFolder.forEach((k, n) -> AppLogger.raw(String.format("  %-34s %4d", k, n)));
            AppLogger.raw("  ':root tutorials <word>' lists those matching it; ':root demo <name>' runs one.");
            return;
        }
        int shown = 0;
        for (Path p : all) {
            final String rel = folder.relativize(p).toString().replace('\\', '/');
            if (!rel.toLowerCase(Locale.ROOT).contains(word)) continue;
            if (shown++ < 80) AppLogger.raw(String.format("  %-52s %s", rel, RootDemos.summary(p)));
        }
        if (shown == 0) AppLogger.result("No tutorial matches " + word + ".");
        else if (shown > 80) AppLogger.raw("  ... " + (shown - 80) + " more; a longer word narrows it.");
    }

    /**
     * Shows or sets the formats. Kept in settings.conf for the next sessions,
     * and told to the running engine through ROOT's own gEnv, which a .rootrc
     * can also set (Sphere.Canvas.Formats).
     */
    static void formats(String i, CommandExecutionContext c) {
        final String asked = Handlers.args(i, ":root canvas formats").strip();
        if (asked.isEmpty()) {
            AppLogger.result("Canvases reach the Plots tab as " + String.join(", ", RootDemos.canvasFormats())
                + ". Formats known: " + String.join(", ", RootDemos.FORMATS) + "; 'none' stops it.");
            return;
        }
        final boolean off = asked.equalsIgnoreCase("none");
        final List<String> chosen = RootDemos.formatsOf(asked);
        final String spec = off ? "none" : String.join(" ", chosen);
        final SettingsManager settings = new SettingsManager();
        settings.setProperty("ROOT_CANVAS_FORMATS", spec);
        settings.save();
        final com.sphere.core.rootbackend.RootBackend b = Handlers.backend(c);
        if (b != null && b.isAvailable()) {
            b.executeClingAwait("gEnv->SetValue(\"Sphere.Canvas.Formats\", \"" + spec + "\")", Handlers.TIMEOUT_MS);
        }
        AppLogger.result(off ? "Canvases are no longer written for the Plots tab."
            : "Canvases now reach the Plots tab as " + String.join(", ", chosen) + ".");
    }

    /* ------------------------------------------------------------------ */

    /**
     * The formats a demo writes: those of ':root canvas formats', png when they
     * are "none" (a demo is run to be seen), and png always for a run of all,
     * whose pictures are compared from one run to the next.
     */
    static List<String> demoFormats(boolean comparing) {
        final List<String> formats = new ArrayList<>(RootDemos.canvasFormats());
        if (formats.isEmpty() || comparing && !formats.contains("png")) formats.add(0, "png");
        return formats;
    }

    static synchronized Path workFolder() throws IOException {
        if (work == null || !Files.isDirectory(work)) {
            work = Files.createTempDirectory("sphere-root-demos");
            work.toFile().deleteOnExit();
        }
        return work;
    }

    /** Half the cores, at most four: each ROOT takes a core and a few hundred megabytes. */
    private static int jobs(Integer asked) {
        if (asked != null && asked > 0) return Math.min(asked, 16);
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }
}

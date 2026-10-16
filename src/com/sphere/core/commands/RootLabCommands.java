package com.sphere.core.commands;

import com.sphere.core.rootio.RTree;
import com.sphere.core.rootio.RootIO;
import com.sphere.core.rootlab.ColumnStats;
import com.sphere.core.rootlab.DataFormats;
import com.sphere.core.rootlab.FileCompare;
import com.sphere.core.rootlab.RootFsck;
import com.sphere.core.rootlab.bench.LabCatalog;
import com.sphere.core.rootlab.bench.LabResult;
import com.sphere.core.rootlab.bench.LabRunner;
import com.sphere.core.rootlab.bench.LabSuite;
import com.sphere.utils.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * :rootlab -- the ROOT Lab from the console: ROOT's test programs run in
 * Java, and CERN data files checked, compared and summarised, local or remote.
 */
public final class RootLabCommands {

    private RootLabCommands() {
    }

    public static void overview(String input, CommandExecutionContext ctx) {
        AppLogger.result("ROOT Lab: CERN data files and ROOT's test programs, in Java");
        AppLogger.raw("  :rootlab test [suite ...] [--network]   run the ported ROOT tests (suites: "
            + String.join(", ", LabCatalog.all().stream().map(LabSuite::id).toList()) + ")");
        AppLogger.raw("  :rootlab open <file|folder|url>          open in the ROOT Lab tab");
        AppLogger.raw("  :rootlab fsck <file|url> [--deep]        check a ROOT file record by record");
        AppLogger.raw("  :rootlab compare <a> <b>                 compare two files histogram by histogram");
        AppLogger.raw("  :rootlab stats <file|url> <tree> <branch> [entries]   statistics of a branch");
        AppLogger.raw("  :rootlab sniff <file>                    what a data file is");
        AppLogger.raw("  Locations: paths, https://..., root://eospublic.cern.ch//eos/opendata/..., archive.zip#file.root");
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

    private static Path local(String spec) {
        try {
            final Path p = com.sphere.core.fs.WorkingDirectory.get().resolve(spec);
            return Files.exists(p) ? p : null;
        } catch (RuntimeException notAPath) {
            return null;
        }
    }

    private static RootIO open(String spec) throws IOException {
        final Path p = local(spec);
        return p != null ? RootIO.open(p) : RootIO.open(spec);
    }

    public static void test(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":rootlab test");
        boolean network = false;
        final List<LabSuite> suites = new ArrayList<>();
        for (String a : w) {
            if (a.equals("--network") || a.equals("-n")) {
                network = true;
            } else if (!a.equals("all")) {
                final LabSuite s = LabCatalog.find(a);
                if (s == null) {
                    AppLogger.error("No ROOT Lab suite " + a + ". :rootlab lists them.");
                    return;
                }
                suites.add(s);
            }
        }
        final List<LabSuite> run = suites.isEmpty() ? LabCatalog.all() : suites;
        AppLogger.info("Running " + run.size() + " suite(s) of ROOT's tests in Java"
            + (network ? ", reading root.cern over HTTPS" : "") + "...");
        final LabRunner.Summary s = new LabRunner().run(run, network, new LabRunner.Listener() {
            @Override
            public void testFinished(LabSuite suite, LabResult r) {
                final String line = String.format(Locale.ROOT, "%-16s %s", suite.id(), LabRunner.line(0, r.title(), r.status()));
                switch (r.status()) {
                    case PASSED -> AppLogger.raw(line);
                    case SKIPPED -> AppLogger.warn(line + "  (" + r.message() + ")");
                    default -> {
                        AppLogger.error(line + "  " + r.message());
                        for (int i = 0; i < Math.min(5, r.details().size()); i++) {
                            AppLogger.raw("                   " + r.details().get(i));
                        }
                    }
                }
            }
        });
        final String total = String.format(Locale.ROOT, "%d passed, %d failed, %d errors, %d skipped in %.2f s",
            s.passed(), s.failed(), s.errors(), s.skipped(), s.nanos() / 1e9);
        if (s.ok()) {
            AppLogger.success(total);
        } else {
            AppLogger.error(total);
        }
    }

    public static void openInLab(String input, CommandExecutionContext ctx) {
        final String spec = Handlers.args(input, ":rootlab open");
        if (spec.isEmpty()) {
            Handlers.usage(":rootlab open <file|folder|url>");
            return;
        }
        if (!com.sphere.components.rootlab.RootLabPanel.exists()) {
            AppLogger.error("The ROOT Lab tab is not part of this window.");
            return;
        }
        final Path p = local(spec);
        if (p != null) {
            com.sphere.components.rootlab.RootLabPanel.instance().open(p);
        } else {
            com.sphere.components.rootlab.RootLabPanel.instance().openLocation(spec);
        }
    }

    public static void fsck(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":rootlab fsck");
        final boolean deep = w.remove("--deep");
        if (w.isEmpty()) {
            Handlers.usage(":rootlab fsck <file|url> [--deep]");
            return;
        }
        try (RootIO io = open(w.get(0))) {
            final boolean full = deep || !io.file().isRemote();
            final RootFsck.Report r = RootFsck.check(io.file(), full ? RootFsck.Options.full() : RootFsck.Options.quick(),
                null, null);
            for (String line : r.text().split("\n")) {
                if (line.contains("ERROR")) {
                    AppLogger.error(line);
                } else if (line.contains("WARNING")) {
                    AppLogger.warn(line);
                } else {
                    AppLogger.raw(line);
                }
            }
        } catch (IOException | RuntimeException failed) {
            AppLogger.error("Could not check " + w.get(0) + ": " + failed.getMessage());
        }
    }

    public static void compare(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":rootlab compare");
        if (w.size() < 2) {
            Handlers.usage(":rootlab compare <a> <b>");
            return;
        }
        try (RootIO a = open(w.get(0)); RootIO b = open(w.get(1))) {
            final FileCompare.Result r = FileCompare.compare(a, b, null);
            for (String line : r.text(w.get(0), w.get(1)).split("\n")) {
                if (line.contains("<== differs")) {
                    AppLogger.warn(line);
                } else {
                    AppLogger.raw(line);
                }
            }
        } catch (IOException | RuntimeException failed) {
            AppLogger.error("Could not compare: " + failed.getMessage());
        }
    }

    public static void stats(String input, CommandExecutionContext ctx) {
        final List<String> w = words(input, ":rootlab stats");
        if (w.size() < 3) {
            Handlers.usage(":rootlab stats <file|url> <tree> <branch> [entries]");
            return;
        }
        long limit = Long.MAX_VALUE;
        if (w.size() > 3) {
            try {
                limit = Long.parseLong(w.get(3).replace("_", ""));
            } catch (NumberFormatException notANumber) {
                Handlers.usage(":rootlab stats <file|url> <tree> <branch> [entries]");
                return;
            }
        }
        try (RootIO io = open(w.get(0))) {
            final RTree t = io.tree(w.get(1));
            final RTree.RBranch b = t.branch(w.get(2));
            if (b == null) {
                AppLogger.error(w.get(1) + " has no branch " + w.get(2));
                return;
            }
            final ColumnStats s = ColumnStats.of(b, limit, null, null);
            AppLogger.result(s.summary());
            for (String r : s.remarks()) {
                AppLogger.raw("  - " + r);
            }
            AppLogger.raw(String.format(Locale.ROOT, "  read in %.2f s", s.nanos / 1e9));
            com.sphere.components.rootlab.RootLabPanel.plot(s.histogram(), b.name());
        } catch (IOException | RuntimeException failed) {
            AppLogger.error("Could not read the branch: " + failed.getMessage());
        }
    }

    public static void sniff(String input, CommandExecutionContext ctx) {
        final String spec = Handlers.args(input, ":rootlab sniff");
        final Path p = spec.isEmpty() ? null : local(spec);
        if (p == null) {
            Handlers.usage(":rootlab sniff <file>");
            return;
        }
        try {
            AppLogger.result(p.getFileName() + ": " + DataFormats.sniff(p).describe());
        } catch (IOException failed) {
            AppLogger.error("Could not read " + p + ": " + failed.getMessage());
        }
    }
}

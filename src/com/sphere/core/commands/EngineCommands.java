package com.sphere.core.commands;

import com.sphere.core.InProcessEngine;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fjcontrib.ContribCatalog;
import com.sphere.core.fjcontrib.validation.Example;
import com.sphere.core.fjcontrib.validation.Examples;
import com.sphere.core.fjcontrib.validation.Validator;
import com.sphere.utils.AppLogger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What every engine answers, for the three that run inside Sphere: ':fjet',
 * ':fjco' and ':hepmc' take ping, diag, mode and exit as ':root', ':py' and
 * ':julia' do.
 */
final class EngineCommands {

    /** The example program ':fjco diag' runs against the C++ output: one of the quickest. */
    private static final String DIAG_EXAMPLE = "RecursiveTools/example_softdrop";

    private EngineCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    static void register() {
        for (InProcessEngine e : InProcessEngine.values()) {
            final String p = ":" + e.key();
            CommandDefinitions.register(p + " ping", "Probe " + e.label()
                + ": real work checked against what the physics requires; PONG when right", (i, c) -> ping(e));
            CommandDefinitions.register(p + " diag", "What " + e.label()
                + " is, whether it computes correctly, and the C++ installation beside it", (i, c) -> diag(e));
            CommandDefinitions.register(p + " mode", "Every line is a " + p + " command written without '" + p
                + "', until 'exit'. Usage: " + p + " mode", (i, c) -> Handlers.switchMode(c, e.key(), "[" + e.key() + "]"));
            CommandDefinitions.register(p + " exit", "Leave " + p + " mode. Usage: " + p + " exit",
                (i, c) -> Handlers.switchMode(c, null, ""));
        }
    }

    static void ping(InProcessEngine e) {
        final InProcessEngine.Probe p = e.probe();
        if (p.ok()) AppLogger.result("PONG");
        else AppLogger.error(e.label() + " answered wrong: " + p.detail());
    }

    static void diag(InProcessEngine e) {
        final InProcessEngine.Probe p = e.probe();
        AppLogger.result(e.description());
        row("probe", (p.ok() ? "PONG" : "FAILED") + String.format(Locale.ROOT, " in %.1f ms: ", p.millis()) + p.detail());
        if (e == InProcessEngine.FASTJET) fastjet();
        else if (e == InProcessEngine.FJCONTRIB) fjcontrib();
        else if (e == InProcessEngine.HEPMC3) HepMCCommands.diagRows();
        else if (e == InProcessEngine.MINUIT2) Minuit2Commands.diagRows();
        final InProcessEngine.Native cpp = e.findNative();
        row("C++", cpp == null ? e.nativeHint()
            : cpp.prefix() + (cpp.version() == null ? "" : ", version " + cpp.version()) + "; " + cpp.detail());
        row("mode", ":" + e.key() + " mode, then " + e.modeExamples() + " ...; 'exit' leaves");
        if (p.ok()) AppLogger.success(e.label() + " is ready.");
        else AppLogger.error(e.label() + " gives wrong answers: " + p.detail());
    }

    private static void fastjet() {
        final List<?> events = FastJetCommands.loadedEvents();
        row("events", events.isEmpty() ? "none (':fjet read <file>' or ':fjet toy')"
            : events.size() + " from " + FastJetCommands.loadedSource());
        final JetDefinition def = FastJetCommands.activeDefinition();
        row("definition", FastJetCommands.activeName() + ": " + (def == null ? "none" : def.description()));
        row("precision", Precision.defaultPrecision().description());
    }

    private static void fjcontrib() {
        final List<String> byCategory = new ArrayList<>();
        for (ContribCatalog.Category cat : ContribCatalog.Category.values()) {
            byCategory.add(cat.label() + " " + ContribCatalog.of(cat).size());
        }
        row("contribs", ContribCatalog.size() + ": " + String.join(", ", byCategory));
        final List<String> missing = InProcessEngine.missingSpecs();
        row("algorithms", InProcessEngine.contribSpecs().size() + " in ':fjet def'"
            + (missing.isEmpty() ? "" : "; missing: " + String.join(", ", missing)));
        final List<Example> all = Examples.all();
        final Path release = InProcessEngine.fjcontribRelease();
        final List<Example> pick = Examples.matching(DIAG_EXAMPLE);
        final Example ex = pick.isEmpty() ? all.get(0) : pick.get(0);
        final boolean reference = Validator.haveReference(ex);
        row("references", all.size() + " example programs; C++ outputs "
            + (release != null ? "from " + release : reference ? "in the jar" : "absent (':fjco validate --dir <fjcontrib>')"));
        if (reference) {
            LimitedWarning.setSink(message -> { });
            try {
                final Validator.Outcome r = ClusterSequence.selfCheck(() -> Validator.run(ex));
                row("versus C++", ex.id() + (r.error() != null ? ": ERROR " + r.error()
                    : r.identical() ? " identical to the character" : " differs: " + r.firstDifference().replace("\n", " | "))
                    + String.format(Locale.ROOT, " (%.0f ms); all of them: ':fjco validate'", r.millis()));
            } catch (Exception failed) {
                row("versus C++", ex.id() + ": " + failed.getMessage());
            } finally {
                LimitedWarning.setSink(AppLogger::warn);
            }
        }
        row("citations", "console menu > Citations > Citation fjcontrib; ':fjco bib <file.bib>'");
    }

    static void row(String label, String text) {
        AppLogger.raw(String.format("  %-11s %s", label, text));
    }
}

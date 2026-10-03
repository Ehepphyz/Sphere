package com.sphere.core.fjcontrib.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every example program of fjcontrib that Sphere ports, the ones "make
 * check" runs, and a command-line entry to run them outside the console.
 */
public final class Examples {

    private Examples() {
    }

    public static List<Example> all() {
        final List<Example> l = new ArrayList<>();
        l.addAll(RecursiveToolsExamples.all());
        l.addAll(NsubjettinessExamples.all());
        l.addAll(EnergyCorrelatorExamples.all());
        l.addAll(LundPlaneExamples.all());
        l.addAll(SoftKillerExamples.all());
        l.addAll(ConstituentSubtractorExamples.all());
        l.addAll(GenericSubtractorExamples.all());
        l.addAll(JetCleanserExamples.all());
        l.addAll(JetFFMomentsExamples.all());
        l.addAll(JetsWithoutJetsExamples.all());
        l.addAll(SubjetCountingExamples.all());
        l.addAll(VariableRExamples.all());
        l.addAll(ValenciaExamples.all());
        l.addAll(CentauroExamples.all());
        l.addAll(DISGenktExamples.all());
        l.addAll(KTClusCXXExamples.all());
        l.addAll(FlavorConeExamples.all());
        l.addAll(ScJetExamples.all());
        l.addAll(FlavourExamples.all());
        l.addAll(ClusteringExamples.all());
        return l;
    }

    /** The examples whose contrib or name contains the text (all when empty). */
    public static List<Example> matching(String text) {
        final String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        final List<Example> out = new ArrayList<>();
        for (Example e : all()) {
            if (t.isEmpty() || e.id().toLowerCase(Locale.ROOT).contains(t)) out.add(e);
        }
        return out;
    }

    /** java ... Examples [filter] [--show]: runs and compares, printing a line per example. */
    public static void main(String[] args) throws Exception {
        String filter = "";
        boolean show = false;
        for (String a : args) {
            if (a.equals("--show")) show = true;
            else filter = a;
        }
        int ok = 0;
        int n = 0;
        for (Example e : matching(filter)) {
            n++;
            if (show) {
                System.out.print(Validator.output(e));
                continue;
            }
            final Validator.Outcome r = Validator.run(e);
            final String status = r.error() != null ? "ERROR    " : r.identical() ? "IDENTICAL" : r.agrees(1e-5) ? "ROUNDING " : "DIFFERS  ";
            if (r.error() == null && r.agrees(1e-5)) ok++;
            System.out.printf(Locale.ROOT, "%s %-55s %5d lines %6d numbers, %4d lines differ, max rel. dev %.2e%s  (%.0f ms)%n",
                status, e.id(), r.lines(), r.numbers(), r.differingLines(), r.maxRelativeDeviation(),
                r.textDiffers() ? ", text differs" : "", r.millis());
            if (r.error() != null) System.out.println("      " + r.error());
            else if (!r.identical()) System.out.println("      first: " + r.firstDifference());
        }
        if (!show) System.out.println(ok + " of " + n + " agree");
    }
}

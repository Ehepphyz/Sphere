package com.sphere.core.rootlab.bench;

import java.util.ArrayList;
import java.util.List;

/**
 * The ROOT Lab without the window:
 * <pre>
 *   java -cp Sphere.jar com.sphere.core.rootlab.bench.RootLabCli [--network] [suite ...]
 * </pre>
 * runs the suites (all of them by default), prints the report ROOT's stress
 * programs print, and exits with 0 only when nothing failed.
 */
public final class RootLabCli {

    private RootLabCli() {
    }

    public static void main(String[] args) {
        boolean network = false;
        boolean verbose = false;
        final List<LabSuite> chosen = new ArrayList<>();
        for (String a : args) {
            if (a.equals("--network") || a.equals("-n")) {
                network = true;
            } else if (a.equals("--verbose") || a.equals("-v")) {
                verbose = true;
            } else if (a.equals("--list")) {
                for (LabSuite s : LabCatalog.all()) {
                    System.out.printf("%-16s %s%s%n", s.id(), s.title(), s.network() ? "  [network]" : "");
                }
                return;
            } else {
                final LabSuite s = LabCatalog.find(a);
                if (s == null) {
                    System.err.println("no suite " + a + "; --list shows them");
                    System.exit(2);
                }
                chosen.add(s);
            }
        }
        final List<LabSuite> suites = chosen.isEmpty() ? LabCatalog.all() : chosen;
        final boolean v = verbose;
        final LabRunner.Summary summary = new LabRunner().run(suites, network, new LabRunner.Listener() {
            @Override
            public void testFinished(LabSuite suite, LabResult r) {
                System.out.printf("%-16s %s%n", suite.id(), LabRunner.line(0, r.title(), r.status()));
                if (v) {
                    for (String d : r.details()) {
                        System.out.println("                   " + d);
                    }
                }
            }
        });
        System.out.println();
        System.out.print(LabRunner.report(suites, summary));
        System.exit(summary.ok() ? 0 : 1);
    }
}

package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.internal.StdSort;
import com.sphere.core.fjcontrib.ktcluscxx.KTClusCXXPlugin;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/** The example programs of KTClusCXX 1.0.1. */
final class KTClusCXXExamples {

    private static final String C = "KTClusCXX";
    private static final String DATA = "single-epDIS-event.dat";
    private static final int[] MODES = {
        1111, 2111, 3111, 4111, 1211, 2211, 3211, 4211,
        1311, 2311, 3311, 4311, 1112, 2112, 3112, 4112,
        1212, 2212, 3212, 4212, 1312, 2312, 3312, 4312,
        1113, 2113, 3113, 4113, 1213, 2213, 3213, 4213,
        1313, 2313, 3313, 4313};

    private KTClusCXXExamples() {
    }

    static List<Example> all() {
        return List.of(
            Example.of(C, "example", DATA, KTClusCXXExamples::example),
            Example.of(C, "exampleall", DATA, KTClusCXXExamples::exampleAll));
    }

    /** while (cin >> px >> py >> pz >> E): whitespace-separated numbers up to the first that does not parse. */
    private static List<PseudoJet> read(BufferedReader in) throws Exception {
        final List<Double> numbers = new ArrayList<>();
        String line;
        outer:
        while ((line = in.readLine()) != null) {
            for (String t : line.trim().split("\\s+")) {
                if (t.isEmpty()) continue;
                try {
                    numbers.add(Double.parseDouble(t));
                } catch (NumberFormatException e) {
                    break outer;
                }
            }
        }
        final List<PseudoJet> particles = new ArrayList<>();
        for (int i = 0; i + 3 < numbers.size(); i += 4) {
            particles.add(Events.particle(numbers.get(i), numbers.get(i + 1), numbers.get(i + 2), numbers.get(i + 3)));
        }
        return particles;
    }

    private static void print(Cout o, PseudoJet j) {
        o.p(j.px()).p(" ").p(j.py()).p(" ").p(j.pz()).p(" ").p(j.e()).endl();
    }

    private static void cluster(Cout o, List<PseudoJet> input, int mode, boolean inclusive, String modeText,
                                double radius, double ycut, double ecut) {
        final ClusterSequence cs = new ClusterSequence(input, KTClusCXXPlugin.jetDefinition(mode, radius));
        final List<PseudoJet> jets = new ArrayList<>(inclusive ? cs.inclusiveJets() : cs.exclusiveJets(ecut * ecut * ycut));
        StdSort.sort(jets, (a, b) -> a.E() > b.E() ? -1 : 0);
        final double[] dmerge = new double[input.size()];
        for (int i = 0; i < dmerge.length; i++) dmerge[i] = cs.exclusiveDmergeMax(i);
        o.p("Parameters: ").p(modeText).p(" ").p(radius).p(" ").p(ycut).p(" ").p(ecut).endl();
        o.p("Pseudojets:").endl();
        for (PseudoJet j : jets) print(o, j);
        o.p("exclusive_dmerge:").endl();
        for (double d : dmerge) o.p(Math.min(1000000.0, d)).endl();
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        o.p("#Usage: example <input file=standard input> <mode=2111> <Radius=1.0> <YCUT=0.0001> <ECUT=1.0>").endl();
        final String smode = "2111";
        final boolean inclusive = smode.length() > 4 && smode.charAt(4) == 'i';
        final int mode = Integer.parseInt(smode.substring(0, 4));
        final List<PseudoJet> input = read(in);
        o.fixed().setw(11).setprecision(6);
        o.p("Input particles:").endl();
        for (PseudoJet p : input) print(o, p);
        cluster(o, input, mode, inclusive, smode, 1.0, 0.0001, 1.0);
    }

    static void exampleAll(BufferedReader in, Cout o, String[] args) throws Exception {
        o.p("#Usage: exampleall <input file=standard input>  <Radius=1.0> <YCUT=0.0001> <ECUT=1.0>").endl();
        final List<PseudoJet> input = read(in);
        o.fixed().setw(11).setprecision(6);
        o.p("Input particles:").endl();
        for (PseudoJet p : input) print(o, p);
        for (boolean inclusive : new boolean[]{true, false}) {
            for (int mode : MODES) cluster(o, input, mode, inclusive, mode + (inclusive ? "i" : ""), 1.0, 0.0001, 1.0);
        }
    }
}

package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RecombinationScheme;
import com.sphere.core.fastjet.Strategy;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelator;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelator.Measure;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelatorGeneralized;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorC1;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorC2;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorCseries;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorD2;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorDoubleRatio;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorGeneralizedD2;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorM2;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorMseries;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorN2;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorN3;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorNseries;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorRatio;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorU1;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorU2;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorU3;
import com.sphere.core.fjcontrib.energycorrelator.EnergyCorrelators.EnergyCorrelatorUseries;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/** The example programs of EnergyCorrelator 1.3.2. */
final class EnergyCorrelatorExamples {

    private static final String C = "EnergyCorrelator";
    private static final String RULE = "-------------------------------------------------------------------------------------";

    private EnergyCorrelatorExamples() {
    }

    static List<Example> all() {
        final List<Example> l = new ArrayList<>();
        l.add(Example.of(C, "example", "single-event.dat", EnergyCorrelatorExamples::example));
        l.add(Example.of(C, "example_basic_usage", "single-event.dat", EnergyCorrelatorExamples::basic));
        return l;
    }

    private static List<PseudoJet> antikt1(List<PseudoJet> event) {
        return PseudoJet.sortedByPt(new ClusterSequence(event,
            new JetDefinition(JetAlgorithm.ANTIKT, 1.0, RecombinationScheme.E_SCHEME, Strategy.BEST)).inclusiveJets());
    }

    static void basic(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final List<PseudoJet> jets = antikt1(event);
        if (!(jets.get(0).perp() > 200)) return;
        final PseudoJet j = jets.get(0);
        o.p(RULE).endl().p("EnergyCorrelator: C series ").endl().p(RULE).endl();
        o.printf("%7s %14s %14s %14s\n", "beta", "C1", "C2", "C3");
        for (double beta : new double[]{1.0, 2.0}) {
            o.printf("%7.3f %14.6f %14.6f %14.6f\n", beta, new EnergyCorrelatorC1(beta).result(j),
                new EnergyCorrelatorC2(beta).result(j), new EnergyCorrelatorCseries(3, beta).result(j));
        }
        o.p(RULE).endl().p("EnergyCorrelator: D2, orignal (alpha=beta) and generalized ").endl().p(RULE).endl();
        o.printf("%7s %14s %14s %14s\n", "beta", "D2", "D2(alpha=1)", "D2(alpha=2)");
        for (double beta : new double[]{1.0, 2.0}) {
            o.printf("%7.3f %14.6f %14.6f %14.6f\n", beta, new EnergyCorrelatorD2(beta).result(j),
                new EnergyCorrelatorGeneralizedD2(1.0, beta).result(j), new EnergyCorrelatorGeneralizedD2(2.0, beta).result(j));
        }
        o.p(RULE).endl().p("EnergyCorrelator: N series  ").endl().p(RULE).endl();
        o.printf("%7s %14s %14s\n", "beta", "N2", "N3");
        for (double beta : new double[]{1.0, 2.0}) {
            o.printf("%7.3f %14.6f %14.6f\n", beta, new EnergyCorrelatorN2(beta).result(j), new EnergyCorrelatorN3(beta).result(j));
        }
        o.p(RULE).endl().p("EnergyCorrelator: M series ").endl().p(RULE).endl();
        o.printf("%7s %14s %14s\n", "beta", "M2", "M3");
        for (double beta : new double[]{1.0, 2.0}) {
            o.printf("%7.3f %14.6f %14.6f\n", beta, new EnergyCorrelatorM2(beta).result(j), new EnergyCorrelatorMseries(3, beta).result(j));
        }
        o.p(RULE).endl().p("EnergyCorrelator: U series ").endl().p(RULE).endl();
        o.printf("%7s %14s %14s %14s\n", "beta", "U1", "U2", "U3");
        for (double beta : new double[]{0.5, 1.0, 2.0}) {
            o.printf("%7.3f %14.8f %14.8f %14.8f\n", beta, new EnergyCorrelatorU1(beta).result(j),
                new EnergyCorrelatorU2(beta).result(j), new EnergyCorrelatorU3(beta).result(j));
        }
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final List<PseudoJet> jets = antikt1(event);
        final double[] betalist = {0.1, 0.2, 0.5, 1.0, 1.5, 2.0};
        final double[] alphalist = {0.1, 0.2, 0.5, 1.0};
        final Measure[] measures = {Measure.pt_R, Measure.E_theta};
        final String[] modename = {"pt_R", "E_theta"};
        for (int jj = 0; jj < 2; jj++) {
            if (!(jets.get(jj).perp() > 200)) continue;
            final PseudoJet j = jets.get(jj);
            for (int mi = 0; mi < measures.length; mi++) {
                final Measure m = measures[mi];
                final String name = modename[mi];
                o.p(RULE).endl().p("EnergyCorrelator:  ECF(N,beta) with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s %14s %14s %14s %15s\n", "beta", "N=1 (GeV)", "N=2 (GeV^2)", "N=3 (GeV^3)", "N=4 (GeV^4)", "N=5 (GeV^5)");
                for (double beta : betalist) {
                    o.printf("%7.3f %14.2f %14.2f %14.6g %14.6g %15.6g \n", beta, new EnergyCorrelator(1, beta, m).result(j),
                        new EnergyCorrelator(2, beta, m).result(j), new EnergyCorrelator(3, beta, m).result(j),
                        new EnergyCorrelator(4, beta, m).result(j), new EnergyCorrelator(5, beta, m).result(j));
                }
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorRatio:  r_N^(beta) = ECF(N+1,beta)/ECF(N,beta) with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s %14s %14s %14s %15s \n", "beta", "N=0 (GeV)", "N=1 (GeV)", "N=2 (GeV)", "N=3 (GeV)", "N=4 (GeV)");
                for (double beta : betalist) {
                    o.printf("%7.3f %14.4f %14.4f %14.6g %14.6g %15.6g \n", beta, new EnergyCorrelatorRatio(0, beta, m).result(j),
                        new EnergyCorrelatorRatio(1, beta, m).result(j), new EnergyCorrelatorRatio(2, beta, m).result(j),
                        new EnergyCorrelatorRatio(3, beta, m).result(j), new EnergyCorrelatorRatio(4, beta, m).result(j));
                }
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorDoubleRatio:  C_N^(beta) = r_N^(beta)/r_{N-1}^(beta) with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s %14s %14s %14s \n", "beta", "N=1", "N=2", "N=3", "N=4");
                for (double beta : betalist) {
                    o.printf("%7.3f %14.6f %14.6f %14.6f %14.6f \n", beta, new EnergyCorrelatorDoubleRatio(1, beta, m).result(j),
                        new EnergyCorrelatorDoubleRatio(2, beta, m).result(j), new EnergyCorrelatorDoubleRatio(3, beta, m).result(j),
                        new EnergyCorrelatorDoubleRatio(4, beta, m).result(j));
                }
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorC1:  C_1^(beta) = ECF(2,beta)/ECF(1,beta)^2 with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s \n", "beta", "C1 obs");
                for (double beta : betalist) o.printf("%7.3f %14.6f \n", beta, new EnergyCorrelatorC1(beta, m).result(j));
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorC2:  C_2^(beta) = ECF(3,beta)*ECF(1,beta)/ECF(2,beta)^2 with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s \n", "beta", "C2 obs");
                for (double beta : betalist) o.printf("%7.3f %14.6f \n", beta, new EnergyCorrelatorC2(beta, m).result(j));
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorD2:  D_2^(beta) = ECF(3,beta)*ECF(1,beta)^3/ECF(2,beta)^3 with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s \n", "beta", "D2 obs");
                for (double beta : betalist) o.printf("%7.3f %14.6f \n", beta, new EnergyCorrelatorD2(beta, m).result(j));
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorGeneralizedD2:  D_2^(alpha, beta) = ECFN(3,alpha)/ECFN(2,beta)^(3*alpha/beta) with ")
                    .p(name).endl().p(RULE).endl();
                o.printf("%7s %18s %18s %18s %18s\n", "beta", "alpha = 0.100", "alpha = 0.200", "alpha = 0.500", "alpha = 1.000");
                for (int b = 1; b < betalist.length; b++) {
                    o.printf("%7.3f ", betalist[b]);
                    for (double alpha : alphalist) o.printf("%18.6g ", new EnergyCorrelatorGeneralizedD2(alpha, betalist[b], m).result(j));
                    o.printf("\n");
                }
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorGeneralized (angles = N Choose 2):  ECFN(N, beta) with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %7s %14s %14s %14s\n", "beta", "N=1", "N=2", "N=3", "N=4");
                for (double beta : betalist) {
                    o.printf("%7.3f %7.2f %14.10f %14.10f %14.10f \n", beta, new EnergyCorrelatorGeneralized(-1, 1, beta, m).result(j),
                        new EnergyCorrelatorGeneralized(-1, 2, beta, m).result(j), new EnergyCorrelatorGeneralized(-1, 3, beta, m).result(j),
                        new EnergyCorrelatorGeneralized(-1, 4, beta, m).result(j));
                }
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorGeneralized:  ECFG(angles, N, beta=1) with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %7s %14s %14s %14s\n", "angles", "N=1", "N=2", "N=3", "N=4");
                final double beta1 = 1.0;
                {
                    final int angle = 1;
                    o.printf("%7.0f %7.2f %14.10f %14.10f %14.10f \n", (double) angle,
                        new EnergyCorrelatorGeneralized(angle, 1, beta1, m).result(j),
                        new EnergyCorrelatorGeneralized(angle, 2, beta1, m).result(j),
                        new EnergyCorrelatorGeneralized(angle, 3, beta1, m).result(j),
                        new EnergyCorrelatorGeneralized(angle, 4, beta1, m, EnergyCorrelator.Strategy.slow).result(j));
                }
                for (int angle = 2; angle < 4; angle++) {
                    o.printf("%7.0f %7s %14s %14.10f %14.10f \n", (double) angle, " ", " ",
                        new EnergyCorrelatorGeneralized(angle, 3, beta1, m).result(j),
                        new EnergyCorrelatorGeneralized(angle, 4, beta1, m).result(j));
                }
                for (int angle = 4; angle < 7; angle++) {
                    o.printf("%7.0f %7s %14s %14s %14.10f \n", (double) angle, " ", " ", " ",
                        new EnergyCorrelatorGeneralized(angle, 4, beta1, m).result(j));
                }
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorNseries:  N_i(beta) = ECFG(i+1, 2, beta)/ECFG(i, 1, beta)^2 with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s %14s %14s \n", "beta", "N=1", "N=2", "N=3");
                for (double beta : betalist) {
                    o.printf("%7.3f %14.6f %14.6f %14.6f \n", beta, new EnergyCorrelatorNseries(1, beta, m).result(j),
                        new EnergyCorrelatorNseries(2, beta, m).result(j), new EnergyCorrelatorNseries(3, beta, m).result(j));
                }
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorN2:  N2(beta) = ECFG(3, 2, beta)/ECFG(2, 1, beta)^2 with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s \n", "beta", "N2 obs");
                for (double beta : betalist) o.printf("%7.3f %14.6f \n", beta, new EnergyCorrelatorN2(beta, m).result(j));
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorN3:  N3(beta) = ECFG(4, 2, beta)/ECFG(3, 1, beta)^2 with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s \n", "beta", "N3 obs");
                for (double beta : betalist) o.printf("%7.3f %14.6f \n", beta, new EnergyCorrelatorN3(beta, m).result(j));
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorMseries:  M_i(beta) = ECFG(i+1, 1, beta)/ECFN(i, 1, beta) with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s %14s %14s \n", "beta", "N=1", "N=2", "N=3");
                for (double beta : betalist) {
                    o.printf("%7.3f %14.6f %14.6f %14.6f \n", beta, new EnergyCorrelatorMseries(1, beta, m).result(j),
                        new EnergyCorrelatorMseries(2, beta, m).result(j), new EnergyCorrelatorMseries(3, beta, m).result(j));
                }
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorM2:  M2(beta) = ECFG(3, 1, beta)/ECFG(3, 1, beta) with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s \n", "beta", "M2 obs");
                for (double beta : betalist) o.printf("%7.3f %14.6f \n", beta, new EnergyCorrelatorM2(beta, m).result(j));
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorCseries:  C_i(beta) = ECFN(i-1, beta)*ECFN(i+1, beta)/ECFN(i, beta)^2 with ")
                    .p(name).endl().p(RULE).endl();
                o.printf("%7s %20s %20s %20s \n", "beta", "N=1", "N=2", "N=3");
                for (double beta : betalist) {
                    o.printf("%7.3f %20.10f %20.10f %20.10f \n", beta, new EnergyCorrelatorCseries(1, beta, m).result(j),
                        new EnergyCorrelatorCseries(2, beta, m).result(j), new EnergyCorrelatorCseries(3, beta, m).result(j));
                }
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorUseries:  U_i(beta) = ECFG(i+1, 1, beta) with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %20s %20s %20s \n", "beta", "N=1", "N=2", "N=3");
                for (double beta : betalist) {
                    o.printf("%7.3f %20.10f %20.10f %20.10f \n", beta, new EnergyCorrelatorUseries(1, beta, m).result(j),
                        new EnergyCorrelatorUseries(2, beta, m).result(j), new EnergyCorrelatorUseries(3, beta, m).result(j));
                }
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorU1:  U1(beta) = ECFG(2, 1, beta) with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s \n", "beta", "U1 obs");
                for (double beta : betalist) o.printf("%7.3f %14.10f \n", beta, new EnergyCorrelatorU1(beta, m).result(j));
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorU2:  U2(beta) = ECFG(3, 1, beta) with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s \n", "beta", "U2 obs");
                for (double beta : betalist) o.printf("%7.3f %14.10f \n", beta, new EnergyCorrelatorU2(beta, m).result(j));
                end(o);
                o.p(RULE).endl().p("EnergyCorrelatorU3:  U3(beta) = ECFG(4, 1, beta) with ").p(name).endl().p(RULE).endl();
                o.printf("%7s %14s \n", "beta", "U3 obs");
                for (double beta : betalist) o.printf("%7.3f %14.10f \n", beta, new EnergyCorrelatorU3(beta, m).result(j));
                end(o);
            }
        }
    }

    private static void end(Cout o) {
        o.p(RULE).endl().endl();
    }
}

package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.jetcleanser.JetCleanser;
import com.sphere.core.fjcontrib.jetcleanser.JetCleanser.CleansingMode;
import com.sphere.core.fjcontrib.jetcleanser.JetCleanser.InputMode;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/** The example program of JetCleanser 1.0.1. */
final class JetCleanserExamples {

    private JetCleanserExamples() {
    }

    static List<Example> all() {
        return List.of(Example.of("JetCleanser", "example", "Pythia-Zp2jets-lhc-pileup-1ev.dat", JetCleanserExamples::example));
    }

    private static void line(Cout o, String label, PseudoJet j) {
        o.p(label).p(j.pt()).p(" eta = ").p(j.eta()).p(" phi = ").p(j.phi()).p("   m = ").p(j.m()).endl();
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> hardCharged = new ArrayList<>();
        final List<PseudoJet> hardNeutral = new ArrayList<>();
        final List<PseudoJet> puCharged = new ArrayList<>();
        final List<PseudoJet> puNeutral = new ArrayList<>();
        int nsub = 0;
        String s;
        while ((s = in.readLine()) != null) {
            if (s.startsWith("#END")) break;
            if (s.startsWith("#SUBSTART")) nsub += 1;
            if (s.startsWith("#")) continue;
            final double[] v = Events.numbers(s, 6);
            final PseudoJet p = Events.particle(v[0], v[1], v[2], v[3]);
            final int charge = (int) v[5];
            if (nsub <= 1) {
                if (charge != 0) hardCharged.add(p); else hardNeutral.add(p);
            } else {
                if (charge != 0) puCharged.add(p); else puNeutral.add(p);
            }
        }
        o.p("# ").p(nsub - 1).p(" pileup events on top of the hard event").endl();
        final List<PseudoJet> hardEvent = new ArrayList<>(hardCharged);
        final List<PseudoJet> fullEvent = new ArrayList<>(hardCharged);
        final List<PseudoJet> fullNeutral = new ArrayList<>();
        hardEvent.addAll(hardNeutral);
        fullEvent.addAll(hardNeutral);
        fullNeutral.addAll(hardNeutral);
        fullEvent.addAll(puCharged);
        fullEvent.addAll(puNeutral);
        fullNeutral.addAll(puNeutral);
        final JetDefinition jetDef = new JetDefinition(JetAlgorithm.ANTIKT, 1.0);
        final List<List<PseudoJet>> sets = List.of(fullEvent, hardCharged, puCharged, fullNeutral, hardEvent);
        final List<List<PseudoJet>> jetSets = JetCleanser.clusterSets(jetDef, fullEvent, sets, 25.0);
        final List<PseudoJet> plain = jetSets.get(0);
        final List<PseudoJet> lv = jetSets.get(1);
        final List<PseudoJet> pu = jetSets.get(2);
        final List<PseudoJet> neutrals = jetSets.get(3);
        final List<PseudoJet> truth = jetSets.get(4);
        for (int mode = 0; mode < 2; mode++) {
            final boolean together = mode == 0;
            final InputMode im = together ? InputMode.input_nc_together : InputMode.input_nc_separate;
            o.p(together ? "ATLAS-like cleansing:" : "CMS-like cleansing:").endl().endl();
            final JetCleanser jvf = new JetCleanser(new JetDefinition(JetAlgorithm.KT, 0.3), CleansingMode.jvf_cleansing, im);
            jvf.setTrimming(0.01);
            final JetCleanser lin = new JetCleanser(0.25, CleansingMode.linear_cleansing, im);
            lin.setLinearParameters(0.65);
            final JetCleanser gau = new JetCleanser(0.3, CleansingMode.gaussian_cleansing, im);
            gau.setGaussianParameters(0.67, 0.62, 0.20, 0.25);
            o.p(jvf.description()).endl();
            o.p(lin.description()).endl();
            o.p(gau.description()).endl();
            final int nJets = Math.min(plain.size(), 3);
            PseudoJet plainDijet = new PseudoJet(), truthDijet = new PseudoJet(), jvfDijet = new PseudoJet(),
                linDijet = new PseudoJet(), gauDijet = new PseudoJet();
            for (int i = 0; i < nJets; i++) {
                final PseudoJet plainJet = plain.get(i);
                final PseudoJet truthJet = truth.get(i);
                final PseudoJet j1, j2, j3;
                if (together) {
                    j1 = jvf.result(plainJet, lv.get(i).constituents(), pu.get(i).constituents());
                    j2 = lin.result(plainJet, lv.get(i).constituents(), pu.get(i).constituents());
                    j3 = gau.result(plainJet, lv.get(i).constituents(), pu.get(i).constituents());
                } else {
                    j1 = jvf.resultSeparate(neutrals.get(i).constituents(), lv.get(i).constituents(), pu.get(i).constituents());
                    j2 = lin.resultSeparate(neutrals.get(i).constituents(), lv.get(i).constituents(), pu.get(i).constituents());
                    j3 = gau.resultSeparate(neutrals.get(i).constituents(), lv.get(i).constituents(), pu.get(i).constituents());
                }
                line(o, "                  no pileup: pt = ", truthJet);
                line(o, "                with pileup: pt = ", plainJet);
                line(o, " with pileup + jvf cleansed: pt = ", j1);
                line(o, " with pileup + lin cleansed: pt = ", j2);
                line(o, " with pileup + gau cleansed: pt = ", j3);
                o.endl();
                if (i < 2) {
                    plainDijet = plainDijet.plus(plainJet);
                    truthDijet = truthDijet.plus(truthJet);
                    jvfDijet = jvfDijet.plus(j1);
                    linDijet = linDijet.plus(j2);
                    gauDijet = gauDijet.plus(j3);
                }
            }
            o.p("Dijet Masses: ").endl().p(" plain = ").p(plainDijet.m()).endl().p(" truth = ").p(truthDijet.m()).endl()
                .p(" jvf   = ").p(jvfDijet.m()).endl().p(" lin   = ").p(linDijet.m()).endl().p(" gau   = ").p(gauDijet.m())
                .endl().endl();
        }
    }
}

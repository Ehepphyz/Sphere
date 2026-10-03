package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.plugins.EECambridgePlugin;
import com.sphere.core.fjcontrib.lundplane.LundDeclustering;
import com.sphere.core.fjcontrib.lundplane.LundEEDeclustering;
import com.sphere.core.fjcontrib.lundplane.LundEEHelpers;
import com.sphere.core.fjcontrib.lundplane.LundEEHelpers.Matrix3;
import com.sphere.core.fjcontrib.lundplane.LundGenerator;
import com.sphere.core.fjcontrib.lundplane.LundJSON;
import com.sphere.core.fjcontrib.lundplane.LundWithSecondary;
import com.sphere.core.fjcontrib.lundplane.RecursiveLundEEGenerator;
import com.sphere.core.fjcontrib.lundplane.SecondaryLund;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;

/** The example programs of LundPlane 2.1.2. */
final class LundPlaneExamples {

    private static final String C = "LundPlane";

    private LundPlaneExamples() {
    }

    static List<Example> all() {
        final List<Example> l = new ArrayList<>();
        l.add(Example.of(C, "example", "single-event.dat", LundPlaneExamples::example));
        l.add(Example.of(C, "example_secondary", "single-event.dat", LundPlaneExamples::secondary));
        l.add(Example.of(C, "example_dpsi_collinear", "single-ee-event.dat", LundPlaneExamples::dpsiCollinear));
        l.add(Example.of(C, "example_dpsi_slice", "single-ee-event.dat", LundPlaneExamples::dpsiSlice));
        return l;
    }

    private static List<PseudoJet> jets(List<PseudoJet> event) {
        return PseudoJet.sortedByPt(new ClusterSequence(event, new JetDefinition(JetAlgorithm.ANTIKT, 1.0)).inclusiveJets(100.0));
    }

    static void example(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        final String filename = "jets.json";
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        o.p("# writing declusterings of primary and secondary plane to file ").p(filename).endl();
        final StringBuilder json = new StringBuilder();
        final List<PseudoJet> jets = jets(event);
        final LundGenerator lund = new LundGenerator();
        o.p(lund.description()).endl();
        for (int ijet = 0; ijet < jets.size(); ijet++) {
            o.endl().p("Lund coordinates ( ln 1/Delta, ln kt ) of declusterings of jet ").p(ijet).p(" are:").endl();
            final List<LundDeclustering> declusts = lund.result(jets.get(ijet));
            for (int i = 0; i < declusts.size(); ++i) {
                final double[] c = declusts.get(i).lundCoordinates();
                o.p("(").p(c[0]).p(", ").p(c[1]).p(")");
                if (i < declusts.size() - 1) o.p("; ");
            }
            o.endl();
            json.append(LundJSON.toJson(declusts)).append('\n');
        }
        o.endl().p("File ").p(filename).p(" written.").endl();
    }

    static void secondary(BufferedReader in, Cout o, String[] args) throws Exception {
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final List<PseudoJet> jets = jets(event);
        final LundWithSecondary lund = new LundWithSecondary(new SecondaryLund.SecondaryLund_mMDT());
        o.p(lund.description()).endl();
        for (int ijet = 0; ijet < jets.size(); ijet++) {
            o.endl().p("Lund coordinates ( ln 1/Delta, ln kt ) of declusterings of jet ").p(ijet).p(" are:").endl();
            final List<LundDeclustering> declusts = lund.primary(jets.get(ijet));
            for (int i = 0; i < declusts.size(); ++i) {
                final double[] c = declusts.get(i).lundCoordinates();
                o.p("[").p(i).p("](").p(c[0]).p(", ").p(c[1]).p(")");
                if (i < declusts.size() - 1) o.p("; ");
            }
            o.endl();
            final List<LundDeclustering> sec = lund.secondary(declusts);
            o.endl().p("with Lund coordinates for the secondary plane (from primary declustering [")
                .p(lund.secondaryIndex(declusts)).p("]):").endl();
            for (int i = 0; i < sec.size(); ++i) {
                final double[] c = sec.get(i).lundCoordinates();
                o.p("[").p(i).p("](").p(c[0]).p(", ").p(c[1]).p(")");
                if (i < sec.size() - 1) o.p("; ");
            }
            o.endl();
        }
    }

    static void dpsiCollinear(BufferedReader in, Cout o, String[] args) throws Exception {
        final double z1cut = 0.1, z2cut = 0.1;
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final RecursiveLundEEGenerator lund = new RecursiveLundEEGenerator(-1);
        final ClusterSequence cs = new ClusterSequence(event, new JetDefinition(new EECambridgePlugin(1.0)));
        final List<LundEEDeclustering> declusts = lund.result(cs);
        int iPrimary1 = -1, iPrimary2 = -1;
        double psiPrimary1 = 0;
        double dpsi11 = Double.MAX_VALUE;
        for (int i = 0; i < declusts.size(); ++i) {
            if (declusts.get(i).depth() == 0 && declusts.get(i).z() > z1cut) {
                if (iPrimary1 < 0) {
                    iPrimary1 = i;
                    psiPrimary1 = declusts.get(i).psibar();
                } else {
                    iPrimary2 = i;
                    dpsi11 = LundEEHelpers.mapToPi(declusts.get(i).psibar() - psiPrimary1);
                    break;
                }
            }
        }
        if (iPrimary1 < 0) return;
        final int follow = declusts.get(iPrimary1).leafIplane();
        final List<LundEEDeclustering> secondaries = new ArrayList<>();
        for (LundEEDeclustering d : declusts) if (d.iplane() == follow) secondaries.add(d);
        int iSecondary = -1;
        double dpsi12 = Double.MAX_VALUE;
        for (int i = 0; i < secondaries.size(); ++i) {
            if (secondaries.get(i).z() > z2cut) {
                iSecondary = i;
                dpsi12 = LundEEHelpers.mapToPi(secondaries.get(i).psibar() - psiPrimary1);
                break;
            }
        }
        o.p("Highest-kt primary that passes the zcut of ").p(z1cut).p(", with Lund coordinates  ( ln 1/Delta, ln kt, psibar ):").endl();
        double[] c = declusts.get(iPrimary1).lundCoordinates();
        o.p("index [").p(iPrimary1).p("](").p(c[0]).p(", ").p(c[1]).p(", ").p(declusts.get(iPrimary1).psibar()).p(")").endl();
        if (iSecondary >= 0) {
            o.endl().p("with Lund coordinates for the secondary plane that passes the zcut of ").p(z2cut).endl();
            c = secondaries.get(iSecondary).lundCoordinates();
            o.p("index [").p(iSecondary).p("](").p(c[0]).p(", ").p(c[1]).p(", ").p(secondaries.get(iSecondary).psibar()).p(")");
            o.p(" --> delta_psi(primary,secondary) = ").p(dpsi12).endl();
        }
        if (iPrimary2 >= 0) {
            o.endl().p("with Lund coordinates for the second primary plane that passes the zcut of ").p(z1cut).endl();
            c = declusts.get(iPrimary2).lundCoordinates();
            o.p("index [").p(iPrimary2).p("](").p(c[0]).p(", ").p(c[1]).p(", ").p(declusts.get(iPrimary2).psibar()).p(")");
            o.p(" --> delta_psi(primary_1, primary_2) = ").p(dpsi11).endl();
        }
    }

    private static boolean inSlice(PseudoJet p, PseudoJet pref) {
        final Matrix3 rotmat = Matrix3.fromDirection(pref).transpose();
        return Math.abs(rotmat.times(p).rap()) < 1.0;
    }

    static void dpsiSlice(BufferedReader in, Cout o, String[] args) throws Exception {
        final double z2cut = 0.1;
        final List<PseudoJet> event = Events.readEvent(in);
        o.p("# read an event with ").p(event.size()).p(" particles").endl();
        final RecursiveLundEEGenerator lund = new RecursiveLundEEGenerator(-1, true);
        final ClusterSequence cs = new ClusterSequence(event, new JetDefinition(new EECambridgePlugin(1.0)));
        final List<PseudoJet> excl = new ArrayList<>(cs.exclusiveJets(2));
        if (excl.get(0).pz() < excl.get(1).pz()) {
            final PseudoJet t = excl.get(0);
            excl.set(0, excl.get(1));
            excl.set(1, t);
        }
        final PseudoJet evAxis = excl.get(0).minus(excl.get(1));
        final List<LundEEDeclustering> declusts = lund.result(cs);
        int indexOfMaxPt = -1;
        double maxPt = 0.0;
        double psi1 = 0.0;
        for (int i = 0; i < declusts.size(); i++) {
            final LundEEDeclustering d = declusts.get(i);
            if (!inSlice(d.harder(), evAxis) && inSlice(d.softer(), evAxis)) {
                if (d.softer().pt() > maxPt) {
                    indexOfMaxPt = i;
                    maxPt = d.softer().pt();
                    psi1 = d.psibar();
                }
            }
        }
        if (indexOfMaxPt < 0) return;
        final int follow = declusts.get(indexOfMaxPt).leafIplane();
        final List<LundEEDeclustering> secondaries = new ArrayList<>();
        for (LundEEDeclustering d : declusts) if (d.iplane() == follow) secondaries.add(d);
        int indexOfMaxKt = -1;
        double dpsi = 0;
        for (int i = 0; i < secondaries.size(); i++) {
            if (secondaries.get(i).z() > z2cut) {
                indexOfMaxKt = i;
                dpsi = LundEEHelpers.mapToPi(secondaries.get(i).psibar() - psi1);
                break;
            }
        }
        if (indexOfMaxKt < 0) return;
        o.p("Primary in the central slice, with Lund coordinates ( ln 1/Delta, ln kt, psibar ):").endl();
        double[] c = declusts.get(indexOfMaxPt).lundCoordinates();
        o.p("index [").p(indexOfMaxPt).p("](").p(c[0]).p(", ").p(c[1]).p(", ").p(declusts.get(indexOfMaxPt).psibar()).p(")").endl();
        o.endl().p("with Lund coordinates for the (highest-kT) secondary plane that passes the zcut of ").p(z2cut).endl();
        c = secondaries.get(indexOfMaxKt).lundCoordinates();
        o.p("index [").p(indexOfMaxKt).p("](").p(c[0]).p(", ").p(c[1]).p(", ").p(secondaries.get(indexOfMaxKt).psibar()).p(")");
        o.p(" --> delta_psi,slice = ").p(dpsi).endl();
    }
}

package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.Citations;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * fastjet::PxConePlugin, the PXCONE cone of L. Del Pozo (OPAL), made
 * (eta, phi) by M. Seymour, with the fixes of M. Wobisch and G. Salam (M.H.
 * Seymour, C. Tevlin, JHEP 0611 (2006) 052): stable cones from every
 * particle and from the midpoints of pairs of stable cones, removal of
 * cones sharing more than overlap_threshold of their energy, each shared
 * particle then going to the nearest cone, and a minimum jet energy.
 *
 * This is a line-by-line translation of FastJet's pxcone.f, Fortran
 * semantics kept: loop bounds fixed on entry, the single-precision literals
 * of the original (pi = 3.141592654 as a REAL, 4.25E-18...), its tree sort.
 * The fixed array sizes of the Fortran (5000 particles and proto-jets) are
 * gone. Under {@link Precision#DD} only the four-momenta summed in the
 * ClusterSequence carry 106 bits.
 */
public final class PxConePlugin implements JetDefinition.Plugin {

    private final double coneRadius;
    private final double minJetEnergy;
    private final double overlapThreshold;
    private final boolean eSchemeJets;
    private final int mode;
    private boolean robust;

    public PxConePlugin(double coneRadius) {
        this(coneRadius, 5.0, 0.5, false, 2);
    }

    public PxConePlugin(double coneRadius, double minJetEnergy, double overlapThreshold) {
        this(coneRadius, minJetEnergy, overlapThreshold, false, 2);
    }

    /**
     * @param eSchemeJets whether the jets are the E-scheme sums of their
     *                    particles, rather than PXCONE's own momenta
     * @param mode        1 for e+e- (angles between momenta), 2 for hadron
     *                    colliders (eta, phi)
     */
    public PxConePlugin(double coneRadius, double minJetEnergy, double overlapThreshold, boolean eSchemeJets,
                        int mode) {
        this.coneRadius = coneRadius;
        this.minJetEnergy = minJetEnergy;
        this.overlapThreshold = overlapThreshold;
        this.eSchemeJets = eSchemeJets;
        this.mode = mode;
    }

    public double coneRadius() { return coneRadius; }
    public double minJetEnergy() { return minJetEnergy; }
    public double overlapThreshold() { return overlapThreshold; }
    public boolean eSchemeJets() { return eSchemeJets; }
    public int mode() { return mode; }

    /**
     * The robust (experimental) mode: the proto-jets the overlap treatment
     * leaves without any particle are dropped, whatever the energy cut, and
     * the Fortran bound of as many jets as particles no longer applies.
     *
     * With min_jet_energy = 0, which an analysis scanning the threshold down
     * meets, PXCONE keeps those empty proto-jets (an energy of 0 is not below
     * 0) and may then find more jets than particles and stop. They carry no
     * particle, so dropping them changes no physical jet; the default keeps
     * FastJet's behaviour.
     */
    public PxConePlugin setRobust(boolean robust) {
        this.robust = robust;
        return this;
    }

    public boolean robust() {
        return robust;
    }

    @Override
    public double R() {
        return coneRadius;
    }

    @Override
    public boolean isSpherical() {
        return mode != 2;
    }

    @Override
    public String description() {
        return "PxCone jet algorithm with cone_radius = " + Fmt.g(coneRadius) + ", min_jet_energy = "
            + Fmt.g(minJetEnergy) + ", overlap_threshold  = " + Fmt.g(overlapThreshold) + ", E_scheme_jets  = "
            + (eSchemeJets ? 1 : 0) + ", mode (1=e+e-, 2=hh) = " + mode
            + " (NB: non-standard version of PxCone, containing small bug fixes by Gavin Salam)"
            + (robust ? " [robust mode: empty proto-jets dropped]" : "");
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        final boolean dd = cs.precision() == Precision.DD;
        final int ntrak = cs.nJets();
        final double[][] ptrak = new double[ntrak][];
        for (int i = 0; i < ntrak; i++) {
            final PseudoJet p = cs.jet(i);
            ptrak[i] = new double[]{p.px(), p.py(), p.pz(), p.E()};
        }
        printBanner();
        final Pxcone px = new Pxcone(mode, ptrak, coneRadius, minJetEnergy, overlapThreshold, ntrak, robust);
        if (px.ierr != 0) {
            String why = px.message;
            if (px.message.startsWith("Found more than MXJET") && minJetEnergy <= 0) {
                why += " (with min_jet_energy = " + Fmt.g(minJetEnergy) + " the proto-jets emptied by the overlap"
                    + " treatment are kept, having an energy not below the cut; use min_jet_energy > 0, or"
                    + " setRobust(true) which drops them)";
            }
            throw new FastJetException("An error occurred while running PXCONE: " + why);
        }

        final int njet = px.njet;
        final List<List<Integer>> content = new ArrayList<>(njet);
        for (int j = 0; j < njet; j++) content.add(new ArrayList<>());
        for (int itrak = 0; itrak < ntrak; itrak++) {
            final int jetI = px.ipass[itrak] - 1;
            if (jetI >= 0) content.get(jetI).add(itrak);
        }
        for (int ipxjet = njet - 1; ipxjet >= 0; ipxjet--) {
            final List<Integer> list = content.get(ipxjet);
            if (list.isEmpty()) continue; // an emptied cone kept by a zero energy cut
            int jetK = list.get(0);
            for (int il = 1; il < list.size(); il++) {
                final int jetJ = list.get(il);
                if (il != list.size() - 1 || eSchemeJets) {
                    jetK = cs.pluginRecordIJRecombination(jetK, jetJ, 0.0);
                } else {
                    final double[] pj = px.pjet[ipxjet];
                    jetK = cs.pluginRecordIJRecombination(jetK, jetJ, 0.0, new PseudoJet(pj[0], pj[1], pj[2], pj[3]));
                }
            }
            final PseudoJet j = cs.jet(jetK);
            if (dd) {
                cs.pluginRecordIBRecombination(jetK, j.kt2DD());
            } else {
                cs.pluginRecordIBRecombination(jetK, j.perp2());
            }
        }
    }

    /** PXCONE's credit, kept for the Citations menu rather than printed. */
    private void printBanner() {
        Citations.plugin("PxCone", String.join(System.lineSeparator(),
            "PXCONE: Cone Jet-finder",
            "Written by Luis Del Pozo of OPAL",
            "Modified for eta-phi by Mike Seymour",
            "Includes bug fixes by Wobisch, Salam",
            "PXCONE is not a supported product and is",
            "provided for comparative purposes only"));
    }

    /* ------------------------------------------------------------------ */
    /* pxcone.f                                                            */
    /* ------------------------------------------------------------------ */

    /** PXMDPI's constants: REAL literals given to DOUBLE PRECISION parameters. */
    static final double PX_PI = (double) 3.141592654f;
    static final double PX_TWOPI = (double) 6.283185307f;
    static final double PX_THRPI = (double) 9.424777961f;
    static final double PX_EPS = (double) 1E-15f;
    static final double TINY_PT = (double) 4.25E-18f;

    /** PXMDPI: phi folded into (-pi, pi], with PXCONE's pi. */
    static double pxmdpi(double phi) {
        double r = phi;
        if (r <= PX_PI) {
            if (r > -PX_PI) {
                // nothing to do
            } else if (r > -PX_THRPI) {
                r = r + PX_TWOPI;
            } else {
                r = -((PX_PI - r) % PX_TWOPI) + PX_PI;
            }
        } else if (r <= PX_THRPI) {
            r = r - PX_TWOPI;
        } else {
            r = ((PX_PI + r) % PX_TWOPI) - PX_PI;
        }
        if (Math.abs(r) < PX_EPS) r = 0;
        return r;
    }

    /** One call of SUBROUTINE PXCONE; arrays 0-based, jet numbers (ipass) 1-based. */
    static final class Pxcone {
        final int mode;
        final int ntrak;
        final double[][] pp;
        final double[][] pu;
        final List<boolean[]> jetlis = new ArrayList<>();
        final List<double[]> pj = new ArrayList<>();
        int njet;
        boolean unstbl;
        int ierr;
        String message = "";
        double[][] pjet;
        int[] ipass;
        int[] ijmul;

        Pxcone(int mode, double[][] ptrak, double coner, double epslon, double ovlim, int mxjet, boolean robust) {
            this.mode = mode;
            this.ntrak = ptrak.length;
            pp = new double[ntrak][4];
            pu = new double[ntrak][3];
            final double rsep = 2.0; // RSEP = 2D0
            if (mode != 2) {
                for (int i = 0; i < ntrak; i++) System.arraycopy(ptrak[i], 0, pp[i], 0, 4);
            } else {
                for (int i = 0; i < ntrak; i++) {
                    final double[] t = ptrak[i];
                    final double ptsq = t[0] * t[0] + t[1] * t[1];
                    final double s = Math.sqrt(ptsq + t[2] * t[2]) + Math.abs(t[2]);
                    final double ppsq = s * s;
                    if (ptsq <= TINY_PT * ppsq) {
                        pp[i][0] = 20;
                    } else {
                        pp[i][0] = 0.5 * CRMath.log(ppsq / ptsq);
                    }
                    pp[i][0] = Math.copySign(Math.abs(pp[i][0]), t[2]);
                    pp[i][1] = (ptsq == 0) ? 0 : CRMath.atan2(t[1], t[0]);
                    pp[i][2] = 0;
                    pp[i][3] = Math.sqrt(ptsq);
                    pu[i][0] = pp[i][0];
                    pu[i][1] = pp[i][1];
                    pu[i][2] = pp[i][2];
                }
            }
            njet = 0;
            final double cosr;
            final double cos2r;
            if (mode != 2) {
                cosr = CRMath.cos(coner);
                cos2r = CRMath.cos(coner);
            } else {
                cosr = 1 - coner * coner;
                cos2r = 1 - (rsep * coner) * (rsep * coner);
            }
            unstbl = false;
            if (mode != 2) {
                pxuvec();
                if (ierr != 0) return;
            }
            for (int n = 0; n < ntrak; n++) {
                pxsear(cosr, pu[n].clone());
                if (ierr != 0) return;
            }
            final double[] vec1 = new double[3];
            final double[] vec2 = new double[3];
            final double[] vseed = new double[3];
            final int n1Last = njet - 1; // DO 140 N1 = 1,NJET-1: bounds fixed on entry
            for (int n1 = 0; n1 < n1Last; n1++) {
                vec1[0] = pj.get(n1)[0];
                vec1[1] = pj.get(n1)[1];
                vec1[2] = pj.get(n1)[2];
                if (mode != 2) pxnorv(vec1);
                final int n2Last = njet; // DO 150 N2 = N1+1,NJET
                for (int n2 = n1 + 1; n2 < n2Last; n2++) {
                    vec2[0] = pj.get(n2)[0];
                    vec2[1] = pj.get(n2)[1];
                    vec2[2] = pj.get(n2)[2];
                    if (mode != 2) pxnorv(vec2);
                    for (int i = 0; i < 3; i++) vseed[i] = vec1[i] + vec2[i];
                    if (mode != 2) {
                        pxnorv(vseed);
                    } else {
                        vseed[0] = vseed[0] / 2;
                        vseed[1] = pxmdpi(vec1[1] + 0.5d * pxmdpi(vec2[1] - vec1[1]));
                    }
                    final double cosval;
                    if (mode != 2) {
                        cosval = vec1[0] * vec2[0] + vec1[1] * vec2[1] + vec1[2] * vec2[2];
                    } else if (Math.abs(vec1[0]) >= 20 || Math.abs(vec2[0]) >= 20) {
                        cosval = -1000;
                    } else {
                        final double d0 = vec1[0] - vec2[0];
                        final double d1 = pxmdpi(vec1[1] - vec2[1]);
                        cosval = 1 - (d0 * d0 + d1 * d1);
                    }
                    if (cosval <= cosr && cosval >= cos2r) pxsear(cosr, vseed.clone());
                    if (ierr != 0) return;
                }
            }
            if (unstbl) {
                ierr = -1;
                message = "Too many iterations to find a proto-jet";
                return;
            }
            pxord(epslon);
            pxolap(ovlim);
            pxord(epslon);
            if (robust) dropEmptyProtojets();
            if (njet > mxjet && !robust) {
                ierr = -1;
                message = "Found more than MXJET jets";
                return;
            }
            pjet = new double[njet][4];
            for (int i = 0; i < njet; i++) {
                final double[] j = pj.get(i);
                if (mode != 2) {
                    System.arraycopy(j, 0, pjet[i], 0, 4);
                } else {
                    pjet[i][0] = j[3] * CRMath.cos(j[1]);
                    pjet[i][1] = j[3] * CRMath.sin(j[1]);
                    pjet[i][2] = j[3] * CRMath.sinh(j[0]);
                    pjet[i][3] = j[3] * CRMath.cosh(j[0]);
                }
            }
            ipass = new int[ntrak];
            ijmul = new int[Math.max(mxjet, njet)];
            for (int i = 0; i < ntrak; i++) {
                ipass[i] = -1;
                for (int j = 0; j < njet; j++) {
                    if (jetlis.get(j)[i]) {
                        ijmul[j]++;
                        ipass[i] = j + 1;
                    }
                }
            }
        }

        /** The robust mode: proto-jets without any particle removed, order kept. */
        private void dropEmptyProtojets() {
            int k = 0;
            for (int i = 0; i < njet; i++) {
                boolean any = false;
                for (boolean b : jetlis.get(i)) {
                    if (b) {
                        any = true;
                        break;
                    }
                }
                if (any) {
                    jetlis.set(k, jetlis.get(i));
                    pj.set(k, pj.get(i));
                    k++;
                }
            }
            njet = k;
        }

        /** PXUVEC: the unit vectors of the particles. */
        private void pxuvec() {
            for (int n = 0; n < ntrak; n++) {
                double mag = 0.0;
                for (int mu = 0; mu < 3; mu++) mag = mag + pp[n][mu] * pp[n][mu];
                mag = Math.sqrt(mag);
                if (mag == 0.0) {
                    ierr = -1;
                    message = "An input particle has zero mod(p)";
                    return;
                }
                for (int mu = 0; mu < 3; mu++) pu[n][mu] = pp[n][mu] / mag;
            }
        }

        /** PXNORV(3, A, A): normalises in place, unless of zero length. */
        private static void pxnorv(double[] a) {
            double c = 0;
            for (int i = 0; i < 3; i++) c = c + a[i] * a[i];
            if (c <= 0) return;
            c = 1 / Math.sqrt(c);
            for (int i = 0; i < 3; i++) a[i] = a[i] * c;
        }

        /** PXSEAR: iterates a cone from a seed axis; a new stable one becomes a proto-jet. */
        private void pxsear(double cosr, double[] vseed) {
            final double[] oaxis = vseed.clone();
            final double[] naxis = new double[3];
            final double[] pnew = new double[4];
            boolean[] oldlis = new boolean[ntrak];
            for (int iter = 1; iter <= 30; iter++) {
                final boolean[] newlis = new boolean[ntrak];
                final boolean ok = pxtry(cosr, oaxis, naxis, pnew, newlis);
                if (!ok) return;
                if (Arrays.equals(newlis, oldlis)) {
                    if (pxnew(newlis)) {
                        if (njet < jetlis.size()) {
                            jetlis.set(njet, newlis);
                            pj.set(njet, pnew.clone());
                        } else {
                            jetlis.add(newlis);
                            pj.add(pnew.clone());
                        }
                        njet = njet + 1;
                    }
                    return;
                }
                oldlis = newlis;
                System.arraycopy(naxis, 0, oaxis, 0, 3);
            }
            unstbl = true;
        }

        /** PXTRY: the particles in the cone about an axis, their momentum and new axis. */
        private boolean pxtry(double cosr, double[] oaxis, double[] naxis, double[] pnew, boolean[] newlis) {
            boolean ok = false;
            for (int mu = 0; mu < 4; mu++) pnew[mu] = 0.0;
            for (int n = 0; n < ntrak; n++) {
                double cosval;
                if (mode != 2) {
                    cosval = 0.0;
                    for (int mu = 0; mu < 3; mu++) cosval = cosval + oaxis[mu] * pu[n][mu];
                } else if (Math.abs(pu[n][0]) >= 20 || Math.abs(oaxis[0]) >= 20) {
                    cosval = -1000;
                } else {
                    final double d0 = oaxis[0] - pu[n][0];
                    final double d1 = pxmdpi(oaxis[1] - pu[n][1]);
                    cosval = 1 - (d0 * d0 + d1 * d1);
                }
                if (cosval >= cosr) {
                    newlis[n] = true;
                    ok = true;
                    if (mode != 2) {
                        for (int mu = 0; mu < 4; mu++) pnew[mu] = pnew[mu] + pp[n][mu];
                    } else {
                        pnew[0] = pnew[0] + pp[n][3] / (pp[n][3] + pnew[3]) * (pp[n][0] - pnew[0]);
                        pnew[1] = pxmdpi(pnew[1] + pp[n][3] / (pp[n][3] + pnew[3]) * pxmdpi(pp[n][1] - pnew[1]));
                        pnew[3] = pnew[3] + pp[n][3];
                    }
                } else {
                    newlis[n] = false;
                }
            }
            if (ok) {
                final double norm;
                if (mode != 2) {
                    double normsq = 0.0;
                    for (int mu = 0; mu < 3; mu++) normsq = normsq + pnew[mu] * pnew[mu];
                    norm = Math.sqrt(normsq);
                } else {
                    norm = 1;
                }
                for (int mu = 0; mu < 3; mu++) naxis[mu] = pnew[mu] / norm;
            }
            return ok;
        }

        /** PXNEW: whether a particle list is not already that of a proto-jet. */
        private boolean pxnew(boolean[] tstlis) {
            for (int i = 0; i < njet; i++) {
                if (Arrays.equals(tstlis, jetlis.get(i))) return false;
            }
            return true;
        }

        /** PXORD: proto-jets in decreasing energy, those below epslon dropped. */
        private void pxord(double epslon) {
            final double[] elist = new double[njet + 1];
            for (int i = 1; i <= njet; i++) elist[i] = pj.get(i - 1)[3];
            final int[] index = pxsorv(njet, elist);
            final List<double[]> ptemp = new ArrayList<>(pj.subList(0, njet));
            final List<boolean[]> logtmp = new ArrayList<>(jetlis.subList(0, njet));
            for (int i = 1; i <= njet; i++) {
                pj.set(i - 1, ptemp.get(index[njet + 1 - i] - 1).clone());
                jetlis.set(i - 1, logtmp.get(index[njet + 1 - i] - 1).clone());
            }
            final int n = njet; // DO 300, I=1, NJET: bounds fixed on entry
            for (int i = 0; i < n; i++) {
                if (pj.get(i)[3] < epslon) {
                    njet = njet - 1;
                    pj.get(i)[3] = 0.;
                }
            }
        }

        /** PXSORV(N, A, K, 'I'): the ascending order of A(1..N), by a threaded binary tree. */
        static int[] pxsorv(int n, double[] a) {
            final int[] k = new int[n + 1];
            if (n <= 0) return k;
            final int[] il = new int[n + 1];
            final int[] ir = new int[n + 1];
            for (int i = 2; i <= n; i++) {
                int j = 1;
                while (true) {
                    if (a[i] > a[j]) {
                        if (ir[j] <= 0) {
                            ir[i] = ir[j];
                            ir[j] = i;
                            break;
                        }
                        j = ir[j];
                    } else {
                        if (il[j] == 0) {
                            ir[i] = -j;
                            il[j] = i;
                            break;
                        }
                        j = il[j];
                    }
                }
            }
            int i = 1;
            int j = 1;
            while (true) {
                while (il[j] > 0) j = il[j];
                boolean descend = false;
                while (!descend) {
                    k[i] = j;
                    i = i + 1;
                    if (ir[j] < 0) {
                        j = -ir[j];
                    } else if (ir[j] == 0) {
                        return k;
                    } else {
                        j = ir[j];
                        descend = true;
                    }
                }
            }
        }

        /** PXOLAP: overlapping proto-jets removed, shared particles to the nearest. */
        private void pxolap(double ovlim) {
            if (njet <= 1) return;
            for (int i = 1; i < njet; i++) {
                double eover = 0.0;
                for (int n = 0; n < ntrak; n++) {
                    boolean ovelap = false;
                    for (int j = 0; j < i; j++) {
                        if (jetlis.get(i)[n] && jetlis.get(j)[n]) ovelap = true;
                    }
                    if (ovelap) eover = eover + pp[n][3];
                }
                if (eover > ovlim * pj.get(i)[3]) Arrays.fill(jetlis.get(i), false);
            }
            final int[] ijet = new int[njet];
            final double[] vec1 = new double[3];
            final double[] vec2 = new double[3];
            double thet = 0;
            int ijmin = 0;
            for (int i = 0; i < ntrak; i++) {
                int nj = 0;
                for (int j = 0; j < njet; j++) {
                    if (jetlis.get(j)[i]) ijet[nj++] = j;
                }
                if (nj <= 1) continue;
                vec1[0] = pp[i][0];
                vec1[1] = pp[i][1];
                vec1[2] = pp[i][2];
                double thmin = 0.;
                for (int j = 0; j < nj; j++) {
                    final double[] p = pj.get(ijet[j]);
                    vec2[0] = p[0];
                    vec2[1] = p[1];
                    vec2[2] = p[2];
                    if (mode != 2) {
                        // PXANG3, which leaves THET as it was for a null vector
                        double c = (vec1[0] * vec1[0] + vec1[1] * vec1[1] + vec1[2] * vec1[2])
                            * (vec2[0] * vec2[0] + vec2[1] * vec2[1] + vec2[2] * vec2[2]);
                        if (c > 0) {
                            c = 1 / Math.sqrt(c);
                            final double cost = (vec1[0] * vec2[0] + vec1[1] * vec2[1] + vec1[2] * vec2[2]) * c;
                            thet = CRMath.acos(cost);
                        }
                    } else {
                        final double d0 = vec1[0] - vec2[0];
                        final double d1 = pxmdpi(vec1[1] - vec2[1]);
                        thet = d0 * d0 + d1 * d1;
                    }
                    if (j == 0 || thet < thmin) {
                        thmin = thet;
                        ijmin = ijet[j];
                    }
                }
                for (int j = 0; j < njet; j++) jetlis.get(j)[i] = false;
                jetlis.get(ijmin)[i] = true;
            }
            for (int i = 0; i < njet; i++) {
                final double[] p = pj.get(i);
                for (int mu = 0; mu < 4; mu++) p[mu] = 0.0;
                for (int n = 0; n < ntrak; n++) {
                    if (!jetlis.get(i)[n]) continue;
                    if (mode != 2) {
                        for (int mu = 0; mu < 4; mu++) p[mu] = p[mu] + pp[n][mu];
                    } else {
                        p[0] = p[0] + pp[n][3] / (pp[n][3] + p[3]) * (pp[n][0] - p[0]);
                        p[1] = pxmdpi(p[1] + pp[n][3] / (pp[n][3] + p[3]) * pxmdpi(pp[n][1] - p[1]));
                        p[3] = p[3] + pp[n][3];
                    }
                }
            }
        }
    }
}

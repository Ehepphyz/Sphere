package com.sphere.core.fjcontrib.lab;

import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Toy events with a boosted object, at parton level, to try substructure,
 * flavour and pileup tools without a generator.
 *
 * <p>Each hard parton is showered by an angular-ordered soft-collinear
 * cascade: emissions fill its Lund plane with the leading-log density
 * 2 C_R alpha_s / pi per unit ln(1/theta) and ln(1/z), down to kt = ktMin,
 * each gluon emitted cascading in turn inside its own angle, and a gluon
 * sometimes splitting to a quark pair instead (the soft flavoured pairs the
 * flavour algorithms must be safe against). The emitter recoils, so that
 * three-momentum is conserved and each splitting gives the jet its mass,
 * m^2 = z(1-z) E^2 theta^2. Quark jets radiate with C_F, gluon jets with C_A,
 * and the Lund density ':fjco lundmap' shows sits at the value put in.
 * Decaying W, Z, H and top quarks radiate inside the cone of their colour
 * dipoles, which keeps two- and three-prong jets narrower than QCD jets.
 * The partons carry their PDG code as user index, as the fjcontrib flavour
 * examples' own input does.
 *
 * <p>The object recoils against nothing visible (as against a neutrino pair),
 * so that the leading jet is the object; {@link Options#recoil()} adds a QCD
 * parton back to back instead. Not a generator: no hadronisation, no running
 * coupling. Enough for the tools to behave as on real jets.
 */
public final class BoostedToys {

    /** What the leading jet is. */
    public enum Kind {
        QCD, QUARK, GLUON, W, Z, HIGGS, TOP;

        public static Kind parse(String s) {
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "qcd", "dijet" -> QCD;
                case "quark", "q" -> QUARK;
                case "gluon", "g" -> GLUON;
                case "w" -> W;
                case "z" -> Z;
                case "h", "higgs" -> HIGGS;
                case "top", "t" -> TOP;
                default -> throw new IllegalArgumentException("unknown kind " + s + " (qcd, quark, gluon, w, z, higgs, top)");
            };
        }
    }

    /**
     * @param pt     transverse momentum of the boosted object (GeV)
     * @param soft   soft gluons of an underlying event, uniform in |y| &lt; 4
     * @param alphaS the fixed coupling of the cascade
     * @param ktMin  the smallest kt emitted (GeV)
     * @param gToQQ  the probability that a gluon's emission is a g -&gt; q qbar splitting instead
     * @param recoil whether a QCD parton recoils against the object
     */
    public record Options(int events, double pt, int soft, long seed, double alphaS, double ktMin, double gToQQ,
                          boolean recoil) {
        public static Options defaults(int events) {
            return new Options(events, 600, 30, 1, 0.12, 1.0, 0.08, false);
        }
    }

    private static final double CF = 4.0 / 3.0;
    private static final double CA = 3.0;
    private static final double MW = 80.38;
    private static final double MZ = 91.19;
    private static final double MH = 125.1;
    private static final double MT = 172.5;

    private BoostedToys() {
    }

    public static List<List<PseudoJet>> generate(Kind kind, Options o) {
        final SplittableRandom rnd = new SplittableRandom(o.seed() * 0x9E3779B97F4A7C15L + kind.ordinal());
        final List<List<PseudoJet>> events = new ArrayList<>(o.events());
        for (int e = 0; e < o.events(); e++) {
            final List<PseudoJet> ev = new ArrayList<>();
            final double pt = o.pt() * (0.9 + 0.2 * rnd.nextDouble());
            final double y = -1.5 + 3 * rnd.nextDouble();
            final double phi = 2 * Math.PI * rnd.nextDouble();
            switch (kind) {
                case QCD -> shower(ev, rnd.nextBoolean() ? quark(rnd) : 21, momentum(pt, y, phi), 1.0, o, rnd, 0);
                case QUARK -> shower(ev, quark(rnd), momentum(pt, y, phi), 1.0, o, rnd, 0);
                case GLUON -> shower(ev, 21, momentum(pt, y, phi), 1.0, o, rnd, 0);
                case W -> decayTwoBody(ev, MW, pt, y, phi, rnd.nextBoolean() ? new int[]{2, -1} : new int[]{4, -3}, o, rnd);
                case Z -> {
                    final int q = 1 + rnd.nextInt(5);
                    decayTwoBody(ev, MZ, pt, y, phi, new int[]{q, -q}, o, rnd);
                }
                case HIGGS -> decayTwoBody(ev, MH, pt, y, phi, new int[]{5, -5}, o, rnd);
                case TOP -> decayTop(ev, pt, y, phi, o, rnd);
            }
            if (o.recoil()) {
                shower(ev, rnd.nextBoolean() ? quark(rnd) : 21, momentum(pt * (0.9 + 0.2 * rnd.nextDouble()),
                    y + (rnd.nextDouble() - 0.5) * 2, phi + Math.PI + 0.2 * (rnd.nextDouble() - 0.5)), 1.0, o, rnd, 0);
            }
            for (int k = 0; k < o.soft(); k++) {
                final double spt = 0.2 - 0.5 * Math.log(1 - rnd.nextDouble());
                add(ev, 21, momentum(spt, -4 + 8 * rnd.nextDouble(), 2 * Math.PI * rnd.nextDouble()));
            }
            events.add(ev);
        }
        return events;
    }

    /* ------------------------------------------------------------------ */
    /* Hard partons and decays                                             */
    /* ------------------------------------------------------------------ */

    private static int quark(SplittableRandom r) {
        final int q = 1 + r.nextInt(5);
        return r.nextBoolean() ? q : -q;
    }

    /** The three-momentum of a massless particle of given pt, rapidity and azimuth. */
    private static double[] momentum(double pt, double y, double phi) {
        return new double[]{pt * Math.cos(phi), pt * Math.sin(phi), pt * Math.sinh(y)};
    }

    /** A resonance of mass m and given pt decaying isotropically to two massless partons. */
    private static void decayTwoBody(List<PseudoJet> ev, double m, double pt, double y, double phi, int[] pdgs,
                                     Options o, SplittableRandom r) {
        final double mt = Math.sqrt(m * m + pt * pt);
        final double[] res = {mt * Math.cosh(y), pt * Math.cos(phi), pt * Math.sin(phi), mt * Math.sinh(y)};
        final double[][] d = twoBody(res, m, 0, r);
        // a colour singlet radiates inside the angle between its two partons
        final double opening = angle(d[0], d[1]);
        shower(ev, pdgs[0], three(d[0]), opening, o, r, 0);
        shower(ev, pdgs[1], three(d[1]), opening, o, r, 0);
    }

    /** t -> b W+, W+ -> u dbar. */
    private static void decayTop(List<PseudoJet> ev, double pt, double y, double phi, Options o, SplittableRandom r) {
        final double mt = Math.sqrt(MT * MT + pt * pt);
        final double[] top = {mt * Math.cosh(y), pt * Math.cos(phi), pt * Math.sin(phi), mt * Math.sinh(y)};
        final double[][] bw = twoBody(top, MT, MW, r);
        final double[][] ud = twoBody(bw[1], MW, 0, r);
        final double wOpening = angle(ud[0], ud[1]);
        shower(ev, 5, three(bw[0]), 0.8, o, r, 0);
        shower(ev, 2, three(ud[0]), wOpening, o, r, 0);
        shower(ev, -1, three(ud[1]), wOpening, o, r, 0);
    }

    private static double angle(double[] a, double[] b) {
        final double c = (a[1] * b[1] + a[2] * b[2] + a[3] * b[3])
            / Math.sqrt((a[1] * a[1] + a[2] * a[2] + a[3] * a[3]) * (b[1] * b[1] + b[2] * b[2] + b[3] * b[3]));
        return Math.acos(Math.max(-1, Math.min(1, c)));
    }

    private static double[] three(double[] p4) {
        return new double[]{p4[1], p4[2], p4[3]};
    }

    /**
     * Two daughters, of masses 0 and m2, of a parent (E, px, py, pz) of mass
     * m, isotropic in its rest frame; answers their (E, px, py, pz).
     */
    private static double[][] twoBody(double[] parent, double m, double m2, SplittableRandom r) {
        final double p = (m * m - m2 * m2) / (2 * m);
        final double cost = 2 * r.nextDouble() - 1;
        final double sint = Math.sqrt(1 - cost * cost);
        final double ph = 2 * Math.PI * r.nextDouble();
        final double[] a = {p, p * sint * Math.cos(ph), p * sint * Math.sin(ph), p * cost};
        final double[] b = {Math.sqrt(p * p + m2 * m2), -a[1], -a[2], -a[3]};
        return new double[][]{boost(a, parent, m), boost(b, parent, m)};
    }

    /** Boosts q from the rest frame of the parent (of mass m) to the lab. */
    private static double[] boost(double[] q, double[] parent, double m) {
        final double bx = parent[1] / parent[0];
        final double by = parent[2] / parent[0];
        final double bz = parent[3] / parent[0];
        final double gamma = parent[0] / m;
        final double bp = bx * q[1] + by * q[2] + bz * q[3];
        final double b2 = bx * bx + by * by + bz * bz;
        final double k = b2 > 0 ? (gamma - 1) * bp / b2 + gamma * q[0] : 0;
        return new double[]{gamma * (q[0] + bp), q[1] + k * bx, q[2] + k * by, q[3] + k * bz};
    }

    /* ------------------------------------------------------------------ */
    /* The cascade                                                         */
    /* ------------------------------------------------------------------ */

    private static double norm(double[] p) {
        return Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
    }

    /**
     * Emissions of a massless parton of three-momentum p, at angles below
     * thetaMax, in decreasing angle; the emitter recoils against each one,
     * and each emitted gluon cascades within its own angle.
     */
    private static void shower(List<PseudoJet> ev, int pdg, double[] p, double thetaMax, Options o,
                               SplittableRandom r, int depth) {
        final boolean gluon = pdg == 21;
        final double cr = gluon ? CA : CF;
        final double e = norm(p);
        // kt = z E theta > ktMin with z < 1/2 and theta < thetaMax: in (a, b) = (ln thetaMax/theta,
        // ln 1/2z) the triangle a + b < L, of area L^2/2 and density 2 C_R alpha_s / pi
        final double l = Math.log(e * thetaMax / (2 * o.ktMin()));
        if (depth > 40 || l <= 0) {
            add(ev, pdg, p);
            return;
        }
        final int count = poisson(2 * cr * o.alphaS() / Math.PI * l * l / 2, r);
        final double[] as = new double[count];
        final double[] bs = new double[count];
        for (int k = 0; k < count; k++) {
            double a;
            double b;
            do {
                a = l * r.nextDouble();
                b = l * r.nextDouble();
            } while (a + b >= l);
            as[k] = a;
            bs[k] = b;
        }
        final Integer[] order = new Integer[count];
        for (int k = 0; k < count; k++) order[k] = k;
        java.util.Arrays.sort(order, (x, y) -> Double.compare(as[x], as[y]));
        final double[] current = p.clone();
        for (int k : order) {
            final double ecur = norm(current);
            final double theta = thetaMax * Math.exp(-as[k]);
            final double z = 0.5 * Math.exp(-bs[k]);
            if (z * ecur * theta < o.ktMin()) continue; // the recoil took the emitter below the cut
            final double[] n = {current[0] / ecur, current[1] / ecur, current[2] / ecur};
            final double[] m = rotate(n, theta, 2 * Math.PI * r.nextDouble());
            final double[] emitted = {z * ecur * m[0], z * ecur * m[1], z * ecur * m[2]};
            final double[] rest = {current[0] - emitted[0], current[1] - emitted[1], current[2] - emitted[2]};
            if (gluon && r.nextDouble() < o.gToQQ()) {
                // the gluon splits into a quark pair and is gone
                final int q = 1 + r.nextInt(5);
                shower(ev, q, rest, theta, o, r, depth + 1);
                shower(ev, -q, emitted, theta, o, r, depth + 1);
                return;
            }
            shower(ev, 21, emitted, theta, o, r, depth + 1);
            System.arraycopy(rest, 0, current, 0, 3);
        }
        add(ev, pdg, current);
    }

    /** n turned by theta, at azimuth phi around itself. */
    private static double[] rotate(double[] n, double theta, double phi) {
        final double[] u = Math.abs(n[2]) < 0.9 ? cross(n, new double[]{0, 0, 1}) : cross(n, new double[]{1, 0, 0});
        final double nu = norm(u);
        for (int i = 0; i < 3; i++) u[i] /= nu;
        final double[] v = cross(n, u);
        final double c = Math.cos(theta);
        final double s = Math.sin(theta);
        final double cp = Math.cos(phi);
        final double sp = Math.sin(phi);
        return new double[]{c * n[0] + s * (cp * u[0] + sp * v[0]), c * n[1] + s * (cp * u[1] + sp * v[1]),
            c * n[2] + s * (cp * u[2] + sp * v[2])};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static int poisson(double mean, SplittableRandom r) {
        if (mean <= 0) return 0;
        if (mean > 60) return Math.max(0, (int) Math.round(mean + Math.sqrt(mean) * gauss(r)));
        final double limit = Math.exp(-mean);
        double p = r.nextDouble();
        int k = 0;
        while (p > limit) {
            p *= r.nextDouble();
            k++;
        }
        return k;
    }

    private static double gauss(SplittableRandom r) {
        return Math.sqrt(-2 * Math.log(1 - r.nextDouble())) * Math.cos(2 * Math.PI * r.nextDouble());
    }

    /** A massless parton of three-momentum p, its PDG code as user index. */
    private static void add(List<PseudoJet> ev, int pdg, double[] p) {
        final double e = norm(p);
        if (!(e > 0)) return;
        final PseudoJet j = new PseudoJet(p[0], p[1], p[2], e, Precision.defaultPrecision());
        j.setUserIndex(pdg);
        ev.add(j);
    }
}

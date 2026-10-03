package com.sphere.core.fjcontrib.ktcluscxx;

import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.NNBriefJet;
import com.sphere.core.fastjet.NNH;
import com.sphere.core.fastjet.Precision;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.RecombinationScheme;
import com.sphere.core.fastjet.plugins.EEDirection;
import com.sphere.core.fjcontrib.ContribCitations;

/**
 * The KTCLUS algorithms, fastjet::contrib::KTClusCXXPlugin (KTClusCXX 1.0.1,
 * A. Verbytskyi; a C++ rendering of the Fortran KTCLUS of M.H. Seymour,
 * after S. Catani, Yu.L. Dokshitzer, M.H. Seymour and B.R. Webber, Nucl.
 * Phys. B 406 (1993) 187): the exclusive kt algorithm for e+e-, e p, p e
 * and p p collisions, with the angular, Delta R or "f" (cosh Dy - cos Dphi)
 * distance, selected by a four-digit mode TYPE ANGLE MONO RECOM (e.g. 2111:
 * e p, angular, jets, E scheme).
 *
 * <p>With {@link Precision#DOUBLE} the clustering is the C++ one bit for bit.
 * In double-double the angular 1 - cos theta comes from the chord, the beam
 * factor 1 -+ cos theta from kt^2 / (|p| (|p| +- pz)) when it would cancel,
 * and cosh Dy - cos Dphi as 2 (sinh^2(Dy/2) + sin^2(Dphi/2)): the distances
 * of nearly collinear pairs and of particles along the beams keep their
 * full relative precision.
 */
public class KTClusCXXPlugin implements JetDefinition.Plugin {

    static {
        ContribCitations.use("ktcluscxx");
    }

    public static final int EE = 1000, EP = 2000, PE = 3000, PP = 4000;
    public static final int ANGULAR = 100, DELTAR = 200, F = 300;
    public static final int JETS = 10, MONOLITIC = 20;
    public static final int RECOM_E = 1, RECOM_PT = 2, RECOM_PT2 = 3;

    private static final double EE_BEAM = Double.MAX_VALUE / 256;

    private final int type;
    private final int angle;
    private final int mono;
    private final int recom;
    private final double r;

    public KTClusCXXPlugin(int type, int angle, int mono, int recom, double radius) {
        this.type = type;
        this.angle = angle;
        this.mono = mono;
        this.recom = recom;
        this.r = radius;
    }

    /** KTClusCXXJetDefinition(mode, R): the plugin with the recombination scheme of the last digit. */
    public static JetDefinition jetDefinition(int mode, double radius) {
        final int type = 1000 * (mode / 1000);
        final int angle = 100 * ((mode / 100) % 10);
        final int mono = 10 * ((mode / 10) % 10);
        final int recom = mode % 10;
        if (mode < 1000 || mode > 9999 || type > 4000 || angle < 100 || angle > 300 || mono < 10 || mono > 20
            || recom < 1 || recom > 3) {
            throw new FastJetException("In KTClusCXXJetDefinition, unrecognized mode =" + mode);
        }
        final JetDefinition jd = new JetDefinition(new KTClusCXXPlugin(type, angle, mono, recom, radius));
        jd.setRecombinationScheme(recom == 1 ? RecombinationScheme.E_SCHEME
            : recom == 2 ? RecombinationScheme.PT_SCHEME : RecombinationScheme.PT2_SCHEME);
        return jd;
    }

    public static JetDefinition jetDefinition(int mode) {
        return jetDefinition(mode, 1.0);
    }

    public static JetDefinition jetDefinition(int m1, int m2, int m3, int m4, double x) {
        return jetDefinition(m1 + m2 + m3 + m4, x);
    }

    public int mode() { return type + angle + mono + recom; }

    @Override
    public String description() {
        return " KTCLUS algorithm plugin in mode " + type + angle + mono + recom + ", using NNH strategy";
    }

    @Override public double R() { return r; }
    @Override public boolean exclusiveSequenceMeaningful() { return true; }

    private static double toPi(double a1) {
        double phi = a1;
        while (phi > Math.PI) phi -= 2 * Math.PI;
        while (phi < -Math.PI) phi += 2 * Math.PI;
        return phi;
    }

    private static DD toPi(DD a1) {
        DD phi = a1;
        while (phi.gt(DD.PI)) phi = phi.sub(DD.TWO_PI);
        while (phi.lt(DD.PI.neg())) phi = phi.add(DD.TWO_PI);
        return phi;
    }

    /** KTClusBriefJet. */
    static final class BriefJet implements NNBriefJet<BriefJet> {
        private final KTClusCXXPlugin p;
        private final boolean dd;
        private final double e;
        private final double pt;
        private final double phi;
        private final double rap;
        private final double nx;
        private final double ny;
        private final double nz;
        private EEDirection n;
        private DD e2;
        private DD pt2;
        private DD phiDD;
        private DD rapDD;
        private DD beam;
        private double low;

        BriefJet(PseudoJet jet, KTClusCXXPlugin p, boolean dd) {
            this.p = p;
            this.dd = dd;
            final double norm = 1.0 / Math.sqrt(jet.modp2());
            nx = jet.px() * norm;
            ny = jet.py() * norm;
            nz = jet.pz() * norm;
            e = jet.E();
            pt = jet.pt();
            phi = jet.phi();
            rap = jet.rap();
            if (dd) {
                n = new EEDirection(jet, true);
                e2 = jet.eDD().sqr();
                pt2 = jet.kt2DD();
                phiDD = jet.phiDD();
                rapDD = jet.rapDD();
                beam = beamDD(jet);
            }
        }

        private DD beamDD(PseudoJet jet) {
            if (p.type == EE) return new DD(EE_BEAM);
            final DD r2 = DD.square(p.r);
            if (p.angle != ANGULAR) return r2.mul(pt2);
            final DD kt2 = jet.kt2DD();
            final DD pz = jet.pzDD();
            final DD modp = kt2.add(pz.sqr()).sqrt();
            // 1 + nz and 1 - nz without cancellation
            final DD onePlus = pz.lt(0.0) ? kt2.div(modp.mul(modp.sub(pz))) : modp.add(pz).div(modp);
            final DD oneMinus = pz.gt(0.0) ? kt2.div(modp.mul(modp.add(pz))) : modp.sub(pz).div(modp);
            final DD f = p.type == EP ? onePlus : p.type == PE ? oneMinus : DD.min(oneMinus, onePlus);
            return r2.mulPow2(2.0).mul(e2).mul(f);
        }

        @Override
        public double distance(BriefJet o) {
            if (p.mono != JETS) throw new FastJetException("The algorithms with MONO!=1 are not implemented in the current version.");
            if (!dd) {
                low = 0.0;
                return switch (p.angle) {
                    case ANGULAR -> 2 * Math.min(e * e, o.e * o.e) * (1.0 - (nx * o.nx + ny * o.ny + nz * o.nz));
                    case DELTAR -> Math.min(pt * pt, o.pt * o.pt)
                        * (CRMath.pow(toPi(phi - o.phi), 2) + CRMath.pow(rap - o.rap, 2));
                    case F -> Math.min(pt * pt, o.pt * o.pt) * 2 * (CRMath.cosh(rap - o.rap) - CRMath.cos(toPi(phi - o.phi)));
                    default -> throw new FastJetException("Unknown distance function");
                };
            }
            final DD d = switch (p.angle) {
                case ANGULAR -> {
                    final double c = n.oneMinusCos(o.n);
                    yield DD.min(e2, o.e2).mulPow2(2.0).mul(new DD(c, n.lastLow()));
                }
                case DELTAR -> DD.min(pt2, o.pt2).mul(toPi(phiDD.sub(o.phiDD)).sqr().add(rapDD.sub(o.rapDD).sqr()));
                case F -> {
                    final DD sh = rapDD.sub(o.rapDD).mulPow2(0.5).sinh();
                    final DD sn = toPi(phiDD.sub(o.phiDD)).mulPow2(0.5).sin();
                    yield DD.min(pt2, o.pt2).mulPow2(4.0).mul(sh.sqr().add(sn.sqr()));
                }
                default -> throw new FastJetException("Unknown distance function");
            };
            low = d.lo;
            return d.hi;
        }

        @Override
        public double beamDistance() {
            if (dd) {
                low = beam.lo;
                return beam.hi;
            }
            low = 0.0;
            if (p.type == EE) return EE_BEAM;
            final double rr = p.r;
            if (p.angle == ANGULAR) {
                return switch (p.type) {
                    case EP -> 2 * rr * rr * e * e * (1 + nz);
                    case PE -> 2 * rr * rr * e * e * (1 - nz);
                    default -> 2 * rr * rr * e * e * Math.min(1 - nz, 1 + nz);
                };
            }
            return rr * rr * pt * pt;
        }

        @Override public double lowWord() { return low; }
    }

    @Override
    public void runClustering(ClusterSequence cs) {
        final boolean dd = cs.precision() == Precision.DD;
        int njets = cs.jets().size();
        final NNH<BriefJet> nn = new NNH<>(cs.jets(), j -> new BriefJet(j, this, dd));
        final int[] ab = new int[2];
        while (njets > 0) {
            final DD dij = nn.dijMinDD(ab);
            final int i = ab[0];
            final int j = ab[1];
            if (j >= 0) {
                final int k = dd ? cs.pluginRecordIJRecombination(i, j, dij) : cs.pluginRecordIJRecombination(i, j, dij.hi);
                nn.mergeJets(i, j, cs.jet(k), k);
            } else {
                final BriefJet jt = new BriefJet(cs.jet(i), this, dd);
                final double diB = jt.beamDistance();
                if (dd) cs.pluginRecordIBRecombination(i, new DD(diB, jt.lowWord()));
                else cs.pluginRecordIBRecombination(i, diB);
                nn.removeJet(i);
            }
            njets--;
        }
    }
}

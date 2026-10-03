package com.sphere.core.fjcontrib.nsubjettiness;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fjcontrib.nsubjettiness.TauComponents.TauMode;

import java.util.ArrayList;
import java.util.List;

/**
 * How tau_N is measured, MeasureDefinition (Nsubjettiness 2.3.2): the
 * distance of a particle to an axis and to the beam, their weights, the
 * normalisation, and the one-pass minimisation of the axes they allow.
 *
 * The measures themselves are the nested classes, as in the C++:
 * {@link NormalizedMeasure}, {@link UnnormalizedMeasure},
 * {@link NormalizedCutoffMeasure}, {@link UnnormalizedCutoffMeasure}
 * (jet shapes), {@link ConicalMeasure}, {@link OriginalGeometricMeasure},
 * {@link ModifiedGeometricMeasure}, {@link ConicalGeometricMeasure},
 * {@link XConeMeasure} (event shapes, jet finding).
 *
 * The sums over particles are carried to 106 bits when the particles are
 * double-double; with double particles they are the C++'s, term for term.
 */
public abstract class MeasureDefinition {

    protected TauMode tauMode = TauMode.UNDEFINED_SHAPE;
    protected boolean useAxisScaling = true;

    protected MeasureDefinition() {
    }

    public abstract String description();

    /** A copy, create() in the C++. */
    public abstract MeasureDefinition create();

    /** The fast distance to an axis (by default the numerator). */
    public double jetDistanceSquared(PseudoJet particle, PseudoJet axis) {
        return jetNumerator(particle, axis);
    }

    /** The fast distance to the beam (by default the numerator). */
    public double beamDistanceSquared(PseudoJet particle) {
        return beamNumerator(particle);
    }

    public abstract double jetNumerator(PseudoJet particle, PseudoJet axis);

    public abstract double beamNumerator(PseudoJet particle);

    public abstract double denominator(PseudoJet particle);

    protected void setTauMode(TauMode mode) {
        tauMode = mode;
    }

    protected void setAxisScaling(boolean scaling) {
        useAxisScaling = scaling;
    }

    public boolean hasDenominator() {
        return tauMode == TauMode.NORMALIZED_JET_SHAPE || tauMode == TauMode.NORMALIZED_EVENT_SHAPE;
    }

    public boolean hasBeam() {
        return tauMode == TauMode.UNNORMALIZED_EVENT_SHAPE || tauMode == TauMode.NORMALIZED_EVENT_SHAPE;
    }

    /** A light-like axis along the three-momentum. */
    protected static PseudoJet lightFrom(PseudoJet input) {
        final double length = Math.sqrt(input.px() * input.px() + input.py() * input.py() + input.pz() * input.pz());
        return new PseudoJet(input.px() / length, input.py() / length, input.pz() / length, 1.0, input.precision());
    }

    protected static double sq(double x) {
        return x * x;
    }

    /** tau for these particles and axes. */
    public double result(List<PseudoJet> particles, List<PseudoJet> axes) {
        return componentResult(particles, axes).tau();
    }

    public TauComponents componentResult(List<PseudoJet> particles, List<PseudoJet> axes) {
        return componentResultFromPartition(getPartition(particles, axes), axes);
    }

    /** Each particle to its nearest axis, or to the beam. */
    public TauPartition getPartition(List<PseudoJet> particles, List<PseudoJet> axes) {
        final TauPartition p = new TauPartition(axes.size());
        for (int i = 0; i < particles.size(); i++) {
            int jMin = -1;
            double minRsq = hasBeam() ? beamDistanceSquared(particles.get(i)) : Double.MAX_VALUE;
            for (int j = 0; j < axes.size(); j++) {
                final double tempRsq = jetDistanceSquared(particles.get(i), axes.get(j));
                if (tempRsq < minRsq) {
                    minRsq = tempRsq;
                    jMin = j;
                }
            }
            if (jMin == -1) {
                if (!hasBeam()) throw new FastJetException("MeasureDefinition: a particle is in no region and there is no beam");
                p.pushBackBeam(particles.get(i), i);
            } else {
                p.pushBackJet(jMin, particles.get(i), i);
            }
        }
        return p;
    }

    public TauComponents componentResultFromPartition(TauPartition partition, List<PseudoJet> axes) {
        final double[] jetPieces = new double[axes.size()];
        double beamPiece = 0.0;
        double tauDen = hasDenominator() ? 0.0 : 1.0;
        boolean dd = false;
        final DD[] jetDD = new DD[axes.size()];
        DD beamDD = DD.ZERO;
        DD denDD = new DD(tauDen);
        for (int j = 0; j < axes.size(); j++) {
            jetDD[j] = DD.ZERO;
            final List<PseudoJet> part = partition.jet(j).constituents();
            for (PseudoJet c : part) {
                final double num = jetNumerator(c, axes.get(j));
                jetPieces[j] += num;
                jetDD[j] = jetDD[j].add(num);
                dd |= c.isDD();
                if (hasDenominator()) {
                    final double den = denominator(c);
                    tauDen += den;
                    denDD = denDD.add(den);
                }
            }
        }
        if (hasBeam()) {
            for (PseudoJet c : partition.beam().constituents()) {
                final double num = beamNumerator(c);
                beamPiece += num;
                beamDD = beamDD.add(num);
                dd |= c.isDD();
                if (hasDenominator()) {
                    final double den = denominator(c);
                    tauDen += den;
                    denDD = denDD.add(den);
                }
            }
        }
        final List<PseudoJet> jets = partition.jets();
        if (!dd) return new TauComponents(tauMode, jetPieces, beamPiece, tauDen, jets, axes);
        // double-double particles: every sum kept to 106 bits, rounded once
        DD total = beamDD;
        for (int j = 0; j < jetPieces.length; j++) {
            jetPieces[j] = jetDD[j].doubleValue();
            total = total.add(jetDD[j]);
        }
        final double den = denDD.doubleValue();
        return new TauComponents(tauMode, jetPieces, beamDD.doubleValue(), den, jets, axes,
            hasDenominator() ? total.div(denDD).mul(den) : total);
    }

    /**
     * The generic one-pass minimisation: particles assigned to their nearest
     * axis, each axis moved to their (weighted) light-like sum, while tau
     * decreases; the best axes met are returned.
     */
    public List<PseudoJet> getOnePassAxes(int nJets, List<PseudoJet> particles, List<PseudoJet> currentAxes,
                                          int nAttempts, double accuracy) {
        if (nJets != currentAxes.size()) throw new FastJetException("getOnePassAxes: " + nJets + " axes expected");
        List<PseudoJet> seedAxes = new ArrayList<>(currentAxes.size());
        for (PseudoJet a : currentAxes) seedAxes.add(lightFrom(a).times(a.E()));
        double seedTau = result(particles, seedAxes);
        List<PseudoJet> bestAxesSoFar = seedAxes;
        double bestTauSoFar = seedTau;
        for (int iAtt = 0; iAtt < nAttempts; iAtt++) {
            List<PseudoJet> newAxes = new ArrayList<>(seedAxes.size());
            final List<PseudoJet> summedJets = new ArrayList<>(seedAxes.size());
            for (int k = 0; k < seedAxes.size(); k++) {
                newAxes.add(new PseudoJet(0, 0, 0, 0));
                summedJets.add(new PseudoJet(0, 0, 0, 0));
            }
            for (PseudoJet p : particles) {
                int minJ = -1;
                double minDist = beamDistanceSquared(p);
                for (int j = 0; j < seedAxes.size(); j++) {
                    final double tempDist = jetDistanceSquared(p, seedAxes.get(j));
                    if (tempDist < minDist) {
                        minDist = tempDist;
                        minJ = j;
                    }
                }
                if (minJ != -1) {
                    summedJets.get(minJ).plusEqual(p);
                    if (useAxisScaling) {
                        final double pseudoMomentum = PseudoJet.dotProduct(lightFrom(seedAxes.get(minJ)), p) + accuracy;
                        final double axisScaling = jetNumerator(p, seedAxes.get(minJ)) / pseudoMomentum;
                        newAxes.get(minJ).plusEqual(p.times(axisScaling));
                    }
                }
            }
            if (!useAxisScaling) newAxes = new ArrayList<>(summedJets);
            for (int k = 0; k < newAxes.size(); k++) {
                if (newAxes.get(k).perp() > 0) {
                    final PseudoJet l = lightFrom(newAxes.get(k));
                    l.timesEqual(summedJets.get(k).E());
                    newAxes.set(k, l);
                }
            }
            final double newTau = result(particles, newAxes);
            if (newTau < bestTauSoFar) {
                bestAxesSoFar = newAxes;
                bestTauSoFar = newTau;
            }
            if (Math.abs(newTau - seedTau) < accuracy) {
                break;
            }
            seedAxes = newAxes;
            seedTau = newTau;
        }
        return bestAxesSoFar;
    }

    /* ================================================================== */
    /* The default (N-subjettiness) measures                              */
    /* ================================================================== */

    /** The energy and angle of the default measures. */
    public enum DefaultMeasureType {
        /** Transverse momenta and boost-invariant angles. */
        pt_R,
        /** Energies and angles. */
        E_theta,
        /** Dot-product inspired. */
        lorentz_dot,
        /** Conical-geometric inspired. */
        perp_lorentz_dot
    }

    /** The original N-subjettiness measure, DefaultMeasure: pt DeltaR^beta, normalised by pt R0^beta. */
    public static class DefaultMeasure extends MeasureDefinition {
        protected final double beta;
        protected final double r0;
        protected final double rcutoff;
        protected double rcutoffSq;
        protected DefaultMeasureType measureType;

        protected DefaultMeasure(double beta, double r0, double rcutoff, DefaultMeasureType measureType) {
            this.beta = beta;
            this.r0 = r0;
            this.rcutoff = rcutoff;
            this.rcutoffSq = sq(rcutoff);
            this.measureType = measureType;
            if (beta <= 0) throw new FastJetException("DefaultMeasure:  You must choose beta > 0.");
            if (r0 <= 0) throw new FastJetException("DefaultMeasure:  You must choose R0 > 0.");
            if (rcutoff <= 0) throw new FastJetException("DefaultMeasure:  You must choose Rcutoff > 0.");
        }

        protected DefaultMeasure(DefaultMeasure o) {
            this.beta = o.beta;
            this.r0 = o.r0;
            this.rcutoff = o.rcutoff;
            this.rcutoffSq = o.rcutoffSq;
            this.measureType = o.measureType;
            this.tauMode = o.tauMode;
            this.useAxisScaling = o.useAxisScaling;
        }

        @Override
        public String description() {
            return "Default Measure (should not be used directly)";
        }

        @Override
        public DefaultMeasure create() {
            return new DefaultMeasure(this);
        }

        @Override
        public double jetDistanceSquared(PseudoJet particle, PseudoJet axis) {
            return angleSquared(particle, axis);
        }

        @Override
        public double beamDistanceSquared(PseudoJet particle) {
            return rcutoffSq;
        }

        @Override
        public double jetNumerator(PseudoJet particle, PseudoJet axis) {
            final double jetDist = angleSquared(particle, axis);
            return jetDist > 0.0 ? energy(particle) * CRMath.pow(jetDist, beta / 2.0) : 0.0;
        }

        @Override
        public double beamNumerator(PseudoJet particle) {
            return energy(particle) * CRMath.pow(rcutoff, beta);
        }

        @Override
        public double denominator(PseudoJet particle) {
            return energy(particle) * CRMath.pow(r0, beta);
        }

        protected void setDefaultMeasureType(DefaultMeasureType t) {
            measureType = t;
        }

        public double beta() { return beta; }
        public double R0() { return r0; }
        public double Rcutoff() { return rcutoff; }

        protected double energy(PseudoJet jet) {
            return switch (measureType) {
                case pt_R, perp_lorentz_dot -> jet.perp();
                case E_theta, lorentz_dot -> jet.e();
            };
        }

        protected double angleSquared(PseudoJet jet1, PseudoJet jet2) {
            switch (measureType) {
                case pt_R:
                    return jet1.squaredDistance(jet2);
                case E_theta: {
                    final double dot = jet1.px() * jet2.px() + jet1.py() * jet2.py() + jet1.pz() * jet2.pz();
                    final double norm1 = Math.sqrt(jet1.px() * jet1.px() + jet1.py() * jet1.py() + jet1.pz() * jet1.pz());
                    final double norm2 = Math.sqrt(jet2.px() * jet2.px() + jet2.py() * jet2.py() + jet2.pz() * jet2.pz());
                    double costheta = dot / (norm1 * norm2);
                    if (costheta > 1.0) costheta = 1.0;
                    final double theta = CRMath.acos(costheta);
                    return theta * theta;
                }
                case lorentz_dot: {
                    final double dotproduct = PseudoJet.dotProduct(jet1, jet2);
                    return 2.0 * dotproduct / (jet1.e() * jet2.e());
                }
                case perp_lorentz_dot: {
                    final PseudoJet lightJet = lightFrom(jet2);
                    final double dotproduct = PseudoJet.dotProduct(jet1, lightJet);
                    return 2.0 * dotproduct / (lightJet.pt() * jet1.pt());
                }
                default:
                    return Double.NaN;
            }
        }

        protected String measureTypeName() {
            return measureType.name();
        }

        /** The v1.0 minimisation for pt_R: a Lloyd iteration of light-like axes in (y, phi). */
        @Override
        public List<PseudoJet> getOnePassAxes(int nJets, List<PseudoJet> inputJets, List<PseudoJet> seedAxes,
                                              int nAttempts, double accuracy) {
            if (measureType != DefaultMeasureType.pt_R) {
                return super.getOnePassAxes(nJets, inputJets, seedAxes, nAttempts, accuracy);
            }
            LightLikeAxis[] oldAxes = new LightLikeAxis[nJets];
            for (int k = 0; k < nJets; k++) {
                oldAxes[k] = new LightLikeAxis(0, 0, 0, 0);
                oldAxes[k].setRap(seedAxes.get(k).rap());
                oldAxes[k].setPhi(seedAxes.get(k).phi());
                oldAxes[k].setMom(seedAxes.get(k).modp());
            }
            double cmp = Double.MAX_VALUE;
            int h = 0;
            while (cmp > accuracy && h < nAttempts) {
                cmp = 0.0;
                h++;
                final LightLikeAxis[] newAxes = updateAxes(oldAxes, inputJets, accuracy);
                if (newAxes.length == 0) {
                    oldAxes = newAxes;
                    break;
                }
                for (int k = 0; k < nJets; k++) cmp += oldAxes[k].distance(newAxes[k]);
                cmp = cmp / nJets;
                oldAxes = newAxes;
            }
            final List<PseudoJet> out = new ArrayList<>();
            for (int k = 0; k < oldAxes.length; k++) out.add(oldAxes[k].convertToPseudoJet());
            return out;
        }

        /** UpdateAxesFast: one assignment and update step (up to 20 axes, as in the C++). */
        protected LightLikeAxis[] updateAxes(LightLikeAxis[] oldAxes, List<PseudoJet> inputJets, double accuracy) {
            final int n = oldAxes.length;
            if (n < 1 || n > 20) {
                System.out.println("N-jettiness is hard-coded to only allow up to 20 jets!");
                return new LightLikeAxis[0];
            }
            final LightLikeAxis[] newAxes = new LightLikeAxis[n];
            final PseudoJet[] newJets = new PseudoJet[n];
            for (int k = 0; k < n; k++) {
                newAxes[k] = new LightLikeAxis(0, 0, 0, 0);
                newJets[k] = new PseudoJet(0, 0, 0, 0);
            }
            final double precision = accuracy;
            final int[] assignment = new int[inputJets.size()];
            int kAssign = -1;
            for (int i = 0; i < inputJets.size(); i++) {
                double smallestDist = Double.MAX_VALUE;
                for (int k = 0; k < n; k++) {
                    final double thisDist = oldAxes[k].distanceSq(inputJets.get(i));
                    if (thisDist < smallestDist) {
                        smallestDist = thisDist;
                        kAssign = k;
                    }
                }
                if (smallestDist > sq(rcutoff)) kAssign = -1;
                assignment[i] = kAssign;
            }
            for (int i = 0; i < inputJets.size(); i++) {
                final int oldJetI = assignment[i];
                if (oldJetI == -1) continue;
                final PseudoJet in = inputJets.get(i);
                final LightLikeAxis ax = newAxes[oldJetI];
                final double inputPhi = in.phi();
                final double inputRap = in.rap();
                double oldDist;
                if (beta == 1.0) {
                    final double dr = Math.sqrt(sq(precision) + oldAxes[oldJetI].distanceSq(in));
                    oldDist = 1.0 / dr;
                } else if (beta == 2.0) {
                    oldDist = 1.0;
                } else if (beta == 0.0) {
                    final double drSq = sq(precision) + oldAxes[oldJetI].distanceSq(in);
                    oldDist = 1.0 / drSq;
                } else {
                    oldDist = sq(precision) + oldAxes[oldJetI].distanceSq(in);
                    oldDist = CRMath.pow(oldDist, 0.5 * beta - 1.0);
                }
                ax.setRap(ax.rap() + in.perp() * inputRap * oldDist);
                final double distPhi = inputPhi - oldAxes[oldJetI].phi();
                if (Math.abs(distPhi) <= Math.PI) {
                    ax.setPhi(ax.phi() + in.perp() * inputPhi * oldDist);
                } else if (distPhi > Math.PI) {
                    ax.setPhi(ax.phi() + in.perp() * (-2 * Math.PI + inputPhi) * oldDist);
                } else if (distPhi < -Math.PI) {
                    ax.setPhi(ax.phi() + in.perp() * (+2 * Math.PI + inputPhi) * oldDist);
                }
                ax.setWeight(ax.weight() + in.perp() * oldDist);
                newJets[oldJetI].plusEqual(in);
            }
            for (int k = 0; k < n; k++) {
                if (newAxes[k].weight() == 0) {
                    newAxes[k] = oldAxes[k].copy();
                } else {
                    newAxes[k].setRap(newAxes[k].rap() / newAxes[k].weight());
                    newAxes[k].setPhi(newAxes[k].phi() / newAxes[k].weight());
                    newAxes[k].setPhi((newAxes[k].phi() + 2 * Math.PI) % (2 * Math.PI));
                    newAxes[k].setMom(Math.sqrt(newJets[k].modp2()));
                }
            }
            return newAxes;
        }
    }

    /** Dimensionless default measure with a radius cutoff, NormalizedCutoffMeasure(beta, R0, Rcutoff). */
    public static class NormalizedCutoffMeasure extends DefaultMeasure {
        public NormalizedCutoffMeasure(double beta, double r0, double rcutoff, DefaultMeasureType type) {
            super(beta, r0, rcutoff, type);
            setTauMode(TauMode.NORMALIZED_JET_SHAPE);
        }

        public NormalizedCutoffMeasure(double beta, double r0, double rcutoff) {
            this(beta, r0, rcutoff, DefaultMeasureType.pt_R);
        }

        protected NormalizedCutoffMeasure(NormalizedCutoffMeasure o) {
            super(o);
        }

        @Override
        public String description() {
            return "Normalized Cutoff Measure (beta = " + f2(beta) + ", R0 = " + f2(r0) + ", Rcut = " + f2(rcutoff) + ")";
        }

        @Override
        public NormalizedCutoffMeasure create() {
            return new NormalizedCutoffMeasure(this);
        }
    }

    /** Dimensionless default measure, NormalizedMeasure(beta, R0): the original N-subjettiness. */
    public static class NormalizedMeasure extends NormalizedCutoffMeasure {
        public NormalizedMeasure(double beta, double r0, DefaultMeasureType type) {
            super(beta, r0, Double.MAX_VALUE, type);
            rcutoffSq = Double.MAX_VALUE;
            setTauMode(TauMode.NORMALIZED_JET_SHAPE);
        }

        public NormalizedMeasure(double beta, double r0) {
            this(beta, r0, DefaultMeasureType.pt_R);
        }

        protected NormalizedMeasure(NormalizedMeasure o) {
            super(o);
        }

        @Override
        public String description() {
            return "Normalized Measure (beta = " + f2(beta) + ", R0 = " + f2(r0) + ")";
        }

        @Override
        public NormalizedMeasure create() {
            return new NormalizedMeasure(this);
        }
    }

    /** Dimensionful default measure with a radius cutoff, UnnormalizedCutoffMeasure(beta, Rcutoff). */
    public static class UnnormalizedCutoffMeasure extends DefaultMeasure {
        public UnnormalizedCutoffMeasure(double beta, double rcutoff, DefaultMeasureType type) {
            super(beta, Double.NaN, rcutoff, type);
            setTauMode(TauMode.UNNORMALIZED_EVENT_SHAPE);
        }

        public UnnormalizedCutoffMeasure(double beta, double rcutoff) {
            this(beta, rcutoff, DefaultMeasureType.pt_R);
        }

        protected UnnormalizedCutoffMeasure(UnnormalizedCutoffMeasure o) {
            super(o);
        }

        @Override
        public String description() {
            return "Unnormalized Cutoff Measure (beta = " + f2(beta) + ", Rcut = " + f2(rcutoff) + ", in GeV)";
        }

        @Override
        public UnnormalizedCutoffMeasure create() {
            return new UnnormalizedCutoffMeasure(this);
        }
    }

    /** Dimensionful default measure, UnnormalizedMeasure(beta): the recommended jet shape. */
    public static class UnnormalizedMeasure extends UnnormalizedCutoffMeasure {
        public UnnormalizedMeasure(double beta, DefaultMeasureType type) {
            super(beta, Double.MAX_VALUE, type);
            rcutoffSq = Double.MAX_VALUE;
            setTauMode(TauMode.UNNORMALIZED_JET_SHAPE);
        }

        public UnnormalizedMeasure(double beta) {
            this(beta, DefaultMeasureType.pt_R);
        }

        protected UnnormalizedMeasure(UnnormalizedMeasure o) {
            super(o);
        }

        @Override
        public String description() {
            return "Unnormalized Measure (beta = " + f2(beta) + ", in GeV)";
        }

        @Override
        public UnnormalizedMeasure create() {
            return new UnnormalizedMeasure(this);
        }
    }

    /* ================================================================== */
    /* The event-shape measures (Nsubjettiness 2.2)                       */
    /* ================================================================== */

    /** ConicalMeasure(beta, Rcutoff): perfect cones in (y, phi) around light-like axes. */
    public static class ConicalMeasure extends MeasureDefinition {
        protected final double beta;
        protected final double rcutoff;
        protected final double rcutoffSq;

        public ConicalMeasure(double beta, double rcutoff) {
            this.beta = beta;
            this.rcutoff = rcutoff;
            this.rcutoffSq = sq(rcutoff);
            if (beta <= 0) throw new FastJetException("ConicalMeasure:  You must choose beta > 0.");
            if (rcutoff <= 0) throw new FastJetException("ConicalMeasure:  You must choose Rcutoff > 0.");
            setTauMode(TauMode.UNNORMALIZED_EVENT_SHAPE);
        }

        @Override
        public String description() {
            return "Conical Measure (beta = " + f2(beta) + ", Rcut = " + f2(rcutoff) + ", in GeV)";
        }

        @Override
        public ConicalMeasure create() {
            return new ConicalMeasure(beta, rcutoff);
        }

        @Override
        public double jetDistanceSquared(PseudoJet particle, PseudoJet axis) {
            return particle.squaredDistance(lightFrom(axis));
        }

        @Override
        public double beamDistanceSquared(PseudoJet particle) {
            return rcutoffSq;
        }

        @Override
        public double jetNumerator(PseudoJet particle, PseudoJet axis) {
            final double jetDist = particle.squaredDistance(lightFrom(axis)) / rcutoffSq;
            final double jetPerp = particle.perp();
            return beta == 2.0 ? jetPerp * jetDist : jetPerp * CRMath.pow(jetDist, beta / 2.0);
        }

        @Override
        public double beamNumerator(PseudoJet particle) {
            return particle.perp();
        }

        @Override
        public double denominator(PseudoJet particle) {
            return Double.NaN;
        }
    }

    /** OriginalGeometricMeasure(Rcutoff): dot-product distances, no normalisation. */
    public static class OriginalGeometricMeasure extends MeasureDefinition {
        protected final double rcutoff;
        protected final double rcutoffSq;

        public OriginalGeometricMeasure(double rcutoff) {
            this.rcutoff = rcutoff;
            this.rcutoffSq = sq(rcutoff);
            if (rcutoff <= 0) throw new FastJetException("OriginalGeometricMeasure:  You must choose Rcutoff > 0.");
            setTauMode(TauMode.UNNORMALIZED_EVENT_SHAPE);
            setAxisScaling(false);
        }

        @Override
        public String description() {
            return "Original Geometric Measure (Rcut = " + f2(rcutoff) + ", in GeV)";
        }

        @Override
        public OriginalGeometricMeasure create() {
            return new OriginalGeometricMeasure(rcutoff);
        }

        @Override
        public double jetNumerator(PseudoJet particle, PseudoJet axis) {
            return PseudoJet.dotProduct(lightFrom(axis), particle) / rcutoffSq;
        }

        @Override
        public double beamNumerator(PseudoJet particle) {
            final PseudoJet beamA = new PseudoJet(0, 0, 1, 1);
            final PseudoJet beamB = new PseudoJet(0, 0, -1, 1);
            return Math.min(PseudoJet.dotProduct(beamA, particle), PseudoJet.dotProduct(beamB, particle));
        }

        @Override
        public double denominator(PseudoJet particle) {
            return Double.NaN;
        }
    }

    /** ModifiedGeometricMeasure(Rcutoff): as the original, with a beam measure that allows conical jets. */
    public static class ModifiedGeometricMeasure extends MeasureDefinition {
        protected final double rcutoff;
        protected final double rcutoffSq;

        public ModifiedGeometricMeasure(double rcutoff) {
            this.rcutoff = rcutoff;
            this.rcutoffSq = sq(rcutoff);
            if (rcutoff <= 0) throw new FastJetException("ModifiedGeometricMeasure:  You must choose Rcutoff > 0.");
            setTauMode(TauMode.UNNORMALIZED_EVENT_SHAPE);
            setAxisScaling(false);
        }

        @Override
        public String description() {
            return "Modified Geometric Measure (Rcut = " + f2(rcutoff) + ", in GeV)";
        }

        @Override
        public ModifiedGeometricMeasure create() {
            return new ModifiedGeometricMeasure(rcutoff);
        }

        @Override
        public double jetNumerator(PseudoJet particle, PseudoJet axis) {
            return PseudoJet.dotProduct(lightFrom(axis), particle) / rcutoffSq;
        }

        @Override
        public double beamNumerator(PseudoJet particle) {
            final PseudoJet lightParticle = lightFrom(particle);
            return 0.5 * particle.mperp() * lightParticle.pt();
        }

        @Override
        public double denominator(PseudoJet particle) {
            return Double.NaN;
        }
    }

    /** ConicalGeometricMeasure(beta, gamma, Rcutoff): the basis of XCone. */
    public static class ConicalGeometricMeasure extends MeasureDefinition {
        protected final double jetBeta;
        protected final double beamGamma;
        protected final double rcutoff;
        protected final double rcutoffSq;

        public ConicalGeometricMeasure(double jetBeta, double beamGamma, double rcutoff) {
            this.jetBeta = jetBeta;
            this.beamGamma = beamGamma;
            this.rcutoff = rcutoff;
            this.rcutoffSq = sq(rcutoff);
            if (jetBeta <= 0) throw new FastJetException("ConicalGeometricMeasure:  You must choose beta > 0.");
            if (beamGamma <= 0) throw new FastJetException("ConicalGeometricMeasure:  You must choose gamma > 0.");
            if (rcutoff <= 0) throw new FastJetException("ConicalGeometricMeasure:  You must choose Rcutoff > 0.");
            setTauMode(TauMode.UNNORMALIZED_EVENT_SHAPE);
        }

        @Override
        public String description() {
            return "Conical Geometric Measure (beta = " + f2(jetBeta) + ", gamma = " + f2(beamGamma) + ", Rcut = "
                + f2(rcutoff) + ", in GeV)";
        }

        @Override
        public ConicalGeometricMeasure create() {
            return new ConicalGeometricMeasure(jetBeta, beamGamma, rcutoff);
        }

        @Override
        public double jetDistanceSquared(PseudoJet particle, PseudoJet axis) {
            final PseudoJet lightAxis = lightFrom(axis);
            return 2.0 * PseudoJet.dotProduct(lightFrom(axis), particle) / (lightAxis.pt() * particle.pt());
        }

        @Override
        public double beamDistanceSquared(PseudoJet particle) {
            return rcutoffSq;
        }

        @Override
        public double jetNumerator(PseudoJet particle, PseudoJet axis) {
            final double jetDist = jetDistanceSquared(particle, axis) / rcutoffSq;
            if (jetDist > 0.0) {
                final PseudoJet lightParticle = lightFrom(particle);
                final double weight = beamGamma == 1.0 ? 1.0 : CRMath.pow(0.5 * lightParticle.pt(), beamGamma - 1.0);
                return particle.pt() * weight * CRMath.pow(jetDist, jetBeta / 2.0);
            }
            return 0.0;
        }

        @Override
        public double beamNumerator(PseudoJet particle) {
            final PseudoJet lightParticle = lightFrom(particle);
            final double weight = beamGamma == 1.0 ? 1.0 : CRMath.pow(0.5 * lightParticle.pt(), beamGamma - 1.0);
            return particle.pt() * weight;
        }

        @Override
        public double denominator(PseudoJet particle) {
            return Double.NaN;
        }
    }

    /** XConeMeasure(beta, R): the conical geometric measure with gamma = 1, XCone's. */
    public static class XConeMeasure extends ConicalGeometricMeasure {
        public XConeMeasure(double jetBeta, double r) {
            super(jetBeta, 1.0, r);
        }

        @Override
        public String description() {
            return "XCone Measure (beta = " + f2(jetBeta) + ", Rcut = " + f2(rcutoff) + ", in GeV)";
        }

        @Override
        public XConeMeasure create() {
            return new XConeMeasure(jetBeta, rcutoff);
        }
    }

    /** A light-like axis in (y, phi) with a weight and a momentum, for the minimisations. */
    public static final class LightLikeAxis {
        private double rap;
        private double phi;
        private double weight;
        private double mom;

        public LightLikeAxis() {
        }

        public LightLikeAxis(double rap, double phi, double weight, double mom) {
            this.rap = rap;
            this.phi = phi;
            this.weight = weight;
            this.mom = mom;
        }

        public LightLikeAxis copy() {
            return new LightLikeAxis(rap, phi, weight, mom);
        }

        public double rap() { return rap; }
        public double phi() { return phi; }
        public double weight() { return weight; }
        public double mom() { return mom; }
        public void setRap(double v) { rap = v; }
        public void setPhi(double v) { phi = v; }
        public void setWeight(double v) { weight = v; }
        public void setMom(double v) { mom = v; }

        public void reset(double rap, double phi, double weight, double mom) {
            this.rap = rap;
            this.phi = phi;
            this.weight = weight;
            this.mom = mom;
        }

        public PseudoJet convertToPseudoJet() {
            final double e = mom;
            final double e2r = CRMath.exp(2.0 * rap);
            final double pz = (e2r - 1.0) / (e2r + 1.0) * e;
            final double pt = Math.sqrt(e * e - pz * pz);
            return new PseudoJet(CRMath.cos(phi) * pt, CRMath.sin(phi) * pt, pz, e);
        }

        public double distanceSq(PseudoJet input) {
            return distanceSq(input.rap(), input.phi());
        }

        public double distance(PseudoJet input) {
            return Math.sqrt(distanceSq(input));
        }

        public double distanceSq(LightLikeAxis input) {
            return distanceSq(input.rap, input.phi);
        }

        public double distance(LightLikeAxis input) {
            return Math.sqrt(distanceSq(input));
        }

        private double distanceSq(double rap2, double phi2) {
            final double distRap = rap - rap2;
            double distPhi = Math.abs(phi - phi2);
            if (distPhi > Math.PI) distPhi = 2.0 * Math.PI - distPhi;
            return distRap * distRap + distPhi * distPhi;
        }
    }

    /** std::fixed << setprecision(2), as every description writes its parameters. */
    static String f2(double x) {
        return Fmt.f(x, 0, 2);
    }
}

package com.sphere.core.fjcontrib.constituentsubtractor;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.LimitedWarning;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.Selector;
import com.sphere.core.fastjet.tools.BackgroundEstimatorBase;
import com.sphere.core.fastjet.tools.GridMedianBackgroundEstimator;
import com.sphere.core.fastjet.tools.JetMedianBackgroundEstimator;
import com.sphere.core.fastjet.tools.Transformer;
import com.sphere.core.fjcontrib.ContribCitations;
import com.sphere.core.fjcontrib.internal.StdSort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Constituent subtraction, fastjet::contrib::ConstituentSubtractor
 * (ConstituentSubtractor 1.4.7; P. Berta, M. Spousta, D.W. Miller and
 * R. Leitner, JHEP 06 (2014) 092; P. Berta, L. Masetti, D.W. Miller and
 * M. Spousta, JHEP 08 (2019) 175): the background, carried by massless
 * ghosts of pt rho A, is taken away from the particles pair by pair, closest
 * pairs first, the distance being pt^alpha sin(theta)^polarAngleExp DeltaR
 * (or the three-dimensional angle). Jet by jet (on the explicit ghosts of an
 * area clustering) or on the whole event (on a uniform grid of ghosts up to
 * max_eta).
 *
 * Every pair within max_distance is kept and sorted, with std::sort's own
 * order for ties, so the subtracted particles are the C++'s bit for bit.
 */
public class ConstituentSubtractor implements Transformer {

    static {
        ContribCitations.use("cs");
    }

    /** The zero of pt and mass the corrected particles are compared with. */
    public static final double ZERO_PT = 1e-50;
    public static final double ZERO_MASS = 1e-50;

    /** How particle-ghost distances are measured. */
    public enum Distance {
        /** sqrt(Delta y^2 + Delta phi^2), longitudinally boost invariant. */
        deltaR,
        /** The angle between the three-momenta. */
        angle
    }

    private static final LimitedWarning WARNING_UNUSED_RHOM = new LimitedWarning();

    protected BackgroundEstimatorBase bgeRho;
    protected BackgroundEstimatorBase bgeRhom;
    protected boolean commonBge;
    protected double rho;
    protected double rhom;
    protected boolean externallySuppliedRhoRhom;
    protected boolean doMassSubtraction;
    protected boolean massesToZero = true;
    protected boolean fixPseudorapidity;
    protected boolean scaleFourmomentum;
    protected boolean removeParticlesWithZeroPtAndMass = true;
    protected boolean removeAllZeroPtParticles;
    protected double alpha;
    protected Distance distance = Distance.deltaR;
    protected double maxDistance = -1;
    protected boolean useMaxDistance;
    protected double polarAngleExp;
    protected double ghostArea = 0.01;
    protected double gridSizePhi = -1;
    protected double gridSizeRap = -1;
    protected boolean ghostsConstructed;
    protected boolean ghostsRapiditySorted;
    protected int nGhostsPhi = -1;
    protected double maxEta = -1;
    protected boolean useNearbyHard;
    protected double nearbyHardRadius = -1;
    protected double nearbyHardFactor = -1;
    protected List<PseudoJet> hardProxies;
    protected List<PseudoJet> ghosts = new ArrayList<>();
    protected List<Double> ghostsArea = new ArrayList<>();
    protected List<Double> ghostsRapidities = new ArrayList<>();
    protected double gridSizeBackgroundEstimator = 0.5;
    protected Selector ghostSelector;
    protected Selector particleSelector;
    protected FunctionOfPseudoJet<Double> rescaling;

    public ConstituentSubtractor() {
    }

    /** With an estimator for rho and, optionally (null), one for rho_m. */
    public ConstituentSubtractor(BackgroundEstimatorBase bgeRho, BackgroundEstimatorBase bgeRhom, double alpha,
                                 double maxDistance, Distance distance) {
        this.bgeRho = bgeRho;
        this.bgeRhom = bgeRhom;
        this.alpha = alpha;
        this.distance = distance;
        this.maxDistance = maxDistance;
        this.useMaxDistance = maxDistance > 0;
    }

    public ConstituentSubtractor(BackgroundEstimatorBase bgeRho) {
        this(bgeRho, null, 0, -1, Distance.deltaR);
    }

    public ConstituentSubtractor(BackgroundEstimatorBase bgeRho, BackgroundEstimatorBase bgeRhom) {
        this(bgeRho, bgeRhom, 0, -1, Distance.deltaR);
    }

    /** With rho and rho_m given. */
    public ConstituentSubtractor(double rho, double rhom, double alpha, double maxDistance, Distance distance) {
        if (!(rho >= 0) || !(rhom >= 0)) throw new FastJetException("ConstituentSubtractor: rho and rho_m must be >= 0");
        this.rho = rho;
        this.rhom = rhom;
        this.externallySuppliedRhoRhom = true;
        this.alpha = alpha;
        this.distance = distance;
        this.maxDistance = maxDistance;
        this.useMaxDistance = maxDistance > 0;
    }

    public ConstituentSubtractor(double rho, double rhom) {
        this(rho, rhom, 0, -1, Distance.deltaR);
    }

    public ConstituentSubtractor(double rho) {
        this(rho, 0, 0, -1, Distance.deltaR);
    }

    /* ------------------------------------------------------------------ */
    /* Set-up                                                              */
    /* ------------------------------------------------------------------ */

    protected void initializeCommon() {
        if (maxEta <= 0) throw new FastJetException("ConstituentSubtractor::initialize_common: The value for eta cut was not set or it is negative. It needs to be set before using the function initialize");
        if (massesToZero && doMassSubtraction) throw new FastJetException("ConstituentSubtractor::initialize_common: It is specified to do mass subtraction and also to keep the masses at zero. Something is wrong.");
        if (massesToZero && scaleFourmomentum) throw new FastJetException("ConstituentSubtractor::initialize_common: It is specified to do scaling of fourmomenta and also to keep the masses at zero. Something is wrong.");
        if (doMassSubtraction && scaleFourmomentum) throw new FastJetException("ConstituentSubtractor::initialize_common: It is specified to do mass subtraction and also to do scaling of fourmomenta. Something is wrong.");
        constructGhostsUniformly(maxEta);
    }

    /** To call once the parameters are set, before the event loop (event-wide subtraction). */
    public void initialize() {
        initializeCommon();
    }

    public void setBackgroundEstimator(BackgroundEstimatorBase bgeRho, BackgroundEstimatorBase bgeRhom) {
        this.bgeRho = bgeRho;
        this.bgeRhom = bgeRhom;
    }

    public void setBackgroundEstimator(BackgroundEstimatorBase bgeRho) {
        setBackgroundEstimator(bgeRho, null);
    }

    public void setScalarBackgroundDensity(double rho, double rhom) {
        if (!(rho >= 0) || !(rhom >= 0)) throw new FastJetException("ConstituentSubtractor: rho and rho_m must be >= 0");
        this.rho = rho;
        this.rhom = rhom;
        externallySuppliedRhoRhom = true;
        commonBge = false;
    }

    public void setScalarBackgroundDensity(double rho) {
        setScalarBackgroundDensity(rho, 0);
    }

    /** rho_m from the same estimator as rho (a JetMedianBackgroundEstimator, or one with rho_m). */
    public void setCommonBgeForRhoAndRhom() {
        if (bgeRho == null) throw new FastJetException("ConstituentSubtractor::set_common_bge_for_rho_and_rhom() is not allowed when _bge_rho is not set!");
        if (bgeRhom != null) throw new FastJetException("ConstituentSubtractor::set_common_bge_for_rho_and_rhom() is not allowed in the presence of an existing background estimator for rho_m.");
        if (externallySuppliedRhoRhom) throw new FastJetException("ConstituentSubtractor::set_common_bge_for_rho_and_rhom() is not allowed when supplying externally the values for rho and rho_m.");
        if (!bgeRho.hasRhoM() && !(bgeRho instanceof JetMedianBackgroundEstimator)) {
            throw new FastJetException("ConstituentSubtractor::set_common_bge_for_rho_and_rhom() is currently only allowed for background estimators of JetMedianBackgroundEstimator type.");
        }
        commonBge = true;
    }

    public void setCommonBgeForRhoAndRhom(boolean value) {
        if (value) setCommonBgeForRhoAndRhom();
        else throw new FastJetException("ConstituentSubtractor::set_common_bge_for_rho_and_rhom: This function should be not used with false! Read the instructions for mass subtraction in the header file.");
    }

    public void setKeepOriginalMasses() { massesToZero = false; }

    public void setDoMassSubtraction() {
        doMassSubtraction = true;
        massesToZero = false;
    }

    public void setRemoveParticlesWithZeroPtAndMass(boolean value) { removeParticlesWithZeroPtAndMass = value; }
    public void setRemoveAllZeroPtParticles(boolean value) { removeAllZeroPtParticles = value; }
    public void setAlpha(double value) { alpha = value; }
    public void setPolarAngleExp(double value) { polarAngleExp = value; }

    public void setGhostArea(double value) {
        ghostArea = value;
        clearGhosts();
    }

    public void setDistanceType(Distance d) { distance = d; }

    /** Pairs farther than this are not used; <= 0: no limit. */
    public void setMaxDistance(double value) {
        if (value > 0) {
            useMaxDistance = true;
            maxDistance = value;
        } else {
            useMaxDistance = false;
        }
    }

    public void setMaxStandardDeltaR(double value) { setMaxDistance(value); }
    public double getMaxDistance() { return maxDistance; }
    public void setMaxEta(double value) { maxEta = value; }
    public void setFixPseudorapidity() { fixPseudorapidity = true; }

    public void setScaleFourmomentum() {
        scaleFourmomentum = true;
        massesToZero = false;
    }

    public void setGhostSelector(Selector selector) {
        ghostSelector = selector;
        clearGhosts();
    }

    public void setParticleSelector(Selector selector) { particleSelector = selector; }
    public void setRescaling(FunctionOfPseudoJet<Double> r) { rescaling = r; }
    public void setGridSizeBackgroundEstimator(double value) { gridSizeBackgroundEstimator = value; }

    /** Distances within nearby_hard_radius of a hard proxy are multiplied by nearby_hard_factor. */
    public void setUseNearbyHard(double radius, double factor) {
        nearbyHardRadius = radius;
        nearbyHardFactor = factor;
        useNearbyHard = radius > 0;
    }

    public List<PseudoJet> getGhosts() { return new ArrayList<>(ghosts); }

    public List<Double> getGhostsArea() { return new ArrayList<>(ghostsArea); }

    /* ------------------------------------------------------------------ */
    /* Jet by jet                                                          */
    /* ------------------------------------------------------------------ */

    @Override
    public PseudoJet result(PseudoJet jet) {
        if (bgeRho == null && !externallySuppliedRhoRhom) {
            throw new FastJetException("ConstituentSubtractor::result() constituent subtraction needs a BackgroundEstimator or a value for rho.");
        }
        if (ghostsConstructed) throw new FastJetException("ConstituentSubtractor::result() The ghosts are constructed, but they are not needed when using this function. When you want to perform jet-by-jet correction, initialize a new ConstituentSubtractor without construction of ghosts.");
        final List<PseudoJet> particles = new ArrayList<>();
        final List<PseudoJet> jetGhosts = new ArrayList<>();
        Selector.isPureGhost().sift(jet.constituents(), jetGhosts, particles);
        List<PseudoJet> selected = particles;
        final List<PseudoJet> unselected = new ArrayList<>();
        if (particleSelector != null) {
            selected = new ArrayList<>();
            particleSelector.sift(particles, selected, unselected);
        }
        final List<Double> area = new ArrayList<>(jetGhosts.size());
        for (PseudoJet g : jetGhosts) area.add(g.area());
        final List<PseudoJet> proxies = getBackgroundProxiesFromGhosts(jetGhosts, area);
        final List<PseudoJet> subtracted = doSubtraction(selected, proxies, null);
        if (particleSelector != null) subtracted.addAll(unselected);
        final PseudoJet out = PseudoJet.join(subtracted);
        out.setUserIndex(jet.userIndex());
        out.setUserInfo(jet.userInfo());
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* The whole event                                                     */
    /* ------------------------------------------------------------------ */

    /** The older form, which (re)builds the ghosts up to max_eta. */
    public List<PseudoJet> subtractEvent(List<PseudoJet> particles, double maxEtaIn) {
        if (Math.abs(maxEta / maxEtaIn - 1) > 1e-5 && maxEtaIn > 0) {
            ghostsConstructed = false;
            maxEta = maxEtaIn;
        }
        if (!ghostsConstructed) constructGhostsUniformly(maxEta);
        return subtractEvent(particles, (List<PseudoJet>) null);
    }

    public List<PseudoJet> subtractEvent(List<PseudoJet> particles) {
        return subtractEvent(particles, (List<PseudoJet>) null);
    }

    /** The event subtracted on the ghost grid; particles beyond max_eta are dropped. */
    public List<PseudoJet> subtractEvent(List<PseudoJet> particles, List<PseudoJet> hardProxiesIn) {
        final List<PseudoJet> proxies = getBackgroundProxiesFromGhosts(ghosts, ghostsArea);
        final List<PseudoJet> selected = new ArrayList<>();
        final List<PseudoJet> unselected = new ArrayList<>();
        for (PseudoJet p : particles) {
            if (Math.abs(p.eta()) > maxEta) continue;
            if (p.pt() < ZERO_PT && removeAllZeroPtParticles) continue;
            if (p.pt() < ZERO_PT && (massesToZero || p.m() < ZERO_MASS) && removeParticlesWithZeroPtAndMass) continue;
            if (particleSelector != null) {
                if (particleSelector.pass(p)) selected.add(p); else unselected.add(p);
            } else {
                selected.add(p);
            }
        }
        if (useNearbyHard) {
            if (hardProxiesIn != null) hardProxies = hardProxiesIn;
            else throw new FastJetException("ConstituentSubtractor::subtract_event: It was requested to use closeby hard proxies but they were not provided in this function!");
        } else if (hardProxiesIn != null) {
            throw new FastJetException("ConstituentSubtractor::subtract_event: Hard proxies were provided but the set_use_hard_proxies function was not used before initialization. It needs to be called before initialization!");
        }
        final List<PseudoJet> subtracted = doSubtraction(selected, proxies, null);
        if (particleSelector != null) subtracted.addAll(unselected);
        return subtracted;
    }

    /**
     * The event subtracted with the help of the charged tracks: the signal
     * and pileup tracks (scaled by CSS and CBS) subtracted first, then a
     * grid-median rho from what remains of the neutral part.
     */
    public List<PseudoJet> subtractEventUsingChargedInfo(List<PseudoJet> particles, double chargedBackgroundScale,
                                                         List<PseudoJet> chargedBackground, double chargedSignalScale,
                                                         List<PseudoJet> chargedSignal, double maxEtaIn) {
        if (Math.abs(maxEta / maxEtaIn - 1) > 1e-5) ghostsConstructed = false;
        if (!ghostsConstructed) constructGhostsUniformly(maxEtaIn);
        ghostsRapiditySorted = false;
        final List<PseudoJet> scaledSignal = new ArrayList<>();
        final List<PseudoJet> scaledBackground = new ArrayList<>();
        for (PseudoJet p : chargedBackground) {
            if (Math.abs(p.eta()) > maxEtaIn) continue;
            scaledBackground.add(p.times(chargedBackgroundScale));
        }
        for (PseudoJet p : chargedSignal) {
            if (Math.abs(p.eta()) > maxEtaIn) continue;
            scaledSignal.add(p.times(chargedSignalScale));
        }
        final List<PseudoJet> selected = new ArrayList<>();
        for (PseudoJet p : particles) if (Math.abs(p.eta()) < maxEtaIn) selected.add(p);
        final List<PseudoJet> remainingChargedBackground = new ArrayList<>();
        double maxDeltaR = getMaxDistance();
        if (maxDeltaR <= 0) maxDeltaR = 0.5;
        setMaxDistance(0.2);
        final List<PseudoJet> afterSignal = doSubtraction(selected, scaledSignal, null);
        final List<PseudoJet> afterAll = doSubtraction(afterSignal, scaledBackground, remainingChargedBackground);
        final List<PseudoJet> backgroundUsed = doSubtraction(scaledBackground, remainingChargedBackground, null);
        final BackgroundEstimatorBase savedRho = bgeRho;
        final boolean savedCommon = commonBge;
        bgeRho = new GridMedianBackgroundEstimator(maxEtaIn, gridSizeBackgroundEstimator);
        if (doMassSubtraction) setCommonBgeForRhoAndRhom();
        bgeRho.setRescalingClass(rescaling);
        bgeRho.setParticles(afterAll);
        final List<PseudoJet> proxies = getBackgroundProxiesFromGhosts(ghosts, ghostsArea);
        proxies.addAll(backgroundUsed);
        setMaxDistance(maxDeltaR);
        final List<PseudoJet> subtracted = doSubtraction(selected, proxies, null);
        // the C++ deletes its estimator here; the one given before is put back
        bgeRho = savedRho;
        commonBge = savedCommon;
        return subtracted;
    }

    /* ------------------------------------------------------------------ */
    /* The ghosts and the proxies                                          */
    /* ------------------------------------------------------------------ */

    protected void clearGhosts() {
        ghosts = new ArrayList<>();
        ghostsRapidities = new ArrayList<>();
        ghostsArea = new ArrayList<>();
        ghostsRapiditySorted = false;
        ghostsConstructed = false;
    }

    /** Massless ghosts on a uniform (y, phi) grid up to max_eta. */
    public void constructGhostsUniformly(double maxEtaIn) {
        clearGhosts();
        maxEta = maxEtaIn;
        final double a = Math.sqrt(ghostArea);
        nGhostsPhi = (int) (2 * 3.14159265 / a + 0.5);
        final int nGhostsRap = (int) (2 * maxEtaIn / a + 0.5);
        gridSizePhi = 2 * 3.14159265 / (double) nGhostsPhi;
        gridSizeRap = 2 * maxEtaIn / (double) nGhostsRap;
        final double usedGhostArea = gridSizePhi * gridSizeRap;
        for (int iRap = 0; iRap < nGhostsRap; ++iRap) {
            final double rapidity = gridSizeRap * (iRap + 0.5) - maxEtaIn;
            ghostsRapidities.add(rapidity);
            for (int iPhi = 0; iPhi < nGhostsPhi; ++iPhi) {
                final PseudoJet ghost = new PseudoJet(0, 0, 0, 1);
                ghost.resetMomentumPtYPhiM(1, rapidity, gridSizePhi * (iPhi + 0.5), 1e-200);
                if (ghostSelector != null && !ghostSelector.pass(ghost)) continue;
                ghosts.add(ghost);
                ghostsArea.add(usedGhostArea);
            }
        }
        ghostsRapiditySorted = true;
        ghostsConstructed = true;
    }

    /** Each ghost becomes a proxy of pt rho A and mass from rho_m A. */
    protected List<PseudoJet> getBackgroundProxiesFromGhosts(List<PseudoJet> ghostsIn, List<Double> area) {
        final int n = ghostsIn.size();
        final double[] pt = new double[n];
        final double[] mtMinusPt = new double[n];
        if (externallySuppliedRhoRhom) {
            for (int j = 0; j < n; ++j) {
                pt[j] = rho * area.get(j);
                mtMinusPt[j] = rhom * area.get(j);
            }
        } else {
            for (int j = 0; j < n; ++j) pt[j] = bgeRho.rho(ghostsIn.get(j)) * area.get(j);
            if (bgeRhom != null) {
                if (!bgeRhom.hasRhoM()) {
                    throw new FastJetException("ConstituentSubtractor: The provided background estimator for rho_m has no support to compute rho_m, and other option to get it is not available in ConstituentSubtractor.");
                }
                for (int j = 0; j < n; ++j) mtMinusPt[j] = bgeRhom.rhoM(ghostsIn.get(j)) * area.get(j);
            } else if (commonBge) {
                if (bgeRho.hasRhoM()) {
                    for (int j = 0; j < n; ++j) mtMinusPt[j] = bgeRho.rhoM(ghostsIn.get(j)) * area.get(j);
                } else {
                    final JetMedianBackgroundEstimator jmbge = (JetMedianBackgroundEstimator) bgeRho;
                    final FunctionOfPseudoJet<Double> original = jmbge.jetDensityClass();
                    jmbge.setJetDensityClass(ptmDensity());
                    for (int j = 0; j < n; ++j) mtMinusPt[j] = jmbge.rho(ghostsIn.get(j)) * area.get(j);
                    jmbge.setJetDensityClass(original);
                }
            } else {
                Arrays.fill(mtMinusPt, 1e-200);
                final double threshold = 1e-5;
                if (bgeRho.hasRhoM() && bgeRho.rhoM() > threshold * bgeRho.rho() && !massesToZero && !scaleFourmomentum) {
                    WARNING_UNUSED_RHOM.warn("ConstituentSubtractor:: Background estimator indicates non-zero rho_m, but the ConstituentSubtractor does not use rho_m information, nor the masses are set to zero, nor the 4-momentum is scaled. Consider calling set_common_bge_for_rho_and_rhom() to include the rho_m information; or call set_keep_original_masses(false) to set masses for all particles to zero; or call set_scale_fourmomentum to scale the fourmomentum.");
                }
            }
        }
        final List<PseudoJet> proxies = new ArrayList<>(n);
        for (int j = 0; j < n; ++j) {
            final double massSquared = CRMath.pow(mtMinusPt[j] + pt[j], 2) - CRMath.pow(pt[j], 2);
            final double mass = massSquared > 0 ? Math.sqrt(massSquared) : 0;
            final PseudoJet proxy = new PseudoJet(0, 0, 0, 1);
            proxy.resetMomentumPtYPhiM(pt[j], ghostsIn.get(j).rap(), ghostsIn.get(j).phi(), mass);
            proxies.add(proxy);
        }
        return proxies;
    }

    /** fastjet::BackgroundJetPtMDensity: the scalar sum of mt - pt over the constituents, per unit area. */
    public static FunctionOfPseudoJet<Double> ptmDensity() {
        return FunctionOfPseudoJet.of("BackgroundPtMDensity", jet -> {
            double scalarPtm = 0;
            for (PseudoJet c : jet.constituents()) scalarPtm += c.mperp() - c.perp();
            return scalarPtm / jet.area();
        });
    }

    protected double getTransformedDistance(double d) {
        double out = -1;
        if (distance == Distance.deltaR) out = CRMath.pow(d, 2);
        if (distance == Distance.angle) out = -CRMath.cos(d);
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* The subtraction                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * The particles corrected with these background proxies; with a list
     * given, what is left of each proxy is put in it.
     */
    public List<PseudoJet> doSubtraction(List<PseudoJet> particles, List<PseudoJet> backgroundProxies,
                                         List<PseudoJet> remainingBackgroundProxies) {
        final int nProxies = backgroundProxies.size();
        final int nParticles = particles.size();
        final double maxDistanceTransformed = getTransformedDistance(maxDistance);
        final PseudoJet[] sorted = particles.toArray(new PseudoJet[0]);
        StdSort.sort(sorted, 0, sorted.length, (a, b) -> a.rap() < b.rap() ? -1 : 0);

        final double[] pPhi = new double[nParticles];
        final double[] pRap = new double[nParticles];
        final double[] pPt = new double[nParticles];
        final double[] pMt = new double[nParticles];
        final double[] pFactor = new double[nParticles];
        final double[] pPxN = new double[nParticles];
        final double[] pPyN = new double[nParticles];
        final double[] pPzN = new double[nParticles];
        double ptFactor = 1;
        double polarAngleFactor = 1;
        double nearbyFactor = 1;
        final double maxDistanceFromHardTransformed = getTransformedDistance(nearbyHardRadius);
        for (int i = 0; i < nParticles; ++i) {
            final PseudoJet p = sorted[i];
            pPhi[i] = p.phi();
            pRap[i] = p.rap();
            pPt[i] = p.pt();
            pMt[i] = p.mt();
            if (Math.abs(alpha) > 1e-5) ptFactor = CRMath.pow(pPt[i], alpha);
            final double momentum = Math.sqrt(p.pt2() + p.pz() * p.pz());
            if (Math.abs(polarAngleExp) > 1e-5) polarAngleFactor = CRMath.pow(pPt[i] / momentum, polarAngleExp);
            if (distance == Distance.angle) {
                pPxN[i] = p.px() / momentum;
                pPyN[i] = p.py() / momentum;
                pPzN[i] = p.pz() / momentum;
            }
            if (useNearbyHard) {
                nearbyFactor = 1;
                double distanceFromHard = -1;
                for (PseudoJet h : hardProxies) {
                    if (distance == Distance.deltaR) {
                        double dPhi = Math.abs(h.phi() - pPhi[i]);
                        if (dPhi > Math.PI) dPhi = 2 * Math.PI - dPhi;
                        final double dRap = h.rap() - pRap[i];
                        distanceFromHard = dPhi * dPhi + dRap * dRap;
                    }
                    if (distance == Distance.angle) {
                        distanceFromHard = -(pPxN[i] * h.px() + pPyN[i] * h.py() + pPzN[i] * h.pz())
                            / Math.sqrt(h.pt2() * h.pt2() + h.pz() * h.pz());
                    }
                    if (distanceFromHard <= maxDistanceFromHardTransformed) {
                        nearbyFactor = nearbyHardFactor;
                        break;
                    }
                }
            }
            pFactor[i] = ptFactor * polarAngleFactor * nearbyFactor;
        }

        final double[] bPhi = new double[nProxies];
        final double[] bRap = new double[nProxies];
        final double[] bPt = new double[nProxies];
        final double[] bMt = new double[nProxies];
        final double[] bPxN = new double[nProxies];
        final double[] bPyN = new double[nProxies];
        final double[] bPzN = new double[nProxies];
        for (int j = 0; j < nProxies; ++j) {
            final PseudoJet b = backgroundProxies.get(j);
            bPhi[j] = b.phi();
            bRap[j] = b.rap();
            bPt[j] = b.pt();
            bMt[j] = b.mt();
            if (distance == Distance.angle) {
                final double momentum = Math.sqrt(b.pt2() + b.pz() * b.pz());
                bPxN[j] = b.px() / momentum;
                bPyN[j] = b.py() / momentum;
                bPzN[j] = b.pz() / momentum;
            }
        }

        final int[] minIndex = new int[nProxies];
        final int[] maxIndex = new int[nProxies];
        if (useMaxDistance && distance == Distance.deltaR && ghostsRapiditySorted && ghostSelector == null) {
            int k = 0;
            for (double gr : ghostsRapidities) {
                final int min = findIndexAfter(gr - maxDistance, pRap);
                final int max = findIndexBefore(gr + maxDistance, pRap);
                for (int iphi = 0; iphi < nGhostsPhi && k < nProxies; ++iphi, ++k) {
                    minIndex[k] = min;
                    maxIndex[k] = max;
                }
            }
        } else {
            Arrays.fill(maxIndex, nParticles);
        }

        // the pairs within the distance, their CS distance and indices packed in a long
        PairList pairs = new PairList(Math.max(16, Math.min(1 << 24, nParticles)));
        final boolean skipOutsidePhi = distance == Distance.deltaR && useMaxDistance && maxDistance < 2 * Math.PI / 2. * 0.9999
            && ghostsConstructed && ghostSelector == null;
        double distanceTransformed = 0;
        for (int j = 0; j < nProxies; ++j) {
            boolean switched = false;
            double phiMax = 0;
            double phiMin = 0;
            if (skipOutsidePhi) {
                phiMax = bPhi[j] + maxDistance;
                phiMin = bPhi[j] - maxDistance;
                if (phiMax > 2 * Math.PI) {
                    phiMin = phiMax - 2 * Math.PI;
                    phiMax = bPhi[j] - maxDistance;
                    switched = true;
                }
                if (phiMin < 0) {
                    phiMax = phiMin + 2 * Math.PI;
                    phiMin = bPhi[j] + maxDistance;
                    switched = true;
                }
            }
            for (int i = minIndex[j]; i < maxIndex[j]; ++i) {
                if (distance == Distance.deltaR) {
                    if (skipOutsidePhi && ((switched && pPhi[i] > phiMin && pPhi[i] < phiMax)
                        || (!switched && (pPhi[i] < phiMin || pPhi[i] > phiMax)))) continue;
                    double dPhi = Math.abs(bPhi[j] - pPhi[i]);
                    if (dPhi > Math.PI) dPhi = 2 * Math.PI - dPhi;
                    final double dRap = bRap[j] - pRap[i];
                    distanceTransformed = dPhi * dPhi + dRap * dRap;
                }
                if (distance == Distance.angle) {
                    distanceTransformed = -(pPxN[i] * bPxN[j] + pPyN[i] * bPyN[j] + pPzN[i] * bPzN[j]);
                }
                if (!useMaxDistance || distanceTransformed <= maxDistanceTransformed) {
                    pairs.add(distanceTransformed * pFactor[i], ((long) i << 32) | (j & 0xffffffffL));
                }
            }
        }
        StdSort.sortByKey(pairs.key, pairs.payload, pairs.n);

        final double[] bFracPt = new double[nProxies];
        final double[] pFracPt = new double[nParticles];
        final double[] bFracMt = new double[nProxies];
        final double[] pFracMt = new double[nParticles];
        Arrays.fill(bFracPt, 1.);
        Arrays.fill(pFracPt, 1.);
        Arrays.fill(bFracMt, 1.);
        Arrays.fill(pFracMt, 1.);
        for (int q = 0; q < pairs.n; ++q) {
            final int pi = (int) (pairs.payload[q] >>> 32);
            final int bi = (int) pairs.payload[q];
            if (bFracPt[bi] > 0 && pFracPt[pi] > 0 && pPt[pi] > 0 && backgroundProxies.get(bi).pt() > 0) {
                final double ratioPt = pPt[pi] * pFracPt[pi] / bPt[bi] / bFracPt[bi];
                if (ratioPt > 1) {
                    pFracPt[pi] *= 1 - 1. / ratioPt;
                    bFracPt[bi] = -1;
                } else {
                    bFracPt[bi] *= 1 - ratioPt;
                    pFracPt[pi] = -1;
                }
            }
            if (doMassSubtraction && bFracMt[bi] > 0 && pFracMt[pi] > 0 && pMt[pi] > pPt[pi] && bMt[bi] > bPt[bi]) {
                final double ratio = (pMt[pi] - pPt[pi]) * pFracMt[pi] / (bMt[bi] - bPt[bi]) / bFracMt[bi];
                if (ratio > 1) {
                    pFracMt[pi] *= 1 - 1. / ratio;
                    bFracMt[bi] = -1;
                } else {
                    bFracMt[bi] *= 1 - ratio;
                    pFracMt[pi] = -1;
                }
            }
        }
        pairs = null;

        final List<PseudoJet> out = new ArrayList<>();
        for (int i = 0; i < nParticles; ++i) {
            final PseudoJet p = sorted[i];
            boolean ptLargerThanZero = true;
            double correctedPt = ZERO_PT;
            if (pFracPt[i] > 0) correctedPt = pPt[i] * pFracPt[i];
            if (correctedPt <= ZERO_PT) {
                if (removeAllZeroPtParticles) continue;
                correctedPt = ZERO_PT;
                ptLargerThanZero = false;
            }
            PseudoJet sub;
            if (scaleFourmomentum) {
                if (ptLargerThanZero) {
                    sub = p.times(pFracPt[i]);
                } else {
                    double scale = 1;
                    if (correctedPt < pPt[i]) scale = correctedPt / pPt[i];
                    sub = p.times(scale);
                }
                if (sub.m() <= ZERO_MASS && !ptLargerThanZero && removeParticlesWithZeroPtAndMass) continue;
            } else {
                final boolean massLargerThanZero = !massesToZero && p.m() > ZERO_MASS;
                if (!ptLargerThanZero && !massLargerThanZero && removeParticlesWithZeroPtAndMass) continue;
                double newMass = ZERO_MASS;
                if (doMassSubtraction) {
                    if (pFracMt[i] > 0) {
                        final double subMtMinusPt = (pMt[i] - pPt[i]) * pFracMt[i];
                        final double massSquared = CRMath.pow(correctedPt + subMtMinusPt, 2) - CRMath.pow(correctedPt, 2);
                        if (massSquared > 0) newMass = Math.sqrt(massSquared);
                    }
                } else if (!massesToZero) {
                    newMass = p.m();
                }
                if (newMass <= ZERO_MASS) {
                    if (!ptLargerThanZero && removeParticlesWithZeroPtAndMass) continue;
                    newMass = ZERO_MASS;
                }
                sub = new PseudoJet();
                if (fixPseudorapidity) {
                    final double scale = correctedPt / pPt[i];
                    sub.reset(p.px() * scale, p.py() * scale, p.pz() * scale,
                        Math.sqrt(CRMath.pow(correctedPt, 2) + CRMath.pow(scale, 2) * CRMath.pow(p.pz(), 2) + CRMath.pow(newMass, 2)));
                } else {
                    sub.resetPtYPhiM(correctedPt, pRap[i], pPhi[i], newMass);
                }
            }
            sub.setUserIndex(p.userIndex());
            sub.setUserInfo(p.userInfo());
            out.add(sub);
        }

        if (remainingBackgroundProxies != null) {
            for (int i = 0; i < nProxies; ++i) {
                final PseudoJet b = backgroundProxies.get(i);
                final boolean ptLargerThanZero = bFracPt[i] > 0 && bPt[i] > 0;
                final boolean mtLargerThanZero = !massesToZero && bFracMt[i] > 0 && bMt[i] > bPt[i];
                double scale = 1e-100;
                if (ptLargerThanZero) scale = bFracPt[i];
                PseudoJet sub;
                if (scaleFourmomentum) {
                    sub = b.times(scale);
                } else {
                    double newMass = 1e-150;
                    if (mtLargerThanZero) {
                        if (doMassSubtraction) {
                            final double subMtMinusPt = (bMt[i] - bPt[i]) * bFracMt[i];
                            final double massSquared = CRMath.pow(scale * bPt[i] + subMtMinusPt, 2) - CRMath.pow(scale * bPt[i], 2);
                            if (massSquared > 0) newMass = Math.sqrt(massSquared);
                        } else {
                            newMass = b.m();
                        }
                    }
                    sub = new PseudoJet();
                    if (fixPseudorapidity) {
                        sub.reset(b.px() * scale, b.py() * scale, b.pz() * scale,
                            Math.sqrt(CRMath.pow(scale, 2) * (b.pt2() + CRMath.pow(b.pz(), 2)) + CRMath.pow(newMass, 2)));
                    } else {
                        sub.resetPtYPhiM(scale * bPt[i], bRap[i], bPhi[i], newMass);
                    }
                }
                remainingBackgroundProxies.add(sub);
            }
        }
        return out;
    }

    /** A growing list of (key, payload), the CS_distances vector. */
    private static final class PairList {
        double[] key;
        long[] payload;
        int n;

        PairList(int capacity) {
            key = new double[capacity];
            payload = new long[capacity];
        }

        void add(double k, long p) {
            if (n == key.length) {
                final int cap = (int) Math.min(Integer.MAX_VALUE - 8, 2L * key.length);
                key = Arrays.copyOf(key, cap);
                payload = Arrays.copyOf(payload, cap);
            }
            key[n] = k;
            payload[n] = p;
            n++;
        }
    }

    /** The first index whose value is >= value (binary search as the C++ does it). */
    protected int findIndexAfter(double value, double[] vec) {
        final int size = vec.length;
        if (size == 0) return 0;
        final int nIterations = (int) (CRMath.log(size) / CRMath.log(2) + 2);
        int lowerBound = 0;
        int upperBound = size - 1;
        if (value <= vec[0]) return 0;
        if (value > vec[size - 1]) return size;
        for (int i = 0; i < nIterations; ++i) {
            final int test = (upperBound + lowerBound) / 2;
            if (value > vec[test]) {
                if (value <= vec[test + 1]) return test + 1;
                lowerBound = test;
            } else {
                if (value > vec[test - 1]) return test;
                upperBound = test;
            }
        }
        return lowerBound;
    }

    /** One past the last index whose value is <= value. */
    protected int findIndexBefore(double value, double[] vec) {
        final int size = vec.length;
        if (size == 0) return 0;
        final int nIterations = (int) (CRMath.log(size) / CRMath.log(2) + 1);
        int lowerBound = 0;
        int upperBound = size - 1;
        if (value < vec[0]) return 0;
        if (value >= vec[size - 1]) return size;
        for (int i = 0; i < nIterations; ++i) {
            final int test = (upperBound + lowerBound) / 2;
            if (value >= vec[test]) {
                if (value < vec[test + 1]) return test + 1;
                lowerBound = test;
            } else {
                if (value >= vec[test - 1]) return test;
                upperBound = test;
            }
        }
        return upperBound + 1;
    }

    /* ------------------------------------------------------------------ */
    /* Description                                                         */
    /* ------------------------------------------------------------------ */

    protected void descriptionCommon(StringBuilder d) {
        if (externallySuppliedRhoRhom) {
            d.append("       Using externally supplied rho = ").append(Fmt.g(rho)).append(" and rho_m = ").append(Fmt.g(rhom)).append('\n');
        } else if (bgeRhom != null && bgeRho != null) {
            d.append("       Using rho estimation: ").append(bgeRho.description()).append('\n');
            d.append("       Using rho_m estimation: ").append(bgeRhom.description()).append('\n');
        } else if (bgeRho != null) {
            d.append("       Using rho estimation: ").append(bgeRho.description()).append('\n');
        } else {
            d.append("       No externally supplied rho, nor background estimator").append('\n');
        }
        if (doMassSubtraction) {
            d.append("       The mass part (delta_m) will be also corrected.").append('\n');
            d.append(commonBge ? "       using the same background estimator for rho_m as for rho"
                               : "       using different background estimator for rho_m as for rho").append('\n');
        } else if (massesToZero) {
            d.append("       The masses of all particles will be set to zero.").append('\n');
        } else if (scaleFourmomentum) {
            d.append("       The masses will be corrected by scaling the whole 4-momentum.").append('\n');
        } else {
            d.append("       The original mass of the particles will be kept.").append('\n');
        }
        if (!scaleFourmomentum) {
            d.append(fixPseudorapidity ? "       The pseudo-rapidity of the particles will be kept unchanged (not rapidity)."
                                       : "       The rapidity of the particles will be kept unchanged (not pseudo-rapidity).").append('\n');
        }
        if (useNearbyHard) {
            d.append("       Using information about nearby hard proxies with parameters _nearby_hard_radius=")
                .append(Fmt.g(nearbyHardRadius)).append(" and _nearby_hard_factor=").append(Fmt.g(nearbyHardFactor)).append('\n');
        } else {
            d.append("       The information about nearby hard proxies will not be used.").append('\n');
        }
    }

    @Override
    public String description() {
        final StringBuilder d = new StringBuilder("\nDescription of fastjet::ConstituentSubtractor which can be used for event-wide or jet-by-jet correction:\n");
        descriptionCommon(d);
        d.append("       Using parameters: max_distance = ").append(Fmt.g(maxDistance)).append("   alpha = ").append(Fmt.g(alpha)).append('\n');
        return d.toString();
    }
}

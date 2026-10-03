package com.sphere.core.fjcontrib.constituentsubtractor;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.Fmt;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;

/**
 * Iterative constituent subtraction, IterativeConstituentSubtractor
 * (P. Berta, L. Masetti, D.W. Miller and M. Spousta, JHEP 08 (2019) 175):
 * the event-wide subtraction done several times with growing max_distance;
 * after each pass what is left of the background is spread back uniformly
 * over the proxies that remain (the used ones optionally removed).
 */
public class IterativeConstituentSubtractor extends ConstituentSubtractor {

    static {
        com.sphere.core.fjcontrib.ContribCitations.use("ics");
    }

    protected List<Double> maxDistances = new ArrayList<>();
    protected List<Double> alphas = new ArrayList<>();
    protected List<Double> nearbyHardRadii = new ArrayList<>();
    protected List<Double> nearbyHardFactors = new ArrayList<>();
    protected boolean useNearbyHardIterative;
    protected boolean ghostRemoval = true;

    public IterativeConstituentSubtractor() {
    }

    @Override
    public void initialize() {
        if (maxDistances.isEmpty()) throw new FastJetException("IterativeConstituentSubtractor::initialize(): The vector for max_distances is empty. It should be specified before using the function initialize.");
        initializeCommon();
    }

    @Override
    public List<PseudoJet> subtractEvent(List<PseudoJet> particles, double maxEtaIn) {
        throw new FastJetException("IterativeConstituentSubtractor::subtract_event(): This version of subtract_event should not be used. Use the version subtract_event(std::vector<fastjet::PseudoJet> const &particles)");
    }

    @Override
    public List<PseudoJet> subtractEvent(List<PseudoJet> particles, List<PseudoJet> hardProxiesIn) {
        final boolean originalSorted = ghostsRapiditySorted;
        if (useNearbyHardIterative) {
            if (hardProxiesIn != null) hardProxies = hardProxiesIn;
            else throw new FastJetException("IterativeConstituentSubtractor::subtract_event: It was requested to use closeby hard proxies but they were not provided in this function!");
        } else if (hardProxiesIn != null) {
            throw new FastJetException("IterativeConstituentSubtractor::subtract_event: Hard proxies were provided but the set_use_hard_proxies function was not used before initialization. It needs to be called before initialization!");
        }
        List<PseudoJet> proxies = getBackgroundProxiesFromGhosts(ghosts, ghostsArea);
        List<PseudoJet> subtracted = new ArrayList<>();
        final List<PseudoJet> unselected = new ArrayList<>();
        for (PseudoJet p : particles) {
            if (Math.abs(p.eta()) > maxEta) continue;
            if (p.pt() < ZERO_PT && removeAllZeroPtParticles) continue;
            if (p.pt() < ZERO_PT && (massesToZero || p.m() < ZERO_MASS) && removeParticlesWithZeroPtAndMass) continue;
            if (particleSelector != null) {
                if (particleSelector.pass(p)) subtracted.add(p); else unselected.add(p);
            } else {
                subtracted.add(p);
            }
        }
        for (int iteration = 0; iteration < maxDistances.size(); ++iteration) {
            setMaxDistance(maxDistances.get(iteration));
            setAlpha(alphas.get(iteration));
            if (useNearbyHardIterative) setUseNearbyHard(nearbyHardRadii.get(iteration), nearbyHardFactors.get(iteration));
            final boolean last = iteration == maxDistances.size() - 1;
            final List<PseudoJet> remaining = last ? null : new ArrayList<>();
            subtracted = doSubtraction(subtracted, proxies, remaining);
            if (last) continue;
            double backgroundPt = 0, backgroundMt = 0, remainingPt = 0, remainingMt = 0;
            proxies = new ArrayList<>(proxies);
            for (int i = proxies.size() - 1; i >= 0; --i) {
                remainingPt += remaining.get(i).pt();
                remainingMt += remaining.get(i).mt();
                if (ghostRemoval && remaining.get(i).pt() > 1e-10) {
                    proxies.set(i, proxies.get(proxies.size() - 1));
                    proxies.remove(proxies.size() - 1);
                } else {
                    backgroundPt += proxies.get(i).pt();
                    backgroundMt += proxies.get(i).mt();
                }
            }
            if (ghostRemoval) ghostsRapiditySorted = false;
            for (int i = 0; i < proxies.size(); ++i) {
                final PseudoJet b = proxies.get(i);
                final double pt = b.pt() * remainingPt / backgroundPt;
                double mtMinusPt = 0;
                if (backgroundMt > backgroundPt + 1e-20) {
                    mtMinusPt = (b.mt() - b.pt()) * (remainingMt - remainingPt) / (backgroundMt - backgroundPt);
                }
                double mass = 0;
                if (mtMinusPt > 1e-20) mass = Math.sqrt(CRMath.pow(mtMinusPt + pt, 2) - CRMath.pow(pt, 2));
                final PseudoJet reset = b.copy();
                reset.resetMomentumPtYPhiM(pt, b.rap(), b.phi(), mass);
                proxies.set(i, reset);
            }
        }
        ghostsRapiditySorted = originalSorted;
        if (particleSelector != null) subtracted.addAll(unselected);
        return subtracted;
    }

    @Override
    public String description() {
        final StringBuilder d = new StringBuilder("\nDescription of fastjet::IterativeConstituentSubtractor:\n");
        descriptionCommon(d);
        d.append("       IterativeConstituentSubtractor parameters: \n");
        for (int it = 0; it < maxDistances.size(); ++it) {
            d.append("            Iteration ").append(it + 1).append(":  max_distance = ").append(Fmt.g(maxDistances.get(it)))
                .append("  alpha = ").append(Fmt.g(alphas.get(it))).append('\n');
        }
        return d.toString();
    }

    public void setParameters(List<Double> maxDistancesIn, List<Double> alphasIn) {
        if (maxDistancesIn.size() != alphasIn.size()) throw new FastJetException("IterativeConstituentSubtractor::set_parameters(): the provided vectors have different size. They should have the same size.");
        if (maxDistancesIn.isEmpty()) throw new FastJetException("IterativeConstituentSubtractor::set_parameters(): One of the provided vectors is empty. They should be not empty.");
        maxDistances = new ArrayList<>(maxDistancesIn);
        alphas = new ArrayList<>(alphasIn);
    }

    public void setNearbyHardParameters(List<Double> radii, List<Double> factors) {
        if (radii.size() != factors.size()) throw new FastJetException("IterativeConstituentSubtractor::set_use_nearby_hard(): the provided vectors have different size. They should have the same size.");
        if (radii.isEmpty()) throw new FastJetException("IterativeConstituentSubtractor::set_use_nearby_hard(): One of the provided vectors is empty. They should be not empty.");
        nearbyHardRadii = new ArrayList<>(radii);
        nearbyHardFactors = new ArrayList<>(factors);
        useNearbyHardIterative = true;
    }

    public void setGhostRemoval(boolean value) {
        ghostRemoval = value;
    }
}

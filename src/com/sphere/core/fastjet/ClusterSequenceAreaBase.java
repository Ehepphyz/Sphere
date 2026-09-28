package com.sphere.core.fastjet;

import java.util.ArrayList;
import java.util.List;

/**
 * A clustering that knows the area of its jets, fastjet::ClusterSequenceAreaBase,
 * with the median-based estimates of the background built on those areas.
 */
public abstract class ClusterSequenceAreaBase extends ClusterSequence {

    static {
        Citations.use("areas"); // listed in the console's Citations menu once used
    }

    private static final LimitedWarning WARNINGS = new LimitedWarning();
    private static final LimitedWarning WARNINGS_ZERO_AREA = new LimitedWarning();
    private static final LimitedWarning WARNINGS_EMPTY_AREA = new LimitedWarning();

    /** The median of pt/area, its spread and the mean area it was taken over. */
    public record MedianRho(double median, double sigma, double meanArea) { }

    protected ClusterSequenceAreaBase() {
    }

    protected ClusterSequenceAreaBase(List<? extends PseudoJet> particles, JetDefinition jetDef,
                                      boolean writeout) {
        super(particles, jetDef, writeout);
    }

    /** The scalar area of a jet. */
    public double area(PseudoJet jet) {
        return 0.0;
    }

    /** The uncertainty of that area, from the spread over repeated ghostings. */
    public double areaError(PseudoJet jet) {
        return 0.0;
    }

    /** The four-vector area. */
    public PseudoJet area4vector(PseudoJet jet) {
        return new PseudoJet(0.0, 0.0, 0.0, 0.0);
    }

    /** True if the jet is made only of ghosts. */
    public boolean isPureGhost(PseudoJet jet) {
        return false;
    }

    /** True if the ghosts are among the jets' constituents. */
    public boolean hasExplicitGhosts() {
        return false;
    }

    /** The area inside the selector not covered by any jet. */
    public double emptyArea(Selector selector) {
        if (hasExplicitGhosts()) {
            return 0.0;
        }
        return emptyAreaFromJets(inclusiveJets(0.0), selector);
    }

    public double emptyAreaFromJets(List<PseudoJet> allJets, Selector selector) {
        checkSelectorGoodForMedian(selector);
        double empty = selector.area();
        for (PseudoJet j : allJets) {
            if (selector.pass(j)) {
                empty -= area(j);
            }
        }
        return empty;
    }

    /** The empty area counted in jets of typical area 0.55 pi R^2. */
    public double nEmptyJets(Selector selector) {
        final double R = jetDef().R();
        return emptyArea(selector) / (0.55 * Math.PI * R * R);
    }

    public double medianPtPerUnitArea(Selector selector) {
        return medianRhoAndSigma(selector, false).median();
    }

    public double medianPtPerUnitArea4vector(Selector selector) {
        return medianRhoAndSigma(selector, true).median();
    }

    public double medianPtPerUnitSomething(Selector selector, boolean useArea4vector) {
        return medianRhoAndSigma(selector, useArea4vector).median();
    }

    /** rho, sigma and the mean area from the inclusive jets, empty area included. */
    public MedianRho medianRhoAndSigma(Selector selector, boolean useArea4vector) {
        return medianRhoAndSigma(inclusiveJets(), selector, useArea4vector, true);
    }

    public MedianRho medianRhoAndSigma(List<PseudoJet> allJets, Selector selector,
                                       boolean useArea4vector, boolean allAreInclusive) {
        checkJetAlgGoodForMedian();
        checkSelectorGoodForMedian(selector);
        final List<Double> ptOverAreas = new ArrayList<>();
        double totalArea = 0.0;
        double totalNjets = 0;
        for (PseudoJet j : allJets) {
            if (selector.pass(j)) {
                final double thisArea = useArea4vector ? area4vector(j).perp() : area(j);
                if (thisArea > 0) {
                    ptOverAreas.add(j.perp() / thisArea);
                } else {
                    WARNINGS_ZERO_AREA.warn("ClusterSequenceAreaBase::get_median_rho_and_sigma(...): discarded jet with zero area. Zero-area jets may be due to (i) too large a ghost area (ii) a jet being outside the ghost range (iii) the computation not being done using an appropriate algorithm (kt;C/A).");
                }
                totalArea += thisArea;
                totalNjets += 1.0;
            }
        }
        if (ptOverAreas.isEmpty()) {
            return new MedianRho(0.0, 0.0, 0.0);
        }
        final double[] sorted = ptOverAreas.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        final double[] posn = {0.5, (1.0 - 0.6827) / 2.0};
        final double[] res = new double[2];
        double nEmpty;
        double emptyA;
        double meanArea;
        if (hasExplicitGhosts()) {
            emptyA = 0.0;
            nEmpty = 0;
        } else if (allAreInclusive) {
            emptyA = emptyArea(selector);
            nEmpty = nEmptyJets(selector);
        } else {
            emptyA = emptyAreaFromJets(allJets, selector);
            meanArea = totalArea / totalNjets;
            nEmpty = emptyA / meanArea;
        }
        totalNjets += nEmpty;
        totalArea += emptyA;
        final int size = sorted.length;
        if (nEmpty < -size / 4.0) {
            WARNINGS_EMPTY_AREA.warn("ClusterSequenceAreaBase::get_median_rho_and_sigma(...): the estimated empty area is suspiciously large and negative and may lead to an over-estimation of rho. This may be due to (i) a rare statistical fluctuation or (ii) too small a range used to estimate the background properties.");
        }
        for (int i = 0; i < 2; i++) {
            double pos = (size - 1.0 + nEmpty) * posn[i] - nEmpty;
            double ratio;
            if (pos >= 0 && size > 1) {
                int ipos = (int) pos;
                if (ipos + 1 > size - 1) {
                    ipos = size - 2;
                    pos = size - 1;
                }
                ratio = sorted[ipos] * (ipos + 1 - pos) + sorted[ipos + 1] * (pos - ipos);
            } else {
                ratio = 0.0;
            }
            res[i] = ratio;
        }
        final double median = res[0];
        final double error = res[0] - res[1];
        meanArea = totalArea / totalNjets;
        final double sigma = error * Math.sqrt(Math.max(0.0, meanArea));
        return new MedianRho(median, sigma, meanArea);
    }

    /** A fit of pt/area to a + b y^2; returns {a, b}. */
    public double[] parabolicPtPerUnitArea(Selector selector, double excludeAbove, boolean useArea4vector) {
        checkSelectorGoodForMedian(selector);
        int n = 0;
        double meanF = 0, meanX2 = 0, meanX4 = 0, meanFx2 = 0;
        for (PseudoJet j : inclusiveJets()) {
            if (selector.pass(j)) {
                final double thisArea = useArea4vector ? area4vector(j).perp() : area(j);
                final double f = j.perp() / thisArea;
                if (excludeAbove <= 0.0 || f < excludeAbove) {
                    final double x = j.rap();
                    final double x2 = x * x;
                    meanF += f;
                    meanX2 += x2;
                    meanX4 += x2 * x2;
                    meanFx2 += f * x2;
                    n++;
                }
            }
        }
        if (n <= 1) {
            return new double[]{0.0, 0.0};
        }
        meanF /= n;
        meanX2 /= n;
        meanX4 /= n;
        meanFx2 /= n;
        final double b = (meanF * meanX2 - meanFx2) / (meanX2 * meanX2 - meanX4);
        final double a = meanF - b * meanX2;
        return new double[]{a, b};
    }

    /** The jets above ptmin minus rho times their four-vector area. */
    public List<PseudoJet> subtractedJets(double rho, double ptmin) {
        final List<PseudoJet> sub = new ArrayList<>();
        for (PseudoJet j : PseudoJet.sortedByPt(inclusiveJets(ptmin))) {
            sub.add(subtractedJet(j, rho));
        }
        return sub;
    }

    public List<PseudoJet> subtractedJets(Selector selector, double ptmin) {
        return subtractedJets(medianPtPerUnitArea4vector(selector), ptmin);
    }

    public PseudoJet subtractedJet(PseudoJet jet, double rho) {
        final PseudoJet area4 = area4vector(jet);
        PseudoJet sub;
        if (rho * area4.perp() < jet.perp()) {
            sub = jet.minus(area4.times(rho));
        } else {
            sub = new PseudoJet(0.0, 0.0, 0.0, 0.0);
        }
        sub.setClusterHistIndex(jet.clusterHistIndex());
        sub.setUserIndex(jet.userIndex());
        sub.setStructure(jet.structure());
        return sub;
    }

    public PseudoJet subtractedJet(PseudoJet jet, Selector selector) {
        return subtractedJet(jet, medianPtPerUnitArea4vector(selector));
    }

    public double subtractedPt(PseudoJet jet, double rho, boolean useArea4vector) {
        if (useArea4vector) {
            return subtractedJet(jet, rho).perp();
        }
        return jet.perp() - rho * area(jet);
    }

    protected void checkSelectorGoodForMedian(Selector selector) {
        if (!hasExplicitGhosts() && !selector.hasFiniteArea()) {
            throw new FastJetException("ClusterSequenceAreaBase: empty area can only be computed from selectors with a finite area");
        }
        if (!selector.appliesJetByJet()) {
            throw new FastJetException("ClusterSequenceAreaBase: empty area can only be computed from selectors that apply jet by jet");
        }
    }

    protected void checkJetAlgGoodForMedian() {
        final JetAlgorithm a = jetDef().jetAlgorithm();
        if (a != JetAlgorithm.KT && a != JetAlgorithm.CAMBRIDGE && a != JetAlgorithm.CAMBRIDGE_FOR_PASSIVE) {
            WARNINGS.warn("ClusterSequenceAreaBase: jet_def being used may not be suitable for estimating diffuse backgrounds (good options are kt, cam)");
        }
    }
}

package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's RandGeneral (Random/RandGeneral.h, .icc, RandGeneral.cc): numbers in [0,1) distributed as a
 * histogram given bin by bin: linear within the bin of the cumulated weights (IntType 0) or the bin's
 * low edge (IntType 1).
 */
public class RandGeneral extends HepRandom {

    private HepRandomEngine localEngine;
    private double[] theIntegralPdf;
    private int nBins;
    private double oneOverNbins;
    private int InterpolationType;

    /** On this thread's engine (the one of the moment, kept). */
    public RandGeneral(double[] aProbFunc, int theProbSize) {
        this(aProbFunc, theProbSize, 0);
    }

    public RandGeneral(double[] aProbFunc, int theProbSize, int IntType) {
        this(HepRandom.getTheEngine(), aProbFunc, theProbSize, IntType);
    }

    public RandGeneral(HepRandomEngine anEngine, double[] aProbFunc, int theProbSize) {
        this(anEngine, aProbFunc, theProbSize, 0);
    }

    public RandGeneral(HepRandomEngine anEngine, double[] aProbFunc, int theProbSize, int IntType) {
        localEngine = anEngine;
        nBins = theProbSize;
        InterpolationType = IntType;
        prepareTable(aProbFunc);
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandGeneral(RandGeneral d) {
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandGeneral assign(RandGeneral d) {
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandGeneral d) {
        localEngine = d.localEngine;
        theIntegralPdf = d.theIntegralPdf.clone();
        nBins = d.nBins;
        oneOverNbins = d.oneOverNbins;
        InterpolationType = d.InterpolationType;
    }

    private void prepareTable(double[] aProbFunc) {
        if (nBins < 1) {
            StdStreams.cerr().print("RandGeneral constructed with no bins - will use flat distribution\n");
            useFlatDistribution();
            return;
        }
        theIntegralPdf = new double[nBins + 1];
        theIntegralPdf[0] = 0;
        int ptn;
        double weight;
        for (ptn = 0; ptn < nBins; ++ptn) {
            weight = aProbFunc[ptn];
            if (weight < 0) {
                StdStreams.cerr().print("RandGeneral constructed with negative-weight bin " + ptn + " = "
                    + new COStream().put(weight).str() + " \n   -- will substitute 0 weight \n");
                weight = 0;
            }
            theIntegralPdf[ptn + 1] = theIntegralPdf[ptn] + weight;
        }
        if (theIntegralPdf[nBins] <= 0) {
            StdStreams.cerr().print("RandGeneral constructed nothing in bins - will use flat distribution\n");
            useFlatDistribution();
            return;
        }
        for (ptn = 0; ptn < nBins + 1; ++ptn) {
            theIntegralPdf[ptn] /= theIntegralPdf[nBins];
        }
        oneOverNbins = 1.0 / nBins;
        if ((InterpolationType != 0) && (InterpolationType != 1)) {
            StdStreams.cerr().print("RandGeneral does not recognize IntType " + InterpolationType
                + "\n Will use type 0 (continuous linear interpolation \n");
            InterpolationType = 0;
        }
    }

    private void useFlatDistribution() {
        nBins = 1;
        theIntegralPdf = new double[] {0, 1};
        oneOverNbins = 1.0;
    }

    /** The bin of rand among the cumulated weights by bisection, then the point in it. */
    private double mapRandom(double rand) {
        int nbelow = 0;
        int nabove = nBins;
        int middle;
        while (nabove > nbelow + 1) {
            middle = (nabove + nbelow + 1) >> 1;
            if (rand >= theIntegralPdf[middle]) {
                nbelow = middle;
            } else {
                nabove = middle;
            }
        }
        if (InterpolationType == 1) {
            return nbelow * oneOverNbins;
        } else {
            final double binMeasure = theIntegralPdf[nabove] - theIntegralPdf[nbelow];
            if (binMeasure == 0) {
                return (nbelow + .5) * oneOverNbins;
            }
            final double binFraction = (rand - theIntegralPdf[nbelow]) / binMeasure;
            return (nbelow + binFraction) * oneOverNbins;
        }
    }

    /** shoot() of RandGeneral is fire(): the engine kept. */
    public double shoot() {
        return fire();
    }

    public void shootArray(int size, double[] vect) {
        fireArray(size, vect);
    }

    public double shoot(HepRandomEngine anEngine) {
        return mapRandom(anEngine.flat());
    }

    public void shootArray(HepRandomEngine anEngine, int size, double[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = shoot(anEngine);
    }

    public double fire() {
        return mapRandom(localEngine.flat());
    }

    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = fire();
    }

    @Override
    public double call() {
        return fire();
    }

    @Override
    public String name() {
        return "RandGeneral";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandGeneral";
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        os.put("Uvec").put("\n");
        os.put(nBins).put(" ").put(oneOverNbins).put(" ").put(InterpolationType).put("\n");
        DistIO.words(os, oneOverNbins);
        os.put("\n");
        for (double v : theIntegralPdf) DistIO.put(os, v);
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        final String w = is.nextWord();
        if (w.equals("Uvec")) {
            nBins = is.nextInt();
            is.nextDouble();
            InterpolationType = is.nextInt();
            oneOverNbins = DistIO.getWords(is);
            theIntegralPdf = new double[nBins + 1];
            for (int i = 0; i < theIntegralPdf.length; ++i) theIntegralPdf[i] = DistIO.get(is);
            return is;
        }
        nBins = (int) DistIO.integer(w);
        oneOverNbins = is.nextDouble();
        InterpolationType = is.nextInt();
        theIntegralPdf = new double[nBins + 1];
        for (int i = 0; i < theIntegralPdf.length; ++i) theIntegralPdf[i] = is.nextDouble();
        return is;
    }
}

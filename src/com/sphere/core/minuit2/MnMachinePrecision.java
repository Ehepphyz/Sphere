package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

/**
 * The relative precision of the function values: by default four times the
 * double epsilon, with eps2 = 2 sqrt(eps) the step that numerical
 * derivatives keep above; setPrecision lowers it for a function computed less
 * accurately than doubles allow.
 */
public final class MnMachinePrecision implements MnPrint.Printable {

    private double epsMac;
    private double epsMa2;

    public MnMachinePrecision() {
        epsMac = 4. * Math.ulp(1.0);
        epsMa2 = 2. * Math.sqrt(epsMac);
    }

    public MnMachinePrecision(MnMachinePrecision o) {
        epsMac = o.epsMac;
        epsMa2 = o.epsMa2;
    }

    public double eps() {
        return epsMac;
    }

    public double eps2() {
        return epsMa2;
    }

    public void setPrecision(double prec) {
        epsMac = prec;
        epsMa2 = 2. * Math.sqrt(epsMac);
    }

    /** Finds the precision by halving until 1 + eps no longer differs from 1. */
    public void computePrecision() {
        epsMac = 4.0E-7;
        epsMa2 = 2. * Math.sqrt(epsMac);
        double epstry = 0.5;
        final double one = 1.0;
        for (int i = 0; i < 100; i++) {
            epstry *= 0.5;
            final double epsp1 = one + epstry;
            final double epsbak = epsp1 - one;
            if (epsbak < epstry) {
                epsMac = 8. * epstry;
                epsMa2 = 2. * Math.sqrt(epsMac);
                break;
            }
        }
    }

    @Override
    public void print(COStream os) {
        final int pr = os.precision();
        os.precision(MnMatrix.PRECISION);
        os.put("MnMachinePrecision ").put(eps()).put('\n');
        os.precision(pr);
    }
}

package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

/** The Minos errors of one parameter: the lower and upper crossings and what limited them. */
public final class MinosError implements MnPrint.Printable {

    private final int parameter;
    private final double minParValue;
    private final MnCross upper;
    private final MnCross lower;

    public MinosError() {
        this(0, 0., new MnCross(), new MnCross());
    }

    public MinosError(int par, double value, MnCross low, MnCross up) {
        this.parameter = par;
        this.minParValue = value;
        this.upper = up;
        this.lower = low;
    }

    /** (lower, upper). */
    public double[] pair() {
        return new double[] {lower(), upper()};
    }

    /** The lower error (negative): the crossing, or the parabolic error when not found. */
    public double lower() {
        if (atLowerLimit()) return lowerState().parameter(parameter()).lowerLimit() - minParValue;
        if (lowerValid()) {
            double err = lowerState().error(parameter());
            if (lowerState().parameter(parameter()).hasLowerLimit()) {
                err = Cxx.min(err, minParValue - lowerState().parameter(parameter()).lowerLimit());
            }
            return -1. * err * (1. + lower.value());
        }
        return -lowerState().error(parameter());
    }

    /** The upper error. */
    public double upper() {
        if (atUpperLimit()) return upperState().parameter(parameter()).upperLimit() - minParValue;
        if (upperValid()) {
            double err = upperState().error(parameter());
            if (upperState().parameter(parameter()).hasUpperLimit()) {
                err = Cxx.min(err, upperState().parameter(parameter()).upperLimit() - minParValue);
            }
            return err * (1. + upper.value());
        }
        return upperState().error(parameter());
    }

    public int parameter() {
        return parameter;
    }

    public MnUserParameterState lowerState() {
        return lower.state();
    }

    public MnUserParameterState upperState() {
        return upper.state();
    }

    public boolean isValid() {
        return lower.isValid() && upper.isValid();
    }

    public boolean lowerValid() {
        return lower.isValid();
    }

    public boolean upperValid() {
        return upper.isValid();
    }

    public boolean atLowerLimit() {
        return lower.atLimit();
    }

    public boolean atUpperLimit() {
        return upper.atLimit();
    }

    public boolean atLowerMaxFcn() {
        return lower.atMaxFcn();
    }

    public boolean atUpperMaxFcn() {
        return upper.atMaxFcn();
    }

    public boolean lowerNewMin() {
        return lower.newMinimum();
    }

    public boolean upperNewMin() {
        return upper.newMinimum();
    }

    public int nfcn() {
        return upper.nfcn() + lower.nfcn();
    }

    public double min() {
        return minParValue;
    }

    /** operator&lt;&lt;. */
    @Override
    public void print(COStream os) {
        os.put("Minos # of function calls: ").put(nfcn()).put('\n');
        if (!isValid()) os.put("Minos Error is not valid.").put('\n');
        if (!lowerValid()) os.put("lower Minos Error is not valid.").put('\n');
        if (!upperValid()) os.put("upper Minos Error is not valid.").put('\n');
        if (atLowerLimit()) os.put("Minos Error is Lower limit of Parameter ").put(parameter()).put(".").put('\n');
        if (atUpperLimit()) os.put("Minos Error is Upper limit of Parameter ").put(parameter()).put(".").put('\n');
        if (atLowerMaxFcn()) os.put("Minos number of function calls for Lower Error exhausted.").put('\n');
        if (atUpperMaxFcn()) os.put("Minos number of function calls for Upper Error exhausted.").put('\n');
        if (lowerNewMin()) {
            os.put("Minos found a new Minimum in negative direction.").put('\n');
            lowerState().print(os);
            os.put('\n');
        }
        if (upperNewMin()) {
            os.put("Minos found a new Minimum in positive direction.").put('\n');
            upperState().print(os);
            os.put('\n');
        }
        final int pr = os.precision();
        os.put("No  |").put("|   Name    |").put("|   Value@min   |").put("|    negative   |").put("|   positive  ").put('\n');
        os.setw(4).put(parameter()).setw(5).put("||");
        os.setw(10).put(lowerState().name(parameter())).setw(3).put("||");
        os.setprecision(MnMatrix.PRECISION).setw(MnMatrix.WIDTH).put(min()).put(" ||").setprecision(MnMatrix.PRECISION)
            .setw(MnMatrix.WIDTH).put(lower()).put(" ||").setw(MnMatrix.WIDTH).put(upper()).put('\n');
        os.put('\n');
        os.precision(pr);
    }

    @Override
    public String toString() {
        final COStream os = new COStream();
        print(os);
        return os.str();
    }
}

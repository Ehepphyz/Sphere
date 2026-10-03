package com.sphere.core.fastjet.plugins;

import com.sphere.core.fastjet.DD;
import com.sphere.core.fastjet.PseudoJet;

/**
 * The unit direction of an e+e- brief jet and the 1 - cos(theta) between two
 * of them.
 *
 * In double this is FastJet's arithmetic, n = p/|p| and 1 - n_a.n_b, which
 * loses all relative precision below theta ~ 1e-8. In double-double the
 * direction is normalised to 106 bits and 1 - cos(theta) is taken as half
 * the squared chord |n_a - n_b|^2, which has no cancellation at all.
 */
public final class EEDirection {

    private final boolean dd;
    private final double xH, xL, yH, yL, zH, zL;
    /** The low word of the last {@link #oneMinusCos} result. */
    double lastLow;

    /** The low word of the last {@link #oneMinusCos} result; 0 in double. */
    public double lastLow() {
        return lastLow;
    }

    public EEDirection(PseudoJet jet, boolean dd) {
        this.dd = dd;
        if (!dd) {
            final double norm = 1.0 / Math.sqrt(jet.modp2());
            xH = jet.px() * norm;
            yH = jet.py() * norm;
            zH = jet.pz() * norm;
            xL = yL = zL = 0.0;
            return;
        }
        final DD norm2 = jet.kt2DD().add(jet.pzDD().sqr());
        if (!norm2.gt(0.0)) {
            // FastJet divides by zero here; the distances are then NaN and
            // such a jet only ever meets the beam. Keep that behaviour.
            xH = yH = zH = Double.NaN;
            xL = yL = zL = 0.0;
            return;
        }
        final DD inv = DD.ONE.div(norm2.sqrt());
        final DD x = jet.pxDD().mul(inv);
        final DD y = jet.pyDD().mul(inv);
        final DD z = jet.pzDD().mul(inv);
        xH = x.hi;
        xL = x.lo;
        yH = y.hi;
        yL = y.lo;
        zH = z.hi;
        zL = z.lo;
    }

    /** 1 - cos(theta) to the other direction; the low word goes to lastLow. */
    public double oneMinusCos(EEDirection o) {
        if (!dd) {
            lastLow = 0.0;
            return 1 - xH * o.xH - yH * o.yH - zH * o.zH;
        }
        double sumH = 0.0;
        double sumL = 0.0;
        for (int c = 0; c < 3; c++) {
            final double ah, al, bh, bl;
            if (c == 0) {
                ah = xH; al = xL; bh = o.xH; bl = o.xL;
            } else if (c == 1) {
                ah = yH; al = yL; bh = o.yH; bl = o.yL;
            } else {
                ah = zH; al = zL; bh = o.zH; bl = o.zL;
            }
            final double s = ah - bh;
            final double e = DD.twoSumErr(ah, -bh, s) + (al - bl);
            final double xh = s + e;
            final double xl = e - (xh - s);
            final double p1 = xh * xh;
            final double p1e = DD.twoProdErr(xh, xh, p1) + 2.0 * xh * xl;
            final double t = sumH + p1;
            final double te = DD.twoSumErr(sumH, p1, t) + sumL + p1e;
            sumH = t + te;
            sumL = te - (sumH - t);
        }
        lastLow = 0.5 * sumL;
        return 0.5 * sumH;
    }

    DD oneMinusCosDD(EEDirection o) {
        final double h = oneMinusCos(o);
        return new DD(h, lastLow);
    }
}

package com.sphere.core.fastjet.io;

import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Toy hadron-collider events, to try an analysis without a generator: a few
 * hard partons balanced in transverse momentum, each fragmented into
 * collimated particles (momentum fractions from a soft spectrum, angles
 * falling with energy), over an underlying event of soft particles uniform
 * in rapidity and azimuth. Not physics, but jets that behave like jets.
 */
public final class ToyEvents {

    private ToyEvents() {
    }

    public static List<List<PseudoJet>> generate(int nEvents, int nJets, double ptHard, int nSoft, long seed) {
        final SplittableRandom rnd = new SplittableRandom(seed);
        final List<List<PseudoJet>> events = new ArrayList<>(nEvents);
        for (int e = 0; e < nEvents; e++) {
            final List<PseudoJet> ev = new ArrayList<>();
            final double phi0 = 2 * Math.PI * rnd.nextDouble();
            for (int j = 0; j < nJets; j++) {
                final double pt = ptHard * (j < 2 ? 1.0 : 0.3 + 0.5 * rnd.nextDouble()) * (0.8 + 0.4 * rnd.nextDouble());
                final double y = -2.5 + 5 * rnd.nextDouble();
                final double phi = j < 2 ? phi0 + j * Math.PI + 0.1 * (rnd.nextDouble() - 0.5) : 2 * Math.PI * rnd.nextDouble();
                fragment(ev, pt, y, phi, rnd);
            }
            for (int k = 0; k < nSoft; k++) {
                final PseudoJet p = new PseudoJet();
                p.resetMomentumPtYPhiM(-0.6 * Math.log(1 - rnd.nextDouble()), -4 + 8 * rnd.nextDouble(),
                    2 * Math.PI * rnd.nextDouble(), 0.13957);
                ev.add(p);
            }
            events.add(ev);
        }
        return events;
    }

    private static void fragment(List<PseudoJet> ev, double pt, double y, double phi, SplittableRandom rnd) {
        double left = pt;
        while (left > 0.5) {
            final double z = Math.min(1.0, Math.pow(rnd.nextDouble(), 2.5) + 0.02);
            final double ptk = Math.min(left, z * pt);
            left -= ptk;
            final double spread = 0.25 / Math.sqrt(1 + ptk / 5.0);
            final PseudoJet p = new PseudoJet();
            p.resetMomentumPtYPhiM(ptk, y + spread * gauss(rnd), phi + spread * gauss(rnd), 0.13957);
            ev.add(p);
        }
    }

    private static double gauss(SplittableRandom r) {
        return Math.sqrt(-2 * Math.log(1 - r.nextDouble())) * Math.cos(2 * Math.PI * r.nextDouble());
    }
}

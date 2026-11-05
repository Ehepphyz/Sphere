package com.sphere.core.geant4;

import java.util.List;

/**
 * The dimension of a value the C++ computed, told by reading the code twice: in CLHEP's units, then in the
 * probe's (Units.probeSymbols), where the millimeter is 2, the MeV 3 and the nanosecond 5. A value of
 * length^a energy^b time^c reads 2^a 3^b 5^c times larger in the probe, and the exponents are the small
 * integers whose product the ratio is. No rule is written for any operator or function: the code's own
 * arithmetic carries the units, through a division, a square root or a loop.
 */
public final class Dimensions {

    private Dimensions() {
    }

    /** Exponents of length, energy and time (CLHEP's mass is energy time^2 / length^2). */
    public record Dim(int length, int energy, int time) {

        public static final Dim NONE = new Dim(0, 0, 0);

        /** The category Units shows such a value in, or null when it has none of its own (a field, a pressure...). */
        public Units.Category category() {
            if (energy == 0 && time == 0) {
                return switch (length) {
                    case 0 -> Units.Category.NONE;
                    case 1 -> Units.Category.LENGTH;
                    case 2 -> Units.Category.AREA;
                    case 3 -> Units.Category.VOLUME;
                    default -> null;
                };
            }
            if (length == 0 && energy == 1 && time == 0) {
                return Units.Category.ENERGY;
            }
            if (length == 0 && energy == 0 && time == 1) {
                return Units.Category.TIME;
            }
            if (energy == 1 && time == 2 && length == -2) {
                return Units.Category.MASS;
            }
            if (energy == 1 && time == 2 && length == -5) {
                return Units.Category.DENSITY;
            }
            return null;
        }
    }

    private static final double LN_L = Math.log(Units.PROBE_LENGTH), LN_E = Math.log(Units.PROBE_ENERGY),
        LN_T = Math.log(Units.PROBE_TIME);
    /**
     * The exponents looked for. The closest two products of 2, 3 and 5 within twice these bounds differ by
     * 0.47 % (5^6 / (2^6 3^5)), so a ratio read to a few parts in 1e15 names one of them without doubt.
     */
    private static final int MAX_LENGTH = 6, MAX_OTHER = 3;
    private static final double TOLERANCE = 1e-9;

    /** The dimension the ratio of a probe value to its value shows; null when it is no small product of 2, 3 and 5. */
    public static Dim of(double value, double probe) {
        if (value == 0 || probe == 0 || !Double.isFinite(value) || !Double.isFinite(probe)
            || Math.signum(value) != Math.signum(probe)) {
            return null;
        }
        final double r = Math.log(probe / value);
        for (int a = -MAX_LENGTH; a <= MAX_LENGTH; a++) {
            for (int b = -MAX_OTHER; b <= MAX_OTHER; b++) {
                for (int c = -MAX_OTHER; c <= MAX_OTHER; c++) {
                    if (Math.abs(r - (a * LN_L + b * LN_E + c * LN_T)) < TOLERANCE) {
                        return new Dim(a, b, c);
                    }
                }
            }
        }
        return null;
    }

    /**
     * One dimension for values computed together (the runs of a loop, the coordinates of a vector), each
     * against its probe: the non-zero ones must agree. All of them zero is dimensionless; null when they
     * disagree, when a ratio is no product of the probe's units, or when the probe ran the code otherwise.
     */
    public static Dim of(List<double[]> values, List<double[]> probes) {
        if (values.size() != probes.size()) {
            return null;
        }
        Dim found = null;
        for (int i = 0; i < values.size(); i++) {
            final double[] v = values.get(i), p = probes.get(i);
            if (v.length != p.length) {
                return null;
            }
            for (int k = 0; k < v.length; k++) {
                if (v[k] == 0 && p[k] == 0) {
                    continue;
                }
                final Dim d = of(v[k], p[k]);
                if (d == null || (found != null && !found.equals(d))) {
                    return null;
                }
                found = d;
            }
        }
        return found == null ? Dim.NONE : found;
    }
}

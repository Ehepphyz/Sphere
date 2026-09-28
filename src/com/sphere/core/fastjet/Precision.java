package com.sphere.core.fastjet;

/**
 * The arithmetic a clustering is carried out in.
 *
 * {@link #DD} holds momenta, rapidities, azimuths and distances as
 * double-double numbers, 106 bits of significand, so an angle is known to
 * 2^-106 and two particles a hair apart in the collinear limit are still told
 * apart. {@link #DOUBLE} reproduces the C++ FastJet arithmetic operation for
 * operation, so its results agree with FastJet bit for bit; it is the mode to
 * compare against published output.
 */
public enum Precision {
    DOUBLE,
    DD;

    private static volatile Precision defaultPrecision = DD;

    /** The precision used when none is given. */
    public static Precision defaultPrecision() {
        return defaultPrecision;
    }

    public static void setDefault(Precision precision) {
        defaultPrecision = precision == null ? DD : precision;
    }

    public String description() {
        return this == DD ? "double-double (2^-106)" : "double (FastJet bit-compatible)";
    }
}

package com.sphere.core.geant4;

/**
 * A chemical element as G4Element sees it: atomic number, molar mass and the
 * effective nucleon number, plus the radiation-length factor of Tsai's
 * formula that G4Element::ComputeLradTsaiFactor computes.
 */
public final class Element {

    public final String name;
    public final String symbol;
    public final int z;
    /** Molar mass in g/mole. */
    public final double a;
    /** Effective number of nucleons (G4Element::GetN). */
    public final double n;
    public final boolean nist;
    /** Tsai's radiation-length factor per atom, in mm^2 (G4Element::GetfRadTsai). */
    public final double radTsai;

    public Element(String name, String symbol, int z, double a, double n, boolean nist) {
        this.name = name;
        this.symbol = symbol;
        this.z = z;
        this.a = a;
        this.n = n;
        this.nist = nist;
        this.radTsai = radTsai(z);
    }

    /** G4Element::ComputeCoulombFactor then ComputeLradTsaiFactor, for an integer Zeff. */
    static double radTsai(double zeff) {
        final double k1 = 0.0083, k2 = 0.20206, k3 = 0.0020, k4 = 0.0369;
        final double az2 = (Units.FINE_STRUCTURE * zeff) * (Units.FINE_STRUCTURE * zeff);
        final double az4 = az2 * az2;
        final double coulomb = (k1 * az4 + k2 + 1. / (1. + az2)) * az2 - (k3 * az4 + k4) * az4;

        final double[] lradLight = {5.31, 4.79, 4.74, 4.71};
        final double[] lpradLight = {6.144, 5.621, 5.805, 5.924};
        final double logZ3 = Math.log(zeff) / 3.;
        final int iz = (int) Math.rint(zeff) - 1;
        double lrad, lprad;
        if (iz <= 3) {
            lrad = lradLight[Math.max(0, iz)];
            lprad = lpradLight[Math.max(0, iz)];
        } else {
            lrad = Math.log(184.15) - logZ3;
            lprad = Math.log(1194.) - 2 * logZ3;
        }
        final double alphaRcl2 = Units.FINE_STRUCTURE * Units.CLASSIC_ELECTR_RADIUS * Units.CLASSIC_ELECTR_RADIUS;
        return 4 * alphaRcl2 * zeff * (zeff * (lrad - coulomb) + lprad);
    }

    @Override
    public String toString() {
        return symbol + " (Z=" + z + ", A=" + Units.num(a) + " g/mole)";
    }
}

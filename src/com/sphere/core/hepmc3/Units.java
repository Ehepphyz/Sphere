package com.sphere.core.hepmc3;

/**
 * Units of an event: momentum in MeV or GeV, length in mm or cm, and the
 * conversions between them.
 */
public final class Units {

    /** Momentum units. */
    public enum MomentumUnit { MEV, GEV }

    /** Position units. */
    public enum LengthUnit { MM, CM }

    private Units() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The momentum unit a name starts with ("GEV", "MEV"); GEV with an error when neither. */
    public static MomentumUnit momentumUnit(String name) {
        if (name.startsWith("GEV")) return MomentumUnit.GEV;
        if (name.startsWith("MEV")) return MomentumUnit.MEV;
        Setup.error(300, "Units::momentum_unit: unrecognised unit name: '" + name + "', setting to GEV");
        return MomentumUnit.GEV;
    }

    /** The length unit a name starts with ("MM", "CM"); CM with an error when neither. */
    public static LengthUnit lengthUnit(String name) {
        if (name.startsWith("CM")) return LengthUnit.CM;
        if (name.startsWith("MM")) return LengthUnit.MM;
        Setup.error(300, "Units::length_unit: unrecognised unit name: '" + name + "', setting to CM");
        return LengthUnit.CM;
    }

    public static String name(MomentumUnit u) {
        return u == null ? "<UNDEFINED>" : u.name();
    }

    public static String name(LengthUnit u) {
        return u == null ? "<UNDEFINED>" : u.name();
    }

    /** A four-vector from one momentum unit to another, in place. */
    public static void convert(FourVector v, MomentumUnit from, MomentumUnit to) {
        if (from == to) return;
        if (from == MomentumUnit.GEV) v.multiplyBy(1000.);
        else if (from == MomentumUnit.MEV) v.multiplyBy(0.001);
    }

    /** A value from one momentum unit to another. */
    public static double convert(double m, MomentumUnit from, MomentumUnit to) {
        if (from == to) return m;
        if (from == MomentumUnit.GEV) return m * 1000.;
        if (from == MomentumUnit.MEV) return m * 0.001;
        return m;
    }

    /** A four-vector from one length unit to another, in place. */
    public static void convert(FourVector v, LengthUnit from, LengthUnit to) {
        if (from == to) return;
        if (from == LengthUnit.CM) v.multiplyBy(10.0);
        else if (from == LengthUnit.MM) v.multiplyBy(0.1);
    }

    /** A value from one length unit to another. */
    public static double convert(double m, LengthUnit from, LengthUnit to) {
        if (from == to) return m;
        if (from == LengthUnit.CM) return m * 10.0;
        if (from == LengthUnit.MM) return m * 0.1;
        return m;
    }
}

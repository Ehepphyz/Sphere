package com.sphere.core.geant4;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CLHEP's system of units, exactly as G4GDMLEvaluator sees it.
 *
 * Geant4's GDML reader calls Evaluator::setStdMath() and
 * Evaluator::setSystemOfUnits(meter, kilogram, second, ampere, kelvin, mole,
 * candela) with CLHEP's own base values, so "10*cm" in a GDML attribute is 100
 * (millimetres) and "45*deg" is 0.785 (radians). The table below is built the
 * same way, from the same base values, so an expression evaluates here to the
 * double Geant4 computes.
 */
public final class Units {

    private Units() {
    }

    // CLHEP base units in Geant4's internal system (mm, ns, MeV, eplus, kelvin, mole, candela)
    public static final double MILLIMETER = 1.0;
    public static final double METER = 1000.0 * MILLIMETER;
    public static final double NANOSECOND = 1.0;
    public static final double SECOND = 1.e+9 * NANOSECOND;
    public static final double MEV = 1.0;
    public static final double EPLUS = 1.0;
    public static final double E_SI = 1.602176634e-19;
    public static final double EV = 1.e-6 * MEV;
    public static final double JOULE = EV / E_SI;
    public static final double KILOGRAM = JOULE * SECOND * SECOND / (METER * METER);
    public static final double COULOMB = EPLUS / E_SI;
    public static final double AMPERE = COULOMB / SECOND;
    public static final double KELVIN = 1.0;
    public static final double MOLE = 1.0;
    public static final double CANDELA = 1.0;

    public static final double PI = 3.14159265358979323846;
    public static final double TWOPI = 2 * PI;
    public static final double DEG = PI / 180.0;
    public static final double CM = 10.0;
    public static final double CM3 = CM * CM * CM;
    public static final double GRAM = 1.e-3 * KILOGRAM;
    public static final double G_PER_CM3 = GRAM / CM3;
    public static final double AVOGADRO = 6.02214076e+23 / MOLE;
    public static final double AMU = GRAM / MOLE / AVOGADRO;   // CLHEP amu = amu_c2/c_squared
    public static final double ATMOSPHERE;
    public static final double FINE_STRUCTURE = 7.2973525693e-3;
    // classic_electr_radius = elm_coupling / electron_mass_c2 (CLHEP), in mm
    public static final double CLASSIC_ELECTR_RADIUS = 2.8179403262e-12;

    /** Every name the GDML evaluator knows, mapped to its value. */
    private static final Map<String, Double> SYMBOLS;

    static {
        final Map<String, Double> t = new LinkedHashMap<>();
        final double kilo = 1e3, mega = 1e6, giga = 1e9, tera = 1e12, peta = 1e15;
        final double deci = 1e-1, centi = 1e-2, milli = 1e-3, micro = 1e-6, nano = 1e-9, pico = 1e-12;
        final double m = METER, kg = KILOGRAM, s = SECOND, A = AMPERE, K = KELVIN, mol = MOLE, cd = CANDELA;

        // setStdMath
        t.put("pi", PI);
        t.put("e", Math.E);
        t.put("gamma", 0.577215664901532861);
        t.put("radian", 1.0);
        t.put("rad", 1.0);
        t.put("degree", DEG);
        t.put("deg", DEG);

        // setSystemOfUnits
        put(t, m, "meter", "metre", "m");
        put(t, kg, "kilogram", "kg");
        put(t, s, "second", "s");
        put(t, A, "ampere", "amp", "A");
        put(t, K, "kelvin", "K");
        put(t, mol, "mole", "mol");
        put(t, cd, "candela", "cd");
        put(t, milli, "milliradian", "mrad");
        put(t, 1.0, "steradian", "sr");
        final double Hz = 1. / s;
        put(t, Hz, "hertz", "Hz");
        final double N = m * kg / (s * s);
        put(t, N, "newton", "N");
        final double Pa = N / (m * m);
        put(t, Pa, "pascal", "Pa");
        put(t, 101325. * Pa, "atmosphere", "atm");
        put(t, 100000 * Pa, "bar");
        final double J = N * m;
        put(t, J, "joule", "J");
        final double W = J / s;
        put(t, W, "watt", "W");
        final double C = A * s;
        put(t, C, "coulomb", "C");
        final double V = J / C;
        put(t, V, "volt", "V");
        final double ohm = V / A;
        put(t, ohm, "ohm");
        put(t, 1. / ohm, "siemens", "S");
        put(t, C / V, "farad", "F");
        final double T = V * s / (m * m);
        put(t, T, "tesla", "T");
        put(t, 1.e-4 * T, "gauss", "Gs");
        final double Wb = V * s;
        put(t, Wb, "weber", "Wb");
        put(t, Wb / A, "henry", "H");
        final double lm = cd;
        put(t, lm, "lumen", "lm");
        put(t, lm / (m * m), "lux", "lx");
        final double Bq = 1. / s;
        put(t, Bq, "becquerel", "Bq");
        put(t, kilo * Bq, "kilobecquerel", "kBq");
        put(t, mega * Bq, "megabecquerel", "MBq");
        put(t, giga * Bq, "gigabecquerel", "GBq");
        put(t, 3.7e+10 * Bq, "curie", "Ci");
        put(t, milli * 3.7e+10 * Bq, "millicurie", "mCi");
        put(t, micro * 3.7e+10 * Bq, "microcurie", "uCi");
        final double Gy = J / kg;
        put(t, Gy, "gray", "Gy");
        put(t, kilo * Gy, "kilogray");
        put(t, milli * Gy, "milligray");
        put(t, micro * Gy, "microgray");
        put(t, J / kg, "sievert", "Sv");
        final double mm = milli * m, cm = centi * m, km = kilo * m;
        put(t, mm, "millimeter", "mm");
        put(t, cm, "centimeter", "cm");
        put(t, deci * m, "decimeter");
        put(t, km, "kilometer", "km");
        put(t, micro * m, "micrometer", "micron", "um");
        put(t, nano * m, "nanometer", "nm");
        put(t, 3.0856775807e+16 * m, "parsec", "pc");
        put(t, 1.e-10 * m, "angstrom");
        put(t, 1.e-15 * m, "fermi");
        put(t, m * m, "m2");
        put(t, mm * mm, "mm2");
        put(t, cm * cm, "cm2");
        put(t, km * km, "km2");
        final double barn = 1.e-28 * m * m;
        put(t, barn, "barn");
        put(t, milli * barn, "millibarn", "mbarn");
        put(t, micro * barn, "microbarn");
        put(t, nano * barn, "nanobarn");
        put(t, pico * barn, "picobarn");
        put(t, m * m * m, "m3");
        put(t, mm * mm * mm, "mm3");
        put(t, cm * cm * cm, "cm3", "cc");
        put(t, km * km * km, "km3");
        final double L = 1.e-3 * m * m * m;
        put(t, L, "liter", "litre", "L");
        put(t, centi * L, "centiliter", "cL");
        put(t, milli * L, "milliliter", "mL");
        put(t, 1. / m, "diopter", "dioptre", "dpt");
        final double g = 0.001 * kg;
        put(t, g, "gram", "g");
        put(t, milli * g, "milligram", "mg");
        put(t, milli * s, "millisecond", "ms");
        put(t, micro * s, "microsecond", "us");
        put(t, nano * s, "nanosecond", "ns");
        put(t, pico * s, "picosecond", "ps");
        final double minute = 60 * s, hour = 60 * minute, day = 24 * hour;
        put(t, minute, "minute");
        put(t, hour, "hour");
        put(t, day, "day");
        put(t, 365 * day, "year");
        put(t, milli * A, "milliampere", "mA");
        put(t, micro * A, "microampere");
        put(t, nano * A, "nanoampere");
        put(t, kilo * Hz, "kilohertz", "kHz");
        put(t, mega * Hz, "megahertz", "MHz");
        put(t, kilo * N, "kilonewton", "kN");
        put(t, kilo * 100000 * Pa, "kilobar", "kbar");
        put(t, milli * 100000 * Pa, "millibar", "mbar");
        put(t, kilo * J, "kilojoule", "kJ");
        put(t, mega * J, "megajoule", "MJ");
        put(t, giga * J, "gigajoule", "GJ");
        final double eV = E_SI * C * V;
        put(t, eV, "electronvolt", "eV");
        put(t, milli * eV, "millielectronvolt");
        put(t, kilo * eV, "kiloelectronvolt", "keV");
        put(t, mega * eV, "megaelectronvolt", "MeV");
        put(t, giga * eV, "gigaelectronvolt", "GeV");
        put(t, tera * eV, "teraelectronvolt", "TeV");
        put(t, peta * eV, "petaelectronvolt", "PeV");
        put(t, kilo * W, "kilowatt", "kW");
        put(t, mega * W, "megawatt", "MW");
        put(t, giga * W, "gigawatt", "GW");
        put(t, kilo * V, "kilovolt", "kV");
        put(t, mega * V, "megavolt", "MV");
        put(t, milli * (C / V), "millifarad", "mF");
        put(t, micro * (C / V), "microfarad", "uF");
        put(t, nano * (C / V), "nanofarad", "nF");
        put(t, pico * (C / V), "picofarad", "pF");
        put(t, 1.e-1 * T, "kilogauss", "kGs");
        SYMBOLS = Collections.unmodifiableMap(t);
        ATMOSPHERE = t.get("atmosphere");
    }

    private static void put(Map<String, Double> t, double value, String... names) {
        for (String n : names) {
            t.put(n, value);
        }
    }

    /** The value of a unit or constant the GDML evaluator defines, or null. */
    public static Double symbol(String name) {
        return SYMBOLS.get(name);
    }

    public static boolean isReserved(String name) {
        return SYMBOLS.containsKey(name);
    }

    public static Map<String, Double> symbols() {
        return SYMBOLS;
    }

    // ------------------------------------------------------------------
    // Display: the G4BestUnit idea, a value shown in the unit that keeps it readable
    // ------------------------------------------------------------------

    public enum Category {
        LENGTH("mm", new String[] {"km", "m", "cm", "mm", "um", "nm"},
                     new double[] {1e6, 1e3, 10, 1, 1e-3, 1e-6}),
        ANGLE("deg", new String[] {"deg", "mrad", "urad"},
                     new double[] {DEG, 1e-3, 1e-6}),
        AREA("mm2", new String[] {"m2", "cm2", "mm2", "um2"}, new double[] {1e6, 100, 1, 1e-6}),
        VOLUME("mm3", new String[] {"m3", "L", "cm3", "mm3", "um3"},
                      new double[] {1e9, 1e6, 1e3, 1, 1e-9}),
        MASS("g", new String[] {"t", "kg", "g", "mg", "ug"},
                  new double[] {1e3 * KILOGRAM, KILOGRAM, GRAM, 1e-3 * GRAM, 1e-6 * GRAM}),
        DENSITY("g/cm3", new String[] {"g/cm3", "mg/cm3", "ug/cm3"},
                         new double[] {G_PER_CM3, 1e-3 * G_PER_CM3, 1e-6 * G_PER_CM3}),
        ENERGY("MeV", new String[] {"PeV", "TeV", "GeV", "MeV", "keV", "eV"},
                      new double[] {1e9, 1e6, 1e3, 1, 1e-3, 1e-6}),
        TIME("ns", new String[] {"s", "ms", "us", "ns", "ps"}, new double[] {1e9, 1e6, 1e3, 1, 1e-3}),
        NONE("", new String[] {""}, new double[] {1});

        public final String defaultUnit;
        final String[] names;
        final double[] values;

        Category(String defaultUnit, String[] names, double[] values) {
            this.defaultUnit = defaultUnit;
            this.names = names;
            this.values = values;
        }

        public double unitValue(String unit) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(unit)) {
                    return values[i];
                }
            }
            final Double s = symbol(unit);
            return s != null ? s : 1.0;
        }
    }

    /** Formats a value in internal units with the unit that keeps it between 1 and 1000. */
    public static String best(double value, Category c) {
        if (c == Category.NONE) {
            return num(value);
        }
        if (c == Category.ANGLE) {
            return num(value / DEG) + " deg";
        }
        final double a = Math.abs(value);
        if (a == 0 || Double.isInfinite(a) || Double.isNaN(a)) {
            return num(value) + " " + c.defaultUnit;
        }
        for (int i = 0; i < c.names.length; i++) {
            if (a >= c.values[i] * 0.9999999 || i == c.names.length - 1) {
                return num(value / c.values[i]) + " " + c.names[i];
            }
        }
        return num(value) + " " + c.defaultUnit;
    }

    /** A number with up to 6 significant digits and no trailing zeros. */
    public static String num(double v) {
        if (Double.isNaN(v)) {
            return "nan";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "inf" : "-inf";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        final double a = Math.abs(v);
        String s;
        if (a >= 1e-4 && a < 1e7) {
            s = String.format(Locale.ROOT, "%.6g", v);
            if (s.contains("e")) {
                return s;
            }
            if (s.indexOf('.') >= 0) {
                s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return s;
        }
        s = String.format(Locale.ROOT, "%.5e", v);
        return s.replaceAll("0+e", "e").replaceAll("\\.e", "e");
    }

    /** A literal for generated code and GDML: round-trips the double exactly. */
    public static String exact(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v).replace("E", "e");
    }
}

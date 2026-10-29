package com.sphere.core.clhep;

import com.sphere.core.clhep.evaluator.Evaluator;
import com.sphere.core.clhep.units.PhysicalConstants;
import com.sphere.core.clhep.units.SystemOfUnits;
import com.sphere.core.clhep.units.UnitTable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CLHEP's Evaluator as a calculator of physical quantities: an expression ({@code 10*GeV/c_light}) is
 * evaluated in CLHEP's units (mm, ns, MeV, eplus, kelvin, mole, candela), with every unit and constant of
 * SystemOfUnits and PhysicalConstants at CLHEP's own value, and the variables {@link #let} defines.
 *
 * <p>Its dimension is found without any table of dimensions: the expression is evaluated again in seven
 * systems of units, each with one SI base unit (metre, kilogram, second, ampere, kelvin, mole, candela) twice
 * as large, and the ratio of the values is two to the power of that unit's exponent. A sum of quantities of
 * different dimensions, or a function of a dimensioned quantity, has ratios that are no such powers: it is
 * said to be inconsistent.
 */
public final class Quantities {

    /** The SI base units, in the order of the exponents. */
    public static final String[] BASE = {"m", "kg", "s", "A", "K", "mol", "cd"};

    /** An evaluation: the value, or the Evaluator's error and where it stopped. */
    public record Value(double value, boolean ok, String error, int position) {
    }

    /**
     * The exponents of the SI base units, halves allowed; null exponents when the expression has none
     * consistent (its terms differ in dimension).
     */
    public record Dimension(double[] exponents) {

        public boolean dimensionless() {
            for (double e : exponents) {
                if (e != 0) return false;
            }
            return true;
        }

        public boolean is(double... e) {
            for (int k = 0; k < exponents.length; k++) {
                if (exponents[k] != (k < e.length ? e[k] : 0)) return false;
            }
            return true;
        }

        public boolean sameAs(Dimension o) {
            return o != null && is(o.exponents);
        }

        /** As SI writes it: "kg m^2 s^-2", "1" when dimensionless. */
        public String si() {
            final StringBuilder b = new StringBuilder();
            final int[] order = {1, 0, 2, 3, 4, 5, 6};
            for (int k : order) {
                final double e = exponents[k];
                if (e == 0) continue;
                if (b.length() > 0) b.append(' ');
                b.append(BASE[k]);
                if (e != 1) b.append('^').append(e == Math.rint(e) ? Integer.toString((int) e) : (int) (2 * e) + "/2");
            }
            return b.length() == 0 ? "1" : b.toString();
        }

        /** What kind of quantity it is, or "" when it has no usual name. */
        public String name() {
            for (Kind k : KINDS) {
                if (is(k.exponents)) return k.name;
            }
            return "";
        }
    }

    /** A kind of quantity, and the units it reads best in, from the smallest. */
    private record Kind(String name, double[] exponents, String[] units, String suffix, String scale) {
    }

    private static Kind kind(String name, double[] e, String... units) {
        return new Kind(name, e, units, "", null);
    }

    private static final List<Kind> KINDS = List.of(
        kind("length", new double[]{1}, "fermi", "nm", "um", "mm", "cm", "m", "km", "pc"),
        kind("area", new double[]{2}, "picobarn", "nanobarn", "microbarn", "millibarn", "barn", "mm2", "cm2", "m2", "km2"),
        kind("volume", new double[]{3}, "mm3", "cm3", "liter", "m3"),
        kind("time", new double[]{0, 0, 1}, "ps", "ns", "us", "ms", "s", "minute", "hour", "day", "year"),
        kind("frequency or activity", new double[]{0, 0, -1}, "Hz", "kHz", "MHz"),
        kind("energy", new double[]{2, 1, -2}, "millielectronvolt", "eV", "keV", "MeV", "GeV", "TeV", "PeV"),
        new Kind("momentum", new double[]{1, 1, -1}, new String[]{"eV", "keV", "MeV", "GeV", "TeV", "PeV"}, "/c", "c_light"),
        new Kind("mass", new double[]{0, 1}, new String[]{"eV", "keV", "MeV", "GeV", "TeV", "PeV"}, "/c^2", "c_squared"),
        new Kind("velocity", new double[]{1, 0, -1}, new String[]{"c_light"}, "", null),
        kind("acceleration", new double[]{1, 0, -2}, "m/s2"),
        kind("force", new double[]{1, 1, -2}, "N", "kN"),
        kind("power", new double[]{2, 1, -3}, "W", "kW", "MW", "GW"),
        kind("pressure", new double[]{-1, 1, -2}, "Pa", "mbar", "bar", "kbar"),
        kind("action", new double[]{2, 1, -1}, "hbar_Planck"),
        kind("energy x length", new double[]{3, 1, -2}, "MeV*fermi", "GeV*fermi"),
        kind("electric charge", new double[]{0, 0, 1, 1}, "eplus", "coulomb"),
        kind("electric current", new double[]{0, 0, 0, 1}, "nanoampere", "microampere", "mA", "A"),
        kind("electric potential", new double[]{2, 1, -3, -1}, "V", "kV", "MV"),
        kind("electric field", new double[]{1, 1, -3, -1}, "V/cm", "kV/cm", "MV/m"),
        kind("magnetic field", new double[]{0, 1, -2, -1}, "gauss", "kilogauss", "tesla"),
        kind("magnetic flux", new double[]{2, 1, -2, -1}, "weber"),
        kind("magnetic moment", new double[]{2, 0, 0, 1}, "MeV/tesla"),
        kind("resistance", new double[]{2, 1, -3, -2}, "ohm"),
        kind("capacitance", new double[]{-2, -1, 4, 2}, "pF", "nF", "uF", "mF", "farad"),
        kind("inductance", new double[]{2, 1, -2, -2}, "henry"),
        kind("permeability", new double[]{1, 1, -2, -2}, "henry/m"),
        kind("permittivity", new double[]{-3, -1, 4, 2}, "farad/m"),
        kind("absorbed dose", new double[]{2, 0, -2}, "microgray", "milligray", "gray", "kilogray"),
        kind("density", new double[]{-3, 1}, "mg/cm3", "g/cm3"),
        kind("entropy (energy per kelvin)", new double[]{2, 1, -2, 0, -1}, "eV/kelvin", "MeV/kelvin"),
        kind("temperature", new double[]{0, 0, 0, 0, 1}, "kelvin"),
        kind("amount of substance", new double[]{0, 0, 0, 0, 0, 1}, "mole"),
        kind("per amount of substance", new double[]{0, 0, 0, 0, 0, -1}, "1/mole"),
        kind("luminous intensity", new double[]{0, 0, 0, 0, 0, 0, 1}, "candela"));

    /** The Evaluator in CLHEP's units, then one per SI base unit doubled. */
    private final Evaluator base;
    private final Evaluator[] scaled = new Evaluator[BASE.length];
    private final Map<String, String> lets = new LinkedHashMap<>();

    public Quantities() {
        base = make(1, -1);
        for (Field f : fields()) {
            try {
                base.setVariable(f.getName(), f.getDouble(null));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        for (int k = 0; k < BASE.length; k++) scaled[k] = make(2, k);
    }

    /** SystemOfUnits' then PhysicalConstants' public doubles. */
    public static List<Field> fields() {
        final List<Field> out = new ArrayList<>();
        for (Class<?> c : new Class<?>[]{SystemOfUnits.class, PhysicalConstants.class}) {
            for (Field f : c.getDeclaredFields()) {
                final int m = f.getModifiers();
                if (Modifier.isPublic(m) && Modifier.isStatic(m) && f.getType() == double.class) out.add(f);
            }
        }
        return out;
    }

    /**
     * An Evaluator with mathematics and the units: CLHEP's base values of the SI units, the one at index k
     * times factor, then the units and constants of CLHEP it does not define, from their expressions.
     */
    private static Evaluator make(double factor, int k) {
        final double[] si = {SystemOfUnits.meter, SystemOfUnits.kilogram, SystemOfUnits.second, SystemOfUnits.ampere,
            SystemOfUnits.kelvin, SystemOfUnits.mole, SystemOfUnits.candela};
        if (k >= 0) si[k] *= factor;
        final Evaluator ev = new Evaluator();
        ev.setStdMath();
        ev.setSystemOfUnits(si[0], si[1], si[2], si[3], si[4], si[5], si[6]);
        // eplus is a base unit of CLHEP's system: in another it is the positron charge in coulombs
        ev.setVariable("e_SI", 1.602176634e-19);
        ev.setVariable("eplus", ev.evaluate("e_SI*coulomb"));
        for (String[] d : UnitTable.DEFINITIONS) {
            if (ev.findVariable(d[0])) continue;
            final double v = ev.evaluate(d[1]);
            if (ev.status() == Evaluator.OK) ev.setVariable(d[0], v);
        }
        return ev;
    }

    /** The value of an expression in CLHEP's units. */
    public Value value(String expression) {
        final double v = base.evaluate(expression);
        final int status = base.status();
        if (status == Evaluator.OK) return new Value(v, true, "", 0);
        return new Value(v, false, base.error_name().replace("Evaluator : ", ""), base.error_position());
    }

    /** The dimension of an expression, null when its value is 0, not finite or not of one dimension. */
    public Dimension dimension(String expression) {
        final double v0 = base.evaluate(expression);
        if (base.status() != Evaluator.OK || v0 == 0 || !Double.isFinite(v0)) return null;
        final double[] e = new double[BASE.length];
        for (int k = 0; k < BASE.length; k++) {
            final double vk = scaled[k].evaluate(expression);
            if (scaled[k].status() != Evaluator.OK || vk == 0 || !Double.isFinite(vk)) return null;
            final double a = Math.log(Math.abs(vk / v0)) / Math.log(2);
            final double half = Math.rint(2 * a) / 2;
            if (Math.abs(a - half) > 1e-6) return new Dimension(null);
            e[k] = half == 0 ? 0 : half;
        }
        return new Dimension(e);
    }

    /** Whether the dimension found is one: a quantity whose terms agree. */
    public static boolean consistent(Dimension d) {
        return d != null && d.exponents() != null;
    }

    /** Defines a variable in every system of units; the error when the expression has none. */
    public String let(String name, String expression) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) return "'" + name + "' is not a name (a letter or _, then letters, digits, _)";
        final Value v = value(expression);
        if (!v.ok()) return v.error() + " at '" + tail(expression, v.position()) + "'";
        final double[] values = new double[BASE.length];
        for (int k = 0; k < BASE.length; k++) values[k] = scaled[k].evaluate(expression);
        base.setVariable(name, v.value());
        for (int k = 0; k < BASE.length; k++) scaled[k].setVariable(name, values[k]);
        lets.put(name, expression);
        return null;
    }

    public boolean forget(String name) {
        if (lets.remove(name) == null) return false;
        base.removeVariable(name);
        for (Evaluator ev : scaled) ev.removeVariable(name);
        return true;
    }

    /** The variables defined, with their expressions. */
    public Map<String, String> lets() {
        return new LinkedHashMap<>(lets);
    }

    private static String tail(String expression, int position) {
        return position >= 0 && position < expression.length() ? expression.substring(position) : expression;
    }

    /** Where in the expression an evaluation stopped, as the text from there. */
    public static String at(String expression, int position) {
        return tail(expression, position);
    }

    /**
     * The value in the units that read best for its dimension ("10 GeV", "3.3 ns", "0.5 c"): the largest unit
     * not larger than the value. Empty when the dimension has no units here.
     */
    public String readable(double value, Dimension d) {
        if (d == null || d.exponents() == null) return "";
        for (Kind k : KINDS) {
            if (!d.is(k.exponents)) continue;
            if (k.name.equals("velocity")) {
                return num(value / PhysicalConstants.c_light) + " c";
            }
            final double v = k.scale == null ? value : value * base.evaluate(k.scale);
            String best = null;
            double bestUnit = 0;
            for (String u : k.units) {
                final double unit = base.evaluate(u);
                if (base.status() != Evaluator.OK || unit <= 0) continue;
                if (best == null || Math.abs(v) >= unit * (1 - 1e-12)) {
                    best = u;
                    bestUnit = unit;
                }
            }
            if (best == null) return "";
            return num(v / bestUnit) + " " + symbol(best) + k.suffix;
        }
        return "";
    }

    /** A unit as it is usually written. */
    private static String symbol(String unit) {
        return switch (unit) {
            case "eplus" -> "e";
            case "c_light" -> "c";
            case "hbar_Planck" -> "hbar";
            case "millielectronvolt" -> "meV";
            case "picobarn" -> "pb";
            case "nanobarn" -> "nb";
            case "microbarn" -> "ub";
            case "millibarn" -> "mb";
            case "nanoampere" -> "nA";
            case "microampere" -> "uA";
            case "kilogauss" -> "kG";
            case "gauss" -> "G";
            case "tesla" -> "T";
            case "coulomb" -> "C";
            case "kelvin" -> "K";
            case "mole" -> "mol";
            case "candela" -> "cd";
            case "farad" -> "F";
            case "henry" -> "H";
            case "weber" -> "Wb";
            case "ohm" -> "ohm";
            case "microgray" -> "uGy";
            case "milligray" -> "mGy";
            case "gray" -> "Gy";
            case "kilogray" -> "kGy";
            case "minute" -> "min";
            case "hour" -> "h";
            case "day" -> "d";
            case "year" -> "yr";
            case "liter" -> "L";
            default -> unit;
        };
    }

    /** A number with 12 significant digits, no trailing zeros. */
    public static String num(double v) {
        if (v == 0) return "0";
        if (!Double.isFinite(v)) return Double.toString(v);
        String s = String.format(Locale.ROOT, "%.12g", v);
        String exp = "";
        final int e = s.indexOf('e');
        if (e >= 0) {
            exp = s.substring(e);
            s = s.substring(0, e);
        }
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        }
        if (!exp.isEmpty()) {
            final int x = Integer.parseInt(exp.substring(1));
            exp = "e" + (x < 0 ? "-" : "+") + (Math.abs(x) < 10 ? "0" : "") + Math.abs(x);
        }
        return s + exp;
    }

    /** The names the Evaluator knows, units and constants included, with their values in CLHEP's units. */
    public Map<String, Double> names() {
        final Map<String, Double> out = new LinkedHashMap<>();
        for (String n : base.names()) out.put(n, base.value(n));
        return out;
    }
}

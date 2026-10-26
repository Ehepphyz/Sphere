package com.sphere.core.geant4;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a person types for a value, made into a model expression: "12" in
 * the default unit, "12cm" and "12 cm" as 12*cm, "45" in an angle as 45*deg,
 * a decimal comma as a point, and expressions naming variables kept as typed.
 */
public final class Inputs {

    private Inputs() {
    }

    private static final Pattern UNIT_AFTER_NUMBER = Pattern.compile(
        "(?<![A-Za-z_0-9.])([0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)\\s*([A-Za-z_][A-Za-z_0-9]*(?:/[A-Za-z_][A-Za-z_0-9]*)?)");
    private static final Pattern NUMBER = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?");

    public static String normalize(String typed, Units.Category c, String defaultUnit) {
        String t = typed.trim();
        if (t.matches("[-+]?[0-9]+,[0-9]+.*")) {
            t = t.replaceFirst(",", ".");
        }
        final Matcher m = UNIT_AFTER_NUMBER.matcher(t);
        t = m.replaceAll(r -> {
            final String unit = r.group(2);
            final String[] parts = unit.split("/");
            boolean known = true;
            for (String p : parts) {
                known &= Units.isReserved(p);
            }
            return Matcher.quoteReplacement(known ? r.group(1) + "*" + unit : r.group(0));
        });
        if (NUMBER.matcher(t).matches()) {
            return switch (c) {
                case ANGLE -> t + "*deg";
                case LENGTH, AREA, VOLUME -> t + "*" + (defaultUnit == null ? "mm" : defaultUnit);
                case DENSITY -> t + "*g/cm3";
                case ENERGY -> t + "*MeV";
                default -> t;
            };
        }
        try {
            final Expr e = Expr.parse(t);
            if (!e.mentionsUnit() && e.variables().isEmpty()) {
                return switch (c) {
                    case ANGLE -> "(" + t + ")*deg";
                    case LENGTH -> "(" + t + ")*" + (defaultUnit == null ? "mm" : defaultUnit);
                    default -> t;
                };
            }
        } catch (GeometryException ignored) {
            // left as typed; evaluation reports it
        }
        return t;
    }

    /** True when the text reads as a value (number, expression), not as a name. */
    public static boolean looksNumeric(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        final char c = s.charAt(0);
        return Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == '(';
    }
}

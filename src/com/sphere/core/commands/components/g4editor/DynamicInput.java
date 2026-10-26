package com.sphere.components.g4editor;

import com.sphere.core.geant4.Expr;
import com.sphere.core.geant4.GeometryException;
import com.sphere.core.geant4.Units;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keyboard entry while drawing, the "dynamic input" of CAD programs: the
 * fields of the current step float by the cursor and follow the mouse until
 * a key is typed. Then the typed text wins: digits, units ("12 cm", "12cm"),
 * expressions and the document's variables ("2*Rin + 5 mm"). Tab locks the
 * field and moves on, Enter takes the step with what was typed, Escape
 * cancels the tool.
 */
final class DynamicInput {

    static final class Field {
        final String label;
        final Units.Category category;
        double mouse;
        String typed;
        boolean locked;

        Field(String label, Units.Category category) {
            this.label = label;
            this.category = category;
        }
    }

    final List<Field> fields = new ArrayList<>();
    int active;
    String error;

    void set(Field... fs) {
        fields.clear();
        for (Field f : fs) {
            fields.add(f);
        }
        active = 0;
        error = null;
    }

    void clear() {
        fields.clear();
        active = 0;
        error = null;
    }

    boolean isEmpty() {
        return fields.isEmpty();
    }

    Field field(int i) {
        return i < fields.size() ? fields.get(i) : null;
    }

    boolean typing() {
        for (Field f : fields) {
            if (f.typed != null) {
                return true;
            }
        }
        return false;
    }

    /** Takes a key; true when it belonged to the input. */
    boolean key(KeyEvent e) {
        if (fields.isEmpty()) {
            return false;
        }
        final Field f = fields.get(active);
        final int code = e.getKeyCode();
        if (e.getID() == KeyEvent.KEY_PRESSED) {
            if (code == KeyEvent.VK_TAB) {
                if (f.typed != null) {
                    f.locked = true;
                }
                active = (active + (e.isShiftDown() ? fields.size() - 1 : 1)) % fields.size();
                return true;
            }
            if (code == KeyEvent.VK_BACK_SPACE) {
                if (f.typed != null && !f.typed.isEmpty()) {
                    f.typed = f.typed.substring(0, f.typed.length() - 1);
                    if (f.typed.isEmpty()) {
                        f.typed = null;
                        f.locked = false;
                    }
                    error = null;
                    return true;
                }
                return f.typed != null;
            }
            return false;
        }
        if (e.getID() == KeyEvent.KEY_TYPED) {
            final char c = e.getKeyChar();
            if (c == '\t' || c == '\b' || c == '\n' || c == 27 || Character.isISOControl(c)) {
                return c == '\t' || c == '\b';
            }
            if (e.isControlDown() || e.isAltDown()) {
                return false;
            }
            // Typing starts with a digit, a sign, a point, a parenthesis or '='; then anything.
            if (f.typed == null && !(Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == '('
                || c == '=')) {
                return false;
            }
            if (f.typed == null) {
                f.typed = c == '=' ? "" : String.valueOf(c);
            } else {
                f.typed += c;
            }
            error = null;
            return true;
        }
        return false;
    }

    /** The value of a field, typed or from the mouse, in internal units. */
    double value(int i, Expr.Scope scope, String lengthUnit) {
        final Field f = fields.get(i);
        if (f.typed == null || f.typed.isBlank()) {
            return f.mouse;
        }
        try {
            return Expr.eval(normalize(f.typed, f.category, lengthUnit), scope);
        } catch (GeometryException e) {
            error = f.label + ": " + e.getMessage();
            return f.mouse;
        }
    }

    /** The expression a field gives the model: what was typed (variables kept), or the mouse value. */
    String expr(int i, String lengthUnit) {
        final Field f = fields.get(i);
        if (f.typed != null && !f.typed.isBlank()) {
            return normalize(f.typed, f.category, lengthUnit);
        }
        return format(f.mouse, f.category, lengthUnit);
    }

    boolean valid(int i, Expr.Scope scope, String unit) {
        final Field f = fields.get(i);
        if (f.typed == null || f.typed.isBlank()) {
            return true;
        }
        try {
            Expr.eval(normalize(f.typed, f.category, unit), scope);
            return true;
        } catch (GeometryException e) {
            return false;
        }
    }

    static String format(double v, Units.Category c, String lengthUnit) {
        if (c == Units.Category.ANGLE) {
            return Units.num(Math.rint(Math.toDegrees(v) * 1e6) / 1e6) + "*deg";
        }
        if (c == Units.Category.LENGTH) {
            final double u = Units.Category.LENGTH.unitValue(lengthUnit);
            return Units.num(Math.rint(v / u * 1e6) / 1e6) + "*" + lengthUnit;
        }
        return Units.num(v);
    }

    /**
     * "12" -> "12*mm" in the default unit, "12cm" and "12 cm" -> "12*cm",
     * "45" in an angle field -> "45*deg"; an expression naming a variable is
     * kept as typed, since the variable carries its unit.
     */
    static String normalize(String typed, Units.Category c, String lengthUnit) {
        String t = typed.trim().replace(',', '.');
        // number followed by a unit name, with or without a space
        t = UNIT_AFTER_NUMBER.matcher(t).replaceAll(m -> Units.isReserved(m.group(2))
            ? m.group(1) + "*" + m.group(2) : java.util.regex.Matcher.quoteReplacement(m.group(0)));
        if (t.matches("[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?")) {
            if (c == Units.Category.ANGLE) {
                return t + "*deg";
            }
            if (c == Units.Category.LENGTH) {
                return t + "*" + lengthUnit;
            }
            return t;
        }
        try {
            final Expr e = Expr.parse(t);
            if (!e.mentionsUnit() && e.variables().isEmpty()) {
                if (c == Units.Category.ANGLE) {
                    return "(" + t + ")*deg";
                }
                if (c == Units.Category.LENGTH) {
                    return "(" + t + ")*" + lengthUnit;
                }
            }
        } catch (GeometryException ignored) {
            // shown as invalid
        }
        return t;
    }

    private static final java.util.regex.Pattern UNIT_AFTER_NUMBER = java.util.regex.Pattern.compile(
        "(?<![A-Za-z_0-9.])([0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)\\s*([A-Za-z_][A-Za-z_0-9]*)");

    /** What the HUD shows for a field. */
    String display(int i, String unit) {
        final Field f = fields.get(i);
        if (f.typed != null) {
            return f.typed + (i == active ? "|" : "");
        }
        return Units.best(f.mouse, f.category);
    }
}

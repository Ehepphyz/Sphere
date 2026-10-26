package com.sphere.core.geant4;

/**
 * A named value of the document: GDML's constant, variable, quantity and
 * expression. Every expression of the model may name it, which is what makes
 * a design parametric: change a define, and every solid and placement that
 * reads it follows.
 */
public final class Define {

    public enum Kind { CONSTANT, VARIABLE, QUANTITY, EXPRESSION }

    String name;
    Kind kind;
    String expr;
    /** For a quantity: the unit name its value is multiplied by, e.g. "cm". */
    String unit;
    /** For a quantity: GDML's type attribute, e.g. "length". */
    String type;
    double value;
    String error;
    String comment;

    public Define(String name, Kind kind, String expr) {
        this.name = name;
        this.kind = kind;
        this.expr = expr;
    }

    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    public String expr() {
        return expr;
    }

    public String unit() {
        return unit;
    }

    public String type() {
        return type;
    }

    public double value() {
        return value;
    }

    public String error() {
        return error;
    }

    public String comment() {
        return comment;
    }

    public void setExpr(String e) {
        this.expr = e;
    }

    public void setKind(Kind k) {
        this.kind = k;
    }

    public void setUnit(String u) {
        this.unit = u;
    }

    public void setType(String t) {
        this.type = t;
    }

    public void setComment(String c) {
        this.comment = c;
    }

    /** The full expression, with a quantity's unit applied. */
    public String effectiveExpr() {
        if (kind == Kind.QUANTITY && unit != null && !unit.isBlank()) {
            return "(" + expr + ")*" + unit;
        }
        return expr;
    }
}

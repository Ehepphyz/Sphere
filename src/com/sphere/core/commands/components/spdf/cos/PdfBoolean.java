package com.sphere.components.spdf.cos;

public final class PdfBoolean implements PdfObject {

    public static final PdfBoolean TRUE = new PdfBoolean(true);
    public static final PdfBoolean FALSE = new PdfBoolean(false);

    public final boolean value;

    private PdfBoolean(boolean value) {
        this.value = value;
    }

    public static PdfBoolean of(boolean b) {
        return b ? TRUE : FALSE;
    }

    @Override
    public PdfObject copy() {
        return this;
    }

    @Override
    public String toString() {
        return value ? "true" : "false";
    }
}

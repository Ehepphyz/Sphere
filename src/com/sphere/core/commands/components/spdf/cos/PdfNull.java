package com.sphere.components.spdf.cos;

/** The null object, also what a missing or dangling reference resolves to. */
public final class PdfNull implements PdfObject {

    public static final PdfNull NULL = new PdfNull();

    private PdfNull() {
    }

    @Override
    public PdfObject copy() {
        return this;
    }

    @Override
    public String toString() {
        return "null";
    }
}

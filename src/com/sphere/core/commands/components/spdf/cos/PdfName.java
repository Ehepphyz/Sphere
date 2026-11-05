package com.sphere.components.spdf.cos;

import java.util.concurrent.ConcurrentHashMap;

/** A name, interned: two names with the same text are the same object. */
public final class PdfName implements PdfObject, Comparable<PdfName> {

    private static final ConcurrentHashMap<String, PdfName> NAMES = new ConcurrentHashMap<>();

    public final String name;

    private PdfName(String name) {
        this.name = name;
    }

    public static PdfName of(String name) {
        PdfName n = NAMES.get(name);
        if (n == null) {
            n = NAMES.computeIfAbsent(name, PdfName::new);
        }
        return n;
    }

    public static final PdfName TYPE = of("Type");
    public static final PdfName SUBTYPE = of("Subtype");
    public static final PdfName LENGTH = of("Length");
    public static final PdfName FILTER = of("Filter");
    public static final PdfName DECODE_PARMS = of("DecodeParms");
    public static final PdfName RESOURCES = of("Resources");
    public static final PdfName CONTENTS = of("Contents");
    public static final PdfName FONT = of("Font");
    public static final PdfName XOBJECT = of("XObject");
    public static final PdfName PARENT = of("Parent");
    public static final PdfName KIDS = of("Kids");
    public static final PdfName COUNT = of("Count");

    @Override
    public PdfObject copy() {
        return this;
    }

    @Override
    public int compareTo(PdfName o) {
        return name.compareTo(o.name);
    }

    @Override
    public String toString() {
        return "/" + name;
    }
}

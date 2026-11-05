package com.sphere.components.spdf.cos;

/** An indirect reference, which knows the document it resolves in. */
public final class PdfRef implements PdfObject {

    public final int num;
    public final int gen;
    final PdfDocument doc;

    public PdfRef(int num, int gen, PdfDocument doc) {
        this.num = num;
        this.gen = gen;
        this.doc = doc;
    }

    public PdfDocument document() {
        return doc;
    }

    /** The object referred to; null when it does not exist. */
    public PdfObject resolve() {
        return doc == null ? PdfNull.NULL : doc.getObject(num);
    }

    @Override
    public PdfObject copy() {
        return this;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PdfRef r && r.num == num && r.gen == gen && r.doc == doc;
    }

    @Override
    public int hashCode() {
        return num * 31 + gen;
    }

    @Override
    public String toString() {
        return num + " " + gen + " R";
    }

    /** Follows references until a direct object (at most a few hops, against cycles). */
    public static PdfObject deref(PdfObject o) {
        int hops = 0;
        while (o instanceof PdfRef r && hops++ < 32) {
            o = r.resolve();
        }
        return o == null ? PdfNull.NULL : o;
    }
}

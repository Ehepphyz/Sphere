package com.sphere.components.spdf.cos;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** An array. {@link #get} resolves references, {@link #getRaw} does not. */
public final class PdfArray implements PdfObject, Iterable<PdfObject> {

    private final ArrayList<PdfObject> items;

    public PdfArray() {
        items = new ArrayList<>();
    }

    public PdfArray(List<? extends PdfObject> list) {
        items = new ArrayList<>(list);
    }

    public static PdfArray of(PdfObject... objects) {
        return new PdfArray(List.of(objects));
    }

    public static PdfArray ofNumbers(double... values) {
        PdfArray a = new PdfArray();
        for (double v : values) {
            a.add(PdfNumber.of(v));
        }
        return a;
    }

    public int size() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public PdfObject get(int i) {
        return i < 0 || i >= items.size() ? PdfNull.NULL : PdfRef.deref(items.get(i));
    }

    public PdfObject getRaw(int i) {
        return i < 0 || i >= items.size() ? PdfNull.NULL : items.get(i);
    }

    public void add(PdfObject o) {
        items.add(o == null ? PdfNull.NULL : o);
    }

    public void add(int index, PdfObject o) {
        items.add(index, o == null ? PdfNull.NULL : o);
    }

    public void set(int i, PdfObject o) {
        items.set(i, o == null ? PdfNull.NULL : o);
    }

    public PdfObject remove(int i) {
        return items.remove(i);
    }

    public boolean removeRaw(PdfObject o) {
        return items.remove(o);
    }

    public void clear() {
        items.clear();
    }

    public List<PdfObject> rawList() {
        return items;
    }

    public float getFloat(int i, float def) {
        return get(i) instanceof PdfNumber n ? n.floatValue() : def;
    }

    public double getDouble(int i, double def) {
        return get(i) instanceof PdfNumber n ? n.doubleValue() : def;
    }

    public int getInt(int i, int def) {
        return get(i) instanceof PdfNumber n ? n.intValue() : def;
    }

    public String getName(int i) {
        return get(i) instanceof PdfName n ? n.name : null;
    }

    public PdfDict getDict(int i) {
        return get(i) instanceof PdfDict d ? d : null;
    }

    public PdfArray getArray(int i) {
        return get(i) instanceof PdfArray a ? a : null;
    }

    public float[] toFloats() {
        float[] f = new float[items.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = getFloat(i, 0);
        }
        return f;
    }

    public double[] toDoubles() {
        double[] f = new double[items.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = getDouble(i, 0);
        }
        return f;
    }

    @Override
    public Iterator<PdfObject> iterator() {
        List<PdfObject> resolved = new ArrayList<>(items.size());
        for (PdfObject o : items) {
            resolved.add(PdfRef.deref(o));
        }
        return resolved.iterator();
    }

    @Override
    public PdfArray copy() {
        PdfArray a = new PdfArray();
        for (PdfObject o : items) {
            a.items.add(o.copy());
        }
        return a;
    }

    @Override
    public String toString() {
        return items.toString();
    }
}

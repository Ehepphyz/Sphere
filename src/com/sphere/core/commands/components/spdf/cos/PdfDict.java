package com.sphere.components.spdf.cos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** A dictionary, its keys in the order read. {@link #get} resolves references, {@link #getRaw} does not. */
public class PdfDict implements PdfObject {

    protected final LinkedHashMap<PdfName, PdfObject> map = new LinkedHashMap<>();

    public PdfDict() {
    }

    public int size() {
        return map.size();
    }

    public PdfObject get(PdfName key) {
        PdfObject o = map.get(key);
        return o == null ? PdfNull.NULL : PdfRef.deref(o);
    }

    public PdfObject get(String key) {
        return get(PdfName.of(key));
    }

    /** The first of several keys present (abbreviated inline-image keys, for one). */
    public PdfObject get(String key, String alternate) {
        PdfObject o = get(key);
        return o == PdfNull.NULL ? get(alternate) : o;
    }

    public PdfObject getRaw(PdfName key) {
        PdfObject o = map.get(key);
        return o == null ? PdfNull.NULL : o;
    }

    public PdfObject getRaw(String key) {
        return getRaw(PdfName.of(key));
    }

    public boolean has(String key) {
        PdfObject o = map.get(PdfName.of(key));
        return o != null && o != PdfNull.NULL;
    }

    public PdfDict put(PdfName key, PdfObject value) {
        if (value == null || value == PdfNull.NULL) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
        return this;
    }

    public PdfDict put(String key, PdfObject value) {
        return put(PdfName.of(key), value);
    }

    public PdfDict put(String key, String nameValue) {
        return put(PdfName.of(key), PdfName.of(nameValue));
    }

    public PdfDict put(String key, double number) {
        return put(PdfName.of(key), PdfNumber.of(number));
    }

    public PdfObject remove(String key) {
        return map.remove(PdfName.of(key));
    }

    public Set<PdfName> keys() {
        return map.keySet();
    }

    public Set<Map.Entry<PdfName, PdfObject>> entries() {
        return map.entrySet();
    }

    public PdfDict getDict(String key) {
        return get(key) instanceof PdfDict d ? d : null;
    }

    public PdfArray getArray(String key) {
        return get(key) instanceof PdfArray a ? a : null;
    }

    public PdfStream getStream(String key) {
        return get(key) instanceof PdfStream s ? s : null;
    }

    public String getName(String key) {
        return get(key) instanceof PdfName n ? n.name : null;
    }

    public String getName(String key, String def) {
        String n = getName(key);
        return n == null ? def : n;
    }

    public boolean isType(String type) {
        return type.equals(getName("Type"));
    }

    public int getInt(String key, int def) {
        return get(key) instanceof PdfNumber n ? n.intValue() : def;
    }

    public long getLong(String key, long def) {
        return get(key) instanceof PdfNumber n ? n.longValue() : def;
    }

    public float getFloat(String key, float def) {
        return get(key) instanceof PdfNumber n ? n.floatValue() : def;
    }

    public boolean getBool(String key, boolean def) {
        return get(key) instanceof PdfBoolean b ? b.value : def;
    }

    /** A text string's value, or null. */
    public String getText(String key) {
        return get(key) instanceof PdfString s ? s.text() : null;
    }

    public PdfString getString(String key) {
        return get(key) instanceof PdfString s ? s : null;
    }

    public float[] getFloats(String key) {
        PdfArray a = getArray(key);
        return a == null ? null : a.toFloats();
    }

    /** Copies this dictionary's entries into another one (shallow: the values are shared). */
    public void copyInto(PdfDict target) {
        for (Map.Entry<PdfName, PdfObject> e : map.entrySet()) {
            target.map.put(e.getKey(), e.getValue());
        }
    }

    @Override
    public PdfDict copy() {
        PdfDict d = new PdfDict();
        for (Map.Entry<PdfName, PdfObject> e : map.entrySet()) {
            d.map.put(e.getKey(), e.getValue().copy());
        }
        return d;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("<<");
        for (Map.Entry<PdfName, PdfObject> e : map.entrySet()) {
            sb.append(e.getKey()).append(' ').append(e.getValue()).append(' ');
        }
        return sb.append(">>").toString();
    }
}

package com.sphere.core.rootio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An object read from a ROOT file: its class, the version it was written
 * with, and its members by name, those of its base classes included (a
 * TBranchElement holds fName, from TNamed, as well as fClassName).
 *
 * <p>Members are boxed numbers (Integer, Long, Double, Float, Short, Byte,
 * Boolean), Strings, arrays of primitives, Lists (STL collections, TObjArray,
 * TList) and RObjects.
 */
public final class RObject {

    public final String className;
    public final int version;
    public final Map<String, Object> members = new LinkedHashMap<>();

    public RObject(String className, int version) {
        this.className = className;
        this.version = version;
    }

    public Object get(String name) {
        return members.get(name);
    }

    public boolean has(String name) {
        return members.containsKey(name);
    }

    public String string(String name) {
        final Object v = members.get(name);
        return v == null ? "" : v.toString();
    }

    public long number(String name) {
        final Object v = members.get(name);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        return 0;
    }

    public int integer(String name) {
        return (int) number(name);
    }

    public double real(String name) {
        final Object v = members.get(name);
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(String name) {
        final Object v = members.get(name);
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public RObject object(String name) {
        return members.get(name) instanceof RObject o ? o : null;
    }

    /** A long array member (fBasketSeek), widened from whatever it was stored as. */
    public long[] longs(String name) {
        final Object v = members.get(name);
        if (v instanceof long[] a) return a;
        if (v instanceof int[] a) {
            final long[] out = new long[a.length];
            for (int i = 0; i < a.length; i++) out[i] = a[i];
            return out;
        }
        return new long[0];
    }

    public int[] ints(String name) {
        final Object v = members.get(name);
        if (v instanceof int[] a) return a;
        if (v instanceof long[] a) {
            final int[] out = new int[a.length];
            for (int i = 0; i < a.length; i++) out[i] = (int) a[i];
            return out;
        }
        return new int[0];
    }

    @Override
    public String toString() {
        return className + " v" + version + " " + members.keySet();
    }
}

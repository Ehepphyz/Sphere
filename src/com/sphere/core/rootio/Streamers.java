package com.sphere.core.rootio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The class descriptions a ROOT file carries (its TList of TStreamerInfo):
 * for every class written, its members in the order they were streamed, with
 * their types. Everything else in the file is read by following them.
 */
public final class Streamers {

    /* The element types of TVirtualStreamerInfo. */
    public static final int K_BASE = 0;
    public static final int K_CHAR = 1;
    public static final int K_SHORT = 2;
    public static final int K_INT = 3;
    public static final int K_LONG = 4;
    public static final int K_FLOAT = 5;
    public static final int K_COUNTER = 6;
    public static final int K_CHARSTAR = 7;
    public static final int K_DOUBLE = 8;
    public static final int K_DOUBLE32 = 9;
    public static final int K_LEGACYCHAR = 10;
    public static final int K_UCHAR = 11;
    public static final int K_USHORT = 12;
    public static final int K_UINT = 13;
    public static final int K_ULONG = 14;
    public static final int K_BITS = 15;
    public static final int K_LONG64 = 16;
    public static final int K_ULONG64 = 17;
    public static final int K_BOOL = 18;
    public static final int K_FLOAT16 = 19;
    public static final int K_OFFSET_L = 20;
    public static final int K_OFFSET_P = 40;
    public static final int K_OBJECT = 61;
    public static final int K_ANY = 62;
    public static final int K_OBJECTP = 63;
    public static final int K_OBJECTPP = 64;
    public static final int K_TSTRING = 65;
    public static final int K_TOBJECT = 66;
    public static final int K_TNAMED = 67;
    public static final int K_ANYP = 68;
    public static final int K_ANYPP = 69;
    public static final int K_STL = 300;
    public static final int K_STLSTRING = 365;
    public static final int K_STREAMER = 500;
    public static final int K_STREAMLOOP = 501;

    /** One member of a class: TStreamerElement and what its subclass adds. */
    public record Element(String kind, String name, String title, int type, int size, int arrayLength, int arrayDim,
                          int[] maxIndex, String typeName, int baseVersion, String countName, String countClass,
                          int stlType, int ctype, RObject raw) {
        public boolean isBase() {
            return kind.equals("TStreamerBase");
        }

        public boolean isSTL() {
            return kind.equals("TStreamerSTL") || kind.equals("TStreamerSTLstring");
        }
    }

    /** One class at one version. */
    public record Info(String name, String title, long checksum, int version, List<Element> elements, RObject raw) {
        public Element element(int index) {
            return index >= 0 && index < elements.size() ? elements.get(index) : null;
        }

        /** The element of that name, bases aside. */
        public Element element(String member) {
            for (Element e : elements) if (!e.isBase() && e.name().equals(member)) return e;
            return null;
        }
    }

    private final Map<String, Map<Integer, Info>> byName = new LinkedHashMap<>();
    /** The items of the list as read, in order: the class descriptions and the list of rules. */
    private final List<Object> items = new ArrayList<>();
    private final Map<Long, Info> byChecksum = new HashMap<>();

    public void add(Info info) {
        byName.computeIfAbsent(info.name(), k -> new LinkedHashMap<>()).put(info.version(), info);
        if (info.checksum() != 0) byChecksum.put(info.checksum(), info);
    }

    /** The class at that version; else at its only or latest version. */
    public Info find(String className, int version) {
        final Map<Integer, Info> versions = byName.get(className);
        if (versions == null) return null;
        final Info exact = versions.get(version);
        if (exact != null) return exact;
        Info latest = null;
        for (Info i : versions.values()) if (latest == null || i.version() > latest.version()) latest = i;
        return latest;
    }

    public Info byChecksum(long checksum) {
        return byChecksum.get(checksum);
    }

    public boolean knows(String className) {
        return byName.containsKey(className);
    }

    public List<Info> all() {
        final List<Info> out = new ArrayList<>();
        for (Map<Integer, Info> v : byName.values()) out.addAll(v.values());
        return out;
    }

    /**
     * The same TList written behind a key of another length: the bytes are the
     * same, but the tags of classes and objects met before count from the
     * start of the key, so each moves by the difference.
     */
    public static byte[] rebase(byte[] payload, int fromKeylen, int toKeylen) {
        if (fromKeylen == toKeylen) return payload;
        final RBuffer b = new RBuffer(payload, fromKeylen);
        b.tagLog = new ArrayList<>();
        new ObjectReader(new Streamers()).readClass(b, "TList");
        final byte[] out = payload.clone();
        final long delta = toKeylen - fromKeylen;
        for (int at : b.tagLog) {
            final long tag = ((out[at] & 0xffL) << 24) | ((out[at + 1] & 0xffL) << 16) | ((out[at + 2] & 0xffL) << 8) | (out[at + 3] & 0xffL);
            final long moved = (tag & RBuffer.CLASS_MASK) != 0
                ? ((tag & ~RBuffer.CLASS_MASK) + delta) | RBuffer.CLASS_MASK
                : tag + delta;
            out[at] = (byte) (moved >>> 24);
            out[at + 1] = (byte) (moved >>> 16);
            out[at + 2] = (byte) (moved >>> 8);
            out[at + 3] = (byte) moved;
        }
        return out;
    }

    /** Reads the TList of TStreamerInfo the file keeps at fSeekInfo. */
    public static Streamers read(byte[] payload, int keylen) {
        final Streamers s = new Streamers();
        final ObjectReader reader = new ObjectReader(s);
        final RBuffer b = new RBuffer(payload, keylen);
        final Object list = reader.readClass(b, "TList");
        if (list instanceof List<?> items) {
            for (Object o : items) if (o instanceof Info info) s.add(info);
            s.items.addAll(items);
            if (list instanceof RList r) s.listBits = r.bits;
        }
        return s;
    }

    private long listBits = RList.BITS;

    /** The items of the list as it was read: class descriptions, and ROOT's list of rules if it had one. */
    public List<Object> items() {
        return new ArrayList<>(items);
    }

    /**
     * The TList of these class descriptions as TFile::WriteStreamerInfo
     * writes it, for a key of that length.
     *
     * @param items class descriptions (and lists of rules) in the order to write them
     */
    public static byte[] write(List<Object> items, int keylen, long listBits) {
        final RList list = new RList();
        list.className = "TList";
        list.bits = listBits;
        list.addAll(items);
        final WBuffer b = new WBuffer(keylen);
        b.mapObject(list, 1L);
        new ObjectWriter(new Streamers(), java.util.Set.of()).writeClass(b, "TList", list);
        return b.toByteArray();
    }

    /** The bits of the list these descriptions were read from (a TList on the stack in ROOT: kNotDeleted alone). */
    public long listBits() {
        return listBits;
    }
}

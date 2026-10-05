package com.sphere.core.rootio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes objects into a ROOT record the way TBufferFile does, the inverse of
 * {@link ObjectReader}: by the class's TStreamerInfo (WriteClassBuffer), and
 * by hand for the classes ROOT streams with code of its own (TObject, TNamed,
 * TString, TObjArray, TList, TArray*, and a TBasket kept in memory).
 *
 * <p>A class without ClassDef (the HepMC3 data classes) is a foreign class:
 * WriteVersion writes it as version 0 followed by its checksum. Collections
 * of objects are written member-wise, as ROOT does by default.
 */
public final class ObjectWriter {

    /** The version ROOT gives STL collections (TStreamerInfo's, 9, in the files described). */
    public static final int STL_VERSION = com.sphere.Sphere.ROOT_STL_VERSION;

    private final Streamers streamers;
    private final Set<String> foreignPrefixes;

    public ObjectWriter(Streamers streamers, Set<String> foreignPrefixes) {
        this.streamers = streamers;
        this.foreignPrefixes = foreignPrefixes;
    }

    /** ROOT's own classes without ClassDef, written as foreign ones (version 0 and checksum). */
    private static final Set<String> ROOT_FOREIGN = Set.of("ROOT::TIOFeatures");

    private boolean foreign(String className, Streamers.Info info) {
        if (info.version() > 1) return false;
        if (ROOT_FOREIGN.contains(className)) return true;
        for (String p : foreignPrefixes) if (className.startsWith(p)) return true;
        return false;
    }

    /* ---- WriteObjectAny ------------------------------------------------------ */

    /** A pointer: 0 for null, the tag of an object already written, or a class tag and the object. */
    public void writeObjectAny(WBuffer b, Object o) {
        if (o == null) {
            b.u32(0);
            return;
        }
        final Long seen = b.objects.get(o);
        if (seen != null) {
            b.u32(seen);
            return;
        }
        final String className = classOf(o);
        final int cntpos = b.reserveCount();
        final long beg = b.displacement() - 4;
        final Long cls = b.classes.get(className);
        if (cls != null) {
            b.u32(cls | RBuffer.CLASS_MASK);
        } else {
            final long at = b.displacement();
            b.u32(RBuffer.NEW_CLASS_TAG);
            b.cstring(className);
            b.classes.put(className, at + RBuffer.MAP_OFFSET);
        }
        b.objects.put(o, beg + RBuffer.MAP_OFFSET);
        writeClass(b, className, o);
        b.setCount(cntpos);
    }

    private static String classOf(Object o) {
        if (o instanceof RObject r) return r.className;
        if (o instanceof Collection c) return c.className();
        if (o instanceof RList r) return r.className;
        if (o instanceof Streamers.Info) return "TStreamerInfo";
        if (o instanceof Streamers.Element e) return e.kind();
        if (o instanceof String || o instanceof ObjString) return "TObjString";
        throw new IllegalArgumentException("no class for " + o.getClass().getSimpleName());
    }

    /** A TObjArray or TList written through a pointer. */
    public record Collection(String className, List<Object> items) {
    }

    /* ---- one object ------------------------------------------------------------ */

    @SuppressWarnings("unchecked")
    public void writeClass(WBuffer b, String className, Object o) {
        switch (className) {
            case "TObject" -> writeTObject(b, (RObject) o);
            case "TString" -> b.tstring(String.valueOf(o));
            case "TNamed" -> writeTNamed(b, (RObject) o);
            case "TObjArray" -> writeTObjArray(b, o instanceof Collection c ? c.items() : (List<Object>) o);
            case "TList", "THashList" -> writeTList(b, o instanceof Collection c ? c.items() : (List<Object>) o);
            case "TArrayC", "TArrayS", "TArrayI", "TArrayL", "TArrayL64", "TArrayF", "TArrayD" -> writeTArray(b, className, o);
            case "TBasket" -> writeTBasket(b, (RObject) o);
            case "TObjString" -> {
                if (o instanceof ObjString os) writeTObjString(b, os.value(), os.bits());
                else writeTObjString(b, String.valueOf(o), RList.BITS);
            }
            case "TStreamerInfo" -> writeStreamerInfo(b, (Streamers.Info) o);
            case "TStreamerBase", "TStreamerBasicType", "TStreamerBasicPointer", "TStreamerLoop", "TStreamerObject",
                 "TStreamerObjectPointer", "TStreamerObjectAny", "TStreamerObjectAnyPointer", "TStreamerString",
                 "TStreamerSTL", "TStreamerSTLstring" -> writeStreamerElement(b, (Streamers.Element) o);
            default -> writeByStreamer(b, className, (RObject) o, ((RObject) o).version);
        }
    }

    private void writeByStreamer(WBuffer b, String className, RObject o, int version) {
        final Streamers.Info info = streamers.find(className, version);
        if (info == null) throw new IllegalStateException("no description of " + className + " to write it with");
        final int cntpos = b.reserveCount();
        if (foreign(className, info)) {
            b.i16(0);
            b.u32(info.checksum());
        } else {
            b.i16(info.version());
        }
        writeMembers(b, info, o);
        b.setCount(cntpos);
    }

    public void writeMembers(WBuffer b, Streamers.Info info, RObject o) {
        for (Streamers.Element e : info.elements()) writeElement(b, e, e.isBase() ? o : o.get(e.name()), o);
    }

    public void writeElement(WBuffer b, Streamers.Element e, Object value, RObject owner) {
        final int t = e.type();
        if (e.isBase()) {
            switch (e.name()) {
                case "TObject" -> writeTObject(b, owner);
                case "TNamed" -> writeTNamed(b, owner);
                default -> writeByStreamer(b, e.name(), owner, e.baseVersion());
            }
            return;
        }
        if (t == Streamers.K_STLSTRING || e.kind().equals("TStreamerSTLstring")) {
            b.tstring(value == null ? "" : value.toString());
            return;
        }
        if (e.kind().equals("TStreamerSTL")) {
            writeSTL(b, e.typeName(), e.ctype(), value);
            return;
        }
        if (t > 0 && t < Streamers.K_OFFSET_L) {
            writeBasic(b, t, value);
            return;
        }
        if (t > Streamers.K_OFFSET_L && t < Streamers.K_OFFSET_P) {
            writeBasicArray(b, t - Streamers.K_OFFSET_L, value, Math.max(0, e.arrayLength()));
            return;
        }
        if (t > Streamers.K_OFFSET_P && t < Streamers.K_OBJECT) {
            final int count = owner == null ? 0 : owner.integer(e.countName());
            if (value == null || count <= 0) {
                b.i8(0);
                return;
            }
            b.i8(1);
            writeBasicArray(b, t - Streamers.K_OFFSET_P, value, count);
            return;
        }
        switch (t) {
            case Streamers.K_OBJECT, Streamers.K_ANY -> writeClass(b, stripPointer(e.typeName()), value);
            case Streamers.K_OBJECTP, Streamers.K_ANYP, Streamers.K_OBJECTPP, Streamers.K_ANYPP -> writeObjectAny(b, value);
            case Streamers.K_TSTRING -> b.tstring(value == null ? "" : value.toString());
            case Streamers.K_TOBJECT -> writeTObject(b, (RObject) value);
            case Streamers.K_TNAMED -> writeTNamed(b, (RObject) value);
            default -> throw new IllegalStateException("member " + e.name() + " of type " + t + " is not written by this writer");
        }
    }

    private static String stripPointer(String typeName) {
        String s = typeName.strip();
        while (s.endsWith("*")) s = s.substring(0, s.length() - 1).strip();
        return s;
    }

    /* ---- numbers ------------------------------------------------------------------ */

    public void writeBasic(WBuffer b, int type, Object v) {
        final Number n = v instanceof Number num ? num : v instanceof Boolean bo ? (bo ? 1 : 0) : 0;
        switch (type) {
            case Streamers.K_CHAR, Streamers.K_LEGACYCHAR, Streamers.K_UCHAR -> b.i8(n.intValue());
            case Streamers.K_SHORT, Streamers.K_USHORT -> b.i16(n.intValue());
            case Streamers.K_INT, Streamers.K_COUNTER, Streamers.K_UINT, Streamers.K_BITS -> b.i32((int) n.longValue());
            case Streamers.K_LONG, Streamers.K_LONG64, Streamers.K_ULONG, Streamers.K_ULONG64 -> b.i64(n.longValue());
            case Streamers.K_FLOAT, Streamers.K_DOUBLE32, Streamers.K_FLOAT16 -> b.f32(n.floatValue());
            case Streamers.K_DOUBLE -> b.f64(n.doubleValue());
            case Streamers.K_BOOL -> b.bool(n.intValue() != 0);
            default -> throw new IllegalStateException("basic type " + type + " is not written by this writer");
        }
    }

    /** n values of an array (any primitive array), as the basic type says. */
    public void writeBasicArray(WBuffer b, int type, Object array, int n) {
        for (int i = 0; i < n; i++) writeBasic(b, type, element(array, i));
    }

    private static Object element(Object array, int i) {
        if (array instanceof int[] a) return i < a.length ? a[i] : 0;
        if (array instanceof long[] a) return i < a.length ? a[i] : 0L;
        if (array instanceof double[] a) return i < a.length ? a[i] : 0.0;
        if (array instanceof float[] a) return i < a.length ? a[i] : 0f;
        if (array instanceof short[] a) return i < a.length ? a[i] : (short) 0;
        if (array instanceof byte[] a) return i < a.length ? a[i] : (byte) 0;
        if (array instanceof boolean[] a) return i < a.length && a[i];
        if (array instanceof List<?> l) return i < l.size() ? l.get(i) : 0;
        return 0;
    }

    private static int length(Object array) {
        if (array instanceof int[] a) return a.length;
        if (array instanceof long[] a) return a.length;
        if (array instanceof double[] a) return a.length;
        if (array instanceof float[] a) return a.length;
        if (array instanceof short[] a) return a.length;
        if (array instanceof byte[] a) return a.length;
        if (array instanceof boolean[] a) return a.length;
        if (array instanceof List<?> l) return l.size();
        return 0;
    }

    /* ---- STL ---------------------------------------------------------------------------- */

    /**
     * An STL sequence with its header: numbers and strings element by element,
     * objects member by member (List of RObject), as ROOT writes them.
     */
    public void writeSTL(WBuffer b, String typeName, int ctype, Object value) {
        final String inner = ObjectReader.innerType(typeName);
        final int basic = ctype > 0 && ctype < Streamers.K_OFFSET_L ? ctype : ObjectReader.basicType(inner);
        final int cntpos = b.reserveCount();
        final int n = length(value);
        if (basic != 0) {
            b.i16(STL_VERSION);
            b.i32(n);
            writeBasicArray(b, basic, value, n);
        } else if (inner.equals("string") || inner.equals("std::string") || inner.equals("TString")) {
            b.i16(STL_VERSION);
            b.i32(n);
            for (int i = 0; i < n; i++) b.tstring(String.valueOf(element(value, i)));
        } else {
            final Streamers.Info info = streamers.find(inner, -1);
            if (info == null) throw new IllegalStateException("no description of " + inner + " to write it with");
            b.i16(STL_VERSION | RBuffer.STREAMED_MEMBER_WISE);
            if (foreign(inner, info)) {
                b.i16(0);
                b.u32(info.checksum());
            } else {
                b.i16(info.version());
            }
            b.i32(n);
            final List<RObject> objects = new ArrayList<>(n);
            for (int i = 0; i < n; i++) objects.add((RObject) element(value, i));
            writeMemberWise(b, info, objects);
        }
        b.setCount(cntpos);
    }

    private void writeMemberWise(WBuffer b, Streamers.Info info, List<RObject> objects) {
        for (Streamers.Element e : info.elements()) {
            if (e.isBase()) {
                final Streamers.Info base = streamers.find(e.name(), e.baseVersion());
                if (base != null) writeMemberWise(b, base, objects);
                continue;
            }
            for (RObject o : objects) writeElement(b, e, o.get(e.name()), o);
        }
    }

    /* ---- the classes ROOT streams by hand ------------------------------------------- */

    /** TObject: version 1 (no byte count), fUniqueID, fBits without the in-memory bits (as ROOT 6.20 kept them). */
    public void writeTObject(WBuffer b, RObject o) {
        b.i16(1);
        b.u32(o == null ? 0 : o.number("fUniqueID"));
        b.u32(o == null ? 0x03000000L : o.number("fBits"));
    }

    public void writeTNamed(WBuffer b, RObject o) {
        final int cntpos = b.reserveCount();
        b.i16(1);
        writeTObject(b, o);
        b.tstring(o.string("fName"));
        b.tstring(o.string("fTitle"));
        b.setCount(cntpos);
    }

    /** TObjArray: header, TObject, name, the slots up to the last filled one, the lower bound, the objects. */
    private void writeTObjArray(WBuffer b, List<Object> items) {
        final int cntpos = b.reserveCount();
        b.i16(3);
        writeCollectionHeader(b, items);
        int last = items.size() - 1;
        while (last >= 0 && items.get(last) == null) last--;
        b.i32(last + 1);
        b.i32(0);
        for (int i = 0; i <= last; i++) writeObjectAny(b, items.get(i));
        b.setCount(cntpos);
    }

    private void writeTList(WBuffer b, List<Object> items) {
        final int cntpos = b.reserveCount();
        b.i16(5);
        writeCollectionHeader(b, items);
        b.i32(items.size());
        for (int i = 0; i < items.size(); i++) {
            final Object o = items.get(i);
            if (o instanceof String str && items instanceof RList r && r.stringBits.containsKey(i)) {
                writeObjectAny(b, new ObjString(str, r.stringBits.get(i)));
            } else {
                writeObjectAny(b, o);
            }
            b.i8(0);
        }
        b.setCount(cntpos);
    }

    /** The collection's own TObject and name. */
    private void writeCollectionHeader(WBuffer b, List<Object> items) {
        b.i16(1);
        b.u32(0);
        b.u32(items instanceof RList r ? r.bits : RList.BITS);
        b.tstring(items instanceof RList r ? r.name : "");
    }

    private void writeTArray(WBuffer b, String className, Object array) {
        final int n = length(array);
        b.i32(n);
        final int type = switch (className) {
            case "TArrayC" -> Streamers.K_CHAR;
            case "TArrayS" -> Streamers.K_SHORT;
            case "TArrayI" -> Streamers.K_INT;
            case "TArrayL", "TArrayL64" -> Streamers.K_LONG64;
            case "TArrayF" -> Streamers.K_FLOAT;
            default -> Streamers.K_DOUBLE;
        };
        writeBasicArray(b, type, array, n);
    }

    /**
     * A basket still in memory, kept inside its branch (TBasket::Streamer with
     * its buffer): the key it would be, its fields, its entry offsets, and its
     * buffer (the key header it starts with, then the data).
     */
    private void writeTBasket(WBuffer b, RObject o) {
        b.i32(o.integer("fNbytes"));
        final int keyVersion = o.integer("fKeyVersion");
        b.i16(keyVersion);
        b.i32(o.integer("fObjlen"));
        b.u32(o.number("fDatime"));
        b.i16(o.integer("fKeylen"));
        b.i16(o.integer("fCycle"));
        if (keyVersion > 1000) {
            b.i64(o.number("fSeekKey"));
            b.i64(o.number("fSeekPdir"));
        } else {
            b.i32((int) o.number("fSeekKey"));
            b.i32((int) o.number("fSeekPdir"));
        }
        b.tstring("TBasket");
        b.tstring(o.string("fName"));
        b.tstring(o.string("fTitle"));
        b.i16(3);
        b.i32(o.integer("fBufferSize"));
        b.i32(o.integer("fNevBufSize"));
        final int nevBuf = o.integer("fNevBuf");
        b.i32(nevBuf);
        b.i32(o.integer("fLast"));
        final int[] offsets = (int[]) o.get("fEntryOffset");
        int flag = offsets != null && nevBuf > 0 ? 1 : 2;
        flag += 10;
        b.i8(flag);
        if (offsets != null && nevBuf > 0) {
            b.i32(nevBuf);
            for (int i = 0; i < nevBuf; i++) b.i32(offsets[i]);
        }
        b.bytes((byte[]) o.get("header"));
        b.bytes((byte[]) o.get("data"));
    }

    /* ---- class descriptions -------------------------------------------------------------- */

    private static int raw(RObject raw, String member, int otherwise) {
        return raw != null && raw.has(member) ? raw.integer(member) : otherwise;
    }

    private static long rawBits(RObject raw) {
        return raw != null && raw.has("fBits") ? raw.number("fBits") : RList.BITS;
    }

    /** A TNamed as a base: its header, its TObject, name and title. */
    private void writeNamed(WBuffer b, int version, long uniqueID, long bits, String name, String title) {
        final int cntpos = b.reserveCount();
        b.i16(version);
        b.i16(1);
        b.u32(uniqueID);
        b.u32(bits);
        b.tstring(name);
        b.tstring(title);
        b.setCount(cntpos);
    }

    /** A TObjString with bits of its own. */
    private record ObjString(String value, long bits) {
    }

    /** TObjString: its TObject and its string. */
    private void writeTObjString(WBuffer b, String s, long bits) {
        final int cntpos = b.reserveCount();
        b.i16(1);
        b.i16(1);
        b.u32(0);
        b.u32(bits);
        b.tstring(s);
        b.setCount(cntpos);
    }

    /** TStreamerInfo::Streamer: name and title, checksum, class version, the elements in a TObjArray. */
    private void writeStreamerInfo(WBuffer b, Streamers.Info info) {
        final RObject raw = info.raw();
        final int cntpos = b.reserveCount();
        b.i16(raw != null ? raw.version : 9);
        writeNamed(b, raw(raw, "namedVersion", 1), raw != null && raw.has("fUniqueID") ? raw.number("fUniqueID") : 0,
            rawBits(raw), info.name(), info.title());
        b.u32(info.checksum());
        b.i32(info.version());
        final RList elements = new RList();
        elements.bits = raw != null && raw.has("elementsBits") ? raw.number("elementsBits") : 0x02000000L;
        elements.name = raw != null && raw.has("elementsName") ? raw.string("elementsName") : "";
        elements.addAll(info.elements());
        writeObjectAny(b, elements);
        b.setCount(cntpos);
    }

    /**
     * A TStreamerElement of its kind (WriteClassBuffer of each): the element
     * part, then what the kind adds; a collection as kStreamer, the type ROOT
     * writes for it.
     */
    private void writeStreamerElement(WBuffer b, Streamers.Element e) {
        final RObject raw = e.raw();
        final String kind = e.kind();
        final int cntpos = b.reserveCount();
        b.i16(raw != null ? raw.version : defaultVersion(kind));
        if (kind.equals("TStreamerSTLstring")) {
            final int inner = b.reserveCount();
            b.i16(raw(raw, "stlVersion", 3));
            writeElementPart(b, e, raw);
            b.i32(e.stlType());
            b.i32(e.ctype());
            b.setCount(inner);
        } else {
            writeElementPart(b, e, raw);
            switch (kind) {
                case "TStreamerBase" -> {
                    if ((raw != null ? raw.version : 3) > 2) b.i32(e.baseVersion());
                }
                case "TStreamerBasicPointer", "TStreamerLoop" -> {
                    b.i32(raw(raw, "fCountVersion", 0));
                    b.tstring(e.countName());
                    b.tstring(e.countClass());
                }
                case "TStreamerSTL" -> {
                    b.i32(e.stlType());
                    b.i32(e.ctype());
                }
                default -> {
                }
            }
        }
        b.setCount(cntpos);
    }

    private void writeElementPart(WBuffer b, Streamers.Element e, RObject raw) {
        final int cntpos = b.reserveCount();
        final int version = raw(raw, "elementVersion", 4);
        b.i16(version);
        writeNamed(b, raw(raw, "namedVersion", 1), raw != null && raw.has("fUniqueID") ? raw.number("fUniqueID") : 0,
            rawBits(raw), e.name(), e.title());
        final int type = raw != null && raw.has("fType") ? raw.integer("fType")
            : e.kind().equals("TStreamerSTL") || e.kind().equals("TStreamerSTLstring") ? Streamers.K_STREAMER
            : e.type() == Streamers.K_BOOL && e.typeName().equals("bool") ? e.type() : e.type();
        b.i32(type);
        b.i32(e.size());
        b.i32(e.arrayLength());
        b.i32(e.arrayDim());
        final int[] maxIndex = e.maxIndex() == null ? new int[5] : e.maxIndex();
        if (version == 1) {
            b.i32(maxIndex.length);
            for (int v : maxIndex) b.i32(v);
        } else {
            for (int i = 0; i < 5; i++) b.i32(i < maxIndex.length ? maxIndex[i] : 0);
        }
        b.tstring(e.typeName());
        b.setCount(cntpos);
    }

    /** The versions ROOT 6.20 wrote the element classes with. */
    private static int defaultVersion(String kind) {
        return switch (kind) {
            case "TStreamerBase", "TStreamerSTL" -> 3;
            default -> 2;
        };
    }

    /** For callers building objects: an RObject of a class at a version, with its members. */
    public static RObject object(String className, int version, Map<String, Object> members) {
        final RObject o = new RObject(className, version);
        o.members.putAll(members);
        return o;
    }
}

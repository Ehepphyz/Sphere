package com.sphere.core.rootio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads objects out of a ROOT record the way TBufferFile does: by the
 * class's TStreamerInfo for every class streamed member by member, and by
 * hand for the classes ROOT streams with code of its own (TObject, TNamed,
 * TString, TList, TObjArray, TObjString, TArray*, TBasket, TDatime, and the
 * streamer descriptions themselves).
 *
 * <p>STL collections are read element by element, or member by member when
 * ROOT wrote them so (the kStreamedMemberWise bit of their version): all the
 * first members of the elements, then all the second ones, and so on.
 */
public final class ObjectReader {

    private static final int IS_REFERENCED = 1 << 4;

    private final Streamers streamers;

    public ObjectReader(Streamers streamers) {
        this.streamers = streamers;
    }

    public Streamers streamers() {
        return streamers;
    }

    /* ---- ReadObjectAny -------------------------------------------------------- */

    /** A pointer member: null, an object already read, or a new object behind a class tag. */
    public Object readObjectAny(RBuffer b) {
        final long beg = b.displacement();
        long bcnt = b.u32();
        int vers;
        long start = 0;
        long tag;
        if ((bcnt & RBuffer.BYTE_COUNT_MASK) == 0 || bcnt == RBuffer.NEW_CLASS_TAG) {
            vers = 0;
            tag = bcnt;
            bcnt = 0;
            if (b.tagLog != null && tag != 0 && tag != RBuffer.NEW_CLASS_TAG) b.tagLog.add(b.position() - 4);
        } else {
            vers = 1;
            start = b.displacement();
            tag = b.u32();
            bcnt &= ~RBuffer.BYTE_COUNT_MASK;
            if (b.tagLog != null && tag != 0 && tag != RBuffer.NEW_CLASS_TAG) b.tagLog.add(b.position() - 4);
        }
        if ((tag & RBuffer.CLASS_MASK) == 0) {
            if (tag == 0) return null;
            final Object known = b.refs.get(tag);
            if (known == null) {
                b.moveToDisplacement(beg + bcnt + 4);
                return null;
            }
            return known;
        }
        final String className;
        if (tag == RBuffer.NEW_CLASS_TAG) {
            className = b.cstring();
            b.refs.put(vers > 0 ? start + RBuffer.MAP_OFFSET : (long) b.refs.size() + 1, new RBuffer.ClassTag(className));
        } else {
            final Object c = b.refs.get(tag & ~RBuffer.CLASS_MASK);
            if (!(c instanceof RBuffer.ClassTag ct)) {
                b.moveToDisplacement(beg + bcnt + 4);
                return null;
            }
            className = ct.name();
        }
        final long objectKey = vers > 0 ? beg + RBuffer.MAP_OFFSET : (long) b.refs.size() + 1;
        // registered before its members are read, as ROOT does, so that a member
        // may point back at it (a branch's fBranchCount at the branch holding it)
        final Object obj = readClass(b, className, objectKey);
        b.refs.put(objectKey, obj);
        if (bcnt > 0) b.moveToDisplacement(beg + bcnt + 4);
        return obj;
    }

    /* ---- one object of a known class ------------------------------------------------ */

    /** An object of that class, its header included. */
    public Object readClass(RBuffer b, String className) {
        return readClass(b, className, -1);
    }

    /** The same, the object registered under a tag before its members are read. */
    private Object readClass(RBuffer b, String className, long refKey) {
        switch (className) {
            case "TObject":
                return readTObject(b);
            case "TString":
                return b.tstring();
            case "TNamed":
                return readTNamed(b);
            case "TList":
            case "THashList":
                return readTList(b);
            case "TObjArray":
                return readTObjArray(b);
            case "TObjString":
                return readTObjString(b);
            case "TArrayC":
            case "TArrayS":
            case "TArrayI":
            case "TArrayL":
            case "TArrayL64":
            case "TArrayF":
            case "TArrayD":
                return readTArray(b, className);
            case "TDatime":
                return b.u32();
            case "TBasket":
                return readTBasket(b);
            case "TStreamerInfo":
                return readStreamerInfo(b);
            case "TStreamerBase":
            case "TStreamerBasicType":
            case "TStreamerBasicPointer":
            case "TStreamerLoop":
            case "TStreamerObject":
            case "TStreamerObjectPointer":
            case "TStreamerObjectAny":
            case "TStreamerObjectAnyPointer":
            case "TStreamerString":
            case "TStreamerSTL":
            case "TStreamerSTLstring":
            case "TStreamerArtificial":
                return readStreamerElement(b, className);
            default:
                return readByStreamer(b, className, refKey);
        }
    }

    /** A class read by its TStreamerInfo: header, members, then on to where the header said it ends. */
    private Object readByStreamer(RBuffer b, String className, long refKey) {
        final RBuffer.Header h = b.header();
        Streamers.Info info = h.version() == 0 && h.checksum() != 0 ? streamers.byChecksum(h.checksum()) : null;
        if (info == null) info = streamers.find(className, h.version());
        final RObject o = new RObject(className, h.version());
        if (refKey >= 0) b.refs.put(refKey, o);
        if (info == null) {
            o.members.put("@unread", "no description of " + className + " v" + h.version() + " in the file");
            b.end(h);
            return o;
        }
        readMembers(b, info, o);
        b.end(h);
        return o;
    }

    /** Every member of a class description into an object (base members merged in). */
    public void readMembers(RBuffer b, Streamers.Info info, RObject into) {
        for (Streamers.Element e : info.elements()) {
            final Object v = readElement(b, e, into);
            if (e.isBase()) {
                if (v instanceof RObject base) into.members.putAll(base.members);
            } else {
                into.members.put(e.name(), v);
            }
        }
    }

    /** One member, of whatever kind its description says. */
    public Object readElement(RBuffer b, Streamers.Element e, RObject owner) {
        final int t = e.type();
        if (e.isBase()) return readClass(b, e.name());
        // std::string members are written as a TString is
        if (t == Streamers.K_STLSTRING || e.kind().equals("TStreamerSTLstring")) return b.tstring();
        if (e.kind().equals("TStreamerSTL")) return readSTL(b, e.typeName(), e.stlType(), e.ctype());
        if (t > 0 && t < Streamers.K_OFFSET_L) return readBasic(b, t, e.title());
        if (t > Streamers.K_OFFSET_L && t < Streamers.K_OFFSET_P) {
            return readBasicArray(b, t - Streamers.K_OFFSET_L, Math.max(e.arrayLength(), 0), e.title());
        }
        if (t > Streamers.K_OFFSET_P && t < Streamers.K_OBJECT) {
            final int n = owner == null ? 0 : owner.integer(e.countName());
            final byte isArray = b.i8();
            return readBasicArray(b, t - Streamers.K_OFFSET_P, isArray != 0 ? n : 0, e.title());
        }
        switch (t) {
            case Streamers.K_OBJECT:
            case Streamers.K_ANY:
                if (e.arrayLength() > 1) {
                    final List<Object> out = new ArrayList<>();
                    for (int i = 0; i < e.arrayLength(); i++) out.add(readClass(b, stripPointer(e.typeName())));
                    return out;
                }
                return readClass(b, stripPointer(e.typeName()));
            case Streamers.K_OBJECTP:
            case Streamers.K_ANYP:
            case Streamers.K_OBJECTPP:
            case Streamers.K_ANYPP: {
                // a pointer (63: never null, 64: may be null), or a fixed array of them
                if (e.arrayLength() <= 1) return readObjectAny(b);
                final List<Object> out = new ArrayList<>();
                for (int i = 0; i < e.arrayLength(); i++) out.add(readObjectAny(b));
                return out;
            }
            case Streamers.K_TSTRING:
                return b.tstring();
            case Streamers.K_TOBJECT:
                return readTObject(b);
            case Streamers.K_TNAMED:
                return readTNamed(b);
            case Streamers.K_CHARSTAR: {
                final int n = b.i32();
                return new String(b.bytes(Math.max(n, 0)), java.nio.charset.StandardCharsets.UTF_8);
            }
            default:
                throw new IllegalStateException("member " + e.name() + " of type " + t + " (" + e.typeName()
                    + ") is not read by this reader");
        }
    }

    private static String stripPointer(String typeName) {
        String s = typeName.strip();
        while (s.endsWith("*")) s = s.substring(0, s.length() - 1).strip();
        return s;
    }

    /* ---- numbers ------------------------------------------------------------------------ */

    /** A value of a basic type; Double32 and Float16 as their title's range says. */
    public Object readBasic(RBuffer b, int type, String title) {
        return switch (type) {
            case Streamers.K_CHAR, Streamers.K_LEGACYCHAR -> b.i8();
            case Streamers.K_SHORT -> b.i16();
            case Streamers.K_INT, Streamers.K_COUNTER -> b.i32();
            case Streamers.K_LONG, Streamers.K_LONG64, Streamers.K_ULONG, Streamers.K_ULONG64 -> b.i64();
            case Streamers.K_FLOAT -> b.f32();
            case Streamers.K_DOUBLE -> b.f64();
            case Streamers.K_DOUBLE32 -> readDouble32(b, title, false);
            case Streamers.K_FLOAT16 -> (float) readDouble32(b, title, true);
            case Streamers.K_UCHAR -> b.u8();
            case Streamers.K_USHORT -> b.u16();
            case Streamers.K_UINT, Streamers.K_BITS -> b.u32();
            case Streamers.K_BOOL -> b.bool();
            default -> throw new IllegalStateException("basic type " + type + " is not read by this reader");
        };
    }

    /**
     * Double32_t: a float, unless the comment gives a range [min,max(,nbits)],
     * when it is an integer of nbits (32 by default) spread over the range.
     */
    private static double readDouble32(RBuffer b, String title, boolean float16) {
        final double[] range = range(title);
        if (range == null) return float16 ? b.f32() : b.f32();
        final double min = range[0];
        final double max = range[1];
        final int nbits = (int) range[2];
        if (nbits == 0 || max <= min) return b.f32();
        final long aint = b.u32();
        final double factor = ((1L << Math.min(nbits, 32)) - 1) / (max - min);
        return aint / factor + min;
    }

    private static double[] range(String title) {
        if (title == null) return null;
        final int a = title.indexOf('[');
        final int z = title.indexOf(']', a + 1);
        if (a < 0 || z < 0) return null;
        final String[] parts = title.substring(a + 1, z).split(",");
        if (parts.length < 2) return null;
        try {
            final double min = eval(parts[0]);
            final double max = eval(parts[1]);
            final double nbits = parts.length > 2 ? Double.parseDouble(parts[2].strip()) : 32;
            return new double[]{min, max, nbits};
        } catch (RuntimeException notARange) {
            return null;
        }
    }

    private static double eval(String s) {
        final String t = s.strip().replace("pi", String.valueOf(Math.PI));
        return Double.parseDouble(t);
    }

    /** n values of a basic type, as the primitive array that fits it. */
    public Object readBasicArray(RBuffer b, int type, int n, String title) {
        switch (type) {
            case Streamers.K_CHAR, Streamers.K_LEGACYCHAR, Streamers.K_UCHAR: {
                return b.bytes(n);
            }
            case Streamers.K_BOOL: {
                final boolean[] out = new boolean[n];
                for (int i = 0; i < n; i++) out[i] = b.bool();
                return out;
            }
            case Streamers.K_SHORT, Streamers.K_USHORT: {
                final short[] out = new short[n];
                for (int i = 0; i < n; i++) out[i] = b.i16();
                return out;
            }
            case Streamers.K_INT, Streamers.K_COUNTER: {
                final int[] out = new int[n];
                for (int i = 0; i < n; i++) out[i] = b.i32();
                return out;
            }
            case Streamers.K_UINT, Streamers.K_BITS: {
                final long[] out = new long[n];
                for (int i = 0; i < n; i++) out[i] = b.u32();
                return out;
            }
            case Streamers.K_LONG, Streamers.K_LONG64, Streamers.K_ULONG, Streamers.K_ULONG64: {
                final long[] out = new long[n];
                for (int i = 0; i < n; i++) out[i] = b.i64();
                return out;
            }
            case Streamers.K_FLOAT: {
                final float[] out = new float[n];
                for (int i = 0; i < n; i++) out[i] = b.f32();
                return out;
            }
            case Streamers.K_DOUBLE: {
                final double[] out = new double[n];
                for (int i = 0; i < n; i++) out[i] = b.f64();
                return out;
            }
            case Streamers.K_DOUBLE32, Streamers.K_FLOAT16: {
                final double[] out = new double[n];
                for (int i = 0; i < n; i++) out[i] = ((Number) readBasic(b, type, title)).doubleValue();
                return out;
            }
            default:
                throw new IllegalStateException("arrays of type " + type + " are not read by this reader");
        }
    }

    /* ---- STL collections ---------------------------------------------------------- */

    /** The basic type a C++ type name stands for, or 0 when it is not one. */
    public static int basicType(String typeName) {
        return switch (typeName.strip()) {
            case "char", "Char_t", "signed char" -> Streamers.K_CHAR;
            case "unsigned char", "UChar_t", "Byte_t" -> Streamers.K_UCHAR;
            case "short", "Short_t", "short int" -> Streamers.K_SHORT;
            case "unsigned short", "UShort_t", "unsigned short int" -> Streamers.K_USHORT;
            case "int", "Int_t" -> Streamers.K_INT;
            case "unsigned int", "UInt_t", "unsigned" -> Streamers.K_UINT;
            case "long", "Long_t", "long int" -> Streamers.K_LONG;
            case "unsigned long", "ULong_t" -> Streamers.K_ULONG;
            case "long long", "Long64_t", "long long int", "int64_t" -> Streamers.K_LONG64;
            case "unsigned long long", "ULong64_t", "uint64_t" -> Streamers.K_ULONG64;
            case "float", "Float_t" -> Streamers.K_FLOAT;
            case "double", "Double_t" -> Streamers.K_DOUBLE;
            case "Double32_t" -> Streamers.K_DOUBLE32;
            case "Float16_t" -> Streamers.K_FLOAT16;
            case "bool", "Bool_t" -> Streamers.K_BOOL;
            default -> 0;
        };
    }

    /** The template argument of "vector<X>" (X itself when it is not a template). */
    public static String innerType(String typeName) {
        final String s = typeName.strip();
        final int a = s.indexOf('<');
        final int z = s.lastIndexOf('>');
        if (a < 0 || z < a) return s;
        String inner = s.substring(a + 1, z).strip();
        // vector<T, allocator<T> >: the first argument only
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            final char c = inner.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                inner = inner.substring(0, i).strip();
                break;
            }
        }
        if (inner.startsWith("const ")) inner = inner.substring(6).strip();
        return inner;
    }

    private static boolean isString(String typeName) {
        final String s = typeName.strip();
        return s.equals("string") || s.equals("std::string") || s.equals("TString")
            || s.startsWith("basic_string<char");
    }

    /**
     * An STL sequence: its header (with the member-wise bit), then its size
     * and its elements. Numbers come back as a primitive array, strings as a
     * List of String, objects as a List of RObject.
     */
    public Object readSTL(RBuffer b, String typeName, int stlType, int ctype) {
        final RBuffer.Header h = b.header();
        final String inner = innerType(typeName);
        try {
            if (stlType == 6 || stlType == 7 || typeName.strip().startsWith("map") || typeName.contains("::map<")) {
                return readMap(b, typeName, h);
            }
            if (h.memberWise()) {
                int innerVersion = b.u16();
                long checksum = 0;
                if (innerVersion == 0) checksum = b.u32();
                final int n = b.i32();
                Streamers.Info info = checksum != 0 ? streamers.byChecksum(checksum) : null;
                if (info == null) info = streamers.find(inner, innerVersion);
                final List<RObject> objects = new ArrayList<>(n);
                for (int i = 0; i < n; i++) objects.add(new RObject(inner, innerVersion));
                if (info == null) throw new IllegalStateException("no description of " + inner + " in the file");
                readMemberWise(b, info, objects);
                return new ArrayList<Object>(objects);
            }
            final int n = b.i32();
            int basic = ctype > 0 && ctype < Streamers.K_OFFSET_L ? ctype : basicType(inner);
            if (basic != 0) return readBasicArray(b, basic, n, "");
            if (isString(inner)) {
                final List<Object> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) out.add(b.tstring());
                return out;
            }
            final List<Object> out = new ArrayList<>(n);
            final String cls = stripPointer(inner);
            final boolean pointers = inner.strip().endsWith("*");
            for (int i = 0; i < n; i++) out.add(pointers ? readObjectAny(b) : readClass(b, cls));
            return out;
        } finally {
            b.end(h);
        }
    }

    /** All the first members of n objects, then all the second ones, ... */
    private void readMemberWise(RBuffer b, Streamers.Info info, List<RObject> objects) {
        final int n = objects.size();
        for (Streamers.Element e : info.elements()) {
            final int t = e.type();
            if (e.isBase()) {
                final Streamers.Info base = streamers.find(e.name(), e.baseVersion());
                if (base != null) readMemberWise(b, base, objects);
                continue;
            }
            if (t > 0 && t < Streamers.K_OFFSET_L) {
                for (int i = 0; i < n; i++) objects.get(i).members.put(e.name(), readBasic(b, t, e.title()));
                continue;
            }
            for (int i = 0; i < n; i++) objects.get(i).members.put(e.name(), readElement(b, e, objects.get(i)));
        }
    }

    /** std::map, read as a List of two-element Lists (key, value), member-wise as ROOT writes it. */
    private Object readMap(RBuffer b, String typeName, RBuffer.Header h) {
        if (h.memberWise()) {
            final int innerVersion = b.u16();
            if (innerVersion == 0) b.u32();
        }
        final int n = b.i32();
        final String args = innerType(typeName.replaceFirst("^(std::)?(multi)?map", "vector"));
        final String full = typeName.substring(typeName.indexOf('<') + 1, typeName.lastIndexOf('>'));
        int depth = 0;
        int comma = -1;
        for (int i = 0; i < full.length(); i++) {
            final char c = full.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                comma = i;
                break;
            }
        }
        final String keyType = comma < 0 ? args : full.substring(0, comma).strip();
        final String valueType = comma < 0 ? args : full.substring(comma + 1).strip();
        final List<Object> keys = new ArrayList<>(n);
        final List<Object> values = new ArrayList<>(n);
        for (int i = 0; i < n; i++) keys.add(readValue(b, keyType));
        for (int i = 0; i < n; i++) values.add(readValue(b, valueType));
        final List<Object> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(List.of(keys.get(i), values.get(i)));
        return out;
    }

    private Object readValue(RBuffer b, String type) {
        final int basic = basicType(type);
        if (basic != 0) return readBasic(b, basic, "");
        if (isString(type)) return b.tstring();
        if (type.contains("<")) return readSTL(b, type, 1, 0);
        return readClass(b, type);
    }

    /* ---- the classes ROOT streams by hand ------------------------------------------------ */

    /** TObject: a version (no byte count), fUniqueID, fBits, and a process id when referenced. */
    public RObject readTObject(RBuffer b) {
        final int start = b.position();
        final long first = b.u32();
        int version;
        if ((first & RBuffer.BYTE_COUNT_MASK) != 0) {
            version = b.u16();
        } else {
            b.position(start);
            version = b.u16();
        }
        final RObject o = new RObject("TObject", version);
        o.members.put("fUniqueID", b.u32());
        final long bits = b.u32();
        o.members.put("fBits", bits);
        if ((bits & IS_REFERENCED) != 0) b.u16();
        return o;
    }

    public RObject readTNamed(RBuffer b) {
        final RBuffer.Header h = b.header();
        final RObject o = new RObject("TNamed", h.version());
        o.members.putAll(readTObject(b).members);
        o.members.put("fName", b.tstring());
        o.members.put("fTitle", b.tstring());
        b.end(h);
        return o;
    }

    private List<Object> readTList(RBuffer b) {
        final RBuffer.Header h = b.header();
        final RList out = new RList();
        out.className = "TList";
        if (h.version() > 3) {
            out.bits = readTObject(b).number("fBits");
            out.name = b.tstring();
            final int n = b.i32();
            for (int i = 0; i < n; i++) {
                objStringBits = RList.BITS;
                final Object item = readObjectAny(b);
                if (item instanceof String && objStringBits != RList.BITS) out.stringBits.put(out.size(), objStringBits);
                out.add(item);
                int nch = b.u8();
                if (h.version() > 4 && nch == 255) nch = b.i32();
                b.skip(nch);
            }
        }
        b.end(h);
        return out;
    }

    private List<Object> readTObjArray(RBuffer b) {
        final RBuffer.Header h = b.header();
        final RList out = new RList();
        if (h.version() > 2) out.bits = readTObject(b).number("fBits");
        if (h.version() > 1) out.name = b.tstring();
        final int n = b.i32();
        b.i32(); // fLowerBound
        for (int i = 0; i < n; i++) out.add(readObjectAny(b));
        b.end(h);
        return out;
    }

    /** The bits of the last TObjString read, for the list holding it. */
    private long objStringBits = RList.BITS;

    private String readTObjString(RBuffer b) {
        final RBuffer.Header h = b.header();
        objStringBits = readTObject(b).number("fBits");
        final String s = b.tstring();
        b.end(h);
        return s;
    }

    /** TArrayX: its length, then its values (no header). */
    private Object readTArray(RBuffer b, String className) {
        final int n = b.i32();
        return switch (className) {
            case "TArrayC" -> readBasicArray(b, Streamers.K_CHAR, n, "");
            case "TArrayS" -> readBasicArray(b, Streamers.K_SHORT, n, "");
            case "TArrayI" -> readBasicArray(b, Streamers.K_INT, n, "");
            case "TArrayL", "TArrayL64" -> readBasicArray(b, Streamers.K_LONG64, n, "");
            case "TArrayF" -> readBasicArray(b, Streamers.K_FLOAT, n, "");
            default -> readBasicArray(b, Streamers.K_DOUBLE, n, "");
        };
    }

    /**
     * TBasket::Streamer: the TKey it is, its fields, its entry offsets, and,
     * for a basket kept inside its branch (written with the tree rather than
     * on its own), its whole buffer.
     */
    private RObject readTBasket(RBuffer b) {
        final RObject o = new RObject("TBasket", 0);
        o.members.put("fNbytes", b.i32());
        final int keyVersion = b.i16();
        o.members.put("fKeyVersion", keyVersion);
        o.members.put("fObjlen", b.i32());
        o.members.put("fDatime", b.u32());
        final int keylen = b.i16();
        o.members.put("fKeylen", keylen);
        o.members.put("fCycle", b.i16());
        if (keyVersion > 1000) {
            o.members.put("fSeekKey", b.i64());
            o.members.put("fSeekPdir", b.i64());
        } else {
            o.members.put("fSeekKey", b.u32());
            o.members.put("fSeekPdir", b.u32());
        }
        b.tstring();
        o.members.put("fName", b.tstring());
        o.members.put("fTitle", b.tstring());
        final int version = b.u16();
        o.members.put("fBufferSize", b.i32());
        int nevBufSize = b.i32();
        if (nevBufSize < 0) {
            nevBufSize = -nevBufSize;
            b.u8(); // fIOBits
        }
        o.members.put("fNevBufSize", nevBufSize);
        final int nevBuf = b.i32();
        o.members.put("fNevBuf", nevBuf);
        final int last = b.i32();
        o.members.put("fLast", last);
        int flag = b.i8();
        if (flag >= 80) flag -= 80;
        int[] offsets = null;
        if (flag != 0 && flag % 10 != 2) {
            if (nevBuf > 0) {
                final int n = b.i32();
                offsets = (int[]) readBasicArray(b, Streamers.K_INT, n, "");
                if (flag > 20 && flag < 40) for (int i = 0; i < offsets.length; i++) offsets[i] &= ~0xFF000000;
            }
            if (flag > 40) {
                final int n = b.i32();
                readBasicArray(b, Streamers.K_INT, n, "");
            }
        }
        if (flag == 1 || flag > 10) {
            final byte[] buffer = version > 1 ? b.bytes(last) : b.bytes(b.i32());
            final int border = Math.max(0, last - keylen);
            final byte[] data = new byte[border];
            System.arraycopy(buffer, Math.min(keylen, buffer.length), data, 0, Math.min(border, Math.max(0, buffer.length - keylen)));
            o.members.put("header", java.util.Arrays.copyOf(buffer, Math.min(keylen, buffer.length)));
            o.members.put("data", data);
            if (offsets != null) {
                final int[] rel = new int[nevBuf + 1];
                for (int i = 0; i < nevBuf && i < offsets.length; i++) rel[i] = offsets[i] - keylen;
                rel[nevBuf] = border;
                o.members.put("offsets", rel);
            }
        }
        return o;
    }

    /* ---- the class descriptions themselves ------------------------------------------------ */

    private Streamers.Info readStreamerInfo(RBuffer b) {
        final RBuffer.Header h = b.header();
        final RObject named = readTNamed(b);
        final long checksum = b.u32();
        final int classVersion = b.i32();
        final Object elements = readObjectAny(b);
        b.end(h);
        final List<Streamers.Element> list = new ArrayList<>();
        if (elements instanceof List<?> l) {
            for (Object e : l) if (e instanceof Streamers.Element el) list.add(el);
        }
        // what writing it back takes: the versions, the bits, the elements' array
        final RObject raw = new RObject("TStreamerInfo", h.version());
        raw.members.put("fUniqueID", named.get("fUniqueID"));
        raw.members.put("fBits", named.get("fBits"));
        raw.members.put("namedVersion", named.version);
        if (elements instanceof RList r) {
            raw.members.put("elementsBits", r.bits);
            raw.members.put("elementsName", r.name);
        }
        return new Streamers.Info(named.string("fName"), named.string("fTitle"), checksum, classVersion, list, raw);
    }

    private Streamers.Element readStreamerElement(RBuffer b, String kind) {
        final RBuffer.Header outer = b.header();
        Streamers.Element base;
        if (kind.equals("TStreamerSTLstring")) {
            base = readStreamerElement(b, "TStreamerSTL");
            b.end(outer);
            final RObject raw = base.raw();
            raw.members.put("stlVersion", raw.version);
            final RObject sraw = new RObject(kind, outer.version());
            sraw.members.putAll(raw.members);
            return new Streamers.Element(kind, base.name(), base.title(), base.type(), base.size(), base.arrayLength(),
                base.arrayDim(), base.maxIndex(), base.typeName(), 0, "", "", base.stlType(), base.ctype(), sraw);
        }
        // TStreamerElement itself, with its own header
        final RBuffer.Header h = b.header();
        final RObject named = readTNamed(b);
        final RObject raw = new RObject(kind, outer.version());
        raw.members.put("elementVersion", h.version());
        raw.members.put("namedVersion", named.version);
        raw.members.put("fUniqueID", named.get("fUniqueID"));
        raw.members.put("fBits", named.get("fBits"));
        int type = b.i32();
        raw.members.put("fType", type);
        final int size = b.i32();
        final int arrayLength = b.i32();
        final int arrayDim = b.i32();
        int[] maxIndex;
        if (h.version() == 1) {
            final int n = b.i32();
            maxIndex = (int[]) readBasicArray(b, Streamers.K_INT, n, "");
        } else {
            maxIndex = (int[]) readBasicArray(b, Streamers.K_INT, 5, "");
        }
        final String typeName = b.tstring();
        if (type == Streamers.K_UCHAR && (typeName.equals("Bool_t") || typeName.equals("bool"))) type = Streamers.K_BOOL;
        b.end(h);
        int baseVersion = 0;
        String countName = "";
        String countClass = "";
        int stlType = 0;
        int ctype = 0;
        switch (kind) {
            case "TStreamerBase":
                if (outer.version() > 2) baseVersion = b.i32();
                break;
            case "TStreamerBasicPointer":
            case "TStreamerLoop":
                raw.members.put("fCountVersion", b.i32());
                countName = b.tstring();
                countClass = b.tstring();
                break;
            case "TStreamerSTL":
                stlType = b.i32();
                ctype = b.i32();
                break;
            default:
                break;
        }
        b.end(outer);
        return new Streamers.Element(kind, named.string("fName"), named.string("fTitle"), type, size, arrayLength,
            arrayDim, maxIndex, typeName, baseVersion, countName, countClass, stlType, ctype, raw);
    }

    /** For messages: a class name as ROOT prints it. */
    static String describe(Object o) {
        if (o == null) return "null";
        if (o instanceof RObject r) return r.className;
        return o.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }
}

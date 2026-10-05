package com.sphere.core.rootio;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A TTree written in Java the way ROOT writes one (ROOT sources, tree/tree:
 * TTree, TBranch, TBranchElement, TBasket, TLeafElement):
 *
 * <ul>
 *   <li>{@link #branch} is TTree::Branch for an object at split level 99:
 *       TBranchElement::Init and Unroll make one branch per member, a count
 *       branch and one branch per member of the elements for a collection of
 *       objects, a branch without data and one per member for an object member;</li>
 *   <li>{@link #fill} is TTree::Fill: each branch writes its entry into its
 *       basket (TBranch::FillImpl, TBasket::Update), a full basket is written
 *       to the file (WriteBasketImpl, TBasket::WriteBuffer) and reused
 *       (WriteReset); then the tree flushes, optimises its basket sizes and
 *       saves itself as ROOT does once 30 MB and 300 MB are compressed;</li>
 *   <li>{@link #write} is WriteTObject: the tree with its branches, leaves and
 *       the baskets still in memory, under the next cycle of its key.</li>
 * </ul>
 *
 * Values are {@link RObject}s with members named as in the class description;
 * collections are {@link List}s, numbers any {@link Number}, vectors of numbers
 * Java arrays or lists, vectors of strings lists of strings.
 *
 * <p>The objects are written as ROOT 6.20 wrote them, with the bits it left
 * set (kIsOnHeap, kNotDeleted and the branches' own).
 */
public final class TreeWriter {

    /** The bits of the TTree, of a top-level split branch, of the other branches, of the leaves, as ROOT 6.20 wrote them. */
    static final long BITS_TREE = 0x03000008L;
    static final long BITS_TOP_BRANCH = 0x03420000L;
    static final long BITS_BRANCH = 0x03500000L;
    static final long BITS_LEAF = 0x03000000L;
    public static final int DEFAULT_BASKET_SIZE = 32000;
    public static final int SPLIT_LEVEL = 99;
    private static final int DEFAULT_ENTRY_OFFSET_LEN = 1000;

    private enum Kind { TOP, OBJECT, COUNT, MEMBER, ARRAY }

    private final RFileWriter file;
    private final Streamers streamers;
    private final ObjectWriter writer;
    private final String name;
    private final String title;
    private final List<Branch> branches = new ArrayList<>();
    private final List<Leaf> leaves = new ArrayList<>();
    private long entries;
    private long totBytes;
    private long zipBytes;
    private long savedBytes;
    private long flushedBytes;
    private long autoSave = -300000000L;
    private long autoFlush = -30000000L;
    private RFileWriter.Key savedKey;

    public TreeWriter(RFileWriter file, Streamers streamers, ObjectWriter writer, String name, String title) {
        this.file = file;
        this.streamers = streamers;
        this.writer = writer;
        this.name = name;
        this.title = title;
    }

    public long entries() {
        return entries;
    }

    /* ---- the description ------------------------------------------------------------ */

    /** A TLeafElement. */
    private static final class Leaf {
        final Branch branch;
        final String name;
        String title;
        final int lenType;
        boolean isRange;
        final boolean isUnsigned;
        Leaf leafCount;
        final int id;
        final int type;

        Leaf(Branch branch, String name, int id, int type) {
            this.branch = branch;
            this.name = name;
            this.title = name;
            this.id = id;
            this.type = type;
            int bare = type;
            if (bare > Streamers.K_OFFSET_P && bare < Streamers.K_OBJECT) bare -= Streamers.K_OFFSET_P;
            else if (bare > Streamers.K_OFFSET_L && bare < Streamers.K_OFFSET_P) bare -= Streamers.K_OFFSET_L;
            this.isUnsigned = type < Streamers.K_OBJECT
                && ((bare >= Streamers.K_UCHAR && bare <= Streamers.K_ULONG) || bare == Streamers.K_ULONG64);
            this.lenType = type < Streamers.K_OBJECT ? lenType(bare) : 0;
        }

        /** TLeafElement's fLenType: the size of the basic type, 0 otherwise. */
        private static int lenType(int t) {
            return switch (t) {
                case Streamers.K_CHAR, Streamers.K_UCHAR, Streamers.K_LEGACYCHAR, Streamers.K_BOOL -> 1;
                case Streamers.K_SHORT, Streamers.K_USHORT, Streamers.K_FLOAT16 -> 2;
                case Streamers.K_FLOAT, Streamers.K_DOUBLE32, Streamers.K_INT, Streamers.K_UINT -> 4;
                case Streamers.K_LONG, Streamers.K_ULONG, Streamers.K_LONG64, Streamers.K_ULONG64, Streamers.K_DOUBLE -> 8;
                default -> 0;
            };
        }
    }

    /** A TBranchElement: what ROOT records of it, and its filling. */
    public final class Branch {
        final Kind kind;
        final String name;
        String title;
        final Branch parent;
        final List<Branch> children = new ArrayList<>();
        Leaf leaf;
        Branch branchCount;
        String className = "";
        String parentName = "";
        String clonesName = "";
        long checksum;
        int classVersion;
        int id;
        int type;
        int streamerType;
        int splitLevel;
        int offset;
        long bits = BITS_BRANCH;
        int compress;
        int basketSize = DEFAULT_BASKET_SIZE;
        int entryOffsetLen;
        Streamers.Element element;
        /** The members leading to the value, from the parent's value (from each element for a member of a collection). */
        String[] path = new String[0];

        long nentries;
        long entryNumber;
        long branchTotBytes;
        long branchZipBytes;
        int writeBasket;
        int maxBaskets = 10;
        int[] basketBytes = new int[10];
        long[] basketEntry = new long[10];
        long[] basketSeek = new long[10];
        Basket basket;
        int maximum;

        private Branch(Kind kind, String name, Branch parent) {
            this.kind = kind;
            this.name = name;
            this.title = name;
            this.parent = parent;
            this.compress = file.compression();
        }

        public String name() {
            return name;
        }

        /** TBranchElement::FillImpl. */
        void fill(Object parentValue) throws IOException {
            switch (kind) {
                case TOP -> {
                    ++nentries;
                    for (Branch c : children) c.fill(parentValue);
                }
                case OBJECT -> {
                    ++nentries;
                    final Object v = resolve(parentValue, path);
                    for (Branch c : children) c.fill(v);
                }
                case COUNT -> {
                    final List<?> items = list(resolve(parentValue, path));
                    final int n = items.size();
                    if (n > maximum) maximum = n;
                    fillBasket(b -> b.i32(n));
                    for (Branch c : children) c.fill(items);
                }
                case MEMBER -> {
                    final Object v = resolve(parentValue, path);
                    fillBasket(b -> writer.writeElement(b, element, v, null));
                }
                case ARRAY -> {
                    final List<?> items = (List<?>) parentValue;
                    fillBasket(b -> {
                        for (Object o : items) writer.writeElement(b, element, resolve(o, path), null);
                    });
                }
            }
        }

        /** TBranch::FillImpl: the entry goes into the basket, which is written once full. */
        private void fillBasket(java.util.function.Consumer<WBuffer> entry) throws IOException {
            if (basket == null) basket = new Basket(this);
            final Basket bk = basket;
            final int lold = bk.length();
            bk.update(lold);
            ++nentries;
            ++entryNumber;
            bk.data.resetMap();
            entry.accept(bk.data);
            final int lnew = bk.length();
            final int nbytes = lnew - lold;
            int nsize = 0;
            if (entryOffsetLen != 0) {
                nsize = bk.nevBuf * 4;
            } else if (bk.nevBufSize == 0) {
                bk.nevBufSize = nbytes;
            }
            if (lnew + 2L * nsize + nbytes >= basketSize) writeBasketToFile();
        }

        /** TBranch::WriteBasketImpl with TBasket::WriteBuffer and WriteReset. */
        void writeBasketToFile() throws IOException {
            final Basket bk = basket;
            final int nevbuf = bk.nevBuf;
            if (entryOffsetLen > 10 && 4 * nevbuf < entryOffsetLen) {
                entryOffsetLen = nevbuf < 3 ? 10 : 4 * nevbuf;
            } else if (entryOffsetLen != 0 && nevbuf > entryOffsetLen) {
                entryOffsetLen = 2 * nevbuf;
            }
            final int last = bk.length();
            final WBuffer payload = new WBuffer(0);
            payload.bytes(bk.data.toByteArray());
            if (bk.entryOffset != null) {
                payload.i32(nevbuf + 1);
                for (int i = 0; i <= nevbuf; i++) payload.i32(bk.entryOffset[i]);
            }
            final byte[] objects = payload.toByteArray();
            if (last > bk.bufferSize) bk.bufferSize = last;
            final WBuffer fields = new WBuffer(0);
            fields.i16(3);
            fields.i32(bk.bufferSize);
            fields.i32(bk.nevBufSize);
            fields.i32(nevbuf);
            fields.i32(last);
            fields.i8(0);
            final long[] r = file.writeBasket(name, TreeWriter.this.name, writeBasket, fields.toByteArray(), objects);
            final int nbytes = (int) r[1];
            final int keylen = (int) r[2];
            basketBytes[writeBasket] = nbytes;
            basketSeek[writeBasket] = r[0];
            final int addbytes = objects.length + keylen;
            branchZipBytes += nbytes;
            branchTotBytes += addbytes;
            totBytes += addbytes;
            zipBytes += nbytes;
            ++writeBasket;
            if (writeBasket >= maxBaskets) expandBasketArrays();
            bk.writeReset();
            basketEntry[writeBasket] = entryNumber;
        }

        /** TBranch::ExpandBasketArrays. */
        private void expandBasketArrays() {
            final int newsize = Math.max(10, (int) (1.5 * maxBaskets));
            basketBytes = Arrays.copyOf(basketBytes, newsize);
            basketEntry = Arrays.copyOf(basketEntry, newsize);
            basketSeek = Arrays.copyOf(basketSeek, newsize);
            maxBaskets = newsize;
        }

        /** TBranch::FlushBaskets: the basket in memory if it holds entries, then the sub-branches'. */
        void flushBaskets() throws IOException {
            if (basket != null && basket.nevBuf > 0) writeBasketToFile();
            for (Branch c : children) c.flushBaskets();
        }

        /** TBranchElement::SetBasketSize. */
        void setBasketSize(int bufsize) {
            final int minsize = 100 + name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bufsize < minsize + entryOffsetLen) bufsize = minsize + entryOffsetLen;
            basketSize = bufsize;
            if (basket != null) basket.bufferSize = basketSize;
            for (Branch c : children) c.setBasketSize(basketSize);
        }
    }

    /** A TBasket in memory: the entries of a branch, their offsets, the header ROOT keeps in front of them. */
    private final class Basket {
        final Branch branch;
        final int keylen;
        int bufferSize;
        int nevBufSize;
        int nevBuf;
        int[] entryOffset;
        final WBuffer data;
        int objlen;
        byte[] header;

        /** The TBasket constructor. */
        Basket(Branch branch) {
            this.branch = branch;
            this.keylen = RFileWriter.keylen("TBasket", branch.name, name, true) + 19;
            this.bufferSize = branch.basketSize;
            this.nevBufSize = branch.entryOffsetLen;
            this.data = new WBuffer(keylen);
            // TKey(TDirectory*) gave fKeylen the size of an empty key, 29 bytes
            this.header = header(29);
            this.objlen = bufferSize - keylen;
            if (nevBufSize != 0) entryOffset = new int[nevBufSize];
        }

        /**
         * A basket kept inside the tree, as TBasket::Streamer reads it: its
         * buffer, and its entry offsets with room for exactly those entries.
         */
        Basket(Branch branch, RObject kept) {
            this.branch = branch;
            this.keylen = kept.integer("fKeylen");
            this.bufferSize = kept.integer("fBufferSize");
            this.nevBuf = kept.integer("fNevBuf");
            this.objlen = kept.integer("fObjlen");
            this.data = new WBuffer(keylen);
            final byte[] bytes = (byte[]) kept.get("data");
            if (bytes != null) data.bytes(bytes);
            final byte[] h = (byte[]) kept.get("header");
            final int[] rel = (int[]) kept.get("offsets");
            if (rel != null) {
                this.nevBufSize = nevBuf;
                this.entryOffset = new int[nevBuf + 1];
                for (int i = 0; i < nevBuf; i++) entryOffset[i] = rel[i] + keylen;
            } else {
                this.nevBufSize = kept.integer("fNevBufSize");
            }
            this.header = h != null ? h : header(keylen);
        }

        int length() {
            return keylen + data.length();
        }

        /** What TBasket::Streamer writes with fHeaderOnly: the key fields as they stand, then the basket's. */
        private byte[] header(int keylenField) {
            final WBuffer h = new WBuffer(0);
            h.bytes(RFileWriter.keyHeader(0, 1004, 0, RFileWriter.datimeEpoch(), keylenField, 0, 0, 0, "TBasket", branch.name, name));
            h.i16(3);
            h.i32(bufferSize);
            h.i32(nevBufSize);
            h.i32(0);
            h.i32(0);
            h.i8(0);
            return h.toByteArray();
        }

        /** TBasket::Update: the entry's offset, the table doubled when full. */
        void update(int offset) {
            if (entryOffset != null) {
                if (nevBuf + 1 >= nevBufSize) {
                    final int newsize = Math.max(10, 2 * nevBufSize);
                    entryOffset = Arrays.copyOf(entryOffset, newsize);
                    nevBufSize = newsize;
                    if (branch.writeBasket < 10 && branch.entryOffsetLen != 0) branch.entryOffsetLen = newsize;
                }
                entryOffset[nevBuf] = offset;
            }
            nevBuf++;
        }

        /** TBasket::WriteReset: emptied for the next entries, with the branch's offset table size. */
        void writeReset() {
            final int newNevBufSize = branch.entryOffsetLen;
            if (newNevBufSize == 0) {
                entryOffset = null;
            } else {
                entryOffset = new int[newNevBufSize];
            }
            nevBufSize = newNevBufSize;
            nevBuf = 0;
            data.reset();
            header = header(keylen);
            objlen = bufferSize - keylen;
        }

        /** The basket as TBranch::Streamer writes it inside the tree. */
        RObject object() {
            final int last = length();
            if (last > bufferSize) bufferSize = last;
            final RObject o = new RObject("TBasket", 3);
            o.members.put("fNbytes", 0);
            o.members.put("fKeyVersion", 1004);
            o.members.put("fObjlen", objlen);
            o.members.put("fDatime", Integer.toUnsignedLong(RFileWriter.datimeEpoch()));
            o.members.put("fKeylen", keylen);
            o.members.put("fCycle", 0);
            o.members.put("fSeekKey", 0L);
            o.members.put("fSeekPdir", 0L);
            o.members.put("fName", branch.name);
            o.members.put("fTitle", name);
            o.members.put("fBufferSize", bufferSize);
            o.members.put("fNevBufSize", nevBufSize);
            o.members.put("fNevBuf", nevBuf);
            o.members.put("fLast", last);
            if (entryOffset != null) o.members.put("fEntryOffset", Arrays.copyOf(entryOffset, nevBuf));
            o.members.put("header", header);
            o.members.put("data", data.toByteArray());
            return o;
        }
    }

    /* ---- TTree::Branch -------------------------------------------------------------------- */

    /** TTree::Branch(name, object) at split level 99: the branch and all its sub-branches, as ROOT splits the class. */
    public Branch branch(String branchName, String className) {
        final Streamers.Info info = streamers.find(className, -1);
        if (info == null) throw new IllegalArgumentException("no description of " + className);
        final Branch top = new Branch(Kind.TOP, branchName, null);
        describe(top, info, -2, -1, SPLIT_LEVEL);
        top.parentName = "";
        top.bits = BITS_TOP_BRANCH;
        top.entryOffsetLen = DEFAULT_ENTRY_OFFSET_LEN;
        top.leaf = leaf(top, branchName, -2, -1);
        branches.add(top);
        final List<Streamers.Element> elements = info.elements();
        for (int i = 0; i < elements.size(); i++) {
            final Streamers.Element e = elements.get(i);
            if (e.isBase()) throw new IllegalArgumentException(className + " has a base class, which this writer does not split");
            topMember(top, info, i, e, e.name());
        }
        return top;
    }

    /** What TBranchElement's constructor takes from the class description. */
    private static void describe(Branch b, Streamers.Info info, int id, int streamerType, int splitLevel) {
        b.className = info.name();
        b.checksum = info.checksum();
        b.classVersion = info.version();
        b.id = id;
        b.streamerType = streamerType;
        b.splitLevel = splitLevel;
    }

    /** TBranchElement::Init's fEntryOffsetLen: the tree's default for what has no fixed size, 0 otherwise. */
    private static int entryOffsetLen(int streamerType, int btype) {
        if (btype != 0 || streamerType <= Streamers.K_BASE || streamerType == Streamers.K_CHARSTAR
            || streamerType == Streamers.K_BITS || streamerType > Streamers.K_FLOAT16) {
            return DEFAULT_ENTRY_OFFSET_LEN;
        }
        return 0;
    }

    /** A member of the top-level object (TBranchElement::Init at split level 98). */
    private void topMember(Branch top, Streamers.Info info, int i, Streamers.Element e, String branchName) {
        final int split = SPLIT_LEVEL - 1;
        final Streamers.Info valueInfo = e.kind().equals("TStreamerSTL") ? splittable(e) : null;
        if (valueInfo != null) {
            // a collection of objects: the count, then a branch per member of the elements
            final Branch b = new Branch(Kind.COUNT, branchName, top);
            describe(b, info, i, streamerType(e), split);
            b.parentName = info.name();
            b.type = 4;
            b.clonesName = valueInfo.name();
            b.entryOffsetLen = entryOffsetLen(streamerType(e), 0);
            b.path = new String[]{e.name()};
            b.title = branchName + "_";
            b.leaf = leaf(b, branchName + "_", i, streamerType(e));
            b.leaf.isRange = true;
            top.children.add(b);
            unrollElements(b, branchName, valueInfo, valueInfo.name(), new String[0], 0);
            for (Branch c : b.children) {
                c.branchCount = b;
                c.leaf.leafCount = b.leaf;
                final String last = c.name.substring(c.name.lastIndexOf('.') + 1);
                c.title = last + "[" + branchName + "_]";
                c.leaf.title = c.title;
            }
            return;
        }
        final Streamers.Info objectInfo = isObject(e) ? streamers.find(stripPointer(e.typeName()), -1) : null;
        if (objectInfo != null) {
            // an object member: a branch without data, a branch per member of the object
            final Branch b = new Branch(Kind.OBJECT, branchName, top);
            describe(b, info, i, streamerType(e), split);
            b.parentName = info.name();
            b.type = 2;
            b.entryOffsetLen = entryOffsetLen(streamerType(e), 0);
            b.path = new String[]{e.name()};
            top.children.add(b);
            final List<Streamers.Element> members = objectInfo.elements();
            for (int j = 0; j < members.size(); j++) {
                final Streamers.Element m = members.get(j);
                final Branch c = new Branch(Kind.MEMBER, branchName + "." + m.name(), b);
                describe(c, objectInfo, j, streamerType(m), 0);
                c.parentName = objectInfo.name();
                c.entryOffsetLen = entryOffsetLen(streamerType(m), 0);
                c.element = m;
                c.path = new String[]{m.name()};
                c.leaf = leaf(c, c.name, j, streamerType(m));
                b.children.add(c);
            }
            return;
        }
        // a number, a string, a collection that cannot be split: the member as it is
        final Branch b = new Branch(Kind.MEMBER, branchName, top);
        describe(b, info, i, streamerType(e), cannotSplit(e) ? 0 : split);
        b.parentName = info.name();
        b.entryOffsetLen = entryOffsetLen(streamerType(e), 0);
        b.element = e;
        b.path = new String[]{e.name()};
        b.leaf = leaf(b, branchName, i, streamerType(e));
        top.children.add(b);
    }

    /**
     * TBranchElement::Unroll for the elements of a collection (btype 41):
     * a branch per basic member, an object member unrolled in place, its
     * members' offset that of the object in the element.
     */
    private void unrollElements(Branch owner, String prefix, Streamers.Info info, String parentClass, String[] path, int offset) {
        final List<Streamers.Element> elements = info.elements();
        final int[] offsets = layout(info);
        for (int j = 0; j < elements.size(); j++) {
            final Streamers.Element e = elements.get(j);
            if (e.isBase()) throw new IllegalArgumentException(info.name() + " has a base class, which this writer does not split");
            final String[] sub = Arrays.copyOf(path, path.length + 1);
            sub[path.length] = e.name();
            final Streamers.Info objectInfo = isObject(e) ? streamers.find(stripPointer(e.typeName()), -1) : null;
            if (objectInfo != null) {
                unrollElements(owner, prefix + "." + e.name(), objectInfo, parentClass, sub, offset + offsets[j]);
                continue;
            }
            final Branch c = new Branch(Kind.ARRAY, prefix + "." + e.name(), owner);
            describe(c, info, j, streamerType(e), 0);
            c.parentName = parentClass;
            c.type = 41;
            c.offset = offset;
            c.entryOffsetLen = entryOffsetLen(streamerType(e), 41);
            c.element = e;
            c.path = sub;
            c.leaf = leaf(c, c.name, j, streamerType(e));
            owner.children.add(c);
        }
    }

    /**
     * The element's type as ROOT holds it in memory: a collection is written
     * as kStreamer (500) for older readers, and read back as kSTL (300), or
     * kSTLp (71) through a pointer (TStreamerSTL::Streamer).
     */
    private static int streamerType(Streamers.Element e) {
        if (e.kind().equals("TStreamerSTL")) return e.typeName().contains("*") ? 71 : Streamers.K_STL;
        if (e.kind().equals("TStreamerSTLstring")) return Streamers.K_STLSTRING;
        return e.type();
    }

    private Leaf leaf(Branch b, String leafName, int id, int type) {
        final Leaf l = new Leaf(b, leafName, id, type);
        leaves.add(l);
        return l;
    }

    /** The description of the elements of a collection ROOT splits (objects, not numbers or strings). */
    private Streamers.Info splittable(Streamers.Element e) {
        final String inner = ObjectReader.innerType(e.typeName());
        if (inner == null || ObjectReader.basicType(inner) != 0) return null;
        if (inner.equals("string") || inner.equals("std::string") || inner.equals("TString") || inner.contains("<")
            || inner.endsWith("*")) {
            return null;
        }
        return streamers.find(inner, -1);
    }

    private static boolean isObject(Streamers.Element e) {
        return (e.type() == Streamers.K_OBJECT || e.type() == Streamers.K_ANY) && !e.typeName().contains("*");
    }

    /** TStreamerElement::CannotSplit, for what this writer meets: collections of numbers or strings. */
    private boolean cannotSplit(Streamers.Element e) {
        return e.kind().equals("TStreamerSTL") && splittable(e) == null;
    }

    private static String stripPointer(String typeName) {
        String s = typeName.strip();
        while (s.endsWith("*")) s = s.substring(0, s.length() - 1).strip();
        return s;
    }

    /* ---- the members' places in memory -------------------------------------------- */

    /** Where each member of the class lies in a C++ object (x86-64 layout), as fOffset records it. */
    private int[] layout(Streamers.Info info) {
        final List<Streamers.Element> elements = info.elements();
        final int[] offsets = new int[elements.size()];
        int at = 0;
        for (int j = 0; j < elements.size(); j++) {
            final int[] sa = sizeAlign(elements.get(j));
            at = align(at, sa[1]);
            offsets[j] = at;
            at += sa[0];
        }
        return offsets;
    }

    private int[] classSizeAlign(Streamers.Info info) {
        int at = 0;
        int maxAlign = 1;
        for (Streamers.Element e : info.elements()) {
            final int[] sa = sizeAlign(e);
            at = align(at, sa[1]) + sa[0];
            maxAlign = Math.max(maxAlign, sa[1]);
        }
        return new int[]{align(at, maxAlign), maxAlign};
    }

    private int[] sizeAlign(Streamers.Element e) {
        final int t = e.type();
        if (e.kind().equals("TStreamerSTLstring") || t == Streamers.K_STLSTRING) return new int[]{32, 8};
        if (e.kind().equals("TStreamerSTL")) {
            return switch (e.stlType()) {
                case 3 -> new int[]{80, 8};
                case 4, 5, 6, 7 -> new int[]{48, 8};
                default -> new int[]{24, 8};
            };
        }
        if (t == Streamers.K_TSTRING) return new int[]{24, 8};
        if (t > Streamers.K_OFFSET_P && t < Streamers.K_OBJECT) return new int[]{8, 8};
        if (t == Streamers.K_OBJECTP || t == Streamers.K_OBJECTPP || t == Streamers.K_ANYP || t == Streamers.K_ANYPP) return new int[]{8, 8};
        if (t > 0 && t < Streamers.K_OFFSET_L) {
            final int s = basicSize(t);
            return new int[]{s, s};
        }
        if (t > Streamers.K_OFFSET_L && t < Streamers.K_OFFSET_P) {
            final int s = basicSize(t - Streamers.K_OFFSET_L);
            return new int[]{s * Math.max(1, e.arrayLength()), s};
        }
        final Streamers.Info info = streamers.find(stripPointer(e.typeName()), -1);
        if (info != null) return classSizeAlign(info);
        return new int[]{Math.max(1, e.size()), Math.min(8, Math.max(1, Integer.highestOneBit(Math.max(1, e.size()))))};
    }

    private static int basicSize(int t) {
        return switch (t) {
            case Streamers.K_CHAR, Streamers.K_UCHAR, Streamers.K_LEGACYCHAR, Streamers.K_BOOL -> 1;
            case Streamers.K_SHORT, Streamers.K_USHORT -> 2;
            case Streamers.K_LONG, Streamers.K_ULONG, Streamers.K_LONG64, Streamers.K_ULONG64, Streamers.K_DOUBLE,
                 Streamers.K_DOUBLE32 -> 8;
            default -> 4;
        };
    }

    private static int align(int at, int a) {
        return a <= 1 ? at : (at + a - 1) / a * a;
    }

    /* ---- a tree read back to go on filling it ---------------------------------------- */

    /**
     * Takes up the state of the same tree as read from its file (TTree::Streamer
     * in an "UPDATE" file): its counts, each branch's baskets on disk, and the
     * baskets that were kept inside the tree, which the next entries go on
     * filling. The branches must already be made, as they were then.
     *
     * @param tree the TTree object read
     * @param key  its key in the file: the one a later AutoSave replaces
     */
    public void resume(RObject tree, RFileWriter.Key key) throws IOException {
        entries = tree.number("fEntries");
        totBytes = tree.number("fTotBytes");
        zipBytes = tree.number("fZipBytes");
        savedBytes = tree.number("fSavedBytes");
        flushedBytes = tree.number("fFlushedBytes");
        autoSave = tree.number("fAutoSave");
        autoFlush = tree.number("fAutoFlush");
        savedKey = key;
        resume(branches, tree.list("fBranches"));
    }

    private void resume(List<Branch> mine, List<?> read) throws IOException {
        if (read == null || mine.size() != read.size()) throw new IOException("the tree in the file has other branches");
        for (int i = 0; i < mine.size(); i++) {
            final Branch b = mine.get(i);
            final RObject o = (RObject) read.get(i);
            if (!b.name.equals(o.string("fName")) || b.type != o.integer("fType")) {
                throw new IOException("the tree in the file has other branches (" + o.string("fName") + ")");
            }
            b.nentries = o.number("fEntries");
            b.entryNumber = o.number("fEntryNumber");
            b.branchTotBytes = o.number("fTotBytes");
            b.branchZipBytes = o.number("fZipBytes");
            b.writeBasket = o.integer("fWriteBasket");
            b.basketSize = o.integer("fBasketSize");
            b.entryOffsetLen = o.integer("fEntryOffsetLen");
            b.compress = o.integer("fCompress");
            b.maximum = o.integer("fMaximum");
            final int[] bytes = o.ints("fBasketBytes");
            final long[] entry = o.longs("fBasketEntry");
            final long[] seek = o.longs("fBasketSeek");
            b.maxBaskets = Math.max(Math.max(bytes.length, 10), b.writeBasket + 1);
            b.basketBytes = Arrays.copyOf(bytes, b.maxBaskets);
            b.basketEntry = Arrays.copyOf(entry, b.maxBaskets);
            b.basketSeek = Arrays.copyOf(seek, b.maxBaskets);
            final List<?> baskets = o.list("fBaskets");
            b.basket = null;
            if (baskets != null && b.writeBasket < baskets.size() && baskets.get(b.writeBasket) instanceof RObject kept) {
                b.basket = new Basket(b, kept);
            }
            resume(b.children, o.list("fBranches"));
        }
    }

    /* ---- TTree::Fill ------------------------------------------------------------------------ */

    /** One entry: a value for each top-level branch, in the order they were made. */
    public void fill(Object... values) throws IOException {
        for (int i = 0; i < branches.size(); i++) branches.get(i).fill(i < values.length ? values[i] : null);
        ++entries;
        boolean flush = false;
        boolean save = false;
        if (autoFlush != 0 || autoSave != 0) {
            if (flushedBytes == 0) {
                final long zip = zipBytes;
                if (autoFlush != 0) flush = autoFlush < 0 ? zip > -autoFlush : entries % autoFlush == 0;
                if (autoSave != 0) save = autoSave < 0 ? zip > -autoSave : entries % autoSave == 0;
                if (flush || save) {
                    flushBaskets();
                    flush = false;
                    optimizeBaskets(totBytes);
                    flushedBytes = zipBytes;
                    autoFlush = entries;
                    if (autoSave < 0) {
                        if (zip != 0) {
                            autoSave = Math.max(autoFlush, entries * ((-autoSave / zip) / entries));
                        } else if (totBytes != 0) {
                            autoSave = Math.max(autoFlush, entries * ((-autoSave / totBytes) / entries));
                        } else {
                            final long total = serialize(0).length;
                            autoSave = Math.max(autoFlush, entries * ((-autoSave / total) / entries));
                        }
                    } else if (autoSave > 0) {
                        autoSave = autoFlush * (autoSave / autoFlush);
                    }
                    if (autoSave != 0 && entries >= autoSave) save = true;
                }
            } else {
                if (autoFlush != 0) flush = entries > 1 && entries % autoFlush == 0;
                if (autoSave != 0) save = entries % autoSave == 0;
            }
        }
        if (flush) {
            flushBaskets();
            flushedBytes = zipBytes;
        }
        if (save) autoSaveTree();
    }

    /** TTree::FlushBaskets. */
    public void flushBaskets() throws IOException {
        for (Branch b : branches) b.flushBaskets();
    }

    /** TTree::OptimizeBaskets(maxMemory, 1, ""): basket sizes in proportion to each branch's share of the bytes. */
    private void optimizeBaskets(long maxMemory) {
        final int nleaves = leaves.size();
        final double treeSize = totBytes;
        if (nleaves == 0 || treeSize == 0) return;
        final double aveSize = treeSize / nleaves;
        long bmin = 512;
        long bmax = 256000;
        double memFactor = 1;
        for (int pass = 0; pass < 2; pass++) {
            int newMemsize = 0;
            for (Leaf leaf : leaves) {
                final Branch branch = leaf.branch;
                final double branchTot = branch.branchTotBytes;
                final double idealFactor = branchTot / aveSize;
                final long sizeOfOneEntry = branch.nentries == 0
                    ? (long) aveSize & 0xffffffffL
                    : 1 + ((long) (branchTot / branch.nentries) & 0xffffffffL);
                final int oldBsize = branch.basketSize;
                if (!branch.children.isEmpty()) continue;
                double bsize = oldBsize * idealFactor * memFactor;
                if (bsize < 0) bsize = bmax;
                if (bsize > bmax) bsize = bmax;
                long newBsize = (long) bsize & 0xffffffffL;
                if (pass == 1) {
                    final long clusterSize = autoFlush > 0 ? autoFlush : branch.nentries;
                    if (branch.entryOffsetLen != 0) newBsize = (newBsize + clusterSize * 4 * 2) & 0xffffffffL;
                    newBsize = newBsize - newBsize % 512 + 512;
                }
                if (newBsize < sizeOfOneEntry) newBsize = sizeOfOneEntry;
                if (newBsize < bmin) newBsize = bmin;
                if (newBsize > 10000000) newBsize = bmax;
                if (pass == 1) branch.setBasketSize((int) newBsize);
                newMemsize += (int) newBsize;
            }
            memFactor = (double) maxMemory / (double) newMemsize;
            if (memFactor > 100) memFactor = 100;
            final double bminNew = bmin * memFactor;
            final double bmaxNew = bmax * memFactor;
            final long hardmax = 1024L * 1024 * 1024;
            final long hardmin = 8;
            bmin = bminNew > hardmax ? hardmax : (bminNew < hardmin ? hardmin : (long) bminNew);
            bmax = bmaxNew > hardmax ? bmin : (long) bmaxNew;
        }
    }

    /** TTree::AutoSave(""): the tree under a new cycle, the previous one deleted, the class descriptions written once. */
    private void autoSaveTree() throws IOException {
        savedBytes = zipBytes;
        final RFileWriter.Key previous = savedKey;
        savedKey = write();
        if (previous != null) file.deleteKey(previous);
        if (!file.streamerInfoWritten()) file.writeStreamerInfo();
    }

    /* ---- TDirectoryFile::WriteTObject ------------------------------------------------- */

    /** The tree as it stands, baskets still in memory included, under the next cycle of its name. */
    public RFileWriter.Key write() throws IOException {
        return file.writeObject("TTree", name, title, this::serialize);
    }

    /** The TTree record for a key of that length. */
    private byte[] serialize(int keylen) {
        final RObject tree = treeObject();
        final WBuffer b = new WBuffer(keylen);
        b.mapObject(tree, 1L);
        writer.writeClass(b, "TTree", tree);
        return b.toByteArray();
    }

    private RObject treeObject() {
        final Map<Leaf, RObject> leafObjects = new IdentityHashMap<>();
        for (Leaf l : leaves) leafObjects.put(l, leafObject(l));
        for (Leaf l : leaves) leafObjects.get(l).members.put("fLeafCount", l.leafCount == null ? null : leafObjects.get(l.leafCount));
        final Map<Branch, RObject> branchObjects = new IdentityHashMap<>();
        for (Branch b : branches) create(b, branchObjects);
        for (Branch b : branches) complete(b, branchObjects, leafObjects);

        final RObject t = new RObject("TTree", 20);
        final Map<String, Object> m = t.members;
        m.put("fUniqueID", 0L);
        m.put("fBits", BITS_TREE);
        m.put("fName", name);
        m.put("fTitle", title);
        m.put("fLineColor", 602);
        m.put("fLineStyle", 1);
        m.put("fLineWidth", 1);
        m.put("fFillColor", 0);
        m.put("fFillStyle", 1001);
        m.put("fMarkerColor", 1);
        m.put("fMarkerStyle", 1);
        m.put("fMarkerSize", 1.0f);
        m.put("fEntries", entries);
        m.put("fTotBytes", totBytes);
        m.put("fZipBytes", zipBytes);
        m.put("fSavedBytes", savedBytes);
        m.put("fFlushedBytes", flushedBytes);
        m.put("fWeight", 1.0);
        m.put("fTimerInterval", 0);
        m.put("fScanField", 25);
        m.put("fUpdate", 0);
        m.put("fDefaultEntryOffsetLen", DEFAULT_ENTRY_OFFSET_LEN);
        m.put("fNClusterRange", 0);
        m.put("fMaxEntries", 1000000000000L);
        m.put("fMaxEntryLoop", 1000000000000L);
        m.put("fMaxVirtualSize", 0L);
        m.put("fAutoSave", autoSave);
        m.put("fAutoFlush", autoFlush);
        m.put("fEstimate", 1000000L);
        m.put("fClusterRangeEnd", new long[0]);
        m.put("fClusterSize", new long[0]);
        m.put("fIOFeatures", ioFeatures());
        final RList top = RList.owning();
        for (Branch b : branches) top.add(branchObjects.get(b));
        m.put("fBranches", top);
        final RList all = new RList();
        for (Leaf l : leaves) all.add(leafObjects.get(l));
        m.put("fLeaves", all);
        m.put("fAliases", null);
        m.put("fIndexValues", new double[0]);
        m.put("fIndex", new int[0]);
        m.put("fTreeIndex", null);
        m.put("fFriends", null);
        m.put("fUserInfo", null);
        m.put("fBranchRef", null);
        return t;
    }

    private static RObject ioFeatures() {
        final RObject f = new RObject("ROOT::TIOFeatures", 1);
        f.members.put("fIOBits", 0);
        return f;
    }

    private static RObject leafObject(Leaf l) {
        final RObject o = new RObject("TLeafElement", 1);
        final Map<String, Object> m = o.members;
        m.put("fUniqueID", 0L);
        m.put("fBits", BITS_LEAF);
        m.put("fName", l.name);
        m.put("fTitle", l.title);
        m.put("fLen", 1);
        m.put("fLenType", l.lenType);
        m.put("fOffset", 0);
        m.put("fIsRange", l.isRange);
        m.put("fIsUnsigned", l.isUnsigned);
        m.put("fLeafCount", null);
        m.put("fID", l.id);
        m.put("fType", l.type);
        return o;
    }

    private static void create(Branch b, Map<Branch, RObject> objects) {
        objects.put(b, new RObject("TBranchElement", 10));
        for (Branch c : b.children) create(c, objects);
    }

    /** TBranch::Streamer and TBranchElement's: the arrays as long as max(baskets written + 1, 10), the basket in memory if it holds entries. */
    private void complete(Branch b, Map<Branch, RObject> branchObjects, Map<Leaf, RObject> leafObjects) {
        final RObject o = branchObjects.get(b);
        final Map<String, Object> m = new LinkedHashMap<>();
        final int max = Math.max(b.writeBasket + 1, 10);
        m.put("fUniqueID", 0L);
        m.put("fBits", b.bits);
        m.put("fName", b.name);
        m.put("fTitle", b.title);
        m.put("fFillColor", 0);
        m.put("fFillStyle", 1001);
        m.put("fCompress", b.compress);
        m.put("fBasketSize", b.basketSize);
        m.put("fEntryOffsetLen", b.entryOffsetLen);
        m.put("fWriteBasket", b.writeBasket);
        m.put("fEntryNumber", b.entryNumber);
        m.put("fIOFeatures", ioFeatures());
        m.put("fOffset", b.offset);
        m.put("fMaxBaskets", max);
        m.put("fSplitLevel", b.splitLevel);
        m.put("fEntries", b.nentries);
        m.put("fFirstEntry", 0L);
        m.put("fTotBytes", b.branchTotBytes);
        m.put("fZipBytes", b.branchZipBytes);
        final RList children = new RList();
        for (Branch c : b.children) children.add(branchObjects.get(c));
        m.put("fBranches", children);
        final RList leafList = new RList();
        if (b.leaf != null) leafList.add(leafObjects.get(b.leaf));
        m.put("fLeaves", leafList);
        final RList baskets = new RList();
        if (b.basket != null && b.basket.nevBuf > 0) {
            for (int i = 0; i < b.writeBasket; i++) baskets.add(null);
            baskets.add(b.basket.object());
        }
        m.put("fBaskets", baskets);
        m.put("fBasketBytes", Arrays.copyOf(b.basketBytes, max));
        m.put("fBasketEntry", Arrays.copyOf(b.basketEntry, max));
        m.put("fBasketSeek", Arrays.copyOf(b.basketSeek, max));
        m.put("fFileName", "");
        m.put("fClassName", b.className);
        m.put("fParentName", b.parentName);
        m.put("fClonesName", b.clonesName);
        m.put("fCheckSum", b.checksum);
        m.put("fClassVersion", b.classVersion);
        m.put("fID", b.id);
        m.put("fType", b.type);
        m.put("fStreamerType", b.streamerType);
        m.put("fMaximum", b.maximum);
        m.put("fBranchCount", b.branchCount == null ? null : branchObjects.get(b.branchCount));
        m.put("fBranchCount2", null);
        o.members.putAll(m);
        for (Branch c : b.children) complete(c, branchObjects, leafObjects);
    }

    /* ---- values ------------------------------------------------------------------------------ */

    private static Object resolve(Object value, String[] path) {
        Object v = value;
        for (String p : path) {
            if (!(v instanceof RObject r)) return null;
            v = r.get(p);
        }
        return v;
    }

    private static List<?> list(Object v) {
        if (v instanceof List<?> l) return l;
        return List.of();
    }
}

package com.sphere.core.rootio;

import com.sphere.components.rootview.RootKey;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A TTree read in Java: its branches, and the value of any branch at any
 * entry, out of baskets read from the file or kept inside the tree itself.
 *
 * <p>Plain branches (TBranch with TLeafI, TLeafD, ... and variable arrays
 * counted by another leaf) and the branches ROOT makes of objects
 * (TBranchElement) are read: members of a split object, the counts and the
 * members of split STL collections (types 3, 4, 31, 41), STL vectors, strings
 * and whole unsplit objects.
 */
public final class RTree {

    private final RootIO io;
    private final RObject tree;
    private final List<RBranch> top = new ArrayList<>();
    private final List<RBranch> all = new ArrayList<>();
    private final Map<RObject, RBranch> byObject = new IdentityHashMap<>();
    private final Map<RObject, RBranch> byLeaf = new IdentityHashMap<>();

    RTree(RootIO io, RObject tree) {
        this.io = io;
        this.tree = tree;
        for (Object o : tree.list("fBranches")) {
            if (o instanceof RObject b) top.add(build(b, null));
        }
    }

    private RBranch build(RObject b, RBranch parent) {
        final RBranch br = new RBranch(this, b, parent);
        byObject.put(b, br);
        for (Object l : b.list("fLeaves")) if (l instanceof RObject leaf) byLeaf.put(leaf, br);
        all.add(br);
        for (Object o : b.list("fBranches")) {
            if (o instanceof RObject child) br.children.add(build(child, br));
        }
        return br;
    }

    public RObject object() {
        return tree;
    }

    public String name() {
        return tree.string("fName");
    }

    public String title() {
        return tree.string("fTitle");
    }

    public long entries() {
        return tree.number("fEntries");
    }

    /** The top-level branches. */
    public List<RBranch> branches() {
        return top;
    }

    /** Every branch, depth first. */
    public List<RBranch> allBranches() {
        return all;
    }

    /** The first branch of that name, depth first; null when absent. */
    public RBranch branch(String name) {
        for (RBranch b : all) if (b.name().equals(name)) return b;
        return null;
    }

    /** The branch of that name under another one (a name may be used twice in a tree). */
    public RBranch branch(String under, String name) {
        final RBranch parent = branch(under);
        if (parent == null) return null;
        return parent.find(name);
    }

    RBranch of(RObject branchObject) {
        return branchObject == null ? null : byObject.get(branchObject);
    }

    RBranch ofLeaf(RObject leaf) {
        return leaf == null ? null : byLeaf.get(leaf);
    }

    RootIO io() {
        return io;
    }

    /** One branch, and the baskets holding its entries. */
    public static final class RBranch {
        private final RTree tree;
        private final RObject b;
        private final RBranch parent;
        private final List<RBranch> children = new ArrayList<>();
        private List<Basket> baskets;
        private int cached = -1;
        private byte[] data;
        private int[] offsets;
        private int fixedSize;
        /** The key header in front of the basket's data: ROOT's buffer positions count it. */
        private int basketKeylen;

        RBranch(RTree tree, RObject b, RBranch parent) {
            this.tree = tree;
            this.b = b;
            this.parent = parent;
        }

        public String name() {
            return b.string("fName");
        }

        public String title() {
            return b.string("fTitle");
        }

        public RObject object() {
            return b;
        }

        public RBranch parent() {
            return parent;
        }

        public List<RBranch> children() {
            return children;
        }

        public boolean isElement() {
            return b.className.equals("TBranchElement");
        }

        /** TBranchElement's fType: 0 member, 3/4 collection count, 31/41 member of a collection, -1/-2 top. */
        public int type() {
            return b.integer("fType");
        }

        public int streamerType() {
            return b.integer("fStreamerType");
        }

        public int id() {
            return b.integer("fID");
        }

        public String elementClass() {
            return b.string("fClassName");
        }

        public long entries() {
            return b.number("fEntries");
        }

        public List<RObject> leaves() {
            final List<RObject> out = new ArrayList<>();
            for (Object o : b.list("fLeaves")) if (o instanceof RObject r) out.add(r);
            return out;
        }

        /** The branch of that name among this one's descendants. */
        public RBranch find(String name) {
            for (RBranch c : children) {
                if (c.name().equals(name)) return c;
                final RBranch deeper = c.find(name);
                if (deeper != null) return deeper;
            }
            return null;
        }

        /** True when the branch holds data of its own (not only sub-branches). */
        public boolean hasData() {
            if (!isElement()) return !leaves().isEmpty();
            final int t = type();
            return t == 3 || t == 4 || children.isEmpty();
        }

        /** What the branch holds, as a reader of the tree would say it. */
        public String typeName() {
            if (!isElement()) {
                final List<RObject> ls = leaves();
                if (ls.isEmpty()) return "";
                final RObject leaf = ls.get(0);
                final String cls = leaf.className.replace("TLeaf", "");
                final RObject count = leaf.object("fLeafCount");
                final int len = leaf.integer("fLen");
                return cls + (count != null ? "[" + count.string("fName") + "]" : len > 1 ? "[" + len + "]" : "");
            }
            final int t = type();
            if (t == 3 || t == 4) return "count of " + b.string("fClonesName");
            final Streamers.Element el = element();
            if (el != null) return (t == 31 || t == 41 ? el.typeName() + "[]" : el.typeName());
            return elementClass();
        }

        /** The description of the member this branch holds, when it holds one. */
        public Streamers.Element element() {
            try {
                final Streamers s = tree.io().streamers();
                final Streamers.Info info = s.find(elementClass(), b.integer("fClassVersion"));
                if (info == null) return null;
                return info.element(id());
            } catch (IOException e) {
                return null;
            }
        }

        /* ---- baskets ---------------------------------------------------------------- */

        private record Basket(long first, long seek, RObject embedded) {
        }

        private List<Basket> baskets() throws IOException {
            if (baskets != null) return baskets;
            final List<Basket> out = new ArrayList<>();
            final long[] seeks = b.longs("fBasketSeek");
            final long[] firsts = b.longs("fBasketEntry");
            final int write = b.integer("fWriteBasket");
            for (int i = 0; i < seeks.length; i++) {
                if (seeks[i] != 0) out.add(new Basket(i < firsts.length ? firsts[i] : 0, seeks[i], null));
            }
            long next = write < firsts.length ? firsts[write] : 0;
            for (Object o : b.list("fBaskets")) {
                if (o instanceof RObject basket && basket.className.equals("TBasket") && basket.has("data")) {
                    out.add(new Basket(next, 0, basket));
                    next += basket.integer("fNevBuf");
                }
            }
            out.sort((x, y) -> Long.compare(x.first(), y.first()));
            baskets = out;
            return out;
        }

        public int basketCount() throws IOException {
            return baskets().size();
        }

        /** Loads the basket holding an entry; answers the index of the entry inside it. */
        private int load(long entry) throws IOException {
            final List<Basket> list = baskets();
            int lo = 0;
            int hi = list.size() - 1;
            int found = -1;
            while (lo <= hi) {
                final int mid = (lo + hi) >>> 1;
                if (list.get(mid).first() <= entry) {
                    found = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            if (found < 0) throw new IOException("entry " + entry + " of " + name() + " is in no basket");
            if (found != cached) readBasket(list.get(found));
            cached = found;
            return (int) (entry - list.get(found).first());
        }

        private void readBasket(Basket bk) throws IOException {
            if (bk.embedded() != null) {
                data = (byte[]) bk.embedded().get("data");
                offsets = (int[]) bk.embedded().get("offsets");
                basketKeylen = bk.embedded().integer("fKeylen");
                final int nev = bk.embedded().integer("fNevBuf");
                fixedSize = offsets == null && nev > 0 ? data.length / nev : 0;
                return;
            }
            final RootKey k = tree.io().file().keyAt(bk.seek());
            if (k == null) throw new IOException("no basket at " + bk.seek() + " for " + name());
            final byte[] head = tree.io().file().rawAt(bk.seek(), k.keylen);
            final RBuffer h = new RBuffer(head, 0);
            h.position(k.keylen - 19);
            h.u16();                       // basket version
            h.i32();                       // fBufferSize
            h.i32();                       // fNevBufSize
            final int nevBuf = h.i32();
            final int last = h.i32();
            final byte[] payload = tree.io().file().payload(k);
            basketKeylen = k.keylen;
            final int border = last - k.keylen;
            data = border == payload.length ? payload : java.util.Arrays.copyOf(payload, Math.max(0, border));
            if (border != payload.length && border >= 0) {
                final RBuffer o = new RBuffer(payload, border, payload.length, 0);
                final int n = o.i32();
                offsets = new int[nevBuf + 1];
                for (int i = 0; i < nevBuf && i < n; i++) offsets[i] = o.i32() - k.keylen;
                offsets[nevBuf] = border;
                fixedSize = 0;
            } else {
                offsets = null;
                fixedSize = nevBuf > 0 ? data.length / nevBuf : 0;
            }
        }

        /** The bytes of one entry, as a buffer positioned on them. */
        public RBuffer entry(long entry) throws IOException {
            final int i = load(entry);
            if (offsets != null) return new RBuffer(data, offsets[i], offsets[i + 1], basketKeylen);
            return new RBuffer(data, i * fixedSize, (i + 1) * fixedSize, basketKeylen);
        }

        /* ---- values -------------------------------------------------------------------- */

        /**
         * The value at an entry: a boxed number, a primitive array, a String,
         * a List (STL collection of strings or objects), an RObject, or a
         * List of the leaves' values for a branch of several leaves.
         */
        public Object value(long entry) throws IOException {
            final RBuffer e = entry(entry);
            final ObjectReader r = tree.io().reader();
            if (!isElement()) return leafValues(e, entry, r);
            final int t = type();
            final int st = streamerType();
            if (t == 3 || t == 4) return e.i32();
            if (t == 31 || t == 41) {
                final int n = countAt(entry);
                if (st > 0 && st < Streamers.K_OFFSET_L) return r.readBasicArray(e, st, n, "");
                if (st == Streamers.K_TSTRING) {
                    final List<Object> out = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) out.add(e.tstring());
                    return out;
                }
                final Streamers.Element el = element();
                final List<Object> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) out.add(el == null ? null : r.readElement(e, el, null));
                return out;
            }
            if (t <= 0 && id() < 0) {
                // a whole object, not split
                return r.readClass(e, elementClass());
            }
            final Streamers.Element el = element();
            if (st > 0 && st < Streamers.K_OFFSET_L) return r.readBasic(e, st, el == null ? "" : el.title());
            if (el != null) return r.readElement(e, el, null);
            if (st == Streamers.K_TSTRING) return e.tstring();
            throw new IOException(name() + ": a branch of type " + t + "/" + st + " is not read by this reader");
        }

        /** The size of the collection a member of a split collection belongs to, at an entry. */
        private int countAt(long entry) throws IOException {
            final RBranch count = tree.of(b.object("fBranchCount"));
            if (count == null) throw new IOException(name() + ": its collection's count branch is missing");
            return ((Number) count.value(entry)).intValue();
        }

        private Object leafValues(RBuffer e, long entry, ObjectReader r) throws IOException {
            final List<RObject> ls = leaves();
            final List<Object> values = new ArrayList<>(ls.size());
            for (RObject leaf : ls) {
                int n = Math.max(1, leaf.integer("fLen"));
                final RObject countLeaf = leaf.object("fLeafCount");
                if (countLeaf != null) {
                    final RBranch cb = tree.ofLeaf(countLeaf);
                    final Object c = cb == null ? null : cb.value(entry);
                    final long count = c instanceof Number num ? num.longValue()
                        : c instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Number num2 ? num2.longValue() : 0;
                    n *= (int) count;
                    values.add(readLeaf(e, leaf, n, r, true));
                } else {
                    values.add(readLeaf(e, leaf, n, r, leaf.integer("fLen") > 1));
                }
            }
            return values.size() == 1 ? values.get(0) : values;
        }

        private static Object readLeaf(RBuffer e, RObject leaf, int n, ObjectReader r, boolean array) {
            final boolean unsigned = leaf.number("fIsUnsigned") != 0;
            final int type = switch (leaf.className) {
                case "TLeafB" -> unsigned ? Streamers.K_UCHAR : Streamers.K_CHAR;
                case "TLeafS" -> unsigned ? Streamers.K_USHORT : Streamers.K_SHORT;
                case "TLeafI" -> unsigned ? Streamers.K_UINT : Streamers.K_INT;
                case "TLeafL", "TLeafG" -> Streamers.K_LONG64;
                case "TLeafF" -> Streamers.K_FLOAT;
                case "TLeafD" -> Streamers.K_DOUBLE;
                case "TLeafO" -> Streamers.K_BOOL;
                case "TLeafD32" -> Streamers.K_DOUBLE32;
                case "TLeafF16" -> Streamers.K_FLOAT16;
                case "TLeafC" -> -1;
                default -> 0;
            };
            if (type == -1) return e.tstring();
            if (type == 0) throw new IllegalStateException("leaves of class " + leaf.className + " are not read by this reader");
            if (!array) return r.readBasic(e, type, leaf.string("fTitle"));
            return r.readBasicArray(e, type, n, leaf.string("fTitle"));
        }

        /** The numbers at an entry, flattened (an array, a collection, a single value). */
        public double[] numbers(long entry) throws IOException {
            return flatten(value(entry));
        }

        static double[] flatten(Object v) {
            if (v == null) return new double[0];
            if (v instanceof Number n) return new double[]{n.doubleValue()};
            if (v instanceof Boolean bo) return new double[]{bo ? 1 : 0};
            if (v instanceof double[] a) return a;
            if (v instanceof float[] a) {
                final double[] out = new double[a.length];
                for (int i = 0; i < a.length; i++) out[i] = a[i];
                return out;
            }
            if (v instanceof int[] a) {
                final double[] out = new double[a.length];
                for (int i = 0; i < a.length; i++) out[i] = a[i];
                return out;
            }
            if (v instanceof long[] a) {
                final double[] out = new double[a.length];
                for (int i = 0; i < a.length; i++) out[i] = a[i];
                return out;
            }
            if (v instanceof short[] a) {
                final double[] out = new double[a.length];
                for (int i = 0; i < a.length; i++) out[i] = a[i];
                return out;
            }
            if (v instanceof byte[] a) {
                final double[] out = new double[a.length];
                for (int i = 0; i < a.length; i++) out[i] = a[i];
                return out;
            }
            if (v instanceof boolean[] a) {
                final double[] out = new double[a.length];
                for (int i = 0; i < a.length; i++) out[i] = a[i] ? 1 : 0;
                return out;
            }
            if (v instanceof List<?> l) {
                final List<double[]> parts = new ArrayList<>();
                int n = 0;
                for (Object o : l) {
                    final double[] p = flatten(o);
                    parts.add(p);
                    n += p.length;
                }
                final double[] out = new double[n];
                int at = 0;
                for (double[] p : parts) {
                    System.arraycopy(p, 0, out, at, p.length);
                    at += p.length;
                }
                return out;
            }
            return new double[0];
        }

        /** Every number of the branch over all entries: what a histogram of it is filled with. */
        public double[] column() throws IOException {
            final long n = entries();
            double[] out = new double[(int) Math.min(Integer.MAX_VALUE - 8, Math.max(16, n))];
            int at = 0;
            for (long i = 0; i < n; i++) {
                final double[] v = numbers(i);
                if (at + v.length > out.length) out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, at + v.length));
                System.arraycopy(v, 0, out, at, v.length);
                at += v.length;
            }
            return java.util.Arrays.copyOf(out, at);
        }

        @Override
        public String toString() {
            return name() + " (" + b.className + ")";
        }
    }
}

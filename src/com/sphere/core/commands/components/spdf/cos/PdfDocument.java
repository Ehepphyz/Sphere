package com.sphere.components.spdf.cos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A PDF document in memory: the file's bytes, its cross-reference (tables,
 * streams, hybrids, incremental updates), the objects loaded or changed, and
 * the page tree. A file whose cross-reference is missing or wrong is rebuilt
 * by scanning its objects, as viewers do, so damaged files still open.
 *
 * Objects load lazily and stay in memory once read; changing one is done
 * through {@link #putObject}. The edit history snapshots objects before it
 * touches them, which is how every edit can be undone.
 */
public final class PdfDocument {

    private static final byte[] STARTXREF = "startxref".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] TRAILER = "trailer".getBytes(StandardCharsets.ISO_8859_1);

    /** Where an object lives: at an offset, or inside an object stream. */
    private record Entry(int type, long offset, int index) {
    }

    private final byte[] data;
    private final Map<Integer, Entry> xref = new HashMap<>();
    private final Map<Integer, PdfObject> objects = new HashMap<>();
    private final Set<Integer> deleted = new HashSet<>();
    private final Map<Integer, ObjStm> objStms = new HashMap<>();
    private final Set<Integer> loading = new HashSet<>();
    private Map<Integer, Long> scanIndex;
    private PdfDict trailer = new PdfDict();
    private PdfCrypt crypt;
    private int encryptNum = -1;
    private int maxNum;
    private String version = "1.7";
    private boolean repaired;
    private List<PdfPage> pages;
    private int modCount;
    private final List<String> warnings = new ArrayList<>();

    private static final class ObjStm {
        int[] nums;
        int[] offsets;
        byte[] data;
    }

    private PdfDocument(byte[] data) {
        this.data = data;
    }

    public static PdfDocument open(Path file, String password) throws IOException {
        return open(Files.readAllBytes(file), password);
    }

    public static PdfDocument open(byte[] data, String password) throws IOException {
        PdfDocument d = new PdfDocument(data);
        d.load(password);
        return d;
    }

    /** A new document with no pages. */
    public static PdfDocument create() {
        PdfDocument d = new PdfDocument(new byte[0]);
        PdfDict pagesNode = new PdfDict();
        pagesNode.put("Type", "Pages");
        pagesNode.put("Kids", new PdfArray());
        pagesNode.put("Count", 0);
        PdfRef pr = d.addObject(pagesNode);
        PdfDict cat = new PdfDict();
        cat.put("Type", "Catalog");
        cat.put("Pages", pr);
        d.trailer.put("Root", d.addObject(cat));
        return d;
    }

    /* ---------------------------------------------------------------- loading */

    private void load(String password) throws IOException {
        if (data.length < 8) {
            throw new IOException("Not a PDF file (too short)");
        }
        int head = PdfLexer.indexOf(data, "%PDF-".getBytes(StandardCharsets.ISO_8859_1), 0, Math.min(data.length, 2048));
        if (head < 0) {
            throw new IOException("Not a PDF file (no %PDF header)");
        }
        if (head + 8 <= data.length) {
            version = new String(data, head + 5, 3, StandardCharsets.ISO_8859_1).trim();
        }
        boolean ok;
        try {
            ok = readXrefChain();
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok || !(trailer.get("Root") instanceof PdfDict)) {
            rebuild();
        }
        if (!(trailer.get("Root") instanceof PdfDict)) {
            throw new IOException("No document catalog: the file is too damaged to read");
        }
        PdfObject enc = trailer.getRaw("Encrypt");
        if (enc instanceof PdfRef er) {
            encryptNum = er.num;
        }
        PdfObject encDict = PdfRef.deref(enc);
        if (encDict instanceof PdfDict ed) {
            byte[] id0 = new byte[0];
            PdfArray ids = trailer.getArray("ID");
            if (ids != null && ids.get(0) instanceof PdfString s) {
                id0 = s.getBytes();
            }
            crypt = new PdfCrypt(ed, id0, password);
            // objects read before the key was known (the catalog, through the checks) are read again
            Map<Integer, PdfObject> keep = new HashMap<>();
            if (encryptNum >= 0 && objects.containsKey(encryptNum)) {
                keep.put(encryptNum, objects.get(encryptNum));
            }
            objects.clear();
            objStms.clear();
            objects.putAll(keep);
        }
    }

    private boolean readXrefChain() {
        int sx = PdfLexer.lastIndexOf(data, STARTXREF, data.length);
        if (sx < 0) {
            return false;
        }
        PdfLexer lx = new PdfLexer(data, sx + STARTXREF.length, data.length);
        Object t = lx.next();
        if (!(t instanceof PdfNumber n)) {
            return false;
        }
        long offset = n.longValue();
        Set<Long> visited = new HashSet<>();
        boolean first = true;
        while (offset > 0 && offset < data.length && visited.add(offset)) {
            PdfDict section = readSection((int) offset);
            if (section == null) {
                return !first && trailer.has("Root");
            }
            if (first) {
                trailer = section;
                first = false;
            } else {
                for (var e : section.entries()) {
                    if (!trailer.keys().contains(e.getKey())) {
                        trailer.put(e.getKey(), e.getValue());
                    }
                }
            }
            long prev = section.getLong("Prev", -1);
            offset = prev;
        }
        return !first;
    }

    /** Reads one xref section (table or stream) at an offset, returning its trailer dictionary. */
    private PdfDict readSection(int offset) {
        PdfLexer lx = new PdfLexer(data, offset, data.length);
        lx.skipWhite();
        if (!lx.at("xref")) {
            // a little off? look around the offset for the keyword
            int near = PdfLexer.indexOf(data, "xref".getBytes(StandardCharsets.ISO_8859_1), Math.max(0, offset - 64),
                                        Math.min(data.length, offset + 64));
            if (near >= 0 && (near == 0 || data[near - 1] != 't')) {
                lx.pos = near;
            }
        }
        if (lx.at("xref")) {
            lx.pos += 4;
            readTable(lx);
            lx.skipWhite();
            if (!lx.at("trailer")) {
                int tr = PdfLexer.indexOf(data, TRAILER, lx.pos, Math.min(data.length, lx.pos + 4096));
                if (tr < 0) {
                    return null;
                }
                lx.pos = tr;
            }
            lx.pos += TRAILER.length;
            PdfObject t = new PdfParser(lx, this).parseObject();
            if (!(t instanceof PdfDict td)) {
                return null;
            }
            long xs = td.getLong("XRefStm", -1);
            if (xs > 0 && xs < data.length) {
                readXrefStream((int) xs);
            }
            return td;
        }
        return readXrefStream(offset);
    }

    private void readTable(PdfLexer lx) {
        while (true) {
            lx.skipWhite();
            if (lx.at("trailer") || lx.pos >= lx.end) {
                return;
            }
            Object a = lx.next();
            Object b = lx.next();
            if (!(a instanceof PdfNumber start) || !(b instanceof PdfNumber count)) {
                return;
            }
            int s = start.intValue();
            int c = count.intValue();
            for (int i = 0; i < c; i++) {
                Object off = lx.next();
                Object gen = lx.next();
                Object kind = lx.next();
                if (!(off instanceof PdfNumber o) || !(gen instanceof PdfNumber) || !(kind instanceof String k)) {
                    return;
                }
                // the classic off-by-one: a section numbered from 1 starting with object 0's free entry
                if (i == 0 && s == 1 && o.longValue() == 0 && "f".equals(k)) {
                    s = 0;
                }
                int num = s + i;
                maxNum = Math.max(maxNum, num);
                if ("n".equals(k) && o.longValue() > 0 && !xref.containsKey(num)) {
                    xref.put(num, new Entry(1, o.longValue(), 0));
                }
            }
        }
    }

    private PdfDict readXrefStream(int offset) {
        PdfParser p = new PdfParser(new PdfLexer(data, offset, data.length), this);
        PdfParser.Indirect ind;
        try {
            ind = p.parseIndirect();
        } catch (RuntimeException e) {
            return null;
        }
        if (ind == null || !(ind.object() instanceof PdfStream s)) {
            return null;
        }
        int[] w = new int[3];
        PdfArray wa = s.getArray("W");
        if (wa == null || wa.size() < 3) {
            return null;
        }
        for (int i = 0; i < 3; i++) {
            w[i] = wa.getInt(i, 0);
        }
        int size = s.getInt("Size", 0);
        maxNum = Math.max(maxNum, size - 1);
        PdfArray index = s.getArray("Index");
        if (index == null) {
            index = PdfArray.ofNumbers(0, size);
        }
        byte[] d = s.getDecoded();
        int rowLen = w[0] + w[1] + w[2];
        int pos = 0;
        for (int k = 0; k + 1 < index.size(); k += 2) {
            int first = index.getInt(k, 0);
            int count = index.getInt(k + 1, 0);
            for (int i = 0; i < count && pos + rowLen <= d.length; i++) {
                int type = w[0] == 0 ? 1 : (int) field(d, pos, w[0]);
                long f2 = field(d, pos + w[0], w[1]);
                long f3 = field(d, pos + w[0] + w[1], w[2]);
                pos += rowLen;
                int num = first + i;
                if (xref.containsKey(num)) {
                    continue;
                }
                if (type == 1 && f2 > 0) {
                    xref.put(num, new Entry(1, f2, 0));
                } else if (type == 2) {
                    xref.put(num, new Entry(2, f2, (int) f3));
                }
            }
        }
        PdfDict t = new PdfDict();
        s.copyInto(t);
        for (String k : new String[] { "Length", "Filter", "DecodeParms", "W", "Index", "Type" }) {
            t.remove(k);
        }
        return t;
    }

    private static long field(byte[] d, int pos, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            v = (v << 8) | (d[pos + i] & 0xff);
        }
        return v;
    }

    /** Rebuilds the cross-reference by scanning every "n g obj" of the file. */
    private void rebuild() {
        repaired = true;
        warnings.add("The cross-reference table was damaged; it was rebuilt by scanning the file.");
        xref.clear();
        objects.clear();
        objStms.clear();
        Map<Integer, Long> idx = scanIndex();
        for (var e : idx.entrySet()) {
            xref.put(e.getKey(), new Entry(1, e.getValue(), 0));
            maxNum = Math.max(maxNum, e.getKey());
        }
        // objects inside object streams
        for (var e : new ArrayList<>(idx.entrySet())) {
            PdfObject o = getObject(e.getKey());
            if (o instanceof PdfStream s && s.isType("ObjStm")) {
                ObjStm os = objStm(e.getKey());
                if (os != null) {
                    for (int i = 0; i < os.nums.length; i++) {
                        if (!xref.containsKey(os.nums[i])) {
                            xref.put(os.nums[i], new Entry(2, e.getKey(), i));
                            maxNum = Math.max(maxNum, os.nums[i]);
                        }
                    }
                }
            }
        }
        // the trailer: the last one naming a catalog, else an XRef stream's, else a catalog found by type
        PdfDict best = null;
        int at = data.length;
        while ((at = PdfLexer.lastIndexOf(data, TRAILER, at)) >= 0) {
            PdfLexer lx = new PdfLexer(data, at + TRAILER.length, data.length);
            PdfObject t = new PdfParser(lx, this).parseObject();
            if (t instanceof PdfDict td && td.get("Root") instanceof PdfDict) {
                best = td;
                break;
            }
        }
        if (best == null) {
            for (int num : idx.keySet()) {
                PdfObject o = getObject(num);
                if (o instanceof PdfStream s && s.isType("XRef") && s.get("Root") instanceof PdfDict) {
                    best = new PdfDict();
                    s.copyInto(best);
                    for (String k : new String[] { "Length", "Filter", "DecodeParms", "W", "Index", "Type", "Prev" }) {
                        best.remove(k);
                    }
                }
            }
        }
        if (best == null) {
            best = new PdfDict();
            for (int num : idx.keySet()) {
                if (getObject(num) instanceof PdfDict d && d.isType("Catalog") && d.has("Pages")) {
                    best.put("Root", new PdfRef(num, 0, this));
                }
                if (getObject(num) instanceof PdfDict d && !best.has("Info")
                    && (d.has("Producer") || d.has("Creator")) && !d.has("Type")) {
                    best.put("Info", new PdfRef(num, 0, this));
                }
            }
        }
        best.remove("Prev");
        best.remove("XRefStm");
        trailer = best;
    }

    /** Offsets of every "num gen obj" in the file, the last definition of a number winning. */
    private Map<Integer, Long> scanIndex() {
        if (scanIndex != null) {
            return scanIndex;
        }
        Map<Integer, Long> idx = new HashMap<>();
        byte[] b = data;
        for (int i = 0; i + 3 <= b.length; i++) {
            if (b[i] != 'o' || b[i + 1] != 'b' || b[i + 2] != 'j') {
                continue;
            }
            if (i + 3 < b.length && PdfLexer.isRegular(b[i + 3] & 0xff)) {
                continue;
            }
            int p = i - 1;
            int ws = 0;
            while (p >= 0 && PdfLexer.isWhite(b[p] & 0xff)) {
                p--;
                ws++;
            }
            if (ws == 0) {
                continue;
            }
            int genEnd = p;
            while (p >= 0 && b[p] >= '0' && b[p] <= '9') {
                p--;
            }
            if (p == genEnd) {
                continue;
            }
            ws = 0;
            while (p >= 0 && PdfLexer.isWhite(b[p] & 0xff)) {
                p--;
                ws++;
            }
            if (ws == 0) {
                continue;
            }
            int numEnd = p;
            while (p >= 0 && b[p] >= '0' && b[p] <= '9') {
                p--;
            }
            if (p == numEnd || numEnd - p > 10) {
                continue;
            }
            if (p >= 0 && PdfLexer.isRegular(b[p] & 0xff)) {
                continue;
            }
            long num = 0;
            for (int k = p + 1; k <= numEnd; k++) {
                num = num * 10 + (b[k] - '0');
            }
            if (num > 0 && num < 50_000_000) {
                idx.put((int) num, (long) (p + 1));
            }
        }
        scanIndex = idx;
        return idx;
    }

    /* ---------------------------------------------------------------- objects */

    /** The object with a number, loaded if needed; PdfNull when there is none. */
    public synchronized PdfObject getObject(int num) {
        PdfObject o = objects.get(num);
        if (o != null) {
            return o;
        }
        if (deleted.contains(num)) {
            return PdfNull.NULL;
        }
        Entry e = xref.get(num);
        if (e == null || !loading.add(num)) {
            return PdfNull.NULL;
        }
        try {
            o = e.type == 2 ? loadCompressed(num, (int) e.offset, e.index) : loadAt(num, e.offset);
        } catch (RuntimeException ex) {
            o = null;
        } finally {
            loading.remove(num);
        }
        if (o == null) {
            o = PdfNull.NULL;
        }
        objects.put(num, o);
        return o;
    }

    /** Used while a stream reads its /Length: no recursion into the object being read. */
    PdfObject getObjectNoStream(int num) {
        return getObject(num);
    }

    private PdfObject loadAt(int num, long offset) {
        PdfParser.Indirect ind = parseAt(offset);
        if (ind == null || ind.num() != num) {
            Long other = scanIndex().get(num);
            if (other != null && other != offset) {
                ind = parseAt(other);
            }
        }
        if (ind == null || ind.num() != num) {
            return null;
        }
        PdfObject o = ind.object();
        if (crypt != null && num != encryptNum) {
            boolean isXref = o instanceof PdfStream s && s.isType("XRef");
            if (!isXref) {
                crypt.decryptStrings(o, num, ind.gen(), 0);
                if (o instanceof PdfStream s) {
                    boolean meta = s.isType("Metadata") && !crypt.encryptMetadata;
                    if (!meta && !"Crypt".equals(s.getName("Filter"))) {
                        s.setRaw(crypt.decryptStream(s.getRaw(), num, ind.gen()));
                    }
                }
            }
        }
        return o;
    }

    private PdfParser.Indirect parseAt(long offset) {
        if (offset < 0 || offset >= data.length) {
            return null;
        }
        try {
            return new PdfParser(new PdfLexer(data, (int) offset, data.length), this).parseIndirect();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ObjStm objStm(int stmNum) {
        ObjStm os = objStms.get(stmNum);
        if (os != null) {
            return os;
        }
        if (!(getObject(stmNum) instanceof PdfStream s)) {
            return null;
        }
        byte[] d = s.getDecoded();
        int n = s.getInt("N", 0);
        int first = s.getInt("First", 0);
        PdfLexer lx = new PdfLexer(d, 0, Math.min(d.length, first));
        os = new ObjStm();
        os.nums = new int[n];
        os.offsets = new int[n];
        for (int i = 0; i < n; i++) {
            Object a = lx.next();
            Object b = lx.next();
            if (!(a instanceof PdfNumber x) || !(b instanceof PdfNumber y)) {
                os.nums = java.util.Arrays.copyOf(os.nums, i);
                os.offsets = java.util.Arrays.copyOf(os.offsets, i);
                break;
            }
            os.nums[i] = x.intValue();
            os.offsets[i] = first + y.intValue();
        }
        os.data = d;
        objStms.put(stmNum, os);
        return os;
    }

    private PdfObject loadCompressed(int num, int stmNum, int index) {
        ObjStm os = objStm(stmNum);
        if (os == null) {
            return null;
        }
        int at = -1;
        if (index >= 0 && index < os.nums.length && os.nums[index] == num) {
            at = index;
        } else {
            for (int i = 0; i < os.nums.length; i++) {
                if (os.nums[i] == num) {
                    at = i;
                    break;
                }
            }
        }
        if (at < 0) {
            return null;
        }
        PdfLexer lx = new PdfLexer(os.data, os.offsets[at], os.data.length);
        return new PdfParser(lx, this).parseObject();
    }

    /** Replaces (or, with null, deletes) the object with a number. */
    public synchronized void putObject(int num, PdfObject o) {
        if (o == null || o == PdfNull.NULL) {
            objects.remove(num);
            deleted.add(num);
        } else {
            objects.put(num, o);
            deleted.remove(num);
        }
        maxNum = Math.max(maxNum, num);
        modCount++;
    }

    /** Adds an object under a new number and returns its reference. */
    public synchronized PdfRef addObject(PdfObject o) {
        int num = ++maxNum;
        while (xref.containsKey(num) || objects.containsKey(num)) {
            num = ++maxNum;
        }
        objects.put(num, o);
        deleted.remove(num);
        modCount++;
        return new PdfRef(num, 0, this);
    }

    public PdfRef ref(int num) {
        return new PdfRef(num, 0, this);
    }

    public boolean exists(int num) {
        return !deleted.contains(num) && (objects.containsKey(num) || xref.containsKey(num));
    }

    /* ---------------------------------------------------------------- structure */

    public PdfDict trailer() {
        return trailer;
    }

    public PdfDict catalog() {
        return trailer.getDict("Root");
    }

    public PdfDict info() {
        return trailer.getDict("Info");
    }

    /** The document information dictionary, created if missing. */
    public PdfDict infoForWriting() {
        PdfDict i = info();
        if (i == null) {
            i = new PdfDict();
            trailer.put("Info", addObject(i));
        }
        return i;
    }

    public String version() {
        return version;
    }

    public boolean wasRepaired() {
        return repaired;
    }

    public boolean isEncrypted() {
        return crypt != null;
    }

    public int permissions() {
        return crypt == null ? -1 : crypt.permissions;
    }

    public List<String> warnings() {
        return warnings;
    }

    public int modCount() {
        return modCount;
    }

    /** Called after the page tree or the objects changed outside {@link #putObject}. */
    public void changed() {
        modCount++;
        pages = null;
    }

    /** The pages in order, the inherited attributes resolved. */
    public synchronized List<PdfPage> pages() {
        if (pages == null) {
            List<PdfPage> out = new ArrayList<>();
            PdfDict cat = catalog();
            PdfObject root = cat == null ? null : cat.getRaw("Pages");
            collect(root, 0, out, Collections.newSetFromMap(new IdentityHashMap<>()));
            pages = out;
        }
        return pages;
    }

    public int pageCount() {
        return pages().size();
    }

    private void collect(PdfObject node, int depth, List<PdfPage> out, Set<PdfDict> seen) {
        if (depth > 64 || node == null) {
            return;
        }
        int num = node instanceof PdfRef r ? r.num : -1;
        PdfObject o = PdfRef.deref(node);
        if (!(o instanceof PdfDict d) || !seen.add(d)) {
            return;
        }
        PdfArray kids = d.getArray("Kids");
        boolean isPage = d.isType("Page") || (kids == null && !d.isType("Pages"));
        if (isPage) {
            if (num < 0) {
                num = addObject(d).num;
            }
            out.add(new PdfPage(this, num, d));
            return;
        }
        if (kids != null) {
            for (int i = 0; i < kids.size(); i++) {
                PdfObject k = kids.getRaw(i);
                if (k instanceof PdfDict kd) {
                    // a page held directly in Kids: made an object of its own so edits can name it
                    PdfRef nr = addObject(kd);
                    kids.set(i, nr);
                    k = nr;
                }
                collect(k, depth + 1, out, seen);
            }
        }
    }

    /**
     * Makes the page tree a single /Pages node holding every page, each page
     * carrying what it used to inherit. Reordering, inserting and deleting
     * pages then only rewrite one array.
     */
    public PdfDict flattenPageTree() {
        List<PdfPage> list = pages();
        PdfDict cat = catalog();
        PdfObject rootRaw = cat.getRaw("Pages");
        PdfDict root;
        int rootNum;
        if (rootRaw instanceof PdfRef rr && rr.resolve() instanceof PdfDict rd) {
            root = rd;
            rootNum = rr.num;
        } else {
            root = new PdfDict();
            PdfRef nr = addObject(root);
            rootNum = nr.num;
            cat.put("Pages", nr);
        }
        PdfArray kids = new PdfArray();
        for (PdfPage p : list) {
            PdfDict d = p.dict();
            for (String key : PdfPage.INHERITABLE) {
                if (!d.has(key)) {
                    PdfObject v = p.inheritedRaw(key);
                    if (v != null) {
                        d.put(key, v);
                    }
                }
            }
            d.put("Parent", new PdfRef(rootNum, 0, this));
            kids.add(new PdfRef(p.objNum(), 0, this));
        }
        root.put("Type", "Pages");
        root.put("Kids", kids);
        root.put("Count", list.size());
        root.remove("Resources");
        root.remove("MediaBox");
        root.remove("Rotate");
        root.remove("Parent");
        changed();
        return root;
    }

    /** Every object number in use, loaded or not. */
    public Set<Integer> objectNumbers() {
        Set<Integer> s = new HashSet<>(xref.keySet());
        s.addAll(objects.keySet());
        s.removeAll(deleted);
        return s;
    }

    public byte[] originalBytes() {
        return data;
    }
}

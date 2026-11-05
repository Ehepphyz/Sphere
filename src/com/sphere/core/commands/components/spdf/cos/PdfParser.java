package com.sphere.components.spdf.cos;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Objects from tokens: arrays, dictionaries, "n g R" references, and the
 * indirect objects of the file body with their streams. A stream whose
 * /Length is wrong (it happens) is measured up to its endstream instead.
 */
public final class PdfParser {

    private static final byte[] ENDSTREAM = "endstream".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] ENDOBJ = "endobj".getBytes(StandardCharsets.ISO_8859_1);

    public final PdfLexer lx;
    private final PdfDocument doc;

    public PdfParser(PdfLexer lx, PdfDocument doc) {
        this.lx = lx;
        this.doc = doc;
    }

    public PdfParser(byte[] data, PdfDocument doc) {
        this(new PdfLexer(data), doc);
    }

    /** The next object; a keyword other than true/false/null comes back as PdfNull. */
    public PdfObject parseObject() {
        return parseFrom(lx.next(), 0);
    }

    PdfObject parseFrom(Object t, int depth) {
        if (depth > 256) {
            return PdfNull.NULL;
        }
        if (t instanceof PdfNumber n) {
            if (n.isInteger() && n.longValue() >= 0) {
                int save = lx.pos;
                Object t2 = lx.next();
                if (t2 instanceof PdfNumber g && g.isInteger() && g.longValue() >= 0) {
                    Object t3 = lx.next();
                    if ("R".equals(t3)) {
                        return new PdfRef(n.intValue(), g.intValue(), doc);
                    }
                }
                lx.pos = save;
            }
            return n;
        }
        if (t instanceof PdfObject o) {
            return o;
        }
        if (t == PdfLexer.Tok.ARRAY_START) {
            PdfArray a = new PdfArray();
            while (true) {
                Object x = lx.next();
                if (x == PdfLexer.Tok.ARRAY_END || x == PdfLexer.Tok.EOF) {
                    break;
                }
                if (x == PdfLexer.Tok.DICT_END) {
                    continue;
                }
                if (x instanceof String kw && isStructural(kw)) {
                    lx.pos -= kw.length();
                    break;
                }
                a.add(parseFrom(x, depth + 1));
            }
            return a;
        }
        if (t == PdfLexer.Tok.DICT_START) {
            PdfDict d = new PdfDict();
            while (true) {
                Object k = lx.next();
                if (k == PdfLexer.Tok.DICT_END || k == PdfLexer.Tok.EOF) {
                    break;
                }
                if (k instanceof String kw && isStructural(kw)) {
                    lx.pos -= kw.length();
                    break;
                }
                if (!(k instanceof PdfName key)) {
                    continue;
                }
                Object v = lx.next();
                if (v == PdfLexer.Tok.DICT_END) {
                    d.put(key, PdfNull.NULL);
                    break;
                }
                d.put(key, parseFrom(v, depth + 1));
            }
            return d;
        }
        if (t instanceof String kw) {
            switch (kw) {
                case "true": return PdfBoolean.TRUE;
                case "false": return PdfBoolean.FALSE;
                default: return PdfNull.NULL;
            }
        }
        return PdfNull.NULL;
    }

    private static boolean isStructural(String kw) {
        return kw.equals("endobj") || kw.equals("stream") || kw.equals("endstream") || kw.equals("obj")
            || kw.equals("xref") || kw.equals("trailer") || kw.equals("startxref");
    }

    /** What an indirect object definition holds. */
    public record Indirect(int num, int gen, PdfObject object) {
    }

    /**
     * Parses "num gen obj ... endobj" at the lexer's position; null when the
     * position does not hold an object header.
     */
    public Indirect parseIndirect() {
        Object a = lx.next();
        Object b = lx.next();
        Object c = lx.next();
        if (!(a instanceof PdfNumber num) || !(b instanceof PdfNumber gen) || !"obj".equals(c)) {
            return null;
        }
        int objStart = lx.pos;
        PdfObject o = parseObject();
        int afterObj = lx.pos;
        Object next = lx.next();
        if ("stream".equals(next) && o instanceof PdfDict dict) {
            o = readStream(dict, num.intValue(), gen.intValue());
        } else {
            lx.pos = afterObj;
        }
        if (objStart < 0) {
            return null;
        }
        return new Indirect(num.intValue(), gen.intValue(), o);
    }

    private PdfStream readStream(PdfDict dict, int num, int gen) {
        byte[] b = lx.buf;
        int p = lx.pos;
        // the keyword is followed by CRLF or LF (a lone CR from sloppy writers)
        if (p < lx.end && b[p] == '\r') {
            p++;
        }
        if (p < lx.end && b[p] == '\n') {
            p++;
        }
        final int start = p;
        int length = -1;
        PdfObject lenObj = dict.getRaw(PdfName.LENGTH);
        if (lenObj instanceof PdfNumber n) {
            length = n.intValue();
        } else if (lenObj instanceof PdfRef r && doc != null) {
            PdfObject resolved = doc.getObjectNoStream(r.num);
            if (resolved instanceof PdfNumber n) {
                length = n.intValue();
            }
        }
        int dataEnd = -1;
        if (length >= 0 && start + length <= lx.end) {
            int q = start + length;
            int k = q;
            while (k < lx.end && PdfLexer.isWhite(b[k] & 0xff) && k - q < 8) {
                k++;
            }
            if (matches(b, k, ENDSTREAM)) {
                dataEnd = q;
                lx.pos = k + ENDSTREAM.length;
            }
        }
        if (dataEnd < 0) {
            int es = PdfLexer.indexOf(b, ENDSTREAM, start, lx.end);
            int eo = PdfLexer.indexOf(b, ENDOBJ, start, lx.end);
            if (es < 0 || (eo >= 0 && eo < es)) {
                es = eo >= 0 ? eo : lx.end;
                lx.pos = es;
            } else {
                lx.pos = es + ENDSTREAM.length;
            }
            dataEnd = es;
            // the EOL before endstream belongs to the syntax, not the data
            if (dataEnd > start && b[dataEnd - 1] == '\n') {
                dataEnd--;
            }
            if (dataEnd > start && b[dataEnd - 1] == '\r') {
                dataEnd--;
            }
        }
        byte[] data = Arrays.copyOfRange(b, start, Math.max(start, dataEnd));
        PdfStream s = new PdfStream(dict, data);
        s.put(PdfName.LENGTH, new PdfNumber(data.length));
        return s;
    }

    private static boolean matches(byte[] b, int at, byte[] what) {
        if (at < 0 || at + what.length > b.length) {
            return false;
        }
        for (int i = 0; i < what.length; i++) {
            if (b[at + i] != what[i]) {
                return false;
            }
        }
        return true;
    }
}

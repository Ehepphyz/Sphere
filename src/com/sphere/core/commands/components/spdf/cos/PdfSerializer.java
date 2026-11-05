package com.sphere.components.spdf.cos;

import java.util.Map;
import java.util.function.IntUnaryOperator;

/** Writes objects in PDF syntax; references can be renumbered on the way out. */
public final class PdfSerializer {

    private PdfSerializer() {
    }

    public static void write(PdfObject o, ByteBuf out) {
        write(o, out, null);
    }

    /** @param renumber old object number to new one; -1 writes the reference as null */
    public static void write(PdfObject o, ByteBuf out, IntUnaryOperator renumber) {
        if (o == null || o instanceof PdfNull) {
            out.put("null");
        } else if (o instanceof PdfBoolean b) {
            out.put(b.value ? "true" : "false");
        } else if (o instanceof PdfNumber n) {
            out.put(n.toString());
        } else if (o instanceof PdfName n) {
            writeName(n, out);
        } else if (o instanceof PdfString s) {
            writeString(s, out);
        } else if (o instanceof PdfRef r) {
            int num = renumber == null ? r.num : renumber.applyAsInt(r.num);
            if (num < 0) {
                out.put("null");
            } else {
                out.put(Integer.toString(num)).put(renumber == null ? " " + r.gen + " R" : " 0 R");
            }
        } else if (o instanceof PdfArray a) {
            out.put('[');
            boolean first = true;
            for (PdfObject x : a.rawList()) {
                if (!first && needsSpace(out)) {
                    out.put(' ');
                }
                write(x, out, renumber);
                first = false;
            }
            out.put(']');
        } else if (o instanceof PdfDict d) {
            out.put("<<");
            for (Map.Entry<PdfName, PdfObject> e : d.entries()) {
                writeName(e.getKey(), out);
                PdfObject v = e.getValue();
                if (!(v instanceof PdfName || v instanceof PdfArray || v instanceof PdfString || v instanceof PdfDict)) {
                    out.put(' ');
                }
                write(v, out, renumber);
            }
            out.put(">>");
        }
    }

    private static boolean needsSpace(ByteBuf out) {
        int c = out.last() & 0xff;
        return !(c == ']' || c == '>' || c == ')' || c == '[');
    }

    public static void writeName(PdfName n, ByteBuf out) {
        out.put('/');
        String s = n.name;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i) & 0xff;
            if (c < 0x21 || c > 0x7E || c == '#' || PdfLexer.isDelimiter(c)) {
                out.put('#');
                out.put(Character.forDigit(c >> 4, 16));
                out.put(Character.forDigit(c & 15, 16));
            } else {
                out.put(c);
            }
        }
    }

    public static void writeString(PdfString s, ByteBuf out) {
        byte[] b = s.getBytes();
        int binary = 0;
        for (byte x : b) {
            int c = x & 0xff;
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t' || c > 0x7E) {
                binary++;
            }
        }
        if (s.hex || binary > b.length / 4) {
            out.put('<');
            for (byte x : b) {
                int c = x & 0xff;
                out.put(Character.forDigit(c >> 4, 16));
                out.put(Character.forDigit(c & 15, 16));
            }
            out.put('>');
            return;
        }
        out.put('(');
        for (byte x : b) {
            int c = x & 0xff;
            switch (c) {
                case '(': case ')': case '\\':
                    out.put('\\').put(c);
                    break;
                case '\n':
                    out.put("\\n");
                    break;
                case '\r':
                    out.put("\\r");
                    break;
                default:
                    if (c < 0x20 || c > 0x7E) {
                        out.put('\\');
                        out.put('0' + ((c >> 6) & 7));
                        out.put('0' + ((c >> 3) & 7));
                        out.put('0' + (c & 7));
                    } else {
                        out.put(c);
                    }
            }
        }
        out.put(')');
    }
}

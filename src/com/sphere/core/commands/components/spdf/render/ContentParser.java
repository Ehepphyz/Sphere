package com.sphere.components.spdf.render;

import com.sphere.components.spdf.cos.ByteBuf;
import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfBoolean;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfLexer;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfNull;
import com.sphere.components.spdf.cos.PdfNumber;
import com.sphere.components.spdf.cos.PdfObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a content stream into operations. Operands are collected until an
 * operator; an inline image (BI ... ID data EI) is read with its data, whose
 * end is found from /Length or the image size when they are known, else by
 * the EI that is followed by text rather than more binary.
 */
public final class ContentParser {

    private ContentParser() {
    }

    public static List<Op> parse(byte[] data) {
        List<Op> ops = new ArrayList<>(Math.max(16, data.length / 12));
        PdfLexer lx = new PdfLexer(data);
        List<PdfObject> operands = new ArrayList<>(8);
        while (true) {
            Object t = lx.next();
            if (t == PdfLexer.Tok.EOF) {
                break;
            }
            if (t instanceof PdfObject o) {
                operands.add(o);
                continue;
            }
            if (t == PdfLexer.Tok.ARRAY_START) {
                operands.add(readArray(lx, 0));
                continue;
            }
            if (t == PdfLexer.Tok.DICT_START) {
                operands.add(readDict(lx, 0));
                continue;
            }
            if (t instanceof String kw) {
                switch (kw) {
                    case "true" -> operands.add(PdfBoolean.TRUE);
                    case "false" -> operands.add(PdfBoolean.FALSE);
                    case "null" -> operands.add(PdfNull.NULL);
                    case "BI" -> {
                        Op img = readInlineImage(lx);
                        if (img != null) {
                            ops.add(img);
                        }
                        operands.clear();
                    }
                    default -> {
                        if (!kw.isEmpty()) {
                            ops.add(new Op(kw, operands.toArray(new PdfObject[0])));
                        }
                        operands.clear();
                    }
                }
            }
            // stray } { and the like are dropped
        }
        return ops;
    }

    private static PdfArray readArray(PdfLexer lx, int depth) {
        PdfArray a = new PdfArray();
        while (depth < 64) {
            Object t = lx.next();
            if (t == PdfLexer.Tok.ARRAY_END || t == PdfLexer.Tok.EOF) {
                break;
            }
            if (t instanceof PdfObject o) {
                a.add(o);
            } else if (t == PdfLexer.Tok.ARRAY_START) {
                a.add(readArray(lx, depth + 1));
            } else if (t == PdfLexer.Tok.DICT_START) {
                a.add(readDict(lx, depth + 1));
            } else if ("true".equals(t)) {
                a.add(PdfBoolean.TRUE);
            } else if ("false".equals(t)) {
                a.add(PdfBoolean.FALSE);
            } else if ("null".equals(t)) {
                a.add(PdfNull.NULL);
            } else if (t instanceof String kw) {
                // an operator inside an array: the array was not closed
                lx.pos -= kw.length();
                break;
            }
        }
        return a;
    }

    private static PdfDict readDict(PdfLexer lx, int depth) {
        PdfDict d = new PdfDict();
        while (depth < 64) {
            Object k = lx.next();
            if (k == PdfLexer.Tok.DICT_END || k == PdfLexer.Tok.EOF) {
                break;
            }
            if (!(k instanceof PdfName key)) {
                continue;
            }
            Object v = lx.next();
            PdfObject val;
            if (v instanceof PdfObject o) {
                val = o;
            } else if (v == PdfLexer.Tok.ARRAY_START) {
                val = readArray(lx, depth + 1);
            } else if (v == PdfLexer.Tok.DICT_START) {
                val = readDict(lx, depth + 1);
            } else if ("true".equals(v)) {
                val = PdfBoolean.TRUE;
            } else if ("false".equals(v)) {
                val = PdfBoolean.FALSE;
            } else if (v == PdfLexer.Tok.DICT_END) {
                break;
            } else {
                val = PdfNull.NULL;
            }
            d.put(key, val);
        }
        return d;
    }

    private static Op readInlineImage(PdfLexer lx) {
        PdfDict d = new PdfDict();
        while (true) {
            Object k = lx.next();
            if (k == PdfLexer.Tok.EOF) {
                return null;
            }
            if ("ID".equals(k)) {
                break;
            }
            if (!(k instanceof PdfName key)) {
                continue;
            }
            Object v = lx.next();
            PdfObject val;
            if (v instanceof PdfObject o) {
                val = o;
            } else if (v == PdfLexer.Tok.ARRAY_START) {
                val = readArray(lx, 1);
            } else if (v == PdfLexer.Tok.DICT_START) {
                val = readDict(lx, 1);
            } else if ("true".equals(v)) {
                val = PdfBoolean.TRUE;
            } else if ("false".equals(v)) {
                val = PdfBoolean.FALSE;
            } else if ("ID".equals(v)) {
                break;
            } else {
                val = PdfNull.NULL;
            }
            d.put(key, val);
        }
        byte[] b = lx.buf;
        int start = lx.pos;
        // one whitespace byte separates ID from the data
        if (start < lx.end && PdfLexer.isWhite(b[start] & 0xff)) {
            start++;
        }
        int end = -1;
        int len = d.getInt("L", d.getInt("Length", -1));
        if (len < 0) {
            len = unfilteredLength(d);
        }
        if (len >= 0 && start + len <= lx.end) {
            int k = start + len;
            int q = k;
            while (q < lx.end && PdfLexer.isWhite(b[q] & 0xff)) {
                q++;
            }
            if (q + 1 < lx.end && b[q] == 'E' && b[q + 1] == 'I'
                && (q + 2 >= lx.end || !PdfLexer.isRegular(b[q + 2] & 0xff))) {
                end = k;
                lx.pos = q + 2;
            }
        }
        if (end < 0) {
            int p = start;
            while (p + 1 < lx.end) {
                if (b[p] == 'E' && b[p + 1] == 'I' && (p == start || PdfLexer.isWhite(b[p - 1] & 0xff))
                    && (p + 2 >= lx.end || PdfLexer.isWhite(b[p + 2] & 0xff)) && looksLikeText(b, p + 2, lx.end)) {
                    end = p;
                    while (end > start && PdfLexer.isWhite(b[end - 1] & 0xff) && end - 1 >= start) {
                        end--;
                        break;
                    }
                    lx.pos = p + 2;
                    break;
                }
                p++;
            }
            if (end < 0) {
                end = lx.end;
                lx.pos = lx.end;
            }
        }
        byte[] data = java.util.Arrays.copyOfRange(b, start, Math.max(start, end));
        return new Op("BI", new PdfObject[0], d, data);
    }

    /** The data length of an inline image stored without filters, or -1. */
    private static int unfilteredLength(PdfDict d) {
        if (d.get("Filter", "F") != PdfNull.NULL) {
            return -1;
        }
        int w = d.getInt("W", d.getInt("Width", -1));
        int h = d.getInt("H", d.getInt("Height", -1));
        if (w <= 0 || h <= 0) {
            return -1;
        }
        boolean mask = d.getBool("IM", d.getBool("ImageMask", false));
        int bpc = mask ? 1 : d.getInt("BPC", d.getInt("BitsPerComponent", 8));
        int comps = 1;
        PdfObject cs = d.get("CS", "ColorSpace");
        if (!mask && cs instanceof PdfName n) {
            comps = switch (n.name) {
                case "RGB", "DeviceRGB", "CalRGB" -> 3;
                case "CMYK", "DeviceCMYK" -> 4;
                default -> 1;
            };
        } else if (!mask && cs instanceof PdfArray a && "Indexed".equals(a.getName(0)) || "I".equals(
            cs instanceof PdfArray a2 ? a2.getName(0) : null)) {
            comps = 1;
        }
        return ((w * comps * bpc + 7) / 8) * h;
    }

    private static boolean looksLikeText(byte[] b, int from, int end) {
        int n = Math.min(end, from + 48);
        for (int i = from; i < n; i++) {
            int c = b[i] & 0xff;
            if (c > 127 || (c < 32 && !PdfLexer.isWhite(c))) {
                return false;
            }
        }
        return true;
    }

    /** Writes operations back as a content stream. */
    public static byte[] write(List<Op> ops) {
        ByteBuf out = new ByteBuf(ops.size() * 16 + 64);
        for (Op op : ops) {
            op.write(out);
            out.put('\n');
        }
        return out.toBytes();
    }
}

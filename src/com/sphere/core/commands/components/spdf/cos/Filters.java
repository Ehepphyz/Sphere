package com.sphere.components.spdf.cos;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The standard filters (7.4): Flate and LZW with their predictors, ASCIIHex,
 * ASCII85, RunLength. The image codecs (DCT, JPX, JBIG2, CCITTFax) are left
 * for the image decoder, which gets the bytes still in their codec.
 * Corrupt data gives what could be decoded rather than nothing.
 */
public final class Filters {

    private Filters() {
    }

    public static boolean isImageCodec(String f) {
        return f.equals("DCTDecode") || f.equals("DCT") || f.equals("JPXDecode") || f.equals("JBIG2Decode")
            || f.equals("CCITTFaxDecode") || f.equals("CCF");
    }

    /** The image codec the data stays in after {@link #decode}, or null. */
    public static String imageFilter(PdfDict dict) {
        PdfObject f = dict.get("Filter", "F");
        String last = null;
        if (f instanceof PdfName n) {
            last = n.name;
        } else if (f instanceof PdfArray a && a.size() > 0) {
            last = a.getName(a.size() - 1);
        }
        if (last == null || !isImageCodec(last)) {
            return null;
        }
        return switch (last) {
            case "DCT" -> "DCTDecode";
            case "CCF" -> "CCITTFaxDecode";
            default -> last;
        };
    }

    /** The decode parameters of the image codec (the last filter), or null. */
    public static PdfDict imageParams(PdfDict dict) {
        PdfObject p = dict.get("DecodeParms", "DP");
        if (p instanceof PdfDict d) {
            return d;
        }
        if (p instanceof PdfArray a && a.size() > 0) {
            return a.getDict(a.size() - 1);
        }
        return null;
    }

    /** Undoes the filters in order, stopping before an image codec. */
    public static byte[] decode(byte[] data, PdfDict dict, boolean unused) {
        PdfObject f = dict.get("Filter", "F");
        PdfObject p = dict.get("DecodeParms", "DP");
        if (f instanceof PdfName n) {
            return isImageCodec(n.name) ? data : apply(n.name, data, p instanceof PdfDict d ? d : null);
        }
        if (f instanceof PdfArray a) {
            for (int i = 0; i < a.size(); i++) {
                String name = a.getName(i);
                if (name == null) {
                    continue;
                }
                if (isImageCodec(name)) {
                    break;
                }
                PdfDict parms = null;
                if (p instanceof PdfArray pa) {
                    parms = pa.getDict(i);
                } else if (p instanceof PdfDict d && a.size() == 1) {
                    parms = d;
                }
                data = apply(name, data, parms);
            }
        }
        return data;
    }

    public static byte[] apply(String name, byte[] data, PdfDict parms) {
        try {
            switch (name) {
                case "FlateDecode":
                case "Fl":
                    return predict(inflate(data), parms);
                case "LZWDecode":
                case "LZW":
                    return predict(lzw(data, parms == null ? 1 : parms.getInt("EarlyChange", 1)), parms);
                case "ASCIIHexDecode":
                case "AHx":
                    return asciiHex(data);
                case "ASCII85Decode":
                case "A85":
                    return ascii85(data);
                case "RunLengthDecode":
                case "RL":
                    return runLength(data);
                default:
                    return data;
            }
        } catch (RuntimeException e) {
            return data;
        }
    }

    public static byte[] inflate(byte[] data) {
        byte[] out = inflate(data, false);
        if (out == null || out.length == 0) {
            // no zlib header, or a broken one: try the raw deflate stream
            byte[] raw = inflate(data, true);
            if (raw != null && raw.length > 0) {
                return raw;
            }
            if (data.length > 2) {
                byte[] skipped = new byte[data.length - 2];
                System.arraycopy(data, 2, skipped, 0, skipped.length);
                raw = inflate(skipped, true);
                if (raw != null && raw.length > 0) {
                    return raw;
                }
            }
        }
        return out == null ? new byte[0] : out;
    }

    private static byte[] inflate(byte[] data, boolean nowrap) {
        Inflater inf = new Inflater(nowrap);
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, data.length * 3));
        byte[] buf = new byte[65536];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) {
                        break;
                    }
                }
                out.write(buf, 0, n);
            }
        } catch (DataFormatException e) {
            // keep what came out before the damage
        } finally {
            inf.end();
        }
        return out.toByteArray();
    }

    /** TIFF predictor 2 and the PNG predictors 10-15. */
    static byte[] predict(byte[] data, PdfDict parms) {
        if (parms == null) {
            return data;
        }
        int predictor = parms.getInt("Predictor", 1);
        if (predictor <= 1) {
            return data;
        }
        int colors = Math.max(1, parms.getInt("Colors", 1));
        int bpc = Math.max(1, parms.getInt("BitsPerComponent", 8));
        int columns = Math.max(1, parms.getInt("Columns", 1));
        int bpp = Math.max(1, (colors * bpc + 7) / 8);
        int rowLen = (colors * bpc * columns + 7) / 8;
        if (predictor == 2) {
            byte[] out = data.clone();
            if (bpc == 8) {
                for (int row = 0; row + rowLen <= out.length; row += rowLen) {
                    for (int i = bpp; i < rowLen; i++) {
                        out[row + i] = (byte) (out[row + i] + out[row + i - bpp]);
                    }
                }
            } else if (bpc == 16) {
                for (int row = 0; row + rowLen <= out.length; row += rowLen) {
                    for (int i = bpp; i + 1 < rowLen; i += 2) {
                        int prev = ((out[row + i - bpp] & 0xff) << 8) | (out[row + i - bpp + 1] & 0xff);
                        int cur = ((out[row + i] & 0xff) << 8) | (out[row + i + 1] & 0xff);
                        int v = (prev + cur) & 0xffff;
                        out[row + i] = (byte) (v >> 8);
                        out[row + i + 1] = (byte) v;
                    }
                }
            }
            return out;
        }
        int rows = data.length / (rowLen + 1);
        byte[] out = new byte[rows * rowLen];
        byte[] prev = new byte[rowLen];
        int in = 0;
        for (int r = 0; r < rows; r++) {
            int type = data[in++] & 0xff;
            int o = r * rowLen;
            for (int i = 0; i < rowLen; i++) {
                int raw = data[in++] & 0xff;
                int left = i >= bpp ? out[o + i - bpp] & 0xff : 0;
                int up = prev[i] & 0xff;
                int upLeft = i >= bpp ? prev[i - bpp] & 0xff : 0;
                int v;
                switch (type) {
                    case 1: v = raw + left; break;
                    case 2: v = raw + up; break;
                    case 3: v = raw + ((left + up) >> 1); break;
                    case 4: {
                        int pa = Math.abs(up - upLeft);
                        int pb = Math.abs(left - upLeft);
                        int pc = Math.abs(left + up - 2 * upLeft);
                        int pred = (pa <= pb && pa <= pc) ? left : (pb <= pc ? up : upLeft);
                        v = raw + pred;
                        break;
                    }
                    default: v = raw; break;
                }
                out[o + i] = (byte) v;
            }
            System.arraycopy(out, o, prev, 0, rowLen);
        }
        return out;
    }

    static byte[] lzw(byte[] data, int earlyChange) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 3);
        byte[][] table = new byte[4096][];
        for (int i = 0; i < 256; i++) {
            table[i] = new byte[] { (byte) i };
        }
        int next = 258;
        int codeLen = 9;
        byte[] prev = null;
        long bits = 0;
        int nbits = 0;
        int pos = 0;
        while (true) {
            while (nbits < codeLen && pos < data.length) {
                bits = (bits << 8) | (data[pos++] & 0xff);
                nbits += 8;
            }
            if (nbits < codeLen) {
                break;
            }
            int code = (int) ((bits >> (nbits - codeLen)) & ((1 << codeLen) - 1));
            nbits -= codeLen;
            if (code == 256) {
                next = 258;
                codeLen = 9;
                prev = null;
                continue;
            }
            if (code == 257) {
                break;
            }
            byte[] entry;
            if (code < next && table[code] != null) {
                entry = table[code];
            } else if (prev != null) {
                entry = new byte[prev.length + 1];
                System.arraycopy(prev, 0, entry, 0, prev.length);
                entry[prev.length] = prev[0];
            } else {
                break;
            }
            out.write(entry, 0, entry.length);
            if (prev != null && next < 4096) {
                byte[] add = new byte[prev.length + 1];
                System.arraycopy(prev, 0, add, 0, prev.length);
                add[prev.length] = entry[0];
                table[next++] = add;
            }
            prev = entry;
            if (next + earlyChange >= (1 << codeLen) && codeLen < 12) {
                codeLen++;
            }
        }
        return out.toByteArray();
    }

    static byte[] asciiHex(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 2 + 1);
        int hi = -1;
        for (byte b : data) {
            int c = b & 0xff;
            if (c == '>') {
                break;
            }
            int v = PdfLexer.hexValue(c);
            if (v < 0) {
                continue;
            }
            if (hi < 0) {
                hi = v;
            } else {
                out.write((hi << 4) | v);
                hi = -1;
            }
        }
        if (hi >= 0) {
            out.write(hi << 4);
        }
        return out.toByteArray();
    }

    static byte[] ascii85(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        long tuple = 0;
        int count = 0;
        int i = 0;
        // a leading "<~" is not part of the data
        if (data.length >= 2 && data[0] == '<' && data[1] == '~') {
            i = 2;
        }
        for (; i < data.length; i++) {
            int c = data[i] & 0xff;
            if (c == '~') {
                break;
            }
            if (PdfLexer.isWhite(c)) {
                continue;
            }
            if (c == 'z' && count == 0) {
                out.write(0);
                out.write(0);
                out.write(0);
                out.write(0);
                continue;
            }
            if (c < '!' || c > 'u') {
                continue;
            }
            tuple = tuple * 85 + (c - '!');
            if (++count == 5) {
                out.write((int) (tuple >> 24));
                out.write((int) (tuple >> 16));
                out.write((int) (tuple >> 8));
                out.write((int) tuple);
                tuple = 0;
                count = 0;
            }
        }
        if (count > 1) {
            for (int k = count; k < 5; k++) {
                tuple = tuple * 85 + 84;
            }
            for (int k = 0; k < count - 1; k++) {
                out.write((int) (tuple >> (24 - 8 * k)));
            }
        }
        return out.toByteArray();
    }

    static byte[] runLength(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 2);
        int i = 0;
        while (i < data.length) {
            int len = data[i++] & 0xff;
            if (len == 128) {
                break;
            }
            if (len < 128) {
                int n = Math.min(len + 1, data.length - i);
                out.write(data, i, n);
                i += n;
            } else if (i < data.length) {
                int b = data[i++] & 0xff;
                for (int k = 0; k < 257 - len; k++) {
                    out.write(b);
                }
            }
        }
        return out.toByteArray();
    }
}

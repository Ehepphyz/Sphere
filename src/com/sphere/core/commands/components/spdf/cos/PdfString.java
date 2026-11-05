package com.sphere.components.spdf.cos;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A string: raw bytes, read as text through PDFDocEncoding, UTF-16BE or UTF-8 when it is a text string. */
public final class PdfString implements PdfObject {

    private byte[] bytes;
    /** Written as <hex> rather than (literal), as it was read. */
    public boolean hex;

    private static final char[] DOC_ENCODING = new char[256];

    static {
        for (int i = 0; i < 256; i++) {
            DOC_ENCODING[i] = (char) i;
        }
        final String low = "˘ˇˆ˙˝˛˚˜";
        for (int i = 0; i < low.length(); i++) {
            DOC_ENCODING[0x18 + i] = low.charAt(i);
        }
        final String high = "•†‡…—–ƒ⁄‹›−‰„“"
            + "”‘’‚™ﬁﬂŁŒŠŸŽıłœš"
            + "ž�€";
        for (int i = 0; i < high.length(); i++) {
            DOC_ENCODING[0x80 + i] = high.charAt(i);
        }
    }

    public PdfString(byte[] bytes) {
        this.bytes = bytes;
    }

    public PdfString(byte[] bytes, boolean hex) {
        this.bytes = bytes;
        this.hex = hex;
    }

    /** A text string: PDFDocEncoding when every character has a byte there, UTF-16BE otherwise. */
    public static PdfString text(String s) {
        boolean doc = true;
        for (int i = 0; i < s.length() && doc; i++) {
            char c = s.charAt(i);
            doc = (c >= 0x20 && c < 0x7F) || c == '\n' || c == '\r' || c == '\t' || (c >= 0xA1 && c <= 0xFF && c != 0xAD);
        }
        if (doc) {
            return new PdfString(s.getBytes(StandardCharsets.ISO_8859_1));
        }
        byte[] u = s.getBytes(StandardCharsets.UTF_16BE);
        byte[] out = new byte[u.length + 2];
        out[0] = (byte) 0xFE;
        out[1] = (byte) 0xFF;
        System.arraycopy(u, 0, out, 2, u.length);
        return new PdfString(out);
    }

    public byte[] getBytes() {
        return bytes;
    }

    public void setBytes(byte[] b) {
        bytes = b;
    }

    /** The bytes read as a text string (7.9.2.2): UTF-16BE or UTF-8 with their mark, PDFDocEncoding otherwise. */
    public String text() {
        return decodeText(bytes);
    }

    public static String decodeText(byte[] b) {
        if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        if (b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        char[] c = new char[b.length];
        for (int i = 0; i < b.length; i++) {
            c[i] = DOC_ENCODING[b[i] & 0xff];
        }
        return new String(c);
    }

    /** The bytes as Latin-1, one char per byte: for strings that are byte sequences (codes, IDs). */
    public String latin1() {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    @Override
    public PdfObject copy() {
        return new PdfString(bytes.clone(), hex);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PdfString s && Arrays.equals(s.bytes, bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "(" + text() + ")";
    }
}

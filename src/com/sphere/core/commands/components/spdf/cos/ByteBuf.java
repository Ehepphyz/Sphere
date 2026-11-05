package com.sphere.components.spdf.cos;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A growable byte buffer, unsynchronized, for writing files and content streams. */
public final class ByteBuf {

    private byte[] b;
    private int n;

    public ByteBuf() {
        this(1024);
    }

    public ByteBuf(int capacity) {
        b = new byte[Math.max(16, capacity)];
    }

    private void ensure(int more) {
        if (n + more > b.length) {
            b = Arrays.copyOf(b, Math.max(b.length * 2, n + more));
        }
    }

    public ByteBuf put(int c) {
        ensure(1);
        b[n++] = (byte) c;
        return this;
    }

    public ByteBuf put(byte[] data) {
        return put(data, 0, data.length);
    }

    public ByteBuf put(byte[] data, int off, int len) {
        ensure(len);
        System.arraycopy(data, off, b, n, len);
        n += len;
        return this;
    }

    /** ASCII/Latin-1 text, one byte per char. */
    public ByteBuf put(String s) {
        ensure(s.length());
        for (int i = 0; i < s.length(); i++) {
            b[n++] = (byte) s.charAt(i);
        }
        return this;
    }

    public ByteBuf num(double v) {
        return put(PdfNumber.format(v));
    }

    public int size() {
        return n;
    }

    public void clear() {
        n = 0;
    }

    public byte last() {
        return n == 0 ? 0 : b[n - 1];
    }

    public byte[] toBytes() {
        return Arrays.copyOf(b, n);
    }

    @Override
    public String toString() {
        return new String(b, 0, n, StandardCharsets.ISO_8859_1);
    }
}

package com.sphere.components.spdf.cos;

import java.io.ByteArrayOutputStream;
import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.zip.Deflater;

/**
 * A stream: its dictionary and its data as stored in the file (filtered,
 * already decrypted). The decoded data is computed on demand and kept while
 * memory allows. The byte arrays are never changed in place, so copies share them.
 */
public final class PdfStream extends PdfDict {

    private byte[] raw;
    private SoftReference<byte[]> decoded;

    public PdfStream(PdfDict dict, byte[] raw) {
        if (dict != null) {
            dict.copyInto(this);
        }
        this.raw = raw == null ? new byte[0] : raw;
    }

    /** A new stream holding data, Flate-compressed when asked and worth it. */
    public static PdfStream of(byte[] data, boolean compress) {
        PdfStream s = new PdfStream(null, null);
        s.setData(data, compress);
        return s;
    }

    public byte[] getRaw() {
        return raw;
    }

    /** Replaces the stored bytes, the filters staying as they are. */
    public void setRaw(byte[] bytes) {
        raw = bytes;
        decoded = null;
        put(PdfName.LENGTH, new PdfNumber(bytes.length));
    }

    /** The data with every filter undone, image filters (DCT, JPX...) excepted. */
    public byte[] getDecoded() {
        byte[] d = decoded == null ? null : decoded.get();
        if (d == null) {
            d = Filters.decode(raw, this, false);
            decoded = new SoftReference<>(d);
        }
        return d;
    }

    /** True when the last filter is an image codec the caller has to decode itself. */
    public String imageFilter() {
        return Filters.imageFilter(this);
    }

    /** Replaces the data with new decoded bytes, Flate-compressed when asked. */
    public void setData(byte[] data, boolean compress) {
        remove("DecodeParms");
        remove("DP");
        remove("F");
        remove("Filter");
        if (compress && data.length > 32) {
            raw = deflate(data);
            put("Filter", "FlateDecode");
        } else {
            raw = data;
        }
        put(PdfName.LENGTH, new PdfNumber(raw.length));
        decoded = new SoftReference<>(data);
    }

    public static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
        byte[] buf = new byte[65536];
        while (!d.finished()) {
            int n = d.deflate(buf);
            out.write(buf, 0, n);
        }
        d.end();
        return out.toByteArray();
    }

    @Override
    public PdfStream copy() {
        PdfStream s = new PdfStream(null, raw);
        for (Map.Entry<PdfName, PdfObject> e : entries()) {
            s.map.put(e.getKey(), e.getValue().copy());
        }
        s.decoded = decoded;
        return s;
    }

    @Override
    public String toString() {
        return super.toString() + " stream[" + raw.length + "]";
    }
}

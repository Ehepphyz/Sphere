package com.sphere.components.spdf.cos;

import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes a document as a new file: only the objects still reachable from the
 * catalog and the information dictionary, renumbered from 1, without
 * encryption. Unchanged streams keep their bytes and filters; streams stored
 * without a filter are compressed. In compact mode the small objects go into
 * object streams and the cross-reference becomes a stream (PDF 1.5), which
 * often makes the file a good deal smaller.
 */
public final class PdfWriter {

    private PdfWriter() {
    }

    public static byte[] write(PdfDocument doc, boolean compact) {
        PdfDict trailer = doc.trailer();
        // reachable objects, breadth first from the roots
        Map<Integer, Integer> renum = new HashMap<>();
        List<Integer> order = new ArrayList<>();
        ArrayDeque<PdfObject> queue = new ArrayDeque<>();
        queue.add(trailer.getRaw("Root"));
        if (trailer.getRaw("Info") != PdfNull.NULL) {
            queue.add(trailer.getRaw("Info"));
        }
        while (!queue.isEmpty()) {
            PdfObject o = queue.poll();
            if (o instanceof PdfRef r) {
                if (renum.containsKey(r.num)) {
                    continue;
                }
                PdfObject target = doc.getObject(r.num);
                if (target == PdfNull.NULL) {
                    continue;
                }
                renum.put(r.num, order.size() + 1);
                order.add(r.num);
                queue.add(target);
            } else if (o instanceof PdfArray a) {
                queue.addAll(a.rawList());
            } else if (o instanceof PdfDict d) {
                for (var e : d.entries()) {
                    PdfObject v = e.getValue();
                    if (v instanceof PdfRef || v instanceof PdfArray || v instanceof PdfDict) {
                        queue.add(v);
                    }
                }
            }
        }
        java.util.function.IntUnaryOperator map = n -> {
            Integer x = renum.get(n);
            return x == null ? -1 : x;
        };

        ByteBuf out = new ByteBuf(Math.max(4096, doc.originalBytes().length + 4096));
        out.put(compact ? "%PDF-1.5\n" : "%PDF-1.7\n");
        out.put(new byte[] { '%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n' });
        int n = order.size();
        long[] offsets = new long[n + 1];
        int[] stmOf = new int[n + 1];
        int[] stmIndex = new int[n + 1];

        List<Integer> packable = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int oldNum = order.get(i);
            int newNum = i + 1;
            PdfObject o = doc.getObject(oldNum);
            if (compact && !(o instanceof PdfStream)) {
                packable.add(newNum);
                continue;
            }
            offsets[newNum] = out.size();
            out.put(Integer.toString(newNum)).put(" 0 obj\n");
            writeObject(o, out, map);
            out.put("\nendobj\n");
        }

        int size = n + 1;
        if (compact && !packable.isEmpty()) {
            // object streams of up to 200 objects each
            int nextNum = n + 1;
            for (int start = 0; start < packable.size(); start += 200) {
                int end = Math.min(packable.size(), start + 200);
                ByteBuf header = new ByteBuf();
                ByteBuf body = new ByteBuf();
                for (int k = start; k < end; k++) {
                    int newNum = packable.get(k);
                    header.put(Integer.toString(newNum)).put(' ').put(Integer.toString(body.size())).put(' ');
                    writeObjectBody(doc.getObject(order.get(newNum - 1)), body, map);
                    body.put('\n');
                    stmOf[newNum] = nextNum;
                    stmIndex[newNum] = k - start;
                }
                byte[] hb = header.toBytes();
                ByteBuf all = new ByteBuf(hb.length + body.size());
                all.put(hb).put(body.toBytes());
                PdfStream os = PdfStream.of(all.toBytes(), true);
                os.put("Type", "ObjStm");
                os.put("N", end - start);
                os.put("First", hb.length);
                int stmNum = nextNum++;
                offsets = growTo(offsets, stmNum + 1);
                stmOf = growTo(stmOf, stmNum + 1);
                stmIndex = growTo(stmIndex, stmNum + 1);
                offsets[stmNum] = out.size();
                out.put(Integer.toString(stmNum)).put(" 0 obj\n");
                writeObject(os, out, map);
                out.put("\nendobj\n");
            }
            size = nextNum + 1;
            int xrefNum = nextNum;
            offsets = growTo(offsets, size);
            stmOf = growTo(stmOf, size);
            stmIndex = growTo(stmIndex, size);
            long xrefOffset = out.size();
            offsets[xrefNum] = xrefOffset;
            int w2 = xrefOffset > 0xFFFFFFL ? 5 : 4;
            ByteBuf rows = new ByteBuf(size * (3 + w2));
            for (int num = 0; num < size; num++) {
                if (num == 0) {
                    rows.put(0);
                    putBE(rows, 0, w2);
                    putBE(rows, 0xFFFF, 2);
                } else if (stmOf[num] != 0) {
                    rows.put(2);
                    putBE(rows, stmOf[num], w2);
                    putBE(rows, stmIndex[num], 2);
                } else {
                    rows.put(1);
                    putBE(rows, offsets[num], w2);
                    putBE(rows, 0, 2);
                }
            }
            PdfStream xs = PdfStream.of(rows.toBytes(), true);
            xs.put("Type", "XRef");
            xs.put("Size", size);
            xs.put("W", PdfArray.ofNumbers(1, w2, 2));
            fillTrailer(xs, doc, trailer, map, size);
            out.put(Integer.toString(xrefNum)).put(" 0 obj\n");
            writeObject(xs, out, map);
            out.put("\nendobj\nstartxref\n").put(Long.toString(xrefOffset)).put("\n%%EOF\n");
            return out.toBytes();
        }

        long xrefOffset = out.size();
        out.put("xref\n0 ").put(Integer.toString(size)).put("\n0000000000 65535 f \n");
        for (int num = 1; num < size; num++) {
            String off = Long.toString(offsets[num]);
            out.put("0000000000".substring(off.length())).put(off).put(" 00000 n \n");
        }
        PdfDict t = new PdfDict();
        fillTrailer(t, doc, trailer, map, size);
        out.put("trailer\n");
        PdfSerializer.write(t, out, map);
        out.put("\nstartxref\n").put(Long.toString(xrefOffset)).put("\n%%EOF\n");
        return out.toBytes();
    }

    private static void fillTrailer(PdfDict t, PdfDocument doc, PdfDict trailer, java.util.function.IntUnaryOperator map,
                                    int size) {
        t.put("Size", size);
        t.put("Root", trailer.getRaw("Root"));
        if (trailer.getRaw("Info") instanceof PdfRef) {
            t.put("Info", trailer.getRaw("Info"));
        }
        byte[] id0 = null;
        PdfArray ids = trailer.getArray("ID");
        if (ids != null && ids.get(0) instanceof PdfString s && s.getBytes().length > 0) {
            id0 = s.getBytes();
        }
        byte[] id1 = newId(doc);
        if (id0 == null) {
            id0 = id1;
        }
        t.put("ID", PdfArray.of(new PdfString(id0, true), new PdfString(id1, true)));
    }

    private static byte[] newId(PdfDocument doc) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(Long.toString(System.nanoTime()).getBytes());
            md5.update(Integer.toString(doc.modCount()).getBytes());
            md5.update(Integer.toString(doc.objectNumbers().size()).getBytes());
            return md5.digest();
        } catch (Exception e) {
            return new byte[16];
        }
    }

    private static void putBE(ByteBuf b, long v, int len) {
        for (int i = len - 1; i >= 0; i--) {
            b.put((int) (v >> (8 * i)) & 0xff);
        }
    }

    private static long[] growTo(long[] a, int n) {
        return a.length >= n ? a : java.util.Arrays.copyOf(a, n);
    }

    private static int[] growTo(int[] a, int n) {
        return a.length >= n ? a : java.util.Arrays.copyOf(a, n);
    }

    /** An indirect object's body: a stream with its data, any other object as is. */
    private static void writeObject(PdfObject o, ByteBuf out, java.util.function.IntUnaryOperator map) {
        if (o instanceof PdfStream s) {
            byte[] raw = s.getRaw();
            PdfDict dict = new PdfDict();
            s.copyInto(dict);
            if (!s.has("Filter") && raw.length > 256 && !s.isType("XRef") && !s.isType("Metadata")) {
                // stored plain: compress it on the way out
                byte[] z = PdfStream.deflate(raw);
                if (z.length < raw.length * 0.9) {
                    raw = z;
                    dict.put("Filter", "FlateDecode");
                    dict.remove("DecodeParms");
                }
            }
            dict.put("Length", raw.length);
            PdfSerializer.write(dict, out, map);
            out.put("\nstream\n").put(raw).put("\nendstream");
        } else {
            PdfSerializer.write(o, out, map);
        }
    }

    private static void writeObjectBody(PdfObject o, ByteBuf out, java.util.function.IntUnaryOperator map) {
        PdfSerializer.write(o, out, map);
    }
}

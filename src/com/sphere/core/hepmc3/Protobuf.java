package com.sphere.core.hepmc3;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The protocol buffer messages of HepMC3.proto, encoded and decoded by hand:
 * proto2, fields written in number order, repeated fields not packed (as
 * the C++ protobuf writes them), unknown fields skipped on reading. No
 * protobuf library is needed for HepMC3's binary format.
 */
public final class Protobuf {

    private Protobuf() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** MessageDigest.MessageType. */
    public static final int UNKNOWN = 0;
    public static final int HEADER = 1;
    public static final int RUN_INFO = 2;
    public static final int EVENT = 3;
    public static final int FOOTER = 4;

    /* ---- encoding ------------------------------------------------------------- */

    /** A message being written. */
    public static final class Out {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream(256);

        public byte[] bytes() {
            return b.toByteArray();
        }

        public int size() {
            return b.size();
        }

        void varint(long v) {
            while ((v & ~0x7FL) != 0) {
                b.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            b.write((int) v);
        }

        void tag(int field, int wire) {
            varint(((long) field << 3) | wire);
        }

        /** int32: a negative value is sign-extended to ten bytes, as protobuf does. */
        public Out int32(int field, int v) {
            tag(field, 0);
            varint(v);
            return this;
        }

        public Out uint32(int field, long v) {
            tag(field, 0);
            varint(v & 0xFFFFFFFFL);
            return this;
        }

        public Out uint64(int field, long v) {
            tag(field, 0);
            varint(v);
            return this;
        }

        public Out bool(int field, boolean v) {
            tag(field, 0);
            varint(v ? 1 : 0);
            return this;
        }

        public Out fixed32(int field, int v) {
            tag(field, 5);
            for (int k = 0; k < 4; k++) b.write((v >>> (8 * k)) & 0xFF);
            return this;
        }

        public Out double64(int field, double d) {
            tag(field, 1);
            final long v = Double.doubleToRawLongBits(d);
            for (int k = 0; k < 8; k++) b.write((int) ((v >>> (8 * k)) & 0xFF));
            return this;
        }

        public Out string(int field, String s) {
            return bytes(field, s.getBytes(StandardCharsets.ISO_8859_1));
        }

        public Out bytes(int field, byte[] data) {
            tag(field, 2);
            varint(data.length);
            b.write(data, 0, data.length);
            return this;
        }

        public Out message(int field, Out m) {
            return bytes(field, m.bytes());
        }
    }

    private static Out fourVector(FourVector v) {
        return new Out().double64(1, v.x()).double64(2, v.y()).double64(3, v.z()).double64(4, v.t());
    }

    /** MessageDigest: always ten bytes (two fixed32). */
    public static byte[] digest(int bytes, int type) {
        return new Out().fixed32(1, bytes).fixed32(2, type).bytes();
    }

    /** Header: versions of HepMC3 and of the protobuf library (none here: 0.0.0). */
    public static byte[] header() {
        return new Out().string(1, HepMC3.version())
            .uint32(2, (HepMC3.VERSION_CODE / 1000000) % 1000)
            .uint32(3, (HepMC3.VERSION_CODE / 1000) % 1000)
            .uint32(4, HepMC3.VERSION_CODE % 1000)
            .uint32(5, 0).uint32(6, 0).uint32(7, 0).bytes();
    }

    public static byte[] footer(long nevents, long eventBytes) {
        return new Out().uint32(1, nevents).uint64(2, eventBytes).bytes();
    }

    public static byte[] runInfo(GenRunInfo run) {
        final GenRunInfoData d = new GenRunInfoData();
        run.writeData(d);
        final Out o = new Out();
        for (String s : d.weightNames) o.string(1, s);
        for (String s : d.toolName) o.string(2, s);
        for (String s : d.toolVersion) o.string(3, s);
        for (String s : d.toolDescription) o.string(4, s);
        for (String s : d.attributeName) o.string(5, s);
        for (String s : d.attributeString) o.string(6, s);
        return o.bytes();
    }

    public static byte[] event(GenEvent evt) {
        final GenEventData data = new GenEventData();
        evt.writeData(data);
        final Out o = new Out();
        o.int32(1, data.eventNumber);
        o.int32(2, data.momentumUnit == Units.MomentumUnit.MEV ? 1 : 0);
        o.int32(3, data.lengthUnit == Units.LengthUnit.CM ? 1 : 0);
        for (GenParticleData p : data.particles) {
            o.message(4, new Out().int32(1, p.pid).int32(2, p.status).bool(3, p.isMassSet).double64(4, p.mass)
                .message(5, fourVector(p.momentum)));
        }
        for (GenVertexData v : data.vertices) {
            o.message(5, new Out().int32(1, v.status).message(2, fourVector(v.position)));
        }
        for (double w : data.weights) o.double64(6, w);
        o.message(7, fourVector(data.eventPos));
        for (int i = 0; i < data.links1.size(); i++) o.int32(8, data.links1.get(i));
        for (int i = 0; i < data.links2.size(); i++) o.int32(9, data.links2.get(i));
        for (int i = 0; i < data.attributeId.size(); i++) o.int32(10, data.attributeId.get(i));
        for (String s : data.attributeName) o.string(11, s);
        for (String s : data.attributeString) o.string(12, s);
        return o.bytes();
    }

    /* ---- decoding --------------------------------------------------------------- */

    /** A message being read. */
    public static final class In {
        private final byte[] b;
        private int pos;
        private final int end;
        public int field;
        public int wire;

        public In(byte[] b, int from, int to) {
            this.b = b;
            this.pos = from;
            this.end = to;
        }

        public In(byte[] b) {
            this(b, 0, b.length);
        }

        /** Reads the next tag; false at the end. */
        public boolean next() {
            if (pos >= end) return false;
            final long t = varint();
            field = (int) (t >>> 3);
            wire = (int) (t & 7);
            return true;
        }

        public long varint() {
            long v = 0;
            int shift = 0;
            while (true) {
                if (pos >= end) throw new IllegalStateException("truncated varint");
                final int c = b[pos++] & 0xFF;
                v |= (long) (c & 0x7F) << shift;
                if ((c & 0x80) == 0) return v;
                shift += 7;
                if (shift > 63) throw new IllegalStateException("varint too long");
            }
        }

        public int fixed32() {
            int v = 0;
            for (int k = 0; k < 4; k++) v |= (b[pos++] & 0xFF) << (8 * k);
            return v;
        }

        public double double64() {
            long v = 0;
            for (int k = 0; k < 8; k++) v |= ((long) (b[pos++] & 0xFF)) << (8 * k);
            return Double.longBitsToDouble(v);
        }

        public In sub() {
            final int len = (int) varint();
            final In m = new In(b, pos, pos + len);
            pos += len;
            return m;
        }

        public String string() {
            final int len = (int) varint();
            final String s = new String(b, pos, len, StandardCharsets.ISO_8859_1);
            pos += len;
            return s;
        }

        /** Skips a field of an unknown number. */
        public void skip() {
            switch (wire) {
                case 0 -> varint();
                case 1 -> pos += 8;
                case 2 -> pos += (int) varint();
                case 5 -> pos += 4;
                default -> throw new IllegalStateException("unsupported wire type " + wire);
            }
        }

        /** A repeated scalar written packed (another writer may), or one element. */
        public void ints(List<Integer> into) {
            if (wire == 2) {
                final In p = sub();
                while (p.pos < p.end) into.add((int) p.varint());
            } else {
                into.add((int) varint());
            }
        }

        public void doubles(List<Double> into) {
            if (wire == 2) {
                final In p = sub();
                while (p.pos < p.end) into.add(p.double64());
            } else {
                into.add(double64());
            }
        }
    }

    /** The digest at a position: {bytes, type}. */
    public static int[] readDigest(byte[] ten) {
        final In in = new In(ten);
        int bytes = 0;
        int type = 0;
        while (in.next()) {
            if (in.field == 1 && in.wire == 5) bytes = in.fixed32();
            else if (in.field == 2 && in.wire == 5) type = in.fixed32();
            else in.skip();
        }
        return new int[]{bytes, type};
    }

    private static FourVector readFourVector(In m) {
        final double[] v = new double[4];
        while (m.next()) {
            if (m.field >= 1 && m.field <= 4 && m.wire == 1) v[m.field - 1] = m.double64();
            else m.skip();
        }
        return new FourVector(v[0], v[1], v[2], v[3]);
    }

    public static void readRunInfo(byte[] msg, GenRunInfo run) {
        final GenRunInfoData d = new GenRunInfoData();
        final In in = new In(msg);
        while (in.next()) {
            if (in.wire != 2) {
                in.skip();
                continue;
            }
            switch (in.field) {
                case 1 -> d.weightNames.add(in.string());
                case 2 -> d.toolName.add(in.string());
                case 3 -> d.toolVersion.add(in.string());
                case 4 -> d.toolDescription.add(in.string());
                case 5 -> d.attributeName.add(in.string());
                case 6 -> d.attributeString.add(in.string());
                default -> in.skip();
            }
        }
        run.readData(d);
    }

    /** Fills an event from its message, through GenEventData as GenEvent::read_data does. */
    public static void readEvent(byte[] msg, GenEvent evt) {
        final GenEventData data = new GenEventData();
        final In in = new In(msg);
        final List<Integer> links1 = new ArrayList<>();
        final List<Integer> links2 = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        while (in.next()) {
            switch (in.field) {
                case 1 -> data.eventNumber = (int) in.varint();
                case 2 -> data.momentumUnit = in.varint() == 1 ? Units.MomentumUnit.MEV : Units.MomentumUnit.GEV;
                case 3 -> data.lengthUnit = in.varint() == 1 ? Units.LengthUnit.CM : Units.LengthUnit.MM;
                case 4 -> {
                    final In m = in.sub();
                    final GenParticleData p = new GenParticleData();
                    while (m.next()) {
                        switch (m.field) {
                            case 1 -> p.pid = (int) m.varint();
                            case 2 -> p.status = (int) m.varint();
                            case 3 -> p.isMassSet = m.varint() != 0;
                            case 4 -> p.mass = m.double64();
                            case 5 -> p.momentum = readFourVector(m.sub());
                            default -> m.skip();
                        }
                    }
                    data.particles.add(p);
                }
                case 5 -> {
                    final In m = in.sub();
                    final GenVertexData v = new GenVertexData();
                    while (m.next()) {
                        switch (m.field) {
                            case 1 -> v.status = (int) m.varint();
                            case 2 -> v.position = readFourVector(m.sub());
                            default -> m.skip();
                        }
                    }
                    data.vertices.add(v);
                }
                case 6 -> in.doubles(data.weights);
                case 7 -> data.eventPos = readFourVector(in.sub());
                case 8 -> in.ints(links1);
                case 9 -> in.ints(links2);
                case 10 -> in.ints(ids);
                case 11 -> data.attributeName.add(in.string());
                case 12 -> data.attributeString.add(in.string());
                default -> in.skip();
            }
        }
        for (int v : links1) data.links1.add(v);
        for (int v : links2) data.links2.add(v);
        for (int v : ids) data.attributeId.add(v);
        evt.readData(data);
    }
}

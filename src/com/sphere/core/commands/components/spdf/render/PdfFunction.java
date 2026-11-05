package com.sphere.components.spdf.render;

import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Functions (7.10): sampled (0), exponential (2), stitching (3) and the
 * PostScript calculator (4), which color spaces and shadings call.
 */
public abstract class PdfFunction {

    protected double[] domain;
    protected double[] range;

    public abstract double[] eval(double[] in);

    public int outputs() {
        return range == null ? 1 : range.length / 2;
    }

    protected double[] clipOut(double[] out) {
        if (range != null) {
            for (int i = 0; i < out.length && 2 * i + 1 < range.length; i++) {
                out[i] = Math.max(range[2 * i], Math.min(range[2 * i + 1], out[i]));
            }
        }
        return out;
    }

    protected double clipIn(double v, int i) {
        if (domain != null && 2 * i + 1 < domain.length) {
            return Math.max(domain[2 * i], Math.min(domain[2 * i + 1], v));
        }
        return v;
    }

    public static PdfFunction parse(PdfObject o) {
        if (o instanceof PdfArray a) {
            // an array of 1-out functions, one per component
            List<PdfFunction> parts = new ArrayList<>();
            for (PdfObject x : a) {
                PdfFunction f = parse(x);
                if (f != null) {
                    parts.add(f);
                }
            }
            return parts.isEmpty() ? null : new Combined(parts);
        }
        if (!(o instanceof PdfDict d)) {
            return null;
        }
        PdfFunction f;
        try {
            f = switch (d.getInt("FunctionType", -1)) {
                case 0 -> new Sampled((PdfStream) d);
                case 2 -> new Exponential(d);
                case 3 -> new Stitching(d);
                case 4 -> new PostScript((PdfStream) d);
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
        if (f != null) {
            PdfArray dom = d.getArray("Domain");
            f.domain = dom == null ? new double[] { 0, 1 } : dom.toDoubles();
            PdfArray rng = d.getArray("Range");
            if (f.range == null) {
                f.range = rng == null ? null : rng.toDoubles();
            }
        }
        return f;
    }

    private static final class Combined extends PdfFunction {
        final List<PdfFunction> parts;

        Combined(List<PdfFunction> parts) {
            this.parts = parts;
        }

        @Override
        public double[] eval(double[] in) {
            double[] out = new double[parts.size()];
            for (int i = 0; i < out.length; i++) {
                double[] r = parts.get(i).eval(in);
                out[i] = r.length > 0 ? r[0] : 0;
            }
            return out;
        }

        @Override
        public int outputs() {
            return parts.size();
        }
    }

    private static final class Sampled extends PdfFunction {
        final int[] size;
        final int bps;
        final double[] encode;
        final double[] decode;
        final double[] samples;
        final int nOut;

        Sampled(PdfStream s) {
            size = toInts(s.getArray("Size"));
            bps = s.getInt("BitsPerSample", 8);
            PdfArray r = s.getArray("Range");
            range = r == null ? new double[] { 0, 1 } : r.toDoubles();
            nOut = range.length / 2;
            PdfArray e = s.getArray("Encode");
            encode = new double[size.length * 2];
            for (int i = 0; i < size.length; i++) {
                encode[2 * i] = e == null ? 0 : e.getDouble(2 * i, 0);
                encode[2 * i + 1] = e == null ? size[i] - 1 : e.getDouble(2 * i + 1, size[i] - 1);
            }
            PdfArray dc = s.getArray("Decode");
            decode = dc == null ? range.clone() : dc.toDoubles();
            byte[] b = s.getDecoded();
            int total = nOut;
            for (int v : size) {
                total *= Math.max(1, v);
            }
            samples = new double[total];
            double max = Math.pow(2, bps) - 1;
            long bit = 0;
            for (int i = 0; i < total; i++) {
                long v = 0;
                for (int k = 0; k < bps; k++) {
                    int byteIdx = (int) ((bit + k) >> 3);
                    int b1 = byteIdx < b.length ? b[byteIdx] & 0xff : 0;
                    v = (v << 1) | ((b1 >> (7 - ((bit + k) & 7))) & 1);
                }
                bit += bps;
                samples[i] = v / max;
            }
        }

        @Override
        public double[] eval(double[] in) {
            int m = size.length;
            double[] pos = new double[m];
            int[] i0 = new int[m];
            double[] frac = new double[m];
            for (int i = 0; i < m; i++) {
                double x = clipIn(i < in.length ? in[i] : 0, i);
                double d0 = domain[2 * i];
                double d1 = domain[2 * i + 1];
                double e = d1 == d0 ? encode[2 * i] : encode[2 * i] + (x - d0) * (encode[2 * i + 1] - encode[2 * i]) / (d1 - d0);
                e = Math.max(0, Math.min(size[i] - 1, e));
                pos[i] = e;
                i0[i] = (int) Math.floor(e);
                if (i0[i] >= size[i] - 1) {
                    i0[i] = Math.max(0, size[i] - 2);
                }
                frac[i] = size[i] == 1 ? 0 : e - i0[i];
            }
            double[] out = new double[nOut];
            // multilinear interpolation over the 2^m corners
            int corners = 1 << Math.min(m, 8);
            for (int c = 0; c < corners; c++) {
                double weight = 1;
                int index = 0;
                int stride = 1;
                for (int i = 0; i < m; i++) {
                    int bitSet = (c >> i) & 1;
                    int idx = Math.min(size[i] - 1, i0[i] + bitSet);
                    weight *= bitSet == 1 ? frac[i] : 1 - frac[i];
                    index += idx * stride;
                    stride *= size[i];
                }
                if (weight == 0) {
                    continue;
                }
                for (int j = 0; j < nOut; j++) {
                    int k = index * nOut + j;
                    if (k < samples.length) {
                        out[j] += weight * samples[k];
                    }
                }
            }
            for (int j = 0; j < nOut; j++) {
                out[j] = decode[2 * j] + out[j] * (decode[2 * j + 1] - decode[2 * j]);
            }
            return clipOut(out);
        }
    }

    private static int[] toInts(PdfArray a) {
        if (a == null) {
            return new int[] { 1 };
        }
        int[] r = new int[a.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = Math.max(1, a.getInt(i, 1));
        }
        return r;
    }

    private static final class Exponential extends PdfFunction {
        final double[] c0;
        final double[] c1;
        final double n;

        Exponential(PdfDict d) {
            PdfArray a0 = d.getArray("C0");
            PdfArray a1 = d.getArray("C1");
            c0 = a0 == null ? new double[] { 0 } : a0.toDoubles();
            c1 = a1 == null ? new double[] { 1 } : a1.toDoubles();
            n = d.getFloat("N", 1);
        }

        @Override
        public double[] eval(double[] in) {
            double x = clipIn(in.length > 0 ? in[0] : 0, 0);
            double xn = n == 1 ? x : Math.pow(x, n);
            double[] out = new double[Math.min(c0.length, c1.length)];
            for (int i = 0; i < out.length; i++) {
                out[i] = c0[i] + xn * (c1[i] - c0[i]);
            }
            return clipOut(out);
        }

        @Override
        public int outputs() {
            return Math.min(c0.length, c1.length);
        }
    }

    private static final class Stitching extends PdfFunction {
        final PdfFunction[] fns;
        final double[] bounds;
        final double[] encode;

        Stitching(PdfDict d) {
            PdfArray f = d.getArray("Functions");
            fns = new PdfFunction[f == null ? 0 : f.size()];
            for (int i = 0; i < fns.length; i++) {
                fns[i] = parse(f.get(i));
            }
            PdfArray b = d.getArray("Bounds");
            bounds = b == null ? new double[0] : b.toDoubles();
            PdfArray e = d.getArray("Encode");
            encode = e == null ? new double[0] : e.toDoubles();
        }

        @Override
        public double[] eval(double[] in) {
            double x = clipIn(in.length > 0 ? in[0] : 0, 0);
            int k = 0;
            while (k < bounds.length && x >= bounds[k]) {
                k++;
            }
            if (k >= fns.length) {
                k = fns.length - 1;
            }
            if (k < 0 || fns[k] == null) {
                return new double[] { 0 };
            }
            double lo = k == 0 ? domain[0] : bounds[k - 1];
            double hi = k == bounds.length ? domain[1] : bounds[k];
            double e0 = 2 * k < encode.length ? encode[2 * k] : 0;
            double e1 = 2 * k + 1 < encode.length ? encode[2 * k + 1] : 1;
            double t = hi == lo ? e0 : e0 + (x - lo) * (e1 - e0) / (hi - lo);
            return clipOut(fns[k].eval(new double[] { t }));
        }

        @Override
        public int outputs() {
            for (PdfFunction f : fns) {
                if (f != null) {
                    return f.outputs();
                }
            }
            return 1;
        }
    }

    /** Type 4: a small stack machine over the PostScript calculator operators. */
    private static final class PostScript extends PdfFunction {
        private final Object[] program;

        PostScript(PdfStream s) {
            String src = new String(s.getDecoded(), java.nio.charset.StandardCharsets.ISO_8859_1);
            int[] pos = { src.indexOf('{') + 1 };
            program = block(src, pos);
        }

        private static Object[] block(String s, int[] pos) {
            List<Object> out = new ArrayList<>();
            while (pos[0] < s.length()) {
                char c = s.charAt(pos[0]);
                if (Character.isWhitespace(c)) {
                    pos[0]++;
                } else if (c == '{') {
                    pos[0]++;
                    out.add(block(s, pos));
                } else if (c == '}') {
                    pos[0]++;
                    break;
                } else {
                    int st = pos[0];
                    while (pos[0] < s.length() && !Character.isWhitespace(s.charAt(pos[0])) && s.charAt(pos[0]) != '{'
                           && s.charAt(pos[0]) != '}') {
                        pos[0]++;
                    }
                    String tok = s.substring(st, pos[0]);
                    try {
                        out.add(Double.parseDouble(tok));
                    } catch (NumberFormatException e) {
                        out.add(tok);
                    }
                }
            }
            return out.toArray();
        }

        @Override
        public double[] eval(double[] in) {
            double[] st = new double[100];
            int[] sp = { 0 };
            for (int i = 0; i < in.length; i++) {
                st[sp[0]++] = clipIn(in[i], i);
            }
            try {
                run(program, st, sp);
            } catch (RuntimeException e) {
                // a program that underflows gives what its stack holds
            }
            int n = range == null ? sp[0] : range.length / 2;
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                int k = sp[0] - n + i;
                out[i] = k >= 0 ? st[k] : 0;
            }
            return clipOut(out);
        }

        private static void run(Object[] prog, double[] st, int[] sp) {
            for (int i = 0; i < prog.length; i++) {
                Object o = prog[i];
                if (o instanceof Double v) {
                    if (sp[0] < st.length) {
                        st[sp[0]++] = v;
                    }
                    continue;
                }
                if (o instanceof Object[]) {
                    // a procedure: consumed by the if/ifelse that follows
                    if (i + 1 < prog.length && "if".equals(prog[i + 1])) {
                        if (st[--sp[0]] != 0) {
                            run((Object[]) o, st, sp);
                        }
                        i++;
                    } else if (i + 2 < prog.length && prog[i + 1] instanceof Object[] other && "ifelse".equals(prog[i + 2])) {
                        boolean c = st[--sp[0]] != 0;
                        run(c ? (Object[]) o : other, st, sp);
                        i += 2;
                    }
                    continue;
                }
                op((String) o, st, sp);
            }
        }

        private static void op(String op, double[] st, int[] sp) {
            int n = sp[0];
            double a;
            double b;
            switch (op) {
                case "add": b = st[--n]; a = st[--n]; st[n++] = a + b; break;
                case "sub": b = st[--n]; a = st[--n]; st[n++] = a - b; break;
                case "mul": b = st[--n]; a = st[--n]; st[n++] = a * b; break;
                case "div": b = st[--n]; a = st[--n]; st[n++] = b == 0 ? 0 : a / b; break;
                case "idiv": b = st[--n]; a = st[--n]; st[n++] = b == 0 ? 0 : (long) a / (long) b; break;
                case "mod": b = st[--n]; a = st[--n]; st[n++] = b == 0 ? 0 : (long) a % (long) b; break;
                case "neg": st[n - 1] = -st[n - 1]; break;
                case "abs": st[n - 1] = Math.abs(st[n - 1]); break;
                case "ceiling": st[n - 1] = Math.ceil(st[n - 1]); break;
                case "floor": st[n - 1] = Math.floor(st[n - 1]); break;
                case "round": st[n - 1] = Math.floor(st[n - 1] + 0.5); break;
                case "truncate": case "cvi": st[n - 1] = (long) st[n - 1]; break;
                case "cvr": break;
                case "sqrt": st[n - 1] = Math.sqrt(Math.max(0, st[n - 1])); break;
                case "sin": st[n - 1] = Math.sin(Math.toRadians(st[n - 1])); break;
                case "cos": st[n - 1] = Math.cos(Math.toRadians(st[n - 1])); break;
                case "atan": b = st[--n]; a = st[--n]; {
                    double deg = Math.toDegrees(Math.atan2(a, b));
                    st[n++] = deg < 0 ? deg + 360 : deg;
                } break;
                case "exp": b = st[--n]; a = st[--n]; st[n++] = Math.pow(a, b); break;
                case "ln": st[n - 1] = Math.log(st[n - 1]); break;
                case "log": st[n - 1] = Math.log10(st[n - 1]); break;
                case "dup": st[n] = st[n - 1]; n++; break;
                case "pop": n--; break;
                case "exch": a = st[n - 1]; st[n - 1] = st[n - 2]; st[n - 2] = a; break;
                case "copy": {
                    int k = (int) st[--n];
                    for (int i = 0; i < k; i++) {
                        st[n + i] = st[n - k + i];
                    }
                    n += k;
                    break;
                }
                case "index": {
                    int k = (int) st[--n];
                    st[n] = st[n - 1 - k];
                    n++;
                    break;
                }
                case "roll": {
                    int j = (int) st[--n];
                    int k = (int) st[--n];
                    if (k > 0 && k <= n) {
                        double[] part = java.util.Arrays.copyOfRange(st, n - k, n);
                        for (int i = 0; i < k; i++) {
                            st[n - k + Math.floorMod(i + j, k)] = part[i];
                        }
                    }
                    break;
                }
                case "eq": b = st[--n]; a = st[--n]; st[n++] = a == b ? 1 : 0; break;
                case "ne": b = st[--n]; a = st[--n]; st[n++] = a != b ? 1 : 0; break;
                case "gt": b = st[--n]; a = st[--n]; st[n++] = a > b ? 1 : 0; break;
                case "ge": b = st[--n]; a = st[--n]; st[n++] = a >= b ? 1 : 0; break;
                case "lt": b = st[--n]; a = st[--n]; st[n++] = a < b ? 1 : 0; break;
                case "le": b = st[--n]; a = st[--n]; st[n++] = a <= b ? 1 : 0; break;
                case "and": b = st[--n]; a = st[--n]; st[n++] = (long) a & (long) b; break;
                case "or": b = st[--n]; a = st[--n]; st[n++] = (long) a | (long) b; break;
                case "xor": b = st[--n]; a = st[--n]; st[n++] = (long) a ^ (long) b; break;
                case "not": st[n - 1] = st[n - 1] == 0 ? 1 : (st[n - 1] == 1 ? 0 : ~(long) st[n - 1]); break;
                case "bitshift": b = st[--n]; a = st[--n]; st[n++] = b >= 0 ? (long) a << (long) b : (long) a >> (long) -b; break;
                case "true": st[n++] = 1; break;
                case "false": st[n++] = 0; break;
                default: break;
            }
            sp[0] = Math.max(0, n);
        }
    }
}

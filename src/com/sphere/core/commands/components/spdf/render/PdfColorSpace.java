package com.sphere.components.spdf.render;

import com.sphere.components.spdf.cos.PdfArray;
import com.sphere.components.spdf.cos.PdfDict;
import com.sphere.components.spdf.cos.PdfName;
import com.sphere.components.spdf.cos.PdfObject;
import com.sphere.components.spdf.cos.PdfStream;
import com.sphere.components.spdf.cos.PdfString;
import java.util.HashMap;
import java.util.Map;

/**
 * Color spaces (8.6): the device ones, CIE-based ones (CalGray, CalRGB, Lab,
 * ICCBased through their component count), and the special ones (Indexed,
 * Separation, DeviceN, Pattern). Every color ends as an sRGB int.
 */
public abstract class PdfColorSpace {

    public abstract int components();

    public abstract int rgb(float[] c);

    public float[] initial() {
        return new float[components()];
    }

    public String name() {
        return getClass().getSimpleName();
    }

    public boolean isPattern() {
        return false;
    }

    /** The range of each component (min, max pairs) for decoding image samples. */
    public float[] defaultDecode(int bpc) {
        float[] d = new float[2 * components()];
        for (int i = 0; i < components(); i++) {
            d[2 * i] = 0;
            d[2 * i + 1] = 1;
        }
        return d;
    }

    public static final PdfColorSpace GRAY = new PdfColorSpace() {
        @Override
        public int components() {
            return 1;
        }

        @Override
        public int rgb(float[] c) {
            int v = clamp255(c[0]);
            return (v << 16) | (v << 8) | v;
        }

        @Override
        public String name() {
            return "DeviceGray";
        }
    };

    public static final PdfColorSpace RGB = new PdfColorSpace() {
        @Override
        public int components() {
            return 3;
        }

        @Override
        public int rgb(float[] c) {
            return (clamp255(c[0]) << 16) | (clamp255(c[1]) << 8) | clamp255(c[2]);
        }

        @Override
        public String name() {
            return "DeviceRGB";
        }
    };

    public static final PdfColorSpace CMYK = new PdfColorSpace() {
        @Override
        public int components() {
            return 4;
        }

        @Override
        public float[] initial() {
            return new float[] { 0, 0, 0, 1 };
        }

        @Override
        public int rgb(float[] c) {
            return cmyk(c[0], c[1], c[2], c[3]);
        }

        @Override
        public String name() {
            return "DeviceCMYK";
        }
    };

    /** CMYK to sRGB: the plain formula, softened toward what press profiles give. */
    public static int cmyk(float c, float m, float y, float k) {
        c = clamp01(c);
        m = clamp01(m);
        y = clamp01(y);
        k = clamp01(k);
        float r = (1 - c) * (1 - k);
        float g = (1 - m) * (1 - k);
        float b = (1 - y) * (1 - k);
        // inks are not pure: cyan holds some green, magenta some blue
        g = g * (1 - 0.08f * c);
        b = b * (1 - 0.12f * m);
        r = r * (1 - 0.05f * m);
        return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    static int clamp255(float v) {
        int i = Math.round(v * 255f);
        return i < 0 ? 0 : i > 255 ? 255 : i;
    }

    /* ------------------------------------------------------------- resolution */

    /** The color space an operand or a resource names. */
    public static PdfColorSpace resolve(PdfObject o, PdfDict resources, Map<Object, PdfColorSpace> cache) {
        return resolve(o, resources, cache, 0);
    }

    private static PdfColorSpace resolve(PdfObject o, PdfDict resources, Map<Object, PdfColorSpace> cache, int depth) {
        if (depth > 8 || o == null) {
            return GRAY;
        }
        if (o instanceof PdfName n) {
            switch (n.name) {
                case "DeviceGray": case "G": case "CalGray":
                    return GRAY;
                case "DeviceRGB": case "RGB": case "CalRGB":
                    return RGB;
                case "DeviceCMYK": case "CMYK":
                    return CMYK;
                case "Pattern":
                    return new PatternSpace(null);
                default:
                    PdfDict csd = resources == null ? null : resources.getDict("ColorSpace");
                    PdfObject named = csd == null ? null : csd.get(n.name);
                    if (named != null && !(named instanceof com.sphere.components.spdf.cos.PdfNull)) {
                        return resolve(named, resources, cache, depth + 1);
                    }
                    return GRAY;
            }
        }
        if (o instanceof PdfArray a && a.size() > 0) {
            PdfColorSpace cached = cache == null ? null : cache.get(a);
            if (cached != null) {
                return cached;
            }
            PdfColorSpace cs = build(a, resources, cache, depth);
            if (cache != null) {
                cache.put(a, cs);
            }
            return cs;
        }
        return GRAY;
    }

    private static PdfColorSpace build(PdfArray a, PdfDict resources, Map<Object, PdfColorSpace> cache, int depth) {
        String kind = a.getName(0);
        if (kind == null) {
            return GRAY;
        }
        try {
            switch (kind) {
                case "DeviceGray": case "G": case "CalGray":
                    return GRAY;
                case "DeviceRGB": case "RGB": case "CalRGB":
                    return RGB;
                case "DeviceCMYK": case "CMYK":
                    return CMYK;
                case "ICCBased": {
                    PdfStream s = a.get(1) instanceof PdfStream st ? st : null;
                    int n = s == null ? 3 : s.getInt("N", 3);
                    if (s != null && s.has("Alternate")) {
                        PdfColorSpace alt = resolve(s.get("Alternate"), resources, cache, depth + 1);
                        if (alt.components() == n) {
                            return alt;
                        }
                    }
                    return n == 1 ? GRAY : n == 4 ? CMYK : RGB;
                }
                case "Lab":
                    return new Lab(a.getDict(1));
                case "Indexed": case "I":
                    return new Indexed(resolve(a.get(1), resources, cache, depth + 1), a.getInt(2, 255), a.get(3));
                case "Separation":
                    return new Separation(a.getName(1), resolve(a.get(2), resources, cache, depth + 1),
                                          PdfFunction.parse(a.get(3)), 1);
                case "DeviceN": {
                    PdfArray names = a.getArray(1);
                    int n = names == null ? 1 : names.size();
                    boolean none = names != null;
                    for (int i = 0; names != null && i < names.size(); i++) {
                        none &= "None".equals(names.getName(i));
                    }
                    return new Separation(none ? "None" : "DeviceN", resolve(a.get(2), resources, cache, depth + 1),
                                          PdfFunction.parse(a.get(3)), n);
                }
                case "Pattern":
                    return new PatternSpace(a.size() > 1 ? resolve(a.get(1), resources, cache, depth + 1) : null);
                default:
                    return GRAY;
            }
        } catch (RuntimeException e) {
            return GRAY;
        }
    }

    /** A color space by component count, for images that name none (JPEG, JPX). */
    public static PdfColorSpace forComponents(int n) {
        return n == 1 ? GRAY : n == 4 ? CMYK : RGB;
    }

    /* ------------------------------------------------------------- special spaces */

    public static final class Indexed extends PdfColorSpace {
        public final PdfColorSpace base;
        public final int hival;
        public final int[] palette;

        Indexed(PdfColorSpace base, int hival, PdfObject lookup) {
            this.base = base;
            this.hival = Math.max(0, Math.min(255, hival));
            byte[] b = lookup instanceof PdfString s ? s.getBytes()
                : lookup instanceof PdfStream st ? st.getDecoded() : new byte[0];
            int n = base.components();
            palette = new int[this.hival + 1];
            float[] c = new float[n];
            for (int i = 0; i <= this.hival; i++) {
                for (int k = 0; k < n; k++) {
                    int idx = i * n + k;
                    c[k] = idx < b.length ? (b[idx] & 0xff) / 255f : 0;
                }
                if (base instanceof Lab lab) {
                    c = lab.scaleFromBytes(c);
                }
                palette[i] = base.rgb(c);
            }
        }

        @Override
        public int components() {
            return 1;
        }

        @Override
        public int rgb(float[] c) {
            int i = Math.round(c[0]);
            return palette[Math.max(0, Math.min(hival, i))];
        }

        @Override
        public float[] defaultDecode(int bpc) {
            return new float[] { 0, (1 << bpc) - 1 };
        }

        @Override
        public String name() {
            return "Indexed";
        }
    }

    public static final class Separation extends PdfColorSpace {
        final String colorant;
        final PdfColorSpace alt;
        final PdfFunction fn;
        final int n;
        private final Map<Integer, Integer> lut = new HashMap<>();

        Separation(String colorant, PdfColorSpace alt, PdfFunction fn, int n) {
            this.colorant = colorant;
            this.alt = alt;
            this.fn = fn;
            this.n = n;
        }

        @Override
        public int components() {
            return n;
        }

        @Override
        public float[] initial() {
            float[] f = new float[n];
            java.util.Arrays.fill(f, 1f);
            return f;
        }

        public boolean isNone() {
            return "None".equals(colorant);
        }

        @Override
        public int rgb(float[] c) {
            if ("All".equals(colorant)) {
                int v = clamp255(1 - c[0]);
                return (v << 16) | (v << 8) | v;
            }
            int key = 0;
            if (n <= 3) {
                for (int i = 0; i < n; i++) {
                    key = (key << 8) | clamp255(c[i]);
                }
                Integer hit;
                synchronized (lut) {
                    hit = lut.get(key);
                }
                if (hit != null) {
                    return hit;
                }
            }
            int out;
            if (fn == null) {
                float v = 1 - c[0];
                out = (clamp255(v) << 16) | (clamp255(v) << 8) | clamp255(v);
            } else {
                double[] in = new double[n];
                for (int i = 0; i < n; i++) {
                    in[i] = c[i];
                }
                double[] r = fn.eval(in);
                float[] f = new float[alt.components()];
                for (int i = 0; i < f.length; i++) {
                    f[i] = i < r.length ? (float) r[i] : 0;
                }
                out = alt.rgb(f);
            }
            if (n <= 3) {
                synchronized (lut) {
                    if (lut.size() < 65536) {
                        lut.put(key, out);
                    }
                }
            }
            return out;
        }

        @Override
        public String name() {
            return n == 1 ? "Separation" : "DeviceN";
        }
    }

    public static final class Lab extends PdfColorSpace {
        final double xw;
        final double yw;
        final double zw;
        final float[] range;

        Lab(PdfDict d) {
            double[] wp = { 0.9505, 1.0, 1.089 };
            float[] r = { -100, 100, -100, 100 };
            if (d != null) {
                PdfArray w = d.getArray("WhitePoint");
                if (w != null && w.size() == 3) {
                    wp = w.toDoubles();
                }
                PdfArray rr = d.getArray("Range");
                if (rr != null && rr.size() == 4) {
                    r = rr.toFloats();
                }
            }
            xw = wp[0];
            yw = wp[1];
            zw = wp[2];
            range = r;
        }

        @Override
        public int components() {
            return 3;
        }

        @Override
        public float[] defaultDecode(int bpc) {
            return new float[] { 0, 100, range[0], range[1], range[2], range[3] };
        }

        float[] scaleFromBytes(float[] c) {
            return new float[] { c[0] * 100, range[0] + c[1] * (range[1] - range[0]),
                                 range[2] + c[2] * (range[3] - range[2]) };
        }

        private static double finv(double t) {
            return t > 6.0 / 29 ? t * t * t : 3 * (6.0 / 29) * (6.0 / 29) * (t - 4.0 / 29);
        }

        private static double gamma(double v) {
            v = Math.max(0, Math.min(1, v));
            return v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        }

        @Override
        public int rgb(float[] c) {
            double l = c[0];
            double a = Math.max(range[0], Math.min(range[1], c[1]));
            double b = Math.max(range[2], Math.min(range[3], c[2]));
            double fy = (l + 16) / 116;
            double fx = fy + a / 500;
            double fz = fy - b / 200;
            double x = xw * finv(fx);
            double y = yw * finv(fy);
            double z = zw * finv(fz);
            double r = 3.1338561 * x - 1.6168667 * y - 0.4906146 * z;
            double g = -0.9787684 * x + 1.9161415 * y + 0.0334540 * z;
            double bl = 0.0719453 * x - 0.2289914 * y + 1.4052427 * z;
            return (clamp255((float) gamma(r)) << 16) | (clamp255((float) gamma(g)) << 8) | clamp255((float) gamma(bl));
        }
    }

    public static final class PatternSpace extends PdfColorSpace {
        public final PdfColorSpace under;

        PatternSpace(PdfColorSpace under) {
            this.under = under;
        }

        @Override
        public int components() {
            return under == null ? 0 : under.components();
        }

        @Override
        public int rgb(float[] c) {
            return under == null ? 0 : under.rgb(c);
        }

        @Override
        public boolean isPattern() {
            return true;
        }
    }
}

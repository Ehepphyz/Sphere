package com.sphere.core.hepmc3.cxx;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * The Sphere x87 ABI from Java: the functions of bridge/lib/sphere_x87.S (long double arithmetic,
 * comparison, conversions, and MinGW's sinl, cosl, asinl, atan2l) run by the x87 unit of this
 * processor. Their machine code, assembled for the two x86-64 calling conventions
 * ({@link X87Code}), is copied into executable memory and called through the Foreign Function
 * API as a critical call (no thread transition, the Java arrays passed in place): nothing to
 * install, the same on Windows, Linux and macOS Intel.
 *
 * <p>Where there is no x87 unit (ARM, RISC-V...) {@link #available} is false and
 * {@link LongDouble}'s software computes the same bits; {@link #check} compares the two on
 * special and random values, which is what makes the software trustworthy on those processors.
 */
public final class X87Abi {

    private X87Abi() {
    }

    private static final boolean READY;
    private static final String DESCRIPTION;
    private static MethodHandle add, sub, mul, div, sqrt, cmp, fromF64, toF64, fromI64, toI64, sin, cos, asin, atan2;

    static {
        boolean ok = false;
        String d;
        try {
            d = setUp();
            ok = true;
        } catch (Throwable t) {
            d = "not available (" + t.getClass().getSimpleName() + ": " + t.getMessage() + "); the software long double serves";
        }
        READY = ok;
        DESCRIPTION = d;
    }

    /** Whether the x87 unit runs the ABI here. */
    public static boolean available() {
        return READY;
    }

    public static String describe() {
        return DESCRIPTION;
    }

    private static String setUp() throws Throwable {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) {
            throw new UnsupportedOperationException("no x87 unit on " + arch);
        }
        final boolean windows = os.contains("win");
        final byte[] code = HexFormat.of().parseHex(windows ? X87Code.WIN64 : X87Code.SYSV);
        final Map<String, Long> at = new HashMap<>();
        for (String s : windows ? X87Code.WIN64_SYMBOLS : X87Code.SYSV_SYMBOLS) {
            final int c = s.indexOf(':');
            at.put(s.substring(0, c), Long.parseLong(s.substring(c + 1)));
        }
        NativeMath.requireNativeAccess();
        final Linker linker = Linker.nativeLinker();
        final MemorySegment page = NativeMath.Executable.load(linker, windows, code);
        final Linker.Option critical = Linker.Option.critical(true);
        final FunctionDescriptor binary = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        final FunctionDescriptor unary = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        final FunctionDescriptor compare = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        final java.util.function.BiFunction<String, FunctionDescriptor, MethodHandle> h = (name, fd) -> {
            final Long off = at.get(name);
            if (off == null) throw new IllegalStateException("no " + name + " in the code");
            return linker.downcallHandle(page.asSlice(off), fd, critical);
        };
        add = h.apply("sx87_add", binary);
        sub = h.apply("sx87_sub", binary);
        mul = h.apply("sx87_mul", binary);
        div = h.apply("sx87_div", binary);
        sqrt = h.apply("sx87_sqrt", unary);
        cmp = h.apply("sx87_cmp", compare);
        fromF64 = h.apply("sx87_from_f64", unary);
        toF64 = h.apply("sx87_to_f64", unary);
        fromI64 = h.apply("sx87_from_i64", unary);
        toI64 = h.apply("sx87_to_i64", unary);
        sin = h.apply("sx87_sin", unary);
        cos = h.apply("sx87_cos", unary);
        asin = h.apply("sx87_asin", unary);
        atan2 = h.apply("sx87_atan2", binary);
        // one sum and one root, to know the code runs
        final byte[] three = binary(add, LongDouble.of(1.).toX87(), LongDouble.of(2.).toX87());
        if (LongDouble.fromX87(three).toDouble() != 3. || LongDouble.fromX87(unary(sqrt, LongDouble.of(4.).toX87())).toDouble() != 2.) {
            throw new IllegalStateException("the x87 code gives wrong values");
        }
        return "the x87 unit, " + (windows ? "Win64" : "System V") + " convention (sphere_x87.S " + X87Code.SOURCE_SHA256
            + ", " + code.length + " bytes)";
    }

    /* ---- raw: ten bytes in, ten bytes out ------------------------------------------------- */

    static byte[] binary(MethodHandle h, byte[] a, byte[] b) {
        final byte[] r = new byte[10];
        try {
            h.invokeExact(MemorySegment.ofArray(a), MemorySegment.ofArray(b), MemorySegment.ofArray(r));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return r;
    }

    static byte[] unary(MethodHandle h, byte[] a) {
        final byte[] r = new byte[10];
        try {
            h.invokeExact(MemorySegment.ofArray(a), MemorySegment.ofArray(r));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return r;
    }

    private static void need() {
        if (!READY) throw new UnsupportedOperationException("the x87 ABI is " + DESCRIPTION);
    }

    /* ---- long double ---------------------------------------------------------------------- */

    public static LongDouble add(LongDouble a, LongDouble b) {
        need();
        return LongDouble.fromX87(binary(add, a.toX87(), b.toX87()));
    }

    public static LongDouble subtract(LongDouble a, LongDouble b) {
        need();
        return LongDouble.fromX87(binary(sub, a.toX87(), b.toX87()));
    }

    public static LongDouble multiply(LongDouble a, LongDouble b) {
        need();
        return LongDouble.fromX87(binary(mul, a.toX87(), b.toX87()));
    }

    public static LongDouble divide(LongDouble a, LongDouble b) {
        need();
        return LongDouble.fromX87(binary(div, a.toX87(), b.toX87()));
    }

    public static LongDouble sqrt(LongDouble a) {
        need();
        return LongDouble.fromX87(unary(sqrt, a.toX87()));
    }

    /** -1, 0, 1, or 2 when unordered (a NaN), as fucomi compares. */
    public static int compare(LongDouble a, LongDouble b) {
        need();
        try {
            return (int) cmp.invokeExact(MemorySegment.ofArray(a.toX87()), MemorySegment.ofArray(b.toX87()));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static LongDouble fromDouble(double x) {
        need();
        final byte[] r = new byte[10];
        try {
            fromF64.invokeExact(MemorySegment.ofArray(new double[] {x}), MemorySegment.ofArray(r));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return LongDouble.fromX87(r);
    }

    public static double toDouble(LongDouble a) {
        need();
        final double[] r = new double[1];
        try {
            toF64.invokeExact(MemorySegment.ofArray(a.toX87()), MemorySegment.ofArray(r));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return r[0];
    }

    public static LongDouble fromLong(long x) {
        need();
        final byte[] r = new byte[10];
        try {
            fromI64.invokeExact(MemorySegment.ofArray(new long[] {x}), MemorySegment.ofArray(r));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return LongDouble.fromX87(r);
    }

    /** (long long) a: truncated toward zero; out of range (or NaN), the x87's integer indefinite, Long.MIN_VALUE. */
    public static long toLong(LongDouble a) {
        need();
        final long[] r = new long[1];
        try {
            toI64.invokeExact(MemorySegment.ofArray(a.toX87()), MemorySegment.ofArray(r));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return r[0];
    }

    /** MinGW-w64's sinl. */
    public static LongDouble sin(LongDouble x) {
        need();
        return LongDouble.fromX87(unary(sin, x.toX87()));
    }

    /** MinGW-w64's cosl. */
    public static LongDouble cos(LongDouble x) {
        need();
        return LongDouble.fromX87(unary(cos, x.toX87()));
    }

    /** MinGW-w64's asinl. */
    public static LongDouble asin(LongDouble x) {
        need();
        return LongDouble.fromX87(unary(asin, x.toX87()));
    }

    /** MinGW-w64's atan2l. */
    public static LongDouble atan2(LongDouble y, LongDouble x) {
        need();
        return LongDouble.fromX87(binary(atan2, y.toX87(), x.toX87()));
    }

    /* ---- the software against the hardware -------------------------------------------------- */

    /** What a check found: the cases compared, the differences, a few of them, and the time of each side. */
    public record Check(int cases, int differences, List<String> examples, double softwareNanos, double x87Nanos) {
        public boolean passed() {
            return differences == 0;
        }

        public String text() {
            final StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "%d operations compared, %d differ%s; "
                + "%.0f ns per operation in software, %.0f ns on the x87 unit", cases, differences,
                differences == 0 ? " (the software long double gives the x87's bits)" : "", softwareNanos, x87Nanos));
            for (String e : examples) s.append("\n  ").append(e);
            return s.toString();
        }
    }

    /** Equal bits, or both NaN (the payload of a NaN is not compared). */
    private static boolean same(byte[] a, byte[] b) {
        final LongDouble x = LongDouble.fromX87(a);
        final LongDouble y = LongDouble.fromX87(b);
        if (x.isNaN() || y.isNaN()) return x.isNaN() && y.isNaN();
        return java.util.Arrays.equals(a, b);
    }

    private static String hex(byte[] b) {
        final StringBuilder s = new StringBuilder();
        for (int i = 9; i >= 0; i--) s.append(String.format("%02x", b[i] & 0xFF));
        return s.toString();
    }

    /** A value of the kinds that test a long double: near 1, anywhere, near both ends, subnormal, with few bits. */
    static byte[] random(SplittableRandom r) {
        long sig = r.nextLong() | 0x8000000000000000L;
        int exp;
        switch (r.nextInt(12)) {
            case 0, 1, 2, 3, 4 -> exp = 16383 + r.nextInt(-80, 81);
            case 5 -> exp = r.nextInt(1, 32767);
            case 6 -> exp = r.nextInt(1, 80);
            case 7 -> {
                exp = 0;
                sig >>>= r.nextInt(1, 64);
            }
            case 8 -> exp = 32766 - r.nextInt(0, 80);
            case 9 -> {
                exp = 16383 + r.nextInt(-20, 21);
                sig &= -1L << r.nextInt(40, 64);
            }
            default -> {
                exp = 16383 + r.nextInt(-3, 4);
                sig &= -1L << r.nextInt(0, 64);
            }
        }
        final int top = (r.nextBoolean() ? 0x8000 : 0) | exp;
        final byte[] b = new byte[10];
        for (int i = 0; i < 8; i++) b[i] = (byte) (sig >>> (8 * i));
        b[8] = (byte) top;
        b[9] = (byte) (top >>> 8);
        return b;
    }

    private static byte[] x87(long sig, int top) {
        final byte[] b = new byte[10];
        for (int i = 0; i < 8; i++) b[i] = (byte) (sig >>> (8 * i));
        b[8] = (byte) top;
        b[9] = (byte) (top >>> 8);
        return b;
    }

    /** The values every check includes: zeros, infinities, NaN, one, the ends of the range and of the subnormals. */
    static List<byte[]> specials() {
        final List<byte[]> l = new ArrayList<>();
        for (int s : new int[] {0, 0x8000}) {
            l.add(x87(0, s));                                   // 0
            l.add(x87(0x8000000000000000L, s | 0x7FFF));        // inf
            l.add(x87(0x8000000000000000L, s | 16383));         // 1
            l.add(x87(0xC000000000000000L, s | 16384));         // 3
            l.add(x87(0xAAAAAAAAAAAAAAABL, s | 16381));         // ~1/3
            l.add(x87(1L, s));                                  // smallest subnormal
            l.add(x87(0x7FFFFFFFFFFFFFFFL, s));                 // largest subnormal
            l.add(x87(0x8000000000000000L, s | 1));             // smallest normal
            l.add(x87(0xFFFFFFFFFFFFFFFFL, s | 0x7FFE));        // largest finite
            l.add(x87(0xFFFFFFFFFFFFFFFFL, s | 16383));         // 2 - ulp
        }
        l.add(x87(0xC000000000000000L, 0xFFFF));                // the x87's default NaN
        return l;
    }

    /**
     * Compares LongDouble's software with this processor's x87 unit: add, subtract, multiply,
     * divide, square root, comparison, the conversions to and from double; every pair of the
     * special values and n random pairs. On Windows, also MinGW's sinl, cosl, asinl of this code
     * against NativeMath's.
     */
    public static Check check(int n, long seed) {
        need();
        final SplittableRandom r = new SplittableRandom(seed);
        final List<byte[][]> pairs = new ArrayList<>();
        final List<byte[]> sp = specials();
        for (byte[] a : sp) for (byte[] b : sp) pairs.add(new byte[][] {a, b});
        for (int i = 0; i < n; i++) {
            final byte[] a = random(r);
            byte[] b = random(r);
            if (r.nextInt(8) == 0) {
                // close to a: cancellations
                b = a.clone();
                b[0] ^= (byte) r.nextInt(1, 256);
                b[9] ^= (byte) (r.nextBoolean() ? 0x80 : 0);
            }
            pairs.add(new byte[][] {a, b});
        }
        int cases = 0;
        int diffs = 0;
        final List<String> ex = new ArrayList<>();
        long tSoft = 0;
        long tX87 = 0;
        long ops = 0;
        for (byte[][] p : pairs) {
            final LongDouble a = LongDouble.fromX87(p[0]);
            final LongDouble b = LongDouble.fromX87(p[1]);
            final String[] names = {"+", "-", "*", "/", "sqrt"};
            for (int k = 0; k < names.length; k++) {
                final long t0 = System.nanoTime();
                final LongDouble s = switch (k) {
                    case 0 -> a.addSoftware(b);
                    case 1 -> a.addSoftware(b.negate());
                    case 2 -> a.multiplySoftware(b);
                    case 3 -> a.divideSoftware(b);
                    default -> a.sqrtSoftware();
                };
                final byte[] sb = s.toX87();
                final long t1 = System.nanoTime();
                final byte[] hb = switch (k) {
                    case 0 -> binary(add, p[0], p[1]);
                    case 1 -> binary(sub, p[0], p[1]);
                    case 2 -> binary(mul, p[0], p[1]);
                    case 3 -> binary(div, p[0], p[1]);
                    default -> unary(sqrt, p[0]);
                };
                final long t2 = System.nanoTime();
                tSoft += t1 - t0;
                tX87 += t2 - t1;
                ops++;
                cases++;
                if (!same(sb, hb)) {
                    diffs++;
                    if (ex.size() < 8) {
                        ex.add(names[k] + " of " + hex(p[0]) + (k < 4 ? " and " + hex(p[1]) : "") + ": software " + hex(sb)
                            + ", x87 " + hex(hb));
                    }
                }
            }
            // comparison
            final int c;
            try {
                c = (int) cmp.invokeExact(MemorySegment.ofArray(p[0]), MemorySegment.ofArray(p[1]));
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            final int sc = a.lt(b) ? -1 : a.eq(b) ? 0 : a.gt(b) ? 1 : 2;
            cases++;
            if (c != sc) {
                diffs++;
                if (ex.size() < 8) ex.add("compare " + hex(p[0]) + " and " + hex(p[1]) + ": software " + sc + ", x87 " + c);
            }
            // to double
            final double sd = a.toDouble();
            final double hd = toDouble(a);
            cases++;
            if (Double.doubleToRawLongBits(sd) != Double.doubleToRawLongBits(hd) && !(Double.isNaN(sd) && Double.isNaN(hd))) {
                diffs++;
                if (ex.size() < 8) ex.add("to double " + hex(p[0]) + ": software " + sd + ", x87 " + hd);
            }
        }
        // from double
        for (int i = 0; i < n; i++) {
            final double d = Double.longBitsToDouble(r.nextLong());
            cases++;
            if (!same(LongDouble.of(d).toX87(), fromDouble(d).toX87())) {
                diffs++;
                if (ex.size() < 8) ex.add("from double " + d);
            }
        }
        // MinGW's functions: this code against NativeMath's own instructions
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
            && NativeMath.target() == NativeMath.Target.HOST && !NativeMath.longDoubleIsDouble()) {
            for (int i = 0; i < n / 4; i++) {
                final LongDouble x = LongDouble.of(i % 50 == 0 ? Math.scalb(r.nextDouble(), r.nextInt(60, 80))
                    : (r.nextDouble() * 2 - 1) * (i % 3 == 0 ? 1 : 1e4));
                final LongDouble y = LongDouble.of(r.nextDouble() * 2 - 1);
                final byte[][] mine = {sin(x).toX87(), cos(x).toX87(), asin(y).toX87()};
                final byte[][] theirs = {NativeMath.sinl(x).toX87(), NativeMath.cosl(x).toX87(), NativeMath.asinl(y).toX87()};
                for (int k = 0; k < 3; k++) {
                    cases++;
                    if (!same(mine[k], theirs[k])) {
                        diffs++;
                        if (ex.size() < 8) ex.add(new String[] {"sinl", "cosl", "asinl"}[k] + " of " + (k < 2 ? x : y));
                    }
                }
            }
        }
        return new Check(cases, diffs, ex, ops == 0 ? 0 : (double) tSoft / ops, ops == 0 ? 0 : (double) tX87 / ops);
    }

    /** java ... X87Abi [n]: the check, printed. */
    public static void main(String[] args) {
        System.out.println("Sphere x87 ABI: " + describe());
        if (!available()) return;
        final Check c = check(args.length > 0 ? Integer.parseInt(args[0]) : 20000, 1);
        System.out.println(c.text());
        if (!c.passed()) System.exit(1);
    }
}

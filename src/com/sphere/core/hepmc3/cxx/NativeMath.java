package com.sphere.core.hepmc3.cxx;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;

/**
 * The mathematical functions of a C++ program, computed from Java so that a
 * port gives the bits the C++ gives.
 *
 * <p>A C++ library calls the C library of the machine it runs on: exp, log,
 * sin... on double, and sinl, cosl, asinl, powl on long double, which is the
 * 80-bit x87 format with GCC and Clang on x86-64 (Linux, WSL, macOS Intel,
 * MinGW). Those results differ from one C library to another, and the long
 * double ones are not even correctly rounded: glibc's sinl is a polynomial
 * of its own, MinGW's is the x87 fsin instruction. So a port that is to give
 * the C++'s bits must call the same functions:
 *
 * <ul>
 *   <li>{@link Target#HOST}, the C library of this machine, through the
 *       Foreign Function and Memory API: the double functions directly; the
 *       long double ones, which that API cannot pass, through a few
 *       instructions written into an executable page that load the 80-bit
 *       value, call the library's function as the C ABI wants (System V on
 *       Linux, WSL and macOS Intel) or run the very x87 instructions MinGW's
 *       library runs (Windows), and store the 80-bit result;</li>
 *   <li>{@link Target#PORTABLE}, the same on every machine: Java's StrictMath
 *       (fdlibm) for double, correctly rounded values for long double.</li>
 * </ul>
 *
 * Arithmetic on long double (add, multiply, divide, square root) is exact
 * IEEE rounding at 64 bits and is done by {@link LongDouble} in both cases.
 * On a machine where long double is double (MSVC, Apple Silicon), the long
 * double functions are the double ones.
 */
public final class NativeMath {

    /** Which C library the functions reproduce. */
    public enum Target { HOST, PORTABLE }

    private static volatile Target target;
    private static String hostDescription = "";
    private static String hostFailure = "";

    private static MethodHandle exp, log, log10, sin, cos, asin, atan, pow;
    /** The other double functions (CLHEP's): tan, acos, sinh, cosh, tanh, atan2 and hypot (std::abs of a complex). */
    private static MethodHandle tan, acos, sinh, cosh, tanh, atan2, hypot;
    /** The float functions a C++ std::log, std::exp... of a float calls (CLHEP's RandExpZiggurat, Geometry). */
    private static MethodHandle logf, expf, sinf, cosf, atan2f;
    private static MethodHandle sinl, cosl, asinl, powl;
    private static boolean longDoubleIsDouble;
    private static boolean hostReady;

    private NativeMath() {
    }

    /** Where 80-bit values cross to the native code: per thread, 16-byte aligned. */
    private static final ThreadLocal<MemorySegment> BUFFER = ThreadLocal.withInitial(() -> Arena.global().allocate(48, 16));

    static {
        try {
            setUpHost();
            hostReady = true;
        } catch (Throwable t) {
            hostFailure = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        target = hostReady ? Target.HOST : Target.PORTABLE;
    }

    public static Target target() {
        return target;
    }

    /** Chooses the C library to reproduce; HOST falls back to PORTABLE when this machine's cannot be reached. */
    public static void setTarget(Target t) {
        target = t == Target.HOST && !hostReady ? Target.PORTABLE : t;
    }

    public static boolean hostAvailable() {
        return hostReady;
    }

    /** Why this machine's C library is not reachable; empty when it is. */
    public static String hostFailure() {
        return hostFailure;
    }

    /**
     * Native access is the launcher's to grant: the manifest of Sphere.jar
     * (Enable-Native-Access) grants it to 'java -jar Sphere.jar', the option
     * --enable-native-access to any other launch. Asked before any restricted
     * method is called, so that without it none is: the portable functions
     * serve and {@link #hostFailure} says why, instead of the JVM's warning
     * today and the call refused tomorrow.
     */
    static void requireNativeAccess() {
        final Module self = NativeMath.class.getModule();
        if (!self.isNativeAccessEnabled()) {
            throw new IllegalCallerException("native access is not enabled for Sphere (run 'java -jar Sphere.jar', "
                + "whose manifest enables it, or add --enable-native-access=" + (self.isNamed() ? self.getName() : "ALL-UNNAMED") + ")");
        }
    }

    /** What the functions are, in a sentence. */
    public static String describe() {
        if (target == Target.PORTABLE) {
            return "portable: StrictMath (fdlibm) for double, correctly rounded x87 long double"
                + (hostReady ? "" : " (this machine's C library is not reachable: " + hostFailure + ")");
        }
        return hostDescription;
    }

    /** True when the reproduced long double is double (MSVC, Apple Silicon): the long double functions are the double ones. */
    public static boolean longDoubleIsDouble() {
        return target == Target.HOST && longDoubleIsDouble;
    }

    /* ---- double ----------------------------------------------------------------- */

    public static double exp(double x) {
        return target == Target.HOST ? call(exp, x) : StrictMath.exp(x);
    }

    public static double log(double x) {
        return target == Target.HOST ? call(log, x) : StrictMath.log(x);
    }

    public static double log10(double x) {
        return target == Target.HOST ? call(log10, x) : StrictMath.log10(x);
    }

    public static double sin(double x) {
        return target == Target.HOST ? call(sin, x) : StrictMath.sin(x);
    }

    public static double cos(double x) {
        return target == Target.HOST ? call(cos, x) : StrictMath.cos(x);
    }

    public static double asin(double x) {
        return target == Target.HOST ? call(asin, x) : StrictMath.asin(x);
    }

    public static double atan(double x) {
        return target == Target.HOST ? call(atan, x) : StrictMath.atan(x);
    }

    public static double pow(double x, double y) {
        if (target != Target.HOST) return StrictMath.pow(x, y);
        try {
            return (double) pow.invokeExact(x, y);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static double tan(double x) {
        return target == Target.HOST ? call(tan, x) : StrictMath.tan(x);
    }

    public static double acos(double x) {
        return target == Target.HOST ? call(acos, x) : StrictMath.acos(x);
    }

    public static double sinh(double x) {
        return target == Target.HOST ? call(sinh, x) : StrictMath.sinh(x);
    }

    public static double cosh(double x) {
        return target == Target.HOST ? call(cosh, x) : StrictMath.cosh(x);
    }

    public static double tanh(double x) {
        return target == Target.HOST ? call(tanh, x) : StrictMath.tanh(x);
    }

    public static double atan2(double y, double x) {
        if (target != Target.HOST) return StrictMath.atan2(y, x);
        try {
            return (double) atan2.invokeExact(y, x);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    public static double hypot(double x, double y) {
        if (target != Target.HOST) return StrictMath.hypot(x, y);
        try {
            return (double) hypot.invokeExact(x, y);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static double call(MethodHandle h, double x) {
        try {
            return (double) h.invokeExact(x);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /* ---- float ------------------------------------------------------------------ */

    public static float logf(float x) {
        return target == Target.HOST ? callf(logf, x) : (float) StrictMath.log(x);
    }

    public static float expf(float x) {
        return target == Target.HOST ? callf(expf, x) : (float) StrictMath.exp(x);
    }

    public static float sinf(float x) {
        return target == Target.HOST ? callf(sinf, x) : (float) StrictMath.sin(x);
    }

    public static float cosf(float x) {
        return target == Target.HOST ? callf(cosf, x) : (float) StrictMath.cos(x);
    }

    public static float atan2f(float y, float x) {
        if (target != Target.HOST) return (float) StrictMath.atan2(y, x);
        try {
            return (float) atan2f.invokeExact(y, x);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static float callf(MethodHandle h, float x) {
        try {
            return (float) h.invokeExact(x);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /* ---- long double ----------------------------------------------------------- */

    public static LongDouble sinl(LongDouble x) {
        if (target == Target.HOST) return longDoubleIsDouble ? LongDouble.of(sin(x.toDouble())) : x87(sinl, x);
        return Exact.sin(x);
    }

    public static LongDouble cosl(LongDouble x) {
        if (target == Target.HOST) return longDoubleIsDouble ? LongDouble.of(cos(x.toDouble())) : x87(cosl, x);
        return Exact.cos(x);
    }

    public static LongDouble asinl(LongDouble x) {
        if (target == Target.HOST) return longDoubleIsDouble ? LongDouble.of(asin(x.toDouble())) : x87(asinl, x);
        return Exact.asin(x);
    }

    public static LongDouble powl(LongDouble x, LongDouble y) {
        if (target == Target.HOST) {
            if (longDoubleIsDouble) return LongDouble.of(pow(x.toDouble(), y.toDouble()));
            if (powl != null) return x87(powl, x, y);
        }
        return Exact.pow(x, y);
    }

    private static LongDouble x87(MethodHandle h, LongDouble x) {
        final MemorySegment b = BUFFER.get();
        final MemorySegment in = b.asSlice(0, 16);
        final MemorySegment out = b.asSlice(32, 16);
        in.copyFrom(MemorySegment.ofArray(x.toX87()));
        try {
            h.invokeExact(in, out);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return LongDouble.fromX87(out.asSlice(0, 10).toArray(ValueLayout.JAVA_BYTE));
    }

    private static LongDouble x87(MethodHandle h, LongDouble x, LongDouble y) {
        final MemorySegment b = BUFFER.get();
        final MemorySegment in = b.asSlice(0, 16);
        final MemorySegment in2 = b.asSlice(16, 16);
        final MemorySegment out = b.asSlice(32, 16);
        in.copyFrom(MemorySegment.ofArray(x.toX87()));
        in2.copyFrom(MemorySegment.ofArray(y.toX87()));
        try {
            h.invokeExact(in, in2, out);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return LongDouble.fromX87(out.asSlice(0, 10).toArray(ValueLayout.JAVA_BYTE));
    }

    /* ---- this machine ----------------------------------------------------------- */

    private static void setUpHost() throws Throwable {
        requireNativeAccess();
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        final boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        final boolean windows = os.contains("win");
        final boolean mac = os.contains("mac") || os.contains("darwin");
        final Linker linker = Linker.nativeLinker();
        final Arena arena = Arena.global();
        final SymbolLookup libm;
        String library;
        if (windows) {
            libm = SymbolLookup.libraryLookup("ucrtbase", arena);
            library = "the Universal C Runtime (ucrtbase)";
        } else if (mac) {
            libm = linker.defaultLookup();
            library = "the macOS C library (libSystem)";
        } else {
            libm = lookupFirst(arena, "libm.so.6", "libm.so");
            library = "the GNU C library (libm.so.6)";
        }
        final FunctionDescriptor d1 = FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE);
        exp = linker.downcallHandle(find(libm, "exp"), d1);
        log = linker.downcallHandle(find(libm, "log"), d1);
        log10 = linker.downcallHandle(find(libm, "log10"), d1);
        sin = linker.downcallHandle(find(libm, "sin"), d1);
        cos = linker.downcallHandle(find(libm, "cos"), d1);
        asin = linker.downcallHandle(find(libm, "asin"), d1);
        atan = linker.downcallHandle(find(libm, "atan"), d1);
        pow = linker.downcallHandle(find(libm, "pow"),
            FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));
        tan = linker.downcallHandle(find(libm, "tan"), d1);
        acos = linker.downcallHandle(find(libm, "acos"), d1);
        sinh = linker.downcallHandle(find(libm, "sinh"), d1);
        cosh = linker.downcallHandle(find(libm, "cosh"), d1);
        tanh = linker.downcallHandle(find(libm, "tanh"), d1);
        atan2 = linker.downcallHandle(find(libm, "atan2"),
            FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));
        hypot = linker.downcallHandle(find(libm, "hypot"),
            FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));
        final FunctionDescriptor f1 = FunctionDescriptor.of(ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT);
        logf = linker.downcallHandle(find(libm, "logf"), f1);
        expf = linker.downcallHandle(find(libm, "expf"), f1);
        sinf = linker.downcallHandle(find(libm, "sinf"), f1);
        cosf = linker.downcallHandle(find(libm, "cosf"), f1);
        atan2f = linker.downcallHandle(find(libm, "atan2f"),
            FunctionDescriptor.of(ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
        if (!x64) {
            // Apple Silicon and Windows on ARM: long double is double; Linux on aarch64 has a 128-bit
            // long double this class does not reach: its long double functions stay portable
            longDoubleIsDouble = mac || windows;
            hostDescription = library + (longDoubleIsDouble ? ", long double = double" : ", long double functions portable")
                + " (" + arch + ")";
            if (!longDoubleIsDouble) throw new UnsupportedOperationException("128-bit long double on " + arch);
            return;
        }
        final FunctionDescriptor unary = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        final FunctionDescriptor binary = FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
        if (windows) {
            // MinGW's long double functions are in a static library: run their x87 instructions here
            final MemorySegment page = Executable.load(linker, true,
                Executable.slots(X87.minGwTrig(X87.FSIN), X87.minGwTrig(X87.FCOS), X87.minGwAsin()));
            sinl = linker.downcallHandle(Executable.slot(page, 0), unary);
            cosl = linker.downcallHandle(Executable.slot(page, 1), unary);
            asinl = linker.downcallHandle(Executable.slot(page, 2), unary);
            powl = null;
            hostDescription = library + " for double; the x87 instructions of MinGW's sinl, cosl and asinl for long double";
        } else {
            // System V: call the library's own long double functions through an adapter
            final MemorySegment page = Executable.load(linker, false, Executable.slots(
                X87.sysvAdapter(find(libm, "sinl").address(), 1),
                X87.sysvAdapter(find(libm, "cosl").address(), 1),
                X87.sysvAdapter(find(libm, "asinl").address(), 1),
                X87.sysvAdapter(find(libm, "powl").address(), 2)));
            sinl = linker.downcallHandle(Executable.slot(page, 0), unary);
            cosl = linker.downcallHandle(Executable.slot(page, 1), unary);
            asinl = linker.downcallHandle(Executable.slot(page, 2), unary);
            powl = linker.downcallHandle(Executable.slot(page, 3), binary);
            hostDescription = library + ", its long double functions on the x87 80-bit format";
        }
        // check the long double functions once: sin(0.5) and asin(0.5) must be what they are
        final LongDouble half = LongDouble.of(0.5);
        if (Math.abs(x87(sinl, half).toDouble() - 0.479425538604203) > 1e-15
            || Math.abs(x87(asinl, half).toDouble() - 0.5235987755982989) > 1e-15) {
            throw new IllegalStateException("the x87 thunks give wrong values");
        }
    }

    private static SymbolLookup lookupFirst(Arena arena, String... names) {
        RuntimeException last = null;
        for (String n : names) {
            try {
                return SymbolLookup.libraryLookup(n, arena);
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last;
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
        final Optional<MemorySegment> s = lookup.find(name);
        return s.orElseThrow(() -> new UnsupportedOperationException("no " + name + " in the C library"));
    }

    /**
     * Pages of machine code, from the operating system, never writable and
     * executable at once (W^X): the page is mapped read-write, the code copied
     * in, then the page made read-execute for good.
     */
    static final class Executable {
        private static final long PAGE = 4096;
        /** The room of one thunk in a page of {@link #slots}. */
        private static final int SLOT = 128;

        /** Thunks one after the other, each at the start of a slot of {@link #SLOT} bytes. */
        static byte[] slots(byte[]... thunks) {
            final byte[] image = new byte[thunks.length * SLOT];
            for (int i = 0; i < thunks.length; i++) {
                if (thunks[i].length > SLOT) throw new IllegalStateException("a thunk of " + thunks[i].length + " bytes overflows its slot");
                System.arraycopy(thunks[i], 0, image, i * SLOT, thunks[i].length);
            }
            return image;
        }

        /** The entry of the thunk in slot i of a page loaded from {@link #slots}. */
        static MemorySegment slot(MemorySegment page, int i) {
            return page.asSlice((long) i * SLOT);
        }

        /** The code in a page of its own, read-execute; the segment starts at its first byte. */
        static MemorySegment load(Linker linker, boolean windows, byte[] code) throws Throwable {
            if (code.length > PAGE) throw new IllegalStateException("the code does not fit its page");
            final MemorySegment page;
            if (windows) {
                final SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                final MethodHandle valloc = linker.downcallHandle(find(k32, "VirtualAlloc"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                final MethodHandle vprotect = linker.downcallHandle(find(k32, "VirtualProtect"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                final MethodHandle flush = linker.downcallHandle(find(k32, "FlushInstructionCache"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
                // MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE
                page = mapped((MemorySegment) valloc.invokeExact(MemorySegment.NULL, PAGE, 0x3000, 0x04));
                page.copyFrom(MemorySegment.ofArray(code));
                try (Arena a = Arena.ofConfined()) {
                    // PAGE_EXECUTE_READ; the previous protection is written to 'old'
                    final MemorySegment old = a.allocate(ValueLayout.JAVA_INT);
                    if ((int) vprotect.invokeExact(page, PAGE, 0x20, old) == 0) {
                        throw new IllegalStateException("VirtualProtect refused to make the code executable");
                    }
                }
                // GetCurrentProcess() is the pseudo handle -1
                if ((int) flush.invokeExact(MemorySegment.ofAddress(-1L), page, PAGE) == 0) {
                    throw new IllegalStateException("FlushInstructionCache failed");
                }
            } else {
                final SymbolLookup libc = linker.defaultLookup();
                final MethodHandle mmap = linker.downcallHandle(find(libc, "mmap"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
                final MethodHandle mprotect = linker.downcallHandle(find(libc, "mprotect"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
                final boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
                // PROT_READ | PROT_WRITE; MAP_PRIVATE | MAP_ANONYMOUS
                page = mapped((MemorySegment) mmap.invokeExact(MemorySegment.NULL, PAGE, 3, mac ? 0x1002 : 0x22, -1, 0L));
                page.copyFrom(MemorySegment.ofArray(code));
                // PROT_READ | PROT_EXEC
                if ((int) mprotect.invokeExact(page, PAGE, 5) != 0) {
                    throw new IllegalStateException("mprotect refused to make the code executable");
                }
            }
            return page;
        }

        /** The page, or the failure of VirtualAlloc (NULL) and mmap (MAP_FAILED). */
        private static MemorySegment mapped(MemorySegment page) {
            if (page.address() == 0 || page.address() == -1L) throw new IllegalStateException("no memory for the code");
            return page.reinterpret(PAGE);
        }
    }

    /** x86-64 machine code. */
    private static final class X87 {
        static final int FSIN = 0xFE;
        static final int FCOS = 0xFF;

        static byte[] b(int... v) {
            final byte[] out = new byte[v.length];
            for (int i = 0; i < v.length; i++) out[i] = (byte) v[i];
            return out;
        }

        static byte[] concat(byte[]... parts) {
            int n = 0;
            for (byte[] p : parts) n += p.length;
            final byte[] out = new byte[n];
            int at = 0;
            for (byte[] p : parts) {
                System.arraycopy(p, 0, out, at, p.length);
                at += p.length;
            }
            return out;
        }

        /** Saves the caller's x87 control word and sets 64-bit precision, as C++ programs run (0x037F). */
        static final byte[] ENTER = b(
            0x48, 0x83, 0xEC, 0x08,                   // sub $8,%rsp
            0xD9, 0x3C, 0x24,                         // fnstcw (%rsp)
            0x66, 0xC7, 0x44, 0x24, 0x02, 0x7F, 0x03, // movw $0x037f,2(%rsp)
            0xD9, 0x6C, 0x24, 0x02);                  // fldcw 2(%rsp)
        /** Restores the caller's control word and returns. */
        static final byte[] LEAVE = b(
            0xD9, 0x2C, 0x24,                         // fldcw (%rsp)
            0x48, 0x83, 0xC4, 0x08,                   // add $8,%rsp
            0xC3);                                    // ret

        /**
         * MinGW's __sinl_internal / __cosl_internal (Windows convention: in rcx,
         * out rdx): fsin or fcos, and when the argument is beyond the
         * instruction's range, the reduction by 2 pi with fprem1, then again.
         */
        static byte[] minGwTrig(int op) {
            final byte[] tail = concat(b(0xDB, 0x3A), LEAVE);         // fstpt (%rdx)
            final byte[] reduce = concat(b(
                0xD9, 0xEB,                   // fldpi
                0xD8, 0xC0,                   // fadd %st(0),%st
                0xD9, 0xC9,                   // fxch %st(1)
                0xD9, 0xF5,                   // L: fprem1
                0xDF, 0xE0,                   // fnstsw %ax
                0xA9, 0x00, 0x04, 0x00, 0x00, // test $0x400,%eax
                0x75, 0xF5,                   // jne L
                0xDD, 0xD9,                   // fstp %st(1)
                0xD9, op), tail);             // fsin / fcos
            return concat(ENTER, b(
                0xDB, 0x29,                   // fldt (%rcx)
                0xD9, op,                     // fsin / fcos
                0xDF, 0xE0,                   // fnstsw %ax
                0xA9, 0x00, 0x04, 0x00, 0x00, // test $0x400,%eax
                0x75, tail.length), tail, reduce);
        }

        /** MinGW's asinl: atan2(x, sqrt(1 - x*x)) on the x87 unit. */
        static byte[] minGwAsin() {
            return concat(ENTER, b(
                0xDB, 0x29,                   // fldt (%rcx)
                0xD9, 0xC0,                   // fld %st(0)
                0xD8, 0xC8,                   // fmul %st(0),%st
                0xD9, 0xE8,                   // fld1
                0xDE, 0xE1,                   // fsubp %st,%st(1)
                0xD9, 0xFA,                   // fsqrt
                0xD9, 0xF3,                   // fpatan
                0xDB, 0x3A), LEAVE);          // fstpt (%rdx)
        }

        /**
         * Calls a long double function of the C library as System V wants it
         * (each 80-bit argument in 16 bytes on the stack, the result in st(0)):
         * in rdi (and rsi), out in the next register.
         */
        static byte[] sysvAdapter(long function, int args) {
            final int frame = args == 1 ? 0x20 : 0x30;
            final byte[] copyArgs = args == 1
                ? b(0x48, 0x8B, 0x07,                         // mov (%rdi),%rax
                    0x48, 0x89, 0x04, 0x24,                   // mov %rax,(%rsp)
                    0x0F, 0xB7, 0x47, 0x08,                   // movzwl 8(%rdi),%eax
                    0x66, 0x89, 0x44, 0x24, 0x08)             // mov %ax,8(%rsp)
                : b(0x48, 0x8B, 0x07,                         // mov (%rdi),%rax
                    0x48, 0x89, 0x04, 0x24,                   // mov %rax,(%rsp)
                    0x0F, 0xB7, 0x47, 0x08,                   // movzwl 8(%rdi),%eax
                    0x66, 0x89, 0x44, 0x24, 0x08,             // mov %ax,8(%rsp)
                    0x48, 0x8B, 0x06,                         // mov (%rsi),%rax
                    0x48, 0x89, 0x44, 0x24, 0x10,             // mov %rax,0x10(%rsp)
                    0x0F, 0xB7, 0x46, 0x08,                   // movzwl 8(%rsi),%eax
                    0x66, 0x89, 0x44, 0x24, 0x18);            // mov %ax,0x18(%rsp)
            final byte[] out = args == 1 ? b(0x48, 0x89, 0xF3) : b(0x48, 0x89, 0xD3); // mov %rsi/%rdx,%rbx
            final byte[] target = new byte[8];
            for (int i = 0; i < 8; i++) target[i] = (byte) (function >>> (8 * i));
            return concat(
                b(0x53),                                      // push %rbx
                out,
                b(0x48, 0x83, 0xEC, frame + 0x10),            // sub $frame+16,%rsp  (control word slot above the args)
                b(0xD9, 0x7C, 0x24, frame),                   // fnstcw frame(%rsp)
                b(0x66, 0xC7, 0x44, 0x24, frame + 2, 0x7F, 0x03), // movw $0x037f,frame+2(%rsp)
                b(0xD9, 0x6C, 0x24, frame + 2),               // fldcw frame+2(%rsp)
                copyArgs,
                b(0x48, 0xB8), target,                        // movabs $function,%rax
                b(0xFF, 0xD0),                                // call *%rax
                b(0xDB, 0x3B),                                // fstpt (%rbx)
                b(0xD9, 0x6C, 0x24, frame),                   // fldcw frame(%rsp)
                b(0x48, 0x83, 0xC4, frame + 0x10),            // add $frame+16,%rsp
                b(0x5B, 0xC3));                               // pop %rbx; ret
        }
    }

    /* ---- correctly rounded long double, for the portable target ------------------- */

    /** sin, cos, asin and pow of an x87 value, rounded once from 60 exact digits. */
    static final class Exact {
        private static final MathContext MC = new MathContext(60, RoundingMode.HALF_EVEN);
        private static BigDecimal pi;

        static LongDouble sin(LongDouble x) {
            if (x.isNaN() || x.isInfinite()) return LongDouble.of(Double.NaN);
            if (x.isZero()) return x;
            return round(sinOf(x.toBigDecimal()));
        }

        static LongDouble cos(LongDouble x) {
            if (x.isNaN() || x.isInfinite()) return LongDouble.of(Double.NaN);
            return round(sinOf(x.toBigDecimal().add(pi().divide(BigDecimal.valueOf(2), MC), MC)));
        }

        static LongDouble asin(LongDouble x) {
            if (x.isNaN()) return x;
            final BigDecimal v = x.toBigDecimal();
            final int c = v.abs().compareTo(BigDecimal.ONE);
            if (c > 0) return LongDouble.of(Double.NaN);
            if (x.isZero()) return x;
            if (c == 0) return round(pi().divide(BigDecimal.valueOf(2), MC).multiply(BigDecimal.valueOf(v.signum())));
            final BigDecimal root = BigDecimal.ONE.subtract(v.multiply(v, MC), MC).sqrt(MC);
            return round(atanOf(v.divide(root, MC)));
        }

        static LongDouble pow(LongDouble x, LongDouble y) {
            final BigDecimal vx = x.toBigDecimal();
            final BigDecimal vy = y.toBigDecimal();
            if (vy.signum() == 0) return LongDouble.of(1.0);
            if (vx.signum() <= 0) return LongDouble.of(StrictMath.pow(x.toDouble(), y.toDouble()));
            return round(expOf(vy.multiply(lnOf(vx), MC)));
        }

        private static LongDouble round(BigDecimal v) {
            if (v.signum() == 0) return LongDouble.of(0.0);
            // v = n * 2^k with n of about 80 bits, the rest a sticky bit
            final int k = (int) Math.floor((v.unscaledValue().abs().bitLength()
                - v.scale() * 3.321928094887362) - 80);
            BigDecimal scaled = k >= 0 ? v.divide(new BigDecimal(BigInteger.TWO.pow(k)))
                : v.multiply(new BigDecimal(BigInteger.TWO.pow(-k)));
            final BigInteger n = scaled.setScale(0, RoundingMode.DOWN).toBigIntegerExact();
            final boolean sticky = scaled.compareTo(new BigDecimal(n)) != 0;
            return LongDouble.ofScaled(n, k, sticky);
        }

        private static synchronized BigDecimal pi() {
            if (pi == null) {
                // Machin: pi = 16 atan(1/5) - 4 atan(1/239)
                pi = atanSeries(BigDecimal.ONE.divide(BigDecimal.valueOf(5), MC)).multiply(BigDecimal.valueOf(16))
                    .subtract(atanSeries(BigDecimal.ONE.divide(BigDecimal.valueOf(239), MC)).multiply(BigDecimal.valueOf(4)), MC);
            }
            return pi;
        }

        private static BigDecimal sinOf(BigDecimal x) {
            final BigDecimal twoPi = pi().multiply(BigDecimal.valueOf(2));
            BigDecimal r = x.remainder(twoPi, MC);
            BigDecimal term = r;
            BigDecimal sum = r;
            final BigDecimal r2 = r.multiply(r, MC);
            for (int i = 1; i < 200; i++) {
                term = term.multiply(r2, MC).divide(BigDecimal.valueOf((2L * i) * (2L * i + 1)), MC).negate();
                sum = sum.add(term, MC);
                if (term.abs().compareTo(BigDecimal.ONE.movePointLeft(70)) < 0) break;
            }
            return sum;
        }

        private static BigDecimal atanSeries(BigDecimal x) {
            BigDecimal term = x;
            BigDecimal sum = x;
            final BigDecimal x2 = x.multiply(x, MC);
            for (int i = 1; i < 2000; i++) {
                term = term.multiply(x2, MC).negate();
                final BigDecimal t = term.divide(BigDecimal.valueOf(2L * i + 1), MC);
                sum = sum.add(t, MC);
                if (t.abs().compareTo(BigDecimal.ONE.movePointLeft(70)) < 0) break;
            }
            return sum;
        }

        private static BigDecimal atanOf(BigDecimal x) {
            // halve the argument until it is small: atan x = 2 atan(x / (1 + sqrt(1 + x^2)))
            int doublings = 0;
            BigDecimal v = x;
            while (v.abs().compareTo(new BigDecimal("0.1")) > 0) {
                v = v.divide(BigDecimal.ONE.add(BigDecimal.ONE.add(v.multiply(v, MC), MC).sqrt(MC), MC), MC);
                doublings++;
            }
            return atanSeries(v).multiply(BigDecimal.valueOf(1L << doublings));
        }

        private static BigDecimal lnOf(BigDecimal x) {
            // x = m 2^k with m in [1,2): ln x = k ln 2 + 2 atanh((m-1)/(m+1))
            int k = 0;
            BigDecimal m = x;
            final BigDecimal two = BigDecimal.valueOf(2);
            while (m.compareTo(two) >= 0) {
                m = m.divide(two, MC);
                k++;
            }
            while (m.compareTo(BigDecimal.ONE) < 0) {
                m = m.multiply(two);
                k--;
            }
            return atanhSeries(m.subtract(BigDecimal.ONE).divide(m.add(BigDecimal.ONE), MC)).multiply(two)
                .add(ln2().multiply(BigDecimal.valueOf(k)), MC);
        }

        private static BigDecimal ln2;

        private static synchronized BigDecimal ln2() {
            if (ln2 == null) ln2 = atanhSeries(BigDecimal.ONE.divide(BigDecimal.valueOf(3), MC)).multiply(BigDecimal.valueOf(2));
            return ln2;
        }

        private static BigDecimal atanhSeries(BigDecimal x) {
            BigDecimal term = x;
            BigDecimal sum = x;
            final BigDecimal x2 = x.multiply(x, MC);
            for (int i = 1; i < 4000; i++) {
                term = term.multiply(x2, MC);
                final BigDecimal t = term.divide(BigDecimal.valueOf(2L * i + 1), MC);
                sum = sum.add(t, MC);
                if (t.abs().compareTo(BigDecimal.ONE.movePointLeft(70)) < 0) break;
            }
            return sum;
        }

        private static BigDecimal expOf(BigDecimal x) {
            // e^x = (e^(x/2^s))^(2^s)
            int s = 0;
            BigDecimal v = x;
            while (v.abs().compareTo(new BigDecimal("0.01")) > 0) {
                v = v.divide(BigDecimal.valueOf(2), MC);
                s++;
            }
            BigDecimal term = BigDecimal.ONE;
            BigDecimal sum = BigDecimal.ONE;
            for (int i = 1; i < 200; i++) {
                term = term.multiply(v, MC).divide(BigDecimal.valueOf(i), MC);
                sum = sum.add(term, MC);
                if (term.abs().compareTo(BigDecimal.ONE.movePointLeft(75)) < 0) break;
            }
            for (int i = 0; i < s; i++) sum = sum.multiply(sum, MC);
            return sum;
        }
    }
}

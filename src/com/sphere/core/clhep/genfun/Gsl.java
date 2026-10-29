package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.StdStreams;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The special functions of the GNU Scientific Library that GenericFunctions calls (Airy, Bessel, elliptic
 * integrals, Legendre), from this machine's GSL through the FFM API: the very code a CLHEP built here runs.
 * CLHEP needs GSL for these classes and so does the port: without it they throw, saying why. GSL's error
 * handler is turned off, so that an error comes back as a status, which the classes report as CLHEP does,
 * instead of aborting the JVM.
 *
 * <p>The library is the file the property {@code sphere.gsl} names, else the first found: on Windows a
 * libgsl-N.dll on the PATH or in MSYS2 (ucrt64, mingw64, clang64), with its libgslcblas; on Linux
 * libgsl.so; on macOS Homebrew's libgsl.dylib.
 */
public final class Gsl {

    /** GSL_PREC_DOUBLE. */
    private static final int PREC_DOUBLE = 0;

    private static MethodHandle airyAi, airyBi, besselJn, besselYn, besselJnu, besselYnu, besseljl, besselyl;
    private static MethodHandle ellintF, ellintE, ellintP, legendrePl, legendrePlm, legendrePlArray, legendreSphPlmArray;
    private static String description = "";
    private static String failure = "";
    private static final boolean READY;

    /** gsl_sf_result {val, err}, per thread. */
    private static final ThreadLocal<MemorySegment> RESULT = ThreadLocal.withInitial(() -> Arena.global().allocate(16, 8));

    static {
        boolean ok = false;
        try {
            load();
            ok = true;
        } catch (Throwable t) {
            failure = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        READY = ok;
    }

    private Gsl() {
    }

    /** A gsl_sf_result and the status of the call (0 when it succeeded). */
    public record Result(double val, double err, int status) {
    }

    public static boolean available() {
        return READY;
    }

    /** Which GSL serves, or why none does. */
    public static String describe() {
        return READY ? description : "GSL not available (" + failure + ")";
    }

    /** The status a class checks: on an error, CLHEP's warning, then the SIGFPE CLHEP raises, as an exception. */
    static double checked(Result r, String gslName) {
        if (r.status() != 0) {
            StdStreams.cerr().print("Warning, GSL function " + gslName + " return code" + r.status() + "\n");
            throw new ArithmeticException("SIGFPE: GSL function " + gslName + " returned " + r.status());
        }
        return r.val();
    }

    public static Result airyAi(double x) {
        ready();
        try {
            return result((int) airyAi.invokeExact(x, PREC_DOUBLE, RESULT.get()));
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    public static Result airyBi(double x) {
        ready();
        try {
            return result((int) airyBi.invokeExact(x, PREC_DOUBLE, RESULT.get()));
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    public static Result besselJn(int n, double x) {
        return intDouble(besselJn, n, x);
    }

    public static Result besselYn(int n, double x) {
        return intDouble(besselYn, n, x);
    }

    public static Result besseljl(int l, double x) {
        return intDouble(besseljl, l, x);
    }

    public static Result besselyl(int l, double x) {
        return intDouble(besselyl, l, x);
    }

    public static Result legendrePl(int l, double x) {
        return intDouble(legendrePl, l, x);
    }

    public static Result besselJnu(double nu, double x) {
        ready();
        try {
            return result((int) besselJnu.invokeExact(nu, x, RESULT.get()));
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    public static Result besselYnu(double nu, double x) {
        ready();
        try {
            return result((int) besselYnu.invokeExact(nu, x, RESULT.get()));
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    /** The incomplete elliptic integral of the first kind F(phi, k). */
    public static Result ellintF(double phi, double k) {
        ready();
        try {
            return result((int) ellintF.invokeExact(phi, k, PREC_DOUBLE, RESULT.get()));
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    /** The incomplete elliptic integral of the second kind E(phi, k). */
    public static Result ellintE(double phi, double k) {
        ready();
        try {
            return result((int) ellintE.invokeExact(phi, k, PREC_DOUBLE, RESULT.get()));
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    /** The incomplete elliptic integral of the third kind P(phi, k, n). */
    public static Result ellintP(double phi, double k, double n) {
        ready();
        try {
            return result((int) ellintP.invokeExact(phi, k, n, PREC_DOUBLE, RESULT.get()));
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    public static Result legendrePlm(int l, int m, double x) {
        ready();
        try {
            return result((int) legendrePlm.invokeExact(l, m, x, RESULT.get()));
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    /** P_0(x)..P_lmax(x) (gsl_sf_legendre_Pl_array); the status is not looked at, as in CLHEP. */
    public static double[] legendrePlArray(int lmax, double x) {
        ready();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment a = arena.allocate(ValueLayout.JAVA_DOUBLE, Math.max(lmax + 1, 1));
            final int status = (int) legendrePlArray.invokeExact(lmax, x, a);
            return a.toArray(ValueLayout.JAVA_DOUBLE);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    /**
     * The normalized associated Legendre functions of order m, l = m..lmax (gsl_sf_legendre_sphPlm_array),
     * in an array of lmax + 1 as CLHEP sizes it (the last m entries left 0).
     */
    public static double[] legendreSphPlmArray(int lmax, int m, double x) {
        ready();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment a = arena.allocate(ValueLayout.JAVA_DOUBLE, Math.max(lmax + 1, 1));
            final int status = (int) legendreSphPlmArray.invokeExact(lmax, m, x, a);
            return a.toArray(ValueLayout.JAVA_DOUBLE);
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    private static Result intDouble(MethodHandle h, int n, double x) {
        ready();
        try {
            return result((int) h.invokeExact(n, x, RESULT.get()));
        } catch (Throwable t) {
            throw failed(t);
        }
    }

    private static Result result(int status) {
        final MemorySegment r = RESULT.get();
        return new Result(r.get(ValueLayout.JAVA_DOUBLE, 0), r.get(ValueLayout.JAVA_DOUBLE, 8), status);
    }

    private static void ready() {
        if (!READY) {
            throw new UnsupportedOperationException("this function of CLHEP's GenericFunctions needs the GNU Scientific "
                + "Library (GSL), as CLHEP does: " + failure);
        }
    }

    private static RuntimeException failed(Throwable t) {
        return t instanceof RuntimeException r ? r : new IllegalStateException(t);
    }

    private static void load() throws Throwable {
        final Module self = Gsl.class.getModule();
        if (!self.isNativeAccessEnabled()) {
            throw new IllegalCallerException("native access is not enabled for Sphere (run 'java -jar Sphere.jar', "
                + "whose manifest enables it, or add --enable-native-access=" + (self.isNamed() ? self.getName() : "ALL-UNNAMED") + ")");
        }
        final Linker linker = Linker.nativeLinker();
        final Arena arena = Arena.global();
        final SymbolLookup gsl = library(arena);

        final MethodHandle off = linker.downcallHandle(find(gsl, "gsl_set_error_handler_off"), FunctionDescriptor.of(ValueLayout.ADDRESS));
        final MemorySegment previous = (MemorySegment) off.invokeExact();

        final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
        final ValueLayout.OfDouble D = ValueLayout.JAVA_DOUBLE;
        final java.lang.foreign.AddressLayout P = ValueLayout.ADDRESS;
        airyAi = linker.downcallHandle(find(gsl, "gsl_sf_airy_Ai_e"), FunctionDescriptor.of(I, D, I, P));
        airyBi = linker.downcallHandle(find(gsl, "gsl_sf_airy_Bi_e"), FunctionDescriptor.of(I, D, I, P));
        final FunctionDescriptor intDouble = FunctionDescriptor.of(I, I, D, P);
        besselJn = linker.downcallHandle(find(gsl, "gsl_sf_bessel_Jn_e"), intDouble);
        besselYn = linker.downcallHandle(find(gsl, "gsl_sf_bessel_Yn_e"), intDouble);
        besseljl = linker.downcallHandle(find(gsl, "gsl_sf_bessel_jl_e"), intDouble);
        besselyl = linker.downcallHandle(find(gsl, "gsl_sf_bessel_yl_e"), intDouble);
        legendrePl = linker.downcallHandle(find(gsl, "gsl_sf_legendre_Pl_e"), intDouble);
        final FunctionDescriptor doubleDouble = FunctionDescriptor.of(I, D, D, P);
        besselJnu = linker.downcallHandle(find(gsl, "gsl_sf_bessel_Jnu_e"), doubleDouble);
        besselYnu = linker.downcallHandle(find(gsl, "gsl_sf_bessel_Ynu_e"), doubleDouble);
        ellintF = linker.downcallHandle(find(gsl, "gsl_sf_ellint_F_e"), FunctionDescriptor.of(I, D, D, I, P));
        ellintE = linker.downcallHandle(find(gsl, "gsl_sf_ellint_E_e"), FunctionDescriptor.of(I, D, D, I, P));
        ellintP = linker.downcallHandle(find(gsl, "gsl_sf_ellint_P_e"), FunctionDescriptor.of(I, D, D, D, I, P));
        legendrePlm = linker.downcallHandle(find(gsl, "gsl_sf_legendre_Plm_e"), FunctionDescriptor.of(I, I, I, D, P));
        legendrePlArray = linker.downcallHandle(find(gsl, "gsl_sf_legendre_Pl_array"), FunctionDescriptor.of(I, I, D, P));
        legendreSphPlmArray = linker.downcallHandle(find(gsl, "gsl_sf_legendre_sphPlm_array"), FunctionDescriptor.of(I, I, I, D, P));

        String version = "?";
        final Optional<MemorySegment> v = gsl.find("gsl_version");
        if (v.isPresent()) {
            final MemorySegment text = v.get().reinterpret(P.byteSize()).get(P, 0);
            if (!text.equals(MemorySegment.NULL)) version = text.reinterpret(32).getString(0);
        }
        description = "GSL " + version + " (" + description + ")";
    }

    /** The GSL library: the property's file, else the first found where GSL is usually installed. */
    private static SymbolLookup library(Arena arena) throws IOException {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String explicit = System.getProperty("sphere.gsl");
        if (os.contains("win")) {
            final List<Path> dirs = new ArrayList<>();
            if (explicit != null) dirs.add(Path.of(explicit).toAbsolutePath().getParent());
            for (String d : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
                if (!d.isBlank()) dirs.add(Path.of(d.trim()));
            }
            for (String root : new String[] {"C:\\msys64\\ucrt64\\bin", "C:\\msys64\\mingw64\\bin", "C:\\msys64\\clang64\\bin"}) {
                dirs.add(Path.of(root));
            }
            for (Path dir : dirs) {
                final Path lib = explicit != null && dir.equals(dirs.get(0)) ? Path.of(explicit).toAbsolutePath() : newest(dir, "libgsl-");
                if (lib == null || !Files.isRegularFile(lib)) continue;
                // libgsl imports libgslcblas by name: loaded first, the import finds it
                final Path cblas = newest(lib.getParent(), "libgslcblas-");
                if (cblas != null) SymbolLookup.libraryLookup(cblas, arena);
                description = lib.toString();
                return SymbolLookup.libraryLookup(lib, arena);
            }
            throw new UnsupportedOperationException("no libgsl-N.dll on the PATH or in MSYS2 (install it with "
                + "'pacman -S mingw-w64-ucrt-x86_64-gsl', or name it with -Dsphere.gsl=...)");
        }
        final List<String> names = new ArrayList<>();
        if (explicit != null) names.add(explicit);
        if (os.contains("mac") || os.contains("darwin")) {
            names.addAll(List.of("/opt/homebrew/lib/libgsl.dylib", "/usr/local/lib/libgsl.dylib", "libgsl.dylib"));
        } else {
            names.addAll(List.of("libgsl.so", "libgsl.so.28", "libgsl.so.27", "libgsl.so.25", "libgsl.so.23", "libgsl.so.19"));
        }
        RuntimeException last = null;
        for (String n : names) {
            try {
                final SymbolLookup l = n.contains("/") ? SymbolLookup.libraryLookup(Path.of(n), arena) : SymbolLookup.libraryLookup(n, arena);
                description = n;
                return l;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw new UnsupportedOperationException("no GSL library found (" + String.join(", ", names) + "): install GSL "
            + "(libgsl), or name it with -Dsphere.gsl=..." + (last != null ? "; " + last.getMessage() : ""));
    }

    /** The file of dir named prefix + a number + .dll with the largest number, or null. */
    private static Path newest(Path dir, String prefix) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> {
                final String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.startsWith(prefix) && n.endsWith(".dll") && n.substring(prefix.length(), n.length() - 4).matches("\\d+");
            }).max(Comparator.comparingInt(p -> {
                final String n = p.getFileName().toString();
                return Integer.parseInt(n.substring(prefix.length(), n.length() - 4));
            })).orElse(null);
        } catch (java.nio.file.AccessDeniedException e) {
            return null;
        }
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new UnsupportedOperationException("no " + name + " in the GSL library"));
    }
}

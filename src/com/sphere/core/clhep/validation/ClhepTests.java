package com.sphere.core.clhep.validation;

import com.sphere.core.clhep.CMath;
import com.sphere.core.clhep.evaluator.Evaluator;
import com.sphere.core.clhep.genfun.AbsFunction;
import com.sphere.core.clhep.genfun.AbsParameter;
import com.sphere.core.clhep.genfun.Cos;
import com.sphere.core.clhep.genfun.Parameter;
import com.sphere.core.clhep.genfun.Sin;
import com.sphere.core.clhep.geometry.BasicVector3D;
import com.sphere.core.clhep.geometry.BasicVector3DF;
import com.sphere.core.clhep.geometry.Point3D;
import com.sphere.core.clhep.matrix.HepDiagMatrix;
import com.sphere.core.clhep.matrix.HepMatrix;
import com.sphere.core.clhep.matrix.HepSymMatrix;
import com.sphere.core.clhep.matrix.HepVector;
import com.sphere.core.clhep.random.CLib;
import com.sphere.core.clhep.random.DRand48Engine;
import com.sphere.core.clhep.random.DualRand;
import com.sphere.core.clhep.random.HepJamesRandom;
import com.sphere.core.clhep.random.HepRandom;
import com.sphere.core.clhep.random.HepRandomEngine;
import com.sphere.core.clhep.random.Hurd160Engine;
import com.sphere.core.clhep.random.Hurd288Engine;
import com.sphere.core.clhep.random.MTwistEngine;
import com.sphere.core.clhep.random.MixMaxRng;
import com.sphere.core.clhep.random.ProcessState;
import com.sphere.core.clhep.random.RandBinomial;
import com.sphere.core.clhep.random.RandBreitWigner;
import com.sphere.core.clhep.random.RandChiSquare;
import com.sphere.core.clhep.random.RandEngine;
import com.sphere.core.clhep.random.RandExpZiggurat;
import com.sphere.core.clhep.random.RandExponential;
import com.sphere.core.clhep.random.RandFlat;
import com.sphere.core.clhep.random.RandGamma;
import com.sphere.core.clhep.random.RandGauss;
import com.sphere.core.clhep.random.RandGaussQ;
import com.sphere.core.clhep.random.RandGaussZiggurat;
import com.sphere.core.clhep.random.RandPoisson;
import com.sphere.core.clhep.random.RandStudentT;
import com.sphere.core.clhep.random.RanecuEngine;
import com.sphere.core.clhep.random.Ranlux64Engine;
import com.sphere.core.clhep.random.RanluxEngine;
import com.sphere.core.clhep.random.RanluxppEngine;
import com.sphere.core.clhep.random.RanshiEngine;
import com.sphere.core.clhep.random.StatusFiles;
import com.sphere.core.clhep.random.TripleRand;
import com.sphere.core.clhep.units.PhysicalConstants;
import com.sphere.core.clhep.vector.Hep3Vector;
import com.sphere.core.clhep.vector.HepLorentzVector;
import com.sphere.core.clhep.vector.HepRotation;
import com.sphere.core.clhep.vector.HepRotationZ;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.CRand;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * CLHEP's own test programs, ported: each is the C++ main(), line for line, printing what the C++ prints on
 * cout and cerr, writing the files it writes, returning its exit code. The files CLHEP itself saves states in
 * (Config.conf...) are kept in memory ({@link StatusFiles}) and compared as well. The tests that take standard
 * input are given the input recorded with the C++ outputs: CLHEP's own (testThreeVector, testRandDists...),
 * or ours for testRandom (the ENTERs and a choice that run every engine and every distribution) and
 * testEvaluator (a list of expressions and errors). The Matrix, Vector, Random and Geometry tests beyond the
 * first are in {@link MatrixTests}, {@link VectorTests}, {@link RandomTests} and {@link GeometryTests}.
 */
public final class ClhepTests {

    /** What a test program sees and leaves: its standard input, cout and cerr, the files it writes. */
    public static final class Io {
        final Program program;
        /** Whether the C library is glibc's (its assert() messages, 64-bit long...), MinGW's UCRT otherwise. */
        final boolean glibc;
        final StringBuilder out = new StringBuilder();
        final StringBuilder err = new StringBuilder();
        final COStream cout = new COStream(out);
        final COStream cerr = new COStream(err);
        /** The files written, by name: the test's own and those CLHEP saves states in ({@link StatusFiles}). */
        final Map<String, StringBuilder> files = new LinkedHashMap<>();
        private final String input;
        private int at;
        private boolean failed;
        private CIStream cin;

        Io(Program program, String input, boolean glibc) {
            this.program = program;
            this.glibc = glibc;
            this.input = input == null ? "" : input;
        }

        /** std::cin for the formatted reads (cin &gt;&gt; v): the input that get() and getLine() have not taken. */
        CIStream cin() {
            if (cin == null) cin = new CIStream(input.substring(at));
            return cin;
        }

        /** std::ofstream(name): the file made empty, a stream writing it. */
        COStream file(String name) {
            final StringBuilder b = new StringBuilder();
            files.put(name, b);
            return new COStream(b);
        }

        /** std::ifstream(name): the file's text, a stream that fails at once when there is no such file. */
        CIStream ifstream(String name) {
            final StringBuilder b = files.get(name);
            if (b != null) return new CIStream(b.toString());
            final CIStream none = new CIStream("");
            none.setFail();
            return none;
        }

        /** cin.get(): the next character, -1 at the end. */
        int get() {
            if (at >= input.length()) {
                failed = true;
                return -1;
            }
            return input.charAt(at++);
        }

        /** cin >> c: the next character that is not white space, -1 at the end. */
        int nextChar() {
            while (at < input.length() && Character.isWhitespace(input.charAt(at))) at++;
            return get();
        }

        /** cin.get(buffer, n): up to n-1 characters before the end of the line, not extracted; null (failbit) when none. */
        String getLine(int n) {
            if (failed) return null;
            final int start = at;
            while (at < input.length() && input.charAt(at) != '\n' && at - start < n - 1) at++;
            if (at == start) {
                failed = true;
                return null;
            }
            return input.substring(start, at);
        }

        /** cin.ignore(): one character dropped. */
        void ignore() {
            if (!failed && at < input.length()) at++;
        }
    }

    /** exit(code), or an assertion that failed. */
    static final class Exit extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;

        Exit(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    /** assert(expression) on that line of main(). */
    static void check(Io io, boolean ok, String expression, int line) {
        check(io, ok, expression, line, "int main()");
    }

    /**
     * assert(expression) on that line of a function, as the C library fails it. MinGW's UCRT writes "Assertion
     * failed: expression, file F, line L", glibc "program: F:L: function: Assertion `expression' failed.", F the
     * test's source as make_refs.sh compiles it (from the root of CLHEP's tree). Then abort(), its status as bash
     * gives it: 127 for the UCRT's fast fail (0xC0000409), 134 for glibc's SIGABRT.
     */
    static void check(Io io, boolean ok, String expression, int line, String function) {
        if (ok) return;
        final String file = io.program.pkg() + "/test/" + io.program.id() + ".cc";
        io.cerr.put(io.glibc ? io.program.id() + ": " + file + ":" + line + ": " + function + ": Assertion `" + expression + "' failed.\n"
            : "Assertion failed: " + expression + ", file " + file + ", line " + line + "\n");
        throw new Exit(io.glibc ? 134 : 127);
    }

    /** cout &lt;&lt; x &lt;&lt; y...: text, numbers, and the matrices and vectors as their operator&lt;&lt; prints them. */
    static COStream say(COStream os, Object... xs) {
        for (Object x : xs) {
            switch (x) {
                case String s -> os.put(s);
                case Integer i -> os.put(i.intValue());
                case Long l -> os.put(l.longValue());
                case Double d -> os.put(d.doubleValue());
                case HepMatrix m -> m.print(os);
                case HepSymMatrix m -> m.print(os);
                case HepDiagMatrix m -> m.print(os);
                case HepVector v -> v.print(os);
                case Hep3Vector v -> v.print(os);
                case HepLorentzVector v -> v.print(os);
                case BasicVector3D v -> v.print(os);
                case BasicVector3DF v -> v.print(os);
                default -> throw new IllegalArgumentException("cout << " + x.getClass().getName());
            }
        }
        return os;
    }

    /** A test: its name, its package, the system whose outputs it is compared with, its main(). */
    record Program(String id, String pkg, boolean linux, ToIntFunction<Io> main) {
    }

    static final Map<String, Program> PROGRAMS = new LinkedHashMap<>();

    static {
        add(new Program("testBug6740", "Geometry", false, ClhepTests::testBug6740));
        add(new Program("testBasicVector3D", "Geometry", false, GeometryTests::testBasicVector3D));
        add(new Program("testBug104262", "Matrix", false, ClhepTests::testBug104262));
        add(new Program("testBug6176", "Matrix", false, ClhepTests::testBug6176));
        add(new Program("testBug6181", "Matrix", false, MatrixTests::testBug6181));
        add(new Program("testMatrix", "Matrix", false, MatrixTests::testMatrix));
        add(new Program("testInversion", "Matrix", false, MatrixTests::testInversion));
        add(new Program("testEvaluator", "Evaluator", false, ClhepTests::testEvaluator));
        add(new Program("testGenericFunctions", "GenericFunctions", false, ClhepTests::testGenericFunctions));
        add(new Program("testUnits", "Units", false, ClhepTests::testUnits));
        add(new Program("testXF", "Fields", false, io -> 0));
        add(new Program("testCLHEP155", "Random", false, ClhepTests::testCLHEP155));
        add(new Program("eulerProb", "Vector", false, ClhepTests::eulerProb));
        add(new Program("testThreeVector", "Vector", false, VectorTests::testThreeVector));
        add(new Program("testLorentzVector", "Vector", false, VectorTests::testLorentzVector));
        add(new Program("testRotation", "Vector", false, VectorTests::testRotation));
        add(new Program("testRotationAxis", "Vector", false, VectorTests::testRotationAxis));
        add(new Program("testSubscripts", "Vector", false, VectorTests::testSubscripts));
        add(new Program("testBug73093", "Random", true, ClhepTests::testBug73093));
        add(new Program("testRandom", "Random", true, ClhepTests::testRandom));
        add(new Program("testZiggurat", "Random", false, ClhepTests::testZiggurat));
        add(new Program("testSaveEngineStatus", "Random", false, RandomTests::testSaveEngineStatus));
        add(new Program("testSaveSharedEngines", "Random", false, RandomTests::testSaveSharedEngines));
        add(new Program("testStaticStreamSave", "Random", false, RandomTests::testStaticStreamSave));
        add(new Program("testInstanceRestore", "Random", true, RandomTests::testInstanceRestore));
        add(new Program("testAnonymousEngineRestore", "Random", false, RandomTests::testAnonymousEngineRestore));
        add(new Program("testVectorSave", "Random", true, RandomTests::testVectorSave));
        add(new Program("testEngineCopy", "Random", true, RandomTests::testEngineCopy));
        add(new Program("testRanecuSequence", "Random", false, RandomTests::testRanecuSequence));
        add(new Program("testDistCopy", "Random", false, RandomTests::testDistCopy));
        add(new Program("testRandDists", "Random", false, RandomTests::testRandDists));
        add(new Program("testVectorDists", "RandomObjects", false, RandomTests::testVectorDists));
    }

    private static void add(Program p) {
        PROGRAMS.put(p.id, p);
    }

    /** The tests too long to run with the others, and why: ':clhep validate' runs them when it is told to. */
    static final Map<String, String> LONG = Map.of("testRotationAxis", "130 million rotations, natively 13 s");

    private ClhepTests() {
    }

    /** What a test printed, its exit code and the files it wrote. */
    public record Result(String out, String err, int exit, Map<String, String> files) {
    }

    /** Runs a test as a fresh C++ process would run, its rand() glibc's when it is compared with Linux's outputs. */
    public static Result run(String id, String input) {
        final Program p = PROGRAMS.get(id);
        ProcessState.restart(p.linux ? CRand.Library.GLIBC : CLib.defaultRandLibrary());
        HepSymMatrix.resetThread();
        final Io io = new Io(p, input, p.linux || "linux".equals(ClhepValidation.system()));
        final StdStreams streams = new StdStreams(line -> io.out.append(line).append('\n'), line -> io.err.append(line).append('\n'));
        int code;
        try {
            code = StdStreams.with(streams, () -> StatusFiles.inMemory(io.files, () -> p.main.applyAsInt(io)));
        } catch (Exit e) {
            code = e.code;
        } catch (Exception e) {
            io.err.append("Java: ").append(e).append('\n');
            code = -1;
        }
        final Map<String, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> f : io.files.entrySet()) files.put(f.getKey(), f.getValue().toString());
        ProcessState.restart();
        return new Result(io.out.toString(), io.err.toString(), code, files);
    }

    /* ---- Geometry ---- */

    static int testBug6740(Io io) {
        final Hep3Vector d = new Hep3Vector(10, 0, 0);
        final Point3D a = new Point3D(0, 0, 0);
        final Point3D pv = new Point3D(1, 1, 1);
        final float expected = 1.41421f;
        int numbad = 0;
        final Hep3Vector dist = a.minus(pv).toHep3Vector();
        final Hep3Vector cross = dist.cross(d.unit());
        io.cout.put(cross.mag()).put("\n");
        final float result = (float) cross.mag();
        final float eps = 1.0e-5f;
        if (Math.abs(result - expected) > eps) {
            ++numbad;
            io.cout.put("Expected ").put(expected).put(" got ").put(result).put("\n");
        }
        return numbad;
    }

    /* ---- Matrix ---- */

    static int testBug104262(Io io) {
        HepVector fXVector = new HepVector();
        fXVector = new HepVector(32);
        io.cout.put("fXVector, 1, 2 : ").put(fXVector.get(1)).put(", ").put(fXVector.get(2)).put("\n");
        fXVector.set(1, 99);
        fXVector.set(31, 199);
        io.cout.put("fXVector, 1, 31 : ").put(fXVector.get(1)).put(", ").put(fXVector.get(31)).put("\n");
        return 0;
    }

    static int testBug6176(Io io) {
        final HepMatrix mtr = new HepMatrix(7, 7, 0);
        for (int i = 1; i < 8; ++i) {
            for (int j = 1; j < 8; ++j) {
                if (i <= j) {
                    mtr.set(i, j, 10 * i + j);
                    mtr.set(j, i, 10 * j + i);
                }
            }
        }
        mtr.print(io.cout.put("Initial matrix ")).put("\n");
        mtr.sub(1, 4, 1, 4).print(io.cout.put("Sub (1,4,1,4)")).put("\n");
        return 0;
    }

    /* ---- Evaluator ---- */

    static int testEvaluator(Io io) {
        final Evaluator eval = new Evaluator();
        eval.setVariable(" dummy  ", 0.);
        check(io, eval.findVariable("  dummy "), "eval.findVariable(\"  dummy \")", 21);
        eval.clear();
        eval.setStdMath();
        eval.setSystemOfUnits();
        io.cout.precision(15);
        for (;;) {
            io.cout.put("CALC> ");
            final String exp = io.getLine(240);
            io.ignore();
            if (exp == null || exp.equals("exit") || exp.equals("quit")) {
                io.cout.put("\n");
                break;
            }
            final double value = eval.evaluate(exp);
            switch (eval.status()) {
                case Evaluator.OK -> io.cout.put(value).put("\n");
                case Evaluator.WARNING_BLANK_STRING -> {
                    continue;
                }
                default -> {
                    io.cout.put("------");
                    for (int i = 0; i < eval.error_position(); i++) io.cout.put("-");
                    io.cout.put("^").put("\n");
                    eval.print_error();
                }
            }
            io.cout.put("\n");
        }
        return 0;
    }

    /* ---- GenericFunctions ---- */

    static int testGenericFunctions(Io io) {
        final double FLT_EPSILON = Math.ulp(1.0f);
        final AbsFunction f = new Sin();
        final AbsFunction g = new Cos();
        final AbsParameter p = new Parameter("Parameter", 4.0);
        for (double x = 0; x < 100; x++) {
            check(io, Math.abs(f.times(g).call(x) - (CMath.sin(x) * CMath.cos(x))) <= FLT_EPSILON, "fabs((f*g) (x) - (sin(x)*cos(x))) <= FLT_EPSILON", 21);
            check(io, Math.abs(f.plus(g).call(x) - (CMath.sin(x) + CMath.cos(x))) <= FLT_EPSILON, "fabs((f+g) (x) - (sin(x)+cos(x))) <= FLT_EPSILON", 22);
            check(io, Math.abs(f.minus(g).call(x) - (CMath.sin(x) - CMath.cos(x))) <= FLT_EPSILON, "fabs((f-g) (x) - (sin(x)-cos(x))) <= FLT_EPSILON", 23);
            check(io, Math.abs(f.div(g).call(x) - (CMath.sin(x) / CMath.cos(x))) <= FLT_EPSILON, "fabs((f/g) (x) - (sin(x)/cos(x))) <= FLT_EPSILON", 24);
            check(io, Math.abs(p.times(f).call(x) - 4.0 * CMath.sin(x)) <= FLT_EPSILON, "fabs((p*f)(x)- 4.0*sin(x)) <= FLT_EPSILON", 28);
            check(io, Math.abs(f.times(p).call(x) - 4.0 * CMath.sin(x)) <= FLT_EPSILON, "fabs((f*p)(x)- 4.0*sin(x)) <= FLT_EPSILON", 29);
            check(io, Math.abs(f.times(4.0).call(x) - 4.0 * CMath.sin(x)) <= FLT_EPSILON, "fabs((f*4.0)(x)- 4.0*sin(x)) <= FLT_EPSILON", 33);
            check(io, Math.abs(AbsFunction.times(4.0, f).call(x) - 4.0 * CMath.sin(x)) <= FLT_EPSILON, "fabs((4.0*f)(x)- 4.0*sin(x)) <= FLT_EPSILON", 34);
            check(io, Math.abs(f.of(g).call(x) - CMath.sin(CMath.cos(x))) <= FLT_EPSILON, "fabs(((f(g))(x)) - sin(cos(x))) <= FLT_EPSILON", 37);
        }
        check(io, Math.abs(p.times(p).getValue() - 4.0 * 4.0) <= FLT_EPSILON, "fabs((p*p).getValue()- 4.0*4.0) <= FLT_EPSILON", 43);
        check(io, Math.abs(AbsParameter.times(4, p).getValue() - 4.0 * 4.0) <= FLT_EPSILON, "fabs((4*p).getValue()- 4.0*4.0) <= FLT_EPSILON", 46);
        check(io, Math.abs(p.times(4).getValue() - 4.0 * 4.0) <= FLT_EPSILON, "fabs((p*4).getValue()- 4.0*4.0) <= FLT_EPSILON", 47);
        io.cout.put("GenericFunctions autotest has passed").put("\n");
        return 0;
    }

    /* ---- Units ---- */

    static int testUnits(Io io) {
        int nbad = 0;
        double m = 0;
        for (int i = 0; i < 7; i++, m++) check(io, m == i, "m == double(i)", 31);
        check(io, m != PhysicalConstants.meter, "m != CLHEP::meter", 32);
        final COStream os1 = new COStream();
        final COStream os3 = new COStream();
        os1.setprecision(8).put(PhysicalConstants.c_light);
        final String os2 = "299.79246";
        if (!os1.str().equals(os2)) {
            io.cout.put("compare --").put(os1.str()).put("-- to --").put(os2).put("--").put("\n");
            nbad++;
        }
        os3.setprecision(16).put(PhysicalConstants.pi);
        final String os4 = "3.141592653589793";
        if (!os3.str().equals(os4)) {
            nbad++;
            io.cout.put("compare --").put(os3.str()).put("--").put("\n");
            io.cout.put("     to --").put(os4).put("--").put("\n");
            io.cout.put("M_PI    --").setprecision(16).put(Math.PI).put("--").put("\n");
        }
        return nbad;
    }

    /* ---- Random ---- */

    static int testCLHEP155(Io io) {
        try {
            new MixMaxRng(0);
            return 1;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** As the C++ built for 64-bit long (Linux), the system its outputs are compared with. */
    static int testBug73093(Io io) {
        int bad = 0;
        {
            final COStream output = io.file("testBug73093.cout");
            long seed;
            long mult = -235421;
            for (int il = 0; il < 100; ++il) {
                seed = 9899876543210000L;
                mult = mult + 120034020050070L;
                seed += il * mult;
                final Ranlux64Engine rng = new Ranlux64Engine();
                final long N = 20;
                rng.setSeed(seed, 1);
                output.put("\n");
                output.put("sizeof(long) = ").put(8).put("\n");
                output.put("Generating ").put(N).put(" random numbers with seed ").put(seed).put("\n");
                output.put("Using seed ").put(seed).put("\n");
                double sum = 0;
                for (long i = 0; i < N; ++i) {
                    final double r = rng.flat();
                    if (Math.abs(r) > 1.0) ++bad;
                    output.put(r).put("\n");
                    sum += r;
                }
                output.put("Sum: ").put(sum).put("\n");
                output.put("Average: ").put(sum / N).put("\n");
            }
        }
        {
            final COStream output = io.file("testBug73093.seq");
            long seed;
            final long mult = -235421;
            for (int il = 0; il < 50; ++il) {
                seed = 97654321;
                seed += il * mult;
                final Ranlux64Engine rng = new Ranlux64Engine();
                final long N = 20;
                rng.setSeed(seed, 1);
                for (long i = 0; i < N; ++i) {
                    final double r = rng.flat();
                    if (Math.abs(r) > 1.0) ++bad;
                    output.put("[").put(il).put("][").put(i).put("] = ").put(r).put(";").put("\n");
                }
            }
        }
        return bad;
    }

    static int testZiggurat(Io io) {
        final COStream output = io.file("testZiggurat.cout");
        final int ntest = 10000000;
        output.put("DEBUG: ntest=").put(ntest).put("\n");
        double sumRnd1 = 0;
        for (int i = 0; i < ntest; ++i) sumRnd1 += RandGauss.shoot();
        sumRnd1 /= ntest;
        output.put("DEBUG: avg RandGauss=").put(sumRnd1).put("\n");
        double sumRnd2 = 0;
        for (int i = 0; i < ntest; ++i) sumRnd2 += RandGaussQ.shoot();
        sumRnd2 /= ntest;
        output.put("DEBUG: avg RandGaussQ=").put(sumRnd2).put("\n");
        double sumZig = 0;
        for (int i = 0; i < ntest; ++i) sumZig += RandGaussZiggurat.shoot();
        sumZig /= ntest;
        output.put("DEBUG: avg RandGaussZiggurat=").put(sumZig).put("\n");
        double sumExp = 0;
        for (int i = 0; i < ntest; ++i) sumExp += RandExponential.shoot();
        sumExp /= ntest;
        output.put("DEBUG: avg RandExponential=").put(sumExp).put("\n");
        double sumExpZ = 0;
        for (int i = 0; i < ntest; ++i) sumExpZ += RandExpZiggurat.shoot();
        sumExpZ /= ntest;
        output.put("DEBUG: avg RandExpZiggurat=").put(sumExpZ).put("\n");
        return 0;
    }

    /** testRandom's global engines, constructed before main() in the C++'s order. */
    private static final class RandomGlobals {
        final HepJamesRandom theJamesEngine = new HepJamesRandom();
        final RandEngine theRandEngine = new RandEngine();
        final DRand48Engine theDRand48Engine = new DRand48Engine();
        final RanluxEngine theRanluxEngine = new RanluxEngine(19780503, 4);
        final Ranlux64Engine theRanlux64Engine = new Ranlux64Engine();
        final RanluxppEngine theRanluxppEngine = new RanluxppEngine();
        final RanecuEngine theRanecuEngine = new RanecuEngine();
        final Hurd160Engine theHurd160Engine = new Hurd160Engine();
        final Hurd288Engine theHurd288Engine = new Hurd288Engine();
        final MixMaxRng theMixMaxEngine = new MixMaxRng();
        final MTwistEngine theMTwistEngine = new MTwistEngine();
        final RanshiEngine theRanshiEngine = new RanshiEngine();
        final DualRand theDualRandEngine = new DualRand();
        final TripleRand theTripleRandEngine = new TripleRand();
    }

    private static final String PRESS = "                   -----  Press <ENTER> to continue  -----";

    static int testRandom(Io io) {
        // Randomize.h's HepRandomGenActive makes the default engine during static initialization, before the globals
        HepRandom.createInstance();
        final RandomGlobals g = new RandomGlobals();
        final COStream cout = io.cout;
        // init()
        final HepRandom r = new HepRandom();
        cout.put("r=").put(r.call()).put("\n");
        cout.put("\n").put("\n");
        cout.put("---------------------------- Random shooting test -----------------------------").put("\n");
        cout.put("                             --------------------                              ").put("\n");
        cout.put(" >>> Random Engines available <<<").put("\n").put("\n");
        for (String e : new String[]{"HepJamesRandom (default)", "Rand", "DRand48", "Ranlux", "Ranlux64", "Ranluxpp", "Ranecu",
            "Hurd160", "Hurd288", "MixMax", "MTwist", "Ranshi", "DualRand"}) {
            cout.put("   > ").put(e).put("\n");
        }
        cout.put("   > TripleRand").put("\n").put("\n");
        cout.put(PRESS);
        if (io.get() != '\n') throw new Exit(0);
        cout.put("\n");
        // start_test()
        final Object[][] tests = {
            {"-------------------------  Test on HepJamesRandom  ----------------------------", null},
            {"---------------------------  Test on RandEngine  ------------------------------", g.theRandEngine},
            {"-------------------------  Test on DRand48Engine  -----------------------------", g.theDRand48Engine},
            {"---------------------  Test on RanluxEngine (luxury 4) ------------------------", g.theRanluxEngine},
            {"-------------------------  Test on Ranlux64Engine -----------------------------", g.theRanlux64Engine},
            {"-------------------------  Test on RanluxppEngine -----------------------------", g.theRanluxppEngine},
            {"--------------------------  Test on RanecuEngine ------------------------------", g.theRanecuEngine},
            {"-------------------------  Test on Hurd160Engine ------------------------------", g.theHurd160Engine},
            {"-------------------------  Test on Hurd288Engine ------------------------------", g.theHurd288Engine},
            {"--------------------------  Test on MixMaxEngine ------------------------------", g.theMixMaxEngine},
            {"--------------------------  Test on MTwistEngine ------------------------------", g.theMTwistEngine},
            {"--------------------------  Test on RanshiEngine ------------------------------", g.theRanshiEngine},
            {"-------------------------  Test on DualRandEngine -----------------------------", g.theDualRandEngine},
            {"------------------------  Test on TripleRandEngine ----------------------------", g.theTripleRandEngine}};
        for (int t = 0; t < tests.length; t++) {
            if (t > 0) {
                cout.put(PRESS);
                if (io.get() != '\n') throw new Exit(0);
                cout.put("\n");
            }
            cout.put((String) tests[t][0]).put("\n");
            cout.put("\n");
            if (tests[t][1] != null) HepRandom.setTheEngine((HepRandomEngine) tests[t][1]);
            layout(cout);
        }
        distLayout(io, cout);
        userLayout(io, cout, g);
        return 0;
    }

    private static void layout(COStream cout) {
        final float m1 = 3.0f;
        final int size = 5;
        final double[] vect = new double[size];
        cout.put(" Flat ]0,1[          : ").put(RandFlat.shoot()).put("\n");
        cout.put(" Flat ]0,5[          : ").put(RandFlat.shoot(5)).put("\n");
        cout.put(" Flat ]-5,3[         : ").put(RandFlat.shoot(-5, 3)).put("\n");
        cout.put(" Exp (m=1)           : ").put(RandExponential.shoot()).put("\n");
        cout.put(" Exp (m=3)           : ").put(RandExponential.shoot(3)).put("\n");
        cout.put(" Gauss (m=1)         : ").put(RandGauss.shoot()).put("\n");
        cout.put(" Gauss (m=3,v=1)     : ").put(RandGauss.shoot(3, 1)).put("\n");
        cout.put(" Wigner(1,0.2)       : ").put(RandBreitWigner.shoot(1, 0.2)).put("\n");
        cout.put(" Wigner(1,0.2,1)     : ").put(RandBreitWigner.shoot(1, 0.2, 1)).put("\n");
        cout.put(" Wigner2(1,0.2)      : ").put(RandBreitWigner.shootM2(1, 0.2)).put("\n");
        cout.put(" Wigner2(1,0.2,1)    : ").put(RandBreitWigner.shootM2(1, 0.2, 1)).put("\n");
        cout.put(" IntFlat [0,99[      : ").put(RandFlat.shootInt(99)).put("\n");
        cout.put(" IntFlat [-99,37[    : ").put(RandFlat.shootInt(-99, 37)).put("\n");
        cout.put(" Poisson (m=3.0)     : ").put(RandPoisson.shoot(m1)).put("\n");
        cout.put(" Binomial(n=1,p=0.5) : ").put(RandBinomial.shoot()).put("\n");
        cout.put(" Binomial(n=-5,p=0.3): ").put(RandBinomial.shoot(-5, 0.3)).put("\n");
        cout.put(" ChiSqr (a=1)        : ").put(RandChiSquare.shoot()).put("\n");
        cout.put(" ChiSqr (a=-5)       : ").put(RandChiSquare.shoot(-5)).put("\n");
        cout.put(" Gamma (k=1,l=1)     : ").put(RandGamma.shoot()).put("\n");
        cout.put(" Gamma (k=3,l=0.5)   : ").put(RandGamma.shoot(3, 0.5)).put("\n");
        cout.put(" StudT (a=1)         : ").put(RandStudentT.shoot()).put("\n");
        cout.put(" StudT (a=2.5)       : ").put(RandStudentT.shoot(2.5)).put("\n");
        cout.put("\n");
        cout.put(" Shooting an array of 5 flat numbers ...").put("\n").put("\n");
        RandFlat.shootArray(size, vect);
        for (int i = 0; i < size; ++i) cout.put(" ").put(vect[i]);
        cout.put("\n").put("\n");
    }

    private static void distLayout(Io io, COStream cout) {
        final float m1 = 3.0f;
        final int size = 5;
        final double[] vect = new double[size];
        final HepJamesRandom aJamesEngine = new HepJamesRandom();
        final RandEngine aRandEngine = new RandEngine();
        final DRand48Engine aDRand48Engine = new DRand48Engine();
        final RanluxEngine aRanluxEngine = new RanluxEngine(19780503, 4);
        new Ranlux64Engine();
        new RanluxppEngine();
        final RanecuEngine aRanecuEngine = new RanecuEngine();
        final Hurd288Engine aHurd288Engine = new Hurd288Engine();
        final MixMaxRng aMixMaxEngine = new MixMaxRng();
        final MTwistEngine aMTwistEngine = new MTwistEngine();
        final RanshiEngine aRanshiEngine = new RanshiEngine();
        final TripleRand aTripleRandEngine = new TripleRand();
        final RandFlat aFlatObj = new RandFlat(aJamesEngine);
        final RandExponential anExponentialObj = new RandExponential(aRandEngine);
        final RandExpZiggurat anExpZigguratObj = new RandExpZiggurat(aMixMaxEngine);
        final RandGauss aGaussObj = new RandGauss(aDRand48Engine);
        final RandBreitWigner aBreitObj = new RandBreitWigner(aRanluxEngine);
        final RandPoisson aPoissonObj = new RandPoisson(aRanecuEngine);
        final RandBinomial aBinomialObj = new RandBinomial(aHurd288Engine);
        final RandChiSquare aChiSquareObj = new RandChiSquare(aMTwistEngine);
        final RandGamma aGammaObj = new RandGamma(aRanshiEngine);
        final RandStudentT aStudentTObj = new RandStudentT(aTripleRandEngine);
        cout.put(PRESS);
        if (io.get() != '\n') throw new Exit(0);
        cout.put("\n").put("\n");
        cout.put("-------------------- Shooting test on distribution objects --------------------").put("\n");
        cout.put("\n");
        cout.put(" Flat ]0,1[          : ").put(aFlatObj.fire()).put("\n");
        cout.put(" Flat ]0,5[          : ").put(aFlatObj.fire(5)).put("\n");
        cout.put(" Flat ]-5,3[         : ").put(aFlatObj.fire(-5, 3)).put("\n");
        cout.put(" Exp (m=1)           : ").put(anExponentialObj.fire()).put("\n");
        cout.put(" Exp (m=3)           : ").put(anExponentialObj.fire(3)).put("\n");
        cout.put(" ExpZig (m=1)        : ").put(anExpZigguratObj.fire()).put("\n");
        cout.put(" ExpZig (m=3)        : ").put(anExpZigguratObj.fire(3)).put("\n");
        cout.put(" Gauss (m=1)         : ").put(aGaussObj.fire()).put("\n");
        cout.put(" Gauss (m=3,v=1)     : ").put(aGaussObj.fire(3, 1)).put("\n");
        cout.put(" Wigner(1,0.2)       : ").put(aBreitObj.fire(1, 0.2)).put("\n");
        cout.put(" Wigner(1,0.2,1)     : ").put(aBreitObj.fire(1, 0.2, 1)).put("\n");
        cout.put(" Wigner2(1,0.2)      : ").put(aBreitObj.fireM2(1, 0.2)).put("\n");
        cout.put(" Wigner2(1,0.2,1)    : ").put(aBreitObj.fireM2(1, 0.2, 1)).put("\n");
        cout.put(" IntFlat [0,99[      : ").put(aFlatObj.fireInt(99)).put("\n");
        cout.put(" IntFlat [-99,37[    : ").put(aFlatObj.fireInt(-99, 37)).put("\n");
        cout.put(" Poisson (m=3.0)     : ").put(aPoissonObj.fire(m1)).put("\n");
        cout.put(" Binomial(n=1,p=0.5) : ").put(aBinomialObj.fire()).put("\n");
        cout.put(" Binomial(n=-5,p=0.3): ").put(aBinomialObj.fire(-5, 0.3)).put("\n");
        cout.put(" ChiSqr (a=1)        : ").put(aChiSquareObj.fire()).put("\n");
        cout.put(" ChiSqr (a=-5)       : ").put(aChiSquareObj.fire(-5)).put("\n");
        cout.put(" Gamma (k=1,l=1)     : ").put(aGammaObj.fire()).put("\n");
        cout.put(" Gamma (k=3,l=0.5)   : ").put(aGammaObj.fire(3, 0.5)).put("\n");
        cout.put(" StudT (a=1)         : ").put(aStudentTObj.fire()).put("\n");
        cout.put(" StudT (a=2.5)       : ").put(aStudentTObj.fire(2.5)).put("\n");
        cout.put("\n");
        cout.put(" Shooting an array of 5 flat numbers ...").put("\n").put("\n");
        aFlatObj.fireArray(size, vect);
        for (int i = 0; i < size; ++i) cout.put(" ").put(vect[i]);
        cout.put("\n").put("\n");
        cout.put(PRESS);
        if (io.get() != '\n') throw new Exit(0);
    }

    private static void userLayout(Io io, COStream cout, RandomGlobals g) {
        final float m1 = 3.0f;
        final int size = 5;
        final double[] vect = new double[size];
        cout.put("\n").put("\n");
        cout.put("-------------------- Shooting test skeeping the generator ---------------------").put("\n");
        cout.put("\n");
        cout.put(" >>> Select a Random Engine <<<").put("\n").put("\n");
        final String[] menu = {"a. HepJamesRandom (default)", "b. Rand", "c. DRand48", "d. Ranlux", "e. Ranlux64", "f. Ranecu",
            "g. Hurd160", "h. Hurd288", "i. MTwist", "j. Ranshi", "k. DualRand", "l. TripleRand"};
        for (String m : menu) cout.put("   ").put(m).put("\n");
        cout.put("   m. MixMax").put("\n").put("\n");
        cout.put(" > ");
        int sel = io.nextChar();
        while (sel < 'a' || sel > 'm') {
            cout.put("\n").put(" >>> Choice not legal !!  [a..m]<<<").put("\n");
            sel = io.nextChar();
            if (sel < 0) throw new Exit(0);
        }
        final HepRandomEngine anEngine = switch (sel) {
            case 'b' -> g.theRandEngine;
            case 'c' -> g.theDRand48Engine;
            case 'd' -> g.theRanluxEngine;
            case 'e' -> g.theRanlux64Engine;
            case 'f' -> g.theRanecuEngine;
            case 'g' -> g.theHurd160Engine;
            case 'h' -> g.theHurd288Engine;
            case 'i' -> g.theMTwistEngine;
            case 'j' -> g.theRanshiEngine;
            case 'k' -> g.theDualRandEngine;
            case 'l' -> g.theTripleRandEngine;
            case 'm' -> g.theMixMaxEngine;
            default -> g.theJamesEngine;
        };
        cout.put("\n");
        cout.put(" Flat ]0,1[          : ").put(RandFlat.shoot(anEngine)).put("\n");
        cout.put(" Flat ]0,5[          : ").put(RandFlat.shoot(anEngine, 5)).put("\n");
        cout.put(" Flat ]-5,3[         : ").put(RandFlat.shoot(anEngine, -5, 3)).put("\n");
        cout.put(" Exp (m=1)           : ").put(RandExponential.shoot(anEngine)).put("\n");
        cout.put(" Exp (m=3)           : ").put(RandExponential.shoot(anEngine, 3)).put("\n");
        cout.put(" Gauss (m=1)         : ").put(RandGauss.shoot(anEngine)).put("\n");
        cout.put(" Gauss (m=3,v=1)     : ").put(RandGauss.shoot(anEngine, 3, 1)).put("\n");
        cout.put(" Wigner(1,0.2)       : ").put(RandBreitWigner.shoot(anEngine, 1, 0.2)).put("\n");
        cout.put(" Wigner(1,0.2,1)     : ").put(RandBreitWigner.shoot(anEngine, 1, 0.2, 1)).put("\n");
        cout.put(" Wigner2(1,0.2)      : ").put(RandBreitWigner.shootM2(anEngine, 1, 0.2)).put("\n");
        cout.put(" Wigner2(1,0.2,1)    : ").put(RandBreitWigner.shootM2(anEngine, 1, 0.2, 1)).put("\n");
        cout.put(" IntFlat [0,99[      : ").put(RandFlat.shootInt(anEngine, 99)).put("\n");
        cout.put(" IntFlat [-99,37[    : ").put(RandFlat.shootInt(anEngine, -99, 37)).put("\n");
        cout.put(" Poisson (m=3.0)     : ").put(RandPoisson.shoot(anEngine, m1)).put("\n");
        cout.put(" Binomial(n=1,p=0.5) : ").put(RandBinomial.shoot(anEngine)).put("\n");
        cout.put(" Binomial(n=-5,p=0.3): ").put(RandBinomial.shoot(anEngine, -5, 0.3)).put("\n");
        cout.put(" ChiSqr (a=1)        : ").put(RandChiSquare.shoot(anEngine)).put("\n");
        cout.put(" ChiSqr (a=-5)       : ").put(RandChiSquare.shoot(anEngine, -5)).put("\n");
        cout.put(" Gamma (k=1,l=1)     : ").put(RandGamma.shoot(anEngine)).put("\n");
        cout.put(" Gamma (k=3,l=0.5)   : ").put(RandGamma.shoot(anEngine, 3, 0.5)).put("\n");
        cout.put(" StudT (a=1)         : ").put(RandStudentT.shoot(anEngine)).put("\n");
        cout.put(" StudT (a=2.5)       : ").put(RandStudentT.shoot(anEngine, 2.5)).put("\n");
        cout.put("\n");
        cout.put(" Shooting an array of 5 flat numbers ...").put("\n").put("\n");
        RandFlat.shootArray(anEngine, size, vect);
        for (int i = 0; i < size; ++i) cout.put(" ").put(vect[i]);
        cout.put("\n").put("\n");
    }

    /* ---- Vector ---- */

    /** eulerProb's myRotClass: a HepRotation whose elements are set one by one. */
    private static final class MyRotClass extends HepRotation {
        MyRotClass(HepRotationZ rot) {
            super(rot);
        }

        void setXX(double v) {
            rxx = v;
        }

        void setXY(double v) {
            rxy = v;
        }

        void setYX(double v) {
            ryx = v;
        }

        void setYY(double v) {
            ryy = v;
        }

        void setZZ(double v) {
            rzz = v;
        }
    }

    static int eulerProb(Io io) {
        final COStream cout = io.cout;
        final HepRotationZ az = new HepRotationZ(120 * PhysicalConstants.deg);
        final MyRotClass rot = new MyRotClass(az);
        final double corr = 0.9999999999999999;
        rot.setZZ(corr);
        rot.setXX(rot.xx() / Math.sqrt(corr));
        rot.setXY(rot.xy() / Math.sqrt(corr));
        rot.setYX(rot.yx() / Math.sqrt(corr));
        rot.setYY(rot.yy() / Math.sqrt(corr));
        cout.scientific();
        rot.print(cout);
        cout.put("\n");
        cout.precision(30);
        cout.put(rot.xx()).put("\t").put(rot.xy()).put("\t").put(rot.xz()).put("\n")
            .put(rot.yx()).put("\t").put(rot.yy()).put("\t").put(rot.yz()).put("\n")
            .put(rot.zx()).put("\t").put(rot.zy()).put("\t").put(rot.zz()).put("\n");
        cout.put("\nEuler angles:").put("\nphi = ").put(rot.phi()).put("\ttheta = ").put(rot.theta()).put("\tpsi = ").put(rot.psi())
            .put("\n");
        final HepRotation newrot = new HepRotation(rot.phi(), rot.theta(), rot.psi());
        newrot.print(cout);
        return 0;
    }
}

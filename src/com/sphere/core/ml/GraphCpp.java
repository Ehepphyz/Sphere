package com.sphere.core.ml;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.ml.GraphCompiler.Arg;
import com.sphere.core.ml.GraphCompiler.Binding;
import com.sphere.core.ml.GraphCompiler.CompiledGraph;
import com.sphere.core.ml.GraphCompiler.Fallback;
import com.sphere.core.ml.GraphCompiler.Group;
import com.sphere.core.ml.GraphCompiler.Instr;
import com.sphere.core.ml.GraphCompiler.Matmul;
import com.sphere.core.ml.GraphCompiler.Micro;
import com.sphere.core.ml.GraphCompiler.NotCompiled;
import com.sphere.core.ml.GraphCompiler.Reduction;
import com.sphere.core.ml.GraphCompiler.Storage;
import com.sphere.core.ml.GraphCompiler.View;
import com.sphere.core.ml.NeuralGraph.Tensor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * A graph of PyTorch or TensorFlow transcribed into C++ source, for a Geant4 application to compile as its own code:
 * one header, no library at all, no file beside it.
 *
 * <p>What is written is what {@link GraphCompiler} lowers the graph to, once more but as text: its fused loops, its
 * products over strided views, its planned buffers. The weights are hexadecimal literals, so that none changes by a
 * bit. The memory of a call is one buffer per thread, allocated once, which is what Geant4's worker threads need.
 * The header carries inputs and the outputs Sphere's interpreter gave for them, and {@code SelfTestError()} compares
 * the two with the compiler and the flags of the application itself.
 *
 * <p>A transcription is specialized to one batch, and refused -- with what stopped it -- for what cannot be written
 * as fixed loops: loops or conditions on the data, sizes depending on the data, and the operations
 * {@link #transcribe} lists as missing. {@code sphere_graph.hpp} runs those.
 */
public final class GraphCpp {

    private GraphCpp() {
    }

    /** Past this many numbers in the source a compiler suffers: the graph is better read from its file. */
    private static final long MAX_CONSTANTS = 2_000_000L;
    /** The self-test is left out past this many numbers. */
    private static final int MAX_REFERENCE = 20_000;

    /** A transcribed graph: its header's text, and what a program writes to call it. */
    public record Transcript(String className, String text, String usage, String report, List<String> inputs, int[][] inShapes,
                             int[][] outShapes, boolean selfTest, long bitsAgainstInterpreter) {

        public String fileName() {
            return className + ".hh";
        }
    }

    /** What the header's self-test said once compiled: {@code error} is the worst difference, NaN when not run. */
    public record Verdict(boolean compiled, double error, String detail) {
    }

    /** The folder of the exported graphs. */
    public static Path models() {
        return Bridge.folder().resolve("models");
    }

    /** The graphs exported so far (:torch export, :tf export, :tf import), by name. */
    public static List<Path> graphs() throws IOException {
        final Path dir = models();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".sng.spx")).sorted().toList();
        }
    }

    /** "my-tagger" gives "MyTaggerModel": a class name for a graph's name. */
    public static String classNameFor(String graphName) {
        final StringBuilder b = new StringBuilder();
        boolean up = true;
        for (char c : graphName.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                b.append(up ? Character.toUpperCase(c) : c);
                up = false;
            } else {
                up = true;
            }
        }
        if (b.length() == 0 || Character.isDigit(b.charAt(0))) {
            b.insert(0, "Net");
        }
        return b.append("Model").toString();
    }

    // ------------------------------------------------------------------
    // The scalar functions, as GraphKernels has them
    // ------------------------------------------------------------------

    private static final Map<String, String> SCALARS = new LinkedHashMap<>();

    private static void scalar(String name, String parameters, String body) {
        SCALARS.put(name, "    static double f_" + name + "(" + parameters + ") { return " + body + "; }\n");
    }

    static {
        final String v = "double v", uw = "double u, double w";
        scalar("id", v, "v");
        scalar("relu", v, "v != v ? v : jmax(v, 0.0)");
        scalar("gelu", v, "0.5 * v * (1.0 + std::erf(v / std::sqrt(2.0)))");
        scalar("geluTanh", v, "0.5 * v * (1.0 + std::tanh(std::sqrt(2.0 / 3.141592653589793) * (v + 0.044715 * v * v * v)))");
        scalar("sigmoid", v, "1.0 / (1.0 + std::exp(-v))");
        for (String f : List.of("tanh", "exp", "log", "sqrt", "sin", "cos", "sinh", "cosh", "atan", "asin", "acos", "log1p", "expm1",
            "log10", "erf", "floor", "ceil")) {
            scalar(f, v, "std::" + f + "(v)");
        }
        scalar("rsqrt", v, "1.0 / std::sqrt(v)");
        scalar("neg", v, "-v");
        scalar("abs", v, "std::fabs(v)");
        scalar("log2", v, "std::log(v) / std::log(2.0)");
        scalar("reciprocal", v, "1.0 / v");
        scalar("sign", v, "v > 0 ? 1.0 : v < 0 ? -1.0 : 0.0");
        scalar("round", v, "std::nearbyint(v)");
        scalar("trunc", v, "v < 0 ? std::ceil(v) : std::floor(v)");
        scalar("silu", v, "v / (1.0 + std::exp(-v))");
        scalar("leakyRelu", "double v, double s", "v > 0 ? v : s * v");
        scalar("hardtanh", "double v, double lo, double hi", "jmin(jmax(v, lo), hi)");
        scalar("elu", "double v, double al, double sc, double is", "sc * (v > 0 ? v : al * std::expm1(is * v))");
        scalar("softplus", "double v, double be, double th", "be * v > th ? v : std::log1p(std::exp(be * v)) / be");
        scalar("logicalNot", v, "v == 0 ? 1.0 : 0.0");
        scalar("isnan", v, "v != v ? 1.0 : 0.0");
        scalar("isinf", v, "std::isinf(v) ? 1.0 : 0.0");
        scalar("toBool", v, "v != 0 ? 1.0 : 0.0");
        scalar("toInt", v, "v < 0 ? std::ceil(v) : std::floor(v)");
        scalar("toFloat32", v, "static_cast<double>(static_cast<float>(v))");
        scalar("add", uw, "u + w");
        scalar("addAlpha", "double u, double w, double al", "u + al * w");
        scalar("sub", "double u, double w, double al", "u - al * w");
        scalar("rsub", "double u, double w, double al", "w - al * u");
        scalar("mul", uw, "u * w");
        scalar("div", uw, "u / w");
        scalar("divFloor", uw, "jfloordiv(u, w)");
        scalar("divTrunc", uw, "(u / w) < 0 ? std::ceil(u / w) : std::floor(u / w)");
        scalar("remainder", uw, "jmod(u, w)");
        scalar("fmod", uw, "std::fmod(u, w)");
        scalar("maximum", uw, "jmax(u, w)");
        scalar("minimum", uw, "jmin(u, w)");
        scalar("atan2", uw, "std::atan2(u, w)");
        scalar("eq", uw, "u == w ? 1.0 : 0.0");
        scalar("ne", uw, "u != w ? 1.0 : 0.0");
        scalar("lt", uw, "u < w ? 1.0 : 0.0");
        scalar("le", uw, "u <= w ? 1.0 : 0.0");
        scalar("gt", uw, "u > w ? 1.0 : 0.0");
        scalar("ge", uw, "u >= w ? 1.0 : 0.0");
        scalar("logicalAnd", uw, "u != 0 && w != 0 ? 1.0 : 0.0");
        scalar("logicalOr", uw, "u != 0 || w != 0 ? 1.0 : 0.0");
        scalar("logicalXor", uw, "(u != 0) != (w != 0) ? 1.0 : 0.0");
        scalar("clampMin", uw, "u != u ? u : jmax(u, w)");
        scalar("clampMax", uw, "u != u ? u : jmin(u, w)");
        scalar("pow", uw, "std::pow(u, w)");
        scalar("scaleAdd", "double c, double p, double be, double al", "be * c + al * p");
        scalar("redMax", uw, "jmax(u, w)");
        scalar("redMin", uw, "jmin(u, w)");
        scalar("bnAffine", "double x, double mean, double var, double w, double b, double eps",
            "x * (w / std::sqrt(var + eps)) + (b - mean * (w / std::sqrt(var + eps)))");
        scalar("where", "double c, double x, double y", "c != 0 ? x : y");
    }

    // ------------------------------------------------------------------
    // Transcription
    // ------------------------------------------------------------------

    /** The graph as a C++ header for inputs of this batch; a {@link NotCompiled} says why it cannot be. */
    public static Transcript transcribe(NeuralGraph g, String className, int batch) {
        final CompiledGraph cg = GraphCompiler.compile(g, batch, true);
        return new Writer(g, cg, className).write();
    }

    /** Writes the header in the application's include/ (its own folder if it has none); the path written. */
    public static Path write(Transcript t, Path application) throws IOException {
        final Path file = target(t.className(), application);
        Files.writeString(file, t.text(), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Gives the application what it needs to call PyTorch or TensorFlow while it runs, through Sphere's gateway: the
     * headers of sphere_mlink, copied beside its own so that its CMakeLists finds them as it is. The files written.
     */
    public static List<Path> installGateway(Path application) throws IOException {
        final Path include = application.resolve("include");
        final Path to = Files.isDirectory(include) ? include : application;
        final List<Path> out = new ArrayList<>();
        for (String h : List.of("sphere_spx.hpp", "sphere_graph.hpp", "sphere_mlink.hpp")) {
            final Path from = Bridge.libraries().resolve(h);
            if (!Files.isRegularFile(from)) {
                throw new IOException("no " + from + ": start Sphere's bridge once");
            }
            out.add(Files.copy(from, to.resolve(h), StandardCopyOption.REPLACE_EXISTING));
        }
        return out;
    }

    /** Compiles the header alone with this compiler and runs its self-test, in a folder of its own under {@code work}. */
    public static Verdict verify(Transcript t, String compiler, Path work, Consumer<String> log) throws IOException, InterruptedException {
        if (!t.selfTest()) {
            return new Verdict(false, Double.NaN, "the graph is too large for a self-test in its header");
        }
        final Path dir = Files.createDirectories(work.resolve(t.className()));
        Files.writeString(dir.resolve(t.fileName()), t.text(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("verify.cpp"), "#include \"" + t.fileName() + "\"\n#include <cstdio>\n"
            + "int main() { std::printf(\"%.17g\\n\", " + t.className() + "::SelfTestError()); return 0; }\n", StandardCharsets.UTF_8);
        final String exe = "verify" + (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : "");
        final Run build = run(List.of(compiler, "-std=c++17", "-O2", "-Wall", "-I.", "verify.cpp", "-o", exe), compiler, dir, 600);
        if (build.code != 0) {
            log.accept(build.output);
            return new Verdict(false, Double.NaN, "the compiler refused the header");
        }
        if (!build.output.isBlank()) {
            log.accept(build.output);
        }
        final Run r = run(List.of(dir.resolve(exe).toString()), compiler, dir, 120);
        if (r.code != 0) {
            return new Verdict(true, Double.NaN, "the self-test ended with code " + r.code + ": " + r.output);
        }
        final double e = Double.parseDouble(r.output.trim());
        return new Verdict(true, e, String.format(Locale.ROOT, "compiled, worst difference from Sphere's interpreter %.3g", e));
    }

    private record Run(int code, String output) {
    }

    /** A program's exit code and output; its output goes to a file, so that the time limit holds even when it never closes it. */
    private static Run run(List<String> cmd, String compiler, Path dir, int seconds) throws IOException, InterruptedException {
        final Path log = Files.createTempFile(dir, "run", ".log");
        try {
            final ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            final Path tool = Path.of(compiler);
            if (tool.getParent() != null) {   // a compiler from MSYS2 and what it builds need its DLLs
                pb.environment().merge("PATH", tool.getParent().toString(), (a, b) -> b + java.io.File.pathSeparator + a);
            }
            final Process p = pb.start();
            p.getOutputStream().close();
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(10, TimeUnit.SECONDS);
                return new Run(-1, "timed out after " + seconds + " s\n" + new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
            }
            return new Run(p.exitValue(), new String(Files.readAllBytes(log), StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(log);
        }
    }

    /** Where a header of this class goes in the application: its include/ folder, or the folder itself. */
    public static Path target(String className, Path application) {
        final Path include = application.resolve("include");
        return (Files.isDirectory(include) ? include : application).resolve(className + ".hh");
    }

    /** Whether a file is a header this class wrote (and may be written again), not one of the application's own. */
    public static boolean generated(Path header) {
        try (BufferedReader r = Files.newBufferedReader(header, StandardCharsets.UTF_8)) {
            final String first = r.readLine();
            return first != null && first.startsWith("// " + header.getFileName() + " -- the network");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // The writer
    // ------------------------------------------------------------------

    private static final class Writer {
        final NeuralGraph g;
        final CompiledGraph cg;
        final String cls;
        final StringBuilder body = new StringBuilder();
        final StringBuilder extra = new StringBuilder();
        final Set<String> used = new LinkedHashSet<>();
        final Set<String> missing = new java.util.TreeSet<>();
        final List<String> params = new ArrayList<>();
        final Map<Integer, Integer> buffers = new TreeMap<>();   // slot -> size
        boolean embedding;
        int extras;
        int var;

        Writer(NeuralGraph g, CompiledGraph cg, String cls) {
            this.g = g;
            this.cg = cg;
            this.cls = cls;
        }

        Transcript write() {
            long constants = 0;
            for (Storage s : cg.storages) {
                if (s.kind == GraphCompiler.CONST) {
                    constants += s.size;
                }
            }
            if (constants > MAX_CONSTANTS) {
                throw new NotCompiled(g.name + " holds " + constants + " constants, too many for a source file (at most " + MAX_CONSTANTS
                    + "): run it with sphere_graph.hpp (:torch cpp)");
            }
            parameters();
            for (Storage s : cg.storages) {
                if ((s.kind == GraphCompiler.KERNEL || s.kind == GraphCompiler.DYNAMIC) && s.slot >= 0) {
                    buffers.merge(s.slot, s.size, Math::max);
                }
            }
            int t = 0;
            for (Instr in : cg.instrs) {
                instruction(in, t++);
            }
            if (!missing.isEmpty()) {
                throw new NotCompiled(g.name + " uses what has no C++ transcription yet: " + String.join(", ", missing)
                    + ". It still runs in Geant4 through sphere_graph.hpp (:torch cpp)");
            }
            final int[][] outShapes = new int[cg.outs.size()][];
            for (int i = 0; i < outShapes.length; i++) {
                outShapes[i] = cg.outs.get(i) instanceof View v ? v.shape() : ((Tensor) cg.outs.get(i)).shape;
            }
            outputs();

            // inputs Sphere's interpreter answers for: below 0.9, so that one read as an index is a valid one
            final Random random = new Random(20261109L);
            final List<Tensor> in = new ArrayList<>();
            int reference = 0;
            for (int[] s : cg.inShapes) {
                final double[] d = new double[NeuralGraph.numel(s)];
                for (int i = 0; i < d.length; i++) {
                    d[i] = random.nextDouble() * 0.9;
                }
                in.add(new Tensor(d, s));
                reference += d.length;
            }
            final List<Tensor> expected = cg.run(in);
            for (Tensor o : expected) {
                reference += o.numel();
            }
            final boolean selfTest = reference <= MAX_REFERENCE;
            final long bits = cg.check(in);
            if (bits != 0) {
                throw new NotCompiled("the compiled graph differs from the interpreter by " + bits + " bits: not transcribed");
            }
            final String text = header(constants, outShapes, selfTest ? in : null, selfTest ? expected : null);
            return new Transcript(cls, text, usage(outShapes), cg.report(), List.copyOf(params), cg.inShapes, outShapes, selfTest, bits);
        }

        void parameters() {
            final Set<String> taken = new HashSet<>();
            for (int i = 0; i < g.inputs.size(); i++) {
                final String raw = i < g.inputNames.size() ? g.inputNames.get(i) : g.inputs.get(i);
                String id = raw.replaceAll("[^A-Za-z0-9_]", "_");
                if (id.isEmpty() || !taken.add(id)) {
                    id = "input" + i;
                    taken.add(id);
                }
                params.add(id);
            }
        }

        String name(Storage s) {
            return switch (s.kind) {
                case GraphCompiler.INPUT -> "in_" + params.get(s.input);
                case GraphCompiler.CONST -> "c" + s.slot;
                default -> "b" + s.slot;
            };
        }

        String fresh(String stem) {
            return stem + (var++);
        }

        static String shape(int[] s) {
            return Arrays.toString(s).replace(" ", "");
        }

        static String index(int offset, int[] strides, String[] vars) {
            final StringBuilder b = new StringBuilder();
            b.append(offset);
            for (int d = 0; d < strides.length; d++) {
                if (strides[d] != 0) {
                    b.append(" + ").append(vars[d]);
                    if (strides[d] != 1) {
                        b.append(" * ").append(strides[d]);
                    }
                }
            }
            String s = b.toString();
            if (s.startsWith("0 + ")) {
                s = s.substring(4);
            }
            return s;
        }

        static String literal(double x) {
            if (Double.isNaN(x)) {
                return "std::numeric_limits<double>::quiet_NaN()";
            }
            if (Double.isInfinite(x)) {
                return (x < 0 ? "-" : "") + "std::numeric_limits<double>::infinity()";
            }
            return Double.toHexString(x);
        }

        static void array(StringBuilder b, String indent, String id, double[] data) {
            b.append(indent).append("static const double ").append(id).append("[").append(Math.max(1, data.length)).append("] = {");
            for (int i = 0; i < data.length; i++) {
                b.append(i % 4 == 0 ? "\n" + indent + "    " : " ").append(literal(data[i])).append(i + 1 < data.length ? "," : "");
            }
            if (data.length == 0) {
                b.append("0.0");
            }
            b.append("\n").append(indent).append("};\n");
        }

        /** Opens one loop per size, each on its own line; the variables, and the indentation inside. */
        String[] open(int[] sizes, String indent, StringBuilder b) {
            final String[] vars = new String[sizes.length];
            for (int d = 0; d < sizes.length; d++) {
                vars[d] = fresh("i");
                b.append(indent).append("    ".repeat(d)).append("for (int ").append(vars[d]).append(" = 0; ").append(vars[d]).append(" < ")
                    .append(sizes[d]).append("; ++").append(vars[d]).append(") {\n");
            }
            return vars;
        }

        void close(int depth, String indent, StringBuilder b) {
            for (int d = depth - 1; d >= 0; d--) {
                b.append(indent).append("    ".repeat(d)).append("}\n");
            }
        }

        void instruction(Instr in, int t) {
            final StringBuilder b = body;
            if (in.group != null) {
                b.append("        { // ").append(t).append(": elementwise over ").append(shape(in.group.shape));
                b.append(" (").append(describe(in.group)).append(")\n");
                group(in.group);
            } else if (in.mm != null) {
                b.append("        { // ").append(t).append(": matrix product\n");
                product(in.mm);
            } else if (in.sm != null) {
                b.append("        { // ").append(t).append(in.sm.log() ? ": log-softmax\n" : ": softmax\n");
                softmax(in);
            } else if (in.ln != null) {
                b.append("        { // ").append(t).append(": layer normalization\n");
                layerNorm(in);
            } else if (in.cv != null) {
                b.append("        { // ").append(t).append(": convolution\n");
                conv(in);
            } else if (in.pl != null) {
                b.append("        { // ").append(t).append(in.pl.max() ? ": max pooling\n" : ": average pooling\n");
                pool(in);
            } else if (in.fb != null) {
                b.append("        { // ").append(t).append(": ").append(in.fb.op).append("\n");
                fallback(in.fb);
            } else {
                return;
            }
            b.append("        }\n");
        }

        static String describe(Group g) {
            final Set<String> m = new LinkedHashSet<>();
            for (Micro x : g.micros) {
                m.add(x.method());
            }
            return String.join(", ", m);
        }

        void group(Group gr) {
            final StringBuilder b = body;
            final String in0 = "            ";
            final int r = gr.shape.length;
            final int[] contiguous = NeuralGraph.strides(gr.shape);
            final List<int[]> redStrides = new ArrayList<>();
            for (Reduction rd : gr.reductions) {
                final int[] ost = NeuralGraph.strides(rd.kshape());
                final int[] st = new int[r];
                for (int d = 0; d < r; d++) {
                    st[d] = rd.reduced()[d] ? 0 : ost[d];
                }
                redStrides.add(st);
                final String init = rd.kind() == GraphCompiler.MAX ? "-std::numeric_limits<double>::infinity()"
                    : rd.kind() == GraphCompiler.MIN ? "std::numeric_limits<double>::infinity()" : "0.0";
                b.append(in0).append("std::fill_n(").append(name(rd.out())).append(", ").append(rd.out().size).append(", ").append(init).append(");\n");
            }
            final String[] vars = open(gr.shape, in0, b);
            final String ind = in0 + "    ".repeat(r);
            final String[] val = new String[gr.operands.size()];
            for (int k = 0; k < val.length; k++) {
                final View o = gr.operands.get(k);
                val[k] = "v" + k;
                b.append(ind).append("const double ").append(val[k]).append(" = ").append(name(o.storage())).append("[")
                    .append(index(o.offset(), o.stridesFor(gr.shape), vars)).append("];\n");
            }
            for (int j = 0; j < gr.micros.size(); j++) {
                final Micro m = gr.micros.get(j);
                final List<String> args = new ArrayList<>();
                for (Arg a : m.args()) {
                    args.add(switch (a.kind()) {
                        case Arg.OPERAND -> val[a.index()];
                        case Arg.MICRO -> "m" + a.index();
                        default -> literal(a.value());
                    });
                }
                for (double c : m.consts()) {
                    args.add(literal(c));
                }
                b.append(ind).append("const double m").append(j).append(" = ").append(call(m.method())).append("(")
                    .append(String.join(", ", args)).append(");\n");
            }
            for (Object[] st : gr.stored) {
                final View o = (View) st[1];
                b.append(ind).append(name(o.storage())).append("[").append(index(0, contiguous, vars)).append("] = m").append(st[0]).append(";\n");
            }
            for (int k = 0; k < gr.reductions.size(); k++) {
                final Reduction rd = gr.reductions.get(k);
                final String at = name(rd.out()) + "[" + index(0, redStrides.get(k), vars) + "]";
                b.append(ind).append(at).append(" = ");
                if (rd.kind() == GraphCompiler.MAX || rd.kind() == GraphCompiler.MIN) {
                    b.append(rd.kind() == GraphCompiler.MAX ? "jmax(" : "jmin(").append(at).append(", m").append(rd.micro()).append(");\n");
                } else {
                    b.append(at).append(" + m").append(rd.micro()).append(";\n");
                }
            }
            close(r, in0, b);
            for (Reduction rd : gr.reductions) {
                if (rd.kind() == GraphCompiler.MEAN) {
                    final String i = fresh("i");
                    b.append(in0).append("for (int ").append(i).append(" = 0; ").append(i).append(" < ").append(rd.out().size).append("; ++").append(i)
                        .append(") ").append(name(rd.out())).append("[").append(i).append("] /= ").append(literal(rd.count())).append(";\n");
                }
            }
        }

        String call(String method) {
            if (!SCALARS.containsKey(method)) {
                missing.add("the scalar function " + method);
                return "f_missing";
            }
            used.add(method);
            return "f_" + method;
        }

        void product(Matmul mm) {
            final StringBuilder b = body;
            final String in0 = "            ";
            final String out = name(mm.out());
            b.append(in0).append("std::fill_n(").append(out).append(", ").append(mm.out().size).append(", 0.0);\n");
            final int nb = mm.batch().length;
            final String[] z = open(mm.batch(), in0, b);
            final String ind = in0 + "    ".repeat(nb);
            final String i = fresh("i"), p = fresh("p"), j = fresh("j");
            final int[] so = new int[nb];
            int acc = mm.n() * mm.m();
            for (int d = nb - 1; d >= 0; d--) {
                so[d] = acc;
                acc *= mm.batch()[d];
            }
            final int[] aStr = Arrays.copyOf(mm.sa(), nb + 2), bStr = Arrays.copyOf(mm.sb(), nb + 2), oStr = Arrays.copyOf(so, nb + 2);
            aStr[nb] = mm.sai();
            aStr[nb + 1] = mm.sap();
            bStr[nb] = mm.sbp();
            bStr[nb + 1] = mm.sbj();
            oStr[nb] = mm.m();
            oStr[nb + 1] = 1;
            final String[] aV = Arrays.copyOf(z, nb + 2), bV = Arrays.copyOf(z, nb + 2), oV = Arrays.copyOf(z, nb + 2);
            aV[nb] = i;
            aV[nb + 1] = p;
            bV[nb] = p;
            bV[nb + 1] = j;
            oV[nb] = i;
            oV[nb + 1] = j;
            b.append(ind).append("for (int ").append(i).append(" = 0; ").append(i).append(" < ").append(mm.n()).append("; ++").append(i).append(") {\n");
            b.append(ind).append("    for (int ").append(p).append(" = 0; ").append(p).append(" < ").append(mm.k()).append("; ++").append(p).append(") {\n");
            // a zero is multiplied like the others, as the interpreter does: 0 * NaN is NaN, and a NaN must not vanish
            b.append(ind).append("        const double a = ").append(name(mm.a().storage())).append("[").append(index(mm.a().offset(), aStr, aV)).append("];\n");
            b.append(ind).append("        for (int ").append(j).append(" = 0; ").append(j).append(" < ").append(mm.m()).append("; ++").append(j).append(") {\n");
            b.append(ind).append("            ").append(out).append("[").append(index(0, oStr, oV)).append("] += a * ").append(name(mm.b().storage()))
                .append("[").append(index(mm.b().offset(), bStr, bV)).append("];\n");
            b.append(ind).append("        }\n").append(ind).append("    }\n").append(ind).append("}\n");
            close(nb, in0, b);
        }

        void softmax(Instr in) {
            final var sm = in.sm;
            final String x = name(sm.in()), o = name(sm.out());
            final int n = sm.n(), inner = sm.inner();
            final StringBuilder b = body;
            final String t = "            ";
            b.append(t).append("for (int p = 0; p < ").append(sm.outer()).append("; ++p) for (int q = 0; q < ").append(inner).append("; ++q) {\n");
            b.append(t).append("    const int base = p * ").append(n * inner).append(" + q;\n");
            b.append(t).append("    double m = -std::numeric_limits<double>::infinity(), s = 0.0;\n");
            b.append(t).append("    for (int i = 0; i < ").append(n).append("; ++i) m = jmax(m, ").append(x).append("[base + i * ").append(inner).append("]);\n");
            b.append(t).append("    for (int i = 0; i < ").append(n).append("; ++i) { const double e = std::exp(").append(x).append("[base + i * ").append(inner)
                .append("] - m); ").append(o).append("[base + i * ").append(inner).append("] = e; s += e; }\n");
            if (sm.log()) {
                b.append(t).append("    const double ls = std::log(s);\n");
                b.append(t).append("    for (int i = 0; i < ").append(n).append("; ++i) ").append(o).append("[base + i * ").append(inner).append("] = ")
                    .append(x).append("[base + i * ").append(inner).append("] - m - ls;\n");
            } else {
                b.append(t).append("    for (int i = 0; i < ").append(n).append("; ++i) ").append(o).append("[base + i * ").append(inner).append("] /= s;\n");
            }
            b.append(t).append("}\n");
        }

        void layerNorm(Instr in) {
            final var ln = in.ln;
            final String x = name(ln.in()), o = name(ln.out()), t = "            ";
            final int inner = ln.inner();
            final StringBuilder b = body;
            b.append(t).append("for (int p = 0; p < ").append(ln.outer()).append("; ++p) {\n");
            b.append(t).append("    double m = 0.0, v = 0.0;\n");
            b.append(t).append("    for (int i = 0; i < ").append(inner).append("; ++i) m += ").append(x).append("[p * ").append(inner).append(" + i];\n");
            b.append(t).append("    m /= ").append(literal(inner)).append(";\n");
            b.append(t).append("    for (int i = 0; i < ").append(inner).append("; ++i) { const double d = ").append(x).append("[p * ").append(inner).append(" + i] - m; v += d * d; }\n");
            b.append(t).append("    v /= ").append(literal(inner)).append(";\n");
            b.append(t).append("    const double rr = 1.0 / std::sqrt(v + ").append(literal(ln.eps())).append(");\n");
            b.append(t).append("    ").append(name(ln.mean())).append("[p] = m;\n");
            b.append(t).append("    ").append(name(ln.rstd())).append("[p] = rr;\n");
            b.append(t).append("    for (int i = 0; i < ").append(inner).append("; ++i) {\n");
            b.append(t).append("        double y = (").append(x).append("[p * ").append(inner).append(" + i] - m) * rr;\n");
            if (ln.w() != null) {
                b.append(t).append("        y = y * ").append(name(ln.w())).append("[i];\n");
            }
            if (ln.b() != null) {
                b.append(t).append("        y = y + ").append(name(ln.b())).append("[i];\n");
            }
            b.append(t).append("        ").append(o).append("[p * ").append(inner).append(" + i] = y;\n");
            b.append(t).append("    }\n").append(t).append("}\n");
        }

        /** "pos_d = o_d st_d - pd_d + k_d dl_d", each outside [0, in_d) going to {@code outside}: the statements. */
        void positions(StringBuilder b, String ind, String[] ov, String[] kv, String[] pos, int[] st, int[] pd, int[] dl, int[] in, String outside) {
            for (int d = 0; d < ov.length; d++) {
                b.append(ind).append("const int ").append(pos[d]).append(" = ").append(ov[d]).append(" * ").append(st[d]).append(" + (").append(-pd[d])
                    .append(") + ").append(kv[d]).append(" * ").append(dl[d]).append(";\n");
                b.append(ind).append("if (").append(pos[d]).append(" < 0 || ").append(pos[d]).append(" >= ").append(in[d]).append(") ").append(outside).append("\n");
            }
        }

        void conv(Instr in) {
            final var cv = in.cv;
            final StringBuilder b = body;
            final String t = "            ";
            final int nd = cv.in().length;
            final int isz = NeuralGraph.numel(cv.in()), ksz = NeuralGraph.numel(cv.ks()), osz = NeuralGraph.numel(cv.out_());
            final int[] ist = NeuralGraph.strides(cv.in()), kst = NeuralGraph.strides(cv.ks()), ost = NeuralGraph.strides(cv.out_());
            final String s = fresh("s"), co = fresh("co"), g = fresh("g"), ci = fresh("ci"), acc = fresh("acc");
            b.append(t).append("for (int ").append(s).append(" = 0; ").append(s).append(" < ").append(cv.n()).append("; ++").append(s).append(") ");
            b.append("for (int ").append(co).append(" = 0; ").append(co).append(" < ").append(cv.cout()).append("; ++").append(co).append(") {\n");
            final String t1 = t + "    ";
            b.append(t1).append("const int ").append(g).append(" = ").append(co).append(" / ").append(cv.og()).append(";\n");
            final String[] ov = open(cv.out_(), t1, b);
            final String t2 = t1 + "    ".repeat(nd);
            b.append(t2).append("double ").append(acc).append(" = ").append(cv.bias() == null ? "0.0" : name(cv.bias()) + "[" + co + "]").append(";\n");
            b.append(t2).append("for (int ").append(ci).append(" = 0; ").append(ci).append(" < ").append(cv.cg()).append("; ++").append(ci).append(") {\n");
            final String t3 = t2 + "    ";
            final String[] kv = open(cv.ks(), t3, b);
            final String t4 = t3 + "    ".repeat(nd);
            final String[] pos = new String[nd];
            for (int d = 0; d < nd; d++) {
                pos[d] = fresh("pos");
            }
            // the padding is zeros, multiplied like the rest: an infinite weight there makes NaN, as in the interpreter
            final String inside = fresh("inside");
            b.append(t4).append("bool ").append(inside).append(" = true;\n");
            positions(b, t4, ov, kv, pos, cv.st(), cv.pd(), cv.dl(), cv.in(), inside + " = false;");
            b.append(t4).append(acc).append(" += (").append(inside).append(" ? ").append(name(cv.x())).append("[(").append(s).append(" * ").append(cv.cin()).append(" + ")
                .append(g).append(" * ").append(cv.cg()).append(" + ").append(ci).append(") * ").append(isz);
            for (int d = 0; d < nd; d++) {
                b.append(" + ").append(pos[d]).append(" * ").append(ist[d]);
            }
            b.append("] : 0.0) * ").append(name(cv.w())).append("[(").append(co).append(" * ").append(cv.cg()).append(" + ").append(ci).append(") * ").append(ksz);
            for (int d = 0; d < nd; d++) {
                b.append(" + ").append(kv[d]).append(" * ").append(kst[d]);
            }
            b.append("];\n");
            close(nd, t3, b);
            b.append(t2).append("}\n");
            b.append(t2).append(name(cv.out())).append("[(").append(s).append(" * ").append(cv.cout()).append(" + ").append(co).append(") * ").append(osz);
            for (int d = 0; d < nd; d++) {
                b.append(" + ").append(ov[d]).append(" * ").append(ost[d]);
            }
            b.append("] = ").append(acc).append(";\n");
            close(nd, t1, b);
            b.append(t).append("}\n");
        }

        void pool(Instr in) {
            final var pl = in.pl;
            final StringBuilder b = body;
            final String t = "            ";
            final int isz = NeuralGraph.numel(pl.in()), osz = NeuralGraph.numel(pl.out_()), ksz = NeuralGraph.numel(pl.kernel());
            final int[] ist = NeuralGraph.strides(pl.in()), ost = NeuralGraph.strides(pl.out_());
            final String p = fresh("p"), acc = fresh("acc"), cov = fresh("cov"), v = fresh("v");
            final String start = pl.max() ? "-std::numeric_limits<double>::infinity()" : "0.0";
            b.append(t).append("for (int ").append(p).append(" = 0; ").append(p).append(" < ").append(pl.planes()).append("; ++").append(p).append(") {\n");
            final String t1 = t + "    ";
            final String[] ov = open(pl.out_(), t1, b);
            final String t2 = t1 + "    ".repeat(2);
            b.append(t2).append("double ").append(acc).append(" = ").append(start).append(";\n").append(t2).append("int ").append(cov).append(" = 0;\n");
            b.append(t2).append("(void)").append(cov).append(";\n");
            final String[] kv = open(pl.kernel(), t2, b);
            final String t3 = t2 + "    ".repeat(2);
            final String[] pos = {fresh("pos"), fresh("pos")};
            b.append(t3).append("double ").append(v).append(" = ").append(start).append(";\n");
            final String inside = fresh("inside");
            b.append(t3).append("bool ").append(inside).append(" = true;\n");
            final String[] e = new String[2];
            for (int d = 0; d < 2; d++) {
                e[d] = pos[d];
                b.append(t3).append("const int ").append(pos[d]).append(" = ").append(ov[d]).append(" * ").append(pl.stride()[d]).append(" + (").append(-pl.pad()[d])
                    .append(") + ").append(kv[d]).append(" * ").append(pl.dil()[d]).append(";\n");
                b.append(t3).append("if (").append(pos[d]).append(" < 0 || ").append(pos[d]).append(" >= ").append(pl.in()[d]).append(") ").append(inside).append(" = false;\n");
            }
            b.append(t3).append("if (").append(inside).append(") { ").append(v).append(" = ").append(name(pl.x())).append("[").append(p).append(" * ").append(isz)
                .append(" + ").append(e[0]).append(" * ").append(ist[0]).append(" + ").append(e[1]).append(" * ").append(ist[1]).append("]; ++").append(cov).append("; }\n");
            b.append(t3).append(pl.max() ? acc + " = jmax(" + acc + ", " + v + ");\n" : acc + " += " + v + ";\n");
            close(2, t2, b);
            b.append(t2).append(name(pl.out())).append("[").append(p).append(" * ").append(osz).append(" + ").append(ov[0]).append(" * ").append(ost[0])
                .append(" + ").append(ov[1]).append(" * ").append(ost[1]).append("] = ").append(acc);
            if (!pl.max()) {
                if (pl.divisor() > 0) {
                    b.append(" / ").append(literal(pl.divisor()));
                } else if (pl.countPad()) {
                    b.append(" / ").append(literal(ksz));
                } else {
                    b.append(" / static_cast<double>(").append(cov).append(")");
                }
            }
            b.append(";\n");
            close(2, t1, b);
            b.append(t).append("}\n");
        }

        /** A tensor argument of an interpreter's operation: a buffer's name, or a new array for a constant. */
        record Part(String name, int[] shape) {
        }

        Part part(Object o) {
            if (o instanceof Binding x) {
                return new Part(name(x.storage()), x.shape());
            }
            if (o instanceof Tensor t) {
                final String id = "x" + (extras++);
                accessor(extra, id, t.data);
                return new Part(id, t.shape);
            }
            return null;
        }

        Object arg(Fallback f, int i) {
            return f.bindings[i] != null ? f.bindings[i] : f.template[i];
        }

        void fallback(Fallback f) {
            switch (f.op) {
                case "cat.default", "stack.default" -> join(f);
                case "embedding.default" -> embedding(f);
                default -> missing.add(f.op);
            }
        }

        void join(Fallback f) {
            final boolean stack = f.op.equals("stack.default");
            final List<?> list = (List<?>) arg(f, 0);
            final List<Part> parts = new ArrayList<>();
            for (Object o : list) {
                final Part p = part(o);
                if (p == null) {
                    missing.add(f.op + " of a value that is no tensor");
                    return;
                }
                if (stack || !(p.shape.length == 1 && NeuralGraph.numel(p.shape) == 0)) {
                    parts.add(p);
                }
            }
            if (parts.isEmpty()) {
                missing.add(f.op + " of nothing");
                return;
            }
            final long dv = f.template.length > 1 && f.template[1] != null ? NeuralGraph.lng(f.template[1]) : 0;
            final int rank = parts.get(0).shape.length;
            final int d = NeuralGraph.dim(dv, stack ? rank + 1 : rank);
            int outer = 1, inner = 1;
            final int[] s = parts.get(0).shape;
            for (int i = 0; i < d; i++) {
                outer *= s[i];
            }
            for (int i = stack ? d : d + 1; i < rank; i++) {
                inner *= s[i];
            }
            final StringBuilder b = body;
            final String t = "            ";
            b.append(t).append("double* o = ").append(name(f.outs[0])).append(";\n");
            b.append(t).append("for (int p = 0; p < ").append(outer).append("; ++p) {\n");
            for (Part p : parts) {
                final int row = (stack ? 1 : p.shape[d]) * inner;
                b.append(t).append("    for (int e = 0; e < ").append(row).append("; ++e) *o++ = ").append(p.name).append("[p * ").append(row).append(" + e];\n");
            }
            b.append(t).append("}\n");
        }

        void embedding(Fallback f) {
            final Part w = part(arg(f, 0)), idx = part(arg(f, 1));
            if (w == null || idx == null || w.shape.length == 0) {
                missing.add(f.op + " of these arguments");
                return;
            }
            final int n = w.shape[0], inner = NeuralGraph.numel(w.shape) / Math.max(1, n), m = NeuralGraph.numel(idx.shape);
            final String t = "            ", o = name(f.outs[0]);
            final StringBuilder b = body;
            b.append(t).append("for (int j = 0; j < ").append(m).append("; ++j) {\n");
            embedding = true;
            // Java's (int): NaN is 0, out of range clamps; then what the interpreter refuses, refused here, not read past the table
            b.append(t).append("    int at = jtoint(").append(idx.name).append("[j]);\n");
            b.append(t).append("    if (at < 0) at += ").append(n).append(";\n");
            b.append(t).append("    if (at < 0 || at >= ").append(n).append(") throw std::out_of_range(\"").append(cls)
                .append(": an embedding index outside its table\");\n");
            b.append(t).append("    for (int e = 0; e < ").append(inner).append("; ++e) ").append(o).append("[j * ").append(inner).append(" + e] = ").append(w.name)
                .append("[at * ").append(inner).append(" + e];\n");
            b.append(t).append("}\n");
        }

        void outputs() {
            final StringBuilder b = body;
            for (int i = 0; i < cg.outs.size(); i++) {
                final Object o = cg.outs.get(i);
                b.append("        { // output ").append(i).append("\n");
                if (o instanceof View v) {
                    final String[] vars = open(v.shape(), "            ", b);
                    final String ind = "            " + "    ".repeat(v.shape().length);
                    b.append(ind).append("out_").append(i).append("[").append(index(0, NeuralGraph.strides(v.shape()), vars)).append("] = ")
                        .append(name(v.storage())).append("[").append(index(v.offset(), v.strides(), vars)).append("];\n");
                    close(v.shape().length, "            ", b);
                } else {
                    final Tensor t = (Tensor) o;
                    final String id = "x" + (extras++);
                    accessor(extra, id, t.data);
                    b.append("            std::copy_n(").append(id).append(", ").append(t.numel()).append(", out_").append(i).append(");\n");
                }
                b.append("        }\n");
            }
        }

        String signature(int outs) {
            final List<String> p = new ArrayList<>();
            for (String id : params) {
                p.add("const double* in_" + id);
            }
            for (int i = 0; i < outs; i++) {
                p.add("double* out_" + i);
            }
            return String.join(", ", p);
        }

        String header(long constants, int[][] outShapes, List<Tensor> refIn, List<Tensor> refOut) {
            final StringBuilder h = new StringBuilder();
            final String guard = cls + "_hh";
            h.append("// ").append(cls).append(".hh -- the network \"").append(g.name).append("\" (").append(g.framework).append(") as C++ source.\n");
            h.append("// Written by Sphere from its graph: no PyTorch, no TensorFlow, no file to load. Thread-safe, for Geant4's workers.\n");
            h.append("// ").append(constants).append(" constants, in hexadecimal so that none changes by a bit; needs C++17.\n");
            h.append("// Specialized to a batch of ").append(cg.batch).append(": transcribe again for another.\n//\n");
            h.append("// ").append(cg.report()).append("\n//\n");
            h.append("// Written again by :g4 model cpp: change the model rather than this file.\n");
            h.append("#ifndef ").append(guard).append("\n#define ").append(guard).append("\n\n");
            for (String inc : List.of("algorithm", "cmath", "cstddef", "limits", "stdexcept", "vector")) {
                h.append("#include <").append(inc).append(">\n");
            }
            h.append("\nclass ").append(cls).append(" {\npublic:\n");
            h.append("    static constexpr int kBatch = ").append(cg.batch).append(";\n");
            for (int i = 0; i < params.size(); i++) {
                h.append("    static constexpr std::size_t kInput").append(i).append(" = ").append(NeuralGraph.numel(cg.inShapes[i])).append(";   // ")
                    .append(params.get(i)).append(" ").append(shape(cg.inShapes[i])).append(", row-major\n");
            }
            for (int i = 0; i < outShapes.length; i++) {
                h.append("    static constexpr std::size_t kOutput").append(i).append(" = ").append(NeuralGraph.numel(outShapes[i])).append(";   // output ")
                    .append(i).append(" ").append(shape(outShapes[i])).append("\n");
            }
            h.append("\n    /** The network on these inputs; each pointer holds kInputN or receives kOutputN numbers. */\n");
            h.append("    static void Evaluate(").append(signature(outShapes.length)).append(") {\n");
            int total = 0;
            for (int z : buffers.values()) {
                total += z;
            }
            h.append(pointers());
            h.append("        thread_local std::vector<double> scratch(").append(Math.max(1, total)).append(");   // once per thread\n");
            h.append("        double* const work = scratch.data();\n        (void)work;\n");
            int at = 0;
            for (Map.Entry<Integer, Integer> e : buffers.entrySet()) {
                h.append("        double* const b").append(e.getKey()).append(" = work + ").append(at).append(";\n");
                at += e.getValue();
            }
            h.append(body);
            h.append("    }\n");
            if (refIn != null) {
                h.append("\n    /** The largest difference from what Sphere's interpreter gave, each scaled by max(|expected|, 1). Call it once at start-up. */\n");
                h.append("    static double SelfTestError() {\n");
                final List<String> call = new ArrayList<>();
                for (int i = 0; i < refIn.size(); i++) {
                    array(h, "        ", "input" + i, refIn.get(i).data);
                    call.add("input" + i);
                }
                for (int i = 0; i < refOut.size(); i++) {
                    array(h, "        ", "expected" + i, refOut.get(i).data);
                    h.append("        std::vector<double> got").append(i).append("(kOutput").append(i).append(");\n");
                    call.add("got" + i + ".data()");
                }
                h.append("        Evaluate(").append(String.join(", ", call)).append(");\n");
                h.append("        double worst = 0.0;\n");
                for (int i = 0; i < refOut.size(); i++) {
                    h.append("        for (std::size_t i = 0; i < kOutput").append(i).append("; ++i) {\n");
                    h.append("            const double e = expected").append(i).append("[i], v = got").append(i).append("[i];\n");
                    h.append("            if ((e != e) != (v != v)) return std::numeric_limits<double>::infinity();\n");
                    h.append("            if (e == e) worst = std::max(worst, std::fabs(v - e) / std::max(std::fabs(e), 1.0));\n");
                    h.append("        }\n");
                }
                h.append("        return worst;\n    }\n");
            }
            h.append("\nprivate:\n");
            h.append("    static double jmax(double a, double b) {\n");
            h.append("        if (a != a || b != b) return std::numeric_limits<double>::quiet_NaN();\n");
            h.append("        if (a == 0.0 && b == 0.0) return std::signbit(a) ? b : a;\n");
            h.append("        return a > b ? a : b;\n    }\n");
            h.append("    static double jmin(double a, double b) {\n");
            h.append("        if (a != a || b != b) return std::numeric_limits<double>::quiet_NaN();\n");
            h.append("        if (a == 0.0 && b == 0.0) return std::signbit(a) ? a : b;\n");
            h.append("        return a < b ? a : b;\n    }\n");
            if (used.contains("divFloor")) {   // NeuralGraph.floorDiv: from the remainder, exact where the quotient is not
                h.append("    static double jfloordiv(double a, double b) {\n        if (b == 0) return a / b;\n");
                h.append("        const double mod = std::fmod(a, b);\n        double div = (a - mod) / b;\n");
                h.append("        if (mod != 0 && (b < 0) != (mod < 0)) div -= 1;\n        if (div == 0) return std::copysign(0.0, a / b);\n");
                h.append("        const double low = std::floor(div);\n        return div - low > 0.5 ? low + 1 : low;\n    }\n");
            }
            if (embedding) {   // Java's (int) of a double
                h.append("    static int jtoint(double v) {\n        if (v != v) return 0;\n");
                h.append("        if (v >= 2147483647.0) return 2147483647;\n        if (v <= -2147483648.0) return -2147483647 - 1;\n");
                h.append("        return static_cast<int>(v);\n    }\n");
            }
            if (used.contains("remainder")) {
                h.append("    static double jmod(double u, double w) {\n        double m = std::fmod(u, w);\n");
                h.append("        if (m != 0) { if ((w < 0) != (m < 0)) m += w; } else { m = std::copysign(0.0, w); }\n");
                h.append("        return m;\n    }\n");
            }
            for (String u : used) {
                h.append(SCALARS.get(u));
            }
            h.append("\n");
            for (Storage s : cg.storages) {
                if (s.kind == GraphCompiler.CONST) {
                    accessor(h, "c" + s.slot, s.constant);
                }
            }
            return h.append(extra).append("};\n\n#endif\n").toString();
        }

        /** The numbers in a function of their own, below the code that reads them. */
        static void accessor(StringBuilder b, String id, double[] data) {
            b.append("    static const double* ").append(id).append("_get() {\n");
            array(b, "        ", "data", data);
            b.append("        return data;\n    }\n");
        }

        /** What Evaluate starts with: a pointer to each array of numbers. */
        String pointers() {
            final StringBuilder b = new StringBuilder();
            for (Storage s : cg.storages) {
                if (s.kind == GraphCompiler.CONST) {
                    b.append("        const double* const c").append(s.slot).append(" = c").append(s.slot).append("_get();\n        (void)c").append(s.slot).append(";\n");
                }
            }
            for (int i = 0; i < extras; i++) {
                b.append("        const double* const x").append(i).append(" = x").append(i).append("_get();\n        (void)x").append(i).append(";\n");
            }
            return b.toString();
        }

        String usage(int[][] outShapes) {
            final StringBuilder u = new StringBuilder();
            u.append("// The network \"").append(g.name).append("\" (").append(g.framework).append("), in ").append(cls).append(".hh: no ML library, thread-safe.\n");
            final List<String> call = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                final String id = "nnInput_" + params.get(i);
                u.append("std::vector<double> ").append(id).append("(").append(cls).append("::kInput").append(i).append(", 0.);   // ").append(shape(cg.inShapes[i]))
                    .append(", row-major: fill it\n");
                call.add(id + ".data()");
            }
            for (int i = 0; i < outShapes.length; i++) {
                final String id = "nnOutput" + i;
                u.append("std::vector<double> ").append(id).append("(").append(cls).append("::kOutput").append(i).append(");   // ").append(shape(outShapes[i])).append("\n");
                call.add(id + ".data()");
            }
            u.append(cls).append("::Evaluate(").append(String.join(", ", call)).append(");\n");
            u.append("G4cout << \"").append(g.name).append(": \" << nnOutput0[0] << G4endl;\n");
            return u.toString();
        }
    }
}

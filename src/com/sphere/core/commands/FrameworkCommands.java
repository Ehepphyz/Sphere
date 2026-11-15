package com.sphere.core.commands;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.BridgeConformance;
import com.sphere.core.geantbackend.G4Json;
import com.sphere.core.ml.GraphCompiler;
import com.sphere.core.ml.GraphTrainer;
import com.sphere.core.ml.MlEngine;
import com.sphere.core.ml.NeuralGraph;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * TensorFlow, PyTorch and JAX on one footing:
 * <ul>
 * <li>:ml xf -- a Sphere graph (exported from PyTorch, imported from TensorFlow or Keras) run and differentiated in
 * PyTorch (torch.ops.aten), JAX and TensorFlow, held to numpy's reference and to each other; written as a
 * TensorFlow SavedModel, a TorchScript trace and a torch.export program, each reloaded and run again; every engine
 * timed -- Java's interpreter and its bytecode compiler too -- and the fastest that agrees named (sphere_xf);</li>
 * <li>:ml flow nle, :ml flow fastsim -- normalizing flows (rational-quadratic splines) written once in PyTorch and
 * exported: a neural likelihood of the W mass against the exact one, a fast simulation of the jets' detector against
 * the full one; the exported sampler then run by Java (sphere_flows).</li>
 * </ul>
 */
final class FrameworkCommands {

    private FrameworkCommands() {
    }

    static void register() {
        CommandDefinitions.register(":ml xf", "One graph in every framework: run and differentiated in PyTorch, JAX and TensorFlow, held to"
            + " numpy and to each other; written as a SavedModel, TorchScript and torch.export, reloaded; every engine timed (Java's"
            + " too), the fastest exact one named. Usage: :ml xf <graph> [dataset] [--rows 8] [--engines torch,jax,tensorflow]"
            + " [--noexport] [--nobench]", FrameworkCommands::xf);
        CommandDefinitions.register(":ml flow nle", "A normalizing flow p(pT, MET, eta | m_W) learned from simulations: exact to the"
            + " Jacobian, its distance to the exact density in nats, the mass measured with it (score calibrated) against the exact"
            + " likelihood; exported, run in every framework. Usage: :ml flow nle [--samples 100000] [--epochs 10] [--experiments 60]"
            + " [--calibration 200000]", FrameworkCommands::nle);
        CommandDefinitions.register(":ml flow fastsim", "A normalizing flow as the detector: p(detector level | particle level) of six jet"
            + " observables, sampled for new jets against the full simulation; its sampler exported and run by Java. Usage: :ml flow"
            + " fastsim [--jets 40000] [--epochs 10] [--layers 6]", FrameworkCommands::fastsim);
        CommandDefinitions.register(":ml xgrad", "TensorFlow, PyTorch and JAX inside one differentiable computation, held to the analytic gradient: every framework through every"
            + " other, eagerly and compiled, three in one chain rule, a network trained through a foreign loss. In code: sphere_xgrad.as_torch(f), as_tensorflow(f), as_jax(f)."
            + " Usage: :ml xgrad", FrameworkCommands::xgrad);
        CommandDefinitions.register(":ml fuzz", "Every operation of the graphs against the PyTorch installed, on arguments drawn from each operator's own schema, with ties, NaN and infinities:"
            + " values and gradients in NumPy, JAX and TensorFlow, then the same cases replayed by the Java and C++ runtimes. A new operation, or PyTorch's next version, is covered with nothing to write."
            + " In code: sphere_xf.fuzz(). Usage: :ml fuzz [--seed n] [--rounds 6] [--only text] [--no-gradients]", FrameworkCommands::fuzz);
        CommandDefinitions.register(":ml twin", "A saved model (TorchScript, torch.export, SavedModel, frozen graph, Keras, a Sphere graph) as a trainable module of another framework, held to the"
            + " original; with --out, written in any format and reloaded (.pt, .pt2, .sng.spx, else a SavedModel). In code: sphere_xf.twin(model, to). Usage: :ml twin <model> <torch|tensorflow|jax>"
            + " [--out file] [--shape 16,7;16]", FrameworkCommands::twin);
    }

    private static void fuzz(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml fuzz");
        MlCommands.background("ml-fuzz", () -> {
            try {
                AppLogger.info("Every operation against PyTorch, then the same cases in Java and C++ (some minutes)...");
                final Path folder = Bridge.folder().resolve("fuzz");
                Files.createDirectories(folder);
                final MlEngine e = MlCommands.engine("torch");
                final Map<String, Object> args = new LinkedHashMap<>();
                args.put("folder", e.path(folder));
                args.put("seed", a.num("seed", 7));
                args.put("rounds", a.num("rounds", 6));
                args.put("gradients", !a.flags().containsKey("no-gradients"));
                if (a.flags().containsKey("only")) {
                    args.put("only", a.flags().get("only"));
                }
                final Map<String, Object> r = e.call("xf_fuzz", args, (f, m) -> AppLogger.stream("  " + m)).get();
                if (Boolean.TRUE.equals(r.get("ok"))) {
                    AppLogger.success(G4Json.str(r, "verdict", ""));
                } else {
                    AppLogger.error(G4Json.str(r, "verdict", ""));
                }
                if (r.get("defects") instanceof List<?> defects) {
                    defects.stream().limit(30).forEach(d -> AppLogger.raw("    " + d));
                }
                for (BridgeConformance.Result x : List.of(BridgeConformance.java(folder), BridgeConformance.cpp(folder, new SettingsManager()))) {
                    if (x.ok()) {
                        AppLogger.success(x.verdict());
                    } else if (x.files() == 0) {
                        AppLogger.warn(x.verdict());
                    } else {
                        AppLogger.error(x.verdict());
                        x.defects().stream().limit(30).forEach(d -> AppLogger.raw("    " + d));
                    }
                }
            } catch (Exception ex) {
                AppLogger.error("ml fuzz: " + MlCommands.failure(ex));
            }
        });
    }

    private static void xgrad(String input, CommandExecutionContext ctx) {
        MlCommands.background("ml-xgrad", () -> {
            try {
                AppLogger.info("Every framework through every other (their imports take a while)...");
                final MlEngine e = MlCommands.engine("torch");
                final Map<String, Object> r = e.call("xgrad_check", Map.of(), (f, m) -> AppLogger.stream(m)).get();
                final String verdict = G4Json.str(r, "verdict", "") + String.format(Locale.ROOT, " (%.0f s)", G4Json.num(r, "seconds", 0));
                if (Boolean.TRUE.equals(r.get("ok"))) {
                    AppLogger.success(verdict);
                } else {
                    AppLogger.error(verdict);
                }
            } catch (Exception ex) {
                AppLogger.error("ml xgrad: " + MlCommands.failure(ex));
            }
        });
    }

    private static void twin(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml twin");
        if (a.words().size() < 2 || !List.of("torch", "tensorflow", "jax").contains(a.word(1, ""))) {
            Handlers.usage(":ml twin <model> <torch|tensorflow|jax> [--out file] [--shape 16,7;16]");
            return;
        }
        final Path given = Handlers.resolve(a.word(0, "")).toPath().toAbsolutePath();
        final Path model = Files.exists(given) ? given : MlCommands.models().resolve(a.word(0, "") + ".sng.spx");
        if (!Files.exists(model)) {
            AppLogger.error("No model " + given);
            return;
        }
        MlCommands.background("ml-twin", () -> {
            try {
                final MlEngine e = MlCommands.engine("torch");
                final Map<String, Object> args = new LinkedHashMap<>();
                args.put("model", e.path(model));
                args.put("to", a.word(1, "torch"));
                if (a.flags().containsKey("out")) {
                    args.put("out", e.path(Handlers.resolve(a.flags().get("out")).toPath().toAbsolutePath()));
                }
                if (a.flags().containsKey("shape")) {
                    final List<List<Integer>> shapes = new ArrayList<>();
                    for (String row : a.flags().get("shape").split(";")) {
                        shapes.add(java.util.Arrays.stream(row.split(",")).map(String::strip).filter(t -> !t.isEmpty()).map(Integer::valueOf).toList());
                    }
                    args.put("shapes", shapes);
                }
                final Map<String, Object> r = e.call("twin", args, (f, m) -> AppLogger.stream("  " + m)).get();
                AppLogger.success(G4Json.str(r, "verdict", ""));
            } catch (Exception ex) {
                AppLogger.error("ml twin: " + MlCommands.failure(ex));
            }
        });
    }

    private static Path graphPath(String name) {
        if (name.endsWith(".spx") || name.contains("/") || name.contains("\\")) {
            return Handlers.resolve(name).toPath();
        }
        return MlCommands.models().resolve(name + ".sng.spx");
    }

    // ------------------------------------------------------------------
    // :ml xf
    // ------------------------------------------------------------------

    private static void xf(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml xf");
        if (a.words().isEmpty()) {
            Handlers.usage(":ml xf <graph> [dataset] [--rows 8] [--engines torch,jax,tensorflow] [--noexport] [--nobench]");
            return;
        }
        MlCommands.background("ml-xf", () -> {
            try {
                final Path gp = graphPath(a.word(0, ""));
                if (!Files.exists(gp)) {
                    AppLogger.error("No graph " + gp + " (':torch export', ':tf import' make them)");
                    return;
                }
                final Path ds = a.words().size() > 1 ? MlCommands.dataset(a.word(1, "")) : null;
                final MlEngine e = MlCommands.engine("torch");
                final Map<String, Object> args = new LinkedHashMap<>();
                args.put("graph", e.path(gp));
                if (ds != null) {
                    args.put("data", e.path(ds));
                }
                args.put("rows", a.num("rows", 8));
                if (a.flags().containsKey("engines")) {
                    args.put("engines", List.of(a.flags().get("engines").split(",")));
                }
                args.put("exports", !a.flags().containsKey("noexport"));
                args.put("bench", !a.flags().containsKey("nobench"));
                AppLogger.info("The graph in PyTorch, JAX and TensorFlow (TensorFlow's import takes a while; ':ml cancel' stops it)...");
                final Map<String, Object> r = e.call("xf_check", args, (f, m) -> AppLogger.stream(m)).get();
                AppLogger.raw(String.format(Locale.ROOT, "  %s: %d nodes of %d operations, %d weights (%d trainable tensors), %d rows",
                    G4Json.str(r, "graph", "?"), (int) G4Json.num(r, "nodes", 0), (int) G4Json.num(r, "ops", 0),
                    (int) G4Json.num(r, "weights", 0), (int) G4Json.num(r, "trainable", 0), (int) G4Json.num(r, "rows", 0)));
                final Map<String, Object> eng = G4Json.obj(r, "engines"), gr = G4Json.obj(r, "gradients");
                for (String k : eng.keySet()) {
                    final Map<String, Object> x = G4Json.obj(eng, k);
                    final Map<String, Object> gx = G4Json.obj(gr, k);
                    AppLogger.raw(x.containsKey("error") ? "  " + k + ": " + x.get("error") : String.format(Locale.ROOT,
                        "  %-11s outputs within %5.1f epsilon of numpy's%s", k, G4Json.num(x, "outputs_eps", Double.NaN),
                        gx.containsKey("eps") ? String.format(Locale.ROOT, ", gradients within %.1f of %s's", G4Json.num(gx, "eps",
                            Double.NaN), G4Json.str(gx, "vs", "?")) : k.equals(G4Json.str(gr, "base", "")) ? ", the gradients' reference"
                            : ""));
                }
                final Map<String, Object> fd = G4Json.obj(gr, "finite_differences");
                if (!fd.isEmpty()) {
                    AppLogger.raw(String.format(Locale.ROOT, "  %s's gradient against finite differences of numpy's reference: %.1e (%d"
                        + " random directions of the weights and inputs)", G4Json.str(fd, "base", "?"), G4Json.num(fd, "relative",
                        Double.NaN), (int) G4Json.num(fd, "directions", 0)));
                }
                final Map<String, Object> ex = G4Json.obj(r, "exports");
                for (String k : ex.keySet()) {
                    final Map<String, Object> x = G4Json.obj(ex, k);
                    AppLogger.raw(x.containsKey("error") ? "  " + k + ": " + x.get("error") : String.format(Locale.ROOT,
                        "  %-12s %s, reloaded: within %.1f epsilon", k, G4Json.str(x, "path", ""), G4Json.num(x, "eps", Double.NaN)));
                }
                final Map<String, Object> timing = G4Json.obj(r, "timing");
                final StringBuilder fastest = new StringBuilder();
                if (!timing.isEmpty()) {
                    final NeuralGraph g = NeuralGraph.load(gp);
                    for (String b : timing.keySet()) {
                        final Map<String, Double> row = new LinkedHashMap<>();
                        final Map<String, Object> t = G4Json.obj(timing, b);
                        for (String k : t.keySet()) {
                            row.put(k, G4Json.num(t, k, Double.NaN));
                        }
                        final String java = javaTiming(g, ds, Integer.parseInt(b), row);
                        final StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "  batch %4s, ms a call:", b));
                        String best = null;
                        for (Map.Entry<String, Double> en : row.entrySet()) {
                            line.append(String.format(Locale.ROOT, " %s %.3f", en.getKey(), en.getValue()));
                            if (best == null || en.getValue() < row.get(best)) {
                                best = en.getKey();
                            }
                        }
                        AppLogger.raw(line + (java.isEmpty() ? "" : "  (" + java + ")"));
                        fastest.append(fastest.length() == 0 ? "" : ", ").append("at a batch of ").append(b).append(": ").append(best);
                    }
                }
                AppLogger.success(G4Json.str(r, "verdict", "").replaceAll("; fastest at a batch.*$", "")
                    + (fastest.length() > 0 ? "; the fastest engine, Java's counted, " + fastest : "")
                    + String.format(Locale.ROOT, " (%.0f s)", G4Json.num(r, "seconds", 0)));
            } catch (Exception ex) {
                AppLogger.error("ml xf: " + MlCommands.failure(ex));
            }
        });
    }

    /** Java's interpreter and its bytecode, timed on the graph at a batch, into row; what they agree to. */
    private static String javaTiming(NeuralGraph g, Path ds, int batch, Map<String, Double> row) {
        try {
            final List<NeuralGraph.Tensor> in = inputs(g, ds, batch);
            final int n = batch == 1 ? 200 : 5;
            g.run(in);
            row.put("java", best(() -> g.run(in), n));
            final GraphCompiler.CompiledGraph cg = GraphCompiler.compile(g, in, true);
            final long bits = cg.check(in);
            row.put("java_jit", best(() -> cg.run(in), n));
            return bits == 0 ? "Java's bytecode the interpreter's to the bit" : bits + " values of Java's bytecode differ";
        } catch (Exception ex) {
            return "Java: " + MlCommands.failure(ex);
        }
    }

    /**
     * The best of three timings of n calls, after a warm-up of 30 calls or half a second: a compiled graph is a
     * hidden class of as many methods as kernels, which HotSpot compiles one by one as they warm -- timed cold,
     * a graph of a hundred kernels runs slower than the interpreter, whose code every graph has already warmed.
     */
    private static double best(Runnable f, int n) {
        final long until = System.nanoTime() + 500_000_000L;
        for (int i = 0; i < 30 || System.nanoTime() < until; i++) {
            f.run();
            if (i >= 300) {
                break;
            }
        }
        double best = Double.POSITIVE_INFINITY;
        for (int rep = 0; rep < 3; rep++) {
            final long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                f.run();
            }
            best = Math.min(best, (System.nanoTime() - t0) / 1e6 / n);
        }
        return best;
    }

    /** A dataset's first rows for the graph's inputs, or normal draws of the example's shapes (masks all ones). */
    private static List<NeuralGraph.Tensor> inputs(NeuralGraph g, Path ds, int batch) throws Exception {
        if (ds != null) {
            final List<NeuralGraph.Tensor> all = GraphTrainer.data(g, ds, batch).inputs();
            if (GraphTrainer.rowsOf(all) >= batch) {
                return GraphTrainer.rows(all, 0, batch);
            }
        }
        final int[][] ex = g.exampleShapes();
        final Random rng = new Random(11);
        final List<NeuralGraph.Tensor> out = new ArrayList<>();
        for (int k = 0; k < g.inputNames.size(); k++) {
            final int[] s = k < ex.length && ex[k].length > 0 ? ex[k].clone() : new int[]{batch};
            s[0] = batch;
            int size = 1;
            for (int d : s) {
                size *= d;
            }
            final double[] v = new double[size];
            final boolean mask = g.inputNames.get(k).toLowerCase(Locale.ROOT).contains("mask");
            for (int i = 0; i < size; i++) {
                v[i] = mask ? 1.0 : rng.nextGaussian();
            }
            out.add(new NeuralGraph.Tensor(v, s));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // :ml flow
    // ------------------------------------------------------------------

    private static Map<String, Object> flowArgs(MlCommands.Args a, List<String> ints) {
        final Map<String, Object> args = new LinkedHashMap<>();
        for (String k : ints) {
            if (a.flags().containsKey(k)) {
                args.put(k, a.num(k, 0));
            }
        }
        if (a.flags().containsKey("noframeworks")) {
            args.put("frameworks", false);
        }
        return args;
    }

    private static void nle(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml flow nle");
        MlCommands.background("ml-flow-nle", () -> {
            try {
                final MlEngine e = MlCommands.engine("torch");
                AppLogger.info("A normalizing flow learns the W's likelihood from simulations (a few minutes; ':ml cancel' stops it)...");
                final Map<String, Object> r = e.call("flow_nle", flowArgs(a, List.of("samples", "epochs", "layers", "bins", "hidden",
                    "experiments", "events", "calibration", "seed")), (f, m) -> AppLogger.stream(m)).get();
                final Map<String, Object> fl = G4Json.obj(r, "flow"), x = G4Json.obj(r, "exactness");
                AppLogger.raw(String.format(Locale.ROOT, "  the flow: %d couplings of %d bins, %d weights, %d simulated events, %.0f s;"
                        + " log|det J| within %.1e of the Jacobian's determinant, its inverse within %.1e", (int) G4Json.num(fl, "layers", 0),
                    (int) G4Json.num(fl, "bins", 0), (int) G4Json.num(fl, "weights", 0), (int) G4Json.num(fl, "samples", 0),
                    G4Json.num(fl, "seconds", 0), G4Json.num(x, "logdet_vs_jacobian", Double.NaN), G4Json.num(x, "inverse_roundtrip",
                        Double.NaN)));
                AppLogger.raw(String.format(Locale.ROOT, "  its distance to the exact density: %.4f +- %.4f nats an event (KL); its"
                    + " log-likelihood a quartic in the mass to %.1e an event", G4Json.num(r, "kl_nats", Double.NaN), G4Json.num(r, "kl_err",
                    Double.NaN), G4Json.num(r, "quartic_fit_rms", Double.NaN)));
                final Map<String, Object> inf = G4Json.obj(r, "inference");
                for (String k : inf.keySet()) {
                    final Map<String, Object> v = G4Json.obj(inf, k);
                    AppLogger.raw(String.format(Locale.ROOT, "    %-8s bias %+6.1f +- %4.1f MeV, resolution %5.1f, interval %5.1f, coverage"
                            + " %.3f; against the exact %+6.1f +- %4.1f (%.1f apart)", k, G4Json.num(v, "bias_mev", Double.NaN),
                        G4Json.num(v, "bias_err_mev", Double.NaN), G4Json.num(v, "rms_mev", Double.NaN), G4Json.num(v, "sigma_mev",
                            Double.NaN), G4Json.num(v, "coverage", Double.NaN), G4Json.num(v, "shift_vs_exact_mev", Double.NaN),
                        G4Json.num(v, "shift_vs_exact_err_mev", Double.NaN), G4Json.num(v, "rms_vs_exact_mev", Double.NaN)));
                }
                graphs(G4Json.obj(r, "graphs"));
                AppLogger.success(G4Json.str(r, "verdict", "") + String.format(Locale.ROOT, " (%.0f s)", G4Json.num(r, "seconds", 0)));
            } catch (Exception ex) {
                AppLogger.error("ml flow nle: " + MlCommands.failure(ex));
            }
        });
    }

    private static void fastsim(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml flow fastsim");
        MlCommands.background("ml-flow-fastsim", () -> {
            try {
                final MlEngine e = MlCommands.engine("torch");
                AppLogger.info("A normalizing flow learns the jets' detector (a few minutes; ':ml cancel' stops it)...");
                final Map<String, Object> r = e.call("flow_fastsim", flowArgs(a, List.of("jets", "epochs", "layers", "bins", "hidden",
                    "seed")), (f, m) -> AppLogger.stream(m)).get();
                final Map<String, Object> fl = G4Json.obj(r, "flow"), x = G4Json.obj(r, "exactness");
                AppLogger.raw(String.format(Locale.ROOT, "  the flow: %d couplings, %d weights, %d jets, %.0f s; log|det J| within %.1e of"
                        + " the Jacobian's determinant, its inverse within %.1e", (int) G4Json.num(fl, "layers", 0),
                    (int) G4Json.num(fl, "weights", 0), (int) G4Json.num(fl, "jets", 0), G4Json.num(fl, "seconds", 0),
                    G4Json.num(x, "logdet_vs_jacobian", Double.NaN), G4Json.num(x, "inverse_roundtrip", Double.NaN)));
                AppLogger.raw("    observable          flow    floor  no detector   (triangular discrimination to the full detector, x1000)");
                final Map<String, Object> d = G4Json.obj(r, "discrimination");
                for (String k : d.keySet()) {
                    final Map<String, Object> v = G4Json.obj(d, k);
                    AppLogger.raw(String.format(Locale.ROOT, "    %-18s %6.2f %8.2f %12.2f", G4Json.str(v, "label", k), G4Json.num(v, "flow",
                        Double.NaN), G4Json.num(v, "floor", Double.NaN), G4Json.num(v, "no_detector", Double.NaN)));
                }
                graphs(G4Json.obj(r, "graphs"));
                AppLogger.success(G4Json.str(r, "verdict", "") + String.format(Locale.ROOT, " (%.0f s)", G4Json.num(r, "seconds", 0)));
            } catch (Exception ex) {
                AppLogger.error("ml flow fastsim: " + MlCommands.failure(ex));
            }
        });
    }

    /** The flow's graphs: the frameworks' agreement, then the sampler run by Java against PyTorch, and timed. */
    private static void graphs(Map<String, Object> g) {
        if (g.isEmpty()) {
            return;
        }
        final Map<String, Object> fw = G4Json.obj(g, "frameworks"), gr = G4Json.obj(g, "gradients");
        if (!fw.isEmpty()) {
            final StringBuilder b = new StringBuilder("  its log-density in");
            for (String k : fw.keySet()) {
                b.append(String.format(Locale.ROOT, " %s %s", k, fw.get(k) instanceof Number n ? String.format(Locale.ROOT, "%.1f", n.doubleValue())
                    : String.valueOf(fw.get(k))));
            }
            b.append(" epsilon of numpy's");
            if (!gr.isEmpty()) {
                b.append("; gradients within ");
                for (String k : gr.keySet()) {
                    b.append(String.format(Locale.ROOT, "%s %.1f ", k, G4Json.num(gr, k, Double.NaN)));
                }
                b.append("epsilon of PyTorch's");
            }
            AppLogger.raw(b.toString());
        }
        final Map<String, Object> s = G4Json.obj(g, "sampler");
        if (s.containsKey("error") || !s.containsKey("path")) {
            AppLogger.warn("the sampler as a graph: " + s.getOrDefault("error", "not written"));
            return;
        }
        try {
            final NeuralGraph sg = NeuralGraph.load(Path.of(G4Json.str(s, "path", "")));
            final List<?> ins = G4Json.list(s, "inputs");
            final List<NeuralGraph.Tensor> in = new ArrayList<>();
            for (Object o : ins) {
                in.add(matrix((List<?>) o));
            }
            final NeuralGraph.Tensor ref = matrix(G4Json.list(s, "output"));
            final double[] got = sg.run(in).get(0).data;
            double worst = 0, scale = 0;
            for (int i = 0; i < got.length; i++) {
                worst = Math.max(worst, Math.abs(got[i] - ref.data[i]));
                scale = Math.max(scale, Math.abs(ref.data[i]));
            }
            final GraphCompiler.CompiledGraph cg = GraphCompiler.compile(sg, in, true);
            final long bits = cg.check(in);
            // a batch of 256 new draws: Java's sampling, timed
            final int b = 256, dim = in.get(0).shape[1], cdim = in.get(1).shape[1];
            final Random rng = new Random(5);
            final double[] z = new double[b * dim], c = new double[b * cdim];
            for (int i = 0; i < z.length; i++) {
                z[i] = rng.nextGaussian();
            }
            for (int i = 0; i < c.length; i++) {
                c[i] = in.get(1).data[i % in.get(1).data.length];
            }
            final List<NeuralGraph.Tensor> big = List.of(new NeuralGraph.Tensor(z, b, dim), new NeuralGraph.Tensor(c, b, cdim));
            final GraphCompiler.CompiledGraph cb = GraphCompiler.compile(sg, big, true);
            final double interp = best(() -> sg.run(big), 3), jit = best(() -> cb.run(big), 3);
            AppLogger.raw(String.format(Locale.ROOT, "  its sampler in Java: within %.1f epsilon of PyTorch's; as bytecode %d values"
                    + " differing from the interpreter's; %.1f us a draw interpreted, %.1f compiled (batches of %d)", worst / Math.max(
                    Math.ulp(1.0) * Math.max(scale, 1e-300), 1e-300), bits, 1e3 * interp / b, 1e3 * jit / b, b));
        } catch (Exception ex) {
            AppLogger.warn("the sampler in Java: " + MlCommands.failure(ex));
        }
    }

    private static NeuralGraph.Tensor matrix(List<?> rows) {
        final int n = rows.size();
        final int m = ((List<?>) rows.get(0)).size();
        final double[] v = new double[n * m];
        for (int i = 0; i < n; i++) {
            final List<?> r = (List<?>) rows.get(i);
            for (int j = 0; j < m; j++) {
                v[i * m + j] = ((Number) r.get(j)).doubleValue();
            }
        }
        return new NeuralGraph.Tensor(v, n, m);
    }
}

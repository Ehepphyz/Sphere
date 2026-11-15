package com.sphere.core.commands;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.Spx;
import com.sphere.core.geantbackend.G4Json;
import com.sphere.core.ml.GraphAutograd;
import com.sphere.core.ml.GraphTrainer;
import com.sphere.core.ml.MlAutopilot;
import com.sphere.core.ml.MlEngine;
import com.sphere.core.ml.MlShm;
import com.sphere.core.ml.NeuralGraph;
import com.sphere.core.ml.NeuralNet;
import com.sphere.utils.AppLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Learning at the frontier, in Sphere's own terms:
 * <ul>
 * <li>:torch grad, :torch gradcheck, :torch train -- PyTorch's autograd and optimizers in Java (GraphAutograd,
 * GraphTrainer): any exported model of PyTorch, Keras or TensorFlow trained without them, its gradients held to
 * PyTorch's own and to finite differences;</li>
 * <li>:ml lorentz -- the Lorentz invariant networks of the zoo (LorentzNet, PELICAN) and their symmetries measured;</li>
 * <li>:ml optimize -- a network trained for the analysis's expected CLs through pyhf's model, its systematics and its
 * Monte Carlo statistics (sphere_dana), against the same network trained to classify;</li>
 * <li>:ml shm -- Java's jets handed to numpy, PyTorch, JAX and TensorFlow through a shared arena, no copy;</li>
 * <li>:ml need, :ml auto -- what a task needs, identified, and the engine that does it best, chosen and run.</li>
 * </ul>
 */
public final class LearnCommands {

    private LearnCommands() {
    }

    static void register() {
        CommandDefinitions.register(":torch grad", "PyTorch's gradients and optimizer steps against Java's autograd: a script's model exported"
            + " with its weights trainable, PyTorch's own gradients of a loss (and 3 steps of Adam, AdamW, SGD) written, Java's computed on"
            + " the graph and held to them. Usage: :torch grad <script.py|graph> [--rows 8] [--steps 3]", LearnCommands::grad);
        CommandDefinitions.register(":torch gradcheck", "Java's gradients of a graph against finite differences of its own forward,"
            + " no Python. Usage: :torch gradcheck <graph> [dataset] [--samples 40]", LearnCommands::gradcheck);
        CommandDefinitions.register(":torch train", "Train (fine-tune) a graph in Java: autograd, SGD/Adam/AdamW as torch.optim's, micro-batches"
            + " planned to the memory, on threads; the best epoch written as a new graph. Usage: :torch train <graph> <dataset> [--optimizer"
            + " adam|adamw|sgd] [--lr x] [--epochs n] [--batch b] [--wd x] [--momentum x] [--nesterov] [--schedule cosine|linear] [--clip x]"
            + " [--val 0.2] [--rows N] [--memory MB] [--threads n] [--loss cross_entropy|bce|mse] [--name n]", LearnCommands::train);
        CommandDefinitions.register(":ml lorentz", "A network's symmetries measured: its logits under random Lorentz boosts and rotations of"
            + " the jets, and under permutations of their particles. Usage: :ml lorentz <model> <dataset> [--jets 64] [--rapidity 2]"
            + " [--transforms 6]", LearnCommands::lorentz);
        CommandDefinitions.register(":ml optimize", "Train a network for the analysis itself: the expected CLs (mu=1) through pyhf's model --"
            + " normsys, histosys from the jet energy scale, staterror -- differentiated (soft bins, the envelope theorem), against the"
            + " same network trained to classify; both judged by pyhf. Usage: :ml optimize <dataset> [model] [--target cls|z0] [--signal S]"
            + " [--background B] [--norm x] [--jes x] [--bins K] [--bandwidth h] [--epochs n] [--pretrain n] [--name n]", LearnCommands::optimize);
        CommandDefinitions.register(":ml optimize check", "The analysis's loss and gradient in PyTorch, JAX (and TensorFlow) against each other,"
            + " numpy and finite differences; its CLs against pyhf's. Usage: :ml optimize check <dataset> [model] [--backends torch,jax]"
            + " [--target cls|z0] [--rows 128]", LearnCommands::optimizeCheck);
        CommandDefinitions.register(":ml shm probe", "The shared tensor arena: a tensor Java writes, read by numpy, PyTorch, JAX (and"
            + " TensorFlow with --tf) where it is -- each checked to share the memory. Usage: :ml shm probe [--tf]", LearnCommands::shmProbe);
        CommandDefinitions.register(":ml shm tag", "A network of the zoo on a dataset's jets through the arena: Java writes them, PyTorch"
            + " computes, Java reads the logits back and holds them to its own. Usage: :ml shm tag <model> <dataset> [--jets 256]"
            + " [--precision float32|float64]", LearnCommands::shmTag);
        CommandDefinitions.register(":ml need", "What a task needs, identified: the machine, the frameworks, the model's operations and memory,"
            + " the dataset; the engine to use, why, what is missing. Usage: :ml need [status|infer|deploy|frameworks|train|finetune|"
            + "uncertainty|analysis|sbi|unfold|automl|flow|fastsim|lorentz|bridge] [graph|network|dataset|script ...]", LearnCommands::need);
        CommandDefinitions.register(":ml auto", "Do what ':ml need' plans: the engine chosen, its first command run, the next ones listed."
            + " Usage: :ml auto <intent> <targets...>", LearnCommands::auto);
        CommandDefinitions.register(":torch jit", "A graph compiled to JVM bytecode (fused loops, strided views, planned buffers), held to the"
            + " interpreter to the bit on a dataset's jets, and timed against it. Usage: :torch jit <graph> <dataset> [--batch 1] [--nofold]",
            LearnCommands::jit);
        CommandDefinitions.register(":ml uncertainty", "How much a graph's predictions can be trusted: Laplace's approximation from its"
            + " Gauss-Newton curvature (Lanczos), conformal sets of guaranteed coverage, jets out of distribution. Usage: :ml uncertainty"
            + " <graph> <dataset> [--ood dataset] [--alpha 0.1] [--rank 20] [--rows N]", LearnCommands::uncertainty);
        FrontierCommands.register();
        FrameworkCommands.register();
    }

    private static void jit(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":torch jit");
        if (a.words().size() < 2) {
            Handlers.usage(":torch jit <graph> <dataset> [--batch 1] [--nofold]");
            return;
        }
        MlCommands.background("torch-jit", () -> {
            try {
                final NeuralGraph g = NeuralGraph.load(model(a.word(0, "")));
                final int batch = a.num("batch", 1), reps = Math.max(1, 512 / batch);
                final List<NeuralGraph.Tensor> all = GraphTrainer.data(g, MlCommands.dataset(a.word(1, "")), batch * reps).inputs();
                final int rows = GraphTrainer.rowsOf(all) / batch * batch;
                final var cg = com.sphere.core.ml.GraphCompiler.compile(g, GraphTrainer.rows(all, 0, batch), !a.flags().containsKey("nofold"));
                AppLogger.raw("  " + cg.report());
                long bits = 0;
                for (int lo = 0; lo + batch <= rows; lo += batch) {
                    bits += cg.check(GraphTrainer.rows(all, lo, lo + batch));
                }
                long t0 = System.nanoTime();
                for (int lo = 0; lo + batch <= rows; lo += batch) {
                    g.run(GraphTrainer.rows(all, lo, lo + batch), Double.POSITIVE_INFINITY, 1, null);
                }
                final double interp = (System.nanoTime() - t0) / 1e6;
                t0 = System.nanoTime();
                for (int lo = 0; lo + batch <= rows; lo += batch) {
                    cg.run(GraphTrainer.rows(all, lo, lo + batch));
                }
                final double jitMs = (System.nanoTime() - t0) / 1e6;
                final String timing = String.format(Locale.ROOT, "%d jets in batches of %d: interpreter %.1f ms, compiled %.1f ms (x%.1f)", rows,
                    batch, interp, jitMs, interp / jitMs);
                if (bits == 0) {
                    AppLogger.success("Compiled " + g.name + ": the interpreter's outputs to the bit; " + timing);
                } else {
                    AppLogger.warn(bits + " output values differ from the interpreter's; " + timing);
                }
            } catch (Exception ex) {
                AppLogger.error("torch jit: " + MlCommands.failure(ex));
            }
        });
    }

    private static void uncertainty(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml uncertainty");
        if (a.words().size() < 2) {
            Handlers.usage(":ml uncertainty <graph> <dataset> [--ood dataset] [--alpha 0.1] [--rank 20] [--rows N]");
            return;
        }
        MlCommands.background("ml-uncertainty", () -> {
            try {
                final com.sphere.core.ml.GraphUncertainty.Options o = new com.sphere.core.ml.GraphUncertainty.Options();
                o.alpha = a.real("alpha", 0.1);
                o.rank = a.num("rank", 20);
                o.maxRows = a.num("rows", -1);
                if (a.flags().containsKey("ood")) {
                    o.ood = MlCommands.dataset(a.flags().get("ood"));
                }
                final var r = com.sphere.core.ml.GraphUncertainty.analyze(NeuralGraph.load(model(a.word(0, ""))), MlCommands.dataset(
                    a.word(1, "")), o, AppLogger::stream);
                AppLogger.raw(String.format(Locale.ROOT, "  Laplace: %d weights, %d Lanczos steps, leading curvatures %s, prior %.3g (by the held-out"
                        + " likelihood), %.1f effective parameters; NLL %.4f -> %.4f, ECE %.4f -> %.4f, Brier %.4f -> %.4f", r.weights(), r.lanczos(),
                    Arrays.toString(Arrays.copyOf(r.eigenvalues(), Math.min(4, r.eigenvalues().length))), r.delta(), r.effectiveParameters(),
                    r.nllBefore(), r.nllAfter(), r.eceBefore(), r.eceAfter(), r.brierBefore(), r.brierAfter()));
                AppLogger.raw(String.format(Locale.ROOT, "  conformal sets at alpha %.2g: coverage %.3f (signal %.3f, background %.3f; %.3f"
                        + " guaranteed), mean size %.2f, %.0f %% decisive", r.alpha(), r.coverage(), r.coverageSignal(), r.coverageBackground(),
                    1 - r.alpha(), r.setSize(), 100 * r.singletons()));
                for (Map.Entry<String, Double> e : r.ood().entrySet()) {
                    AppLogger.raw(String.format(Locale.ROOT, "  out of distribution by %s: AUROC %.3f", e.getKey(), e.getValue()));
                }
                AppLogger.success(String.format(Locale.ROOT, "Uncertainty of %s on %d jets in %.1f s", a.word(0, ""), r.rows(), r.seconds()));
            } catch (Exception ex) {
                AppLogger.error("ml uncertainty: " + MlCommands.failure(ex));
            }
        });
    }

    private static void log(List<String> lines) {
        for (String l : lines) {
            AppLogger.raw(l);
        }
    }

    private static Path model(String name) {
        final Path p = MlAutopilot.resolve(name);
        return p == null ? Path.of(name) : p;
    }

    // ------------------------------------------------------------------
    // :torch grad, gradcheck, train
    // ------------------------------------------------------------------

    private static void grad(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":torch grad");
        if (a.words().isEmpty()) {
            Handlers.usage(":torch grad <script.py|graph> [--rows 8] [--steps 3]");
            return;
        }
        final String target = a.word(0, "");
        MlCommands.background("torch-grad", () -> {
            try {
                Path graph, ref;
                if (target.endsWith(".py")) {
                    final Path script = Handlers.resolve(target).toPath();
                    final MlEngine e = MlCommands.engine("torch");
                    final Map<String, Object> args = new LinkedHashMap<>();
                    args.put("kind", "script");
                    args.put("script", e.path(script));
                    args.put("gradients", true);
                    args.put("rows", a.num("rows", 8));
                    args.put("steps", a.num("steps", 3));
                    if (a.flags().containsKey("name")) {
                        args.put("name", a.flags().get("name"));
                    }
                    AppLogger.info("Exporting " + script.getFileName() + " with its weights trainable, and PyTorch's own gradients...");
                    final Map<String, Object> r = e.call("graph_export", args, (f, m) -> AppLogger.stream("  " + m)).get();
                    final String name = G4Json.str(r, "name", "");
                    graph = MlCommands.models().resolve(name + ".sng.spx");
                    ref = Bridge.folder().resolve("ml").resolve(name + ".gradref.spx");
                    final Map<String, Object> gr = G4Json.obj(r, "gradients");
                    AppLogger.raw(String.format(Locale.ROOT, "  PyTorch: loss %s = %.10g on %d rows, |grad| %.4g over %d weights in %d tensors",
                        G4Json.str(gr, "kind", "?"), G4Json.num(gr, "loss", Double.NaN), (int) G4Json.num(gr, "rows", 0),
                        G4Json.num(gr, "grad_norm", Double.NaN), (int) G4Json.num(gr, "weights", 0), (int) G4Json.num(gr, "params", 0)));
                } else {
                    graph = model(target);
                    final NeuralGraph g0 = NeuralGraph.load(graph);
                    ref = Bridge.folder().resolve("ml").resolve(g0.name + ".gradref.spx");
                    if (!Files.isRegularFile(ref)) {
                        AppLogger.warn("No PyTorch reference for " + g0.name + " (':torch grad <its script.py>' writes one): finite differences"
                            + " instead");
                        gradcheck(":torch gradcheck " + graph, ctx);
                        return;
                    }
                }
                final NeuralGraph g = NeuralGraph.load(graph);
                final GraphTrainer.ReferenceCheck rc = GraphTrainer.checkReference(g, ref);
                AppLogger.raw(String.format(Locale.ROOT, "  Java: loss %.10g (PyTorch %.10g), outputs within %.2g of their scale", rc.javaLoss(),
                    rc.torchLoss(), rc.outputs()));
                AppLogger.raw(String.format(Locale.ROOT, "  gradients of %d weights: within %.2g of the gradient's scale (largest at %s)", rc.weights(),
                    rc.gradients(), rc.gradWhere()));
                for (GraphTrainer.OptimizerCheck o : rc.optimizers()) {
                    AppLogger.raw(String.format(Locale.ROOT, "  %-60s after %d steps: weights within %.2g of their scale", o.describe(), rc.steps(),
                        o.relative()));
                }
                if (!rc.zeroBySymmetry().isEmpty()) {
                    AppLogger.raw("  zero by a symmetry of the model (their gradient is rounding in both engines; Adam's steps on it apart): "
                        + String.join(", ", rc.zeroBySymmetry()));
                }
                final double eps = rc.epsUnits();
                if (eps < 64) {
                    AppLogger.success(String.format(Locale.ROOT, "Java's autograd is PyTorch's: %.1f double epsilons at most, %.0f ms", eps,
                        rc.millis()));
                } else {
                    AppLogger.warn(String.format(Locale.ROOT, "Java and PyTorch differ by %.3g double epsilons (a float32 region of the model"
                        + " rounds its gradients in PyTorch)", eps));
                }
            } catch (Exception ex) {
                AppLogger.error("torch grad: " + MlCommands.failure(ex));
            }
        });
    }

    /** Inputs for a graph: a dataset's first rows by its feeds, or the export's reference inputs. */
    private static List<NeuralGraph.Tensor> inputs(NeuralGraph g, Path data, int rows) throws Exception {
        if (data != null) {
            return GraphTrainer.data(g, data, rows).inputs();
        }
        final Path ref = Bridge.folder().resolve("ml").resolve(g.name + ".graphref.spx");
        try (Spx.Reader r = new Spx.Reader(ref)) {
            final Map<String, String> meta = r.meta();
            final List<NeuralGraph.Tensor> in = new ArrayList<>();
            for (int k = 0; k < g.inputNames.size(); k++) {
                final String s = meta.getOrDefault("Shape.in." + k, "");
                final int[] shape = s.isBlank() ? new int[0] : Arrays.stream(s.split(",")).mapToInt(v -> Integer.parseInt(v.strip())).toArray();
                in.add(new NeuralGraph.Tensor(r.f64("in." + k), shape));
            }
            return in;
        }
    }

    private static void gradcheck(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":torch gradcheck");
        if (a.words().isEmpty()) {
            Handlers.usage(":torch gradcheck <graph> [dataset] [--samples 40]");
            return;
        }
        try {
            final NeuralGraph g = NeuralGraph.load(model(a.word(0, "")));
            final Path data = a.words().size() > 1 ? MlCommands.dataset(a.word(1, "")) : null;
            final GraphAutograd ad = new GraphAutograd(g);
            final GraphAutograd.Coverage c = ad.coverage();
            AppLogger.raw("  " + g.name + ": " + c.differentiated().values().stream().mapToInt(Integer::intValue).sum() + " operations"
                + " differentiated (" + c.differentiated().size() + " kinds), " + c.constant().values().stream().mapToInt(Integer::intValue).sum()
                + " carrying no gradient" + (c.control() > 0 ? ", " + c.control() + " loops or conditions" : "") + "; " + ad.trainable()
                + " values trained" + (ad.guessed ? " (guessed: the graph was exported frozen)" : ""));
            if (!c.complete()) {
                AppLogger.error("  no gradient here for: " + String.join(", ", c.missing()));
                return;
            }
            final GraphAutograd.Check ch = ad.gradcheck(inputs(g, data, 4), a.num("samples", 40), 11);
            final String verdict = String.format(Locale.ROOT, "%d weights: largest relative difference %.2g at %s (autograd %.8g, differences %.8g)",
                ch.compared(), ch.worst(), ch.where(), ch.autograd(), ch.numeric());
            if (ch.passed(1e-5)) {
                AppLogger.success("Java's gradients hold to finite differences: " + verdict);
            } else {
                AppLogger.warn(verdict + " -- a ReLU's kink crossed by the step shows as one large difference; several mean an error");
            }
        } catch (Exception ex) {
            AppLogger.error("torch gradcheck: " + MlCommands.failure(ex));
        }
    }

    private static void train(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":torch train");
        if (a.words().size() < 2) {
            Handlers.usage(":torch train <graph> <dataset> [--optimizer adam|adamw|sgd] [--lr x] [--epochs n] [--batch b] [--wd x]"
                + " [--momentum x] [--nesterov] [--schedule cosine|linear] [--clip x] [--val 0.2] [--rows N] [--memory MB] [--threads n]"
                + " [--loss cross_entropy|bce|mse] [--name n]");
            return;
        }
        MlCommands.background("torch-train", () -> {
            try {
                final NeuralGraph g = NeuralGraph.load(model(a.word(0, "")));
                final Path data = MlCommands.dataset(a.word(1, ""));
                final GraphTrainer.Options o = new GraphTrainer.Options();
                o.optimizer = a.flags().getOrDefault("optimizer", "adam");
                o.epochs = a.num("epochs", 5);
                o.batch = a.num("batch", 128);
                o.threads = a.num("threads", 0);
                o.maxRows = a.num("rows", -1);
                o.val = a.real("val", 0.2);
                o.clip = a.real("clip", 0);
                o.memory = a.real("memory", 0) * 1048576.0;
                o.schedule = a.flags().getOrDefault("schedule", "constant");
                o.name = a.flags().get("name");
                if (a.flags().containsKey("loss")) {
                    o.loss = GraphTrainer.Loss.of(a.flags().get("loss"));
                }
                if (a.flags().containsKey("lr")) {
                    o.hyper.put("lr", a.real("lr", 1e-3));
                }
                if (a.flags().containsKey("wd")) {
                    o.hyper.put("weight_decay", a.real("wd", 0));
                }
                if (a.flags().containsKey("momentum")) {
                    o.hyper.put("momentum", a.real("momentum", 0.9));
                }
                if (a.flags().containsKey("nesterov")) {
                    o.hyper.put("nesterov", 1.0);
                }
                final GraphTrainer.Result r = GraphTrainer.train(g, data, o, AppLogger::stream, () -> false);
                final GraphTrainer.Epoch best = r.best() > 0 ? r.epochs().get(r.best() - 1) : null;
                AppLogger.success(String.format(Locale.ROOT, "%s trained in Java: %d weights, %d epochs, best %d%s -> %s", g.name, r.weights(),
                    r.epochs().size(), r.best(), best == null ? "" : String.format(Locale.ROOT, " (val_loss %.5f, val_auc %.4f)", best.valLoss(),
                        best.valAuc()), r.path()));
                AppLogger.raw("  the graph runs as the first did: ':torch run', ':torch cpp', ':ml tag' take it");
            } catch (Exception ex) {
                AppLogger.error("torch train: " + MlCommands.failure(ex));
            }
        });
    }

    // ------------------------------------------------------------------
    // :ml lorentz, optimize
    // ------------------------------------------------------------------

    private static void lorentz(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml lorentz");
        if (a.words().size() < 2) {
            Handlers.usage(":ml lorentz <model> <dataset> [--jets 64] [--rapidity 2] [--transforms 6]");
            return;
        }
        MlCommands.background("ml-lorentz", () -> {
            try {
                final MlEngine e = MlCommands.engine("numpy");
                final Map<String, Object> args = new LinkedHashMap<>();
                final Path m = model(a.word(0, ""));
                args.put("model", Files.isRegularFile(m) ? e.path(m) : a.word(0, ""));
                args.put("data", e.path(MlCommands.dataset(a.word(1, ""))));
                args.put("rows", a.num("jets", 64));
                args.put("rapidity", a.real("rapidity", 2.0));
                args.put("transforms", a.num("transforms", 6));
                final Map<String, Object> r = e.call("lorentz", args).get();
                AppLogger.raw(String.format(Locale.ROOT, "  %s (%s): %d jets, %d Lorentz transformations (gamma up to %.2f): logits moved by %.2g of"
                        + " their scale; particles permuted: %.2g", G4Json.str(r, "name", "?"), G4Json.str(r, "model", "?"),
                    (int) G4Json.num(r, "jets", 0), (int) G4Json.num(r, "transforms", 0), G4Json.num(r, "max_gamma", 0),
                    G4Json.num(r, "lorentz", Double.NaN), G4Json.num(r, "permutation", Double.NaN)));
                if (Boolean.TRUE.equals(r.get("invariant"))) {
                    AppLogger.success(G4Json.str(r, "verdict", ""));
                } else {
                    AppLogger.warn(G4Json.str(r, "verdict", ""));
                }
            } catch (Exception ex) {
                AppLogger.error("ml lorentz: " + MlCommands.failure(ex));
            }
        });
    }

    private static Map<String, Object> danaArgs(MlCommands.Args a, MlEngine e, String command) {
        final Map<String, Object> args = new LinkedHashMap<>();
        args.put("data", e.path(MlCommands.dataset(a.word(0, ""))));
        args.put("model", a.word(1, "deepsets"));
        for (String k : List.of("target", "name", "engine")) {
            if (a.flags().containsKey(k)) {
                args.put(k, a.flags().get(k));
            }
        }
        for (String k : List.of("signal", "background", "norm", "jes", "bandwidth", "lr")) {
            if (a.flags().containsKey(k)) {
                args.put(k, a.real(k, 0));
            }
        }
        for (String k : List.of("bins", "epochs", "pretrain", "batch", "seed", "points", "rows", "fd")) {
            if (a.flags().containsKey(k)) {
                args.put(k, a.num(k, 0));
            }
        }
        if (a.flags().containsKey("backends")) {
            args.put("backends", List.of(a.flags().get("backends").split(",")));
        }
        return args;
    }

    private static void optimize(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml optimize");
        if (a.words().isEmpty()) {
            Handlers.usage(":ml optimize <dataset> [model] [--target cls|z0] [--signal S] [--background B] [--norm x] [--jes x] [--bins K]"
                + " [--bandwidth h] [--epochs n] [--pretrain n] [--name n] [--engine torch|tensorflow]");
            return;
        }
        MlCommands.background("ml-optimize", () -> {
            try {
                final MlEngine e = MlCommands.engine("torch");
                AppLogger.info("Training " + a.word(1, "deepsets") + " for the expected CLs, and the same network to classify, from one start"
                    + " (':ml cancel' stops it)...");
                final Map<String, Object> r = e.call("dana_optimize", danaArgs(a, e, "optimize"), (f, m) -> AppLogger.stream(m)).get();
                for (String k : List.of("analysis", "bce")) {
                    final Map<String, Object> x = G4Json.obj(r, k);
                    if (x.isEmpty()) {
                        continue;
                    }
                    AppLogger.raw(String.format(Locale.ROOT, "  %-9s expected CLs %.4g (pyhf %.4g), AUC %.4f, nuisances pulled: norm %.3f, jes %.3f",
                        k.equals("analysis") ? "analysis" : "classify", G4Json.num(x, "cls", Double.NaN), G4Json.num(x, "pyhf_cls", Double.NaN),
                        G4Json.num(x, "auc", Double.NaN), G4Json.num(x, "a_norm", 0), G4Json.num(x, "a_jes", 0)));
                }
                AppLogger.success(G4Json.str(r, "verdict", "") + "; the network: " + G4Json.str(r, "name", "?") + " (':ml tag', ':ml audit')");
            } catch (Exception ex) {
                AppLogger.error("ml optimize: " + MlCommands.failure(ex));
            }
        });
    }

    private static void optimizeCheck(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml optimize check");
        if (a.words().isEmpty()) {
            Handlers.usage(":ml optimize check <dataset> [model] [--backends torch,jax,tensorflow] [--target cls|z0] [--rows 128]");
            return;
        }
        MlCommands.background("ml-optimize-check", () -> {
            try {
                final MlEngine e = MlCommands.engine("torch");
                final Map<String, Object> r = e.call("dana_check", danaArgs(a, e, "check")).get();
                final Map<String, Object> fw = G4Json.obj(r, "frameworks");
                AppLogger.raw(String.format(Locale.ROOT, "  %s, target %s, %d jets, %d weights; numpy's loss %.15g", G4Json.str(r, "model", "?"),
                    G4Json.str(r, "target", "?"), (int) G4Json.num(r, "rows", 0), (int) G4Json.num(r, "weights", 0),
                    G4Json.num(r, "numpy_loss", Double.NaN)));
                for (Map.Entry<String, Object> en : fw.entrySet()) {
                    final Map<String, Object> f = G4Json.obj(fw, en.getKey());
                    AppLogger.raw(f.containsKey("error") ? "  " + en.getKey() + ": " + f.get("error") : String.format(Locale.ROOT,
                        "  %-10s loss within %.2g of numpy's, gradient within %.2g of PyTorch's (%.1f s)", en.getKey(),
                        G4Json.num(f, "loss_vs_numpy", Double.NaN), G4Json.num(f, "grad_vs_torch", Double.NaN), G4Json.num(f, "seconds", 0)));
                }
                final Map<String, Object> fd = G4Json.obj(r, "finite_differences");
                if (!fd.isEmpty()) {
                    AppLogger.raw(String.format(Locale.ROOT, "  finite differences at %d weights, the profile fitted again at each: median %.2g,"
                            + " worst %.2g (%s)", (int) G4Json.num(fd, "weights", 0), G4Json.num(fd, "median_relative", Double.NaN),
                        G4Json.num(fd, "worst_relative", Double.NaN), G4Json.str(fd, "worst_at", "")));
                }
                final Map<String, Object> ph = G4Json.obj(r, "pyhf");
                if (!ph.isEmpty()) {
                    AppLogger.raw(ph.containsKey("error") ? "  pyhf: " + ph.get("error") : String.format(Locale.ROOT,
                        "  expected CLs %.10g, pyhf's %.10g: within %.2g", G4Json.num(ph, "cls", Double.NaN), G4Json.num(ph, "pyhf_cls",
                            Double.NaN), G4Json.num(ph, "relative", Double.NaN)));
                }
            } catch (Exception ex) {
                AppLogger.error("ml optimize check: " + MlCommands.failure(ex));
            }
        });
    }

    // ------------------------------------------------------------------
    // :ml shm
    // ------------------------------------------------------------------

    private static Path arenaFolder() {
        return MlEngine.folder().resolve("shm");
    }

    private static void shmProbe(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml shm probe");
        MlCommands.background("ml-shm", () -> {
            try (MlShm shm = MlShm.create(arenaFolder(), 8L << 20)) {
                final MlEngine e = MlCommands.engine("torch");
                final double[] v = new double[256 * 32];
                for (int i = 0; i < v.length; i++) {
                    v[i] = Math.sin(i);
                }
                shm.put("probe", v, MlShm.DType.FLOAT64, 256, 32);
                final List<String> fws = new ArrayList<>(List.of("numpy", "torch", "jax"));
                if (a.flags().containsKey("tf")) {
                    fws.add("tensorflow");
                }
                final Map<String, Object> args = new LinkedHashMap<>(shm.describe());
                args.put("path", e.path(shm.file()));
                args.put("slot", "probe");
                args.put("frameworks", fws);
                final Map<String, Object> r = e.call("shm_probe", args).get();
                AppLogger.raw("  " + shm + "; slot probe " + r.get("shape") + " " + G4Json.str(r, "dtype", "") + ", 64-byte aligned: "
                    + r.get("aligned_64"));
                final Map<String, Object> fw = G4Json.obj(r, "frameworks");
                boolean all = true;
                for (Map.Entry<String, Object> en : fw.entrySet()) {
                    final Map<String, Object> f = G4Json.obj(fw, en.getKey());
                    if (Boolean.FALSE.equals(f.get("available"))) {
                        AppLogger.raw("  " + en.getKey() + ": not installed");
                        continue;
                    }
                    final boolean zc = Boolean.TRUE.equals(f.get("zero_copy"));
                    all &= zc;
                    AppLogger.raw(String.format(Locale.ROOT, "  %-10s %s%s", en.getKey(), f.containsKey("error") ? "error: " + f.get("error")
                        : zc ? "reads Java's memory itself (a sentinel written by Java's side of the mapping, read through its tensor)"
                        : "copied", f.get("address_matches") instanceof Boolean b ? (b ? ", its data pointer the arena's" : ", another address")
                        : ""));
                }
                e.call("shm_detach", Map.of("path", e.path(shm.file()))).get();
                if (all) {
                    AppLogger.success("Zero copy between Java and every framework present: the arena is their memory");
                } else {
                    AppLogger.warn("A framework copied: it gets the data, not the memory");
                }
            } catch (Exception ex) {
                AppLogger.error("ml shm probe: " + MlCommands.failure(ex));
            }
        });
    }

    private static void shmTag(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml shm tag");
        if (a.words().size() < 2) {
            Handlers.usage(":ml shm tag <model> <dataset> [--jets 256] [--precision float32|float64]");
            return;
        }
        MlCommands.background("ml-shm-tag", () -> {
            try {
                final Path mp = model(a.word(0, ""));
                final NeuralNet net = NeuralNet.load(mp);
                final Path data = MlCommands.dataset(a.word(1, ""));
                final int jets;
                final double[] x, mask;
                final int points, F = net.features();
                try (Spx.Reader r = new Spx.Reader(data)) {
                    points = Integer.parseInt(r.meta().getOrDefault("Points", "0"));
                    final double[] xa = r.f64("x"), ma = r.f64("mask");
                    jets = Math.min(a.num("jets", 256), ma.length / Math.max(1, points));
                    x = Arrays.copyOf(xa, jets * points * F);
                    mask = Arrays.copyOf(ma, jets * points);
                }
                final long bytes = (long) jets * points * (F + 1) * 8 + (long) jets * 16 + (1 << 20);
                try (MlShm shm = MlShm.create(arenaFolder(), bytes)) {
                    final MlEngine e = MlCommands.engine("torch");
                    final long t0 = System.nanoTime();
                    shm.put("x", x, MlShm.DType.FLOAT64, jets, points, F);
                    shm.put("mask", mask, MlShm.DType.FLOAT64, jets, points);
                    final MlShm.Slot out = shm.allocate("logits", MlShm.DType.FLOAT64, jets, net.classes.size());
                    final double writeMs = (System.nanoTime() - t0) / 1e6;
                    final Map<String, Object> args = new LinkedHashMap<>();
                    args.put("path", e.path(shm.file()));
                    args.put("model", e.path(mp));
                    args.put("x", "x");
                    args.put("mask", "mask");
                    args.put("out", "logits");
                    args.put("engine", "torch");
                    args.put("precision", a.flags().getOrDefault("precision", "float64"));
                    final long t1 = System.nanoTime();
                    final Map<String, Object> r = e.call("shm_predict", args).get();
                    final double callMs = (System.nanoTime() - t1) / 1e6;
                    final double[] z = shm.read(out);
                    e.call("shm_detach", Map.of("path", e.path(shm.file()))).get();
                    final long t2 = System.nanoTime();
                    double worst = 0, scale = 0;
                    final int P = net.points();
                    final double[][] jx = new double[P][F];
                    final double[] jm = new double[P];
                    final int c = net.classes.size();
                    for (int j = 0; j < jets; j++) {
                        for (int i = 0; i < P; i++) {
                            for (int f = 0; f < F; f++) {
                                jx[i][f] = i < points ? x[(j * points + i) * F + f] : 0;
                            }
                            jm[i] = i < points ? mask[j * points + i] : 0;
                        }
                        final double[] mine = net.logits(jx, jm, null);
                        for (int k = 0; k < c; k++) {
                            worst = Math.max(worst, Math.abs(mine[k] - z[j * c + k]));
                            scale = Math.max(scale, Math.abs(mine[k]));
                        }
                    }
                    final double javaMs = (System.nanoTime() - t2) / 1e6;
                    AppLogger.raw(String.format(Locale.ROOT, "  %d jets: Java wrote them in %.1f ms; PyTorch (%s) read them in place, its forward"
                            + " %.1f ms, its logits written back in %.2f ms (%.1f ms the round trip); Java's own NeuralNet %.1f ms", jets, writeMs,
                        G4Json.str(r, "precision", "?"), G4Json.num(r, "forward_ms", 0), G4Json.num(r, "write_ms", 0), callMs, javaMs));
                    final double eps = worst / Math.max(scale, 1e-300) / 0x1.0p-52;
                    final String verdict = String.format(Locale.ROOT, "PyTorch's logits through the arena against Java's: %.3g of their scale (%.1f"
                        + " double epsilons)", worst / Math.max(scale, 1e-300), eps);
                    if ("float64".equals(G4Json.str(r, "precision", "")) ? eps < 4096 : worst / Math.max(scale, 1e-300) < 1e-4) {
                        AppLogger.success(verdict);
                    } else {
                        AppLogger.warn(verdict);
                    }
                }
            } catch (Exception ex) {
                AppLogger.error("ml shm tag: " + MlCommands.failure(ex));
            }
        });
    }

    // ------------------------------------------------------------------
    // :ml need, :ml auto
    // ------------------------------------------------------------------

    private static MlAutopilot.Plan plan(String input, String command) {
        final MlCommands.Args a = MlCommands.Args.of(input, command);
        final String intent = a.words().isEmpty() ? "status" : a.word(0, "status");
        final List<String> targets = a.words().size() > 1 ? a.words().subList(1, a.words().size()) : List.of();
        return MlAutopilot.plan(intent, targets, a.flags(), G4Commands.settings());
    }

    private static void need(String input, CommandExecutionContext ctx) {
        MlCommands.background("ml-need", () -> {
            try {
                final MlAutopilot.Plan p = plan(input, ":ml need");
                for (String c : MlAutopilot.capabilities(p)) {
                    AppLogger.raw("  " + c);
                }
                log(MlAutopilot.describe(p));
                if (p.runnable()) {
                    AppLogger.success("':ml auto " + Handlers.args(input, ":ml need").strip() + "' runs it");
                }
            } catch (Exception ex) {
                AppLogger.error("ml need: " + MlCommands.failure(ex));
            }
        });
    }

    private static void auto(String input, CommandExecutionContext ctx) {
        MlCommands.background("ml-auto", () -> {
            try {
                final MlAutopilot.Plan p = plan(input, ":ml auto");
                log(MlAutopilot.describe(p));
                if (!p.runnable()) {
                    AppLogger.warn("Nothing run: what blocks is above");
                    return;
                }
                final String first = p.run().get(0);
                final CommandDefinitions.CommandInfo info = CommandDefinitions.find(first);
                if (info == null) {
                    AppLogger.error("no command for " + first);
                    return;
                }
                AppLogger.info("Running " + first);
                info.handler.accept(first, ctx);
                if (p.run().size() > 1) {
                    AppLogger.raw("  then: " + String.join("  ;  ", p.run().subList(1, p.run().size())));
                }
            } catch (Exception ex) {
                AppLogger.error("ml auto: " + MlCommands.failure(ex));
            }
        });
    }
}

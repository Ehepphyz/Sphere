package com.sphere.core.ml;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.Spx;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What a learning task needs, identified before anything runs: the machine (its cores, its memory, the instruction
 * set HotSpot found on its CPU), the Pythons and their frameworks (with the engines already running, whose imports
 * are paid), and the task's object itself -- a graph (which of its operations Java runs and differentiates, whether
 * its weights are the model's, what a row costs forward and on the tape), a network of the zoo (which runtime runs
 * it), a dataset (its jets, whether they carry what a Lorentz invariant network reads, its labels, its size against
 * the memory). From them, an engine for the intent -- Java where Java can (no interpreter to start, no import to wait
 * for), the framework that does it best otherwise -- the commands that do it, why, and what is missing with what to do
 * about it. ':ml need' shows the plan, ':ml auto' runs it.
 */
public final class MlAutopilot {

    private MlAutopilot() {
    }

    /** The machine, as the JVM sees it. */
    public record Machine(int cores, double ramMb, double freeMb, double heapMb, double budgetMb, String os, String cpu,
                          int avx, boolean fma, boolean nativeAccess) {
        public String isa() {
            if (avx < 0) {
                return "unknown";
            }
            return switch (avx) {
                case 0 -> "SSE only";
                case 1 -> "AVX";
                case 2 -> "AVX2" + (fma ? "+FMA" : "");
                default -> "AVX-512" + (fma ? "+FMA" : "");
            } + (fma && avx < 2 ? "+FMA" : "");
        }
    }

    /** Something a task needs and has not, why, and what to do. */
    public record Need(String what, String why, String remedy) {
    }

    /** An intent's plan: the engine chosen, the commands to run (in order), the reasons, the needs, the facts found. */
    public record Plan(String intent, String engine, List<String> run, List<String> reasons, List<Need> needs,
                       Map<String, Object> facts) {
        public boolean runnable() {
            return !run.isEmpty() && needs.stream().noneMatch(n -> n.what().startsWith("!"));
        }
    }

    private static String vmOption(String name) {
        try {
            final com.sun.management.HotSpotDiagnosticMXBean hs = ManagementFactory.getPlatformMXBean(
                com.sun.management.HotSpotDiagnosticMXBean.class);
            return hs.getVMOption(name).getValue();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The machine now: what HotSpot found on the CPU (UseAVX, UseFMA) tells the vector width a kernel gets. */
    public static Machine machine() {
        final int cores = Runtime.getRuntime().availableProcessors();
        double ram = -1, free = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            ram = os.getTotalMemorySize() / 1048576.0;
            free = os.getFreeMemorySize() / 1048576.0;
        }
        final String avx = vmOption("UseAVX"), fma = vmOption("UseFMA");
        boolean nativeAccess;
        try {
            nativeAccess = MlAutopilot.class.getModule().isNativeAccessEnabled();
        } catch (RuntimeException e) {
            nativeAccess = false;
        }
        final String cpu = System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", System.getProperty("os.arch"));
        return new Machine(cores, ram, free, Runtime.getRuntime().maxMemory() / 1048576.0, NeuralGraph.budget() / 1048576.0,
            System.getProperty("os.name") + " " + System.getProperty("os.version"), cpu, avx == null ? -1 : Integer.parseInt(avx),
            "true".equals(fma), nativeAccess);
    }

    /** The frameworks each Python holds, and which interpreter is best for each (MlLocator's probes: nothing imported). */
    static Map<String, Object> pythons(Function<String, String> settings) {
        final Map<String, Object> out = new LinkedHashMap<>();
        try {
            final List<MlLocator.Interpreter> all = MlLocator.find(settings, false);
            final List<String> seen = new ArrayList<>();
            for (MlLocator.Interpreter i : all) {
                seen.add(i.describe());
            }
            out.put("pythons", seen);
            for (String f : List.of("torch", "tensorflow", "keras", "jax", "pyhf")) {
                final MlLocator.Interpreter best = MlLocator.best(settings, f);
                out.put(f, best == null ? null : best.version(f));
            }
        } catch (RuntimeException e) {
            out.put("error", e.getMessage());
        }
        final List<String> running = new ArrayList<>();
        for (MlEngine e : MlEngine.running()) {
            running.add(e.description());
        }
        out.put("engines_running", running);
        return out;
    }

    /** What a file is: "graph", "zoo", "dataset", "script", or "" (missing or other). */
    static String kind(Path p) {
        if (p == null || !Files.isRegularFile(p)) {
            return "";
        }
        final String n = p.getFileName().toString();
        if (n.endsWith(".py")) {
            return "script";
        }
        if (n.endsWith(".pb") || n.endsWith(".keras") || n.endsWith(".h5")) {
            return "tensorflow";
        }
        try (Spx.Reader r = new Spx.Reader(p)) {
            final String k = r.meta().getOrDefault("Kind", "");
            return switch (k) {
                case "sphere-graph" -> "graph";
                case "sphere-nn" -> "zoo";
                case "ml-dataset" -> "dataset";
                default -> k;
            };
        } catch (Exception e) {
            return "";
        }
    }

    /** A name resolved the way Sphere's commands resolve it: a path, a graph or network of models/, a dataset. */
    public static Path resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        final Path direct = Path.of(name);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        final Path models = Bridge.folder().resolve("models");
        for (Path p : List.of(models.resolve(name + ".sng.spx"), models.resolve(name + ".snn.spx"), Bridge.folder().resolve(name + ".spx"),
            Bridge.folder().resolve(name))) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return direct;
    }

    /** What a graph is to the engines: Java forward, Java gradients, the model's weights, the memory of a row. */
    static Map<String, Object> graphFacts(Path p, List<String> reasons, List<Need> needs) {
        final Map<String, Object> f = new LinkedHashMap<>();
        try {
            final NeuralGraph g = NeuralGraph.load(p);
            f.put("graph", g.name);
            f.put("operations", g.nodes());
            f.put("weights", g.weights());
            f.put("feeds", g.feeds);
            f.put("framework", g.framework);
            f.put("java_forward", true);
            f.put("rows_independent", g.rowsIndependent());
            f.put("forward_mb_per_row", g.perRowPeak() / 1048576.0);
            final GraphAutograd ad = new GraphAutograd(g);
            final GraphAutograd.Coverage c = ad.coverage();
            f.put("java_gradients", c.complete());
            f.put("trainable", g.trainable());
            f.put("trained_values", ad.trainable());
            if (!c.complete()) {
                needs.add(new Need("! gradients of " + String.join(", ", c.missing()), "Java's autograd has no rule for them",
                    "train in PyTorch (':ml train', or the model's own script)"));
            }
            if (!g.trainable()) {
                needs.add(new Need("the model's own weights", g.name + " was exported frozen: its weights folded (a transpose of"
                    + " a Linear's weight is what Java would train)", "export it again: ':torch export <script.py>' keeps them"));
            }
            final int[][] ex = g.exampleShapes();
            if (ex.length == g.inputNames.size() && ex.length > 0) {
                final List<NeuralGraph.Tensor> sample = new ArrayList<>();
                for (int[] s : ex) {
                    final int[] shape = s.clone();
                    if (shape.length > 0) {
                        shape[0] = Math.min(2, Math.max(1, shape[0]));
                    }
                    final double[] d = new double[NeuralGraph.numel(shape)];
                    java.util.Arrays.fill(d, 0.5);
                    sample.add(new NeuralGraph.Tensor(d, shape));
                }
                try {
                    final GraphTrainer.TapePlan tp = GraphTrainer.plan(ad, sample);
                    f.put("tape_mb_per_row", tp.perRow() / 1048576.0);
                } catch (RuntimeException e) {
                    f.put("tape_mb_per_row", null);
                }
            }
            reasons.add(String.format(Locale.ROOT, "%s: %d operations, %d weights, all run by Java%s", g.name, g.nodes(), g.weights(),
                c.complete() ? " and differentiated by it" : ""));
        } catch (Exception e) {
            f.put("java_forward", false);
            f.put("error", e.getMessage());
            needs.add(new Need("! a graph Java runs", String.valueOf(e.getMessage()), "run the model in its framework, or add the"
                + " operations named to NeuralGraph"));
        }
        return f;
    }

    static Map<String, Object> zooFacts(Path p, List<String> reasons) {
        final Map<String, Object> f = new LinkedHashMap<>();
        try {
            final NeuralNet n = NeuralNet.load(p);
            f.put("network", n.name);
            f.put("model", n.model);
            f.put("trained_with", n.trainedWith);
            f.put("java", true);
            f.put("cpp", true);   // sphere_nn.hpp runs the seven networks of the zoo
            f.put("lorentz_invariant", List.of("lorentznet", "pelican").contains(n.model));
            reasons.add(n.name + ": " + n.model + " of the zoo, run by Java's NeuralNet" + (List.of("lorentznet", "pelican").contains(n.model)
                ? " (Lorentz invariant by construction)" : ""));
        } catch (Exception e) {
            f.put("java", false);
            f.put("error", e.getMessage());
        }
        return f;
    }

    static Map<String, Object> datasetFacts(Path p, Machine m, List<String> reasons, List<Need> needs) {
        final Map<String, Object> f = new LinkedHashMap<>();
        try (Spx.Reader r = new Spx.Reader(p)) {
            final Map<String, String> meta = r.meta();
            final long jets = Long.parseLong(meta.getOrDefault("Jets", meta.getOrDefault("Rows", "0")));
            final String feats = meta.getOrDefault("Features", "");
            f.put("dataset", p.getFileName().toString());
            f.put("jets", jets);
            f.put("points", Integer.parseInt(meta.getOrDefault("Points", "0")));
            f.put("features", feats);
            f.put("high_level", meta.getOrDefault("HighLevel", ""));
            f.put("labels", r.has("y"));
            final boolean four = feats.contains("log_pt") && meta.getOrDefault("Coords", "4,5").split(",").length == 2;
            f.put("four_momenta", four);
            long bytes = 0;
            for (Spx.Section s : r.sections()) {
                bytes += s.bytes();
            }
            f.put("mb", bytes / 1048576.0);
            if (bytes / 1048576.0 > 0.5 * m.budgetMb()) {
                needs.add(new Need("memory for the whole dataset", String.format(Locale.ROOT, "%.0f MB of jets against %.0f MB to spend",
                    bytes / 1048576.0, m.budgetMb()), "--rows N to take the first N, or a larger JVM heap (-Xmx)"));
            }
            if (!r.has("y")) {
                needs.add(new Need("! labels", p.getFileName() + " has no y", "':ml jets <class>' per class, then ':ml jets save'"));
            }
            reasons.add(String.format(Locale.ROOT, "%s: %d jets of %s particles, %s%s", p.getFileName(), jets, meta.getOrDefault("Points", "?"),
                r.has("y") ? "labeled" : "unlabeled", four ? ", their four-momenta known (pt, eta, phi): Lorentz invariant networks apply" : ""));
        } catch (Exception e) {
            f.put("error", e.getMessage());
        }
        return f;
    }

    /**
     * The plan of an intent on its targets. Intents: status, infer, train, finetune, analysis, lorentz, bridge. The
     * targets are names or paths (a graph, a network of the zoo, a dataset, a script), in any order.
     */
    public static Plan plan(String intent, List<String> targets, Map<String, String> flags, Function<String, String> settings) {
        final Machine m = machine();
        final List<String> reasons = new ArrayList<>(), run = new ArrayList<>();
        final List<Need> needs = new ArrayList<>();
        final Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("machine", m);
        final Map<String, Object> py = pythons(settings);
        facts.put("python", py);
        reasons.add(String.format(Locale.ROOT, "this machine: %d cores, %s, %.0f MB of RAM (%.0f free), %.0f MB a run may spend now",
            m.cores(), m.isa(), m.ramMb(), m.freeMb(), m.budgetMb()));
        Path graph = null, zoo = null, data = null, script = null;
        String zooName = null;
        for (String t : targets) {
            final Path p = resolve(t);
            switch (kind(p)) {
                case "graph" -> graph = p;
                case "zoo" -> zoo = p;
                case "dataset" -> data = p;
                case "script" -> script = p;
                default -> {
                    if (List.of("mlp", "deepsets", "efn", "particlenet", "transformer", "lorentznet", "pelican").contains(t)) {
                        zooName = t;
                    } else {
                        needs.add(new Need("! " + t, "neither a graph, a network, a dataset nor a script Sphere finds", "a path, or a name"
                            + " of models/ or of the bridge folder"));
                    }
                }
            }
        }
        if (graph != null) {
            facts.put("graph", graphFacts(graph, reasons, needs));
        }
        if (zoo != null) {
            facts.put("network", zooFacts(zoo, reasons));
        }
        if (data != null) {
            facts.put("dataset", datasetFacts(data, m, reasons, needs));
        }
        final boolean torch = py.get("torch") != null, jax = py.get("jax") != null, keras = py.get("keras") != null;
        final boolean pyhf = py.get("pyhf") != null, tf = py.get("tensorflow") != null;
        @SuppressWarnings("unchecked")
        final List<String> engines = (List<String>) py.getOrDefault("engines_running", List.of());
        final String engine;
        switch (intent) {
            case "status" -> {
                engine = "-";
                reasons.add("Java runs every graph and every network of the zoo, and differentiates every operation it runs:"
                    + " inference and fine-tuning need no Python");
                reasons.add("Java compiles a graph to bytecode, the same to the bit and faster (':torch jit'), and measures how far"
                    + " its predictions can be trusted, to second order (':ml uncertainty')");
                reasons.add(torch ? "PyTorch " + py.get("torch") + " trains the zoo, exports graphs, and checks Java's gradients"
                    : "no PyTorch: the zoo trains on Keras, graphs come from TensorFlow's files (':tf import')");
                if (tf) {
                    reasons.add("TensorFlow " + py.get("tensorflow") + " is here; ':tf import' reads its models without importing it");
                }
                if (!pyhf) {
                    needs.add(new Need("pyhf", "the analysis's judge (':ml optimize', ':ml pyhf')", "pip install pyhf"));
                }
                if (!engines.isEmpty()) {
                    reasons.add("running already (no import to wait for): " + String.join("; ", engines));
                }
            }
            case "infer" -> {
                if (graph != null) {
                    engine = "java (NeuralGraph)";
                    run.add(data != null ? ":torch run " + graph + " " + data : ":ml tag " + graph);
                    reasons.add("Java runs it on " + m.cores() + " threads in the memory it plans: no interpreter started");
                    if (data != null) {
                        run.add(":torch jit " + graph + " " + data);
                        reasons.add("compiled to JVM bytecode it runs the same to the bit, 4 to 7 times faster one jet at a time"
                            + " (':ml need deploy')");
                    }
                } else if (zoo != null) {
                    engine = "java (NeuralNet)";
                    run.add(data != null ? ":ml shm tag " + zoo + " " + data : ":ml tag " + zoo);
                    reasons.add(data != null ? "Java and PyTorch both, through the shared arena: Java's scores, held to PyTorch's"
                        : "Java runs it on the loaded events");
                } else if (script != null) {
                    engine = "torch.export, then Java";
                    run.add(":torch export " + script);
                    reasons.add("a script's model becomes a graph first, then Java runs it without PyTorch");
                } else {
                    engine = "-";
                    needs.add(new Need("! a model", "nothing to run", "a graph (models/*.sng.spx), a network (models/*.snn.spx) or a script"));
                }
            }
            case "train", "finetune" -> {
                if (graph != null && data != null) {
                    @SuppressWarnings("unchecked")
                    final Map<String, Object> gf = (Map<String, Object>) facts.get("graph");
                    final boolean grads = Boolean.TRUE.equals(gf.get("java_gradients"));
                    final double tape = gf.get("tape_mb_per_row") instanceof Number n ? n.doubleValue() : 0;
                    if (grads) {
                        engine = "java (GraphAutograd + GraphTrainer)";
                        final int batch = tape > 0 ? (int) Math.max(8, Math.min(512, Math.floor(m.budgetMb() / Math.max(1, m.cores()) / tape)))
                            : 128;
                        final StringBuilder cmd = new StringBuilder(":torch train " + graph + " " + data + " --batch " + Math.min(batch,
                            Integer.parseInt(flags.getOrDefault("batch", String.valueOf(batch)))));
                        for (String k : List.of("epochs", "lr", "optimizer", "wd", "momentum", "schedule", "clip", "val", "rows", "memory",
                            "threads", "loss", "name")) {   // what ':ml auto' was told, passed on
                            if (flags.containsKey(k)) {
                                cmd.append(" --").append(k).append(' ').append(flags.get(k));
                            }
                        }
                        run.add(cmd.toString());
                        reasons.add(String.format(Locale.ROOT, "Java trains it: every operation has its gradient, a row's tape takes %.3f MB, a"
                            + " batch of %d fits %d threads", tape, batch, m.cores()));
                        if (torch) {
                            reasons.add("PyTorch can hold the gradients to its own first: ':torch grad <the model's script>'");
                        }
                    } else {
                        engine = torch ? "torch (the model's own training)" : "-";
                    }
                } else if (data != null) {
                    final String model = zooName != null ? zooName : chooseModel(facts, flags);
                    final String backend = !torch && keras ? "keras" : jax && keras && List.of("particlenet", "transformer").contains(model)
                        && !"torch".equals(flags.get("backend")) ? "keras" : "torch";
                    engine = backend.equals("keras") ? "keras on " + (jax ? "jax" : "its backend") : "torch";
                    run.add(":ml train " + data + " " + model + " " + backend + (flags.containsKey("epochs") ? " --epochs " + flags.get("epochs")
                        : ""));
                    reasons.add("the zoo's " + model + (zooName == null ? " (chosen: " + why(model, facts) + ")" : ""));
                    if (backend.equals("keras")) {
                        reasons.add("Keras on JAX compiles " + model + " with XLA: sphere_ml measured it 10x faster than Keras on PyTorch");
                    }
                    if (!torch && !keras) {
                        needs.add(new Need("! a framework to train with", "no PyTorch, no Keras", "pip install torch (or keras jax)"));
                    }
                    run.add(":ml audit <the network> " + data);
                } else {
                    engine = "-";
                    needs.add(new Need("! a dataset", "nothing to train on", "':ml toy', or ':ml jets <class>' then ':ml jets save <name>'"));
                }
            }
            case "analysis" -> {
                engine = "torch (sphere_dana), judged by pyhf";
                if (data == null) {
                    needs.add(new Need("! a dataset", "the analysis's signal and background", "':ml toy' or ':ml jets ...'"));
                } else {
                    final String model = zooName != null ? zooName : "deepsets";
                    run.add(":ml optimize check " + data + " " + model);
                    run.add(":ml optimize " + data + " " + model + (flags.containsKey("jes") ? " --jes " + flags.get("jes") : "")
                        + (flags.containsKey("norm") ? " --norm " + flags.get("norm") : ""));
                    reasons.add("the expected CLs differentiated through pyhf's model (normsys, histosys, staterror), the profile's"
                        + " gradient by the envelope theorem: checked first across frameworks and against pyhf, then trained against"
                        + " a classifier from the same start");
                }
                if (!torch) {
                    needs.add(new Need("! PyTorch", "the analysis trains with it", "pip install torch"));
                }
                if (!pyhf) {
                    needs.add(new Need("pyhf", "the judge of the result (the CLs is computed without it)", "pip install pyhf"));
                }
                if (!jax) {
                    needs.add(new Need("JAX", "the second framework the gradients are held to", "pip install jax"));
                }
            }
            case "lorentz" -> {
                engine = "numpy (the reference), then Java";
                if (zoo != null && data != null) {
                    run.add(":ml lorentz " + zoo + " " + data);
                } else if (data != null) {
                    run.add(":ml train " + data + " lorentznet " + (torch ? "torch" : "keras"));
                    run.add(":ml lorentz <the network> " + data);
                    reasons.add("no Lorentz invariant network yet: LorentzNet first, then its symmetries measured");
                } else {
                    needs.add(new Need("! a dataset", "jets to boost", "':ml toy'"));
                }
            }
            case "deploy", "jit" -> {
                if (graph != null) {
                    engine = "java (GraphCompiler: the graph as JVM bytecode)";
                    run.add(":torch jit " + graph + " " + (data != null ? data : "<dataset>"));
                    run.add(":ml xf " + graph + (data != null ? " " + data : ""));
                    reasons.add("fused elementwise loops, strided views, products and reductions as kernels, buffers planned by"
                        + " lifetime: held to the interpreter to the bit on the jets, and timed against it");
                    reasons.add("then PyTorch, JAX and TensorFlow, compiled by XLA or not, timed beside Java on this machine: the"
                        + " fastest engine that agrees with the reference, and the SavedModel and TorchScript to deploy it");
                    if (data == null) {
                        needs.add(new Need("! a dataset", "the jets to compile for, check and time on", "':ml toy'"));
                    }
                } else {
                    engine = "-";
                    needs.add(new Need("! a graph", "only portable graphs compile", "':torch export <script.py>', ':tf import ...'"));
                }
            }
            case "uncertainty", "trust" -> {
                if (graph != null && data != null) {
                    engine = "java (GraphUncertainty: second order on the graph's own tape)";
                    run.add(":ml uncertainty " + graph + " " + data);
                    reasons.add("Laplace's posterior from the Gauss-Newton curvature (Lanczos on exact products, no Python), the"
                        + " prior by the held-out likelihood, the probit; conformal sets of guaranteed coverage; out-of-distribution"
                        + " jets by three scores (give one with --ood)");
                } else {
                    engine = "-";
                    needs.add(new Need("! a graph and a dataset", "the network to judge and the jets to judge it on",
                        "a models/*.sng.spx and ':ml toy'"));
                }
            }
            case "frameworks", "convert", "xf" -> {
                if (graph != null) {
                    engine = "PyTorch, JAX and TensorFlow (sphere_xf), held to numpy and to Java";
                    run.add(":ml xf " + graph + (data != null ? " " + data : ""));
                    reasons.add("the graph replayed by torch.ops.aten, by jax.numpy, by TensorFlow's NumPy: outputs and gradients"
                        + " held to numpy's and to each other, a SavedModel, TorchScript and torch.export written and reloaded");
                    if (!torch) {
                        needs.add(new Need("! PyTorch", "the gradients' reference and the TorchScript", "pip install torch"));
                    }
                    if (!jax) {
                        needs.add(new Need("JAX", "one of the three frameworks (and XLA)", "pip install jax"));
                    }
                    if (!tf) {
                        needs.add(new Need("TensorFlow", "the SavedModel and TensorFlow's XLA", "pip install tensorflow"));
                    }
                } else {
                    engine = "-";
                    needs.add(new Need("! a graph", "the model to carry across", "':torch export <script.py>' or ':tf import ...'"));
                }
            }
            case "flow", "nle", "generative" -> {
                engine = "torch (sphere_flows), then every framework";
                run.add(":ml flow nle");
                run.add(":ml flow fastsim");
                reasons.add("rational-quadratic spline flows: densities exact by construction (checked against the Jacobian),"
                    + " the W's likelihood learned and held to the exact one, then exported and run in TensorFlow, JAX and Java");
                if (!torch) {
                    needs.add(new Need("! PyTorch", "the flows train with it", "pip install torch"));
                }
            }
            case "fastsim" -> {
                engine = "torch (sphere_flows), sampled by Java";
                run.add(":ml flow fastsim");
                reasons.add("a flow as the detector: p(detector | particle level) of the jets' observables, held to the full"
                    + " simulation, its sampler exported and run by Java");
                if (!torch) {
                    needs.add(new Need("! PyTorch", "the flow trains with it", "pip install torch"));
                }
            }
            case "sbi", "measure" -> {
                engine = "torch (sphere_sbi), held to the exact likelihood";
                run.add(":ml sbi");
                reasons.add("a W mass from a simulator alone: CARL and ALICES (the joint ratio of each event and its latent mass),"
                    + " their scores calibrated into unbiased estimating equations, judged on pseudo-experiments against the exact"
                    + " likelihood, the Cramer-Rao bound and the template fits in pT and mT");
                if (!torch) {
                    needs.add(new Need("! PyTorch", "the networks train with it", "pip install torch"));
                }
            }
            case "unfold" -> {
                engine = "torch (sphere_unfold)";
                run.add(":ml unfold");
                reasons.add("OmniFold, six observables of a jet at once and unbinned, held to IBU to the rounding in its binned"
                    + " limit, then against IBU and the truth, with the correlations IBU cannot give");
                if (!torch) {
                    needs.add(new Need("! PyTorch", "the classifiers train with it", "pip install torch"));
                }
            }
            case "automl", "search" -> {
                if (data != null) {
                    final String model = zooName != null && List.of("deepsets", "efn", "mlp").contains(zooName) ? zooName : "deepsets";
                    engine = "torch, searched by Bayesian optimization (sphere_automl)";
                    run.add(":ml automl " + data + " " + model + (flags.containsKey("dcorr") ? " --dcorr " + flags.get("dcorr") : "")
                        + (flags.containsKey("params") ? " --params " + flags.get("params") : ""));
                    run.add(":ml automl check");
                    reasons.add(String.format(Locale.ROOT, "the AUC maximized under the mass decorrelation's bound, a Gaussian process for"
                        + " each, constrained LogEI; %d cores: trials run one after the other", m.cores()));
                    if (!torch) {
                        needs.add(new Need("! PyTorch", "the trials train with it", "pip install torch"));
                    }
                } else {
                    engine = "-";
                    needs.add(new Need("! a dataset", "the jets to search a tagger for", "':ml toy'"));
                }
            }
            case "link", "connect", "program" -> {
                engine = "the gateway (sphere_mlink), started by the first program that needs it";
                run.add(":ml link status");
                run.add(":ml link check");
                reasons.add("TensorFlow, PyTorch and pyhf stay in the Python that holds them; ROOT macros, Geant4 applications, C++, Fortran, Julia and"
                    + " Python reach them by #include \"sphere_mlink.hpp\", use sphere_mlink, SphereMLink, import sphere_mlink: nothing to link or configure");
                reasons.add("a model called often is made a graph, held to its framework, and run inside the program if that is faster");
            }
            case "fuzz", "conformance", "conforms" -> {
                engine = "the PyTorch installed, as the definition; NumPy, JAX, TensorFlow, Java and C++ held to it";
                run.add(":ml fuzz");
                reasons.add("every operation of the graphs on arguments drawn from its own schema, with ties, NaN and infinities: values and gradients, then the same"
                    + " cases in Java and C++");
                reasons.add("a new version of PyTorch, or a new operation, is covered with nothing to write");
            }
            case "bridge" -> {
                engine = "the shared arena (Java FFM + mmap, DLPack)";
                run.add(":ml shm probe");
                reasons.add("Java writes tensors into a mapped file; numpy, PyTorch, JAX and TensorFlow read them where they are");
            }
            default -> {
                engine = "-";
                needs.add(new Need("! an intent", "'" + intent + "' is none of status, infer, deploy, frameworks, train, finetune,"
                    + " uncertainty, analysis, sbi, unfold, automl, flow, fastsim, lorentz, bridge, link, fuzz",
                    "':ml need status'"));
            }
        }
        return new Plan(intent, engine, run, reasons, needs, facts);
    }

    /** The zoo network for a dataset: what its jets carry decides. */
    static String chooseModel(Map<String, Object> facts, Map<String, String> flags) {
        if (flags.containsKey("model")) {
            return flags.get("model");
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> d = (Map<String, Object>) facts.getOrDefault("dataset", Map.of());
        final int points = d.get("points") instanceof Number n ? n.intValue() : 0;
        final long jets = d.get("jets") instanceof Number n ? n.longValue() : 0;
        if (points == 0) {
            return "mlp";
        }
        if (flags.containsKey("invariant") || flags.containsKey("lorentz")) {
            return "lorentznet";
        }
        return jets >= 20000 ? "particlenet" : "deepsets";
    }

    static String why(String model, Map<String, Object> facts) {
        return switch (model) {
            case "mlp" -> "the jets carry high-level features only";
            case "lorentznet" -> "a Lorentz invariant network was asked for";
            case "particlenet" -> "enough jets for EdgeConv to learn from";
            default -> "few jets: Deep Sets learns from them without overfitting";
        };
    }

    /** The plan as lines of text. */
    public static List<String> describe(Plan p) {
        final List<String> out = new ArrayList<>();
        out.add("intent: " + p.intent() + "   engine: " + p.engine());
        for (String r : p.reasons()) {
            out.add("  because " + r);
        }
        if (!p.needs().isEmpty()) {
            out.add("  needed:");
            for (Need n : p.needs()) {
                out.add("    " + (n.what().startsWith("!") ? "BLOCKING " + n.what().substring(2) : n.what()) + " -- " + n.why() + "; " + n.remedy());
            }
        }
        if (!p.run().isEmpty()) {
            out.add("  runs:");
            for (String c : p.run()) {
                out.add("    " + c);
            }
        }
        return out;
    }

    /** The machine and the frameworks as the facts a status shows. */
    public static Set<String> capabilities(Plan p) {
        final Set<String> out = new LinkedHashSet<>();
        final Machine m = (Machine) p.facts().get("machine");
        out.add(String.format(Locale.ROOT, "CPU %s, %s; %d cores; RAM %.0f MB (%.0f free); JVM heap %.0f MB; FFM native access %s", m.cpu(),
            m.isa(), m.cores(), m.ramMb(), m.freeMb(), m.heapMb(), m.nativeAccess() ? "granted" : "not granted (the arena needs none)"));
        return out;
    }
}

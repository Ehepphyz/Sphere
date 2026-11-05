package com.sphere.core.commands;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.Spx;
import com.sphere.core.bridge.SpxExport;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.contrib.EventBatch;
import com.sphere.core.geantbackend.G4Json;
import com.sphere.core.ml.MlCitations;
import com.sphere.core.ml.MlData;
import com.sphere.core.ml.MlEngine;
import com.sphere.core.ml.MlLocator;
import com.sphere.core.ml.NeuralNet;
import com.sphere.utils.AppLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * :ml -- machine learning in Sphere. PyTorch, TensorFlow (Keras, which also runs on JAX or PyTorch) and JAX to
 * train, pyhf to fit, in one engine (sphere_ml) started with the Python that holds them, found by MlLocator;
 * datasets made from the jets of Sphere's FastJet, their high-level observables from its fjcontrib; trained
 * networks run back in Sphere's Java (NeuralNet) on the loaded events, and from C++ (sphere_nn.hpp); every
 * framework, and the Java, held to one numpy reference by ':ml crosscheck'.
 */
public final class MlCommands {

    private MlCommands() {
    }

    static void register() {
        CommandDefinitions.register(":ml", "Machine learning in Sphere: PyTorch, TensorFlow/Keras, JAX and pyhf, what each command does", MlCommands::help);
        CommandDefinitions.register(":ml locate", "The Pythons Sphere can learn with and what each holds (torch, tensorflow, keras, jax, pyhf). Usage: :ml locate [wsl]", MlCommands::locate);
        CommandDefinitions.register(":ml status", "The learning engines running, the jets gathered, the networks trained", MlCommands::status);
        CommandDefinitions.register(":ml start", "Start the learning engine of a framework. Usage: :ml start [torch|keras|tensorflow|pyhf]", MlCommands::start);
        CommandDefinitions.register(":ml stop", "Stop the learning engines", MlCommands::stop);
        CommandDefinitions.register(":ml cancel", "Stop the training going on after its current batch, keeping its best epoch", MlCommands::cancel);
        CommandDefinitions.register(":ml toy", "A toy dataset of jets, W-like two-prong against QCD-like, to try everything. Usage: :ml toy [jets] [name] [--points p]", MlCommands::toy);
        CommandDefinitions.register(":ml jets", "Add the jets of the loaded events (:fjet read/toy, the active definition) to a dataset, as a class; save it. Usage: :ml jets <class> [--points 64] [--ptmin GeV] [--njets n] | :ml jets save <name> | :ml jets clear", MlCommands::jets);
        CommandDefinitions.register(":ml table", "A dataset from ROOT trees, read by Sphere's own ROOT reader: one class per tree, the branches as features (the MLP trains on it). Usage: :ml table <name> <file.root>:<tree> <class> [<file.root>:<tree> <class> ...] --features a,b,c", MlCommands::table);
        CommandDefinitions.register(":ml train","Train a network of the zoo on a dataset. Usage: :ml train <dataset> [mlp|deepsets|efn|particlenet|transformer] [torch|keras|tensorflow] [--epochs n] [--batch b] [--lr x] [--points p] [--k k] [--name n] [--precision float64] [--decorrelate mass[:lambda]]", MlCommands::train);
        CommandDefinitions.register(":ml models", "The trained networks, their architecture, framework and test AUC", MlCommands::models);
        CommandDefinitions.register(":ml crosscheck", "One network in PyTorch, Keras and Sphere's Java, in float64 and float32, held to numpy in float64 on the same jets: each difference judged by the rounding of its precision (same function, or not). Usage: :ml crosscheck <model> <dataset> [rows]", MlCommands::crosscheck);
        CommandDefinitions.register(":ml irc", "Infrared and collinear safety of a trained network, measured: its probabilities when each jet gains a particle of eps of its pt, and when its hardest particle splits in two eps apart, as eps goes to 0 -- safe if the change vanishes. Usage: :ml irc <model> <dataset> [jets]", MlCommands::irc);
        CommandDefinitions.register(":ml audit", "A trained tagger refereed as a physics analysis would: infrared and collinear safety, mass sculpting, calibration (against the statistics' floor, with the temperature that fixes it), the jet energy scale's systematic -- each with its verdict and what to do. Usage: :ml audit <model> <dataset> [--jets n] [--jes 0.02]", MlCommands::audit);
        CommandDefinitions.register(":ml calibrate", "Make P(signal) a probability: a temperature and an isotonic map fitted on jets the training never learned, judged on others by the Brier score, the better kept (none if neither helps) and written into the network for Python, Java and C++ to apply. Usage: :ml calibrate <model> <dataset> [--method auto|isotonic|temperature|none]", MlCommands::calibrate);
        CommandDefinitions.register(":ml pareto","DisCo's trade-off measured: a network trained at each lambda, its background rejection at half the signal (R50) against how little it sculpts the background's mass (1/JSD), its AUC at fixed mass, the Pareto front, and the lambda to take. Usage: :ml pareto <dataset> [model] [backend] [--lambdas 0,0.1,0.3,1,3] [--epochs 12]", MlCommands::pareto);
        CommandDefinitions.register(":ml tag", "Run a trained network -- or a graph of any PyTorch or Keras model (:torch export, :tf export) -- in Sphere's Java on the jets of the loaded events; the scores to the Plots tab. Usage: :ml tag <model|graph> [--ptmin GeV] [--njets n] [--bins b]", MlCommands::tag);
        CommandDefinitions.register(":ml pyhf", "Fit a HistFactory model with pyhf on each backend it has (NumPy and JAX; PyTorch and TensorFlow up to pyhf 0.7, which 0.8 removed): best fit, CLs, 95% upper limit, and how the backends agree. Usage: :ml pyhf <signal> <background> <bkg-uncertainty> <observed> [backends...] | :ml pyhf <workspace.json|histfactory.xml> [backends...]", MlCommands::pyhf);
        CommandDefinitions.register(":ml bib", "Write the references of PyTorch, TensorFlow, Keras, JAX, pyhf and the zoo's networks. Usage: :ml bib <file.bib>", MlCommands::bib);
    }

    /** The jets gathered by ':ml jets' until saved. */
    private static MlData.Builder pending;
    /** The engine running a training, for ':ml cancel'. */
    private static volatile MlEngine training;

    private static Function<String, String> settings() {
        return G4Commands.settings();
    }

    /** The words after a command, and its --flags as a map. */
    record Args(List<String> words, Map<String, String> flags) {
        static Args of(String input, String command) {
            final List<String> w = new ArrayList<>();
            final Map<String, String> f = new LinkedHashMap<>();
            final String[] t = Handlers.args(input, command).trim().split("\\s+");
            for (int i = 0; i < t.length; i++) {
                if (t[i].isEmpty()) {
                    continue;
                }
                if (t[i].startsWith("--")) {
                    f.put(t[i].substring(2), i + 1 < t.length && !t[i + 1].startsWith("--") ? t[++i] : "true");
                } else {
                    w.add(t[i]);
                }
            }
            return new Args(w, f);
        }

        String word(int i, String fallback) {
            return i < words.size() ? words.get(i) : fallback;
        }

        int num(String key, int fallback) {
            return flags.containsKey(key) ? Integer.parseInt(flags.get(key)) : fallback;
        }

        double real(String key, double fallback) {
            return flags.containsKey(key) ? Double.parseDouble(flags.get(key)) : fallback;
        }
    }

    static void background(String name, Runnable r) {
        final Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    static String failure(Exception e) {
        final Throwable c = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }

    /** A dataset by its name in the bridge folder, or a path. */
    static Path dataset(String name) {
        if (name.contains("/") || name.contains("\\") || name.endsWith(".spx")) {
            return Handlers.resolve(name).toPath();
        }
        return Bridge.folder().resolve(name + ".spx");
    }

    static Path models() {
        return Bridge.folder().resolve("models");
    }

    private static Path model(String name) {
        return name.endsWith(".spx") ? Handlers.resolve(name).toPath() : models().resolve(name + ".snn.spx");
    }

    /**
     * The engine for a framework: the Python holding it, its engine started when not running. "numpy" (a toy, a
     * cross-check) takes the Python with the most frameworks, so that one engine serves them all.
     */
    static MlEngine engine(String framework) throws Exception {
        MlLocator.Interpreter i = null;
        if (framework.equals("numpy")) {
            for (String f : List.of("torch", "keras", "numpy")) {
                i = i != null ? i : MlLocator.best(settings(), f);
            }
        } else {
            i = MlLocator.best(settings(), framework);
        }
        if (i == null) {
            throw new IllegalStateException(missing(framework));
        }
        return MlEngine.shared(i);
    }

    private static String missing(String framework) {
        return switch (framework) {
            case "tensorflow" -> "No Python of this machine holds TensorFlow (none has a wheel for Python 3.14 on"
                + " Windows). Keras runs the same networks on JAX or PyTorch: ':ml train ... keras'. For TensorFlow"
                + " itself, give ML_PYTHON in settings.conf a Python 3.10-3.13 with tensorflow, or install it in"
                + " WSL (pip install tensorflow), which ':ml locate wsl' then finds.";
            case "pyhf" -> "No Python holds pyhf: pip install pyhf (and torch or jax for its autodiff backends).";
            default -> "No Python holds " + framework + " (':ml locate' shows what each has). pip install "
                + (framework.equals("torch") ? "torch" : framework) + " in one of them, or name it ML_PYTHON in"
                + " settings.conf.";
        };
    }

    // ------------------------------------------------------------------
    // Overview, environments, engines
    // ------------------------------------------------------------------

    public static void help(String input, CommandExecutionContext ctx) {
        AppLogger.result("Machine learning in Sphere: PyTorch, TensorFlow/Keras, JAX and pyhf, in one engine");
        AppLogger.raw("  :ml locate [wsl]                       the Pythons and what each holds; the engine picks for you");
        AppLogger.raw("  :ml toy [jets] [name]                  a toy dataset: W-like two-prong jets against QCD-like ones");
        AppLogger.raw("  :ml jets <class> [--points 64]         the loaded events' jets as one class (:fjet read/toy first)");
        AppLogger.raw("  :ml jets save <name>                   the classes gathered, as bridge/<name>.spx");
        AppLogger.raw("  :ml table <name> <f.root>:<tree> <class> ... --features a,b   ROOT trees as a dataset (no ROOT needed)");
        AppLogger.raw("  :ml train <dataset> [model] [backend]  mlp|deepsets|efn|particlenet|transformer on torch|keras|tensorflow:");
        AppLogger.raw("                                         AUC, rejection at 30/50/70 % signal, mass sculpting, ROC to Plots");
        AppLogger.raw("        --decorrelate mass[:lambda]      DisCo: a score that forgets the background's mass (no sculpted bump)");
        AppLogger.raw("        --precision float64              double precision throughout");
        AppLogger.raw("  :ml crosscheck <model> <dataset>       numpy, PyTorch, Keras and Sphere's Java: the same function?");
        AppLogger.raw("  :ml irc <model> <dataset>              infrared and collinear safety, measured: a soft particle, a split one");
        AppLogger.raw("  :ml audit <model> <dataset>            the tagger refereed: discrimination, IRC safety, sculpting, calibration, energy scale");
        AppLogger.raw("  :ml calibrate <model> <dataset>        P(signal) made a probability (isotonic or temperature), in every engine");
        AppLogger.raw("  :ml pareto <dataset> [model]           DisCo's trade-off: rejection against 1/JSD for each lambda, the front, the pick");
        AppLogger.raw("  :ml tag <model>                        the network in Java on the loaded events' jets: scores to Plots");
        AppLogger.raw("  :ml pyhf <s> <b> <db> <n> | <ws.json> | <histfactory.xml>   pyhf on NumPy, JAX (PyTorch, TensorFlow up to 0.7)");
        AppLogger.raw("  :ml models | status | cancel | stop | bib <file.bib>");
        AppLogger.raw("  In Python (scripts, notebooks Sphere starts): import sphere_ml as ml; ml.fit(...), ml.stats(...)");
        AppLogger.raw("  In C++ (ROOT macros, Geant4 programs): #include \"sphere_nn.hpp\"; sphere::nn::Network net(\"tagger\")");
    }

    public static void locate(String input, CommandExecutionContext ctx) {
        final boolean wsl = Args.of(input, ":ml locate").words().contains("wsl");
        background("ml-locate", () -> {
            final List<MlLocator.Interpreter> all = MlLocator.find(settings(), wsl);
            if (all.isEmpty()) {
                AppLogger.warn("No Python found: name one in settings.conf (ML_PYTHON or PYTHON_EXEC)");
                return;
            }
            for (MlLocator.Interpreter i : all) {
                final boolean any = i.has("torch") || i.has("tensorflow") || i.has("keras") || i.has("jax") || i.has("pyhf");
                AppLogger.raw((any ? "  * " : "    ") + i.label() + "  " + i.describe());
            }
            for (String f : List.of("torch", "tensorflow", "keras", "pyhf")) {
                final MlLocator.Interpreter b = all.stream().filter(i -> i.has(f)).findFirst().orElse(null);
                AppLogger.raw(String.format(Locale.ROOT, "  %-11s %s", f, b == null ? "none" + (f.equals("tensorflow")
                    ? " (Keras runs the same networks on " + all.stream().filter(i -> i.has("keras")).map(i ->
                    i.version("keras_backend")).findFirst().orElse("JAX or PyTorch") + ")" : "") : b.label()));
            }
            if (!wsl) {
                AppLogger.raw("  ':ml locate wsl' looks inside WSL too, where TensorFlow advises to run it on Windows");
            }
        });
    }

    /**
     * :ml status: what this machine can learn with and where (before anything is started), the engines running,
     * the datasets and networks there are, the jets gathered -- and the command that comes next.
     */
    public static void status(String input, CommandExecutionContext ctx) {
        background("ml-status", () -> {
            final List<MlLocator.Interpreter> all = MlLocator.find(settings(), false);
            AppLogger.result("Machine learning in Sphere");
            boolean any = false;
            for (String f : List.of("torch", "keras", "tensorflow", "jax", "pyhf")) {
                final MlLocator.Interpreter i = all.stream().filter(x -> x.has(f)).findFirst().orElse(null);
                any |= i != null && !f.equals("jax");
                final String extra = i == null ? "" : switch (f) {
                    case "keras" -> " on " + i.version("keras_backend") + i.kerasFrom();
                    case "pyhf" -> " (backends " + (i.version("pyhf").startsWith("0.7") ? "numpy, pytorch, jax" : "numpy, jax")
                        + ")";
                    default -> "";
                };
                AppLogger.raw(String.format(Locale.ROOT, "  %-11s %s", f, i != null ? i.version(f) + extra + "   in "
                    + environment(i) : f.equals("tensorflow") ? "not installed (no wheel for Python 3.14 on Windows): Keras"
                    + " runs the same networks" + (all.stream().anyMatch(x -> x.has("keras")) ? " here" : "") + "; ':ml locate wsl'"
                    + " looks in WSL" : "not installed"));
            }
            // keras.json naming another backend: the user's own Keras takes it, Sphere's the fastest it has
            all.stream().filter(i -> i.has("keras_json") && i.has("keras_backend")
                    && !i.version("keras_json").equals(i.version("keras_backend"))
                    && "installed".equals(i.version("keras_backend_from"))).findFirst()
                .ifPresent(i -> AppLogger.raw("              Sphere's Keras runs on " + i.version("keras_backend") + ", your"
                    + " keras.json names " + i.version("keras_json") + " (which your own Keras scripts take)"
                    + ("jax".equals(i.version("keras_backend")) ? ": XLA compiles the networks -- ParticleNet ran 10x faster"
                    + " than on torch here, in less memory" : "") + "; ML_KERAS_BACKEND=" + i.version("keras_json")
                    + " in settings.conf makes Sphere follow it"));
            // the machine's memory, as the trainings' governor (sphere_ml.MemoryGovernor) reckons it
            if (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean os) {
                final double mb = 1024.0 * 1024.0;
                final double total = os.getTotalMemorySize() / mb, free = os.getFreeMemorySize() / mb;
                final double reserve = Math.max(400, 0.12 * total);
                int learned = 0;
                try {
                    learned = G4Json.object(Files.readString(Bridge.folder().resolve("ml").resolve("memory.json"))).size();
                } catch (Exception ignored) {
                    // nothing trained yet
                }
                final String cap = settings().apply("ML_MEMORY_MB");
                AppLogger.raw(String.format(Locale.ROOT, "  memory      %.1f GB, %.1f GB available now: a training takes what is"
                        + " spared beyond %.0f MB%s, its batch planned and watched%s", total / 1024, free / 1024, reserve,
                    cap == null || cap.isBlank() ? " (ML_MEMORY_MB caps it)" : ", at most ML_MEMORY_MB = " + cap.strip() + " MB",
                    learned > 0 ? "; costs measured for " + learned + " network" + (learned > 1 ? "s" : "") : ""));
            }
            final var running = MlEngine.running();
            AppLogger.raw("  engine      " + (running.isEmpty() ? "not started: the first command that needs one starts it"
                + " (or ':ml start')" : ""));
            for (MlEngine e : running) {
                AppLogger.raw("    " + e.description() + (e.busy() ? "  [busy]" : ""));
            }
            // the datasets of the bridge folder, read from their headers
            final List<String> datasets = new ArrayList<>();
            try (var s = Files.list(Bridge.folder())) {
                for (Path p : (Iterable<Path>) s.filter(x -> x.getFileName().toString().endsWith(".spx")).sorted()::iterator) {
                    try (Spx.Reader r = new Spx.Reader(p)) {
                        final Map<String, String> m = r.meta();
                        if ("ml-dataset".equals(m.get("Kind"))) {
                            final String name = p.getFileName().toString().replaceAll("\\.spx$", "");
                            datasets.add(name);
                            AppLogger.raw(String.format(Locale.ROOT, "  dataset     %-20s %s rows, classes %s%s", name,
                                m.get("Jets"), m.get("Classes"), "0".equals(m.getOrDefault("Points", "0")) ? ", table of "
                                + m.get("HighLevel") : ", " + m.get("Points") + " particles each"));
                        }
                    } catch (Exception ignored) {
                        // not a dataset
                    }
                }
            } catch (Exception ignored) {
                // no bridge folder yet
            }
            if (datasets.isEmpty()) {
                AppLogger.raw("  dataset     none yet in " + Bridge.folder());
            }
            if (pending != null && pending.size() > 0) {
                AppLogger.raw("  gathered    " + pending.size() + " jets " + pending.counts() + ", " + pending.points()
                    + " particles each, not saved (':ml jets save <name>')");
            }
            final List<String> nets = new ArrayList<>();
            try (var s = Files.list(models())) {
                s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".snn.spx")).sorted()
                    .forEach(n -> nets.add(n.substring(0, n.length() - 8)));
            } catch (Exception ignored) {
                // none trained yet
            }
            if (nets.isEmpty()) {
                AppLogger.raw("  network     none trained yet");
            } else {
                models(input, ctx);
            }
            // what to do next, from where things stand
            final String next = !any ? "pip install torch (or keras jax, pyhf) in a Python Sphere sees: ML_PYTHON in settings.conf"
                : datasets.isEmpty() ? "':ml toy' makes a dataset of toy jets in seconds; from your events: ':fjet read <file>',"
                + " ':ml jets <class>' per sample, ':ml jets save <name>'"
                : nets.isEmpty() ? "':ml train " + datasets.get(0) + "' trains ParticleNet-Lite on it (or mlp, deepsets,"
                + " transformer; torch or keras)"
                : "':ml crosscheck " + nets.get(0) + " " + datasets.get(0) + "' holds the frameworks and Sphere's Java to one"
                + " reference; ':ml tag " + nets.get(0) + "' runs it on the loaded events";
            AppLogger.info("Next: " + next);
        });
    }

    /** An interpreter by its environment's name (pylhc), or its path. */
    private static String environment(MlLocator.Interpreter i) {
        if (i.wsl()) {
            return "WSL " + i.distro();
        }
        final Path p = Path.of(i.label());
        final Path dir = p.getParent();
        return dir != null && dir.getParent() != null && (dir.getFileName().toString().equalsIgnoreCase("Scripts")
            || dir.getFileName().toString().equals("bin")) ? dir.getParent().getFileName().toString() + " (" + i.label() + ")"
            : i.label();
    }

    public static void start(String input, CommandExecutionContext ctx) {
        final String f = Args.of(input, ":ml start").word(0, "torch");
        background("ml-start", () -> {
            try {
                final MlEngine e = engine(f);
                AppLogger.success(e.description() + " -- " + e.interpreter().label());
            } catch (Exception e) {
                AppLogger.error(failure(e));
            }
        });
    }

    public static void stop(String input, CommandExecutionContext ctx) {
        MlEngine.stopAll();
        AppLogger.info("learning engines stopped");
    }

    public static void cancel(String input, CommandExecutionContext ctx) {
        final MlEngine e = training;
        if (e == null || !e.busy()) {
            AppLogger.info("No training is going on");
            return;
        }
        e.cancel();
        AppLogger.info("Training stopping after its current batch; the best epoch so far is kept");
    }

    // ------------------------------------------------------------------
    // Datasets
    // ------------------------------------------------------------------

    public static void toy(String input, CommandExecutionContext ctx) {
        final Args a = Args.of(input, ":ml toy");
        final int n = Integer.parseInt(a.word(0, "4000"));
        final String name = a.word(1, "toyjets");
        background("ml-toy", () -> {
            try {
                final MlEngine e = engine("numpy");
                final Map<String, Object> r = e.call("toy", Map.of("jets", n, "points", a.num("points", 32), "seed",
                    a.num("seed", 1), "path", e.path(dataset(name)))).get();
                final List<Integer> perClass = G4Json.list(r, "per_class").stream().map(v -> ((Number) v).intValue()).toList();
                AppLogger.success(String.format(Locale.ROOT, "%s: %d jets %s, %d particles x %d features each, high-level %s",
                    name, (int) G4Json.num(r, "jets", 0), perClass, (int) G4Json.num(r, "points", 0),
                    G4Json.list(r, "features").size(), G4Json.list(r, "high_level")));
            } catch (Exception e) {
                AppLogger.error("toy: " + failure(e));
            }
        });
    }

    public static void jets(String input, CommandExecutionContext ctx) {
        final Args a = Args.of(input, ":ml jets");
        final String what = a.word(0, "");
        if (what.isEmpty()) {
            Handlers.usage(":ml jets <class> [--points 64] [--ptmin GeV] [--njets n] | :ml jets save <name> | :ml jets clear");
            return;
        }
        if (what.equals("clear")) {
            pending = null;
            AppLogger.info("gathered jets forgotten");
            return;
        }
        if (what.equals("save")) {
            if (pending == null || pending.size() == 0) {
                AppLogger.error("No jets gathered: ':ml jets <class>' after ':fjet read' or ':fjet toy'");
                return;
            }
            final String name = a.word(1, "jets");
            try {
                final Path out = pending.write(dataset(name), name);
                AppLogger.success(String.format(Locale.ROOT, "%s: %d jets %s written to %s", name, pending.size(),
                    pending.counts(), out));
                if (pending.classes().size() < 2) {
                    AppLogger.warn("One class only: a classifier needs two (':ml jets <other class>' on another sample)");
                }
            } catch (Exception e) {
                AppLogger.error("Cannot write the dataset: " + failure(e));
            }
            return;
        }
        final List<List<PseudoJet>> events = FastJetCommands.loadedEvents();
        final JetDefinition def = FastJetCommands.activeDefinition();
        if (events.isEmpty()) {
            AppLogger.error("No events loaded: ':fjet read <file>' or ':fjet toy' first, then ':ml jets " + what + "'");
            return;
        }
        if (def == null) {
            AppLogger.error("No jet definition: ':fjet def fat antikt:0.8' for instance");
            return;
        }
        final int points = a.num("points", pending == null ? 64 : pending.points());
        if (pending != null && pending.points() != points) {
            AppLogger.error("The jets gathered have " + pending.points() + " particles each: save or clear them first");
            return;
        }
        if (pending == null) {
            pending = new MlData.Builder(points);
        }
        final MlData.Builder into = pending;
        final double ptmin = a.real("ptmin", 0);
        final int njets = a.num("njets", 1);
        background("ml-jets", () -> {
            final long t0 = System.nanoTime();
            final List<ClusterSequence> cs = EventBatch.cluster(events, def, Runtime.getRuntime().availableProcessors());
            final List<Double> weights = FastJetCommands.loadedWeights();
            int added = 0;
            synchronized (into) {
                for (int e = 0; e < cs.size(); e++) {
                    final List<PseudoJet> found = PseudoJet.sortedByPt(cs.get(e).inclusiveJets(ptmin));
                    for (int k = 0; k < njets && k < found.size(); k++) {
                        if (!found.get(k).constituents().isEmpty()) {
                            into.add(found.get(k), what, e < weights.size() ? weights.get(e) : 1.0);
                            added++;
                        }
                    }
                }
                into.source(FastJetCommands.loadedSource() + " [" + FastJetCommands.activeName() + "] as " + what);
            }
            AppLogger.success(String.format(Locale.ROOT, "%d jets of %d events as '%s' (%s, pt > %s GeV, leading %d), %.1f s;"
                + " gathered %s", added, events.size(), what, FastJetCommands.activeName(), ptmin, njets,
                (System.nanoTime() - t0) / 1e9, into.counts()));
        });
    }

    /**
     * :ml table: ROOT trees made into a dataset by Sphere's own reader (no ROOT needed): each tree one class,
     * the branches named the features, the rows of all of them written as an SPX table for the MLP.
     */
    public static void table(String input, CommandExecutionContext ctx) {
        final Args a = Args.of(input, ":ml table");
        final List<String> w = a.words();
        if (w.size() < 3 || w.size() % 2 == 0 || !a.flags().containsKey("features")) {
            Handlers.usage(":ml table <name> <file.root>:<tree> <class> [<file.root>:<tree> <class> ...] --features a,b,c");
            return;
        }
        final String name = w.get(0);
        final List<String> features = List.of(a.flags().get("features").split(","));
        background("ml-table", () -> {
            final List<String> classes = new ArrayList<>();
            final List<double[][]> columns = new ArrayList<>();
            final List<String> sources = new ArrayList<>();
            for (int i = 1; i + 1 < w.size(); i += 2) {
                final String spec = w.get(i);
                final int colon = spec.lastIndexOf(':');
                if (colon <= 1) {
                    AppLogger.error(spec + ": give the tree as <file.root>:<tree>");
                    return;
                }
                final Path file = Handlers.resolve(spec.substring(0, colon)).toPath();
                try (com.sphere.core.rootio.RootIO io = com.sphere.core.rootio.RootIO.open(file)) {
                    final com.sphere.core.rootio.RTree tree = io.tree(spec.substring(colon + 1));
                    final double[][] cols = new double[features.size()][];
                    for (int f = 0; f < features.size(); f++) {
                        final com.sphere.core.rootio.RTree.RBranch b = tree.branch(features.get(f));
                        if (b == null) {
                            AppLogger.error("No branch " + features.get(f) + " in " + spec);
                            return;
                        }
                        cols[f] = b.column();
                        if (f > 0 && cols[f].length != cols[0].length) {
                            AppLogger.error(features.get(f) + " has " + cols[f].length + " numbers, " + features.get(0)
                                + " " + cols[0].length + ": the features are one number per entry");
                            return;
                        }
                    }
                    classes.add(w.get(i + 1));
                    columns.add(cols);
                    sources.add(file.getFileName() + ":" + tree.name() + " (" + cols[0].length + " entries) as " + w.get(i + 1));
                } catch (Exception e) {
                    AppLogger.error("Cannot read " + spec + ": " + failure(e));
                    return;
                }
            }
            try {
                final Path out = MlData.table(dataset(name), name, features, classes, columns,
                    "Sphere ROOT reader: " + String.join("; ", sources));
                AppLogger.success(name + ": " + String.join("; ", sources) + ", features " + features + " -> " + out
                    + " (':ml train " + name + " mlp')");
            } catch (Exception e) {
                AppLogger.error("Cannot write " + name + ": " + failure(e));
            }
        });
    }

    // ------------------------------------------------------------------
    // Training
    // ------------------------------------------------------------------

    public static void train(String input, CommandExecutionContext ctx) {
        final Args a = Args.of(input, ":ml train");
        if (a.words().isEmpty()) {
            Handlers.usage(":ml train <dataset> [mlp|deepsets|efn|particlenet|transformer] [torch|keras|tensorflow] [--epochs n]"
                + " [--batch b] [--lr x] [--points p] [--k k] [--name n] [--precision float64] [--decorrelate mass[:lambda]]");
            return;
        }
        final Path data = dataset(a.word(0, ""));
        if (!Files.isRegularFile(data)) {
            AppLogger.error("No dataset " + data + ": ':ml jets ... save " + a.word(0, "") + "' or ':ml toy'");
            return;
        }
        final String model = a.word(1, "particlenet");
        final String backend = a.word(2, "torch");
        final String framework = backend.equals("keras") ? "keras" : backend;
        background("ml-train", () -> {
            try {
                final MlEngine e = engine(framework);
                final Map<String, Object> args = new LinkedHashMap<>();
                args.put("data", e.path(data));
                args.put("model", model);
                args.put("backend", backend);
                for (String k : List.of("epochs", "batch", "points", "k", "seed", "patience")) {
                    if (a.flags().containsKey(k)) {
                        args.put(k, Integer.parseInt(a.flags().get(k)));
                    }
                }
                for (String k : List.of("lr", "val", "test")) {
                    if (a.flags().containsKey(k)) {
                        args.put(k, Double.parseDouble(a.flags().get(k)));
                    }
                }
                if (a.flags().containsKey("name")) {
                    args.put("name", a.flags().get("name"));
                }
                if (a.flags().containsKey("precision")) {
                    args.put("precision", a.flags().get("precision"));
                }
                if (a.flags().containsKey("decorrelate")) {
                    final String d = a.flags().get("decorrelate");
                    args.put("decorrelate", d.equals("true") ? "mass" : d);
                }
                AppLogger.info("Training " + model + " on " + data.getFileName() + " with " + e.description()
                    + (args.containsKey("precision") ? ", in " + args.get("precision") : "")
                    + (args.containsKey("decorrelate") ? ", decorrelated from the " + args.get("decorrelate") + " (DisCo)" : "")
                    + " (':ml cancel' stops it)");
                training = e;
                final Map<String, Object> r = e.call("train", args, (f, m) -> AppLogger.stream("  " + m)).get();
                training = null;
                report(r);
            } catch (Exception ex) {
                training = null;
                AppLogger.error("train: " + failure(ex));
                final String tail = MlEngine.logTail(6);
                if (!tail.isBlank()) {
                    AppLogger.raw(tail);
                }
            }
        });
    }

    private static void report(Map<String, Object> r) {
        final Map<String, Object> m = G4Json.obj(r, "metrics");
        AppLogger.success(String.format(Locale.ROOT, "%s: %s on %s, %d parameters, %d epochs in %.1f s", G4Json.str(r, "name", "?"),
            G4Json.str(r, "model", "?"), G4Json.str(r, "engine", "?"), (int) G4Json.num(r, "parameters", 0),
            G4Json.list(r, "history").size(), G4Json.num(r, "seconds", 0)));
        AppLogger.raw(String.format(Locale.ROOT, "  test (%d jets): AUC %.4f, accuracy %.4f, background rejection 1/eps_B %s / %s / %s"
                + " at 30 / 50 / 70 %% signal", (int) G4Json.num(r, "test_jets", 0), G4Json.num(m, "auc", 0),
            G4Json.num(m, "accuracy", 0), rejection(m, "rejection_30"), rejection(m, "rejection_50"),
            rejection(m, "rejection_70")));
        if (m.get("rejection_30_min") instanceof Number || m.get("rejection_50_min") instanceof Number
            || m.get("rejection_70_min") instanceof Number) {
            AppLogger.raw(String.format(Locale.ROOT, "  (>: no background jet of the test set, %.0f effective, passes the cut:"
                + " 1/eps_B > N/3 at 95 %% CL; a larger test set measures it)", G4Json.num(m, "background_jets_effective", 0)));
        }
        if (m.containsKey("sculpting_jsd")) {
            final double jsd = G4Json.num(m, "sculpting_jsd", 0);
            AppLogger.raw(String.format(Locale.ROOT, "  mass sculpting at 50 %% signal: JSD %.3g (1/JSD %s): %s", jsd,
                m.get("sculpting_1_over_jsd") instanceof Number inv ? String.format(Locale.ROOT, "%.3g", inv.doubleValue())
                    : "infinite", jsd > 0.1 ? "the cut reshapes the background's mass -- decorrelate"
                + " (no mass input, or a mass-decorrelated loss) before a bump hunt" : "the background's mass keeps its shape"));
        }
        for (String k : m.keySet()) {
            if (k.startsWith("numpy_vs_")) {
                AppLogger.raw(String.format(Locale.ROOT, "  %s vs the numpy reference on the test jets: max |dlogit| %.2e",
                    k.substring(9), G4Json.num(m, k, 0)));
            }
        }
        final Map<String, Object> mem = G4Json.obj(r, "memory");
        if (Boolean.TRUE.equals(mem.get("active"))) {
            final int changes = G4Json.list(mem, "events").size();
            AppLogger.raw(String.format(Locale.ROOT, "  memory: peak %.0f MB of the %.0f MB this training could take; batch %d"
                    + "%s; a jet cost %.2f MB (planned with %.2f, %s) -- learned for the next training",
                G4Json.num(mem, "peak_mb", 0), G4Json.num(mem, "budget_mb", 0), (int) G4Json.num(r, "batch", 0),
                changes > 0 ? " -> " + (int) G4Json.num(mem, "batch", 0) + " (" + changes + " change" + (changes > 1 ? "s" : "")
                    + ")" : "", G4Json.num(mem, "measured_per_jet_mb", 0), G4Json.num(mem, "per_jet_mb", 0),
                G4Json.str(mem, "per_jet_source", "estimated")));
        }
        AppLogger.raw("  exported " + G4Json.str(r, "path", "") + "; ROC in the Plots tab; ':ml tag "
            + G4Json.str(r, "name", "") + "' runs it in Java");
    }

    /** A number as people write it: 0.1, not 0.100000. */
    private static String plain(double v) {
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** A JSON value as an object (an empty one if it is not). */
    private static Map<String, Object> asMap(Object o) {
        return o == null ? Map.of() : G4Json.obj(Map.of("v", o), "v");
    }

    /** A crosscheck's difference, with how many times the rounding of its precision it is: "2.08e-16 (0.6x)". */
    private static String cell(Map<String, Object> a) {
        return a.containsKey("max_abs") ? String.format(Locale.ROOT, "%.2e (%.1fx)", G4Json.num(a, "max_abs", 0),
            G4Json.num(a, "noise_units", 0)) : "failed";
    }

    /** 1/eps_B with its binomial error, or its 95 % CL lower bound where no background jet is left. */
    private static String rejection(Map<String, Object> m, String key) {
        if (m.get(key) instanceof Number r && Double.isFinite(r.doubleValue())) {
            return m.get(key + "_err") instanceof Number e
                ? String.format(Locale.ROOT, "%.3g ± %.2g", r.doubleValue(), e.doubleValue())
                : String.format(Locale.ROOT, "%.1f", r.doubleValue());
        }
        return m.get(key + "_min") instanceof Number b ? String.format(Locale.ROOT, "> %.3g", b.doubleValue()) : "infinite";
    }

    public static void models(String input, CommandExecutionContext ctx) {
        final Path dir = models();
        if (!Files.isDirectory(dir)) {
            AppLogger.raw("  networks: none trained yet (':ml train')");
            return;
        }
        try (var s = Files.list(dir)) {
            final List<Path> all = s.filter(p -> p.getFileName().toString().endsWith(".snn.spx")).sorted().toList();
            AppLogger.raw("  networks (" + dir + "): " + (all.isEmpty() ? "none" : ""));
            for (Path p : all) {
                try (Spx.Reader r = new Spx.Reader(p)) {
                    final Map<String, String> meta = r.meta();
                    final Map<String, Object> spec = MlEngine.json(r.text("spec"));
                    final String cal = meta.getOrDefault("Calibration", "");
                    AppLogger.raw(String.format(Locale.ROOT, "    %-34s %-12s %-26s AUC %s%s", meta.get("Name"),
                        meta.get("Model"), meta.get("TrainedWith"), String.format(Locale.ROOT, "%.4f",
                            G4Json.num(G4Json.obj(spec, "metrics"), "auc", Double.NaN)), cal.isEmpty() ? "" : ", " + cal
                            + " calibration"));
                } catch (Exception e) {
                    AppLogger.raw("    " + p.getFileName() + ": " + failure(e));
                }
            }
        } catch (Exception e) {
            AppLogger.error(failure(e));
        }
    }

    // ------------------------------------------------------------------
    // Cross-checks: every framework, and Sphere's Java, against numpy
    // ------------------------------------------------------------------

    public static void crosscheck(String input, CommandExecutionContext ctx) {
        final Args a = Args.of(input, ":ml crosscheck");
        if (a.words().size() < 2) {
            Handlers.usage(":ml crosscheck <model> <dataset> [rows]");
            return;
        }
        final Path net = model(a.word(0, "")), data = dataset(a.word(1, ""));
        final int rows = Integer.parseInt(a.word(2, "256"));
        background("ml-crosscheck", () -> {
            try {
                final MlEngine e = engine("numpy");
                final Map<String, Object> r = e.call("crosscheck", Map.of("model", e.path(net), "data", e.path(data),
                    "rows", rows)).get();
                final int n = (int) G4Json.num(r, "rows", 0);
                final Map<String, Object> noise = G4Json.obj(r, "noise");
                final double noise64 = G4Json.num(noise, "float64", 0), noise32 = G4Json.num(noise, "float32", 0);
                final double factor = G4Json.num(r, "factor", 64);
                final String trained = G4Json.str(r, "trained_in", "float32");
                AppLogger.result(String.format(Locale.ROOT, "%s (trained in %s) on %d jets of %s: largest |logit| differences"
                    + " to numpy in float64 (logits up to %.3g), and in units of the rounding of their precision", net.getFileName(),
                    trained, n, data.getFileName(), G4Json.num(r, "scale", 0)));
                AppLogger.raw(String.format(Locale.ROOT, "  %-26s %-20s %-20s %s", "", "float64", "float32", "us/jet 64 | 32"));
                AppLogger.raw(String.format(Locale.ROOT, "  %-26s %-20s %-20s", "rounding alone (numpy)",
                    String.format(Locale.ROOT, "%.2e", noise64), String.format(Locale.ROOT, "%.2e", noise32)));
                boolean one = true;
                double agree64 = 0;
                final Map<String, Object> engines = G4Json.obj(r, "engines");
                for (String name : engines.keySet()) {
                    final Map<String, Object> v = G4Json.obj(engines, name);
                    if (!v.containsKey("float64")) {
                        AppLogger.raw("  " + name + ": " + G4Json.str(v, "error", "?"));
                        one = false;
                        continue;
                    }
                    final Map<String, Object> a64 = G4Json.obj(v, "float64"), a32 = G4Json.obj(v, "float32");
                    final String verdict = G4Json.str(v, "verdict", "?");
                    AppLogger.raw(String.format(Locale.ROOT, "  %-26s %-20s %-20s %6.0f | %-6.0f %s", name, cell(a64), cell(a32),
                        G4Json.num(a64, "us_per_jet", 0), G4Json.num(a32, "us_per_jet", 0), verdict));
                    one &= verdict.startsWith("same function");
                    agree64 = Math.max(agree64, G4Json.num(a64, "max_abs", 0));
                }
                // Sphere's Java, against the reference the engine wrote, judged by the same rounding
                final NeuralNet nn = NeuralNet.load(net);
                final Path ref = Bridge.folder().resolve("ml").resolve(nn.name + ".reference.spx");
                try (Spx.Reader rr = new Spx.Reader(ref)) {
                    final double[] logits = rr.f64("logits");
                    nn.logits(data, n);   // untimed: the JIT compiles it
                    final long t0 = System.nanoTime();
                    final double[][] mine = nn.logits(data, n);
                    final double us = (System.nanoTime() - t0) / 1e3 / Math.max(1, n);
                    double worst = 0;
                    int beyond = 0;
                    for (int i = 0; i < n; i++) {
                        double jet = 0;
                        for (int k = 0; k < mine[i].length; k++) {
                            jet = Math.max(jet, Math.abs(mine[i][k] - logits[i * mine[i].length + k]));
                        }
                        worst = Math.max(worst, jet);
                        beyond += jet > factor * noise64 ? 1 : 0;
                    }
                    final double units = noise64 > 0 ? worst / noise64 : 0;
                    final String verdict = units <= factor ? "same function"
                        : "another function: " + beyond + " jet(s) beyond float64's rounding";
                    AppLogger.raw(String.format(Locale.ROOT, "  %-26s %-20s %-20s %6.0f | %-6s %s", "Sphere Java (NeuralNet)",
                        String.format(Locale.ROOT, "%.2e (%.1fx)", worst, units), "--", us, "", verdict));
                    one &= units <= factor;
                    agree64 = Math.max(agree64, worst);
                }
                if (one) {
                    AppLogger.success(String.format(Locale.ROOT, "One function everywhere: in float64 every framework and Sphere's"
                        + " Java agree with numpy to %.1e, float64's own rounding; in float32 they differ by %.1e as numpy itself"
                        + " does -- float32's rounding (eps = 1.19e-7), not a difference of implementation", agree64, noise32));
                    if (!"float64".equals(trained)) {
                        AppLogger.info("Trained in float32, its weights hold float32's digits: ':ml train ... --precision"
                            + " float64' trains in double throughout, weights and gradients included");
                    }
                } else {
                    AppLogger.warn("Not one function everywhere: an engine above differs beyond the rounding of its precision");
                }
            } catch (Exception e) {
                AppLogger.error("crosscheck: " + failure(e));
            }
        });
    }

    // ------------------------------------------------------------------
    // Physics checks: infrared and collinear safety, the audit
    // ------------------------------------------------------------------

    public static void irc(String input, CommandExecutionContext ctx) {
        final Args a = Args.of(input, ":ml irc");
        if (a.words().size() < 2) {
            Handlers.usage(":ml irc <model> <dataset> [jets]");
            return;
        }
        final Path net = model(a.word(0, "")), data = dataset(a.word(1, ""));
        final int rows = Integer.parseInt(a.word(2, "64"));
        background("ml-irc", () -> {
            try {
                final MlEngine e = engine("numpy");
                final Map<String, Object> r = e.call("irc", Map.of("model", e.path(net), "data", e.path(data),
                    "rows", rows)).get();
                AppLogger.result(String.format(Locale.ROOT, "%s (%s) on %d jets of %s: how its probabilities change as"
                    + " eps goes to 0", net.getFileName(), G4Json.str(r, "model", "?"), (int) G4Json.num(r, "jets", 0),
                    data.getFileName()));
                final StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "  %-17s", "eps"));
                for (Object eps : G4Json.list(r, "eps")) {
                    head.append(String.format(Locale.ROOT, "%-9.0e", ((Number) eps).doubleValue()));
                }
                AppLogger.raw(head.toString());
                for (String[] probe : new String[][] {{"soft", "soft emission"}, {"collinear", "collinear split"}}) {
                    final Map<String, Object> p = G4Json.obj(r, probe[0]);
                    final StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  %-17s", probe[1]));
                    for (Object v : G4Json.list(p, "median")) {
                        row.append(String.format(Locale.ROOT, "%-9.1e", ((Number) v).doubleValue()));
                    }
                    AppLogger.raw(row + "  " + G4Json.str(p, "verdict", "?"));
                }
                AppLogger.raw("  (the median change of the probabilities over the jets; log-log in the Plots tab)");
                if (Boolean.TRUE.equals(r.get("safe"))) {
                    AppLogger.success("Infrared and collinear safe: what the network says does not depend on soft particles nor"
                        + " collinear splittings -- what perturbative QCD can predict, robust to hadronization and to the detector");
                } else {
                    AppLogger.warn("Not infrared and collinear safe: its predictions hang on the soft and collinear modeling"
                        + " (hadronization, detector splitting), a systematic to carry -- an 'efn' is safe by construction"
                        + " (':ml train <dataset> efn')");
                }
            } catch (Exception e) {
                AppLogger.error("irc: " + failure(e));
            }
        });
    }

    public static void audit(String input, CommandExecutionContext ctx) {
        final Args a = Args.of(input, ":ml audit");
        if (a.words().size() < 2) {
            Handlers.usage(":ml audit <model> <dataset> [--jets n] [--jes 0.02]");
            return;
        }
        final Path net = model(a.word(0, "")), data = dataset(a.word(1, ""));
        final int rows = a.num("jets", 2000);
        final double jes = a.real("jes", 0.02);
        background("ml-audit", () -> {
            try {
                final MlEngine e = engine("numpy");
                final Map<String, Object> r = e.call("audit", Map.of("model", e.path(net), "data", e.path(data),
                    "rows", rows, "jes", jes)).get();
                AppLogger.result(String.format(Locale.ROOT, "Audit of %s (%s, %s) on %s: AUC %.4f -- %d of %d checks passed",
                    G4Json.str(r, "name", "?"), G4Json.str(r, "model", "?"), G4Json.str(r, "trained_with", "?"),
                    G4Json.str(r, "where", "?"), G4Json.num(r, "auc", 0), (int) G4Json.num(r, "passed", 0),
                    (int) G4Json.num(r, "graded", 0)));
                for (Object o : G4Json.list(r, "checks")) {
                    final String grade = G4Json.str(o, "grade", "?");
                    final String mark = switch (grade) {
                        case "pass" -> "[pass]";
                        case "warn" -> "[warn]";
                        case "fail" -> "[FAIL]";
                        default -> "[ -- ]";
                    };
                    AppLogger.raw(String.format(Locale.ROOT, "  %s %-30s %s", mark, G4Json.str(o, "check", "?"),
                        G4Json.str(o, "finding", "")));
                    final String advice = G4Json.str(o, "advice", "");
                    if (!advice.isBlank()) {
                        AppLogger.raw(String.format(Locale.ROOT, "  %6s %-30s -> %s", "", "", advice));
                    }
                }
                if (G4Json.num(r, "passed", 0) == G4Json.num(r, "graded", 0)) {
                    AppLogger.success("Fit for an analysis on every count checked");
                } else {
                    AppLogger.info("What to do is under each check that did not pass; ':ml irc' details the safety,"
                        + " ':ml crosscheck' the function the engines compute");
                }
            } catch (Exception e) {
                AppLogger.error("audit: " + failure(e));
            }
        });
    }

    public static void calibrate(String input, CommandExecutionContext ctx) {
        final Args a = Args.of(input, ":ml calibrate");
        if (a.words().size() < 2) {
            Handlers.usage(":ml calibrate <model> <dataset> [--method auto|isotonic|temperature|none]");
            return;
        }
        final Path net = model(a.word(0, "")), data = dataset(a.word(1, ""));
        final String method = a.flags().getOrDefault("method", "auto");
        background("ml-calibrate", () -> {
            try {
                final MlEngine e = engine("numpy");
                final Map<String, Object> r = e.call("calibrate", Map.of("model", e.path(net), "data", e.path(data),
                    "method", method)).get();
                final String name = G4Json.str(r, "name", "?"), chosen = G4Json.str(r, "chosen", "none");
                AppLogger.result(String.format(Locale.ROOT, "Calibration of %s (%s): fitted on %s, judged on %s", name,
                    G4Json.str(r, "model", "?"), G4Json.str(r, "fitted_on", "?"), G4Json.str(r, "judged_on", "?")));
                AppLogger.raw(String.format(Locale.ROOT, "  %-13s %-8s %-8s", "method", "Brier", "ECE"));
                final Map<String, Object> table = G4Json.obj(r, "candidates");
                for (String m : List.of("none", "temperature", "isotonic")) {
                    final Map<String, Object> c = G4Json.obj(table, m);
                    final String what = switch (m) {
                        case "temperature" -> String.format(Locale.ROOT, "T = %.3f", G4Json.num(r, "temperature", 1));
                        case "isotonic" -> (int) G4Json.num(r, "knots", 0) + " knots, monotone";
                        default -> "the network as it is";
                    };
                    AppLogger.raw(String.format(Locale.ROOT, "  %-13s %-8.4f %-8.3f %-26s%s", m, G4Json.num(c, "brier", 0),
                        G4Json.num(c, "ece", 0), what, m.equals(chosen) ? "<- kept" : ""));
                }
                AppLogger.raw(String.format(Locale.ROOT, "  (Brier: the proper score the choice is made by; a perfectly"
                    + " calibrated network would show an ECE of %.3f on these %d jets)", G4Json.num(r, "floor", 0),
                    (int) G4Json.num(r, "jets", 0)));
                // Sphere's Java, through the calibration now in the export, against what numpy made of those jets
                final NeuralNet nn = NeuralNet.load(net);
                final Path ref = Bridge.folder().resolve("ml").resolve(name + ".calibration.spx");
                final double[][] mine = nn.probabilities(ref, -1);
                double worst = 0;
                try (Spx.Reader rr = new Spx.Reader(ref)) {
                    final double[] prob = rr.f64("prob");
                    for (int i = 0; i < mine.length; i++) {
                        for (int c = 0; c < mine[i].length; c++) {
                            worst = Math.max(worst, Math.abs(mine[i][c] - prob[i * mine[i].length + c]));
                        }
                    }
                }
                if (chosen.equals("none")) {
                    AppLogger.success("Left as it is: already calibrated -- neither a temperature nor an isotonic map does 1 %"
                        + " better on jets it never saw");
                } else {
                    AppLogger.success(String.format(Locale.ROOT, "%s calibration written into %s: Sphere's Java applies it"
                        + " (probabilities, ':ml tag') to %.1e of numpy over %d jets, as sphere_nn.hpp does in C++", chosen,
                        net.getFileName(), worst, mine.length));
                }
            } catch (Exception ex) {
                AppLogger.error("calibrate: " + failure(ex));
            }
        });
    }

    public static void pareto(String input, CommandExecutionContext ctx) {
        final Args a = Args.of(input, ":ml pareto");
        if (a.words().isEmpty()) {
            Handlers.usage(":ml pareto <dataset> [model] [backend] [--lambdas 0,0.1,0.3,1,3] [--epochs 12]");
            return;
        }
        final Path data = dataset(a.word(0, ""));
        if (!Files.isRegularFile(data)) {
            AppLogger.error("No dataset " + data + ": ':ml jets ... save " + a.word(0, "") + "' or ':ml toy'");
            return;
        }
        final String model = a.word(1, "efn"), backend = a.word(2, "torch");
        final List<Double> lambdas = new ArrayList<>();
        for (String v : a.flags().getOrDefault("lambdas", "0,0.1,0.3,1,3").split(",")) {
            lambdas.add(Double.parseDouble(v.strip()));
        }
        final int epochs = a.num("epochs", 12);
        background("ml-pareto", () -> {
            try {
                final MlEngine e = engine(backend.equals("keras") ? "keras" : backend);
                final Map<String, Object> args = new LinkedHashMap<>();
                args.put("data", e.path(data));
                args.put("model", model);
                args.put("backend", backend);
                args.put("lambdas", lambdas);
                args.put("epochs", epochs);
                AppLogger.info("DisCo's trade-off for " + model + " on " + data.getFileName() + ": " + lambdas.size()
                    + " trainings of " + epochs + " epochs (':ml cancel' stops them)");
                training = e;
                final Map<String, Object> r = e.call("pareto", args, (f, m) -> AppLogger.stream("  " + m)).get();
                training = null;
                AppLogger.result(String.format(Locale.ROOT, "DisCo's trade-off for %s on %s (%s): background rejection at"
                    + " half the signal against 1/JSD, the background mass's shape kept", model, data.getFileName(), backend));
                AppLogger.raw(String.format(Locale.ROOT, "  %-8s %-7s %-18s %-10s %-9s %s", "lambda", "AUC",
                    "AUC at fixed mass", "R50", "1/JSD", "separates?"));
                for (Object o : G4Json.list(r, "points")) {
                    final Map<String, Object> q = asMap(o);
                    final String rej = q.get("r50") instanceof Number n ? String.format(Locale.ROOT, "%.3g", n.doubleValue())
                        : String.format(Locale.ROOT, "> %.3g", G4Json.num(q, "r50_min", 0));
                    AppLogger.raw(String.format(Locale.ROOT, "  %-8s %-7.3f %-18s %-10s %-9.3g %s",
                        plain(G4Json.num(q, "lambda", 0)), G4Json.num(q, "auc", 0),
                        q.get("auc_fixed_mass") instanceof Number n ? String.format(Locale.ROOT, "%.3f", n.doubleValue()) : "--",
                        rej, G4Json.num(q, "inv_jsd", 0), (Boolean.TRUE.equals(q.get("significant")) ? "yes"
                            : Boolean.TRUE.equals(q.get("separates")) ? "by the mass only (a coin at fixed mass)"
                            : "no (within 3 sigma of a coin)") + (Boolean.TRUE.equals(q.get("front")) ? ", on the front" : "")));
                }
                final String pick = G4Json.str(r, "suggested", ""), verdict = G4Json.str(r, "verdict", "");
                if (!pick.isBlank() && G4Json.num(r, "suggested_lambda", 0) > 0) {
                    AppLogger.success(verdict + " -- " + pick + "; ':ml audit " + pick + " "
                        + data.getFileName().toString().replaceAll("\\.spx$", "") + "' referees it");
                } else {
                    AppLogger.warn(verdict);
                }
                AppLogger.raw("  (R50 and 1/JSD by lambda in the Plots tab; each network kept in bridge/models)");
            } catch (Exception ex) {
                training = null;
                AppLogger.error("pareto: " + failure(ex));
            }
        });
    }

    // ------------------------------------------------------------------
    // A trained network on the loaded events, in Java
    // ------------------------------------------------------------------

    public static void tag(String input, CommandExecutionContext ctx) {
        final Args a = Args.of(input, ":ml tag");
        if (a.words().isEmpty()) {
            Handlers.usage(":ml tag <model> [--ptmin GeV] [--njets n] [--bins b]");
            return;
        }
        final List<List<PseudoJet>> events = FastJetCommands.loadedEvents();
        final JetDefinition def = FastJetCommands.activeDefinition();
        if (events.isEmpty() || def == null) {
            AppLogger.error("No events or no jet definition: ':fjet read <file>' (or ':fjet toy') and ':fjet def ...' first");
            return;
        }
        final Path net = model(a.word(0, ""));
        final double ptmin = a.real("ptmin", 0);
        final int njets = a.num("njets", 1), bins = a.num("bins", 20);
        // a graph exported by ':torch export' or ':tf export' -- any PyTorch or Keras model -- rather than a network of the zoo
        final Path graph = TorchCommands.graphPath(a.word(0, ""));
        if (a.word(0, "").endsWith(".sng.spx") || Files.isRegularFile(graph) && !Files.isRegularFile(net)) {
            TorchCommands.tag(graph, events, def, ptmin, njets, bins);
            return;
        }
        background("ml-tag", () -> {
            try {
                final NeuralNet nn = NeuralNet.load(net);
                final int signal = nn.classes.size() - 1;
                final List<ClusterSequence> cs = EventBatch.cluster(events, def, Runtime.getRuntime().availableProcessors());
                final List<double[]> scored = new ArrayList<>();   // pt, mass, score
                final long t0 = System.nanoTime();
                for (ClusterSequence c : cs) {
                    final List<PseudoJet> found = PseudoJet.sortedByPt(c.inclusiveJets(ptmin));
                    for (int k = 0; k < njets && k < found.size(); k++) {
                        final PseudoJet j = found.get(k);
                        if (j.constituents().isEmpty()) {
                            continue;
                        }
                        final double[] mask = new double[Math.max(nn.points(), 1)];
                        final double[][] x = MlData.particles(j, mask.length, mask);
                        // the observables (an O(n^3) energy correlator among them) only for the network that reads them
                        final double[] p = nn.probabilities(x, mask, nn.model.equals("mlp") ? MlData.highLevel(j) : null);
                        scored.add(new double[] {j.pt(), j.m(), p[signal]});
                    }
                }
                final double us = (System.nanoTime() - t0) / 1e3 / Math.max(1, scored.size());
                if (scored.isEmpty()) {
                    AppLogger.warn("No jet above " + ptmin + " GeV");
                    return;
                }
                final double[] edges = new double[bins + 1], counts = new double[bins];
                for (int b = 0; b <= bins; b++) {
                    edges[b] = (double) b / bins;
                }
                int tagged = 0;
                for (double[] s : scored) {
                    counts[Math.min(bins - 1, (int) (s[2] * bins))]++;
                    tagged += s[2] > 0.5 ? 1 : 0;
                }
                final Path out = SpxExport.histogram("score " + nn.name, "P(" + nn.classes.get(signal) + ")", edges, counts,
                    null, null, null, Bridge.outbox().resolve(SpxExport.safeName("mltag_" + nn.name) + ".spx"));
                AppLogger.success(String.format(Locale.ROOT, "%s (%s, run in Java): %d jets of %d events, %d (%.1f %%) with"
                    + " P(%s) > 0.5, %.0f us per jet; scores in the Plots tab (%s)", nn.name, nn.model, scored.size(),
                    events.size(), tagged, 100.0 * tagged / scored.size(), nn.classes.get(signal), us, out.getFileName()));
                scored.sort((x, y) -> Double.compare(y[2], x[2]));
                for (int i = 0; i < Math.min(5, scored.size()); i++) {
                    AppLogger.raw(String.format(Locale.ROOT, "  pt %8.1f GeV  mass %7.1f GeV  P(%s) %.4f", scored.get(i)[0],
                        scored.get(i)[1], nn.classes.get(signal), scored.get(i)[2]));
                }
            } catch (Exception e) {
                AppLogger.error("tag: " + failure(e));
            }
        });
    }

    // ------------------------------------------------------------------
    // pyhf
    // ------------------------------------------------------------------

    public static void pyhf(String input, CommandExecutionContext ctx) {
        final Args a = Args.of(input, ":ml pyhf");
        final List<String> w = a.words();
        if (w.isEmpty()) {
            Handlers.usage(":ml pyhf <signal> <background> <bkg-uncertainty> <observed> [numpy jax (pytorch tensorflow: pyhf 0.7)]"
                + "  (comma-separated for several bins)  |  :ml pyhf <workspace.json|histfactory.xml> [backends]");
            return;
        }
        final Map<String, Object> args = new LinkedHashMap<>();
        int first;
        if (w.get(0).toLowerCase(Locale.ROOT).endsWith(".json")) {
            args.put("workspace", Handlers.resolve(w.get(0)).toPath().toAbsolutePath().toString());
            first = 1;
        } else if (w.get(0).toLowerCase(Locale.ROOT).endsWith(".xml")) {
            // a HistFactory analysis (XML and ROOT histograms), as hist2workspace reads it
            args.put("xml", Handlers.resolve(w.get(0)).toPath().toAbsolutePath().toString());
            first = 1;
        } else {
            if (w.size() < 4) {
                Handlers.usage(":ml pyhf <signal> <background> <bkg-uncertainty> <observed> (e.g. :ml pyhf 5,10 50,60 7,8 53,65)");
                return;
            }
            final String[] keys = {"signal", "background", "bkg_uncertainty", "observed"};
            for (int i = 0; i < 4; i++) {
                final List<Double> v = new ArrayList<>();
                for (String s : w.get(i).split(",")) {
                    v.add(Double.parseDouble(s));
                }
                args.put(keys[i], v);
            }
            first = 4;
        }
        if (w.size() > first) {
            args.put("backends", w.subList(first, w.size()));
        }
        if (a.flags().containsKey("mu-max")) {
            args.put("mu_max", Double.parseDouble(a.flags().get("mu-max")));
        }
        background("ml-pyhf", () -> {
            try {
                final MlEngine e = engine("pyhf");
                for (String k : List.of("workspace", "xml")) {
                    if (args.containsKey(k)) {
                        args.put(k, e.path(Path.of((String) args.get(k))));
                    }
                }
                final Map<String, Object> r = e.call("stats", args, (f, m) -> AppLogger.stream("  " + m)).get();
                if (r.containsKey("workspace_json")) {
                    AppLogger.info("HistFactory XML read by pyhf: channels " + G4Json.list(r, "channels") + ", parameter of"
                        + " interest " + G4Json.str(r, "poi", "?") + "; kept as pyhf JSON (HEPData's likelihood format): "
                        + G4Json.str(r, "workspace_json", ""));
                }
                AppLogger.result("pyhf " + G4Json.str(r, "pyhf", "?") + ": the same likelihood on each backend it has "
                    + G4Json.list(r, "available"));
                for (Map.Entry<String, Object> b : G4Json.obj(r, "backends").entrySet()) {
                    final Object v = b.getValue();
                    if (v instanceof Map<?, ?> m && m.containsKey("error")) {
                        AppLogger.warn("  " + b.getKey() + ": " + m.get("error"));
                        continue;
                    }
                    final List<?> exp = G4Json.list(v, "expected_limits");
                    AppLogger.raw(String.format(Locale.ROOT, "  %-10s mu_hat %.5f  CLs(mu=1) %.5f  95%% CL limit on mu: %.4f"
                            + " observed, %.4f expected [%.4f, %.4f] (1 sigma)  %.2f s", b.getKey(), G4Json.num(v, "mu_hat", 0),
                        G4Json.num(v, "cls_obs", 0), G4Json.num(v, "upper_limit", 0),
                        exp.size() > 2 ? ((Number) exp.get(2)).doubleValue() : Double.NaN,
                        exp.size() > 1 ? ((Number) exp.get(1)).doubleValue() : Double.NaN,
                        exp.size() > 3 ? ((Number) exp.get(3)).doubleValue() : Double.NaN, G4Json.num(v, "seconds", 0)));
                }
                final Map<String, Object> ag = G4Json.obj(r, "agreement");
                if (!ag.isEmpty()) {
                    AppLogger.raw(String.format(Locale.ROOT, "  backends agree to %.1e (mu_hat), %.1e (CLs), %.1e (limit),"
                        + " relative; CLs(mu) in the Plots tab", G4Json.num(ag, "mu_hat", 0), G4Json.num(ag, "cls_obs", 0),
                        G4Json.num(ag, "upper_limit", 0)));
                }
            } catch (Exception e) {
                AppLogger.error("pyhf: " + failure(e));
            }
        });
    }

    public static void bib(String input, CommandExecutionContext ctx) {
        final String file = Handlers.args(input, ":ml bib").trim();
        if (file.isEmpty()) {
            Handlers.usage(":ml bib <file.bib>");
            return;
        }
        final Path out = Handlers.resolve(file).toPath();
        try {
            Files.writeString(out, MlCitations.bibtex(), java.nio.charset.StandardCharsets.UTF_8);
            AppLogger.result(MlCitations.bibliography().size() + " references written to " + out + ".");
        } catch (java.io.IOException e) {
            AppLogger.error("Cannot write " + out + ": " + e.getMessage());
        }
    }
}

package com.sphere.core.commands;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.bridge.Spx;
import com.sphere.core.bridge.SpxExport;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.fastjet.contrib.EventBatch;
import com.sphere.core.geantbackend.G4Json;
import com.sphere.core.ml.MlData;
import com.sphere.core.ml.MlEngine;
import com.sphere.core.ml.NeuralGraph;
import com.sphere.utils.AppLogger;
import com.sphere.utils.SettingsManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * :torch and :tf -- PyTorch and TensorFlow in Sphere. A model of either -- TensorFlow's through Keras, its API,
 * on the torch backend -- is made a portable graph by sphere_graph (torch.export, core ATen, SPX), which Sphere
 * then runs with neither: in Java (NeuralGraph: the jets of a FastJet loop, ':ml tag', a dataset streamed) and
 * in C++ (sphere_graph.hpp: a ROOT macro, a Geant4 program, Sphere's own C++ engine). Each engine is held to
 * numpy's reference and numpy to the framework; each plans its memory from what the machine has now -- and so
 * does the compiler of the C++ these commands write.
 */
public final class TorchCommands {

    private static final double MB = 1048576.0;

    private TorchCommands() {
    }

    static void register() {
        CommandDefinitions.register(":torch", "PyTorch in Sphere: any model made a portable graph that Java and C++ run without PyTorch, their memory planned; what each command does", TorchCommands::help);
        CommandDefinitions.register(":torch status", "PyTorch, LibTorch (the C++ library its wheel carries), Keras and TensorFlow here; the graphs, the memory Sphere spares, the C++ compiler", TorchCommands::status);
        CommandDefinitions.register(":torch export", "A PyTorch model made a graph: a script defining model and example (and feeds, name if it likes) exported by torch.export in a Python of its own, held to PyTorch at three batches it was not traced on. Usage: :torch export <script.py> [--name n] [--feeds particles|high_level]", TorchCommands::export);
        CommandDefinitions.register(":torch models", "The graphs exported: what each holds, how its batch was freed, the memory a row takes", TorchCommands::models);
        CommandDefinitions.register(":torch check", "A graph run by Sphere's Java (and its C++ with --cpp) on numpy's reference: the difference, and the same to the bit in chunks and on threads. Usage: :torch check <graph> [--cpp]", TorchCommands::check);
        CommandDefinitions.register(":torch run", "A graph over a dataset of any size, streamed file to file in the chunks the memory affords, on threads; AUC and scores when it is labeled. Usage: :torch run <graph> <dataset> [--out name] [--engine java|c++] [--memory MB] [--threads n]", TorchCommands::run);
        CommandDefinitions.register(":torch audit", "A jet tagger of any framework refereed as physics would, in Java alone: whether its particles' order matters, whether its padding leaks, collinear and infrared safety (exact splits, soft emissions with the jet recomputed, eps scanned), float32 inputs, and the features it reacts to. Usage: :torch audit <graph> <dataset> [--jets 400]", TorchCommands::audit);
        CommandDefinitions.register(":torch cpp","A C++ program and a ROOT macro running a graph with sphere_graph.hpp, no PyTorch; --compile builds the program with g++, its flags chosen by the memory free. Usage: :torch cpp <graph> [--compile]", TorchCommands::cpp);
        CommandDefinitions.register(":tf", "TensorFlow in Sphere: Keras models (TensorFlow's API) made graphs that Java and C++ run without TensorFlow; what each command does", TorchCommands::tfHelp);
        CommandDefinitions.register(":tf status", "TensorFlow here or not, the backends Keras could run on, and how a TensorFlow model reaches Sphere", TorchCommands::tfStatus);
        CommandDefinitions.register(":tf export", "A Keras model (.keras, .h5, or a script defining model and example) made a graph through Keras's torch backend, its batch freed even where Keras's code fixes it. Usage: :tf export <model.keras|model.h5|script.py> [--shape 16,7;16] [--name n] [--feeds particles|high_level]", TorchCommands::tfExport);
        CommandDefinitions.register(":tf import", "A TensorFlow model read without TensorFlow -- a frozen graph (.pb) or a SavedModel (TF1 or TF2: its functions inlined, its variables restored from the checkpoint) -- made a graph that Java and C++ run. Usage: :tf import <model.pb|saved_model_dir> [--inputs a,b] [--outputs y] [--shape 16,7;16] [--fix training=false] [--name n] [--feeds particles|high_level]", TorchCommands::tfImport);
    }

    // ------------------------------------------------------------------
    // Help and status
    // ------------------------------------------------------------------

    private static void help(String input, CommandExecutionContext ctx) {
        AppLogger.raw(String.join("\n",
            "PyTorch in Sphere -- any model made a graph that Sphere runs without PyTorch:",
            "  :torch status                    what is here: PyTorch, LibTorch, Keras, TensorFlow, the graphs, the memory, g++",
            "  :torch export <script.py>        torch.export of the script's `model` on its `example` (a tuple of input tensors):",
            "                                   a graph in bridge/models/<name>.sng.spx, held to PyTorch at batches 1, 3 and 17;",
            "                                   --feeds particles (x [B,P,7], mask [B,P]) or high_level ([B,9]) for ':ml tag'",
            "  :torch models                    the graphs and what each holds",
            "  :torch check <graph> [--cpp]     Sphere's Java (and C++) on numpy's reference -- and the same to the bit in chunks, on threads",
            "  :torch run <graph> <dataset>     a dataset of any size streamed through the graph, in Java or --engine c++",
            "  :torch cpp <graph> [--compile]   a C++ program and a ROOT macro for the graph (sphere_graph.hpp); g++ if asked",
            "  :torch audit <graph> <dataset>   a jet tagger refereed as physics would, in Java: particles' order, padding leaks,",
            "                                   collinear and infrared safety (exact splits, soft emissions, eps scanned), float32,",
            "                                   and the features it reacts to -- whatever framework made it",
            "  :ml tag <graph>                  the graph on the jets of the loaded events (:fjet), in Java",
            "  :tf ...                          the same for Keras models, TensorFlow's API (:tf for its commands)",
            "Every graph is optimized by exact rewrites (common subexpressions, identities, mm+bias into addmm), each held to",
            "the original to the bit before it is kept. Loops and conditions run as sub-graphs in numpy, Java and C++ alike.",
            "Memory: a graph's bytes per row are measured once; a run takes the chunks that fit what the machine spares now, on",
            "as many threads as fit; in C++ a value that would not fit for even one row goes to a temporary file. A model whose",
            "code fixes its batch is traced at two batches and its sizes solved as k B + c: the graph takes any batch."));
    }

    private static void tfHelp(String input, CommandExecutionContext ctx) {
        AppLogger.raw(String.join("\n",
            "TensorFlow in Sphere -- through Keras, TensorFlow's API, which also runs on PyTorch:",
            "  :tf status                       TensorFlow here or not, Keras and its backends, what reaches Sphere and how",
            "  :tf export <model.keras|.h5>     a Keras model made a graph (Keras on its torch backend, then torch.export):",
            "                                   --shape 16,7;16 when the file leaves its rows' sizes free; --feeds as for :torch",
            "  :tf export <script.py>           a script defining a Keras `model` and its `example`",
            "  :tf import <model.pb|savedmodel>  a TensorFlow model itself, read without TensorFlow: a frozen GraphDef (.pb, or",
            "                                   .pbtxt), or a SavedModel (TF1 or TF2), its functions inlined and its variables",
            "                                   restored from the checkpoint; its loops and conditions -- While, If, Case, TF1's",
            "                                   frames and Switch/Merge conditions, nested to any depth -- unrolled or inlined",
            "                                   where the data do not move them, kept as loops and as branches of which only the",
            "                                   one taken runs where they do; its batch free; an input the file gives no shape",
            "                                   taken for a scalar if the model runs so (said), --shape otherwise; outputs, if",
            "                                   --outputs names none, what nothing takes and TensorFlow would let be fetched;",
            "                                   --fix training=false,keep_prob=1 makes inputs constants, the conditions they",
            "                                   choose resolved at import (an input that only chooses branches is pointed out)",
            "Then :torch models, check, run, cpp and :ml tag take the graph as any other: Java and C++ run it, TensorFlow absent."));
    }

    private static void status(String input, CommandExecutionContext ctx) {
        MlCommands.background("torch-status", () -> {
            try {
                final Map<String, Object> s = MlCommands.engine("torch").call("graph_status", Map.of()).get();
                frameworks(s);
            } catch (Exception e) {
                AppLogger.warn("PyTorch's engine: " + MlCommands.failure(e) + " (':ml locate' lists the Pythons)");
            }
            AppLogger.raw(String.format(Locale.ROOT, "  Java: NeuralGraph spares %.0f MB now (the JVM's heap and the machine's free memory), %d threads",
                NeuralGraph.budget() / MB, Runtime.getRuntime().availableProcessors()));
            final String gpp = gpp();
            AppLogger.raw(String.format(Locale.ROOT, "  C++: %s; %.0f MB free now, so %s", gpp == null ? "no g++ (GPP_DIR in settings.conf)" : "g++ " + gpp,
                free() / MB, Gcc.describe(Gcc.flags(free() - Gcc.KEEP))));
            graphs(false);
        });
    }

    private static void frameworks(Map<String, Object> s) {
        final Object torch = s.get("torch");
        if (torch == null) {
            AppLogger.warn("PyTorch: not in " + G4Json.str(s, "executable", "?") + " -- " + G4Json.str(s, "torch_error", ""));
        } else {
            AppLogger.info(String.format(Locale.ROOT, "PyTorch %s (Python %s, %s): %d threads, %s, torch.export %s", torch,
                G4Json.str(s, "python", "?"), G4Json.str(s, "executable", "?"), (int) G4Json.num(s, "threads", 0),
                Boolean.TRUE.equals(s.get("cuda")) ? "CUDA" : "CPU only", Boolean.TRUE.equals(s.get("export")) ? "there" : "missing"));
            final Map<String, Object> lt = G4Json.obj(s, "libtorch");
            final Map<String, Object> libs = G4Json.obj(lt, "libraries");
            if (!libs.isEmpty()) {
                final List<String> big = new ArrayList<>();
                libs.forEach((k, v) -> {
                    if (v instanceof Number n && n.doubleValue() > 5 * MB) {
                        big.add(String.format(Locale.ROOT, "%s %.0f MB", k, n.doubleValue() / MB));
                    }
                });
                AppLogger.raw("  LibTorch, the C++ library the wheel carries: headers " + G4Json.str(lt, "include", "-") + "; " + libs.size()
                    + " libraries in " + G4Json.str(lt, "lib", "-") + " (" + String.join(", ", big) + ")"
                    + (G4Json.str(lt, "cmake", "").isEmpty() ? "" : "; CMake: " + G4Json.str(lt, "cmake", "")));
                AppLogger.raw("    a program on LibTorch needs the wheel's compiler (MSVC on Windows) and gigabytes to build; sphere_graph.hpp"
                    + " runs the same models with no library: ':torch cpp'");
            }
        }
        final Map<String, Object> backends = G4Json.obj(s, "backends");
        final List<String> have = new ArrayList<>();
        backends.forEach((k, v) -> {
            if (Boolean.TRUE.equals(v)) {
                have.add(k);
            }
        });
        AppLogger.raw("  Keras " + G4Json.str(s, "keras", "absent") + ", backends here: " + (have.isEmpty() ? "none" : String.join(", ", have))
            + "; TensorFlow " + (s.get("tensorflow") == null ? "absent (':tf status')" : G4Json.str(s, "tensorflow", "?"))
            + "; sphere_graph: " + (int) G4Json.num(s, "graph_ops", 0) + " core ATen operations");
    }

    private static void tfStatus(String input, CommandExecutionContext ctx) {
        MlCommands.background("tf-status", () -> {
            try {
                final Map<String, Object> s = MlCommands.engine("torch").call("graph_status", Map.of()).get();
                final Object tf = s.get("tensorflow");
                if (tf != null) {
                    AppLogger.info("TensorFlow " + tf + " is in " + G4Json.str(s, "executable", "?") + ": ':ml train ... tensorflow' trains on it;"
                        + " its Keras models still reach Java and C++ as graphs through ':tf export' (Keras on torch)");
                } else {
                    AppLogger.info("TensorFlow is in no Python Sphere uses here (" + G4Json.str(s, "executable", "?") + ", Python "
                        + G4Json.str(s, "python", "?") + "): PyPI has no TensorFlow for Python 3.14 on Windows.");
                }
                AppLogger.raw("  Keras " + G4Json.str(s, "keras", "absent") + " is TensorFlow's API and runs here on PyTorch: a model built or saved"
                    + " with it (.keras, .h5) becomes a graph with ':tf export', which Java and C++ run with neither TensorFlow nor PyTorch.");
                AppLogger.raw("  TensorFlow's own files -- a frozen graph (.pb), a SavedModel folder -- are read without it by ':tf import'"
                    + " (sphere_tf: the protobuf decoded, the checkpoint's variables restored, the functions inlined).");
                AppLogger.raw("  TensorFlow itself, if wanted: a Python 3.12 or 3.13 (py -3.12 -m venv tfenv; tfenv\\Scripts\\pip install tensorflow),"
                    + " or WSL's Python; then ML_PYTHON in settings.conf. Sphere installs nothing by itself.");
            } catch (Exception e) {
                AppLogger.warn("The engine: " + MlCommands.failure(e));
            }
        });
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    private static void export(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":torch export");
        if (a.words().isEmpty()) {
            Handlers.usage(":torch export <script.py> [--name n] [--feeds particles|high_level]  (the script defines model and example)");
            return;
        }
        final Path script = Handlers.resolve(a.word(0, "")).toPath().toAbsolutePath();
        exportWith(a, script, "script", "PyTorch");
    }

    private static void tfExport(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":tf export");
        if (a.words().isEmpty()) {
            Handlers.usage(":tf export <model.keras|model.h5|script.py> [--shape 16,7;16] [--name n] [--feeds particles|high_level]");
            return;
        }
        final Path file = Handlers.resolve(a.word(0, "")).toPath().toAbsolutePath();
        exportWith(a, file, file.toString().toLowerCase(Locale.ROOT).endsWith(".py") ? "script" : "keras", "Keras on its torch backend");
    }

    private static void tfImport(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":tf import");
        if (a.words().isEmpty()) {
            Handlers.usage(":tf import <model.pb|saved_model_dir> [--inputs a,b] [--outputs y] [--shape 16,7;16] [--fix training=false]"
                + " [--name n] [--feeds particles|high_level]");
            return;
        }
        exportWith(a, Handlers.resolve(a.word(0, "")).toPath().toAbsolutePath(), "tf", "TensorFlow, read without it");
    }

    private static void exportWith(MlCommands.Args a, Path file, String kind, String what) {
        if (kind.equals("tf") ? !Files.exists(file) : !Files.isRegularFile(file)) {
            AppLogger.error("No " + (kind.equals("tf") ? "model " : "file ") + file);
            return;
        }
        final Map<String, Object> args = new LinkedHashMap<>();
        args.put("kind", kind);
        if (a.flags().containsKey("name")) {
            args.put("name", a.flags().get("name"));
        }
        if (a.flags().containsKey("feeds")) {
            args.put("feeds", a.flags().get("feeds"));
        }
        if (a.flags().containsKey("shape")) {
            final List<List<Integer>> shapes = new ArrayList<>();
            for (String row : a.flags().get("shape").split(";")) {
                shapes.add(java.util.Arrays.stream(row.split(",")).map(String::strip).filter(t -> !t.isEmpty()).map(Integer::valueOf).toList());
            }
            args.put("shapes", shapes);
        }
        for (String k : List.of("inputs", "outputs")) {
            if (a.flags().containsKey(k)) {
                args.put(k, java.util.Arrays.stream(a.flags().get(k).split(",")).map(String::strip).filter(t -> !t.isEmpty()).toList());
            }
        }
        if (a.flags().containsKey("fix")) {   // inputs made constants: training=false, keep_prob=1
            final Map<String, Object> fixed = new LinkedHashMap<>();
            for (String pair : a.flags().get("fix").split(",")) {
                final String[] kv = pair.split("=", 2);
                final String v = kv.length == 2 ? kv[1].strip().toLowerCase(Locale.ROOT) : "";
                try {
                    fixed.put(kv[0].strip(), switch (v) {
                        case "true", "yes", "on" -> 1.0;
                        case "false", "no", "off" -> 0.0;
                        default -> Double.parseDouble(v);
                    });
                } catch (NumberFormatException ex) {
                    AppLogger.error("--fix " + pair.strip() + ": name=value, the value a number or true/false (training=false, keep_prob=1)");
                    return;
                }
            }
            args.put("fixed", fixed);
        }
        MlCommands.background("graph-export", () -> {
            try {
                final MlEngine e = MlCommands.engine(kind.equals("tf") ? "numpy" : "torch");
                args.put(kind.equals("script") ? "script" : "path", e.path(file));
                AppLogger.info(kind.equals("tf") ? "Reading " + file.getFileName() + " (" + what + ") in a Python of its own: the protobuf"
                    + " decoded, the variables restored, each operation converted as it runs..."
                    : "Exporting " + file.getFileName() + " (" + what + ") in a Python of its own: torch.export, core ATen, the graph...");
                final Map<String, Object> r = e.call("graph_export", args, (f, m) -> AppLogger.stream("  " + m)).get();
                exported(r);
            } catch (Exception ex) {
                AppLogger.error("export: " + MlCommands.failure(ex));
            }
        });
    }

    private static void exported(Map<String, Object> r) throws IOException {
        final String name = G4Json.str(r, "name", "?");
        final Map<String, Object> check = G4Json.obj(r, "check");
        final boolean same = Boolean.TRUE.equals(check.get("same"));
        AppLogger.raw(String.format(Locale.ROOT, "  %s: %d operations, %d weights; inputs %s%s; %s; batch %s; rows %s", name,
            (int) G4Json.num(r, "nodes", 0), (long) G4Json.num(r, "weights", 0), String.join(", ", strings(G4Json.list(r, "inputs"))),
            G4Json.str(r, "feeds", "").isEmpty() ? "" : " (feeds " + G4Json.str(r, "feeds", "") + ")", G4Json.str(r, "precision", "?"),
            G4Json.str(r, "batch", "?"), Boolean.TRUE.equals(r.get("rows_independent")) ? "independent (chunks change nothing)" : "coupled (run whole)"));
        final Map<String, Object> ops = G4Json.obj(r, "ops");
        final List<Map.Entry<String, Object>> top = new ArrayList<>(ops.entrySet());
        top.sort((x, y) -> Double.compare(((Number) y.getValue()).doubleValue(), ((Number) x.getValue()).doubleValue()));
        AppLogger.raw("  operations: " + String.join(", ", top.stream().limit(10).map(en -> en.getKey().replaceAll("\\.default$", "") + " x"
            + ((Number) en.getValue()).intValue()).toList()) + (top.size() > 10 ? ", ... (" + top.size() + " kinds)" : ""));
        final Map<String, Object> mem = G4Json.obj(r, "memory");
        if (!mem.isEmpty()) {
            AppLogger.raw(String.format(Locale.ROOT, "  memory: %.3f MB a row at its peak, %.2f MB of weights%s", G4Json.num(mem, "per_row_mb", 0),
                G4Json.num(mem, "weights_mb", 0), Boolean.TRUE.equals(mem.get("linear")) ? "" : " (not linear in the batch: run whole)"));
        }
        final Map<String, Object> tf = G4Json.obj(r, "tf");
        if (!tf.isEmpty()) {   // a TensorFlow model read by sphere_tf: no framework here to hold it to
            AppLogger.raw(String.format(Locale.ROOT, "  TensorFlow %s: %d nodes of %d kinds read, %d variables restored from the checkpoint;"
                + " inputs %s, outputs %s", G4Json.str(tf, "kind", "?"), (int) G4Json.num(tf, "nodes", 0), (int) G4Json.num(tf, "kinds", 0),
                (int) G4Json.num(tf, "variables", 0), strings(G4Json.list(tf, "inputs")), strings(G4Json.list(tf, "outputs"))));
            final boolean ok = G4Json.num(tf, "consistent", 1) == 0;
            final Object free = tf.get("batch_free");
            final String line = String.format(Locale.ROOT, "  %s: the graph saved runs as the conversion did, to %.1e; %s", ok ? "CONSISTENT"
                : "INCONSISTENT", G4Json.num(tf, "consistent", 0), free == null ? "its shapes fixed, no batch" : Boolean.TRUE.equals(free)
                ? "it runs at batches 1, 3 and 17, its batch free" : "its batch is NOT free");
            if (ok) {
                AppLogger.success(line);
            } else {
                AppLogger.warn(line);
            }
            final List<?> scalars = G4Json.list(tf, "scalars");
            if (scalars != null && !scalars.isEmpty()) {
                AppLogger.raw("  " + strings(scalars) + ": no shape in the file, so taken for scalars, the model running so;"
                    + " --shape gives their rows if they have some");
            }
            final Map<String, Object> fixed = G4Json.obj(tf, "fixed");
            if (!fixed.isEmpty()) {
                AppLogger.raw("  fixed: " + String.join(", ", fixed.entrySet().stream().map(en -> en.getKey() + " = " + (en.getValue()
                    instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) ? String.valueOf(n.longValue()) : String.valueOf(en.getValue())))
                    .toList()) + ": constants now, the conditions they chose resolved at import");
            }
            final List<?> flags = G4Json.list(tf, "flags");
            if (flags != null && !flags.isEmpty()) {
                AppLogger.raw("  " + strings(flags) + ": " + (flags.size() == 1 ? "an input" : "inputs") + " of one value choosing between"
                    + " branches; --fix " + String.join(",", flags.stream().map(f -> f + "=<value>").toList()) + " keeps only the branch"
                    + (flags.size() == 1 ? " it takes" : "es they take")
                    + (Boolean.TRUE.equals(tf.get("flags_unbatch")) ? ", and the graph, its other inputs batched, then runs in chunks" : ""));
            }
            final List<?> control = G4Json.list(tf, "control");
            for (Object note : control == null ? List.of() : control) {
                AppLogger.raw("  control flow: " + note);
            }
            AppLogger.raw("  TensorFlow is not here to run the model: its operations follow TensorFlow's definitions as sphere_tf reads them,"
                + " which Sphere's tests hold to models TensorFlow wrote (their outputs known) and to PyTorch's own operations");
        } else {
            final String line = String.format(Locale.ROOT, "  %s against the framework at batches %s it was not traced on: |difference| %.2e, %.1f units"
                + " of %s's rounding at its scale", same ? "SAME" : "DIFFERENT", G4Json.list(check, "sizes") == null ? "?" : G4Json.list(check, "sizes")
                .stream().map(v -> String.valueOf(((Number) v).intValue())).toList(), G4Json.num(check, "max_abs", 0),
                G4Json.num(check, "eps_units", 0), G4Json.str(check, "precision", "float64"));
            if (same) {
                AppLogger.success(line);
            } else {
                AppLogger.warn(line);
            }
        }
        final Map<String, Object> opt = G4Json.obj(r, "optimized");
        if (!opt.isEmpty()) {
            AppLogger.raw(String.format(Locale.ROOT, "  optimized: %d -> %d nodes (%d merged, %d rewritten, %d mm+bias fused into addmm), %s",
                (int) G4Json.num(opt, "before", 0), (int) G4Json.num(opt, "after", 0), (int) G4Json.num(opt, "merged", 0),
                (int) G4Json.num(opt, "rewritten", 0), (int) G4Json.num(opt, "fused", 0), Boolean.TRUE.equals(opt.get("verified"))
                    ? "the same to the bit on the example" : Boolean.TRUE.equals(opt.get("reverted")) ? "undone: a bit changed" : "unverified"));
        }
        AppLogger.raw(String.format(Locale.ROOT, "  exported in %.1f s by a Python that took %.0f MB, given back", G4Json.num(r, "seconds", 0),
            G4Json.num(r, "peak_mb", 0)));
        final Path graph = graphPath(name), ref = referencePath(name);
        if (Files.isRegularFile(graph) && Files.isRegularFile(ref)) {
            javaCheck(NeuralGraph.load(graph), ref);
        }
        AppLogger.raw("  next: ':torch run " + name + " <dataset>', ':torch check " + name + " --cpp', ':torch cpp " + name + "'"
            + (G4Json.str(r, "feeds", "").isEmpty() ? "" : ", ':ml tag " + name + "'"));
    }

    private static List<String> strings(List<?> l) {
        return l == null ? List.of() : l.stream().map(String::valueOf).toList();
    }

    // ------------------------------------------------------------------
    // The graphs, checked
    // ------------------------------------------------------------------

    static Path graphPath(String name) {
        return name.endsWith(".spx") ? Handlers.resolve(name).toPath() : MlCommands.models().resolve(name + ".sng.spx");
    }

    private static Path referencePath(String name) {
        final String base = Path.of(name).getFileName().toString().replaceAll("\\.sng\\.spx$", "");
        return Bridge.folder().resolve("ml").resolve(base + ".graphref.spx");
    }

    private static void models(String input, CommandExecutionContext ctx) {
        graphs(true);
    }

    private static void graphs(boolean detail) {
        final Path dir = MlCommands.models();
        final List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".sng.spx")).sorted().forEach(files::add);
            } catch (IOException e) {
                AppLogger.warn("models: " + e.getMessage());
            }
        }
        if (files.isEmpty()) {
            AppLogger.raw("  graphs: none yet (':torch export <script.py>', ':tf export <model.keras>')");
            return;
        }
        AppLogger.raw("  graphs in " + dir + ": " + files.size());
        if (!detail) {
            return;
        }
        for (Path f : files) {
            try {
                final NeuralGraph g = NeuralGraph.load(f);
                AppLogger.raw(String.format(Locale.ROOT, "  %s%n      %s; batch %s; %s; %.1f kB a row at its peak%s", g, g.precision, g.batch,
                    g.rowsIndependent() ? "rows independent" : "rows coupled", g.perRowPeak() / 1024,
                    Files.isRegularFile(referencePath(g.name)) ? "; numpy's reference kept" : ""));
            } catch (Exception e) {
                AppLogger.warn("  " + f.getFileName() + ": " + MlCommands.failure(e));
            }
        }
    }

    private static boolean javaCheck(NeuralGraph g, Path ref) throws IOException {
        final NeuralGraph.Agreement a = g.agree(ref);
        final String line = String.format(Locale.ROOT, "  Java (NeuralGraph): |Java - numpy| = %.1e on %s's reference (%.1f ms); %s",
            a.vsNumpy(), g.name, a.millis(), !g.rowsIndependent() ? "rows coupled: run whole"
                : a.bitwise() ? String.format(Locale.ROOT, "in chunks of %d and on %d threads the same to the bit", a.chunks().chunk, a.threads().threads)
                : String.format(Locale.ROOT, "chunks differ by %.1e, threads by %.1e", a.chunked(), a.threaded()));
        final boolean ok = a.vsNumpy() <= 1e-12 && a.bitwise();
        if (ok) {
            AppLogger.success(line);
        } else {
            AppLogger.warn(line);
        }
        return ok;
    }

    private static void check(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":torch check");
        if (a.words().isEmpty()) {
            Handlers.usage(":torch check <graph> [--cpp]");
            return;
        }
        final Path graph = graphPath(a.word(0, "")), ref = referencePath(a.word(0, ""));
        final boolean cpp = a.flags().containsKey("cpp");
        MlCommands.background("torch-check", () -> {
            try {
                if (!Files.isRegularFile(ref)) {
                    throw new IOException("no reference " + ref.getFileName() + ": ':torch export' writes it with the graph");
                }
                final NeuralGraph g = NeuralGraph.load(graph);
                AppLogger.info(g + "; " + g.precision + ", batch " + g.batch);
                javaCheck(g, ref);
                if (cpp) {
                    final Map<String, String> r = Gcc.tool("check", List.of(graph.toString(), ref.toString()), AppLogger::stream);
                    final double vs = Double.parseDouble(r.getOrDefault("vs_numpy", "NaN"));
                    final boolean bitwise = "0".equals(r.get("chunked")) && "0".equals(r.get("threaded"));
                    final String line = String.format(Locale.ROOT, "  C++ (sphere_graph.hpp): |C++ - numpy| = %.1e (%s ms); %s", vs, r.getOrDefault("millis", "?"),
                        !"1".equals(r.get("rows_independent")) ? "rows coupled: run whole" : bitwise ? "in chunks of " + r.get("chunk") + " and on "
                            + r.get("threads") + " threads the same to the bit" : "chunks differ by " + r.get("chunked") + ", threads by " + r.get("threaded"));
                    if (vs <= 1e-12 && bitwise) {
                        AppLogger.success(line);
                    } else {
                        AppLogger.warn(line);
                    }
                }
            } catch (Exception e) {
                AppLogger.error("check: " + MlCommands.failure(e));
            }
        });
    }

    // ------------------------------------------------------------------
    // A dataset through a graph
    // ------------------------------------------------------------------

    private static void run(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":torch run");
        if (a.words().size() < 2) {
            Handlers.usage(":torch run <graph> <dataset> [--out name] [--engine java|c++] [--memory MB] [--threads n]");
            return;
        }
        final Path graph = graphPath(a.word(0, "")), data = MlCommands.dataset(a.word(1, ""));
        final String base = graph.getFileName().toString().replaceAll("\\.sng\\.spx$", "");
        final Path out = Bridge.folder().resolve(SpxExport.safeName(a.flags().getOrDefault("out", data.getFileName().toString()
            .replaceAll("\\.spx$", "") + "_" + base)) + ".spx");
        final boolean cpp = a.flags().getOrDefault("engine", "java").toLowerCase(Locale.ROOT).startsWith("c");
        final double memory = a.real("memory", 0) * MB;
        final int threads = a.num("threads", 0);
        MlCommands.background("torch-run", () -> {
            try {
                if (!Files.isRegularFile(data)) {
                    throw new IOException("no dataset " + data);
                }
                final NeuralGraph g = NeuralGraph.load(graph);
                final long t0 = System.nanoTime();
                final String how;
                if (cpp) {
                    final List<String> args = new ArrayList<>(List.of(graph.toString(), data.toString(), out.toString()));
                    if (memory > 0 || threads > 0) {
                        args.add(memory > 0 ? String.valueOf(memory / MB) : "-1");
                        args.add(String.valueOf(threads));
                    }
                    final Map<String, String> r = Gcc.tool("run", args, AppLogger::stream);
                    how = String.format(Locale.ROOT, "C++: %s rows in %s chunks of %s on %s threads, %.2f MB held at most of the %.1f MB allowed%s",
                        r.get("rows"), r.get("chunks"), r.get("chunk"), r.get("threads"), Double.parseDouble(r.getOrDefault("peak", "0")) / MB,
                        Double.parseDouble(r.getOrDefault("memory", "0")) / MB, Double.parseDouble(r.getOrDefault("spilled", "0")) > 0
                            ? String.format(Locale.ROOT, ", %.1f MB through temporary files", Double.parseDouble(r.get("spilled")) / MB) : "");
                } else {
                    final NeuralGraph.RunInfo r = g.runFile(data, out, memory > 0 ? memory : NeuralGraph.budget(), threads);
                    how = "Java: " + r;
                }
                final double s = (System.nanoTime() - t0) / 1e9;
                AppLogger.success(String.format(Locale.ROOT, "%s over %s in %.2f s -- %s; the outputs in %s", g.name, data.getFileName(), s, how,
                    out.getFileName()));
                summarize(g, out);
            } catch (Exception e) {
                AppLogger.error("run: " + MlCommands.failure(e));
            }
        });
    }

    /** A classifier's outputs read back: P(signal) as the model ends with it, its AUC on the labels, its histograms to the Plots tab. */
    private static void summarize(NeuralGraph g, Path out) throws IOException {
        if (Files.size(out) > 512 * MB) {
            return;
        }
        try (Spx.Reader r = new Spx.Reader(out)) {
            final Map<String, String> meta = r.meta();
            final String shape = meta.getOrDefault("Shape.out.0", "");
            final int[] s = shape.isBlank() ? new int[0] : java.util.Arrays.stream(shape.split(",")).mapToInt(v -> Integer.parseInt(v.strip())).toArray();
            final NeuralGraph.Score score = NeuralGraph.signal(new NeuralGraph.Tensor(r.f64("out.0"), s));
            if (score == null) {
                return;
            }
            final double[] p = score.p();
            final double[] y = r.has("y") ? r.numbers("y") : null;
            String auc = "";
            if (y != null && y.length == p.length) {
                final double v = auc(p, y);
                auc = Double.isNaN(v) ? "" : String.format(Locale.ROOT, "; AUC %.4f on its labels", v);
            }
            AppLogger.raw("  P(signal): " + score.reading() + auc);
            final int bins = 40;
            final double[] edges = IntStream.rangeClosed(0, bins).mapToDouble(b -> (double) b / bins).toArray();
            for (int cls = y == null ? -1 : 0; cls <= (y == null ? -1 : 1); cls++) {
                final double[] counts = new double[bins];
                for (int i = 0; i < p.length; i++) {
                    if (cls < 0 || (int) y[i] == cls) {
                        counts[Math.min(bins - 1, (int) (p[i] * bins))]++;
                    }
                }
                final String tag = cls < 0 ? "" : cls == 0 ? " background" : " signal";
                SpxExport.histogram("P(signal) " + g.name + tag, "P(signal)", edges, counts, null, null, null,
                    Bridge.outbox().resolve(SpxExport.safeName("torch_" + g.name + tag.strip()) + ".spx"));
            }
        }
    }

    /** The area under the ROC curve: the Mann-Whitney statistic, ties counted half. */
    static double auc(double[] p, double[] y) {
        final Integer[] order = IntStream.range(0, p.length).boxed().toArray(Integer[]::new);
        java.util.Arrays.sort(order, (u, v) -> Double.compare(p[u], p[v]));
        double ranks = 0;
        long pos = 0;
        for (int i = 0; i < order.length; ) {
            int j = i;
            while (j + 1 < order.length && p[order[j + 1]] == p[order[i]]) {
                j++;
            }
            final double rank = 0.5 * (i + j) + 1;
            for (int k = i; k <= j; k++) {
                if (y[order[k]] > 0.5) {
                    ranks += rank;
                    pos++;
                }
            }
            i = j + 1;
        }
        final long neg = p.length - pos;
        return pos == 0 || neg == 0 ? Double.NaN : (ranks - pos * (pos + 1) / 2.0) / ((double) pos * neg);
    }

    private static void audit(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":torch audit");
        if (a.words().size() < 2) {
            Handlers.usage(":torch audit <graph> <dataset> [--jets 400]");
            return;
        }
        final Path graph = graphPath(a.word(0, "")), data = MlCommands.dataset(a.word(1, ""));
        final int jets = a.num("jets", 400);
        MlCommands.background("torch-audit", () -> {
            try {
                final NeuralGraph g = NeuralGraph.load(graph);
                final com.sphere.core.ml.GraphAudit.Report r = com.sphere.core.ml.GraphAudit.run(g, data, jets, AppLogger::raw);
                for (com.sphere.core.ml.GraphAudit.Check c : r.checks()) {
                    final String line = String.format(Locale.ROOT, "  [%s] %s: %s", c.grade(), c.name(), c.finding());
                    switch (c.grade()) {
                        case "pass" -> AppLogger.success(line);
                        case "fail" -> AppLogger.error(line);
                        case "warn" -> AppLogger.warn(line);
                        default -> AppLogger.raw(line);
                    }
                }
                AppLogger.raw("  it reacts to: " + String.join(", ", r.features()));
                AppLogger.info(g.name + " (" + g.framework + "), on " + r.jets() + " jets: " + r.verdict());
            } catch (Exception e) {
                AppLogger.error("audit: " + MlCommands.failure(e));
            }
        });
    }

    // ------------------------------------------------------------------
    // The jets of the loaded events through a graph (':ml tag <graph>')
    // ------------------------------------------------------------------

    static void tag(Path file, List<List<PseudoJet>> events, JetDefinition def, double ptmin, int njets, int bins) {
        MlCommands.background("ml-tag", () -> {
            try {
                final NeuralGraph g = NeuralGraph.load(file);
                final boolean particles = g.feeds.equals("particles");
                if (!particles && !g.feeds.equals("high_level")) {
                    throw new IllegalStateException(g.name + " was exported without feeds: export it with feeds=\"particles\" (x [B, P, "
                        + MlData.FEATURES.size() + "] and mask [B, P], as ':ml jets' writes them) or feeds=\"high_level\" ([B, " + MlData.HIGH_LEVEL.size() + "])");
                }
                final int[][] ex = g.exampleShapes();
                final int points = particles ? ex[0][1] : 0, features = particles ? ex[0][2] : ex[0][1];
                final int want = particles ? MlData.FEATURES.size() : MlData.HIGH_LEVEL.size();
                if (features != want || particles && g.inputNames.size() < 2) {
                    throw new IllegalStateException(g.name + " takes rows of " + features + " features" + (particles ? " and a mask" : "")
                        + ", Sphere's jets give " + want + (particles ? " a particle (" + MlData.FEATURES + ")" : " (" + MlData.HIGH_LEVEL + ")"));
                }
                final List<PseudoJet> jets = new ArrayList<>();
                for (ClusterSequence c : EventBatch.cluster(events, def, Runtime.getRuntime().availableProcessors())) {
                    final List<PseudoJet> found = PseudoJet.sortedByPt(c.inclusiveJets(ptmin));
                    for (int k = 0; k < njets && k < found.size(); k++) {
                        if (!found.get(k).constituents().isEmpty()) {
                            jets.add(found.get(k));
                        }
                    }
                }
                if (jets.isEmpty()) {
                    AppLogger.warn("No jet above " + ptmin + " GeV");
                    return;
                }
                // in blocks the memory affords: a block's inputs are made only when it runs
                final long fit = g.batchFor(NeuralGraph.budget());
                final int block = (int) Math.max(1, Math.min(8192, fit < 0 ? 512 : fit));
                final double[] score = new double[jets.size()];
                String reading = "";
                final long t0 = System.nanoTime();
                for (int lo = 0; lo < jets.size(); lo += block) {
                    final int hi = Math.min(jets.size(), lo + block), n = hi - lo;
                    final List<NeuralGraph.Tensor> in = new ArrayList<>();
                    if (particles) {
                        final double[] x = new double[n * points * features], mask = new double[n * points];
                        for (int i = 0; i < n; i++) {
                            final double[] m = new double[points];
                            final double[][] pf = MlData.particles(jets.get(lo + i), points, m);
                            for (int q = 0; q < points; q++) {
                                System.arraycopy(pf[q], 0, x, (i * points + q) * features, features);
                            }
                            System.arraycopy(m, 0, mask, i * points, points);
                        }
                        in.add(new NeuralGraph.Tensor(x, n, points, features));
                        in.add(new NeuralGraph.Tensor(mask, n, points));
                        if (g.inputNames.size() > 2) {   // a third input: the jet's observables
                            final double[] h = new double[n * MlData.HIGH_LEVEL.size()];
                            for (int i = 0; i < n; i++) {
                                System.arraycopy(MlData.highLevel(jets.get(lo + i)), 0, h, i * MlData.HIGH_LEVEL.size(), MlData.HIGH_LEVEL.size());
                            }
                            in.add(new NeuralGraph.Tensor(h, n, MlData.HIGH_LEVEL.size()));
                        }
                    } else {
                        final double[] h = new double[n * features];
                        for (int i = 0; i < n; i++) {
                            System.arraycopy(MlData.highLevel(jets.get(lo + i)), 0, h, i * features, features);
                        }
                        in.add(new NeuralGraph.Tensor(h, n, features));
                    }
                    final NeuralGraph.Score s = NeuralGraph.signal(g.run(in).get(0));
                    if (s == null) {
                        throw new IllegalStateException(g.name + "'s output is not a classifier's ([n] or [n, classes])");
                    }
                    System.arraycopy(s.p(), 0, score, lo, n);
                    reading = s.reading();
                }
                final double us = (System.nanoTime() - t0) / 1e3 / jets.size();
                final double[] edges = IntStream.rangeClosed(0, bins).mapToDouble(b -> (double) b / bins).toArray(), counts = new double[bins];
                int tagged = 0;
                for (double s : score) {
                    counts[Math.min(bins - 1, (int) (s * bins))]++;
                    tagged += s > 0.5 ? 1 : 0;
                }
                final Path out = SpxExport.histogram("score " + g.name, "P(signal)", edges, counts, null, null, null,
                    Bridge.outbox().resolve(SpxExport.safeName("mltag_" + g.name) + ".spx"));
                AppLogger.success(String.format(Locale.ROOT, "%s (a graph of %s, run in Java): %d jets of %d events, %d (%.1f %%) with P(signal) > 0.5,"
                    + " %.0f us per jet in blocks of %d; P(signal) is %s; scores in the Plots tab (%s)", g.name, g.framework, jets.size(), events.size(),
                    tagged, 100.0 * tagged / jets.size(), us, block, reading, out.getFileName()));
                final List<Integer> order = new ArrayList<>(IntStream.range(0, jets.size()).boxed().toList());
                order.sort((u, v) -> Double.compare(score[v], score[u]));
                for (int i = 0; i < Math.min(5, order.size()); i++) {
                    final PseudoJet j = jets.get(order.get(i));
                    AppLogger.raw(String.format(Locale.ROOT, "  pt %8.1f GeV  mass %7.1f GeV  P(signal) %.4f", j.pt(), j.m(), score[order.get(i)]));
                }
            } catch (Exception e) {
                AppLogger.error("tag: " + MlCommands.failure(e));
            }
        });
    }

    // ------------------------------------------------------------------
    // C++: a program and a ROOT macro for a graph, and the compiler that builds them
    // ------------------------------------------------------------------

    private static void cpp(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":torch cpp");
        if (a.words().isEmpty()) {
            Handlers.usage(":torch cpp <graph> [--compile]");
            return;
        }
        final Path graph = graphPath(a.word(0, ""));
        final boolean compile = a.flags().containsKey("compile");
        MlCommands.background("torch-cpp", () -> {
            try {
                final NeuralGraph g = NeuralGraph.load(graph);
                final Path dir = Bridge.folder().resolve("cpp");
                Files.createDirectories(dir);
                final String id = SpxExport.safeName(g.name).replaceAll("[^A-Za-z0-9_]", "_");
                final Path program = dir.resolve(id + "_run.cpp"), macro = dir.resolve(id + "_tag.C");
                final Path lib = Bridge.libraries();
                final String path = graph.toAbsolutePath().toString().replace('\\', '/');
                Files.writeString(program, String.join("\n",
                    "// " + id + "_run.cpp -- the graph " + g.name + " (" + g.framework + ") run from C++ with sphere_graph.hpp: no PyTorch, no",
                    "// TensorFlow. A dataset of any size streamed through it, its memory planned from the machine's free memory.",
                    "//",
                    "//   g++ -std=c++17 -O2 -I \"" + lib.toString().replace('\\', '/') + "\" " + id + "_run.cpp -o " + id + "_run",
                    "//   ./" + id + "_run <dataset.spx> <out.spx> [memory MB] [threads]",
                    "//",
                    "// In a Geant4 program, one const Graph serves every worker thread: run() is thread-safe; give each event's",
                    "// inputs as Tensor::wrap(pointer, shape) and read P(signal) from the output.",
                    "#include \"sphere_graph.hpp\"",
                    "",
                    "#include <cstdio>",
                    "#include <cstdlib>",
                    "",
                    "int main(int argc, char** argv) {",
                    "    if (argc < 3) {",
                    "        std::fprintf(stderr, \"usage: %s <dataset.spx> <out.spx> [memory MB] [threads]\\n\", argv[0]);",
                    "        return 2;",
                    "    }",
                    "    try {",
                    "        sphere::graph::Graph g(\"" + path + "\");",
                    "        std::printf(\"%s\\n\", g.describe().c_str());",
                    "        const double memory = argc > 3 ? std::atof(argv[3]) * 1048576.0 : -1;   // -1: what the machine spares now",
                    "        const auto r = g.run_file(argv[1], argv[2], memory, argc > 4 ? std::atoi(argv[4]) : 0);",
                    "        std::printf(\"%lld rows in %lld chunks of %lld on %d threads; %.2f MB held at most of %.1f MB; %.1f MB in temporary files\\n\",",
                    "                    (long long)r.rows, (long long)r.chunks, (long long)r.chunk, r.threads, r.peak / 1048576.0, r.memory / 1048576.0,",
                    "                    r.spilled / 1048576.0);",
                    "    } catch (const std::exception& e) {",
                    "        std::fprintf(stderr, \"%s\\n\", e.what());",
                    "        return 1;",
                    "    }",
                    "    return 0;",
                    "}",
                    ""), StandardCharsets.UTF_8);
                Files.writeString(macro, String.join("\n",
                    "// " + id + "_tag.C -- ROOT macro: the graph " + g.name + " over a dataset, P(signal) histogrammed. No PyTorch, no TensorFlow.",
                    "//   root -l '" + id + "_tag.C(\"jets\")'      (Sphere sets ROOT_INCLUDE_PATH; else .include " + lib.toString().replace('\\', '/') + ")",
                    "#include \"sphere_graph.hpp\"",
                    "",
                    "void " + id + "_tag(const char* dataset = \"jets\") {",
                    "    sphere::graph::Graph g(\"" + path + "\");",
                    "    const std::string out = std::string(dataset) + \"_" + id + "\";",
                    "    const auto r = g.run_file(dataset, out);",
                    "    printf(\"%s: %lld rows in %lld chunks on %d threads\\n\", g.describe().c_str(), (long long)r.rows, (long long)r.chunks, r.threads);",
                    "    spx::File f(spx::resolve(out));",
                    "    std::int64_t n = 0;",
                    "    const double* p = f.f64(\"out.0\", &n);",
                    "    const std::int64_t classes = r.rows ? n / r.rows : 1;",
                    "    auto* h = new TH1D(\"" + id + "\", \"" + g.name + ";P(signal);jets\", 40, 0, 1);",
                    "    for (std::int64_t i = 0; i < r.rows; ++i) {",
                    "        const double* row = p + i * classes;",
                    "        double s = row[classes - 1];",
                    "        if (classes > 1) {   // logits: a softmax, the last class",
                    "            double m = row[0], z = 0;",
                    "            for (std::int64_t c = 1; c < classes; ++c) m = std::max(m, row[c]);",
                    "            for (std::int64_t c = 0; c < classes; ++c) z += std::exp(row[c] - m);",
                    "            s = std::exp(row[classes - 1] - m) / z;",
                    "        } else if (s < 0 || s > 1) {",
                    "            s = 1 / (1 + std::exp(-s));",
                    "        }",
                    "        h->Fill(s);",
                    "    }",
                    "    h->Draw();",
                    "}",
                    ""), StandardCharsets.UTF_8);
                AppLogger.success("Written " + program + " and " + macro + " (the graph read from " + graph.getFileName() + ")");
                if (compile) {
                    final Path exe = dir.resolve(id + "_run" + (windows() ? ".exe" : ""));
                    Gcc.build(program, exe, AppLogger::stream);
                    AppLogger.success("Built " + exe + ": '" + exe.getFileName() + " <dataset.spx> <out.spx>'");
                }
            } catch (Exception e) {
                AppLogger.error("cpp: " + MlCommands.failure(e));
            }
        });
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    static String gpp() {
        try {
            return new SettingsManager().resolveTool("GPP_DIR", "g++");
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The machine's free memory now, in bytes (its available memory, on Windows). */
    static double free() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
            ? os.getFreeMemorySize() : Double.POSITIVE_INFINITY;
    }

    /**
     * g++ for a program of sphere_graph.hpp, its flags chosen by the memory free when it starts. Measured on this
     * header: -O2 peaks at 640 MB; -O2 with GCC's collector run often (ggc-min-expand=10, ggc-min-heapsize=16384)
     * at 440 MB for the same code in the same time; -O1 at 630 MB in half the time. With memory to spare, -pipe
     * keeps the compiler's intermediate files in memory; otherwise they go to temporary files on the disk, in the
     * bridge's folder. A watcher stops the compiler if the machine runs short, and it is tried again with the
     * lightest flags. A build is kept until its source or the headers change.
     */
    static final class Gcc {
        static final double KEEP = 300 * MB;   // left to everything else while g++ runs
        private static final double SHORT = 160 * MB;

        private Gcc() {
        }

        static List<String> flags(double room) {
            if (room >= 900 * MB) {
                return List.of("-O2", "-pipe");
            }
            if (room >= 480 * MB) {
                return List.of("-O2", "--param", "ggc-min-expand=10", "--param", "ggc-min-heapsize=16384");
            }
            return List.of("-O1", "--param", "ggc-min-expand=5", "--param", "ggc-min-heapsize=4096");
        }

        static String describe(List<String> flags) {
            return flags.contains("-pipe") ? "g++ -O2 -pipe (memory to spare)"
                : flags.contains("-O2") ? "g++ -O2, GCC's collector run often (440 MB instead of 640) and its files on disk"
                : "g++ -O1, GCC's collector at its tightest and its files on disk (the machine is short)";
        }

        static Path build(Path source, Path exe, Consumer<String> say) throws IOException, InterruptedException {
            final String gpp = gpp();
            if (gpp == null) {
                throw new IOException("no g++: GPP_DIR in settings.conf, or g++ on the PATH");
            }
            final Path include = Bridge.libraries();
            final String stamp = digest(source, include.resolve("sphere_graph.hpp"), include.resolve("sphere_spx.hpp"));
            final Path stampFile = exe.resolveSibling(exe.getFileName() + ".sha256");
            if (Files.isRegularFile(exe) && Files.isRegularFile(stampFile) && Files.readString(stampFile).strip().equals(stamp)) {
                return exe;
            }
            final Path tmp = Bridge.folder().resolve("cpp").resolve("tmp");
            Files.createDirectories(tmp);
            for (int attempt = 0; attempt < 2; attempt++) {
                final List<String> flags = flags(attempt == 0 ? free() - KEEP : 0);
                say.accept(String.format(Locale.ROOT, "  compiling %s: %.0f MB free, so %s ...", source.getFileName(), free() / MB, describe(flags)));
                final List<String> cmd = new ArrayList<>(List.of(gpp, "-std=c++17"));
                cmd.addAll(flags);
                if (windows()) {
                    cmd.add("-static");   // the program runs anywhere, the compiler's DLLs not needed
                }
                cmd.addAll(List.of("-I", include.toString(), source.toString(), "-o", exe.toString()));
                final ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
                final Path bin = Path.of(gpp).getParent();
                if (bin != null) {
                    pb.environment().merge("PATH", bin.toString(), (old, add) -> add + java.io.File.pathSeparator + old);
                }
                for (String k : List.of("TMP", "TEMP", "TMPDIR")) {
                    pb.environment().put(k, tmp.toString());
                }
                final long t0 = System.nanoTime();
                final Process p = pb.start();
                final List<String> tail = Collections.synchronizedList(new ArrayList<>());
                final Thread reader = Thread.ofPlatform().daemon().start(() -> {
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        for (String line; (line = in.readLine()) != null; ) {
                            tail.add(line);
                            if (tail.size() > 60) {
                                tail.remove(0);
                            }
                        }
                    } catch (IOException ignored) {
                        // the compiler gone
                    }
                });
                boolean starved = false;
                while (!p.waitFor(250, TimeUnit.MILLISECONDS)) {
                    if (free() < SHORT) {
                        starved = true;
                        p.descendants().forEach(ProcessHandle::destroyForcibly);
                        p.destroyForcibly();
                        p.waitFor(5, TimeUnit.SECONDS);
                        break;
                    }
                }
                reader.join(2000);
                if (starved) {
                    say.accept(String.format(Locale.ROOT, "  the machine fell to %.0f MB free: g++ stopped; again with the lightest flags", free() / MB));
                    continue;
                }
                if (p.exitValue() != 0) {
                    throw new IOException("g++ failed:\n" + String.join("\n", tail));
                }
                say.accept(String.format(Locale.ROOT, "  built in %.0f s", (System.nanoTime() - t0) / 1e9));
                Files.writeString(stampFile, stamp);
                return exe;
            }
            throw new IOException(String.format(Locale.ROOT, "too little memory to compile now (%.0f MB free): close something and try again", free() / MB));
        }

        /** Sphere's C++ engine for graphs, built once: its answer to a request, as key and value. */
        static Map<String, String> tool(String what, List<String> args, Consumer<String> say) throws IOException, InterruptedException {
            final Path lib = Bridge.libraries();
            final Path exe = build(lib.resolve("sphere_graph_tool.cpp"), Bridge.folder().resolve("cpp")
                .resolve("sphere_graph_tool" + (windows() ? ".exe" : "")), say);
            final List<String> cmd = new ArrayList<>(List.of(exe.toString(), what));
            cmd.addAll(args);
            final ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("SPHERE_BRIDGE", Bridge.folder().toString());
            final Process p = pb.start();
            final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IOException("the C++ engine: " + (err.isBlank() ? "exit " + p.exitValue() : err.strip()));
            }
            final Map<String, String> r = new LinkedHashMap<>();
            for (String line : out.split("\\R")) {
                final int sp = line.indexOf(' ');
                if (sp > 0) {
                    r.put(line.substring(0, sp), line.substring(sp + 1).strip());
                }
            }
            return r;
        }

        private static String digest(Path... files) throws IOException {
            try {
                final MessageDigest md = MessageDigest.getInstance("SHA-256");
                for (Path f : files) {
                    md.update(Files.readAllBytes(f));
                }
                return HexFormat.of().formatHex(md.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
        }
    }
}

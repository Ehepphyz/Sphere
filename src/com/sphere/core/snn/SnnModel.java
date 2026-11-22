package com.sphere.core.snn;

import com.sphere.core.ml.GraphTrainer;
import com.sphere.core.ml.NeuralGraph;
import com.sphere.core.ml.NeuralGraph.Tensor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The network's own neural network, run and trained by Sphere: an identifier's spelling in, the neuron it belongs to
 * out. The cortex trained it on every name of every neuron's API -- ROOT's and Geant4's classes, FastJet's, HepMC3's,
 * the Python packages' modules and names, tens of thousands -- and exported it as a portable graph; here Sphere's
 * NeuralGraph runs it with no framework, and Sphere's GraphTrainer fine-tunes it on the user's own programs (the
 * classes they derive from a module's, the fixes the healer made), so that what the network reads in code becomes the
 * user's: {@code :snn learn}.
 *
 * <p>An identifier is read exactly as the cortex reads it (sphere_snn.features): its character 2-, 3- and 4-grams with
 * ^ and $ at its ends, the shape of its letters, its first two letters, hashed by FNV-1a over UTF-8 into 512 counts of
 * unit length -- the same bytes, the same buckets, the same sums in the same order, so Java and Python give one model
 * the same numbers.
 */
public final class SnnModel {

    private SnnModel() {
    }

    public static final int DIM = 512;

    /** The keywords and commonest words of C++, Python and Julia, which tell nothing of a module. */
    static final Set<String> WORDS = Set.copyOf(java.util.Arrays.asList(("alignas alignof and asm auto bool break case catch char class const constexpr "
        + "const_cast continue decltype default delete do double dynamic_cast else enum explicit export extern false float "
        + "for friend goto if inline int long mutable namespace new noexcept not nullptr operator or private protected "
        + "public register reinterpret_cast return short signed sizeof static static_assert static_cast struct switch "
        + "template this throw true try typedef typeid typename union unsigned using virtual void volatile while include "
        + "define ifdef ifndef endif pragma main argc argv std cout cerr endl string vector size_t printf print def import "
        + "from lambda None True False self elif pass with yield assert global nonlocal raise async await function end "
        + "module begin let local println length push range len list dict set tuple str float int bool open close write "
        + "read append value values data result results size first second auto const_iterator iterator begin end name "
        + "type args kwargs").split(" ")));

    private static final Pattern BLOCK = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE = Pattern.compile("//[^\n]*");
    private static final Pattern QUOTED = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern WORD = Pattern.compile("\\b[A-Za-z_][A-Za-z0-9_]{2,}\\b");
    private static final Pattern MACRO = Pattern.compile("^[A-Z0-9_]+$");

    /** A neuron and how sure the model is. */
    public record Guess(String neuron, double p) {
    }

    private static Path models() {
        return Snn.folder().resolve("models");
    }

    static int fnv(String text) {
        int h = 0x811C9DC5;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xFF;
            h *= 0x01000193;
        }
        return h;
    }

    private static int bucket(String text) {
        return Integer.remainderUnsigned(fnv(text), DIM);
    }

    /** An identifier as the model reads it: sphere_snn.features, to the bit. */
    public static double[] features(String token) {
        final double[] v = new double[DIM];
        final String t = "^" + token + "$";
        for (int k = 2; k <= 4; k++) {
            for (int i = 0; i + k <= t.length(); i++) {
                v[bucket(t.substring(i, i + k))] += 1.0;
            }
        }
        final String shape = token.replaceAll("[0-9]+", "9").replaceAll("[a-z]+", "x").replaceAll("[A-Z]+", "X");
        v[bucket("shape:" + shape)] += 2.0;
        v[bucket("pre:" + token.substring(0, Math.min(2, token.length())))] += 1.5;
        double sum = 0.0;
        for (double x : v) {
            sum += x * x;
        }
        final double norm = sum > 0 ? Math.sqrt(sum) : 1.0;
        for (int i = 0; i < DIM; i++) {
            v[i] /= norm;
        }
        return v;
    }

    /** ROOT_TH1_H, FASTJET_VERSION: a macro, not a name of an API (TH1D and TF1 are). */
    static boolean macroLike(String name) {
        return MACRO.matcher(name).matches() && (name.contains("_") || name.length() > 6);
    }

    /** The identifiers of a source in any language, its keywords and commonest words left out: sphere_snn.code_tokens. */
    public static List<String> tokens(String code) {
        String text = BLOCK.matcher(code).replaceAll(" ");
        text = LINE.matcher(text).replaceAll("");
        text = QUOTED.matcher(text).replaceAll(" ");
        final Set<String> out = new LinkedHashSet<>();
        final Matcher m = WORD.matcher(text);
        while (m.find() && out.size() < 4000) {
            final String t = m.group();
            if (!WORDS.contains(t) && !macroLike(t)) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    private static NeuralGraph cached;
    private static Path cachedFrom;
    private static long cachedStamp;
    private static List<String> classes;

    /** The model the cortex trained, or the one Sphere fine-tuned on the user's programs when that one is newer. */
    static synchronized NeuralGraph graph() throws IOException {
        final Path base = models().resolve("snn_api.sng.spx");
        final Path user = models().resolve("snn_api_user.sng.spx");
        if (!Files.isRegularFile(base)) {
            throw new IOException("the network's neural model is not trained yet (':snn train')");
        }
        final Path take = Files.isRegularFile(user)
            && Files.getLastModifiedTime(user).compareTo(Files.getLastModifiedTime(base)) > 0 ? user : base;
        final long stamp = Files.getLastModifiedTime(take).toMillis();
        if (cached == null || !take.equals(cachedFrom) || stamp != cachedStamp) {
            cached = NeuralGraph.load(take);
            cachedFrom = take;
            cachedStamp = stamp;
            classes = Files.readAllLines(models().resolve("snn_api.classes.txt"), StandardCharsets.UTF_8).stream()
                .filter(s -> !s.isBlank()).toList();
        }
        return cached;
    }

    /** The model in use: the cortex's, or the user's fine-tuned one. */
    public static synchronized String which() throws IOException {
        graph();
        return cachedFrom.getFileName().toString();
    }

    /** For each identifier, the neurons the model reads in it, the likeliest first. */
    public static Map<String, List<Guess>> predict(List<String> tokens, int top) throws IOException {
        final Map<String, List<Guess>> out = new LinkedHashMap<>();
        if (tokens.isEmpty()) {
            return out;
        }
        final NeuralGraph g = graph();
        final List<String> names;
        synchronized (SnnModel.class) {
            names = classes;
        }
        final double[] x = new double[tokens.size() * DIM];
        for (int r = 0; r < tokens.size(); r++) {
            System.arraycopy(features(tokens.get(r)), 0, x, r * DIM, DIM);
        }
        final Tensor logits = g.run(List.of(new Tensor(x, tokens.size(), DIM))).get(0);
        final int k = logits.shape[1];
        for (int r = 0; r < tokens.size(); r++) {
            double max = Double.NEGATIVE_INFINITY;
            for (int c = 0; c < k; c++) {
                max = Math.max(max, logits.data[r * k + c]);
            }
            final double[] p = new double[k];
            double z = 0;
            for (int c = 0; c < k; c++) {
                p[c] = Math.exp(logits.data[r * k + c] - max);
                z += p[c];
            }
            final List<Guess> best = new ArrayList<>();
            final boolean[] taken = new boolean[k];
            for (int t = 0; t < Math.min(top, k); t++) {
                int arg = -1;
                for (int c = 0; c < k; c++) {
                    if (!taken[c] && (arg < 0 || p[c] > p[arg])) {
                        arg = c;
                    }
                }
                taken[arg] = true;
                best.add(new Guess(arg < names.size() ? names.get(arg) : "#" + arg, p[arg] / z));
            }
            out.put(tokens.get(r), best);
        }
        return out;
    }

    /** What a source uses, as the model reads it: neuron -> (score, the identifiers that said so). */
    public static Map<String, List<String>> read(String code, double threshold) throws IOException {
        final Map<String, List<String>> votes = new LinkedHashMap<>();
        final Map<String, Double> score = new LinkedHashMap<>();
        for (Map.Entry<String, List<Guess>> e : predict(tokens(code), 1).entrySet()) {
            final Guess g = e.getValue().get(0);
            if (g.p() >= threshold) {
                votes.computeIfAbsent(g.neuron(), n -> new ArrayList<>()).add(e.getKey());
                score.merge(g.neuron(), g.p(), Double::sum);
            }
        }
        final Map<String, List<String>> out = new LinkedHashMap<>();
        score.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
            .forEach(e -> out.put(e.getKey(), votes.get(e.getKey())));
        return out;
    }

    /**
     * Fine-tunes the model on the user's programs with Sphere's own trainer (Adam, as torch.optim steps it), from the
     * dataset the cortex wrote (models/snn_api_user.data.spx: the names the user's code gave the network, and a
     * sample of every neuron's API, so nothing learned before is forgotten). Writes models/snn_api_user.sng.spx.
     */
    public static GraphTrainer.Result learn(Consumer<String> log) throws IOException {
        final Path base = models().resolve("snn_api.sng.spx");
        final Path data = models().resolve("snn_api_user.data.spx");
        if (!Files.isRegularFile(data)) {
            throw new IOException("no dataset of the user's programs yet: the cortex writes it ('snn learn-data')");
        }
        final NeuralGraph g = NeuralGraph.load(base);
        final GraphTrainer.Options o = new GraphTrainer.Options();
        o.optimizer = "adam";
        o.hyper.put("lr", 1e-3);
        o.epochs = 6;
        o.batch = 64;
        o.val = 0.15;
        o.loss = GraphTrainer.Loss.CROSS_ENTROPY;
        o.name = "snn_api_user";
        final GraphTrainer.Result r = GraphTrainer.train(g, data, o, log, () -> false);
        synchronized (SnnModel.class) {
            cached = null;
        }
        return r;
    }
}

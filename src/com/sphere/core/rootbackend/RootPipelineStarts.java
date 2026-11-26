package com.sphere.core.rootbackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sphere.core.rootbackend.RootPipelineManifest.Input;
import static com.sphere.core.rootbackend.RootPipelineManifest.Kind;
import static com.sphere.core.rootbackend.RootPipelineRecipes.Family;
import static com.sphere.core.rootbackend.RootPipelineRecipes.Recipe;

/**
 * Everything a new pipeline can start from, and how to find it.
 *
 * The catalog of recipes is one way in. A physicist usually has something
 * already: a line of math on paper, a pipeline that is almost right, a
 * function in an old macro. Each of those becomes a recipe like any other --
 * kind, inputs, modules, body, and what the body needs above it -- so the
 * builder, the command line and the generator treat them all the same way.
 *
 * A formula can also start its own uncertainty, by linear propagation with
 * exact derivatives, or its derivative in one input; both are checked against
 * finite differences before they are handed over.
 */
public final class RootPipelineStarts {

    private RootPipelineStarts() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** What a formula that compares is told, kept as one constant so others can recognize it. */
    public static final String COMPARES = "It compares, so it is a filter: true keeps the entry.";

    /** How the note on what a macro function brings along begins. */
    private static final String BROUGHT = "Brought along from ";

    /**
     * A start, with what the user should know about how it was read.
     *
     * options, when there are some, are the build settings the start brings:
     * a copy keeps its original's, a function that keeps state is not
     * declared stateless.
     */
    public record Start(Recipe recipe, String suggestedName, List<String> notes, String error,
                        RootPipelineManifest options) {
        static Start fail(String why) {
            return new Start(null, null, List.of(), why, null);
        }

        public static Start of(Recipe recipe) {
            return new Start(recipe, null, List.of(), null, null);
        }

        public boolean ok() {
            return recipe != null;
        }
    }

    /** A recipe with how well it answered a search. */
    public record Hit(Recipe recipe, int score) { }

    /* ================================================================== */
    /* Search                                                              */
    /* ================================================================== */

    /**
     * The shorthand physicists write, mapped to the words the catalog uses.
     * One shorthand may stand for several words; all of them are searched.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("dr", "deltar angular distance"),
        Map.entry("deltar", "deltar angular distance"),
        Map.entry("dphi", "azimuthal difference"),
        Map.entry("deltaphi", "azimuthal difference"),
        Map.entry("phi", "azimuthal"),
        Map.entry("mt", "transverse mass"),
        Map.entry("mtw", "transverse mass"),
        Map.entry("m", "invariant mass"),
        Map.entry("mass", "invariant mass"),
        Map.entry("minv", "invariant mass"),
        Map.entry("mjj", "invariant mass"),
        Map.entry("mll", "invariant mass"),
        Map.entry("mee", "invariant mass"),
        Map.entry("mmumu", "invariant mass"),
        Map.entry("mgg", "invariant mass"),
        Map.entry("ht", "scalar sum ht"),
        Map.entry("met", "missing energy"),
        Map.entry("metsig", "missing energy significance"),
        Map.entry("iso", "isolation"),
        Map.entry("reliso", "relative isolation"),
        Map.entry("eta", "pseudorapidity"),
        Map.entry("y", "rapidity"),
        Map.entry("lead", "highest"),
        Map.entry("leading", "highest"),
        Map.entry("n", "count"),
        Map.entry("njets", "count above threshold"),
        Map.entry("z", "significance"),
        Map.entry("zsig", "significance"),
        Map.entry("sig", "significance"),
        Map.entry("signif", "significance"),
        Map.entry("asimov", "significance asimov"),
        Map.entry("soverb", "naive significance"),
        Map.entry("pval", "p-value"),
        Map.entry("pvalue", "p-value"),
        Map.entry("eff", "efficiency"),
        Map.entry("bw", "breit-wigner"),
        Map.entry("cb", "crystal ball"),
        Map.entry("pu", "pile-up"),
        Map.entry("pileup", "pile-up"),
        Map.entry("sf", "scale factor"),
        Map.entry("xs", "cross section"),
        Map.entry("xsec", "cross section"),
        Map.entry("lumi", "luminosity"),
        Map.entry("pdf", "parton distribution"),
        Map.entry("alphas", "strong coupling"),
        Map.entry("as", "strong coupling"),
        Map.entry("chi2", "chi-square"),
        Map.entry("cs", "collins-soper"),
        Map.entry("costheta", "collins-soper cos theta"),
        Map.entry("ctau", "decay length"),
        Map.entry("lxy", "decay length"),
        Map.entry("trig", "trigger turn-on"),
        Map.entry("turnon", "trigger turn-on"),
        Map.entry("hist", "histogram"),
        Map.entry("h", "histogram"),
        Map.entry("cut", "filter"),
        Map.entry("sel", "filter"),
        Map.entry("select", "filter"),
        Map.entry("window", "mass window"),
        Map.entry("llr", "log-likelihood ratio"),
        Map.entry("nll", "log-likelihood"),
        Map.entry("bkg", "background"),
        Map.entry("w", "weight"),
        Map.entry("bjorken", "bjorken")
    );

    /** Splits "invMassJJ", "delta_r", "mt2W" into lower-case words. */
    public static List<String> words(String text) {
        final List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        final String spaced = text
            .replaceAll("([a-z])([A-Z])", "$1 $2")
            .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
            .replaceAll("[^A-Za-z0-9\\-]+", " ");
        for (String one : spaced.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!one.isEmpty()) {
                out.add(one);
            }
        }
        return out;
    }

    /** The words searched for one typed word: itself, and what it stands for. */
    private static Set<String> expand(String word) {
        final Set<String> out = new LinkedHashSet<>();
        out.add(word);
        final String alias = ALIASES.get(word.replace("-", ""));
        if (alias != null) {
            out.addAll(List.of(alias.split(" ")));
        }
        return out;
    }

    /** One recipe's words, read once per search, in the forms a typed word can meet them. */
    private record Terms(String title, String about, String family, List<String> plain,
                         Set<String> pairs, Set<String> inputs) {
        static Terms of(Recipe recipe) {
            final List<String> titleWords = words(recipe.title());
            final List<String> plain = new ArrayList<>();
            for (String w : titleWords) {
                plain.add(w.replace("-", ""));
                if (w.contains("-")) {
                    for (String part : w.split("-")) {
                        if (!part.isEmpty()) {
                            plain.add(part);
                        }
                    }
                }
            }
            // "crystalball" is "crystal ball" typed without its space.
            final Set<String> pairs = new LinkedHashSet<>();
            for (int i = 0; i + 1 < titleWords.size(); i++) {
                pairs.add((titleWords.get(i) + titleWords.get(i + 1)).replace("-", ""));
            }
            final Set<String> inputs = new LinkedHashSet<>();
            for (Input one : recipe.inputs()) {
                inputs.add(one.name().toLowerCase(Locale.ROOT));
            }
            return new Terms(recipe.title().toLowerCase(Locale.ROOT).replace("-", "").replace(" ", ""),
                recipe.about() == null ? "" : recipe.about().toLowerCase(Locale.ROOT),
                recipe.family().label, plain, pairs, inputs);
        }

        int score(String term) {
            final String t = term.replace("-", "");
            if (t.isEmpty()) {
                return 0;
            }
            if (plain.contains(t)) {
                return 6;
            }
            if (t.length() >= 3) {
                for (String w : plain) {
                    if (w.startsWith(t)) {
                        return 5;
                    }
                }
            }
            if (pairs.contains(t)) {
                return 5;
            }
            if (t.length() >= 3 && title.contains(t)) {
                return 4;
            }
            // A typo: "brietwigner", "signifcance" -- and, since the box searches
            // as it is typed, a typo in the start of a word: "brietwig".
            if (t.length() >= 5) {
                final int allowed = t.length() >= 8 ? 2 : 1;
                final List<String> candidates = new ArrayList<>(plain);
                candidates.addAll(pairs);
                for (String w : candidates) {
                    if (w.length() < 4) {
                        continue;
                    }
                    if (RootFormula.distance(t, w) <= allowed
                            || (w.length() > t.length()
                                && RootFormula.distance(t, w.substring(0, t.length())) <= allowed)) {
                        return 3;
                    }
                }
            }
            if (t.length() >= 3 && about.contains(t)) {
                return 2;
            }
            if (family.contains(t)) {
                return 2;
            }
            return inputs.contains(t) ? 1 : 0;
        }
    }

    /** How well one recipe answers a query, 0 for not at all. */
    public static int score(Recipe recipe, String query) {
        final List<String> asked = words(query);
        if (asked.isEmpty()) {
            return 0;
        }
        if (recipe.body() == null) {
            // The empty recipe is no quantity, but it answers to its own words.
            for (String w : asked) {
                if (Set.of("empty", "blank", "nothing", "new").contains(w)) {
                    return 10;
                }
            }
            return 0;
        }
        final Terms terms = Terms.of(recipe);
        int total = 0;
        int answered = 0;
        for (String word : asked) {
            int direct = terms.score(word);
            if (direct > 0) {
                direct += 1;
            }
            // The words an alias stands for add up: "mjj" is "invariant mass",
            // and a title holding both words beats one holding only "mass".
            int expanded = 0;
            for (String term : expand(word)) {
                if (!term.equals(word)) {
                    expanded += terms.score(term);
                }
            }
            final int best = Math.max(direct, expanded);
            if (best > 0) {
                answered++;
            }
            total += best;
        }
        // A recipe that answers every word beats one that answers one word well.
        if (answered == asked.size() && asked.size() > 1) {
            total += 3;
        }
        return total;
    }

    /** The recipes that answer a query, best first. An empty query is the catalog. */
    public static List<Hit> search(String query, int limit) {
        final List<Hit> hits = new ArrayList<>();
        final boolean all = query == null || query.isBlank();
        for (Recipe one : RootPipelineRecipes.all()) {
            final int s = all ? 1 : score(one, query);
            if (s > 0) {
                hits.add(new Hit(one, s));
            }
        }
        if (!all) {
            hits.sort(Comparator.comparingInt(Hit::score).reversed());
        }
        return limit > 0 && hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    /**
     * The recipe a pipeline's name points to, when it clearly points to one.
     *
     * "deltaR_jj" is the angular distance, "zAsimov" the Asimov significance.
     * A name that matches several recipes equally is not a suggestion: being
     * told the wrong quantity is worse than not being told anything.
     */
    public static Recipe suggest(String pipelineName) {
        if (pipelineName == null || pipelineName.isBlank()) {
            return null;
        }
        // "mT_W" splits into m, t, w and loses the "mt" it was written as, so
        // the name is also tried run together, and the clearer reading wins.
        final String compact = String.join("", words(pipelineName));
        Recipe best = null;
        int bestMargin = 0;
        for (String reading : new String[]{pipelineName, compact}) {
            final List<Hit> hits = search(reading, 2);
            if (hits.isEmpty() || hits.get(0).score() < 6 || hits.get(0).recipe().body() == null) {
                continue;
            }
            final int margin = hits.size() > 1 ? hits.get(0).score() - hits.get(1).score()
                                               : hits.get(0).score();
            if (margin > bestMargin) {
                bestMargin = margin;
                best = hits.get(0).recipe();
            }
        }
        return bestMargin > 0 ? best : null;
    }

    /* ================================================================== */
    /* Start from a formula                                                */
    /* ================================================================== */

    /**
     * A transform, or a filter when it compares, from one line of math.
     *
     * The C++ is exactly the formula: no guard is slipped in. A NaN it can
     * produce is said in the notes, where it can be fixed, rather than turned
     * into a 0 that would pile up in the first bin of every histogram and
     * hide itself from :root pipeline check, fuzz and domain.
     */
    public static Start fromFormula(String formula) {
        final String text = formula == null ? "" : formula.trim();
        final RootFormula.Parsed p;
        try {
            p = RootFormula.parse(text);
        } catch (RootFormula.Problem problem) {
            return Start.fail(problem.explain(text));
        }
        final List<String> notes = new ArrayList<>();
        final List<Input> inputs = doubles(p.inputs());
        if (inputs.isEmpty()) {
            inputs.add(new Input("x", "double"));
            notes.add("The formula reads no input: the pipeline takes one, x, and leaves it unused.");
        } else {
            notes.add("Inputs read from the formula: " + String.join(", ", p.inputs()) + ".");
        }
        if (p.condition()) {
            notes.add(COMPARES);
        }
        notes.addAll(p.notes());
        notes.addAll(RootFormula.domain(p));
        notes.add(RootFormula.preview(p));
        foreignNote(p.root(), notes);

        final RootFormula.Shared shared = RootFormula.share(List.of(p.root()), names(inputs));
        final StringBuilder body = new StringBuilder();
        body.append("  // Written from: ").append(text).append('\n');
        definitions(body, shared);
        body.append("  return ").append(RootFormula.cpp(shared.outputs().get(0))).append(";\n");
        if (!shared.defs().isEmpty()) {
            notes.add(shared.defs().size() + (shared.defs().size() == 1 ? " subexpression is" : " subexpressions are")
                + " computed once and reused, in the same order of operations.");
        }
        final Recipe recipe = new Recipe(Family.FORMULA, "Formula: " + clip(text, 48),
            safe("From the formula " + text), p.condition() ? Kind.FILTER : Kind.TRANSFORM,
            List.copyOf(inputs), mathFor(body.toString()), body.toString(),
            algorithmFor(List.of(p.root())));
        return new Start(recipe, null, notes, null, null);
    }

    /**
     * The uncertainty of a formula by linear propagation.
     *
     * Each input x gets its error x_err; the derivatives are exact, shared
     * where they repeat, and checked against finite differences. Each input's
     * contribution is named and watched, so the Inspect tab shows the error
     * budget input by input, and the sum is taken with std::hypot for two or
     * three inputs, which neither overflows nor underflows.
     */
    public static Start fromUncertainty(String formula) {
        final String text = formula == null ? "" : formula.trim();
        final RootFormula.Parsed p;
        try {
            p = RootFormula.parse(text);
        } catch (RootFormula.Problem problem) {
            return Start.fail(problem.explain(text));
        }
        if (p.condition()) {
            return Start.fail("A condition is true or false: it has no uncertainty to propagate.");
        }
        if (p.inputs().isEmpty()) {
            return Start.fail("The formula reads no input, so it has no uncertainty.");
        }
        final Set<String> taken = new LinkedHashSet<>(p.inputs());
        final Map<String, String> errors = new LinkedHashMap<>();
        for (String x : p.inputs()) {
            errors.put(x, fresh(x + "_err", taken));
        }
        final Map<String, String> parts = new LinkedHashMap<>();
        for (String x : p.inputs()) {
            parts.put(x, fresh("c_" + x, taken));
        }
        final Map<String, RootFormula.Node> derivatives = new LinkedHashMap<>();
        try {
            for (String x : p.inputs()) {
                derivatives.put(x, RootFormula.derivative(p.root(), x));
            }
        } catch (RootFormula.Problem problem) {
            return Start.fail(problem.explain(text));
        }
        final List<RootFormula.Node> contributions = new ArrayList<>();
        for (String x : p.inputs()) {
            contributions.add(RootFormula.product(derivatives.get(x), RootFormula.variable(errors.get(x))));
        }
        final RootFormula.Shared shared = RootFormula.share(contributions, taken);

        final List<String> notes = new ArrayList<>();
        notes.add("Inputs: " + String.join(", ", p.inputs()) + ", then their errors "
            + String.join(", ", errors.values()) + ".");
        final StringBuilder body = new StringBuilder();
        body.append("  // Uncertainty of: ").append(text).append('\n');
        body.append("  // by linear propagation, the inputs' errors taken as independent:\n");
        body.append("  //   sigma^2 = sum over the inputs of (df/dx * x_err)^2\n");
        definitions(body, shared);
        final List<String> live = new ArrayList<>();
        for (int i = 0; i < p.inputs().size(); i++) {
            final String x = p.inputs().get(i);
            final RootFormula.Node out = shared.outputs().get(i);
            if (out instanceof RootFormula.Num zero && zero.value() == 0) {
                notes.add(x + " does not change the value: it adds nothing to the uncertainty.");
                continue;
            }
            final String c = parts.get(x);
            body.append("  const double ").append(c).append(" = ").append(RootFormula.cpp(out))
                .append(";  // from ").append(x).append('\n');
            body.append("  SPHERE_WATCH(").append(c).append(");\n");
            live.add(c);
        }
        body.append("  return ").append(quadrature(live)).append(";\n");

        notes.addAll(RootFormula.domain(p));
        notes.add(RootFormula.verify(p.root(), p.inputs(), derivatives));
        for (String one : RootFormula.breaks(derivatives, p.inputs())) {
            notes.add("The uncertainty is NaN wherever a slope is: " + one);
        }
        notes.add(budget(p, derivatives));
        if (!live.isEmpty()) {
            notes.add("Each contribution is watched: the Inspect tab shows the error budget, input by input.");
        }
        foreignNote(p.root(), notes);

        final List<Input> inputs = doubles(p.inputs());
        inputs.addAll(doubles(new ArrayList<>(errors.values())));
        final Recipe recipe = new Recipe(Family.FORMULA, "Uncertainty of " + clip(text, 40),
            safe("Uncertainty of " + text + " by linear propagation"), Kind.TRANSFORM,
            List.copyOf(inputs), mathFor(body.toString()), body.toString(),
            algorithmFor(shared.outputs()));
        return new Start(recipe, null, notes, null, null);
    }

    /** The derivative of a formula in one of its inputs, exact and checked. */
    public static Start fromDerivative(String formula, String input) {
        final String text = formula == null ? "" : formula.trim();
        final RootFormula.Parsed p;
        try {
            p = RootFormula.parse(text);
        } catch (RootFormula.Problem problem) {
            return Start.fail(problem.explain(text));
        }
        if (p.condition()) {
            return Start.fail("A condition is true or false: it has no derivative.");
        }
        if (input == null || input.isBlank()) {
            return Start.fail("In which input? Its inputs are " + String.join(", ", p.inputs()) + ".");
        }
        if (!p.inputs().contains(input)) {
            return Start.fail(input + " is not in the formula: its inputs are "
                + (p.inputs().isEmpty() ? "none" : String.join(", ", p.inputs())) + ".");
        }
        final RootFormula.Node d;
        try {
            d = RootFormula.derivative(p.root(), input);
        } catch (RootFormula.Problem problem) {
            return Start.fail(problem.explain(text));
        }
        final RootFormula.Shared shared = RootFormula.share(List.of(d), new LinkedHashSet<>(p.inputs()));
        final StringBuilder body = new StringBuilder();
        body.append("  // d/d").append(input).append(" of: ").append(text).append('\n');
        definitions(body, shared);
        body.append("  return ").append(RootFormula.cpp(shared.outputs().get(0))).append(";\n");

        final List<String> notes = new ArrayList<>();
        notes.add("Inputs read from the formula: " + String.join(", ", p.inputs()) + ".");
        notes.add("d/d" + input + " = " + clip(RootFormula.cpp(d), 110));
        notes.add(RootFormula.verify(p.root(), p.inputs(), Map.of(input, d)));
        notes.addAll(RootFormula.breaks(Map.of(input, d), p.inputs()));
        try {
            final Map<String, Double> at = RootFormula.typical(p.inputs());
            notes.add("At " + RootFormula.describe(at) + " it gives "
                + RootFormula.shortNumber(RootFormula.eval(d, at)) + ".");
        } catch (RootFormula.NotEvaluable notHere) {
            // verify() has said why already.
        }
        if (!RootFormula.smooth(p.root())) {
            notes.add("abs, min, max, a rounding, % or ? : make it piecewise: the derivative jumps where they switch.");
        }
        final Recipe recipe = new Recipe(Family.FORMULA, "d/d" + input + " of " + clip(text, 40),
            safe("Derivative in " + input + " of " + text), Kind.TRANSFORM, doubles(p.inputs()),
            mathFor(body.toString()), body.toString(), algorithmFor(shared.outputs()));
        return new Start(recipe, null, notes, null, null);
    }

    private static String quadrature(List<String> parts) {
        return switch (parts.size()) {
            case 0 -> "0.0";
            case 1 -> "std::abs(" + parts.get(0) + ")";
            case 2, 3 -> "std::hypot(" + String.join(", ", parts) + ")";
            default -> {
                final List<String> squares = new ArrayList<>();
                for (String c : parts) {
                    squares.add(c + " * " + c);
                }
                yield "std::sqrt(" + String.join(" + ", squares) + ")";
            }
        };
    }

    /** The error budget at typical values with 5% errors: the value, its error, and who gives it. */
    private static String budget(RootFormula.Parsed p, Map<String, RootFormula.Node> derivatives) {
        final Map<String, Double> at = RootFormula.typical(p.inputs());
        try {
            final Map<String, Double> errors = new LinkedHashMap<>();
            final Map<String, Double> squares = new LinkedHashMap<>();
            double total = 0;
            for (String x : p.inputs()) {
                final double v = at.get(x);
                final double e = v == 0 ? 0.05 : Math.abs(v) * 0.05;
                errors.put(x, e);
                final double c = RootFormula.eval(derivatives.get(x), at) * e;
                squares.put(x, c * c);
                total += c * c;
            }
            if (!Double.isFinite(total)) {
                return "At the typical values its uncertainty is not a number: read the notes on the domain.";
            }
            final double value = RootFormula.eval(p.root(), at);
            final List<String> point = new ArrayList<>();
            for (String x : p.inputs()) {
                if (point.size() == 5) {
                    point.add("...");
                    break;
                }
                point.add(x + "=" + RootFormula.shortNumber(at.get(x)) + "+-"
                          + RootFormula.shortNumber(errors.get(x)));
            }
            final StringBuilder s = new StringBuilder("With 5% errors, at ")
                .append(String.join(", ", point)).append(": ")
                .append(RootFormula.shortNumber(value)).append(" +- ")
                .append(RootFormula.shortNumber(Math.sqrt(total)));
            if (total > 0 && p.inputs().size() > 1) {
                final List<String> shares = new ArrayList<>();
                for (Map.Entry<String, Double> e : squares.entrySet()) {
                    shares.add(e.getKey() + " " + Math.round(100 * e.getValue() / total) + "%");
                }
                s.append(", the variance from ").append(String.join(", ", shares));
            }
            return s.append('.').toString();
        } catch (RootFormula.NotEvaluable e) {
            return "Not previewed: " + e.getMessage() + " is computed by ROOT only.";
        }
    }

    private static void foreignNote(RootFormula.Node root, List<String> notes) {
        final String foreign = RootFormula.foreignCall(root);
        if (foreign != null && !foreign.startsWith("TMath::")) {
            notes.add(foreign + " is handed to C++ as written: if the build cannot find it, "
                + "add its header in the editor.");
        }
    }

    private static void definitions(StringBuilder body, RootFormula.Shared shared) {
        for (RootFormula.Def def : shared.defs()) {
            body.append("  const ").append(def.condition() ? "bool " : "double ").append(def.name())
                .append(" = ").append(RootFormula.cpp(def.value())).append(";\n");
        }
    }

    private static String algorithmFor(List<RootFormula.Node> nodes) {
        for (RootFormula.Node one : nodes) {
            if (RootFormula.usesMinMax(one)) {
                return "#include <algorithm>";
            }
        }
        return null;
    }

    /** The inputs of a formula as the form shows them, for a dialog that wants them as typed. */
    public static List<String> formulaInputs(String formula) {
        return RootFormula.inputsOf(formula == null ? "" : formula.trim());
    }

    /* ================================================================== */
    /* Start from a pipeline already written                               */
    /* ================================================================== */

    public static Start fromPipeline(Path source) {
        return fromPipeline(source, null);
    }

    /**
     * A copy of a pipeline on disk, under the next free version of its name.
     *
     * What the copy keeps: the body; whatever the user wrote around the entry
     * point; the build options; the layer it lives in. Where the body names
     * itself -- a recursive call, a histogram called "name_h", a comment --
     * the new name is written, so the copy never fills the original's
     * histogram or calls the original's code.
     */
    public static Start fromPipeline(Path source, String activeProject) {
        final String text = read(source);
        if (text == null) {
            return Start.fail("Cannot read " + source + ".");
        }
        final RootPipelineManifest m = RootPipelineManifest.fromText(text);
        if (m == null) {
            return Start.fail(source.getFileName() + " does not declare itself as a pipeline.");
        }
        if (m.kind == Kind.LIBRARY) {
            return Start.fail(m.name + " is a plain library with no entry point: copy the file itself instead.");
        }
        final int mark = text.indexOf(RootPipelineTemplates.BODY_MARK);
        final int from = mark < 0 ? 0 : text.indexOf('\n', mark) + 1;
        int to = text.indexOf(RootPipelineTemplates.TAIL_MARK, Math.max(from, 0));
        if (to < 0) {
            to = text.length();
        }
        final String half = text.substring(Math.max(from, 0), to);
        // As the source writes it: a monitored pipeline's under its inner name.
        final String opening = m.writtenSignature() + " {";
        final int at = half.indexOf(opening);
        if (at < 0) {
            return Start.fail("The declaration of " + m.name
                + " was rewritten by hand, so its body cannot be found.");
        }
        final int open = at + opening.length() - 1;
        final int close = matching(blanked(half), open);
        if (close < 0) {
            return Start.fail("The body of " + m.name + " never closes.");
        }
        String inner = half.substring(open + 1, close);
        if (inner.startsWith("\n")) {
            inner = inner.substring(1);
        }
        inner = inner.stripTrailing() + "\n";
        final String around = (half.substring(0, at).strip() + "\n\n" + half.substring(close + 1).strip()).strip();

        final RootPipelineManifest options = new RootPipelineManifest();
        options.takeBuildOptions(m);
        options.description = m.description;
        options.global = under(source, RootUserPipeline.globalRoot());

        final List<String> notes = new ArrayList<>();
        notes.add("Copied from " + source.getFileName() + "; the original stays as it is.");
        if (!around.isBlank()) {
            notes.add("The code around its entry point comes along.");
        }
        notes.add("Its build options come along: " + String.join(" ", m.flags())
            + (m.threadSafe ? ", stateless" : ", keeps state") + ".");
        final Recipe recipe = new Recipe(Family.COPY, "Copy of " + m.name,
            m.description == null || m.description.isBlank() ? "A copy of " + m.name : m.description,
            m.kind, List.copyOf(m.inputs), List.copyOf(m.modules), renameSelf(inner, m.name),
            around.isBlank() ? null : renameSelf(around, m.name));
        return new Start(recipe, nextFreeName(m.name, activeProject), notes, null, options);
    }

    /** deltaR -> deltaR_v2, deltaR_v2 -> deltaR_v3, skipping any version that exists already. */
    public static String nextFreeName(String name, String activeProject) {
        final Matcher v = Pattern.compile("^(.*?)_v(\\d{1,6})$").matcher(name);
        final String stem = v.matches() ? v.group(1) : name;
        int n = v.matches() ? Integer.parseInt(v.group(2)) + 1 : 2;
        String candidate = stem + "_v" + n;
        while (pipelineNamed(candidate, activeProject) != null && n < 10_000) {
            candidate = stem + "_v" + (++n);
        }
        return candidate.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static boolean under(Path file, Path root) {
        try {
            return root != null && file.toAbsolutePath().normalize()
                                       .startsWith(root.toAbsolutePath().normalize());
        } catch (RuntimeException unknowable) {
            return true;
        }
    }

    /* ================================================================== */
    /* Start from a function in a macro                                    */
    /* ================================================================== */

    /** What a piece of a macro at file scope is. */
    public enum UnitKind { INCLUDE, USING, DEFINE, TYPE, VARIABLE, FUNCTION }

    /** One piece of a macro at file scope: where it is written, and in which namespace. */
    public record Unit(UnitKind kind, String name, int start, int end, String space, boolean mutable) { }

    /**
     * One function a macro defines.
     *
     * @param internal not reached by a plain C++ declaration written elsewhere:
     *                 static, inline, constexpr, in an anonymous namespace, or
     *                 extern "C", whose symbol carries no C++ signature
     */
    public record Function(String returnType, String name, String parameters, String body,
                           int start, int end, String space, boolean template, boolean internal) {
        @Override
        public String toString() {
            return returnType + " " + name + "(" + parameters + ")";
        }
    }

    /** A macro read once: its text, and what it defines at file scope. */
    public record Macro(Path file, String text, List<Unit> units, List<Function> functions) {
        public String origin() {
            return file == null || file.getFileName() == null ? "the macro" : file.getFileName().toString();
        }
    }

    private static final Pattern INCLUDE = Pattern.compile("^\\s*#\\s*include\\s*([<\"][^>\"]+[>\"])");
    private static final Pattern DEFINE = Pattern.compile("^\\s*#\\s*define\\s+([A-Za-z_]\\w*)");
    private static final Pattern NAMESPACE = Pattern.compile("(?s)(?:inline\\s+)?namespace(?:\\s+([A-Za-z_][\\w:]*))?");
    private static final Pattern TYPE_HEAD = Pattern.compile("\\b(class|struct|union|enum)\\b");
    // The return type may take several words (unsigned int, long long, long
    // double): without them such a function was not seen at all.
    private static final Pattern HEADER = Pattern.compile(
        "(?s)(template\\s*<.*?>\\s*)?((?:(?:inline|static|constexpr|extern)\\s+)*)"
        + "((?:const\\s+)?(?:(?:unsigned|signed|short|long)\\s+)*[A-Za-z_][\\w:]*"
        + "(?:\\s*<[^(){};]*?>)?(?:\\s*[*&]+)?)(?:\\s+|(?<=[*&]))"
        + "([A-Za-z_][\\w:]*)\\s*\\(([^(){}]*)\\)\\s*(?:const\\s*)?(?:noexcept\\s*)?(?:override\\s*)?"
        + "(?:->\\s*[^{;]+?)?\\s*");
    private static final Pattern USING_NAMESPACE = Pattern.compile("(?s)using\\s+namespace\\s+[\\w:]+\\s*;");
    private static final Pattern USING_DECLARATION = Pattern.compile("(?s)using\\s+[\\w:]+\\s*;");
    private static final Pattern USING_ALIAS = Pattern.compile("(?s)using\\s+([A-Za-z_]\\w*)\\s*=.*;");
    private static final Pattern FORWARD = Pattern.compile("(?s)(?:class|struct|union|enum(?:\\s+class)?)\\s+[\\w:]+\\s*;");
    private static final Pattern CONSTRUCTOR_ARGUMENTS = Pattern.compile("[\\s\\d.eE+\\-*/,]*");
    private static final Pattern CONSTANT = Pattern.compile("\\b(?:const|constexpr)\\b");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
    private static final Pattern STATIC_LOCAL = Pattern.compile("\\bstatic\\b(?!\\s+(?:const|constexpr)\\b)");
    private static final Pattern RANDOM = Pattern.compile(
        "\\b(?:gRandom|TRandom\\d?|rand|srand|mt19937(?:_64)?|default_random_engine|minstd_rand0?|ranlux\\w*)\\b");
    private static final Pattern ROOT_GLOBALS = Pattern.compile("\\b(?:gROOT|gDirectory|gPad|gStyle|gFile|gSystem)\\b");
    // extern before a definition is extern "C", its string blanked out
    private static final Pattern INTERNAL = Pattern.compile("\\b(?:static|inline|constexpr|extern)\\b");

    /** Stand for an anonymous namespace and an extern "C" block among those a scan is inside. */
    private static final String ANONYMOUS = "\u0000";
    private static final String EXTERN_C = "\u0001";

    public static Macro readMacro(Path file) {
        final String text = read(file);
        return text == null ? null : macroOf(text, file);
    }

    /**
     * What a macro defines at file scope.
     *
     * A scan of its braces, with the comments, strings and preprocessor
     * lines taken out first: a namespace is entered, a function or a type is
     * read whole and skipped, a statement ending in ; is a variable, an alias
     * or a prototype. Methods inside a class are never mistaken for functions
     * of their own, because the class's body is skipped as one piece.
     */
    public static Macro macroOf(String text, Path file) {
        final List<Unit> units = new ArrayList<>();
        final List<Function> functions = new ArrayList<>();
        final char[] chars = blanked(text).toCharArray();
        final int n = chars.length;

        int at = 0;
        while (at < n) {
            final int end = lineEnd(text, at);
            int first = at;
            while (first < end && (chars[first] == ' ' || chars[first] == '\t')) {
                first++;
            }
            if (first < end && chars[first] == '#') {
                int last = end;
                while (last < n && text.substring(at, last).stripTrailing().endsWith("\\")) {
                    last = lineEnd(text, last + 1);
                }
                final String directive = text.substring(at, last);
                final Matcher inc = INCLUDE.matcher(directive);
                final Matcher def = DEFINE.matcher(directive);
                if (inc.find()) {
                    units.add(new Unit(UnitKind.INCLUDE, inc.group(1), at, last, "", false));
                } else if (def.find()) {
                    units.add(new Unit(UnitKind.DEFINE, def.group(1), at, last, "", false));
                }
                for (int i = at; i < last; i++) {
                    if (chars[i] != '\n') {
                        chars[i] = ' ';
                    }
                }
                at = last + 1;
            } else {
                at = end + 1;
            }
        }
        final String code = new String(chars);

        final Deque<String> spaces = new ArrayDeque<>();
        int stmt = 0;
        int parens = 0;
        for (int i = 0; i < n; i++) {
            final char ch = code.charAt(i);
            if (ch == '(') {
                parens++;
                continue;
            }
            if (ch == ')') {
                if (parens > 0) {
                    parens--;
                }
                continue;
            }
            if (ch == '{') {
                final int begin = skipSpace(code, stmt, i);
                final String header = code.substring(begin, i).trim();
                final String space = spaceOf(spaces);
                final Matcher ns = NAMESPACE.matcher(header);
                if (ns.matches()) {
                    spaces.push(ns.group(1) == null ? ANONYMOUS : ns.group(1));
                    stmt = i + 1;
                    parens = 0;
                    continue;
                }
                if (header.equals("extern")) {
                    spaces.push(EXTERN_C);
                    stmt = i + 1;
                    parens = 0;
                    continue;
                }
                final int close = matching(code, i);
                if (close < 0) {
                    break;
                }
                if (TYPE_HEAD.matcher(header).find() && header.indexOf('(') < 0 && header.indexOf('=') < 0) {
                    int end = close + 1;
                    final int semi = code.indexOf(';', end);
                    final int nextBrace = code.indexOf('{', end);
                    if (semi >= 0 && (nextBrace < 0 || semi < nextBrace)
                            && code.substring(end, semi).matches("[\\s\\w*&,]*")) {
                        end = semi + 1;
                    }
                    final String name = typeName(header, code.substring(close + 1, end));
                    if (name != null) {
                        units.add(new Unit(UnitKind.TYPE, name, begin, end, space, false));
                    }
                    i = end - 1;
                    stmt = end;
                    parens = 0;
                    continue;
                }
                if (header.contains(")")) {
                    final Matcher h = HEADER.matcher(header);
                    if (h.matches() && !h.group(4).contains("::")) {
                        final boolean internal = spaces.contains(ANONYMOUS) || spaces.contains(EXTERN_C)
                            || INTERNAL.matcher(h.group(2)).find();
                        final Function f = new Function(h.group(3).trim(), h.group(4), h.group(5).trim(),
                            text.substring(i + 1, close), begin, close + 1, space, h.group(1) != null,
                            internal);
                        functions.add(f);
                        units.add(new Unit(UnitKind.FUNCTION, f.name(), begin, close + 1, space, false));
                    }
                    i = close;
                    stmt = close + 1;
                    parens = 0;
                    continue;
                }
                // A brace initializer: the statement goes on to its ;
                i = close;
                continue;
            }
            if (ch == '}') {
                if (!spaces.isEmpty()) {
                    spaces.pop();
                }
                stmt = i + 1;
                parens = 0;
                continue;
            }
            if (ch == ';' && parens == 0) {
                statement(code, stmt, i + 1, spaceOf(spaces), units);
                stmt = i + 1;
            }
        }
        return new Macro(file, text, List.copyOf(units), List.copyOf(functions));
    }

    private static String spaceOf(Deque<String> spaces) {
        final List<String> names = new ArrayList<>();
        final var it = spaces.descendingIterator();
        while (it.hasNext()) {
            final String one = it.next();
            if (!one.isEmpty() && !one.equals(ANONYMOUS) && !one.equals(EXTERN_C)) {
                names.add(one);
            }
        }
        return String.join("::", names);
    }

    private static String typeName(String header, String after) {
        if (header.startsWith("typedef")) {
            return lastIdentifier(after);
        }
        final Matcher m = Pattern.compile("\\b(?:class|struct|union|enum(?:\\s+class)?)\\s+([A-Za-z_]\\w*)")
                                 .matcher(header);
        return m.find() ? m.group(1) : null;
    }

    private static void statement(String code, int from, int to, String space, List<Unit> units) {
        final int begin = skipSpace(code, from, to);
        if (begin >= to) {
            return;
        }
        final String s = code.substring(begin, to).trim();
        if (s.equals(";")) {
            return;
        }
        if (USING_NAMESPACE.matcher(s).matches() || USING_DECLARATION.matcher(s).matches()) {
            units.add(new Unit(UnitKind.USING, null, begin, to, space, false));
            return;
        }
        final Matcher alias = USING_ALIAS.matcher(s);
        if (alias.matches()) {
            units.add(new Unit(UnitKind.TYPE, alias.group(1), begin, to, space, false));
            return;
        }
        if (s.startsWith("typedef")) {
            final Matcher pointer = Pattern.compile("\\(\\s*\\*\\s*([A-Za-z_]\\w*)\\s*\\)").matcher(s);
            final String name = pointer.find() ? pointer.group(1) : lastIdentifier(s.substring(0, s.length() - 1));
            if (name != null) {
                units.add(new Unit(UnitKind.TYPE, name, begin, to, space, false));
            }
            return;
        }
        if (FORWARD.matcher(s).matches() || s.startsWith("template") || s.startsWith("friend")) {
            return;
        }
        final String declaration = s.substring(0, s.length() - 1);
        final int eq = topLevel(declaration, '=');
        final int paren = declaration.indexOf('(');
        if (paren >= 0 && (eq < 0 || paren < eq)) {
            final int close = declaration.indexOf(')', paren);
            final String inside = close > paren ? declaration.substring(paren + 1, close) : "";
            // double f(double x); is a prototype; TRandom3 rng(42); is a variable.
            if (inside.isBlank() || !CONSTRUCTOR_ARGUMENTS.matcher(inside).matches()) {
                return;
            }
        }
        final String head = eq < 0 ? declaration : declaration.substring(0, eq);
        final boolean constant = CONSTANT.matcher(head).find()
            || (eq >= 0 && declaration.substring(eq + 1).trim().startsWith("["));
        for (String name : declaredNames(declaration)) {
            units.add(new Unit(UnitKind.VARIABLE, name, begin, to, space, !constant));
        }
    }

    private static List<String> declaredNames(String declaration) {
        final List<String> out = new ArrayList<>();
        for (String part : splitTopLevel(declaration, ',')) {
            int cut = -1;
            int depth = 0;
            for (int k = 0; k < part.length(); k++) {
                final char ch = part.charAt(k);
                if (ch == '<') {
                    depth++;
                } else if (ch == '>') {
                    depth--;
                } else if (depth == 0 && "=({[".indexOf(ch) >= 0) {
                    cut = k;
                    break;
                }
            }
            final String name = lastIdentifier(cut >= 0 ? part.substring(0, cut) : part);
            if (name != null && !Set.of("const", "constexpr", "static", "inline", "extern").contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * What a function becomes, and why when it cannot.
     *
     * The function is taken with everything it needs from its macro, found by
     * following the names it uses: the helpers it calls, the constants and
     * globals it reads, the types it builds, the #defines it expands -- and
     * what those need in turn. They go above the entry point, inside an
     * anonymous namespace, so two pipelines brought from the same macro can
     * be loaded together without their helpers colliding.
     */
    public static Start fromFunction(Macro macro, Function f, String activeProject) {
        if (f.template()) {
            return Start.fail(f.name() + " is a template: a pipeline has one signature.");
        }
        final String ret = f.returnType().replace(" ", "");
        final Kind kind;
        if (ret.equals("bool") || ret.equals("Bool_t")) {
            kind = Kind.FILTER;
        } else if (ret.equals("TH1D*")) {
            kind = Kind.HISTOGRAM;
        } else if (ret.matches("TH1[FI]?\\*")) {
            return Start.fail(f.name() + " returns " + f.returnType() + "; a histogram pipeline hands back a TH1D *.");
        } else if (ret.equals("void")) {
            return Start.fail(f.name() + " returns nothing, so it has no value to give an entry.");
        } else if (formType(f.returnType()) != null && !formType(f.returnType()).contains("RVec")
                   && !formType(f.returnType()).contains("char")) {
            kind = Kind.TRANSFORM;
        } else {
            return Start.fail(f.name() + " returns " + f.returnType() + ", which RDataFrame cannot take from a pipeline.");
        }

        final List<Input> inputs = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<String> params = splitTopLevel(f.parameters(), ',');
        String body = f.body();
        for (int k = 0; k < params.size(); k++) {
            final String p = params.get(k).replaceAll("=.*$", "").trim();
            if (p.isEmpty() || p.equals("void")) {
                continue;
            }
            final Matcher pm = Pattern.compile("^(.*?)([A-Za-z_]\\w*)\\s*$").matcher(p);
            if (!pm.find() || pm.group(1).isBlank()) {
                return Start.fail("Parameter " + (k + 1) + " of " + f.name() + " has no name: " + p + ".");
            }
            final String rawType = pm.group(1).trim();
            final String name = pm.group(2);
            if (kind == Kind.HISTOGRAM && k == 0) {
                if (!rawType.replace(" ", "").equals("TTree*")) {
                    return Start.fail(f.name() + " returns a histogram, so it must take the tree first.");
                }
                if (!name.equals("tree")) {
                    body = rename(body, name, "tree", false);
                    notes.add("The tree " + name + " is called tree in the pipeline.");
                }
                continue;
            }
            final String type = formType(rawType);
            if (type == null) {
                return Start.fail("Parameter " + name + " of " + f.name() + " is a " + rawType
                    + ", which the form has no type for.");
            }
            inputs.add(new Input(name, type));
        }
        if (inputs.isEmpty() && kind != Kind.HISTOGRAM) {
            return Start.fail(f.name() + " takes no argument: a pipeline is given its inputs entry by entry.");
        }
        // A macro trusts its tree; a pipeline is also called with none (its
        // test macro does), and a crash there takes the engine down with it.
        if (kind == Kind.HISTOGRAM && !TREE_CHECKED.matcher(blanked(body)).find()) {
            body = guardTree(body);
            notes.add("A missing tree gives nullptr instead of crashing the engine.");
        }

        final String code = blanked(macro.text());
        final Set<Unit> needed = needed(macro, code, f);
        final Set<RootPipelineModules> modules = new LinkedHashSet<>(List.of(RootPipelineModules.MATH));
        final String prelude = prelude(macro, needed, modules, notes);
        final List<String> state = state(code, macro, f, needed);

        modules.addAll(modulesUsedBy(f.body() + "\n" + (prelude == null ? "" : prelude)));
        if (kind == Kind.HISTOGRAM) {
            modules.add(RootPipelineModules.TREE);
            modules.add(RootPipelineModules.HIST);
        }
        for (Input one : inputs) {
            if (one.type().contains("RVec")) {
                modules.add(RootPipelineModules.VECTORS);
            }
        }

        final RootPipelineManifest options = new RootPipelineManifest();
        options.threadSafe = state.isEmpty();
        if (!state.isEmpty()) {
            notes.add("It keeps state (" + String.join(", ", state) + "): not declared stateless, "
                + "so :root mt on will not run it on several entries at once.");
        }
        notes.add(0, "Read " + f.name() + " from " + macro.origin() + ": " + namesWithTypes(inputs) + ".");

        final String suggested = activeProject == null || pipelineNamed(f.name(), activeProject) == null
            ? f.name() : nextFreeName(f.name(), activeProject);
        final Recipe recipe = new Recipe(Family.MACRO, f.name() + " from " + macro.origin(),
            safe("From " + f.name() + " in " + macro.origin()), kind, List.copyOf(inputs),
            List.copyOf(modules), reindent(renameSelf(body, f.name())),
            prelude == null ? null : renameSelf(prelude, f.name()));
        return new Start(recipe, suggested, notes, null, options);
    }

    /**
     * One line about a function, for a list to choose from. It opens on the
     * function's parameters, so two overloads cannot read alike.
     */
    public static String verdict(Macro macro, Function f) {
        final Start s = fromFunction(macro, f, null);
        if (!s.ok()) {
            return head(f) + "   -   cannot: " + firstLine(s.error());
        }
        final int count = s.recipe().inputs().size();
        return head(f) + "   -   " + s.recipe().kind().token + ", " + count
            + (count == 1 ? " input" : " inputs")
            + (s.options() != null && !s.options().threadSafe ? ", keeps state" : "")
            + (s.notes().stream().anyMatch(n -> n.startsWith(BROUGHT)) ? ", brings helpers" : "");
    }

    // tree == nullptr, nullptr != tree, !tree, if (tree) -- not reader(tree) nor !tree->...
    private static final Pattern TREE_CHECKED = Pattern.compile(
        "\\btree\\s*[!=]=\\s*(?:nullptr|NULL|0)\\b|\\b(?:nullptr|NULL)\\s*[!=]=\\s*tree\\b"
        + "|!\\s*tree\\b(?!\\s*->)|\\bif\\s*\\(\\s*tree\\s*\\)");

    /** The body, opening on a check that the tree is there, at the body's own indentation. */
    private static String guardTree(String body) {
        String indent = "    ";
        for (String line : body.split("\n")) {
            if (!line.isBlank()) {
                indent = line.substring(0, line.length() - line.stripLeading().length());
                break;
            }
        }
        // Two spaces in, as reindent writes every body.
        return "\n" + indent + "if (tree == nullptr) {\n" + indent + "  return nullptr;\n" + indent + "}"
             + (body.startsWith("\n") ? "" : "\n") + body;
    }

    /** name(type a, type b), default values left out, as a list shows a function. */
    private static String head(Function f) {
        final List<String> parameters = new ArrayList<>();
        for (String one : splitTopLevel(f.parameters(), ',')) {
            final String p = one.replaceAll("(?s)=.*$", "").replaceAll("\\s+", " ").trim();
            if (!p.isEmpty() && !p.equals("void")) {
                parameters.add(p);
            }
        }
        return f.name() + "(" + String.join(", ", parameters) + ")";
    }

    /**
     * A function in a macro file. With no function named, the first one that
     * can be a pipeline is taken, so a one-function macro needs no second word.
     */
    public static Start fromMacro(Path file, String function) {
        return fromMacro(file, function, null);
    }

    public static Start fromMacro(Path file, String function, String activeProject) {
        final Macro macro = readMacro(file);
        if (macro == null) {
            return Start.fail("Cannot read " + file + ".");
        }
        if (macro.functions().isEmpty()) {
            return Start.fail(macro.origin() + " defines no function at file scope.");
        }
        if (function != null && !function.isBlank()) {
            Start first = null;
            for (Function one : macro.functions()) {
                if (one.name().equals(function.trim())) {
                    final Start s = fromFunction(macro, one, activeProject);
                    if (s.ok()) {
                        return s;
                    }
                    if (first == null) {
                        first = s;
                    }
                }
            }
            if (first != null) {
                return first;
            }
            return Start.fail(macro.origin() + " has no function " + function + ". It has: "
                + String.join(", ", macro.functions().stream().map(Function::name).toList()) + ".");
        }
        String firstReason = null;
        for (Function one : macro.functions()) {
            final Start s = fromFunction(macro, one, activeProject);
            if (s.ok()) {
                if (macro.functions().size() > 1) {
                    final List<String> notes = new ArrayList<>(s.notes());
                    notes.add(macro.origin() + " has " + macro.functions().size()
                        + " functions; name another with " + macro.origin() + ":<function>.");
                    return new Start(s.recipe(), s.suggestedName(), notes, null, s.options());
                }
                return s;
            }
            if (firstReason == null) {
                firstReason = s.error();
            }
        }
        return Start.fail(firstReason);
    }

    /** Every unit the function reaches through the names it uses, and what those reach in turn. */
    private static Set<Unit> needed(Macro macro, String code, Function chosen) {
        final Map<String, List<Unit>> byName = new HashMap<>();
        for (Unit u : macro.units()) {
            if (u.name() != null && u.kind() != UnitKind.INCLUDE && u.kind() != UnitKind.USING) {
                byName.computeIfAbsent(u.name(), k -> new ArrayList<>()).add(u);
            }
        }
        final Set<Unit> needed = new LinkedHashSet<>();
        final Deque<String> todo = new ArrayDeque<>(identifiers(code, chosen.start(), chosen.end()));
        // A parameter hides a global of the same name: x in f(double x) is not ::x.
        final Set<String> seen = new HashSet<>();
        for (String parameter : splitTopLevel(chosen.parameters(), ',')) {
            final String name = lastIdentifier(parameter.replaceAll("=.*$", ""));
            if (name != null) {
                seen.add(name);
            }
        }
        while (!todo.isEmpty()) {
            final String id = todo.pop();
            if (!seen.add(id) || id.equals(chosen.name())) {
                continue;
            }
            for (Unit u : byName.getOrDefault(id, List.of())) {
                if (needed.add(u)) {
                    todo.addAll(identifiers(code, u.start(), u.end()));
                }
            }
        }
        return needed;
    }

    /** The names used between two offsets, member accesses (p.x, p->x) left out. */
    private static Set<String> identifiers(String code, int from, int to) {
        final Set<String> out = new LinkedHashSet<>();
        final Matcher m = IDENTIFIER.matcher(code);
        m.region(Math.max(0, from), Math.min(code.length(), to));
        while (m.find()) {
            final int s = m.start();
            final boolean member = (s > 0 && code.charAt(s - 1) == '.')
                || (s > 1 && code.charAt(s - 1) == '>' && code.charAt(s - 2) == '-');
            if (!member && !(s > 0 && Character.isDigit(code.charAt(s - 1)))) {
                out.add(m.group());
            }
        }
        return out;
    }

    /** The code above the entry point: includes, using-directives, #defines, then the helpers. */
    private static String prelude(Macro macro, Set<Unit> needed, Set<RootPipelineModules> modules,
                                  List<String> notes) {
        final String text = macro.text();
        final StringBuilder pre = new StringBuilder();
        for (Unit u : macro.units()) {
            if (u.kind() != UnitKind.INCLUDE) {
                continue;
            }
            final String spec = u.name();
            final RootPipelineModules module = moduleForHeader(spec);
            if (module != null) {
                modules.add(module);
                if (RootPipelineModules.headersOf(List.of(module)).contains(bare(spec))) {
                    continue;
                }
            }
            if (Set.of("<string>", "<cmath>", "<chrono>").contains(spec)) {
                continue;
            }
            String line = text.substring(u.start(), u.end()).strip();
            if (spec.startsWith("\"") && macro.file() != null && macro.file().getParent() != null) {
                final Path beside = macro.file().getParent().resolve(bare(spec));
                if (Files.isRegularFile(beside)) {
                    final String absolute = beside.toAbsolutePath().normalize().toString().replace('\\', '/');
                    line = "#include \"" + absolute + "\"";
                    notes.add("#include " + spec + " now names " + absolute + ": the pipeline is built in includes/.");
                } else {
                    notes.add(spec + " is not beside the macro: the build will look for it in includes/.");
                }
            }
            pre.append(line).append('\n');
        }
        for (Unit u : macro.units()) {
            if (u.kind() == UnitKind.USING && u.space().isEmpty()) {
                pre.append(text.substring(u.start(), u.end()).strip()).append('\n');
            }
        }
        final List<Unit> pieces = new ArrayList<>();
        final List<String> brought = new ArrayList<>();
        final Set<Integer> spans = new HashSet<>();
        for (Unit u : macro.units()) {
            if (!needed.contains(u) || !spans.add(u.start())) {
                continue;
            }
            if (u.kind() == UnitKind.DEFINE) {
                pre.append(text.substring(u.start(), u.end()).strip()).append('\n');
                brought.add(u.name());
            } else if (u.kind() == UnitKind.TYPE || u.kind() == UnitKind.VARIABLE || u.kind() == UnitKind.FUNCTION) {
                pieces.add(u);
            }
        }
        if (!pieces.isEmpty()) {
            if (pre.length() > 0) {
                pre.append('\n');
            }
            pre.append("// Brought from ").append(macro.origin())
               .append(", in a namespace of its own so that nothing collides.\n");
            pre.append("namespace {\n\n");
            String open = "";
            for (Unit u : pieces) {
                if (!u.space().equals(open)) {
                    if (!open.isEmpty()) {
                        pre.append("}  // namespace ").append(open).append("\n\n");
                    }
                    if (!u.space().isEmpty()) {
                        pre.append("namespace ").append(u.space()).append(" {\n\n");
                    }
                    open = u.space();
                }
                pre.append(text.substring(u.start(), u.end()).strip()).append("\n\n");
                brought.add(u.kind() == UnitKind.FUNCTION ? u.name() + "()"
                    : u.kind() == UnitKind.TYPE ? "type " + u.name() : u.name());
            }
            if (!open.isEmpty()) {
                pre.append("}  // namespace ").append(open).append("\n\n");
            }
            pre.append("}  // namespace\n");
        }
        if (!brought.isEmpty()) {
            notes.add(BROUGHT + macro.origin() + ": "
                + String.join(", ", new LinkedHashSet<>(brought)) + ".");
        }
        final String out = pre.toString().strip();
        return out.isEmpty() ? null : out;
    }

    /** Why a function cannot be declared stateless, or nothing when it can. */
    private static List<String> state(String code, Macro macro, Function chosen, Set<Unit> needed) {
        final Set<String> why = new LinkedHashSet<>();
        final List<int[]> bodies = new ArrayList<>();
        final List<String> owners = new ArrayList<>();
        bodies.add(new int[]{chosen.start(), chosen.end()});
        owners.add(chosen.name());
        for (Unit u : needed) {
            if (u.kind() == UnitKind.FUNCTION) {
                bodies.add(new int[]{u.start(), u.end()});
                owners.add(u.name());
            } else if (u.kind() == UnitKind.VARIABLE && u.mutable()) {
                why.add("the global " + u.name());
            }
        }
        for (int k = 0; k < bodies.size(); k++) {
            final int open = code.indexOf('{', bodies.get(k)[0]);
            if (open < 0 || open >= bodies.get(k)[1]) {
                continue;
            }
            final String part = code.substring(open, bodies.get(k)[1]);
            if (STATIC_LOCAL.matcher(part).find()) {
                why.add("a static variable in " + owners.get(k));
            }
            if (RANDOM.matcher(part).find()) {
                why.add("a random generator");
            }
            if (ROOT_GLOBALS.matcher(part).find()) {
                why.add("ROOT's global state");
            }
        }
        return new ArrayList<>(why);
    }

    /** The part of ROOT a header belongs to, or null for one Sphere does not group. */
    static RootPipelineModules moduleForHeader(String spec) {
        final String h = bare(spec);
        if (h.equals("TMath.h")) {
            return RootPipelineModules.MATH;
        }
        if (h.startsWith("ROOT/RVec")) {
            return RootPipelineModules.VECTORS;
        }
        if (h.startsWith("Math/Vector") || h.startsWith("Math/GenVector") || h.startsWith("Math/LorentzVector")
                || h.startsWith("Math/Point") || h.startsWith("Math/PtEtaPhi")) {
            return RootPipelineModules.LORENTZ;
        }
        if (h.matches("T(Tree|TreeReader|TreeReaderValue|TreeReaderArray|Chain|Branch)\\.h")) {
            return RootPipelineModules.TREE;
        }
        if (h.matches("T(H1|H1D|H1F|H2|H2D|H2F|H3|Profile)\\.h")) {
            return RootPipelineModules.HIST;
        }
        if (h.startsWith("ROOT/RDataFrame")) {
            return RootPipelineModules.FRAME;
        }
        if (h.matches("TF[123]\\.h") || h.startsWith("Fit/") || h.equals("TFitResult.h")) {
            return RootPipelineModules.FIT;
        }
        if (h.startsWith("Roo")) {
            return RootPipelineModules.ROOFIT;
        }
        if (h.startsWith("TMVA/")) {
            return RootPipelineModules.TMVA;
        }
        if (h.matches("TRandom\\d?\\.h")) {
            return RootPipelineModules.RANDOM;
        }
        if (h.matches("T(File|Directory|Key)\\.h")) {
            return RootPipelineModules.FILES;
        }
        return null;
    }

    private static String bare(String spec) {
        return spec.replaceAll("[<>\"]", "").trim();
    }

    /** The C++ spellings of a type, reduced to the ones the form offers. */
    static String formType(String type) {
        String t = type.replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^const ", "").replaceAll("\\s*&$", "").trim();
        t = t.replace("ROOT::VecOps::RVec", "ROOT::RVec").replace("RVec<", "ROOT::RVec<")
             .replace("ROOT::ROOT::RVec", "ROOT::RVec").replace(" ", "");
        return switch (t) {
            case "double", "Double_t", "Double32_t" -> "double";
            case "float", "Float_t" -> "float";
            case "int", "Int_t", "short", "Short_t" -> "int";
            case "long", "Long_t", "Long64_t", "longlong" -> "long";
            case "unsigned", "unsignedint", "UInt_t" -> "unsigned int";
            case "bool", "Bool_t" -> "bool";
            case "char*" -> "const char *";
            case "ROOT::RVec<double>", "RVecD", "ROOT::RVecD" -> "ROOT::RVec<double>";
            case "ROOT::RVec<float>", "RVecF", "ROOT::RVecF" -> "ROOT::RVec<float>";
            case "ROOT::RVec<int>", "RVecI", "ROOT::RVecI" -> "ROOT::RVec<int>";
            default -> null;
        };
    }

    /* ================================================================== */
    /* What a plain library lets the prompt call                           */
    /* ================================================================== */

    /** Words a declaration can name without the library's own types. */
    private static final Set<String> KNOWN_TYPE_WORDS = Set.of(
        "void", "bool", "char", "short", "int", "long", "float", "double", "signed", "unsigned",
        "const", "volatile", "size_t", "std", "string", "string_view", "vector", "array", "pair",
        "ROOT", "VecOps", "RVec", "RVecD", "RVecF", "RVecI", "RVecB", "Math",
        "Double_t", "Double32_t", "Float_t", "Int_t", "UInt_t", "Long_t", "ULong_t", "Long64_t",
        "ULong64_t", "Short_t", "UShort_t", "Bool_t", "Char_t", "UChar_t", "Size_t",
        "PtEtaPhiMVector", "PtEtaPhiEVector", "PxPyPzEVector", "PxPyPzMVector",
        "XYZVector", "XYZTVector", "XYZPoint");

    /** A ROOT class by its naming rule: TH1D, TTree, TLorentzVector... */
    private static final Pattern ROOT_CLASS = Pattern.compile("T[A-Z]\\w*");

    /**
     * The declarations that make a plain library's functions callable by name.
     *
     * A library pipeline has no entry point for its manifest to describe: what
     * it offers is whatever its source defines. Each function the rest of the
     * process can reach -- not static, not inline, not in an anonymous
     * namespace, not a template -- is declared exactly as it is written, its
     * namespace, parameters and default values included, so the declaration
     * names the very symbol the compiler emitted. A function that takes or
     * returns one of the library's own types is left out: its declaration
     * would need that type too, and the interpreter would refuse it.
     */
    public static List<String> callableDeclarations(String source) {
        final List<String> out = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return out;
        }
        // Only the user's half: the header and the tail are Sphere's own.
        String text = source;
        final int body = text.indexOf(RootPipelineTemplates.BODY_MARK);
        if (body >= 0) {
            text = text.substring(body + RootPipelineTemplates.BODY_MARK.length());
        }
        final int tail = text.indexOf(RootPipelineTemplates.TAIL_MARK);
        if (tail >= 0) {
            text = text.substring(0, tail);
        }
        for (Function f : macroOf(text, null).functions()) {
            if (f.template() || f.internal() || f.name().equals("main")
                    || f.name().startsWith("sphere_pipeline_") || !knownType(f.returnType())) {
                continue;
            }
            boolean known = true;
            final List<String> written = new ArrayList<>();
            for (String parameter : splitTopLevel(f.parameters(), ',')) {
                final String declared = parameter.replaceAll("(?s)=.*$", "").trim();
                if (declared.isEmpty() || declared.equals("void")) {
                    continue;
                }
                final String name = lastIdentifier(declared);
                // "double x" names x; "double" alone, or "const double &", names nothing.
                final String type = name != null && declared.endsWith(name)
                        && !KNOWN_TYPE_WORDS.contains(name) && !ROOT_CLASS.matcher(name).matches()
                    ? declared.substring(0, declared.length() - name.length()) : declared;
                if (!knownType(type)) {
                    known = false;
                    break;
                }
                // A default value is kept when it is a plain literal. One that
                // names a constant of the library would make the declaration
                // fail, and the scan sees string literals blanked out.
                final String whole = parameter.replaceAll("\\s+", " ").trim();
                final int eq = whole.indexOf('=');
                final boolean literal = eq > 0 && whole.substring(eq + 1).trim()
                    .matches("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?[fFlLuU]*|true|false|nullptr");
                written.add(literal ? whole : declared.replaceAll("\\s+", " "));
            }
            if (!known) {
                continue;
            }
            final String one = f.returnType() + " " + f.name() + "(" + String.join(", ", written) + ");";
            out.add(f.space().isEmpty() ? one : "namespace " + f.space() + " { " + one + " }");
        }
        return out;
    }

    /** True when every word of a type is one the interpreter knows without the library. */
    private static boolean knownType(String type) {
        final Matcher word = IDENTIFIER.matcher(type);
        boolean any = false;
        while (word.find()) {
            any = true;
            final String w = word.group();
            if (!KNOWN_TYPE_WORDS.contains(w) && !ROOT_CLASS.matcher(w).matches()) {
                return false;
            }
        }
        return any;
    }

    /* ================================================================== */
    /* The one entry point the command line uses                           */
    /* ================================================================== */

    /**
     * Reads what follows "from":
     *   = formula            the formula's value, or a filter when it compares
     *   err = formula        its uncertainty by linear propagation
     *   d/dx = formula       its derivative in x
     *   copy name, or name   a pipeline already written
     *   file.C[:function]    a function of a macro
     *   anything else        words searched in the catalog
     */
    public static Start resolve(String what, String activeProject) {
        final String w = what == null ? "" : what.trim();
        if (w.isEmpty()) {
            return Start.fail("From what? Recipe words, = formula, err = formula, d/dx = formula, "
                + "copy <pipeline>, or file.C[:function].");
        }
        if (w.startsWith("=")) {
            return fromFormula(w.substring(1));
        }
        final Matcher sigma = Pattern.compile("(?i)^(?:err|error|sigma|unc|uncertainty)\\s*=\\s*(.+)$").matcher(w);
        if (sigma.matches()) {
            return fromUncertainty(sigma.group(1));
        }
        final Matcher slope = Pattern.compile("^d/d([A-Za-z_]\\w*)\\s*=\\s*(.+)$").matcher(w);
        if (slope.matches()) {
            return fromDerivative(slope.group(2), slope.group(1));
        }
        if (w.toLowerCase(Locale.ROOT).startsWith("formula ")) {
            return fromFormula(w.substring(8));
        }
        final boolean copy = w.toLowerCase(Locale.ROOT).startsWith("copy ");
        final String target = copy ? w.substring(5).trim() : w;
        final Path pipeline = pipelineNamed(target, activeProject);
        if (pipeline != null) {
            return fromPipeline(pipeline, activeProject);
        }
        if (copy) {
            return Start.fail("No pipeline named " + target + ".");
        }
        final Matcher macro = Pattern.compile("^(.+?\\.(?:C|c|cc|cxx|cpp|h|hh|hpp|hxx))(?::(\\w+))?$").matcher(w);
        if (macro.matches()) {
            final Path file = macroFile(macro.group(1), activeProject);
            if (file == null) {
                return Start.fail("No macro " + macro.group(1) + " here, in user_scripts/ or beside Sphere.");
            }
            return fromMacro(file, macro.group(2), activeProject);
        }
        // Math written without its "=": it reads as a formula and has an operator in it.
        if (w.matches(".*[+*/^()<>].*") && !w.contains(" ")) {
            final Start s = fromFormula(w);
            if (s.ok()) {
                return s;
            }
        }
        final Recipe exact = RootPipelineRecipes.byTitle(w);
        if (exact != null) {
            return Start.of(exact);
        }
        final List<Hit> hits = search(w, 4);
        if (hits.isEmpty()) {
            return Start.fail("Nothing in the catalog answers \"" + w + "\". :root forge recipes lists it.");
        }
        final List<String> notes = new ArrayList<>();
        if (hits.size() > 1 && hits.get(1).score() >= hits.get(0).score() - 1) {
            final List<String> others = new ArrayList<>();
            for (Hit one : hits.subList(1, hits.size())) {
                others.add(one.recipe().title());
            }
            notes.add("Also close: " + String.join(", ", others) + ".");
        }
        return new Start(hits.get(0).recipe(), null, notes, null, null);
    }

    /** A pipeline source on disk, by its declared name; the project wins over global. */
    public static Path pipelineNamed(String name, String activeProject) {
        if (name == null || name.isBlank() || !name.matches("[A-Za-z_]\\w*")) {
            return null;
        }
        Path found = null;
        for (Path root : RootUserPipeline.layers(activeProject)) {
            for (Path source : RootUserPipeline.sources(root)) {
                final RootPipelineManifest m = RootPipelineManifest.fromSource(source);
                if (m != null && m.name.equalsIgnoreCase(name)) {
                    found = source;
                }
            }
        }
        return found;
    }

    /** Every pipeline on disk that can be copied. */
    public static List<Path> copyable(String activeProject) {
        final List<Path> out = new ArrayList<>();
        for (Path root : RootUserPipeline.layers(activeProject)) {
            for (Path source : RootUserPipeline.sources(root)) {
                final RootPipelineManifest m = RootPipelineManifest.fromSource(source);
                if (m != null && m.kind != Kind.LIBRARY) {
                    out.add(source);
                }
            }
        }
        return out;
    }

    private static Path macroFile(String given, String activeProject) {
        try {
            final Path direct = Path.of(given);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            final Path resolved = RootUserPipeline.resolveMacro(given, activeProject);
            if (resolved != null && Files.isRegularFile(resolved)) {
                return resolved;
            }
            for (Path root : RootUserPipeline.layers(activeProject)) {
                for (String dir : new String[]{RootUserPipeline.SCRIPTS_DIR, "includes", "."}) {
                    final Path one = root.resolve(dir).resolve(given);
                    if (Files.isRegularFile(one)) {
                        return one;
                    }
                }
            }
        } catch (InvalidPathException | SecurityException notAPath) {
            return null;
        }
        return null;
    }

    /* ================================================================== */
    /* Reading C++                                                         */
    /* ================================================================== */

    private static final byte CODE = 0;
    private static final byte COMMENT = 1;
    private static final byte STRING = 2;
    private static final byte CHARACTER = 3;

    /** What each character of a source is: code, comment, string or character literal. */
    private static byte[] kinds(String text) {
        final int n = text.length();
        final byte[] kind = new byte[n];
        int k = 0;
        while (k < n) {
            final char ch = text.charAt(k);
            final char next = k + 1 < n ? text.charAt(k + 1) : '\0';
            if (ch == '/' && next == '/') {
                int end = text.indexOf('\n', k);
                if (end < 0) {
                    end = n;
                }
                java.util.Arrays.fill(kind, k, end, COMMENT);
                k = end;
            } else if (ch == '/' && next == '*') {
                int end = text.indexOf("*/", k + 2);
                end = end < 0 ? n : end + 2;
                java.util.Arrays.fill(kind, k, end, COMMENT);
                k = end;
            } else if (ch == '"' || ch == '\'') {
                int end = k + 1;
                while (end < n && text.charAt(end) != ch && text.charAt(end) != '\n') {
                    if (text.charAt(end) == '\\') {
                        end++;
                    }
                    end++;
                }
                end = Math.min(end + 1, n);
                java.util.Arrays.fill(kind, k, end, ch == '"' ? STRING : CHARACTER);
                k = end;
            } else {
                k++;
            }
        }
        return kind;
    }

    /** The text with comments, strings and characters blanked, every offset and line kept. */
    static String blanked(String text) {
        final byte[] kind = kinds(text);
        final char[] out = text.toCharArray();
        for (int i = 0; i < out.length; i++) {
            if (kind[i] != CODE && out[i] != '\n') {
                out[i] = ' ';
            }
        }
        return new String(out);
    }

    /**
     * One name replaced by another, in code and comments; inside string
     * literals too when asked, where "deltaR_h" is renamed with it.
     */
    static String rename(String text, String name, String replacement, boolean inStrings) {
        if (text == null || name == null || name.isEmpty()) {
            return text;
        }
        final byte[] kind = kinds(text);
        final Pattern word = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");
        final Pattern loose = Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(name) + "(?![A-Za-z0-9])");
        final String with = Matcher.quoteReplacement(replacement);
        final StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int j = i;
            while (j < text.length() && kind[j] == kind[i]) {
                j++;
            }
            final String run = text.substring(i, j);
            switch (kind[i]) {
                case CODE, COMMENT -> out.append(word.matcher(run).replaceAll(with));
                case STRING -> out.append(inStrings ? loose.matcher(run).replaceAll(with) : run);
                default -> out.append(run);
            }
            i = j;
        }
        return out.toString();
    }

    /** The pipeline's own name turned into the placeholder its new name is written over. */
    static String renameSelf(String text, String name) {
        return rename(text, name, RootPipelineRecipes.SELF, true);
    }

    /** The brace that closes the one at {@code open}, in text whose comments are blanked. */
    static int matching(String code, int open) {
        int depth = 0;
        for (int k = open; k < code.length(); k++) {
            final char ch = code.charAt(k);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return k;
            }
        }
        return -1;
    }

    private static int lineEnd(String text, int from) {
        final int end = text.indexOf('\n', from);
        return end < 0 ? text.length() : end;
    }

    private static int skipSpace(String code, int from, int to) {
        int k = from;
        while (k < to && Character.isWhitespace(code.charAt(k))) {
            k++;
        }
        return k;
    }

    private static int topLevel(String s, char wanted) {
        int depth = 0;
        for (int k = 0; k < s.length(); k++) {
            final char ch = s.charAt(k);
            if (ch == '(' || ch == '{' || ch == '[' || ch == '<') {
                depth++;
            } else if (ch == ')' || ch == '}' || ch == ']' || ch == '>') {
                depth--;
            } else if (ch == wanted && depth == 0) {
                if (wanted == '=' && k + 1 < s.length() && s.charAt(k + 1) == '=') {
                    k++;
                    continue;
                }
                return k;
            }
        }
        return -1;
    }

    /** Split on the separators that are not inside (), {}, [] or <>. */
    private static List<String> splitTopLevel(String list, char separator) {
        final List<String> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int k = 0; k < list.length(); k++) {
            final char ch = list.charAt(k);
            if (ch == '(' || ch == '{' || ch == '[' || ch == '<') {
                depth++;
            } else if (ch == ')' || ch == '}' || ch == ']' || ch == '>') {
                depth--;
            } else if (ch == separator && depth == 0) {
                out.add(list.substring(from, k));
                from = k + 1;
            }
        }
        if (from < list.length()) {
            out.add(list.substring(from));
        }
        return out;
    }

    private static String lastIdentifier(String s) {
        final Matcher m = IDENTIFIER.matcher(s);
        String last = null;
        while (m.find()) {
            last = m.group();
        }
        return last;
    }

    /**
     * The modules a formula's code needs: TMath only when it is written out.
     *
     * A formula is written with std:: functions, which need no ROOT at all.
     * Without the TMath include its pipeline compiles with a bare g++, so the
     * builder's Inspect tab, :root pipeline check and the timing all work on a
     * machine where ROOT is not installed or its engine is off.
     */
    static List<RootPipelineModules> mathFor(String body) {
        return blanked(body).contains("TMath::") ? List.of(RootPipelineModules.MATH) : List.of();
    }

    /** The ROOT modules a piece of code visibly reaches for. */
    static List<RootPipelineModules> modulesUsedBy(String code) {
        final List<RootPipelineModules> out = new ArrayList<>();
        if (code == null) {
            return out;
        }
        if (code.matches("(?s).*\\bTMath::.*")) {
            out.add(RootPipelineModules.MATH);
        }
        if (code.matches("(?s).*\\b(RVec|VecOps|RVecD|RVecF|RVecI)\\b.*")) {
            out.add(RootPipelineModules.VECTORS);
        }
        if (code.matches("(?s).*\\b(PtEtaPhi[EM]Vector|PxPyPzEVector|LorentzVector|XYZVector|Polar3DVector)\\b.*")) {
            out.add(RootPipelineModules.LORENTZ);
        }
        if (code.matches("(?s).*\\b(TTreeReader|TTreeReaderValue|TTreeReaderArray|TTree)\\b.*")) {
            out.add(RootPipelineModules.TREE);
        }
        if (code.matches("(?s).*\\b(TH1[DFI]?|TH2[DFI]?|TProfile)\\b.*")) {
            out.add(RootPipelineModules.HIST);
        }
        if (code.matches("(?s).*\\b(TF1|ROOT::Fit)\\b.*")) {
            out.add(RootPipelineModules.FIT);
        }
        if (code.matches("(?s).*\\b(TRandom3?|gRandom)\\b.*")) {
            out.add(RootPipelineModules.RANDOM);
        }
        if (code.matches("(?s).*\\b(TFile|TDirectory)\\b.*")) {
            out.add(RootPipelineModules.FILES);
        }
        if (code.matches("(?s).*\\bRoo[A-Z]\\w*.*")) {
            out.add(RootPipelineModules.ROOFIT);
        }
        if (code.matches("(?s).*\\bTMVA::.*")) {
            out.add(RootPipelineModules.TMVA);
        }
        if (code.matches("(?s).*\\bROOT::RDataFrame\\b.*")) {
            out.add(RootPipelineModules.FRAME);
        }
        return out;
    }

    /** The body's own indentation taken away and two spaces put back. */
    private static String reindent(String body) {
        final List<String> lines = new ArrayList<>(List.of(body.split("\n", -1)));
        while (!lines.isEmpty() && lines.get(0).isBlank()) {
            lines.remove(0);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        int least = Integer.MAX_VALUE;
        for (String one : lines) {
            if (!one.isBlank()) {
                int lead = 0;
                while (lead < one.length() && (one.charAt(lead) == ' ' || one.charAt(lead) == '\t')) {
                    lead++;
                }
                least = Math.min(least, lead);
            }
        }
        if (least == Integer.MAX_VALUE) {
            least = 0;
        }
        final StringBuilder out = new StringBuilder();
        for (String one : lines) {
            out.append(one.isBlank() ? "" : "  " + one.substring(Math.min(least, one.length())).stripTrailing())
               .append('\n');
        }
        return out.toString();
    }

    /* ================================================================== */
    /* Small things                                                        */
    /* ================================================================== */

    private static List<Input> doubles(List<String> names) {
        final List<Input> out = new ArrayList<>();
        for (String one : names) {
            out.add(new Input(one, "double"));
        }
        return out;
    }

    private static Set<String> names(List<Input> inputs) {
        final Set<String> out = new LinkedHashSet<>();
        for (Input one : inputs) {
            out.add(one.name());
        }
        return out;
    }

    /** A name not taken yet, taken from now on: x_err, then x_err2, ... */
    private static String fresh(String wanted, Set<String> taken) {
        String name = wanted;
        int k = 2;
        while (taken.contains(name)) {
            name = wanted + (k++);
        }
        taken.add(name);
        return name;
    }

    private static String namesWithTypes(List<Input> inputs) {
        if (inputs.isEmpty()) {
            return "no input";
        }
        final List<String> out = new ArrayList<>();
        for (Input one : inputs) {
            out.add(one.name() + " (" + one.type() + ")");
        }
        return String.join(", ", out);
    }

    /** A description safe in the manifest line, whose fields are separated by " :: ". */
    private static String safe(String text) {
        return text.replace(" :: ", " ").replace('\n', ' ').replace('"', '\'');
    }

    static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        final int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    private static String clip(String text, int most) {
        return text.length() <= most ? text : text.substring(0, most - 3) + "...";
    }

    private static String read(Path file) {
        try {
            return file == null ? null : Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }
}

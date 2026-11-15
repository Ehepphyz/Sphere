package com.sphere.core.commands;

import com.sphere.core.geantbackend.G4Json;
import com.sphere.core.ml.GraphCompiler;
import com.sphere.core.ml.MlEngine;
import com.sphere.core.ml.NeuralGraph;
import com.sphere.utils.AppLogger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Inference, unfolding and model search at the frontier, each held to a reference:
 * <ul>
 * <li>:ml sbi -- simulation-based inference: likelihood ratios learned from a simulator (CARL, and ALICES on what
 * the simulator knows of each event), their scores calibrated into unbiased estimating equations, a W mass measured
 * with them against the exact likelihood (in closed form), the Cramer-Rao bound and the LHC's template fits; the
 * learned ratio then run by Java, interpreted and compiled (sphere_sbi);</li>
 * <li>:ml unfold -- OmniFold, all observables of a jet unfolded at once and unbinned, against iterative Bayesian
 * unfolding (to the rounding in its binned limit) and the truth (sphere_unfold);</li>
 * <li>:ml automl, :ml automl check -- Bayesian optimization of a tagger under the constraints of the physics (its
 * score decorrelated from the mass) and of the machine (weights, latency), the optimizer first held to scikit-learn
 * and to the constrained Branin and Hartmann references (sphere_automl).</li>
 * </ul>
 */
final class FrontierCommands {

    private FrontierCommands() {
    }

    static void register() {
        CommandDefinitions.register(":ml sbi", "Simulation-based inference of a W mass: likelihood ratios learned from a simulator"
            + " (CARL; ALICES on the joint ratio of each event and its latent mass), their scores calibrated, measured on"
            + " pseudo-experiments against the exact likelihood, the Cramer-Rao bound and template fits in pT and mT. Usage: :ml sbi"
            + " [--samples 100000] [--epochs 8] [--ensemble 1] [--alpha 0] [--experiments 100] [--events 2000] [--estimators"
            + " carl,alices] [--calibration 1000000]", FrontierCommands::sbi);
        CommandDefinitions.register(":ml unfold", "OmniFold: six observables of a jet unfolded at once and unbinned, by two classifiers an"
            + " iteration; binned, held to iterative Bayesian unfolding to the rounding; then against IBU and the truth, with its"
            + " closure, correlations and a derived observable. Usage: :ml unfold [--jets 40000] [--iterations 5] [--epochs 8]"
            + " [--bins 20] [--noclosure]", FrontierCommands::unfold);
        CommandDefinitions.register(":ml automl check", "The optimizer of ':ml automl' held to references: its Gaussian process against"
            + " scikit-learn's, its search against random search on the constrained Branin and Hartmann-6 functions. Usage: :ml automl"
            + " check [--seeds 3]", FrontierCommands::automlCheck);
        CommandDefinitions.register(":ml automl", "Search a tagger's architecture and training by Bayesian optimization under constraints:"
            + " the validation AUC maximized, the score's distance correlation to the mass bounded, the weights and the latency too;"
            + " the best trained again, set against the zoo's default. Usage: :ml automl <dataset> [deepsets|efn|mlp] [--trials 16]"
            + " [--epochs 8] [--dcorr 0.15] [--params N] [--latency us] [--name n]", FrontierCommands::automl);
    }

    // ------------------------------------------------------------------
    // :ml sbi
    // ------------------------------------------------------------------

    private static void sbi(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml sbi");
        MlCommands.background("ml-sbi", () -> {
            try {
                final MlEngine e = MlCommands.engine("torch");
                final Map<String, Object> args = new LinkedHashMap<>();
                for (String k : List.of("samples", "epochs", "width", "depth", "ensemble", "experiments", "events", "calibration",
                    "seed")) {
                    if (a.flags().containsKey(k)) {
                        args.put(k, a.num(k, 0));
                    }
                }
                if (a.flags().containsKey("templates")) {
                    args.put("template_events", a.num("templates", 0));
                }
                if (a.flags().containsKey("alpha")) {
                    args.put("alpha", a.real("alpha", 0));
                }
                if (a.flags().containsKey("estimators")) {
                    args.put("estimators", List.of(a.flags().get("estimators").split(",")));
                }
                AppLogger.info("Simulation-based inference of a W mass, held to its exact likelihood (a few minutes; ':ml cancel'"
                    + " stops it)...");
                final Map<String, Object> r = e.call("sbi_run", args, (f, m) -> AppLogger.stream(m)).get();
                AppLogger.raw("  " + G4Json.str(r, "model", ""));
                final Map<String, Object> c = G4Json.obj(r, "check");
                AppLogger.raw(String.format(Locale.ROOT, "  the exact likelihood: Faddeeva form against adaptive quadrature %.1e, the"
                        + " missing pT integrated out %.1e, the density's integral against the acceptance %.1e; acceptance %.6f (the"
                        + " simulator's count: pull %+.2f); the joint ratio's weights average %.5f (pull %+.2f)",
                    G4Json.num(c, "faddeeva_vs_quadrature", Double.NaN), G4Json.num(c, "missing_pt_integral_vs_closed_form", Double.NaN),
                    G4Json.num(c, "density_integral_vs_acceptance", Double.NaN), G4Json.num(c, "acceptance", Double.NaN),
                    G4Json.num(c, "acceptance_pull", Double.NaN), G4Json.num(c, "joint_weights_mean", Double.NaN),
                    G4Json.num(c, "joint_weights_pull", Double.NaN)));
                final Map<String, Object> est = G4Json.obj(r, "estimators");
                for (Map.Entry<String, Object> en : est.entrySet()) {
                    final Map<String, Object> x = G4Json.obj(est, en.getKey());
                    AppLogger.raw(String.format(Locale.ROOT, "  %-6s log r within %.4f of the exact (its own scale %.4f); %d weights x %d,"
                            + " %d simulated events, %.0f s; its score's mean under the simulator off by up to %.4f /GeV (known to"
                            + " %.1e), calibrated away", en.getKey().toUpperCase(Locale.ROOT), G4Json.num(x, "logr_rms", Double.NaN),
                        G4Json.num(x, "logr_scale", Double.NaN), (int) G4Json.num(x, "weights", 0) / Math.max(1, (int) G4Json.num(x,
                            "members", 1)), (int) G4Json.num(x, "members", 1), (int) G4Json.num(x, "samples", 0),
                        G4Json.num(x, "seconds", 0), G4Json.num(x, "score_calibration_worst", Double.NaN),
                        G4Json.num(x, "score_calibration_error", Double.NaN)));
                }
                final Map<String, Object> inf = G4Json.obj(r, "inference");
                AppLogger.raw(String.format(Locale.ROOT, "  %d pseudo-experiments of %d events (MeV):", (int) G4Json.num(r, "experiments", 0),
                    (int) G4Json.num(r, "events", 0)));
                AppLogger.raw(String.format(Locale.ROOT, "    %-18s %13s %7s %7s %11s %9s %21s", "method", "bias", "rms", "sigma", "pulls",
                    "coverage", "vs the exact"));
                for (Map.Entry<String, Object> en : inf.entrySet()) {
                    final Map<String, Object> x = G4Json.obj(inf, en.getKey());
                    AppLogger.raw(String.format(Locale.ROOT, "    %-18s %6.1f +- %4.1f %7.1f %7.1f %5.2f/%5.2f %9.3f %+8.1f +- %4.1f (%5.1f)",
                        en.getKey(), G4Json.num(x, "bias_mev", Double.NaN), G4Json.num(x, "bias_err_mev", Double.NaN),
                        G4Json.num(x, "rms_mev", Double.NaN), G4Json.num(x, "sigma_mev", Double.NaN), G4Json.num(x, "pull_mean", Double.NaN),
                        G4Json.num(x, "pull_width", Double.NaN), G4Json.num(x, "coverage", Double.NaN),
                        G4Json.num(x, "shift_vs_exact_mev", Double.NaN), G4Json.num(x, "shift_vs_exact_err_mev", Double.NaN),
                        G4Json.num(x, "rms_vs_exact_mev", Double.NaN)));
                }
                AppLogger.raw(String.format(Locale.ROOT, "  the Cramer-Rao bound: %.1f MeV (%.4f /GeV^2 an event); the coverage's own"
                        + " error +-%.3f; '_raw' the networks uncalibrated, '_normalized' renormalized as CARL prescribes",
                    G4Json.num(r, "cramer_rao_mev", Double.NaN), G4Json.num(r, "fisher_per_event", Double.NaN),
                    G4Json.num(G4Json.obj(inf, "exact"), "coverage_err", Double.NaN)));
                final Map<String, Object> g = G4Json.obj(r, "graph");
                if (g.containsKey("error")) {
                    AppLogger.warn("the network as a graph: " + g.get("error"));
                } else if (!g.isEmpty()) {
                    javaRatio(g);
                }
                AppLogger.success(G4Json.str(r, "verdict", "") + String.format(Locale.ROOT, " (%.0f s)", G4Json.num(r, "seconds", 0)));
            } catch (Exception ex) {
                AppLogger.error("ml sbi: " + MlCommands.failure(ex));
            }
        });
    }

    private static double[] doubles(List<?> l) {
        final double[] out = new double[l.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ((Number) l.get(i)).doubleValue();
        }
        return out;
    }

    /** The learned ratio as Java runs it: the exported graph on PyTorch's events, interpreted, then compiled. */
    private static void javaRatio(Map<String, Object> g) {
        try {
            final NeuralGraph graph = NeuralGraph.load(Path.of(G4Json.str(g, "path", "")));
            final double[] ref = doubles(G4Json.list(g, "logr"));
            final int n = ref.length;
            final List<NeuralGraph.Tensor> in = new ArrayList<>();
            for (String k : List.of("x", "y", "eta", "theta")) {
                in.add(new NeuralGraph.Tensor(doubles(G4Json.list(g, k)), n));
            }
            final double[] got = graph.run(in).get(0).data;
            double worst = 0;
            for (int i = 0; i < n; i++) {
                worst = Math.max(worst, Math.abs(got[i] - ref[i]));
            }
            String compiled;
            try {
                final GraphCompiler.CompiledGraph cg = GraphCompiler.compile(graph, in, true);
                compiled = cg.check(in) + " values differing from the interpreter's";
            } catch (Exception ex) {
                compiled = "not compiled (" + MlCommands.failure(ex) + ")";
            }
            AppLogger.raw(String.format(Locale.ROOT, "  in Java: %s, log r within %.1e of PyTorch's (float64) on %d events; as bytecode,"
                + " %s (':torch jit %s <events>' times it)", graph.name, worst, n, compiled, graph.name));
        } catch (Exception ex) {
            AppLogger.warn("the learned ratio in Java: " + MlCommands.failure(ex));
        }
    }

    // ------------------------------------------------------------------
    // :ml unfold
    // ------------------------------------------------------------------

    private static void unfold(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml unfold");
        MlCommands.background("ml-unfold", () -> {
            try {
                final MlEngine e = MlCommands.engine("torch");
                final Map<String, Object> args = new LinkedHashMap<>();
                for (String k : List.of("jets", "iterations", "epochs", "later", "bins", "seed")) {
                    if (a.flags().containsKey(k)) {
                        args.put(k, a.num(k, 0));
                    }
                }
                if (a.flags().containsKey("noclosure")) {
                    args.put("closure", false);
                }
                AppLogger.info("OmniFold against iterative Bayesian unfolding and the truth (a few minutes; ':ml cancel' stops it)...");
                final Map<String, Object> r = e.call("unfold_run", args, (f, m) -> AppLogger.stream(m)).get();
                final Map<String, Object> bin = G4Json.obj(r, "binned");
                double worst = 0;
                for (String k : bin.keySet()) {
                    worst = Math.max(worst, G4Json.num(G4Json.obj(bin, k), "omnifold_vs_ibu", Double.NaN));
                }
                AppLogger.raw(String.format(Locale.ROOT, "  binned OmniFold (histograms for classifiers) against IBU, %d iterations, every"
                    + " observable: within %.1e", (int) G4Json.num(r, "iterations", 0), worst));
                AppLogger.raw("  triangular discrimination to the truth (x1000), " + (int) G4Json.num(r, "jets", 0) + " jets:");
                AppLogger.raw(String.format(Locale.ROOT, "    %-18s %10s %8s %9s %7s %8s %9s %15s", "observable", "simulation", "IBU",
                    "OmniFold", "floor", "closure", "detector", "refold IBU/Omni"));
                final Map<String, Object> d = G4Json.obj(r, "discrimination");
                for (Map.Entry<String, Object> en : d.entrySet()) {
                    final Map<String, Object> x = G4Json.obj(d, en.getKey());
                    AppLogger.raw(String.format(Locale.ROOT, "    %-18s %10.2f %8.2f %9.2f %7.2f %8.2f %9.2f %7.2f/%-7.2f",
                        G4Json.str(x, "label", en.getKey()), G4Json.num(x, "prior", Double.NaN), G4Json.num(x, "ibu", Double.NaN),
                        G4Json.num(x, "omnifold", Double.NaN), G4Json.num(x, "floor", Double.NaN), G4Json.num(x, "closure", Double.NaN),
                        G4Json.num(x, "detector", Double.NaN), G4Json.num(x, "refold_ibu", Double.NaN),
                        G4Json.num(x, "refold_omnifold", Double.NaN)));
                }
                final Map<String, Object> cor = G4Json.obj(r, "correlations");
                for (Map.Entry<String, Object> en : cor.entrySet()) {
                    final Map<String, Object> x = G4Json.obj(cor, en.getKey());
                    AppLogger.raw(String.format(Locale.ROOT, "  correlation %-20s truth %+.3f, simulation %+.3f, OmniFold %+.3f", en.getKey(),
                        G4Json.num(x, "truth", Double.NaN), G4Json.num(x, "prior", Double.NaN), G4Json.num(x, "omnifold", Double.NaN)));
                }
                final Map<String, Object> dv = G4Json.obj(r, "derived");
                AppLogger.raw(String.format(Locale.ROOT, "  %s, an observable no classifier saw: simulation %.2f, OmniFold %.2f (floor %.2f)",
                    G4Json.str(dv, "name", "?"), G4Json.num(dv, "prior", Double.NaN), G4Json.num(dv, "omnifold", Double.NaN),
                    G4Json.num(dv, "floor", Double.NaN)));
                AppLogger.success(G4Json.str(r, "verdict", "") + String.format(Locale.ROOT, " (%.0f s)", G4Json.num(r, "seconds", 0)));
            } catch (Exception ex) {
                AppLogger.error("ml unfold: " + MlCommands.failure(ex));
            }
        });
    }

    // ------------------------------------------------------------------
    // :ml automl
    // ------------------------------------------------------------------

    private static void automlCheck(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml automl check");
        MlCommands.background("ml-automl-check", () -> {
            try {
                final MlEngine e = MlCommands.engine("torch");
                final Map<String, Object> args = new LinkedHashMap<>();
                args.put("seeds", a.num("seeds", 3));
                AppLogger.info("The optimizer held to references (about two minutes)...");
                final Map<String, Object> r = e.call("automl_check", args, (f, m) -> AppLogger.stream(m)).get();
                final Map<String, Object> gp = G4Json.obj(r, "gp_vs_sklearn");
                AppLogger.raw(gp.containsKey("error") ? "  scikit-learn: " + gp.get("error") : String.format(Locale.ROOT,
                    "  the Gaussian process against scikit-learn's at the same hyperparameters: mean %.1e, deviation %.1e, log marginal"
                        + " likelihood %.1e", G4Json.num(gp, "mean", Double.NaN), G4Json.num(gp, "std", Double.NaN),
                    G4Json.num(gp, "log_marginal_likelihood", Double.NaN)));
                boolean ok = true;
                for (String k : List.of("branin_constrained", "hartmann6")) {
                    final Map<String, Object> x = G4Json.obj(r, k);
                    final double bo = G4Json.num(x, "bo_gap", Double.NaN), rs = G4Json.num(x, "random_gap", Double.NaN);
                    ok &= bo < rs;
                    AppLogger.raw(String.format(Locale.ROOT, "  %-19s optimum %.5f: Bayesian optimization %.5f (%.1e from it), random search"
                            + " %.5f (%.1e), %d evaluations, the median of %d seeds", k, G4Json.num(x, "optimum", Double.NaN),
                        G4Json.num(x, "bo_median", Double.NaN), bo, G4Json.num(x, "random_median", Double.NaN), rs,
                        (int) G4Json.num(x, "budget", 0), (int) G4Json.num(x, "seeds", 0)));
                }
                if (ok) {
                    AppLogger.success("The optimizer finds what random search misses, on both references");
                } else {
                    AppLogger.warn("Random search did as well on a reference: see above");
                }
            } catch (Exception ex) {
                AppLogger.error("ml automl check: " + MlCommands.failure(ex));
            }
        });
    }

    private static void automl(String input, CommandExecutionContext ctx) {
        final MlCommands.Args a = MlCommands.Args.of(input, ":ml automl");
        if (a.words().isEmpty()) {
            Handlers.usage(":ml automl <dataset> [deepsets|efn|mlp] [--trials 16] [--epochs 8] [--dcorr 0.15] [--params N] [--latency us]"
                + " [--name n]");
            return;
        }
        MlCommands.background("ml-automl", () -> {
            try {
                final MlEngine e = MlCommands.engine("torch");
                final Map<String, Object> args = new LinkedHashMap<>();
                args.put("data", e.path(MlCommands.dataset(a.word(0, ""))));
                args.put("model", a.word(1, "deepsets"));
                args.put("trials", a.num("trials", 16));
                args.put("epochs", a.num("epochs", 8));
                args.put("max_dcorr", a.real("dcorr", 0.15));
                if (a.flags().containsKey("params")) {
                    args.put("max_params", a.num("params", 0));
                }
                if (a.flags().containsKey("latency")) {
                    args.put("max_latency_us", a.real("latency", 0));
                }
                for (String k : List.of("seed", "name")) {
                    if (a.flags().containsKey(k)) {
                        args.put(k, k.equals("seed") ? (Object) a.num(k, 0) : a.flags().get(k));
                    }
                }
                AppLogger.info("Searching " + args.get("model") + " on " + a.word(0, "") + ": " + args.get("trials") + " trials of "
                    + args.get("epochs") + " epochs (':ml cancel' stops it)...");
                final Map<String, Object> r = e.call("automl_search", args, (f, m) -> AppLogger.stream(m)).get();
                final Map<String, Object> best = G4Json.obj(r, "best");
                if (!best.isEmpty()) {
                    AppLogger.raw(String.format(Locale.ROOT, "  the network: %s (%s), test AUC %.4f, background rejection %.1f at 50 %% signal,"
                            + " the mass sculpted by %.3f (JSD)", G4Json.str(best, "name", "?"), G4Json.str(best, "path", ""),
                        G4Json.num(best, "test_auc", Double.NaN), G4Json.num(best, "test_rejection_50", Double.NaN),
                        G4Json.num(best, "sculpting_jsd", Double.NaN)));
                }
                AppLogger.success(G4Json.str(r, "verdict", "") + String.format(Locale.ROOT, " (%.0f s)", G4Json.num(r, "seconds", 0)));
            } catch (Exception ex) {
                AppLogger.error("ml automl: " + MlCommands.failure(ex));
            }
        });
    }
}

package com.sphere.core.geant4;

import com.sphere.core.bridge.Bridge;
import com.sphere.core.geantbackend.Geant4AppBuilder;
import com.sphere.core.geantbackend.Geant4Locator;
import com.sphere.core.ml.GraphCpp;
import com.sphere.core.ml.MlLocator;
import com.sphere.core.ml.NeuralGraph;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A learned fast simulation of a detector, from the design to a validated Geant4 application, without a human in the
 * loop.
 *
 * <ol>
 * <li>A pilot run measures what a shower costs and how it fluctuates, and which cells matter.</li>
 * <li>The time given is spent where it buys the most: the showers to learn from, log-uniform in energy; development
 * and final showers at fixed energies, more of them where they are cheap and noisy, so that every energy is measured
 * as precisely as the others.</li>
 * <li>What least squares can give is not left to the network: {@link ShowerBasis} places the mean profile and its width
 * at every energy in closed form, and the flow, trained in PyTorch, learns the rest.</li>
 * <li>The flow's latent space is then recalibrated on showers it never saw: exactly invertible, the flow maps a real
 * shower to its latent point, which should be N(0, 1) in every coordinate; the mean and width it really has there,
 * shrunk to zero when they are noise, become the base distribution the C++ draws from.</li>
 * <li>The fast simulation is held to Geant4 on the development showers, test by test, each with three outcomes. A
 * scale alone wrong is calibrated in closed form; a shape wrong brings showers where it failed and a longer, wider
 * training; what is not yet known brings showers where it is not known.</li>
 * <li>Once the development showers pass, fresh showers of Geant4 -- never used for any decision -- give the verdict,
 * once. An application whose verdict is not a pass runs its full physics unless told otherwise.</li>
 * </ol>
 * The primary's energy, direction and position at the envelope are what the network learns from and what it is used
 * with: the C++ records them in every run, at the same place the fast simulation is triggered.
 */
public final class FastSimPipeline {

    private FastSimPipeline() {
    }

    private static final int PILOT_EVENTS = 300, MIN_TRAINING = 1500, MIN_PER_POINT = 150, OVERSAMPLE = 4;
    /** A shower whose cells saw less than this share of its energy saw nothing. */
    private static final double EMPTY = 1e-9;
    /** The share of the visible energy the cells kept must hold. */
    private static final double KEPT = 0.9995;
    /** The showers learned from reach this factor beyond the energies replaced: the edges of a flow's range are where it is worst. */
    private static final double GUARD = 1.3;

    /** What the pipeline tells whoever started it, and asks whether to stop. */
    public interface Listener {
        void log(String line);

        default void stage(String name, double fraction) {
        }

        default boolean canceled() {
            return false;
        }
    }

    /** {@code minutes}: the time it may take; 0 threads: all there are. */
    public record Settings(double minutes, long seed, int threads, int maxRounds) {
        public static Settings defaults() {
            return new Settings(20, 20261109L, 0, 4);
        }
    }

    /** How it ended. {@code points}: the final comparison on fresh showers (or the last development one if it never got there). */
    public record Result(boolean validated, String verdict, FastSimPlanner.Plan plan, List<FastSimStats.Point> points, double speedup,
                         double secondsPerFull, double secondsPerFast, double firedFraction, int rounds, Path application, Path report) {
    }

    /**
     * A shower as a run wrote it: the gun's energy, the primary's at the envelope (0 if it never entered), 1 if it entered
     * within the acceptance + 2 if also within the energies, the energy other particles brought in, the cells kept.
     */
    record Shower(double gun, double entry, int flag, double other, double[] cells) {
        double visible() {
            double s = 0;
            for (double c : cells) {
                s += c;
            }
            return s;
        }

        boolean inDomain() {
            return (flag & 2) != 0;
        }

        /** The primary alone entered, within the acceptance, at any energy: a shower that is the network's to learn. */
        boolean clean() {
            return (flag & 1) != 0 && entry > 0 && other <= 1e-3 * entry;
        }

        /** A clean shower whose cells saw something. */
        boolean learnable() {
            return clean() && visible() > EMPTY * entry;
        }
    }

    record RunInfo(int events, long fired, long entered, long inDomain, double loopSeconds, double wallSeconds) {
        double perShower() {
            return loopSeconds / Math.max(1, events);
        }
    }

    private static final class Canceled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** The whole chain, on this thread. {@code folder} is written; anything already in it of the same name is replaced. */
    public static Result run(Geometry g, CppExport.Options gun, Path folder, Function<String, String> settings, Settings s, Listener l) throws Exception {
        final long t0 = System.nanoTime();
        final CppExport.Options options = gun.cppOnly();
        final FastSimPlanner.Plan plan = FastSimPlanner.plan(g, options);
        if (!plan.runnable()) {
            throw new IllegalStateException(String.join("; ", plan.blockers()));
        }
        l.log(plan.describe().stripTrailing());
        final Job job = new Job(plan, folder.toAbsolutePath().normalize(), settings, s, l, t0);
        try {
            return job.go(FastSimCode.application(g, options, plan));
        } finally {
            final Process p = job.python;
            if (p != null && p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        }
    }

    private static final class Job {
        final FastSimPlanner.Plan plan;
        final Path folder, data;
        final Function<String, String> settings;
        final Settings s;
        final Listener l;
        final long t0;
        final int threads;
        Geant4AppBuilder.Build build;
        List<FastSimPlanner.Cell> cells;
        ShowerBasis basis;
        double[][] latentShift, latentLogScale;
        double[] punch = new double[0], calibration = {1, 0, 0};
        TotalMap totalMap = TotalMap.identity();
        long seed;
        RunInfo lastRun;
        volatile Process python;
        double secondsPerMeV, overhead, trainingSeconds = 120;

        Job(FastSimPlanner.Plan plan, Path folder, Function<String, String> settings, Settings s, Listener l, long t0) {
            this.plan = plan;
            this.folder = folder;
            this.data = folder.resolve("fastsim_data");
            this.settings = settings;
            this.s = s;
            this.l = l;
            this.t0 = t0;
            this.threads = s.threads() > 0 ? s.threads() : Math.max(1, Runtime.getRuntime().availableProcessors());
            this.seed = s.seed();
            this.cells = plan.cells();
        }

        double left() {
            return s.minutes() * 60 - (System.nanoTime() - t0) / 1e9;
        }

        void check() {
            if (l.canceled()) {
                throw new Canceled();
            }
        }

        void stage(String name, double f) {
            l.log("== " + name);
            l.stage(name, f);
            check();
        }

        /** Seconds a run of these showers takes: its start, and each shower in proportion to its energy. */
        double cost(double[] energies, int[] counts) {
            double mev = 0;
            for (int k = 0; k < energies.length; k++) {
                mev += counts[k] * energies[k];
            }
            return overhead + mev * secondsPerMeV;
        }

        Result go(Map<String, String> files) throws Exception {
            Files.createDirectories(data);
            CppExport.write(folder, files);
            stage("Building the application against Geant4", 0.02);
            compile(true);

            stage("Pilot run: what a shower costs and how it fluctuates, the cells that matter", 0.05);
            final List<Shower> pilot = simulate("pilot", "full", PILOT_EVENTS, ranges(plan.emin(), plan.emax(), null), null, null);
            final RunInfo pilotRun = lastRun;
            final double domain = pilotRun.inDomain() / (double) Math.max(1, pilotRun.events());
            if (domain < 0.5) {
                throw new IllegalStateException(String.format(Locale.ROOT, "only %.0f%% of the primaries reach %s inside the domain (%d entered of %d): aim the gun at its face",
                    100 * domain, plan.envelope(), pilotRun.entered(), pilotRun.events()));
            }
            prune(pilot);
            double mev = 0;
            for (Shower x : pilot) {
                mev += x.gun();
            }
            secondsPerMeV = pilotRun.loopSeconds() / Math.max(1e-9, mev);
            overhead = Math.max(5, pilotRun.wallSeconds() - pilotRun.loopSeconds());
            final List<Shower> seen = pilot.stream().filter(Shower::learnable).toList();
            final double[] variance = FastSimStats.varianceModel(seen.stream().mapToDouble(Shower::entry).toArray(),
                seen.stream().mapToDouble(x -> x.visible() / x.entry()).toArray());

            // the budget, before anything else is run: what is left for training rounds once the data and the verdict are paid
            final double[] energies = validationEnergies();
            final double budget = left();
            final double low = plan.emin() / GUARD, high = plan.emax() * GUARD;
            final double meanTrain = (high - low) / Math.log(high / low);
            final int showers = (int) Math.min(plan.targetShowers(), 0.30 * budget / (secondsPerMeV * meanTrain));
            final int[] dev = FastSimStats.allocate(energies, variance, secondsPerMeV, 0.12 * budget, MIN_PER_POINT);
            final int[] fin = FastSimStats.allocate(energies, variance, secondsPerMeV, 0.12 * budget, MIN_PER_POINT);
            final double reserve = cost(energies, fin) * (1 + 0.5) + 2 * overhead + 90;   // the final runs, full and fast, and a build
            if (showers < MIN_TRAINING || left() < cost(energies, dev) + reserve + trainingSeconds) {
                final double need = (MIN_TRAINING * secondsPerMeV * meanTrain + 2 * cost(energies, dev) + reserve + trainingSeconds + 300) / 60;
                throw new IllegalStateException(String.format(Locale.ROOT, "%.0f minutes are not enough for this detector here: a shower of %.0f MeV takes %.1f ms;"
                    + " give it at least %.0f minutes", s.minutes(), meanTrain, 1000 * secondsPerMeV * meanTrain, Math.ceil(need)));
            }
            l.log(String.format(Locale.ROOT, "%.1f ms a shower of %.0f MeV here. Spending the time: %d showers to learn from; at %s MeV, %s development"
                    + " showers and as many fresh ones for the verdict (more where showers are cheap and noisy).", 1000 * secondsPerMeV * meanTrain, meanTrain,
                showers, join(energies), Arrays.toString(dev)));

            stage("Geant4: the showers to learn from", 0.1);
            final List<Shower> training = new ArrayList<>(simulate("train", "full", showers, ranges(low, high, null), null, null));
            stage("Geant4: development showers, at the energies the network is held to", 0.25);
            final List<Shower> development = new ArrayList<>(simulate("dev", "full", sum(dev), null, energies, dev));

            int epochs = showers >= 16000 ? 40 : 60, hidden = plan.flowHidden(), recalibrations = 0, round = 0;
            boolean retrain = true;
            Path sampler = null, encoder = null;
            List<FastSimStats.Point> points = List.of();
            RunInfo fastRun = null;
            String stop = "the rounds allowed are spent";
            while (round < s.maxRounds()) {
                round++;
                if (retrain) {
                    stage(String.format(Locale.ROOT, "Round %d: the closed-form part of %d showers, then the flow on what is left (%d epochs, %d hidden)",
                        round, training.stream().filter(Shower::learnable).count(), epochs, hidden), 0.3 + 0.08 * round);
                    final long start = System.nanoTime();
                    final Path[] graphs = train(training, epochs, hidden);
                    trainingSeconds = (System.nanoTime() - start) / 1e9;
                    sampler = graphs[0];
                    encoder = graphs[1];
                    calibration = new double[] {1, 0, 0};
                    recalibrations = 0;
                    stage("Round " + round + ": the flow's latent space, measured on showers it never saw", 0.32 + 0.08 * round);
                    recalibrate(encoder, development, energies);
                    totalMap = mapTotal(sampler, development, energies);
                    retrain = false;
                }
                stage("Round " + round + ": the network as C++, in the application", 0.35 + 0.08 * round);
                install(sampler, false);
                compile(false);
                stage("Round " + round + ": the fast simulation against Geant4 on the development showers", 0.38 + 0.08 * round);
                final List<Shower> fast = simulate("fast", "fast", OVERSAMPLE * sum(dev), null, energies, scale(dev, OVERSAMPLE));
                fastRun = lastRun;
                checkFired(fastRun);
                points = compare(development, fast, energies);
                points.forEach(p -> l.log(p.describe()));
                if (points.stream().allMatch(FastSimStats.Point::passed)) {
                    stop = "the development showers pass";
                    break;
                }
                final double roundCost = trainingSeconds + 90 + cost(energies, scale(dev, OVERSAMPLE)) * 0.3;
                if (left() < reserve + roundCost) {
                    stop = "the time left is for the verdict";
                    break;
                }
                final List<FastSimStats.Point> failed = points.stream().filter(p -> p.verdict() == FastSimStats.Verdict.FAIL).toList();
                if (!failed.isEmpty() && failed.stream().allMatch(FastSimStats.Point::onlyResponse) && recalibrations < 2) {
                    calibration = recalibrateResponse(points);
                    recalibrations++;
                    l.log(String.format(Locale.ROOT, "Only the scale is off: calibrated in closed form (%.5f, %.5f, %.5f), no new training.", calibration[0], calibration[1], calibration[2]));
                    continue;
                }
                if (!failed.isEmpty()) {
                    // the shape is wrong where it failed: showers there, and a longer, wider training
                    final int more = (int) Math.min(showers / 2, 0.4 * (left() - reserve - roundCost) / (secondsPerMeV * meanTrain));
                    if (more < 500) {
                        stop = "no time left to learn more";
                        break;
                    }
                    final double[] targets = failed.stream().mapToDouble(FastSimStats.Point::energy).toArray();
                    l.log("Where it fails (" + join(targets) + " MeV): " + more + " more showers there, a longer and wider training.");
                    training.addAll(simulate("train" + round, "full", more, ranges(low, high, targets), null, null));
                    epochs = epochs * 3 / 2;
                    hidden = Math.min(320, hidden * 5 / 4);
                    retrain = true;
                    continue;
                }
                // nothing failed, something is not yet known: more development showers where it is not known
                final int[] more = new int[energies.length];
                for (int k = 0; k < energies.length; k++) {
                    more[k] = points.get(k).verdict() == FastSimStats.Verdict.UNDECIDED ? dev[k] : 0;
                }
                if (left() < reserve + roundCost + cost(energies, more)) {
                    stop = "the precision needed takes more time than is left";
                    break;
                }
                l.log("Nothing fails; not yet known at some energies: " + Arrays.toString(more) + " more development showers there.");
                development.addAll(simulate("dev" + round, "full", sum(more), null, energies, more));
                for (int k = 0; k < energies.length; k++) {
                    dev[k] += more[k];
                }
                recalibrate(encoder, development, energies);   // with every development shower
                totalMap = mapTotal(sampler, development, energies);
            }
            l.log("Rounds over: " + stop + ".");
            if (sampler == null) {
                throw new IllegalStateException("no network was trained");
            }

            // the verdict: fresh showers, used for nothing else, compared once
            stage("Verdict: fresh showers of Geant4, never used for any decision", 0.85);
            install(sampler, false);
            compile(false);
            final List<Shower> holdout = simulate("final", "full", sum(fin), null, energies, fin);
            final RunInfo fullFinal = lastRun;
            final List<Shower> fastFinal = simulate("finalfast", "fast", OVERSAMPLE * sum(fin), null, energies, scale(fin, OVERSAMPLE));
            final RunInfo fastFinalRun = lastRun;
            checkFired(fastFinalRun);
            List<FastSimStats.Point> verdictPoints = compare(holdout, fastFinal, energies, 0.90);
            verdictPoints.forEach(p -> l.log(p.describe()));
            // where the first look could not decide, a second one: the time left spent there, judged at a stricter level
            final int[] more = secondLook(verdictPoints, fin, energies, fastFinalRun.perShower() / Math.max(1e-12, fullFinal.perShower()));
            if (sum(more) > 0) {
                stage("Verdict, second look: fresh showers where it was not yet known, judged at 95% on all of them", 0.92);
                l.log("Second look: " + Arrays.toString(more) + " more fresh showers at " + join(energies) + " MeV.");
                holdout.addAll(simulate("final2", "full", sum(more), null, energies, more));
                fastFinal.addAll(simulate("finalfast2", "fast", OVERSAMPLE * sum(more), null, energies, scale(more, OVERSAMPLE)));
                checkFired(lastRun);
                final List<FastSimStats.Point> second = compare(holdout, fastFinal, energies, 0.95);
                final List<FastSimStats.Point> merged = new ArrayList<>();
                for (int k = 0; k < energies.length; k++) {
                    // a point decided at the first look keeps its verdict
                    merged.add(verdictPoints.get(k).verdict() == FastSimStats.Verdict.UNDECIDED ? second.get(k) : verdictPoints.get(k));
                }
                verdictPoints = merged;
                verdictPoints.forEach(p -> l.log(p.describe()));
            }
            final boolean validated = verdictPoints.stream().allMatch(FastSimStats.Point::passed);
            final boolean failedAny = verdictPoints.stream().anyMatch(p -> p.verdict() == FastSimStats.Verdict.FAIL);
            final String verdict = validated ? "Validated on fresh showers: the fast simulation agrees with Geant4 at every energy, within the margins."
                : failedAny ? "Not validated: on fresh showers it differs from Geant4 (" + reasons(verdictPoints) + ")."
                : "Not validated yet: nothing differs from Geant4 on fresh showers, but the precision needed is not reached (" + reasons(verdictPoints) + "); give it more time.";
            install(sampler, validated);   // the default mode says whether it passed
            compile(false);
            final double full = fullFinal.perShower(), fastShower = fastFinalRun.perShower();
            final double speedup = fastShower > 0 ? full / fastShower : 0;
            final double fired = fastFinalRun.fired() / (double) Math.max(1, fastFinalRun.events());
            final Path report = folder.resolve("fastsim_report.md");
            Files.writeString(report, report(verdict, validated, points, verdictPoints, speedup, full, fastShower, round), StandardCharsets.UTF_8);
            stage("Done", 1);
            l.log(verdict);
            l.log(String.format(Locale.ROOT, "%.2f ms a shower in full simulation, %.3f ms in fast (event loops alone): %.0f times faster; %.1f%% of the primaries replaced. Report: %s",
                full * 1000, fastShower * 1000, speedup, 100 * fired, report));
            return new Result(validated, verdict, plan, verdictPoints, speedup, full, fastShower, fired, round, folder, report);
        }

        void checkFired(RunInfo run) {
            final double fired = run.fired() / (double) Math.max(1, run.inDomain());
            if (run.inDomain() > 0 && fired < 0.95) {
                throw new IllegalStateException(String.format(Locale.ROOT, "the model replaced only %.0f%% of the primaries inside its domain: its region does not reach them", 100 * fired));
            }
        }

        String join(double[] values) {
            final StringBuilder b = new StringBuilder();
            for (double v : values) {
                b.append(b.length() > 0 ? ", " : "").append(String.format(Locale.ROOT, "%.0f", v));
            }
            return b.toString();
        }

        int sum(int[] n) {
            int t = 0;
            for (int v : n) {
                t += v;
            }
            return t;
        }

        int[] scale(int[] n, int f) {
            final int[] out = new int[n.length];
            for (int i = 0; i < n.length; i++) {
                out[i] = n[i] * f;
            }
            return out;
        }

        /** The energies the network is held to: both ends, their middle, and the gun's own. */
        double[] validationEnergies() {
            final double a = plan.emin(), b = plan.emax();
            final TreeSet<Double> e = new TreeSet<>(List.of(a * 1.05, Math.sqrt(a * b), b * 0.95));
            if (plan.energy() > a * 1.05 && plan.energy() < b * 0.95 && e.stream().allMatch(x -> Math.abs(plan.energy() / x - 1) > 0.05)) {
                e.add(plan.energy());
            }
            return e.stream().mapToDouble(Double::doubleValue).toArray();
        }

        /** Log-uniform over [low, high]; with targets, half the showers within 30% of them. */
        String ranges(double low, double high, double[] targets) {
            final StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "%.8g:%.8g:%d", low, high, targets == null ? 1 : targets.length));
            if (targets != null) {
                for (double t : targets) {
                    b.append(String.format(Locale.ROOT, ",%.8g:%.8g:1", Math.max(low, t / 1.3), Math.min(high, t * 1.3)));
                }
            }
            return b.toString();
        }

        // ---- Geant4

        void compile(boolean clean) {
            check();
            final StringBuilder last = new StringBuilder();
            final var b = Geant4AppBuilder.build(folder, clean, settings, line -> {
                last.append(line).append('\n');
                if (last.length() > 6000) {
                    last.delete(0, last.length() - 6000);
                }
            });
            if (!b.ok()) {
                check();
                throw new IllegalStateException(b.message() + "\n" + last);
            }
            build = b;
        }

        /** The application run in a mode, its energies drawn from {@code ranges} or from {@code energies} by {@code weights}; the showers it wrote. */
        List<Shower> simulate(String tag, String mode, int events, String ranges, double[] energies, int[] weights) throws Exception {
            check();
            try (Stream<Path> old = Files.list(data)) {
                for (Path p : old.filter(p -> p.getFileName().toString().startsWith(tag + "_")).toList()) {
                    Files.delete(p);
                }
            }
            final Path macro = folder.resolve("fs_run.mac"), control = folder.resolve("fs_control.txt");
            Files.writeString(macro, "/run/numberOfThreads " + threads + "\n/run/initialize\n/tracking/verbose 0\n/run/beamOn " + events + "\n");
            final StringBuilder c = new StringBuilder("mode " + mode + "\nseed " + (seed++) + "\n");
            final String out = build.inWsl() ? Geant4Locator.toWslPath(data, build.g4(), settings) + "/" + tag + "_" : data.resolve(tag + "_").toString().replace('\\', '/');
            c.append("out ").append(out).append('\n');
            if (energies != null) {
                c.append("energies ");
                for (int i = 0; i < energies.length; i++) {
                    c.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "%.8g", energies[i]));
                }
                c.append("\nweights ");
                for (int i = 0; i < weights.length; i++) {
                    c.append(i > 0 ? "," : "").append(weights[i]);
                }
                c.append('\n');
            } else {
                c.append("ranges ").append(ranges).append('\n');
            }
            Files.writeString(control, c.toString());
            final StringBuilder tail = new StringBuilder();
            final String[] summary = {null};
            final long start = System.nanoTime();
            final int code = Geant4AppBuilder.run(build, build.program(), List.of("fs_run.mac", "fs_control.txt"), settings, line -> {
                if (line.startsWith("SPHEREFS")) {
                    summary[0] = line;
                }
                tail.append(line).append('\n');
                if (tail.length() > 8000) {
                    tail.delete(0, tail.length() - 8000);
                }
            });
            final double wall = (System.nanoTime() - start) / 1e9;
            if (code != 0 || summary[0] == null) {
                check();
                throw new IllegalStateException("the Geant4 run ended with code " + code + (summary[0] == null ? ", without its summary" : "") + ":\n" + tail);
            }
            lastRun = parse(summary[0], wall);
            return read(tag);
        }

        RunInfo parse(String line, double wall) {
            final Map<String, String> m = new HashMap<>();
            for (String w : line.split("\\s+")) {
                final String[] kv = w.split("=", 2);
                if (kv.length == 2) {
                    m.put(kv[0], kv[1]);
                }
            }
            return new RunInfo(Integer.parseInt(m.get("events")), Long.parseLong(m.get("fired")), Long.parseLong(m.get("entered")),
                Long.parseLong(m.get("domain")), Double.parseDouble(m.get("loop")), wall);
        }

        List<Shower> read(String tag) throws IOException {
            final Map<String, Integer> index = new HashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                index.put(plan.detectors().indexOf(cells.get(i).detector()) + ":" + cells.get(i).key(), i);
            }
            final List<Shower> out = new ArrayList<>();
            final TreeSet<String> unknown = new TreeSet<>();
            try (Stream<Path> files = Files.list(data)) {
                for (Path p : files.filter(f -> f.getFileName().toString().startsWith(tag + "_")).sorted().toList()) {
                    try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            final String[] w = line.trim().split(" ");
                            if (w.length < 4) {
                                continue;
                            }
                            final double[] row = new double[cells.size()];
                            for (int i = 4; i < w.length; i++) {
                                final int c = w[i].lastIndexOf(':');
                                final Integer at = index.get(w[i].substring(0, c));
                                if (at != null) {
                                    row[at] += Double.parseDouble(w[i].substring(c + 1));
                                } else {
                                    unknown.add(w[i].substring(0, c));
                                }
                            }
                            out.add(new Shower(Double.parseDouble(w[0]), Double.parseDouble(w[1]), Integer.parseInt(w[2]), Double.parseDouble(w[3]), row));
                        }
                    }
                }
            }
            if (cells == plan.cells() && !unknown.isEmpty()) {
                throw new IllegalStateException("Geant4 read cells the plan does not know: " + unknown + "; the geometry and the numbering disagree");
            }
            return out;
        }

        /** The cells that hold the energy; a shower's few stray deposits elsewhere are not worth a dimension. */
        void prune(List<Shower> pilot) {
            final int n = cells.size();
            final double[] sum = new double[n];
            double total = 0;
            for (Shower x : pilot) {
                for (int i = 0; i < n; i++) {
                    sum[i] += x.cells()[i];
                    total += x.cells()[i];
                }
            }
            if (total <= 0) {
                throw new IllegalStateException("the pilot run deposited nothing in the sensitive volumes");
            }
            final Integer[] order = new Integer[n];
            Arrays.setAll(order, i -> i);
            Arrays.sort(order, (a, b) -> Double.compare(sum[b], sum[a]));
            final boolean[] keep = new boolean[n];
            double held = 0;
            int kept = 0;
            for (int i = 0; i < n && kept < FastSimPlanner.MAX_CELLS && held < KEPT * total; i++) {
                keep[order[i]] = true;
                held += sum[order[i]];
                kept++;
            }
            final List<FastSimPlanner.Cell> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (keep[i]) {
                    out.add(cells.get(i));   // in the order of the detector: the stick breaking follows it
                }
            }
            l.log(String.format(Locale.ROOT, "%d of %d cells hold %.3f%% of the energy.", out.size(), n, 100 * held / total));
            cells = out;
        }

        // ---- the closed-form part, PyTorch, the latent space

        /** The basis fitted on the showers to learn from, the chance of no deposit, and the flow trained on what is left; {sampler, encoder}. */
        Path[] train(List<Shower> all, int epochs, int hidden) throws Exception {
            final List<Shower> learn = new ArrayList<>(), domain = new ArrayList<>();
            for (Shower x : all) {
                if (x.clean()) {
                    domain.add(x);
                    if (x.learnable()) {
                        learn.add(x);
                    }
                }
            }
            final SplittableRandom random = new SplittableRandom(seed++);
            final double[] energies = new double[learn.size()];
            final double[][] raw = new double[learn.size()][];
            for (int i = 0; i < raw.length; i++) {
                energies[i] = learn.get(i).entry();
                raw[i] = ShowerBasis.raw(energies[i], learn.get(i).cells(), random);
            }
            basis = ShowerBasis.fit(energies, raw);
            punch = punchThrough(domain);
            final int dim = cells.size();
            final Path file = data.resolve("features.bin");
            final ByteBuffer buffer = ByteBuffer.allocate(Math.multiplyExact(8 * raw.length, dim + 1)).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < raw.length; i++) {
                for (double w : basis.whiten(raw[i], energies[i])) {
                    buffer.putDouble(w);
                }
                buffer.putDouble(Math.log(energies[i]));
            }
            buffer.flip();
            try (FileChannel c = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                while (buffer.hasRemaining()) {
                    c.write(buffer);
                }
            }
            final MlLocator.Interpreter interpreter = MlLocator.best(settings, "torch");
            if (interpreter == null || interpreter.wsl()) {
                throw new IllegalStateException("no Python holding PyTorch on this side (PYTHON_EXEC, ML_PYTHON in settings.conf)");
            }
            final Path models = data.resolve("graph");
            Files.createDirectories(models);
            final String name = "fastsim_sampler";
            final Path job = data.resolve("job.json");
            Files.writeString(job, String.format(Locale.ROOT, "{\"data\": \"%s\", \"dim\": %d, \"context\": 1, \"layers\": %d, \"bins\": %d, \"hidden\": %d, \"epochs\": %d,"
                    + " \"batch\": 512, \"lr\": 0.002, \"weight_decay\": 0.01, \"seed\": %d, \"name\": \"%s\", \"folder\": \"%s\", \"threads\": %d}",
                json(file), dim, plan.flowLayers(), plan.flowBins(), hidden, epochs, seed % 100000, name, json(models), threads));
            final List<String> cmd = new ArrayList<>(interpreter.command());
            cmd.add(Bridge.libraries().resolve("sphere_fastsim.py").toString());
            cmd.add(job.toString());
            final ProcessBuilder pb = new ProcessBuilder(cmd).directory(data.toFile()).redirectErrorStream(true);
            Bridge.environment(pb.environment());
            final Process p = pb.start();
            python = p;
            p.getOutputStream().close();
            final StringBuilder tail = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("  epoch") || line.startsWith("epoch") || line.startsWith("training") || line.startsWith("overfitting")) {
                        l.log("  " + line.strip());
                    }
                    tail.append(line).append('\n');
                    if (tail.length() > 6000) {
                        tail.delete(0, tail.length() - 6000);
                    }
                    if (l.canceled()) {
                        p.descendants().forEach(ProcessHandle::destroyForcibly);
                        p.destroyForcibly();
                    }
                }
            }
            final int code = p.waitFor();
            python = null;
            check();
            if (code != 0) {
                throw new IllegalStateException("the training failed:\n" + tail);
            }
            l.log(String.format(Locale.ROOT, "  learned from %d showers%s.", learn.size(), punch.length > 0 ? String.format(Locale.ROOT,
                ", %d of %d crossing without a deposit", domain.size() - learn.size(), domain.size()) : ""));
            return new Path[] {models.resolve(name + ".sng.spx"), models.resolve(name + "_encoder.sng.spx")};
        }

        String json(Path p) {
            return p.toString().replace('\\', '/').replace("\"", "\\\"");
        }

        /** The chance that a primary entering inside the domain leaves no deposit, on an even grid of t: none when it never happened. */
        double[] punchThrough(List<Shower> domain) {
            final int bins = 6;
            final int[] all = new int[bins], empty = new int[bins];
            int zeros = 0;
            for (Shower x : domain) {
                final int b = Math.min(bins - 1, (int) ((basis.t(x.entry()) + 1) / 2 * bins));
                all[b]++;
                if (!x.learnable()) {
                    empty[b]++;
                    zeros++;
                }
            }
            if (zeros == 0) {
                return new double[0];
            }
            final double[] grid = new double[bins];
            for (int b = 0; b < bins; b++) {
                grid[b] = all[b] > 0 ? (double) empty[b] / all[b] : (double) zeros / domain.size();
            }
            return grid;
        }

        /**
         * The flow's latent space on showers it never saw. Invertible, it maps a real shower to its latent point, N(0, 1)
         * in every coordinate if it were exact. The mean and the log of the width found there at each energy -- shrunk
         * towards zero by as much as their noise explains (James-Stein), so that noise is not learned -- become, as
         * parabolas in t, the base distribution the C++ draws from.
         */
        void recalibrate(Path encoder, List<Shower> development, double[] energies) throws IOException {
            final NeuralGraph g = NeuralGraph.load(encoder);
            final int d = cells.size(), k = energies.length;
            final SplittableRandom random = new SplittableRandom(seed++);
            final double[] t = new double[k], weight = new double[k];
            final double[][] shift = new double[k][d], logScale = new double[k][d];
            int groups = 0;
            for (int e = 0; e < k; e++) {
                final double energy = energies[e];
                final List<Shower> group = development.stream().filter(x -> x.learnable() && Math.abs(x.gun() / energy - 1) < 1e-6).toList();
                final int n = group.size();
                if (n < 30) {
                    continue;
                }
                final double[] w = new double[n * d], c = new double[n];
                double entry = 0;
                for (int i = 0; i < n; i++) {
                    final Shower x = group.get(i);
                    System.arraycopy(basis.whiten(ShowerBasis.raw(x.entry(), x.cells(), random), x.entry()), 0, w, i * d, d);
                    c[i] = Math.log(x.entry());
                    entry += x.entry();
                }
                final double[] z = g.run(List.of(new NeuralGraph.Tensor(w, n, d), new NeuralGraph.Tensor(c, n, 1))).get(0).data;
                final double[] m = new double[d], v = new double[d];
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < d; j++) {
                        m[j] += z[i * d + j];
                    }
                }
                for (int j = 0; j < d; j++) {
                    m[j] /= n;
                }
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < d; j++) {
                        final double r = z[i * d + j] - m[j];
                        v[j] += r * r;
                    }
                }
                double t1 = 0, t2 = 0, tg = 0;
                final double[] gj = new double[d];
                for (int j = 0; j < d; j++) {
                    v[j] /= Math.max(1, n - 1);
                    gj[j] = 0.5 * Math.log(Math.max(1e-12, v[j]));
                    t1 += n * m[j] * m[j];
                    t2 += (n - 1) * (v[j] - 1) * (n - 1) * (v[j] - 1) / (2.0 * (n - 1));
                    tg += 2.0 * n * gj[j] * gj[j];
                }
                final double fm = Math.max(0, 1 - Math.max(d - 2, 1) / Math.max(t1, 1e-12)), fg = Math.max(0, 1 - Math.max(d - 2, 1) / Math.max(tg, 1e-12));
                l.log(String.format(Locale.ROOT, "  latent space at %.0f MeV, %d showers: mean chi2 %.1f for %d (p %.3g), width chi2 %.1f (p %.3g); kept %.0f%% and %.0f%% of them",
                    energy, n, t1, d, FastSimStats.chi2Above(t1, d), t2, FastSimStats.chi2Above(t2, d), 100 * fm, 100 * fg));
                for (int j = 0; j < d; j++) {
                    shift[groups][j] = fm * m[j];
                    logScale[groups][j] = fg * gj[j];
                }
                t[groups] = basis.t(entry / n);
                weight[groups] = n;
                groups++;
            }
            latentShift = new double[d][];
            latentLogScale = new double[d][];
            final double[] ys = new double[groups], yg = new double[groups];
            final double[] tt = Arrays.copyOf(t, groups), ww = Arrays.copyOf(weight, groups);
            for (int j = 0; j < d; j++) {
                for (int e = 0; e < groups; e++) {
                    ys[e] = shift[e][j];
                    yg[e] = logScale[e][j];
                }
                latentShift[j] = groups == 0 ? new double[3] : FastSimStats.polyfit(tt, ys, ww, 2);
                latentLogScale[j] = groups == 0 ? new double[3] : FastSimStats.polyfit(tt, yg, ww, 2);
            }
        }

        /**
         * The total energy's distribution carried to Geant4's: at each energy measured, the network's ln(visible / E) from
         * draws of the C++'s own recipe (the recalibrated latent space, the closed-form part) against the development showers'.
         */
        TotalMap mapTotal(Path sampler, List<Shower> development, double[] energies) throws IOException {
            final NeuralGraph g = NeuralGraph.load(sampler);
            final int d = cells.size(), draws = 20000;
            final SplittableRandom random = new SplittableRandom(seed++);
            final List<Double> ts = new ArrayList<>();
            final List<double[]> network = new ArrayList<>(), geant4 = new ArrayList<>();
            for (double energy : energies) {
                final List<Shower> group = development.stream().filter(x -> x.learnable() && Math.abs(x.gun() / energy - 1) < 1e-6).toList();
                if (group.size() < 100) {
                    continue;
                }
                double entry = 0;
                final double[] real = new double[group.size()];
                for (int i = 0; i < real.length; i++) {
                    entry += group.get(i).entry();
                    real[i] = Math.log(group.get(i).visible() / group.get(i).entry());
                }
                entry /= real.length;
                Arrays.sort(real);
                final double t = basis.t(entry), u = Math.log(entry);
                final double[] z = new double[draws * d], c = new double[draws];
                for (int i = 0; i < draws; i++) {
                    c[i] = u;
                    for (int j = 0; j < d; j++) {
                        z[i * d + j] = FastSimStats.poly(latentShift[j], t) + Math.exp(FastSimStats.poly(latentLogScale[j], t)) * random.nextGaussian();
                    }
                }
                final double[] w = g.run(List.of(new NeuralGraph.Tensor(z, draws, d), new NeuralGraph.Tensor(c, draws, 1))).get(0).data;
                final double[] f0 = new double[draws];
                for (int i = 0; i < draws; i++) {
                    f0[i] = FastSimStats.poly(basis.mean[0], t) + Math.exp(0.5 * FastSimStats.poly(basis.logVariance[0], t)) * w[i * d];
                }
                Arrays.sort(f0);
                ts.add(t);
                network.add(f0);
                geant4.add(real);
                l.log(String.format(Locale.ROOT, "  total energy at %.0f MeV: the network's median %.4f and width %.4f of ln(visible/E), Geant4's %.4f and %.4f",
                    energy, f0[draws / 2], (TotalMap.quantile(f0, 0.84) - TotalMap.quantile(f0, 0.16)) / 2, TotalMap.quantile(real, 0.5),
                    (TotalMap.quantile(real, 0.84) - TotalMap.quantile(real, 0.16)) / 2));
            }
            return ts.isEmpty() ? TotalMap.identity()
                : TotalMap.fit(ts.stream().mapToDouble(Double::doubleValue).toArray(), network.toArray(double[][]::new), geant4.toArray(double[][]::new));
        }

        /** c(t) / (1 + delta) where the response is off, as a parabola in t: a calibration in closed form. */
        double[] recalibrateResponse(List<FastSimStats.Point> points) {
            final double[] t = new double[points.size()], y = new double[points.size()], w = new double[points.size()];
            for (int i = 0; i < t.length; i++) {
                final FastSimStats.Point p = points.get(i);
                t[i] = basis.t(p.energy());
                y[i] = FastSimStats.poly(calibration, t[i]) / (1 + p.responseDelta());
                final double sigma = Math.max(1e-4, (p.response().high() - p.response().low()) / 3.29);
                w[i] = 1 / (sigma * sigma);
            }
            return FastSimStats.polyfit(t, y, w, 2);
        }

        void install(Path sampler, boolean validated) throws Exception {
            final GraphCpp.Transcript t = GraphCpp.transcribe(NeuralGraph.load(sampler), "SphereFastNet", 1);
            Files.writeString(folder.resolve("include/SphereFastNet.hh"), t.text(), StandardCharsets.UTF_8);
            Files.writeString(folder.resolve("include/SphereFastTable.hh"), FastSimCode.table(plan, cells, "The network: " + t.report(), basis,
                latentShift, latentLogScale, punch, calibration, totalMap, validated), StandardCharsets.UTF_8);
        }

        // ---- the comparisons

        List<FastSimStats.Point> compare(List<Shower> full, List<Shower> fast, double[] energies) {
            return compare(full, fast, energies, 0.90);
        }

        List<FastSimStats.Point> compare(List<Shower> full, List<Shower> fast, double[] energies, double level) {
            final List<FastSimStats.Point> out = new ArrayList<>();
            for (double e : energies) {
                out.add(FastSimStats.compare(e, rows(full, e), rows(fast, e), seed++, level));
            }
            return out;
        }

        /**
         * Fresh showers to add where the first look could not decide: at each such energy, as many as would bring its
         * widest undecided interval inside its margin at the stricter level (the interval narrowing as 1/sqrt(n)), at
         * least half as many again and at most 16 times as many, all of it within the time left. None if anything failed.
         */
        int[] secondLook(List<FastSimStats.Point> points, int[] counts, double[] energies, double fastOverFull) {
            final int[] more = new int[energies.length];
            if (points.stream().anyMatch(p -> p.verdict() == FastSimStats.Verdict.FAIL)) {
                return more;
            }
            final double stricter = Math.pow(1.96 / 1.645, 2);
            double cost = 0;
            for (int k = 0; k < energies.length; k++) {
                final FastSimStats.Point p = points.get(k);
                if (p.verdict() != FastSimStats.Verdict.UNDECIDED) {
                    continue;
                }
                double factor = 1.5;
                for (String what : p.undecided()) {
                    final FastSimStats.Interval i = switch (what) {
                        case "response" -> p.response();
                        case "width" -> new FastSimStats.Interval(p.width().value() - 1, p.width().low() - 1, p.width().high() - 1);
                        default -> p.profile();
                    };
                    final double margin = switch (what) {
                        case "response" -> p.responseMargin();
                        case "width" -> FastSimStats.WIDTH_MARGIN;
                        default -> FastSimStats.PROFILE_MARGIN * FastSimStats.PROFILE_MARGIN;
                    };
                    final double half = (i.high() - i.low()) / 2;
                    final double room = what.equals("profile") ? margin - i.value() : margin - Math.abs(i.value());
                    factor = Math.max(factor, room > 0 ? stricter * (half / room) * (half / room) : 16);
                }
                more[k] = (int) Math.round(counts[k] * (Math.min(16, factor) - 1));
                cost += more[k] * energies[k] * secondsPerMeV * (1 + OVERSAMPLE * fastOverFull);
            }
            final double time = left() - 3 * overhead - 120;   // the two runs' starts, and the last build
            if (cost > time) {
                final double f = Math.max(0, time / cost);
                for (int k = 0; k < more.length; k++) {
                    more[k] = (int) (more[k] * f);
                }
            }
            l.log(String.format(Locale.ROOT, "  %.0f s left for a second look.", Math.max(0, time)));
            return more;
        }

        double[][] rows(List<Shower> showers, double energy) {
            return showers.stream().filter(x -> Math.abs(x.gun() / energy - 1) < 1e-6).map(Shower::cells).toArray(double[][]::new);
        }

        String reasons(List<FastSimStats.Point> points) {
            final List<String> out = new ArrayList<>();
            for (FastSimStats.Point p : points) {
                if (!p.passed()) {
                    out.add(String.format(Locale.ROOT, "%.0f MeV: %s", p.energy(), String.join(", ", p.failing().isEmpty() ? p.undecided() : p.failing())));
                }
            }
            return String.join("; ", out);
        }

        String report(String verdict, boolean validated, List<FastSimStats.Point> development, List<FastSimStats.Point> fresh, double speedup, double full, double fast, int rounds) {
            final StringBuilder b = new StringBuilder("# Learned fast simulation\n\n").append(verdict).append("\n\n");
            b.append(plan.describe().replace(System.lineSeparator(), "\n")).append('\n');
            b.append(String.format(Locale.ROOT, "%d round(s). Full simulation %.3f ms a shower, fast %.4f ms (event loops alone): %.0f times faster.%n%n", rounds, full * 1000, fast * 1000, speedup));
            b.append("The application's default mode is `").append(validated ? "fast" : "full").append("` (`include/SphereFastTable.hh`); `mode fast` or `mode full`")
                .append(" in a control file given as the second argument chooses.\n\n");
            table(b, "Verdict, on fresh showers never used for any decision", fresh);
            table(b, "Last development comparison (the showers the decisions were taken on)", development);
            b.append("""
                Each test is a 90% interval from a bootstrap over the showers of both samples: a test passes when the interval is inside its
                margin, fails when it is outside, and is not yet known otherwise. Where the first look on fresh showers could not decide, the
                time left buys more fresh showers there, and those energies are judged again on all of them at 95%; an energy decided at the
                first look keeps its verdict. Margins, fixed in advance: response within the larger of 1%
                and 5% of the resolution; width within 10%; the mean profile within 2% of the shower, its distance debiased so that a perfect
                model scores 0 on average; the total energy and the depth not separated by a two-sample KS test at 0.1% (plus 0.02).
                """);
            return b.toString();
        }

        void table(StringBuilder b, String title, List<FastSimStats.Point> points) {
            b.append("## ").append(title).append("\n\n");
            b.append("| Energy (MeV) | Showers full / fast | Response fast/full - 1 | Width ratio | Profile distance (raw) | KS total / depth (limit) | Result |\n");
            b.append("|---|---|---|---|---|---|---|\n");
            for (FastSimStats.Point p : points) {
                b.append(String.format(Locale.ROOT, "| %.1f | %d / %d | %+.2f%% [%+.2f, %+.2f] | %.3f [%.3f, %.3f] | %.2f%% (%.2f%%) | %.3f / %.3f (%.3f) | %s%s |%n", p.energy(),
                    p.nFull(), p.nFast(), 100 * p.responseDelta(), 100 * p.response().low(), 100 * p.response().high(), p.width().value(), p.width().low(), p.width().high(),
                    100 * p.profileDistance(), 100 * p.profileRaw(), p.ksTotal(), p.ksDepth(), p.ksLimit(), p.verdict(),
                    p.failing().isEmpty() && p.undecided().isEmpty() ? "" : ": " + String.join(", ", p.failing().isEmpty() ? p.undecided() : p.failing())));
            }
            b.append('\n');
        }
    }
}

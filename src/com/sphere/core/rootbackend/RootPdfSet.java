package com.sphere.core.rootbackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A whole parton distribution set, with the questions that are usually asked of
 * one already answered.
 *
 * A set is a directory: an info file saying how many members there are and what
 * they mean, and one grid file per member. Member zero is the best fit; the
 * others carry the uncertainty, either as Hessian eigenvector pairs or as Monte
 * Carlo replicas, and the two are combined by different formulas. Getting that
 * wrong is the most common mistake made with these files, so the set reads the
 * error type from the info file and picks the formula itself.
 *
 * Three things here are not in LHAPDF, because they are what everyone writes
 * for themselves after installing it. The sum rules are checked when the set is
 * opened, so a truncated download or a grid whose range is too short to carry
 * the momentum is caught before it has produced a cross section. The parton
 * luminosity is computed directly, which is what a search actually needs from a
 * PDF and what LHAPDF leaves to the caller. And two sets can be compared point
 * by point, band included, which is how the choice between them is made.
 */
public final class RootPdfSet {

    /** How the members after the first are to be combined. */
    public enum Errors { HESSIAN, SYMMETRIC_HESSIAN, REPLICAS, NONE }

    /** A value and the band around it. */
    public record Band(double central, double low, double high) {
        public double spread() { return high - low; }
        public double relative() {
            return central == 0.0 ? 0.0 : (high - low) / (2.0 * Math.abs(central));
        }
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%.6g  [%.6g, %.6g]", central, low, high);
        }
    }

    /** What a sum rule came out at, and whether that is acceptable. */
    public record Rule(String name, double value, double expected, double tolerance) {
        public boolean holds() { return Math.abs(value - expected) <= tolerance; }
        public String summary() {
            return String.format(Locale.ROOT, "%-22s %10.6f   expected %6.3f   %s",
                name, value, expected, holds() ? "ok" : "OFF by "
                + String.format(Locale.ROOT, "%.4f", value - expected));
        }
    }

    /** The two initial partons of a luminosity. */
    public record Channel(int first, int second) {
        public boolean same() { return first == second; }
    }

    private static final int GAUSS_POINTS = 8;

    // Gauss-Legendre on [-1, 1]: eight points integrate a degree fifteen
    // polynomial exactly, which is well past the cubic the grid interpolates.
    private static final double[] GAUSS_X = {
        -0.9602898564975363, -0.7966664774136267, -0.5255324099163290, -0.1834346424956498,
         0.1834346424956498,  0.5255324099163290,  0.7966664774136267,  0.9602898564975363};
    private static final double[] GAUSS_W = {
         0.1012285362903763,  0.2223810344533745,  0.3137066458778873,  0.3626837833783620,
         0.3626837833783620,  0.3137066458778873,  0.2223810344533745,  0.1012285362903763};

    private final Path folder;
    private final String name;
    private final Map<String, String> info = new LinkedHashMap<>();
    private final List<RootPdfGrid> members = new ArrayList<>();
    private Errors errors = Errors.NONE;
    private RootPdfAlphaS coupling;
    private boolean couplingRead = false;
    private double confidence = 68.268949;

    private RootPdfSet(Path folder, String name) {
        this.folder = folder;
        this.name = name;
    }

    /* ------------------------------------------------------------------ */
    /* Opening                                                             */
    /* ------------------------------------------------------------------ */

    /** Opens the central member only, which is all most work needs. */
    public static RootPdfSet central(Path folder) throws IOException {
        return open(folder, 1, RootPdfGrid.Accuracy.LHAPDF);
    }

    /** Opens every member, which is what an uncertainty needs. */
    public static RootPdfSet open(Path folder) throws IOException {
        return open(folder, Integer.MAX_VALUE, RootPdfGrid.Accuracy.LHAPDF);
    }

    /**
     * Opens a set.
     *
     * The info file is optional: a directory holding only member files is still
     * a set, it simply has no uncertainty. What is not optional is that member
     * zero exists, since every other member is only meaningful beside it.
     */
    public static RootPdfSet open(Path folder, int howMany, RootPdfGrid.Accuracy accuracy)
            throws IOException {
        return open(folder, howMany, accuracy,
                    RootPdfGrid.Interpolation.LOG_BICUBIC,
                    RootPdfGrid.Extrapolation.CONTINUATION);
    }

    /** The same, with the interpolation and the extrapolation named. */
    public static RootPdfSet open(Path folder, int howMany, RootPdfGrid.Accuracy accuracy,
                                  RootPdfGrid.Interpolation interpolation,
                                  RootPdfGrid.Extrapolation extrapolation)
            throws IOException {
        if (folder == null || !Files.isDirectory(folder)) {
            throw new IOException("No such PDF set directory: " + folder);
        }
        final String name = folder.getFileName().toString();
        RootPdfSet set = new RootPdfSet(folder, name);

        Path infoFile = folder.resolve(name + ".info");
        if (Files.isRegularFile(infoFile)) {
            for (String line : Files.readAllLines(infoFile, StandardCharsets.UTF_8)) {
                final String bare = line.trim();
                if (bare.isEmpty() || bare.startsWith("#") || bare.equals("---")) {
                    continue;
                }
                final int colon = bare.indexOf(':');
                if (colon > 0) {
                    set.info.put(bare.substring(0, colon).trim(), bare.substring(colon + 1).trim());
                }
            }
        }
        set.errors = readErrors(set.info.get("ErrorType"));
        try {
            set.confidence = Double.parseDouble(set.info.getOrDefault("ErrorConfLevel", "68.268949"));
        } catch (NumberFormatException notANumber) {
            set.confidence = 68.268949;
        }

        for (int m = 0; m < howMany; m++) {
            Path member = folder.resolve(String.format(Locale.ROOT, "%s_%04d.dat", name, m));
            if (!Files.isRegularFile(member)) {
                break;
            }
            set.members.add(RootPdfGrid.read(member, accuracy, interpolation, extrapolation));
        }
        if (set.members.isEmpty()) {
            throw new IOException("No member files in " + folder
                + " (expected " + name + "_0000.dat).");
        }
        return set;
    }

    private static Errors readErrors(String said) {
        if (said == null) {
            return Errors.NONE;
        }
        final String bare = said.trim().toLowerCase(Locale.ROOT);
        if (bare.startsWith("symmhessian")) {
            return Errors.SYMMETRIC_HESSIAN;
        }
        if (bare.startsWith("hessian")) {
            return Errors.HESSIAN;
        }
        if (bare.startsWith("replicas") || bare.startsWith("mc")) {
            return Errors.REPLICAS;
        }
        return Errors.NONE;
    }

    /* ------------------------------------------------------------------ */
    /* Values                                                              */
    /* ------------------------------------------------------------------ */

    /** The central member's value. */
    public double xfxQ2(int pid, double x, double q2) {
        return members.get(0).xfxQ2(pid, x, q2);
    }

    public double xfxQ(int pid, double x, double q) {
        return xfxQ2(pid, x, q * q);
    }

    /**
     * The value with the uncertainty around it.
     *
     * Hessian members come in pairs, one for each eigenvector displaced up and
     * down, and the asymmetric form keeps only the displacement that moved the
     * observable in the direction being measured. Replicas are a sample, so the
     * band is the standard deviation about their own mean rather than about
     * member zero. Combining a replica set with the Hessian formula, or the
     * reverse, gives an answer that looks reasonable and is wrong, which is why
     * the choice is made here from the info file and not by the caller.
     */
    public Band band(int pid, double x, double q2) {
        final double central = members.get(0).xfxQ2(pid, x, q2);
        if (members.size() < 2 || errors == Errors.NONE) {
            return new Band(central, central, central);
        }
        double[] values = new double[members.size()];
        for (int m = 0; m < members.size(); m++) {
            values[m] = members.get(m).xfxQ2(pid, x, q2);
        }
        return combine(values);
    }

    /** The band around any quantity computed from each member in turn. */
    public Band band(java.util.function.ToDoubleFunction<RootPdfGrid> of) {
        double[] values = new double[members.size()];
        for (int m = 0; m < members.size(); m++) {
            values[m] = of.applyAsDouble(members.get(m));
        }
        if (values.length < 2 || errors == Errors.NONE) {
            return new Band(values[0], values[0], values[0]);
        }
        return combine(values);
    }

    private Band combine(double[] values) {
        final double central = values[0];
        switch (errors) {
            case REPLICAS -> {
                double mean = 0.0;
                for (int m = 1; m < values.length; m++) {
                    mean += values[m];
                }
                mean /= (values.length - 1);
                double sum = 0.0;
                for (int m = 1; m < values.length; m++) {
                    final double d = values[m] - mean;
                    sum += d * d;
                }
                final double sigma = Math.sqrt(sum / (values.length - 2));
                return new Band(central, mean - sigma, mean + sigma);
            }
            case SYMMETRIC_HESSIAN -> {
                double sum = 0.0;
                for (int m = 1; m < values.length; m++) {
                    final double d = values[m] - central;
                    sum += d * d;
                }
                final double sigma = Math.sqrt(sum);
                return new Band(central, central - sigma, central + sigma);
            }
            case HESSIAN -> {
                double up = 0.0;
                double down = 0.0;
                for (int m = 1; m + 1 < values.length; m += 2) {
                    final double plus = values[m] - central;
                    final double minus = values[m + 1] - central;
                    final double rise = Math.max(Math.max(plus, minus), 0.0);
                    final double fall = Math.max(Math.max(-plus, -minus), 0.0);
                    up += rise * rise;
                    down += fall * fall;
                }
                return new Band(central, central - Math.sqrt(down), central + Math.sqrt(up));
            }
            default -> {
                return new Band(central, central, central);
            }
        }
    }

    /**
     * The strong coupling this set was fitted with.
     *
     * Null when the info file says nothing about it, which is normal for a
     * grid written by hand. A cross section needs the set's own coupling and
     * not another, so this is read from the same file as the grid rather than
     * left to the caller to supply.
     */
    public RootPdfAlphaS alphaS() {
        if (!couplingRead) {
            couplingRead = true;
            coupling = RootPdfAlphaS.fromInfo(info);
        }
        return coupling;
    }

    /** The coupling at a scale, or NaN when the set does not define one. */
    public double alphasQ(double q) {
        final RootPdfAlphaS as = alphaS();
        return as == null ? Double.NaN : as.alphasQ2(q * q);
    }

    /* ------------------------------------------------------------------ */
    /* The sum rules                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * What the set integrates to, against what it must.
     *
     * The momentum carried by all partons is one, and the valence content is
     * two up quarks and one down. These hold by construction in the fit, so a
     * set that fails them has been damaged: a truncated file, a member that did
     * not finish downloading, or a grid whose small-x edge is too high to hold
     * the momentum that lives below it. None of those announce themselves, and
     * all of them produce a cross section that is quietly wrong by percent.
     *
     * The tolerance is loose on purpose. The integral runs from the grid's own
     * smallest x, not from zero, so a little momentum is always missing, and
     * the interpolation adds its own error. A percent is comfortably more than
     * either and far less than real damage.
     */
    public List<Rule> sumRules(double q2) {
        List<Rule> rules = new ArrayList<>();
        final RootPdfGrid one = members.get(0);

        double momentum = 0.0;
        for (int pid : RootPdfGrid.FLAVORS) {
            if (pid == 22 || !one.carries(pid)) {
                continue;
            }
            momentum += integrate(one, pid, q2, true);
        }
        rules.add(new Rule("momentum", momentum, 1.0, 0.01));

        rules.add(new Rule("up valence", valence(one, 2, q2), 2.0, 0.02));
        rules.add(new Rule("down valence", valence(one, 1, q2), 1.0, 0.02));
        if (one.carries(3) && one.carries(-3)) {
            rules.add(new Rule("strange valence", valence(one, 3, q2), 0.0, 0.02));
        }
        return rules;
    }

    private double valence(RootPdfGrid grid, int pid, double q2) {
        return integrate(grid, pid, q2, false) - integrate(grid, -pid, q2, false);
    }

    /**
     * One flavor integrated over x.
     *
     * The grid holds xf, so the momentum integral is that value integrated in x
     * and the number integral is the same value integrated in log x. Both are
     * done interval by interval between the grid's own knots, where the
     * interpolant is a single cubic in log x and eight Gauss points are exact
     * for it several times over.
     */
    private double integrate(RootPdfGrid grid, int pid, double q2, boolean momentum) {
        if (!grid.carries(pid)) {
            return 0.0;
        }
        final double[] knots = grid.xKnotValues();
        double total = 0.0;
        for (int i = 0; i + 1 < knots.length; i++) {
            final double a = Math.log(knots[i]);
            final double b = Math.log(knots[i + 1]);
            if (b - a < 1e-12) {
                continue;
            }
            final double half = 0.5 * (b - a);
            final double mid = 0.5 * (b + a);
            for (int g = 0; g < GAUSS_POINTS; g++) {
                final double u = mid + half * GAUSS_X[g];
                final double x = Math.exp(u);
                final double value = grid.xfxQ2(pid, x, q2);
                total += GAUSS_W[g] * half * (momentum ? value * x : value);
            }
        }
        return total;
    }

    /* ------------------------------------------------------------------ */
    /* Parton luminosity                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * The differential parton luminosity, per unit of tau.
     *
     * Everything produced at a hadron collider is produced by two partons, and
     * how many of those collisions are available at a given mass is what the
     * luminosity says. It is the quantity that decides whether a search is
     * worth doing before any matrix element is written, and LHAPDF does not
     * compute it: every group writes this integral again.
     *
     * The grid holds xf rather than f, which cancels one factor of x out of the
     * integrand and leaves an overall 1/tau. Integrating in log x rather than x
     * is what keeps the small-x end, where the gluon rises steeply, from
     * swallowing the answer.
     */
    public double luminosity(Channel channel, double tau, double q2) {
        if (tau <= 0.0 || tau >= 1.0) {
            return 0.0;
        }
        final RootPdfGrid one = members.get(0);
        final double lowest = Math.log(tau);
        final int slices = 200;
        final double step = -lowest / slices;
        double total = 0.0;

        for (int s = 0; s < slices; s++) {
            final double a = lowest + s * step;
            final double half = 0.5 * step;
            final double mid = a + half;
            for (int g = 0; g < GAUSS_POINTS; g++) {
                final double u = mid + half * GAUSS_X[g];
                final double x = Math.exp(u);
                final double other = tau / x;
                if (other >= 1.0) {
                    continue;
                }
                final double first = one.xfxQ2(channel.first(), x, q2)
                                   * one.xfxQ2(channel.second(), other, q2);
                double integrand = first;
                if (!channel.same()) {
                    integrand += one.xfxQ2(channel.second(), x, q2)
                               * one.xfxQ2(channel.first(), other, q2);
                }
                total += GAUSS_W[g] * half * integrand;
            }
        }
        return total / (tau * tau);
    }

    /** The luminosity as a function of the mass produced, at a given collider energy. */
    public double luminosityAtMass(Channel channel, double mass, double collider, double q2) {
        final double tau = (mass * mass) / (collider * collider);
        // dL/dM = (2M/s) dL/dtau.
        return luminosity(channel, tau, q2) * 2.0 * mass / (collider * collider);
    }

    /** Gluon fusion, the channel most of the Higgs comes through. */
    public static Channel gluonGluon() { return new Channel(21, 21); }

    /** A quark against its own antiquark, summed over the light flavors. */
    public double quarkAntiquark(double tau, double q2) {
        double total = 0.0;
        for (int pid : new int[]{1, 2, 3, 4, 5}) {
            if (members.get(0).carries(pid) && members.get(0).carries(-pid)) {
                total += luminosity(new Channel(pid, -pid), tau, q2);
            }
        }
        return total;
    }

    /* ------------------------------------------------------------------ */
    /* Comparing two sets                                                  */
    /* ------------------------------------------------------------------ */

    /** One flavor of one set against another, at one point. */
    public record Difference(int pid, double x, double q2,
                             double mine, double theirs, double ratio,
                             double myBand, double theirBand) {

        /**
         * True when the two disagree by more than their own uncertainties allow.
         *
         * Two sets differing by less than their bands is the normal case and
         * says nothing. Differing by more is the interesting one: it means the
         * choice of set matters for whatever is being computed, and that the
         * difference belongs in the systematic rather than being ignored.
         */
        public boolean significant() {
            final double together = Math.hypot(myBand, theirBand);
            return together > 0.0 && Math.abs(mine - theirs) > together;
        }
    }

    /** Compares this set with another across a range of x, at one scale. */
    public List<Difference> compare(RootPdfSet other, int pid, double q2, int points) {
        List<Difference> found = new ArrayList<>();
        final double lowest = Math.log(Math.max(members.get(0).xMin(), other.members.get(0).xMin()));
        final double highest = Math.log(0.9);
        for (int i = 0; i < points; i++) {
            final double x = Math.exp(lowest + (highest - lowest) * i / (points - 1.0));
            final Band a = band(pid, x, q2);
            final Band b = other.band(pid, x, q2);
            found.add(new Difference(pid, x, q2, a.central(), b.central(),
                b.central() == 0.0 ? Double.NaN : a.central() / b.central(),
                a.spread() / 2.0, b.spread() / 2.0));
        }
        return found;
    }

    /* ------------------------------------------------------------------ */
    /* What the set is                                                     */
    /* ------------------------------------------------------------------ */

    public String name() { return name; }
    public Path folder() { return folder; }
    public int memberCount() { return members.size(); }
    public Errors errorType() { return errors; }
    public double confidenceLevel() { return confidence; }
    public RootPdfGrid member(int at) { return members.get(at); }
    public RootPdfGrid centralMember() { return members.get(0); }

    public String meta(String key, String fallback) {
        return info.getOrDefault(key, fallback);
    }

    /** Everything the members had to say about themselves, member by member. */
    public List<RootPdfGrid.Finding> check() {
        List<RootPdfGrid.Finding> found = new ArrayList<>(members.get(0).check());
        int broken = 0;
        for (int m = 1; m < members.size(); m++) {
            if (!members.get(m).usable()) {
                broken++;
            }
        }
        if (broken > 0) {
            found.add(new RootPdfGrid.Finding(true,
                broken + " of " + (members.size() - 1) + " error members are unusable."));
        }
        if (errors == Errors.HESSIAN && members.size() % 2 == 0) {
            found.add(new RootPdfGrid.Finding(true,
                "A Hessian set needs an even number of error members, one pair per "
                + "eigenvector, but this one has " + (members.size() - 1) + "."));
        }
        if (members.size() > 1 && errors == Errors.NONE) {
            found.add(new RootPdfGrid.Finding(false,
                members.size() - 1 + " extra members are present but the info file does not "
                + "say how to combine them, so no uncertainty can be given."));
        }

        final int declared = declaredMembers();
        if (declared > 0 && members.size() > 1 && declared != members.size()) {
            found.add(new RootPdfGrid.Finding(true, "The info file declares " + declared
                + " members but " + members.size() + " files are present."));
        }
        found.addAll(rangeFindings());
        return found;
    }

    private int declaredMembers() {
        try {
            return Integer.parseInt(info.getOrDefault("NumMembers", "0").trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * Whether the grid covers what the info file says it covers.
     *
     * A set that stopped downloading part way through still parses: the file
     * ends on a whole line, the blocks are consistent, and every sum rule holds
     * to within a fraction of a percent, because the momentum below a small x
     * edge is small by construction. Nothing in the values gives it away. What
     * does give it away is the info file, which still declares the range the
     * set was published with, and no longer matches the grid beside it.
     */
    private List<RootPdfGrid.Finding> rangeFindings() {
        List<RootPdfGrid.Finding> found = new ArrayList<>();
        final RootPdfGrid one = members.get(0);
        compare(found, "XMin", one.xMin(), "smallest x");
        compare(found, "XMax", one.xMax(), "largest x");
        compare(found, "QMin", one.qMin(), "smallest Q");
        compare(found, "QMax", one.qMax(), "largest Q");
        return found;
    }

    private void compare(List<RootPdfGrid.Finding> found, String key,
                         double actual, String what) {
        final String said = info.get(key);
        if (said == null) {
            return;
        }
        final double declared;
        try {
            declared = Double.parseDouble(said.trim());
        } catch (NumberFormatException notANumber) {
            return;
        }
        if (declared == 0.0 || Math.abs(actual - declared) / Math.abs(declared) < 1e-6) {
            return;
        }
        found.add(new RootPdfGrid.Finding(true, String.format(Locale.ROOT,
            "The info file gives %s as %.6g but the grid holds %.6g. The set is not "
            + "the one it says it is: most often a download that stopped part way.",
            what, declared, actual)));
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s  %d member%s  %s  %s",
            name, members.size(), members.size() == 1 ? "" : "s",
            errors, members.get(0).toString());
    }

    /** The flavors the set carries, in the order the interpolator keeps them. */
    public int[] flavors() {
        return members.get(0).flavors();
    }

    /** True when every sum rule holds at the scale given. */
    public boolean sound(double q2) {
        return sumRules(q2).stream().allMatch(Rule::holds);
    }

    /** The sum rules written out, one per line. */
    public String sumRuleReport(double q2) {
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "%s at Q = %.4g GeV%n", name, Math.sqrt(q2)));
        for (Rule rule : sumRules(q2)) {
            out.append("  ").append(rule.summary()).append(System.lineSeparator());
        }
        return out.toString();
    }

    /** The flavors a luminosity channel may name, for a menu that offers them. */
    public static List<Channel> commonChannels() {
        return List.of(new Channel(21, 21), new Channel(2, -2), new Channel(1, -1),
                       new Channel(21, 2), new Channel(21, 1), new Channel(4, -4),
                       new Channel(5, -5));
    }

    /** The name a channel goes by. */
    public static String channelName(Channel channel) {
        return particleName(channel.first()) + particleName(channel.second());
    }

    /** The name a particle code goes by, for a label or a legend. */
    public static String particleName(int pid) {
        return switch (pid) {
            case 21 -> "g";
            case 22 -> "gamma";
            case 1 -> "d";
            case -1 -> "dbar";
            case 2 -> "u";
            case -2 -> "ubar";
            case 3 -> "s";
            case -3 -> "sbar";
            case 4 -> "c";
            case -4 -> "cbar";
            case 5 -> "b";
            case -5 -> "bbar";
            case 6 -> "t";
            case -6 -> "tbar";
            default -> String.valueOf(pid);
        };
    }

    /** The members' values at one point, for a caller that wants the sample itself. */
    public double[] members(int pid, double x, double q2) {
        double[] values = new double[members.size()];
        for (int m = 0; m < members.size(); m++) {
            values[m] = members.get(m).xfxQ2(pid, x, q2);
        }
        return values;
    }

    /** The replicas sorted, for a percentile rather than a standard deviation. */
    public Band percentileBand(int pid, double x, double q2, double percent) {
        if (errors != Errors.REPLICAS || members.size() < 3) {
            return band(pid, x, q2);
        }
        double[] values = members(pid, x, q2);
        double[] replicas = Arrays.copyOfRange(values, 1, values.length);
        Arrays.sort(replicas);
        final double tail = (100.0 - percent) / 200.0;
        return new Band(values[0], at(replicas, tail), at(replicas, 1.0 - tail));
    }

    private static double at(double[] sorted, double fraction) {
        final double place = fraction * (sorted.length - 1);
        final int below = (int) Math.floor(place);
        final int above = Math.min(below + 1, sorted.length - 1);
        return sorted[below] + (place - below) * (sorted[above] - sorted[below]);
    }
}

package com.sphere.core.golem95;

import com.sphere.core.golem95.port.Cx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One-loop tensor integrals, every component, built from Golem95's form factors:
 *
 * <pre>
 *   I^{mu1..mur} = integral of k^mu1 ... k^mur / prod_i ((k + r_i)^2 - m_i^2)     (Golem95's normalisation)
 *                = sum_g sum_{j1..jm in S} [g^g r_j1 ... r_jm]^{mu1..mur} F_g(j1 .. jm),   m = r - 2g,
 * </pre>
 *
 * with F_0 = A, F_1 = B, F_2 = C, F_3 = D, the bracket summed over the ways of putting the g metrics on pairs of
 * indices (the rest taking the r's in order), metric (+,-,-,-), component 0 the energy. Derived from Feynman
 * parameters, the sum holds for any set S of propagators and any momenta r_i: no r_N = 0 needed, a pinched
 * propagator simply left out.
 *
 * <p>Golem95 has the same formula in its module tensor_integrals, rank by rank, and it cannot be used as it
 * is in 1.3.4: its init_smat writes into an S matrix it never associates (only initgolem95 does) and never
 * prepares; 25 of the ti* take the propagators left from the previous integral's S matrix, before init_smat,
 * and ti2r1, ti2r2 read their optional argument pinches before asking whether it was given; ti4r5 is in no
 * generic interface; ti3r4 calls the box's A44, B44, C44 and ti5r6 the hexagon's A66, each over labels its
 * integral does not have, and stop; ti2r3 and ti4r5 run over rows of their momenta that are not there and
 * return wrong numbers without a word; ti3r1 puts a pair of numbers into three. With a propagator pinched,
 * every ti* asks for the whole diagram's form factors (A41 where the triangle left needs A31), which give NaN
 * or numbers depending on what was computed before, and pairs momenta with the wrong propagators; ti4r3
 * crashes. Here one formula serves every N and every rank up to 7 (beyond, a g^4 term would need form factors
 * Golem95 does not have), for complex masses too, a pinched propagator taking the form factors of the
 * diagram left. It was held to Golem95's own ti* where they work, and to a corrected copy where they do not.
 *
 * <p>Above rank N, Golem95's form factors are not symmetric in their labels, as the integral is: it reduces
 * them in the order given, and another order gives another number, by up to 1e-9 for a triangle at rank 4 and
 * 3e-3 for a box at rank 7 (below rank N+1 the orders agree to the rounding). Golem95's ti* take each order
 * with its own number, so their tensors are not symmetric either. Sphere takes the mean over the orders, the
 * symmetric tensor the integral is, and counts what one order or another would change in the tensor's error
 * ({@link Tensor#orderSpread()}). The hexagon at rank 7 was worse, for another reason: Golem95 1.3.4 reduces it
 * over permutations that start from an array entry it never sets, so its numbers were wrong by factors of up to
 * a thousand and changed from one call to the next; the sources Sphere carries, and so the port, set it.
 *
 * <p>{@link #identities} holds a tensor to what it must satisfy exactly: its contractions with r_j - r_a and its
 * trace are tensors of lower rank with a propagator more pinched, computed from other form factors.
 */
public final class Tensors {

    private Tensors() {
    }

    /** How a tensor takes the form factors above rank N, which depend on the order of their labels. */
    public enum Labels {
        /** The mean over the orders of the labels: the symmetric tensor the integral is (Sphere's answer). */
        SYMMETRIZED,
        /** Each order of the labels with its own form factor, as Golem95's ti* sum them (to hold Sphere to them). */
        AS_GOLEM95
    }

    /**
     * The orders of labels one symmetrized tensor computes at most: every order of every label set up to it
     * (every box and pentagon), two per set beyond (the sorted order and its reverse: the hexagon at rank 7).
     */
    static final int ORDER_BUDGET = 8192;

    /** The highest rank whose tensor Golem95's form factors give whole. */
    public static int maxRank(int points) {
        return Math.min(7, GolemEngine.maxRank(points));
    }

    /** The metric diag(+1, -1, -1, -1). */
    private static double eta(int mu) {
        return mu == 0 ? 1 : -1;
    }

    /**
     * A tensor integral: 4^rank Laurent series, the components in column-major order (the first index
     * changes fastest), as Golem95's Fortran stores them.
     *
     * @param orderSpread how far the tensor moves, relative to its largest component, when its form factors
     *                    above rank N are each taken in one order of their labels (the sorted one, or its
     *                    reverse) instead: part of its error, 0 at rank N and below
     * @param lastPair    the rank-(R-2) tensor of the terms whose last two indices carry one g^{mu nu} together,
     *                    in the components of the others (empty below rank 2): what the trace over the last two
     *                    indices in n dimensions has beyond the 4-dimensional one, over n - 4 = -2 eps
     */
    public record Tensor(int rank, int points, double[] data, double mu2, String what, double orderSpread,
                         double[] lastPair) {

        public int size() {
            return data.length / 6;
        }

        private int flat(int[] mu) {
            if (mu.length != rank) {
                throw new IllegalArgumentException("a rank-" + rank + " tensor has " + rank + " indices");
            }
            int f = 0;
            for (int s = rank - 1; s >= 0; s--) {
                if (mu[s] < 0 || mu[s] > 3) {
                    throw new IllegalArgumentException("Lorentz indices are 0 (energy) to 3");
                }
                f = 4 * f + mu[s];
            }
            return f;
        }

        /** The component I^{mu1 ... mur}. */
        public Laurent get(int... mu) {
            final int o = 6 * flat(mu);
            return new Laurent(new Cx(data[o], data[o + 1]), new Cx(data[o + 2], data[o + 3]), new Cx(data[o + 4], data[o + 5]),
                mu2);
        }

        /** sum over the components times v1[mu1] ... vr[mur], the vectors' components as given (no metric). */
        public Laurent contractComponents(double[]... v) {
            return contract(v, false);
        }

        /** The Lorentz-invariant contraction I^{mu1..mur} v1_mu1 ... vr_mur, the vectors' indices lowered. */
        public Laurent contract(double[]... v) {
            return contract(v, true);
        }

        private Laurent contract(double[][] v, boolean lower) {
            if (v.length != rank) {
                throw new IllegalArgumentException("a rank-" + rank + " tensor takes " + rank + " vectors");
            }
            final double[] acc = new double[6];
            for (int f = 0; f < size(); f++) {
                double w = 1;
                int q = f;
                for (int s = 0; s < rank; s++) {
                    final int mu = q & 3;
                    q >>= 2;
                    w *= lower ? eta(mu) * v[s][mu] : v[s][mu];
                }
                for (int c = 0; c < 6; c++) {
                    acc[c] += w * data[6 * f + c];
                }
            }
            return new Laurent(new Cx(acc[0], acc[1]), new Cx(acc[2], acc[3]), new Cx(acc[4], acc[5]), mu2);
        }

        /** The largest modulus of a real or imaginary part over the components. */
        public double largest() {
            double m = 0;
            for (double d : data) {
                m = Math.max(m, Math.abs(d));
            }
            return m;
        }
    }

    /**
     * The tensor integral of this rank for the engine's kinematics, which must have its propagators' momenta;
     * the propagators of {@code pinched} left out; symmetrized over the orders of the labels.
     */
    public static Tensor compute(GolemEngine engine, int rank, int[] pinched) {
        return compute(engine, rank, pinched, Labels.SYMMETRIZED);
    }

    /** The same, its form factors above rank N taken as {@code mode} says. */
    public static Tensor compute(GolemEngine engine, int rank, int[] pinched, Labels mode) {
        final Kinematics k = engine.kinematics();
        if (k == null) {
            throw new IllegalStateException("no kinematics: give Golem95 one first");
        }
        final double[][] r = k.momenta();
        if (r == null) {
            throw new IllegalArgumentException("a tensor's components need the propagators' momenta: define the kinematics "
                + "with ':golem momenta' (its invariants alone give only Lorentz-invariant quantities)");
        }
        final int n = k.n();
        final int[] pin = pinched == null ? new int[0] : pinched.clone();
        final boolean[] out = new boolean[n + 1];
        for (int p : pin) {
            if (p < 1 || p > n || out[p]) {
                throw new IllegalArgumentException("cannot pinch propagator " + p + " of a " + n + "-point integral");
            }
            out[p] = true;
        }
        final int points = n - pin.length;
        if (points < 1) {
            throw new IllegalArgumentException("pinching every propagator leaves no integral");
        }
        if (rank < 0 || rank > maxRank(points)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "Sphere builds %d-point tensors up to rank %d "
                + "(Golem95 has no form factors for a fourth g^{mu nu})", points, maxRank(points)));
        }
        // the labels the sums run over: the propagators left whose momentum is not zero
        final List<Integer> labels = new ArrayList<>();
        for (int j = 1; j <= n; j++) {
            if (!out[j] && !isZero(r[j - 1])) {
                labels.add(j);
            }
        }
        final Values v = formFactors(engine, rank, points, labels, pin, mode);
        final Lookup returned = mode == Labels.AS_GOLEM95 ? v::own : v::mean;
        final double[] data = assemble(rank, points, labels, r, returned);
        double spread = 0;
        if (v.ordered()) {
            // what one order of the labels or another would give instead
            final double scale = Math.max(largest(data), Double.MIN_NORMAL);
            for (Lookup one : new Lookup[]{v::sorted, v::reversed}) {
                final double[] other = assemble(rank, points, labels, r, one);
                for (int i = 0; i < data.length; i++) {
                    spread = Math.max(spread, Math.abs(other[i] - data[i]) / scale);
                }
            }
        }
        final String what = String.format(Locale.ROOT, "rank-%d tensor of the %d-point integral%s", rank, points,
            pin.length == 0 ? "" : " without propagator" + (pin.length == 1 ? " " : "s ") + Arrays.toString(pin));
        return new Tensor(rank, points, data, engine.settings().mu2(), what, spread,
            rank >= 2 ? lastPair(rank, points, labels, r, returned) : new double[0]);
    }

    /**
     * The terms of sum_g [g^g r ... r]_sym F_g whose last two positions are one pair of the g metrics: g - 1
     * metrics over the first R - 2 positions, the r's on the rest, in order.
     */
    private static double[] lastPair(int rank, int points, List<Integer> labels, double[][] r, Lookup f) {
        final double[] data = new double[6 << (2 * (rank - 2))];
        for (int g = 1; 2 * g <= rank && g <= 3; g++) {
            final char type = "abcd".charAt(g);
            if (GolemEngine.missing(type, points, rank) != null) {
                continue;
            }
            final int m = rank - 2 * g;
            if (m > 0 && labels.isEmpty()) {
                continue;
            }
            addPairings(data, rank - 2, g - 1, vectorPart(type, m, labels, r, f));
        }
        return data;
    }

    /**
     * How far a tensor is from identities it satisfies exactly, k.(r_j - r_a) and k^2 being written with its
     * propagators D_i = (k + r_i)^2 - m_i^2 (a the last one left, j each other):
     *
     * <pre>
     *   T_R^{.. nu} (r_j - r_a)_nu = [T_{R-1}(no j) - T_{R-1}(no a) - (r_j^2 - m_j^2 - r_a^2 + m_a^2) T_{R-1}] / 2
     *   g_{mu nu} T_R^{.. mu nu}   = T_{R-2}(no a) - 2 T_{R-1}^{.. nu} r_{a nu} - (r_a^2 - m_a^2) T_{R-2}
     * </pre>
     *
     * the trace in n dimensions below five points (the 4-dimensional one, less 2 eps times {@link Tensor#lastPair()}),
     * where Golem95's g^{mu nu} is the n-dimensional metric; at five and six points, which Golem95 writes in four
     * dimensions, the 4-dimensional trace, where the tensor is finite in the ultraviolet (not for the pentagon at
     * rank 6). The tensors of lower rank, with one propagator more pinched, are computed anew from other form
     * factors: a measure of the tensor's accuracy that does not take Golem95's word for it.
     *
     * @param contraction the largest deviation over j, relative to the largest component of the terms compared
     * @param trace       the same for the trace (NaN below rank 2, and for the pentagon at rank 6)
     * @param tensors     how many tensors were computed for it
     */
    public record Identities(double contraction, double trace, int tensors, long nanos) {

        /** The larger of the two that were measured. */
        public double worst() {
            return Double.isNaN(trace) ? contraction : Double.isNaN(contraction) ? trace : Math.max(contraction, trace);
        }
    }

    /** The identities of {@code t}, the tensor of the engine's kinematics with those propagators pinched. */
    public static Identities identities(GolemEngine engine, Tensor t, int[] pinched) {
        final long t0 = System.nanoTime();
        final Kinematics k = engine.kinematics();
        final double[][] r = k.momenta();
        final int rank = t.rank();
        final int[] pin = pinched == null ? new int[0] : pinched.clone();
        final List<Integer> left = new ArrayList<>();
        for (int j = 1; j <= k.n(); j++) {
            boolean gone = false;
            for (int p : pin) {
                gone |= p == j;
            }
            if (!gone) {
                left.add(j);
            }
        }
        if (rank < 1 || left.size() < 2) {
            throw new IllegalArgumentException("the identities need a tensor of rank 1 or more with two propagators or more");
        }
        final int a = left.get(left.size() - 1);
        final double[] ra = r[a - 1];
        final Cx ca = Cx.sub(Cx.of(dot(ra, ra)), k.mass2(a));
        final Tensor lower = compute(engine, rank - 1, pin);
        final Tensor noA = compute(engine, rank - 1, with(pin, a));
        int tensors = 2;
        double contraction = 0;
        for (int j : left) {
            if (j == a) {
                continue;
            }
            final double[] rj = r[j - 1];
            final double[] v = {rj[0] - ra[0], rj[1] - ra[1], rj[2] - ra[2], rj[3] - ra[3]};
            final Tensor noJ = compute(engine, rank - 1, with(pin, j));
            tensors++;
            final double[] lhs = contractLast(t.data(), rank, v);
            final Cx c = Cx.sub(Cx.sub(Cx.of(dot(rj, rj)), k.mass2(j)), ca);
            final double[] scaled = times(lower.data(), c);
            final double[] rhs = new double[lhs.length];
            for (int i = 0; i < rhs.length; i++) {
                rhs[i] = 0.5 * (noJ.data()[i] - noA.data()[i] - scaled[i]);
            }
            contraction = Math.max(contraction, gap(lhs, rhs, noJ.data(), noA.data(), scaled));
        }
        // Below five points Golem95's g^{mu nu} is the n-dimensional metric. At five and six it writes the tensors in
        // four dimensions, their (n-4)-dimensional parts being O(eps) where the tensor is finite in the ultraviolet
        // (R < 2N - 4): its form factors there carry infrared poles that n - 4 must not multiply. The pentagon at
        // rank 6, which is not finite, drops a rational term the trace would need: only its contractions are checked.
        final int points = left.size();
        final boolean nDim = points <= 4;
        double trace = Double.NaN;
        if (rank >= 2 && (nDim || rank < 2 * points - 4)) {
            final Tensor lower2 = compute(engine, rank - 2, pin);
            final Tensor noA2 = compute(engine, rank - 2, with(pin, a));
            tensors += 2;
            final double[] four = traceLast(t.data(), rank);
            final double[] lhs = four.clone();
            final double[] twoX = new double[lhs.length];
            if (nDim) {
                final double[] x = t.lastPair();
                for (int i = 0; i < lhs.length; i += 6) {
                    // less 2 eps times x: its 1/eps^2 term into 1/eps, its 1/eps term into the finite part
                    twoX[i + 2] = 2 * x[i];
                    twoX[i + 3] = 2 * x[i + 1];
                    twoX[i + 4] = 2 * x[i + 2];
                    twoX[i + 5] = 2 * x[i + 3];
                }
                for (int i = 0; i < lhs.length; i++) {
                    lhs[i] -= twoX[i];
                }
            }
            final double[] across = contractLast(lower.data(), rank - 1, ra);
            final double[] scaled = times(lower2.data(), ca);
            final double[] rhs = new double[lhs.length];
            for (int i = 0; i < rhs.length; i++) {
                rhs[i] = noA2.data()[i] - 2 * across[i] - scaled[i];
            }
            // measured against everything combined, the 4-dimensional trace and its shift included: both sides can be 0
            trace = gap(lhs, rhs, four, twoX, noA2.data(), across, scaled);
        }
        return new Identities(contraction, trace, tensors, System.nanoTime() - t0);
    }

    private static int[] with(int[] pin, int p) {
        final int[] out = Arrays.copyOf(pin, pin.length + 1);
        out[pin.length] = p;
        return out;
    }

    private static double dot(double[] x, double[] y) {
        return x[0] * y[0] - x[1] * y[1] - x[2] * y[2] - x[3] * y[3];
    }

    /** T^{.. nu} v_nu: the last index contracted with v, lowered. */
    private static double[] contractLast(double[] data, int rank, double[] v) {
        final int low = 1 << (2 * (rank - 1));
        final double[] out = new double[6 * low];
        for (int g = 0; g < low; g++) {
            for (int nu = 0; nu < 4; nu++) {
                final double w = eta(nu) * v[nu];
                if (w == 0) {
                    continue;
                }
                final int from = 6 * (g + low * nu);
                for (int c = 0; c < 6; c++) {
                    out[6 * g + c] += w * data[from + c];
                }
            }
        }
        return out;
    }

    /** g_{mu nu} T^{.. mu nu} over the last two indices, in 4 dimensions. */
    private static double[] traceLast(double[] data, int rank) {
        final int low = 1 << (2 * (rank - 2));
        final double[] out = new double[6 * low];
        for (int g = 0; g < low; g++) {
            for (int mu = 0; mu < 4; mu++) {
                final int from = 6 * (g + low * mu + 4 * low * mu);
                for (int c = 0; c < 6; c++) {
                    out[6 * g + c] += eta(mu) * data[from + c];
                }
            }
        }
        return out;
    }

    /** Every Laurent coefficient times the complex number z. */
    private static double[] times(double[] data, Cx z) {
        final double[] out = new double[data.length];
        for (int i = 0; i < data.length; i += 2) {
            out[i] = data[i] * z.re - data[i + 1] * z.im;
            out[i + 1] = data[i] * z.im + data[i + 1] * z.re;
        }
        return out;
    }

    /** max |x - y|, relative to the largest number of x and of every term y is made of. */
    private static double gap(double[] x, double[] y, double[]... terms) {
        double scale = largest(x);
        for (double[] t : terms) {
            scale = Math.max(scale, largest(t));
        }
        double d = 0;
        for (int i = 0; i < x.length; i++) {
            d = Math.max(d, Math.abs(x[i] - y[i]));
        }
        return scale == 0 ? d : d / scale;
    }

    /** sum_g [g^g r ... r]_sym F_g, the form factors as {@code f} gives them. */
    private static double[] assemble(int rank, int points, List<Integer> labels, double[][] r, Lookup f) {
        final double[] data = new double[6 << (2 * rank)];
        for (int g = 0; 2 * g <= rank && g <= 3; g++) {
            final char type = "abcd".charAt(g);
            if (GolemEngine.missing(type, points, rank) != null) {
                continue;
            }
            final int m = rank - 2 * g;
            if (m > 0 && labels.isEmpty()) {
                continue;
            }
            addPairings(data, rank, g, vectorPart(type, m, labels, r, f));
        }
        return data;
    }

    private static double largest(double[] data) {
        double m = 0;
        for (double d : data) {
            m = Math.max(m, Math.abs(d));
        }
        return m;
    }

    /** A form factor's value for its labels in the order given. */
    private interface Lookup {
        Laurent of(char type, int[] ordered);
    }

    /**
     * The form factors of one tensor: each order of labels computed ({@code own}, by type and labels in order)
     * and the mean over them for each set of labels ({@code mean}, by type and sorted labels); {@code ordered}
     * when some set's form factor was computed in more than one order.
     */
    private record Values(Map<String, Laurent> own, Map<String, Laurent> mean, boolean ordered) {

        Laurent mean(char type, int[] ordered) {
            return mean.get(key(type, sortedCopy(ordered)));
        }

        /** Its own value where that order was computed, else the set's (computed in one order only). */
        Laurent own(char type, int[] ordered) {
            final Laurent l = own.get(key(type, ordered));
            return l != null ? l : mean(type, ordered);
        }

        Laurent sorted(char type, int[] ordered) {
            return own.get(key(type, sortedCopy(ordered)));
        }

        Laurent reversed(char type, int[] ordered) {
            final int[] s = sortedCopy(ordered);
            for (int i = 0, j = s.length - 1; i < j; i++, j--) {
                final int t = s[i];
                s[i] = s[j];
                s[j] = t;
            }
            final Laurent l = own.get(key(type, s));
            return l != null ? l : sorted(type, ordered);
        }
    }

    private static int[] sortedCopy(int[] a) {
        final int[] s = a.clone();
        Arrays.sort(s);
        return s;
    }

    /**
     * Every form factor the tensor needs, computed on the workers, each keeping Golem95's cache for the
     * kinematics across its share; from a worker itself (a tensor computed inside a parallel task) in order,
     * so no task waits on its own pool. At rank N and below, once per set of labels (the orders agree); above,
     * in every order of its labels within {@link #ORDER_BUDGET} (always, for {@link Labels#AS_GOLEM95}), else
     * in the sorted order and its reverse.
     */
    private static Values formFactors(GolemEngine e, int rank, int points, List<Integer> labels, int[] pin, Labels mode) {
        final List<Object[]> sets = new ArrayList<>();
        int every = 0;
        for (int g = 0; 2 * g <= rank && g <= 3; g++) {
            final char type = "abcd".charAt(g);
            if (GolemEngine.missing(type, points, rank) != null) {
                continue;
            }
            final int m = rank - 2 * g;
            if (m > 0 && labels.isEmpty()) {
                continue;
            }
            multisets(labels, m, 0, new int[m], 0, type, sets);
            every += (int) Math.min(Integer.MAX_VALUE / 8, Math.pow(labels.size(), m));
        }
        final boolean above = rank > points;
        final boolean all = mode == Labels.AS_GOLEM95 || every <= ORDER_BUDGET;
        final List<Object[]> needed = new ArrayList<>();
        final List<List<String>> orders = new ArrayList<>();
        for (Object[] x : sets) {
            final char type = (char) x[0];
            final int[] s = (int[]) x[1];
            final List<int[]> these = new ArrayList<>();
            if (above && distinct(s) > 1) {
                if (all) {
                    permutations(s.clone(), 0, these);
                } else {
                    these.add(s);
                    final int[] rev = s.clone();
                    for (int i = 0, j = rev.length - 1; i < j; i++, j--) {
                        final int t = rev[i];
                        rev[i] = rev[j];
                        rev[j] = t;
                    }
                    these.add(rev);
                }
            } else {
                these.add(s);
            }
            final List<String> keys = new ArrayList<>();
            for (int[] o : these) {
                needed.add(new Object[]{type, o});
                keys.add(key(type, o));
            }
            orders.add(keys);
        }
        final Map<String, Laurent> own = compute(e, needed, pin);
        final Map<String, Laurent> mean = new HashMap<>();
        boolean ordered = false;
        for (int i = 0; i < sets.size(); i++) {
            final List<String> keys = orders.get(i);
            ordered |= keys.size() > 1;
            mean.put(key((char) sets.get(i)[0], (int[]) sets.get(i)[1]), keys.size() == 1 ? own.get(keys.get(0)) : mean(own, keys));
        }
        return new Values(own, mean, ordered);
    }

    private static Laurent mean(Map<String, Laurent> own, List<String> keys) {
        final double[] acc = new double[6];
        double mu2 = 0;
        for (String k : keys) {
            final Laurent l = own.get(k);
            acc[0] += l.a().re;
            acc[1] += l.a().im;
            acc[2] += l.b().re;
            acc[3] += l.b().im;
            acc[4] += l.c().re;
            acc[5] += l.c().im;
            mu2 = l.mu2();
        }
        final int n = keys.size();
        return new Laurent(new Cx(acc[0] / n, acc[1] / n), new Cx(acc[2] / n, acc[3] / n), new Cx(acc[4] / n, acc[5] / n), mu2);
    }

    private static int distinct(int[] sorted) {
        int d = sorted.length == 0 ? 0 : 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] != sorted[i - 1]) {
                d++;
            }
        }
        return d;
    }

    /** Every distinct order of the labels, the sorted one first. */
    private static void permutations(int[] a, int k, List<int[]> out) {
        if (k == a.length) {
            out.add(a.clone());
            return;
        }
        for (int i = k; i < a.length; i++) {
            boolean seen = false;
            for (int j = k; j < i && !seen; j++) {
                seen = a[j] == a[i];
            }
            if (seen) {
                continue;
            }
            rotate(a, k, i);
            permutations(a, k + 1, out);
            unrotate(a, k, i);
        }
    }

    /** Moves a[i] to position k, shifting a[k..i-1] right: the rest stays sorted, so the sorted order comes first. */
    private static void rotate(int[] a, int k, int i) {
        final int t = a[i];
        System.arraycopy(a, k, a, k + 1, i - k);
        a[k] = t;
    }

    private static void unrotate(int[] a, int k, int i) {
        final int t = a[k];
        System.arraycopy(a, k + 1, a, k, i - k);
        a[i] = t;
    }

    /** The form factors listed, by type and labels in order. */
    private static Map<String, Laurent> compute(GolemEngine e, List<Object[]> needed, int[] pin) {
        final Map<String, Laurent> out = new HashMap<>();
        final boolean nested = Golem95.onWorker();
        if (nested || needed.size() < 16) {
            for (Object[] x : needed) {
                out.put(key((char) x[0], (int[]) x[1]), e.formFactor((char) x[0], (int[]) x[1], pin).laurent());
            }
            return out;
        }
        final Kinematics k = e.kinematics();
        final GolemEngine.Settings s = e.settings();
        final int chunks = Math.min(needed.size(), 4 * Golem95.workers());
        final List<java.util.concurrent.Callable<List<Laurent>>> tasks = new ArrayList<>();
        for (int c = 0; c < chunks; c++) {
            final int from = c * needed.size() / chunks;
            final int to = (c + 1) * needed.size() / chunks;
            final List<Object[]> part = needed.subList(from, to);
            tasks.add(() -> {
                final GolemEngine w = Golem95.workerEngine(s).set(k);
                final List<Laurent> v = new ArrayList<>();
                for (Object[] x : part) {
                    v.add(w.formFactor((char) x[0], (int[]) x[1], pin).laurent());
                }
                return v;
            });
        }
        final List<Golem95.Outcome<List<Laurent>>> done = Golem95.all(tasks);
        for (int c = 0; c < chunks; c++) {
            final Golem95.Outcome<List<Laurent>> o = done.get(c);
            if (!o.ok()) {
                throw o.error();
            }
            final int from = c * needed.size() / chunks;
            for (int i = 0; i < o.value().size(); i++) {
                final Object[] x = needed.get(from + i);
                out.put(key((char) x[0], (int[]) x[1]), o.value().get(i));
            }
        }
        return out;
    }

    /** The multisets of size m of the labels, as sorted arrays (labels in increasing order). */
    private static void multisets(List<Integer> labels, int m, int start, int[] cur, int depth, char type, List<Object[]> out) {
        if (depth == m) {
            out.add(new Object[]{type, cur.clone()});
            return;
        }
        for (int i = start; i < labels.size(); i++) {
            cur[depth] = labels.get(i);
            multisets(labels, m, i, cur, depth + 1, type, out);
        }
    }

    private static String key(char type, int[] sortedLabels) {
        return type + Arrays.toString(sortedLabels);
    }

    /**
     * How far the tensor moves, relative to its largest component, when the momenta and masses move by 1e-15
     * (twice, on two workers): how many of its digits mean something.
     */
    public static double noise(Kinematics k, GolemEngine.Settings s, int rank, int[] pinched, Tensor t0, long seed) {
        final java.util.Random rng = new java.util.Random(seed);
        final List<java.util.concurrent.Callable<Tensor>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final Kinematics moved = k.perturbedMomenta(new java.util.Random(rng.nextLong()), Reliability.NOISE_DELTA);
            tasks.add(() -> compute(Golem95.workerEngine(s).set(moved), rank, pinched));
        }
        final double scale = Math.max(t0.largest(), Double.MIN_NORMAL);
        double noise = 0;
        for (Golem95.Outcome<Tensor> o : Golem95.all(tasks)) {
            if (!o.ok()) {
                return Double.NaN;
            }
            final double[] d = o.value().data();
            for (int i = 0; i < d.length; i++) {
                noise = Math.max(noise, Math.abs(d[i] - t0.data()[i]) / scale);
            }
        }
        return noise;
    }

    private static boolean isZero(double[] v) {
        return v[0] == 0 && v[1] == 0 && v[2] == 0 && v[3] == 0;
    }

    /**
     * G^{nu1..num} = sum_{j1..jm} r_j1^nu1 ... r_jm^num F(j1..jm), by contracting one label after the other
     * (m mode products instead of a sum over every label and every component at once).
     */
    private static double[] vectorPart(char type, int m, List<Integer> labels, double[][] r, Lookup values) {
        final int nl = labels.size();
        if (m == 0) {
            final Laurent v = values.of(type, new int[0]);
            return new double[]{v.a().re, v.a().im, v.b().re, v.b().im, v.c().re, v.c().im};
        }
        // F over the labels, first label fastest, each order of labels as the lookup takes it
        int total = 1;
        for (int s = 0; s < m; s++) {
            total *= nl;
        }
        double[] cur = new double[6 * total];
        final int[] idx = new int[m];
        for (int f = 0; f < total; f++) {
            int q = f;
            for (int s = 0; s < m; s++) {
                idx[s] = labels.get(q % nl);
                q /= nl;
            }
            final Laurent v = values.of(type, idx);
            final int o = 6 * f;
            cur[o] = v.a().re;
            cur[o + 1] = v.a().im;
            cur[o + 2] = v.b().re;
            cur[o + 3] = v.b().im;
            cur[o + 4] = v.c().re;
            cur[o + 5] = v.c().im;
        }
        // mode s: [nu_1 .. nu_s-1 (Lorentz), j_s .. j_m (labels)] -> [nu_1 .. nu_s, j_s+1 .. j_m]
        int lorentz = 1;
        int rest = total;
        for (int s = 0; s < m; s++) {
            rest /= nl;
            final double[] next = new double[6 * lorentz * 4 * rest];
            for (int a = 0; a < lorentz; a++) {
                for (int jl = 0; jl < nl; jl++) {
                    final double[] rv = r[labels.get(jl) - 1];
                    for (int b = 0; b < rest; b++) {
                        final int from = 6 * (a + lorentz * (jl + nl * b));
                        for (int nu = 0; nu < 4; nu++) {
                            final double w = rv[nu];
                            if (w == 0) {
                                continue;
                            }
                            final int to = 6 * (a + lorentz * (nu + 4 * b));
                            for (int c = 0; c < 6; c++) {
                                next[to + c] += w * cur[from + c];
                            }
                        }
                    }
                }
            }
            cur = next;
            lorentz *= 4;
        }
        return cur;
    }

    /**
     * Adds sum over the pairings of g of the r indices: for each way of choosing g disjoint pairs of index
     * positions, prod eta^{mu_a mu_b} times G at the remaining positions in order.
     */
    private static void addPairings(double[] data, int rank, int g, double[] gt) {
        final List<int[]> pairings = new ArrayList<>();
        pairings(new int[rank], 0, g, -1, pairings);
        final int size = 1 << (2 * rank);
        final int[] mu = new int[rank];
        for (int f = 0; f < size; f++) {
            int q = f;
            for (int s = 0; s < rank; s++) {
                mu[s] = q & 3;
                q >>= 2;
            }
            for (int[] p : pairings) {
                // p[s] = 0 for an r position, else the pair number (1..g) of the metric it carries
                double w = 1;
                boolean zero = false;
                for (int pair = 1; pair <= g && !zero; pair++) {
                    int a = -1;
                    int b = -1;
                    for (int s = 0; s < rank; s++) {
                        if (p[s] == pair) {
                            if (a < 0) {
                                a = s;
                            } else {
                                b = s;
                            }
                        }
                    }
                    if (mu[a] != mu[b]) {
                        zero = true;
                    } else {
                        w *= eta(mu[a]);
                    }
                }
                if (zero) {
                    continue;
                }
                int gf = 0;
                int mult = 1;
                for (int s = 0; s < rank; s++) {
                    if (p[s] == 0) {
                        gf += mult * mu[s];
                        mult *= 4;
                    }
                }
                for (int c = 0; c < 6; c++) {
                    data[6 * f + c] += w * gt[6 * gf + c];
                }
            }
        }
    }

    /**
     * Every set of g disjoint pairs of the positions 0..rank-1, once each: the pairs in the order of their first
     * positions (p[s] = 0 for a position left to an r, else the number of its pair).
     */
    private static void pairings(int[] p, int done, int g, int lastFirst, List<int[]> out) {
        if (done == g) {
            out.add(p.clone());
            return;
        }
        for (int a = lastFirst + 1; a < p.length; a++) {
            if (p[a] != 0) {
                continue;
            }
            for (int b = a + 1; b < p.length; b++) {
                if (p[b] != 0) {
                    continue;
                }
                p[a] = done + 1;
                p[b] = done + 1;
                pairings(p, done + 1, g, a, out);
                p[a] = 0;
                p[b] = 0;
            }
        }
    }
}

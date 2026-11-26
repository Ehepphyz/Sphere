package com.sphere.core.golem95;

import com.sphere.core.golem95.port.Cx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The kinematics of an N-point one-loop integral (N = 1..6), as Golem95 takes it: the matrix
 *
 * <pre>
 *   S_ij = (q_i - q_j)^2 - m_i^2 - m_j^2,     q_i = p_1 + ... + p_i,
 * </pre>
 *
 * with Golem95's labels: leg p_i enters between propagators i-1 and i (leg 1 between N and 1), so
 * S(i-1, i) = p_i^2 - m_{i-1}^2 - m_i^2, and for a box S(1,3) = t = (p_2+p_3)^2, S(2,4) = s = (p_1+p_2)^2.
 * Masses are complex (m^2 - i m Gamma) for unstable particles.
 *
 * <p>Built from the matrix, from invariants, or from four-momenta; and read back: {@link #analyse()}
 * says what kind of integral it is, where it is singular (soft and collinear regions, thresholds, a
 * vanishing det S), and how well conditioned the reduction Golem95 will apply to it is.
 */
public final class Kinematics {

    /** What is zero but for rounding: an entry of S under this fraction of the largest. */
    public static final double SNAP = 1e-12;

    private final int n;
    private final Cx[][] s;
    private final String origin;
    /** r_1 .. r_N, the momenta of the propagators (q_i = k + r_i, r_N = 0), when the kinematics has them. */
    private final double[][] momenta;

    private Kinematics(Cx[][] s, String origin) {
        this(s, origin, null);
    }

    private Kinematics(Cx[][] s, String origin, double[][] momenta) {
        this.n = s.length;
        if (momenta != null) {
            if (momenta.length != n) {
                throw new IllegalArgumentException(n + " propagators need " + n + " momenta");
            }
            this.momenta = new double[n][];
            for (int i = 0; i < n; i++) {
                if (momenta[i].length != 4) {
                    throw new IllegalArgumentException("a momentum has four components, E px py pz");
                }
                this.momenta[i] = momenta[i].clone();
            }
        } else {
            this.momenta = null;
        }
        if (n < 1 || n > 6) {
            throw new IllegalArgumentException("Golem95 computes 1- to 6-point integrals; this one has " + n + " points");
        }
        this.s = new Cx[n][n];
        for (int i = 0; i < n; i++) {
            if (s[i].length != n) {
                throw new IllegalArgumentException("the S matrix is not square");
            }
            for (int j = 0; j < n; j++) {
                this.s[i][j] = s[i][j];
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                final Cx a = s[i][j], b = s[j][i];
                final double scale = Math.max(1e-300, Math.max(Cx.abs(a), Cx.abs(b)));
                if (Cx.abs(Cx.sub(a, b)) > 1e-12 * scale) {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "the S matrix is not symmetric: S(%d,%d) = %s but S(%d,%d) = %s", i + 1, j + 1, str(a), j + 1,
                        i + 1, str(b)));
                }
            }
        }
        this.origin = origin;
    }

    /** From the matrix itself, 1-based rows as Golem95 writes them. */
    public static Kinematics ofS(Cx[][] s, String origin) {
        return new Kinematics(s, origin == null ? "S matrix" : origin);
    }

    public static Kinematics tadpole(Cx m1) {
        return new Kinematics(new Cx[][]{{Cx.mul(-2.0, m1)}}, "tadpole m1^2=" + str(m1));
    }

    /** A two-point function of virtuality p^2 and squared masses m1^2, m2^2. */
    public static Kinematics bubble(double p2, Cx m1, Cx m2) {
        final Cx[] m = {m1, m2};
        return fromInvariants(2, new double[]{p2, p2}, Map.of(), m, "bubble p^2=" + num(p2));
    }

    /**
     * From the leg virtualities p_i^2 and the channel invariants: "s12" = (p_1+p_2)^2, "s23", ...,
     * "s123" = (p_1+p_2+p_3)^2 ...; for a box "s" and "t" also stand for s12 and s23. Masses squared.
     */
    public static Kinematics fromInvariants(int n, double[] legs2, Map<String, Double> channels, Cx[] masses2,
                                            String origin) {
        if (legs2.length != n || masses2.length != n) {
            throw new IllegalArgumentException("an " + n + "-point integral needs " + n + " legs and " + n + " masses");
        }
        final Cx[][] s = new Cx[n][n];
        for (int i = 0; i < n; i++) {
            s[i][i] = Cx.mul(-2.0, masses2[i]);
        }
        for (int j = 0; j < n; j++) {
            for (int i = j + 1; i < n; i++) {
                // (q_i - q_j)^2 with q the 1-based partial sums: the legs j+2 .. i+1 (1-based) in between
                final double v = segment(n, j + 2, i - j, legs2, channels);
                s[j][i] = s[i][j] = Cx.sub(Cx.sub(Cx.of(v), masses2[j]), masses2[i]);
            }
        }
        return new Kinematics(s, origin);
    }

    /** The invariant (p_a + ... + p_{a+len-1})^2, legs cyclic and 1-based. */
    private static double segment(int n, int a, int len, double[] legs2, Map<String, Double> ch) {
        int start = ((a - 1) % n) + 1;
        int length = len;
        if (length > n - length) {
            // the complement has the same invariant: momentum is conserved
            start = ((start - 1 + length) % n) + 1;
            length = n - length;
        }
        if (length == 1) {
            return legs2[start - 1];
        }
        final List<String> keys = new ArrayList<>();
        keys.add(key(n, start, length));
        if (2 * length == n) {
            keys.add(key(n, ((start - 1 + length) % n) + 1, length));
        }
        for (String k : new ArrayList<>(keys)) {
            final char[] d = k.substring(1).toCharArray();
            Arrays.sort(d);
            keys.add("s" + new String(d));
        }
        if (n == 4 && length == 2) {
            keys.add(keys.contains("s12") || keys.contains("s34") ? "s" : "t");
        }
        for (String k : keys) {
            final Double v = ch.get(k);
            if (v != null) {
                return v;
            }
        }
        throw new IllegalArgumentException("the invariant " + keys.get(0) + " is needed"
            + (n == 4 ? " (s = s12, t = s23 for a box)" : ""));
    }

    private static String key(int n, int start, int length) {
        final StringBuilder b = new StringBuilder("s");
        for (int k = 0; k < length; k++) {
            b.append(((start - 1 + k) % n) + 1);
        }
        return b.toString();
    }

    /** From incoming four-momenta (E, px, py, pz), metric (+,-,-,-), which must add up to zero. */
    public static Kinematics fromMomenta(double[][] p, Cx[] masses2, String origin) {
        final int n = p.length;
        if (masses2.length != n) {
            throw new IllegalArgumentException(n + " momenta need " + n + " masses");
        }
        final double[] total = new double[4];
        double size = 0;
        for (double[] v : p) {
            for (int k = 0; k < 4; k++) {
                total[k] += v[k];
                size = Math.max(size, Math.abs(v[k]));
            }
        }
        for (int k = 0; k < 4; k++) {
            if (Math.abs(total[k]) > 1e-9 * Math.max(1, size)) {
                throw new IllegalArgumentException("the momenta do not add up to zero: component " + k + " is " + total[k]);
            }
        }
        final double[][] q = new double[n][4];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 4; k++) {
                q[i][k] = (i == 0 ? 0 : q[i - 1][k]) + p[i][k];
            }
        }
        // propagator i carries k + q_i; measured from propagator N's, so that r_N is exactly 0
        final double[][] r = new double[n][4];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 4; k++) {
                r[i][k] = i == n - 1 ? 0 : q[i][k] - q[n - 1][k];
            }
        }
        return fromPropagatorMomenta(r, masses2, origin == null ? "momenta" : origin);
    }

    /**
     * From the momenta of the propagators: propagator i is (k + r_i)^2 - m_i^2, r_N = 0 (the last one may be
     * left out). This is how Golem95's tensor integrals take a kinematics, and what a tensor needs beyond S.
     */
    public static Kinematics fromPropagatorMomenta(double[][] r, Cx[] masses2, String origin) {
        final int n = masses2.length;
        if (r.length != n && r.length != n - 1) {
            throw new IllegalArgumentException(n + " propagators need " + (n - 1) + " momenta (r_N = 0)");
        }
        final double[][] all = new double[n][4];
        for (int i = 0; i < n; i++) {
            if (i < r.length) {
                if (r[i].length != 4) {
                    throw new IllegalArgumentException("a momentum has four components, E px py pz");
                }
                all[i] = r[i].clone();
            }
        }
        for (int k = 0; k < 4; k++) {
            if (all[n - 1][k] != 0) {
                throw new IllegalArgumentException("r_N is the loop momentum's origin: it must be 0");
            }
        }
        final Cx[][] s = new Cx[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    s[i][i] = Cx.mul(-2.0, masses2[i]);
                } else {
                    final double m2 = minkowski(all[i], all[j]);
                    s[i][j] = Cx.sub(Cx.sub(Cx.of(m2), masses2[i]), masses2[j]);
                }
            }
        }
        return new Kinematics(s, origin == null ? "propagator momenta" : origin, all);
    }

    /** (a - b)^2 with the metric (+,-,-,-), E first. */
    static double minkowski(double[] a, double[] b) {
        final double d0 = a[0] - b[0];
        final double d1 = a[1] - b[1];
        final double d2 = a[2] - b[2];
        final double d3 = a[3] - b[3];
        return d0 * d0 - d1 * d1 - d2 * d2 - d3 * d3;
    }

    /**
     * The same kinematics with every component of the propagators' momenta and every mass moved by a relative
     * amount (zeros stay zeros, r_N stays 0): what a tensor's noise is measured with. The entries of S that are
     * zero stay zero, as {@link #perturbed} keeps them: a light-like leg between massless propagators, moved by
     * its rounding, would be a leg off shell by that much, of which Golem95 makes no finite number.
     */
    public Kinematics perturbedMomenta(java.util.Random rng, double relative) {
        if (momenta == null) {
            throw new IllegalStateException("this kinematics has no momenta");
        }
        final double[][] r = new double[n][4];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 4; k++) {
                r[i][k] = momenta[i][k] * (1 + relative * rng.nextGaussian());
            }
        }
        final Cx[] m = new Cx[n];
        for (int i = 0; i < n; i++) {
            final Cx m0 = mass2(i + 1);
            m[i] = new Cx(m0.re * (1 + relative * rng.nextGaussian()), m0.im * (1 + relative * rng.nextGaussian()));
        }
        // r_N is 0 when the kinematics came from fromPropagatorMomenta or fromMomenta; relabelled ones keep theirs
        final Cx[][] t = new Cx[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                t[i][j] = s[i][j].re == 0 && s[i][j].im == 0 ? Cx.ZERO
                    : i == j ? Cx.mul(-2.0, m[i]) : Cx.sub(Cx.sub(Cx.of(minkowski(r[i], r[j])), m[i]), m[j]);
            }
        }
        return new Kinematics(t, origin + " (perturbed)", r);
    }

    /** The propagators' momenta r_1 .. r_N (r_N = 0), or null when the kinematics was given without them. */
    public double[][] momenta() {
        if (momenta == null) {
            return null;
        }
        final double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            out[i] = momenta[i].clone();
        }
        return out;
    }

    public boolean hasMomenta() {
        return momenta != null;
    }

    /*
     * Moving a kinematics. S mixes two kinds of parameters: the invariants P_ij = (q_i - q_j)^2 =
     * S_ij + m_i^2 + m_j^2, real for a physical process, and the masses m_i^2, complex for unstable
     * particles. They are moved separately and S rebuilt, so the invariants stay real (moving the entries
     * of S one by one would give them imaginary parts, a kinematics no process has and Golem95 does not
     * expect). And every exact zero of S stays: a massless propagator (S_ii = 0) and a leg on the mass
     * shell of its neighbours (S_ij = 0) are what makes an integral infrared divergent, and a move that
     * removed them would change the integral, not measure it.
     */

    /** The same integral with each invariant and each mass moved by a relative amount (zeros stay zeros). */
    public Kinematics perturbed(java.util.Random rng, double relative) {
        final double[] mr = new double[n];
        final double[] mi = new double[n];
        final double[][] p = new double[n][n];
        for (int i = 0; i < n; i++) {
            mr[i] = relative * rng.nextGaussian();
            mi[i] = relative * rng.nextGaussian();
            for (int j = i + 1; j < n; j++) {
                p[i][j] = p[j][i] = relative * rng.nextGaussian();
            }
        }
        return moved(mr, mi, p, null, origin + " (perturbed)");
    }

    /**
     * The kinematics moved by h along a symmetric direction d: each mass by a relative h d_ii, each
     * invariant by h d_ij times the scale max|S| (so an invariant that is zero, a light-like leg next to
     * massive propagators, moves too); the zeros of S stay. The integral is analytic along such a line
     * as long as it crosses no threshold.
     */
    public Kinematics deformed(double[][] d, double h) {
        final double[] m = new double[n];
        final double[][] add = new double[n][n];
        final double scale = scale();
        for (int i = 0; i < n; i++) {
            m[i] = h * d[i][i];
            for (int j = 0; j < n; j++) {
                if (i != j) {
                    add[i][j] = h * d[i][j] * scale;
                }
            }
        }
        return moved(m, m, null, add, origin + " (moved)");
    }

    /**
     * Masses times (1 + massRe) and (1 + massIm) in their real and imaginary parts, invariants times
     * (1 + factor) plus add, S rebuilt; the zeros of S kept.
     */
    private Kinematics moved(double[] massRe, double[] massIm, double[][] factor, double[][] add, String name) {
        final Cx[] m = new Cx[n];
        for (int i = 0; i < n; i++) {
            final Cx m0 = mass2(i + 1);
            m[i] = new Cx(m0.re * (1 + massRe[i]), m0.im * (1 + massIm[i]));
        }
        final Cx[][] t = new Cx[n][n];
        for (int i = 0; i < n; i++) {
            t[i][i] = Cx.mul(-2.0, m[i]);
            for (int j = i + 1; j < n; j++) {
                if (s[i][j].re == 0 && s[i][j].im == 0) {
                    t[i][j] = t[j][i] = Cx.ZERO;
                    continue;
                }
                Cx p = Cx.add(Cx.add(s[i][j], mass2(i + 1)), mass2(j + 1));
                if (factor != null) {
                    p = Cx.mul(1 + factor[i][j], p);
                }
                if (add != null) {
                    p = Cx.add(p, add[i][j]);
                }
                t[i][j] = t[j][i] = Cx.sub(Cx.sub(p, m[i]), m[j]);
            }
        }
        return new Kinematics(t, name);
    }

    /**
     * The same integral with propagator i named perm[i-1] (1-based): S'(perm[i], perm[j]) = S(i, j). The
     * integral, as a function of S and of the Feynman parameters' labels, does not change; Golem95's
     * computation of it does, since which formula it picks depends on where the zeros of S are.
     */
    public Kinematics permuted(int[] perm) {
        if (perm.length != n) {
            throw new IllegalArgumentException("a permutation of " + n + " propagators is needed");
        }
        final boolean[] seen = new boolean[n];
        for (int p : perm) {
            if (p < 1 || p > n || seen[p - 1]) {
                throw new IllegalArgumentException("not a permutation of 1.." + n + ": " + Arrays.toString(perm));
            }
            seen[p - 1] = true;
        }
        final Cx[][] t = new Cx[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                t[perm[i] - 1][perm[j] - 1] = s[i][j];
            }
        }
        double[][] r = null;
        if (momenta != null) {
            r = new double[n][];
            for (int i = 0; i < n; i++) {
                r[perm[i] - 1] = momenta[i];
            }
        }
        return new Kinematics(t, origin + " (relabelled " + Arrays.toString(perm) + ")", r);
    }

    /** The integral left when the propagators of {@code set} (1-based) are removed: Golem95's pinching. */
    public Kinematics pinched(int[] set) {
        if (set == null || set.length == 0) {
            return this;
        }
        final boolean[] out = new boolean[n];
        for (int p : set) {
            if (p < 1 || p > n || out[p - 1]) {
                throw new IllegalArgumentException("cannot pinch propagator " + p + " of a " + n + "-point integral");
            }
            out[p - 1] = true;
        }
        final int m = n - set.length;
        if (m < 1) {
            throw new IllegalArgumentException("pinching every propagator leaves no integral");
        }
        final Cx[][] t = new Cx[m][m];
        for (int i = 0, a = 0; i < n; i++) {
            if (out[i]) {
                continue;
            }
            for (int j = 0, b = 0; j < n; j++) {
                if (!out[j]) {
                    t[a][b++] = s[i][j];
                }
            }
            a++;
        }
        double[][] r = null;
        if (momenta != null) {
            r = new double[m][];
            for (int i = 0, a = 0; i < n; i++) {
                if (!out[i]) {
                    r[a++] = momenta[i];
                }
            }
        }
        return new Kinematics(t, origin + " pinched " + Arrays.toString(set), r);
    }

    /**
     * The entries of S that are zero but for rounding: not 0, yet at most {@code relative} of the largest.
     * A leg meant to be on its mass shell, computed from four-momenta or typed with the digits of a
     * subtraction, ends up there; Golem95 takes it as a leg that far off shell, which an infrared-divergent
     * integral cannot have, and may give no finite number. (1-based pairs, i <= j.)
     */
    public List<int[]> roundingZeros(double relative) {
        final double limit = relative * scale();
        final List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i; j < n; j++) {
                final double a = Cx.abs(s[i][j]);
                if (a > 0 && a <= limit) {
                    out.add(new int[]{i + 1, j + 1});
                }
            }
        }
        return out;
    }

    /** The same kinematics with the entries that are zero but for rounding made exactly zero. */
    public Kinematics snapped(double relative) {
        final List<int[]> zeros = roundingZeros(relative);
        if (zeros.isEmpty()) {
            return this;
        }
        final Cx[][] t = matrix();
        for (int[] z : zeros) {
            t[z[0] - 1][z[1] - 1] = Cx.ZERO;
            t[z[1] - 1][z[0] - 1] = Cx.ZERO;
        }
        return new Kinematics(t, origin, momenta);
    }

    /** Every S_ij times lambda: in other units of mass squared. A power of 4 changes no bit of the inputs. */
    public Kinematics scaled(double lambda) {
        final Cx[][] t = new Cx[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                t[i][j] = new Cx(s[i][j].re * lambda, s[i][j].im * lambda);
            }
        }
        double[][] r = null;
        if (momenta != null && lambda > 0) {
            r = new double[n][4];
            final double f = Math.sqrt(lambda);
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < 4; k++) {
                    r[i][k] = momenta[i][k] * f;
                }
            }
        }
        return new Kinematics(t, origin + " x " + num(lambda), r);
    }

    /** The integral with its propagators relabelled k -> k+shift (the same integral). */
    public Kinematics rotated(int shift) {
        final Cx[][] t = new Cx[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                t[i][j] = s[Math.floorMod(i + shift, n)][Math.floorMod(j + shift, n)];
            }
        }
        return new Kinematics(t, origin + " (rotated by " + shift + ")");
    }

    /** The integral read backwards (propagator k -> N+1-k): the same integral. */
    public Kinematics reflected() {
        final Cx[][] t = new Cx[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                t[i][j] = s[n - 1 - i][n - 1 - j];
            }
        }
        return new Kinematics(t, origin + " (reflected)");
    }

    public int n() {
        return n;
    }

    public String origin() {
        return origin;
    }

    /** S(i, j), 1-based. */
    public Cx s(int i, int j) {
        return s[i - 1][j - 1];
    }

    public Cx[][] matrix() {
        final Cx[][] t = new Cx[n][];
        for (int i = 0; i < n; i++) {
            t[i] = s[i].clone();
        }
        return t;
    }

    /** m_i^2 = -S_ii / 2. */
    public Cx mass2(int i) {
        return Cx.mul(-0.5, s[i - 1][i - 1]);
    }

    /** p_i^2 = S(i-1, i) + m_{i-1}^2 + m_i^2, the leg between propagators i-1 and i. */
    public Cx leg2(int i) {
        if (n == 1) {
            return Cx.ZERO;
        }
        final int prev = i == 1 ? n : i - 1;
        return Cx.add(Cx.add(s(prev, i), mass2(prev)), mass2(i));
    }

    /** The largest |S_ij|: what "small" is measured against. */
    public double scale() {
        double m = 0;
        for (Cx[] r : s) {
            for (Cx z : r) {
                m = Math.max(m, Cx.abs(z));
            }
        }
        return m;
    }

    public String name() {
        return switch (n) {
            case 1 -> "tadpole";
            case 2 -> "bubble";
            case 3 -> "triangle";
            case 4 -> "box";
            case 5 -> "pentagon";
            default -> "hexagon";
        };
    }

    /* ---------------------------------------------------------------- analysis */

    /**
     * What the integral is and where it is delicate.
     *
     * @param soft        propagators j whose region is soft (massless, both neighbours on their mass shell)
     * @param collinear   pairs (j, j+1) of massless propagators around a light-like leg
     * @param detS        det S: zero on the leading Landau singularity
     * @param detG        the Gram determinant of the propagator momenta
     * @param sumB        B = sum_ij (S^-1)_ij, which Golem95 divides by
     * @param normalizedB |B| max|S_ij|, what Golem95 compares with its cuts (5e-3)
     * @param relativeB   |B| / sum_ij |(S^-1)_ij|: how much of its terms survives in B; near the rounding
     *                    of the inversion, B is zero but for its noise
     * @param condition   ||S|| ||S^-1||, how much the inversion amplifies rounding
     * @param thresholdDistance how far the nearest two-particle cut is from its threshold, in units of
     *                    max|S_ij| (the unit {@link #deformed} moves invariants in); infinite with no massive cut
     */
    public record Analysis(int n, double scale, Cx[] masses2, Cx[] legs2, boolean[] onShellLeg,
                           boolean[] masslessLine, List<Integer> soft, List<int[]> collinear, Cx detS, Cx detG,
                           Cx sumB, double normalizedB, double relativeB, double condition, double thresholdDistance,
                           List<String> thresholds, List<String> notes) {

        /** B vanishes but for the rounding of S^-1: a reduction that divides by it divides noise by noise. */
        public boolean bIsNoise() {
            return n >= 2 && Double.isFinite(relativeB) && relativeB <= 64 * 0x1p-53 * Math.max(1, condition);
        }

        /** det S vanishes (relative to max|S|^N): the leading Landau singularity, or S not invertible. */
        public boolean detSVanishes() {
            return n >= 2 && Cx.abs(detS) <= 1e-12 * Math.pow(Math.max(scale, 1e-300), n);
        }

        /**
         * det S vanishes without the infrared configurations that make it vanish anyway (a massless pair of
         * propagators around a light-like leg gives S two proportional rows): the leading Landau
         * singularity, where the integral itself is singular.
         */
        public boolean landau() {
            return detSVanishes() && soft.isEmpty() && collinear.isEmpty();
        }

        public boolean doublePoleExpected() {
            return !soft.isEmpty();
        }

        public boolean infraredFinite() {
            return soft.isEmpty() && collinear.isEmpty();
        }

        public boolean complexMasses() {
            for (Cx m : masses2) {
                if (m.im != 0) {
                    return true;
                }
            }
            return false;
        }
    }

    private Analysis analysis;

    public synchronized Analysis analyse() {
        if (analysis == null) {
            analysis = computeAnalysis();
        }
        return analysis;
    }

    private Analysis computeAnalysis() {
        final double scale = scale();
        final double tol = 1e-10 * Math.max(scale, 1e-300);
        final Cx[] m2 = new Cx[n];
        final Cx[] p2 = new Cx[n];
        final boolean[] onShell = new boolean[n];
        final boolean[] massless = new boolean[n];
        for (int i = 1; i <= n; i++) {
            m2[i - 1] = mass2(i);
            p2[i - 1] = leg2(i);
            massless[i - 1] = Cx.abs(m2[i - 1]) <= tol;
            onShell[i - 1] = Cx.abs(p2[i - 1]) <= tol;
        }
        final List<Integer> soft = new ArrayList<>();
        final List<int[]> coll = new ArrayList<>();
        if (n >= 3) {
            for (int j = 1; j <= n; j++) {
                final int prev = j == 1 ? n : j - 1;
                final int next = j == n ? 1 : j + 1;
                if (Cx.abs(s(j, j)) <= tol && Cx.abs(s(prev, j)) <= tol && Cx.abs(s(j, next)) <= tol) {
                    soft.add(j);
                }
                if (Cx.abs(s(j, j)) <= tol && Cx.abs(s(next, next)) <= tol && Cx.abs(s(j, next)) <= tol) {
                    coll.add(new int[]{j, next});
                }
            }
        }
        final Cx[][] inv = new Cx[n][n];
        final Cx det = invert(s, inv);
        Cx sumB = Cx.ZERO;
        double normInv = 0;
        double sumAbsInv = 0;
        boolean singular = Cx.abs(det) == 0 || !Double.isFinite(det.re);
        if (!singular) {
            for (int i = 0; i < n; i++) {
                double row = 0;
                for (int j = 0; j < n; j++) {
                    sumB = Cx.add(sumB, inv[i][j]);
                    row += Cx.abs(inv[i][j]);
                }
                normInv = Math.max(normInv, row);
                sumAbsInv += row;
            }
        }
        double normS = 0;
        for (Cx[] r : s) {
            double row = 0;
            for (Cx z : r) {
                row += Cx.abs(z);
            }
            normS = Math.max(normS, row);
        }
        Cx detG = Cx.ONE;
        if (n >= 2) {
            final Cx[][] g = new Cx[n - 1][n - 1];
            for (int i = 0; i < n - 1; i++) {
                for (int j = 0; j < n - 1; j++) {
                    g[i][j] = Cx.sub(Cx.sub(Cx.add(s[i][n - 1], s[j][n - 1]), s[i][j]), s[n - 1][n - 1]);
                }
            }
            detG = invert(g, new Cx[n - 1][n - 1]);
        }
        final List<String> thr = new ArrayList<>();
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 1; i <= n; i++) {
            for (int j = i + 1; j <= n; j++) {
                if (m2[i - 1].im != 0 || m2[j - 1].im != 0) {
                    continue;
                }
                final double mi = Math.sqrt(Math.max(0, m2[i - 1].re));
                final double mj = Math.sqrt(Math.max(0, m2[j - 1].re));
                if (mi == 0 && mj == 0) {
                    continue;
                }
                final double channel = Cx.add(Cx.add(s(i, j), m2[i - 1]), m2[j - 1]).re;
                final double threshold = (mi + mj) * (mi + mj);
                final double rel = (channel - threshold) / threshold;
                nearest = Math.min(nearest, Math.abs(channel - threshold) / Math.max(scale, Double.MIN_NORMAL));
                if (Math.abs(rel) < 1e-2) {
                    thr.add(String.format(Locale.ROOT, "the cut through propagators %d and %d is %s its threshold "
                            + "(m%d + m%d)^2 = %s: (q%d - q%d)^2 - threshold = %s", i, j,
                        Math.abs(rel) < 1e-8 ? "at" : rel > 0 ? "just above" : "just below", i, j, num(threshold),
                        i, j, num(channel - threshold)));
                }
            }
        }
        final List<String> notes = new ArrayList<>();
        final double normB = singular ? Double.NaN : Cx.abs(sumB) * scale;
        final double relB = singular || sumAbsInv == 0 ? Double.NaN : Cx.abs(sumB) / sumAbsInv;
        final double cond = singular ? Double.POSITIVE_INFINITY : normS * normInv;
        final Analysis an = new Analysis(n, scale, m2, p2, onShell, massless, List.copyOf(soft), List.copyOf(coll),
            det, detG, sumB, normB, relB, cond, nearest, List.copyOf(thr), notes);
        final List<int[]> rounding = roundingZeros(SNAP);
        if (!rounding.isEmpty()) {
            final List<String> where = new ArrayList<>();
            for (int[] z : rounding) {
                where.add(String.format(Locale.ROOT, "S(%d,%d) = %s", z[0], z[1], str(s(z[0], z[1]))));
            }
            notes.add(String.join(", ", where) + (rounding.size() == 1 ? " is" : " are") + " zero but for rounding (under "
                + "1e-12 of the largest entry): Golem95 takes " + (rounding.size() == 1 ? "it" : "them") + " as given, a leg "
                + "that far off shell, and may give no finite number; ':golem snap' makes " + (rounding.size() == 1 ? "it" : "them")
                + " exactly 0");
        }
        if (n >= 3) {
            if ((singular || an.detSVanishes()) && an.landau()) {
                notes.add("det S vanishes: the kinematics sits on the leading Landau singularity of this " + name()
                    + ", where the integral itself is singular");
            } else if (singular || an.detSVanishes()) {
                notes.add("det S vanishes, as it does with a massless pair of propagators around a light-like leg: "
                    + "Golem95 has formulas of its own for these, and does not invert S");
            }
            if (an.bIsNoise()) {
                notes.add(String.format(Locale.ROOT, "B = sum (S^-1)_ij is zero but for rounding (%.1e of its terms): "
                    + "a reduction that divides by B divides noise by noise", relB));
            } else if (!singular && normB <= 5e-3 && n <= 4) {
                notes.add(String.format(Locale.ROOT, "|B| max|S| = %.2e is below Golem95's cut 5e-3: the basis "
                    + "integrals are integrated numerically instead of reduced (the Gram determinant is small)", normB));
            }
            if (cond > 1e10 && Double.isFinite(cond) && !an.detSVanishes()) {
                notes.add(String.format(Locale.ROOT, "S is ill-conditioned (||S|| ||S^-1|| = %.1e): about %d of the 16 "
                    + "digits are lost in its inversion", cond, Math.min(16, (int) Math.round(Math.log10(cond)))));
            }
        }
        return new Analysis(n, scale, m2, p2, onShell, massless, an.soft(), an.collinear(), det, detG, sumB, normB,
            relB, cond, nearest, an.thresholds(), List.copyOf(notes));
    }

    /** Gauss-Jordan with partial pivoting; returns det(a) and fills inv (left as is when det = 0). */
    static Cx invert(Cx[][] a, Cx[][] inv) {
        final int n = a.length;
        final Cx[][] m = new Cx[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                m[i][j] = a[i][j];
                m[i][n + j] = i == j ? Cx.ONE : Cx.ZERO;
            }
        }
        Cx det = Cx.ONE;
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) {
                if (Cx.abs(m[r][c]) > Cx.abs(m[piv][c])) {
                    piv = r;
                }
            }
            if (Cx.abs(m[piv][c]) == 0) {
                return Cx.ZERO;
            }
            if (piv != c) {
                final Cx[] t = m[piv];
                m[piv] = m[c];
                m[c] = t;
                det = Cx.neg(det);
            }
            final Cx p = m[c][c];
            det = Cx.mul(det, p);
            for (int j = 0; j < 2 * n; j++) {
                m[c][j] = Cx.div(m[c][j], p);
            }
            for (int r = 0; r < n; r++) {
                if (r == c) {
                    continue;
                }
                final Cx f = m[r][c];
                if (f.re == 0 && f.im == 0) {
                    continue;
                }
                for (int j = 0; j < 2 * n; j++) {
                    m[r][j] = Cx.sub(m[r][j], Cx.mul(f, m[c][j]));
                }
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                inv[i][j] = m[i][n + j];
            }
        }
        return det;
    }

    /** Several lines saying what the integral is, for ':golem show'. */
    public List<String> describe() {
        final Analysis an = analyse();
        final List<String> out = new ArrayList<>();
        int offShell = 0;
        final List<String> legs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (n >= 2 && !an.onShellLeg()[i]) {
                offShell++;
                legs.add("p" + (i + 1) + "^2 = " + str(an.legs2()[i]));
            }
        }
        int massive = 0;
        final List<String> ms = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!an.masslessLine()[i]) {
                massive++;
                ms.add("m" + (i + 1) + "^2 = " + str(an.masses2()[i]));
            }
        }
        out.add(String.format(Locale.ROOT, "%s (%s): %d of %d legs off shell%s; %d massive propagator%s%s%s",
            name(), origin, offShell, n, legs.isEmpty() ? "" : " (" + String.join(", ", legs) + ")", massive,
            massive == 1 ? "" : "s", ms.isEmpty() ? "" : " (" + String.join(", ", ms) + ")",
            an.complexMasses() ? ", complex: unstable particles" : ""));
        if (n >= 3) {
            if (an.infraredFinite()) {
                out.add("infrared: finite (no massless propagator between on-shell legs)");
            } else {
                final List<String> parts = new ArrayList<>();
                for (int j : an.soft()) {
                    parts.add("soft at propagator " + j);
                }
                for (int[] c : an.collinear()) {
                    parts.add("collinear between " + c[0] + " and " + c[1]);
                }
                out.add("infrared: " + String.join(", ", parts) + (an.doublePoleExpected()
                    ? "; the scalar integral has a 1/eps^2 pole" : "; single poles only"));
            }
        }
        if (n >= 2 && Double.isFinite(an.normalizedB())) {
            out.add(String.format(Locale.ROOT, "det S = %s, Gram det G = %s, B = sum (S^-1)_ij = %s",
                str(an.detS()), str(an.detG()), str(an.sumB())));
            out.add(String.format(Locale.ROOT, "conditioning: ||S|| ||S^-1|| = %.3g, |B| max|S| = %.3g%s",
                an.condition(), an.normalizedB(), n <= 4 && n >= 3
                    ? (an.normalizedB() > 5e-3 ? " (Golem95 reduces algebraically)" : " (Golem95 integrates numerically)") : ""));
        } else if (n >= 2) {
            out.add(String.format(Locale.ROOT, "det S = 0, Gram det G = %s: S has no inverse, so no b_i nor B", str(an.detG())));
        }
        out.addAll(an.thresholds());
        out.addAll(an.notes());
        return out;
    }

    static String num(double v) {
        if (v == 0) {
            return "0";
        }
        final double a = Math.abs(v);
        if (a >= 1e-3 && a < 1e6) {
            final String t = String.format(Locale.ROOT, "%.6g", v);
            return t.contains(".") ? t.replaceFirst("0+$", "").replaceFirst("\\.$", "") : t;
        }
        return String.format(Locale.ROOT, "%.4e", v);
    }

    static String str(Cx z) {
        if (z.im == 0) {
            return num(z.re);
        }
        return num(z.re) + (z.im < 0 ? " - " : " + ") + num(Math.abs(z.im)) + "i";
    }

    @Override
    public String toString() {
        final StringBuilder b = new StringBuilder(name() + " S = [");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                b.append("; ");
            }
            for (int j = 0; j < n; j++) {
                if (j > 0) {
                    b.append(' ');
                }
                b.append(str(s[i][j]));
            }
        }
        return b.append(']').toString();
    }
}

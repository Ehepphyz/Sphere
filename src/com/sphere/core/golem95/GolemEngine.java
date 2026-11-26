package com.sphere.core.golem95;

import com.sphere.core.golem95.port.Cx;
import com.sphere.core.golem95.port.FIO;
import com.sphere.core.golem95.port.FStop;
import com.sphere.core.golem95.port.GolemCore;
import com.sphere.core.golem95.port.M_form_factor_type;
import com.sphere.core.golem95.port.M_parametre;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * One Golem95: the translated library (every module of Golem95 1.3.4 and OneLOop 3.6.1 as one
 * {@link GolemCore}) behind a Java interface. Set a {@link Kinematics}, then ask for form factors, basis
 * integrals or the scalar integral by OneLOop; each answer is a {@link Laurent} series together with what
 * Golem95 said while computing it.
 *
 * <p>An engine is used by one thread at a time (its methods are synchronized); engines share nothing,
 * so as many as there are processors compute side by side, which the Fortran library, with its module
 * variables, cannot. Where Golem95 would stop the program, the engine throws a {@link GolemException}
 * with Golem95's own explanation and rebuilds itself before the next computation.
 *
 * <p>Two things differ from calling the Fortran directly, both on purpose. Golem95 configures OneLOop only
 * the first time a program needs it, with the scale mu and an on-shell threshold under which invariants
 * count as zero, and which threshold depends on which integral came first: 1e-10 from the boxes, 100 eps
 * from the finite triangles. An invariant between the two (a leg on shell but for the rounding of a
 * subtraction) then makes the same integral's value depend on what the program computed before; for one
 * pentagon of the validation it changes by a factor of four. The engine sets both itself, the threshold
 * at 100 eps (what the Fortran reference run ended with), the scale at every change of mu^2: a value
 * depends on its question only. And the scalar integrals by OneLOop are computed by a second core, so
 * asking for them changes nothing Golem95 keeps.
 */
public final class GolemEngine {

    /**
     * What Golem95 is asked to compute with.
     *
     * @param mu2          the scale mu^2 of the (mu^2)^eps factor
     * @param rationalOnly only the rational part of each form factor (Golem95's rat_or_tot = rat)
     * @param tolerance    the accuracy of the one-dimensional numerical integrals Golem95 uses where |B| is small
     * @param accuracy     how far a matrix inverse may be from exact before Golem95 tries another method
     * @param cut          the |B| (normalised) below which 3- and 4-point basis integrals are integrated
     *                     numerically instead of reduced (Golem95's coupure_*, 5e-3)
     */
    public record Settings(double mu2, boolean rationalOnly, double tolerance, double accuracy, double cut) {

        public static final Settings DEFAULT = new Settings(1.0, false, 1e-8, 1e-10, 5e-3);

        public Settings {
            if (!(mu2 > 0) || !Double.isFinite(mu2)) {
                throw new IllegalArgumentException("mu^2 must be positive, not " + mu2);
            }
            if (!(tolerance > 0) || !(accuracy > 0)) {
                throw new IllegalArgumentException("the tolerance and the accuracy must be positive");
            }
            if (!(cut >= 0) || cut >= 1) {
                throw new IllegalArgumentException("the cut is between 0 (always reduce) and 1, not " + cut);
            }
        }

        public Settings withMu2(double v) {
            return new Settings(v, rationalOnly, tolerance, accuracy, cut);
        }

        public Settings withRationalOnly(boolean v) {
            return new Settings(mu2, v, tolerance, accuracy, cut);
        }

        public Settings withTolerance(double v) {
            return new Settings(mu2, rationalOnly, v, accuracy, cut);
        }

        public Settings withAccuracy(double v) {
            return new Settings(mu2, rationalOnly, tolerance, v, cut);
        }

        public Settings withCut(double v) {
            return new Settings(mu2, rationalOnly, tolerance, accuracy, v);
        }

        public List<String> describe() {
            return List.of(
                String.format(Locale.ROOT, "mu^2        %s", Kinematics.num(mu2)),
                "parts       " + (rationalOnly ? "the rational part only" : "rational and cut-constructible (everything)"),
                String.format(Locale.ROOT, "tolerance   %.1e  (numerical integrals where |B| is small)", tolerance),
                String.format(Locale.ROOT, "accuracy    %.1e  (matrix inverses)", accuracy),
                String.format(Locale.ROOT, "cut         %.1e  (|B| below which 3- and 4-point integrals are integrated numerically)", cut));
        }
    }

    /**
     * An answer: the series, what it is, what Golem95 wrote while computing it, whether it raised its own
     * accuracy warning (not_enough_accuracy), and how long it took.
     */
    public record Value(String what, Laurent laurent, List<String> said, boolean accuracyWarning, long nanos) {

        public Cx a() {
            return laurent.a();
        }

        public Cx b() {
            return laurent.b();
        }

        public Cx c() {
            return laurent.c();
        }
    }

    /** Golem95 gave up (a STOP in the Fortran), or the translation met a state it cannot handle. */
    public static final class GolemException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final transient List<String> said;

        GolemException(String message, List<String> said, Throwable cause) {
            super(message, cause);
            this.said = List.copyOf(said);
        }

        /** What Golem95 wrote before it gave up. */
        public List<String> said() {
            return said;
        }
    }

    private GolemCore core;
    private GolemCore olo;
    private Settings settings;
    private Kinematics kinematics;
    private boolean prepared;
    private boolean broken;
    private long evaluations;
    private long nanos;

    public GolemEngine() {
        this(Settings.DEFAULT);
    }

    public GolemEngine(Settings settings) {
        this.core = fresh();
        configure(settings);
    }

    /**
     * OneLOop's on-shell threshold, as Golem95's finite triangles set it (100 epsilon(1._ki)); its boxes would
     * set 1e-10 had they been first. Absolute, as in Golem95: Sphere's snapping takes care of rounding zeros.
     */
    static final double ONSHELL = 100.0 * 2.220446049250313E-16;

    /** OneLOop configured as Golem95 does on its first call, and marked done, so no later call changes it. */
    private static void pinOneLoop(GolemCore c, double mu2) {
        c.m_avh_olo.olo_onshell(ONSHELL);
        c.m_avh_olo.olo_scale(Math.sqrt(mu2));
        c.m_parametre.olo = true;
    }

    private static GolemCore fresh() {
        final GolemCore c = new GolemCore();
        // Golem95's warnings go to the engine's record (never to a terminal): attached to each answer
        c.m_parametre.if_print_warn_par = true;
        return c;
    }

    /** Applies settings; the integrals already computed for the kinematics are forgotten if they change. */
    public synchronized GolemEngine configure(Settings s) {
        Objects.requireNonNull(s, "settings");
        final Settings old = settings;
        settings = s;
        final M_parametre p = core.m_parametre;
        p.mu2_scale_par = s.mu2();
        p.rat_or_tot_par.assign(s.rationalOnly() ? M_parametre.rat : M_parametre.tot);
        p.tolerance = s.tolerance();
        p.accuracy_par = s.accuracy();
        p.coupure_3p1m_2mi = s.cut();
        p.coupure_3p2m = s.cut();
        p.coupure_3p3m = s.cut();
        p.coupure_4p1m = s.cut();
        p.coupure_4p2m_opp = s.cut();
        p.coupure_4p2m_adj = s.cut();
        p.coupure_4p3m = s.cut();
        p.coupure_3p2m_1mi = s.cut();
        if (old == null || old.mu2() != s.mu2()) {
            // Golem95 configures OneLOop once, with a threshold that depends on its first caller: fixed here
            pinOneLoop(core, s.mu2());
        }
        if (prepared && !s.equals(old)) {
            core.m_cache.reset_cache();
        }
        return this;
    }

    public synchronized Settings settings() {
        return settings;
    }

    public synchronized Kinematics kinematics() {
        return kinematics;
    }

    /** How many answers this engine gave, and the time they took. */
    public synchronized String usage() {
        return String.format(Locale.ROOT, "%d evaluation%s, %.3f s", evaluations, evaluations == 1 ? "" : "s", nanos / 1e9);
    }

    /**
     * Hands the kinematics to Golem95: its S matrix, inverses and b_i are prepared once for every question.
     * The same kinematics again keeps what Golem95 has computed for it (its cache of basis integrals).
     */
    public synchronized GolemEngine set(Kinematics k) {
        Objects.requireNonNull(k, "kinematics");
        if (prepared && !broken && k == kinematics) {
            return this;
        }
        if (broken) {
            rebuild();
        }
        if (prepared) {
            try {
                core.m_matrice_s.exitgolem95();
            } catch (RuntimeException e) {
                rebuild();
            }
            prepared = false;
        }
        kinematics = null;
        core.io.drain();
        final int n = k.n();
        try {
            core.m_matrice_s.initgolem95(n, null);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    core.m_matrice_s.s_mat.a[i][j] = k.s(i + 1, j + 1);
                }
            }
            core.m_matrice_s.preparesmatrix();
        } catch (RuntimeException e) {
            broken = true;
            final List<String> said = said(core.io.drain());
            throw new GolemException("Golem95 cannot take this S matrix" + (said.isEmpty() ? ": " + e : ": "
                + String.join(" ", said)), said, e);
        }
        prepared = true;
        kinematics = k;
        return this;
    }

    private void rebuild() {
        core = fresh();
        prepared = false;
        broken = false;
        final Settings s = settings;
        settings = null;
        configure(s);
    }

    private Kinematics requireKinematics() {
        if (kinematics == null) {
            throw new IllegalStateException("no kinematics: give Golem95 one first");
        }
        if (broken) {
            set(kinematics);
        }
        return kinematics;
    }

    /* ---------------------------------------------------------------- form factors */

    /**
     * The form factor A, B, C or D with these labels: rank = labels + 2 (B), + 4 (C) or + 6 (D), for the integral
     * left once the propagators of {@code pinched} are removed.
     */
    public synchronized Value formFactor(char type, int[] labels, int[] pinched) {
        final int n = requireKinematics().n();
        final int[] pin = pinched == null ? new int[0] : pinched.clone();
        final int[] l = labels == null ? new int[0] : labels.clone();
        checkLabels(n, l, pin);
        final char t = Character.toLowerCase(type);
        final int g = "abcd".indexOf(t);
        if (g < 0) {
            throw new IllegalArgumentException("form factors are A, B, C or D, not " + type);
        }
        final int points = n - pin.length;
        final int rank = l.length + 2 * g;
        final String missing = missing(t, points, rank);
        if (missing != null) {
            throw new IllegalArgumentException(missing);
        }
        final String what = new Quantity.FormFactor(t, l, pin).label(n);
        final Method named = named(t, points, rank);
        return run(what, () -> named != null
            ? invoke(named, owner(points), l, pin)
            : core.m_form_factor_higher_ranks.ffSet(g, points, rank, pin, l));
    }

    /**
     * The tensor integral of this rank, every component, built from Golem95's form factors (the kinematics
     * must have its propagators' momenta; see {@link Tensors}).
     */
    public synchronized Tensors.Tensor tensor(int rank, int[] pinched) {
        return tensor(rank, pinched, Tensors.Labels.SYMMETRIZED);
    }

    /** The same, its form factors above rank N taken as {@code labels} says (see {@link Tensors.Labels}). */
    public synchronized Tensors.Tensor tensor(int rank, int[] pinched, Tensors.Labels labels) {
        requireKinematics();
        return Tensors.compute(this, rank, pinched, labels);
    }

    /** Parses and computes ("a43 1 2 3", "b42", "int n+2 1 2", "dd0012", "scalar", "... / 4"). */
    public synchronized Value compute(String quantity) {
        return Quantity.parse(quantity, requireKinematics().n()).on(this);
    }

    /** The highest rank Golem95 defines a form factor of for N points. */
    public static int maxRank(int points) {
        return points <= 4 ? points + 10 : points == 5 ? 6 : 7;
    }

    /** Why Golem95 has no such form factor, or null if it has. */
    public static String missing(char type, int points, int rank) {
        if (points < 1 || points > 6) {
            return "Golem95 computes 1- to 6-point integrals, not " + points;
        }
        final int g = "abcd".indexOf(Character.toLowerCase(type));
        if (g < 0) {
            return "form factors are A, B, C or D";
        }
        if (g > 0 && points == 6) {
            return "Golem95 defines only A form factors for six points";
        }
        if (rank < 2 * g) {
            return Character.toUpperCase(type) + " form factors start at rank " + (2 * g);
        }
        if (rank > maxRank(points)) {
            return String.format(Locale.ROOT, "Golem95 goes up to rank %d for %d-point integrals, not %d", maxRank(points),
                points, rank);
        }
        return null;
    }

    private static final Map<String, Optional<Method>> NAMED = new ConcurrentHashMap<>();

    /** Golem95's own function for it (a43_s ...), or null if it is one of the higher ranks. */
    private static Method named(char type, int points, int rank) {
        if (rank > points) {
            return null;
        }
        final String name = "" + type + points + rank + "_s";
        return NAMED.computeIfAbsent(name, k -> {
            final int g = "abcd".indexOf(type);
            final Class<?>[] params = new Class<?>[rank - 2 * g + 1];
            Arrays.fill(params, int.class);
            params[params.length - 1] = int[].class;
            try {
                return Optional.of(ownerClass(points).getMethod(k, params));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private static Class<?> ownerClass(int points) {
        return switch (points) {
            case 1 -> com.sphere.core.golem95.port.M_form_factor_1p.class;
            case 2 -> com.sphere.core.golem95.port.M_form_factor_2p.class;
            case 3 -> com.sphere.core.golem95.port.M_form_factor_3p.class;
            case 4 -> com.sphere.core.golem95.port.M_form_factor_4p.class;
            case 5 -> com.sphere.core.golem95.port.M_form_factor_5p.class;
            default -> com.sphere.core.golem95.port.M_form_factor_6p.class;
        };
    }

    private Object owner(int points) {
        return switch (points) {
            case 1 -> core.m_form_factor_1p;
            case 2 -> core.m_form_factor_2p;
            case 3 -> core.m_form_factor_3p;
            case 4 -> core.m_form_factor_4p;
            case 5 -> core.m_form_factor_5p;
            default -> core.m_form_factor_6p;
        };
    }

    private static M_form_factor_type.T_form_factor invoke(Method m, Object owner, int[] labels, int[] pinched) {
        final Object[] args = new Object[labels.length + 1];
        for (int i = 0; i < labels.length; i++) {
            args[i] = labels[i];
        }
        args[labels.length] = pinched;
        try {
            return (M_form_factor_type.T_form_factor) m.invoke(owner, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /* ---------------------------------------------------------------- basis integrals */

    /** I_N^{n+dimPlus}(z_l1 ... z_lr): Golem95's generic basis integral (fnp_generic). */
    public synchronized Value integral(int dimPlus, int[] labels, int[] pinched) {
        final int n = requireKinematics().n();
        final int[] pin = pinched == null ? new int[0] : pinched.clone();
        final int[] l = labels == null ? new int[0] : labels.clone();
        checkLabels(n, l, pin);
        if (dimPlus < 0 || dimPlus % 2 != 0 || dimPlus > 2 * maxRank(n)) {
            throw new IllegalArgumentException("the dimension is n + 2g with g = 0, 1, 2 ...: n+" + dimPlus + " is not one");
        }
        final int points = n - pin.length;
        final String what = new Quantity.Integral(dimPlus, l, pin).label(n);
        return run(what, () -> core.m_generic_function_np.fnp_generic(points, dimPlus, core.m_array.packb(pin),
            l.length, l, null));
    }

    private static void checkLabels(int n, int[] labels, int[] pinched) {
        final boolean[] out = new boolean[n + 1];
        for (int p : pinched) {
            if (p < 1 || p > n) {
                throw new IllegalArgumentException("there is no propagator " + p + " to pinch: they are 1.." + n);
            }
            if (out[p]) {
                throw new IllegalArgumentException("propagator " + p + " is pinched twice");
            }
            out[p] = true;
        }
        if (pinched.length >= n) {
            throw new IllegalArgumentException("pinching every propagator leaves no integral");
        }
        for (int l : labels) {
            if (l < 1 || l > n) {
                throw new IllegalArgumentException("label " + l + " is not a propagator: they are 1.." + n);
            }
            if (out[l]) {
                throw new IllegalArgumentException("label " + l + " is a pinched propagator");
            }
        }
    }

    /* ---------------------------------------------------------------- OneLOop */

    /**
     * The scalar integral by OneLOop (1 to 4 propagators): independent of Golem95's reduction, the
     * check of its A^{N,0}. OneLOop numbers the propagators from the one before leg 1, Golem95 from the
     * one after it: OneLOop's (1, 2, ..., N) are Golem95's (N, 1, ..., N-1).
     */
    public synchronized Value scalar(int[] pinched) {
        final Kinematics k = requireKinematics().pinched(pinched);
        final int n = k.n();
        if (n > 4) {
            throw new IllegalArgumentException("OneLOop computes 1- to 4-point scalar integrals; this one has " + n + " points");
        }
        if (olo == null) {
            olo = fresh();
            olo.m_avh_olo.olo_onshell(ONSHELL);
        }
        final int[] o = new int[n];
        o[0] = n;
        for (int i = 1; i < n; i++) {
            o[i] = i;
        }
        final Cx[] m = new Cx[n];
        for (int i = 0; i < n; i++) {
            m[i] = k.mass2(o[i]);
        }
        final double rmu = Math.sqrt(settings.mu2());
        final String what = "scalar " + k.name() + " by OneLOop" + (pinched == null || pinched.length == 0 ? ""
            : " (" + new Quantity.Scalar(pinched).label().replace("scalar integral by OneLOop ", "") + ")");
        olo.io.drain();
        final long t0 = System.nanoTime();
        final Cx[] r = {Cx.ZERO, Cx.ZERO, Cx.ZERO};
        try {
            switch (n) {
                case 1 -> olo.m_avh_olo_dp.a0cr(r, m[0], rmu);
                case 2 -> olo.m_avh_olo_dp.b0ccr(r, p(k, o, 0, 1), m[0], m[1], rmu);
                case 3 -> olo.m_avh_olo_dp.c0ccr(r, p(k, o, 0, 1), p(k, o, 1, 2), p(k, o, 2, 0), m[0], m[1], m[2], rmu);
                default -> olo.m_avh_olo_dp.d0ccr(r, p(k, o, 0, 1), p(k, o, 1, 2), p(k, o, 2, 3), p(k, o, 3, 0),
                    p(k, o, 0, 2), p(k, o, 1, 3), m[0], m[1], m[2], m[3], rmu);
            }
        } catch (RuntimeException e) {
            final List<String> said = said(olo.io.drain());
            olo = null;
            throw new GolemException("OneLOop failed computing the " + what + ": " + e, said, e);
        }
        final long dt = System.nanoTime() - t0;
        account(dt);
        return new Value(what, new Laurent(r[2], r[1], r[0], settings.mu2()), said(olo.io.drain()), false, dt);
    }

    /** (k_a - k_b)^2 in OneLOop's numbering: S + m^2 + m^2 of the corresponding Golem95 propagators. */
    private static Cx p(Kinematics k, int[] o, int a, int b) {
        return Cx.add(Cx.add(k.s(o[a], o[b]), k.mass2(o[a])), k.mass2(o[b]));
    }

    /* ---------------------------------------------------------------- Golem95's own view */

    /** b_i = sum_j (S^-1)_ij and B = sum b_i as Golem95 computed them for its reduction (1-based). */
    public synchronized Cx[] b() {
        final int n = requireKinematics().n();
        final Cx[] out = new Cx[n + 1];
        if (n < 2) {
            Arrays.fill(out, Cx.ZERO);
            return out;
        }
        for (int i = 1; i <= n; i++) {
            out[i - 1] = core.m_matrice_s.b(i, 0);
        }
        out[n] = core.m_matrice_s.sumb(0);
        return out;
    }

    /* ---------------------------------------------------------------- running */

    private Value run(String what, Supplier<M_form_factor_type.T_form_factor> body) {
        core.io.drain();
        core.m_parametre.not_enough_accuracy_par = false;
        final long t0 = System.nanoTime();
        final M_form_factor_type.T_form_factor r;
        try {
            r = body.get();
        } catch (FStop e) {
            broken = true;
            final List<String> said = said(core.io.drain());
            throw new GolemException("Golem95 gave up on " + what + (said.isEmpty() ? "" : ": " + String.join(" ", said)),
                said, e);
        } catch (RuntimeException e) {
            broken = true;
            final List<String> said = said(core.io.drain());
            throw new GolemException("the translated Golem95 failed on " + what + ": " + e, said, e);
        }
        final long dt = System.nanoTime() - t0;
        account(dt);
        return new Value(what, new Laurent(r.a, r.b, r.c, settings.mu2()), said(core.io.drain()),
            core.m_parametre.not_enough_accuracy_par, dt);
    }

    private void account(long dt) {
        evaluations++;
        nanos += dt;
    }

    /** The lines Golem95 wrote, without its banners and OneLOop's greeting (which asks for citations). */
    static List<String> said(List<FIO.Line> lines) {
        final List<String> out = new ArrayList<>();
        for (FIO.Line l : lines) {
            final String t = l.text().strip();
            if (t.isEmpty() || t.startsWith("#")
                || t.chars().allMatch(ch -> ch == '+' || Character.isLetter(ch) && Character.isUpperCase(ch))) {
                continue;
            }
            out.add(t.replaceAll("\\s{2,}", " "));
        }
        return out;
    }
}

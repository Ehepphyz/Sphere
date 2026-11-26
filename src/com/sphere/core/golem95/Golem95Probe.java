package com.sphere.core.golem95;

import com.sphere.core.golem95.port.Cx;

import java.util.Locale;
import java.util.Map;

/**
 * What ':golem ping' asks Golem95: real integrals, held to what is known of them exactly, not to numbers
 * recorded from an earlier run.
 *
 * <ul>
 *   <li>the massless box, whose Laurent series is known in closed form:
 *       4/(st)/eps^2 - 2(log(-s) + log(-t))/(st)/eps + (2 log(-s) log(-t) - pi^2)/(st);</li>
 *   <li>the tadpole A0(m^2) = m^2 (1/eps + 1 - log m^2) and the bubble B0(0; m, m) = 1/eps - log m^2;</li>
 *   <li>Golem95's own box against OneLOop's, two independent programs, on a box with four masses;</li>
 *   <li>the box at another scale mu^2 equal to the series re-expanded.</li>
 * </ul>
 */
public final class Golem95Probe {

    private Golem95Probe() {
    }

    /** Runs the probe; returns what was checked, throws on a wrong answer. */
    public static String check() {
        final GolemEngine e = new GolemEngine();
        final Cx z = Cx.ZERO;
        final double s = 200;
        final double t = -123;
        final Kinematics box = Kinematics.fromInvariants(4, new double[]{0, 0, 0, 0}, Map.of("s", s, "t", t),
            new Cx[]{z, z, z, z}, "massless box");
        final Laurent a40 = e.set(box).formFactor('a', new int[0], null).laurent();
        final Cx ls = new Cx(Math.log(s), -Math.PI);
        final Cx lt = Cx.of(Math.log(-t));
        final double st = s * t;
        final Laurent exact = new Laurent(Cx.of(4 / st), Cx.mul(-2 / st, Cx.add(ls, lt)),
            Cx.div(Cx.sub(Cx.mul(2, Cx.mul(ls, lt)), Cx.of(Math.PI * Math.PI)), Cx.of(st)), 1);
        require(a40.distance(exact) < 1e-13, "the massless box is off its closed form by %.1e", a40.distance(exact));

        final double m2 = 10;
        final Laurent a10 = e.set(Kinematics.tadpole(Cx.of(m2))).formFactor('a', new int[0], null).laurent();
        final Laurent a0 = new Laurent(z, Cx.of(m2), Cx.of(m2 * (1 - Math.log(m2))), 1);
        require(a10.distance(a0) < 1e-14, "A0 is off m^2 (1/eps + 1 - log m^2) by %.1e", a10.distance(a0));
        final Laurent a20 = e.set(Kinematics.bubble(0, Cx.of(m2), Cx.of(m2))).formFactor('a', new int[0], null).laurent();
        final Laurent b0 = new Laurent(z, Cx.ONE, Cx.of(-Math.log(m2)), 1);
        require(a20.distance(b0) < 1e-14, "B0(0; m, m) is off 1/eps - log m^2 by %.1e", a20.distance(b0));

        final Kinematics massive = Kinematics.fromInvariants(4, new double[]{1, 2, 3, 4}, Map.of("s", 20.0, "t", -5.0),
            new Cx[]{Cx.of(0.3), Cx.of(0.7), Cx.of(1.1), Cx.of(1.9)}, "massive box");
        e.set(massive);
        final Laurent golem = e.formFactor('a', new int[0], null).laurent();
        final Laurent olo = e.scalar(null).laurent();
        require(golem.distance(olo) < 1e-12, "Golem95's box and OneLOop's differ by %.1e", golem.distance(olo));

        final Laurent at37 = e.configure(e.settings().withMu2(37)).set(box).formFactor('a', new int[0], null).laurent();
        require(at37.distance(exact.atScale(37)) < 1e-13, "the box at mu^2 = 37 is not the series re-expanded (%.1e)",
            at37.distance(exact.atScale(37)));
        return "the massless box exact to 1e-13, A0 and B0 exact, Golem95's box = OneLOop's, the mu^2 dependence exact";
    }

    private static void require(boolean ok, String what, Object... args) {
        if (!ok) {
            throw new IllegalStateException(String.format(Locale.ROOT, what, args));
        }
    }
}

package com.sphere.core.golem95;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Something Golem95 computes for a kinematics: a form factor A, B, C or D of the tensor decomposition, a
 * basis integral I_N^{n+2g}(z_l ...) with Feynman parameters in its numerator, or the scalar integral
 * computed independently by OneLOop. Each knows how it changes when the propagators are renamed and
 * when every invariant is scaled, which is what lets Sphere check a value against itself.
 */
public sealed interface Quantity permits Quantity.FormFactor, Quantity.Integral, Quantity.Scalar {

    /** As Golem95 writes it, N for the number of propagators: A^{N,2}(1,3), I_N^{n+2}(z1 z3). */
    String label();

    /** As Golem95 writes it for a kinematics of n propagators: A^{4,2}(1,3), I_4^{n+2}(z1 z3). */
    default String label(int n) {
        final String points = Integer.toString(n - pinched().length);
        return label().replace("^{N,", "^{" + points + ",").replace("I_N^", "I_" + points + "^");
    }

    /** Its value for the engine's kinematics. */
    GolemEngine.Value on(GolemEngine engine);

    /** The same quantity once propagator i is named perm[i-1] (1-based). */
    Quantity relabelled(int[] perm);

    /** The power of lambda it scales with when every S_ij and mu^2 are multiplied by lambda. */
    int scaling(int n);

    /** The propagators removed from the kinematics' S matrix before computing. */
    int[] pinched();

    /** The scalar integral (rank 0) it equals, which OneLOop can check, or false. */
    boolean isScalar();

    /**
     * A^{N,r}, B^{N,r}, C^{N,r} or D^{N,r} with its labels: Golem95's form factors, N the number of propagators
     * left once those of {@code pinched} are removed.
     */
    record FormFactor(char type, int[] labels, int[] pinched) implements Quantity {

        public FormFactor {
            type = Character.toLowerCase(type);
            if (type != 'a' && type != 'b' && type != 'c' && type != 'd') {
                throw new IllegalArgumentException("form factors are A, B, C or D, not " + type);
            }
            labels = labels == null ? new int[0] : labels.clone();
            pinched = pinched == null ? new int[0] : pinched.clone();
        }

        public int generation() {
            return type - 'a';
        }

        public int rank() {
            return labels.length + 2 * generation();
        }

        @Override
        public String label() {
            return Character.toUpperCase(type) + "^{N," + rank() + "}" + list(labels) + pins(pinched);
        }

        @Override
        public GolemEngine.Value on(GolemEngine engine) {
            return engine.formFactor(type, labels, pinched);
        }

        @Override
        public Quantity relabelled(int[] perm) {
            return new FormFactor(type, map(labels, perm), map(pinched, perm));
        }

        @Override
        public int scaling(int n) {
            return 2 + generation() - (n - pinched.length);
        }

        @Override
        public boolean isScalar() {
            return type == 'a' && labels.length == 0;
        }

        @Override
        public String toString() {
            return label();
        }
    }

    /** The basis integral I_N^{n+dimPlus}(z_l1 ... z_lr), which every form factor is made of. */
    record Integral(int dimPlus, int[] labels, int[] pinched) implements Quantity {

        public Integral {
            if (dimPlus < 0 || dimPlus % 2 != 0) {
                throw new IllegalArgumentException("the dimension is n + 2g, g = 0, 1, 2 ...: n+" + dimPlus + " is not one");
            }
            labels = labels == null ? new int[0] : labels.clone();
            pinched = pinched == null ? new int[0] : pinched.clone();
        }

        @Override
        public String label() {
            final StringBuilder z = new StringBuilder();
            for (int l : labels) {
                z.append(z.length() == 0 ? "" : " ").append('z').append(l);
            }
            return "I_N^{n" + (dimPlus == 0 ? "" : "+" + dimPlus) + "}(" + (z.length() == 0 ? "1" : z) + ")" + pins(pinched);
        }

        @Override
        public GolemEngine.Value on(GolemEngine engine) {
            return engine.integral(dimPlus, labels, pinched);
        }

        @Override
        public Quantity relabelled(int[] perm) {
            return new Integral(dimPlus, map(labels, perm), map(pinched, perm));
        }

        @Override
        public int scaling(int n) {
            return 2 + dimPlus / 2 - (n - pinched.length);
        }

        @Override
        public boolean isScalar() {
            return dimPlus == 0 && labels.length == 0;
        }

        @Override
        public String toString() {
            return label();
        }
    }

    /** The scalar integral by OneLOop, independent of Golem95's reduction (1 to 4 propagators). */
    record Scalar(int[] pinched) implements Quantity {

        public Scalar {
            pinched = pinched == null ? new int[0] : pinched.clone();
        }

        @Override
        public String label() {
            return "scalar integral by OneLOop" + pins(pinched);
        }

        @Override
        public GolemEngine.Value on(GolemEngine engine) {
            return engine.scalar(pinched);
        }

        @Override
        public Quantity relabelled(int[] perm) {
            return new Scalar(map(pinched, perm));
        }

        @Override
        public int scaling(int n) {
            return 2 - (n - pinched.length);
        }

        @Override
        public boolean isScalar() {
            return true;
        }

        @Override
        public String toString() {
            return label();
        }
    }

    /**
     * Reads a quantity as the commands write it:
     * <pre>
     *   a43 1 2 3        A^{4,3}(1,2,3) (the digits after the letter: points and rank, as Golem95 names it)
     *   b 1 2            B with labels 1 2 (points from the kinematics, rank from the labels)
     *   int n+2 1 3      I_N^{n+2}(z1 z3)
     *   dd0012           LoopTools' name: B^{4,4}(1,2) (a pair of zeros per g^{mu nu}, then the indices)
     *   scalar           the scalar integral by OneLOop
     *   ... /2 4         the same with propagators 2 and 4 pinched
     * </pre>
     *
     * @param n the number of propagators of the kinematics it is for, or -1 if unknown
     */
    static Quantity parse(String text, int n) {
        final String[] parts = text.trim().split("/", 2);
        final int[] pinched = parts.length > 1 ? ints(parts[1].trim().isEmpty() ? new String[0] : parts[1].trim().split("[\\s,]+")) : new int[0];
        final String[] w = parts[0].trim().split("[\\s,]+");
        if (w.length == 0 || w[0].isEmpty()) {
            throw new IllegalArgumentException("what should be computed? a form factor (a40, b42 1, c44), an integral (int n+2 1 2) or scalar");
        }
        final String head = w[0].toLowerCase(Locale.ROOT);
        if (head.equals("int") || head.equals("i") || head.equals("integral")) {
            int dim = 0;
            int from = 1;
            if (w.length > 1 && w[1].toLowerCase(Locale.ROOT).startsWith("n")) {
                final String d = w[1].substring(1).replace("+", "").trim();
                try {
                    dim = d.isEmpty() ? 0 : Integer.parseInt(d);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("'" + w[1] + "' is not a dimension (n, n+2, n+4 ...)");
                }
                from = 2;
            }
            return new Integral(dim, ints(Arrays.copyOfRange(w, from, w.length)), pinched);
        }
        final int[] rest = ints(Arrays.copyOfRange(w, 1, w.length));
        if (head.equals("scalar") || head.equals("oneloop") || head.equals("olo")) {
            if (rest.length > 0) {
                throw new IllegalArgumentException("the scalar integral has no labels");
            }
            return new Scalar(pinched);
        }
        if (LoopTools.is(head)) {
            if (rest.length > 0) {
                throw new IllegalArgumentException("a LoopTools name carries its indices: " + head + ", not " + head + " "
                    + String.join(" ", Arrays.copyOfRange(w, 1, w.length)));
            }
            return LoopTools.formFactor(head, n, pinched);
        }
        final char type = head.charAt(0);
        if ("abcd".indexOf(type) < 0) {
            throw new IllegalArgumentException("'" + w[0] + "' is neither a form factor (a, b, c, d), an integral (int) nor scalar");
        }
        final String digits = head.substring(1);
        if (!digits.isEmpty()) {
            if (!digits.matches("\\d{2,3}")) {
                throw new IllegalArgumentException("'" + w[0] + "' is not a Golem95 form factor name (a40, b42, c44, a45, a410 ...)");
            }
            final int points = digits.charAt(0) - '0';
            final int rank = Integer.parseInt(digits.substring(1));
            final int g = type - 'a';
            if (n >= 0 && points != n - pinched.length) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "%s is a %d-point form factor, but the kinematics has %d propagators%s", w[0], points, n,
                    pinched.length == 0 ? "" : " less " + pinched.length + " pinched"));
            }
            if (rest.length != rank - 2 * g) {
                throw new IllegalArgumentException(String.format(Locale.ROOT, "%s takes %d label%s (rank %d%s), not %d",
                    w[0], rank - 2 * g, rank - 2 * g == 1 ? "" : "s", rank,
                    g == 0 ? "" : g == 1 ? " less the 2 of g^{mu nu}" : g == 2 ? " less the 4 of two g^{mu nu}" : " less the 6 of three g^{mu nu}", rest.length));
            }
        }
        return new FormFactor(type, rest, pinched);
    }

    private static int[] ints(String[] w) {
        final List<Integer> out = new ArrayList<>();
        for (String s : w) {
            if (s.isBlank()) {
                continue;
            }
            try {
                out.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + s + "' is not a propagator label (1 ... N)");
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] map(int[] labels, int[] perm) {
        final int[] out = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            out[i] = perm[labels[i] - 1];
        }
        return out;
    }

    private static String list(int[] l) {
        if (l.length == 0) {
            return "";
        }
        final StringBuilder b = new StringBuilder("(");
        for (int i = 0; i < l.length; i++) {
            b.append(i == 0 ? "" : ",").append(l[i]);
        }
        return b.append(')').toString();
    }

    private static String pins(int[] p) {
        if (p.length == 0) {
            return "";
        }
        final StringBuilder b = new StringBuilder(" without propagator");
        b.append(p.length == 1 ? " " : "s ");
        for (int i = 0; i < p.length; i++) {
            b.append(i == 0 ? "" : i == p.length - 1 ? " and " : ", ").append(p[i]);
        }
        return b.toString();
    }
}

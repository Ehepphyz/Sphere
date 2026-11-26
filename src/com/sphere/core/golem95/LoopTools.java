package com.sphere.core.golem95;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LoopTools' names for the coefficients of the tensor decomposition (A0, B1, C00, D0012, E00123 ...), read as
 * the Golem95 form factors they are: as Golem95's g*0i functions read them, a pair of zeros for each g^{mu nu}
 * and the other digits the momenta's indices, so dd0012 is B^{4,4}(1,2) and ee00003 is C^{5,5}(3).
 *
 * <p>The momenta follow Golem95's g*0i: LoopTools' first propagator, the one whose momentum is 0, is Golem95's
 * propagator N, and index i stands for the momentum r_i of Golem95's propagator i (LoopTools' k_i). Unlike the
 * g*0i, which copy the name into a buffer one character too short for the highest rank of A, B, C and D (aa00,
 * bb001, cc0000 and dd00001 become aa0, bb00, cc000 and dd0000, the third of which stops the program), every
 * rank Golem95 has is read here, D form factors (dd000000) included.
 */
public final class LoopTools {

    private LoopTools() {
    }

    private static final Pattern NAME = Pattern.compile("([a-f])\\1(\\d+)");

    /** Whether the text is a LoopTools name (two equal letters a..f, then digits). */
    public static boolean is(String text) {
        return NAME.matcher(text.trim().toLowerCase(Locale.ROOT)).matches();
    }

    /** The form factor a LoopTools name stands for, the integral having n propagators less the pinched ones. */
    public static Quantity.FormFactor formFactor(String name, int n, int[] pinched) {
        final Matcher m = NAME.matcher(name.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException("'" + name + "' is not a LoopTools name (aa0, bb1, cc00, dd0012, ee00123 ...)");
        }
        final int points = m.group(1).charAt(0) - 'a' + 1;
        final int left = n - (pinched == null ? 0 : pinched.length);
        if (n >= 0 && points != left) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "%s is a %d-point coefficient (%s), but the "
                + "kinematics has %d propagator%s", name, points, letters(points), left, left == 1 ? "" : "s"));
        }
        final String digits = m.group(2);
        if (digits.equals("0")) {
            return new Quantity.FormFactor('a', new int[0], pinched);
        }
        int zeros = 0;
        final List<Integer> labels = new ArrayList<>();
        for (char c : digits.toCharArray()) {
            if (c == '0') {
                zeros++;
            } else {
                final int l = c - '0';
                if (l > points) {
                    throw new IllegalArgumentException("index " + l + " in " + name + ": a " + points + "-point integral has "
                        + "momenta 1.." + points);
                }
                labels.add(l);
            }
        }
        if (zeros % 2 != 0) {
            throw new IllegalArgumentException("in " + name + " the zeros come in pairs (one pair per g^{mu nu})");
        }
        if (zeros > 6) {
            throw new IllegalArgumentException("Golem95 has form factors with up to three g^{mu nu}, not " + zeros / 2);
        }
        final char type = (char) ('a' + zeros / 2);
        final int[] l = labels.stream().mapToInt(Integer::intValue).toArray();
        final int rank = digits.length();
        final String missing = GolemEngine.missing(type, points, rank);
        if (missing != null) {
            throw new IllegalArgumentException(name + ": " + missing);
        }
        return new Quantity.FormFactor(type, l, pinched);
    }

    private static String letters(int points) {
        return switch (points) {
            case 1 -> "aa: A";
            case 2 -> "bb: B";
            case 3 -> "cc: C";
            case 4 -> "dd: D";
            case 5 -> "ee: E";
            default -> "ff: F";
        };
    }

    /** The LoopTools name of a form factor, if it has one (the momenta's indices 1..N). */
    public static String name(Quantity.FormFactor f, int points) {
        final StringBuilder b = new StringBuilder();
        final char letter = (char) ('a' + points - 1);
        b.append(letter).append(letter);
        if (f.rank() == 0) {
            return b.append('0').toString();
        }
        b.append("0".repeat(2 * f.generation()));
        for (int l : f.labels()) {
            b.append(l);
        }
        return b.toString();
    }
}

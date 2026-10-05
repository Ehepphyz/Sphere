package com.sphere.core.hepmc3.search;

import java.util.Locale;
import java.util.Map;

/**
 * A cut written as text, made into the Filter HepMC3's search library would
 * build in C++: {@code status==1 && pt>10 && abs(eta)<2.5} is
 * {@code (STATUS == 1) && (PT > 10.) && (ETA.abs() < 2.5)}. Sphere's own
 * addition, for the console; the filters themselves are HepMC3's.
 *
 * <pre>
 *   expr       := term ('||' term)*
 *   term       := factor ('&amp;&amp;' factor)*
 *   factor     := '!' factor | '(' expr ')' | 'final' | 'visible' | comparison
 *   comparison := operand ('&lt;' | '&lt;=' | '&gt;' | '&gt;=' | '==' | '!=') number
 *   operand    := name | 'abs(' name ')'
 * </pre>
 *
 * with the names of {@link StandardSelector#byName()}: status, pid, pt, e,
 * rap (y), eta, phi, et, m. {@code final} is status 1; {@code visible} is a
 * final particle other than a neutrino.
 */
public final class CutExpression {

    private final String text;
    private int pos;

    private CutExpression(String text) {
        this.text = text;
    }

    /** The filter the text writes; IllegalArgumentException saying where it is wrong. */
    public static Filter parse(String text) {
        final CutExpression p = new CutExpression(text == null ? "" : text);
        if (p.text.isBlank()) return Filter.ACCEPT_ALL;
        final Filter f = p.expr();
        p.skipBlanks();
        if (p.pos < p.text.length()) throw p.error("unexpected '" + p.text.substring(p.pos) + "'");
        return f;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException(what + " at character " + (pos + 1) + " of \"" + text + "\"");
    }

    private void skipBlanks() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
    }

    private boolean take(String token) {
        skipBlanks();
        if (text.startsWith(token, pos)) {
            pos += token.length();
            return true;
        }
        return false;
    }

    private Filter expr() {
        Filter f = term();
        while (take("||")) f = f.or(term());
        return f;
    }

    private Filter term() {
        Filter f = factor();
        while (take("&&")) f = f.and(factor());
        return f;
    }

    private Filter factor() {
        skipBlanks();
        if (text.startsWith("!", pos) && !text.startsWith("!=", pos)) {
            pos++;
            return factor().not();
        }
        if (take("(")) {
            final Filter f = expr();
            if (!take(")")) throw error("')' expected");
            return f;
        }
        final int start = pos;
        final String word = name();
        if (word.equals("final")) return p -> p.status() == 1;
        if (word.equals("visible")) {
            return p -> p.status() == 1 && p.absPid() != 12 && p.absPid() != 14 && p.absPid() != 16;
        }
        Selector s;
        boolean integral;
        if (word.equals("abs")) {
            if (!take("(")) throw error("'(' expected after abs");
            final String inner = name();
            s = selector(inner);
            integral = integral(inner);
            if (!take(")")) throw error("')' expected");
            s = s.abs();
        } else {
            pos = start;
            final String n = name();
            s = selector(n);
            integral = integral(n);
        }
        skipBlanks();
        final String op;
        if (take("<=")) op = "<=";
        else if (take(">=")) op = ">=";
        else if (take("==")) op = "==";
        else if (take("!=")) op = "!=";
        else if (take("<")) op = "<";
        else if (take(">")) op = ">";
        else throw error("a comparison (<, <=, >, >=, ==, !=) expected");
        final double v = number();
        if (integral && v == Math.rint(v) && Math.abs(v) < Integer.MAX_VALUE) {
            final int k = (int) v;
            return switch (op) {
                case "<" -> s.lt(k);
                case "<=" -> s.le(k);
                case ">" -> s.gt(k);
                case ">=" -> s.ge(k);
                case "==" -> s.eq(k);
                default -> s.ne(k);
            };
        }
        return switch (op) {
            case "<" -> s.lt(v);
            case "<=" -> s.le(v);
            case ">" -> s.gt(v);
            case ">=" -> s.ge(v);
            case "==" -> s.eq(v);
            default -> s.ne(v);
        };
    }

    private String name() {
        skipBlanks();
        final int start = pos;
        while (pos < text.length() && (Character.isLetterOrDigit(text.charAt(pos)) || text.charAt(pos) == '_')) pos++;
        if (pos == start) throw error("a name expected");
        return text.substring(start, pos).toLowerCase(Locale.ROOT);
    }

    private double number() {
        skipBlanks();
        final int start = pos;
        while (pos < text.length() && "0123456789+-.eE".indexOf(text.charAt(pos)) >= 0) pos++;
        try {
            return Double.parseDouble(text.substring(start, pos));
        } catch (NumberFormatException e) {
            pos = start;
            throw error("a number expected");
        }
    }

    private static boolean integral(String name) {
        return name.equals("status") || name.equals("pid") || name.equals("pdg_id");
    }

    private Selector selector(String name) {
        final Map<String, Selector> known = StandardSelector.byName();
        final Selector s = known.get(name);
        if (s == null) throw error("unknown quantity '" + name + "' (" + String.join(", ", known.keySet()) + ")");
        return s;
    }
}

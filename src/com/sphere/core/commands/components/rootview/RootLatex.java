package com.sphere.components.rootview;

import java.util.Map;

/**
 * ROOT's TLatex, read into plain text a Swing label can show: #alpha becomes
 * α, p_{T} becomes pₜ where Unicode has the letter, #sqrt{s} becomes √s.
 * Titles and axis names in physics are full of these, and shown raw they read
 * as noise.
 */
final class RootLatex {

    private static final Map<String, String> SYMBOLS = Map.ofEntries(
        Map.entry("alpha", "α"), Map.entry("beta", "β"), Map.entry("gamma", "γ"), Map.entry("delta", "δ"),
        Map.entry("epsilon", "ε"), Map.entry("varepsilon", "ε"), Map.entry("zeta", "ζ"), Map.entry("eta", "η"),
        Map.entry("theta", "θ"), Map.entry("vartheta", "ϑ"), Map.entry("iota", "ι"), Map.entry("kappa", "κ"),
        Map.entry("lambda", "λ"), Map.entry("mu", "μ"), Map.entry("nu", "ν"), Map.entry("xi", "ξ"),
        Map.entry("omicron", "ο"), Map.entry("pi", "π"), Map.entry("rho", "ρ"), Map.entry("sigma", "σ"),
        Map.entry("tau", "τ"), Map.entry("upsilon", "υ"), Map.entry("phi", "φ"), Map.entry("varphi", "φ"),
        Map.entry("chi", "χ"), Map.entry("psi", "ψ"), Map.entry("omega", "ω"),
        Map.entry("Alpha", "Α"), Map.entry("Beta", "Β"), Map.entry("Gamma", "Γ"), Map.entry("Delta", "Δ"),
        Map.entry("Epsilon", "Ε"), Map.entry("Zeta", "Ζ"), Map.entry("Eta", "Η"), Map.entry("Theta", "Θ"),
        Map.entry("Iota", "Ι"), Map.entry("Kappa", "Κ"), Map.entry("Lambda", "Λ"), Map.entry("Mu", "Μ"),
        Map.entry("Nu", "Ν"), Map.entry("Xi", "Ξ"), Map.entry("Pi", "Π"), Map.entry("Rho", "Ρ"),
        Map.entry("Sigma", "Σ"), Map.entry("Tau", "Τ"), Map.entry("Upsilon", "Υ"), Map.entry("Phi", "Φ"),
        Map.entry("Chi", "Χ"), Map.entry("Psi", "Ψ"), Map.entry("Omega", "Ω"),
        Map.entry("pm", "±"), Map.entry("mp", "∓"), Map.entry("times", "×"), Map.entry("cdot", "·"),
        Map.entry("div", "÷"), Map.entry("leq", "≤"), Map.entry("geq", "≥"), Map.entry("neq", "≠"),
        Map.entry("approx", "≈"), Map.entry("sim", "∼"), Map.entry("equiv", "≡"), Map.entry("propto", "∝"),
        Map.entry("infty", "∞"), Map.entry("partial", "∂"), Map.entry("nabla", "∇"), Map.entry("int", "∫"),
        Map.entry("sum", "∑"), Map.entry("prod", "∏"), Map.entry("rightarrow", "→"), Map.entry("leftarrow", "←"),
        Map.entry("leftrightarrow", "↔"), Map.entry("Rightarrow", "⇒"), Map.entry("to", "→"),
        Map.entry("circ", "°"), Map.entry("degree", "°"), Map.entry("hbar", "ℏ"), Map.entry("ell", "ℓ"),
        Map.entry("in", "∈"), Map.entry("perp", "⊥"), Map.entry("parallel", "∥"), Map.entry("angle", "∠"),
        Map.entry("AA", "Å"), Map.entry("aa", "å"), Map.entry("LT", "<"), Map.entry("GT", ">"),
        Map.entry("ll", "≪"), Map.entry("gg", "≫"), Map.entry("dagger", "†"), Map.entry("bullet", "•"));

    private static final String SUP_FROM = "0123456789+-=()ni";
    private static final String SUP_TO = "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱ";
    private static final String SUB_FROM = "0123456789+-=()aehijklmnoprstuvx";
    private static final String SUB_TO = "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓ";

    private RootLatex() {
    }

    static String plain(String latex) {
        if (latex == null || latex.isEmpty()) return "";
        final StringBuilder out = new StringBuilder();
        final String s = latex;
        int i = 0;
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c == '#') {
                int j = i + 1;
                while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
                final String word = s.substring(i + 1, j);
                if (word.isEmpty()) {
                    out.append(c);
                    i++;
                    continue;
                }
                i = j;
                switch (word) {
                    case "sqrt" -> {
                        final String[] arg = group(s, i);
                        out.append('√').append(plain(arg[0]));
                        i = Integer.parseInt(arg[1]);
                    }
                    case "frac" -> {
                        final String[] a = group(s, i);
                        final String[] b = group(s, Integer.parseInt(a[1]));
                        out.append(plain(a[0])).append('/').append(plain(b[0]));
                        i = Integer.parseInt(b[1]);
                    }
                    case "bar", "overline", "hat", "tilde", "vec", "dot" -> {
                        final String[] arg = group(s, i);
                        final String mark = switch (word) {
                            case "hat" -> "̂";
                            case "tilde" -> "̃";
                            case "vec" -> "⃗";
                            case "dot" -> "̇";
                            default -> "̅";
                        };
                        for (char k : plain(arg[0]).toCharArray()) out.append(k).append(mark);
                        i = Integer.parseInt(arg[1]);
                    }
                    case "splitline" -> {
                        final String[] a = group(s, i);
                        final String[] b = group(s, Integer.parseInt(a[1]));
                        out.append(plain(a[0])).append(" / ").append(plain(b[0]));
                        i = Integer.parseInt(b[1]);
                    }
                    case "color", "font", "scale" -> {
                        // #color[2]{text}: the parameter is dropped, the text kept.
                        if (i < s.length() && s.charAt(i) == '[') {
                            final int close = s.indexOf(']', i);
                            i = close < 0 ? s.length() : close + 1;
                        }
                        final String[] arg = group(s, i);
                        out.append(plain(arg[0]));
                        i = Integer.parseInt(arg[1]);
                    }
                    case "it", "bf", "mbox", "text", "rm", "kern", "lower" -> {
                        final String[] arg = group(s, i);
                        out.append(plain(arg[0]));
                        i = Integer.parseInt(arg[1]);
                    }
                    default -> out.append(SYMBOLS.getOrDefault(word, word));
                }
            } else if (c == '^' || c == '_') {
                final String[] arg = group(s, i + 1);
                final String inner = plain(arg[0]);
                out.append(c == '^' ? superscript(inner) : subscript(inner));
                i = Integer.parseInt(arg[1]);
            } else if (c == '{' || c == '}') {
                i++;
            } else if (c == '~') {
                out.append(' ');
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The {group} at i, or the single character there, and where reading resumes. */
    private static String[] group(String s, int i) {
        if (i >= s.length()) return new String[]{"", Integer.toString(i)};
        if (s.charAt(i) != '{') return new String[]{String.valueOf(s.charAt(i)), Integer.toString(i + 1)};
        int depth = 0;
        for (int j = i; j < s.length(); j++) {
            if (s.charAt(j) == '{') depth++;
            else if (s.charAt(j) == '}' && --depth == 0) return new String[]{s.substring(i + 1, j), Integer.toString(j + 1)};
        }
        return new String[]{s.substring(i + 1), Integer.toString(s.length())};
    }

    static String superscript(String text) {
        return mapped(text, SUP_FROM, SUP_TO, "^");
    }

    static String subscript(String text) {
        return mapped(text, SUB_FROM, SUB_TO, "_");
    }

    /** Unicode super- or subscripts when every character has one, else the ROOT spelling kept readable. */
    private static String mapped(String text, String from, String to, String fallback) {
        final StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            final int k = from.indexOf(c);
            if (k < 0) return text.length() == 1 ? fallback + text : fallback + "(" + text + ")";
            out.append(to.charAt(k));
        }
        return out.toString();
    }
}

package com.sphere.core.python.jupyterlab;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bonds: a variable bound to a widget, in plain Python. A module-level literal
 * assignment with a widget comment becomes a widget under its cell:
 *
 * <pre>
 *   mass = 125.0       # @slider 100..150 step 0.5
 *   nbins = 40         # @slider 10..200
 *   log_y = False      # @toggle
 *   channel = "mumu"   # @select ee, mumu, tautau
 *   title = "Run 3"    # @text
 *   cut = 20           # @bind            (the widget follows the value)
 * </pre>
 *
 * The code stays the one truth: moving the widget rewrites the literal, then
 * the cell runs with everything depending on it. Nothing is hidden in widget
 * state, a restart gives the same notebook, and any Jupyter still reads it
 * (the comment is only a comment).
 */
final class NotebookBonds {

    enum Kind { SLIDER, TOGGLE, SELECT, TEXT }

    /**
     * One bond.
     *
     * @param start   where the literal starts in the cell's text
     * @param end     where it ends
     * @param integer a slider over whole numbers
     * @param quote   the quote a string literal uses, 0 for a literal written bare (numbers, True)
     */
    record Bond(String name, Kind kind, int start, int end, String literal, double min, double max, double step,
                boolean integer, List<String> options, char quote) {

        /** The same widget: what a rewrite of its own literal leaves unchanged. */
        boolean sameWidget(Bond o) {
            return o != null && name.equals(o.name) && kind == o.kind && min == o.min && max == o.max && step == o.step
                   && integer == o.integer && options.equals(o.options) && quote == o.quote;
        }

        double number() {
            try {
                return Double.parseDouble(literal.replace("_", ""));
            } catch (NumberFormatException e) {
                return min;
            }
        }

        boolean bool() {
            return "True".equals(literal);
        }

        /** The text of a string literal, the literal itself otherwise. */
        String string() {
            return quote != 0 && literal.length() >= 2 ? literal.substring(1, literal.length() - 1) : literal;
        }

        int ticks() {
            return (int) Math.max(1, Math.min(100_000, Math.round((max - min) / step)));
        }

        int tickOf(double v) {
            return (int) Math.max(0, Math.min(ticks(), Math.round((v - min) / step)));
        }

        double valueAt(int tick) {
            return Math.min(max, min + tick * step);
        }

        int decimals() {
            if (integer) {
                return 0;
            }
            int s = Math.max(BigDecimal.valueOf(step).stripTrailingZeros().scale(),
                             BigDecimal.valueOf(min).stripTrailingZeros().scale());
            return Math.max(1, Math.min(10, s));
        }

        /** A number as Python literal: whole for an integer slider, a float (with its point) otherwise. */
        String literalFor(double v) {
            if (integer) {
                return Long.toString(Math.round(v));
            }
            return BigDecimal.valueOf(v).setScale(decimals(), RoundingMode.HALF_UP).toPlainString();
        }

        String literalFor(boolean b) {
            return b ? "True" : "False";
        }

        /** A string as a literal with the bond's quote; that quote and backslashes are kept out, so it reads back. */
        String literalFor(String s) {
            if (quote == 0) {
                return s;
            }
            char other = quote == '"' ? '\'' : '"';
            String clean = s.replace('\\', '/').replace(quote, other).replace('\n', ' ').replace('\r', ' ');
            return quote + clean + quote;
        }

        /** What the bar shows beside the widget. */
        String display() {
            return switch (kind) {
                case SLIDER -> literalFor(number());
                case TOGGLE -> literal;
                default -> string();
            };
        }
    }

    private static final Pattern LINE = Pattern.compile(
        "^([A-Za-z_][A-Za-z0-9_]*)[ \\t]*(?::[^=\\n#]+)?=[ \\t]*"
        + "([-+]?(?:\\d[\\d_]*\\.?[\\d_]*(?:[eE][-+]?\\d+)?|\\.\\d+(?:[eE][-+]?\\d+)?)|True|False"
        + "|\"[^\"\\\\\\n]*\"|'[^'\\\\\\n]*')"
        + "[ \\t]*#[ \\t]*@(slider|range|toggle|checkbox|check|select|choice|text|bind)\\b([^\\n]*)$",
        Pattern.MULTILINE);
    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private NotebookBonds() {
    }

    /** The bonds of a cell's text, in order. */
    static List<Bond> parse(String text) {
        List<Bond> out = new ArrayList<>();
        if (text == null || text.indexOf('@') < 0) {
            return out;
        }
        Matcher m = LINE.matcher(text);
        while (m.find()) {
            Bond b = bond(m.group(1), m.group(2), m.start(2), m.end(2), m.group(3), m.group(4).strip());
            if (b != null) {
                out.add(b);
            }
        }
        return out;
    }

    private static Bond bond(String name, String literal, int start, int end, String kind, String args) {
        boolean isBool = literal.equals("True") || literal.equals("False");
        boolean isString = literal.startsWith("\"") || literal.startsWith("'");
        char quote = isString ? literal.charAt(0) : 0;
        String k = switch (kind) {
            case "bind" -> isBool ? "toggle" : isString ? "text" : "slider";
            case "range" -> "slider";
            case "checkbox", "check" -> "toggle";
            case "choice" -> "select";
            default -> kind;
        };
        switch (k) {
            case "toggle" -> {
                return isBool ? new Bond(name, Kind.TOGGLE, start, end, literal, 0, 1, 1, true, List.of(), (char) 0) : null;
            }
            case "text" -> {
                return isString ? new Bond(name, Kind.TEXT, start, end, literal, 0, 0, 1, false, List.of(), quote) : null;
            }
            case "select" -> {
                List<String> options = new ArrayList<>();
                for (String o : args.split(",")) {
                    String s = o.strip();
                    if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
                        s = s.substring(1, s.length() - 1);
                    }
                    if (!s.isEmpty() && !options.contains(s)) {
                        options.add(s);
                    }
                }
                if (options.isEmpty() || isBool) {
                    return null;
                }
                String current = isString ? literal.substring(1, literal.length() - 1) : literal;
                if (!options.contains(current)) {
                    options.add(0, current);
                }
                return new Bond(name, Kind.SELECT, start, end, literal, 0, options.size() - 1, 1, false,
                                List.copyOf(options), quote);
            }
            default -> {
                if (isBool || isString) {
                    return null;
                }
                boolean integer = !literal.contains(".") && !literal.toLowerCase(Locale.ROOT).contains("e");
                double v;
                try {
                    v = Double.parseDouble(literal.replace("_", ""));
                } catch (NumberFormatException e) {
                    return null;
                }
                List<Double> nums = new ArrayList<>();
                Matcher m = NUMBER.matcher(args.replace("..", " ").replace(':', ' ').replace(',', ' ')
                                               .replaceAll("(?i)step", " "));
                while (m.find()) {
                    nums.add(Double.parseDouble(m.group()));
                }
                double lo;
                double hi;
                if (nums.size() >= 2) {
                    lo = nums.get(0);
                    hi = nums.get(1);
                } else if (v == 0) {
                    lo = 0;
                    hi = integer ? 10 : 1;
                } else if (v > 0) {
                    lo = 0;
                    hi = niceCeil(2 * v);
                } else {
                    lo = -niceCeil(-2 * v);
                    hi = 0;
                }
                if (hi < lo) {
                    double t = lo;
                    lo = hi;
                    hi = t;
                }
                if (hi == lo) {
                    hi = lo + 1;
                }
                double step = nums.size() >= 3 ? Math.abs(nums.get(2)) : integer ? 1 : nice((hi - lo) / 100);
                if (step == 0) {
                    step = integer ? 1 : (hi - lo) / 100;
                }
                integer = integer && step == Math.rint(step) && lo == Math.rint(lo);
                return new Bond(name, Kind.SLIDER, start, end, literal, lo, hi, step, integer, List.of(), (char) 0);
            }
        }
    }

    /** 1, 2 or 5 times a power of ten, nearest x. */
    static double nice(double x) {
        if (x <= 0) {
            return 1;
        }
        double p = Math.pow(10, Math.floor(Math.log10(x)));
        double m = x / p;
        return (m < 1.5 ? 1 : m < 3.5 ? 2 : m < 7.5 ? 5 : 10) * p;
    }

    private static double niceCeil(double x) {
        double p = Math.pow(10, Math.floor(Math.log10(x)));
        return Math.ceil(x / p) * p;
    }
}

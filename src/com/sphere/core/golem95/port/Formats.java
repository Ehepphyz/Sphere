package com.sphere.core.golem95.port;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fortran edit descriptors for the library's messages: (A, I5, F10.4, E16.8, ES14.6, G, 3X, 'text', /),
 * repeat counts and parenthesised groups. The numbers are written as Fortran writes them, near enough
 * for a message; nothing in the computation depends on them.
 */
final class Formats {

    private Formats() {
    }

    static String apply(String fmt, List<Object> items) {
        String f = fmt.trim();
        if (f.startsWith("(") && f.endsWith(")")) {
            f = f.substring(1, f.length() - 1);
        }
        final List<String> descs = expand(f);
        final StringBuilder out = new StringBuilder();
        int k = 0;
        boolean any = false;
        // the format is reused while items remain
        for (int round = 0; round < 1000; round++) {
            boolean consumed = false;
            for (String d : descs) {
                if (d.startsWith("'") || d.startsWith("\"")) {
                    out.append(d, 1, d.length() - 1);
                    continue;
                }
                if (d.equals("/")) {
                    out.append('\n');
                    continue;
                }
                if (d.matches("\\d*x")) {
                    final int n = d.length() > 1 ? Integer.parseInt(d.substring(0, d.length() - 1)) : 1;
                    out.append(" ".repeat(n));
                    continue;
                }
                if (k >= items.size()) {
                    if (any || round > 0) {
                        return out.toString();
                    }
                    continue;
                }
                out.append(edit(d, items.get(k++)));
                consumed = true;
                any = true;
            }
            if (k >= items.size() || !consumed) {
                break;
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static List<String> expand(String f) {
        final List<String> out = new ArrayList<>();
        int i = 0;
        while (i < f.length()) {
            final char c = f.charAt(i);
            if (c == ',' || c == ' ') {
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < f.length() && f.charAt(j) != c) {
                    j++;
                }
                out.add(f.substring(i, Math.min(j + 1, f.length())));
                i = j + 1;
                continue;
            }
            if (c == '/') {
                out.add("/");
                i++;
                continue;
            }
            int j = i;
            while (j < f.length() && Character.isDigit(f.charAt(j))) {
                j++;
            }
            final int rep = j > i ? Integer.parseInt(f.substring(i, j)) : 1;
            if (j < f.length() && f.charAt(j) == '(') {
                int depth = 0;
                int e = j;
                for (; e < f.length(); e++) {
                    if (f.charAt(e) == '(') {
                        depth++;
                    } else if (f.charAt(e) == ')') {
                        depth--;
                        if (depth == 0) {
                            break;
                        }
                    }
                }
                final List<String> inner = expand(f.substring(j + 1, e));
                for (int r = 0; r < rep; r++) {
                    out.addAll(inner);
                }
                i = e + 1;
                continue;
            }
            int e = j;
            while (e < f.length() && f.charAt(e) != ',' && f.charAt(e) != '/' && f.charAt(e) != '(') {
                e++;
            }
            final String d = f.substring(j, e).trim().toLowerCase(Locale.ROOT);
            if (d.equals("x")) {
                out.add(rep + "x");
            } else {
                for (int r = 0; r < rep; r++) {
                    out.add(d);
                }
            }
            i = e;
        }
        return out;
    }

    private static String edit(String d, Object v) {
        try {
            final char c = d.charAt(0);
            final String w = d.substring(d.startsWith("es") || d.startsWith("en") ? 2 : 1);
            final String[] wd = w.split("\\.");
            final int width = wd[0].isEmpty() ? 0 : Integer.parseInt(wd[0].replaceAll("e.*", ""));
            final int dec = wd.length > 1 ? Integer.parseInt(wd[1].replaceAll("e.*", "")) : 0;
            if (v instanceof Cx z) {
                return edit(d, z.re) + edit(d, z.im);
            }
            switch (c) {
                case 'a':
                    return String.valueOf(v);
                case 'i':
                    return pad(String.valueOf(((Number) v).longValue()), width);
                case 'f':
                    return pad(String.format(Locale.ROOT, "%." + dec + "f", ((Number) v).doubleValue()), width);
                case 'e':
                case 'd':
                    return pad(String.format(Locale.ROOT, "%." + dec + "E", ((Number) v).doubleValue()), width);
                case 'g':
                    return pad(String.format(Locale.ROOT, "%." + Math.max(1, dec) + "G", ((Number) v).doubleValue()), width);
                case 'l':
                    return pad(Boolean.TRUE.equals(v) ? "T" : "F", width);
                default:
                    return F.listItem(v);
            }
        } catch (RuntimeException e) {
            return F.listItem(v);
        }
    }

    private static String pad(String s, int width) {
        if (width <= 0) {
            return s;
        }
        if (s.length() > width) {
            return "*".repeat(width);
        }
        return " ".repeat(width - s.length()) + s;
    }
}

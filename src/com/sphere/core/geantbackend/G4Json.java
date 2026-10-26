package com.sphere.core.geantbackend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSON for geant4-bridge's requests and replies: parsed into Map, List,
 * String, Double, Boolean and null, written back from the same.
 */
public final class G4Json {

    private G4Json() {
    }

    public static Object parse(String text) {
        final int[] pos = {0};
        final Object v = value(text, pos);
        skip(text, pos);
        if (pos[0] != text.length()) {
            throw new IllegalArgumentException("trailing characters in JSON at " + pos[0]);
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        final Object o = parse(text);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("not a JSON object");
        }
        return (Map<String, Object>) o;
    }

    public static String write(Object v) {
        final StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof Boolean bo) {
            b.append(bo);
        } else if (v instanceof Number n) {
            final double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                b.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                b.append((long) d);
            } else {
                b.append(String.format(Locale.ROOT, "%.12g", d));
            }
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                quote(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof Iterable<?> it) {
            b.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                write(b, o);
            }
            b.append(']');
        } else if (v instanceof double[] arr) {
            b.append('[');
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    b.append(',');
                }
                write(b, arr[i]);
            }
            b.append(']');
        } else {
            quote(b, String.valueOf(v));
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    private static void skip(String t, int[] p) {
        while (p[0] < t.length() && Character.isWhitespace(t.charAt(p[0]))) {
            p[0]++;
        }
    }

    private static Object value(String t, int[] p) {
        skip(t, p);
        if (p[0] >= t.length()) {
            throw new IllegalArgumentException("unexpected end of JSON");
        }
        final char c = t.charAt(p[0]);
        if (c == '{') {
            p[0]++;
            final Map<String, Object> m = new LinkedHashMap<>();
            skip(t, p);
            if (t.charAt(p[0]) == '}') {
                p[0]++;
                return m;
            }
            while (true) {
                skip(t, p);
                final Object k = value(t, p);
                skip(t, p);
                if (t.charAt(p[0]) != ':') {
                    throw new IllegalArgumentException("expected ':' at " + p[0]);
                }
                p[0]++;
                m.put(String.valueOf(k), value(t, p));
                skip(t, p);
                final char d = t.charAt(p[0]++);
                if (d == '}') {
                    return m;
                }
                if (d != ',') {
                    throw new IllegalArgumentException("expected ',' or '}' at " + (p[0] - 1));
                }
            }
        }
        if (c == '[') {
            p[0]++;
            final List<Object> l = new ArrayList<>();
            skip(t, p);
            if (t.charAt(p[0]) == ']') {
                p[0]++;
                return l;
            }
            while (true) {
                l.add(value(t, p));
                skip(t, p);
                final char d = t.charAt(p[0]++);
                if (d == ']') {
                    return l;
                }
                if (d != ',') {
                    throw new IllegalArgumentException("expected ',' or ']' at " + (p[0] - 1));
                }
            }
        }
        if (c == '"') {
            p[0]++;
            final StringBuilder s = new StringBuilder();
            while (p[0] < t.length()) {
                final char ch = t.charAt(p[0]++);
                if (ch == '"') {
                    return s.toString();
                }
                if (ch == '\\') {
                    final char e = t.charAt(p[0]++);
                    switch (e) {
                        case 'n' -> s.append('\n');
                        case 't' -> s.append('\t');
                        case 'r' -> s.append('\r');
                        case 'b' -> s.append('\b');
                        case 'f' -> s.append('\f');
                        case 'u' -> {
                            s.append((char) Integer.parseInt(t.substring(p[0], p[0] + 4), 16));
                            p[0] += 4;
                        }
                        default -> s.append(e);
                    }
                } else {
                    s.append(ch);
                }
            }
            throw new IllegalArgumentException("unterminated JSON string");
        }
        if (t.startsWith("true", p[0])) {
            p[0] += 4;
            return Boolean.TRUE;
        }
        if (t.startsWith("false", p[0])) {
            p[0] += 5;
            return Boolean.FALSE;
        }
        if (t.startsWith("null", p[0])) {
            p[0] += 4;
            return null;
        }
        final int start = p[0];
        while (p[0] < t.length() && "+-.0123456789eE".indexOf(t.charAt(p[0])) >= 0) {
            p[0]++;
        }
        if (start == p[0]) {
            throw new IllegalArgumentException("unexpected '" + c + "' at " + start);
        }
        return Double.parseDouble(t.substring(start, p[0]));
    }

    // Accessors that tolerate missing or mistyped fields

    public static double num(Object o, String key, double fallback) {
        if (o instanceof Map<?, ?> m && m.get(key) instanceof Number n) {
            return n.doubleValue();
        }
        return fallback;
    }

    public static String str(Object o, String key, String fallback) {
        if (o instanceof Map<?, ?> m && m.get(key) instanceof String s) {
            return s;
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o, String key) {
        if (o instanceof Map<?, ?> m && m.get(key) instanceof Map<?, ?> v) {
            return (Map<String, Object>) v;
        }
        return Map.of();
    }

    public static List<?> list(Object o, String key) {
        if (o instanceof Map<?, ?> m && m.get(key) instanceof List<?> l) {
            return l;
        }
        return List.of();
    }
}

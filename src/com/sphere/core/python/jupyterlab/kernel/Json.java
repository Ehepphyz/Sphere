package com.sphere.core.python.jupyterlab.kernel;

import com.sphere.core.python.jupyterlab.JupylabXedParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact JSON for the kernel wire. Decoding goes through the notebook's own
 * parser; this is the encoder it lacked for nested values: the stdio driver only
 * ever sent flat objects, and a Jupyter message nests a header in a list of frames.
 */
public final class Json {

    private Json() {
    }

    public static String encode(Object value) {
        StringBuilder sb = new StringBuilder(64);
        write(value, sb);
        return sb.toString();
    }

    /** A JSON object, or an empty map when the text is anything else. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        if (text == null || text.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = new JupylabXedParser(text).parse();
            return parsed instanceof Map ? (Map<String, Object>) parsed : new LinkedHashMap<>();
        } catch (RuntimeException malformed) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    public static List<Object> list(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return new ArrayList<>();
    }

    public static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    public static int integer(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static void write(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            quote(s, sb);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                   || value instanceof java.math.BigInteger || value instanceof Short || value instanceof Byte) {
            sb.append(value);
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            // JSON has no NaN or infinity; null is what Python's json writes for them in strict mode
            sb.append(Double.isFinite(d) ? String.valueOf(n) : "null");
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> items) {
            sb.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(item, sb);
            }
            sb.append(']');
        } else if (value instanceof Object[] array) {
            write(java.util.Arrays.asList(array), sb);
        } else {
            quote(String.valueOf(value), sb);
        }
    }

    static void quote(String text, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}

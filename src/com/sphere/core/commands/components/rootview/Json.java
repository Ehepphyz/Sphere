package com.sphere.components.rootview;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read what the ROOT backend answers.
 *
 * The engine describes trees and branches as JSON objects; Sphere carries no
 * external jar, so the few shapes it sends are read here. Anything malformed
 * ends the parse and leaves what was read, since a viewer shows what it can.
 */
public final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text == null ? "" : text;
    }

    /** A map, a list, a string, a number, a boolean, or null. */
    public static Object parse(String text) {
        Json reader = new Json(text);
        reader.skipSpace();
        return reader.value();
    }

    /** The value at `key` of a parsed object, or null. */
    @SuppressWarnings("unchecked")
    public static Object get(Object object, String key) {
        return object instanceof Map<?, ?> map ? ((Map<String, Object>) map).get(key) : null;
    }

    public static String text(Object object, String key, String fallback) {
        Object value = get(object, key);
        return value == null ? fallback : String.valueOf(value);
    }

    public static long number(Object object, String key, long fallback) {
        Object value = get(object, key);
        return value instanceof Number n ? n.longValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object object, String key) {
        Object value = get(object, key);
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    // ---- the reader --------------------------------------------------------

    private Object value() {
        if (at >= text.length()) {
            return null;
        }
        final char c = text.charAt(at);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        at++;                                   // past the brace
        skipSpace();
        while (at < text.length() && text.charAt(at) != '}') {
            if (text.charAt(at) != '"') {
                break;
            }
            final String key = string();
            skipSpace();
            if (at >= text.length() || text.charAt(at) != ':') {
                break;
            }
            at++;
            skipSpace();
            out.put(key, value());
            skipSpace();
            if (at < text.length() && text.charAt(at) == ',') {
                at++;
                skipSpace();
            }
        }
        if (at < text.length()) {
            at++;                               // past the closing brace
        }
        return out;
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        at++;
        skipSpace();
        while (at < text.length() && text.charAt(at) != ']') {
            out.add(value());
            skipSpace();
            if (at < text.length() && text.charAt(at) == ',') {
                at++;
                skipSpace();
            }
        }
        if (at < text.length()) {
            at++;
        }
        return out;
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        at++;                                   // past the quote
        while (at < text.length()) {
            final char c = text.charAt(at++);
            if (c == '"') {
                break;
            }
            if (c != '\\' || at >= text.length()) {
                b.append(c);
                continue;
            }
            final char escaped = text.charAt(at++);
            switch (escaped) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    if (at + 4 <= text.length()) {
                        b.append((char) Integer.parseInt(
                            text.substring(at, at + 4), 16));
                        at += 4;
                    }
                }
                default -> b.append(escaped);
            }
        }
        return b.toString();
    }

    private Object number() {
        final int from = at;
        while (at < text.length() && "+-.eE0123456789".indexOf(text.charAt(at)) >= 0) {
            at++;
        }
        if (at == from) {
            at++;                               // something unexpected; step over it
            return null;
        }
        try {
            return Double.valueOf(text.substring(from, at));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private Object literal(String word, Object result) {
        if (text.startsWith(word, at)) {
            at += word.length();
            return result;
        }
        at++;
        return null;
    }

    private void skipSpace() {
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
    }
}

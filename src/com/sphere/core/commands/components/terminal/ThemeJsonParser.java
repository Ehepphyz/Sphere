package com.sphere.components.terminal;

import java.util.*;

/**
 * Minimal JSON parser designed specifically for theme files.
 */
public class ThemeJsonParser {

    private final String text;
    private int index = 0;

    public ThemeJsonParser(String text) {
        this.text = text.trim();
    }

    /** Entry point to parse JSON text into a Map. */
    public static Map<String, Object> parse(String text) {
        return new ThemeJsonParser(text).parseObject();
    }

    private char peek() {
        return text.charAt(index);
    }

    private char next() {
        return text.charAt(index++);
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(peek())) {
            index++;
        }
    }

    private void expect(char c) {
        if (next() != c) {
            throw new RuntimeException("Expected '" + c + "' at position " + index);
        }
    }

    /** Parse "string" values. */
    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    /** Parse number values (int or double). */
    private Number parseNumber() {
        int start = index;
        while (index < text.length() && "0123456789.-".indexOf(peek()) >= 0) {
            index++;
        }
        String num = text.substring(start, index);
        if (num.contains(".")) return Double.parseDouble(num);
        return Integer.parseInt(num);
    }

    /** Parse true/false/string/number/object values. */
    private Object parseValue() {
        skipWhitespace();
        char c = peek();

        if (c == '"') return parseString();
        if (c == '{') return parseObject();
        if (Character.isDigit(c) || c == '-') return parseNumber();

        if (text.startsWith("true", index)) {
            index += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", index)) {
            index += 5;
            return Boolean.FALSE;
        }

        throw new RuntimeException("Unexpected value at position " + index);
    }

    /** Detect if all values in the map are strings. */
    private boolean isStringOnlyMap(Map<String, Object> map) {
        for (Object v : map.values()) {
            if (!(v instanceof String)) return false;
        }
        return true;
    }

    /** Parse { key: value, ... } objects. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();

        expect('{');
        skipWhitespace();

        while (peek() != '}') {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            skipWhitespace();

            Object value = parseValue();
            map.put(key, value);

            skipWhitespace();
            if (peek() == ',') {
                next();
                skipWhitespace();
            }
        }

        expect('}');

        return map;
    }
}

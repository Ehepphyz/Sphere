package com.sphere.core.python.jupyterlab;

import java.util.*;

public class JupylabXedParser {
    private final String s;
    private int pos = 0;

    public JupylabXedParser(String s) {
        this.s = s;
    }

    public Object parse() {
        skipWs();
        Object v = parseValue();
        skipWs();
        return v;
    }

    private Object parseValue() {
        skipWs();
        if (match("null")) return null;
        if (match("true")) return Boolean.TRUE;
        if (match("false")) return Boolean.FALSE;

        char c = peek();
        if (c == '"') return parseString();
        if (c == '{') return parseObject();
        if (c == '[') return parseArray();
        if (c == '-' || Character.isDigit(c)) return parseNumber();

        throw new RuntimeException("Unexpected char at " + pos + ": " + c);
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        expect('{');
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            expect(':');
            skipWs();
            Object val = parseValue();
            map.put(key, val);
            skipWs();
            char c = peek();
            if (c == '}') {
                pos++;
                break;
            }
            expect(',');
        }
        return map;
    }

    private List<Object> parseArray() {
        List<Object> list = new ArrayList<>();
        expect('[');
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            Object v = parseValue();
            list.add(v);
            skipWs();
            char c = peek();
            if (c == ']') {
                pos++;
                break;
            }
            expect(',');
        }
        return list;
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) throw new RuntimeException("Unterminated string");
            char c = s.charAt(pos++);
            if (c == '"') break;
            if (c == '\\') {
                if (pos >= s.length()) throw new RuntimeException("Bad escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) throw new RuntimeException("Bad unicode escape");
                        String hex = s.substring(pos, pos + 4);
                        pos += 4;
                        sb.append((char) Integer.parseInt(hex, 16));
                        break;
                    default: sb.append(e); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Number parseNumber() {
        int start = pos;
        if (peek() == '-') pos++;
        while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
        }
        String num = s.substring(start, pos).trim();
        if (num.contains(".") || num.contains("e") || num.contains("E")) {
            return Double.parseDouble(num);
        }
        return Integer.parseInt(num);
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    private char peek() {
        if (pos >= s.length()) throw new RuntimeException("Unexpected end of input");
        return s.charAt(pos);
    }

    private void expect(char c) {
        if (peek() != c) throw new RuntimeException("Expected '" + c + "' at " + pos);
        pos++;
    }

    private boolean match(String lit) {
        skipWs();
        if (s.startsWith(lit, pos)) {
            pos += lit.length();
            return true;
        }
        return false;
    }
}
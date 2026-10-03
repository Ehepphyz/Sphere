package com.sphere.core.fjcontrib.validation;

import com.sphere.core.fastjet.Fmt;

/**
 * C's printf conversions (%d %i %u %ld %f %e %g %s %c %%, with the flags
 * - + space 0 #, a width and a precision, * for either), each number
 * rounded from its exact binary value as glibc does. Java's Formatter
 * differs from C on %g and may round a half differently on %f and %e.
 */
public final class Printf {

    private Printf() {
    }

    public static String format(String fmt, Object... args) {
        final StringBuilder out = new StringBuilder();
        int a = 0;
        int i = 0;
        while (i < fmt.length()) {
            final char c = fmt.charAt(i);
            if (c != '%') {
                out.append(c);
                i++;
                continue;
            }
            i++;
            if (i < fmt.length() && fmt.charAt(i) == '%') {
                out.append('%');
                i++;
                continue;
            }
            boolean minus = false, plus = false, space = false, zero = false, alt = false;
            while (i < fmt.length() && "-+ 0#".indexOf(fmt.charAt(i)) >= 0) {
                switch (fmt.charAt(i)) {
                    case '-' -> minus = true;
                    case '+' -> plus = true;
                    case ' ' -> space = true;
                    case '0' -> zero = true;
                    default -> alt = true;
                }
                i++;
            }
            int width = 0;
            if (i < fmt.length() && fmt.charAt(i) == '*') {
                width = ((Number) args[a++]).intValue();
                if (width < 0) {
                    minus = true;
                    width = -width;
                }
                i++;
            } else {
                while (i < fmt.length() && Character.isDigit(fmt.charAt(i))) width = 10 * width + (fmt.charAt(i++) - '0');
            }
            int precision = -1;
            if (i < fmt.length() && fmt.charAt(i) == '.') {
                i++;
                precision = 0;
                if (i < fmt.length() && fmt.charAt(i) == '*') {
                    precision = ((Number) args[a++]).intValue();
                    i++;
                } else {
                    while (i < fmt.length() && Character.isDigit(fmt.charAt(i))) precision = 10 * precision + (fmt.charAt(i++) - '0');
                }
            }
            while (i < fmt.length() && "hlLqjzt".indexOf(fmt.charAt(i)) >= 0) i++;
            final char conv = fmt.charAt(i++);
            String body;
            switch (conv) {
                case 'd', 'i', 'u' -> {
                    final long v = ((Number) args[a++]).longValue();
                    body = Long.toString(Math.abs(v));
                    if (precision >= 0 && body.length() < precision) body = "0".repeat(precision - body.length()) + body;
                    body = sign(v < 0, plus, space) + body;
                }
                case 'f', 'F' -> {
                    final double v = ((Number) args[a++]).doubleValue();
                    body = Fmt.f(v, 0, precision < 0 ? 6 : precision);
                    if (alt && precision == 0 && !body.contains(".")) body += ".";
                    body = signed(body, v, plus, space);
                }
                case 'e', 'E' -> {
                    final double v = ((Number) args[a++]).doubleValue();
                    body = Fmt.e(v, 0, precision < 0 ? 6 : precision);
                    if (conv == 'E') body = body.toUpperCase();
                    body = signed(body, v, plus, space);
                }
                case 'g', 'G' -> {
                    final double v = ((Number) args[a++]).doubleValue();
                    body = Fmt.g(v, precision < 0 ? 6 : (precision == 0 ? 1 : precision));
                    if (conv == 'G') body = body.toUpperCase();
                    body = signed(body, v, plus, space);
                }
                case 's' -> {
                    body = String.valueOf(args[a++]);
                    if (precision >= 0 && body.length() > precision) body = body.substring(0, precision);
                    zero = false;
                }
                case 'c' -> {
                    final Object o = args[a++];
                    body = o instanceof Number n ? String.valueOf((char) n.intValue()) : String.valueOf(o);
                    zero = false;
                }
                default -> throw new IllegalArgumentException("unsupported conversion %" + conv);
            }
            if (body.length() < width) {
                final int n = width - body.length();
                if (minus) {
                    body = body + " ".repeat(n);
                } else if (zero && isNumber(conv) && !(body.contains("inf") || body.contains("nan"))) {
                    final int signLen = body.startsWith("-") || body.startsWith("+") || body.startsWith(" ") ? 1 : 0;
                    body = body.substring(0, signLen) + "0".repeat(n) + body.substring(signLen);
                } else {
                    body = " ".repeat(n) + body;
                }
            }
            out.append(body);
        }
        return out.toString();
    }

    private static boolean isNumber(char conv) {
        return "diufFeEgG".indexOf(conv) >= 0;
    }

    private static String sign(boolean negative, boolean plus, boolean space) {
        return negative ? "-" : plus ? "+" : space ? " " : "";
    }

    private static String signed(String body, double v, boolean plus, boolean space) {
        if (body.startsWith("-")) return body;
        if (Double.isNaN(v)) return body;
        return (plus ? "+" : space ? " " : "") + body;
    }
}

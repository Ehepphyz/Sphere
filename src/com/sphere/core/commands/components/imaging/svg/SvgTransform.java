package com.sphere.components.imaging.svg;

import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.List;

/** Reads the value of a `transform` attribute into an AffineTransform. */
public final class SvgTransform {

    private SvgTransform() {
    }

    public static AffineTransform parse(String value) {
        AffineTransform result = new AffineTransform();
        if (value == null || value.isBlank()) {
            return result;
        }

        int i = 0;
        final int n = value.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(value.charAt(i)) || value.charAt(i) == ',')) {
                i++;
            }
            final int nameStart = i;
            while (i < n && (Character.isLetter(value.charAt(i)))) {
                i++;
            }
            if (i == nameStart) {
                break;
            }
            final String name = value.substring(nameStart, i);

            while (i < n && Character.isWhitespace(value.charAt(i))) {
                i++;
            }
            if (i >= n || value.charAt(i) != '(') {
                break;
            }
            final int close = value.indexOf(')', i);
            if (close < 0) {
                break;
            }
            final double[] args = numbers(value.substring(i + 1, close));
            i = close + 1;

            // Each function multiplies on the right, so they apply left to right.
            result.concatenate(toTransform(name, args));
        }
        return result;
    }

    private static AffineTransform toTransform(String name, double[] a) {
        switch (name) {
            case "matrix":
                if (a.length >= 6) {
                    return new AffineTransform(a[0], a[1], a[2], a[3], a[4], a[5]);
                }
                break;
            case "translate":
                if (a.length >= 2) {
                    return AffineTransform.getTranslateInstance(a[0], a[1]);
                }
                if (a.length == 1) {
                    return AffineTransform.getTranslateInstance(a[0], 0);
                }
                break;
            case "scale":
                if (a.length >= 2) {
                    return AffineTransform.getScaleInstance(a[0], a[1]);
                }
                if (a.length == 1) {
                    return AffineTransform.getScaleInstance(a[0], a[0]);
                }
                break;
            case "rotate":
                if (a.length >= 3) {
                    return AffineTransform.getRotateInstance(Math.toRadians(a[0]), a[1], a[2]);
                }
                if (a.length == 1) {
                    return AffineTransform.getRotateInstance(Math.toRadians(a[0]));
                }
                break;
            case "skewx":
            case "skewX":
                if (a.length >= 1) {
                    return AffineTransform.getShearInstance(Math.tan(Math.toRadians(a[0])), 0);
                }
                break;
            case "skewy":
            case "skewY":
                if (a.length >= 1) {
                    return AffineTransform.getShearInstance(0, Math.tan(Math.toRadians(a[0])));
                }
                break;
            default:
                break;
        }
        return new AffineTransform();
    }

    private static double[] numbers(String text) {
        List<Double> values = new ArrayList<>();
        for (String token : text.split("[,\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            try {
                values.add(Double.parseDouble(token.trim()));
            } catch (NumberFormatException ignored) {
                // A malformed argument makes the whole function a no-op, which is
                // what the spec asks for.
                return new double[0];
            }
        }
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}

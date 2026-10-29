package com.sphere.core.clhep.vector;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.CStr;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's reading of vectors (Vector/src/ZMinput.cc): "(x, y, z)", "x y z", with or without the
 * parentheses and commas; what cannot be read is said on cerr and leaves the stream failed. The values
 * read go into the array given; those not read keep what it held.
 */
public final class ZMinput {

    private ZMinput() {
    }

    private static boolean eatwhitespace(CIStream is) {
        int c;
        while ((c = is.get()) >= 0) {
            if (!CStr.isSpace((char) c)) {
                is.putback((char) c);
                return true;
            }
        }
        return false;
    }

    private static void fouledup() {
        StdStreams.cerr().print("istream mysteriously lost a putback character!\n");
    }

    private static void err(String s) {
        StdStreams.cerr().print(s);
    }

    /** The closing parenthesis, else the stream made to fail as CLHEP makes it. */
    private static void close(CIStream is, String missing, String noClose) {
        if (!eatwhitespace(is)) {
            err(noClose);
            return;
        }
        final int c = is.get();
        if (c < 0) {
            fouledup();
            return;
        }
        if (c != ')') {
            err(missing);
            if (Character.isDigit(c) || c == '-' || c == '+') {
                is.putback('@');
            } else {
                is.putback('c');
            }
            is.nextInt();   // fails, leaving the stream bad
        }
    }

    /** A separating comma, taken when there is one. False when the stream ended. */
    private static boolean comma(CIStream is, String ended) {
        final int c = is.get();
        if (c < 0) {
            fouledup();
            return false;
        }
        if (c == ',') {
            if (!eatwhitespace(is)) {
                err(ended);
                return false;
            }
        } else {
            is.putback((char) c);
        }
        return true;
    }

    /** ZMinput3doubles: x, y and z into v[0..2]. */
    public static void input3doubles(CIStream is, String type, double[] v) {
        boolean parenthesis = false;
        if (!eatwhitespace(is)) {
            err("istream ended before trying to input " + type + "\n");
            return;
        }
        int c = is.get();
        if (c < 0) {
            fouledup();
            return;
        }
        if (c == '(') {
            parenthesis = true;
            if (!eatwhitespace(is)) {
                err("istream ended after ( trying to input " + type + "\n");
                return;
            }
        } else {
            is.putback((char) c);
        }
        double x = is.nextDouble();
        if (is.fail()) {
            v[0] = x;
            err("Could not read first value in input of " + type + "\n");
            return;
        }
        v[0] = x;
        if (!eatwhitespace(is)) {
            err("istream ended before second value of " + type + "\n");
            return;
        }
        if (!comma(is, "istream ended ater one value and comma in " + type + "\n")) return;
        x = is.nextDouble();
        v[1] = x;
        if (is.fail()) {
            err("Could not read second value in input of " + type + "\n");
            return;
        }
        if (!eatwhitespace(is)) {
            err("istream ended before third value of " + type + "\n");
            return;
        }
        if (!comma(is, "istream ended ater two values and comma in " + type + "\n")) return;
        x = is.nextDouble();
        v[2] = x;
        if (is.fail()) {
            err("Could not read third value in input of " + type + "\n");
            return;
        }
        if (parenthesis) {
            close(is, "Missing closing parenthesis in input of " + type + "\n",
                "No closing parenthesis in input of " + type + "\n");
        }
    }

    /** ZMinputAxisAngle: the axis into v[0..2], the angle into v[3]. */
    public static void inputAxisAngle(CIStream is, double[] v) {
        boolean parenthesis = false;
        if (!eatwhitespace(is)) {
            err("istream ended before trying to input AxisAngle \n");
            return;
        }
        final int c = is.get();
        if (c < 0) {
            fouledup();
            return;
        }
        if (c == '(') {
            parenthesis = true;
            if (!eatwhitespace(is)) {
                err("istream ended after ( trying to input AxisAngle \n");
                return;
            }
        } else {
            is.putback((char) c);
        }
        input3doubles(is, "axis of AxisAngle", v);
        if (is.fail()) return;
        if (!eatwhitespace(is)) {
            err("istream ended before delta of AxisAngle \n");
            return;
        }
        if (!comma(is, "istream ended ater axis and comma in AxisAngle \n")) return;
        v[3] = is.nextDouble();
        if (is.fail()) {
            err("Could not delta value in input of AxisAngle \n");
            return;
        }
        if (parenthesis) {
            close(is, "Missing closing parenthesis in input of AxisAngle \n",
                "No closing parenthesis in input of AxisAngle \n");
        }
    }

    /** ZMinput2doubles: x and y into v[0..1]. */
    public static void input2doubles(CIStream is, String type, double[] v) {
        boolean parenthesis = false;
        if (!eatwhitespace(is)) {
            err("istream ended before trying to input " + type + "\n");
            return;
        }
        final int c = is.get();
        if (c < 0) {
            fouledup();
            return;
        }
        if (c == '(') {
            parenthesis = true;
            if (!eatwhitespace(is)) {
                err("istream ended after ( trying to input " + type + "\n");
                return;
            }
        } else {
            is.putback((char) c);
        }
        v[0] = is.nextDouble();
        if (is.fail()) {
            err("Could not read first value in input of " + type + "\n");
            return;
        }
        if (!eatwhitespace(is)) {
            err("istream ended before second value of " + type + "\n");
            return;
        }
        if (!comma(is, "istream ended ater one value and comma in " + type + "\n")) return;
        v[1] = is.nextDouble();
        if (is.fail()) {
            err("Could not read second value in input of " + type + "\n");
            return;
        }
        if (parenthesis) {
            close(is, "Missing closing parenthesis in input of " + type + "\n",
                "No closing parenthesis in input of " + type + "\n");
        }
    }
}

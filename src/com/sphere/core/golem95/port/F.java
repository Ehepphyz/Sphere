package com.sphere.core.golem95.port;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The Fortran intrinsics the translated Golem95 calls, with gfortran's results: integer powers by
 * libgcc's repeated squaring, NINT rounding half away from zero, SIGN by the sign bit, MODULO with the
 * sign of the divisor, the bit intrinsics on 32-bit integers, blank-insensitive character comparison,
 * and the array reductions and constructions on Java arrays.
 */
public final class F {

    private F() {
    }

    /* ------------------------------------------------------------ numbers */

    /** Integer ** integer, as Fortran defines it (a negative power is 0 unless the base is 1 or -1). */
    public static int ipow(int a, int b) {
        if (b < 0) {
            if (a == 1) {
                return 1;
            }
            if (a == -1) {
                return (b & 1) == 0 ? 1 : -1;
            }
            return 0;
        }
        int r = 1;
        int x = a;
        int n = b;
        while (n != 0) {
            if ((n & 1) != 0) {
                r *= x;
            }
            n >>>= 1;
            if (n != 0) {
                x *= x;
            }
        }
        return r;
    }

    /** libgcc's __powidf2: real ** integer by repeated squaring, the reciprocal last. */
    public static double powi(double x, int m) {
        long n = m < 0 ? -(long) m : m;
        double y = (n % 2) != 0 ? x : 1.0;
        while ((n >>= 1) != 0) {
            x = x * x;
            if ((n % 2) != 0) {
                y = y * x;
            }
        }
        return m < 0 ? 1.0 / y : y;
    }

    /** NINT: to the nearest integer, halves away from zero. */
    public static int nint(double x) {
        return (int) (x >= 0 ? Math.floor(x + 0.5) : -Math.floor(-x + 0.5));
    }

    public static double aint(double x) {
        return x >= 0 ? Math.floor(x) : Math.ceil(x);
    }

    public static double anint(double x) {
        return x >= 0 ? Math.floor(x + 0.5) : -Math.floor(-x + 0.5);
    }

    /** SIGN(a, b): |a| with the sign of b, the sign bit of a zero included (gfortran's copysign). */
    public static double sign(double a, double b) {
        return Math.copySign(Math.abs(a), b);
    }

    public static int isign(int a, int b) {
        final int m = Math.abs(a);
        return b >= 0 ? m : -m;
    }

    /** MODULO for reals: a - floor(a/p) p, the sign of p. */
    public static double modulo(double a, double p) {
        final double r = a % p;
        return (r != 0 && ((r < 0) != (p < 0))) ? r + p : r;
    }

    public static int ishft(int i, int shift) {
        if (shift >= 32 || shift <= -32) {
            return 0;
        }
        return shift >= 0 ? i << shift : i >>> -shift;
    }

    public static boolean btest(int i, int pos) {
        return ((i >>> pos) & 1) != 0;
    }

    public static int ibits(int i, int pos, int len) {
        if (len >= 32) {
            return i >>> pos;
        }
        return (i >>> pos) & ((1 << len) - 1);
    }

    public static int exponent(double x) {
        return x == 0 ? 0 : Math.getExponent(x) + 1;
    }

    public static double fraction(double x) {
        return x == 0 ? 0 : Math.scalb(x, -exponent(x));
    }

    /* ------------------------------------------------------------ characters */

    public static String trim(String s) {
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == ' ') {
            n--;
        }
        return s.substring(0, n);
    }

    public static int lenTrim(String s) {
        return trim(s).length();
    }

    public static String adjustl(String s) {
        int k = 0;
        while (k < s.length() && s.charAt(k) == ' ') {
            k++;
        }
        return s.substring(k) + " ".repeat(k);
    }

    public static String adjustr(String s) {
        final String t = trim(s);
        return " ".repeat(s.length() - t.length()) + t;
    }

    /** INDEX: 1-based position of sub in s, 0 when absent. */
    public static int index(String s, String sub) {
        return s.indexOf(sub) + 1;
    }

    public static int index(String s, String sub, boolean back) {
        return (back ? s.lastIndexOf(sub) : s.indexOf(sub)) + 1;
    }

    public static int verify(String s, String set) {
        for (int i = 0; i < s.length(); i++) {
            if (set.indexOf(s.charAt(i)) < 0) {
                return i + 1;
            }
        }
        return 0;
    }

    public static int scan(String s, String set) {
        for (int i = 0; i < s.length(); i++) {
            if (set.indexOf(s.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return 0;
    }

    public static String repeat(String s, int n) {
        return s.repeat(Math.max(0, n));
    }

    public static String achar(int i) {
        return String.valueOf((char) i);
    }

    public static int iachar(String c) {
        return c.isEmpty() ? 32 : c.charAt(0);
    }

    /** Fortran's character comparison: the shorter padded with blanks. */
    public static boolean streq(String a, String b) {
        return trim(a).equals(trim(b));
    }

    public static int strcmp(String a, String b) {
        final int n = Math.max(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            final char x = i < a.length() ? a.charAt(i) : ' ';
            final char y = i < b.length() ? b.charAt(i) : ' ';
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    /** s(lo:hi), 1-based and inclusive, blank beyond the end. */
    public static String sub(String s, int lo, int hi) {
        if (hi < lo) {
            return "";
        }
        final StringBuilder b = new StringBuilder();
        for (int i = lo; i <= hi; i++) {
            b.append(i - 1 < s.length() && i >= 1 ? s.charAt(i - 1) : ' ');
        }
        return b.toString();
    }

    /** s with s(lo:hi) replaced by v, padded or cut to the length of the substring. */
    public static String setSub(String s, int lo, int hi, String v) {
        final StringBuilder b = new StringBuilder(s);
        while (b.length() < hi) {
            b.append(' ');
        }
        for (int i = lo; i <= hi; i++) {
            final int k = i - lo;
            b.setCharAt(i - 1, k < v.length() ? v.charAt(k) : ' ');
        }
        return b.toString();
    }

    /** A value assigned to a character variable of a fixed length: cut if longer. */
    public static String fit(String v, int len) {
        return v.length() > len ? v.substring(0, len) : v;
    }

    /* ------------------------------------------------------------ arrays: creation */

    public static Cx[] fill(Cx[] a, Cx v) {
        Arrays.fill(a, v);
        return a;
    }

    public static Cx[][] fill(Cx[][] a, Cx v) {
        for (Cx[] r : a) {
            Arrays.fill(r, v);
        }
        return a;
    }

    public static Cx[][][] fill(Cx[][][] a, Cx v) {
        for (Cx[][] r : a) {
            fill(r, v);
        }
        return a;
    }

    public static Cx[][][][] fill(Cx[][][][] a, Cx v) {
        for (Cx[][][] r : a) {
            fill(r, v);
        }
        return a;
    }

    public static String[] fill(String[] a, String v) {
        Arrays.fill(a, v);
        return a;
    }

    public static String[][] fill(String[][] a, String v) {
        for (String[] r : a) {
            Arrays.fill(r, v);
        }
        return a;
    }

    public static <T> T[] fill(T[] a, Supplier<? extends T> s) {
        for (int i = 0; i < a.length; i++) {
            a[i] = s.get();
        }
        return a;
    }

    public static <T> T[][] fill(T[][] a, Supplier<? extends T> s) {
        for (T[] r : a) {
            fill(r, s);
        }
        return a;
    }

    /** Fills every element of an object array of any rank, the innermost ones from the supplier. */
    public static <A> A fillAll(A array, Supplier<?> s) {
        fillDeep(array, s);
        return array;
    }

    private static void fillDeep(Object a, Supplier<?> s) {
        final int n = Array.getLength(a);
        final boolean inner = !a.getClass().getComponentType().isArray();
        for (int i = 0; i < n; i++) {
            if (inner) {
                Array.set(a, i, s.get());
            } else {
                fillDeep(Array.get(a, i), s);
            }
        }
    }

    public static double[] filled(double[] a, double v) {
        Arrays.fill(a, v);
        return a;
    }

    public static double[][] filled(double[][] a, double v) {
        for (double[] r : a) {
            Arrays.fill(r, v);
        }
        return a;
    }

    public static int[] filled(int[] a, int v) {
        Arrays.fill(a, v);
        return a;
    }

    public static int[][] filled(int[][] a, int v) {
        for (int[] r : a) {
            Arrays.fill(r, v);
        }
        return a;
    }

    public static boolean[] filled(boolean[] a, boolean v) {
        Arrays.fill(a, v);
        return a;
    }

    public static Cx[] filled(Cx[] a, Cx v) {
        return fill(a, v);
    }

    public static Cx[][] filled(Cx[][] a, Cx v) {
        return fill(a, v);
    }

    public static String[] filled(String[] a, String v) {
        return fill(a, v);
    }

    /** Sets the elements and lower bounds of an allocatable array; a null pointer gets a wrapper. */
    public static <A> Alloc<A> allocated(Alloc<A> x, A data, int[] lb) {
        final Alloc<A> r = x == null ? new Alloc<>() : x;
        r.a = data;
        r.lb = lb;
        return r;
    }

    /* ------------------------------------------------------------ arrays: copies */

    /** A deep copy of any Java array the translation uses. */
    public static Object copyAny(Object a) {
        if (a == null) {
            return null;
        }
        final Class<?> c = a.getClass();
        if (!c.isArray()) {
            return a instanceof FType t ? t.copyValue() : a;
        }
        final int n = Array.getLength(a);
        final Object out = Array.newInstance(c.getComponentType(), n);
        if (c.getComponentType().isPrimitive()) {
            System.arraycopy(a, 0, out, 0, n);
            return out;
        }
        for (int i = 0; i < n; i++) {
            Array.set(out, i, copyAny(Array.get(a, i)));
        }
        return out;
    }

    public static double[] copyOf(double[] a) {
        return a == null ? null : a.clone();
    }

    public static int[] copyOf(int[] a) {
        return a == null ? null : a.clone();
    }

    public static boolean[] copyOf(boolean[] a) {
        return a == null ? null : a.clone();
    }

    public static Cx[] copyOf(Cx[] a) {
        return a == null ? null : a.clone();
    }

    public static String[] copyOf(String[] a) {
        return a == null ? null : a.clone();
    }

    @SuppressWarnings("unchecked")
    public static <T> T copyOf(T a) {
        return (T) copyAny(a);
    }

    /* ------------------------------------------------------------ arrays: shape */

    public static int len2(Object a) {
        return Array.getLength(a) == 0 ? 0 : Array.getLength(Array.get(a, 0));
    }

    public static int len3(Object a) {
        if (Array.getLength(a) == 0) {
            return 0;
        }
        return len2(Array.get(a, 0));
    }

    public static int len4(Object a) {
        if (Array.getLength(a) == 0) {
            return 0;
        }
        return len3(Array.get(a, 0));
    }

    public static int len5(Object a) {
        return Array.getLength(a) == 0 ? 0 : len4(Array.get(a, 0));
    }

    public static int len6(Object a) {
        return Array.getLength(a) == 0 ? 0 : len5(Array.get(a, 0));
    }

    public static int len7(Object a) {
        return Array.getLength(a) == 0 ? 0 : len6(Array.get(a, 0));
    }

    /** The number of elements of lo:hi:step. */
    public static int extent(int lo, int hi, int step) {
        return Math.max(0, (hi - lo + step) / step);
    }

    public static int size(Object a) {
        if (a == null || !a.getClass().isArray()) {
            return 1;
        }
        final int n = Array.getLength(a);
        if (n == 0 || !a.getClass().getComponentType().isArray()) {
            return n;
        }
        return n * size(Array.get(a, 0));
    }

    public static int sizeDim(Object a, int dim) {
        Object x = a;
        for (int k = 1; k < dim; k++) {
            x = Array.getLength(x) == 0 ? null : Array.get(x, 0);
            if (x == null) {
                return 0;
            }
        }
        return Array.getLength(x);
    }

    public static int[] shape(Object a) {
        final ArrayList<Integer> out = new ArrayList<>();
        Object x = a;
        while (x != null && x.getClass().isArray()) {
            final int n = Array.getLength(x);
            out.add(n);
            x = n == 0 ? null : Array.get(x, 0);
            if (x != null && !x.getClass().isArray()) {
                break;
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    public static boolean sameShape(Object a, int... n) {
        final int[] s = shape(a);
        if (s.length != n.length) {
            return s.length < n.length && Arrays.stream(n).anyMatch(v -> v == 0) == false ? false : Arrays.equals(s, n);
        }
        return Arrays.equals(s, n);
    }

    /* ------------------------------------------------------------ arrays: reductions */

    public static int sum(int[] a) {
        int s = 0;
        for (int v : a) {
            s += v;
        }
        return s;
    }

    public static double sum(double[] a) {
        double s = 0;
        for (double v : a) {
            s += v;
        }
        return s;
    }

    public static Cx sum(Cx[] a) {
        double re = 0, im = 0;
        for (Cx v : a) {
            re += v.re;
            im += v.im;
        }
        return new Cx(re, im);
    }

    public static double sum(double[][] a) {
        // column-major order, as gfortran adds
        double s = 0;
        final int n = a.length, m = len2(a);
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < n; i++) {
                s += a[i][j];
            }
        }
        return s;
    }

    public static Cx sum(Cx[][] a) {
        double re = 0, im = 0;
        final int n = a.length, m = len2(a);
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < n; i++) {
                re += a[i][j].re;
                im += a[i][j].im;
            }
        }
        return new Cx(re, im);
    }

    public static int sum(int[][] a) {
        int s = 0;
        for (int[] r : a) {
            s += sum(r);
        }
        return s;
    }

    public static double sum(double[] a, boolean[] mask) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            if (mask[i]) {
                s += a[i];
            }
        }
        return s;
    }

    public static int sum(int[] a, boolean[] mask) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            if (mask[i]) {
                s += a[i];
            }
        }
        return s;
    }

    public static Cx sum(Cx[] a, boolean[] mask) {
        double re = 0, im = 0;
        for (int i = 0; i < a.length; i++) {
            if (mask[i]) {
                re += a[i].re;
                im += a[i].im;
            }
        }
        return new Cx(re, im);
    }

    /** SUM(a, dim) of a matrix: dim 1 sums each column, dim 2 each row. */
    public static double[] sumDim(double[][] a, int dim) {
        final int n = a.length, m = len2(a);
        if (dim == 1) {
            final double[] out = new double[m];
            for (int j = 0; j < m; j++) {
                double s = 0;
                for (int i = 0; i < n; i++) {
                    s += a[i][j];
                }
                out[j] = s;
            }
            return out;
        }
        final double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < m; j++) {
                s += a[i][j];
            }
            out[i] = s;
        }
        return out;
    }

    public static Cx[] sumDim(Cx[][] a, int dim) {
        final int n = a.length, m = len2(a);
        if (dim == 1) {
            final Cx[] out = new Cx[m];
            for (int j = 0; j < m; j++) {
                double re = 0, im = 0;
                for (int i = 0; i < n; i++) {
                    re += a[i][j].re;
                    im += a[i][j].im;
                }
                out[j] = new Cx(re, im);
            }
            return out;
        }
        final Cx[] out = new Cx[n];
        for (int i = 0; i < n; i++) {
            double re = 0, im = 0;
            for (int j = 0; j < m; j++) {
                re += a[i][j].re;
                im += a[i][j].im;
            }
            out[i] = new Cx(re, im);
        }
        return out;
    }

    public static double product(double[] a) {
        double s = 1;
        for (double v : a) {
            s *= v;
        }
        return s;
    }

    public static int product(int[] a) {
        int s = 1;
        for (int v : a) {
            s *= v;
        }
        return s;
    }

    public static Cx product(Cx[] a) {
        Cx s = Cx.ONE;
        for (Cx v : a) {
            s = Cx.mul(s, v);
        }
        return s;
    }

    public static double maxval(double[] a) {
        double m = -Double.MAX_VALUE;
        for (double v : a) {
            if (v > m || Double.isNaN(m)) {
                m = v;
            }
        }
        return m;
    }

    public static int maxval(int[] a) {
        int m = Integer.MIN_VALUE;
        for (int v : a) {
            m = Math.max(m, v);
        }
        return m;
    }

    public static double maxval(double[][] a) {
        double m = -Double.MAX_VALUE;
        for (double[] r : a) {
            m = Math.max(m, maxval(r));
        }
        return m;
    }

    public static double maxval(double[] a, boolean[] mask) {
        double m = -Double.MAX_VALUE;
        for (int i = 0; i < a.length; i++) {
            if (mask[i] && a[i] > m) {
                m = a[i];
            }
        }
        return m;
    }

    public static int maxval(int[] a, boolean[] mask) {
        int m = Integer.MIN_VALUE;
        for (int i = 0; i < a.length; i++) {
            if (mask[i]) {
                m = Math.max(m, a[i]);
            }
        }
        return m;
    }

    public static double minval(double[] a) {
        double m = Double.MAX_VALUE;
        for (double v : a) {
            if (v < m) {
                m = v;
            }
        }
        return m;
    }

    public static int minval(int[] a) {
        int m = Integer.MAX_VALUE;
        for (int v : a) {
            m = Math.min(m, v);
        }
        return m;
    }

    public static double minval(double[][] a) {
        double m = Double.MAX_VALUE;
        for (double[] r : a) {
            m = Math.min(m, minval(r));
        }
        return m;
    }

    public static double minval(double[] a, boolean[] mask) {
        double m = Double.MAX_VALUE;
        for (int i = 0; i < a.length; i++) {
            if (mask[i] && a[i] < m) {
                m = a[i];
            }
        }
        return m;
    }

    public static int minval(int[] a, boolean[] mask) {
        int m = Integer.MAX_VALUE;
        for (int i = 0; i < a.length; i++) {
            if (mask[i]) {
                m = Math.min(m, a[i]);
            }
        }
        return m;
    }

    public static int count(boolean[] m) {
        int n = 0;
        for (boolean b : m) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    public static int count(boolean[][] m) {
        int n = 0;
        for (boolean[] r : m) {
            n += count(r);
        }
        return n;
    }

    public static boolean any(boolean[] m) {
        for (boolean b : m) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    public static boolean all(boolean[] m) {
        for (boolean b : m) {
            if (!b) {
                return false;
            }
        }
        return true;
    }

    public static boolean any(boolean[][] m) {
        for (boolean[] r : m) {
            if (any(r)) {
                return true;
            }
        }
        return false;
    }

    public static boolean all(boolean[][] m) {
        for (boolean[] r : m) {
            if (!all(r)) {
                return false;
            }
        }
        return true;
    }

    /** MAXLOC: the 1-based position of the first largest element. */
    public static int[] maxloc(double[] a) {
        int k = a.length == 0 ? 0 : 1;
        for (int i = 1; i < a.length; i++) {
            if (a[i] > a[k - 1]) {
                k = i + 1;
            }
        }
        return new int[]{k};
    }

    public static int[] maxloc(int[] a) {
        int k = a.length == 0 ? 0 : 1;
        for (int i = 1; i < a.length; i++) {
            if (a[i] > a[k - 1]) {
                k = i + 1;
            }
        }
        return new int[]{k};
    }

    public static int[] minloc(double[] a) {
        int k = a.length == 0 ? 0 : 1;
        for (int i = 1; i < a.length; i++) {
            if (a[i] < a[k - 1]) {
                k = i + 1;
            }
        }
        return new int[]{k};
    }

    public static int[] minloc(int[] a) {
        int k = a.length == 0 ? 0 : 1;
        for (int i = 1; i < a.length; i++) {
            if (a[i] < a[k - 1]) {
                k = i + 1;
            }
        }
        return new int[]{k};
    }

    public static int[] maxloc(double[] a, boolean[] mask) {
        int k = 0;
        for (int i = 0; i < a.length; i++) {
            if (mask[i] && (k == 0 || a[i] > a[k - 1])) {
                k = i + 1;
            }
        }
        return new int[]{k};
    }

    public static int[] minloc(double[] a, boolean[] mask) {
        int k = 0;
        for (int i = 0; i < a.length; i++) {
            if (mask[i] && (k == 0 || a[i] < a[k - 1])) {
                k = i + 1;
            }
        }
        return new int[]{k};
    }

    public static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }

    /** DOT_PRODUCT of complex vectors conjugates the first. */
    public static Cx dot(Cx[] a, Cx[] b) {
        Cx s = Cx.ZERO;
        for (int i = 0; i < a.length; i++) {
            s = Cx.add(s, Cx.mul(Cx.conj(a[i]), b[i]));
        }
        return s;
    }

    /* ------------------------------------------------------------ arrays: matrices */

    public static double[][] transpose(double[][] a) {
        final int n = a.length, m = len2(a);
        final double[][] t = new double[m][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                t[j][i] = a[i][j];
            }
        }
        return t;
    }

    public static Cx[][] transpose(Cx[][] a) {
        final int n = a.length, m = len2(a);
        final Cx[][] t = new Cx[m][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                t[j][i] = a[i][j];
            }
        }
        return t;
    }

    public static int[][] transpose(int[][] a) {
        final int n = a.length, m = len2(a);
        final int[][] t = new int[m][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                t[j][i] = a[i][j];
            }
        }
        return t;
    }

    public static double[][] matmul(double[][] a, double[][] b) {
        final int n = a.length, k = len2(a), m = len2(b);
        final double[][] c = new double[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                double s = 0;
                for (int l = 0; l < k; l++) {
                    s += a[i][l] * b[l][j];
                }
                c[i][j] = s;
            }
        }
        return c;
    }

    public static Cx[][] matmul(Cx[][] a, Cx[][] b) {
        final int n = a.length, k = len2(a), m = len2(b);
        final Cx[][] c = new Cx[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                Cx s = Cx.ZERO;
                for (int l = 0; l < k; l++) {
                    s = Cx.add(s, Cx.mul(a[i][l], b[l][j]));
                }
                c[i][j] = s;
            }
        }
        return c;
    }

    public static double[] matmul(double[][] a, double[] b) {
        final int n = a.length, k = len2(a);
        final double[] c = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int l = 0; l < k; l++) {
                s += a[i][l] * b[l];
            }
            c[i] = s;
        }
        return c;
    }

    public static double[] matmul(double[] a, double[][] b) {
        final int k = a.length, m = len2(b);
        final double[] c = new double[m];
        for (int j = 0; j < m; j++) {
            double s = 0;
            for (int l = 0; l < k; l++) {
                s += a[l] * b[l][j];
            }
            c[j] = s;
        }
        return c;
    }

    public static Cx[] matmul(Cx[][] a, Cx[] b) {
        final int n = a.length, k = len2(a);
        final Cx[] c = new Cx[n];
        for (int i = 0; i < n; i++) {
            Cx s = Cx.ZERO;
            for (int l = 0; l < k; l++) {
                s = Cx.add(s, Cx.mul(a[i][l], b[l]));
            }
            c[i] = s;
        }
        return c;
    }

    public static Cx[] matmul(Cx[] a, Cx[][] b) {
        final int k = a.length, m = len2(b);
        final Cx[] c = new Cx[m];
        for (int j = 0; j < m; j++) {
            Cx s = Cx.ZERO;
            for (int l = 0; l < k; l++) {
                s = Cx.add(s, Cx.mul(a[l], b[l][j]));
            }
            c[j] = s;
        }
        return c;
    }

    public static Cx[] matmul(double[][] a, Cx[] b) {
        final int n = a.length, k = len2(a);
        final Cx[] c = new Cx[n];
        for (int i = 0; i < n; i++) {
            Cx s = Cx.ZERO;
            for (int l = 0; l < k; l++) {
                s = Cx.add(s, Cx.mul(a[i][l], b[l]));
            }
            c[i] = s;
        }
        return c;
    }

    public static Cx[][] matmul(double[][] a, Cx[][] b) {
        final int n = a.length, k = len2(a), m = len2(b);
        final Cx[][] c = new Cx[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                Cx s = Cx.ZERO;
                for (int l = 0; l < k; l++) {
                    s = Cx.add(s, Cx.mul(a[i][l], b[l][j]));
                }
                c[i][j] = s;
            }
        }
        return c;
    }

    public static Cx[][] matmul(Cx[][] a, double[][] b) {
        final int n = a.length, k = len2(a), m = len2(b);
        final Cx[][] c = new Cx[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                Cx s = Cx.ZERO;
                for (int l = 0; l < k; l++) {
                    s = Cx.add(s, Cx.mul(a[i][l], b[l][j]));
                }
                c[i][j] = s;
            }
        }
        return c;
    }

    /* ------------------------------------------------------------ arrays: pack, constructors */

    public static int[] pack(int[] a, boolean[] mask) {
        return Arrays.stream(indices(mask)).map(i -> a[i]).toArray();
    }

    public static double[] pack(double[] a, boolean[] mask) {
        return Arrays.stream(indices(mask)).mapToDouble(i -> a[i]).toArray();
    }

    public static Cx[] pack(Cx[] a, boolean[] mask) {
        return Arrays.stream(indices(mask)).mapToObj(i -> a[i]).toArray(Cx[]::new);
    }

    public static int[] pack(int[] a, boolean[] mask, int[] vector) {
        final int[] p = pack(a, mask);
        final int[] out = vector.clone();
        System.arraycopy(p, 0, out, 0, p.length);
        return out;
    }

    private static int[] indices(boolean[] mask) {
        int n = 0;
        for (boolean b : mask) {
            if (b) {
                n++;
            }
        }
        final int[] idx = new int[n];
        int k = 0;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) {
                idx[k++] = i;
            }
        }
        return idx;
    }

    /* PACK and UNPACK of a matrix go through its elements in Fortran's order, column by column. */

    public static double[] pack(double[][] a, boolean[][] mask) {
        final ArrayList<Double> out = new ArrayList<>();
        final int n = a.length, m = len2(a);
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < n; i++) {
                if (mask[i][j]) {
                    out.add(a[i][j]);
                }
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    public static double[] pack(double[][] a, boolean mask) {
        final int n = a.length, m = len2(a);
        final double[] out = new double[mask ? n * m : 0];
        int k = 0;
        for (int j = 0; j < m && mask; j++) {
            for (int i = 0; i < n; i++) {
                out[k++] = a[i][j];
            }
        }
        return out;
    }

    public static Cx[] pack(Cx[][] a, boolean[][] mask) {
        final ArrayList<Cx> out = new ArrayList<>();
        final int n = a.length, m = len2(a);
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < n; i++) {
                if (mask[i][j]) {
                    out.add(a[i][j]);
                }
            }
        }
        return out.toArray(new Cx[0]);
    }

    public static Cx[] pack(Cx[][] a, boolean mask) {
        final int n = a.length, m = len2(a);
        final Cx[] out = new Cx[mask ? n * m : 0];
        int k = 0;
        for (int j = 0; j < m && mask; j++) {
            for (int i = 0; i < n; i++) {
                out[k++] = a[i][j];
            }
        }
        return out;
    }

    public static double[][] unpack(double[] v, boolean[][] mask, double[][] field) {
        final int n = mask.length, m = len2(mask);
        final double[][] out = copyOf(field);
        int k = 0;
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < n; i++) {
                if (mask[i][j]) {
                    out[i][j] = v[k++];
                }
            }
        }
        return out;
    }

    public static Cx[][] unpack(Cx[] v, boolean[][] mask, Cx[][] field) {
        final int n = mask.length, m = len2(mask);
        final Cx[][] out = copyOf(field);
        int k = 0;
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < n; i++) {
                if (mask[i][j]) {
                    out[i][j] = v[k++];
                }
            }
        }
        return out;
    }

    public static int[] unpack(int[] v, boolean[] mask, int[] field) {
        final int[] out = field.clone();
        int k = 0;
        for (int i = 0; i < mask.length; i++) {
            if (mask[i]) {
                out[i] = v[k++];
            }
        }
        return out;
    }

    public static int[] unpackScalar(int[] v, boolean[] mask, int field) {
        final int[] out = new int[mask.length];
        int k = 0;
        for (int i = 0; i < mask.length; i++) {
            out[i] = mask[i] ? v[k++] : field;
        }
        return out;
    }

    public static int[] cshift(int[] a, int shift) {
        final int n = a.length;
        final int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = a[Math.floorMod(i + shift, n)];
        }
        return out;
    }

    /** RESHAPE to n x m, filled column by column, or row by row for ORDER=(/2,1/). */
    public static double[][] reshape2(double[] src, int n, int m, boolean rowMajor) {
        final double[][] out = new double[n][m];
        for (int k = 0; k < n * m && k < src.length; k++) {
            if (rowMajor) {
                out[k / m][k % m] = src[k];
            } else {
                out[k % n][k / n] = src[k];
            }
        }
        return out;
    }

    public static int[][] reshape2(int[] src, int n, int m, boolean rowMajor) {
        final int[][] out = new int[n][m];
        for (int k = 0; k < n * m && k < src.length; k++) {
            if (rowMajor) {
                out[k / m][k % m] = src[k];
            } else {
                out[k % n][k / n] = src[k];
            }
        }
        return out;
    }

    public static Cx[][] reshape2(Cx[] src, int n, int m, boolean rowMajor) {
        final Cx[][] out = fill(new Cx[n][m], Cx.ZERO);
        for (int k = 0; k < n * m && k < src.length; k++) {
            if (rowMajor) {
                out[k / m][k % m] = src[k];
            } else {
                out[k % n][k / n] = src[k];
            }
        }
        return out;
    }

    public static void addAll(ArrayList<Object> list, Object array) {
        final int n = Array.getLength(array);
        for (int i = 0; i < n; i++) {
            list.add(Array.get(array, i));
        }
    }

    public static int[] toIntArray(ArrayList<Object> l) {
        return l.stream().mapToInt(o -> ((Number) o).intValue()).toArray();
    }

    public static double[] toDoubleArray(ArrayList<Object> l) {
        return l.stream().mapToDouble(o -> ((Number) o).doubleValue()).toArray();
    }

    public static Cx[] toCxArray(ArrayList<Object> l) {
        return l.stream().map(o -> o instanceof Cx c ? c : Cx.of(((Number) o).doubleValue())).toArray(Cx[]::new);
    }

    public static boolean[] toBooleanArray(ArrayList<Object> l) {
        final boolean[] out = new boolean[l.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (Boolean) l.get(i);
        }
        return out;
    }

    public static String[] toStringArray(ArrayList<Object> l) {
        return l.stream().map(String::valueOf).toArray(String[]::new);
    }

    /* ------------------------------------------------------------ output */

    /**
     * A record as WRITE makes it: list-directed (fmt null) roughly as gfortran writes it, or after a
     * format of A, I, F, E, ES, G, X and quoted text, which is all Golem95 and OneLOop use.
     */
    public static String format(String fmt, Object[] items) {
        final ArrayList<Object> flat = new ArrayList<>();
        for (Object o : items) {
            if (o != null && o.getClass().isArray()) {
                addAll(flat, o);
            } else {
                flat.add(o);
            }
        }
        if (fmt == null) {
            final StringBuilder b = new StringBuilder();
            for (Object o : flat) {
                b.append(' ').append(listItem(o));
            }
            return b.toString();
        }
        return Formats.apply(fmt, flat);
    }

    static String listItem(Object o) {
        if (o instanceof Integer i) {
            return String.format(Locale.ROOT, "%11d", i);
        }
        if (o instanceof Double d) {
            return String.format(Locale.ROOT, "%25.17E", d);
        }
        if (o instanceof Cx c) {
            return String.format(Locale.ROOT, "(%25.17E,%25.17E)", c.re, c.im);
        }
        if (o instanceof Boolean b) {
            return b ? "T" : "F";
        }
        return String.valueOf(o);
    }
}

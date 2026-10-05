package com.sphere.core.minuit2;

import com.sphere.core.hepmc3.cxx.COStream;

/**
 * The linear algebra of Minuit2 (MnMatrix.cxx), operation for operation: the
 * f2c'd BLAS kernels daxpy, dscal, dspmv and dspr, the inversion mnvert, the
 * eigenvalue routine mneigen, and the products the expression templates
 * evaluate to. Each loop runs its sums in the C++ order, so each result has
 * the C++ rounding.
 */
public final class MnMatrix {

    private MnMatrix() {
    }

    private static final ThreadLocal<int[]> MAX_NP = ThreadLocal.withInitial(() -> new int[] {10});

    /** MnMatrix::SetMaxNP: how many elements a vector or matrix prints; returns the previous value. */
    public static int setMaxNP(int value) {
        final int[] m = MAX_NP.get();
        final int prev = m[0];
        m[0] = value;
        return prev;
    }

    public static int maxNP() {
        return MAX_NP.get()[0];
    }

    /* ---- BLAS kernels --------------------------------------------------------- */

    /** dy += da * dx, unrolled by four as the f2c code (the unrolling does not change a rounding). */
    static void mndaxpy(int n, double da, double[] dx, double[] dy) {
        if (n <= 0) return;
        if (da == 0.) return;
        for (int i = 0; i < n; i++) {
            dy[i] += da * dx[i];
        }
    }

    /** dx *= da. */
    static void mndscal(int n, double da, double[] dx, int offset) {
        for (int i = 0; i < n; ++i) {
            dx[offset + i] *= da;
        }
    }

    /** y = alpha * A x + beta * y, A symmetric packed (dspmv, upper storage of the column-major lower triangle). */
    static void mndspmv(int n, double alpha, double[] ap, double[] x, double beta, double[] y) {
        if (n == 0 || (alpha == 0. && beta == 1.)) return;
        if (beta != 1.) {
            if (beta == 0.) {
                for (int i = 0; i < n; ++i) y[i] = 0.;
            } else {
                for (int i = 0; i < n; ++i) y[i] = beta * y[i];
            }
        }
        if (alpha == 0.) return;
        // 1-based as in the f2c code: ap[k] is ap[k - 1] here
        int kk = 1;
        for (int j = 1; j <= n; ++j) {
            final double temp1 = alpha * x[j - 1];
            double temp2 = 0.;
            int k = kk;
            for (int i = 1; i <= j - 1; ++i) {
                y[i - 1] += temp1 * ap[k - 1];
                temp2 += ap[k - 1] * x[i - 1];
                ++k;
            }
            y[j - 1] = y[j - 1] + temp1 * ap[kk + j - 2] + alpha * temp2;
            kk += j;
        }
    }

    /** ap += alpha x x^T (dspr). */
    static void mndspr(int n, double alpha, double[] x, double[] ap) {
        if (n == 0 || alpha == 0.) return;
        int kk = 1;
        for (int j = 1; j <= n; ++j) {
            if (x[j - 1] != 0.) {
                final double temp = alpha * x[j - 1];
                int k = kk;
                for (int i = 1; i <= j; ++i) {
                    ap[k - 1] += x[i - 1] * temp;
                    ++k;
                }
            }
            kk += j;
        }
    }

    /* ---- expressions ---------------------------------------------------------- */

    /** LAVector(f * v): a copy scaled by f. */
    public static LAVector scaled(double f, LAVector v) {
        return v.copy().scale(f);
    }

    /**
     * fa * a + fb * b, as the C++ builds it into a new vector: b copied and
     * scaled by fb, then fa * a added (the (LAVector, A) specialisation).
     */
    public static LAVector sum(double fa, LAVector a, double fb, LAVector b) {
        final LAVector r = b.copy().scale(fb);
        r.plusAssign(fa, a);
        r.scale(1.);
        return r;
    }

    /** a + b. */
    public static LAVector add(LAVector a, LAVector b) {
        return sum(1., a, 1., b);
    }

    /** a - b. */
    public static LAVector subtract(LAVector a, LAVector b) {
        return sum(1., a, -1. * 1., b);
    }

    /** a + f * b (st.Vec() + slam * step). */
    public static LAVector addScaled(LAVector a, double f, LAVector b) {
        return sum(1., a, f, b);
    }

    /** LAVector(f * M * v) with M and v plain: dspmv with alpha f. */
    public static LAVector times(double f, LASymMatrix m, LAVector v) {
        final LAVector y = new LAVector(v.size());
        mndspmv(v.size(), f, m.data, v.data, 0., y.data);
        return y;
    }

    /** M * v. */
    public static LAVector times(LASymMatrix m, LAVector v) {
        return times(1., m, v);
    }

    /** LASymMatrix(f * Outer_product(v)): zeros, then dspr with alpha f. */
    public static LASymMatrix outer(double f, LAVector v) {
        final LASymMatrix r = new LASymMatrix(v.size());
        mndspr(v.size(), f, v.data, r.data);
        return r;
    }

    /** LASymMatrix(f * M): a copy scaled by f. */
    public static LASymMatrix scaled(double f, LASymMatrix m) {
        return m.copy().scale(f);
    }

    /** LASymMatrix(a - b): b copied and negated, then a added. */
    public static LASymMatrix subtract(LASymMatrix a, LASymMatrix b) {
        final LASymMatrix r = b.copy().scale(-1. * 1.);
        r.plusAssign(1., a);
        return r;
    }

    /* ---- functions ------------------------------------------------------------ */

    /** std::inner_product from 0.0, in index order. */
    public static double innerProduct(LAVector v1, LAVector v2) {
        double s = 0.0;
        final double[] a = v1.data, b = v2.data;
        for (int i = 0; i < a.length; i++) {
            s = s + a[i] * b[i];
        }
        return s;
    }

    /** v^T M v: M v by dspmv, then the inner product. */
    public static double similarity(LAVector v, LASymMatrix m) {
        final LAVector tmp = times(m, v);
        return innerProduct(v, tmp);
    }

    /** The sum of the absolute values of the stored elements (the lower triangle once). */
    public static double sumOfElements(LASymMatrix m) {
        double out = 0.0;
        for (double d : m.data) out += Math.abs(d);
        return out;
    }

    public static double sumOfElements(LAVector v) {
        double out = 0.0;
        for (double d : v.data) out += Math.abs(d);
        return out;
    }

    /** Inverts in place; 0 when it worked, 1 when the matrix was not positive. */
    public static int invert(LASymMatrix t) {
        int ifail = 0;
        if (t.size() == 1) {
            final double tmp = t.data[0];
            if (!(tmp > 0.)) ifail = 1;
            else t.data[0] = 1. / tmp;
        } else {
            ifail = mnvert(t);
        }
        return ifail;
    }

    /** Inversion after scaling to a unit diagonal, without pivoting (the matrix is meant positive-definite). */
    static int mnvert(LASymMatrix a) {
        final int nrow = a.nrow;
        final double[] s = new double[nrow];
        final double[] q = new double[nrow];
        final double[] pp = new double[nrow];
        final double[] d = a.data;
        for (int i = 0; i < nrow; i++) {
            final double si = d[LASymMatrix.index(i, i)];
            if (si < 0.) return 1;
            s[i] = 1. / Math.sqrt(si);
        }
        for (int i = 0; i < nrow; i++) {
            for (int j = i; j < nrow; j++) {
                d[LASymMatrix.index(i, j)] *= (s[i] * s[j]);
            }
        }
        for (int i = 0; i < nrow; i++) {
            int k = i;
            if (d[LASymMatrix.index(k, k)] == 0.) return 1;
            q[k] = 1. / d[LASymMatrix.index(k, k)];
            pp[k] = 1.;
            d[LASymMatrix.index(k, k)] = 0.;
            final int kp1 = k + 1;
            if (k != 0) {
                for (int j = 0; j < k; j++) {
                    final int jk = LASymMatrix.index(j, k);
                    pp[j] = d[jk];
                    q[j] = d[jk] * q[k];
                    d[jk] = 0.;
                }
            }
            if (k != nrow - 1) {
                for (int j = kp1; j < nrow; j++) {
                    final int kj = LASymMatrix.index(k, j);
                    pp[j] = d[kj];
                    q[j] = -d[kj] * q[k];
                    d[kj] = 0.;
                }
            }
            for (int j = 0; j < nrow; j++) {
                for (k = j; k < nrow; k++) {
                    d[LASymMatrix.index(j, k)] += (pp[j] * q[k]);
                }
            }
        }
        for (int j = 0; j < nrow; j++) {
            for (int k = j; k < nrow; k++) {
                d[LASymMatrix.index(j, k)] *= (s[j] * s[k]);
            }
        }
        return 0;
    }

    /** The eigenvalues in increasing order (mneigen on the full matrix). */
    public static LAVector eigenvalues(LASymMatrix mat) {
        final int nrow = mat.nrow;
        final double[] tmp = new double[nrow * nrow];
        final double[] work = new double[2 * nrow];
        for (int i = 0; i < nrow; i++) {
            for (int j = 0; j <= i; j++) {
                tmp[i + j * nrow] = mat.get(i, j);
                tmp[i * nrow + j] = mat.get(i, j);
            }
        }
        mneigen(tmp, nrow, nrow, work.length, work);
        final LAVector result = new LAVector(nrow);
        System.arraycopy(work, 0, result.data, 0, nrow);
        return result;
    }

    /**
     * mneigen.F through f2c: Householder reduction to tridiagonal form, then QL
     * iterations; a is column-major ndima x n (1-based through A()), work holds
     * the eigenvalues in its first n places on return.
     */
    static int mneigen(double[] a0, int ndima, int n, int mits, double[] work0) {
        final double precis = 1.e-6;
        final M a = new M(a0, ndima);
        final W work = new W(work0);
        double b, c, f, h, r, s, hh, gl, pr, pt;
        int i, j, k, l, m = 0, i0, i1, j1, m1, n1;
        int ifault = 1;
        i = n;
        for (i1 = 2; i1 <= n; ++i1) {
            l = i - 2;
            f = a.g(i, i - 1);
            gl = 0.;
            if (l >= 1) {
                for (k = 1; k <= l; ++k) {
                    final double r1 = a.g(i, k);
                    gl += r1 * r1;
                }
            }
            h = gl + f * f;
            if (gl > 1e-35) {
                ++l;
                gl = Math.sqrt(h);
                if (f >= 0.) gl = -gl;
                work.s(n + i, gl);
                h -= f * gl;
                a.s(i, i - 1, f - gl);
                f = 0.;
                for (j = 1; j <= l; ++j) {
                    a.s(j, i, a.g(i, j) / h);
                    gl = 0.;
                    for (k = 1; k <= j; ++k) {
                        gl += a.g(j, k) * a.g(i, k);
                    }
                    if (j < l) {
                        j1 = j + 1;
                        for (k = j1; k <= l; ++k) {
                            gl += a.g(k, j) * a.g(i, k);
                        }
                    }
                    work.s(n + j, gl / h);
                    f += gl * a.g(j, i);
                }
                hh = f / (h + h);
                for (j = 1; j <= l; ++j) {
                    f = a.g(i, j);
                    gl = work.g(n + j) - hh * f;
                    work.s(n + j, gl);
                    for (k = 1; k <= j; ++k) {
                        a.s(j, k, a.g(j, k) - f * work.g(n + k) - gl * a.g(i, k));
                    }
                }
                work.s(i, h);
            } else {
                work.s(i, 0.);
                work.s(n + i, f);
            }
            --i;
        }
        work.s(1, 0.);
        work.s(n + 1, 0.);
        for (i = 1; i <= n; ++i) {
            l = i - 1;
            if (!(work.g(i) == 0. || l == 0)) {
                for (j = 1; j <= l; ++j) {
                    gl = 0.;
                    for (k = 1; k <= l; ++k) {
                        gl += a.g(i, k) * a.g(k, j);
                    }
                    for (k = 1; k <= l; ++k) {
                        a.s(k, j, a.g(k, j) - gl * a.g(k, i));
                    }
                }
            }
            work.s(i, a.g(i, i));
            a.s(i, i, 1.);
            if (l != 0) {
                for (j = 1; j <= l; ++j) {
                    a.s(i, j, 0.);
                    a.s(j, i, 0.);
                }
            }
        }
        n1 = n - 1;
        for (i = 2; i <= n; ++i) {
            i0 = n + i - 1;
            work.s(i0, work.g(i0 + 1));
        }
        work.s(n + n, 0.);
        b = 0.;
        f = 0.;
        for (l = 1; l <= n; ++l) {
            j = 0;
            h = precis * (Math.abs(work.g(l)) + Math.abs(work.g(n + l)));
            if (b < h) b = h;
            for (m1 = l; m1 <= n; ++m1) {
                m = m1;
                if (Math.abs(work.g(n + m)) <= b) break;
            }
            if (m != l) {
                do {
                    if (j == mits) return ifault;
                    ++j;
                    pt = (work.g(l + 1) - work.g(l)) / (work.g(n + l) * 2.);
                    r = Math.sqrt(pt * pt + 1.);
                    pr = pt + r;
                    if (pt < 0.) pr = pt - r;
                    h = work.g(l) - work.g(n + l) / pr;
                    for (i = l; i <= n; ++i) {
                        work.s(i, work.g(i) - h);
                    }
                    f += h;
                    pt = work.g(m);
                    c = 1.;
                    s = 0.;
                    m1 = m - 1;
                    i = m;
                    for (i1 = l; i1 <= m1; ++i1) {
                        j = i;
                        --i;
                        gl = c * work.g(n + i);
                        h = c * pt;
                        if (Math.abs(pt) >= Math.abs(work.g(n + i))) {
                            c = work.g(n + i) / pt;
                            r = Math.sqrt(c * c + 1.);
                            work.s(n + j, s * pt * r);
                            s = c / r;
                            c = 1. / r;
                        } else {
                            c = pt / work.g(n + i);
                            r = Math.sqrt(c * c + 1.);
                            work.s(n + j, s * work.g(n + i) * r);
                            s = 1. / r;
                            c /= r;
                        }
                        pt = c * work.g(i) - s * gl;
                        work.s(j, h + s * (c * gl + s * work.g(i)));
                        for (k = 1; k <= n; ++k) {
                            h = a.g(k, j);
                            a.s(k, j, s * a.g(k, i) + c * h);
                            a.s(k, i, c * a.g(k, i) - s * h);
                        }
                    }
                    work.s(n + l, s * pt);
                    work.s(l, c * pt);
                } while (Math.abs(work.g(n + l)) > b);
            }
            work.s(l, work.g(l) + f);
        }
        for (i = 1; i <= n1; ++i) {
            k = i;
            pt = work.g(i);
            i1 = i + 1;
            for (j = i1; j <= n; ++j) {
                if (work.g(j) >= pt) continue;
                k = j;
                pt = work.g(j);
            }
            if (k == i) continue;
            work.s(k, work.g(i));
            work.s(i, pt);
            for (j = 1; j <= n; ++j) {
                pt = a.g(j, i);
                a.s(j, i, a.g(j, k));
                a.s(j, k, pt);
            }
        }
        ifault = 0;
        return ifault;
    }

    /** A column-major matrix read 1-based: a[i + j*dim] of the f2c code. */
    private record M(double[] a, int dim) {
        double g(int i, int j) {
            return a[(i - 1) + (j - 1) * dim];
        }

        void s(int i, int j, double v) {
            a[(i - 1) + (j - 1) * dim] = v;
        }
    }

    /** A vector read 1-based. */
    private record W(double[] w) {
        double g(int i) {
            return w[i - 1];
        }

        void s(int i, double v) {
            w[i - 1] = v;
        }
    }

    /* ---- printing ------------------------------------------------------------- */

    static final int PRECISION = 10;
    static final int WIDTH = PRECISION + 7;

    /** operator&lt;&lt;(ostream, LAVector). */
    public static void print(COStream os, LAVector vec) {
        final int pr = os.precision();
        os.precision(PRECISION);
        final int nrow = vec.size();
        final int np = Math.min(nrow, maxNP());
        os.put("\t[");
        for (int i = 0; i < np; i++) {
            os.width(WIDTH);
            os.put(vec.get(i));
        }
        if (np < nrow) {
            os.put(".... ");
            os.width(WIDTH);
            os.put(vec.get(nrow - 1));
        }
        os.put("]\t");
        os.precision(pr);
    }

    /** operator&lt;&lt;(ostream, LASymMatrix). */
    public static void print(COStream os, LASymMatrix matrix) {
        final int pr = os.precision();
        os.precision(8);
        final int nrow = matrix.nrow();
        final int n = Math.min(nrow, maxNP());
        for (int i = 0; i < nrow; i++) {
            os.put("\n");
            if (i == 0) {
                os.put("[[");
            } else {
                if (i >= n) {
                    os.put("....\n");
                    i = nrow - 1;
                }
                os.put(" [");
            }
            for (int j = 0; j < nrow; j++) {
                if (j >= n) {
                    os.put(".... ");
                    j = nrow - 1;
                }
                os.width(15);
                os.put(matrix.get(i, j));
            }
            os.put("]");
        }
        os.put("]]");
        os.precision(pr);
    }
}

package com.sphere.core.ml;

/**
 * The scalar functions of NeuralGraph's elementwise operations, one static method each, for the kernels
 * {@link GraphCompiler} generates to call: each body is the expression of the interpreter's own lambda, copied as it
 * is written there -- same operations, same order, hence the same bits, which the compiler's check holds them to
 * (NaN, signed zeros and infinities included). Constants an operation takes (a slope, a bound, an alpha) come last.
 */
final class GraphKernels {

    private GraphKernels() {
    }

    private static final double GELU_C = Math.sqrt(2.0 / Math.PI), GELU_R2 = Math.sqrt(2.0);

    // ---- one argument

    static double id(double v) {
        return v;
    }

    static double relu(double v) {
        return Double.isNaN(v) ? v : Math.max(v, 0.0);
    }

    static double gelu(double v) {
        return 0.5 * v * (1.0 + NeuralGraph.Erf.erf(v / GELU_R2));
    }

    static double geluTanh(double v) {
        return 0.5 * v * (1.0 + Math.tanh(GELU_C * (v + 0.044715 * v * v * v)));
    }

    static double sigmoid(double v) {
        return 1.0 / (1.0 + Math.exp(-v));
    }

    static double tanh(double v) {
        return Math.tanh(v);
    }

    static double exp(double v) {
        return Math.exp(v);
    }

    static double log(double v) {
        return Math.log(v);
    }

    static double sqrt(double v) {
        return Math.sqrt(v);
    }

    static double rsqrt(double v) {
        return 1.0 / Math.sqrt(v);
    }

    static double neg(double v) {
        return -v;
    }

    static double abs(double v) {
        return Math.abs(v);
    }

    static double sin(double v) {
        return Math.sin(v);
    }

    static double cos(double v) {
        return Math.cos(v);
    }

    static double sinh(double v) {
        return Math.sinh(v);
    }

    static double cosh(double v) {
        return Math.cosh(v);
    }

    static double atan(double v) {
        return Math.atan(v);
    }

    static double asin(double v) {
        return Math.asin(v);
    }

    static double acos(double v) {
        return Math.acos(v);
    }

    static double log1p(double v) {
        return Math.log1p(v);
    }

    static double expm1(double v) {
        return Math.expm1(v);
    }

    static double log2(double v) {
        return Math.log(v) / Math.log(2.0);
    }

    static double log10(double v) {
        return Math.log10(v);
    }

    static double erf(double v) {
        return NeuralGraph.Erf.erf(v);
    }

    static double reciprocal(double v) {
        return 1.0 / v;
    }

    static double sign(double v) {
        return NeuralGraph.sgn(v);   // 0 for NaN and for -0, as PyTorch
    }

    static double floor(double v) {
        return Math.floor(v);
    }

    static double ceil(double v) {
        return Math.ceil(v);
    }

    static double round(double v) {
        return Math.rint(v);
    }

    static double trunc(double v) {
        return v < 0 ? Math.ceil(v) : Math.floor(v);
    }

    static double silu(double v) {
        return v / (1.0 + Math.exp(-v));
    }

    static double leakyRelu(double v, double s) {
        return v > 0 ? v : s * v;
    }

    static double hardtanh(double v, double lo, double hi) {
        return Math.min(Math.max(v, lo), hi);
    }

    static double elu(double v, double al, double sc, double is) {
        return sc * (v > 0 ? v : al * Math.expm1(is * v));
    }

    static double softplus(double v, double be, double th) {
        return be * v > th ? v : Math.log1p(Math.exp(be * v)) / be;
    }

    static double logicalNot(double v) {
        return v == 0 ? 1 : 0;
    }

    static double isnan(double v) {
        return Double.isNaN(v) ? 1 : 0;
    }

    static double isinf(double v) {
        return Double.isInfinite(v) ? 1 : 0;
    }

    // ---- casts, as NeuralGraph.cast rounds

    static double toBool(double v) {
        return v != 0 ? 1 : 0;
    }

    static double toInt(double v) {
        return v < 0 ? Math.ceil(v) : Math.floor(v);
    }

    static double toFloat32(double v) {
        return (double) (float) v;
    }

    static double toFloat16(double v) {
        return Float.float16ToFloat(Float.floatToFloat16((float) v));
    }

    static double toBFloat16(double v) {
        return NeuralGraph.bf16(v);
    }

    // ---- two arguments

    static double add(double u, double w) {
        return Double.sum(u, w);
    }

    static double addAlpha(double u, double w, double al) {
        return u + al * w;
    }

    static double sub(double u, double w, double al) {
        return u - al * w;
    }

    static double rsub(double u, double w, double al) {
        return w - al * u;
    }

    static double mul(double u, double w) {
        return u * w;
    }

    static double div(double u, double w) {
        return u / w;
    }

    static double divFloor(double u, double w) {
        return NeuralGraph.floorDiv(u, w);   // from the remainder: 1.0 // 0.1 is 9, not floor(10.0)
    }

    static double divTrunc(double u, double w) {
        final double q = u / w;
        return q < 0 ? Math.ceil(q) : Math.floor(q);
    }

    static double remainder(double u, double w) {
        return NeuralGraph.mod(u, w);
    }

    static double fmod(double u, double w) {
        return u % w;
    }

    static double maximum(double u, double w) {
        return Double.isNaN(u) || Double.isNaN(w) ? Double.NaN : Math.max(u, w);
    }

    static double minimum(double u, double w) {
        return Double.isNaN(u) || Double.isNaN(w) ? Double.NaN : Math.min(u, w);
    }

    static double atan2(double u, double w) {
        return Math.atan2(u, w);
    }

    static double eq(double u, double w) {
        return u == w ? 1 : 0;
    }

    static double ne(double u, double w) {
        return u != w ? 1 : 0;
    }

    static double lt(double u, double w) {
        return u < w ? 1 : 0;
    }

    static double le(double u, double w) {
        return u <= w ? 1 : 0;
    }

    static double gt(double u, double w) {
        return u > w ? 1 : 0;
    }

    static double ge(double u, double w) {
        return u >= w ? 1 : 0;
    }

    static double logicalAnd(double u, double w) {
        return u != 0 && w != 0 ? 1 : 0;
    }

    static double logicalOr(double u, double w) {
        return u != 0 || w != 0 ? 1 : 0;
    }

    static double logicalXor(double u, double w) {
        return (u != 0) != (w != 0) ? 1 : 0;
    }

    static double clampMin(double u, double w) {
        return Double.isNaN(u) ? u : Math.max(u, w);
    }

    static double clampMax(double u, double w) {
        return Double.isNaN(u) ? u : Math.min(u, w);
    }

    static double pow(double u, double w) {
        return NeuralGraph.cpow(u, w);   // C's: 1 ** NaN and (-1) ** inf are 1
    }

    /** addmm's beta c + alpha prod, as NeuralGraph.scaleAdd combines them. */
    static double scaleAdd(double c, double p, double be, double al) {
        return be * c + al * p;
    }

    /** NeuralGraph.reduce's running maximum and minimum: a NaN wins. */
    static double redMax(double acc, double v) {
        return Double.isNaN(v) || Double.isNaN(acc) ? Double.NaN : Math.max(acc, v);
    }

    static double redMin(double acc, double v) {
        return Double.isNaN(v) || Double.isNaN(acc) ? Double.NaN : Math.min(acc, v);
    }

    /**
     * Batch normalization in evaluation, as NeuralGraph.batchNorm and PyTorch compute it: a linear and a constant term,
     * alpha = w / sqrt(var + eps) and beta = b - mean alpha, then x alpha + beta (w = 1 and b = 0 when absent).
     */
    static double bnAffine(double x, double mean, double var, double w, double b, double eps) {
        final double alpha = w / Math.sqrt(var + eps), beta = b - mean * alpha;
        return x * alpha + beta;
    }

    // ---- three arguments

    static double where(double c, double x, double y) {
        return c != 0 ? x : y;
    }
}

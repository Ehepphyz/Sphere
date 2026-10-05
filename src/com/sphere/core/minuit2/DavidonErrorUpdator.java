package com.sphere.core.minuit2;

/**
 * Migrad's update of the inverse Hessian: Davidon's rank-two formula, or the
 * dual (BFGS) one when the step's curvature exceeds the predicted one.
 */
public final class DavidonErrorUpdator implements MinimumErrorUpdator {

    @Override
    public MinimumError update(MinimumState s0, MinimumParameters p1, FunctionGradient g1) {
        final MnPrint print = new MnPrint("DavidonErrorUpdator");
        final LASymMatrix v0 = s0.error().invHessian();
        final LAVector dx = MnMatrix.subtract(p1.vec(), s0.vec());
        final LAVector dg = MnMatrix.subtract(g1.vec(), s0.gradient().vec());
        final double delgam = MnMatrix.innerProduct(dx, dg);
        final double gvg = MnMatrix.similarity(dg, v0);
        print.debug("\ndx", dx, "\ndg", dg, "\ndelgam", delgam, "gvg", gvg);
        if (delgam == 0) {
            print.warn("delgam = 0 : cannot update - return same matrix (details in info log)");
            print.info("Explanation:\n"
                + "   The distance from the minimum cannot be estimated, since at two\n"
                + "   different points s0 and p1, the function gradient projected onto\n"
                + "   the difference of s0 and p1 is zero, where:\n"
                + " * s0: ", s0.vec(), "\n"
                + " * p1: ", p1.vec(), "\n"
                + " * gradient at s0: ", s0.gradient().vec(), "\n"
                + " * gradient at p1: ", g1.vec(), "\n"
                + "   To understand whether this hints to an issue in the minimized function,\n"
                + "   the minimized function can be plotted along points between s0 and p1 to\n"
                + "   look for unexpected behavior.");
            return s0.error();
        }
        if (delgam < 0) {
            print.warn("delgam < 0 : first derivatives increasing along search line (details in info log)");
            print.info("Explanation:\n"
                + "   The distance from the minimum cannot be estimated, since the minimized\n"
                + "   function seems not to be strictly convex in the space probed by the fit.\n"
                + "   That is expected if the starting parameters are e.g. close to a local maximum\n"
                + "   of the minimized function. If this function is expected to be fully convex\n"
                + "   in the probed range or Minuit is already close to the function minimum, this\n"
                + "   may hint to numerical or analytical issues with the minimized function.\n"
                + "   This was found by projecting the difference of gradients at two points, s0 and p1,\n"
                + "   onto the direction given by the difference of s0 and p1, where:\n"
                + " * s0: ", s0.vec(), "\n"
                + " * p1: ", p1.vec(), "\n"
                + " * gradient at s0: ", s0.gradient().vec(), "\n"
                + " * gradient at p1: ", g1.vec(), "\n"
                + "   To understand whether this hints to an issue in the minimized function,\n"
                + "   the minimized function can be plotted along points between s0 and p1 to\n"
                + "   look for unexpected behavior.");
        }
        if (gvg <= 0) {
            print.warn("gvg <= 0 : cannot update - return same matrix");
            return s0.error();
        }
        final LAVector vg = MnMatrix.times(v0, dg);
        // Outer_product(dx)/delgam - Outer_product(vg)/gvg: the second term first, then the first added
        final LASymMatrix vUpd = MnMatrix.outer(-1. * (1. / gvg) * 1. * 1., vg);
        vUpd.plusOuter((1. / delgam) * 1. * 1., dx);
        if (delgam > gvg) {
            // gvg * Outer_product(dx/delgam - vg/gvg)
            final LAVector t = MnMatrix.sum(1. / delgam, dx, -1. * (1. / gvg), vg);
            vUpd.plusOuter(1. * gvg * 1. * 1., t);
            print.debug("delgam<gvg : use dual (BFGS)  formula");
        } else {
            print.debug("delgam<gvg : use rank 2 Davidon formula");
        }
        final double sumUpd = MnMatrix.sumOfElements(vUpd);
        vUpd.plusAssign(v0);
        final double dcov = 0.5 * (s0.error().dcovar() + sumUpd / MnMatrix.sumOfElements(vUpd));
        return new MinimumError(vUpd, dcov);
    }
}

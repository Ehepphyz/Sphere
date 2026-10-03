package com.sphere.core.fjcontrib.constituentsubtractor;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.FastJetException;
import com.sphere.core.fastjet.FunctionOfPseudoJet;
import com.sphere.core.fastjet.PseudoJet;

import java.util.ArrayList;
import java.util.List;

/**
 * The position dependence of the background, RescalingClasses.hh of
 * ConstituentSubtractor: rho(y, phi) = rho times these factors, for the
 * flow harmonics of heavy-ion events (v2, v3, v4 about the event plane psi)
 * and a rapidity profile given as Gaussians, as vectors, or as a histogram
 * (the ROOT-based templates of the C++, here on {@link Hist1D} and
 * {@link Hist2D}, which follow TH1 and TH2 bin for bin and can be filled from
 * Sphere's ROOT engine).
 */
public final class RescalingClasses {

    private RescalingClasses() {
    }

    static double phiTerm(double phi, double v2, double v3, double v4, double psi) {
        return 1 + 2 * v2 * v2 * CRMath.cos(2 * (phi - psi)) + 2 * v3 * v3 * CRMath.cos(3 * (phi - psi))
            + 2 * v4 * v4 * CRMath.cos(4 * (phi - psi));
    }

    /** Flow harmonics times two Gaussians in rapidity, BackgroundRescalingYPhi. */
    public static final class BackgroundRescalingYPhi implements FunctionOfPseudoJet<Double> {
        private double v2, v3, v4, psi, a1 = 1, sigma1 = 1000, a2, sigma2 = 1000;
        private boolean useRap, usePhi;

        public BackgroundRescalingYPhi() {
        }

        public BackgroundRescalingYPhi(double v2, double v3, double v4, double psi, double a1, double sigma1, double a2,
                                       double sigma2) {
            this.v2 = v2;
            this.v3 = v3;
            this.v4 = v4;
            this.psi = psi;
            this.a1 = a1;
            this.sigma1 = sigma1;
            this.a2 = a2;
            this.sigma2 = sigma2;
            this.useRap = true;
            this.usePhi = true;
        }

        public void useRapTerm(boolean v) { useRap = v; }
        public void usePhiTerm(boolean v) { usePhi = v; }

        @Override
        public Double result(PseudoJet p) {
            final double phiTerm = usePhi ? phiTerm(p.phi(), v2, v3, v4, psi) : 1;
            double rapTerm = 1;
            if (useRap) {
                final double y = p.rap();
                rapTerm = a1 * CRMath.exp(-y * y / (2 * sigma1 * sigma1)) + a2 * CRMath.exp(-y * y / (2 * sigma2 * sigma2));
            }
            return phiTerm * rapTerm;
        }
    }

    /** Flow harmonics times a binned rapidity profile, BackgroundRescalingYPhiUsingVectorForY. */
    public static final class BackgroundRescalingYPhiUsingVectorForY implements FunctionOfPseudoJet<Double> {
        private double v2, v3, v4, psi;
        private final double[] values;
        private final double[] rapBinning;
        private boolean useRap, usePhi;
        private final boolean interpolate;

        public BackgroundRescalingYPhiUsingVectorForY(double v2, double v3, double v4, double psi, List<Double> values,
                                                      List<Double> rapBinning, boolean interpolate) {
            this.v2 = v2;
            this.v3 = v3;
            this.v4 = v4;
            this.psi = psi;
            this.values = values.stream().mapToDouble(Double::doubleValue).toArray();
            this.rapBinning = rapBinning.stream().mapToDouble(Double::doubleValue).toArray();
            this.usePhi = true;
            this.interpolate = interpolate;
            if (this.rapBinning.length >= 2) {
                useRap = true;
                if (this.values.length != this.rapBinning.length - 1) {
                    throw new FastJetException("BackgroundRescalingYPhiUsingVectorForY (from ConstituentSubtractor) The input vectors have wrong dimension. The vector with binning shuld have the size by one higher than the vector with values.");
                }
            }
        }

        public void useRapTerm(boolean v) {
            useRap = v;
            if (v && rapBinning.length < 2) throw new FastJetException("BackgroundRescalingYPhiUsingVectorForY (from ConstituentSubtractor)  Requested rapidity rescaling, but the vector with binning has less than two elements!");
        }

        public void usePhiTerm(boolean v) { usePhi = v; }

        @Override
        public Double result(PseudoJet p) {
            final double phiTerm = usePhi ? phiTerm(p.phi(), v2, v3, v4, psi) : 1;
            final int nBins = rapBinning.length - 1;
            double rapTerm = 1;
            if (useRap) {
                int rapIndex = 0;
                final double rap = p.rap();
                if (rap < rapBinning[0]) {
                    rapIndex = 0;
                } else if (rap >= rapBinning[nBins]) {
                    rapIndex = nBins - 1;
                } else {
                    for (int i = 1; i < nBins + 1; ++i) {
                        if (rap < rapBinning[i]) {
                            rapIndex = i - 1;
                            break;
                        }
                    }
                }
                if (interpolate) {
                    if (rap < (rapBinning[0] + rapBinning[1]) / 2.) {
                        rapTerm = values[0];
                    } else if (rap >= (rapBinning[nBins - 1] + rapBinning[nBins]) / 2.) {
                        rapTerm = values[nBins - 1];
                    } else {
                        final double center = (rapBinning[rapIndex] + rapBinning[rapIndex + 1]) / 2.;
                        final double valueLow, valueHigh, low, high;
                        if (rap < center) {
                            valueLow = values[rapIndex - 1];
                            valueHigh = values[rapIndex];
                            low = (rapBinning[rapIndex - 1] + rapBinning[rapIndex]) / 2.;
                            high = center;
                        } else {
                            valueLow = values[rapIndex];
                            valueHigh = values[rapIndex + 1];
                            low = center;
                            high = (rapBinning[rapIndex + 1] + rapBinning[rapIndex + 2]) / 2.;
                        }
                        rapTerm = valueLow + (rap - low) * ((valueHigh - valueLow) / (high - low));
                    }
                } else {
                    rapTerm = values[rapIndex];
                }
            }
            return phiTerm * rapTerm;
        }
    }

    /** A binned (y, phi) map, BackgroundRescalingYPhiUsingVectors. */
    public static final class BackgroundRescalingYPhiUsingVectors implements FunctionOfPseudoJet<Double> {
        private final List<List<Double>> values;
        private final double[] rapBinning;
        private final double[] phiBinning;
        private boolean useRap, usePhi;

        public BackgroundRescalingYPhiUsingVectors(List<List<Double>> values, List<Double> rapBinning, List<Double> phiBinning) {
            this.values = new ArrayList<>(values);
            this.rapBinning = rapBinning.stream().mapToDouble(Double::doubleValue).toArray();
            this.phiBinning = phiBinning.stream().mapToDouble(Double::doubleValue).toArray();
            useRap = this.rapBinning.length >= 2;
            usePhi = this.phiBinning.length >= 2;
        }

        public void useRapTerm(boolean v) {
            useRap = v;
            if (v && rapBinning.length < 2) throw new FastJetException("BackgroundRescalingYPhiUsingVectors (from ConstituentSubtractor)  Requested rapidity rescaling, but the vector with binning has less than two elements!");
        }

        public void usePhiTerm(boolean v) {
            usePhi = v;
            if (v && phiBinning.length < 2) throw new FastJetException("BackgroundRescalingYPhiUsingVectors (from ConstituentSubtractor)  Requested azimuth rescaling, but the vector with binning has less than two elements!");
        }

        @Override
        public Double result(PseudoJet p) {
            int phiIndex = 0;
            if (usePhi) {
                final double phi = p.phi();
                if (phi < phiBinning[0] || phi >= phiBinning[phiBinning.length - 1]) {
                    throw new FastJetException("BackgroundRescalingYPhiUsingVectors (from ConstituentSubtractor) The phi binning does not correspond to the phi binning of the particles.");
                }
                for (int i = 1; i < phiBinning.length; ++i) {
                    if (phi < phiBinning[i]) {
                        phiIndex = i - 1;
                        break;
                    }
                }
            }
            int rapIndex = 0;
            if (useRap) {
                final double rap = p.rap();
                if (rap < rapBinning[0]) {
                    rapIndex = 0;
                } else if (rap >= rapBinning[rapBinning.length - 1]) {
                    rapIndex = rapBinning.length - 2;
                } else {
                    for (int i = 1; i < rapBinning.length; ++i) {
                        if (rap < rapBinning[i]) {
                            rapIndex = i - 1;
                            break;
                        }
                    }
                }
            }
            if (values.size() <= rapIndex || values.get(rapIndex).size() <= phiIndex) {
                throw new FastJetException("BackgroundRescalingYPhiUsingVectors (from ConstituentSubtractor) The input vector<vector<double> > with values has wrong size.");
            }
            return values.get(rapIndex).get(phiIndex);
        }
    }

    /* ------------------------------------------------------------------ */
    /* The ROOT-histogram rescalings                                       */
    /* ------------------------------------------------------------------ */

    /** A rapidity profile from a 1D histogram, BackgroundRescalingYFromRoot. */
    public static final class BackgroundRescalingYFromRoot implements FunctionOfPseudoJet<Double> {
        private final Hist1D hist;
        private final boolean interpolate;

        public BackgroundRescalingYFromRoot(Hist1D hist, boolean interpolate) {
            this.hist = hist;
            this.interpolate = interpolate;
        }

        @Override
        public Double result(PseudoJet p) {
            if (hist == null) throw new FastJetException("BackgroundRescalingYFromRoot (from ConstituentSubtractor)  The histogram for rescaling not defined! ");
            final double rap = p.rap();
            if (interpolate) return hist.interpolate(rap);
            if (rap < hist.binLowEdge(1)) return hist.binContent(1);
            if (rap >= hist.binUpEdge(hist.nbins())) return hist.binContent(hist.nbins());
            return hist.binContent(hist.findBin(rap));
        }
    }

    /** A (y, phi) map from a 2D histogram, BackgroundRescalingYPhiFromRoot. */
    public static final class BackgroundRescalingYPhiFromRoot implements FunctionOfPseudoJet<Double> {
        private final Hist2D hist;
        private final boolean interpolate;

        public BackgroundRescalingYPhiFromRoot(Hist2D hist, boolean interpolate) {
            this.hist = hist;
            this.interpolate = interpolate;
        }

        @Override
        public Double result(PseudoJet p) {
            if (hist == null) throw new FastJetException("BackgroundRescalingYPhiFromRoot (from ConstituentSubtractor)  The histogram for rescaling not defined! ");
            final double rap = p.rap();
            final double phi = p.phi();
            if (interpolate) return hist.interpolate(rap, phi);
            int xbin;
            if (rap < hist.x().binLowEdge(1)) xbin = 1;
            else if (rap >= hist.x().binUpEdge(hist.x().nbins())) xbin = hist.x().nbins();
            else xbin = hist.x().findBin(rap);
            if (phi < hist.y().binLowEdge(1) || phi > hist.y().binUpEdge(hist.y().nbins())) {
                throw new FastJetException("BackgroundRescalingYPhiFromRoot (from ConstituentSubtractor)  The phi range of the histogram does not correspond to the phi range of the particles! Change the phi range of the histogram.");
            }
            return hist.binContent(xbin, hist.y().findBin(phi));
        }
    }

    /** Flow harmonics times a histogram's rapidity profile, BackgroundRescalingYFromRootPhi. */
    public static final class BackgroundRescalingYFromRootPhi implements FunctionOfPseudoJet<Double> {
        private final double v2, v3, v4, psi;
        private boolean useRap, usePhi;
        private final Hist1D hist;
        private final boolean interpolate;

        public BackgroundRescalingYFromRootPhi(double v2, double v3, double v4, double psi, Hist1D hist, boolean interpolate) {
            this.v2 = v2;
            this.v3 = v3;
            this.v4 = v4;
            this.psi = psi;
            this.hist = hist;
            this.usePhi = true;
            this.interpolate = interpolate;
            if (hist == null) {
                System.out.println("\n\nConstituentSubtractor::BackgroundRescalingYFromRootPhi WARNING: The histogram for rapidity rescaling is not defined!!! Not performing rapidity rescaling.\n\n");
                useRap = false;
            } else {
                useRap = true;
            }
        }

        public void useRapTerm(boolean v) {
            useRap = v;
            if (hist == null && v) throw new FastJetException("BackgroundRescalingYFromRootPhi (from ConstituentSubtractor)  Requested rapidity rescaling, but the histogram for rescaling is not defined!");
        }

        public void usePhiTerm(boolean v) { usePhi = v; }

        @Override
        public Double result(PseudoJet p) {
            final double phiTerm = usePhi ? phiTerm(p.phi(), v2, v3, v4, psi) : 1;
            double rapTerm = 1;
            if (useRap) {
                final double y = p.rap();
                rapTerm = interpolate ? hist.interpolate(y) : hist.binContent(hist.findBin(y));
            }
            return phiTerm * rapTerm;
        }
    }

    /**
     * A histogram of fixed bins with ROOT's TH1D conventions: bins 1..n,
     * 0 and n+1 the under- and overflow, FindBin, GetBinCenter and
     * Interpolate as TH1 computes them.
     */
    public static final class Hist1D {
        private final int nbins;
        private final double xmin;
        private final double xmax;
        private final double[] content;

        public Hist1D(int nbins, double xmin, double xmax) {
            this.nbins = nbins;
            this.xmin = xmin;
            this.xmax = xmax;
            this.content = new double[nbins + 2];
        }

        public int nbins() { return nbins; }

        public int findBin(double x) {
            if (x < xmin) return 0;
            if (!(x < xmax)) return nbins + 1;
            return 1 + (int) (nbins * (x - xmin) / (xmax - xmin));
        }

        public double binWidth() { return (xmax - xmin) / nbins; }
        public double binLowEdge(int bin) { return xmin + (bin - 1) * binWidth(); }
        public double binUpEdge(int bin) { return xmin + bin * binWidth(); }

        public double binCenter(int bin) {
            final double w = (xmax - xmin) / (double) nbins;
            return xmin + (bin - 1) * w + 0.5 * w;
        }

        public double binContent(int bin) { return content[Math.max(0, Math.min(nbins + 1, bin))]; }
        public void setBinContent(int bin, double v) { content[bin] = v; }

        /** TH1::Interpolate: linear between the neighbouring bin centres. */
        public double interpolate(double x) {
            final int xbin = findBin(x);
            if (x <= binCenter(1)) return binContent(1);
            if (x >= binCenter(nbins)) return binContent(nbins);
            final double x0, x1, y0, y1;
            if (x <= binCenter(xbin)) {
                y0 = binContent(xbin - 1);
                x0 = binCenter(xbin - 1);
                y1 = binContent(xbin);
                x1 = binCenter(xbin);
            } else {
                y0 = binContent(xbin);
                x0 = binCenter(xbin);
                y1 = binContent(xbin + 1);
                x1 = binCenter(xbin + 1);
            }
            return y0 + (x - x0) * ((y1 - y0) / (x1 - x0));
        }
    }

    /** A 2D histogram of fixed bins with TH2D's conventions (bilinear Interpolate included). */
    public static final class Hist2D {
        private final Hist1D x;
        private final Hist1D y;
        private final double[][] content;

        public Hist2D(int nx, double xmin, double xmax, int ny, double ymin, double ymax) {
            x = new Hist1D(nx, xmin, xmax);
            y = new Hist1D(ny, ymin, ymax);
            content = new double[nx + 2][ny + 2];
        }

        public Hist1D x() { return x; }
        public Hist1D y() { return y; }
        public double binContent(int bx, int by) { return content[bx][by]; }
        public void setBinContent(int bx, int by, double v) { content[bx][by] = v; }

        /** TH2::Interpolate, by quadrant of the bin, bilinear between bin centres. */
        public double interpolate(double xv, double yv) {
            final int bx = x.findBin(xv);
            final int by = y.findBin(yv);
            if (bx < 1 || bx > x.nbins() || by < 1 || by > y.nbins()) return 0;
            final double dx = x.binUpEdge(bx) - xv;
            final double dy = y.binUpEdge(by) - yv;
            final double hx = x.binWidth() / 2;
            final double hy = y.binWidth() / 2;
            final double x1, x2, y1, y2;
            if (dx <= hx && dy <= hy) {
                x1 = x.binCenter(bx); y1 = y.binCenter(by); x2 = x.binCenter(bx + 1); y2 = y.binCenter(by + 1);
            } else if (dx > hx && dy <= hy) {
                x1 = x.binCenter(bx - 1); y1 = y.binCenter(by); x2 = x.binCenter(bx); y2 = y.binCenter(by + 1);
            } else if (dx > hx) {
                x1 = x.binCenter(bx - 1); y1 = y.binCenter(by - 1); x2 = x.binCenter(bx); y2 = y.binCenter(by);
            } else {
                x1 = x.binCenter(bx); y1 = y.binCenter(by - 1); x2 = x.binCenter(bx + 1); y2 = y.binCenter(by);
            }
            final int bx1 = Math.max(1, x.findBin(x1));
            final int bx2 = Math.min(x.nbins(), x.findBin(x2));
            final int by1 = Math.max(1, y.findBin(y1));
            final int by2 = Math.min(y.nbins(), y.findBin(y2));
            final double q11 = content[bx1][by1];
            final double q12 = content[bx1][by2];
            final double q21 = content[bx2][by1];
            final double q22 = content[bx2][by2];
            final double d = 1.0 * (x2 - x1) * (y2 - y1);
            return 1.0 * q11 / d * (x2 - xv) * (y2 - yv) + 1.0 * q21 / d * (xv - x1) * (y2 - yv)
                + 1.0 * q12 / d * (x2 - xv) * (yv - y1) + 1.0 * q22 / d * (xv - x1) * (yv - y1);
        }
    }
}

package com.sphere.core.fjcontrib.lundplane;

import com.sphere.core.fastjet.CRMath;
import com.sphere.core.fastjet.Fmt;

import java.util.List;

/**
 * Which primary declustering roots the secondary plane, SecondaryLund; the
 * three choices of the contrib are the nested classes.
 */
public abstract class SecondaryLund {

    /** The index of the primary declustering to follow, or -1. */
    public abstract int result(List<LundDeclustering> declusts);

    public String description() {
        return "SecondaryLund";
    }

    /** The first emission with z &gt; zcut, SecondaryLund_mMDT. */
    public static class SecondaryLund_mMDT extends SecondaryLund {
        private final double zcut;

        public SecondaryLund_mMDT() {
            this(0.025);
        }

        public SecondaryLund_mMDT(double zcut) {
            this.zcut = zcut;
        }

        @Override
        public int result(List<LundDeclustering> declusts) {
            for (int i = 0; i < declusts.size(); ++i) {
                if (declusts.get(i).z() > zcut) return i;
            }
            return -1;
        }

        @Override
        public String description() {
            return "SecondaryLund (mMDT selection of leading emission, zcut=" + Fmt.g(zcut) + ")";
        }
    }

    /** Of the emissions with z &gt; zcut, the one of largest pt1 pt2 Delta^2, SecondaryLund_dotmMDT. */
    public static class SecondaryLund_dotmMDT extends SecondaryLund {
        private final double zcut;

        public SecondaryLund_dotmMDT() {
            this(0.025);
        }

        public SecondaryLund_dotmMDT(double zcut) {
            this.zcut = zcut;
        }

        @Override
        public int result(List<LundDeclustering> declusts) {
            int secondaryIndex = -1;
            double dotProdMax = 0.0;
            for (int i = 0; i < declusts.size(); ++i) {
                final LundDeclustering d = declusts.get(i);
                if (d.z() > zcut) {
                    final double dotProd = d.harder().pt() * d.softer().pt() * d.Delta() * d.Delta();
                    if (dotProd > dotProdMax) {
                        dotProdMax = dotProd;
                        secondaryIndex = i;
                    }
                }
            }
            return secondaryIndex;
        }

        @Override
        public String description() {
            return "SecondaryLund (dotmMDT selection of leading emission, zcut=" + Fmt.g(zcut) + ")";
        }
    }

    /** The emission whose mass is closest to a reference mass (the W by default), SecondaryLund_Mass. */
    public static class SecondaryLund_Mass extends SecondaryLund {
        private final double mref2;

        public SecondaryLund_Mass() {
            this(80.4);
        }

        public SecondaryLund_Mass(double refMass) {
            this.mref2 = refMass * refMass;
        }

        @Override
        public int result(List<LundDeclustering> declusts) {
            int secondaryIndex = -1;
            double massDiff = Double.MAX_VALUE;
            for (int i = 0; i < declusts.size(); ++i) {
                final LundDeclustering d = declusts.get(i);
                final double dist = Math.abs(CRMath.log(d.harder().pt() * d.softer().pt() * d.Delta() * d.Delta() / mref2)
                    * CRMath.log(1.0 / d.z()));
                if (dist < massDiff) {
                    massDiff = dist;
                    secondaryIndex = i;
                }
            }
            return secondaryIndex;
        }

        @Override
        public String description() {
            return " (Mass selection of leading emission, m=" + Fmt.g(Math.sqrt(mref2)) + ")";
        }
    }
}

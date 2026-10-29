package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;

/**
 * The tables of Marsaglia and Tsang's ziggurats as RandGaussZiggurat::ziggurat_init and
 * RandExpZiggurat::ziggurat_init compute them (the same code in both): 128 strips of the normal density
 * (kn, wn, fn), 256 of the exponential one (ke, we, fe). Made once per thread, as the C++ thread-locals.
 */
final class ZigguratTables {

    final long[] kn = new long[128];
    final long[] ke = new long[256];
    final float[] wn = new float[128];
    final float[] fn = new float[128];
    final float[] we = new float[256];
    final float[] fe = new float[256];

    /** This thread's tables, made at the first call. */
    static ZigguratTables get() {
        final Statics s = Statics.get();
        if (s.ziggurat == null) s.ziggurat = new ZigguratTables();
        return s.ziggurat;
    }

    private ZigguratTables() {
        final double rzm1 = 2147483648.0;
        final double rzm2 = 4294967296.;
        double dn = 3.442619855899;
        double tn = dn;
        final double vn = 9.91256303526217e-3;
        double q;
        double de = 7.697117470131487;
        double te = de;
        final double ve = 3.949659822581572e-3;

        q = vn / CMath.exp(-.5 * dn * dn);
        kn[0] = (long) ((dn / q) * rzm1);
        kn[1] = 0;
        wn[0] = (float) (q / rzm1);
        wn[127] = (float) (dn / rzm1);
        fn[0] = 1.f;
        fn[127] = (float) CMath.exp(-.5 * dn * dn);
        for (int i = 126; i >= 1; i--) {
            dn = Math.sqrt(-2. * CMath.log(vn / dn + CMath.exp(-.5 * dn * dn)));
            kn[i + 1] = (long) ((dn / tn) * rzm1);
            tn = dn;
            fn[i] = (float) CMath.exp(-.5 * dn * dn);
            wn[i] = (float) (dn / rzm1);
        }

        q = ve / CMath.exp(-de);
        ke[0] = (long) ((de / q) * rzm2);
        ke[1] = 0;
        we[0] = (float) (q / rzm2);
        we[255] = (float) (de / rzm2);
        fe[0] = 1.f;
        fe[255] = (float) CMath.exp(-de);
        for (int i = 254; i >= 1; i--) {
            de = -CMath.log(ve / de + CMath.exp(-de));
            ke[i + 1] = (long) ((de / te) * rzm2);
            te = de;
            fe[i] = (float) CMath.exp(-de);
            we[i] = (float) (de / rzm2);
        }
    }
}

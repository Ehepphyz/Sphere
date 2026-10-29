package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandGaussZiggurat (Random/RandGaussZiggurat.h, RandGaussZiggurat.cc): normal numbers in single
 * precision by Marsaglia and Tsang's ziggurat, one 32-bit word of the engine for most of them.
 *
 * <p>The C++ returns floats; here the static shoots and fire() give the float widened to a double (they
 * hide or override RandGauss's), computed in float as the C++ does, the mean and deviation rounded to
 * float first where the C++ takes floats.
 */
public class RandGaussZiggurat extends RandGauss {

    public RandGaussZiggurat(HepRandomEngine anEngine) {
        super(anEngine);
    }

    public RandGaussZiggurat(HepRandomEngine anEngine, double mean) {
        super(anEngine, mean);
    }

    public RandGaussZiggurat(HepRandomEngine anEngine, double mean, double stdDev) {
        super(anEngine, mean, stdDev);
    }

    /* ---- the ziggurat ---- */

    /** Makes this thread's tables now (they are made at the first number otherwise). */
    public static boolean ziggurat_init() {
        ZigguratTables.get();
        return true;
    }

    private static float uni(HepRandomEngine anEngine) {
        return (float) anEngine.flat();
    }

    /** A normal number: hz the engine's 32-bit word as a signed int, kept when inside its strip. */
    static float ziggurat_RNOR(HepRandomEngine anEngine) {
        final ZigguratTables z = ZigguratTables.get();
        final long hz = (int) anEngine.toUnsignedInt();
        final int iz = (int) (hz & 127);
        return (Math.abs(hz) < z.kn[iz]) ? hz * z.wn[iz] : ziggurat_nfix(hz, anEngine);
    }

    /** Outside the strip: the wedge, or the tail beyond r. */
    static float ziggurat_nfix(long hz, HepRandomEngine anEngine) {
        final ZigguratTables z = ZigguratTables.get();
        final float r = 3.442620f;
        float x;
        float y;
        int iz = (int) (hz & 127);
        for (;;) {
            x = hz * z.wn[iz];
            if (iz == 0) {
                do {
                    x = (float) (-CMath.log(1.0 - uni(anEngine)) * 0.2904764);
                    y = (float) -CMath.log(1.0 - uni(anEngine));
                } while (y + y < x * x);
                return (hz > 0) ? r + x : -r - x;
            }
            if (z.fn[iz] + (1.0 - uni(anEngine)) * (z.fn[iz - 1] - z.fn[iz]) < CMath.exp(-.5 * x * x)) return x;
            hz = (int) anEngine.toUnsignedInt();
            iz = (int) (hz & 127);
            if (Math.abs(hz) < z.kn[iz]) return hz * z.wn[iz];
        }
    }

    /* ---- static ---- */

    public static double shoot() {
        return ziggurat_RNOR(HepRandom.getTheEngine());
    }

    /** shoot(float mean, float stdDev): in float. */
    public static double shoot(double mean, double stdDev) {
        return ziggurat_RNOR(HepRandom.getTheEngine()) * (float) stdDev + (float) mean;
    }

    public static double shoot(HepRandomEngine anotherEngine) {
        return ziggurat_RNOR(anotherEngine);
    }

    public static double shoot(HepRandomEngine anotherEngine, double mean, double stdDev) {
        return ziggurat_RNOR(anotherEngine) * (float) stdDev + (float) mean;
    }

    public static void shootArray(int size, float[] vect) {
        shootArray(size, vect, 0.0f, 1.0f);
    }

    public static void shootArray(int size, float[] vect, float mean) {
        shootArray(size, vect, mean, 1.0f);
    }

    public static void shootArray(int size, float[] vect, float mean, float stdDev) {
        for (int i = 0; i < size; ++i) vect[i] = (float) shoot(mean, stdDev);
    }

    public static void shootArray(int size, double[] vect) {
        shootArray(size, vect, 0.0, 1.0);
    }

    public static void shootArray(int size, double[] vect, double mean) {
        shootArray(size, vect, mean, 1.0);
    }

    public static void shootArray(int size, double[] vect, double mean, double stdDev) {
        for (int i = 0; i < size; ++i) vect[i] = shoot(mean, stdDev);
    }

    public static void shootArray(HepRandomEngine anotherEngine, int size, float[] vect) {
        shootArray(anotherEngine, size, vect, 0.0f, 1.0f);
    }

    public static void shootArray(HepRandomEngine anotherEngine, int size, float[] vect, float mean) {
        shootArray(anotherEngine, size, vect, mean, 1.0f);
    }

    public static void shootArray(HepRandomEngine anotherEngine, int size, float[] vect, float mean, float stdDev) {
        for (int i = 0; i < size; ++i) vect[i] = (float) shoot(anotherEngine, mean, stdDev);
    }

    public static void shootArray(HepRandomEngine anotherEngine, int size, double[] vect) {
        shootArray(anotherEngine, size, vect, 0.0, 1.0);
    }

    public static void shootArray(HepRandomEngine anotherEngine, int size, double[] vect, double mean) {
        shootArray(anotherEngine, size, vect, mean, 1.0);
    }

    public static void shootArray(HepRandomEngine anotherEngine, int size, double[] vect, double mean, double stdDev) {
        for (int i = 0; i < size; ++i) vect[i] = shoot(anotherEngine, mean, stdDev);
    }

    /* ---- this distribution's engine ---- */

    /** The float of (number x default deviation + default mean), computed in double. */
    @Override
    public double fire() {
        return (float) (ziggurat_RNOR(localEngine) * defaultStdDev + defaultMean);
    }

    /** fire(float mean, float stdDev): in float. */
    @Override
    public double fire(double mean, double stdDev) {
        return ziggurat_RNOR(localEngine) * (float) stdDev + (float) mean;
    }

    public void fireArray(int size, float[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = (float) fire(defaultMean, defaultStdDev);
    }

    @Override
    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = fire(defaultMean, defaultStdDev);
    }

    public void fireArray(int size, float[] vect, float mean, float stdDev) {
        for (int i = 0; i < size; ++i) vect[i] = (float) fire(mean, stdDev);
    }

    @Override
    public void fireArray(int size, double[] vect, double mean, double stdDev) {
        for (int i = 0; i < size; ++i) vect[i] = fire(mean, stdDev);
    }

    /** operator(): in double. */
    @Override
    public double call() {
        return ziggurat_RNOR(localEngine) * defaultStdDev + defaultMean;
    }

    @Override
    public double call(double mean, double stdDev) {
        return ziggurat_RNOR(localEngine) * stdDev + mean;
    }

    @Override
    public String name() {
        return "RandGaussZiggurat";
    }

    public static String distributionName() {
        return "RandGaussZiggurat";
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        super.put(os);
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        super.get(is);
        return is;
    }
}

package com.sphere.core.clhep.random;

import com.sphere.core.clhep.CMath;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandExpZiggurat (Random/RandExpZiggurat.h, RandExpZiggurat.cc): exponential numbers in single
 * precision by Marsaglia and Tsang's ziggurat, one 32-bit word of the engine for most of them. Floats as
 * the C++ gives them; the double overloads round the mean to float first, as a C++ call would.
 */
public class RandExpZiggurat extends HepRandom {

    private final HepRandomEngine localEngine;
    private double defaultMean;

    public RandExpZiggurat(HepRandomEngine anEngine) {
        this(anEngine, 1.0);
    }

    public RandExpZiggurat(HepRandomEngine anEngine, double mean) {
        localEngine = anEngine;
        defaultMean = mean;
    }

    /* ---- the ziggurat ---- */

    public static boolean ziggurat_init() {
        ZigguratTables.get();
        return true;
    }

    private static float uni(HepRandomEngine anEngine) {
        return (float) anEngine.flat();
    }

    static float ziggurat_REXP(HepRandomEngine anEngine) {
        final ZigguratTables z = ZigguratTables.get();
        final long jz = anEngine.toUnsignedInt();
        final int iz = (int) (jz & 255);
        return (jz < z.ke[iz]) ? jz * z.we[iz] : ziggurat_efix(jz, anEngine);
    }

    /** Outside the strip: the wedge, or the tail beyond 7.69711 (std::log and std::exp of floats: logf, expf). */
    static float ziggurat_efix(long jz, HepRandomEngine anEngine) {
        final ZigguratTables z = ZigguratTables.get();
        int iz = (int) (jz & 255);
        float x;
        for (;;) {
            if (iz == 0) return (float) (7.69711 - CMath.logf(uni(anEngine)));
            x = jz * z.we[iz];
            if (z.fe[iz] + uni(anEngine) * (z.fe[iz - 1] - z.fe[iz]) < CMath.expf(-x)) return x;
            jz = anEngine.toUnsignedInt();
            iz = (int) (jz & 255);
            if (jz < z.ke[iz]) return jz * z.we[iz];
        }
    }

    /* ---- static ---- */

    public static float shoot() {
        return shoot(HepRandom.getTheEngine());
    }

    public static float shoot(float mean) {
        return shoot(HepRandom.getTheEngine(), mean);
    }

    public static float shoot(double mean) {
        return shoot((float) mean);
    }

    public static float shoot(HepRandomEngine anEngine) {
        return ziggurat_REXP(anEngine);
    }

    public static float shoot(HepRandomEngine anEngine, float mean) {
        return shoot(anEngine) * mean;
    }

    public static float shoot(HepRandomEngine anEngine, double mean) {
        return shoot(anEngine, (float) mean);
    }

    public static void shootArray(int size, float[] vect) {
        shootArray(size, vect, 1.0f);
    }

    public static void shootArray(int size, float[] vect, float mean) {
        for (int i = 0; i < size; ++i) vect[i] = shoot(mean);
    }

    public static void shootArray(int size, double[] vect) {
        shootArray(size, vect, 1.0);
    }

    public static void shootArray(int size, double[] vect, double mean) {
        for (int i = 0; i < size; ++i) vect[i] = shoot(mean);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, float[] vect) {
        shootArray(anEngine, size, vect, 1.0f);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, float[] vect, float mean) {
        for (int i = 0; i < size; ++i) vect[i] = shoot(anEngine, mean);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect) {
        shootArray(anEngine, size, vect, 1.0);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double mean) {
        for (int i = 0; i < size; ++i) vect[i] = shoot(anEngine, mean);
    }

    /* ---- this distribution's engine ---- */

    public float fire() {
        return fire(defaultMean);
    }

    public float fire(float mean) {
        return ziggurat_REXP(localEngine) * mean;
    }

    public float fire(double mean) {
        return fire((float) mean);
    }

    public void fireArray(int size, float[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = fire(defaultMean);
    }

    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; ++i) vect[i] = fire(defaultMean);
    }

    public void fireArray(int size, float[] vect, float mean) {
        for (int i = 0; i < size; ++i) vect[i] = fire(mean);
    }

    public void fireArray(int size, double[] vect, double mean) {
        for (int i = 0; i < size; ++i) vect[i] = fire(mean);
    }

    @Override
    public double call() {
        return fire(defaultMean);
    }

    public float call(float mean) {
        return fire(mean);
    }

    @Override
    public String name() {
        return "RandExpZiggurat";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandExpZiggurat";
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        os.put("Uvec").put("\n");
        DistIO.put(os, defaultMean);
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        final String w = is.nextWord();
        if (w.equals("Uvec")) {
            defaultMean = DistIO.get(is);
            return is;
        }
        defaultMean = DistIO.number(w);
        return is;
    }
}

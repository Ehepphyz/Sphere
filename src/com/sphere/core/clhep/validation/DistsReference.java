package com.sphere.core.clhep.validation;

import com.sphere.core.clhep.random.CLib;
import com.sphere.core.clhep.random.DRand48Engine;
import com.sphere.core.clhep.random.DualRand;
import com.sphere.core.clhep.random.HepJamesRandom;
import com.sphere.core.clhep.random.HepRandom;
import com.sphere.core.clhep.random.HepRandomEngine;
import com.sphere.core.clhep.random.HepStat;
import com.sphere.core.clhep.random.Hurd160Engine;
import com.sphere.core.clhep.random.Hurd288Engine;
import com.sphere.core.clhep.random.MTwistEngine;
import com.sphere.core.clhep.random.MixMaxRng;
import com.sphere.core.clhep.random.NonRandomEngine;
import com.sphere.core.clhep.random.ProcessState;
import com.sphere.core.clhep.random.RandBinomial;
import com.sphere.core.clhep.random.RandBit;
import com.sphere.core.clhep.random.RandBreitWigner;
import com.sphere.core.clhep.random.RandChiSquare;
import com.sphere.core.clhep.random.RandEngine;
import com.sphere.core.clhep.random.RandExpZiggurat;
import com.sphere.core.clhep.random.RandExponential;
import com.sphere.core.clhep.random.RandFlat;
import com.sphere.core.clhep.random.RandGamma;
import com.sphere.core.clhep.random.RandGauss;
import com.sphere.core.clhep.random.RandGaussQ;
import com.sphere.core.clhep.random.RandGaussT;
import com.sphere.core.clhep.random.RandGaussZiggurat;
import com.sphere.core.clhep.random.RandGeneral;
import com.sphere.core.clhep.random.RandLandau;
import com.sphere.core.clhep.random.RandPoisson;
import com.sphere.core.clhep.random.RandPoissonQ;
import com.sphere.core.clhep.random.RandPoissonT;
import com.sphere.core.clhep.random.RandSkewNormal;
import com.sphere.core.clhep.random.RandStudentT;
import com.sphere.core.clhep.random.RanecuEngine;
import com.sphere.core.clhep.random.Ranlux64Engine;
import com.sphere.core.clhep.random.RanluxEngine;
import com.sphere.core.clhep.random.RanluxppEngine;
import com.sphere.core.clhep.random.RanshiEngine;
import com.sphere.core.clhep.random.StaticRandomStates;
import com.sphere.core.clhep.random.TripleRand;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.CRand;

/**
 * The Java twin of dists_ref.cc: the distributions and HepStat's functions, their numbers, states written
 * and read back, the static states saved and restored. Started as a fresh C++ program starts
 * ({@link ProcessState#restart}).
 */
public final class DistsReference extends Reference {

    private final CRand.Library randLibrary;

    public DistsReference() {
        this(CLib.defaultRandLibrary());
    }

    /** With the rand() of the C library given (RandEngine draws from it). */
    public DistsReference(CRand.Library randLibrary) {
        this.randLibrary = randLibrary;
    }

    @Override
    public String name() {
        return "dists";
    }

    private static String put(HepRandom x) {
        return x.put(new COStream()).str();
    }

    @Override
    protected void run() {
        ProcessState.restart(randLibrary);
        int k;

        // ---- HepStat
        {
            final double[] rs = {1e-15, 3e-14, 1.9e-13, 2e-13, 7.7e-13, 3.9e-11, 4e-11, 5.5e-10, 9.99e-9, 1e-8, 3.3e-7,
                1.99e-6, 2e-6, 4.4e-5, 4.99e-4, 5e-4, 0.001, 0.0123, 0.1, 0.25, 0.3333, 0.49999, 0.5,
                0.50001, 0.6, 0.75, 0.9, 0.99, 0.999, 0.9999, 0.99999, 0.999999, 1 - 1e-9, 1 - 1e-12};
            for (double r : rs) d("stat.flatToGaussian", HepStat.flatToGaussian(r));
            for (double r : rs) d("stat.inverseErf", HepStat.inverseErf(2 * r - 1));
            final double[] xs = {-3, -2.5, -1, -0.5, -0.1, 0, 0.01, 0.2, 0.7, 1, 1.5, 2, 2.9, 4};
            for (double x : xs) {
                d("stat.erfQ", HepStat.erfQ(x));
                d("stat.erf", HepStat.erf(x));
            }
            final double[] gs = {0.1, 0.5, 1, 1.5, 2, 3.7, 10, 33.3, 100, 171.5, 1000, 1e5};
            for (double g : gs) d("stat.gammln", HepStat.gammln(g));
        }

        final MixMaxRng se = new MixMaxRng(20250101);
        HepRandom.setTheEngine(se);
        final MixMaxRng e = new MixMaxRng(4357);

        // ---- RandFlat
        for (k = 0; k < 50; k++) d("flat.shoot", RandFlat.shoot());
        for (k = 0; k < 20; k++) d("flat.shootW", RandFlat.shoot(2.5));
        for (k = 0; k < 20; k++) d("flat.shootAB", RandFlat.shoot(-1.5, 3.25));
        for (k = 0; k < 20; k++) i("flat.shootInt", RandFlat.shootInt(1000L));
        for (k = 0; k < 20; k++) i("flat.shootInt2", RandFlat.shootInt(-50L, 50L));
        for (k = 0; k < 70; k++) i("flat.shootBit", RandFlat.shootBit());
        {
            final double[] a = new double[13];
            RandFlat.shootArray(13, a);
            for (k = 0; k < 13; k++) d("flat.shootArray", a[k]);
        }
        {
            final double[] a = new double[13];
            RandFlat.shootArray(13, a, 2.0, 5.0);
            for (k = 0; k < 13; k++) d("flat.shootArray2", a[k]);
        }
        for (k = 0; k < 20; k++) d("flat.shootE", RandFlat.shoot(e));
        for (k = 0; k < 20; k++) d("flat.shootEW", RandFlat.shoot(e, 0.5));
        for (k = 0; k < 20; k++) d("flat.shootEAB", RandFlat.shoot(e, 10.0, 11.0));
        for (k = 0; k < 20; k++) i("flat.shootEInt", RandFlat.shootInt(e, 77L));
        for (k = 0; k < 20; k++) i("flat.shootEInt2", RandFlat.shootInt(e, 5L, 9L));
        for (k = 0; k < 40; k++) i("flat.shootEBit", RandFlat.shootBit(e));
        {
            final double[] a = new double[9];
            RandFlat.shootArray(e, 9, a);
            for (k = 0; k < 9; k++) d("flat.shootEArray", a[k]);
        }
        {
            final double[] a = new double[9];
            RandFlat.shootArray(e, 9, a, -1.0, 1.0);
            for (k = 0; k < 9; k++) d("flat.shootEArray2", a[k]);
        }
        final String flatText;
        {
            final RandFlat f = new RandFlat(e, -2.0, 6.0);
            for (k = 0; k < 20; k++) d("flat.fire", f.fire());
            for (k = 0; k < 5; k++) d("flat.fireW", f.fire(3.0));
            for (k = 0; k < 5; k++) d("flat.fireAB", f.fire(1.0, 2.0));
            for (k = 0; k < 5; k++) i("flat.fireInt", f.fireInt(10L));
            for (k = 0; k < 5; k++) i("flat.fireInt2", f.fireInt(100L, 200L));
            for (k = 0; k < 40; k++) i("flat.fireBit", f.fireBit());
            {
                final double[] a = new double[6];
                f.fireArray(6, a);
                for (k = 0; k < 6; k++) d("flat.fireArray", a[k]);
            }
            {
                final double[] a = new double[6];
                f.fireArray(6, a, 0.0, 0.5);
                for (k = 0; k < 6; k++) d("flat.fireArray2", a[k]);
            }
            for (k = 0; k < 5; k++) d("flat.op", f.call());
            for (k = 0; k < 5; k++) d("flat.opW", f.call(7.0));
            for (k = 0; k < 5; k++) d("flat.opAB", f.call(-7.0, -6.0));
            flatText = put(f);
            text("flat.put", flatText);
            final RandFlat g = new RandFlat(e);
            g.get(new CIStream(flatText));
            text("flat.get", put(g));
            for (k = 0; k < 10; k++) d("flat.restored", g.fire());
            for (k = 0; k < 10; k++) i("flat.restoredBit", g.fireBit());
        }
        {
            final String os = RandFlat.saveDistState(new COStream()).str();
            text("flat.dist", os);
            for (k = 0; k < 5; k++) i("flat.distAfter", RandFlat.shootBit());
            RandFlat.restoreDistState(new CIStream(os));
            for (k = 0; k < 5; k++) i("flat.distRestored", RandFlat.shootBit());
        }

        // ---- RandBit
        for (k = 0; k < 40; k++) i("bit.shootBit", RandBit.shootBit());
        for (k = 0; k < 40; k++) i("bit.shootBitE", RandBit.shootBit(e));
        {
            final RandBit b = new RandBit(e, 1.0, 3.0);
            for (k = 0; k < 40; k++) i("bit.fireBit", b.fireBit());
            for (k = 0; k < 5; k++) d("bit.fire", b.fire());
            final RandBit b2 = new RandBit(e);
            b2.get(new CIStream((" RandBit\n" + flatText).replace("RandFlat", "RandBit")));
            text("bit.get", put(b2));
            for (k = 0; k < 5; k++) d("bit.restored", b2.fire());
        }

        // ---- RandGauss
        for (k = 0; k < 41; k++) d("gauss.shoot", RandGauss.shoot());
        for (k = 0; k < 20; k++) d("gauss.shootMS", RandGauss.shoot(10.0, 2.0));
        {
            final double[] a = new double[11];
            RandGauss.shootArray(11, a, 1.0, 0.5);
            for (k = 0; k < 11; k++) d("gauss.shootArray", a[k]);
        }
        for (k = 0; k < 21; k++) d("gauss.shootE", RandGauss.shoot(e));
        for (k = 0; k < 20; k++) d("gauss.shootEMS", RandGauss.shoot(e, -3.0, 0.25));
        {
            final double[] a = new double[7];
            RandGauss.shootArray(e, 7, a);
            for (k = 0; k < 7; k++) d("gauss.shootEArray", a[k]);
        }
        i("gauss.flag", RandGauss.getFlag());
        {
            final RandGauss g = new RandGauss(e, 5.0, 3.0);
            for (k = 0; k < 21; k++) d("gauss.fire", g.fire());
            for (k = 0; k < 10; k++) d("gauss.fireMS", g.fire(0.0, 1.0));
            {
                final double[] a = new double[5];
                g.fireArray(5, a);
                for (k = 0; k < 5; k++) d("gauss.fireArray", a[k]);
            }
            {
                final double[] a = new double[5];
                g.fireArray(5, a, 2.0, 2.0);
                for (k = 0; k < 5; k++) d("gauss.fireArray2", a[k]);
            }
            for (k = 0; k < 5; k++) d("gauss.op", g.call());
            for (k = 0; k < 5; k++) d("gauss.opMS", g.call(1.0, 0.1));
            i("gauss.F", g.getF());
            final String t = put(g);
            text("gauss.put", t);
            final RandGauss g2 = new RandGauss(e);
            g2.get(new CIStream(t));
            text("gauss.get", put(g2));
            for (k = 0; k < 6; k++) d("gauss.restored", g2.fire());
        }
        {
            final String os = RandGauss.saveDistState(new COStream()).str();
            text("gauss.dist", os);
            d("gauss.distAfter", RandGauss.shoot());
            RandGauss.restoreDistState(new CIStream(os));
            d("gauss.distRestored", RandGauss.shoot());
        }
        {
            final String os = RandGauss.saveFullState(new COStream()).str();
            text("gauss.full", os);
            for (k = 0; k < 5; k++) d("gauss.fullAfter", RandGauss.shoot());
            RandGauss.restoreFullState(new CIStream(os));
            for (k = 0; k < 5; k++) d("gauss.fullRestored", RandGauss.shoot());
        }

        // ---- the tables' ends, through a NonRandomEngine
        final NonRandomEngine nre = new NonRandomEngine();
        {
            final double[] seq = {1e-7, 1.5e-6, 2e-6, 2.5e-6, 4.9e-4, 5e-4, 5.1e-4, 0.0013, 0.1, 0.25, 0.4999, 0.5, 0.5001,
                0.75, 0.9995, 0.99999, 0.9999999, 0.0005, 0.0069, 0.007, 0.0701, 0.069, 0.8, 0.8011, 0.9805,
                0.981, 0.99, 0.9991, 0.99991, 0.999999};
            nre.setRandomSequence(seq, 30);
            for (k = 0; k < 30; k++) d("tables.gaussQ", RandGaussQ.shoot(nre));
            nre.setRandomSequence(seq, 30);
            for (k = 0; k < 30; k++) d("tables.gaussT", RandGaussT.shoot(nre));
            nre.setRandomSequence(seq, 30);
            for (k = 0; k < 30; k++) d("tables.landau", RandLandau.shoot(nre));
        }

        // ---- RandGaussQ, RandGaussT
        for (k = 0; k < 30; k++) d("gaussQ.shoot", RandGaussQ.shoot());
        for (k = 0; k < 10; k++) d("gaussQ.shootMS", RandGaussQ.shoot(1.0, 4.0));
        {
            final double[] a = new double[7];
            RandGaussQ.shootArray(7, a, 0.5, 0.5);
            for (k = 0; k < 7; k++) d("gaussQ.shootArray", a[k]);
        }
        for (k = 0; k < 10; k++) d("gaussQ.shootE", RandGaussQ.shoot(e));
        {
            final double[] a = new double[7];
            RandGaussQ.shootArray(e, 7, a, -1.0, 3.0);
            for (k = 0; k < 7; k++) d("gaussQ.shootEArray", a[k]);
        }
        {
            final RandGaussQ g = new RandGaussQ(e, 100.0, 10.0);
            for (k = 0; k < 10; k++) d("gaussQ.fire", g.fire());
            for (k = 0; k < 5; k++) d("gaussQ.fireMS", g.fire(0.0, 2.0));
            {
                final double[] a = new double[5];
                g.fireArray(5, a);
                for (k = 0; k < 5; k++) d("gaussQ.fireArray", a[k]);
            }
            for (k = 0; k < 5; k++) d("gaussQ.op", g.call());
            for (k = 0; k < 5; k++) d("gaussQ.opMS", g.call(-1.0, 1.0));
            final String t = put(g);
            text("gaussQ.put", t);
            final RandGaussQ g2 = new RandGaussQ(e);
            g2.get(new CIStream(t));
            text("gaussQ.get", put(g2));
        }
        for (k = 0; k < 30; k++) d("gaussT.shoot", RandGaussT.shoot());
        for (k = 0; k < 10; k++) d("gaussT.shootMS", RandGaussT.shoot(1.0, 4.0));
        {
            final double[] a = new double[7];
            RandGaussT.shootArray(7, a, 0.5, 0.5);
            for (k = 0; k < 7; k++) d("gaussT.shootArray", a[k]);
        }
        for (k = 0; k < 10; k++) d("gaussT.shootE", RandGaussT.shoot(e));
        {
            final RandGaussT g = new RandGaussT(e, -5.0, 0.5);
            for (k = 0; k < 10; k++) d("gaussT.fire", g.fire());
            for (k = 0; k < 5; k++) d("gaussT.fireMS", g.fire(0.0, 2.0));
            for (k = 0; k < 5; k++) d("gaussT.op", g.call());
            final String t = put(g);
            text("gaussT.put", t);
            final RandGaussT g2 = new RandGaussT(e);
            g2.get(new CIStream(t));
            text("gaussT.get", put(g2));
        }

        // ---- RandGaussZiggurat, RandExpZiggurat
        for (k = 0; k < 50; k++) d("zig.shoot", RandGaussZiggurat.shoot());
        for (k = 0; k < 20; k++) d("zig.shootMS", RandGaussZiggurat.shoot(1.5f, 0.3f));
        {
            final float[] a = new float[9];
            RandGaussZiggurat.shootArray(9, a, 0.f, 2.f);
            for (k = 0; k < 9; k++) d("zig.shootArrayF", a[k]);
        }
        {
            final double[] a = new double[9];
            RandGaussZiggurat.shootArray(9, a, 1.0, 3.0);
            for (k = 0; k < 9; k++) d("zig.shootArrayD", a[k]);
        }
        for (k = 0; k < 20; k++) d("zig.shootE", RandGaussZiggurat.shoot(e));
        for (k = 0; k < 10; k++) d("zig.shootEMS", RandGaussZiggurat.shoot(e, 0.1f, 10.f));
        {
            final RandGaussZiggurat z = new RandGaussZiggurat(e, 2.0, 0.5);
            for (k = 0; k < 20; k++) d("zig.fire", z.fire());
            for (k = 0; k < 5; k++) d("zig.fireMS", z.fire(1.f, 2.f));
            for (k = 0; k < 5; k++) d("zig.op", z.call());
            for (k = 0; k < 5; k++) d("zig.opMS", z.call(1.0, 2.0));
            {
                final float[] a = new float[5];
                z.fireArray(5, a);
                for (k = 0; k < 5; k++) d("zig.fireArrayF", a[k]);
            }
            {
                final double[] a = new double[5];
                z.fireArray(5, a);
                for (k = 0; k < 5; k++) d("zig.fireArrayD", a[k]);
            }
            text("zig.put", put(z));
        }
        {
            final MixMaxRng ze = new MixMaxRng(777);
            double sum = 0;
            double sum2 = 0;
            float mn = 0;
            float mx = 0;
            long tail = 0;
            for (k = 0; k < 200000; k++) {
                final float v = (float) RandGaussZiggurat.shoot(ze);
                sum += v;
                sum2 += (double) v * v;
                if (v < mn) mn = v;
                if (v > mx) mx = v;
                if (v > 3.442620f || v < -3.442620f) tail++;
            }
            d("zigStress.sum", sum);
            d("zigStress.sum2", sum2);
            d("zigStress.min", mn);
            d("zigStress.max", mx);
            i("zigStress.tail", tail);
        }
        for (k = 0; k < 50; k++) d("expzig.shoot", RandExpZiggurat.shoot());
        for (k = 0; k < 20; k++) d("expzig.shootM", RandExpZiggurat.shoot(2.5f));
        {
            final float[] a = new float[9];
            RandExpZiggurat.shootArray(9, a, 3.f);
            for (k = 0; k < 9; k++) d("expzig.shootArrayF", a[k]);
        }
        {
            final double[] a = new double[9];
            RandExpZiggurat.shootArray(9, a, 0.5);
            for (k = 0; k < 9; k++) d("expzig.shootArrayD", a[k]);
        }
        for (k = 0; k < 20; k++) d("expzig.shootE", RandExpZiggurat.shoot(e));
        {
            final RandExpZiggurat z = new RandExpZiggurat(e, 4.0);
            for (k = 0; k < 20; k++) d("expzig.fire", z.fire());
            for (k = 0; k < 5; k++) d("expzig.fireM", z.fire(0.25f));
            for (k = 0; k < 5; k++) d("expzig.op", z.call());
            for (k = 0; k < 5; k++) d("expzig.opM", z.call(8.f));
            final String t = put(z);
            text("expzig.put", t);
            final RandExpZiggurat z2 = new RandExpZiggurat(e);
            z2.get(new CIStream(t));
            text("expzig.get", put(z2));
            for (k = 0; k < 5; k++) d("expzig.restored", z2.fire());
        }
        {
            final MixMaxRng ze = new MixMaxRng(778);
            double sum = 0;
            float mx = 0;
            long tail = 0;
            for (k = 0; k < 200000; k++) {
                final float v = RandExpZiggurat.shoot(ze);
                sum += v;
                if (v > mx) mx = v;
                if (v > 7.69711f) tail++;
            }
            d("expzigStress.sum", sum);
            d("expzigStress.max", mx);
            i("expzigStress.tail", tail);
        }

        // ---- every engine under the distributions that read its 32-bit words
        {
            final HepRandomEngine[] es = {new MixMaxRng(11), new HepJamesRandom(12), new MTwistEngine(13),
                new RanecuEngine(14), new RanluxEngine(15, 3), new Ranlux64Engine(16, 1), new RanluxppEngine(17),
                new RanshiEngine(18), new DualRand(19), new TripleRand(20), new Hurd160Engine(21),
                new Hurd288Engine(22), new DRand48Engine(23), new RandEngine(24)};
            for (HepRandomEngine x : es) {
                final String n = "engines." + x.name();
                for (k = 0; k < 30; k++) d(n + ".zig", RandGaussZiggurat.shoot(x));
                for (k = 0; k < 30; k++) d(n + ".expzig", RandExpZiggurat.shoot(x));
                for (k = 0; k < 10; k++) d(n + ".gaussQ", RandGaussQ.shoot(x));
                for (k = 0; k < 10; k++) i(n + ".poisson", RandPoisson.shoot(x, 7.5));
            }
        }

        // ---- RandExponential
        for (k = 0; k < 20; k++) d("exp.shoot", RandExponential.shoot());
        for (k = 0; k < 10; k++) d("exp.shootM", RandExponential.shoot(3.0));
        {
            final double[] a = new double[7];
            RandExponential.shootArray(7, a, 0.1);
            for (k = 0; k < 7; k++) d("exp.shootArray", a[k]);
        }
        for (k = 0; k < 10; k++) d("exp.shootE", RandExponential.shoot(e));
        for (k = 0; k < 10; k++) d("exp.shootEM", RandExponential.shoot(e, 7.0));
        {
            final RandExponential x = new RandExponential(e, 2.0);
            for (k = 0; k < 10; k++) d("exp.fire", x.fire());
            for (k = 0; k < 5; k++) d("exp.fireM", x.fire(0.5));
            for (k = 0; k < 5; k++) d("exp.op", x.call());
            {
                final double[] a = new double[5];
                x.fireArray(5, a);
                for (k = 0; k < 5; k++) d("exp.fireArray", a[k]);
            }
            final String t = put(x);
            text("exp.put", t);
            final RandExponential x2 = new RandExponential(e);
            x2.get(new CIStream(t));
            text("exp.get", put(x2));
        }

        // ---- RandBreitWigner
        for (k = 0; k < 10; k++) d("bw.shoot", RandBreitWigner.shoot());
        for (k = 0; k < 10; k++) d("bw.shootAB", RandBreitWigner.shoot(91.19, 2.5));
        for (k = 0; k < 10; k++) d("bw.shootABC", RandBreitWigner.shoot(91.19, 2.5, 10.0));
        d("bw.shootABC0", RandBreitWigner.shoot(91.19, 0.0, 10.0));
        for (k = 0; k < 10; k++) d("bw.shootM2", RandBreitWigner.shootM2(91.19, 2.5));
        for (k = 0; k < 10; k++) d("bw.shootM2C", RandBreitWigner.shootM2(91.19, 2.5, 10.0));
        for (k = 0; k < 10; k++) d("bw.shootM2Cbig", RandBreitWigner.shootM2(1.0, 0.5, 5.0));
        {
            final double[] a = new double[5];
            RandBreitWigner.shootArray(5, a);
            for (k = 0; k < 5; k++) d("bw.shootArray", a[k]);
        }
        {
            final double[] a = new double[5];
            RandBreitWigner.shootArray(5, a, 3.0, 0.1, 0.2);
            for (k = 0; k < 5; k++) d("bw.shootArray3", a[k]);
        }
        for (k = 0; k < 10; k++) d("bw.shootE", RandBreitWigner.shoot(e, 0.775, 0.149));
        for (k = 0; k < 10; k++) d("bw.shootEC", RandBreitWigner.shoot(e, 0.775, 0.149, 0.3));
        for (k = 0; k < 10; k++) d("bw.shootEM2", RandBreitWigner.shootM2(e, 0.775, 0.149));
        for (k = 0; k < 10; k++) d("bw.shootEM2C", RandBreitWigner.shootM2(e, 0.775, 0.149, 0.3));
        {
            final RandBreitWigner b = new RandBreitWigner(e, 1.02, 0.0043);
            for (k = 0; k < 10; k++) d("bw.fire", b.fire());
            for (k = 0; k < 5; k++) d("bw.fireC", b.fire(1.02, 0.0043, 0.01));
            for (k = 0; k < 5; k++) d("bw.fireM2", b.fireM2());
            for (k = 0; k < 5; k++) d("bw.fireM2C", b.fireM2(1.02, 0.0043, 0.01));
            for (k = 0; k < 5; k++) d("bw.op", b.call());
            final String t = put(b);
            text("bw.put", t);
            final RandBreitWigner b2 = new RandBreitWigner(e);
            b2.get(new CIStream(t));
            text("bw.get", put(b2));
        }

        // ---- RandChiSquare, RandGamma, RandStudentT
        {
            final double[] as = {1.0, 1.5, 3.5, 20.0, 0.5};
            for (double a : as) for (k = 0; k < 10; k++) d("chi2.shoot", RandChiSquare.shoot(a));
            for (k = 0; k < 10; k++) d("chi2.shootDefault", RandChiSquare.shoot());
            for (double a : as) for (k = 0; k < 10; k++) d("chi2.shootE", RandChiSquare.shoot(e, a));
            final RandChiSquare c = new RandChiSquare(e, 4.0);
            for (k = 0; k < 10; k++) d("chi2.fire", c.fire());
            for (k = 0; k < 5; k++) d("chi2.fireA", c.fire(1.0));
            {
                final double[] a = new double[5];
                c.fireArray(5, a);
                for (k = 0; k < 5; k++) d("chi2.fireArray", a[k]);
            }
            final String t = put(c);
            text("chi2.put", t);
            final RandChiSquare c2 = new RandChiSquare(e);
            c2.get(new CIStream(t));
            text("chi2.get", put(c2));
        }
        {
            final double[] ks = {0.3, 0.999, 1.0, 2.5, 3.0, 5.0, 13.5, 20.0, 0.0, -1.0};
            for (double kk : ks) for (k = 0; k < 10; k++) d("gamma.shoot", RandGamma.shoot(kk, 1.5));
            for (k = 0; k < 5; k++) d("gamma.shootLambda0", RandGamma.shoot(2.0, 0.0));
            for (k = 0; k < 10; k++) d("gamma.shootDefault", RandGamma.shoot());
            for (double kk : ks) for (k = 0; k < 10; k++) d("gamma.shootE", RandGamma.shoot(e, kk, 0.25));
            final RandGamma g = new RandGamma(e, 7.0, 2.0);
            for (k = 0; k < 10; k++) d("gamma.fire", g.fire());
            for (k = 0; k < 5; k++) d("gamma.fireKL", g.fire(0.5, 1.0));
            for (k = 0; k < 5; k++) d("gamma.op", g.call());
            final String t = put(g);
            text("gamma.put", t);
            final RandGamma g2 = new RandGamma(e);
            g2.get(new CIStream(t));
            text("gamma.get", put(g2));
        }
        {
            final double[] as = {1.0, 2.0, 3.5, 30.0, 0.5};
            for (double a : as) for (k = 0; k < 10; k++) d("studentT.shoot", RandStudentT.shoot(a));
            d("studentT.shootNeg", RandStudentT.shoot(-1.0));
            for (double a : as) for (k = 0; k < 10; k++) d("studentT.shootE", RandStudentT.shoot(e, a));
            final RandStudentT s = new RandStudentT(e, 5.0);
            for (k = 0; k < 10; k++) d("studentT.fire", s.fire());
            for (k = 0; k < 5; k++) d("studentT.op", s.call());
            final String t = put(s);
            text("studentT.put", t);
            final RandStudentT s2 = new RandStudentT(e);
            s2.get(new CIStream(t));
            text("studentT.get", put(s2));
        }

        // ---- RandPoisson, RandPoissonQ, RandPoissonT
        {
            final double[] ms = {0.5, 5.0, 11.9, 12.0, 25.0, 25.0, 300.0, 1e5, 2.05e9, 5.0, -1.0};
            for (double m : ms) for (k = 0; k < 10; k++) i("poisson.shoot", RandPoisson.shoot(m));
            for (double m : ms) for (k = 0; k < 10; k++) i("poisson.shootE", RandPoisson.shoot(e, m));
            {
                final long[] a = new long[7];
                RandPoisson.shootArray(7, a, 40.0);
                for (k = 0; k < 7; k++) i("poisson.shootArray", a[k]);
            }
            final RandPoisson p = new RandPoisson(e, 33.0);
            for (k = 0; k < 10; k++) i("poisson.fire", p.fire());
            for (k = 0; k < 5; k++) i("poisson.fireM", p.fire(3.0));
            for (k = 0; k < 5; k++) d("poisson.op", p.call());
            {
                final long[] a = new long[5];
                p.fireArray(5, a);
                for (k = 0; k < 5; k++) i("poisson.fireArray", a[k]);
            }
            final String t = put(p);
            text("poisson.put", t);
            final RandPoisson p2 = new RandPoisson(e);
            p2.get(new CIStream(t));
            text("poisson.get", put(p2));
            for (k = 0; k < 5; k++) i("poisson.restored", p2.fire());
        }
        {
            final double[] ms = {0.0, 3.0, 9.99, 10.0, 37.3, 94.9, 99.9, 100.0, 250.0, 1e4, 3e9};
            for (double m : ms) for (k = 0; k < 10; k++) i("poissonQ.shoot", RandPoissonQ.shoot(m));
            for (double m : ms) for (k = 0; k < 10; k++) i("poissonQ.shootE", RandPoissonQ.shoot(e, m));
            final RandPoissonQ q = new RandPoissonQ(e, 150.0);
            for (k = 0; k < 10; k++) i("poissonQ.fire", q.fire());
            for (k = 0; k < 5; k++) i("poissonQ.fireM", q.fire(55.5));
            {
                final long[] a = new long[5];
                q.fireArray(5, a);
                for (k = 0; k < 5; k++) i("poissonQ.fireArray", a[k]);
            }
            for (k = 0; k < 5; k++) d("poissonQ.op", q.call());
            final RandPoissonQ q2 = new RandPoissonQ(e, 42.0);
            for (k = 0; k < 10; k++) i("poissonQ.fireSmall", q2.fire());
            final String t = put(q);
            text("poissonQ.put", t);
            final RandPoissonQ q3 = new RandPoissonQ(e);
            q3.get(new CIStream(t));
            text("poissonQ.get", put(q3));
            i("poissonQ.boundary", RandPoissonQ.tableBoundary());
        }
        {
            final double[] ms = {5.0, 50.0, 99.0, 100.0, 150.0, 1e4};
            for (double m : ms) for (k = 0; k < 10; k++) i("poissonT.shoot", RandPoissonT.shoot(m));
            for (double m : ms) for (k = 0; k < 10; k++) i("poissonT.shootE", RandPoissonT.shoot(e, m));
            final RandPoissonT p = new RandPoissonT(e, 64.0);
            for (k = 0; k < 10; k++) i("poissonT.fire", p.fire());
            final RandPoissonT p2 = new RandPoissonT(e, 640.0);
            for (k = 0; k < 10; k++) i("poissonT.fireLarge", p2.fire());
            for (k = 0; k < 5; k++) d("poissonT.op", p.call());
            final String t = put(p);
            text("poissonT.put", t);
            final RandPoissonT p3 = new RandPoissonT(e);
            p3.get(new CIStream(t));
            text("poissonT.get", put(p3));
        }

        // ---- RandBinomial
        {
            final long[] ns = {10, 10, 100, 1000, 1000000, 50, 7};
            final double[] ps = {0.3, 0.9, 0.5, 0.02, 0.4, 0.0, 1.0};
            for (int j = 0; j < 7; j++) for (k = 0; k < 10; k++) d("binomial.shoot", RandBinomial.shoot(ns[j], ps[j]));
            for (k = 0; k < 10; k++) d("binomial.shootDefault", RandBinomial.shoot());
            for (int j = 0; j < 7; j++) for (k = 0; k < 10; k++) d("binomial.shootE", RandBinomial.shoot(e, ns[j], ps[j]));
            final RandBinomial b = new RandBinomial(e, 200, 0.75);
            for (k = 0; k < 10; k++) d("binomial.fire", b.fire());
            for (k = 0; k < 5; k++) d("binomial.fireNP", b.fire(30, 0.1));
            for (k = 0; k < 5; k++) d("binomial.op", b.call());
            final String t = put(b);
            text("binomial.put", t);
            final RandBinomial b2 = new RandBinomial(e);
            b2.get(new CIStream(t));
            text("binomial.get", put(b2));
        }

        // ---- RandLandau
        for (k = 0; k < 20; k++) d("landau.shoot", RandLandau.shoot());
        {
            final double[] a = new double[7];
            RandLandau.shootArray(7, a);
            for (k = 0; k < 7; k++) d("landau.shootArray", a[k]);
        }
        for (k = 0; k < 10; k++) d("landau.shootE", RandLandau.shoot(e));
        {
            final RandLandau l = new RandLandau(e);
            for (k = 0; k < 10; k++) d("landau.fire", l.fire());
            for (k = 0; k < 5; k++) d("landau.op", l.call());
            final String t = put(l);
            text("landau.put", t);
            final RandLandau l2 = new RandLandau(e);
            l2.get(new CIStream(t));
            text("landau.get", put(l2));
        }

        // ---- RandGeneral
        {
            final double[] pdf = {1, 2, 4, 0, 3, 5.5, 0.25, 0, 7, 1};
            final RandGeneral g0 = new RandGeneral(e, pdf, 10, 0);
            for (k = 0; k < 20; k++) d("general.fire0", g0.fire());
            final RandGeneral g1 = new RandGeneral(e, pdf, 10, 1);
            for (k = 0; k < 20; k++) d("general.fire1", g1.fire());
            for (k = 0; k < 10; k++) d("general.shootE", g0.shoot(e));
            for (k = 0; k < 10; k++) d("general.shoot", g0.shoot());
            {
                final double[] a = new double[5];
                g1.fireArray(5, a);
                for (k = 0; k < 5; k++) d("general.fireArray", a[k]);
            }
            final RandGeneral gs = new RandGeneral(pdf, 10);
            for (k = 0; k < 10; k++) d("general.static", gs.fire());
            final double[] none = {0, 0, 0};
            final RandGeneral gf = new RandGeneral(e, none, 3);
            for (k = 0; k < 5; k++) d("general.flat", gf.fire());
            final String t = put(g0);
            text("general.put", t);
            final RandGeneral g2 = new RandGeneral(e, pdf, 1);
            g2.get(new CIStream(t));
            text("general.get", put(g2));
            for (k = 0; k < 5; k++) d("general.restored", g2.fire());
        }

        // ---- RandSkewNormal
        {
            final double[] ks = {0.0, 1.0, -3.0, 10.0};
            for (k = 0; k < 10; k++) d("skew.shoot", RandSkewNormal.shoot());
            for (double kk : ks) for (k = 0; k < 10; k++) d("skew.shootK", RandSkewNormal.shoot(kk));
            for (double kk : ks) for (k = 0; k < 10; k++) d("skew.shootE", RandSkewNormal.shoot(e, kk));
            final RandSkewNormal s = new RandSkewNormal(e, 2.0);
            for (k = 0; k < 10; k++) d("skew.fire", s.fire());
            for (k = 0; k < 5; k++) d("skew.op", s.call());
            final String t = put(s);
            text("skew.put", t);
            final RandSkewNormal s2 = new RandSkewNormal(e);
            s2.get(new CIStream(t));
            text("skew.get", put(s2));
        }

        // ---- StaticRandomStates: the same kind of engine, then another
        {
            final String os = StaticRandomStates.save(new COStream()).str();
            text("static.save", os);
            for (k = 0; k < 5; k++) d("static.after", RandGauss.shoot());
            for (k = 0; k < 5; k++) i("static.afterBit", RandFlat.shootBit());
            StaticRandomStates.restore(new CIStream(os));
            for (k = 0; k < 5; k++) d("static.restored", RandGauss.shoot());
            for (k = 0; k < 5; k++) i("static.restoredBit", RandFlat.shootBit());
            final HepJamesRandom je = new HepJamesRandom(5);
            HepRandom.setTheEngine(je);
            StaticRandomStates.restore(new CIStream(os));
            text("static.engine", HepRandom.getTheEngine().name());
            for (k = 0; k < 5; k++) d("static.restored2", RandGauss.shoot());
            HepRandom.setTheEngine(se);
        }
        for (k = 0; k < 5; k++) d("end.flat", RandFlat.shoot());
    }
}

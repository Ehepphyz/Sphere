package com.sphere.core.clhep.validation;

import com.sphere.core.clhep.random.CLib;
import com.sphere.core.clhep.random.DRand48Engine;
import com.sphere.core.clhep.random.DualRand;
import com.sphere.core.clhep.random.HepJamesRandom;
import com.sphere.core.clhep.random.HepRandomEngine;
import com.sphere.core.clhep.random.Hurd160Engine;
import com.sphere.core.clhep.random.Hurd288Engine;
import com.sphere.core.clhep.random.MTwistEngine;
import com.sphere.core.clhep.random.NonRandomEngine;
import com.sphere.core.clhep.random.ProcessState;
import com.sphere.core.clhep.random.RandEngine;
import com.sphere.core.clhep.random.RanecuEngine;
import com.sphere.core.clhep.random.Ranlux64Engine;
import com.sphere.core.clhep.random.RanluxEngine;
import com.sphere.core.clhep.random.RanluxppEngine;
import com.sphere.core.clhep.random.RanshiEngine;
import com.sphere.core.clhep.random.TripleRand;
import com.sphere.core.clhep.random.MixMaxRng;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.CRand;

/**
 * The Java twin of engines_ref.cc: every engine's numbers, raw bits, and its state saved as words and
 * as text then restored through the engine factory, as the C++ CLHEP gives them.
 */
public final class EnginesReference extends Reference {

    private final CRand.Library randLibrary;

    public EnginesReference() {
        this(CLib.defaultRandLibrary());
    }

    /** With the rand() of the C library given (RandEngine draws from it). */
    public EnginesReference(CRand.Library randLibrary) {
        this.randLibrary = randLibrary;
    }

    @Override
    public String name() {
        return "engines";
    }

    private void exercise(String n, HepRandomEngine e) {
        for (int i = 0; i < 300; i++) d(n + ".flat", e.flat());
        for (int i = 0; i < 20; i++) line(n + ".uint " + e.toUnsignedInt());
        for (int i = 0; i < 5; i++) d(n + ".float", e.toFloat());
        final double[] arr = new double[37];
        e.flatArray(37, arr);
        for (int i = 0; i < 37; i++) d(n + ".arr", arr[i]);
        final long[] v = e.put();
        final StringBuilder b = new StringBuilder(n + ".words " + v.length + ":");
        for (long w : v) b.append(' ').append(Long.toUnsignedString(w));
        line(b.toString());
        final String os = e.put(new COStream()).str();
        text(n + ".text", os);
        final double[] after = new double[50];
        for (int i = 0; i < 50; i++) after[i] = e.flat();
        final HepRandomEngine w = HepRandomEngine.newEngine(v);
        if (w != null) {
            for (int i = 0; i < 50; i++) d(n + ".fromWords", w.flat());
        } else {
            line(n + ".fromWords null");
        }
        final HepRandomEngine t = HepRandomEngine.newEngine(new CIStream(os));
        if (t != null) {
            for (int i = 0; i < 50; i++) d(n + ".fromText", t.flat());
        } else {
            line(n + ".fromText null");
        }
        for (int i = 0; i < 50; i++) d(n + ".after", after[i]);
        line(n + ".seed " + e.getSeed());
    }

    @Override
    protected void run() {
        // the counters of the default-made engines and the C library as a fresh C++ program has them
        ProcessState.restart(randLibrary);
        exercise("mixmax.default", new MixMaxRng());
        exercise("mixmax.12345", new MixMaxRng(12345));
        {
            final MixMaxRng e = new MixMaxRng();
            e.setSeeds(new long[] {123, 456, 789, 1011}, 4);
            exercise("mixmax.seeds4", e);
        }
        {
            final MixMaxRng e = new MixMaxRng();
            e.setSeeds(new long[] {2147483647L, 7}, 0);
            exercise("mixmax.seeds2", e);
        }
        {
            final MixMaxRng e = new MixMaxRng();
            e.setSeed(987654321L, 0);
            exercise("mixmax.setSeed", e);
        }

        exercise("james.default", new HepJamesRandom());
        exercise("james.seed", new HepJamesRandom(31415926));
        exercise("james.rowcol", new HepJamesRandom(17, 1));
        seeded("james.setSeeds", new HepJamesRandom(), new long[] {900000000L}, 0);
        exercise("mtwist.default", new MTwistEngine());
        exercise("mtwist.seed", new MTwistEngine(2718281));
        exercise("mtwist.rowcol", new MTwistEngine(300, 1));
        seeded("mtwist.setSeeds", new MTwistEngine(), new long[] {123456789, 987654321}, 7);
        exercise("ranecu.default", new RanecuEngine());
        exercise("ranecu.index", new RanecuEngine(250));
        {
            final RanecuEngine e = new RanecuEngine();
            e.setSeed(77, 5);
            exercise("ranecu.setSeed", e);
        }
        seeded("ranecu.setSeeds", new RanecuEngine(), new long[] {1234567, 7654321}, 3);
        exercise("ranlux.default", new RanluxEngine());
        exercise("ranlux.seed4", new RanluxEngine(19780503L, 4));
        exercise("ranlux.seed0", new RanluxEngine(12345L, 0));
        exercise("ranlux.rowcol", new RanluxEngine(5, 1, 2));
        seeded("ranlux.setSeeds", new RanluxEngine(), new long[] {11, 22, 33, 0}, 30);
        exercise("ranlux64.default", new Ranlux64Engine());
        exercise("ranlux64.seed2", new Ranlux64Engine(19780503L, 2));
        exercise("ranlux64.seed0", new Ranlux64Engine(4242L, 0));
        exercise("ranlux64.rowcol", new Ranlux64Engine(9, 0, 1));
        seeded("ranlux64.setSeeds", new Ranlux64Engine(), new long[] {111, 222, 0}, 14);
        {
            final Ranlux64Engine e = new Ranlux64Engine();
            e.setSeed(55555, 1);
            exercise("ranlux64.setSeed", e);
        }
        exercise("ranluxpp.default", new RanluxppEngine());
        exercise("ranluxpp.seed", new RanluxppEngine(1234567));
        {
            final RanluxppEngine e = new RanluxppEngine(42);
            e.skip(1000);
            exercise("ranluxpp.skip", e);
        }
        exercise("ranshi.default", new RanshiEngine());
        exercise("ranshi.seed", new RanshiEngine(888888));
        exercise("ranshi.rowcol", new RanshiEngine(3, 4));
        seeded("ranshi.setSeeds", new RanshiEngine(), new long[] {5, 6, 0}, 0);
        exercise("dualrand.default", new DualRand());
        exercise("dualrand.seed", new DualRand(65432));
        exercise("dualrand.rowcol", new DualRand(7, 3));
        exercise("triplerand.default", new TripleRand());
        exercise("triplerand.seed", new TripleRand(65432));
        exercise("triplerand.rowcol", new TripleRand(7, 3));
        exercise("hurd160.default", new Hurd160Engine());
        exercise("hurd160.seed", new Hurd160Engine(1999));
        exercise("hurd160.rowcol", new Hurd160Engine(400, 1));
        exercise("hurd288.default", new Hurd288Engine());
        exercise("hurd288.seed", new Hurd288Engine(1999));
        exercise("hurd288.rowcol", new Hurd288Engine(400, 1));
        exercise("drand48.default", new DRand48Engine());
        exercise("drand48.seed", new DRand48Engine(31337));
        exercise("rand.default", new RandEngine());
        exercise("rand.seed", new RandEngine(271828));
        {
            final NonRandomEngine e = new NonRandomEngine();
            e.setRandomSequence(new double[] {0.1, 0.2, 0.3, 0.4, 0.5}, 5);
            e.setNextRandom(0.25);
            e.setRandomInterval(0.0625);
            for (int i = 0; i < 40; i++) d("nonrandom.flat", e.flat());
            final long[] v = e.put();
            line("nonrandom.words " + v.length);
            final HepRandomEngine w = HepRandomEngine.newEngine(v);
            for (int i = 0; i < 10; i++) d("nonrandom.restored", w.flat());
        }
        exercise("mixmax.default2", new MixMaxRng());
    }

    private void seeded(String n, HepRandomEngine e, long[] seeds, int extra) {
        e.setSeeds(seeds, extra);
        exercise(n, e);
    }
}

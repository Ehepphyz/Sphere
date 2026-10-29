package com.sphere.core.clhep.validation;

import static com.sphere.core.clhep.units.SystemOfUnits.halfpi;
import static com.sphere.core.clhep.units.SystemOfUnits.twopi;
import static com.sphere.core.clhep.validation.ClhepTests.say;

import com.sphere.core.clhep.CMath;
import com.sphere.core.clhep.matrix.HepMatrix;
import com.sphere.core.clhep.matrix.HepSymMatrix;
import com.sphere.core.clhep.matrix.HepVector;
import com.sphere.core.clhep.matrix.MatrixLinear;
import com.sphere.core.clhep.random.DRand48Engine;
import com.sphere.core.clhep.random.DualRand;
import com.sphere.core.clhep.random.HepJamesRandom;
import com.sphere.core.clhep.random.HepRandom;
import com.sphere.core.clhep.random.HepRandomEngine;
import com.sphere.core.clhep.random.Hurd160Engine;
import com.sphere.core.clhep.random.Hurd288Engine;
import com.sphere.core.clhep.random.MTwistEngine;
import com.sphere.core.clhep.random.MixMaxRng;
import com.sphere.core.clhep.random.NonRandomEngine;
import com.sphere.core.clhep.random.RandBinomial;
import com.sphere.core.clhep.random.RandBit;
import com.sphere.core.clhep.random.RandBreitWigner;
import com.sphere.core.clhep.random.RandChiSquare;
import com.sphere.core.clhep.random.RandEngine;
import com.sphere.core.clhep.random.RandExponential;
import com.sphere.core.clhep.random.RandFlat;
import com.sphere.core.clhep.random.RandGamma;
import com.sphere.core.clhep.random.RandGauss;
import com.sphere.core.clhep.random.RandGaussQ;
import com.sphere.core.clhep.random.RandGaussT;
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
import com.sphere.core.clhep.random.TripleRand;
import com.sphere.core.clhep.randomobjects.RandMultiGauss;
import com.sphere.core.clhep.validation.ClhepTests.Exit;
import com.sphere.core.clhep.validation.ClhepTests.Io;
import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * CLHEP's Random tests of saving and restoring (Random/test): the engines' and the distributions' states
 * through files, streams and vectors, static and instance, anonymous restores, copies, Ranecu's seeds. Each
 * writes its report in a file of its own (CLEAN_OUTPUT), as the C++ does; the state files they write and read
 * back are compared as well.
 */
final class RandomTests {

    private RandomTests() {
    }

    /** An engine class as the templates take it: E::engineName(), E e, E e(seed) and E f(e) (null: none). */
    private record Engine(String name, Supplier<HepRandomEngine> make, LongFunction<HepRandomEngine> seeded,
                          UnaryOperator<HepRandomEngine> copy) {
    }

    private static final Engine DRAND48 = new Engine(DRand48Engine.engineName(), DRand48Engine::new, DRand48Engine::new, null);
    private static final Engine DUAL = new Engine(DualRand.engineName(), DualRand::new, DualRand::new,
        e -> new DualRand((DualRand) e));
    private static final Engine HURD160 = new Engine(Hurd160Engine.engineName(), Hurd160Engine::new, Hurd160Engine::new,
        e -> new Hurd160Engine((Hurd160Engine) e));
    private static final Engine HURD288 = new Engine(Hurd288Engine.engineName(), Hurd288Engine::new, Hurd288Engine::new,
        e -> new Hurd288Engine((Hurd288Engine) e));
    private static final Engine JAMES = new Engine(HepJamesRandom.engineName(), HepJamesRandom::new, HepJamesRandom::new,
        e -> new HepJamesRandom((HepJamesRandom) e));
    private static final Engine MIXMAX = new Engine(MixMaxRng.engineName(), MixMaxRng::new, MixMaxRng::new,
        e -> new MixMaxRng((MixMaxRng) e));
    private static final Engine MTWIST = new Engine(MTwistEngine.engineName(), MTwistEngine::new, MTwistEngine::new,
        e -> new MTwistEngine((MTwistEngine) e));
    private static final Engine RAND = new Engine(RandEngine.engineName(), RandEngine::new, RandEngine::new, null);
    /** Its seed is an int: RanecuEngine(int index). */
    private static final Engine RANECU = new Engine(RanecuEngine.engineName(), RanecuEngine::new, s -> new RanecuEngine((int) s),
        e -> new RanecuEngine((RanecuEngine) e));
    private static final Engine RANLUX64 = new Engine(Ranlux64Engine.engineName(), Ranlux64Engine::new, Ranlux64Engine::new,
        e -> new Ranlux64Engine((Ranlux64Engine) e));
    private static final Engine RANLUX = new Engine(RanluxEngine.engineName(), RanluxEngine::new, RanluxEngine::new,
        e -> new RanluxEngine((RanluxEngine) e));
    private static final Engine RANLUXPP = new Engine(RanluxppEngine.engineName(), RanluxppEngine::new, RanluxppEngine::new,
        e -> new RanluxppEngine((RanluxppEngine) e));
    private static final Engine RANSHI = new Engine(RanshiEngine.engineName(), RanshiEngine::new, RanshiEngine::new,
        e -> new RanshiEngine((RanshiEngine) e));
    private static final Engine TRIPLE = new Engine(TripleRand.engineName(), TripleRand::new, TripleRand::new,
        e -> new TripleRand((TripleRand) e));
    private static final Engine NONRANDOM = new Engine(NonRandomEngine.engineName(), NonRandomEngine::new, null,
        e -> new NonRandomEngine((NonRandomEngine) e));

    /** The tests' aSequence(n): a DualRand's flat numbers, for the NonRandomEngine. */
    private static double[] aSequence(int n) {
        final DualRand e = new DualRand(13542);
        final RandFlat f = new RandFlat(e);
        final double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = f.call();
        return v;
    }

    /** if (stat != 0) {...} else {...}: the last lines of each test's report, and its exit code. */
    private static int conclude(Io io, COStream output, int stat, String passed) {
        output.put("\n=============================================\n\n");
        if (stat != 0) {
            say(io.cout, "One or more problems detected: stat = ", stat, "\n");
            say(output, "One or more problems detected: stat = ", stat, "\n");
        } else {
            output.put(passed + " passed with no problems detected.\n");
        }
        if (stat == 0) return 0;
        if (stat > 0) return -(stat | 1);
        return stat | 1;
    }

    /* ---- testSaveEngineStatus ---- */

    /** remembered_r2, remembered_r1005, remembered_r1006 and remembered_r1007. */
    private static final int R2 = 0;
    private static final int R1005 = 1;
    private static final int R1006 = 2;
    private static final int R1007 = 3;

    private static int saveStepX(COStream output, double[] rem) {
        double r = RandGauss.shoot();
        say(output, "r(1) = ", r, "\n");
        HepRandom.saveEngineStatus();
        r = RandGauss.shoot();
        say(output, "r(2) = ", r, "\n");
        rem[R2] = r;
        r = RandGauss.shoot();
        say(output, "r(3) = ", r, "\n");
        for (int i = 0; i < 1001; i++) r = RandGauss.shoot();
        r = RandGauss.shoot();
        rem[R1005] = r;
        say(output, "r1005= ", r, "\n");
        RandGauss.shoot();
        return 0;
    }

    private static int restoreStepX(COStream output, double[] rem) {
        HepRandom.restoreEngineStatus();
        double r = RandGauss.shoot();
        say(output, "restored r(2) = ", r, "\n");
        if (r != rem[R2]) output.put("THIS DOES NOT MATCH REMEMBERED VALUE BUT THAT IS EXPECTED\n");
        r = RandGauss.shoot();
        say(output, "restored r(3) = ", r, "\n");
        for (int i = 0; i < 1001; i++) r = RandGauss.shoot();
        r = RandGauss.shoot();
        say(output, "restored r1005= ", r, "\n");
        if (r != rem[R1005]) output.put("THIS DOES NOT MATCH REMEMBERED VALUE BUT THAT IS EXPECTED\n");
        return 0;
    }

    private static int bsaveStepX(COStream output, double[] rem) {
        int r = RandFlat.shootBit();
        say(output, "r(1) = ", r, "\n");
        HepRandom.saveEngineStatus();
        r = RandFlat.shootBit();
        say(output, "r(2) = ", r, "\n");
        rem[R2] = r;
        r = RandFlat.shootBit();
        say(output, "r(3) = ", r, "\n");
        double d;
        for (int i = 0; i < 1001; i++) {
            d = RandFlat.shoot();
            if (d > 1) output.put("This line inserted so clever compilers don't warn about not using d\n");
        }
        r = RandFlat.shootBit();
        rem[R1005] = r;
        say(output, "r1005= ", r, "\n");
        RandFlat.shootBit();
        return 0;
    }

    private static int brestoreStepX(COStream output, double[] rem) {
        HepRandom.restoreEngineStatus();
        int r = RandFlat.shootBit();
        say(output, "restored r(2) = ", r, "\n");
        if (r != rem[R2]) output.put("THIS DOES NOT MATCH REMEMBERED VALUE BUT THAT IS EXPECTED\n");
        r = RandFlat.shootBit();
        say(output, "restored r(3) = ", r, "\n");
        for (int i = 0; i < 1001; i++) r = RandFlat.shootBit();
        r = RandFlat.shootBit();
        say(output, "restored r1005= ", r, "\n");
        if (r != rem[R1005]) output.put("THIS DOES NOT MATCH REMEMBERED VALUE BUT THAT IS EXPECTED\n");
        return 0;
    }

    private static int saveStep(COStream output, double[] rem) {
        double r = RandGauss.shoot();
        say(output, "r(1) = ", r, "\n");
        RandGauss.saveEngineStatus();
        r = RandGauss.shoot();
        say(output, "r(2) = ", r, "\n");
        rem[R2] = r;
        r = RandGauss.shoot();
        say(output, "r(3) = ", r, "\n");
        for (int i = 0; i < 1001; i++) r = RandGauss.shoot();
        r = RandGauss.shoot();
        rem[R1005] = r;
        say(output, "r1005= ", r, "\n");
        RandGauss.shoot();
        return 0;
    }

    /** The mismatch a restore reports, on cout and in the report. */
    private static void mismatch(Io io, COStream output, String what, Object r) {
        for (COStream os : new COStream[] {io.cout, output}) {
            say(os, what, r, "\n");
            os.put("????? THIS DOES NOT MATCH REMEMBERED VALUE!\n");
        }
    }

    private static int restoreStep(Io io, COStream output, double[] rem) {
        int stat = 0;
        RandGauss.restoreEngineStatus();
        double r = RandGauss.shoot();
        say(output, "restored r(2) = ", r, "\n");
        if (r != rem[R2]) {
            mismatch(io, output, "restored r(2) = ", r);
            stat += 1;
        }
        r = RandGauss.shoot();
        say(output, "restored r(3) = ", r, "\n");
        for (int i = 0; i < 1001; i++) r = RandGauss.shoot();
        r = RandGauss.shoot();
        say(output, "restored r1005= ", r, "\n");
        if (r != rem[R1005]) {
            mismatch(io, output, "restored r1005= ", r);
            stat += 2;
        }
        return stat;
    }

    private static int bsaveStep(COStream output, double[] rem) {
        int r = RandFlat.shootBit();
        say(output, "r(1) = ", r, "\n");
        RandFlat.saveEngineStatus();
        r = RandFlat.shootBit();
        say(output, "r(2) = ", r, "\n");
        rem[R2] = r;
        r = RandFlat.shootBit();
        say(output, "r(3) = ", r, "\n");
        for (int i = 0; i < 1001; i++) r = RandFlat.shootBit();
        r = RandFlat.shootBit();
        rem[R1005] = r;
        say(output, "r1005 = ", r, "\n");
        r = RandFlat.shootBit();
        rem[R1006] = r;
        say(output, "r1006 = ", r, "\n");
        r = RandFlat.shootBit();
        rem[R1007] = r;
        say(output, "r1007 = ", r, "\n");
        RandFlat.shootBit();
        return 0;
    }

    private static int brestoreStep(Io io, COStream output, double[] rem) {
        int stat = 0;
        RandFlat.restoreEngineStatus();
        int r = RandFlat.shootBit();
        say(output, "restored r(2) = ", r, "\n");
        if (r != rem[R2]) {
            stat += 4;
            mismatch(io, output, "restored r(2) = ", r);
        }
        r = RandFlat.shootBit();
        say(output, "restored r(3) = ", r, "\n");
        for (int i = 0; i < 1001; i++) r = RandFlat.shootBit();
        r = RandFlat.shootBit();
        say(output, "restored r1005= ", r, "\n");
        if (r != rem[R1005]) {
            stat += 8;
            mismatch(io, output, "restored r1005= ", r);
        }
        r = RandFlat.shootBit();
        say(output, "restored r1006= ", r, "\n");
        if (r != rem[R1006]) {
            stat += 16;
            mismatch(io, output, "restored r1006= ", r);
        }
        r = RandFlat.shootBit();
        say(output, "restored r1007= ", r, "\n");
        if (r != rem[R1007]) {
            stat += 32;
            mismatch(io, output, "restored r1007= ", r);
        }
        return stat;
    }

    /** TEST_ORIGINAL_SAVE alone (the missing files and old saves are off for routine validation). */
    static int testSaveEngineStatus(Io io) {
        HepRandom.createInstance();
        final COStream output = io.file("testSaveEngineStatus.cout");
        final double[] rem = new double[4];
        int stat = 0;
        output.put("=====================================\n");
        output.put("             Part I \n");
        output.put("Original tests of static save/restore\n");
        output.put("=====================================\n\n");
        output.put("Using old method or HepRandom::saveEngineStatus:\n");
        output.put("All these tests should have a chance of failure.\n");
        output.put(RandGauss.getTheEngine().name());
        output.put(RandGaussQ.getTheEngine().name());
        stat |= saveStepX(output, rem);
        stat |= restoreStepX(output, rem);
        stat |= bsaveStepX(output, rem);
        stat |= brestoreStepX(output, rem);
        output.put("Using the class-specific RandGauss::saveEngineStatus:\n");
        output.put("All these tests should work properly.\n");
        stat |= saveStep(output, rem);
        stat |= restoreStep(io, output, rem);
        stat |= bsaveStep(output, rem);
        stat |= brestoreStep(io, output, rem);
        return conclude(io, output, stat, "testSaveEngineStatus");
    }

    /* ---- testSaveSharedEngines ---- */

    private static void twenty(COStream output, String a, double x, String b, double y) {
        final int pr = output.precision();
        output.precision(20);
        say(output, a, x, b, y, "\n");
        output.precision(pr);
    }

    private static int checkSharingDistributions(Io io, COStream output, Engine E, HepRandom d1, HepRandom d2,
                                                 Function<HepRandomEngine, HepRandom> D1, Function<HepRandomEngine, HepRandom> D2,
                                                 int n1, int n2) {
        int stat = 0;
        say(output, "checkSharingDistribution with: \n", d1.name(), " using ", d1.engine().name(), " and\n", d2.name(),
            " using ", d2.engine().name(), "\n");
        double r = 0;
        r = d1.call();
        r += d2.call();
        for (int i = 0; i < n1; i++) r += d1.call();
        for (int j = 0; j < n2; j++) r += d2.call();
        {
            final COStream os = io.file("t1_shared.save");
            d1.engine().put(os);
            d1.put(os);
            d2.put(os);
        }
        final double kv11 = d1.call();
        final double kv21 = d2.call();
        final double kv12 = d1.call();
        final double kv22 = d2.call();
        r += d1.call() + d2.call();
        // A second capture will test non-cached if first tested cached case:
        {
            final COStream os = io.file("t2_shared.save");
            d1.engine().put(os);
            d1.put(os);
            d2.put(os);
        }
        final double kv13 = d1.call();
        final double kv23 = d2.call();
        final double kv14 = d1.call();
        final double kv24 = d2.call();
        int pr = output.precision();
        output.precision(20);
        say(output, "kv11 = ", kv11, "  kv21 = ", kv21, "\n");
        say(output, "kv12 = ", kv12, "  kv22 = ", kv22, "\n");
        say(output, "kv13 = ", kv13, "  kv23 = ", kv23, "\n");
        say(output, "kv14 = ", kv14, "  kv24 = ", kv24, "\n");
        output.precision(pr);
        final HepRandomEngine e = E.make().get();
        final HepRandom d1r = D1.apply(e);
        final HepRandom d2r = D2.apply(e);
        {
            final CIStream is = io.ifstream("t1_shared.save");
            e.get(is);
            d1r.get(is);
            d2r.get(is);
        }
        final double k11 = d1r.call();
        final double k21 = d2r.call();
        final double k12 = d1r.call();
        final double k22 = d2r.call();
        {
            final CIStream is = io.ifstream("t2_shared.save");
            e.get(is);
            d1r.get(is);
            d2r.get(is);
        }
        final double k13 = d1r.call();
        final double k23 = d2r.call();
        final double k14 = d1r.call();
        final double k24 = d2r.call();
        pr = output.precision();
        output.precision(20);
        say(output, "k11 = ", k11, "  k21 = ", k21, "\n");
        say(output, "k12 = ", k12, "  k22 = ", k22, "\n");
        say(output, "k13 = ", k13, "  k23 = ", k23, "\n");
        say(output, "k14 = ", k14, "  k24 = ", k24, "\n");
        output.precision(pr);
        if (k11 != kv11 || k21 != kv21 || k12 != kv12 || k22 != kv22 || k13 != kv13 || k23 != kv23 || k14 != kv14 || k24 != kv24) {
            say(io.cout, "???? Incorrect restored value for distributions ", d1.name(), " ", d2.name(), "\n");
            say(output, "???? Incorrect restored value for distributions ", d1.name(), " ", d2.name(), "\n");
            stat |= 4096;
        }
        return stat;
    }

    private static int checkSharing(Io io, COStream output, Engine E) {
        int stat = 0;
        final HepRandomEngine e1 = E.seeded().apply(98746);
        final RandGauss g1 = new RandGauss(e1, 50.0, 4.0);
        final RandPoissonQ p1 = new RandPoissonQ(e1, 112.0);
        final RandGauss g2 = new RandGauss(e1, 5.0, 44.0);
        final RandPoissonQ p2 = new RandPoissonQ(e1, 212.0);
        stat |= checkSharingDistributions(io, output, E, g1, p1, RandGauss::new, RandPoissonQ::new, 5, 4);
        stat |= checkSharingDistributions(io, output, E, g1, p2, RandGauss::new, RandPoissonQ::new, 6, 6);
        stat |= checkSharingDistributions(io, output, E, g2, p1, RandGauss::new, RandPoissonQ::new, 8, 9);
        stat |= checkSharingDistributions(io, output, E, g1, p1, RandGauss::new, RandPoissonQ::new, 7, 5);
        stat |= checkSharingDistributions(io, output, E, p1, g2, RandPoissonQ::new, RandGauss::new, 5, 4);
        stat |= checkSharingDistributions(io, output, E, p2, g1, RandPoissonQ::new, RandGauss::new, 6, 6);
        stat |= checkSharingDistributions(io, output, E, p1, g1, RandPoissonQ::new, RandGauss::new, 8, 9);
        stat |= checkSharingDistributions(io, output, E, p2, g1, RandPoissonQ::new, RandGauss::new, 7, 5);
        return stat;
    }

    static int testSaveSharedEngines(Io io) {
        HepRandom.createInstance();
        final COStream output = io.file("testSaveSharedEngines.cout");
        int stat = 0;
        output.put("\n=============================================\n");
        output.put("              Part IV \n");
        output.put("Check behavior when engines are shared \n");
        output.put("=============================================\n\n");
        for (Engine E : List.of(DUAL, HURD160, HURD288, JAMES, MTWIST, RANLUX64, RANLUX, RANSHI, TRIPLE)) {
            stat |= checkSharing(io, output, E);
        }
        return conclude(io, output, stat, "testSaveSharedEngines");
    }

    /* ---- testStaticStreamSave ---- */

    /** A distribution's static functions, as the templates call them: D::shoot(), D::saveFullState(os)... */
    private record Statics(String name, DoubleSupplier shoot, IntSupplier shootBit, UnaryOperator<COStream> saveFullState,
                           UnaryOperator<CIStream> restoreFullState, UnaryOperator<COStream> saveDistState,
                           UnaryOperator<CIStream> restoreDistState) {
    }

    private static final Statics GAUSS = new Statics(RandGauss.distributionName(), RandGauss::shoot, null,
        RandGauss::saveFullState, RandGauss::restoreFullState, RandGauss::saveDistState, RandGauss::restoreDistState);
    private static final Statics FLAT = new Statics(RandFlat.distributionName(), RandFlat::shoot, RandFlat::shootBit,
        RandFlat::saveFullState, RandFlat::restoreFullState, RandFlat::saveDistState, RandFlat::restoreDistState);
    private static final Statics BIT = new Statics(RandBit.distributionName(), RandBit::shoot, RandBit::shootBit,
        RandBit::saveFullState, RandBit::restoreFullState, RandBit::saveDistState, RandBit::restoreDistState);

    /** A distribution whose statics are HepRandom's but for shoot() and its name. */
    private static Statics plain(String name, DoubleSupplier shoot) {
        return new Statics(name, shoot, null, HepRandom::saveFullState, HepRandom::restoreFullState, HepRandom::saveDistState,
            HepRandom::restoreDistState);
    }

    private static void twenty(COStream output, String a, String x, String b, String y) {
        final int pr = output.precision();
        output.precision(20);
        say(output, a, x, b, y, "\n");
        output.precision(pr);
    }

    private static int staticSave(Io io, COStream output, Statics D, int n) {
        int stat = 0;
        int i;
        say(output, "staticSave for distribution ", D.name(), "\n");
        double r = 0;
        double v1;
        double v2;
        double k1;
        double k2;
        for (i = 0; i < n; i++) r += D.shoot().getAsDouble();
        {
            final COStream file = io.file("ss1_distribution.save");
            D.saveFullState().apply(file);
            v1 = D.shoot().getAsDouble();
            D.saveFullState().apply(file);
            v2 = D.shoot().getAsDouble();
            twenty(output, "v1 = ", v1, "  v2 = ", v2);
        }
        for (i = 0; i < n; i++) r += D.shoot().getAsDouble();
        {
            final CIStream file = io.ifstream("ss1_distribution.save");
            D.restoreFullState().apply(file);
            k1 = D.shoot().getAsDouble();
            for (i = 0; i < n; i++) r += D.shoot().getAsDouble();
            D.restoreFullState().apply(file);
            k2 = D.shoot().getAsDouble();
            twenty(output, "k1 = ", k1, "  k2 = ", k2);
        }
        if ((k1 != v1) || (k2 != v2)) {
            say(io.cout, "???? restoreFullState failed for ", D.name(), "\n");
            say(output, "???? restoreFullState failed for ", D.name(), "\n");
            stat |= 8192;
        }
        for (i = 0; i < n; i++) r += D.shoot().getAsDouble();
        {
            final COStream file = io.file("ss2_distribution.save");
            HepRandom.getTheEngine().put(D.saveDistState().apply(file));
            v1 = D.shoot().getAsDouble();
            HepRandom.getTheEngine().put(D.saveDistState().apply(file));
            v2 = D.shoot().getAsDouble();
            twenty(output, "v1 = ", v1, "  v2 = ", v2);
        }
        for (i = 0; i < n; i++) r += D.shoot().getAsDouble();
        {
            final CIStream file = io.ifstream("ss2_distribution.save");
            HepRandom.getTheEngine().get(D.restoreDistState().apply(file));
            k1 = D.shoot().getAsDouble();
            for (i = 0; i < n; i++) r += D.shoot().getAsDouble();
            HepRandom.getTheEngine().get(D.restoreDistState().apply(file));
            k2 = D.shoot().getAsDouble();
            twenty(output, "k1 = ", k1, "  k2 = ", k2);
        }
        if ((k1 != v1) || (k2 != v2)) {
            say(io.cout, "???? restoreDistState failed for ", D.name(), "\n");
            say(output, "???? restoreDistState failed for ", D.name(), "\n");
            stat |= 16384;
        }
        return stat;
    }

    /** 25 bits from D::shootBit(), the first the highest. */
    private static int bits25(Statics D) {
        int v = 0;
        for (int i = 0; i < 25; i++) {
            v <<= 1;
            v += D.shootBit().getAsInt();
        }
        return v;
    }

    private static int staticSaveShootBit(Io io, COStream output, Statics D, int n) {
        int stat = 0;
        int i;
        say(output, "staticSaveShootBit for ", D.name(), "\n");
        double r = 0;
        int bit = 0;
        int v1;
        int v2;
        int k1;
        int k2;
        for (i = 0; i < n; i++) r += D.shoot().getAsDouble();
        for (i = 0; i < n; i++) bit |= D.shootBit().getAsInt();
        {
            final COStream file = io.file("ss1_distribution.save");
            D.saveFullState().apply(file);
            v1 = bits25(D);
            for (i = 0; i < n; i++) bit |= D.shootBit().getAsInt();
            D.saveFullState().apply(file);
            v2 = bits25(D);
            twenty(output, "v1 = ", Integer.toHexString(v1), "  v2 = ", Integer.toHexString(v2));
        }
        for (i = 0; i < n; i++) r += D.shoot().getAsDouble();
        {
            final CIStream file = io.ifstream("ss1_distribution.save");
            D.restoreFullState().apply(file);
            k1 = bits25(D);
            for (i = 0; i < n; i++) r += D.shoot().getAsDouble();
            D.restoreFullState().apply(file);
            k2 = bits25(D);
            twenty(output, "k1 = ", Integer.toHexString(k1), "  k2 = ", Integer.toHexString(k2));
        }
        if ((k1 != v1) || (k2 != v2)) {
            io.cout.put("???? restoreFullState failed for D shootBit()\n");
            output.put("???? restoreFullState failed for D shootBit()\n");
            stat |= 32768;
        }
        for (i = 0; i < n; i++) r += D.shoot().getAsDouble();
        for (i = 0; i < n; i++) bit |= D.shootBit().getAsInt();
        {
            final COStream file = io.file("ss2_distribution.save");
            HepRandom.getTheEngine().put(D.saveDistState().apply(file));
            v1 = bits25(D);
            for (i = 0; i < n; i++) bit |= D.shootBit().getAsInt();
            HepRandom.getTheEngine().put(D.saveDistState().apply(file));
            v2 = bits25(D);
            twenty(output, "v1 = ", Integer.toHexString(v1), "  v2 = ", Integer.toHexString(v2));
        }
        for (i = 0; i < n; i++) r += D.shoot().getAsDouble();
        {
            final CIStream file = io.ifstream("ss2_distribution.save");
            HepRandom.getTheEngine().get(D.restoreDistState().apply(file));
            k1 = bits25(D);
            for (i = 0; i < n; i++) r += D.shoot().getAsDouble();
            for (i = 0; i < n; i++) r += D.shootBit().getAsInt();
            HepRandom.getTheEngine().get(D.restoreDistState().apply(file));
            k2 = bits25(D);
            twenty(output, "k1 = ", Integer.toHexString(k1), "  k2 = ", Integer.toHexString(k2));
        }
        if ((k1 != v1) || (k2 != v2)) {
            io.cout.put("???? restoreDistState failed for RnadFlat::shootBit()\n");
            output.put("???? restoreDistState failed for RnadFlat::shootBit()\n");
            stat |= 65536;
        }
        return stat;
    }

    /** randomizeStatics(n): n rounds of every static distribution. */
    private static void randomizeStatics(int n) {
        for (int i = 0; i < n; i++) {
            RandGauss.shoot();
            RandGaussQ.shoot();
            RandGaussT.shoot();
            RandFlat.shoot();
            RandBit.shoot();
            RandFlat.shootBit();
            RandBit.shootBit();
            RandPoisson.shoot();
            RandPoissonQ.shoot();
            RandPoissonT.shoot();
            RandBinomial.shoot();
            RandBreitWigner.shoot();
            RandChiSquare.shoot();
            RandExponential.shoot();
            RandGamma.shoot();
            RandLandau.shoot();
            RandSkewNormal.shoot();
            RandStudentT.shoot();
        }
    }

    /** captureStatics(): one number of every static distribution, 20 bits of the two that shoot bits. */
    private static double[] captureStatics() {
        final double[] c = new double[56];
        int k = 0;
        c[k++] = RandGauss.shoot();
        c[k++] = RandGaussQ.shoot();
        c[k++] = RandGaussT.shoot();
        c[k++] = RandFlat.shoot();
        c[k++] = RandBit.shoot();
        for (int i = 0; i < 20; i++) {
            c[k++] = RandFlat.shootBit();
            c[k++] = RandBit.shootBit();
        }
        c[k++] = RandPoisson.shoot();
        c[k++] = RandPoissonQ.shoot();
        c[k++] = RandPoissonT.shoot();
        c[k++] = RandBinomial.shoot();
        c[k++] = RandBreitWigner.shoot();
        c[k++] = RandChiSquare.shoot();
        c[k++] = RandExponential.shoot();
        c[k++] = RandGamma.shoot();
        c[k++] = RandLandau.shoot();
        c[k++] = RandSkewNormal.shoot();
        c[k++] = RandStudentT.shoot();
        return c;
    }

    /** saveStatics: from RandGeneral, the toughest test (meaningless as a static distribution). */
    private static void saveStatics(Io io, String filename) {
        RandGeneral.saveStaticRandomStates(io.file(filename));
    }

    private static void restoreStatics(Io io, String filename) {
        RandLandau.restoreStaticRandomStates(io.ifstream(filename));
    }

    /** The captured numbers compared: the failures reported with that bit in stat. */
    private static int compareStatics(Io io, COStream output, double[] c, double[] d, int bit) {
        int stat = 0;
        for (int iv = 0; iv < c.length; iv++) {
            if (c[iv] != d[iv]) {
                say(io.cout, "???? restoreStaticRandomStates failed at random ", iv, "\n");
                say(output, "???? restoreStaticRandomStates failed at random ", iv, "\n");
                stat |= bit;
            }
        }
        return stat;
    }

    static int testStaticStreamSave(Io io) {
        HepRandom.createInstance();
        final COStream output = io.file("testStaticStreamSave.cout");
        int stat = 0;
        output.put("\n=========================================\n");
        output.put("              Part V \n");
        output.put("Static Save/restore to/from streams \n");
        output.put("=========================================\n\n");
        stat |= staticSave(io, output, GAUSS, 7);
        stat |= staticSave(io, output, FLAT, 7);
        stat |= staticSaveShootBit(io, output, FLAT, 19);
        stat |= staticSaveShootBit(io, output, BIT, 23);
        for (int ibinom = 0; ibinom < 15; ibinom++) {
            stat |= staticSave(io, output, plain(RandBinomial.distributionName(), RandBinomial::shoot), 7 + 3 * ibinom);
        }
        stat |= staticSave(io, output, plain(RandBreitWigner.distributionName(), RandBreitWigner::shoot), 7);
        stat |= staticSave(io, output, plain(RandChiSquare.distributionName(), RandChiSquare::shoot), 7);
        stat |= staticSave(io, output, plain(RandExponential.distributionName(), RandExponential::shoot), 7);
        stat |= staticSave(io, output, plain(RandGamma.distributionName(), RandGamma::shoot), 7);
        stat |= staticSave(io, output, new Statics(RandGaussQ.distributionName(), RandGaussQ::shoot, null, RandGaussQ::saveFullState,
            RandGaussQ::restoreFullState, RandGaussQ::saveDistState, RandGaussQ::restoreDistState), 7);
        stat |= staticSave(io, output, new Statics(RandGaussT.distributionName(), RandGaussT::shoot, null, RandGaussT::saveFullState,
            RandGaussT::restoreFullState, RandGaussT::saveDistState, RandGaussT::restoreDistState), 7);
        stat |= staticSave(io, output, plain(RandLandau.distributionName(), RandLandau::shoot), 7);
        stat |= staticSave(io, output, plain(RandPoisson.distributionName(), () -> RandPoisson.shoot()), 7);
        stat |= staticSave(io, output, plain(RandPoissonQ.distributionName(), () -> RandPoissonQ.shoot()), 7);
        stat |= staticSave(io, output, plain(RandPoissonT.distributionName(), () -> RandPoissonT.shoot()), 7);
        stat |= staticSave(io, output, plain(RandSkewNormal.distributionName(), RandSkewNormal::shoot), 7);
        stat |= staticSave(io, output, plain(RandStudentT.distributionName(), RandStudentT::shoot), 7);
        output.put("\n==============================================\n");
        output.put("                 Part VI  \n");
        output.put("Save/restore all static states to/from streams \n");
        output.put("==============================================\n\n");
        randomizeStatics(15);
        saveStatics(io, "ss_distribution.save");
        output.put("Saved all static distributions\n");
        final double[] c = captureStatics();
        output.put("Captured output of all static distributions\n");
        randomizeStatics(11);
        output.put("Randomized all static distributions\n");
        restoreStatics(io, "ss_distribution.save");
        output.put("Restored all static distributions to saved state\n");
        final double[] d = captureStatics();
        output.put("Captured output of all static distributions\n");
        stat |= compareStatics(io, output, c, d, 131072);
        if ((stat & 131072) == 0) output.put("All captured output agrees with earlier values\n");
        return conclude(io, output, stat, "testStaticStreamSave");
    }

    /* ---- testInstanceRestore ---- */

    private static int checkEngineName(Io io, COStream output, Engine E, String name) {
        int stat = 0;
        say(output, E.name(), "\n");
        if (!E.name().equals(name)) {
            say(io.cout, "???? engineName mismatch for ", name, " <--> ", E.name(), "\n");
            say(output, "???? engineName mismatch for ", name, " <--> ", E.name(), "\n");
            stat |= 256;
        }
        final HepRandomEngine e = E.seeded().apply(123);
        if (!e.name().equals(name)) {
            say(io.cout, "???? name mismatch for ", name, " <--> ", e.name(), "\n");
            say(output, "???? name mismatch for ", name, " <--> ", e.name(), "\n");
            stat |= 256;
        }
        return stat;
    }

    private static int checkEngineInstanceSave(Io io, COStream output, Engine E, HepRandomEngine e) {
        int stat = 0;
        say(output, "checkEngineInstanceSave for ", e.name(), "\n");
        final int pr = output.precision();
        output.precision(20);
        double r = 0;
        for (int i = 0; i < 100; i++) r += e.flat();
        e.put(io.file("instance_engine.save"));
        for (int i = 0; i < 100; i++) r += e.flat();
        final double keyValue1 = e.flat();
        final double keyValue2 = e.flat();
        say(output, keyValue1, " ", keyValue2, "\n");
        final HepRandomEngine e2 = E.make().get();
        e2.get(io.ifstream("instance_engine.save"));
        for (int i = 0; i < 100; i++) r += e2.flat();
        final double k1 = e2.flat();
        final double k2 = e2.flat();
        say(output, k1, " ", k2, "\n");
        if (k1 != keyValue1 || k2 != keyValue2) {
            say(io.cout, "???? checkInstanceSave failed for ", e.name(), "\n");
            say(output, "???? checkInstanceSave failed for ", e.name(), "\n");
            stat |= 1024;
        }
        output.precision(pr);
        return stat;
    }

    /** checkSaveDistribution and checkRandGeneralDistribution: D makes the distribution restored into. */
    private static int checkSaveDistribution(Io io, COStream output, Engine E, HepRandom d, int nth,
                                             Function<HepRandomEngine, HepRandom> D) {
        int stat = 0;
        say(output, "checkSaveDistribution with ", d.engine().name(), ", ", d.name(), "\n");
        double r = 0;
        r = d.call();
        for (int i = 0; i < nth; i++) r += d.call();
        {
            final COStream os = io.file("instance_distribution.save");
            d.engine().put(os);
            d.put(os);
        }
        final double keyValue1 = d.call();
        final double keyValue2 = d.call();
        r += d.call();
        // A second capture will test non-cached if first tested cached case:
        {
            final COStream os = io.file("instance2_distribution.save");
            d.engine().put(os);
            d.put(os);
        }
        final double keyValue3 = d.call();
        final double keyValue4 = d.call();
        int pr = output.precision();
        output.precision(20);
        say(output, "keyValue1 = ", keyValue1, "  keyValue2 = ", keyValue2, "\n");
        say(output, "keyValue3 = ", keyValue3, "  keyValue3 = ", keyValue4, "\n");
        output.precision(pr);
        final HepRandomEngine e = E.make().get();
        final HepRandom d2 = D.apply(e);
        {
            final CIStream is = io.ifstream("instance_distribution.save");
            e.get(is);
            d2.get(is);
        }
        final double k1 = d2.call();
        final double k2 = d2.call();
        {
            final CIStream is = io.ifstream("instance2_distribution.save");
            e.get(is);
            d2.get(is);
        }
        final double k3 = d2.call();
        final double k4 = d2.call();
        pr = output.precision();
        output.precision(20);
        say(output, "k1 =        ", k1, "  k2 =        ", k2, "\n");
        say(output, "k3 =        ", k3, "  k4 =        ", k4, "\n");
        output.precision(pr);
        if (k1 != keyValue1 || k2 != keyValue2 || k3 != keyValue3 || k4 != keyValue4) {
            say(io.cout, "???? Incorrect restored value for distribution ", d.name(), "\n");
            say(output, "???? Incorrect restored value for distribution ", d.name(), "\n");
            stat |= 2048;
        }
        return stat;
    }

    private static int checkDistributions(Io io, COStream output, Engine E) {
        int stat = 0;
        final LongFunction<HepRandomEngine> e = E.seeded();
        stat |= checkSaveDistribution(io, output, E, new RandGauss(e.apply(12561), 100.0, 3.0), 33, RandGauss::new);
        stat |= checkSaveDistribution(io, output, E, new RandGauss(e.apply(12572), 100.0, 3.0), 34, RandGauss::new);
        stat |= checkSaveDistribution(io, output, E, new RandGaussQ(e.apply(12563), 10.0, 4.0), 33, RandGaussQ::new);
        stat |= checkSaveDistribution(io, output, E, new RandGaussT(e.apply(12564), 5.0, 2.0), 33, RandGaussT::new);
        stat |= checkSaveDistribution(io, output, E, new RandBinomial(e.apply(12565), 4, 0.6), 33, RandBinomial::new);
        stat |= checkSaveDistribution(io, output, E, new RandFlat(e.apply(12576), 12.5, 35.0), 33, RandFlat::new);
        stat |= checkSaveDistribution(io, output, E, new RandBit(e.apply(12567)), 31, RandBit::new);
        stat |= checkSaveDistribution(io, output, E, new RandBit(e.apply(12578)), 32, RandBit::new);
        stat |= checkSaveDistribution(io, output, E, new RandBit(e.apply(12589)), 33, RandBit::new);
        stat |= checkSaveDistribution(io, output, E, new RandBreitWigner(e.apply(125611), 50.0, 15.0), 33, RandBreitWigner::new);
        stat |= checkSaveDistribution(io, output, E, new RandChiSquare(e.apply(125612), 5.0), 33, RandChiSquare::new);
        stat |= checkSaveDistribution(io, output, E, new RandExponential(e.apply(125713), 8.00), 33, RandExponential::new);
        stat |= checkSaveDistribution(io, output, E, new RandGamma(e.apply(125713), 6.0, 2.0), 33, RandGamma::new);
        stat |= checkSaveDistribution(io, output, E, new RandLandau(e.apply(125714)), 33, RandLandau::new);
        stat |= checkSaveDistribution(io, output, E, new RandStudentT(e.apply(125715), 5), 33, RandStudentT::new);
        // Multiple tests of Poisson distributions for small desired, since the answer in each test is a small
        // int, and coincidental agreement is very possible.
        stat |= checkSaveDistribution(io, output, E, new RandPoisson(e.apply(125616), 2.5), 33, RandPoisson::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoisson(e.apply(125617), 105.0), 34, RandPoisson::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoisson(e.apply(125618), 2.5), 35, RandPoisson::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoisson(e.apply(325618), 2.5), 36, RandPoisson::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoisson(e.apply(425618), 2.5), 37, RandPoisson::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoisson(e.apply(525618), 2.5), 38, RandPoisson::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoisson(e.apply(125619), 110.0), 39, RandPoisson::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonQ(e.apply(124616), 2.5), 33, RandPoissonQ::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonQ(e.apply(126616), 2.5), 32, RandPoissonQ::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonQ(e.apply(127616), 2.5), 31, RandPoissonQ::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonQ(e.apply(129616), 2.5), 30, RandPoissonQ::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonQ(e.apply(125616), 110.0), 33, RandPoissonQ::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonQ(e.apply(125616), 2.5), 34, RandPoissonQ::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonQ(e.apply(125616), 110.0), 34, RandPoissonQ::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonT(e.apply(125616), 2.5), 33, RandPoissonT::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonT(e.apply(125616), 110.0), 33, RandPoissonT::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonT(e.apply(125616), 2.5), 34, RandPoissonT::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonT(e.apply(125616), 110.0), 34, RandPoissonT::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonT(e.apply(125916), 2.5), 10, RandPoissonT::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonT(e.apply(125816), 2.5), 11, RandPoissonT::new);
        stat |= checkSaveDistribution(io, output, E, new RandPoissonT(e.apply(125716), 2.5), 12, RandPoissonT::new);
        final int nbins = 20;
        final double[] pdf = new double[nbins];
        for (int i = 0; i < nbins; ++i) pdf[i] = 5 * i + (10.5 - i) * (10.5 - i);
        stat |= checkSaveDistribution(io, output, E, new RandGeneral(e.apply(125917), pdf, 20), 33,
            en -> new RandGeneral(en, new double[] {1}, 1));
        return stat;
    }

    /** Compared with Linux's outputs: MinGW's runs on (its 32-bit long breaks the heap). */
    static int testInstanceRestore(Io io) {
        HepRandom.createInstance();
        final COStream output = io.file("testInstanceRestore.cout");
        int stat = 0;
        output.put("\n=============================================\n");
        output.put("              Part II \n");
        output.put("Check all engine names were entered correctly \n");
        output.put("=============================================\n\n");
        stat |= checkEngineName(io, output, DRAND48, "DRand48Engine");
        stat |= checkEngineName(io, output, DUAL, "DualRand");
        stat |= checkEngineName(io, output, HURD160, "Hurd160Engine");
        stat |= checkEngineName(io, output, HURD288, "Hurd288Engine");
        stat |= checkEngineName(io, output, JAMES, "HepJamesRandom");
        stat |= checkEngineName(io, output, MIXMAX, "MixMaxRng");
        stat |= checkEngineName(io, output, MTWIST, "MTwistEngine");
        stat |= checkEngineName(io, output, RAND, "RandEngine");
        stat |= checkEngineName(io, output, RANECU, "RanecuEngine");
        stat |= checkEngineName(io, output, RANLUX64, "Ranlux64Engine");
        stat |= checkEngineName(io, output, RANLUX, "RanluxEngine");
        stat |= checkEngineName(io, output, RANLUXPP, "RanluxppEngine");
        stat |= checkEngineName(io, output, RANSHI, "RanshiEngine");
        stat |= checkEngineName(io, output, TRIPLE, "TripleRand");
        output.put("===========================================\n\n");
        output.put("              Part III\n");
        output.put("Check instance methods for specific engines \n");
        output.put("     specific engines and distributions\n");
        output.put("===========================================\n\n");
        for (Engine E : List.of(DUAL, HURD160, HURD288, JAMES, MIXMAX, MTWIST, RAND, RANECU, RANLUX64, RANLUX, RANLUXPP, RANSHI, TRIPLE)) {
            stat |= checkEngineInstanceSave(io, output, E, E.seeded().apply(234));
        }
        {
            final double[] nonRand = aSequence(500);
            final NonRandomEngine e = new NonRandomEngine();
            e.setRandomSequence(nonRand, nonRand.length);
            stat |= checkEngineInstanceSave(io, output, NONRANDOM, e);
        }
        for (Engine E : List.of(DUAL, HURD160, HURD288, JAMES, MIXMAX, MTWIST, RANLUX64, RANLUX, RANLUXPP, RANSHI, TRIPLE)) {
            stat |= checkDistributions(io, output, E);
        }
        RandGaussQ.shoot(); // Just to verify that the static engine is OK
        return conclude(io, output, stat, "testInstanceRestore");
    }

    /* ---- testAnonymousEngineRestore ---- */

    private static void firstFour(COStream output, String which, List<Double> v) {
        say(output, "First four of ", which, " are: ", v.get(0), ",  ", v.get(1), ",  ", v.get(2), ",  ", v.get(3), "\n");
    }

    private static void anonymousRestore1(Io io, COStream output, Engine E, int n, List<Double> v) {
        say(output, "Anonymous restore for ", E.name(), "\n");
        final HepRandomEngine e;
        if (E == NONRANDOM) {
            final double[] nonRand = aSequence(500);
            final NonRandomEngine ne = new NonRandomEngine();
            ne.setRandomSequence(nonRand, nonRand.length);
            e = ne;
        } else {
            e = E.seeded().apply(12349876);
        }
        double r = 0;
        for (int i = 0; i < n; i++) r += e.flat();
        e.put(io.file("anonymous.save"));
        for (int j = 0; j < 25; j++) v.add(e.flat());
        firstFour(output, "v", v);
    }

    private static int anonymousRestore2(Io io, COStream output, Engine E, List<Double> v) {
        int stat = 0;
        final List<Double> k = new ArrayList<>();
        final HepRandomEngine a = HepRandomEngine.newEngine(io.ifstream("anonymous.save"));
        for (int j = 0; j < 25; j++) k.add(a.flat());
        firstFour(output, "k", k);
        for (int m1 = 0; m1 < 25; m1++) {
            if (v.get(m1).doubleValue() != k.get(m1).doubleValue()) {
                say(io.cout, "???? Incorrect restored value for anonymous engine", E.name(), "\n");
                say(output, "???? Incorrect restored value for anonymous engine", E.name(), "\n");
                stat |= 262144;
                return stat;
            }
        }
        return stat;
    }

    private static int anonymousRestore(Io io, COStream output, Engine E, int n) {
        final List<Double> v = new ArrayList<>();
        anonymousRestore1(io, output, E, n, v);
        return anonymousRestore2(io, output, E, v);
    }

    private static int anonymousRestoreStatics1(Io io, COStream output, Engine E) {
        int stat = 0;
        final HepRandomEngine e = E.seeded().apply(12456);
        HepRandom.setTheEngine(e);
        randomizeStatics(15);
        say(output, "\nRandomized, with theEngine = ", e.name(), "\n");
        saveStatics(io, "anon_distribution.save");
        output.put("Saved all static distributions\n");
        final double[] c = captureStatics();
        output.put("Captured output of all static distributions\n");
        randomizeStatics(11);
        output.put("Randomized all static distributions\n");
        restoreStatics(io, "anon_distribution.save");
        output.put("Restored all static distributions to saved state\n");
        final double[] d = captureStatics();
        output.put("Captured output of all static distributions\n");
        stat |= compareStatics(io, output, c, d, 131072);
        if ((stat & 131072) == 0) output.put("All captured output agrees with earlier values\n");
        return stat;
    }

    private static int anonymousRestoreStatics(Io io, COStream output, Engine E1, Engine E2) {
        int stat = 0;
        if (E1.name().equals(E2.name())) return anonymousRestoreStatics1(io, output, E1);
        final HepRandomEngine e1 = E1.seeded().apply(12456);
        HepRandom.setTheEngine(e1);
        randomizeStatics(15);
        say(output, "\nRandomized, with theEngine = ", e1.name(), "\n");
        saveStatics(io, "anon_distribution.save");
        output.put("Saved all static distributions\n");
        final double[] c = captureStatics();
        output.put("Captured output of all static distributions\n");
        final HepRandomEngine e2 = E2.seeded().apply(24653);
        HepRandom.setTheEngine(e2);
        say(output, "Switched to theEngine = ", e2.name(), "\n");
        randomizeStatics(19);
        e2.put(io.file("anon_engine.save"));
        final double v1 = e2.flat();
        final double v2 = e2.flat();
        e2.get(io.ifstream("anon_engine.save"));
        say(output, "Saved the ", e2.name(), " engine: \n", "Next randoms to be ", v1, " ", v2, "\n", "Restored the ", e2.name(),
            " engine to that state\n");
        restoreStatics(io, "anon_distribution.save");
        say(output, "Restored all static distributions to saved state\n", "This changes the engine type back to ", E1.name(), "\n");
        final double[] d = captureStatics();
        output.put("Captured output of all static distributions\n");
        stat |= compareStatics(io, output, c, d, 524288);
        if ((stat & 524288) == 0) output.put("All captured output agrees with earlier values\n");
        final double k1 = e2.flat();
        final double k2 = e2.flat();
        say(output, "The ", e2.name(), " engine should not have been affected: \n", "Next randoms  are  ", k1, " ", k2, "\n");
        if (v1 != k1 || v2 != k2) {
            for (COStream os : new COStream[] {io.cout, output}) {
                os.put("???? Engine used as theEngine was affected by restoring \n");
                os.put("     static distributions to use engine of a different type.\n");
            }
            stat |= 1048576;
        }
        return stat;
    }

    static int testAnonymousEngineRestore(Io io) {
        HepRandom.createInstance();
        final COStream output = io.file("testAnonymousEngineRestore.cout");
        int stat = 0;
        output.put("\n=================================\n");
        output.put("         Part VII \n");
        output.put("Anonymous restore of engines \n");
        output.put("=================================\n\n");
        stat |= anonymousRestore(io, output, DUAL, 13);
        stat |= anonymousRestore(io, output, DRAND48, 14);
        stat |= anonymousRestore(io, output, HURD160, 15);
        stat |= anonymousRestore(io, output, HURD288, 16);
        stat |= anonymousRestore(io, output, JAMES, 17);
        stat |= anonymousRestore(io, output, MIXMAX, 49);
        stat |= anonymousRestore(io, output, MTWIST, 18);
        stat |= anonymousRestore(io, output, RAND, 29);
        stat |= anonymousRestore(io, output, RANECU, 39);
        stat |= anonymousRestore(io, output, RANLUX64, 19);
        stat |= anonymousRestore(io, output, RANLUX, 20);
        stat |= anonymousRestore(io, output, RANLUXPP, 59);
        stat |= anonymousRestore(io, output, RANSHI, 21);
        stat |= anonymousRestore(io, output, TRIPLE, 22);
        stat |= anonymousRestore(io, output, NONRANDOM, 22);
        output.put("\n======================================\n");
        output.put("             Part VIII \n");
        output.put("Anonymous restore static Distributions \n");
        output.put("======================================\n\n");
        stat |= anonymousRestoreStatics(io, output, DUAL, RANLUX64);
        stat |= anonymousRestoreStatics(io, output, DRAND48, TRIPLE);
        stat |= anonymousRestoreStatics(io, output, RAND, RANLUX64);
        stat |= anonymousRestoreStatics(io, output, MTWIST, HURD288);
        stat |= anonymousRestoreStatics(io, output, RANECU, MTWIST);
        stat |= anonymousRestoreStatics(io, output, JAMES, RANSHI);
        stat |= anonymousRestoreStatics(io, output, RANECU, RAND);
        stat |= anonymousRestoreStatics(io, output, RANSHI, HURD160);
        stat |= anonymousRestoreStatics(io, output, TRIPLE, DUAL);
        stat |= anonymousRestoreStatics(io, output, HURD160, JAMES);
        stat |= anonymousRestoreStatics(io, output, HURD288, RANECU);
        stat |= anonymousRestoreStatics(io, output, JAMES, RANLUX64);
        stat |= anonymousRestoreStatics(io, output, JAMES, RANLUXPP);
        stat |= anonymousRestoreStatics(io, output, TRIPLE, TRIPLE);
        stat |= anonymousRestoreStatics(io, output, JAMES, JAMES);
        stat |= anonymousRestoreStatics(io, output, MIXMAX, MIXMAX);
        return conclude(io, output, stat, "testAnonymousEngineRestore");
    }

    /* ---- testVectorSave ---- */

    /** The NonRandomEngine the specializations make: aSequence(500) as its sequence. */
    private static NonRandomEngine nonRandom(double[] nonRand) {
        final NonRandomEngine e = new NonRandomEngine();
        e.setRandomSequence(nonRand, nonRand.length);
        return e;
    }

    /** vectorTest64: the problem of bug #44156, the engine's ID cut to 32 bits (which a 32-bit machine always passes). */
    private static int vectorTest64(COStream output, Engine E, int n) {
        say(output, "Vector restore 64bit test for ", E.name(), "\n");
        final double[] nonRand = E == NONRANDOM ? aSequence(500) : null;
        final HepRandomEngine e = E == NONRANDOM ? nonRandom(nonRand) : E.make().get();
        double x = 0;
        for (int i = 0; i < n; i++) x += e.flat();
        final long[] v = e.put();
        x = e.flat();
        say(output, "x = ", x, "\n");
        final HepRandomEngine f = E == NONRANDOM ? nonRandom(nonRand) : E.make().get();
        v[0] &= 0xffffffffL;
        f.get(v);
        final double y = f.flat();
        say(output, "y = ", y, "\n");
        if (x != y) return n;
        return 0;
    }

    private static long[] vectorRestore1(COStream output, Engine E, int n, List<Double> v) {
        say(output, "Vector restore for ", E.name(), "\n");
        final HepRandomEngine e = E == NONRANDOM ? nonRandom(aSequence(500)) : E.seeded().apply(97538466);
        double r = 0;
        for (int i = 0; i < n; i++) r += e.flat();
        final long[] state = e.put();
        for (int j = 0; j < 25; j++) v.add(e.flat());
        firstFour(output, "v", v);
        return state;
    }

    private static int vectorRestore2(Io io, COStream output, Engine E, long[] state, List<Double> v) {
        int stat = 0;
        final List<Double> k = new ArrayList<>();
        final HepRandomEngine a = HepRandomEngine.newEngine(state);
        if (a == null) {
            say(io.cout, "???? could not restore engine state from vector for ", E.name(), "\n");
            say(output, "???? could not restore engine state from vector for ", E.name(), "\n");
            stat |= 1048576;
            return stat;
        }
        if (!a.name().equals(E.name())) {
            for (COStream os : new COStream[] {io.cout, output}) {
                say(os, "???? restored engine state from vector for ", E.name(), "to different type of engine: ", a.name(), "\n",
                    "There is probably a clash in CRC hashes for these two names!\n");
            }
            stat |= 1048576;
            return stat;
        }
        for (int j = 0; j < 25; j++) k.add(a.flat());
        firstFour(output, "k", k);
        for (int m = 0; m < 25; m++) {
            if (v.get(m).doubleValue() != k.get(m).doubleValue()) {
                say(io.cout, "???? Incorrect vector restored value for anonymous engine: ", E.name(), "\n");
                say(output, "???? Incorrect vector restored value for anonymous engine: ", E.name(), "\n");
                stat |= 1048576;
                return stat;
            }
        }
        return stat;
    }

    private static int vectorRestore(Io io, COStream output, Engine E, int n) {
        final List<Double> v = new ArrayList<>();
        final int status1 = vectorTest64(output, E, n);
        final long[] state = vectorRestore1(output, E, n, v);
        final int status2 = vectorRestore2(io, output, E, state, v);
        return status1 | status2;
    }

    /** Compared with Linux's outputs: on MinGW the default RanshiEngine depends on a 32-bit long. */
    static int testVectorSave(Io io) {
        HepRandom.createInstance();
        final COStream output = io.file("testVectorSave.cout");
        int stat = 0;
        output.put("\n=================================\n");
        output.put("         Part IX \n");
        output.put("Save/restore of engines to vectors\n");
        output.put("=================================\n\n");
        stat |= vectorRestore(io, output, DUAL, 113);
        stat |= vectorRestore(io, output, DRAND48, 114);
        stat |= vectorRestore(io, output, HURD160, 115);
        stat |= vectorRestore(io, output, HURD288, 116);
        stat |= vectorRestore(io, output, JAMES, 117);
        stat |= vectorRestore(io, output, MTWIST, 118);
        stat |= vectorRestore(io, output, RANECU, 139);
        stat |= vectorRestore(io, output, RANLUX64, 119);
        stat |= vectorRestore(io, output, RANLUX, 120);
        stat |= vectorRestore(io, output, RANSHI, 121);
        stat |= vectorRestore(io, output, TRIPLE, 122);
        stat |= vectorRestore(io, output, NONRANDOM, 123);
        stat |= vectorRestore(io, output, RAND, 129);
        return conclude(io, output, stat, "ranRestoreTest");
    }

    /* ---- testEngineCopy ---- */

    private static int copyTest64(COStream output, Engine E, int n) {
        say(output, "Copy 64bit test for ", E.name(), "\n");
        if (E == NONRANDOM) {
            final double[] nonRand = aSequence(500);
            final NonRandomEngine e = nonRandom(nonRand);
            double x = 0;
            for (int i = 0; i < n; i++) x += e.flat();
            e.put();
            final NonRandomEngine f = new NonRandomEngine(e);
            x = e.flat();
            say(output, "x = ", x, "\n");
            final double y = f.flat();
            say(output, "y = ", y, "\n");
            if (x != y) return n;
            for (int i = 0; i < 300; ++i) {
                if (e.flat() != f.flat()) {
                    say(output, "i = ", i, " vectorTest64 for NonRandomEngine problem: e != f \n");
                    return n + i;
                }
            }
            return 0;
        }
        final HepRandomEngine e = E.make().get();
        double x = 0;
        for (int i = 0; i < n; i++) x += e.flat();
        final HepRandomEngine f = E.copy().apply(e);
        x = e.flat();
        say(output, "x = ", x, "\n");
        double y = f.flat();
        say(output, "y = ", y, "\n");
        if (x != y) return n;
        for (int i = 0; i < 1000; ++i) {
            x = e.flat();
            y = f.flat();
            if (x != y) {
                say(output, "i = ", i, " x, y ", x, " ", y, " vectorTest64 problem: e != f \n");
                return n + i;
            }
        }
        return 0;
    }

    private static HepRandomEngine copyRestore1(COStream output, Engine E, int n, List<Double> v) {
        say(output, "Copy for ", E.name(), "\n");
        final HepRandomEngine e = E == NONRANDOM ? nonRandom(aSequence(500)) : E.seeded().apply(97538466);
        double r = 0;
        for (int i = 0; i < n; i++) r += e.flat();
        final HepRandomEngine f = E.copy().apply(e);
        for (int j = 0; j < 25; j++) v.add(e.flat());
        firstFour(output, "v", v);
        return f;
    }

    private static int copyRestore2(Io io, COStream output, Engine E, HepRandomEngine f, List<Double> v) {
        int stat = 0;
        final List<Double> k = new ArrayList<>();
        for (int j = 0; j < 25; j++) k.add(f.flat());
        firstFour(output, "k", k);
        for (int m1 = 0; m1 < 25; m1++) {
            if (v.get(m1).doubleValue() != k.get(m1).doubleValue()) {
                say(io.cout, "???? Incorrect copy restored value for engine: ", E.name(), "\n");
                say(output, "???? Incorrect copy restored value for engine: ", E.name(), "\n");
                stat |= 1048576;
                return stat;
            }
        }
        return stat;
    }

    private static int copyRestore(Io io, COStream output, Engine E, int n) {
        final List<Double> v = new ArrayList<>();
        final int status1 = copyTest64(output, E, n);
        final HepRandomEngine f = copyRestore1(output, E, n, v);
        final int status2 = copyRestore2(io, output, E, f, v);
        return status1 | status2;
    }

    /** Compared with Linux's outputs: on MinGW the default RanshiEngine depends on a 32-bit long. */
    static int testEngineCopy(Io io) {
        HepRandom.createInstance();
        final COStream output = io.file("testEngineCopy.cout");
        int stat = 0;
        output.put("\n=================================\n");
        output.put("         Part IX \n");
        output.put("    Copy test of engines\n");
        output.put("=================================\n\n");
        stat |= copyRestore(io, output, DUAL, 113);
        // copies of DRand48Engine are not allowed
        stat |= copyRestore(io, output, HURD160, 115);
        stat |= copyRestore(io, output, HURD288, 116);
        stat |= copyRestore(io, output, JAMES, 117);
        stat |= copyRestore(io, output, MIXMAX, 149);
        stat |= copyRestore(io, output, MTWIST, 118);
        stat |= copyRestore(io, output, RANECU, 139);
        stat |= copyRestore(io, output, RANLUX64, 119);
        stat |= copyRestore(io, output, RANLUX, 120);
        stat |= copyRestore(io, output, RANLUXPP, 119);
        stat |= copyRestore(io, output, RANSHI, 121);
        stat |= copyRestore(io, output, TRIPLE, 122);
        stat |= copyRestore(io, output, NONRANDOM, 123);
        // anonymous engines are not copyable
        return conclude(io, output, stat, "ranRestoreTest");
    }

    /* ---- testRandDists ---- */

    // Tolerance of deviation from expected results
    private static final double REJECT = 4.0;

    // Mask bits to form a word indicating which if any dists were "bad"
    private static final int GAUSS_BAD = 1 << 0;
    private static final int GENERAL_BAD = 1 << 1;
    private static final int POISSON_BAD = 1 << 2;
    private static final int GAUSSQ_BAD = 1 << 3;
    private static final int GAUSST_BAD = 1 << 4;
    private static final int POISSONQ_BAD = 1 << 5;
    private static final int POISSONT_BAD = 1 << 6;
    private static final int SKEW_NORMAL_BAD = 1 << 7;

    private static final double[] GAMMLN_C = {76.18009172947146, -86.50532032941677, 24.01409824083091, -1.231739572450155,
        0.001208650973866179, -0.000005395239384953};

    /** The test's own gammln (Numerical Recipes'), not RandPoisson's. */
    private static double gammln(double x) {
        double y;
        double tmp;
        double ser;
        y = x;
        tmp = x + 5.5;
        tmp -= (x + .5) * CMath.log(tmp);
        ser = 1.000000000190015;
        for (int i = 0; i < 6; i++) ser += GAMMLN_C[i] / (++y);
        return -tmp + CMath.log(Math.sqrt(twopi) * ser / x);
    }

    private static double gser(COStream cout, double a, double x) {
        final int ITMAX = 100;
        final double EPS = 1.0E-8;
        double ap = a;
        double sum = 1 / a;
        double del = sum;
        for (int n = 0; n < ITMAX; n++) {
            ap++;
            del *= x / ap;
            sum += del;
            if (Math.abs(del) < Math.abs(sum) * EPS) return sum * CMath.exp(-x + a * CMath.log(x) - gammln(a));
        }
        say(cout, "Problem - inaccurate gser ", a, ", ", x, "\n");
        return sum * CMath.exp(-x + a * CMath.log(x) - gammln(a));
    }

    private static double gcf(COStream cout, double a, double x) {
        final int ITMAX = 100;
        final double EPS = 1.0E-8;
        final double VERYSMALL = 1.0E-100;
        double b = x + 1 - a;
        double c = 1 / VERYSMALL;
        double d = 1 / b;
        double h = d;
        for (int i = 1; i <= ITMAX; i++) {
            final double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            if (Math.abs(d) < VERYSMALL) d = VERYSMALL;
            c = b + an / c;
            if (Math.abs(c) < VERYSMALL) c = VERYSMALL;
            d = 1 / d;
            final double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < EPS) return CMath.exp(-x + a * CMath.log(x) - gammln(a)) * h;
        }
        say(cout, "Problem - inaccurate gcf ", a, ", ", x, "\n");
        return CMath.exp(-x + a * CMath.log(x) - gammln(a)) * h;
    }

    private static double gammp(COStream cout, double a, double x) {
        if (x < a + 1) return gser(cout, a, x);
        return 1 - gcf(cout, a, x);
    }

    // Table of integrated density in each range: 0.0 - 0.5 sigma, 0.5 - 1.0 sigma... 5.0 sigma and up
    private static final double[] GAUSS_TABLE = {.191462, .149882, .091848, .044057, .016540, .004860, .001117, .000201, 2.83E-5,
        3.11E-6, 3.87E-7};

    /** Mean and moments up to the sixth, and a histogram with bins every half sigma. */
    private static boolean gaussianTest(COStream cout, HepRandom dist, double mu, double sigma, int nNumbers) {
        boolean good = true;
        double worstSigma = 0;
        double sumx = 0;
        double sumx2 = 0;
        double sumx3 = 0;
        double sumx4 = 0;
        double sumx5 = 0;
        double sumx6 = 0;
        final int[] counts = new int[11];
        final int[] ncounts = new int[11];
        int ciu;
        final int oldprecision = cout.precision();
        cout.precision(5);
        double x;
        double u;
        final int ipr = nNumbers / 10 + 1;
        for (int ifire = 0; ifire < nNumbers; ifire++) {
            x = dist.call(); // operator(): fire() is not virtual in HepRandom
            if (x < mu - 12.0 * sigma) say(cout, "x  = ", x, "\n");
            if ((ifire % ipr) == 0) say(cout, ifire, "\n");
            sumx += x;
            sumx2 += x * x;
            sumx3 += x * x * x;
            sumx4 += x * x * x * x;
            sumx5 += x * x * x * x * x;
            sumx6 += x * x * x * x * x * x;
            u = (x - mu) / sigma;
            if (u >= 0) {
                ciu = (int) (2 * u);
                if (ciu > 10) ciu = 10;
                counts[ciu]++;
            } else {
                ciu = (int) (2 * (-u));
                if (ciu > 10) ciu = 10;
                ncounts[ciu]++;
            }
        }
        final double mean = sumx / nNumbers;
        final double u2 = sumx2 / nNumbers - mean * mean;
        final double u3 = sumx3 / nNumbers - 3 * sumx2 * mean / nNumbers + 2 * mean * mean * mean;
        final double u4 = sumx4 / nNumbers - 4 * sumx3 * mean / nNumbers + 6 * sumx2 * mean * mean / nNumbers
            - 3 * mean * mean * mean * mean;
        final double u5 = sumx5 / nNumbers - 5 * sumx4 * mean / nNumbers + 10 * sumx3 * mean * mean / nNumbers
            - 10 * sumx2 * mean * mean * mean / nNumbers + 4 * mean * mean * mean * mean * mean;
        final double u6 = sumx6 / nNumbers - 6 * sumx5 * mean / nNumbers + 15 * sumx4 * mean * mean / nNumbers
            - 20 * sumx3 * mean * mean * mean / nNumbers + 15 * sumx2 * mean * mean * mean * mean / nNumbers
            - 5 * mean * mean * mean * mean * mean * mean;
        say(cout, "Mean (should be close to ", mu, "): ", mean, "\n");
        say(cout, "Second moment (should be close to ", sigma * sigma, "): ", u2, "\n");
        say(cout, "Third  moment (should be close to zero): ", u3, "\n");
        say(cout, "Fourth moment (should be close to ", 3 * sigma * sigma * sigma * sigma, "): ", u4, "\n");
        say(cout, "Fifth moment (should be close to zero): ", u5, "\n");
        say(cout, "Sixth moment (should be close to ", 15 * sigma * sigma * sigma * sigma * sigma * sigma, "): ", u6, "\n");
        // For large N, the variance squared in the scaled 2nd, 3rd 4th 5th and 6th moments are roughly 2/N, 6/N,
        // 96/N, 720/N and 10170/N respectively.
        final double del1 = Math.sqrt((double) nNumbers) * Math.abs(mean - mu) / sigma;
        final double del2 = Math.sqrt(nNumbers / 2.0) * Math.abs(u2 - sigma * sigma) / (sigma * sigma);
        final double del3 = Math.sqrt(nNumbers / 6.0) * Math.abs(u3) / (sigma * sigma * sigma);
        final double sigma4 = sigma * sigma * sigma * sigma;
        final double del4 = Math.sqrt(nNumbers / 96.0) * Math.abs(u4 - 3 * sigma4) / sigma4;
        final double del5 = Math.sqrt(nNumbers / 720.0) * Math.abs(u5) / (sigma * sigma4);
        final double del6 = Math.sqrt(nNumbers / 10170.0) * Math.abs(u6 - 15 * sigma4 * sigma * sigma) / (sigma4 * sigma * sigma);
        say(cout, "        These represent ", del1, ", ", del2, ", ", del3, ", \n", "                        ", del4, ", ", del5,
            ", ", del6, "\n                         standard deviations from expectations\n");
        for (double del : new double[] {del1, del2, del3, del4, del5, del6}) {
            if (del > worstSigma) worstSigma = del;
        }
        if (del1 > REJECT || del2 > REJECT || del3 > REJECT || del4 > REJECT || del5 > REJECT || del6 > REJECT) {
            cout.put("REJECT hypothesis that this distribution is correct!!\n");
            good = false;
        }
        // The variance of the bin counts is given by a Poisson estimate (std::sqrt(npq)).
        for (int m1 = 0; m1 < 11; m1++) {
            final double expect = GAUSS_TABLE[m1] * nNumbers;
            final double sig = Math.sqrt(GAUSS_TABLE[m1] * (1.0 - GAUSS_TABLE[m1]) * nNumbers);
            cout.precision(oldprecision);
            say(cout, "Between ", m1 / 2.0, " sigma and ", m1 / 2.0 + .5, " sigma (should be about ", expect, "):\n ", "         ",
                ncounts[m1], " negative and ", counts[m1], " positive ", "\n");
            cout.precision(5);
            final double negSigs = Math.abs(ncounts[m1] - expect) / sig;
            final double posSigs = Math.abs(counts[m1] - expect) / sig;
            say(cout, "        These represent ", negSigs, " and ", posSigs, " sigma from expectations\n");
            if (negSigs > REJECT || posSigs > REJECT) {
                cout.put("REJECT hypothesis that this distribution is correct!!\n");
                good = false;
            }
            if (negSigs > worstSigma) worstSigma = negSigs;
            if (posSigs > worstSigma) worstSigma = posSigs;
        }
        say(cout, "\n The worst deviation encountered (out of about 25) was ", worstSigma, " sigma \n\n");
        cout.precision(oldprecision);
        return good;
    }

    private static boolean skewNormalTest(COStream cout, HepRandom dist, double k, int nNumbers) {
        boolean good = true;
        double worstSigma = 0;
        double sumx = 0;
        double sumx2 = 0;
        double sumx3 = 0;
        double sumx4 = 0;
        double sumx5 = 0;
        double sumx6 = 0;
        final int oldprecision = cout.precision();
        cout.precision(5);
        double x;
        // calculate mean and sigma
        final double delta = k / Math.sqrt(1 + k * k);
        final double mu = delta / Math.sqrt(halfpi);
        final double mom2 = 1.;
        final double mom3 = 3 * delta * (1 - (delta * delta) / 3.) / Math.sqrt(halfpi);
        final double mom4 = 3.;
        final double mom5 = 15 * delta * (1 - 2. * (delta * delta) / 3. + (delta * delta * delta * delta) / 5.) / Math.sqrt(halfpi);
        final double mom6 = 15.;
        final int ipr = nNumbers / 10 + 1;
        for (int ifire = 0; ifire < nNumbers; ifire++) {
            x = dist.call();
            if (x < mu - 12.0) say(cout, "x  = ", x, "\n");
            if ((ifire % ipr) == 0) say(cout, ifire, "\n");
            sumx += x;
            sumx2 += x * x;
            sumx3 += x * x * x;
            sumx4 += x * x * x * x;
            sumx5 += x * x * x * x * x;
            sumx6 += x * x * x * x * x * x;
        }
        final double mean = sumx / nNumbers;
        final double u2 = sumx2 / nNumbers;
        final double u3 = sumx3 / nNumbers;
        final double u4 = sumx4 / nNumbers;
        final double u5 = sumx5 / nNumbers;
        final double u6 = sumx6 / nNumbers;
        say(cout, "Mean (should be close to ", mu, "): ", mean, "\n");
        say(cout, "Second moment (should be close to ", mom2, "): ", u2, "\n");
        say(cout, "Third  moment (should be close to ", mom3, "): ", u3, "\n");
        say(cout, "Fourth moment (should be close to ", mom4, "): ", u4, "\n");
        say(cout, "Fifth moment (should be close to ", mom5, "): ", u5, "\n");
        say(cout, "Sixth moment (should be close to ", mom6, "): ", u6, "\n");
        final double del1 = Math.sqrt((double) nNumbers) * Math.abs(mean - mu);
        final double del2 = Math.sqrt(nNumbers / 2.0) * Math.abs(u2 - mom2);
        final double del3 = Math.sqrt(nNumbers / (15. - mom3 * mom3)) * Math.abs(u3 - mom3);
        final double del4 = Math.sqrt(nNumbers / 96.0) * Math.abs(u4 - mom4);
        final double del5 = Math.sqrt(nNumbers / (945. - mom5 * mom5)) * Math.abs(u5 - mom5);
        final double del6 = Math.sqrt(nNumbers / 10170.0) * Math.abs(u6 - mom6);
        say(cout, "        These represent ", del1, ", ", del2, ", ", del3, ", \n", "                        ", del4, ", ", del5,
            ", ", del6, "\n                         standard deviations from expectations\n");
        for (double del : new double[] {del1, del2, del3, del4, del5, del6}) {
            if (del > worstSigma) worstSigma = del;
        }
        if (del1 > REJECT || del2 > REJECT || del3 > REJECT || del4 > REJECT || del5 > REJECT || del6 > REJECT) {
            cout.put("REJECT hypothesis that this distribution is correct!!\n");
            good = false;
        }
        say(cout, "\n The worst deviation encountered (out of about 25) was ", worstSigma, " sigma \n\n");
        cout.precision(oldprecision);
        return good;
    }

    /** The test's poisson(mu)(r): the probability of r. */
    private static double poisson(double mu, int r) {
        final double logAnswer = -mu + r * CMath.log(mu) - gammln(r + 1);
        return CMath.exp(logAnswer);
    }

    /**
     * createRefDist: the expected counts, more than MINBIN in each bin (of clumping values), the tails rolled up
     * at each end; the first and last bins into firstLast.
     */
    private static double[] createRefDist(Io io, double mu, int N1, int MINBIN, int MAXBINS, int clumping, int[] firstLast) {
        final double[] refdist = new double[MAXBINS];
        int c = 0;
        int ic;
        int r = 0;
        double start = 0;
        double binc;
        while (c < MAXBINS) {
            for (ic = 0, binc = 0; ic < clumping; ic++, r++) binc += poisson(mu, r) * N1;
            start += binc;
            if (binc >= MINBIN) break;
            c++;
            if (c > MAXBINS / 3) {
                say(io.cout, "The number of samples supplied ", N1, " is too small to set up a chi^2 to test this distribution.\n");
                throw new Exit(-1);
            }
        }
        final int firstBin = c;
        refdist[firstBin] = start;
        c++;
        // Fill all the other bins until one has less than 20 items.
        double next = 0;
        while (c < MAXBINS) {
            for (ic = 0, binc = 0; ic < clumping; ic++, r++) binc += poisson(mu, r) * N1;
            next = binc;
            if (next < MINBIN) break;
            refdist[c] = next;
            c++;
        }
        // Shove all the remaining items into last bin.
        final int lastBin = c - 1;
        next += refdist[lastBin];
        while (c < MAXBINS) {
            for (ic = 0, binc = 0; ic < clumping; ic++, r++) binc += poisson(mu, r) * N1;
            next += binc;
            c++;
        }
        refdist[lastBin] = next;
        firstLast[0] = firstBin;
        firstLast[1] = lastBin;
        return refdist;
    }

    /**
     * Chi-squared tests of the counts, value by value and in clumps of sqrt(mu) values, then the mean and sigma.
     * dist.fire() is RandPoisson's, not virtual: RandPoissonQ and RandPoissonT draw here with RandPoisson's algorithm.
     */
    private static boolean poissonTest(Io io, RandPoisson dist, double mu, int N2) {
        final COStream cout = io.cout;
        boolean good = true;
        int clumping = (int) Math.sqrt(mu);
        if (clumping <= 1) clumping = 2;
        final int MINBIN = 20;
        final int MAXBINS = 1000;
        final int[] fl = new int[2];
        final int[] fl2 = new int[2];
        final double[] refdist = createRefDist(io, mu, N2, MINBIN, MAXBINS, 1, fl);
        final double[] refdist2 = createRefDist(io, mu, N2, MINBIN, MAXBINS, clumping, fl2);
        final int firstBin = fl[0];
        final int lastBin = fl[1];
        final int firstBin2 = fl2[0];
        final int lastBin2 = fl2[1];
        // Now roll the random dists, treating the tails in the same way as we go.
        double sum = 0;
        double moment = 0;
        final double[] samples = new double[MAXBINS];
        final double[] samples2 = new double[MAXBINS];
        int r;
        int r1;
        int r2;
        for (int i = 0; i < N2; i++) {
            r = (int) dist.fireAsRandPoisson();
            sum += r;
            moment += (r - mu) * (r - mu);
            r1 = r;
            if (r1 < firstBin) r1 = firstBin;
            if (r1 > lastBin) r1 = lastBin;
            samples[r1] += 1;
            r2 = r / clumping;
            if (r2 < firstBin2) r2 = firstBin2;
            if (r2 > lastBin2) r2 = lastBin2;
            samples2[r2] += 1;
        }
        int k;
        for (k = firstBin; k <= lastBin; k++) {
            say(cout, k, "  ", samples[k], "  ", refdist[k], "     ", (samples[k] - refdist[k]) * (samples[k] - refdist[k]) / refdist[k], "\n");
        }
        cout.put("----\n");
        for (k = firstBin2; k <= lastBin2; k++) say(cout, k, "  ", samples2[k], "  ", refdist2[k], "\n");
        // Now find chi^2 for samples[] to apply the first test
        double chi2 = 0;
        for (r = firstBin; r <= lastBin; r++) {
            final double delta = samples[r] - refdist[r];
            chi2 += delta * delta / refdist[r];
        }
        int degFreedom = (lastBin - firstBin + 1) - 1;
        final double pval = 1.0 - gammp(cout, .5 * degFreedom, .5 * chi2);
        say(cout, "Chi^2 is ", chi2, " on ", degFreedom, " degrees of freedom.", "  p = ", pval, "\n");
        // Repeat the chi^2 and p for the clumped sample, to apply the second test
        chi2 = 0;
        for (r = firstBin2; r <= lastBin2; r++) {
            final double delta = samples2[r] - refdist2[r];
            chi2 += delta * delta / refdist2[r];
        }
        degFreedom = (lastBin2 - firstBin2 + 1) - 1;
        final double pval2 = 1.0 - gammp(cout, .5 * degFreedom, .5 * chi2);
        say(cout, "Clumps: Chi^2 is ", chi2, " on ", degFreedom, " degrees of freedom.", "  p = ", pval2, "\n");
        // Check out the mean and sigma to apply the third test
        final double mean = sum / N2;
        final double sigma = Math.sqrt(moment / (N2 - 1));
        final double deviationMean = Math.abs(mean - mu) / (Math.sqrt(mu / N2));
        final double expectedSigma2Variance = (2 * N2 * mu * mu / (N2 - 1) + mu) / N2;
        final double deviationSigma = Math.abs(sigma * sigma - mu) / Math.sqrt(expectedSigma2Variance);
        say(cout, "Mean  (should be ", mu, ") is ", mean, "\n");
        say(cout, "Sigma (should be ", Math.sqrt(mu), ") is ", sigma, "\n");
        say(cout, "These are ", deviationMean, " and ", deviationSigma, " standard deviations from expected values\n\n");
        if ((pval < .0001) || (pval2 < .0001) || (deviationMean > 3.5) || (deviationSigma > 3.5)) {
            good = false;
            cout.put("REJECT this distributon!!!\n");
        }
        return good;
    }

    /** cin &gt;&gt; seed; cout &lt;&lt; seed: the seed, 0 when the test is to be skipped (said so). */
    private static long seed(Io io) {
        io.cout.put("Please enter an integer seed:        ");
        final long seed = io.cin().nextLong();
        say(io.cout, seed, "\n");
        if (seed == 0) io.cout.put("Moving on to next test...\n");
        return seed;
    }

    private static int echoInt(Io io, String prompt) {
        io.cout.put(prompt);
        final int v = io.cin().nextInt();
        say(io.cout, v, "\n");
        return v;
    }

    private static double echoDouble(Io io, String prompt) {
        io.cout.put(prompt);
        final double v = io.cin().nextDouble();
        say(io.cout, v, "\n");
        return v;
    }

    /** testRandGauss, testRandGaussQ and testRandGaussT: their engine, its name, the distribution, the bit. */
    private static int testGaussian(Io io, String name, String engineName, LongFunction<HepRandomEngine> engine,
                                    TriFunction make, int bad) {
        final COStream cout = io.cout;
        cout.put("\n--------------------------------------------\n");
        cout.put("Test of " + name + " distribution \n\n");
        final long seed = seed(io);
        if (seed == 0) return 0;
        final int nNumbers = echoInt(io, "How many numbers should we generate: ");
        if (name.equals("RandGaussQ") && nNumbers >= 20000000) {
            cout.put("With that many samples RandGaussQ need not pass validation...\n");
        }
        final double mu = echoDouble(io, "Enter mu: ");
        final double sigma = echoDouble(io, "Enter sigma: ");
        cout.put("\nInstantiating distribution utilizing " + engineName + " engine...\n");
        final RandGauss dist = make.apply(engine.apply(seed), mu, sigma);
        cout.put("\n Sample  fire(): \n");
        final double x = dist.fire();
        say(cout, x);
        cout.put("\n Testing operator() ... \n");
        return gaussianTest(cout, dist, mu, sigma, nNumbers) ? 0 : bad;
    }

    /** A RandGauss's constructor (engine, mu, sigma). */
    private interface TriFunction {
        RandGauss apply(HepRandomEngine e, double mu, double sigma);
    }

    /** The RandGeneral pdf: a Gaussian of mu .5 and that sigma, in nBins bins from 0 to 1, not normalized. */
    private static double[] gaussianPdf(int nBins, double mu, double sigma) {
        final double xBins = nBins;
        final double[] aProbFunc = new double[nBins];
        for (int iBin = 0; iBin < nBins; iBin++) {
            final double x = iBin / (xBins - 1);
            aProbFunc[iBin] = CMath.exp(-(x - mu) * (x - mu) / (2 * sigma * sigma));
        }
        return aProbFunc;
    }

    private static int testRandGeneral(Io io) {
        final COStream cout = io.cout;
        cout.put("\n--------------------------------------------\n");
        cout.put("Test of RandGeneral distribution (using a Gaussian shape)\n\n");
        boolean good;
        final long seed = seed(io);
        if (seed == 0) return 0;
        final int nNumbers = echoInt(io, "How many numbers should we generate: ");
        final double mu = .5; // Since randGeneral always ranges from 0 to 1
        final double sigma = echoDouble(io, "Enter sigma: ");
        int nBins = echoInt(io, "Enter nBins for stepwise pdf test: ");
        cout.put("\nInstantiating distribution utilizing Ranlux64 engine...\n");
        final Ranlux64Engine eng = new Ranlux64Engine(seed, 3);
        {
            // type 1 - step function pdf
            final RandGeneral dist = new RandGeneral(eng, gaussianPdf(nBins, mu, sigma), nBins, 1);
            cout.put("\n Sample fire(): \n");
            say(cout, dist.fire());
            cout.put("\n Testing operator() ... \n");
            good = gaussianTest(cout, dist, mu, sigma, nNumbers);
        }
        nBins = echoInt(io, "Enter nBins for linearized pdf test: ");
        final RandGeneral dist = new RandGeneral(eng, gaussianPdf(nBins, mu, sigma), nBins, 0);
        cout.put("\n Sample operator(): \n");
        say(cout, dist.call());
        cout.put("\n Testing operator() ... \n");
        final boolean good2 = gaussianTest(cout, dist, mu, sigma, nNumbers);
        good = good && good2;
        return good ? 0 : GENERAL_BAD;
    }

    /** testRandPoisson, testRandPoissonQ and testRandPoissonT: one TripleRand, a distribution for each mu read. */
    private static int testPoisson(Io io, String name, BiFunction<HepRandomEngine, Double, RandPoisson> make, int bad) {
        final COStream cout = io.cout;
        cout.put("\n--------------------------------------------\n");
        cout.put("Test of " + name + " distribution \n\n");
        final long seed = seed(io);
        if (seed == 0) return 0;
        cout.put("\nInstantiating distribution utilizing TripleRand engine...\n");
        final TripleRand eng = new TripleRand(seed);
        final int nNumbers = echoInt(io, "How many numbers should we generate for each mu: ");
        boolean good = true;
        while (true) {
            final double mu = echoDouble(io, "Enter a value for mu: ");
            if (mu == 0) break;
            final RandPoisson dist = make.apply(eng, mu);
            cout.put("\n Sample  fire(): \n");
            final double x = dist.fire();
            say(cout, x);
            cout.put("\n Testing operator() ... \n");
            final boolean thisGood = poissonTest(io, dist, mu, nNumbers);
            if (!thisGood) say(cout, "\n Poisson distribution for mu = ", mu, " is incorrect!!!\n");
            good &= thisGood;
        }
        return good ? 0 : bad;
    }

    private static int testSkewNormal(Io io) {
        final COStream cout = io.cout;
        cout.put("\n--------------------------------------------\n");
        cout.put("Test of SkewNormal distribution \n\n");
        final long seed = seed(io);
        if (seed == 0) return 0;
        final int nNumbers = echoInt(io, "How many numbers should we generate: ");
        final double k = echoDouble(io, "Enter k: ");
        cout.put("\nInstantiating distribution utilizing TripleRand engine...\n");
        final TripleRand eng = new TripleRand(seed);
        final RandSkewNormal dist = new RandSkewNormal(eng, k);
        cout.put("\n Sample  fire(): \n");
        say(cout, dist.fire());
        cout.put("\n Testing operator() ... \n");
        return skewNormalTest(cout, dist, k, nNumbers) ? 0 : SKEW_NORMAL_BAD;
    }

    /** With CLHEP's input: some 10 million numbers, Gaussian, general, Poisson and skew-normal. */
    static int testRandDists(Io io) {
        HepRandom.createInstance();
        int mask = 0;
        mask |= testGaussian(io, "RandGauss", "TripleRand", TripleRand::new, RandGauss::new, GAUSS_BAD);
        mask |= testGaussian(io, "RandGaussQ", "DualRand", DualRand::new, RandGaussQ::new, GAUSSQ_BAD);
        mask |= testGaussian(io, "RandGaussT", "TripleRand", TripleRand::new, RandGaussT::new, GAUSST_BAD);
        mask |= testRandGeneral(io);
        mask |= testPoisson(io, "RandPoisson", RandPoisson::new, POISSON_BAD);
        mask |= testPoisson(io, "RandPoissonQ", RandPoissonQ::new, POISSONQ_BAD);
        mask |= testPoisson(io, "RandPoissonT", RandPoissonT::new, POISSONT_BAD);
        mask |= testSkewNormal(io); // k = 0 (gaussian)
        mask |= testSkewNormal(io); // k = -2
        mask |= testSkewNormal(io); // k = 1
        mask |= testSkewNormal(io); // k = 5
        return mask > 0 ? -mask : mask;
    }

    /* ---- testVectorDists (RandomObjects) ---- */

    /** modifiedOutput: the elements negligible beside their diagonal ones set to 0. */
    private static HepMatrix modifiedOutput(HepSymMatrix D) {
        final HepMatrix DD = new HepMatrix(D);
        final int n = DD.num_row();
        final int m = DD.num_col();
        int i;
        int j;
        for (i = 1; i <= n; ++i) {
            for (j = 1; j <= m; ++j) {
                if (DD.get(i, j) * DD.get(i, j) < 1.0e-24 * DD.get(i, i) * DD.get(j, j)) DD.set(i, j, 0);
            }
        }
        return DD;
    }

    private static final int MULTI_GAUSS_BAD = 1;

    private static int testRandMultiGauss(Io io) {
        final COStream cout = io.cout;
        final CIStream cin = io.cin();
        cout.put("\n--------------------------------------------\n");
        cout.put("Test of RandMultiGauss distribution \n\n");
        cout.put("Please enter an integer seed:        ");
        final long seed = cin.nextLong();
        cout.put("How many vectors should we generate: ");
        final int nvectors = cin.nextInt();
        final double rootn = Math.sqrt((double) nvectors);
        cout.put("Enter the dimensions of mu, then S (normally the same):  ");
        int nMu = cin.nextInt();
        int nS = cin.nextInt();
        if (nMu != nS) {
            cout.put("Usually mu and S will be of equal dimensions.\n");
            cout.put("You may be testing the behavior when that is not the case.\n");
            cout.put("Please verify by re-entering the correct dimensions: ");
            nMu = cin.nextInt();
            nS = cin.nextInt();
        }
        final int dim = (nMu >= nS) ? nMu : nS;
        final HepVector mu = new HepVector(nMu);
        final HepSymMatrix S = new HepSymMatrix(nS);
        cout.put("Enter mu, one component at a time: \n");
        for (int imu = 1; imu <= nMu; imu++) {
            say(cout, imu, ": ");
            mu.set(imu, cin.nextDouble());
        }
        cout.put("Enter S, one white-space-separated row at a time. \n");
        cout.put("The length needed for each row is given in {braces}.\n");
        cout.put("The diagonal elements of S will be the first numbers on each line:\n");
        for (int row = 1; row <= nS; row++) {
            say(cout, row, " {", nS - row + 1, " numbers}: ");
            for (int col = row; col <= nS; col++) S.set(row, col, cin.nextDouble());
        }
        say(cout, "mu is: \n", mu, "\n");
        say(cout, "S is: \n", S, "\n");
        final HepSymMatrix tempS = new HepSymMatrix(S); // diagonalize changes the matrix it is given
        final HepMatrix U = MatrixLinear.diagonalize(tempS);
        final HepSymMatrix D = S.similarityT(U);
        say(cout, "D = Diagonalized S is ", modifiedOutput(D), "\n");
        boolean pd = true;
        for (int npd = 1; npd <= dim; npd++) {
            if (D.get(npd, npd) < 0) pd = false;
        }
        if (!pd) {
            cout.put("S is not positive definite.\n" + "The negative elements of D will have been raised to zero.\n"
                + "The second moment matrix at the end will not match S.\n");
        }
        cout.put("\nInstantiating distribution utilizing TripleRand engine...\n");
        final TripleRand eng = new TripleRand(seed);
        final RandMultiGauss dist = new RandMultiGauss(eng, mu, S);
        final HepVector x = new HepVector(dim);
        cout.put("\n Sample  fire(): \n");
        x.assign(dist.fire());
        say(cout, x);
        say(cout, "Normal operation will try a single group of ", nvectors, " random vectors.\n", "Enter 1 for a single trial with ",
            nvectors, " random vectors.\n", "Alternatively some number of groups of ", nvectors,
            " vectors can be produced to accumulate deviation statistics.\n", "Enter ", 5000 / (dim * (dim + 1)) + 1,
            " or some other number of trials to do this: ");
        final int ntrials = cin.nextInt();
        if (ntrials < 1) return 0;
        cout.put("\n Testing fire() ... \n");
        final HepSymMatrix Sumxy = new HepSymMatrix(nS);
        final HepSymMatrix Dprime = new HepSymMatrix(dim);
        final HepSymMatrix VarD = new HepSymMatrix(dim);
        final HepSymMatrix Delta = new HepSymMatrix(dim);
        int ipr = nvectors / 10;
        if (ipr < 1) ipr = 1;
        int in1 = 0;
        int in2 = 0;
        int in3 = 0;
        int nentries = 0;
        float binno;
        int nbin;
        final int[] bins = new int[30];
        int ix;
        int iy;
        double sumDelta = 0.0;
        double sumDelta2 = 0.0;
        int nunder = 0;
        int nover = 0;
        double worstDeviation = 0;
        int k;
        for (k = 0; k < ntrials; ++k) {
            final HepVector sumx = new HepVector(dim, 0);
            final HepMatrix sumxy = new HepMatrix(dim, dim, 0);
            for (int ifire = 1; ifire <= nvectors; ifire++) {
                x.assign(dist.fire());
                for (ix = 1; ix <= dim; ix++) {
                    sumx.set(ix, sumx.get(ix) + x.get(ix));
                    for (iy = 1; iy <= dim; iy++) sumxy.set(ix, iy, sumxy.get(ix, iy) + x.get(ix) * x.get(iy));
                }
                if ((ifire % ipr) == 0 && k == 0) say(cout, ifire, ", ");
            }
            if (k == 0) say(cout, "Statistics for the first (or only) trial of ", nvectors, " vectors:\n\n");
            sumx.assign(sumx.div(nvectors));
            if (k == 0) say(cout, "\nAverage (should match mu): \n", sumx, "\n");
            for (ix = 1; ix <= dim; ix++) {
                for (iy = 1; iy <= dim; iy++) sumxy.set(ix, iy, sumxy.get(ix, iy) / nvectors - sumx.get(ix) * sumx.get(iy));
            }
            if (pd) {
                if (k == 0) say(cout, "Second Moments (should match S)\n", sumxy, "\n");
            } else {
                if (k == 0) say(cout, "Second Moments \n", sumxy, "\n");
            }
            // sumxy transformed with the matrix that diagonalized S: similarityT acts on a symmetric matrix
            Sumxy.assign(sumxy);
            Dprime.assign(Sumxy.similarityT(U));
            if (k == 0) {
                say(cout, "\nDprime = Second moment matrix transformed by the same matrix that diagonalized S\n", Dprime, "\n");
            }
            for (ix = 1; ix <= dim; ++ix) {
                for (iy = ix; iy <= dim; ++iy) {
                    if (ix == iy) VarD.set(ix, iy, 2.0 * Dprime.get(ix, iy) * Dprime.get(ix, iy) / rootn);
                    else VarD.set(ix, iy, Dprime.get(ix, ix) * Dprime.get(iy, iy) / rootn);
                }
            }
            if (k == 0) say(cout, "\nThe expected variance for Dprime elements is \n", VarD, "\n");
            for (ix = 1; ix <= dim; ++ix) {
                for (iy = ix; iy <= dim; ++iy) {
                    Delta.set(ix, iy, Math.sqrt(rootn) * (D.get(ix, iy) - Dprime.get(ix, iy)) / Math.sqrt(VarD.get(ix, iy)));
                    if (k == 0) {
                        if (Math.abs(Delta.get(ix, iy)) > Math.abs(worstDeviation)) worstDeviation = Delta.get(ix, iy);
                    }
                }
            }
            if (k == 0) {
                say(cout, "\nDifferences between each element and its normative value,\n",
                    "scaled by the expected deviation (sqrt(variance)) are: \n", Delta, "\n");
            }
            if (k == 0) {
                cout.put("About 5% of the above values should have absolute value more than 2.\n"
                    + "Deviations of more than 4 sigma would probably indicate a problem.\n");
            }
            // Do a little counting
            for (ix = 1; ix <= dim; ++ix) {
                for (iy = ix; iy <= dim; ++iy) {
                    final double d = Delta.get(ix, iy);
                    if (d >= -1.0 && d <= 1.0) in1++;
                    if (d >= -2.0 && d <= 2.0) in2++;
                    if (d >= -3.0 && d <= 3.0) in3++;
                    sumDelta += d;
                    sumDelta2 += d * d;
                    binno = (float) (5.0 * (d + 3.0));
                    if (binno < 0.0) ++nunder;
                    if (binno > 30.0) ++nover;
                    if (binno >= 0.0 && binno < 30.0) {
                        nbin = (int) binno;
                        ++nentries;
                        ++bins[nbin];
                    }
                }
            }
        }
        if (ntrials == 1) {
            say(cout, "The worst deviation of any element of D in this trial was ", worstDeviation, "\n");
            if (Math.abs(worstDeviation) > 4) {
                cout.put("\nREJECT this distribution: \n" + "This value indicates a PROBLEM!!!!\n\n");
                return MULTI_GAUSS_BAD;
            }
            return 0;
        }
        final float ndf = (float) (ntrials * dim * (dim + 1) / 2.0);
        say(cout, "\nOut of a total of ").put(ndf).put("\n");
        say(cout, "There are ", in1, " within 1 sigma or ", 100.0 * (float) in1 / ndf, "%", "\n");
        say(cout, "There are ", in2, " within 2 sigma or ", 100.0 * (float) in2 / ndf, "%", "\n");
        say(cout, "There are ", in3, " within 3 sigma or ", 100.0 * (float) in3 / ndf, "%", "\n");
        final double aveDelta = sumDelta / (double) ndf;
        final double rmsDelta = sumDelta2 / (double) ndf - aveDelta * aveDelta;
        say(cout, "\nFor dim = ", dim, " Average(Delta) = ", aveDelta, "    RMS(Delta) = ", rmsDelta, "\n");
        cout.put("\nPoor man's histogram of deviations in 30 bins from -3.0 to 3.0\n");
        cout.put("This should be a standard unit Gaussian.\n\n");
        for (k = 0; k < 30; ++k) {
            cout.width(3);
            cout.put(k + 1).put("   ");
            cout.width(4);
            cout.put(bins[k]).put("\n");
        }
        say(cout, "\nTotal number of entries in this histogram is ", nentries, "\n");
        say(cout, "\twith ", nunder, " underflow(s) and ", nover, " overflow(s)", "\n");
        int status;
        say(cout, "The mean squared delta should be between .85 and 1.15; it is ", rmsDelta, "\n");
        if (Math.abs(rmsDelta - 1.0) <= 0.15) {
            status = 0;
        } else {
            cout.put("REJECT this distribution based on improper spread of delta!\n");
            status = MULTI_GAUSS_BAD;
        }
        if (Math.abs(worstDeviation) > 4) {
            cout.put("REJECT this distribution:  Bad correlation in first trial!\n");
            status = MULTI_GAUSS_BAD;
        }
        return status;
    }

    static int testVectorDists(Io io) {
        HepRandom.createInstance();
        int mask = 0;
        mask |= testRandMultiGauss(io);
        return mask;
    }

    /* ---- testDistCopy ---- */

    /** copy_constructor_is_okay: the copy's saved state is the original's, and it shares the engine. */
    private static <D extends HepRandom> boolean copyConstructorIsOkay(D d1, UnaryOperator<D> copy, Consumer<D> fire) {
        // prime the distribution
        for (int i = 0; i != 17; ++i) fire.accept(d1);
        // capture its state
        final String os1 = d1.put(new COStream()).str();
        final HepRandomEngine e1 = d1.engine();
        // make a copy and capture the copy's state
        final D d2 = copy.apply(d1);
        final String os2 = d2.put(new COStream()).str();
        final HepRandomEngine e2 = d2.engine();
        // do the saved states match and is the underlying engine shared?
        return os1.equals(os2) && e1 == e2;
    }

    /** copy_assignment_is_okay: d2 = d1 makes d2's saved state d1's, its engine d1's. */
    private static <D extends HepRandom> boolean copyAssignmentIsOkay(D d1, D d2, BiConsumer<D, D> assign, Consumer<D> fire) {
        for (int i = 0; i != 17; ++i) fire.accept(d1);
        for (int i = 0; i != 19; ++i) fire.accept(d2);
        final String os1 = d1.put(new COStream()).str();
        final HepRandomEngine e1 = d1.engine();
        assign.accept(d2, d1);
        final String os2 = d2.put(new COStream()).str();
        final HepRandomEngine e2 = d2.engine();
        return os1.equals(os2) && e1 == e2;
    }

    private static <D extends HepRandom> int distCopy(Function<HepRandomEngine, D> make, UnaryOperator<D> copy,
                                                      BiConsumer<D, D> assign, Consumer<D> fire, int failure) {
        return distCopy(make, make, copy, assign, fire, failure);
    }

    /** One distribution's test: copied from make1's, on an MTwistEngine, assigned over make2's, on a DualRand. */
    private static <D extends HepRandom> int distCopy(Function<HepRandomEngine, D> make1, Function<HepRandomEngine, D> make2,
                                                      UnaryOperator<D> copy, BiConsumer<D, D> assign, Consumer<D> fire,
                                                      int failure) {
        final D d1 = make1.apply(new MTwistEngine(97531L));
        if (!copyConstructorIsOkay(d1, copy, fire)) return failure;
        final D d2 = make2.apply(new DualRand(13579L));
        if (!copyAssignmentIsOkay(d1, d2, assign, fire)) return failure;
        return 0;
    }

    /** Exit code only: 0, or minus the mask of the distributions whose copies failed. */
    static int testDistCopy(Io io) {
        final double[] pdf1 = {1.5, 2.5, 3.0, 4.25, 5.65};
        final double[] pdf2 = {1.0, 2.0, 3.0, 4.0, 5.0, 0.60};
        int mask = 0;
        mask |= distCopy(RandBinomial::new, RandBinomial::new, RandBinomial::assign, RandBinomial::fire, 1 << 1);
        mask |= distCopy(RandBreitWigner::new, RandBreitWigner::new, RandBreitWigner::assign, RandBreitWigner::fire, 1 << 2);
        mask |= distCopy(RandChiSquare::new, RandChiSquare::new, RandChiSquare::assign, RandChiSquare::fire, 1 << 3);
        mask |= distCopy(RandExponential::new, RandExponential::new, RandExponential::assign, RandExponential::fire, 1 << 4);
        mask |= distCopy(RandFlat::new, RandFlat::new, RandFlat::assign, RandFlat::fire, 1 << 5);
        mask |= distCopy(RandGamma::new, RandGamma::new, RandGamma::assign, RandGamma::fire, 1 << 6);
        mask |= distCopy(RandGauss::new, RandGauss::new, RandGauss::assign, RandGauss::fire, 1 << 7);
        mask |= distCopy(RandGaussQ::new, RandGaussQ::new, RandGaussQ::assign, RandGaussQ::fire, 1 << 8);
        mask |= distCopy(RandGaussT::new, RandGaussT::new, RandGaussT::assign, RandGaussT::fire, 1 << 9);
        mask |= distCopy(e -> new RandGeneral(e, pdf1, pdf1.length), e -> new RandGeneral(e, pdf2, pdf2.length), RandGeneral::new,
            RandGeneral::assign, RandGeneral::fire, 1 << 10);
        mask |= distCopy(RandLandau::new, RandLandau::new, RandLandau::assign, RandLandau::fire, 1 << 11);
        mask |= distCopy(RandPoisson::new, RandPoisson::new, RandPoisson::assign, RandPoisson::fire, 1 << 12);
        mask |= distCopy(RandPoissonQ::new, RandPoissonQ::new, RandPoissonQ::assign, RandPoissonQ::fire, 1 << 13);
        mask |= distCopy(RandPoissonT::new, RandPoissonT::new, RandPoissonT::assign, RandPoissonT::fire, 1 << 14);
        mask |= distCopy(RandSkewNormal::new, RandSkewNormal::new, RandSkewNormal::assign, RandSkewNormal::fire, 1 << 16);
        mask |= distCopy(RandStudentT::new, RandStudentT::new, RandStudentT::assign, RandStudentT::fire, 1 << 15);
        return -mask;
    }

    /* ---- testRanecuSequence ---- */

    private record Sample(int seed, double[] case1, double[] case2, double[] case3, double[] case4, double[] case5) {
    }

    private static double[] fifteen(HepRandomEngine e) {
        final double[] v = new double[15];
        for (int i = 0; i < 15; ++i) v[i] = e.flat();
        return v;
    }

    private static void useSeed(int seed, List<Sample> ranList) {
        // case 1  -- default constructor
        final double[] case1 = fifteen(new RanecuEngine());
        // case 2
        final double[] case2 = fifteen(new RanecuEngine(seed));
        // case 3 - use HepRandom
        HepRandom.setTheSeed(seed);
        final double[] case3 = new double[15];
        for (int i = 0; i < 15; ++i) case3[i] = HepRandom.getTheEngine().flat();
        // case 4
        final RanecuEngine c4 = new RanecuEngine(1);
        c4.setSeed(seed, 0);
        final double[] case4 = fifteen(c4);
        // case 5
        final RanecuEngine c5 = new RanecuEngine(1);
        final long[] seedarray = {seed, 1};
        c5.setSeeds(seedarray, 2);
        final double[] case5 = fifteen(c5);
        ranList.add(new Sample(seed, case1, case2, case3, case4, case5));
    }

    /** std::vector's ==: the same length, the elements equal. */
    private static boolean same(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static boolean sameCase(COStream output, Sample s1, Sample s2, double[] a, double[] b, String title) {
        if (!same(a, b)) return false;
        output.put(title);
        say(output, " comparing Seed ", s1.seed(), " and Seed ", s2.seed(), "\n");
        output.put(title.substring(0, 6) + " sequence:");
        for (double x : a) say(output, " ", x);
        output.put("\n");
        return true;
    }

    private static boolean compareSamples(Sample s1, Sample s2, COStream output) {
        if (sameCase(output, s1, s2, s1.case1(), s2.case1(), " case1:  default constructor \n")) return false;
        if (sameCase(output, s1, s2, s1.case2(), s2.case2(), " case2:  construct with single seed \n")) return false;
        if (sameCase(output, s1, s2, s1.case3(), s2.case3(), " case3:  construct with single seed \n")) return false;
        if (sameCase(output, s1, s2, s1.case4(), s2.case4(), " case4:  use setSeed \n")) return false;
        if (sameCase(output, s1, s2, s1.case5(), s2.case5(), " case5:  use setSeeds \n")) return false;
        return true;
    }

    static int testRanecuSequence(Io io) {
        final COStream output = io.file("testRanecuSequence.output");
        output.put(" Setting CLHEP Random Engine...\n");
        HepRandom.setTheEngine(new RanecuEngine());
        final List<Sample> ranList = new ArrayList<>();
        // the first 4 seeds once resulted in identical sequences
        useSeed(1275177715, ranList);
        useSeed(1275171265, ranList);
        useSeed(1275168040, ranList);
        useSeed(1275168040 - 2048 * 215, ranList);
        useSeed(4132429, ranList);
        useSeed(-1357924, ranList);
        int sd = 53208134;
        for (int i = 0; i < 1000; ++i) {
            ++sd;
            useSeed(sd, ranList);
        }
        int numbad = 0;
        output.put("\n");
        for (int it = 0; it < ranList.size(); ++it) {
            for (int jt = it + 1; jt < ranList.size(); ++jt) {
                if (!compareSamples(ranList.get(it), ranList.get(jt), output)) {
                    ++numbad;
                    final int a = ranList.get(it).seed();
                    final int b = ranList.get(jt).seed();
                    say(output, "ERROR: Seed ", a, " and Seed ", b, " are ", b - a, " apart and result in identical sequences", "\n");
                    say(io.cerr, "Seed ", a, " and Seed ", b, " are ", b - a, " apart and result in identical sequences", "\n");
                }
            }
        }
        say(output, " numbad is ", numbad, "\n");
        return numbad;
    }
}

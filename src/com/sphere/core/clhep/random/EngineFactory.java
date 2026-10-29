package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

import java.util.List;
import java.util.function.Supplier;

/**
 * CLHEP's EngineFactory (Random/EngineFactory.h, EngineFactory.cc): an engine of the kind a saved state
 * names, from its begin tag in a stream or its ID word in a vector, tried in CLHEP's order. Each engine
 * is made with its default constructor first, as in C++, so that engines counted on construction count
 * these too.
 */
public final class EngineFactory {

    /** An engine kind: its begin tag, its name (whose CRC is its ID word) and its default constructor. */
    record Kind(String beginTag, String engineName, Supplier<HepRandomEngine> make) {
    }

    static final List<Kind> KINDS = List.of(
        new Kind(HepJamesRandom.beginTag(), HepJamesRandom.engineName(), HepJamesRandom::new),
        new Kind(RanecuEngine.beginTag(), RanecuEngine.engineName(), RanecuEngine::new),
        new Kind(Ranlux64Engine.beginTag(), Ranlux64Engine.engineName(), Ranlux64Engine::new),
        new Kind(RanluxppEngine.beginTag(), RanluxppEngine.engineName(), RanluxppEngine::new),
        new Kind(MixMaxRng.beginTag(), MixMaxRng.engineName(), MixMaxRng::new),
        new Kind(MTwistEngine.beginTag(), MTwistEngine.engineName(), MTwistEngine::new),
        new Kind(DRand48Engine.beginTag(), DRand48Engine.engineName(), DRand48Engine::new),
        new Kind(TripleRand.beginTag(), TripleRand.engineName(), TripleRand::new),
        new Kind(DualRand.beginTag(), DualRand.engineName(), DualRand::new),
        new Kind(Hurd160Engine.beginTag(), Hurd160Engine.engineName(), Hurd160Engine::new),
        new Kind(Hurd288Engine.beginTag(), Hurd288Engine.engineName(), Hurd288Engine::new),
        new Kind(RandEngine.beginTag(), RandEngine.engineName(), RandEngine::new),
        new Kind(RanluxEngine.beginTag(), RanluxEngine.engineName(), RanluxEngine::new),
        new Kind(RanshiEngine.beginTag(), RanshiEngine.engineName(), RanshiEngine::new),
        new Kind(NonRandomEngine.beginTag(), NonRandomEngine.engineName(), NonRandomEngine::new));

    private EngineFactory() {
    }

    /** The engine whose begin tag starts the stream, its state read; null (stream failed) when none is. */
    public static HepRandomEngine newEngine(CIStream is) {
        final String tag = is.nextWord();
        for (Kind k : KINDS) {
            if (!tag.equals(k.beginTag())) continue;
            final HepRandomEngine e = k.make().get();
            e.getState(is);
            if (!is.fail()) return e;
        }
        is.setFail();
        StdStreams.cerr().print("Input mispositioned or bad in reading anonymous engine\n\nBegin-tag read was: " + tag
            + "\nInput stream is probably fouled up\n");
        return null;
    }

    /** The engine whose ID is the vector's first word, its state set from it; null when none is. */
    public static HepRandomEngine newEngine(long[] v) {
        for (Kind k : KINDS) {
            if ((v[0] & 0xffffffffL) != HepRandomEngine.engineIDulong(k.engineName())) continue;
            final HepRandomEngine e = k.make().get();
            if (e.getState(v)) return e;
        }
        StdStreams.cerr().print("Cannot correctly get anonymous engine from vector\nFirst unsigned long was: "
            + Long.toUnsignedString(v[0]) + " Vector size was: " + v.length + "\n");
        return null;
    }
}

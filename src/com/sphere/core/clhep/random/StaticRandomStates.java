package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's StaticRandomStates (Random/StaticRandomStates.h, .cc): this thread's static engine and the
 * states the static distributions keep (RandGauss's spare number, RandFlat's bits), saved and restored
 * together. Restoring an engine of the same kind sets the static engine's state; of another kind, it
 * becomes the static engine.
 */
public final class StaticRandomStates {

    private StaticRandomStates() {
    }

    public static COStream save(COStream os) {
        RandGauss.saveFullState(os);
        RandFlat.saveDistState(os);
        return os;
    }

    public static CIStream restore(CIStream is) {
        final HepRandomEngine e = HepRandom.getTheEngine();
        final HepRandomEngine ne = HepRandomEngine.newEngine(is);
        if (is.fail()) return is;
        if (ne == null) return is;
        if (ne.name().equals(e.name())) {
            // the same kind of engine: set the static one's state, so those who hold it see the change
            final COStream os = new COStream();
            ne.put(os);
            final CIStream istst = new CIStream(os.str());
            e.get(istst);
            if (istst.fail()) {
                StdStreams.cerr().print("???? Unexpected behavior in StaticRandomStates::restore:\n"
                    + "The new engine, which had been input successfully from istream\n"
                    + "has encountered a problem when used to set state of theEngine\n");
                is.setFail();
                return is;
            }
        } else {
            HepRandom.setTheEngine(ne);
        }
        RandGauss.restoreDistState(is);
        RandFlat.restoreDistState(is);
        return is;
    }
}

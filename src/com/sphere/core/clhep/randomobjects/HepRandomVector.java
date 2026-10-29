package com.sphere.core.clhep.randomobjects;

import com.sphere.core.clhep.matrix.HepVector;
import com.sphere.core.clhep.random.HepJamesRandom;
import com.sphere.core.clhep.random.HepRandomEngine;

/**
 * CLHEP's HepRandomVector (RandomObjects/RandomVector.h, .icc, .cc): the base of the distributions of random
 * vectors, on an engine (a HepJamesRandom of seed 11327503 by default). As in CLHEP, flat() fills the
 * default HepVector, which has no rows: flatArray fills vectors of their own sizes.
 */
public class HepRandomVector {

    protected HepRandomEngine theEngine;

    public HepRandomVector() {
        theEngine = new HepJamesRandom(11327503L);
    }

    public HepRandomVector(long seed) {
        theEngine = new HepJamesRandom(seed);
    }

    public HepRandomVector(HepRandomEngine engine) {
        theEngine = engine;
    }

    /** A vector of flat values from the engine: CLHEP's, which has no rows. */
    public HepVector flat() {
        return flat(theEngine);
    }

    public HepVector flat(HepRandomEngine theNewEngine) {
        final HepVector v = new HepVector();
        for (int i = 0; i < v.num_row(); i++) v.set(i + 1, theNewEngine.flat());
        return v;
    }

    /** Fills each of the first size vectors with flat values. */
    public void flatArray(int size, HepVector[] vect) {
        flatArray(theEngine, size, vect);
    }

    public void flatArray(HepRandomEngine theNewEngine, int size, HepVector[] vect) {
        for (int n = 0; n < size; n++) {
            for (int i = 0; i < vect[n].num_row(); i++) vect[n].set(i + 1, theNewEngine.flat());
        }
    }

    /** operator(). */
    public HepVector fire() {
        return flat();
    }
}

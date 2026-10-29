package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandBit (Random/RandBit.h, .icc, RandBit.cc): a RandFlat whose bits are each a flat number
 * compared with one half, slower than RandFlat's but independent of one another.
 */
public class RandBit extends RandFlat {

    public RandBit(HepRandomEngine anEngine) {
        super(anEngine);
    }

    public RandBit(HepRandomEngine anEngine, double width) {
        super(anEngine, width);
    }

    public RandBit(HepRandomEngine anEngine, double a, double b) {
        super(anEngine, a, b);
    }

    public static int shootBit() {
        final double x = shoot();
        return (x > .5) ? 1 : 0;
    }

    public static int shootBit(HepRandomEngine engine) {
        final double x = shoot(engine);
        return (x > .5) ? 1 : 0;
    }

    @Override
    public int fireBit() {
        final double x = fire(0, 1);
        return (x > .5) ? 1 : 0;
    }

    @Override
    public String name() {
        return "RandBit";
    }

    public static String distributionName() {
        return "RandBit";
    }

    @Override
    public COStream put(COStream os) {
        os.put(" ").put(name()).put("\n");
        super.put(os);
        return os;
    }

    /** The name, then RandFlat's state, which begins with the name again (name() is virtual, as in C++). */
    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        super.get(is);
        return is;
    }
}

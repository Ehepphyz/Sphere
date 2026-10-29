package com.sphere.core.clhep.genfun;

import java.util.ArrayList;
import java.util.List;

/**
 * CLHEP's Classical::PhaseSpace (PhaseSpace.hh, .cc): the coordinates q and momenta p of a system of
 * NDIM degrees of freedom, as the variables 0..NDIM-1 and NDIM..2NDIM-1 of functions of 2NDIM arguments,
 * with their starting values.
 */
public class PhaseSpace {

    /** The coordinates or the momenta. */
    public static final class Component {

        private final List<Variable> functions = new ArrayList<>();

        private Component(int NDIM, boolean isMomenta) {
            for (int i = 0; i < NDIM; i++) {
                final int offset = isMomenta ? NDIM : 0;
                functions.add(new Variable(i + offset, 2 * NDIM));
            }
        }

        /** The i-th one, operator[]. */
        public Variable get(int i) {
            return new Variable(functions.get(i));
        }
    }

    private final Component _coordinates;
    private final Component _momenta;
    private final double[] _q0;
    private final double[] _p0;
    private final int DIM;

    public PhaseSpace(int NDIM) {
        _coordinates = new Component(NDIM, false);
        _momenta = new Component(NDIM, true);
        _q0 = new double[NDIM];
        _p0 = new double[NDIM];
        DIM = NDIM;
    }

    public Component coordinates() {
        return _coordinates;
    }

    public Component momenta() {
        return _momenta;
    }

    /** The starting value of a coordinate or momentum. */
    public void start(Variable v, double value) {
        final int index = v.index();
        if (index < DIM) {
            _q0[index] = value;
        } else {
            _p0[index - DIM] = value;
        }
    }

    public double startValue(Variable v) {
        final int index = v.index();
        if (index < DIM) {
            return _q0[index];
        } else {
            return _p0[index - DIM];
        }
    }

    public int dim() {
        return DIM;
    }
}

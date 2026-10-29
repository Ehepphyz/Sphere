package com.sphere.core.clhep.genfun;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * CLHEP's Genfun::RKIntegrator (RKIntegrator.hh, .cc): solves dy/dt = f(y) for a set of variables from
 * starting values at t = 0, each solution a function of t ({@link RKFunction}). The points stepped to are
 * cached, by time, and reused until a starting value or control parameter changes.
 */
public class RKIntegrator {

    private final RKData _data;
    private final List<RKFunction> _fcn = new ArrayList<>();

    /** With an adaptive (Cash-Karp) stepper. */
    public RKIntegrator() {
        this(null);
    }

    public RKIntegrator(RKStepper stepper) {
        _data = new RKData();
        _data._stepper = stepper != null ? stepper.copy() : new AdaptiveRKStepper();
    }

    /** A copy of the equation dy/dt = diffEquation(y), with the parameter of y's starting value. */
    public Parameter addDiffEquation(AbsFunction diffEquation) {
        return addDiffEquation(diffEquation, "anon", 0.0, 0.0, 0.0);
    }

    public Parameter addDiffEquation(AbsFunction diffEquation, String variableName) {
        return addDiffEquation(diffEquation, variableName, 0.0, 0.0, 0.0);
    }

    public Parameter addDiffEquation(AbsFunction diffEquation, String variableName, double defStartingValue) {
        return addDiffEquation(diffEquation, variableName, defStartingValue, 0.0, 0.0);
    }

    public Parameter addDiffEquation(AbsFunction diffEquation, String variableName, double defStartingValue,
                                     double defValueMin, double defValueMax) {
        final Parameter par = new Parameter(variableName, defStartingValue, defValueMin, defValueMax);
        _data._startingValParameter.add(par);
        _data._diffEqn.add(diffEquation.copy());
        _data._startingValParameterCache.add(defStartingValue);
        _fcn.add(new RKFunction(_data, _fcn.size()));
        return par;
    }

    /** A parameter the equations may use; changing it clears the cached solution. */
    public Parameter createControlParameter() {
        return createControlParameter("anon", 0.0, 0.0, 0.0);
    }

    public Parameter createControlParameter(String variableName, double defStartingValue, double startingValueMin,
                                            double startingValueMax) {
        final Parameter par = new Parameter(variableName, defStartingValue, startingValueMin, startingValueMax);
        _data._controlParameter.add(par);
        _data._controlParameterCache.add(defStartingValue);
        return par;
    }

    /** The solution for the i-th variable, a function of t. */
    public RKFunction getFunction(int i) {
        return _fcn.get(i);
    }

    /** The equations, their parameters and the points solved for, shared by the solution functions. */
    public static final class RKData {

        /** A point of the solution: the variables and their derivatives at a time; ordered by time. */
        public static final class Data {
            public final double[] variable;
            public final double[] firstDerivative;
            public double time;

            public Data(int size) {
                variable = new double[size];
                firstDerivative = new double[size];
                time = 0;
            }
        }

        final List<Parameter> _startingValParameter = new ArrayList<>();
        final List<Double> _startingValParameterCache = new ArrayList<>();
        final List<Parameter> _controlParameter = new ArrayList<>();
        final List<Double> _controlParameterCache = new ArrayList<>();
        final List<AbsFunction> _diffEqn = new ArrayList<>();
        final TreeSet<Data> _fx = new TreeSet<>((a, b) -> a.time < b.time ? -1 : b.time < a.time ? 1 : 0);
        boolean _locked;
        RKStepper _stepper;

        /** The v-th equation's right-hand side, for steppers. */
        public AbsFunction diffEqn(int v) {
            return _diffEqn.get(v);
        }

        void lock() {
            if (!_locked) {
                final int size = _diffEqn.size();
                for (int i = 0; i < size; i++) {
                    if (!(_diffEqn.get(i).dimensionality() == size)) throw new RuntimeException("Runtime error in RKIntegrator");
                }
                _locked = true;
            }
        }

        void recache() {
            boolean stale = false;
            for (int p = 0; p < _startingValParameter.size(); p++) {
                if (_startingValParameter.get(p).getValue() != _startingValParameterCache.get(p)) {
                    _startingValParameterCache.set(p, _startingValParameter.get(p).getValue());
                    stale = true;
                    break;
                }
            }
            if (!stale) {
                for (int p = 0; p < _controlParameter.size(); p++) {
                    if (_controlParameter.get(p).getValue() != _controlParameterCache.get(p)) {
                        _controlParameterCache.set(p, _controlParameter.get(p).getValue());
                        stale = true;
                        break;
                    }
                }
            }
            if (stale) _fx.clear();
        }
    }

    /** The solution for one variable: 0 before t = 0, else stepped to from the nearest earlier cached point. */
    public static final class RKFunction extends AbsFunction {

        private final RKData _data;
        private final int _index;

        RKFunction(RKData data, int index) {
            _data = data;
            _index = index;
        }

        public RKFunction(RKFunction right) {
            _data = right._data;
            _index = right._index;
        }

        @Override
        public RKFunction copy() {
            return new RKFunction(this);
        }

        @Override
        public double call(double t) {
            if (t < 0) return 0;
            if (!_data._locked) _data.lock();
            _data.recache();
            final int nvar = _data._startingValParameter.size();
            if (_data._fx.isEmpty()) {
                final RKData.Data d = new RKData.Data(nvar);
                d.time = 0;
                for (int f = 0; f < nvar; f++) d.variable[f] = _data._startingValParameterCache.get(f);
                _data._fx.add(d);
            }
            if (t == 0) return _data._fx.first().variable[_index];

            final RKData.Data dt = new RKData.Data(nvar);
            dt.time = t;
            final RKData.Data l = _data._fx.ceiling(dt);
            if (l != null && l.time == t) {
                return l.variable[_index];
            } else {
                RKData.Data u = _data._fx.higher(dt);
                while (u == null) {
                    u = _data._fx.last();
                    final RKData.Data newData = new RKData.Data(nvar);
                    _data._stepper.step(_data, u, newData, 0);
                    _data._fx.add(newData);
                    if (newData.time == t) return newData.variable[_index];
                    u = _data._fx.higher(dt);
                }
                u = _data._fx.lower(u);
                _data._stepper.step(_data, u, dt, t);
                return dt.variable[_index];
            }
        }

        @Override
        public double call(Argument a) {
            return call(a.get(0));
        }
    }

    /** A step of the integration from s to d: to timeLimit if not 0, else as far as the stepper chooses. */
    public abstract static class RKStepper {

        public abstract void step(RKData data, RKData.Data sdata, RKData.Data ddata, double timeLimit);

        public abstract RKStepper copy();
    }
}

package com.sphere.core.clhep.genfun;

import com.sphere.core.hepmc3.cxx.COStream;
import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's Genfun::Parameter (GenericFunctions/Parameter.hh, Parameter.cc): a named value with limits, or,
 * once connected to another parameter, that one's value (the copies a function keeps of its parameters
 * are connected to the originals, so setting a parameter changes the functions made from it).
 */
public class Parameter extends AbsParameter {

    private String _name;
    private double _value;
    private double _lowerLimit;
    private double _upperLimit;
    private AbsParameter _sourceParameter;

    public Parameter(String name, double value) {
        this(name, value, -1e100, 1e100);
    }

    public Parameter(String name, double value, double lowerLimit) {
        this(name, value, lowerLimit, 1e100);
    }

    public Parameter(String name, double value, double lowerLimit, double upperLimit) {
        _name = name;
        _value = value;
        _lowerLimit = lowerLimit;
        _upperLimit = upperLimit;
        _sourceParameter = null;
    }

    public Parameter(Parameter right) {
        _name = right._name;
        _value = right._value;
        _lowerLimit = right._lowerLimit;
        _upperLimit = right._upperLimit;
        _sourceParameter = right._sourceParameter;
    }

    /** operator=. */
    public Parameter assign(Parameter right) {
        if (this != right) {
            _name = right._name;
            _value = right._value;
            _lowerLimit = right._lowerLimit;
            _upperLimit = right._upperLimit;
            _sourceParameter = right._sourceParameter;
        }
        return this;
    }

    @Override
    public Parameter copy() {
        return new Parameter(this);
    }

    @Override
    public Parameter parameter() {
        return this;
    }

    public String getName() {
        return _name;
    }

    @Override
    public double getValue() {
        if (_sourceParameter != null) return _sourceParameter.getValue();
        return _value;
    }

    /** -1e-100 when connected, as CLHEP writes it. */
    public double getLowerLimit() {
        if (_sourceParameter != null) return -1E-100;
        return _lowerLimit;
    }

    public double getUpperLimit() {
        if (_sourceParameter != null) return 1E100;
        return _upperLimit;
    }

    public void setValue(double value) {
        if (_sourceParameter != null) {
            StdStreams.cerr().print("Warning:  Parameter is connected.  Function has no effect.\n");
        } else {
            _value = value;
        }
    }

    public void setLowerLimit(double lowerLimit) {
        if (_sourceParameter != null) {
            StdStreams.cerr().print("Warning:  Parameter is connected.  Function has no effect.\n");
        } else {
            _lowerLimit = lowerLimit;
        }
    }

    public void setUpperLimit(double upperLimit) {
        if (_sourceParameter != null) {
            StdStreams.cerr().print("Warning:  Parameter is connected.  Function has no effect.\n");
        } else {
            _upperLimit = upperLimit;
        }
    }

    /** This parameter takes source's value (the source's own source when it is a connected Parameter). */
    public void connectFrom(AbsParameter source) {
        final Parameter sp = source.parameter();
        if (sp != null && sp._sourceParameter != null) {
            connectFrom(sp._sourceParameter);
        } else {
            _sourceParameter = source;
        }
    }

    /** operator&lt;&lt;. */
    public COStream print(COStream o) {
        return o.put(getName()).put("\t").put(" value = ").put(getValue()).put("\t").put(" limits: [")
            .put(getLowerLimit()).put(",").put(getUpperLimit()).put("]").put("\n");
    }

    @Override
    public String toString() {
        return print(new COStream()).str();
    }
}

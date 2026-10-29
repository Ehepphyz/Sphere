package com.sphere.core.clhep.random;

import com.sphere.core.hepmc3.cxx.CIStream;
import com.sphere.core.hepmc3.cxx.COStream;

/**
 * CLHEP's RandSkewNormal (Random/RandSkewNormal.h, .icc, RandSkewNormal.cc): Azzalini's skew-normal
 * distribution of shape k from two RandGaussT numbers. The static shoot() without a shape takes k = 1
 * (a distribution made without one, 0), as in CLHEP.
 */
public class RandSkewNormal extends HepRandom {

    private HepRandomEngine localEngine;
    private double shapeParameter;

    public RandSkewNormal(HepRandomEngine anEngine) {
        this(anEngine, 0.);
    }

    public RandSkewNormal(HepRandomEngine anEngine, double shape) {
        localEngine = anEngine;
        shapeParameter = shape;
    }

    /** The copy constructor (C++'s implicit one): the same parameters and state, the engine shared. */
    public RandSkewNormal(RandSkewNormal d) {
        copyFrom(d);
    }

    /** operator= (C++'s implicit one): d's parameters and state, and its engine, shared. */
    public RandSkewNormal assign(RandSkewNormal d) {
        copyFrom(d);
        return this;
    }

    private void copyFrom(RandSkewNormal d) {
        localEngine = d.localEngine;
        shapeParameter = d.shapeParameter;
    }

    public static double shoot() {
        final HepRandomEngine anEngine = HepRandom.getTheEngine();
        final double k = 1;
        return gaussianSkewNormal(anEngine, k);
    }

    public static double shoot(double shape) {
        final HepRandomEngine anEngine = HepRandom.getTheEngine();
        return gaussianSkewNormal(anEngine, shape);
    }

    public static void shootArray(int size, double[] vect) {
        shootArray(size, vect, 0.);
    }

    public static void shootArray(int size, double[] vect, double shape) {
        for (int i = 0; i < size; i++) vect[i] = shoot(shape);
    }

    public static double shoot(HepRandomEngine anEngine) {
        final double k = 1;
        return gaussianSkewNormal(anEngine, k);
    }

    public static double shoot(HepRandomEngine anEngine, double shape) {
        return gaussianSkewNormal(anEngine, shape);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect) {
        shootArray(anEngine, size, vect, 0.);
    }

    public static void shootArray(HepRandomEngine anEngine, int size, double[] vect, double shape) {
        for (int i = 0; i < size; i++) vect[i] = shoot(anEngine, shape);
    }

    public double fire() {
        return gaussianSkewNormal(getLocalEngine(), getShapeParameter());
    }

    public double fire(double shape) {
        return gaussianSkewNormal(getLocalEngine(), shape);
    }

    public void fireArray(int size, double[] vect) {
        for (int i = 0; i < size; i++) vect[i] = fire(shapeParameter);
    }

    public void fireArray(int size, double[] vect, double shape) {
        for (int i = 0; i < size; i++) vect[i] = fire(shape);
    }

    @Override
    public double call() {
        return fire(shapeParameter);
    }

    public double call(double shape) {
        return fire(shape);
    }

    /** Two normal numbers u0, u1 made into delta u0 + sqrt(1 - delta²) u1, of u0's sign. */
    protected static double gaussianSkewNormal(HepRandomEngine e, double k) {
        final double[] u = {0., 0.};
        RandGaussT.shootArray(e, 2, u, 0, 1);
        final double delta = k / Math.sqrt(1. + k * k);
        final double u1 = delta * u[0] + Math.sqrt(1 - delta * delta) * u[1];
        final double r = u[0] >= 0 ? u1 : -u1;
        return r;
    }

    protected double getShapeParameter() {
        return shapeParameter;
    }

    protected HepRandomEngine getLocalEngine() {
        return localEngine;
    }

    @Override
    public String name() {
        return "RandSkewNormal";
    }

    @Override
    public HepRandomEngine engine() {
        return localEngine;
    }

    public static String distributionName() {
        return "RandSkewNormal";
    }

    @Override
    public COStream put(COStream os) {
        final int pr = os.precision();
        os.precision(20);
        os.put(" ").put(name()).put("\n");
        os.put("Uvec").put("\n");
        DistIO.put(os, shapeParameter);
        os.precision(pr);
        return os;
    }

    @Override
    public CIStream get(CIStream is) {
        if (!DistIO.expect(is, name())) return is;
        final String w = is.nextWord();
        if (w.equals("Uvec")) {
            shapeParameter = DistIO.get(is);
            return is;
        }
        shapeParameter = DistIO.number(w);
        return is;
    }
}

package com.sphere.core.clhep.vector;

import com.sphere.core.hepmc3.cxx.StdStreams;

/**
 * CLHEP's vector exceptions (Vector/ZMxpv.h, built without ZOOM exceptions): one class, its C++ name
 * kept ({@code ZMxpvZeroVector}...). As in C++, {@link #throwA} writes the exception on cerr and throws
 * it; {@link #throwC} only writes it, and the computation goes on with the value CLHEP chose.
 */
public class ZMxpv extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String PHYSICS_VECTORS = "ZMxPhysicsVectors";
    public static final String SPACELIKE = "ZMxpvSpacelike";
    public static final String NEGATIVE_MASS = "ZMxpvNegativeMass";
    public static final String VECTOR_INPUT_FAILS = "ZMxpvVectorInputFails";
    public static final String INDEX_RANGE = "ZMxpvIndexRange";
    public static final String FIXED_AXIS = "ZMxpvFixedAxis";
    public static final String TACHYONIC = "ZMxpvTachyonic";
    public static final String ZERO_VECTOR = "ZMxpvZeroVector";
    public static final String IMPROPER_TRANSFORMATION = "ZMxpvImproperTransformation";
    public static final String INFINITE_VECTOR = "ZMxpvInfiniteVector";
    public static final String INFINITY = "ZMxpvInfinity";
    public static final String IMPROPER_ROTATION = "ZMxpvImproperRotation";
    public static final String AMBIGUOUS_ANGLE = "ZMxpvAmbiguousAngle";
    public static final String NEGATIVE_R = "ZMxpvNegativeR";
    public static final String UNUSUAL_THETA = "ZMxpvUnusualTheta";
    public static final String PARALLEL_COLS = "ZMxpvParallelCols";
    public static final String NOT_ORTHOGONAL = "ZMxpvNotOrthogonal";
    public static final String NOT_SYMPLECTIC = "ZMxpvNotSymplectic";

    private final String name;
    private final String text;

    public ZMxpv(String name, String message) {
        super(name + ": " + message);
        this.name = name;
        this.text = message;
    }

    /** The class name of the C++ exception. */
    public String name() {
        return name;
    }

    /** what(): the name, a colon and the message. */
    public String what() {
        return name + ": " + text;
    }

    /** ZMthrowA: written on cerr, then thrown. {@code where} is the C++ function it comes from. */
    public static ZMxpv throwA(String name, String message, String where) {
        final ZMxpv e = new ZMxpv(name, message);
        StdStreams.cerr().print(name + " thrown:\n" + e.what() + "\nin " + where + "\n");
        throw e;
    }

    /** ZMthrowC: written on cerr; the caller goes on. */
    public static void throwC(String name, String message, String where) {
        final ZMxpv e = new ZMxpv(name, message);
        StdStreams.cerr().print(name + ":\n" + e.what() + "\nin " + where + "\n");
    }
}

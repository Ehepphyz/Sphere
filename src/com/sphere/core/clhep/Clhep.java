package com.sphere.core.clhep;

/**
 * CLHEP, the Class Library for High Energy Physics, in Java: the version translated and the port's. The
 * packages are those of the C++: units, vector, geometry, matrix, random, randomobjects, evaluator, genfun
 * (GenericFunctions, its special functions from this machine's GSL) and fields. Each gives the C++'s bits,
 * as the references under validation show; the references to cite are in {@link ClhepCitations}.
 */
public final class Clhep {

    /** The release translated, as CLHEP::Version::String() says it. */
    public static final String VERSION = com.sphere.Sphere.CLHEP_VERSION;
    /** Sphere's Java port. */
    public static final String JAVA_PORT_VERSION = com.sphere.Sphere.CLHEP_JAVA_PORT_VERSION;
    public static final String VERSION_LINE = "CLHEP " + VERSION + " Java Sphere " + JAVA_PORT_VERSION;

    private Clhep() {
    }
}

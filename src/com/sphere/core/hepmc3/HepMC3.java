package com.sphere.core.hepmc3;

/**
 * Version of the Java port of HepMC3, the event record of Monte Carlo event
 * generators. Its credits and references are in {@link HepMC3Citations}.
 *
 * <p>Translated from HepMC3 3.03.01 (the master branch of October 2026, the
 * 3.3.2 ChangeLog): the event record (GenEvent, GenParticle, GenVertex,
 * FourVector, Units), its attributes (GenCrossSection, GenHeavyIon,
 * GenPdfInfo, the typed attributes, GenRunInfo), the readers and writers
 * (Asciiv3, HepMC2 IO_GenEvent, HEPEVT, LHEF, protobuf, gzip and zstd,
 * the reader factory and the threaded reader), the Les Houches Event File
 * library LHEF.h, the printing, and the search library (relatives,
 * selectors, filters).
 */
public final class HepMC3 {

    /** The HepMC3 release translated, as HEPMC3_VERSION says it (kept in Sphere.java with every other version). */
    public static final String VERSION = com.sphere.Sphere.HEPMC3_VERSION;

    /** HEPMC3_VERSION_CODE: 1000000*major + 1000*minor + patch. */
    public static final int VERSION_CODE = com.sphere.Sphere.HEPMC3_VERSION_CODE;

    /** The version of the port itself. */
    public static final String JAVA_PORT_VERSION = com.sphere.Sphere.HEPMC3_JAVA_PORT_VERSION;

    public static final String VERSION_LINE = "HepMC3 " + VERSION + " Java Sphere " + JAVA_PORT_VERSION;

    private HepMC3() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** HepMC3::version(): the string the writers put in their headers. */
    public static String version() {
        return VERSION;
    }
}

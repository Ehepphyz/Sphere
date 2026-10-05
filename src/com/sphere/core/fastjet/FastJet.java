package com.sphere.core.fastjet;

import com.sphere.Sphere;

/**
 * Version of the Java port. Its credits and references are in Citations.
 */
public final class FastJet {

    /** The FastJet release this port follows (kept in Sphere.java with every other version). */
    public static final String FASTJET_VERSION = Sphere.FASTJET_VERSION;
    /** The version of the port itself. */
    public static final String JAVA_PORT_VERSION = Sphere.FASTJET_JAVA_PORT_VERSION;
    /** All that Sphere prints about FastJet; the references are in Citations. */
    public static final String VERSION_LINE = "FastJet Java Sphere v" + JAVA_PORT_VERSION;

    private FastJet() {
    }

    public static String versionString() {
        return "FastJet version " + FASTJET_VERSION + " (" + VERSION_LINE + ")";
    }

    /** Printed on the first clustering in place of FastJet's banner. */
    public static String banner() {
        return VERSION_LINE;
    }
}

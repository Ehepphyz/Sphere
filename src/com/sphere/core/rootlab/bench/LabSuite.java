package com.sphere.core.rootlab.bench;

import java.util.List;

/**
 * A group of tests ported from one program of ROOT's test/ directory.
 *
 * The tests of a suite run in order, on one thread, and may share state:
 * stressHistogram's reference tests draw from a single random sequence, so
 * the second test only gets the right numbers after the first has drawn its
 * own. tests() builds them afresh for every run, so a second run starts clean.
 */
public interface LabSuite {

    /** A short identifier, the name of the ROOT program it comes from where there is one. */
    String id();

    /** The title ROOT prints for it. */
    String title();

    /** Where it comes from in ROOT's sources. */
    String origin();

    /** What it checks and why it matters for reading CERN data, in a sentence or two. */
    String description();

    /** True when it reads files over the network (from root.cern). */
    default boolean network() {
        return false;
    }

    List<LabTest> tests();
}

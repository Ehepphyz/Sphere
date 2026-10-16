package com.sphere.core.rootlab.bench;

/** How a test of the ROOT Lab ended. */
public enum LabStatus {
    PASSED("OK"),
    FAILED("FAILED"),
    SKIPPED("skipped"),
    ERROR("ERROR");

    /** The word ROOT's stress programs print at the end of a line. */
    public final String word;

    LabStatus(String word) {
        this.word = word;
    }
}

package com.sphere.core.rootlab.bench;

/** One test of a suite: an id, the title ROOT gives it, and what it does. */
public record LabTest(String id, String title, Body body) {

    /** The test itself; it fails by throwing LabFailure, is skipped by throwing LabSkip. */
    @FunctionalInterface
    public interface Body {
        void run(LabContext ctx) throws Exception;
    }
}

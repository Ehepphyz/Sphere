package com.sphere.core.rootlab.bench;

/** Thrown by a test that cannot run here (no network, a server down): skipped, not failed. */
public final class LabSkip extends Exception {

    public LabSkip(String reason) {
        super(reason);
    }
}

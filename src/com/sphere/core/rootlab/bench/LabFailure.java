package com.sphere.core.rootlab.bench;

import java.util.ArrayList;
import java.util.List;

/** Thrown by a test that ran and found something wrong; the details say what. */
public final class LabFailure extends Exception {

    public final List<String> details;

    public LabFailure(String message) {
        this(message, List.of());
    }

    public LabFailure(String message, List<String> details) {
        super(message);
        this.details = new ArrayList<>(details);
    }
}

package com.sphere.core.rootlab.bench;

import java.util.List;

/** The outcome of one test: its status, a one-line message, the details, and how long it took. */
public record LabResult(String suiteId, String testId, String title, LabStatus status, String message,
                        List<String> details, long nanos) {

    public double seconds() {
        return nanos / 1e9;
    }
}

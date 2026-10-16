package com.sphere.core.rootlab.bench;

import com.sphere.core.rootlab.suites.ArchiveSuite;
import com.sphere.core.rootlab.suites.CompressionSuite;
import com.sphere.core.rootlab.suites.HistViewerSuite;
import com.sphere.core.rootlab.suites.IntegritySuite;
import com.sphere.core.rootlab.suites.RandomSuite;
import com.sphere.core.rootlab.suites.StressHistogramSuite;
import com.sphere.core.rootlab.suites.StressIOPluginsSuite;
import com.sphere.core.rootlab.suites.StressLinearSuite;

import java.util.List;

/** Every suite of the ROOT Lab, in the order they are shown and run. */
public final class LabCatalog {

    private LabCatalog() {
    }

    public static List<LabSuite> all() {
        return List.of(
            new StressHistogramSuite(),
            new StressLinearSuite(),
            new HistViewerSuite(),
            new CompressionSuite(),
            new RandomSuite(),
            new IntegritySuite(),
            new ArchiveSuite(),
            new StressIOPluginsSuite());
    }

    /** The suite of that id, or null. */
    public static LabSuite find(String id) {
        for (LabSuite s : all()) {
            if (s.id().equalsIgnoreCase(id)) {
                return s;
            }
        }
        return null;
    }
}

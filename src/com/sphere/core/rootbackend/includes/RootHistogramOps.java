package com.sphere.core.rootbackend.includes;

import com.sphere.core.rootbackend.RootObjects.RootHistogram;
import com.sphere.core.rootbackend.RootProcessBridge;
import com.sphere.utils.AppLogger;

/**
 * Handles operations and safe analytical manipulations directly on ROOT histograms.
 * Operates asynchronously over the FFM lock-free SHM command ring.
 */
public final class RootHistogramOps {

    private final RootProcessBridge bridge;
    private final RootHistogram histogram;

    public RootHistogramOps(RootProcessBridge bridge, RootHistogram histogram) {
        this.bridge = bridge;
        this.histogram = histogram;
    }

    /**
     * Safely rebins the histogram by the given grouping factor.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean rebin(int factor) {
        if (factor <= 0) {
            AppLogger.error("Invalid rebin factor specified: " + factor);
            return false;
        }

        String clingCmd = String.format(
            "{ TH1* h = (TH1*)gROOT->FindObject(\"%s\"); " +
            "  if (h) { " +
            "    h->Rebin(%d); " +
            "    std::cout << \"OK\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR HistogramNotFound\\n\"; " +
            "  } }",
            histogram.getHandleId(), factor
        );
        return pushClingCommand(clingCmd, "rebin histogram with factor: " + factor);
    }

    /**
     * Safely scales the weights of all histogram bins by a constant factor.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean scale(double factor) {
        String clingCmd = String.format(
            "{ TH1* h = (TH1*)gROOT->FindObject(\"%s\"); " +
            "  if (h) { " +
            "    h->Scale(%f); " +
            "    std::cout << \"OK\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR HistogramNotFound\\n\"; " +
            "  } }",
            histogram.getHandleId(), factor
        );
        return pushClingCommand(clingCmd, "scale histogram with factor: " + factor);
    }

    /**
     * Queues a request to retrieve descriptive statistics for the histogram.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean requestStatistics() {
        String clingCmd = String.format(
            "{ TH1* h = (TH1*)gROOT->FindObject(\"%s\"); " +
            "  if (h) { " +
            "    std::cout << \"OK Mean=\" << h->GetMean() " +
            "              << \" RMS=\" << h->GetRMS() " +
            "              << \" Integral=\" << h->Integral() " +
            "              << \" Entries=\" << h->GetEntries() << \"\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR HistogramNotFound\\n\"; " +
            "  } }",
            histogram.getHandleId()
        );
        return pushClingCommand(clingCmd, "request statistics for histogram: " + histogram.getHandleId());
    }

    /**
     * Queues a request to compute the integral of the histogram bins within a specific range.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean requestIntegral(int binMin, int binMax) {
        String clingCmd = String.format(
            "{ TH1* h = (TH1*)gROOT->FindObject(\"%s\"); " +
            "  if (h) { " +
            "    std::cout << \"OK \" << h->Integral(%d, %d) << \"\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR HistogramNotFound\\n\"; " +
            "  } }",
            histogram.getHandleId(), binMin, binMax
        );
        return pushClingCommand(clingCmd, "request integral for bin range [" + binMin + ", " + binMax + "]");
    }

    private boolean pushClingCommand(String clingCmd, String actionDescription) {
        boolean queued = bridge.pushCommand("CLING_EXEC " + clingCmd);
        if (!queued) {
            AppLogger.error("Failed to queue SHM command to " + actionDescription);
        }
        return queued;
    }
}
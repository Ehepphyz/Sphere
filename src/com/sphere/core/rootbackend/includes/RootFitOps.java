package com.sphere.core.rootbackend.includes;

import com.sphere.core.rootbackend.RootObjects.RootHistogram;
import com.sphere.core.rootbackend.RootProcessBridge;
import com.sphere.utils.AppLogger;

/**
 * High-level analysis engine for executing fits on ROOT histograms.
 * Operates asynchronously over the FFM lock-free SHM command ring.
 */
public final class RootFitOps {

    private final RootProcessBridge bridge;
    private final RootHistogram histogram;

    public RootFitOps(RootProcessBridge bridge, RootHistogram histogram) {
        this.bridge = bridge;
        this.histogram = histogram;
    }

    /**
     * Fits a Gaussian distribution ("gaus") to the histogram.
     * Passes the "Q" (Quiet) flag to suppress unnecessary console output.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean fitGaussian() {
        return executeSafeFit("\"gaus\"", "\"Q\"");
    }

    /**
     * Fits an exponential distribution ("expo") to the histogram.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean fitExpo() {
        return executeSafeFit("\"expo\"", "\"Q\"");
    }

    /**
     * Fits a polynomial of a specified degree to the histogram.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean fitPolynomial(int degree) {
        int validDegree = Math.max(degree, 0);
        return executeSafeFit("\"pol" + validDegree + "\"", "\"Q\"");
    }

    /**
     * Safely defines a custom TF1 formula. If a function with the target name 
     * already exists, it is cleaned up first to prevent Cling JIT redefinition errors.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean fitCustom(String tf1Name, String formula, double min, double max) {
        String clingCmd = String.format(
            "{ TF1* existing = (TF1*)gROOT->GetFunction(\"%s\"); " +
            "  if (existing) { delete existing; } " +
            "  auto* %s = new TF1(\"%s\", \"%s\", %f, %f); }",
            tf1Name, tf1Name, tf1Name, formula, min, max
        );
        return pushClingCommand(clingCmd, "define custom TF1: " + tf1Name);
    }

    /**
     * Applies a previously defined custom TF1 function to this histogram.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean applyCustom(String tf1Name) {
        return executeSafeFit("\"" + tf1Name + "\"", "\"Q\"");
    }

    /**
     * Helper to dispatch fit commands safely over the SHM pipeline.
     */
    private boolean executeSafeFit(String formulaExpr, String options) {
        String clingCmd = String.format(
            "{ TH1* h = (TH1*)gROOT->FindObject(\"%s\"); " +
            "  if (h) { " +
            "    h->Fit(%s, %s); " +
            "    std::cout << \"OK\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR HistogramNotFound\\n\"; " +
            "  } }",
            histogram.getHandleId(), formulaExpr, options
        );
        return pushClingCommand(clingCmd, "execute fit: " + formulaExpr);
    }

    /**
     * Queues an analytical query to request the value of a specific parameter after a fit completes.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean requestFitParameter(String functionName, int parameterIndex) {
        String clingCmd = String.format(
            "{ TF1* f = (TF1*)gROOT->GetFunction(\"%s\"); " +
            "  if (f && %d < f->GetNpar()) { " +
            "    std::cout << \"OK \" << f->GetParameter(%d) << \"\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR InvalidParameterOrFunction\\n\"; " +
            "  } }",
            functionName, parameterIndex, parameterIndex
        );
        return pushClingCommand(clingCmd, "request fit parameter index " + parameterIndex + " for function " + functionName);
    }

    private boolean pushClingCommand(String clingCmd, String actionDescription) {
        boolean queued = bridge.pushCommand("CLING_EXEC " + clingCmd);
        if (!queued) {
            AppLogger.error("Failed to queue SHM command to " + actionDescription);
        }
        return queued;
    }
}
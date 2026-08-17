package com.sphere.core.rootbackend.includes;

import com.sphere.core.rootbackend.RootObjects.RootHistogram;
import com.sphere.core.rootbackend.RootProcessBridge;
import com.sphere.utils.AppLogger;

/**
 * Handles canvas operations and graphics rendering contexts in ROOT.
 * Operates asynchronously over the FFM lock-free SHM command ring.
 */
public final class RootCanvasOps {

    private final RootProcessBridge bridge;
    private final RootHistogram histogram;

    public RootCanvasOps(RootProcessBridge bridge, RootHistogram histogram) {
        this.bridge = bridge;
        this.histogram = histogram;
    }

    /**
     * Safely creates a TCanvas. If a canvas with the same name already exists in ROOT's
     * global registry, it cleans it up first to avoid variable redefinition conflicts in Cling.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean createCanvas(String canvasName) {
        String clingCmd = String.format(
            "{ TCanvas* existing = (TCanvas*)gROOT->GetListOfCanvases()->FindObject(\"%s\"); " +
            "  if (existing) { delete existing; } " +
            "  auto* %s = new TCanvas(\"%s\", \"%s\", 800, 600); }",
            canvasName, canvasName, canvasName, canvasName
        );
        return pushClingCommand(clingCmd, "create canvas: " + canvasName);
    }

    /**
     * Safely changes context to the target canvas, draws the assigned histogram,
     * and forces a paint update of the graphics window.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean draw(String canvasName) {
        String clingCmd = String.format(
            "{ TCanvas* c = (TCanvas*)gROOT->GetListOfCanvases()->FindObject(\"%s\"); " +
            "  TH1* h = (TH1*)gROOT->FindObject(\"%s\"); " + 
            "  if (c && h) { " +
            "    c->cd(); " +
            "    h->Draw(); " +
            "    c->Update(); " +
            "    std::cout << \"OK\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR CanvasOrHistogramNotFound\\n\"; " +
            "  } }",
            canvasName, histogram.getHandleId()
        );
        return pushClingCommand(clingCmd, "draw histogram on canvas: " + canvasName);
    }

    /**
     * Safely exports the canvas to an image or document format (e.g., PNG, PDF, SVG, C).
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean saveAs(String canvasName, String path) {
        String clingCmd = String.format(
            "{ TCanvas* c = (TCanvas*)gROOT->GetListOfCanvases()->FindObject(\"%s\"); " +
            "  if (c) { " +
            "    c->SaveAs(\"%s\"); " +
            "    std::cout << \"OK\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR CanvasNotFound\\n\"; " +
            "  } }",
            canvasName, path
        );
        return pushClingCommand(clingCmd, "export canvas: " + canvasName + " to path: " + path);
    }

    /**
     * Clears all primitives (histograms, functions, legends) drawn on the specified canvas.
     *
     * @return {@code true} if the command was successfully queued in the SHM ring; {@code false} otherwise.
     */
    public boolean clear(String canvasName) {
        String clingCmd = String.format(
            "{ TCanvas* c = (TCanvas*)gROOT->GetListOfCanvases()->FindObject(\"%s\"); " +
            "  if (c) { " +
            "    c->Clear(); " +
            "    c->Update(); " +
            "    std::cout << \"OK\\n\"; " +
            "  } else { " +
            "    std::cout << \"ERROR CanvasNotFound\\n\"; " +
            "  } }",
            canvasName
        );
        return pushClingCommand(clingCmd, "clear canvas: " + canvasName);
    }

    private boolean pushClingCommand(String clingCmd, String actionDescription) {
        boolean queued = bridge.pushCommand("CLING_EXEC " + clingCmd);
        if (!queued) {
            AppLogger.error("Failed to queue SHM command to " + actionDescription);
        }
        return queued;
    }
}
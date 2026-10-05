package com.sphere.components.rootview;

import java.util.Map;

/**
 * What a canvas asks of the window it is in when a function of ROOT's
 * context menu needs more than the canvas: a new canvas for what a function
 * returns (a projection, a clone, a rebinned copy), a page of text for what
 * it prints (Dump, Print, Map), the status line, and the other objects open
 * for a function that takes one (TProfile::Add(h1, h2)).
 */
public interface RootHost {

    /** Opens a canvas of its own: what DrawClone, ProjectionX, Rebin into a new name and the like return. */
    void open(RootScene scene, String title);

    /** Shows what a function printed. */
    void show(String title, String text);

    void status(String text);

    /** The histograms and graphs open elsewhere, by the label to show, for the functions that take another object. */
    default Map<String, Object> objects(String baseClass) {
        return Map.of();
    }

    /** ROOT's fit panel for an object; the host may offer its own analysis instead. */
    default boolean fitPanel(RootTarget target) {
        return false;
    }
}

package com.sphere.components.rootview;

import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;

import java.awt.Point;

/**
 * What a right click found, as TCanvas's picking finds it: the object, its
 * ROOT class, the pad it lies in, and where the mouse was, in the data's
 * coordinates too, for the functions that act where the user pointed
 * (TGraph::InsertPoint, TLegend::DeleteEntry...).
 */
public final class RootTarget {

    public final String className;
    public final Object object;
    public final Pad pad;
    public final RootScene scene;
    public final RootCanvasView canvas;
    public final Point at;
    /** The mouse in the frame's coordinates, NaN outside a frame. */
    public final double x;
    public final double y;

    RootTarget(String className, Object object, Pad pad, RootScene scene, RootCanvasView canvas, Point at, double x,
               double y) {
        this.className = className == null || className.isBlank() ? "TObject" : className;
        this.object = object;
        this.pad = pad;
        this.scene = scene;
        this.canvas = canvas;
        this.at = at;
        this.x = x;
        this.y = y;
    }

    /** The object's name, as ROOT shows it after the class in the menu's title. */
    public String name() {
        if (object instanceof Item i) return i.name == null || i.name.isBlank() ? i.className : i.name;
        if (object instanceof RootPadPainter.AxisRef a) return a.axis() == 0 ? "xaxis" : a.axis() == 1 ? "yaxis" : "zaxis";
        if (object instanceof RootPadPainter.StatsRef) return "stats";
        if (object instanceof RootPadPainter.TitleRef) return "title";
        if (object instanceof RootPadPainter.PaletteRef) return "palette";
        if (object instanceof Pad p) return p.name == null || p.name.isBlank() ? "pad" : p.name;
        if (object instanceof RootScene s) return s.name == null || s.name.isBlank() ? "c1" : s.name;
        return className;
    }

    /** "TH1F::hpx", the title of ROOT's context menu. */
    public String title() {
        return className + "::" + name();
    }

    /** The item itself, when the target is one. */
    public Item item() {
        if (object instanceof Item i) return i;
        if (object instanceof RootPadPainter.AxisRef a) return a.owner();
        if (object instanceof RootPadPainter.StatsRef s) return s.hist();
        if (object instanceof RootPadPainter.PaletteRef p) return p.hist();
        if (object instanceof RootPadPainter.TitleRef t) return t.owner();
        return pad == null ? null : pad.main();
    }
}

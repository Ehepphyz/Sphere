package com.sphere.components.g4editor;

import com.sphere.core.geant4.BBox;
import com.sphere.core.geant4.Budget;
import com.sphere.core.geant4.Extruded;
import com.sphere.core.geant4.GeometryException;
import com.sphere.core.geant4.Mesh;
import com.sphere.core.geant4.PhysicalVolume;
import com.sphere.core.geant4.Placer;
import com.sphere.core.geant4.Solid;
import com.sphere.core.geant4.Solids;
import com.sphere.core.geant4.Units;
import com.sphere.core.geant4.Vec3;
import com.sphere.core.geant4.Xform;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The drawing tools: each turns clicks on the work plane, mouse movements
 * and typed values into a Geant4 solid placed in the right mother.
 */
final class Tools {

    private Tools() {
    }

    /** Where the mouse is: on the work plane (u, v), in the world, and what it snapped to. */
    record Snap(double u, double v, Vec3 world, String kind, Vec3 normal) {
    }

    abstract static class Tool {
        final Viewport vp;
        final DynamicInput input = new DynamicInput();
        int phase;
        Snap last;

        Tool(Viewport vp) {
            this.vp = vp;
        }

        abstract String name();

        abstract String hint();

        /** True while the tool wants the work plane under the mouse. */
        boolean usesPlane() {
            return true;
        }

        void activate() {
            phase = 0;
            fields();
        }

        /** Sets the dynamic input for the current phase. */
        abstract void fields();

        void cancel() {
            phase = 0;
            fields();
            vp.repaint();
        }

        void moved(Snap s, MouseEvent e) {
            last = s;
            update(s, e);
        }

        /** Updates the mouse-driven field values. */
        abstract void update(Snap s, MouseEvent e);

        /** A click takes the step with the mouse (typed fields win). */
        void clicked(Snap s, MouseEvent e) {
            last = s;
            update(s, e);
            step();
        }

        /** Takes the current step: advance, or create. */
        abstract void step();

        boolean key(KeyEvent e) {
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ENTER) {
                step();
                vp.repaint();
                return true;
            }
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                if (phase > 0) {
                    cancel();
                } else {
                    vp.setTool(null);
                }
                return true;
            }
            if (input.key(e)) {
                if (last != null) {
                    update(last, null);
                }
                vp.repaint();
                return true;
            }
            return false;
        }

        void preview(Raster r) {
        }

        void overlay(Graphics2D g) {
        }

        double val(int i) {
            return input.value(i, vp.model.geometry.scope(), vp.model.lengthUnit);
        }

        String expr(int i) {
            return input.expr(i, vp.model.lengthUnit);
        }

        /** A positive expression for a size: the typed one, negated when it was negative. */
        String size(int i) {
            final double v = val(i);
            final String e = expr(i);
            if (v >= 0) {
                return e;
            }
            final DynamicInput.Field f = input.field(i);
            if (f.typed == null) {
                return DynamicInput.format(-v, f.category, vp.model.lengthUnit);
            }
            return "-(" + e + ")";
        }

        static DynamicInput.Field len(String label) {
            return new DynamicInput.Field(label, Units.Category.LENGTH);
        }

        static DynamicInput.Field ang(String label) {
            return new DynamicInput.Field(label, Units.Category.ANGLE);
        }

        /** Builds a solid, places it at a world transform and selects it, as one undo step. */
        void create(String base, Solid s, Xform world, String console) {
            final PhysicalVolume[] made = new PhysicalVolume[1];
            final EditorModel m = vp.model;
            m.edit("Create " + base, () -> made[0] = Placer.create(m.geometry, base, s, m.material, world,
                m.container, m::len));
            if (made[0] != null) {
                m.log(console.replace("$name", made[0].logical().name()).replace("$mother", made[0].mother().name()));
                m.select(made[0], false);
            }
            phase = 0;
            fields();
        }

        Solid temp(String tag) {
            return Solids.create(tag, "preview");
        }

        void drawPreview(Raster r, Solid s, Xform world) {
            try {
                s.evaluate(vp.model.geometry.scope());
                r.mesh(s.mesh(1), world, 0x9046B37A, -1, Raster.FLAG_TRANSPARENT | Raster.FLAG_SELECTED | Raster.FLAG_NO_PICK);
            } catch (GeometryException ignored) {
                // nothing to draw yet
            }
        }

        WorkPlane plane() {
            return vp.plane;
        }

        String at(Vec3 p) {
            final EditorModel m = vp.model;
            return " at " + m.len(p.x()) + " " + m.len(p.y()) + " " + m.len(p.z());
        }
    }

    // ==================================================================
    // Box: corner, opposite corner, height
    // ==================================================================

    static final class BoxTool extends Tool {
        double u1, v1, du, dv;

        BoxTool(Viewport vp) {
            super(vp);
        }

        String name() {
            return "Box";
        }

        String hint() {
            return switch (phase) {
                case 0 -> "Box: click the first corner on the " + plane().name + " plane, or type its coordinates (Tab: next field, Enter: accept)";
                case 1 -> "Box: click the opposite corner, or type length and width";
                default -> "Box: move to set the height and click, or type it; Esc cancels";
            };
        }

        void fields() {
            switch (phase) {
                case 0 -> input.set(len(plane().axisName(true)), len(plane().axisName(false)));
                case 1 -> input.set(len("Length " + plane().axisName(true)), len("Width " + plane().axisName(false)));
                default -> input.set(len("Height"));
            }
        }

        void update(Snap s, MouseEvent e) {
            if (s == null) {
                return;
            }
            switch (phase) {
                case 0 -> {
                    input.field(0).mouse = s.u();
                    input.field(1).mouse = s.v();
                }
                case 1 -> {
                    input.field(0).mouse = s.u() - u1;
                    input.field(1).mouse = s.v() - v1;
                }
                default -> input.field(0).mouse = vp.heightUnderMouse(plane().at(u1 + du / 2, v1 + dv / 2));
            }
        }

        void step() {
            switch (phase) {
                case 0 -> {
                    u1 = val(0);
                    v1 = val(1);
                    phase = 1;
                    fields();
                }
                case 1 -> {
                    du = val(0);
                    dv = val(1);
                    if (Math.abs(du) < 1e-9 || Math.abs(dv) < 1e-9) {
                        return;
                    }
                    lengthExpr = size(0);
                    widthExpr = size(1);
                    phase = 2;
                    fields();
                }
                default -> {
                    final double h = val(0);
                    if (Math.abs(h) < 1e-9) {
                        return;
                    }
                    final Solid s = Solids.create("box", "Box");
                    s.set("x", lengthExpr);
                    s.set("y", widthExpr);
                    s.set("z", size(0));
                    final Vec3 c = plane().at(u1 + du / 2, v1 + dv / 2, h / 2);
                    create("Box", s, plane().frame(c), "box $name " + lengthExpr + " " + widthExpr + " " + size(0)
                        + " " + vp.model.material + at(c));
                }
            }
        }

        String lengthExpr, widthExpr;

        void preview(Raster r) {
            if (phase == 0 || last == null) {
                return;
            }
            final double lu = phase == 1 ? val(0) : du, lv = phase == 1 ? val(1) : dv;
            final double h = phase == 2 ? val(0) : Math.max(vp.camera.pixelSize() * 2, 1e-3);
            if (Math.abs(lu) < 1e-9 || Math.abs(lv) < 1e-9 || Math.abs(h) < 1e-12) {
                return;
            }
            final Solid s = temp("box");
            s.set("x", Units.exact(Math.abs(lu)));
            s.set("y", Units.exact(Math.abs(lv)));
            s.set("z", Units.exact(Math.abs(h)));
            drawPreview(r, s, plane().frame(plane().at(u1 + lu / 2, v1 + lv / 2, h / 2)));
        }
    }

    // ==================================================================
    // Cylinder (G4Tubs): centre, radius (and inner radius), height
    // ==================================================================

    static final class TubeTool extends Tool {
        double cu, cv, r, rin;
        String rExpr, rinExpr;

        TubeTool(Viewport vp) {
            super(vp);
        }

        String name() {
            return "Tube";
        }

        String hint() {
            return switch (phase) {
                case 0 -> "Tube: click the center of the base, or type it";
                case 1 -> "Tube: move to set the radius; Tab to type an inner radius for a hollow tube";
                default -> "Tube: set the length along the plane normal";
            };
        }

        void fields() {
            switch (phase) {
                case 0 -> input.set(len(plane().axisName(true)), len(plane().axisName(false)));
                case 1 -> input.set(len("Radius"), len("Inner radius"));
                default -> input.set(len("Length"));
            }
        }

        void update(Snap s, MouseEvent e) {
            if (s == null) {
                return;
            }
            switch (phase) {
                case 0 -> {
                    input.field(0).mouse = s.u();
                    input.field(1).mouse = s.v();
                }
                case 1 -> {
                    input.field(0).mouse = Math.hypot(s.u() - cu, s.v() - cv);
                    input.field(1).mouse = 0;
                }
                default -> input.field(0).mouse = vp.heightUnderMouse(plane().at(cu, cv));
            }
        }

        void step() {
            switch (phase) {
                case 0 -> {
                    cu = val(0);
                    cv = val(1);
                    phase = 1;
                    fields();
                }
                case 1 -> {
                    r = val(0);
                    rin = val(1);
                    if (r <= 0 || rin < 0 || rin >= r) {
                        return;
                    }
                    rExpr = expr(0);
                    rinExpr = expr(1);
                    phase = 2;
                    fields();
                }
                default -> {
                    final double h = val(0);
                    if (Math.abs(h) < 1e-9) {
                        return;
                    }
                    final Solid s = Solids.create("tube", "Tube");
                    s.set("rmax", rExpr);
                    s.set("rmin", rinExpr);
                    s.set("z", size(0));
                    final Vec3 c = plane().at(cu, cv, h / 2);
                    create("Tube", s, plane().frame(c), "tube $name " + rExpr + " " + size(0) + " rmin=" + rinExpr
                        + " " + vp.model.material + at(c));
                }
            }
        }

        void preview(Raster rr) {
            if (phase == 0 || last == null) {
                return;
            }
            final double rad = phase == 1 ? val(0) : r, ri = phase == 1 ? val(1) : rin;
            final double h = phase == 2 ? val(0) : Math.max(vp.camera.pixelSize() * 2, 1e-3);
            if (rad <= 0 || ri >= rad || Math.abs(h) < 1e-12) {
                return;
            }
            final Solid s = temp("tube");
            s.set("rmax", Units.exact(rad));
            s.set("rmin", Units.exact(Math.max(0, ri)));
            s.set("z", Units.exact(Math.abs(h)));
            drawPreview(rr, s, plane().frame(plane().at(cu, cv, h / 2)));
        }
    }

    // ==================================================================
    // Sphere (G4Orb, or G4Sphere when hollow): centre, radius
    // ==================================================================

    static final class SphereTool extends Tool {
        double cu, cv;

        SphereTool(Viewport vp) {
            super(vp);
        }

        String name() {
            return "Sphere";
        }

        String hint() {
            return phase == 0 ? "Sphere: click the center, or type it" : "Sphere: set the radius (Tab: inner radius for a shell)";
        }

        void fields() {
            if (phase == 0) {
                input.set(len(plane().axisName(true)), len(plane().axisName(false)));
            } else {
                input.set(len("Radius"), len("Inner radius"));
            }
        }

        void update(Snap s, MouseEvent e) {
            if (s == null) {
                return;
            }
            if (phase == 0) {
                input.field(0).mouse = s.u();
                input.field(1).mouse = s.v();
            } else {
                input.field(0).mouse = Math.hypot(s.u() - cu, s.v() - cv);
                input.field(1).mouse = 0;
            }
        }

        void step() {
            if (phase == 0) {
                cu = val(0);
                cv = val(1);
                phase = 1;
                fields();
                return;
            }
            final double r = val(0), ri = val(1);
            if (r <= 0 || ri < 0 || ri >= r) {
                return;
            }
            final Vec3 c = plane().at(cu, cv);
            if (ri > 0) {
                final Solid s = Solids.create("sphere", "Shell");
                s.set("rmax", expr(0));
                s.set("rmin", expr(1));
                create("Shell", s, plane().frame(c), "sphere $name " + expr(0) + " rmin=" + expr(1) + " "
                    + vp.model.material + at(c));
            } else {
                final Solid s = Solids.create("orb", "Sphere");
                s.set("r", expr(0));
                create("Sphere", s, plane().frame(c), "sphere $name " + expr(0) + " " + vp.model.material + at(c));
            }
        }

        void preview(Raster rr) {
            if (phase == 0 || last == null) {
                return;
            }
            final double r = val(0);
            if (r <= 0) {
                return;
            }
            final Solid s = temp("orb");
            s.set("r", Units.exact(r));
            drawPreview(rr, s, plane().frame(plane().at(cu, cv)));
        }
    }

    // ==================================================================
    // Cone (G4Cons): centre, bottom radius, height, top radius
    // ==================================================================

    static final class ConeTool extends Tool {
        double cu, cv, r1, h;
        String r1Expr, hExpr;

        ConeTool(Viewport vp) {
            super(vp);
        }

        String name() {
            return "Cone";
        }

        String hint() {
            return switch (phase) {
                case 0 -> "Cone: click the center of the base";
                case 1 -> "Cone: set the base radius";
                case 2 -> "Cone: set the height";
                default -> "Cone: set the top radius (0 for a point)";
            };
        }

        void fields() {
            switch (phase) {
                case 0 -> input.set(len(plane().axisName(true)), len(plane().axisName(false)));
                case 1 -> input.set(len("Base radius"));
                case 2 -> input.set(len("Height"));
                default -> input.set(len("Top radius"));
            }
        }

        void update(Snap s, MouseEvent e) {
            if (s == null) {
                return;
            }
            switch (phase) {
                case 0 -> {
                    input.field(0).mouse = s.u();
                    input.field(1).mouse = s.v();
                }
                case 1 -> input.field(0).mouse = Math.hypot(s.u() - cu, s.v() - cv);
                case 2 -> input.field(0).mouse = vp.heightUnderMouse(plane().at(cu, cv));
                default -> input.field(0).mouse = Math.hypot(s.u() - cu, s.v() - cv);
            }
        }

        void step() {
            switch (phase) {
                case 0 -> {
                    cu = val(0);
                    cv = val(1);
                    phase = 1;
                    fields();
                }
                case 1 -> {
                    r1 = val(0);
                    if (r1 <= 0) {
                        return;
                    }
                    r1Expr = expr(0);
                    phase = 2;
                    fields();
                }
                case 2 -> {
                    h = val(0);
                    if (Math.abs(h) < 1e-9) {
                        return;
                    }
                    hExpr = size(0);
                    phase = 3;
                    fields();
                }
                default -> {
                    final double r2 = val(0);
                    if (r2 < 0) {
                        return;
                    }
                    final Solid s = Solids.create("cone", "Cone");
                    // A negative height flips the cone: radii swap ends
                    s.set("rmax1", h > 0 ? r1Expr : expr(0));
                    s.set("rmax2", h > 0 ? expr(0) : r1Expr);
                    s.set("rmin1", "0*mm");
                    s.set("rmin2", "0*mm");
                    s.set("z", hExpr);
                    final Vec3 c = plane().at(cu, cv, h / 2);
                    create("Cone", s, plane().frame(c), "cone $name " + r1Expr + " " + expr(0) + " " + hExpr + " "
                        + vp.model.material + at(c));
                }
            }
        }

        void preview(Raster rr) {
            if (phase == 0 || last == null) {
                return;
            }
            final double ra = phase == 1 ? val(0) : r1;
            final double hh = phase == 2 ? val(0) : phase == 3 ? h : Math.max(vp.camera.pixelSize() * 2, 1e-3);
            final double rb = phase == 3 ? val(0) : ra;
            if (ra <= 0 || Math.abs(hh) < 1e-12 || rb < 0) {
                return;
            }
            final Solid s = temp("cone");
            s.set("rmax1", Units.exact(hh > 0 ? ra : rb));
            s.set("rmax2", Units.exact(hh > 0 ? rb : ra));
            s.set("z", Units.exact(Math.abs(hh)));
            drawPreview(rr, s, plane().frame(plane().at(cu, cv, hh / 2)));
        }
    }

    // ==================================================================
    // Torus: centre, swept radius, tube radius
    // ==================================================================

    static final class TorusTool extends Tool {
        double cu, cv, rt;
        String rtExpr;

        TorusTool(Viewport vp) {
            super(vp);
        }

        String name() {
            return "Torus";
        }

        String hint() {
            return switch (phase) {
                case 0 -> "Torus: click the center";
                case 1 -> "Torus: set the swept (ring) radius";
                default -> "Torus: set the tube radius";
            };
        }

        void fields() {
            switch (phase) {
                case 0 -> input.set(len(plane().axisName(true)), len(plane().axisName(false)));
                case 1 -> input.set(len("Ring radius"));
                default -> input.set(len("Tube radius"));
            }
        }

        void update(Snap s, MouseEvent e) {
            if (s == null) {
                return;
            }
            switch (phase) {
                case 0 -> {
                    input.field(0).mouse = s.u();
                    input.field(1).mouse = s.v();
                }
                case 1 -> input.field(0).mouse = Math.hypot(s.u() - cu, s.v() - cv);
                default -> input.field(0).mouse = Math.abs(Math.hypot(s.u() - cu, s.v() - cv) - rt);
            }
        }

        void step() {
            switch (phase) {
                case 0 -> {
                    cu = val(0);
                    cv = val(1);
                    phase = 1;
                    fields();
                }
                case 1 -> {
                    rt = val(0);
                    if (rt <= 0) {
                        return;
                    }
                    rtExpr = expr(0);
                    phase = 2;
                    fields();
                }
                default -> {
                    final double rm = val(0);
                    if (rm <= 0 || rm >= rt) {
                        return;
                    }
                    final Solid s = Solids.create("torus", "Torus");
                    s.set("rtor", rtExpr);
                    s.set("rmax", expr(0));
                    final Vec3 c = plane().at(cu, cv);
                    create("Torus", s, plane().frame(c), "torus $name " + rtExpr + " " + expr(0) + " "
                        + vp.model.material + at(c));
                }
            }
        }

        void preview(Raster rr) {
            if (phase < 2 || last == null) {
                return;
            }
            final double rm = val(0);
            if (rm <= 0 || rm >= rt) {
                return;
            }
            final Solid s = temp("torus");
            s.set("rtor", Units.exact(rt));
            s.set("rmax", Units.exact(rm));
            drawPreview(rr, s, plane().frame(plane().at(cu, cv)));
        }
    }

    // ==================================================================
    // Polygon sketch, extruded (G4ExtrudedSolid)
    // ==================================================================

    static class PolygonTool extends Tool {
        final List<double[]> pts = new ArrayList<>();
        final boolean revolve;

        PolygonTool(Viewport vp, boolean revolve) {
            super(vp);
            this.revolve = revolve;
        }

        String name() {
            return revolve ? "Revolve" : "Extrude";
        }

        String hint() {
            if (phase == 0) {
                return (revolve ? "Revolve: draw the (r, z) profile, r along " + plane().axisName(true) + ", z along "
                    + plane().axisName(false) + "; " : "Extrude: draw the outline; ")
                    + "click or type each vertex, Backspace removes the last, Enter or a click on the first closes";
            }
            return revolve ? "" : "Extrude: set the thickness along the plane normal";
        }

        void fields() {
            if (phase == 0) {
                input.set(len(revolve ? "r" : plane().axisName(true)), len(revolve ? "z" : plane().axisName(false)));
            } else {
                input.set(len("Thickness"));
            }
        }

        @Override
        void activate() {
            pts.clear();
            super.activate();
        }

        @Override
        void cancel() {
            pts.clear();
            super.cancel();
        }

        void update(Snap s, MouseEvent e) {
            if (s == null) {
                return;
            }
            if (phase == 0) {
                double u = s.u(), v = s.v();
                // Shift keeps the new edge horizontal or vertical
                if (e != null && e.isShiftDown() && !pts.isEmpty()) {
                    final double[] p = pts.get(pts.size() - 1);
                    if (Math.abs(u - p[0]) > Math.abs(v - p[1])) {
                        v = p[1];
                    } else {
                        u = p[0];
                    }
                }
                input.field(0).mouse = revolve ? Math.max(0, u) : u;
                input.field(1).mouse = v;
            } else {
                final double[] c = centroid();
                input.field(0).mouse = vp.heightUnderMouse(plane().at(c[0], c[1]));
            }
        }

        double[] centroid() {
            double a = 0, b = 0;
            for (double[] p : pts) {
                a += p[0];
                b += p[1];
            }
            return new double[] {a / Math.max(1, pts.size()), b / Math.max(1, pts.size())};
        }

        @Override
        void clicked(Snap s, MouseEvent e) {
            last = s;
            update(s, e);
            if (phase == 0 && pts.size() >= 3) {
                final double[] first = pts.get(0);
                final double[] scr = vp.screenOf(plane().at(first[0], first[1]));
                if (scr != null && Math.hypot(scr[0] - e.getX(), scr[1] - e.getY()) < 10) {
                    close();
                    return;
                }
            }
            step();
        }

        void step() {
            if (phase == 0) {
                if (input.typing() || last != null) {
                    final double u = val(0), v = val(1);
                    if (pts.isEmpty() || Math.hypot(u - pts.get(pts.size() - 1)[0], v - pts.get(pts.size() - 1)[1]) > 1e-9) {
                        pts.add(new double[] {revolve ? Math.max(0, u) : u, v});
                    }
                    fields();
                }
                return;
            }
            finishExtrude();
        }

        @Override
        boolean key(KeyEvent e) {
            if (phase == 0 && e.getID() == KeyEvent.KEY_PRESSED) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (input.typing()) {
                        step();
                    } else {
                        close();
                    }
                    vp.repaint();
                    return true;
                }
                if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE && !input.typing() && !pts.isEmpty()) {
                    pts.remove(pts.size() - 1);
                    vp.repaint();
                    return true;
                }
            }
            return super.key(e);
        }

        void close() {
            if (pts.size() < 3) {
                return;
            }
            final double[] xy = flat();
            if (!com.sphere.core.geant4.Polygon2D.isSimple(xy)) {
                input.error = "the outline crosses itself";
                return;
            }
            if (revolve) {
                finishRevolve();
            } else {
                phase = 1;
                fields();
            }
        }

        double[] flat() {
            final double[] xy = new double[pts.size() * 2];
            for (int i = 0; i < pts.size(); i++) {
                xy[i * 2] = pts.get(i)[0];
                xy[i * 2 + 1] = pts.get(i)[1];
            }
            return xy;
        }

        void finishExtrude() {
            final double h = val(0);
            if (Math.abs(h) < 1e-9) {
                return;
            }
            final Extruded x = (Extruded) Solids.create("xtru", "Extrusion");
            x.setPolygon(flat());
            final String half = DynamicInput.format(Math.abs(h) / 2, Units.Category.LENGTH, vp.model.lengthUnit);
            final DynamicInput.Field f = input.field(0);
            if (f.typed != null) {
                final String t = size(0);
                x.setSpan("-(" + t + ")/2", "(" + t + ")/2");
            } else {
                x.setSpan("-" + half, half);
            }
            // Vertices are absolute in the plane: the solid sits at the plane origin, lifted by half the thickness
            final Vec3 c = plane().at(0, 0, h / 2);
            create("Extrusion", x, plane().frame(c), "extrude $name " + pts.size() + " vertices, thickness "
                + size(0) + " " + vp.model.material);
            pts.clear();
        }

        void finishRevolve() {
            final Solid s = Solids.create("genericPolycone", "Revolved");
            final Solid.Table t = s.table("rzpoint");
            t.clear();
            for (double[] p : pts) {
                t.add(DynamicInput.format(Math.max(0, p[0]), Units.Category.LENGTH, vp.model.lengthUnit),
                    DynamicInput.format(p[1], Units.Category.LENGTH, vp.model.lengthUnit));
            }
            // Local x along u, local z along v (the axis), y = v x u
            final WorkPlane wp = plane();
            final Vec3 y = wp.v.cross(wp.u);
            final double[] m = {wp.u.x(), y.x(), wp.v.x(), wp.u.y(), y.y(), wp.v.y(), wp.u.z(), y.z(), wp.v.z()};
            final Xform world = new Xform(m, wp.origin.x(), wp.origin.y(), wp.origin.z());
            create("Revolved", s, world, "revolve $name " + pts.size() + " (r,z) points about " + wp.axisName(false));
            pts.clear();
        }

        void preview(Raster r) {
            final List<double[]> ring = new ArrayList<>(pts);
            if (phase == 0 && last != null) {
                ring.add(new double[] {val(0), val(1)});
            }
            if (ring.size() >= 2) {
                final float[] seg = new float[ring.size() * 6];
                int k = 0;
                for (int i = 0; i < ring.size(); i++) {
                    if (i == ring.size() - 1 && phase == 0) {
                        break;
                    }
                    final double[] a = ring.get(i), b = ring.get((i + 1) % ring.size());
                    final Vec3 pa = plane().at(a[0], a[1]), pb = plane().at(b[0], b[1]);
                    seg[k++] = (float) pa.x();
                    seg[k++] = (float) pa.y();
                    seg[k++] = (float) pa.z();
                    seg[k++] = (float) pb.x();
                    seg[k++] = (float) pb.y();
                    seg[k++] = (float) pb.z();
                }
                r.lines(java.util.Arrays.copyOf(seg, k), null, 0xFF1F9D57, 2, false);
            }
            if (revolve && ring.size() >= 3 && phase == 0) {
                final Solid s = temp("genericPolycone");
                final Solid.Table t = s.table("rzpoint");
                t.clear();
                for (double[] p : ring) {
                    t.add(Units.exact(Math.max(0, p[0])), Units.exact(p[1]));
                }
                final WorkPlane wp = plane();
                final Vec3 y = wp.v.cross(wp.u);
                final double[] m = {wp.u.x(), y.x(), wp.v.x(), wp.u.y(), y.y(), wp.v.y(), wp.u.z(), y.z(), wp.v.z()};
                drawPreview(r, s, new Xform(m, wp.origin.x(), wp.origin.y(), wp.origin.z()));
            }
            if (!revolve && phase == 1) {
                final double h = val(0);
                if (Math.abs(h) > 1e-9) {
                    final Extruded x = (Extruded) temp("xtru");
                    x.setPolygon(flat());
                    x.setSpan(Units.exact(-Math.abs(h) / 2), Units.exact(Math.abs(h) / 2));
                    drawPreview(r, x, plane().frame(plane().at(0, 0, h / 2)));
                }
            }
        }

        void overlay(Graphics2D g) {
            g.setColor(new Color(0x1F9D57));
            for (double[] p : pts) {
                final double[] s = vp.screenOf(plane().at(p[0], p[1]));
                if (s != null) {
                    g.fillOval((int) s[0] - 3, (int) s[1] - 3, 7, 7);
                }
            }
        }
    }

    // ==================================================================
    // Measure: distance, components, and material budget between two points
    // ==================================================================

    static final class MeasureTool extends Tool {
        Vec3 a, b;
        Budget.RayReport report;

        MeasureTool(Viewport vp) {
            super(vp);
        }

        String name() {
            return "Measure";
        }

        String hint() {
            return phase == 0 ? "Measure: click a first point (snaps to vertices, surfaces and the grid)"
                : "Measure: click the second point; the radiation lengths crossed between them are shown";
        }

        boolean usesPlane() {
            return true;
        }

        void fields() {
            input.clear();
        }

        void update(Snap s, MouseEvent e) {
            if (phase == 1 && s != null) {
                b = s.world();
            }
        }

        void step() {
            if (last == null) {
                return;
            }
            if (phase == 0) {
                a = last.world();
                b = a;
                report = null;
                phase = 1;
            } else {
                b = last.world();
                final double len = b.sub(a).length();
                if (len > 0) {
                    report = Budget.ray(vp.model.geometry, a, b.sub(a), false);
                    // Keep only the part between the points
                    double x0 = 0, path = 0;
                    for (var seg : report.segments()) {
                        final double from = Math.max(0, seg.from()), to = Math.min(len, seg.to());
                        if (to > from) {
                            final var m = vp.model.geometry.materials().material(seg.lv().material());
                            if (m != null) {
                                x0 += (to - from) / m.radiationLength();
                            }
                            path += to - from;
                        }
                    }
                    vp.model.log("measure " + vp.model.len(a.x()) + " " + vp.model.len(a.y()) + " " + vp.model.len(a.z())
                        + " to " + vp.model.len(b.x()) + " " + vp.model.len(b.y()) + " " + vp.model.len(b.z())
                        + "   # " + Units.best(len, Units.Category.LENGTH) + ", " + String.format("%.4g", x0) + " X0");
                    measuredX0 = x0;
                }
                phase = 0;
            }
        }

        double measuredX0 = -1;

        void preview(Raster r) {
            if (a != null && b != null) {
                r.lines(new float[] {(float) a.x(), (float) a.y(), (float) a.z(), (float) b.x(), (float) b.y(), (float) b.z()},
                    null, 0xFFE0402A, 2, false);
            }
        }

        void overlay(Graphics2D g) {
            if (a == null || b == null) {
                return;
            }
            final double[] sa = vp.screenOf(a), sb = vp.screenOf(b);
            if (sa == null || sb == null) {
                return;
            }
            g.setColor(new Color(0xE0402A));
            g.fillOval((int) sa[0] - 4, (int) sa[1] - 4, 8, 8);
            g.fillOval((int) sb[0] - 4, (int) sb[1] - 4, 8, 8);
            final Vec3 d = b.sub(a);
            final List<String> lines = new ArrayList<>();
            lines.add("distance " + Units.best(d.length(), Units.Category.LENGTH));
            lines.add("dx " + Units.best(d.x(), Units.Category.LENGTH) + "  dy " + Units.best(d.y(), Units.Category.LENGTH)
                + "  dz " + Units.best(d.z(), Units.Category.LENGTH));
            if (phase == 0 && measuredX0 >= 0) {
                lines.add(String.format("material crossed: %.4g X0", measuredX0));
            }
            vp.label(g, (int) ((sa[0] + sb[0]) / 2) + 10, (int) ((sa[1] + sb[1]) / 2) - 10, lines);
        }
    }

    // ==================================================================
    // Move: drag along an axis of the gizmo, or type the displacement
    // ==================================================================

    static final class MoveTool extends Tool {
        int axis = -1;           // 0 x, 1 y, 2 z while dragging
        Vec3 start;              // world origin of the selection at drag start
        double grab;             // axis parameter where the drag began
        boolean rotate;

        MoveTool(Viewport vp, boolean rotate) {
            super(vp);
            this.rotate = rotate;
        }

        String name() {
            return rotate ? "Rotate" : "Move";
        }

        String hint() {
            if (vp.model.selection().isEmpty()) {
                return name() + ": select a volume first";
            }
            return rotate ? "Rotate: drag a ring of the gizmo (15 deg steps, Shift for free), or type an angle and Enter"
                : "Move: drag an arrow of the gizmo, or type a displacement and Enter (Tab: next axis)";
        }

        boolean usesPlane() {
            return false;
        }

        void fields() {
            if (rotate) {
                input.set(ang("about X"), ang("about Y"), ang("about Z"));
            } else {
                input.set(len("dX"), len("dY"), len("dZ"));
            }
        }

        void update(Snap s, MouseEvent e) {
        }

        /** Applies the typed displacement or rotation to the selection, in each mother's frame. */
        void step() {
            if (!input.typing()) {
                return;
            }
            final double a = val(0), b = val(1), c = val(2);
            final EditorModel m = vp.model;
            m.edit(rotate ? "Rotate" : "Move", () -> {
                for (PhysicalVolume pv : m.selection()) {
                    if (rotate) {
                        vp.rotateBy(pv, a, b, c);
                    } else {
                        vp.translateBy(pv, new Vec3(a, b, c));
                    }
                }
            });
            for (PhysicalVolume pv : m.selection()) {
                m.log((rotate ? "rotate " : "move ") + pv.name() + " " + expr(0) + " " + expr(1) + " " + expr(2));
            }
            fields();
        }
    }

    /** All the volumes of the selection: their world bounding box. */
    static BBox selectionBox(Viewport vp) {
        BBox b = BBox.EMPTY;
        for (PhysicalVolume pv : vp.model.selection()) {
            for (var in : vp.model.geometry.instancesOf(pv)) {
                b = b.union(com.sphere.core.geant4.Geometry.worldBox(in));
            }
        }
        return b;
    }

    static void stroke(Graphics2D g, float w) {
        g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    }

    static Mesh unused() {
        return null;
    }
}

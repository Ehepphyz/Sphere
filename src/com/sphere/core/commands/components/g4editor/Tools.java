package com.sphere.components.g4editor;

import com.sphere.core.geant4.BBox;
import com.sphere.core.geant4.Budget;
import com.sphere.core.geant4.Extruded;
import com.sphere.core.geant4.Geometry;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The drawing tools: each turns clicks on the work plane, mouse movements
 * and typed values into a Geant4 solid placed in the right mother.
 *
 * A shape's first two points are taken by two clicks or by one press-drag-release, as a rectangle is
 * drawn anywhere else; what the mouse aims at is read by the drawing assistant (Inference), the preview
 * says where the volume will go and whether it would overlap (LiveCheck), and its sizes show on it.
 */
final class Tools {

    private Tools() {
    }

    /**
     * Where the mouse is: on the work plane (u, v), in the world, what it snapped to, and why: the
     * assistant's label ("Endpoint · Box", "on the X axis") and the guides it draws.
     */
    record Snap(double u, double v, Vec3 world, String kind, Vec3 normal, String label, List<Inference.Guide> guides,
                double rawU, double rawV) {

        Snap(double u, double v, Vec3 world, String kind, Vec3 normal) {
            this(u, v, world, kind, normal, null, List.of(), u, v);
        }

        /** Snapped to a point of the design (a corner, a center, an edge), rather than to the grid or a guide. */
        boolean onGeometry() {
            return switch (kind) {
                case "vertex", "midpoint", "center", "edge", "face" -> true;
                default -> false;
            };
        }
    }

    abstract static class Tool {
        final Viewport vp;
        final DynamicInput input = new DynamicInput();
        int phase;
        Snap last;
        /** Set when the tool made a volume: a sketch that handed its shape over waits for it. */
        boolean created;
        /** What the preview drawn last would do once made (LiveCheck), and the preview it was found for. */
        LiveCheck.Verdict verdict;
        private String verdictKey;
        private boolean previewDrawn;

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
            created = false;
            verdict = null;
            verdictKey = null;
            fields();
        }

        /** Sets the dynamic input for the current phase. */
        abstract void fields();

        void cancel() {
            phase = 0;
            verdict = null;
            verdictKey = null;
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

        // --- what the drawing assistant and the view ask of a tool ------------------------

        /** The points this tool holds (a first corner, a center), for the assistant to snap to and line up with. */
        List<Inference.Ref> references() {
            return List.of();
        }

        /** The last point taken, in plane coordinates: the axes and the 45 degrees are drawn from it; null if none. */
        double[] anchor() {
            return null;
        }

        /** True when a press and drag in this phase draws: the first point at the press, the next at the release. */
        boolean dragDraw() {
            return false;
        }

        /** True when the point about to be taken may move the work plane onto the face clicked. */
        boolean takesFacePlane() {
            return phase == 0;
        }

        /** A left press the tool takes itself; true when it did (the view then neither orbits nor clicks). */
        boolean pressed(MouseEvent e) {
            return false;
        }

        void dragged(MouseEvent e) {
        }

        void released(MouseEvent e) {
        }

        /** The mouse moves over the view: tools that do not draw on the plane follow it here. */
        void hovered(MouseEvent e) {
        }

        /** The dynamic input shown by the cursor: this tool's, or that of the tool it handed its shape to. */
        DynamicInput hud() {
            return input;
        }

        /** The verdict on what is being drawn, when a preview was drawn in the last frame. */
        LiveCheck.Verdict verdict() {
            return verdict;
        }

        /** Draws the preview of a frame; the verdict of a frame without a preview is dropped. */
        void previewFrame(Raster r) {
            previewDrawn = false;
            preview(r);
            if (!previewDrawn) {
                verdict = null;
                verdictKey = null;
            }
        }

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

        /** A positive expression for a size: the typed (or inferred) one, negated when it was negative. */
        String size(int i) {
            final double v = val(i);
            final String e = expr(i);
            if (v >= 0) {
                return e;
            }
            final DynamicInput.Field f = input.field(i);
            if (f.typed == null && f.mouseExpr == null) {
                return DynamicInput.format(-v, f.category, vp.model.lengthUnit);
            }
            if (e.startsWith("-") && e.substring(1).matches("[A-Za-z_][A-Za-z_0-9]*")) {
                return e.substring(1);
            }
            return "-(" + e + ")";
        }

        /** Puts a length the assistant settled on into a field: its value, and its variable when it is one. */
        void put(int i, Inference.Length l) {
            final DynamicInput.Field f = input.field(i);
            if (f != null) {
                f.mouse = l.value();
                f.mouseExpr = l.expr();
                f.hint = l.hint();
            }
        }

        /** True when the point was taken on the design itself, on a guide or an axis: its lengths are kept as they are. */
        boolean exact(Snap s) {
            return vp.snapSuspended() || (s != null && (s.onGeometry() || "guide".equals(s.kind()) || "axis".equals(s.kind())));
        }

        /**
         * A side drawn with the mouse: the variable or the size of the design the mouse is near (read before the
         * grid moved it), else the side between the snapped points.
         */
        Inference.Length side(double snapped, double raw, Vec3 at, Snap s) {
            if (exact(s)) {
                return Inference.Length.plain(snapped);
            }
            final Inference.Length m = vp.inference.match(raw, at);
            return m != null ? m : Inference.Length.plain(snapped);
        }

        /**
         * A radius drawn with the mouse: exact to a point of the design, else a variable or a size it is near,
         * else a round value (a circle need not pass through a grid point).
         */
        Inference.Length radius(double snapped, double raw, Vec3 at, Snap s) {
            if (exact(s)) {
                return Inference.Length.plain(snapped);
            }
            return vp.inference.length(raw, at, vp.gridSnap);
        }

        /** The radius from a center to the snap, as snapped and as the mouse has it. */
        Inference.Length radius(double cu, double cv, Snap s) {
            return radius(Math.hypot(s.u() - cu, s.v() - cv), Math.hypot(s.rawU() - cu, s.rawV() - cv),
                plane().at(s.u(), s.v()), s);
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
                if (verdict != null && verdict.state() == LiveCheck.State.OVERLAP) {
                    m.log("# " + made[0].logical().name() + " " + verdict.describe() + ": F6 moves it clear");
                }
                m.select(made[0], false);
            }
            created = true;
            verdict = null;
            verdictKey = null;
            phase = 0;
            fields();
        }

        Solid temp(String tag) {
            return Solids.create(tag, "preview");
        }

        /** Draws a preview, colored by what it would do once made: green clear, blue touching, red overlapping. */
        void drawPreview(Raster r, Solid s, Xform world) {
            try {
                s.evaluate(vp.model.geometry.scope());
            } catch (GeometryException ignored) {
                return; // nothing to draw yet
            }
            if (s.error() != null) {
                return;
            }
            previewDrawn = true;
            final String key = LiveCheck.key(s, world);
            if (!key.equals(verdictKey)) {
                verdictKey = key;
                verdict = vp.liveCheck ? LiveCheck.of(vp.model.geometry, vp.model.container, s, world) : null;
            }
            final LiveCheck.State st = verdict == null ? LiveCheck.State.CLEAR : verdict.state();
            int flags = Raster.FLAG_TRANSPARENT | Raster.FLAG_NO_PICK;
            if (st == LiveCheck.State.CLEAR) {
                flags |= Raster.FLAG_SELECTED;
            }
            r.mesh(s.mesh(1), world, st.argb, -1, flags);
        }

        WorkPlane plane() {
            return vp.plane;
        }

        String at(Vec3 p) {
            final EditorModel m = vp.model;
            return " at " + m.len(p.x()) + " " + m.len(p.y()) + " " + m.len(p.z());
        }

        static String mm(double v) {
            return Units.best(Math.abs(v), Units.Category.LENGTH);
        }

        /** The direction from a center toward the mouse on the plane, for a radius drawn the way it was pulled. */
        Vec3 toward(double cu, double cv) {
            if (last != null) {
                final double du = last.u() - cu, dv = last.v() - cv, l = Math.hypot(du, dv);
                if (l > 1e-12) {
                    return plane().u.mul(du / l).add(plane().v.mul(dv / l));
                }
            }
            return plane().u;
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
                case 0 -> "Box: click the first corner on the " + plane().name + " plane, or press and drag the base"
                    + " in one stroke; or type its coordinates (Tab: next field, Enter: accept)";
                case 1 -> "Box: click (or release) the opposite corner, or type length and width";
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

        boolean dragDraw() {
            return phase == 0;
        }

        List<Inference.Ref> references() {
            return phase == 0 ? List.of() : List.of(new Inference.Ref(plane().at(u1, v1), "vertex", "first corner"));
        }

        void update(Snap s, MouseEvent e) {
            if (s == null) {
                return;
            }
            switch (phase) {
                case 0 -> {
                    input.field(0).mouse(s.u());
                    input.field(1).mouse(s.v());
                }
                case 1 -> {
                    final Vec3 at = plane().at(s.u(), s.v());
                    put(0, side(s.u() - u1, s.rawU() - u1, at, s));
                    put(1, side(s.v() - v1, s.rawV() - v1, at, s));
                }
                default -> put(0, vp.heightAt(plane().at(u1 + du / 2, v1 + dv / 2)));
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

        void overlay(Graphics2D g) {
            if (phase == 0 || last == null) {
                return;
            }
            final double lu = phase == 1 ? val(0) : du, lv = phase == 1 ? val(1) : dv;
            final Vec3 mid = plane().at(u1 + lu / 2, v1 + lv / 2);
            vp.dim(g, plane().at(u1, v1), plane().at(u1 + lu, v1), mm(lu), mid);
            vp.dim(g, plane().at(u1, v1), plane().at(u1, v1 + lv), mm(lv), mid);
            if (phase == 2) {
                final double h = val(0);
                final Vec3 c = plane().at(u1 + lu, v1);
                vp.dim(g, c, c.add(plane().n.mul(h)), mm(h), mid);
            }
        }
    }

    // ==================================================================
    // Cylinder (G4Tubs): center, radius (and inner radius), height
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
                case 0 -> "Tube: click the center of the base, or press there and drag out the radius";
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

        boolean dragDraw() {
            return phase == 0;
        }

        List<Inference.Ref> references() {
            return phase == 0 ? List.of() : List.of(new Inference.Ref(plane().at(cu, cv), "center", "center"));
        }

        void update(Snap s, MouseEvent e) {
            if (s == null) {
                return;
            }
            switch (phase) {
                case 0 -> {
                    input.field(0).mouse(s.u());
                    input.field(1).mouse(s.v());
                }
                case 1 -> {
                    put(0, radius(cu, cv, s));
                    input.field(1).mouse(0);
                }
                default -> put(0, vp.heightAt(plane().at(cu, cv)));
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

        void overlay(Graphics2D g) {
            if (phase == 0 || last == null) {
                return;
            }
            final double rad = phase == 1 ? val(0) : r;
            final Vec3 c = plane().at(cu, cv), rim = c.add(toward(cu, cv).mul(rad));
            vp.dim(g, c, rim, "R " + mm(rad), null);
            if (phase == 2) {
                vp.dim(g, rim, rim.add(plane().n.mul(val(0))), mm(val(0)), c);
            }
        }
    }

    // ==================================================================
    // Sphere (G4Orb, or G4Sphere when hollow): center, radius
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
            return phase == 0 ? "Sphere: click the center, or press there and drag out the radius"
                : "Sphere: set the radius (Tab: inner radius for a shell)";
        }

        void fields() {
            if (phase == 0) {
                input.set(len(plane().axisName(true)), len(plane().axisName(false)));
            } else {
                input.set(len("Radius"), len("Inner radius"));
            }
        }

        boolean dragDraw() {
            return phase == 0;
        }

        List<Inference.Ref> references() {
            return phase == 0 ? List.of() : List.of(new Inference.Ref(plane().at(cu, cv), "center", "center"));
        }

        void update(Snap s, MouseEvent e) {
            if (s == null) {
                return;
            }
            if (phase == 0) {
                input.field(0).mouse(s.u());
                input.field(1).mouse(s.v());
            } else {
                put(0, radius(cu, cv, s));
                input.field(1).mouse(0);
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

        void overlay(Graphics2D g) {
            if (phase == 0 || last == null) {
                return;
            }
            final Vec3 c = plane().at(cu, cv);
            vp.dim(g, c, c.add(toward(cu, cv).mul(val(0))), "R " + mm(val(0)), null);
        }
    }

    // ==================================================================
    // Cone (G4Cons): center, bottom radius, height, top radius
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
                case 0 -> "Cone: click the center of the base, or press there and drag out its radius";
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

        boolean dragDraw() {
            return phase == 0;
        }

        List<Inference.Ref> references() {
            return phase == 0 ? List.of() : List.of(new Inference.Ref(plane().at(cu, cv), "center", "center"));
        }

        void update(Snap s, MouseEvent e) {
            if (s == null) {
                return;
            }
            switch (phase) {
                case 0 -> {
                    input.field(0).mouse(s.u());
                    input.field(1).mouse(s.v());
                }
                case 1, 3 -> put(0, radius(cu, cv, s));
                default -> put(0, vp.heightAt(plane().at(cu, cv)));
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

        void overlay(Graphics2D g) {
            if (phase == 0 || last == null) {
                return;
            }
            final Vec3 c = plane().at(cu, cv);
            final double ra = phase == 1 ? val(0) : r1;
            final Vec3 rim = c.add(toward(cu, cv).mul(ra));
            vp.dim(g, c, rim, "R " + mm(ra), null);
            if (phase >= 2) {
                final double hh = phase == 2 ? val(0) : h;
                vp.dim(g, rim, rim.add(plane().n.mul(hh)), mm(hh), c);
                if (phase == 3) {
                    final Vec3 top = c.add(plane().n.mul(hh));
                    vp.dim(g, top, top.add(toward(cu, cv).mul(val(0))), "R " + mm(val(0)), null);
                }
            }
        }
    }

    // ==================================================================
    // Torus: center, swept radius, tube radius
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
                case 0 -> "Torus: click the center, or press there and drag out the ring";
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

        boolean dragDraw() {
            return phase == 0;
        }

        List<Inference.Ref> references() {
            return phase == 0 ? List.of() : List.of(new Inference.Ref(plane().at(cu, cv), "center", "center"));
        }

        void update(Snap s, MouseEvent e) {
            if (s == null) {
                return;
            }
            switch (phase) {
                case 0 -> {
                    input.field(0).mouse(s.u());
                    input.field(1).mouse(s.v());
                }
                case 1 -> put(0, radius(cu, cv, s));
                default -> put(0, radius(Math.abs(Math.hypot(s.u() - cu, s.v() - cv) - rt),
                    Math.abs(Math.hypot(s.rawU() - cu, s.rawV() - cv) - rt), plane().at(s.u(), s.v()), s));
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

        void overlay(Graphics2D g) {
            if (phase == 0 || last == null) {
                return;
            }
            final Vec3 c = plane().at(cu, cv);
            final double ring = phase == 1 ? val(0) : rt;
            final Vec3 dir = toward(cu, cv), rim = c.add(dir.mul(ring));
            vp.dim(g, c, rim, "ring R " + mm(ring), null);
            if (phase == 2) {
                vp.dim(g, rim, rim.add(dir.mul(val(0))), "tube r " + mm(val(0)), c);
            }
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

        boolean takesFacePlane() {
            return phase == 0 && pts.isEmpty();
        }

        List<Inference.Ref> references() {
            final List<Inference.Ref> out = new ArrayList<>();
            for (int i = 0; i < pts.size(); i++) {
                out.add(new Inference.Ref(plane().at(pts.get(i)[0], pts.get(i)[1]), "vertex",
                    i == 0 ? "the first point (click it to close)" : "point " + (i + 1)));
            }
            return out;
        }

        double[] anchor() {
            return phase == 0 && !pts.isEmpty() ? pts.get(pts.size() - 1) : null;
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
                input.field(0).mouse(revolve ? Math.max(0, u) : u);
                input.field(1).mouse(v);
            } else {
                final double[] c = centroid();
                put(0, vp.heightAt(plane().at(c[0], c[1])));
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
            if (f.typed != null || f.mouseExpr != null) {
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
            create("Revolved", s, revolveFrame(plane()), "revolve $name " + pts.size() + " (r,z) points about "
                + plane().axisName(false));
            pts.clear();
        }

        /** Local x along u, local z along v (the axis), y = v x u. */
        static Xform revolveFrame(WorkPlane wp) {
            final Vec3 y = wp.v.cross(wp.u);
            final double[] m = {wp.u.x(), y.x(), wp.v.x(), wp.u.y(), y.y(), wp.v.y(), wp.u.z(), y.z(), wp.v.z()};
            return new Xform(m, wp.origin.x(), wp.origin.y(), wp.origin.z());
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
                drawPreview(r, s, revolveFrame(plane()));
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
            if (phase == 0 && !pts.isEmpty() && last != null) {
                // the edge being drawn: its length, and a ring on the first point when a click there closes
                final double[] p = pts.get(pts.size() - 1);
                final double u = val(0), v = val(1);
                vp.dim(g, plane().at(p[0], p[1]), plane().at(u, v), mm(Math.hypot(u - p[0], v - p[1])), null);
                if (pts.size() >= 3) {
                    final double[] f = vp.screenOf(plane().at(pts.get(0)[0], pts.get(0)[1]));
                    final double[] m = vp.screenOf(plane().at(u, v));
                    if (f != null && m != null && Math.hypot(f[0] - m[0], f[1] - m[1]) < 12) {
                        stroke(g, 2f);
                        g.setColor(new Color(0x1F9D57));
                        g.drawOval((int) f[0] - 9, (int) f[1] - 9, 18, 18);
                    }
                }
            }
            if (phase == 1) {
                final double[] c = centroid();
                final Vec3 b = plane().at(c[0], c[1]);
                vp.dim(g, b, b.add(plane().n.mul(val(0))), mm(val(0)), null);
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
            return phase == 0 ? "Measure: click a first point, or press and drag to the second (snaps to corners,"
                + " middles, centers, edges and the grid)"
                : "Measure: click the second point; the radiation lengths crossed between them are shown";
        }

        boolean usesPlane() {
            return true;
        }

        boolean dragDraw() {
            return phase == 0;
        }

        boolean takesFacePlane() {
            return false;
        }

        List<Inference.Ref> references() {
            return phase == 1 && a != null ? List.of(new Inference.Ref(a, "vertex", "first point")) : List.of();
        }

        double[] anchor() {
            return phase == 1 && a != null ? plane().uv(a) : null;
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
    // Move: drag a selected volume (it lands against its neighbors), drag
    // an arrow of the gizmo, or type the displacement; Ctrl copies, and a
    // count typed then makes a row of copies
    // ==================================================================

    static final class MoveTool extends Tool {
        int axis = -1;           // 0 x, 1 y, 2 z while dragging
        Vec3 start;              // world origin of the selection at drag start
        double grab;             // axis parameter where the drag began
        boolean rotate;

        // A selected volume dragged freely
        private boolean dragging, copying;
        private WorkPlane dragPlane;
        private Vec3 grabPoint, delta = Vec3.ZERO;
        private final Map<PhysicalVolume, String[]> startExprs = new LinkedHashMap<>();
        private BBox startBox;
        private final List<BBox> neighborBoxes = new ArrayList<>();
        private final List<String> neighborNames = new ArrayList<>();
        /** What the drag lined up with ("flush against Layer_2 in z"), for the label by the cursor; null when nothing. */
        String contact;
        // The copy just made, and the volumes it copies: a count typed next makes a row of them
        private final List<PhysicalVolume> sources = new ArrayList<>(), copies = new ArrayList<>();
        private Vec3 copyDelta;

        MoveTool(Viewport vp, boolean rotate) {
            super(vp);
            this.rotate = rotate;
        }

        String name() {
            return rotate ? "Rotate" : "Move";
        }

        String hint() {
            if (phase == 1) {
                return "Copied: type 5 and Enter for five in a row (x5), /5 to spread five over the gap; Esc keeps one";
            }
            if (vp.model.selection().isEmpty()) {
                return name() + ": select a volume first";
            }
            return rotate ? "Rotate: drag a ring of the gizmo (15 deg steps, Shift for free), or type an angle and Enter"
                : "Move: drag the volume (it lands flush against its neighbors; Ctrl: drag a copy, Shift: free) or an"
                + " arrow of the gizmo, or type a displacement and Enter (Tab: next axis)";
        }

        boolean usesPlane() {
            return false;
        }

        boolean takesFacePlane() {
            return false;
        }

        void fields() {
            if (phase == 1) {
                final DynamicInput.Field f = new DynamicInput.Field("Copies", Units.Category.NONE);
                f.counts = true;
                f.mouse = 1;
                input.set(f);
            } else if (rotate) {
                input.set(ang("about X"), ang("about Y"), ang("about Z"));
            } else {
                input.set(len("dX"), len("dY"), len("dZ"));
            }
        }

        void update(Snap s, MouseEvent e) {
        }

        @Override
        void cancel() {
            if (dragging) {
                dragging = false;
                vp.model.cancelLive();
            }
            contact = null;
            super.cancel();
        }

        /** Applies the typed displacement or rotation to the selection, in each mother's frame; or makes a row. */
        void step() {
            if (phase == 1) {
                array();
                return;
            }
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

        // --- the selection dragged by hand --------------------------------------------------

        @Override
        boolean pressed(MouseEvent e) {
            final EditorModel m = vp.model;
            if (rotate || m.selection().isEmpty()) {
                return false;
            }
            final Geometry.Instance in = vp.instanceAt(e.getX(), e.getY());
            if (in == null || !m.selection().contains(in.pv())) {
                return false; // the view orbits, as it does outside the selection
            }
            for (PhysicalVolume pv : m.selection()) {
                if (pv.isReplica()) {
                    return false;
                }
            }
            if (phase == 1) {
                phase = 0;
                fields();
            }
            Vec3 p = vp.pointAt(e.getX(), e.getY());
            if (p == null) {
                p = in.world().translation();
            }
            grabPoint = p;
            // Dragged in the work plane's orientation through the point grabbed; seen edge-on, in the view's
            final WorkPlane wp = vp.plane;
            dragPlane = Math.abs(wp.n.dot(vp.camera.back)) > 0.15 ? new WorkPlane(wp.name, p, wp.u, wp.v)
                : new WorkPlane("view", p, vp.camera.right, vp.camera.up);
            startExprs.clear();
            for (PhysicalVolume pv : m.selection()) {
                final String[] pe = pv.positionExprs(), re = pv.rotationExprs();
                startExprs.put(pv, new String[] {pe[0], pe[1], pe[2], re[0], re[1], re[2]});
            }
            startBox = selectionBox(vp);
            neighbors();
            copying = e.isControlDown();
            delta = Vec3.ZERO;
            dragging = true;
            m.checkpoint();
            return true;
        }

        /** The volumes beside the selection (in the same mothers), whose faces the dragged one lands against. */
        private void neighbors() {
            neighborBoxes.clear();
            neighborNames.clear();
            final Geometry g = vp.model.geometry;
            final java.util.Set<PhysicalVolume> sel = vp.model.selection();
            final java.util.Set<Geometry.Instance> mothers = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (PhysicalVolume pv : sel) {
                for (Geometry.Instance in : g.instancesOf(pv)) {
                    if (in.parent() != null) {
                        mothers.add(in.parent());
                    }
                }
            }
            for (Geometry.Instance mi : mothers) {
                // the mother's own walls too: a volume pushed to the side of its envelope stops there
                final BBox mb = Geometry.worldBox(mi);
                if (!mb.isEmpty() && mi.pv() != null) {
                    neighborBoxes.add(mb);
                    neighborNames.add(mi.lv().name());
                }
                for (Geometry.Instance c : g.childrenOf(mi)) {
                    if (!sel.contains(c.pv()) && neighborBoxes.size() < 400) {
                        final BBox b = Geometry.worldBox(c);
                        if (!b.isEmpty()) {
                            neighborBoxes.add(b);
                            neighborNames.add(c.lv().name());
                        }
                    }
                }
            }
        }

        @Override
        void dragged(MouseEvent e) {
            if (!dragging) {
                return;
            }
            final Vec3[] r = vp.camera.ray(e.getX(), e.getY());
            final double[] uv = dragPlane.hit(r[0], r[1]);
            if (uv == null) {
                return;
            }
            final boolean free = e.isShiftDown();
            double du = uv[0], dv = uv[1];
            if (vp.gridSnap && !free) {
                final double s = vp.gridStep();
                du = Math.rint(du / s) * s;
                dv = Math.rint(dv / s) * s;
            }
            Vec3 d = dragPlane.u.mul(du).add(dragPlane.v.mul(dv));
            contact = null;
            if (!free && startBox != null && !startBox.isEmpty()) {
                d = landed(d);
            }
            delta = d;
            final Vec3 dd = d;
            final EditorModel m = vp.model;
            m.live(() -> {
                for (Map.Entry<PhysicalVolume, String[]> en : startExprs.entrySet()) {
                    final String[] s = en.getValue();
                    en.getKey().setPosition(s[0], s[1], s[2]);
                    en.getKey().setRotation(s[3], s[4], s[5]);
                }
                m.geometry.rebuild();
                for (PhysicalVolume pv : startExprs.keySet()) {
                    vp.translateBy(pv, dd);
                }
            });
            vp.status((copying ? "copy " : "move ") + mm(d.length()) + "  (dx " + Units.best(d.x(), Units.Category.LENGTH)
                + ", dy " + Units.best(d.y(), Units.Category.LENGTH) + ", dz " + Units.best(d.z(), Units.Category.LENGTH) + ")"
                + (contact != null ? "   " + contact : ""));
        }

        /**
         * The move adjusted so the selection's box lands flush against a neighbor's face (or lines up with
         * one: same side, same middle) when it comes within a few pixels of it, axis by axis in the drag plane.
         */
        private Vec3 landed(Vec3 d) {
            final double tol = Inference.ALIGN_PX * vp.inference.pixelAt(grabPoint);
            final double[] dv = {d.x(), d.y(), d.z()};
            final double[] lo = {startBox.minX(), startBox.minY(), startBox.minZ()};
            final double[] hi = {startBox.maxX(), startBox.maxY(), startBox.maxZ()};
            final List<String> said = new ArrayList<>();
            for (int a = 0; a < 3; a++) {
                if (Math.abs(Vec3.axis(a).dot(dragPlane.n)) > 0.01) {
                    continue; // not an axis of the drag
                }
                final double mlo = lo[a] + dv[a], mhi = hi[a] + dv[a], mc = (mlo + mhi) / 2;
                double best = tol, shift = 0;
                String what = null;
                int found = -1;
                for (int k = 0; k < neighborBoxes.size(); k++) {
                    final BBox nb = neighborBoxes.get(k);
                    final double[] nlo = {nb.minX(), nb.minY(), nb.minZ()}, nhi = {nb.maxX(), nb.maxY(), nb.maxZ()};
                    // facing each other across the other axes: they can touch
                    boolean facing = true;
                    for (int o = 0; o < 3; o++) {
                        if (o != a && (lo[o] + dv[o] > nhi[o] + tol || hi[o] + dv[o] < nlo[o] - tol)) {
                            facing = false;
                        }
                    }
                    final double[] cand = {nlo[a] - mhi, nhi[a] - mlo, nlo[a] - mlo, nhi[a] - mhi, (nlo[a] + nhi[a]) / 2 - mc};
                    for (int c = 0; c < cand.length; c++) {
                        if (c < 2 && !facing) {
                            continue;
                        }
                        if (Math.abs(cand[c]) < best) {
                            best = Math.abs(cand[c]);
                            shift = cand[c];
                            found = k;
                            what = c < 2 ? "flush against " : c < 4 ? "aligned with " : "centered on ";
                        }
                    }
                }
                if (found >= 0) {
                    dv[a] += shift;
                    said.add(what + neighborNames.get(found) + " in " + "xyz".charAt(a));
                }
            }
            contact = said.isEmpty() ? null : String.join(", ", said);
            return new Vec3(dv[0], dv[1], dv[2]);
        }

        @Override
        void released(MouseEvent e) {
            if (!dragging) {
                return;
            }
            dragging = false;
            final EditorModel m = vp.model;
            if (delta.length() < 1e-12) {
                restoreStart();
                contact = null;
                return;
            }
            if (copying || e.isControlDown()) {
                restoreStart();
                sources.clear();
                sources.addAll(startExprs.keySet());
                copies.clear();
                copyDelta = delta;
                m.edit("Copy", () -> copies.addAll(copyAt(1)));
                for (PhysicalVolume pv : sources) {
                    m.log("copy " + pv.name() + " 1 " + m.len(delta.x()) + " " + m.len(delta.y()) + " " + m.len(delta.z()));
                }
                m.selectAll(new ArrayList<>(copies));
                phase = 1;
                fields();
            } else {
                m.commit("Move");
                for (PhysicalVolume pv : startExprs.keySet()) {
                    final String[] p = pv.positionExprs();
                    m.log("set " + pv.name() + ".position = " + p[0] + " " + p[1] + " " + p[2]);
                }
            }
            contact = null;
        }

        /**
         * Puts the dragged placements back where the drag found them and closes the live change without a step.
         * (Cancelling it would read the document back from its GDML: new objects, and the ones held here stale.)
         */
        private void restoreStart() {
            final EditorModel m = vp.model;
            m.live(() -> {
                for (Map.Entry<PhysicalVolume, String[]> en : startExprs.entrySet()) {
                    final String[] s = en.getValue();
                    en.getKey().setPosition(s[0], s[1], s[2]);
                    en.getKey().setRotation(s[3], s[4], s[5]);
                }
            });
            m.commit("Move");
        }

        /** A copy of each source placement, shifted by k times the copy's move (world), in its mother. */
        private List<PhysicalVolume> copyAt(double k) {
            final Geometry g = vp.model.geometry;
            final List<PhysicalVolume> made = new ArrayList<>();
            for (PhysicalVolume pv : sources) {
                final PhysicalVolume c = g.place(pv.logical(), pv.mother(), g.uniqueName(pv.logical().name() + "_p"));
                final String[] p = pv.positionExprs(), r = pv.rotationExprs();
                c.setPosition(p[0], p[1], p[2]);
                c.setRotation(r[0], r[1], r[2]);
                c.setReflected(pv.reflected());
                vp.translateBy(c, copyDelta.mul(k));
                made.add(c);
            }
            return made;
        }

        /** "5", "x5" or "*5": five copies in a row at the copy's step; "/5": five spread up to the copy. */
        private void array() {
            final DynamicInput.Field f = input.field(0);
            if (f == null || f.typed == null || copyDelta == null || sources.isEmpty()) {
                return;
            }
            String t = f.typed.trim();
            final boolean divide = t.startsWith("/");
            t = t.replaceFirst("^[xX*/]", "").trim();
            final int n;
            try {
                n = (int) Math.round(Double.parseDouble(t));
            } catch (NumberFormatException ex) {
                input.error = "a count: 5, x5 or /5";
                return;
            }
            if (n < 1 || n > 10_000) {
                input.error = "a count between 1 and 10000";
                return;
            }
            final EditorModel m = vp.model;
            final List<PhysicalVolume> all = new ArrayList<>(copies);
            if (n > 1) {
                m.edit(divide ? "Divide" : "Array", () -> {
                    for (int k = divide ? 1 : 2; k <= (divide ? n - 1 : n); k++) {
                        all.addAll(copyAt(divide ? k / (double) n : k));
                    }
                });
                final Vec3 step = divide ? copyDelta.mul(1.0 / n) : copyDelta;
                for (PhysicalVolume pv : sources) {
                    m.log("copy " + pv.name() + " " + n + " " + m.len(step.x()) + " " + m.len(step.y()) + " " + m.len(step.z())
                        + "   # " + (divide ? "the gap divided in " + n : n + " in a row"));
                }
            }
            m.selectAll(all);
            phase = 0;
            fields();
        }

        void overlay(Graphics2D g) {
            if (dragging && contact != null && vp.mouseX() >= 0) {
                vp.tag(g, vp.mouseX() + 16, vp.mouseY() - 26, contact, new Color(0x2AA1C8));
            }
            if (dragging && copying && vp.mouseX() >= 0) {
                vp.tag(g, vp.mouseX() + 16, vp.mouseY() + 12, "+ copy", new Color(0x1F9D57));
            }
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

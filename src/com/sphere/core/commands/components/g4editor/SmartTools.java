package com.sphere.components.g4editor;

import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.PhysicalVolume;
import com.sphere.core.geant4.Solid;
import com.sphere.core.geant4.Solids;
import com.sphere.core.geant4.Units;
import com.sphere.core.geant4.Vec3;
import com.sphere.core.geant4.Xform;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleFunction;

/**
 * The tools that read the hand rather than count its clicks.
 *
 * Sketch: a shape drawn freehand is read (Sketch) and made the solid it looks like: a circle a tube, a
 * rectangle a box, a hexagon a hexagonal prism, a line a plate; Space offers the other readings (a circle is
 * also a sphere, a cone), and the height is pulled as with the other tools.
 *
 * Push/Pull: a face of a volume dragged along its normal, as SketchUp's: the parameter it stands for (a box's
 * length, a tube's radius or length, an extrusion's end) follows, the opposite face stays where it was,
 * and the face stops level with what is around and at the sizes the design has.
 */
final class SmartTools {

    private SmartTools() {
    }

    // ==================================================================
    // A base already known, pulled off the plane (or made as it is)
    // ==================================================================

    /** Makes the solid of a pulled shape for a height (its absolute value, and the expression for it). */
    interface Maker {
        Solid make(double h, String hExpr, String name);
    }

    static final class PullTool extends Tools.Tool {
        private final String label, console;
        private final double cu, cv;
        private final boolean pulls;
        private final Maker maker;
        private final DoubleFunction<Xform> place;

        /**
         * label: what is made; (cu, cv): where the height is pulled; pulls: false for a shape that needs no
         * height (a sphere, a revolution); place: the world transform for a signed height.
         */
        PullTool(Viewport vp, String label, double cu, double cv, boolean pulls, Maker maker, DoubleFunction<Xform> place,
                 String console) {
            super(vp);
            this.label = label;
            this.cu = cu;
            this.cv = cv;
            this.pulls = pulls;
            this.maker = maker;
            this.place = place;
            this.console = console;
        }

        String name() {
            return label;
        }

        String hint() {
            return pulls ? label + ": move to set the height and click, or type it" : label + ": click or Enter makes it";
        }

        void fields() {
            if (pulls) {
                input.set(len("Height"));
            } else {
                input.clear();
            }
        }

        boolean takesFacePlane() {
            return false;
        }

        List<Inference.Ref> references() {
            return List.of(new Inference.Ref(plane().at(cu, cv), "center", "center"));
        }

        void update(Tools.Snap s, MouseEvent e) {
            if (pulls) {
                put(0, vp.heightAt(plane().at(cu, cv)));
            }
        }

        void step() {
            final double h = pulls ? val(0) : 0;
            if (pulls && Math.abs(h) < 1e-9) {
                return;
            }
            final String hExpr = pulls ? size(0) : "";
            final Xform w = place.apply(h);
            create(label.replace(' ', '_').replace("/", ""), maker.make(Math.abs(h), hExpr, label),
                w, console.replace("$h", hExpr) + " " + vp.model.material + at(w.translation()));
        }

        void preview(Raster r) {
            double h = 0;
            if (pulls) {
                h = last == null && !input.typing() ? 0 : val(0);
                if (Math.abs(h) < 1e-12) {
                    h = Math.max(vp.camera.pixelSize() * 2, 1e-3);
                }
            }
            drawPreview(r, maker.make(Math.abs(h), Units.exact(Math.abs(h)), "preview"), place.apply(h));
        }

        void overlay(Graphics2D g) {
            if (pulls && (last != null || input.typing())) {
                final Vec3 b = plane().at(cu, cv);
                vp.dim(g, b, b.add(plane().n.mul(val(0))), mm(val(0)), null);
            }
        }
    }

    /** The plane's frame at a point lifted off it, turned about the normal by an angle. */
    static Xform turned(WorkPlane wp, double u, double v, double lift, double angle) {
        final Xform f = wp.frame(wp.at(u, v, lift));
        return angle == 0 ? f : f.compose(new Xform(Xform.axisAngle(Vec3.Z, angle), 0, 0, 0));
    }

    // ==================================================================
    // Sketch: a shape drawn freehand, read, and made
    // ==================================================================

    static final class SketchTool extends Tools.Tool {
        private final List<double[]> stroke = new ArrayList<>();
        private final List<Vec3> trail = new ArrayList<>();
        private boolean drawing;
        private int lastX, lastY;
        private Sketch.Shape shape;
        /** What the shape was read as, with the sizes it was given (snapped, matched): "circle R 15 cm". */
        private String reading;
        private int choice;
        private Tools.Tool delegate;
        private String message;

        SketchTool(Viewport vp) {
            super(vp);
        }

        String name() {
            return "Sketch";
        }

        String hint() {
            if (delegate != null) {
                final String[] o = options();
                return "Sketch: " + reading + " -> " + o[choice] + ". " + delegate.hint()
                    + (o.length > 1 ? "   (Space: " + o[(choice + 1) % o.length] + " instead" : "   (")
                    + "; Esc: draw again)";
            }
            return "Sketch: draw a shape freehand on the " + plane().name + " plane, press, draw, release: a circle,"
                + " a rectangle, a triangle, a hexagon or any outline, an ellipse, a line; it is read and made a volume"
                + (message != null ? "   -  " + message : "");
        }

        boolean usesPlane() {
            return delegate == null || delegate.usesPlane();
        }

        void fields() {
            if (delegate != null) {
                delegate.fields();
            } else {
                input.clear();
            }
        }

        DynamicInput hud() {
            return delegate != null ? delegate.input : input;
        }

        LiveCheck.Verdict verdict() {
            return delegate != null ? delegate.verdict() : null;
        }

        List<Inference.Ref> references() {
            return delegate != null ? delegate.references() : List.of();
        }

        double[] anchor() {
            return delegate != null ? delegate.anchor() : null;
        }

        boolean takesFacePlane() {
            return delegate == null && !drawing;
        }

        @Override
        void activate() {
            stroke.clear();
            trail.clear();
            delegate = null;
            shape = null;
            message = null;
            drawing = false;
            super.activate();
        }

        @Override
        void cancel() {
            if (delegate != null) {
                delegate.cancel();
            }
            delegate = null;
            shape = null;
            stroke.clear();
            trail.clear();
            drawing = false;
            super.cancel();
        }

        @Override
        void moved(Tools.Snap s, MouseEvent e) {
            last = s;
            if (delegate != null) {
                delegate.moved(s, e);
            }
        }

        void update(Tools.Snap s, MouseEvent e) {
            if (delegate != null) {
                delegate.update(s, e);
            }
        }

        @Override
        void clicked(Tools.Snap s, MouseEvent e) {
            last = s;
            if (delegate != null) {
                delegate.clicked(s, e);
                after();
            }
        }

        void step() {
            if (delegate != null) {
                delegate.step();
                after();
            }
        }

        @Override
        boolean key(KeyEvent e) {
            if (delegate != null) {
                if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_SPACE) {
                    choice = (choice + 1) % options().length;
                    handOver();
                    vp.repaint();
                    return true;
                }
                if (e.getID() == KeyEvent.KEY_TYPED && e.getKeyChar() == ' ') {
                    return true;
                }
                if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    cancel();
                    message = "dropped; draw again";
                    return true;
                }
                final boolean used = delegate.key(e);
                after();
                return used;
            }
            return super.key(e);
        }

        // --- the stroke ------------------------------------------------------------------

        @Override
        boolean pressed(MouseEvent e) {
            if (delegate != null) {
                return false; // a click: it goes to the shape handed over
            }
            stroke.clear();
            trail.clear();
            message = null;
            drawing = true;
            lastX = Integer.MIN_VALUE;
            add(e.getX(), e.getY());
            return true;
        }

        @Override
        void dragged(MouseEvent e) {
            if (drawing && Math.hypot(e.getX() - lastX, e.getY() - lastY) >= 2) {
                add(e.getX(), e.getY());
                vp.repaint();
            }
        }

        @Override
        void released(MouseEvent e) {
            if (!drawing) {
                return;
            }
            drawing = false;
            add(e.getX(), e.getY());
            read();
        }

        private void add(int x, int y) {
            final Vec3[] r = vp.camera.ray(x, y);
            final double[] uv = plane().hit(r[0], r[1]);
            if (uv != null) {
                stroke.add(uv);
                trail.add(plane().at(uv[0], uv[1]));
                lastX = x;
                lastY = y;
            }
        }

        private void read() {
            if (stroke.size() < 2) {
                stroke.clear();
                trail.clear();
                return;
            }
            final double[] c = Sketch.centroid(stroke);
            final double px = vp.inference.pixelAt(plane().at(c[0], c[1]));
            shape = Sketch.recognize(stroke, px);
            stroke.clear();
            if (shape == null) {
                trail.clear();
                message = "not read as a shape: draw a closed outline, or a straight line";
                vp.updateStatus();
                return;
            }
            choice = 0;
            handOver();
            vp.updateStatus();
        }

        /** The readings offered for the shape, the likeliest first. */
        private String[] options() {
            return switch (shape.kind()) {
                case CIRCLE -> new String[] {"Tube", "Sphere", "Cone"};
                case ELLIPSE -> new String[] {"Elliptical tube"};
                case RECTANGLE -> new String[] {"Box", "Extrusion"};
                case LINE -> new String[] {"Plate"};
                default -> new String[] {"Extrusion", "Revolution"};
            };
        }

        /** Gives the shape, its points snapped and its sizes matched, to the tool that pulls the reading chosen. */
        private void handOver() {
            final WorkPlane wp = plane();
            final String opt = options()[choice];
            final String unit = vp.model.lengthUnit;
            final Tools.Tool t;
            reading = shape.describe();
            switch (shape.kind()) {
                case CIRCLE -> {
                    final double[] c = snapped(shape.cu(), shape.cv());
                    final Inference.Length r = sized(shape.r(), c);
                    final String rx = text(r, unit);
                    reading = "circle R " + (r.expr() != null ? r.expr() : mm(r.value()));
                    switch (opt) {
                        case "Sphere" -> t = new PullTool(vp, "Sphere", c[0], c[1], false, (h, he, n) -> {
                            final Solid o = Solids.create("orb", n);
                            o.set("r", rx);
                            return o;
                        }, h -> wp.frame(wp.at(c[0], c[1])), "sphere $name " + rx);
                        case "Cone" -> {
                            final Tools.ConeTool k = new Tools.ConeTool(vp);
                            k.cu = c[0];
                            k.cv = c[1];
                            k.r1 = r.value();
                            k.r1Expr = rx;
                            k.phase = 2;
                            t = k;
                        }
                        default -> {
                            final Tools.TubeTool k = new Tools.TubeTool(vp);
                            k.cu = c[0];
                            k.cv = c[1];
                            k.r = r.value();
                            k.rin = 0;
                            k.rExpr = rx;
                            k.rinExpr = "0*" + unit;
                            k.phase = 2;
                            t = k;
                        }
                    }
                }
                case ELLIPSE -> {
                    final double[] c = snapped(shape.cu(), shape.cv());
                    final Inference.Length al = sized(shape.a(), c), bl = sized(shape.b(), c);
                    final String ax = text(al, unit), bx = text(bl, unit);
                    final double angle = shape.angle();
                    reading = "ellipse " + mm(2 * al.value()) + " x " + mm(2 * bl.value());
                    t = new PullTool(vp, "Elliptical tube", c[0], c[1], true, (h, he, n) -> {
                        final Solid o = Solids.create("eltube", n);
                        o.set("dx", ax);
                        o.set("dy", bx);
                        o.set("dz", "(" + he + ")/2");
                        return o;
                    }, h -> turned(wp, c[0], c[1], h / 2, angle), "eltube $name " + ax + " " + bx + " $h");
                }
                case RECTANGLE -> {
                    final double a = shape.a(), b = shape.b(), angle = shape.angle();
                    if (opt.equals("Box") && angle == 0) {
                        double[] p = snapped(shape.cu() - a / 2, shape.cv() - b / 2);
                        double[] q = snapped(shape.cu() + a / 2, shape.cv() + b / 2);
                        if (Math.abs(q[0] - p[0]) < 1e-9 || Math.abs(q[1] - p[1]) < 1e-9) {
                            p = new double[] {shape.cu() - a / 2, shape.cv() - b / 2};
                            q = new double[] {shape.cu() + a / 2, shape.cv() + b / 2};
                        }
                        final Tools.BoxTool k = new Tools.BoxTool(vp);
                        k.u1 = p[0];
                        k.v1 = p[1];
                        k.du = q[0] - p[0];
                        k.dv = q[1] - p[1];
                        reading = "rectangle " + mm(k.du) + " x " + mm(k.dv);
                        k.lengthExpr = DynamicInput.format(Math.abs(k.du), Units.Category.LENGTH, unit);
                        k.widthExpr = DynamicInput.format(Math.abs(k.dv), Units.Category.LENGTH, unit);
                        k.phase = 2;
                        t = k;
                    } else if (opt.equals("Box")) {
                        final double[] c = snapped(shape.cu(), shape.cv());
                        final Inference.Length al = sized(a, c), bl = sized(b, c);
                        final String ax = text(al, unit), bx = text(bl, unit);
                        reading = "tilted rectangle " + mm(al.value()) + " x " + mm(bl.value()) + ", "
                            + Units.num(Math.rint(Math.toDegrees(angle) * 10) / 10) + " deg";
                        t = new PullTool(vp, "Box", c[0], c[1], true, (h, he, n) -> {
                            final Solid o = Solids.create("box", n);
                            o.set("x", ax);
                            o.set("y", bx);
                            o.set("z", he);
                            return o;
                        }, h -> turned(wp, c[0], c[1], h / 2, angle), "box $name " + ax + " " + bx + " $h  # turned "
                            + Units.num(Math.toDegrees(angle)) + " deg");
                    } else {
                        final List<double[]> pts = new ArrayList<>();
                        final double ca = Math.cos(angle), sa = Math.sin(angle);
                        for (double[] s : new double[][] {{-a / 2, -b / 2}, {a / 2, -b / 2}, {a / 2, b / 2}, {-a / 2, b / 2}}) {
                            pts.add(new double[] {shape.cu() + s[0] * ca - s[1] * sa, shape.cv() + s[0] * sa + s[1] * ca});
                        }
                        t = extrusion(pts, angle == 0);
                    }
                }
                case LINE -> {
                    double[] p = snapped(shape.corners().get(0)[0], shape.corners().get(0)[1]);
                    double[] q = snapped(shape.corners().get(1)[0], shape.corners().get(1)[1]);
                    if (Sketch.dist(p, q) < 1e-9) {
                        p = shape.corners().get(0);
                        q = shape.corners().get(1);
                    }
                    final double length = Sketch.dist(p, q), angle = Math.atan2(q[1] - p[1], q[0] - p[0]);
                    final double cu = (p[0] + q[0]) / 2, cv = (p[1] + q[1]) / 2;
                    reading = "line " + mm(length);
                    final String lx = DynamicInput.format(length, Units.Category.LENGTH, unit);
                    final String tx = DynamicInput.format(nice(length / 20), Units.Category.LENGTH, unit);
                    t = new PullTool(vp, "Plate", cu, cv, true, (h, he, n) -> {
                        final Solid o = Solids.create("box", n);
                        o.set("x", lx);
                        o.set("y", tx);
                        o.set("z", he);
                        return o;
                    }, h -> turned(wp, cu, cv, h / 2, angle), "box $name " + lx + " " + tx + " $h  # a plate along the"
                        + " line, " + tx + " thick (E pushes its faces)");
                }
                default -> {
                    final List<double[]> pts = new ArrayList<>();
                    if (shape.kind() == Sketch.Kind.REGULAR) {
                        // kept regular: the center snapped, the corners carried with it
                        final double[] c = snapped(shape.cu(), shape.cv());
                        final double du = c[0] - shape.cu(), dv = c[1] - shape.cv();
                        for (double[] s : shape.corners()) {
                            pts.add(new double[] {s[0] + du, s[1] + dv});
                        }
                    } else {
                        for (double[] s : shape.corners()) {
                            final double[] p = snapped(s[0], s[1]);
                            if (pts.isEmpty() || Sketch.dist(pts.get(pts.size() - 1), p) > 1e-9) {
                                pts.add(p);
                            }
                        }
                        if (pts.size() < 3 || !com.sphere.core.geant4.Polygon2D.isSimple(Sketch.flat(pts))) {
                            pts.clear();
                            pts.addAll(shape.corners());
                        }
                    }
                    t = opt.equals("Revolution") ? revolution(pts) : extrusion(pts, false);
                }
            }
            if (delegate != null && delegate != t) {
                delegate.cancel();
            }
            delegate = t;
            delegate.fields();
            phase = 1;
            if (last != null) {
                delegate.moved(last, null);
            }
            vp.updateStatus();
        }

        private Tools.Tool extrusion(List<double[]> pts, boolean snapCorners) {
            final Tools.PolygonTool k = new Tools.PolygonTool(vp, false);
            for (double[] p : pts) {
                k.pts.add(snapCorners ? snapped(p[0], p[1]) : p);
            }
            k.phase = 1;
            return k;
        }

        /** The outline turned about the plane's second axis (on the side it was drawn), made as it is. */
        private Tools.Tool revolution(List<double[]> pts) {
            final WorkPlane wp = plane();
            final double side = Sketch.centroid(pts)[0] < 0 ? -1 : 1;
            final String unit = vp.model.lengthUnit;
            final double[] c = Sketch.centroid(pts);
            return new PullTool(vp, "Revolution", c[0], c[1], false, (h, he, n) -> {
                final Solid s = Solids.create("genericPolycone", n);
                final Solid.Table t = s.table("rzpoint");
                t.clear();
                for (double[] p : pts) {
                    t.add(DynamicInput.format(Math.max(0, side * p[0]), Units.Category.LENGTH, unit),
                        DynamicInput.format(p[1], Units.Category.LENGTH, unit));
                }
                return s;
            }, h -> side > 0 ? Tools.PolygonTool.revolveFrame(wp)
                : Tools.PolygonTool.revolveFrame(new WorkPlane(wp.name, wp.origin, wp.u.mul(-1), wp.v)),
                "revolve $name " + pts.size() + " (r,z) points about " + wp.axisName(false));
        }

        /** A point of the shape where the assistant would take it: on a center or a corner near it, or the grid. */
        private double[] snapped(double u, double v) {
            if (vp.snapSuspended()) {
                return new double[] {u, v};
            }
            final double[] s = vp.screenOf(plane().at(u, v));
            if (s != null) {
                final Tools.Snap sn = vp.snapAt((int) Math.round(s[0]), (int) Math.round(s[1]));
                if (sn != null) {
                    return new double[] {sn.u(), sn.v()};
                }
            }
            if (vp.gridSnap) {
                final double g = vp.gridStep();
                return new double[] {Math.rint(u / g) * g, Math.rint(v / g) * g};
            }
            return new double[] {u, v};
        }

        /** A size of the shape as the assistant reads it: a variable, a size of the design, or the grid. */
        private Inference.Length sized(double raw, double[] at) {
            if (vp.snapSuspended()) {
                return Inference.Length.plain(raw);
            }
            final Inference.Length l = vp.inference.length(raw, plane().at(at[0], at[1]), vp.gridSnap);
            return l.value() > 0 ? l : Inference.Length.plain(raw);
        }

        private static String text(Inference.Length l, String unit) {
            return l.expr() != null ? l.expr() : DynamicInput.format(l.value(), Units.Category.LENGTH, unit);
        }

        /** 1, 2 or 5 times a power of ten, the nearest below. */
        private static double nice(double v) {
            if (!(v > 0)) {
                return 1;
            }
            final double p = Math.pow(10, Math.floor(Math.log10(v)));
            final double m = v / p;
            return (m >= 5 ? 5 : m >= 2 ? 2 : 1) * p;
        }

        /** When the shape handed over is made, back to drawing. */
        private void after() {
            if (delegate != null && delegate.created) {
                delegate = null;
                shape = null;
                trail.clear();
                message = null;
                phase = 0;
                created = true;
                input.clear();
                vp.updateStatus();
            }
        }

        void preview(Raster r) {
            if (delegate != null) {
                delegate.previewFrame(r);
            }
        }

        void overlay(Graphics2D g) {
            if (delegate != null) {
                delegate.overlay(g);
                vp.tag(g, 12, vp.getHeight() - 112, reading + "  ->  " + options()[choice]
                    + (options().length > 1 ? "     Space: " + options()[(choice + 1) % options().length] : ""),
                    new Color(0x8B6CC9));
            }
            if (trail.size() >= 2 && (drawing || delegate != null)) {
                final Polygon p = new Polygon();
                for (Vec3 w : trail) {
                    final double[] s = vp.screenOf(w);
                    if (s != null) {
                        p.addPoint((int) Math.round(s[0]), (int) Math.round(s[1]));
                    }
                }
                final Graphics2D h = (Graphics2D) g.create();
                h.setStroke(new BasicStroke(drawing ? 2.5f : 1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                h.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, drawing ? 0.95f : 0.35f));
                h.setColor(new Color(0x8B6CC9));
                h.drawPolyline(p.xpoints, p.ypoints, p.npoints);
                h.dispose();
            }
        }
    }

    // ==================================================================
    // Push/Pull: a face dragged along its normal
    // ==================================================================

    static final class PushPullTool extends Tools.Tool {

        /**
         * A face that can be pushed: the copy it was taken on, the solid parameters it moves (and by how much
         * per unit pushed), its outward normal in the solid's frame, whether the placement follows so the
         * opposite face stays (lengths) or not (radii), the extrusion section it moves (or -1), the point and
         * normal it was taken at in the world, and what it is called.
         */
        private record Face(Geometry.Instance in, Solid solid, String[] params, double[] perUnit, Vec3 localNormal,
                            boolean shifts, int section, Vec3 point, Vec3 normal, String what) {
        }

        private Face hover, face;
        private String note;
        private String[] startExprs;
        private double[] startValues;
        private String startSection;
        private final Map<PhysicalVolume, String[]> startPos = new LinkedHashMap<>();
        private double grab, amount;
        private String amountExpr;
        private boolean dragging, moved, confirm;
        private int pressX, pressY;

        PushPullTool(Viewport vp) {
            super(vp);
        }

        String name() {
            return "Push/Pull";
        }

        String hint() {
            if (phase == 1 && face != null) {
                return "Push/Pull " + face.what() + " of " + face.solid().name() + ": move to push or pull it (it stops"
                    + " level with what is around, at the design's sizes, on the grid; Ctrl: free), click or Enter to"
                    + " apply, or type the distance; Esc cancels";
            }
            return "Push/Pull: drag a face of a volume out or in (box, tube, cone, sphere, torus, elliptical tube,"
                + " trapezoid, extrusion); or click it, move, click again" + (note != null ? "   -  " + note : "");
        }

        boolean usesPlane() {
            return false;
        }

        boolean takesFacePlane() {
            return false;
        }

        void fields() {
            if (phase == 1) {
                input.set(len("Distance"));
            } else {
                input.clear();
            }
        }

        void update(Tools.Snap s, MouseEvent e) {
        }

        @Override
        void activate() {
            hover = null;
            face = null;
            note = null;
            super.activate();
        }

        @Override
        void cancel() {
            if (phase == 1) {
                vp.model.cancelLive();
            }
            face = null;
            dragging = false;
            confirm = false;
            super.cancel();
        }

        @Override
        void hovered(MouseEvent e) {
            if (phase == 0) {
                hover = faceAt(e.getX(), e.getY());
            } else if (!dragging) {
                follow(e.getX(), e.getY(), e.isControlDown());
            }
        }

        @Override
        boolean pressed(MouseEvent e) {
            pressX = e.getX();
            pressY = e.getY();
            if (phase == 1) {
                confirm = true; // a click applies what the mouse set
                return true;
            }
            final Face f = faceAt(e.getX(), e.getY());
            if (f == null) {
                return false;
            }
            if (!begin(f, e.getX(), e.getY())) {
                return false;
            }
            dragging = true;
            moved = false;
            return true;
        }

        @Override
        void dragged(MouseEvent e) {
            if (dragging) {
                if (Math.abs(e.getX() - pressX) + Math.abs(e.getY() - pressY) > 3) {
                    moved = true;
                }
                follow(e.getX(), e.getY(), e.isControlDown());
            }
        }

        @Override
        void released(MouseEvent e) {
            if (confirm) {
                confirm = false;
                step();
                return;
            }
            if (dragging) {
                dragging = false;
                if (moved) {
                    step();
                }
            }
        }

        @Override
        boolean key(KeyEvent e) {
            final boolean used = super.key(e);
            if (used && phase == 1 && face != null && e.getKeyCode() != KeyEvent.VK_ENTER
                && e.getKeyCode() != KeyEvent.VK_ESCAPE) {
                // typed: the face follows what is typed
                if (input.typing()) {
                    apply(val(0), null);
                } else if (vp.mouseX() >= 0) {
                    follow(vp.mouseX(), vp.mouseY(), false);
                }
            }
            return used;
        }

        void step() {
            if (phase != 1 || face == null) {
                return;
            }
            if (input.typing()) {
                apply(val(0), null);
            }
            final EditorModel m = vp.model;
            final Solid s = face.solid();
            m.commit("Push/pull " + s.name() + " " + face.what().toLowerCase(java.util.Locale.ROOT));
            for (String p : face.params()) {
                m.log("set " + s.name() + "." + p + " = " + s.param(p).expr());
            }
            if (face.section() >= 0) {
                m.log("# " + s.name() + ": section " + face.section() + " now at z = "
                    + s.table("section").rows().get(face.section())[0]);
            }
            for (PhysicalVolume pv : startPos.keySet()) {
                final String[] p = pv.positionExprs();
                if (!java.util.Arrays.equals(p, java.util.Arrays.copyOf(startPos.get(pv), 3))) {
                    m.log("set " + pv.name() + ".position = " + p[0] + " " + p[1] + " " + p[2]);
                }
            }
            face = null;
            hover = null;
            phase = 0;
            created = true;
            fields();
        }

        // --- faces ------------------------------------------------------------------------

        /** The face under a pixel, when push/pull knows what it stands for; the note says why not otherwise. */
        private Face faceAt(int x, int y) {
            note = null;
            final Geometry.Instance in = vp.instanceAt(x, y);
            if (in == null || in.pv() == null) {
                return null;
            }
            final Vec3 p = vp.pointAt(x, y);
            final Solid s = in.lv().solid();
            if (p == null || s == null || s.error() != null) {
                return null;
            }
            if (in.pv().isReplica()) {
                note = in.lv().name() + " is a replica's cell: change the replica instead";
                return null;
            }
            final Vec3 l = in.world().applyInverse(p);
            final Vec3 n = s.normal(l.x(), l.y(), l.z());
            final double ax = Math.abs(n.x()), ay = Math.abs(n.y()), az = Math.abs(n.z());
            final boolean cap = az > 0.7;
            final double sz = Math.signum(n.z()) == 0 ? 1 : Math.signum(n.z());
            final Vec3 capN = Vec3.Z.mul(sz);
            Face f = switch (s.tag()) {
                case "box" -> {
                    final int a = ax >= ay && ax >= az ? 0 : ay >= az ? 1 : 2;
                    final double sg = Math.signum(n.get(a)) == 0 ? 1 : Math.signum(n.get(a));
                    final String k = "xyz".substring(a, a + 1);
                    yield face(in, s, new String[] {k}, new double[] {1}, Vec3.axis(a).mul(sg), true, -1, p,
                        "Length " + k);
                }
                case "tube" -> cap ? face(in, s, new String[] {"z"}, new double[] {1}, capN, true, -1, p, "Length z")
                    : radial(in, s, l, p, "rmin", "rmax");
                case "cone" -> {
                    if (cap) {
                        yield face(in, s, new String[] {"z"}, new double[] {1}, capN, true, -1, p, "Length z");
                    }
                    final double rho = Math.hypot(l.x(), l.y());
                    final Vec3 out = rho > 0 ? new Vec3(l.x() / rho, l.y() / rho, 0) : Vec3.X;
                    final double t = (l.z() / Math.max(1e-12, s.v("z")) + 0.5);
                    final double rin = s.v("rmin1") * (1 - t) + s.v("rmin2") * t, rout = s.v("rmax1") * (1 - t) + s.v("rmax2") * t;
                    if (rin > 0 && Math.abs(rho - rin) < Math.abs(rho - rout)) {
                        yield face(in, s, new String[] {"rmin1", "rmin2"}, new double[] {-1, -1}, out.mul(-1), false, -1, p,
                            "Inner radii");
                    }
                    yield face(in, s, new String[] {"rmax1", "rmax2"}, new double[] {1, 1}, out, false, -1, p, "Outer radii");
                }
                case "orb" -> face(in, s, new String[] {"r"}, new double[] {1}, l.unit(), false, -1, p, "Radius");
                case "sphere" -> {
                    final double r = l.length();
                    if (s.v("rmin") > 0 && Math.abs(r - s.v("rmin")) < Math.abs(r - s.v("rmax"))) {
                        yield face(in, s, new String[] {"rmin"}, new double[] {-1}, l.unit().mul(-1), false, -1, p,
                            "Inner radius");
                    }
                    yield face(in, s, new String[] {"rmax"}, new double[] {1}, l.unit(), false, -1, p, "Outer radius");
                }
                case "torus" -> {
                    final double phi = Math.atan2(l.y(), l.x()), rt = s.v("rtor");
                    final Vec3 c = new Vec3(rt * Math.cos(phi), rt * Math.sin(phi), 0), d = l.sub(c);
                    final double r = d.length();
                    if (s.v("rmin") > 0 && Math.abs(r - s.v("rmin")) < Math.abs(r - s.v("rmax"))) {
                        yield face(in, s, new String[] {"rmin"}, new double[] {-1}, d.unit().mul(-1), false, -1, p,
                            "Inner tube radius");
                    }
                    yield face(in, s, new String[] {"rmax"}, new double[] {1}, d.unit(), false, -1, p, "Tube radius");
                }
                case "eltube" -> {
                    if (cap) {
                        yield face(in, s, new String[] {"dz"}, new double[] {0.5}, capN, true, -1, p, "Length z");
                    }
                    final boolean xs = ax >= ay;
                    final double sg = Math.signum(xs ? n.x() : n.y()) == 0 ? 1 : Math.signum(xs ? n.x() : n.y());
                    yield face(in, s, new String[] {xs ? "dx" : "dy"}, new double[] {0.5},
                        (xs ? Vec3.X : Vec3.Y).mul(sg), true, -1, p, xs ? "Width x" : "Width y");
                }
                case "trd" -> {
                    if (cap) {
                        yield face(in, s, new String[] {"z"}, new double[] {1}, capN, true, -1, p, "Length z");
                    }
                    final boolean xs = ax >= ay;
                    final double sg = Math.signum(xs ? n.x() : n.y()) == 0 ? 1 : Math.signum(xs ? n.x() : n.y());
                    yield face(in, s, xs ? new String[] {"x1", "x2"} : new String[] {"y1", "y2"}, new double[] {1, 1},
                        (xs ? Vec3.X : Vec3.Y).mul(sg), true, -1, p, xs ? "Width x" : "Width y");
                }
                case "xtru" -> {
                    if (!cap) {
                        note = "the side of an extrusion: move its outline's vertices in the property sheet";
                        yield null;
                    }
                    final Solid.Table t = s.table("section");
                    int row = 0;
                    for (int i = 1; i < t.size(); i++) {
                        if (sz > 0 ? t.value(i, 0) > t.value(row, 0) : t.value(i, 0) < t.value(row, 0)) {
                            row = i;
                        }
                    }
                    yield face(in, s, new String[0], new double[0], capN, false, row, p, sz > 0 ? "Top face" : "Bottom face");
                }
                default -> {
                    note = s.g4Class() + ": push/pull knows boxes, tubes, cones, spheres, tori, elliptical tubes,"
                        + " trapezoids and extrusions";
                    yield null;
                }
            };
            return f;
        }

        private Face face(Geometry.Instance in, Solid s, String[] params, double[] perUnit, Vec3 ln, boolean shifts,
                          int section, Vec3 p, String what) {
            return new Face(in, s, params, perUnit, ln, shifts, section, p, in.world().applyDir(ln).unit(), what);
        }

        /** The curved side of a tube: its outer radius, or its inner one when nearer. */
        private Face radial(Geometry.Instance in, Solid s, Vec3 l, Vec3 p, String inner, String outer) {
            final double rho = Math.hypot(l.x(), l.y());
            final Vec3 out = rho > 0 ? new Vec3(l.x() / rho, l.y() / rho, 0) : Vec3.X;
            if (s.v(inner) > 0 && Math.abs(rho - s.v(inner)) < Math.abs(rho - s.v(outer))) {
                return face(in, s, new String[] {inner}, new double[] {-1}, out.mul(-1), false, -1, p, "Inner radius");
            }
            return face(in, s, new String[] {outer}, new double[] {1}, out, false, -1, p, "Outer radius");
        }

        /** Takes hold of a face: what it starts from, every placement that follows it; false when it cannot. */
        private boolean begin(Face f, int x, int y) {
            final EditorModel m = vp.model;
            startPos.clear();
            if (f.shifts()) {
                for (LogicalVolume lv : m.geometry.users(f.solid())) {
                    if (lv.solid() != f.solid()) {
                        note = f.solid().name() + " is an operand of a boolean: push/pull the boolean's own faces"
                            + " in the property sheet";
                        return false;
                    }
                    for (PhysicalVolume pv : m.geometry.placementsOf(lv)) {
                        if (pv.isReplica()) {
                            note = lv.name() + " is replicated: change the replica instead";
                            return false;
                        }
                        startPos.put(pv, pv.positionExprs().clone());
                    }
                }
            }
            face = f;
            startExprs = new String[f.params().length];
            startValues = new double[f.params().length];
            for (int i = 0; i < f.params().length; i++) {
                startExprs[i] = f.solid().param(f.params()[i]).expr();
                startValues[i] = f.solid().v(f.params()[i]);
            }
            startSection = f.section() >= 0 ? f.solid().table("section").rows().get(f.section())[0] : null;
            grab = vp.axisParam(f.point(), f.normal(), x, y);
            amount = 0;
            amountExpr = null;
            m.checkpoint();
            phase = 1;
            fields();
            vp.updateStatus();
            return true;
        }

        /** The face where the mouse pulls it: level with a point around, at a size of the design, on the grid. */
        private void follow(int x, int y, boolean free) {
            if (face == null) {
                return;
            }
            final double raw = vp.axisParam(face.point(), face.normal(), x, y) - grab;
            double d = raw;
            String expr = null, why = null;
            final Inference.Length lv = free ? null : vp.inference.level(face.point(), face.normal(), raw);
            if (lv != null) {
                d = lv.value();
                why = lv.hint();
            } else if (!free) {
                // the size the face makes, matched to a variable or a size of the design
                final Inference.Length size = face.params().length > 0 && face.perUnit()[0] > 0
                    ? vp.inference.match(startValues[0] + raw * face.perUnit()[0], face.point()) : null;
                if (size != null && !size.hint().contains(face.solid().name() + " ")) {
                    d = (size.value() - startValues[0]) / face.perUnit()[0];
                    expr = size.expr();
                    why = size.hint();
                } else if (vp.gridSnap) {
                    d = vp.inference.round(raw, face.point());
                }
            }
            put(0, new Inference.Length(d, null, why));
            if (!input.typing()) {
                apply(d, expr);
            }
            vp.repaint();
        }

        /** Sets the face d further out along its normal (in when negative), every placement following. */
        private void apply(double d, String expr0) {
            final Face f = face;
            if (f == null) {
                return;
            }
            // keep every size positive, an inner radius under its outer one
            for (int i = 0; i < f.params().length; i++) {
                final double pu = f.perUnit()[i], v0 = startValues[i];
                final double floor = Math.max(1e-6, Math.abs(v0) * 1e-4);
                if (pu > 0) {
                    double low = floor;
                    final String inner = innerOf(f.params()[i]);
                    if (inner != null && f.solid().param(inner) != null) {
                        low = Math.max(low, f.solid().v(inner) + floor);
                    }
                    d = Math.max(d, (low - v0) / pu);
                } else if (pu < 0) {
                    d = Math.min(d, v0 / -pu);
                    final String outer = outerOf(f.params()[i]);
                    if (outer != null) {
                        d = Math.max(d, (f.solid().v(outer) - floor - v0) / pu);
                    }
                }
            }
            if (f.section() >= 0) {
                // an end of an extrusion stays beyond the other one
                final Solid.Table t = f.solid().table("section");
                double other = f.localNormal().z() > 0 ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
                for (int i = 0; i < t.size(); i++) {
                    if (i != f.section()) {
                        other = f.localNormal().z() > 0 ? Math.max(other, t.value(i, 0)) : Math.min(other, t.value(i, 0));
                    }
                }
                final double z0 = startSectionValue(f);
                // the top end goes to z0 + d, above the others; the bottom one to z0 - d, below them
                d = f.localNormal().z() > 0 ? Math.max(d, other + 1e-6 - z0) : Math.max(d, z0 - other + 1e-6);
            }
            amount = d;
            amountExpr = expr0;
            final double dd = d;
            vp.model.live(() -> {
                for (int i = 0; i < f.params().length; i++) {
                    final String e = i == 0 && expr0 != null && f.perUnit()[i] > 0 ? expr0
                        : vp.shift(startExprs[i], dd * f.perUnit()[i]);
                    f.solid().set(f.params()[i], e);
                }
                if (f.section() >= 0) {
                    f.solid().table("section").set(f.section(), 0,
                        vp.shift(startSection, dd * Math.signum(f.localNormal().z())));
                }
                for (Map.Entry<PhysicalVolume, String[]> en : startPos.entrySet()) {
                    final PhysicalVolume pv = en.getKey();
                    final String[] p = en.getValue();
                    final Vec3 move = pv.transform().applyDir(f.localNormal().mul(dd / 2));
                    pv.setPosition(vp.shift(p[0], move.x()), vp.shift(p[1], move.y()), vp.shift(p[2], move.z()));
                }
            });
        }

        private double startSectionValue(Face f) {
            try {
                return com.sphere.core.geant4.Expr.eval(startSection, vp.model.geometry.scope());
            } catch (com.sphere.core.geant4.GeometryException e) {
                return f.solid().table("section").value(f.section(), 0);
            }
        }

        private static String innerOf(String p) {
            return switch (p) {
                case "rmax" -> "rmin";
                case "rmax1" -> "rmin1";
                case "rmax2" -> "rmin2";
                default -> null;
            };
        }

        private static String outerOf(String p) {
            return switch (p) {
                case "rmin" -> "rmax";
                case "rmin1" -> "rmax1";
                case "rmin2" -> "rmax2";
                default -> null;
            };
        }

        // --- drawing ----------------------------------------------------------------------

        /** The copy a face was taken on, as the document is now (an edit makes the copies anew). */
        private Geometry.Instance now(Face f) {
            for (Geometry.Instance i : vp.model.geometry.instancesOf(f.in().pv())) {
                if (i.copy() == f.in().copy() && i.depth() == f.in().depth()) {
                    return i;
                }
            }
            return f.in();
        }

        void overlay(Graphics2D g) {
            final Face f = phase == 1 ? face : vp.mouseX() >= 0 ? hover : null;
            if (f == null) {
                if (note != null && vp.mouseX() >= 0) {
                    vp.tag(g, vp.mouseX() + 16, vp.mouseY() - 26, note, new Color(0xB07020));
                }
                return;
            }
            final Geometry.Instance in = now(f);
            final Color accent = phase == 1 ? new Color(0x2AA1C8) : new Color(0xE8833A);
            final Graphics2D h = (Graphics2D) g.create();
            for (List<Vec3> loop : outline(f)) {
                final Polygon poly = new Polygon();
                for (Vec3 p : loop) {
                    final double[] s = vp.screenOf(in.world().apply(p));
                    if (s != null) {
                        poly.addPoint((int) Math.round(s[0]), (int) Math.round(s[1]));
                    }
                }
                if (poly.npoints < 2) {
                    continue;
                }
                if (f.localNormal().z() != 0 && Math.abs(f.localNormal().z()) > 0.99 || "box".equals(f.solid().tag())) {
                    h.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f));
                    h.setColor(accent);
                    h.fillPolygon(poly);
                }
                h.setComposite(AlphaComposite.SrcOver);
                h.setColor(accent);
                h.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                h.drawPolygon(poly);
            }
            // The arrow out of the face, and what it sets
            final Vec3 at = f.point().add(f.normal().mul(phase == 1 ? amount : 0));
            final double[] s0 = vp.screenOf(at), s1 = vp.screenOf(at.add(f.normal().mul(vp.inference.pixelAt(at) * 60)));
            if (s0 != null && s1 != null) {
                final double dx = s1[0] - s0[0], dy = s1[1] - s0[1], l = Math.hypot(dx, dy);
                if (l > 4) {
                    final double ux = dx / l, uy = dy / l;
                    final int ex = (int) (s0[0] + ux * 46), ey = (int) (s0[1] + uy * 46);
                    h.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    h.drawLine((int) s0[0], (int) s0[1], ex, ey);
                    h.fillPolygon(new int[] {(int) (ex + ux * 10), (int) (ex - uy * 6), (int) (ex + uy * 6)},
                        new int[] {(int) (ey + uy * 10), (int) (ey + ux * 6), (int) (ey - ux * 6)}, 3);
                }
                final StringBuilder b = new StringBuilder(f.what());
                if (f.params().length > 0) {
                    b.append("  ").append(Units.best(f.solid().v(f.params()[0]), Units.Category.LENGTH));
                }
                if (phase == 1) {
                    b.append("   (").append(amount >= 0 ? "+" : "-").append(mm(amount)).append(")");
                    final DynamicInput.Field fd = input.field(0);
                    if (fd != null && fd.hint != null) {
                        b.append("  ").append(fd.hint);
                    }
                }
                vp.tag(h, (int) s0[0] + 14, (int) s0[1] - 18, b.toString(), accent.darker());
            }
            h.dispose();
        }

        /** The face's outline, in the solid's frame: what lights up under the mouse. */
        private List<List<Vec3>> outline(Face f) {
            final Solid s = f.solid();
            final List<List<Vec3>> out = new ArrayList<>();
            final Vec3 n = f.localNormal();
            switch (s.tag()) {
                case "box" -> {
                    final double[] h = {s.v("x") / 2, s.v("y") / 2, s.v("z") / 2};
                    final int a = Math.abs(n.x()) > 0.5 ? 0 : Math.abs(n.y()) > 0.5 ? 1 : 2;
                    final int b = (a + 1) % 3, c = (a + 2) % 3;
                    final List<Vec3> q = new ArrayList<>();
                    for (double[] k : new double[][] {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
                        final double[] p = new double[3];
                        p[a] = Math.signum(n.get(a)) * h[a];
                        p[b] = k[0] * h[b];
                        p[c] = k[1] * h[c];
                        q.add(new Vec3(p[0], p[1], p[2]));
                    }
                    out.add(q);
                }
                case "tube" -> {
                    final double hz = s.v("z") / 2;
                    if (f.shifts()) {
                        out.add(circle(s.v("rmax"), s.v("rmax"), n.z() * hz));
                        if (s.v("rmin") > 0) {
                            out.add(circle(s.v("rmin"), s.v("rmin"), n.z() * hz));
                        }
                    } else {
                        final double r = s.v(f.params()[0]);
                        out.add(circle(r, r, -hz));
                        out.add(circle(r, r, hz));
                    }
                }
                case "cone" -> {
                    final double hz = s.v("z") / 2;
                    if (f.shifts()) {
                        final double r = n.z() > 0 ? s.v("rmax2") : s.v("rmax1");
                        out.add(circle(r, r, n.z() * hz));
                    } else {
                        final boolean inner = f.params()[0].startsWith("rmin");
                        out.add(circle(s.v(inner ? "rmin1" : "rmax1"), s.v(inner ? "rmin1" : "rmax1"), -hz));
                        out.add(circle(s.v(inner ? "rmin2" : "rmax2"), s.v(inner ? "rmin2" : "rmax2"), hz));
                    }
                }
                case "eltube" -> {
                    final double hz = s.v("dz");
                    if (Math.abs(n.z()) > 0.5) {
                        out.add(circle(s.v("dx"), s.v("dy"), n.z() * hz));
                    } else {
                        out.add(circle(s.v("dx"), s.v("dy"), -hz));
                        out.add(circle(s.v("dx"), s.v("dy"), hz));
                    }
                }
                case "trd" -> {
                    final double hz = s.v("z") / 2;
                    final double x1 = s.v("x1") / 2, x2 = s.v("x2") / 2, y1 = s.v("y1") / 2, y2 = s.v("y2") / 2;
                    if (Math.abs(n.z()) > 0.5) {
                        final double x = n.z() > 0 ? x2 : x1, y = n.z() > 0 ? y2 : y1, z = n.z() * hz;
                        out.add(List.of(new Vec3(-x, -y, z), new Vec3(x, -y, z), new Vec3(x, y, z), new Vec3(-x, y, z)));
                    } else if (Math.abs(n.x()) > 0.5) {
                        final double sg = Math.signum(n.x());
                        out.add(List.of(new Vec3(sg * x1, -y1, -hz), new Vec3(sg * x1, y1, -hz), new Vec3(sg * x2, y2, hz),
                            new Vec3(sg * x2, -y2, hz)));
                    } else {
                        final double sg = Math.signum(n.y());
                        out.add(List.of(new Vec3(-x1, sg * y1, -hz), new Vec3(x1, sg * y1, -hz), new Vec3(x2, sg * y2, hz),
                            new Vec3(-x2, sg * y2, hz)));
                    }
                }
                case "xtru" -> {
                    final Solid.Table t = s.table("section"), v = s.table("twoDimVertex");
                    final int row = f.section();
                    final double z = t.value(row, 0), ox = t.value(row, 1), oy = t.value(row, 2), sc = t.value(row, 3);
                    final List<Vec3> q = new ArrayList<>();
                    for (int i = 0; i < v.size(); i++) {
                        q.add(new Vec3(v.value(i, 0) * sc + ox, v.value(i, 1) * sc + oy, z));
                    }
                    out.add(q);
                }
                case "torus" -> {
                    final double rt = s.v("rtor"), r = s.v(f.params()[0]);
                    out.add(circle(rt + r, rt + r, 0));
                    out.add(circle(Math.max(0, rt - r), Math.max(0, rt - r), 0));
                }
                default -> {
                    // a round solid: the circle it shows the eye, in its own frame
                    final double r = s.v(f.params()[0]);
                    final Geometry.Instance in = now(f);
                    final Vec3 right = in.world().applyInverseDir(vp.camera.right).unit();
                    final Vec3 up = in.world().applyInverseDir(vp.camera.up).unit();
                    final List<Vec3> q = new ArrayList<>();
                    for (int k = 0; k < 64; k++) {
                        final double t = k * Units.TWOPI / 64;
                        q.add(right.mul(r * Math.cos(t)).add(up.mul(r * Math.sin(t))));
                    }
                    out.add(q);
                }
            }
            return out;
        }

        private static List<Vec3> circle(double a, double b, double z) {
            final List<Vec3> q = new ArrayList<>();
            for (int k = 0; k < 64; k++) {
                final double t = k * Units.TWOPI / 64;
                q.add(new Vec3(a * Math.cos(t), b * Math.sin(t), z));
            }
            return q;
        }
    }
}

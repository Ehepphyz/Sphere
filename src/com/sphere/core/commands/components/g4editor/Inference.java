package com.sphere.components.g4editor;

import com.sphere.core.geant4.BBox;
import com.sphere.core.geant4.BooleanSolid;
import com.sphere.core.geant4.Define;
import com.sphere.core.geant4.Expr;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.GeometryException;
import com.sphere.core.geant4.Mesh;
import com.sphere.core.geant4.Solid;
import com.sphere.core.geant4.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * The drawing assistant: what the mouse means where it is, the way SketchUp infers and Figma shows its
 * smart guides. Near the cursor it finds what a hand aims at on a design - a corner, the middle of an edge,
 * the center of a circle or of a face, a point on an edge - and otherwise takes the work plane's point,
 * lined up with what it knows: the points the mouse rested on lately (acquired, as SketchUp says), the
 * tool's own points, the plane's origin, and the axes from the last point. Lengths pulled with the mouse
 * are matched to the design's variables, written by name so the design stays parametric, and to the sizes
 * already drawn; heights and pushed faces stop level with the points around.
 *
 * Everything is in pixels on screen, so the assistant reaches as far at any zoom. Ctrl held, it steps
 * aside: the plane's point is taken as is.
 */
final class Inference {

    /** A dashed line drawn while an inference holds, from what it refers to, to where the mouse is taken. */
    record Guide(Vec3 from, Vec3 to, int argb, String label) {
    }

    /** A point of interest: a corner, the middle of an edge, a center, a point of the tool, an origin. */
    record Ref(Vec3 at, String kind, String label) {
    }

    /** A length the assistant settled on: its value, the expression to write (a variable) or null, and why. */
    record Length(double value, String expr, String hint) {
        static Length plain(double v) {
            return new Length(v, null, null);
        }
    }

    /** What a solid's tessellation offers to snap to, in its own frame (xyz triples), made once per mesh. */
    record Features(float[] corners, float[] mids, float[] centers, float[] faces, float[] edges) {
    }

    /** Reach of the point snaps, of the alignments and of the length matches, in pixels. */
    static final double POINT_PX = 10, ALIGN_PX = 7, LENGTH_PX = 6;
    private static final int MAX_ACQUIRED = 8;
    static final int AXIS_X = 0xFFDC3C3C, AXIS_Y = 0xFF2FA846, AXIS_Z = 0xFF3C64E6, OTHER = 0xFFB050D0,
        LEVEL = 0xFF2AA1C8;

    private final Viewport vp;
    /** The points the mouse snapped to lately, the latest first: what alignments are taken from. */
    private final ArrayDeque<Ref> acquired = new ArrayDeque<>();
    private final Map<Mesh, Features> features = new WeakHashMap<>();
    /** The copies seen near the mouse at the last snap: a corner on a silhouette stays in reach a moment. */
    private List<Geometry.Instance> near = List.of();
    /** The guide of the last distance snapped along a line (a height, a pushed face); null when none holds. */
    Guide alongGuide;

    Inference(Viewport vp) {
        this.vp = vp;
    }

    /** Forgets the acquired points: a new tool starts afresh. */
    void reset() {
        acquired.clear();
        alongGuide = null;
        near = List.of();
    }

    List<Ref> acquired() {
        return List.copyOf(acquired);
    }

    // ------------------------------------------------------------------
    // Points
    // ------------------------------------------------------------------

    /**
     * The snap under a pixel: a point of interest of the volumes there, a point on one of their edges, or
     * the work plane's point lined up with what the assistant knows and snapped to the grid; null when the
     * plane is not under the pixel. free: the plane's point as is (Ctrl held).
     */
    Tools.Snap snap(int mx, int my, boolean free) {
        final WorkPlane plane = vp.plane;
        final Vec3[] ray = vp.camera.ray(mx, my);
        final double[] hit = plane.hit(ray[0], ray[1]);
        // what the mouse has before anything snaps it: lengths are matched on it
        final double hu = hit == null ? 0 : hit[0], hv = hit == null ? 0 : hit[1];
        if (free) {
            return hit == null ? null : new Tools.Snap(hit[0], hit[1], plane.at(hit[0], hit[1]), "free", null, "free",
                List.of(), hu, hv);
        }
        final Tools.Tool tool = vp.tool;
        final List<Ref> toolRefs = tool == null ? List.of() : tool.references();
        near = instancesNear(mx, my);

        // 1. A point of interest within reach: corners first, then centers and middles
        Ref best = null;
        double bestScore = POINT_PX;
        if (vp.vertexSnap) {
            for (Ref r : toolRefs) {
                final double d = px(r.at(), mx, my);
                if (d < bestScore) {
                    bestScore = d;
                    best = r;
                }
            }
            for (Geometry.Instance in : near) {
                final Features f = featuresOf(in);
                final String name = in.lv().name();
                if (f != null) {
                    best = closest(f.corners(), in, mx, my, "vertex", "Endpoint · " + name, 0, best, bestScore);
                    bestScore = lastScore;
                    best = closest(f.centers(), in, mx, my, "center", "Center · " + name, 1.5, best, bestScore);
                    bestScore = lastScore;
                    best = closest(f.mids(), in, mx, my, "midpoint", "Midpoint · " + name, 2, best, bestScore);
                    bestScore = lastScore;
                    best = closest(f.faces(), in, mx, my, "center", "Face center · " + name, 2.5, best, bestScore);
                    bestScore = lastScore;
                }
                // The placement's origin, where its solid is centered (a sphere has no edge to show it)
                final Vec3 c = in.world().translation();
                final double d = px(c, mx, my) + 1.5;
                if (d < bestScore && visible(c)) {
                    bestScore = d;
                    best = new Ref(c, "center", "Center · " + name);
                }
            }
        }
        if (best != null) {
            if (!toolRefs.contains(best)) {
                acquire(best);
            }
            final double[] uv = plane.uv(best.at());
            return new Tools.Snap(uv[0], uv[1], best.at(), best.kind(), null, best.label(), List.of(), hu, hv);
        }

        // 2. A point on an edge
        if (vp.vertexSnap) {
            Vec3 on = null;
            String onName = null;
            double bd = POINT_PX * 0.8;
            for (Geometry.Instance in : near) {
                final Features f = featuresOf(in);
                if (f == null) {
                    continue;
                }
                final float[] e = f.edges();
                for (int i = 0; i + 5 < e.length; i += 6) {
                    final Vec3 a = in.world().apply(new Vec3(e[i], e[i + 1], e[i + 2]));
                    final Vec3 b = in.world().apply(new Vec3(e[i + 3], e[i + 4], e[i + 5]));
                    final double[] sa = vp.screenOf(a), sb = vp.screenOf(b);
                    if (sa == null || sb == null) {
                        continue;
                    }
                    final double dx = sb[0] - sa[0], dy = sb[1] - sa[1], l2 = dx * dx + dy * dy;
                    final double t = l2 > 0 ? Math.max(0, Math.min(1, ((mx - sa[0]) * dx + (my - sa[1]) * dy) / l2)) : 0;
                    final double d = Math.hypot(sa[0] + dx * t - mx, sa[1] + dy * t - my);
                    if (d < bd) {
                        final Vec3 q = a.add(b.sub(a).mul(t));
                        if (visible(q)) {
                            bd = d;
                            on = q;
                            onName = in.lv().name();
                        }
                    }
                }
            }
            if (on != null) {
                final double[] uv = plane.uv(on);
                return new Tools.Snap(uv[0], uv[1], on, "edge", null, "On edge · " + onName, List.of(), hu, hv);
            }
        }

        // 3. The work plane's point, lined up and on the grid
        if (hit == null) {
            return null;
        }
        double u = hit[0], v = hit[1];
        final List<Ref> refs = new ArrayList<>(acquired);
        refs.addAll(toolRefs);
        refs.add(new Ref(plane.origin, "origin", "the origin"));
        final double[] anchor = tool == null ? null : tool.anchor();
        if (anchor != null) {
            refs.add(new Ref(plane.at(anchor[0], anchor[1]), "anchor", "the last point"));
        }
        final Vec3 here = plane.at(u, v);
        Ref ru = null, rv = null;
        double au = 0, av = 0, bu = ALIGN_PX, bv = ALIGN_PX;
        for (Ref r : refs) {
            final double[] q = plane.uv(r.at());
            // the anchor's own point is not a line-up: it is a point snap, or nothing
            final double du = px(here, plane.at(q[0], v)), dv = px(here, plane.at(u, q[1]));
            if (du < bu) {
                bu = du;
                ru = r;
                au = q[0];
            }
            if (dv < bv) {
                bv = dv;
                rv = r;
                av = q[1];
            }
        }
        final List<Inference.Guide> guides = new ArrayList<>();
        String label = null;
        String kind = vp.gridSnap ? "grid" : "free";
        // 45 degrees from the last point of a line, when no axis holds (a narrower reach: it is a hint, not a rule)
        if (ru == null && rv == null && anchor != null) {
            final double du = u - anchor[0], dv = v - anchor[1];
            if (Math.abs(du) > 0 && Math.abs(dv) > 0) {
                final double d = (Math.abs(du) + Math.abs(dv)) / 2;
                final double tu = anchor[0] + Math.signum(du) * d, tv = anchor[1] + Math.signum(dv) * d;
                final Vec3 t = plane.at(tu, tv);
                if (px(here, t) < ALIGN_PX * 0.6 && px(plane.at(anchor[0], anchor[1]), t) > 3 * ALIGN_PX) {
                    guides.add(new Guide(plane.at(anchor[0], anchor[1]), t, OTHER, "45°"));
                    return new Tools.Snap(tu, tv, t, "axis", null, "45° from the last point", guides, hu, hv);
                }
            }
        }
        if (ru != null) {
            u = au;
        }
        if (rv != null) {
            v = av;
        }
        if (vp.gridSnap) {
            final double s = vp.gridStep();
            if (ru == null) {
                u = Math.rint(u / s) * s;
            }
            if (rv == null) {
                v = Math.rint(v / s) * s;
            }
        }
        final Vec3 p = plane.at(u, v);
        if (ru != null) {
            final double[] q = plane.uv(ru.at());
            // the guide runs along v from the reference
            guides.add(new Guide(plane.at(q[0], q[1]), p, axisColor(plane.v), ru.label()));
            label = lineUp(ru, plane, false);
            kind = "guide";
        }
        if (rv != null) {
            final double[] q = plane.uv(rv.at());
            guides.add(new Guide(plane.at(q[0], q[1]), p, axisColor(plane.u), rv.label()));
            label = label == null ? lineUp(rv, plane, true) : label + ", " + lineUp(rv, plane, true);
            kind = "guide";
        }
        return new Tools.Snap(u, v, p, kind, null, label, guides, hu, hv);
    }

    /**
     * True when a point of a volume can be seen from the eye: nothing nearer is drawn at its pixel. A corner
     * hidden behind its own volume often falls on the very pixel of a corner in front: it must not take it.
     */
    private boolean visible(Vec3 p) {
        final double[] s = vp.screenOf(p);
        if (s == null) {
            return false;
        }
        final Vec3 q = vp.pointAt((int) Math.floor(s[0]), (int) Math.floor(s[1]));
        if (q == null) {
            return true; // the background: a point on a silhouette
        }
        final double[] sq = vp.screenOf(q);
        if (sq == null) {
            return true;
        }
        return s[2] <= sq[2] + 4 * pixelAt(p) + 1e-6 * (1 + p.length());
    }

    /** "on the X axis" from the last point, "aligned with Endpoint · Box" from anything else. */
    private static String lineUp(Ref r, WorkPlane plane, boolean alongU) {
        if ("anchor".equals(r.kind())) {
            return "on the " + plane.axisName(alongU).replace("-", "") + " axis";
        }
        return "aligned with " + r.label();
    }

    private double lastScore;

    /** The closest of some points of a copy to the mouse, if closer than the best so far (scores in pixels). */
    private Ref closest(float[] pts, Geometry.Instance in, int mx, int my, String kind, String label, double penalty,
                        Ref best, double bestScore) {
        lastScore = bestScore;
        for (int i = 0; i + 2 < pts.length; i += 3) {
            final Vec3 p = in.world().apply(new Vec3(pts[i], pts[i + 1], pts[i + 2]));
            final double d = px(p, mx, my) + penalty;
            if (d < lastScore && visible(p)) {
                lastScore = d;
                best = new Ref(p, kind, label);
            }
        }
        return best;
    }

    private void acquire(Ref r) {
        final double tol = Math.max(1e-9, vp.camera.pixelSize() * 0.5);
        acquired.removeIf(a -> a.at().distance(r.at()) < tol);
        acquired.addFirst(r);
        while (acquired.size() > MAX_ACQUIRED) {
            acquired.removeLast();
        }
    }

    /** The copies under the mouse and around it (a corner is often on the silhouette), and those of the last snap. */
    private List<Geometry.Instance> instancesNear(int mx, int my) {
        final Map<Geometry.Instance, Boolean> found = new IdentityHashMap<>();
        final List<Geometry.Instance> out = new ArrayList<>();
        // the pixel under the mouse first, then a window around it: a corner's volume may lie on one side only
        final int r = (int) Math.round(POINT_PX);
        for (int ring = 0; ring <= r && out.size() < 6; ring += 3) {
            for (int dy = -ring; dy <= ring; dy += 3) {
                for (int dx = -ring; dx <= ring; dx += 3) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != ring) {
                        continue; // only the ring's border: the inside was looked at already
                    }
                    final Geometry.Instance in = vp.instanceAt(mx + dx, my + dy);
                    if (in != null && in.pv() != null && found.put(in, Boolean.TRUE) == null) {
                        out.add(in);
                    }
                }
            }
        }
        final List<Geometry.Instance> live = vp.model.geometry.instances();
        for (Geometry.Instance in : near) {
            if (out.size() >= 6) {
                break;
            }
            // only while it is still a copy of the document as it is
            if (found.put(in, Boolean.TRUE) == null && in.id() < live.size() && live.get(in.id()) == in) {
                out.add(in);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Distances along a line, and lengths
    // ------------------------------------------------------------------

    /**
     * A distance along a line from an origin (a height pulled off the plane, a face pushed along its normal):
     * stopped level with a point of interest around (the top of a neighbor, a corner), else matched to the
     * design's lengths, else on the grid. The guide of a level stays in alongGuide for the view to draw.
     */
    Length along(Vec3 origin, Vec3 dir, double raw, boolean free) {
        alongGuide = null;
        if (free) {
            return Length.plain(raw);
        }
        final Length l = level(origin, dir, raw);
        return l != null ? l : length(raw, origin.add(dir.mul(raw)), vp.gridSnap);
    }

    /** The world size of the 1-2-5 step of about five pixels at a point: what a pulled length is rounded to. */
    double fineStep(Vec3 at) {
        return step(5 * pixelAt(at));
    }

    /**
     * A distance along a line stopped level with a point of interest within a few pixels (its guide in
     * alongGuide), or null when none is in reach.
     */
    Length level(Vec3 origin, Vec3 dir, double raw) {
        alongGuide = null;
        final Vec3 at = origin.add(dir.mul(raw));
        double best = ALIGN_PX, val = raw;
        Ref hit = null;
        for (Ref r : alongRefs()) {
            final double t = r.at().sub(origin).dot(dir);
            if (Math.abs(t) < 1e-9) {
                continue; // the line's own start: no length
            }
            final double d = px(at, origin.add(dir.mul(t)));
            if (d < best) {
                best = d;
                val = t;
                hit = r;
            }
        }
        if (hit != null) {
            final Vec3 p = origin.add(dir.mul(val));
            alongGuide = new Guide(hit.at(), p, LEVEL, "level with " + hit.label());
            return new Length(val, null, "level with " + hit.label());
        }
        return null;
    }

    /** The points a distance along a line may stop level with: acquired, the tool's, and around the mouse. */
    private List<Ref> alongRefs() {
        final List<Ref> refs = new ArrayList<>(acquired);
        if (vp.tool != null) {
            refs.addAll(vp.tool.references());
        }
        final int mx = vp.mouseX(), my = vp.mouseY();
        if (mx >= 0) {
            for (Geometry.Instance in : instancesNear(mx, my)) {
                final Features f = featuresOf(in);
                if (f == null) {
                    continue;
                }
                final String name = in.lv().name();
                add(refs, f.corners(), in, "vertex", "Endpoint · " + name);
                add(refs, f.faces(), in, "center", "Face · " + name);
                add(refs, f.centers(), in, "center", "Center · " + name);
            }
        }
        return refs;
    }

    private static void add(List<Ref> refs, float[] pts, Geometry.Instance in, String kind, String label) {
        for (int i = 0; i + 2 < pts.length; i += 3) {
            refs.add(new Ref(in.world().apply(new Vec3(pts[i], pts[i + 1], pts[i + 2])), kind, label));
        }
    }

    /**
     * A length drawn with the mouse, matched within a few pixels to a variable of the design (kept by name:
     * the volume follows when the variable changes), else to a size already drawn, else on the grid.
     */
    Length length(double raw, Vec3 at, boolean round) {
        final Length m = match(raw, at);
        if (m != null) {
            return m;
        }
        return Length.plain(round ? round(raw, at) : raw);
    }

    /**
     * A length on round values: the 1-2-5 step that spans about five pixels where it is drawn, finer than the
     * grid, so a size pulled by hand is as precise as the hand and still a round number.
     */
    double round(double raw, Vec3 at) {
        final double s = step(5 * pixelAt(at));
        return Math.rint(raw / s) * s;
    }

    /** The smallest 1, 2 or 5 times a power of ten that is at least a value. */
    static double step(double min) {
        if (!(min > 0)) {
            return 1;
        }
        final double p10 = Math.pow(10, Math.floor(Math.log10(min)));
        for (double m : new double[] {1, 2, 5, 10}) {
            if (m * p10 >= min * (1 - 1e-12)) {
                return m * p10;
            }
        }
        return 10 * p10;
    }

    /**
     * A length matched within a few pixels to a variable of the design (written by name) or to a size already
     * drawn (written as its value); null when nothing is near.
     */
    Length match(double raw, Vec3 at) {
        final double tol = LENGTH_PX * pixelAt(at);
        final double a = Math.abs(raw), sign = raw < 0 ? -1 : 1;
        double best = tol;
        Length found = null;
        final Geometry g = vp.model.geometry;
        final Map<String, Boolean> memo = new HashMap<>();
        for (Define d : g.defines()) {
            if (d.error() != null || d.value() <= 0 || !isLength(g, d, memo, new HashSet<>())) {
                continue;
            }
            final double diff = Math.abs(d.value() - a);
            if (diff < best) {
                best = diff;
                found = new Length(sign * d.value(), (sign < 0 ? "-" : "") + d.name(), "= " + d.name());
            }
        }
        if (found != null) {
            return found;
        }
        int seen = 0;
        for (Solid s : g.solids()) {
            if (seen++ > 4000) {
                break; // a ROOT geometry of thousands of solids: the first ones are enough of a match
            }
            if (s.error() != null) {
                continue;
            }
            for (Solid.Param p : s.params()) {
                if (p.kind != Solid.Kind.LENGTH || p.error() != null || p.value() <= 0) {
                    continue;
                }
                final double diff = Math.abs(p.value() - a);
                if (diff < best) {
                    best = diff;
                    found = new Length(sign * p.value(), null, "= " + s.name() + " " + p.label.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return found;
    }

    private static final Pattern LENGTH_UNIT = Pattern.compile(
        "(?<![A-Za-z_0-9])(km|m|cm|mm|um|nm|pc|parsec|angstrom|fermi|kilometer|meter|centimeter|millimeter|micrometer"
            + "|nanometer|mum)(?![A-Za-z_0-9])");
    private static final Pattern ANGLE_UNIT = Pattern.compile("(?<![A-Za-z_0-9])(deg|degree|rad|radian|mrad|pi)(?![A-Za-z_0-9])");

    /** A define holds a length: typed so, or its expression has a length unit or names one that does. */
    private static boolean isLength(Geometry g, Define d, Map<String, Boolean> memo, Set<String> visiting) {
        final Boolean known = memo.get(d.name());
        if (known != null) {
            return known;
        }
        boolean r = false;
        if (d.type() != null && d.type().equalsIgnoreCase("length")) {
            r = true;
        } else if (d.unit() != null && LENGTH_UNIT.matcher(d.unit()).find()) {
            r = true;
        } else if (d.expr() != null && !ANGLE_UNIT.matcher(d.expr()).find()) {
            if (LENGTH_UNIT.matcher(d.expr()).find()) {
                r = true;
            } else if (visiting.add(d.name())) {
                try {
                    for (String v : Expr.parse(d.expr()).variables()) {
                        final Define o = g.define(v);
                        if (o != null && isLength(g, o, memo, visiting)) {
                            r = true;
                            break;
                        }
                    }
                } catch (GeometryException ignored) {
                    // not a length then
                }
            }
        }
        memo.put(d.name(), r);
        return r;
    }

    // ------------------------------------------------------------------
    // Screen measures
    // ------------------------------------------------------------------

    /** Pixels between a world point and a pixel; far when behind the eye. */
    private double px(Vec3 p, int mx, int my) {
        final double[] s = vp.screenOf(p);
        return s == null ? Double.MAX_VALUE : Math.hypot(s[0] - mx, s[1] - my);
    }

    /** Pixels between two world points on screen. */
    double px(Vec3 a, Vec3 b) {
        final double[] sa = vp.screenOf(a), sb = vp.screenOf(b);
        return sa == null || sb == null ? Double.MAX_VALUE : Math.hypot(sa[0] - sb[0], sa[1] - sb[1]);
    }

    /** The world size of a pixel at a point: the same everywhere in orthographic, by depth in perspective. */
    double pixelAt(Vec3 p) {
        final Camera c = vp.camera;
        if (!c.perspective || p == null) {
            return c.pixelSize();
        }
        final double[] s = c.project(p);
        if (s == null) {
            return c.pixelSize();
        }
        return 2 * s[2] * Math.tan(c.fovY / 2) / Math.max(1, c.height);
    }

    /** X red, Y green, Z blue, as the triad: the color of a guide running along a direction. */
    static int axisColor(Vec3 d) {
        final double ax = Math.abs(d.x()), ay = Math.abs(d.y()), az = Math.abs(d.z());
        if (ax > 0.99) {
            return AXIS_X;
        }
        if (ay > 0.99) {
            return AXIS_Y;
        }
        if (az > 0.99) {
            return AXIS_Z;
        }
        return OTHER;
    }

    // ------------------------------------------------------------------
    // Features of a tessellation
    // ------------------------------------------------------------------

    /** The features of a copy's solid, from a mesh made already when it is a boolean (never on this thread). */
    Features featuresOf(Geometry.Instance in) {
        final Solid s = in.lv().solid();
        if (s == null || s.error() != null) {
            return null;
        }
        Mesh m = s.meshIfReady(1);
        if (m == null) {
            m = s.meshIfReady(0);
        }
        if (m == null && !(s instanceof BooleanSolid)) {
            m = s.mesh(1);
        }
        if (m == null || m.isEmpty()) {
            return null;
        }
        return features.computeIfAbsent(m, Inference::extract);
    }

    /**
     * What a tessellation offers a hand: its corners (where feature edges meet or turn), the middles of its
     * straight edges between corners, the centers of its smooth loops (a tube's rims), and the centers of its
     * large flat faces. Facets of a curved surface are neither corners nor faces.
     */
    static Features extract(Mesh m) {
        final float[] e = m.featureEdges();
        final BBox b = m.bounds();
        final double q = Math.max(b.diagonal(), 1e-9) * 1e-6;
        // Weld the edges' ends into nodes
        final Map<List<Long>, Integer> ids = new HashMap<>();
        final List<double[]> nodes = new ArrayList<>();
        final List<List<Integer>> adj = new ArrayList<>();
        final Set<Long> seenEdges = new HashSet<>();
        for (int i = 0; i + 5 < e.length; i += 6) {
            final int a = node(e[i], e[i + 1], e[i + 2], q, ids, nodes, adj);
            final int c = node(e[i + 3], e[i + 4], e[i + 5], q, ids, nodes, adj);
            if (a == c) {
                continue;
            }
            final long key = Math.min(a, c) * 4_294_967_296L + Math.max(a, c);
            if (seenEdges.add(key)) {
                adj.get(a).add(c);
                adj.get(c).add(a);
            }
        }
        final int n = nodes.size();
        final boolean[] corner = new boolean[n];
        final boolean[] straight = new boolean[n];
        for (int i = 0; i < n; i++) {
            final List<Integer> nb = adj.get(i);
            if (nb.size() != 2) {
                corner[i] = !nb.isEmpty();
                continue;
            }
            final double turn = turn(nodes.get(nb.get(0)), nodes.get(i), nodes.get(nb.get(1)));
            corner[i] = turn > Math.toRadians(20);
            straight[i] = turn < Math.toRadians(1);
        }
        final List<Float> corners = new ArrayList<>(), mids = new ArrayList<>(), centers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (corner[i]) {
                push(corners, nodes.get(i));
            }
        }
        // Straight runs from corner to corner: their middles
        final Set<Long> walked = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (!corner[i]) {
                continue;
            }
            for (int first : adj.get(i)) {
                int prev = i, cur = first;
                boolean ok = true;
                while (!corner[cur]) {
                    if (!straight[cur]) {
                        ok = false;
                        break;
                    }
                    final List<Integer> nb = adj.get(cur);
                    final int next = nb.get(0) == prev ? nb.get(1) : nb.get(0);
                    prev = cur;
                    cur = next;
                    if (cur == i) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) {
                    continue;
                }
                final long key = Math.min(i, cur) * 4_294_967_296L + Math.max(i, cur);
                if (cur != i && walked.add(key)) {
                    final double[] a = nodes.get(i), c = nodes.get(cur);
                    push(mids, new double[] {(a[0] + c[0]) / 2, (a[1] + c[1]) / 2, (a[2] + c[2]) / 2});
                }
            }
        }
        // Smooth closed loops (rims of tubes, of cones, of spheres cut): their centers
        final boolean[] visited = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (visited[i] || corner[i] || adj.get(i).size() != 2) {
                continue;
            }
            final List<Integer> loop = new ArrayList<>();
            boolean closed = true;
            final ArrayDeque<Integer> stack = new ArrayDeque<>();
            stack.push(i);
            visited[i] = true;
            while (!stack.isEmpty()) {
                final int k = stack.pop();
                loop.add(k);
                for (int nb : adj.get(k)) {
                    if (corner[nb] || adj.get(nb).size() != 2) {
                        closed = false;
                    } else if (!visited[nb]) {
                        visited[nb] = true;
                        stack.push(nb);
                    }
                }
            }
            if (closed && loop.size() >= 6) {
                final double[] c = new double[3];
                for (int k : loop) {
                    for (int j = 0; j < 3; j++) {
                        c[j] += nodes.get(k)[j] / loop.size();
                    }
                }
                if (!near(centers, c, q * 100)) {
                    push(centers, c);
                }
            }
        }
        return new Features(floats(corners), floats(mids), floats(centers), faceCenters(m, q), e);
    }

    /** The centers of the flat faces that make a fair part of the surface (a box's six, a tube's two caps). */
    private static float[] faceCenters(Mesh m, double q) {
        final int nt = m.triangleCount();
        if (nt == 0 || nt > 200_000) {
            return new float[0];
        }
        final Map<List<Long>, double[]> groups = new HashMap<>();
        double total = 0;
        final float[] p = m.pos;
        for (int t = 0; t < nt; t++) {
            final int a = m.tri[t * 3] * 3, b = m.tri[t * 3 + 1] * 3, c = m.tri[t * 3 + 2] * 3;
            final double ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
            final double vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            final double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l < 1e-30) {
                continue;
            }
            final double area = l / 2;
            nx /= l;
            ny /= l;
            nz /= l;
            final double d = nx * p[a] + ny * p[a + 1] + nz * p[a + 2];
            final List<Long> key = List.of(Math.round(nx * 2000), Math.round(ny * 2000), Math.round(nz * 2000),
                Math.round(d / (q * 1000)));
            final double[] g = groups.computeIfAbsent(key, k -> new double[4]);
            g[0] += area;
            g[1] += area * (p[a] + p[b] + p[c]) / 3;
            g[2] += area * (p[a + 1] + p[b + 1] + p[c + 1]) / 3;
            g[3] += area * (p[a + 2] + p[b + 2] + p[c + 2]) / 3;
            total += area;
        }
        final List<Float> out = new ArrayList<>();
        for (double[] g : groups.values()) {
            if (g[0] > 0.04 * total) {
                push(out, new double[] {g[1] / g[0], g[2] / g[0], g[3] / g[0]});
            }
        }
        return floats(out);
    }

    private static int node(double x, double y, double z, double q, Map<List<Long>, Integer> ids, List<double[]> nodes,
                            List<List<Integer>> adj) {
        final List<Long> key = List.of(Math.round(x / q), Math.round(y / q), Math.round(z / q));
        Integer id = ids.get(key);
        if (id == null) {
            id = nodes.size();
            ids.put(key, id);
            nodes.add(new double[] {x, y, z});
            adj.add(new ArrayList<>(2));
        }
        return id;
    }

    /** The angle a path turns by at b, coming from a and going to c: 0 straight on. */
    private static double turn(double[] a, double[] b, double[] c) {
        final double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        final double vx = c[0] - b[0], vy = c[1] - b[1], vz = c[2] - b[2];
        final double lu = Math.sqrt(ux * ux + uy * uy + uz * uz), lv = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (lu < 1e-30 || lv < 1e-30) {
            return Math.PI;
        }
        final double cos = (ux * vx + uy * vy + uz * vz) / (lu * lv);
        return Math.acos(Math.max(-1, Math.min(1, cos)));
    }

    private static void push(List<Float> out, double[] p) {
        out.add((float) p[0]);
        out.add((float) p[1]);
        out.add((float) p[2]);
    }

    private static boolean near(List<Float> pts, double[] p, double tol) {
        for (int i = 0; i + 2 < pts.size(); i += 3) {
            if (Math.abs(pts.get(i) - p[0]) < tol && Math.abs(pts.get(i + 1) - p[1]) < tol
                && Math.abs(pts.get(i + 2) - p[2]) < tol) {
                return true;
            }
        }
        return false;
    }

    private static float[] floats(List<Float> l) {
        final float[] f = new float[l.size()];
        for (int i = 0; i < f.length; i++) {
            f[i] = l.get(i);
        }
        return f;
    }
}

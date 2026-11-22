package com.sphere.components.g4editor;

import com.sphere.core.geant4.BBox;
import com.sphere.core.geant4.BeamPhysics;
import com.sphere.core.geant4.Define;
import com.sphere.core.geant4.Expr;
import com.sphere.core.geant4.Gdml;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.GeometryException;
import com.sphere.core.geant4.GoalSeek;
import com.sphere.core.geant4.Inputs;
import com.sphere.core.geant4.Units;
import com.sphere.core.geant4.Vec3;
import com.sphere.core.geantbackend.RunComparison;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.table.DefaultTableModel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Physics Live: Creo Simulation Live's idea, for a detector. What the beam does in the design (the shower, what of it
 * the detector keeps, where the rest leaks, the sampling) is drawn on the model and worked out again at every edit,
 * as a plate is dragged or a thickness typed: the beam's line, the energy left along it, the shower's maximum and
 * the cylinder holding 95% of it, red where it sticks out of the detector; and a card of the numbers.
 *
 * <p>The beam is the design's particle gun, the one the Run panel fires and the exported C++ uses. The panel below
 * the drawing sets it, lists the layers crossed, and seeks goals: the value of a variable meeting a physics target,
 * tried on a copy of the design (GoalSeek), applied as one step undo takes back.
 */
final class SphereLive {

    private static final String[] PARTICLES = {"e-", "e+", "gamma", "mu-", "mu+", "pi-", "pi+", "proton", "neutron",
        "kaon-", "geantino"};
    private static final Color BEAM = new Color(0x38C8F0);
    private static final Color LEAK = new Color(0xE8483C);
    private static final Color OK = new Color(0x52C46A);
    private static final Color WARN = new Color(0xF0B030);
    private static final int CARD_W = 304;

    private final EditorModel model;
    private final Viewport vp;
    private boolean on;
    private BeamPhysics.Report report;
    private long revision = -1;
    private String beamKey = "";
    private boolean panelStale = true;
    /** Where the card was last drawn: the beam's label keeps out of it. */
    private Rectangle lastCard;
    private final Timer timer;
    /** Where the card was drawn last, for clicks; null when not shown. */
    private Rectangle card;
    /** Called after the beam changed here: the Run panel shows the new gun. */
    Runnable gunChanged = () -> {
    };
    /** Shows the panel (the bottom tab). */
    Runnable showPanel = () -> {
    };
    /** Runs the beam in Geant4 (the Run panel, with its events and physics list). */
    Runnable checkInGeant4 = () -> {
    };
    /** The last Geant4 run's answer, the design it was run on (designKey, the gun included), and its comparison. */
    private Map<String, Object> run;
    private String runDesign = "";
    private RunComparison.Result comparison;
    /** The design's key, worked out again only when the design changed. */
    private String designKey = "";
    private long keyRevision = Long.MIN_VALUE;
    final Panel panel;

    SphereLive(EditorModel model, Viewport vp) {
        this.model = model;
        this.vp = vp;
        this.timer = new Timer(30, e -> refresh());
        timer.setRepeats(false);
        this.panel = new Panel();
        model.addListener(new EditorModel.Listener() {
            @Override
            public void documentChanged(Geometry.Change change) {
                schedule();
                panel.variablesChanged();
            }

            @Override
            public void fileChanged() {
                schedule();
            }
        });
    }

    boolean isOn() {
        return on;
    }

    /** Physics Live on or off, the same choice in G4editor and G4code (kept between sessions). */
    static boolean preferred() {
        return java.util.prefs.Preferences.userNodeForPackage(SphereLive.class).getBoolean("physicsLive", true);
    }

    static void prefer(boolean on) {
        java.util.prefs.Preferences.userNodeForPackage(SphereLive.class).putBoolean("physicsLive", on);
    }

    /** What "Check in Geant4" does; null hides it (G4code's Run launches the application itself). */
    void setCheckInGeant4(Runnable r) {
        checkInGeant4 = r == null ? () -> {
        } : r;
        panel.check.setVisible(r != null);
    }

    void setOn(boolean b) {
        on = b;
        panel.live.setSelected(b);
        if (b) {
            refresh();
        }
        vp.repaint();
    }

    BeamPhysics.Report report() {
        if (report == null) {
            refresh();
        }
        return report;
    }

    /** At most every 30 ms while a drag edits the design: the numbers follow the mouse. */
    private void schedule() {
        if ((on || panel.isShowing()) && !timer.isRunning()) {
            timer.start();
        }
    }

    private static String key(BeamPhysics.Beam b) {
        return b.particle() + " " + b.energy() + " " + b.origin() + " " + b.direction();
    }

    /** Works the physics out again when the design or the beam changed since. */
    void refresh() {
        final Geometry g = model.geometry;
        final BeamPhysics.Beam b = BeamPhysics.Beam.of(g);
        final String k = key(b);
        if (report != null && g.revision() == revision && k.equals(beamKey)) {
            return;
        }
        try {
            report = BeamPhysics.analyze(g, b);
        } catch (RuntimeException ex) {
            report = null; // a design half evaluated: the next edit tries again
        }
        revision = g.revision();
        if (!k.equals(beamKey)) {
            beamKey = k;
            panel.showBeam(b);
        }
        compareRun();
        panelStale = true;
        if (panel.isShowing()) {
            syncPanel();
        }
        vp.repaint();
    }

    // ------------------------------------------------------------------
    // Against Geant4
    // ------------------------------------------------------------------

    /**
     * A Geant4 run's answer (the Run panel's), for the design as it was at the revision given: compared with the
     * prediction while neither the design nor the beam changes. Returns the comparison, or null when the run was of
     * another beam.
     */
    RunComparison.Result runFinished(Map<String, Object> answer, String design) {
        run = answer;
        runDesign = design == null ? "" : design;
        keyRevision = Long.MIN_VALUE; // the run wrote its gun into the settings, which no rebuild follows
        refresh();
        compareRun();
        panelStale = true;
        if (panel.isShowing()) {
            syncPanel();
        }
        panel.result.setText(comparison == null ? "The Geant4 run was of another beam or design than Physics Live's."
            : comparison.kept() == null ? "Geant4 ran " + comparison.events() + " events: see the comparison above."
            : String.format(Locale.ROOT, "Geant4 kept %.1f ± %.1f %% of the beam, Physics Live predicted %.1f %%.",
            comparison.kept().measured(), comparison.kept().error(), comparison.kept().predicted()));
        vp.repaint();
        return comparison;
    }

    RunComparison.Result comparison() {
        return comparison;
    }

    /** The comparison holds while the design (its gun included) is the run's: an undo back to it brings it back. */
    private void compareRun() {
        final Geometry g = model.geometry;
        if (run == null || report == null) {
            comparison = null;
            return;
        }
        if (g.revision() != keyRevision) {
            designKey = designKey(g);
            keyRevision = g.revision();
        }
        comparison = designKey.equals(runDesign) ? RunComparison.compare(report, run, g) : null;
    }

    /** What tells a design from another: its GDML (the gun is in it, as auxiliary settings), hashed. */
    static String designKey(Geometry g) {
        final String t = Gdml.write(g);
        return Integer.toHexString(t.hashCode()) + ":" + t.length();
    }

    /** The panel's numbers and layers, written when it shows: a drag does not redraw a table nobody sees. */
    void syncPanel() {
        if (panelStale) {
            panelStale = false;
            panel.show(report);
        }
    }

    // ------------------------------------------------------------------
    // The beam
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Goals: what they turn, and where their answers are written
    // ------------------------------------------------------------------

    /**
     * Where goals find their knobs and write their answers: the design's variables in G4editor, the C++ constants in
     * G4code.
     */
    interface Levers {
        /** The names a goal may turn, for the panel's list. */
        List<String> names();

        /** Taken on the event thread: what the search then runs on, off it. */
        Search prepare(BeamPhysics.Beam beam);

        /** Writes an answer, as one step undo takes back; says what it did. */
        String apply(GoalSeek.Result r);

        /** Why nothing could be turned. */
        default String empty() {
            return "make the design parametric first (Model > Relations, or Variables)";
        }
    }

    /** A search on a copy taken on the event thread: the knobs ranked by their effect, a goal solved. */
    interface Search {
        List<GoalSeek.Lever> rank(GoalSeek.Metric m) throws GeometryException;

        GoalSeek.Result solve(GoalSeek.Goal goal, String name) throws GeometryException;
    }

    /** G4editor's: the design's variables, each change one step of the document. */
    private final class DesignLevers implements Levers {
        public List<String> names() {
            final List<String> out = new ArrayList<>();
            for (Define d : model.geometry.defines()) {
                if (GoalSeek.isLever(d)) {
                    out.add(d.name());
                }
            }
            return out;
        }

        public Search prepare(BeamPhysics.Beam beam) {
            final String gdml = Gdml.write(model.geometry);
            return new Search() {
                private Geometry copy;

                private Geometry copy() throws GeometryException {
                    if (copy == null) {
                        copy = Gdml.read(gdml, null).geometry();
                        copy.rebuild();
                    }
                    return copy;
                }

                public List<GoalSeek.Lever> rank(GoalSeek.Metric m) throws GeometryException {
                    return GoalSeek.levers(copy(), beam, m);
                }

                public GoalSeek.Result solve(GoalSeek.Goal goal, String name) throws GeometryException {
                    return GoalSeek.solve(copy(), beam, goal, name);
                }
            };
        }

        public String apply(GoalSeek.Result r) {
            final Define d = model.geometry.define(r.variable());
            if (d == null) {
                return "The variable " + r.variable() + " is gone.";
            }
            model.edit("Goal " + r.goal().describe(), () -> {
                d.setExpr(r.after());
                if (r.worldGrown()) {
                    model.geometry.rebuild();
                    GoalSeek.growWorld(model.geometry);
                }
            });
            model.log(r.command());
            return "Applied: " + r.command() + (r.worldGrown() ? " (the world grew to hold it)" : "") + ". Ctrl+Z takes it back.";
        }
    }

    /** What goals turn; G4code sets its own. */
    Levers levers = new DesignLevers();
    /** Writes a beam chosen in the panel; G4code writes it into the application's code. Null: the design's gun. */
    java.util.function.Consumer<BeamPhysics.Beam> beamWriter;

    /** Makes a beam the design's gun, as one step. */
    void setBeam(BeamPhysics.Beam nb) {
        if (beamWriter != null) {
            beamWriter.accept(nb);
            refresh();
            return;
        }
        model.edit("Beam " + nb.describe(), () -> nb.store(model.geometry));
        final Vec3 o = nb.origin(), d = nb.direction();
        model.log("beam " + nb.particle().g4Name + " " + Units.best(nb.energy(), Units.Category.ENERGY).replace(" ", "")
            + " at " + Units.num(o.x()) + "mm " + Units.num(o.y()) + "mm " + Units.num(o.z()) + "mm toward "
            + Units.num(d.x()) + " " + Units.num(d.y()) + " " + Units.num(d.z()));
        gunChanged.run();
        refresh();
    }

    /** Aims the beam along a direction, through a point (null: through the world's center), from the world's edge. */
    void aim(Vec3 dir, Vec3 through) {
        final Geometry g = model.geometry;
        final Vec3 d = dir.unit();
        final BeamPhysics.Beam b = BeamPhysics.Beam.of(g);
        setBeam(b.aimed(through == null ? BeamPhysics.Beam.entry(g, d) : entryThrough(g, through, d), d));
    }

    /** Through the selection, along the beam's direction; false when nothing is selected. */
    boolean aimAtSelection() {
        final BBox box = Tools.selectionBox(vp);
        if (box == null || box.isEmpty()) {
            return false;
        }
        aim(BeamPhysics.Beam.of(model.geometry).direction(), box.center());
        return true;
    }

    /** Along the line of sight, through the middle of the view. */
    void aimAlongView() {
        final Vec3 d = vp.camera.target.sub(vp.camera.eye());
        if (d.length() > 1e-12) {
            aim(d, vp.camera.target);
        }
    }

    /** Just inside the world, where a beam going through a point along a direction comes in. */
    static Vec3 entryThrough(Geometry g, Vec3 p, Vec3 d) {
        if (g.world() == null || g.world().solid() == null || g.world().solid().error() != null) {
            return p.sub(d.mul(1000));
        }
        final BBox b = g.world().solid().extent();
        double t = Double.MAX_VALUE;
        final double[] lo = {b.minX(), b.minY(), b.minZ()}, hi = {b.maxX(), b.maxY(), b.maxZ()};
        for (int k = 0; k < 3; k++) {
            final double dk = d.get(k);
            if (Math.abs(dk) > 1e-12) {
                t = Math.min(t, (dk > 0 ? p.get(k) - lo[k] : hi[k] - p.get(k)) / Math.abs(dk));
            }
        }
        return p.sub(d.mul(0.99 * Math.max(0, t)));
    }

    // ------------------------------------------------------------------
    // On the drawing
    // ------------------------------------------------------------------

    boolean hit(int x, int y) {
        return card != null && card.contains(x, y);
    }

    /**
     * A click on the card: on its fold mark it folds into a pill, on the pill it opens again, elsewhere it opens the
     * panel. The choice holds over the view's size, which otherwise decides (a narrow view gets the pill).
     */
    void clicked(int x, int y) {
        if (pill) {
            userFold = Boolean.FALSE;
        } else if (foldBox != null && foldBox.contains(x, y)) {
            userFold = Boolean.TRUE;
        } else {
            showPanel.run();
            return;
        }
        vp.repaint();
    }

    /** True while the card is folded into its pill. */
    boolean folded() {
        return pill;
    }

    /** False: the beam and its shower alone, no card (the template gallery's previews). */
    boolean showCard = true;

    /** A view narrower or lower than this gets the pill, unless the card was opened or folded by hand. */
    private static final int ROOMY_WIDTH = 640, ROOMY_HEIGHT = 420;
    /** The card folded (true) or opened (false) by hand; null: the view's size decides. */
    private Boolean userFold;
    /** The card is drawn folded; where its fold mark is when it is open. */
    private boolean pill;
    private Rectangle foldBox;

    /** The beam, the shower and the card, over the drawing. */
    void paint(Graphics2D g0, int top) {
        card = null;
        if (!on) {
            return;
        }
        final Geometry geo = model.geometry;
        if (report == null || geo.revision() != revision || !key(BeamPhysics.Beam.of(geo)).equals(beamKey)) {
            refresh();
        }
        final BeamPhysics.Report r = report;
        if (r == null) {
            return;
        }
        final Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        try {
            // folded into a pill: by hand, else when the view is too small for the card and the drawing both
            pill = showCard && (userFold != null ? userFold : vp.getWidth() < ROOMY_WIDTH || vp.getHeight() < ROOMY_HEIGHT);
            beam(g, r);
            if (showCard) {
                card(g, r, top);
            }
            lastCard = card;
        } finally {
            g.dispose();
        }
    }

    private Vec3 at(BeamPhysics.Report r, double s) {
        return r.beam().origin().addScaled(r.beam().direction(), s);
    }

    private void beam(Graphics2D g, BeamPhysics.Report r) {
        final double start = r.hit() ? r.start() : 0;
        final double end = r.hit() ? r.end() : 1000;
        final double span = Math.max(end - start, 1);
        final double s0 = r.hit() ? Math.max(0, start - 0.45 * span) : 0, s1 = end + 0.2 * span;
        final double[] a = vp.screenOf(at(r, s0)), z = vp.screenOf(at(r, s1));
        if (a == null || z == null) {
            return;
        }
        // the beam's line, dashed, and its arrow where it comes in
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {7f, 5f}, 0f));
        g.setColor(BEAM);
        g.drawLine((int) a[0], (int) a[1], (int) z[0], (int) z[1]);
        final double sa = r.hit() ? Math.max(s0, start - 0.15 * span) : s0 + 0.5 * (s1 - s0);
        final double[] p = vp.screenOf(at(r, sa)), q = vp.screenOf(at(r, sa + 0.02 * span));
        if (p != null && q != null) {
            final double ang = Math.atan2(q[1] - p[1], q[0] - p[0]);
            final Path2D head = new Path2D.Double();
            head.moveTo(p[0] + 11 * Math.cos(ang), p[1] + 11 * Math.sin(ang));
            head.lineTo(p[0] - 7 * Math.cos(ang - 0.5), p[1] - 7 * Math.sin(ang - 0.5));
            head.lineTo(p[0] - 7 * Math.cos(ang + 0.5), p[1] - 7 * Math.sin(ang + 0.5));
            head.closePath();
            g.setStroke(new BasicStroke(1.4f));
            g.fill(head);
            final String label = r.beam().particle().symbol() + " " + Units.best(r.beam().energy(), Units.Category.ENERGY);
            g.setFont(SphereCreo.UI_BOLD);
            final FontMetrics lf = g.getFontMetrics();
            final int lx = (int) (p[0] - 12 * Math.cos(ang)) - lf.stringWidth(label) / 2, ly = (int) (p[1] - 12 * Math.sin(ang)) - 10;
            if (lastCard == null || !lastCard.intersects(lx - 2, ly - lf.getAscent(), lf.stringWidth(label) + 4, lf.getHeight())) {
                outlined(g, label, lx, ly, BEAM);
            }
        }
        if (!r.hit()) {
            return;
        }
        // the energy left along the beam, in heat colors
        double peak = 0;
        for (BeamPhysics.Layer l : r.layers()) {
            if (l.thickness() > 0) {
                peak = Math.max(peak, l.edep() / l.thickness());
            }
        }
        if (peak > 0) {
            g.setStroke(new BasicStroke(6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            for (BeamPhysics.Layer l : r.layers()) {
                final double t = l.thickness() > 0 ? l.edep() / l.thickness() / peak : 0;
                if (t < 0.01) {
                    continue;
                }
                final double[] u = vp.screenOf(at(r, l.from())), v = vp.screenOf(at(r, l.to()));
                if (u != null && v != null) {
                    g.setColor(heat(t));
                    g.drawLine((int) u[0], (int) u[1], (int) v[0], (int) v[1]);
                }
            }
        }
        if (pill) {
            return; // folded: the beam and its heat only, the drawing kept clear
        }
        // the shower's maximum, and the cylinder holding 95% of it
        final double radius, depth95, edge;
        if (r.shower() != null) {
            radius = r.shower().width95();
            depth95 = along(r, true, r.layers().get(r.first()).depthX0() + r.shower().t95());
            edge = r.shower().edge();
        } else if (r.hadronic() != null) {
            radius = r.lambda() > 0 ? r.depth() / r.lambda() : 0;
            depth95 = along(r, false, r.layers().get(r.first()).depthLambda() + r.hadronic().l95());
            edge = r.hadronic().edge();
        } else {
            return;
        }
        final Vec3 d = r.beam().direction(), u = d.orthogonal().unit(), w = d.cross(u).unit();
        final boolean wide = radius > edge;
        final double sEnd = Math.min(depth95, end), max = r.maxAt();
        final Color glass = vp.darkTheme ? new Color(235, 240, 248, 200) : new Color(40, 50, 70, 190);
        ring(g, at(r, start), u, w, radius, wide ? LEAK : glass, true);
        ring(g, at(r, max), u, w, radius, wide ? LEAK : glass, false);
        ring(g, at(r, sEnd), u, w, radius, wide ? LEAK : glass, true);
        // past the detector, the cylinder is drawn over half its length at most, its end written
        final double reach = Math.min(depth95, end + 0.5 * span);
        for (int k = 0; k < 4; k++) {
            final double th = k * Math.PI / 2;
            final Vec3 off = u.mul(Math.cos(th) * radius).add(w.mul(Math.sin(th) * radius));
            line(g, at(r, start).add(off), at(r, sEnd).add(off), wide ? LEAK : glass, 1f);
            if (depth95 > end) {
                line(g, at(r, end).add(off), at(r, reach).add(off), LEAK, 1.6f);
            }
        }
        if (depth95 > end) {
            ring(g, at(r, reach), u, w, radius, LEAK, reach < depth95);
            final double[] e = vp.screenOf(at(r, reach));
            if (e != null) {
                g.setFont(SphereCreo.SMALL);
                outlined(g, "95 % at " + len(depth95 - start) + (reach < depth95 ? " →" : ""), (int) e[0] + 8,
                    (int) e[1] + 14, LEAK);
            }
        }
        final double[] m = vp.screenOf(at(r, max));
        if (m != null) {
            g.setColor(new Color(255, 236, 140));
            final int mx = (int) m[0], my = (int) m[1];
            g.fillPolygon(new int[] {mx, mx + 6, mx, mx - 6}, new int[] {my - 6, my, my + 6, my}, 4);
            g.setFont(SphereCreo.SMALL);
            final String txt = r.shower() != null ? String.format(Locale.ROOT, "max %.1f X₀", r.shower().tMax())
                : String.format(Locale.ROOT, "max %.2f λI", r.hadronic().tMax());
            outlined(g, txt, mx + 9, my - 8, new Color(255, 236, 140));
        }
    }

    /** The distance along the beam where the depth crossed (X0 or lambda, from the gun) reaches t; past the end, extrapolated. */
    private static double along(BeamPhysics.Report r, boolean x0, double t) {
        final List<BeamPhysics.Layer> ls = r.layers();
        for (int i = r.first(); i <= r.last(); i++) {
            final BeamPhysics.Layer l = ls.get(i);
            final double d0 = x0 ? l.depthX0() : l.depthLambda(), dl = x0 ? l.x0() : l.lambda();
            if (t <= d0 + dl && dl > 0) {
                return l.from() + Math.max(0, (t - d0) / dl) * l.thickness();
            }
        }
        final BeamPhysics.Layer last = ls.get(r.last());
        final double total = x0 ? r.x0() : r.lambda();
        final double end = (x0 ? last.depthX0() + last.x0() : last.depthLambda() + last.lambda());
        return last.to() + (total > 0 ? (t - end) * r.depth() / total : 0);
    }

    private void ring(Graphics2D g, Vec3 c, Vec3 u, Vec3 w, double radius, Color color, boolean dashed) {
        final Path2D path = new Path2D.Double();
        for (int k = 0; k <= 40; k++) {
            final double th = 2 * Math.PI * k / 40;
            final double[] s = vp.screenOf(c.add(u.mul(Math.cos(th) * radius)).add(w.mul(Math.sin(th) * radius)));
            if (s == null) {
                return;
            }
            if (k == 0) {
                path.moveTo(s[0], s[1]);
            } else {
                path.lineTo(s[0], s[1]);
            }
        }
        g.setColor(color);
        g.setStroke(dashed ? new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] {4f, 4f}, 0f)
            : new BasicStroke(1.4f));
        g.draw(path);
    }

    private void line(Graphics2D g, Vec3 a, Vec3 b, Color c, float width) {
        final double[] p = vp.screenOf(a), q = vp.screenOf(b);
        if (p != null && q != null) {
            g.setColor(c);
            g.setStroke(new BasicStroke(width));
            g.drawLine((int) p[0], (int) p[1], (int) q[0], (int) q[1]);
        }
    }

    /** Dark red through orange and yellow to white. */
    static Color heat(double t) {
        final double x = Math.max(0, Math.min(1, t));
        final int r = (int) (150 + 105 * Math.min(1, x * 2));
        final int g = (int) (30 + 225 * Math.max(0, Math.min(1, (x - 0.2) / 0.6)));
        final int b = (int) (20 + 200 * Math.max(0, (x - 0.75) / 0.25));
        return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b), 235);
    }

    private void outlined(Graphics2D g, String s, int x, int y, Color c) {
        g.setColor(vp.darkTheme ? new Color(0, 0, 0, 170) : new Color(255, 255, 255, 200));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) {
                    g.drawString(s, x + dx, y + dy);
                }
            }
        }
        g.setColor(c);
        g.drawString(s, x, y);
    }

    /** The numbers, top left of the drawing, below the color legend when one is shown. */
    private void card(Graphics2D g, BeamPhysics.Report r, int top) {
        final boolean dark = vp.darkTheme;
        final Color text = dark ? new Color(225, 230, 238) : new Color(30, 38, 52);
        final Color dim = dark ? new Color(150, 160, 176) : new Color(100, 110, 128);
        final List<Object[]> rows = new ArrayList<>(); // label, value, color (or null)
        if (!r.hit()) {
            rows.add(new Object[] {"Beam", "misses the detector", WARN});
        } else if (r.shower() != null) {
            final BeamPhysics.Shower s = r.shower();
            rows.add(new Object[] {"Kept", String.format(Locale.ROOT, "%.1f %%", 100 * s.contained()), grade(s.contained())});
            rows.add(new Object[] {"Leaks", String.format(Locale.ROOT, "%.1f %% back, %.1f %% sides", 100 * Math.max(0, 1
                - s.upstream() - s.longitudinal()), 100 * (1 - s.lateral())), null});
            rows.add(depth(r));
            final BeamPhysics.Layer ml = r.layerAt(s.maxAt());
            rows.add(new Object[] {"Maximum", String.format(Locale.ROOT, "%.1f X₀%s", s.tMax(), ml == null ? ""
                : " in " + ml.volume()), null});
            rows.add(new Object[] {"95 % in", String.format(Locale.ROOT, "%.1f X₀, %s around", s.t95(),
                len(s.width95())), s.t95() > r.x0() || s.width95() > s.edge() ? LEAK : null});
        } else if (r.hadronic() != null) {
            final BeamPhysics.Hadronic h = r.hadronic();
            rows.add(new Object[] {"Kept", String.format(Locale.ROOT, "%.1f %%", 100 * h.contained()), grade(h.contained())});
            rows.add(new Object[] {"Interact", String.format(Locale.ROOT, "%.0f %%", 100 * h.interacts()), null});
            rows.add(depth(r));
            rows.add(new Object[] {"95 % in", String.format(Locale.ROOT, "%.1f λI", h.l95()), h.l95() > r.lambda() ? LEAK
                : null});
        } else if (r.muon() != null) {
            rows.add(new Object[] {"Loses", Units.best(r.muon().loss(), Units.Category.ENERGY) + (r.muon().stopsIn() >= 0
                ? ", stops at " + len(r.muon().stopAt() - r.start()) : ""), null});
            rows.add(new Object[] {"Signal", Units.best(r.muon().visible(), Units.Category.ENERGY) + " in the active layers", null});
            rows.add(depth(r));
        } else {
            rows.add(depth(r));
        }
        if (r.sampling() != null && r.beam().particle().kind != BeamPhysics.Particle.Kind.MUON) {
            rows.add(new Object[] {"Sampling", String.format(Locale.ROOT, "%.2f %% mip, %.1f %%/√E", 100 * r.sampling().mip(),
                100 * r.sampling().stochastic()), null});
        }
        // the last Geant4 run, while it is of this design and this beam
        if (run != null) {
            final RunComparison.Result c = comparison;
            if (c != null && c.kept() != null) {
                rows.add(new Object[] {"Geant4", String.format(Locale.ROOT, "kept %.1f ± %.1f %% (%d events)",
                    c.kept().measured(), c.kept().error(), c.events()), c.kept().agrees() ? OK : WARN});
            } else if (c == null) {
                rows.add(new Object[] {"Geant4", "changed since its run", dim});
            }
        }
        g.setFont(SphereCreo.UI);
        final FontMetrics fm = g.getFontMetrics();
        if (pill) {
            pill(g, r, top, text, dim);
            return;
        }
        final int lh = fm.getHeight() + 1;
        final List<String> note = r.notes().isEmpty() ? List.of() : wrap(r.notes().get(0), fm, CARD_W - 20, 2);
        final boolean chart = r.hit();
        final int h = 8 + lh + 4 + rows.size() * lh + (chart ? 50 : 0) + note.size() * lh + lh + 6;
        final int x = 12, y = Math.max(12, top + 8);
        card = new Rectangle(x, y, CARD_W, h);
        g.setColor(dark ? new Color(26, 30, 38, 222) : new Color(255, 255, 255, 228));
        g.fillRoundRect(x, y, CARD_W, h, 8, 8);
        g.setColor(dark ? new Color(90, 100, 120) : new Color(160, 170, 188));
        g.drawRoundRect(x, y, CARD_W, h, 8, 8);
        // the title, and the beam
        int cy = y + 6 + fm.getAscent();
        g.setFont(SphereCreo.UI_BOLD);
        g.setColor(text);
        g.drawString("Physics Live", x + 10, cy);
        g.setFont(SphereCreo.UI);
        final String beam = r.beam().particle().symbol() + "  " + Units.best(r.beam().energy(), Units.Category.ENERGY) + "  "
            + BeamPhysics.Beam.toward(r.beam().direction()).replace("along ", "");
        g.setColor(BEAM);
        g.drawString(beam, x + CARD_W - 30 - fm.stringWidth(beam), cy);
        // the fold mark, top right: the card becomes a pill and the drawing shows again
        foldBox = new Rectangle(x + CARD_W - 22, y + 5, 16, 16);
        g.setColor(dim);
        g.drawRoundRect(foldBox.x, foldBox.y, foldBox.width - 1, foldBox.height - 1, 4, 4);
        g.drawLine(foldBox.x + 4, foldBox.y + 8, foldBox.x + 11, foldBox.y + 8);
        cy += lh + 4;
        for (Object[] row : rows) {
            g.setColor(dim);
            g.drawString((String) row[0], x + 10, cy);
            g.setColor(row[2] == null ? text : (Color) row[2]);
            g.drawString((String) row[1], x + 82, cy);
            cy += lh;
        }
        if (chart) {
            profile(g, r, x + 10, cy - fm.getAscent() + 4, CARD_W - 20, 42, dark);
            cy += 50;
        }
        g.setColor(WARN);
        for (String l : note) {
            g.drawString(l, x + 10, cy);
            cy += lh;
        }
        g.setFont(SphereCreo.SMALL);
        g.setColor(dim);
        g.drawString("Click: the layers, the beam, goals  ·  – folds it", x + 10, cy);
    }

    /** The card folded: the number that matters on one line, a chevron to open it again. */
    private void pill(Graphics2D g, BeamPhysics.Report r, int top, Color text, Color dim) {
        final FontMetrics fm = g.getFontMetrics();
        final String main;
        final Color color;
        if (!r.hit()) {
            main = "the beam misses the detector";
            color = WARN;
        } else if (!Double.isNaN(r.contained())) {
            main = String.format(Locale.ROOT, "kept %.1f %%", 100 * r.contained());
            color = grade(r.contained());
        } else if (r.muon() != null) {
            main = r.muon().stopsIn() >= 0 ? "stops at " + len(r.muon().stopAt() - r.start())
                : "loses " + Units.best(r.muon().loss(), Units.Category.ENERGY);
            color = text;
        } else {
            main = String.format(Locale.ROOT, "%.1f X₀", r.x0());
            color = text;
        }
        final String rest = r.hit() && !Double.isNaN(r.contained()) ? String.format(Locale.ROOT, "  ·  %.1f X₀", r.x0())
            : "";
        final int pad = 8, icon = 16;
        final int w = pad + icon + 6 + fm.stringWidth(main) + fm.stringWidth(rest) + 10 + 8 + pad;
        final int h = fm.getHeight() + 8;
        final int x = 12, y = Math.max(12, top + 8);
        card = new Rectangle(x, y, w, h);
        foldBox = null;
        g.setColor(vp.darkTheme ? new Color(26, 30, 38, 222) : new Color(255, 255, 255, 228));
        g.fillRoundRect(x, y, w, h, h, h);
        g.setColor(vp.darkTheme ? new Color(90, 100, 120) : new Color(160, 170, 188));
        g.drawRoundRect(x, y, w, h, h, h);
        Icons.shower(icon).paintIcon(vp, g, x + pad, y + (h - icon) / 2);
        final int base = y + (h - fm.getHeight()) / 2 + fm.getAscent();
        int cx = x + pad + icon + 6;
        g.setColor(color);
        g.drawString(main, cx, base);
        cx += fm.stringWidth(main);
        g.setColor(dim);
        g.drawString(rest, cx, base);
        // the chevron that opens the card again
        final int ax = x + w - pad - 6, ay = y + h / 2;
        g.fillPolygon(new int[] {ax - 3, ax + 3, ax - 3}, new int[] {ay - 4, ay, ay + 4}, 3);
    }

    private static Object[] depth(BeamPhysics.Report r) {
        return new Object[] {"Depth", String.format(Locale.ROOT, "%.1f X₀ · %.2f λI · %s", r.x0(), r.lambda(),
            len(r.depth())), null};
    }

    /** A length with three significant digits: 3.93 cm, 300 mm, 1.42 m. */
    static String len(double mm) {
        final double a = Math.abs(mm);
        final String[] u = {"m", "cm", "mm", "um"};
        final double[] f = {1000, 10, 1, 1e-3};
        for (int i = 0; i < u.length; i++) {
            if (a >= f[i] * 0.9995 || i == u.length - 1) {
                final double v = mm / f[i];
                String t = Math.abs(v) >= 100 ? String.format(Locale.ROOT, "%.0f", v)
                    : Math.abs(v) >= 10 ? String.format(Locale.ROOT, "%.1f", v) : String.format(Locale.ROOT, "%.2f", v);
                if (t.contains(".")) {
                    t = t.replaceAll("0+$", "").replaceAll("\\.$", "");
                }
                return t + " " + u[i];
            }
        }
        return Units.best(mm, Units.Category.LENGTH);
    }

    private static Color grade(double kept) {
        return kept >= 0.95 ? OK : kept >= 0.85 ? WARN : LEAK;
    }

    /** The energy left per layer along the detector, active layers bright, with the share kept so far as a line. */
    private static void profile(Graphics2D g, BeamPhysics.Report r, int x, int y, int w, int h, boolean dark) {
        g.setColor(dark ? new Color(16, 19, 25, 200) : new Color(236, 239, 244));
        g.fillRect(x, y, w, h);
        final double s0 = r.start(), s1 = r.end();
        if (s1 <= s0) {
            return;
        }
        double peak = 0;
        for (int i = r.first(); i <= r.last(); i++) {
            final BeamPhysics.Layer l = r.layers().get(i);
            if (l.thickness() > 0) {
                peak = Math.max(peak, l.edep() / l.thickness());
            }
        }
        final Path2D kept = new Path2D.Double();
        kept.moveTo(x, y + h);
        double sum = 0;
        for (int i = r.first(); i <= r.last(); i++) {
            final BeamPhysics.Layer l = r.layers().get(i);
            final int a = x + (int) Math.round((l.from() - s0) / (s1 - s0) * w), b = x + (int) Math.round((l.to() - s0) / (s1 - s0) * w);
            final double t = peak > 0 && l.thickness() > 0 ? l.edep() / l.thickness() / peak : 0;
            final int bh = (int) Math.round(t * (h - 4));
            g.setColor(l.active() ? new Color(0x38C8F0) : dark ? new Color(0x8A93A3) : new Color(0x6A7384));
            g.fillRect(a, y + h - bh, Math.max(1, b - a), bh);
            sum += l.edep();
            kept.lineTo(b, y + h - Math.min(1, sum) * (h - 2));
        }
        g.setColor(new Color(255, 214, 90, 220));
        g.setStroke(new BasicStroke(1.3f));
        g.draw(kept);
        g.setStroke(new BasicStroke(1f));
    }

    private static List<String> wrap(String s, FontMetrics fm, int width, int maxLines) {
        final List<String> out = new ArrayList<>();
        final StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            if (fm.stringWidth(line + " " + word) > width && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                if (out.size() == maxLines) {
                    final String last = out.remove(maxLines - 1);
                    out.add(last + " …");
                    return out;
                }
            }
            line.append(line.length() > 0 ? " " : "").append(word);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The panel: the beam, the layers, goals
    // ------------------------------------------------------------------

    final class Panel extends JPanel {
        private static final long serialVersionUID = 1L;
        final JCheckBox live = new JCheckBox("On the drawing (L)");
        final JComboBox<String> particle = new JComboBox<>(PARTICLES);
        final JTextField energy = new JTextField("1 GeV", 7);
        final JLabel aimed = new JLabel(" ");
        final JEditorPane text = new JEditorPane("text/html", "");
        final DefaultTableModel rows = new DefaultTableModel(new Object[] {"#", "Volume", "Material", "Thickness", "X0",
            "λI", "mip dE/dx", "Energy left"}, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        final JComboBox<GoalSeek.Metric> metric = new JComboBox<>(GoalSeek.Metric.values());
        final JComboBox<String> op = new JComboBox<>(new String[] {"≥", "≤", "="});
        final JTextField target = new JTextField("95", 6);
        final JComboBox<String> variable = new JComboBox<>();
        final JButton seek = new JButton("Seek");
        final JButton apply = new JButton("Apply");
        JButton check;
        final JLabel result = new JLabel(" ");
        private GoalSeek.Result last;
        private boolean updating;

        Panel() {
            super(new BorderLayout(6, 4));
            setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            // the beam
            final JPanel beam = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            live.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 14));
            beam.add(live);
            beam.add(lab("Particle"));
            particle.setEditable(false);
            beam.add(particle);
            beam.add(lab("Energy"));
            beam.add(energy);
            beam.add(lab("  Aim"));
            for (String[] a : new String[][] {{"+Z", "Along +z, through the world's center"}, {"+X", "Along +x"},
                {"+Y", "Along +y"}, {"Selection", "Through the selected volumes, along the beam's direction"},
                {"View", "Along the line of sight, through the middle of the view"}}) {
                final JButton b = new JButton(a[0]);
                b.setToolTipText(a[1]);
                b.setMargin(new java.awt.Insets(1, 6, 1, 6));
                b.addActionListener(e -> aimBy(a[0]));
                beam.add(b);
            }
            beam.add(aimed);
            live.setToolTipText("The beam, the shower and its numbers on the drawing, worked out again at every edit");
            live.addActionListener(e -> setOn(live.isSelected()));
            particle.addActionListener(e -> beamEdited());
            energy.addActionListener(e -> beamEdited());
            add(beam, BorderLayout.NORTH);

            // what the beam meets: the numbers, the layers
            text.setEditable(false);
            text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            text.setFont(SphereCreo.UI);
            final JTable table = new JTable(rows);
            table.setAutoCreateRowSorter(false);
            table.setFillsViewportHeight(true);
            table.getColumnModel().getColumn(0).setMaxWidth(36);
            final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(text), new JScrollPane(table));
            split.setResizeWeight(0.45);
            split.setBorder(null);
            split.setMinimumSize(new java.awt.Dimension(0, 0));
            add(split, BorderLayout.CENTER);

            // goals
            final JPanel goal = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            goal.add(lab("Goal"));
            metric.setRenderer(new javax.swing.DefaultListCellRenderer() {
                private static final long serialVersionUID = 1L;

                @Override
                public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                       boolean sel, boolean focus) {
                    final GoalSeek.Metric m = (GoalSeek.Metric) value;
                    return super.getListCellRendererComponent(list, m == null ? "" : cap(m.label) + " (" + m.unit + ")",
                        index, sel, focus);
                }
            });
            metric.addActionListener(e -> target.setText(switch ((GoalSeek.Metric) metric.getSelectedItem()) {
                case CONTAINMENT, LONGITUDINAL, LATERAL -> "95";
                case DEPTH_X0 -> "25";
                case DEPTH_LAMBDA -> "7";
                case DEPTH -> "50 cm";
                case SAMPLING -> "10";
                case RESOLUTION -> "10";
                case MIP_SIGNAL -> "1 MeV";
                case MASS -> "100 kg";
            }));
            goal.add(metric);
            goal.add(op);
            goal.add(target);
            goal.add(lab("by changing"));
            variable.setPrototypeDisplayValue("(the strongest lever)xx");
            goal.add(variable);
            seek.setToolTipText("Searches the value on a copy of the design: nothing changes until Apply");
            apply.setToolTipText("Sets the variable to the value found, as one step (Ctrl+Z takes it back)");
            apply.setEnabled(false);
            seek.addActionListener(e -> seek());
            apply.addActionListener(e -> applyResult());
            goal.add(seek);
            goal.add(apply);
            final JButton copy = new JButton("Copy for the paper");
            copy.setToolTipText("A paragraph describing the detector and what the beam does in it, to the clipboard");
            copy.addActionListener(e -> {
                final BeamPhysics.Report r = report();
                if (r != null && r.hit()) {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(r.paragraph()), null);
                    result.setText("Copied: " + shorten(r.paragraph(), 90));
                }
            });
            goal.add(copy);
            check = new JButton("Check in Geant4");
            check.setToolTipText("Runs this beam in Geant4 (the Run panel's events and physics list); when it is done,"
                + " what Geant4 measured is set against what Physics Live predicts");
            check.addActionListener(e -> {
                result.setText("Geant4 is running the beam; the comparison comes when it is done (Run in Geant4 shows"
                    + " the progress).");
                checkInGeant4.run();
            });
            goal.add(check);
            final JPanel south = new JPanel(new BorderLayout());
            south.add(goal, BorderLayout.NORTH);
            result.setBorder(BorderFactory.createEmptyBorder(0, 8, 2, 8));
            south.add(result, BorderLayout.SOUTH);
            add(south, BorderLayout.SOUTH);
            setMinimumSize(new java.awt.Dimension(0, 0));
            variablesChanged();
            addHierarchyListener(e -> {
                if (isShowing()) {
                    refresh();
                    syncPanel();
                }
            });
        }

        /** A label with room to spare: some themes paint in a font wider than the one sizes were taken in. */
        private static JLabel lab(String text) {
            final JLabel l = new JLabel(text);
            l.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 10));
            return l;
        }

        private static String cap(String s) {
            return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        private static String shorten(String s, int n) {
            return s.length() <= n ? s : s.substring(0, n) + "…";
        }

        private void aimBy(String how) {
            switch (how) {
                case "+Z" -> aim(Vec3.Z, null);
                case "+X" -> aim(Vec3.X, null);
                case "+Y" -> aim(Vec3.Y, null);
                case "Selection" -> {
                    if (!aimAtSelection()) {
                        result.setText("Select the volumes the beam should go through first.");
                    }
                }
                default -> aimAlongView();
            }
        }

        /** The particle or the energy typed: the gun follows. */
        private void beamEdited() {
            if (updating) {
                return;
            }
            final Geometry g = model.geometry;
            final BeamPhysics.Beam b = BeamPhysics.Beam.of(g);
            final BeamPhysics.Particle p = BeamPhysics.Particle.of(String.valueOf(particle.getSelectedItem()));
            double e;
            try {
                e = Expr.eval(Inputs.normalize(energy.getText(), Units.Category.ENERGY, "mm"), g.scope());
            } catch (GeometryException ex) {
                result.setText("Energy: " + ex.getMessage());
                return;
            }
            if (p == null || e <= 0) {
                return;
            }
            if (p != b.particle() || Math.abs(e - b.energy()) > 1e-9 * e) {
                setBeam(b.with(p, e));
            }
        }

        void showBeam(BeamPhysics.Beam b) {
            updating = true;
            try {
                particle.setSelectedItem(b.particle().g4Name);
                if (!energy.isFocusOwner()) {
                    energy.setText(Units.best(b.energy(), Units.Category.ENERGY));
                }
                final Vec3 o = b.origin();
                aimed.setText("  from (" + Units.best(o.x(), Units.Category.LENGTH) + ", " + Units.best(o.y(),
                    Units.Category.LENGTH) + ", " + Units.best(o.z(), Units.Category.LENGTH) + ") "
                    + BeamPhysics.Beam.toward(b.direction()));
            } finally {
                updating = false;
            }
        }

        void show(BeamPhysics.Report r) {
            rows.setRowCount(0);
            if (r == null) {
                text.setText("<html><body>The design does not evaluate: see the console.</body></html>");
                return;
            }
            final StringBuilder b = new StringBuilder("<html><body style='font-family:sans-serif;font-size:10px'>");
            b.append(comparisonHtml());
            for (String l : r.lines()) {
                b.append(esc(l)).append("<br>");
            }
            if (r.hit()) {
                b.append("<br><i>").append(esc(r.paragraph())).append("</i>");
            }
            b.append(String.format(Locale.ROOT, "<br><span style='color:gray'>worked out in %.1f ms</span>", r.millis()));
            text.setText(b.append("</body></html>").toString());
            text.setCaretPosition(0);
            int n = 0;
            for (BeamPhysics.Layer l : r.layers()) {
                n++;
                if (!l.dense() && l.thickness() > 1e3 && l.x0() < 0.01) {
                    continue; // the air of the world before and after: a line would say nothing
                }
                rows.addRow(new Object[] {n, l.volume() + (l.active() ? "  ◆" : ""), l.material(),
                    Units.best(l.thickness(), Units.Category.LENGTH), String.format(Locale.ROOT, "%.3f", l.x0()),
                    String.format(Locale.ROOT, "%.4f", l.lambda()), String.format(Locale.ROOT, "%.3g MeV/mm", l.dedx()),
                    l.edep() > 0 ? String.format(Locale.ROOT, "%.2f %%", 100 * l.edep()) : ""});
            }
        }

        private static String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        /** The last Geant4 run against the prediction, as a table; a line when it is of another design or beam. */
        private String comparisonHtml() {
            if (run == null) {
                return "";
            }
            final RunComparison.Result c = comparison;
            if (c == null) {
                return "<p style='color:#e0a030'>The last Geant4 run was of another design or beam: <b>Check in Geant4</b>"
                    + " runs this one.</p>";
            }
            final StringBuilder b = new StringBuilder("<p><b>Geant4 against Physics Live</b>: ").append(c.events())
                .append(' ').append(esc(c.particle())).append(" at ").append(esc(Units.best(c.energy(), Units.Category.ENERGY)))
                .append(c.physics().isEmpty() ? "" : ", " + esc(c.physics())).append("</p><table cellspacing=0 cellpadding=2>")
                .append("<tr><th align=left>Quantity</th><th align=right>Geant4</th><th align=right>Physics Live</th>")
                .append("<th align=right>Difference</th></tr>");
            final List<RunComparison.Line> lines = new ArrayList<>();
            if (c.kept() != null) {
                lines.add(c.kept());
            }
            if (c.sampling() != null) {
                lines.add(c.sampling());
            }
            if (c.resolution() != null) {
                lines.add(c.resolution());
            }
            lines.addAll(c.volumes());
            for (RunComparison.Line l : lines) {
                final String color = l.agrees() ? "#52c46a" : "#f0b030";
                b.append("<tr><td>").append(esc(l.what())).append(l.unit().isEmpty() ? "" : " <span style='color:gray'>("
                    + esc(l.unit()) + ")</span>").append("</td><td align=right>").append(num(l.measured())).append(" ± ")
                    .append(num(l.error())).append("</td><td align=right>").append(num(l.predicted()))
                    .append("</td><td align=right style='color:").append(color).append("'>")
                    .append(Double.isNaN(l.relative()) ? "" : String.format(Locale.ROOT, "%+.1f %%", 100 * l.relative()))
                    .append("</td></tr>");
            }
            b.append("</table>");
            for (RunComparison.Profile p : c.profiles()) {
                b.append("<p>").append(esc(p.volume())).append(" along its copies (MeV per event), Geant4 / Physics Live:<br>");
                for (int i = 0; i < p.copies().length && i < 30; i++) {
                    b.append(i > 0 ? " &middot; " : "").append(p.copies()[i]).append(": ").append(num(p.measured()[i]))
                        .append(" / ").append(num(p.predicted()[i]));
                }
                b.append("</p>");
            }
            for (String n : c.notes()) {
                b.append("<p style='color:gray'>").append(esc(n)).append("</p>");
            }
            return b.append("<hr>").toString();
        }

        private static String num(double v) {
            final double a = Math.abs(v);
            return a == 0 ? "0" : a >= 100 ? String.format(Locale.ROOT, "%.0f", v) : a >= 10 ? String.format(Locale.ROOT, "%.1f", v)
                : a >= 1 ? String.format(Locale.ROOT, "%.2f", v) : String.format(Locale.ROOT, "%.3g", v);
        }

        /** The knobs a goal may turn (the design's variables, the code's constants), the strongest offered first. */
        void variablesChanged() {
            final Object sel = variable.getSelectedItem();
            final List<String> names = new ArrayList<>();
            names.add("(the strongest lever)");
            names.addAll(levers.names());
            final List<String> now = new ArrayList<>();
            for (int i = 0; i < variable.getItemCount(); i++) {
                now.add(variable.getItemAt(i));
            }
            if (!now.equals(names)) {
                variable.removeAllItems();
                for (String n : names) {
                    variable.addItem(n);
                }
                if (sel != null && names.contains(sel)) {
                    variable.setSelectedItem(sel);
                }
            }
        }

        private void seek() {
            final GoalSeek.Metric m = (GoalSeek.Metric) metric.getSelectedItem();
            final GoalSeek.Op o = switch (op.getSelectedIndex()) {
                case 1 -> GoalSeek.Op.AT_MOST;
                case 2 -> GoalSeek.Op.EQUAL;
                default -> GoalSeek.Op.AT_LEAST;
            };
            final double t;
            try {
                t = m.parse(target.getText(), model.geometry.scope());
            } catch (GeometryException ex) {
                result.setText("Target: " + ex.getMessage());
                return;
            }
            final GoalSeek.Goal goal = new GoalSeek.Goal(m, o, t);
            final String chosen = (String) variable.getSelectedItem();
            final boolean auto = chosen == null || chosen.startsWith("(");
            // the design (or the code) as it is now, taken on the event thread; the search runs on its own copy
            final BeamPhysics.Beam beam = BeamPhysics.Beam.of(model.geometry);
            final Search search = levers.prepare(beam);
            seek.setEnabled(false);
            apply.setEnabled(false);
            result.setText("Seeking " + goal.describe() + " ...");
            new SwingWorker<Object[], Void>() {
                @Override
                protected Object[] doInBackground() throws Exception {
                    String var = chosen;
                    String ranked = "";
                    if (auto) {
                        final List<GoalSeek.Lever> ls = search.rank(m);
                        if (ls.isEmpty()) {
                            throw new GeometryException("nothing moves the " + m.label + ": " + levers.empty());
                        }
                        var = ls.get(0).variable();
                        final StringBuilder b = new StringBuilder();
                        for (int i = 0; i < Math.min(4, ls.size()); i++) {
                            b.append(i > 0 ? "; " : "").append(ls.get(i).describe(m));
                        }
                        ranked = b.toString();
                    }
                    return new Object[] {search.solve(goal, var), ranked};
                }

                @Override
                protected void done() {
                    seek.setEnabled(true);
                    try {
                        final Object[] out = get();
                        last = (GoalSeek.Result) out[0];
                        final String ranked = (String) out[1];
                        result.setText("<html>" + esc(last.message()) + (ranked.isEmpty() ? "" : "<br><span style='color:gray'>"
                            + "Levers: " + esc(ranked) + "</span>") + "</html>");
                        apply.setEnabled(last.changes() && (last.reached() || last.already()));
                    } catch (Exception ex) {
                        final Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                        result.setText(c.getMessage() == null ? c.toString() : c.getMessage());
                    }
                }
            }.execute();
        }

        private void applyResult() {
            final GoalSeek.Result r = last;
            if (r == null || !r.changes()) {
                return;
            }
            final String said = levers.apply(r);
            apply.setEnabled(false);
            result.setText(said);
        }
    }

}

package com.sphere.components.g4editor;

import com.sphere.core.geant4.BBox;
import com.sphere.core.geant4.BooleanSolid;
import com.sphere.core.geant4.Clashes;
import com.sphere.core.geant4.Expr;
import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.GeometryException;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.Material;
import com.sphere.core.geant4.Mesh;
import com.sphere.core.geant4.PhysicalVolume;
import com.sphere.core.geant4.Placer;
import com.sphere.core.geant4.Solid;
import com.sphere.core.geant4.Units;
import com.sphere.core.geant4.Vec3;
import com.sphere.core.geant4.Xform;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The 3D view of G4editor.
 *
 * Navigation follows FreeCAD's: left drag orbits, right or middle drag pans,
 * the wheel zooms at the cursor, 0-6 pick the standard views, F fits. A click
 * selects the volume under the pixel (the rasterizer's id buffer), Ctrl adds
 * to the selection, a double click frames it. The drawing tools take clicks
 * on the work plane, snapped to the grid or to vertices, and typed values.
 */
final class Viewport extends JComponent {

    private static final long serialVersionUID = 1L;

    final EditorModel model;
    final Camera camera = new Camera();
    // Frames are drawn by a thread of their own into the raster of each size that is not the one shown:
    // the event thread only ever shows a finished frame, so the window answers while a heavy one is drawn
    private final Raster[] fullRasters = {new Raster(), new Raster()};
    private final Raster[] fastRasters = {new Raster(), new Raster()};
    /** A finished frame: its raster (pixels, ids, depths), the copies its ids index, its size over the view's. */
    private record Shown(Raster raster, List<Geometry.Instance> drawn, double scale, SceneKey key) {
    }

    private volatile Shown shown;
    /** The raster the event thread reads (paints, picks in), which the render thread then leaves alone. */
    private volatile Raster painting;
    /** The key of the frame last asked of the render thread (event thread only). */
    private SceneKey requestedKey;
    private final java.util.concurrent.atomic.AtomicReference<Scene> pendingScene = new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.locks.ReentrantLock renderLock = new java.util.concurrent.locks.ReentrantLock();
    /** One render thread for every view: an editor opened again does not leave one more behind. */
    private static final ExecutorService RENDERER = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "g4editor-render");
        t.setDaemon(true);
        return t;
    });
    /** Meshes finished aside (booleans), handed to the next frame, which owns the view's cache. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> arrivedMeshes = new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** snapshot() draws on the calling thread, and waits for it. */
    private boolean syncPaint;
    private SceneKey failedKey;
    /** A heavy scene in motion is drawn at this fraction of the size: a quarter of the pixels to fill. */
    private static final double MOVING_SCALE = 0.5;

    // What colours the volumes, and what is isolated
    private ColorScheme.Mode colorMode = ColorScheme.Mode.DOCUMENT;
    private ColorScheme scheme;
    private Object schemeFor;
    /** The placements isolated (they and what they hold are drawn, the rest is not, or as outlines), or null. */
    private java.util.Set<PhysicalVolume> isolated;
    /** Isolating keeps the rest as faint outlines, for where things are (X-ray), rather than hiding it. */
    private boolean xray = true;
    private Runnable viewOptionsListener = () -> { };
    /** An outline of the context is drawn from this size on screen: smaller, it would only be noise. */
    private static final double GHOST_PX = 12;
    WorkPlane plane = WorkPlane.xy(0);
    Tools.Tool tool;

    // View options
    boolean gridSnap = true, vertexSnap = true, showGrid = true, autoTransparent = true, faceSnapPlane = true;
    boolean section;
    int sectionAxis = 1;           // 0 x, 1 y, 2 z
    double sectionOffset;
    boolean darkTheme;
    /** Geant4's y-up convention (the default), or FreeCAD's z-up. */
    boolean yUp = true;

    void setUpAxis(boolean y) {
        yUp = y;
        camera.worldUp = y ? Vec3.Y : Vec3.Z;
        setView("iso", true);
    }

    // Frame bookkeeping
    private final Map<Solid, Mesh[]> meshCache = new IdentityHashMap<>();
    private final Map<Solid, Long> meshRevision = new IdentityHashMap<>();
    private Tools.Snap snap;
    private int mouseX = -1, mouseY = -1;
    private int pressX, pressY, pressButton;
    private boolean dragging;
    private Camera anim0, anim1;
    private long animStart;
    private final Timer animTimer;
    private String status = "";
    private Consumer<String> statusSink = s -> { };
    private Consumer<Geometry.Instance> hoverSink = i -> { };
    private volatile long lastFrameNanos;
    /** How long the last frame drawn at full quality took: a heavy scene gets lighter frames while it moves. */
    private volatile long lastFullFrameNanos;
    /** Bumped by whatever changes the frame without showing in its key: edits, selections, meshes arriving. */
    private long sceneVersion;
    /** The placement drawn highlighted: model.hover(), late by a moment when frames are slow. */
    private PhysicalVolume shownHover;
    private final Timer hoverTimer;
    /** Until when the view counts as moving (orbit, pan, zoom, animation); then a full frame follows. */
    private long movingUntil;
    private final Timer settleTimer;
    /** Smallest projected size, in pixels, of what a moving frame of a heavy scene draws; adapts to the frame time. */
    private volatile double movingMinPx = 3;
    private static final double MAX_MOVING_MIN_PX = 6;
    /** Volumes smaller than this on screen, across their whole box, are not drawn: a speck, at full cost. */
    private static final double MIN_PX = 2;
    private static final long SLOW_FRAME = 40_000_000L;
    /** Tracks to overlay: polylines in world coordinates, with a colour each. */
    final List<float[]> tracks = new ArrayList<>();
    final List<Integer> trackColors = new ArrayList<>();
    /** World points to flag, e.g. where overlaps were found. */
    final List<Vec3> markers = new ArrayList<>();

    // Gizmo drag state
    private int gizmoAxis = -1;
    private boolean gizmoRotate;
    private double gizmoGrab;
    private Vec3 gizmoOrigin;
    private final Map<PhysicalVolume, String[]> dragStart = new HashMap<>();

    Viewport(EditorModel model) {
        this.model = model;
        setFocusable(true);
        setFocusTraversalKeysEnabled(false); // Tab belongs to the dynamic input
        setPreferredSize(new java.awt.Dimension(900, 640));
        setView("iso", false);
        animTimer = new Timer(15, e -> stepAnimation());
        hoverTimer = new Timer(150, e -> {
            shownHover = model.hover();
            repaint();
        });
        hoverTimer.setRepeats(false);
        settleTimer = new Timer(300, e -> repaint());
        settleTimer.setRepeats(false);
        final MouseAdapter ma = new Mouse();
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
        addKeyListener(new Keys());
        model.addListener(new EditorModel.Listener() {
            public void selectionChanged() {
                sceneVersion++;
                repaint();
            }

            public void documentChanged(Geometry.Change c) {
                sceneVersion++;
                repaint();
            }
        });
    }

    /** The view is moving: frames are drawn lighter until it stops, then once more in full. */
    private void moving() {
        movingUntil = System.nanoTime() + 250_000_000L;
        settleTimer.restart();
    }

    private boolean isMoving() {
        return System.nanoTime() < movingUntil || animTimer.isRunning();
    }

    void setStatusSink(Consumer<String> s) {
        this.statusSink = s;
    }

    void setHoverSink(Consumer<Geometry.Instance> s) {
        this.hoverSink = s;
    }

    void setTool(Tools.Tool t) {
        if (tool != null) {
            tool.cancel();
        }
        tool = t;
        if (t != null) {
            t.activate();
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        } else {
            setCursor(Cursor.getDefaultCursor());
        }
        updateStatus();
        requestFocusInWindow();
        repaint();
    }

    void updateStatus() {
        if (tool != null) {
            status = tool.hint();
        } else {
            status = "Select: click a volume (Ctrl adds), drag to orbit, right-drag to pan, wheel to zoom, "
                + "double-click to frame. B box, T tube, S sphere, C cone, P extrude, V revolve, M move, R rotate";
        }
        statusSink.accept(status);
    }

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    void setView(String name, boolean animate) {
        final Camera target = camera.copy();
        target.worldUp = yUp ? Vec3.Y : Vec3.Z;
        if (yUp) {
            // Geant4's convention: y up, the beam along z, front seen from +z
            switch (name) {
                case "front" -> target.look(new Vec3(0, 0, -1), Vec3.Y);
                case "rear", "beam" -> target.look(new Vec3(0, 0, 1), Vec3.Y);
                case "top" -> target.look(new Vec3(0, -1, 0), new Vec3(0, 0, -1));
                case "bottom" -> target.look(new Vec3(0, 1, 0), Vec3.Z);
                case "right" -> target.look(new Vec3(-1, 0, 0), Vec3.Y);
                case "left", "side" -> target.look(new Vec3(1, 0, 0), Vec3.Y);
                default -> target.look(new Vec3(1.1, -0.85, -1.25).unit(), Vec3.Y);
            }
            animateTo(target, animate);
            return;
        }
        final Vec3 upZ = Vec3.Z;
        switch (name) {
            case "front" -> target.look(new Vec3(0, 1, 0), upZ);
            case "rear" -> target.look(new Vec3(0, -1, 0), upZ);
            case "top" -> target.look(new Vec3(0, 0, -1), Vec3.Y);
            case "bottom" -> target.look(new Vec3(0, 0, 1), Vec3.Y);
            case "right" -> target.look(new Vec3(-1, 0, 0), upZ);
            case "left" -> target.look(new Vec3(1, 0, 0), upZ);
            // A detector's longitudinal view: beam (z) to the right, y up
            case "side" -> target.look(new Vec3(1, 0, 0), Vec3.Y);
            // Along the beam: x right, y up
            case "beam" -> target.look(new Vec3(0, 0, -1), Vec3.Y);
            default -> target.look(new Vec3(-1, 1, -0.8).unit(), upZ);
        }
        animateTo(target, animate);
    }

    void fitAll(boolean animate) {
        BBox b = BBox.EMPTY;
        for (Geometry.Instance i : model.geometry.instances()) {
            if (i.lv() == model.geometry.world() && model.geometry.instances().size() > 1) {
                continue;
            }
            b = b.union(Geometry.worldBox(i));
        }
        if (b.isEmpty() && !model.geometry.instances().isEmpty()) {
            b = Geometry.worldBox(model.geometry.instances().get(0));
        }
        final Camera t = camera.copy();
        t.width = Math.max(1, getWidth());
        t.height = Math.max(1, getHeight());
        t.fit(b);
        animateTo(t, animate);
    }

    void fitSelection(boolean animate) {
        final BBox b = Tools.selectionBox(this);
        if (b.isEmpty()) {
            fitAll(animate);
            return;
        }
        final Camera t = camera.copy();
        t.width = Math.max(1, getWidth());
        t.height = Math.max(1, getHeight());
        t.fit(b);
        animateTo(t, animate);
    }

    private void animateTo(Camera target, boolean animate) {
        if (!animate || !isShowing()) {
            copyInto(target, camera);
            repaint();
            return;
        }
        anim0 = camera.copy();
        anim1 = target;
        animStart = System.nanoTime();
        animTimer.start();
    }

    private static void copyInto(Camera from, Camera to) {
        to.target = from.target;
        to.distance = from.distance;
        to.right = from.right;
        to.up = from.up;
        to.back = from.back;
        to.perspective = from.perspective;
        to.worldUp = from.worldUp;
    }

    private void stepAnimation() {
        final double t = Math.min(1, (System.nanoTime() - animStart) / 3.2e8);
        copyInto(Camera.blend(anim0, anim1, t), camera);
        if (t >= 1) {
            copyInto(anim1, camera);
            animTimer.stop();
        } else {
            moving();
        }
        repaint();
    }

    void togglePerspective() {
        camera.perspective = !camera.perspective;
        repaint();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** The view's meshes of a solid, one per level of detail, emptied when the solid changes. */
    private Mesh[] slots(Solid s) {
        Mesh[] arr = meshCache.get(s);
        final Long rev = meshRevision.get(s);
        if (arr == null || rev == null || rev != s.revision()) {
            arr = new Mesh[4];
            meshCache.put(s, arr);
            meshRevision.put(s, s.revision());
        }
        return arr;
    }

    private Mesh meshFor(Solid s, int q) {
        final Mesh[] arr = slots(s);
        if (arr[q] == null) {
            final Mesh ready = q == PROXY ? null : s.meshIfReady(q);
            if (ready != null) {
                // made already, by this solid or another of its shape (before an undo, say): neither a
                // preview nor the CSG again
                arr[q] = ready;
            } else if (q == PROXY) {
                // made from the coarsest tessellation: a boolean's may be its preview still, replaced (and
                // this one with it) when its own arrives
                arr[q] = meshFor(s, 0).simplified(PROXY_CELLS);
            } else if (s instanceof BooleanSolid) {
                // The solid keeps one tessellation; the view keeps one per level of detail
                arr[q] = preview(s, q);
                final Mesh[] slot = arr;
                final long revision = s.revision();
                MESHER.execute(() -> {
                    final Mesh m = s.mesh(q);
                    arrivedMeshes.add(() -> {
                        if (meshCache.get(s) == slot && s.revision() == revision) {
                            slot[q] = m;
                            if (q == 0) {
                                slot[PROXY] = null;
                            }
                        }
                    });
                    SwingUtilities.invokeLater(() -> {
                        sceneVersion++;
                        repaint();
                    });
                });
            } else {
                arr[q] = s.mesh(q);
            }
        }
        return arr[q];
    }

    /**
     * Forgets the meshes of solids the document no longer has. Opening a file, and every undo (which reads
     * the document back from GDML), makes new solids: the old ones' meshes stayed here for the session.
     */
    private void pruneMeshes(List<Solid> solids) {
        if (meshCache.size() <= 64) {
            return;
        }
        final java.util.Set<Solid> live = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        live.addAll(solids);
        meshCache.keySet().retainAll(live);
        meshRevision.keySet().retainAll(live);
    }

    /**
     * Tessellates, on every core, the meshes (and their feature edges) this frame will draw and the view
     * does not hold yet. A ROOT geometry opens with thousands of distinct solids: one after the other on the
     * event thread, the first frame took seconds. Booleans are left to meshFor, which builds them aside.
     */
    private void tessellateMissing(int n, double[] size, double scale) {
        final Map<Solid, Integer> missing = new IdentityHashMap<>();
        for (int i = 1; i < n; i++) {
            if (size[i] < 0) {
                continue;
            }
            final Solid s = volumeOf[i].solid();
            if (s instanceof BooleanSolid) {
                continue;
            }
            final int q = lod(size[i] * scale);
            if (slots(s)[q] == null) {
                missing.merge(s, 1 << q, (a, b) -> a | b);
            }
        }
        if (missing.size() < 16) {
            return; // not worth the hand-over
        }
        final List<Map.Entry<Solid, Integer>> work = new ArrayList<>(missing.entrySet());
        final Mesh[][] made = new Mesh[work.size()][];
        java.util.stream.IntStream.range(0, work.size()).parallel().forEach(k -> {
            final Solid s = work.get(k).getKey();
            final int mask = work.get(k).getValue();
            final Mesh[] out = new Mesh[4];
            for (int q = 0; q < 4; q++) {
                if ((mask & (1 << q)) != 0) {
                    out[q] = q == PROXY ? s.mesh(0).simplified(PROXY_CELLS) : s.mesh(q);
                    out[q].featureEdges();
                }
            }
            made[k] = out;
        });
        // the view's cache is the event thread's: filled here, after the parallel part
        for (int k = 0; k < work.size(); k++) {
            final Mesh[] arr = slots(work.get(k).getKey());
            for (int q = 0; q < 4; q++) {
                if (arr[q] == null && made[k][q] != null) {
                    arr[q] = made[k][q];
                }
            }
        }
    }

    // A boolean can take seconds to tessellate (a volume less dozens of the overlapping neighbours that
    // win over it, as ROOT geometries become): it is built off the paint thread, drawn meanwhile as the
    // solid its chain of first operands starts from
    private static final ExecutorService MESHER = Executors.newFixedThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors() - 1), r -> {
            final Thread t = new Thread(r, "g4editor-mesh");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);     // the threads it starts (Csg's) inherit it: the view stays responsive
            return t;
        });

    private static Mesh preview(Solid s, int q) {
        Xform t = Xform.IDENTITY;
        while (s instanceof BooleanSolid b) {
            t = t.compose(b.firstTransform());
            s = b.first();
        }
        return s.mesh(q).transformed(t);
    }

    /** The level of detail of volumes a few pixels wide: their coarsest mesh, simplified (see Mesh.simplified). */
    private static final int PROXY = 3;
    private static final int PROXY_CELLS = 4;
    /** Below this size on screen a volume is drawn as its proxy; below EDGE_PX, without its feature edges. */
    private static final double PROXY_PX = 10, EDGE_PX = 12;
    /**
     * Below this size on screen, in the frame's pixels, an opaque volume is drawn as a splat: in a full frame
     * only what a mesh would hardly shape anyway, in a moving one a little more. SPLAT_FILL: the splat's side
     * over the volume's projected box diagonal, about what a box covers.
     */
    private static final double SPLAT_PX = 3, SPLAT_PX_MOVING = 6, SPLAT_FILL = 0.6;

    /** Level of detail from the projected size in pixels: few triangles for far or tiny volumes. */
    private static int lod(double px) {
        return px < PROXY_PX ? PROXY : px < 40 ? 0 : px < 220 ? 1 : 2;
    }

    // Made once per instance list, in flat arrays a frame runs through without visiting 200 000 objects: the
    // world sphere of every drawn copy (centre, radius; -1 for a copy never drawn: the world, a broken solid),
    // its logical volume, its placement, and its mother copy's index
    private List<Geometry.Instance> spheresFor;
    private double[] spheres;
    private LogicalVolume[] volumeOf;
    private PhysicalVolume[] placementOf;
    private int[] motherOf;
    private int[] depthOf;

    private double[] spheres(List<Geometry.Instance> list) {
        if (spheresFor != list) {
            final int n = list.size();
            final double[] s = new double[n * 4];
            final LogicalVolume[] lvs = new LogicalVolume[n];
            final PhysicalVolume[] pvs = new PhysicalVolume[n];
            final int[] mothers = new int[n], depths = new int[n];
            for (int i = 0; i < n; i++) {
                final Geometry.Instance in = list.get(i);
                lvs[i] = in.lv();
                pvs[i] = in.pv();
                mothers[i] = in.parent() == null ? -1 : in.parent().id();
                depths[i] = in.depth();
                final BBox b = Geometry.worldBox(in);
                if (b.isEmpty() || in.pv() == null || in.lv().solid() == null || in.lv().solid().error() != null) {
                    s[i * 4 + 3] = -1;
                    continue;
                }
                final Vec3 c = b.center();
                s[i * 4] = c.x();
                s[i * 4 + 1] = c.y();
                s[i * 4 + 2] = c.z();
                s[i * 4 + 3] = b.diagonal() / 2;
            }
            spheres = s;
            volumeOf = lvs;
            placementOf = pvs;
            motherOf = mothers;
            depthOf = depths;
            spheresFor = list;
        }
        return spheres;
    }

    /** Everything a frame is drawn from: two equal keys draw the same pixels. */
    private record SceneKey(Vec3 target, double distance, Vec3 right, Vec3 up, Vec3 back, boolean perspective,
                            double fovY, int width, int height, Object instances, long version, int looks,
                            PhysicalVolume hover, boolean section, int sectionAxis, double sectionOffset, boolean dark,
                            boolean grid, boolean glass, WorkPlane plane, int tracks, Object lastTrack, double minPx,
                            double scale, Object scheme, Object isolated, boolean xray) {
    }

    private SceneKey sceneKey(double minPx, double scale) {
        // Colours and visibility can be set without a rebuild (a material's colour applied): they are read here
        int looks = 1;
        for (LogicalVolume lv : model.geometry.volumes()) {
            looks = looks * 31 + lv.color();
            looks = looks * 31 + (lv.visible() ? 1 : 0) + (lv.wireframe() ? 2 : 0);
        }
        return new SceneKey(camera.target, camera.distance, camera.right, camera.up, camera.back, camera.perspective,
            camera.fovY, getWidth(), getHeight(), model.geometry.instances(), sceneVersion, looks, shownHover, section,
            sectionAxis, sectionOffset, darkTheme, showGrid, autoTransparent, plane, tracks.size(),
            tracks.isEmpty() ? null : tracks.get(tracks.size() - 1), minPx, scale, scheme(), isolated, xray);
    }

    /** True while the view moves and its full frames are slow: frames are lighter until it stops. */
    private boolean lightFrames() {
        return isMoving() && lastFullFrameNanos > SLOW_FRAME && tool == null;
    }

    // ------------------------------------------------------------------
    // Colours and isolation
    // ------------------------------------------------------------------

    ColorScheme.Mode colorMode() {
        return colorMode;
    }

    void setColorMode(ColorScheme.Mode m) {
        colorMode = m == null ? ColorScheme.Mode.DOCUMENT : m;
        repaint();
        viewOptionsListener.run();
    }

    /** The scheme of the document as it is now, made again when the document or the mode changed. */
    private ColorScheme scheme() {
        final List<Geometry.Instance> list = model.geometry.instances();
        if (colorMode == ColorScheme.Mode.DOCUMENT) {
            scheme = null;
        } else if (scheme == null || scheme.mode != colorMode || schemeFor != list) {
            scheme = ColorScheme.of(colorMode, model.geometry);
            schemeFor = list;
        }
        return scheme;
    }

    boolean isIsolated() {
        return isolated != null;
    }

    /** Shows only the selection and what it holds (the rest as outlines, or not at all); false with nothing selected. */
    boolean isolate(boolean on) {
        if (on) {
            if (model.selection().isEmpty()) {
                return false;
            }
            final java.util.Set<PhysicalVolume> set = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            set.addAll(model.selection());
            isolated = set;
            fitSelection(true);
        } else {
            isolated = null;
        }
        repaint();
        viewOptionsListener.run();
        return true;
    }

    boolean xray() {
        return xray;
    }

    void setXray(boolean b) {
        xray = b;
        repaint();
        viewOptionsListener.run();
    }

    /** Told when the colour mode, the isolation or the X-ray option change, so menus can follow keys. */
    void setViewOptionsListener(Runnable r) {
        viewOptionsListener = r == null ? () -> { } : r;
    }

    // ------------------------------------------------------------------
    // Frames: taken on the event thread, drawn on the render thread
    // ------------------------------------------------------------------

    /**
     * Everything a frame is drawn from, taken on the event thread: the render thread reads nothing of the
     * view's own fields, which the event thread goes on changing while it draws.
     */
    private record Scene(SceneKey key, Camera cam, int w, int h, double minPx, double scale, boolean light,
                         List<Geometry.Instance> list, double radius, boolean dark, Vec3 cutN, double cutD,
                         java.util.Set<PhysicalVolume> sel, PhysicalVolume hover, Tools.Tool tool, boolean glass,
                         boolean grid, WorkPlane plane, double gridStep, List<float[]> tracks, List<Integer> trackColors,
                         ColorScheme scheme, java.util.Set<PhysicalVolume> isolated, boolean xray, List<Solid> solids) {
    }

    /** The instance list the mesh cache was last pruned for (event thread). */
    private Object prunedFor;

    private Scene scene(SceneKey key, boolean light, double minPx, double scale) {
        final Geometry g = model.geometry;
        double radius = 1;
        if (g.world() != null && g.world().solid() != null && g.world().solid().error() == null) {
            radius = g.world().solid().extent().diagonal();
        }
        Vec3 cutN = null;
        double cutD = 0;
        if (section) {
            // Cut away the half facing the camera, so the cut faces show
            final Vec3 n = Vec3.axis(sectionAxis);
            cutN = n.dot(camera.back) >= 0 ? n : n.mul(-1);
            cutD = n.dot(camera.back) >= 0 ? sectionOffset : -sectionOffset;
        }
        final List<Geometry.Instance> list = g.instances();
        java.util.Set<PhysicalVolume> sel = java.util.Set.of();
        if (!model.selection().isEmpty()) {
            sel = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            sel.addAll(model.selection());
        }
        List<Solid> solids = null;
        if (prunedFor != list) {
            prunedFor = list;
            solids = new ArrayList<>(g.solids());
        }
        return new Scene(key, camera.copy(), Math.max(1, getWidth()), Math.max(1, getHeight()), minPx, scale, light, list,
            radius, darkTheme, cutN, cutD, sel, shownHover, tool, autoTransparent, showGrid, plane, gridStep(),
            new ArrayList<>(tracks), new ArrayList<>(trackColors), scheme(), isolated, xray, solids);
    }

    /** Asks the render thread for a frame; one asked for before it began is dropped for this one. */
    private void request(Scene sc) {
        if (pendingScene.getAndSet(sc) == null) {
            RENDERER.execute(() -> {
                Scene next;
                while ((next = pendingScene.getAndSet(null)) != null) {
                    final Scene done = next;
                    try {
                        renderFrame(done);
                    } catch (RuntimeException e) {
                        if (STATS) {
                            e.printStackTrace(System.out);
                        }
                        // The document changed under the frame (an edit while it was drawn): draw it once more
                        SwingUtilities.invokeLater(() -> {
                            if (!done.key().equals(failedKey)) {
                                failedKey = done.key();
                                requestedKey = null;
                                repaint();
                            }
                        });
                        continue;
                    }
                    SwingUtilities.invokeLater(this::repaint);
                }
            });
        }
    }

    /** Draws a frame and makes it the one shown; on the render thread, or on the event thread for tools and snapshots. */
    private void renderFrame(Scene sc) {
        renderLock.lock();
        try {
            for (Runnable r; (r = arrivedMeshes.poll()) != null; ) {
                r.run();
            }
            if (sc.solids() != null) {
                pruneMeshes(sc.solids());
            }
            final Raster target = freeRaster(sc.scale() < 1 ? fastRasters : fullRasters);
            final long t0 = System.nanoTime();
            render(sc, target);
            final long dt = System.nanoTime() - t0;
            shown = new Shown(target, sc.list(), sc.scale(), sc.key());
            lastFrameNanos = dt;
            if (sc.light()) {
                // Aim for a moving frame under ~33 ms: leave out a little more of the smallest volumes when it
                // took longer, never so much that the detector thins out while it turns
                final double ms = dt / 1e6;
                movingMinPx = ms > 33 ? Math.min(MAX_MOVING_MIN_PX, movingMinPx * 1.25)
                    : ms < 15 ? Math.max(MIN_PX, movingMinPx / 1.25) : movingMinPx;
            } else {
                lastFullFrameNanos = dt;
            }
        } finally {
            renderLock.unlock();
        }
    }

    /**
     * A raster of the pool neither shown nor read by the event thread. That thread marks the raster it reads
     * and then checks it is still the one shown, taking the newer one if not (see fromShown): so a raster
     * returned here is never read while it is drawn into. When the pool's other raster is the frame just
     * replaced and still being painted, this waits the moment the paint takes.
     */
    private Raster freeRaster(Raster[] pool) {
        while (true) {
            final Shown a = shown;
            final Raster p = painting;
            for (Raster r : pool) {
                if ((a == null || a.raster() != r) && p != r) {
                    return r;
                }
            }
            java.util.concurrent.locks.LockSupport.parkNanos(200_000);
        }
    }

    /** Reads the frame shown on the event thread, the render thread kept off its raster meanwhile. */
    private <T> T fromShown(java.util.function.Function<Shown, T> read) {
        Shown f = shown;
        if (f == null) {
            return null;
        }
        painting = f.raster();
        while (f != shown) {
            // a newer frame came in between: the render thread may already draw into the older one
            f = shown;
            painting = f.raster();
        }
        try {
            return read.apply(f);
        } finally {
            painting = null;
        }
    }

    private void render(Scene sc, Raster raster) {
        final long tStart = System.nanoTime();
        final Camera cam = sc.cam();
        final int w = sc.w(), h = sc.h();
        final double scale = sc.scale();
        final int rw = Math.max(1, (int) Math.round(w * scale)), rh = Math.max(1, (int) Math.round(h * scale));
        if (sc.dark()) {
            raster.setColors(0xFF1D232C, 0xFF3A4352, 0xFF35D07F, 0xFFF6D860, 0xFF0D0F12);
        } else {
            raster.setColors(0xFF97A9C4, 0xFFF1F4F9, 0xFF1FAE5F, 0xFFE8B923, 0xFF1D2128);
        }
        raster.begin(cam, rw, rh, sc.radius());
        raster.setOrthoBias((float) (cam.pixelSize() / scale * 1.5));
        if (sc.cutN() != null) {
            raster.setSection(sc.cutN(), sc.cutD());
        }
        final List<Geometry.Instance> list = sc.list();
        final java.util.Set<PhysicalVolume> sel = sc.sel();
        final PhysicalVolume hov = sc.hover();
        final Tools.Tool tl = sc.tool();
        final ColorScheme cs = sc.scheme();

        final int n = list.size();
        final double[] sph = spheres(list);
        // A copy is selected (isolated) with its placement or any placement above it: mothers come first
        final boolean[] selected = new boolean[n];
        if (!sel.isEmpty()) {
            for (int i = 1; i < n; i++) {
                selected[i] = sel.contains(placementOf[i]) || selected[motherOf[i]];
            }
        }
        boolean[] focus = null;
        // The context outlined around what is isolated: the structure down to the isolated placements' own
        // level, not what other volumes hold, which would bury them in lines
        int ghostDepth = 0;
        if (sc.isolated() != null) {
            focus = new boolean[n];
            for (int i = 1; i < n; i++) {
                final boolean root = sc.isolated().contains(placementOf[i]);
                focus[i] = root || focus[motherOf[i]];
                if (root) {
                    ghostDepth = Math.max(ghostDepth, depthOf[i]);
                }
            }
        }
        final double[] size = new double[n];          // projected diameter in pixels; -1: not drawn
        final boolean[] childShown = new boolean[n];  // a daughter of this copy is drawn (at full quality)
        final boolean[] ghost = focus == null ? null : new boolean[n];
        cull(sc, n, sph, selected, focus, ghost, ghostDepth, size, childShown);
        tessellateMissing(n, size, scale);
        final long tQueue = System.nanoTime();

        // Pass 2, drawing, in the instance order (mothers before daughters)
        final double splatPx = sc.light() ? SPLAT_PX_MOVING : SPLAT_PX;
        if (n > 0) {
            final LogicalVolume lv = list.get(0).lv();
            if (lv.solid() != null && lv.solid().error() == null && lv.visible()) {
                // The world is drawn as a faint frame behind everything, as Geant4 keeps it invisible
                final int fc = sc.dark() ? 0x50C8D2E0 : 0x60506078;
                raster.backgroundLines(meshFor(lv.solid(), 1).featureEdges(), list.get(0).world(), fc, 1);
            }
        }
        for (int i = 1; i < n; i++) {
            if (size[i] < 0) {
                continue;
            }
            final Geometry.Instance in = list.get(i);
            final LogicalVolume lv = in.lv();
            int color = cs == null ? lv.color() : cs.color(lv, in.depth(), lv.color());
            if (ghost != null && ghost[i]) {
                // Isolated, seen through: the rest as faint outlines, there for where things are, not picked
                raster.mesh(meshFor(lv.solid(), lod(size[i] * scale)), in.world(), (0x40 << 24) | (color & 0xFFFFFF),
                    in.id(), Raster.FLAG_WIRE | Raster.FLAG_TRANSPARENT | Raster.FLAG_NO_PICK);
                continue;
            }
            final boolean hovered = in.pv() == hov && tl == null;
            int flags = 0;
            if (selected[i]) {
                flags |= Raster.FLAG_SELECTED;
            } else if (hovered) {
                flags |= Raster.FLAG_HOVER;
            } else if (size[i] * scale < EDGE_PX) {
                // a few pixels wide its edges would fill it: the silhouette pass outlines it instead
                flags |= Raster.FLAG_NO_EDGES;
            }
            if (size[i] * scale < splatPx && !selected[i] && !hovered && !lv.wireframe() && !childShown[i]
                && tl == null && (color >>> 24) >= 250) {
                // a volume a few pixels wide: a splat (see Raster.splat), not its mesh
                raster.splat(sph[i * 4], sph[i * 4 + 1], sph[i * 4 + 2], size[i] * scale * SPLAT_FILL, color, in.id(),
                    flags);
                continue;
            }
            if (lv.wireframe()) {
                flags |= Raster.FLAG_WIRE;
            } else if (sc.glass() && childShown[i] && !selected[i]) {
                // An envelope would hide what it holds: draw it as glass. When nothing it holds is
                // drawn (too small from here, or hidden) it hides nothing, and stays solid.
                color = (Math.min((color >>> 24) & 0xFF, 70) << 24) | (color & 0xFFFFFF);
                flags |= Raster.FLAG_TRANSPARENT;
                if (sc.light()) {
                    // filling glass is the dearest thing a frame does: while the view turns, its outline
                    flags |= Raster.FLAG_WIRE;
                }
            }
            if (tl != null && !(tl instanceof Tools.MoveTool) && !selected[i]) {
                // While drawing, existing volumes recede a little
                color = ((int) (((color >>> 24) & 0xFF) * 0.85) << 24) | (color & 0xFFFFFF);
            }
            raster.mesh(meshFor(lv.solid(), lod(size[i] * scale)), in.world(), color, in.id(), flags);
        }
        if (sc.grid()) {
            grid(sc, raster);
        }
        axes(sc, raster);
        for (int i = 0; i < sc.tracks().size(); i++) {
            raster.lines(sc.tracks().get(i), null, sc.trackColors().get(i), 1, true);
        }
        if (tl != null) {
            tl.preview(raster);
        }
        final long tEnd = System.nanoTime();
        raster.end();
        if (STATS) {
            System.out.printf(java.util.Locale.ROOT, "view: cull+tessellate %.1f ms, queue %.1f ms, raster %.1f ms%n",
                (tQueue - tStart) / 1e6, (tEnd - tQueue) / 1e6, (System.nanoTime() - tEnd) / 1e6);
        }
    }

    private static final boolean STATS = Boolean.getBoolean("g4editor.raster.stats");

    /**
     * Pass 1, culling: the projected diameter of each copy, -1 for one not drawn. A copy is drawn when it
     * shows on screen at least minPx wide (selected and hovered ones always), and is not wholly off screen
     * or on the cut-away side of the section. What is too small to see costs nothing: a detector of a
     * million cells seen whole draws its modules, and its cells once zoomed in. Isolated, the copies outside
     * are left out, or kept as outlines (ghost) from GHOST_PX on. Kept apart and on plain numbers, as it runs
     * over every copy on every frame.
     */
    private void cull(Scene sc, int n, double[] sph, boolean[] selected, boolean[] focus, boolean[] ghost, int ghostDepth,
                      double[] size, boolean[] childShown) {
        final Camera camera = sc.cam();
        final int w = sc.w(), h = sc.h();
        final double minPx = sc.minPx();
        final boolean persp = camera.perspective;
        final Vec3 o = persp ? camera.eye() : camera.target;
        final double ox = o.x(), oy = o.y(), oz = o.z();
        final double rx = camera.right.x(), ry = camera.right.y(), rz = camera.right.z();
        final double ux = camera.up.x(), uy = camera.up.y(), uz = camera.up.z();
        final double bx = camera.back.x(), by = camera.back.y(), bz = camera.back.z();
        final double halfH = camera.halfHeight(), near = camera.near(), aspect = (double) w / h;
        final double focal = 1 / Math.tan(camera.fovY / 2);
        final double pxPerUnit = h / (2 * halfH);
        final Vec3 cutN = sc.cutN();
        final double cutD = sc.cutD();
        final boolean cut = cutN != null;
        final double cx = cut ? cutN.x() : 0, cy = cut ? cutN.y() : 0, cz = cut ? cutN.z() : 0;
        final LogicalVolume[] lvs = volumeOf;
        final PhysicalVolume[] pvs = placementOf;
        final int[] mothers = motherOf;
        final PhysicalVolume hov = sc.hover();
        final boolean hoverable = hov != null && sc.tool() == null;
        final boolean xr = sc.xray();
        for (int i = 0; i < n; i++) {
            size[i] = -1;
            final double r = sph[i * 4 + 3];
            if (r < 0 || (!lvs[i].visible() && !selected[i])) {
                continue;
            }
            final boolean outside = focus != null && !focus[i];
            if (outside && (!xr || depthOf[i] > ghostDepth)) {
                continue;
            }
            final double px0 = sph[i * 4], py0 = sph[i * 4 + 1], pz0 = sph[i * 4 + 2];
            final double dx = px0 - ox, dy = py0 - oy, dz = pz0 - oz;
            final double vx = dx * rx + dy * ry + dz * rz;
            final double vy = dx * ux + dy * uy + dz * uz;
            final double vz = -(dx * bx + dy * by + dz * bz);
            final double sx, sy, px, reach;
            if (persp) {
                if (vz + r < near) {
                    continue; // behind the eye
                }
                if (vz - r < near) {
                    // around the eye: no projection to trust, drawn and clipped by the rasterizer
                    sx = w / 2.0;
                    sy = h / 2.0;
                    px = Double.MAX_VALUE;
                    reach = Double.MAX_VALUE;
                } else {
                    sx = (vx * focal / vz / aspect * 0.5 + 0.5) * w;
                    sy = (0.5 - vy * focal / vz * 0.5) * h;
                    px = r * focal / vz * h;
                    reach = r * focal / (vz - r) * h * 0.5;
                }
            } else {
                sx = (vx / halfH / aspect * 0.5 + 0.5) * w;
                sy = (0.5 - vy / halfH * 0.5) * h;
                px = 2 * r * pxPerUnit;
                reach = r * pxPerUnit;
            }
            if (outside) {
                if (px < GHOST_PX || sx + reach < 0 || sx - reach > w || sy + reach < 0 || sy - reach > h) {
                    continue;
                }
                ghost[i] = true;
                size[i] = px;
                continue;
            }
            if (px >= MIN_PX && mothers[i] >= 0) {
                childShown[mothers[i]] = true;
            }
            if (px < minPx && !selected[i] && !(hoverable && pvs[i] == hov)) {
                continue;
            }
            if (sx + reach < 0 || sx - reach > w || sy + reach < 0 || sy - reach > h) {
                continue;
            }
            if (cut && cx * px0 + cy * py0 + cz * pz0 - cutD > r) {
                continue;
            }
            size[i] = px;
        }
    }

    /** Grid spacing: a 1-2-5 step of at least 14 pixels. */
    double gridStep() {
        final double px = camera.pixelSize();
        final double raw = px * 14;
        final double p10 = Math.pow(10, Math.floor(Math.log10(raw)));
        for (double m : new double[] {1, 2, 5, 10}) {
            if (m * p10 >= raw) {
                return m * p10;
            }
        }
        return 10 * p10;
    }

    private static void grid(Scene sc, Raster raster) {
        final Camera camera = sc.cam();
        final WorkPlane plane = sc.plane();
        final double step = sc.gridStep();
        final int major = 5;
        // Centre the grid on the point of the plane under the view centre
        final Vec3[] ray = camera.ray(camera.width / 2.0, camera.height / 2.0);
        double[] c = plane.hit(ray[0], ray[1]);
        if (c == null) {
            c = plane.uv(camera.target);
        }
        final int n = 40;
        final double cu = Math.rint(c[0] / (step * major)) * step * major, cv = Math.rint(c[1] / (step * major)) * step * major;
        final float[] minor = new float[(2 * n + 1) * 2 * 6];
        final float[] majorL = new float[(2 * n + 1) * 2 * 6];
        int a = 0, b = 0;
        for (int i = -n; i <= n; i++) {
            final double u = cu + i * step, v = cv + i * step;
            final Vec3 p0 = plane.at(u, cv - n * step), p1 = plane.at(u, cv + n * step);
            final Vec3 q0 = plane.at(cu - n * step, v), q1 = plane.at(cu + n * step, v);
            final boolean isMajor = Math.abs(Math.IEEEremainder(i + Math.rint(cu / step), major)) < 1e-6;
            final float[] dst = isMajor ? majorL : minor;
            int k = isMajor ? b : a;
            for (Vec3 p : new Vec3[] {p0, p1, q0, q1}) {
                dst[k++] = (float) p.x();
                dst[k++] = (float) p.y();
                dst[k++] = (float) p.z();
            }
            if (isMajor) {
                b = k;
            } else {
                a = k;
            }
        }
        final int gc = sc.dark() ? 0xFFFFFFFF : 0xFF30405A;
        raster.backgroundLines(java.util.Arrays.copyOf(minor, a), null, (0x22 << 24) | (gc & 0xFFFFFF), 1);
        raster.backgroundLines(java.util.Arrays.copyOf(majorL, b), null, (0x48 << 24) | (gc & 0xFFFFFF), 1);
    }

    private static void axes(Scene sc, Raster raster) {
        final double len = Math.max(sc.cam().halfHeight() * 0.25, 1);
        raster.lines(new float[] {0, 0, 0, (float) len, 0, 0}, null, 0xC0D84040, 2, true);
        raster.lines(new float[] {0, 0, 0, 0, (float) len, 0}, null, 0xC040A848, 2, true);
        raster.lines(new float[] {0, 0, 0, 0, 0, (float) len}, null, 0xC04060D8, 2, true);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        final int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        camera.width = w;
        camera.height = h;
        final boolean light = lightFrames();
        final double minPx = light ? movingMinPx : MIN_PX, scale = light ? MOVING_SCALE : 1;
        final SceneKey key = sceneKey(minPx, scale);
        if (tool != null) {
            // A tool previews into the frame as the mouse moves, from the event thread's state: drawn here, at once
            renderFrame(scene(key, light, minPx, scale));
            requestedKey = null;
        } else if (syncPaint) {
            final Shown f = shown;
            if (f == null || !key.equals(f.key())) {
                renderFrame(scene(key, light, minPx, scale));
            }
            requestedKey = null;
        } else if (!key.equals(requestedKey)) {
            requestedKey = key;
            request(scene(key, light, minPx, scale));
        }
        final long tDraw = System.nanoTime();
        final Graphics2D g = (Graphics2D) g0.create();
        final Boolean drew = fromShown(f -> g.drawImage(f.scale() < 1 ? f.raster().scaledTo(w, h) : f.raster().image(), 0, 0, null));
        if (drew == null) {
            // nothing drawn yet: the background, until the first frame
            g.setColor(new Color(darkTheme ? 0x1D232C : 0x97A9C4));
            g.fillRect(0, 0, w, h);
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        final long tOverlay = System.nanoTime();
        overlay(g);
        g.dispose();
        if (STATS) {
            System.out.printf(java.util.Locale.ROOT, "paint: image %.1f ms, overlay %.1f ms%n", (tOverlay - tDraw) / 1e6,
                (System.nanoTime() - tOverlay) / 1e6);
        }
    }


    // ------------------------------------------------------------------
    // Overlays: navigation cube, triad, gizmo, snap marker, dynamic input
    // ------------------------------------------------------------------

    private static final Font LABEL = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private static final Font LABEL_B = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    private static final Font CUBE_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 9);
    private Polygon[] cubeFaces = new Polygon[0];
    private String[] cubeNames = new String[0];

    private void overlay(Graphics2D g) {
        navCube(g);
        triad(g);
        legend(g);
        if (isolated != null) {
            banner(g, "Isolated: " + isolated.size() + (isolated.size() > 1 ? " placements" : " placement")
                + (xray ? ", the rest as outlines" : "") + "   -   I shows everything again");
        }
        if (clashBanner != null) {
            banner(g, clashBanner, isolated != null ? 42 : 10, new Color(206, 58, 58, 232), Color.WHITE);
        }
        for (Clashes.Clash c : clashes) {
            clashMarker(g, c);
        }
        if (!markers.isEmpty()) {
            Tools.stroke(g, 2f);
            for (Vec3 m : markers) {
                final double[] s = screenOf(m);
                if (s == null) {
                    continue;
                }
                final int x = (int) s[0], y = (int) s[1];
                g.setColor(new Color(230, 40, 40, 220));
                g.drawOval(x - 7, y - 7, 14, 14);
                g.drawLine(x - 4, y - 4, x + 4, y + 4);
                g.drawLine(x - 4, y + 4, x + 4, y - 4);
            }
        }
        if (tool instanceof Tools.MoveTool mt && !model.selection().isEmpty()) {
            gizmo(g, mt.rotate);
        }
        if (tool != null) {
            tool.overlay(g);
        }
        if (snap != null && tool != null && tool.usesPlane() && mouseX >= 0) {
            snapMarker(g);
        }
        if (tool != null && !tool.input.isEmpty() && mouseX >= 0) {
            dynamicInput(g);
        }
        // Frame time and scale, bottom right
        g.setFont(LABEL);
        final String info = (camera.perspective ? "persp" : "ortho") + "   grid " + Units.best(gridStep(), Units.Category.LENGTH)
            + (section ? "   section " + "xyz".charAt(sectionAxis) + "=" + Units.best(sectionOffset, Units.Category.LENGTH) : "")
            + String.format("   %.0f ms", lastFrameNanos / 1e6);
        final FontMetrics fm = g.getFontMetrics();
        g.setColor(darkTheme ? new Color(220, 226, 236, 200) : new Color(30, 38, 52, 200));
        g.drawString(info, getWidth() - fm.stringWidth(info) - 10, getHeight() - 8);
    }

    /** The colour scheme's key, top left: a swatch per material (or detector), or the bar of a scale. */
    private void legend(Graphics2D g) {
        final ColorScheme cs = scheme;
        if (cs == null) {
            return;
        }
        g.setFont(LABEL);
        final FontMetrics fm = g.getFontMetrics();
        final int lh = fm.getHeight() + 2, x = 12, y = 12;
        final List<String> lines = new ArrayList<>();
        for (ColorScheme.Entry e : cs.legend) {
            lines.add(e.label() + (e.copies() > 0 ? "  (" + e.copies() + ")" : ""));
        }
        int wmax = fm.stringWidth(cs.title()) + 8;
        for (String l : lines) {
            wmax = Math.max(wmax, fm.stringWidth(l) + 22);
        }
        if (cs.continuous) {
            wmax = Math.max(wmax, 190);
        }
        final int hh = 10 + lh + (cs.continuous ? 34 : lh * lines.size()) + 4;
        g.setColor(darkTheme ? new Color(26, 30, 38, 215) : new Color(255, 255, 255, 220));
        g.fillRoundRect(x, y, wmax + 16, hh, 8, 8);
        g.setColor(darkTheme ? new Color(90, 100, 120) : new Color(160, 170, 188));
        g.drawRoundRect(x, y, wmax + 16, hh, 8, 8);
        final Color text = darkTheme ? new Color(225, 230, 238) : new Color(30, 38, 52);
        g.setFont(LABEL_B);
        g.setColor(text);
        g.drawString(cs.title(), x + 8, y + 6 + fm.getAscent());
        g.setFont(LABEL);
        int cy = y + 8 + lh;
        if (cs.continuous) {
            final int bw = wmax, bx = x + 8;
            for (int i = 0; i < bw; i++) {
                g.setColor(new Color(ColorScheme.viridis(i / (double) (bw - 1))));
                g.drawLine(bx + i, cy, bx + i, cy + 12);
            }
            g.setColor(text);
            g.drawString(cs.low, bx, cy + 14 + fm.getAscent());
            g.drawString(cs.high, bx + bw - fm.stringWidth(cs.high), cy + 14 + fm.getAscent());
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            g.setColor(new Color(cs.legend.get(i).rgb()));
            g.fillRect(x + 8, cy + 3, 12, 12);
            g.setColor(text);
            g.drawString(lines.get(i), x + 26, cy + fm.getAscent());
            cy += lh;
        }
    }

    /** The clashes the selection has now (see ClashWatch), and the line that sums them up; none when null. */
    private List<Clashes.Clash> clashes = List.of();
    private String clashBanner;

    void setClashes(List<Clashes.Clash> c, String banner) {
        clashes = c == null ? List.of() : c;
        clashBanner = banner;
        repaint();
    }

    /** Where a clash is deepest, ringed, with an arrow along the move that clears it. */
    private void clashMarker(Graphics2D g, Clashes.Clash c) {
        final Vec3 at = c.fault().worldPoint();
        final double[] s = at == null ? null : screenOf(at);
        if (s == null) {
            return;
        }
        final int x = (int) s[0], y = (int) s[1];
        g.setColor(new Color(206, 58, 58, 90));
        g.fillOval(x - 11, y - 11, 22, 22);
        Tools.stroke(g, 2f);
        g.setColor(new Color(206, 58, 58, 235));
        g.drawOval(x - 11, y - 11, 22, 22);
        g.fillOval(x - 3, y - 3, 6, 6);
        if (c.move() == null) {
            return;
        }
        final double[] e = screenOf(at.add(c.move()));
        if (e == null) {
            return;
        }
        double dx = e[0] - x, dy = e[1] - y;
        final double len = Math.hypot(dx, dy);
        if (len < 1e-6) {
            return; // along the line of sight
        }
        // a move of a hair or of metres shows as an arrow one can read
        final double k = Math.max(26, Math.min(90, len)) / len;
        dx *= k;
        dy *= k;
        final int ex = (int) (x + dx), ey = (int) (y + dy);
        g.setColor(new Color(40, 170, 90, 235));
        g.drawLine(x, y, ex, ey);
        final double a = Math.atan2(dy, dx);
        g.fillPolygon(new int[] {ex, (int) (ex - 9 * Math.cos(a - 0.45)), (int) (ex - 9 * Math.cos(a + 0.45))},
            new int[] {ey, (int) (ey - 9 * Math.sin(a - 0.45)), (int) (ey - 9 * Math.sin(a + 0.45))}, 3);
    }

    /** A line of state across the top of the view. */
    private void banner(Graphics2D g, String text) {
        banner(g, text, 10, new Color(240, 170, 40, 225), new Color(40, 30, 10));
    }

    private void banner(Graphics2D g, String text, int y, Color bg, Color fg) {
        g.setFont(LABEL_B);
        final FontMetrics fm = g.getFontMetrics();
        final int w = fm.stringWidth(text) + 20, x = (getWidth() - w) / 2;
        g.setColor(bg);
        g.fillRoundRect(x, y, w, fm.getHeight() + 8, 10, 10);
        g.setColor(fg);
        g.drawString(text, x + 10, y + 4 + fm.getAscent());
    }

    private void navCube(Graphics2D g) {
        final int cx = getWidth() - 62, cy = 62;
        final double s = 30;
        final Vec3[] corners = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = new Vec3((i & 1) == 0 ? -1 : 1, (i & 2) == 0 ? -1 : 1, (i & 4) == 0 ? -1 : 1);
        }
        final int[][] faces = {{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
        // Faces in order -z, +z, -y, +y, -x, +x
        final String[] names = yUp ? new String[] {"rear", "front", "bottom", "top", "left", "right"}
            : new String[] {"bottom", "top", "front", "rear", "left", "right"};
        final String[] labels = yUp ? new String[] {"REAR", "FRONT", "BOTTOM", "TOP", "LEFT", "RIGHT"}
            : new String[] {"BOTTOM", "TOP", "FRONT", "REAR", "LEFT", "RIGHT"};
        final Vec3[] normals = {Vec3.Z.mul(-1), Vec3.Z, Vec3.Y.mul(-1), Vec3.Y, Vec3.X.mul(-1), Vec3.X};
        final List<Integer> order = new ArrayList<>();
        for (int f = 0; f < 6; f++) {
            if (normals[f].dot(camera.back) > 0.02) {
                order.add(f);
            }
        }
        order.sort((a, b) -> Double.compare(normals[a].dot(camera.back), normals[b].dot(camera.back)));
        final List<Polygon> polys = new ArrayList<>();
        final List<String> pn = new ArrayList<>();
        for (int f : order) {
            final Polygon p = new Polygon();
            for (int k : faces[f]) {
                final Vec3 c = corners[k];
                p.addPoint((int) Math.round(cx + c.dot(camera.right) * s), (int) Math.round(cy - c.dot(camera.up) * s));
            }
            final double lit = 0.55 + 0.45 * normals[f].dot(camera.back);
            final boolean hot = mouseX >= 0 && p.contains(mouseX, mouseY);
            final Color base = hot ? new Color(90, 170, 255) : darkTheme ? new Color(70, 80, 96) : new Color(222, 228, 238);
            g.setColor(new Color((int) (base.getRed() * lit), (int) (base.getGreen() * lit), (int) (base.getBlue() * lit), 235));
            g.fillPolygon(p);
            g.setColor(darkTheme ? new Color(150, 160, 175) : new Color(90, 100, 118));
            g.drawPolygon(p);
            final java.awt.Rectangle r = p.getBounds();
            g.setFont(CUBE_FONT);
            final FontMetrics fm = g.getFontMetrics();
            if (normals[f].dot(camera.back) > 0.35) {
                g.setColor(darkTheme ? new Color(230, 234, 240) : new Color(40, 48, 62));
                g.drawString(labels[f], (int) r.getCenterX() - fm.stringWidth(labels[f]) / 2, (int) r.getCenterY() + 4);
            }
            polys.add(p);
            pn.add(names[f]);
        }
        cubeFaces = polys.toArray(new Polygon[0]);
        cubeNames = pn.toArray(new String[0]);
    }

    private void triad(Graphics2D g) {
        final int ox = 48, oy = getHeight() - 48;
        final double s = 32;
        final Vec3[] ax = {Vec3.X, Vec3.Y, Vec3.Z};
        final Color[] cs = {new Color(220, 60, 60), new Color(50, 170, 70), new Color(60, 100, 230)};
        final String[] ln = {"X", "Y", "Z"};
        final Integer[] order = {0, 1, 2};
        java.util.Arrays.sort(order, (a, b) -> Double.compare(ax[a].dot(camera.back), ax[b].dot(camera.back)));
        g.setFont(LABEL_B);
        for (int i : order) {
            final int x = (int) (ox + ax[i].dot(camera.right) * s), y = (int) (oy - ax[i].dot(camera.up) * s);
            g.setColor(cs[i]);
            Tools.stroke(g, 2.5f);
            g.drawLine(ox, oy, x, y);
            g.drawString(ln[i], x + (x >= ox ? 3 : -11), y + (y >= oy ? 12 : -2));
        }
    }

    private void snapMarker(Graphics2D g) {
        final double[] s = screenOf(snap.world());
        if (s == null) {
            return;
        }
        final int x = (int) s[0], y = (int) s[1];
        Tools.stroke(g, 1.6f);
        switch (snap.kind()) {
            case "vertex" -> {
                g.setColor(new Color(240, 120, 20));
                g.drawRect(x - 5, y - 5, 10, 10);
            }
            case "center" -> {
                g.setColor(new Color(200, 40, 200));
                g.drawOval(x - 6, y - 6, 12, 12);
                g.drawLine(x - 3, y, x + 3, y);
                g.drawLine(x, y - 3, x, y + 3);
            }
            case "face" -> {
                g.setColor(new Color(20, 140, 220));
                g.drawLine(x - 6, y - 6, x + 6, y + 6);
                g.drawLine(x - 6, y + 6, x + 6, y - 6);
            }
            default -> {
                g.setColor(darkTheme ? new Color(255, 255, 255, 200) : new Color(30, 40, 60, 200));
                g.drawLine(x - 7, y, x + 7, y);
                g.drawLine(x, y - 7, x, y + 7);
            }
        }
    }

    private void dynamicInput(Graphics2D g) {
        final DynamicInput in = tool.input;
        final List<String> lines = new ArrayList<>();
        for (int i = 0; i < in.fields.size(); i++) {
            lines.add(in.fields.get(i).label + "  " + in.display(i, model.lengthUnit));
        }
        final int x = mouseX + 18, y = mouseY + 22;
        g.setFont(LABEL);
        final FontMetrics fm = g.getFontMetrics();
        int wmax = 0;
        for (String l : lines) {
            wmax = Math.max(wmax, fm.stringWidth(l));
        }
        final int lh = fm.getHeight() + 3;
        final int hh = lh * lines.size() + 8 + (in.error != null ? lh : 0);
        g.setColor(darkTheme ? new Color(26, 30, 38, 230) : new Color(255, 255, 255, 235));
        g.fillRoundRect(x, y, wmax + 22, hh, 8, 8);
        g.setColor(darkTheme ? new Color(90, 100, 120) : new Color(150, 160, 180));
        g.drawRoundRect(x, y, wmax + 22, hh, 8, 8);
        for (int i = 0; i < lines.size(); i++) {
            final DynamicInput.Field f = in.fields.get(i);
            final int ly = y + 6 + lh * i;
            if (i == in.active) {
                g.setColor(darkTheme ? new Color(60, 110, 200, 160) : new Color(200, 220, 255));
                g.fillRoundRect(x + 4, ly, wmax + 14, lh - 1, 6, 6);
            }
            final boolean bad = !in.valid(i, model.geometry.scope(), model.lengthUnit);
            g.setColor(bad ? new Color(210, 50, 50) : f.typed != null ? (darkTheme ? new Color(140, 220, 160)
                : new Color(20, 120, 60)) : darkTheme ? new Color(225, 230, 238) : new Color(30, 38, 52));
            g.setFont(f.locked ? LABEL_B : LABEL);
            g.drawString(lines.get(i), x + 10, ly + fm.getAscent() + 1);
        }
        if (in.error != null) {
            g.setColor(new Color(210, 50, 50));
            g.drawString(in.error, x + 10, y + 6 + lh * lines.size() + fm.getAscent());
        }
    }

    /** A floating text box, for the tools' measurements. */
    void label(Graphics2D g, int x, int y, List<String> lines) {
        g.setFont(LABEL);
        final FontMetrics fm = g.getFontMetrics();
        int wmax = 0;
        for (String l : lines) {
            wmax = Math.max(wmax, fm.stringWidth(l));
        }
        final int lh = fm.getHeight();
        g.setColor(darkTheme ? new Color(26, 30, 38, 225) : new Color(255, 255, 255, 230));
        g.fillRoundRect(x, y - lh, wmax + 16, lh * lines.size() + 8, 8, 8);
        g.setColor(darkTheme ? new Color(225, 230, 238) : new Color(30, 38, 52));
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(lines.get(i), x + 8, y + lh * i);
        }
    }

    // --- gizmo ----------------------------------------------------------

    private Vec3 gizmoOrigin() {
        final PhysicalVolume pv = model.primary();
        if (pv == null) {
            return null;
        }
        final List<Geometry.Instance> ins = model.geometry.instancesOf(pv);
        return ins.isEmpty() ? null : ins.get(0).world().translation();
    }

    private double gizmoLength() {
        return camera.pixelSize() * 85;
    }

    private void gizmo(Graphics2D g, boolean rotate) {
        final Vec3 o = gizmoOrigin();
        if (o == null) {
            return;
        }
        final double[] so = screenOf(o);
        if (so == null) {
            return;
        }
        final Color[] cs = {new Color(225, 60, 60), new Color(40, 175, 70), new Color(55, 100, 235)};
        final double L = gizmoLength();
        for (int a = 0; a < 3; a++) {
            final boolean hot = a == gizmoAxis || (gizmoAxis < 0 && hitGizmo(mouseX, mouseY, rotate) == a);
            g.setColor(hot ? new Color(250, 200, 40) : cs[a]);
            Tools.stroke(g, hot ? 3.5f : 2.5f);
            if (rotate) {
                final Vec3 ax = Vec3.axis(a), u = ax.orthogonal(), v = ax.cross(u);
                int px = 0, py = 0;
                for (int k = 0; k <= 48; k++) {
                    final double t = k * Units.TWOPI / 48;
                    final double[] s = screenOf(o.add(u.mul(Math.cos(t) * L * 0.8)).add(v.mul(Math.sin(t) * L * 0.8)));
                    if (s == null) {
                        continue;
                    }
                    if (k > 0) {
                        g.drawLine(px, py, (int) s[0], (int) s[1]);
                    }
                    px = (int) s[0];
                    py = (int) s[1];
                }
            } else {
                final double[] se = screenOf(o.add(Vec3.axis(a).mul(L)));
                if (se == null) {
                    continue;
                }
                g.drawLine((int) so[0], (int) so[1], (int) se[0], (int) se[1]);
                // Arrow head
                final double dx = se[0] - so[0], dy = se[1] - so[1], l = Math.max(1, Math.hypot(dx, dy));
                final double ux = dx / l, uy = dy / l;
                final Polygon head = new Polygon();
                head.addPoint((int) (se[0] + ux * 10), (int) (se[1] + uy * 10));
                head.addPoint((int) (se[0] - uy * 5), (int) (se[1] + ux * 5));
                head.addPoint((int) (se[0] + uy * 5), (int) (se[1] - ux * 5));
                g.fillPolygon(head);
                g.setFont(LABEL_B);
                g.drawString("XYZ".substring(a, a + 1), (int) (se[0] + ux * 14) - 4, (int) (se[1] + uy * 14) + 4);
            }
        }
        g.setColor(new Color(250, 250, 250));
        g.fillOval((int) so[0] - 4, (int) so[1] - 4, 8, 8);
    }

    /** The gizmo handle under a pixel, or -1. */
    private int hitGizmo(int mx, int my, boolean rotate) {
        final Vec3 o = gizmoOrigin();
        if (o == null || mx < 0) {
            return -1;
        }
        final double L = gizmoLength();
        int best = -1;
        double bd = 8;
        for (int a = 0; a < 3; a++) {
            if (rotate) {
                final Vec3 ax = Vec3.axis(a), u = ax.orthogonal(), v = ax.cross(u);
                double[] prev = null;
                for (int k = 0; k <= 48; k++) {
                    final double t = k * Units.TWOPI / 48;
                    final double[] s = screenOf(o.add(u.mul(Math.cos(t) * L * 0.8)).add(v.mul(Math.sin(t) * L * 0.8)));
                    if (prev != null && s != null) {
                        final double d = segDist(mx, my, prev[0], prev[1], s[0], s[1]);
                        if (d < bd) {
                            bd = d;
                            best = a;
                        }
                    }
                    prev = s;
                }
            } else {
                final double[] so = screenOf(o), se = screenOf(o.add(Vec3.axis(a).mul(L)));
                if (so == null || se == null) {
                    continue;
                }
                final double d = segDist(mx, my, so[0], so[1], se[0], se[1]);
                if (d < bd) {
                    bd = d;
                    best = a;
                }
            }
        }
        return best;
    }

    private static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        final double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        double t = l2 > 0 ? ((px - ax) * dx + (py - ay) * dy) / l2 : 0;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - ax - dx * t, py - ay - dy * t);
    }

    /** Parameter along an axis line through o of the point closest to the mouse ray. */
    private double axisParam(Vec3 o, Vec3 axis, int mx, int my) {
        final Vec3[] r = camera.ray(mx, my);
        final Vec3 w0 = o.sub(r[0]);
        final double b = axis.dot(r[1]), d = axis.dot(w0), e = r[1].dot(w0);
        final double den = 1 - b * b;
        if (Math.abs(den) < 1e-9) {
            return 0;
        }
        return (b * e - d) / den;
    }

    /** Angle of the mouse about an axis through o, in the plane perpendicular to it. */
    private double axisAngle(Vec3 o, Vec3 axis, int mx, int my) {
        final Vec3[] r = camera.ray(mx, my);
        final double den = r[1].dot(axis);
        Vec3 p;
        if (Math.abs(den) < 1e-6) {
            p = r[0].add(r[1].mul(o.sub(r[0]).dot(r[1])));
        } else {
            p = r[0].add(r[1].mul(o.sub(r[0]).dot(axis) / den));
        }
        final Vec3 u = axis.orthogonal(), v = axis.cross(u), d = p.sub(o);
        return Math.atan2(d.dot(v), d.dot(u));
    }

    // ------------------------------------------------------------------
    // Snapping and geometry under the mouse
    // ------------------------------------------------------------------

    double[] screenOf(Vec3 p) {
        return camera.project(p);
    }

    Geometry.Instance instanceAt(int x, int y) {
        return fromShown(f -> {
            final int id = f.raster().idAt((int) (x * f.scale()), (int) (y * f.scale()));
            return id < 0 || id >= f.drawn().size() ? null : f.drawn().get(id);
        });
    }

    private Tools.Snap computeSnap(int mx, int my, boolean allowFacePlane) {
        final Vec3[] r = camera.ray(mx, my);
        final Geometry.Instance under = instanceAt(mx, my);
        // Vertex snap on the volume under the mouse
        if (vertexSnap && under != null) {
            final Mesh m = under.lv().solid().mesh(1); // the solid's own: the view's cache is the render thread's
            double best = 11;
            Vec3 bp = null;
            for (int i = 0; i < m.vertexCount(); i++) {
                final Vec3 p = under.world().apply(new Vec3(m.pos[i * 3], m.pos[i * 3 + 1], m.pos[i * 3 + 2]));
                final double[] s = screenOf(p);
                if (s == null) {
                    continue;
                }
                final double d = Math.hypot(s[0] - mx, s[1] - my);
                if (d < best) {
                    best = d;
                    bp = p;
                }
            }
            final Vec3 c = under.world().translation();
            final double[] sc = screenOf(c);
            if (sc != null && Math.hypot(sc[0] - mx, sc[1] - my) < Math.min(best, 11)) {
                final double[] uv = plane.uv(c);
                return new Tools.Snap(uv[0], uv[1], c, "center", null);
            }
            if (bp != null) {
                final double[] uv = plane.uv(bp);
                return new Tools.Snap(uv[0], uv[1], bp, "vertex", null);
            }
        }
        if (allowFacePlane && under != null) {
            final Vec3 p = fromShown(f -> f.raster().pointAt((int) (mx * f.scale()), (int) (my * f.scale())));
            if (p != null) {
                final Vec3 local = under.world().applyInverse(p);
                final Vec3 nl = under.lv().solid().normal(local.x(), local.y(), local.z());
                final Vec3 nw = under.world().applyDir(nl);
                return new Tools.Snap(0, 0, p, "face", nw);
            }
        }
        final double[] uv = plane.hit(r[0], r[1]);
        if (uv == null) {
            return null;
        }
        double u = uv[0], v = uv[1];
        String kind = "free";
        if (gridSnap) {
            final double s = gridStep();
            u = Math.rint(u / s) * s;
            v = Math.rint(v / s) * s;
            kind = "grid";
        }
        return new Tools.Snap(u, v, plane.at(u, v), kind, null);
    }

    /** The height the mouse pulls off the plane at a base point, snapped to the grid step. */
    double heightUnderMouse(Vec3 base) {
        final Vec3[] r = camera.ray(mouseX, mouseY);
        double h = plane.height(base, r[0], r[1]);
        if (gridSnap) {
            final double s = gridStep();
            h = Math.rint(h / s) * s;
        }
        return h;
    }

    // ------------------------------------------------------------------
    // Placement edits used by the gizmo and the move tool
    // ------------------------------------------------------------------

    /** Translates a placement by a world vector, keeping parametric expressions parametric. */
    void translateBy(PhysicalVolume pv, Vec3 worldDelta) {
        final Geometry.Instance mi = Placer.instanceOf(model.geometry, pv.mother(), null);
        final Vec3 d = mi == null ? worldDelta : mi.world().applyInverseDir(worldDelta);
        final String[] p = pv.positionExprs();
        pv.setPosition(shift(p[0], d.x()), shift(p[1], d.y()), shift(p[2], d.z()));
    }

    private String shift(String expr, double delta) {
        if (Math.abs(delta) < 1e-12) {
            return expr;
        }
        try {
            final Expr e = Expr.parse(expr);
            if (e.variables().isEmpty()) {
                return model.len(e.eval(model.geometry.scope()) + delta);
            }
        } catch (GeometryException ignored) {
            // fall through: keep the text and add to it
        }
        return expr + (delta >= 0 ? " + " : " - ") + model.len(Math.abs(delta));
    }

    /** Rotates a placement about world axes through its own origin. */
    void rotateBy(PhysicalVolume pv, double ax, double ay, double az) {
        final Geometry geo = model.geometry;
        final Geometry.Instance mi = Placer.instanceOf(geo, pv.mother(), null);
        final Xform motherW = mi == null ? Xform.IDENTITY : mi.world();
        final Xform worldNow = motherW.compose(pv.transform());
        final Xform rot = Xform.booleanPlacement(0, 0, 0, ax, ay, az); // active Rz Ry Rx
        final double[] r = mul(rot.rotation(), worldNow.rotation());
        final Vec3 t = worldNow.translation();
        Placer.setWorldTransform(pv, mi, new Xform(r, t.x(), t.y(), t.z()), model::len);
        // Keep position expressions as they were: only the rotation changed
    }

    private static double[] mul(double[] a, double[] b) {
        final double[] m = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                m[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    private final class Mouse extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            pressX = e.getX();
            pressY = e.getY();
            pressButton = e.getButton();
            dragging = false;
            if (SwingUtilities.isLeftMouseButton(e) && tool instanceof Tools.MoveTool mt) {
                final int a = hitGizmo(e.getX(), e.getY(), mt.rotate);
                if (a >= 0) {
                    gizmoAxis = a;
                    gizmoRotate = mt.rotate;
                    gizmoOrigin = gizmoOrigin();
                    gizmoGrab = mt.rotate ? axisAngle(gizmoOrigin, Vec3.axis(a), e.getX(), e.getY())
                        : axisParam(gizmoOrigin, Vec3.axis(a), e.getX(), e.getY());
                    dragStart.clear();
                    for (PhysicalVolume pv : model.selection()) {
                        final String[] p = pv.positionExprs(), r = pv.rotationExprs();
                        dragStart.put(pv, new String[] {p[0], p[1], p[2], r[0], r[1], r[2]});
                    }
                    model.checkpoint();
                }
            }
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            final int dx = e.getX() - mouseX, dy = e.getY() - mouseY;
            if (Math.abs(e.getX() - pressX) + Math.abs(e.getY() - pressY) > 3) {
                dragging = true;
            }
            if (gizmoAxis >= 0) {
                moving();
                dragGizmo(e);
                mouseX = e.getX();
                mouseY = e.getY();
                return;
            }
            if (mouseX >= 0 && dragging) {
                final boolean pan = SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)
                    || (SwingUtilities.isLeftMouseButton(e) && (e.isControlDown() || e.isShiftDown()) && tool == null);
                if (pan) {
                    camera.pan(dx, dy);
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    camera.orbit(-dx * 0.008, dy * 0.008);
                }
                moving();
                repaint();
            }
            mouseX = e.getX();
            mouseY = e.getY();
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            if (gizmoAxis >= 0) {
                gizmoAxis = -1;
                model.commit(gizmoRotate ? "Rotate" : "Move");
                for (PhysicalVolume pv : model.selection()) {
                    final String[] p = pv.positionExprs(), r = pv.rotationExprs();
                    model.log(gizmoRotate ? "set " + pv.name() + ".rotation = " + r[0] + " " + r[1] + " " + r[2]
                        : "set " + pv.name() + ".position = " + p[0] + " " + p[1] + " " + p[2]);
                }
                repaint();
                return;
            }
            if (!dragging && pressButton == MouseEvent.BUTTON1) {
                click(e);
            } else if (!dragging && pressButton == MouseEvent.BUTTON3 && tool != null) {
                setTool(null); // right click ends a tool, as in FreeCAD's Draft
            } else if (!dragging && pressButton == MouseEvent.BUTTON3) {
                contextMenu(e);
            }
            dragging = false;
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            mouseX = e.getX();
            mouseY = e.getY();
            final Geometry.Instance in = instanceAt(e.getX(), e.getY());
            final PhysicalVolume hv = in == null ? null : in.pv();
            if (hv != model.hover()) {
                model.hover(hv);
                hoverSink.accept(in);
                if (lastFrameNanos < SLOW_FRAME) {
                    shownHover = hv;
                } else {
                    // A slow scene highlights what the mouse rests on, not every volume it crosses
                    hoverTimer.restart();
                }
            }
            if (tool != null && tool.usesPlane()) {
                snap = computeSnap(e.getX(), e.getY(), false);
                if (snap != null) {
                    tool.moved(snap, e);
                }
            }
            repaint();
        }

        @Override
        public void mouseExited(MouseEvent e) {
            mouseX = -1;
            model.hover(null);
            shownHover = null;
            hoverTimer.stop();
            repaint();
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            final double f = Math.pow(1.15, e.getPreciseWheelRotation());
            camera.zoomAt(f, e.getX(), e.getY());
            moving();
            if (tool != null && tool.usesPlane()) {
                snap = computeSnap(e.getX(), e.getY(), false);
                if (snap != null) {
                    tool.moved(snap, null);
                }
            }
            repaint();
        }
    }

    private void dragGizmo(MouseEvent e) {
        final Vec3 axis = Vec3.axis(gizmoAxis);
        double delta;
        if (gizmoRotate) {
            delta = axisAngle(gizmoOrigin, axis, e.getX(), e.getY()) - gizmoGrab;
            if (!e.isShiftDown()) {
                delta = Math.rint(delta / Math.toRadians(15)) * Math.toRadians(15);
            }
        } else {
            delta = axisParam(gizmoOrigin, axis, e.getX(), e.getY()) - gizmoGrab;
            if (gridSnap && !e.isShiftDown()) {
                final double s = gridStep();
                delta = Math.rint(delta / s) * s;
            }
        }
        final double dd = delta;
        model.live(() -> {
            for (Map.Entry<PhysicalVolume, String[]> en : dragStart.entrySet()) {
                final String[] s = en.getValue();
                en.getKey().setPosition(s[0], s[1], s[2]);
                en.getKey().setRotation(s[3], s[4], s[5]);
            }
            model.geometry.rebuild();
            for (PhysicalVolume pv : dragStart.keySet()) {
                if (gizmoRotate) {
                    rotateBy(pv, gizmoAxis == 0 ? dd : 0, gizmoAxis == 1 ? dd : 0, gizmoAxis == 2 ? dd : 0);
                } else {
                    translateBy(pv, axis.mul(dd));
                }
            }
        });
        statusSink.accept((gizmoRotate ? "rotate " + Units.num(Math.toDegrees(dd)) + " deg about " : "move ")
            + (gizmoRotate ? "XYZ".charAt(gizmoAxis) : Units.best(dd, Units.Category.LENGTH) + " along " + "XYZ".charAt(gizmoAxis)));
    }

    private void click(MouseEvent e) {
        // Navigation cube faces
        for (int i = cubeFaces.length - 1; i >= 0; i--) {
            if (cubeFaces[i].contains(e.getX(), e.getY())) {
                setView(cubeNames[i], true);
                return;
            }
        }
        if (tool != null && !(tool instanceof Tools.MoveTool)) {
            // First click of a shape on a face: draw on that face
            if (tool.phase == 0 && faceSnapPlane && !(tool instanceof Tools.MeasureTool)) {
                final Tools.Snap f = computeSnap(e.getX(), e.getY(), true);
                if (f != null && "face".equals(f.kind()) && f.normal() != null && instanceAt(e.getX(), e.getY()) != null
                    && !(tool instanceof Tools.PolygonTool pt && !pt.pts.isEmpty())) {
                    plane = WorkPlane.facing(f.world(), f.normal());
                    tool.fields();
                    snap = computeSnap(e.getX(), e.getY(), false);
                }
            }
            if (snap == null) {
                snap = computeSnap(e.getX(), e.getY(), false);
            }
            if (snap != null) {
                tool.clicked(snap, e);
                updateStatus();
            }
            repaint();
            return;
        }
        final Geometry.Instance in = instanceAt(e.getX(), e.getY());
        if (e.getClickCount() >= 2 && in != null && in.pv() != null) {
            model.select(topSelectable(in), false);
            fitSelection(true);
            return;
        }
        if (in == null || in.pv() == null) {
            if (!e.isControlDown()) {
                model.clearSelection();
            }
            return;
        }
        model.select(topSelectable(in), e.isControlDown());
        final PhysicalVolume p = model.primary();
        if (p != null) {
            model.log("select " + p.name());
        }
    }

    /** Alt-click picks the volume itself; a plain click picks it too (the deepest under the pixel). */
    private PhysicalVolume topSelectable(Geometry.Instance in) {
        return in.pv();
    }

    private java.util.function.Predicate<KeyEvent> shortcuts = e -> false;

    /** Keys the view does not use itself (tool letters, Delete): handed to the frame. */
    void setShortcuts(java.util.function.Predicate<KeyEvent> s) {
        shortcuts = s;
    }

    /** Renders the current view into an image, for exports and tests. */
    java.awt.image.BufferedImage snapshot(int w, int h) {
        setSize(w, h);
        final java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = img.createGraphics();
        syncPaint = true;
        try {
            paintComponent(g);
        } finally {
            syncPaint = false;
        }
        g.dispose();
        return img;
    }

    private Consumer<MouseEvent> contextMenuHandler = e -> { };

    void setContextMenuHandler(Consumer<MouseEvent> h) {
        contextMenuHandler = h;
    }

    private void contextMenu(MouseEvent e) {
        final Geometry.Instance in = instanceAt(e.getX(), e.getY());
        if (in != null && in.pv() != null && !model.selection().contains(in.pv())) {
            model.select(in.pv(), false);
        }
        contextMenuHandler.accept(e);
    }

    private final class Keys extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent e) {
            if (tool != null && tool.key(e)) {
                updateStatus();
                e.consume();
                return;
            }
            if (e.isControlDown()) {
                return; // menu accelerators
            }
            switch (e.getKeyCode()) {
                case KeyEvent.VK_0, KeyEvent.VK_NUMPAD0 -> setView("iso", true);
                case KeyEvent.VK_1, KeyEvent.VK_NUMPAD1 -> setView("front", true);
                case KeyEvent.VK_2, KeyEvent.VK_NUMPAD2 -> setView("top", true);
                case KeyEvent.VK_3, KeyEvent.VK_NUMPAD3 -> setView("right", true);
                case KeyEvent.VK_4, KeyEvent.VK_NUMPAD4 -> setView("rear", true);
                case KeyEvent.VK_5, KeyEvent.VK_NUMPAD5 -> setView("bottom", true);
                case KeyEvent.VK_6, KeyEvent.VK_NUMPAD6 -> setView("left", true);
                case KeyEvent.VK_7, KeyEvent.VK_NUMPAD7 -> setView("side", true);
                case KeyEvent.VK_8, KeyEvent.VK_NUMPAD8 -> setView("beam", true);
                case KeyEvent.VK_F -> {
                    if (model.selection().isEmpty()) {
                        fitAll(true);
                    } else {
                        fitSelection(true);
                    }
                }
                case KeyEvent.VK_ESCAPE -> {
                    if (tool != null) {
                        setTool(null);
                    } else {
                        model.clearSelection();
                    }
                }
                case KeyEvent.VK_O -> togglePerspective();
                case KeyEvent.VK_G -> {
                    gridSnap = !gridSnap;
                    statusSink.accept("grid snapping " + (gridSnap ? "on" : "off"));
                }
                case KeyEvent.VK_X -> {
                    section = !section;
                    repaint();
                }
                case KeyEvent.VK_I -> {
                    if (isolated != null) {
                        isolate(false);
                    } else if (!isolate(true)) {
                        statusSink.accept("Select volumes first: I then shows only them, the rest as outlines");
                    }
                }
                case KeyEvent.VK_K -> {
                    // The next colouring: document, material, density, X0, depth, sensitive
                    final ColorScheme.Mode[] all = ColorScheme.Mode.values();
                    setColorMode(all[(colorMode.ordinal() + 1) % all.length]);
                    statusSink.accept("color by " + colorMode.label + "   (K for the next)");
                }
                default -> {
                    if (shortcuts.test(e)) {
                        e.consume();
                    }
                    return;
                }
            }
            e.consume();
            repaint();
        }

        @Override
        public void keyTyped(KeyEvent e) {
            if (tool != null && tool.key(e)) {
                e.consume();
            }
        }
    }

    // ------------------------------------------------------------------

    /** What the status bar says about the volume under the mouse. */
    static String describe(Geometry g, Geometry.Instance in) {
        if (in == null) {
            return "";
        }
        final LogicalVolume lv = in.lv();
        final Material m = g.materials().material(lv.material());
        final StringBuilder b = new StringBuilder(in.pathName()).append("   ").append(lv.solid() == null ? "" : lv.solid().g4Class())
            .append("   ").append(lv.material());
        if (m != null) {
            b.append("  (").append(Units.best(m.density(), Units.Category.DENSITY)).append(", X0 ")
             .append(Units.best(m.radiationLength(), Units.Category.LENGTH)).append(")");
        }
        if (lv.sensitive() != null) {
            b.append("   SD ").append(lv.sensitive());
        }
        return b.toString();
    }
}

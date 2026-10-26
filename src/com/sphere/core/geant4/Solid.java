package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/**
 * A Geant4 solid: its parameters as expressions, and what the editor and the
 * analyses need from it.
 *
 * Parameters are named after the GDML attributes of the solid (full lengths
 * where GDML has them, as G4GDMLWriteSolids writes them), so a solid reads and
 * writes GDML generically. Every query works in the solid's own frame and in
 * Geant4's internal units (mm, rad).
 *
 * The geometry queries rest on a signed distance: negative inside, zero on the
 * surface, positive outside. Its sign is exact, so inside() answers like
 * G4VSolid::Inside with Geant4's surface tolerance; its magnitude is exact or
 * a lower bound of the true distance (the {@link #safety()} factor says how
 * far it can be trusted), which is what a sphere-tracing navigator needs.
 */
public abstract class Solid {

    /** Half of Geant4's kCarTolerance (1e-9 mm): G4VSolid's surface band. */
    public static final double HALF_TOLERANCE = 0.5e-9;

    public static final int OUTSIDE = 0;
    public static final int SURFACE = 1;
    public static final int INSIDE = 2;

    public enum Kind {
        LENGTH(Units.Category.LENGTH),
        ANGLE(Units.Category.ANGLE),
        NUMBER(Units.Category.NONE),
        COUNT(Units.Category.NONE);

        public final Units.Category category;

        Kind(Units.Category c) {
            this.category = c;
        }
    }

    /** One parameter: an expression and the value it last evaluated to. */
    public static final class Param {
        public final String key;
        public final String label;
        public final Kind kind;
        String expr;
        double value;
        String error;

        Param(String key, String label, Kind kind, String expr) {
            this.key = key;
            this.label = label;
            this.kind = kind;
            this.expr = expr;
        }

        public String expr() {
            return expr;
        }

        public double value() {
            return value;
        }

        public String error() {
            return error;
        }
    }

    /** A list of rows, for the solids GDML describes with child elements (zplanes, vertices). */
    public static final class Table {
        public final String element;
        public final String[] columns;
        public final Kind[] kinds;
        final List<String[]> rows = new ArrayList<>();
        /** Replaced whole by each evaluation, never emptied and refilled: the view draws from another thread. */
        volatile List<double[]> values = new ArrayList<>();

        Table(String element, String[] columns, Kind[] kinds) {
            this.element = element;
            this.columns = columns;
            this.kinds = kinds;
        }

        public List<String[]> rows() {
            return Collections.unmodifiableList(rows);
        }

        public int size() {
            return rows.size();
        }

        public double value(int row, int col) {
            return values.get(row)[col];
        }

        public void clear() {
            rows.clear();
            values = new ArrayList<>();
        }

        public void add(String... cells) {
            rows.add(cells.clone());
        }

        public void set(int row, int col, String expr) {
            rows.get(row)[col] = expr;
        }

        public void remove(int row) {
            rows.remove(row);
        }

        public void insert(int row, String... cells) {
            rows.add(row, cells.clone());
        }
    }

    protected String name;
    private final Map<String, Param> params = new LinkedHashMap<>();
    private final Map<String, Table> tables = new LinkedHashMap<>();
    /** One tessellation per quality 0..3: the view draws near and far copies of a solid at different ones. */
    private volatile Mesh[] meshes = new Mesh[4];
    private double cachedVolume = Double.NaN;
    private double cachedArea = Double.NaN;
    private String error;
    private long revision;
    /**
     * What the last good evaluation computed (every value, the operands' revisions) and the operands it
     * saw. Every edit re-evaluates the whole document; a solid whose inputs came out the same keeps its
     * mesh, which for a boolean of a ROOT geometry is seconds of CSG.
     */
    private double[] evaluatedInputs;
    private Solid[] evaluatedOperands;
    /** The shape the last good evaluation describes, by which meshes are shared (see Shape); null if invalid. */
    private volatile Shape shape;

    /**
     * What a solid's meshes are made from: its class, its evaluated values and its operands' shapes, not its
     * name nor the object. Equal shapes tessellate alike, so they share their meshes (MeshCache): a ROOT
     * geometry's thousand crystals of one size, or the solids an undo reads back from GDML, all new objects.
     */
    static final class Shape {
        private final String type;
        private final double[] values;
        private final Shape[] operands;
        private final int hash;

        private Shape(String type, double[] values, Shape[] operands) {
            this.type = type;
            this.values = values;
            this.operands = operands;
            this.hash = (type.hashCode() * 31 + Arrays.hashCode(values)) * 31 + Arrays.hashCode(operands);
        }

        /** The shape of a solid just evaluated from these inputs (see inputs()), or null if an operand has none. */
        static Shape of(Solid s, double[] inputs, Solid[] ops) {
            final Shape[] os = new Shape[ops.length];
            for (int i = 0; i < ops.length; i++) {
                os[i] = ops[i].shape;
                if (os[i] == null) {
                    return null;
                }
            }
            // the inputs end with the operands' revisions, which name objects: their shapes stand for them
            return new Shape(s.getClass().getName() + " " + s.g4Class(), Arrays.copyOf(inputs, inputs.length - ops.length), os);
        }

        @Override
        public boolean equals(Object o) {
            return o == this || o instanceof Shape s && hash == s.hash && type.equals(s.type)
                && Arrays.equals(values, s.values) && Arrays.equals(operands, s.operands);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    protected Solid(String name) {
        this.name = name;
    }

    // ------------------------------------------------------------------
    // Identity and parameters
    // ------------------------------------------------------------------

    /** The GDML element, e.g. "box". */
    public abstract String tag();

    /** The Geant4 class, e.g. "G4Box". */
    public abstract String g4Class();

    /** A short human description of the shape. */
    public String description() {
        return g4Class();
    }

    public String name() {
        return name;
    }

    public void rename(String n) {
        this.name = n;
    }

    protected final Param param(String key, String label, Kind kind, String expr) {
        final Param p = new Param(key, label, kind, expr);
        params.put(key, p);
        return p;
    }

    protected final Table table(String element, String[] columns, Kind[] kinds) {
        final Table t = new Table(element, columns, kinds);
        tables.put(element, t);
        return t;
    }

    public final List<Param> params() {
        return new ArrayList<>(params.values());
    }

    public final Param param(String key) {
        return params.get(key);
    }

    public final List<Table> tables() {
        return new ArrayList<>(tables.values());
    }

    public final Table table(String element) {
        return tables.get(element);
    }

    /** Sets a parameter's expression; call evaluate() afterwards. */
    public final void set(String key, String expr) {
        final Param p = params.get(key);
        if (p == null) {
            throw new IllegalArgumentException(g4Class() + " has no parameter " + key);
        }
        p.expr = expr;
    }

    public final double v(String key) {
        return params.get(key).value;
    }

    /** The solids this one is made of (booleans), empty for a primitive. */
    public List<Solid> operands() {
        return List.of();
    }

    /** Replaces an operand, when a boolean references a solid by object. */
    public void replaceOperand(Solid oldOne, Solid newOne) {
    }

    public String error() {
        return error;
    }

    public long revision() {
        return revision;
    }

    /**
     * Evaluates every expression against the scope, checks the result the way
     * the Geant4 constructor would, and drops the cached mesh and volume.
     */
    public final void evaluate(Expr.Scope scope) throws GeometryException {
        final boolean wasValid = error == null && evaluatedInputs != null;
        error = null;
        boolean changed = true;
        Shape made = null;
        try {
            for (Param p : params.values()) {
                p.error = null;
                try {
                    p.value = Expr.eval(p.expr, scope);
                } catch (GeometryException e) {
                    p.error = e.getMessage();
                    throw new GeometryException(name + "." + p.key + ": " + e.getMessage());
                }
            }
            for (Table t : tables.values()) {
                final List<double[]> fresh = new ArrayList<>(t.rows.size());
                for (int r = 0; r < t.rows.size(); r++) {
                    final String[] row = t.rows.get(r);
                    final double[] vals = new double[row.length];
                    for (int c = 0; c < row.length; c++) {
                        try {
                            vals[c] = Expr.eval(row[c], scope);
                        } catch (GeometryException e) {
                            throw new GeometryException(name + "." + t.element + "[" + r + "]."
                                + t.columns[c] + ": " + e.getMessage());
                        }
                    }
                    fresh.add(vals);
                }
                t.values = fresh;
            }
            for (Solid s : operands()) {
                if (s.error != null) {
                    throw new GeometryException(name + ": operand " + s.name + " is invalid: " + s.error);
                }
            }
            final Solid[] ops = operands().toArray(new Solid[0]);
            final double[] inputs = inputs(ops);
            if (wasValid && Arrays.equals(inputs, evaluatedInputs) && sameOperands(ops)) {
                changed = false;
                return;
            }
            evaluatedInputs = null;
            validate();
            prepare();
            evaluatedInputs = inputs;
            evaluatedOperands = ops;
            made = Shape.of(this, inputs, ops);
        } catch (GeometryException e) {
            error = e.getMessage();
            evaluatedInputs = null;
            throw e;
        } finally {
            if (changed) {
                synchronized (this) {
                    meshes = new Mesh[4];
                    shape = made;
                    cachedVolume = Double.NaN;
                    cachedArea = Double.NaN;
                    cachedBox = null;
                    revision++;
                }
            }
        }
    }

    /** Every evaluated value, then each operand's revision: all a solid's shape depends on. */
    private double[] inputs(Solid[] ops) {
        int n = params.size() + ops.length;
        for (Table t : tables.values()) {
            n++;
            for (double[] row : t.values) {
                n += row.length + 1;
            }
        }
        final double[] in = new double[n];
        int k = 0;
        for (Param p : params.values()) {
            in[k++] = p.value;
        }
        for (Table t : tables.values()) {
            in[k++] = t.values.size();
            for (double[] row : t.values) {
                in[k++] = row.length;
                for (double v : row) {
                    in[k++] = v;
                }
            }
        }
        for (Solid o : ops) {
            in[k++] = o.revision;
        }
        return in;
    }

    private boolean sameOperands(Solid[] ops) {
        if (evaluatedOperands == null || evaluatedOperands.length != ops.length) {
            return false;
        }
        for (int i = 0; i < ops.length; i++) {
            if (evaluatedOperands[i] != ops[i]) {
                return false;
            }
        }
        return true;
    }

    /** Checks the evaluated values; throws what Geant4's constructor would refuse. */
    protected abstract void validate() throws GeometryException;

    /** Computes caches (planes, polygons) from the evaluated values. */
    protected void prepare() throws GeometryException {
    }

    protected static void require(boolean ok, String message) throws GeometryException {
        if (!ok) {
            throw new GeometryException(message);
        }
    }

    // ------------------------------------------------------------------
    // Geometry queries
    // ------------------------------------------------------------------

    /** Signed distance in the solid's frame: exact sign, lower-bound magnitude. */
    public abstract double sdf(double x, double y, double z);

    /** How much of sdf() a sphere tracer may step: 1 for exact or conservative distances. */
    public double safety() {
        return 1.0;
    }

    /** G4VSolid::Inside: OUTSIDE, SURFACE or INSIDE, with Geant4's surface tolerance. */
    public int inside(double x, double y, double z) {
        final double d = sdf(x, y, z);
        if (d > HALF_TOLERANCE) {
            return OUTSIDE;
        }
        return d < -HALF_TOLERANCE ? INSIDE : SURFACE;
    }

    public final int inside(Vec3 p) {
        return inside(p.x(), p.y(), p.z());
    }

    /** G4VSolid::BoundingLimits. */
    public abstract BBox extent();

    private volatile BBox cachedBox;

    /**
     * extent(), kept until the solid changes. Asked for every drawn copy (a view framing 200 000 of them), and
     * a boolean's is its whole tree of operands: a ROOT geometry's composite shapes made that seconds.
     */
    public final BBox box() {
        BBox b = cachedBox;
        if (b == null) {
            b = extent();
            cachedBox = b;
        }
        return b;
    }

    /** The outward normal at a surface point, from the distance's gradient. */
    public Vec3 normal(double x, double y, double z) {
        final double h = Math.max(1e-6, extent().diagonal() * 1e-7);
        final double gx = sdf(x + h, y, z) - sdf(x - h, y, z);
        final double gy = sdf(x, y + h, z) - sdf(x, y - h, z);
        final double gz = sdf(x, y, z + h) - sdf(x, y, z - h);
        return new Vec3(gx, gy, gz).unit();
    }

    /**
     * The distance along a unit direction to the first surface crossing, or
     * +infinity: DistanceToIn from outside, DistanceToOut from inside. Sphere
     * tracing on the signed distance, refined by bisection on the sign.
     */
    public double distanceAlong(double ox, double oy, double oz, double dx, double dy, double dz,
                                double maxLength) {
        final double s0 = sdf(ox, oy, oz);
        final boolean startInside = s0 < 0;
        final double minStep = Math.max(1e-7, extent().diagonal() * 1e-9);
        final double k = safety();
        double t = 0;
        double prev = 0;
        for (int i = 0; i < 4096 && t <= maxLength; i++) {
            final double d = sdf(ox + dx * t, oy + dy * t, oz + dz * t);
            final boolean in = d < 0;
            if (in != startInside && i > 0) {
                // Crossed between prev and t: bisect on the sign.
                double a = prev, b = t;
                for (int j = 0; j < 60 && b - a > 1e-10; j++) {
                    final double m = 0.5 * (a + b);
                    final boolean mi = sdf(ox + dx * m, oy + dy * m, oz + dz * m) < 0;
                    if (mi == startInside) {
                        a = m;
                    } else {
                        b = m;
                    }
                }
                return b;
            }
            final double step = Math.max(Math.abs(d) * k, minStep);
            prev = t;
            t += step;
        }
        return Double.POSITIVE_INFINITY;
    }

    // ------------------------------------------------------------------
    // Measures
    // ------------------------------------------------------------------

    /** G4VSolid::GetCubicVolume: analytic where Geant4 has it, estimated otherwise. */
    public double cubicVolume() {
        double v = cachedVolume;
        if (Double.isNaN(v)) {
            v = computeVolume();
            cachedVolume = v;
        }
        return v;
    }

    protected double computeVolume() {
        return estimateVolume(400_000, 0x5EED);
    }

    /** G4VSolid::EstimateCubicVolume: Monte Carlo over the bounding box, in parallel. */
    public final double estimateVolume(int points, long seed) {
        final BBox b = extent();
        if (b.isEmpty() || b.volume() == 0) {
            return 0;
        }
        final int chunks = 16;
        final int per = Math.max(1, points / chunks);
        final long inside = IntStream.range(0, chunks).parallel().mapToLong(c -> {
            final SplittableRandom rng = new SplittableRandom(seed + c * 7919L);
            long n = 0;
            for (int i = 0; i < per; i++) {
                final double x = b.minX() + rng.nextDouble() * (b.maxX() - b.minX());
                final double y = b.minY() + rng.nextDouble() * (b.maxY() - b.minY());
                final double z = b.minZ() + rng.nextDouble() * (b.maxZ() - b.minZ());
                if (sdf(x, y, z) <= 0) {
                    n++;
                }
            }
            return n;
        }).sum();
        return b.volume() * inside / ((double) per * chunks);
    }

    /** G4VSolid::GetSurfaceArea: analytic where it is simple, from a fine mesh otherwise. */
    public double surfaceArea() {
        double a = cachedArea;
        if (Double.isNaN(a)) {
            a = computeArea();
            cachedArea = a;
        }
        return a;
    }

    protected double computeArea() {
        return buildMesh(3).area();
    }

    /**
     * How thick the solid is where it is thinnest, for a plate or a shell:
     * twice its volume over its surface (exact for a thin slab or a thin
     * tube), never more than its smallest extent.
     */
    public double thickness() {
        final Vec3 s = extent().size();
        final double box = Math.min(s.x(), Math.min(s.y(), s.z()));
        final double a = surfaceArea();
        return a > 0 ? Math.min(box, 2 * cubicVolume() / a) : box;
    }

    // ------------------------------------------------------------------
    // Tessellation
    // ------------------------------------------------------------------

    /** quality 0..3: segments per full turn 24, 48, 96, 192. */
    public static int segments(int quality) {
        return 24 << Math.max(0, Math.min(3, quality));
    }

    public final Mesh mesh(int quality) {
        final boolean cached = quality >= 0 && quality < 4;
        final Mesh[] arr = meshes;
        if (cached && arr[quality] != null) {
            return arr[quality];
        }
        synchronized (this) {
            if (cached && meshes[quality] != null) {
                return meshes[quality];
            }
            final Shape sh = shape;
            Mesh built;
            try {
                if (error != null) {
                    built = Mesh.EMPTY;
                } else if (cached && sh != null) {
                    built = MeshCache.get(sh, quality, () -> buildMesh(quality));
                } else {
                    built = buildMesh(quality);
                }
            } catch (RuntimeException e) {
                built = Mesh.EMPTY;
            }
            if (cached) {
                meshes[quality] = built;
            }
            return built;
        }
    }

    /**
     * The mesh at a quality if it is made already, by this solid or by another of the same shape; null when it
     * would have to be made. The view takes a boolean's this way rather than drawing a preview while the
     * same CSG it did a moment ago (before an undo, say) runs again.
     */
    public final Mesh meshIfReady(int quality) {
        final Mesh[] arr = meshes;
        if (arr[quality] != null) {
            return arr[quality];
        }
        final Shape sh = shape;
        final Mesh m = sh == null || error != null ? null : MeshCache.peek(sh, quality);
        if (m != null) {
            synchronized (this) {
                if (meshes == arr && shape == sh && arr[quality] == null) {
                    arr[quality] = m;
                }
            }
        }
        return m;
    }

    protected abstract Mesh buildMesh(int quality);

    /**
     * A random point on the surface, the way G4VSolid::GetPointOnSurface is used
     * by CheckOverlaps: picked on the mesh by area, then pulled onto the true
     * surface along the gradient of the distance.
     */
    public Vec3 pointOnSurface(SplittableRandom rng) {
        final Mesh m = mesh(2);
        if (m.isEmpty()) {
            return extent().center();
        }
        final double[] cdf = surfaceCdf(m);
        final double u = rng.nextDouble() * cdf[cdf.length - 1];
        int lo = 0, hi = cdf.length - 1;
        while (lo < hi) {
            final int mid = (lo + hi) >>> 1;
            if (cdf[mid] < u) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        final int t = lo * 3;
        double a = rng.nextDouble(), b = rng.nextDouble();
        if (a + b > 1) {
            a = 1 - a;
            b = 1 - b;
        }
        final int i0 = m.tri[t] * 3, i1 = m.tri[t + 1] * 3, i2 = m.tri[t + 2] * 3;
        double x = m.pos[i0] + a * (m.pos[i1] - m.pos[i0]) + b * (m.pos[i2] - m.pos[i0]);
        double y = m.pos[i0 + 1] + a * (m.pos[i1 + 1] - m.pos[i0 + 1]) + b * (m.pos[i2 + 1] - m.pos[i0 + 1]);
        double z = m.pos[i0 + 2] + a * (m.pos[i1 + 2] - m.pos[i0 + 2]) + b * (m.pos[i2 + 2] - m.pos[i0 + 2]);
        for (int it = 0; it < 6; it++) {
            final double d = sdf(x, y, z);
            if (Math.abs(d) < 1e-9) {
                break;
            }
            final Vec3 n = normal(x, y, z);
            x -= n.x() * d;
            y -= n.y() * d;
            z -= n.z() * d;
        }
        return new Vec3(x, y, z);
    }

    private double[] cdf;
    private Mesh cdfMesh;

    private synchronized double[] surfaceCdf(Mesh m) {
        if (cdfMesh != m) {
            final int nt = m.triangleCount();
            final double[] c = new double[nt];
            double acc = 0;
            for (int i = 0; i < nt; i++) {
                acc += m.triArea(m.tri[i * 3], m.tri[i * 3 + 1], m.tri[i * 3 + 2]);
                c[i] = acc;
            }
            cdf = c;
            cdfMesh = m;
        }
        return cdf;
    }

    // ------------------------------------------------------------------
    // Code generation
    // ------------------------------------------------------------------

    /** The C++ expression that constructs this solid, with parameter expressions as C++. */
    public abstract String cpp(CppNames names);

    /** C++ text of a parameter, from its expression. */
    protected final String c(String key) {
        try {
            return Expr.parse(params.get(key).expr).toCpp();
        } catch (GeometryException e) {
            return Units.exact(params.get(key).value);
        }
    }

    /** C++ text of a table cell. */
    protected final String c(Table t, int row, int col) {
        try {
            return Expr.parse(t.rows.get(row)[col]).toCpp();
        } catch (GeometryException e) {
            return Units.exact(t.values.get(row)[col]);
        }
    }

    /** Resolves solid names to C++ variable names. */
    public interface CppNames {
        String solid(Solid s);

        String string(String s);
    }

    /** A copy with its own parameters (operands of a boolean are shared). */
    public Solid copy(String newName) {
        final Solid s = Solids.create(tag(), newName);
        copyParamsTo(s);
        return s;
    }

    protected final void copyParamsTo(Solid s) {
        for (Param p : params.values()) {
            if (s.params.containsKey(p.key)) {
                s.params.get(p.key).expr = p.expr;
            }
        }
        for (Table t : tables.values()) {
            final Table d = s.tables.get(t.element);
            if (d != null) {
                d.rows.clear();
                for (String[] r : t.rows) {
                    d.rows.add(r.clone());
                }
            }
        }
    }

    @Override
    public String toString() {
        return name + " (" + g4Class() + ")";
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /**
     * Exact signed distance to the 2D wedge of angles [s, s+d] with apex at the
     * origin; -infinity when the wedge is a full turn.
     */
    protected static double wedge(double x, double y, double cs, double ss, double ce, double se,
                                  double dphi) {
        if (dphi >= Units.TWOPI - 1e-12) {
            return Double.NEGATIVE_INFINITY;
        }
        final double h1 = ss * x - cs * y;      // >0: clockwise of the start ray
        final double h2 = ce * y - se * x;      // >0: counter-clockwise of the end ray
        final boolean in = dphi <= Math.PI ? (h1 <= 0 && h2 <= 0) : (h1 <= 0 || h2 <= 0);
        final double t1 = cs * x + ss * y, t2 = ce * x + se * y;
        final double r = Math.sqrt(x * x + y * y);
        final double d1 = t1 > 0 ? Math.abs(h1) : r;
        final double d2 = t2 > 0 ? Math.abs(h2) : r;
        final double d = Math.min(d1, d2);
        return in ? -d : d;
    }

    /** Signed distance to an (r, z) polygon, edges on the axis r = 0 not counting as surface. */
    protected static double rzDistance(double[] rz, double r, double z) {
        final int n = rz.length / 2;
        double d2 = Double.MAX_VALUE;
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double ri = rz[i * 2], zi = rz[i * 2 + 1], rj = rz[j * 2], zj = rz[j * 2 + 1];
            if ((zi > z) != (zj > z) && r < (rj - ri) * (z - zi) / (zj - zi) + ri) {
                in = !in;
            }
            if (ri == 0 && rj == 0) {
                continue; // the axis is no surface of a solid of revolution
            }
            final double ex = ri - rj, ez = zi - zj;
            final double wx = r - rj, wz = z - zj;
            final double l2 = ex * ex + ez * ez;
            double t = l2 > 0 ? (wx * ex + wz * ez) / l2 : 0;
            t = Math.max(0, Math.min(1, t));
            final double dx = wx - ex * t, dz = wz - ez * t;
            d2 = Math.min(d2, dx * dx + dz * dz);
        }
        final double d = Math.sqrt(d2);
        return in ? -d : d;
    }

    /** Volume of revolution of an (r, z) polygon over dphi (Pappus). */
    protected static double revolvedVolume(double[] rz, double dphi) {
        final int n = rz.length / 2;
        double m = 0;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double r0 = rz[j * 2], z0 = rz[j * 2 + 1], r1 = rz[i * 2], z1 = rz[i * 2 + 1];
            m += (r0 + r1) * (r0 * z1 - r1 * z0);
        }
        return Math.abs(m / 6.0) * dphi;
    }

    /** First moment about the axis, ∫∫ r dr dz, of an (r, z) polygon. */
    protected static double rzMoment(double[] rz) {
        return revolvedVolume(rz, 1.0);
    }
}

package com.sphere.core.geant4;

import com.sphere.components.rootview.RootColors;
import com.sphere.components.rootview.RootKey;
import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RootIO;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A ROOT geometry -- the TGeoManager a .root file holds, as TGeoManager::Export
 * writes it -- read into a G4editor document, without ROOT.
 *
 * The file is read by its own class descriptions (rootio), then translated
 * the way TGDMLWrite translates a TGeo geometry to GDML, so that what the
 * editor saves is what ROOT would have exported:
 * <ul>
 *   <li>units: ROOT's cm and degrees become mm and degrees, written as
 *       expressions ("12.5*mm") that read as typed;</li>
 *   <li>shapes: each TGeo shape becomes the Geant4 solid of the same surface
 *       (TGeoTrap, TGeoGtra and TGeoArb8 become G4Trap or G4GenericTrap,
 *       a scaled full sphere a G4Ellipsoid, a composite a boolean tree);</li>
 *   <li>materials: a material named after a NIST one is that NIST material;
 *       vacuum becomes G4_Galactic; any other is defined from its elements,
 *       which are NIST's unless their molar mass says they are enriched;</li>
 *   <li>placements: node matrices become GDML positions and rotations;
 *       divisions that fill their mother become replicas (G4PVReplica), the
 *       others one placement per slice; assemblies are opened up, their parts
 *       placed in the assembly's mother, which is what Geant4 does with them.</li>
 * </ul>
 * What has no Geant4 counterpart is approximated and said so in the warnings.
 */
public final class TGeoReader {

    private TGeoReader() {
    }

    /** What a read found: the document, what could not be carried over exactly, and what was done. */
    public record Result(Geometry geometry, List<String> warnings, List<String> notes) {
    }

    /** One geometry a file holds: the key's name, cycle and title. */
    public record Entry(String name, int cycle, String title) {
    }

    /** The geometries (TGeoManager keys) of a file, in the order the file lists them. */
    public static List<Entry> geometries(Path file) throws IOException {
        try (RootIO io = RootIO.open(file)) {
            final List<Entry> out = new ArrayList<>();
            for (RootKey k : io.keys()) {
                if (k.className.equals("TGeoManager")) {
                    out.add(new Entry(k.name, k.cycle, k.title));
                }
            }
            return out;
        }
    }

    /** Reads the geometry of that name (null: the first one), as TGeoManager::Import picks it. */
    public static Result read(Path file, String name) throws IOException, GeometryException {
        try (RootIO io = RootIO.open(file)) {
            RootKey key = null;
            for (RootKey k : io.keys()) {
                if (k.className.equals("TGeoManager") && (name == null || name.equals(k.name))
                    && (key == null || !key.name.equals(k.name) || k.cycle > key.cycle)) {
                    key = k;
                    if (name == null) {
                        break;
                    }
                }
            }
            if (key == null) {
                throw new GeometryException(name == null
                    ? file.getFileName() + " holds no ROOT geometry (no TGeoManager)"
                    : file.getFileName() + " has no geometry named " + name);
            }
            final Object o;
            try {
                o = io.read(key);
            } catch (RuntimeException e) {
                throw new GeometryException("the geometry " + key.name + " could not be read: " + e.getMessage(), e);
            }
            if (!(o instanceof RObject geo)) {
                throw new GeometryException("the geometry " + key.name + " could not be read");
            }
            if (geo.has("@unread")) {
                throw new GeometryException(geo.string("@unread"));
            }
            return new Reader(geo, key.name).read();
        }
    }

    // ------------------------------------------------------------------
    // Units and numbers
    // ------------------------------------------------------------------

    /** ROOT's 1 atmosphere, in Geant4's internal pressure unit (TGeoMaterial's STP_pressure). */
    static final double ATMOSPHERE = 6.32420e+8;

    /** A number as short as it can be written without changing it (15 significant digits). */
    static String fmt(double v) {
        if (Math.abs(v) < 1e-13 || Double.isNaN(v)) {
            return "0";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "1e300" : "-1e300";
        }
        final String s = new BigDecimal(v).round(new MathContext(15)).stripTrailingZeros().toPlainString();
        return s.length() > 24 ? Units.exact(v) : s;
    }

    /** A length given in cm, as an expression in mm. */
    static String mm(double cm) {
        return fmt(cm * 10) + "*mm";
    }

    /** An angle given in degrees. */
    static String deg(double d) {
        return fmt(d) + "*deg";
    }

    /** An angle computed from a matrix, in radians: rounded to 12 digits, which removes what cos and sin leave. */
    static String degOf(double rad) {
        final double d = Math.toDegrees(rad);
        if (Math.abs(d) < 1e-10) {
            return "0*deg";
        }
        return new BigDecimal(d).round(new MathContext(12)).stripTrailingZeros().toPlainString() + "*deg";
    }

    /**
     * A name GDML and the editor accept: letters, digits and underscores, not
     * starting with a digit. The signs that tell the two halves of a detector
     * apart keep their meaning: "D0+" and "D0-" become "D0_plus" and "D0_minus".
     */
    static String clean(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim();
        s = s.replaceAll("\\+$", "_plus").replaceAll("-$", "_minus").replace("+", "_plus_");
        s = s.replaceAll("[^A-Za-z0-9_]", "_");
        if (s.isEmpty() || s.matches("_+")) {
            s = fallback;
        }
        if (Character.isDigit(s.charAt(0))) {
            s = "_" + s;
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Transforms
    // ------------------------------------------------------------------

    /** A TGeo matrix: rotation (row major, local to master), translation in cm, and whether it mirrors. */
    record Mat(double[] r, double[] t, boolean scaled) {
        static final Mat IDENTITY = new Mat(new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, new double[3], false);

        double det() {
            return r[0] * (r[4] * r[8] - r[5] * r[7]) - r[1] * (r[3] * r[8] - r[5] * r[6])
                + r[2] * (r[3] * r[7] - r[4] * r[6]);
        }

        boolean reflected() {
            return det() < 0;
        }

        /** this * o: o first, then this. */
        Mat times(Mat o) {
            final double[] m = Xform.mul(r, o.r);
            final double[] t2 = {
                r[0] * o.t[0] + r[1] * o.t[1] + r[2] * o.t[2] + t[0],
                r[3] * o.t[0] + r[4] * o.t[1] + r[5] * o.t[2] + t[1],
                r[6] * o.t[0] + r[7] * o.t[1] + r[8] * o.t[2] + t[2]};
            return new Mat(m, t2, scaled || o.scaled);
        }

        Mat inverse() {
            final double[] rt = Xform.transpose(r);
            return new Mat(rt, new double[] {
                -(rt[0] * t[0] + rt[1] * t[1] + rt[2] * t[2]),
                -(rt[3] * t[0] + rt[4] * t[1] + rt[5] * t[2]),
                -(rt[6] * t[0] + rt[7] * t[1] + rt[8] * t[2])}, scaled);
        }

        /** The proper rotation left when the mirror through the local z = 0 plane is taken out. */
        Mat unreflected() {
            final double[] m = r.clone();
            m[2] = -m[2];
            m[5] = -m[5];
            m[8] = -m[8];
            return new Mat(m, t, scaled);
        }

        static Mat translation(double x, double y, double z) {
            return new Mat(IDENTITY.r, new double[] {x, y, z}, false);
        }

        static Mat rotationZ(double degrees) {
            final double a = Math.toRadians(degrees), c = Math.cos(a), s = Math.sin(a);
            return new Mat(new double[] {c, -s, 0, s, c, 0, 0, 0, 1}, new double[3], false);
        }
    }

    // ------------------------------------------------------------------

    private static final class Reader {
        final RObject geo;
        final String keyName;
        final Geometry g = new Geometry();
        final NistDB nist = NistDB.get();
        final List<String> warnings = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final Map<String, Integer> warningCounts = new LinkedHashMap<>();
        final Set<String> used = new HashSet<>();
        final Map<RObject, Solid> solids = new IdentityHashMap<>();
        /** Where a shape's own frame is offset from the solid's (a TGeoBBox with an origin, a half-space). */
        final Map<RObject, Mat> shapeFrames = new IdentityHashMap<>();
        final Map<RObject, LogicalVolume> volumes = new IdentityHashMap<>();
        final Map<LogicalVolume, Mat> volumeFrames = new HashMap<>();
        final Map<RObject, String> materials = new IdentityHashMap<>();
        final Map<String, String> customElements = new HashMap<>();
        final Map<String, Integer> shapeKinds = new TreeMap<>();
        /** What was recognised and turned into another solid, by kind, with a few examples. */
        final Map<String, List<String>> recognised = new LinkedHashMap<>();
        /** Placements whose ROOT node may overlap its siblings (AddNodeOverlap: GEANT3's MANY). */
        final Set<PhysicalVolume> many = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        /** Placements of division slices (TGeoNodeOffset): ROOT's navigator gives them their mother's overlapping state. */
        final Set<PhysicalVolume> offsets = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        /** The TGeo medium of each volume: what GEANT3's virtual levels compare. */
        final Map<LogicalVolume, RObject> media = new IdentityHashMap<>();
        /** The TGeo shape each logical volume was made from (resolved, for a runtime shape). */
        final Map<LogicalVolume, RObject> lvShapes = new IdentityHashMap<>();
        /** A volume of runtime shape, once per mother shape it takes its dimensions from. */
        final Map<RuntimeKey, LogicalVolume> runtimeVolumes = new HashMap<>();
        int placements;
        int flattened;
        int replicas;
        int expandedDivisions;
        int runtimeShapes;
        int mirrored;

        Reader(RObject geo, String keyName) {
            this.geo = geo;
            this.keyName = keyName;
        }

        void warn(String category, String message) {
            final int n = warningCounts.merge(category, 1, Integer::sum);
            if (n <= 3) {
                warnings.add(message);
            } else if (n == 4) {
                warnings.add("(more of the same: " + category + ")");
            }
        }

        /** Counts a recognition of a kind ("TGeoGtra read as G4GenericTrap"), keeping a few names as examples. */
        void recognise(String kind, String example) {
            recognised.computeIfAbsent(kind, k -> new ArrayList<>()).add(example);
        }

        String unique(String base) {
            return unique(base, "_");
        }

        /**
         * A name no other object has. ROOT allows the same name on a shape, a
         * volume and its nodes ("World", "World", "World_1"), the editor does
         * not: a shape or a volume met again takes a suffix of its own kind
         * ("World_shape", "RDIV_v2"), so nodes keep ROOT's names.
         */
        String unique(String base, String suffix) {
            final String b = clean(base, "Object");
            if (!used.contains(b) && !Units.isReserved(b)) {
                used.add(b);
                return b;
            }
            if (suffix.equals("_shape") && !used.contains(b + "_shape")) {
                used.add(b + "_shape");
                return b + "_shape";
            }
            // the next number to try for this name, so a name met thousands of times costs no more
            final String stem = b + suffix;
            for (int i = nextSuffix.getOrDefault(stem, 2); ; i++) {
                final String n = stem + i;
                if (!used.contains(n) && !Units.isReserved(n)) {
                    used.add(n);
                    nextSuffix.put(stem, i + 1);
                    return n;
                }
            }
        }

        final Map<String, Integer> nextSuffix = new HashMap<>();

        Result read() throws GeometryException {
            final String title = geo.string("fName");
            g.setTitle(clean(title.isBlank() ? keyName : title, "detector"));
            final RObject master = geo.object("fMasterVolume");
            if (master == null) {
                throw new GeometryException("the geometry has no top volume (fMasterVolume is not set)");
            }
            final LogicalVolume world;
            if (isAssembly(master)) {
                world = assemblyWorld(master);
            } else {
                world = volume(master, null);
            }
            world.wireframe = true;
            world.color = 0x40B0B8C8;
            g.setWorld(world);
            g.rebuild();
            dropEmpty(world);
            if (!many.isEmpty()) {
                new ManyResolver(this).resolve();
                prune(world);
                g.rebuild();
            }

            final long usedMaterials = g.volumes().stream().map(LogicalVolume::material).distinct().count();
            notes.add(String.format(Locale.ROOT, "ROOT geometry %s: %d volumes, %d placements, %d solids, %d materials",
                keyName, g.volumes().size(), placements, g.solids().size(), usedMaterials));
            final long physical = physical(world, new IdentityHashMap<>());
            if (physical > g.instances().size()) {
                notes.add(String.format(Locale.ROOT, "%,d physical volumes in all: G4editor draws and analyzes the first"
                    + " %,d; Geant4 and the files written keep them all", physical, g.instances().size()));
            }
            if (mirrored > 0) {
                notes.add(mirrored + " mirrored placement" + (mirrored > 1 ? "s" : "") + " (reflection matrices) kept as such:"
                    + " GDML writes them with scale (1, 1, -1), Geant4 places them through G4ReflectionFactory");
            }
            if (runtimeShapes > 0) {
                notes.add(runtimeShapes + " shape" + (runtimeShapes > 1 ? "s" : "") + " took dimensions from their mother"
                    + " (ROOT's runtime shapes, negative parameters), as TGeoVolume::CheckShapes does");
            }
            final StringBuilder kinds = new StringBuilder("shapes:");
            shapeKinds.forEach((k, n) -> kinds.append(' ').append(k).append(" x").append(n));
            notes.add(kinds.toString());
            if (replicas > 0) {
                notes.add(replicas + " division" + (replicas > 1 ? "s" : "") + " read as G4PVReplica");
            }
            if (expandedDivisions > 0) {
                notes.add(expandedDivisions + " division" + (expandedDivisions > 1 ? "s" : "")
                    + " read as one placement per slice (not a G4PVReplica: not centered and filling its mother, or slices that differ)");
            }
            if (flattened > 0) {
                notes.add(flattened + " assembly placement" + (flattened > 1 ? "s" : "")
                    + " opened up: their parts are placed in the assembly's mother, as Geant4 does");
            }
            recognised.forEach((kind, names) -> notes.add(names.size() + " " + kind
                + (names.isEmpty() ? "" : " (" + String.join(", ", names.subList(0, Math.min(3, names.size())))
                    + (names.size() > 3 ? ", ..." : "") + ")")));
            for (Geometry.Issue i : g.issues()) {
                if (i.severity() == Geometry.Severity.ERROR) {
                    warn("model", i.message());
                }
            }
            return new Result(g, warnings, notes);
        }

        // --------------------------------------------------------------
        // Volumes and placements
        // --------------------------------------------------------------

        static boolean isAssembly(RObject vol) {
            final RObject shape = vol.object("fShape");
            return vol.className.equals("TGeoVolumeAssembly") || (shape != null && shape.className.equals("TGeoShapeAssembly"));
        }

        /** A volume of runtime shape, as placed in a mother of a given shape. */
        record RuntimeKey(RObject volume, RObject motherShape) {
        }

        /**
         * The logical volume of a TGeo volume, made once. A volume whose shape
         * is a runtime one (negative parameters, GEANT3's way of saying "as my
         * mother") is made once per mother shape, its dimensions taken from it.
         */
        LogicalVolume volume(RObject vol, RObject motherShape) {
            final RObject shape = vol.object("fShape");
            final RObject resolved = isRuntime(shape) && motherShape != null ? resolveRuntime(shape, motherShape) : null;
            final RuntimeKey key = resolved == null ? null : new RuntimeKey(vol, motherShape);
            final LogicalVolume known = key == null ? volumes.get(vol) : runtimeVolumes.get(key);
            if (known != null) {
                return known;
            }
            final String name = unique(vol.string("fName").isBlank() ? "Volume" : vol.string("fName"), "_v");
            final LogicalVolume lv = new LogicalVolume(name, null, material(medium(vol)));
            media.put(lv, vol.object("fMedium"));
            if (key == null) {
                volumes.put(vol, lv);
            } else {
                runtimeVolumes.put(key, lv);
                runtimeShapes++;
            }
            g.addVolume(lv);
            final RObject use = resolved != null ? resolved : shape;
            lv.solid = solid(use, name);
            if (use != null) {
                lvShapes.put(lv, use);
            }
            final Mat frame = shapeFrames.get(use);
            if (frame != null) {
                volumeFrames.put(lv, frame);
            }
            style(vol, lv);
            daughters(vol, lv, Mat.IDENTITY, "", false);
            return lv;
        }

        /**
         * The volumes the overlap resolution left placed nowhere (cut copies
         * took their place), and the solids no volume uses any more, dropped:
         * neither is in the ROOT file as such.
         */
        void prune(LogicalVolume world) {
            final Set<LogicalVolume> reached = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            final java.util.ArrayDeque<LogicalVolume> todo = new java.util.ArrayDeque<>();
            todo.push(world);
            while (!todo.isEmpty()) {
                final LogicalVolume lv = todo.pop();
                if (reached.add(lv)) {
                    for (PhysicalVolume d : lv.daughters) {
                        todo.push(d.logical);
                    }
                }
            }
            for (LogicalVolume lv : new ArrayList<>(g.volumes())) {
                if (!reached.contains(lv)) {
                    g.volumes.remove(lv.name);
                }
            }
            for (Solid s : g.unusedSolids()) {
                g.removeSolid(s);
            }
        }

        /**
         * Volumes whose shape holds no volume -- a box of zero thickness, a
         * tube whose rmin is its rmax -- which ROOT accepts and never finds a
         * point in, and Geant4 refuses: left out, with what they hold, which
         * ROOT never reaches either.
         */
        void dropEmpty(LogicalVolume world) {
            final List<LogicalVolume> empty = new ArrayList<>();
            for (LogicalVolume lv : g.volumes()) {
                if (lv != world && lv.solid != null && lv.solid.error() != null && emptyShape(lvShapes.get(lv))) {
                    empty.add(lv);
                }
            }
            if (empty.isEmpty()) {
                return;
            }
            int removed = 0;
            final Set<LogicalVolume> gone = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            gone.addAll(empty);
            for (LogicalVolume m : g.volumes()) {
                final int before = m.daughters.size();
                m.daughters.removeIf(pv -> gone.contains(pv.logical));
                removed += before - m.daughters.size();
                if (before != m.daughters.size()) {
                    m.invalidate();
                }
            }
            many.removeIf(pv -> gone.contains(pv.logical));
            for (LogicalVolume lv : empty) {
                g.deleteVolume(lv);
            }
            g.rebuild();
            final List<String> names = empty.stream().map(lv -> lv.name).limit(3).toList();
            notes.add(removed + " placement" + (removed == 1 ? "" : "s") + " of " + empty.size() + " shape"
                + (empty.size() == 1 ? "" : "s") + " holding no volume (zero thickness, rmin = rmax) left out ("
                + String.join(", ", names) + (empty.size() > 3 ? ", ..." : "")
                + "): ROOT never finds a point in them, Geant4 refuses them");
        }

        /** True when a TGeo shape encloses nothing: its box is flat, or its outer radius is not above its inner one. */
        static boolean emptyShape(RObject s) {
            if (s == null || s.className.equals("TGeoCompositeShape") || s.className.equals("TGeoShapeAssembly")) {
                return false;
            }
            if (s.has("fDX") && (s.real("fDX") <= 0 || s.real("fDY") <= 0 || s.real("fDZ") <= 0)) {
                return true;
            }
            if (s.has("fRmin") && s.has("fRmax") && !s.className.equals("TGeoEltu") && s.real("fRmax") <= s.real("fRmin")) {
                return true;
            }
            return s.has("fRmax1") && s.real("fRmax1") <= s.real("fRmin1") && s.real("fRmax2") <= s.real("fRmin2");
        }

        /** TGeoShape::kGeoRunTimeShape, or a negative dimension where the constructor would have set it. */
        static boolean isRuntime(RObject shape) {
            if (shape == null) {
                return false;
            }
            if ((shape.number("fShapeBits") & (1L << 7)) != 0) {
                return true;
            }
            for (String m : RUNTIME_DIMENSIONS.getOrDefault(shape.className, new String[0])) {
                if (shape.real(m) < 0) {
                    return true;
                }
            }
            return false;
        }

        /** The dimensions a runtime shape may leave to its mother, by class. */
        static final Map<String, String[]> RUNTIME_DIMENSIONS = Map.ofEntries(
            Map.entry("TGeoBBox", new String[] {"fDX", "fDY", "fDZ"}),
            Map.entry("TGeoTrd1", new String[] {"fDx1", "fDx2", "fDy", "fDz"}),
            Map.entry("TGeoTrd2", new String[] {"fDx1", "fDx2", "fDy1", "fDy2", "fDz"}),
            Map.entry("TGeoTrap", new String[] {"fDz", "fH1", "fBl1", "fTl1", "fH2", "fBl2", "fTl2"}),
            Map.entry("TGeoGtra", new String[] {"fDz", "fH1", "fBl1", "fTl1", "fH2", "fBl2", "fTl2"}),
            Map.entry("TGeoPara", new String[] {"fX", "fY", "fZ"}),
            Map.entry("TGeoTube", new String[] {"fRmin", "fRmax", "fDz"}),
            Map.entry("TGeoTubeSeg", new String[] {"fRmin", "fRmax", "fDz"}),
            Map.entry("TGeoCtub", new String[] {"fRmin", "fRmax", "fDz"}),
            Map.entry("TGeoEltu", new String[] {"fRmin", "fRmax", "fDz"}),
            Map.entry("TGeoCone", new String[] {"fDz", "fRmin1", "fRmax1", "fRmin2", "fRmax2"}),
            Map.entry("TGeoConeSeg", new String[] {"fDz", "fRmin1", "fRmax1", "fRmin2", "fRmax2"}),
            Map.entry("TGeoSphere", new String[] {"fRmin", "fRmax"}));

        /**
         * A runtime shape made concrete, as its GetMakeRuntimeShape does: each
         * negative dimension is the mother's dimension of the same name, or,
         * when the mother is of another class, the matching half length of its
         * bounding box. A trapezoid's corners are computed again from its new
         * dimensions.
         */
        RObject resolveRuntime(RObject shape, RObject mother) {
            final RObject r = new RObject(shape.className, shape.version);
            r.members.putAll(shape.members);
            r.members.put("fShapeBits", shape.number("fShapeBits") & ~(1L << 7));
            for (String m : RUNTIME_DIMENSIONS.getOrDefault(shape.className, new String[0])) {
                if (shape.real(m) >= 0) {
                    continue;
                }
                double v;
                if (mother.has(m) && mother.real(m) >= 0) {
                    v = mother.real(m);
                } else {
                    final String axis = m.startsWith("fDz") || m.equals("fZ") || m.equals("fDZ") ? "fDZ"
                        : m.startsWith("fDy") || m.startsWith("fH") || m.equals("fY") || m.equals("fDY") ? "fDY" : "fDX";
                    v = m.startsWith("fRmin") ? 0 : mother.real(axis);
                }
                r.members.put(m, v);
            }
            if (shape.className.equals("TGeoTrap") || shape.className.equals("TGeoGtra")) {
                r.members.put("fXY", trapVertices(r));
            }
            if (shape.className.equals("TGeoBBox")) {
                r.members.put("fOrigin", new double[3]);
            }
            return r;
        }

        /** TGeoTrap's constructor: the eight corners from the half lengths and angles; TGeoGtra's twist on top. */
        static double[] trapVertices(RObject t) {
            final double d = Math.PI / 180;
            final double dz = t.real("fDz"), th = t.real("fTheta") * d, ph = t.real("fPhi") * d;
            final double h1 = t.real("fH1"), bl1 = t.real("fBl1"), tl1 = t.real("fTl1"), a1 = t.real("fAlpha1") * d;
            final double h2 = t.real("fH2"), bl2 = t.real("fBl2"), tl2 = t.real("fTl2"), a2 = t.real("fAlpha2") * d;
            final double tx = Math.tan(th) * Math.cos(ph), ty = Math.tan(th) * Math.sin(ph);
            final double ta1 = Math.tan(a1), ta2 = Math.tan(a2);
            final double[] xy = {
                -dz * tx - h1 * ta1 - bl1, -dz * ty - h1, -dz * tx + h1 * ta1 - tl1, -dz * ty + h1,
                -dz * tx + h1 * ta1 + tl1, -dz * ty + h1, -dz * tx - h1 * ta1 + bl1, -dz * ty - h1,
                dz * tx - h2 * ta2 - bl2, dz * ty - h2, dz * tx + h2 * ta2 - tl2, dz * ty + h2,
                dz * tx + h2 * ta2 + tl2, dz * ty + h2, dz * tx - h2 * ta2 + bl2, dz * ty - h2};
            if (t.className.equals("TGeoGtra")) {
                final double tw = t.real("fTwistAngle") * d;
                for (int end = 0; end < 2; end++) {
                    final double sign = end == 0 ? -1 : 1;
                    final double xc = sign * dz * Math.sin(th) * Math.cos(ph), yc = sign * dz * Math.sin(th) * Math.sin(ph);
                    final double c = Math.cos(sign * 0.5 * tw), s = Math.sin(sign * 0.5 * tw);
                    for (int i = end * 4; i < end * 4 + 4; i++) {
                        final double x = xy[i * 2] - xc, y = xy[i * 2 + 1] - yc;
                        xy[i * 2] = x * c + y * s + xc;
                        xy[i * 2 + 1] = -x * s + y * c + yc;
                    }
                }
            }
            return xy;
        }

        /** Physical volumes below a logical volume, itself included, every copy counted. */
        static long physical(LogicalVolume lv, Map<LogicalVolume, Long> memo) {
            final Long known = memo.get(lv);
            if (known != null) {
                return known;
            }
            long n = 1;
            for (PhysicalVolume pv : lv.daughters) {
                n += (long) pv.copies() * physical(pv.logical, memo);
            }
            memo.put(lv, n);
            return n;
        }

        static RObject medium(RObject vol) {
            final RObject med = vol.object("fMedium");
            return med == null ? null : med.object("fMaterial");
        }

        /** Colour, transparency and visibility, as TGeoVolume keeps them. */
        void style(RObject vol, LogicalVolume lv) {
            final int index = vol.integer("fLineColor");
            int transparency = (byte) vol.integer("fTransparency");
            if (transparency < 0) {
                // -1: the material's, kept in its fill style as 3000 + transparency
                final RObject mat = medium(vol);
                final int fill = mat == null ? 0 : mat.integer("fFillStyle");
                transparency = fill >= 3000 && fill <= 3100 ? fill - 3000 : 0;
            }
            final int alpha = (int) Math.round(255 * (1 - Math.max(0, Math.min(100, transparency)) / 100.0));
            if (index <= 1 || !RootColors.defined(index)) {
                // ROOT's default (black) says nothing: colour by material, as the editor does
                lv.color = (MaterialColors.colorFor(lv.material) & 0xFFFFFF) | (alpha << 24);
            } else {
                final java.awt.Color c = RootColors.color(index);
                lv.color = (alpha << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
            }
            // TGeoAtt::kVisThis
            lv.visible = (vol.number("fGeoAtt") & 4) != 0 || vol == geo.object("fMasterVolume");
        }

        /** The world, when ROOT's top volume is an assembly: a box of vacuum around what it holds. */
        LogicalVolume assemblyWorld(RObject master) {
            final RObject shape = master.object("fShape");
            double dx = shape == null ? 0 : shape.real("fDX");
            double dy = shape == null ? 0 : shape.real("fDY");
            double dz = shape == null ? 0 : shape.real("fDZ");
            final double[] o = shape == null ? new double[3] : arr(shape.get("fOrigin"), 3);
            if (dx <= 0 || dy <= 0 || dz <= 0) {
                dx = dy = dz = 500;
            }
            final Solid box = Solids.create("box", unique("WorldBox"));
            box.set("x", mm(2.2 * (dx + Math.abs(o[0]))));
            box.set("y", mm(2.2 * (dy + Math.abs(o[1]))));
            box.set("z", mm(2.2 * (dz + Math.abs(o[2]))));
            g.addSolid(box);
            final LogicalVolume lv = new LogicalVolume(unique("World"), box, "G4_Galactic");
            g.addVolume(lv);
            daughters(master, lv, Mat.IDENTITY, clean(master.string("fName"), "Assembly") + "_", false);
            warn("assembly world", "the top volume " + master.string("fName")
                + " is an assembly: it is placed in a vacuum world box around it");
            return lv;
        }

        /**
         * TGeoNode::kGeoNodeOverlap: placed with AddNodeOverlap, GEANT3's MANY.
         * ROOT 5 kept TGeoNode's flags four bits lower (kGeoNodeOverlap was
         * BIT(13), where ROOT 6 has kInvalidObject), and ROOT 6 still reads
         * them so: a node with none of today's flags and BIT(13) is overlapping.
         */
        static boolean isMany(RObject node) {
            final long bits = node.number("fBits");
            return (bits & (1L << 17)) != 0 || ((bits & (0x1FL << 14)) == 0 && (bits & (1L << 13)) != 0);
        }

        /**
         * The nodes of a TGeo volume, placed in a logical volume; pre is the
         * transform of an opened assembly, many says the assembly was placed
         * overlapping (its parts then are too).
         */
        void daughters(RObject vol, LogicalVolume mother, Mat pre, String prefix, boolean manyChain) {
            final Map<RObject, List<RObject>> divisions = new LinkedHashMap<>();
            final RObject motherShape = lvShapes.get(mother);
            for (Object o : vol.list("fNodes")) {
                if (!(o instanceof RObject node)) {
                    continue;
                }
                final RObject dv = node.object("fVolume");
                if (dv == null) {
                    continue;
                }
                if (node.className.equals("TGeoNodeOffset")) {
                    final RObject finder = node.object("fFinder");
                    divisions.computeIfAbsent(finder != null ? finder : vol.object("fFinder"), k -> new ArrayList<>()).add(node);
                    continue;
                }
                final boolean overlapping = manyChain || isMany(node);
                final Mat m = pre.times(matrix(node.object("fMatrix"), "placement " + node.string("fName")));
                if (isAssembly(dv)) {
                    flattened++;
                    daughters(dv, mother, m, prefix + clean(node.string("fName"), "part") + "_", overlapping);
                    continue;
                }
                final PhysicalVolume pv = place(volume(dv, motherShape), mother, m,
                    prefix + clean(node.string("fName"), dv.string("fName")), node.integer("fNumber"));
                if (overlapping) {
                    many.add(pv);
                }
            }
            for (Map.Entry<RObject, List<RObject>> d : divisions.entrySet()) {
                division(vol, mother, pre, prefix, d.getKey(), d.getValue());
            }
        }

        /** One placement: the TGeo node matrix as a GDML position and rotation, frames of shapes accounted for. */
        PhysicalVolume place(LogicalVolume child, LogicalVolume mother, Mat m, String name, int copy) {
            final Mat inMother = volumeFrames.containsKey(mother) ? volumeFrames.get(mother).inverse().times(m) : m;
            Mat full = volumeFrames.containsKey(child) ? inMother.times(volumeFrames.get(child)) : inMother;
            final PhysicalVolume pv = new PhysicalVolume(unique(name), child, mother);
            pv.copyNumber = copy;
            if (full.reflected()) {
                // a mirrored placement: the mirror through the daughter's z = 0 plane, then a rotation
                pv.reflected = true;
                full = full.unreflected();
                mirrored++;
            }
            setPlacement(pv, full);
            mother.daughters.add(pv);
            placements++;
            return pv;
        }

        static void setPlacement(PhysicalVolume pv, Mat m) {
            pv.setPosition(mm(m.t[0]), mm(m.t[1]), mm(m.t[2]));
            // R_gdml = R^T: the node matrix maps daughter to mother (G4PVPlacement's inverse)
            final double[] a = Xform.gdmlAngles(Xform.transpose(m.r));
            pv.setRotation(degOf(a[0]), degOf(a[1]), degOf(a[2]));
        }

        /**
         * A TGeo division: a replica when it is one (same volume, slices
         * centred and filling the mother along x, y or z, or turning around
         * z), one placement per slice otherwise.
         */
        void division(RObject vol, LogicalVolume mother, Mat pre, String prefix, RObject finder, List<RObject> nodes) {
            final String kind = finder == null ? "" : finder.className;
            final double step = finder == null ? 0 : finder.real("fStep");
            final double start = finder == null ? 0 : finder.real("fStart");
            final double end = finder == null ? 0 : finder.real("fEnd");
            final int n = nodes.size();
            final RObject first = nodes.get(0).object("fVolume");
            boolean same = true;
            for (RObject node : nodes) {
                same &= node.object("fVolume") == first;
            }
            final RObject shape = vol.object("fShape");
            PhysicalVolume.Replica.Axis axis = null;
            if (same && pre == Mat.IDENTITY && !volumeFrames.containsKey(mother) && step > 0 && shape != null
                && !isAssembly(first)) {
                final double[] origin = arr(shape.get("fOrigin"), 3);
                final double tol = 1e-9 * Math.max(1, Math.abs(step) * n);
                final boolean centred = Math.abs(start + end) < tol && Math.abs(end - start - n * step) < tol;
                switch (kind) {
                    case "TGeoPatternX" -> axis = centred && Math.abs(2 * shape.real("fDX") - n * step) < tol
                        && Math.abs(origin[0]) < tol ? PhysicalVolume.Replica.Axis.kXAxis : null;
                    case "TGeoPatternY" -> axis = centred && Math.abs(2 * shape.real("fDY") - n * step) < tol
                        && Math.abs(origin[1]) < tol ? PhysicalVolume.Replica.Axis.kYAxis : null;
                    case "TGeoPatternZ" -> axis = centred && Math.abs(2 * shape.real("fDZ") - n * step) < tol
                        && Math.abs(origin[2]) < tol ? PhysicalVolume.Replica.Axis.kZAxis : null;
                    case "TGeoPatternCylPhi" -> axis = Math.abs(n * step - 360) < 1e-9 || phiSpan(mother, start, n * step)
                        ? PhysicalVolume.Replica.Axis.kPhi : null;
                    default -> axis = null;
                }
            }
            final RObject motherShape = lvShapes.get(mother);
            if (axis != null) {
                final LogicalVolume child = volume(first, motherShape);
                String base = nodes.get(0).string("fName").replaceAll("_\\d+$", "");
                final PhysicalVolume pv = new PhysicalVolume(unique(prefix + clean(base, first.string("fName")) + "_rep"),
                    child, mother);
                final PhysicalVolume.Replica r = new PhysicalVolume.Replica();
                r.axis = axis;
                r.number = String.valueOf(n);
                if (axis == PhysicalVolume.Replica.Axis.kPhi) {
                    r.width = deg(step);
                    r.offset = deg(start);
                } else {
                    r.width = mm(step);
                    r.offset = "0*mm";
                }
                pv.replica = r;
                mother.daughters.add(pv);
                offsets.add(pv);
                placements++;
                replicas++;
                return;
            }
            expandedDivisions++;
            for (RObject node : nodes) {
                final RObject dv = node.object("fVolume");
                final Mat m = pre.times(sliceMatrix(finder, node));
                if (isAssembly(dv)) {
                    flattened++;
                    daughters(dv, mother, m, prefix + clean(node.string("fName"), "part") + "_", false);
                    continue;
                }
                offsets.add(place(volume(dv, motherShape), mother, m, prefix + clean(node.string("fName"), dv.string("fName")),
                    node.integer("fNumber")));
            }
        }

        /** True when the mother is a phi segment exactly as wide as the division. */
        static boolean phiSpan(LogicalVolume mother, double start, double span) {
            final Solid s = mother.solid;
            if (s == null || s.param("deltaphi") == null || s.param("startphi") == null) {
                return false;
            }
            try {
                final double d = Math.toDegrees(Expr.eval(s.param("deltaphi").expr(), null));
                final double a = Math.toDegrees(Expr.eval(s.param("startphi").expr(), null));
                return Math.abs(d - span) < 1e-9 && Math.abs(Math.IEEEremainder(a - start, 360)) < 1e-9;
            } catch (GeometryException e) {
                return false;
            }
        }

        /** The matrix of one slice of a division, as the pattern finder's cd() computes it. */
        Mat sliceMatrix(RObject finder, RObject node) {
            final double off = node.real("fOffset");
            if (finder == null) {
                warn("division", "a division without its pattern finder: slices placed at its center");
                return Mat.IDENTITY;
            }
            final double angle = finder.real("fStart") + (node.integer("fIndex") + 0.5) * finder.real("fStep");
            return switch (finder.className) {
                case "TGeoPatternX", "TGeoPatternParaX" -> Mat.translation(off, 0, 0);
                case "TGeoPatternY" -> Mat.translation(0, off, 0);
                case "TGeoPatternParaY" -> Mat.translation(off * finder.real("fTxy"), off, 0);
                case "TGeoPatternZ" -> Mat.translation(0, 0, off);
                case "TGeoPatternParaZ", "TGeoPatternTrapZ" -> Mat.translation(off * finder.real("fTxz"),
                    off * finder.real("fTyz"), off);
                case "TGeoPatternCylPhi", "TGeoPatternSphPhi" -> Mat.rotationZ(angle);
                case "TGeoPatternCylR", "TGeoPatternSphR", "TGeoPatternSphTheta" -> Mat.IDENTITY;
                default -> {
                    warn("division", "division pattern " + finder.className + " is not known: slices placed at the center");
                    yield Mat.IDENTITY;
                }
            };
        }

        /** Any TGeo matrix, as rotation and translation. */
        Mat matrix(RObject m, String what) {
            if (m == null) {
                return Mat.IDENTITY;
            }
            double[] r = null;
            double[] t = null;
            double[] scale = null;
            switch (m.className) {
                case "TGeoIdentity" -> {
                    return Mat.IDENTITY;
                }
                case "TGeoTranslation" -> t = arr(m.get("fTranslation"), 3);
                case "TGeoRotation" -> r = arr(m.get("fRotationMatrix"), 9);
                case "TGeoScale" -> scale = arr(m.get("fScale"), 3);
                case "TGeoCombiTrans", "TGeoGenTrans" -> {
                    t = arr(m.get("fTranslation"), 3);
                    final RObject rot = m.object("fRotation");
                    if (rot != null) {
                        r = arr(rot.get("fRotationMatrix"), 9);
                    }
                    if (m.has("fScale")) {
                        scale = arr(m.get("fScale"), 3);
                    }
                }
                case "TGeoHMatrix" -> {
                    t = arr(m.get("fTranslation"), 3);
                    r = arr(m.get("fRotationMatrix"), 9);
                    scale = arr(m.get("fScale"), 3);
                }
                default -> warn("matrix", what + ": transformation " + m.className + " is not known (identity used)");
            }
            if (r == null || isZero(r)) {
                r = Mat.IDENTITY.r.clone();
            }
            if (t == null) {
                t = new double[3];
            }
            boolean scaled = false;
            if (scale != null && !(scale[0] == 1 && scale[1] == 1 && scale[2] == 1)) {
                final boolean mirror = Math.abs(Math.abs(scale[0]) - 1) < 1e-12 && Math.abs(Math.abs(scale[1]) - 1) < 1e-12
                    && Math.abs(Math.abs(scale[2]) - 1) < 1e-12;
                if (!mirror) {
                    warn("scale", what + ": a matrix that stretches (" + fmt(scale[0]) + ", " + fmt(scale[1]) + ", "
                        + fmt(scale[2]) + ") is not a placement Geant4 knows; the stretch is ignored");
                    scaled = true;
                }
                for (int i = 0; i < 3; i++) {
                    final double s = Math.signum(scale[i]) == 0 ? 1 : Math.signum(scale[i]);
                    r[i] *= s;
                    r[3 + i] *= s;
                    r[6 + i] *= s;
                }
            }
            return new Mat(r, t, scaled);
        }

        static boolean isZero(double[] a) {
            for (double v : a) {
                if (v != 0) {
                    return false;
                }
            }
            return true;
        }

        static double[] arr(Object o, int n) {
            final double[] out = new double[n];
            if (o instanceof double[] d) {
                System.arraycopy(d, 0, out, 0, Math.min(n, d.length));
            } else if (o instanceof float[] f) {
                for (int i = 0; i < Math.min(n, f.length); i++) {
                    out[i] = f[i];
                }
            }
            return out;
        }

        static double[] arr(Object o) {
            if (o instanceof double[] d) {
                return d;
            }
            if (o instanceof float[] f) {
                final double[] out = new double[f.length];
                for (int i = 0; i < f.length; i++) {
                    out[i] = f[i];
                }
                return out;
            }
            return new double[0];
        }

        // --------------------------------------------------------------
        // Materials
        // --------------------------------------------------------------

        /** The editor's name for a TGeo material: NIST's when it is one, defined from its elements otherwise. */
        String material(RObject mat) {
            if (mat == null) {
                return "G4_Galactic";
            }
            final String known = materials.get(mat);
            if (known != null) {
                return known;
            }
            final String raw = mat.string("fName").trim();
            final String result = defineMaterial(mat, raw);
            materials.put(mat, result);
            return result;
        }

        private String defineMaterial(RObject mat, String raw) {
            if (nist.material(raw) != null) {
                return raw;
            }
            final double density = mat.real("fDensity");
            final boolean mixture = mat.className.equals("TGeoMixture");
            if (density < 1e-10 || (!mixture && mat.real("fZ") < 0.5)) {
                recognise("vacuum material(s) read as G4_Galactic", raw);
                return "G4_Galactic";
            }
            String name = clean(raw, "Material");
            while (g.materials().isDefined(name) || nist.material(name) != null) {
                name = name + "_";
            }
            final Material m = Material.define(name, density);
            final int state = mat.integer("fState");
            m.setState(switch (state) {
                case 1 -> Material.State.SOLID;
                case 2 -> Material.State.LIQUID;
                case 3 -> Material.State.GAS;
                // undefined: what G4Material decides, by its 10 mg/cm3 gas threshold
                default -> density > 0.01 ? Material.State.SOLID : Material.State.GAS;
            });
            if (mat.real("fTemperature") > 0) {
                m.setTemperature(mat.real("fTemperature"));
            }
            if (mat.real("fPressure") > 0) {
                m.setPressure(mat.real("fPressure") / ATMOSPHERE);
            }
            if (mixture) {
                final int n = mat.integer("fNelements");
                final double[] z = arr(mat.get("fZmixture"));
                final double[] a = arr(mat.get("fAmixture"));
                final double[] w = arr(mat.get("fWeights"));
                final int[] atoms = mat.ints("fNatoms");
                final List<Object> elems = mat.list("fElements");
                boolean byAtoms = atoms.length == n && n > 0;
                for (int i = 0; i < n && byAtoms; i++) {
                    byAtoms = atoms[i] > 0;
                }
                for (int i = 0; i < n && i < z.length && i < a.length; i++) {
                    final RObject el = i < elems.size() && elems.get(i) instanceof RObject r ? r : null;
                    final String ref = element(z[i], a[i], el);
                    if (ref == null) {
                        continue;
                    }
                    if (byAtoms) {
                        m.addPart(ref, atoms[i], true);
                    } else if (i < w.length && w[i] > 0) {
                        m.addPart(ref, w[i], false);
                    }
                }
            } else {
                final String ref = element(mat.real("fZ"), mat.real("fA"), mat.object("fElement"));
                if (ref != null) {
                    m.addPart(ref, 1, true);
                }
            }
            try {
                g.materials().define(m);
            } catch (GeometryException e) {
                warn("material", "material " + raw + " could not be defined (" + e.getMessage() + "): G4_AIR used");
                g.materials().remove(name);
                return "G4_AIR";
            }
            return name;
        }

        /** An element by Z and molar mass: NIST's when the mass is NIST's, a defined one (enriched, say) otherwise. */
        String element(double zValue, double a, RObject el) {
            final int z = (int) Math.round(zValue);
            if (z < 1) {
                return null;
            }
            final Element std = nist.element(z);
            if (std != null && Math.abs(std.a - a) <= 2e-3 * std.a) {
                return std.symbol;
            }
            final String key = z + ":" + a;
            final String known = customElements.get(key);
            if (known != null) {
                return known;
            }
            String base = el != null ? clean(el.string("fName"), "") : "";
            if (base.isEmpty()) {
                base = (std != null ? std.symbol : "Z" + z) + "_A" + Math.round(a);
            }
            String name = base;
            while (g.materials().element(name) != null) {
                name = name + "_";
            }
            g.materials().defineElement(new Element(name, std != null ? std.symbol : name, z, a, a, false));
            customElements.put(key, name);
            return name;
        }

        // --------------------------------------------------------------
        // Shapes
        // --------------------------------------------------------------

        /** The solid of a shape, made once; an unnamed shape is named after what uses it (hint). */
        Solid solid(RObject shape, String hint) {
            if (shape == null) {
                final Solid s = Solids.create("box", unique("Missing"));
                s.set("x", "10*mm");
                s.set("y", "10*mm");
                s.set("z", "10*mm");
                g.addSolid(s);
                warn("shape", "a volume without a shape: a 1 cm box stands in for it");
                return s;
            }
            final Solid known = solids.get(shape);
            if (known != null) {
                return known;
            }
            final Solid s = convert(shape, hint);
            solids.put(shape, s);
            g.addSolid(s);
            shapeKinds.merge(shape.className.replaceFirst("^TGeo", ""), 1, Integer::sum);
            return s;
        }

        String solidName(RObject shape, String fallback) {
            final String n = shape.string("fName");
            return unique(n.isBlank() ? fallback : n, "_shape");
        }

        Solid convert(RObject sh, String hint) {
            final String cls = sh.className;
            final String name = solidName(sh, hint == null ? cls.replaceFirst("^TGeo", "") + "Shape" : hint + "_shape");
            switch (cls) {
                case "TGeoBBox": {
                    final Solid s = Solids.create("box", name);
                    s.set("x", mm(2 * sh.real("fDX")));
                    s.set("y", mm(2 * sh.real("fDY")));
                    s.set("z", mm(2 * sh.real("fDZ")));
                    final double[] o = arr(sh.get("fOrigin"), 3);
                    if (o[0] != 0 || o[1] != 0 || o[2] != 0) {
                        final RObject master = geo.object("fMasterVolume");
                        if (master != null && master.object("fShape") == sh) {
                            // the world has no placement to carry the offset: it grows around the origin instead
                            s.set("x", mm(2 * (sh.real("fDX") + Math.abs(o[0]))));
                            s.set("y", mm(2 * (sh.real("fDY") + Math.abs(o[1]))));
                            s.set("z", mm(2 * (sh.real("fDZ") + Math.abs(o[2]))));
                            recognise("world box(es) off the origin, grown to be centered on it", name);
                        } else {
                            shapeFrames.put(sh, Mat.translation(o[0], o[1], o[2]));
                        }
                    }
                    return s;
                }
                case "TGeoTube":
                    return tube(name, sh, 0, 360);
                case "TGeoTubeSeg":
                    return tube(name, sh, sh.real("fPhi1"), sh.real("fPhi2") - sh.real("fPhi1"));
                case "TGeoCtub": {
                    final Solid s = Solids.create("cutTube", name);
                    s.set("rmin", mm(sh.real("fRmin")));
                    s.set("rmax", mm(sh.real("fRmax")));
                    s.set("z", mm(2 * sh.real("fDz")));
                    s.set("startphi", deg(sh.real("fPhi1")));
                    s.set("deltaphi", deg(sh.real("fPhi2") - sh.real("fPhi1")));
                    final double[] lo = arr(sh.get("fNlow"), 3), hi = arr(sh.get("fNhigh"), 3);
                    s.set("lowX", fmt(lo[0]));
                    s.set("lowY", fmt(lo[1]));
                    s.set("lowZ", fmt(lo[2]));
                    s.set("highX", fmt(hi[0]));
                    s.set("highY", fmt(hi[1]));
                    s.set("highZ", fmt(hi[2]));
                    return s;
                }
                case "TGeoEltu": {
                    final Solid s = Solids.create("eltube", name);
                    s.set("dx", mm(sh.real("fRmin")));
                    s.set("dy", mm(sh.real("fRmax")));
                    s.set("dz", mm(sh.real("fDz")));
                    return s;
                }
                case "TGeoHype": {
                    final Solid s = Solids.create("hype", name);
                    s.set("rmin", mm(sh.real("fRmin")));
                    s.set("rmax", mm(sh.real("fRmax")));
                    s.set("inst", deg(sh.real("fStIn")));
                    s.set("outst", deg(sh.real("fStOut")));
                    s.set("z", mm(2 * sh.real("fDz")));
                    return s;
                }
                case "TGeoCone":
                    return cone(name, sh, 0, 360);
                case "TGeoConeSeg":
                    return cone(name, sh, sh.real("fPhi1"), sh.real("fPhi2") - sh.real("fPhi1"));
                case "TGeoSphere":
                    return sphere(name, sh);
                case "TGeoTorus": {
                    final Solid s = Solids.create("torus", name);
                    s.set("rmin", mm(sh.real("fRmin")));
                    s.set("rmax", mm(sh.real("fRmax")));
                    s.set("rtor", mm(sh.real("fR")));
                    s.set("startphi", deg(sh.real("fPhi1")));
                    s.set("deltaphi", deg(sh.real("fDphi")));
                    return s;
                }
                case "TGeoTrd1", "TGeoTrd2": {
                    final Solid s = Solids.create("trd", name);
                    final boolean one = cls.equals("TGeoTrd1");
                    s.set("x1", mm(2 * sh.real("fDx1")));
                    s.set("x2", mm(2 * sh.real("fDx2")));
                    s.set("y1", mm(2 * sh.real(one ? "fDy" : "fDy1")));
                    s.set("y2", mm(2 * sh.real(one ? "fDy" : "fDy2")));
                    s.set("z", mm(2 * sh.real("fDz")));
                    return s;
                }
                case "TGeoPara": {
                    final Solid s = Solids.create("para", name);
                    s.set("x", mm(2 * sh.real("fX")));
                    s.set("y", mm(2 * sh.real("fY")));
                    s.set("z", mm(2 * sh.real("fZ")));
                    s.set("alpha", deg(sh.real("fAlpha")));
                    s.set("theta", deg(sh.real("fTheta")));
                    s.set("phi", deg(sh.real("fPhi")));
                    return s;
                }
                case "TGeoTrap":
                    return trap(name, sh);
                case "TGeoGtra":
                    recognise("twisted trapezoid(s), TGeoGtra, read as G4GenericTrap", name);
                    return arb8(name, sh);
                case "TGeoArb8":
                    return arb8(name, sh);
                case "TGeoPcon", "TGeoPgon":
                    return pcon(name, sh, cls.equals("TGeoPgon"));
                case "TGeoXtru":
                    return xtru(name, sh);
                case "TGeoParaboloid": {
                    final Solid s = Solids.create("paraboloid", name);
                    s.set("rlo", mm(sh.real("fRlo")));
                    s.set("rhi", mm(sh.real("fRhi")));
                    s.set("dz", mm(sh.real("fDz")));
                    return s;
                }
                case "TGeoScaledShape":
                    return scaled(name, sh);
                case "TGeoCompositeShape":
                    return composite(name, sh);
                case "TGeoTessellated":
                    return tessellated(name, sh);
                case "TGeoHalfSpace":
                    return halfSpace(name, sh);
                default:
                    return boundingBox(name, sh, "shape " + cls + " has no Geant4 counterpart here");
            }
        }

        Solid boundingBox(String name, RObject sh, String why) {
            final Solid s = Solids.create("box", name);
            s.set("x", mm(Math.max(1e-4, 2 * sh.real("fDX"))));
            s.set("y", mm(Math.max(1e-4, 2 * sh.real("fDY"))));
            s.set("z", mm(Math.max(1e-4, 2 * sh.real("fDZ"))));
            final double[] o = arr(sh.get("fOrigin"), 3);
            if (o[0] != 0 || o[1] != 0 || o[2] != 0) {
                shapeFrames.put(sh, Mat.translation(o[0], o[1], o[2]));
            }
            warn("unsupported shape", why + ": " + sh.string("fName") + " is replaced by its bounding box");
            return s;
        }

        Solid tube(String name, RObject sh, double phi1, double dphi) {
            final Solid s = Solids.create("tube", name);
            s.set("rmin", mm(sh.real("fRmin")));
            s.set("rmax", mm(sh.real("fRmax")));
            s.set("z", mm(2 * sh.real("fDz")));
            s.set("startphi", deg(phi1));
            s.set("deltaphi", deg(dphi));
            return s;
        }

        Solid cone(String name, RObject sh, double phi1, double dphi) {
            final Solid s = Solids.create("cone", name);
            s.set("rmin1", mm(sh.real("fRmin1")));
            s.set("rmax1", mm(sh.real("fRmax1")));
            s.set("rmin2", mm(sh.real("fRmin2")));
            s.set("rmax2", mm(sh.real("fRmax2")));
            s.set("z", mm(2 * sh.real("fDz")));
            s.set("startphi", deg(phi1));
            s.set("deltaphi", deg(dphi));
            return s;
        }

        Solid sphere(String name, RObject sh) {
            final double t1 = sh.real("fTheta1"), t2 = sh.real("fTheta2");
            final double p1 = sh.real("fPhi1"), p2 = sh.real("fPhi2");
            if (sh.real("fRmin") <= 0 && t1 <= 0 && t2 >= 180 && p2 - p1 >= 360) {
                final Solid s = Solids.create("orb", name);
                s.set("r", mm(sh.real("fRmax")));
                return s;
            }
            final Solid s = Solids.create("sphere", name);
            s.set("rmin", mm(sh.real("fRmin")));
            s.set("rmax", mm(sh.real("fRmax")));
            s.set("startphi", deg(p1));
            s.set("deltaphi", deg(p2 - p1));
            s.set("starttheta", deg(t1));
            s.set("deltatheta", deg(t2 - t1));
            return s;
        }

        /** TGeoTrap as G4Trap, its parameters being G4Trap's half lengths; a G4GenericTrap if G4Trap refuses it. */
        Solid trap(String name, RObject sh) {
            final Solid s = Solids.create("trap", name);
            s.set("z", mm(2 * sh.real("fDz")));
            s.set("theta", deg(sh.real("fTheta")));
            s.set("phi", deg(sh.real("fPhi")));
            s.set("y1", mm(2 * sh.real("fH1")));
            s.set("x1", mm(2 * sh.real("fBl1")));
            s.set("x2", mm(2 * sh.real("fTl1")));
            s.set("alpha1", deg(sh.real("fAlpha1")));
            s.set("y2", mm(2 * sh.real("fH2")));
            s.set("x3", mm(2 * sh.real("fBl2")));
            s.set("x4", mm(2 * sh.real("fTl2")));
            s.set("alpha2", deg(sh.real("fAlpha2")));
            try {
                s.evaluate(null);
                return s;
            } catch (GeometryException notPlanar) {
                // TGeoTrap is an arb8 that accepts what G4Trap refuses (faces a little off plane)
                recognise("trapezoid(s) G4Trap refuses, read as G4GenericTrap", name);
                return arb8(name, sh);
            }
        }

        Solid arb8(String name, RObject sh) {
            final GenericTrap s = new GenericTrap(name);
            final double[] xy = arr(sh.get("fXY"), 16);
            final double[] mmxy = new double[16];
            for (int i = 0; i < 16; i++) {
                mmxy[i] = xy[i] * 10;
            }
            s.set("dz", mm(sh.real("fDz")));
            for (int i = 0; i < 8; i++) {
                s.set("v" + (i + 1) + "x", fmt(mmxy[i * 2]) + "*mm");
                s.set("v" + (i + 1) + "y", fmt(mmxy[i * 2 + 1]) + "*mm");
            }
            return s;
        }

        Solid pcon(String name, RObject sh, boolean pgon) {
            final Solid s = Solids.create(pgon ? "polyhedra" : "polycone", name);
            s.set("startphi", deg(sh.real("fPhi1")));
            s.set("deltaphi", deg(sh.real("fDphi")));
            if (pgon) {
                s.set("numsides", String.valueOf(sh.integer("fNedges")));
            }
            final double[] rmin = arr(sh.get("fRmin")), rmax = arr(sh.get("fRmax")), z = arr(sh.get("fZ"));
            final Solid.Table t = s.table("zplane");
            t.clear();
            for (int i = 0; i < sh.integer("fNz") && i < z.length; i++) {
                t.add(mm(rmin[i]), mm(rmax[i]), mm(z[i]));
            }
            return s;
        }

        Solid xtru(String name, RObject sh) {
            final Solid s = Solids.create("xtru", name);
            final double[] x = arr(sh.get("fX")), y = arr(sh.get("fY"));
            final double[] z = arr(sh.get("fZ")), sc = arr(sh.get("fScale")), x0 = arr(sh.get("fX0")), y0 = arr(sh.get("fY0"));
            final Solid.Table v = s.table("twoDimVertex"), sec = s.table("section");
            v.clear();
            sec.clear();
            for (int i = 0; i < sh.integer("fNvert") && i < x.length; i++) {
                v.add(mm(x[i]), mm(y[i]));
            }
            for (int i = 0; i < sh.integer("fNz") && i < z.length; i++) {
                sec.add(mm(z[i]), mm(x0[i]), mm(y0[i]), fmt(sc[i]));
            }
            return s;
        }

        /** TGeoScaledShape: G4ScaledSolid, or a G4Ellipsoid when it is ROOT's way of making one. */
        Solid scaled(String name, RObject sh) {
            final RObject base = sh.object("fShape");
            final RObject scale = sh.object("fScale");
            final double[] f = scale == null ? new double[] {1, 1, 1} : arr(scale.get("fScale"), 3);
            if (base != null && base.className.equals("TGeoSphere") && base.real("fRmin") <= 0
                && base.real("fTheta1") <= 0 && base.real("fTheta2") >= 180
                && base.real("fPhi2") - base.real("fPhi1") >= 360 && f[0] > 0 && f[1] > 0 && f[2] > 0) {
                final double r = base.real("fRmax");
                final Solid s = Solids.create("ellipsoid", name);
                s.set("ax", mm(r * f[0]));
                s.set("by", mm(r * f[1]));
                s.set("cz", mm(r * f[2]));
                s.set("zcut1", "0*mm");
                s.set("zcut2", "0*mm");
                recognise("scaled sphere(s) read as G4Ellipsoid", name);
                return s;
            }
            final ScaledSolid s = new ScaledSolid(name, solid(base, name + "_base"));
            s.set("sx", fmt(f[0]));
            s.set("sy", fmt(f[1]));
            s.set("sz", fmt(f[2]));
            return s;
        }

        final Map<Solid, Solid> mirrors = new IdentityHashMap<>();

        /** A solid mirrored through its z = 0 plane: a G4ScaledSolid of scale (1, 1, -1), made once. */
        Solid mirrored(Solid s) {
            return mirrors.computeIfAbsent(s, k -> {
                final ScaledSolid m = new ScaledSolid(unique(k.name() + "_mirror"), k);
                m.set("sz", "-1");
                g.addSolid(m);
                return m;
            });
        }

        /** TGeoCompositeShape: the tree of TGeoBoolNode as G4 booleans, each matrix as the operand's placement. */
        Solid composite(String name, RObject sh) {
            final RObject node = sh.object("fNode");
            if (node == null) {
                return boundingBox(name, sh, "composite shape without its boolean node");
            }
            final BooleanSolid.Op op = switch (node.className) {
                case "TGeoUnion" -> BooleanSolid.Op.UNION;
                case "TGeoSubtraction" -> BooleanSolid.Op.SUBTRACTION;
                case "TGeoIntersection" -> BooleanSolid.Op.INTERSECTION;
                default -> null;
            };
            if (op == null) {
                return boundingBox(name, sh, "boolean " + node.className + " is not known");
            }
            final RObject left = node.object("fLeft"), right = node.object("fRight");
            if (left == null || right == null) {
                return boundingBox(name, sh, "a boolean operand is missing");
            }
            Solid a = solid(left, name + "_a");
            Solid b = solid(right, name + "_b");
            Mat lm = matrix(node.object("fLeftMat"), "boolean " + sh.string("fName"));
            Mat rm = matrix(node.object("fRightMat"), "boolean " + sh.string("fName"));
            if (shapeFrames.containsKey(left)) {
                lm = lm.times(shapeFrames.get(left));
            }
            if (shapeFrames.containsKey(right)) {
                rm = rm.times(shapeFrames.get(right));
            }
            // an operand placed mirrored: the operand mirrored through z = 0 (a G4ScaledSolid of scale -1), then turned
            if (lm.reflected()) {
                a = mirrored(a);
                lm = lm.unreflected();
            }
            if (rm.reflected()) {
                b = mirrored(b);
                rm = rm.unreflected();
            }
            final BooleanSolid s = new BooleanSolid(name, op, a, b);
            // R_gdml = the right matrix itself (G4GDMLReadSolids: G4Transform3D(R_gdml, position))
            final double[] ra = Xform.gdmlAngles(rm.r);
            s.set("x", mm(rm.t[0]));
            s.set("y", mm(rm.t[1]));
            s.set("z", mm(rm.t[2]));
            s.set("rx", degOf(ra[0]));
            s.set("ry", degOf(ra[1]));
            s.set("rz", degOf(ra[2]));
            final double[] la = Xform.gdmlAngles(lm.r);
            s.set("fx", mm(lm.t[0]));
            s.set("fy", mm(lm.t[1]));
            s.set("fz", mm(lm.t[2]));
            s.set("frx", degOf(la[0]));
            s.set("fry", degOf(la[1]));
            s.set("frz", degOf(la[2]));
            return s;
        }

        Solid tessellated(String name, RObject sh) {
            final Tessellated s = new Tessellated(name);
            final List<double[]> vertices = new ArrayList<>();
            for (Object o : sh.list("fVertices")) {
                if (o instanceof RObject v) {
                    final double[] p = arr(v.get("fVec"), 3);
                    vertices.add(new double[] {p[0] * 10, p[1] * 10, p[2] * 10});
                }
            }
            final List<int[]> facets = new ArrayList<>();
            for (Object o : sh.list("fFacets")) {
                if (o instanceof RObject f) {
                    final int[] iv = f.ints("fIvert");
                    final int nv = f.integer("fNvert");
                    facets.add(nv == 4 && iv.length >= 4 ? new int[] {iv[0], iv[1], iv[2], iv[3]}
                        : new int[] {iv[0], iv[1], iv[2]});
                }
            }
            s.setSurface(vertices, facets);
            return s;
        }

        /**
         * TGeoHalfSpace has no Geant4 counterpart: a box ten metres deep and
         * wide stands in for it, its face on the plane, which is the same
         * thing inside any detector that fits in it.
         */
        Solid halfSpace(String name, RObject sh) {
            final double[] p = arr(sh.get("fP"), 3), nrm = arr(sh.get("fN"), 3);
            final Vec3 n = new Vec3(nrm[0], nrm[1], nrm[2]).unit();
            final double half = 1000;   // cm
            final Solid s = Solids.create("box", name);
            s.set("x", mm(2 * half));
            s.set("y", mm(2 * half));
            s.set("z", mm(2 * half));
            // the box's +z face on the plane: its centre half a box behind, its z axis along the normal
            final Vec3 u = n.orthogonal().unit(), v = n.cross(u);
            final double[] r = {u.x(), v.x(), n.x(), u.y(), v.y(), n.y(), u.z(), v.z(), n.z()};
            shapeFrames.put(sh, new Mat(r, new double[] {p[0] - n.x() * half, p[1] - n.y() * half, p[2] - n.z() * half},
                false));
            warn("half space", "half space " + sh.string("fName") + " is replaced by a 20 m box with a face on its plane");
            return s;
        }
    }

    // ------------------------------------------------------------------
    // Overlapping placements
    // ------------------------------------------------------------------

    /**
     * ROOT's overlapping placements (TGeoVolume::AddNodeOverlap, GEANT3's
     * MANY) made disjoint, as Geant4 wants every geometry, each part going
     * where ROOT's navigator sends a point. Among siblings that hold a point,
     * TGeoNavigator::SearchNode takes a placed-without-overlap (ONLY) one at
     * once, and FindInCluster searches the overlapping ones in the order they
     * were placed: the first whose search ends in an ONLY placement wins;
     * failing that, the one whose search goes deepest, the first placed of
     * those. So every sibling brings "strong" parts (all of it when ONLY;
     * when overlapping, where its search ends in an ONLY daughter), which win
     * in the order placed; then its overlapping daughters, which win over the
     * bodies; then its body, the first placed body winning. Each placement
     * loses, by boolean subtraction, what wins over it -- G3toG4's way with
     * MANY -- its daughters likewise. A placement left with nothing is
     * removed.
     */
    private static final class ManyResolver {
        private static final int SAMPLES = 1500;
        private final Reader r;
        private int cut;
        private int removed;

        ManyResolver(Reader r) {
            this.r = r;
        }

        /** A solid that takes its region away, placed in the frame of what it cuts. */
        private record Cutter(Solid solid, Xform t, String name) {
            BBox box() {
                return t.apply(solid.extent());
            }

            /** The same cutter in another frame, given the inverse of that frame's placement. */
            Cutter in(Xform frameInverse) {
                return new Cutter(solid, frameInverse.compose(t), name);
            }
        }

        void resolve() {
            final int overlapping = r.many.size();
            // the deepest mothers first: a volume cut later copies daughters already made disjoint
            final Map<LogicalVolume, Integer> heights = new IdentityHashMap<>();
            final List<LogicalVolume> order = new ArrayList<>(r.g.volumes());
            for (LogicalVolume lv : order) {
                height(lv, heights);
            }
            order.sort(java.util.Comparator.comparingInt(heights::get));
            for (LogicalVolume mother : order) {
                resolveIn(mother);
            }
            r.notes.add(String.format(Locale.ROOT, "%d overlapping placement%s (ROOT's AddNodeOverlap, GEANT3's MANY) made"
                    + " disjoint as ROOT's navigator shares them out: %d placements cut by what wins over them, %d"
                    + " removed (nothing of them left)", overlapping, overlapping > 1 ? "s" : "", cut, removed));
        }

        private static int height(LogicalVolume lv, Map<LogicalVolume, Integer> memo) {
            final Integer known = memo.get(lv);
            if (known != null) {
                return known;
            }
            memo.put(lv, 0);
            int h = 0;
            for (PhysicalVolume pv : lv.daughters) {
                h = Math.max(h, 1 + height(pv.logical, memo));
            }
            memo.put(lv, h);
            return h;
        }

        private static boolean usable(Solid s) {
            return s != null && s.error() == null;
        }

        /** A sibling's parts of one kind, in the mother's frame, with their boxes. */
        private static final class Parts {
            final List<Cutter> list = new ArrayList<>();
            BBox[] boxes;

            void close() {
                boxes = new BBox[list.size()];
                for (int k = 0; k < boxes.length; k++) {
                    boxes[k] = list.get(k).box();
                }
            }
        }

        @SuppressWarnings("unchecked")
        private void resolveIn(LogicalVolume mother) {
            final List<PhysicalVolume> ds = mother.daughters;
            boolean any = false;
            for (PhysicalVolume pv : ds) {
                any |= r.many.contains(pv);
            }
            if (!any) {
                return;
            }
            final int n = ds.size();
            final boolean[] many = new boolean[n];
            // what each sibling brings, in the mother's frame, as its subtree stands (made disjoint
            // already, the deepest first): its strong parts, its overlapping daughters, its body
            final Parts[] strong = new Parts[n], weak = new Parts[n];
            final Cutter[] body = new Cutter[n];
            final BBox[] bodyBox = new BBox[n];
            for (int i = 0; i < n; i++) {
                final PhysicalVolume pv = ds.get(i);
                many[i] = r.many.contains(pv);
                strong[i] = new Parts();
                weak[i] = new Parts();
                final Solid s = pv.logical.solid;
                if (usable(s)) {
                    if (!many[i]) {
                        for (int c = 0; c < pv.copies(); c++) {
                            strong[i].list.add(new Cutter(s, pv.copyTransform(c), pv.name));
                        }
                    } else {
                        body[i] = new Cutter(s, pv.transform, pv.name);
                        bodyBox[i] = body[i].box();
                        strongParts(pv.logical, pv.transform, strong[i].list, 0);
                        for (PhysicalVolume d : pv.logical.daughters) {
                            if (r.many.contains(d) && usable(d.logical.solid)) {
                                weak[i].list.add(new Cutter(within(original(d.logical).solid, d.transform, s), pv.transform.compose(d.transform), d.name));
                            }
                        }
                    }
                }
                strong[i].close();
                weak[i].close();
            }
            final List<PhysicalVolume> gone = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final PhysicalVolume pv = ds.get(i);
                if (pv.replica != null || !usable(pv.logical.solid)) {
                    continue;
                }
                final Xform inv = pv.transform.inverse();
                final Solid self = pv.logical.solid;
                final BBox selfBox = pv.transform.apply(self.extent());
                // what wins over some of this placement, in its frame, less the parts of it that win over that
                final List<Cutter> cutters = new ArrayList<>();
                List<Cutter> daughterCutters = cutters;
                if (!many[i]) {
                    // ONLY: all of it is strong; only the strong parts of overlapping siblings placed before win
                    // (two ONLY siblings that overlap do in ROOT too: left as they are)
                    for (int j = 0; j < i; j++) {
                        if (many[j]) {
                            touching(strong[j], pv, selfBox, cutters, inv);
                        }
                    }
                    if (cutters.isEmpty()) {
                        continue;
                    }
                } else {
                    final List<Cutter> ownStrong = new ArrayList<>(), ownAll = new ArrayList<>();
                    for (Cutter c : strong[i].list) {
                        ownStrong.add(c.in(inv));
                    }
                    ownAll.addAll(ownStrong);
                    for (Cutter c : weak[i].list) {
                        ownAll.add(c.in(inv));
                    }
                    final int strongId = setId(ownStrong), allId = setId(ownAll);
                    // what can win over its daughters: not what is less all of them
                    daughterCutters = new ArrayList<>();
                    for (int j = 0; j < n; j++) {
                        if (j == i) {
                            continue;
                        }
                        final List<Cutter> found = new ArrayList<>();
                        // strong parts: all win over this one's body and overlapping daughters; over its
                        // strong parts, only those of siblings placed before
                        touching(strong[j], pv, selfBox, found, inv);
                        for (Cutter c : found) {
                            final Cutter w = j < i ? c : less(c, ownStrong, strongId);
                            cutters.add(w);
                            daughterCutters.add(w);
                        }
                        found.clear();
                        // overlapping daughters: win over the body, and over the overlapping daughters
                        // of a sibling placed after
                        touching(weak[j], pv, selfBox, found, inv);
                        for (Cutter c : found) {
                            final Cutter w = less(c, j < i ? ownStrong : ownAll, j < i ? strongId : allId);
                            cutters.add(w);
                            if (j < i) {
                                daughterCutters.add(w);
                            }
                        }
                        // bodies: win over the body of a sibling placed after
                        if (j < i && body[j] != null && bodyBox[j].overlaps(selfBox)
                            && touch(self, pv.transform, body[j].solid(), body[j].t())) {
                            cutters.add(less(body[j].in(inv), ownAll, allId));
                        }
                    }
                    if (cutters.isEmpty()) {
                        continue;
                    }
                }
                final LogicalVolume result = clipBody(pv.logical, cutters, daughterCutters);
                if (result == null) {
                    gone.add(pv);
                    removed++;
                } else if (result != pv.logical) {
                    pv.logical = result;
                    cut++;
                }
            }
            ds.removeAll(gone);
            mother.invalidate();
        }

        /** The parts that touch a placement (its box in the mother's frame given), into its frame. */
        private void touching(Parts parts, PhysicalVolume pv, BBox selfBox, List<Cutter> out, Xform inv) {
            for (int k = 0; k < parts.boxes.length; k++) {
                final Cutter c = parts.list.get(k);
                if (parts.boxes[k].overlaps(selfBox) && touch(pv.logical.solid, pv.transform, c.solid(), c.t())) {
                    out.add(c.in(inv));
                }
            }
        }

        /**
         * Where a search entering a volume (placed by t in the mother's frame)
         * ends in an ONLY placement: its ONLY daughters, and theirs down its
         * overlapping ones.
         */
        private void strongParts(LogicalVolume lv, Xform t, List<Cutter> out, int depth) {
            for (PhysicalVolume d : lv.daughters) {
                if (!usable(d.logical.solid)) {
                    continue;
                }
                final boolean overlapping = r.many.contains(d);
                if (overlapping && depth >= 3) {
                    continue;
                }
                for (int c = 0; c < d.copies(); c++) {
                    final Xform dt = d.copyTransform(c);
                    if (overlapping) {
                        strongParts(d.logical, t.compose(dt), out, depth + 1);
                    } else {
                        // ROOT looks for a point in a daughter only once it is in the mother:
                        // what of the daughter sticks out of it wins nothing
                        out.add(new Cutter(within(original(d.logical).solid, dt, original(lv).solid), t.compose(dt), d.name));
                    }
                }
            }
        }

        /** A cutter less the parts of it that some of the given ones (in the same frame) cover. */
        private Cutter less(Cutter c, List<Cutter> keep, int keepId) {
            final StringBuilder k = new StringBuilder(96).append(id(c.solid())).append('@');
            key(k, c.t());
            final String key = k.append('#').append(keepId).toString();
            Solid known = lessened.get(key);
            if (known == null) {
                known = less(c, keep).solid();
                lessened.put(key, known);
            }
            return known == c.solid() ? c : new Cutter(known, c.t(), c.name());
        }

        private Cutter less(Cutter c, List<Cutter> keep) {
            Solid cur = c.solid();
            boolean changed = false;
            final BBox box = c.box();
            final Xform inv = c.t().inverse();
            for (Cutter k : keep) {
                if (!k.box().overlaps(box) || !touch(c.solid(), c.t(), k.solid(), k.t())) {
                    continue;
                }
                final BooleanSolid b = subtraction(c.solid().name() + "_less", cur, inv.compose(k.t()), k.solid());
                if (b == null) {
                    return c;
                }
                cur = b;
                changed = true;
            }
            return changed ? new Cutter(cur, c.t(), c.name()) : c;
        }

        /** first − second, the second placed in the first's frame by t: a solid of the document. */
        private BooleanSolid subtraction(String name, Solid first, Xform t, Solid second) {
            return combined(BooleanSolid.Op.SUBTRACTION, name, first, t, second);
        }

        /**
         * A daughter's solid, placed by t in its mother's, less what of it
         * sticks out of the mother: the daughter itself when nothing does.
         */
        private Solid within(Solid daughter, Xform t, Solid mother) {
            final StringBuilder k = new StringBuilder(96).append(id(daughter)).append('<').append(id(mother)).append('|');
            key(k, t);
            final String key = k.toString();
            Solid known = inside.get(key);
            if (known == null) {
                known = daughter;
                if (sticksOut(daughter, t, mother)) {
                    final BooleanSolid b = combined(BooleanSolid.Op.INTERSECTION, daughter.name() + "_in", daughter, t.inverse(), mother);
                    if (b != null) {
                        known = b;
                    }
                }
                inside.put(key, known);
            }
            return known;
        }

        private final Map<String, Solid> inside = new HashMap<>();

        /** True when some of a solid, placed by t in another's frame, lies outside that other. */
        private static boolean sticksOut(Solid s, Xform t, Solid outer) {
            final BBox box = s.extent();
            if (box.isEmpty()) {
                return false;
            }
            final java.util.SplittableRandom rng = new java.util.SplittableRandom(0x0D7L);
            final double[] q = new double[3];
            final Xform inv = t.inverse();
            for (int k = 0; k < SAMPLES; k++) {
                final double x = box.minX() + rng.nextDouble() * (box.maxX() - box.minX());
                final double y = box.minY() + rng.nextDouble() * (box.maxY() - box.minY());
                final double z = box.minZ() + rng.nextDouble() * (box.maxZ() - box.minZ());
                if (s.inside(x, y, z) != Solid.INSIDE) {
                    continue;
                }
                inv.applyInverse(x, y, z, q);   // t applied: the point in the outer solid's frame
                if (outer.inside(q[0], q[1], q[2]) == Solid.OUTSIDE) {
                    return true;
                }
            }
            return false;
        }

        /** first (op) second, the second placed in the first's frame by t: a solid of the document. */
        private BooleanSolid combined(BooleanSolid.Op op, String name, Solid first, Xform t, Solid second) {
            if (t.isReflection()) {
                // a boolean places its operand by a rotation: a mirrored one goes in as a mirrored solid
                second = r.mirrored(second);
                t = t.compose(Xform.REFLECT_Z);
            }
            final BooleanSolid b = new BooleanSolid(r.unique(name, "_"), op, first, second);
            final Vec3 p = t.translation();
            // the second solid's placement in the first: G4Transform3D(R_gdml, position), R_gdml its rotation
            final double[] a = Xform.gdmlAngles(t.rotation());
            b.set("x", fmt(p.x()) + "*mm");
            b.set("y", fmt(p.y()) + "*mm");
            b.set("z", fmt(p.z()) + "*mm");
            b.set("rx", degOf(a[0]));
            b.set("ry", degOf(a[1]));
            b.set("rz", degOf(a[2]));
            try {
                b.evaluate(null);
            } catch (GeometryException e) {
                return null;
            }
            r.g.addSolid(b);
            return b;
        }

        // Symmetric detectors meet the same overlap again and again (a sector and its neighbour,
        // 18 times over): what is worked out once, for a solid and what cuts it as placed
        // relative to it, serves every copy -- and the cut volumes are shared, not made anew.
        private final Map<Object, Integer> ids = new IdentityHashMap<>();
        private final Map<String, Boolean> touches = new HashMap<>();
        private final Map<String, Solid> lessened = new HashMap<>();
        private final Map<String, LogicalVolume> clipped = new HashMap<>();
        private final Map<String, Integer> sets = new HashMap<>();

        // the volume a cut one was made from: a sibling's daughter cut by its own neighbours wins, as
        // ROOT has it, where the original does less what those neighbours (which cut too) take
        private final Map<LogicalVolume, LogicalVolume> origin = new IdentityHashMap<>();

        private LogicalVolume original(LogicalVolume lv) {
            return origin.getOrDefault(lv, lv);
        }

        private int id(Object o) {
            return ids.computeIfAbsent(o, k -> ids.size());
        }

        /** A transform as a key: 1 nm in the translation, 1e-9 in the rotation. */
        private static void key(StringBuilder b, Xform t) {
            for (double v : t.r) {
                b.append(Math.round(v * 1e9)).append(',');
            }
            b.append(Math.round(t.tx * 1e6)).append(',').append(Math.round(t.ty * 1e6)).append(',').append(Math.round(t.tz * 1e6));
        }

        private String key(List<Cutter> cutters) {
            final StringBuilder b = new StringBuilder(cutters.size() * 64);
            for (Cutter c : cutters) {
                b.append(id(c.solid())).append('@');
                key(b, c.t());
                b.append(';');
            }
            return b.toString();
        }

        /** A list of cutters as a small number, the same for the same solids placed the same way. */
        private int setId(List<Cutter> cutters) {
            return sets.computeIfAbsent(key(cutters), k -> sets.size());
        }

        /** True when two placed solids share some volume. */
        private boolean touch(Solid a, Xform ta, Solid b, Xform tb) {
            if (!usable(a) || !usable(b)) {
                return false;
            }
            final Xform rel = ta.inverse().compose(tb);     // b in a's frame
            final StringBuilder k = new StringBuilder(96).append(id(a)).append('|').append(id(b)).append('|');
            key(k, rel);
            return touches.computeIfAbsent(k.toString(), x -> touch(a, b, rel));
        }

        /** True when b, placed by rel in a's frame, shares some of a: points sampled where their boxes meet. */
        private static boolean touch(Solid a, Solid b, Xform rel) {
            final BBox box = a.extent().intersect(rel.apply(b.extent()));
            if (box.isEmpty()) {
                return false;
            }
            final java.util.SplittableRandom rng = new java.util.SplittableRandom(0x5EEDL);
            final double[] q = new double[3];
            for (int k = 0; k < SAMPLES; k++) {
                final double x = box.minX() + rng.nextDouble() * (box.maxX() - box.minX());
                final double y = box.minY() + rng.nextDouble() * (box.maxY() - box.minY());
                final double z = box.minZ() + rng.nextDouble() * (box.maxZ() - box.minZ());
                if (a.inside(x, y, z) != Solid.INSIDE) {
                    continue;
                }
                rel.applyInverse(x, y, z, q);
                if (b.inside(q[0], q[1], q[2]) == Solid.INSIDE) {
                    return true;
                }
            }
            return false;
        }

        /**
         * True when some of a solid is left outside every cutter: points drawn
         * inside the solid, each tested against the cutters until one holds it.
         */
        private static boolean occupied(Solid s, List<Cutter> cutters) {
            final BBox box = s.extent();
            if (box.isEmpty()) {
                return false;
            }
            final java.util.SplittableRandom rng = new java.util.SplittableRandom(0xC0FFEEL);
            final double[] q = new double[3];
            for (int k = 0; k < 20 * SAMPLES; k++) {
                final double x = box.minX() + rng.nextDouble() * (box.maxX() - box.minX());
                final double y = box.minY() + rng.nextDouble() * (box.maxY() - box.minY());
                final double z = box.minZ() + rng.nextDouble() * (box.maxZ() - box.minZ());
                if (s.inside(x, y, z) != Solid.INSIDE) {
                    continue;
                }
                boolean taken = false;
                for (Cutter c : cutters) {
                    c.t().applyInverse(x, y, z, q);
                    if (c.solid().inside(q[0], q[1], q[2]) != Solid.OUTSIDE) {
                        taken = true;
                        break;
                    }
                }
                if (!taken) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The volume less what the cutters take, its daughters likewise (null:
         * nothing left). The volume is copied: other placements of it may not
         * be cut, or not the same way.
         */
        private LogicalVolume clip(LogicalVolume lv, List<Cutter> cutters, int depth) {
            if (depth > 12 || !usable(lv.solid)) {
                return lv;
            }
            // the cutters of a placement were chosen because they touch it; a daughter's must be checked
            final List<Cutter> relevant = new ArrayList<>();
            final BBox own = lv.solid.extent();
            for (Cutter c : cutters) {
                if (depth == 0 || (c.box().overlaps(own) && touch(lv.solid, Xform.IDENTITY, c.solid(), c.t()))) {
                    relevant.add(c);
                }
            }
            if (relevant.isEmpty()) {
                return lv;
            }
            return clipBody(lv, relevant, relevant, depth);
        }

        private LogicalVolume clipBody(LogicalVolume lv, List<Cutter> bodyCutters, List<Cutter> daughterCutters) {
            return clipBody(lv, bodyCutters, daughterCutters, 0);
        }

        /** The body less one set of cutters, the daughters less another (the strong parts that win over them). */
        private LogicalVolume clipBody(LogicalVolume lv, List<Cutter> bodyCutters, List<Cutter> daughterCutters, int depth) {
            final String key = id(lv) + "/" + depth + "/" + setId(bodyCutters) + "/" + setId(daughterCutters);
            if (clipped.containsKey(key)) {
                return clipped.get(key);    // null: nothing left
            }
            final LogicalVolume n = cutNow(lv, bodyCutters, daughterCutters, depth);
            clipped.put(key, n);
            return n;
        }

        private LogicalVolume cutNow(LogicalVolume lv, List<Cutter> bodyCutters, List<Cutter> daughterCutters, int depth) {
            if (!occupied(lv.solid, bodyCutters)) {
                return null;
            }
            Solid cur = lv.solid;
            for (Cutter c : bodyCutters) {
                final BooleanSolid b = subtraction(lv.solid.name() + "_cut", cur, c.t(), c.solid());
                if (b == null) {
                    return lv;
                }
                cur = b;
            }
            final LogicalVolume n = new LogicalVolume(r.unique(lv.name + "_cut", "_v"), cur, lv.material);
            n.color = lv.color;
            n.visible = lv.visible;
            n.wireframe = lv.wireframe;
            n.sensitive = lv.sensitive;
            n.aux.putAll(lv.aux);
            r.g.addVolume(n);
            origin.put(n, original(lv));
            for (PhysicalVolume d : lv.daughters) {
                LogicalVolume dl = d.logical;
                if (d.replica == null && !daughterCutters.isEmpty()) {
                    final Xform dinv = d.transform.inverse();
                    final List<Cutter> inside = new ArrayList<>();
                    for (Cutter c : daughterCutters) {
                        inside.add(c.in(dinv));
                    }
                    dl = clip(d.logical, inside, depth + 1);
                    if (dl == null) {
                        continue;
                    }
                }
                final PhysicalVolume nd = new PhysicalVolume(r.unique(d.name), dl, n);
                if (r.many.contains(d)) {
                    r.many.add(nd);     // still overlapping, to the mothers resolved later
                }
                nd.copyNumber = d.copyNumber;
                nd.setPosition(d.x, d.y, d.z);
                nd.setRotation(d.rx, d.ry, d.rz);
                nd.reflected = d.reflected;
                // evaluated now: the mothers resolved later place this copy's daughters through it
                // before the geometry is rebuilt
                nd.vx = d.vx;
                nd.vy = d.vy;
                nd.vz = d.vz;
                nd.vrx = d.vrx;
                nd.vry = d.vry;
                nd.vrz = d.vrz;
                nd.transform = d.transform;
                if (d.replica != null) {
                    final PhysicalVolume.Replica rep = new PhysicalVolume.Replica();
                    rep.axis = d.replica.axis;
                    rep.number = d.replica.number;
                    rep.width = d.replica.width;
                    rep.offset = d.replica.offset;
                    rep.n = d.replica.n;
                    rep.w = d.replica.w;
                    rep.off = d.replica.off;
                    nd.replica = rep;
                }
                if (r.offsets.contains(d)) {
                    r.offsets.add(nd);
                }
                n.daughters.add(nd);
            }
            return n;
        }
    }
}

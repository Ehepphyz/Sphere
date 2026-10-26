package com.sphere.core.geant4;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * A Geant4 geometry document: defines, materials, solids, logical volumes and
 * the tree of placements under the world volume. It is what a GDML file
 * holds, and what G4GDMLParser turns into a G4VPhysicalVolume.
 */
public final class Geometry {

    /** Something wrong with the model, attached to the object it concerns. */
    public record Issue(Severity severity, String message, Object target) {
    }

    public enum Severity { ERROR, WARNING, INFO }

    /** What changed, so listeners redraw only what they must. */
    public enum Change { STRUCTURE, PROPERTIES, MATERIALS, DEFINES, ALL }

    public interface Listener {
        void geometryChanged(Geometry g, Change change);
    }

    /** One drawn copy of a logical volume: the placements leading to it, and its world transform. */
    public record Instance(int id, PhysicalVolume pv, int copy, LogicalVolume lv, Xform world, int depth,
                           Instance parent) {
        /** The placements from the world down to this copy. */
        public List<PhysicalVolume> path() {
            final List<PhysicalVolume> out = new ArrayList<>();
            for (Instance i = this; i != null && i.pv != null; i = i.parent) {
                out.add(0, i.pv);
            }
            return out;
        }

        public String pathName() {
            final StringBuilder b = new StringBuilder();
            for (PhysicalVolume p : path()) {
                b.append('/').append(p.name);
                if (p.isReplica()) {
                    b.append('[').append(copy).append(']');
                }
            }
            return b.length() == 0 ? "/" + lv.name : b.toString();
        }
    }

    final Map<String, Define> defines = new LinkedHashMap<>();
    final MaterialTable materials = new MaterialTable();
    final Map<String, Solid> solids = new LinkedHashMap<>();
    final Map<String, LogicalVolume> volumes = new LinkedHashMap<>();
    LogicalVolume world;
    String title = "detector";
    /** Free-form settings the editor keeps with the document (gun, run, view): GDML userinfo. */
    final Map<String, String> settings = new LinkedHashMap<>();

    /** A GDML matrix (a define): the tables a material's optical properties name, values as written. */
    public record Matrix(String name, int coldim, String values) {
    }

    final Map<String, Matrix> matrices = new LinkedHashMap<>();
    /**
     * Optical surfaces (opticalsurface among the solids) and what puts them on volumes (skinsurface,
     * bordersurface in the structure), kept as the file wrote them: the editor does not change them.
     */
    final List<String> surfaces = new ArrayList<>();
    final List<String> surfaceLinks = new ArrayList<>();

    private final List<Issue> issues = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile List<Instance> instances = List.of();
    private final Map<String, Double> values = new HashMap<>();
    private final Expr.Scope scope = values::get;
    private long revision;

    public Geometry() {
    }

    /** A new document: a 2 m air world, as most Geant4 examples start. */
    public static Geometry blank() {
        final Geometry g = new Geometry();
        final Solid w = Solids.create("box", "WorldBox");
        w.set("x", "2*m");
        w.set("y", "2*m");
        w.set("z", "2*m");
        g.solids.put(w.name(), w);
        final LogicalVolume lv = new LogicalVolume("World", w, "G4_AIR");
        lv.wireframe = true;
        lv.color = 0x40B0B8C8;
        g.volumes.put(lv.name, lv);
        g.world = lv;
        g.rebuild();
        return g;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public LogicalVolume world() {
        return world;
    }

    public String title() {
        return title;
    }

    public void setTitle(String t) {
        this.title = t;
    }

    public Map<String, String> settings() {
        return settings;
    }

    public Map<String, Matrix> matrices() {
        return matrices;
    }

    public MaterialTable materials() {
        return materials;
    }

    public Collection<Define> defines() {
        return defines.values();
    }

    public Define define(String name) {
        return defines.get(name);
    }

    public Collection<Solid> solids() {
        return solids.values();
    }

    public Solid solid(String name) {
        return solids.get(name);
    }

    public Collection<LogicalVolume> volumes() {
        return volumes.values();
    }

    public LogicalVolume volume(String name) {
        return volumes.get(name);
    }

    Map<String, LogicalVolume> volumesMap() {
        return volumes;
    }

    public List<Issue> issues() {
        return List.copyOf(issues);
    }

    public Expr.Scope scope() {
        return scope;
    }

    public long revision() {
        return revision;
    }

    /** Every drawn copy of every volume, world first; rebuilt by rebuild(). */
    public List<Instance> instances() {
        return instances;
    }

    /** Every placement of the document, in tree order. */
    public List<PhysicalVolume> placements() {
        final List<PhysicalVolume> out = new ArrayList<>();
        final Set<LogicalVolume> seen = new HashSet<>();
        final Deque<LogicalVolume> todo = new ArrayDeque<>();
        if (world != null) {
            todo.add(world);
        }
        while (!todo.isEmpty()) {
            final LogicalVolume lv = todo.poll();
            if (!seen.add(lv)) {
                continue;
            }
            for (PhysicalVolume pv : lv.daughters) {
                out.add(pv);
                todo.add(pv.logical);
            }
        }
        return out;
    }

    /** The placements of a logical volume. */
    public List<PhysicalVolume> placementsOf(LogicalVolume lv) {
        final List<PhysicalVolume> out = new ArrayList<>();
        for (LogicalVolume m : volumes.values()) {
            for (PhysicalVolume pv : m.daughters) {
                if (pv.logical == lv) {
                    out.add(pv);
                }
            }
        }
        return out;
    }

    public PhysicalVolume placement(String name) {
        for (LogicalVolume m : volumes.values()) {
            for (PhysicalVolume pv : m.daughters) {
                if (pv.name.equals(name)) {
                    return pv;
                }
            }
        }
        return null;
    }

    /** The logical volumes that use a solid, directly or through a boolean. */
    public List<LogicalVolume> users(Solid s) {
        final List<LogicalVolume> out = new ArrayList<>();
        for (LogicalVolume lv : volumes.values()) {
            if (lv.solid != null && uses(lv.solid, s)) {
                out.add(lv);
            }
        }
        return out;
    }

    private static boolean uses(Solid root, Solid s) {
        if (root == s) {
            return true;
        }
        for (Solid o : root.operands()) {
            if (uses(o, s)) {
                return true;
            }
        }
        return false;
    }

    /** Solids no volume and no boolean uses. */
    public List<Solid> unusedSolids() {
        final Set<Solid> used = new HashSet<>();
        for (LogicalVolume lv : volumes.values()) {
            collect(lv.solid, used);
        }
        final List<Solid> out = new ArrayList<>();
        for (Solid s : solids.values()) {
            if (!used.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static void collect(Solid s, Set<Solid> out) {
        if (s == null || !out.add(s)) {
            return;
        }
        for (Solid o : s.operands()) {
            collect(o, out);
        }
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void fire(Change c) {
        for (Listener l : listeners) {
            l.geometryChanged(this, c);
        }
    }

    // ------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------

    /**
     * Evaluates everything in dependency order: defines, then solids (operands
     * before the booleans using them), then placements; rebuilds the instance
     * list and the navigation indexes. Problems are kept in issues().
     */
    public synchronized void rebuild() {
        issues.clear();
        values.clear();
        evaluateDefines();
        final Set<Solid> done = new HashSet<>();
        for (Solid s : solids.values()) {
            evaluateSolid(s, done, new HashSet<>());
        }
        for (LogicalVolume lv : volumes.values()) {
            lv.invalidate();
            if (lv.solid == null) {
                issues.add(new Issue(Severity.ERROR, "volume " + lv.name + " has no solid", lv));
            }
            if (lv.material == null || lv.material.isBlank()) {
                issues.add(new Issue(Severity.ERROR, "volume " + lv.name + " has no material", lv));
            } else if (materials.material(lv.material) == null) {
                issues.add(new Issue(Severity.ERROR, "volume " + lv.name + ": unknown material " + lv.material, lv));
            }
            for (PhysicalVolume pv : lv.daughters) {
                try {
                    pv.evaluate(scope);
                } catch (GeometryException e) {
                    issues.add(new Issue(Severity.ERROR, e.getMessage(), pv));
                }
            }
        }
        for (String e : materials.resolveAll()) {
            issues.add(new Issue(Severity.ERROR, e, null));
        }
        if (world == null) {
            issues.add(new Issue(Severity.ERROR, "the document has no world volume", null));
        } else if (detectCycle()) {
            issues.add(new Issue(Severity.ERROR, "a volume is placed inside itself", null));
        }
        rebuildInstances();
        revision++;
    }

    private void evaluateDefines() {
        // Order by dependency, as GDML requires a define before its use
        final Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (Define d : defines.values()) {
            try {
                deps.put(d.name, Expr.parse(d.effectiveExpr()).variables());
            } catch (GeometryException e) {
                d.error = e.getMessage();
                deps.put(d.name, Set.of());
            }
        }
        final Set<String> state = new HashSet<>(), visiting = new HashSet<>();
        for (Define d : defines.values()) {
            visit(d.name, deps, state, visiting);
        }
    }

    private void visit(String name, Map<String, Set<String>> deps, Set<String> done, Set<String> visiting) {
        if (done.contains(name)) {
            return;
        }
        final Define d = defines.get(name);
        if (d == null) {
            return;
        }
        if (!visiting.add(name)) {
            d.error = "circular definition through " + name;
            issues.add(new Issue(Severity.ERROR, "define " + name + ": " + d.error, d));
            return;
        }
        for (String dep : deps.getOrDefault(name, Set.of())) {
            visit(dep, deps, done, visiting);
        }
        visiting.remove(name);
        done.add(name);
        try {
            d.value = Expr.eval(d.effectiveExpr(), scope);
            d.error = null;
            values.put(name, d.value);
        } catch (GeometryException e) {
            d.error = e.getMessage();
            issues.add(new Issue(Severity.ERROR, "define " + name + ": " + e.getMessage(), d));
        }
    }

    private void evaluateSolid(Solid s, Set<Solid> done, Set<Solid> visiting) {
        if (s == null || done.contains(s)) {
            return;
        }
        if (!visiting.add(s)) {
            issues.add(new Issue(Severity.ERROR, "solid " + s.name() + " contains itself", s));
            return;
        }
        for (Solid o : s.operands()) {
            evaluateSolid(o, done, visiting);
        }
        try {
            s.evaluate(scope);
        } catch (GeometryException e) {
            issues.add(new Issue(Severity.ERROR, e.getMessage(), s));
        }
        done.add(s);
    }

    private boolean detectCycle() {
        final Set<LogicalVolume> stack = new HashSet<>(), done = new HashSet<>();
        return cycle(world, stack, done);
    }

    private boolean cycle(LogicalVolume lv, Set<LogicalVolume> stack, Set<LogicalVolume> done) {
        if (stack.contains(lv)) {
            return true;
        }
        if (done.contains(lv)) {
            return false;
        }
        stack.add(lv);
        for (PhysicalVolume pv : lv.daughters) {
            if (cycle(pv.logical, stack, done)) {
                return true;
            }
        }
        stack.remove(lv);
        done.add(lv);
        return false;
    }

    private static final int MAX_INSTANCES = 200_000;

    private void rebuildInstances() {
        final List<Instance> out = new ArrayList<>();
        if (world != null && issues.stream().noneMatch(i -> i.message().contains("inside itself"))) {
            final Instance root = new Instance(0, null, 0, world, Xform.IDENTITY, 0, null);
            out.add(root);
            expand(root, out);
        }
        instances = List.copyOf(out);
    }

    private void expand(Instance parent, List<Instance> out) {
        for (PhysicalVolume pv : parent.lv().daughters) {
            for (int c = 0; c < pv.copies(); c++) {
                if (out.size() >= MAX_INSTANCES) {
                    return;
                }
                final Instance i = new Instance(out.size(), pv, c, pv.logical,
                    parent.world().compose(pv.copyTransform(c)), parent.depth() + 1, parent);
                out.add(i);
                expand(i, out);
            }
        }
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    /**
     * G4Navigator::LocateGlobalPointAndSetup: the deepest volume holding a world
     * point, as the chain of instances from the world, or an empty list outside.
     */
    public List<Instance> locate(Vec3 p) {
        final List<Instance> path = new ArrayList<>();
        final List<Instance> all = instances;
        if (all.isEmpty() || world.solid == null || world.solid.inside(p) == Solid.OUTSIDE) {
            return path;
        }
        // Map from (parent instance, pv, copy) to instance: walk the flattened list by structure
        Instance cur = all.get(0);
        path.add(cur);
        double[] q = {p.x(), p.y(), p.z()};
        outer:
        while (true) {
            final double[] local = new double[3];
            cur.world().applyInverse(p.x(), p.y(), p.z(), local);
            final DaughterIndex.Slot[] hit = new DaughterIndex.Slot[1];
            cur.lv().index().query(local[0], local[1], local[2], slot -> {
                final double[] d = new double[3];
                slot.t.applyInverse(local[0], local[1], local[2], d);
                if (slot.pv.logical.solid.inside(d[0], d[1], d[2]) != Solid.OUTSIDE) {
                    hit[0] = slot;
                    return false;
                }
                return true;
            });
            if (hit[0] == null) {
                break;
            }
            // Find the child instance
            for (Instance c : childrenOf(cur)) {
                if (c.pv() == hit[0].pv && c.copy() == hit[0].copy) {
                    cur = c;
                    path.add(cur);
                    continue outer;
                }
            }
            break;
        }
        return path;
    }

    private final Map<Instance, List<Instance>> childCache = new HashMap<>();
    private List<Instance> childCacheFor = List.of();

    /** The instances placed directly inside an instance. */
    public synchronized List<Instance> childrenOf(Instance parent) {
        if (childCacheFor != instances) {
            childCache.clear();
            for (Instance i : instances) {
                if (i.parent() != null) {
                    childCache.computeIfAbsent(i.parent(), k -> new ArrayList<>()).add(i);
                }
            }
            childCacheFor = instances;
        }
        return childCache.getOrDefault(parent, List.of());
    }

    /** The instances of a placement (all copies, under every placement of its mother). */
    public List<Instance> instancesOf(PhysicalVolume pv) {
        final List<Instance> out = new ArrayList<>();
        for (Instance i : instances) {
            if (i.pv() == pv) {
                out.add(i);
            }
        }
        return out;
    }

    /** The world box of an instance's solid. */
    public static BBox worldBox(Instance i) {
        return i.lv().solid == null || i.lv().solid.error() != null ? BBox.EMPTY : i.world().apply(i.lv().solid.box());
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    /** A name not yet used by any solid, volume, placement or define. */
    public String uniqueName(String base) {
        String b = base == null || base.isBlank() ? "Object" : base.replaceAll("[^A-Za-z0-9_]", "_");
        if (Character.isDigit(b.charAt(0))) {
            b = "_" + b;
        }
        if (!nameTaken(b)) {
            return b;
        }
        final String stem = b.replaceAll("_?\\d+$", "");
        for (int i = 1; ; i++) {
            final String n = stem + "_" + i;
            if (!nameTaken(n)) {
                return n;
            }
        }
    }

    public boolean nameTaken(String n) {
        return solids.containsKey(n) || volumes.containsKey(n) || defines.containsKey(n) || matrices.containsKey(n)
            || placement(n) != null || Units.isReserved(n);
    }

    public void addDefine(Define d) {
        defines.put(d.name, d);
    }

    public void removeDefine(String name) {
        defines.remove(name);
    }

    public void addSolid(Solid s) {
        solids.put(s.name(), s);
    }

    public void removeSolid(Solid s) {
        solids.remove(s.name());
    }

    public void addVolume(LogicalVolume lv) {
        volumes.put(lv.name, lv);
    }

    public void setWorld(LogicalVolume lv) {
        this.world = lv;
    }

    /** Places a logical volume in a mother; the placement is named after the volume. */
    public PhysicalVolume place(LogicalVolume lv, LogicalVolume mother, String name) {
        final PhysicalVolume pv = new PhysicalVolume(name == null ? uniqueName(lv.name + "_pv") : name, lv, mother);
        int copy = 0;
        for (PhysicalVolume o : mother.daughters) {
            if (o.logical == lv) {
                copy = Math.max(copy, o.copyNumber + 1);
            }
        }
        pv.copyNumber = copy;
        mother.daughters.add(pv);
        mother.invalidate();
        return pv;
    }

    /** Removes a placement; the logical volume stays defined. */
    public void unplace(PhysicalVolume pv) {
        if (pv.mother != null) {
            pv.mother.daughters.remove(pv);
            pv.mother.invalidate();
        }
    }

    /** Moves a placement to another mother, keeping its world position when asked. */
    public boolean reparent(PhysicalVolume pv, LogicalVolume newMother) {
        if (pv.logical.contains(newMother)) {
            return false; // would place a volume inside itself
        }
        unplace(pv);
        pv.mother = newMother;
        newMother.daughters.add(pv);
        newMother.invalidate();
        return true;
    }

    /** Deletes a logical volume with its placements, and its solid when nothing else uses it. */
    public void deleteVolume(LogicalVolume lv) {
        if (lv == world) {
            return;
        }
        for (PhysicalVolume pv : placementsOf(lv)) {
            unplace(pv);
        }
        volumes.remove(lv.name);
        for (PhysicalVolume d : new ArrayList<>(lv.daughters)) {
            if (placementsOf(d.logical).isEmpty() || placementsOf(d.logical).stream().allMatch(p -> p.mother == lv)) {
                deleteVolume(d.logical);
            }
        }
        if (lv.solid != null && users(lv.solid).isEmpty()) {
            removeSolidTree(lv.solid);
        }
    }

    private void removeSolidTree(Solid s) {
        solids.remove(s.name());
        for (Solid o : s.operands()) {
            if (users(o).isEmpty() && solids.values().stream().noneMatch(x -> x.operands().contains(o))) {
                removeSolidTree(o);
            }
        }
    }

    /**
     * Renames a define, solid, volume or placement, and rewrites every
     * expression that named a define, so the model keeps meaning the same.
     */
    public boolean rename(Object target, String newName) {
        if (newName == null || newName.isBlank() || nameTaken(newName)) {
            return false;
        }
        if (target instanceof Define d) {
            final String old = d.name;
            defines.remove(old);
            d.name = newName;
            defines.put(newName, d);
            rewriteExpressions(old, newName);
            return true;
        }
        if (target instanceof Solid s) {
            solids.remove(s.name());
            s.rename(newName);
            solids.put(newName, s);
            return true;
        }
        if (target instanceof LogicalVolume lv) {
            volumes.remove(lv.name);
            lv.name = newName;
            volumes.put(newName, lv);
            return true;
        }
        if (target instanceof PhysicalVolume pv) {
            pv.name = newName;
            return true;
        }
        if (target instanceof Material m && materials.isDefined(m.name())) {
            materials.rename(m.name(), newName);
            for (LogicalVolume lv : volumes.values()) {
                if (m.name().equals(lv.material)) {
                    lv.material = newName;
                }
            }
            return true;
        }
        return false;
    }

    private void rewriteExpressions(String from, String to) {
        final Consumer<Solid> fix = s -> {
            for (Solid.Param p : s.params()) {
                p.expr = rewrite(p.expr, from, to);
            }
            for (Solid.Table t : s.tables()) {
                for (String[] row : t.rows) {
                    for (int i = 0; i < row.length; i++) {
                        row[i] = rewrite(row[i], from, to);
                    }
                }
            }
        };
        solids.values().forEach(fix);
        for (Define d : defines.values()) {
            d.expr = rewrite(d.expr, from, to);
        }
        for (PhysicalVolume pv : placements()) {
            pv.x = rewrite(pv.x, from, to);
            pv.y = rewrite(pv.y, from, to);
            pv.z = rewrite(pv.z, from, to);
            pv.rx = rewrite(pv.rx, from, to);
            pv.ry = rewrite(pv.ry, from, to);
            pv.rz = rewrite(pv.rz, from, to);
            if (pv.replica != null) {
                pv.replica.width = rewrite(pv.replica.width, from, to);
                pv.replica.offset = rewrite(pv.replica.offset, from, to);
                pv.replica.number = rewrite(pv.replica.number, from, to);
            }
        }
    }

    private static String rewrite(String expr, String from, String to) {
        try {
            final Expr e = Expr.parse(expr);
            return e.variables().contains(from) ? e.rename(from, to) : expr;
        } catch (GeometryException e) {
            return expr;
        }
    }

    /** Replaces the whole content with another document's, keeping the listeners. */
    public void replaceWith(Geometry o) {
        copyFrom(o);
        rebuild();
    }

    /**
     * Same, for a document evaluated just before and changed by nothing since (a file read aside): its
     * evaluation is taken over instead of done again. For a big ROOT geometry that was seconds on the event
     * thread, right after the file had been read and evaluated on another one.
     */
    public synchronized void replaceWithEvaluated(Geometry o) {
        copyFrom(o);
        materials.resolveAll();
        issues.clear();
        issues.addAll(o.issues);
        values.clear();
        values.putAll(o.values);
        instances = o.instances;
        revision++;
    }

    private void copyFrom(Geometry o) {
        defines.clear();
        defines.putAll(o.defines);
        materials.clear();
        for (Element e : o.materials.definedElements()) {
            materials.defineElement(e);
        }
        for (Material m : o.materials.defined()) {
            try {
                materials.define(m);
            } catch (GeometryException ignored) {
                // reported by rebuild
            }
        }
        solids.clear();
        solids.putAll(o.solids);
        volumes.clear();
        volumes.putAll(o.volumes);
        world = o.world;
        title = o.title;
        settings.clear();
        settings.putAll(o.settings);
        matrices.clear();
        matrices.putAll(o.matrices);
        surfaces.clear();
        surfaces.addAll(o.surfaces);
        surfaceLinks.clear();
        surfaceLinks.addAll(o.surfaceLinks);
    }
}

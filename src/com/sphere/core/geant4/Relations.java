package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The design intent behind volumes drawn by hand, read from where they stand and written as relations, the way Creo
 * keeps a part's relations: plates stacked face to face along an axis become a stack whose thicknesses are variables
 * and whose positions follow from them; an envelope that holds a stack exactly is sized by it; a width all the plates
 * of a stack share becomes one variable. A thickness changed afterwards (on the model, in the Variables, by a goal)
 * moves the plates after it and resizes the envelope.
 *
 * <p>What the designer wrote is kept: a position that already names a variable keeps its stack out of the relations,
 * an expression is used as it is. Nothing moves when the relations are written: every position and size is checked
 * against what it was, and the relations are not kept if one differs.
 */
public final class Relations {

    /** Two faces closer than this (mm) touch: far below a drawing's precision, far above double rounding. */
    private static final double TOL = 1e-6;
    private static final String AXES = "xyz";

    private Relations() {
    }

    public enum Kind { VARIABLE, SOLID, PLACEMENT }

    /** One change: a new variable, a solid's parameter, or one coordinate of a placement. */
    public record Step(Kind kind, String target, String property, String expr, Object ref) {
        /** The console command that does the same. */
        public String command() {
            return kind == Kind.VARIABLE ? "var " + target + " = " + expr : "set " + target + "." + property + " = " + expr;
        }
    }

    /** The relations found: the changes that write them, and what was read, in words. */
    public record Plan(List<Step> steps, List<String> findings) {
        public boolean isEmpty() {
            return steps.isEmpty();
        }

        /** The variables the relations make. */
        public List<String> variables() {
            final List<String> out = new ArrayList<>();
            for (Step s : steps) {
                if (s.kind() == Kind.VARIABLE) {
                    out.add(s.target() + " = " + s.expr());
                }
            }
            return out;
        }

        public List<String> commands() {
            final List<String> out = new ArrayList<>();
            for (Step s : steps) {
                out.add(s.command());
            }
            return out;
        }
    }

    // ------------------------------------------------------------------
    // Reading the intent
    // ------------------------------------------------------------------

    /**
     * The relations of the design, or of the placements given (and what they hold): stacks along z first, as
     * detectors stack along the beam, then y and x.
     */
    public static Plan plan(Geometry g, Collection<PhysicalVolume> scope) {
        final Set<PhysicalVolume> in = scope == null || scope.isEmpty() ? null : expand(scope);
        final Planner x = new Planner(g);
        for (LogicalVolume m : bottomUp(g)) {
            final List<Piece> ps = new ArrayList<>();
            for (PhysicalVolume pv : m.daughters()) {
                if (in != null && !in.contains(pv)) {
                    continue;
                }
                final Piece p = Piece.of(pv);
                if (p != null) {
                    ps.add(p);
                }
            }
            if (ps.size() < 2) {
                continue;
            }
            for (int a = 2; a >= 0; a--) {
                for (List<Piece> chain : chains(ps, a)) {
                    x.stack(m, chain, a);
                }
            }
        }
        x.widths();
        final Plan plan = new Plan(List.copyOf(x.steps), List.copyOf(x.findings));
        if (plan.isEmpty()) {
            return plan;
        }
        // tried on a copy first: the relations must give back every value as it is
        try {
            final Geometry copy = Gdml.read(Gdml.write(g), null).geometry();
            copy.rebuild();
            apply(copy, plan);
        } catch (GeometryException e) {
            return new Plan(List.of(), List.of("Relations were found, but they would move something (" + e.getMessage()
                + "): none are proposed."));
        }
        return plan;
    }

    /** The placements given and the ones their volumes hold. */
    private static Set<PhysicalVolume> expand(Collection<PhysicalVolume> scope) {
        final Set<PhysicalVolume> out = new HashSet<>(scope);
        for (PhysicalVolume pv : scope) {
            out.addAll(pv.logical().daughters());
        }
        return out;
    }

    /** The volumes, deepest first: an envelope is sized by its stack before the stack it stands in is read. */
    private static List<LogicalVolume> bottomUp(Geometry g) {
        final List<LogicalVolume> out = new ArrayList<>();
        if (g.world() != null) {
            visit(g.world(), new HashSet<>(), out);
        }
        return out;
    }

    private static void visit(LogicalVolume lv, Set<LogicalVolume> seen, List<LogicalVolume> out) {
        if (!seen.add(lv)) {
            return;
        }
        for (PhysicalVolume d : lv.daughters()) {
            visit(d.logical(), seen, out);
        }
        out.add(lv);
    }

    /** A placement that can stand in a stack: unturned, unmirrored, not a replica, a solid with flat faces. */
    private static final class Piece {
        final PhysicalVolume pv;
        final Solid s;
        final double[] lo = new double[3], hi = new double[3];
        /** The parameter that is the full length along each axis (the faces at plus and minus half of it), or null. */
        final String[] key;

        private Piece(PhysicalVolume pv, Solid s, String[] key) {
            this.pv = pv;
            this.s = s;
            this.key = key;
        }

        static Piece of(PhysicalVolume pv) {
            if (pv.isReplica() || pv.reflected() || pv.error() != null) {
                return null;
            }
            final Vec3 r = pv.rotation();
            if (Math.abs(r.x()) > 1e-12 || Math.abs(r.y()) > 1e-12 || Math.abs(r.z()) > 1e-12) {
                return null;
            }
            final Solid s = pv.logical().solid();
            if (s == null || s.error() != null) {
                return null;
            }
            final String[] key = keys(s);
            if (key == null) {
                return null;
            }
            final Piece p = new Piece(pv, s, key);
            final BBox b = s.extent();
            final Vec3 c = pv.position();
            for (int a = 0; a < 3; a++) {
                p.lo[a] = c.get(a) + b.min().get(a);
                p.hi[a] = c.get(a) + b.max().get(a);
                if (key[a] != null && Math.abs(b.max().get(a) + b.min().get(a)) > TOL) {
                    key[a] = null;
                }
            }
            return p;
        }

        String name() {
            return pv.logical().name();
        }
    }

    /** The full-length parameters of a solid's axes: a box's three, the z length of a tube, a cone or a trd. */
    static String[] keys(Solid s) {
        return switch (s.tag()) {
            case "box" -> new String[] {"x", "y", "z"};
            case "tube", "cone", "trd" -> new String[] {null, null, "z"};
            default -> null;
        };
    }

    /** The stacks along an axis: pieces each lying on the one before, face on face, their footprints overlapping. */
    private static List<List<Piece>> chains(List<Piece> all, int a) {
        final List<Piece> ps = new ArrayList<>();
        for (Piece p : all) {
            if (p.key[a] != null) {
                ps.add(p);
            }
        }
        final int n = ps.size();
        final int[] next = new int[n], prev = new int[n], outs = new int[n], ins = new int[n];
        Arrays.fill(next, -1);
        Arrays.fill(prev, -1);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && touches(ps.get(i), ps.get(j), a)) {
                    outs[i]++;
                    ins[j]++;
                    next[i] = j;
                    prev[j] = i;
                }
            }
        }
        final List<List<Piece>> out = new ArrayList<>();
        final boolean[] used = new boolean[n];
        for (int i = 0; i < n; i++) {
            final boolean linkedIn = ins[i] == 1 && outs[prev[i]] == 1;
            if (linkedIn || used[i]) {
                continue;
            }
            final List<Piece> c = new ArrayList<>();
            int k = i;
            while (true) {
                c.add(ps.get(k));
                used[k] = true;
                if (outs[k] != 1 || ins[next[k]] != 1 || used[next[k]]) {
                    break;
                }
                k = next[k];
            }
            if (c.size() >= 2) {
                out.add(c);
            }
        }
        return out;
    }

    /** j lies on i along the axis: j's low face on i's high face, and they overlap across. */
    private static boolean touches(Piece i, Piece j, int a) {
        if (Math.abs(j.lo[a] - i.hi[a]) > tol(i.hi[a])) {
            return false;
        }
        for (int b = 0; b < 3; b++) {
            if (b != a && Math.min(i.hi[b], j.hi[b]) - Math.max(i.lo[b], j.lo[b]) <= TOL) {
                return false;
            }
        }
        return true;
    }

    private static double tol(double v) {
        return TOL + 1e-12 * Math.abs(v);
    }

    private static boolean literal(String expr) {
        try {
            return Expr.parse(expr).variables().isEmpty();
        } catch (GeometryException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Writing it
    // ------------------------------------------------------------------

    private record Stack(LogicalVolume mother, List<Piece> pieces, int axis, String name) {
    }

    private static final class Planner {
        final Geometry g;
        final List<Step> steps = new ArrayList<>();
        final List<String> findings = new ArrayList<>();
        /** What each solid parameter (solid.key) is written as now: a new variable, an envelope's sum. */
        final Map<String, String> assigned = new HashMap<>();
        final Set<String> names = new HashSet<>();
        final List<Stack> stacks = new ArrayList<>();

        Planner(Geometry g) {
            this.g = g;
        }

        /** A name for a new variable, a valid identifier no object or variable has. */
        String name(String base) {
            String b = base.replaceAll("[^A-Za-z0-9_]", "_");
            if (b.isEmpty() || !Character.isLetter(b.charAt(0)) && b.charAt(0) != '_') {
                b = "v_" + b;
            }
            String n = b;
            for (int k = 2; g.nameTaken(n) || g.define(n) != null || names.contains(n) || Units.isReserved(n); k++) {
                n = b + "_" + k;
            }
            names.add(n);
            return n;
        }

        /** A piece's thickness along an axis as an expression: a new variable where it was a plain value. */
        String thickness(Piece p, int a) {
            final String id = p.s.name() + "." + p.key[a];
            final String done = assigned.get(id);
            if (done != null) {
                return done;
            }
            final String expr = p.s.param(p.key[a]).expr();
            if (!literal(expr)) {
                assigned.put(id, expr);
                return expr;
            }
            boolean other = false;
            for (String k : keys(p.s)) {
                other |= k != null && !k.equals(p.key[a]) && assigned.containsKey(p.s.name() + "." + k);
            }
            final String v = name(p.name() + (other ? "_" + p.key[a] : "_t"));
            steps.add(new Step(Kind.VARIABLE, v, null, expr, null));
            steps.add(new Step(Kind.SOLID, p.s.name(), p.key[a], v, p.s));
            assigned.put(id, v);
            return v;
        }

        /** A stack: its thicknesses become variables, its positions sums of them, its envelope its sum when it fits. */
        void stack(LogicalVolume m, List<Piece> chain, int a) {
            final String axis = String.valueOf(AXES.charAt(a));
            for (Piece p : chain) {
                if (!literal(p.pv.positionExprs()[a])) {
                    findings.add("The stack along " + axis + " in " + m.name() + " (" + names(chain) + ") is already placed"
                        + " by expressions: kept as written.");
                    return;
                }
            }
            final int n = chain.size();
            final Lin[] t = new Lin[n];
            final Lin sum = new Lin();
            for (int i = 0; i < n; i++) {
                t[i] = Lin.of(thickness(chain.get(i), a));
                sum.add(t[i], 1);
            }
            final double lo0 = chain.get(0).lo[a], hiK = chain.get(n - 1).hi[a];
            final Solid ms = m.solid();
            final String[] mk = ms == null || ms.error() != null ? null : keys(ms);
            final String mKey = mk == null ? null : mk[a];
            final double mHalf = mKey != null ? ms.v(mKey) / 2 : Double.NaN;
            final boolean bottom = mKey != null && Math.abs(lo0 + mHalf) <= tol(mHalf);
            final boolean top = mKey != null && Math.abs(hiK - mHalf) <= tol(mHalf);
            final String mId = mKey == null ? null : ms.name() + "." + mKey;
            final String mExpr = mKey == null ? null : assigned.getOrDefault(mId, ms.param(mKey).expr());
            Lin base;
            boolean fromTop = false;
            String how;
            if (bottom && top && m != g.world() && !assigned.containsKey(mId) && literal(mExpr)) {
                final String s = sum.text();
                steps.add(new Step(Kind.SOLID, ms.name(), mKey, s, ms));
                assigned.put(mId, s);
                base = new Lin().add(sum, -0.5);
                how = m.name() + " holds it exactly: its " + axis + " becomes " + s;
            } else if (bottom) {
                base = Lin.of(mExpr).times(-0.5);
                how = "from the -" + axis + " face of " + m.name();
            } else if (top) {
                base = Lin.of(mExpr).times(0.5);
                fromTop = true;
                how = "against the +" + axis + " face of " + m.name();
            } else if (Math.abs(lo0 + hiK) <= tol(hiK - lo0)) {
                base = new Lin().add(sum, -0.5);
                how = "centered on " + axis + " = 0";
            } else {
                base = new Lin();
                base.constant = lo0;
                how = "from " + axis + " = " + Units.best(lo0, Units.Category.LENGTH);
            }
            for (int j = 0; j < n; j++) {
                final Lin pos = new Lin().add(base, 1);
                if (!fromTop) {
                    for (int i = 0; i < j; i++) {
                        pos.add(t[i], 1);
                    }
                    pos.add(t[j], 0.5);
                } else {
                    for (int i = j + 1; i < n; i++) {
                        pos.add(t[i], -1);
                    }
                    pos.add(t[j], -0.5);
                }
                final Piece p = chain.get(j);
                steps.add(new Step(Kind.PLACEMENT, p.pv.name(), axis, pos.text(), p.pv));
            }
            final Stack st = new Stack(m, chain, a, stackName(m, chain));
            stacks.add(st);
            findings.add(n + " volumes stacked along " + axis + " in " + m.name() + " (" + names(chain) + "), " + how + ".");
        }

        /** The widths all the pieces of a stack share become one variable, their envelope's too when it is as wide. */
        void widths() {
            for (Stack st : stacks) {
                final int a = st.axis();
                final boolean boxes = st.pieces().stream().allMatch(p -> p.s.tag().equals("box"));
                final boolean tubes = a == 2 && st.pieces().stream().allMatch(p -> p.s.tag().equals("tube"));
                if (boxes) {
                    final Map<Integer, Double> common = new LinkedHashMap<>();
                    for (int b = 0; b < 3; b++) {
                        if (b != a) {
                            final double v = shared(st, AXES.substring(b, b + 1));
                            if (!Double.isNaN(v)) {
                                common.put(b, v);
                            }
                        }
                    }
                    if (common.size() == 2 && Math.abs(common.values().stream().findFirst().get()
                        - common.values().stream().skip(1).findFirst().get()) <= TOL) {
                        final String v = name(st.name() + "_w");
                        assignWidth(st, v, common.keySet().stream().map(b -> AXES.substring(b, b + 1)).toList());
                    } else {
                        for (int b : common.keySet()) {
                            final String k = AXES.substring(b, b + 1);
                            assignWidth(st, name(st.name() + "_" + k), List.of(k));
                        }
                    }
                } else if (tubes && !Double.isNaN(shared(st, "rmax"))) {
                    assignWidth(st, name(st.name() + "_r"), List.of("rmax"));
                }
            }
        }

        /** The value of a parameter every piece of a stack has, plain and equal and not yet related; else NaN. */
        double shared(Stack st, String key) {
            double v = Double.NaN;
            for (Piece p : st.pieces()) {
                final Solid.Param prm = p.s.param(key);
                if (prm == null || assigned.containsKey(p.s.name() + "." + key) || !literal(prm.expr())) {
                    return Double.NaN;
                }
                if (Double.isNaN(v)) {
                    v = prm.value();
                } else if (Math.abs(prm.value() - v) > tol(v)) {
                    return Double.NaN;
                }
            }
            return v;
        }

        void assignWidth(Stack st, String var, List<String> keys) {
            final Piece first = st.pieces().get(0);
            final String text = first.s.param(keys.get(0)).expr();
            final double value = first.s.param(keys.get(0)).value();
            steps.add(new Step(Kind.VARIABLE, var, null, text, null));
            final Set<Solid> done = new HashSet<>();
            for (Piece p : st.pieces()) {
                if (done.add(p.s)) {
                    for (String k : keys) {
                        steps.add(new Step(Kind.SOLID, p.s.name(), k, var, p.s));
                        assigned.put(p.s.name() + "." + k, var);
                    }
                }
            }
            // the envelope, when it is exactly as wide
            final Solid ms = st.mother().solid();
            String also = "";
            if (st.mother() != g.world() && ms != null && ms.error() == null && ms.tag().equals(first.s.tag()) && done.add(ms)) {
                for (String k : keys) {
                    final Solid.Param prm = ms.param(k);
                    if (prm != null && !assigned.containsKey(ms.name() + "." + k) && literal(prm.expr())
                        && Math.abs(prm.value() - value) <= tol(value)) {
                        steps.add(new Step(Kind.SOLID, ms.name(), k, var, ms));
                        assigned.put(ms.name() + "." + k, var);
                        also = ", and " + st.mother().name() + " around them";
                    }
                }
            }
            findings.add((st.name().equals("stack") ? "The stacked volumes share their " : "The volumes of the " + st.name()
                + " stack share their ") + String.join(" and ", keys) + ": " + var + " = " + text + also + ".");
        }

        /** The stack's name: what its volumes' names begin with, else its envelope's, else "stack". */
        String stackName(LogicalVolume m, List<Piece> chain) {
            String prefix = chain.get(0).name();
            for (Piece p : chain) {
                int k = 0;
                final String n = p.name();
                while (k < prefix.length() && k < n.length() && prefix.charAt(k) == n.charAt(k)) {
                    k++;
                }
                prefix = prefix.substring(0, k);
            }
            prefix = prefix.replaceAll("[_\\d]+$", "");
            if (prefix.length() >= 3) {
                return prefix;
            }
            return m != g.world() ? m.name() : "stack";
        }

        static String names(List<Piece> chain) {
            final List<String> out = new ArrayList<>();
            for (Piece p : chain) {
                if (out.size() == 6) {
                    out.add("...");
                    break;
                }
                out.add(p.pv.name());
            }
            return String.join(", ", out);
        }
    }

    /** A sum of terms with coefficients and a length: what positions in a stack are written as. */
    private static final class Lin {
        final Map<String, Double> terms = new LinkedHashMap<>();
        double constant;

        /** An expression as a term, or a plain value as a length. */
        static Lin of(String expr) {
            final Lin l = new Lin();
            if (literal(expr)) {
                l.constant = Expr.evalOr(expr, Expr.NO_VARIABLES, 0);
            } else {
                l.terms.put(expr, 1.0);
            }
            return l;
        }

        Lin add(Lin o, double c) {
            for (Map.Entry<String, Double> e : o.terms.entrySet()) {
                terms.merge(e.getKey(), e.getValue() * c, Double::sum);
            }
            constant += o.constant * c;
            return this;
        }

        Lin times(double c) {
            terms.replaceAll((k, v) -> v * c);
            constant *= c;
            return this;
        }

        String text() {
            final StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Double> e : terms.entrySet()) {
                final double c = e.getValue();
                if (Math.abs(c) < 1e-12) {
                    continue;
                }
                final String t = e.getKey().matches("[A-Za-z_][A-Za-z0-9_]*") ? e.getKey() : "(" + e.getKey() + ")";
                final double abs = Math.abs(c);
                b.append(b.length() == 0 ? c < 0 ? "-" : "" : c < 0 ? " - " : " + ");
                b.append(Math.abs(abs - 1) < 1e-12 ? t : Units.num(abs) + "*" + t);
            }
            if (Math.abs(constant) > 1e-12 || b.length() == 0) {
                final String k = Units.num(Math.abs(constant)) + "*mm";
                b.append(b.length() == 0 ? (constant < 0 ? "-" : "") + k : (constant < 0 ? " - " : " + ") + k);
            }
            return b.toString();
        }
    }

    // ------------------------------------------------------------------
    // Applying, checked
    // ------------------------------------------------------------------

    /**
     * Writes the relations into the design, then checks that every placement stands where it stood and every solid
     * has its sizes: if one differs, everything is put back and the reason thrown.
     */
    public static void apply(Geometry g, Plan plan) throws GeometryException {
        final Map<String, Vec3> pos = new HashMap<>();
        for (PhysicalVolume pv : g.placements()) {
            pos.put(pv.mother().name() + "/" + pv.name(), pv.position());
        }
        final Map<String, double[]> sizes = new HashMap<>();
        for (Solid s : g.solids()) {
            sizes.put(s.name(), values(s));
        }
        final Set<String> errors = new HashSet<>();
        for (Geometry.Issue i : g.issues()) {
            if (i.severity() == Geometry.Severity.ERROR) {
                errors.add(i.message());
            }
        }
        final List<Runnable> undo = new ArrayList<>();
        try {
            for (Step st : plan.steps()) {
                switch (st.kind()) {
                    case VARIABLE -> {
                        if (g.define(st.target()) != null || g.nameTaken(st.target())) {
                            throw new GeometryException("the name " + st.target() + " is taken");
                        }
                        g.addDefine(new Define(st.target(), Define.Kind.CONSTANT, st.expr()));
                        undo.add(() -> g.removeDefine(st.target()));
                    }
                    case SOLID -> {
                        final Solid s = st.ref() instanceof Solid r && g.solid(r.name()) == r ? r : g.solid(st.target());
                        if (s == null || s.param(st.property()) == null) {
                            throw new GeometryException("no solid " + st.target() + "." + st.property());
                        }
                        final String old = s.param(st.property()).expr();
                        s.set(st.property(), st.expr());
                        undo.add(() -> s.set(st.property(), old));
                    }
                    case PLACEMENT -> {
                        final PhysicalVolume pv = st.ref() instanceof PhysicalVolume r && r.mother() != null
                            && g.volume(r.mother().name()) == r.mother() && r.mother().daughters().contains(r) ? r
                            : g.placement(st.target());
                        if (pv == null) {
                            throw new GeometryException("no placement " + st.target());
                        }
                        final String[] p = pv.positionExprs();
                        final String[] old = p.clone();
                        p[AXES.indexOf(st.property())] = st.expr();
                        pv.setPosition(p[0], p[1], p[2]);
                        undo.add(() -> pv.setPosition(old[0], old[1], old[2]));
                    }
                }
            }
            g.rebuild();
            final String moved = moved(g, pos, sizes, errors);
            if (moved != null) {
                throw new GeometryException(moved);
            }
        } catch (GeometryException | RuntimeException e) {
            for (int i = undo.size() - 1; i >= 0; i--) {
                undo.get(i).run();
            }
            g.rebuild();
            throw e instanceof GeometryException ge ? ge : new GeometryException(String.valueOf(e.getMessage()));
        }
    }

    private static double[] values(Solid s) {
        final List<Solid.Param> ps = s.params();
        final double[] out = new double[ps.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ps.get(i).value();
        }
        return out;
    }

    /** What the relations changed, said; null when everything is where it was. */
    private static String moved(Geometry g, Map<String, Vec3> pos, Map<String, double[]> sizes, Set<String> errors) {
        for (Geometry.Issue i : g.issues()) {
            if (i.severity() == Geometry.Severity.ERROR && !errors.contains(i.message())) {
                return i.message();
            }
        }
        for (PhysicalVolume pv : g.placements()) {
            final Vec3 was = pos.get(pv.mother().name() + "/" + pv.name());
            if (was != null && pv.position().distance(was) > TOL + 1e-12 * was.length()) {
                return pv.name() + " by " + Units.best(pv.position().distance(was), Units.Category.LENGTH);
            }
        }
        for (Solid s : g.solids()) {
            final double[] was = sizes.get(s.name());
            final double[] now = values(s);
            if (was != null && was.length == now.length) {
                for (int i = 0; i < now.length; i++) {
                    if (Math.abs(now[i] - was[i]) > TOL + 1e-12 * Math.abs(was[i])) {
                        return s.name() + "." + s.params().get(i).key + " from " + Units.num(was[i]) + " to " + Units.num(now[i]);
                    }
                }
            }
        }
        return null;
    }
}

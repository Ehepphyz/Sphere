package com.sphere.core.geant4;

import java.util.List;

/**
 * G4UnionSolid, G4SubtractionSolid and G4IntersectionSolid.
 *
 * The second solid is placed in the frame of the first with an active
 * transform, as G4GDMLReadSolids::BooleanRead builds it:
 * G4Transform3D(R_gdml, position). The first may be displaced too
 * (GDML's firstposition / firstrotation, a G4DisplacedSolid in Geant4).
 */
public final class BooleanSolid extends Solid {

    public enum Op {
        UNION("union", "G4UnionSolid"),
        SUBTRACTION("subtraction", "G4SubtractionSolid"),
        INTERSECTION("intersection", "G4IntersectionSolid");

        public final String tag;
        public final String g4;

        Op(String tag, String g4) {
            this.tag = tag;
            this.g4 = g4;
        }

        public static Op ofTag(String t) {
            for (Op o : values()) {
                if (o.tag.equals(t)) {
                    return o;
                }
            }
            return null;
        }
    }

    private static final Kind L = Kind.LENGTH;
    private static final Kind A = Kind.ANGLE;

    private final Op op;
    private Solid first, second;
    private Xform tSecond = Xform.IDENTITY, tFirst = Xform.IDENTITY;
    private boolean firstMoved;

    public BooleanSolid(String name, Op op, Solid first, Solid second) {
        super(name);
        this.op = op;
        this.first = first;
        this.second = second;
        param("x", "Second position x", L, "0*mm");
        param("y", "Second position y", L, "0*mm");
        param("z", "Second position z", L, "0*mm");
        param("rx", "Second rotation x", A, "0*deg");
        param("ry", "Second rotation y", A, "0*deg");
        param("rz", "Second rotation z", A, "0*deg");
        param("fx", "First position x", L, "0*mm");
        param("fy", "First position y", L, "0*mm");
        param("fz", "First position z", L, "0*mm");
        param("frx", "First rotation x", A, "0*deg");
        param("fry", "First rotation y", A, "0*deg");
        param("frz", "First rotation z", A, "0*deg");
    }

    public Op op() {
        return op;
    }

    public Solid first() {
        return first;
    }

    public Solid second() {
        return second;
    }

    public boolean firstMoved() {
        return firstMoved;
    }

    public String tag() {
        return op.tag;
    }

    public String g4Class() {
        return op.g4;
    }

    @Override
    public List<Solid> operands() {
        return List.of(first, second);
    }

    @Override
    public void replaceOperand(Solid oldOne, Solid newOne) {
        if (first == oldOne) {
            first = newOne;
        }
        if (second == oldOne) {
            second = newOne;
        }
    }

    protected void validate() throws GeometryException {
        require(first != null && second != null, op.g4 + " " + name + ": needs two solids");
        require(first != this && second != this, op.g4 + " " + name + ": cannot contain itself");
        tSecond = Xform.booleanPlacement(v("x"), v("y"), v("z"), v("rx"), v("ry"), v("rz"));
        tFirst = Xform.booleanPlacement(v("fx"), v("fy"), v("fz"), v("frx"), v("fry"), v("frz"));
        firstMoved = v("fx") != 0 || v("fy") != 0 || v("fz") != 0 || v("frx") != 0 || v("fry") != 0 || v("frz") != 0;
    }

    public double sdf(double x, double y, double z) {
        final double[] q = new double[3];
        double a;
        if (firstMoved) {
            tFirst.applyInverse(x, y, z, q);
            a = first.sdf(q[0], q[1], q[2]);
        } else {
            a = first.sdf(x, y, z);
        }
        tSecond.applyInverse(x, y, z, q);
        final double b = second.sdf(q[0], q[1], q[2]);
        return switch (op) {
            case UNION -> Math.min(a, b);
            case INTERSECTION -> Math.max(a, b);
            case SUBTRACTION -> Math.max(a, -b);
        };
    }

    public double safety() {
        return Math.min(first.safety(), second.safety());
    }

    public BBox extent() {
        final BBox a = tFirst.apply(first.box());
        final BBox b = tSecond.apply(second.box());
        return switch (op) {
            case UNION -> a.union(b);
            case INTERSECTION -> a.intersect(b);
            case SUBTRACTION -> a;
        };
    }

    protected Mesh buildMesh(int q) {
        if (op == Op.SUBTRACTION && first instanceof BooleanSolid f && f.op == Op.SUBTRACTION) {
            // a chain of subtractions (a volume less the neighbours that win over it, as overlapping
            // ROOT placements become) drawn at once: the body less every cutter
            final java.util.List<Mesh> cutters = new java.util.ArrayList<>();
            Solid s = this;
            Xform t = Xform.IDENTITY;
            while (s instanceof BooleanSolid b && b.op == Op.SUBTRACTION && b.error() == null) {
                cutters.add(b.second.mesh(q).transformed(t.compose(b.tSecond)));
                t = t.compose(b.tFirst);
                s = b.first;
            }
            java.util.Collections.reverse(cutters);
            return Csg.subtractAll(s.mesh(q).transformed(t), cutters);
        }
        final Mesh a = first.mesh(q).transformed(tFirst);
        final Mesh b = second.mesh(q).transformed(tSecond);
        final Csg.Op o = switch (op) {
            case UNION -> Csg.Op.UNION;
            case INTERSECTION -> Csg.Op.INTERSECTION;
            case SUBTRACTION -> Csg.Op.SUBTRACTION;
        };
        return Csg.apply(o, a, b);
    }

    protected double computeVolume() {
        return estimateVolume(1_000_000, 0xB001);
    }

    public Xform secondTransform() {
        return tSecond;
    }

    public Xform firstTransform() {
        return tFirst;
    }

    public String cpp(CppNames n) {
        final String a = firstMoved
            ? "new G4DisplacedSolid(" + n.string(name + "_first") + ", " + n.solid(first) + ", "
                + transform3D("frx", "fry", "frz", "fx", "fy", "fz") + ")"
            : n.solid(first);
        return "new " + op.g4 + "(" + n.string(name) + ", " + a + ", " + n.solid(second) + ", "
            + transform3D("rx", "ry", "rz", "x", "y", "z") + ")";
    }

    private String transform3D(String rx, String ry, String rz, String x, String y, String z) {
        return "G4Transform3D(G4RotationMatrix().rotateX(" + c(rx) + ").rotateY(" + c(ry) + ").rotateZ("
            + c(rz) + "), G4ThreeVector(" + c(x) + ", " + c(y) + ", " + c(z) + "))";
    }

    @Override
    public Solid copy(String newName) {
        final BooleanSolid s = new BooleanSolid(newName, op, first, second);
        copyParamsTo(s);
        return s;
    }
}

package com.sphere.core.geant4;

/**
 * A placement of a logical volume inside a mother: G4PVPlacement, or
 * G4PVReplica when a replica is set.
 *
 * Position and rotation are expressions. The rotation follows GDML's physvol:
 * angles (rx, ry, rz) make R = Rz Ry Rx, and the daughter-to-mother map is
 * p_mother = R^-1 p_daughter + t, which is G4PVPlacement(R, t).
 */
public final class PhysicalVolume {

    /** G4PVReplica's slicing: the axis, the copy count, their width and offset. */
    public static final class Replica {
        public enum Axis { kXAxis, kYAxis, kZAxis, kRho, kPhi }

        Axis axis = Axis.kZAxis;
        String number = "2";
        String width = "10*mm";
        String offset = "0*mm";
        int n;
        double w, off;

        public Axis axis() {
            return axis;
        }

        public String number() {
            return number;
        }

        public String width() {
            return width;
        }

        public String offset() {
            return offset;
        }

        public int count() {
            return n;
        }

        public double widthValue() {
            return w;
        }

        public double offsetValue() {
            return off;
        }

        public void setAxis(Axis a) {
            this.axis = a;
        }

        public void setNumber(String e) {
            this.number = e;
        }

        public void setWidth(String e) {
            this.width = e;
        }

        public void setOffset(String e) {
            this.offset = e;
        }
    }

    String name;
    LogicalVolume logical;
    LogicalVolume mother;
    int copyNumber;
    String x = "0*mm", y = "0*mm", z = "0*mm";
    String rx = "0*deg", ry = "0*deg", rz = "0*deg";
    /**
     * A mirrored placement: the daughter is mirrored through its own z = 0
     * plane, then rotated and moved -- GDML's physvol scale (1, 1, -1),
     * G4ReflectionFactory::Place's G4ReflectZ3D, a ROOT matrix with
     * kGeoReflection. Detectors built in halves place one half so.
     */
    boolean reflected;
    Replica replica;

    double vx, vy, vz, vrx, vry, vrz;
    Xform transform = Xform.IDENTITY;
    String error;

    public PhysicalVolume(String name, LogicalVolume logical, LogicalVolume mother) {
        this.name = name;
        this.logical = logical;
        this.mother = mother;
    }

    public String name() {
        return name;
    }

    public LogicalVolume logical() {
        return logical;
    }

    public LogicalVolume mother() {
        return mother;
    }

    public int copyNumber() {
        return copyNumber;
    }

    public Replica replica() {
        return replica;
    }

    public boolean isReplica() {
        return replica != null;
    }

    public boolean reflected() {
        return reflected;
    }

    public void setReflected(boolean mirrored) {
        this.reflected = mirrored;
    }

    public String error() {
        return error;
    }

    public String[] positionExprs() {
        return new String[] {x, y, z};
    }

    public String[] rotationExprs() {
        return new String[] {rx, ry, rz};
    }

    public Vec3 position() {
        return new Vec3(vx, vy, vz);
    }

    /** GDML rotation angles in radians. */
    public Vec3 rotation() {
        return new Vec3(vrx, vry, vrz);
    }

    public Xform transform() {
        return transform;
    }

    public void setPosition(String ex, String ey, String ez) {
        x = ex;
        y = ey;
        z = ez;
    }

    public void setRotation(String ex, String ey, String ez) {
        rx = ex;
        ry = ey;
        rz = ez;
    }

    public void setCopyNumber(int c) {
        this.copyNumber = c;
    }

    public void setReplica(Replica r) {
        this.replica = r;
    }

    void evaluate(Expr.Scope scope) throws GeometryException {
        error = null;
        try {
            vx = Expr.eval(x, scope);
            vy = Expr.eval(y, scope);
            vz = Expr.eval(z, scope);
            vrx = Expr.eval(rx, scope);
            vry = Expr.eval(ry, scope);
            vrz = Expr.eval(rz, scope);
            transform = Xform.placement(vx, vy, vz, vrx, vry, vrz);
            if (reflected) {
                // G4Transform3D(R_gdml^-1, t) * G4Scale3D(1, 1, -1), as G4GDMLReadStructure::PhysvolRead
                transform = transform.compose(Xform.REFLECT_Z);
            }
            if (replica != null) {
                replica.n = (int) Math.round(Expr.eval(replica.number, scope));
                replica.w = Expr.eval(replica.width, scope);
                replica.off = Expr.eval(replica.offset, scope);
                if (replica.n < 1) {
                    throw new GeometryException("replica " + name + ": needs at least one copy");
                }
                if (replica.w <= 0) {
                    throw new GeometryException("replica " + name + ": width must be positive");
                }
            }
        } catch (GeometryException e) {
            error = name + ": " + e.getMessage();
            throw new GeometryException(error);
        }
    }

    /** Number of copies this placement makes. */
    public int copies() {
        return replica == null ? 1 : Math.max(1, replica.n);
    }

    /**
     * The daughter-to-mother transform of one copy. For a replica, as
     * G4ReplicaNavigation::ComputeTransformation: Cartesian copies centred on
     * the mother, phi copies rotated by -(offset + width (i + 1/2)).
     */
    public Xform copyTransform(int i) {
        if (replica == null) {
            return transform;
        }
        final double w = replica.w, n = replica.n;
        return switch (replica.axis) {
            case kXAxis -> Xform.translation(-w * 0.5 * (n - 1) + w * i, 0, 0);
            case kYAxis -> Xform.translation(0, -w * 0.5 * (n - 1) + w * i, 0);
            case kZAxis -> Xform.translation(0, 0, -w * 0.5 * (n - 1) + w * i);
            case kPhi -> {
                final double a = replica.off + w * (i + 0.5);
                yield new Xform(Xform.axisAngle(Vec3.Z, a), 0, 0, 0);
            }
            case kRho -> Xform.IDENTITY;
        };
    }

    @Override
    public String toString() {
        return name;
    }
}

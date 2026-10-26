package com.sphere.core.geant4;

/** An immutable 3-vector in Geant4's internal units (mm). */
public record Vec3(double x, double y, double z) {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 X = new Vec3(1, 0, 0);
    public static final Vec3 Y = new Vec3(0, 1, 0);
    public static final Vec3 Z = new Vec3(0, 0, 1);

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 mul(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public Vec3 addScaled(Vec3 o, double s) {
        return new Vec3(x + o.x * s, y + o.y * s, z + o.z * s);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double length2() {
        return x * x + y * y + z * z;
    }

    public Vec3 unit() {
        final double l = length();
        return l > 0 ? new Vec3(x / l, y / l, z / l) : this;
    }

    public double distance(Vec3 o) {
        return sub(o).length();
    }

    public Vec3 min(Vec3 o) {
        return new Vec3(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
    }

    public Vec3 max(Vec3 o) {
        return new Vec3(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
    }

    public double get(int axis) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    public static Vec3 axis(int i) {
        return i == 0 ? X : i == 1 ? Y : Z;
    }

    /** Any unit vector orthogonal to this one. */
    public Vec3 orthogonal() {
        final Vec3 u = unit();
        final Vec3 a = Math.abs(u.x) < 0.9 ? X : Y;
        return u.cross(a).unit();
    }

    @Override
    public String toString() {
        return "(" + Units.num(x) + ", " + Units.num(y) + ", " + Units.num(z) + ")";
    }
}

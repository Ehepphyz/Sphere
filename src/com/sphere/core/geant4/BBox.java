package com.sphere.core.geant4;

/** An axis-aligned bounding box. */
public record BBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public static final BBox EMPTY = new BBox(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Double.NEGATIVE_INFINITY);

    public static BBox of(Vec3 a, Vec3 b) {
        return new BBox(Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                        Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
    }

    public static BBox centered(double hx, double hy, double hz) {
        return new BBox(-hx, -hy, -hz, hx, hy, hz);
    }

    public boolean isEmpty() {
        return minX > maxX || minY > maxY || minZ > maxZ;
    }

    public BBox union(BBox o) {
        if (isEmpty()) {
            return o;
        }
        if (o.isEmpty()) {
            return this;
        }
        return new BBox(Math.min(minX, o.minX), Math.min(minY, o.minY), Math.min(minZ, o.minZ),
                        Math.max(maxX, o.maxX), Math.max(maxY, o.maxY), Math.max(maxZ, o.maxZ));
    }

    public BBox intersect(BBox o) {
        return new BBox(Math.max(minX, o.minX), Math.max(minY, o.minY), Math.max(minZ, o.minZ),
                        Math.min(maxX, o.maxX), Math.min(maxY, o.maxY), Math.min(maxZ, o.maxZ));
    }

    public BBox include(Vec3 p) {
        return union(new BBox(p.x(), p.y(), p.z(), p.x(), p.y(), p.z()));
    }

    public BBox grow(double d) {
        return new BBox(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d);
    }

    public boolean overlaps(BBox o) {
        return minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY
            && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(BBox o) {
        return o.minX >= minX && o.maxX <= maxX && o.minY >= minY && o.maxY <= maxY
            && o.minZ >= minZ && o.maxZ <= maxZ;
    }

    public Vec3 center() {
        return new Vec3((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
    }

    public Vec3 halfSize() {
        return new Vec3((maxX - minX) / 2, (maxY - minY) / 2, (maxZ - minZ) / 2);
    }

    public Vec3 size() {
        return new Vec3(maxX - minX, maxY - minY, maxZ - minZ);
    }

    public Vec3 min() {
        return new Vec3(minX, minY, minZ);
    }

    public Vec3 max() {
        return new Vec3(maxX, maxY, maxZ);
    }

    public double diagonal() {
        return isEmpty() ? 0 : size().length();
    }

    public double volume() {
        return isEmpty() ? 0 : (maxX - minX) * (maxY - minY) * (maxZ - minZ);
    }

    /** Slab test: the entry and exit distances of a ray, or null when it misses. */
    public double[] intersectRay(Vec3 o, Vec3 d, double tmax) {
        double t0 = 0, t1 = tmax;
        for (int a = 0; a < 3; a++) {
            final double oa = o.get(a), da = d.get(a);
            final double lo = a == 0 ? minX : a == 1 ? minY : minZ;
            final double hi = a == 0 ? maxX : a == 1 ? maxY : maxZ;
            if (Math.abs(da) < 1e-300) {
                if (oa < lo || oa > hi) {
                    return null;
                }
                continue;
            }
            double tn = (lo - oa) / da, tf = (hi - oa) / da;
            if (tn > tf) {
                final double s = tn;
                tn = tf;
                tf = s;
            }
            t0 = Math.max(t0, tn);
            t1 = Math.min(t1, tf);
            if (t0 > t1) {
                return null;
            }
        }
        return new double[] {t0, t1};
    }
}

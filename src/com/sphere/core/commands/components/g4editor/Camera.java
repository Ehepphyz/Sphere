package com.sphere.components.g4editor;

import com.sphere.core.geant4.BBox;
import com.sphere.core.geant4.Vec3;

/**
 * The viewer: an orbit camera around a target, orthographic by default as in
 * a CAD program, perspective on request. Orientation is an orthonormal frame
 * (right, up, back) so any view is reachable; orbiting turns about the
 * document's up axis like FreeCAD's turntable.
 */
final class Camera {

    Vec3 target = Vec3.ZERO;
    double distance = 3000;
    // Orthonormal frame: x = right, y = up, z = back (from target toward the eye)
    Vec3 right = new Vec3(1, 0, 0), up = new Vec3(0, 0, 1), back = new Vec3(0, -1, 0);
    boolean perspective;
    double fovY = Math.toRadians(30);
    int width = 800, height = 600;
    Vec3 worldUp = Vec3.Z;

    Camera copy() {
        final Camera c = new Camera();
        c.target = target;
        c.distance = distance;
        c.right = right;
        c.up = up;
        c.back = back;
        c.perspective = perspective;
        c.fovY = fovY;
        c.width = width;
        c.height = height;
        c.worldUp = worldUp;
        return c;
    }

    Vec3 eye() {
        return target.add(back.mul(distance));
    }

    /** Half the visible height at the target, in mm. */
    double halfHeight() {
        return distance * Math.tan(fovY / 2);
    }

    /** World units per pixel at the target depth. */
    double pixelSize() {
        return 2 * halfHeight() / Math.max(1, height);
    }

    double near() {
        return Math.max(distance * 1e-3, 1e-3);
    }

    /** The 4x4 view-projection matrix, row-major, mapping world to clip space. */
    double[] viewProjection() {
        final Vec3 e = eye();
        // View: rows are right, up, back; translation -R e
        final double[] v = {
            right.x(), right.y(), right.z(), -right.dot(e),
            up.x(), up.y(), up.z(), -up.dot(e),
            back.x(), back.y(), back.z(), -back.dot(e),
            0, 0, 0, 1};
        final double aspect = (double) width / Math.max(1, height);
        final double n = near(), f = distance * 200 + 1e6;
        final double[] p = new double[16];
        if (perspective) {
            final double t = 1 / Math.tan(fovY / 2);
            p[0] = t / aspect;
            p[5] = t;
            p[10] = -(f + n) / (f - n);
            p[11] = -2 * f * n / (f - n);
            p[14] = -1;
        } else {
            final double hh = halfHeight(), hw = hh * aspect;
            final double fo = distance * 100 + 1e6;
            p[0] = 1 / hw;
            p[5] = 1 / hh;
            p[10] = -2 / (fo + distance * 100 + 1e6);
            p[11] = 0;
            p[15] = 1;
        }
        return mul(p, v);
    }

    static double[] mul(double[] a, double[] b) {
        final double[] m = new double[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                double s = 0;
                for (int k = 0; k < 4; k++) {
                    s += a[i * 4 + k] * b[k * 4 + j];
                }
                m[i * 4 + j] = s;
            }
        }
        return m;
    }

    /** World point to screen pixel (x, y) and view depth; null behind the eye. */
    double[] project(Vec3 p) {
        final Vec3 d = p.sub(eye());
        final double vz = -d.dot(back);
        final double vx = d.dot(right), vy = d.dot(up);
        double sx, sy;
        if (perspective) {
            if (vz <= near()) {
                return null;
            }
            final double t = 1 / Math.tan(fovY / 2);
            sx = vx * t / vz;
            sy = vy * t / vz;
        } else {
            sx = vx / halfHeight();
            sy = vy / halfHeight();
        }
        final double aspect = (double) width / Math.max(1, height);
        return new double[] {(sx / aspect * 0.5 + 0.5) * width, (0.5 - sy * 0.5) * height, vz};
    }

    /** The ray under a pixel: origin and unit direction. */
    Vec3[] ray(double px, double py) {
        final double aspect = (double) width / Math.max(1, height);
        final double nx = (px / width - 0.5) * 2 * aspect, ny = (0.5 - py / height) * 2;
        if (perspective) {
            final double t = Math.tan(fovY / 2);
            final Vec3 dir = right.mul(nx * t).add(up.mul(ny * t)).sub(back).unit();
            return new Vec3[] {eye(), dir};
        }
        final double hh = halfHeight();
        final Vec3 o = target.add(right.mul(nx * hh)).add(up.mul(ny * hh)).add(back.mul(distance * 50 + 1e5));
        return new Vec3[] {o, back.mul(-1)};
    }

    /** Turntable orbit: yaw about the world up axis, pitch about the camera's right axis. */
    void orbit(double yaw, double pitch) {
        rotate(worldUp, yaw);
        // Keep away from the poles, where the turntable flips
        final double cur = Math.asin(Math.max(-1, Math.min(1, back.dot(worldUp))));
        final double lim = Math.toRadians(89.5);
        final double np = Math.max(-lim, Math.min(lim, cur + pitch));
        rotate(right, -(np - cur));
    }

    /** Free rotation about any axis through the target. */
    void rotate(Vec3 axis, double a) {
        right = rot(right, axis, a).unit();
        back = rot(back, axis, a).unit();
        up = back.cross(right).unit();
        right = up.cross(back).unit();
    }

    static Vec3 rot(Vec3 v, Vec3 axis, double a) {
        final Vec3 k = axis.unit();
        final double c = Math.cos(a), s = Math.sin(a);
        return v.mul(c).add(k.cross(v).mul(s)).add(k.mul(k.dot(v) * (1 - c)));
    }

    void pan(double dxPixels, double dyPixels) {
        final double s = pixelSize();
        target = target.sub(right.mul(dxPixels * s)).add(up.mul(dyPixels * s));
    }

    /** Zooms by a factor, keeping the world point under the pixel fixed. */
    void zoomAt(double factor, double px, double py) {
        final double aspect = (double) width / Math.max(1, height);
        final double nx = (px / width - 0.5) * 2 * aspect, ny = (0.5 - py / height) * 2;
        final double hh = halfHeight();
        final Vec3 under = target.add(right.mul(nx * hh)).add(up.mul(ny * hh));
        distance = Math.max(1e-3, Math.min(1e9, distance * factor));
        final double hh2 = halfHeight();
        final Vec3 under2 = target.add(right.mul(nx * hh2)).add(up.mul(ny * hh2));
        target = target.add(under.sub(under2));
    }

    /** Looks along a direction (from eye toward target) with an up hint. */
    void look(Vec3 forward, Vec3 upHint) {
        back = forward.mul(-1).unit();
        right = upHint.cross(back).unit();
        if (right.length2() < 1e-12) {
            right = back.orthogonal();
        }
        up = back.cross(right).unit();
    }

    /** Frames a box so it fills most of the view. */
    void fit(BBox b) {
        if (b == null || b.isEmpty()) {
            return;
        }
        target = b.center();
        final double r = Math.max(b.diagonal() / 2, 1e-3);
        final double aspect = (double) width / Math.max(1, height);
        final double hh = r * 1.08 / Math.min(1, aspect);
        distance = hh / Math.tan(fovY / 2);
    }

    /** Interpolates two cameras for animated view changes (normalized lerp of the frames). */
    static Camera blend(Camera a, Camera b, double t) {
        final Camera c = a.copy();
        final double s = t * t * (3 - 2 * t);
        c.target = a.target.mul(1 - s).add(b.target.mul(s));
        c.distance = Math.exp(Math.log(a.distance) * (1 - s) + Math.log(b.distance) * s);
        Vec3 bk = a.back.mul(1 - s).add(b.back.mul(s));
        if (bk.length2() < 1e-6) {
            bk = a.back.add(a.right.mul(0.01)).mul(1 - s).add(b.back.mul(s));
        }
        Vec3 u = a.up.mul(1 - s).add(b.up.mul(s));
        bk = bk.unit();
        Vec3 r = u.cross(bk);
        if (r.length2() < 1e-9) {
            r = bk.orthogonal();
        }
        r = r.unit();
        u = bk.cross(r).unit();
        c.back = bk;
        c.up = u;
        c.right = r;
        return c;
    }
}

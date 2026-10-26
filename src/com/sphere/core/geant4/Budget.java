package com.sphere.core.geant4;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Material budget: radiation and interaction lengths crossed by geantinos
 * fired from a point, the scan a detector paper shows as X/X0 against
 * pseudorapidity. Geant4 users run it with G4Geantino and a stepping action;
 * here it runs on the editor's own navigator, in parallel, in a second.
 */
public final class Budget {

    public enum Axis { ETA, THETA, COS_THETA }

    /** A scan over a direction grid. */
    public record Map2D(Axis axis, double aMin, double aMax, int na, double phiMin, double phiMax, int nphi,
                        double[] x0, double[] lambda, double[] path) {
        public double x0(int ia, int ip) {
            return x0[ip * na + ia];
        }

        public double lambda(int ia, int ip) {
            return lambda[ip * na + ia];
        }

        public double maxX0() {
            double m = 0;
            for (double v : x0) {
                m = Math.max(m, v);
            }
            return m;
        }

        public double maxLambda() {
            double m = 0;
            for (double v : lambda) {
                m = Math.max(m, v);
            }
            return m;
        }

        /** X/X0 averaged over phi, per bin of the polar axis. */
        public double[] profileX0() {
            final double[] out = new double[na];
            for (int ia = 0; ia < na; ia++) {
                double s = 0;
                for (int ip = 0; ip < nphi; ip++) {
                    s += x0(ia, ip);
                }
                out[ia] = s / nphi;
            }
            return out;
        }

        public double[] profileLambda() {
            final double[] out = new double[na];
            for (int ia = 0; ia < na; ia++) {
                double s = 0;
                for (int ip = 0; ip < nphi; ip++) {
                    s += lambda(ia, ip);
                }
                out[ia] = s / nphi;
            }
            return out;
        }

        public double axisValue(int ia) {
            return aMin + (aMax - aMin) * (ia + 0.5) / na;
        }
    }

    /** What one ray crossed, per volume and per material. */
    public record RayReport(double x0, double lambda, double path, Map<String, double[]> byVolume,
                            Map<String, double[]> byMaterial, List<Tracer.Segment> segments) {
    }

    private Budget() {
    }

    public static Vec3 direction(Axis axis, double a, double phi) {
        final double theta = switch (axis) {
            case ETA -> 2 * Math.atan(Math.exp(-a));
            case THETA -> a;
            case COS_THETA -> Math.acos(Math.max(-1, Math.min(1, a)));
        };
        final double st = Math.sin(theta);
        return new Vec3(st * Math.cos(phi), st * Math.sin(phi), Math.cos(theta));
    }

    /**
     * Scans na × nphi directions from an origin up to the world's edge. Every
     * ray goes on its own task; with 4 cores a 100 × 64 map of a tracker takes
     * well under a second.
     */
    public static Map2D scan(Geometry g, Vec3 origin, Axis axis, double aMin, double aMax, int na,
                             double phiMin, double phiMax, int nphi) {
        final double[] x0 = new double[na * nphi], lam = new double[na * nphi], path = new double[na * nphi];
        final MaterialTable mt = g.materials;
        final double maxLen = g.world == null || g.world.solid == null ? 1e9 : g.world.solid.extent().diagonal() * 2;
        IntStream.range(0, na * nphi).parallel().forEach(k -> {
            final int ia = k % na, ip = k / na;
            final double a = aMin + (aMax - aMin) * (ia + 0.5) / na;
            final double phi = phiMin + (phiMax - phiMin) * (ip + 0.5) / nphi;
            final double[] acc = new double[3];
            new Tracer(g).trace(origin, direction(axis, a, phi), maxLen, seg -> {
                final Material m = mt.material(seg.lv().material);
                if (m != null) {
                    acc[0] += seg.length() / m.radiationLength();
                    acc[1] += seg.length() / m.nuclearInteractionLength();
                }
                acc[2] += seg.length();
            });
            x0[k] = acc[0];
            lam[k] = acc[1];
            path[k] = acc[2];
        });
        return new Map2D(axis, aMin, aMax, na, phiMin, phiMax, nphi, x0, lam, path);
    }

    /** One ray, broken down by volume and by material, world excluded when asked. */
    public static RayReport ray(Geometry g, Vec3 origin, Vec3 dir, boolean skipWorld) {
        final double maxLen = g.world == null || g.world.solid == null ? 1e9 : g.world.solid.extent().diagonal() * 2;
        final List<Tracer.Segment> segs = new Tracer(g).segments(origin, dir, maxLen);
        final Map<String, double[]> byVolume = new LinkedHashMap<>(), byMaterial = new LinkedHashMap<>();
        double x0 = 0, lam = 0, path = 0;
        for (Tracer.Segment s : segs) {
            if (skipWorld && s.lv() == g.world) {
                continue;
            }
            final Material m = g.materials.material(s.lv().material);
            if (m == null) {
                continue;
            }
            final double a = s.length() / m.radiationLength(), b = s.length() / m.nuclearInteractionLength();
            x0 += a;
            lam += b;
            path += s.length();
            byVolume.computeIfAbsent(s.lv().name, k -> new double[3]);
            final double[] v = byVolume.get(s.lv().name);
            v[0] += a;
            v[1] += b;
            v[2] += s.length();
            byMaterial.computeIfAbsent(m.name(), k -> new double[3]);
            final double[] w = byMaterial.get(m.name());
            w[0] += a;
            w[1] += b;
            w[2] += s.length();
        }
        return new RayReport(x0, lam, path, byVolume, byMaterial, segs);
    }
}

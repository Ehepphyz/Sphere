package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.List;

/**
 * The copies of a G4PVDivision, computed as Geant4's division
 * parameterisations compute them (G4ParameterisationBoxX ...
 * G4ParameterisationPolyhedraZ, source/geometry/divisions): for each copy,
 * the solid it is cut to and where it stands in the mother.
 *
 * The number of copies and their width follow G4VDivisionParameterisation:
 * given the number, the width is (extent - offset) / n; given the width, the
 * number is the whole part of (extent - offset) / width; given both, both are
 * taken as they are. The quirks of Geant4's own code are kept (a cone's phi
 * offset counted twice, a trd's y width taken at -z), so the drawing is what
 * Geant4 builds, with one exception: Geant4 master accumulates the rotation of
 * phi divisions in one matrix shared by all copies, where each copy is meant
 * to be turned by its own angle; the copies are drawn as meant.
 */
final class Divisions {

    private Divisions() {
    }

    enum Kind { NDIV, WIDTH, NDIV_AND_WIDTH }

    /** One copy: its solid, its place in the mother, the angle its frame is turned by about z. */
    record Copy(Solid solid, double x, double y, double z, double frameRotZ) {
    }

    record Result(List<Copy> copies, String error) {
        static Result error(String e) {
            return new Result(List.of(), e);
        }
    }

    /**
     * @param mother   the divided volume's solid
     * @param daughter the solid of the volume divided into: a G4Trd mother divided along x or y gives G4Trap
     *                 copies when its daughter is a G4Trap
     * @param axis     kXAxis, kYAxis, kZAxis, kRho or kPhi
     * @param name     the copies' solids are named name_0, name_1...
     */
    static Result divide(Solid mother, Solid daughter, String axis, int nDiv, double width, double offset, Kind kind,
                         String name) {
        try {
            return switch (mother.tag()) {
                case "box" -> box(mother, axis, nDiv, width, offset, kind, name);
                case "tube" -> tubs(mother, axis, nDiv, width, offset, kind, name);
                case "cone" -> cons(mother, axis, nDiv, width, offset, kind, name);
                case "trd" -> trd(mother, daughter, axis, nDiv, width, offset, kind, name);
                case "para" -> para(mother, axis, nDiv, width, offset, kind, name);
                case "polycone" -> planes(mother, false, axis, nDiv, width, offset, kind, name);
                case "polyhedra" -> planes(mother, true, axis, nDiv, width, offset, kind, name);
                default -> Result.error("Geant4 divides G4Box, G4Tubs, G4Cons, G4Trd, G4Para, G4Polycone and G4Polyhedra,"
                    + " not " + mother.g4Class());
            };
        } catch (GeometryException | RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Counting
    // ------------------------------------------------------------------

    /** {number, width} as G4VDivisionParameterisation's constructors settle them. */
    private static double[] count(double extent, int nDiv, double width, double offset, Kind kind) {
        int n = nDiv;
        double w = width;
        if (kind == Kind.WIDTH) {
            n = (int) ((extent - offset) / width);
        } else if (kind == Kind.NDIV) {
            w = (extent - offset) / nDiv;
        }
        return new double[] {n, w};
    }

    private static Result counted(int n, double w, List<Copy> out, String what) {
        if (n < 1) {
            return Result.error("no copy: " + what + " leaves room for none (Geant4 stops: Illegal number of replicas)");
        }
        if (w <= 0) {
            return Result.error("the width must be positive (Geant4 stops)");
        }
        return new Result(out, null);
    }

    // ------------------------------------------------------------------
    // Values of the mother, and solids made
    // ------------------------------------------------------------------

    private static double val(Solid s, String key) throws GeometryException {
        return Expr.eval(s.param(key).expr(), Expr.NO_VARIABLES);
    }

    private static double cell(String expr) throws GeometryException {
        return Expr.eval(expr, Expr.NO_VARIABLES);
    }

    private static String len(double mm) {
        return CppGeometry.fmt(mm) + "*mm";
    }

    private static String ang(double rad) {
        return CppGeometry.fmt(Math.toDegrees(rad)) + "*deg";
    }

    /** A solid of the mother's kind (or another), its parameters set from {key, value, 'L'|'A'|'N'} triples. */
    private static Solid make(String tag, String name, Object... spec) {
        final Solid s = Solids.create(tag, name);
        for (int k = 0; k < spec.length; k += 3) {
            final double v = (Double) spec[k + 1];
            final char kind = (Character) spec[k + 2];
            s.set((String) spec[k], kind == 'L' ? len(v) : kind == 'A' ? ang(v) : CppGeometry.fmt(v));
        }
        return s;
    }

    // ------------------------------------------------------------------
    // G4Box
    // ------------------------------------------------------------------

    private static Result box(Solid m, String axis, int nDiv, double width, double offset, Kind kind, String name)
        throws GeometryException {
        final double hx = val(m, "x") / 2, hy = val(m, "y") / 2, hz = val(m, "z") / 2;
        final int a = switch (axis) {
            case "kXAxis" -> 0;
            case "kYAxis" -> 1;
            case "kZAxis" -> 2;
            default -> -1;
        };
        if (a < 0) {
            return Result.error("a G4Box is divided along x, y or z, not " + axis);
        }
        final double h = a == 0 ? hx : a == 1 ? hy : hz;
        final double[] c = count(2 * h, nDiv, width, offset, kind);
        final int n = (int) c[0];
        final double w = c[1];
        final List<Copy> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final double posi = -h + offset + (i + 0.5) * w;
            final Solid s = make("box", name + "_" + i, "x", a == 0 ? w : 2 * hx, 'L', "y", a == 1 ? w : 2 * hy, 'L',
                "z", a == 2 ? w : 2 * hz, 'L');
            out.add(new Copy(s, a == 0 ? posi : 0, a == 1 ? posi : 0, a == 2 ? posi : 0, 0));
        }
        return counted(n, w, out, "the box");
    }

    // ------------------------------------------------------------------
    // G4Tubs
    // ------------------------------------------------------------------

    private static Result tubs(Solid m, String axis, int nDiv, double width, double offset, Kind kind, String name)
        throws GeometryException {
        final double rmin = val(m, "rmin"), rmax = val(m, "rmax"), hz = val(m, "z") / 2;
        final double sphi = val(m, "startphi"), dphi = val(m, "deltaphi");
        final List<Copy> out = new ArrayList<>();
        switch (axis) {
            case "kRho" -> {
                final double[] c = count(rmax - rmin, nDiv, width, offset, kind);
                final int n = (int) c[0];
                for (int i = 0; i < n; i++) {
                    out.add(new Copy(make("tube", name + "_" + i, "rmin", rmin + offset + c[1] * i, 'L',
                        "rmax", rmin + offset + c[1] * (i + 1), 'L', "z", 2 * hz, 'L', "startphi", sphi, 'A',
                        "deltaphi", dphi, 'A'), 0, 0, 0, 0));
                }
                return counted(n, c[1], out, "the tube's thickness");
            }
            case "kPhi" -> {
                final double[] c = count(dphi, nDiv, width, offset, kind);
                final int n = (int) c[0];
                final Solid s = make("tube", name + "_phi", "rmin", rmin, 'L', "rmax", rmax, 'L', "z", 2 * hz, 'L',
                    "startphi", sphi, 'A', "deltaphi", c[1], 'A');
                for (int i = 0; i < n; i++) {
                    out.add(new Copy(s, 0, 0, 0, -(offset + i * c[1])));
                }
                return counted(n, c[1], out, "the tube's phi range");
            }
            case "kZAxis" -> {
                final double[] c = count(2 * hz, nDiv, width, offset, kind);
                final int n = (int) c[0];
                final Solid s = make("tube", name + "_z", "rmin", rmin, 'L', "rmax", rmax, 'L', "z", c[1], 'L',
                    "startphi", sphi, 'A', "deltaphi", dphi, 'A');
                for (int i = 0; i < n; i++) {
                    out.add(new Copy(s, 0, 0, -hz + offset + c[1] / 2 + i * c[1], 0));
                }
                return counted(n, c[1], out, "the tube's length");
            }
            default -> {
                return Result.error("a G4Tubs is divided along kRho, kPhi or kZAxis, not " + axis);
            }
        }
    }

    // ------------------------------------------------------------------
    // G4Cons
    // ------------------------------------------------------------------

    private static Result cons(Solid m, String axis, int nDiv, double width, double offset, Kind kind, String name)
        throws GeometryException {
        final double rmin1 = val(m, "rmin1"), rmax1 = val(m, "rmax1"), rmin2 = val(m, "rmin2"), rmax2 = val(m, "rmax2");
        final double hz = val(m, "z") / 2, sphi = val(m, "startphi"), dphi = val(m, "deltaphi");
        final List<Copy> out = new ArrayList<>();
        switch (axis) {
            case "kRho" -> {
                final double[] c = count(rmax1 - rmin1, nDiv, width, offset, kind);
                final int n = (int) c[0];
                final double wPlus = (rmax2 - rmin2 - offset) / n;
                for (int i = 0; i < n; i++) {
                    out.add(new Copy(make("cone", name + "_" + i, "rmin1", rmin1 + offset + c[1] * i, 'L',
                        "rmax1", rmin1 + offset + c[1] * (i + 1), 'L', "rmin2", rmin2 + offset + wPlus * i, 'L',
                        "rmax2", rmin2 + offset + wPlus * (i + 1), 'L', "z", 2 * hz, 'L', "startphi", sphi, 'A',
                        "deltaphi", dphi, 'A'), 0, 0, 0, 0));
                }
                return counted(n, c[1], out, "the cone's thickness");
            }
            case "kPhi" -> {
                final double[] c = count(dphi, nDiv, width, offset, kind);
                final int n = (int) c[0];
                // G4ParameterisationConsPhi puts the offset in the start angle and in the rotation both
                final Solid s = make("cone", name + "_phi", "rmin1", rmin1, 'L', "rmax1", rmax1, 'L', "rmin2", rmin2, 'L',
                    "rmax2", rmax2, 'L', "z", 2 * hz, 'L', "startphi", offset + sphi, 'A', "deltaphi", c[1], 'A');
                for (int i = 0; i < n; i++) {
                    out.add(new Copy(s, 0, 0, 0, -(offset + i * c[1])));
                }
                return counted(n, c[1], out, "the cone's phi range");
            }
            case "kZAxis" -> {
                final double[] c = count(2 * hz, nDiv, width, offset, kind);
                final int n = (int) c[0];
                final double w = c[1];
                final double aIn = (rmin2 - rmin1) / (2 * hz), bIn = (rmin2 + rmin1) / 2;
                final double aOut = (rmax2 - rmax1) / (2 * hz), bOut = (rmax2 + rmax1) / 2;
                for (int i = 0; i < n; i++) {
                    final double zm = -hz + offset + w * i, zp = -hz + offset + w * (i + 1);
                    out.add(new Copy(make("cone", name + "_" + i, "rmin1", aIn * zm + bIn, 'L', "rmax1", aOut * zm + bOut, 'L',
                        "rmin2", aIn * zp + bIn, 'L', "rmax2", aOut * zp + bOut, 'L', "z", w, 'L', "startphi", sphi, 'A',
                        "deltaphi", dphi, 'A'), 0, 0, -hz + offset + w / 2 + i * w, 0));
                }
                return counted(n, w, out, "the cone's length");
            }
            default -> {
                return Result.error("a G4Cons is divided along kRho, kPhi or kZAxis, not " + axis);
            }
        }
    }

    // ------------------------------------------------------------------
    // G4Trd
    // ------------------------------------------------------------------

    private static Result trd(Solid m, Solid daughter, String axis, int nDiv, double width, double offset, Kind kind,
                              String name) throws GeometryException {
        final double dx1 = val(m, "x1") / 2, dx2 = val(m, "x2") / 2, dy1 = val(m, "y1") / 2, dy2 = val(m, "y2") / 2;
        final double dz = val(m, "z") / 2;
        final boolean toTrap = daughter != null && daughter.tag().equals("trap");
        final List<Copy> out = new ArrayList<>();
        switch (axis) {
            case "kXAxis" -> {
                final double[] c = count(dx1 + dx2, nDiv, width, offset, kind);
                final int n = (int) c[0];
                final double w = c[1], mdx = (dx1 + dx2) / 2;
                for (int i = 0; i < n; i++) {
                    final Solid s;
                    if (toTrap) {
                        final double ratio = (dx2 - dx1) / (dx2 + dx1), change = ratio * w;
                        final double c1 = -dx1 + (offset - ratio * offset / 2) + (i + 0.5) * (w - change);
                        final double c2 = -dx2 + (offset + ratio * offset / 2) + (i + 0.5) * (w + change);
                        final double alp = Math.atan((c2 - c1) / (dz * 2));
                        final double px1 = w / 2 - change / 2, px2 = w / 2 + change / 2;
                        s = trap(name + "_" + i, dz, alp, 0, dy1, px1, px1, 0, dy2, px2, px2, 0);
                    } else {
                        s = make("trd", name + "_" + i, "x1", w, 'L', "x2", w, 'L', "y1", 2 * dy1, 'L', "y2", 2 * dy2, 'L',
                            "z", 2 * dz, 'L');
                    }
                    out.add(new Copy(s, -mdx + offset + (i + 0.5) * w, 0, 0, 0));
                }
                return counted(n, w, out, "the trd's width");
            }
            case "kYAxis" -> {
                // the number from the -z half-length, the positions from the mean one, as Geant4 does
                final double[] c = count(2 * dy1, nDiv, width, offset, kind);
                final int n = (int) c[0];
                final double w = c[1], mdy = (dy1 + dy2) / 2;
                for (int i = 0; i < n; i++) {
                    final Solid s;
                    if (toTrap) {
                        final double ratio = (dy2 - dy1) / (dy2 + dy1), change = ratio * w;
                        final double c1 = -dy1 + (offset - ratio * offset / 2) + (i + 0.5) * (w - change);
                        final double c2 = -dy2 + (offset + ratio * offset / 2) + (i + 0.5) * (w + change);
                        final double alp = Math.atan((c2 - c1) / (dz * 2));
                        final double py1 = w / 2 - change / 2, py2 = w / 2 + change / 2;
                        s = trap(name + "_" + i, dz, alp, Math.PI / 2, py1, dx1, dx1, 0, py2, dx2, dx2, 0);
                    } else {
                        s = make("trd", name + "_" + i, "x1", 2 * dx1, 'L', "x2", 2 * dx2, 'L', "y1", w, 'L', "y2", w, 'L',
                            "z", 2 * dz, 'L');
                    }
                    out.add(new Copy(s, 0, -mdy + offset + (i + 0.5) * w, 0, 0));
                }
                return counted(n, w, out, "the trd's height");
            }
            case "kZAxis" -> {
                final double[] c = count(2 * dz, nDiv, width, offset, kind);
                final int n = (int) c[0];
                final double w = c[1], ddx = dx2 - dx1, ddy = dy2 - dy1, zl = 2 * dz;
                for (int i = 0; i < n; i++) {
                    final double f0 = (offset + i * w) / zl, f1 = (offset + (i + 1) * w) / zl;
                    out.add(new Copy(make("trd", name + "_" + i, "x1", 2 * (dx1 + ddx * f0), 'L', "x2", 2 * (dx1 + ddx * f1), 'L',
                        "y1", 2 * (dy1 + ddy * f0), 'L', "y2", 2 * (dy1 + ddy * f1), 'L', "z", w, 'L'), 0, 0,
                        -dz + offset + (i + 0.5) * w, 0));
                }
                return counted(n, w, out, "the trd's length");
            }
            default -> {
                return Result.error("a G4Trd is divided along x, y or z, not " + axis);
            }
        }
    }

    /** G4Trap::SetAllParameters(pDz, pTheta, pPhi, pDy1, pDx1, pDx2, pAlp1, pDy2, pDx3, pDx4, pAlp2). */
    private static Solid trap(String name, double dz, double theta, double phi, double dy1, double dx1, double dx2,
                              double alp1, double dy2, double dx3, double dx4, double alp2) {
        return make("trap", name, "z", 2 * dz, 'L', "theta", theta, 'A', "phi", phi, 'A', "y1", 2 * dy1, 'L',
            "x1", 2 * dx1, 'L', "x2", 2 * dx2, 'L', "alpha1", alp1, 'A', "y2", 2 * dy2, 'L', "x3", 2 * dx3, 'L',
            "x4", 2 * dx4, 'L', "alpha2", alp2, 'A');
    }

    // ------------------------------------------------------------------
    // G4Para
    // ------------------------------------------------------------------

    private static Result para(Solid m, String axis, int nDiv, double width, double offset, Kind kind, String name)
        throws GeometryException {
        final double hx = val(m, "x") / 2, hy = val(m, "y") / 2, hz = val(m, "z") / 2;
        final double alpha = val(m, "alpha"), theta = val(m, "theta"), phi = val(m, "phi");
        final List<Copy> out = new ArrayList<>();
        final int a = switch (axis) {
            case "kXAxis" -> 0;
            case "kYAxis" -> 1;
            case "kZAxis" -> 2;
            default -> -1;
        };
        if (a < 0) {
            return Result.error("a G4Para is divided along x, y or z, not " + axis);
        }
        final double h = a == 0 ? hx : a == 1 ? hy : hz;
        final double[] c = count(2 * h, nDiv, width, offset, kind);
        final int n = (int) c[0];
        final double w = c[1];
        final Solid s = make("para", name + "_" + axis, "x", a == 0 ? w : 2 * hx, 'L', "y", a == 1 ? w : 2 * hy, 'L',
            "z", a == 2 ? w : 2 * hz, 'L', "alpha", alpha, 'A', "theta", theta, 'A', "phi", phi, 'A');
        final double tth = Math.tan(theta);
        for (int i = 0; i < n; i++) {
            final double posi = -h + offset + (i + 0.5) * w;
            switch (a) {
                case 0 -> out.add(new Copy(s, posi, 0, 0, 0));
                case 1 -> out.add(new Copy(s, posi * Math.tan(alpha), posi, 0, 0));
                default -> out.add(new Copy(s, tth * Math.cos(phi) * posi, tth * Math.sin(phi) * posi, posi, 0));
            }
        }
        return counted(n, w, out, "the para");
    }

    // ------------------------------------------------------------------
    // G4Polycone and G4Polyhedra, by their z planes
    // ------------------------------------------------------------------

    /**
     * Geant4 divides them on their original planes, a G4Polyhedra's radii taken at the corners (the planes'
     * distances to the sides divided by cos(step / 2)); the copies are written back as planes at the sides.
     */
    private static Result planes(Solid m, boolean pgon, String axis, int nDiv, double width, double offset, Kind kind,
                                 String name) throws GeometryException {
        final Solid.Table t = m.table("zplane");
        final int nz = t.size();
        final double[] zs = new double[nz], rmin = new double[nz], rmax = new double[nz];
        final double sphi = val(m, "startphi"), dphi = val(m, "deltaphi");
        final int sides = pgon ? (int) Math.round(val(m, "numsides")) : 0;
        final double toCorner = pgon ? 1 / Math.cos(dphi / sides / 2) : 1;
        for (int i = 0; i < nz; i++) {
            final String[] row = t.rows().get(i);
            rmin[i] = cell(row[0]) * toCorner;
            rmax[i] = cell(row[1]) * toCorner;
            zs[i] = cell(row[2]);
        }
        final String tag = pgon ? "polyhedra" : "polycone";
        final List<Copy> out = new ArrayList<>();
        switch (axis) {
            case "kRho" -> {
                final double[] c = count(rmax[0] - rmin[0], nDiv, width, offset, kind);
                final int n = (int) c[0];
                for (int i = 0; i < n; i++) {
                    final double[] lo = new double[nz], hi = new double[nz];
                    for (int k = 0; k < nz; k++) {
                        final double w = (rmax[k] - rmin[k] - offset) / n;
                        lo[k] = rmin[k] + offset + w * i;
                        hi[k] = rmin[k] + offset + w * (i + 1);
                    }
                    out.add(new Copy(planesSolid(tag, name + "_" + i, sphi, dphi, sides, zs, lo, hi, pgon ? dphi / sides : 0),
                        0, 0, 0, 0));
                }
                return counted(n, c[1], out, "the thickness at the first plane");
            }
            case "kPhi" -> {
                int n;
                double w;
                if (pgon) {
                    // G4ParameterisationPolyhedraPhi: one side a copy, no offset
                    n = kind == Kind.WIDTH ? sides : nDiv;
                    w = dphi / n;
                    final Solid s = planesSolid(tag, name + "_phi", sphi, w, 1, zs, rmin, rmax, w);
                    for (int i = 0; i < n; i++) {
                        out.add(new Copy(s, 0, 0, 0, -(i * w)));
                    }
                } else {
                    final double[] c = count(dphi, nDiv, width, offset, kind);
                    n = (int) c[0];
                    w = c[1];
                    final Solid s = planesSolid(tag, name + "_phi", sphi, w, 0, zs, rmin, rmax, 0);
                    for (int i = 0; i < n; i++) {
                        out.add(new Copy(s, 0, 0, 0, -(offset + i * w)));
                    }
                }
                return counted(n, w, out, "the phi range");
            }
            case "kZAxis" -> {
                final double step = pgon ? dphi / sides : 0;
                if (kind == Kind.NDIV) {
                    // one copy between two planes of the mother
                    if (nDiv > nz - 1) {
                        return Result.error(nDiv + " copies along z need " + (nDiv + 1) + " planes; the mother has " + nz);
                    }
                    for (int i = 0; i < nDiv; i++) {
                        final double posi = (zs[i] + zs[i + 1]) / 2;
                        out.add(new Copy(planesSolid(tag, name + "_" + i, sphi, dphi, sides,
                            new double[] {zs[i] - posi, zs[i + 1] - posi}, new double[] {rmin[i], rmin[i + 1]},
                            new double[] {rmax[i], rmax[i + 1]}, step), 0, 0, posi, 0));
                    }
                    return counted(nDiv, 1, out, "the planes");
                }
                final double[] c = count(Math.abs(zs[nz - 1] - zs[0]), nDiv, width, offset, kind);
                final int n = (int) c[0];
                final double w = c[1];
                for (int i = 0; i < n; i++) {
                    final double posi = zs[0] + offset + (2 * i + 1) * w / 2;
                    final double z0 = posi - w / 2, z1 = posi + w / 2;
                    out.add(new Copy(planesSolid(tag, name + "_" + i, sphi, dphi, sides, new double[] {-w / 2, w / 2},
                        new double[] {Math.max(0, at(z0, zs, rmin)), Math.max(0, at(z1, zs, rmin))},
                        new double[] {at(z0, zs, rmax), at(z1, zs, rmax)}, step), 0, 0, posi, 0));
                }
                return counted(n, w, out, "the length");
            }
            default -> {
                return Result.error("a " + m.g4Class() + " is divided along kRho, kPhi or kZAxis, not " + axis);
            }
        }
    }

    /** A radius at z, on the segment of the mother's planes holding z. */
    private static double at(double z, double[] zs, double[] r) {
        for (int k = 0; k + 1 < zs.length; k++) {
            if (z >= Math.min(zs[k], zs[k + 1]) - 1e-9 && z <= Math.max(zs[k], zs[k + 1]) + 1e-9) {
                return zs[k + 1] == zs[k] ? r[k] : r[k] + (r[k + 1] - r[k]) * (z - zs[k]) / (zs[k + 1] - zs[k]);
            }
        }
        return z < zs[0] ? r[0] : r[r.length - 1];
    }

    /** Planes given at the corners, written at the sides for a polyhedra (step: its angle per side). */
    private static Solid planesSolid(String tag, String name, double sphi, double dphi, int sides, double[] z, double[] rmin,
                                     double[] rmax, double step) {
        final Solid s = Solids.create(tag, name);
        s.set("startphi", ang(sphi));
        s.set("deltaphi", ang(dphi));
        final double toSide = tag.equals("polyhedra") ? Math.cos(step / 2) : 1;
        if (tag.equals("polyhedra")) {
            s.set("numsides", String.valueOf(sides));
        }
        final Solid.Table t = s.table("zplane");
        t.clear();
        for (int i = 0; i < z.length; i++) {
            t.add(len(rmin[i] * toSide), len(rmax[i] * toSide), len(z[i]));
        }
        return s;
    }
}

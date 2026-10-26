package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parametric detectors, built the way Geant4's own examples build them, with
 * every size a document variable so the whole detector follows a change:
 *
 *  - calorimeter: a sampling calorimeter as in example B4, layers of absorber
 *    and active gap, the layer replicated along z with a G4PVReplica;
 *  - tracker: a silicon barrel, concentric tubes in an air envelope;
 *  - telescope: thin planes along the beam, as in example B2's tracker;
 *  - shield: a hollow box of concrete around the origin;
 *  - phantom: a water phantom with a bone insert, as in medical examples;
 *  - tpc: an argon drift volume with a field cage and readout end plates.
 */
public final class Templates {

    private Templates() {
    }

    public static List<String> kinds() {
        return List.of("calorimeter", "tracker", "telescope", "shield", "phantom", "tpc");
    }

    public static List<String> build(Geometry g, String kind, Map<String, String> o, String unit) throws GeometryException {
        return switch (kind) {
            case "calorimeter", "calo", "calorimetre" -> calorimeter(g, o, unit);
            case "tracker", "barrel" -> tracker(g, o, unit);
            case "telescope" -> telescope(g, o, unit);
            case "shield", "shielding" -> shield(g, o, unit);
            case "phantom" -> phantom(g, o, unit);
            case "tpc" -> tpc(g, o, unit);
            default -> throw new GeometryException("unknown template " + kind + "; templates: " + kinds());
        };
    }

    private static String opt(Map<String, String> o, String key, String def, Units.Category c, String unit) {
        final String v = o.get(key);
        return v == null ? def : Inputs.normalize(v, c, unit);
    }

    private static String mat(Geometry g, Map<String, String> o, String key, String def) throws GeometryException {
        final String v = o.getOrDefault(key, def);
        final String m = Intent.material(v);
        final String name = m != null ? m : v;
        if (g.materials().material(name) == null) {
            throw new GeometryException("unknown material " + v);
        }
        return name;
    }

    private static String var(Geometry g, String name, String expr) {
        String n = name;
        if (g.nameTaken(n)) {
            n = g.uniqueName(name);
        }
        g.addDefine(new Define(n, Define.Kind.CONSTANT, expr));
        return n;
    }

    private static LogicalVolume volume(Geometry g, String name, Solid s, String material) {
        final String n = g.uniqueName(name);
        s.rename(g.uniqueName(n + "_s"));
        g.addSolid(s);
        final LogicalVolume lv = new LogicalVolume(n, s, material);
        lv.setColor(MaterialColors.colorFor(material));
        g.addVolume(lv);
        return lv;
    }

    private static Solid box(String x, String y, String z) {
        final Solid s = Solids.create("box", "b");
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        return s;
    }

    private static Solid tube(String rmin, String rmax, String z) {
        final Solid s = Solids.create("tube", "t");
        s.set("rmin", rmin);
        s.set("rmax", rmax);
        s.set("z", z);
        return s;
    }

    /** Grows a box world to hold a half-extent, so a template never sticks out of it. */
    private static void ensureWorld(Geometry g, double hx, double hy, double hz) {
        final Solid w = g.world().solid();
        if (!"box".equals(w.tag()) || w.error() != null) {
            return;
        }
        final double m = 1.2;
        if (w.v("x") / 2 < hx * m) {
            w.set("x", Units.num(2 * hx * m) + "*mm");
        }
        if (w.v("y") / 2 < hy * m) {
            w.set("y", Units.num(2 * hy * m) + "*mm");
        }
        if (w.v("z") / 2 < hz * m) {
            w.set("z", Units.num(2 * hz * m) + "*mm");
        }
    }

    private static double eval(Geometry g, String e) throws GeometryException {
        g.rebuild();
        return Expr.eval(e, g.scope());
    }

    // ------------------------------------------------------------------

    static List<String> calorimeter(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String n = var(g, "cal_nLayers", o.getOrDefault("layers", "20"));
        final String absT = var(g, "cal_absT", opt(o, "abs", "10*mm", Units.Category.LENGTH, unit));
        final String gapT = var(g, "cal_gapT", opt(o, "act", opt(o, "gap", "5*mm", Units.Category.LENGTH, unit), Units.Category.LENGTH, unit));
        final String size = var(g, "cal_size", opt(o, "size", "10*cm", Units.Category.LENGTH, unit));
        final String z0 = var(g, "cal_z", opt(o, "z", "0*mm", Units.Category.LENGTH, unit));
        final String absMat = mat(g, o, "absorber", "G4_Pb");
        final String gapMat = mat(g, o, "active", "G4_lAr");
        final String layerT = "(" + absT + "+" + gapT + ")";

        final LogicalVolume calo = volume(g, "Calorimeter", box(size, size, n + "*" + layerT), "G4_Galactic");
        calo.setColor(0x30A0B0C0);
        final LogicalVolume layer = volume(g, "Layer", box(size, size, layerT), "G4_Galactic");
        layer.setColor(0x20A0B0C0);
        layer.setVisible(false);
        final LogicalVolume abs = volume(g, "Absorber", box(size, size, absT), absMat);
        final LogicalVolume gap = volume(g, "Gap", box(size, size, gapT), gapMat);
        gap.setSensitive("GapSD");

        final PhysicalVolume pa = g.place(abs, layer, g.uniqueName("Absorber_p"));
        pa.setPosition("0*mm", "0*mm", "-" + gapT + "/2");
        final PhysicalVolume pg = g.place(gap, layer, g.uniqueName("Gap_p"));
        pg.setPosition("0*mm", "0*mm", absT + "/2");
        final PhysicalVolume rep = g.place(layer, calo, g.uniqueName("Layer_rep"));
        final PhysicalVolume.Replica r = new PhysicalVolume.Replica();
        r.setAxis(PhysicalVolume.Replica.Axis.kZAxis);
        r.setNumber(n);
        r.setWidth(layerT);
        r.setOffset("0*mm");
        rep.setReplica(r);
        final PhysicalVolume pc = g.place(calo, g.world(), g.uniqueName("Calorimeter_p"));
        pc.setPosition("0*mm", "0*mm", z0 + "+" + n + "*" + layerT + "/2");
        final double hz = Math.abs(eval(g, z0)) + eval(g, n + "*" + layerT);
        ensureWorld(g, eval(g, size) / 2, eval(g, size) / 2, hz);
        return List.of(pc.name());
    }

    static List<String> tracker(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final int layers = (int) Math.round(Expr.eval(o.getOrDefault("layers", "4"), g.scope()));
        final String r0 = var(g, "trk_r0", opt(o, "r0", "5*cm", Units.Category.LENGTH, unit));
        final String dr;
        if (o.containsKey("rmax")) {
            final String rmax = Inputs.normalize(o.get("rmax"), Units.Category.LENGTH, unit);
            dr = var(g, "trk_dr", "(" + rmax + "-" + r0 + ")/" + Math.max(1, layers - 1));
        } else {
            dr = var(g, "trk_dr", opt(o, "dr", "5*cm", Units.Category.LENGTH, unit));
        }
        final String t = var(g, "trk_t", opt(o, "thickness", "300*um", Units.Category.LENGTH, unit));
        final String len = var(g, "trk_length", opt(o, "length", "60*cm", Units.Category.LENGTH, unit));
        final String m = mat(g, o, "material", "G4_Si");
        final String outer = r0 + "+" + (layers - 1) + "*" + dr + "+" + t + "+1*mm";
        final LogicalVolume env = volume(g, "Tracker", tube("(" + r0 + ")-1*mm", outer, len + "+2*mm"), "G4_AIR");
        env.setColor(0x24A0C0E0);
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < layers; i++) {
            final String ri = r0 + "+" + i + "*" + dr;
            final LogicalVolume lv = volume(g, "TrkLayer" + i, tube(ri, ri + "+" + t, len), m);
            lv.setSensitive("TrackerSD");
            final PhysicalVolume pv = g.place(lv, env, g.uniqueName("TrkLayer" + i + "_p"));
            pv.setCopyNumber(i);
            out.add(pv.name());
        }
        final PhysicalVolume pe = g.place(env, g.world(), g.uniqueName("Tracker_p"));
        out.add(0, pe.name());
        final double ro = eval(g, outer);
        ensureWorld(g, ro, ro, eval(g, len) / 2);
        return out;
    }

    static List<String> telescope(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final int planes = (int) Math.round(Expr.eval(o.getOrDefault("planes", o.getOrDefault("layers", "6")), g.scope()));
        final String pitch = var(g, "tel_pitch", opt(o, "pitch", "5*cm", Units.Category.LENGTH, unit));
        final String size = var(g, "tel_size", opt(o, "size", "2*cm", Units.Category.LENGTH, unit));
        final String t = var(g, "tel_t", opt(o, "thickness", "300*um", Units.Category.LENGTH, unit));
        final String z0 = var(g, "tel_z", opt(o, "z", "0*mm", Units.Category.LENGTH, unit));
        final String m = mat(g, o, "material", "G4_Si");
        final LogicalVolume plane = volume(g, "Plane", box(size, size, t), m);
        plane.setSensitive("TelescopeSD");
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < planes; i++) {
            final PhysicalVolume pv = g.place(plane, g.world(), g.uniqueName("Plane_" + i));
            pv.setCopyNumber(i);
            pv.setPosition("0*mm", "0*mm", z0 + "+(" + i + "-" + (planes - 1) + "/2)*" + pitch);
            out.add(pv.name());
        }
        ensureWorld(g, eval(g, size), eval(g, size), Math.abs(eval(g, z0)) + planes * eval(g, pitch) / 2 + 1);
        return out;
    }

    static List<String> shield(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String inner = var(g, "sh_inner", opt(o, "inner", opt(o, "size", "1*m", Units.Category.LENGTH, unit), Units.Category.LENGTH, unit));
        final String t = var(g, "sh_t", opt(o, "thickness", "20*cm", Units.Category.LENGTH, unit));
        final String m = mat(g, o, "material", "G4_CONCRETE");
        final Solid out = box(inner + "+2*" + t, inner + "+2*" + t, inner + "+2*" + t);
        out.rename(g.uniqueName("ShieldOuter"));
        g.addSolid(out);
        final Solid in = box(inner, inner, inner);
        in.rename(g.uniqueName("ShieldInner"));
        g.addSolid(in);
        final BooleanSolid b = new BooleanSolid(g.uniqueName("ShieldShape"), BooleanSolid.Op.SUBTRACTION, out, in);
        g.addSolid(b);
        final String name = g.uniqueName("Shield");
        final LogicalVolume lv = new LogicalVolume(name, b, m);
        lv.setColor(MaterialColors.colorFor(m));
        g.addVolume(lv);
        final PhysicalVolume pv = g.place(lv, g.world(), g.uniqueName("Shield_p"));
        final double h = eval(g, inner) / 2 + eval(g, t);
        ensureWorld(g, h, h, h);
        return List.of(pv.name());
    }

    static List<String> phantom(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String size = var(g, "ph_size", opt(o, "size", "30*cm", Units.Category.LENGTH, unit));
        final String bone = var(g, "ph_bone", opt(o, "bone", "2*cm", Units.Category.LENGTH, unit));
        final LogicalVolume water = volume(g, "Phantom", box(size, size, size), mat(g, o, "material", "G4_WATER"));
        water.setSensitive("PhantomSD");
        final LogicalVolume b = volume(g, "BoneSlab", box(size, size, bone), "G4_BONE_COMPACT_ICRU");
        g.place(b, water, g.uniqueName("BoneSlab_p")).setPosition("0*mm", "0*mm", "-" + size + "/6");
        final PhysicalVolume pv = g.place(water, g.world(), g.uniqueName("Phantom_p"));
        final double h = eval(g, size) / 2;
        ensureWorld(g, h, h, h);
        return List.of(pv.name());
    }

    static List<String> tpc(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String r = var(g, "tpc_r", opt(o, "radius", "50*cm", Units.Category.LENGTH, unit));
        final String len = var(g, "tpc_length", opt(o, "length", "1*m", Units.Category.LENGTH, unit));
        final String wall = var(g, "tpc_wall", opt(o, "wall", "5*mm", Units.Category.LENGTH, unit));
        final String gas = mat(g, o, "gas", "G4_Ar");
        final LogicalVolume cage = volume(g, "FieldCage", tube("0*mm", r + "+" + wall, len + "+2*" + wall), "G4_KAPTON");
        final LogicalVolume drift = volume(g, "DriftGas", tube("0*mm", r, len), gas);
        drift.setSensitive("TpcSD");
        g.place(drift, cage, g.uniqueName("DriftGas_p"));
        final PhysicalVolume pv = g.place(cage, g.world(), g.uniqueName("FieldCage_p"));
        final double rr = eval(g, r + "+" + wall), hz = eval(g, len) / 2 + eval(g, wall);
        ensureWorld(g, rr, rr, hz);
        return List.of(pv.name());
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}

package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parametric detectors, built the way Geant4's own examples build them, with every size a document variable so the
 * whole detector follows a change (on the model, in the Variables, by a goal). Each comes with the beam it is made
 * for, set as the design's gun when the design has none yet, so Physics Live and a run in Geant4 start right.
 *
 * <ul>
 * <li>calorimetry: a sampling calorimeter (example B4, its layer a G4PVReplica), a hadronic one (iron and
 *     scintillator tiles), a crystal matrix (PbWO4 as CMS's, BGO, CsI);</li>
 * <li>tracking: a silicon barrel around a beryllium pipe, a beam telescope with its device under test, a TPC with its
 *     central cathode and readout end plates, a scintillator hodoscope;</li>
 * <li>whole experiments: a collider detector in barrel layers (pipe, tracker, crystals, hadron calorimeter, coil,
 *     return yoke with muon chambers), a fixed-target spectrometer (target, stations, dipole, calorimeter, muon
 *     filter);</li>
 * <li>astroparticle and neutrinos: a water Cherenkov tank with its photomultipliers, a cosmic-muon telescope around
 *     an object (muon tomography);</li>
 * <li>nuclear and medical: a germanium spectrometer in its lead castle, a water phantom with bone and lung, a
 *     concrete shielding with an optional lead lining.</li>
 * </ul>
 */
public final class Templates {

    private Templates() {
    }

    /** A parameter a template reads: its key (template kind key=value), what it is, its default. */
    public record Param(String key, String label, String value, String help) {
    }

    /** A template: its kind (the console's word), its name, its field, what it builds, its parameters, its beam. */
    public record Template(String kind, String title, String field, String description, List<Param> params, String beam) {
    }

    private static Param p(String key, String label, String value, String help) {
        return new Param(key, label, value, help);
    }

    private static final List<Template> CATALOG = List.of(
        new Template("calorimeter", "Sampling calorimeter", "Calorimetry",
            "Geant4's example B4: layers of absorber and active gap, the layer replicated along z with a G4PVReplica."
                + " The gaps are the sensitive detector.",
            List.of(p("layers", "Layers", "20", "how many absorber + gap layers"), p("abs", "Absorber thickness", "10*mm", ""),
                p("gap", "Active thickness", "5*mm", ""), p("size", "Transverse size", "10*cm", ""),
                p("absorber", "Absorber material", "G4_Pb", "lead, tungsten, iron, copper..."),
                p("active", "Active material", "G4_lAr", "liquid argon, scintillator, silicon...")),
            "e- 10 GeV along +z"),
        new Template("hcal", "Hadronic calorimeter", "Calorimetry",
            "Iron plates and scintillator tiles, the layer replicated along z (CMS HCAL, ATLAS TileCal): deep enough in"
                + " interaction lengths to hold a hadron shower.",
            List.of(p("layers", "Layers", "30", ""), p("abs", "Absorber thickness", "50*mm", ""),
                p("act", "Tile thickness", "4*mm", ""), p("size", "Transverse size", "1*m", ""),
                p("absorber", "Absorber material", "G4_Fe", "iron, brass, copper, tungsten"),
                p("active", "Active material", "G4_PLASTIC_SC_VINYLTOLUENE", "")),
            "pi- 50 GeV along +z"),
        new Template("ecal", "Crystal calorimeter", "Calorimetry",
            "A matrix of crystals with their wrapping, each a sensitive volume with its own copy number (CMS ECAL's"
                + " PbWO4, or BGO, CsI(Tl), LYSO). An odd number of columns puts a crystal on the axis.",
            List.of(p("n", "Crystals per side", "5", "the matrix is n x n"), p("size", "Crystal front face", "22*mm", ""),
                p("length", "Crystal length", "230*mm", "25.8 X0 of PbWO4"), p("gap", "Wrapping gap", "0.2*mm", ""),
                p("material", "Crystal", "G4_PbWO4", "G4_BGO, G4_CESIUM_IODIDE...")),
            "e- 50 GeV along +z"),
        new Template("tracker", "Silicon tracker barrel", "Tracking",
            "Concentric silicon layers in an air envelope around a beryllium beam pipe; each layer sensitive with its"
                + " copy number. The beam leaves the interaction point across the layers.",
            List.of(p("layers", "Layers", "4", ""), p("r0", "First radius", "5*cm", ""), p("dr", "Layer spacing", "5*cm", ""),
                p("thickness", "Layer thickness", "300*um", ""), p("length", "Length", "60*cm", ""),
                p("pipe", "Beam pipe radius", "2.5*cm", "its wall: 0.8 mm of beryllium"),
                p("material", "Layer material", "G4_Si", "")),
            "mu- 10 GeV from the center along +x"),
        new Template("telescope", "Beam telescope", "Tracking",
            "Thin sensor planes along the beam with a device under test in the middle (EUDET-like, example B2's"
                + " tracker); each plane sensitive with its copy number.",
            List.of(p("planes", "Planes", "6", ""), p("pitch", "Plane spacing", "5*cm", ""), p("size", "Plane size", "2*cm", ""),
                p("thickness", "Plane thickness", "300*um", ""), p("dut", "Device under test", "500*um", "0: none"),
                p("material", "Plane material", "G4_Si", "")),
            "e- 4 GeV along +z"),
        new Template("tpc", "Time projection chamber", "Tracking",
            "An argon drift volume in its field cage, split by the central cathode, with aluminum readout end plates.",
            List.of(p("radius", "Drift radius", "50*cm", ""), p("length", "Drift length", "1*m", ""),
                p("wall", "Field cage wall", "5*mm", ""), p("plate", "End plate", "2*cm", ""), p("gas", "Gas", "G4_Ar", "")),
            "mu- 1 GeV across the drift volume"),
        new Template("hodoscope", "Scintillator hodoscope", "Tracking",
            "Two planes of scintillator bars, one along x and one along y, each bar sensitive with its copy number: a"
                + " trigger, a time-of-flight wall.",
            List.of(p("bars", "Bars per plane", "9", "odd: a bar on the axis"), p("width", "Bar width", "5*cm", ""),
                p("thickness", "Bar thickness", "1*cm", ""), p("gap", "Gap between planes", "1*cm", "")),
            "pi+ 2 GeV along +z"),
        new Template("collider", "Collider detector (barrel)", "Experiments",
            "A collider experiment in barrel layers around the beam: beryllium pipe, silicon tracker, PbWO4 crystals,"
                + " brass and scintillator hadron calorimeter, superconducting coil, iron return yoke with muon chambers.",
            List.of(p("length", "Barrel length", "5*m", ""), p("ecal_r", "ECAL inner radius", "1290*mm", ""),
                p("ecal_t", "ECAL thickness", "230*mm", ""), p("hcal_r", "HCAL inner radius", "1800*mm", ""),
                p("hcal_layers", "HCAL layers", "12", ""), p("coil_r", "Coil inner radius", "2950*mm", "")),
            "pi- 50 GeV from the interaction point along +x"),
        new Template("fixedtarget", "Fixed-target spectrometer", "Experiments",
            "Target, silicon stations, dipole magnet yoke with its gap, scintillator stations, lead and scintillator"
                + " calorimeter, iron muon filter and muon station, along the beam.",
            List.of(p("target", "Target thickness", "10*mm", ""), p("material", "Target material", "G4_Be", "")),
            "pi- 20 GeV along +z"),
        new Template("cherenkov", "Water Cherenkov tank", "Astroparticle and neutrinos",
            "A steel tank of water with photomultipliers on rings along its wall, each sensitive with its copy number"
                + " (Super-Kamiokande, Auger, IceTop in miniature).",
            List.of(p("radius", "Tank radius", "1.5*m", ""), p("height", "Tank height", "3*m", ""),
                p("rings", "PMT rings", "5", ""), p("per_ring", "PMTs per ring", "16", ""), p("pmt", "PMT radius", "10*cm", "")),
            "mu- 1 GeV from above"),
        new Template("cosmic", "Cosmic-muon telescope", "Astroparticle and neutrinos",
            "Scintillator planes above and below an object, the way muon tomography sees through it; the muons come"
                + " from above (+y is up).",
            List.of(p("object", "Object size", "50*cm", ""), p("material", "Object material", "G4_Fe", "iron, lead, water..."),
                p("size", "Plane size", "1*m", ""), p("gap", "Object to planes", "10*cm", ""),
                p("spacing", "Between paired planes", "20*cm", "")),
            "mu- 4 GeV from above"),
        new Template("hpge", "Germanium spectrometer", "Nuclear and medical",
            "A coaxial HPGe crystal with its dead layer and cold finger, in its aluminum end cap and vacuum, inside a"
                + " lead castle lined with copper: gamma spectroscopy, low background.",
            List.of(p("radius", "Crystal radius", "30*mm", ""), p("length", "Crystal length", "60*mm", ""),
                p("bore", "Core bore radius", "5*mm", ""), p("dead", "Dead layer", "0.7*mm", ""),
                p("lead", "Lead thickness", "10*cm", ""), p("copper", "Copper lining", "3*mm", "")),
            "gamma 662 keV (137Cs) from 2 cm above the end cap"),
        new Template("phantom", "Water phantom", "Nuclear and medical",
            "A water phantom, the sensitive volume, with a compact bone slab across the beam and a lung insert beside it,"
                + " as in Geant4's medical examples.",
            List.of(p("size", "Phantom size", "30*cm", ""), p("bone", "Bone slab thickness", "2*cm", ""),
                p("material", "Phantom material", "G4_WATER", "")),
            "proton 150 MeV along +z (its Bragg peak)"),
        new Template("shield", "Concrete shielding", "Nuclear and medical",
            "A hollow box of concrete around the origin, with an optional lead lining inside: a source's bunker.",
            List.of(p("inner", "Cavity size", "1*m", ""), p("thickness", "Wall thickness", "20*cm", ""),
                p("lining", "Lead lining", "0*mm", "0: none"), p("material", "Wall material", "G4_CONCRETE", "")),
            "gamma 1.25 MeV (60Co) from the center through the wall"));

    /** The templates with what the gallery shows of them. */
    public static List<Template> catalog() {
        return CATALOG;
    }

    /** The document variable each parameter becomes; a count fixed when built, or a material, has none. */
    private static final Map<String, String> VARIABLES = Map.ofEntries(
        Map.entry("calorimeter.layers", "cal_nLayers"), Map.entry("calorimeter.abs", "cal_absT"),
        Map.entry("calorimeter.gap", "cal_gapT"), Map.entry("calorimeter.size", "cal_size"),
        Map.entry("hcal.layers", "hcal_nLayers"), Map.entry("hcal.abs", "hcal_absT"), Map.entry("hcal.act", "hcal_actT"),
        Map.entry("hcal.size", "hcal_size"),
        Map.entry("ecal.size", "ecal_size"), Map.entry("ecal.length", "ecal_length"), Map.entry("ecal.gap", "ecal_gap"),
        Map.entry("tracker.r0", "trk_r0"), Map.entry("tracker.dr", "trk_dr"), Map.entry("tracker.thickness", "trk_t"),
        Map.entry("tracker.length", "trk_length"), Map.entry("tracker.pipe", "trk_pipe"),
        Map.entry("telescope.pitch", "tel_pitch"), Map.entry("telescope.size", "tel_size"),
        Map.entry("telescope.thickness", "tel_t"), Map.entry("telescope.dut", "tel_dut"),
        Map.entry("tpc.radius", "tpc_r"), Map.entry("tpc.length", "tpc_length"), Map.entry("tpc.wall", "tpc_wall"),
        Map.entry("tpc.plate", "tpc_plate"),
        Map.entry("hodoscope.width", "hod_w"), Map.entry("hodoscope.thickness", "hod_t"), Map.entry("hodoscope.gap", "hod_gap"),
        Map.entry("collider.length", "col_length"), Map.entry("collider.ecal_r", "col_ecal_r"),
        Map.entry("collider.ecal_t", "col_ecal_t"), Map.entry("collider.hcal_r", "col_hcal_r"),
        Map.entry("collider.coil_r", "col_coil_r"),
        Map.entry("fixedtarget.target", "ft_target_t"),
        Map.entry("cherenkov.radius", "wc_r"), Map.entry("cherenkov.height", "wc_h"), Map.entry("cherenkov.pmt", "wc_pmt"),
        Map.entry("cosmic.object", "cos_object"), Map.entry("cosmic.size", "cos_size"), Map.entry("cosmic.gap", "cos_gap"),
        Map.entry("cosmic.spacing", "cos_spacing"),
        Map.entry("hpge.radius", "ge_r"), Map.entry("hpge.length", "ge_length"), Map.entry("hpge.bore", "ge_bore"),
        Map.entry("hpge.dead", "ge_dead"), Map.entry("hpge.lead", "ge_lead"), Map.entry("hpge.copper", "ge_copper"),
        Map.entry("phantom.size", "ph_size"), Map.entry("phantom.bone", "ph_bone"),
        Map.entry("shield.inner", "sh_inner"), Map.entry("shield.thickness", "sh_t"), Map.entry("shield.lining", "sh_lining"));

    /** The document variable a template's parameter becomes, or null (a count fixed when it is built, a material). */
    public static String variableOf(String kind, String key) {
        return VARIABLES.get(kind + "." + key);
    }

    /** What a search matches a template on, beyond its name and its text. */
    private static final Map<String, String> KEYWORDS = Map.ofEntries(
        Map.entry("calorimeter", "electromagnetic energy resolution electron photon lead argon sampling B4"),
        Map.entry("hcal", "hadron pion proton jet iron tile hadronic"),
        Map.entry("ecal", "crystal pbwo4 bgo csi lyso electromagnetic electron photon higgs"),
        Map.entry("tracker", "silicon pixel strip vertex momentum barrel"),
        Map.entry("telescope", "test beam dut alignment resolution sensor"),
        Map.entry("tpc", "gas drift argon ionization tracks dE/dx chamber"),
        Map.entry("hodoscope", "trigger time of flight tof bars scintillator"),
        Map.entry("collider", "lhc cms atlas collision onion barrel solenoid muon complete experiment magnet"),
        Map.entry("fixedtarget", "target spectrometer magnet dipole beam dump na62 compass"),
        Map.entry("cherenkov", "neutrino water pmt photomultiplier cherenkov light super-kamiokande auger"),
        Map.entry("cosmic", "muon tomography muography cosmic rays imaging volcano pyramid"),
        Map.entry("hpge", "gamma spectroscopy radioactivity germanium low background isotope cs-137"),
        Map.entry("phantom", "medical dose radiotherapy proton therapy bragg hadron therapy dosimetry"),
        Map.entry("shield", "radiation protection shielding bunker concrete source dose"));

    public static String keywords(String kind) {
        return KEYWORDS.getOrDefault(kind, "");
    }

    public static List<String> kinds() {
        final List<String> out = new ArrayList<>();
        for (Template t : CATALOG) {
            out.add(t.kind());
        }
        return out;
    }

    public static Template template(String kind) {
        for (Template t : CATALOG) {
            if (t.kind().equals(kind)) {
                return t;
            }
        }
        return null;
    }

    public static List<String> build(Geometry g, String kind, Map<String, String> o, String unit) throws GeometryException {
        return switch (kind) {
            case "calorimeter", "calo" -> calorimeter(g, o, unit);
            case "hcal", "hadronic" -> hcal(g, o, unit);
            case "ecal", "crystals", "crystal" -> ecal(g, o, unit);
            case "tracker", "barrel" -> tracker(g, o, unit);
            case "telescope" -> telescope(g, o, unit);
            case "tpc" -> tpc(g, o, unit);
            case "hodoscope", "tof" -> hodoscope(g, o, unit);
            case "collider", "experiment" -> collider(g, o, unit);
            case "fixedtarget", "fixed-target", "spectrometer" -> fixedTarget(g, o, unit);
            case "cherenkov", "watertank", "tank" -> cherenkov(g, o, unit);
            case "cosmic", "muography", "tomography" -> cosmic(g, o, unit);
            case "hpge", "germanium" -> hpge(g, o, unit);
            case "phantom" -> phantom(g, o, unit);
            case "shield", "shielding" -> shield(g, o, unit);
            default -> throw new GeometryException("unknown template " + kind + "; templates: " + kinds());
        };
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String opt(Map<String, String> o, String key, String def, Units.Category c, String unit) {
        final String v = o.get(key);
        return v == null ? def : Inputs.normalize(v, c, unit);
    }

    private static String len(Map<String, String> o, String key, String def, String unit) {
        return opt(o, key, def, Units.Category.LENGTH, unit);
    }

    private static int count(Geometry g, Map<String, String> o, String key, int def) throws GeometryException {
        final String v = o.get(key);
        if (v == null) {
            return def;
        }
        final int n = (int) Math.round(Expr.eval(v, g.scope()));
        if (n < 1) {
            throw new GeometryException(key + " must be at least 1");
        }
        return n;
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

    private static Solid orb(String r) {
        final Solid s = Solids.create("orb", "o");
        s.set("r", r);
        return s;
    }

    /** A hollow volume: an outer solid less a centered inner one (a castle, a bunker, a yoke with its gap). */
    private static LogicalVolume shell(Geometry g, String name, Solid outer, Solid inner, String material) {
        outer.rename(g.uniqueName(name + "_out"));
        g.addSolid(outer);
        inner.rename(g.uniqueName(name + "_in"));
        g.addSolid(inner);
        final BooleanSolid b = new BooleanSolid(g.uniqueName(name + "_s"), BooleanSolid.Op.SUBTRACTION, outer, inner);
        g.addSolid(b);
        final LogicalVolume lv = new LogicalVolume(g.uniqueName(name), b, material);
        lv.setColor(MaterialColors.colorFor(material));
        g.addVolume(lv);
        return lv;
    }

    private static PhysicalVolume place(Geometry g, LogicalVolume lv, LogicalVolume mother, String name, String x, String y,
                                        String z) {
        final PhysicalVolume pv = g.place(lv, mother, g.uniqueName(name));
        pv.setPosition(x, y, z);
        return pv;
    }

    private static String mm(double v) {
        return Units.num(v) + "*mm";
    }

    /**
     * The beam the detector is made for, as the design's gun, when the design has none yet: what Physics Live judges
     * and what a run fires. A null position lets the gun enter the world on the side the beam comes from.
     */
    private static void gun(Geometry g, String particle, String energy, String position, String direction) {
        final Map<String, String> s = g.settings();
        if (s.containsKey("gun.particle")) {
            return;
        }
        s.put("gun.particle", particle);
        s.put("gun.energy", energy);
        if (position != null) {
            s.put("gun.position", position);
        } else {
            s.remove("gun.position");
        }
        s.put("gun.direction", direction);
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

    /** Half the world along an axis, once grown: where a gun inside it stands. */
    private static double worldHalf(Geometry g, int axis) throws GeometryException {
        g.rebuild();
        final BBox b = g.world().solid().extent();
        return axis == 0 ? b.maxX() : axis == 1 ? b.maxY() : b.maxZ();
    }

    private static double eval(Geometry g, String e) throws GeometryException {
        g.rebuild();
        return Expr.eval(e, g.scope());
    }

    /**
     * A sampling stack as example B4 builds it: an envelope, the layer replicated along z, an absorber and an active
     * gap in each layer, the gap sensitive. Placed with its front face at zFront.
     */
    private static PhysicalVolume sampling(Geometry g, LogicalVolume mother, String[] names, String n, String absT,
                                           String actT, String size, String absMat, String actMat, String sd, String x,
                                           String y, String zFront) {
        final String layerT = "(" + absT + "+" + actT + ")";
        final LogicalVolume calo = volume(g, names[0], box(size, size, n + "*" + layerT), "G4_Galactic");
        calo.setColor(0x30A0B0C0);
        final LogicalVolume layer = volume(g, names[1], box(size, size, layerT), "G4_Galactic");
        layer.setColor(0x20A0B0C0);
        layer.setVisible(false);
        final LogicalVolume abs = volume(g, names[2], box(size, size, absT), absMat);
        final LogicalVolume act = volume(g, names[3], box(size, size, actT), actMat);
        act.setSensitive(sd);
        place(g, abs, layer, names[2] + "_p", "0*mm", "0*mm", "-" + actT + "/2");
        place(g, act, layer, names[3] + "_p", "0*mm", "0*mm", absT + "/2");
        final PhysicalVolume rep = g.place(layer, calo, g.uniqueName(names[1] + "_rep"));
        final PhysicalVolume.Replica r = new PhysicalVolume.Replica();
        r.setAxis(PhysicalVolume.Replica.Axis.kZAxis);
        r.setNumber(n);
        r.setWidth(layerT);
        r.setOffset("0*mm");
        rep.setReplica(r);
        return place(g, calo, mother, names[0] + "_p", x, y, zFront + "+" + n + "*" + layerT + "/2");
    }

    // ------------------------------------------------------------------
    // Calorimetry
    // ------------------------------------------------------------------

    static List<String> calorimeter(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String n = var(g, "cal_nLayers", o.getOrDefault("layers", "20"));
        final String absT = var(g, "cal_absT", len(o, "abs", "10*mm", unit));
        final String gapT = var(g, "cal_gapT", len(o, "act", len(o, "gap", "5*mm", unit), unit));
        final String size = var(g, "cal_size", len(o, "size", "10*cm", unit));
        final String z0 = var(g, "cal_z", len(o, "z", "0*mm", unit));
        final String absMat = mat(g, o, "absorber", "G4_Pb");
        final String gapMat = mat(g, o, "active", "G4_lAr");
        // its front face at cal_z
        final String layerT = "(" + absT + "+" + gapT + ")";
        final PhysicalVolume pc = sampling(g, g.world(), new String[] {"Calorimeter", "Layer", "Absorber", "Gap"}, n, absT,
            gapT, size, absMat, gapMat, "GapSD", "0*mm", "0*mm", z0);
        final double hz = Math.abs(eval(g, z0)) + eval(g, n + "*" + layerT);
        ensureWorld(g, eval(g, size) / 2, eval(g, size) / 2, hz);
        gun(g, "e-", "10*GeV", null, "0 0 1");
        return List.of(pc.name());
    }

    static List<String> hcal(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String n = var(g, "hcal_nLayers", o.getOrDefault("layers", "30"));
        final String absT = var(g, "hcal_absT", len(o, "abs", "50*mm", unit));
        final String actT = var(g, "hcal_actT", len(o, "act", "4*mm", unit));
        final String size = var(g, "hcal_size", len(o, "size", "1*m", unit));
        final String z0 = var(g, "hcal_z", len(o, "z", "0*mm", unit));
        final String absMat = mat(g, o, "absorber", "G4_Fe");
        final String actMat = mat(g, o, "active", "G4_PLASTIC_SC_VINYLTOLUENE");
        final String layerT = "(" + absT + "+" + actT + ")";
        final PhysicalVolume ph = sampling(g, g.world(), new String[] {"HCal", "HLayer", "HAbsorber", "HTile"}, n, absT,
            actT, size, absMat, actMat, "HcalSD", "0*mm", "0*mm", z0);
        final double hz = Math.abs(eval(g, z0)) + eval(g, n + "*" + layerT);
        ensureWorld(g, eval(g, size) / 2, eval(g, size) / 2, hz);
        gun(g, "pi-", "50*GeV", null, "0 0 1");
        return List.of(ph.name());
    }

    static List<String> ecal(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final int n = count(g, o, "n", 5);
        final String size = var(g, "ecal_size", len(o, "size", "22*mm", unit));
        final String length = var(g, "ecal_length", len(o, "length", "230*mm", unit));
        final String gap = var(g, "ecal_gap", len(o, "gap", "0.2*mm", unit));
        final String pitch = var(g, "ecal_pitch", size + "+" + gap);
        final String z0 = var(g, "ecal_z", len(o, "z", "0*mm", unit));
        final String m = mat(g, o, "material", "G4_PbWO4");
        final LogicalVolume env = volume(g, "ECal", box(n + "*" + pitch, n + "*" + pitch, length), "G4_AIR");
        env.setColor(0x26A0B0C0);
        final LogicalVolume crystal = volume(g, "Crystal", box(size, size, length), m);
        crystal.setSensitive("CrystalSD");
        final double half = (n - 1) / 2.0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                final PhysicalVolume pv = place(g, crystal, env, "Crystal_" + i + "_" + j, "(" + Units.num(i - half) + ")*" + pitch,
                    "(" + Units.num(j - half) + ")*" + pitch, "0*mm");
                pv.setCopyNumber(i * n + j);
            }
        }
        final PhysicalVolume pe = place(g, env, g.world(), "ECal_p", "0*mm", "0*mm", z0 + "+" + length + "/2");
        final double h = n * eval(g, pitch) / 2;
        ensureWorld(g, h, h, Math.abs(eval(g, z0)) + eval(g, length));
        gun(g, "e-", "50*GeV", null, "0 0 1");
        return List.of(pe.name());
    }

    // ------------------------------------------------------------------
    // Tracking
    // ------------------------------------------------------------------

    static List<String> tracker(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final int layers = count(g, o, "layers", 4);
        final String r0 = var(g, "trk_r0", len(o, "r0", "5*cm", unit));
        final String dr;
        if (o.containsKey("rmax")) {
            final String rmax = Inputs.normalize(o.get("rmax"), Units.Category.LENGTH, unit);
            dr = var(g, "trk_dr", "(" + rmax + "-" + r0 + ")/" + Math.max(1, layers - 1));
        } else {
            dr = var(g, "trk_dr", len(o, "dr", "5*cm", unit));
        }
        final String t = var(g, "trk_t", len(o, "thickness", "300*um", unit));
        final String length = var(g, "trk_length", len(o, "length", "60*cm", unit));
        final String pipe = var(g, "trk_pipe", len(o, "pipe", "2.5*cm", unit));
        final String m = mat(g, o, "material", "G4_Si");
        final String outer = r0 + "+" + (layers - 1) + "*" + dr + "+" + t + "+1*mm";
        final LogicalVolume env = volume(g, "Tracker", tube("(" + r0 + ")-1*mm", outer, length + "+2*mm"), "G4_AIR");
        env.setColor(0x24A0C0E0);
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < layers; i++) {
            final String ri = r0 + "+" + i + "*" + dr;
            final LogicalVolume lv = volume(g, "TrkLayer" + i, tube(ri, ri + "+" + t, length), m);
            lv.setSensitive("TrackerSD");
            final PhysicalVolume pv = g.place(lv, env, g.uniqueName("TrkLayer" + i + "_p"));
            pv.setCopyNumber(i);
            out.add(pv.name());
        }
        // the beryllium pipe the beams run in, inside the tracker's hollow
        final LogicalVolume bp = volume(g, "BeamPipe", tube(pipe, pipe + "+0.8*mm", length), "G4_Be");
        final PhysicalVolume pp = g.place(bp, g.world(), g.uniqueName("BeamPipe_p"));
        final PhysicalVolume pe = g.place(env, g.world(), g.uniqueName("Tracker_p"));
        out.add(0, pp.name());
        out.add(0, pe.name());
        final double ro = eval(g, outer);
        ensureWorld(g, ro, ro, eval(g, length) / 2);
        gun(g, "mu-", "10*GeV", "0*mm 0*mm 0*mm", "1 0 0");
        return out;
    }

    static List<String> telescope(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final int planes = count(g, o, "planes", count(g, o, "layers", 6));
        final String pitch = var(g, "tel_pitch", len(o, "pitch", "5*cm", unit));
        final String size = var(g, "tel_size", len(o, "size", "2*cm", unit));
        final String t = var(g, "tel_t", len(o, "thickness", "300*um", unit));
        final String z0 = var(g, "tel_z", len(o, "z", "0*mm", unit));
        final String m = mat(g, o, "material", "G4_Si");
        final LogicalVolume plane = volume(g, "Plane", box(size, size, t), m);
        plane.setSensitive("TelescopeSD");
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < planes; i++) {
            final PhysicalVolume pv = place(g, plane, g.world(), "Plane_" + i, "0*mm", "0*mm",
                z0 + "+(" + i + "-" + Units.num((planes - 1) / 2.0) + ")*" + pitch);
            pv.setCopyNumber(i);
            out.add(pv.name());
        }
        // the device under test, in the middle: between two planes (or half a pitch past the middle one)
        final String dutT = len(o, "dut", "500*um", unit);
        if (eval(g, dutT) > 0) {
            final String d = var(g, "tel_dut", dutT);
            final LogicalVolume dut = volume(g, "DUT", box(size, size, d), mat(g, o, "dutmaterial", m));
            dut.setSensitive("DutSD");
            out.add(place(g, dut, g.world(), "DUT_p", "0*mm", "0*mm", planes % 2 == 0 ? z0 : z0 + "+" + pitch + "/2").name());
        }
        ensureWorld(g, eval(g, size), eval(g, size), Math.abs(eval(g, z0)) + planes * eval(g, pitch) / 2 + 1);
        gun(g, "e-", "4*GeV", null, "0 0 1");
        return out;
    }

    static List<String> tpc(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String r = var(g, "tpc_r", len(o, "radius", "50*cm", unit));
        final String length = var(g, "tpc_length", len(o, "length", "1*m", unit));
        final String wall = var(g, "tpc_wall", len(o, "wall", "5*mm", unit));
        final String plate = var(g, "tpc_plate", len(o, "plate", "2*cm", unit));
        final String gas = mat(g, o, "gas", "G4_Ar");
        final LogicalVolume cage = volume(g, "FieldCage", tube("0*mm", r + "+" + wall, length + "+2*" + wall), "G4_KAPTON");
        final LogicalVolume drift = volume(g, "DriftGas", tube("0*mm", r, length), gas);
        drift.setSensitive("TpcSD");
        // the central cathode, 25 um of aluminized mylar, splits the drift in two
        final LogicalVolume cathode = volume(g, "Cathode", tube("0*mm", r, "25*um"), "G4_MYLAR");
        g.place(cathode, drift, g.uniqueName("Cathode_p"));
        g.place(drift, cage, g.uniqueName("DriftGas_p"));
        final PhysicalVolume pv = g.place(cage, g.world(), g.uniqueName("FieldCage_p"));
        // the readout end plates, past the cage's ends
        final LogicalVolume end = volume(g, "EndPlate", tube("0*mm", r + "+" + wall, plate), "G4_Al");
        final String zEnd = length + "/2+" + wall + "+" + plate + "/2";
        final PhysicalVolume e0 = place(g, end, g.world(), "EndPlate_0", "0*mm", "0*mm", "-(" + zEnd + ")");
        e0.setCopyNumber(0);
        final PhysicalVolume e1 = place(g, end, g.world(), "EndPlate_1", "0*mm", "0*mm", zEnd);
        e1.setCopyNumber(1);
        final double rr = eval(g, r + "+" + wall), hz = eval(g, zEnd) + eval(g, plate) / 2;
        ensureWorld(g, rr, rr, hz);
        // a muon across the drift volume, a quarter of the length from the cathode
        gun(g, "mu-", "1*GeV", mm(-0.95 * worldHalf(g, 0)) + " 0*mm " + mm(eval(g, length) / 4), "1 0 0");
        return List.of(pv.name(), e0.name(), e1.name());
    }

    static List<String> hodoscope(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final int n = count(g, o, "bars", 9);
        final String w = var(g, "hod_w", len(o, "width", "5*cm", unit));
        final String t = var(g, "hod_t", len(o, "thickness", "1*cm", unit));
        final String gap = var(g, "hod_gap", len(o, "gap", "1*cm", unit));
        final String z0 = var(g, "hod_z", len(o, "z", "0*mm", unit));
        final String m = mat(g, o, "material", "G4_PLASTIC_SC_VINYLTOLUENE");
        final LogicalVolume bx = volume(g, "BarX", box(n + "*" + w, w, t), m);
        final LogicalVolume by = volume(g, "BarY", box(w, n + "*" + w, t), m);
        bx.setSensitive("HodoSD");
        by.setSensitive("HodoSD");
        final double half = (n - 1) / 2.0;
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String at = "(" + Units.num(i - half) + ")*" + w;
            final PhysicalVolume px = place(g, bx, g.world(), "BarX_" + i, "0*mm", at, z0);
            px.setCopyNumber(i);
            final PhysicalVolume py = place(g, by, g.world(), "BarY_" + i, at, "0*mm", z0 + "+" + t + "+" + gap);
            py.setCopyNumber(100 + i);
            out.add(px.name());
            out.add(py.name());
        }
        final double h = n * eval(g, w) / 2;
        ensureWorld(g, h, h, Math.abs(eval(g, z0)) + 2 * eval(g, t) + eval(g, gap));
        gun(g, "pi+", "2*GeV", null, "0 0 1");
        return out;
    }

    // ------------------------------------------------------------------
    // Whole experiments
    // ------------------------------------------------------------------

    static List<String> collider(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String length = var(g, "col_length", len(o, "length", "5*m", unit));
        final String ecalR = var(g, "col_ecal_r", len(o, "ecal_r", "1290*mm", unit));
        final String ecalT = var(g, "col_ecal_t", len(o, "ecal_t", "230*mm", unit));
        final String hcalR = var(g, "col_hcal_r", len(o, "hcal_r", "1800*mm", unit));
        final String hAbs = var(g, "col_hcal_abs", len(o, "hcal_abs", "60*mm", unit));
        final String hAct = var(g, "col_hcal_act", len(o, "hcal_act", "4*mm", unit));
        final int hLayers = count(g, o, "hcal_layers", 12);
        final String coilR = var(g, "col_coil_r", len(o, "coil_r", "2950*mm", unit));
        final String coilT = var(g, "col_coil_t", len(o, "coil_t", "300*mm", unit));
        final List<String> out = new ArrayList<>();
        // the beam pipe and the silicon tracker
        out.add(g.place(volume(g, "BeamPipe", tube("22*mm", "22.8*mm", length), "G4_Be"), g.world(),
            g.uniqueName("BeamPipe_p")).name());
        final double[] trk = {40, 70, 110, 250, 400, 550};
        for (int i = 0; i < trk.length; i++) {
            final LogicalVolume lv = volume(g, "TrkLayer" + i, tube(mm(trk[i]), mm(trk[i] + 0.3), length), "G4_Si");
            lv.setSensitive("TrackerSD");
            final PhysicalVolume pv = g.place(lv, g.world(), g.uniqueName("TrkLayer" + i + "_p"));
            pv.setCopyNumber(i);
            out.add(pv.name());
        }
        // the crystals
        final LogicalVolume ecal = volume(g, "ECalBarrel", tube(ecalR, ecalR + "+" + ecalT, length), "G4_PbWO4");
        ecal.setSensitive("EcalSD");
        out.add(g.place(ecal, g.world(), g.uniqueName("ECalBarrel_p")).name());
        // the hadron calorimeter, brass and scintillator layers
        for (int i = 0; i < hLayers; i++) {
            final String ri = hcalR + "+" + i + "*(" + hAbs + "+" + hAct + ")";
            final LogicalVolume a = volume(g, "HcalAbs" + i, tube(ri, ri + "+" + hAbs, length), "G4_BRASS");
            final LogicalVolume s = volume(g, "HcalTile" + i, tube(ri + "+" + hAbs, ri + "+" + hAbs + "+" + hAct, length),
                "G4_PLASTIC_SC_VINYLTOLUENE");
            s.setSensitive("HcalSD");
            g.place(a, g.world(), g.uniqueName("HcalAbs" + i + "_p")).setCopyNumber(i);
            final PhysicalVolume ps = g.place(s, g.world(), g.uniqueName("HcalTile" + i + "_p"));
            ps.setCopyNumber(i);
            out.add(ps.name());
        }
        // the coil, then the iron return yoke with its muon chambers
        out.add(g.place(volume(g, "Coil", tube(coilR, coilR + "+" + coilT, length), "G4_Al"), g.world(),
            g.uniqueName("Coil_p")).name());
        final double yoke0 = eval(g, coilR + "+" + coilT) + 50;
        double r = yoke0;
        for (int i = 0; i < 3; i++) {
            final LogicalVolume fe = volume(g, "Yoke" + i, tube(mm(r), mm(r + 300), length), "G4_Fe");
            g.place(fe, g.world(), g.uniqueName("Yoke" + i + "_p")).setCopyNumber(i);
            final LogicalVolume ch = volume(g, "MuonChamber" + i, tube(mm(r + 320), mm(r + 360), length), "G4_Ar");
            ch.setSensitive("MuonSD");
            final PhysicalVolume pc = g.place(ch, g.world(), g.uniqueName("MuonChamber" + i + "_p"));
            pc.setCopyNumber(i);
            out.add(pc.name());
            r += 400;
        }
        ensureWorld(g, r, r, eval(g, length) / 2);
        gun(g, "pi-", "50*GeV", "0*mm 0*mm 0*mm", "1 0 0");
        return out;
    }

    static List<String> fixedTarget(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String tt = var(g, "ft_target_t", len(o, "target", "10*mm", unit));
        final String tm = mat(g, o, "material", "G4_Be");
        final List<String> out = new ArrayList<>();
        out.add(place(g, volume(g, "Target", box("5*cm", "5*cm", tt), tm), g.world(), "Target_p", "0*mm", "0*mm", "0*mm")
            .name());
        // silicon stations before the magnet
        final LogicalVolume si = volume(g, "Station", box("10*cm", "10*cm", "300*um"), "G4_Si");
        si.setSensitive("StationSD");
        for (int i = 0; i < 3; i++) {
            final PhysicalVolume pv = place(g, si, g.world(), "Station_" + i, "0*mm", "0*mm", mm(300 + 200 * i));
            pv.setCopyNumber(i);
            out.add(pv.name());
        }
        // the dipole's iron yoke, its gap open along the beam
        final LogicalVolume yoke = shell(g, "DipoleYoke", box("1.6*m", "1.2*m", "1*m"), box("60*cm", "30*cm", "1002*mm"),
            "G4_Fe");
        out.add(place(g, yoke, g.world(), "DipoleYoke_p", "0*mm", "0*mm", "1.5*m").name());
        // scintillator stations after it
        final LogicalVolume sc = volume(g, "ScintStation", box("1*m", "1*m", "1*cm"), "G4_PLASTIC_SC_VINYLTOLUENE");
        sc.setSensitive("ScintSD");
        for (int i = 0; i < 2; i++) {
            final PhysicalVolume pv = place(g, sc, g.world(), "ScintStation_" + i, "0*mm", "0*mm", mm(2400 + 400 * i));
            pv.setCopyNumber(i);
            out.add(pv.name());
        }
        // the calorimeter, then the muon filter and its station
        final String n = var(g, "ft_ecal_layers", "20");
        out.add(sampling(g, g.world(), new String[] {"FtEcal", "FtLayer", "FtLead", "FtScint"}, n, "5*mm", "5*mm", "1.2*m",
            "G4_Pb", "G4_PLASTIC_SC_VINYLTOLUENE", "EcalSD", "0*mm", "0*mm", "3.2*m").name());
        out.add(place(g, volume(g, "MuonFilter", box("1.4*m", "1.4*m", "1*m"), "G4_Fe"), g.world(), "MuonFilter_p", "0*mm",
            "0*mm", "4*m").name());
        final LogicalVolume mu = volume(g, "MuonStation", box("1.5*m", "1.5*m", "1*cm"), "G4_PLASTIC_SC_VINYLTOLUENE");
        mu.setSensitive("MuonSD");
        out.add(place(g, mu, g.world(), "MuonStation_p", "0*mm", "0*mm", "4.6*m").name());
        ensureWorld(g, 800, 750, 4700);
        gun(g, "pi-", "20*GeV", null, "0 0 1");
        return out;
    }

    // ------------------------------------------------------------------
    // Astroparticle and neutrinos
    // ------------------------------------------------------------------

    static List<String> cherenkov(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String r = var(g, "wc_r", len(o, "radius", "1.5*m", unit));
        final String h = var(g, "wc_h", len(o, "height", "3*m", unit));
        final String wall = var(g, "wc_wall", len(o, "wall", "5*mm", unit));
        final String pmt = var(g, "wc_pmt", len(o, "pmt", "10*cm", unit));
        final int rings = count(g, o, "rings", 5), per = count(g, o, "per_ring", 16);
        final LogicalVolume tank = volume(g, "Tank", tube("0*mm", r + "+" + wall, h + "+2*" + wall), "G4_STAINLESS-STEEL");
        final LogicalVolume water = volume(g, "Water", tube("0*mm", r, h), "G4_WATER");
        water.setColor(0x305078C8);
        final LogicalVolume tube = volume(g, "PMT", orb(pmt), "G4_GLASS_PLATE");
        tube.setSensitive("PmtSD");
        final String rc = "(" + r + "-" + pmt + "-10*mm)";
        for (int k = 0; k < rings; k++) {
            for (int j = 0; j < per; j++) {
                final double phi = 2 * Math.PI * j / per;
                final PhysicalVolume pv = place(g, tube, water, "PMT_" + k + "_" + j, rc + "*(" + Units.num(Math.cos(phi)) + ")",
                    rc + "*(" + Units.num(Math.sin(phi)) + ")", "-" + h + "/2+" + Units.num((k + 0.5) / rings) + "*" + h);
                pv.setCopyNumber(k * per + j);
            }
        }
        g.place(water, tank, g.uniqueName("Water_p"));
        final PhysicalVolume pt = g.place(tank, g.world(), g.uniqueName("Tank_p"));
        final double rr = eval(g, r + "+" + wall);
        ensureWorld(g, rr, rr, eval(g, h) / 2 + eval(g, wall));
        gun(g, "mu-", "1*GeV", "0*mm 0*mm " + mm(0.95 * worldHalf(g, 2)), "0 0 -1");
        return List.of(pt.name());
    }

    static List<String> cosmic(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String obj = var(g, "cos_object", len(o, "object", "50*cm", unit));
        final String size = var(g, "cos_size", len(o, "size", "1*m", unit));
        final String t = var(g, "cos_t", len(o, "thickness", "1*cm", unit));
        final String gap = var(g, "cos_gap", len(o, "gap", "10*cm", unit));
        final String spacing = var(g, "cos_spacing", len(o, "spacing", "20*cm", unit));
        final String m = mat(g, o, "material", "G4_Fe");
        final List<String> out = new ArrayList<>();
        out.add(place(g, volume(g, "Object", box(obj, obj, obj), m), g.world(), "Object_p", "0*mm", "0*mm", "0*mm").name());
        final LogicalVolume plane = volume(g, "Plane", box(size, t, size), "G4_PLASTIC_SC_VINYLTOLUENE");
        plane.setSensitive("TelescopeSD");
        final String near = "(" + obj + "/2+" + gap + "+" + t + "/2)", far = "(" + obj + "/2+" + gap + "+" + spacing + "+" + t
            + "/2)";
        final String[] ys = {far, near, "-" + near, "-" + far};
        for (int i = 0; i < 4; i++) {
            final PhysicalVolume pv = place(g, plane, g.world(), "Plane_" + i, "0*mm", ys[i], "0*mm");
            pv.setCopyNumber(i);
            out.add(pv.name());
        }
        final double s = eval(g, size) / 2;
        ensureWorld(g, s, eval(g, far) + eval(g, t), s);
        gun(g, "mu-", "4*GeV", "0*mm " + mm(0.95 * worldHalf(g, 1)) + " 0*mm", "0 -1 0");
        return out;
    }

    // ------------------------------------------------------------------
    // Nuclear and medical
    // ------------------------------------------------------------------

    static List<String> hpge(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String r = var(g, "ge_r", len(o, "radius", "30*mm", unit));
        final String l = var(g, "ge_length", len(o, "length", "60*mm", unit));
        final String bore = var(g, "ge_bore", len(o, "bore", "5*mm", unit));
        final String dead = var(g, "ge_dead", len(o, "dead", "0.7*mm", unit));
        final String pb = var(g, "ge_lead", len(o, "lead", "10*cm", unit));
        final String cu = var(g, "ge_copper", len(o, "copper", "3*mm", unit));
        final String cavity = var(g, "ge_cavity", len(o, "cavity", "20*cm", unit));
        final String capR = var(g, "ge_cap_r", r + "+7*mm");
        final String capL = var(g, "ge_cap_length", l + "+60*mm");
        final String window = var(g, "ge_window", "1*mm");
        final List<String> out = new ArrayList<>();
        // the castle: lead, lined with copper, around a cavity
        final String inner = cavity + "+2*" + cu, outer = inner + "+2*" + pb;
        out.add(g.place(shell(g, "LeadCastle", box(outer, outer, outer), box(inner, inner, inner), "G4_Pb"), g.world(),
            g.uniqueName("LeadCastle_p")).name());
        out.add(g.place(shell(g, "CopperLining", box(inner, inner, inner), box(cavity, cavity, cavity), "G4_Cu"), g.world(),
            g.uniqueName("CopperLining_p")).name());
        // the end cap, its vacuum, the crystal near the window, the cold finger behind it
        final LogicalVolume cap = volume(g, "EndCap", tube("0*mm", capR + "+1.5*mm", capL), "G4_Al");
        final LogicalVolume vac = volume(g, "Vacuum", tube("0*mm", capR, capL + "-2*" + window), "G4_Galactic");
        vac.setVisible(false);
        final LogicalVolume ge = volume(g, "Germanium", tube(bore, r + "-" + dead, l), "G4_Ge");
        ge.setSensitive("GeSD");
        final LogicalVolume dl = volume(g, "DeadLayer", tube(r + "-" + dead, r, l), "G4_Ge");
        final String zc = "(" + capL + "/2-" + window + "-5*mm-" + l + "/2)";
        place(g, ge, vac, "Germanium_p", "0*mm", "0*mm", zc);
        place(g, dl, vac, "DeadLayer_p", "0*mm", "0*mm", zc);
        final String fingerTop = zc + "-" + l + "/2", fingerBottom = "(-" + capL + "/2+" + window + ")";
        final LogicalVolume finger = volume(g, "ColdFinger", tube("0*mm", "4*mm", fingerTop + "-" + fingerBottom), "G4_Cu");
        place(g, finger, vac, "ColdFinger_p", "0*mm", "0*mm", "(" + fingerTop + "+" + fingerBottom + ")/2");
        g.place(vac, cap, g.uniqueName("Vacuum_p"));
        out.add(0, g.place(cap, g.world(), g.uniqueName("EndCap_p")).name());
        final double h = eval(g, outer) / 2;
        ensureWorld(g, h, h, h);
        // a 137Cs line, 2 cm above the window, down the axis
        gun(g, "gamma", "662*keV", "0*mm 0*mm " + mm(eval(g, capL) / 2 + 20), "0 0 -1");
        return out;
    }

    static List<String> phantom(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String size = var(g, "ph_size", len(o, "size", "30*cm", unit));
        final String bone = var(g, "ph_bone", len(o, "bone", "2*cm", unit));
        final LogicalVolume water = volume(g, "Phantom", box(size, size, size), mat(g, o, "material", "G4_WATER"));
        water.setSensitive("PhantomSD");
        final LogicalVolume b = volume(g, "BoneSlab", box(size, size, bone), "G4_BONE_COMPACT_ICRU");
        g.place(b, water, g.uniqueName("BoneSlab_p")).setPosition("0*mm", "0*mm", "-" + size + "/6");
        // a lung insert beside the axis, past the bone
        final LogicalVolume lung = volume(g, "LungInsert", tube("0*mm", size + "/8", size + "/3"), "G4_LUNG_ICRP");
        place(g, lung, water, "LungInsert_p", size + "/4", "0*mm", size + "/8");
        final PhysicalVolume pv = g.place(water, g.world(), g.uniqueName("Phantom_p"));
        final double h = eval(g, size) / 2;
        ensureWorld(g, h, h, h);
        gun(g, "proton", "150*MeV", null, "0 0 1");
        return List.of(pv.name());
    }

    static List<String> shield(Geometry g, Map<String, String> o, String unit) throws GeometryException {
        final String inner = var(g, "sh_inner", len(o, "inner", len(o, "size", "1*m", unit), unit));
        final String t = var(g, "sh_t", len(o, "thickness", "20*cm", unit));
        final String m = mat(g, o, "material", "G4_CONCRETE");
        final String outer = inner + "+2*" + t;
        final List<String> out = new ArrayList<>();
        out.add(g.place(shell(g, "Shield", box(outer, outer, outer), box(inner, inner, inner), m), g.world(),
            g.uniqueName("Shield_p")).name());
        // a lead lining on the walls' inner face
        final String lining = len(o, "lining", "0*mm", unit);
        if (eval(g, lining) > 0) {
            final String lt = var(g, "sh_lining", lining);
            final String in = inner + "-2*" + lt;
            out.add(g.place(shell(g, "Lining", box(inner, inner, inner), box(in, in, in), "G4_Pb"), g.world(),
                g.uniqueName("Lining_p")).name());
        }
        final double h = eval(g, inner) / 2 + eval(g, t);
        ensureWorld(g, h, h, h);
        gun(g, "gamma", "1.25*MeV", "0*mm 0*mm 0*mm", "1 0 0");
        return out;
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}

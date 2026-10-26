package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The design assistant: what a Geant4 expert would say looking over the
 * shoulder. Each finding comes with fixes that are console commands, so a
 * click applies them and the log shows exactly what changed.
 *
 * It knows the geometry (volumes sticking out of their mother, thin
 * volumes, unused objects, rows of copies that could be replicas, deep
 * boolean chains), the code it will turn into (names that are not C++
 * identifiers) and the physics around it (sensitive layers thinner than the
 * default production cut, which physics list suits the materials present).
 */
public final class Advisor {

    public enum Level { ERROR, WARNING, IDEA, INFO }

    public record Fix(String label, String command) {
    }

    public record Advice(Level level, String title, String detail, String target, List<Fix> fixes) {
    }

    private Advisor() {
    }

    /** Geant4's default production cut, 0.7 mm (G4ProductionCutsTable default). */
    public static final double DEFAULT_CUT = 0.7;

    public static List<Advice> review(Geometry g) {
        final List<Advice> out = new ArrayList<>();
        for (Geometry.Issue i : g.issues()) {
            if (i.severity() == Geometry.Severity.ERROR) {
                out.add(new Advice(Level.ERROR, "Invalid model", i.message(), name(i.target()), List.of()));
            }
        }
        if (g.world() == null || g.world().solid() == null || g.world().solid().error() != null) {
            return out;
        }
        world(g, out);
        containment(g, out);
        thin(g, out);
        sensitive(g, out);
        rows(g, out);
        booleans(g, out);
        unused(g, out);
        names(g, out);
        physics(g, out);
        return out;
    }

    private static String name(Object o) {
        if (o instanceof PhysicalVolume pv) {
            return pv.name();
        }
        if (o instanceof LogicalVolume lv) {
            return lv.name();
        }
        if (o instanceof Solid s) {
            return s.name();
        }
        if (o instanceof Define d) {
            return d.name();
        }
        return null;
    }

    private static void world(Geometry g, List<Advice> out) {
        final LogicalVolume w = g.world();
        BBox content = BBox.EMPTY;
        for (Geometry.Instance i : g.instances()) {
            if (i.depth() == 1) {
                content = content.union(Geometry.worldBox(i));
            }
        }
        final BBox wb = w.solid().extent();
        if (!content.isEmpty() && !wb.contains(content)) {
            out.add(new Advice(Level.ERROR, "Volumes stick out of the world",
                "Geant4 refuses a geometry whose daughters leave the world volume.", w.name(),
                List.of(new Fix("Grow the world around the detector", "world fit"))));
        } else if (!content.isEmpty() && content.diagonal() > 0 && wb.diagonal() > 20 * content.diagonal()) {
            out.add(new Advice(Level.IDEA, "The world is much larger than the detector",
                "Particles are tracked through all of it; a tighter world saves time.", w.name(),
                List.of(new Fix("Fit the world with a 10 % margin", "world fit"))));
        }
        if ("G4_AIR".equals(w.material()) && g.instances().size() > 1) {
            out.add(new Advice(Level.INFO, "The world is air",
                "Right for a detector in a hall. For a beam line or a study of the detector alone, vacuum (G4_Galactic) "
                    + "removes scattering in the air.", w.name(),
                List.of(new Fix("Make the world vacuum", "world material G4_Galactic"))));
        }
    }

    /** Daughters whose boxes leave their mother's box: a quick hint before a real overlap check. */
    private static void containment(Geometry g, List<Advice> out) {
        int n = 0;
        for (LogicalVolume m : g.volumes()) {
            if (m.solid() == null || m.solid().error() != null) {
                continue;
            }
            final BBox mb = m.solid().extent().grow(1e-6);
            for (PhysicalVolume pv : m.daughters()) {
                if (pv.logical().solid() == null || pv.logical().solid().error() != null) {
                    continue;
                }
                for (int c = 0; c < pv.copies(); c++) {
                    final BBox db = pv.copyTransform(c).apply(pv.logical().solid().extent());
                    if (!mb.contains(db)) {
                        if (m == g.world()) {
                            break; // reported by the world rule
                        }
                        if (n++ < 20) {
                            out.add(new Advice(Level.WARNING, pv.name() + " may stick out of " + m.name(),
                                "Its bounding box leaves its mother's. Geant4 would mis-track particles there.",
                                pv.name(), List.of(new Fix("Check overlaps precisely", "check"))));
                        }
                        break;
                    }
                }
            }
        }
    }

    private static void thin(Geometry g, List<Advice> out) {
        for (LogicalVolume lv : g.volumes()) {
            final Solid s = lv.solid();
            if (s == null || s.error() != null) {
                continue;
            }
            final Vec3 size = s.extent().size();
            final double min = Math.min(size.x(), Math.min(size.y(), size.z()));
            if (min < 1e-3) {
                out.add(new Advice(Level.WARNING, lv.name() + " is thinner than a micrometer",
                    "At " + Units.best(min, Units.Category.LENGTH) + " it approaches Geant4's surface tolerance (1e-9 mm "
                        + "scaled by the world size); navigation may skip it.", lv.name(), List.of()));
            }
        }
    }

    private static boolean activeLooking(String material) {
        final String m = material.toUpperCase(Locale.ROOT);
        return m.equals("G4_SI") || m.equals("G4_GE") || m.contains("SCINT") || m.contains("VINYLTOLUENE")
            || m.contains("POLYSTYRENE") || m.contains("PBWO4") || m.contains("BGO") || m.contains("IODIDE")
            || m.contains("LAR") || m.contains("LXE") || m.equals("G4_AR") || m.equals("G4_XE") || m.contains("WATER")
            || m.contains("TISSUE") || m.contains("DIAMOND") || m.contains("CDTE") || m.contains("GLASS_LEAD");
    }

    private static void sensitive(Geometry g, List<Advice> out) {
        final List<LogicalVolume> sds = new ArrayList<>();
        for (LogicalVolume lv : g.volumes()) {
            if (lv.sensitive() != null) {
                sds.add(lv);
            }
        }
        if (sds.isEmpty() && g.instances().size() > 1) {
            final List<Fix> fixes = new ArrayList<>();
            for (LogicalVolume lv : g.volumes()) {
                if (lv != g.world() && activeLooking(lv.material()) && fixes.size() < 4) {
                    fixes.add(new Fix("Make " + lv.name() + " (" + lv.material() + ") sensitive", "sensitive " + lv.name()));
                }
            }
            out.add(new Advice(Level.IDEA, "No sensitive detector",
                "Nothing is recorded during a run until a volume is a sensitive detector. The exported C++ project "
                    + "scores energy in every sensitive volume.", null, fixes));
        }
        // Thin sensitive layers versus the default production cut
        for (LogicalVolume lv : sds) {
            final Solid s = lv.solid();
            if (s == null || s.error() != null) {
                continue;
            }
            final double t = s.thickness();
            if (t < DEFAULT_CUT) {
                out.add(new Advice(Level.IDEA, lv.name() + " is thinner than the production cut",
                    "It is " + Units.best(t, Units.Category.LENGTH) + " thick, below Geant4's default 0.7 mm range cut: "
                        + "delta rays and their energy are then deposited locally instead of tracked. Use a region with a "
                        + "smaller cut (G4Region, /run/setCutForRegion) - the exported project does it when asked.",
                    lv.name(), List.of()));
            }
        }
    }

    /** Copies of one volume in a row with a constant pitch: candidates for G4PVReplica or G4PVParameterised. */
    private static void rows(Geometry g, List<Advice> out) {
        for (LogicalVolume m : g.volumes()) {
            final Map<LogicalVolume, List<PhysicalVolume>> by = new LinkedHashMap<>();
            for (PhysicalVolume pv : m.daughters()) {
                if (!pv.isReplica()) {
                    by.computeIfAbsent(pv.logical(), k -> new ArrayList<>()).add(pv);
                }
            }
            for (Map.Entry<LogicalVolume, List<PhysicalVolume>> e : by.entrySet()) {
                final List<PhysicalVolume> l = e.getValue();
                if (l.size() < 4) {
                    continue;
                }
                final List<Vec3> pts = new ArrayList<>();
                for (PhysicalVolume pv : l) {
                    pts.add(pv.transform().translation());
                }
                pts.sort((a, b) -> Double.compare(a.x() + a.y() * 1.0001 + a.z() * 1.0002, b.x() + b.y() * 1.0001 + b.z() * 1.0002));
                final Vec3 step = pts.get(1).sub(pts.get(0));
                boolean regular = step.length() > 0;
                for (int i = 2; i < pts.size() && regular; i++) {
                    regular = pts.get(i).sub(pts.get(i - 1)).sub(step).length() < 1e-6 * Math.max(1, step.length());
                }
                if (regular) {
                    out.add(new Advice(Level.IDEA, l.size() + " copies of " + e.getKey().name() + " in a row",
                        "They are evenly spaced every " + Units.best(step.length(), Units.Category.LENGTH) + ". If an envelope "
                            + "around them were filled by them, a G4PVReplica would describe them in one placement and "
                            + "navigate faster; otherwise placements are right.", e.getKey().name(), List.of()));
                }
            }
            if (m.daughters().size() > 2000) {
                out.add(new Advice(Level.INFO, m.name() + " holds " + m.daughters().size() + " placements",
                    "Geant4 voxelizes them, but the memory grows with each one; a parameterization or nested envelopes "
                        + "keep large arrays light.", m.name(), List.of()));
            }
        }
    }

    private static int depth(Solid s) {
        int d = 0;
        for (Solid o : s.operands()) {
            d = Math.max(d, depth(o));
        }
        return s.operands().isEmpty() ? 0 : d + 1;
    }

    private static void booleans(Geometry g, List<Advice> out) {
        for (LogicalVolume lv : g.volumes()) {
            if (lv.solid() != null && depth(lv.solid()) > 3) {
                out.add(new Advice(Level.WARNING, lv.name() + " is a chain of " + depth(lv.solid()) + " booleans",
                    "Every step of a particle asks each boolean in turn: deep chains slow tracking a lot. A G4MultiUnion, a "
                        + "polycone or an extruded solid often says the same in one solid.", lv.name(), List.of()));
            }
        }
    }

    private static void unused(Geometry g, List<Advice> out) {
        final List<Solid> solids = g.unusedSolids();
        if (!solids.isEmpty()) {
            final StringBuilder cmd = new StringBuilder("delete");
            for (Solid s : solids) {
                cmd.append(' ').append(s.name());
            }
            out.add(new Advice(Level.INFO, solids.size() + " unused solid" + (solids.size() > 1 ? "s" : ""),
                "No volume uses " + solids.stream().map(Solid::name).limit(6).toList() + ".", null,
                List.of(new Fix("Remove them", cmd.toString()))));
        }
        final Set<LogicalVolume> placed = new java.util.HashSet<>();
        placed.add(g.world());
        for (PhysicalVolume pv : g.placements()) {
            placed.add(pv.logical());
        }
        for (LogicalVolume lv : g.volumes()) {
            if (!placed.contains(lv)) {
                out.add(new Advice(Level.INFO, lv.name() + " is not placed", "It is defined but nowhere in the tree.", lv.name(),
                    List.of(new Fix("Place it at the origin", "place " + lv.name()), new Fix("Remove it", "delete " + lv.name()))));
            }
        }
        // Variables nothing reads
        final Map<String, Integer> reads = new HashMap<>();
        for (Define d : g.defines()) {
            reads.put(d.name(), 0);
        }
        if (reads.isEmpty()) {
            return; // nothing to count (a ROOT geometry has no variables), so no expression to read
        }
        final java.util.function.Consumer<String> count = e -> {
            try {
                for (String v : Expr.parse(e).variables()) {
                    reads.computeIfPresent(v, (k, n) -> n + 1);
                }
            } catch (GeometryException ignored) {
                // invalid expressions are reported elsewhere
            }
        };
        for (Solid s : g.solids()) {
            s.params().forEach(p -> count.accept(p.expr()));
            for (Solid.Table t : s.tables()) {
                t.rows().forEach(r -> java.util.Arrays.stream(r).forEach(count));
            }
        }
        for (Define d : g.defines()) {
            count.accept(d.effectiveExpr());
        }
        for (PhysicalVolume pv : g.placements()) {
            java.util.Arrays.stream(pv.positionExprs()).forEach(count);
            java.util.Arrays.stream(pv.rotationExprs()).forEach(count);
            if (pv.replica() != null) {
                count.accept(pv.replica().number());
                count.accept(pv.replica().width());
                count.accept(pv.replica().offset());
            }
        }
        reads.forEach((k, n) -> {
            if (n == 0) {
                out.add(new Advice(Level.INFO, "Variable " + k + " is not used", "Nothing reads it.", k,
                    List.of(new Fix("Remove it", "delete " + k))));
            }
        });
    }

    private static final Set<String> CPP_KEYWORDS = Set.of("alignas", "alignof", "and", "asm", "auto", "bool", "break",
        "case", "catch", "char", "class", "const", "continue", "default", "delete", "do", "double", "else", "enum",
        "explicit", "export", "extern", "false", "float", "for", "friend", "goto", "if", "inline", "int", "long",
        "mutable", "namespace", "new", "not", "operator", "or", "private", "protected", "public", "register", "return",
        "short", "signed", "sizeof", "static", "struct", "switch", "template", "this", "throw", "true", "try", "typedef",
        "union", "unsigned", "using", "virtual", "void", "volatile", "while", "world");

    private static void names(Geometry g, List<Advice> out) {
        for (Define d : g.defines()) {
            if (CPP_KEYWORDS.contains(d.name())) {
                out.add(new Advice(Level.WARNING, "Variable " + d.name() + " is a C++ keyword",
                    "The exported DetectorConstruction would not compile.", d.name(),
                    List.of(new Fix("Rename it " + d.name() + "_", "rename " + d.name() + " " + d.name() + "_"))));
            }
        }
    }

    /** The reference physics list the assistant recommends for this geometry. */
    public static String recommendedPhysicsList(Geometry g) {
        final List<Advice> tmp = new ArrayList<>();
        physics(g, tmp);
        final String t = tmp.get(0).title();
        return t.substring(t.indexOf(':') + 1).trim();
    }

    /** Which reference physics list fits the materials and the detector. */
    private static void physics(Geometry g, List<Advice> out) {
        boolean hydrogenous = false, medical = false, calo = false, gas = false;
        for (LogicalVolume lv : g.volumes()) {
            final String m = lv.material().toUpperCase(Locale.ROOT);
            hydrogenous |= m.contains("POLYETHYLENE") || m.contains("CONCRETE") || m.contains("PARAFFIN") || m.contains("BORON");
            medical |= m.contains("TISSUE") || m.contains("BONE") || m.contains("MUSCLE") || (m.contains("WATER") && lv.sensitive() != null);
            calo |= m.contains("PBWO4") || m.contains("BGO") || m.contains("LAR") || (m.equals("G4_PB") || m.equals("G4_W"));
            gas |= m.equals("G4_AR") || m.equals("G4_XE") || m.contains("CO2") || m.contains("METHANE");
        }
        String list = "FTFP_BERT";
        String why = "the general-purpose list of the LHC experiments";
        if (medical) {
            list = "QGSP_BIC_EMZ";
            why = "binary cascade below a few GeV and the most precise electromagnetic option, for dose in tissue";
        } else if (hydrogenous) {
            list = "QGSP_BIC_HP";
            why = "high-precision neutron transport below 20 MeV, for shielding and moderators";
        } else if (calo) {
            list = "FTFP_BERT_EMZ";
            why = "hadronic showers as at the LHC with the precise electromagnetic option for calorimetry";
        } else if (gas) {
            list = "FTFP_BERT_EMZ";
            why = "precise ionization and multiple scattering in thin gas layers (add G4PAIModel for cluster counting)";
        }
        out.add(new Advice(Level.INFO, "Physics list: " + list, "Chosen for the materials present: " + why
            + ". The exported project and the bridge use it; change it in the Run panel.", null, List.of()));
    }
}

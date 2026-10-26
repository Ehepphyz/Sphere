package com.sphere.core.geant4;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes a complete Geant4 application for the document, laid out like the
 * basic examples of the Geant4 distribution: a DetectorConstruction that
 * builds the geometry in C++ (the document's variables become constants, its
 * expressions C++ expressions), sensitive detectors scoring energy per copy,
 * an analysis manager writing a ROOT file, a particle gun, macros and CMake.
 *
 * The same program reads a GDML file instead when given "-g file.gdml", so
 * the project keeps working when the design changes in the editor.
 */
public final class CppExport {

    /** What the generated application does by default. */
    public record Options(String appName, String physicsList, String particle, String energy, String[] gunPosition,
                          String[] gunDirection, int events, boolean checkOverlaps) {
        public static Options defaults(Geometry g) {
            final Map<String, String> s = g.settings();
            return new Options(
                cppId(s.getOrDefault("app", g.title().isBlank() ? "detector" : g.title())),
                s.getOrDefault("physicsList", Advisor.recommendedPhysicsList(g)),
                s.getOrDefault("gun.particle", "e-"),
                s.getOrDefault("gun.energy", "1*GeV"),
                split3(s.getOrDefault("gun.position", "")),
                split3(s.getOrDefault("gun.direction", "0 0 1")),
                Integer.parseInt(s.getOrDefault("run.events", "100")),
                true);
        }
    }

    private static String[] split3(String s) {
        final String[] f = s.trim().split("\\s+");
        return f.length == 3 ? f : null;
    }

    private final Geometry g;
    private final Options o;
    private final Map<Object, String> ids = new HashMap<>();
    private final Set<String> used = new HashSet<>();

    public CppExport(Geometry g, Options o) {
        this.g = g;
        this.o = o;
    }

    /** Writes the project into a folder; returns the files written. */
    public List<Path> write(Path dir) throws IOException {
        final List<Path> out = new ArrayList<>();
        Files.createDirectories(dir.resolve("include"));
        Files.createDirectories(dir.resolve("src"));
        final Map<String, String> files = files();
        for (Map.Entry<String, String> e : files.entrySet()) {
            final Path p = dir.resolve(e.getKey());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            out.add(p);
        }
        return out;
    }

    /** Every file of the project, by relative path. */
    public Map<String, String> files() {
        final Map<String, String> f = new LinkedHashMap<>();
        final String app = o.appName();
        f.put("CMakeLists.txt", cmake(app));
        f.put(app + ".cc", main(app));
        f.put("include/DetectorConstruction.hh", detectorHeader());
        f.put("src/DetectorConstruction.cc", detectorSource());
        f.put("include/SensitiveDetector.hh", sdHeader());
        f.put("src/SensitiveDetector.cc", sdSource());
        f.put("include/ActionInitialization.hh", actionsHeader());
        f.put("src/ActionInitialization.cc", actionsSource());
        f.put("geometry.gdml", Gdml.write(g));
        f.put("vis.mac", visMacro());
        f.put("run.mac", runMacro());
        f.put("README.md", readme(app));
        return f;
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    public static String cppId(String s) {
        String r = s.replaceAll("[^A-Za-z0-9_]", "_");
        if (r.isEmpty() || Character.isDigit(r.charAt(0))) {
            r = "_" + r;
        }
        return r;
    }

    private String id(Object o, String prefix, String name) {
        return ids.computeIfAbsent(o, k -> {
            String base = prefix + cppId(name);
            String n = base;
            for (int i = 2; used.contains(n); i++) {
                n = base + "_" + i;
            }
            used.add(n);
            return n;
        });
    }

    private final Solid.CppNames names = new Solid.CppNames() {
        public String solid(Solid s) {
            return id(s, "s_", s.name());
        }

        public String string(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    };

    private static String cpp(String expr) {
        try {
            return Expr.parse(expr).toCpp();
        } catch (GeometryException e) {
            return "0. /* invalid: " + expr.replace("*/", "") + " */";
        }
    }

    // ------------------------------------------------------------------
    // DetectorConstruction
    // ------------------------------------------------------------------

    private String detectorHeader() {
        return """
            #ifndef DetectorConstruction_h
            #define DetectorConstruction_h 1

            // Generated by Sphere G4editor from the GDML design "%s".

            #include "G4VUserDetectorConstruction.hh"
            #include "globals.hh"

            class G4VPhysicalVolume;
            class G4LogicalVolume;

            class DetectorConstruction : public G4VUserDetectorConstruction
            {
              public:
                // Builds the geometry in C++, or reads it from a GDML file when one is given.
                explicit DetectorConstruction(const G4String& gdmlFile = "");
                ~DetectorConstruction() override = default;

                G4VPhysicalVolume* Construct() override;
                void ConstructSDandField() override;

              private:
                G4VPhysicalVolume* ConstructFromGdml();
                G4String fGdmlFile;
                G4bool fCheckOverlaps = %s;
            };

            #endif
            """.formatted(g.title(), o.checkOverlaps() ? "true" : "false");
    }

    private String detectorSource() {
        final StringBuilder b = new StringBuilder();
        b.append("""
            // Generated by Sphere G4editor. The geometry below is the editor's document:
            // its variables are the constants at the top, its expressions are kept as written.

            #include "DetectorConstruction.hh"
            #include "SensitiveDetector.hh"

            #include "G4Box.hh"
            #include "G4Cons.hh"
            #include "G4CutTubs.hh"
            #include "G4DisplacedSolid.hh"
            #include "G4Ellipsoid.hh"
            #include "G4EllipticalCone.hh"
            #include "G4EllipticalTube.hh"
            #include "G4ExtrudedSolid.hh"
            #include "G4GenericPolycone.hh"
            #include "G4GenericTrap.hh"
            #include "G4Hype.hh"
            #include "G4IntersectionSolid.hh"
            #include "G4Orb.hh"
            #include "G4Para.hh"
            #include "G4Paraboloid.hh"
            #include "G4Polycone.hh"
            #include "G4Polyhedra.hh"
            #include "G4QuadrangularFacet.hh"
            #include "G4ScaledSolid.hh"
            #include "G4Sphere.hh"
            #include "G4SubtractionSolid.hh"
            #include "G4TessellatedSolid.hh"
            #include "G4Tet.hh"
            #include "G4Torus.hh"
            #include "G4Trap.hh"
            #include "G4Trd.hh"
            #include "G4TriangularFacet.hh"
            #include "G4Tubs.hh"
            #include "G4TwoVector.hh"
            #include "G4UnionSolid.hh"
            #include "G4ReflectionFactory.hh"
            #include "G4Transform3D.hh"

            #include "G4Element.hh"
            #include "G4GDMLParser.hh"
            #include "G4LogicalVolume.hh"
            #include "G4LogicalVolumeStore.hh"
            #include "G4Material.hh"
            #include "G4NistManager.hh"
            #include "G4PVPlacement.hh"
            #include "G4PVReplica.hh"
            #include "G4ProductionCuts.hh"
            #include "G4Region.hh"
            #include "G4SDManager.hh"
            #include "G4SystemOfUnits.hh"
            #include "G4PhysicalConstants.hh"
            #include "G4TwoVector.hh"
            #include "G4VisAttributes.hh"

            #include <algorithm>
            #include <cmath>
            #include <vector>

            using namespace CLHEP;

            DetectorConstruction::DetectorConstruction(const G4String& gdmlFile) : fGdmlFile(gdmlFile) {}

            G4VPhysicalVolume* DetectorConstruction::ConstructFromGdml()
            {
              G4GDMLParser parser;
              parser.Read(fGdmlFile, false);
              // Colours and visibility travel as auxiliary tags of the volumes
              for (auto* lv : *G4LogicalVolumeStore::GetInstance()) {
                for (const auto& aux : parser.GetVolumeAuxiliaryInformation(lv)) {
                  if (aux.type == "Color" && aux.value.size() >= 7) {
                    const auto hex = [&](std::size_t i) { return std::stoi(aux.value.substr(i, 2), nullptr, 16) / 255.; };
                    const G4double a = aux.value.size() >= 9 ? hex(7) : 1.;
                    auto* va = new G4VisAttributes(G4Colour(hex(1), hex(3), hex(5), a));
                    lv->SetVisAttributes(va);
                  }
                  if (aux.type == "Visible" && aux.value == "false") lv->SetVisAttributes(G4VisAttributes::GetInvisible());
                }
              }
              return parser.GetWorldVolume();
            }

            G4VPhysicalVolume* DetectorConstruction::Construct()
            {
              if (!fGdmlFile.empty()) return ConstructFromGdml();

              auto* nist = G4NistManager::Instance();

            """);
        defines(b);
        materials(b);
        solids(b);
        volumes(b);
        placements(b);
        regions(b);
        b.append("  return pv_world;\n}\n\n");
        sdBinding(b);
        return b.toString();
    }

    private void defines(StringBuilder b) {
        if (g.defines().isEmpty()) {
            return;
        }
        b.append("  // ---- Parameters (the document's variables)\n");
        final Map<String, Define> all = new LinkedHashMap<>();
        for (Define d : g.defines()) {
            all.put(d.name(), d);
        }
        final Set<String> done = new LinkedHashSet<>();
        for (Define d : g.defines()) {
            order(d.name(), all, done, new HashSet<>());
        }
        for (String n : done) {
            final Define d = all.get(n);
            if (d.comment() != null && !d.comment().isBlank()) {
                b.append("  // ").append(d.comment()).append('\n');
            }
            b.append("  const G4double ").append(cppId(n)).append(" = ").append(cpp(d.effectiveExpr())).append(";\n");
        }
        b.append('\n');
    }

    private void order(String n, Map<String, Define> all, Set<String> done, Set<String> visiting) {
        if (done.contains(n) || !all.containsKey(n) || !visiting.add(n)) {
            return;
        }
        try {
            for (String v : Expr.parse(all.get(n).effectiveExpr()).variables()) {
                order(v, all, done, visiting);
            }
        } catch (GeometryException ignored) {
            // emitted anyway; the compiler will say
        }
        done.add(n);
    }

    private final Map<String, String> matIds = new LinkedHashMap<>();

    private void materials(StringBuilder b) {
        b.append("  // ---- Materials\n");
        final Set<String> needed = new LinkedHashSet<>();
        for (LogicalVolume lv : g.volumes()) {
            needed.add(lv.material());
        }
        // Document materials may be made of other document materials: define those first
        final Set<String> emitted = new HashSet<>();
        for (String m : needed) {
            material(b, m, emitted);
        }
        b.append('\n');
    }

    private void material(StringBuilder b, String name, Set<String> emitted) {
        if (!emitted.add(name)) {
            return;
        }
        final String v = "mat_" + cppId(name);
        matIds.put(name, v);
        if (!g.materials().isDefined(name)) {
            b.append("  auto* ").append(v).append(" = nist->FindOrBuildMaterial(\"").append(name).append("\");\n");
            return;
        }
        final Material m = g.materials().material(name);
        for (Material.Part p : m.parts()) {
            if (g.materials().element(p.ref()) == null) {
                material(b, p.ref(), emitted);
            }
        }
        final String state = switch (m.state()) {
            case GAS -> "kStateGas";
            case LIQUID -> "kStateLiquid";
            case SOLID -> "kStateSolid";
            default -> "kStateUndefined";
        };
        b.append("  auto* ").append(v).append(" = new G4Material(\"").append(name).append("\", ")
         .append(Units.exact(m.densityGcm3())).append("*g/cm3, ").append(m.parts().size()).append(", ").append(state)
         .append(", ").append(Units.exact(m.temperature())).append("*kelvin, ").append(Units.exact(m.pressure()))
         .append("*atmosphere);\n");
        for (Material.Part p : m.parts()) {
            final Element e = g.materials().element(p.ref());
            if (e != null) {
                final String el = e.nist ? "nist->FindOrBuildElement(\"" + e.symbol + "\")"
                    : "new G4Element(\"" + e.name + "\", \"" + e.symbol + "\", " + e.z + ", " + Units.exact(e.a) + "*g/mole)";
                if (p.byAtoms()) {
                    b.append("  ").append(v).append("->AddElement(").append(el).append(", ").append(Math.round(p.amount()))
                     .append(");\n");
                } else {
                    b.append("  ").append(v).append("->AddElement(").append(el).append(", ").append(Units.exact(p.amount()))
                     .append(");\n");
                }
            } else {
                b.append("  ").append(v).append("->AddMaterial(").append(matIds.get(p.ref())).append(", ")
                 .append(Units.exact(p.amount())).append(");\n");
            }
        }
        if (m.meanExcitation() > 0) {
            b.append("  ").append(v).append("->GetIonisation()->SetMeanExcitationEnergy(")
             .append(Units.exact(m.meanExcitation())).append("*eV);\n");
        }
    }

    private void solids(StringBuilder b) {
        b.append("  // ---- Solids\n");
        final Set<Solid> done = new HashSet<>();
        for (LogicalVolume lv : g.volumes()) {
            solid(b, lv.solid(), done);
        }
        b.append('\n');
    }

    private void solid(StringBuilder b, Solid s, Set<Solid> done) {
        if (s == null || !done.add(s)) {
            return;
        }
        for (Solid op : s.operands()) {
            solid(b, op, done);
        }
        b.append("  auto* ").append(names.solid(s)).append(" = ").append(s.cpp(names)).append(";\n");
    }

    private void volumes(StringBuilder b) {
        b.append("  // ---- Logical volumes and how they are drawn\n");
        for (LogicalVolume lv : g.volumes()) {
            final String v = id(lv, "lv_", lv.name());
            b.append("  auto* ").append(v).append(" = new G4LogicalVolume(").append(names.solid(lv.solid())).append(", ")
             .append(matIds.get(lv.material())).append(", \"").append(lv.name()).append("\");\n");
            final int c = lv.color();
            b.append("  {\n    auto* va = new G4VisAttributes(G4Colour(").append(f(((c >> 16) & 0xFF) / 255.0)).append(", ")
             .append(f(((c >> 8) & 0xFF) / 255.0)).append(", ").append(f((c & 0xFF) / 255.0)).append(", ")
             .append(f(((c >>> 24) & 0xFF) / 255.0)).append("));\n");
            if (!lv.visible()) {
                b.append("    va->SetVisibility(false);\n");
            }
            if (lv.wireframe()) {
                b.append("    va->SetForceWireframe(true);\n");
            } else {
                b.append("    va->SetForceSolid(true);\n");
            }
            b.append("    ").append(v).append("->SetVisAttributes(va);\n  }\n");
        }
        b.append('\n');
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private void placements(StringBuilder b) {
        b.append("  // ---- Placements (G4PVPlacement(rotation, position): the rotation is GDML's, Rz Ry Rx)\n");
        b.append("  auto* pv_world = new G4PVPlacement(nullptr, G4ThreeVector(), ").append(id(g.world(), "lv_", g.world().name()))
         .append(", \"").append(g.world().name()).append("\", nullptr, false, 0, fCheckOverlaps);\n");
        for (PhysicalVolume pv : g.placements()) {
            final String lv = id(pv.logical(), "lv_", pv.logical().name()), m = id(pv.mother(), "lv_", pv.mother().name());
            if (pv.isReplica()) {
                final PhysicalVolume.Replica r = pv.replica();
                b.append("  new G4PVReplica(\"").append(pv.name()).append("\", ").append(lv).append(", ").append(m).append(", ")
                 .append(r.axis().name()).append(", static_cast<G4int>(").append(cpp(r.number())).append("), ")
                 .append(cpp(r.width())).append(", ").append(cpp(r.offset())).append(");\n");
                continue;
            }
            final String[] p = pv.positionExprs(), r = pv.rotationExprs();
            final boolean rotated = !(Gdml.isZero(r[0]) && Gdml.isZero(r[1]) && Gdml.isZero(r[2]));
            String rot = "nullptr";
            if (rotated) {
                rot = "rot_" + cppId(pv.name());
                b.append("  auto* ").append(rot).append(" = new G4RotationMatrix();\n");
                b.append("  ").append(rot).append("->rotateX(").append(cpp(r[0])).append(");\n");
                b.append("  ").append(rot).append("->rotateY(").append(cpp(r[1])).append(");\n");
                b.append("  ").append(rot).append("->rotateZ(").append(cpp(r[2])).append(");\n");
            }
            if (pv.reflected()) {
                // a mirrored placement: G4ReflectionFactory makes the reflected volumes, as G4GDMLReadStructure does
                b.append("  G4ReflectionFactory::Instance()->Place(G4Transform3D(")
                 .append(rotated ? rot + "->inverse()" : "G4RotationMatrix()").append(", G4ThreeVector(").append(cpp(p[0]))
                 .append(", ").append(cpp(p[1])).append(", ").append(cpp(p[2])).append(")) * G4ReflectZ3D(), \"")
                 .append(pv.name()).append("\", ").append(lv).append(", ").append(m).append(", false, ")
                 .append(pv.copyNumber()).append(", fCheckOverlaps);\n");
                continue;
            }
            b.append("  new G4PVPlacement(").append(rot).append(", G4ThreeVector(").append(cpp(p[0])).append(", ")
             .append(cpp(p[1])).append(", ").append(cpp(p[2])).append("), ").append(lv).append(", \"").append(pv.name())
             .append("\", ").append(m).append(", false, ").append(pv.copyNumber()).append(", fCheckOverlaps);\n");
        }
        b.append('\n');
    }

    /** Sensitive layers thinner than the default cut get a region with a cut a third of their thickness. */
    private void regions(StringBuilder b) {
        double thinnest = Double.MAX_VALUE;
        final List<LogicalVolume> thin = new ArrayList<>();
        for (LogicalVolume lv : g.volumes()) {
            if (lv.sensitive() == null || lv.solid() == null || lv.solid().error() != null) {
                continue;
            }
            final double t = lv.solid().thickness();
            if (t < Advisor.DEFAULT_CUT) {
                thin.add(lv);
                thinnest = Math.min(thinnest, t);
            }
        }
        if (thin.isEmpty()) {
            return;
        }
        final double cut = Math.max(1e-3, thinnest / 3);
        b.append("  // ---- Thin sensitive layers: a region with a production cut below their thickness\n");
        b.append("  {\n    auto* region = new G4Region(\"ThinSensitiveLayers\");\n");
        for (LogicalVolume lv : thin) {
            b.append("    ").append(id(lv, "lv_", lv.name())).append("->SetRegion(region);\n");
            b.append("    region->AddRootLogicalVolume(").append(id(lv, "lv_", lv.name())).append(");\n");
        }
        b.append("    auto* cuts = new G4ProductionCuts();\n    cuts->SetProductionCut(").append(Units.exact(cut))
         .append("*mm);\n    region->SetProductionCuts(cuts);\n  }\n\n");
    }

    private void sdBinding(StringBuilder b) {
        b.append("void DetectorConstruction::ConstructSDandField()\n{\n");
        final Map<String, List<LogicalVolume>> bySd = new LinkedHashMap<>();
        for (LogicalVolume lv : g.volumes()) {
            if (lv.sensitive() != null) {
                bySd.computeIfAbsent(lv.sensitive(), k -> new ArrayList<>()).add(lv);
            }
        }
        if (bySd.isEmpty()) {
            b.append("  // No sensitive detector in the design: mark volumes 'sensitive' in G4editor.\n}\n");
            return;
        }
        b.append("  auto* sdManager = G4SDManager::GetSDMpointer();\n");
        b.append("  // With a GDML file, the volumes come from its SensDet auxiliary tags; built in C++, from the design.\n");
        for (Map.Entry<String, List<LogicalVolume>> e : bySd.entrySet()) {
            final String v = "sd_" + cppId(e.getKey());
            b.append("  auto* ").append(v).append(" = new SensitiveDetector(\"").append(e.getKey()).append("\");\n");
            b.append("  sdManager->AddNewDetector(").append(v).append(");\n");
            for (LogicalVolume lv : e.getValue()) {
                b.append("  SetSensitiveDetector(\"").append(lv.name()).append("\", ").append(v).append(", true);\n");
            }
        }
        b.append("}\n");
    }

    // ------------------------------------------------------------------
    // Sensitive detector: energy per copy number, summed per event
    // ------------------------------------------------------------------

    private String sdHeader() {
        return """
            #ifndef SensitiveDetector_h
            #define SensitiveDetector_h 1

            #include "G4VSensitiveDetector.hh"

            #include <map>

            // Scores the energy deposited in each copy of the volumes it is attached to.
            // At the end of an event the totals go to the analysis manager (EventAction).
            class SensitiveDetector : public G4VSensitiveDetector
            {
              public:
                explicit SensitiveDetector(const G4String& name);
                ~SensitiveDetector() override = default;

                void Initialize(G4HCofThisEvent*) override;
                G4bool ProcessHits(G4Step* step, G4TouchableHistory*) override;

                G4double TotalEnergy() const;
                const std::map<G4int, G4double>& EnergyPerCopy() const { return fEdep; }

              private:
                std::map<G4int, G4double> fEdep;
            };

            #endif
            """;
    }

    private String sdSource() {
        return """
            #include "SensitiveDetector.hh"

            #include "G4Step.hh"
            #include "G4TouchableHistory.hh"

            SensitiveDetector::SensitiveDetector(const G4String& name) : G4VSensitiveDetector(name) {}

            void SensitiveDetector::Initialize(G4HCofThisEvent*) { fEdep.clear(); }

            G4bool SensitiveDetector::ProcessHits(G4Step* step, G4TouchableHistory*)
            {
              const G4double edep = step->GetTotalEnergyDeposit();
              if (edep <= 0.) return false;
              const G4int copy = step->GetPreStepPoint()->GetTouchable()->GetCopyNumber();
              fEdep[copy] += edep;
              return true;
            }

            G4double SensitiveDetector::TotalEnergy() const
            {
              G4double sum = 0.;
              for (const auto& [copy, e] : fEdep) sum += e;
              return sum;
            }
            """;
    }

    // ------------------------------------------------------------------
    // Actions: gun, run (analysis), event (filling)
    // ------------------------------------------------------------------

    private List<String> sdNames() {
        final Set<String> s = new LinkedHashSet<>();
        for (LogicalVolume lv : g.volumes()) {
            if (lv.sensitive() != null) {
                s.add(lv.sensitive());
            }
        }
        return new ArrayList<>(s);
    }

    private String actionsHeader() {
        return """
            #ifndef ActionInitialization_h
            #define ActionInitialization_h 1

            #include "G4VUserActionInitialization.hh"

            class ActionInitialization : public G4VUserActionInitialization
            {
              public:
                void BuildForMaster() const override;
                void Build() const override;
            };

            #endif
            """;
    }

    private String actionsSource() {
        final List<String> sds = sdNames();
        final String[] pos = o.gunPosition();
        final String[] dir = o.gunDirection() == null ? new String[] {"0", "0", "1"} : o.gunDirection();
        String px = "0.", py = "0.", pz;
        if (pos != null) {
            px = cpp(Inputs.normalize(pos[0], Units.Category.LENGTH, "mm"));
            py = cpp(Inputs.normalize(pos[1], Units.Category.LENGTH, "mm"));
            pz = cpp(Inputs.normalize(pos[2], Units.Category.LENGTH, "mm"));
        } else {
            // Just inside the world's upstream face
            final double hz = g.world().solid().extent().maxZ();
            pz = Units.exact(-0.99 * hz) + "*mm";
        }
        final StringBuilder sdList = new StringBuilder();
        for (int i = 0; i < sds.size(); i++) {
            sdList.append(i > 0 ? ", " : "").append('"').append(sds.get(i)).append('"');
        }
        return ("""
            // Generated by Sphere G4editor: gun, run and event actions in one place.

            #include "ActionInitialization.hh"
            #include "SensitiveDetector.hh"

            #include "G4AnalysisManager.hh"
            #include "G4Event.hh"
            #include "G4ParticleGun.hh"
            #include "G4ParticleTable.hh"
            #include "G4Run.hh"
            #include "G4SDManager.hh"
            #include "G4SystemOfUnits.hh"
            #include "G4UserEventAction.hh"
            #include "G4UserRunAction.hh"
            #include "G4VUserPrimaryGeneratorAction.hh"

            #include <string>
            #include <vector>

            using namespace CLHEP;

            namespace
            {
            const std::vector<std::string> kDetectors = {%s};

            class PrimaryGenerator : public G4VUserPrimaryGeneratorAction
            {
              public:
                PrimaryGenerator()
                {
                  fGun.SetParticleDefinition(G4ParticleTable::GetParticleTable()->FindParticle("%s"));
                  fGun.SetParticleEnergy(%s);
                  fGun.SetParticlePosition(G4ThreeVector(%s, %s, %s));
                  fGun.SetParticleMomentumDirection(G4ThreeVector(%s, %s, %s).unit());
                }
                void GeneratePrimaries(G4Event* event) override { fGun.GeneratePrimaryVertex(event); }

              private:
                G4ParticleGun fGun{1};
            };

            class RunAction : public G4UserRunAction
            {
              public:
                RunAction()
                {
                  auto* am = G4AnalysisManager::Instance();
                  am->SetDefaultFileType("root");
                  am->SetNtupleMerging(true);
                  am->SetFileName("%s");
                  for (const auto& d : kDetectors) am->CreateH1("Edep_" + d, "Energy in " + d + " [MeV]", 200, 0., %s);
                  am->CreateNtuple("Events", "Energy per event and detector [MeV]");
                  for (const auto& d : kDetectors) am->CreateNtupleDColumn(d);
                  am->FinishNtuple();
                }
                void BeginOfRunAction(const G4Run*) override { G4AnalysisManager::Instance()->OpenFile(); }
                void EndOfRunAction(const G4Run*) override
                {
                  auto* am = G4AnalysisManager::Instance();
                  am->Write();
                  am->CloseFile();
                }
            };

            class EventAction : public G4UserEventAction
            {
              public:
                void EndOfEventAction(const G4Event*) override
                {
                  auto* am = G4AnalysisManager::Instance();
                  auto* sdm = G4SDManager::GetSDMpointer();
                  for (std::size_t i = 0; i < kDetectors.size(); ++i) {
                    auto* sd = static_cast<SensitiveDetector*>(sdm->FindSensitiveDetector(kDetectors[i], false));
                    const G4double e = sd != nullptr ? sd->TotalEnergy() / MeV : 0.;
                    am->FillH1(static_cast<G4int>(i), e);
                    am->FillNtupleDColumn(static_cast<G4int>(i), e);
                  }
                  if (!kDetectors.empty()) am->AddNtupleRow();
                }
            };
            }  // namespace

            void ActionInitialization::BuildForMaster() const { SetUserAction(new RunAction); }

            void ActionInitialization::Build() const
            {
              SetUserAction(new PrimaryGenerator);
              SetUserAction(new RunAction);
              SetUserAction(new EventAction);
            }
            """).formatted(sdList, o.particle(), cpp(Inputs.normalize(o.energy(), Units.Category.ENERGY, "mm")),
            px, py, pz, cpp(dir[0]), cpp(dir[1]), cpp(dir[2]), o.appName(),
            cpp("1.2*(" + Inputs.normalize(o.energy(), Units.Category.ENERGY, "mm") + ")/MeV"));
    }

    // ------------------------------------------------------------------
    // main, CMake, macros
    // ------------------------------------------------------------------

    private String main(String app) {
        return """
            // %1$s: a Geant4 application generated by Sphere G4editor.
            //
            //   %1$s                    interactive, with visualisation (vis.mac)
            //   %1$s run.mac            batch
            //   %1$s -g design.gdml ... the same program on another GDML design

            #include "ActionInitialization.hh"
            #include "DetectorConstruction.hh"

            #include "G4PhysListFactory.hh"
            #include "G4RunManagerFactory.hh"
            #include "G4UIExecutive.hh"
            #include "G4UImanager.hh"
            #include "G4VModularPhysicsList.hh"
            #include "G4VisExecutive.hh"

            #include <string>

            int main(int argc, char** argv)
            {
              G4String gdml, macro;
              for (int i = 1; i < argc; ++i) {
                const std::string a = argv[i];
                if (a == "-g" && i + 1 < argc) gdml = argv[++i];
                else macro = a;
              }

              G4UIExecutive* ui = macro.empty() ? new G4UIExecutive(argc, argv) : nullptr;

              auto* runManager = G4RunManagerFactory::CreateRunManager(G4RunManagerType::Default);
              runManager->SetUserInitialization(new DetectorConstruction(gdml));

              G4PhysListFactory factory;
              G4VModularPhysicsList* physics = factory.GetReferencePhysList("%2$s");
              runManager->SetUserInitialization(physics);
              runManager->SetUserInitialization(new ActionInitialization);

              auto* vis = new G4VisExecutive;
              vis->Initialize();
              auto* uiManager = G4UImanager::GetUIpointer();
              if (ui == nullptr) {
                uiManager->ApplyCommand("/control/execute " + macro);
              } else {
                uiManager->ApplyCommand("/control/execute vis.mac");
                ui->SessionStart();
                delete ui;
              }
              delete vis;
              delete runManager;
              return 0;
            }
            """.formatted(app, o.physicsList());
    }

    private String cmake(String app) {
        return """
            # Generated by Sphere G4editor
            cmake_minimum_required(VERSION 3.16...3.31)
            project(%1$s)

            option(WITH_GEANT4_UIVIS "Build with Geant4 UI and visualization drivers" ON)
            if(WITH_GEANT4_UIVIS)
              find_package(Geant4 REQUIRED ui_all vis_all gdml)
            else()
              find_package(Geant4 REQUIRED gdml)
            endif()
            include(${Geant4_USE_FILE})

            file(GLOB sources ${PROJECT_SOURCE_DIR}/src/*.cc)
            file(GLOB headers ${PROJECT_SOURCE_DIR}/include/*.hh)

            add_executable(%1$s %1$s.cc ${sources} ${headers})
            target_include_directories(%1$s PRIVATE ${PROJECT_SOURCE_DIR}/include)
            target_compile_features(%1$s PRIVATE cxx_std_17)
            target_link_libraries(%1$s ${Geant4_LIBRARIES})

            foreach(f vis.mac run.mac geometry.gdml)
              configure_file(${PROJECT_SOURCE_DIR}/${f} ${PROJECT_BINARY_DIR}/${f} COPYONLY)
            endforeach()
            """.formatted(app);
    }

    private String visMacro() {
        return """
            # Generated by Sphere G4editor
            /run/initialize
            /vis/open
            /vis/viewer/set/autoRefresh false
            /vis/drawVolume
            /vis/viewer/set/style surface
            /vis/viewer/set/hiddenMarker true
            /vis/viewer/set/viewpointThetaPhi 60 30 deg
            /vis/scene/add/trajectories smooth
            /vis/modeling/trajectories/create/drawByCharge
            /vis/scene/endOfEventAction accumulate 50
            /vis/scene/add/axes
            /vis/viewer/set/autoRefresh true
            """;
    }

    private String runMacro() {
        return """
            # Generated by Sphere G4editor
            /run/initialize
            /tracking/verbose 0
            /run/printProgress %d
            /run/beamOn %d
            """.formatted(Math.max(1, o.events() / 10), o.events());
    }

    private String readme(String app) {
        final StringBuilder b = new StringBuilder();
        b.append("# ").append(app).append("\n\nA Geant4 application generated by Sphere G4editor from the design \"")
         .append(g.title()).append("\".\n\n## Build\n\n```bash\nmkdir build && cd build\ncmake -DGeant4_DIR=<geant4>/lib/cmake/Geant4 ..\n")
         .append("make -j\n```\n\n## Run\n\n```bash\n./").append(app).append("              # interactive, with visualization\n./")
         .append(app).append(" run.mac      # ").append(o.events()).append(" events in batch, writes ").append(app)
         .append(".root\n./").append(app).append(" -g geometry.gdml run.mac   # the GDML design instead of the C++ one\n```\n\n")
         .append("Physics list: ").append(o.physicsList()).append(". Gun: ").append(o.particle()).append(", ").append(o.energy())
         .append(".\n\nSensitive detectors: ").append(sdNames().isEmpty() ? "none" : String.join(", ", sdNames()))
         .append(". Each scores the energy deposited per event (ntuple Events, histograms Edep_*).\n");
        return b.toString();
    }
}

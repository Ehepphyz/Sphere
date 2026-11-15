package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The C++ of a learned fast simulation, written over the application {@link CppExport} generates: a
 * G4VFastSimulationModel on a region of the detector, the readout of every cell, and what the training and the
 * validation need from a run (energies drawn, showers written out, the time the event loop took).
 *
 * <p>What the network is told and gives back is in {@code SphereFastTable.hh} and {@code SphereFastNet.hh}, written
 * again each round; the rest is written once. Each change to a generated file is anchored on the exact text it
 * replaces, and refuses to proceed when that text is not there rather than build something else.
 */
public final class FastSimCode {

    private FastSimCode() {
    }

    /** A generated file changed: {@code anchor} must be in it, and is replaced. */
    private static void patch(Map<String, String> files, String file, String anchor, String replacement) {
        final String text = files.get(file);
        if (text == null || !text.contains(anchor)) {
            throw new IllegalStateException("the generated " + file + " no longer holds: " + anchor.strip());
        }
        files.put(file, text.replace(anchor, replacement));
    }

    /** The sensitive detector reading every cell by its place in the tree, not only by its own copy number. */
    static void readCells(Map<String, String> files) {
        patch(files, "include/SensitiveDetector.hh", "    const std::map<G4int, G4double>& EnergyPerCopy() const { return fEdep; }\n",
            "    const std::map<G4int, G4double>& EnergyPerCopy() const { return fEdep; }\n"
                + "    // the energy per cell: the copy numbers from the volume up to the world, three digits each\n"
                + "    const std::map<long long, G4double>& Cells() const { return fCells; }\n"
                + "    void AddFast(long long key, G4double energy);\n");
        patch(files, "include/SensitiveDetector.hh", "    std::map<G4int, G4double> fEdep;\n",
            "    std::map<G4int, G4double> fEdep;\n    std::map<long long, G4double> fCells;\n");
        patch(files, "src/SensitiveDetector.cc", "void SensitiveDetector::Initialize(G4HCofThisEvent*) { fEdep.clear(); }",
            "void SensitiveDetector::Initialize(G4HCofThisEvent*) { fEdep.clear(); fCells.clear(); }\n\n"
                + "void SensitiveDetector::AddFast(long long key, G4double energy)\n{\n"
                + "  fCells[key] += energy;\n  fEdep[static_cast<G4int>(key % 1000)] += energy;\n}");
        patch(files, "src/SensitiveDetector.cc", "  fEdep[copy] += edep;\n",
            "  fEdep[copy] += edep;\n"
                + "  const auto* touchable = step->GetPreStepPoint()->GetTouchable();\n"
                + "  long long key = 0, scale = 1;\n"
                + "  for (G4int d = 0; d < touchable->GetHistoryDepth() && d < 6; ++d) {\n"
                + "    key += scale * touchable->GetCopyNumber(d);\n"
                + "    scale *= 1000;\n"
                + "  }\n"
                + "  fCells[key] += edep;\n");
    }

    /** Every file of the application with the fast simulation in it, no network yet. */
    public static Map<String, String> application(Geometry g, CppExport.Options o, FastSimPlanner.Plan plan) {
        final Map<String, String> files = new LinkedHashMap<>(new CppExport(g, o).files());
        final String main = o.appName() + ".cc", detector = "src/DetectorConstruction.cc", actions = "src/ActionInitialization.cc";
        readCells(files);

        patch(files, detector, "#include \"SensitiveDetector.hh\"\n", "#include \"SensitiveDetector.hh\"\n#include \"SphereFastSim.hh\"\n");
        patch(files, detector, "  return pv_world;\n}\n", "  SphereFast::ConstructRegion();\n  return pv_world;\n}\n");
        patch(files, detector, "void DetectorConstruction::ConstructSDandField()\n{\n",
            "void DetectorConstruction::ConstructSDandField()\n{\n  SphereFast::AttachModel();\n");

        patch(files, main, "#include \"DetectorConstruction.hh\"\n", "#include \"DetectorConstruction.hh\"\n#include \"SphereFastSim.hh\"\n");
        patch(files, main, "  const G4String macro = argc > 1 ? argv[1] : \"\";\n",
            "  const G4String macro = argc > 1 ? argv[1] : \"\";\n  SphereFast::Load(argc > 2 ? argv[2] : \"\");\n");
        patch(files, main, "  runManager->SetUserInitialization(physics);\n",
            "  physics->RegisterPhysics(SphereFast::Physics());\n  runManager->SetUserInitialization(physics);\n");

        patch(files, actions, "#include \"SensitiveDetector.hh\"\n", "#include \"SensitiveDetector.hh\"\n#include \"SphereFastSim.hh\"\n");
        patch(files, actions, "void GeneratePrimaries(G4Event* event) override { fGun.GeneratePrimaryVertex(event); }",
            "void GeneratePrimaries(G4Event* event) override\n    {\n"
                + "      fGun.SetParticleEnergy(SphereFast::SampleEnergy(fGun.GetParticleEnergy()));\n"
                + "      fGun.GeneratePrimaryVertex(event);\n    }");
        patch(files, actions, "void BeginOfRunAction(const G4Run*) override { G4AnalysisManager::Instance()->OpenFile(); }",
            "void BeginOfRunAction(const G4Run*) override\n    {\n      if (IsMaster()) SphereFast::BeginRun();\n"
                + "      G4AnalysisManager::Instance()->OpenFile();\n    }");
        patch(files, actions, "      am->CloseFile();\n", "      am->CloseFile();\n      if (IsMaster()) SphereFast::EndRun();\n");
        patch(files, actions, "  SetUserAction(new EventAction);\n", "  SetUserAction(new EventAction);\n  SetUserAction(SphereFast::Stepping());\n");
        patch(files, actions, "    void EndOfEventAction(const G4Event*) override\n    {\n",
            "    void EndOfEventAction(const G4Event*) override\n    {\n      SphereFast::EndOfEvent();\n");

        files.put("include/SphereFastSim.hh", HEADER);
        files.put("src/SphereFastSim.cc", SOURCE);
        files.put("include/SphereFastNet.hh", STUB_NET);
        final int d = plan.cells().size();
        files.put("include/SphereFastTable.hh", table(plan, plan.cells(), null, ShowerBasis.identity(d, plan.emin(), plan.emax()),
            new double[d][3], new double[d][3], new double[0], new double[] {1, 0, 0}, TotalMap.identity(), false));
        return files;
    }

    private static String lit(double v) {
        return ShowerBasis.hex(v);
    }

    /**
     * What the C++ knows of this plan: the cells kept, the domain, the closed-form part of the shower, the network's
     * recalibrated latent space, the chance of crossing without a deposit, the calibration, whether fast is the default.
     */
    public static String table(FastSimPlanner.Plan p, List<FastSimPlanner.Cell> cells, String networkNote, ShowerBasis basis, double[][] latentShift,
                               double[][] latentLogScale, double[] punch, double[] calibration, TotalMap map, boolean validated) {
        final StringBuilder b = new StringBuilder();
        b.append("// SphereFastTable.hh -- written by Sphere: what the fast simulation of this detector knows.\n");
        if (networkNote != null) {
            b.append("// ").append(networkNote).append('\n');
        }
        b.append("#ifndef SphereFastTable_h\n#define SphereFastTable_h 1\n\n#include <cstddef>\n\nnamespace SphereFastTable\n{\n");
        b.append("struct Cell { int detector; long long key; };\n\n");
        final List<String> detectors = new ArrayList<>(p.detectors());
        b.append("static const char* const kDetectors[] = {");
        for (int i = 0; i < detectors.size(); i++) {
            b.append(i > 0 ? ", " : "").append('"').append(detectors.get(i)).append('"');
        }
        b.append("};\nconstexpr std::size_t kNumDetectors = ").append(detectors.size()).append(";\n");
        b.append("// the cells, along the detector: the network's coordinates follow this order\n");
        b.append("static const Cell kCells[] = {\n");
        for (FastSimPlanner.Cell c : cells) {
            b.append("  {").append(detectors.indexOf(c.detector())).append(", ").append(c.key()).append("LL},   // ").append(c.path().replace("*/", "")).append('\n');
        }
        b.append("};\nconstexpr std::size_t kNumCells = ").append(cells.size()).append(";\n");
        b.append("constexpr bool kHasNetwork = ").append(networkNote != null).append(";\n");
        // a fast simulation that was not validated is never what the application runs unless asked
        b.append("static const char* const kDefaultMode = \"").append(validated ? "fast" : "full").append("\";\n");
        b.append("static const char* const kParticle = \"").append(p.particle()).append("\";\n");
        b.append("static const char* const kEnvelope = \"").append(p.envelope()).append("\";\n");
        b.append(String.format(Locale.ROOT, "constexpr double kEmin = %s, kEmax = %s;   // MeV, at the envelope: %.6g to %.6g%n", lit(p.emin()), lit(p.emax()), p.emin(), p.emax()));
        final Vec3 a = p.axis(), e = p.entry() == null ? Vec3.ZERO : p.entry();
        b.append("constexpr double kAxis[3] = {").append(lit(a.x())).append(", ").append(lit(a.y())).append(", ").append(lit(a.z())).append("};\n");
        b.append("constexpr double kEntry[3] = {").append(lit(e.x())).append(", ").append(lit(e.y())).append(", ").append(lit(e.z())).append("};   // mm\n");
        b.append("constexpr double kAcceptCos = ").append(lit(p.acceptCos())).append(", kAcceptRadius = ").append(lit(p.acceptRadius())).append(";\n\n");
        b.append(basis.cpp()).append('\n');
        b.append("// the network's latent space, recalibrated on showers it was not trained on: z_j = shift_j(t) + exp(logScale_j(t)) N(0, 1)\n");
        ShowerBasis.matrix(b, "kLatentShift", latentShift, 3);
        ShowerBasis.matrix(b, "kLatentLogScale", latentLogScale, 3);
        b.append("\n// the chance of crossing without a deposit, on an even grid of t from -1 to 1\n");
        b.append("constexpr std::size_t kNumPunch = ").append(punch.length).append(";\nstatic const double kPunch[] = {");
        for (int i = 0; i < Math.max(1, punch.length); i++) {
            b.append(i > 0 ? ", " : "").append(lit(i < punch.length ? punch[i] : 0));
        }
        b.append("};\n");
        b.append(map.cpp());
        b.append("static const double kCalibration[3] = {").append(lit(calibration[0])).append(", ").append(lit(calibration[1])).append(", ").append(lit(calibration[2]))
            .append("};   // visible energy x (c0 + c1 t + c2 t^2)\n");
        b.append("}\n\n#endif\n");
        return b.toString();
    }

    private static final String STUB_NET = """
        // SphereFastNet.hh -- no network yet: Sphere writes it here once one is trained.
        #ifndef SphereFastNet_hh
        #define SphereFastNet_hh

        #include <cstddef>

        class SphereFastNet {
        public:
            static constexpr std::size_t kInput0 = 1, kInput1 = 1, kOutput0 = 1;
            static void Evaluate(const double*, const double*, double* out) { out[0] = 0.; }
        };

        #endif
        """;

    private static final String HEADER = """
        // SphereFastSim.hh -- written by Sphere: a learned fast simulation of the detector, and what a run tells Sphere.
        #ifndef SphereFastSim_h
        #define SphereFastSim_h 1

        #include "G4Types.hh"
        #include "G4VPhysicsConstructor.hh"

        #include <string>

        class G4UserSteppingAction;

        namespace SphereFast
        {
        void Load(const std::string& controlFile);   // what this run is: full or fast, its energies, where the showers go
        G4VPhysicsConstructor* Physics();
        void ConstructRegion();                      // in Construct(): the region of the replaced volume
        void AttachModel();                          // in ConstructSDandField(): one model per thread
        G4double SampleEnergy(G4double gunEnergy);
        void EndOfEvent();
        void BeginRun();
        void EndRun();
        G4UserSteppingAction* Stepping();            // what else enters the replaced volume: a shower learned from is the primary alone
        }

        #endif
        """;

    private static final String SOURCE = """
        // SphereFastSim.cc -- written by Sphere. A primary entering the replaced volume inside the domain the network was
        // trained on becomes a shower drawn from it, cell by cell; every other particle stays with Geant4's physics.
        #include "SphereFastSim.hh"
        #include "SensitiveDetector.hh"
        #include "SphereFastNet.hh"
        #include "SphereFastTable.hh"

        #include "G4FastSimulationPhysics.hh"
        #include "G4FastStep.hh"
        #include "G4FastTrack.hh"
        #include "G4LogicalVolume.hh"
        #include "G4LogicalVolumeStore.hh"
        #include "G4PhysicalConstants.hh"
        #include "G4ProductionCuts.hh"
        #include "G4ProductionCutsTable.hh"
        #include "G4Region.hh"
        #include "G4RegionStore.hh"
        #include "G4SDManager.hh"
        #include "G4Step.hh"
        #include "G4SystemOfUnits.hh"
        #include "G4Threading.hh"
        #include "G4Timer.hh"
        #include "G4Track.hh"
        #include "G4UserSteppingAction.hh"
        #include "G4VFastSimulationModel.hh"
        #include "G4VPhysicalVolume.hh"
        #include "G4VSolid.hh"
        #include "G4ios.hh"
        #include "Randomize.hh"

        #include <algorithm>
        #include <array>
        #include <atomic>
        #include <chrono>
        #include <cmath>
        #include <cstdio>
        #include <fstream>
        #include <set>
        #include <sstream>
        #include <string>
        #include <vector>

        namespace
        {
        struct Range
        {
          double low, high, weight;
        };

        // written once by Load, before any thread starts; only read afterwards
        struct Control
        {
          std::string mode = SphereFastTable::kDefaultMode;
          std::string out;
          std::vector<double> energies, weights;
          std::vector<Range> ranges;
          long seed = 0;
        };
        Control gControl;
        std::atomic<long> gEvents{0}, gFired{0}, gEntered{0}, gInDomain{0};
        std::atomic<long long> gFirst{0}, gLast{0};
        G4Timer gTimer;

        // the event being simulated, on its own thread
        thread_local double tlGun = 0., tlEntry = 0., tlOther = 0.;
        thread_local bool tlEntered = false, tlInDomain = false, tlAccepted = false;
        G4Region* gRegion = nullptr;   // set by ConstructRegion, before any thread starts

        long long Now()
        {
          return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
        }

        struct Dump
        {
          std::FILE* file = nullptr;
          ~Dump() { if (file != nullptr) std::fclose(file); }
        };
        thread_local Dump tlDump;

        std::vector<SensitiveDetector*>& Detectors()
        {
          thread_local std::vector<SensitiveDetector*> sds;
          if (sds.empty()) {
            auto* manager = G4SDManager::GetSDMpointer();
            for (std::size_t i = 0; i < SphereFastTable::kNumDetectors; ++i) {
              sds.push_back(static_cast<SensitiveDetector*>(manager->FindSensitiveDetector(SphereFastTable::kDetectors[i], false)));
            }
          }
          return sds;
        }

        double Poly(const double* c, int n, double t)
        {
          double v = 0.;
          for (int i = n - 1; i >= 0; --i) v = v * t + c[i];
          return v;
        }

        double Scaled(double energy)
        {
          const double t = (std::log(energy / MeV) - SphereFastTable::kUMid) / SphereFastTable::kUHalf;
          return std::max(-1., std::min(1., t));
        }

        double PunchThrough(double t)
        {
          const std::size_t n = SphereFastTable::kNumPunch;
          if (n == 0) return 0.;
          if (n == 1) return SphereFastTable::kPunch[0];
          const double x = (t + 1.) * 0.5 * static_cast<double>(n - 1);
          const std::size_t i = std::min<std::size_t>(n - 2, static_cast<std::size_t>(std::max(0., x)));
          const double f = std::min(1., std::max(0., x - static_cast<double>(i)));
          return SphereFastTable::kPunch[i] * (1. - f) + SphereFastTable::kPunch[i + 1] * f;
        }

        std::vector<double> Numbers(const std::string& text)
        {
          std::vector<double> out;
          std::stringstream s(text);
          std::string item;
          while (std::getline(s, item, ',')) {
            if (!item.empty()) out.push_back(std::stod(item));
          }
          return out;
        }

        // ln(visible / E) carried to the distribution of Geant4, as TotalMap.apply does
        double MapTotal(double f0, double t)
        {
          constexpr std::size_t L = SphereFastTable::kMapLevels, K = SphereFastTable::kMapEnergies;
          if (K == 0) return f0;
          std::size_t e = 0;
          while (e + 1 < K && SphereFastTable::kMapT[e + 1] < t) ++e;
          const std::size_t next = std::min<std::size_t>(e + 1, K - 1);
          const double frac = K < 2 ? 0. : std::max(0., std::min(1., (t - SphereFastTable::kMapT[e]) / std::max(1e-12, SphereFastTable::kMapT[next] - SphereFastTable::kMapT[e])));
          std::array<double, L> x{}, y{};
          for (std::size_t l = 0; l < L; ++l) {
            x[l] = SphereFastTable::kMapNetwork[e][l] * (1. - frac) + SphereFastTable::kMapNetwork[next][l] * frac;
            y[l] = x[l] + Poly(SphereFastTable::kMapShift[l], 3, t);
            if (l > 0) {
              x[l] = std::max(x[l], x[l - 1] + 1e-12);
              y[l] = std::max(y[l], y[l - 1]);
            }
          }
          if (f0 <= x[0]) return y[0] + (f0 - x[0]) * (y[1] - y[0]) / (x[1] - x[0]);
          if (f0 >= x[L - 1]) return y[L - 1] + (f0 - x[L - 1]) * (y[L - 1] - y[L - 2]) / (x[L - 1] - x[L - 2]);
          std::size_t lo = 0, hi = L - 1;
          while (hi - lo > 1) {
            const std::size_t mid = (lo + hi) / 2;
            if (x[mid] <= f0) lo = mid;
            else hi = mid;
          }
          return y[lo] + (f0 - x[lo]) * (y[hi] - y[lo]) / (x[hi] - x[lo]);
        }

        // What else than the primary enters the replaced volume, and with how much energy
        class Stepping : public G4UserSteppingAction
        {
          public:
            void UserSteppingAction(const G4Step* step) override
            {
              const G4StepPoint* post = step->GetPostStepPoint();
              if (post->GetStepStatus() != fGeomBoundary || step->GetTrack()->GetParentID() == 0) return;
              const G4VPhysicalVolume* into = post->GetPhysicalVolume();
              const G4VPhysicalVolume* from = step->GetPreStepPoint()->GetPhysicalVolume();
              if (into == nullptr || from == nullptr) return;
              if (into->GetLogicalVolume()->GetRegion() == gRegion && from->GetLogicalVolume()->GetRegion() != gRegion) tlOther += post->GetKineticEnergy();
            }
        };

        G4bool Contains(const G4LogicalVolume* mother, const G4LogicalVolume* volume, std::set<const G4LogicalVolume*>& seen)
        {
          if (!seen.insert(mother).second) return false;
          for (std::size_t i = 0; i < mother->GetNoDaughters(); ++i) {
            const G4LogicalVolume* daughter = mother->GetDaughter(i)->GetLogicalVolume();
            if (daughter == volume || Contains(daughter, volume, seen)) return true;
          }
          return false;
        }

        class Model : public G4VFastSimulationModel
        {
          public:
            explicit Model(G4Region* region) : G4VFastSimulationModel("SphereFastModel", region) {}

            G4bool IsApplicable(const G4ParticleDefinition& particle) override
            {
              return particle.GetParticleName() == SphereFastTable::kParticle;
            }

            // The primary entering the volume: recorded in every mode, in the terms the network learned (its kinetic
            // energy there, its direction, where it enters), and replaced in fast mode when inside the trained domain.
            G4bool ModelTrigger(const G4FastTrack& fast) override
            {
              const G4Track* track = fast.GetPrimaryTrack();
              if (track->GetParentID() != 0 || tlEntered) return false;
              const G4ThreeVector local = fast.GetPrimaryTrackLocalPosition();
              const G4VSolid* solid = fast.GetEnvelopeSolid();
              if (solid->Inside(local) != kSurface) return false;
              if (solid->SurfaceNormal(local).dot(fast.GetPrimaryTrackLocalDirection()) >= 0.) return false;
              tlEntered = true;
              tlEntry = track->GetKineticEnergy();
              ++gEntered;
              const G4ThreeVector axis(SphereFastTable::kAxis[0], SphereFastTable::kAxis[1], SphereFastTable::kAxis[2]);
              const G4ThreeVector entry(SphereFastTable::kEntry[0], SphereFastTable::kEntry[1], SphereFastTable::kEntry[2]);
              const G4ThreeVector offset = track->GetPosition() - entry;
              tlAccepted = track->GetMomentumDirection().dot(axis) >= SphereFastTable::kAcceptCos
                           && (offset - offset.dot(axis) * axis).mag() <= SphereFastTable::kAcceptRadius;
              tlInDomain = tlAccepted && tlEntry >= SphereFastTable::kEmin && tlEntry <= SphereFastTable::kEmax;
              if (tlInDomain) ++gInDomain;
              return tlInDomain && SphereFastTable::kHasNetwork && gControl.mode == "fast";
            }

            void DoIt(const G4FastTrack& fast, G4FastStep& step) override
            {
              const G4Track* track = fast.GetPrimaryTrack();
              const G4double energy = track->GetKineticEnergy();
              const double u = std::log(energy / MeV), t = Scaled(energy);
              ++gFired;
              if (SphereFastTable::kNumPunch > 0 && G4UniformRand() < PunchThrough(t)) {
                // through without a deposit: it leaves by the far side, as it came
                const G4ThreeVector local = fast.GetPrimaryTrackLocalPosition(), direction = fast.GetPrimaryTrackLocalDirection();
                const G4double length = fast.GetEnvelopeSolid()->DistanceToOut(local, direction);
                step.ProposePrimaryTrackFinalPosition(local + length * direction);   // local coordinates
                step.ProposePrimaryTrackFinalTime(track->GetGlobalTime() + length / track->GetVelocity());
                step.ProposePrimaryTrackPathLength(length);
                return;
              }
              constexpr std::size_t D = SphereFastTable::kNumCells;
              // the latent point: the network's base distribution, recalibrated on showers it never saw
              std::array<double, SphereFastNet::kInput0> z{};
              for (std::size_t j = 0; j < z.size(); ++j) {
                const double shift = j < D ? Poly(SphereFastTable::kLatentShift[j], 3, t) : 0.;
                const double scale = j < D ? std::exp(Poly(SphereFastTable::kLatentLogScale[j], 3, t)) : 1.;
                z[j] = shift + scale * G4RandGauss::shoot(0., 1.);
              }
              const std::array<double, 1> condition = {u};
              std::array<double, SphereFastNet::kOutput0> w{};
              SphereFastNet::Evaluate(z.data(), condition.data(), w.data());
              // the closed-form part added back: the mean and the width the energy gives each coordinate
              std::array<double, D> f{};
              for (std::size_t j = 0; j < D && j < w.size(); ++j) {
                f[j] = Poly(SphereFastTable::kMean[j], 4, t) + std::exp(0.5 * Poly(SphereFastTable::kLogVariance[j], 3, t)) * w[j];
              }
              double visible = energy * std::exp(MapTotal(f[0], t)) * Poly(SphereFastTable::kCalibration, 3, t);
              const double most = energy + (std::string(SphereFastTable::kParticle) == "e+" ? 2. * electron_mass_c2 : 0.);
              if (!(visible >= 0.)) visible = 0.;
              visible = std::min(visible, most);   // no more than it brought
              // the shares: stick breaking along the cells, then the floor the training added taken off
              std::array<double, D> share{};
              double remaining = 1.;
              for (std::size_t i = 1; i < D; ++i) {
                const double r = 1. / (1. + std::exp(-f[i]));
                share[i - 1] = remaining * r;
                remaining *= 1. - r;
              }
              share[D - 1] = remaining;
              const double grow = 1. + SphereFastTable::kDelta * 0.5 * static_cast<double>(D);
              double sum = 0.;
              for (auto& s : share) {
                const double v = s * grow;
                s = v > SphereFastTable::kDelta ? v - 0.5 * SphereFastTable::kDelta : 0.;
                sum += s;
              }
              auto& detectors = Detectors();
              if (sum > 0. && visible > 0.) {
                for (std::size_t i = 0; i < D; ++i) {
                  const auto& cell = SphereFastTable::kCells[i];
                  if (share[i] > 0. && detectors[cell.detector] != nullptr) detectors[cell.detector]->AddFast(cell.key, visible * share[i] / sum);
                }
              }
              step.KillPrimaryTrack();
              step.ProposePrimaryTrackPathLength(0.);
              step.ProposeTotalEnergyDeposited(visible);
            }
        };
        }  // namespace

        void SphereFast::Load(const std::string& controlFile)
        {
          if (controlFile.empty()) return;
          std::ifstream in(controlFile);
          if (!in) {
            G4cerr << "SPHEREFS cannot read " << controlFile << G4endl;
            return;
          }
          std::string line;
          while (std::getline(in, line)) {
            if (!line.empty() && line.back() == '\\r') line.pop_back();
            const auto space = line.find(' ');
            const std::string key = line.substr(0, space), value = space == std::string::npos ? "" : line.substr(space + 1);
            if (key == "mode") gControl.mode = value;
            else if (key == "out") gControl.out = value;   // the rest of the line: a path may hold spaces
            else if (key == "seed") gControl.seed = std::stol(value);
            else if (key == "energies") gControl.energies = Numbers(value);
            else if (key == "weights") gControl.weights = Numbers(value);
            else if (key == "ranges") {   // low:high:weight,... energies log-uniform in each, ranges drawn by weight
              std::stringstream s(value);
              std::string item;
              while (std::getline(s, item, ',')) {
                Range r{};
                if (std::sscanf(item.c_str(), "%lf:%lf:%lf", &r.low, &r.high, &r.weight) == 3 && r.low > 0. && r.high >= r.low) gControl.ranges.push_back(r);
              }
            }
          }
          if (gControl.seed != 0) G4Random::setTheSeed(gControl.seed);
        }

        G4VPhysicsConstructor* SphereFast::Physics()
        {
          auto* physics = new G4FastSimulationPhysics();
          physics->ActivateFastSimulation(SphereFastTable::kParticle);
          return physics;
        }

        void SphereFast::ConstructRegion()
        {
          auto* volume = G4LogicalVolumeStore::GetInstance()->GetVolume(SphereFastTable::kEnvelope);
          auto* region = new G4Region("SphereFastRegion");
          // A volume inside the envelope that is the root of a region of its own (CppExport gives thin sensitive layers
          // one) would keep the shower out of the model's reach: that region gives it up, and the envelope keeps its cuts.
          std::vector<G4ProductionCuts*> finer;
          for (auto* other : *G4RegionStore::GetInstance()) {
            if (other == region) continue;
            std::vector<G4LogicalVolume*> inside;
            auto root = other->GetRootLogicalVolumeIterator();
            for (std::size_t i = 0; i < other->GetNumberOfRootVolumes(); ++i, ++root) {
              std::set<const G4LogicalVolume*> seen;
              if (*root != volume && Contains(volume, *root, seen)) inside.push_back(*root);
            }
            for (auto* lv : inside) {
              other->RemoveRootLogicalVolume(lv);
              lv->SetRegionRootFlag(false);
              if (other->GetProductionCuts() != nullptr) finer.push_back(other->GetProductionCuts());
            }
          }
          volume->SetRegion(region);
          region->AddRootLogicalVolume(volume);
          gRegion = region;
          if (!finer.empty()) {
            auto* cuts = new G4ProductionCuts(*G4ProductionCutsTable::GetProductionCutsTable()->GetDefaultProductionCuts());
            for (const auto* c : finer) {
              for (G4int i = 0; i < 4; ++i) cuts->SetProductionCut(std::min(cuts->GetProductionCut(i), c->GetProductionCut(i)), i);
            }
            region->SetProductionCuts(cuts);
          }
        }

        G4UserSteppingAction* SphereFast::Stepping()
        {
          return new ::Stepping();
        }

        void SphereFast::AttachModel()
        {
          new Model(G4RegionStore::GetInstance()->GetRegion("SphereFastRegion"));
        }

        G4double SphereFast::SampleEnergy(G4double gunEnergy)
        {
          long long none = 0;
          gFirst.compare_exchange_strong(none, Now());
          tlEntered = false;
          tlInDomain = false;
          tlAccepted = false;
          tlEntry = 0.;
          tlOther = 0.;
          G4double energy = gunEnergy;
          if (!gControl.energies.empty()) {
            const bool weighted = gControl.weights.size() == gControl.energies.size();
            double total = 0.;
            for (std::size_t i = 0; i < gControl.energies.size(); ++i) total += weighted ? gControl.weights[i] : 1.;
            double x = G4UniformRand() * total;
            std::size_t i = 0;
            for (; i + 1 < gControl.energies.size(); ++i) {
              const double w = weighted ? gControl.weights[i] : 1.;
              if (x < w) break;
              x -= w;
            }
            energy = gControl.energies[i];
          } else if (!gControl.ranges.empty()) {
            double total = 0.;
            for (const auto& r : gControl.ranges) total += r.weight;
            double x = G4UniformRand() * total;
            std::size_t i = 0;
            for (; i + 1 < gControl.ranges.size(); ++i) {
              if (x < gControl.ranges[i].weight) break;
              x -= gControl.ranges[i].weight;
            }
            const auto& r = gControl.ranges[i];
            energy = r.low * std::exp(G4UniformRand() * std::log(r.high / r.low));
          }
          tlGun = energy;
          return energy;
        }

        void SphereFast::EndOfEvent()
        {
          ++gEvents;
          const long long now = Now();
          long long last = gLast.load();
          while (now > last && !gLast.compare_exchange_weak(last, now)) {
          }
          if (gControl.out.empty()) return;
          if (tlDump.file == nullptr) {
            const std::string name = gControl.out + std::to_string(G4Threading::G4GetThreadId() + 1000) + ".txt";
            tlDump.file = std::fopen(name.c_str(), "w");
            if (tlDump.file == nullptr) {
              G4cerr << "SPHEREFS cannot write " << name << G4endl;
              return;
            }
          }
          // the gun energy, the primary energy at the envelope (0: it never entered), 1 if accepted + 2 if in the energies,
          // the energy other particles brought in, the cells
          std::fprintf(tlDump.file, "%.10g %.10g %d %.8g", tlGun, tlEntered ? tlEntry : 0., (tlAccepted ? 1 : 0) + (tlInDomain ? 2 : 0), tlOther);
          auto& detectors = Detectors();
          for (std::size_t d = 0; d < detectors.size(); ++d) {
            if (detectors[d] == nullptr) continue;
            for (const auto& [key, value] : detectors[d]->Cells()) {
              if (value > 0.) std::fprintf(tlDump.file, " %zu:%lld:%.8g", d, key, value);
            }
          }
          std::fputc('\\n', tlDump.file);
          std::fflush(tlDump.file);
        }

        void SphereFast::BeginRun()
        {
          gEvents = 0;
          gFired = 0;
          gEntered = 0;
          gInDomain = 0;
          gFirst = 0;
          gLast = 0;
          gTimer.Start();
        }

        void SphereFast::EndRun()
        {
          gTimer.Stop();
          const long long first = gFirst.load(), last = gLast.load();
          const double loop = first > 0 && last > first ? 1e-9 * static_cast<double>(last - first) : 0.;
          G4cout << "SPHEREFS mode=" << gControl.mode << " events=" << gEvents.load() << " fired=" << gFired.load() << " entered=" << gEntered.load()
                 << " domain=" << gInDomain.load() << " loop=" << loop << " seconds=" << gTimer.GetRealElapsed() << G4endl;
        }
        """;
}

// g4b_geant4.cpp
//
// The Geant4 side of geant4-bridge: reading a GDML design, Geant4's own
// overlap check, NIST materials, navigation, geantino scans of the material
// budget, and runs with a particle gun that report energy deposits per volume
// and the trajectories of the first events.
//
// Everything runs on the engine's main thread, with a serial run manager:
// Geant4's kernel is driven by one thread here, the way an application's
// main() drives it.

#include "g4b_service.h"

#include "G4Event.hh"
#include "G4EnvironmentUtils.hh"
#include "G4Exception.hh"
#include "G4GDMLParser.hh"
#include "G4GeometryManager.hh"
#include "G4LogicalVolume.hh"
#include "G4LogicalVolumeStore.hh"
#include "G4Material.hh"
#include "G4Navigator.hh"
#include "G4NistManager.hh"
#include "G4ParticleGun.hh"
#include "G4ParticleTable.hh"
#include "G4PhysListFactory.hh"
#include "G4PhysicalVolumeStore.hh"
#include "G4Run.hh"
#include "G4RunManager.hh"
#include "G4RunManagerFactory.hh"
#include "G4SolidStore.hh"
#include "G4StateManager.hh"
#include "G4Step.hh"
#include "G4SystemOfUnits.hh"
#include "G4TouchableHistory.hh"
#include "G4Track.hh"
#include "G4UImanager.hh"
#include "G4UIsession.hh"
#include "G4UserEventAction.hh"
#include "G4UserSteppingAction.hh"
#include "G4VExceptionHandler.hh"
#include "G4VModularPhysicsList.hh"
#include "G4VPhysicalVolume.hh"
#include "G4VUserDetectorConstruction.hh"
#include "G4VUserPrimaryGeneratorAction.hh"
#include "G4Version.hh"
#include "G4ios.hh"
#include "Randomize.hh"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <unordered_map>

namespace g4b {

namespace {

// ---------------------------------------------------------------------------
// Output: Geant4 prints a lot, and on the stdio transport stdout is the
// protocol. Everything Geant4 says goes to stderr, and into a buffer while a
// command wants to report it.

class Session : public G4UIsession {
public:
  G4int ReceiveG4cout(const G4String &s) override {
    if (capturing) {
      text += s;
    }
    std::cerr << s << std::flush;
    return 0;
  }
  G4int ReceiveG4cerr(const G4String &s) override {
    if (capturing) {
      text += "[cerr] ";
      text += s;
    }
    std::cerr << s << std::flush;
    return 0;
  }
  bool capturing = false;
  std::string text;
};

Session &session() {
  static Session s;
  return s;
}

/** Collects what Geant4 prints for the duration of a scope. */
struct Capture {
  Capture() {
    session().text.clear();
    session().capturing = true;
  }
  ~Capture() { session().capturing = false; }
  std::string take() {
    std::string t;
    t.swap(session().text);
    return t;
  }
};

// ---------------------------------------------------------------------------
// Exceptions: a fatal G4Exception would abort the process; here it becomes a
// failed command, and the engine lives on.

class Handler : public G4VExceptionHandler {
public:
  G4bool Notify(const char *origin, const char *code, G4ExceptionSeverity severity,
                const char *description) override {
    std::string m = std::string(code) + " in " + origin + ": " + description;
    if (severity == FatalException || severity == FatalErrorInArgument) {
      throw Failure("Geant4 fatal exception " + m);
    }
    if (severity == RunMustBeAborted || severity == EventMustBeAborted) {
      warnings.push_back(m);
      return false;
    }
    warnings.push_back(m);
    return false;
  }
  std::vector<std::string> warnings;
};

Handler *g_handler = nullptr;

// ---------------------------------------------------------------------------
// State

G4GDMLParser g_parser;
G4VPhysicalVolume *g_world = nullptr;
std::string g_gdmlPath;
G4RunManager *g_runManager = nullptr;
std::string g_physics;

/** The detector a run sees: the world already read from GDML. */
class Detector : public G4VUserDetectorConstruction {
public:
  G4VPhysicalVolume *Construct() override { return g_world; }
};

// ---------------------------------------------------------------------------
// Run configuration and scoring

struct Track {
  int id = 0, parent = 0, pdg = 0;
  double charge = 0, ekin = 0;
  std::vector<float> points;
};

struct RunData {
  // configuration
  std::string particle = "e-";
  double energy = 1 * GeV;
  G4ThreeVector position{0, 0, 0};
  G4ThreeVector direction{0, 0, 1};
  double spread = 0;   // cone half-angle
  double spot = 0;     // beam spot radius
  int trackEvents = 10;
  std::size_t maxPoints = 200000;
  double minTrackEnergy = 0;
  // results
  std::unordered_map<const G4LogicalVolume *, double> eventEdep, sum, sum2;
  std::unordered_map<const G4LogicalVolume *, int> hits;
  std::vector<Track> tracks;
  std::unordered_map<int, std::size_t> trackIndex;
  std::size_t points = 0;
  bool truncated = false;
  int eventsDone = 0, eventsWanted = 0;
  Context *ctx = nullptr;
};

RunData g_run;

class Gun : public G4VUserPrimaryGeneratorAction {
public:
  void GeneratePrimaries(G4Event *event) override {
    G4ParticleDefinition *def = G4ParticleTable::GetParticleTable()->FindParticle(g_run.particle);
    if (def == nullptr) {
      G4Exception("geant4-bridge", "Gun001", FatalErrorInArgument, ("unknown particle " + g_run.particle).c_str());
      return;
    }
    gun_.SetParticleDefinition(def);
    gun_.SetParticleEnergy(g_run.energy);
    G4ThreeVector dir = g_run.direction.unit();
    G4ThreeVector pos = g_run.position;
    const G4ThreeVector u = dir.orthogonal().unit(), v = dir.cross(u).unit();
    if (g_run.spot > 0) {
      const double r = g_run.spot * std::sqrt(G4UniformRand()), a = CLHEP::twopi * G4UniformRand();
      pos += r * (std::cos(a) * u + std::sin(a) * v);
    }
    if (g_run.spread > 0) {
      const double ct = 1 - G4UniformRand() * (1 - std::cos(g_run.spread)), st = std::sqrt(1 - ct * ct);
      const double a = CLHEP::twopi * G4UniformRand();
      dir = (ct * dir + st * (std::cos(a) * u + std::sin(a) * v)).unit();
    }
    gun_.SetParticlePosition(pos);
    gun_.SetParticleMomentumDirection(dir);
    gun_.GeneratePrimaryVertex(event);
  }

private:
  G4ParticleGun gun_{1};
};

class Events : public G4UserEventAction {
public:
  void BeginOfEventAction(const G4Event *) override {
    g_run.eventEdep.clear();
    if (g_run.ctx != nullptr && g_run.ctx->cancelled()) {
      G4RunManager::GetRunManager()->AbortRun(true);
    }
  }
  void EndOfEventAction(const G4Event *) override {
    for (const auto &[lv, e] : g_run.eventEdep) {
      g_run.sum[lv] += e;
      g_run.sum2[lv] += e * e;
      g_run.hits[lv] += 1;
    }
    ++g_run.eventsDone;
    const int every = std::max(1, g_run.eventsWanted / 50);
    if (g_run.ctx != nullptr && g_run.eventsDone % every == 0) {
      g_run.ctx->progress(static_cast<double>(g_run.eventsDone) / std::max(1, g_run.eventsWanted),
                          "event " + std::to_string(g_run.eventsDone) + " / " + std::to_string(g_run.eventsWanted));
    }
  }
};

class Steps : public G4UserSteppingAction {
public:
  void UserSteppingAction(const G4Step *step) override {
    const G4StepPoint *pre = step->GetPreStepPoint();
    const G4VPhysicalVolume *pv = pre->GetPhysicalVolume();
    const double edep = step->GetTotalEnergyDeposit();
    if (pv != nullptr && edep > 0) {
      g_run.eventEdep[pv->GetLogicalVolume()] += edep;
    }
    if (g_run.eventsDone >= g_run.trackEvents || g_run.truncated) {
      return;
    }
    const G4Track *t = step->GetTrack();
    auto it = g_run.trackIndex.find(t->GetTrackID());
    if (it == g_run.trackIndex.end()) {
      if (t->GetVertexKineticEnergy() < g_run.minTrackEnergy) {
        return;
      }
      Track tr;
      tr.id = t->GetTrackID();
      tr.parent = t->GetParentID();
      tr.pdg = t->GetDefinition()->GetPDGEncoding();
      tr.charge = t->GetDefinition()->GetPDGCharge();
      tr.ekin = t->GetVertexKineticEnergy();
      const G4ThreeVector p = pre->GetPosition();
      tr.points = {static_cast<float>(p.x()), static_cast<float>(p.y()), static_cast<float>(p.z())};
      g_run.trackIndex[tr.id] = g_run.tracks.size();
      g_run.tracks.push_back(std::move(tr));
      it = g_run.trackIndex.find(t->GetTrackID());
      g_run.points++;
    }
    const G4ThreeVector q = step->GetPostStepPoint()->GetPosition();
    auto &pts = g_run.tracks[it->second].points;
    pts.push_back(static_cast<float>(q.x()));
    pts.push_back(static_cast<float>(q.y()));
    pts.push_back(static_cast<float>(q.z()));
    if (++g_run.points >= g_run.maxPoints) {
      g_run.truncated = true;
    }
  }
};

// Track IDs restart every event: the index must too
class EventsWithTracks : public Events {
public:
  void BeginOfEventAction(const G4Event *e) override {
    g_run.trackIndex.clear();
    Events::BeginOfEventAction(e);
  }
};

// ---------------------------------------------------------------------------
// Helpers

G4ThreeVector vec(const Json &a, G4ThreeVector fallback) {
  if (!a.isArray() || a.size() != 3) {
    return fallback;
  }
  return {a.at(0).num(), a.at(1).num(), a.at(2).num()};
}

void requireWorld() {
  if (g_world == nullptr) {
    throw Failure("no geometry: send 'load' with a GDML file first");
  }
}

const char *kDatasets[] = {"G4NEUTRONHPDATA", "G4LEDATA",      "G4LEVELGAMMADATA", "G4RADIOACTIVEDATA",
                           "G4PARTICLEXSDATA", "G4PIIDATA",     "G4REALSURFACEDATA", "G4SAIDXSDATA",
                           "G4ABLADATA",      "G4INCLDATA",    "G4ENSDFSTATEDATA", "G4CHANNELINGDATA"};

Json datasets(std::vector<std::string> &missing) {
  Json d = Json::object();
  for (const char *name : kDatasets) {
    const char *dir = G4FindDataDir(name);
    if (dir != nullptr) {
      d.set(name, std::string(dir));
    } else {
      d.set(name, Json());
      missing.emplace_back(name);
    }
  }
  return d;
}

Json materialJson(const G4Material *m) {
  Json j = Json::object();
  j.set("name", m->GetName());
  j.set("density_g_cm3", m->GetDensity() / (g / cm3));
  j.set("radiationLength_mm", m->GetRadlen() / mm);
  j.set("nuclearInteractionLength_mm", m->GetNuclearInterLength() / mm);
  j.set("meanExcitation_eV", m->GetIonisation()->GetMeanExcitationEnergy() / eV);
  j.set("electronDensity_per_mm3", m->GetElectronDensity() * mm3);
  j.set("temperature_K", m->GetTemperature() / kelvin);
  j.set("pressure_atm", m->GetPressure() / atmosphere);
  j.set("state", m->GetState() == kStateGas ? "gas" : m->GetState() == kStateLiquid ? "liquid" : "solid");
  Json els = Json::array();
  const G4double *f = m->GetFractionVector();
  for (std::size_t i = 0; i < m->GetNumberOfElements(); ++i) {
    const G4Element *e = m->GetElement(static_cast<G4int>(i));
    Json el = Json::object();
    el.set("symbol", e->GetSymbol());
    el.set("Z", e->GetZ());
    el.set("A_g_mole", e->GetA() / (g / mole));
    el.set("massFraction", f[i]);
    els.push(el);
  }
  j.set("elements", els);
  return j;
}

// ---------------------------------------------------------------------------
// Commands

Json cmdVersion(const Json &, Context &) {
  Json j = Json::object();
  j.set("engine", "geant4-bridge");
  j.set("geant4", G4Version);
  j.set("date", G4Date);
  j.set("versionNumber", G4VERSION_NUMBER);
  std::vector<std::string> missing;
  j.set("datasets", datasets(missing));
  Json m = Json::array();
  for (const auto &s : missing) {
    m.push(s);
  }
  j.set("missingDatasets", m);
  G4PhysListFactory f;
  Json lists = Json::array();
  for (const auto &l : f.AvailablePhysLists()) {
    lists.push(std::string(l));
  }
  j.set("physicsLists", lists);
  Json em = Json::array();
  for (const auto &l : f.AvailablePhysListsEM()) {
    em.push(std::string(l));
  }
  j.set("emOptions", em);
  j.set("geometry", g_world == nullptr ? Json() : Json(g_gdmlPath));
  j.set("physics", g_physics.empty() ? Json() : Json(g_physics));
  return j;
}

Json cmdLoad(const Json &a, Context &) {
  std::string path = a.str("path", "");
  if (path.empty() && a.has("text")) {
    // A design sent inline (a client on another file system, WSL for one)
    path = (std::filesystem::temp_directory_path() / "geant4_bridge_design.gdml").string();
    std::ofstream(path) << a["text"].str();
  }
  if (path.empty()) {
    throw Failure("load needs 'path' or 'text'");
  }
  if (g_runManager != nullptr && g_world != nullptr) {
    // Geant4 cannot swap the world under an initialised kernel safely: the
    // client restarts the engine, which takes a second.
    throw Failure("restart: the geometry of an initialized run manager cannot be replaced; restart the bridge");
  }
  Capture cap;
  G4GeometryManager::GetInstance()->OpenGeometry();
  if (g_world != nullptr) {
    G4PhysicalVolumeStore::Clean();
    G4LogicalVolumeStore::Clean();
    G4SolidStore::Clean();
    g_world = nullptr;
  }
  g_parser.Clear();
  g_parser.Read(path, a["validate"].boolean(false));
  g_world = g_parser.GetWorldVolume();
  if (g_world == nullptr) {
    throw Failure("the GDML file has no world volume");
  }
  g_gdmlPath = path;
  G4GeometryManager::GetInstance()->CloseGeometry(true, false);
  Json j = Json::object();
  j.set("world", g_world->GetName());
  j.set("physicalVolumes", static_cast<double>(G4PhysicalVolumeStore::GetInstance()->size()));
  j.set("logicalVolumes", static_cast<double>(G4LogicalVolumeStore::GetInstance()->size()));
  j.set("solids", static_cast<double>(G4SolidStore::GetInstance()->size()));
  Json mats = Json::array();
  for (const G4Material *m : *G4Material::GetMaterialTable()) {
    mats.push(m->GetName());
  }
  j.set("materials", mats);
  j.set("log", cap.take());
  return j;
}

Json cmdOverlaps(const Json &a, Context &ctx) {
  requireWorld();
  const int res = static_cast<int>(a.num("resolution", 1000));
  const double tol = a.num("tolerance", 0) * mm;
  const int maxErr = static_cast<int>(a.num("maxErrors", 1));
  Json faults = Json::array();
  std::size_t checked = 0;
  const auto *store = G4PhysicalVolumeStore::GetInstance();
  const std::size_t n = store->size();
  for (std::size_t i = 0; i < n; ++i) {
    G4VPhysicalVolume *pv = (*store)[i];
    if (pv == g_world) {
      continue;
    }
    Capture cap;
    const bool bad = pv->CheckOverlaps(res, tol, true, maxErr);
    ++checked;
    if (bad) {
      Json f = Json::object();
      f.set("volume", pv->GetName());
      f.set("logical", pv->GetLogicalVolume()->GetName());
      f.set("message", cap.take());
      faults.push(f);
    }
    if (i % 20 == 0) {
      ctx.progress(static_cast<double>(i) / n, "checking " + pv->GetName());
    }
    if (ctx.cancelled()) {
      break;
    }
  }
  Json j = Json::object();
  j.set("checked", static_cast<double>(checked));
  j.set("faults", faults);
  return j;
}

Json cmdMaterial(const Json &a, Context &) {
  const std::string name = a.str("name", "");
  Capture cap;
  G4Material *m = G4Material::GetMaterial(name, false);
  if (m == nullptr) {
    m = G4NistManager::Instance()->FindOrBuildMaterial(name);
  }
  if (m == nullptr) {
    throw Failure("no material " + name + " in the design or in Geant4's NIST database");
  }
  return materialJson(m);
}

Json cmdLocate(const Json &a, Context &) {
  requireWorld();
  const G4ThreeVector p = vec(a["point"], {a.num("x", 0), a.num("y", 0), a.num("z", 0)});
  G4Navigator nav;
  nav.SetWorldVolume(g_world);
  nav.LocateGlobalPointAndSetup(p, nullptr, false, true);
  G4TouchableHistory *t = nav.CreateTouchableHistory();
  Json path = Json::array();
  for (int d = t->GetHistoryDepth(); d >= 0; --d) {
    G4VPhysicalVolume *pv = t->GetVolume(d);
    if (pv == nullptr) {
      continue;
    }
    Json e = Json::object();
    e.set("physical", pv->GetName());
    e.set("logical", pv->GetLogicalVolume()->GetName());
    e.set("copy", t->GetReplicaNumber(d));
    e.set("material", pv->GetLogicalVolume()->GetMaterial()->GetName());
    path.push(e);
  }
  delete t;
  Json j = Json::object();
  j.set("path", path);
  return j;
}

/** One geantino along a direction: radiation and interaction lengths crossed. */
void geantino(G4Navigator &nav, const G4ThreeVector &origin, const G4ThreeVector &dir, double &x0, double &lam,
              double &path) {
  x0 = lam = path = 0;
  G4ThreeVector p = origin;
  G4VPhysicalVolume *pv = nav.LocateGlobalPointAndSetup(p, &dir, false, false);
  int guard = 0;
  while (pv != nullptr && guard++ < 100000) {
    G4double safety = 0;
    const G4double step = nav.ComputeStep(p, dir, 1.e12 * mm, safety);
    if (step >= kInfinity || step > 1.e11 * mm) {
      break;
    }
    const G4Material *m = pv->GetLogicalVolume()->GetMaterial();
    x0 += step / m->GetRadlen();
    lam += step / m->GetNuclearInterLength();
    path += step;
    p += step * dir;
    nav.SetGeometricallyLimitedStep();
    pv = nav.LocateGlobalPointAndSetup(p, &dir, true, false);
  }
}

Json cmdScan(const Json &a, Context &ctx) {
  requireWorld();
  const G4ThreeVector origin = vec(a["origin"], {0, 0, 0});
  G4Navigator nav;
  nav.SetWorldVolume(g_world);
  Json x0s = Json::array(), lams = Json::array(), paths = Json::array();
  std::vector<G4ThreeVector> dirs;
  if (a["directions"].isArray()) {
    for (std::size_t i = 0; i < a["directions"].size(); ++i) {
      dirs.push_back(vec(a["directions"].at(i), {0, 0, 1}).unit());
    }
  } else {
    // A grid in pseudorapidity (or theta) and phi, as the editor's map
    const bool eta = a.str("axis", "eta") == "eta";
    const double lo = a.num("min", eta ? -4 : 0), hi = a.num("max", eta ? 4 : CLHEP::pi);
    const int na = static_cast<int>(a.num("n", 40)), np = static_cast<int>(a.num("nphi", 16));
    for (int ip = 0; ip < np; ++ip) {
      for (int ia = 0; ia < na; ++ia) {
        const double v = lo + (hi - lo) * (ia + 0.5) / na;
        const double th = eta ? 2 * std::atan(std::exp(-v)) : v;
        const double ph = -CLHEP::pi + CLHEP::twopi * (ip + 0.5) / np;
        dirs.emplace_back(std::sin(th) * std::cos(ph), std::sin(th) * std::sin(ph), std::cos(th));
      }
    }
  }
  for (std::size_t i = 0; i < dirs.size(); ++i) {
    double x0 = 0, lam = 0, path = 0;
    geantino(nav, origin, dirs[i], x0, lam, path);
    x0s.push(x0);
    lams.push(lam);
    paths.push(path);
    if (i % 200 == 0) {
      ctx.progress(static_cast<double>(i) / dirs.size(), "ray " + std::to_string(i));
      if (ctx.cancelled()) {
        break;
      }
    }
  }
  Json j = Json::object();
  j.set("x0", x0s);
  j.set("lambda", lams);
  j.set("path_mm", paths);
  return j;
}

void ensureRunManager(const std::string &physics) {
  if (g_runManager != nullptr) {
    if (physics != g_physics) {
      throw Failure("restart: the physics list is " + g_physics + " for this engine; restart it for " + physics);
    }
    return;
  }
  std::vector<std::string> missing;
  datasets(missing);
  for (const auto &need : {"G4LEDATA", "G4LEVELGAMMADATA", "G4ENSDFSTATEDATA", "G4PARTICLEXSDATA"}) {
    for (const auto &m : missing) {
      if (m == need) {
        throw Failure(std::string("Geant4 data set ") + need +
                      " is missing: install the data sets (geant4-config --install-datasets, or conda install "
                      "geant4-data) or set GEANT4_DATA_DIR");
      }
    }
  }
  G4PhysListFactory factory;
  if (!factory.IsReferencePhysList(physics)) {
    throw Failure("unknown physics list " + physics + "; 'version' lists them");
  }
  g_runManager = G4RunManagerFactory::CreateRunManager(G4RunManagerType::SerialOnly);
  g_runManager->SetUserInitialization(new Detector);
  g_runManager->SetUserInitialization(factory.GetReferencePhysList(physics));
  g_runManager->SetUserAction(new Gun);
  g_runManager->SetUserAction(new EventsWithTracks);
  g_runManager->SetUserAction(new Steps);
  g_runManager->Initialize();
  g_physics = physics;
}

Json cmdRun(const Json &a, Context &ctx) {
  requireWorld();
  Capture cap;
  const std::string physics = a.str("physics", "FTFP_BERT");
  ensureRunManager(physics);
  g_run = RunData{};
  g_run.particle = a.str("particle", "e-");
  g_run.energy = a.num("energy_MeV", 1000) * MeV;
  g_run.position = vec(a["position_mm"], {0, 0, 0});
  g_run.direction = vec(a["direction"], {0, 0, 1});
  g_run.spread = a.num("spread_rad", 0);
  g_run.spot = a.num("spot_mm", 0);
  g_run.trackEvents = static_cast<int>(a.num("trackEvents", 10));
  g_run.maxPoints = static_cast<std::size_t>(a.num("maxPoints", 200000));
  g_run.minTrackEnergy = a.num("minTrackEnergy_MeV", 0) * MeV;
  const int events = static_cast<int>(a.num("events", 100));
  g_run.eventsWanted = events;
  g_run.ctx = &ctx;
  if (a.has("seed")) {
    CLHEP::HepRandom::setTheSeed(static_cast<long>(a.num("seed", 12345)));
  }
  if (g_handler != nullptr) {
    g_handler->warnings.clear();
  }
  const auto t0 = std::chrono::steady_clock::now();
  g_runManager->BeamOn(events);
  const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
  g_run.ctx = nullptr;

  Json j = Json::object();
  j.set("events", g_run.eventsDone);
  j.set("particle", g_run.particle);
  j.set("energy_MeV", g_run.energy / MeV);
  j.set("physics", g_physics);
  j.set("time_ms", ms);
  Json edep = Json::object();
  for (const auto &[lv, s] : g_run.sum) {
    const double n = std::max(1, g_run.eventsDone);
    const double mean = s / n, var = std::max(0.0, g_run.sum2[lv] / n - mean * mean);
    Json e = Json::object();
    e.set("total_MeV", s / MeV);
    e.set("mean_MeV", mean / MeV);
    e.set("rms_MeV", std::sqrt(var) / MeV);
    e.set("events", g_run.hits[lv]);
    try {
      const double mass = const_cast<G4LogicalVolume *>(lv)->GetMass(false, false);
      if (mass > 0) {
        e.set("dose_Gy_per_event", (mean / mass) / gray);
      }
    } catch (...) {
      // a mass Geant4 cannot compute leaves the dose out
    }
    edep.set(lv->GetName(), e);
  }
  j.set("edep", edep);
  Json tracks = Json::array();
  for (const Track &t : g_run.tracks) {
    Json tj = Json::object();
    tj.set("id", t.id);
    tj.set("parent", t.parent);
    tj.set("pdg", t.pdg);
    tj.set("charge", t.charge);
    tj.set("ekin_MeV", t.ekin / MeV);
    Json p = Json::array();
    for (float v : t.points) {
      p.push(static_cast<double>(v));
    }
    tj.set("points_mm", p);
    tracks.push(tj);
  }
  j.set("tracks", tracks);
  j.set("truncated", g_run.truncated);
  Json w = Json::array();
  if (g_handler != nullptr) {
    for (const auto &s : g_handler->warnings) {
      w.push(s);
    }
  }
  j.set("warnings", w);
  return j;
}

Json cmdUi(const Json &a, Context &) {
  Capture cap;
  const int code = G4UImanager::GetUIpointer()->ApplyCommand(a.str("command", ""));
  Json j = Json::object();
  j.set("code", code);
  j.set("output", cap.take());
  return j;
}

Json cmdMass(const Json &a, Context &) {
  requireWorld();
  G4LogicalVolume *lv = G4LogicalVolumeStore::GetInstance()->GetVolume(a.str("volume", ""), false);
  if (lv == nullptr) {
    throw Failure("no logical volume " + a.str("volume", ""));
  }
  Json j = Json::object();
  j.set("mass_kg", lv->GetMass() / kg);
  return j;
}

Json cmdExport(const Json &a, Context &) {
  requireWorld();
  const std::string path = a.str("path", "");
  if (path.empty()) {
    throw Failure("export needs a path");
  }
  Capture cap;
  G4GDMLParser writer;
  writer.Write(path, g_world, false);
  Json j = Json::object();
  j.set("written", path);
  return j;
}

} // namespace

std::string engineDescription() {
  return std::string("geant4-bridge on ") + G4Version.substr(1, G4Version.size() - 2);
}

void registerGeant4() {
  G4UImanager::GetUIpointer()->SetCoutDestination(&session());
  g_handler = new Handler;
  G4StateManager::GetStateManager()->SetExceptionHandler(g_handler);
  auto &r = Registry::instance();
  r.add("version", "Geant4 version, data sets, physics lists", cmdVersion);
  r.add("load", "read a GDML design: {path} or {text}", cmdLoad);
  r.add("overlaps", "G4VPhysicalVolume::CheckOverlaps on every placement: {resolution, tolerance_mm, maxErrors}", cmdOverlaps);
  r.add("material", "a material as Geant4 builds it: {name}", cmdMaterial);
  r.add("locate", "the volumes holding a point: {point:[x,y,z] mm}", cmdLocate);
  r.add("scan", "geantino material budget: {origin, directions} or {axis:eta|theta, min, max, n, nphi}", cmdScan);
  r.add("run", "beamOn with a particle gun: {events, particle, energy_MeV, position_mm, direction, spread_rad, spot_mm, "
               "physics, trackEvents, maxPoints, seed}", cmdRun);
  r.add("ui", "a Geant4 UI command: {command}", cmdUi);
  r.add("mass", "G4LogicalVolume::GetMass: {volume}", cmdMass);
  r.add("export", "write the loaded world back as GDML: {path}", cmdExport);
}

} // namespace g4b

// sphere_snn.hpp
//
// The Sphere Neural Network from C++ (sphere_snn.py says what the network is): the synapses between the neurons a
// program is built with, and what the machine holds besides. Build with the flags snn-config or find_package(SphereNN)
// give for the neurons used; each part below is compiled when its neurons are in the program:
//
//     g++ app.cpp $(snn-config --cflags --libs hepmc3 fastjet fjcontrib root)
//
//     #include "sphere_snn.hpp"
//     namespace snn = sphere::snn;
//     auto particles = snn::pseudojets(event);               // HepMC3 -> FastJet: final state, visible, in GeV
//     snn::JetTree tree("jets", {"msd"});                    // FastJet -> ROOT: one entry an event
//     tree.fill(jets, {{"msd", groomed_masses}});
//     snn::HepMC3Primaries primaries(event);                 // HepMC3 -> Geant4: a G4VPrimaryGenerator
//     snn::hepmc3(pythia, event);                            // Pythia8 -> HepMC3
//     torch::Tensor t = snn::tensor(tree, {"pt", "eta"});    // ROOT -> libtorch; snn::tensor(jets): FastJet -> libtorch
//     TF_Tensor* x = snn::tf_tensor(values, {2, 3});         // -> TensorFlow's C API
//     double cls = snn::cls(hsig, hbkg, hdata);              // ROOT -> pyhf, through the gateway (sphere_mlink.hpp)
//     for (const auto& n : snn::neurons()) std::cout << n.id << " " << n.version << "\n";   // what the machine holds
//
// In a ROOT macro snn_load.C gives the neurons and includes this file; in Python snn.cpp(...) does. The file may be
// included again once more neurons are in: the parts not yet compiled are then. snn-config defines SNN_HAS_<NEURON>
// for each neuron asked and SNN_CONFIGURED; without them, a neuron is taken as there when its header can be included.

#ifndef SPHERE_SNN_CORE
#define SPHERE_SNN_CORE

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace sphere {
namespace snn {

/** A neuron of the network: a module, its version, and where and from which languages it is reached. */
struct Neuron {
    std::string id, title, version, side, languages, where, family;
};

inline std::string env(const char* name) {
    const char* v = std::getenv(name);
    return v ? std::string(v) : std::string();
}

/** windows, wsl-<distribution>, linux or macos: where this program runs. */
inline std::string side() {
#if defined(_WIN32)
    return "windows";
#elif defined(__APPLE__)
    return "macos";
#else
    std::ifstream in("/proc/version");
    std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) { return char(std::tolower(c)); });
    if (text.find("microsoft") == std::string::npos) return "linux";
    std::string d = env("WSL_DISTRO_NAME");
    if (d.empty()) {
        std::ifstream os("/etc/os-release");
        std::string line;
        while (std::getline(os, line)) {
            if (line.rfind("NAME=", 0) == 0) {
                d = line.substr(5);
                d.erase(std::remove(d.begin(), d.end(), '"'), d.end());
                d = d.substr(0, d.find(' '));
                break;
            }
        }
    }
    return d.empty() ? "wsl" : "wsl-" + d;
#endif
}

/** $SPHERE_SNN: the network's folder, one subfolder a side. */
inline std::string network_folder() {
    const std::string given = env("SPHERE_SNN");
    if (!given.empty()) return given;
    const std::string bridge = env("SPHERE_BRIDGE");
    return (bridge.empty() ? std::string("bridge") : bridge) + "/snn";
}

/** The neurons the machine holds, as this side sees them (its own and, from Windows, those of WSL). */
inline std::vector<Neuron> neurons(const std::string& of_side = "") {
    std::vector<Neuron> out;
    std::ifstream in(network_folder() + "/" + (of_side.empty() ? side() : of_side) + "/neurons.tsv");
    std::string line;
    while (std::getline(in, line)) {
        if (line.empty() || line[0] == '#') continue;
        std::vector<std::string> f;
        std::stringstream s(line);
        std::string x;
        while (std::getline(s, x, '\t')) f.push_back(x);
        f.resize(7);
        out.push_back(Neuron{f[0], f[1], f[2], f[3], f[4], f[5], f[6]});
    }
    return out;
}

inline bool has(const std::string& id) {
    for (const Neuron& n : neurons())
        if (n.id == id) return true;
    return false;
}

inline std::string version(const std::string& id) {
    for (const Neuron& n : neurons())
        if (n.id == id) return n.version;
    return "";
}

}  // namespace snn
}  // namespace sphere

#endif  // SPHERE_SNN_CORE

// ---------------------------------------------------------------------------------------------------------------------
// Which neurons are in this program: asked for (SNN_HAS_X), or found by their header unless snn-config said what is in.
// Evaluated again at each inclusion, so that a later one compiles what an earlier one could not.
// ---------------------------------------------------------------------------------------------------------------------

#undef SNN_FASTJET_
#if defined(SNN_HAS_FASTJET)
#define SNN_FASTJET_ SNN_HAS_FASTJET
#elif !defined(SNN_CONFIGURED) && defined(__has_include)
#if __has_include(<fastjet/PseudoJet.hh>)
#define SNN_FASTJET_ 1
#endif
#endif
#ifndef SNN_FASTJET_
#define SNN_FASTJET_ 0
#endif

#undef SNN_HEPMC3_
#if defined(SNN_HAS_HEPMC3)
#define SNN_HEPMC3_ SNN_HAS_HEPMC3
#elif !defined(SNN_CONFIGURED) && defined(__has_include)
#if __has_include(<HepMC3/GenEvent.h>)
#define SNN_HEPMC3_ 1
#endif
#endif
#ifndef SNN_HEPMC3_
#define SNN_HEPMC3_ 0
#endif

#undef SNN_ROOT_
#if defined(SNN_HAS_ROOT)
#define SNN_ROOT_ SNN_HAS_ROOT
#elif !defined(SNN_CONFIGURED) && defined(__has_include)
#if __has_include(<TTree.h>)
#define SNN_ROOT_ 1
#endif
#endif
#ifndef SNN_ROOT_
#define SNN_ROOT_ 0
#endif

#undef SNN_GEANT4_
#if defined(SNN_HAS_GEANT4)
#define SNN_GEANT4_ SNN_HAS_GEANT4
#elif !defined(SNN_CONFIGURED) && defined(__has_include)
#if __has_include(<G4VPrimaryGenerator.hh>)
#define SNN_GEANT4_ 1
#endif
#endif
#ifndef SNN_GEANT4_
#define SNN_GEANT4_ 0
#endif

#undef SNN_CLHEP_
#if defined(SNN_HAS_CLHEP)
#define SNN_CLHEP_ SNN_HAS_CLHEP
#elif SNN_GEANT4_
#define SNN_CLHEP_ 1
#elif !defined(SNN_CONFIGURED) && defined(__has_include)
#if __has_include(<CLHEP/Vector/LorentzVector.h>)
#define SNN_CLHEP_ 1
#endif
#endif
#ifndef SNN_CLHEP_
#define SNN_CLHEP_ 0
#endif

#undef SNN_PYTHIA8_
#if defined(SNN_HAS_PYTHIA8)
#define SNN_PYTHIA8_ SNN_HAS_PYTHIA8
#elif !defined(SNN_CONFIGURED) && defined(__has_include)
#if __has_include(<Pythia8/Pythia.h>)
#define SNN_PYTHIA8_ 1
#endif
#endif
#ifndef SNN_PYTHIA8_
#define SNN_PYTHIA8_ 0
#endif

// libtorch and TensorFlow's C API are heavy to include: only when asked for.
#undef SNN_TORCH_
#if defined(SNN_HAS_TORCH)
#define SNN_TORCH_ SNN_HAS_TORCH
#else
#define SNN_TORCH_ 0
#endif

#undef SNN_TENSORFLOW_
#if defined(SNN_HAS_TENSORFLOW)
#define SNN_TENSORFLOW_ SNN_HAS_TENSORFLOW
#else
#define SNN_TENSORFLOW_ 0
#endif

// ---------------------------------------------------------------------------------------------------------------------
// HepMC3 -> FastJet
// ---------------------------------------------------------------------------------------------------------------------

#if SNN_FASTJET_ && SNN_HEPMC3_ && !defined(SPHERE_SNN_HEPMC3_FASTJET)
#define SPHERE_SNN_HEPMC3_FASTJET
#include <HepMC3/GenEvent.h>
#include <HepMC3/GenParticle.h>
#include <HepMC3/Units.h>
#include <fastjet/PseudoJet.hh>

namespace sphere {
namespace snn {

/** A particle no detector sees: neutrinos, the graviton, the lightest neutralino, sneutrinos, the gravitino. */
inline bool invisible(int pid) {
    switch (std::abs(pid)) {
    case 12: case 14: case 16: case 18: case 39: case 1000012: case 1000014: case 1000016: case 1000022: case 1000039:
    case 2000012: case 2000014: case 2000016: case 5000039:
        return true;
    default:
        return false;
    }
}

/** Which particles of an event become PseudoJets. */
struct Select {
    bool final_state = true;  ///< status 1 only
    bool visible = true;      ///< what invisible() names left out
    double ptmin = 0;         ///< GeV
    double absetamax = -1;    ///< no cut when negative
};

/** HepMC3 -> FastJet: an event's particles as PseudoJets in GeV, user_index the particle's id in the event. */
inline std::vector<fastjet::PseudoJet> pseudojets(const HepMC3::GenEvent& event, const Select& s = Select()) {
    std::vector<fastjet::PseudoJet> out;
    out.reserve(event.particles().size());
    const double unit = event.momentum_unit() == HepMC3::Units::MEV ? 1e-3 : 1.0;
    for (const auto& p : event.particles()) {
        if (s.final_state && p->status() != 1) continue;
        if (s.visible && invisible(p->pid())) continue;
        const HepMC3::FourVector& m = p->momentum();
        fastjet::PseudoJet j(m.px() * unit, m.py() * unit, m.pz() * unit, m.e() * unit);
        if (s.ptmin > 0 && j.pt() < s.ptmin) continue;
        if (s.absetamax >= 0 && std::abs(j.eta()) > s.absetamax) continue;
        j.set_user_index(p->id());
        out.push_back(j);
    }
    return out;
}

}  // namespace snn
}  // namespace sphere
#endif

// ---------------------------------------------------------------------------------------------------------------------
// ROOT; FastJet <-> ROOT
// ---------------------------------------------------------------------------------------------------------------------

#if SNN_ROOT_ && !defined(SPHERE_SNN_ROOT)
#define SPHERE_SNN_ROOT
#include <TH1.h>

namespace sphere {
namespace snn {

/** A histogram's bin contents, under- and overflow left out. */
inline std::vector<double> contents(const TH1& h) {
    std::vector<double> out;
    for (int b = 1; b <= h.GetNbinsX(); ++b) out.push_back(h.GetBinContent(b));
    return out;
}

/** A histogram's bin errors, under- and overflow left out. */
inline std::vector<double> errors(const TH1& h) {
    std::vector<double> out;
    for (int b = 1; b <= h.GetNbinsX(); ++b) out.push_back(h.GetBinError(b));
    return out;
}

}  // namespace snn
}  // namespace sphere
#endif

#if SNN_FASTJET_ && SNN_ROOT_ && !defined(SPHERE_SNN_FASTJET_ROOT)
#define SPHERE_SNN_FASTJET_ROOT
#include <Math/Vector4D.h>
#include <TLorentzVector.h>
#include <TTree.h>
#include <fastjet/PseudoJet.hh>
#include <initializer_list>

namespace sphere {
namespace snn {

inline ROOT::Math::PxPyPzEVector lorentz(const fastjet::PseudoJet& j) { return ROOT::Math::PxPyPzEVector(j.px(), j.py(), j.pz(), j.E()); }
inline TLorentzVector tlorentz(const fastjet::PseudoJet& j) { return TLorentzVector(j.px(), j.py(), j.pz(), j.E()); }
inline fastjet::PseudoJet pseudojet(const ROOT::Math::PxPyPzEVector& v) { return fastjet::PseudoJet(v.Px(), v.Py(), v.Pz(), v.E()); }
inline fastjet::PseudoJet pseudojet(const TLorentzVector& v) { return fastjet::PseudoJet(v.Px(), v.Py(), v.Pz(), v.E()); }

/** FastJet -> ROOT: jets as a TTree, one entry an event: the vectors pt, eta, phi (-pi to pi), m and nconst of its
 *  jets, and the per-jet columns named when the tree is made. The tree belongs to the directory open when it is made:
 *  open the TFile first and Write it at the end. */
class JetTree {
public:
    explicit JetTree(const char* name = "jets", std::initializer_list<std::string> columns = {}, const char* title = "jets (sphere_snn)")
        : tree_(new TTree(name, title)) {
        book(columns);
    }
    JetTree(TTree* tree, std::initializer_list<std::string> columns = {}) : tree_(tree) { book(columns); }
    JetTree(const JetTree&) = delete;
    JetTree& operator=(const JetTree&) = delete;

    /** One event's jets, and for each column named one value a jet. */
    void fill(const std::vector<fastjet::PseudoJet>& jets, const std::map<std::string, std::vector<double>>& columns = {}) {
        for (const auto& c : columns)
            if (!extra_.count(c.first)) throw std::invalid_argument("JetTree: no column " + c.first + " was named when the tree was made");
        pt_.clear();
        eta_.clear();
        phi_.clear();
        m_.clear();
        nconst_.clear();
        for (const auto& j : jets) {
            pt_.push_back(j.pt());
            eta_.push_back(j.eta());
            phi_.push_back(j.phi_std());
            m_.push_back(j.m());
            nconst_.push_back(j.has_constituents() ? int(j.constituents().size()) : 0);
        }
        for (auto& c : extra_) {
            c.second->clear();
            const auto it = columns.find(c.first);
            if (it != columns.end()) *c.second = it->second;
        }
        tree_->Fill();
    }
    TTree* tree() const { return tree_; }

private:
    void book(std::initializer_list<std::string> columns) {
        tree_->Branch("pt", &pt_);
        tree_->Branch("eta", &eta_);
        tree_->Branch("phi", &phi_);
        tree_->Branch("m", &m_);
        tree_->Branch("nconst", &nconst_);
        for (const auto& c : columns) {
            auto& v = extra_[c];
            v.reset(new std::vector<double>());
            tree_->Branch(c.c_str(), v.get());
        }
    }
    TTree* tree_;
    std::vector<double> pt_, eta_, phi_, m_;
    std::vector<int> nconst_;
    std::map<std::string, std::unique_ptr<std::vector<double>>> extra_;
};

}  // namespace snn
}  // namespace sphere
#endif

// ---------------------------------------------------------------------------------------------------------------------
// HepMC3 -> Geant4
// ---------------------------------------------------------------------------------------------------------------------

#if SNN_GEANT4_ && SNN_HEPMC3_ && !defined(SPHERE_SNN_HEPMC3_GEANT4)
#define SPHERE_SNN_HEPMC3_GEANT4
#include <G4Event.hh>
#include <G4ParticleTable.hh>
#include <G4PhysicalConstants.hh>
#include <G4PrimaryParticle.hh>
#include <G4PrimaryVertex.hh>
#include <G4SystemOfUnits.hh>
#include <G4VPrimaryGenerator.hh>
#include <HepMC3/GenEvent.h>
#include <HepMC3/GenParticle.h>
#include <HepMC3/GenVertex.h>

namespace sphere {
namespace snn {

/** HepMC3 -> Geant4: an event's final-state particles as primaries, each from its production vertex (the event's
 *  origin when it has none). A particle Geant4's table does not know (it was not built, or a nucleus) is counted in
 *  skipped(). The event is held by reference: it must outlive GeneratePrimaryVertex. */
class HepMC3Primaries : public G4VPrimaryGenerator {
public:
    HepMC3Primaries() = default;
    explicit HepMC3Primaries(const HepMC3::GenEvent& event) : event_(&event) {}
    void set(const HepMC3::GenEvent& event) { event_ = &event; }

    void GeneratePrimaryVertex(G4Event* g4) override {
        if (!event_) return;
        const double mom = event_->momentum_unit() == HepMC3::Units::GEV ? CLHEP::GeV : CLHEP::MeV;
        const double len = event_->length_unit() == HepMC3::Units::MM ? CLHEP::mm : CLHEP::cm;
        std::map<const HepMC3::GenVertex*, G4PrimaryVertex*> vertices;
        G4ParticleTable* table = G4ParticleTable::GetParticleTable();
        for (const auto& p : event_->particles()) {
            if (p->status() != 1) continue;
            G4ParticleDefinition* def = table->FindParticle(p->pid());
            if (!def) {
                ++skipped_;
                continue;
            }
            const auto v = p->production_vertex();
            G4PrimaryVertex*& pv = vertices[v.get()];
            if (!pv) {
                const HepMC3::FourVector pos = v ? v->position() : event_->event_pos();
                pv = new G4PrimaryVertex(pos.x() * len, pos.y() * len, pos.z() * len, pos.t() * len / CLHEP::c_light);
                g4->AddPrimaryVertex(pv);
            }
            const HepMC3::FourVector& m = p->momentum();
            pv->SetPrimary(new G4PrimaryParticle(def, m.px() * mom, m.py() * mom, m.pz() * mom, m.e() * mom));
        }
    }
    std::size_t skipped() const { return skipped_; }

private:
    const HepMC3::GenEvent* event_ = nullptr;
    std::size_t skipped_ = 0;
};

}  // namespace snn
}  // namespace sphere
#endif

// ---------------------------------------------------------------------------------------------------------------------
// Pythia8 -> HepMC3
// ---------------------------------------------------------------------------------------------------------------------

#if SNN_PYTHIA8_ && SNN_HEPMC3_ && !defined(SPHERE_SNN_PYTHIA8_HEPMC3)
#define SPHERE_SNN_PYTHIA8_HEPMC3
#include <Pythia8/Pythia.h>
#include <Pythia8Plugins/HepMC3.h>

namespace sphere {
namespace snn {

/** Pythia8 -> HepMC3: Pythia's current event, its cross section and weights with it, as a HepMC3 event. */
inline bool hepmc3(Pythia8::Pythia& pythia, HepMC3::GenEvent& event) {
    static thread_local HepMC3::Pythia8ToHepMC3 to;
    return to.fill_next_event(pythia, event);
}

}  // namespace snn
}  // namespace sphere
#endif

// ---------------------------------------------------------------------------------------------------------------------
// CLHEP <-> HepMC3, CLHEP <-> FastJet
// ---------------------------------------------------------------------------------------------------------------------

#if SNN_CLHEP_ && SNN_HEPMC3_ && !defined(SPHERE_SNN_CLHEP_HEPMC3)
#define SPHERE_SNN_CLHEP_HEPMC3
#include <CLHEP/Vector/LorentzVector.h>
#include <HepMC3/FourVector.h>

namespace sphere {
namespace snn {
inline CLHEP::HepLorentzVector clhep(const HepMC3::FourVector& v) { return CLHEP::HepLorentzVector(v.px(), v.py(), v.pz(), v.e()); }
inline HepMC3::FourVector fourvector(const CLHEP::HepLorentzVector& v) { return HepMC3::FourVector(v.px(), v.py(), v.pz(), v.e()); }
}  // namespace snn
}  // namespace sphere
#endif

#if SNN_CLHEP_ && SNN_FASTJET_ && !defined(SPHERE_SNN_CLHEP_FASTJET)
#define SPHERE_SNN_CLHEP_FASTJET
#include <CLHEP/Vector/LorentzVector.h>
#include <fastjet/PseudoJet.hh>

namespace sphere {
namespace snn {
inline CLHEP::HepLorentzVector clhep(const fastjet::PseudoJet& j) { return CLHEP::HepLorentzVector(j.px(), j.py(), j.pz(), j.E()); }
inline fastjet::PseudoJet pseudojet(const CLHEP::HepLorentzVector& v) { return fastjet::PseudoJet(v.px(), v.py(), v.pz(), v.e()); }
}  // namespace snn
}  // namespace sphere
#endif

// ---------------------------------------------------------------------------------------------------------------------
// libtorch: rows, jets and trees as tensors
// ---------------------------------------------------------------------------------------------------------------------

#if SNN_TORCH_ && !defined(SPHERE_SNN_TORCH)
#define SPHERE_SNN_TORCH
#include <torch/torch.h>

namespace sphere {
namespace snn {

/** Rows of numbers as one (rows, columns) float64 tensor. */
inline torch::Tensor tensor(const std::vector<std::vector<double>>& rows) {
    const std::int64_t n = std::int64_t(rows.size()), k = rows.empty() ? 0 : std::int64_t(rows[0].size());
    torch::Tensor t = torch::empty({n, k}, torch::kFloat64);
    auto a = t.accessor<double, 2>();
    for (std::int64_t i = 0; i < n; ++i) {
        if (std::int64_t(rows[std::size_t(i)].size()) != k) throw std::invalid_argument("tensor: rows of different lengths");
        for (std::int64_t j = 0; j < k; ++j) a[i][j] = rows[std::size_t(i)][std::size_t(j)];
    }
    return t;
}

/** Numbers as a float64 tensor of one dimension. */
inline torch::Tensor tensor(const std::vector<double>& values) {
    torch::Tensor t = torch::empty({std::int64_t(values.size())}, torch::kFloat64);
    std::copy(values.begin(), values.end(), t.data_ptr<double>());
    return t;
}

}  // namespace snn
}  // namespace sphere
#endif

#if SNN_TORCH_ && SNN_FASTJET_ && !defined(SPHERE_SNN_TORCH_FASTJET)
#define SPHERE_SNN_TORCH_FASTJET
#include <fastjet/PseudoJet.hh>

namespace sphere {
namespace snn {
/** FastJet -> libtorch: particles or jets as an (n, 4) float64 tensor of px, py, pz, E. */
inline torch::Tensor tensor(const std::vector<fastjet::PseudoJet>& jets) {
    torch::Tensor t = torch::empty({std::int64_t(jets.size()), 4}, torch::kFloat64);
    auto a = t.accessor<double, 2>();
    for (std::size_t i = 0; i < jets.size(); ++i) {
        a[std::int64_t(i)][0] = jets[i].px();
        a[std::int64_t(i)][1] = jets[i].py();
        a[std::int64_t(i)][2] = jets[i].pz();
        a[std::int64_t(i)][3] = jets[i].E();
    }
    return t;
}
}  // namespace snn
}  // namespace sphere
#endif

#if SNN_TORCH_ && SNN_ROOT_ && !defined(SPHERE_SNN_TORCH_ROOT)
#define SPHERE_SNN_TORCH_ROOT
#include <TTree.h>
#include <TTreeFormula.h>

namespace sphere {
namespace snn {
/** ROOT -> libtorch: expressions of a tree's branches (a branch's name is one) as the columns of one (entries,
 *  expressions) float64 tensor. */
inline torch::Tensor tensor(TTree& tree, const std::vector<std::string>& columns) {
    std::vector<std::unique_ptr<TTreeFormula>> formulas;
    for (const std::string& c : columns) {
        formulas.emplace_back(new TTreeFormula(("snn_" + c).c_str(), c.c_str(), &tree));
        if (formulas.back()->GetNdim() == 0) throw std::invalid_argument("tensor: the tree has no " + c);
    }
    const Long64_t n = tree.GetEntries();
    torch::Tensor t = torch::empty({std::int64_t(n), std::int64_t(columns.size())}, torch::kFloat64);
    auto a = t.accessor<double, 2>();
    for (Long64_t i = 0; i < n; ++i) {
        tree.LoadTree(i);
        for (std::size_t j = 0; j < formulas.size(); ++j) {
            formulas[j]->GetNdata();
            a[std::int64_t(i)][std::int64_t(j)] = formulas[j]->EvalInstance(0);
        }
    }
    return t;
}
}  // namespace snn
}  // namespace sphere
#endif

// ---------------------------------------------------------------------------------------------------------------------
// TensorFlow's C API
// ---------------------------------------------------------------------------------------------------------------------

#if SNN_TENSORFLOW_ && !defined(SPHERE_SNN_TENSORFLOW)
#define SPHERE_SNN_TENSORFLOW
#include <cstring>
#include <tensorflow/c/c_api.h>

namespace sphere {
namespace snn {
inline std::string tensorflow_version() { return TF_Version(); }

/** Numbers as a float64 TF_Tensor of a shape: a copy, which TF_DeleteTensor frees. */
inline TF_Tensor* tf_tensor(const std::vector<double>& values, const std::vector<std::int64_t>& shape) {
    std::int64_t n = 1;
    for (std::int64_t d : shape) n *= d;
    if (n != std::int64_t(values.size())) throw std::invalid_argument("tf_tensor: the shape does not hold the values");
    TF_Tensor* t = TF_AllocateTensor(TF_DOUBLE, shape.data(), int(shape.size()), values.size() * sizeof(double));
    std::memcpy(TF_TensorData(t), values.data(), values.size() * sizeof(double));
    return t;
}
}  // namespace snn
}  // namespace sphere
#endif

// ---------------------------------------------------------------------------------------------------------------------
// The gateway: pyhf, and any neuron of another Python, from C++ (define SPHERE_SNN_NO_GATEWAY to leave it out)
// ---------------------------------------------------------------------------------------------------------------------

#if !defined(SPHERE_SNN_NO_GATEWAY) && !defined(SPHERE_SNN_GATEWAY) && defined(__has_include)
#if __has_include("sphere_mlink.hpp")
#define SPHERE_SNN_GATEWAY
#include "sphere_mlink.hpp"

namespace sphere {
namespace snn {

/** pyhf's observed CLs at mu for one channel of counts: signal, background, its uncertainty, what was observed. */
inline double cls(const std::vector<double>& signal, const std::vector<double>& background, const std::vector<double>& uncertainty,
                  const std::vector<double>& observed, double mu = 1.0) {
    return sphere::mlink::link().pyhf(signal, background, uncertainty, observed, mu).cls_obs;
}

/** Python run in the gateway, every neuron of its Python at hand (ROOT, fastjet, torch, tf, pyhf...): its number. */
inline double hub(const std::string& code, const sphere::mlink::Inputs& inputs = {}) { return sphere::mlink::link().number(code, inputs); }

}  // namespace snn
}  // namespace sphere
#endif
#endif

#if defined(SPHERE_SNN_GATEWAY) && SNN_ROOT_ && !defined(SPHERE_SNN_GATEWAY_ROOT)
#define SPHERE_SNN_GATEWAY_ROOT
namespace sphere {
namespace snn {
/** ROOT -> pyhf: one channel of histograms, the background's bin errors its uncertainty; the observed CLs at mu. */
inline double cls(const TH1& signal, const TH1& background, const TH1& observed, double mu = 1.0) {
    return sphere::mlink::link().pyhf(signal, background, observed, mu).cls_obs;
}
}  // namespace snn
}  // namespace sphere
#endif

#!/usr/bin/env python3
"""sphere_snn -- the Sphere Neural Network: every module of this machine, wired to every other.

A neuron is a module: ROOT and its parts (RDataFrame, RooFit, TMVA, Minuit2, GenVector, TGeo), Geant4, CLHEP, VecGeom,
FastJet and fjcontrib, HepMC3, LHAPDF, Pythia 8, YODA, Rivet, Herwig, MadGraph, Delphes, Golem95; PyTorch (and libtorch),
TensorFlow (and its C API), Keras, JAX, pyhf, iminuit, scikit-learn, uproot, awkward, vector...; Julia and its
JetReconstruction; Sphere's own Java translations, its networks and its gateway. A synapse is a way data goes from one
neuron to another: a HepMC3 event becomes FastJet's particles, jets become a ROOT tree, a tree becomes a tensor of
PyTorch, TensorFlow or JAX, histograms become a pyhf model, a Geant4 geometry becomes ROOT's, a Pythia event becomes
HepMC3's, HepMC3 events become Geant4's primaries. This file is the cortex: it finds the neurons of the machine, on this
side of WSL and the other, chooses installations that can live in one program together, says to every language how to
reach each one, and runs every neuron and synapse for real to say which work.

    C++, ROOT macros, Geant4 applications   g++ -std=c++20 app.cpp $(snn-config --cflags --libs hepmc3 fastjet root)
                                            #include "sphere_snn.hpp"               (the synapses, in C++)
                                            find_package(SphereNN COMPONENTS fastjet root geant4)   SNN::fastjet ...
                                            .x $SPHERE_SNN/<side>/snn_load.C("fastjet fjcontrib geant4")   (ROOT prompt)
    Python                                  import sphere_snn as snn
                                            ROOT, fj, torch = snn.use("root", "fastjet", "torch")
                                            gbl = snn.cpp("fjcontrib", "geant4")   (any C++ neuron through ROOT's Cling)
    Julia                                   SphereSNN.jets(px, py, pz, E; R = 0.4); SphereSNN.hub("ROOT.gROOT.GetVersion()")
    any program, either side of WSL         the gateway (sphere_mlink): code naming a neuron its Python lacks is sent on
                                            to a Python that has it

Commands (python sphere_snn.py <command>):

    detect [--rescan] [--json] [--local]    the neurons of this machine and the installation chosen for each
    write                                   env.tsv (what every process Sphere starts is given), thissnn.sh (Sphere's
                                            terminals), bin/snn, bin/snn-config, CMake, pkg-config, snn_load.C, the Julia
                                            module, neurons.tsv and connectome.json, in $SPHERE_SNN/<side>: inside Sphere
    declare [--dry]                         settings.conf [SNN] made true, the user's own lines kept
    run [--why] <file> [args]               a program run with what it uses; a failed C++ build read, fixed, built again
    index | train | predict | suggest | memory | learn-data   the API of every neuron, the neural model trained on it,
                                            what it reads in code, what a program will want next, what was learned
    config [--cflags] [--libs] [--env] <neurons>   a C++ build of those neurons together (bin/snn-config is this)
    cmake <neurons>                         their CMake targets (what find_package(SphereNN) includes)
    env [sh|cmd|powershell] [<neurons>]     what a shell needs
    check [--quick] [--only cpp,python,cling,julia,gateway]   every neuron and synapse run; the matrix written
    matrix [--json]                         the last check, neuron by neuron
    route <from> <to>                       the synapses that carry data from one neuron to the other

One program holds one copy of a library. Two FastJets in one process (MadGraph's libfastjet.so.0 and the Python wheel's
libfastjet.so) would each take the other's symbols, so for the neurons a program asks for, one installation of each is
chosen: the newest, from the family (the installation tree) the others asked for come from when they are tied together
-- fjcontrib to the FastJet it was built on, Rivet to its FastJet, HepMC3 and YODA. Geant4 carries its own CLHEP, which
a program with Geant4 gets. The C++ standard is ROOT's when ROOT is in (its headers insist), else the highest asked.
In a Python process, what is already loaded wins: a second installation of a library is refused, with the way round it.

Sphere is a sandbox. The network's whole configuration is the [SNN] section of Sphere's settings.conf (one key a neuron,
the installation it is taken from; empty switches it off), everything it writes is in Sphere's folder, and what it runs
for itself runs with a home inside Sphere: nothing of the machine is written.
"""

import json
import math
import os
import re
import shlex
import shutil
import struct
import subprocess
import sys
import time

FORMAT = 1
HERE = os.path.dirname(os.path.abspath(__file__))
WINDOWS = os.name == "nt"
LINUX = sys.platform.startswith("linux")


# ===========================================================================
# What a network is made of
# ===========================================================================

GROUPS = ("cern", "simulation", "jets", "events", "generators", "analysis", "ml", "statistics", "data", "julia", "sphere")


def _n(title, group, deps=(), parent=None):
    return {"title": title, "group": group, "deps": tuple(deps), "parent": parent}


NEURONS = {
    "root": _n("ROOT", "cern"),
    "rdataframe": _n("RDataFrame", "cern", ["root"], "root"),
    "roofit": _n("RooFit, RooStats, HistFactory", "cern", ["root"], "root"),
    "tmva": _n("TMVA and SOFIE", "cern", ["root"], "root"),
    "minuit2": _n("Minuit2", "cern", ["root"], "root"),
    "genvector": _n("GenVector", "cern", ["root"], "root"),
    "tgeo": _n("TGeo and GDML", "cern", ["root"], "root"),
    "mathmore": _n("MathMore", "cern", ["root"], "root"),
    "rntuple": _n("RNTuple", "cern", ["root"], "root"),
    "geant4": _n("Geant4", "simulation"),
    "clhep": _n("CLHEP", "simulation"),
    "vecgeom": _n("VecGeom", "simulation"),
    "delphes": _n("Delphes", "simulation", ["root"]),
    "pyg4ometry": _n("pyg4ometry", "simulation"),
    "fastjet": _n("FastJet", "jets"),
    "fjcontrib": _n("FastJet contrib", "jets", ["fastjet"]),
    "hepmc3": _n("HepMC3", "events"),
    "pylhe": _n("pylhe", "events"),
    "lhapdf": _n("LHAPDF", "generators"),
    "pythia8": _n("Pythia 8", "generators"),
    "madgraph": _n("MadGraph5_aMC@NLO", "generators"),
    "herwig": _n("Herwig", "generators"),
    "thepeg": _n("ThePEG", "generators"),
    "golem95": _n("Golem95 and OneLOop (one-loop integrals)", "generators"),
    "yoda": _n("YODA", "analysis"),
    "rivet": _n("Rivet", "analysis", ["fastjet", "hepmc3", "yoda"]),
    "coffea": _n("coffea", "analysis"),
    "torch": _n("PyTorch, libtorch", "ml"),
    "tensorflow": _n("TensorFlow, its C API", "ml"),
    "keras": _n("Keras", "ml"),
    "jax": _n("JAX", "ml"),
    "sklearn": _n("scikit-learn", "ml"),
    "onnx": _n("ONNX", "ml"),
    "onnxruntime": _n("ONNX Runtime", "ml"),
    "pyhf": _n("pyhf", "statistics"),
    "iminuit": _n("iminuit", "statistics"),
    "spey": _n("spey", "statistics"),
    "zfit": _n("zfit", "statistics"),
    "numpy": _n("NumPy", "data"),
    "scipy": _n("SciPy", "data"),
    "uproot": _n("uproot", "data"),
    "awkward": _n("awkward", "data"),
    "vector": _n("vector", "data"),
    "hist": _n("hist", "data"),
    "particle": _n("particle", "data"),
    "numba": _n("Numba", "data"),
    "pandas": _n("pandas", "data"),
    "matplotlib": _n("Matplotlib", "data"),
    "mplhep": _n("mplhep", "data"),
    "julia": _n("Julia", "julia"),
    "jetreconstruction": _n("JetReconstruction.jl", "julia", ["julia"]),
    "lorentzvectorhep": _n("LorentzVectorHEP.jl", "julia", ["julia"]),
    "unroot": _n("UnROOT.jl", "julia", ["julia"]),
    "pythoncall": _n("PythonCall.jl", "julia", ["julia"]),
    "geant4jl": _n("Geant4.jl", "julia", ["julia"]),
    "fhist": _n("FHist.jl", "julia", ["julia"]),
    "java": _n("Java (22 or later), which runs Sphere's translations", "sphere"),
    "sphere-java": _n("Sphere's Java FastJet, fjcontrib, HepMC3, CLHEP, Minuit2, TMinuit, Golem95, ROOT I/O", "sphere"),
    "sphere-nn": _n("Sphere's networks and portable graphs", "sphere"),
    "mlink": _n("the gateway, sphere_mlink", "sphere"),
}

# Neurons whose installations must be the same tree, when both are in one program: (a, b) ties a to b.
TIES = (("fjcontrib", "fastjet"), ("rivet", "fastjet"), ("rivet", "hepmc3"), ("rivet", "yoda"),
        ("thepeg", "fastjet"), ("thepeg", "hepmc3"), ("thepeg", "lhapdf"), ("herwig", "thepeg"))
# ... and those better the same tree, not bound to: Pythia's LHAPDF plugin was built on one LHAPDF.
LEANS = (("pythia8", "lhapdf"),)

# The Python packages a neuron can be: (neuron, distribution, module).
PY_PACKAGES = (
    ("torch", "torch", "torch"), ("tensorflow", "tensorflow", "tensorflow"), ("keras", "keras", "keras"),
    ("jax", "jax", "jax"), ("sklearn", "scikit-learn", "sklearn"), ("onnx", "onnx", "onnx"),
    ("onnxruntime", "onnxruntime", "onnxruntime"), ("pyhf", "pyhf", "pyhf"), ("iminuit", "iminuit", "iminuit"),
    ("spey", "spey", "spey"), ("zfit", "zfit", "zfit"), ("numpy", "numpy", "numpy"), ("scipy", "scipy", "scipy"),
    ("uproot", "uproot", "uproot"), ("awkward", "awkward", "awkward"), ("vector", "vector", "vector"),
    ("hist", "hist", "hist"), ("coffea", "coffea", "coffea"), ("particle", "particle", "particle"),
    ("numba", "numba", "numba"), ("pandas", "pandas", "pandas"), ("matplotlib", "matplotlib", "matplotlib"),
    ("mplhep", "mplhep", "mplhep"), ("pyg4ometry", "pyg4ometry", "pyg4ometry"), ("pylhe", "pylhe", "pylhe"),
    ("hepmc3", "pyhepmc", "pyhepmc"), ("fastjet", "fastjet", "fastjet"), ("geant4", "geant4_pybind", "geant4_pybind"),
    ("pythia8", "pythia8mc", "pythia8"), ("root", "ROOT", "ROOT"),
)

JULIA_PACKAGES = {"JetReconstruction": "jetreconstruction", "LorentzVectorHEP": "lorentzvectorhep", "UnROOT": "unroot",
                  "PythonCall": "pythoncall", "Geant4": "geant4jl", "FHist": "fhist"}

# The names a program uses for a neuron: snn.use("ROOT"), and the names the gateway binds.
ALIASES = {"ROOT": "root", "pyroot": "root", "cppyy": "root", "pytorch": "torch", "libtorch": "torch",
           "tf": "tensorflow", "fj": "fastjet", "contrib": "fjcontrib", "pyhepmc": "hepmc3", "HepMC3": "hepmc3",
           "g4": "geant4", "Geant4": "geant4", "Pythia8": "pythia8", "pythia": "pythia8", "ak": "awkward",
           "np": "numpy", "jnp": "jax", "scikit-learn": "sklearn", "LHAPDF": "lhapdf", "Rivet": "rivet",
           "YODA": "yoda", "CLHEP": "clhep", "mg5": "madgraph", "JetReconstruction": "jetreconstruction",
           "Golem95": "golem95", "golem": "golem95", "OneLOop": "golem95", "avh_olo": "golem95"}

# The parts of ROOT a neuron is: its libraries (the first ones required) and a header.
ROOT_PARTS = {
    "rdataframe": (["ROOTDataFrame"], 1, ["ROOT/RDataFrame.hxx"]),
    "roofit": (["RooFitCore", "RooFit", "RooStats", "HistFactory"], 2, ["RooRealVar.h"]),
    "tmva": (["TMVA"], 1, ["TMVA/Reader.h"]),
    "minuit2": (["Minuit2"], 1, ["Minuit2/Minuit2Minimizer.h"]),
    "genvector": (["GenVector"], 1, ["Math/Vector4D.h"]),
    "tgeo": (["Geom", "Gdml"], 2, ["TGeoManager.h"]),
    "mathmore": (["MathMore"], 1, ["Math/SpecFuncMathMore.h"]),
    "rntuple": (["ROOTNTuple"], 1, ["ROOT/RNTuple.hxx"]),
}

# What Cling is given when a neuron is asked for in Python (the headers that exist are declared).
CLING_HEADERS = {
    "fastjet": ["fastjet/ClusterSequence.hh", "fastjet/Selector.hh", "fastjet/tools/Filter.hh"],
    "fjcontrib": ["fastjet/contrib/SoftDrop.hh", "fastjet/contrib/Nsubjettiness.hh", "fastjet/contrib/EnergyCorrelator.hh",
                  "fastjet/contrib/RecursiveSoftDrop.hh", "fastjet/contrib/ModifiedMassDropTagger.hh",
                  "fastjet/contrib/LundGenerator.hh", "fastjet/contrib/SoftKiller.hh",
                  "fastjet/contrib/ConstituentSubtractor.hh", "fastjet/contrib/VariableRPlugin.hh"],
    "hepmc3": ["HepMC3/Version.h", "HepMC3/GenEvent.h", "HepMC3/GenParticle.h", "HepMC3/GenVertex.h",
               "HepMC3/ReaderAscii.h", "HepMC3/WriterAscii.h", "HepMC3/Print.h"],
    "geant4": ["G4Version.hh", "G4NistManager.hh", "G4SystemOfUnits.hh", "G4PhysicalConstants.hh", "G4Box.hh",
               "G4Tubs.hh", "G4LogicalVolume.hh", "G4PVPlacement.hh", "G4ParticleTable.hh", "G4GDMLParser.hh"],
    "clhep": ["CLHEP/Vector/LorentzVector.h", "CLHEP/Units/SystemOfUnits.h"],
    "lhapdf": ["LHAPDF/LHAPDF.h"],
    "pythia8": ["Pythia8/Pythia.h"],
    "yoda": ["YODA/Histo.h", "YODA/Histo1D.h"],
    "rivet": ["Rivet/AnalysisHandler.hh"],
}

# Geant4's libraries a program without a window does without (loaded only for 'geant4-vis').
G4_VIS = {"G4OpenGL", "G4Tree", "G4FR", "G4GMocren", "G4RayTracer", "G4VRML", "G4ToolsSG", "G4vis_management",
          "G4modeling", "G4interfaces", "G4visQt3D", "G4visVtk", "G4OpenInventor", "G4gl2ps"}


def _s(a, b, what, how, both=False):
    return {"id": a + (" <> " if both else " > ") + b, "from": a, "to": b, "what": what, "how": how, "both": both}


SYNAPSES = [
    _s("hepmc3", "fastjet", "an event's final-state visible particles as PseudoJets",
       {"cpp": "sphere::snn::pseudojets(event)", "python": "snn.pseudojets(event)", "cling": "sphere::snn::pseudojets"}),
    _s("fastjet", "fjcontrib", "jets groomed and measured on the same libfastjet",
       {"cpp": "#include <fastjet/contrib/SoftDrop.hh>", "python": "snn.cpp('fjcontrib').fastjet.contrib"}),
    _s("fastjet", "root", "jets as a TTree: pt, eta, phi, m, nconst and columns of the program's",
       {"cpp": "sphere::snn::JetTree", "python": "snn.jets_to_root(path, events)"}),
    _s("root", "uproot", "the same files, written by one and read by the other", {"python": "uproot.open(path)"}, True),
    _s("uproot", "awkward", "trees as jagged arrays", {"python": "uproot.open(path)[tree].arrays()"}),
    _s("awkward", "fastjet", "jagged events clustered at once", {"python": "fastjet.ClusterSequence(events, jetdef)"}),
    _s("root", "torch", "tree branches as one tensor",
       {"cpp": "sphere::snn::tensor(tree, branches)", "python": "snn.root_to_torch(path, tree, branches)"}),
    _s("root", "tensorflow", "tree branches as one tensor",
       {"cpp": "sphere::snn::tf_tensor(values, shape)", "python": "snn.root_to_tf(path, tree, branches)"}),
    _s("root", "jax", "tree branches as one array", {"python": "snn.root_to_jax(path, tree, branches)"}),
    _s("root", "pyhf", "histograms as a pyhf model: CLs and upper limits",
       {"cpp": "sphere::snn::cls(signal, background, observed) (gateway)", "python": "snn.hist_to_pyhf(s, b, n)",
        "julia": "SphereSNN.pyhf(s, b, db, n)"}),
    _s("torch", "tensorflow", "tensors shared without a copy (DLPack)", {"python": "snn.tensor(x, 'tensorflow')"}, True),
    _s("torch", "jax", "tensors shared without a copy (DLPack)", {"python": "snn.tensor(x, 'jax')"}, True),
    _s("tensorflow", "jax", "tensors shared without a copy (DLPack)", {"python": "snn.tensor(x, 'jax')"}, True),
    _s("fastjet", "torch", "particles and jets as a tensor (px, py, pz, E)",
       {"cpp": "sphere::snn::tensor(jets)", "python": "snn.tensor(snn.momenta(jets), 'torch')"}),
    _s("vector", "genvector", "the same four-vector algebra", {"python": "vector.obj(...) and ROOT.Math.PxPyPzEVector"}, True),
    _s("iminuit", "minuit2", "the same minimizer, from Python and from ROOT", {"python": "iminuit.Minuit and ROOT.Minuit2"}, True),
    _s("geant4", "tgeo", "a Geant4 geometry as ROOT's, through GDML",
       {"cpp": "G4GDMLParser::Write + TGeoManager::Import", "python": "snn.g4_to_tgeo(world)"}),
    _s("hepmc3", "geant4", "an event's particles as Geant4's primaries", {"cpp": "sphere::snn::HepMC3Primaries"}),
    _s("pythia8", "hepmc3", "a generated event as HepMC3", {"cpp": "sphere::snn::hepmc3(pythia, event)"}),
    _s("lhapdf", "pythia8", "a PDF set inside Pythia", {"cpp": "pythia.readString(\"PDF:pSet = LHAPDF6:<set>\")"}),
    _s("hepmc3", "rivet", "events through Rivet's analyses", {"cpp": "Rivet::AnalysisHandler", "tool": "rivet -a <analysis> events.hepmc"}),
    _s("rivet", "yoda", "an analysis's histograms", {"cpp": "AnalysisHandler::writeData(file.yoda)"}),
    _s("yoda", "root", "YODA's histograms as ROOT's", {"tool": "yoda2root"}),
    _s("hepmc3", "delphes", "events through a detector's simulation", {"tool": "DelphesHepMC3 card.tcl out.root events.hepmc"}),
    _s("delphes", "root", "reconstructed objects as a tree", {"tool": "DelphesHepMC3"}),
    _s("madgraph", "pythia8", "hard events showered and hadronized", {"tool": "mg5_aMC, shower = PYTHIA8"}),
    _s("herwig", "hepmc3", "Herwig's events as HepMC3", {"tool": "Herwig run (HepMCFile)"}),
    _s("clhep", "hepmc3", "four-vectors", {"cpp": "sphere::snn::clhep(v), sphere::snn::fourvector(v)"}, True),
    _s("clhep", "fastjet", "four-vectors", {"cpp": "sphere::snn::clhep(j), sphere::snn::pseudojet(v)"}, True),
    _s("clhep", "geant4", "Geant4's units and vectors", {"cpp": "Geant4's own CLHEP"}),
    _s("jetreconstruction", "fastjet", "Julia's jets held to FastJet's", {"julia": "SphereSNN.jets(...; via = :fastjet)"}, True),
    _s("sphere-nn", "torch", "PyTorch and Keras models as portable graphs, run in C++ and Java",
       {"python": "sphere_graph.export(model)", "cpp": "sphere_graph.hpp"}, True),
    _s("sphere-nn", "tensorflow", "TensorFlow models as portable graphs, run in C++ and Java",
       {"python": "sphere_tf.load(saved_model)", "cpp": "sphere_graph.hpp"}, True),
    _s("sphere-nn", "java", "the same graphs run by Java's NeuralGraph", {"java": "NeuralGraph.load(path).run(tensors)"}, True),
    _s("pyg4ometry", "geant4", "geometries written as GDML for Geant4", {"python": "pyg4ometry.gdml.Writer"}),
    _s("sphere-java", "fastjet", "Sphere's Java FastJet held to the C++", {"java": ":fjco validate"}),
    _s("sphere-java", "hepmc3", "Sphere's Java HepMC3 held to the C++", {"java": ":hepmc validate"}),
    _s("sphere-java", "minuit2", "Sphere's Java Minuit2 held to the C++", {"java": ":minuit2 validate"}),
    _s("sphere-java", "clhep", "Sphere's Java CLHEP held to the C++", {"java": ":clhep validate"}),
    _s("sphere-java", "golem95", "Sphere's Java Golem95 held to the Fortran, each answer with its correct digits",
       {"java": ":golem validate, :golem check", "fortran": "use form_factor_4p (linked by Sphere)"}),
    _s("mlink", "root", "every language, either side of WSL, reaches the neurons of a Python", {
        "cpp": "sphere_mlink.hpp", "python": "sphere_mlink", "julia": "SphereMLink.jl", "java": "MlLink", "fortran": "sphere_mlink.f90"}),
]


class SnnError(RuntimeError):
    """A neuron missing, or two installations that cannot share a program."""


# ===========================================================================
# Small tools
# ===========================================================================

def _run(cmd, timeout=30, env=None, cwd=None, both=False):
    """What a program printed (with what it said on stderr when both), or None when it could not run."""
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT if both else subprocess.DEVNULL,
                           stdin=subprocess.DEVNULL, timeout=timeout, env=sandbox_env() if env is None else env, cwd=cwd)
    except (OSError, subprocess.SubprocessError, ValueError):
        return None
    out = p.stdout.decode("utf-8", "replace").strip()
    return out if out else None


def _read(path, limit=1 << 20):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read(limit)
    except OSError:
        return ""


def _vt(v):
    """A version as a tuple to compare: '3.03.01' -> (3, 3, 1)."""
    return tuple(int(x) for x in re.findall(r"\d+", str(v or ""))[:4]) or (0,)


def _norm(p):
    return os.path.normpath(p) if p else p


def _uniq(seq):
    out = []
    for x in seq:
        if x and x not in out:
            out.append(x)
    return out


def _lib_file(libdir, name):
    """The shared library of a name in a folder: libX.so (or a versioned one), or None."""
    if not libdir:
        return None
    for cand in ("lib%s.so" % name, "lib%s.dylib" % name):
        p = os.path.join(libdir, cand)
        if os.path.exists(p):
            return p
    try:
        for f in sorted(os.listdir(libdir)):
            if f.startswith("lib%s.so." % name):
                return os.path.join(libdir, f)
    except OSError:
        pass
    return None


def elf(path):
    """SONAME, NEEDED and RPATH/RUNPATH of an ELF64 little-endian shared library; empty for anything else."""
    out = {"soname": None, "needed": [], "runpath": []}
    try:
        with open(path, "rb") as f:
            head = f.read(64)
            if len(head) < 64 or head[:4] != b"\x7fELF" or head[4] != 2 or head[5] != 1:
                return out
            phoff = struct.unpack_from("<Q", head, 32)[0]
            phentsize, phnum = struct.unpack_from("<HH", head, 54)
            f.seek(phoff)
            ph = f.read(phentsize * phnum)
            loads, dyn = [], None
            for i in range(phnum):
                p_type, _, p_offset, p_vaddr, _, p_filesz, _, _ = struct.unpack_from("<IIQQQQQQ", ph, i * phentsize)
                if p_type == 1:
                    loads.append((p_vaddr, p_offset, p_filesz))
                elif p_type == 2:
                    dyn = (p_offset, p_filesz)
            if dyn is None:
                return out
            f.seek(dyn[0])
            d = f.read(dyn[1])
            entries = [struct.unpack_from("<qQ", d, k) for k in range(0, len(d) - 15, 16)]
            strtab = next((v for t, v in entries if t == 5), None)
            strsz = next((v for t, v in entries if t == 10), 0)
            if strtab is None:
                return out
            off = next((o + strtab - va for va, o, sz in loads if va <= strtab < va + sz), None)
            if off is None:
                return out
            f.seek(off)
            s = f.read(strsz)

            def at(i):
                return s[i:s.index(b"\0", i)].decode("utf-8", "replace")

            for t, v in entries:
                if t == 0:
                    break
                if t == 1:
                    out["needed"].append(at(v))
                elif t == 14:
                    out["soname"] = at(v)
                elif t in (15, 29):
                    out["runpath"] += [x for x in at(v).split(":") if x]
    except (OSError, struct.error, ValueError):
        pass
    return out


def _cxx11(path, limit=64 << 20):
    """1 when a C++ library speaks the new std::string, 0 when the old one (_GLIBCXX_USE_CXX11_ABI=0, as manylinux2014
    wheels do), None when it takes no string to tell. Its symbols say it: a const std::string& is mangled RKSs in the
    old ABI and RKNSt7__cxx1112basic_string in the new. (A library of the old ABI may still name __cxx11 types of the
    standard library elsewhere: that alone tells nothing.)"""
    old = new = 0
    try:
        with open(path, "rb") as f:
            seen, tail = 0, b""
            while seen < limit:
                block = f.read(1 << 20)
                if not block:
                    break
                chunk = tail + block
                old += chunk.count(b"RKSs")
                new += chunk.count(b"RKNSt7__cxx1112basic_string")
                tail, seen = block[-32:], seen + len(block)
    except OSError:
        return None
    if old == new == 0:
        return None
    return 1 if new >= old else 0


SYSTEM_LIBDIRS = ("/lib", "/lib64", "/usr/lib", "/usr/lib64", "/lib/x86_64-linux-gnu", "/usr/lib/x86_64-linux-gnu",
                  "/usr/local/lib")


def _closure(lib, dirs):
    """A library and what it needs from the folders given, the needed first: what to load before it, in order."""
    order, seen = [], set()

    def visit(path):
        real = os.path.realpath(path)
        if real in seen:
            return
        seen.add(real)
        info = elf(path)
        here = os.path.dirname(path)
        search = [d.replace("$ORIGIN", here) for d in info["runpath"]] + list(dirs)
        for name in info["needed"]:
            for d in search:
                cand = os.path.join(d, name)
                if os.path.exists(cand) and not any(os.path.realpath(d).startswith(s) for s in SYSTEM_LIBDIRS):
                    visit(cand)
                    break
        order.append(path)

    visit(lib)
    return order


def _flags(text):
    """A config script's flags sorted out: -I, -L, -l, -D, -std, the rest."""
    out = {"inc": [], "libdir": [], "libs": [], "defs": [], "std": None, "flags": [], "ldflags": []}
    try:
        words = shlex.split(text or "")
    except ValueError:
        words = (text or "").split()
    for f in words:
        if f.startswith("-I") and len(f) > 2:
            out["inc"].append(_norm(f[2:]))
        elif f.startswith("-L") and len(f) > 2:
            out["libdir"].append(_norm(f[2:]))
        elif f.startswith("-l") and len(f) > 2:
            out["libs"].append(f[2:])
        elif f.startswith("-D") and len(f) > 2:
            out["defs"].append(f[2:])
        elif f.startswith("-std="):
            m = re.match(r"-std=(?:c|gnu)\+\+(\w+)", f)
            if m:
                out["std"] = {"0x": 11, "1y": 14, "1z": 17, "2a": 20, "2b": 23, "2c": 26}.get(m.group(1)) or int(re.sub(r"\D", "", m.group(1)) or 11)
        elif f.startswith("-Wl,-rpath") or f.startswith("-Wl,--rpath") or f.startswith("-O") or f.startswith("-W") \
                or f in ("-g", "-fPIC", "-pipe"):
            continue
        elif f.startswith("-Wl,") or f == "-rdynamic":
            out["ldflags"].append(f)
        else:
            out["flags"].append(f)
    return out


_WSL = []


def _in_wsl():
    if not LINUX:
        return False
    if not _WSL:            # read once: native() asks for every path it is given
        _WSL.append("microsoft" in _read("/proc/version").lower())
    return _WSL[0]


def side():
    """windows, wsl-<distribution>, linux or macos: where this Python runs."""
    if WINDOWS:
        return "windows"
    if sys.platform == "darwin":
        return "macos"
    if _in_wsl():
        d = os.environ.get("WSL_DISTRO_NAME")
        if not d:
            m = re.search(r'^NAME="?([^"\s]+)', _read("/etc/os-release"), re.M)
            d = m.group(1) if m else ""
        return "wsl-" + d if d else "wsl"
    return "linux"


def native(path):
    """A path as this side names it: D:\\x seen from WSL is /mnt/d/x, and the other way round."""
    if not isinstance(path, str) or not path:
        return path
    if WINDOWS:
        m = re.match(r"^/mnt/([a-zA-Z])(?:/(.*))?$", path)
        if m:
            return m.group(1).upper() + ":\\" + (m.group(2) or "").replace("/", "\\")
    elif _in_wsl():
        m = re.match(r"^([a-zA-Z]):[\\/](.*)$", path)
        if m:
            return "/mnt/" + m.group(1).lower() + "/" + m.group(2).replace("\\", "/")
    return path


def wsl_path(path):
    """A Windows path as WSL names it."""
    p = os.path.abspath(path).replace("\\", "/")
    return re.sub(r"^([A-Za-z]):", lambda m: "/mnt/" + m.group(1).lower(), p)


# ===========================================================================
# Where things are
# ===========================================================================

def sphere_root():
    """Sphere's folder (settings.conf, Sphere.jar), when this file is one Sphere carries or wrote."""
    cands = [os.environ.get("SPHERE_HOME"), os.path.join(HERE, "..", "..", ".."),
             os.path.join(HERE, "..", "..", "..", "..", "..", "..")]
    for c in cands:
        if c and os.path.isfile(os.path.join(native(c), "settings.conf")):
            return os.path.normpath(native(c))
    return None


def bridge_folder():
    """Sphere's bridge folder: the one of the Sphere this file belongs to, else $SPHERE_BRIDGE. (A gateway started for
    another Python sets SPHERE_BRIDGE to its own folder: the Sphere's is the network's.) A cortex found in no Sphere
    keeps to the folder it lies in: the network writes nowhere else than in Sphere."""
    r = sphere_root()
    if r:
        return os.path.join(r, "bridge")
    b = os.environ.get("SPHERE_BRIDGE")
    return native(b) if b else os.path.join(HERE, "bridge")


def network_folder():
    """$SPHERE_SNN: the network's folder, one subfolder a side."""
    given = os.environ.get("SPHERE_SNN")
    return native(given) if given else os.path.join(bridge_folder(), "snn")


def folder(for_side=None):
    return os.path.join(network_folder(), for_side or side())


def lib_folder():
    """Where the bridge's libraries are: sphere_snn.hpp, sphere_mlink.hpp, SphereSNN.jl and this file."""
    given = os.environ.get("SPHERE_BRIDGE_LIB")
    if given and os.path.isfile(os.path.join(native(given), "sphere_snn.py")):
        return native(given)
    return HERE


_SETTINGS = {}


def settings_path():
    r = sphere_root()
    return os.path.join(r, "settings.conf") if r else None


def settings_sections():
    """settings.conf as {SECTION: [(KEY, value), ...]} in the file's order (a key may repeat, as PATH does). Read again
    only when the file changed: a value switched in it is what the next question gets."""
    path = settings_path()
    try:
        st = os.stat(path) if path else None
    except OSError:
        st = None
    if st is None:
        return {}
    stamp = (path, st.st_mtime_ns, st.st_size)
    if _SETTINGS.get("stamp") == stamp:
        return _SETTINGS["sections"]
    out, section = {}, "GLOBAL"
    for line in _read(path, 4 << 20).splitlines():
        line = line.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1].strip().upper()
            out.setdefault(section, [])
        elif "=" in line:
            k, v = line.split("=", 1)
            out.setdefault(section, []).append((k.strip().upper(), v.strip()))
    _SETTINGS.update(stamp=stamp, sections=out)
    return out


def settings():
    """settings.conf of Sphere, KEY -> value (the last one wins): every section, and of [SNN] its SNN_ keys (the
    neurons' keys are read by declared())."""
    out = {}
    for section, rows in settings_sections().items():
        for k, v in rows:
            if section != "SNN" or k.startswith("SNN_"):
                out[k] = v
    return out


_VAR = re.compile(r"\$(?:\{([A-Za-z0-9_]+)\}|([A-Za-z0-9_]+))")


def _expand(value, table, depth=0):
    """$NAME and ${NAME} in a settings value, as Sphere expands them: from settings.conf, else the environment."""
    if not value or "$" not in value or depth > 8:
        return value

    def sub(m):
        name = (m.group(1) or m.group(2)).upper()
        if name in table:
            return _expand(table[name], table, depth + 1)
        got = os.environ.get(name)
        if got is None and name == "HOME":
            got = os.path.expanduser("~")
        return got if got is not None else m.group(0)
    return _VAR.sub(sub, value)


# ===========================================================================
# settings.conf [SNN]: the network as the user declares it
# ===========================================================================
#
# The section is the network's only configuration, and nothing else is: no file of the machine is written, no
# variable of the user's set. Each neuron has a key -- FASTJET for the Linux side (WSL's, seen from Windows), WIN_TORCH
# for Windows -- whose value is the installation it is taken from: a path, a tree's name (madgraph, pylhc...), a
# version, or $KEY to follow another key (a ROOT part follows $ROOT). Empty, the neuron is switched off. Sphere writes
# the section from what it finds (declare) and keeps it true as the machine changes, but a line whose value is no
# longer the one Sphere wrote is the user's, and is never rewritten: bridge/snn/declared.json remembers what Sphere
# wrote. The value is a preference, not a cage: a program whose neurons are tied to another tree (Rivet to the FastJet
# it was built with) still gets one that can share it, and says so.

SNN_SETTINGS = ("SNN_WSL", "SNN_PREFER", "SNN_PATHS", "SNN_LEARN", "SNN_HEAL")
# What follows what, when both are in one tree: the section writes these as $KEY.
ANCHORS = dict([(part, "root") for part in ROOT_PARTS] + [("fjcontrib", "fastjet"), ("thepeg", "herwig"), ("clhep", "geant4")])


def snn_key(neuron, for_side):
    """A neuron's key in [SNN]: FASTJET on the Linux side (WSL's, from Windows), WIN_TORCH on Windows."""
    return ("WIN_" if for_side == "windows" else "") + neuron.upper().replace("-", "_")


_KEY_NEURON = {n.upper().replace("-", "_"): n for n in NEURONS}


def _decl_norm(p):
    p = native(os.path.expanduser(str(p).strip().strip('"')))
    p = os.path.normpath(p).rstrip("\\/") or p
    return os.path.normcase(p) if WINDOWS else p


def _inside(child, parent):
    return bool(child and parent) and child != parent and (child.startswith(parent + "/") or child.startswith(parent + "\\"))


def declared(for_side=None, rows=None):
    """[SNN] as a side reads it: {neuron or 'PYTHON': value}, $NAMES expanded; '' is a neuron switched off. rows: the
    section's (KEY, raw value) pairs, when not those of the file."""
    sd = for_side or side()
    win = sd == "windows"
    secs = settings_sections()
    table = {}
    for rs in secs.values():
        for k, v in rs:
            table[k] = v
    snn = list(secs.get("SNN", [])) if rows is None else list(rows)
    for k, v in snn:                   # [SNN]'s own keys win: $ROOT is the network's ROOT
        table[k] = v
    out = {}
    for k, v in snn:
        if k.startswith("SNN_") or win != k.startswith("WIN_"):
            continue
        name = k[4:] if win else k
        n = "PYTHON" if name == "PYTHON" else _KEY_NEURON.get(name)
        if n:
            out[n] = _expand(v, table).strip().strip('"')
    return out


def match_provider(providers, value):
    """The installation a [SNN] value names among a neuron's: a path (the installation, a folder holding it, a file in
    it, or the Python whose package it is), the name of its tree (madgraph, pylhc, root...), or a version (3.4, @3.4)."""
    v = (value or "").strip().strip('"')
    if not v or not providers:
        return None
    if re.search(r"[\\/]", v) or re.match(r"^[A-Za-z]:", v) or v.startswith("~"):
        nv = _decl_norm(v)
        for p in providers:
            if p.get("prefix") and _decl_norm(p["prefix"]) == nv:
                return p
        for p in providers:
            if p.get("python") and _decl_norm(p["python"]) == nv:
                return p
        near = [p for p in providers if p.get("prefix") and (_inside(nv, _decl_norm(p["prefix"])) or _inside(_decl_norm(p["prefix"]), nv))]
        return max(near, key=lambda p: len(p["prefix"])) if near else None
    low = v.lower()
    for p in providers:
        if low in (str(p.get("family_name", "")).lower(), os.path.basename(str(p.get("family", "")).rstrip("/\\")).lower()):
            return p
    ver = low.lstrip("@").lstrip("v")
    for p in providers:
        if ver and re.sub(r"^\D+", "", str(p.get("version", ""))).startswith(ver):
            return p
    return None


def _apply_declared(c, values=None):
    """The connectome as [SNN] has it: a neuron left empty switched off, the installation a key names put first (what
    takes a neuron's first installation takes it) and preferred when neurons are chosen together (c['declared']), a
    value naming nothing found kept aside (c['unmatched']). values: {neuron: value} instead of the file's."""
    d = declared(c.get("side")) if values is None else values
    c = dict(c)
    c["neurons"] = dict(c.get("neurons", {}))
    c["declared"], c["off"], c["unmatched"] = {}, [], {}
    for n, v in d.items():
        if n == "PYTHON":
            hit = [p for p in c.get("pythons", []) if v and _decl_norm(p["python"]) == _decl_norm(v)]
            if hit:
                c["python"] = hit[0]["python"]
            elif v:
                c["unmatched"]["PYTHON"] = v
            continue
        if v == "":
            if n in c["neurons"]:
                c["off"].append(n)
                del c["neurons"][n]
            continue
        info = c["neurons"].get(n)
        p = match_provider(info["providers"] if info else [], v)
        if p is None:
            c["unmatched"][n] = v
            continue
        c["declared"][n] = p["prefix"]
        info = dict(info)
        info["providers"] = [p] + [q for q in info["providers"] if q is not p]
        c["neurons"][n] = info
    if WINDOWS and c.get("remote") and values is None:
        remote = {}
        for sd, r in c["remote"].items():
            off = {k for k, v in declared(sd).items() if v == ""}
            r = dict(r)
            r["neurons"] = {k: v for k, v in r.get("neurons", {}).items() if k not in off}
            r["can"] = {k: v for k, v in r.get("can", {}).items() if k not in off}
            remote[sd] = r
        c["remote"] = remote
    return c


def _declared_paths(for_side=None):
    """The folders [SNN] names on a side that exist: detection looks into them too."""
    out = []
    for n, v in declared(for_side).items():
        if v and (re.search(r"[\\/]", v) or v.startswith("~")):
            p = native(os.path.expanduser(v))
            if os.path.exists(p):
                out.append(p)
    return out


# ===========================================================================
# The network's own processes: a home inside Sphere
# ===========================================================================

def _cache_folder():
    """Where the network works (checks, builds, the index, its training): inside Sphere, never on the machine."""
    return os.path.join(folder(), "work")


def sandbox_home():
    return os.path.join(folder(), "home")


def sandbox_env(base=None):
    """The environment of what the network runs for itself -- probes, checks, the index, its training: the machine's,
    with a home inside Sphere. Whatever a module writes when it is imported or run (Keras's keras.json, Matplotlib's
    font cache, spey's PyPI check, ROOT's and MadGraph's histories, Python's bytecode, Julia's logs and caches) lands
    in bridge/snn/<side>/home; the machine is read, never written. Julia still finds the user's packages: their depot
    comes second, after Sphere's."""
    env = dict(os.environ if base is None else base)
    home = sandbox_home()
    real = os.path.expanduser("~")
    os.makedirs(home, exist_ok=True)
    env["HOME"] = home
    if WINDOWS:
        env["USERPROFILE"] = home
        env["APPDATA"] = os.path.join(home, "AppData", "Roaming")
        env["LOCALAPPDATA"] = os.path.join(home, "AppData", "Local")
    for k, sub in (("XDG_CACHE_HOME", ".cache"), ("XDG_CONFIG_HOME", ".config"), ("XDG_DATA_HOME", ".local/share"),
                   ("XDG_STATE_HOME", ".local/state")):
        env[k] = os.path.join(home, *sub.split("/"))
    env.update(MPLCONFIGDIR=os.path.join(home, ".config", "matplotlib"), KERAS_HOME=os.path.join(home, ".keras"),
               PYTHONPYCACHEPREFIX=os.path.join(home, "pycache"), ROOT_HIST="0", SPEY_CHECKUPDATE="OFF",
               JUPYTER_PLATFORM_DIRS="1", PYTHONIOENCODING="utf-8")
    env.setdefault("MPLBACKEND", "Agg")
    depots = [d for d in (os.environ.get("JULIA_DEPOT_PATH") or os.path.join(real, ".julia")).split(os.pathsep) if d]
    env["JULIA_DEPOT_PATH"] = os.pathsep.join([os.path.join(home, ".julia")] + depots)
    return env


# ===========================================================================
# Detection: what each installation is
# ===========================================================================

CONFIG_SCRIPTS = {"root-config": "root", "geant4-config": "geant4", "fastjet-config": "fastjet", "HepMC3-config": "hepmc3",
                  "lhapdf-config": "lhapdf", "pythia8-config": "pythia8", "clhep-config": "clhep", "yoda-config": "yoda",
                  "rivet-config": "rivet", "Herwig": "herwig", "mg5_aMC": "madgraph"}
SKIP_DIRS = {"src", "source", "sources", "build", "builds", "root-src", "tmp", "temp", "Temp", "Downloads", "Backup",
             "node_modules", "__pycache__", "tests", "test", "doc", "docs", "examples", "Template", "models", "PLUGIN",
             "vendor", "Analyses", "site-packages", "dist-packages", "include", "share", "man", "etc", "pkgs",
             "conda-meta", "proc", "sys", "dev", "mnt", "snap", "Trash", "cache", "Cache", "WorkSpace", "sessions"}


def family_root(prefix):
    """The installation tree a prefix belongs to: a Python environment, MadGraph's HEPTools, or the prefix itself."""
    if not prefix:
        return "?"
    parts = prefix.replace("\\", "/").rstrip("/").split("/")
    low = [p.lower() for p in parts]
    if "site-packages" in low:
        i = low.index("site-packages")
        root = parts[:i - 1] if low[i - 1] == "lib" else parts[:i - 2]
        return "/".join(root) or "/"
    if "HEPTools" in parts:
        return "/".join(parts[:parts.index("HEPTools") + 1])
    return "/".join(parts)


def family_name(root):
    parts = root.replace("\\", "/").rstrip("/").split("/")
    if parts and parts[-1] == "HEPTools" and len(parts) > 1:
        return parts[-2]
    return parts[-1] if parts else root


def _provider(neuron, version, prefix, origin, **kw):
    p = {"neuron": neuron, "version": str(version or "").strip(), "prefix": prefix, "origin": origin,
         "family": family_root(prefix), "inc": [], "libdir": [], "libs": [], "defs": [], "flags": [], "ldflags": [],
         "std": None, "abi": None, "py": [], "module": None, "python": None, "preload": [], "env": {}, "bin": [],
         "cmake": [], "langs": [], "headers": [], "extra": {}}
    p.update(kw)
    p["family_name"] = family_name(p["family"])
    return p


def _probe_root(script):
    def q(*a):
        return _run([script] + list(a)) or ""
    version = q("--version")
    if not version:
        return []
    prefix, incdir, libdir = q("--prefix"), q("--incdir"), q("--libdir")
    cf, lf = _flags(q("--cflags")), _flags(q("--libs"))
    pyver = q("--python-version")
    feats = q("--features").split()
    py = [libdir] if os.path.isfile(os.path.join(libdir, "ROOT", "__init__.py")) else []
    cmake = [d for d in (os.path.join(prefix, "share", "root", "cmake"), os.path.join(prefix, "cmake"),
                         os.path.join(libdir, "cmake")) if os.path.isfile(os.path.join(d, "ROOTConfig.cmake"))]
    langs = ["cpp", "cling"] + (["python"] if py else [])
    base = dict(inc=[incdir], libdir=[libdir], std=cf["std"], flags=[f for f in cf["flags"] if f in ("-pthread", "-m64")],
                abi=1 if "-D_GLIBCXX_USE_CXX11_ABI=0" not in q("--cflags") else 0, langs=langs,
                env={"ROOTSYS": prefix}, bin=[os.path.join(prefix, "bin")], cmake=cmake[:1], py=py, module="ROOT" if py else None)
    root = _provider("root", version, prefix, script, libs=lf["libs"], defs=cf["defs"], ldflags=lf["ldflags"],
                     headers=["TROOT.h"], extra={"features": feats, "python_version": pyver}, **base)
    out = [root]
    for nid, (libs, required, headers) in ROOT_PARTS.items():
        have = [l for l in libs if _lib_file(libdir, l)]
        if len(have) >= required and all(l in have for l in libs[:required]):
            part = dict(base, module=None, py=[])
            out.append(_provider(nid, version, prefix, script, libs=have, headers=headers, extra={"part_of": "root"}, **part))
    return out


def _probe_geant4(script):
    def q(*a):
        return _run([script] + list(a)) or ""
    version, prefix = q("--version"), q("--prefix")
    if not version:
        return []
    cf, lf = _flags(q("--cflags")), _flags(q("--libs"))
    libdir = lf["libdir"][0] if lf["libdir"] else os.path.join(prefix, "lib")
    datasets = {}
    for line in q("--datasets").splitlines():
        parts = line.split()
        if len(parts) >= 3:
            datasets[parts[1]] = parts[2]
    feats = {f: q("--has-feature", f) == "yes" for f in ("gdml", "qt", "multithreaded")}
    core = [l for l in lf["libs"] if l.startswith("G4") and l not in G4_VIS]
    preload = [p for p in (_lib_file(libdir, l) for l in reversed(core)) if p]
    # GDML's inline code calls Xerces-C, which geant4-config leaves to libG4gdml: a program needs it named itself.
    gdml = _lib_file(libdir, "G4gdml")
    for need in (elf(gdml)["needed"] if gdml else []):
        if not need.startswith("libG4") and not re.match(r"lib(stdc\+\+|m|c|gcc_s|pthread|dl|rt)\.so", need):
            lf["libs"].append(":" + need)
    cmake = [d for d in (os.path.join(libdir, "cmake", "Geant4"), os.path.join(prefix, "lib64", "cmake", "Geant4"))
             if os.path.isfile(os.path.join(d, "Geant4Config.cmake"))]
    g4 = _provider("geant4", version, prefix, script, inc=cf["inc"], libdir=[libdir], libs=lf["libs"], defs=cf["defs"],
                   std=cf["std"], flags=[f for f in cf["flags"] if f in ("-pthread", "-m64")], env=datasets,
                   preload=preload, bin=[os.path.join(prefix, "bin")], cmake=cmake[:1], langs=["cpp", "cling"],
                   abi=_cxx11(_lib_file(libdir, "G4global") or ""), headers=["G4Version.hh"],
                   extra={"features": feats, "datasets": sorted(datasets)})
    out = [g4]
    clhep = _lib_file(libdir, "G4clhep")
    if clhep:
        inc = next((d for d in cf["inc"] if os.path.isdir(os.path.join(d, "CLHEP"))), cf["inc"][0] if cf["inc"] else "")
        m = re.search(r'"(\d+\.\d+\.\d+(?:\.\d+)?)"', _read(os.path.join(inc, "CLHEP", "ClhepVersion.h")))
        out.append(_provider("clhep", m.group(1) if m else "Geant4 %s's own" % version, prefix, script, family=g4["family"], inc=[inc],
                             libdir=[libdir], libs=["G4clhep"], preload=[clhep], std=cf["std"], langs=["cpp", "cling"],
                             headers=["CLHEP/Vector/LorentzVector.h"], extra={"builtin": "geant4"}))
    return out


def _probe_fastjet(script):
    version = _run([script, "--version"])
    prefix = os.path.dirname(os.path.dirname(os.path.abspath(script)))
    if not os.path.isfile(os.path.join(prefix, "include", "fastjet", "PseudoJet.hh")):
        said = _run([script, "--prefix"])
        if not said or not os.path.isfile(os.path.join(said, "include", "fastjet", "PseudoJet.hh")):
            return []
        prefix = said
    inc = os.path.join(prefix, "include")
    libdir = next((d for d in (os.path.join(prefix, "lib64"), os.path.join(prefix, "lib")) if _lib_file(d, "fastjet")), None)
    if not libdir:
        return []
    m = re.search(r'FASTJET_VERSION\s+"([^"]+)"', _read(os.path.join(inc, "fastjet", "config_auto.h")))
    version = (m.group(1) if m else version) or "?"
    libs = [l for l in ("fastjettools", "fastjetplugins", "siscone_spherical", "siscone", "fastjet") if _lib_file(libdir, l)]
    main = _lib_file(libdir, "fastjet")
    wheel = "site-packages" in prefix.replace("\\", "/")
    p = _provider("fastjet", version, prefix, script, inc=[inc], libdir=[libdir], libs=libs, abi=_cxx11(main),
                  preload=[_lib_file(libdir, l) for l in reversed(libs)], langs=["cpp", "cling"] + (["python"] if wheel else []),
                  module="fastjet" if wheel else None, py=[os.path.dirname(prefix)] if wheel else [],
                  headers=["fastjet/ClusterSequence.hh"], extra={"soname": elf(main)["soname"], "wheel": wheel})
    out = [p]
    contrib = _lib_file(libdir, "fastjetcontribfragile")
    cdir = os.path.join(inc, "fastjet", "contrib")
    if contrib and os.path.isdir(cdir):
        names = sorted({f[:-3] for f in os.listdir(cdir) if f.endswith(".hh")})
        out.append(_provider("fjcontrib", "%d tools" % len(names), prefix, script, family=p["family"], inc=[inc],
                             libdir=[libdir], libs=["fastjetcontribfragile"], preload=[contrib], abi=p["abi"],
                             langs=["cpp", "cling"], headers=["fastjet/contrib/SoftDrop.hh"],
                             extra={"tools": names, "fastjet": version}))
    return out


def _probe_hepmc3(prefix, origin):
    m = re.search(r'HEPMC3_VERSION\s+"([\d.]+)"', _read(os.path.join(prefix, "include", "HepMC3", "Version.h")))
    if not m:
        return []
    version = ".".join(str(int(x)) for x in m.group(1).split("."))
    libdir = next((d for d in (os.path.join(prefix, "lib64"), os.path.join(prefix, "lib")) if _lib_file(d, "HepMC3")), None)
    if not libdir:
        return []
    main = _lib_file(libdir, "HepMC3")
    libs = ["HepMC3"] + (["HepMC3search"] if _lib_file(libdir, "HepMC3search") else [])
    cmake = [d for d in (os.path.join(prefix, "share", "HepMC3", "cmake"), os.path.join(libdir, "cmake", "HepMC3"))
             if os.path.isdir(d)]
    return [_provider("hepmc3", version, prefix, origin, inc=[os.path.join(prefix, "include")], libdir=[libdir], libs=libs,
                      preload=[main] + ([_lib_file(libdir, "HepMC3search")] if "HepMC3search" in libs else []),
                      abi=_cxx11(main), cmake=cmake[:1], langs=["cpp", "cling"], headers=["HepMC3/GenEvent.h"],
                      bin=[os.path.join(prefix, "bin")] if os.path.isfile(os.path.join(prefix, "bin", "HepMC3-config")) else [],
                      extra={"soname": elf(main)["soname"], "rootIO": bool(_lib_file(libdir, "HepMC3rootIO"))})]


def _python_dirs(prefix, name):
    """prefix/lib*/python3.x/site-packages folders holding a module."""
    out = []
    for lib in ("lib", "lib64"):
        base = os.path.join(prefix, lib)
        try:
            for d in sorted(os.listdir(base)):
                if d.startswith("python3"):
                    sp = os.path.join(base, d, "site-packages")
                    try:
                        if any(f == name or f.startswith(name + ".") or f.startswith(name + "-") for f in os.listdir(sp)):
                            out.append(sp)
                    except OSError:
                        pass
        except OSError:
            pass
    return out


def _probe_lhapdf(script):
    def q(*a):
        return _run([script] + list(a)) or ""
    version, prefix = q("--version"), q("--prefix")
    if not version or not prefix:
        return []
    prefix = _norm(prefix)
    incdir, libdir, datadir = _norm(q("--incdir")), _norm(q("--libdir")), _norm(q("--datadir"))
    main = _lib_file(libdir, "LHAPDF")
    if not main:
        return []
    sets = []
    try:
        sets = sorted(d for d in os.listdir(datadir) if os.path.isfile(os.path.join(datadir, d, d + ".info")))
    except OSError:
        pass
    py = _python_dirs(prefix, "lhapdf")
    return [_provider("lhapdf", version, prefix, script, inc=[incdir], libdir=[libdir], libs=["LHAPDF"], preload=[main],
                      env={"LHAPDF_DATA_PATH": datadir}, py=py, module="lhapdf" if py else None, abi=_cxx11(main),
                      langs=["cpp", "cling"] + (["python"] if py else []), bin=[os.path.join(prefix, "bin")],
                      headers=["LHAPDF/LHAPDF.h"], extra={"sets": sets})]


def _probe_pythia8(script):
    cx, lb = _flags(_run([script, "--cxxflags"]) or ""), _flags(_run([script, "--libs"]) or "")
    inc = next((d for d in cx["inc"] if os.path.isfile(os.path.join(d, "Pythia8", "Pythia.h"))), None)
    if not inc:
        cand = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(script))), "include")
        inc = cand if os.path.isfile(os.path.join(cand, "Pythia8", "Pythia.h")) else None
    if not inc:
        return []
    prefix = os.path.dirname(inc)
    m = re.search(r"define\s+PYTHIA_VERSION\s+([\d.]+)", _read(os.path.join(inc, "Pythia8", "Pythia.h")))
    libdir = next((d for d in lb["libdir"] + [os.path.join(prefix, "lib")] if _lib_file(d, "pythia8")), None)
    if not libdir:
        return []
    xml = next((d for d in (os.path.join(prefix, "share", "Pythia8", "xmldoc"), os.path.join(prefix, "xmldoc"))
                if os.path.isdir(d)), "")
    others = [d for d in cx["inc"] if d != inc]
    extra_dirs = [d for d in lb["libdir"] if d != libdir]
    main = _lib_file(libdir, "pythia8")
    return [_provider("pythia8", m.group(1) if m else "?", prefix, script, inc=[inc] + others, libdir=[libdir] + extra_dirs,
                      libs=["pythia8"] + [l for l in lb["libs"] if l == "z"], defs=[d for d in cx["defs"] if d == "GZIP"],
                      env={"PYTHIA8DATA": xml} if xml else {}, preload=[main], abi=_cxx11(main), langs=["cpp", "cling"],
                      headers=["Pythia8/Pythia.h"],
                      extra={"lhapdf6_plugin": bool(_lib_file(libdir, "pythia8lhapdf6"))})]


def _probe_simple(neuron, script, libname=None, pymod=None, headers=()):
    """YODA, Rivet, ThePEG, Herwig, CLHEP: a config script with --version, --prefix and flags."""
    def q(*a):
        return (_run([script] + list(a)) or "").replace('"', "")
    version = q("--version")
    prefix = _norm(q("--prefix")) or os.path.dirname(os.path.dirname(os.path.abspath(script)))
    if not version:
        return []
    version = re.sub(r"^[A-Za-z]+\s+", "", version.splitlines()[0])
    cf = _flags(q("--cppflags") or q("--cflags") or q("--cxxflags") or q("--include"))
    lf = _flags(q("--libs") or q("--ldflags"))
    libdir = lf["libdir"] or [os.path.join(prefix, "lib")]
    main = _lib_file(libdir[0], libname) if libname else None
    py = _python_dirs(prefix, pymod) if pymod else []
    langs = (["cpp", "cling"] if main else ["tool"]) + (["python"] if py else [])
    return [_provider(neuron, version, prefix, script, inc=_uniq(cf["inc"]) or [os.path.join(prefix, "include")],
                      libdir=_uniq(libdir), libs=_uniq(lf["libs"]) or ([libname] if libname else []), defs=cf["defs"],
                      std=cf["std"], preload=[main] if main else [], py=py, module=pymod if py else None, abi=_cxx11(main) if main else None,
                      bin=[os.path.join(prefix, "bin")], langs=langs, headers=list(headers))]


def _probe_herwig(exe):
    """Herwig and the ThePEG under it: their versions are what 'Herwig --version' says (herwig-config has none)."""
    said = _run([exe, "--version"], timeout=60, both=True) or ""
    prefix = os.path.dirname(os.path.dirname(os.path.abspath(exe)))
    out = []
    for neuron, word in (("herwig", "Herwig"), ("thepeg", "ThePEG")):
        m = re.search(r"\b%s\s+([\d.]+)" % word, said)
        if m:
            out.append(_provider(neuron, m.group(1), prefix, exe, bin=[os.path.join(prefix, "bin")], langs=["tool"],
                                 extra={"exe": exe} if neuron == "herwig" else {}))
    return out


def _probe_madgraph(script):
    prefix = os.path.dirname(os.path.dirname(os.path.abspath(script)))
    m = re.search(r"version\s*=\s*([\w.]+)", _read(os.path.join(prefix, "VERSION")))
    return [_provider("madgraph", m.group(1) if m else "?", prefix, script, bin=[os.path.join(prefix, "bin")], langs=["tool"])]


def _probe_delphes(d):
    m = re.search(r"([\d.]+)", _read(os.path.join(d, "VERSION")))
    libs = ["Delphes"]
    return [_provider("delphes", m.group(1) if m else "?", d, os.path.join(d, "DelphesHepMC3"),
                      inc=[d, os.path.join(d, "external")], libdir=[d], libs=libs, langs=["cpp", "tool"], bin=[d],
                      headers=["classes/DelphesClasses.h"],
                      extra={"own_fastjet": True, "without_fastjet": bool(_lib_file(d, "DelphesNoFastJet"))})]


def _probe_vecgeom(prefix):
    cm = next((os.path.join(prefix, l, "cmake", "VecGeom") for l in ("lib", "lib64")
               if os.path.isfile(os.path.join(prefix, l, "cmake", "VecGeom", "VecGeomConfig.cmake"))), None)
    if not cm:
        return []
    m = re.search(r'PACKAGE_VERSION\s+"([^"]+)"', _read(os.path.join(cm, "VecGeomConfigVersion.cmake")))
    libdir = os.path.dirname(os.path.dirname(cm))
    vc = os.path.join(libdir, "cmake", "VecCore")
    return [_provider("vecgeom", m.group(1) if m else "?", prefix, cm, inc=[os.path.join(prefix, "include")], libdir=[libdir],
                      libs=[l for l in ("vgdml", "vecgeom") if _lib_file(libdir, l)], cmake=[cm] + ([vc] if os.path.isdir(vc) else []),
                      langs=["cpp"], headers=["VecGeom/base/Global.h"])]


PY_PROBE = r"""
import json, os, sys, importlib.util
try:
    from importlib import metadata as md
except ImportError:
    md = None
want = json.loads(sys.argv[1])
out = {"python": sys.executable, "version": "%d.%d.%d" % sys.version_info[:3], "prefix": sys.prefix,
       "base": getattr(sys, "base_prefix", sys.prefix), "packages": {}}
for neuron, dist, mod in want:
    try:
        spec = importlib.util.find_spec(mod)
    except Exception:
        spec = None
    if spec is None:
        continue
    v = None
    if md is not None:
        try:
            v = md.version(dist)
        except Exception:
            v = None
    loc = spec.origin if spec.origin not in (None, "namespace") else None
    if loc is None and spec.submodule_search_locations:
        loc = list(spec.submodule_search_locations)[0]
    path = os.path.dirname(loc) if loc and os.path.basename(loc).startswith("__init__.") else loc
    out["packages"][neuron + "|" + mod] = {"neuron": neuron, "version": v, "module": mod, "path": path}
print(json.dumps(out))
"""


def _probe_python(exe):
    """What one Python holds, without importing any of it -- and without the network's import finder, which would
    answer for what the Python only reaches through the network."""
    said = _run([exe, "-I", "-c", PY_PROBE, json.dumps(PY_PACKAGES)], timeout=60, env=sandbox_env(dict(os.environ, SPHERE_SNN_IMPORT="0")))
    if not said:
        return None
    try:
        info = json.loads(said.splitlines()[-1])
    except ValueError:
        return None
    provs = []
    for key, pk in info["packages"].items():
        n, path, v = pk["neuron"], pk["path"] or "", pk["version"] or "?"
        if n == "fastjet":
            script = os.path.join(path, "bin", "fastjet-config")
            got = _probe_fastjet(script) if os.path.isfile(script) else []
            for p in got:
                p["python"] = info["python"]
                if p["neuron"] == "fastjet":
                    p.update(module="fastjet", langs=_uniq(p["langs"] + ["python"]), version=p["version"] or v)
            if got:
                provs += got
                continue
        p = _provider(n, v, path, info["python"], module=pk["module"], python=info["python"], langs=["python"])
        if n == "hepmc3":
            p["version"] = "pyhepmc " + v
        if n == "torch":
            inc, lib = os.path.join(path, "include"), os.path.join(path, "lib")
            if os.path.isdir(os.path.join(inc, "torch")) and _lib_file(lib, "torch"):
                c10 = _lib_file(lib, "c10")
                abi = _cxx11(c10) if c10 else None
                p.update(inc=[inc, os.path.join(inc, "torch", "csrc", "api", "include")], libdir=[lib],
                         libs=["torch", "torch_cpu", "c10"], std=17, abi=abi,
                         defs=["_GLIBCXX_USE_CXX11_ABI=%d" % abi] if abi is not None and not WINDOWS else [],
                         cmake=[os.path.join(path, "share", "cmake", "Torch")], langs=["python", "cpp"])
        if n == "tensorflow":
            inc = os.path.join(path, "include")
            cc = os.path.join(path, "libtensorflow_cc.so.2")
            fw = os.path.join(path, "libtensorflow_framework.so.2")
            if os.path.isfile(os.path.join(inc, "tensorflow", "c", "c_api.h")) and os.path.isfile(cc) and os.path.isfile(fw):
                p.update(inc=[inc], libdir=[path], libs=[":libtensorflow_cc.so.2", ":libtensorflow_framework.so.2"],
                         std=17, abi=1, langs=["python", "cpp"])
        provs.append(p)
    info["providers"] = provs
    return info


def _python_candidates(cfg, venvs):
    """The Pythons worth asking: Sphere's, the active one, every virtual and conda environment found, the system's."""
    out = []
    mine = declared().get("PYTHON")       # [SNN] PYTHON (WIN_PYTHON on Windows): the side's Python
    if mine:
        out.append(native(os.path.expanduser(mine)))
    for key in ("ML_PYTHON", "PYTHON_EXEC", "SNN_PYTHON"):
        v = cfg.get(key)
        if v:
            out.append(native(v))
    for env in ("VIRTUAL_ENV", "CONDA_PREFIX"):
        if os.environ.get(env):
            venvs = [os.environ[env]] + list(venvs)
    for v in venvs:
        for exe in (("Scripts", "python.exe"), ("python.exe",)) if WINDOWS else (("bin", "python3"), ("bin", "python")):
            p = os.path.join(v, *exe)
            if os.path.isfile(p):
                out.append(p)
                break
    if not WINDOWS:
        out += [p for p in ("/usr/bin/python3", "/usr/local/bin/python3") if os.path.isfile(p)]
    elif shutil.which("python"):
        out.append(shutil.which("python"))
    seen, keep = set(), []
    for p in out:
        if not WINDOWS and (p.lower().endswith(".exe") or p.startswith("/mnt/")):
            continue            # Windows' own, reached through WSL's interop: the Windows side reports it
        if os.path.isfile(p) and os.path.normcase(os.path.abspath(p)) not in seen:
            seen.add(os.path.normcase(os.path.abspath(p)))
            keep.append(os.path.abspath(p))
    return keep


def _probe_julia(cfg):
    exe = native(cfg.get("JULIA_DIR") or "") or shutil.which("julia") or ""
    if exe and os.path.isdir(exe):
        exe = os.path.join(exe, "bin", "julia.exe" if WINDOWS else "julia")
    if exe and not WINDOWS and (exe.lower().endswith(".exe") or exe.startswith("/mnt/")):
        exe = shutil.which("julia") or ""       # Windows' Julia belongs to the Windows side
    if not exe or not os.path.isfile(exe):
        cand = os.path.join(os.path.expanduser("~"), ".juliaup", "bin", "julia")
        exe = cand if os.path.isfile(cand) else ""
    if not exe:
        return []
    said = _run([exe, "--startup-file=no", "--version"], timeout=60) or ""
    m = re.search(r"(\d+\.\d+\.\d+)", said)
    if not m:
        return []
    version = m.group(1)
    depot = (os.environ.get("JULIA_DEPOT_PATH") or "").split(os.pathsep)[0] or os.path.join(os.path.expanduser("~"), ".julia")
    mm = ".".join(version.split(".")[:2])
    manifest = os.path.join(depot, "environments", "v" + mm, "Manifest.toml")
    project = _read(os.path.join(depot, "environments", "v" + mm, "Project.toml"))
    out = [_provider("julia", version, os.path.dirname(os.path.dirname(exe)), exe, langs=["julia"],
                     extra={"exe": exe, "depot": depot, "environment": os.path.dirname(manifest)})]
    text, current = _read(manifest), None
    for line in text.splitlines():
        h = re.match(r"^\[\[deps\.([\w.]+)\]\]", line)
        if h:
            current = h.group(1)
            continue
        v = re.match(r'^version\s*=\s*"([^"]+)"', line)
        if v and current in JULIA_PACKAGES:
            direct = re.search(r"^%s\s*=" % re.escape(current), project, re.M) is not None
            out.append(_provider(JULIA_PACKAGES[current], v.group(1), depot, manifest, family=depot, langs=["julia"],
                                 module=current, extra={"in_project": direct}))
            current = None
    return out


def _probe_java(cfg):
    """The newest Java of this side that runs Sphere's translations (22 or later: they call the C library through
    the FFM API), with its javac."""
    import glob
    cands = [native(cfg.get(k) or "") for k in ("JAVA_ALTER", "JAVA_EXEC")]
    if os.environ.get("JAVA_HOME"):
        cands.append(os.path.join(os.environ["JAVA_HOME"], "bin", "java.exe" if WINDOWS else "java"))
    if not WINDOWS:
        home = os.path.expanduser("~")
        for pattern in ("Java*/*/bin/java", "*jdk*/bin/java", "*/jdk/bin/java"):
            cands += sorted(glob.glob(os.path.join(home, pattern)))
        cands += sorted(glob.glob("/usr/lib/jvm/*/bin/java")) + sorted(glob.glob("/opt/*jdk*/bin/java"))
    if shutil.which("java"):
        cands.append(shutil.which("java"))
    best = None
    for exe in _uniq(cands):
        if not exe or not os.path.isfile(exe) or (not WINDOWS and (exe.lower().endswith(".exe") or exe.startswith("/mnt/"))):
            continue
        said = _run([exe, "-version"], timeout=60, both=True) or ""
        m = re.search(r'version "(\d+)(?:\.(\d+))?(?:\.(\d+))?', said)
        if not m:
            continue
        major = int(m.group(1))
        javac = os.path.join(os.path.dirname(exe), "javac.exe" if WINDOWS else "javac")
        if best is None or major > best[0]:
            best = (major, exe, javac if os.path.isfile(javac) else None, ".".join(x for x in m.groups() if x))
    if best is None or best[0] < 22:
        return []
    major, exe, javac, version = best
    return [_provider("java", version, os.path.dirname(os.path.dirname(exe)), exe, langs=["java"],
                      extra={"exe": exe, "javac": javac, "major": major})]


def _probe_sphere():
    """Sphere's Java translations, its graphs and its gateway: what the bridge's libraries and Sphere.jar carry."""
    out = []
    root = sphere_root()
    lib = lib_folder()
    if root and os.path.isfile(os.path.join(root, "Sphere.jar")):
        stamp = time.strftime("%Y-%m-%d", time.localtime(os.path.getmtime(os.path.join(root, "Sphere.jar"))))
        out.append(_provider("sphere-java", "Sphere.jar of " + stamp, root, os.path.join(root, "Sphere.jar"), langs=["java"],
                             extra={"modules": {"fastjet": ":fjet", "fjcontrib": ":fjco", "hepmc3": ":hepmc", "clhep": ":clhep",
                                                "minuit2": ":minuit2", "minuit": ":minuit", "root": ":rootlab",
                                                "golem95": ":golem"}}))
        # Golem95: Sphere's Java port is always there; the Fortran library is, once Sphere has built it for a compiler
        out.append(_provider("golem95", "1.3.4 (Sphere's Java port)", root, os.path.join(root, "Sphere.jar"), langs=["java"],
                             extra={"command": ":golem", "oneloop": "3.6.1"}))
        build = os.path.join(root, "config", "bridge", "build")
        for d in (sorted(os.listdir(build)) if os.path.isdir(build) else []):
            lib_a = os.path.join(build, d, "lib", "libgolem95.a")
            inc = os.path.join(build, d, "include", "golem95")
            if d.startswith("golem95-") and os.path.isfile(lib_a) and os.path.isfile(os.path.join(inc, "matrice_s.mod")):
                out.append(_provider("golem95", "1.3.4", os.path.join(build, d), lib_a, inc=[inc],
                                     libdir=[os.path.dirname(lib_a)], libs=["golem95"], langs=["fortran"],
                                     extra={"built_by": "sphere", "command": ":golem native"}))
                break
    if os.path.isfile(os.path.join(lib, "sphere_graph.hpp")):
        out.append(_provider("sphere-nn", "Sphere", lib, os.path.join(lib, "sphere_graph.hpp"), inc=[lib],
                             langs=["cpp", "python", "java"], module="sphere_graph", py=[lib], headers=["sphere_graph.hpp"]))
    if os.path.isfile(os.path.join(lib, "sphere_mlink.py")):
        mlink = os.environ.get("SPHERE_MLINK") or os.path.join(bridge_folder(), "mlink")
        out.append(_provider("mlink", "Sphere", lib, os.path.join(lib, "sphere_mlink.py"), inc=[lib], py=[lib],
                             module="sphere_mlink", langs=["cpp", "python", "julia", "java", "fortran"],
                             headers=["sphere_mlink.hpp"], extra={"folder": native(mlink)}))
    return out


def _scan(roots):
    """Config scripts, Python environments, HepMC3, Delphes and VecGeom prefixes below the roots (path, depth)."""
    found = {"configs": [], "venvs": [], "hepmc3": [], "delphes": [], "vecgeom": []}
    seen = set()
    stack = [(r, d) for r, d in roots if r and os.path.isdir(r)]
    while stack:
        d, depth = stack.pop()
        try:
            real = os.path.realpath(d)
        except OSError:
            continue
        if real in seen:
            continue
        seen.add(real)
        try:
            entries = list(os.scandir(d))
        except OSError:
            continue
        names = {e.name for e in entries}
        if "pyvenv.cfg" in names or "conda-meta" in names:
            found["venvs"].append(d)
        if "DelphesHepMC3" in names and "libDelphes.so" in names:
            found["delphes"].append(d)
        if "bin" in names:
            b = os.path.join(d, "bin")
            for script, neuron in CONFIG_SCRIPTS.items():
                p = os.path.join(b, script)
                if os.path.isfile(p) and os.access(p, os.X_OK):
                    found["configs"].append((neuron, p))
        if "include" in names and os.path.isfile(os.path.join(d, "include", "HepMC3", "Version.h")):
            found["hepmc3"].append(d)
        if ("lib" in names or "lib64" in names) and any(
                os.path.isfile(os.path.join(d, l, "cmake", "VecGeom", "VecGeomConfig.cmake")) for l in ("lib", "lib64")):
            found["vecgeom"].append(d)
        if "envs" in names and "conda-meta" in names:
            stack.append((os.path.join(d, "envs"), 1))
        if depth <= 0:
            continue
        for e in entries:
            try:
                if e.is_dir(follow_symlinks=False) and not e.name.startswith(".") and e.name not in SKIP_DIRS \
                        and not e.name.startswith("python3"):
                    stack.append((e.path, depth - 1))
            except OSError:
                continue
    return found


def _roots(cfg):
    home = os.path.expanduser("~")
    roots = [(home, 5), ("/opt", 4), ("/usr/local", 3), ("/usr", 0)]
    for key in ("ROOT_DIR", "GEANT4_DIR", "MG5_DIR", "HERWIG_DIR"):
        v = native(cfg.get(key) or "")
        if v and os.path.isdir(v):
            roots.append((os.path.dirname(v.rstrip("/")), 2))
    for key in ("ROOTSYS", "G4INSTALL", "VIRTUAL_ENV", "CONDA_PREFIX", "FASTJET", "HEPMC3_DIR", "LHAPDF_DIR"):
        v = os.environ.get(key)
        if v and os.path.isdir(v):
            roots.append((v, 1))
    for v in _declared_paths():      # what settings.conf [SNN] names is looked into, wherever it is
        roots.append((v if os.path.isdir(v) else os.path.dirname(v), 3))
    for extra in (cfg.get("SNN_PATHS") or os.environ.get("SPHERE_SNN_PATHS") or "").split(os.pathsep):
        v = native(extra.strip())
        if v and os.path.isdir(v):
            roots.append((v, 5))
    for d in (os.environ.get("PATH") or "").split(os.pathsep):
        if d and os.path.isdir(d) and not d.startswith("/mnt/"):
            roots.append((os.path.dirname(d.rstrip("/")) or "/", 0))
    return roots


def _detect_unix(cfg):
    found = _scan(_roots(cfg))
    provs, stamps = [], {}
    probes = {"root": _probe_root, "geant4": _probe_geant4, "fastjet": _probe_fastjet, "lhapdf": _probe_lhapdf,
              "pythia8": _probe_pythia8, "madgraph": _probe_madgraph,
              "clhep": lambda s: _probe_simple("clhep", s, None, None, ["CLHEP/Vector/LorentzVector.h"]),
              "yoda": lambda s: _probe_simple("yoda", s, "YODA", "yoda", ["YODA/Histo.h"]),
              "rivet": lambda s: _probe_simple("rivet", s, "Rivet", "rivet", ["Rivet/AnalysisHandler.hh"]),
              "herwig": _probe_herwig}
    hepmc_prefixes = list(found["hepmc3"])
    for neuron, script in found["configs"]:
        stamps[script] = os.path.getmtime(script)
        if neuron == "hepmc3":
            pre = _run([script, "--prefix"])
            if pre:
                hepmc_prefixes.append(_norm(pre))
            continue
        try:
            got = probes[neuron](script)
        except Exception as e:      # one odd installation must not hide the others
            got = []
            sys.stderr.write("snn: %s: %s\n" % (script, e))
        if neuron == "clhep":
            for p in got:
                libdir = p["libdir"][0] if p["libdir"] else ""
                main = next((os.path.join(libdir, f) for f in sorted(os.listdir(libdir)) if f.startswith("libCLHEP") and ".so" in f
                             and "-" in f), None) if os.path.isdir(libdir) else None
                if main:
                    p["preload"] = [main]
                    p["langs"] = ["cpp", "cling"]
        provs += got
    for pre in _uniq(os.path.realpath(p) for p in hepmc_prefixes):
        provs += _probe_hepmc3(pre, pre)
    for d in found["delphes"]:
        provs += _probe_delphes(d)
    for d in found["vecgeom"]:
        provs += _probe_vecgeom(d)
    pythons = []
    for exe in _python_candidates(cfg, found["venvs"]):
        info = _probe_python(exe)
        if info:
            stamps[exe] = os.path.getmtime(exe)
            pythons.append(info)
            provs += info.pop("providers")
    return provs, pythons, stamps


def _detect_windows(cfg):
    provs, pythons, stamps = [], [], {}
    venvs = []
    for key in ("ML_PYTHON", "PYTHON_EXEC"):
        v = cfg.get(key)
        if v and os.path.isfile(v):
            venvs.append(os.path.dirname(os.path.dirname(v)))
    for exe in _python_candidates(cfg, venvs):
        info = _probe_python(exe)
        if info:
            stamps[exe] = os.path.getmtime(exe)
            pythons.append(info)
            provs += info.pop("providers")
    return provs, pythons, stamps


def _dedupe(provs):
    """One installation once: the same prefix found twice, or one tree showing the same version from two prefixes
    (MadGraph's HEPTools has its FastJet in HEPTools/ and in HEPTools/fastjet/): the most specific prefix is kept."""
    out, seen = [], set()
    for p in provs:
        key = (p["neuron"], os.path.normcase(os.path.realpath(p["prefix"] or "")) if p["prefix"] else p["origin"], p.get("python"))
        if key in seen:
            continue
        seen.add(key)
        out.append(p)
    best = {}
    for p in out:
        if p.get("python"):
            continue
        key = (p["neuron"], p["version"], p["family"])
        if key not in best or len(p["prefix"] or "") > len(best[key]["prefix"] or ""):
            best[key] = p
    return [p for p in out if p.get("python") or best.get((p["neuron"], p["version"], p["family"])) is p]


def _python_access(provs, pythons):
    """Which Python can import which neuron: its own packages, ROOT's PyROOT and the modules of LHAPDF, YODA and Rivet
    when built for its version, and every C++ neuron through Cling when ROOT is one of them."""
    for py in pythons:
        mm = ".".join(py["version"].split(".")[:2])
        can = {}
        for p in provs:
            if p.get("python") and "python" in p["langs"] and os.path.normcase(p["python"]) == os.path.normcase(py["python"]):
                can.setdefault(p["neuron"], "import " + (p["module"] or p["neuron"]))
        for p in provs:
            if p["neuron"] == "root" and "python" in p["langs"] and p["extra"].get("python_version", "")[:len(mm)] == mm:
                can.setdefault("root", "snn.use('root')")
            if p["neuron"] in ("lhapdf", "yoda", "rivet") and p["py"] and any("python" + mm in d for d in p["py"]):
                can.setdefault(p["neuron"], "snn.use('%s')" % p["neuron"])
        if "root" in can:
            for p in provs:
                if "cling" in p["langs"] and p["neuron"] not in can:
                    can[p["neuron"]] = "snn.cpp('%s')" % p["neuron"]
        py["can"] = can
    return pythons


def _neuron_table(provs):
    table = {}
    for p in provs:
        n = table.setdefault(p["neuron"], {"title": NEURONS.get(p["neuron"], {}).get("title", p["neuron"]),
                                           "group": NEURONS.get(p["neuron"], {}).get("group", "data"), "providers": []})
        n["providers"].append(p)
    for n in table.values():
        n["providers"].sort(key=lambda p: (_vt(p["version"]), len(p["langs"])), reverse=True)
    return table


def _wsl_distros(cfg):
    wanted = [d.strip() for d in (cfg.get("SNN_WSL") or "").split(",") if d.strip()]
    if wanted:
        return wanted
    exe = shutil.which("wsl.exe") or os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32", "wsl.exe")
    try:
        raw = subprocess.run([exe, "-l", "-q"], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    text = raw.decode("utf-16-le", "ignore") if raw[:2] != b"\xef\xbb" and b"\x00" in raw else raw.decode("utf-8", "ignore")
    return [d.strip().strip("\x00") for d in text.splitlines() if d.strip().strip("\x00") and "docker" not in d.lower()]


def _detect_wsl(distro, rescan):
    """The connectome of a WSL distribution, made there by the same file, through wsl.exe."""
    script = wsl_path(os.path.join(HERE, "sphere_snn.py"))
    args = ["wsl.exe", "-d", distro, "--", "env", "SPHERE_SNN=" + wsl_path(network_folder()),
            "SPHERE_BRIDGE=" + wsl_path(bridge_folder()), "python3", script, "detect", "--json", "--local"]
    if rescan:
        args.append("--rescan")
    said = _run(args, timeout=600, env=dict(os.environ))     # wsl.exe as the user starts it (.wslconfig)
    if not said:
        return None
    try:
        return json.loads(said[said.index("{"):])
    except ValueError:
        return None


def detect(rescan=False, local=False, quiet=True, raw=False):
    """The connectome of this side (and, from Windows, of each WSL distribution): read from connectome.json when it is
    still true, made again otherwise -- and then as settings.conf [SNN] has it (raw: as found, every installation)."""
    path = os.path.join(folder(), "connectome.json")
    if not rescan:
        try:
            with open(path, encoding="utf-8") as fh:
                c = json.load(fh)
            if c.get("format") == FORMAT and _still_true(c) and (local or not WINDOWS or "remote" in c):
                return c if raw else _apply_declared(c)
        except (OSError, ValueError):
            pass
    t0 = time.time()
    cfg = settings()
    provs, pythons, stamps = (_detect_windows if WINDOWS else _detect_unix)(cfg)
    provs += _probe_julia(cfg) + _probe_java(cfg) + _probe_sphere()
    provs = _dedupe(provs)
    pythons = _python_access(provs, pythons)
    best = max(pythons, key=lambda p: (len(p.get("can", {})), "root" in p.get("can", {})), default=None)
    c = {"format": FORMAT, "side": side(), "made": time.time(), "seconds": 0.0, "host": os.uname().nodename if hasattr(os, "uname") else os.environ.get("COMPUTERNAME", ""),
         "folder": folder(), "lib": lib_folder(), "python": best["python"] if best else None,
         "pythons": pythons, "neurons": _neuron_table(provs), "stamps": stamps, "prefer": cfg.get("SNN_PREFER", ""),
         "declared_seen": _declared_paths()}
    if WINDOWS and not local:
        c["remote"] = {}
        for d in _wsl_distros(cfg):
            got = _detect_wsl(d, rescan)
            if got:
                c["remote"][got.get("side", "wsl-" + d)] = {"python": got.get("python"), "distro": d,
                                                          "neurons": {k: {"version": v["providers"][0]["version"],
                                                                          "langs": sorted({l for p in v["providers"] for l in p["langs"]})}
                                                                      for k, v in got.get("neurons", {}).items()},
                                                          "can": next((p.get("can", {}) for p in got.get("pythons", [])
                                                                       if p["python"] == got.get("python")), {})}
    c["seconds"] = round(time.time() - t0, 2)
    try:
        os.makedirs(folder(), exist_ok=True)
        _write_text(path, json.dumps(c, indent=1))
        _write_text(os.path.join(folder(), "neurons.tsv"), _neurons_tsv(c))
        _write_text(os.path.join(folder(), "synapses.tsv"), _synapses_tsv())
    except OSError as e:
        if not quiet:
            sys.stderr.write("snn: cannot write %s: %s\n" % (path, e))
    return c if raw else _apply_declared(c)


def _still_true(c):
    if time.time() - c.get("made", 0) > 7 * 86400:
        return False
    seen = set(c.get("declared_seen", []))
    for n, v in declared(c.get("side")).items():
        if n == "PYTHON" or not v or not (re.search(r"[\\/]", v) or v.startswith("~")):
            continue
        path = native(os.path.expanduser(v))
        if path in seen or not os.path.exists(path):
            continue
        info = c.get("neurons", {}).get(n)
        if not info or match_provider(info["providers"], v) is None:
            return False        # an installation [SNN] names that the connectome has not looked at yet
    for path, mtime in c.get("stamps", {}).items():
        try:
            if abs(os.path.getmtime(path) - mtime) > 1:
                return False
        except OSError:
            return False
    return True


def _write_text(path, text):
    part = "%s.%d.part" % (path, os.getpid())
    with open(part, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    for attempt in range(50):
        try:
            os.replace(part, path)
            return
        except OSError:
            if attempt == 49:
                raise
            time.sleep(0.02)


# ===========================================================================
# Choosing installations that can share a program
# ===========================================================================

def _closure_of(names):
    out = []

    def add(n):
        if n in out:
            return
        for d in NEURONS.get(n, {}).get("deps", ()):
            if d != "julia":
                add(d)
        out.append(n)

    for n in names:
        add(n)
    return out


def canonical(name):
    n = ALIASES.get(name, name)
    n = ALIASES.get(n.lower(), n.lower()) if n not in NEURONS else n
    return n


def _family_rank(c, lang):
    """The families a choice prefers -- settings' SNN_PREFER, then for Python and Cling the Python of this process --,
    and every family by how many neurons it holds. A choice takes the preferred family, else the newest installation,
    and only between equal versions the largest tree: a tree that grows (Herwig found beside its FastJet) must not
    take the place of a newer one."""
    count = {}
    for n in c["neurons"].values():
        for p in n["providers"]:
            count[p["family"]] = count.get(p["family"], 0) + 1
    preferred = []
    for name in (c.get("prefer") or os.environ.get("SPHERE_SNN_PREFER") or "").split(","):
        name = name.strip()
        preferred += [f for f in count if name and (family_name(f) == name or f == name)]
    if lang in ("python", "cling"):
        here = family_root(os.path.join(sys.prefix, "lib", "python3", "site-packages"))
        preferred += [f for f in count if f == here]
    return _uniq(preferred), sorted(count, key=lambda f: -count[f])


def resolve(c, names, lang="cpp", pinned=None):
    """{neuron: installation} for one program in a language holding all these neurons; SnnError when one is missing
    or two cannot share it. pinned: what a running process already holds."""
    pinned = dict(pinned or {})
    wanted = _closure_of([canonical(n) for n in names])
    accept = {"cpp": ("cpp",), "cling": ("cling",), "python": ("python", "cling")}[lang]
    cands = {}
    for n in wanted:
        ps = [p for p in c["neurons"].get(n, {}).get("providers", []) if any(l in p["langs"] for l in accept)]
        if lang in ("cpp", "cling") and any(p.get("abi") != 0 for p in ps):
            # a library of the old std::string (a manylinux2014 wheel's) cannot take a string from what the system's
            # compiler builds, Cling included: another installation of it is taken
            ps = [p for p in ps if p.get("abi") != 0]
        if n in pinned:
            ps = [p for p in ps if p["prefix"] == pinned[n]["prefix"]] or [pinned[n]]
        if not ps:
            raise SnnError(_missing(c, n, lang))
        cands[n] = ps
    parent = {n: n for n in wanted}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for a, b in TIES + tuple((n, "root") for n in ROOT_PARTS):
        if a in parent and b in parent:
            parent[find(a)] = find(b)
    groups = {}
    for n in wanted:
        groups.setdefault(find(n), []).append(n)
    preferred, by_size = _family_rank(c, lang)
    trust = memory_trust(lang)
    chosen, notes = {}, []
    for members in groups.values():
        fams = None
        for n in members:
            fs = {p["family"] for p in cands[n]}
            fams = fs if fams is None else fams & fs
        if fams:
            named = c.get("declared", {})

            def key(f):
                # the trees settings.conf [SNN] names come first, then what the network learned works in this language,
                # then SNN_PREFER, the newest, the largest tree
                anchor = next(p for p in cands[members[0]] if p["family"] == f)
                votes = sum(1 for n in members if named.get(n) and any(p["family"] == f and p["prefix"] == named[n] for p in cands[n]))
                return (-votes, -trust(f), preferred.index(f) if f in preferred else len(preferred),
                        tuple(-x for x in _vt(anchor["version"])), by_size.index(f) if f in by_size else len(by_size))
            fam = min(fams, key=key)
            for n in members:
                chosen[n] = next(p for p in cands[n] if p["family"] == fam)
        else:
            held = ", ".join("%s (%s)" % (n, "/".join(sorted({p["family_name"] for p in cands[n]}))) for n in members)
            raise SnnError("no single installation holds %s together: they would bring two copies of one library into "
                           "one program. Run them in two programs (the gateway, snn.hub, joins them) or install them "
                           "from one tree." % held)
    # leanings: Pythia's LHAPDF plugin and the LHAPDF of the program
    for a, b in LEANS:
        if a in chosen and b in chosen and chosen[a]["family"] != chosen[b]["family"]:
            same = [p for p in cands[b] if p["family"] == chosen[a]["family"]]
            if same and b not in pinned:
                chosen[b] = same[0]
    g4 = chosen.get("geant4") or pinned.get("geant4")
    if "geant4" in chosen and "clhep" in pinned and not pinned["clhep"]["extra"].get("builtin"):
        raise SnnError("CLHEP %s is loaded already, and Geant4 carries its own CLHEP: two CLHEPs cannot share a process. "
                       "Take Geant4 first (its CLHEP is then the process's), or another process" % pinned["clhep"]["version"])
    if "clhep" in chosen and g4 is not None:
        mine = [p for p in cands["clhep"] if p["extra"].get("builtin") == "geant4" and p["family"] == g4["family"]]
        if mine:
            chosen["clhep"] = mine[0]
        else:
            notes.append("Geant4 brings its own CLHEP: the other one was left out")
            del chosen["clhep"]
    elif "clhep" in chosen and chosen["clhep"]["extra"].get("builtin") and "clhep" not in pinned:
        own = [p for p in cands["clhep"] if not p["extra"].get("builtin")]
        if own:     # without Geant4, CLHEP's own library rather than the copy Geant4 carries
            chosen["clhep"] = own[0]
    if "delphes" in chosen and "fastjet" in chosen:
        notes.append("Delphes carries a FastJet of its own: libDelphesNoFastJet is linked instead")
    abis = {p["abi"] for p in chosen.values() if p.get("abi") is not None and "cpp" in p["langs"]}
    if lang == "cpp" and len(abis) > 1:
        old = [n for n, p in chosen.items() if p.get("abi") == 0]
        raise SnnError("%s speak%s the old std::string (_GLIBCXX_USE_CXX11_ABI=0) and the rest the new: they cannot be "
                       "linked together" % (", ".join(old), "s" if len(old) == 1 else ""))
    return {n: chosen[n] for n in wanted if n in chosen}, notes


def _missing(c, n, lang):
    if n not in NEURONS:
        return "'%s' is no neuron of the network (%s)" % (n, ", ".join(sorted(NEURONS)))
    have = c["neurons"].get(n)
    if not have and n in c.get("off", []):
        return "%s is switched off in settings.conf ([SNN] %s is empty): write an installation there to switch it on" % (
            NEURONS[n]["title"], snn_key(n, c.get("side")))
    if not have:
        where = [s for s, r in (c.get("remote") or {}).items() if n in r["neurons"]]
        return "%s is not on this side (%s)%s" % (NEURONS[n]["title"], c.get("side"),
                                                  "; it is in " + ", ".join(where) + ": reach it through the gateway (snn.hub)" if where else "")
    langs = sorted({l for p in have["providers"] for l in p["langs"]})
    return "%s cannot be reached from %s here (it can from %s)" % (NEURONS[n]["title"], lang, ", ".join(langs))


def std_of(chosen):
    stds = [p["std"] for p in chosen.values() if p.get("std")]
    if "root" in chosen and chosen["root"].get("std"):
        return chosen["root"]["std"]
    return max(stds + [17])


def _order(chosen):
    """Link order: what needs comes before what is needed."""
    seq = list(chosen)
    rank = {n: i for i, n in enumerate(_closure_of(seq))}
    return sorted(seq, key=lambda n: -rank.get(n, 0))


def flags(c, names, with_rpath=True):
    """cflags, libs, env and std of a C++ program holding these neurons."""
    chosen, notes = resolve(c, names, "cpp")
    std = std_of(chosen)
    lib = c.get("lib") or lib_folder()
    cflags = ["-std=c++%d" % std]
    defs, incs, other = ["SNN_CONFIGURED=1"], [lib], []
    for n in _order(chosen):
        p = chosen[n]
        defs.append("SNN_HAS_%s=1" % re.sub(r"\W", "_", n.upper()))
        defs += p["defs"]
        incs += p["inc"]
        other += p["flags"]
    for n in chosen:
        if n in ROOT_PARTS:
            defs.append("SNN_HAS_ROOT=1")
    if "geant4" in chosen:
        defs.append("SNN_HAS_CLHEP=1")
    cflags += ["-D" + d for d in _uniq(defs)] + ["-I" + d for d in _uniq(incs)] + _uniq(other)
    libdirs, libs, ld = [], [], []
    for n in _order(chosen):
        p = chosen[n]
        libdirs += p["libdir"]
        names_ = p["libs"]
        if n == "delphes" and "fastjet" in chosen and p["extra"].get("without_fastjet"):
            names_ = ["DelphesNoFastJet"]
        libs += names_
        ld += p["ldflags"]
    libflags = ["-L" + d for d in _uniq(libdirs)]
    if with_rpath and _uniq(libdirs) and not WINDOWS:
        libflags.append("-Wl,--disable-new-dtags,-rpath," + ":".join(_uniq(libdirs)))
    libflags += ["-l" + l for l in _uniq(libs)] + _uniq(ld)
    env = {}
    for p in chosen.values():
        env.update(p["env"])
    if not WINDOWS and _uniq(libdirs):
        # A library built with RUNPATH (Rivet's) looks for what it needs in its own path only, not the program's:
        # what a run needs on LD_LIBRARY_PATH, the installations chosen first.
        env["LD_LIBRARY_PATH"] = ":".join(_uniq(libdirs + [os.path.join(p["family"], "lib") for p in chosen.values()
                                                            if os.path.isdir(os.path.join(p["family"], "lib"))]))
    return {"chosen": chosen, "notes": notes, "std": std, "cflags": cflags, "libs": libflags, "env": env}


# ===========================================================================
# What the network writes for every language
# ===========================================================================

def _default_set(c, lang):
    """One installation of every neuron reachable from a language, chosen together: what a shell is given."""
    names = []
    for n in c["neurons"]:
        if NEURONS.get(n, {}).get("group") == "julia":
            continue
        names.append(n)
    keep = {}
    # Geant4 before CLHEP (its own CLHEP is then the one), the generators' tools last
    for n in sorted(names, key=lambda n: (n in ("rivet", "thepeg", "herwig", "delphes"), n != "geant4", n)):
        try:
            chosen, _ = resolve(c, list(keep) + [n], lang, pinned=keep)
            keep.update(chosen)
        except SnnError:
            continue
    return keep


def env_of(c, names=None, light=False):
    """The variables a shell needs to reach the neurons (all of them, or those named), as (name, value, how): how is
    'prepend' for path lists, 'set', 'default' (set when unset) or 'append'. light: what every process Sphere starts
    is given -- names, tools, CMake and pkg-config, the import finder, Julia's module, a bytecode cache inside Sphere
    --, without LD_LIBRARY_PATH, which would change what other programs load: 'snn run' and snn-config give a program
    exactly the rest."""
    lib = c.get("lib") or lib_folder()
    me = c.get("folder") or folder()
    if names:
        chosen, _ = resolve(c, names, "cling" if c["neurons"].get("root") else "cpp")
    else:
        chosen = _default_set(c, "cling" if c["neurons"].get("root") else "cpp")
    out = [("SPHERE_SNN", os.path.dirname(me), "set"), ("SPHERE_BRIDGE", bridge_folder(), "set"), ("SPHERE_BRIDGE_LIB", lib, "set")]
    paths = {"PATH": [os.path.join(me, "bin")], "LD_LIBRARY_PATH": [], "PYTHONPATH": [lib], "ROOT_INCLUDE_PATH": [lib],
             "CMAKE_PREFIX_PATH": [os.path.join(me, "cmake")], "PKG_CONFIG_PATH": [os.path.join(me, "pkgconfig")]}
    data = {}
    for n in _order(chosen)[::-1]:
        p = chosen[n]
        paths["PATH"] += p["bin"]
        paths["LD_LIBRARY_PATH"] += p["libdir"]
        if n in ("root", "lhapdf", "yoda", "rivet") and not light:
            paths["PYTHONPATH"] += p["py"]
        paths["ROOT_INCLUDE_PATH"] += [d for d in p["inc"] if "root" not in n]
        paths["CMAKE_PREFIX_PATH"] += p["cmake"]
        data.update(p["env"])
    if light:
        del paths["LD_LIBRARY_PATH"]
    for k, v in paths.items():
        if WINDOWS and k in ("LD_LIBRARY_PATH", "ROOT_INCLUDE_PATH", "CMAKE_PREFIX_PATH", "PKG_CONFIG_PATH"):
            continue
        out.append((k, os.pathsep.join(_uniq(v)), "prepend"))
    out += [(k, v, "set") for k, v in sorted(data.items())]
    if light:
        out.append(("PYTHONPYCACHEPREFIX", os.path.join(os.path.dirname(me), "pycache"), "default"))
        if c["neurons"].get("julia"):
            out.append(("JULIA_LOAD_PATH", os.path.join(me, "julia"), "append"))
    return out


def render_env(c, shell="sh", names=None, light=False):
    rows = env_of(c, names, light)
    lines = []
    if shell in ("sh", "bash", "zsh", "wsl"):
        lines.append('_snn_prepend() { eval "_snn_was=\\${$1:-}"; case ":$_snn_was:" in *":$2:"*) ;; '
                     '*) if [ -n "$_snn_was" ]; then export "$1=$2:$_snn_was"; else export "$1=$2"; fi ;; esac; }')
        for k, v, how in rows:
            if how == "prepend":
                for one in reversed(v.split(os.pathsep)):
                    lines.append("_snn_prepend %s %s" % (k, shlex.quote(one)))
            elif how == "default":
                lines.append('[ -n "${%s:-}" ] || export %s=%s' % (k, k, shlex.quote(v)))
            elif how == "append":       # JULIA_LOAD_PATH: an empty entry first stands for Julia's own load path
                lines.append('case ":${%s:-}:" in *:%s:*) ;; *) export %s="${%s:-}":%s ;; esac' % (k, shlex.quote(v), k, k, shlex.quote(v)))
            else:
                lines.append("export %s=%s" % (k, shlex.quote(v)))
        lines.append("unset -f _snn_prepend; unset _snn_was")
    elif shell == "cmd":
        lines.append("@echo off")
        for k, v, how in rows:
            lines.append('set "%s=%s%s"' % (k, v, (";%" + k + "%") if how == "prepend" else ""))
    elif shell == "powershell":
        for k, v, how in rows:
            val = v.replace("'", "''")
            lines.append("$env:%s = '%s'%s" % (k, val, (" + ';' + $env:" + k) if how == "prepend" else ""))
    return "\n".join(lines) + "\n"


def _synapses_tsv():
    lines = ["# id\tfrom\tto\twhat\tlanguages"]
    for s in SYNAPSES:
        lines.append("\t".join([s["id"], s["from"], s["to"], s["what"], ",".join(sorted(s["how"]))]))
    return "\n".join(lines) + "\n"


def _neurons_tsv(c):
    lines = ["# id\ttitle\tversion\tside\tlanguages\twhere\tfamily"]
    for n, info in sorted(c["neurons"].items()):
        p = info["providers"][0]
        langs = sorted({l for q in info["providers"] for l in q["langs"]})
        lines.append("\t".join([n, info["title"], p["version"], c["side"], ",".join(langs), p["prefix"] or "", p["family_name"]]))
    for s, r in sorted((c.get("remote") or {}).items()):
        for n, info in sorted(r["neurons"].items()):
            lines.append("\t".join([n, NEURONS.get(n, {}).get("title", n), info["version"], s, ",".join(info["langs"]), "", ""]))
    return "\n".join(lines) + "\n"


def cmake_text(c, names):
    """CMake targets SNN::<neuron> for one program holding these neurons, and SNN::all."""
    f = flags(c, names, with_rpath=False)
    chosen = f["chosen"]
    lib = c.get("lib") or lib_folder()
    out = ["# Written by sphere_snn.py for: %s" % " ".join(names),
           "if(NOT TARGET SNN::bridge)",
           "  add_library(SNN::bridge INTERFACE IMPORTED)",
           '  set_target_properties(SNN::bridge PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "%s" INTERFACE_COMPILE_FEATURES "cxx_std_%d")'
           % (_cm(lib), f["std"]),
           "endif()"]
    for n in _order(chosen):
        p = chosen[n]
        t = "SNN::" + n
        full = []
        for l in p["libs"]:
            if n == "delphes" and "fastjet" in chosen and p["extra"].get("without_fastjet"):
                l = "DelphesNoFastJet"
            if l.startswith(":"):
                cand = next((os.path.join(d, l[1:]) for d in p["libdir"] if os.path.isfile(os.path.join(d, l[1:]))), None)
            else:
                cand = next((x for x in (_lib_file(d, l) for d in p["libdir"]) if x), None)
            full.append(cand or l)
        deps = ["SNN::bridge"] + ["SNN::" + d for d in NEURONS.get(n, {}).get("deps", ()) if d in chosen]
        if n in ROOT_PARTS and "root" in chosen:
            deps.append("SNN::root")
        defs = ["SNN_HAS_%s=1" % re.sub(r"\W", "_", n.upper())] + p["defs"] + (["SNN_HAS_ROOT=1"] if n in ROOT_PARTS else [])
        if n == "geant4":
            defs.append("SNN_HAS_CLHEP=1")
        opts = [o for o in p["flags"] if o in ("-pthread", "-m64")]
        out += ["if(NOT TARGET %s)" % t,
                "  add_library(%s INTERFACE IMPORTED)" % t,
                "  set_target_properties(%s PROPERTIES" % t,
                '    INTERFACE_INCLUDE_DIRECTORIES "%s"' % _cm(";".join(_uniq(p["inc"]))),
                '    INTERFACE_LINK_LIBRARIES "%s"' % _cm(";".join(full + deps + [o for o in p["ldflags"] if o != "-rdynamic"])),
                '    INTERFACE_COMPILE_DEFINITIONS "%s"' % ";".join(_uniq(defs)),
                '    INTERFACE_COMPILE_OPTIONS "%s"' % ";".join(_uniq(opts)) + ")",
                "endif()",
                "set(SphereNN_%s_FOUND TRUE)" % n,
                'set(SphereNN_%s_VERSION "%s")' % (n, p["version"])]
        for k, v in sorted(p["env"].items()):
            out.append('set(SphereNN_ENV_%s "%s")' % (re.sub(r"\W", "_", k), _cm(v)))
    out += ["if(NOT TARGET SNN::all)", "  add_library(SNN::all INTERFACE IMPORTED)", "endif()",
            'set_property(TARGET SNN::all APPEND PROPERTY INTERFACE_LINK_LIBRARIES %s)' % " ".join("SNN::" + n for n in _order(chosen)),
            "set(SphereNN_CXX_STANDARD %d)" % f["std"],
            'set(SphereNN_RUNTIME_ENV "%s")' % _cm(";".join("%s=%s" % kv for kv in sorted(f["env"].items())))]
    for note in f["notes"]:
        out.append('message(STATUS "SphereNN: %s")' % note.replace('"', "'"))
    return "\n".join(out) + "\n"


def _cm(s):
    return str(s).replace("\\", "/").replace('"', '\\"')


CMAKE_CONFIG = r'''# SphereNNConfig.cmake -- the Sphere Neural Network for CMake.
#
#   find_package(SphereNN REQUIRED COMPONENTS hepmc3 fastjet fjcontrib root geant4 torch)
#   target_link_libraries(app PRIVATE SNN::all)          # or SNN::fastjet SNN::root ...
#   set_property(TARGET app PROPERTY CXX_STANDARD ${SphereNN_CXX_STANDARD})
#
# The installations are chosen together, for the components asked, by the cortex (sphere_snn.py), as snn-config does:
# the targets of one find_package can share one program. ${SphereNN_RUNTIME_ENV} lists what the program needs set when
# it runs (Geant4's data, LHAPDF's sets, Pythia's xmldoc); thissnn.sh sets them in a shell.
set(SphereNN_VERSION 1.0)
set(_snn_python "@PYTHON@")
set(_snn_cortex "@CORTEX@")
if(NOT SphereNN_FIND_COMPONENTS)
  set(SphereNN_FIND_COMPONENTS @DEFAULTS@)
endif()
execute_process(COMMAND "${_snn_python}" "${_snn_cortex}" cmake ${SphereNN_FIND_COMPONENTS}
                OUTPUT_VARIABLE _snn_targets ERROR_VARIABLE _snn_error RESULT_VARIABLE _snn_status)
if(NOT _snn_status EQUAL 0)
  set(SphereNN_FOUND FALSE)
  set(SphereNN_NOT_FOUND_MESSAGE "${_snn_error}")
  return()
endif()
set(_snn_file "${CMAKE_CURRENT_BINARY_DIR}/SphereNN_targets.cmake")
file(WRITE "${_snn_file}" "${_snn_targets}")
include("${_snn_file}")
set(SphereNN_FOUND TRUE)
'''

CMAKE_VERSION = r'''set(PACKAGE_VERSION 1.0)
if(PACKAGE_FIND_VERSION VERSION_GREATER PACKAGE_VERSION)
  set(PACKAGE_VERSION_COMPATIBLE FALSE)
else()
  set(PACKAGE_VERSION_COMPATIBLE TRUE)
endif()
'''

SNN_CONFIG = r'''#!/bin/sh
# snn-config -- the flags of a C++ program holding neurons of the Sphere Neural Network together:
#     g++ app.cpp $(snn-config --cflags --libs hepmc3 fastjet fjcontrib root)
#     snn-config --list            the neurons that can be asked for
#     snn-config --env geant4      what the program needs set when it runs
exec "@PYTHON@" "@CORTEX@" config "$@"
'''


JULIA_SHIM = """# SphereSNN, for `using SphereSNN` in what Sphere starts: JULIA_LOAD_PATH names this folder, the module is the one of
# Sphere's bridge library. Never precompiled: nothing is written to Julia's depot.
__precompile__(false)
Base.include(@__MODULE__, raw"%s")
"""


def write(c=None):
    """Writes, in this side's folder inside Sphere -- and nowhere else --, every way in, from what was found and what
    settings.conf [SNN] chose: env.json (what every process Sphere starts is given), thissnn.sh (read by Sphere's own
    terminals), the snn command, snn-config, CMake, pkg-config, snn_load.C, the Julia module."""
    raw = c or detect(raw=True)
    c = _apply_declared(raw)
    me = raw.get("folder") or folder()
    os.makedirs(me, exist_ok=True)
    cortex = os.path.join(lib_folder(), "sphere_snn.py")
    py = sys.executable
    written = []
    _write_text(os.path.join(me, "connectome.json"), json.dumps(raw, indent=1))
    _write_text(os.path.join(me, "neurons.tsv"), _neurons_tsv(c))
    _write_text(os.path.join(me, "synapses.tsv"), _synapses_tsv())
    written += ["connectome.json", "neurons.tsv", "synapses.tsv"]
    for gone in ("thissnn.bat", "thissnn.ps1"):         # ways in for shells outside Sphere: there are none any more
        try:
            os.remove(os.path.join(me, gone))
        except OSError:
            pass
    _write_text(os.path.join(network_folder(), "rules.tsv"),
                "# kind\tpattern\tneuron -- how a C++ source names a neuron (sphere_snn.INCLUDE_NEURONS, CODE_NEURONS)\n"
                + "".join("include\t%s\t%s\n" % r for r in INCLUDE_NEURONS) + "".join("code\t%s\t%s\n" % r for r in CODE_NEURONS))
    rows = env_of(c, light=True)
    _write_text(os.path.join(me, "env.json"), json.dumps({"side": c["side"], "made": time.time(), "env": [list(r) for r in rows]}, indent=1))
    _write_text(os.path.join(me, "env.tsv"), "# how\tname\tvalue -- what every process Sphere starts on %s is given\n" % c["side"]
                + "".join("%s\t%s\t%s\n" % (how, k, v) for k, v, how in rows))
    written += ["env.json", "env.tsv"]
    os.makedirs(os.path.join(me, "bin"), exist_ok=True)
    snn_sh = os.path.join(me, "bin", "snn")
    _write_text(snn_sh, '#!/bin/sh\n# snn -- the Sphere Neural Network, in what Sphere starts\nexec "%s" "%s" "$@"\n' % (
        py.replace("\\", "/"), cortex.replace("\\", "/")))
    os.chmod(snn_sh, 0o755)
    written.append("bin/snn")
    if WINDOWS:
        _write_text(os.path.join(me, "bin", "snn.bat"), '@"%s" "%s" %%*\r\n' % (py, cortex))
        written.append("bin/snn.bat")
    if c["neurons"].get("julia"):
        os.makedirs(os.path.join(me, "julia"), exist_ok=True)
        _write_text(os.path.join(me, "julia", "SphereSNN.jl"), JULIA_SHIM % os.path.join(lib_folder(), "SphereSNN.jl"))
        written.append("julia/SphereSNN.jl")
    if WINDOWS:
        return written
    head = ("# thissnn.sh -- the Sphere Neural Network in a terminal of Sphere, which reads it by itself. Written by\n"
            "# sphere_snn.py for %s on %s from settings.conf [SNN]: nothing of the machine is changed.\n") % (
        c["side"], time.strftime("%Y-%m-%d %H:%M"))
    _write_text(os.path.join(me, "thissnn.sh"), head + render_env(c, "sh", light=True))
    written.append("thissnn.sh")
    tool = os.path.join(me, "bin", "snn-config")
    _write_text(tool, SNN_CONFIG.replace("@PYTHON@", py).replace("@CORTEX@", cortex))
    os.chmod(tool, 0o755)
    os.makedirs(os.path.join(me, "cmake"), exist_ok=True)
    cpp_neurons = [n for n, info in c["neurons"].items() if any("cpp" in p["langs"] for p in info["providers"])]
    defaults = sorted(_default_set(c, "cpp"))
    _write_text(os.path.join(me, "cmake", "SphereNNConfig.cmake"),
                CMAKE_CONFIG.replace("@PYTHON@", py).replace("@CORTEX@", cortex).replace("@DEFAULTS@", " ".join(defaults)))
    _write_text(os.path.join(me, "cmake", "SphereNNConfigVersion.cmake"), CMAKE_VERSION)
    written += ["bin/snn-config", "cmake/SphereNNConfig.cmake"]
    os.makedirs(os.path.join(me, "pkgconfig"), exist_ok=True)
    for n in sorted(cpp_neurons):
        try:
            f = flags(c, [n])
        except SnnError:
            continue
        p = f["chosen"][n]
        pc = "\n".join(["# snn-%s.pc, written by sphere_snn.py" % n, "Name: snn-%s" % n,
                        "Description: %s, as the Sphere Neural Network chose it (%s)" % (NEURONS.get(n, {}).get("title", n), p["family_name"]),
                        "Version: %s" % re.sub(r"[^\w.]", "", p["version"]) or "0",
                        "Cflags: %s" % " ".join(f["cflags"]), "Libs: %s" % " ".join(f["libs"]), ""])
        _write_text(os.path.join(me, "pkgconfig", "snn-%s.pc" % n), pc)
        written.append("pkgconfig/snn-%s.pc" % n)
    _write_text(os.path.join(me, "snn_load.C"), _root_loader(c))
    written.append("snn_load.C")
    return written


def _root_loader(c):
    """snn_load.C: ROOT's prompt and macros given neurons, the way PyROOT gets them from snn.cpp."""
    chosen = _default_set(c, "cling")
    lib = c.get("lib") or lib_folder()
    rows = []
    for n in _order(chosen)[::-1]:
        p = chosen[n]
        if n == "root" or n in ROOT_PARTS:
            libs = [x for x in (_lib_file(d, l) for d in p["libdir"] for l in p["libs"]) if x] if n in ROOT_PARTS else []
        else:
            libs = p["preload"]
        if not ("cling" in p["langs"] or n in ROOT_PARTS):
            continue
        heads = [h for h in CLING_HEADERS.get(n, p["headers"]) if any(os.path.isfile(os.path.join(d, h)) for d in p["inc"])]
        deps = [d for d in NEURONS.get(n, {}).get("deps", ()) if d in chosen]
        rows.append('    {"%s", "%s", "%s", "%s", "%s", "%s"},' % (
            n, " ".join(deps), _cs(";".join(p["inc"])), _cs(";".join(libs)),
            _cs(";".join("%s=%s" % kv for kv in sorted(p["env"].items()))), _cs(";".join(heads))))
    return r'''// snn_load.C -- neurons of the Sphere Neural Network at ROOT's prompt, in a macro, or in PyROOT:
//     root [0] .x %(me)s/snn_load.C("fastjet fjcontrib hepmc3 geant4")
//     root [1] fastjet::contrib::SoftDrop sd(0.0, 0.1);
// Each neuron brings its headers, its libraries (what they need first) and the variables it reads when it runs, then
// sphere_snn.hpp: the synapses between the neurons given. Written by sphere_snn.py for %(side)s.
#include <TInterpreter.h>
#include <TSystem.h>
#include <TString.h>
#include <cstdlib>
#include <iostream>
#include <set>
#include <sstream>
#include <string>
#include <vector>

namespace snn_load_detail {
struct Neuron { const char* id; const char* deps; const char* inc; const char* libs; const char* env; const char* headers; };
static const Neuron table[] = {
%(rows)s
};
inline std::vector<std::string> split(const std::string& s, char by) {
    std::vector<std::string> out; std::stringstream in(s); std::string w;
    while (std::getline(in, w, by)) if (!w.empty()) out.push_back(w);
    return out;
}
inline std::set<std::string>& loaded() { static std::set<std::string> s; return s; }
inline bool load(const std::string& id) {
    if (loaded().count(id)) return true;
    for (const Neuron& n : table) {
        if (id != n.id) continue;
        for (const std::string& d : split(n.deps, ' ')) if (!load(d)) return false;
        for (const std::string& e : split(n.env, ';')) {
            const auto at = e.find('=');
            if (at != std::string::npos && !std::getenv(e.substr(0, at).c_str())) gSystem->Setenv(e.substr(0, at).c_str(), e.substr(at + 1).c_str());
        }
        for (const std::string& d : split(n.inc, ';')) gInterpreter->AddIncludePath(d.c_str());
        for (const std::string& l : split(n.libs, ';')) {
            if (gSystem->Load(l.c_str()) < 0) { std::cerr << "snn_load: " << id << ": cannot load " << l << std::endl; return false; }
        }
        for (const std::string& h : split(n.headers, ';')) gInterpreter->Declare(("#include \"" + h + "\"").c_str());
        std::string has = "SNN_HAS_" + id;
        for (char& ch : has) ch = (ch == '-') ? '_' : static_cast<char>(std::toupper(static_cast<unsigned char>(ch)));
        gInterpreter->Declare(("#ifndef " + has + "\n#define " + has + " 1\n#endif").c_str());
        loaded().insert(id);
        return true;
    }
    std::cerr << "snn_load: no neuron '" << id << "' here; the neurons: ";
    for (const Neuron& n : table) std::cerr << n.id << " ";
    std::cerr << std::endl;
    return false;
}
}  // namespace snn_load_detail

int snn_load(const char* neurons = "")
{
    gInterpreter->AddIncludePath("%(lib)s");
    int failed = 0;
    for (const std::string& id : snn_load_detail::split(neurons ? neurons : "", ' ')) failed += !snn_load_detail::load(id);
    // the neurons in are those given: no header looked for in vain (Cling would not look again once one is given)
    gInterpreter->Declare("#ifndef SNN_CONFIGURED\n#define SNN_CONFIGURED 1\n#endif\n#ifndef SNN_HAS_ROOT\n#define SNN_HAS_ROOT 1\n#endif");
    if (snn_load_detail::loaded().count("geant4")) gInterpreter->Declare("#ifndef SNN_HAS_CLHEP\n#define SNN_HAS_CLHEP 1\n#endif");
    gInterpreter->Declare("#include \"sphere_snn.hpp\"");
    if (!neurons || !*neurons) {
        std::cout << "snn_load(\"<neurons>\"):";
        for (const auto& n : snn_load_detail::table) std::cout << " " << n.id;
        std::cout << std::endl;
    }
    return failed;
}
''' % {"me": c.get("folder") or folder(), "side": c["side"], "rows": "\n".join(rows), "lib": _cs(lib)}


def _cs(s):
    return str(s).replace("\\", "\\\\").replace('"', '\\"')


# ===========================================================================
# Python: the neurons in this process
# ===========================================================================

_HELD = {}          # neuron -> installation, in this process
_CLING = set()      # the neurons Cling was given (headers declared, libraries loaded)
_CONNECTOME = None


def connectome():
    global _CONNECTOME
    if _CONNECTOME is None:
        _CONNECTOME = detect(local=True)
    return _CONNECTOME


def _mapped():
    """The shared libraries this process has mapped (Linux)."""
    out = set()
    try:
        with open("/proc/self/maps", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                i = line.find("/")
                if i > 0 and ".so" in line:
                    out.add(line[i:].strip())
    except OSError:
        pass
    return out


def _holding(lang="python"):
    """What this process already holds, found from its modules and its mapped libraries: neuron -> installation.
    For Cling, only what holds a C++ library counts (pyhepmc carries its HepMC3 inside: it pins no HepMC3)."""
    held = {n: p for n, p in _HELD.items() if lang != "cling" or "cling" in p["langs"]}
    c = connectome()
    maps = _mapped()
    real_maps = {os.path.realpath(m) for m in maps}
    for n, info in c["neurons"].items():
        if n in held:
            continue
        for p in info["providers"]:
            if lang == "cling" and "cling" not in p["langs"]:
                continue
            mods = [p["module"]] if p.get("module") else []
            if any(m in sys.modules and getattr(sys.modules[m], "__file__", None)
                   and os.path.realpath(sys.modules[m].__file__).startswith(os.path.realpath(p["prefix"] or "/nowhere"))
                   for m in mods):
                held[n] = p
                break
            libs = [x for x in p["preload"] if x]
            if libs and any(os.path.realpath(x) in real_maps for x in libs):
                held[n] = p
                break
    return held


def _python_choice(names, lang):
    c = connectome()
    try:
        chosen, notes = resolve(c, names, lang, pinned=_holding(lang))
    except SnnError as e:
        raise SnnError(str(e)) from None
    return chosen


def _preload(paths):
    import ctypes
    for p in paths:
        if p and os.path.exists(p):
            ctypes.CDLL(p, mode=getattr(ctypes, "RTLD_GLOBAL", 0))


def _apply(p, lang):
    for d in p["py"]:
        if d not in sys.path:
            sys.path.insert(0, d)
    for k, v in p["env"].items():
        os.environ.setdefault(k, v)
    if lang == "python" and p["neuron"] in ("lhapdf", "yoda", "rivet"):
        dirs = _uniq(p["libdir"] + [os.path.join(p["family"], "lib")])
        for lib in list(p["preload"]):
            _preload(_closure(lib, dirs))


def use(*names):
    """Neurons as Python modules in this process: ROOT, torch, tensorflow, fastjet, pyhf, uproot... A C++ neuron that
    has no module of its own (Geant4, fjcontrib, Pythia8, CLHEP) comes through Cling: snn.cpp(name) is what is given.
    One name gives one module; several, a tuple."""
    import importlib
    got = []
    c = connectome()
    for name in names:
        n = canonical(name)
        info = c["neurons"].get(n)
        if info is None:
            raise SnnError(_missing(c, n, "python"))
        mine = [p for p in info["providers"] if "python" in p["langs"] and _importable_here(p)]
        if n in ROOT_PARTS or not mine:
            if any("cling" in p["langs"] for p in info["providers"]) and any(_importable_here(p) for p in c["neurons"].get("root", {}).get("providers", [])):
                got.append(cpp(n))
                continue
            where = [p["python"] for p in info["providers"] if p.get("python")]
            raise SnnError("%s has no module for this Python (%s)%s" % (NEURONS.get(n, {}).get("title", n), sys.executable,
                                                                         "; it is in " + ", ".join(where) + ": snn.hub reaches it" if where else ""))
        held = _holding()
        p, err = (held[n] if n in held and held[n] in mine else None), None
        if p is None:
            for cand in mine:          # the newest this process can take beside what it holds
                try:
                    resolve(c, [n], "python", pinned=dict(held, **{n: cand}))
                    p = cand
                    break
                except SnnError as e:
                    err = e
            if p is None:
                raise err
        _apply(p, "python")
        _HELD[n] = p
        if n == "root":
            os.environ.setdefault("ROOTSYS", p["prefix"])
        got.append(importlib.import_module(p.get("module") or n))
    return got[0] if len(got) == 1 else tuple(got)


def _importable_here(p):
    """Whether this Python can import an installation: a package of its own, or a module built for its version."""
    if p.get("python"):
        return _same_python(p["python"])
    mm = "%d.%d" % sys.version_info[:2]
    if p["neuron"] == "root":
        return bool(p["py"]) and (p["extra"].get("python_version") or "").startswith(mm)
    return any("python" + mm in d for d in p["py"])


def _same_python(path):
    if not path:
        return False
    if os.path.normcase(os.path.abspath(path)) == os.path.normcase(os.path.abspath(sys.executable)):
        return True
    for py in connectome().get("pythons", []):
        if os.path.normcase(py["python"]) == os.path.normcase(path):
            return os.path.normcase(py["prefix"]) == os.path.normcase(sys.prefix)
    return False


def cpp(*names, headers=None):
    """C++ neurons in this process through Cling, ROOT's interpreter: their headers declared, their libraries loaded
    (what they need first), sphere_snn.hpp given what is now there. Returns cppyy.gbl:
        gbl = snn.cpp("fjcontrib", "geant4")
        sd = gbl.fastjet.contrib.SoftDrop(0.0, 0.1)
        water = gbl.G4NistManager.Instance().FindOrBuildMaterial("G4_WATER")"""
    ROOT = use("root")
    import cppyy
    asked = [canonical(n) for n in names]
    chosen = _python_choice(asked, "cling")
    lib = connectome().get("lib") or lib_folder()
    ROOT.gInterpreter.AddIncludePath(lib)
    for n in _order(chosen)[::-1]:
        p = chosen[n]
        if n == "root" or n in _CLING:
            _HELD.setdefault(n, p)
            continue
        if n in _HELD and "cling" in _HELD[n]["langs"] and _HELD[n]["prefix"] != p["prefix"]:
            raise SnnError("%s is held from %s already" % (n, _HELD[n]["prefix"]))
        for k, v in p["env"].items():
            os.environ.setdefault(k, v)
        for d in p["inc"]:
            ROOT.gInterpreter.AddIncludePath(d)
        if n in ROOT_PARTS:
            for l in p["libs"]:
                ROOT.gSystem.Load("lib" + l)
        else:
            for path in p["preload"]:
                if path and ROOT.gSystem.Load(path) < 0:
                    raise SnnError("Cling could not load %s for %s" % (path, n))
        wanted = headers if (headers and n in asked) else CLING_HEADERS.get(n, p["headers"])
        for h in wanted:
            if any(os.path.isfile(os.path.join(d, h)) for d in p["inc"]) or n in ROOT_PARTS:
                cppyy.include(h)
        ROOT.gInterpreter.Declare("#ifndef SNN_HAS_{0}\n#define SNN_HAS_{0} 1\n#endif".format(re.sub(r"\W", "_", n.upper())))
        if n in ROOT_PARTS:
            ROOT.gInterpreter.Declare("#ifndef SNN_HAS_ROOT\n#define SNN_HAS_ROOT 1\n#endif")
        _HELD[n] = p
        _CLING.add(n)
    # SNN_CONFIGURED: the neurons in are the ones declared above. Without it sphere_snn.hpp would ask __has_include
    # for the others, and Cling remembers a header it did not find: once its neuron is given later, still not found.
    ROOT.gInterpreter.Declare("#ifndef SNN_HAS_ROOT\n#define SNN_HAS_ROOT 1\n#endif\n"
                              "#ifndef SNN_CONFIGURED\n#define SNN_CONFIGURED 1\n#endif\n"
                              "#ifndef SPHERE_SNN_NO_GATEWAY\n#define SPHERE_SNN_NO_GATEWAY 1\n#endif")
    if "geant4" in _HELD:
        ROOT.gInterpreter.Declare("#ifndef SNN_HAS_CLHEP\n#define SNN_HAS_CLHEP 1\n#endif")
    ROOT.gInterpreter.Declare('#include "sphere_snn.hpp"')
    return cppyy.gbl


class _Hub:
    """The gateway, for a neuron this Python cannot hold: snn.hub.eval("ROOT.gROOT.GetVersion()")."""

    def __getattr__(self, name):
        import sphere_mlink
        return getattr(sphere_mlink.link(), name)


hub = _Hub()


# ---------------------------------------------------------------------------
# Python synapses
# ---------------------------------------------------------------------------

INVISIBLE = {12, 14, 16, 18, 1000022, 1000039, 1000012, 1000014, 1000016, 2000012, 2000014, 2000016, 39, 5000039}


def pseudojets(event, final=True, visible=True, ptmin=0.0):
    """HepMC3 -> FastJet: an event's particles as fastjet.PseudoJet, user_index the particle's id. A pyhepmc event
    gives the Python fastjet's; a HepMC3::GenEvent of Cling, C++ ones (through sphere_snn.hpp)."""
    if type(event).__module__.startswith("cppyy") or "cppyy" in type(event).__module__:
        gbl = cpp("hepmc3", "fastjet")
        sel = gbl.sphere.snn.Select()
        sel.final_state, sel.visible, sel.ptmin = final, visible, ptmin
        return gbl.sphere.snn.pseudojets(event, sel)
    fastjet = use("fastjet")
    unit = 1e-3 if str(getattr(event, "momentum_unit", "GEV")).upper().endswith("MEV") else 1.0
    out = []
    for p in event.particles:
        if final and p.status != 1:
            continue
        if visible and abs(p.pid) in INVISIBLE:
            continue
        m = p.momentum
        j = fastjet.PseudoJet(m.px * unit, m.py * unit, m.pz * unit, m.e * unit)
        if ptmin and j.pt() < ptmin:
            continue
        j.set_user_index(int(p.id))
        out.append(j)
    return out


def momenta(things):
    """Four-momenta (px, py, pz, E) of PseudoJets (Python's or C++'s), HepMC3 particles or vectors: an (n, 4) array."""
    import numpy as np
    rows = []
    for t in things:
        if hasattr(t, "px") and callable(t.px):
            rows.append((t.px(), t.py(), t.pz(), t.E() if hasattr(t, "E") else t.e()))
        elif hasattr(t, "momentum"):
            m = t.momentum
            rows.append((m.px, m.py, m.pz, m.e))
        else:
            rows.append(tuple(t)[:4])
    return np.asarray(rows, dtype=np.float64).reshape(-1, 4)


def jets_to_root(path, events, tree="jets", columns=None):
    """FastJet -> ROOT: one entry an event, the vectors pt, eta, phi, m, nconst of its jets, and columns of the
    caller's (name -> one list an event). Written by uproot, so no ROOT is needed; ROOT reads it."""
    import numpy as np
    uproot, ak = use("uproot", "awkward")
    data = {"pt": [], "eta": [], "phi": [], "m": [], "nconst": []}
    for jets in events:
        data["pt"].append([j.pt() for j in jets])
        data["eta"].append([j.eta() for j in jets])
        data["phi"].append([j.phi_std() for j in jets])
        data["m"].append([j.m() for j in jets])
        data["nconst"].append([len(j.constituents()) if j.has_constituents() else 0 for j in jets])
    for k, v in (columns or {}).items():
        data[k] = v
    arrays = {k: ak.Array([np.asarray(e, dtype=np.int32 if k == "nconst" else np.float64) for e in v]) for k, v in data.items()}
    with uproot.recreate(path) as f:
        f[tree] = arrays
    return path


def root_arrays(path, tree, branches, library="np"):
    """ROOT -> arrays: branches of a tree read by uproot (no ROOT needed)."""
    uproot = use("uproot")
    with uproot.open(path) as f:
        return f[tree].arrays(branches, library=library)


def _columns(path, tree, branches):
    import numpy as np
    got = root_arrays(path, tree, branches, library="np")
    cols = []
    for b in branches:
        a = got[b]
        if a.dtype == object:
            a = np.concatenate([np.asarray(x, dtype=np.float64) for x in a]) if len(a) else np.zeros(0)
        cols.append(np.asarray(a, dtype=np.float64))
    return np.stack(cols, axis=1) if cols else np.zeros((0, 0))


def root_to_torch(path, tree, branches, dtype=None):
    """ROOT -> PyTorch: branches as the columns of one tensor (rows: entries; a vector branch is flattened)."""
    torch = use("torch")
    t = torch.from_numpy(_columns(path, tree, branches))
    return t.to(dtype) if dtype is not None else t


def root_to_tf(path, tree, branches, dtype=None):
    tf = use("tensorflow")
    return tf.convert_to_tensor(_columns(path, tree, branches), dtype=dtype)


def root_to_jax(path, tree, branches):
    jax = use("jax")
    jax.config.update("jax_enable_x64", True)
    return jax.numpy.asarray(_columns(path, tree, branches))


def tensor(x, to="numpy"):
    """A tensor of NumPy, PyTorch, TensorFlow or JAX as another's, through DLPack (no copy) when both sides can."""
    import numpy as np
    to = canonical(to)
    mod = type(x).__module__.split(".")[0]
    if to == "numpy":
        if mod == "torch":
            return x.detach().cpu().numpy()
        if mod in ("tensorflow", "keras"):
            return x.numpy()
        return np.asarray(x)
    if to == "torch":
        torch = use("torch")
        if mod in ("jax", "jaxlib", "tensorflow"):
            try:
                return torch.from_dlpack(x if mod != "tensorflow" else use("tensorflow").experimental.dlpack.to_dlpack(x))
            except Exception:
                pass
        return torch.as_tensor(tensor(x, "numpy"))
    if to == "tensorflow":
        tf = use("tensorflow")
        if mod in ("torch", "jax", "jaxlib"):
            try:
                cap = x.__dlpack__() if mod == "torch" else x.__dlpack__()
                return tf.experimental.dlpack.from_dlpack(cap)
            except Exception:
                pass
        return tf.convert_to_tensor(tensor(x, "numpy"))
    if to == "jax":
        jax = use("jax")
        jax.config.update("jax_enable_x64", True)
        if mod == "torch":
            try:
                return jax.dlpack.from_dlpack(x)
            except Exception:
                pass
        return jax.numpy.asarray(tensor(x, "numpy"))
    raise SnnError("tensor(..., to=%r): numpy, torch, tensorflow or jax" % to)


def _counts(h):
    import numpy as np
    if hasattr(h, "GetNbinsX"):
        return np.array([h.GetBinContent(i) for i in range(1, h.GetNbinsX() + 1)], dtype=float), \
            np.array([h.GetBinError(i) for i in range(1, h.GetNbinsX() + 1)], dtype=float)
    if hasattr(h, "values") and callable(h.values):
        v = np.asarray(h.values(), dtype=float)
        e = h.errors() if hasattr(h, "errors") else np.sqrt(v)
        return v, np.asarray(e, dtype=float)
    v = np.asarray(h, dtype=float)
    return v, np.sqrt(np.maximum(v, 0))


def hist_to_pyhf(signal, background, observed, bkg_uncertainty=None, mu=1.0):
    """ROOT -> pyhf: one channel of histograms (ROOT's, uproot's, hist's or plain counts) as a pyhf model: the observed
    and expected CLs at mu, and the model. The background's uncertainty is its bin errors unless one is given."""
    pyhf = use("pyhf")
    s, _ = _counts(signal)
    b, db = _counts(background)
    n, _ = _counts(observed)
    if bkg_uncertainty is not None:
        db = _counts(bkg_uncertainty)[0]
    model = pyhf.simplemodels.uncorrelated_background(signal=s.tolist(), bkg=b.tolist(), bkg_uncertainty=db.tolist())
    data = n.tolist() + model.config.auxdata
    obs, exp = pyhf.infer.hypotest(mu, data, model, return_expected=True)
    return {"cls_obs": float(obs), "cls_exp": float(exp), "model": model, "data": data}


def yoda_to_root(yoda_path, root_path):
    """YODA -> ROOT: the 1D histograms and estimates of a YODA file (Rivet's) as TH1D in a ROOT file, written by uproot,
    so that no ROOT is needed to write them; ROOT, uproot and every reader of ROOT files read them. Answers
    {name: (sum of weights, sum of squared weights)} for each histogram written."""
    import numpy as np
    yoda, uproot = use("yoda"), use("uproot")
    hist = use("hist")
    written = {}
    with uproot.recreate(root_path) as f:
        for path, ao in yoda.read(yoda_path).items():
            if not hasattr(ao, "xEdges") or not hasattr(ao, "bins"):
                continue
            try:
                edges = np.asarray(ao.xEdges(), dtype=np.float64)
                bins = list(ao.bins())
            except Exception:              # a counter, a scatter: no binning to make a histogram of
                continue
            if edges.ndim != 1 or len(edges) < 2 or len(bins) != len(edges) - 1:
                continue
            if all(hasattr(b, "sumW") for b in bins):
                sumw = np.array([b.sumW() for b in bins], dtype=np.float64)
                sumw2 = np.array([b.sumW2() for b in bins], dtype=np.float64)
            elif all(hasattr(b, "val") for b in bins):         # an estimate: its value, its average error
                sumw = np.array([b.val() for b in bins], dtype=np.float64)
                err = [getattr(b, "totalErrAvg", getattr(b, "errAvg", lambda: 0.0))() for b in bins]
                sumw2 = np.array([e * e for e in err], dtype=np.float64)
            else:
                continue
            h = hist.Hist(hist.axis.Variable(edges), storage=hist.storage.Weight())
            view = h.view()
            view.value, view.variance = sumw, sumw2
            name = path.strip("/").replace("/", "__") or "yoda"
            f[name] = h
            written[name] = (sumw, sumw2)
    return written


def g4_material(name):
    """A Geant4 material: density (g/cm3), radiation and nuclear interaction lengths (cm), through Cling."""
    gbl = cpp("geant4")
    m = gbl.G4NistManager.Instance().FindOrBuildMaterial(name)
    if not m:
        raise SnnError("Geant4 has no material %s" % name)
    cm, g, cm3 = gbl.CLHEP.cm, gbl.CLHEP.g, gbl.CLHEP.cm3
    return {"name": str(m.GetName()), "density": m.GetDensity() / (g / cm3), "X0": m.GetRadlen() / cm,
            "lambda_I": m.GetNuclearInterLength() / cm}


def g4_to_tgeo(world, path):
    """Geant4 -> ROOT: a world (its physical volume, of Cling) written as GDML by Geant4 and read by TGeo."""
    gbl = cpp("geant4", "tgeo")
    if os.path.exists(path):
        os.remove(path)
    parser = gbl.G4GDMLParser()
    parser.Write(path, world, False)
    ROOT = use("root")
    ROOT.TGeoManager.Import(path)
    return ROOT.gGeoManager


# ===========================================================================
# The check: every neuron and synapse run for real
# ===========================================================================

def reference_particles():
    """The event every language builds the same, bit for bit: only +, -, *, / and sqrt, which IEEE rounds alike."""
    out = []
    for k in range(60):
        a, i = k % 3, k // 3
        if a == 0:
            px, py, pz = 40.0 + (i % 5) * 1.5, 10.0 + (i % 4) * 0.75, 25.0 + (i % 6) * 2.0
        elif a == 1:
            px, py, pz = -35.0 - (i % 4) * 1.25, -12.0 - (i % 5) * 0.5, -30.0 + (i % 3) * 1.0
        else:
            px, py, pz = (i % 7) * 0.5 - 1.5, (i % 5) * 0.4 - 0.8, (i % 9) * 0.7 - 2.8
        e = (px * px + py * py + pz * pz + 0.13957 * 0.13957) ** 0.5
        out.append((px, py, pz, e, 211 if k % 2 == 0 else -211))
    out.append((5.0, 5.0, 5.0, 75.0 ** 0.5, 12))
    return out


CPP_REFERENCE = r'''
inline std::vector<std::array<double, 5>> snn_reference_particles() {
    std::vector<std::array<double, 5>> out;
    for (int k = 0; k < 60; ++k) {
        const int a = k % 3, i = k / 3;
        double px, py, pz;
        if (a == 0) { px = 40.0 + (i % 5) * 1.5; py = 10.0 + (i % 4) * 0.75; pz = 25.0 + (i % 6) * 2.0; }
        else if (a == 1) { px = -35.0 - (i % 4) * 1.25; py = -12.0 - (i % 5) * 0.5; pz = -30.0 + (i % 3) * 1.0; }
        else { px = (i % 7) * 0.5 - 1.5; py = (i % 5) * 0.4 - 0.8; pz = (i % 9) * 0.7 - 2.8; }
        const double e = std::sqrt(px * px + py * py + pz * pz + 0.13957 * 0.13957);
        out.push_back({px, py, pz, e, k % 2 == 0 ? 211.0 : -211.0});
    }
    out.push_back({5.0, 5.0, 5.0, std::sqrt(75.0), 12.0});
    return out;
}
'''

CPP_TESTS = [
    # (name, neurons, what it checks, source)
    ("root", ["root"], ["root"], r'''
#include <TH1D.h>
#include <TROOT.h>
#include <iostream>
int main() {
    TH1D h("h", "h", 10, 0, 10);
    for (int i = 0; i < 1000; ++i) h.Fill(i % 10 + 0.5);
    std::cout << "SNN root ok ROOT " << gROOT->GetVersion() << ": a histogram of " << h.GetEntries() << " entries" << std::endl;
}
'''),
    ("fastjet", ["fastjet", "fjcontrib"], ["fastjet", "fjcontrib", "fastjet > fjcontrib"], r'''
#include <fastjet/ClusterSequence.hh>
#include <fastjet/config.h>
#include <fastjet/contrib/SoftDrop.hh>
#include <fastjet/contrib/Nsubjettiness.hh>
#include <cstdio>
#include <iostream>
int main() {
    std::vector<fastjet::PseudoJet> v{{10, 1, 50, 51.5}, {9, 2, 40, 41.2}, {12, -1, 45, 46.6}, {-20, 1, 10, 22.5}};
    fastjet::ClusterSequence cs(v, fastjet::JetDefinition(fastjet::antikt_algorithm, 1.0));
    auto jets = fastjet::sorted_by_pt(cs.inclusive_jets());
    fastjet::contrib::SoftDrop sd(0.0, 0.1);
    fastjet::contrib::Nsubjettiness tau1(1, fastjet::contrib::OnePass_KT_Axes(), fastjet::contrib::UnnormalizedMeasure(1.0));
    std::cout << "SNN fastjet ok FastJet " << FASTJET_VERSION << ": " << jets.size() << " jets" << std::endl;
    std::cout << "SNN fjcontrib ok " << sd.description().substr(0, 60) << "..." << std::endl;
    std::printf("SNN fastjet>fjcontrib ok SoftDrop m %.17g, tau1 %.17g\n", sd(jets[0]).m(), tau1(jets[0]));
    std::printf("SNNV fjcontrib.sd_m %.17g\nSNNV fjcontrib.tau1 %.17g\n", sd(jets[0]).m(), tau1(jets[0]));
}
'''),
    ("hepmc3", ["hepmc3"], ["hepmc3"], r'''
#include <HepMC3/GenEvent.h>
#include <HepMC3/GenParticle.h>
#include <HepMC3/GenVertex.h>
#include <HepMC3/Version.h>
#include <iostream>
#include <memory>
int main() {
    HepMC3::GenEvent ev(HepMC3::Units::GEV, HepMC3::Units::MM);
    auto v = std::make_shared<HepMC3::GenVertex>();
    v->add_particle_in(std::make_shared<HepMC3::GenParticle>(HepMC3::FourVector(0, 0, 45, 45), 11, 4));
    v->add_particle_out(std::make_shared<HepMC3::GenParticle>(HepMC3::FourVector(1, 2, 3, 4), 211, 1));
    ev.add_vertex(v);
    std::cout << "SNN hepmc3 ok HepMC3 " << HepMC3::version() << ": an event of " << ev.particles().size() << " particles" << std::endl;
}
'''),
    ("geant4", ["geant4"], ["geant4"], r'''
#include <G4NistManager.hh>
#include <G4SystemOfUnits.hh>
#include <G4Version.hh>
#include <cstdio>
#include <iostream>
int main() {
    auto* w = G4NistManager::Instance()->FindOrBuildMaterial("G4_WATER");
    std::printf("SNN geant4 ok %s: G4_WATER %.4f g/cm3, X0 %.3f cm\n", G4Version.c_str(), w->GetDensity() / (g / cm3), w->GetRadlen() / cm);
    std::printf("SNNV geant4.water_X0 %.17g\n", w->GetRadlen() / cm);
}
'''),
    ("clhep", ["clhep"], ["clhep"], r'''
#include <CLHEP/Vector/LorentzVector.h>
#include <CLHEP/ClhepVersion.h>
#include <cstdio>
#include <iostream>
int main() {
    CLHEP::HepLorentzVector v(1, 2, 3, 10);
    std::cout << "SNN clhep ok CLHEP " << CLHEP::Version::String() << ": m = " << v.m() << std::endl;
    std::printf("SNNV clhep.m %.17g\n", v.m());
}
'''),
    ("minuit2", ["minuit2"], ["minuit2"], r'''
#include <Minuit2/FCNBase.h>
#include <Minuit2/FunctionMinimum.h>
#include <Minuit2/MnMigrad.h>
#include <Minuit2/MnUserParameterState.h>
#include <cstdio>
#include <vector>
struct Quadratic : ROOT::Minuit2::FCNBase {
    double operator()(std::vector<double> const& v) const override {
        const double x = v[0], y = v[1];
        return (x - 1.25) * (x - 1.25) + 3.0 * (y + 0.5) * (y + 0.5) + 0.25 * x * y;
    }
    double Up() const override { return 1.0; }
};
int main() {
    Quadratic f;
    ROOT::Minuit2::MnUserParameterState start;
    start.Add("x", 0.0, 0.1);
    start.Add("y", 0.0, 0.1);
    ROOT::Minuit2::MnMigrad migrad(f, start);
    const ROOT::Minuit2::FunctionMinimum min = migrad();
    const double x = min.UserState().Value(0), y = min.UserState().Value(1);
    std::printf("SNN minuit2 %s ROOT's Minuit2: minimum (%.9f, %.9f) in %d calls\n", min.IsValid() ? "ok" : "FAIL", x, y, min.NFcn());
    std::printf("SNNV minuit2.x %.17g\nSNNV minuit2.y %.17g\nSNNV minuit2.fval %.17g\nSNNV minuit2.nfcn %d\n", x, y, min.Fval(), min.NFcn());
    return min.IsValid() ? 0 : 1;
}
'''),
    ("lhe-pythia8", ["pythia8", "hepmc3"], ["madgraph > pythia8"], r'''
#include "sphere_snn.hpp"
#include <HepMC3/WriterAscii.h>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>
int main(int argc, char** argv) {
    if (argc < 2) { std::cout << "SNN madgraph>pythia8 skip no LHE events (the madgraph tool writes them)" << std::endl; return 0; }
    const char* xml = std::getenv("PYTHIA8DATA");
    Pythia8::Pythia pythia(xml ? xml : "../share/Pythia8/xmldoc", false);
    const std::vector<std::string> settings{"Beams:frameType = 4", std::string("Beams:LHEF = ") + argv[1], "Next:numberCount = 0",
                                            "Print:quiet = on", "Random:setSeed = on", "Random:seed = 11"};
    for (const std::string& s : settings) pythia.readString(s);
    if (!pythia.init()) { std::cout << "SNN madgraph>pythia8 FAIL Pythia did not take MadGraph's events" << std::endl; return 1; }
    const std::string out = std::string(std::getenv("SNN_OUT") ? std::getenv("SNN_OUT") : ".") + "/snn_mg5_py8.hepmc";
    HepMC3::WriterAscii file(out);
    int events = 0, muons = 0;
    for (int i = 0; i < 100000; ++i) {
        if (!pythia.next()) {
            if (pythia.info.atEndOfFile()) break;
            continue;
        }
        HepMC3::GenEvent ev(HepMC3::Units::GEV, HepMC3::Units::MM);
        sphere::snn::hepmc3(pythia, ev);
        file.write_event(ev);
        ++events;
        for (int k = 0; k < pythia.event.size(); ++k)
            if (pythia.event[k].isFinal() && pythia.event[k].idAbs() == 13) ++muons;
    }
    file.close();
    std::cout << "SNN madgraph>pythia8 " << (events > 0 ? "ok" : "FAIL") << " Pythia showered " << events
              << " MadGraph events (e+e- -> mu+mu-, " << muons << " final muons), written as HepMC3" << std::endl;
    return events > 0 ? 0 : 1;
}
'''),
    ("lhapdf", ["lhapdf"], ["lhapdf"], r'''
#include <LHAPDF/LHAPDF.h>
#include <cstdio>
#include <iostream>
int main(int argc, char** argv) {
    LHAPDF::setVerbosity(0);
    if (argc < 2) { std::cout << "SNN lhapdf ok LHAPDF " << LHAPDF::version() << " (no set installed)" << std::endl; return 0; }
    LHAPDF::PDF* pdf = LHAPDF::mkPDF(argv[1], 0);
    const double v = pdf->xfxQ(21, 0.1, 100.0);
    std::printf("SNN lhapdf ok LHAPDF %s: %s, x g(x = 0.1, Q = 100 GeV) = %.6f\n", LHAPDF::version().c_str(), argv[1], v);
    std::printf("SNNV lhapdf.xg %.17g\n", v);
    delete pdf;
}
'''),
    ("pythia8", ["pythia8", "hepmc3"], ["pythia8", "pythia8 > hepmc3"], r'''
#include "sphere_snn.hpp"
#include <HepMC3/WriterAscii.h>
#include <cstdlib>
#include <iostream>
int main() {
    const char* xml = std::getenv("PYTHIA8DATA");
    Pythia8::Pythia pythia(xml ? xml : "../share/Pythia8/xmldoc", false);
    for (const char* s : {"Beams:idA = 11", "Beams:idB = -11", "Beams:eCM = 91.1876", "WeakSingleBoson:ffbar2gmZ = on",
                          "PDF:lepton = off", "Next:numberCount = 0", "Print:quiet = on", "Random:setSeed = on", "Random:seed = 7"})
        pythia.readString(s);
    if (!pythia.init()) { std::cout << "SNN pythia8 FAIL Pythia did not initialize" << std::endl; return 1; }
    std::cout << "SNN pythia8 ok Pythia " << pythia.parm("Pythia:versionNumber") << ": e+e- -> Z initialized" << std::endl;
    const std::string out = std::string(std::getenv("SNN_OUT") ? std::getenv("SNN_OUT") : ".") + "/snn_pythia.hepmc";
    HepMC3::WriterAscii file(out);
    int events = 0, particles = 0;
    for (int i = 0; i < 5; ++i) {
        if (!pythia.next()) continue;
        HepMC3::GenEvent ev(HepMC3::Units::GEV, HepMC3::Units::MM);
        if (!sphere::snn::hepmc3(pythia, ev)) continue;
        file.write_event(ev);
        ++events;
        particles += ev.particles().size();
    }
    file.close();
    std::cout << "SNN pythia8>hepmc3 ok " << events << " events, " << particles << " particles, written to " << out << std::endl;
    return events == 5 ? 0 : 1;
}
'''),
    ("lhapdf-pythia8", ["pythia8", "lhapdf"], ["lhapdf > pythia8"], r'''
#include <Pythia8/Pythia.h>
#include <cstdlib>
#include <iostream>
int main(int argc, char** argv) {
    if (argc < 2) { std::cout << "SNN lhapdf>pythia8 FAIL no LHAPDF set installed" << std::endl; return 1; }
    const char* xml = std::getenv("PYTHIA8DATA");
    Pythia8::Pythia pythia(xml ? xml : "../share/Pythia8/xmldoc", false);
    for (std::string s : {std::string("PDF:pSet = LHAPDF6:") + argv[1], std::string("Beams:eCM = 13000."), std::string("HardQCD:all = on"),
                          std::string("PhaseSpace:pTHatMin = 50."), std::string("PartonLevel:all = off"), std::string("HadronLevel:all = off"),
                          std::string("Next:numberCount = 0"), std::string("Print:quiet = on")})
        pythia.readString(s);
    if (!pythia.init()) { std::cout << "SNN lhapdf>pythia8 FAIL Pythia did not take the set" << std::endl; return 1; }
    int n = 0;
    for (int i = 0; i < 3; ++i) n += pythia.next();
    std::cout << "SNN lhapdf>pythia8 ok Pythia ran with LHAPDF6:" << argv[1] << ", " << n << " hard events, sigma = "
              << pythia.info.sigmaGen() << " mb" << std::endl;
}
'''),
    ("yoda", ["yoda"], ["yoda"], r'''
#if __has_include(<YODA/Histo.h>)
#include <YODA/Histo.h>
#else
#include <YODA/Histo1D.h>
#endif
#include <iostream>
int main() {
    YODA::Histo1D h(10, 0.0, 10.0);
    for (int i = 0; i < 100; ++i) h.fill(i % 10 + 0.5);
    std::cout << "SNN yoda ok YODA: a histogram of " << h.numEntries() << " entries" << std::endl;
}
'''),
    ("rivet", ["rivet"], ["rivet", "hepmc3 > rivet", "rivet > yoda"], r'''
#include <Rivet/AnalysisHandler.hh>
#include <Rivet/Rivet.hh>
#include <HepMC3/ReaderAscii.h>
#include <HepMC3/GenEvent.h>
#include <YODA/ReaderYODA.h>
#include <YODA/AnalysisObject.h>
#include <cstdlib>
#include <fstream>
#include <iostream>
int main() {
    std::cout << "SNN rivet ok Rivet " << Rivet::version() << std::endl;
    const std::string dir = std::getenv("SNN_OUT") ? std::getenv("SNN_OUT") : ".";
    std::ifstream probe(dir + "/snn_pythia.hepmc");
    if (!probe) {
        std::cout << "SNN hepmc3>rivet skip no events (the pythia8 check writes them)" << std::endl;
        std::cout << "SNN rivet>yoda skip no events to analyse" << std::endl;
        return 0;
    }
    Rivet::AnalysisHandler ah;
    ah.addAnalysis("MC_XS");
    HepMC3::ReaderAscii in(dir + "/snn_pythia.hepmc");
    int n = 0;
    for (;;) {
        HepMC3::GenEvent ev;
        if (!in.read_event(ev) || in.failed()) break;
        ah.analyze(ev);
        ++n;
    }
    ah.finalize();
    ah.writeData(dir + "/snn_rivet.yoda");
    std::cout << "SNN hepmc3>rivet ok " << n << " events through MC_XS" << std::endl;
    std::vector<YODA::AnalysisObject*> objects;
    YODA::ReaderYODA::create().read(dir + "/snn_rivet.yoda", objects);
    std::cout << "SNN rivet>yoda ok " << objects.size() << " objects in " << dir << "/snn_rivet.yoda" << std::endl;
    for (auto* o : objects) delete o;
    return n > 0 ? 0 : 1;
}
'''),
    ("chain", ["hepmc3", "fastjet", "fjcontrib", "root", "clhep"],
     ["hepmc3 > fastjet", "fastjet > root", "clhep <> hepmc3", "clhep <> fastjet"], r'''
#include "sphere_snn.hpp"
#include <TFile.h>
#include <fastjet/ClusterSequence.hh>
#include <fastjet/contrib/SoftDrop.hh>
#include <HepMC3/GenVertex.h>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <memory>
''' + CPP_REFERENCE.replace("inline ", "static ") + r'''
int main() {
    HepMC3::GenEvent ev(HepMC3::Units::GEV, HepMC3::Units::MM);
    auto v = std::make_shared<HepMC3::GenVertex>();
    for (const auto& p : snn_reference_particles())
        v->add_particle_out(std::make_shared<HepMC3::GenParticle>(HepMC3::FourVector(p[0], p[1], p[2], p[3]), int(p[4]), 1));
    ev.add_vertex(v);
    const auto parts = sphere::snn::pseudojets(ev);
    fastjet::ClusterSequence cs(parts, fastjet::JetDefinition(fastjet::antikt_algorithm, 0.4));
    const auto jets = fastjet::sorted_by_pt(cs.inclusive_jets(5.0));
    std::printf("SNN hepmc3>fastjet ok %zu particles (the neutrino left out) -> %zu jets\n", parts.size(), jets.size());
    for (std::size_t k = 0; k < jets.size(); ++k) std::printf("SNNV chain.jet%zu_pt %.17g\n", k, jets[k].pt());
    const std::string out = std::string(std::getenv("SNN_OUT") ? std::getenv("SNN_OUT") : ".") + "/snn_jets.root";
    TFile f(out.c_str(), "RECREATE");
    sphere::snn::JetTree tree("jets", {"msd"});
    fastjet::contrib::SoftDrop sd(0.0, 0.1);
    for (int e = 0; e < 3; ++e) {
        std::vector<double> msd;
        for (const auto& j : jets) msd.push_back(sd(j).m() * (1 + e));
        tree.fill(jets, {{"msd", msd}});
    }
    f.Write();
    f.Close();
    std::printf("SNN fastjet>root ok 3 entries of %zu jets in %s (pt eta phi m nconst msd)\n", jets.size(), out.c_str());
    const CLHEP::HepLorentzVector lv = sphere::snn::clhep(jets[0]);
    const fastjet::PseudoJet back = sphere::snn::pseudojet(lv);
    const HepMC3::FourVector fv = sphere::snn::fourvector(lv);
    const CLHEP::HepLorentzVector lv2 = sphere::snn::clhep(fv);
    const bool same = back.px() == jets[0].px() && back.E() == jets[0].E() && lv2.e() == lv.e();
    std::printf("SNN clhep<>fastjet %s m %.6f\n", same ? "ok" : "FAIL", lv.m());
    std::printf("SNN clhep<>hepmc3 %s E %.6f\n", same ? "ok" : "FAIL", fv.e());
    return same ? 0 : 1;
}
'''),
    ("primaries", ["geant4", "hepmc3"], ["hepmc3 > geant4"], r'''
#include "sphere_snn.hpp"
#include <G4Event.hh>
#include <G4LeptonConstructor.hh>
#include <G4MesonConstructor.hh>
#include <G4BaryonConstructor.hh>
#include <G4BosonConstructor.hh>
#include <iostream>
#include <memory>
int main() {
    G4LeptonConstructor::ConstructParticle();
    G4MesonConstructor::ConstructParticle();
    G4BaryonConstructor::ConstructParticle();
    G4BosonConstructor::ConstructParticle();
    G4ParticleTable::GetParticleTable()->SetReadiness();   // what a physics list given to G4RunManager does
    HepMC3::GenEvent ev(HepMC3::Units::GEV, HepMC3::Units::MM);
    auto v = std::make_shared<HepMC3::GenVertex>(HepMC3::FourVector(1, 2, 3, 0));
    v->add_particle_in(std::make_shared<HepMC3::GenParticle>(HepMC3::FourVector(0, 0, 50, 50), 23, 2));
    for (int pid : {211, -211, 11, 22, 2212, 12})
        v->add_particle_out(std::make_shared<HepMC3::GenParticle>(HepMC3::FourVector(1, 1, 5, 6), pid, 1));
    ev.add_vertex(v);
    sphere::snn::HepMC3Primaries gen(ev);
    G4Event g4(1);
    gen.GeneratePrimaryVertex(&g4);
    int n = 0;
    for (int k = 0; k < g4.GetNumberOfPrimaryVertex(); ++k) n += g4.GetPrimaryVertex(k)->GetNumberOfParticle();
    const auto* pv = g4.GetPrimaryVertex(0);
    std::cout << "SNN hepmc3>geant4 " << (n == 6 ? "ok " : "FAIL ") << g4.GetNumberOfPrimaryVertex() << " vertex at ("
              << pv->GetX0() << ", " << pv->GetY0() << ", " << pv->GetZ0() << ") mm, " << n << " primaries" << std::endl;
    return n == 6 ? 0 : 1;
}
'''),
    ("gdml", ["geant4", "tgeo"], ["geant4 > tgeo"], r'''
#include <G4Box.hh>
#include <G4GDMLParser.hh>
#include <G4LogicalVolume.hh>
#include <G4NistManager.hh>
#include <G4PVPlacement.hh>
#include <G4SystemOfUnits.hh>
#include <TGeoBBox.h>
#include <TGeoManager.h>
#include <TGeoVolume.h>
#include <cstdio>
#include <cstdlib>
#include <iostream>
int main() {
    const std::string path = std::string(std::getenv("SNN_OUT") ? std::getenv("SNN_OUT") : ".") + "/snn_world.gdml";
    std::remove(path.c_str());
    auto* nist = G4NistManager::Instance();
    auto* world = new G4LogicalVolume(new G4Box("World", 1 * m, 1 * m, 1 * m), nist->FindOrBuildMaterial("G4_AIR"), "World");
    auto* tank = new G4LogicalVolume(new G4Box("Tank", 20 * cm, 30 * cm, 40 * cm), nist->FindOrBuildMaterial("G4_WATER"), "Tank");
    new G4PVPlacement(nullptr, G4ThreeVector(0, 0, 10 * cm), tank, "Tank", world, false, 0);
    auto* top = new G4PVPlacement(nullptr, G4ThreeVector(), world, "World", nullptr, false, 0);
    G4GDMLParser parser;
    parser.Write(path, top, false);
    TGeoManager::Import(path.c_str());
    TGeoVolume* t = gGeoManager ? gGeoManager->GetVolume("Tank") : nullptr;
    if (!t) { std::cout << "SNN geant4>tgeo FAIL TGeo found no Tank in " << path << std::endl; return 1; }
    auto* box = dynamic_cast<TGeoBBox*>(t->GetShape());
    std::printf("SNN geant4>tgeo ok Geant4 wrote %s, TGeo read %d volumes: Tank of %s, %.1f x %.1f x %.1f cm\n", path.c_str(),
                gGeoManager->GetListOfVolumes()->GetEntries(), t->GetMaterial()->GetName(), 2 * box->GetDX(), 2 * box->GetDY(), 2 * box->GetDZ());
}
'''),
    ("tensorflow", ["tensorflow", "root"], ["tensorflow", "root > tensorflow"], r'''
#include "sphere_snn.hpp"
#include <TH1D.h>
#include <cstdio>
#include <iostream>
int main() {
    TH1D h("h", "h", 4, 0, 4);
    for (int i = 0; i < 4; ++i) h.SetBinContent(i + 1, 10.0 * (i + 1));
    const std::vector<double> v = sphere::snn::contents(h);
    TF_Tensor* t = sphere::snn::tf_tensor(v, {2, 2});
    const double* d = static_cast<const double*>(TF_TensorData(t));
    double sum = 0;
    for (int k = 0; k < 4; ++k) sum += d[k];
    std::printf("SNN tensorflow ok TensorFlow C API %s\n", TF_Version());
    std::printf("SNN root>tensorflow %s a TH1 as a 2x2 TF_Tensor, sum %.1f\n", sum == 100.0 ? "ok" : "FAIL", sum);
    TF_DeleteTensor(t);
    return sum == 100.0 ? 0 : 1;
}
'''),
    ("torch", ["torch", "root", "fastjet"], ["torch", "root > torch", "fastjet > torch"], r'''
#include "sphere_snn.hpp"
#include <TTree.h>
#include <torch/version.h>
#include <cstdio>
#include <iostream>
int main() {
    TTree t("t", "t");
    double x = 0, y = 0;
    t.Branch("x", &x);
    t.Branch("y", &y);
    for (int i = 0; i < 5; ++i) { x = i; y = 2.0 * i; t.Fill(); }
    torch::Tensor a = sphere::snn::tensor(t, {"x", "y"});
    const double s = a.sum().item<double>();
    std::printf("SNN torch ok libtorch %s\n", TORCH_VERSION);
    std::printf("SNN root>torch %s a tree of 5 entries as a tensor %ldx%ld, sum %.1f\n", s == 30.0 ? "ok" : "FAIL",
                long(a.size(0)), long(a.size(1)), s);
    std::vector<fastjet::PseudoJet> jets{{1, 2, 3, 10}, {4, 5, 6, 20}};
    torch::Tensor j = sphere::snn::tensor(jets);
    std::printf("SNN fastjet>torch %s jets as a tensor %ldx%ld\n", j.size(0) == 2 && j.size(1) == 4 ? "ok" : "FAIL",
                long(j.size(0)), long(j.size(1)));
    return s == 30.0 ? 0 : 1;
}
'''),
]


# Sphere's portable graph of a PyTorch model (exported by the Python check), run by sphere_graph.hpp.
CPP_GRAPH = r'''
#include "sphere_graph.hpp"
#include <cstdio>
#include <fstream>
#include <vector>
int main(int argc, char** argv) {
    if (argc < 3) { std::printf("SNN sphere-nn<>torch skip no graph (the Python check exports it)\n"); return 0; }
    std::ifstream in(argv[2]);
    long rows = 0, cols = 0;
    in >> rows >> cols;
    std::vector<double> x(static_cast<std::size_t>(rows * cols));
    for (double& v : x) in >> v;
    sphere::graph::Graph g(argv[1]);
    const auto out = g.run({sphere::graph::Tensor::wrap(x.data(), {rows, cols})});
    std::printf("SNN sphere-nn<>torch ok sphere_graph.hpp ran PyTorch's model: %ld outputs\n", static_cast<long>(out[0].numel()));
    for (long i = 0; i < static_cast<long>(out[0].numel()); ++i) std::printf("SNNV graph.cpp%ld %.17g\n", i, out[0].p[i]);
}
'''

# Sphere's Java translations held to the other neurons: the same particles, file, function and graph, the same bits.
JAVA_CHECK = r'''
import com.sphere.core.clhep.vector.HepLorentzVector;
import com.sphere.core.fastjet.ClusterSequence;
import com.sphere.core.fastjet.JetAlgorithm;
import com.sphere.core.fastjet.JetDefinition;
import com.sphere.core.fastjet.PseudoJet;
import com.sphere.core.hepmc3.GenEvent;
import com.sphere.core.hepmc3.GenParticle;
import com.sphere.core.hepmc3.ReaderAscii;
import com.sphere.core.minuit2.FCNBase;
import com.sphere.core.minuit2.FunctionMinimum;
import com.sphere.core.minuit2.MnMigrad;
import com.sphere.core.ml.NeuralGraph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SnnJavaCheck {
    interface Check {
        void run() throws Exception;
    }

    static final Map<String, String> ARGS = new HashMap<>();

    static double num(String key) {
        final String v = ARGS.get(key);
        return v == null ? Double.NaN : Double.parseDouble(v);
    }

    static void say(String id, boolean ok, String detail) {
        System.out.println("SNN " + id + (ok ? " ok " : " FAIL ") + detail);
    }

    static void skip(String id, String detail) {
        System.out.println("SNN " + id + " skip " + detail);
    }

    public static void main(String[] args) {
        for (String a : args) {
            final int i = a.indexOf('=');
            if (i > 0) ARGS.put(a.substring(0, i), a.substring(i + 1));
        }
        System.out.println("SNN java ok Java " + System.getProperty("java.version") + " ran Sphere's translations");
        final String[] ids = {"sphere-java>fastjet", "sphere-java>hepmc3", "sphere-java>clhep", "sphere-java>minuit2", "sphere-nn<>java",
                              "sphere-java>golem95"};
        final Check[] checks = {SnnJavaCheck::fastjet, SnnJavaCheck::hepmc, SnnJavaCheck::clhep, SnnJavaCheck::minuit2, SnnJavaCheck::graph,
                                SnnJavaCheck::golem95};
        for (int k = 0; k < checks.length; k++) {
            try {
                checks[k].run();
            } catch (Throwable e) {
                say(ids[k], false, e.toString().replace('\n', ' '));
            }
        }
    }

    static void fastjet() {
        final List<PseudoJet> parts = new ArrayList<>();
        for (int k = 0; k < 60; ++k) {
            final int a = k % 3, i = k / 3;
            double px, py, pz;
            if (a == 0) { px = 40.0 + (i % 5) * 1.5; py = 10.0 + (i % 4) * 0.75; pz = 25.0 + (i % 6) * 2.0; }
            else if (a == 1) { px = -35.0 - (i % 4) * 1.25; py = -12.0 - (i % 5) * 0.5; pz = -30.0 + (i % 3) * 1.0; }
            else { px = (i % 7) * 0.5 - 1.5; py = (i % 5) * 0.4 - 0.8; pz = (i % 9) * 0.7 - 2.8; }
            parts.add(new PseudoJet(px, py, pz, Math.sqrt(px * px + py * py + pz * pz + 0.13957 * 0.13957)));
        }
        final ClusterSequence cs = new ClusterSequence(parts, new JetDefinition(JetAlgorithm.ANTIKT, 0.4));
        final List<PseudoJet> jets = PseudoJet.sortedByPt(cs.inclusiveJets(5.0));
        final StringBuilder pts = new StringBuilder();
        boolean same = !Double.isNaN(num("jet0"));
        for (int k = 0; k < jets.size(); k++) {
            pts.append(k == 0 ? "" : ", ").append(jets.get(k).pt());
            same &= jets.get(k).pt() == num("jet" + k);
        }
        same &= Double.isNaN(num("jet" + jets.size()));
        say("sphere-java>fastjet", same, "Sphere's Java FastJet: " + jets.size() + " jets, pt " + pts
            + (same ? ", C++ FastJet's bit for bit" : ", C++ FastJet gave " + ARGS.get("jet0") + ", " + ARGS.get("jet1")));
    }

    static void hepmc() {
        final String file = ARGS.getOrDefault("out", ".") + "/snn_pythia.hepmc";
        if (!Files.isRegularFile(Path.of(file))) { skip("sphere-java>hepmc3", "no events (the pythia8 check writes them)"); return; }
        final ReaderAscii in = new ReaderAscii(file);
        int events = 0, finals = 0;
        double sumE = 0;
        while (true) {
            final GenEvent ev = new GenEvent();
            if (!in.readEvent(ev) || in.failed()) break;
            events++;
            for (GenParticle p : ev.particles()) {
                if (p.status() == 1) { finals++; sumE += p.momentum().e(); }
            }
        }
        final boolean same = events == (int) num("hepmc_n") && finals == (int) num("hepmc_nfinal") && sumE == num("hepmc_sumE");
        say("sphere-java>hepmc3", same, "Sphere's Java HepMC3 read C++ Pythia's file: " + events + " events, " + finals
            + " final particles, sum E " + sumE + (same ? ", pyhepmc's bit for bit" : ", pyhepmc read " + ARGS.get("hepmc_n") + " / "
            + ARGS.get("hepmc_nfinal") + " / " + ARGS.get("hepmc_sumE")));
    }

    static void golem95() {
        // real integrals held to their closed forms, and Golem95's box to OneLOop's; throws on a wrong answer
        say("sphere-java>golem95", true, "Sphere's Java Golem95: " + com.sphere.core.golem95.Golem95Probe.check());
    }

    static void clhep() {
        final double m = new HepLorentzVector(1, 2, 3, 10).m();
        final boolean same = m == num("clhep_m");
        say("sphere-java>clhep", same, "Sphere's Java CLHEP: m " + m + (same ? ", C++ CLHEP's bit for bit" : ", C++ CLHEP gave " + ARGS.get("clhep_m")));
    }

    static void minuit2() {
        final FCNBase f = new FCNBase() {
            @Override
            public double value(double[] v) {
                final double x = v[0], y = v[1];
                return (x - 1.25) * (x - 1.25) + 3.0 * (y + 0.5) * (y + 0.5) + 0.25 * x * y;
            }

            @Override
            public double up() {
                return 1.0;
            }
        };
        final FunctionMinimum min = new MnMigrad(f, new double[] {0.0, 0.0}, new double[] {0.1, 0.1}).minimize();
        final double x = min.userState().value(0), y = min.userState().value(1);
        final boolean bits = x == num("minuit_x") && y == num("minuit_y") && min.fval() == num("minuit_fval") && min.nfcn() == (int) num("minuit_nfcn");
        final boolean near = Math.abs(x - num("minuit_x")) < 1e-9 && Math.abs(y - num("minuit_y")) < 1e-9;
        say("sphere-java>minuit2", bits || near, "Sphere's Java Minuit2: minimum (" + x + ", " + y + ") in " + min.nfcn() + " calls"
            + (bits ? ", ROOT's Minuit2 bit for bit" : near ? ", ROOT's Minuit2 to 1e-9 (not to the bit)" : ", ROOT's Minuit2 gave ("
            + ARGS.get("minuit_x") + ", " + ARGS.get("minuit_y") + ")"));
    }

    static void graph() throws Exception {
        final Path g = Path.of(ARGS.getOrDefault("out", ".") + "/snn_graph.sng.spx");
        final Path io = Path.of(ARGS.getOrDefault("out", ".") + "/snn_graph_io.txt");
        if (!Files.isRegularFile(g) || !Files.isRegularFile(io)) { skip("sphere-nn<>java", "no graph (the Python check exports it)"); return; }
        final String[] w = Files.readString(io).trim().split("\\s+");
        final int rows = Integer.parseInt(w[0]), cols = Integer.parseInt(w[1]);
        final double[] x = new double[rows * cols];
        for (int k = 0; k < x.length; k++) x[k] = Double.parseDouble(w[2 + k]);
        final NeuralGraph.Tensor y = NeuralGraph.load(g).run(List.of(new NeuralGraph.Tensor(x, rows, cols))).get(0);
        double worst = 0;
        boolean bits = true;
        for (int k = 0; k < y.data.length; k++) {
            final double ref = Double.parseDouble(w[2 + x.length + k]);
            worst = Math.max(worst, Math.abs(y.data[k] - ref));
            bits &= y.data[k] == ref;
        }
        say("sphere-nn<>java", worst < 1e-12, "Sphere's Java NeuralGraph ran the PyTorch model: " + y.data.length + " outputs"
            + (bits ? ", numpy's bit for bit" : ", within " + worst + " of numpy's"));
    }
}
'''


def _together_source(chosen):
    """One program holding every C++ neuron at once: the proof that each pair can live together."""
    inc = {"root": "#include <TROOT.h>", "geant4": "#include <G4Version.hh>\n#include <G4NistManager.hh>",
           "fastjet": "#include <fastjet/ClusterSequence.hh>\n#include <fastjet/config.h>",
           "fjcontrib": "#include <fastjet/contrib/SoftDrop.hh>", "hepmc3": "#include <HepMC3/Version.h>",
           "lhapdf": "#include <LHAPDF/LHAPDF.h>", "pythia8": "#include <Pythia8/Pythia.h>",
           "clhep": "#include <CLHEP/Vector/LorentzVector.h>", "torch": "#include <torch/torch.h>",
           "tensorflow": "#include <tensorflow/c/c_api.h>", "yoda": "#if __has_include(<YODA/Histo.h>)\n#include <YODA/Histo.h>\n#else\n#include <YODA/Histo1D.h>\n#endif",
           "rivet": "#include <Rivet/Rivet.hh>", "tgeo": "#include <TGeoManager.h>", "roofit": "#include <RooRealVar.h>",
           "tmva": "#include <TMVA/Reader.h>", "minuit2": "#include <Minuit2/Minuit2Minimizer.h>",
           "genvector": "#include <Math/Vector4D.h>", "rdataframe": "#include <ROOT/RDataFrame.hxx>"}
    say = {"root": 'out << " ROOT " << gROOT->GetVersion();',
           "geant4": 'out << " Geant4 " << G4VERSION_NUMBER << " (G4_Si " << G4NistManager::Instance()->FindOrBuildMaterial("G4_Si")->GetDensity() << ")";',
           "fastjet": 'out << " FastJet " << FASTJET_VERSION;', "fjcontrib": 'out << " fjcontrib (SoftDrop " << fastjet::contrib::SoftDrop(0, 0.1).description().size() << " chars)";',
           "hepmc3": 'out << " HepMC3 " << HepMC3::version();', "lhapdf": 'out << " LHAPDF " << LHAPDF::version();',
           "pythia8": 'out << " Pythia8";', "clhep": 'out << " CLHEP (m " << CLHEP::HepLorentzVector(1, 2, 3, 10).m() << ")";',
           "torch": 'out << " libtorch " << torch::ones({2}).sum().item<double>();', "tensorflow": 'out << " TensorFlow " << TF_Version();',
           "yoda": 'out << " YODA " << YODA::Histo1D(2, 0., 1.).numBins();', "rivet": 'out << " Rivet " << Rivet::version();',
           "tgeo": 'out << " TGeo";', "roofit": 'out << " RooFit " << RooRealVar("x", "x", 1.5).getVal();',
           "tmva": 'out << " TMVA";', "minuit2": 'out << " Minuit2";', "genvector": 'out << " GenVector " << ROOT::Math::PxPyPzEVector(0, 0, 3, 5).M();',
           "rdataframe": 'out << " RDataFrame " << *ROOT::RDataFrame(3).Count();'}
    names = [n for n in _order(chosen)[::-1] if n in inc]
    body = "\n".join(inc[n] for n in names)
    calls = "\n    ".join(say[n] for n in names)
    return body + "\n#include <iostream>\n#include <sstream>\nint main() {\n    std::ostringstream out;\n    " + calls + \
        '\n    std::cout << "SNN together ok' + " %d neurons in one program:" % len(names) + '" << out.str() << std::endl;\n}\n', names


def _compiler(cfg):
    for key in ("GPP_DIR", "CLANGPP_DIR"):
        v = native(cfg.get(key) or "")
        if v and os.path.isfile(v) and not v.lower().endswith(".exe"):
            return v
    return os.environ.get("CXX") or shutil.which("g++") or shutil.which("c++") or "g++"


def _build(name, source, f, cxx, out_dir, cache, log):
    """Compiles a check program (once for the same source, flags and compiler) and answers its path, or None."""
    import hashlib
    key = hashlib.sha1("\0".join([source, cxx] + f["cflags"] + f["libs"]).encode("utf-8")).hexdigest()[:16]
    exe = os.path.join(cache, "%s-%s" % (name, key))
    if os.path.isfile(exe):
        return exe, 0.0, ""
    src = exe + ".cpp"
    with open(src, "w", encoding="utf-8") as fh:
        fh.write(source)
    cmd = [cxx, "-O0", "-ffp-contract=off", src, "-o", exe] + f["cflags"] + f["libs"]
    t0 = time.time()
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=1800, env=sandbox_env())
        text = p.stdout.decode("utf-8", "replace")
        ok = p.returncode == 0
    except (OSError, subprocess.SubprocessError) as e:
        text, ok = str(e), False
    log.write("$ %s\n%s\n" % (" ".join(shlex.quote(x) for x in cmd), text))
    if not ok:
        errors = [l for l in text.splitlines() if re.search(r"\berror\b", l) and "error_msg" not in l]
        lines = text.strip().splitlines()
        first = errors[0] if errors else (lines[-1] if lines else "?")
        return None, time.time() - t0, re.sub(r"^\S*/", "", first)[:300]
    return exe, time.time() - t0, ""


def _run_check(exe, args, env, timeout=600):
    t0 = time.time()
    try:
        p = subprocess.run([exe] + args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout, env=sandbox_env(env))
        return p.returncode, p.stdout.decode("utf-8", "replace"), time.time() - t0
    except (OSError, subprocess.SubprocessError) as e:
        return -1, str(e), time.time() - t0


def _parse(text):
    """SNN <id> <ok|FAIL|skip> <detail> and SNNV <key> <value> lines."""
    results, values = [], {}
    for line in text.splitlines():
        m = re.match(r"^SNN (\S+) (ok|FAIL|skip)\s*(.*)$", line)
        if m:
            ident = m.group(1).replace("<>", "\0").replace(">", " > ").replace("\0", " <> ")
            results.append((ident, m.group(2), m.group(3)))
        m = re.match(r"^SNNV (\S+) (\S+)$", line)
        if m:
            try:
                values[m.group(1)] = float(m.group(2))
            except ValueError:
                pass
    return results, values


def check(only=None, quick=False, progress=print):
    """Every neuron and synapse run for real, each language its own way; the results written to matrix.json."""
    t0 = time.time()
    c = detect(local=True)
    me = c.get("folder") or folder()
    os.makedirs(me, exist_ok=True)
    out_dir = os.path.join(_cache_folder(), "out")
    cache = os.path.join(_cache_folder(), "build")
    os.makedirs(out_dir, exist_ok=True)
    os.makedirs(cache, exist_ok=True)
    langs = set((only or "cpp,python,cling,julia,gateway,tool,java").split(","))
    if "madgraph" in langs:     # asked for by name: MadGraph is run, with the other tools
        langs.add("tool")
    report = {"format": FORMAT, "side": c["side"], "made": time.time(), "results": [], "values": {}, "together": {}}
    try:        # the numbers the C++ programs gave last, for Python and Cling to be held to when C++ is not run now
        with open(os.path.join(me, "matrix.json"), encoding="utf-8") as fh:
            report["values"].update(json.load(fh).get("values", {}))
    except (OSError, ValueError):
        pass

    def add(kind, ident, lang, ok, detail, seconds=0.0):
        report["results"].append({"kind": kind, "id": ident, "lang": lang, "ok": ok, "detail": detail, "seconds": round(seconds, 2)})
        progress("  %-4s %-8s %-26s %s" % ("ok" if ok is True else "--" if ok is None else "FAIL", lang, ident, detail))

    cfg = settings()
    cxx = _compiler(cfg)
    tool_log = open(os.path.join(_cache_folder(), "check-tools.log"), "w", encoding="utf-8")
    if "tool" in langs and not WINDOWS:
        progress("Generators, as their users run them:")
        _tools_before(c, out_dir, _cache_folder(), add, progress, tool_log, madgraph="madgraph" in langs)
    if "cpp" in langs and not WINDOWS:
        progress("C++ (%s), one program at a time:" % cxx)
        log = open(os.path.join(_cache_folder(), "check-cpp.log"), "w", encoding="utf-8")
        env = dict(os.environ, SNN_OUT=out_dir, SPHERE_SNN=os.path.dirname(me), SPHERE_BRIDGE_LIB=c.get("lib") or lib_folder())
        tests = [t for t in CPP_TESTS if not (quick and t[0] in ("torch",))]
        for name, neurons, covers, source in tests:
            try:
                f = flags(c, neurons)
            except SnnError as e:
                for cv in covers:
                    add("synapse" if (">" in cv or "<>" in cv) else "neuron", cv, "cpp", None, "not here: %s" % e)
                continue
            args = []
            if "lhapdf" in neurons:
                sets = f["chosen"]["lhapdf"]["extra"].get("sets", [])
                args = [s for s in sets if "lo" in s.lower()][:1] or sets[:1]
            if name == "lhe-pythia8":
                lhe = os.path.join(out_dir, "snn_mg5.lhe")
                args = [lhe] if os.path.isfile(lhe) else []
            run_env = dict(env, **{k: v for k, v in f["env"].items() if k not in env or k == "LD_LIBRARY_PATH"})
            if env.get("LD_LIBRARY_PATH") and f["env"].get("LD_LIBRARY_PATH"):
                run_env["LD_LIBRARY_PATH"] = f["env"]["LD_LIBRARY_PATH"] + ":" + env["LD_LIBRARY_PATH"]
            exe, secs, err = _build(name, source, f, cxx, out_dir, cache, log)
            if exe is None:
                for cv in covers:
                    add("synapse" if (">" in cv or "<>" in cv) else "neuron", cv, "cpp", False, "does not compile: " + err, secs)
                continue
            code, text, rsecs = _run_check(exe, args, run_env)
            log.write("$ %s %s\n%s\n" % (exe, " ".join(args), text))
            results, values = _parse(text)
            report["values"].update({"cpp." + k: v for k, v in values.items()})
            seen = set()
            for ident, status, detail in results:
                seen.add(ident)
                add("synapse" if (">" in ident or "<>" in ident) else "neuron", ident, "cpp",
                    True if status == "ok" else None if status == "skip" else False, detail, secs + rsecs)
            for cv in covers:
                if cv not in seen:
                    add("synapse" if (">" in cv or "<>" in cv) else "neuron", cv, "cpp", False,
                        "the program ended (%d) before saying: %s" % (code, text.strip().splitlines()[-1][:200] if text.strip() else "nothing"))
        if not quick:
            together = _default_set(c, "cpp")
            names = [n for n in together if any(l == "cpp" for l in together[n]["langs"]) and n not in ("delphes", "vecgeom", "sphere-nn", "mlink")]
            try:
                f = flags(c, names)
                source, held = _together_source(f["chosen"])
                exe, secs, err = _build("together", source, f, cxx, out_dir, cache, log)
                if exe is None:
                    add("together", "together", "cpp", False, "does not compile: " + err, secs)
                else:
                    code, text, rsecs = _run_check(exe, [], dict(env, **f["env"]))
                    results, _ = _parse(text)
                    ok = any(r[0] == "together" and r[1] == "ok" for r in results)
                    detail = next((r[2] for r in results if r[0] == "together"), text.strip()[-200:])
                    add("together", "together", "cpp", ok, detail, secs + rsecs)
                    if ok:
                        report["together"]["cpp"] = held
            except SnnError as e:
                add("together", "together", "cpp", False, str(e))
        log.close()
    if "tool" in langs and not WINDOWS:
        _tool_delphes(c, out_dir, add, progress, tool_log)
    if ("python" in langs or "cling" in langs) and c.get("python"):
        progress("Python (%s), one process:" % c["python"])
        dest = os.path.join(_cache_folder(), "check-python.json")
        try:
            os.remove(dest)
        except OSError:
            pass
        env = dict(os.environ, SNN_OUT=out_dir, SPHERE_SNN=os.path.dirname(me), PYTHONDONTWRITEBYTECODE="1",
                   TF_CPP_MIN_LOG_LEVEL="3", SNN_VALUES=json.dumps(report["values"]))
        cmd = [c["python"], os.path.join(lib_folder(), "sphere_snn.py"), "check-python", dest, ",".join(sorted(langs))]
        t1 = time.time()
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=sandbox_env(env), timeout=3600)
        text = p.stdout.decode("utf-8", "replace")
        with open(os.path.join(_cache_folder(), "check-python.log"), "w", encoding="utf-8") as fh:
            fh.write(text)
        got = {"results": [], "together": {}}
        try:
            with open(dest, encoding="utf-8") as fh:
                got = json.load(fh)
        except (OSError, ValueError):
            pass
        for r in got["results"]:
            add(r["kind"], r["id"], r["lang"], r["ok"], r["detail"], r.get("seconds", 0))
        report["together"].update(got.get("together", {}))
        report["values"].update(got.get("values", {}))
        if p.returncode != 0:
            last = [l for l in text.strip().splitlines() if l.strip()][-1:] or ["no output"]
            add("together", "process", "python", False, "the check process stopped (%d) after %d results: %s" % (
                p.returncode, len(got["results"]), last[0][:200]), time.time() - t1)
    if "python" in langs and c.get("python"):
        progress("Sphere's graphs (a process of their own):")
        dest = os.path.join(_cache_folder(), "check-graphs.json")
        try:
            os.remove(dest)
        except OSError:
            pass
        env = dict(os.environ, SNN_OUT=out_dir, SPHERE_SNN=os.path.dirname(me), PYTHONDONTWRITEBYTECODE="1", TF_CPP_MIN_LOG_LEVEL="3")
        p = subprocess.run([c["python"], os.path.join(lib_folder(), "sphere_snn.py"), "check-graphs", dest],
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=sandbox_env(env), timeout=1800)
        got = {"results": [], "values": {}}
        try:
            with open(dest, encoding="utf-8") as fh:
                got = json.load(fh)
        except (OSError, ValueError):
            pass
        for r in got["results"]:
            add(r["kind"], r["id"], r["lang"], r["ok"], r["detail"], r.get("seconds", 0))
        report["values"].update(got.get("values", {}))
        if p.returncode != 0 and not got["results"]:
            add("synapse", "sphere-nn <> torch", "python", False, "the graphs' process stopped (%d): %s" % (
                p.returncode, _last_words(p.stdout.decode("utf-8", "replace"))))
    if "cpp" in langs and not WINDOWS:
        log = open(os.path.join(_cache_folder(), "check-cpp.log"), "a", encoding="utf-8")
        _check_graph_cpp(c, out_dir, os.path.join(_cache_folder(), "build"), report, add, cxx, log)
        log.close()
    if "java" in langs and not WINDOWS:
        _check_java(c, out_dir, _cache_folder(), report, add, progress, tool_log)
    tool_log.close()
    if WINDOWS and ("julia" in langs or "gateway" in langs):
        _check_windows(c, langs, add, report, progress)
    report["seconds"] = round(time.time() - t0, 1)
    # A check of some languages keeps what the last one found for the others.
    try:
        with open(os.path.join(me, "matrix.json"), encoding="utf-8") as fh:
            before = json.load(fh)
        ran = {r["lang"] for r in report["results"]} | langs
        report["results"] = [r for r in before.get("results", []) if r["lang"] not in ran] + report["results"]
        for lang, held in before.get("together", {}).items():
            report["together"].setdefault(lang, held)
        for k, v in before.get("values", {}).items():
            report["values"].setdefault(k, v)
    except (OSError, ValueError):
        pass
    _write_text(os.path.join(me, "matrix.json"), json.dumps(report, indent=1))
    return report


# ---------------------------------------------------------------------------
# The tools of the network: generators and a detector's simulation, run as their users run them
# ---------------------------------------------------------------------------

def _run_tool(cmd, cwd, env, timeout, log):
    t0 = time.time()
    log.write("$ (cd %s) %s\n" % (cwd, " ".join(shlex.quote(x) for x in cmd)))
    try:
        p = subprocess.run(cmd, cwd=cwd, env=sandbox_env(env), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                           timeout=timeout)
        text, code = p.stdout.decode("utf-8", "replace"), p.returncode
    except subprocess.TimeoutExpired as e:
        text, code = (e.stdout or b"").decode("utf-8", "replace") + "\n(stopped after %d s)" % timeout, -1
    except OSError as e:
        text, code = str(e), -1
    log.write(text[-30000:] + "\n")
    log.flush()
    return code, text, time.time() - t0


def _last_words(text, n=1):
    lines = [l.strip() for l in (text or "").splitlines() if l.strip()]
    return " | ".join(lines[-n:])[:300] if lines else "no output"


def _newest(paths):
    paths = [p for p in paths if os.path.isfile(p)]
    return max(paths, key=os.path.getmtime) if paths else None


MG5_SCRIPT = """set auto_update 0 --no_save
set automatic_html_opening False --no_save
generate e+ e- > mu+ mu-
output {work} -f
launch {work}
shower=OFF
analysis=OFF
done
set nevents 20
set ebeam1 45.6
set ebeam2 45.6
set lpp1 0
set lpp2 0
set iseed 7
done
"""

HERWIG_HEPMC = """create ThePEG::HepMCFile /Herwig/Analysis/HepMC HepMCAnalysis.so
set /Herwig/Analysis/HepMC:PrintEvent 5
set /Herwig/Analysis/HepMC:Filename snn_herwig.hepmc
insert /Herwig/Generators/EventGenerator:AnalysisHandlers 0 /Herwig/Analysis/HepMC
"""


def _tools_before(c, out_dir, cache, add, progress, log, madgraph=False):
    """MadGraph and Herwig, as their users run them: e+e- -> mu+mu- as LHE events, LEP events as HepMC. Their work is
    kept (MadGraph compiles Fortran, Herwig builds its matrix elements): a later check reuses it. MadGraph is run only
    when asked for by name (check --only madgraph): starting, it rewrites files of its own installation
    (Template/LO/Source/make_opts), and the network writes nothing outside Sphere unless told to."""
    import glob
    import gzip
    mgs = [p for p in c["neurons"].get("madgraph", {}).get("providers", []) if os.path.isfile(p["origin"])]
    if mgs and not madgraph and not _newest(glob.glob(os.path.join(cache, "mg5_ee_mumu", "Events", "*", "unweighted_events.lhe.gz"))):
        progress("  MadGraph %s: not run -- starting, it rewrites files of its own installation; "
                 "'snn check --only madgraph' runs it" % mgs[0]["version"])
        mgs = []
    if mgs:
        mg = mgs[0]
        work = os.path.join(cache, "mg5_ee_mumu")
        lhe = _newest(glob.glob(os.path.join(work, "Events", "*", "unweighted_events.lhe.gz")))
        secs, said = 0.0, ""
        if not lhe:
            progress("  MadGraph %s: e+e- -> mu+mu- (written and compiled once)..." % mg["version"])
            script = os.path.join(cache, "mg5_ee_mumu.mg5")
            with open(script, "w", encoding="utf-8") as fh:
                fh.write(MG5_SCRIPT.format(work=work))
            for python in _uniq([sys.executable, c.get("python")]):
                code, said, secs = _run_tool([python, mg["origin"], script], cache, dict(os.environ), 3600, log)
                lhe = _newest(glob.glob(os.path.join(work, "Events", "*", "unweighted_events.lhe.gz")))
                if lhe:
                    break
        if lhe:
            with gzip.open(lhe, "rb") as fh:
                data = fh.read()
            with open(os.path.join(out_dir, "snn_mg5.lhe"), "wb") as fh:
                fh.write(data)
            add("neuron", "madgraph", "tool", True, "MadGraph %s: e+e- -> mu+mu-, %d events as LHE%s" % (
                mg["version"], data.count(b"<event"), " (made now)" if secs else " (made by an earlier check)"), secs)
        else:
            add("neuron", "madgraph", "tool", False, "MadGraph made no events: " + _last_words(said, 2), secs)
    hws = [p for p in c["neurons"].get("herwig", {}).get("providers", []) if os.path.isfile(os.path.join(p["prefix"], "bin", "Herwig"))]
    if hws:
        hw = hws[0]
        work = os.path.join(cache, "herwig")
        os.makedirs(work, exist_ok=True)
        made = os.path.join(work, "snn_herwig.hepmc")
        secs, said = 0.0, ""
        if not os.path.isfile(made):
            progress("  Herwig %s: LEP events (its matrix elements built once)..." % hw["version"])
            lep = _read(os.path.join(hw["prefix"], "share", "Herwig", "LEP.in"))
            if "saverun LEP EventGenerator" in lep:
                lep = lep.replace("saverun LEP EventGenerator", HERWIG_HEPMC + "saverun snn_LEP EventGenerator")
                with open(os.path.join(work, "snn_LEP.in"), "w", encoding="utf-8") as fh:
                    fh.write(lep)
                libs = [os.path.join(hw["prefix"], "lib", d) for d in ("", "ThePEG", "Herwig")]
                env = dict(os.environ, PATH=os.path.join(hw["prefix"], "bin") + os.pathsep + os.environ.get("PATH", ""),
                           LD_LIBRARY_PATH=os.pathsep.join(libs + [os.environ.get("LD_LIBRARY_PATH", "")]))
                exe = os.path.join(hw["prefix"], "bin", "Herwig")
                code, said, secs = _run_tool([exe, "read", "snn_LEP.in"], work, env, 3600, log)
                if code == 0:
                    code, said2, secs2 = _run_tool([exe, "run", "snn_LEP.run", "-N", "5", "-s", "7"], work, env, 1800, log)
                    said, secs = said + said2, secs + secs2
        if os.path.isfile(made):
            text = _read(made, 64 << 20)
            n = len(re.findall(r"^E ", text, re.M))
            shutil.copyfile(made, os.path.join(out_dir, "snn_herwig.hepmc"))
            add("neuron", "herwig", "tool", n > 0, "Herwig %s: %d LEP events written as HepMC by ThePEG's HepMCFile%s" % (
                hw["version"], n, " (made now)" if secs else " (made by an earlier check)"), secs)
        else:
            add("neuron", "herwig", "tool", False, "Herwig wrote no events: " + _last_words(said, 2), secs)


def _tool_delphes(c, out_dir, add, progress, log):
    """Delphes on the events of the C++ Pythia check, through a detector card of e+e- colliders."""
    dls = [p for p in c["neurons"].get("delphes", {}).get("providers", []) if os.path.isfile(os.path.join(p["prefix"], "DelphesHepMC3"))]
    events = os.path.join(out_dir, "snn_pythia.hepmc")
    if not dls:
        return
    if not os.path.isfile(events):
        add("synapse", "hepmc3 > delphes", "tool", None, "no events (the pythia8 check writes them)")
        return
    d = dls[0]["prefix"]
    card = next((os.path.join(d, "cards", k) for k in ("delphes_card_CircularEE.tcl", "delphes_card_IDEA.tcl", "delphes_card_ATLAS.tcl")
                 if os.path.isfile(os.path.join(d, "cards", k))), None)
    root = next(iter(c["neurons"].get("root", {}).get("providers", [])), None)
    out = os.path.join(out_dir, "snn_delphes.root")
    if os.path.exists(out):
        os.remove(out)
    libs = [d] + (root["libdir"] if root else [])
    env = dict(os.environ, LD_LIBRARY_PATH=os.pathsep.join(libs + [os.environ.get("LD_LIBRARY_PATH", "")]))
    progress("  Delphes on Pythia's events (%s)..." % os.path.basename(card or "?"))
    code, said, secs = _run_tool([os.path.join(d, "DelphesHepMC3"), card, out, events], d, env, 1800, log)
    if os.path.isfile(out) and os.path.getsize(out) > 0:
        add("synapse", "hepmc3 > delphes", "tool", True, "DelphesHepMC3 simulated %s on C++ Pythia's events: %d kB of ROOT tree" % (
            os.path.basename(card)[:-4], os.path.getsize(out) // 1024), secs)
    else:
        add("synapse", "hepmc3 > delphes", "tool", False, "Delphes wrote nothing (%d): %s" % (code, _last_words(said, 2)), secs)


def _check_graph_cpp(c, out_dir, cache, report, add, cxx, log):
    """Sphere's graph of a PyTorch model, run by sphere_graph.hpp: held to numpy's run of it."""
    graph, io = os.path.join(out_dir, "snn_graph.sng.spx"), os.path.join(out_dir, "snn_graph_io.txt")
    if not (os.path.isfile(graph) and os.path.isfile(io)):
        add("synapse", "sphere-nn <> torch", "cpp", None, "no graph (the Python check exports it)")
        return
    try:
        f = flags(c, ["sphere-nn"])
    except SnnError as e:
        add("synapse", "sphere-nn <> torch", "cpp", None, "not here: %s" % e)
        return
    exe, secs, err = _build("graph", CPP_GRAPH, f, cxx, out_dir, cache, log)
    if exe is None:
        add("synapse", "sphere-nn <> torch", "cpp", False, "does not compile: " + err, secs)
        return
    code, text, rsecs = _run_check(exe, [graph, io], dict(os.environ))
    _, values = _parse(text)
    got = [values.get("graph.cpp%d" % k) for k in range(len(values))]
    ref = report["values"].get("python.graph.y") or []
    if not got or None in got or len(got) != len(ref):
        add("synapse", "sphere-nn <> torch", "cpp", False, "the C++ run gave %d values for %d: %s" % (len(got), len(ref), _last_words(text)), secs + rsecs)
        return
    worst = max(abs(a - b) for a, b in zip(got, ref))
    add("synapse", "sphere-nn <> torch", "cpp", worst <= 1e-12 * max(1.0, max(abs(b) for b in ref)),
        "sphere_graph.hpp ran PyTorch's model: %d outputs, %s" % (len(got), "numpy's bit for bit" if worst == 0 else "within %.1e of numpy's" % worst),
        secs + rsecs)


def _check_java(c, out_dir, cache, report, add, progress, log):
    """Sphere's Java translations held to the other neurons: FastJet, HepMC3, CLHEP, Minuit2, the graphs, and Golem95
    (to the closed forms of its integrals and to OneLOop)."""
    jv = next(iter(c["neurons"].get("java", {}).get("providers", [])), None)
    root = sphere_root()
    jar = os.path.join(root, "Sphere.jar") if root else None
    if jv is None or not jv["extra"].get("javac") or not jar or not os.path.isfile(jar):
        add("neuron", "java", "java", None, "no Java 22+ with javac, or no Sphere.jar, on this side")
        return
    jdir = os.path.join(cache, "java")
    os.makedirs(jdir, exist_ok=True)
    src = os.path.join(jdir, "SnnJavaCheck.java")
    if _read(src) != JAVA_CHECK or not os.path.isfile(os.path.join(jdir, "SnnJavaCheck.class")) \
            or os.path.getmtime(os.path.join(jdir, "SnnJavaCheck.class")) < os.path.getmtime(jar):
        with open(src, "w", encoding="utf-8") as fh:
            fh.write(JAVA_CHECK)
        code, said, secs = _run_tool([jv["extra"]["javac"], "-nowarn", "-cp", jar, "-d", jdir, src], jdir, dict(os.environ), 600, log)
        if code != 0:
            add("neuron", "java", "java", False, "the check does not compile against Sphere.jar: " + _last_words(said, 2), secs)
            return
    v = report["values"]
    args = ["out=" + out_dir]
    for k in range(10):
        if "cpp.chain.jet%d_pt" % k in v:
            args.append("jet%d=%r" % (k, v["cpp.chain.jet%d_pt" % k]))
    for key, name in (("cpp.clhep.m", "clhep_m"), ("cpp.minuit2.x", "minuit_x"), ("cpp.minuit2.y", "minuit_y"),
                      ("cpp.minuit2.fval", "minuit_fval"), ("cpp.minuit2.nfcn", "minuit_nfcn"), ("python.hepmc.n", "hepmc_n"),
                      ("python.hepmc.nfinal", "hepmc_nfinal"), ("python.hepmc.sumE", "hepmc_sumE")):
        if key in v:
            args.append("%s=%r" % (name, v[key]))
    progress("Java %s, Sphere's translations (%s):" % (jv["version"], jar))
    code, said, secs = _run_tool([jv["extra"]["exe"], "-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED",
                                  "-cp", jar + os.pathsep + jdir, "SnnJavaCheck"] + args, root, dict(os.environ), 900, log)
    results, _ = _parse(said)
    for ident, status, detail in results:
        add("synapse" if (">" in ident or "<>" in ident) else "neuron", ident, "java",
            True if status == "ok" else None if status == "skip" else False, detail, secs)
    if not results:
        add("neuron", "java", "java", False, "the Java check said nothing (%d): %s" % (code, _last_words(said, 2)), secs)


def _check_python(dest, langs):
    """Runs in the network's Python: every Python neuron imported into one process, the C++ ones through Cling, the
    synapses run, values held to those the C++ programs printed."""
    import math
    langs = set(langs.split(","))
    out = {"results": [], "together": {}, "values": {}}
    values = json.loads(os.environ.get("SNN_VALUES") or "{}")
    odir = os.environ.get("SNN_OUT", ".")

    def add(kind, ident, lang, ok, detail, t0=None):
        out["results"].append({"kind": kind, "id": ident, "lang": lang, "ok": ok, "detail": str(detail)[:300],
                               "seconds": round(time.time() - t0, 2) if t0 else 0})
        print("%s %s %s %s" % ("ok" if ok else "--" if ok is None else "FAIL", lang, ident, detail), flush=True)
        with open(dest, "w", encoding="utf-8") as fh:
            json.dump(out, fh)

    def attempt(kind, ident, lang, fn):
        t0 = time.time()
        try:
            r = fn()
            if isinstance(r, tuple) and r and r[0] is None:
                add(kind, ident, lang, None, r[1], t0)
            else:
                add(kind, ident, lang, True, r, t0)
            return True
        except SnnError as e:
            add(kind, ident, lang, None, "not here: %s" % e, t0)
        except Exception as e:      # a neuron that fails must not stop the others
            add(kind, ident, lang, False, "%s: %s" % (type(e).__name__, e), t0)
        return False

    c = connectome()
    me = next((p for p in c["pythons"] if _same_python(p["python"])), {"can": {}})
    can = me.get("can", {})
    py_neurons = [n for n, how in can.items() if how.startswith("import") or n in ("root", "lhapdf", "yoda")]
    if "root" in py_neurons:
        py_neurons.remove("root")
        py_neurons.insert(0, "root")
    held = []
    if "python" in langs:
        for n in py_neurons:
            def imp(n=n):
                m = use(n)
                held.append(n)
                return "%s %s" % (getattr(m, "__name__", n), getattr(m, "__version__", "") or (m.gROOT.GetVersion() if n == "root" else ""))
            attempt("neuron", n, "python", imp)
        out["together"]["python"] = list(held)
        add("together", "together", "python", len(held) == len(py_neurons),
            "%d of %d Python neurons in one process: %s" % (len(held), len(py_neurons), " ".join(held)))
        if "root" in held:          # a crash reported at once: no debugger attached for a backtrace (minutes, on a small machine)
            sys.modules["ROOT"].gEnv.SetValue("Root.Stacktrace", "no")
    ref = reference_particles()

    def near(a, b, rel=1e-12):
        return abs(a - b) <= rel * max(1.0, abs(a), abs(b))

    if "cling" in langs and "root" in can:
        def fjc():
            gbl = cpp("fjcontrib")
            fj = gbl.fastjet
            v = gbl.std.vector[fj.PseudoJet]()
            for p in ((10, 1, 50, 51.5), (9, 2, 40, 41.2), (12, -1, 45, 46.6), (-20, 1, 10, 22.5)):
                v.push_back(fj.PseudoJet(*p))
            cs = fj.ClusterSequence(v, fj.JetDefinition(fj.antikt_algorithm, 1.0))
            jets = fj.sorted_by_pt(cs.inclusive_jets())
            sd = fj.contrib.SoftDrop(0.0, 0.1)
            tau = fj.contrib.Nsubjettiness(1, fj.contrib.OnePass_KT_Axes(), fj.contrib.UnnormalizedMeasure(1.0))
            m, t = sd(jets[0]).m(), tau(jets[0])
            ref_m, ref_t = values.get("cpp.fjcontrib.sd_m"), values.get("cpp.fjcontrib.tau1")
            same = "" if ref_m is None else (", the C++ program's bit for bit" if m == ref_m and t == ref_t else
                                             ", NOT the C++ program's (%r, %r)" % (ref_m, ref_t))
            if ref_m is not None and not (m == ref_m and t == ref_t):
                raise AssertionError("Cling %r %r against C++ %r %r" % (m, t, ref_m, ref_t))
            return "SoftDrop m %.6f, tau1 %.6f through Cling%s" % (m, t, same)
        attempt("synapse", "fastjet > fjcontrib", "cling", fjc)

        def g4():
            info = g4_material("G4_WATER")
            ref = values.get("cpp.geant4.water_X0")
            if ref is not None and info["X0"] != ref:
                raise AssertionError("X0 %r against the C++ program's %r" % (info["X0"], ref))
            return "Geant4 through Cling: G4_WATER %.4f g/cm3, X0 %.3f cm, lambda_I %.2f cm%s" % (
                info["density"], info["X0"], info["lambda_I"], ", the C++ program's bit for bit" if ref is not None else "")
        attempt("neuron", "geant4", "cling", g4)

        def hepmc_cling():
            gbl = cpp("hepmc3", "fastjet")
            H = gbl.HepMC3
            ev = H.GenEvent(H.Units.GEV, H.Units.MM)
            vx = gbl.std.make_shared[H.GenVertex]()
            for px, py, pz, e, pid in ref:
                vx.add_particle_out(gbl.std.make_shared[H.GenParticle](H.FourVector(px, py, pz, e), int(pid), 1))
            ev.add_vertex(vx)
            parts = gbl.sphere.snn.pseudojets(ev, gbl.sphere.snn.Select())
            fj = gbl.fastjet
            cs = fj.ClusterSequence(parts, fj.JetDefinition(fj.antikt_algorithm, 0.4))
            jets = fj.sorted_by_pt(cs.inclusive_jets(5.0))
            pts = [jets[k].pt() for k in range(len(jets))]
            refs = [values.get("cpp.chain.jet%d_pt" % k) for k in range(len(pts))]
            if all(r is not None for r in refs) and pts != refs:
                raise AssertionError("jets %r against the C++ program's %r" % (pts, refs))
            return "sphere_snn.hpp through Cling: %d particles -> %d jets%s" % (
                len(parts), len(jets), ", the C++ program's bit for bit" if refs and refs[0] is not None else "")
        attempt("synapse", "hepmc3 > fastjet", "cling", hepmc_cling)

        def pythia():
            gbl = cpp("pythia8")
            xml = os.environ.get("PYTHIA8DATA", "")
            p = gbl.Pythia8.Pythia(xml, False)
            for s in ("Beams:idA = 11", "Beams:idB = -11", "Beams:eCM = 91.1876", "WeakSingleBoson:ffbar2gmZ = on",
                      "PDF:lepton = off", "Next:numberCount = 0", "Print:quiet = on"):
                p.readString(s)
            if not p.init():
                raise AssertionError("Pythia did not initialize")
            n = sum(int(p.next()) for _ in range(3))
            return "Pythia %.3f through Cling: %d e+e- -> Z events" % (p.parm("Pythia:versionNumber"), n)
        attempt("neuron", "pythia8", "cling", pythia)

        def gdml():
            gbl = cpp("geant4", "tgeo")
            import cppyy
            null = cppyy.nullptr
            cm, m_ = gbl.CLHEP.cm, gbl.CLHEP.m
            nist = gbl.G4NistManager.Instance()
            made = []          # Geant4's stores own what is built: Python must not delete it

            def g4(obj):
                obj.__python_owns__ = False
                made.append(obj)
                return obj
            world = g4(gbl.G4LogicalVolume(g4(gbl.G4Box("World", 1 * m_, 1 * m_, 1 * m_)), nist.FindOrBuildMaterial("G4_AIR"), "World"))
            tank = g4(gbl.G4LogicalVolume(g4(gbl.G4Box("Tank", 20 * cm, 30 * cm, 40 * cm)), nist.FindOrBuildMaterial("G4_WATER"), "Tank"))
            g4(gbl.G4PVPlacement(null, gbl.G4ThreeVector(0, 0, 10 * cm), tank, "Tank", world, False, 0))
            top = g4(gbl.G4PVPlacement(null, gbl.G4ThreeVector(), world, "World", null, False, 0))
            geo = g4_to_tgeo(top, os.path.join(odir, "snn_world_py.gdml"))
            t = geo.GetVolume("Tank")
            return "Geant4 -> GDML -> TGeo from Python: Tank of %s, %.0f cm wide" % (t.GetMaterial().GetName(), 2 * t.GetShape().GetDX())
        attempt("synapse", "geant4 > tgeo", "python", gdml)

        def lha():
            lhapdf = use("lhapdf")
            sets = (_HELD.get("lhapdf") or c["neurons"]["lhapdf"]["providers"][0])["extra"].get("sets", [])  # the module's own
            name = ([s for s in sets if "lo" in s.lower()][:1] or sets[:1])
            if not name:
                return (None, "no PDF set installed")
            lhapdf.setVerbosity(0)
            v = lhapdf.mkPDF(name[0], 0).xfxQ(21, 0.1, 100.0)
            ref = values.get("cpp.lhapdf.xg")
            if ref is not None and v != ref:
                raise AssertionError("%r against the C++ program's %r" % (v, ref))
            return "LHAPDF %s from Python: %s x g = %.6f%s" % (lhapdf.version(), name[0], v, ", the C++ program's bit for bit" if ref is not None else "")
        if "lhapdf" in can:
            attempt("neuron", "lhapdf", "python", lha)
    if "python" in langs:
        def chain():
            pyhepmc = use("pyhepmc")
            fastjet = use("fastjet")
            ev = pyhepmc.GenEvent(pyhepmc.Units.GEV, pyhepmc.Units.MM)
            vx = pyhepmc.GenVertex()
            for px, py, pz, e, pid in ref:
                vx.add_particle_out(pyhepmc.GenParticle((px, py, pz, e), int(pid), 1))
            ev.add_vertex(vx)
            parts = pseudojets(ev)
            cs = fastjet.ClusterSequence(parts, fastjet.JetDefinition(fastjet.antikt_algorithm, 0.4))
            jets = fastjet.sorted_by_pt(cs.inclusive_jets(5.0))
            pts = [j.pt() for j in jets]
            refs = [values.get("cpp.chain.jet%d_pt" % k) for k in range(len(pts))]
            if all(r is not None for r in refs) and pts != refs:
                raise AssertionError("jets %r against the C++ program's %r" % (pts, refs))
            return "pyhepmc -> fastjet: %d particles -> %d jets%s" % (len(parts), len(jets),
                                                                       ", the C++ program's bit for bit" if refs and refs[0] is not None else "")
        if "hepmc3" in can and "fastjet" in can:
            attempt("synapse", "hepmc3 > fastjet", "python", chain)

        jets_file = os.path.join(odir, "snn_jets.root")

        def root_read():
            ROOT = use("root")
            f = ROOT.TFile.Open(jets_file)
            t = f.Get("jets")
            n = t.GetEntries()
            t.GetEntry(0)
            pts = list(t.pt)
            f.Close()
            return "PyROOT reads the C++ program's jets: %d entries, leading pt %.6f" % (n, pts[0])
        if os.path.isfile(jets_file) and "root" in can:
            attempt("synapse", "fastjet > root", "python", root_read)

        def to_tensors():
            torch = use("torch")
            tf = use("tensorflow")
            jax = use("jax")
            a = root_to_torch(jets_file, "jets", ["pt", "eta", "phi", "m", "msd"])
            b = root_to_tf(jets_file, "jets", ["pt", "eta", "phi", "m", "msd"])
            j = root_to_jax(jets_file, "jets", ["pt", "eta", "phi", "m", "msd"])
            s1, s2, s3 = float(a.sum()), float(tf.reduce_sum(b)), float(j.sum())
            if not (near(s1, s2) and near(s1, s3)):        # each framework sums in its own order: the last bits may differ
                raise AssertionError("sums %r %r %r" % (s1, s2, s3))
            import numpy as np
            if not (np.array_equal(a.numpy(), b.numpy()) and np.array_equal(a.numpy(), np.asarray(j))):
                raise AssertionError("the three tensors do not hold the same values")
            return a, b, j, s1
        if os.path.isfile(jets_file):
            got = {}

            def r2t():
                a, b, j, s = to_tensors()
                got.update(a=a, b=b, j=j)
                return "the C++ program's tree as torch %s, tensorflow %s and jax %s, sums equal (%.6f)" % (
                    tuple(a.shape), tuple(b.shape), tuple(j.shape), s)
            if attempt("synapse", "root > torch", "python", r2t):
                add("synapse", "root > tensorflow", "python", True, "the same tree, the same sum")
                add("synapse", "root > jax", "python", True, "the same tree, the same sum")
                add("synapse", "root <> uproot", "python", True, "uproot read what ROOT's C++ wrote")

                def dl():
                    import numpy as np
                    a = got["a"]
                    t1 = tensor(a, "tensorflow")
                    j1 = tensor(a, "jax")
                    back = tensor(tensor(t1, "jax"), "torch")
                    if not np.array_equal(np.asarray(back), a.numpy()) or not np.array_equal(np.asarray(j1), a.numpy()):
                        raise AssertionError("values changed on the way")
                    return "torch -> tensorflow -> jax -> torch, every value the same"
                if attempt("synapse", "torch <> tensorflow", "python", dl):
                    add("synapse", "torch <> jax", "python", True, "within the same round trip")
                    add("synapse", "tensorflow <> jax", "python", True, "within the same round trip")

        def pyhf_syn():
            ROOT = use("root")
            hs, hb, hn = (ROOT.TH1D("snn_s", "", 2, 0, 2), ROOT.TH1D("snn_b", "", 2, 0, 2), ROOT.TH1D("snn_n", "", 2, 0, 2))
            for k, (s, b, db, n) in enumerate(((5.0, 50.0, 7.0, 53.0), (10.0, 60.0, 8.0, 65.0))):
                hs.SetBinContent(k + 1, s)
                hb.SetBinContent(k + 1, b)
                hb.SetBinError(k + 1, db)
                hn.SetBinContent(k + 1, n)
            r = hist_to_pyhf(hs, hb, hn)
            pyhf = use("pyhf")
            model = pyhf.simplemodels.uncorrelated_background(signal=[5.0, 10.0], bkg=[50.0, 60.0], bkg_uncertainty=[7.0, 8.0])
            direct = float(pyhf.infer.hypotest(1.0, [53.0, 65.0] + model.config.auxdata, model))
            if r["cls_obs"] != direct:
                raise AssertionError("%r against %r" % (r["cls_obs"], direct))
            return "TH1s -> pyhf: CLs %.6f, pyhf's own" % r["cls_obs"]
        if "root" in can and "pyhf" in can:
            attempt("synapse", "root > pyhf", "python", pyhf_syn)

        def awk():
            ak = use("awkward")
            fastjet = use("fastjet")
            events = ak.Array([[{"px": p[0], "py": p[1], "pz": p[2], "E": p[3]} for p in ref[:-1]]] * 2)
            cs = fastjet.ClusterSequence(events, fastjet.JetDefinition(fastjet.antikt_algorithm, 0.4))
            jets = cs.inclusive_jets(min_pt=5.0)
            return "2 jagged events clustered at once: %s jets" % list(ak.num(jets))
        if "awkward" in can and "fastjet" in can:
            attempt("synapse", "awkward > fastjet", "python", awk)

        def upawk():
            uproot = use("uproot")
            with uproot.open(jets_file) as f:
                arr = f["jets"].arrays()
            return "uproot -> awkward: %d entries, fields %s" % (len(arr), ", ".join(arr.fields))
        if os.path.isfile(jets_file) and "uproot" in can:
            attempt("synapse", "uproot > awkward", "python", upawk)

        def vec():
            ROOT = use("root")
            vector = use("vector")
            a = vector.obj(px=3.0, py=4.0, pz=12.0, E=20.0)
            b = ROOT.Math.PxPyPzEVector(3.0, 4.0, 12.0, 20.0)
            if not near(a.mass, b.M()) or not near(a.eta, b.Eta()):
                raise AssertionError("%r %r" % (a.mass, b.M()))
            return "vector and GenVector agree: m %.12f, eta %.12f" % (a.mass, a.eta)
        if "vector" in can and "root" in can:
            attempt("synapse", "vector <> genvector", "python", vec)

        def minuit():
            ROOT = use("root")
            iminuit = use("iminuit")

            def fcn(x, y):
                return (x - 1.25) ** 2 + 3.0 * (y + 0.5) ** 2 + 0.25 * x * y
            m = iminuit.Minuit(fcn, x=0.0, y=0.0)
            m.migrad()
            mini = ROOT.Math.Factory.CreateMinimizer("Minuit2", "Migrad")

            def on_vector(v):          # held here for as long as Minuit2 calls it
                return fcn(v[0], v[1])
            f = ROOT.Math.Functor(on_vector, 2)
            mini.SetFunction(f)
            mini.SetVariable(0, "x", 0.0, 0.1)
            mini.SetVariable(1, "y", 0.0, 0.1)
            mini.SetTolerance(1e-6)
            mini.Minimize()
            x2, y2 = mini.X()[0], mini.X()[1]
            if not (abs(m.values["x"] - x2) < 1e-4 and abs(m.values["y"] - y2) < 1e-4):
                raise AssertionError("iminuit (%r, %r) against ROOT's Minuit2 (%r, %r)" % (m.values["x"], m.values["y"], x2, y2))
            return "iminuit and ROOT's Minuit2 find the same minimum: (%.6f, %.6f)" % (x2, y2)
        if "iminuit" in can and "root" in can:
            attempt("synapse", "iminuit <> minuit2", "python", minuit)

        hepmc_file = os.path.join(odir, "snn_pythia.hepmc")

        def pythia_py():
            pyhepmc = use("pyhepmc")
            fastjet = use("fastjet")
            n, jets_n, finals, sum_e = 0, 0, 0, 0.0
            with pyhepmc.open(hepmc_file) as f:
                for ev in f:
                    for p in ev.particles:          # in the file's order: what Java's reader is held to
                        if p.status == 1:
                            finals += 1
                            sum_e += p.momentum.e
                    parts = pseudojets(ev)
                    cs = fastjet.ClusterSequence(parts, fastjet.JetDefinition(fastjet.ee_genkt_algorithm, 0.7, -1.0))
                    jets_n += len(cs.inclusive_jets(2.0))
                    n += 1
            out["values"].update({"python.hepmc.n": n, "python.hepmc.nfinal": finals, "python.hepmc.sumE": sum_e})
            return "the C++ Pythia's HepMC3 events read by pyhepmc and clustered by fastjet: %d events, %d jets" % (n, jets_n)
        if os.path.isfile(hepmc_file) and "hepmc3" in can and "fastjet" in can:
            attempt("synapse", "pythia8 > hepmc3", "python", pythia_py)

        yoda_file = os.path.join(odir, "snn_rivet.yoda")

        def yoda_root():
            target = os.path.join(odir, "snn_yoda.root")
            written = yoda_to_root(yoda_file, target)
            ROOT = use("root")
            f = ROOT.TFile.Open(target)
            for name, (sumw, sumw2) in written.items():
                h = f.Get(name)
                got = [h.GetBinContent(i + 1) for i in range(h.GetNbinsX())]
                err2 = [h.GetBinError(i + 1) ** 2 for i in range(h.GetNbinsX())]
                if got != [float(x) for x in sumw] or not all(near(a, b) for a, b in zip(err2, sumw2)):
                    raise AssertionError("%s: ROOT read %r for YODA's %r" % (name, got, list(sumw)))
            f.Close()
            return "Rivet's %d YODA objects written as ROOT histograms by uproot, read back by ROOT: the same contents" % len(written)
        if os.path.isfile(yoda_file) and "yoda" in can and "root" in can and "uproot" in can:
            attempt("synapse", "yoda > root", "python", yoda_root)

        def pyg4():
            import pyg4ometry
            g = pyg4ometry.geant4
            reg = g.Registry()
            wl = g.LogicalVolume(g.solid.Box("WorldBox", 2000, 2000, 2000, reg), "G4_AIR", "World", reg)
            tl = g.LogicalVolume(g.solid.Box("TankBox", 400, 600, 800, reg), "G4_WATER", "Tank", reg)
            g.PhysicalVolume([0, 0, 0], [0, 0, 100], tl, "TankPV", wl, reg)
            reg.setWorld(wl.name)
            path = os.path.join(odir, "snn_pyg4ometry.gdml")
            w = pyg4ometry.gdml.Writer()
            w.addDetector(reg)
            w.write(path)
            gbl = cpp("geant4")
            parser = gbl.G4GDMLParser()
            parser.__python_owns__ = False
            parser.Read(path, False)
            lv = parser.GetWorldVolume().GetLogicalVolume()
            if lv.GetNoDaughters() != 1:
                raise AssertionError("Geant4 read %d daughters" % lv.GetNoDaughters())
            d = lv.GetDaughter(0)
            return "pyg4ometry wrote GDML, Geant4 read it: %s holds %s of %s" % (
                lv.GetName(), d.GetName(), d.GetLogicalVolume().GetMaterial().GetName())
        if "pyg4ometry" in can and "geant4" in can:
            attempt("synapse", "pyg4ometry > geant4", "python", pyg4)

        hw_file = os.path.join(odir, "snn_herwig.hepmc")

        def herwig_py():
            pyhepmc = use("pyhepmc")
            fastjet = use("fastjet")
            n = jets = parts = 0
            with pyhepmc.open(hw_file) as f:
                for ev in f:
                    ps = pseudojets(ev)
                    parts += len(ps)
                    cs = fastjet.ClusterSequence(ps, fastjet.JetDefinition(fastjet.ee_genkt_algorithm, 0.7, -1.0))
                    jets += len(cs.inclusive_jets(2.0))
                    n += 1
            if n == 0:
                raise AssertionError("no event read")
            return "Herwig's events read by pyhepmc: %d events, %d visible particles, %d jets by FastJet" % (n, parts, jets)
        if os.path.isfile(hw_file) and "hepmc3" in can and "fastjet" in can:
            attempt("synapse", "herwig > hepmc3", "python", herwig_py)

        dl_file = os.path.join(odir, "snn_delphes.root")

        def delphes_py():
            uproot = use("uproot")
            with uproot.open(dl_file) as f:
                t = f["Delphes"]
                n = t.num_entries
                tops = [k for k in t.keys(recursive=False)]
                size = [k for k in t.keys() if k.endswith("Jet_size")]
                jets = int(t[size[0]].array(library="np").sum()) if size else -1
            return "Delphes's tree read by uproot: %d events, %d jets, branches %s" % (n, jets, ", ".join(tops[:8]))
        if os.path.isfile(dl_file) and "uproot" in can:
            attempt("synapse", "delphes > root", "python", delphes_py)

        lhe_file, mg_py8 = os.path.join(odir, "snn_mg5.lhe"), os.path.join(odir, "snn_mg5_py8.hepmc")

        def mg5_py():
            import pylhe
            events = list(pylhe.LHEFile.fromfile(lhe_file).events)
            muons = sum(1 for e in events for p in e.particles if abs(int(p.id)) == 13 and int(p.status) == 1)
            n = finals = 0
            if os.path.isfile(mg_py8):
                pyhepmc = use("pyhepmc")
                with pyhepmc.open(mg_py8) as f:
                    for ev in f:
                        n += 1
                        finals += sum(1 for p in ev.particles if p.status == 1)
            return "MadGraph's %d LHE events (%d muons) read by pylhe; C++ Pythia's shower of them read by pyhepmc: %d events, %d final particles" % (
                len(events), muons, n, finals)
        if os.path.isfile(lhe_file) and "pylhe" in can:
            attempt("synapse", "madgraph > pythia8", "python", mg5_py)

    with open(dest, "w", encoding="utf-8") as fh:
        json.dump(out, fh)
    return 0


def _check_graphs(dest):
    """Sphere's portable graphs, in a process of their own (torch.export and a SavedModel's export want room): a PyTorch
    network and a Keras/TensorFlow one made graphs, run by numpy and held to their framework; the graph and its
    numbers left for C++ (sphere_graph.hpp) and Java (NeuralGraph)."""
    import numpy as np
    out = {"results": [], "values": {}}
    odir = os.environ.get("SNN_OUT", ".")
    if lib_folder() not in sys.path:
        sys.path.insert(0, lib_folder())

    def attempt(ident, fn):
        t0 = time.time()
        try:
            r = fn()
            out["results"].append({"kind": "synapse", "id": ident, "lang": "python", "ok": True, "detail": r, "seconds": round(time.time() - t0, 2)})
        except SnnError as e:
            out["results"].append({"kind": "synapse", "id": ident, "lang": "python", "ok": None, "detail": "not here: %s" % e, "seconds": 0})
        except Exception as e:
            out["results"].append({"kind": "synapse", "id": ident, "lang": "python", "ok": False,
                                   "detail": ("%s: %s" % (type(e).__name__, e))[:300], "seconds": round(time.time() - t0, 2)})
        print("%s %s" % (ident, out["results"][-1]["detail"]), flush=True)
        with open(dest, "w", encoding="utf-8") as fh:
            json.dump(out, fh)

    def first(got):
        return np.asarray(got[0] if isinstance(got, (list, tuple)) else next(iter(got.values())) if isinstance(got, dict) else got)

    def graph_torch():
        torch = use("torch")
        import sphere_graph as sg
        torch.manual_seed(7)
        model = torch.nn.Sequential(torch.nn.Linear(5, 8), torch.nn.Tanh(), torch.nn.Linear(8, 3)).double().eval()
        x = torch.linspace(-1.0, 1.0, 20, dtype=torch.float64).reshape(4, 5)
        g = sg.export(model, (x,), "snn_graph", folder=odir)
        ref = model(x).detach().numpy()
        got = first(sg.run(g, [x.numpy()]))
        worst = float(np.max(np.abs(got - ref)))
        if worst > 1e-12 * max(1.0, float(np.max(np.abs(ref)))):
            raise AssertionError("numpy's run of the graph is %.3g from PyTorch's" % worst)
        with open(os.path.join(odir, "snn_graph_io.txt"), "w", encoding="utf-8") as fh:
            fh.write("%d %d\n%s\n%s\n" % (x.shape[0], x.shape[1], " ".join("%.17g" % v for v in x.numpy().ravel()),
                                          " ".join("%.17g" % v for v in got.ravel())))
        out["values"]["python.graph.y"] = [float(v) for v in got.ravel()]
        return "a PyTorch network exported as Sphere's portable graph: numpy's run %s PyTorch's" % (
            "equals" if worst == 0 else "is within %.1e of" % worst)
    attempt("sphere-nn <> torch", graph_torch)

    def graph_tf():
        os.environ["KERAS_BACKEND"] = "tensorflow"     # a SavedModel is TensorFlow's: Keras exports it on that backend
        keras = use("keras")
        import sphere_graph as sg
        import sphere_tf
        keras.utils.set_random_seed(7)
        model = keras.Sequential([keras.Input((5,)), keras.layers.Dense(8, activation="tanh"), keras.layers.Dense(3)])
        saved = os.path.join(odir, "snn_tf_model")
        shutil.rmtree(saved, ignore_errors=True)
        model.export(saved)
        g = sphere_tf.load(saved, name="snn_tf", folder=odir)
        x = np.linspace(-1.0, 1.0, 20).reshape(4, 5).astype(np.float32)
        ref = np.asarray(model(x))
        got = first(sg.run(g, [x.astype(np.float64)]))
        worst = float(np.max(np.abs(got - ref)))
        if worst > 1e-5 * max(1.0, float(np.max(np.abs(ref)))):
            raise AssertionError("the graph is %.3g from TensorFlow (float32)" % worst)
        return "a Keras/TensorFlow SavedModel read as Sphere's graph without TensorFlow: within %.1e of TensorFlow's float32" % worst
    attempt("sphere-nn <> tensorflow", graph_tf)
    return 0


def _check_windows(c, langs, add, report, progress):
    """From Windows: Julia (its jets held to FastJet's, in WSL, through the gateway) and the gateway itself."""
    jl = next((p for p in c["neurons"].get("julia", {}).get("providers", [])), None)
    os.environ.setdefault("SPHERE_BRIDGE", bridge_folder())
    os.environ.setdefault("SPHERE_MLINK", os.path.join(bridge_folder(), "mlink"))
    if "gateway" in langs:
        progress("The gateway, from Windows to the neurons of WSL:")
        t0 = time.time()
        try:
            sys.path.insert(0, lib_folder())
            import sphere_mlink
            link = sphere_mlink.link()
            v = link.eval("ROOT.gROOT.GetVersion()")
            add("synapse", "mlink > root", "gateway", True, "Windows' Python asked, ROOT %s answered from WSL" % v, t0)
            parts = [list(p[:4]) for p in reference_particles()[:-1]]
            import numpy as np
            pts = link.eval("[j.pt() for j in fastjet.sorted_by_pt(fastjet.ClusterSequence([fastjet.PseudoJet(*map(float, r)) for r in x], "
                            "fastjet.JetDefinition(fastjet.antikt_algorithm, 0.4)).inclusive_jets(5.0))]", x=np.asarray(parts))
            report["values"]["gateway.fastjet_pts"] = [float(x) for x in np.asarray(pts).ravel()]
            add("synapse", "mlink > fastjet", "gateway", True, "FastJet in WSL clustered Windows' particles: %d jets" % len(report["values"]["gateway.fastjet_pts"]))
        except Exception as e:
            add("synapse", "mlink > root", "gateway", False, "%s: %s" % (type(e).__name__, e), t0)
        t0 = time.time()
        try:                       # what 'import ROOT' gives a Python without ROOT: a module whose objects live in WSL
            R = remote("ROOT")
            h = R.TH1D("snn_remote", "", 10, 0.0, 1.0)
            for x in (0.15, 0.25, 0.25, 0.85):
                h.Fill(x)
            mean, n = float(h.GetMean()), int(h.GetEntries())
            add("synapse", "mlink > root", "python", abs(mean - 0.375) < 1e-12 and n == 4,
                "Windows' Python made and filled a TH1D that lives in WSL (a remote module): %d entries, mean %.4f, %.0f ms a call"
                % (n, mean, 1000 * (time.time() - t0) / 8), t0)
        except Exception as e:
            add("synapse", "mlink > root", "python", False, "the remote module: %s: %s" % (type(e).__name__, e), t0)
    if "julia" in langs and jl:
        progress("Julia (%s):" % jl["extra"].get("exe"))
        script = os.path.join(_cache_folder(), "check.jl")
        with open(script, "w", encoding="utf-8") as fh:
            fh.write('include(raw"%s")\nusing .SphereSNN\nSphereSNN.check()\n' % os.path.join(lib_folder(), "SphereSNN.jl"))
        t0 = time.time()
        env = dict(os.environ, SPHERE_SNN=network_folder(), SPHERE_BRIDGE=bridge_folder())
        try:
            p = subprocess.run([jl["extra"]["exe"], "--startup-file=no", script], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               timeout=1800, env=sandbox_env(env))
            text = p.stdout.decode("utf-8", "replace")
        except (OSError, subprocess.SubprocessError) as e:
            text = "SNN julia FAIL %s" % e
        results, _ = _parse(text)
        for ident, status, detail in results:
            add("synapse" if (">" in ident or "<>" in ident) else "neuron", ident, "julia",
                True if status == "ok" else None if status == "skip" else False, detail, time.time() - t0)
        if not results:
            add("neuron", "julia", "julia", False, (text.strip().splitlines() or ["no output"])[-1][:300], time.time() - t0)


# ===========================================================================
# Reading the network back
# ===========================================================================

def route(c, a, b):
    """The synapses that carry data from neuron a to neuron b: the shortest chain."""
    a, b = canonical(a), canonical(b)
    edges = {}
    for s in SYNAPSES:
        edges.setdefault(s["from"], []).append((s["to"], s))
        if s["both"]:
            edges.setdefault(s["to"], []).append((s["from"], s))
    for part in ROOT_PARTS:
        edges.setdefault("root", []).append((part, {"id": "root > " + part, "what": "a part of ROOT", "how": {"cpp": "-l" + ROOT_PARTS[part][0][0]}}))
        edges.setdefault(part, []).append(("root", {"id": part + " > root", "what": "a part of ROOT", "how": {}}))
    prev, todo = {a: None}, [a]
    while todo:
        x = todo.pop(0)
        if x == b:
            break
        for y, s in edges.get(x, []):
            if y not in prev:
                prev[y] = (x, s)
                todo.append(y)
    if b not in prev:
        return None
    chain = []
    while prev[b] is not None:
        x, s = prev[b]
        chain.append(s)
        b = x
    return chain[::-1]


def matrix_text(c=None, report=None):
    c = c or detect(local=True)
    if report is None:
        try:
            with open(os.path.join(c.get("folder") or folder(), "matrix.json"), encoding="utf-8") as fh:
                report = json.load(fh)
        except (OSError, ValueError):
            report = {"results": [], "together": {}}
    lines = ["Sphere Neural Network -- %s%s" % (c["side"], (", checked %s in %.0f s" % (
        time.strftime("%Y-%m-%d %H:%M", time.localtime(report["made"])), report.get("seconds", 0))) if report.get("made") else ", not checked yet (snn check)")]
    by = {}
    for r in report["results"]:
        by.setdefault((r["id"], r["lang"]), r)
    langs = ["cpp", "cling", "python", "julia", "java", "tool"]
    lines.append("")
    lines.append("Neurons            %-7s%-7s%-7s%-7s%-7s%-7s %-22s %s" % ("C++", "Cling", "Python", "Julia", "Java", "Tool",
                                                                       "version", "installation"))
    for g in GROUPS:
        for n in sorted(k for k in c["neurons"] if NEURONS.get(k, {}).get("group") == g):
            info = c["neurons"][n]
            p = info["providers"][0]
            have = {l for q in info["providers"] for l in q["langs"]}
            cells = []
            for l in langs:
                r = by.get((n, l))
                if r is not None:
                    cells.append("ok" if r["ok"] else "--" if r["ok"] is None else "FAIL")
                elif l in have:
                    cells.append("yes")
                else:
                    cells.append("")
            extra = " (+%d more)" % (len(info["providers"]) - 1) if len(info["providers"]) > 1 else ""
            lines.append("  %-16s %-7s%-7s%-7s%-7s%-7s%-7s %-22s %s%s" % (n, cells[0], cells[1], cells[2], cells[3], cells[4], cells[5],
                                                                      p["version"][:22], p["family_name"], extra))
    if c.get("remote"):
        for s, r in c["remote"].items():
            lines.append("  in %s: %s" % (s, ", ".join(sorted(r["neurons"]))))
    lines.append("")
    lines.append("Synapses")
    for s in SYNAPSES:
        rs = [r for (i, l), r in by.items() if i == s["id"]]
        status = "  ".join("%s %s" % (r["lang"], "ok" if r["ok"] else "--" if r["ok"] is None else "FAIL") for r in rs) or "not run"
        lines.append("  %-28s %-30s %s" % (s["id"], status, s["what"]))
    lines.append("")
    lines.append("Together in one program")
    for lang, held in sorted(report.get("together", {}).items()):
        lines.append("  %-7s %s" % (lang, " ".join(held)))
    fails = [r for r in report["results"] if r["ok"] is False]
    if fails:
        lines.append("")
        lines.append("What failed")
        for r in fails:
            lines.append("  %-8s %-24s %s" % (r["lang"], r["id"], r["detail"]))
    return "\n".join(lines)


def describe(c):
    lines = ["Sphere Neural Network -- %s (%s); found in %.1f s" % (c["side"], c.get("host", ""), c.get("seconds", 0)),
             "  network Python: %s" % c.get("python")]
    for g in GROUPS:
        names = sorted(k for k in c["neurons"] if NEURONS.get(k, {}).get("group") == g)
        if not names:
            continue
        lines.append("  %s" % g)
        for n in names:
            info = c["neurons"][n]
            for k, p in enumerate(info["providers"]):
                lines.append("    %s %-18s %-24s %-22s %s" % ("*" if k == 0 else " ", n if k == 0 else "", p["version"][:24],
                                                            ",".join(p["langs"]), p["prefix"] or p["origin"]))
    for s, r in (c.get("remote") or {}).items():
        lines.append("  %s (python %s): %s" % (s, r.get("python"), ", ".join("%s %s" % (k, v["version"]) for k, v in sorted(r["neurons"].items()))))
    return "\n".join(lines)


# ===========================================================================
# What the gateway asks
# ===========================================================================

# The functions of this module, by the neuron they need: snn.g4_material in a request sends it where Geant4 is.
NEEDS = {"g4_material": "geant4", "g4_to_tgeo": "geant4", "pseudojets": "fastjet", "jets_to_root": "uproot",
         "root_arrays": "uproot", "_columns": "uproot", "root_to_torch": "torch", "root_to_tf": "tensorflow",
         "root_to_jax": "jax", "hist_to_pyhf": "pyhf"}

# The names the gateway binds, and what each is: a module (use) or a namespace of Cling (cpp).
BINDINGS = {"ROOT": ("root", None), "fastjet": ("fastjet", None), "pyhepmc": ("hepmc3", None), "uproot": ("uproot", None),
            "ak": ("awkward", None), "awkward": ("awkward", None), "vector": ("vector", None), "iminuit": ("iminuit", None),
            "lhapdf": ("lhapdf", None), "yoda": ("yoda", None), "g4": ("geant4", ""), "contrib": ("fjcontrib", "fastjet.contrib"),
            "hepmc3": ("hepmc3", "HepMC3"), "pythia8": ("pythia8", "Pythia8"), "clhep": ("clhep", "CLHEP")}


def binding(name):
    """What the gateway binds to a name a request uses: snn.use(...) for a module, a namespace of Cling for a C++
    neuron (g4 is Cling's global namespace, where Geant4's classes are)."""
    if name == "cppyy":
        use("root")
        import cppyy
        return cppyy
    neuron, space = BINDINGS[name]
    if space is None:
        if name == "pyhepmc":
            import importlib
            return importlib.import_module("pyhepmc")
        return use(neuron)
    gbl = cpp(neuron)
    for part in space.split(".") if space else []:
        gbl = getattr(gbl, part)
    return gbl


def here():
    """The neurons this Python can hold: neuron -> how (from the connectome; nothing imported)."""
    try:
        c = connectome()
    except Exception:
        return {}
    for py in c.get("pythons", []):
        if _same_python(py["python"]):
            return dict(py.get("can", {}))
    return {}


def routes():
    """For each neuron this Python cannot hold, the Python that can, as the command that starts it: what the gateway
    sends a request on to."""
    try:
        c = connectome() if not WINDOWS else detect()
    except Exception:
        return {}
    mine = here()
    out = {}
    for py in c.get("pythons", []):
        for n in py.get("can", {}):
            if n not in mine and n not in out and not _same_python(py["python"]):
                out[n] = [py["python"]]
    for s, r in (c.get("remote") or {}).items():
        if not r.get("python"):
            continue
        for n in r.get("can", {}):
            if n not in mine and n not in out:
                out[n] = ["wsl.exe", "-d", r["distro"], "--", r["python"]]
    return out


# ===========================================================================
# Imports where the neurons are not: the finder of sphere_snn_hook, and modules served by another Python
# ===========================================================================

# Python's names for the neurons, and the neuron each is.
PY_MODULE_NEURONS = {"ROOT": "root", "cppyy": "root", "fastjet": "fastjet", "pyhepmc": "hepmc3", "torch": "torch",
                     "tensorflow": "tensorflow", "keras": "keras", "jax": "jax", "pyhf": "pyhf", "uproot": "uproot",
                     "awkward": "awkward", "vector": "vector", "iminuit": "iminuit", "sklearn": "sklearn",
                     "lhapdf": "lhapdf", "yoda": "yoda", "rivet": "rivet", "numpy": "numpy", "scipy": "scipy",
                     "hist": "hist", "coffea": "coffea", "particle": "particle", "numba": "numba", "pandas": "pandas",
                     "matplotlib": "matplotlib", "mplhep": "mplhep", "pyg4ometry": "pyg4ometry", "pylhe": "pylhe",
                     "zfit": "zfit", "spey": "spey", "onnx": "onnx", "onnxruntime": "onnxruntime",
                     "geant4_pybind": "geant4", "pythia8": "pythia8"}
# The modules served from another Python when this one has none: physics modules a library does not probe for (a
# library trying 'import torch' to know whether it may use it must still be told no).
REMOTE_MODULES = frozenset(("ROOT", "fastjet", "pyhepmc", "lhapdf", "yoda", "rivet", "pythia8", "pylhe", "pyg4ometry"))


def find_spec(name):
    """What sphere_snn_hook asks when every other finder failed: the spec of a neuron's module, from its installation
    when this Python can load it, else a module served by the Python that holds it; None when neither."""
    import importlib.machinery
    import importlib.util
    n = PY_MODULE_NEURONS.get(name)
    if n is None:
        return None
    c = connectome()
    info = c["neurons"].get(n) or {"providers": []}
    mine = [p for p in info["providers"] if "python" in p["langs"] and p["py"] and _importable_here(p)
            and (p.get("module") or n) == name]
    held = _holding()
    for cand in mine:
        try:
            resolve(c, [n], "python", pinned=dict(held, **{n: cand}))
        except SnnError:
            continue
        _apply(cand, "python")
        _HELD[n] = cand
        if n == "root":
            os.environ.setdefault("ROOTSYS", cand["prefix"])
        spec = importlib.machinery.PathFinder.find_spec(name, cand["py"])
        if spec is not None:
            return spec
    if name in REMOTE_MODULES and os.environ.get("SPHERE_SNN_REMOTE", "1") != "0":
        where = routes().get(n)
        if where:
            return importlib.util.spec_from_loader(name, _RemoteLoader(name, where))
    return None


class _RemoteLoader:
    def __init__(self, name, where):
        self.name, self.where = name, where

    def create_module(self, spec):
        return RemoteModule(self.name)

    def exec_module(self, module):
        sys.stderr.write("snn: %s is not in this Python: it is served by %s through the gateway, its objects staying there\n"
                         % (self.name, self.where[-1]))


# What a session of the gateway holds for the objects of this process: each kept under a number, sent back as a handle.
_REMOTE_SETUP = '''
try:
    _snn_r
except NameError:
    import json as _snn_json
    _snn_r, _snn_n = {}, [0]

    def _snn_plain(v):
        return v is None or isinstance(v, (bool, int, float, str))

    def _snn_keep(v):
        try:
            import numpy as _snn_np
            if isinstance(v, _snn_np.ndarray) and v.dtype.kind in "biuf" and v.size:
                return v
            if isinstance(v, _snn_np.generic):
                v = v.item()
        except ImportError:
            pass
        if _snn_plain(v):
            return _snn_json.dumps({"k": "v", "v": v})
        if isinstance(v, (list, tuple)) and all(_snn_plain(x) for x in v):
            return _snn_json.dumps({"k": "v", "v": list(v)})
        _snn_n[0] += 1
        _snn_r[_snn_n[0]] = v
        try:
            r = repr(v)
        except Exception:
            r = object.__repr__(v)
        return _snn_json.dumps({"k": "h", "id": _snn_n[0], "t": type(v).__name__, "r": r[:300]})
'''


class _RemoteSession:
    """The gateway's session where the objects of one remote module live: every request names the module, so that
    the gateway sends it to the Python that holds it, and each object it makes is kept there under a number."""

    _links = {}

    def __init__(self, module):
        self.module = module
        self.session = "snn-%s-%d-%06x" % (module, os.getpid(), int(time.time() * 1000) & 0xffffff)
        self.ready = False
        self.freed = []
        self._link = None

    def link(self):
        """The gateway of the Python that holds the module, spoken to directly: the folder the gateway of this side
        would send the request on to (bridge/mlink/via/...), set up the same way, so one hop instead of two."""
        if self._link is not None:
            return self._link
        if lib_folder() not in sys.path:
            sys.path.insert(0, lib_folder())
        os.environ.setdefault("SPHERE_BRIDGE", bridge_folder())
        os.environ.setdefault("SPHERE_MLINK", os.path.join(bridge_folder(), "mlink"))
        import hashlib
        import sphere_mlink
        command = routes().get(PY_MODULE_NEURONS.get(self.module, ""))
        if not command:
            self._link = sphere_mlink.link()
            return self._link
        sub = os.path.join(sphere_mlink.folder(), "via", hashlib.sha1("\0".join(command).encode("utf-8")).hexdigest()[:12])
        if sub not in _RemoteSession._links:
            if not os.path.isfile(os.path.join(sub, "start.bat" if WINDOWS else "start.sh")):
                script, where = os.path.join(lib_folder(), "sphere_mlink.py"), sub
                if os.path.basename(command[0]).lower().startswith("wsl"):
                    script, where = wsl_path(script), wsl_path(sub)
                os.makedirs(sub, exist_ok=True)
                subprocess.run(command + [script, "--install", where], check=True, timeout=180, stdin=subprocess.DEVNULL,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            _RemoteSession._links[sub] = sphere_mlink.Link(sub, timeout=180.0)
        self._link = _RemoteSession._links[sub]
        return self._link

    def arg(self, v, arrays):
        if isinstance(v, _Remote):
            return object.__getattribute__(v, "_snn_expr")
        if v is None or isinstance(v, (bool, int, float, complex, str, bytes)):
            return repr(v)
        if type(v).__module__ == "numpy" and hasattr(v, "dtype"):
            if not getattr(v, "shape", ()):
                return repr(v.item())
            name = "a%d" % len(arrays)
            arrays[name] = v
            return name
        if isinstance(v, list):
            return "[%s]" % ", ".join(self.arg(x, arrays) for x in v)
        if isinstance(v, tuple):
            return "(%s)" % "".join(self.arg(x, arrays) + ", " for x in v)
        if isinstance(v, dict):
            return "{%s}" % ", ".join("%s: %s" % (self.arg(k, arrays), self.arg(x, arrays)) for k, x in v.items())
        raise TypeError("snn: a %s cannot be sent to %s's Python" % (type(v).__name__, self.module))

    def ask(self, expr, arrays=None):
        head = "import %s\n" % self.module  # the module named: the gateway sends the request to the Python that has it
        if not self.ready:
            head += _REMOTE_SETUP
        if self.freed:
            head += "for _snn_k in (%s,):\n    _snn_r.pop(_snn_k, None)\n" % ", ".join(str(k) for k in self.freed)
            self.freed = []
        value = self.link().eval(head + "_snn_keep(%s)" % expr, session=self.session, **(arrays or {}))
        self.ready = True
        if isinstance(value, str):
            try:
                d = json.loads(value)
            except ValueError:
                return value
            if isinstance(d, dict) and d.get("k") == "v":
                return d["v"]
            if isinstance(d, dict) and d.get("k") == "h":
                return _Remote("_snn_r[%d]" % d["id"], self, d["id"], d.get("t"))
        return value


class _Remote:
    """An object of another Python, named by the expression that reaches it there. Attributes are names further on;
    a call, an index, a comparison or arithmetic is asked of that Python: a number, a string, a list of them or an
    array comes back as itself, anything else as another _Remote. value() asks for what it is."""

    __slots__ = ("_snn_expr", "_snn_s", "_snn_id", "_snn_type", "__weakref__")

    def __init__(self, expr, session, rid=None, kind=None):
        object.__setattr__(self, "_snn_expr", expr)
        object.__setattr__(self, "_snn_s", session)
        object.__setattr__(self, "_snn_id", rid)
        object.__setattr__(self, "_snn_type", kind)

    def _ask(self, expr, arrays=None):
        return object.__getattribute__(self, "_snn_s").ask(expr, arrays)

    def _e(self):
        return object.__getattribute__(self, "_snn_expr")

    def __getattr__(self, attr):
        if attr.startswith("__") and attr.endswith("__"):
            raise AttributeError(attr)
        return _Remote("%s.%s" % (self._e(), attr), object.__getattribute__(self, "_snn_s"))

    def __setattr__(self, attr, value):
        arrays = {}
        s = object.__getattribute__(self, "_snn_s")
        self._ask("setattr(%s, %r, %s)" % (self._e(), attr, s.arg(value, arrays)), arrays)

    def __call__(self, *args, **kwargs):
        arrays = {}
        s = object.__getattribute__(self, "_snn_s")
        parts = [s.arg(a, arrays) for a in args] + ["%s=%s" % (k, s.arg(v, arrays)) for k, v in kwargs.items()]
        return self._ask("%s(%s)" % (self._e(), ", ".join(parts)), arrays)

    def __getitem__(self, key):
        arrays = {}
        return self._ask("%s[%s]" % (self._e(), object.__getattribute__(self, "_snn_s").arg(key, arrays)), arrays)

    def __setitem__(self, key, value):
        arrays = {}
        s = object.__getattribute__(self, "_snn_s")
        self._ask("%s.__setitem__(%s, %s)" % (self._e(), s.arg(key, arrays), s.arg(value, arrays)), arrays)

    def __len__(self):
        return int(self._ask("len(%s)" % self._e()))

    def __iter__(self):
        items = self._ask("list(%s)" % self._e())
        if isinstance(items, _Remote):
            return (items[k] for k in range(len(items)))
        return iter(items)

    def value(self):
        return self._ask(self._e())

    def __repr__(self):
        return str(self._ask("repr(%s)" % self._e()))

    def __str__(self):
        return str(self._ask("str(%s)" % self._e()))

    def __float__(self):
        return float(self._ask("float(%s)" % self._e()))

    def __int__(self):
        return int(self._ask("int(%s)" % self._e()))

    __index__ = __int__

    def __bool__(self):
        return bool(self._ask("bool(%s)" % self._e()))

    def __hash__(self):
        return id(self)

    def __del__(self):
        try:
            rid = object.__getattribute__(self, "_snn_id")
            if rid is not None:
                object.__getattribute__(self, "_snn_s").freed.append(rid)
        except Exception:
            pass

    def _op(self, op, other, reflected=False):
        arrays = {}
        o = object.__getattribute__(self, "_snn_s").arg(other, arrays)
        return self._ask("(%s %s %s)" % ((o, op, self._e()) if reflected else (self._e(), op, o)), arrays)


for _name, _sym in (("add", "+"), ("sub", "-"), ("mul", "*"), ("truediv", "/"), ("floordiv", "//"), ("mod", "%"), ("pow", "**")):
    setattr(_Remote, "__%s__" % _name, (lambda s: lambda self, other: self._op(s, other))(_sym))
    setattr(_Remote, "__r%s__" % _name, (lambda s: lambda self, other: self._op(s, other, True))(_sym))
for _name, _sym in (("eq", "=="), ("ne", "!="), ("lt", "<"), ("le", "<="), ("gt", ">"), ("ge", ">=")):
    setattr(_Remote, "__%s__" % _name, (lambda s: lambda self, other: self._op(s, other))(_sym))
_Remote.__neg__ = lambda self: self._ask("(-%s)" % self._e())


class RemoteModule(__import__("types").ModuleType):
    """A module of another Python -- ROOT in WSL seen from Windows' Python -- through the gateway: what is done with it
    and with what it makes is done there; numbers, strings and arrays come back."""

    def __init__(self, name):
        super().__init__(name)
        object.__setattr__(self, "_snn_session", _RemoteSession(name))

    def __getattr__(self, attr):
        if attr.startswith("__") and attr.endswith("__"):
            raise AttributeError(attr)
        return _Remote("%s.%s" % (self.__name__, attr), object.__getattribute__(self, "_snn_session"))

    def __repr__(self):
        return "<module %r of another Python, through the gateway>" % self.__name__


def remote(name):
    """A module of the Python that holds it, whichever this one is: remote("ROOT").TH1D("h", "h", 10, 0, 1)."""
    return RemoteModule(name)


# ===========================================================================
# snn run: a program given, the network reads what it needs, builds it and runs it where those neurons are
# ===========================================================================

# An #include's path and the neuron it belongs to, the most specific first.
INCLUDE_NEURONS = [
    (r"^fastjet/contrib/", "fjcontrib"), (r"^(fastjet|siscone)/", "fastjet"), (r"^HepMC3/", "hepmc3"),
    (r"^Pythia8(Plugins)?/", "pythia8"), (r"^LHAPDF/", "lhapdf"), (r"^Rivet/", "rivet"), (r"^YODA/", "yoda"),
    (r"^CLHEP/", "clhep"), (r"^G4\w*\.(hh|h|icc)$|^Geant4/", "geant4"), (r"^(torch|ATen|c10)/", "torch"),
    (r"^tensorflow/", "tensorflow"), (r"^(VecGeom|VecCore)/", "vecgeom"),
    (r"^(classes/Delphes|modules/|ExRootAnalysis/)", "delphes"),
    (r"^Math/(Vector|Point|GenVector|LorentzVector|Rotation|Boost|Transform|Plane|Translation|VectorUtil|EulerAngles|Quaternion)", "genvector"),
    (r"^Math/(SpecFuncMathMore|GSL|Polynomial|Interpolator|Vavilov|KelvinFunctions)", "mathmore"),
    (r"^Minuit2/", "minuit2"), (r"^ROOT/(RDataFrame|RDF/|RResultPtr)", "rdataframe"), (r"^ROOT/RNTuple", "rntuple"),
    (r"^TMVA/", "tmva"), (r"^(Roo\w+\.h|RooFit/|RooStats/|HistFactory/)", "roofit"), (r"^(TGeo\w*|TGDML\w*)\.h$", "tgeo"),
    (r"^(T[A-Z]\w*\.h|R\w+\.h|ROOT/|Math/|Fit/)", "root"),
    (r"^sphere_graph\.hpp$", "sphere-nn"), (r"^sphere_mlink\.hpp$", "mlink"),
]
# The names a ROOT macro uses without including anything (ROOT's prompt includes for it).
CODE_NEURONS = [
    (r"\bfastjet::contrib::", "fjcontrib"), (r"\bfastjet::", "fastjet"), (r"\bHepMC3::", "hepmc3"),
    (r"\bPythia8::", "pythia8"), (r"\bLHAPDF::", "lhapdf"), (r"\bRivet::", "rivet"), (r"\bYODA::", "yoda"),
    (r"\bCLHEP::", "clhep"), (r"\bG4[A-Z]\w+", "geant4"), (r"\btorch::", "torch"), (r"\bTF_[A-Z]\w+", "tensorflow"),
    (r"\bRoo[A-Z]\w+", "roofit"), (r"\bTMVA::", "tmva"), (r"\bROOT::Minuit2::", "minuit2"),
    (r"\bROOT::(RDataFrame|RDF::)", "rdataframe"), (r"\bTGeo[A-Z]\w+", "tgeo"),
]


def _strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def needs_of(path, _seen=None):
    """The neurons a program uses, read from its source, and why each: #include lines (the program's own headers
    followed) and, for a macro, the names it uses; import statements and snn.use / snn.cpp for Python; using for
    Julia. Returns (neurons in order, {neuron: what named it})."""
    ext = os.path.splitext(path)[1].lower()
    text = _read(path, 8 << 20)
    found = {}

    def put(n, why):
        if n and n not in found:
            found[n] = why
    if ext in (".py", ".pyw"):
        import ast
        try:
            tree = ast.parse(text)
        except SyntaxError:
            tree = None
        for node in ast.walk(tree) if tree is not None else []:
            if isinstance(node, ast.Import):
                for a in node.names:
                    top = a.name.split(".")[0]
                    put(PY_MODULE_NEURONS.get(top), "import " + a.name)
            elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
                top = node.module.split(".")[0]
                put(PY_MODULE_NEURONS.get(top), "from %s import" % node.module)
            elif isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr in ("use", "cpp", "remote"):
                for a in node.args:
                    if isinstance(a, ast.Constant) and isinstance(a.value, str):
                        put(canonical(a.value) if canonical(a.value) in NEURONS else PY_MODULE_NEURONS.get(a.value),
                            "snn.%s(%r)" % (node.func.attr, a.value))
        return list(found), found
    if ext == ".jl":
        for m in re.finditer(r"^\s*(?:using|import)\s+([\w.,\s:]+)", text, re.M):
            for name in re.split(r"[,\s:]+", m.group(1)):
                if name in JULIA_PACKAGES:
                    put(JULIA_PACKAGES[name], "using " + name)
        return list(found), found
    seen = _seen if _seen is not None else set()
    seen.add(os.path.abspath(path))
    code = _strip_comments(text)
    for inc in re.findall(r'^\s*#\s*include\s*[<"]([^>"]+)[>"]', code, re.M):
        local = os.path.join(os.path.dirname(path), inc)
        if os.path.isfile(local) and os.path.abspath(local) not in seen and len(seen) < 64:
            sub, why = needs_of(local, seen)
            for n in sub:
                put(n, "%s, through %s" % (why[n], inc))
            continue
        for pattern, n in INCLUDE_NEURONS:
            if re.search(pattern, inc):
                put(n, "#include <%s>" % inc)
                break
        else:
            known = (_API.get("idx") or {}).get("headers", {}).get(inc)
            if known:
                put(known, "#include <%s> (API index)" % inc)
    for pattern, n in CODE_NEURONS:
        m = re.search(pattern, code)
        if m:
            put(n, "uses %s" % m.group(0))
    return list(found), found


def _is_macro(path):
    """A ROOT macro: C++ without a main()."""
    return os.path.splitext(path)[1].lower() in (".c", ".cxx", ".cc", ".cpp") and \
        re.search(r"\bint\s+main\s*\(", _strip_comments(_read(path, 8 << 20))) is None


def _other_side_run(c, path, args, what, flags=()):
    """Runs 'sphere_snn.py run' in the WSL distribution that holds what a program needs, the file's path made WSL's."""
    for s, r in (c.get("remote") or {}).items():
        if r.get("distro"):
            cmd = ["wsl.exe", "-d", r["distro"], "--", "env", "SPHERE_SNN=" + wsl_path(network_folder()),
                   "SPHERE_BRIDGE=" + wsl_path(bridge_folder()), "python3", wsl_path(os.path.join(HERE, "sphere_snn.py")),
                   "run"] + list(flags) + [wsl_path(path)] + list(args)
            sys.stderr.write(("snn run: %s needs %s: run in %s\n" % (os.path.basename(path), what, s)) if what != "no neuron" else
                             "snn run: %s names no neuron itself: built in %s, where the healer finds what it misses\n" % (
                                 os.path.basename(path), s))
            sys.stderr.flush()
            return subprocess.call(cmd)
    raise SnnError("%s needs %s, which no side of this machine holds" % (os.path.basename(path), what))


def run_program(path, args=(), plan_only=False, build_only=False):
    """A program run with the neurons it needs, read from its source: C++ compiled (once for a source, flags and
    compiler) with the installations chosen together and run with what it needs set; a ROOT macro given its neurons
    through snn_load.C; Python run by a Python that holds what it imports (the network's import finder on); Julia
    given SphereSNN on its LOAD_PATH. Where this side cannot (C++ from Windows), the other side runs it."""
    import hashlib
    path = os.path.abspath(native(path))
    if not os.path.isfile(path):
        raise SnnError("no file %s" % path)
    ext = os.path.splitext(path)[1].lower()
    needs, why = needs_of(path)
    c = detect(local=not WINDOWS)
    say = sys.stderr.write
    names = ", ".join(needs) or "no neuron"
    if ext in (".py", ".pyw"):
        here = [p for p in c.get("pythons", []) if all(n in p.get("can", {}) for n in needs)]
        here.sort(key=lambda p: p["python"] != c.get("python"))
        if not here and WINDOWS and any(all(n in r.get("can", {}) for n in needs) for r in (c.get("remote") or {}).values()):
            return _other_side_run(c, path, args, names, (["--why"] if plan_only else []) + (["--build-only"] if build_only else []))
        if not here:
            missing = [n for n in needs if not any(n in p.get("can", {}) for p in c.get("pythons", []))]
            raise SnnError("no Python here holds %s (%s)" % (", ".join(needs), "missing: " + ", ".join(missing)))
        py = here[0]["python"]
        say("snn run: %s uses %s -> %s\n" % (os.path.basename(path), names, py))
        if plan_only:
            return 0
        env = dict(os.environ, SPHERE_SNN=network_folder(), SPHERE_BRIDGE=bridge_folder(), SPHERE_BRIDGE_LIB=lib_folder())
        code = subprocess.call([py, os.path.join(lib_folder(), "sphere_snn.py"), "exec", path] + list(args), env=env)
        try:
            taught = derived_names(_read(path, 4 << 20), api_index(c)) if code == 0 else {}
        except Exception:
            taught = {}
        learn(needs, code == 0, "python", [family_root(os.path.join(os.path.dirname(os.path.dirname(py)), "lib", "python3", "site-packages"))],
              (), os.path.basename(path), names=taught)
        return code
    if ext == ".jl":
        jl = next(iter(c["neurons"].get("julia", {}).get("providers", [])), None)
        if jl is None:
            raise SnnError("no Julia on this side")
        was = os.environ.get("JULIA_LOAD_PATH", "")
        shim = os.path.join(folder(), "julia")
        if not os.path.isfile(os.path.join(shim, "SphereSNN.jl")):
            os.makedirs(shim, exist_ok=True)
            _write_text(os.path.join(shim, "SphereSNN.jl"), JULIA_SHIM % os.path.join(lib_folder(), "SphereSNN.jl"))
        env = dict(os.environ, SPHERE_SNN=network_folder(), SPHERE_BRIDGE=bridge_folder(), SPHERE_BRIDGE_LIB=lib_folder(),
                   JULIA_LOAD_PATH=(was if was else "") + os.pathsep + shim)
        say("snn run: %s uses %s -> Julia %s (SphereSNN on its LOAD_PATH)\n" % (os.path.basename(path), names, jl["version"]))
        return 0 if plan_only else subprocess.call([jl["extra"]["exe"], path] + list(args), env=env)
    # C++: a program, or a ROOT macro
    if WINDOWS or not any("cpp" in p["langs"] for info in c["neurons"].values() for p in info["providers"]):
        if WINDOWS and c.get("remote"):
            return _other_side_run(c, path, args, names, (["--why"] if plan_only else []) + (["--build-only"] if build_only else []))
        raise SnnError("no C++ neuron on this side")
    if _is_macro(path):
        root = next(iter(c["neurons"].get("root", {}).get("providers", [])), None)
        if root is None:
            raise SnnError("a ROOT macro, and no ROOT here")
        given = [n for n in needs if n != "root" and NEURONS.get(n, {}).get("parent") != "root"]
        macro = path + ("(%s)" % ", ".join(args) if args else "")
        say("snn run: %s is a ROOT macro using %s -> ROOT %s, given %s\n" % (os.path.basename(path), names, root["version"],
                                                                             " ".join(given) or "nothing more"))
        if plan_only:
            return 0
        loader = os.path.join(c.get("folder") or folder(), "snn_load.C")
        if not os.path.isfile(loader):
            write(c)
        cmd = [os.path.join(root["prefix"], "bin", "root"), "-l", "-b", "-q", "-e", '.x %s("%s")' % (loader, " ".join(given)), macro]
        code = subprocess.call(cmd, env=dict(os.environ, SPHERE_SNN=network_folder(), ROOT_HIST="0"))
        learn(needs, code == 0, "cling", [root["family"]], (), os.path.basename(path))
        return code
    f = flags(c, needs)
    cxx = _compiler(settings())
    sources = [path] + [os.path.join(os.path.dirname(path), inc) for inc in
                        re.findall(r'^\s*#\s*include\s*"([^"]+)"', _read(path), re.M) if os.path.isfile(os.path.join(os.path.dirname(path), inc))]
    key = hashlib.sha1("\0".join([_read(s, 8 << 20) for s in sources] + [cxx] + f["cflags"] + f["libs"]).encode("utf-8")).hexdigest()[:16]
    cache = os.path.join(_cache_folder(), "run")
    os.makedirs(cache, exist_ok=True)
    exe = os.path.join(cache, "%s-%s" % (os.path.splitext(os.path.basename(path))[0], key))
    chosen = ", ".join("%s %s (%s)" % (n, p["version"], p["family_name"]) for n, p in f["chosen"].items())
    say("snn run: %s uses %s -> %s\n" % (os.path.basename(path), names, chosen or "the standard library"))
    for n in needs:
        say("           %-11s %s\n" % (n, why[n]))
    if plan_only:
        say("           g++ %s\n" % " ".join(f["cflags"] + f["libs"]))
        return 0
    healed = exe + ".snn.json"
    if not os.path.isfile(exe):
        t0 = time.time()
        f, heals = heal_build(c, path, needs, cxx, exe, say)
        _write_text(healed, json.dumps({"neurons": list(f["chosen"]), "heals": [h["why"] for h in heals]}))
        say("           built in %.1f s%s\n" % (time.time() - t0, ", healed %d time(s)" % len(heals) if heals else ""))
    else:
        try:
            with open(healed, encoding="utf-8") as fh:
                kept = json.load(fh)
            f = flags(c, kept["neurons"])
            for why in kept.get("heals", []):
                say("           healed     %s (the build kept from before)\n" % why)
        except (OSError, ValueError, KeyError, SnnError):
            pass
        learn(f["chosen"], True, "cpp", [q["family"] for q in f["chosen"].values()], (), os.path.basename(path))
    if build_only:
        print(exe)
        return 0
    env = dict(os.environ, SPHERE_SNN=network_folder(), SPHERE_BRIDGE=bridge_folder(), SPHERE_BRIDGE_LIB=lib_folder())
    for k, v in f["env"].items():
        env[k] = v + (os.pathsep + os.environ[k] if k == "LD_LIBRARY_PATH" and os.environ.get(k) else "")
    sys.stderr.flush()
    return subprocess.call([exe] + list(args), env=env)


# ===========================================================================
# Memory: what the network learned from what it ran
# ===========================================================================
#
# A network that learns. A program the network runs fires its neurons together; when it works, the synapses between
# them are strengthened (Hebb's rule, bounded: w <- w + eta (1 - w)), and every weight fades with a half-life of a
# month, so what is done now counts more than what was done once. The weights say which neurons a program holding
# some of them will likely want next (suggest). Each installation tree keeps, per language, its record of builds that
# linked and builds that did not: a tree that keeps failing is passed over when [SNN] names none (resolve). Each fix
# the healer made is remembered and given to the next program before its first build. All of it in
# bridge/snn/<side>/memory.json, inside Sphere; SNN_LEARN=off in [SNN] stops it.

HALF_LIFE = 30 * 86400.0
ETA = 0.25
_MEMORY = {}


def _memory_path():
    return os.path.join(folder(), "memory.json")


def memory():
    path = _memory_path()
    try:
        st = os.stat(path)
        stamp = (st.st_mtime_ns, st.st_size)
    except OSError:
        stamp = None
    if "m" in _MEMORY and _MEMORY.get("stamp") == stamp:
        return _MEMORY["m"]
    m = {"format": 1, "synapses": {}, "fired": {}, "trust": {}, "heals": {}, "runs": []}
    if stamp:
        try:
            with open(path, encoding="utf-8") as fh:
                got = json.load(fh)
            if got.get("format") == 1:
                m.update(got)
        except (OSError, ValueError):
            pass
    _MEMORY.update(stamp=stamp, m=m)
    return m


def _learning():
    return (settings().get("SNN_LEARN") or "on").strip().lower() not in ("off", "0", "no", "false")


def _faded(e, now=None):
    now = now or time.time()
    return e.get("w", 0.0) * 0.5 ** (max(0.0, now - e.get("t", now)) / HALF_LIFE)


def learn(neurons, ok, lang="cpp", families=(), heals=(), what="", link_failed=False, names=None):
    """One program's lesson: the neurons it fired strengthened together when it worked; its installation trees'
    record kept (a failure counts against them only when it was the link, not the program's own code); the fixes that
    made it build remembered for the next program."""
    if not _learning():
        return
    m = memory()
    now = time.time()
    ns = sorted({n for n in neurons if n})
    for n in ns:
        e = m["fired"].setdefault(n, {"n": 0, "t": now})
        e["n"] += 1
        e["t"] = now
    if ok:
        for i, a in enumerate(ns):
            for b in ns[i + 1:]:
                e = m["synapses"].setdefault(a + "|" + b, {"w": 0.0, "t": now, "n": 0})
                w = _faded(e, now)
                e.update(w=round(w + ETA * (1.0 - w), 6), t=now, n=e.get("n", 0) + 1)
        for h in heals:
            e = m["heals"].setdefault(h["key"], {"n": 0})
            e.update({k: v for k, v in h.items() if k != "why"})
            e["n"] = e.get("n", 0) + 1
        for name, n in (names or {}).items():      # the user's own classes, what the neural model learns next
            m.setdefault("names", {})[name] = n
    if ok or link_failed:
        for f in set(families):
            e = m["trust"].setdefault(lang + "|" + f, {"ok": 0, "fail": 0})
            e["ok" if ok else "fail"] += 1
    m["runs"] = (m.get("runs", []) + [{"t": round(now), "what": what, "lang": lang, "neurons": ns, "ok": bool(ok),
                                       "heals": [h["key"] for h in heals]}])[-300:]
    os.makedirs(folder(), exist_ok=True)
    _write_text(_memory_path(), json.dumps(m, indent=1, sort_keys=True))
    _MEMORY.clear()


def weight(a, b, m=None):
    """The strength of the synapse between two neurons, as the network's runs made it (0 to 1, faded)."""
    e = (m or memory())["synapses"].get("%s|%s" % (min(a, b), max(a, b)))
    return _faded(e) if e else 0.0


def memory_trust(lang):
    """f(tree): 1 for an installation tree whose builds in this language link, -1 for one whose builds keep failing
    to, 0 while the network does not know."""
    rec = memory().get("trust", {})

    def trust(f):
        e = rec.get(lang + "|" + f)
        if not e:
            return 0
        ok, fail = e.get("ok", 0), e.get("fail", 0)
        p = (ok + 1.0) / (ok + fail + 2.0)
        return 1 if p > 0.75 and ok >= 2 else -1 if p < 0.3 and fail >= 2 else 0
    return trust


# ===========================================================================
# The API index: every header, class, namespace and module of every neuron
# ===========================================================================
#
# What the network knows of each neuron is not a list written by hand: it reads the installations themselves. Every
# header of every C++ neuron (ROOT's parts told apart), the classes and namespaces they declare, every Python
# package's modules and public names: bridge/snn/<side>/api.json. From it, any #include, any class used without its
# header, any symbol a link misses and any import is traced to its neuron -- and the neural model learns the spelling
# of every API from it.

HEADER_TOPS = {"torch": ["torch", "ATen", "c10"], "tensorflow": ["tensorflow/c"], "delphes": ["classes", "modules", "ExRootAnalysis"],
               "vecgeom": ["VecGeom", "VecCore"], "fastjet": ["fastjet", "siscone"], "pythia8": ["Pythia8", "Pythia8Plugins"],
               "hepmc3": ["HepMC3"], "lhapdf": ["LHAPDF"], "yoda": ["YODA"], "rivet": ["Rivet"], "clhep": ["CLHEP"],
               "thepeg": ["ThePEG"], "herwig": ["Herwig"], "fjcontrib": ["fastjet/contrib"]}
HEADER_EXT = (".h", ".hh", ".hpp", ".hxx", ".H", ".icc", ".tcc")
EMBEDDED_COPIES = {"Pythia8/FJcore.h", "fastjet/internal/", "Pythia8Plugins/FastJet3.h"}
SKIP_HEADER_DIRS = {"detail", "details", "impl", "internal", "test", "tests", "testing", "python3", "private", "cuda", "hip",
                    "mps", "xpu", "metal", "vulkan", "quantized", "mkldnn", "miopen", "cudnn"}
CLASS_RE = re.compile(r"^[ \t]*(?:template\s*<[^;{]*?>\s*)?(?:class|struct)\s+(?:[A-Z][A-Z0-9_]*\s+)*([A-Za-z_]\w*)\s*(?:final\s*)?"
                      r"(?::[^;{]*)?\{", re.M)
NS_RE = re.compile(r"^[ \t]*namespace\s+([A-Za-z_]\w*)\s*\{", re.M)
CAPI_RE = re.compile(r"\b((?:TF|TFE)_[A-Z]\w+)\s*\(")
ALIAS_RE = re.compile(r"^[ \t]*(?:using\s+([A-Z]\w+)\s*=|typedef\s+[^;{}()]*?\b([A-Z]\w+)\s*;)", re.M)
# Namespaces whose owner is known: FastJet opens its own with a macro, and Delphes carries a copy of it.
KNOWN_NAMESPACES = {"fastjet": "fastjet", "contrib": "fjcontrib", "HepMC3": "hepmc3", "Pythia8": "pythia8", "LHAPDF": "lhapdf",
                    "YODA": "yoda", "Rivet": "rivet", "CLHEP": "clhep", "torch": "torch", "at": "torch", "c10": "torch",
                    "ROOT": "root", "RooFit": "roofit", "RooStats": "roofit", "TMVA": "tmva", "vecgeom": "vecgeom",
                    "ThePEG": "thepeg", "Herwig": "herwig", "TGeo": "tgeo"}
API_FORMAT = 1
_API = {}


def _macro_like(name):
    """ROOT_TH1_H, FASTJET_VERSION: a macro, not a name of an API (TH1D and TF1 are)."""
    return bool(re.match(r"^[A-Z0-9_]+$", name)) and ("_" in name or len(name) > 6)


def _header_list(inc, tops, limit=7000):
    out = []
    for r in ([os.path.join(inc, t) for t in tops] if tops else [inc]):
        if not os.path.isdir(r):
            continue
        for d, dirs, files in os.walk(r):
            dirs[:] = sorted(x for x in dirs if not x.startswith(".") and x not in SKIP_HEADER_DIRS and not x.startswith("python3"))
            for f in sorted(files):
                if f.endswith(HEADER_EXT):
                    out.append(os.path.relpath(os.path.join(d, f), inc).replace("\\", "/"))
                    if len(out) >= limit:
                        return out
    return out


def _owner(n, rel, here):
    """The neuron a header of n's include folder belongs to: ROOT's parts and Geant4's CLHEP told apart."""
    if n in ("root", "geant4"):
        for pattern, m in INCLUDE_NEURONS:
            if re.search(pattern, rel):
                return m if m in here else n
    if n == "fastjet" and rel.startswith("fastjet/contrib/") and "fjcontrib" in here:
        return "fjcontrib"
    return n


def _python_api(n, p, out, cap=700):
    import ast
    pkg = p.get("prefix") or ""
    mod = p.get("module") or n
    out["modules"].setdefault(mod, n)
    names = []
    if os.path.isdir(pkg):
        for e in sorted(os.listdir(pkg))[:400]:
            stem, ext = os.path.splitext(e)
            if e.startswith("_") or e.startswith("."):
                continue
            if ext == ".py" or os.path.isfile(os.path.join(pkg, e, "__init__.py")):
                out["modules"].setdefault(mod + "." + stem, n)
                names.append(stem)
        init = os.path.join(pkg, "__init__.py")
        try:
            tree = ast.parse(_read(init, 1 << 20))
        except (SyntaxError, ValueError):
            tree = None
        for node in (tree.body if tree is not None else []):
            if isinstance(node, (ast.FunctionDef, ast.ClassDef, ast.AsyncFunctionDef)):
                names.append(node.name)
            elif isinstance(node, ast.Assign):
                for t in node.targets:
                    if isinstance(t, ast.Name):
                        names.append(t.id)
                    if isinstance(t, ast.Name) and t.id == "__all__" and isinstance(node.value, (ast.List, ast.Tuple)):
                        names += [x.value for x in node.value.elts if isinstance(x, ast.Constant) and isinstance(x.value, str)]
            elif isinstance(node, ast.ImportFrom):
                names += [a.asname or a.name for a in node.names if a.name != "*"]
    kept = 0
    for name in _uniq(names):
        if name.startswith("_") or not re.match(r"^[A-Za-z]\w+$", name):
            continue
        out["names"].setdefault(name, [])
        if n not in out["names"][name]:
            out["names"][name].append(n)
        kept += 1
        if kept >= cap:
            break


def api_index(c=None, force=False):
    """This side's API index (made again when the installations [SNN] chose changed): headers -> neuron, classes ->
    [neuron, header, namespace], namespaces -> neuron, Python modules and public names -> neurons."""
    c = c or detect(local=True)
    path = os.path.join(c.get("folder") or folder(), "api.json")
    chosen = {}
    try:
        chosen.update(_default_set(c, "cling" if c["neurons"].get("root") else "cpp"))
    except SnnError:
        pass
    for n, info in c["neurons"].items():
        if n not in chosen and info.get("providers"):
            py = c.get("python")
            mine = [p for p in info["providers"] if p.get("python") and py and _decl_norm(p["python"]) == _decl_norm(py)]
            chosen[n] = (mine or info["providers"])[0]
    stamp = sorted("%s=%s@%s" % (n, p.get("prefix"), p.get("version")) for n, p in chosen.items())
    if not force:
        if _API.get("path") == path and _API.get("stamp") == stamp:
            return _API["idx"]
        try:
            with open(path, encoding="utf-8") as fh:
                idx = json.load(fh)
            if idx.get("format") == API_FORMAT and idx.get("stamp") == stamp:
                if not os.path.isfile(os.path.join(os.path.dirname(path), "knows.tsv")):
                    _write_knows(idx, os.path.join(os.path.dirname(path), "knows.tsv"))
                _API.update(path=path, stamp=stamp, idx=idx)
                return idx
        except (OSError, ValueError):
            pass
    t0 = time.time()
    idx = {"format": API_FORMAT, "side": c.get("side"), "stamp": stamp, "headers": {}, "classes": {}, "namespaces": {},
           "modules": {}, "names": {}, "includes": {}}
    lib = os.path.normcase(os.path.abspath(lib_folder()))
    ns_votes = {}
    for n, p in sorted(chosen.items()):
        if not any(l in p.get("langs", []) for l in ("cpp", "cling")) or NEURONS.get(n, {}).get("parent") == "root":
            continue        # ROOT's parts are told apart while ROOT's own folder is read
        tops = HEADER_TOPS.get(n)
        if not tops and n not in ("root", "geant4"):
            tops = _uniq(h.split("/")[0] for h in CLING_HEADERS.get(n, []) + p.get("headers", []) if "/" in h) or None
        for inc in p.get("inc", []):
            if not os.path.isdir(inc) or os.path.normcase(os.path.abspath(inc)) == lib:
                continue
            rels = _header_list(inc, tops)
            if rels:
                idx["includes"].setdefault(n, []).append(inc)
            scanned = 0
            for rel in rels:
                owner = _owner(n, rel, c["neurons"])
                idx["headers"].setdefault(rel, owner)
                if scanned >= 3500 or rel.endswith((".icc", ".tcc")) or any(rel.startswith(e) for e in EMBEDDED_COPIES):
                    continue
                scanned += 1
                text = _read(os.path.join(inc, rel), 192 << 10)
                nss = NS_RE.findall(text)
                ns = next((x for x in nss if x in KNOWN_NAMESPACES), nss[0] if nss else "")
                for x in set(nss):
                    ns_votes.setdefault(x, {}).setdefault(owner, 0)
                    ns_votes[x][owner] += 1
                found = set(CLASS_RE.findall(text)) | {a or b for a, b in ALIAS_RE.findall(text)}
                if n == "tensorflow":
                    found |= set(CAPI_RE.findall(text))
                base = os.path.splitext(os.path.basename(rel))[0]
                if n in ("root", "geant4") and re.match(r"^[A-Z]\w+$", base):
                    found.add(base)        # TH1D.h declares TH1D, G4Box.hh G4Box: what is used is found by its file
                for cls in found:
                    if len(cls) < 3 or _macro_like(cls) or cls.startswith("_"):
                        continue
                    lst = idx["classes"].setdefault(cls, [])
                    if len(lst) < 4 and not any(e[0] == owner for e in lst):
                        lst.append([owner, rel, ns])
    for ns, votes in ns_votes.items():
        if len(ns) >= 2 and ns not in ("std", "detail", "internal", "impl"):
            idx["namespaces"][ns] = max(votes, key=votes.get)
    for ns, n in KNOWN_NAMESPACES.items():
        if n in c["neurons"]:
            idx["namespaces"][ns] = n
    for n, p in sorted(chosen.items()):
        if "python" in p.get("langs", []) and p.get("module"):
            _python_api(n, p, idx)
    for py_mod, n in PY_MODULE_NEURONS.items():
        if n in c["neurons"]:
            idx["modules"].setdefault(py_mod, n)
    idx["seconds"] = round(time.time() - t0, 2)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    _write_text(path, json.dumps(idx, separators=(",", ":")))
    _write_knows(idx, os.path.join(os.path.dirname(path), "knows.tsv"))
    _API.update(path=path, stamp=stamp, idx=idx)
    return idx


def _write_knows(idx, path):
    """knows.tsv: what the index knows, one name a line, for Sphere's C++ mode to tell at once whether a failed build is
    one the network can heal (a header, its bare name, a class, a namespace) -- without reading the JSON."""
    rows, seen = ["# kind\tname\tneuron -- what the network's API index knows (%s)" % idx.get("side")], set()
    for rel, n in sorted(idx.get("headers", {}).items()):
        rows.append("header\t%s\t%s" % (rel, n))
        base = rel.rsplit("/", 1)[-1]
        if ("base", base) not in seen:
            seen.add(("base", base))
            rows.append("base\t%s\t%s" % (base, n))
    for cls, lst in sorted(idx.get("classes", {}).items()):
        rows.append("class\t%s\t%s" % (cls, lst[0][0]))
    for ns, n in sorted(idx.get("namespaces", {}).items()):
        rows.append("ns\t%s\t%s" % (ns, n))
    _write_text(path, "\n".join(rows) + "\n")


def _bases(idx):
    if "_bases" not in idx:
        b = {}
        for rel, n in idx["headers"].items():
            b.setdefault(rel.rsplit("/", 1)[-1], []).append(rel)
        idx["_bases"] = b
    return idx["_bases"]


# ===========================================================================
# The network's own neural network: the spelling of every API, learned
# ===========================================================================
#
# An identifier is read the way the network's neural model reads it -- its character 2-, 3- and 4-grams, with ^ and $
# at its ends, the shape of its letters (TH1D -> X9X), its first two letters -- hashed (FNV-1a) into FEAT_DIM counts
# of unit length. A multilayer perceptron trained on every name of every neuron's API (tens of thousands: ROOT's and
# Geant4's classes, FastJet's, HepMC3's, the Python packages' modules and names) says which neuron an identifier it
# never saw belongs to: G4MyCalorimeter is Geant4's, TH3Poly ROOT's, ClusterSequenceArea FastJet's. Trained with
# PyTorch, exported as a portable graph (bridge/snn/models/snn_api.sng.spx) that Sphere's NeuralGraph runs in Java,
# sphere_graph.hpp in C++ and sphere_graph in numpy; Java's GraphTrainer fine-tunes it on the user's own programs.
# The features are computed the same way in Java (com.sphere.core.snn.SnnModel): the same bytes, the same buckets.

FEAT_DIM = 512
FEATURES = "fnv1a32 ^ngrams$ 2-4, shape x2, prefix2 x1.5, l2; dim 512"


def _fnv(text):
    h = 0x811C9DC5
    for b in text.encode("utf-8"):
        h = ((h ^ b) * 0x01000193) & 0xFFFFFFFF
    return h


def features(token):
    v = [0.0] * FEAT_DIM
    t = "^" + token + "$"
    for k in (2, 3, 4):
        for i in range(len(t) - k + 1):
            v[_fnv(t[i:i + k]) % FEAT_DIM] += 1.0
    shape = re.sub(r"[A-Z]+", "X", re.sub(r"[a-z]+", "x", re.sub(r"[0-9]+", "9", token)))
    v[_fnv("shape:" + shape) % FEAT_DIM] += 2.0
    v[_fnv("pre:" + token[:2]) % FEAT_DIM] += 1.5
    total = 0.0
    for x in v:                 # one sum after another, as Java adds them: not sum(), compensated since Python 3.12
        total += x * x
    norm = math.sqrt(total) if total > 0 else 1.0
    return [x / norm for x in v]


CODE_WORDS = frozenset("""alignas alignof and asm auto bool break case catch char class const constexpr const_cast continue
decltype default delete do double dynamic_cast else enum explicit export extern false float for friend goto if inline
int long mutable namespace new noexcept not nullptr operator or private protected public register reinterpret_cast
return short signed sizeof static static_assert static_cast struct switch template this throw true try typedef typeid
typename union unsigned using virtual void volatile while include define ifdef ifndef endif pragma main argc argv std
cout cerr endl string vector size_t printf print def import from lambda None True False self elif pass with yield
assert global nonlocal raise async await function end module begin let local println length push range len list dict
set tuple str float int bool open close write read append value values data result results size first second auto
const_iterator iterator begin end name type args kwargs""".split())


def code_tokens(text):
    """The identifiers of a source in any language, its keywords and the commonest words left out."""
    text = re.sub(r'"(?:\\.|[^"\\])*"', " ", _strip_comments(text))
    seen, out = set(), []
    for t in re.findall(r"\b[A-Za-z_][A-Za-z0-9_]{2,}\b", text):
        if t not in seen and t not in CODE_WORDS and not _macro_like(t):
            seen.add(t)
            out.append(t)
    return out[:4000]


def _models():
    d = os.path.join(network_folder(), "models")
    return d, os.path.join(d, "snn_api.sng.spx"), os.path.join(d, "snn_api.labels.json")


def _training_rows():
    """(identifier, neuron) for every API of every side the network saw: what the model learns from."""
    rows = set()
    base = network_folder()
    for sd in (os.listdir(base) if os.path.isdir(base) else []):
        try:
            with open(os.path.join(base, sd, "api.json"), encoding="utf-8") as fh:
                idx = json.load(fh)
        except (OSError, ValueError):
            continue
        for cls, lst in idx.get("classes", {}).items():
            own = [e for e in lst if os.path.splitext(e[1].rsplit("/", 1)[-1])[0] == cls
                   or KNOWN_NAMESPACES.get(e[2]) == e[0]] if len(lst) > 1 else lst
            for owner, _, _ in (own or lst):
                rows.add((cls, owner))
        for rel, n in idx.get("headers", {}).items():
            stem = os.path.splitext(rel.rsplit("/", 1)[-1])[0]
            if len(stem) >= 3:
                rows.add((stem, n))
        for name, ns in idx.get("names", {}).items():
            for n in ns:
                rows.add((name, n))
        for mod, n in idx.get("modules", {}).items():
            rows.add((mod.split(".")[-1], n))
        for ns, n in idx.get("namespaces", {}).items():
            rows.add((ns, n))
    return sorted(rows)


def train_model(epochs=24, hidden=256, cap=900, floor=40, seed=7, progress=print):
    """Trains the network's neural model on every API it indexed, with PyTorch, and exports it as a graph Sphere runs
    without any framework. Returns its account (classes, rows, validation accuracy, where it is)."""
    import random
    import numpy as np
    import torch
    if lib_folder() not in sys.path:
        sys.path.insert(0, lib_folder())
    import sphere_graph
    rows = _training_rows()
    if not rows:
        raise SnnError("no API indexed yet: 'snn index' on each side first")
    rng = random.Random(seed)
    by = {}
    for t, n in rows:
        by.setdefault(n, []).append(t)
    classes = sorted(n for n, ts in by.items() if len(ts) >= 3)
    data = []
    for n in classes:
        ts = by[n][:]
        rng.shuffle(ts)
        ts = ts[:cap]
        while len(ts) < floor:              # a neuron of few names is seen as often as one of many, nearly
            ts = ts + ts[:floor - len(ts)]
        data += [(t, classes.index(n)) for t in ts]
    rng.shuffle(data)
    x = np.array([features(t) for t, _ in data], dtype=np.float64)
    y = np.array([k for _, k in data], dtype=np.int64)
    nv = max(1, len(data) // 10)
    torch.manual_seed(seed)
    model = torch.nn.Sequential(torch.nn.Linear(FEAT_DIM, hidden), torch.nn.ReLU(), torch.nn.Linear(hidden, len(classes))).double()
    opt = torch.optim.Adam(model.parameters(), lr=3e-3)
    xt, yt = torch.from_numpy(x[nv:]), torch.from_numpy(y[nv:])
    xv, yv = torch.from_numpy(x[:nv]), torch.from_numpy(y[:nv])
    best, best_state, t0 = -1.0, None, time.time()
    for ep in range(epochs):
        model.train()
        perm = torch.randperm(len(xt), generator=torch.Generator().manual_seed(seed + ep))
        total = 0.0
        for a in range(0, len(xt), 256):
            i = perm[a:a + 256]
            opt.zero_grad()
            loss = torch.nn.functional.cross_entropy(model(xt[i]), yt[i])
            loss.backward()
            opt.step()
            total += float(loss) * len(i)
        model.eval()
        with torch.no_grad():
            out = model(xv)
            acc = float((out.argmax(1) == yv).double().mean())
            acc3 = float((out.topk(3, dim=1).indices == yv[:, None]).any(1).double().mean())
        progress("  epoch %2d  loss %.4f  validation accuracy %.3f, in the first three %.3f" % (ep + 1, total / len(xt), acc, acc3))
        if acc > best:
            best, best3, best_state = acc, acc3, {k: v.clone() for k, v in model.state_dict().items()}
    model.load_state_dict(best_state)
    d, gpath, lpath = _models()
    os.makedirs(d, exist_ok=True)
    graph = sphere_graph.export(model, (torch.from_numpy(x[:2]),), "snn_api", folder=d, trainable=True)
    with torch.no_grad():
        ref = model(torch.from_numpy(x[:64])).numpy()
    got = np.asarray(sphere_graph.run(graph, [x[:64]]))
    account = {"classes": classes, "dim": FEAT_DIM, "features": FEATURES, "rows": len(data), "names": len(rows),
               "hidden": hidden, "epochs": epochs, "accuracy": round(best, 4), "accuracy_top3": round(best3, 4), "seconds": round(time.time() - t0, 1),
               "graph_vs_torch": float(np.max(np.abs(got - ref))), "made": time.time(), "trained_by": "torch " + torch.__version__}
    _write_text(lpath, json.dumps(account, indent=1))
    _write_text(os.path.join(d, "snn_api.classes.txt"), "".join(n + "\n" for n in classes))
    account["path"] = gpath
    return account


def predict_tokens(tokens, top=3):
    """{identifier: [(neuron, probability), ...]} as the neural model reads them (None before it is trained). The
    graph is run by sphere_graph, numpy alone: no framework."""
    d, gpath, lpath = _models()
    if not (os.path.isfile(gpath) and os.path.isfile(lpath)) or not tokens:
        return None
    try:
        import numpy as np
        if lib_folder() not in sys.path:
            sys.path.insert(0, lib_folder())
        import sphere_graph
        with open(lpath, encoding="utf-8") as fh:
            lab = json.load(fh)
        user = os.path.join(d, "snn_api_user.sng.spx")      # the one Java fine-tuned on the user's programs, when newer
        g = sphere_graph.load("snn_api_user" if os.path.isfile(user) and os.path.getmtime(user) > os.path.getmtime(gpath)
                              else "snn_api", d)
        logits = np.asarray(sphere_graph.run(g, [np.array([features(t) for t in tokens], dtype=np.float64)]))
    except Exception:
        return None
    z = np.exp(logits - logits.max(axis=1, keepdims=True))
    pr = z / z.sum(axis=1, keepdims=True)
    out = {}
    for t, row in zip(tokens, pr):
        best = np.argsort(-row)[:top]
        out[t] = [(lab["classes"][k], float(row[k])) for k in best]
    return out


# ===========================================================================
# What a program needs, and what it will need next
# ===========================================================================

def _model_needs(tokens, idx, known, threshold=0.8):
    """Neurons the neural model reads in identifiers no index knows (a class of the user's deriving from G4..., a name
    misspelled), each with its reason."""
    unknown = [t for t in tokens if t not in idx.get("classes", {}) and t not in idx.get("names", {}) and len(t) >= 4][:600]
    got = predict_tokens(unknown, top=1) or {}
    votes = {}
    for t, ((n, p),) in got.items():
        if p >= threshold and n not in known:
            votes.setdefault(n, []).append((p, t))
    return {n: sorted(v, reverse=True)[:3] for n, v in votes.items() if len(v) >= 2 or v[0][0] > 0.95}


def suggest(path_or_names, lang=None, top=6):
    """What a program holds and what it will likely want next: the neurons its source names (index), those the neural
    model reads in its identifiers, and the neighbours its neurons fired with before (Hebbian weights) or reach by a
    synapse -- each with the code that joins them, in the program's language."""
    if isinstance(path_or_names, str) and os.path.isfile(native(path_or_names)):
        path = native(path_or_names)
        ext = os.path.splitext(path)[1].lower()
        lang = lang or ("python" if ext in (".py", ".pyw") else "julia" if ext == ".jl" else "cpp")
        names, why = needs_of(path)
        c = detect(local=not WINDOWS)
        idx = api_index(c) if not WINDOWS or c["neurons"] else {}
        model = _model_needs(code_tokens(_read(path, 4 << 20)), idx, set(names))
    else:
        names, why, model = [canonical(n) for n in path_or_names], {}, {}
    m = memory()
    scores = {}
    have = set(names) | set(model)
    for n in NEURONS:
        if n in have:
            continue
        hebb = sum(weight(a, n, m) for a in have)
        links = [s for s in SYNAPSES if (s["from"] in have and s["to"] == n) or (s["both"] and s["to"] in have and s["from"] == n)]
        score = hebb + 0.25 * len(links)
        if score > 0:
            scores[n] = (score, hebb, links)
    nexts = []
    for n, (score, hebb, links) in sorted(scores.items(), key=lambda kv: -kv[1][0])[:top]:
        nexts.append({"neuron": n, "score": round(score, 3), "hebbian": round(hebb, 3),
                      "synapses": [[s["id"], s["how"].get(lang) or next(iter(s["how"].values()), "")] for s in links]})
    return {"lang": lang, "named": [[n, why.get(n, "")] for n in names],
            "model": [[n, round(v[0][0], 3), [t for _, t in v]] for n, v in model.items()], "next": nexts}


# ===========================================================================
# Healing: a build that fails is read, fixed and tried again
# ===========================================================================

CPP_DIAGNOSES = [
    ("header", re.compile(r"fatal error: ([^\s:'`]+): No such file or directory")),
    ("header", re.compile(r"fatal error: '([^']+)' file not found")),
    ("member", re.compile(r"error: '([A-Za-z_]\w*)' is not a member of '([\w:]+)'")),
    ("member", re.compile(r"error: no (?:type|member) named '([A-Za-z_]\w*)' in namespace '([\w:]+)'")),
    ("ident", re.compile(r"error: '([A-Za-z_]\w*)' (?:was not declared in this scope|does not name a type|has not been declared)")),
    ("ident", re.compile(r"error: (?:unknown type name|use of undeclared identifier) '([A-Za-z_]\w*)'")),
    ("ident", re.compile(r"incomplete type '(?:class |struct )?(?:[\w:]+::)?([A-Za-z_]\w*)'")),
    ("symbol", re.compile(r"undefined reference to [`']([^']+)'")),
    ("symbol", re.compile(r"undefined symbol: (\S+)")),
]


def _symbol_owner(sym, idx, c):
    """The neuron a symbol a link missed belongs to: 'fastjet::ClusterSequence::...', 'TH1::Fill(double)',
    'vtable for G4Box'."""
    s = re.sub(r"^(?:vtable|typeinfo|typeinfo name|VTT|construction vtable) for ", "", sym).split("(")[0]
    while re.search(r"<[^<>]*>", s):
        s = re.sub(r"<[^<>]*>", "", s)
    parts = [x.strip() for x in s.split("::") if x.strip()]
    for x in reversed(parts):           # the most specific first: ROOT::Math::LorentzVector is GenVector's, not ROOT's
        for owner, _, _ in idx.get("classes", {}).get(x, []):
            if owner in c["neurons"]:
                return owner, x
    for x in reversed(parts):
        if x in idx.get("namespaces", {}) and idx["namespaces"][x] in c["neurons"]:
            return idx["namespaces"][x], x
    return None, None


def _pick(cands, names, ns=None):
    """Of the [neuron, header, namespace] a class name may be, the one a program most likely means."""
    if ns:
        top = ns.split("::")[0]
        hit = [e for e in cands if e[2] == top or e[2] == ns]
        if hit:
            cands = hit
    m = memory()
    return max(cands, key=lambda e: (e[0] in names, sum(weight(e[0], n, m) for n in names), -len(e[1])))


def _diagnose(text, c, idx, names, injected, extra):
    """The first thing the network can fix in a failed C++ build: (fix, kind) or (None, kind of the first error)."""
    first = None
    for line in text.splitlines():
        for kind, rx in CPP_DIAGNOSES:
            mt = rx.search(line)
            if not mt:
                continue
            first = first or kind
            if kind == "header":
                h = mt.group(1)
                n = idx["headers"].get(h)
                if n and n in c["neurons"] and n not in names:
                    return {"key": "header:" + h, "neuron": n, "why": "#include <%s>: %s" % (h, NEURONS.get(n, {}).get("title", n))}, kind
                if not n:
                    tail = [r for r in _bases(idx).get(h.rsplit("/", 1)[-1], []) if r.endswith("/" + h)]
                    if tail:
                        rel = min(tail, key=len)
                        n = idx["headers"][rel]
                        # the folder of the installation the program is built with, never a copy another carries
                        for inc in idx.get("includes", {}).get(n, []):
                            if os.path.isfile(os.path.join(inc, rel)):
                                d = os.path.join(inc, rel[:len(rel) - len(h)].rstrip("/"))
                                if d not in extra:
                                    return {"key": "header:" + h, "neuron": n, "dir": d,
                                            "why": '#include "%s" is %s: -I%s' % (h, rel, d)}, kind
            elif kind in ("ident", "member"):
                x = mt.group(1)
                ns = mt.group(2) if kind == "member" else None
                cands = [e for e in idx.get("classes", {}).get(x, []) if e[0] in c["neurons"]]
                owner = idx.get("namespaces", {}).get(x)
                if not cands and owner in c["neurons"] and CLING_HEADERS.get(owner):
                    # a namespace used with nothing of it included (fastjet::PseudoJet alone): its first header, then
                    # whatever the next build still misses
                    header = next((h for h in CLING_HEADERS[owner] if h in idx["headers"]), None)
                    if header and header not in injected:
                        return {"key": "ident:" + x, "neuron": owner, "header": header,
                                "why": "namespace %s used with nothing of it included: -include %s" % (x, header)}, kind
                if cands:
                    owner, header, _ = _pick(cands, names, ns)
                    if header not in injected:
                        return {"key": "ident:" + x, "neuron": owner, "header": header,
                                "why": "%s%s used without its header: -include %s (%s)" % (ns + "::" if ns else "", x, header,
                                                                                        NEURONS.get(owner, {}).get("title", owner))}, kind
            elif kind == "symbol":
                owner, what = _symbol_owner(mt.group(1), idx, c)
                if owner and owner not in names:
                    return {"key": "symbol:" + what, "neuron": owner,
                            "why": "the link missed %s: %s's libraries" % (what, NEURONS.get(owner, {}).get("title", owner))}, kind
    return None, first


DERIVED_CPP = re.compile(r"\b(?:class|struct)\s+([A-Za-z_]\w*)\s*(?:final\s*)?:\s*(?:(?:public|protected|private|virtual)\s+)*([\w:]+)")
DERIVED_PY = re.compile(r"^\s*class\s+([A-Za-z_]\w*)\s*\(\s*([\w.]+)", re.M)


def derived_names(src, idx):
    """The user's own classes deriving from a neuron's: MyDetector : public G4VUserDetectorConstruction is Geant4's,
    class Net(torch.nn.Module) PyTorch's. What the neural model learns from the user's programs."""
    out = {}
    for name, base in DERIVED_CPP.findall(src) + DERIVED_PY.findall(src):
        b = re.split(r"::|\.", base)[-1]
        hit = [e[0] for e in idx.get("classes", {}).get(b, [])] + list(idx.get("names", {}).get(b, []))
        if not hit and "." in base:
            hit = [idx.get("modules", {}).get(base.split(".")[0])] if idx.get("modules", {}).get(base.split(".")[0]) else []
        if hit and not _macro_like(name):
            out[name] = hit[0]
    return out


def learn_data(per_class=40, repeat=20, seed=11):
    """The dataset Sphere's trainer fine-tunes the neural model on (models/snn_api_user.data.spx): the names the user's
    programs gave the network -- their classes deriving from a neuron's, the identifiers the healer traced -- each seen
    often, and a sample of every neuron's API, so that nothing learned before is forgotten."""
    import random
    import numpy as np
    if lib_folder() not in sys.path:
        sys.path.insert(0, lib_folder())
    from sphere_ml import write_spx
    d, gpath, lpath = _models()
    try:
        with open(os.path.join(d, "snn_api.classes.txt"), encoding="utf-8") as fh:
            classes = [l.strip() for l in fh if l.strip()]
    except OSError:
        raise SnnError("the neural model is not trained yet ('snn train')")
    mine = {}
    base = network_folder()
    for sd in sorted(os.listdir(base)) if os.path.isdir(base) else []:
        try:
            with open(os.path.join(base, sd, "memory.json"), encoding="utf-8") as fh:
                m = json.load(fh)
        except (OSError, ValueError):
            continue
        mine.update(m.get("names", {}))
        for key, h in m.get("heals", {}).items():
            if key.startswith("ident:") and h.get("neuron"):
                mine[key[6:]] = h["neuron"]
    rows = [(t, n) for t, n in sorted(mine.items()) if n in classes]
    if not rows:
        raise SnnError("the user's programs taught the network nothing yet: run some ('snn run')")
    rng = random.Random(seed)
    by = {}
    for t, n in _training_rows():
        if n in classes:
            by.setdefault(n, []).append(t)
    sample = []
    for n, ts in sorted(by.items()):
        rng.shuffle(ts)
        sample += [(t, n) for t in ts[:per_class]]
    data = rows * repeat + sample
    rng.shuffle(data)
    x = np.array([features(t) for t, _ in data], dtype=np.float64)
    y = np.array([classes.index(n) for _, n in data], dtype=np.int64)
    path = os.path.join(d, "snn_api_user.data.spx")
    write_spx(path, "snn_api_user", [("x", x), ("y", y)],
              {"Kind": "sphere-dataset", "Rows": len(data), "Classes": len(classes), "Features": FEATURES, "Mine": len(rows)})
    return {"mine": rows, "rows": len(data), "path": path}


def heal_build(c, path, needs, cxx, exe, say=None, attempts=8):
    """A C++ program built with the neurons it needs; a build that fails is read, fixed and built again: a header of a
    neuron the program did not name (the neuron is given), a header included by its bare name (its folder is given),
    a class used without its header (-include gives it, the source is left as it is), a symbol the link missed (its
    neuron's libraries are given). What was learned before is given before the first build. Returns (flags, heals)."""
    say = say or (lambda s: None)
    idx = api_index(c)
    names, injected, extra, heals = list(needs), [], [], []
    src = _read(path, 8 << 20)
    code = _strip_comments(src)
    healing = (settings().get("SNN_HEAL") or "on").strip().lower() not in ("off", "0", "no", "false")
    if healing:
        mem = memory().get("heals", {})
        included = set(re.findall(r'^\s*#\s*include\s*[<"]([^>"]+)[>"]', code, re.M))
        for t in code_tokens(src):
            h = mem.get("ident:" + t)
            if h and h.get("neuron") in c["neurons"] and h.get("header") and h["header"] not in included and h["header"] not in injected:
                injected.append(h["header"])
                names.append(h["neuron"])
                heals.append(dict(h, key="ident:" + t, why="learned: %s -> -include %s" % (t, h["header"])))
        for inc in included:
            h = mem.get("header:" + inc)
            if h and h.get("neuron") in c["neurons"]:
                names.append(h["neuron"])
                mine = idx.get("includes", {}).get(h["neuron"], [])
                if h.get("dir") and os.path.isdir(h["dir"]) and any(_inside(h["dir"], d) or h["dir"] == d for d in mine):
                    extra.append(h["dir"])
                heals.append(dict(h, key="header:" + inc, why="learned: %s" % inc))
    env = dict(os.environ, LC_ALL="C", LANG="C")
    kind = None
    for attempt in range(attempts):
        f = flags(c, _uniq(names))
        cmd = [cxx, "-O2", path, "-o", exe] + ["-I" + d for d in extra] + [x for h in injected for x in ("-include", h)] + f["cflags"] + f["libs"]
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env)
        text = p.stdout.decode("utf-8", "replace")
        if p.returncode == 0:
            for h in heals:
                say("           healed     %s\n" % h["why"])
            learn(f["chosen"], True, "cpp", [q["family"] for q in f["chosen"].values()], heals, os.path.basename(path),
                  names=derived_names(src, idx))
            return f, heals
        fix, kind = _diagnose(text, c, idx, names, injected, extra) if healing else (None, None)
        if fix is None:
            sys.stdout.write(text)
            learn(f["chosen"], False, "cpp", [q["family"] for q in f["chosen"].values()], (), os.path.basename(path),
                  link_failed=kind == "symbol")
            raise SnnError("%s did not build%s (snn-config --cflags --libs %s)" % (
                os.path.basename(path), " -- healed %d time(s) first" % len(heals) if heals else "", " ".join(_uniq(names))))
        say("           heal %d     %s\n" % (attempt + 1, fix["why"]))
        heals.append(fix)
        if fix.get("neuron") and fix["neuron"] not in names:
            names.append(fix["neuron"])
        if fix.get("header"):
            injected.append(fix["header"])
        if fix.get("dir"):
            extra.append(fix["dir"])
    raise SnnError("%s still does not build after %d fixes" % (os.path.basename(path), attempts))


# ===========================================================================
# settings.conf [SNN] written: what Sphere found, the user's own lines kept
# ===========================================================================

SECTION_HEAD = (
    "# The Sphere Neural Network: every module Sphere found, and the installation each one is taken from by its C++,",
    "# Python, Julia and Java modes, its terminals and its notebooks. Nothing is installed on the machine: this section",
    "# is the whole of it. To switch a module, write one of the installations listed under it (#|), a tree's name",
    "# (madgraph, pylhc...) or a version; to switch it off, leave it empty -- what Sphere starts next takes it. Sphere",
    "# keeps the section true as the machine changes and never rewrites a line you changed. Keys without a prefix are",
    "# the Linux side's (WSL's, from Windows), WIN_ keys Windows'; $ROOT, $FASTJET... follow another key.",
)


def _section_block(lines):
    """(start, end) of [SNN] among settings.conf's lines: its header's index and the next section's."""
    start = None
    for i, l in enumerate(lines):
        t = l.strip()
        if t.startswith("[") and t.endswith("]"):
            if start is not None:
                return start, i
            if t[1:-1].strip().upper() == "SNN":
                start = i
    return (start, len(lines)) if start is not None else (None, None)


def _section_sides():
    """The sides [SNN] describes, with their connectomes as found: Windows and the WSL distribution SNN_WSL names (the
    first one seen otherwise), or this machine alone."""
    base = network_folder()
    if WINDOWS:
        seen = sorted(d for d in os.listdir(base) if d.startswith("wsl") and os.path.isfile(os.path.join(base, d, "connectome.json"))) \
            if os.path.isdir(base) else []
        wanted = ["wsl-" + d.strip() for d in (settings().get("SNN_WSL") or "").split(",") if d.strip()]
        sides = ["windows"] + ([w for w in wanted if w in seen][:1] or seen[:1])
    else:
        sides = [side()]
    out = []
    for sd in sides:
        try:
            with open(os.path.join(base, sd, "connectome.json"), encoding="utf-8") as fh:
                out.append((sd, json.load(fh)))
        except (OSError, ValueError):
            continue
    return out


def _alt_line(n, p):
    title = NEURONS.get(n, {}).get("title", n).split(",")[0]
    where = ("Python " + p["python"]) if p.get("python") else (p.get("family_name") or "")
    return "#|     %s   (%s %s%s)" % (p.get("prefix") or p.get("origin") or "?", title, p.get("version") or "?", ", " + where if where else "")


def _side_choice(c, values):
    """{neuron: installation} for one side: the user's values first, the rest chosen around them the way one program
    would hold them (one tree for what is tied together), Python's packages from the side's Python."""
    capp = _apply_declared(c, values)
    chosen = {}
    try:
        chosen.update(_default_set(capp, "cpp"))
    except SnnError:
        pass
    py = capp.get("python")
    for n, info in capp["neurons"].items():
        if n in chosen or not info.get("providers"):
            continue
        ps = info["providers"]
        mine = [p for p in ps if p.get("python") and py and _decl_norm(p["python"]) == _decl_norm(py)]
        chosen[n] = ps[0] if n in capp["declared"] else (mine or ps)[0]
    return capp, chosen


def render_section(body, snapshot):
    """[SNN]'s new lines from every side's connectome, the section as it is and what Sphere wrote last. A line whose
    value is not what Sphere wrote is the user's: kept as it is, and taken as a choice the rest is made around.
    Returns (lines, written by Sphere {key: value}, the user's keys)."""
    cur, comments, last = {}, {}, ""
    for raw_line in body:
        t = raw_line.strip()
        if not t or t.startswith("#|") or t.startswith("# ----") or t in SECTION_HEAD:
            continue
        if t.startswith(("#", ";")):
            comments.setdefault(last, []).append(raw_line.rstrip())
        elif "=" in t:
            k, v = t.split("=", 1)
            last = k.strip().upper()
            cur[last] = v.strip()
    user = {k for k in cur if snapshot.get(k) != cur[k]}
    mine, written = {}, set()
    lines = list(SECTION_HEAD) + comments.get("", [])

    def put(key, value, extra=()):
        lines.append("%s=%s" % (key, cur[key] if key in user else value))
        lines.extend(extra)
        lines.extend(comments.get(key, []))
        written.add(key)
        if key not in user:
            mine[key] = value

    sides = _section_sides()
    if WINDOWS:
        put("SNN_WSL", ",".join(sd[4:] for sd, _ in sides if sd.startswith("wsl-")))
    put("SNN_LEARN", "on")
    put("SNN_HEAL", "on")
    for k in ("SNN_PREFER", "SNN_PATHS"):
        if k in cur:
            put(k, cur[k])
    user_rows = [(k, cur[k]) for k in cur if k in user]
    cfg = settings()
    for sd, c in sides:
        win = sd == "windows"
        title = "Windows" if win else ("WSL " + sd[4:]) if sd.startswith("wsl-") else sd.capitalize()
        lines += ["", "# ---- %s %s" % (title, "-" * max(4, 106 - len(title)))]
        capp, chosen = _side_choice(c, declared(sd, user_rows))
        pyval = c.get("python") or ""
        if win and pyval and cfg.get("PYTHON_EXEC") and _decl_norm(cfg["PYTHON_EXEC"]) == _decl_norm(pyval):
            pyval = "$PYTHON_EXEC"
        put(snn_key("python", sd), pyval, ["#|     %s   (Python %s, %d neurons)" % (q["python"], q.get("version", "?"), len(q.get("can", {})))
                                           for q in c.get("pythons", []) if _decl_norm(q["python"]) != _decl_norm(capp.get("python") or "")][:4])
        order = sorted(chosen, key=lambda n: (GROUPS.index(NEURONS[n]["group"]) if NEURONS.get(n, {}).get("group") in GROUPS else 99,
                                              ANCHORS.get(n, n), n in ANCHORS, n))      # ROOT, then the parts that follow it
        for n in order:
            p = chosen[n]
            val = p.get("prefix") or p.get("origin") or ""
            a = ANCHORS.get(n)
            if a and a in chosen and p.get("prefix") and chosen[a].get("prefix") == p["prefix"]:
                val = "$" + snn_key(a, sd)
            alts, seen = [], {p.get("prefix")}
            for q in c["neurons"].get(n, {}).get("providers", []):
                if q.get("prefix") not in seen:
                    seen.add(q.get("prefix"))
                    alts.append(_alt_line(n, q))
            put(snn_key(n, sd), val, alts[:4])
        for k in [k for k in cur if k not in written and not k.startswith("SNN_") and k.startswith("WIN_") == win]:
            n = _KEY_NEURON.get(k[4:] if win else k)
            if n is None:
                continue
            info = c["neurons"].get(n)
            extra = [_alt_line(n, q) for q in info["providers"][:4]] if info else ["#|     not found on this side any more"]
            lines.append("%s=%s" % (k, cur[k]))
            lines.extend(extra)
            lines.extend(comments.get(k, []))
            written.add(k)
    left = [k for k in cur if k not in written]
    if left:
        lines += ["", "# ---- kept as written " + "-" * 92]
        for k in left:
            lines.append("%s=%s" % (k, cur[k]))
            lines.extend(comments.get(k, []))
    return lines, mine, user


def declare(write_file=True):
    """settings.conf [SNN] made true: what Sphere found on every side, the user's own lines kept as they are; the file
    replaced in one move (a whole version or the other on disk), its line endings kept."""
    path = settings_path()
    if not path:
        raise SnnError("no settings.conf: this cortex is in no Sphere")
    with open(path, "rb") as fh:
        text = fh.read().decode("utf-8")
    nl = "\r\n" if "\r\n" in text else "\n"
    lines = text.splitlines()
    start, end = _section_block(lines)
    body = lines[start + 1:end] if start is not None else []
    snap_path = os.path.join(network_folder(), "declared.json")
    try:
        with open(snap_path, encoding="utf-8") as fh:
            snapshot = json.load(fh)
    except (OSError, ValueError):
        snapshot = {}
    new_body, mine, user = render_section(body, snapshot)
    block = ["[SNN]"] + new_body
    if start is None:
        out = lines + ([""] if lines and lines[-1].strip() else []) + block
    else:
        rest = lines[end:]
        out = lines[:start] + block + ([""] if rest else []) + rest
    new_text = nl.join(out) + nl
    changed = new_text != text
    if write_file:
        if changed:
            part = path + ".snn-%d.part" % os.getpid()
            with open(part, "w", encoding="utf-8", newline="") as fh:
                fh.write(new_text)
            for attempt in range(50):
                try:
                    os.replace(part, path)
                    break
                except OSError:
                    if attempt == 49:
                        os.remove(part)
                        raise
                    time.sleep(0.05)
        keep = {k: snapshot[k] for k in user if k in snapshot}
        keep.update(mine)
        _write_text(snap_path, json.dumps(keep, indent=1, sort_keys=True))
        _SETTINGS.clear()
    return {"changed": changed, "path": path, "keys": len(mine) + len(user), "yours": sorted(user), "section": block}


# ===========================================================================
# Command line
# ===========================================================================

def main(argv):
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(__doc__)
        return 0
    cmd, rest = argv[0], argv[1:]
    if cmd == "exec":                       # a Python program, the network's import finder on (what 'run' uses)
        import runpy
        if lib_folder() not in sys.path:
            sys.path.insert(0, lib_folder())
        import sphere_snn_hook  # noqa: F401 -- installs the finder
        sys.argv = list(rest)
        sys.path.insert(0, os.path.dirname(os.path.abspath(rest[0])))
        runpy.run_path(rest[0], run_name="__main__")
        return 0
    if cmd == "run":                        # options before the file; what follows the file is the program's
        k = next((i for i, a in enumerate(rest) if not a.startswith("--")), None)
        if k is None:
            sys.stderr.write("snn run [--why] [--build-only] <program.cpp|macro.C|script.py|script.jl> [its arguments]\n")
            return 2
        try:
            return run_program(rest[k], rest[k + 1:], plan_only="--why" in rest[:k], build_only="--build-only" in rest[:k])
        except SnnError as e:
            sys.stderr.write("snn: %s\n" % e)
            return 2
    opts = {a for a in rest if a.startswith("--")}
    words = [a for a in rest if not a.startswith("--")]
    try:
        if cmd == "status":
            print(describe(detect(local=not WINDOWS)))
            return 0
        if cmd == "declare":                # settings.conf [SNN] made true, the user's lines kept
            got = declare(write_file="--dry" not in opts)
            if "--json" in opts:
                print(json.dumps(got))
            else:
                print("\n".join(got["section"]) if "--dry" in opts else "%s [SNN]: %d keys, %d of them yours%s" % (
                    got["path"], got["keys"], len(got["yours"]), "" if got["changed"] else " (unchanged)"))
            return 0
        if cmd == "index":                  # the API of every neuron of this side
            idx = api_index(detect(local=True), force="--force" in opts)
            print("%s: %d headers, %d classes, %d namespaces, %d Python modules, %d names (%.1f s)" % (
                idx.get("side"), len(idx["headers"]), len(idx["classes"]), len(idx["namespaces"]), len(idx["modules"]),
                len(idx["names"]), idx.get("seconds", 0)))
            return 0
        if cmd == "train":                  # the network's neural model, on every API indexed
            try:
                import torch  # noqa: F401
            except ImportError:
                c = detect(local=True)
                if not c.get("python"):
                    raise SnnError("no Python with PyTorch on this side to train the model")
                return subprocess.call([c["python"], os.path.join(lib_folder(), "sphere_snn.py"), "train"] + rest,
                                       env=sandbox_env(dict(os.environ, SPHERE_SNN=network_folder())))
            got = train_model(progress=lambda s: (print(s), sys.stdout.flush()))
            print("snn_api: %d classes, %d names (%d rows), validation accuracy %.3f, graph = torch to %.1e, %s" % (
                len(got["classes"]), got["names"], got["rows"], got["accuracy"], got["graph_vs_torch"], got["path"]))
            return 0
        if cmd == "predict":                # the neural model on identifiers, or on a file's
            toks = code_tokens(_read(native(words[0]), 4 << 20)) if len(words) == 1 and os.path.isfile(native(words[0])) else words
            got = predict_tokens(toks)
            if got is None:
                raise SnnError("the neural model is not trained yet ('snn train')")
            if "--json" in opts:
                print(json.dumps(got))
            else:
                for t, best in got.items():
                    print("%-28s %s" % (t, "   ".join("%s %.3f" % nb for nb in best)))
            return 0
        if cmd == "suggest":                # what a program holds and will likely want next
            got = suggest(words[0] if len(words) == 1 else words)
            if "--json" in opts:
                print(json.dumps(got))
                return 0
            for n, why in got["named"]:
                print("  named    %-12s %s" % (n, why))
            for n, p, toks in got["model"]:
                print("  read     %-12s %.2f  (%s)" % (n, p, ", ".join(toks)))
            for x in got["next"]:
                print("  next     %-12s %.2f  %s" % (x["neuron"], x["score"], "; ".join("%s: %s" % (i, h) for i, h in x["synapses"][:2])))
            return 0
        if cmd == "learn-data":             # what Sphere's trainer fine-tunes the neural model on
            got = learn_data()
            print("%d names from the user's programs (%s), %d rows: %s" % (len(got["mine"]), ", ".join(
                "%s->%s" % kv for kv in got["mine"][:8]), got["rows"], got["path"]))
            return 0
        if cmd == "memory":                 # what the network learned
            m = memory()
            strong = sorted(((weight(*k.split("|"), m), k) for k in m["synapses"]), reverse=True)[:12]
            print("%d runs remembered, %d synapses strengthened, %d fixes learned" % (len(m["runs"]), len(m["synapses"]), len(m["heals"])))
            for w, k in strong:
                print("  %-28s %.3f" % (k.replace("|", " <> "), w))
            for k, h in sorted(m["heals"].items(), key=lambda kv: -kv[1].get("n", 0))[:12]:
                print("  fix  %-32s -> %s %s (%dx)" % (k, h.get("neuron"), h.get("header") or h.get("dir") or "", h.get("n", 0)))
            return 0
        if cmd == "detect":
            c = detect(rescan="--rescan" in opts, local="--local" in opts, quiet="--json" in opts)
            print(json.dumps(c) if "--json" in opts else describe(c))
        elif cmd == "write":
            c = detect(rescan="--rescan" in opts)
            for w in write(c):
                print(os.path.join(c.get("folder") or folder(), w))
        elif cmd == "config":
            c = detect(local=True)
            if "--list" in opts:
                print(" ".join(sorted(n for n, i in c["neurons"].items() if any("cpp" in p["langs"] for p in i["providers"]))))
                return 0
            if "--version" in opts and not words:
                print("1.0")
                return 0
            f = flags(c, words)
            out = []
            if "--cflags" in opts or "--cxxflags" in opts:
                out += f["cflags"]
            if "--libs" in opts:
                out += f["libs"]
            if "--std" in opts:
                out.append(str(f["std"]))
            if "--version" in opts:
                out += ["%s=%s" % (n, p["version"]) for n, p in f["chosen"].items()]
            if "--env" in opts:
                out += ["%s=%s" % kv for kv in sorted(f["env"].items())]
            if "--why" in opts:
                out += ["%s: %s %s (%s)" % (n, p["family_name"], p["version"], p["prefix"]) for n, p in f["chosen"].items()] + f["notes"]
            print(("\n" if ("--env" in opts or "--why" in opts) else " ").join(out))
        elif cmd == "cmake":
            sys.stdout.write(cmake_text(detect(local=True), words))
        elif cmd == "env":
            shell = words[0] if words and words[0] in ("sh", "bash", "zsh", "wsl", "cmd", "powershell") else ("cmd" if WINDOWS else "sh")
            names = [w for w in words if w != shell]
            sys.stdout.write(render_env(detect(local=True), shell, names or None))
        elif cmd == "check":
            only = next((a.split("=", 1)[1] for a in rest if a.startswith("--only=")), None)
            if "--only" in rest:
                i = rest.index("--only")
                only = rest[i + 1] if i + 1 < len(rest) else None
            report = check(only=only, quick="--quick" in opts)
            print(json.dumps(report) if "--json" in opts else "\n" + matrix_text(report=report))
        elif cmd in ("check-python", "check-graphs"):
            code = _check_python(words[0], words[1] if len(words) > 1 else "python,cling") if cmd == "check-python" else _check_graphs(words[0])
            sys.stdout.flush()
            # ROOT, TensorFlow and PyTorch tearing down together can take minutes (or hang) on a small machine:
            # the results are written, the process goes now
            os._exit(code)
        elif cmd == "matrix":
            c = detect(local=True)
            if "--json" in opts:
                with open(os.path.join(c.get("folder") or folder(), "matrix.json"), encoding="utf-8") as fh:
                    sys.stdout.write(fh.read())
            else:
                print(matrix_text(c))
        elif cmd == "route":
            if len(words) != 2:
                raise SnnError("route <from> <to>")
            chain = route(detect(local=True), words[0], words[1])
            if chain is None:
                print("no synapse leads from %s to %s" % (words[0], words[1]))
                return 1
            for s in chain:
                print("%-26s %s   [%s]" % (s["id"], s["what"], "; ".join("%s: %s" % kv for kv in s.get("how", {}).items())))
        elif cmd == "routes":
            print(json.dumps(routes()))
        elif cmd == "here":
            print(json.dumps(here()))
        else:
            raise SnnError("unknown command '%s' (detect, write, declare, config, cmake, env, check, matrix, route, run, index, "
                           "train, predict, suggest, memory)" % cmd)
    except SnnError as e:
        sys.stderr.write("snn: %s\n" % e)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

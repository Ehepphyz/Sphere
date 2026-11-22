"""sphere_snn_hook -- the Sphere Neural Network in every Python, at no cost until it is needed.

Sphere's sitecustomize imports this module when a Python Sphere started starts (the bridge library is on the path of
those Pythons only: nothing is installed in any Python of the machine). It puts one finder at the end of sys.meta_path: it is asked only about an import every other finder failed, and only for the
module of a neuron (ROOT, lhapdf, yoda, rivet, fastjet, pyhepmc...). It then asks the network (sphere_snn):

  * the neuron's installation can be loaded by this Python (ROOT's PyROOT built for its version, MadGraph's LHAPDF,
    YODA and Rivet modules): their folders are given and their libraries loaded in order, and the import proceeds;
  * another Python holds it (on this side of WSL or the other): a module is given that sends what is done with it to
    that Python through the gateway, the objects staying there (sphere_snn.RemoteModule). A notice says so once.

SPHERE_SNN_IMPORT=0 turns the finder off; SPHERE_SNN_REMOTE=0 keeps it to installations of this side.
"""

import sys

# The modules the network gives (sphere_snn.PY_MODULE_NEURONS has their neurons).
NAMES = frozenset(("ROOT", "cppyy", "lhapdf", "yoda", "rivet", "fastjet", "pyhepmc", "pythia8", "uproot", "awkward",
                   "vector", "iminuit", "pyhf", "torch", "tensorflow", "keras", "jax", "zfit", "pylhe", "pyg4ometry",
                   "hist", "coffea", "particle", "spey", "onnx", "sklearn", "geant4_pybind"))


class SphereNetworkFinder:
    """The last finder of sys.meta_path: a neuron's module no other finder found."""

    busy = False

    def find_spec(self, name, path=None, target=None):
        if path is not None or name not in NAMES or SphereNetworkFinder.busy:
            return None
        import os
        if os.environ.get("SPHERE_SNN_IMPORT", "1") == "0":
            return None
        SphereNetworkFinder.busy = True        # what sphere_snn imports to answer is not asked of it again
        try:
            import sphere_snn
            return sphere_snn.find_spec(name)
        except Exception as e:                 # an import that fails says why, as one would without the network
            sys.stderr.write("snn: %s: %s\n" % (name, e))
            return None
        finally:
            SphereNetworkFinder.busy = False

    def invalidate_caches(self):
        pass


def install():
    if not any(type(f).__name__ == "SphereNetworkFinder" for f in sys.meta_path):
        sys.meta_path.append(SphereNetworkFinder())


install()

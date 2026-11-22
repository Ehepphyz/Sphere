"""sitecustomize -- Sphere's, for the Pythons Sphere starts: the Sphere Neural Network's import finder, with nothing
installed in any Python of the machine.

Every Python reads a module of this name at startup, from its path. This one is on the path only of the processes Sphere
starts (PYTHONPATH names Sphere's bridge library for them, and for nothing else), so the Python environments of the
machine are left as they are: no .pth, no file in any site-packages. It then gives way to the sitecustomize the Python
would have read without it (Debian's, a conda environment's), so nothing that Python did at startup is lost.

SPHERE_SNN_IMPORT=0 turns the finder off.
"""


def _sphere_chain():
    """Runs the sitecustomize this one hides, the next one on the path, as Python would have."""
    import importlib.machinery
    import importlib.util
    import os
    import sys
    here = os.path.dirname(os.path.abspath(__file__))
    for entry in list(sys.path):
        if not entry or os.path.abspath(entry) == here:
            continue
        spec = importlib.machinery.PathFinder.find_spec("sitecustomize", [entry])
        if spec is None or not spec.origin or os.path.dirname(os.path.abspath(spec.origin)) == here:
            continue
        module = importlib.util.module_from_spec(spec)
        sys.modules["sitecustomize"] = module
        spec.loader.exec_module(module)
        return


try:
    _sphere_chain()
except Exception:            # theirs failed: it would have failed without Sphere too, and must not stop the program
    pass

try:
    import os as _os
    if _os.environ.get("SPHERE_SNN_IMPORT", "1") != "0":
        import sphere_snn_hook  # noqa: F401 -- puts the finder last on sys.meta_path, asked only when all others fail
except Exception:
    pass

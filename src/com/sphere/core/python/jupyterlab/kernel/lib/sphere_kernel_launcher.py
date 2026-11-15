"""Starts Sphere's kernel from the folder this file is in (a kernelspec, or Sphere's config/)."""
import os
import sys

_here = os.path.dirname(os.path.abspath(__file__))
if sys.path and os.path.abspath(sys.path[0] or os.curdir) == _here:
    sys.path.pop(0)
sys.path.insert(0, _here)
import sphere_kernel.__main__ as _main  # noqa: E402  (the kernel imports itself from here, before user code)
sys.path.remove(_here)
sys.path.insert(0, os.getcwd())  # the notebook's folder first, as in Jupyter
sys.path.append(_here)           # Sphere's helpers last, so they never shadow the user's modules
sys.exit(_main.main())

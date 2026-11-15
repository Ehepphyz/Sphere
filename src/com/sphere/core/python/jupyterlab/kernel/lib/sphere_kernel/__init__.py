"""Sphere HEP kernel: a Jupyter kernel written from scratch on Python's standard library.

It speaks the Jupyter protocol 5.4 over its own ZeroMQ implementation (zmtp),
so it needs neither ipykernel, nor IPython, nor pyzmq, nor tornado: any Python
3.9+ runs it, in Sphere's notebook as in JupyterLab, Jupyter Notebook or VS Code.

    python -m sphere_kernel install [--user | --sys-prefix | --prefix DIR]
    python -m sphere_kernel -f connection_file.json
"""

__version__ = "1.0.0"

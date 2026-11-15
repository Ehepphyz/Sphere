"""Installs the kernel where Jupyter looks for kernels, so JupyterLab, Notebook and VS Code list it.

    python -m sphere_kernel install                 for this user
    python -m sphere_kernel install --sys-prefix    in the current environment
    python -m sphere_kernel install --prefix DIR    under DIR/share/jupyter
    python -m sphere_kernel install --uninstall     removes it again

The kernel is copied into the kernelspec's own folder and started from there
({resource_dir}), with the Python that ran the installation unless --python
names another: nothing is pip-installed, nothing else is needed.
"""

import argparse
import json
import os
import shutil
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
LAUNCHER = "sphere_kernel_launcher.py"


def data_dir(user=True, sys_prefix=False, prefix=None):
    if prefix:
        return os.path.join(prefix, "share", "jupyter")
    if sys_prefix:
        return os.path.join(sys.prefix, "share", "jupyter")
    if os.name == "nt":
        return os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "jupyter")
    if sys.platform == "darwin":
        return os.path.join(os.path.expanduser("~"), "Library", "Jupyter")
    xdg = os.environ.get("XDG_DATA_HOME") or os.path.join(os.path.expanduser("~"), ".local", "share")
    return os.path.join(xdg, "jupyter")


LOGO_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
<circle cx="32" cy="32" r="30" fill="#1e1e2e"/>
<ellipse cx="32" cy="32" rx="26" ry="9" fill="none" stroke="#4fc1ff" stroke-width="2.4" transform="rotate(-30 32 32)"/>
<ellipse cx="32" cy="32" rx="26" ry="9" fill="none" stroke="#c5a0ff" stroke-width="2.4" transform="rotate(30 32 32)"/>
<ellipse cx="32" cy="32" rx="26" ry="9" fill="none" stroke="#e8a85c" stroke-width="2.4" transform="rotate(90 32 32)"/>
<circle cx="32" cy="32" r="5" fill="#ffd54a"/>
</svg>
"""


def _png(size):
    """The logo as a PNG drawn pixel by pixel: three orbits around a nucleus."""
    import math
    rows = []
    c = (size - 1) / 2.0
    orbit_colors = [(79, 193, 255), (197, 160, 255), (232, 168, 92)]
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            dx, dy = (x - c) / (size / 2.0), (y - c) / (size / 2.0)
            r = math.hypot(dx, dy)
            px = (0, 0, 0, 0)
            if r <= 0.95:
                px = (30, 30, 46, 255)
            for k, angle in enumerate((-30, 30, 90)):
                a = math.radians(angle)
                u = dx * math.cos(a) + dy * math.sin(a)
                v = -dx * math.sin(a) + dy * math.cos(a)
                e = (u / 0.82) ** 2 + (v / 0.29) ** 2
                if abs(e - 1.0) < 0.16 and r <= 0.95:
                    px = orbit_colors[k] + (255,)
            if r < 0.17:
                px = (255, 213, 74, 255)
            row += bytes(px)
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def install(name="sphere-hep", display_name=None, python=None, user=True, sys_prefix=False, prefix=None):
    dest = os.path.join(data_dir(user, sys_prefix, prefix), "kernels", name)
    if os.path.isdir(dest):
        shutil.rmtree(dest)
    os.makedirs(dest)
    shutil.copytree(HERE, os.path.join(dest, "sphere_kernel"),
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    launcher = os.path.join(os.path.dirname(HERE), LAUNCHER)
    if os.path.isfile(launcher):
        shutil.copy2(launcher, os.path.join(dest, LAUNCHER))
    else:
        with open(os.path.join(dest, LAUNCHER), "w", encoding="utf-8") as f:
            f.write(LAUNCHER_SOURCE)
    version = "%d.%d" % sys.version_info[:2]
    spec = {
        "argv": [python or sys.executable, "{resource_dir}/" + LAUNCHER, "-f", "{connection_file}"],
        "display_name": display_name or "Sphere HEP (Python %s, homemade)" % version,
        "language": "python",
        "interrupt_mode": "message",
        "metadata": {"debugger": False, "sphere": {"homemade": True}},
    }
    with open(os.path.join(dest, "kernel.json"), "w", encoding="utf-8") as f:
        json.dump(spec, f, indent=2)
    with open(os.path.join(dest, "logo-svg.svg"), "w", encoding="utf-8") as f:
        f.write(LOGO_SVG)
    for size in (32, 64):
        with open(os.path.join(dest, "logo-%dx%d.png" % (size, size)), "wb") as f:
            f.write(_png(size))
    return dest


LAUNCHER_SOURCE = '''"""Starts Sphere's kernel from the folder this file is in (a kernelspec, or Sphere's config/)."""
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
'''


def main(argv=None):
    parser = argparse.ArgumentParser(prog="python -m sphere_kernel install",
                                     description="Install the Sphere HEP kernel for Jupyter.")
    where = parser.add_mutually_exclusive_group()
    where.add_argument("--user", action="store_true", help="for this user (the default)")
    where.add_argument("--sys-prefix", action="store_true", help="in this Python environment")
    where.add_argument("--prefix", help="under PREFIX/share/jupyter")
    parser.add_argument("--name", default="sphere-hep", help="the kernel's name (default sphere-hep)")
    parser.add_argument("--display-name", help="the name Jupyter shows")
    parser.add_argument("--python", help="the interpreter to run the kernel with (default: this one)")
    parser.add_argument("--uninstall", action="store_true", help="remove the kernel")
    args = parser.parse_args(argv)
    if args.uninstall:
        dest = os.path.join(data_dir(True, args.sys_prefix, args.prefix), "kernels", args.name)
        if os.path.isdir(dest):
            shutil.rmtree(dest)
            print("Removed %s" % dest)
        else:
            print("Not installed: %s" % dest)
        return 0
    dest = install(args.name, args.display_name, args.python, True, args.sys_prefix, args.prefix)
    print("Installed the Sphere HEP kernel in %s" % dest)
    print("Jupyter now lists it as %r." % json.load(open(os.path.join(dest, "kernel.json")))["display_name"])
    return 0

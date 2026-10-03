"""sphere_mpl -- the matplotlib backend Sphere gives the Python programs it starts.

plt.show() opens a window, and in a program Sphere started that window either
blocks the run until someone closes it or never appears at all. Here show()
hands every open figure to Sphere's Plots tab instead: each is written into
$SPHERE_PLOTS as a PNG, which the tab picks up and the image editor can open.
A script run again replaces its figures rather than piling them up.

Sphere sets MPLBACKEND=module://sphere_mpl only when the environment names no
backend of its own, and matplotlib.use(...) in a script still wins.
"""

import os
import sys

from matplotlib._pylab_helpers import Gcf
from matplotlib.backend_bases import FigureManagerBase, _Backend
from matplotlib.backends.backend_agg import FigureCanvasAgg

#: Pixels per inch of the pictures written: enough to edit and print a figure.
DPI = 150


def _folder():
    folder = os.environ.get("SPHERE_PLOTS") or os.path.join(os.getcwd(), "plots")
    os.makedirs(folder, exist_ok=True)
    return folder


def _stem():
    script = sys.argv[0] if sys.argv and sys.argv[0] else ""
    stem = os.path.splitext(os.path.basename(script))[0]
    return stem if stem and stem not in ("-c", "-") else "python"


def publish_figures():
    """Writes every open figure to the Plots tab's folder, closes it, and returns the paths."""
    folder, stem = _folder(), _stem()
    written = []
    for manager in Gcf.get_all_fig_managers():
        figure = manager.canvas.figure
        path = os.path.join(folder, "%s_fig%d.png" % (stem, manager.num))
        figure.savefig(path, dpi=max(DPI, figure.dpi), bbox_inches="tight")
        written.append(path)
    Gcf.destroy_all()
    return written


@_Backend.export
class _BackendSphere(_Backend):
    FigureCanvas = FigureCanvasAgg
    FigureManager = FigureManagerBase

    @staticmethod
    def show(*args, **kwargs):
        # A kernel (Sphere's notebook, its console) collects the figures
        # itself after each run; taking them here would leave it none.
        if os.environ.get("SPHERE_MPL_KEEP") == "1":
            return
        for path in publish_figures():
            print("[plots] " + os.path.basename(path))

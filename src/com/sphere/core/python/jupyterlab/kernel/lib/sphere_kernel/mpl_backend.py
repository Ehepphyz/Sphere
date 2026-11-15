"""matplotlib's figures as cell outputs: the kernel's inline backend.

Selected with MPLBACKEND=module://sphere_kernel.mpl_backend (the kernel sets it
when nothing else is chosen) or %matplotlib inline. A figure drawn by a cell is
shown when the cell ends, or at plt.show(), then closed, as in Jupyter.
FORMAT is png, retina (png at twice the resolution, shown at its size) or svg.
"""

import base64
import io

from matplotlib import _pylab_helpers
from matplotlib.backend_bases import FigureManagerBase
from matplotlib.backends.backend_agg import FigureCanvasAgg

FigureCanvas = FigureCanvasAgg
FigureManager = FigureManagerBase

FORMAT = "png"


def _bundle(figure):
    if FORMAT == "svg":
        buf = io.StringIO()
        figure.savefig(buf, format="svg", bbox_inches="tight")
        return {"image/svg+xml": buf.getvalue(), "text/plain": repr(figure)}, {}
    retina = FORMAT == "retina"
    dpi = figure.dpi * (2 if retina else 1)
    buf = io.BytesIO()
    figure.savefig(buf, format="png", bbox_inches="tight", dpi=dpi)
    data = buf.getvalue()
    metadata = {}
    if retina:
        try:
            import struct
            w, h = struct.unpack(">II", data[16:24])
            metadata = {"image/png": {"width": w // 2, "height": h // 2}}
        except Exception:
            metadata = {}
    return {"image/png": base64.b64encode(data).decode("ascii"), "text/plain": repr(figure)}, metadata


def _publish(figure):
    from . import display
    if display.KERNEL is None or not figure.axes and not figure.artists and not figure.texts:
        return
    data, metadata = _bundle(figure)
    display.KERNEL.publish_display(data, metadata, {}, False)


def show(close=None, block=None):
    """Shows every open figure in the cell, then closes them."""
    try:
        for manager in _pylab_helpers.Gcf.get_all_fig_managers():
            _publish(manager.canvas.figure)
    finally:
        if close is not False:
            import matplotlib.pyplot as plt
            plt.close("all")


def flush_figures():
    """Called by the kernel when a cell ends: the figures it left open are its outputs."""
    if _pylab_helpers.Gcf.get_all_fig_managers():
        show(True)

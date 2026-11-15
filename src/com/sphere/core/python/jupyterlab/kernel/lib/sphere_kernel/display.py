"""Rich output: an object's MIME bundle, display(), display ids, clear_output.

An object shows itself through the IPython protocol (_repr_mimebundle_ and the
_repr_*_ methods) with nothing of IPython needed. Two kinds of objects that do
not know the protocol are drawn anyway: a matplotlib figure, and a ROOT object
(histogram, graph, function, canvas), drawn on a batch canvas the way ROOT's own
notebooks do.
"""

import base64
import io
import os
import pprint
import sys
import tempfile
import uuid

_REPRS = (
    ("_repr_html_", "text/html"),
    ("_repr_markdown_", "text/markdown"),
    ("_repr_svg_", "image/svg+xml"),
    ("_repr_png_", "image/png"),
    ("_repr_jpeg_", "image/jpeg"),
    ("_repr_latex_", "text/latex"),
    ("_repr_json_", "application/json"),
    ("_repr_javascript_", "application/javascript"),
    ("_repr_pdf_", "application/pdf"),
)
_BINARY = {"image/png", "image/jpeg", "application/pdf"}

#: The kernel publishing displays; set when it starts.
KERNEL = None


class _Printer:
    """Enough of IPython's pretty printer for objects with a _repr_pretty_."""

    def __init__(self):
        self.parts = []
        self.indentation = 0

    def text(self, s):
        self.parts.append(str(s))

    def breakable(self, sep=" "):
        self.parts.append(sep)

    def pretty(self, obj):
        self.parts.append(pretty(obj))

    def begin_group(self, indent=0, open=""):
        self.parts.append(open)

    def end_group(self, dedent=0, close=""):
        self.parts.append(close)

    def group(self, indent=0, open="", close=""):
        printer = self

        class _Group:
            def __enter__(self):
                printer.parts.append(open)

            def __exit__(self, *exc):
                printer.parts.append(close)

        return _Group()

    def indent(self, n):
        return self.group()

    def flush(self):
        pass

    def getvalue(self):
        return "".join(self.parts)


def pretty(obj):
    """text/plain of an object: its _repr_pretty_, pprint for long containers, else repr."""
    method = getattr(type(obj), "_repr_pretty_", None)
    if method is not None and not isinstance(obj, type):
        try:
            p = _Printer()
            obj._repr_pretty_(p, False)
            return p.getvalue()
        except Exception:
            pass
    try:
        text = repr(obj)
    except Exception as e:
        return "<%s object (repr failed: %s)>" % (type(obj).__name__, e)
    if len(text) > 100 and type(obj) in (list, tuple, dict, set, frozenset):
        try:
            return pprint.pformat(obj, width=100, sort_dicts=False)
        except Exception:
            pass
    return text


def _call_repr(obj, method):
    fn = getattr(obj, method, None)
    if fn is None or isinstance(obj, type) or not callable(fn):
        return None, None
    try:
        r = fn()
    except Exception:
        return None, None
    if isinstance(r, tuple) and len(r) == 2:
        return r
    return r, None


def mimebundle(obj, include=None, exclude=None):
    """(data, metadata) of an object, richest forms first, text/plain always."""
    data = {}
    metadata = {}
    fn = getattr(obj, "_repr_mimebundle_", None)
    if fn is not None and callable(fn) and not isinstance(obj, type):
        try:
            try:
                r = fn(include=include, exclude=exclude)
            except TypeError:
                r = fn()
            if isinstance(r, tuple):
                d, m = r
            else:
                d, m = r, None
            if d:
                data.update(d)
            if m:
                metadata.update(m)
        except Exception:
            pass
    for method, mime in _REPRS:
        if mime in data:
            continue
        value, md = _call_repr(obj, method)
        if value is None:
            continue
        if mime in _BINARY and isinstance(value, (bytes, bytearray)):
            value = base64.b64encode(bytes(value)).decode("ascii")
        data[mime] = value
        if md:
            metadata[mime] = md
    if "image/png" not in data:
        png = _figure_png(obj) or _root_png(obj)
        if png:
            data["image/png"] = base64.b64encode(png).decode("ascii")
    if "text/plain" not in data:
        data["text/plain"] = pretty(obj)
    if include:
        data = {k: v for k, v in data.items() if k in include}
    if exclude:
        data = {k: v for k, v in data.items() if k not in exclude}
    return data, metadata


def _figure_png(obj):
    mod = type(obj).__module__ or ""
    if not mod.startswith("matplotlib") or type(obj).__name__ not in ("Figure", "SubFigure"):
        return None
    try:
        buf = io.BytesIO()
        obj.savefig(buf, format="png", bbox_inches="tight")
        return buf.getvalue()
    except Exception:
        return None


_ROOT_DRAWABLE = ("TH1", "TGraph", "TF1", "TMultiGraph", "THStack", "TGraph2D", "TEfficiency", "TProfile")


def _root_png(obj):
    """A ROOT histogram, graph or canvas drawn on a batch canvas, as JupyROOT does."""
    mod = type(obj).__module__ or ""
    root = sys.modules.get("ROOT")
    if root is None or not (mod.startswith("cppyy") or "ROOT" in mod):
        return None
    inherits = getattr(obj, "InheritsFrom", None)
    if inherits is None:
        return None
    try:
        if inherits("TCanvas") or inherits("TPad"):
            canvas = obj
            created = False
        elif any(inherits(name) for name in _ROOT_DRAWABLE):
            root.gROOT.SetBatch(True)
            canvas = root.TCanvas("sphere_canvas_" + uuid.uuid4().hex[:8], "", 800, 600)
            created = True
            option = "COLZ" if inherits("TH2") else "AP" if inherits("TGraph") and not inherits("TGraph2D") else ""
            obj.Draw(option)
        else:
            return None
        canvas.Update()
        fd, path = tempfile.mkstemp(suffix=".png")
        os.close(fd)
        try:
            canvas.SaveAs(path)
            with open(path, "rb") as f:
                return f.read()
        finally:
            os.unlink(path)
            if created:
                canvas.Close()
    except Exception:
        return None


# ---- display() and friends ------------------------------------------------------


class DisplayHandle:
    """What display(..., display_id=True) returns: the output can be updated in place."""

    def __init__(self, display_id=None):
        self.display_id = display_id or uuid.uuid4().hex

    def __repr__(self):
        return "<DisplayHandle display_id=%s>" % self.display_id

    def display(self, obj, **kwargs):
        display(obj, display_id=self.display_id, **kwargs)

    def update(self, obj, **kwargs):
        update_display(obj, display_id=self.display_id, **kwargs)


def display(*objs, include=None, exclude=None, metadata=None, transient=None, display_id=None,
            raw=False, clear=False, update=False, **kwargs):
    """Shows objects as outputs of the running cell, with their richest forms."""
    if KERNEL is None:
        print(*objs)
        return None
    if clear:
        clear_output(wait=True)
    if display_id is True:
        display_id = uuid.uuid4().hex
    for obj in objs:
        if raw:
            data, md = dict(obj), dict(metadata or {})
        else:
            data, md = mimebundle(obj, include, exclude)
            if metadata:
                md.update(metadata)
        tr = dict(transient or {})
        if display_id:
            tr["display_id"] = display_id
        KERNEL.publish_display(data, md, tr, update)
    return DisplayHandle(display_id) if display_id else None


def update_display(obj, *, display_id, **kwargs):
    kwargs["update"] = True
    display(obj, display_id=display_id, **kwargs)


def clear_output(wait=False):
    """Clears the cell's outputs; with wait, only when the next output arrives (no flicker)."""
    if KERNEL is not None:
        KERNEL.publish_clear(wait)


def publish_display_data(data, metadata=None, source=None, *, transient=None, **kwargs):
    if KERNEL is not None:
        KERNEL.publish_display(data, metadata or {}, transient or {}, False)


class _Text:
    mime = "text/plain"

    def __init__(self, data=None, url=None, filename=None, metadata=None):
        if data is None and filename:
            with open(filename, "r", encoding="utf-8") as f:
                data = f.read()
        self.data = data if data is not None else ""
        self.metadata = metadata

    def _repr_mimebundle_(self, include=None, exclude=None):
        return {self.mime: self.data, "text/plain": "<%s object>" % type(self).__name__}

    def __repr__(self):
        return "<%s object>" % type(self).__name__


class HTML(_Text):
    mime = "text/html"


class Markdown(_Text):
    mime = "text/markdown"


class Latex(_Text):
    mime = "text/latex"


class Math(_Text):
    mime = "text/latex"

    def _repr_mimebundle_(self, include=None, exclude=None):
        s = self.data.strip()
        return {"text/latex": s if s.startswith("$") else "$$%s$$" % s, "text/plain": s}


class SVG(_Text):
    mime = "image/svg+xml"


class Javascript(_Text):
    mime = "application/javascript"


class JSON:
    def __init__(self, data=None, expanded=False, root="root"):
        self.data = data

    def _repr_mimebundle_(self, include=None, exclude=None):
        return {"application/json": self.data, "text/plain": pretty(self.data)}


class Image:
    def __init__(self, data=None, url=None, filename=None, format=None, width=None, height=None):
        if data is None and filename:
            with open(filename, "rb") as f:
                data = f.read()
        if isinstance(data, str) and os.path.exists(data):
            with open(data, "rb") as f:
                data = f.read()
        self.data = data or b""
        self.format = (format or ("jpeg" if self.data[:3] == b"\xff\xd8\xff" else "png")).lower()
        self.width = width
        self.height = height

    def _repr_mimebundle_(self, include=None, exclude=None):
        mime = "image/jpeg" if self.format in ("jpg", "jpeg") else "image/png"
        md = {}
        if self.width:
            md["width"] = self.width
        if self.height:
            md["height"] = self.height
        return ({mime: base64.b64encode(self.data).decode("ascii"), "text/plain": "<Image>"},
                {mime: md} if md else {})

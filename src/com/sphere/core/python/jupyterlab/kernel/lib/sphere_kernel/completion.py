"""Completion and documentation, asked of the live objects.

Completion knows what only a running kernel can: the keys of a dict, the
columns of a DataFrame, the branches of a ROOT or uproot tree and the fields
of an awkward array after obj[" ; the files of the folder inside a string;
the magics; the parameters of the call being typed. Jedi, when installed,
completes the rest (it reads the namespace too); without it, the attributes
and names of the namespace do.

Documentation (Shift+Tab, obj?) gives the signature, the docstring, the file,
and for data what matters in physics: shape, dtype and device of an array or
tensor, the size of a frame, the type of a jagged array, the entries of a tree.
"""

import builtins
import inspect
import keyword
import os
import re

_jedi = None
_jedi_failed = False

_KEYS_CONTEXT = re.compile(r"""([A-Za-z_][\w.]*(?:\[[^\[\]]*\])*)\s*\[\s*(["'])([^"']*)$""")
_STRING_CONTEXT = re.compile(r"""(["'])([^"'\n]*)$""")
_ATTR_CONTEXT = re.compile(r"([A-Za-z_][\w]*(?:\.[A-Za-z_]\w*)*)\.(\w*)$")
_NAME_CONTEXT = re.compile(r"[A-Za-z_]\w*$")


def _get_jedi():
    global _jedi, _jedi_failed
    if _jedi is None and not _jedi_failed:
        try:
            import jedi
            _jedi = jedi
        except Exception:
            _jedi_failed = True
    return _jedi


def resolve(dotted, namespace):
    """The object a dotted name designates, following attributes only: nothing is called."""
    parts = dotted.split(".")
    if parts[0] in namespace:
        obj = namespace[parts[0]]
    elif hasattr(builtins, parts[0]):
        obj = getattr(builtins, parts[0])
    else:
        raise LookupError(parts[0])
    for p in parts[1:]:
        obj = getattr(obj, p)
    return obj


def resolve_expression(expr, namespace):
    """A dotted name, possibly indexed by literals (events["jets"]), without calling anything."""
    m = re.match(r"^([A-Za-z_][\w.]*)((?:\[[^\[\]]*\])*)$", expr.strip())
    if not m:
        raise LookupError(expr)
    obj = resolve(m.group(1), namespace)
    for index in re.findall(r"\[([^\[\]]*)\]", m.group(2)):
        index = index.strip()
        if len(index) >= 2 and index[0] in "\"'" and index[-1] == index[0]:
            obj = obj[index[1:-1]]
        elif re.fullmatch(r"-?\d+", index):
            obj = obj[int(index)]
        else:
            raise LookupError(index)
    return obj


def keys_of(obj):
    """What obj["..."] accepts: dict keys, columns, tree branches, record fields."""
    mod = type(obj).__module__ or ""
    try:
        if isinstance(obj, dict):
            return [k for k in obj if isinstance(k, str)]
        if mod.startswith("awkward"):
            return list(getattr(obj, "fields", []) or [])
        if mod.startswith("uproot"):
            keys = getattr(obj, "keys", None)
            if callable(keys):
                return [str(k).split(";")[0] for k in keys()]
        if mod.startswith("pandas") or mod.startswith("polars"):
            return [str(c) for c in getattr(obj, "columns", [])]
        if mod.startswith(("cppyy", "ROOT")) and hasattr(obj, "GetListOfBranches"):
            return [b.GetName() for b in obj.GetListOfBranches()]
        if mod.startswith(("cppyy", "ROOT")) and hasattr(obj, "GetListOfKeys"):
            return [k.GetName() for k in obj.GetListOfKeys()]
        if hasattr(obj, "dtype") and getattr(obj.dtype, "names", None):
            return list(obj.dtype.names)  # a structured NumPy array
        keys = getattr(obj, "keys", None)
        if callable(keys):
            return [k for k in keys() if isinstance(k, str)]
    except Exception:
        pass
    return []


def _kind(obj):
    if inspect.ismodule(obj):
        return "module"
    if inspect.isclass(obj):
        return "class"
    if inspect.isroutine(obj) or callable(obj):
        return "function"
    return "instance"


def complete(code, cursor, namespace, magics=()):
    """(matches, start, end, types) for the text before the cursor."""
    before = code[:cursor]
    line = before[before.rfind("\n") + 1:]

    # obj["...  : keys, columns, branches, fields
    m = _KEYS_CONTEXT.search(line)
    if m:
        try:
            obj = resolve_expression(m.group(1), namespace)
            prefix = m.group(3)
            keys = [k for k in keys_of(obj) if k.startswith(prefix)]
            if keys:
                return sorted(keys), cursor - len(prefix), cursor, ["key"] * len(keys)
        except Exception:
            pass

    # %magic
    stripped = line.lstrip()
    if stripped.startswith("%") and " " not in stripped:
        prefix = stripped.lstrip("%")
        marks = stripped[:len(stripped) - len(prefix)]
        found = sorted("%s%s" % (marks, name) for name in magics if name.startswith(prefix))
        return found, cursor - len(stripped), cursor, ["magic"] * len(found)

    # a path inside a string
    s = _STRING_CONTEXT.search(line)
    if s and line.count(s.group(1)) % 2 == 1 and ("/" in s.group(2) or "\\" in s.group(2) or "." in s.group(2)
                                                   or re.search(r"(open|read\w*|load\w*|Path|TFile\w*|uproot\.open)\s*\(\s*[\"']$",
                                                                line[:s.start() + 1])):
        typed = s.group(2)
        folder, base = os.path.split(typed)
        try:
            entries = sorted(os.scandir(folder or "."), key=lambda e: e.name.lower())
            found = []
            for e in entries:
                if e.name.startswith(base) and not (e.name.startswith(".") and not base.startswith(".")):
                    found.append(e.name + ("/" if e.is_dir() else ""))
            if found:
                return found, cursor - len(base), cursor, ["path"] * len(found)
        except OSError:
            pass

    jedi = _get_jedi()
    if jedi is not None:
        try:
            row = before.count("\n") + 1
            column = len(line)
            completions = jedi.Interpreter(code, [namespace]).complete(row, column)
            if completions:
                first = completions[0]
                typed = len(first.name) - len(first.complete)
                matches = [c.name_with_symbols for c in completions[:500]]
                types = [c.type for c in completions[:500]]
                return matches, cursor - typed, cursor, types
        except Exception:
            pass

    # obj.attr
    a = _ATTR_CONTEXT.search(line)
    if a:
        try:
            obj = resolve(a.group(1), namespace)
            prefix = a.group(2)
            names = sorted(n for n in dir(obj) if n.startswith(prefix) and (prefix.startswith("_") or not n.startswith("_")))
            types = []
            for n in names:
                try:
                    types.append(_kind(getattr(obj, n)))
                except Exception:
                    types.append("instance")
            return names, cursor - len(prefix), cursor, types
        except Exception:
            return [], cursor, cursor, []

    # a name, and the parameters of the call around the cursor
    w = _NAME_CONTEXT.search(line)
    prefix = w.group(0) if w else ""
    found = {}
    for name in call_parameters(before, namespace):
        if name.startswith(prefix):
            found[name + "="] = "param"
    for name in list(namespace) + dir(builtins):
        if name.startswith(prefix) and not name.startswith("__"):
            try:
                found.setdefault(name, _kind(namespace[name] if name in namespace else getattr(builtins, name)))
            except Exception:
                found.setdefault(name, "instance")
    for kw in keyword.kwlist:
        if kw.startswith(prefix):
            found.setdefault(kw, "keyword")
    names = sorted(found, key=lambda n: (not n.endswith("="), n.lower()))
    return names, cursor - len(prefix), cursor, [found[n] for n in names]


def call_parameters(before, namespace):
    """The parameter names of the call whose parentheses the cursor is in."""
    depth = 0
    for i in range(len(before) - 1, -1, -1):
        c = before[i]
        if c in ")]}":
            depth += 1
        elif c in "([{":
            if depth == 0:
                if c != "(":
                    return []
                m = re.search(r"([A-Za-z_][\w.]*)\s*$", before[:i])
                if not m:
                    return []
                try:
                    sig = inspect.signature(resolve(m.group(1), namespace))
                except Exception:
                    return []
                return [p.name for p in sig.parameters.values()
                        if p.kind in (p.POSITIONAL_OR_KEYWORD, p.KEYWORD_ONLY)]
            depth -= 1
    return []


# ---- Documentation -----------------------------------------------------------------

_INSPECT_NAME = re.compile(r"[A-Za-z_][\w.]*(?:\[[^\[\]]*\])*$")


def name_at(code, cursor):
    """The expression documentation is asked for: the name under the cursor, or the call it is in."""
    before = code[:cursor]
    after = code[cursor:]
    m = re.match(r"[\w]*", after)
    word_end = cursor + (m.end() if m else 0)
    candidate = _INSPECT_NAME.search(code[:word_end])
    if candidate and candidate.group(0):
        return candidate.group(0)
    depth = 0
    for i in range(len(before) - 1, -1, -1):
        c = before[i]
        if c in ")]}":
            depth += 1
        elif c in "([{":
            if depth == 0:
                n = _INSPECT_NAME.search(before[:i].rstrip())
                return n.group(0) if n else ""
            depth -= 1
    return ""


def describe_data(obj):
    """What a physicist wants to know of a value at a glance: shape, dtype, device, size."""
    mod = type(obj).__module__ or ""
    name = type(obj).__name__
    try:
        if mod.startswith("torch") and hasattr(obj, "shape") and hasattr(obj, "device"):
            grad = ", requires_grad" if getattr(obj, "requires_grad", False) else ""
            return "%s %s on %s%s" % (str(obj.dtype).replace("torch.", ""), tuple(obj.shape), obj.device, grad)
        if mod.startswith("awkward"):
            return "%d entries, type %s" % (len(obj), str(obj.type)[:120])
        if mod.startswith(("pandas", "polars")) and hasattr(obj, "shape"):
            shape = obj.shape
            return "%d rows x %d columns" % shape if len(shape) == 2 else "%d rows" % shape[0]
        if hasattr(obj, "shape") and hasattr(obj, "dtype") and not callable(obj.shape):
            size = getattr(obj, "nbytes", None)
            mem = (", %s" % _bytes(size)) if isinstance(size, int) else ""
            return "%s %s%s" % (getattr(obj.dtype, "name", obj.dtype), tuple(obj.shape), mem)
        if mod.startswith("uproot") and hasattr(obj, "num_entries"):
            return "%d entries, %d branches" % (obj.num_entries, len(obj.keys()))
        if mod.startswith(("cppyy", "ROOT")):
            if hasattr(obj, "GetEntries") and hasattr(obj, "GetNbinsX"):
                return "%s '%s': %d entries, %d bins" % (obj.ClassName(), obj.GetName(), obj.GetEntries(), obj.GetNbinsX())
            if hasattr(obj, "GetEntries"):
                return "%s '%s': %d entries" % (obj.ClassName(), obj.GetName(), obj.GetEntries())
        if isinstance(obj, (list, tuple, set, frozenset, dict)):
            return "%d elements" % len(obj)
        if isinstance(obj, (str, bytes)):
            return "%d characters" % len(obj) if isinstance(obj, str) else "%d bytes" % len(obj)
    except Exception:
        pass
    return ""


def _bytes(n):
    for unit in ("B", "kB", "MB", "GB"):
        if n < 1024:
            return "%.0f %s" % (n, unit) if unit == "B" else "%.1f %s" % (n, unit)
        n /= 1024.0
    return "%.1f TB" % n


def documentation(expr, namespace, detail=0):
    """The text of obj? (detail 0) or obj?? (detail 1, with the source); None if unknown."""
    try:
        obj = resolve_expression(expr, namespace)
    except Exception:
        if keyword.iskeyword(expr):
            return "%s is a Python keyword." % expr
        return None
    lines = []
    if inspect.isclass(obj) or inspect.isroutine(obj) or callable(obj) and not hasattr(obj, "shape"):
        try:
            lines.append("Signature: %s%s" % (expr.split(".")[-1], inspect.signature(obj)))
        except (TypeError, ValueError):
            pass
    else:
        text = repr(obj)
        if len(text) > 400:
            text = text[:400] + "..."
        lines.append("String form: %s" % text)
        data = describe_data(obj)
        if data:
            lines.append("Data:        %s" % data)
    t = type(obj)
    lines.append("Type:        %s" % (t.__name__ if t.__module__ in ("builtins", None) else "%s.%s" % (t.__module__, t.__qualname__)))
    try:
        f = inspect.getsourcefile(obj) or inspect.getfile(obj)
        if f:
            lines.append("File:        %s" % f)
    except (TypeError, OSError):
        pass
    doc = inspect.getdoc(obj)
    if doc and not (not inspect.isclass(obj) and not callable(obj) and doc == inspect.getdoc(t)):
        lines.append("Docstring:\n%s" % doc)
    elif doc is None:
        lines.append("Docstring:   <no docstring>")
    if detail:
        try:
            lines.append("Source:\n%s" % inspect.getsource(obj))
        except (TypeError, OSError):
            pass
    return "\n".join(lines)

"""A notebook cell's own syntax, made Python.

    %%magic args        get_ipython().run_cell_magic('magic', 'args', body)
    %magic args         get_ipython().run_line_magic('magic', 'args')
    !command            get_ipython().system('command')
    x = !command        x = get_ipython().getoutput('command')
    x = %magic args     x = get_ipython().run_line_magic('magic', 'args')
    obj? / obj??        get_ipython().inspect_object('obj', 0 / 1)

Only at the head of a statement: a % inside an expression is a modulo, a ! in
a string is a character. The scanner below follows strings (every prefix,
triple quotes), brackets and backslash continuations to know where statements
start, the way Python's tokenizer would.
"""

import re

_ASSIGN = re.compile(r"^(\s*)((?:[A-Za-z_][\w.]*\s*,\s*)*[A-Za-z_][\w.]*)\s*=\s*([!%])(.*)$")
_HELP = re.compile(r"^(\s*)(\?{1,2})?([A-Za-z_][\w.]*(?:\[[^\]]*\])?)(\?{1,2})?\s*$")
_PREFIX = re.compile(r"[rRbBuUfFtT]{1,2}$")


def _split_magic(text):
    text = text.strip()
    name, _, args = text.partition(" ")
    return name, args.strip()


def statement_starts(source):
    """The indices of the lines that begin a statement (0-based): every line, empty ones and the last included,
    that is not inside a string, a bracket or a backslash continuation."""
    starts = {0}
    depth = 0
    quote = None      # the quote that closes the current string, or None
    i = 0
    n = len(source)
    line = 0

    def newline(continued=False):
        nonlocal line
        line += 1
        if quote is None and depth == 0 and not continued:
            starts.add(line)

    while i < n:
        c = source[i]
        if quote is not None:
            if c == "\\":
                if i + 1 < n and source[i + 1] == "\n":
                    newline(True)
                i += 2
                continue
            if source.startswith(quote, i):
                i += len(quote)
                quote = None
                continue
            if c == "\n":
                if len(quote) == 1:
                    quote = None  # an unterminated string ends with its line
                newline()
            i += 1
            continue
        if c == "#":
            while i < n and source[i] != "\n":
                i += 1
            continue
        if c in "\"'":
            triple = source[i:i + 3] == c * 3
            quote = c * 3 if triple else c
            i += 3 if triple else 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth = max(0, depth - 1)
        elif c == "\\" and i + 1 < n and source[i + 1] == "\n":
            i += 2
            newline(True)
            continue
        elif c == "\n":
            newline()
        i += 1
    return starts


def transform_cell(source):
    """Python source for a cell written with magics, shell escapes and help."""
    if not source:
        return source
    lines = source.split("\n")
    # A cell magic owns the whole cell.
    first = 0
    while first < len(lines) and not lines[first].strip():
        first += 1
    if first < len(lines) and lines[first].lstrip().startswith("%%"):
        name, args = _split_magic(lines[first].lstrip()[2:])
        body = "\n".join(lines[first + 1:])
        return "get_ipython().run_cell_magic(%r, %r, %r)\n" % (name, args, body)
    starts = statement_starts(_mask_magics(lines))
    out = []
    for index, raw in enumerate(lines):
        if index not in starts:
            out.append(raw)
            continue
        out.append(_transform_line(raw))
    return "\n".join(out)


def _mask_magics(lines):
    """The cell with its magic and shell lines blanked, so their quotes do not open strings."""
    masked = []
    for raw in lines:
        s = raw.lstrip()
        if s.startswith(("%", "!")) or _ASSIGN.match(raw) or (s.endswith("?") and not s.startswith("#")):
            masked.append("")
        else:
            masked.append(raw)
    return "\n".join(masked)


def _transform_line(raw):
    stripped = raw.lstrip()
    indent = raw[:len(raw) - len(stripped)]
    if not stripped or stripped.startswith("#"):
        return raw
    if stripped.startswith("%%"):
        return raw  # a cell magic after the first line is not one
    if stripped.startswith("%"):
        name, args = _split_magic(stripped[1:])
        return "%sget_ipython().run_line_magic(%r, %r)" % (indent, name, args)
    if stripped.startswith("!!"):
        return "%sget_ipython().getoutput(%r)" % (indent, stripped[2:])
    if stripped.startswith("!"):
        return "%sget_ipython().system(%r)" % (indent, stripped[1:])
    m = _ASSIGN.match(raw)
    if m:
        lead, target, kind, rest = m.groups()
        if kind == "!":
            return "%s%s = get_ipython().getoutput(%r)" % (lead, target, rest.strip())
        name, args = _split_magic(rest)
        return "%s%s = get_ipython().run_line_magic(%r, %r)" % (lead, target, name, args)
    if stripped.endswith("?") or stripped.startswith("?"):
        h = _HELP.match(raw)
        if h and (h.group(2) or h.group(4)):
            marks = h.group(4) or h.group(2)
            return "%sget_ipython().inspect_object(%r, %d)" % (h.group(1), h.group(3), len(marks) - 1)
    return raw


def is_complete(source):
    """('complete' | 'incomplete' | 'invalid', indent) for a console's Enter."""
    import codeop
    if not source.strip():
        return "complete", ""
    lines = source.rstrip("\n").split("\n")
    if lines[0].lstrip().startswith("%%"):
        return ("complete", "") if source.endswith("\n\n") else ("incomplete", "")
    python = transform_cell(source)
    try:
        code = codeop.compile_command(python, "<input>", "exec")
    except (SyntaxError, OverflowError, ValueError):
        return "invalid", ""
    if code is None:
        last = lines[-1]
        indent = last[:len(last) - len(last.lstrip())]
        if last.rstrip().endswith(":"):
            indent += "    "
        return "incomplete", indent
    if lines[-1].startswith((" ", "\t")) and not source.endswith("\n"):
        return "incomplete", lines[-1][:len(lines[-1]) - len(lines[-1].lstrip())]
    return "complete", ""

"""Extract Geant4's public C++ API statically from the Geant4 source tree.

Usage: python -I g4api.py <geant4-source-root> <out.txt.gz>

Reads every header under source/**/include/, finds the classes and structs
declared at namespace scope, their bases and their public constructors and
methods, with the comment that documents each one: the Doxygen block above it
(Geant4 master documents most headers so), or the // block old headers write
under a group of declarations. Hover and completion in Sphere then show
Geant4's own words.

The output is a gzipped text file, one record per line, fields split by '|':
  # <version>
  @<class>|<bases, comma separated>|<category>|<header>|<doc>
  c|<params>|<doc>                      a constructor of the last class
  m|<return type>|<name>|<params>|<doc>  a method (s instead of m: static)
Docs keep the brief sentence; ' ¶ ' joins the parameter notes to it.
"""
import bisect
import gzip
import os
import re
import sys

SKIP_DIRS = {"externals", "g3tog4"}
SPECIFIERS = re.compile(r"\b(virtual|inline|static|explicit|constexpr|consteval|friend|extern|"
                        r"G4DLLEXPORT|G4DLLIMPORT|G4GLOB_DLL|G4ThreadLocal|G4MT_TLS_)\b|\[\[[^\]]*\]\]")
DOXYGEN = ("/**", "///", "//!", "/*!")
MAX_DOC = 220
MAX_PARAM_DOC = 90
JUNK_DOC = re.compile(r"^(forward declarations?|includes?|constructors?( and destructors?)?(/destructors?)?|"
                      r"destructors?|(default )?(constructor|destructor)s?|accessors?( and modifiers)?|modifiers?|"
                      r"(public |protected |private )?methods?|get/set methods?|set/get methods?|data members?|"
                      r"copy constructor and assignment operator|operators?|inline methods?|inline functions?|"
                      r"getters?( and setters)?|setters?)\.?$", re.I)


def scan(src):
    """The text with preprocessor lines, comments and string contents blanked (offsets hold), and the comments.

    Comments come back as (start, end, text), in order.
    """
    out = list(src)
    comments = []
    i, n = 0, len(src)
    line_start = True
    while i < n:
        c = src[i]
        if line_start and c == "#":
            k = i
            while True:
                k = src.find("\n", k)
                k = n if k < 0 else k
                if k < n and k > 0 and src[k - 1] == "\\":
                    k += 1
                    continue
                break
            for t in range(i, k):
                if out[t] != "\n":
                    out[t] = " "
            i = k
            continue
        if c == "\n":
            line_start = True
            i += 1
            continue
        if not c.isspace():
            line_start = False
        if c == '"' or c == "'":
            j = i + 1
            while j < n and src[j] != c and src[j] != "\n":
                j += 2 if src[j] == "\\" else 1
            for t in range(i + 1, min(j, n)):
                out[t] = " "
            i = j + 1
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            comments.append((i, j, src[i:j]))
            for t in range(i, j):
                out[t] = " "
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            comments.append((i, j, src[i:j]))
            for t in range(i, j):
                if out[t] != "\n":
                    out[t] = " "
            i = j
        else:
            i += 1
    return "".join(out), comments


def doc_before(src, comments, ends, pos):
    """The comment block ending right before pos.

    A Doxygen block may stand a blank line above the declaration; a plain //
    block must touch it, and must not itself trail the code above it.
    """
    block = []
    k = pos
    idx = bisect.bisect_right(ends, pos) - 1
    while idx >= 0:
        a, b, text = comments[idx]
        gap = src[b:k]
        if gap.strip():
            break
        if gap.count("\n") > (2 if text.startswith(DOXYGEN) else 1):
            break
        block.insert(0, text)
        k = a
        idx -= 1
    if not block:
        return ""
    if not block[0].startswith(DOXYGEN):
        line_start = src.rfind("\n", 0, k)
        prev_start = src.rfind("\n", 0, max(line_start, 0))
        prev = src[prev_start + 1:line_start].strip() if line_start > 0 else ""
        if prev and not prev.startswith(("//", "/*", "*")) and prev.endswith((";", "}")):
            return ""
    return clean_doc("\n".join(block))


def doc_after(src, comments, ends, pos):
    """A // comment on the same line, after the declaration."""
    eol = src.find("\n", pos)
    eol = len(src) if eol < 0 else eol
    idx = bisect.bisect_left(ends, pos)
    while idx < len(comments):
        a, b, text = comments[idx]
        if a >= eol:
            break
        if a >= pos:
            return clean_doc(text)
        idx += 1
    return ""


def doc_trailing(src, end):
    """The // block old headers write under a group of declarations, closed by a blank line."""
    eol = src.find("\n", end)
    for _ in range(10):
        if eol < 0:
            return ""
        nxt = src.find("\n", eol + 1)
        line = src[eol + 1:nxt if nxt >= 0 else len(src)].strip()
        if not line or line.startswith(("public:", "protected:", "private:", "}", "/*", "///", "#")):
            return ""
        if line.startswith("//"):
            block = []
            while line.startswith("//") and not line.startswith("///"):
                block.append(line)
                eol = nxt
                if eol < 0:
                    line = ""
                    break
                nxt = src.find("\n", eol + 1)
                line = src[eol + 1:nxt if nxt >= 0 else len(src)].strip()
            if line and not line.startswith(("public:", "protected:", "private:", "}")):
                return ""
            return clean_doc("\n".join(block))
        eol = nxt
    return ""


def class_doc(src, comments, name):
    """The description at the top of a Geant4 header: the block after '// <name>' or 'Class description:'."""
    lines = [text for (a, b, text) in comments if text.startswith("//") and a < len(src) // 2]
    for i, l in enumerate(lines):
        t = re.sub(r"^//+", "", l).strip()
        if t == name or t.lower().startswith("class description"):
            body = []
            for l2 in lines[i + 1:i + 30]:
                t2 = re.sub(r"^//+", "", l2).strip()
                if t2.lower().startswith("class description") or (not t2 and not body):
                    continue
                if (not t2 and body) or re.match(r"^-{3,}", t2) or t2.startswith(("Author", "History")):
                    break
                body.append("// " + t2)
            if body:
                return clean_doc("\n".join(body))
    return ""


def clean_doc(raw):
    if not raw:
        return ""
    lines = []
    for l in raw.split("\n"):
        l = l.strip()
        l = re.sub(r"^(/\*\*?!?|\*/|\*|///?<?|//!<?)", "", l).strip()
        l = re.sub(r"\*/$", "", l).strip()
        if re.fullmatch(r"[-=*_/ ]*", l):
            l = ""
        lines.append(l)
    brief, params, cur = [], [], None
    for l in lines:
        m = re.match(r"[@\\]param(?:\[[a-z, ]*\])?\s+(\w+)\s*(.*)", l)
        if m:
            cur = [m.group(1), m.group(2)]
            params.append(cur)
            continue
        if re.match(r"[@\\](returns?|retval)\b", l):
            cur = ["returns", re.sub(r"^[@\\](returns?|retval)\s*", "", l)]
            params.append(cur)
            continue
        if re.match(r"[@\\](ingroup|author|date|file|class|struct|see|sa|note|warning|deprecated|todo|"
                    r"tparam|code|endcode|copydoc|defgroup|addtogroup|\{|\})", l):
            cur = None
            continue
        if l.startswith(("Author", "History", "Created", "Modified", "Revision", "Original author")) or \
                re.match(r"^\d\d[./-]\d\d[./-]\d\d", l):
            break
        if not l:
            if brief and not params:
                break
            cur = None
            continue
        if cur is not None:
            cur[1] += " " + l
        elif not params:
            brief.append(re.sub(r"^[@\\]brief\s*", "", l))
    text = re.sub(r"\s+", " ", " ".join(brief)).strip()
    m = re.match(r"(.+?[.!?])(\s|$)", text)
    if m and len(m.group(1)) > 25:
        text = m.group(1)
    if len(text) > MAX_DOC:
        text = text[:MAX_DOC - 1].rsplit(" ", 1)[0] + "…"
    notes = []
    for name, d in params:
        d = re.sub(r"\s+", " ", d).strip()
        if not d:
            continue
        if len(d) > MAX_PARAM_DOC:
            d = d[:MAX_PARAM_DOC - 1].rsplit(" ", 1)[0] + "…"
        notes.append(name + ": " + d)
    if JUNK_DOC.match(text):
        text = ""
    out = text
    if notes:
        out += " ¶ " + "; ".join(notes)
    return out.replace("|", "/").strip()


def match_brace(text, i, open_c="{", close_c="}"):
    depth = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == open_c:
            depth += 1
        elif c == close_c:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return n - 1


def norm(s):
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"\s*([(),<>&*:])\s*", r"\1", s)
    s = s.replace(",", ", ")
    s = re.sub(r"(\w)([*&]+)(\w)", r"\1\2 \3", s)
    s = re.sub(r"\b(const|unsigned|long|short|signed|struct|class|enum)(\w)", r"\1 \2", s)
    return s


CLASS_RE = re.compile(r"\b(class|struct)\s+(?:G4DLLEXPORT\s+|G4DLLIMPORT\s+|alignas\([^)]*\)\s+)?"
                      r"([A-Za-z_]\w*)\s*(final\s*)?(:[^;{]*)?\{")
SCOPE_RE = re.compile(r"\bnamespace\s+[\w:]*\s*\{|\bextern\s+\"C\"\s*\{|[{}]")


def classes(text):
    """(kind, name, bases text, body start, body end, declaration start) of the classes at namespace scope."""
    out = []
    i = 0
    while True:
        m = CLASS_RE.search(text, i)
        if not m:
            break
        if not at_namespace_scope(text, m.start()):
            i = m.end()
            continue
        body_start = m.end() - 1
        body_end = match_brace(text, body_start)
        out.append((m.group(1), m.group(2), m.group(4) or "", body_start + 1, body_end, m.start()))
        i = body_end + 1
    return out


def at_namespace_scope(text, end):
    depth = 0
    ns = []
    for m in SCOPE_RE.finditer(text, 0, end):
        tok = m.group(0)
        if tok == "{":
            depth += 1
        elif tok == "}":
            if ns and ns[-1] == depth:
                ns.pop()
            depth -= 1
        else:
            depth += 1
            ns.append(depth)
    return depth == len(ns)


def bases(text):
    out = []
    for b in re.split(r",(?![^<]*>)", text.lstrip(":")):
        b = re.sub(r"\b(public|protected|private|virtual)\b", "", b).strip()
        if b:
            out.append(re.sub(r"\s+", "", b))
    return out


def members(body, kind, cls, src, comments, ends, offset):
    """Public constructors and methods of a class body; offsets are the file's."""
    access = "public" if kind == "struct" else "private"
    i, n = 0, len(body)
    out = []
    stmt_start = 0
    while i < n:
        c = body[i]
        if c == "{":
            j = match_brace(body, i)
            head = body[stmt_start:i]
            if re.search(r"\b(class|struct|union|enum)\b", head) and "(" not in head:
                k = body.find(";", j + 1)
                stmt_start = (k + 1) if k >= 0 else n
                i = stmt_start
                continue
            if access == "public":
                add_member(head, cls, src, comments, ends, offset + stmt_start, out)
            i = j + 1
            while i < n and body[i] in " \t\r\n;":
                i += 1
            stmt_start = i
            continue
        if c == ";":
            if access == "public":
                add_member(body[stmt_start:i], cls, src, comments, ends, offset + stmt_start, out)
            stmt_start = i + 1
        elif c == ":" and body[i + 1:i + 2] != ":" and body[i - 1:i] != ":":
            label = body[stmt_start:i].strip()
            if label in ("public", "protected", "private"):
                access = label
                stmt_start = i + 1
        i += 1
    return out


def add_member(decl, cls, src, comments, ends, at, out):
    raw = decl
    decl = decl.strip()
    if decl.startswith("template"):
        decl = re.sub(r"^template\s*<[^>]*>\s*", "", decl)
    if "(" not in decl or decl.startswith(("using ", "typedef ", "friend ", "static_assert")):
        return
    if re.search(r"=\s*delete\s*$", decl):
        return
    p = decl.find("(")
    close = match_brace(decl, p, "(", ")")
    head, params = decl[:p], decl[p + 1:close]
    is_static = bool(re.search(r"\bstatic\b", head))
    head = SPECIFIERS.sub(" ", head).strip()
    m = re.search(r"(operator\s*\S+|~?[A-Za-z_]\w*)\s*$", head)
    if not m:
        return
    name = m.group(1)
    if name.startswith(("~", "operator")) or name in ("if", "for", "while", "return", "G4DECLARE"):
        return
    ret = head[:m.start()].strip()
    lead = len(raw) - len(raw.lstrip())
    doc = doc_before(src, comments, ends, at + lead) or doc_after(src, comments, ends, at + len(raw)) \
        or doc_trailing(src, at + len(raw))
    params = norm(params)
    if params == "void":
        params = ""
    if name == cls and not ret:
        out.append(("c", "", name, params, doc))
    elif ret:
        out.append(("s" if is_static else "m", norm(ret), name, params, doc))


def category(path):
    p = path.replace("\\", "/")
    k = p.find("/source/")
    rest = p[k + 8:] if k >= 0 else p
    j = rest.find("/include")
    return rest[:j] if j >= 0 else os.path.dirname(rest)


def version(root):
    path = os.path.join(root, "source", "global", "management", "include", "G4Version.hh")
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            t = f.read()
    except OSError:
        return "geant4"
    tag = re.search(r'G4Version\s*=\s*"\$Name:\s*([^$]*)\$"', t)
    date = re.search(r'G4Date\s*=\s*"\(([^)]*)\)"', t)
    return ((tag.group(1).strip() if tag else "geant4") + (" (" + date.group(1) + ")" if date else "")).strip()


def main(root, out_path):
    records = {}
    for dirpath, dirnames, filenames in os.walk(os.path.join(root, "source")):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        if os.path.basename(dirpath) != "include":
            continue
        for fn in sorted(filenames):
            if not fn.endswith((".hh", ".h")):
                continue
            path = os.path.join(dirpath, fn)
            with open(path, encoding="utf-8", errors="replace") as f:
                src = f.read()
            text, comments = scan(src)
            ends = [c[1] for c in comments]
            for kind, name, base_text, b0, b1, start in classes(text):
                if not name.startswith("G4") or name in records:
                    continue
                doc = doc_before(src, comments, ends, start)
                if not doc:
                    t = text.rfind("template", 0, start)
                    if t >= 0 and ";" not in text[t:start] and "}" not in text[t:start]:
                        doc = doc_before(src, comments, ends, t)
                if not doc:
                    doc = class_doc(src, comments, name)
                ms = members(text[b0:b1], kind, name, src, comments, ends, b0)
                records[name] = (bases(base_text), category(path), fn, doc, ms)
    lines = ["# " + version(root)]
    for name in sorted(records):
        bs, cat, header, doc, ms = records[name]
        lines.append("@" + "|".join([name, ",".join(bs), cat, header, doc]))
        seen = set()
        for m in ms:
            key = (m[0] == "c", m[2], m[3])
            if key in seen:
                continue
            seen.add(key)
            if m[0] == "c":
                lines.append("c|" + m[3] + "|" + m[4])
            else:
                lines.append(m[0] + "|" + m[1] + "|" + m[2] + "|" + m[3] + "|" + m[4])
    data = ("\n".join(lines) + "\n").encode("utf-8")
    with gzip.open(out_path, "wb", compresslevel=9) as f:
        f.write(data)
    n_classes = sum(1 for l in lines if l.startswith("@"))
    print("%d classes, %d members, %.1f MB of text, %s" % (n_classes, len(lines) - n_classes - 1, len(data) / 1e6,
                                                           out_path))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    main(sys.argv[1], sys.argv[2])

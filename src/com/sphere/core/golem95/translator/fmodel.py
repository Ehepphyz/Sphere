"""Fortran 90 program units: modules, derived types, interfaces, procedures and their statements."""
import re

from fsrc import (ParseError, parse_expr, split_top, matching_paren, Parser, Name, Call, Comp, Arg)


class Use:
    def __init__(self, module, only, renames, line):
        self.module = module          # used module
        self.only = only              # None (everything) or list of (local, remote) names
        self.renames = renames        # list of (local, remote) for a full use with renames
        self.line = line


class VarDecl:
    def __init__(self, name, base, dtype, char_len, dims, attrs, intent, init, ptr_init, line):
        self.name = name
        self.base = base              # integer real complex logical character type procedure
        self.dtype = dtype            # derived type name
        self.char_len = char_len      # '*' or an expression text, for character
        self.dims = dims              # None (scalar) or list of (lo, hi) texts; hi ':' deferred, '*' assumed size
        self.attrs = attrs            # set of attribute words
        self.intent = intent          # in out inout or None
        self.init = init              # initializer expression text
        self.ptr_init = ptr_init      # => null()
        self.line = line

    @property
    def rank(self):
        return 0 if self.dims is None else len(self.dims)

    def __repr__(self):
        return f"VarDecl({self.name}:{self.base}{'(' + self.dtype + ')' if self.dtype else ''} rank{self.rank} {sorted(self.attrs)})"


class TypeDef:
    def __init__(self, name, line):
        self.name = name
        self.components = []          # VarDecl
        self.line = line


class Interface:
    def __init__(self, kind, name, line):
        self.kind = kind              # generic operator assignment explicit
        self.name = name              # generic name, operator symbol or '='
        self.procs = []               # module procedure names
        self.bodies = []              # Proc headers of an explicit interface
        self.line = line


class Proc:
    def __init__(self, name, kind, args, result, prefixes, rtype, line, file):
        self.name = name
        self.kind = kind              # function subroutine
        self.args = args              # dummy argument names
        self.result = result          # result variable name (functions)
        self.prefixes = prefixes      # recursive pure elemental
        self.rtype = rtype            # type spec text given in front of 'function', if any
        self.uses = []
        self.decls = {}               # name -> VarDecl
        self.order = []               # declaration order
        self.interfaces = []          # explicit interfaces of dummy procedures
        self.body = []
        self.internal = []            # contained procedures
        self.host = None
        self.module = None
        self.line = line
        self.file = file
        self.access_stmts = []

    def __repr__(self):
        return f"Proc({self.kind} {self.name}({','.join(self.args)}))"


class Module:
    def __init__(self, name, line, file):
        self.name = name
        self.uses = []
        self.decls = {}
        self.order = []
        self.types = {}
        self.interfaces = []
        self.procs = {}
        self.default_private = False
        self.private = set()
        self.public = set()
        self.line = line
        self.file = file


# --------------------------------------------------------------------------
# Executable statements
# --------------------------------------------------------------------------

class S:
    """An executable statement: kind, fields, and where it comes from."""

    def __init__(self, kind, line, **fields):
        self.kind = kind
        self.line = line
        self.__dict__.update(fields)

    def __repr__(self):
        return f"S({self.kind}@{self.line})"


TYPE_START = re.compile(r"^(integer|real|complex|logical|character|double\s*precision|double\s*complex|type|procedure)\b(?!\s*=)")


def parse_type_spec(text):
    """(base, kind_text, dtype, char_len, rest) for a declaration's leading type spec."""
    m = TYPE_START.match(text)
    if not m:
        return None
    word = re.sub(r"\s+", " ", m.group(1))
    i = m.end()
    rest = text[i:]
    s = rest.lstrip()
    j = len(rest) - len(s)
    paren = None
    if s.startswith("("):
        k = matching_paren(s, 0)
        paren = s[1:k].strip()
        s = s[k + 1:]
    elif s.startswith("*"):
        mm = re.match(r"\*\s*(\(\s*\*\s*\)|\d+)", s)
        if mm:
            paren = "len=" + mm.group(1).strip("() ") if word == "character" else None
            s = s[mm.end():]
    base, dtype, char_len = word, None, None
    if word == "double precision":
        base = "real"
    elif word == "double complex":
        base = "complex"
    elif word == "type":
        if paren is None:
            return None            # 'type name' defines a type: not a declaration
        base, dtype = "type", paren.strip()
    elif word == "procedure":
        base, dtype = "procedure", paren
    elif word == "character":
        if paren:
            mm = re.match(r"(?:len\s*=\s*)?([^,]+)", paren)
            char_len = mm.group(1).strip() if mm else "1"
            if paren.startswith("kind"):
                char_len = "1"
        else:
            char_len = "1"
    return base, paren, dtype, char_len, s


def parse_dims(text):
    out = []
    for d in split_top(text):
        if d == ":":
            out.append((None, ":"))
        elif d == "*":
            out.append((None, "*"))
        else:
            parts = split_top(d, ":")
            if len(parts) == 2:
                out.append((parts[0] or None, parts[1] or ":"))
            else:
                out.append((None, d))
    return out


def parse_declaration(text, line):
    """A list of VarDecl for a type declaration statement, or None if text is not one."""
    spec = parse_type_spec(text)
    if spec is None:
        return None
    base, kind_text, dtype, char_len, rest = spec
    rest = rest.strip()
    attrs, intent, dims = set(), None, None
    if "::" in rest:
        head, ents = rest.split("::", 1)
    else:
        if base in ("type", "procedure") and not rest.startswith(","):
            head, ents = "", rest
        elif rest.startswith(","):
            raise ParseError(f"declaration without '::' and with attributes: {text}")
        else:
            # 'real(ki) function f(x)' is a header, not a declaration
            if re.match(r"(recursive\s+|pure\s+|elemental\s+)*function\b", rest):
                return None
            head, ents = "", rest
    for a in split_top(head.strip().lstrip(",")):
        a = a.strip()
        if not a:
            continue
        if a.startswith("dimension"):
            k = a.index("(")
            dims = parse_dims(a[k + 1:matching_paren(a, k)])
        elif a.startswith("intent"):
            intent = re.search(r"\(\s*(\w+)\s*\)", a).group(1).replace(" ", "")
        else:
            attrs.add(a.replace(" ", ""))
    out = []
    for e in split_top(ents):
        if not e:
            continue
        init = ptr_init = None
        m = re.match(r"^(\w+)\s*(.*)$", e)
        name, tail = m.group(1), m.group(2).strip()
        edims, elen = dims, char_len
        if tail.startswith("("):
            k = matching_paren(tail, 0)
            edims = parse_dims(tail[1:k])
            tail = tail[k + 1:].strip()
        if tail.startswith("*"):
            mm = re.match(r"\*\s*(\(\s*\*\s*\)|\(\s*[^)]*\)|\d+)", tail)
            elen = mm.group(1).strip("() ")
            tail = tail[mm.end():].strip()
        if tail.startswith("=>"):
            ptr_init = tail[2:].strip()
        elif tail.startswith("="):
            init = tail[1:].strip()
        elif tail:
            raise ParseError(f"cannot read entity '{e}' in '{text}'")
        out.append(VarDecl(name, base, dtype, elen, edims, set(attrs), intent, init, ptr_init, line))
    return out


HEADER = re.compile(r"^(?P<pre>(?:(?:recursive|pure|elemental|impure|module)\s+|(?:integer|real|complex|logical|character|double\s+precision|type)\s*(?:\([^)]*\))?\s+)*)"
                    r"(?P<kind>function|subroutine)\s+(?P<name>\w+)\s*(?:\((?P<args>[^)]*)\))?\s*(?:result\s*\(\s*(?P<res>\w+)\s*\))?\s*$")


def parse_header(stmt):
    m = HEADER.match(stmt.text)
    if not m:
        return None
    pre = m.group("pre") or ""
    prefixes = set(re.findall(r"\b(recursive|pure|elemental|impure)\b", pre))
    rtype = re.sub(r"\b(recursive|pure|elemental|impure)\b", "", pre).strip() or None
    args = [a.strip() for a in (m.group("args") or "").split(",") if a.strip()]
    kind = m.group("kind")
    res = m.group("res") or (m.group("name") if kind == "function" else None)
    return Proc(m.group("name"), kind, args, res, prefixes, rtype, stmt.line, stmt.file)


END = re.compile(r"^end\s*(function|subroutine|module|type|interface|if|do|select|where|program)?\b\s*(\w*)\s*$")


def is_end(text, what=None):
    m = END.match(text)
    if not m:
        return False
    return what is None or m.group(1) in (what, None)


class Reader:
    """Walks the statements of one file and builds its modules."""

    def __init__(self, stmts):
        self.stmts = stmts
        self.i = 0

    def peek(self):
        return self.stmts[self.i] if self.i < len(self.stmts) else None

    def next(self):
        s = self.stmts[self.i]
        self.i += 1
        return s

    def error(self, s, msg):
        raise ParseError(f"{s.file}:{s.line}: {msg}: {s.text}")

    def units(self):
        mods = []
        external = None
        while self.peek() is not None:
            s = self.next()
            m = re.match(r"^module\s+(\w+)$", s.text)
            if m and m.group(1) != "procedure":
                mods.append(self.module(Module(m.group(1), s.line, s.file)))
            elif re.match(r"^program\b", s.text):
                # a main program is not translated
                while self.peek() is not None and not is_end(self.next().text, "program"):
                    pass
            else:
                p = parse_header(s)
                if p is None:
                    self.error(s, "only modules and external procedures are translated")
                # External procedures (the LoopTools-like interface) live in a module of their file.
                if external is None:
                    stem = re.sub(r"\W", "_", s.file.replace("\\", "/").rsplit("/", 1)[-1].rsplit(".", 1)[0])
                    external = Module("ext_" + stem, s.line, s.file)
                    external.external = True
                    mods.append(external)
                p.module = external
                self.proc_body(p)
                external.procs[p.name] = p
        return mods

    # ----------------------------------------------------------- specification part
    def spec_stmt(self, s, owner):
        """Handles one specification statement for a module or a procedure; False if it is not one."""
        t = s.text
        m = re.match(r"^use\s*(?:,\s*intrinsic\s*::)?\s*(\w+)\s*(.*)$", t)
        if m:
            rest = m.group(2).strip()
            only, renames = None, []
            if rest.startswith(","):
                rest = rest[1:].strip()
                mo = re.match(r"^only\s*:\s*(.*)$", rest)
                if mo:
                    only = []
                    for item in split_top(mo.group(1)):
                        if not item:
                            continue
                        if "=>" in item and not item.startswith("operator"):
                            a, b = [x.strip() for x in item.split("=>")]
                            only.append((a, b))
                        else:
                            only.append((item.replace(" ", ""), item.replace(" ", "")))
                else:
                    for item in split_top(rest):
                        a, b = [x.strip() for x in item.split("=>")]
                        renames.append((a, b))
            owner.uses.append(Use(m.group(1), only, renames, s.line))
            return True
        if re.match(r"^implicit\s+none$", t):
            return True
        if re.match(r"^implicit\b", t):
            self.error(s, "implicit typing is not translated")
        m = re.match(r"^(public|private)\s*(?:::)?\s*(.*)$", t)
        if m and not re.match(r"^(public|private)\s*=", t):
            words = [w.replace(" ", "") for w in split_top(m.group(2))] if m.group(2) else []
            if isinstance(owner, Module):
                if not words:
                    owner.default_private = m.group(1) == "private"
                else:
                    (owner.private if m.group(1) == "private" else owner.public).update(words)
            return True
        if re.match(r"^(save|sequence)\s*$", t):
            return True
        m = re.match(r"^save\s*(?:::)?\s*(.+)$", t)
        if m:
            for w in split_top(m.group(1)):
                d = owner.decls.get(w)
                if d:
                    d.attrs.add("save")
            return True
        m = re.match(r"^(intrinsic|external)\s*(?:::)?\s*(.+)$", t)
        if m:
            return True
        m = re.match(r"^parameter\s*\((.*)\)$", t)
        if m:
            for item in split_top(m.group(1)):
                n, v = [x.strip() for x in item.split("=", 1)]
                d = owner.decls.get(n)
                if d is None:
                    self.error(s, f"parameter {n} without a declaration")
                d.attrs.add("parameter")
                d.init = v
            return True
        m = re.match(r"^dimension\s*(?:::)?\s*(.+)$", t)
        if m:
            for e in split_top(m.group(1)):
                mm = re.match(r"^(\w+)\s*\((.*)\)$", e)
                owner.decls[mm.group(1)].dims = parse_dims(mm.group(2))
            return True
        m = re.match(r"^(allocatable|target|pointer|optional|intent\s*\(\s*\w+\s*\))\s*(?:::)?\s*(.+)$", t)
        if m and not re.match(r"^\w+\s*(\(.*\))?\s*=", t):
            attr = m.group(1).replace(" ", "")
            for e in split_top(m.group(2)):
                mm = re.match(r"^(\w+)\s*(?:\((.*)\))?$", e)
                d = owner.decls.get(mm.group(1))
                if d is None:
                    # the type comes in a later statement (OneLOop writes 'intent(out) :: x1' first)
                    d = VarDecl(mm.group(1), None, None, None, None, set(), None, None, None, s.line)
                    owner.decls[d.name] = d
                    owner.order.append(d.name)
                if attr.startswith("intent"):
                    d.intent = re.search(r"\((\w+)\)", attr).group(1)
                else:
                    d.attrs.add(attr)
                if mm.group(2):
                    d.dims = parse_dims(mm.group(2))
            return True
        m = re.match(r"^type\s*(?:,\s*(public|private)\s*)?(?:::)?\s*(\w+)$", t)
        if m and not t.startswith("type(") and not re.match(r"^type\s*\(", t):
            td = TypeDef(m.group(2), s.line)
            if m.group(1) == "private" and isinstance(owner, Module):
                owner.private.add(td.name)
            while True:
                c = self.next()
                if is_end(c.text, "type"):
                    break
                if c.text in ("sequence", "private"):
                    continue
                decls = parse_declaration(c.text, c.line)
                if decls is None:
                    self.error(c, "not a component declaration")
                td.components.extend(decls)
            owner.types[td.name] = td
            return True
        if re.match(r"^(abstract\s+)?interface\b", t):
            self.interface(s, owner)
            return True
        decls = parse_declaration(t, s.line)
        if decls is not None:
            for d in decls:
                prev = owner.decls.get(d.name)
                if prev is not None:
                    # a second statement about the same name
                    if prev.base is None:
                        prev.base, prev.dtype, prev.char_len = d.base, d.dtype, d.char_len
                        prev.init, prev.ptr_init = d.init, d.ptr_init
                    prev.attrs |= d.attrs
                    prev.dims = prev.dims or d.dims
                    prev.intent = prev.intent or d.intent
                    continue
                owner.decls[d.name] = d
                owner.order.append(d.name)
            return True
        return False

    def interface(self, s, owner):
        t = s.text
        m = re.match(r"^interface\s+operator\s*\(\s*(.+?)\s*\)$", t)
        if m:
            itf = Interface("operator", m.group(1), s.line)
        else:
            m = re.match(r"^interface\s+assignment\s*\(\s*=\s*\)$", t)
            if m:
                itf = Interface("assignment", "=", s.line)
            else:
                m = re.match(r"^interface\s+(\w+)$", t)
                itf = Interface("generic", m.group(1), s.line) if m else Interface("explicit", None, s.line)
        while True:
            c = self.next()
            if is_end(c.text, "interface"):
                break
            m = re.match(r"^(?:module\s+)?procedure\s*(?:::)?\s*(.+)$", c.text)
            if m:
                itf.procs.extend(x.strip() for x in m.group(1).split(","))
                continue
            p = parse_header(c)
            if p is None:
                self.error(c, "unexpected statement in an interface block")
            self.proc_body(p, header_only=True)
            itf.bodies.append(p)
        owner.interfaces.append(itf)

    # ----------------------------------------------------------------- units
    def module(self, mod):
        while True:
            s = self.next()
            if is_end(s.text, "module") and (s.text.startswith("end module") or s.text == "end"):
                return mod
            if s.text == "contains":
                break
            if not self.spec_stmt(s, mod):
                self.error(s, "unexpected statement in a module's specification part")
        while True:
            s = self.next()
            if is_end(s.text, "module") and (s.text.startswith("end module") or s.text == "end"):
                return mod
            p = parse_header(s)
            if p is None:
                self.error(s, "a procedure was expected")
            p.module = mod
            self.proc_body(p)
            mod.procs[p.name] = p

    def proc_body(self, p, header_only=False):
        try:
            return self._proc_body(p, header_only)
        finally:
            # 'integer function f(x)' or '... function f(x) result(r)' with the type in front: the result
            # variable is declared by the header itself
            if p.kind == "function" and p.result not in p.decls and p.rtype:
                spec = parse_type_spec(p.rtype + " :: " + p.result)
                if spec is not None:
                    base, kind_text, dtype, char_len, rest = spec
                    d = VarDecl(p.result, base, dtype, char_len, None, set(), None, None, None, p.line)
                    p.decls[p.result] = d
                    p.order.append(p.result)

    def _proc_body(self, p, header_only=False):
        # specification part
        while True:
            s = self.peek()
            if s is None:
                raise ParseError(f"unterminated procedure {p.name}")
            if is_end(s.text, p.kind):
                self.next()
                return p
            if s.text == "contains":
                break
            if self._is_spec(s):
                # advanced first: an interface or a type definition reads on from the next statement
                self.next()
                if self.spec_stmt(s, p):
                    continue
                self.i -= 1
            if header_only:
                self.error(s, "an executable statement in an interface body")
            break
        if s.text != "contains":
            p.body = self.block(p, ("end",))
        s = self.peek()
        if s is not None and s.text == "contains":
            self.next()
            while True:
                s = self.next()
                if is_end(s.text, p.kind):
                    return p
                q = parse_header(s)
                if q is None:
                    self.error(s, "an internal procedure was expected")
                q.host = p
                q.module = p.module
                self.proc_body(q)
                p.internal.append(q)
        s = self.next()
        if not is_end(s.text, p.kind):
            self.error(s, f"end of {p.kind} {p.name} expected")
        return p

    def _is_spec(self, s):
        t = s.text
        if re.match(r"^(use|implicit|public|private|save|intrinsic|external|parameter|dimension|allocatable|target|pointer|optional|intent|sequence)\b", t):
            # 'parameter(x) = ...' or 'save = 1' would be assignments, which Golem has none of
            return not re.match(r"^\w+\s*=[^=>]", t)
        if re.match(r"^(abstract\s+)?interface\b", t):
            return True
        if re.match(r"^type\s*(,|::|\w)", t) and not re.match(r"^type\s*\(", t):
            return True
        return parse_type_spec(t) is not None and not re.match(r"^\w+\s*(\(.*\))?\s*=[^=]", t)

    # ------------------------------------------------------------ execution part
    def block(self, p, stops):
        body = []
        while True:
            s = self.peek()
            if s is None:
                raise ParseError(f"unterminated block in {p.name}")
            t = s.text
            if (t == "contains" or is_end(t) and END.match(t).group(1) in (None, "function", "subroutine")) and "end" in stops:
                return body
            if any(self._stops(t, st) for st in stops):
                return body
            self.next()
            body.append(self.statement(s, p))

    @staticmethod
    def _stops(t, st):
        if st == "end":
            return False
        if st == "else":
            return re.match(r"^else(\s+\w+)?$", t) is not None or re.match(r"^else\s*if\s*\(", t) is not None
        if st == "endif":
            return re.match(r"^end\s*if\b", t) is not None
        if st == "enddo":
            return re.match(r"^end\s*do\b", t) is not None
        if st == "case":
            return re.match(r"^case\b", t) is not None
        if st == "endselect":
            return re.match(r"^end\s*select\b", t) is not None
        if st == "elsewhere":
            return re.match(r"^else\s*where\b", t) is not None
        if st == "endwhere":
            return re.match(r"^end\s*where\b", t) is not None
        return False

    def statement(self, s, p):
        t = s.text
        line = s.line
        # construct names: 'name: do ...', 'name: if (...) then', 'name: select case'
        label = None
        m = re.match(r"^(\w+)\s*:\s*(do|if|select)\b(.*)$", t)
        if m:
            label = m.group(1)
            t = m.group(2) + m.group(3)
        if re.match(r"^if\s*\(", t):
            k = matching_paren(t, t.index("("))
            cond = parse_expr(t[t.index("(") + 1:k])
            rest = t[k + 1:].strip()
            if rest == "then":
                return self.if_block(cond, p, line, label)
            inner = self.statement(type(s)(rest, s.line, s.file), p)
            return S("if", line, branches=[(cond, [inner])], other=None, label=label)
        if re.match(r"^do\b", t):
            return self.do_block(t, p, line, label)
        if re.match(r"^select\s*case\s*\(", t):
            k = matching_paren(t, t.index("("))
            sel = parse_expr(t[t.index("(") + 1:k])
            return self.select_block(sel, p, line, label)
        if re.match(r"^where\s*\(", t):
            k = matching_paren(t, t.index("("))
            mask = parse_expr(t[t.index("(") + 1:k])
            rest = t[k + 1:].strip()
            if rest:
                inner = self.statement(type(s)(rest, s.line, s.file), p)
                return S("where", line, mask=mask, body=[inner], other=[])
            return self.where_block(mask, p, line)
        m = re.match(r"^call\s+(\w+)\s*(\(.*\))?$", t)
        if m:
            args = []
            if m.group(2):
                pp = Parser(m.group(2)[1:])
                args = pp.arglist(")")
            return S("call", line, name=m.group(1), args=args)
        m = re.match(r"^(allocate|deallocate|nullify)\s*\((.*)\)$", t)
        if m:
            items, stat = [], None
            for a in split_top(m.group(2)):
                ms = re.match(r"^(stat|errmsg|source)\s*=\s*(.*)$", a)
                if ms:
                    if ms.group(1) == "stat":
                        stat = parse_expr(ms.group(2))
                    continue
                items.append(parse_expr(a))
            return S(m.group(1), line, items=items, stat=stat)
        if t == "return":
            return S("return", line)
        if t == "continue":
            return S("continue", line)
        m = re.match(r"^(exit|cycle)\s*(\w*)$", t)
        if m:
            return S(m.group(1), line, label=m.group(2) or None)
        m = re.match(r"^stop\b\s*(.*)$", t)
        if m and not re.match(r"^stop\s*=", t):
            code = m.group(1).strip()
            return S("stop", line, code=parse_expr(code) if code else None)
        m = re.match(r"^(write|print|read)\b\s*(.*)$", t)
        if m and not re.match(r"^(write|print|read)\s*=", t):
            return self.io(m.group(1), m.group(2), line)
        # assignment or pointer assignment
        k = self._assign_pos(t)
        if k is not None:
            pos, op = k
            lhs = parse_expr(t[:pos])
            rhs = parse_expr(t[pos + len(op):])
            return S("ptrassign" if op == "=>" else "assign", line, lhs=lhs, rhs=rhs)
        self.error(s, "statement not understood")

    @staticmethod
    def _assign_pos(t):
        depth, q = 0, None
        for i, ch in enumerate(t):
            if q:
                if ch == q:
                    q = None
                continue
            if ch in "'\"":
                q = ch
            elif ch in "([":
                depth += 1
            elif ch in ")]":
                depth -= 1
            elif depth == 0 and ch == "=":
                prev = t[i - 1] if i else ""
                nxt = t[i + 1] if i + 1 < len(t) else ""
                if nxt == "=" or prev in "=<>/":
                    continue
                if nxt == ">":
                    return i, "=>"
                return i, "="
        return None

    def where_block(self, mask, p, line):
        """where / elsewhere (mask) / elsewhere / end where, as a chain of masked blocks."""
        body = self.block(p, ("elsewhere", "endwhere"))
        s = self.next()
        t = s.text
        if re.match(r"^end\s*where\b", t):
            return S("where", line, mask=mask, body=body, other=[])
        m = re.match(r"^else\s*where\s*\((.*)\)$", t)
        if m:
            inner = self.where_block(parse_expr(m.group(1)), p, s.line)
            return S("where", line, mask=mask, body=body, other=[inner])
        other = self.block(p, ("endwhere",))
        self.next()
        return S("where", line, mask=mask, body=body, other=other)

    def if_block(self, cond, p, line, label):
        branches = []
        body = self.block(p, ("else", "endif"))
        branches.append((cond, body))
        other = None
        while True:
            s = self.next()
            t = s.text
            if re.match(r"^end\s*if\b", t):
                return S("if", line, branches=branches, other=other, label=label)
            m = re.match(r"^else\s*if\s*\(", t)
            if m:
                k = matching_paren(t, t.index("("))
                c = parse_expr(t[t.index("(") + 1:k])
                branches.append((c, self.block(p, ("else", "endif"))))
                continue
            if t == "else" or re.match(r"^else\s+\w+$", t):
                other = self.block(p, ("endif",))
                continue
            self.error(s, "else or end if expected")

    def do_block(self, t, p, line, label):
        m = re.match(r"^do\s+while\s*\((.*)\)$", t)
        if m:
            body = self.block(p, ("enddo",))
            self.next()
            return S("dowhile", line, cond=parse_expr(m.group(1)), body=body, label=label)
        m = re.match(r"^do\s*(\d+)?\s*,?\s*(\w+)\s*=\s*(.*)$", t)
        if m:
            parts = split_top(m.group(3))
            lo, hi = parse_expr(parts[0]), parse_expr(parts[1])
            step = parse_expr(parts[2]) if len(parts) > 2 else None
            body = self.block(p, ("enddo",))
            self.next()
            return S("do", line, var=m.group(2), lo=lo, hi=hi, step=step, body=body, label=label)
        if t == "do":
            body = self.block(p, ("enddo",))
            self.next()
            return S("doforever", line, body=body, label=label)
        raise ParseError(f"{p.file}:{line}: do statement not understood: {t}")

    def select_block(self, sel, p, line, label):
        cases = []
        s = self.peek()
        while s is not None and not re.match(r"^case\b", s.text) and not re.match(r"^end\s*select", s.text):
            self.next()
            s = self.peek()
        while True:
            s = self.next()
            t = s.text
            if re.match(r"^end\s*select\b", t):
                return S("select", line, sel=sel, cases=cases, label=label)
            m = re.match(r"^case\s+default\b", t) or re.match(r"^case\s*\(\s*default\s*\)", t)
            if m:
                cases.append(("default", self.block(p, ("case", "endselect"))))
                continue
            m = re.match(r"^case\s*\((.*)\)\s*\w*$", t)
            if not m:
                self.error(s, "case expected")
            sels = []
            for item in split_top(m.group(1)):
                parts = split_top(item, ":")
                if len(parts) == 2:
                    sels.append(("range", parse_expr(parts[0]) if parts[0] else None,
                                 parse_expr(parts[1]) if parts[1] else None))
                else:
                    sels.append(("value", parse_expr(item)))
            cases.append((sels, self.block(p, ("case", "endselect"))))

    def io(self, kind, rest, line):
        rest = rest.strip()
        if kind == "print":
            parts = split_top(rest)
            fmt = parts[0]
            items = [self.io_item(x) for x in parts[1:] if x]
            return S("write", line, unit=None, fmt=None if fmt == "*" else parse_expr(fmt), items=items, opts={})
        if not rest.startswith("("):
            raise ParseError(f"{line}: {kind} without a control list")
        k = matching_paren(rest, 0)
        ctl = split_top(rest[1:k])
        unit, fmt, opts = None, None, {}
        for n, c in enumerate(ctl):
            mm = re.match(r"^(unit|fmt|advance|iostat|err|end|rec)\s*=\s*(.*)$", c)
            if mm:
                key, val = mm.group(1), mm.group(2)
            else:
                key, val = ("unit" if n == 0 else "fmt"), c
            if key == "unit":
                unit = None if val == "*" else parse_expr(val)
            elif key == "fmt":
                fmt = None if val == "*" else parse_expr(val)
            else:
                opts[key] = parse_expr(val)
        items = [self.io_item(x) for x in split_top(rest[k + 1:].strip().lstrip(",")) if x]
        return S(kind, line, unit=unit, fmt=fmt, items=items, opts=opts)

    @staticmethod
    def io_item(text):
        if text.startswith("("):
            pp = Parser(text)
            try:
                item = pp.ctor_item()
                if pp.done():
                    return item
            except ParseError:
                pass
        return parse_expr(text)

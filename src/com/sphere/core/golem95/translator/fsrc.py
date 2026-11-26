"""Fortran 90 free-form source: logical statements and expressions.

A statement is the text between line ends and semicolons, continuation lines joined,
comments removed, lowercased outside character literals, with the line it starts on.
Expressions are parsed into small tuples-like node objects by a precedence parser.
"""
import re


class Stmt:
    __slots__ = ("text", "line", "file")

    def __init__(self, text, line, file):
        self.text, self.line, self.file = text, line, file

    def __repr__(self):
        return f"{self.file}:{self.line}: {self.text}"


def _strip_comment(line):
    """The line without its comment, and whether it ends inside a character literal."""
    out, q = [], None
    i = 0
    while i < len(line):
        ch = line[i]
        if q:
            out.append(ch)
            if ch == q:
                if i + 1 < len(line) and line[i + 1] == q:
                    out.append(q)
                    i += 1
                else:
                    q = None
        elif ch in "'\"":
            q = ch
            out.append(ch)
        elif ch == "!":
            break
        else:
            out.append(ch)
        i += 1
    return "".join(out), q


def _lower_outside_strings(text):
    out, q = [], None
    i = 0
    while i < len(text):
        ch = text[i]
        if q:
            out.append(ch)
            if ch == q:
                if i + 1 < len(text) and text[i + 1] == q:
                    out.append(q)
                    i += 1
                else:
                    q = None
        elif ch in "'\"":
            q = ch
            out.append(ch)
        else:
            out.append(ch.lower())
        i += 1
    return "".join(out)


def _split_semicolons(text):
    parts, cur, q = [], [], None
    for ch in text:
        if q:
            cur.append(ch)
            if ch == q:
                q = None
        elif ch in "'\"":
            q = ch
            cur.append(ch)
        elif ch == ";":
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    return parts


def statements(path, display=None):
    """The logical statements of a free-form source file."""
    name = display or path
    raw = open(path, encoding="latin-1").read().splitlines()
    out = []
    buf, start, quote = "", None, None
    for no, line in enumerate(raw, 1):
        if quote:
            # a character literal continued over the line end
            text = line
            stripped = text.lstrip()
            if stripped.startswith("&"):
                text = stripped[1:]
            code, quote2 = _strip_comment(quote + text)
            code = code[1:]
            quote = quote2
        else:
            code, quote = _strip_comment(line)
            if buf:
                s = code.lstrip()
                if not s:
                    # a comment line between a statement and its continuation
                    continue
                if s.startswith("&"):
                    code = s[1:]
        stripped = code.rstrip()
        if stripped.endswith("&"):
            if start is None:
                start = no
            buf += stripped[:-1]
            continue
        if start is None:
            start = no
        buf += code
        text = buf.strip()
        buf = ""
        if text:
            for part in _split_semicolons(text):
                part = part.strip()
                if part:
                    part = re.sub(r"^\d+\s+", "", part)  # a statement label
                    out.append(Stmt(_lower_outside_strings(part), start, name))
        start = None
    return out


# ---------------------------------------------------------------------------
# Expressions
# ---------------------------------------------------------------------------

class Node:
    __slots__ = ()


class Num(Node):
    """A numeric literal: kind 'int', 'real' (double) or 'single' (a default-real literal)."""
    __slots__ = ("text", "kind", "value")

    def __init__(self, text, kind, value):
        self.text, self.kind, self.value = text, kind, value

    def __repr__(self):
        return f"Num({self.text})"


class Str(Node):
    __slots__ = ("value",)

    def __init__(self, value):
        self.value = value

    def __repr__(self):
        return f"Str({self.value!r})"


class Logic(Node):
    __slots__ = ("value",)

    def __init__(self, value):
        self.value = value


class Cplx(Node):
    __slots__ = ("re", "im")

    def __init__(self, re_, im):
        self.re, self.im = re_, im


class Name(Node):
    __slots__ = ("id",)

    def __init__(self, id_):
        self.id = id_

    def __repr__(self):
        return f"Name({self.id})"


class Arg(Node):
    __slots__ = ("key", "value")

    def __init__(self, key, value):
        self.key, self.value = key, value


class Range(Node):
    __slots__ = ("lo", "hi", "step")

    def __init__(self, lo, hi, step):
        self.lo, self.hi, self.step = lo, hi, step


class Call(Node):
    """name(args): a function call, an array element or section, or a substring."""
    __slots__ = ("base", "args")

    def __init__(self, base, args):
        self.base, self.args = base, args

    def __repr__(self):
        return f"Call({self.base!r},{len(self.args)})"


class Comp(Node):
    __slots__ = ("base", "field")

    def __init__(self, base, field):
        self.base, self.field = base, field


class Un(Node):
    __slots__ = ("op", "x")

    def __init__(self, op, x):
        self.op, self.x = op, x


class Bin(Node):
    __slots__ = ("op", "a", "b")

    def __init__(self, op, a, b):
        self.op, self.a, self.b = op, a, b


class Paren(Node):
    __slots__ = ("x",)

    def __init__(self, x):
        self.x = x


class ArrCtor(Node):
    __slots__ = ("items",)

    def __init__(self, items):
        self.items = items


class ImpliedDo(Node):
    __slots__ = ("items", "var", "lo", "hi", "step")

    def __init__(self, items, var, lo, hi, step):
        self.items, self.var, self.lo, self.hi, self.step = items, var, lo, hi, step


DOT_OPS = {".and.": "and", ".or.": "or", ".not.": "not", ".eqv.": "eqv", ".neqv.": "neqv",
           ".eq.": "==", ".ne.": "/=", ".lt.": "<", ".le.": "<=", ".gt.": ">", ".ge.": ">=",
           ".true.": "TRUE", ".false.": "FALSE"}

TOKEN = re.compile(r"""
    (?P<ws>\s+)
  | (?P<str>'(?:[^']|'')*'|"(?:[^"]|"")*")
  | (?P<dot>\.(?:and|or|not|eqv|neqv|eq|ne|lt|le|gt|ge|true|false)\.(?:_\w+)?)
  | (?P<num>(?:\d+\.\d*|\.\d+|\d+)(?:[eEdDqQ][-+]?\d+)?(?:_\w+)?)
  | (?P<id>[a-z_][a-z0-9_]*)
  | (?P<op>\*\*|//|==|/=|<=|>=|=>|\(/|/\)|[-+*/<>=(),:%\[\]])
""", re.X)


class ParseError(Exception):
    pass


def tokenize(text):
    toks = []
    i = 0
    while i < len(text):
        m = TOKEN.match(text, i)
        if not m:
            raise ParseError(f"cannot read '{text[i:i + 20]}' in '{text}'")
        kind = m.lastgroup
        val = m.group(kind)
        if kind == "num" and "." in val and not re.search(r"[eEdDqQ]", val):
            # "1.eq.2": the dot belongs to the operator, not to the number
            mm = re.match(r"(\d+)\.(and|or|not|eqv|neqv|eq|ne|lt|le|gt|ge|true|false)\.", text[i:])
            if mm:
                toks.append(("num", mm.group(1)))
                i += len(mm.group(1))
                continue
        if kind != "ws":
            if kind == "dot":
                base = re.match(r"\.\w+\.", val).group(0)
                toks.append(("op", DOT_OPS[base]) if base not in (".true.", ".false.") else ("logic", base == ".true."))
            else:
                toks.append((kind, val))
        i = m.end()
    return toks


def number(text):
    """A Num from a literal: its Java-relevant kind and value."""
    t = text
    kind_suffix = None
    if "_" in t:
        t, kind_suffix = t.split("_", 1)
    if re.fullmatch(r"\d+", t):
        return Num(text, "int", int(t))
    double = kind_suffix is not None or re.search(r"[dD]", t) is not None
    v = float(re.sub(r"[dDqQ]", "e", t))
    return Num(text, "real" if double else "single", v)


class Parser:
    """A precedence parser over the tokens of one expression (or of a list of them)."""

    def __init__(self, text):
        self.text = text
        self.toks = tokenize(text)
        self.i = 0

    def peek(self, k=0):
        j = self.i + k
        return self.toks[j] if j < len(self.toks) else (None, None)

    def at(self, *vals):
        kind, val = self.peek()
        return kind == "op" and val in vals

    def take(self, val=None):
        kind, v = self.peek()
        if val is not None and not (kind == "op" and v == val):
            raise ParseError(f"expected '{val}' at token {self.i} in '{self.text}'")
        self.i += 1
        return v

    def done(self):
        return self.i >= len(self.toks)

    # precedence climbing, lowest first
    def expr(self):
        return self.eqv()

    def eqv(self):
        x = self.or_()
        while self.at("eqv", "neqv"):
            op = self.take()
            x = Bin(op, x, self.or_())
        return x

    def or_(self):
        x = self.and_()
        while self.at("or"):
            self.take()
            x = Bin("or", x, self.and_())
        return x

    def and_(self):
        x = self.not_()
        while self.at("and"):
            self.take()
            x = Bin("and", x, self.not_())
        return x

    def not_(self):
        if self.at("not"):
            self.take()
            return Un("not", self.not_())
        return self.rel()

    def rel(self):
        x = self.concat()
        if self.at("==", "/=", "<", "<=", ">", ">="):
            op = self.take()
            x = Bin(op, x, self.concat())
        return x

    def concat(self):
        x = self.add()
        while self.at("//"):
            self.take()
            x = Bin("//", x, self.add())
        return x

    def add(self):
        if self.at("+", "-"):
            op = self.take()
            x = self.mul()
            x = Un(op, x)
        else:
            x = self.mul()
        while self.at("+", "-"):
            op = self.take()
            x = Bin(op, x, self.mul())
        return x

    def mul(self):
        x = self.power()
        while self.at("*", "/"):
            op = self.take()
            x = Bin(op, x, self.power())
        return x

    def power(self):
        x = self.postfix()
        if self.at("**"):
            self.take()
            # right associative; a unary sign is allowed after ** (an extension most compilers take)
            if self.at("+", "-"):
                op = self.take()
                return Bin("**", x, Un(op, self.power()))
            return Bin("**", x, self.power())
        return x

    def postfix(self):
        x = self.primary()
        while True:
            if self.at("("):
                self.take("(")
                x = Call(x, self.arglist(")"))
            elif self.at("%"):
                self.take("%")
                kind, v = self.peek()
                if kind != "id":
                    raise ParseError(f"component name expected in '{self.text}'")
                self.take()
                x = Comp(x, v)
            else:
                return x

    def arglist(self, close):
        args = []
        if self.at(close):
            self.take(close)
            return args
        while True:
            args.append(self.arg())
            if self.at(","):
                self.take(",")
                continue
            self.take(close)
            return args

    def arg(self):
        # keyword argument
        kind, v = self.peek()
        if kind == "id" and self.peek(1) == ("op", "="):
            self.take()
            self.take("=")
            return Arg(v, self.expr())
        # a section a:b:c, or :, or a:
        lo = hi = step = None
        if self.at(":"):
            self.take(":")
            if not (self.at(",") or self.at(")") or self.at(":")):
                hi = self.expr()
            if self.at(":"):
                self.take(":")
                step = self.expr()
            return Arg(None, Range(lo, hi, step))
        e = self.expr()
        if self.at(":"):
            self.take(":")
            if not (self.at(",") or self.at(")") or self.at(":")):
                hi = self.expr()
            if self.at(":"):
                self.take(":")
                step = self.expr()
            return Arg(None, Range(e, hi, step))
        return Arg(None, e)

    def primary(self):
        kind, v = self.peek()
        if kind == "num":
            self.take()
            return number(v)
        if kind == "str":
            self.take()
            q = v[0]
            return Str(v[1:-1].replace(q + q, q))
        if kind == "logic":
            self.take()
            return Logic(v)
        if kind == "id":
            self.take()
            return Name(v)
        if kind == "op" and v in ("(/", "["):
            self.take()
            close = "/)" if v == "(/" else "]"
            items = self.ctor_items(close)
            return ArrCtor(items)
        if kind == "op" and v == "(":
            self.take("(")
            # an implied-do inside a constructor is handled by ctor_items; here: (x) or (re, im)
            e = self.expr()
            if self.at(","):
                self.take(",")
                im = self.expr()
                self.take(")")
                return Cplx(e, im)
            self.take(")")
            return Paren(e)
        raise ParseError(f"unexpected '{v}' in '{self.text}'")

    def ctor_items(self, close):
        items = []
        if self.at(close):
            self.take(close)
            return items
        while True:
            items.append(self.ctor_item())
            if self.at(","):
                self.take(",")
                continue
            self.take(close)
            return items

    def ctor_item(self):
        # (items, i = lo, hi[, step]) is an implied-do; anything else is an expression
        if self.at("("):
            save = self.i
            try:
                self.take("(")
                inner = []
                while True:
                    kind, v = self.peek()
                    if kind == "id" and self.peek(1) == ("op", "="):
                        var = v
                        self.take()
                        self.take("=")
                        lo = self.expr()
                        self.take(",")
                        hi = self.expr()
                        step = None
                        if self.at(","):
                            self.take(",")
                            step = self.expr()
                        self.take(")")
                        return ImpliedDo(inner, var, lo, hi, step)
                    inner.append(self.ctor_item())
                    if self.at(","):
                        self.take(",")
                        continue
                    raise ParseError("not an implied do")
            except ParseError:
                self.i = save
        return self.expr()


def parse_expr(text):
    p = Parser(text)
    e = p.expr()
    if not p.done():
        raise ParseError(f"trailing tokens after expression in '{text}' at {p.toks[p.i:]}")
    return e


def split_top(text, sep=","):
    """Splits at separators outside parentheses and character literals."""
    parts, cur, depth, q = [], [], 0, None
    i = 0
    while i < len(text):
        ch = text[i]
        if q:
            cur.append(ch)
            if ch == q:
                q = None
        elif ch in "'\"":
            q = ch
            cur.append(ch)
        elif ch in "([":
            depth += 1
            cur.append(ch)
        elif ch in ")]":
            depth -= 1
            cur.append(ch)
        elif depth == 0 and text.startswith(sep, i):
            parts.append("".join(cur))
            cur = []
            i += len(sep)
            continue
        else:
            cur.append(ch)
        i += 1
    parts.append("".join(cur))
    return [p.strip() for p in parts]


def matching_paren(text, start):
    """The index of the parenthesis closing the one at start."""
    depth, q = 0, None
    for i in range(start, len(text)):
        ch = text[i]
        if q:
            if ch == q:
                q = None
        elif ch in "'\"":
            q = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
    raise ParseError(f"unbalanced parentheses in '{text}'")

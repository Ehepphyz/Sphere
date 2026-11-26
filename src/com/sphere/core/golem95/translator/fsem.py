"""Semantics: types, scopes, what a name means where it is used, generic resolution, by-reference dummies."""


class GenError(Exception):
    pass
import re

from fsrc import parse_expr, Name, Call, Comp, Num, Str, Logic, Cplx, Un, Bin, Paren, ArrCtor, ImpliedDo, Arg, Range


class FT:
    """A Fortran type as the translation needs it."""
    __slots__ = ("base", "rank", "dtype")

    def __init__(self, base, rank=0, dtype=None):
        self.base, self.rank, self.dtype = base, rank, dtype

    def elem(self):
        return FT(self.base, 0, self.dtype)

    def with_rank(self, r):
        return FT(self.base, r, self.dtype)

    def __eq__(self, o):
        return isinstance(o, FT) and (self.base, self.rank, self.dtype) == (o.base, o.rank, o.dtype)

    def __hash__(self):
        return hash((self.base, self.rank, self.dtype))

    def __repr__(self):
        return f"{self.base}{'(' + self.dtype + ')' if self.dtype else ''}{'[' + str(self.rank) + ']' if self.rank else ''}"


INT, REAL, CPLX, LOG, CHAR, VOID = FT("integer"), FT("real"), FT("complex"), FT("logical"), FT("character"), FT("void")
NUMERIC_ORDER = {"integer": 0, "real": 1, "complex": 2}


def decl_type(d):
    base = d.base
    if base == "double precision":
        base = "real"
    if base == "type":
        return FT("type", d.rank, d.dtype)
    if base == "procedure":
        return FT("procedure", 0, d.dtype)
    return FT(base, d.rank)


class Entity:
    """What a name means: a variable, a procedure, a generic, a derived type, an intrinsic."""

    def __init__(self, kind, name, owner=None, decl=None, proc=None, specifics=None, typedef=None):
        self.kind = kind            # var proc generic type intrinsic dummyproc
        self.name = name            # name in the defining unit
        self.owner = owner          # defining Module (or Proc for locals)
        self.decl = decl
        self.proc = proc
        self.specifics = specifics or []
        self.typedef = typedef

    def __repr__(self):
        return f"Entity({self.kind} {self.name})"


INTRINSICS = set("""
abs sqrt log log10 exp sin cos tan asin acos atan atan2 sinh cosh tanh real dble aimag conjg cmplx dcmplx int nint
floor ceiling sign mod modulo max min epsilon huge tiny digits precision range radix maxexponent minexponent kind
selected_real_kind selected_int_kind size lbound ubound shape allocated associated present count any all sum product
maxval minval maxloc minloc dot_product matmul transpose pack unpack merge spread reshape iand ior ieor not ishft
ibset ibclr btest ibits trim len len_trim adjustl adjustr index achar char iachar ichar repeat verify scan scale
exponent fraction nearest spacing null dabs dsqrt dlog dexp dimag dconjg dreal float sngl dfloat aint anint
lge lgt lle llt bit_size huge cshift eoshift
""".split())


class Program:
    """Every module, every derived type and external procedure, and the answers to name questions."""

    def __init__(self, modules):
        self.modules = {m.name: m for m in modules}
        self.types = {}             # type name -> (module, TypeDef)
        self.externals = {}         # external procedure name -> Proc
        for m in modules:
            for t in m.types.values():
                self.types[t.name] = (m, t)
            if getattr(m, "external", False):
                for p in m.procs.values():
                    self.externals[p.name] = p
            for p in m.procs.values():
                p.module = m
                for q in p.internal:
                    q.module = m
        self._exports = {}
        self.operators = {}         # op -> list of (module, Proc)
        self.assignments = []       # list of (module, Proc)
        for m in modules:
            for itf in m.interfaces:
                if itf.kind == "operator":
                    op = {".eq.": "==", ".ne.": "/="}.get(itf.name, itf.name)
                    for pn in itf.procs:
                        self.operators.setdefault(op, []).append(m.procs[pn])
                elif itf.kind == "assignment":
                    for pn in itf.procs:
                        self.assignments.append(m.procs[pn])

    # --------------------------------------------------------------- module scopes
    def exports(self, mod):
        """Public names of a module: name -> Entity, its own and those it imports publicly."""
        if mod.name in self._exports:
            return self._exports[mod.name]
        self._exports[mod.name] = {}           # guards against cycles
        out = {}
        imported = self.imports(mod.uses, mod)
        own = self.own_entities(mod)

        def public(name):
            if name in mod.private:
                return False
            if name in mod.public:
                return True
            d = mod.decls.get(name)
            if d is not None:
                if "public" in d.attrs:
                    return True
                if "private" in d.attrs:
                    return False
            return not mod.default_private
        for name, ent in imported.items():
            if public(name):
                out[name] = ent
        for name, ent in own.items():
            if public(name):
                if name in out and ent.kind == "generic" and out[name].kind == "generic":
                    out[name] = Entity("generic", name, specifics=out[name].specifics + ent.specifics)
                else:
                    out[name] = ent
        self._exports[mod.name] = out
        return out

    def own_entities(self, mod):
        own = {}
        for name, d in mod.decls.items():
            own[name] = Entity("var", name, owner=mod, decl=d)
        for name, p in mod.procs.items():
            own[name] = Entity("proc", name, owner=mod, proc=p)
        for name, t in mod.types.items():
            own[name] = Entity("type", name, owner=mod, typedef=t)
        for itf in mod.interfaces:
            if itf.kind == "generic":
                specs = []
                for pn in itf.procs:
                    e = own.get(pn) or self.lookup_in_imports(mod, pn)
                    if e is None or e.kind != "proc":
                        raise KeyError(f"generic {itf.name} in {mod.name}: specific {pn} unknown")
                    specs.append(e.proc)
                prev = own.get(itf.name)
                if prev is not None and prev.kind == "generic":
                    prev.specifics.extend(specs)
                elif prev is not None and prev.kind == "proc":
                    # a generic named like one of its specifics
                    own[itf.name] = Entity("generic", itf.name, owner=mod, specifics=specs)
                else:
                    own[itf.name] = Entity("generic", itf.name, owner=mod, specifics=specs)
            elif itf.kind == "explicit":
                for b in itf.bodies:
                    target = self.externals.get(b.name) or self.find_global_proc(b.name)
                    if target is not None:
                        own[b.name] = Entity("proc", b.name, owner=target.module, proc=target)
        return own

    def find_global_proc(self, name):
        for m in self.modules.values():
            p = m.procs.get(name)
            if p is not None and name in self.exports(m):
                return p
        return None

    def lookup_in_imports(self, mod, name):
        return self.imports(mod.uses, mod).get(name)

    def imports(self, uses, who):
        out = {}
        for u in uses:
            m = self.modules.get(u.module)
            if m is None:
                raise KeyError(f"{who.name}: module {u.module} not found")
            ex = self.exports(m)
            if u.only is not None:
                for local, remote in u.only:
                    if remote.startswith("operator(") or remote.startswith("assignment("):
                        continue
                    e = ex.get(remote)
                    if e is None:
                        raise KeyError(f"{who.name}: {remote} is not public in {u.module}")
                    self._merge(out, local, e)
            else:
                renamed = {remote: local for local, remote in u.renames}
                for name, e in ex.items():
                    self._merge(out, renamed.get(name, name), e)
        return out

    @staticmethod
    def _merge(out, name, e):
        prev = out.get(name)
        if prev is not None and prev.kind == "generic" and e.kind == "generic" and prev is not e:
            specs = list(prev.specifics)
            for s in e.specifics:
                if s not in specs:
                    specs.append(s)
            out[name] = Entity("generic", name, specifics=specs)
        else:
            out[name] = e

    def module_scope(self, mod):
        if not hasattr(mod, "_scope"):
            scope = dict(self.imports(mod.uses, mod))
            for name, e in self.own_entities(mod).items():
                if name in scope and e.kind == "generic" and scope[name].kind == "generic":
                    scope[name] = Entity("generic", name, owner=mod,
                                         specifics=scope[name].specifics + [s for s in e.specifics if s not in scope[name].specifics])
                else:
                    scope[name] = e
            mod._scope = scope
        return mod._scope


class Scope:
    """Where a name is looked up inside a procedure: its own names, its uses, its host, its module."""

    def __init__(self, prog, proc):
        self.prog = prog
        self.proc = proc
        self.local = {}
        for name, d in proc.decls.items():
            if d.base is None:
                continue
            self.local[name] = Entity("var", name, owner=proc, decl=d)
        for itf in proc.interfaces:
            for b in itf.bodies:
                if b.name in proc.args:
                    self.local[b.name] = Entity("dummyproc", b.name, owner=proc, proc=b)
                else:
                    target = prog.externals.get(b.name) or prog.find_global_proc(b.name)
                    if target is not None:
                        self.local[b.name] = Entity("proc", b.name, owner=target.module, proc=target)
        for q in proc.internal:
            self.local[q.name] = Entity("proc", q.name, owner=proc.module, proc=q)
        self.used = prog.imports(proc.uses, proc) if proc.uses else {}
        self.host = Scope(prog, proc.host) if proc.host is not None else None
        self.module = prog.module_scope(proc.module)

    def lookup(self, name):
        e = self.local.get(name)
        if e is not None:
            return e
        e = self.used.get(name)
        if e is not None:
            return e
        if self.host is not None:
            e = self.host.lookup(name)
            if e is not None:
                return e
        e = self.module.get(name)
        if e is not None:
            return e
        p = self.prog.externals.get(name)
        if p is not None:
            return Entity("proc", name, owner=p.module, proc=p)
        if name in INTRINSICS:
            return Entity("intrinsic", name)
        return None


# --------------------------------------------------------------------- generic resolution

def dummy_types(prog, p):
    """(name, FT, optional) of each dummy argument."""
    out = []
    for a in p.args:
        d = p.decls.get(a)
        if d is None:
            # a dummy procedure declared by an interface block
            out.append((a, FT("procedure"), False))
            continue
        out.append((a, decl_type(d), "optional" in d.attrs))
    return out


def compatible(dummy, actual):
    if dummy.base == "procedure":
        return actual.base == "procedure"
    if dummy.base != actual.base:
        return False
    if dummy.base == "type" and dummy.dtype != actual.dtype:
        return False
    return dummy.rank == actual.rank


def resolve_generic(prog, specifics, actual_types, keywords):
    """The specific procedure whose dummies match the actual arguments' types, ranks and keywords."""
    found = []
    for p in specifics:
        dummies = dummy_types(prog, p)
        bound = [None] * len(dummies)
        ok = True
        pos = 0
        for t, k in zip(actual_types, keywords):
            if k is None:
                if pos >= len(dummies):
                    ok = False
                    break
                bound[pos] = t
                pos += 1
            else:
                idx = next((i for i, d in enumerate(dummies) if d[0] == k), None)
                if idx is None:
                    ok = False
                    break
                bound[idx] = t
        if not ok:
            continue
        for (n, dt, opt), at in zip(dummies, bound):
            if at is None:
                if not opt:
                    ok = False
                    break
            elif not compatible(dt, at):
                ok = False
                break
        if ok:
            found.append(p)
    if len(found) == 1:
        return found[0]
    if not found:
        return None
    # elemental and non-elemental with the same shape: prefer the exact one first found
    return found[0]

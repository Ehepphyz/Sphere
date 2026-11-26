"""Java from the Fortran model: one class per module, instance fields for module variables,
instance methods for procedures, so that every engine (GolemCore) is independent of every other."""
import re
import struct

from fsrc import (parse_expr, Name, Call, Comp, Num, Str, Logic, Cplx, Un, Bin, Paren, ArrCtor, ImpliedDo, Arg,
                  Range)
from fsem import (GenError, FT, INT, REAL, CPLX, LOG, CHAR, VOID, NUMERIC_ORDER, decl_type, Entity, Scope, resolve_generic,
                  dummy_types, INTRINSICS)

JAVA_KEYWORDS = set("""abstract assert boolean break byte case catch char class const continue default do double else
enum extends final finally float for goto if implements import instanceof int interface long native new package private
protected public return short static strictfp super switch synchronized this throw throws transient try void volatile
while true false null var record yield sealed permits""".split())




def jname(n):
    return n + "_" if n in JAVA_KEYWORDS else n


def mod_class(m):
    return "M_" + m.name


def mod_field(m):
    return "m_" + m.name


def single(v):
    """The double value of a default-real (single precision) literal."""
    return struct.unpack("f", struct.pack("f", v))[0]


def jdouble(v):
    if v != v:
        return "Double.NaN"
    if v in (float("inf"), float("-inf")):
        return "Double.POSITIVE_INFINITY" if v > 0 else "Double.NEGATIVE_INFINITY"
    r = repr(float(v))
    if "e" in r or "E" in r:
        r = r.replace("e", "E")
        if "." not in r.split("E")[0]:
            r = r.replace("E", ".0E")
    return r


def jstring(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


class VarInfo:
    """How a Fortran variable is reached from Java."""

    def __init__(self, access, ft, alloc=False, holder=False, lbs=None, decl=None, boxed=False, pointer=False):
        self.access = access        # Java expression for the variable itself (the holder, the Alloc, the array...)
        self.ft = ft
        self.alloc = alloc          # an Alloc wrapper (allocatable or pointer array)
        self.holder = holder        # a by-reference dummy held in a Ref
        self.lbs = lbs              # Java lower bound expressions, plain arrays
        self.decl = decl
        self.boxed = boxed
        self.pointer = pointer

    def value(self):
        """The Java expression for the value (scalar) or the data (array)."""
        if self.holder:
            return self.access + ".v"
        return self.access

    def data(self):
        """The Java array holding an array's elements."""
        base = self.value()
        return base + ".a" if self.alloc else base

    def lb(self, k):
        if self.alloc:
            return f"{self.value()}.lb[{k}]"
        return self.lbs[k] if self.lbs else "1"


class Ctx:
    """The state of the procedure being generated."""

    def __init__(self, gen, mod, proc, scope):
        self.gen = gen
        self.mod = mod
        self.proc = proc
        self.scope = scope
        self.pre = []
        self.post = []
        self.tmp = 0
        self.loops = []             # construct labels of enclosing loops
        self.locals_declared = set()

    def temp(self, prefix="t"):
        self.tmp += 1
        return f"_{prefix}{self.tmp}"


class Gen:
    # A method's Java source above this many characters risks the JVM's 64 KB bytecode limit.
    SPLIT_CHARS = 60_000

    def __init__(self, prog, notes):
        self.prog = prog
        self.notes = notes          # what the translator decided, for the generated header
        self.failures = []
        self.split = set()          # procedures whose locals are fields, so their body can be cut in parts
        self.extra = []             # field declarations and part methods of the module being generated
        self.byref = {}             # (proc id) -> set of dummy names passed by reference
        self.host_fields = {}       # host proc id -> set of variable names made fields
        self.funcs = {}             # functional interface name -> (param java types, return java type)
        self.analyse()

    # ================================================================= analysis
    def all_procs(self):
        for m in self.prog.modules.values():
            for p in m.procs.values():
                yield p
                for q in p.internal:
                    yield q

    def analyse(self):
        # host variables an internal procedure uses become fields of the module instance
        for p in self.all_procs():
            if p.host is not None:
                used = set()
                self._names_in(p.body, used)
                host = p.host
                shared = {n for n in used if n in host.decls and n not in p.decls}
                self.host_fields.setdefault(id(host), set()).update(shared)
        # dummies written in the procedure, to a fixpoint through calls
        written = {id(p): set() for p in self.all_procs()}
        for p in self.all_procs():
            for a in p.args:
                d = p.decls.get(a)
                if d is not None and d.intent in ("out", "inout"):
                    written[id(p)].add(a)
        changed = True
        rounds = 0
        while changed and rounds < 20:
            changed = False
            rounds += 1
            for p in self.all_procs():
                before = len(written[id(p)])
                self._writes(p, p.body, written)
                if len(written[id(p)]) != before:
                    changed = True
        for p in self.all_procs():
            self.byref[id(p)] = {a for a in written[id(p)] if self._needs_holder(p, a)}
        self.written = written

    def _needs_holder(self, p, a):
        d = p.decls.get(a)
        if d is None:
            return False
        if d.intent == "in":
            return False
        if d.rank > 0:
            return False
        if d.base == "type":
            return "pointer" in d.attrs or "allocatable" in d.attrs
        return True

    def _names_in(self, stmts, out):
        for s in stmts:
            for v in s.__dict__.values():
                self._names_expr(v, out)

    def _names_expr(self, v, out):
        if isinstance(v, list):
            for x in v:
                self._names_expr(x, out)
        elif isinstance(v, tuple):
            for x in v:
                self._names_expr(x, out)
        elif isinstance(v, Name):
            out.add(v.id)
        elif hasattr(v, "__slots__"):
            for k in v.__slots__:
                self._names_expr(getattr(v, k), out)
        elif hasattr(v, "kind") and hasattr(v, "line") and isinstance(getattr(v, "kind", None), str):
            for x in v.__dict__.values():
                self._names_expr(x, out)
        elif isinstance(v, str):
            pass

    @staticmethod
    def base_name(e):
        while True:
            if isinstance(e, Name):
                return e.id
            if isinstance(e, Call):
                e = e.base
            elif isinstance(e, Comp):
                e = e.base
            elif isinstance(e, Paren):
                return None
            else:
                return None

    def _writes(self, p, stmts, written):
        mine = written[id(p)]
        args = set(p.args)
        scope = None
        for s in stmts:
            k = s.kind
            targets = []
            if k in ("assign", "ptrassign"):
                targets.append(s.lhs)
            elif k in ("allocate", "deallocate", "nullify"):
                targets.extend(s.items)
                if s.stat is not None:
                    targets.append(s.stat)
            elif k == "do":
                targets.append(Name(s.var))
            elif k == "read":
                targets.extend(s.items)
            elif k == "write" and s.unit is not None and not isinstance(s.unit, Num):
                targets.append(s.unit)
            elif k == "call":
                if scope is None:
                    scope = Scope(self.prog, p)
                self._call_writes(scope, s.name, s.args, written, targets)
            for t in targets:
                n = self.base_name(t)
                if n in args:
                    mine.add(n)
            # calls inside expressions: functions with written dummies
            if k in ("assign", "if", "dowhile", "select", "write", "call"):
                if scope is None:
                    scope = Scope(self.prog, p)
                for expr in self._exprs_of(s):
                    self._expr_call_writes(scope, expr, written, mine, args)
            for sub in ("body", "other"):
                b = getattr(s, sub, None)
                if isinstance(b, list):
                    self._writes(p, b, written)
            if k == "if":
                for _, b in s.branches:
                    self._writes(p, b, written)
            if k == "select":
                for _, b in s.cases:
                    self._writes(p, b, written)

    def _exprs_of(self, s):
        out = []
        for v in s.__dict__.values():
            if isinstance(v, (Call, Bin, Un, Paren, Comp)):
                out.append(v)
            elif isinstance(v, list):
                for x in v:
                    if isinstance(x, (Call, Bin, Un, Paren, Comp, Arg)):
                        out.append(x)
        if s.kind == "if":
            out.extend(c for c, _ in s.branches)
        return out

    def _expr_call_writes(self, scope, e, written, mine, args):
        if isinstance(e, Arg):
            e = e.value
        if isinstance(e, Call) and isinstance(e.base, Name):
            ent = scope.lookup(e.base.id)
            if ent is not None and ent.kind in ("proc", "generic"):
                targets = []
                self._call_writes(scope, e.base.id, e.args, written, targets)
                for t in targets:
                    n = self.base_name(t)
                    if n in args:
                        mine.add(n)
            for a in e.args:
                self._expr_call_writes(scope, a.value if isinstance(a, Arg) else a, written, mine, args)
        elif isinstance(e, Bin):
            self._expr_call_writes(scope, e.a, written, mine, args)
            self._expr_call_writes(scope, e.b, written, mine, args)
        elif isinstance(e, (Un, Paren)):
            self._expr_call_writes(scope, e.x, written, mine, args)

    def _call_writes(self, scope, name, cargs, written, targets):
        ent = scope.lookup(name)
        if ent is None:
            return
        cands = [ent.proc] if ent.kind == "proc" else list(ent.specifics) if ent.kind == "generic" else []
        for q in cands:
            w = written.get(id(q), set())
            for i, a in enumerate(cargs):
                if a.key is not None:
                    if a.key in w:
                        targets.append(a.value)
                elif i < len(q.args) and q.args[i] in w:
                    targets.append(a.value)

    # ================================================================= types
    def type_class(self, dtype):
        m, t = self.prog.types[dtype]
        return f"{mod_class(m)}.T_{dtype}"

    def jelem(self, ft):
        b = ft.base
        if b == "integer":
            return "int"
        if b == "real":
            return "double"
        if b == "complex":
            return "Cx"
        if b == "logical":
            return "boolean"
        if b == "character":
            return "String"
        if b == "type":
            return self.type_class(ft.dtype)
        raise GenError(f"no Java type for {ft}")

    def jtype(self, ft, alloc=False):
        t = self.jelem(ft) + "[]" * ft.rank
        return f"Alloc<{t}>" if alloc else t

    @staticmethod
    def boxed(jt):
        return {"int": "Integer", "double": "Double", "boolean": "Boolean"}.get(jt, jt)

    @staticmethod
    def holder(jt):
        return {"int": "IRef", "double": "DRef", "boolean": "BRef"}.get(jt, f"Ref<{jt}>")

    def zero(self, ft):
        b = ft.base
        if b == "integer":
            return "0"
        if b == "real":
            return "0.0"
        if b == "complex":
            return "Cx.ZERO"
        if b == "logical":
            return "false"
        if b == "character":
            return '""'
        if b == "type":
            return f"new {self.type_class(ft.dtype)}()"
        raise GenError(f"no default for {ft}")

    def new_array(self, ft, extents):
        """A Java expression creating an array of the given extents filled with defaults."""
        et = self.jelem(ft)
        dims = ''.join('[' + e + ']' for e in extents)
        if ft.base in ("complex", "type", "character"):
            filler = {"complex": "() -> Cx.ZERO", "character": '() -> ""'}.get(ft.base)
            if ft.base == "type":
                filler = f"{self.type_class(ft.dtype)}::new"
            return f"F.fillAll(new {et}{dims}, {filler})"
        return f"new {et}{dims}"

    # ================================================================= modules
    def module_source(self, m, package):
        L = []
        L.append(f"package {package};")
        L.append("")
        L.append(f"/**")
        L.append(f" * Fortran module {m.name} of {m.file}, translated for Sphere.")
        L.append(f" * Generated by Sphere's Fortran translator from Golem95 1.3.4 (with OneLOop 3.6.1):")
        L.append(f" * do not edit; change the translator and generate again.")
        L.append(f" */")
        L.append("@SuppressWarnings({\"unused\", \"cast\", \"UnusedAssignment\"})")
        L.append(f"public final class {mod_class(m)} {{")
        L.append("    final GolemCore core;")
        L.append("")
        # derived types
        for t in m.types.values():
            L.extend("    " + x for x in self.type_source(m, t))
            L.append("")
        # constants and variables
        statics, fields = [], []
        for name in m.order:
            d = m.decls[name]
            if d.base is None:
                continue
            if "parameter" in d.attrs:
                statics.append(self.constant(m, d, None))
            else:
                fields.append(self.field(m, d, None))
        L.extend("    " + s for s in statics if s)
        L.extend("    " + s for s in fields if s)
        # saved locals and shared host variables of the procedures
        for p in m.procs.values():
            for q in [p] + p.internal:
                for name in q.order:
                    d = q.decls[name]
                    if d.base is None or name in q.args or "parameter" in d.attrs:
                        continue
                    if d.init is not None or "save" in d.attrs or name in self.host_fields.get(id(q), set()):
                        fields.append(None)
                        L.append("    " + self.field(m, d, q))
        L.append("")
        L.append(f"    {mod_class(m)}(GolemCore core) {{")
        L.append("        this.core = core;")
        L.append("    }")
        self.extra = []
        for p in m.procs.values():
            for q in [p] + p.internal:
                L.append("")
                try:
                    L.extend("    " + x for x in self.proc_source(m, q))
                except (GenError, KeyError) as e:
                    self.failures.append(f"{m.name}.{q.name}: {e}")
                    L.extend("    " + x for x in self.stub_source(m, q, e))
        for x in self.extra:
            L.append("")
            L.extend("    " + y for y in x)
        L.append("}")
        return "\n".join(L) + "\n"

    def stub_source(self, m, p, why):
        """A method that says it was not translated, so the rest still compiles."""
        try:
            rt, ralloc = self.result_type(p)
            rjt = "void" if rt == VOID else self.jtype(rt, ralloc)
            params = ", ".join(f"{self.param_java(p, a)[0]} {jname(a)}" for a in p.args)
        except Exception:
            rjt, params = "void", ""
        msg = jstring(f"{p.name} was not translated: {why}"[:300])
        return [f"/** Not translated: {str(why)[:200].replace('*/', '* /')} */",
                f"{'private ' if p.host else 'public '}{rjt} {self.proc_jname(p)}({params}) {{",
                f"    throw new UnsupportedOperationException({msg});",
                "}"]

    def type_source(self, m, t):
        cls = f"T_{t.name}"
        L = [f"public static final class {cls} implements FType {{"]
        comps = []
        for c in t.components:
            ft = decl_type(c)
            alloc = c.rank > 0 and ("allocatable" in c.attrs or "pointer" in c.attrs)
            ptr_scalar = c.rank == 0 and "pointer" in c.attrs
            jt = self.jtype(ft, alloc)
            if alloc:
                init = "null" if "pointer" in c.attrs else "new Alloc<>()"
            elif ptr_scalar:
                init = "null"
            elif c.rank > 0:
                ext = [self.extent_text(lo, hi, None, None) for lo, hi in c.dims]
                init = self.new_array(ft, ext)
            elif c.init is not None:
                init = self.const_expr(m, c.init, ft)
                if ft.base == "type" and not init.startswith("new "):
                    init += ".copy()"
            else:
                init = self.zero(ft)
            comps.append((c, ft, jt, alloc, ptr_scalar))
            L.append(f"    public {jt} {jname(c.name)} = {init};")
        L.append("")
        L.append(f"    public {cls}() {{")
        L.append("    }")
        if comps:
            params = ", ".join(f"{jt} {jname(c.name)}" for c, ft, jt, a, p in comps)
            L.append("")
            L.append(f"    public {cls}({params}) {{")
            for c, ft, jt, a, p in comps:
                L.append(f"        this.{jname(c.name)} = {jname(c.name)};")
            L.append("    }")
        L.append("")
        L.append(f"    /** Fortran's intrinsic assignment: a copy of every component (pointers stay pointers). */")
        L.append(f"    public void assign({cls} o) {{")
        for c, ft, jt, alloc, ptr in comps:
            n = jname(c.name)
            if ptr or (alloc and "pointer" in c.attrs):
                L.append(f"        this.{n} = o.{n};")
            elif alloc:
                L.append(f"        this.{n} = o.{n}.copy();")
            elif c.rank > 0:
                L.append(f"        this.{n} = F.copyOf(o.{n});")
            elif ft.base == "type":
                L.append(f"        this.{n}.assign(o.{n});")
            else:
                L.append(f"        this.{n} = o.{n};")
        L.append("    }")
        L.append("")
        L.append(f"    public {cls} copy() {{")
        L.append(f"        {cls} r = new {cls}();")
        L.append("        r.assign(this);")
        L.append("        return r;")
        L.append("    }")
        L.append("")
        L.append("    @Override")
        L.append("    public Object copyValue() {")
        L.append("        return copy();")
        L.append("    }")
        L.append("}")
        return L

    def extent_text(self, lo, hi, m, scope_ctx):
        """Java extent of a declared dimension lo:hi (constant or in terms of dummies)."""
        if hi in (":", "*"):
            raise GenError("deferred or assumed size has no extent here")
        hi_j = self.dim_expr(hi, scope_ctx)
        if lo is None:
            return hi_j
        lo_j = self.dim_expr(lo, scope_ctx)
        return f"({hi_j}) - ({lo_j}) + 1"

    def dim_expr(self, text, ctx):
        e = parse_expr(text)
        if ctx is None:
            return self.static_int(e)
        code, ft = self.expr(e, ctx)
        if ft.base != "integer":
            code = f"(int) ({code})"
        return code

    def static_int(self, e):
        """An integer constant expression of a declaration outside any procedure."""
        if isinstance(e, Num) and e.kind == "int":
            return str(e.value)
        if isinstance(e, Name):
            return self.static_name(e.id)
        if isinstance(e, Bin) and e.op in "+-*/":
            return f"({self.static_int(e.a)} {e.op} {self.static_int(e.b)})"
        if isinstance(e, Un) and e.op == "-":
            return f"(-{self.static_int(e.x)})"
        if isinstance(e, Paren):
            return self.static_int(e.x)
        if isinstance(e, Bin) and e.op == "**":
            return f"F.ipow({self.static_int(e.a)}, {self.static_int(e.b)})"
        raise GenError(f"not a constant integer: {e}")

    def static_name(self, n):
        for m in self.prog.modules.values():
            d = m.decls.get(n)
            if d is not None and "parameter" in d.attrs:
                return f"{mod_class(m)}.{jname(n)}"
        raise GenError(f"constant {n} unknown")

    def const_expr(self, m, text, ft):
        """A module-level initializer, evaluated once in Java."""
        ctx = Ctx(self, m, None, ModuleOnlyScope(self.prog, m))
        e = parse_expr(text)
        if isinstance(e, ArrCtor) and ft.rank > 0:
            return self.array_ctor(e, ctx, ft)
        code, t = self.expr(e, ctx)
        if ctx.pre:
            raise GenError(f"initializer needs statements: {text}")
        return self.convert(code, t, ft)

    def constant(self, m, d, proc):
        ft = decl_type(d)
        ctx = Ctx(self, m, proc, ModuleOnlyScope(self.prog, m))
        if d.init is None:
            raise GenError(f"parameter {d.name} without a value")
        e = parse_expr(d.init)
        if ft.rank > 0:
            code = self.array_value(e, ctx, ft)
            if code is None:
                v, t = self.expr(e, ctx)
                ext = [self.extent_text(lo, hi, m, None) for lo, hi in d.dims]
                code = f"F.filled({self.new_array(ft, ext)}, {self.convert(v, t, ft.elem())})"
        else:
            code, t = self.expr(e, ctx)
            code = self.convert(code, t, ft)
        if ctx.pre:
            raise GenError(f"constant {d.name} needs statements")
        return f"public static final {self.jtype(ft)} {jname(d.name)} = {code};"

    def field(self, m, d, proc):
        ft = decl_type(d)
        alloc = d.rank > 0 and ("allocatable" in d.attrs or "pointer" in d.attrs)
        name = jname(d.name) if proc is None else f"{proc.name}__{d.name}"
        jt = self.jtype(ft, alloc)
        if ft.base == "procedure":
            raise GenError(f"procedure variable {d.name}")
        if alloc:
            init = "null" if "pointer" in d.attrs else "new Alloc<>()"
        elif d.rank == 0 and "pointer" in d.attrs:
            init = "null"
        elif d.rank > 0:
            ext = [self.extent_text(lo, hi, m, None) for lo, hi in d.dims]
            init = self.new_array(ft, ext)
            if d.init is not None:
                init = self.array_init(m, d, ft, init)
        elif d.init is not None:
            init = self.const_expr(m, d.init, ft)
            if ft.base == "type" and not init.startswith("new "):
                # a value, not the constant itself: assigning to the variable must not change the constant
                init += ".copy()"
        else:
            init = self.zero(ft)
        return f"public {jt} {name} = {init};"

    def array_init(self, m, d, ft, fresh):
        ctx = Ctx(self, m, None, ModuleOnlyScope(self.prog, m))
        e = parse_expr(d.init)
        code = self.array_value(e, ctx, ft)
        if code is not None:
            return code
        v, t = self.expr(e, ctx)
        return f"F.filled({fresh}, {self.convert(v, t, ft.elem())})"

    # ================================================================= procedures
    def proc_jname(self, p):
        return jname(p.name) if p.host is None else f"{p.host.name}__{p.name}"

    def is_function(self, p):
        return p.kind == "function"

    def result_type(self, p):
        if not self.is_function(p):
            return VOID, False
        d = p.decls.get(p.result)
        if d is None:
            if p.rtype:
                from fmodel import parse_type_spec
                spec = parse_type_spec(p.rtype + " :: x")
                base = spec[0]
                return (FT("type", 0, spec[2]) if base == "type" else FT(base)), False
            raise GenError(f"function {p.name} without a result type")
        alloc = d.rank > 0 and ("allocatable" in d.attrs or "pointer" in d.attrs)
        return decl_type(d), alloc

    def param_java(self, p, a):
        """(java type, kind) of a dummy argument: kind is value, holder, boxed, func."""
        d = p.decls.get(a)
        if d is None or d.base is None:
            # a dummy procedure
            for itf in p.interfaces:
                for b in itf.bodies:
                    if b.name == a:
                        return self.func_interface(b), "func"
            raise GenError(f"dummy {a} of {p.name} has no declaration")
        ft = decl_type(d)
        alloc = d.rank > 0 and ("allocatable" in d.attrs or "pointer" in d.attrs)
        jt = self.jtype(ft, alloc)
        if a in self.byref[id(p)]:
            return self.holder(jt), "holder"
        if "optional" in d.attrs and d.rank == 0 and ft.base in ("integer", "real", "logical"):
            return self.boxed(jt), "boxed"
        return jt, "value"

    def func_interface(self, b):
        params = []
        for a in b.args:
            d = b.decls.get(a)
            ft = decl_type(d)
            params.append(self.jtype(ft))
        d = b.decls.get(b.result)
        rt = self.jtype(decl_type(d)) if d is not None else "void"
        name = "Fn_" + "_".join(re.sub(r"\W", "", x.replace("[]", "A")) for x in params) + "__" + re.sub(r"\W", "", rt.replace("[]", "A"))
        self.funcs[name] = (params, rt)
        return name

    def proc_source(self, m, p):
        lines = self.proc_source_plain(m, p)
        if sum(len(x) for x in lines) <= self.SPLIT_CHARS:
            return lines
        if "recursive" in p.prefixes or p.host is not None:
            raise GenError(f"{p.name} is too large for one Java method and cannot be split")
        return self.proc_source_split(m, p)

    def proc_source_split(self, m, p):
        """A procedure too large for one Java method: its variables become fields of the module instance
        (the procedure is not recursive, and an engine is used by one thread), and its larger branches
        become methods of their own that return whether the Fortran returned."""
        self.split.add(id(p))
        scope = Scope(self.prog, p)
        ctx = Ctx(self, m, p, scope)
        ctx.part = 0
        rt, ralloc = self.result_type(p)
        rjt = "void" if rt == VOID else self.jtype(rt, ralloc)
        params = []
        entry = []
        fields = [f"// the variables of {p.name}, which is cut into parts"]
        for a in p.args:
            jt, kind = self.param_java(p, a)
            params.append(f"{jt} {jname(a)}")
            fields.append(f"private {jt} {p.name}__{a};")
            entry.append(f"this.{p.name}__{a} = {jname(a)};")
        for name in p.order:
            d = p.decls[name]
            if d.base is None or name in p.args:
                continue
            if name in self.host_fields.get(id(p), set()) or (d.init is not None and "parameter" not in d.attrs) \
                    or "save" in d.attrs:
                continue
            for decl in self.local_decl(ctx, d, p):
                # "T x = init;" or "final T x = init;": a field, set again at each call
                mm = re.match(r"^(final\s+)?(.+?)\s+(\w+)\s*=\s*(.*);$", decl)
                jt, var, init = mm.group(2), mm.group(3), mm.group(4)
                fields.append(f"private {jt} {p.name}__{var};")
                entry.append(f"this.{p.name}__{var} = {init};")
        res = f"this.{p.name}__{p.result}" if self.is_function(p) else None
        if self.is_function(p) and p.result not in p.decls:
            fields.append(f"private {rjt} {p.name}__{p.result};")
            entry.append(f"{res} = {self.zero(rt)};")
        self.extra.append(fields)
        body = self.split_block(p.body, ctx)
        L = [f"/** Fortran {p.kind} {p.name} ({p.file}:{p.line}), in parts: it is too large for one Java method. */"]
        L.append(f"public {rjt} {self.proc_jname(p)}({', '.join(params)}) {{")
        L.extend("    " + x for x in entry)
        done = f"return {res};" if res else "return;"
        for x in body:
            L.append("    " + x.replace("@RETURN@", done))
        if self.is_function(p):
            L.append(f"    return {res};")
        L.append("}")
        return L

    def split_block(self, stmts, ctx):
        """Lines for a block, its parts outlined into methods while it is too large."""
        chunks = []
        for s in stmts:
            lines = self.stmt(s, ctx)
            if sum(len(x) for x in lines) > self.SPLIT_CHARS and s.kind in ("if", "select") \
                    and not self.leaves_loop(s):
                lines = self.outlined_branches(s, ctx)
            chunks.append(lines)
        total = sum(len(x) for c in chunks for x in c)
        if total <= self.SPLIT_CHARS:
            return [x for c in chunks for x in c]
        # consecutive statements into parts of a size the JVM takes
        out, part, size = [], [], 0
        for c in chunks:
            n = sum(len(x) for x in c)
            if part and size + n > self.SPLIT_CHARS:
                out.extend(self.part_call(part, ctx))
                part, size = [], 0
            part.extend(c)
            size += n
        if part:
            out.extend(self.part_call(part, ctx))
        return out

    def outlined_branches(self, s, ctx):
        """An if or a select whose branches are each a part method."""
        if s.kind == "if":
            out = []
            for i, (c, body) in enumerate(s.branches):
                saved = ctx.pre
                ctx.pre = []
                cond = self.cond(c, ctx)
                pre = ctx.pre
                ctx.pre = saved
                if i == 0:
                    out.extend(pre)
                    out.append(f"if ({cond}) {{")
                else:
                    if pre:
                        raise GenError("an else-if condition needs statements in a part")
                    out.append(f"}} else if ({cond}) {{")
                out.extend("    " + x for x in self.part_call(self.split_block(body, ctx), ctx))
            if s.other is not None:
                out.append("} else {")
                out.extend("    " + x for x in self.part_call(self.split_block(s.other, ctx), ctx))
            out.append("}")
            return out
        sel, t = self.expr(s.sel, ctx)
        tmp = ctx.temp("sel")
        out = [f"final {self.jtype(t)} {tmp} = {sel};"]
        first = True
        default = None
        for sels, body in s.cases:
            if sels == "default":
                default = body
                continue
            conds = []
            for item in sels:
                if item[0] == "value":
                    v, vt = self.expr(item[1], ctx)
                    conds.append(self.compare("==", tmp, t, v, vt))
                else:
                    parts = []
                    if item[1] is not None:
                        v, vt = self.expr(item[1], ctx)
                        parts.append(self.compare(">=", tmp, t, v, vt))
                    if item[2] is not None:
                        v, vt = self.expr(item[2], ctx)
                        parts.append(self.compare("<=", tmp, t, v, vt))
                    conds.append("(" + " && ".join(parts) + ")")
            out.append(("if" if first else "} else if") + f" ({' || '.join(conds)}) {{")
            first = False
            out.extend("    " + x for x in self.part_call(self.split_block(body, ctx), ctx))
        if default is not None:
            out.append("} else {" if not first else "{")
            out.extend("    " + x for x in self.part_call(self.split_block(default, ctx), ctx))
        out.append("}")
        return out

    def part_call(self, lines, ctx):
        """Moves lines into a method of their own; the call returns from the caller when they returned."""
        if not lines:
            return []
        if sum(len(x) for x in lines) < 2_000:
            return lines
        ctx.tmp += 1
        name = f"{ctx.proc.name}__part{ctx.tmp}"
        method = [f"private boolean {name}() {{"]
        method.extend("    " + x.replace("@RETURN@", "return true;") for x in lines)
        method.append("    return false;")
        method.append("}")
        self.extra.append(method)
        return [f"if (this.{name}()) @RETURN@"]

    def leaves_loop(self, s):
        """Whether a statement holds an exit or a cycle of a loop around it (it cannot be outlined then)."""
        found = []

        def walk(stmts, depth):
            for x in stmts:
                if x.kind in ("exit", "cycle") and depth == 0:
                    found.append(x)
                inner = depth + (1 if x.kind in ("do", "dowhile", "doforever") else 0)
                for sub in ("body", "other"):
                    b = getattr(x, sub, None)
                    if isinstance(b, list):
                        walk(b, inner)
                if x.kind == "if":
                    for _, b in x.branches:
                        walk(b, inner)
                if x.kind == "select":
                    for _, b in x.cases:
                        walk(b, inner)
        walk([s], 0)
        return bool(found)

    def proc_source_plain(self, m, p):
        scope = Scope(self.prog, p)
        ctx = Ctx(self, m, p, scope)
        rt, ralloc = self.result_type(p)
        rjt = "void" if rt == VOID else self.jtype(rt, ralloc)
        params = []
        for a in p.args:
            jt, kind = self.param_java(p, a)
            params.append(f"{jt} {jname(a)}")
        mods = "public " if p.host is None else "private "
        L = [f"/** Fortran {p.kind} {p.name} ({p.file}:{p.line}). */"]
        L.append(f"{mods}{rjt} {self.proc_jname(p)}({', '.join(params)}) {{")
        body = []
        # locals
        for name in p.order:
            d = p.decls[name]
            if d.base is None or name in p.args:
                continue
            if name in self.host_fields.get(id(p), set()) or (d.init is not None and "parameter" not in d.attrs) \
                    or "save" in d.attrs:
                continue                    # a field of the module instance
            body.extend(self.local_decl(ctx, d, p))
        if self.is_function(p) and p.result not in p.decls:
            body.append(f"{rjt} {jname(p.result)} = {self.zero(rt)};")
        stmts = self.block(p.body, ctx)
        body.extend(stmts)
        ends = p.body and p.body[-1].kind in ("return", "stop")
        if self.is_function(p) and not ends:
            body.append(f"return {jname(p.result)};")
        L.extend("    " + x for x in body)
        L.append("}")
        return L

    def local_decl(self, ctx, d, p):
        ft = decl_type(d)
        n = jname(d.name)
        if ft.base == "procedure":
            raise GenError(f"procedure variable {d.name} in {p.name}")
        if "parameter" in d.attrs:
            e = parse_expr(d.init)
            if ft.rank > 0:
                code = self.array_value(e, ctx, ft)
                if code is None:
                    v, t = self.expr(e, ctx)
                    ext = [self.extent_text(lo, hi, None, ctx) for lo, hi in d.dims]
                    code = f"F.filled({self.new_array(ft, ext)}, {self.convert(v, t, ft.elem())})"
            else:
                v, t = self.expr(e, ctx)
                code = self.convert(v, t, ft)
            return [f"final {self.jtype(ft)} {n} = {code};"]
        alloc = d.rank > 0 and ("allocatable" in d.attrs or "pointer" in d.attrs)
        if alloc:
            return [f"{self.jtype(ft, True)} {n} = {'null' if 'pointer' in d.attrs else 'new Alloc<>()'};"]
        if d.rank == 0 and "pointer" in d.attrs:
            return [f"{self.jtype(ft)} {n} = null;"]
        if d.rank > 0:
            ext = [self.extent_text(lo, hi, None, ctx) for lo, hi in d.dims]
            return [f"{self.jtype(ft)} {n} = {self.new_array(ft, ext)};"]
        if ft.base == "character" and d.char_len not in (None, "*", "1") and re.fullmatch(r"\d+", d.char_len or ""):
            return [f"String {n} = \"\";"]
        return [f"{self.jtype(ft)} {n} = {self.zero(ft)};"]

    # ================================================================= variables
    def var(self, name, ctx):
        """VarInfo for a variable name as seen in ctx."""
        ent = ctx.scope.lookup(name)
        if ent is None:
            # the result of a function named like itself
            raise GenError(f"{ctx.proc.file if ctx.proc else ctx.mod.file}: unknown name '{name}' in {ctx.proc.name if ctx.proc else ctx.mod.name}")
        return self.var_of(ent, ctx)

    def var_of(self, ent, ctx):
        if ent.kind != "var":
            raise GenError(f"'{ent.name}' is not a variable")
        d = ent.decl
        ft = decl_type(d)
        alloc = d.rank > 0 and ("allocatable" in d.attrs or "pointer" in d.attrs)
        pointer = "pointer" in d.attrs
        lbs = None
        if d.rank > 0 and not alloc:
            lbs = []
            for lo, hi in d.dims:
                if lo is None:
                    lbs.append("1")
                else:
                    lbs.append(self.lb_code(lo, ent, ctx))
        owner = ent.owner
        n = jname(ent.name)
        from fmodel import Module
        if isinstance(owner, Module):
            if "parameter" in d.attrs:
                return VarInfo(f"{mod_class(owner)}.{n}", ft, alloc, False, lbs, d, pointer=pointer)
            if ctx.mod is owner:
                return VarInfo(f"this.{n}", ft, alloc, False, lbs, d, pointer=pointer)
            return VarInfo(f"core.{mod_field(owner)}.{n}", ft, alloc, False, lbs, d, pointer=pointer)
        # a local or a dummy of owner (ctx.proc or its host)
        proc = owner
        if "parameter" in d.attrs:
            return VarInfo(n, ft, alloc, False, lbs, d)
        if d.name in self.host_fields.get(id(proc), set()) or (d.init is not None and d.name not in proc.args) \
                or ("save" in d.attrs and d.name not in proc.args):
            return VarInfo(f"this.{proc.name}__{d.name}", ft, alloc, False, lbs, d, pointer=pointer)
        if proc is not ctx.proc:
            raise GenError(f"host variable {d.name} of {proc.name} used by {ctx.proc.name} is not a field")
        if id(proc) in self.split:
            # a procedure cut into parts keeps its variables in fields
            holder = d.name in proc.args and d.name in self.byref[id(proc)]
            boxed = d.name in proc.args and "optional" in d.attrs and d.rank == 0 \
                and ft.base in ("integer", "real", "logical") and not holder
            return VarInfo(f"this.{proc.name}__{d.name}", ft, alloc, holder, lbs, d, boxed=boxed, pointer=pointer)
        if d.name in proc.args:
            holder = d.name in self.byref[id(proc)]
            boxed = "optional" in d.attrs and d.rank == 0 and ft.base in ("integer", "real", "logical") and not holder
            return VarInfo(n, ft, alloc, holder, lbs, d, boxed=boxed, pointer=pointer)
        if proc.kind == "function" and d.name == proc.result:
            return VarInfo(n, ft, alloc, False, lbs, d, pointer=pointer)
        return VarInfo(n, ft, alloc, False, lbs, d, pointer=pointer)

    def lb_code(self, lo, ent, ctx):
        e = parse_expr(lo)
        if isinstance(e, Num):
            return str(e.value)
        if ctx is not None and ctx.proc is not None:
            code, ft = self.expr(e, ctx)
            return code
        return self.static_int(e)

    # ================================================================= statements
    def block(self, stmts, ctx):
        out = []
        for s in stmts:
            out.extend(self.stmt(s, ctx))
        return out

    def stmt(self, s, ctx):
        saved_pre, saved_post = ctx.pre, ctx.post
        ctx.pre, ctx.post = [], []
        try:
            lines = getattr(self, "s_" + s.kind)(s, ctx)
        except GenError as e:
            raise GenError(f"{ctx.proc.file}:{s.line}: {e}") from None
        out = ctx.pre + lines + ctx.post
        ctx.pre, ctx.post = saved_pre, saved_post
        return out

    def s_continue(self, s, ctx):
        return []

    def s_return(self, s, ctx):
        if id(ctx.proc) in self.split:
            return ["@RETURN@"]
        if ctx.proc.kind == "function":
            return [f"return {jname(ctx.proc.result)};"]
        return ["return;"]

    def s_stop(self, s, ctx):
        if s.code is None:
            return ["throw new FStop(\"STOP\");"]
        c, t = self.expr(s.code, ctx)
        return [f"throw new FStop(String.valueOf({c}));"]

    def s_exit(self, s, ctx):
        return [f"break {s.label};" if s.label else "break;"]

    def s_cycle(self, s, ctx):
        return [f"continue {s.label};" if s.label else "continue;"]

    def cond(self, e, ctx):
        c, t = self.expr(e, ctx)
        if t.base != "logical" or t.rank:
            raise GenError(f"condition is not a logical scalar: {t}")
        return c

    def s_if(self, s, ctx):
        out = []
        first = True
        # each condition after the first is evaluated only when reached: its statements go inside the else
        return self._if_chain(s.branches, s.other, ctx, s.label)

    def _if_chain(self, branches, other, ctx, label):
        (c0, b0), rest = branches[0], branches[1:]
        pre_saved = ctx.pre
        ctx.pre = []
        cond = self.cond(c0, ctx)
        pre = ctx.pre
        ctx.pre = pre_saved
        out = list(pre)
        head = f"if ({cond}) {{"
        if label:
            head = f"{label}: " + head
        out.append(head)
        out.extend("    " + x for x in self.block(b0, ctx))
        if rest:
            out.append("} else {")
            out.extend("    " + x for x in self._if_chain(rest, other, ctx, None))
            out.append("}")
        elif other is not None:
            out.append("} else {")
            out.extend("    " + x for x in self.block(other, ctx))
            out.append("}")
        else:
            out.append("}")
        return out

    def s_do(self, s, ctx):
        v = self.var(s.var, ctx)
        lo, tlo = self.expr(s.lo, ctx)
        hi, thi = self.expr(s.hi, ctx)
        var = v.value()
        out = []
        label = (s.label + ": ") if s.label else ""
        if s.step is None or (isinstance(s.step, Num) and s.step.value == 1):
            end = ctx.temp("hi")
            out.append(f"final int {end} = {self.convert(hi, thi, INT)};")
            out.append(f"{label}for ({var} = {self.convert(lo, tlo, INT)}; {var} <= {end}; {var}++) {{")
        else:
            st, tst = self.expr(s.step, ctx)
            n = ctx.temp("n")
            step = ctx.temp("st")
            out.append(f"final int {step} = {self.convert(st, tst, INT)};")
            out.append(f"{var} = {self.convert(lo, tlo, INT)};")
            out.append(f"final int {n} = Math.max(0, ({self.convert(hi, thi, INT)} - {var} + {step}) / {step});")
            k = ctx.temp("k")
            out.append(f"{label}for (int {k} = 0; {k} < {n}; {k}++, {var} += {step}) {{")
        out.extend("    " + x for x in self.block(s.body, ctx))
        out.append("}")
        return out

    def s_dowhile(self, s, ctx):
        pre_saved = ctx.pre
        ctx.pre = []
        c = self.cond(s.cond, ctx)
        pre = ctx.pre
        ctx.pre = pre_saved
        label = (s.label + ": ") if s.label else ""
        if pre:
            out = [f"{label}while (true) {{"]
            out.extend("    " + x for x in pre)
            out.append(f"    if (!({c})) break;")
        else:
            out = [f"{label}while ({c}) {{"]
        out.extend("    " + x for x in self.block(s.body, ctx))
        out.append("}")
        return out

    def s_doforever(self, s, ctx):
        label = (s.label + ": ") if s.label else ""
        out = [f"{label}while (true) {{"]
        out.extend("    " + x for x in self.block(s.body, ctx))
        out.append("}")
        return out

    def s_select(self, s, ctx):
        sel, t = self.expr(s.sel, ctx)
        tmp = ctx.temp("sel")
        out = [f"final {self.jtype(t)} {tmp} = {sel};"]
        first = True
        default = None
        branches = []
        for sels, body in s.cases:
            if sels == "default":
                default = body
                continue
            conds = []
            for item in sels:
                if item[0] == "value":
                    v, vt = self.expr(item[1], ctx)
                    conds.append(self.compare("==", tmp, t, v, vt))
                else:
                    parts = []
                    if item[1] is not None:
                        v, vt = self.expr(item[1], ctx)
                        parts.append(self.compare(">=", tmp, t, v, vt))
                    if item[2] is not None:
                        v, vt = self.expr(item[2], ctx)
                        parts.append(self.compare("<=", tmp, t, v, vt))
                    conds.append("(" + " && ".join(parts) + ")")
            branches.append((" || ".join(conds), body))
        label = (s.label + ": ") if s.label else ""
        if label:
            out.append(f"{label}{{")
        for i, (c, body) in enumerate(branches):
            out.append(("if" if i == 0 else "} else if") + f" ({c}) {{")
            out.extend("    " + x for x in self.block(body, ctx))
        if default is not None:
            if branches:
                out.append("} else {")
                out.extend("    " + x for x in self.block(default, ctx))
                out.append("}")
            else:
                out.extend(self.block(default, ctx))
        elif branches:
            out.append("}")
        if label:
            out.append("}")
        return out

    def compare(self, op, a, ta, b, tb):
        if ta.base == "character" or tb.base == "character":
            fn = {"==": "F.streq", "/=": "!F.streq"}.get(op)
            if fn:
                return f"{fn}({a}, {b})"
            return f"(F.strcmp({a}, {b}) {self.jop(op)} 0)"
        if ta.base == "complex" or tb.base == "complex":
            ca, cb = self.convert(a, ta, CPLX), self.convert(b, tb, CPLX)
            if op == "==":
                return f"Cx.eq({ca}, {cb})"
            if op == "/=":
                return f"!Cx.eq({ca}, {cb})"
            raise GenError("complex ordering")
        if ta.base == "logical":
            return f"({a} {'==' if op in ('==', 'eqv') else '!='} {b})"
        if ta.base != tb.base:
            a, b = self.convert(a, ta, REAL), self.convert(b, tb, REAL)
        return f"({a} {self.jop(op)} {b})"

    @staticmethod
    def jop(op):
        return {"/=": "!=", "==": "=="}.get(op, op)

    # --------------------------------------------------------------- calls
    def s_call(self, s, ctx):
        ent = ctx.scope.lookup(s.name)
        if ent is None:
            raise GenError(f"unknown subroutine {s.name}")
        if ent.kind == "intrinsic":
            raise GenError(f"intrinsic subroutine {s.name}")
        code = self.call(ent, s.name, s.args, ctx, statement=True)
        return [code + ";"]

    def call(self, ent, name, args, ctx, statement=False):
        """Java for a call to a procedure, with the copies in and out its by-reference dummies need."""
        if ent.kind == "dummyproc":
            parts = []
            for a, (dn, dt, opt) in zip(args, dummy_types(self.prog, ent.proc)):
                v, t = self.expr(a.value, ctx)
                parts.append(self.convert(v, t, dt) if dt.rank == 0 else v)
            return f"{jname(name)}.call({', '.join(parts)})"
        actual_types = []
        keys = []
        for a in args:
            keys.append(a.key)
            actual_types.append(self.arg_type(a.value, ctx))
        if ent.kind == "generic":
            p = resolve_generic(self.prog, ent.specifics, actual_types, keys)
            if p is None:
                p = self.resolve_elemental(ent.specifics, actual_types, keys)
            if p is None:
                raise GenError(f"no specific of {name} for {actual_types}")
        elif ent.kind == "proc":
            p = ent.proc
        else:
            raise GenError(f"{name} is not a procedure ({ent.kind})")
        elemental = "elemental" in p.prefixes and any(t.rank > 0 for t in actual_types if t is not None) \
            and not all(dt.rank == t.rank for (dn, dt, o), t in zip(dummy_types(self.prog, p), actual_types) if t is not None)
        if elemental:
            raise GenError(f"elemental call of {p.name} on arrays outside an array expression")
        return self.call_specific(p, args, ctx)

    def resolve_elemental(self, specifics, actual_types, keys):
        el = [p for p in specifics if "elemental" in p.prefixes]
        if not el:
            return None
        flat = [t.elem() if t is not None else None for t in actual_types]
        return resolve_generic(self.prog, el, flat, keys)

    def target(self, p, ctx):
        """Java receiver.method for a specific procedure."""
        m = p.module
        n = self.proc_jname(p)
        if m is ctx.mod:
            return f"this.{n}" if p.host is None else n
        return f"core.{mod_field(m)}.{n}"

    def bind(self, p, args):
        """The actual argument for each dummy, by position and keyword."""
        bound = [None] * len(p.args)
        pos = 0
        for a in args:
            if a.key is None:
                bound[pos] = a.value
                pos += 1
            else:
                bound[p.args.index(a.key)] = a.value
        return bound

    def call_specific(self, p, args, ctx):
        bound = self.bind(p, args)
        syn = getattr(p, "synthetic", None)
        if syn is not None:
            # a higher-rank form factor: one formula for all of them (M_form_factor_higher_ranks)
            g, n, r, suffix = syn
            ls = []
            for actual in bound[:-1]:
                v, t = self.expr(actual, ctx)
                ls.append(self.convert(v, t, INT))
            last = bound[-1]
            if suffix == "_b":
                v, t = self.expr(last, ctx)
                return (f"core.m_form_factor_higher_ranks.ff({g}, {n}, {r}, {self.convert(v, t, INT)}, "
                        f"new int[]{{{', '.join(ls)}}})")
            v, t = self.expr(last, ctx)
            if t.rank != 1:
                tmp, tt = self.materialize(last, ctx)
                v = tmp
            return (f"core.m_form_factor_higher_ranks.ffSet({g}, {n}, {r}, {v}, "
                    f"new int[]{{{', '.join(ls)}}})")
        parts = []
        for a, actual in zip(p.args, bound):
            jt, kind = self.param_java(p, a)
            d = p.decls.get(a)
            if actual is None:
                parts.append("null")
                continue
            if kind == "func":
                parts.append(self.proc_ref(actual, ctx, jt))
                continue
            dft = decl_type(d)
            if kind == "holder":
                parts.append(self.byref_arg(actual, dft, jt, ctx, p, a))
                continue
            if dft.rank > 0:
                parts.append(self.array_arg(actual, d, dft, ctx, p, a))
                continue
            if dft.base == "type":
                v, t = self.expr(actual, ctx)
                parts.append(v)
                continue
            v, t = self.expr(actual, ctx)
            parts.append(self.convert(v, t, dft))
        return f"{self.target(p, ctx)}({', '.join(parts)})"

    def proc_ref(self, actual, ctx, iface):
        if not isinstance(actual, Name):
            raise GenError("a procedure argument must be a name")
        ent = ctx.scope.lookup(actual.id)
        if ent is None:
            raise GenError(f"unknown procedure {actual.id}")
        if ent.kind == "dummyproc":
            return jname(actual.id)
        if ent.kind != "proc":
            raise GenError(f"{actual.id} passed as a procedure is a {ent.kind}")
        q = ent.proc
        tgt = self.target(q, ctx)
        recv, meth = tgt.rsplit(".", 1) if "." in tgt else ("this", tgt)
        return f"{recv}::{meth}"

    def byref_arg(self, actual, dft, jt, ctx, p, a):
        """A holder for a by-reference dummy, copied back to the actual after the call."""
        if isinstance(actual, Name):
            ent = ctx.scope.lookup(actual.id)
            if ent is not None and ent.kind == "var":
                v = self.var_of(ent, ctx)
                if v.holder and self.jtype(v.ft) == self.jtype(dft) and not v.alloc:
                    return v.access
        h = ctx.temp("r")
        is_lvalue = self.is_lvalue(actual, ctx)
        if is_lvalue:
            cur, ct = self.expr(actual, ctx)
            init = self.convert(cur, ct, dft) if dft.base != "type" else cur
        else:
            cur, ct = self.expr(actual, ctx)
            init = self.convert(cur, ct, dft) if dft.base != "type" else cur
        ctx.pre.append(f"final {jt} {h} = new {jt.replace('<', '<').split('<')[0]}{'<>' if '<' in jt else ''}({init});")
        if is_lvalue:
            back = self.assign_code(actual, f"{h}.v", dft, ctx, into_post=True)
            ctx.post.extend(back)
        return h

    def is_lvalue(self, e, ctx):
        if isinstance(e, Name):
            ent = ctx.scope.lookup(e.id)
            return ent is not None and ent.kind == "var" and "parameter" not in ent.decl.attrs
        if isinstance(e, Comp):
            return self.is_lvalue(e.base, ctx) if not isinstance(e.base, Call) else True
        if isinstance(e, Call) and isinstance(e.base, (Name, Comp)):
            if isinstance(e.base, Name):
                ent = ctx.scope.lookup(e.base.id)
                return ent is not None and ent.kind == "var"
            return True
        return False

    def array_arg(self, actual, d, dft, ctx, p, a):
        """An array actual for an array dummy: the array itself, or a copy of a section copied back."""
        alloc_dummy = "allocatable" in d.attrs or "pointer" in d.attrs
        written = a in self.written.get(id(p), set())
        if isinstance(actual, Name):
            ent = ctx.scope.lookup(actual.id)
            if ent is not None and ent.kind == "var":
                v = self.var_of(ent, ctx)
                if alloc_dummy:
                    if not v.alloc:
                        raise GenError(f"a plain array passed to the allocatable dummy {a} of {p.name}")
                    return v.value()
                return v.data()
        if isinstance(actual, Comp):
            info = self.comp_array(actual, ctx)
            if info is not None:
                code, ft, alloc = info
                if alloc_dummy:
                    return code
                return code + ".a" if alloc else code
        # a section or an expression: a temporary
        tmp, tft = self.materialize(actual, ctx)
        if written and self.is_lvalue(actual, ctx):
            ctx.post.extend(self.copy_back(actual, tmp, tft, ctx))
        return tmp

    def comp_array(self, e, ctx):
        """(java, ft, alloc) for x%y when y is an array component."""
        base, bt = self.expr(e.base, ctx)
        if bt.base != "type" or bt.rank:
            return None
        _, td = self.prog.types[bt.dtype]
        c = next((c for c in td.components if c.name == e.field), None)
        if c is None or c.rank == 0:
            return None
        alloc = "allocatable" in c.attrs or "pointer" in c.attrs
        return f"{base}.{jname(c.name)}", decl_type(c), alloc

    # --------------------------------------------------------------- allocation
    def s_allocate(self, s, ctx):
        out = []
        for item in s.items:
            if isinstance(item, Call):
                v = self.lvalue_container(item.base, ctx)
                code, ft, alloc = v
                exts, lbs = [], []
                for a in item.args:
                    r = a.value
                    if isinstance(r, Range):
                        lo, tl = self.expr(r.lo, ctx) if r.lo is not None else ("1", INT)
                        hi, th = self.expr(r.hi, ctx)
                        lo, hi = self.convert(lo, tl, INT), self.convert(hi, th, INT)
                        lbs.append(lo)
                        exts.append(f"Math.max(0, ({hi}) - ({lo}) + 1)")
                    else:
                        hi, th = self.expr(r, ctx)
                        lbs.append("1")
                        exts.append(f"Math.max(0, {self.convert(hi, th, INT)})")
                if not alloc:
                    raise GenError("allocate of a non-allocatable array")
                if code.endswith(".a"):
                    code = code[:-2]
                out.append(f"{code} = F.allocated({code}, {self.new_array(ft.with_rank(len(exts)), exts)}, new int[]{{{', '.join(lbs)}}});")
            else:
                code, ft, alloc = self.lvalue_container(item, ctx)
                if ft.base != "type" or ft.rank:
                    raise GenError("allocate of a scalar that is not a derived type")
                out.append(f"{code} = new {self.type_class(ft.dtype)}();")
        if s.stat is not None:
            out.extend(self.assign_code(s.stat, "0", INT, ctx))
        return out

    def lvalue_container(self, e, ctx):
        """(java, ft, alloc) of a whole variable or component, as a container (Alloc, reference)."""
        if isinstance(e, Name):
            v = self.var(e.id, ctx)
            return v.value(), v.ft, v.alloc
        if isinstance(e, Comp):
            base, bt = self.expr(e.base, ctx)
            _, td = self.prog.types[bt.dtype]
            c = next(c for c in td.components if c.name == e.field)
            alloc = c.rank > 0 and ("allocatable" in c.attrs or "pointer" in c.attrs)
            return f"{base}.{jname(c.name)}", decl_type(c), alloc
        raise GenError(f"cannot allocate {e}")

    def s_deallocate(self, s, ctx):
        out = []
        for item in s.items:
            code, ft, alloc = self.lvalue_container(item, ctx)
            if alloc:
                out.append(f"if ({code} != null) {code}.a = null;")
            else:
                out.append(f"{code} = null;")
        if s.stat is not None:
            out.extend(self.assign_code(s.stat, "0", INT, ctx))
        return out

    def s_nullify(self, s, ctx):
        out = []
        for item in s.items:
            code, ft, alloc = self.lvalue_container(item, ctx)
            out.append(f"{code} = null;")
        return out

    def s_ptrassign(self, s, ctx):
        code, ft, alloc = self.lvalue_container(s.lhs, ctx)
        rhs = s.rhs
        if isinstance(rhs, Call) and isinstance(rhs.base, Name) and rhs.base.id == "null":
            return [f"{code} = null;"]
        if isinstance(rhs, (Name, Comp)):
            rcode, rft, ralloc = self.lvalue_container(rhs, ctx)
            if alloc and not ralloc:
                # a pointer to a plain array with the target attribute
                v = self.var(rhs.id, ctx) if isinstance(rhs, Name) else None
                lbs = ", ".join(v.lbs) if v is not None and v.lbs else ", ".join("1" for _ in range(rft.rank))
                return [f"{code} = new Alloc<>({rcode}, new int[]{{{lbs}}});"]
            return [f"{code} = {rcode};"]
        raise GenError("pointer assignment to something other than a variable")

    # --------------------------------------------------------------- output
    def s_write(self, s, ctx):
        items = []
        for it in s.items:
            if isinstance(it, ImpliedDo):
                tmp = self.implied_list(it, ctx)
                items.append(tmp)
                continue
            v, t = self.expr(it, ctx)
            if t.rank:
                tmp, tt = self.materialize(it, ctx) if not self.is_whole_array(it, ctx) else (self.whole_array_data(it, ctx), t)
                items.append(tmp)
            else:
                items.append(v if t.base not in ("integer", "real", "logical") else v)
        fmt = "null"
        if s.fmt is not None:
            f, ft = self.expr(s.fmt, ctx)
            fmt = f if ft.base == "character" else "null"
        text = f"F.format({fmt}, new Object[]{{{', '.join(items)}}})"
        if s.unit is None:
            return [f"core.io.write(6, {text});"]
        u, ut = self.expr(s.unit, ctx)
        if ut.base == "character":
            # an internal write: the record goes into the variable
            return self.assign_code(s.unit, text, CHAR, ctx)
        return [f"core.io.write({u}, {text});"]

    def s_read(self, s, ctx):
        raise GenError("read statements are not translated")

    def implied_list(self, it, ctx):
        lst = ctx.temp("io")
        ctx.pre.append(f"final java.util.ArrayList<Object> {lst} = new java.util.ArrayList<>();")
        var = self.var(it.var, ctx).value()
        lo, tl = self.expr(it.lo, ctx)
        hi, th = self.expr(it.hi, ctx)
        ctx.pre.append(f"for ({var} = {lo}; {var} <= {hi}; {var}++) {{")
        for x in it.items:
            v, t = self.expr(x, ctx)
            ctx.pre.append(f"    {lst}.add({v});")
        ctx.pre.append("}")
        return f"{lst}.toArray()"

    # --------------------------------------------------------------- where
    def s_where(self, s, ctx):
        shape = self.shape_of(s.mask, ctx)
        counters = [ctx.temp("i") for _ in shape]
        out = []
        ext = [ctx.temp("n") for _ in shape]
        for e, sh in zip(ext, shape):
            out.append(f"final int {e} = {sh};")
        for c, e in zip(counters, ext):
            out.append(f"for (int {c} = 0; {c} < {e}; {c}++) {{")
        mask = self.elem(s.mask, counters, ctx)
        body = self.where_body(s.body, counters, ctx)
        other = self.where_body(s.other, counters, ctx)
        out.append(f"if ({mask[0]}) {{")
        out.extend("    " + x for x in body)
        if other:
            out.append("} else {")
            out.extend("    " + x for x in other)
        out.append("}")
        out.extend("}" for _ in counters)
        return out

    def where_body(self, stmts, counters, ctx):
        out = []
        for s in stmts:
            if s.kind == "where":
                mask = self.elem(s.mask, counters, ctx)
                out.append(f"if ({mask[0]}) {{")
                out.extend("    " + x for x in self.where_body(s.body, counters, ctx))
                if s.other:
                    out.append("} else {")
                    out.extend("    " + x for x in self.where_body(s.other, counters, ctx))
                out.append("}")
                continue
            if s.kind != "assign":
                raise GenError("only assignments inside where")
            rhs, rt = self.elem(s.rhs, counters, ctx)
            lhs, lt = self.elem_lvalue(s.lhs, counters, ctx)
            out.append(f"{lhs} = {self.convert(rhs, rt, lt)};")
        return out

    # --------------------------------------------------------------- assignment
    def s_assign(self, s, ctx):
        lt = self.arg_type(s.lhs, ctx)
        rt = self.arg_type(s.rhs, ctx)
        if lt is None:
            raise GenError("cannot type the left side")
        if lt.rank == 0:
            if lt.base == "type" and (rt is None or rt.base != "type" or rt.dtype != lt.dtype):
                return self.defined_assignment(s, lt, rt, ctx)
            if lt.base == "type" and rt is not None and rt.base == "type" and isinstance(s.lhs, Name):
                ent = ctx.scope.lookup(s.lhs.id)
            v, t = self.expr(s.rhs, ctx)
            return self.assign_code(s.lhs, v, t, ctx)
        return self.array_assign(s.lhs, s.rhs, ctx)

    def defined_assignment(self, s, lt, rt, ctx):
        cands = self.prog.assignments
        p = resolve_generic(self.prog, cands, [lt, rt], [None, None])
        if p is None:
            raise GenError(f"no assignment(=) for {lt} = {rt}")
        return [self.call_specific(p, [Arg(None, s.lhs), Arg(None, s.rhs)], ctx) + ";"]

    def assign_code(self, lhs, value, vt, ctx, into_post=False):
        """Statements storing a Java value of type vt into a scalar Fortran lvalue."""
        lt = self.arg_type(lhs, ctx)
        if lt is None:
            raise GenError("cannot type an assignment target")
        if lt.rank:
            raise GenError("array target where a scalar was expected")
        if isinstance(lhs, Call) and isinstance(lhs.base, Name):
            ent = ctx.scope.lookup(lhs.base.id)
            if ent is not None and ent.kind == "var" and decl_type(ent.decl).base == "character" and ent.decl.rank == 0:
                # a substring as the target
                v = self.var_of(ent, ctx)
                r = lhs.args[0].value
                lo, tl = self.expr(r.lo, ctx) if r.lo is not None else ("1", INT)
                hi, th = self.expr(r.hi, ctx) if r.hi is not None else (f"{v.value()}.length()", INT)
                return [f"{v.value()} = F.setSub({v.value()}, {lo}, {hi}, {self.convert(value, vt, CHAR)});"]
        target = self.lvalue(lhs, ctx)
        if lt.base == "type":
            if vt.base == "type":
                return [f"{target}.assign({value});"]
            raise GenError("derived type assigned from another type")
        conv = self.convert(value, vt, lt)
        if lt.base == "character":
            d = self.decl_of(lhs, ctx)
            if d is not None and d.char_len and re.fullmatch(r"\d+", d.char_len):
                conv = f"F.fit({conv}, {d.char_len})"
        return [f"{target} = {conv};"]

    def decl_of(self, e, ctx):
        if isinstance(e, Name):
            ent = ctx.scope.lookup(e.id)
            return ent.decl if ent is not None and ent.kind == "var" else None
        if isinstance(e, Comp):
            bt = self.arg_type(e.base, ctx)
            if bt is not None and bt.base == "type":
                _, td = self.prog.types[bt.dtype]
                return next((c for c in td.components if c.name == e.field), None)
        if isinstance(e, Call):
            return self.decl_of(e.base, ctx)
        return None

    def lvalue(self, e, ctx):
        """A Java lvalue for a scalar Fortran designator."""
        if isinstance(e, Name):
            v = self.var(e.id, ctx)
            if v.ft.rank:
                raise GenError(f"whole array {e.id} as a scalar target")
            return v.value()
        if isinstance(e, Comp):
            base, bt = self.expr(e.base, ctx)
            return f"{base}.{jname(e.field)}"
        if isinstance(e, Call):
            code, ft = self.element(e, ctx)
            return code
        raise GenError(f"not a variable: {e}")

    # ================================================================= expressions
    def arg_type(self, e, ctx):
        """The FT of an expression without generating it."""
        if isinstance(e, Name):
            ent = ctx.scope.lookup(e.id)
            if ent is not None and ent.kind in ("dummyproc",) or ent is not None and ent.kind == "proc" \
                    and not (ent.proc is ctx.proc and ent.proc.kind == "function"):
                return FT("procedure")
        saved = (ctx.pre, ctx.post, ctx.tmp)
        ctx.pre, ctx.post = [], []
        try:
            code, t = self.expr(e, ctx)
            return t
        finally:
            ctx.pre, ctx.post, ctx.tmp = saved

    def expr(self, e, ctx):
        m = getattr(self, "e_" + type(e).__name__, None)
        if m is None:
            raise GenError(f"expression {type(e).__name__} not translated")
        return m(e, ctx)

    def e_Num(self, e, ctx):
        if e.kind == "int":
            return str(e.value), INT
        if e.kind == "single":
            return jdouble(single(e.value)), REAL
        return jdouble(e.value), REAL

    def e_Str(self, e, ctx):
        return jstring(e.value), CHAR

    def e_Logic(self, e, ctx):
        return ("true" if e.value else "false"), LOG

    def e_Cplx(self, e, ctx):
        a, ta = self.expr(e.re, ctx)
        b, tb = self.expr(e.im, ctx)
        return f"new Cx({self.convert(a, ta, REAL)}, {self.convert(b, tb, REAL)})", CPLX

    def e_Paren(self, e, ctx):
        c, t = self.expr(e.x, ctx)
        if c is None:
            return None, t
        return f"({c})", t

    def e_ArrCtor(self, e, ctx):
        ct = self.ctor_type(e, ctx)
        return self.array_ctor(e, ctx, ct), ct

    def array_value(self, e, ctx, ft):
        """Java for an array-valued initializer: a constructor or an array intrinsic such as reshape."""
        if isinstance(e, ArrCtor):
            return self.array_ctor(e, ctx, ft)
        if isinstance(e, Call) and isinstance(e.base, Name) and e.base.id in INTRINSICS:
            import fintr
            code, t = fintr.array_intrinsic(self, e.base.id, e.args, ctx)
            return code
        return None

    def e_Name(self, e, ctx):
        ent = ctx.scope.lookup(e.id)
        if ent is None:
            raise GenError(f"unknown name '{e.id}'")
        if ent.kind == "var":
            v = self.var_of(ent, ctx)
            if v.ft.rank:
                return v.data(), v.ft
            if v.boxed:
                return v.value(), v.ft
            return v.value(), v.ft
        if ent.kind in ("proc", "generic"):
            # a function called without parentheses is not Fortran; this is the result variable
            p = ent.proc if ent.kind == "proc" else None
            if p is not None and p is ctx.proc and p.kind == "function":
                return jname(p.result), self.result_type(p)[0]
            raise GenError(f"procedure {e.id} used as a value")
        if ent.kind == "intrinsic":
            raise GenError(f"intrinsic {e.id} used as a value")
        raise GenError(f"'{e.id}' is a {ent.kind}")

    def e_Comp(self, e, ctx):
        base, bt = self.expr(e.base, ctx)
        if bt.base != "type":
            raise GenError(f"component {e.field} of a {bt}")
        _, td = self.prog.types[bt.dtype]
        c = next((c for c in td.components if c.name == e.field), None)
        if c is None:
            raise GenError(f"type {bt.dtype} has no component {e.field}")
        ft = decl_type(c)
        alloc = c.rank > 0 and ("allocatable" in c.attrs or "pointer" in c.attrs)
        if bt.rank:
            # a component of every element: an array expression
            return None, ft.with_rank(bt.rank + ft.rank)
        code = f"{base}.{jname(c.name)}"
        if alloc:
            code += ".a"
        return code, ft

    def e_Un(self, e, ctx):
        x, t = self.expr(e.x, ctx)
        if t.rank:
            return None, t
        if t.base == "type":
            # a defined unary operator: minus_ff, plus_ff
            cands = [p for p in self.prog.operators.get(e.op, []) if len(p.args) == 1]
            p = resolve_generic(self.prog, cands, [t], [None])
            if p is None:
                raise GenError(f"no unary operator {e.op} for {t}")
            return self.call_specific(p, [Arg(None, e.x)], ctx), self.result_type(p)[0]
        if e.op == "not":
            return f"!{self.wrap(x)}", LOG
        if e.op == "+":
            return x, t
        if t.base == "complex":
            return f"Cx.neg({x})", t
        return f"(-{self.wrap(x)})", t

    @staticmethod
    def wrap(x):
        return x if re.fullmatch(r"[\w.\[\]]+", x) else f"({x})"

    def e_Bin(self, e, ctx):
        a, ta = self.expr(e.a, ctx)
        b, tb = self.expr(e.b, ctx)
        if ta.rank or tb.rank:
            return None, self.binary_type(e.op, ta, tb)
        if ta.base == "type" or tb.base == "type":
            return self.defined_op(e.op, e.a, e.b, ta, tb, ctx)
        return self.binary(e.op, a, ta, b, tb)

    def binary_type(self, op, ta, tb):
        rank = max(ta.rank, tb.rank)
        if op in ("and", "or", "eqv", "neqv", "==", "/=", "<", "<=", ">", ">="):
            return LOG.with_rank(rank)
        if op == "//":
            return CHAR.with_rank(rank)
        if ta.base == "type" or tb.base == "type":
            return FT("type", rank, ta.dtype or tb.dtype)
        base = max(ta.base, tb.base, key=lambda b: NUMERIC_ORDER.get(b, -1))
        return FT(base, rank)

    def binary(self, op, a, ta, b, tb):
        if op in ("and", "or"):
            return f"({a} {'&&' if op == 'and' else '||'} {b})", LOG
        if op in ("eqv", "neqv"):
            return f"({a} {'==' if op == 'eqv' else '!='} {b})", LOG
        if op in ("==", "/=", "<", "<=", ">", ">="):
            return self.compare(op, a, ta, b, tb), LOG
        if op == "//":
            return f"({a} + {b})", CHAR
        if op == "**":
            return self.power(a, ta, b, tb)
        base = max(ta.base, tb.base, key=lambda x: NUMERIC_ORDER.get(x, -1))
        if base == "complex":
            fn = {"+": "add", "-": "sub", "*": "mul", "/": "div"}[op]
            ca = a if ta.base == "complex" else self.convert(a, ta, REAL)
            cb = b if tb.base == "complex" else self.convert(b, tb, REAL)
            return f"Cx.{fn}({ca}, {cb})", CPLX
        if base == "real":
            a = self.convert(a, ta, REAL)
            b = self.convert(b, tb, REAL)
            return f"({a} {op} {b})", REAL
        if base == "integer":
            return f"({a} {op} {b})", INT
        raise GenError(f"operator {op} on {ta} and {tb}")

    def power(self, a, ta, b, tb):
        if tb.base == "integer":
            if ta.base == "integer":
                return f"F.ipow({a}, {b})", INT
            if ta.base == "real":
                return f"F.powi({a}, {b})", REAL
            if ta.base == "complex":
                return f"Cx.powi({a}, {b})", CPLX
        if ta.base == "complex" or tb.base == "complex":
            return f"Cx.pow({self.convert(a, ta, CPLX) if ta.base != 'real' else a}, {b if tb.base != 'integer' else self.convert(b, tb, REAL)})", CPLX
        return f"Math.pow({self.convert(a, ta, REAL)}, {self.convert(b, tb, REAL)})", REAL

    def defined_op(self, op, ea, eb, ta, tb, ctx):
        cands = self.prog.operators.get(op, [])
        p = resolve_generic(self.prog, cands, [ta, tb], [None, None])
        if p is None:
            raise GenError(f"no operator {op} for {ta} and {tb}")
        rt, _ = self.result_type(p)
        return self.call_specific(p, [Arg(None, ea), Arg(None, eb)], ctx), rt

    def convert(self, code, frm, to):
        """Fortran's conversion on assignment and argument association between intrinsic types."""
        if code is None:
            raise GenError("array value where a scalar is needed")
        if frm.base == to.base or to.base in ("type", "procedure", "void"):
            return code
        if to.base == "real":
            if frm.base == "integer":
                return f"(double) {self.wrap(code)}"
            if frm.base == "complex":
                return f"{self.wrap(code)}.re"
        if to.base == "complex":
            if frm.base in ("real", "integer"):
                return f"Cx.of({code})"
        if to.base == "integer":
            if frm.base == "real":
                return f"(int) {self.wrap(code)}"
            if frm.base == "complex":
                return f"(int) {self.wrap(code)}.re"
        if to.base == "character" and frm.base == "character":
            return code
        raise GenError(f"cannot convert {frm} to {to}")

    # --------------------------------------------------------------- calls and references
    def e_Call(self, e, ctx):
        base = e.base
        if isinstance(base, Comp):
            info = self.comp_array(base, ctx)
            if info is None:
                c, ct = self.expr(base, ctx)
                if ct.base == "character" and ct.rank == 0 and e.args and isinstance(e.args[0].value, Range):
                    r = e.args[0].value
                    lo, tl = self.expr(r.lo, ctx) if r.lo is not None else ("1", INT)
                    hi, th = self.expr(r.hi, ctx) if r.hi is not None else (f"{c}.length()", INT)
                    return f"F.sub({c}, {lo}, {hi})", CHAR
                raise GenError("call on a component that is not an array")
            code, ft, alloc = info
            return self.subscript(code + (".a" if alloc else ""), ft, None, e.args, ctx,
                                  lbs=[f"{code}.lb[{k}]" for k in range(ft.rank)] if alloc else ["1"] * ft.rank)
        if not isinstance(base, Name):
            raise GenError("call on an expression")
        name = base.id
        ent = ctx.scope.lookup(name)
        if ent is None:
            raise GenError(f"unknown function or array '{name}'")
        if ent.kind == "var":
            v = self.var_of(ent, ctx)
            if v.ft.base == "character" and v.ft.rank == 0:
                r = e.args[0].value
                lo, tl = self.expr(r.lo, ctx) if r.lo is not None else ("1", INT)
                hi, th = self.expr(r.hi, ctx) if r.hi is not None else (f"{v.value()}.length()", INT)
                return f"F.sub({v.value()}, {lo}, {hi})", CHAR
            if v.ft.rank == 0:
                raise GenError(f"scalar {name} called with arguments")
            return self.subscript(v.data(), v.ft, v, e.args, ctx)
        if ent.kind == "dummyproc":
            b = ent.proc
            d = b.decls.get(b.result)
            return self.call(ent, name, e.args, ctx), decl_type(d)
        if ent.kind == "type":
            return self.constructor(ent, e.args, ctx)
        if ent.kind == "intrinsic":
            return self.intrinsic(name, e.args, ctx)
        if ent.kind in ("proc", "generic"):
            actual = [self.arg_type(a.value, ctx) for a in e.args]
            keys = [a.key for a in e.args]
            if ent.kind == "generic":
                p = resolve_generic(self.prog, ent.specifics, actual, keys)
                if p is None:
                    p = self.resolve_elemental(ent.specifics, actual, keys)
                    if p is not None:
                        rt, _ = self.result_type(p)
                        return None, rt.with_rank(max(t.rank for t in actual if t is not None))
                if p is None:
                    raise GenError(f"no specific of {name} for {actual}")
            else:
                p = ent.proc
                if "elemental" in p.prefixes and any(t is not None and t.rank for t in actual):
                    rt, _ = self.result_type(p)
                    return None, rt.with_rank(max(t.rank for t in actual if t is not None))
            if p.kind != "function":
                raise GenError(f"subroutine {name} used as a function")
            rt, ralloc = self.result_type(p)
            code = self.call_specific(p, e.args, ctx)
            if ralloc:
                code += ".a"
            return code, rt
        raise GenError(f"{name} cannot be called")

    def constructor(self, ent, args, ctx):
        td = ent.typedef
        parts = []
        for a, c in zip(args, td.components):
            v, t = self.expr(a.value, ctx)
            if c.rank > 0:
                v = f"F.copyOf({v})"            # the constructor takes the values
            elif t.base == "type" and not v.startswith("new "):
                v += ".copy()"
            parts.append(self.convert(v, t, decl_type(c)) if c.rank == 0 else v)
        if len(args) != len(td.components):
            raise GenError(f"structure constructor {td.name} with {len(args)} values for {len(td.components)} components")
        return f"new {self.type_class(td.name)}({', '.join(parts)})", FT("type", 0, td.name)

    def subscript(self, data, ft, v, args, ctx, lbs=None):
        """An element or a section of an array (a range or a vector of indices in a dimension)."""
        sectioned = 0
        for a in args:
            if isinstance(a.value, Range):
                sectioned += 1
            else:
                at = self.arg_type(a.value, ctx)
                if at is not None and at.rank > 0:
                    sectioned += 1
        if sectioned:
            return None, ft.with_rank(sectioned)
        idx = []
        for k, a in enumerate(args):
            i, t = self.expr(a.value, ctx)
            i = self.convert(i, t, INT)
            lb = (v.lb(k) if v is not None else (lbs[k] if lbs else "1"))
            idx.append(self.index(i, lb))
        return data + "".join(f"[{x}]" for x in idx), ft.elem()

    @staticmethod
    def index(i, lb):
        if lb == "1":
            if re.fullmatch(r"\d+", i):
                return str(int(i) - 1)
            return f"{i} - 1"
        if lb == "0":
            return i
        if re.fullmatch(r"-?\d+", lb) and re.fullmatch(r"-?\d+", i):
            return str(int(i) - int(lb))
        return f"{i} - {lb}" if re.fullmatch(r"[\w.\[\]]+", lb) else f"{i} - ({lb})"

    def element(self, e, ctx):
        code, ft = self.e_Call(e, ctx)
        if code is None:
            raise GenError("a section where an element was expected")
        return code, ft

    # --------------------------------------------------------------- intrinsics
    def intrinsic(self, name, args, ctx):
        import fintr
        return fintr.intrinsic(self, name, args, ctx)

    # ================================================================= arrays
    def is_whole_array(self, e, ctx):
        if isinstance(e, Name):
            ent = ctx.scope.lookup(e.id)
            return ent is not None and ent.kind == "var" and ent.decl.rank > 0
        return False

    def whole_array_data(self, e, ctx):
        return self.var(e.id, ctx).data()

    def view(self, e, ctx):
        """For an array designator: (java data, elem FT, [dims]) where a dim is ('s', idx) or ('r', lo, hi, step),
        with lower bounds already applied: indices are Java (0-based) indices into data."""
        if isinstance(e, Name):
            v = self.var(e.id, ctx)
            data = v.data()
            dims = []
            for k in range(v.ft.rank):
                dims.append(("r", "0", f"{self.length(data, k)} - 1", "1"))
            return data, v.ft.elem(), dims
        if isinstance(e, Comp):
            info = self.comp_array(e, ctx)
            if info is not None:
                code, ft, alloc = info
                data = code + (".a" if alloc else "")
                return data, ft.elem(), [("r", "0", f"{self.length(data, k)} - 1", "1") for k in range(ft.rank)]
            return None
        if isinstance(e, Call):
            base = e.base
            if isinstance(base, Name):
                ent = ctx.scope.lookup(base.id)
                if ent is None or ent.kind != "var":
                    return None
                v = self.var_of(ent, ctx)
                if v.ft.rank == 0:
                    return None
                data = v.data()
                lbf = v.lb
                ft = v.ft
            elif isinstance(base, Comp):
                info = self.comp_array(base, ctx)
                if info is None:
                    return None
                code, ft, alloc = info
                data = code + (".a" if alloc else "")
                lbf = (lambda k, code=code: f"{code}.lb[{k}]") if alloc else (lambda k: "1")
            else:
                return None
            dims = []
            for k, a in enumerate(e.args):
                lb = lbf(k)
                r = a.value
                if isinstance(r, Range):
                    lo = self.int_expr(r.lo, ctx) if r.lo is not None else None
                    hi = self.int_expr(r.hi, ctx) if r.hi is not None else None
                    st = self.int_expr(r.step, ctx) if r.step is not None else "1"
                    jlo = self.index(lo, lb) if lo is not None else "0"
                    jhi = self.index(hi, lb) if hi is not None else f"{self.length(data, k)} - 1"
                    dims.append(("r", jlo, jhi, st))
                else:
                    at = self.arg_type(r, ctx)
                    if at is not None and at.rank > 0:
                        # a vector subscript: the elements at the indices the array lists
                        if self.is_whole_array(r, ctx):
                            vec = self.whole_array_data(r, ctx)
                        else:
                            vec, vt = self.materialize(r, ctx)
                        dims.append(("v", vec, lb))
                        continue
                    i = self.int_expr(r, ctx)
                    dims.append(("s", self.index(i, lb)))
            return data, ft.elem(), dims
        return None

    def int_expr(self, e, ctx):
        c, t = self.expr(e, ctx)
        c = self.convert(c, t, INT)
        if re.fullmatch(r"[\w.\[\]]+|\d+", c):
            return c
        tmp = ctx.temp("ix")
        ctx.pre.append(f"final int {tmp} = {c};")
        return tmp

    @staticmethod
    def length(data, k):
        if k == 0:
            return f"{data}.length"
        return f"F.len{k + 1}({data})"

    def shape_of(self, e, ctx):
        """Java expressions for the extents of an array expression."""
        t = self.arg_type(e, ctx)
        if t is None or t.rank == 0:
            return []
        vw = self.view(e, ctx)
        if vw is not None:
            out = []
            for d in vw[2]:
                if d[0] == "r":
                    _, lo, hi, st = d
                    out.append(f"F.extent({lo}, {hi}, {st})" if st != "1" else f"Math.max(0, ({hi}) - ({lo}) + 1)")
                elif d[0] == "v":
                    out.append(f"{d[1]}.length")
            return out
        if isinstance(e, Paren):
            return self.shape_of(e.x, ctx)
        if isinstance(e, Un):
            return self.shape_of(e.x, ctx)
        if isinstance(e, Bin):
            ta = self.arg_type(e.a, ctx)
            return self.shape_of(e.a if ta.rank else e.b, ctx)
        if isinstance(e, Comp):
            bt = self.arg_type(e.base, ctx)
            if bt.rank:
                return self.shape_of(e.base, ctx)
        if isinstance(e, Call) and isinstance(e.base, Name):
            ent = ctx.scope.lookup(e.base.id)
            if ent is not None and (ent.kind == "intrinsic" and e.base.id in fintr_elemental()
                                    or ent.kind in ("proc", "generic") and self.call_is_elemental(ent, e, ctx)):
                for a in e.args:
                    at = self.arg_type(a.value, ctx)
                    if at is not None and at.rank:
                        return self.shape_of(a.value, ctx)
        # anything else is computed into a temporary first
        tmp, tt = self.materialize(e, ctx)
        return [self.length(tmp, k) for k in range(tt.rank)]

    def call_is_elemental(self, ent, e, ctx):
        actual = [self.arg_type(a.value, ctx) for a in e.args]
        keys = [a.key for a in e.args]
        if ent.kind == "proc":
            return "elemental" in ent.proc.prefixes and any(t is not None and t.rank for t in actual)
        p = resolve_generic(self.prog, ent.specifics, actual, keys)
        return p is None and self.resolve_elemental(ent.specifics, actual, keys) is not None

    def elem(self, e, counters, ctx):
        """(java, FT) for one element of an array expression, at the loop counters."""
        t = self.arg_type(e, ctx)
        if t is not None and t.rank == 0:
            return self.hoist(e, ctx)
        vw = self.view(e, ctx)
        if vw is not None:
            data, et, dims = vw
            idx = []
            k = 0
            for d in dims:
                if d[0] == "s":
                    idx.append(d[1])
                elif d[0] == "v":
                    c = counters[k]
                    k += 1
                    idx.append(self.index(f"{d[1]}[{c}]", d[2]))
                else:
                    _, lo, hi, st = d
                    c = counters[k]
                    k += 1
                    if lo == "0" and st == "1":
                        idx.append(c)
                    elif st == "1":
                        idx.append(f"{lo} + {c}")
                    else:
                        idx.append(f"{lo} + {c} * {st}")
            return data + "".join(f"[{x}]" for x in idx), et
        if isinstance(e, Paren):
            c, ct = self.elem(e.x, counters, ctx)
            return f"({c})", ct
        if isinstance(e, Un):
            c, ct = self.elem(e.x, counters, ctx)
            if e.op == "not":
                return f"!{self.wrap(c)}", LOG
            if ct.base == "type":
                cands = [p for p in self.prog.operators.get(e.op, []) if len(p.args) == 1]
                p = resolve_generic(self.prog, cands, [ct], [None])
                return f"{self.target(p, ctx)}({c})", self.result_type(p)[0]
            if e.op == "+":
                return c, ct
            return (f"Cx.neg({c})" if ct.base == "complex" else f"(-{self.wrap(c)})"), ct
        if isinstance(e, Bin):
            a, ta = self.elem(e.a, counters, ctx)
            b, tb = self.elem(e.b, counters, ctx)
            if ta.base == "type" or tb.base == "type":
                cands = self.prog.operators.get(e.op, [])
                p = resolve_generic(self.prog, cands, [ta, tb], [None, None])
                if p is None:
                    raise GenError(f"no elemental operator {e.op} for {ta}, {tb}")
                rt, _ = self.result_type(p)
                return f"{self.target(p, ctx)}({a}, {b})", rt
            return self.binary(e.op, a, ta, b, tb)
        if isinstance(e, Comp):
            c, ct = self.elem(e.base, counters, ctx)
            _, td = self.prog.types[ct.dtype]
            comp = next(x for x in td.components if x.name == e.field)
            return f"{c}.{jname(e.field)}", decl_type(comp)
        if isinstance(e, Call) and isinstance(e.base, Name):
            ent = ctx.scope.lookup(e.base.id)
            if ent is not None and ent.kind == "intrinsic" and e.base.id in fintr_elemental():
                import fintr
                return fintr.elemental(self, e.base.id, e.args, counters, ctx)
            if ent is not None and ent.kind in ("proc", "generic") and self.call_is_elemental(ent, e, ctx):
                actual = [self.arg_type(a.value, ctx) for a in e.args]
                keys = [a.key for a in e.args]
                p = ent.proc if ent.kind == "proc" else self.resolve_elemental(ent.specifics, actual, keys)
                parts = []
                for dummy, actual_e in zip(p.args, self.bind(p, e.args)):
                    if actual_e is None:
                        parts.append("null")     # an optional argument not given
                        continue
                    c, ct = self.elem(actual_e, counters, ctx)
                    d = p.decls.get(dummy)
                    parts.append(self.convert(c, ct, decl_type(d)) if d is not None and d.rank == 0 else c)
                rt, _ = self.result_type(p)
                return f"{self.target(p, ctx)}({', '.join(parts)})", rt
        # computed into a temporary, then read element by element
        tmp, tt = self.materialize(e, ctx)
        return tmp + "".join(f"[{c}]" for c in counters), tt.elem()

    def hoist(self, e, ctx):
        c, t = self.expr(e, ctx)
        if re.fullmatch(r"[\w.]+|-?\d+(\.\d*)?(E-?\d+)?|\"[^\"]*\"", c or "") or t.base == "type":
            return c, t
        tmp = ctx.temp("s")
        ctx.pre.append(f"final {self.jtype(t)} {tmp} = {c};")
        return tmp, t

    def elem_lvalue(self, e, counters, ctx):
        vw = self.view(e, ctx)
        if vw is None:
            raise GenError("array target not understood")
        c, t = self.elem(e, counters, ctx)
        return c, t

    def materialize(self, e, ctx):
        """Computes an array expression into a fresh Java array; returns (name, FT)."""
        t = self.arg_type(e, ctx)
        if isinstance(e, ArrCtor):
            tmp = ctx.temp("a")
            code = self.array_ctor(e, ctx, None)
            ct = self.ctor_type(e, ctx)
            ctx.pre.append(f"final {self.jtype(ct)} {tmp} = {code};")
            return tmp, ct
        if isinstance(e, Call) and isinstance(e.base, Name):
            ent = ctx.scope.lookup(e.base.id)
            if ent is not None and ent.kind == "intrinsic" and e.base.id not in fintr_elemental():
                import fintr
                code, ct = fintr.array_intrinsic(self, e.base.id, e.args, ctx)
                tmp = ctx.temp("a")
                ctx.pre.append(f"final {self.jtype(ct)} {tmp} = {code};")
                return tmp, ct
            if ent is not None and ent.kind in ("proc", "generic") and not self.call_is_elemental(ent, e, ctx):
                code, ct = self.expr(e, ctx)
                tmp = ctx.temp("a")
                ctx.pre.append(f"final {self.jtype(ct)} {tmp} = {code};")
                return tmp, ct
        vw = self.view(e, ctx)
        shape = self.shape_of(e, ctx)
        tmp = ctx.temp("a")
        ext = [ctx.temp("n") for _ in shape]
        for x, sh in zip(ext, shape):
            ctx.pre.append(f"final int {x} = {sh};")
        ctx.pre.append(f"final {self.jtype(t)} {tmp} = {self.new_array(t, ext)};")
        counters = [ctx.temp("i") for _ in shape]
        saved = ctx.pre
        ctx.pre = []
        c, ct = self.elem(e, counters, ctx)
        inner = ctx.pre
        ctx.pre = saved
        loop = []
        for cnt, x in zip(counters, ext):
            loop.append(f"for (int {cnt} = 0; {cnt} < {x}; {cnt}++) {{")
        loop.extend("    " + x for x in inner)
        loop.append(f"    {tmp}{''.join('[' + x + ']' for x in counters)} = {self.convert(c, ct, t.elem())};")
        loop.extend("}" for _ in counters)
        ctx.pre.extend(loop)
        return tmp, t

    def copy_back(self, actual, tmp, tft, ctx):
        shape = [self.length(tmp, k) for k in range(tft.rank)]
        counters = [ctx.temp("i") for _ in shape]
        saved = ctx.pre
        ctx.pre = []
        lhs, lt = self.elem_lvalue(actual, counters, ctx)
        inner = ctx.pre
        ctx.pre = saved
        out = []
        for c, sh in zip(counters, shape):
            out.append(f"for (int {c} = 0; {c} < {sh}; {c}++) {{")
        out.extend("    " + x for x in inner)
        out.append(f"    {lhs} = {tmp}{''.join('[' + c + ']' for c in counters)};")
        out.extend("}" for _ in counters)
        return out

    def array_assign(self, lhs, rhs, ctx):
        lt = self.arg_type(lhs, ctx)
        rt = self.arg_type(rhs, ctx)
        out = []
        # an allocatable target takes the shape of the value (Fortran 2003's reallocation)
        realloc = None
        if isinstance(lhs, Name) and rt is not None and rt.rank:
            v = self.var(lhs.id, ctx)
            if v.alloc:
                realloc = v
        call_value = rt is not None and rt.rank and self.array_valued_call(rhs, ctx)
        if lhs_aliases(lhs, rhs) or call_value or (rt is not None and rt.rank and not self.simple_array_expr(rhs, ctx)):
            tmp, tt = self.materialize(rhs, ctx)
            src = Name(tmp)
            src_code = tmp
        else:
            src = rhs
            src_code = None
        if realloc is not None:
            shape = [self.length(src_code, k) for k in range(rt.rank)] if src_code else self.shape_of(rhs, ctx)
            out.append(f"if ({realloc.value()}.a == null || !F.sameShape({realloc.value()}.a, {', '.join(shape)})) "
                       f"{realloc.value()} = F.allocated({realloc.value()}, {self.new_array(realloc.ft, shape)}, "
                       f"new int[]{{{', '.join('1' for _ in shape)}}});")
        shape = self.shape_of(lhs, ctx)
        if call_value and realloc is None:
            # a function's result may not have the target's shape (Fortran leaves that undefined, and gfortran
            # copies past the end of the shorter): what is defined is the common extent
            shape = [f"Math.min({sh}, {self.length(src_code, k)})" for k, sh in enumerate(shape)]
        counters = [ctx.temp("i") for _ in shape]
        ext = [ctx.temp("n") for _ in shape]
        for x, sh in zip(ext, shape):
            out.append(f"final int {x} = {sh};")
        saved = ctx.pre
        ctx.pre = []
        if src_code is not None:
            val, vt = src_code + "".join(f"[{c}]" for c in counters), rt.elem()
        else:
            val, vt = self.elem(rhs, counters, ctx)
        hoisted = [x for x in ctx.pre]
        ctx.pre = []
        tgt, tt = self.elem_lvalue(lhs, counters, ctx)
        inner = ctx.pre
        ctx.pre = saved
        # hoisted scalars (a scalar value, a section's bounds) go before the loops
        out[:0] = []
        out = hoisted + out
        for c, x in zip(counters, ext):
            out.append(f"for (int {c} = 0; {c} < {x}; {c}++) {{")
        out.extend("    " + x for x in inner)
        if tt.base == "type":
            if vt.base == "type":
                out.append(f"    {tgt}.assign({val});")
            else:
                p = resolve_generic(self.prog, self.prog.assignments, [tt, vt], [None, None])
                if p is None:
                    raise GenError(f"no assignment(=) for {tt} = {vt}")
                out.append(f"    {self.target(p, ctx)}({tgt}, {val});")
        else:
            out.append(f"    {tgt} = {self.convert(val, vt, tt)};")
        out.extend("}" for _ in counters)
        return out

    def simple_array_expr(self, e, ctx):
        """True when the value can be read element by element without a temporary."""
        return True

    def array_valued_call(self, e, ctx):
        """A call of a function that is not elemental: its value is a fresh array, of its own size."""
        if not (isinstance(e, Call) and isinstance(e.base, Name)):
            return False
        ent = ctx.scope.lookup(e.base.id)
        return ent is not None and ent.kind in ("proc", "generic") and not self.call_is_elemental(ent, e, ctx)

    def ctor_type(self, e, ctx):
        t = None
        for it in e.items:
            if isinstance(it, ImpliedDo):
                it_t = self.arg_type(it.items[0], ctx) if not isinstance(it.items[0], ImpliedDo) else None
                if it_t is None:
                    continue
                cand = it_t.elem()
            else:
                cand = self.arg_type(it, ctx).elem()
            if t is None or NUMERIC_ORDER.get(cand.base, -1) > NUMERIC_ORDER.get(t.base, -1):
                t = cand
        return (t or INT).with_rank(1)

    def array_ctor(self, e, ctx, ft):
        """Java for (/ ... /): an array of the target's element type."""
        et = ft.elem() if ft is not None else self.ctor_type(e, ctx).elem()
        simple = all(not isinstance(it, ImpliedDo) and self.arg_type(it, ctx).rank == 0 for it in e.items)
        if simple:
            vals = []
            for it in e.items:
                v, t = self.expr(it, ctx)
                vals.append(self.convert(v, t, et))
            return f"new {self.jelem(et)}[]{{{', '.join(vals)}}}"
        lst = ctx.temp("lst")
        ctx.pre.append(f"final java.util.ArrayList<Object> {lst} = new java.util.ArrayList<>();")
        self.ctor_items(e.items, lst, et, ctx)
        return f"F.to{self.jelem(et).capitalize() if et.base != 'type' else 'Objects'}Array({lst})" if et.base != "type" else \
            f"{lst}.toArray(new {self.jelem(et)}[0])"

    def ctor_items(self, items, lst, et, ctx):
        for it in items:
            if isinstance(it, ImpliedDo):
                var = self.var(it.var, ctx).value()
                lo, tl = self.expr(it.lo, ctx)
                hi, th = self.expr(it.hi, ctx)
                ctx.pre.append(f"for ({var} = {lo}; {var} <= {hi}; {var}++) {{")
                saved = ctx.pre
                ctx.pre = []
                self.ctor_items(it.items, lst, et, ctx)
                inner = ctx.pre
                ctx.pre = saved
                ctx.pre.extend("    " + x for x in inner)
                ctx.pre.append("}")
            else:
                t = self.arg_type(it, ctx)
                if t.rank:
                    tmp, tt = self.materialize(it, ctx) if not self.is_whole_array(it, ctx) else (self.whole_array_data(it, ctx), t)
                    ctx.pre.append(f"F.addAll({lst}, {tmp});")
                else:
                    v, vt = self.expr(it, ctx)
                    ctx.pre.append(f"{lst}.add({self.convert(v, vt, et)});")


def lhs_aliases(lhs, rhs):
    """Whether the variable assigned also appears in the value (a temporary keeps Fortran's semantics)."""
    n = Gen.base_name(lhs)
    found = []

    def walk(e):
        if isinstance(e, Name):
            if e.id == n:
                found.append(e)
        elif isinstance(e, (list, tuple)):
            for x in e:
                walk(x)
        elif hasattr(e, "__slots__"):
            for k in e.__slots__:
                walk(getattr(e, k))
    walk(rhs)
    return bool(found)


def fintr_elemental():
    import fintr
    return fintr.ELEMENTAL


class ModuleOnlyScope:
    """The scope of a module's specification part (initializers of variables and constants)."""

    def __init__(self, prog, mod):
        self.prog = prog
        self.mod = mod
        self.scope = prog.module_scope(mod)

    def lookup(self, name):
        e = self.scope.get(name)
        if e is not None:
            return e
        if name in INTRINSICS:
            return Entity("intrinsic", name)
        return None

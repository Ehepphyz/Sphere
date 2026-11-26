"""Fortran intrinsic procedures in Java: elemental ones, inquiries, reductions, array-valued ones."""
import re

from fsrc import Name, Num, Arg, Range
from fsem import GenError, FT, INT, REAL, CPLX, LOG, CHAR, NUMERIC_ORDER, decl_type

ELEMENTAL = set("""abs sqrt log log10 exp sin cos tan asin acos atan atan2 sinh cosh tanh real dble aimag conjg cmplx
dcmplx int nint floor ceiling sign mod modulo max min iand ior ieor not ishft ibset ibclr btest ibits scale aint anint
dabs dsqrt dlog dexp dimag dconjg dreal float dfloat sngl merge len_trim adjustl adjustr index achar char iachar ichar
exponent fraction""".split())




def wrap(x):
    return x if re.fullmatch(r"[\w.\[\]]+", x) else f"({x})"


def positional(args, names):
    """The arguments by intrinsic dummy name, positional or keyword."""
    out = {}
    pos = 0
    for a in args:
        if a.key is None:
            out[names[pos]] = a.value
            pos += 1
        else:
            out[a.key] = a.value
    return out


def apply(gen, name, vals, keys=None):
    """A scalar intrinsic applied to generated (code, FT) arguments."""
    n = name
    if n in ("dabs",):
        n = "abs"
    if n in ("dsqrt",):
        n = "sqrt"
    if n in ("dlog",):
        n = "log"
    if n in ("dexp",):
        n = "exp"
    if n in ("dimag",):
        n = "aimag"
    if n in ("dconjg",):
        n = "conjg"
    if n in ("dreal", "float", "dfloat", "sngl", "dble"):
        n = "real"
    if n == "dcmplx":
        n = "cmplx"
    if n == "char":
        n = "achar"
    if n == "ichar":
        n = "iachar"
    (a, ta) = vals[0]
    if n == "abs":
        if ta.base == "complex":
            return f"Cx.abs({a})", REAL
        return f"Math.abs({a})", ta
    if n in ("sqrt", "log", "exp", "sin", "cos"):
        if ta.base == "complex":
            return f"Cx.{n}({a})", CPLX
        return f"Math.{n}({gen.convert(a, ta, REAL)})", REAL
    if n in ("log10", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh"):
        if n == "atan" and len(vals) == 2:
            return f"Math.atan2({a}, {vals[1][0]})", REAL
        return f"Math.{n}({gen.convert(a, ta, REAL)})", REAL
    if n == "atan2":
        return f"Math.atan2({gen.convert(a, ta, REAL)}, {gen.convert(vals[1][0], vals[1][1], REAL)})", REAL
    if n == "real":
        return gen.convert(a, ta, REAL), REAL
    if n == "aimag":
        return f"{wrap(a)}.im", REAL
    if n == "conjg":
        return f"Cx.conj({a})", CPLX
    if n == "cmplx":
        if ta.base == "complex" and len(vals) == 1:
            return a, CPLX
        if len(vals) >= 2 and vals[1][1].base != "complex":
            b, tb = vals[1]
            return f"new Cx({gen.convert(a, ta, REAL)}, {gen.convert(b, tb, REAL)})", CPLX
        return f"Cx.of({gen.convert(a, ta, REAL)})", CPLX
    if n == "int":
        if ta.base == "integer":
            return a, INT
        return gen.convert(a, ta, INT), INT
    if n == "nint":
        return f"F.nint({a})", INT
    if n == "floor":
        return f"(int) Math.floor({a})", INT
    if n == "ceiling":
        return f"(int) Math.ceil({a})", INT
    if n == "aint":
        return f"F.aint({a})", REAL
    if n == "anint":
        return f"F.anint({a})", REAL
    if n == "sign":
        b, tb = vals[1]
        if ta.base == "integer":
            return f"F.isign({a}, {b})", INT
        return f"F.sign({a}, {gen.convert(b, tb, REAL)})", REAL
    if n == "mod":
        b, tb = vals[1]
        if ta.base == "integer" and tb.base == "integer":
            return f"({a} % {b})", INT
        return f"({gen.convert(a, ta, REAL)} % {gen.convert(b, tb, REAL)})", REAL
    if n == "modulo":
        b, tb = vals[1]
        if ta.base == "integer":
            return f"Math.floorMod({a}, {b})", INT
        return f"F.modulo({a}, {b})", REAL
    if n in ("max", "min"):
        base = max((t.base for _, t in vals), key=lambda b: NUMERIC_ORDER.get(b, -1))
        rt = FT(base)
        code = gen.convert(vals[0][0], vals[0][1], rt)
        for v, t in vals[1:]:
            code = f"Math.{n}({code}, {gen.convert(v, t, rt)})"
        return code, rt
    if n == "iand":
        return f"({a} & {vals[1][0]})", INT
    if n == "ior":
        return f"({a} | {vals[1][0]})", INT
    if n == "ieor":
        return f"({a} ^ {vals[1][0]})", INT
    if n == "not":
        if ta.base == "logical":
            return f"!{wrap(a)}", LOG
        return f"(~{wrap(a)})", INT
    if n == "ishft":
        return f"F.ishft({a}, {vals[1][0]})", INT
    if n == "ibset":
        return f"({a} | (1 << {vals[1][0]}))", INT
    if n == "ibclr":
        return f"({a} & ~(1 << {vals[1][0]}))", INT
    if n == "btest":
        return f"F.btest({a}, {vals[1][0]})", LOG
    if n == "ibits":
        return f"F.ibits({a}, {vals[1][0]}, {vals[2][0]})", INT
    if n == "scale":
        return f"Math.scalb({a}, {vals[1][0]})", REAL
    if n == "exponent":
        return f"F.exponent({a})", INT
    if n == "fraction":
        return f"F.fraction({a})", REAL
    if n == "merge":
        (t, tt), (f, tf), (m, tm) = vals[0], vals[1], vals[2]
        rt = tt if NUMERIC_ORDER.get(tt.base, -1) >= NUMERIC_ORDER.get(tf.base, -1) else tf
        return f"({m} ? {gen.convert(t, tt, rt)} : {gen.convert(f, tf, rt)})", rt
    if n == "len_trim":
        return f"F.lenTrim({a})", INT
    if n == "adjustl":
        return f"F.adjustl({a})", CHAR
    if n == "adjustr":
        return f"F.adjustr({a})", CHAR
    if n == "index":
        return f"F.index({a}, {vals[1][0]})", INT
    if n == "achar":
        return f"F.achar({a})", CHAR
    if n == "iachar":
        return f"F.iachar({a})", INT
    raise GenError(f"intrinsic {name} not translated")


INTRINSIC_ARGS = {
    "cmplx": ["x", "y", "kind"], "dcmplx": ["x", "y"], "real": ["a", "kind"], "int": ["a", "kind"],
    "nint": ["a", "kind"], "aint": ["a", "kind"], "anint": ["a", "kind"], "floor": ["a", "kind"],
    "ceiling": ["a", "kind"], "sum": ["array", "dim", "mask"], "product": ["array", "dim", "mask"],
    "maxval": ["array", "dim", "mask"], "minval": ["array", "dim", "mask"], "count": ["mask", "dim", "kind"],
    "any": ["mask", "dim"], "all": ["mask", "dim"], "maxloc": ["array", "dim", "mask"],
    "minloc": ["array", "dim", "mask"], "size": ["array", "dim", "kind"], "lbound": ["array", "dim", "kind"],
    "ubound": ["array", "dim", "kind"], "pack": ["array", "mask", "vector"], "unpack": ["vector", "mask", "field"],
    "spread": ["source", "dim", "ncopies"], "reshape": ["source", "shape", "pad", "order"],
    "matmul": ["matrix_a", "matrix_b"], "transpose": ["matrix"], "dot_product": ["vector_a", "vector_b"],
    "index": ["string", "substring", "back", "kind"], "merge": ["tsource", "fsource", "mask"],
    "cshift": ["array", "shift", "dim"], "associated": ["pointer", "target"], "allocated": ["array"],
    "present": ["a"], "shape": ["source", "kind"], "repeat": ["string", "ncopies"], "verify": ["string", "set", "back"],
    "scan": ["string", "set", "back"], "len": ["string", "kind"], "len_trim": ["string", "kind"], "trim": ["string"],
    "epsilon": ["x"], "huge": ["x"], "tiny": ["x"], "digits": ["x"], "precision": ["x"], "range": ["x"],
    "radix": ["x"], "maxexponent": ["x"], "minexponent": ["x"], "kind": ["x"], "bit_size": ["i"],
    "selected_real_kind": ["p", "r", "radix"], "selected_int_kind": ["r"], "null": ["mold"],
}


def scalar_args(gen, name, args, ctx):
    vals = []
    for a in args:
        if a.key == "kind":
            continue
        vals.append(gen.expr(a.value, ctx))
    return vals


def data_of(gen, e, ctx):
    """A Java array holding an array expression's elements, and its FT."""
    if isinstance(e, Name):
        ent = ctx.scope.lookup(e.id)
        if ent is not None and ent.kind == "var" and ent.decl.rank > 0:
            v = gen.var_of(ent, ctx)
            return v.data(), v.ft
    from fsrc import Comp
    if isinstance(e, Comp):
        info = gen.comp_array(e, ctx)
        if info is not None:
            code, ft, alloc = info
            return code + (".a" if alloc else ""), ft
    return gen.materialize(e, ctx)


def dim_const(e):
    if e is None:
        return None
    if isinstance(e, Num) and e.kind == "int":
        return e.value
    return None


def intrinsic(gen, name, args, ctx):
    n = name
    a = positional(args, INTRINSIC_ARGS.get(n, ["a", "b", "c", "d", "e", "f", "g", "h"]))
    # inquiries about numbers
    if n in ("epsilon", "huge", "tiny", "digits", "precision", "range", "radix", "maxexponent", "minexponent",
             "kind", "bit_size"):
        t = gen.arg_type(args[0].value, ctx) if args else REAL
        is_int = t.base == "integer"
        table = {
            "epsilon": ("2.220446049250313E-16", REAL),
            "huge": ("Integer.MAX_VALUE", INT) if is_int else ("Double.MAX_VALUE", REAL),
            "tiny": ("Double.MIN_NORMAL", REAL),
            "digits": ("31", INT) if is_int else ("53", INT),
            "precision": ("15", INT), "range": ("9", INT) if is_int else ("307", INT), "radix": ("2", INT),
            "maxexponent": ("1024", INT), "minexponent": ("-1021", INT),
            "kind": ("4", INT) if is_int or t.base == "logical" else ("8", INT), "bit_size": ("32", INT),
        }
        return table[n]
    if n in ("selected_real_kind",):
        return "8", INT
    if n in ("selected_int_kind",):
        return "4", INT
    if n == "null":
        return "null", FT("void")
    if n == "present":
        x = args[0].value
        v = gen.var(x.id, ctx)
        return f"({v.access} != null)", LOG
    if n == "allocated":
        x = a["array"]
        code, ft, alloc = gen.lvalue_container(x, ctx)
        return f"({code} != null && {code}.a != null)", LOG
    if n == "associated":
        code, ft, alloc = gen.lvalue_container(a["pointer"], ctx)
        if "target" in a:
            t, tt, talloc = gen.lvalue_container(a["target"], ctx)
            return f"({code} == {t})", LOG
        if alloc:
            return f"({code} != null && {code}.a != null)", LOG
        return f"({code} != null)", LOG
    if n == "len":
        x = a["string"]
        d = gen.decl_of(x, ctx)
        if d is not None and d.char_len and re.fullmatch(r"\d+", d.char_len):
            return d.char_len, INT
        c, t = gen.expr(x, ctx)
        return f"{wrap(c)}.length()", INT
    if n == "trim":
        c, t = gen.expr(a["string"], ctx)
        return f"F.trim({c})", CHAR
    if n == "repeat":
        c, t = gen.expr(a["string"], ctx)
        k, kt = gen.expr(a["ncopies"], ctx)
        return f"F.repeat({c}, {k})", CHAR
    if n in ("verify", "scan"):
        c, t = gen.expr(a["string"], ctx)
        s, st = gen.expr(a["set"], ctx)
        return f"F.{n}({c}, {s})", INT
    if n == "index" and "back" in a:
        c, t = gen.expr(a["string"], ctx)
        s, st = gen.expr(a["substring"], ctx)
        b, bt = gen.expr(a["back"], ctx)
        return f"F.index({c}, {s}, {b})", INT
    if n in ("size", "lbound", "ubound"):
        return inquiry(gen, n, a, ctx)
    if n in ("sum", "product", "maxval", "minval", "count", "any", "all") and "dim" not in a:
        return reduction(gen, n, a, ctx)
    if n == "dot_product":
        x, tx = data_of(gen, a["vector_a"], ctx)
        y, ty = data_of(gen, a["vector_b"], ctx)
        rt = tx.elem() if NUMERIC_ORDER.get(tx.base, -1) >= NUMERIC_ORDER.get(ty.base, -1) else ty.elem()
        return f"F.dot({x}, {y})", rt
    # elemental on scalars
    if n in ELEMENTAL:
        types = [gen.arg_type(x.value, ctx) for x in args if x.key != "kind"]
        if any(t is not None and t.rank for t in types):
            vals_t = [t for t in types if t is not None]
            r = max(t.rank for t in vals_t)
            rt = apply_type(gen, n, [(None, t.elem()) for t in vals_t])
            return None, rt.with_rank(r)
        vals = scalar_args(gen, n, ordered(n, args), ctx)
        return apply(gen, n, vals)
    # array-valued: computed into a temporary by the caller
    rt = array_result_type(gen, n, a, ctx)
    return None, rt


def ordered(n, args):
    """Arguments in positional order, keyword ones placed where their dummy is."""
    names = INTRINSIC_ARGS.get(n)
    if names is None or all(a.key is None for a in args):
        return args
    pos = positional(args, names)
    out = []
    for nm in names:
        if nm in pos and nm != "kind":
            out.append(Arg(None, pos[nm]))
    return out


def apply_type(gen, n, vals):
    try:
        fake = [(f"x{i}", t) for i, (_, t) in enumerate(vals)]
        return apply(gen, n, fake)[1]
    except Exception:
        return vals[0][1]


def inquiry(gen, n, a, ctx):
    x = a["array"]
    dim = a.get("dim")
    code, ft, alloc, lbs = None, None, False, None
    if isinstance(x, Name):
        v = gen.var(x.id, ctx)
        data, ft, alloc = v.data(), v.ft, v.alloc
        lbf = v.lb
    else:
        from fsrc import Comp
        if isinstance(x, Comp):
            info = gen.comp_array(x, ctx)
            code, ft, alloc = info
            data = code + (".a" if alloc else "")
            lbf = (lambda k, code=code: f"{code}.lb[{k}]") if alloc else (lambda k: "1")
        else:
            data, ft = gen.materialize(x, ctx)
            lbf = lambda k: "1"
    if dim is None:
        if n == "size":
            if ft.rank == 1:
                return f"{data}.length", INT
            return f"F.size({data})", INT
        if n == "lbound":
            return "new int[]{" + ", ".join(lbf(k) for k in range(ft.rank)) + "}", INT.with_rank(1)
        return "new int[]{" + ", ".join(f"{lbf(k)} + {gen.length(data, k)} - 1" for k in range(ft.rank)) + "}", INT.with_rank(1)
    k = dim_const(dim)
    if k is None:
        dc, dt = gen.expr(dim, ctx)
        if n == "size":
            return f"F.sizeDim({data}, {dc})", INT
        raise GenError(f"{n} with a variable dim")
    k -= 1
    if n == "size":
        return gen.length(data, k), INT
    if n == "lbound":
        return lbf(k), INT
    return f"{lbf(k)} + {gen.length(data, k)} - 1", INT


def reduction(gen, n, a, ctx):
    key = "mask" if n in ("count", "any", "all") else "array"
    x, tx = data_of(gen, a[key], ctx)
    mask = None
    if n not in ("count", "any", "all") and "mask" in a:
        mask, tm = data_of(gen, a["mask"], ctx)
    if n == "count":
        return f"F.count({x})", INT
    if n in ("any", "all"):
        return f"F.{n}({x})", LOG
    rt = tx.elem()
    if mask is not None:
        return f"F.{n}({x}, {mask})", rt
    return f"F.{n}({x})", rt


def array_result_type(gen, n, a, ctx):
    def t(k):
        return gen.arg_type(a[k], ctx)
    if n == "transpose":
        return t("matrix")
    if n == "matmul":
        ta, tb = t("matrix_a"), t("matrix_b")
        base = ta.base if NUMERIC_ORDER.get(ta.base, -1) >= NUMERIC_ORDER.get(tb.base, -1) else tb.base
        return FT(base, 1 if ta.rank == 1 or tb.rank == 1 else 2)
    if n == "pack":
        return t("array").with_rank(1)
    if n == "unpack":
        return t("vector").with_rank(t("mask").rank)
    if n == "spread":
        return t("source").with_rank(t("source").rank + 1)
    if n == "reshape":
        from fsrc import ArrCtor
        shp = a["shape"]
        rank = len(shp.items) if isinstance(shp, ArrCtor) else 2
        src = a["source"]
        st = gen.ctor_type(src, ctx) if isinstance(src, ArrCtor) else t("source")
        return st.elem().with_rank(rank)
    if n in ("sum", "product", "maxval", "minval"):
        x = t("array")
        return x.with_rank(x.rank - 1)
    if n in ("count",):
        return INT.with_rank(t("mask").rank - 1)
    if n in ("any", "all"):
        return LOG.with_rank(t("mask").rank - 1)
    if n in ("maxloc", "minloc"):
        return INT.with_rank(1)
    if n == "shape":
        return INT.with_rank(1)
    if n in ("lbound", "ubound"):
        return INT.with_rank(1)
    if n == "cshift":
        return t("array")
    raise GenError(f"intrinsic {n} not translated")


def array_intrinsic(gen, n, args, ctx):
    a = positional(args, INTRINSIC_ARGS.get(n, []))
    if n == "transpose":
        x, tx = data_of(gen, a["matrix"], ctx)
        return f"F.transpose({x})", tx
    if n == "matmul":
        x, tx = data_of(gen, a["matrix_a"], ctx)
        y, ty = data_of(gen, a["matrix_b"], ctx)
        return f"F.matmul({x}, {y})", array_result_type(gen, n, a, ctx)
    if n == "pack":
        x, tx = data_of(gen, a["array"], ctx)
        m, tm = data_of(gen, a["mask"], ctx) if gen.arg_type(a["mask"], ctx).rank else (gen.expr(a["mask"], ctx)[0], LOG)
        if "vector" in a:
            v, tv = data_of(gen, a["vector"], ctx)
            return f"F.pack({x}, {m}, {v})", tx.with_rank(1)
        return f"F.pack({x}, {m})", tx.with_rank(1)
    if n == "unpack":
        v, tv = data_of(gen, a["vector"], ctx)
        m, tm = data_of(gen, a["mask"], ctx)
        f, tf = gen.expr(a["field"], ctx)
        if tf.rank:
            f, tf = data_of(gen, a["field"], ctx)
            return f"F.unpack({v}, {m}, {f})", tv.with_rank(tm.rank)
        return f"F.unpackScalar({v}, {m}, {gen.convert(f, tf, tv.elem())})", tv.with_rank(tm.rank)
    if n in ("sum", "product", "maxval", "minval", "count", "any", "all"):
        key = "mask" if n in ("count", "any", "all") else "array"
        x, tx = data_of(gen, a[key], ctx)
        d, dt = gen.expr(a["dim"], ctx)
        return f"F.{n}Dim({x}, {d})", array_result_type(gen, n, a, ctx)
    if n in ("maxloc", "minloc"):
        x, tx = data_of(gen, a["array"], ctx)
        if "mask" in a:
            m, tm = data_of(gen, a["mask"], ctx)
            return f"F.{n}({x}, {m})", INT.with_rank(1)
        return f"F.{n}({x})", INT.with_rank(1)
    if n == "shape":
        x, tx = data_of(gen, a["source"], ctx)
        return f"F.shape({x})", INT.with_rank(1)
    if n in ("lbound", "ubound"):
        c, t = inquiry(gen, n, a, ctx)
        return c, t
    if n == "reshape":
        from fsrc import ArrCtor
        src, shp = a["source"], a["shape"]
        if isinstance(src, ArrCtor):
            st = gen.ctor_type(src, ctx)
            s_code = gen.array_ctor(src, ctx, st)
        else:
            s_code, st = data_of(gen, src, ctx)
        if not isinstance(shp, ArrCtor):
            raise GenError("reshape to a shape that is not a constructor")
        dims = [gen.expr(x, ctx)[0] for x in shp.items]
        row = "false"
        if "order" in a:
            o = a["order"]
            if isinstance(o, ArrCtor) and [getattr(x, "value", None) for x in o.items] == [2, 1]:
                row = "true"
        return f"F.reshape{len(dims)}({s_code}, {', '.join(dims)}, {row})", st.elem().with_rank(len(dims))
    if n == "spread":
        x, tx = data_of(gen, a["source"], ctx) if gen.arg_type(a["source"], ctx).rank else gen.expr(a["source"], ctx)
        d, dt = gen.expr(a["dim"], ctx)
        k, kt = gen.expr(a["ncopies"], ctx)
        return f"F.spread({x}, {d}, {k})", array_result_type(gen, n, a, ctx)
    if n == "cshift":
        x, tx = data_of(gen, a["array"], ctx)
        s, st = gen.expr(a["shift"], ctx)
        return f"F.cshift({x}, {s})", tx
    raise GenError(f"array intrinsic {n} not translated")


def elemental(gen, name, args, counters, ctx):
    vals = []
    for x in ordered(name, args):
        if x.key == "kind":
            continue
        vals.append(gen.elem(x.value, counters, ctx))
    return apply(gen, name, vals)

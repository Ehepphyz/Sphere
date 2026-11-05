"""sphere_graph -- any PyTorch model (and Keras's, on its torch backend) as a portable graph Sphere runs anywhere.

    import sphere_graph as sg
    g = sg.export(model, (x, mask), "tagger", feeds="particles")   # torch.export -> core ATen -> SPX
    y = sg.run(g, {"x": x, "mask": mask})                          # numpy, float64: the reference
    sg.check(g)                                                    # what Java and C++ are held to

A model is captured by torch.export with a symbolic batch, decomposed to core ATen -- some eighty operations
that every PyTorch model reduces to -- and written as SPX (models/<name>.sng.spx): a text graph, one
operation a line, its arguments normalized by the operation's own schema, and the weights in float64. The
graph is simplified on the way: what depends on the weights alone is computed once (unless it would grow),
what the outputs do not need is dropped. Sphere then runs it without PyTorch: in numpy here, in Java
(com.sphere.core.ml.NeuralGraph: a FastJet loop, ':ml tag'), in C++ (sphere_graph.hpp: a ROOT macro, a
Geant4 program) -- each held, at export, to PyTorch itself on batches of other sizes than the one traced.

Values are computed in float64; where the model itself casts to float32, the cast rounds as the model's would.
"""

import math
import operator
import os
import sys

import numpy as np

import sphere_spx as spx

FORMAT = 1
_PY = {operator.mul: "py.mul", operator.add: "py.add", operator.sub: "py.sub", operator.floordiv: "py.floordiv",
       operator.truediv: "py.truediv", operator.neg: "py.neg", operator.getitem: "py.getitem",
       operator.mod: "py.mod", operator.pow: "py.pow"}


class GraphError(ValueError):
    """An operation sphere_graph does not have, where the model calls it."""


# bitwise operations on booleans are logic: what a mask's ~, & and | become
_LOGICAL = {"bitwise_not.default": "logical_not.default", "bitwise_and.Tensor": "logical_and.default",
            "bitwise_or.Tensor": "logical_or.default", "bitwise_xor.Tensor": "logical_xor.default"}


# ---------------------------------------------------------------------------
# Export: torch.export, core ATen, the graph
# ---------------------------------------------------------------------------

def _encode(v, tensors):
    import torch
    if isinstance(v, torch.fx.Node):
        return "n:" + v.name
    if isinstance(v, bool):
        return "b:%d" % v
    if isinstance(v, int):
        return "i:%d" % v
    if isinstance(v, float):
        return "f:" + repr(v)
    if v is None:
        return "none"
    if isinstance(v, torch.dtype):
        return "s:" + str(v).replace("torch.", "")
    if isinstance(v, (torch.device, torch.layout, torch.memory_format)):
        return "none"
    if isinstance(v, str):
        return "s:" + v
    if isinstance(v, (list, tuple)):
        if v and all(isinstance(e, torch.fx.Node) and e.name in tensors for e in v):
            return "T:" + ",".join(e.name for e in v)
        return "L:" + ",".join(_encode(e, tensors) for e in v)
    raise GraphError("an argument of type %s (%r)" % (type(v).__name__, v))


def _normalized(node):
    """The node's arguments in the order of its schema, defaults filled: what the runtimes index."""
    t = node.target
    schema = getattr(t, "_schema", None)
    if schema is None:
        return list(node.args)
    out = []
    for i, a in enumerate(schema.arguments):
        if i < len(node.args):
            out.append(node.args[i])
        elif a.name in node.kwargs:
            out.append(node.kwargs[a.name])
        elif a.has_default_value():
            out.append(a.default_value)
        else:
            out.append(None)
    return out


def _where(node):
    trace = (node.meta or {}).get("stack_trace") or ""
    lines = [l.strip() for l in trace.strip().splitlines() if l.strip()]
    return lines[-1] if lines else "?"


def _positional(model, n):
    """The model as a module of n named positional tensors, what torch.export gives a symbolic batch to: a Keras
    model (one list of inputs, its forward a *args), or any other whose forward takes *args; others as they are."""
    import inspect
    import torch
    keras = "keras" in type(model).__module__
    try:
        varargs = any(p.kind == p.VAR_POSITIONAL for p in inspect.signature(model.forward).parameters.values())
    except (TypeError, ValueError):
        varargs = True
    if not keras and not varargs:
        return model
    names = ", ".join("x%d" % i for i in range(n))
    scope = {}
    exec("def forward(self, %s):\n    return self.model(%s)\n" % (names, "[%s]" % names if keras and n > 1 else names), scope)
    wrapper = type("Positional", (torch.nn.Module,), {"forward": scope["forward"]})()
    wrapper.model = model
    return wrapper


def export(model, example, name, feeds=None, dynamic_batch=True, folder=None, framework=None, check=True):
    """The model as a graph: models/<name>.sng.spx, and the graph itself (a dict). example is the tuple of
    the model's inputs (tensors) for the trace; with dynamic_batch, their first dimension is left free.
    feeds says what the inputs are, for Sphere's commands to feed them: "particles" (Sphere's raw particle
    features [B, P, F] and their mask [B, P], as ':ml jets' writes them), "high_level" ([B, H]), or None.

    A model whose own code fixes its batch -- Keras's, on its torch backend, does -- cannot be traced with a
    free one; it is traced at two batches instead, and the two graphs made one of any batch (_differential)."""
    import copy
    import torch
    try:
        m = copy.deepcopy(model)
    except Exception:
        m = model
    m = m.eval()
    try:
        if "keras" not in type(model).__module__:   # Keras keeps its layers' precision by their dtype policy
            m = m.double()
        example = tuple(e.double() if torch.is_tensor(e) and e.is_floating_point() else e for e in example)
    except Exception:
        pass   # a model that keeps its own precision: its casts are in the graph
    m = _positional(m, len(example))
    names = []
    for i, e in enumerate(example):
        names.append("x" if i == 0 else "mask" if i == 1 and feeds == "particles" else "input%d" % i)
    about = {"name": name, "feeds": feeds or "", "framework": framework or "torch " + torch.__version__}
    if not dynamic_batch:
        graph = _trace(m, example, names, False, about)
        graph["batch"] = "fixed"
    else:
        try:
            graph = _trace(m, example, names, True, about)
            graph["batch"] = "symbolic"
        except Exception as e:
            if "Constraints violated" not in str(e) and "specialized" not in str(e):
                raise
            graph = _differential(m, example, names, about)
    graph["example_shapes"] = [list(e.shape) if torch.is_tensor(e) else [] for e in example]
    graph["precision"] = "mixed" if any(nd["dtype"] in ("float32", "float16", "bfloat16") for nd in graph["nodes"]) \
        else "float64"
    arrays = _arrays(graph, list(example))
    graph["optimized"] = optimize(graph, [arrays[i] for i in graph["inputs"]])
    if not graph.get("_short"):
        _shorten(graph)
    graph["rows_independent"] = _rows_independent(graph, example)
    p = plan(graph)
    if p:
        graph["memory"] = {"per_row_mb": p["per_row_peak"] / 2 ** 20, "weights_mb": p["weights"] / 2 ** 20,
                           "linear": p["linear"]}
    path = save(graph, folder)
    graph["path"] = path
    if check:
        graph["check"] = check_against(m, graph, example)
    return graph


def _trace(m, example, names, dynamic, about):
    """torch.export of the model on these inputs -- their first dimension symbolic, if dynamic -- decomposed to
    core ATen: the graph of its weights and its nodes, their arguments encoded; folded and pruned."""
    import torch
    from torch.export import Dim
    shapes = None
    if dynamic:
        b = Dim("batch", min=1, max=1 << 24)
        shapes = tuple({0: b} if torch.is_tensor(e) and e.dim() > 0 else None for e in example)
    with torch.no_grad():
        ep = torch.export.export(m, tuple(example), dynamic_shapes=shapes)
        core = ep.run_decompositions()
    sig = core.graph_signature
    consts, inputs, kinds = {}, [], {}
    params = dict(core.state_dict)
    params.update(getattr(core, "constants", {}) or {})
    user = iter(names)
    for s in sig.input_specs:
        kind = s.kind.name
        if kind == "USER_INPUT":
            inputs.append(s.arg.name)
            kinds[s.arg.name] = next(user, s.arg.name)
        elif kind in ("PARAMETER", "BUFFER", "CONSTANT_TENSOR"):
            consts[s.arg.name] = np.asarray(params[s.target].detach().cpu().double().numpy(), dtype=np.float64)
        else:
            raise GraphError("an input of kind %s (%s)" % (kind, s.arg.name))
    outputs = [s.arg.name for s in sig.output_specs if s.kind.name == "USER_OUTPUT"]
    nodes, tensors, missing = [], set(inputs) | set(consts), {}
    for n in core.graph.nodes:
        if n.op != "call_function":
            continue
        t = n.target
        if t in _PY:
            op = _PY[t]
        elif hasattr(t, "_schema"):
            op = "%s.%s" % (t._schema.name.split("::")[-1], t._overloadname)
        else:
            raise GraphError("%s, at %s: not an ATen operation" % (t, _where(n)))
        val = n.meta.get("val")
        if op in _LOGICAL and torch.is_tensor(val) and val.dtype == torch.bool:
            op = _LOGICAL[op]   # ~mask, mask & other: logic on booleans; on integers they stay bits, and missing
        if op not in OPS:
            missing.setdefault(op, _where(n))
        if torch.is_tensor(val):
            tensors.add(n.name)
        nodes.append({"name": n.name, "op": op, "args": _normalized(n),
                      "dtype": str(val.dtype).replace("torch.", "") if torch.is_tensor(val) else ""})
    if missing:
        raise GraphError("operations sphere_graph has not (yet), with where the model calls them:\n" + "\n".join(
            "  %s  at %s" % kv for kv in sorted(missing.items())))
    for nd in nodes:
        nd["enc"] = [_encode(a, tensors) for a in nd["args"]]
    graph = {"name": about["name"], "inputs": inputs, "input_names": [kinds[i] for i in inputs], "outputs": outputs,
             "consts": consts, "shapes": {k: list(v.shape) for k, v in consts.items()}, "nodes": nodes,
             "feeds": about["feeds"], "framework": about["framework"]}
    _fold(graph)
    _prune(graph)
    return graph


def _differential(m, example, names, about, batches=(5, 7)):
    """A graph of any batch from two traces at fixed ones: the graphs must be the same but for integers, and
    each integer that differs, a between the batch b1's and b between b2's, is taken for k B + c of the batch B
    (k = (a - b) / (b1 - b2), whole numbers both) -- computed from the input's first dimension at run time, by
    nodes put first. What fixed the batch in the model's code is then free again; check_against holds the result
    to the model at three batches neither trace saw."""
    import gc
    rng = np.random.default_rng(1)
    b1, b2 = batches
    g1 = _trace(m, _batched(example, b1, rng), names, False, about)
    gc.collect()   # the first trace's program and its cycles gone before the second's
    g2 = _trace(m, _batched(example, b2, rng), names, False, about)
    gc.collect()
    _shorten(g1)
    _shorten(g2)
    if len(g1["nodes"]) != len(g2["nodes"]) or g1["shapes"] != g2["shapes"] or any(
            not np.array_equal(g1["consts"][k], g2["consts"][k]) for k in g1["consts"]):
        raise GraphError("the model's graph changes with its batch beyond its sizes: export it with dynamic_batch=False")
    made, extra = {}, []

    def symbol(k, c):
        if (k, c) not in made:
            if not extra:
                extra.append({"name": "s0", "op": "sym_size.int", "enc": ["n:" + g1["inputs"][0], "i:0"], "dtype": ""})
            ref = "s0"
            for op, v in (("py.mul", k), ("py.add", c)):
                if v != (1 if op == "py.mul" else 0):
                    extra.append({"name": "s%d" % len(extra), "op": op, "enc": ["n:" + ref, "i:%d" % v], "dtype": ""})
                    ref = extra[-1]["name"]
            made[(k, c)] = ref
        return "n:" + made[(k, c)]

    def scalar(t1, t2):
        if t1 == t2:
            return t1
        if t1.startswith("i:") and t2.startswith("i:"):
            a, b = int(t1[2:]), int(t2[2:])
            if (a - b) % (b1 - b2) == 0:
                k = (a - b) // (b1 - b2)
                return symbol(k, a - k * b1)
        raise GraphError("an argument changes with the batch otherwise than k B + c: %s at %d, %s at %d" % (t1, b1, t2, b2))

    for n1, n2 in zip(g1["nodes"], g2["nodes"]):
        if n1["op"] != n2["op"] or len(n1["enc"]) != len(n2["enc"]):
            raise GraphError("the model's graph changes with its batch: %s against %s" % (n1["op"], n2["op"]))
        enc = []
        for t1, t2 in zip(n1["enc"], n2["enc"]):
            if t1 != t2 and t1.startswith("L:") and t2.startswith("L:"):
                e1, e2 = t1[2:].split(","), t2[2:].split(",")
                if len(e1) != len(e2):
                    raise GraphError("a list changes length with the batch: %s, %s" % (t1, t2))
                enc.append("L:" + ",".join(scalar(x, y) for x, y in zip(e1, e2)))
            else:
                enc.append(scalar(t1, t2))
        n1["enc"] = enc
    g1["nodes"] = extra + g1["nodes"]
    g1["_short"] = True
    g1["batch"] = "generalized from batches %d and %d (%d sizes made symbolic)" % (b1, b2, len(made))
    return g1


def _refs(enc):
    out = []
    for a in enc:
        if a.startswith("n:"):
            out.append(a[2:])
        elif a.startswith("T:"):
            out += a[2:].split(",")
        elif a.startswith("L:"):
            out += [e[2:] for e in a[2:].split(",") if e.startswith("n:")]
    return out


def _fold(graph):
    """What depends on the weights alone, computed once -- unless it would grow (an expand of a weight stays an
    expand): the graph a model leaves is the weights transposed, reshaped, scaled, once and for all."""
    known = dict(graph["consts"])
    kept = []
    for nd in graph["nodes"]:
        refs = _refs(nd["enc"])
        if refs and all(r in known for r in refs) and nd["op"] not in ("py.getitem",):
            try:
                v = OPS[nd["op"]](*_decode(nd["enc"], known))
            except Exception:
                v = None
            if isinstance(v, np.ndarray) and v.size <= sum(np.size(known[r]) for r in refs if isinstance(known[r], np.ndarray)):
                known[nd["name"]] = v
                graph["consts"][nd["name"]] = np.asarray(v, dtype=np.float64)
                graph["shapes"][nd["name"]] = list(v.shape)
                continue
        kept.append(nd)
    graph["nodes"] = kept


CONTROL = ("sphere.while", "sphere.case")


def _blocks_of(nd):
    """The blocks a control node runs: its "s:" arguments, and lists of them."""
    if nd["op"] not in CONTROL:
        return []
    out = []
    for t in nd["enc"]:
        if t.startswith("s:"):
            out.append(t[2:])
        elif t.startswith("L:") and len(t) > 2:
            out += [e[2:] for e in t[2:].split(",") if e.startswith("s:")]
    return out


def _prune_nodes(nodes, outputs):
    need = set(outputs)
    for nd in reversed(nodes):
        if nd["name"] in need:
            need.update(_refs(nd["enc"]))
    return [nd for nd in nodes if nd["name"] in need], need


def _prune(graph):
    """The nodes, blocks and weights the outputs need, no more -- inside the blocks too."""
    graph["nodes"], need = _prune_nodes(graph["nodes"], graph["outputs"])
    blocks = graph.get("blocks") or {}
    if blocks:
        used, stack = set(), [b for nd in graph["nodes"] for b in _blocks_of(nd)]
        while stack:
            b = stack.pop()
            if b in used or b not in blocks:
                continue
            used.add(b)
            blk = blocks[b]
            blk["nodes"], inner = _prune_nodes(blk["nodes"], blk["outputs"])
            need |= inner
            stack += [c for nd in blk["nodes"] for c in _blocks_of(nd)]
        graph["blocks"] = {k: v for k, v in blocks.items() if k in used}
    for k in [k for k in graph["consts"] if k not in need]:
        del graph["consts"][k]
        del graph["shapes"][k]


# ---------------------------------------------------------------------------
# The optimizer: exact rewrites only, each graph held to its original to the bit before it is kept
# ---------------------------------------------------------------------------

_IDENTITIES = ("alias.default", "detach.default", "contiguous.default")
_VIEWS = ("view.default", "_unsafe_view.default", "reshape.default")


def _scalar_const(t, consts, value):
    """Whether token t is the number value: a literal, or a weight of one element of it (0-d)."""
    if t.startswith(("f:", "i:")):
        return float(t[2:]) == value
    if t.startswith("n:") and t[2:] in consts:
        c = np.asarray(consts[t[2:]])
        return c.ndim == 0 and float(c) == value
    return False


def _rewrite(nd, prod, consts, protect):
    """An exact rewrite of a node, its arguments' producers known: ("alias", name) -- the node is that value --,
    ("node", op, enc) -- another node --, or None."""
    op, e = nd["op"], nd["enc"]
    arg = e[0][2:] if e and e[0].startswith("n:") else None
    if arg is None:
        return None
    inner = prod.get(arg)
    if nd["name"] in protect and (arg not in prod):
        return None   # an output that is an input or a weight keeps a node of its own
    if op in _IDENTITIES or op == "clone.default" or op in ("_to_copy.default", "to.dtype") and (
            len(e) < 2 or e[1] in ("none", "s:float64")):
        return "alias", arg
    if op in ("_to_copy.default", "to.dtype") and len(e) > 1 and e[1] == "s:float32" and inner is not None and \
            inner["op"] in ("_to_copy.default", "to.dtype") and len(inner["enc"]) > 1 and inner["enc"][1] == "s:float32":
        return "alias", arg   # rounded to float32 twice is rounded once
    if op in ("mul.Tensor", "mul.Scalar") and len(e) > 1:
        if _scalar_const(e[1], consts, 1.0):
            return "alias", arg
        if e[1].startswith("n:") and _scalar_const(e[0], consts, 1.0):
            return "alias", e[1][2:]
    if op in ("div.Tensor", "div.Scalar") and len(e) > 1 and _scalar_const(e[1], consts, 1.0):
        return "alias", arg
    if op in ("sub.Tensor", "sub.Scalar") and len(e) > 1 and _scalar_const(e[1], consts, 0.0) and (
            len(e) < 3 or e[2] in ("none", "i:1", "f:1.0")):
        return "alias", arg   # x - 0 is x, -0 included (x + 0 is not: -0 + 0 is +0)
    if op == "pow.Tensor_Scalar" and len(e) > 1 and _scalar_const(e[1], consts, 1.0):
        return "alias", arg
    if op in ("neg.default", "relu.default") and inner is not None and inner["op"] == op:
        return ("alias", inner["enc"][0][2:]) if op == "neg.default" else ("alias", arg)
    if op == "permute.default" and len(e) > 1 and e[1].startswith("L:"):
        perm = [int(t[2:]) for t in e[1][2:].split(",") if t] if len(e[1]) > 2 else []
        if perm == list(range(len(perm))):
            return "alias", arg
        if inner is not None and inner["op"] == "permute.default" and inner["enc"][1].startswith("L:"):
            first = [int(t[2:]) for t in inner["enc"][1][2:].split(",") if t]
            if len(first) == len(perm) and all(p >= 0 for p in first + perm):
                return "node", "permute.default", [inner["enc"][0], "L:" + ",".join("i:%d" % first[p] for p in perm)]
    if op in _VIEWS and inner is not None and inner["op"] in _VIEWS:
        return "node", op, [inner["enc"][0]] + e[1:]   # a view of a view is a view of the first's input
    return None


def _optimize_nodes(nodes, outputs, consts, inputs, stats):
    """Rewrites, the fusion of mm and a bias into addmm, common subexpressions merged, the dead removed -- to a
    fixed point."""
    protect = set(outputs)
    for _ in range(8):
        alias, prod, seen, out, changed = {}, {}, {}, [], False

        def sub(t):
            def one(x):
                while x in alias:
                    x = alias[x]
                return x
            if t.startswith("n:"):
                return "n:" + one(t[2:])
            if t.startswith("T:"):
                return "T:" + ",".join(one(x) for x in t[2:].split(","))
            if t.startswith("L:") and len(t) > 2:
                return "L:" + ",".join("n:" + one(x[2:]) if x.startswith("n:") else x for x in t[2:].split(","))
            return t

        uses = {}
        for nd in nodes:
            for r in _refs(nd["enc"]):
                uses[r] = uses.get(r, 0) + 1
        for nd in nodes:
            nd = dict(nd, enc=[sub(t) for t in nd["enc"]])
            rw = _rewrite(nd, prod, consts, protect)
            if rw is not None and rw[0] == "alias" and rw[1] != nd["name"]:
                alias[nd["name"]] = rw[1]
                stats["rewritten"] = stats.get("rewritten", 0) + 1
                changed = True
                continue
            if rw is not None and rw[0] == "node":
                nd = dict(nd, op=rw[1], enc=rw[2])
                stats["rewritten"] = stats.get("rewritten", 0) + 1
                changed = True
            # mm then a bias of alpha 1: addmm (beta c + alpha a@b, both 1: c + a@b, the same sum), computed in place
            if nd["op"] == "add.Tensor" and (len(nd["enc"]) < 3 or nd["enc"][2] in ("i:1", "f:1.0", "none")):
                for k in (0, 1):
                    t = nd["enc"][k]
                    p = prod.get(t[2:]) if t.startswith("n:") else None
                    if p is not None and p["op"] == "mm.default" and uses.get(t[2:], 0) == 1 and t[2:] not in protect \
                            and nd["enc"][1 - k].startswith("n:"):
                        nd = dict(nd, op="addmm.default", enc=[nd["enc"][1 - k], p["enc"][0], p["enc"][1]])
                        stats["fused"] = stats.get("fused", 0) + 1
                        changed = True
                        break
            key = (nd["op"], tuple(nd["enc"]))   # pure operations: the same arguments, the same value
            if key in seen:
                alias[nd["name"]] = seen[key]
                stats["merged"] = stats.get("merged", 0) + 1
                changed = True
                continue
            seen[key] = nd["name"]
            prod[nd["name"]] = nd
            out.append(nd)
        for i, o in enumerate(outputs):
            while o in alias:
                o = alias[o]
            outputs[i] = o
        nodes, _ = _prune_nodes(out, outputs)
        if not changed:
            break
    return nodes


def optimize(graph, example=None):
    """The graph made smaller by exact rewrites only -- identities removed, x*1, x/1, x-0, x^1, -(-x), relu(relu(x)),
    float32 twice, permutations and views composed, mm and its bias fused into addmm (computed in place by Java and
    C++: one tensor fewer), common subexpressions merged, the dead removed, in every block too. With example
    inputs, the optimized graph is held to the original on them, to the bit, and kept only if so. Stats returned."""
    def snap(nodes):   # what a node is once encoded: its name, operation, arguments (the trace's own objects left)
        return [{k: (list(v) if k == "enc" else v) for k, v in nd.items() if k != "args"} for nd in nodes]

    before = {"nodes": snap(graph["nodes"]), "outputs": list(graph["outputs"]), "consts": dict(graph["consts"]),
              "shapes": dict(graph["shapes"])}
    if graph.get("blocks"):
        before["blocks"] = {b: {"inputs": list(v["inputs"]), "nodes": snap(v["nodes"]), "outputs": list(v["outputs"])}
                            for b, v in graph["blocks"].items()}
    n0 = len(graph["nodes"]) + sum(len(b["nodes"]) for b in (graph.get("blocks") or {}).values())
    reference = None
    if example is not None:
        with np.errstate(all="ignore"):
            reference = _execute(graph, dict(zip(graph["inputs"], example)))
    stats = {}
    outs = list(graph["outputs"])
    graph["nodes"] = _optimize_nodes(graph["nodes"], outs, graph["consts"], graph["inputs"], stats)
    graph["outputs"] = outs
    for blk in (graph.get("blocks") or {}).values():
        bouts = list(blk["outputs"])
        blk["nodes"] = _optimize_nodes(blk["nodes"], bouts, graph["consts"], blk["inputs"], stats)
        blk["outputs"] = bouts
    for k in ("_last", "_plan"):
        graph.pop(k, None)
    _prune(graph)
    n1 = len(graph["nodes"]) + sum(len(b["nodes"]) for b in (graph.get("blocks") or {}).values())
    stats.update({"before": n0, "after": n1, "verified": None})
    if reference is not None:
        with np.errstate(all="ignore"):
            got = _execute(graph, dict(zip(graph["inputs"], example)))
        same = len(got) == len(reference) and all(
            np.shape(a) == np.shape(b) and np.array_equal(np.asarray(a), np.asarray(b), equal_nan=True) for a, b in zip(got, reference))
        stats["verified"] = bool(same)
        if not same:   # an optimization that changed a bit: none is kept
            graph.update(before)
            graph.pop("_last", None)
            stats.update({"after": n0, "reverted": True})
    return stats


def _shorten(graph):
    """Short names -- inputs i0.., weights w0.., values v0.., blocks b0.. and theirs bK.i0.., bK.v0.. -- for SPX's
    24-character section names, and a shorter graph to read."""
    m = {}
    for k, i in enumerate(graph["inputs"]):
        m[i] = "i%d" % k
    for k, c in enumerate(graph["consts"]):
        m[c] = "w%d" % k
    for k, nd in enumerate(graph["nodes"]):
        m[nd["name"]] = "v%d" % k
    blocks = graph.get("blocks") or {}
    bm = {b: "b%d" % k for k, b in enumerate(blocks)}
    for k in ("_last", "_plan"):   # caches of the old names
        graph.pop(k, None)

    def tok(t, local):
        def ref(e):
            return local[e] if e in local else m[e]
        if t.startswith("n:"):
            return "n:" + ref(t[2:])
        if t.startswith("T:"):
            return "T:" + ",".join(ref(e) for e in t[2:].split(","))
        if t.startswith("s:") and t[2:] in bm:
            return "s:" + bm[t[2:]]
        if t.startswith("L:") and len(t) > 2:
            return "L:" + ",".join("n:" + ref(e[2:]) if e.startswith("n:") else "s:" + bm[e[2:]]
                                   if e.startswith("s:") and e[2:] in bm else e for e in t[2:].split(","))
        return t

    for nd in graph["nodes"]:
        nd["enc"] = [tok(t, {}) for t in nd["enc"]]
        nd["name"] = m[nd["name"]]
    renamed = {}
    for b, blk in blocks.items():
        short = bm[b]
        local = {i: "%s.i%d" % (short, k) for k, i in enumerate(blk["inputs"])}
        local.update({nd["name"]: "%s.v%d" % (short, k) for k, nd in enumerate(blk["nodes"])})
        for nd in blk["nodes"]:
            nd["enc"] = [tok(t, local) for t in nd["enc"]]
            nd["name"] = local[nd["name"]]
        renamed[short] = {"inputs": [local[i] for i in blk["inputs"]], "nodes": blk["nodes"],
                          "outputs": [local[o] if o in local else m[o] for o in blk["outputs"]]}
    if blocks:
        graph["blocks"] = renamed
    graph["inputs"] = [m[i] for i in graph["inputs"]]
    graph["consts"] = {m[k]: v for k, v in graph["consts"].items()}
    graph["shapes"] = {m[k]: v for k, v in graph["shapes"].items()}
    graph["outputs"] = [m[o] for o in graph["outputs"]]


def text(graph):
    """The graph as the runtimes read it: one line a declaration, tab-separated; its blocks (the bodies of loops
    and the branches of conditions) between begin and end, each a function of its inputs and the weights."""
    blocks = graph.get("blocks") or {}
    out = ["#sphere-graph %d" % (2 if blocks else FORMAT)]
    for i, k in zip(graph["inputs"], graph["input_names"]):
        out.append("input\t%s\t%s" % (i, k))
    for k, shape in graph["shapes"].items():
        out.append("const\t%s\t%s" % (k, ",".join(str(int(s)) for s in shape)))
    for name, blk in blocks.items():
        out.append("begin\t%s" % name)
        out += ["input\t%s\t%s" % (i, i) for i in blk["inputs"]]
        out += ["node\t%s\t%s\t%s" % (nd["name"], nd["op"], " ".join(nd["enc"])) for nd in blk["nodes"]]
        out += ["output\t%s" % o for o in blk["outputs"]]
        out.append("end")
    for nd in graph["nodes"]:
        out.append("node\t%s\t%s\t%s" % (nd["name"], nd["op"], " ".join(nd["enc"])))
    for o in graph["outputs"]:
        out.append("output\t%s" % o)
    return "\n".join(out) + "\n"


def _folder(folder=None):
    return folder or os.path.join(spx.bridge_folder(), "models")


def save(graph, folder=None):
    folder = _folder(folder)
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, graph["name"] + ".sng.spx")
    meta = {"Kind": "sphere-graph", "Format": 2 if graph.get("blocks") else FORMAT, "Name": graph["name"],
            "Inputs": ",".join(graph["input_names"]),
            "Outputs": len(graph["outputs"]), "Feeds": graph["feeds"], "Framework": graph["framework"],
            "Precision": graph.get("precision", "float64"), "Nodes": len(graph["nodes"]),
            "ExampleShapes": ";".join(",".join(str(s) for s in sh) for sh in graph.get("example_shapes", [])),
            "RowsIndependent": 1 if graph.get("rows_independent") else 0, "Batch": graph.get("batch", "symbolic")}
    from sphere_ml import write_spx
    sections = [("graph", text(graph))] + [("w." + k, np.asarray(v, dtype=np.float64).ravel())
                                           for k, v in graph["consts"].items()]
    write_spx(path, graph["name"], sections, meta)
    return path


def load(name, folder=None):
    """A graph from its SPX, as export made it (no PyTorch needed)."""
    path = name if name.endswith(".spx") else os.path.join(_folder(folder), name + ".sng.spx")
    f = spx.SPXFile(path)
    meta = f.meta()
    graph = {"name": meta.get("Name", ""), "inputs": [], "input_names": [], "outputs": [], "consts": {}, "shapes": {},
             "nodes": [], "feeds": meta.get("Feeds", ""), "framework": meta.get("Framework", ""),
             "precision": meta.get("Precision", "float64"), "path": path,
             "rows_independent": meta.get("RowsIndependent", "0") == "1",
             # a scalar input's shape is empty, and kept: two of them are ";"
             "example_shapes": [[int(s) for s in sh.split(",") if s] for sh in meta.get("ExampleShapes", "").split(";")]
             if meta.get("ExampleShapes", "") else []}
    block = None   # the block being read, between begin and end
    for line in f.text("graph").splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        into = block if block is not None else graph
        if parts[0] == "begin":
            block = graph.setdefault("blocks", {}).setdefault(parts[1], {"inputs": [], "nodes": [], "outputs": []})
        elif parts[0] == "end":
            block = None
        elif parts[0] == "input":
            into["inputs"].append(parts[1])
            if block is None:
                graph["input_names"].append(parts[2])
        elif parts[0] == "const":
            shape = [int(s) for s in parts[2].split(",") if s != ""]
            graph["shapes"][parts[1]] = shape
            graph["consts"][parts[1]] = np.array(f.array("w." + parts[1])).reshape(shape)
        elif parts[0] == "node":
            into["nodes"].append({"name": parts[1], "op": parts[2], "enc": parts[3].split(" ") if len(parts) > 3 and
                                  parts[3] else [], "dtype": ""})
        elif parts[0] == "output":
            into["outputs"].append(parts[1])
    return graph


# ---------------------------------------------------------------------------
# The reference: numpy, float64
# ---------------------------------------------------------------------------

def _scalar(tok, env):
    if tok == "none":
        return None   # an index list's whole dimension: x[:, idx]
    kind, _, v = tok.partition(":")
    if kind == "n":
        return env[v]
    if kind == "i":
        return int(v)
    if kind == "f":
        return float(v)
    if kind == "b":
        return v == "1"
    if kind == "s":
        return v
    raise GraphError("token " + tok)


def _decode(enc, env):
    out = []
    for tok in enc:
        if tok == "none":
            out.append(None)
        elif tok.startswith("T:"):
            out.append([env[e] for e in tok[2:].split(",")])
        elif tok.startswith("L:"):
            out.append([_scalar(e, env) for e in tok[2:].split(",")] if len(tok) > 2 else [])
        else:
            out.append(_scalar(tok, env))
    return out


def _arrays(graph, inputs):
    if isinstance(inputs, (list, tuple)):
        inputs = dict(zip(graph["input_names"], inputs))
    out = {}
    for i, k in zip(graph["inputs"], graph["input_names"]):
        v = inputs[k] if k in inputs else inputs[i]
        out[i] = np.asarray(v.detach().cpu().double().numpy() if hasattr(v, "detach") else v, dtype=np.float64)
    return out


def _nbytes(v):
    if isinstance(v, np.ndarray):
        return v.nbytes
    if isinstance(v, (list, tuple)):
        return sum(_nbytes(e) for e in v)
    return 0


def _owners(v, out):
    """The arrays that own v's memory: a view counts as the array it views, as Java and C++ count them."""
    if isinstance(v, np.ndarray):
        while isinstance(v.base, np.ndarray):
            v = v.base
        out.append(v)
    elif isinstance(v, (list, tuple)):
        for e in v:
            _owners(e, out)
    return out


class _Holdings:
    """The bytes a run's values hold: each array once, however many views share it; the weights apart."""

    def __init__(self, consts):
        self.count, self.held = {}, 0.0
        self.weights = {id(o) for c in consts.values() for o in _owners(c, [])}

    def take(self, v):
        for o in _owners(v, []):
            if id(o) in self.weights:
                continue
            entry = self.count.setdefault(id(o), [0, o])   # the array kept, so that its id is not reused meanwhile
            entry[0] += 1
            if entry[0] == 1:
                self.held += o.nbytes

    def drop(self, v):
        for o in _owners(v, []):
            entry = self.count.get(id(o))
            if entry is not None:
                entry[0] -= 1
                if entry[0] == 0:
                    del self.count[id(o)]
                    self.held -= o.nbytes


def _execute(graph, arrays, live=None):
    """One pass over the nodes, each value freed after its last use; live (a list) gets the bytes held after
    every node -- what the memory plan is made of."""
    env = dict(graph["consts"])
    env.update(arrays)
    last = graph.get("_last")
    if last is None:
        last = {}
        for at, nd in enumerate(graph["nodes"]):
            for r in _refs(nd["enc"]):
                last[r] = at
        graph["_last"] = last
    h = _Holdings(graph["consts"])
    for v in arrays.values():
        h.take(v)
    outputs = set(graph["outputs"])
    for at, nd in enumerate(graph["nodes"]):
        try:
            v = _apply(graph, nd["op"], _decode(nd["enc"], env))
        except GraphError:
            raise
        except Exception as e:
            raise GraphError("%s (%s): %s: %s" % (nd["name"], nd["op"], type(e).__name__, e)) from e
        env[nd["name"]] = v
        h.take(v)
        if live is not None:
            live.append(h.held)
        for r in set(_refs(nd["enc"])):
            if last.get(r) == at and r not in outputs and r not in graph["consts"]:
                h.drop(env.pop(r, None))
    return [env[o] for o in graph["outputs"]]


LOOP_LIMIT = 10 ** 7   # a loop past this many iterations is taken for one that does not end


def _truth(v):
    """A condition's value: a scalar, or a tensor of one element (TensorFlow's predicates are)."""
    a = np.asarray(v).reshape(-1)
    if a.size != 1:
        raise GraphError("a condition of %d values, not one" % a.size)
    return bool(a[0] != 0)


def _run_block(graph, name, values):
    """A block (a loop's condition or body, a branch) run on its inputs' values: its outputs."""
    blk = graph["blocks"][name]
    if len(values) != len(blk["inputs"]):
        raise GraphError("block %s takes %d inputs, not %d" % (name, len(blk["inputs"]), len(values)))
    env = dict(graph["consts"])
    env.update(zip(blk["inputs"], values))
    for nd in blk["nodes"]:
        try:
            env[nd["name"]] = _apply(graph, nd["op"], _decode(nd["enc"], env))
        except GraphError:
            raise
        except Exception as e:
            raise GraphError("%s (%s): %s: %s" % (nd["name"], nd["op"], type(e).__name__, e)) from e
    return [env[o] for o in blk["outputs"]]


def _apply(graph, op, args):
    """An operation on its arguments; the control ones -- sphere.while (condition, body, values[, limit]) and
    sphere.case (index, branches, values) -- run their blocks, as Java's and C++'s runtimes do."""
    if op == "sphere.while":
        cond, body, vals = args[0], args[1], list(args[2])
        limit = int(args[3]) if len(args) > 3 and args[3] is not None else LOOP_LIMIT
        n = 0
        while _truth(_run_block(graph, cond, vals)[0]):
            vals = _run_block(graph, body, vals)
            n += 1
            if n >= limit:
                raise GraphError("a loop past %d iterations" % limit)
        return vals
    if op == "sphere.case":
        k = int(np.asarray(args[0]).reshape(-1)[0])
        branches = args[1]
        return _run_block(graph, branches[k if 0 <= k < len(branches) else len(branches) - 1], list(args[2]))
    return OPS[op](*args)


def plan(graph):
    """The memory a run takes, as the batch grows: the graph run on batches of 1 and 2 (inputs of the traced
    shapes), the bytes alive after each node counted, so that every node t holds a_t + b_t B bytes and the
    peak is max_t (a_t + b_t B) -- checked at a batch of 4. Cached on the graph."""
    if "_plan" in graph:
        return graph["_plan"]
    shapes = graph.get("example_shapes") or []
    if not shapes or len(shapes) != len(graph["inputs"]):
        graph["_plan"] = None
        return None

    if str(graph.get("batch", "")).startswith("fixed") or any(len(s) == 0 for s in shapes):
        graph["_plan"] = None   # an input without a batch to grow: nothing to plan by rows
        return None

    def at(b):
        live = []
        _execute(graph, {i: np.ones([b] + list(s[1:])) for i, s in zip(graph["inputs"], shapes)}, live)
        return np.asarray(live, dtype=np.float64)

    try:
        with np.errstate(all="ignore"):
            l1, l2, l4 = at(1), at(2), at(4)
    except GraphError:
        graph["_plan"] = None   # the graph's sizes do not follow its first dimension: it runs whole
        return None
    slope, base = l2 - l1, 2 * l1 - l2
    predicted, actual = float(np.max(base + 4 * slope)), float(np.max(l4))
    graph["_plan"] = {"base": base, "slope": slope, "linear": abs(predicted - actual) <= 0.05 * max(actual, 1.0),
                      "weights": float(sum(_nbytes(v) for v in graph["consts"].values())),
                      "per_row_peak": float(np.max(base + slope))}
    return graph["_plan"]


def batch_for(graph, budget):
    """The largest batch whose run stays within budget bytes (its weights apart): min_t (budget - a_t) / b_t."""
    p = plan(graph)
    if p is None or not p["linear"]:
        return None
    grow = p["slope"] > 0
    if not np.any(grow):
        return 1 << 30
    return int(max(0, np.floor(np.min((budget - p["base"][grow]) / p["slope"][grow]))))


def budget():
    """What a run may take: half of what the machine has available beyond its reserve, as the training's
    governor reckons it (sphere_ml.MemoryGovernor), or 256 MB where nothing answers."""
    try:
        from sphere_ml import _memory, MemoryGovernor
        m = _memory()
    except Exception:
        m = None
    if not m:
        return 256.0 * 2 ** 20
    reserve = max(MemoryGovernor.RESERVE_MIN, MemoryGovernor.RESERVE_FRACTION * m["total"])
    return 0.5 * max(32.0 * 2 ** 20, min(m["avail"] - reserve, m["commit_avail"] - MemoryGovernor.COMMIT_MIN))


def run(graph, inputs, memory=None):
    """The graph's outputs for these inputs (a dict by the names export gave them -- x, mask, input2 -- or by
    the graph's own input names; or a list in order), computed in float64 by numpy.

    The memory it takes is planned (plan()) and held to what the machine spares now (budget(), or memory
    bytes): a batch too large for it runs in chunks, when the graph's rows are independent (export checks
    it), each value freed after its last use."""
    graph = load(graph) if isinstance(graph, str) else graph
    arrays = _arrays(graph, inputs)
    n = next((a.shape[0] for a in arrays.values() if a.ndim), 0)
    chunk = batch_for(graph, budget() if memory is None else memory) if graph.get("rows_independent") else None
    if chunk is not None and chunk < 1:
        p = plan(graph)
        raise GraphError("one row of %s needs %.0f MB, where this machine spares %.0f MB now" % (
            graph["name"], p["per_row_peak"] / 2 ** 20, (budget() if memory is None else memory) / 2 ** 20))
    if chunk is None or n <= chunk:
        outs = _execute(graph, arrays)
    else:
        parts = [_execute(graph, {k: v[a:a + chunk] for k, v in arrays.items()}) for a in range(0, n, chunk)]
        outs = [np.concatenate([p[i] for p in parts], axis=0) for i in range(len(graph["outputs"]))]
    return outs[0] if len(outs) == 1 else outs


def _rows_independent(graph, example):
    """Whether each row of a batch is computed from that row alone -- a batch then runs in chunks unchanged:
    the batch's outputs against each row's, alone."""
    arrays = _arrays(graph, list(example))
    n = next((a.shape[0] for a in arrays.values() if a.ndim), 0)
    if n < 2:
        return False
    with np.errstate(all="ignore"):
        whole = _execute(graph, arrays)
        for r in (0, n - 1):
            alone = _execute(graph, {k: v[r:r + 1] for k, v in arrays.items()})
            for w, a in zip(whole, alone):
                if np.ndim(w) == 0 or w.shape[0] != n or not np.allclose(w[r:r + 1], a, rtol=1e-12, atol=1e-14,
                                                                           equal_nan=True):
                    return False
    return True


# ---- the operations, as ATen defines them

def _c(a):
    """a, contiguous, its rank kept: numpy's ascontiguousarray makes a 0-d array 1-d, which a select of a
    vector's element, a shape's size, must not be -- Java and C++ keep it a scalar."""
    a = np.asarray(a)
    return a if a.ndim == 0 or a.flags.c_contiguous else np.ascontiguousarray(a)


def _dims(d, rank):
    d = [d] if isinstance(d, int) else list(d)
    return tuple(x % rank if rank else 0 for x in d)


def _shape(sizes, x):
    sizes = [int(s) for s in sizes]
    return sizes


def _expand(x, sizes):
    sizes = [int(s) for s in sizes]
    lead = len(sizes) - x.ndim
    target = [x.shape[i - lead] if (s == -1 and i >= lead) else s for i, s in enumerate(sizes)]
    return _c(np.broadcast_to(x, target))


def _reduce(f):
    def op(x, dims=None, keepdim=False, dtype=None):
        if dims is None or (isinstance(dims, list) and not dims):
            return np.asarray(f(x, axis=None, keepdims=keepdim))
        return np.asarray(f(x, axis=_dims(dims, x.ndim), keepdims=keepdim))
    return op


def _gelu(x, approximate="none"):
    if approximate == "tanh":
        return 0.5 * x * (1.0 + np.tanh(math.sqrt(2.0 / math.pi) * (x + 0.044715 * x ** 3)))
    return 0.5 * x * (1.0 + _erf(x / math.sqrt(2.0)))


def _erf(x):
    return np.vectorize(math.erf, otypes=[np.float64])(x) if np.ndim(x) else np.float64(math.erf(float(x)))


def _layer_norm(x, shape, w, b, eps):
    axes = tuple(range(x.ndim - len(shape), x.ndim))
    mean = x.mean(axis=axes, keepdims=True)
    var = ((x - mean) ** 2).mean(axis=axes, keepdims=True)
    rstd = 1.0 / np.sqrt(var + eps)
    y = (x - mean) * rstd
    if w is not None:
        y = y * w
    if b is not None:
        y = y + b
    return (y, mean, rstd)


def _batch_norm(x, w, b, mean, var, momentum, eps):
    shape = [1, -1] + [1] * (x.ndim - 2)
    y = (x - mean.reshape(shape)) / np.sqrt(var.reshape(shape) + eps)
    if w is not None:
        y = y * w.reshape(shape)
    if b is not None:
        y = y + b.reshape(shape)
    return (y, np.zeros(0), np.zeros(0))


def _softmax(x, dim, half=False):
    e = np.exp(x - x.max(axis=dim, keepdims=True))
    return e / e.sum(axis=dim, keepdims=True)


def _log_softmax(x, dim, half=False):
    z = x - x.max(axis=dim, keepdims=True)
    return z - np.log(np.exp(z).sum(axis=dim, keepdims=True))


def _slice(x, dim=0, start=None, end=None, step=1):
    dim = dim % x.ndim
    n = x.shape[dim]
    start = 0 if start is None else start
    end = n if end is None else end
    start, end = max(-n, min(start, n)), max(-n, min(end, n))
    idx = [slice(None)] * x.ndim
    idx[dim] = slice(start, end, step)
    return _c(x[tuple(idx)])


def _topk(x, k, dim=-1, largest=True, sorted=True):
    dim = dim % x.ndim
    key = -x if largest else x
    idx = np.argsort(key, axis=dim, kind="stable")
    idx = np.take(idx, np.arange(int(k)), axis=dim)
    return (np.take_along_axis(x, idx, axis=dim), idx.astype(np.float64))


def _conv(x, w, b, stride, padding, dilation, transposed, output_padding, groups):
    if transposed:
        raise GraphError("transposed convolutions")
    nd = w.ndim - 2
    stride, padding, dilation = (list(v) * nd if len(v) == 1 else list(v) for v in (stride, padding, dilation))
    pad = [(0, 0), (0, 0)] + [(p, p) for p in padding]
    xp = np.pad(x, pad)
    n, cin = x.shape[0], x.shape[1]
    cout, cg = w.shape[0], w.shape[1]
    ks = w.shape[2:]
    out_sp = [(xp.shape[2 + i] - dilation[i] * (ks[i] - 1) - 1) // stride[i] + 1 for i in range(nd)]
    y = np.zeros([n, cout] + out_sp)
    og = cout // groups
    for g in range(groups):
        xs = xp[:, g * cg:(g + 1) * cg]
        ws = w[g * og:(g + 1) * og]
        for kidx in np.ndindex(*ks):
            sl = [slice(None), slice(None)] + [slice(kidx[i] * dilation[i], kidx[i] * dilation[i] + stride[i] *
                                                     (out_sp[i] - 1) + 1, stride[i]) for i in range(nd)]
            patch = xs[tuple(sl)]                                    # (n, cg, *out)
            wk = ws[(slice(None), slice(None)) + tuple(kidx)]        # (og, cg)
            y[:, g * og:(g + 1) * og] += np.einsum("nc...,oc->no...", patch, wk)
    if b is not None:
        y += b.reshape([1, -1] + [1] * nd)
    return y


def _ints(v):
    """An int[N] argument, given as a list or as one int (padding=0)."""
    return [int(e) for e in v] if isinstance(v, (list, tuple)) else [int(v)]


def _constant_pad(x, pad, value=0.0):
    """F.pad's constant padding: pairs from the last dimension back, a negative one cropping."""
    pad = _ints(pad)
    widths = [(0, 0)] * x.ndim
    for i in range(len(pad) // 2):
        widths[x.ndim - 1 - i] = (pad[2 * i], pad[2 * i + 1])
    x = x[tuple(slice(max(0, -lo), x.shape[d] - max(0, -hi)) for d, (lo, hi) in enumerate(widths))]
    return np.pad(x, [(max(0, lo), max(0, hi)) for lo, hi in widths], constant_values=float(value))


def _bfloat16(x):
    """c10::BFloat16's rounding, by way of float32 as PyTorch's cast goes: to nearest, ties to even."""
    f = np.ascontiguousarray(np.asarray(x, dtype=np.float32)).reshape(-1)
    u = f.view(np.uint32).astype(np.uint64)
    u = (((u + ((u >> 16) & 1) + 0x7FFF) >> 16) << 16) & 0xFFFFFFFF
    out = u.astype(np.uint32).view(np.float32).astype(np.float64)
    return np.where(np.isnan(f), np.nan, out).reshape(np.shape(x))


def _pool(x, kernel, stride, padding, dilation, ceil_mode, reduce, fill, nd=2):
    """A pooling over the last nd dimensions, its kernel, stride, padding and dilation each one int or nd."""
    if ceil_mode:
        raise GraphError("pooling with ceil_mode")
    kernel, stride, padding, dilation = _ints(kernel), _ints(stride or []), _ints(padding), _ints(dilation or [1])
    kernel, padding, dilation = (v * nd if len(v) == 1 else v for v in (kernel, padding, dilation))
    stride = kernel if not stride else stride * nd if len(stride) == 1 else stride
    xp = np.pad(x, [(0, 0)] * (x.ndim - nd) + [(p, p) for p in padding], constant_values=fill)
    out_sp = [(xp.shape[x.ndim - nd + i] - dilation[i] * (kernel[i] - 1) - 1) // stride[i] + 1 for i in range(nd)]
    windows = []
    for kidx in np.ndindex(*kernel):
        sl = [slice(None)] * (x.ndim - nd) + [slice(kidx[i] * dilation[i], kidx[i] * dilation[i] + stride[i] *
                                                    (out_sp[i] - 1) + 1, stride[i]) for i in range(nd)]
        windows.append(xp[tuple(sl)])
    return reduce(np.stack(windows), 0)


def _avg_pool(x, k, s=(), p=(0,), ceil=False, count_pad=True, div=None):
    """ATen's average: the window's sum over divisor_override, over the window's size with the padding counted,
    or over the input positions it covers without."""
    total = _pool(x, k, s, p, [1], ceil, np.sum, 0.0)
    if div is not None:
        return total / float(div)
    if count_pad or not any(_ints(p)):
        kernel = _ints(k)
        return total / float(np.prod(kernel * 2 if len(kernel) == 1 else kernel))
    return total / _pool(np.ones_like(x), k, s, p, [1], ceil, np.sum, 0.0)


def _to(x, dtype=None, *rest):
    if dtype in ("bool",):
        return (np.asarray(x) != 0).astype(np.float64)
    if dtype in ("int64", "int32", "int16", "int8", "uint8"):
        return np.trunc(np.asarray(x, dtype=np.float64))
    if dtype in ("float32",):
        return np.asarray(np.asarray(x, dtype=np.float32), dtype=np.float64)   # the model's own rounding
    if dtype in ("float16",):   # PyTorch's cast of a double to half goes by way of float32
        return np.asarray(np.asarray(np.asarray(x, dtype=np.float32), dtype=np.float16), dtype=np.float64)
    if dtype in ("bfloat16",):
        return _bfloat16(x)
    return np.array(x, dtype=np.float64)


def _b(x):
    return np.asarray(x, dtype=np.float64)


def _cmp(f):
    return lambda a, b: f(np.asarray(a), np.asarray(b)).astype(np.float64)


def _full(size, value, *rest):
    return np.full([int(s) for s in size], float(value))


def _arange(start, end=None, step=1, *rest):
    if end is None:
        start, end = 0, start
    return np.arange(start, end, step, dtype=np.float64)


def _index(x, indices):
    """x[i0, i1, ...] with index tensors (None for a whole dimension): numpy's advanced indexing, as ATen's."""
    idx = tuple(slice(None) if i is None else np.asarray(i).astype(np.int64) for i in indices)
    return np.asarray(x[idx], dtype=np.float64)


_CHECKS = ("_assert_tensor_metadata.default", "_assert_async.msg", "_assert_scalar.default",
           "_functional_assert_async.msg", "sym_constrain_range_for_size.default", "sym_constrain_range.default")

OPS = {
    # the trace's own assertions on shapes and types: nothing to compute
    **{c: (lambda *a, **k: None) for c in _CHECKS},
    # sizes, symbolic or not
    "sym_size.int": lambda x, d: int(x.shape[d]),
    "sym_numel.default": lambda x: int(x.size),
    "py.mul": lambda a, b: a * b, "py.add": lambda a, b: a + b, "py.sub": lambda a, b: a - b,
    "py.floordiv": lambda a, b: a // b, "py.truediv": lambda a, b: a / b, "py.neg": lambda a: -a,
    "py.mod": lambda a, b: a % b, "py.pow": lambda a, b: a ** b, "py.getitem": lambda t, i: t[i],
    # shapes
    "view.default": lambda x, s: _c(x).reshape([int(v) for v in s]),
    "_unsafe_view.default": lambda x, s: _c(x).reshape([int(v) for v in s]),
    "reshape.default": lambda x, s: _c(x).reshape([int(v) for v in s]),
    "permute.default": lambda x, d: _c(np.transpose(x, [v % max(x.ndim, 1) for v in d])),
    "expand.default": lambda x, s, implicit=False: _expand(x, s),
    "clone.default": lambda x, memory_format=None: np.array(x),
    "alias.default": lambda x: x,
    "detach.default": lambda x: x,
    "lift_fresh_copy.default": lambda x: np.array(x),
    "contiguous.default": lambda x, memory_format=None: _c(x),
    "squeeze.dims": lambda x, d: np.squeeze(x, axis=tuple(v % x.ndim for v in d if x.shape[v % x.ndim] == 1)),
    "squeeze.dim": lambda x, d: np.squeeze(x, axis=d % x.ndim) if x.shape[d % x.ndim] == 1 else x,
    "squeeze.default": lambda x: np.squeeze(x),
    "unsqueeze.default": lambda x, d: np.expand_dims(x, d % (x.ndim + 1)),
    "select.int": lambda x, d, i: _c(np.take(x, i, axis=d % x.ndim)),
    "slice.Tensor": _slice,
    "cat.default": lambda ts, dim=0: np.concatenate([np.atleast_1d(t) for t in ts if t.size or t.ndim > 1],
                                                    axis=dim % max(t.ndim for t in ts)),
    "stack.default": lambda ts, dim=0: np.stack(ts, axis=dim % (ts[0].ndim + 1)),
    "split_with_sizes.default": lambda x, sizes, dim=0: list(np.split(x, np.cumsum(sizes)[:-1], axis=dim % x.ndim)),
    "split.Tensor": lambda x, size, dim=0: list(np.split(x, range(size, x.shape[dim % x.ndim], size), axis=dim % x.ndim)),
    "unbind.int": lambda x, dim=0: [np.take(x, i, axis=dim % x.ndim) for i in range(x.shape[dim % x.ndim])],
    "flip.default": lambda x, dims: np.flip(x, axis=_dims(dims, x.ndim)).copy(),
    "roll.default": lambda x, shifts, dims=(): np.roll(x, shifts, axis=_dims(dims, x.ndim) if dims else None),
    "repeat.default": lambda x, r: np.tile(x, [int(v) for v in r]),
    "constant_pad_nd.default": _constant_pad,
    "_to_copy.default": lambda x, dtype=None, *rest: _to(x, dtype),
    "to.dtype": lambda x, dtype=None, *rest: _to(x, dtype),
    # creation
    "full.default": _full,
    "full_like.default": lambda x, v, *rest: np.full(np.shape(x), float(v)),
    "zeros_like.default": lambda x, *rest: np.zeros(np.shape(x)),
    "ones_like.default": lambda x, *rest: np.ones(np.shape(x)),
    "zeros.default": lambda size, *rest: np.zeros([int(s) for s in size]),
    "ones.default": lambda size, *rest: np.ones([int(s) for s in size]),
    "empty.memory_format": lambda size, *rest: np.zeros([int(s) for s in size]),
    "scalar_tensor.default": lambda v, *rest: np.float64(v) * np.ones(()),
    "arange.start_step": _arange,
    "arange.default": lambda end, *rest: np.arange(end, dtype=np.float64),
    "arange.start": lambda start, end, *rest: np.arange(start, end, dtype=np.float64),
    # products
    "mm.default": lambda a, b: a @ b,
    "addmm.default": lambda c, a, b, beta=1, alpha=1: beta * c + alpha * (a @ b),
    "bmm.default": lambda a, b: np.matmul(a, b),
    "matmul.default": lambda a, b: np.matmul(a, b),
    "baddbmm.default": lambda c, a, b, beta=1, alpha=1: beta * c + alpha * np.matmul(a, b),
    "dot.default": lambda a, b: np.asarray(np.dot(a, b)),
    # elementwise, one argument
    "relu.default": lambda x: np.maximum(x, 0.0),
    "gelu.default": _gelu,
    "sigmoid.default": lambda x: 1.0 / (1.0 + np.exp(-x)),
    "tanh.default": np.tanh, "exp.default": np.exp, "log.default": np.log, "sqrt.default": np.sqrt,
    "rsqrt.default": lambda x: 1.0 / np.sqrt(x), "neg.default": np.negative, "abs.default": np.abs,
    "sin.default": np.sin, "cos.default": np.cos, "sinh.default": np.sinh, "cosh.default": np.cosh,
    "atan.default": np.arctan, "asin.default": np.arcsin, "acos.default": np.arccos,
    "log1p.default": np.log1p, "expm1.default": np.expm1, "log2.default": np.log2, "log10.default": np.log10,
    "erf.default": _erf, "reciprocal.default": lambda x: 1.0 / x, "sign.default": np.sign,
    "floor.default": np.floor, "ceil.default": np.ceil, "round.default": np.round, "trunc.default": np.trunc,
    "silu.default": lambda x: x / (1.0 + np.exp(-x)),
    "leaky_relu.default": lambda x, s=0.01: np.where(x > 0, x, s * x),
    "hardtanh.default": lambda x, lo=-1.0, hi=1.0: np.clip(x, lo, hi),
    "elu.default": lambda x, alpha=1.0, scale=1.0, input_scale=1.0: scale * np.where(x > 0, x, alpha * (np.exp(
        input_scale * x) - 1.0)),
    "softplus.default": lambda x, beta=1.0, threshold=20.0: np.where(beta * x > threshold, x, np.log1p(np.exp(
        beta * x)) / beta),
    "clamp.default": lambda x, lo=None, hi=None: np.clip(x, -np.inf if lo is None else lo, np.inf if hi is None else hi),
    "clamp.Tensor": lambda x, lo=None, hi=None: np.clip(x, -np.inf if lo is None else lo, np.inf if hi is None else hi),
    "clamp_min.default": lambda x, lo: np.maximum(x, lo),
    "clamp_max.default": lambda x, hi: np.minimum(x, hi),
    "pow.Tensor_Scalar": lambda x, e: np.power(x, e), "pow.Tensor_Tensor": lambda x, e: np.power(x, e),
    "pow.Scalar": lambda b, e: np.power(b, e),
    "logical_not.default": lambda x: (np.asarray(x) == 0).astype(np.float64),
    "isnan.default": lambda x: np.isnan(x).astype(np.float64),
    "isinf.default": lambda x: np.isinf(x).astype(np.float64),
    # elementwise, two arguments (broadcasting)
    "add.Tensor": lambda a, b, alpha=1: _b(a) + alpha * _b(b), "add.Scalar": lambda a, b, alpha=1: _b(a) + alpha * b,
    "sub.Tensor": lambda a, b, alpha=1: _b(a) - alpha * _b(b), "sub.Scalar": lambda a, b, alpha=1: _b(a) - alpha * b,
    "rsub.Scalar": lambda a, b, alpha=1: b - alpha * _b(a), "rsub.Tensor": lambda a, b, alpha=1: _b(b) - alpha * _b(a),
    "mul.Tensor": lambda a, b: _b(a) * _b(b), "mul.Scalar": lambda a, b: _b(a) * b,
    "div.Tensor": lambda a, b: _b(a) / _b(b), "div.Scalar": lambda a, b: _b(a) / b,
    "div.Tensor_mode": lambda a, b, mode=None: np.floor(_b(a) / _b(b)) if mode == "floor" else
    np.trunc(_b(a) / _b(b)) if mode == "trunc" else _b(a) / _b(b),
    "remainder.Scalar": lambda a, b: np.mod(a, b), "remainder.Tensor": lambda a, b: np.mod(a, b),
    "fmod.Scalar": lambda a, b: np.fmod(a, b),
    "maximum.default": lambda a, b: np.maximum(a, b), "minimum.default": lambda a, b: np.minimum(a, b),
    "atan2.default": lambda a, b: np.arctan2(a, b),
    "eq.Scalar": _cmp(np.equal), "eq.Tensor": _cmp(np.equal), "ne.Scalar": _cmp(np.not_equal),
    "ne.Tensor": _cmp(np.not_equal), "lt.Scalar": _cmp(np.less), "lt.Tensor": _cmp(np.less),
    "le.Scalar": _cmp(np.less_equal), "le.Tensor": _cmp(np.less_equal), "gt.Scalar": _cmp(np.greater),
    "gt.Tensor": _cmp(np.greater), "ge.Scalar": _cmp(np.greater_equal), "ge.Tensor": _cmp(np.greater_equal),
    "logical_and.default": _cmp(lambda a, b: (a != 0) & (b != 0)),
    "logical_or.default": _cmp(lambda a, b: (a != 0) | (b != 0)),
    "logical_xor.default": _cmp(lambda a, b: (a != 0) ^ (b != 0)),
    "where.self": lambda c, a, b: np.where(np.asarray(c) != 0, a, b).astype(np.float64),
    # reductions
    "sum.dim_IntList": _reduce(np.sum), "sum.default": lambda x, dtype=None: np.asarray(np.sum(x)),
    "mean.dim": _reduce(np.mean), "mean.default": lambda x, dtype=None: np.asarray(np.mean(x)),
    "amax.default": _reduce(np.max), "amin.default": _reduce(np.min),
    "prod.dim_int": lambda x, d, keepdim=False, dtype=None: np.prod(x, axis=d % x.ndim, keepdims=keepdim),
    "max.dim": lambda x, d, keepdim=False: (np.max(x, axis=d % x.ndim, keepdims=keepdim),
                                            np.argmax(x, axis=d % x.ndim, keepdims=keepdim).astype(np.float64)),
    "min.dim": lambda x, d, keepdim=False: (np.min(x, axis=d % x.ndim, keepdims=keepdim),
                                            np.argmin(x, axis=d % x.ndim, keepdims=keepdim).astype(np.float64)),
    "max.default": lambda x: np.asarray(np.max(x)), "min.default": lambda x: np.asarray(np.min(x)),
    "argmax.default": lambda x, d=None, keepdim=False: np.argmax(x, axis=None if d is None else d % x.ndim,
                                                                  keepdims=keepdim).astype(np.float64),
    "argmin.default": lambda x, d=None, keepdim=False: np.argmin(x, axis=None if d is None else d % x.ndim,
                                                                  keepdims=keepdim).astype(np.float64),
    "var.correction": lambda x, dims=None, correction=None, keepdim=False: np.var(
        x, axis=None if dims is None else _dims(dims, x.ndim), ddof=1 if correction is None else correction,
        keepdims=keepdim),
    "any.dim": lambda x, d, keepdim=False: np.any(x != 0, axis=d % x.ndim, keepdims=keepdim).astype(np.float64),
    "any.default": lambda x: np.asarray(np.any(x != 0), dtype=np.float64),
    "cumsum.default": lambda x, d, dtype=None: np.cumsum(x, axis=d % x.ndim),
    # normalizations, softmax
    "_softmax.default": _softmax, "_log_softmax.default": _log_softmax,
    "native_layer_norm.default": _layer_norm,
    "_native_batch_norm_legit_no_training.default": _batch_norm,
    # gathering
    "gather.default": lambda x, d, idx, sparse_grad=False: np.take_along_axis(x, np.asarray(idx).astype(np.int64),
                                                                               axis=d % x.ndim),
    "index_select.default": lambda x, d, idx: np.take(x, np.asarray(idx).astype(np.int64), axis=d % x.ndim),
    "embedding.default": lambda w, idx, *rest: w[np.asarray(idx).astype(np.int64)],
    "index.Tensor": _index,
    "topk.default": _topk,
    "sort.default": lambda x, d=-1, descending=False: (lambda i: (np.take_along_axis(x, i, d % x.ndim),
                                                                  i.astype(np.float64)))(
        np.argsort(-x if descending else x, axis=d % x.ndim, kind="stable")),
    # images
    "convolution.default": _conv,
    "max_pool2d_with_indices.default": lambda x, k, s=(), p=(0,), d=(1,), ceil=False: (
        _pool(x, k, s, p, d, ceil, np.max, -np.inf), np.zeros(0)),
    "avg_pool2d.default": _avg_pool,
    "_adaptive_avg_pool2d.default": lambda x, out: x.mean(axis=(-2, -1), keepdims=True) if list(out) == [1, 1]
    else (_ for _ in ()).throw(GraphError("adaptive pooling to %s" % (out,))),
}


# ---------------------------------------------------------------------------
# Checks: PyTorch against the graph, at batch sizes the trace never saw
# ---------------------------------------------------------------------------

def _batched(example, b, rng):
    """The example's inputs at another batch size: its rows drawn again, so a mask stays a mask."""
    import torch
    n = example[0].shape[0]
    rows = torch.as_tensor(rng.integers(0, n, b))
    return tuple(e[rows] if torch.is_tensor(e) and e.dim() > 0 else e for e in example)


def check_against(model, graph, example, sizes=(1, 3, 17), seed=0):
    """PyTorch's own outputs against the graph's in numpy, at batch sizes other than the trace's: the largest
    difference, relative to the outputs' scale, and where the graph rounds to float32 as the model does, the
    float32 rounding it is held to instead of float64's."""
    import torch
    rng = np.random.default_rng(seed)
    worst, scale = 0.0, 0.0
    with torch.no_grad():
        for b in sizes:
            xs = _batched(example, b, rng)
            ref = model(*xs)
            ref = ref if isinstance(ref, (list, tuple)) else [ref]
            mine = run(graph, list(xs))
            mine = mine if isinstance(mine, list) else [mine]
            for r, g in zip(ref, mine):
                r = r.detach().double().numpy()
                worst = max(worst, float(np.max(np.abs(r - g))) if r.size else 0.0)
                scale = max(scale, float(np.max(np.abs(r))) if r.size else 0.0)
    eps = float(np.finfo(np.float32 if graph.get("precision") == "mixed" else np.float64).eps)
    units = worst / max(eps * max(scale, 1e-30), 1e-300)
    return {"sizes": list(sizes), "max_abs": worst, "scale": scale, "eps_units": units,
            "same": units <= 4096, "precision": graph.get("precision", "float64")}


def reference(graph, inputs, name=None):
    """The graph's inputs and outputs written out (bridge/ml/<name>.graphref.spx), for Java and C++ to be held
    to numpy on the same values."""
    graph = load(graph) if isinstance(graph, str) else graph
    if isinstance(inputs, (list, tuple)):
        inputs = dict(zip(graph["input_names"], inputs))
    out = run(graph, inputs)
    outs = out if isinstance(out, list) else [out]
    path = os.path.join(spx.bridge_folder(), "ml", (name or graph["name"]) + ".graphref.spx")
    meta = {"Kind": "sphere-graph-reference", "Inputs": ",".join(graph["input_names"]), "Outputs": len(outs)}
    sections = []
    for k, key in enumerate(graph["input_names"]):
        v = inputs[key]
        v = np.asarray(v.detach().cpu().double().numpy() if hasattr(v, "detach") else v, dtype=np.float64)
        sections.append(("in.%d" % k, v.ravel()))
        meta["Shape.in.%d" % k] = ",".join(str(s) for s in v.shape)
    for i, o in enumerate(outs):
        sections.append(("out.%d" % i, np.asarray(o, dtype=np.float64).ravel()))
        meta["Shape.out.%d" % i] = ",".join(str(s) for s in np.shape(o))
    from sphere_ml import write_spx
    write_spx(path, "graph reference", sections, meta)
    return path


def describe(graph):
    graph = load(graph) if isinstance(graph, str) else graph
    ops = {}
    for nd in graph["nodes"]:
        ops[nd["op"]] = ops.get(nd["op"], 0) + 1
    return {"name": graph["name"], "inputs": graph["input_names"], "outputs": len(graph["outputs"]),
            "nodes": len(graph["nodes"]), "weights": int(sum(np.size(v) for v in graph["consts"].values())),
            "ops": ops, "feeds": graph["feeds"], "framework": graph["framework"], "precision": graph["precision"]}


# ---------------------------------------------------------------------------
# An export asked by Sphere (sphere_ml.graph_export), in a Python of its own
# ---------------------------------------------------------------------------

def _keras_example(model, shapes, rows=8):
    """Inputs for tracing a Keras model: rows of the shapes its file declares (or those given, a list of row
    shapes), as float32 tensors."""
    import torch
    if not shapes:
        shapes = []
        for t in model.inputs:
            row = list(t.shape)[1:]
            if any(s is None for s in row):
                raise GraphError("input %s of the model has a free size (%s): give the rows' shapes (--shape 16,7;16)"
                                 % (getattr(t, "name", "?"), list(t.shape)))
            shapes.append(row)
    rng = np.random.default_rng(0)
    return tuple(torch.tensor(rng.normal(size=[rows] + [int(s) for s in row]), dtype=torch.float32) for row in shapes)


def _tf_job(job):
    """A TensorFlow model read by sphere_tf: numpy only, neither TensorFlow nor PyTorch imported."""
    import sphere_tf
    g = sphere_tf.load(job["path"], name=job.get("name"), inputs=job.get("inputs"), outputs=job.get("outputs"),
                       shapes=job.get("shapes"), feeds=job.get("feeds"), fixed=job.get("fixed"))
    d = sphere_tf.describe(g)
    tf = g["tf"]
    return {"name": g["name"], "path": g["path"], "reference": g["reference"], "nodes": d["nodes"], "weights": d["weights"],
            "ops": d["ops"], "inputs": d["inputs"], "feeds": d["feeds"], "precision": d["precision"], "framework": d["framework"],
            "batch": g.get("batch"), "rows_independent": bool(g.get("rows_independent")), "memory": g.get("memory"),
            "example_shapes": g.get("example_shapes"), "optimized": g.get("optimized"),
            "tf": {"kind": tf["kind"], "nodes": sum(d["tf_ops"].values()), "kinds": len(d["tf_ops"]), "variables": tf["variables"],
                   "inputs": tf["inputs"], "outputs": tf["outputs"], "consistent": tf["consistent"], "control": tf.get("control", []),
                   "scalars": tf.get("scalars", []), "fixed": tf.get("fixed", {}), "flags": tf.get("flags", []),
                   "flags_unbatch": tf.get("flags_unbatch", False), "batch_free": tf["batch_free"]}}


def _run_job(job):
    kind, name, feeds, framework = job.get("kind", "script"), job.get("name"), job.get("feeds"), None
    if kind == "tf":
        return _tf_job(job)
    import torch
    if kind == "script":
        import runpy
        scope = runpy.run_path(job["script"], run_name="__sphere_export__")
        model, example = scope.get("model"), scope.get("example")
        if model is None or example is None:
            raise GraphError("%s defines no `model` and `example` (the tuple of its inputs, a batch of them)" % job["script"])
        name = name or scope.get("name") or os.path.splitext(os.path.basename(job["script"]))[0]
        feeds = feeds or scope.get("feeds")
        framework = scope.get("framework")
    elif kind == "keras":
        import keras
        if keras.backend.backend() != "torch":
            raise GraphError("Keras runs here on %s: a graph needs its torch backend (KERAS_BACKEND=torch)" % keras.backend.backend())
        model = keras.saving.load_model(job["path"], compile=False)
        example = _keras_example(model, job.get("shapes"))
        name = name or os.path.splitext(os.path.basename(job["path"]))[0]
        framework = "keras %s (torch backend), from %s" % (keras.__version__, os.path.basename(job["path"]))
    else:
        raise GraphError("an export of kind " + kind)
    if torch.is_tensor(example):
        example = (example,)
    import gc
    gc.collect()   # what the script left in reference cycles (a Keras training leaves many) freed before the trace
    g = export(model, tuple(example), name, feeds=feeds, framework=framework)
    ref = reference(g, [e[:8] if torch.is_tensor(e) and e.dim() else e for e in example])
    d = describe(g)
    return {"name": name, "path": g["path"], "reference": ref, "nodes": d["nodes"], "weights": d["weights"], "ops": d["ops"],
            "inputs": d["inputs"], "feeds": d["feeds"], "precision": d["precision"], "framework": d["framework"],
            "batch": g.get("batch"), "rows_independent": bool(g.get("rows_independent")), "memory": g.get("memory"),
            "check": g.get("check"), "example_shapes": g.get("example_shapes"), "optimized": g.get("optimized")}


def _job(path):
    """The export of the job's file, its result -- or what failed -- written to the file the job names."""
    import json
    import traceback
    with open(path, encoding="utf-8") as fh:
        job = json.load(fh)
    try:
        out = _run_job(job)
    except BaseException as e:
        out = {"error": "%s: %s" % (type(e).__name__, e), "trace": traceback.format_exc()[-4000:]}
    with open(job["result"], "w", encoding="utf-8") as fh:
        json.dump(out, fh, default=str)


if __name__ == "__main__":
    if len(sys.argv) > 2 and sys.argv[1] == "job":
        _job(sys.argv[2])
    else:
        print("sphere_graph %d: %d operations" % (FORMAT, len(OPS)))

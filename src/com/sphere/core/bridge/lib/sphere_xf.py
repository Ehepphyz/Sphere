"""One model, every framework: Sphere's portable graphs run -- and train -- in PyTorch, TensorFlow and JAX.

A graph is what Sphere exports from PyTorch (torch.export, core ATen) or imports from TensorFlow and Keras
(sphere_tf): operations named as ATen names them, their arguments in the order of ATen's schemas. numpy's
interpreter (sphere_graph) is its reference, Java's and C++'s runtimes are held to it. Here the same graph is
given to the frameworks themselves:

- PyTorch: every node replayed through torch.ops.aten, its arguments split back into positional and keyword ones
  by the operator's own schema -- the graph becomes an nn.Module whose weights are Parameters (the trainable ones
  as export named them), differentiable by autograd, traceable into TorchScript (libtorch, C++) and torch.export;
- JAX: every node by jax.numpy and jax.lax -- a pure function of (weights, inputs), for jax.jit, jax.grad, vmap;
- TensorFlow: every node by tf.experimental.numpy and tf.nn -- a tf.Module whose weights are tf.Variables, its
  call a tf.function of a free batch (a SavedModel, served or loaded by TensorFlow's C++), compiled by XLA when
  asked, differentiated by GradientTape.

So a model trained in PyTorch is deployed or fine-tuned in TensorFlow, a Keras or TensorFlow model in PyTorch,
either in JAX. Each is held, not trusted: the outputs of every framework against numpy's in units of the double's
epsilon, the gradients of a random projection of the outputs against each other and against finite differences
of the reference along random directions, the SavedModel and the TorchScript reloaded and run again. The engines
are timed on this machine, and the fastest that agrees with the reference is named.
"""
import math
import os
import time

import numpy as np

import sphere_graph as sg

EPS = float(np.finfo(np.float64).eps)
_INDEX_NAMES = ("indices", "index")
_MEMORY_FORMATS = {"contiguous_format", "preserve_format", "channels_last", "channels_last_3d"}


# ----------------------------------------------------------------------------------------------------------------
# PyTorch: the nodes replayed through torch.ops.aten
# ----------------------------------------------------------------------------------------------------------------

def _torch_dtypes():
    import torch
    return {"float32": torch.float32, "float64": torch.float64, "float16": torch.float16, "bfloat16": torch.bfloat16,
            "int64": torch.int64, "int32": torch.int32, "int16": torch.int16, "int8": torch.int8,
            "uint8": torch.uint8, "bool": torch.bool, "complex64": torch.complex64, "complex128": torch.complex128}


class _TorchCall:
    """One node's operator and how its encoded arguments become a call: the schema's keyword-only arguments by
    name, dtypes and memory formats from their names, index arguments made integers, conditions booleans."""

    def __init__(self, op):
        import torch
        self.op = op
        self.py = sg.OPS.get(op) if op.startswith("py.") or op in sg._CHECKS else None
        if self.py is not None:
            return
        packet, _, overload = op.partition(".")
        if op in ("view.default", "_unsafe_view.default"):
            packet, overload = "reshape", "default"   # the reference's view: of a contiguous copy when it must be
        try:
            self.fn = getattr(getattr(torch.ops.aten, packet), overload or "default")
        except AttributeError:
            raise sg.GraphError("PyTorch has no aten.%s" % op)
        self.args = list(self.fn._schema.arguments)
        self.dtypes = _torch_dtypes()

    def __call__(self, vals):
        import torch
        if self.py is not None:
            return self.py(*vals)
        pos, kw = [], {}
        for i, v in enumerate(vals):
            a = self.args[i] if i < len(self.args) else None
            if a is not None:
                if isinstance(v, str) and (a.name == "dtype" or "ScalarType" in str(a.type)):
                    v = self.dtypes[v]
                elif isinstance(v, str) and v in _MEMORY_FORMATS:
                    v = getattr(torch, v)
                if a.name in _INDEX_NAMES:
                    v = _long(v)
                elif a.name in ("condition", "mask") and torch.is_tensor(v) and v.dtype != torch.bool:
                    v = v != 0
            if a is not None and a.kwarg_only:
                kw[a.name] = v
            else:
                pos.append(v)
        return _float64(self.fn(*pos, **kw))


def _float64(v):
    """The reference's semantics: a cast to float32 rounds, and the value stays a float64 (a Keras model casts its inputs so,
    and its weights here are float64)."""
    import torch
    if torch.is_tensor(v):
        return v.double() if v.is_floating_point() and v.dtype != torch.float64 else v
    if isinstance(v, (list, tuple)):
        return type(v)(_float64(e) for e in v)
    return v


def _long(v):
    import torch
    if torch.is_tensor(v) and v.is_floating_point():
        return v.long()
    if isinstance(v, (list, tuple)):
        return type(v)(_long(e) for e in v)
    return v


def _torch_module():
    import torch

    class TorchGraph(torch.nn.Module):
        """A portable graph as a PyTorch module: its trainable weights Parameters (by the names export gave them,
        dots made underscores), the others buffers; float64 throughout, as the graph's reference computes."""

        def __init__(self, graph, trainable=True):
            super().__init__()
            self.graph_name = graph["name"]
            self._inputs, self._outputs = list(graph["inputs"]), list(graph["outputs"])
            self._nodes = [(nd["name"], _TorchCall(nd["op"]), nd["enc"]) for nd in graph["nodes"]]
            self._blocks = {k: ([(n["name"], _TorchCall(n["op"]), n["enc"]) for n in b["nodes"]], b["inputs"], b["outputs"])
                            for k, b in (graph.get("blocks") or {}).items()}
            self._const_names = {}
            params = graph.get("params") or {}
            for k, v in graph["consts"].items():
                attr = "c_" + k
                t = torch.as_tensor(np.asarray(v, dtype=np.float64))
                if trainable and k in params:
                    self.register_parameter(attr, torch.nn.Parameter(t))
                else:
                    self.register_buffer(attr, t)
                self._const_names[k] = attr
            self.param_names = {self._const_names[k]: params[k] for k in params if k in self._const_names}

        def _env(self):
            return {k: getattr(self, a) for k, a in self._const_names.items()}

        def _run(self, nodes, env):
            for name, call, enc in nodes:
                if call.op == "sphere.while":
                    raise sg.GraphError("loops run in PyTorch through the block runner")
                env[name] = call(sg._decode(enc, env))
            return env

        def forward(self, *inputs):
            env = self._env()
            env.update(zip(self._inputs, inputs))
            for name, call, enc in self._nodes:
                vals = sg._decode(enc, env)
                if call.op in ("sphere.while", "sphere.case"):
                    env[name] = self._control(call.op, vals)
                else:
                    env[name] = call(vals)
            outs = tuple(env[o] for o in self._outputs)
            return outs[0] if len(outs) == 1 else outs

        def _block(self, name, values):
            nodes, ins, outs = self._blocks[name]
            env = self._env()
            env.update(zip(ins, values))
            self._run(nodes, env)
            return [env[o] for o in outs]

        def _control(self, op, args):
            if op == "sphere.while":
                vals, n = list(args[2]), 0
                while bool(self._block(args[0], vals)[0].reshape(-1)[0] != 0):
                    vals = self._block(args[1], vals)
                    n += 1
                    if n >= sg.LOOP_LIMIT:
                        raise sg.GraphError("a loop past %d iterations" % sg.LOOP_LIMIT)
                return vals
            k = int(args[0].reshape(-1)[0]) if hasattr(args[0], "reshape") else int(args[0])
            branches = args[1]
            return self._block(branches[k if 0 <= k < len(branches) else len(branches) - 1], list(args[2]))

    return TorchGraph


def to_torch(graph, trainable=True):
    """The graph as a torch.nn.Module (float64)."""
    graph = sg.load(graph) if isinstance(graph, str) else graph
    return _torch_module()(graph, trainable)


# ----------------------------------------------------------------------------------------------------------------
# JAX and TensorFlow: the reference's semantics -- float64, booleans as 0 and 1 -- in their NumPy
# ----------------------------------------------------------------------------------------------------------------

class _Backend:
    """What differs between jax.numpy and TensorFlow's NumPy: the namespace, the special functions, the sizes
    that TensorFlow may know only when it runs, the convolutions and poolings, the stable sort."""
    name = ""
    xp = None

    def size(self, x, d):
        return x.shape[d]

    def f64(self, x):
        return self.xp.asarray(x, dtype=self.xp.float64)


class _Jax(_Backend):
    name = "jax"

    def __init__(self):
        import jax
        jax.config.update("jax_enable_x64", True)
        import jax.numpy as jnp
        import jax.scipy.special as jss
        self.jax, self.xp, self.lax, self.erf = jax, jnp, jax.lax, jss.erf
        self.constant = jax.lax.stop_gradient

    def argsort(self, x, axis):
        return self.xp.argsort(x, axis=axis, stable=True)

    def conv(self, x, w, stride, padding, dilation, groups):
        nd = len(w.shape) - 2
        dn = {1: ("NCH", "OIH", "NCH"), 2: ("NCHW", "OIHW", "NCHW"), 3: ("NCDHW", "OIDHW", "NCDHW")}[nd]
        return self.lax.conv_general_dilated(x, w, list(stride), [(p, p) for p in padding], rhs_dilation=list(dilation),
                                             dimension_numbers=dn, feature_group_count=groups,
                                             precision=self.lax.Precision.HIGHEST)

    def pool(self, x, kernel, stride, padding, dilation, kind, nd):
        lead = len(x.shape) - nd
        init = -np.inf if kind == "max" else 0.0
        op = self.lax.max if kind == "max" else self.lax.add
        return self.lax.reduce_window(x, init, op, (1,) * lead + tuple(kernel), (1,) * lead + tuple(stride),
                                      [(0, 0)] * lead + [(p, p) for p in padding],
                                      window_dilation=(1,) * lead + tuple(dilation))


class _TF(_Backend):
    name = "tensorflow"

    def __init__(self):
        import tensorflow as tf
        self.tf = tf
        self.xp = tf.experimental.numpy
        self.xp.experimental_enable_numpy_behavior(prefer_float32=False)
        self.constant = tf.stop_gradient

    def erf(self, x):
        tf = self.tf
        return tf.where(tf.abs(x) > 27.0, tf.sign(x), tf.math.erf(x))   # tf.math.erf is NaN where x * x overflows

    def fmod(self, a, b):
        """The exact remainder (a - b * trunc(a / b) loses it for a large quotient), with the gradient TensorFlow has none for."""
        tf = self.tf

        @tf.custom_gradient
        def mod(x, y):
            def grad(dv):
                q = x / y
                return dv, -dv * tf.sign(q) * tf.floor(tf.abs(q))
            return tf.raw_ops.Mod(x=x, y=y), grad

        a, b = tf.convert_to_tensor(a, dtype=tf.float64), tf.convert_to_tensor(b, dtype=tf.float64)
        s = tf.broadcast_dynamic_shape(tf.shape(a), tf.shape(b))
        return mod(tf.broadcast_to(a, s), tf.broadcast_to(b, s))

    def size(self, x, d):
        s = x.shape[d]
        return s if s is not None else self.tf.shape(x)[d]

    def argsort(self, x, axis):
        return self.tf.argsort(x, axis=axis, stable=True)

    def _nhwc(self, x, nd):
        return self.tf.transpose(x, [0] + list(range(2, 2 + nd)) + [1])

    def _nchw(self, x, nd):
        return self.tf.transpose(x, [0, 1 + nd] + list(range(1, 1 + nd)))

    def conv(self, x, w, stride, padding, dilation, groups):
        tf = self.tf
        nd = len(w.shape) - 2
        every = list(stride) if max(stride) > 1 and max(dilation) > 1 else None   # TensorFlow refuses the two together: every s-th of the outputs at stride 1
        stride = [1] * nd if every else stride
        xh = tf.pad(self._nhwc(x, nd), [[0, 0]] + [[p, p] for p in padding] + [[0, 0]])
        wk = tf.transpose(w, list(range(2, 2 + nd)) + [1, 0])          # (k..., cin/groups, cout)
        if groups == 1:
            y = tf.nn.convolution(xh, wk, strides=list(stride), padding="VALID", dilations=list(dilation))
        else:
            cg, og = x.shape[1] // groups, w.shape[0] // groups
            y = tf.concat([tf.nn.convolution(xh[..., g * cg:(g + 1) * cg], wk[..., g * og:(g + 1) * og],
                                             strides=list(stride), padding="VALID", dilations=list(dilation))
                           for g in range(groups)], -1)
        y = self._nchw(y, nd)
        return y[(slice(None), slice(None)) + tuple(slice(None, None, s) for s in every)] if every else y

    def pool(self, x, kernel, stride, padding, dilation, kind, nd):
        tf = self.tf
        if kind != "max":
            return self._pool(x, kernel, stride, padding, dilation, kind, nd)
        nan = tf.math.is_nan(x)   # TensorFlow's looks past NaN, PyTorch's does not
        seen = self._pool(tf.cast(nan, x.dtype), kernel, stride, padding, dilation, kind, nd)
        y = self._pool(tf.where(nan, tf.constant(-np.inf, x.dtype), x), kernel, stride, padding, dilation, kind, nd)
        return tf.where(seen > 0, tf.constant(np.nan, x.dtype), y)

    def _pool(self, x, kernel, stride, padding, dilation, kind, nd):
        tf = self.tf
        fill = -np.inf if kind == "max" else 0.0
        lead = len(x.shape) - nd
        if lead != 2:
            raise sg.GraphError("pooling over %d leading dimensions in TensorFlow" % lead)
        xh = tf.pad(self._nhwc(x, nd), [[0, 0]] + [[p, p] for p in padding] + [[0, 0]], constant_values=fill)
        y = tf.nn.pool(xh, list(kernel), "MAX" if kind == "max" else "AVG", strides=list(stride), padding="VALID",
                       dilations=list(dilation))
        if kind != "max":
            y = y * float(np.prod(kernel))   # a sum, as the reference's pooling gives it
        else:
            y = tf.where(tf.equal(y, x.dtype.min), tf.constant(-np.inf, x.dtype), y)   # its maximum of nothing but -inf is the lowest number
        return self._nchw(y, nd)


def _xp_ops(B):
    """The operations of the graphs, as the reference defines them, in the backend's NumPy."""
    xp = B.xp
    f64 = B.f64
    # what TensorFlow's NumPy lacks, from what it has
    trunc = getattr(xp, "trunc", None) or (lambda x: xp.sign(x) * xp.floor(xp.abs(x)))
    fmod = getattr(B, "fmod", None) or xp.fmod

    def ndim(x):
        return len(x.shape)

    def dims(d, rank):
        d = [d] if isinstance(d, int) else list(d)
        return tuple(v % rank if rank else 0 for v in d)

    def ints(v):
        return [int(e) for e in v] if isinstance(v, (list, tuple)) else [int(v)]

    def sizes(s):
        return [s_ if not isinstance(s_, float) else int(s_) for s_ in s]

    def reduce(f):
        def op(x, d=None, keepdim=False, dtype=None):
            if d is None or (isinstance(d, list) and not d) or ndim(x) == 0:
                return f(x, axis=None, keepdims=keepdim)
            return f(x, axis=dims(d, ndim(x)), keepdims=keepdim)
        return op

    def lifted(op):
        """An operation along a dimension, on a 0-d tensor too: PyTorch takes one as a vector of one element."""
        def f(x, d=None, *rest, **kw):
            if ndim(x) > 0:
                return op(x, d, *rest, **kw)
            out = op(xp.reshape(x, [1]), None if d is None else 0, *rest, **kw)
            return tuple(xp.reshape(o, []) for o in out) if isinstance(out, tuple) else xp.reshape(out, [])
        return f

    def expand(x, s, implicit=False):
        s = sizes(s)
        lead = len(s) - ndim(x)
        target = [B.size(x, i - lead) if (isinstance(v, int) and v == -1 and i >= lead) else v for i, v in enumerate(s)]
        return xp.broadcast_to(x, target)

    def slice_(x, dim=0, start=None, end=None, step=1):
        dim = dim % ndim(x)
        n = x.shape[dim]
        start = 0 if start is None else start
        if end is not None and n is not None:
            start, end = max(-n, min(start, n)), max(-n, min(end, n))
        elif n is not None:
            start = max(-n, min(start, n))
        idx = [slice(None)] * ndim(x)
        idx[dim] = slice(start, None if (end is None or (n is not None and end >= n)) else end, step)
        return x[tuple(idx)]

    def cmp(f):
        return lambda a, b: f64(f(f64(a), f64(b)))

    def clamp(x, lo=None, hi=None):
        """By selection, not by a mask multiplied: where it clamps the gradient is 0, as PyTorch's, even when
        what follows has an infinite one (a square root at 0) -- jax.numpy.clip's would be inf * 0 = NaN."""
        if lo is not None and hi is not None:   # the two bounds equal, PyTorch gives neither a gradient
            lo = xp.where(f64(lo) == f64(hi), B.constant(f64(lo)), f64(lo))
        if lo is not None:
            lo = f64(lo)
            x = xp.where(xp.logical_or(x < lo, xp.isnan(lo)), lo + xp.zeros_like(x), x)
        if hi is not None:
            hi = f64(hi)
            x = xp.where(xp.logical_or(x > hi, xp.isnan(hi)), hi + xp.zeros_like(x), x)
        return x

    def hardtanh(x, lo=-1.0, hi=1.0):
        """clamp, but with no gradient at the two bounds themselves."""
        return xp.where(xp.logical_and(x > lo, x < hi), x, B.constant(clamp(x, lo, hi)))

    def power(x, e):
        """With PyTorch's derivative where the textbook's is 0 * inf: x ** 0 gives the base nothing, even at 0."""
        x, e = f64(x), f64(e)
        zero = xp.logical_and(x == 0, e == 0)
        return xp.where(zero, xp.ones_like(x), xp.power(xp.where(zero, xp.ones_like(x), x), e))

    def atan2(a, b):
        """No gradient at the origin, as in PyTorch: the textbook's is 0 / 0."""
        a, b = f64(a), f64(b)
        zero = xp.logical_and(a == 0, b == 0)
        return xp.where(zero, B.constant(xp.arctan2(a, b)), xp.arctan2(xp.where(zero, xp.ones_like(a), a), xp.where(zero, xp.ones_like(b), b)))

    def select(x, d, i):
        d = d % ndim(x)
        return xp.take(x, i + B.size(x, d) if isinstance(i, int) and i < 0 else i, axis=d)   # TensorFlow's take clips a negative index to 0

    def balanced(larger):
        """The larger or the smaller of two; where they are equal each has half the gradient, as in PyTorch (TensorFlow gives it all to one)."""
        def op(a, b):
            a, b = f64(a), f64(b)
            return xp.where(a > b if larger else a < b, a, xp.where(a < b if larger else a > b, b, 0.5 * a + 0.5 * b))
        return op

    def floordiv(a, b):
        """PyTorch's, from the exact remainder: the floor of a / b is wrong when the quotient rounds, or is infinite. No gradient, as there."""
        a, b = B.constant(f64(a)), B.constant(f64(b))
        mod = fmod(a, b)
        div = (a - mod) / b
        div = xp.where(xp.logical_and(mod != 0, (b < 0) != (mod < 0)), div - 1.0, div)
        low = xp.floor(div)
        low = xp.where(div - low > 0.5, low + 1.0, low)
        return xp.where(b == 0, a / b, xp.where(div == 0, (a / b) * 0.0, low))

    def first(f, x, d=None, keepdim=False):
        """Where the extreme is: its first place, the first NaN's if there is one, as in PyTorch (TensorFlow's looks past NaN and has no keepdims)."""
        rank = ndim(x)
        flat = d is None or rank == 0
        v = xp.reshape(x, [-1]) if flat else x
        axis = 0 if flat else d % rank
        nan = xp.isnan(v)
        at = xp.where(xp.any(nan, axis=axis), xp.argmax(f64(nan), axis=axis), f(xp.where(nan, xp.zeros_like(v), v), axis=axis))
        if not keepdim or rank == 0:
            return at
        return xp.reshape(at, [1] * rank) if flat else xp.expand_dims(at, axis)

    def extreme(f):
        """The extreme along d and where it is: the gradient goes to the element chosen, as in PyTorch (xp.max would share it among ties)."""
        def op(x, d, keepdim=False):
            if ndim(x) == 0:
                return (x, xp.zeros_like(x))
            axis = d % ndim(x)
            at = first(f, x, axis, True)
            v = xp.take_along_axis(x, at, axis=axis)
            return (v, f64(at)) if keepdim else (xp.squeeze(v, axis=axis), f64(xp.squeeze(at, axis=axis)))
        return op

    def var(x, ds=None, correction=None, keepdim=False):
        axes = None if ds is None or (isinstance(ds, (list, tuple)) and not ds) or ndim(x) == 0 else dims(ds, ndim(x))
        n = 1.0
        for a in (range(ndim(x)) if axes is None else axes):
            n = n * f64(B.size(x, a))
        mean = xp.mean(x, axis=axes, keepdims=True)
        return xp.sum((x - mean) ** 2, axis=axes, keepdims=keepdim) / xp.maximum(n - (1.0 if correction is None else float(correction)), 0.0)

    def softmax(x, d, half=False):
        e = xp.exp(x - xp.max(x, axis=d, keepdims=True))
        return e / xp.sum(e, axis=d, keepdims=True)

    def log_softmax(x, d, half=False):
        z = x - xp.max(x, axis=d, keepdims=True)
        return z - xp.log(xp.sum(xp.exp(z), axis=d, keepdims=True))

    def layer_norm(x, shape, w, b, eps):
        axes = tuple(range(ndim(x) - len(shape), ndim(x)))
        mean = xp.mean(x, axis=axes, keepdims=True)
        var = xp.mean((x - mean) ** 2, axis=axes, keepdims=True)
        rstd = 1.0 / xp.sqrt(var + eps)
        y = (x - mean) * rstd
        if w is not None:
            y = y * w
        if b is not None:
            y = y + b
        return (y, mean, rstd)

    def batch_norm(x, w, b, mean, var, momentum, eps):
        shape = [1, -1] + [1] * (ndim(x) - 2)
        # as PyTorch computes it, a linear and a constant term: the same number, and the same NaN from an infinite weight
        alpha = (1.0 if w is None else xp.reshape(w, shape)) / xp.sqrt(xp.reshape(var, shape) + eps)
        beta = (0.0 if b is None else xp.reshape(b, shape)) - xp.reshape(mean, shape) * alpha
        return (x * alpha + beta, xp.zeros(0), xp.zeros(0))

    def to(x, dtype=None, *rest):
        if dtype == "bool":
            return f64(f64(x) != 0)
        if dtype in ("int64", "int32", "int16", "int8", "uint8"):
            return trunc(f64(x))
        if dtype == "float32":
            return f64(xp.asarray(x, dtype=xp.float32))
        if dtype == "float16":
            return f64(xp.asarray(xp.asarray(x, dtype=xp.float32), dtype=xp.float16))
        return f64(x)

    def cat(ts, d=0):
        ts = [t for t in ts if ndim(t) > 1 or (t.shape[0] is None or t.shape[0] > 0)]
        return xp.concatenate([xp.atleast_1d(t) for t in ts], axis=d % max(ndim(t) for t in ts))

    def gelu(x, approximate="none"):
        if approximate == "tanh":
            return 0.5 * x * (1.0 + xp.tanh(math.sqrt(2.0 / math.pi) * (x + 0.044715 * x ** 3)))
        return 0.5 * x * (1.0 + B.erf(x / math.sqrt(2.0)))

    def order(x, d, descending):
        """The permutation of PyTorch's sort: stable, NaN greater than everything. Two stable sorts, the last deciding first."""
        nan = xp.isnan(x)
        by = B.argsort(xp.where(nan, xp.zeros_like(x), -x if descending else x), d)
        last = f64(xp.logical_not(nan) if descending else nan)
        return xp.take_along_axis(by, B.argsort(xp.take_along_axis(last, by, axis=d), d), axis=d)

    def topk(x, k, d=-1, largest=True, sorted=True):
        d = d % ndim(x)
        idx = xp.take(order(x, d, largest), xp.arange(int(k)), axis=d)
        return (xp.take_along_axis(x, idx, axis=d), f64(idx))

    def sort(x, d=-1, descending=False):
        d = d % ndim(x)
        i = order(x, d, descending)
        return (xp.take_along_axis(x, i, axis=d), f64(i))

    def conv(x, w, b, stride, padding, dilation, transposed, output_padding, groups):
        if transposed:
            raise sg.GraphError("transposed convolutions")
        nd = ndim(w) - 2
        stride, padding, dilation = (ints(v) * nd if len(ints(v)) == 1 else ints(v) for v in (stride, padding, dilation))
        y = B.conv(x, w, stride, padding, dilation, int(groups))
        if b is not None:
            y = y + xp.reshape(b, [1, -1] + [1] * nd)
        return y

    def pool(x, kernel, stride, padding, dilation, ceil, kind, nd=2):
        if ceil:
            raise sg.GraphError("pooling with ceil_mode")
        kernel, padding, dilation = (ints(v) * nd if len(ints(v)) == 1 else ints(v) for v in (kernel, padding, dilation or [1]))
        s = ints(stride or [])
        stride = kernel if not s else s * nd if len(s) == 1 else s
        return B.pool(x, kernel, stride, padding, dilation, kind, nd)

    def avg_pool(x, k, s=(), p=(0,), ceil=False, count_pad=True, div=None):
        total = pool(x, k, s, p, [1], ceil, "sum")
        if div is not None:
            return total / float(div)
        if count_pad or not any(ints(p)):
            kernel = ints(k)
            return total / float(np.prod(kernel * 2 if len(kernel) == 1 else kernel))
        return total / pool(xp.ones_like(x), k, s, p, [1], ceil, "sum")

    def constant_pad(x, pad, value=0.0):
        pad = ints(pad)
        widths = [(0, 0)] * ndim(x)
        for i in range(len(pad) // 2):
            widths[ndim(x) - 1 - i] = (pad[2 * i], pad[2 * i + 1])
        x = x[tuple(slice(max(0, -lo), (x.shape[d] - max(0, -hi)) if hi < 0 else None) for d, (lo, hi) in enumerate(widths))]
        return xp.pad(x, [(max(0, lo), max(0, hi)) for lo, hi in widths], mode="constant", constant_values=float(value))

    def index(x, indices):
        idx = tuple(slice(None) if i is None else xp.asarray(i, dtype=xp.int64) for i in indices)
        return x[idx]

    def i64(v):
        return xp.asarray(v, dtype=xp.int64)

    def split(x, size, d=0):
        d = d % ndim(x)
        n = x.shape[d]
        return [slice_(x, d, a, a + size) for a in range(0, n, size)]

    def split_sizes(x, ss, d=0):
        d, out, a = d % ndim(x), [], 0
        for s in ss:
            out.append(slice_(x, d, a, a + int(s)))
            a += int(s)
        return out

    ops = {
        **{c: (lambda *a, **k: None) for c in sg._CHECKS},
        "sym_size.int": lambda x, d: B.size(x, d),
        "sym_numel.default": lambda x: int(np.prod(x.shape)),
        **{k: sg.OPS[k] for k in sg.OPS if k.startswith("py.")},
        "view.default": lambda x, s: xp.reshape(x, sizes(s)),
        "_unsafe_view.default": lambda x, s: xp.reshape(x, sizes(s)),
        "reshape.default": lambda x, s: xp.reshape(x, sizes(s)),
        "permute.default": lambda x, d: xp.transpose(x, [v % max(ndim(x), 1) for v in d]),
        "expand.default": expand,
        "clone.default": lambda x, memory_format=None: x,
        "alias.default": lambda x: x, "detach.default": lambda x: x, "lift_fresh_copy.default": lambda x: x,
        "contiguous.default": lambda x, memory_format=None: x,
        "squeeze.dims": lambda x, d: xp.squeeze(x, axis=tuple(v % ndim(x) for v in d if x.shape[v % ndim(x)] == 1))
        if any(x.shape[v % ndim(x)] == 1 for v in d) else x,
        "squeeze.dim": lambda x, d: xp.squeeze(x, axis=d % ndim(x)) if x.shape[d % ndim(x)] == 1 else x,
        "squeeze.default": lambda x: xp.squeeze(x),
        "unsqueeze.default": lambda x, d: xp.expand_dims(x, d % (ndim(x) + 1)),
        "select.int": select,
        "slice.Tensor": slice_,
        "cat.default": cat,
        "stack.default": lambda ts, d=0: xp.stack(ts, axis=d % (ndim(ts[0]) + 1)),
        "split_with_sizes.default": split_sizes,
        "split.Tensor": split,
        "unbind.int": lambda x, d=0: [xp.take(x, i, axis=d % ndim(x)) for i in range(x.shape[d % ndim(x)])],
        "flip.default": lambda x, ds: xp.flip(x, axis=dims(ds, ndim(x))),
        "roll.default": lambda x, sh, ds=(): xp.roll(x, sh, axis=dims(ds, ndim(x))) if ds else xp.roll(x, ints(sh)[0], axis=None),
        "repeat.default": lambda x, r: xp.tile(x, [int(v) for v in r]),
        "constant_pad_nd.default": constant_pad,
        "_to_copy.default": lambda x, dtype=None, *rest: to(x, dtype),
        "to.dtype": lambda x, dtype=None, *rest: to(x, dtype),
        "full.default": lambda s, v, *rest: xp.full(sizes(s), float(v), dtype=xp.float64),
        "full_like.default": lambda x, v, *rest: xp.full_like(f64(x), float(v)),
        "zeros_like.default": lambda x, *rest: xp.zeros_like(f64(x)),
        "ones_like.default": lambda x, *rest: xp.ones_like(f64(x)),
        "zeros.default": lambda s, *rest: xp.zeros(sizes(s), dtype=xp.float64),
        "ones.default": lambda s, *rest: xp.ones(sizes(s), dtype=xp.float64),
        "empty.memory_format": lambda s, *rest: xp.zeros(sizes(s), dtype=xp.float64),
        "scalar_tensor.default": lambda v, *rest: f64(float(v)),
        "arange.start_step": lambda a, e=None, st=1, *rest: xp.arange(a, e, st, dtype=xp.float64) if e is not None
        else xp.arange(0, a, st, dtype=xp.float64),
        "arange.default": lambda e, *rest: xp.arange(e, dtype=xp.float64),
        "arange.start": lambda a, e, *rest: xp.arange(a, e, dtype=xp.float64),
        "mm.default": lambda a, b: xp.matmul(a, b),
        "addmm.default": lambda c, a, b, beta=1, alpha=1: beta * c + alpha * xp.matmul(a, b),
        "bmm.default": lambda a, b: xp.matmul(a, b),
        "matmul.default": lambda a, b: xp.matmul(a, b),
        "baddbmm.default": lambda c, a, b, beta=1, alpha=1: beta * c + alpha * xp.matmul(a, b),
        "dot.default": lambda a, b: xp.dot(a, b),
        "relu.default": lambda x: xp.where(x <= 0, xp.zeros_like(x), x),   # no gradient at 0, NaN stays NaN
        "gelu.default": gelu,
        "sigmoid.default": lambda x: 1.0 / (1.0 + xp.exp(-x)),
        "tanh.default": xp.tanh, "exp.default": xp.exp, "log.default": xp.log, "sqrt.default": xp.sqrt,
        "rsqrt.default": lambda x: 1.0 / xp.sqrt(x), "neg.default": xp.negative,
        "abs.default": lambda x: xp.where(x == 0, xp.zeros_like(x), xp.abs(x)),   # no gradient at 0, as in PyTorch
        "sin.default": xp.sin, "cos.default": xp.cos, "sinh.default": xp.sinh, "cosh.default": xp.cosh,
        "atan.default": xp.arctan, "asin.default": xp.arcsin, "acos.default": xp.arccos,
        "log1p.default": xp.log1p, "expm1.default": xp.expm1, "log2.default": xp.log2, "log10.default": xp.log10,
        "erf.default": B.erf, "reciprocal.default": lambda x: 1.0 / x,
        "sign.default": lambda x: xp.where(xp.isnan(x), xp.zeros_like(x), xp.sign(x)),
        "floor.default": xp.floor, "ceil.default": xp.ceil, "round.default": xp.round, "trunc.default": trunc,
        "silu.default": lambda x: x / (1.0 + xp.exp(-x)),
        "leaky_relu.default": lambda x, s=0.01: xp.where(x > 0, x, s * x),
        "hardtanh.default": hardtanh,
        "elu.default": lambda x, alpha=1.0, scale=1.0, input_scale=1.0: scale * xp.where(x > 0, x, alpha * xp.expm1(input_scale * x)),
        "softplus.default": lambda x, beta=1.0, threshold=20.0: xp.where(beta * x > threshold, x, xp.log1p(
            xp.exp(xp.where(beta * x > threshold, f64(threshold), beta * x))) / beta),   # no exp to overflow in the branch not taken
        "clamp.default": clamp, "clamp.Tensor": clamp,
        "clamp_min.default": lambda x, lo: clamp(x, lo, None),
        "clamp_max.default": lambda x, hi: clamp(x, None, hi),
        "pow.Tensor_Scalar": power, "pow.Tensor_Tensor": power, "pow.Scalar": power,
        "logical_not.default": lambda x: f64(f64(x) == 0),
        "isnan.default": lambda x: f64(xp.isnan(x)), "isinf.default": lambda x: f64(xp.isinf(x)),
        "add.Tensor": lambda a, b, alpha=1: f64(a) + alpha * f64(b), "add.Scalar": lambda a, b, alpha=1: f64(a) + alpha * b,
        "sub.Tensor": lambda a, b, alpha=1: f64(a) - alpha * f64(b), "sub.Scalar": lambda a, b, alpha=1: f64(a) - alpha * b,
        "rsub.Scalar": lambda a, b, alpha=1: b - alpha * f64(a), "rsub.Tensor": lambda a, b, alpha=1: f64(b) - alpha * f64(a),
        "mul.Tensor": lambda a, b: f64(a) * f64(b), "mul.Scalar": lambda a, b: f64(a) * b,
        "div.Tensor": lambda a, b: f64(a) / f64(b), "div.Scalar": lambda a, b: f64(a) / b,
        "div.Tensor_mode": lambda a, b, mode=None: floordiv(a, b) if mode == "floor" else
        trunc(B.constant(f64(a)) / B.constant(f64(b))) if mode == "trunc" else f64(a) / f64(b),   # rounded, it has no gradient
        "remainder.Scalar": lambda a, b: xp.mod(a, b), "remainder.Tensor": lambda a, b: xp.mod(a, b),
        "fmod.Scalar": fmod,
        "maximum.default": balanced(True), "minimum.default": balanced(False),
        "atan2.default": atan2,
        "eq.Scalar": cmp(xp.equal), "eq.Tensor": cmp(xp.equal), "ne.Scalar": cmp(xp.not_equal),
        "ne.Tensor": cmp(xp.not_equal), "lt.Scalar": cmp(xp.less), "lt.Tensor": cmp(xp.less),
        "le.Scalar": cmp(xp.less_equal), "le.Tensor": cmp(xp.less_equal), "gt.Scalar": cmp(xp.greater),
        "gt.Tensor": cmp(xp.greater), "ge.Scalar": cmp(xp.greater_equal), "ge.Tensor": cmp(xp.greater_equal),
        "logical_and.default": cmp(lambda a, b: xp.logical_and(a != 0, b != 0)),
        "logical_or.default": cmp(lambda a, b: xp.logical_or(a != 0, b != 0)),
        "logical_xor.default": cmp(lambda a, b: xp.logical_xor(a != 0, b != 0)),
        "where.self": lambda c, a, b: f64(xp.where(f64(c) != 0, f64(a), f64(b))),
        "sum.dim_IntList": reduce(xp.sum), "sum.default": lambda x, dtype=None: xp.sum(x),
        "mean.dim": reduce(xp.mean), "mean.default": lambda x, dtype=None: xp.mean(x),
        "amax.default": reduce(xp.max), "amin.default": reduce(xp.min),
        "prod.dim_int": lifted(lambda x, d, keepdim=False, dtype=None: xp.prod(x, axis=d % ndim(x), keepdims=keepdim)),
        "max.dim": extreme(xp.argmax), "min.dim": extreme(xp.argmin),
        "max.default": lambda x: xp.max(x), "min.default": lambda x: xp.min(x),
        "argmax.default": lambda x, d=None, keepdim=False: f64(first(xp.argmax, x, d, keepdim)),
        "argmin.default": lambda x, d=None, keepdim=False: f64(first(xp.argmin, x, d, keepdim)),
        "var.correction": var,
        "any.dim": lifted(lambda x, d, keepdim=False: f64(xp.any(f64(x) != 0, axis=d % ndim(x), keepdims=keepdim))),
        "any.default": lambda x: f64(xp.any(f64(x) != 0)),
        "cumsum.default": lifted(lambda x, d, dtype=None: xp.cumsum(x, axis=d % ndim(x))),
        "_softmax.default": lifted(softmax), "_log_softmax.default": lifted(log_softmax),
        "native_layer_norm.default": layer_norm,
        "_native_batch_norm_legit_no_training.default": batch_norm,
        "gather.default": lambda x, d, idx, sparse_grad=False: xp.take_along_axis(x, i64(idx), axis=d % ndim(x)),
        "index_select.default": lambda x, d, idx: xp.take(x, i64(idx), axis=d % ndim(x)),
        "embedding.default": lambda w, idx, *rest: xp.take(w, i64(idx), axis=0),
        "index.Tensor": index,
        "topk.default": topk,
        "sort.default": lifted(sort),
        "convolution.default": conv,
        "max_pool2d_with_indices.default": lambda x, k, s=(), p=(0,), d=(1,), ceil=False: (
            pool(x, k, s, p, d, ceil, "max"), xp.zeros(0)),
        "avg_pool2d.default": avg_pool,
        "_adaptive_avg_pool2d.default": lambda x, out: xp.mean(x, axis=(-2, -1), keepdims=True) if list(out) == [1, 1]
        else (_ for _ in ()).throw(sg.GraphError("adaptive pooling to %s" % (out,))),
    }
    return ops


class XGraph:
    """A graph as a function of (weights, inputs) in JAX's or TensorFlow's NumPy, on the reference's semantics."""

    def __init__(self, graph, backend):
        self.graph, self.B = graph, backend
        self.ops = _xp_ops(backend)
        missing = sorted({nd["op"] for nd in graph["nodes"] if nd["op"] not in self.ops
                          and nd["op"] not in ("sphere.while", "sphere.case")})
        if missing:
            raise sg.GraphError("%s has no %s yet" % (backend.name, ", ".join(missing)))
        self.params = [k for k in graph["consts"] if k in (graph.get("params") or {})]

    def _nodes(self, nodes, env):
        for nd in nodes:
            vals = sg._decode(nd["enc"], env)
            if nd["op"] == "sphere.while" or nd["op"] == "sphere.case":
                env[nd["name"]] = self._control(nd["op"], vals, env)
                continue
            try:
                env[nd["name"]] = self.ops[nd["op"]](*vals)
            except sg.GraphError:
                raise
            except Exception as e:
                raise sg.GraphError("%s (%s) in %s: %s: %s" % (nd["name"], nd["op"], self.B.name, type(e).__name__, e)) from e
        return env

    def _block(self, name, values, consts):
        b = self.graph["blocks"][name]
        env = dict(consts)
        env.update(zip(b["inputs"], values))
        self._nodes(b["nodes"], env)
        return [env[o] for o in b["outputs"]]

    def _control(self, op, args, env):
        consts = {k: env[k] for k in self.graph["consts"]}
        if self.B.name == "jax":
            lax = self.B.lax
            if op == "sphere.while":
                return list(lax.while_loop(lambda v: self._block(args[0], list(v), consts)[0].reshape(-1)[0] != 0,
                                           lambda v: tuple(self._block(args[1], list(v), consts)), tuple(args[2])))
            branches = [lambda v, b=b: tuple(self._block(b, list(v), consts)) for b in args[1]]
            return list(lax.switch(self.B.xp.asarray(args[0], dtype=self.B.xp.int32).reshape(-1)[0], branches,
                                   tuple(args[2])))
        tf = self.B.tf
        if op == "sphere.while":
            return list(tf.while_loop(lambda *v: self._block(args[0], list(v), consts)[0].reshape(-1)[0] != 0,
                                      lambda *v: tuple(self._block(args[1], list(v), consts)), tuple(args[2])))
        k = tf.cast(tf.reshape(args[0], [-1])[0], tf.int32)
        return list(tf.switch_case(k, [lambda b=b: tuple(self._block(b, list(args[2]), consts)) for b in args[1]]))

    def __call__(self, weights, *inputs):
        env = {}
        for k, v in self.graph["consts"].items():
            env[k] = weights[k] if k in weights else v
        env.update(zip(self.graph["inputs"], inputs))
        self._nodes(self.graph["nodes"], env)
        outs = [env[o] for o in self.graph["outputs"]]
        return outs[0] if len(outs) == 1 else tuple(outs)


def to_jax(graph):
    """(apply, weights): apply(weights, *inputs) the graph in JAX, weights its trainable ones as jnp arrays (the
    others constants of the function)."""
    graph = sg.load(graph) if isinstance(graph, str) else graph
    B = _Jax()
    g = XGraph(graph, B)
    consts = {k: B.xp.asarray(np.asarray(v, dtype=np.float64)) for k, v in graph["consts"].items()}
    weights = {k: consts[k] for k in g.params}

    def apply(w, *inputs):
        return g({**consts, **w}, *inputs)

    return apply, weights


def to_tensorflow(graph, batch_free=True):
    """The graph as a tf.Module: its trainable weights tf.Variables (by the names export gave them), the others
    constants; __call__ a tf.function of the example's shapes with a free batch (what a SavedModel serves)."""
    graph = sg.load(graph) if isinstance(graph, str) else graph
    B = _TF()
    tf = B.tf
    g = XGraph(graph, B)

    class GraphModule(tf.Module):
        def __init__(self):
            super().__init__(name=_tf_name(graph["name"]))
            self.weights_by_name = {}
            for k, v in graph["consts"].items():
                if k in g.params:
                    self.weights_by_name[k] = tf.Variable(np.asarray(v, dtype=np.float64), name=_tf_name(
                        graph["params"][k]))
            self._consts = {k: tf.constant(np.asarray(v, dtype=np.float64)) for k, v in graph["consts"].items()
                            if k not in g.params}

        def run(self, *inputs):
            return g({**self._consts, **self.weights_by_name}, *[B.f64(x) for x in inputs])

        def __call__(self, *inputs):
            return self.serve(*inputs)

    m = GraphModule()
    shapes = graph.get("example_shapes") or []
    if shapes and len(shapes) == len(graph["inputs"]):
        sig = [tf.TensorSpec([None if (batch_free and len(s) and i == 0) else d for i, d in enumerate(s)], tf.float64,
                             name=_tf_name(n)) for s, n in zip(shapes, graph["input_names"])]
        m.serve = tf.function(m.run, input_signature=sig, autograph=False)
    else:
        m.serve = tf.function(m.run, autograph=False)
    return m


def _tf_name(s):
    import re
    return re.sub(r"[^A-Za-z0-9_.\-/]", "_", s) or "w"


# ----------------------------------------------------------------------------------------------------------------
# A saved model made a trainable module of another framework, and written back
# ----------------------------------------------------------------------------------------------------------------

def kind_of(path):
    """What a saved model is, from its file: graph, torchscript, torch.export, keras or tensorflow."""
    low = path.lower()
    if os.path.isdir(path):
        if os.path.exists(os.path.join(path, "saved_model.pb")) or os.path.exists(os.path.join(path, "saved_model.pbtxt")):
            return "tensorflow"
        raise sg.GraphError(path + " is a folder without a SavedModel")
    for ext, kind in ((".sng.spx", "graph"), (".keras", "keras"), (".h5", "keras"), (".pt2", "torch.export"), (".pt", "torchscript"),
                      (".pth", "torchscript"), (".ts", "torchscript"), (".pb", "tensorflow"), (".pbtxt", "tensorflow")):
        if low.endswith(ext):
            return kind
    raise sg.GraphError("cannot tell what kind of model %s is" % path)


def graph_of(model, shapes=None, name=None, folder=None):
    """Any saved model as a portable graph, the weights its framework trains marked as such. shapes: the rows' shapes of its
    inputs, where the file does not say them."""
    if isinstance(model, dict):
        return model
    path = os.path.abspath(model)
    if not os.path.exists(path):
        raise FileNotFoundError(path)
    kind = kind_of(path)
    stem = name or os.path.splitext(os.path.basename(path.rstrip("/\\")))[0].replace(".sng", "")
    if kind == "graph":
        return sg.load(path)
    if kind == "tensorflow":
        import sphere_tf
        return sphere_tf.load(path, name=stem, shapes=shapes, folder=folder)
    if kind == "torch.export":
        import torch
        program = torch.export.load(path)
        if not shapes:
            raise sg.GraphError("%s does not say the shapes of its inputs: give them" % path)
        rng = np.random.default_rng(0)
        example = tuple(torch.tensor(rng.normal(size=[8] + list(s)), dtype=torch.float32) for s in shapes)
        return sg.export_program(program, example, stem, folder=folder, trainable=True)
    import sphere_ml as ml
    job = {"kind": kind, "path": path, "name": stem, "folder": folder}
    if shapes:
        job["shapes"] = [list(s) for s in shapes]
    return sg.load(ml.graph_export(job)["path"])


def _with_weights(graph):
    """Which constants can be trained: those the export named; for a graph that named none, the arrays of numbers not whole."""
    graph = dict(graph)
    if not graph.get("params"):
        graph["params"] = {k: k for k, v in graph["consts"].items()
                           if np.ndim(v) >= 1 and np.size(v) > 1 and np.any(np.asarray(v) != np.round(np.asarray(v)))}
    return graph


class Twin:
    """A saved model as a module of another framework: .module (a torch.nn.Module or a tf.Module, its weights Parameters or
    Variables) or, in JAX, .apply and .weights; called like the model. save() writes it as it is now, in any format."""

    def __init__(self, graph, to):
        self.graph, self.framework = _with_weights(graph), to
        self.module, self.apply, self.weights = None, None, None
        if to == "torch":
            self.module = to_torch(self.graph)
        elif to == "tensorflow":
            self.module = to_tensorflow(self.graph)
        elif to == "jax":
            self.apply, self.weights = to_jax(self.graph)
        else:
            raise sg.GraphError("no framework %s (torch, tensorflow, jax)" % to)

    def __call__(self, *inputs):
        if self.framework == "jax":
            return self.apply(self.weights, *inputs)
        if self.framework == "tensorflow":
            return self.module.run(*inputs)
        import torch
        return self.module(*[torch.as_tensor(x, dtype=torch.float64) for x in inputs])     # the graph's weights are float64

    def parameters(self):
        """What an optimizer of the framework takes."""
        if self.framework == "torch":
            return list(self.module.parameters())
        return self.module.trainable_variables if self.framework == "tensorflow" else self.weights

    def now(self):
        """The graph with the weights as they are now."""
        g = dict(self.graph)
        g["consts"] = dict(g["consts"])
        if self.framework == "torch":
            for k, attr in self.module._const_names.items():
                g["consts"][k] = getattr(self.module, attr).detach().cpu().double().numpy().copy()
        elif self.framework == "tensorflow":
            for k, v in self.module.weights_by_name.items():
                g["consts"][k] = v.numpy().copy()
        else:
            for k, v in self.weights.items():
                g["consts"][k] = np.array(v, dtype=np.float64)
        return g

    def save(self, path):
        """Writes the model as it is now, by the path's name: .sng.spx (what Java and C++ run), .pt (TorchScript), .pt2
        (torch.export), anything else a TensorFlow SavedModel folder. Reloaded and held to this one before it answers."""
        return save_graph(self.now(), path)


def save_graph(graph, path):
    inputs = example_inputs(graph, rows=4, seed=11)
    want = [np.asarray(o, dtype=np.float64) for o in _outs(sg.run(graph, inputs))]
    low = path.lower()
    folder = os.path.dirname(os.path.abspath(path))
    os.makedirs(folder, exist_ok=True)
    if low.endswith(".sng.spx"):
        g = dict(graph)
        g["name"] = os.path.basename(path)[:-len(".sng.spx")]
        file = sg.save(g, folder)
        back = sg.run(sg.load(file), inputs)
    elif low.endswith((".pt", ".pth", ".ts")) or low.endswith(".pt2"):
        import torch
        m = to_torch(graph, trainable=False)
        xs = tuple(torch.as_tensor(x, dtype=torch.float64) for x in inputs)
        file = os.path.abspath(path)
        if low.endswith(".pt2"):
            torch.export.save(torch.export.export(m, xs), file)
            loaded = torch.export.load(file).module()
        else:
            with torch.no_grad():
                torch.jit.trace(m, xs, check_trace=False).save(file)
            loaded = torch.jit.load(file)
        with torch.no_grad():
            back = loaded(*xs)
    else:
        import tensorflow as tf
        m = to_tensorflow(graph)
        file = os.path.abspath(path)
        tf.saved_model.save(m, file, signatures={"serving_default": m.serve.get_concrete_function()})
        loaded = tf.saved_model.load(file)      # held: its functions refer to variables it alone keeps alive
        back = loaded.serve(*[tf.constant(x, dtype=tf.float64) for x in inputs])
    worst = max(float(np.max(np.abs(_np(a) - r))) if np.size(r) else 0.0 for a, r in zip(_outs(back), want))
    if worst > 1e-9 * max(1.0, max(float(np.max(np.abs(r))) for r in want if np.size(r))):
        raise sg.GraphError("%s, reloaded, differs by %.3g from the model it was written from" % (file, worst))
    return {"path": file, "reloaded_within": worst}


def _original(path, kind, inputs):
    """What the framework the model was saved by answers, for the twin to be held to; None if it cannot be asked here."""
    try:
        if kind == "torchscript":
            import torch
            with torch.no_grad():
                return torch.jit.load(path, map_location="cpu")(*[torch.as_tensor(x, dtype=torch.float32) for x in inputs])
        if kind == "torch.export":
            import torch
            program = torch.export.load(path)
            dtype = next((v.dtype for v in program.state_dict.values() if v.is_floating_point()), torch.float32)
            with torch.no_grad():
                return program.module()(*[torch.as_tensor(x, dtype=dtype) for x in inputs])
        if kind == "tensorflow" and os.path.isdir(path):
            import tensorflow as tf
            loaded = tf.saved_model.load(path)      # held: its functions refer to variables it alone keeps alive
            sig = loaded.signatures["serving_default"]
            wanted = sig.structured_input_signature[1]
            return sig(**{k: tf.constant(x, dtype=wanted[k].dtype) for k, x in zip(sorted(wanted), inputs)})
    except Exception:
        return None
    return None


def twin(model, to="torch", shapes=None, name=None, folder=None, check=True):
    """A saved model (TorchScript, torch.export, a SavedModel or frozen graph, Keras, a Sphere graph) as a trainable module of
    the framework `to`. It is held to the framework that saved it, on inputs of its own shapes, before it is handed over."""
    t = Twin(graph_of(model, shapes, name, folder), to)
    t.fidelity = None
    if check and isinstance(model, str):
        path = os.path.abspath(model)
        inputs = example_inputs(t.graph, rows=4, seed=5)
        want = _original(path, kind_of(path), inputs)
        if want is not None:
            answer = t(*inputs)
            want = list(want.values()) if isinstance(want, dict) else _outs(want)
            t.fidelity = max(float(np.max(np.abs(_np(a) - _np(b)))) for a, b in zip(_outs(answer), want))
            if t.fidelity > 1e-4:
                raise sg.GraphError("the %s twin of %s differs by %.3g from the original" % (to, model, t.fidelity))
    return t


def twin_command(model, to, out=None, shapes=None, report=None):
    """':ml twin': the model in the other framework, proved the same, written where asked."""
    say = report or (lambda message: None)
    say("reading " + os.path.basename(model.rstrip("/\\")) + " ...")
    t = twin(model, to, shapes)
    if to == "jax":
        count = sum(np.size(v) for v in t.weights.values())
    elif to == "torch":
        count = sum(p.numel() for p in t.parameters())
    else:
        count = sum(int(np.prod(v.shape)) for v in t.parameters())
    r = {"from": kind_of(os.path.abspath(model)) if isinstance(model, str) else "graph", "to": to, "tensors": len(t.graph.get("params") or {}),
         "trainable_numbers": int(count), "fidelity": t.fidelity, "nodes": len(t.graph["nodes"])}
    if out:
        r.update(t.save(out))
    r["verdict"] = "%s made a %s module, %d numbers trainable%s%s" % (r["from"], to, count,
                                                                       ", within %.1e of the original" % t.fidelity if t.fidelity is not None else "",
                                                                       "; written to %s" % r["path"] if out else "")
    return r


# ----------------------------------------------------------------------------------------------------------------
# held to the reference and to each other
# ----------------------------------------------------------------------------------------------------------------

def example_inputs(graph, rows=8, seed=0, data=None):
    """Inputs for the graph: a dataset's rows when given (by its feeds), else draws of the example's shapes --
    normal values, a mask's first entries ones."""
    rng = np.random.default_rng(seed)
    if data is not None:
        import sphere_ml as ml
        d = ml.dataset(data) if isinstance(data, str) else data
        take = np.arange(min(rows, len(d)))
        by = {"x": d.x, "mask": d.mask, "hl": getattr(d, "hl", None)}
        out = []
        for n in graph["input_names"]:
            src = by.get(n)
            if src is None:
                break
            out.append(np.asarray(src[take], dtype=np.float64))
        else:
            return out
    out = []
    for n, s in zip(graph["input_names"], graph.get("example_shapes") or []):
        shape = [rows] + list(s[1:]) if len(s) else []
        if "mask" in n.lower():
            m = np.zeros(shape)
            if m.ndim == 2:
                for r in range(shape[0]):
                    m[r, :max(1, int(rng.integers(1, shape[1] + 1)))] = 1.0
            out.append(m)
        else:
            out.append(rng.standard_normal(shape))
    return out


def _gap(a, ref):
    """max |a - ref| in units of the double's epsilon at the reference's scale (NaN where both are NaN)."""
    a, ref = np.asarray(a, dtype=np.float64), np.asarray(ref, dtype=np.float64)
    if a.shape != ref.shape:
        return float("inf")
    both = np.isnan(a) & np.isnan(ref)
    d = np.where(both, 0.0, np.abs(a - ref))
    scale = float(np.max(np.abs(np.where(np.isfinite(ref), ref, 0.0)))) if ref.size else 0.0
    return float(np.max(d)) / max(EPS * max(scale, 1e-300), 1e-300) if d.size else 0.0


def _outs(v):
    return list(v) if isinstance(v, (list, tuple)) else [v]


def _np(v):
    if hasattr(v, "detach"):
        return v.detach().cpu().numpy()
    return np.asarray(v)


def check(graph, data=None, rows=8, seed=0, engines=("torch", "jax", "tensorflow"), gradients=True, exports=True,
          bench=True, folder=None, report=None):
    """The graph in every framework: outputs against numpy's, gradients against each other and finite
    differences, the exported artifacts reloaded, the engines timed."""
    report = report or (lambda text: None)
    graph = sg.load(graph) if isinstance(graph, str) else graph
    t_all = time.perf_counter()
    inputs = example_inputs(graph, rows, seed, data)
    with np.errstate(all="ignore"):
        ref = [np.asarray(o, dtype=np.float64) for o in _outs(sg._execute(graph, dict(zip(graph["inputs"], inputs))))]
    out = {"graph": graph["name"], "nodes": len(graph["nodes"]), "ops": len({n["op"] for n in graph["nodes"]}),
           "weights": int(sum(np.size(v) for v in graph["consts"].values())),
           "trainable": len([k for k in graph["consts"] if k in (graph.get("params") or {})]),
           "rows": rows, "inputs": [list(np.shape(x)) for x in inputs], "engines": {}}
    rng = np.random.default_rng(seed + 1)
    cot = [rng.standard_normal(r.shape) for r in ref]
    runners = {}
    for e in engines:
        t0 = time.perf_counter()
        try:
            runners[e] = _engine(e, graph)
            res = runners[e]["forward"](inputs)
            gaps = [_gap(_np(a), r) for a, r in zip(_outs(res), ref)]
            out["engines"][e] = {"outputs_eps": max(gaps) if gaps else 0.0, "setup_s": time.perf_counter() - t0}
            report("  %s: the outputs within %.1f epsilon of numpy's" % (e, out["engines"][e]["outputs_eps"]))
        except Exception as ex:
            out["engines"][e] = {"error": "%s: %s" % (type(ex).__name__, str(ex)[:300])}
            report("  %s: %s" % (e, out["engines"][e]["error"]))
            runners.pop(e, None)
    if gradients and runners:
        out["gradients"] = _gradients(graph, inputs, cot, runners, report)
    if exports:
        out["exports"] = _exports(graph, inputs, ref, runners, folder, report)
    if bench:
        out["timing"] = _bench(graph, runners, seed, report)
    out["seconds"] = time.perf_counter() - t_all
    out["verdict"] = _verdict(out)
    return out


def _engine(name, graph):
    """The framework's forward, and its gradient of sum(cot * outputs) by (weights, float inputs)."""
    if name == "torch":
        import torch
        m = to_torch(graph)

        def forward(inputs):
            with torch.no_grad():
                return m(*[torch.as_tensor(x, dtype=torch.float64) for x in inputs])

        def grad(inputs, cot):
            xs = [torch.as_tensor(x, dtype=torch.float64).requires_grad_(True) for x in inputs]
            outs = _outs(m(*xs))
            loss = sum((o * torch.as_tensor(c)).sum() for o, c in zip(outs, cot))
            params = [p for _, p in m.named_parameters()]
            gs = torch.autograd.grad(loss, params + xs, allow_unused=True)
            names = [n[2:] for n, _ in m.named_parameters()]
            return ({k: _np(g) if g is not None else np.zeros(p.shape) for k, g, p in zip(names, gs[:len(params)], params)},
                    [_np(g) if g is not None else np.zeros(np.shape(x)) for g, x in zip(gs[len(params):], inputs)])

        return {"forward": forward, "grad": grad, "module": m}
    if name == "jax":
        import jax
        apply, w0 = to_jax(graph)
        jitted = jax.jit(apply)

        def forward(inputs):
            return jitted(w0, *inputs)

        def loss(w, *xs, cot=None):
            return sum((o * c).sum() for o, c in zip(_outs(apply(w, *xs)), cot))

        def grad(inputs, cot):
            gw, *gx = jax.grad(lambda w, *xs: loss(w, *xs, cot=cot), argnums=tuple(range(1 + len(inputs))))(w0, *inputs)
            return {k: np.asarray(v) for k, v in gw.items()}, [np.asarray(g) for g in gx]

        return {"forward": forward, "grad": grad, "apply": apply, "weights": w0, "jit": jitted}
    if name == "tensorflow":
        import tensorflow as tf
        m = to_tensorflow(graph)

        def forward(inputs):
            return m(*[tf.constant(x, dtype=tf.float64) for x in inputs])

        def grad(inputs, cot):
            xs = [tf.Variable(x, dtype=tf.float64) for x in inputs]
            with tf.GradientTape() as tape:
                outs = _outs(m.run(*xs))
                loss = tf.add_n([tf.reduce_sum(o * c) for o, c in zip(outs, cot)])
            ws = list(m.weights_by_name.items())
            gs = tape.gradient(loss, [v for _, v in ws] + xs, unconnected_gradients=tf.UnconnectedGradients.ZERO)
            gs = [tf.convert_to_tensor(g).numpy() for g in gs]   # an embedding's gradient comes as IndexedSlices
            return {k: g for (k, _), g in zip(ws, gs[:len(ws)])}, gs[len(ws):]

        return {"forward": forward, "grad": grad, "module": m}
    raise ValueError("engine %s: torch, jax or tensorflow" % name)


def _gradients(graph, inputs, cot, runners, report):
    """Every framework's gradient against PyTorch's (or the first's), and the first's against finite differences
    of numpy's reference along three random directions of the weights and the inputs."""
    grads, out = {}, {}
    for e, r in runners.items():
        try:
            grads[e] = r["grad"](inputs, cot)
        except Exception as ex:
            out[e] = {"error": "%s: %s" % (type(ex).__name__, str(ex)[:300])}
    if not grads:
        return out
    base = "torch" if "torch" in grads else next(iter(grads))
    gw0, gx0 = grads[base]
    keys = sorted(gw0)

    def flat(gw, gx):
        return np.concatenate([np.asarray(gw[k], dtype=np.float64).ravel() for k in keys]
                              + [np.asarray(g, dtype=np.float64).ravel() for g in gx]) if keys or gx else np.zeros(0)

    v0 = flat(gw0, gx0)
    finite = np.isfinite(v0)
    scale = float(np.max(np.abs(v0[finite]))) if finite.any() else 0.0
    for e, (gw, gx) in grads.items():
        if e == base:
            continue
        try:
            v = flat(gw, gx)
            same_nan = np.isnan(v) & np.isnan(v0)
            d = np.where(same_nan, 0.0, np.abs(v - v0))
            out[e] = {"vs": base, "eps": float(np.max(d)) / max(EPS * max(scale, 1e-300), 1e-300) if d.size else 0.0,
                      "nan": int(np.sum(same_nan))}
            report("  %s's gradient within %.1f epsilon of %s's" % (e, out[e]["eps"], base))
        except Exception as ex:
            out[e] = {"error": "%s: %s" % (type(ex).__name__, str(ex)[:200])}
    # finite differences of the reference: <grad, v> against (L(+hv) - L(-hv)) / 2h
    rng = np.random.default_rng(7)
    worst = 0.0

    def loss_at(consts, xs):
        g2 = dict(graph)
        g2["consts"] = consts
        with np.errstate(all="ignore"):
            outs = _outs(sg._execute(g2, dict(zip(graph["inputs"], xs))))
        return sum(float(np.sum(np.asarray(o) * c)) for o, c in zip(outs, cot))

    smooth = ["mask" not in n.lower() for n in graph["input_names"]]   # a mask is compared to zero: not perturbed
    for _ in range(3):
        dw = {k: rng.standard_normal(np.shape(graph["consts"][k])) for k in keys}
        dx = [rng.standard_normal(np.shape(x)) * s for x, s in zip(inputs, smooth)]
        h = 1e-6 * max(1.0, max([float(np.max(np.abs(graph["consts"][k]))) for k in keys] + [0.0]))
        plus = loss_at({**graph["consts"], **{k: graph["consts"][k] + h * dw[k] for k in keys}},
                       [x + h * d for x, d in zip(inputs, dx)])
        minus = loss_at({**graph["consts"], **{k: graph["consts"][k] - h * dw[k] for k in keys}},
                        [x - h * d for x, d in zip(inputs, dx)])
        fd = (plus - minus) / (2 * h)
        an = sum(float(np.sum(np.asarray(gw0[k]) * dw[k])) for k in keys) + sum(
            float(np.sum(np.asarray(g) * d)) for g, d in zip(gx0, dx))
        worst = max(worst, abs(fd - an) / max(abs(an), 1e-12))
    out["finite_differences"] = {"base": base, "relative": worst, "directions": 3}
    report("  %s's gradient against finite differences of numpy's reference: %.1e (3 directions)" % (base, worst))
    out["base"] = base
    return out


def _exports(graph, inputs, ref, runners, folder, report):
    """The deployable artifacts: a TensorFlow SavedModel (a free batch), a TorchScript trace and a torch.export
    program -- each written, reloaded and run again against numpy."""
    import tempfile
    folder = folder or os.path.join(tempfile.gettempdir(), "sphere_xf")
    os.makedirs(folder, exist_ok=True)
    out = {}
    if "tensorflow" in runners:
        try:
            import tensorflow as tf
            path = os.path.join(folder, graph["name"] + ".savedmodel")
            m = runners["tensorflow"]["module"]
            try:
                tf.saved_model.save(m, path, signatures={"serving_default": m.serve.get_concrete_function()})
            except (ValueError, TypeError):
                tf.saved_model.save(m, path)
            back = tf.saved_model.load(path)
            res = back.serve(*[tf.constant(x, dtype=tf.float64) for x in inputs])
            out["savedmodel"] = {"path": path, "eps": max(_gap(_np(a), r) for a, r in zip(_outs(res), ref))}
            report("  SavedModel %s reloaded: within %.1f epsilon" % (path, out["savedmodel"]["eps"]))
        except Exception as ex:
            out["savedmodel"] = {"error": "%s: %s" % (type(ex).__name__, str(ex)[:300])}
    if "torch" in runners:
        import torch
        m = runners["torch"]["module"]
        xs = tuple(torch.as_tensor(x, dtype=torch.float64) for x in inputs)
        try:
            path = os.path.join(folder, graph["name"] + ".torchscript.pt")
            with torch.no_grad():
                ts = torch.jit.trace(m, xs, check_trace=False)
            ts.save(path)
            back = torch.jit.load(path)
            with torch.no_grad():
                res = back(*xs)
            out["torchscript"] = {"path": path, "eps": max(_gap(_np(a), r) for a, r in zip(_outs(res), ref))}
            report("  TorchScript %s reloaded: within %.1f epsilon" % (path, out["torchscript"]["eps"]))
        except Exception as ex:
            out["torchscript"] = {"error": "%s: %s" % (type(ex).__name__, str(ex)[:300])}
        try:
            path = os.path.join(folder, graph["name"] + ".pt2")
            ep = torch.export.export(m, xs)
            torch.export.save(ep, path)
            back = torch.export.load(path).module()
            with torch.no_grad():
                res = back(*xs)
            out["torch_export"] = {"path": path, "eps": max(_gap(_np(a), r) for a, r in zip(_outs(res), ref))}
            report("  torch.export %s reloaded: within %.1f epsilon" % (path, out["torch_export"]["eps"]))
        except Exception as ex:
            out["torch_export"] = {"error": "%s: %s" % (type(ex).__name__, str(ex)[:300])}
    return out


def _bench(graph, runners, seed, report, batches=(1, 256), repeat=None):
    """The forward's time per call at a batch of 1 and of 256, best of a few: numpy's reference, PyTorch (the
    replay, and its TorchScript trace), JAX compiled by XLA, TensorFlow's function, and XLA's."""
    out = {}
    for b in batches:
        inputs = example_inputs(graph, b, seed)
        row = {}

        def timed(f, n):
            f()
            best = float("inf")
            for _ in range(3):
                t = time.perf_counter()
                for _ in range(n):
                    f()
                best = min(best, (time.perf_counter() - t) / n)
            return best * 1e3

        n = 20 if b == 1 else 3
        with np.errstate(all="ignore"):
            row["numpy"] = timed(lambda: sg._execute(graph, dict(zip(graph["inputs"], inputs))), n)
        if "torch" in runners:
            import torch
            m = runners["torch"]["module"]
            xs = tuple(torch.as_tensor(x, dtype=torch.float64) for x in inputs)
            with torch.no_grad():
                row["torch"] = timed(lambda: m(*xs), n)
                try:
                    ts = torch.jit.trace(m, xs, check_trace=False)
                    row["torchscript"] = timed(lambda: ts(*xs), n)
                except Exception:
                    pass
        if "jax" in runners:
            r = runners["jax"]
            import jax
            f = jax.jit(r["apply"])
            row["jax_xla"] = timed(lambda: jax.block_until_ready(f(r["weights"], *inputs)), n)
        if "tensorflow" in runners:
            import tensorflow as tf
            m = runners["tensorflow"]["module"]
            xs = [tf.constant(x, dtype=tf.float64) for x in inputs]
            fn = tf.function(m.run, autograph=False)
            row["tf_function"] = timed(lambda: fn(*xs), n)
            try:
                fx = tf.function(m.run, jit_compile=True, autograph=False)
                row["tf_xla"] = timed(lambda: fx(*xs), n)
            except Exception:
                pass
        out[str(b)] = row
        best = min(row, key=row.get)
        report("  batch %d (ms per call): %s -- fastest %s" % (b, ", ".join("%s %.3f" % kv for kv in row.items()), best))
    return out


def _verdict(out):
    eng = out["engines"]
    ok = [e for e, v in eng.items() if "error" not in v and v["outputs_eps"] <= 64]
    bad = [e for e, v in eng.items() if "error" in v or v.get("outputs_eps", 0) > 64]
    g = out.get("gradients", {})
    parts = ["%s's %d operations run in %s to %s epsilon of numpy's" % (
        out["graph"], out["ops"], ", ".join(ok) or "no framework",
        "%.0f" % max([eng[e]["outputs_eps"] for e in ok] or [0]))]
    if bad:
        parts.append("not in %s" % ", ".join(bad))
    gs = [(e, v["eps"]) for e, v in g.items() if isinstance(v, dict) and "eps" in v]
    if gs:
        parts.append("their gradients agree with %s's to %.0f epsilon, and with finite differences to %.1e" % (
            g.get("base", "?"), max(v for _, v in gs), g.get("finite_differences", {}).get("relative", float("nan"))))
    ex = out.get("exports", {})
    done = [k for k, v in ex.items() if "error" not in v]
    if done:
        parts.append("exported and reloaded as %s" % ", ".join(done))
    t = out.get("timing", {})
    if t:
        parts.append("fastest at a batch of 1: %s, of 256: %s" % (
            min(t["1"], key=t["1"].get) if "1" in t else "?", min(t["256"], key=t["256"].get) if "256" in t else "?"))
    return "; ".join(parts)


# ----------------------------------------------------------------------------------------------------------------
# Conformance: every operation of the graphs against PyTorch, the definition they were written from
# ----------------------------------------------------------------------------------------------------------------

_TINY = float(np.finfo(np.float64).tiny)   # under it JAX and TensorFlow flush to zero, their processors set for speed
_EPS = float(np.finfo(np.float64).eps)
_EDGE = np.array([0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 2.5, -2.5, 1e-300, -1e-300, 1e300, -1e300, np.inf, -np.inf, np.nan,
                  3.0, 3.0, -3.0, 1e-8, 7.0])
_NUMBERS = (0.5, 2.0, -1.0, 0.0, 3)
_FIRST_ONLY = ("max_pool2d_with_indices.default", "_native_batch_norm_legit_no_training.default",
               "native_layer_norm.default")   # the graphs define nothing of their other outputs
_UNHELD = ("empty.memory_format",)            # uninitialized memory: there is nothing to hold it to
_KERNELS = ("convolution.default",)   # NaN or an infinity through a convolution: what comes out is that of each framework's kernel
_DEFAULT = object()
# sums of many terms: with 1e300, or 1e-300 beside 3, among them the result is that of an order of summation, each framework's own
_SUMMED = ("convolution.default", "avg_pool2d.default", "_adaptive_avg_pool2d.default", "native_layer_norm.default", "mm.default",
           "addmm.default", "bmm.default", "baddbmm.default", "matmul.default", "dot.default", "sum.default", "sum.dim_IntList",
           "mean.default", "mean.dim", "cumsum.default", "var.correction", "prod.dim_int", "_native_batch_norm_legit_no_training.default")
_SUM_ROUNDING = 1e-13   # of the largest value of the output: what is left of a sum that cancels is its rounding, however small the result


class _Drawn:
    """A tensor of a case: the float64 array a graph holds, and the type PyTorch takes it in."""

    def __init__(self, array, dtype):
        self.array, self.dtype = np.asarray(array, dtype=np.float64), dtype


def _schema_choices(a):
    """What to try for an argument, from its schema alone: "tensor", the values to try, or None for its default."""
    t, name = str(a.type), a.name
    if name in ("dtype", "layout", "device", "pin_memory", "memory_format", "non_blocking", "generator", "names", "sparse_grad",
                "sparse", "scale_grad_by_freq"):
        return None
    if "Tensor" in t:
        return "tensor" if "List" not in t else None
    optional = [None] if "Optional" in t else []
    if name in ("dim", "dims", "axis"):
        return ([[-1], [0], [1], [0, 1], [-1, 0], []] if "List" in t else [-1, 0, 1]) + optional
    if "bool" in t:
        return [False, True]
    if name in ("size", "shape"):
        return [[3, 4], [2, 2], [4]]
    if "List" in t:
        return None
    if name == "rounding_mode":
        return ["trunc", "floor", None]
    if name == "approximate":
        return ["none", "tanh"]
    if "number" in t or "float" in t:
        return list(_NUMBERS) + optional
    return [1, 2] + optional if "int" in t else None


def _fuzz_recipes(T, P, I, C, pick, casts):
    """The operations whose arguments depend on one another, which a schema cannot say: their arguments, in its order."""

    def matmul():
        a, b = pick(((3, 2), (2, 4)), ((4,), (4,)), ((3, 4), (4,)), ((4,), (4, 3)), ((2, 3, 2), (2, 4)))
        return [T(a), T(b)]

    def roll():
        shifts, dims_ = pick(([1], [0]), ([-1], [1]), ([2, 1], [0, 1]), ([5], [-1]), ([1], []))
        return [T((3, 4)), shifts, dims_]

    def layer_norm():
        s = pick((4,), (3, 4))
        return [T((2, 3, 4)), list(s), pick(T(s), None), pick(T(s), None), 1e-5]

    def batch_norm():
        x = pick((4, 3), (2, 3, 4, 4))
        return [T(x), pick(T((3,)), None), pick(T((3,)), None), T((3,)), P((3,)), 0.1, 1e-5]

    def pools():
        return [pick([2, 2], [3, 3]), pick([], [2, 2], [1, 1]), pick([0, 0], [1, 1])]

    return {
        "view.default": lambda: [T((3, 4)), pick([-1], [2, -1], [-1, 2], [4, 3], [12])],
        "_unsafe_view.default": lambda: [T((3, 4)), pick([-1], [2, -1], [4, 3])],
        "reshape.default": lambda: [T((2, 3, 2)), pick([-1], [3, -1], [-1, 6], [12])],
        "permute.default": lambda: [T((2, 3, 4)), pick([2, 0, 1], [1, 2, 0], [0, 2, 1], [-1, 0, 1])],
        "expand.default": lambda: [T((1, 4)), pick([3, 4], [-1, 4], [2, 3, 4], [3, -1])],
        "squeeze.dims": lambda: [T((3, 1, 4, 1)), pick([1], [3], [1, 3], [0], [-1])],
        "squeeze.dim": lambda: [T((3, 1, 4)), pick(1, 0, -2)],
        "squeeze.default": lambda: [T((3, 1, 4, 1))],
        "unsqueeze.default": lambda: [T((3, 4)), pick(0, 1, 2, -1, -3)],
        "select.int": lambda: [T((3, 4)), pick(0, 1, -1), pick(0, 1, -1, 2)],
        "slice.Tensor": lambda: [T((5, 4)), pick(0, 1, -1), pick(None, 0, 1, -2, -100, 100), pick(None, 3, -1, 100, -100, 0), pick(1, 2, 3)],
        "cat.default": lambda: [[T((3, 2)), T((3, 2)), T((3, 2))], pick(0, 1, -1)],
        "stack.default": lambda: [[T((3, 2)), T((3, 2))], pick(0, 1, 2, -1)],
        "split_with_sizes.default": lambda: [T((5, 2)), pick([2, 3], [1, 1, 3], [5]), pick(0, -2)],
        "split.Tensor": lambda: [T((5, 2)), pick(1, 2, 3, 7), pick(0, -2)],
        "unbind.int": lambda: [T((3, 2)), pick(0, 1, -1)],
        "flip.default": lambda: [T((3, 4)), pick([0], [1], [0, 1], [-1])],
        "roll.default": roll,
        "repeat.default": lambda: [T((3, 2)), pick([2, 1], [1, 2], [2, 1, 3])],
        "constant_pad_nd.default": lambda: [T((3, 4)), pick([1, 1], [0, 2], [1, 0, 0, 1], [-1, 1], [1, -1, 0, 0]), pick(0, 0.0, 1.5, -2)],
        "_to_copy.default": lambda: [T((3, 2)), pick(None, *casts())],
        "to.dtype": lambda: [T((3, 2)), pick(*casts())],
        "mm.default": lambda: [T((3, 2)), T((2, 4))],
        "addmm.default": lambda: [T((3, 4)), T((3, 2)), T((2, 4)), pick(1, 0.5, 2), pick(1, 0.5, -1)],
        "bmm.default": lambda: [T((2, 3, 2)), T((2, 2, 4))],
        "matmul.default": matmul,
        "baddbmm.default": lambda: [T((2, 3, 4)), T((2, 3, 2)), T((2, 2, 4)), pick(1, 0.5), pick(1, -1)],
        "dot.default": lambda: [T((4,)), T((4,))],
        "where.self": lambda: [C((3, 4)), T((3, 4)), T((3, 4))],
        "native_layer_norm.default": layer_norm,
        "_native_batch_norm_legit_no_training.default": batch_norm,
        "gather.default": lambda: pick([T((3, 4)), 1, I(4, (3, 5))], [T((3, 4)), 0, I(3, (2, 4))], [T((4,)), 0, I(4, (6,))]),
        "index_select.default": lambda: pick([T((3, 4)), 1, I(4, (5,))], [T((3, 4)), 0, I(3, (2,))]),
        "embedding.default": lambda: [T((6, 3)), pick(I(6, (4, 2)), I(6, (3,)))],
        "index.Tensor": lambda: pick([T((3, 4)), [I(3, (5,)), I(4, (5,))]], [T((3, 4)), [None, I(4, (2,))]], [T((3, 4)), [I(3, (2,))]]),
        "topk.default": lambda: [T((3, 4)), pick(1, 2, 3), pick(-1, 0, 1), pick(True, False), pick(True, False)],
        "softplus.default": lambda: [T(pick((3, 4), (4,), ())), pick(0.5, 1.0, 2.0), pick(1.0, 3, 20.0)],   # beta > 0: else it is not the function
        "convolution.default": lambda: pick(
            [T((2, 3, 8)), T((4, 3, 3)), pick(T((4,)), None), pick([1], [2]), pick([0], [1]), pick([1], [2]), False, [0], 1],
            [T((2, 3, 6, 6)), T((4, 3, 3, 3)), pick(T((4,)), None), pick([1, 1], [2, 1]), pick([0, 0], [1, 1]), pick([1, 1], [1, 2]), False, [0, 0], 1],
            [T((2, 4, 6, 6)), T((4, 2, 3, 3)), None, [1, 1], [1, 1], [1, 1], False, [0, 0], 2]),
        "max_pool2d_with_indices.default": lambda: [T((2, 3, 6, 6))] + pools() + [[1, 1], False],
        "avg_pool2d.default": lambda: [T((2, 3, 6, 6))] + pools() + [False, pick(True, False), pick(None, 4)],
        "_adaptive_avg_pool2d.default": lambda: [T((2, 3, 6, 6)), [1, 1]],
    }


def _difference(truth, got, rounding=1e-10, gradient=False, share=0.0):
    """How got differs from truth: None if it does not (NaN where NaN, within 64 units in the last place); else (how, where).
    How: "flush" (a difference only of subnormal numbers), "rounding", "undefined" (a gradient PyTorch has as NaN or infinite, at a
    pole or where the function has no derivative), "value"."""
    a, b = np.asarray(truth, dtype=np.float64), np.asarray(got, dtype=np.float64)
    if a.shape != b.shape:
        return "value", "shape %s, here %s" % (a.shape, b.shape)
    if not a.size:
        return None
    finite = np.isfinite(a) & np.isfinite(b)
    gap = np.abs(np.where(finite, a, 0.0) - np.where(finite, b, 0.0))
    error = np.where(finite, gap / np.maximum(np.maximum(np.abs(np.where(finite, a, 0.0)), np.abs(np.where(finite, b, 0.0))), _TINY), np.inf)
    wrong = ~((np.isnan(a) & np.isnan(b)) | (a == b) | (error <= 64 * _EPS))
    if not wrong.any():
        return None
    i = int(np.flatnonzero(wrong.ravel())[0])
    where = "at %d: PyTorch %r, here %r" % (i, float(a.ravel()[i]), float(b.ravel()[i]))
    if (finite & (gap <= 4 * _TINY))[wrong].all():
        return "flush", where
    largest = float(np.max(np.abs(a[np.isfinite(a)]))) if np.isfinite(a).any() else 0.0
    if (finite & ((error <= rounding) | (gap <= share * largest)))[wrong].all():
        return "rounding", where
    if gradient and (~np.isfinite(a))[wrong].all():
        return "undefined", where
    return "value", where


def _flat(v):
    if isinstance(v, (list, tuple)):
        return [e for x in v for e in _flat(x)]
    return [v]


def _fuzz_write(op, cases, folder):
    """The cases of an operation as one graph with no inputs, PyTorch's answers among its weights: its outputs come in pairs, the
    runtime's value and what it should be. Any runtime replays it with what it has, nothing of Python or PyTorch."""
    graph = {"name": op, "inputs": [], "input_names": [], "outputs": [], "consts": {}, "shapes": {}, "nodes": [], "feeds": "",
             "framework": "fuzz", "params": {}}
    for c in cases:
        graph["consts"].update(c["inputs"])
        graph["nodes"].append(c["node"])
        v = c["node"]["name"]
        for i, t in enumerate(c["truth"]):
            if not t.size:
                continue
            value = v
            if c["several"]:
                value = "%s_%d" % (v, i)
                graph["nodes"].append({"name": value, "op": "py.getitem", "args": [], "enc": ["n:" + v, "i:%d" % i], "dtype": ""})
            want = "%s_want%d" % (v, i)
            graph["consts"][want] = t
            graph["outputs"] += [value, want]
    graph["shapes"] = {k: list(np.shape(x)) for k, x in graph["consts"].items()}
    return sg.save(graph, folder)


def fuzz(seed=7, rounds=6, gradients=True, folder=None, only=None, report=None):
    """Every operation of the graphs against PyTorch, the definition they were written from, in NumPy, JAX and TensorFlow: values on
    arguments generated from each operator's own schema (so that a new operation, or PyTorch's next version, is covered with nothing
    to write), with ties, zeros, NaN and infinities; and, away from those, the gradients, with PyTorch's conventions at the kinks.

    What differs is told apart: a real difference, from the rounding of a framework's own libm, from the subnormal numbers JAX and
    TensorFlow flush to zero, from a gradient PyTorch itself has as NaN. In folder, the cases as files Java and C++ replay, a list of
    them in cases.txt. only: operations to keep to (a name or part of one)."""
    import time
    import warnings
    import torch
    t0 = time.time()
    say = report or (lambda m: None)
    rng = np.random.default_rng(seed)
    state = {"kind": "normal", "op": ""}

    def pick(*options):
        return options[int(rng.integers(len(options)))]

    def draw(shape):
        kind = state["kind"]
        if kind == "normal":
            return rng.normal(size=shape)
        if kind == "ties":
            return rng.integers(-2, 3, size=shape).astype(np.float64)
        if kind == "edge":
            x = np.resize(rng.permutation(_EDGE), shape)
            return np.where(np.isfinite(x) & (np.abs(x) >= 1e300) | (x != 0) & (np.abs(x) < 1e-4), np.sign(x) * 0.25, x) if state["op"] in _SUMMED else x
        return rng.uniform(0.01, 2.0, size=shape)

    T = lambda shape: _Drawn(draw(shape), torch.float64)
    P = lambda shape: _Drawn(rng.uniform(0.01, 2.0, size=shape), torch.float64)
    I = lambda high, shape: _Drawn(rng.integers(0, high, size=shape), torch.int64)
    C = lambda shape: _Drawn(rng.integers(0, 2, size=shape), torch.bool)
    # the integer of a NaN or an infinity is whatever the processor makes of it
    casts = lambda: (torch.float32, torch.float64, torch.bool) if state["kind"] == "edge" else (torch.float32, torch.float64, torch.int64, torch.int32, torch.bool)
    recipes = _fuzz_recipes(T, P, I, C, pick, casts)

    engines, differentiators, absent = {"numpy": lambda g, xs: sg.run(g, xs)}, {}, []
    try:
        jx = _Jax()

        def jax_gradient(g, xs, weights):
            net = XGraph(g, jx)

            def total(*v):
                return sum(jx.xp.sum(jx.xp.asarray(w) * o) for o, w in zip(_flat(net({}, *v)), weights) if w is not None)
            return jx.jax.grad(total, argnums=tuple(range(len(xs))))(*[jx.xp.asarray(x) for x in xs])
        engines["jax"] = lambda g, xs: XGraph(g, jx)({}, *[jx.xp.asarray(x) for x in xs])
        differentiators["jax"] = jax_gradient
    except ImportError:
        absent.append("jax")
    try:
        tb = _TF()
        tf = tb.tf

        def tf_gradient(g, xs, weights):
            net = XGraph(g, tb)
            v = [tf.constant(x, dtype=tf.float64) for x in xs]
            with tf.GradientTape() as tape:
                tape.watch(v)
                total = tf.add_n([tf.reduce_sum(tf.constant(w, dtype=tf.float64) * o) for o, w in zip(_flat(net({}, *v)), weights) if w is not None])
            return [tf.convert_to_tensor(g).numpy() for g in tape.gradient(total, v, unconnected_gradients=tf.UnconnectedGradients.ZERO)]
        engines["tensorflow"] = lambda g, xs: XGraph(g, tb)({}, *[tf.constant(x, dtype=tf.float64) for x in xs])
        differentiators["tensorflow"] = tf_gradient
    except ImportError:
        absent.append("tensorflow")

    ops = [op for op in sg.OPS if "." in op and not op.startswith(("py.", "sphere.", "sym_")) and op not in sg._CHECKS and op not in _UNHELD]
    if only:
        ops = [op for op in ops if any(o in op for o in ([only] if isinstance(only, str) else only))]
    found, counts, not_aten, files = {}, {}, [], []
    tally = {"cases": 0, "gradients": 0}

    def note(op, engine, where, how, kind, detail):
        e = found.setdefault((op, engine, where, how), {"op": op, "engine": engine, "in": where, "class": how, "n": 0, "kind": kind, "example": detail})
        e["n"] += 1

    def one_case(op, fn, plan, cid):
        arguments = fn._schema.arguments
        if op in recipes:
            values = recipes[op]()
        else:
            shape, values, drawn_ = pick((3, 4), (4,), (2, 3, 2), ()), [], 0
            for p in plan:
                if p == "tensor":
                    values.append(T(shape if not drawn_ else pick(shape, shape[1:], ())))
                    drawn_ += 1
                else:
                    values.append(_DEFAULT if p is None else pick(*p))
        values = list(values) + [_DEFAULT] * (len(arguments) - len(values))
        drawn = []

        def encode(v):
            if isinstance(v, _Drawn):
                drawn.append(v)
                return "n:c%d_%d" % (cid, len(drawn) - 1)
            if isinstance(v, (list, tuple)):
                if v and all(isinstance(e, _Drawn) for e in v):
                    return "T:" + ",".join(encode(e)[2:] for e in v)
                return "L:" + ",".join(encode(e) for e in v)
            if isinstance(v, torch.dtype):
                return "s:" + str(v).replace("torch.", "")
            return sg._encode(v, set())

        enc = [encode(a.default_value if v is _DEFAULT else v) for v, a in zip(values, arguments)]

        def oracle(grad):
            leaves = {}

            def real(v):
                if isinstance(v, _Drawn):
                    t = torch.tensor(v.array, dtype=v.dtype)
                    if grad and v.dtype == torch.float64:
                        leaves[id(v)] = t.requires_grad_()
                    return t
                return [real(e) for e in v] if isinstance(v, list) else v
            pos = [real(v) for v, a in zip(values, arguments) if v is not _DEFAULT and not a.kwarg_only]
            kw = {a.name: real(v) for v, a in zip(values, arguments) if v is not _DEFAULT and a.kwarg_only}
            return fn(*pos, **kw), leaves

        try:
            answer, _ = oracle(False)
        except Exception:
            return None   # not something PyTorch does
        truth = _flat(answer)
        if not all(torch.is_tensor(t) and t.layout == torch.strided for t in truth):
            return None
        if op in _FIRST_ONLY:
            truth = truth[:1]
        if op == "topk.default":   # among equal values PyTorch's order is that of a partial sort: unspecified
            x = values[0].array
            if np.isnan(x).any() or np.unique(x).size != x.size:
                truth = truth[:1]
        truth = [t.detach().double().numpy() for t in truth]
        names = ["c%d_%d" % (cid, i) for i in range(len(drawn))]
        arrays = [d.array for d in drawn]
        node = {"name": "v%d" % cid, "op": op, "args": [], "enc": enc, "dtype": ""}
        graph = {"name": "fuzz", "inputs": names, "input_names": names, "outputs": [node["name"]], "consts": {}, "shapes": {},
                 "nodes": [node], "feeds": "", "framework": "fuzz", "params": {}}
        kind = state["kind"]
        share = _SUM_ROUNDING if op in _SUMMED else 0.0
        tally["cases"] += 1
        for engine, run in engines.items():
            try:
                got = [g.numpy() if hasattr(g, "numpy") else np.asarray(g) for g in _flat(run(graph, arrays))]
            except Exception as e:
                note(op, engine, "value", "error", kind, "%s: %s  [%s]" % (type(e).__name__, str(e)[:200], " ".join(enc)))
                continue
            if len(got) < len(truth):
                note(op, engine, "value", "value", kind, "%d outputs, here %d" % (len(truth), len(got)))
                continue
            for t, g in zip(truth, got):
                d = _difference(t, g, share=share)
                if d:
                    note(op, engine, "value", "kernel" if d[0] == "value" and kind == "edge" and op in _KERNELS else d[0], kind, "%s  [%s]" % (d[1], " ".join(enc)))
        # PyTorch's gradient of a variance over dim=[], or of a scalar's, contradicts its own value (the number of elements taken as 1, or 0 / 0)
        degenerate = op == "var.correction" and (values[0].array.ndim == 0 or (isinstance(values[1], list) and not values[1]))
        if gradients and kind != "edge" and differentiators and not degenerate:
            try:
                out, leaves = oracle(True)
                out = _flat(out)
                if op in _FIRST_ONLY:
                    out = out[:1]
                weights = [rng.normal(size=tuple(o.shape)) if torch.is_tensor(o) and o.requires_grad else None for o in out]
                used = [(o, w) for o, w in zip(out, weights) if w is not None]
                if used and leaves:
                    total = sum((torch.tensor(w, dtype=o.dtype) * o).sum() for o, w in used)
                    want = torch.autograd.grad(total, list(leaves.values()), allow_unused=True)
                else:
                    want = None
            except Exception:
                want = None
            if want is not None:
                tally["gradients"] += 1
                where = [i for i, d in enumerate(drawn) if id(d) in leaves]
                for engine, differentiate in differentiators.items():
                    try:
                        g = differentiate(graph, arrays, weights)
                    except Exception as e:
                        note(op, engine, "gradient", "error", kind, "%s: %s" % (type(e).__name__, str(e)[:200]))
                        continue
                    for i, w in zip(where, want):
                        t = np.zeros_like(arrays[i]) if w is None else w.detach().numpy()
                        d = _difference(t, np.asarray(g[i]), rounding=1e-8, gradient=True, share=share)
                        if d:
                            note(op, engine, "gradient", d[0], kind, "input %d, %s  [%s]" % (i, d[1], " ".join(enc)))
        return {"inputs": dict(zip(names, arrays)), "node": node, "truth": truth, "several": isinstance(answer, (list, tuple))}

    if folder:
        os.makedirs(folder, exist_ok=True)
    with warnings.catch_warnings(), np.errstate(all="ignore"):
        warnings.simplefilter("ignore")
        for at, op in enumerate(ops):
            if at % 15 == 0:
                say("%d of %d operations, %d cases" % (at, len(ops), tally["cases"]))
            state["op"] = op
            packet, _, overload = op.partition(".")
            fn = getattr(getattr(torch.ops.aten, packet, None), overload or "default", None)
            if fn is None:
                not_aten.append(op)
                continue
            plan = None if op in recipes else [_schema_choices(a) for a in fn._schema.arguments]
            if plan is not None and any(p is None and not a.has_default_value() for p, a in zip(plan, fn._schema.arguments)):
                not_aten.append(op)
                continue
            cases = []
            for kind in ("normal", "ties", "edge", "positive"):
                state["kind"] = kind
                for _ in range(rounds):
                    c = one_case(op, fn, plan, len(cases))
                    if c is not None:
                        cases.append(c)
            counts[op] = len(cases)
            if folder and cases:
                try:
                    files.append((os.path.basename(_fuzz_write(op, cases, folder)), _SUM_ROUNDING if op in _SUMMED else 0.0))
                except Exception as e:
                    note(op, "files", "value", "error", "", "%s: %s" % (type(e).__name__, str(e)[:200]))
            if "jax" in engines:
                jx.jax.clear_caches()
    if folder:
        with open(os.path.join(folder, "cases.txt"), "w", encoding="utf-8", newline="\n") as f:
            f.write("".join("%s\t%g\n" % n for n in files))
    entries = sorted(found.values(), key=lambda e: (e["class"], e["op"], e["engine"]))
    defects = [e for e in entries if e["class"] in ("value", "error")]
    count = lambda how: sum(e["n"] for e in entries if e["class"] == how)
    reached = [op for op, c in counts.items() if c]
    out = {"seed": seed, "rounds": rounds, "operations": len(ops), "exercised": len(reached), "cases": tally["cases"], "gradients": tally["gradients"],
           "engines": list(engines), "absent": absent, "not_reached": sorted(set(ops) - set(reached)), "defects": defects,
           "rounding": count("rounding"), "flushed": count("flush"), "undefined": count("undefined"), "kernel": count("kernel"),
           "others": [e for e in entries if e["class"] not in ("value", "error")], "folder": folder, "files": len(files),
           "seconds": time.time() - t0, "ok": not defects and len(reached) == len(ops)}
    out["verdict"] = "%s: %d of %d operations against PyTorch %s, %d cases, %d gradients in %s; %d differences of a framework's rounding, %d of subnormals flushed to zero, %d of a convolution kernel on NaN or inf, %d gradients PyTorch has as NaN or infinite%s%s" % (
        "Conformant" if not defects else "%d DIFFERENCES" % len(defects), len(reached), len(ops), torch.__version__.split("+")[0], tally["cases"], tally["gradients"],
        ", ".join(engines), out["rounding"], out["flushed"], out["kernel"], out["undefined"],
        "" if len(reached) == len(ops) else "; not reached: " + ", ".join(out["not_reached"][:8]),
        "; absent: " + ", ".join(absent) if absent else "")
    return out


if __name__ == "__main__":
    import json
    import sys
    print(json.dumps(check(sys.argv[1], engines=tuple(sys.argv[2].split(",")) if len(sys.argv) > 2 else
                           ("torch", "jax", "tensorflow"), report=print), indent=1, default=str))

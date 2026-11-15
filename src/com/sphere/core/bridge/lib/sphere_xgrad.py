"""sphere_xgrad -- TensorFlow, PyTorch and JAX inside one differentiable computation.

    import sphere_xgrad as xg

    surrogate = xg.as_torch(keras_model)         # a TensorFlow model as a layer of a PyTorch network
    loss = surrogate(torch_net(x)).mean()
    loss.backward()                              # through PyTorch, into TensorFlow, and back

    layer = xg.as_tensorflow(torch_module)       # a PyTorch module in a GradientTape, in a tf.function, in Keras's fit
    f = jax.jit(xg.as_jax(torch_module))         # the same in JAX, under jit and grad

Each framework stays what it is and differentiates its own operations. Between two of them only numbers pass, and the
one thing reverse-mode differentiation needs of a piece it cannot see into: its vector-Jacobian product. So a function
of any framework is first reduced to a neutral form -- arrays in, arrays out, and a way back from cotangents to
gradients -- and any framework can take that form as one of its own differentiable operations. Three sources and three
receivers, rather than a converter for each pair; and since a receiver gives back an ordinary function of its framework,
bridges nest: PyTorch calling JAX calling TensorFlow calling PyTorch is one chain rule.

The framework a function is written in is found from the function: a module's class, or what its code refers to.
Inside a tf.function or a jax.jit, where shapes must be known before anything runs, the shapes of the answer are found by
running the function on two batches and seeing which dimensions follow the batch.

check() holds every pair to the analytic gradient, eagerly and under tf.function and jax.jit, the three-framework chain
included, and a network trained through a foreign loss to the same network trained without leaving PyTorch.
"""

import itertools
import math
import threading
import time

import numpy as np

FRAMEWORKS = ("torch", "tensorflow", "jax")
_ROOTS = {"torch": "torch", "tensorflow": "tensorflow", "tf_keras": "tensorflow", "jax": "jax", "flax": "jax", "equinox": "jax"}


# ---------------------------------------------------------------------------
# Which framework a function is written in
# ---------------------------------------------------------------------------

def _root(module):
    return (module or "").split(".")[0]


def _keras_backend(default="tensorflow"):
    try:
        import keras
        return keras.backend.backend()
    except Exception:
        return default


def framework_of(f):
    """"torch", "tensorflow" or "jax", from what f is: the class of a module or a bound method, else what its code refers to."""
    if isinstance(f, _Bridged):
        return f.framework
    owner = getattr(f, "__self__", None)
    for cls in type(owner if owner is not None else f).__mro__:
        root = _root(cls.__module__)
        if root in _ROOTS:
            return _ROOTS[root]
        if root == "keras":
            return _keras_backend()
    found = set()

    def look(code, scope):
        for name in code.co_names:
            value = scope.get(name)
            root = _root(getattr(value, "__name__", "") if value is not None and hasattr(value, "__name__") else
                         type(value).__module__ if value is not None else "")
            if root in _ROOTS:
                found.add(_ROOTS[root])
            elif root == "keras":
                found.add(_keras_backend())
        for const in code.co_consts:
            if hasattr(const, "co_names"):
                look(const, scope)

    code = getattr(f, "__code__", None)
    if code is not None:
        scope = dict(getattr(f, "__globals__", {}))
        for cell_name, cell in zip(code.co_freevars, getattr(f, "__closure__", None) or ()):
            try:
                scope[cell_name] = cell.cell_contents
            except ValueError:
                pass
        look(code, scope)
    if len(found) == 1:
        return found.pop()
    raise ValueError("cannot tell which framework %r is written in%s: say it (source='torch', 'tensorflow' or 'jax')"
                     % (f, " (it uses %s)" % ", ".join(sorted(found)) if found else ""))


# ---------------------------------------------------------------------------
# A function as numbers and a way back
# ---------------------------------------------------------------------------

class _Neutral:
    """call(*arrays) -> (outputs as a tuple of arrays, vjp); vjp(*cotangents) -> one gradient array per input. single: the
    function returned one value rather than a tuple."""

    def __init__(self):
        self.single = True


def _outputs(value, neutral):
    if isinstance(value, dict):         # a SavedModel's signature names its outputs
        value = value[next(iter(sorted(value)))] if len(value) == 1 else tuple(value[k] for k in sorted(value))
    if isinstance(value, (tuple, list)):
        neutral.single = len(value) == 1
        return tuple(value)
    neutral.single = True
    return (value,)


def _from_torch(f):
    import torch
    neutral = _Neutral()

    def call(*arrays):
        xs = [torch.tensor(np.asarray(a), requires_grad=np.asarray(a).dtype.kind == "f") for a in arrays]
        with torch.enable_grad():       # this may be running inside another bridge's forward, where PyTorch has turned it off
            ys = _outputs(f(*xs), neutral)
        values = tuple(y.detach().cpu().numpy() for y in ys)

        def vjp(*cotangents):
            wrt = [x for x in xs if x.requires_grad]
            pairs = [(y, c) for y, c in zip(ys, cotangents) if y.requires_grad and c is not None]
            with torch.enable_grad():
                grads = torch.autograd.grad([y for y, _ in pairs], wrt, [torch.as_tensor(np.array(c), dtype=y.dtype) for y, c in pairs],
                                            allow_unused=True, retain_graph=True) if pairs and wrt else [None] * len(wrt)
            it = iter(grads)
            out = []
            for x in xs:
                g = next(it) if x.requires_grad else None
                out.append(np.zeros(x.shape, dtype=x.detach().numpy().dtype) if g is None else g.detach().cpu().numpy())
            return tuple(out)

        return values, vjp

    neutral.call = call
    return neutral


def _from_tensorflow(f):
    import tensorflow as tf
    neutral = _Neutral()

    def call(*arrays):
        xs = [tf.constant(np.asarray(a)) for a in arrays]
        with tf.GradientTape(persistent=True) as tape:
            tape.watch(xs)
            ys = _outputs(f(*xs), neutral)
        ys = [tf.convert_to_tensor(y) for y in ys]
        values = tuple(y.numpy() for y in ys)

        def vjp(*cotangents):
            given = [tf.constant(np.array(c), dtype=y.dtype) if c is not None else tf.zeros_like(y) for y, c in zip(ys, cotangents)]
            grads = tape.gradient(ys, xs, output_gradients=given, unconnected_gradients=tf.UnconnectedGradients.ZERO)
            return tuple(tf.convert_to_tensor(g).numpy() for g in grads)

        return values, vjp

    neutral.call = call
    return neutral


def _from_jax(f):
    import jax
    import jax.numpy as jnp
    neutral = _Neutral()

    def call(*arrays):
        ys, pull = jax.vjp(lambda *x: _outputs(f(*x), neutral), *[jnp.asarray(np.asarray(a)) for a in arrays])
        values = tuple(np.asarray(y) for y in ys)

        def vjp(*cotangents):
            given = tuple(jnp.asarray(np.array(c), dtype=y.dtype) if c is not None else jnp.zeros_like(y) for y, c in zip(ys, cotangents))
            return tuple(np.array(g) for g in pull(given))

        return values, vjp

    neutral.call = call
    return neutral


_SOURCES = {"torch": _from_torch, "tensorflow": _from_tensorflow, "jax": _from_jax}


def _x64():
    import jax
    jax.config.update("jax_enable_x64", True)


def _neutral(f, source):
    source = source or framework_of(f)
    if source not in _SOURCES:
        raise ValueError("no framework %s (%s)" % (source, ", ".join(FRAMEWORKS)))
    if source == "jax":
        _x64()
    return source, _SOURCES[source](f)


class _Bridged:
    """A function given to a framework it was not written in: an ordinary function of that framework."""

    def __init__(self, framework, source, fn):
        self.framework, self.source, self.fn = framework, source, fn

    def __call__(self, *args):
        return self.fn(*args)

    def __repr__(self):
        return "<a %s function, as a %s one>" % (self.source, self.framework)


# ---------------------------------------------------------------------------
# PyTorch takes it
# ---------------------------------------------------------------------------

def as_torch(f, source=None):
    """f, of another framework, as a function of torch tensors that autograd differentiates."""
    import torch
    source, neutral = _neutral(f, source)
    if source == "torch":
        return f

    class Crossing(torch.autograd.Function):
        @staticmethod
        def forward(ctx, *xs):
            values, vjp = neutral.call(*[x.detach().cpu().numpy() for x in xs])
            ctx.vjp, ctx.inputs = vjp, [(x.dtype, x.device, x.is_floating_point()) for x in xs]
            device = xs[0].device if xs else None
            return tuple(torch.as_tensor(np.array(v), device=device) for v in values)

        @staticmethod
        def backward(ctx, *cotangents):
            grads = ctx.vjp(*[None if c is None else c.detach().cpu().numpy() for c in cotangents])
            return tuple(torch.as_tensor(np.array(g), dtype=d, device=at) if floating else None for g, (d, at, floating) in zip(grads, ctx.inputs))

    def fn(*xs):
        xs = [x if torch.is_tensor(x) else torch.as_tensor(np.asarray(x)) for x in xs]
        out = Crossing.apply(*xs)
        return out[0] if neutral.single else out

    return _Bridged("torch", source, fn)


# ---------------------------------------------------------------------------
# TensorFlow takes it
# ---------------------------------------------------------------------------

class _Pending:
    """The way back of each call made inside a graph, until its gradient asks for it."""

    def __init__(self, keep=4096):
        self.lock, self.items, self.keep, self.ids = threading.Lock(), {}, keep, itertools.count(1)

    def put(self, vjp):
        with self.lock:
            k = next(self.ids)
            self.items[k] = vjp
            while len(self.items) > self.keep:
                del self.items[next(iter(self.items))]
            return k

    def take(self, k):
        with self.lock:
            return self.items[int(k)]


def as_tensorflow(f, source=None):
    """f, of another framework, as a function of TensorFlow tensors: GradientTape differentiates it, eagerly and in a
    tf.function, Keras's fit included."""
    import tensorflow as tf
    source, neutral = _neutral(f, source)
    if source == "tensorflow":
        return f
    pending = _Pending()
    known = {}

    def answer_of(xs):
        """The dtypes and shapes of the outputs, a dimension None where it follows the batch: two runs, two batches."""
        key = tuple((x.dtype.name, tuple(x.shape.as_list())) for x in xs)
        if key not in known:
            def probe(free):
                return neutral.call(*[np.full([free if d is None else d for d in x.shape.as_list()], 0.5, x.dtype.as_numpy_dtype) for x in xs])[0]
            a, b = probe(2), probe(3)
            known[key] = ([tf.as_dtype(np.asarray(v).dtype) for v in a],
                          [[d if d == e else None for d, e in zip(np.shape(v), np.shape(w))] for v, w in zip(a, b)])
        return known[key]

    @tf.custom_gradient
    def eagerly(*xs):
        values, vjp = neutral.call(*[x.numpy() for x in xs])
        ys = [tf.constant(np.array(v)) for v in values]

        def grad(*cotangents, variables=None):
            gs = vjp(*[None if c is None else c.numpy() for c in cotangents])
            return [tf.constant(np.array(g), dtype=x.dtype) for g, x in zip(gs, xs)]

        return (ys[0] if neutral.single else tuple(ys)), grad

    @tf.custom_gradient
    def in_graph(*xs):
        dtypes, shapes = answer_of(xs)

        def forward(*arrays):
            values, vjp = neutral.call(*arrays)
            return [np.array(v, dtype=t.as_numpy_dtype) for v, t in zip(values, dtypes)] + [np.int64(pending.put(vjp))]

        out = tf.numpy_function(forward, list(xs), dtypes + [tf.int64], stateful=True)
        token, ys = out[-1], list(out[:-1])
        for y, s in zip(ys, shapes):
            y.set_shape(s)

        def grad(*cotangents, variables=None):
            given = [tf.zeros_like(y) if c is None else c for y, c in zip(ys, cotangents)]

            def backward(k, *cs):
                return [np.array(g, dtype=x.dtype.as_numpy_dtype) for g, x in zip(pending.take(k)(*cs), xs)]

            gs = tf.numpy_function(backward, [token] + given, [x.dtype for x in xs], stateful=True)
            for g, x in zip(gs, xs):
                g.set_shape(x.shape)
            return list(gs)

        return (ys[0] if neutral.single else tuple(ys)), grad

    def fn(*xs):
        xs = [tf.convert_to_tensor(x) for x in xs]
        return eagerly(*xs) if tf.executing_eagerly() else in_graph(*xs)

    return _Bridged("tensorflow", source, fn)


# ---------------------------------------------------------------------------
# JAX takes it
# ---------------------------------------------------------------------------

def as_jax(f, source=None):
    """f, of another framework, as a function of JAX arrays: under jax.grad and jax.jit (jax.vmap runs it row by row)."""
    import jax
    import jax.numpy as jnp
    _x64()
    source, neutral = _neutral(f, source)
    if source == "jax":
        return f
    known = {}

    def answer_of(xs):
        key = tuple((tuple(x.shape), str(x.dtype)) for x in xs)
        if key not in known:
            values = neutral.call(*[np.full(x.shape, 0.5, np.dtype(str(x.dtype))) for x in xs])[0]
            known[key] = tuple(jax.ShapeDtypeStruct(np.shape(v), np.asarray(v).dtype) for v in values)
        return known[key]

    def run(xs):
        shapes = answer_of(xs)

        def host(*arrays):
            return tuple(np.array(v, dtype=s.dtype) for v, s in zip(neutral.call(*[np.array(a) for a in arrays])[0], shapes))

        out = jax.pure_callback(host, shapes, *xs, vmap_method="sequential")
        return out[0] if neutral.single else out

    @jax.custom_vjp
    def crossing(*xs):
        return run(xs)

    def fwd(*xs):
        return run(xs), xs

    def bwd(xs, cotangent):
        given = cotangent if isinstance(cotangent, tuple) else (cotangent,)
        wanted = tuple(jax.ShapeDtypeStruct(x.shape, x.dtype) for x in xs)

        def host(inputs, cs):
            grads = neutral.call(*[np.array(i) for i in inputs])[1](*[np.array(c) for c in cs])
            return tuple(np.array(g, dtype=s.dtype) for g, s in zip(grads, wanted))

        return jax.pure_callback(host, wanted, xs, given, vmap_method="sequential")

    crossing.defvjp(fwd, bwd)

    def fn(*xs):
        return crossing(*[jnp.asarray(x) for x in xs])

    return _Bridged("jax", source, fn)


_RECEIVERS = {"torch": as_torch, "tensorflow": as_tensorflow, "jax": as_jax}


def bridge(f, to, source=None):
    """f as a function of the framework `to`."""
    if to not in _RECEIVERS:
        raise ValueError("no framework %s (%s)" % (to, ", ".join(FRAMEWORKS)))
    return _RECEIVERS[to](f, source)


# ---------------------------------------------------------------------------
# Held to the analytic gradient
# ---------------------------------------------------------------------------

def _same_math():
    """f(x, y) = (sum_i sin(x_i) y_i^2, x * y), written in each framework; its value and its vector-Jacobian product by hand."""
    import jax.numpy as jnp
    import tensorflow as tf
    import torch
    fns = {"torch": lambda x, y: ((torch.sin(x) * y ** 2).sum(-1), x * y),
           "tensorflow": lambda x, y: (tf.reduce_sum(tf.sin(x) * y ** 2, axis=-1), x * y),
           "jax": lambda x, y: ((jnp.sin(x) * y ** 2).sum(-1), x * y)}

    def value(x, y):
        return (np.sin(x) * y ** 2).sum(-1), x * y

    def pull(x, y, u, v):
        return np.cos(x) * y ** 2 * u[..., None] + y * v, 2 * np.sin(x) * y * u[..., None] + x * v

    return fns, value, pull


def _in_torch(fn, x, y, u, v):
    import torch
    a, b = torch.tensor(x, requires_grad=True), torch.tensor(y, requires_grad=True)
    p, q = fn(a, b)
    ((p * torch.as_tensor(u)).sum() + (q * torch.as_tensor(v)).sum()).backward()
    return (p.detach().numpy(), q.detach().numpy()), (a.grad.numpy(), b.grad.numpy())


def _in_tf(fn, x, y, u, v):
    import tensorflow as tf
    a, b = tf.constant(x), tf.constant(y)
    with tf.GradientTape() as tape:
        tape.watch([a, b])
        p, q = fn(a, b)
        s = tf.reduce_sum(p * u) + tf.reduce_sum(q * v)
    ga, gb = tape.gradient(s, [a, b])
    return (p.numpy(), q.numpy()), (ga.numpy(), gb.numpy())


def _in_tf_graph(fn, x, y, u, v):
    import tensorflow as tf

    @tf.function(input_signature=[tf.TensorSpec([None, x.shape[1]], tf.float64)] * 2, autograph=False)
    def step(a, b):
        with tf.GradientTape() as tape:
            tape.watch([a, b])
            p, q = fn(a, b)
            s = tf.reduce_sum(p * u) + tf.reduce_sum(q * v)
        return p, q, tape.gradient(s, [a, b])

    p, q, g = step(tf.constant(x), tf.constant(y))
    return (p.numpy(), q.numpy()), (g[0].numpy(), g[1].numpy())


def _in_jax(fn, x, y, u, v, jit=False):
    import jax
    import jax.numpy as jnp

    def scalar(a, b):
        p, q = fn(a, b)
        return (p * u).sum() + (q * v).sum(), (p, q)

    run = jax.value_and_grad(scalar, argnums=(0, 1), has_aux=True)
    run = jax.jit(run) if jit else run
    (_, (p, q)), (ga, gb) = run(jnp.asarray(x), jnp.asarray(y))
    return (np.asarray(p), np.asarray(q)), (np.asarray(ga), np.asarray(gb))


_MODES = {"torch": [("autograd", _in_torch)], "tensorflow": [("eager", _in_tf), ("tf.function", _in_tf_graph)],
          "jax": [("grad", _in_jax), ("jit", lambda *a: _in_jax(*a, jit=True))]}


def _relative(got, want):
    return max(float(np.max(np.abs(g - w) / (np.abs(w) + 1.0))) for g, w in zip(got, want))


def _train_through(seed=0, steps=25):
    """A PyTorch network trained on a loss written in TensorFlow, against the same network trained on the same loss written in
    PyTorch: the same weights at the end, or the bridge is wrong."""
    import tensorflow as tf
    import torch

    def net():
        torch.manual_seed(seed)
        return torch.nn.Sequential(torch.nn.Linear(3, 8), torch.nn.Tanh(), torch.nn.Linear(8, 1)).double()

    rng = np.random.default_rng(seed)
    x = torch.as_tensor(rng.normal(size=(64, 3)))
    t = torch.as_tensor((rng.normal(size=(64, 1)) + 1.0) * 0.5)

    def pure(y, target):
        return torch.mean((torch.sin(y) - target) ** 2 + 0.1 * y ** 4)

    foreign = as_torch(lambda y, target: tf.reduce_mean((tf.sin(y) - target) ** 2 + 0.1 * y ** 4), "tensorflow")

    def run(loss):
        m = net()
        opt = torch.optim.Adam(m.parameters(), lr=0.01)
        history = []
        for _ in range(steps):
            opt.zero_grad()
            v = loss(m(x), t)
            v.backward()
            opt.step()
            history.append(float(v.detach()))
        return np.concatenate([p.detach().numpy().ravel() for p in m.parameters()]), history

    w0, h0 = run(pure)
    w1, h1 = run(foreign)
    return {"weights": float(np.max(np.abs(w0 - w1))), "loss_first": h0[0], "loss_last": h0[-1], "steps": steps}


def _keras_and_module():
    """Real models rather than functions: a Keras model in PyTorch, a PyTorch module in TensorFlow, each through its float32."""
    import tensorflow as tf
    import torch
    out = {}
    tf.keras.utils.set_random_seed(0)
    inp = tf.keras.Input((3,))
    k = tf.keras.Model(inp, tf.keras.layers.Dense(2)(tf.keras.layers.Dense(4, activation="tanh")(inp)))
    x = np.random.default_rng(1).normal(size=(5, 3)).astype(np.float32)
    in_torch = as_torch(k)
    a = torch.tensor(x, requires_grad=True)
    y = in_torch(a)
    y.sum().backward()
    t = tf.constant(x)
    with tf.GradientTape() as tape:
        tape.watch(t)
        s = tf.reduce_sum(k(t))
    out["keras in torch"] = float(np.max(np.abs(a.grad.numpy() - tape.gradient(s, t).numpy())))
    torch.manual_seed(0)
    m = torch.nn.Sequential(torch.nn.Linear(3, 4), torch.nn.Tanh(), torch.nn.Linear(4, 2))
    in_tf = as_tensorflow(m)
    t = tf.constant(x)
    with tf.GradientTape() as tape:
        tape.watch(t)
        s = tf.reduce_sum(in_tf(t))
    a = torch.tensor(x, requires_grad=True)
    m(a).sum().backward()
    out["module in tf"] = float(np.max(np.abs(a.grad.numpy() - tape.gradient(s, t).numpy())))
    return out


def check(seed=3, report=None):
    """Every pair of frameworks, in every mode, against the analytic gradient; three frameworks in one chain rule; a training
    through a foreign loss; real models. Differences relative to the values' scale, in float64 unless a model is float32."""
    say = report or (lambda message: None)
    t0 = time.time()
    fns, value, pull = _same_math()
    rng = np.random.default_rng(seed)
    x, y = rng.normal(size=(4, 5)), rng.normal(size=(4, 5))
    u, v = rng.normal(size=(4,)), rng.normal(size=(4, 5))
    want, wgrad = value(x, y), pull(x, y, u, v)
    pairs = {}
    worst = 0.0
    for source in FRAMEWORKS:
        for target in FRAMEWORKS:
            if source == target:
                continue
            fn = bridge(fns[source], target, source)
            for mode, run in _MODES[target]:
                got, grad = run(fn, x, y, u, v)
                d = max(_relative(got, want), _relative(grad, wgrad))
                pairs["%s in %s (%s)" % (source, target, mode)] = d
                worst = max(worst, d)
                say("  %-11s in %-11s %-12s %.1e" % (source, target, mode, d))
    # torch -> tensorflow -> jax -> torch, the outermost call in PyTorch
    inner = as_tensorflow(fns["torch"], "torch")
    middle = as_jax(inner, "tensorflow")
    outer = as_torch(middle, "jax")
    got, grad = _in_torch(outer, x, y, u, v)
    chain = max(_relative(got, want), _relative(grad, wgrad))
    say("  torch -> jax -> tensorflow -> torch, one chain rule: %.1e" % chain)
    trained = _train_through(seed)
    say("  a network trained through a TensorFlow loss: weights within %.1e of PyTorch alone (loss %.4f -> %.4f)"
        % (trained["weights"], trained["loss_first"], trained["loss_last"]))
    models = _keras_and_module()
    for k, d in models.items():
        say("  %s: gradient within %.1e of the model's own" % (k, d))
    good = worst < 1e-12 and chain < 1e-12 and trained["weights"] < 1e-10 and all(d < 1e-5 for d in models.values())
    return {"pairs": pairs, "chain": chain, "trained": trained, "models": models, "worst": worst, "ok": good, "seconds": time.time() - t0,
            "verdict": ("every framework differentiates through every other: %d crossings within %.1e of the analytic gradient, three frameworks in "
                        "one chain rule within %.1e, a network trained through a foreign loss within %.1e of the same training in PyTorch alone")
                       % (len(pairs), worst, chain, trained["weights"])}

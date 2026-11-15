"""sphere_dana -- an analysis made differentiable: a network trained for the physics result itself.

    import sphere_dana as dana
    r = dana.optimize("toyjets", model="deepsets", target="cls", norm=0.2, jes=0.1, epochs=25)
    r["analysis"]["cls"], r["bce"]["cls"]     # the expected CLs (mu = 1) of the network trained for it, and of the
                                              # same network trained to classify -- each also computed by pyhf
    dana.crosscheck("toyjets")                # the loss and its gradient in PyTorch, JAX (and TensorFlow), held
                                              # to each other, to numpy, to finite differences; the CLs to pyhf's

A classifier is trained to separate signal from background; an analysis wants a limit -- and between the two stand
the binning, the systematic uncertainties and the profile likelihood, which a classifier never sees (Cowan,
Cranmer, Gross, Vitells 2011; de Castro, Dorigo 2019, INFERNO; Simpson, Heinrich 2023, neos). Here all of it is
differentiable, and the network learns what the limit needs:

  * the network's scores fill soft histograms: each jet spreads over the bins by a Gaussian kernel of width
    `bandwidth`, the outer bins open to -inf and +inf so that no jet falls out -- the hard histogram as it goes to 0;
  * the bins make a HistFactory model as pyhf writes it, its interpolations pyhf's (and ROOT's) own: the signal
    scaled by mu; the background scaled by a normsys (code4: exponential beyond |a| = 1, a polynomial of degree 6
    within, smooth at 0), shaped by a histosys (code4p) whose templates are the network's own scores on the
    background with its jet energy scale moved by +-jes -- the systematic follows what the network learns, and so
    does its cost -- and its Monte Carlo statistics a staterror (Barlow-Beeston lite: a factor gamma per bin,
    constrained by the bin's own sqrt(sum w^2) / sum w, soft too): a bin the batch fills with three jets is worth
    what three jets are, and the network cannot win by emptying the background out of a bin;
  * the target is the expected CLs at mu = 1 of the median background-only experiment, asymptotically
    2 (1 - Phi(sqrt(q_1))) = erfc(sqrt(q_1 / 2)), q_1 = 2 (NLL(1, theta^^) - NLL(0, 0)) on the Asimov data -- q
    summed bin by bin as Poisson deviances, never as the difference of two large NLLs, and its log CLs computed
    without underflow where CLs is tiny; or the discovery significance Z_0;
  * the nuisances theta^^ (two systematics and a gamma a bin) are profiled in float64, without gradients: the gammas
    in closed form, Newton's method on the two systematics alone (see profile). The gradient is exact all the same: by the envelope theorem, d/dphi min_theta NLL(theta, phi) =
    dNLL/dphi at theta = theta^^(phi), the fit's own gradient in theta being zero there. The likelihood is evaluated
    at the fit's result with gradients -- nothing differentiated through the fit, no implicit function to solve.

The same functions run in numpy (the reference), PyTorch, JAX and TensorFlow; pyhf judges the result on the hard
histograms of the trained network. The network is any of sphere_ml's zoo, LorentzNet and PELICAN included.
"""

import math
import time

import numpy as np

import sphere_ml as ml

TARGETS = ("cls", "z0", "bce")


# ---------------------------------------------------------------------------
# Backends: the network's operations (sphere_ml's) and the analysis's, in numpy, PyTorch, JAX and TensorFlow
# ---------------------------------------------------------------------------

def _numpy_backend():
    from scipy import special

    class NumpyAna(ml.NumpyOps):
        name = "numpy"
        erfc = staticmethod(special.erfc)
        log1p = staticmethod(np.log1p)
        where = staticmethod(np.where)
        stop = staticmethod(lambda x: x)
        const = staticmethod(lambda v: np.asarray(v, dtype=np.float64))
        value = staticmethod(lambda x: np.asarray(x, dtype=np.float64))
        sigmoid = staticmethod(lambda x: 1.0 / (1.0 + np.exp(-x)))

    return NumpyAna


def _torch_backend():
    import torch
    base = ml._torch_ops()

    class TorchAna(base):
        name = "torch"
        erfc = staticmethod(torch.erfc)
        log1p = staticmethod(torch.log1p)
        where = staticmethod(torch.where)
        stop = staticmethod(lambda x: x.detach())
        const = staticmethod(lambda v: torch.as_tensor(np.asarray(v, dtype=np.float64)))
        value = staticmethod(lambda x: x.detach().cpu().double().numpy())
        sigmoid = staticmethod(torch.sigmoid)
        ones = staticmethod(lambda shape: torch.ones(shape, dtype=torch.float64))
        eye = staticmethod(lambda n: torch.eye(n, dtype=torch.float64))

    return TorchAna


def _numpylike(name, xp, erfc, stop, const, value, smallest, softmax, sigmoid, relu):
    """sphere_ml's operations, and the analysis's, from a module that speaks numpy: jax.numpy, TensorFlow's numpy.
    relu is the framework's own: maximum(x, 0) differentiates to 1/2 (JAX) or 1 (TensorFlow) exactly at x = 0, where
    torch.relu gives 0 -- and a bias initialized to 0 on a null input (PELICAN's diagonal) is exactly there."""
    s = staticmethod
    ns = {
        "name": name,
        "dense": s(lambda x, W, b: xp.matmul(x, W) + b), "relu": s(relu),
        "exp": s(xp.exp), "log": s(xp.log), "sqrt": s(xp.sqrt), "sin": s(xp.sin), "cos": s(xp.cos),
        "sinh": s(xp.sinh), "cosh": s(xp.cosh), "abs": s(xp.abs), "minimum": s(xp.minimum), "maximum": s(xp.maximum),
        "sign": s(xp.sign), "eye_like": s(lambda n, ref: xp.eye(n, dtype=ref.dtype)),
        "concat": s(lambda xs, axis: xp.concatenate(xs, axis=axis)), "stack": s(lambda xs, axis: xp.stack(xs, axis=axis)),
        "reshape": s(lambda x, shape: xp.reshape(x, shape)), "expand": s(lambda x, axis: xp.expand_dims(x, axis)),
        "transpose": s(lambda x, perm: xp.transpose(x, perm)),
        "sum": s(lambda x, axis, keep=False: xp.sum(x, axis=axis, keepdims=keep)),
        "mean": s(lambda x, axis, keep=False: xp.mean(x, axis=axis, keepdims=keep)),
        "matmul": s(xp.matmul), "ones": s(lambda shape: xp.ones(shape)), "eye": s(lambda n: xp.eye(n)),
        "zeros_like": s(xp.zeros_like), "ones_like": s(xp.ones_like),
        "take_along": s(lambda x, idx, axis: xp.take_along_axis(x, idx, axis=axis)),
        "smallest": s(smallest), "softmax": s(softmax), "sigmoid": s(sigmoid),
        "erfc": s(erfc), "log1p": s(xp.log1p), "where": s(xp.where), "stop": s(stop), "const": s(const),
        "value": s(value),
    }
    return type(name.capitalize() + "Ana", (), ns)


def _jax_backend():
    import jax
    jax.config.update("jax_enable_x64", True)
    import jax.numpy as jnp
    from jax.scipy import special
    return _numpylike("jax", jnp, special.erfc, jax.lax.stop_gradient,
                      lambda v: jnp.asarray(np.asarray(v, dtype=np.float64)), lambda x: np.asarray(x, dtype=np.float64),
                      lambda d, k: jnp.argsort(d, axis=-1, stable=True)[..., :k],
                      lambda x, axis: jax.nn.softmax(x, axis=axis), jax.nn.sigmoid, jax.nn.relu)


def _tf_backend():
    import tensorflow as tf
    tnp = tf.experimental.numpy
    tnp.experimental_enable_numpy_behavior(prefer_float32=False)
    return _numpylike("tensorflow", tnp, tf.math.erfc, tf.stop_gradient,
                      lambda v: tf.constant(np.asarray(v, dtype=np.float64)),
                      lambda x: np.asarray(x.numpy() if hasattr(x, "numpy") else x, dtype=np.float64),
                      lambda d, k: tf.math.top_k(-d, k).indices, lambda x, axis: tf.nn.softmax(x, axis=axis),
                      tf.math.sigmoid, tf.nn.relu)


BACKENDS = {"numpy": _numpy_backend, "torch": _torch_backend, "jax": _jax_backend, "tensorflow": _tf_backend}


def backend(name):
    if name not in BACKENDS:
        raise ValueError("no backend %s (%s)" % (name, ", ".join(BACKENDS)))
    return BACKENDS[name]()


# ---------------------------------------------------------------------------
# Soft histograms
# ---------------------------------------------------------------------------

def inner_edges(bins):
    """The bins' inner edges on [0, 1]: the outer two bins run to -inf and +inf."""
    return np.linspace(0.0, 1.0, int(bins) + 1)[1:-1]


def _kernel(O, s, inner, h):
    """Each jet's share of each bin: Phi((e_{k+1} - s) / h) - Phi((e_k - s) / h), Phi(x) = erfc(-x / sqrt 2) / 2,
    e_0 = -inf and e_K = +inf, so that the shares of a jet add up to 1."""
    c = 0.5 * O.erfc((O.expand(s, -1) - O.const(inner)) / (h * math.sqrt(2.0)))
    cdf = O.concat([O.zeros_like(c[..., :1]), c, O.ones_like(c[..., :1])], -1)
    return cdf[..., 1:] - cdf[..., :-1]


def soft_hist(O, s, w, inner, h, squares=False):
    """The soft histogram sum_i w_i K_ik -- or, with squares, sum_i (w_i K_ik)^2, its Monte Carlo variance."""
    k = _kernel(O, s, inner, h) * O.expand(w, -1)
    return O.sum(k * k if squares else k, 0)


def hard_hist(s, w, inner, squares=False):
    """The histogram the soft one becomes as its bandwidth goes to 0 (with squares: sum w^2 a bin)."""
    k = np.searchsorted(inner, s, side="right")
    return np.bincount(k, weights=np.asarray(w) ** 2 if squares else w, minlength=len(inner) + 1).astype(np.float64)


# ---------------------------------------------------------------------------
# The HistFactory model, as pyhf builds it
# ---------------------------------------------------------------------------

def code4_coefficients(norm):
    """pyhf's normsys interpolation (code4, ROOT's default too): (1 + norm)^a above a = 1, (1 - norm)^-a below
    a = -1, and between them the polynomial 1 + sum_{i=1}^{6} c_i a^i that meets those two exponentials' values,
    slopes and curvatures at a = +-1 -- smooth through 0, where code1 has a kink."""
    if norm <= 0:
        return np.zeros(6)
    rows, rhs = [], []
    for a, base in ((1.0, 1.0 + norm), (-1.0, 1.0 - norm)):
        L = math.log(base)
        rows.append([a ** i for i in range(1, 7)])
        rhs.append(base - 1.0)
        rows.append([i * a ** (i - 1) for i in range(1, 7)])
        rhs.append(L * base * a)                     # d/da base^|a| at a = +-1
        rows.append([i * (i - 1) * a ** (i - 2) for i in range(1, 7)])
        rhs.append(L * L * base)
    return np.linalg.solve(np.array(rows), np.array(rhs))


def kappa(norm, c, a):
    """code4's factor at a, with its first and second derivatives in a."""
    if norm <= 0:
        return 1.0, 0.0, 0.0
    if a >= 1.0:
        L = math.log1p(norm)
        v = math.exp(a * L)
        return v, L * v, L * L * v
    if a <= -1.0:
        L = math.log1p(-norm)
        v = math.exp(-a * L)
        return v, -L * v, L * L * v
    v = 1.0 + sum(c[i] * a ** (i + 1) for i in range(6))
    d1 = sum((i + 1) * c[i] * a ** i for i in range(6))
    d2 = sum((i + 1) * i * c[i] * a ** (i - 1) for i in range(1, 6))
    return v, d1, d2


def code4p(a):
    """pyhf's histosys interpolation (code4p), as the weights of (b_up - b) and (b - b_down) in the shift at a: linear
    beyond |a| = 1, inside it a S + (3a^6 - 10a^4 + 15a^2) A with S = (d_up + d_dn) / 2, A = (d_up - d_dn) / 16 --
    and those weights' first and second derivatives in a."""
    if a > 1.0:
        return (a, 0.0), (1.0, 0.0), (0.0, 0.0)
    if a < -1.0:
        return (0.0, a), (0.0, 1.0), (0.0, 0.0)
    p = a * a * (a * a * (3.0 * a * a - 10.0) + 15.0)
    p1 = 18.0 * a ** 5 - 40.0 * a ** 3 + 30.0 * a
    p2 = 90.0 * a ** 4 - 120.0 * a ** 2 + 30.0
    return (0.5 * a + p / 16.0, 0.5 * a - p / 16.0), (0.5 + p1 / 16.0, 0.5 - p1 / 16.0), (p2 / 16.0, -p2 / 16.0)


class Hists:
    """An analysis's histograms (any framework's tensors): the signal s, the background b, its histosys templates up
    and dn, and its Monte Carlo uncertainty unc (absolute, a bin)."""

    def __init__(self, s, b, up, dn, unc):
        self.s, self.b, self.up, self.dn, self.unc = s, b, up, dn, unc

    def values(self, O):
        return Hists(*(O.value(v) for v in (self.s, self.b, self.up, self.dn, self.unc)))


def expected(O, H, mu, a, norm):
    """mu s + gamma kappa(a_norm) (b + delta(a_jes)): pyhf's background -- the histosys added to the nominal, the
    normsys and the staterror's gamma multiplying it -- the nuisances a = (a_norm, a_jes, gamma_1..K) fixed numbers,
    the histograms tensors."""
    an, aj, gam = float(a[0]), float(a[1]), np.asarray(a[2:], dtype=np.float64)
    k = kappa(norm, code4_coefficients(norm), an)[0]
    (wu, wd), _, _ = code4p(aj)
    return mu * H.s + O.const(k * gam) * (H.b + wu * (H.up - H.b) + wd * (H.b - H.dn))


def _deviance(O, lam, n):
    """lam - n - n log(lam / n) = n (u - log1p(u)), u = lam / n - 1: one bin's share of a log-likelihood ratio,
    written so that the nearly equal NLLs of a test statistic are never subtracted (q of 0.4 out of NLLs of 5000
    would keep only four digits); the series below |u| = 0.01, where u - log1p(u) itself cancels."""
    u = lam / n - 1.0
    small = u * u * (0.5 - u * (1.0 / 3 - u * (0.25 - u * (0.2 - u * (1.0 / 6 - u * (1.0 / 7 - u * (0.125 - u / 9.0)))))))
    big = u - O.log1p(O.where(O.abs(u) < 0.01, O.zeros_like(u), u))
    return n * O.where(O.abs(u) < 0.01, small, big)


def _constraint(O, H, a):
    """sum of the constraints' -log, up to constants: a_norm^2/2 + a_jes^2/2 + sum ((gamma - 1) / sigma)^2 / 2, sigma
    the bin's relative Monte Carlo uncertainty (a tensor: it moves with the network, and so does its gradient)."""
    gam = O.const(np.asarray(a[2:], dtype=np.float64) - 1.0)
    rel = H.unc / H.b
    return 0.5 * (float(a[0]) ** 2 + float(a[1]) ** 2) + 0.5 * O.sum((gam / rel) * (gam / rel), 0)


BOUNDS_ALPHA, BOUNDS_GAMMA = (-5.0, 5.0), (1e-10, 10.0)   # pyhf's: normsys and histosys, staterror


class Fit(tuple):
    """A profile fit: unpacks as (theta^^, NLL there minus that of the data's own expectation, iterations); also says
    whether it converged (.converged) and how far from stationary it stopped (.residual: the projected gradient of the
    two systematics, in units of their own curvature -- sigmas of the variable, whatever the scales of the problem)."""

    def __new__(cls, a, f, it, converged, residual):
        self = super().__new__(cls, (a, f, it))
        self.converged, self.residual = bool(converged), float(residual)
        return self


def _best_gammas(m, c, n, rel2, polish=3):
    """Each bin's gamma given the systematics, in closed form (Barlow, Beeston 1993; Conway 2011): the stationary point
    of lam - n log lam + (gamma - 1)^2 / (2 rel2), lam = m + c gamma, solves c gamma^2 + B gamma + C = 0 with
    B = m - c + c^2 rel2 and C = c rel2 (m - n) - m. The likelihood being convex in gamma, it is the unique root with
    lam > 0: (-B + sqrt D) / 2c, whatever the sign of c, written as -2C / (B + sqrt D) for B >= 0 (no cancellation).
    A few Newton steps then remove the rounding of D; pyhf's bounds clip the result, which is exact for a convex
    function of one variable."""
    A, B, C = c, m - c + c * c * rel2, c * rel2 * (m - n) - m
    with np.errstate(all="ignore"):
        D = np.sqrt(np.maximum(B * B - 4.0 * A * C, 0.0))
        g = np.where(B >= 0, -2.0 * C / (B + D), (D - B) / (2.0 * A))
        for _ in range(polish):
            lam = m + c * g
            h = c * (1.0 - n / lam) + (g - 1.0) / rel2
            g2 = g - h / (c * c * n / (lam * lam) + 1.0 / rel2)
            g = np.where(np.isfinite(g2) & (m + c * g2 > 0), g2, g)
    return np.clip(np.where(np.isfinite(g), g, 1.0), BOUNDS_GAMMA[0], BOUNDS_GAMMA[1])


def profile(H, n, norm, mu, iters=500, tol=1e-10):
    """theta^^ = argmin of NLL(mu, theta) on data n over theta = (a_norm, a_jes, gamma_1..K) within pyhf's bounds (the
    a in [-5, 5], the gammas in [1e-10, 10]), in float64: a Fit, (theta^^, NLL there minus that of the data's own
    expectation, iterations), which also says whether it converged.

    The scales of these variables differ by many orders (a gamma's curvature is 1 / rel^2, up to 1e12 and more; a
    systematic's is about 1), so that no tolerance on the raw gradient means anything and one Newton system over all
    of them is ill conditioned. The problem is separable, and is solved as such (variable projection; Golub, Pereyra
    1973): given the two systematics every gamma is known in closed form (_best_gammas), and Newton's method runs only
    on F(a) = min_gamma NLL(a, gamma), two variables of the same scale. F's gradient is NLL's at gamma^^(a) (the
    envelope theorem again); its Hessian is the Schur complement of the gammas, summed bin by bin -- O(K) instead of
    O(K^3) -- and written below without the subtraction of nearly equal numbers that it is when a gamma is stiff or
    free. The steps are damped (Levenberg, Marquardt) on a's own curvature, with an active set at the bounds.

    It stops on what can be known: the projected gradient under `tol` sigmas of the variable, or at float64's
    resolution of the likelihood, where no step can be told from rounding.

    A bin of few Monte Carlo events (a large staterror) may lose its whole background at the minimum: its gamma then
    sits at pyhf's lower bound, which pyhf's fit finds too. The envelope theorem still gives the gradient: the bounds
    do not move with the network's weights. H holds numpy arrays.

    F is convex wherever the model describes the data; where it does not (a bin's background driven to nothing, a gamma
    at a bound) F may have several minima, and a coarse grid of its two variables looks for a lower one."""
    s, b, up, dn = (np.asarray(v, dtype=np.float64) for v in (H.s, H.b, H.up, H.dn))
    n = np.asarray(n, dtype=np.float64)
    rel2 = (np.asarray(H.unc, dtype=np.float64) / b) ** 2
    coef = code4_coefficients(norm)
    du, dd = up - b, b - dn
    m = mu * s
    Onp = _numpy_backend()
    lo, hi = np.full(2, BOUNDS_ALPHA[0]), np.full(2, BOUNDS_ALPHA[1])
    eps = np.finfo(np.float64).eps

    def value(a):
        """F(a), and what its derivatives need; F = inf out of the model's domain."""
        k, k1, k2 = kappa(norm, coef, a[0])
        w, w1, w2 = code4p(a[1])
        base = b + w[0] * du + w[1] * dd
        D1, D2 = w1[0] * du + w1[1] * dd, w2[0] * du + w2[1] * dd
        gam = _best_gammas(m, k * base, n, rel2)
        lam = m + k * base * gam
        if not np.all(np.isfinite(lam)) or np.any(lam <= 0):
            return np.inf, None
        g1 = gam - 1.0
        F = float(np.sum(_deviance(Onp, lam, n)) + 0.5 * (a[0] * a[0] + a[1] * a[1]) + 0.5 * np.sum(g1 * g1 / rel2))
        return F, (k, k1, k2, base, D1, D2, gam, lam)

    def derivatives(a, st):
        """F's gradient and Hessian in a, and the gradient's own rounding error."""
        k, k1, k2, base, D1, D2, gam, lam = st
        r, curv = 1.0 - n / lam, n / (lam * lam)
        c = k * base
        ca = (k1 * base, k * D1)                                # d c / d a_i
        d = (ca[0] * gam, ca[1] * gam)                          # d lam / d a_i at fixed gamma
        e = {(0, 0): k2 * gam * base, (1, 1): k * gam * D2, (0, 1): k1 * gam * D1}
        hgg = c * c * curv + 1.0 / rel2
        free = (gam > BOUNDS_GAMMA[0]) & (gam < BOUNDS_GAMMA[1])
        rho = np.where(free, (1.0 / rel2) / hgg, 1.0)           # the part of a bin's curvature its gamma cannot absorb
        grad = np.array([np.sum(d[0] * r) + a[0], np.sum(d[1] * r) + a[1]])
        Hs = np.eye(2)
        for i, j in ((0, 0), (0, 1), (1, 1)):
            cross = ((d[i] * ca[j] + ca[i] * d[j]) * c * curv * r + ca[i] * ca[j] * r * r) / hgg
            Hs[i, j] += float(np.sum(d[i] * d[j] * curv * rho + e[(i, j)] * r - np.where(free, cross, 0.0)))
        Hs[1, 0] = Hs[0, 1]
        noise = 8.0 * eps * np.array([np.sum(np.abs(d[0]) * (1.0 + n / lam)) + abs(a[0]),
                                      np.sum(np.abs(d[1]) * (1.0 + n / lam)) + abs(a[1])])
        return grad, Hs, noise

    def measure(a, g, Hs):
        """The variables not held at a bound, their scales, and the projected gradient in sigmas of each."""
        F = np.flatnonzero(~(((a <= lo) & (g > 0)) | ((a >= hi) & (g < 0))))
        if F.size == 0:
            return F, np.ones(0), 0.0
        scale = np.sqrt(np.maximum(np.abs(np.diag(Hs)[F]), 1.0))   # the constraint alone gives a curvature of 1
        return F, scale, float(np.max(np.abs(g[F]) / scale))

    def descend(a, fa, st):
        """Damped Newton from a: (a, F, state, iterations, converged, residual, F was seen not to be convex)."""
        tau, it, converged, residual, convex, best, stalled = 0.0, 0, False, np.inf, True, fa, 0
        for it in range(1, iters + 1):
            g, Hs, noise = derivatives(a, st)
            F, scale, residual = measure(a, g, Hs)
            if F.size == 0 or residual <= tol or np.all(np.abs(g[F]) <= noise[F]):
                converged = True
                break
            S = Hs[np.ix_(F, F)] / np.outer(scale, scale)
            gs = g[F] / scale
            least = float(np.linalg.eigvalsh(S)[0])
            convex = convex and least >= 0.0
            floor = 1e-10 - least if least < 1e-10 else 0.0     # where F is not convex: the least damping that descends
            accepted = False
            for _ in range(60):
                damp = max(tau, floor)
                step = np.zeros(2)
                step[F] = -np.linalg.solve(S + damp * np.eye(F.size), gs) / scale   # S p = -D^-1 g, with p = D step
                a2 = np.clip(a + step, lo, hi)
                move = a2 - a
                predicted = -float(g @ move + 0.5 * move @ Hs @ move)
                f2, st2 = value(a2)
                # a step's gain below the likelihood's rounding; not one clipped at a bound into no gain (damped, next)
                resolved = 0.0 < predicted <= 16.0 * eps * (1.0 + abs(fa))
                if resolved:
                    if st2 is not None:     # F cannot judge the step any more, the gradient can
                        g2, H2, _ = derivatives(a2, st2)
                        accepted = measure(a2, g2, H2)[2] < residual
                    converged = not accepted
                    break
                if st2 is not None and predicted > 0 and f2 <= fa - 1e-4 * predicted:
                    accepted = True
                    tau = 0.0 if damp < 1e-8 else damp / 10.0
                    break
                tau = max(10.0 * damp, 1e-8)
            if not accepted:
                break
            a, fa, st = a2, f2, st2
            stalled = 0 if fa < best - 1e-13 * (1.0 + abs(best)) else stalled + 1   # on a fold of F (a gamma just at its
            best = min(best, fa)                                                     # bound): said, not ground at for ever
            if stalled >= 25:
                break
        return a, fa, st, it, converged, residual, convex

    a0 = np.zeros(2)
    fa, st = value(a0)
    if st is None:   # the nominal itself is out of the model's domain (no expected events in a bin): nothing to fit
        return Fit(np.concatenate([a0, np.ones(len(b))]), fa, 0, False, np.inf)
    a, fa, st, it, converged, residual, convex = descend(a0, fa, st)
    if not convex or np.any(st[6] <= BOUNDS_GAMMA[0]) or np.any(st[6] >= BOUNDS_GAMMA[1]):
        # F may have other minima, and with two variables one can look: a coarse grid, Newton again from a lower basin
        axis = np.linspace(BOUNDS_ALPHA[0], BOUNDS_ALPHA[1], 11)
        scan = [(value(np.array([x, y])), x, y) for x in axis for y in axis]
        (f0, st0), x, y = min(scan, key=lambda t: t[0][0])
        if st0 is not None and f0 < fa:
            a1, f1, st1, it1, c1, r1, _ = descend(np.array([x, y]), f0, st0)
            it += it1
            if f1 < fa:
                a, fa, st, converged, residual = a1, f1, st1, c1, r1
    return Fit(np.concatenate([a, st[6]]), fa, it, converged, residual)


def q_mu(O, H, norm, a):
    """q_1 on the Asimov background-only data b: 2 (NLL(1, theta^^) - NLL(0, theta_0)), as the bins' deviances and the
    constraints (theta_0, the nominal, costs nothing)."""
    lam = expected(O, H, 1.0, a, norm)
    return 2.0 * (O.sum(_deviance(O, lam, H.b), 0) + _constraint(O, H, a))


def q_0(O, H, norm, a):
    """q_0 on the Asimov signal-plus-background data s + b: 2 (NLL(0, theta^^_0) - NLL(1, theta_0))."""
    lam = expected(O, H, 0.0, a, norm)
    return 2.0 * (O.sum(_deviance(O, lam, H.s + H.b), 0) + _constraint(O, H, a))


def log_cls(O, q):
    """log CLs = log erfc(sqrt(q / 2)), past z = 5 by erfc's asymptotic series: no underflow, no NaN in either
    branch's gradient (each computed on inputs safe for it)."""
    z = O.sqrt(O.maximum(q, O.zeros_like(q) + 1e-300) * 0.5)
    five = O.zeros_like(z) + 5.0
    small = O.log(O.erfc(O.minimum(z, five)))
    zs = O.maximum(z, five)
    big = -zs * zs - O.log(zs) - 0.5 * math.log(math.pi) + O.log(1.0 - 0.5 / (zs * zs) + 0.75 / (zs * zs * zs * zs))
    return O.where(z < 5.0, small, big)


def _softplus(O, x):
    return O.relu(x) + O.log(1.0 + O.exp(-O.abs(x)))


# ---------------------------------------------------------------------------
# The network's scores, the analysis's loss
# ---------------------------------------------------------------------------

def scores(O, spec, P, inputs):
    """P(signal) of a two-class network: the softmax's second class, as the sigmoid of the logits' difference."""
    z = ml.forward(O, spec, P, inputs)
    return O.sigmoid(z[:, 1] - z[:, 0])


class Setup:
    """What the loss needs besides the weights: the expected events, the bins, the systematics."""

    def __init__(self, spec, signal, background, norm, jes, bins, bandwidth):
        self.spec, self.S, self.B = spec, float(signal), float(background)
        self.norm, self.jes = float(norm), float(jes)
        self.inner, self.h = inner_edges(bins), float(bandwidth)
        self.floor = 1e-6 * self.B   # a millionth of the background in every bin: no bin empty, in pyhf's model too


def hists(O, st, P, batch):
    """The soft histograms of a batch, signal and background weighted to the analysis's expected events: s, b (with
    its floor), the histosys templates, the background's Monte Carlo uncertainty sqrt(sum (w K)^2 + floor^2)."""
    ss = scores(O, st.spec, P, batch["sig"])
    sb = scores(O, st.spec, P, batch["bkg"])
    ws = O.const(np.full(batch["n_sig"], st.S / batch["n_sig"]))
    wb = O.const(np.full(batch["n_bkg"], st.B / batch["n_bkg"]))
    s = soft_hist(O, ss, ws, st.inner, st.h)
    b = soft_hist(O, sb, wb, st.inner, st.h) + st.floor
    unc = O.sqrt(soft_hist(O, sb, wb, st.inner, st.h, squares=True) + st.floor * st.floor)
    if st.jes > 0:
        up = soft_hist(O, scores(O, st.spec, P, batch["up"]), wb, st.inner, st.h) + st.floor
        dn = soft_hist(O, scores(O, st.spec, P, batch["dn"]), wb, st.inner, st.h) + st.floor
    else:
        up, dn = b, b
    return Hists(s, b, up, dn, unc)


def fitted(O, st, H, target):
    """The profiled nuisances for the target, from the histograms' values (no gradient)."""
    V = H.values(O)
    if target == "z0":
        return profile(V, V.s + V.b, st.norm, 0.0)
    return profile(V, V.b, st.norm, 1.0)


def loss(O, st, P, batch, target, a=None):
    """The target's loss on a batch and what it found: log CLs (cls), -Z0 (z0), or the balanced binary cross entropy
    (bce, the classifier's). a, the profiled nuisances, is computed here unless given (JAX traces the loss: it
    profiles first, on values)."""
    if target == "bce":
        z_sig = ml.forward(O, st.spec, P, batch["sig"])
        z_bkg = ml.forward(O, st.spec, P, batch["bkg"])
        d_sig, d_bkg = z_sig[:, 1] - z_sig[:, 0], z_bkg[:, 1] - z_bkg[:, 0]
        return O.mean(_softplus(O, -d_sig), 0) + O.mean(_softplus(O, d_bkg), 0), {}
    H = hists(O, st, P, batch)
    converged = True
    if a is None:
        fit = fitted(O, st, H, target)
        a, converged = fit[0], fit.converged
    if target == "z0":
        q = q_0(O, H, st.norm, a)
        return -O.sqrt(O.maximum(q, O.zeros_like(q) + 1e-300)), {"a": a, "converged": converged}
    return log_cls(O, q_mu(O, H, st.norm, a)), {"a": a, "converged": converged}


# ---------------------------------------------------------------------------
# pyhf, the judge
# ---------------------------------------------------------------------------

def pyhf_cls(H, norm):
    """The expected CLs at mu = 1 that pyhf computes for these histograms: the same HistFactory model written as its
    JSON (normsys, histosys, staterror on the background; pyhf's default interpolations), the Asimov background-only
    data, its asymptotic calculator with qtilde (observed = median expected here)."""
    import pyhf
    pyhf.set_backend("numpy")
    b = list(map(float, H.b))
    mods = [{"name": "mcstat", "type": "staterror", "data": list(map(float, H.unc))}]
    if norm > 0:
        mods.append({"name": "bkg_norm", "type": "normsys", "data": {"hi": 1.0 + norm, "lo": 1.0 - norm}})
    if np.any(np.asarray(H.up) != np.asarray(H.b)) or np.any(np.asarray(H.dn) != np.asarray(H.b)):
        mods.append({"name": "jes", "type": "histosys", "data": {"hi_data": list(map(float, H.up)),
                                                                   "lo_data": list(map(float, H.dn))}})
    spec = {"channels": [{"name": "sr", "samples": [
        {"name": "signal", "data": list(map(float, H.s)), "modifiers": [{"name": "mu", "type": "normfactor", "data": None}]},
        {"name": "background", "data": b, "modifiers": mods}]}]}
    model = pyhf.Model(spec, poi_name="mu")
    data = b + model.config.auxdata
    obs, exp = pyhf.infer.hypotest(1.0, data, model, test_stat="qtilde", return_expected=True)
    return float(obs), float(exp)


def cls_numpy(H, norm):
    """The expected CLs of hard histograms by this module's own model: what pyhf_cls is held to."""
    O = _numpy_backend()
    fit = profile(H, H.b, norm, 1.0)
    a = fit[0]
    q = float(q_mu(O, H, norm, a))
    from scipy.special import erfc
    return float(erfc(math.sqrt(max(q, 0.0) / 2.0))), q, a, fit.converged


# ---------------------------------------------------------------------------
# Data
# ---------------------------------------------------------------------------

def _inputs(spec, norm, ds, rows, scale=1.0):
    raw = ml._rows(ds, rows)
    if scale != 1.0:
        raw["x"], raw["hl"] = ml._scaled(ds, raw["x"], raw["hl"], scale)
    return ml.prepare(spec, norm, dtype=np.float64, **raw)


def _to(O, inputs):
    return {k: O.const(v) for k, v in inputs.items()}


def _take(inputs, idx):
    return {k: v[idx] for k, v in inputs.items()}


class _Pool:
    """A split's jets prepared once: signal; background nominal, with its energy scale up, down."""

    def __init__(self, spec, norm, ds, rows, jes):
        y = ds.y[rows]
        self.sig_rows, self.bkg_rows = rows[y == 1], rows[y != 1]
        self.sig = _inputs(spec, norm, ds, self.sig_rows)
        self.bkg = _inputs(spec, norm, ds, self.bkg_rows)
        self.up = _inputs(spec, norm, ds, self.bkg_rows, 1.0 + jes) if jes > 0 else self.bkg
        self.dn = _inputs(spec, norm, ds, self.bkg_rows, 1.0 - jes) if jes > 0 else self.bkg

    def batch(self, O, si, bi):
        return {"sig": _to(O, _take(self.sig, si)), "bkg": _to(O, _take(self.bkg, bi)), "up": _to(O, _take(self.up, bi)),
                "dn": _to(O, _take(self.dn, bi)), "n_sig": len(si), "n_bkg": len(bi)}


def _auc(ss, sb):
    y = np.concatenate([np.ones(len(ss)), np.zeros(len(sb))])
    es, eb, _ = ml.roc(np.concatenate([ss, sb]), y)
    return float(np.trapezoid(es, eb))


def _hard(st, spec, P, sig, bkg, up, dn):
    """The hard histograms of jets' scores, weighted to the analysis's events: Hists of numpy arrays, and the scores."""
    O = _numpy_backend()
    ss, sb = np.asarray(scores(O, spec, P, sig)), np.asarray(scores(O, spec, P, bkg))
    su = np.asarray(scores(O, spec, P, up)) if st.jes > 0 else sb
    sd = np.asarray(scores(O, spec, P, dn)) if st.jes > 0 else sb
    ws, wb = np.full(len(ss), st.S / len(ss)), np.full(len(sb), st.B / len(sb))
    H = Hists(hard_hist(ss, ws, st.inner), hard_hist(sb, wb, st.inner) + st.floor, hard_hist(su, wb, st.inner) + st.floor,
              hard_hist(sd, wb, st.inner) + st.floor, np.sqrt(hard_hist(sb, wb, st.inner, squares=True) + st.floor ** 2))
    return H, ss, sb


def _evaluate(st, weights, pool):
    """A trained network judged on a split: its hard histograms' expected CLs by this module and by pyhf, the soft
    ones' at the bandwidth trained with, its profiled nuisances, its AUC."""
    O = _numpy_backend()
    P = {k: np.asarray(v, dtype=np.float64) for k, v in weights.items()}
    H, ss, sb = _hard(st, st.spec, P, pool.sig, pool.bkg, pool.up, pool.dn)
    cls, q, a, converged = cls_numpy(H, st.norm)
    out = {"profile_converged": converged, "cls": cls, "q": q, "a_norm": float(a[0]), "a_jes": float(a[1]), "gammas": [float(g) for g in a[2:]],
           "s": H.s.tolist(), "b": H.b.tolist(), "b_up": H.up.tolist(), "b_down": H.dn.tolist(),
           "mc_rel": (H.unc / H.b).tolist(), "auc": _auc(ss, sb)}
    try:
        obs, exp = pyhf_cls(H, st.norm)
        out.update({"pyhf_cls": obs, "pyhf_cls_expected": exp, "pyhf_relative": abs(obs - cls) / max(abs(cls), 1e-300)})
    except Exception as e:   # pyhf absent, or its fit failed: said, not hidden
        out["pyhf_error"] = "%s: %s" % (type(e).__name__, e)
    bt = {"sig": pool.sig, "bkg": pool.bkg, "up": pool.up, "dn": pool.dn, "n_sig": len(ss), "n_bkg": len(sb)}
    Hs = hists(O, st, P, bt)
    a2, _, _ = profile(Hs, Hs.b, st.norm, 1.0)
    out["soft_cls"] = float(np.exp(log_cls(O, np.asarray(q_mu(O, Hs, st.norm, a2)))))
    return out


# ---------------------------------------------------------------------------
# Training for the analysis: PyTorch
# ---------------------------------------------------------------------------

def _val_score(st, weights, pool, target):
    """The target's loss on a whole held-out split (numpy, float64): what keeps the best epoch."""
    O = _numpy_backend()
    P = {k: np.asarray(v, dtype=np.float64) for k, v in weights.items()}
    batch = {"sig": pool.sig, "bkg": pool.bkg, "up": pool.up, "dn": pool.dn, "n_sig": len(pool.sig_rows),
             "n_bkg": len(pool.bkg_rows)}
    return float(loss(O, st, P, batch, target)[0])


def _epochs(st, pool, target, epochs, batch, seed, step, weights, report, canceled, val):
    """The loop both engines share: step(si, bi) trains on a batch and returns (loss, the fit converged), or None when
    its gradient was not finite and no step was taken -- a NaN let through would poison every weight for good."""
    rng = np.random.default_rng(seed)
    ns, nb = len(pool.sig_rows), len(pool.bkg_rows)
    half = max(16, batch // 2)
    steps = max(1, min(ns, nb) // half)
    history, best, best_score = [], None, float("inf")
    for epoch in range(1, epochs + 1):
        if canceled is not None and canceled():
            break
        ps, pb = rng.permutation(ns), rng.permutation(nb)
        total, t0, done, refused, unfit = 0.0, time.time(), 0, 0, 0
        for k in range(steps):
            result = step(ps[k * half:(k + 1) * half], pb[k * half:(k + 1) * half])
            if result is None:
                refused += 1
                continue
            total += result[0]
            done += 1
            unfit += not result[1]
        h = {"epoch": epoch, "loss": total / max(done, 1), "seconds": time.time() - t0}
        if refused:
            h["refused_steps"] = refused
        if unfit:
            h["unconverged_fits"] = unfit
        if val is not None:
            h["val_loss"] = _val_score(st, weights(), val, target)
            if h["val_loss"] < best_score:
                best_score, best = h["val_loss"], weights()
                h["best"] = True
        history.append(h)
        if report:
            report(epoch, h)
    return (best if best is not None else weights()), history


def _train_torch(st, params, pool, target, epochs, batch, lr, seed, report=None, canceled=None, val=None, clip=1.0):
    """The network trained by Adam on the target's loss, in float64; the analysis's gradients clipped to a norm of
    clip (a bin about to empty makes a steep likelihood); the epoch of least loss on the held-out split kept."""
    import torch
    torch.manual_seed(seed)
    O = _torch_backend()
    net = ml._torch_net(st.spec, params, precision="float64")
    opt = torch.optim.Adam(net.parameters(), lr=lr)

    def step(si, bi):
        P = {name: net.p[ml._san(name)] for name in net.names}
        value, info = loss(O, st, P, pool.batch(O, si, bi), target)
        opt.zero_grad(set_to_none=True)
        value.backward()
        norm = torch.nn.utils.clip_grad_norm_(net.parameters(), clip if target != "bce" and clip else float("inf"))
        if not (torch.isfinite(value) and torch.isfinite(norm)):
            return None
        opt.step()
        return float(value.detach()), info.get("converged", True)

    return _epochs(st, pool, target, epochs, batch, seed, step, net.weights, report, canceled, val)


def _train_tf(st, params, pool, target, epochs, batch, lr, seed, report=None, canceled=None, val=None, clip=1.0):
    """The same training in TensorFlow, float64: torch.optim.Adam's own update (written here, so that the two engines
    differ by nothing but their framework, and Keras's versions do not enter), the same clipping, the same batches."""
    import tensorflow as tf
    O = _tf_backend()
    P = {k: tf.Variable(np.asarray(v, dtype=np.float64), name="w%d" % i) for i, (k, v) in enumerate(params.items())}
    m1 = {k: np.zeros_like(v.numpy()) for k, v in P.items()}
    m2 = {k: np.zeros_like(v.numpy()) for k, v in P.items()}
    count = [0]

    def step(si, bi):
        with tf.GradientTape() as tape:
            value, info = loss(O, st, P, pool.batch(O, si, bi), target)
        names = list(P)
        found = tape.gradient(value, [P[k] for k in names])
        g = [tf.zeros_like(P[k]) if v is None else tf.convert_to_tensor(v) for k, v in zip(names, found)]
        if target != "bce" and clip:
            g, norm = tf.clip_by_global_norm(g, clip)
        else:
            norm = tf.linalg.global_norm(g)
        if not (np.isfinite(float(value)) and np.isfinite(float(norm))):
            return None
        count[0] += 1
        t = count[0]
        for k, v in zip(names, g):
            v = v.numpy()
            m1[k] = 0.9 * m1[k] + 0.1 * v
            m2[k] = 0.999 * m2[k] + 0.001 * v * v
            P[k].assign_sub(lr * (m1[k] / (1.0 - 0.9 ** t)) / (np.sqrt(m2[k] / (1.0 - 0.999 ** t)) + 1e-8))
        return float(value), info.get("converged", True)

    return _epochs(st, pool, target, epochs, batch, seed, step, lambda: {k: v.numpy().copy() for k, v in P.items()}, report,
                   canceled, val)


ENGINES = {"torch": _train_torch, "tensorflow": _train_tf}


def optimize(data, model="deepsets", target="cls", signal=50.0, background=1000.0, norm=0.2, jes=0.1, bins=6,
             bandwidth=0.08, epochs=20, pretrain=None, batch=512, lr=1e-3, seed=7, baseline=True, points=None,
             report=None, canceled=None, engine="torch", **kw):
    """A network of the zoo trained for the analysis (target cls: the expected CLs at mu = 1; z0: the discovery
    significance) against the same network trained to classify (bce): both start from the same classifier,
    pretrained `pretrain` epochs (half of epochs by default -- the analysis's likelihood is steep where a network
    knows nothing yet), then train `epochs` more, each keeping its epoch of least loss on held-out jets, and each is
    judged on other held-out jets by its hard histograms' expected CLs -- this module's and pyhf's.

    signal and background are the expected events of the analysis (the dataset's jets weighted to them), norm the
    background's normalization uncertainty (a normsys), jes the jet energy scale's (a histosys from the network's
    own scores on the background scaled by 1 +- jes); the Monte Carlo statistics are a staterror. engine is the
    framework that trains: torch or tensorflow."""
    if target not in ("cls", "z0"):
        raise ValueError("target cls or z0, not " + str(target))
    if engine not in ENGINES:
        raise ValueError("no engine %s (%s)" % (engine, ", ".join(ENGINES)))
    train_with = ENGINES[engine]
    t0 = time.time()
    ds = ml.dataset(data)
    if ds.y is None:
        raise ValueError(ds.name + " has no labels")
    spec = ml.make_spec(model, ds, points=points or ds.points, **kw)
    if spec["classes"] != 2:
        raise ValueError("an analysis of signal against background: two classes, not %d" % spec["classes"])
    tr, va, te = ml._split(len(ds), seed, 0.2, 0.3)
    spec["norm"] = ml.fit_norm(spec, ds, tr)
    st = Setup(spec, signal, background, norm, jes, bins, bandwidth)
    train, val, test = (_Pool(spec, spec["norm"], ds, r, jes) for r in (tr, va, te))
    pretrain = max(0, epochs // 2 if pretrain is None else int(pretrain))
    out = {"engine": engine, "model": model, "dataset": ds.name, "target": target, "signal": signal, "background": background,
           "norm": norm, "jes": jes, "bins": bins, "bandwidth": bandwidth, "epochs": epochs, "pretrain": pretrain,
           "train_jets": len(tr), "val_jets": len(va), "test_jets": len(te)}

    def tell(kind):
        def r(epoch, h):
            if report:
                report(kind, epoch, h)
        return r

    start = ml.init_params(spec, seed)
    if pretrain:
        start, out["pretrain_history"] = train_with(st, start, train, "bce", pretrain, batch, lr, seed, tell("pretrain"),
                                                    canceled, val)
    w_ana, hist_ana = train_with(st, start, train, target, epochs, batch, lr, seed + 1, tell(target), canceled, val)
    out["analysis"] = _evaluate(st, w_ana, test)
    out["analysis"]["history"] = hist_ana
    if baseline:
        w_bce, hist_bce = train_with(st, start, train, "bce", epochs, batch, lr, seed + 1, tell("bce"), canceled, val)
        out["bce"] = _evaluate(st, w_bce, test)
        out["bce"]["history"] = hist_bce
        out["gain"] = out["bce"]["cls"] / max(out["analysis"]["cls"], 1e-300)
    out["seconds"] = time.time() - t0
    out["weights"] = w_ana
    out["spec"] = spec
    out["verdict"] = _verdict(out)
    return out


def _verdict(out):
    a = out["analysis"]
    text = "trained for %s: expected CLs(mu=1) %.4g" % (out["target"], a["cls"])
    if "pyhf_cls" in a:
        text += " (pyhf %.4g, relative difference %.1e)" % (a["pyhf_cls"], a["pyhf_relative"])
    if not a.get("profile_converged", True):
        text += " [WARNING: the profile fit of this result did not converge]"
    if "bce" in out:
        b = out["bce"]
        text += "; trained to classify: %.4g -- %s" % (
            b["cls"], "the analysis-trained network excludes better, its CLs %.2f times smaller" % out["gain"]
            if out["gain"] > 1 else "classification did as well here (systematics too small to matter?)")
    return text


# ---------------------------------------------------------------------------
# The frameworks held to each other
# ---------------------------------------------------------------------------

def _batch_to(O, b):
    return {k: (_to(O, v) if isinstance(v, dict) else v) for k, v in b.items()}


def _grad_torch(st, P0, batch_np, target, a):
    import torch
    O = _torch_backend()
    P = {k: torch.tensor(v, dtype=torch.float64, requires_grad=True) for k, v in P0.items()}
    value, _ = loss(O, st, P, _batch_to(O, batch_np), target, a)
    value.backward()
    return float(value.detach()), {k: (P[k].grad.double().numpy() if P[k].grad is not None else np.zeros(np.shape(P0[k])))
                                   for k in P}


def _grad_jax(st, P0, batch_np, target, a):
    import jax
    O = _jax_backend()
    batch = _batch_to(O, batch_np)

    def f(P):
        return loss(O, st, P, batch, target, a)[0]

    value, g = jax.value_and_grad(f)({k: O.const(v) for k, v in P0.items()})
    return float(value), {k: np.asarray(g[k], dtype=np.float64) for k in g}


def _grad_tf(st, P0, batch_np, target, a):
    import tensorflow as tf
    O = _tf_backend()
    P = {k: tf.Variable(np.asarray(v, dtype=np.float64)) for k, v in P0.items()}
    batch = _batch_to(O, batch_np)
    with tf.GradientTape() as tape:
        value, _ = loss(O, st, P, batch, target, a)
    g = tape.gradient(value, P)
    return float(value), {k: (np.asarray(g[k].numpy(), dtype=np.float64) if g[k] is not None else np.zeros(np.shape(P0[k])))
                          for k in P}


def crosscheck(data, model="deepsets", target="cls", backends=("torch", "jax", "tensorflow"), rows=128, signal=50.0, background=1000.0,
               norm=0.2, jes=0.1, bins=6, bandwidth=0.08, seed=3, fd=16, points=None, **kw):
    """The analysis's loss and its gradient with respect to every weight of a network, in each framework, on the same
    jets and from the same weights, in float64: each framework against PyTorch, numpy's loss against all of them, the
    gradient against numpy's central differences at fd weights (the profile fitted again at each); and the hard
    histograms' CLs against pyhf's."""
    ds = ml.dataset(data)
    spec = ml.make_spec(model, ds, points=points or ds.points, **kw)
    rows_all = np.arange(len(ds))
    spec["norm"] = ml.fit_norm(spec, ds, rows_all)
    rng = np.random.default_rng(seed)
    pool = _Pool(spec, spec["norm"], ds, rows_all, jes)
    si = rng.choice(len(pool.sig_rows), min(rows, len(pool.sig_rows)), replace=False)
    bi = rng.choice(len(pool.bkg_rows), min(rows, len(pool.bkg_rows)), replace=False)
    batch_np = {"sig": _take(pool.sig, si), "bkg": _take(pool.bkg, bi), "up": _take(pool.up, bi), "dn": _take(pool.dn, bi),
                "n_sig": len(si), "n_bkg": len(bi)}
    st = Setup(spec, signal, background, norm, jes, bins, bandwidth)
    P0 = ml.init_params(spec, seed)
    Onp = _numpy_backend()
    # the profile, once, on numpy's histograms: every framework then evaluates the likelihood at the same point
    a = None if target == "bce" else fitted(Onp, st, hists(Onp, st, P0, batch_np), target)[0]
    ref_loss = float(loss(Onp, st, P0, batch_np, target, a)[0])
    out = {"model": model, "target": target, "rows": int(len(si) + len(bi)),
           "weights": int(sum(np.size(v) for v in P0.values())), "numpy_loss": ref_loss, "frameworks": {}}
    if a is not None:
        out["profiled"] = {"a_norm": float(a[0]), "a_jes": float(a[1]), "gammas": [round(float(g), 6) for g in a[2:]]}
    grads = {}
    runners = {"torch": _grad_torch, "jax": _grad_jax, "tensorflow": _grad_tf}
    for name in backends:
        t0 = time.time()
        try:
            value, g = runners[name](st, P0, batch_np, target, a)
            grads[name] = g
            out["frameworks"][name] = {"loss": value, "loss_vs_numpy": abs(value - ref_loss) / max(abs(ref_loss), 1e-300),
                                       "seconds": time.time() - t0}
        except Exception as e:
            out["frameworks"][name] = {"error": "%s: %s" % (type(e).__name__, e)}
    ref = grads.get("torch") or next(iter(grads.values()), None)
    if ref is not None:
        scale = max(float(np.max(np.abs(v))) for v in ref.values())
        for name, g in grads.items():
            d = max(float(np.max(np.abs(g[k] - ref[k]))) for k in ref)
            out["frameworks"][name]["grad_vs_torch"] = d / max(scale, 1e-300)
        keys = list(P0)
        found = []
        for _ in range(int(fd)):
            k = keys[rng.integers(len(keys))]
            idx = tuple(int(rng.integers(n)) for n in np.shape(P0[k]))
            h = 1e-8 * (1.0 + abs(float(P0[k][idx])))
            vals = []
            for sgn in (1.0, -1.0):
                P = {kk: np.array(vv) for kk, vv in P0.items()}
                P[k][idx] += sgn * h
                # the profile moves with the weights: fitted again, the envelope theorem's premise
                aa = None if a is None else fitted(Onp, st, hists(Onp, st, P, batch_np), target)[0]
                vals.append(float(loss(Onp, st, P, batch_np, target, aa)[0]))
            num = (vals[0] - vals[1]) / (2 * h)
            rel = abs(num - float(ref[k][idx])) / max(abs(num), abs(float(ref[k][idx])), 1e-3 * scale)
            found.append((rel, "%s%s" % (k, list(idx)), num, float(ref[k][idx])))
        found.sort()
        w = found[-1]
        # a ReLU's kink crossed between w - h and w + h is a difference of the function, not of its gradient: the
        # median says what the gradient is worth, the worst where a kink may lie
        out["finite_differences"] = {"weights": int(fd), "median_relative": found[len(found) // 2][0], "worst_relative": w[0],
                                     "worst_at": w[1], "worst_numeric": w[2], "worst_autograd": w[3]}
    if target != "bce":
        H, _, _ = _hard(st, spec, P0, batch_np["sig"], batch_np["bkg"], batch_np["up"], batch_np["dn"])
        mine, q, _, _ = cls_numpy(H, norm)
        try:
            theirs, _ = pyhf_cls(H, norm)
            out["pyhf"] = {"cls": mine, "pyhf_cls": theirs, "relative": abs(mine - theirs) / max(abs(mine), 1e-300), "q": q}
        except Exception as e:
            out["pyhf"] = {"cls": mine, "error": "%s: %s" % (type(e).__name__, e)}
    return out

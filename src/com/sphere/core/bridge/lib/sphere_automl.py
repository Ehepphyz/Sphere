"""AutoML under constraints: Bayesian optimization of a tagger's architecture and training, the constraints the
physics and the machine impose modelled with it.

The optimizer: a Gaussian process per quantity -- Matern 5/2 with a length per dimension (automatic relevance), a
constant mean, a noise; its hyperparameters by the marginal likelihood (L-BFGS-B, several starts) -- on the unit
cube the search space maps to (integers rounded before the kernel sees them, Garrido-Merchan & Hernandez-Lobato
2020). The next trial maximizes the expected improvement times the probability that every modelled constraint
holds (Gardner et al. 2014, Gelbart et al. 2014), computed in logarithms so that it stays informative far from
the best (LogEI, Ament et al. 2023); until a trial is feasible, the probability of feasibility alone. Constraints
known before any training (the number of weights) are not modelled: candidates that break them are never tried.

Held to references first: the process's posterior and marginal likelihood against scikit-learn's at the same
hyperparameters; the optimizer against random search on the constrained Branin function (Gardner et al.'s
benchmark, optimum 0.397887) and Hartmann's 6-dimensional one (optimum -3.32237).

Then the tagger: a network of Sphere's zoo (Deep Sets, EFN, MLP) on a dataset, its widths, depth, learning rate,
weight decay and DisCo's lambda searched to maximize the validation AUC while the score's distance correlation to
the background's mass stays below a bound (no bump sculpted for a bump hunt to find), the weights below a budget,
the inference below a latency measured on this machine. The best feasible network is trained again under its own
name and set against the zoo's default with the same epochs, decorrelated (lambda 1) and not.
"""
import math
import time

import numpy as np

# ----------------------------------------------------------------------------------------------------------------
# the Gaussian process
# ----------------------------------------------------------------------------------------------------------------


def matern52(A, B, lengths, amp):
    d = (A[:, None, :] - B[None, :, :]) / lengths
    r = np.sqrt(np.maximum((d * d).sum(-1), 0.0))
    s = math.sqrt(5.0) * r
    return amp * (1.0 + s + s * s / 3.0) * np.exp(-s)


class GP:
    """Gaussian process regression of y on X in [0, 1]^d: y standardized, Matern 5/2 with automatic relevance,
    a noise; the hyperparameters (log amplitude, log lengths, log noise) by the marginal likelihood."""

    def __init__(self, X, y, noise_floor=1e-6):
        self.X = np.asarray(X, dtype=float)
        y = np.asarray(y, dtype=float)
        self.mu, self.sd = float(y.mean()), float(y.std() or 1.0)
        self.y = (y - self.mu) / self.sd
        self.noise_floor = noise_floor
        self.params = None

    def unpack(self, p):
        d = self.X.shape[1]
        return math.exp(p[0]), np.exp(p[1:1 + d]), math.exp(p[1 + d]) + self.noise_floor

    def nlml(self, p, grad=False):
        """-log p(y | X, hyperparameters) of the standardized y (a huge value where K is not positive), and its
        gradient in the log hyperparameters: 1/2 tr((K^-1 - a a^T) dK/dp), dK/dlog l_d = 5/3 amp (1 + s) e^-s
        (dx_d / l_d)^2 for Matern 5/2."""
        amp, lengths, noise = self.unpack(p)
        D = (self.X[:, None, :] - self.X[None, :, :]) / lengths
        D2 = D * D
        s = math.sqrt(5.0) * np.sqrt(np.maximum(D2.sum(-1), 0.0))
        e = np.exp(-s)
        K0 = amp * (1.0 + s + s * s / 3.0) * e
        n = len(self.X)
        K = K0 + noise * np.eye(n)
        try:
            L = np.linalg.cholesky(K)
        except np.linalg.LinAlgError:
            return (1e25, np.zeros(len(p))) if grad else 1e25
        a = np.linalg.solve(L.T, np.linalg.solve(L, self.y))
        val = float(0.5 * self.y @ a + np.log(np.diag(L)).sum() + 0.5 * n * math.log(2.0 * math.pi))
        if not grad:
            return val
        Li = np.linalg.solve(L, np.eye(n))
        W = Li.T @ Li - np.outer(a, a)                   # K^-1 - a a^T
        g = np.empty(len(p))
        g[0] = 0.5 * float(np.sum(W * K0))
        common = (5.0 / 3.0) * amp * (1.0 + s) * e
        for d in range(self.X.shape[1]):
            g[1 + d] = 0.5 * float(np.sum(W * (common * D2[:, :, d])))
        g[-1] = 0.5 * float(np.trace(W)) * (noise - self.noise_floor)
        return val, g

    def fit(self, rng, starts=3, init=None):
        """The hyperparameters by L-BFGS-B on the analytic gradient, from init (a previous fit's) and random
        starts."""
        from scipy.optimize import minimize
        d = self.X.shape[1]
        lo = np.concatenate([[math.log(0.05)], np.full(d, math.log(0.02)), [math.log(1e-6)]])
        hi = np.concatenate([[math.log(20.0)], np.full(d, math.log(20.0)), [math.log(1.0)]])
        best = None
        for s in range(starts):
            if s == 0:
                p0 = (np.clip(init, lo, hi) if init is not None and len(init) == len(lo)
                      else np.concatenate([[0.0], np.full(d, math.log(0.3)), [math.log(1e-2)]]))
            else:
                p0 = rng.uniform(lo, hi)
            r = minimize(lambda q: self.nlml(q, True), p0, jac=True, method="L-BFGS-B", bounds=list(zip(lo, hi)))
            if best is None or r.fun < best.fun:
                best = r
        self.set(best.x)
        return self

    def set(self, p):
        self.params = np.asarray(p, dtype=float)
        amp, lengths, noise = self.unpack(self.params)
        K = matern52(self.X, self.X, lengths, amp) + noise * np.eye(len(self.X))
        self.L = np.linalg.cholesky(K)
        self.alpha = np.linalg.solve(self.L.T, np.linalg.solve(self.L, self.y))
        return self

    def predict(self, Xs):
        """The posterior's mean and standard deviation of the latent function at Xs, in y's own units."""
        amp, lengths, _ = self.unpack(self.params)
        Ks = matern52(np.asarray(Xs, dtype=float), self.X, lengths, amp)
        mean = Ks @ self.alpha
        v = np.linalg.solve(self.L, Ks.T)
        var = np.maximum(amp - (v * v).sum(0), 1e-18)
        return self.mu + self.sd * mean, self.sd * np.sqrt(var)


def log_h(z):
    """log(phi(z) + z Phi(z)): EI = sigma h(z), stable for every z -- erfcx where it cancels, the asymptote far
    below (LogEI)."""
    from scipy.special import erfcx
    z = np.asarray(z, dtype=float)
    out = np.empty_like(z)
    far = z < -25.0
    zz = z[~far]
    out[~far] = -0.5 * zz * zz + np.log(np.maximum(1.0 / math.sqrt(2.0 * math.pi)
                                                   + 0.5 * zz * erfcx(-zz / math.sqrt(2.0)), 1e-300))
    zf = z[far]
    out[far] = -0.5 * zf * zf - 0.5 * math.log(2.0 * math.pi) - 2.0 * np.log(-zf)
    return out


def acquisition(X, gp_f, best, gps_c=(), bounds_c=()):
    """log EI(x) (for a minimum) + sum log P(c_k(x) <= bound_k); without a feasible best, the log probability of
    feasibility alone."""
    from scipy.special import log_ndtr
    total = np.zeros(len(X))
    for gp, b in zip(gps_c, bounds_c):
        m, s = gp.predict(X)
        total += log_ndtr((b - m) / s)
    if best is None:
        return total
    m, s = gp_f.predict(X)
    return total + np.log(s) + log_h((best - m) / s)


def _propose(rng, d, gp_f, best, gps_c, bounds_c, X_seen, allowed, snap, candidates=1024):
    """The point of the cube that maximizes the acquisition: Sobol candidates and perturbations of the best points
    seen, the five best polished by L-BFGS-B; every point snapped to the space's integers before the processes see
    it; candidates breaking a known constraint never proposed."""
    from scipy.optimize import minimize
    from scipy.stats import qmc
    C = qmc.Sobol(d, seed=int(rng.integers(1 << 30))).random(candidates)
    if len(X_seen):
        C = np.concatenate([C, np.clip(X_seen[rng.integers(len(X_seen), size=256)]
                                       + 0.06 * rng.standard_normal((256, d)), 0.0, 1.0)])
    C = np.array([snap(c) for c in C])
    ok = np.array([allowed(c) for c in C])
    if not ok.any():
        return C[int(rng.integers(len(C)))]
    C = C[ok]
    a = acquisition(C, gp_f, best, gps_c, bounds_c)
    out, out_a = C[int(np.argmax(a))], float(np.max(a))
    for i in np.argsort(-a)[:5]:
        r = minimize(lambda x: -float(acquisition(snap(x)[None, :], gp_f, best, gps_c, bounds_c)[0]), C[i],
                     method="L-BFGS-B", bounds=[(0.0, 1.0)] * d, options={"maxiter": 60})
        x = snap(r.x)
        if -r.fun > out_a and allowed(x):
            out, out_a = x, -r.fun
    return out


def optimize(f, d, budget, bounds_c=(), n_init=None, allowed=None, snap=None, seed=0, report=None, canceled=None):
    """Minimize f over [0, 1]^d in `budget` evaluations, f(x) -> (value, [constraint values...]) with the
    constraints c_k <= bounds_c[k] (a value None: the trial failed, infeasible). The first n_init points are a
    scrambled Sobol design; then the constrained LogEI's maximum each time. allowed(x) are the constraints known
    without evaluating; snap(x) the point of the integers' grid a point stands for. Returns every trial."""
    from scipy.stats import qmc
    rng = np.random.default_rng(seed)
    report = report or (lambda text: None)
    canceled = canceled or (lambda: False)
    allowed = allowed or (lambda x: True)
    snap = snap or (lambda x: np.asarray(x, dtype=float))
    n_init = n_init or min(budget, max(4, d + 2))
    init = [snap(x) for x in qmc.Sobol(d, seed=seed).random(4 * n_init) if allowed(snap(x))][:n_init]
    trials, hyper = [], {}
    for k in range(budget):
        if canceled():
            raise RuntimeError("canceled")
        good = [t for t in trials if t["value"] is not None]
        if k < len(init):
            x = init[k]
        elif len(good) < 2:
            x = snap(rng.uniform(size=d))
        else:
            X = np.array([t["x"] for t in good])
            gp_f = GP(X, [t["value"] for t in good]).fit(rng, init=hyper.get("f"))
            gps_c = [GP(X, [t["constraints"][j] for t in good]).fit(rng, init=hyper.get(j))
                     for j in range(len(bounds_c))]
            hyper["f"] = gp_f.params
            for j, g in enumerate(gps_c):
                hyper[j] = g.params
            feas = [t["value"] for t in good if t["feasible"]]
            x = _propose(rng, d, gp_f, min(feas) if feas else None, gps_c, bounds_c, X, allowed, snap)
        value, cons = f(np.asarray(x))
        feasible = value is not None and all(c <= b for c, b in zip(cons, bounds_c))
        trials.append({"x": np.asarray(x).tolist(), "value": value, "constraints": list(cons or []),
                       "feasible": bool(feasible)})
        report(trials[-1])
    return trials


def random_search(f, d, budget, bounds_c=(), allowed=None, seed=0):
    rng = np.random.default_rng(seed + 7919)
    allowed = allowed or (lambda x: True)
    trials = []
    while len(trials) < budget:
        x = rng.uniform(size=d)
        if not allowed(x):
            continue
        value, cons = f(x)
        trials.append({"x": x.tolist(), "value": value, "constraints": list(cons or []),
                       "feasible": bool(value is not None and all(c <= b for c, b in zip(cons, bounds_c)))})
    return trials


def best_feasible(trials):
    feas = [t["value"] for t in trials if t["feasible"]]
    return min(feas) if feas else float("inf")


# ----------------------------------------------------------------------------------------------------------------
# the references
# ----------------------------------------------------------------------------------------------------------------

def branin(u):
    """Branin on [-5, 10] x [0, 15] from the unit square, with Gardner et al.'s constraint (x1 - 2.5)^2 + (x2 -
    7.5)^2 <= 50: of the three minima (0.397887) only (pi, 2.275) is feasible."""
    x1, x2 = -5.0 + 15.0 * u[0], 15.0 * u[1]
    v = (x2 - 5.1 / (4 * math.pi ** 2) * x1 ** 2 + 5 / math.pi * x1 - 6) ** 2 + 10 * (1 - 1 / (8 * math.pi)) * math.cos(x1) + 10
    return v, [(x1 - 2.5) ** 2 + (x2 - 7.5) ** 2]


HART_A = np.array([[10, 3, 17, 3.5, 1.7, 8], [0.05, 10, 17, 0.1, 8, 14], [3, 3.5, 1.7, 10, 17, 8],
                   [17, 8, 0.05, 10, 0.1, 14]])
HART_P = 1e-4 * np.array([[1312, 1696, 5569, 124, 8283, 5886], [2329, 4135, 8307, 3736, 1004, 9991],
                          [2348, 1451, 3522, 2883, 3047, 6650], [4047, 8828, 8732, 5743, 1091, 381]])
HART_C = np.array([1.0, 1.2, 3.0, 3.2])


def hartmann6(u):
    return float(-np.sum(HART_C * np.exp(-np.sum(HART_A * (u[None, :] - HART_P) ** 2, 1)))), []


def check(seeds=3, report=None):
    """The process against scikit-learn's at the same hyperparameters; the optimizer against random search on the
    references, the median over seeds of the best feasible value found."""
    report = report or (lambda text: None)
    out = {}
    rng = np.random.default_rng(1)
    X = rng.uniform(size=(25, 3))
    y = np.sin(6 * X[:, 0]) + X[:, 1] ** 2 - X[:, 2] + 0.05 * rng.standard_normal(25)
    gp = GP(X, y).fit(rng)
    Xs = rng.uniform(size=(40, 3))
    m, s = gp.predict(Xs)
    try:
        from sklearn.gaussian_process import GaussianProcessRegressor
        from sklearn.gaussian_process.kernels import ConstantKernel, Matern, WhiteKernel
        amp, lengths, noise = gp.unpack(gp.params)
        k = ConstantKernel(amp, "fixed") * Matern(lengths, "fixed", nu=2.5) + WhiteKernel(noise, "fixed")
        sk = GaussianProcessRegressor(k, alpha=0.0, optimizer=None, normalize_y=False).fit(X, gp.y)
        ms, ss = sk.predict(Xs, return_std=True)
        ss = np.sqrt(np.maximum(ss ** 2 - noise, 1e-18))   # scikit-learn's includes the white noise
        out["gp_vs_sklearn"] = {"mean": float(np.max(np.abs((ms * gp.sd + gp.mu) - m))),
                                "std": float(np.max(np.abs(ss * gp.sd - s))),
                                "log_marginal_likelihood": float(abs(-gp.nlml(gp.params)
                                                                     - sk.log_marginal_likelihood_value_))}
    except ImportError:
        out["gp_vs_sklearn"] = {"error": "scikit-learn not installed"}
    for name, f, d, budget, bounds, opt in (("branin_constrained", branin, 2, 30, [50.0], 0.397887),
                                            ("hartmann6", hartmann6, 6, 40, [], -3.32237)):
        t = time.perf_counter()
        bo = [best_feasible(optimize(f, d, budget, bounds, seed=s)) for s in range(seeds)]
        rs = [best_feasible(random_search(f, d, budget, bounds, seed=s)) for s in range(seeds)]
        out[name] = {"optimum": opt, "budget": budget, "seeds": seeds, "bo_median": float(np.median(bo)),
                     "random_median": float(np.median(rs)), "bo_gap": float(np.median(bo) - opt),
                     "random_gap": float(np.median(rs) - opt), "bo": bo, "random": rs,
                     "seconds": time.perf_counter() - t}
        report("  %s: Bayesian optimization %.5f, random search %.5f (optimum %.5f) in %d evaluations, median of %d"
               % (name, np.median(bo), np.median(rs), opt, budget, seeds))
    return out


# ----------------------------------------------------------------------------------------------------------------
# the tagger
# ----------------------------------------------------------------------------------------------------------------

SPACES = {
    # name: (low, high, log, integer)
    "deepsets": [("phi_width", 16, 128, True, True), ("phi_depth", 1, 3, False, True), ("rho_width", 16, 128, True, True),
                 ("lr", 3e-4, 1e-2, True, False), ("weight_decay", 1e-6, 1e-2, True, False),
                 ("disco", 0.1, 30.0, True, False)],
    "mlp": [("width", 16, 256, True, True), ("depth", 1, 4, False, True), ("lr", 3e-4, 1e-2, True, False),
            ("weight_decay", 1e-6, 1e-2, True, False), ("disco", 0.1, 30.0, True, False)],
}
SPACES["efn"] = SPACES["deepsets"]


def encode(space, h):
    out = []
    for name, lo, hi, log, integer in space:
        v = h[name]
        out.append((math.log(v) - math.log(lo)) / (math.log(hi) - math.log(lo)) if log else (v - lo) / (hi - lo))
    return np.clip(np.array(out), 0.0, 1.0)


def decode(space, x):
    out = {}
    for (name, lo, hi, log, integer), u in zip(space, x):
        v = math.exp(math.log(lo) + u * (math.log(hi) - math.log(lo))) if log else lo + u * (hi - lo)
        out[name] = int(round(v)) if integer else float(v)
    return out


def model_kw(model, h):
    if model in ("deepsets", "efn"):
        phi = [h["phi_width"]] * (h["phi_depth"] - 1) + [2 * h["phi_width"]]
        return {"phi": phi, "rho": [h["rho_width"], max(8, h["rho_width"] // 2)]}
    return {"widths": [h["width"]] * h["depth"]}


def _selected(history, lam, epochs):
    """The epoch fit() keeps: with DisCo the best of the decorrelated half by val_loss + lambda val_dcorr."""
    warm = epochs // 2 if lam else 0
    rows = [h for h in history if "val_loss" in h and h["epoch"] > warm] or [h for h in history if "val_loss" in h]
    return min(rows, key=lambda h: h["val_loss"] + (lam * h.get("val_dcorr", 0.0) if lam else 0.0)) if rows else None


def search(data, model="deepsets", trials=16, epochs=8, max_dcorr=0.15, max_params=None, max_latency_us=None,
           seed=0, name=None, report=None, canceled=None):
    """The tagger's search: trials Bayesian trials of `epochs` epochs each, maximizing the validation AUC under the
    validation distance correlation <= max_dcorr (and the latency of the portable engine's forward, per jet, when
    bounded), never trying a network of more than max_params weights; the best feasible trained again as name."""
    import os
    import tempfile
    import sphere_ml as ml
    report = report or (lambda text: None)
    if model not in SPACES:
        raise ValueError("model %s: the search knows %s" % (model, ", ".join(SPACES)))
    ds = ml.dataset(data) if isinstance(data, str) else data
    if ds.mass is None:
        raise ValueError(ds.name + " has no jet masses: the decorrelation constraint needs them")
    space = SPACES[model]
    tr_rows, _, _ = ml._split(len(ds), 7, 0.2, 0.2)
    sample_rows = tr_rows[:256]

    def spec_of(h):
        return ml.make_spec(model, ds, **model_kw(model, h))

    def weights_of(h):
        return int(sum(v.size for v in ml.init_params(spec_of(h), 1).values()))

    def latency_of(h):
        spec = spec_of(h)
        P = ml.init_params(spec, 1)
        norm = ml.fit_norm(spec, ds, tr_rows)
        inputs = ml.prepare(spec, norm, **ml._rows(ds, sample_rows))
        best = float("inf")
        for _ in range(3):
            t = time.perf_counter()
            ml._forward_np(ml.NumpyOps, spec, P, inputs)
            best = min(best, (time.perf_counter() - t) * 1e6 / len(sample_rows))
        return best

    sizes = {}

    def allowed(x):
        if max_params is None:
            return True
        h = decode(space, x)
        key = tuple(v for (n, _, _, _, integer), v in zip(space, h.values()) if integer)
        if key not in sizes:
            sizes[key] = weights_of(h)
        return sizes[key] <= max_params

    def snap(x):
        return encode(space, decode(space, x))

    bounds = [max_dcorr] + ([max_latency_us] if max_latency_us else [])
    folder = tempfile.mkdtemp(prefix="sphere_automl_")
    saved = ml.models_folder, ml._publish_roc
    log = []

    def train(h, label, keep=False):
        kw = model_kw(model, h)
        r = ml.fit(ds, model, "torch", epochs=epochs, lr=h["lr"], weight_decay=h["weight_decay"], seed=seed + 7,
                   decorrelate="mass:%g" % h["disco"] if h["disco"] > 0 else None, name=label, canceled=canceled, **kw)
        sel = _selected(r["history"], h["disco"], epochs)
        return r, sel

    def f(x):
        h = decode(space, x)
        lat = latency_of(h)
        row = {"config": h, "weights": weights_of(h), "latency_us": lat}
        if max_latency_us and lat > max_latency_us:   # measured before training: not worth an epoch
            row.update({"val_auc": None, "val_dcorr": None, "skipped": "latency"})
            log.append(row)
            report("  trial %d: %s -- %.0f us/jet over the bound, not trained" % (len(log), _short(h), lat))
            return None, None
        t = time.perf_counter()
        try:
            r, sel = train(h, "automl_trial_%d" % len(log))
        except Exception as e:
            row.update({"val_auc": None, "val_dcorr": None, "error": "%s: %s" % (type(e).__name__, e)})
            log.append(row)
            return None, None
        row.update({"val_auc": sel["val_auc"], "val_dcorr": sel.get("val_dcorr", float("nan")), "epoch": sel["epoch"],
                    "seconds": time.perf_counter() - t})
        log.append(row)
        report("  trial %d: %s -- AUC %.4f, dcorr %.3f, %d weights, %.0f us/jet (%.0f s)" % (
            len(log), _short(h), row["val_auc"], row["val_dcorr"], row["weights"], lat, row["seconds"]))
        return -row["val_auc"], [row["val_dcorr"]] + ([lat] if max_latency_us else [])

    t0 = time.perf_counter()
    try:
        ml.models_folder = lambda: folder
        ml._publish_roc = lambda *a, **k: None
        trials_out = optimize(f, len(space), trials, bounds, allowed=allowed, snap=snap, seed=seed, canceled=canceled)
    finally:
        ml.models_folder, ml._publish_roc = saved
        for fn in os.listdir(folder):
            try:
                os.remove(os.path.join(folder, fn))
            except OSError:
                pass
        try:
            os.rmdir(folder)
        except OSError:
            pass
    for row, t in zip(log, trials_out):
        row["feasible"] = t["feasible"]
    feas = [r for r in log if r["feasible"]]
    out = {"dataset": ds.name, "model": model, "trials": log, "epochs": epochs, "max_dcorr": max_dcorr,
           "max_params": max_params, "max_latency_us": max_latency_us, "space": [s[0] for s in space]}
    # the zoo's defaults at the same epochs, with DisCo's lambda 1 and without: what the search is set against
    report("  the zoo's default %s, the same epochs, decorrelated (lambda 1) and not..." % model)
    base = {}
    try:
        ml.models_folder = lambda: folder
        ml._publish_roc = lambda *a, **k: None
        os.makedirs(folder, exist_ok=True)
        for lam in (0.0, 1.0):
            # lambda 1e-9 for "not decorrelated": the same training, the distance correlation measured all the same
            r = ml.fit(ds, model, "torch", epochs=epochs, seed=seed + 7, decorrelate="mass:%g" % (lam or 1e-9),
                       name="automl_default_%g" % lam, canceled=canceled)
            sel = _selected(r["history"], lam or 1e-9, epochs)
            base["lambda_%g" % lam] = {"val_auc": sel["val_auc"], "val_dcorr": sel.get("val_dcorr"),
                                       "test_auc": r["metrics"]["auc"], "parameters": r["parameters"],
                                       "sculpting_jsd": r["metrics"].get("sculpting_jsd")}
    finally:
        ml.models_folder, ml._publish_roc = saved
        import shutil
        shutil.rmtree(folder, ignore_errors=True)
    out["defaults"] = base
    if feas:
        best = max(feas, key=lambda r: r["val_auc"])
        label = name or "%s_%s_automl" % (ds.name, model)
        report("  the best feasible network trained again as %s..." % label)
        r, sel = train(best["config"], label)
        out["best"] = dict(best, name=label, path=r["path"], test_auc=r["metrics"]["auc"],
                           test_rejection_50=r["metrics"].get("rejection_50"),
                           sculpting_jsd=r["metrics"].get("sculpting_jsd"), parameters=r["parameters"])
    out["seconds"] = time.perf_counter() - t0
    out["verdict"] = _verdict(out)
    return out


def _short(h):
    return ", ".join("%s %s" % (k, ("%.2g" % v) if isinstance(v, float) else v) for k, v in h.items())


def _verdict(out):
    if "best" not in out:
        return "no trial met the constraints in %d trials: loosen them or search longer" % len(out["trials"])
    b, d0, d1 = out["best"], out["defaults"].get("lambda_0", {}), out["defaults"].get("lambda_1", {})

    def feasible(d):
        why = []
        if d.get("val_dcorr") is None or d["val_dcorr"] > out["max_dcorr"]:
            why.append("dcorr over the bound")
        if out["max_params"] and d.get("parameters", 0) > out["max_params"]:
            why.append("%d weights over the budget" % d["parameters"])
        return "; ".join(why) or "feasible"

    return ("the best of %d trials (%d feasible): %s -- validation AUC %.4f at distance correlation %.3f (bound %.2f),"
            " test AUC %.4f, %d weights, %.0f us/jet. The zoo's default: AUC %.4f at dcorr %.3f undecorrelated (%s),"
            " %.4f at %.3f with lambda 1 (%s)" % (
                len(out["trials"]), sum(1 for t in out["trials"] if t.get("feasible")), _short(b["config"]),
                b["val_auc"], b["val_dcorr"], out["max_dcorr"], b["test_auc"], b["parameters"], b["latency_us"],
                d0.get("val_auc", float("nan")), d0.get("val_dcorr") or float("nan"),
                feasible(d0),
                d1.get("val_auc", float("nan")), d1.get("val_dcorr") or float("nan"),
                feasible(d1)))


if __name__ == "__main__":
    import json
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "check":
        print(json.dumps(check(report=print), indent=1))
    else:
        print(json.dumps(search(sys.argv[1] if len(sys.argv) > 1 else "toy16", report=print), indent=1, default=str))

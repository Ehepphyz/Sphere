"""Normalizing flows: densities learned exactly normalized, written once in PyTorch, run in every framework.

A conditional neural spline flow (Durkan, Bekasov, Murray, Papamakarios 2019): coupling layers whose transform is a
monotonic rational-quadratic spline per dimension on [-5, 5] (identity outside), its K bins' widths, heights and
knot derivatives given by a network of the untouched dimensions and of the context -- so the density is exact
by the change of variables, log p(x | c) = log N(z) + sum log |dz/dx|, and sampling is the inverse, in closed
form (a quadratic's root per dimension).

Written with nothing a portable graph cannot hold -- the bin found by comparisons made one-hot, the knots by a
product with a triangular matrix, the halves by masks -- so that the log-density and the sampler export as
Sphere graphs and run in TensorFlow, JAX and Java as in PyTorch (sphere_xf holds them to each other).

Checked before being used: log |det J| against the Jacobian's own determinant (torch.func), the inverse against the
forward, the graphs against PyTorch in every framework. Then used where HEP uses them:

- neural likelihood estimation (Papamakarios, Sterratt, Murray 2019) of the W mass of sphere_sbi: p(pT, MET, eta |
  theta) learned from simulations, its distance to the exact density measured (the KL divergence, in nats an
  event), the mass measured with it on the pseudo-experiments, its score calibrated as ALICES's is, against the
  exact likelihood;
- fast detector simulation of the jets of sphere_unfold: p(detector level | particle level) for the six
  observables, sampled for new jets and held to the full simulation's detector, observable by observable and in
  their correlations.
"""
import math
import time

import numpy as np

BOUND = 5.0
MIN_W, MIN_H, MIN_D = 1e-3, 1e-3, 1e-3


def _flow_class():
    import torch

    class SplineCoupling(torch.nn.Module):
        """One coupling: the masked dimensions kept, the others moved by a rational-quadratic spline whose
        parameters a network gives from the kept ones and the context."""

        def __init__(self, dim, context, mask, bins, hidden):
            super().__init__()
            self.dim, self.bins = dim, bins
            self.register_buffer("mask", torch.as_tensor(mask, dtype=torch.float64))
            self.net = torch.nn.Sequential(torch.nn.Linear(dim + context, hidden), torch.nn.SiLU(),
                                           torch.nn.Linear(hidden, hidden), torch.nn.SiLU(),
                                           torch.nn.Linear(hidden, dim * (3 * bins - 1)))
            torch.nn.init.zeros_(self.net[-1].weight)
            torch.nn.init.zeros_(self.net[-1].bias)
            tri = torch.triu(torch.ones(bins, bins, dtype=torch.float64))     # cumulative sums as a product
            self.register_buffer("tri", tri)

        def _knots(self, raw, minimum):
            """softmax widths (or heights) with a floor, their knots from -B to B: (B, D, K) and (B, D, K+1)."""
            import torch
            k = self.bins
            w = minimum + (1.0 - minimum * k) * torch.softmax(raw, -1)
            cum = torch.matmul(w, self.tri)                                   # w_1, w_1+w_2, ..., 1
            # the end knots exactly -B and B: no point of the interval without its bin, whatever the rounding
            knots = torch.cat([torch.zeros_like(cum[..., :1]), cum[..., :-1], torch.ones_like(cum[..., :1])], -1)
            return 2.0 * BOUND * w, 2.0 * BOUND * knots - BOUND

        def _params(self, x, c):
            import torch
            h = self.net(torch.cat([x * self.mask, c], -1))
            p = h.reshape(h.shape[0], self.dim, 3 * self.bins - 1)
            k = self.bins
            widths, xk = self._knots(p[..., :k], MIN_W)
            heights, yk = self._knots(p[..., k:2 * k], MIN_H)
            inner = MIN_D + torch.nn.functional.softplus(p[..., 2 * k:] + math.log(math.e - 1.0))   # 1 at zero
            one = torch.ones_like(inner[..., :1])
            derivs = torch.cat([one, inner, one], -1)                       # the tails' slopes: 1, as the identity
            return widths, xk, heights, yk, derivs

        @staticmethod
        def _pick(onehot, v):
            return (onehot * v).sum(-1)

        def forward(self, x, c):
            """z and log |dz/dx| summed over the moved dimensions."""
            import torch
            widths, xk, heights, yk, d = self._params(x, c)
            xc = torch.clamp(x, -BOUND, BOUND * (1.0 - 1e-6))   # inside a bin even in float32
            u = xc.unsqueeze(-1)
            onehot = ((u >= xk[..., :-1]).to(x.dtype)) * ((u < xk[..., 1:]).to(x.dtype))
            x0, w, y0, h = (self._pick(onehot, xk[..., :-1]), self._pick(onehot, widths),
                            self._pick(onehot, yk[..., :-1]), self._pick(onehot, heights))
            d0, d1 = self._pick(onehot, d[..., :-1]), self._pick(onehot, d[..., 1:])
            s = h / w
            xi = (xc - x0) / w
            a = xi * (1.0 - xi)
            den = s + (d1 + d0 - 2.0 * s) * a
            y = y0 + h * (s * xi * xi + d0 * a) / den
            dy = s * s * (d1 * xi * xi + 2.0 * s * a + d0 * (1.0 - xi) * (1.0 - xi)) / (den * den)
            inside = (x >= -BOUND) & (x < BOUND)
            moved = 1.0 - self.mask
            z = self.mask * x + moved * torch.where(inside, y, x)
            logdet = (moved * torch.where(inside, torch.log(dy), torch.zeros_like(dy))).sum(-1)
            return z, logdet

        def inverse(self, z, c):
            import torch
            widths, xk, heights, yk, d = self._params(z, c)                 # the kept dimensions are z's too
            zc = torch.clamp(z, -BOUND, BOUND * (1.0 - 1e-6))
            u = zc.unsqueeze(-1)
            onehot = ((u >= yk[..., :-1]).to(z.dtype)) * ((u < yk[..., 1:]).to(z.dtype))
            x0, w, y0, h = (self._pick(onehot, xk[..., :-1]), self._pick(onehot, widths),
                            self._pick(onehot, yk[..., :-1]), self._pick(onehot, heights))
            d0, d1 = self._pick(onehot, d[..., :-1]), self._pick(onehot, d[..., 1:])
            s = h / w
            dz = zc - y0
            g = d1 + d0 - 2.0 * s
            qa = h * (s - d0) + dz * g
            qb = h * d0 - dz * g
            qc = -s * dz
            disc = torch.clamp(qb * qb - 4.0 * qa * qc, min=0.0)
            xi = (2.0 * qc) / (-qb - torch.sqrt(disc))
            x = x0 + xi * w
            inside = (z >= -BOUND) & (z < BOUND)
            moved = 1.0 - self.mask
            return self.mask * z + moved * torch.where(inside, x, z)

    class Flow(torch.nn.Module):
        """Standardization, couplings with alternating masks and fixed permutations between them, a standard
        normal base: log_prob(x, c) and sample(z, c)."""

        def __init__(self, dim, context, layers=4, bins=8, hidden=64, x_mean=None, x_std=None, c_mean=None,
                     c_std=None, seed=0):
            super().__init__()
            torch.manual_seed(seed)
            self.dim, self.context = dim, context
            rng = np.random.default_rng(seed)
            self.couplings = torch.nn.ModuleList()
            perms = []
            for l in range(layers):
                mask = np.array([(i + l) % 2 for i in range(dim)], dtype=float)
                if mask.sum() == 0 or mask.sum() == dim:
                    mask[0] = 1.0 - mask[0]
                self.couplings.append(SplineCoupling(dim, context, mask, bins, hidden))
                perms.append(np.eye(dim)[rng.permutation(dim)])
            self.register_buffer("perms", torch.as_tensor(np.stack(perms), dtype=torch.float64))
            for name, v, n in (("x_mean", x_mean, dim), ("x_std", x_std, dim), ("c_mean", c_mean, context),
                               ("c_std", c_std, context)):
                default = np.zeros(n) if "mean" in name else np.ones(n)
                self.register_buffer(name, torch.as_tensor(default if v is None else v, dtype=torch.float64))

        def log_prob(self, x, c):
            import torch
            z = (x - self.x_mean) / self.x_std
            cs = (c - self.c_mean) / self.c_std
            total = -torch.log(self.x_std).sum() * torch.ones_like(z[:, 0])
            for l, cp in enumerate(self.couplings):
                z, ld = cp(z, cs)
                total = total + ld
                z = torch.matmul(z, self.perms[l])
            return total - 0.5 * (z * z).sum(-1) - 0.5 * self.dim * math.log(2.0 * math.pi)

        def sample(self, z, c):
            import torch
            cs = (c - self.c_mean) / self.c_std
            for l in range(len(self.couplings) - 1, -1, -1):
                z = torch.matmul(z, self.perms[l].transpose(0, 1))
                z = self.couplings[l].inverse(z, cs)
            return z * self.x_std + self.x_mean

    class LogProb(torch.nn.Module):
        def __init__(self, flow):
            super().__init__()
            self.flow = flow

        def forward(self, x, c):
            return self.flow.log_prob(x, c)

    class Sampler(torch.nn.Module):
        def __init__(self, flow):
            super().__init__()
            self.flow = flow

        def forward(self, z, c):
            return self.flow.sample(z, c)

    return Flow, LogProb, Sampler


def make_flow(dim, context, x=None, c=None, **kw):
    Flow, _, _ = _flow_class()
    if x is not None:
        kw.update(x_mean=x.mean(0), x_std=x.std(0) + 1e-12)
    if c is not None:
        kw.update(c_mean=c.mean(0), c_std=c.std(0) + 1e-12)
    return Flow(dim, context, **kw).double()


def train(flow, x, c, epochs=10, batch=1024, lr=3e-3, val=0.1, seed=0, report=None, canceled=None, weight_decay=0.0):
    """Maximum likelihood (Adam, one cycle), float32 for speed, the best epoch on the held-out part kept; the flow
    is float64 again after."""
    import torch
    report = report or (lambda text: None)
    canceled = canceled or (lambda: False)
    torch.manual_seed(seed)
    flow.float()
    X, C = torch.as_tensor(x, dtype=torch.float32), torch.as_tensor(c, dtype=torch.float32)
    gen = torch.Generator().manual_seed(seed)
    perm = torch.randperm(len(X), generator=gen)
    nval = int(len(X) * val)
    va, tr = perm[:nval], perm[nval:]
    # weight decay (AdamW, decoupled) pulls the couplings towards the identity: what a flow overfitting a small sample needs
    opt = (torch.optim.AdamW(flow.parameters(), lr=lr, weight_decay=weight_decay) if weight_decay > 0
           else torch.optim.Adam(flow.parameters(), lr=lr))
    steps = epochs * ((len(tr) + batch - 1) // batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=lr, total_steps=steps, pct_start=0.1)
    best, best_loss, hist, t0 = None, float("inf"), [], time.perf_counter()
    for ep in range(epochs):
        flow.train()
        order = tr[torch.randperm(len(tr), generator=gen)]
        tot = 0.0
        for a in range(0, len(order), batch):
            if canceled():
                raise RuntimeError("canceled")
            idx = order[a:a + batch]
            loss = -flow.log_prob(X[idx], C[idx]).mean()
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(flow.parameters(), 5.0)
            opt.step()
            sched.step()
            tot += loss.item() * len(idx)
        flow.eval()
        with torch.no_grad():
            vl = float(-flow.log_prob(X[va], C[va]).mean())
        hist.append({"epoch": ep + 1, "nll": tot / len(tr), "val_nll": vl})
        if vl < best_loss:
            best_loss, best = vl, {k: v.detach().clone() for k, v in flow.state_dict().items()}
        report("  epoch %d: NLL %.5f, held-out %.5f%s" % (ep + 1, tot / len(tr), vl, " (best)" if vl == best_loss else ""))
    if best is None:
        raise RuntimeError("the flow's held-out likelihood was never finite: a smaller learning rate (--lr)")
    flow.load_state_dict(best)
    flow.double().eval()
    return {"history": hist, "seconds": time.perf_counter() - t0, "val_nll": best_loss}


def log_prob(flow, x, c, chunk=100000):
    import torch
    out = np.empty(len(x))
    with torch.no_grad():
        for a in range(0, len(x), chunk):
            out[a:a + chunk] = flow.log_prob(torch.as_tensor(x[a:a + chunk], dtype=torch.float64),
                                             torch.as_tensor(c[a:a + chunk], dtype=torch.float64)).numpy()
    return out


def sample(flow, c, seed=0, chunk=100000):
    import torch
    rng = np.random.default_rng(seed)
    z = rng.standard_normal((len(c), flow.dim))
    out = np.empty_like(z)
    with torch.no_grad():
        for a in range(0, len(z), chunk):
            out[a:a + chunk] = flow.sample(torch.as_tensor(z[a:a + chunk]), torch.as_tensor(c[a:a + chunk],
                                                                                        dtype=torch.float64)).numpy()
    return out


def exactness(flow, x, c, rows=6):
    """log |det J| of the flow against the Jacobian's own (torch.func.jacrev, then slogdet), the inverse against
    the forward: the largest differences, in float64."""
    import torch
    from torch.func import jacrev
    X = torch.as_tensor(x[:rows], dtype=torch.float64)
    Cc = torch.as_tensor(c[:rows], dtype=torch.float64)

    def to_z(xi, ci):
        z = (xi - flow.x_mean) / flow.x_std
        cs = (ci - flow.c_mean) / flow.c_std
        z, cs = z.unsqueeze(0), cs.unsqueeze(0)
        for l, cp in enumerate(flow.couplings):
            z, _ = cp(z, cs)
            z = torch.matmul(z, flow.perms[l])
        return z[0]

    worst_ld = 0.0
    with torch.no_grad():
        lp = flow.log_prob(X, Cc)
    for i in range(rows):
        J = jacrev(to_z)(X[i], Cc[i])
        _, ld = torch.linalg.slogdet(J)
        z = to_z(X[i], Cc[i])
        ref = float(ld.detach()) - 0.5 * float((z * z).sum().detach()) - 0.5 * flow.dim * math.log(2 * math.pi)
        worst_ld = max(worst_ld, abs(ref - float(lp[i])))
    with torch.no_grad():
        zz = torch.as_tensor(np.random.default_rng(3).standard_normal((256, flow.dim)))
        cc = torch.as_tensor(np.repeat(c[:1], 256, 0), dtype=torch.float64)
        xs = flow.sample(zz, cc)
        z_back = (xs - flow.x_mean) / flow.x_std
        cs = (cc - flow.c_mean) / flow.c_std
        for l, cp in enumerate(flow.couplings):
            z_back, _ = cp(z_back, cs)
            z_back = torch.matmul(z_back, flow.perms[l])
        inv = float(torch.max(torch.abs(z_back - zz)))
    return {"logdet_vs_jacobian": worst_ld, "inverse_roundtrip": inv}


def export(flow, name, example_x, example_c, check_frameworks=True, report=None):
    """The log-density and the sampler as portable graphs, and the log-density held in every framework."""
    import torch
    import sphere_graph
    _, LogProb, Sampler = _flow_class()
    out = {}
    xs = torch.as_tensor(example_x[:8], dtype=torch.float64)
    cs = torch.as_tensor(example_c[:8], dtype=torch.float64)
    zs = torch.as_tensor(np.random.default_rng(1).standard_normal((8, flow.dim)))
    for kind, mod, ex in (("logprob", LogProb(flow), (xs, cs)), ("sampler", Sampler(flow), (zs, cs))):
        try:
            g = sphere_graph.export(mod.eval(), ex, "%s_%s" % (name, kind), feeds=None)
            path = g.get("path") if isinstance(g, dict) else str(g)
            with torch.no_grad():
                ref = mod(*ex).numpy()
            # what PyTorch gives on these inputs, in float64: for Java to be held to
            out[kind] = {"path": path, "name": "%s_%s" % (name, kind), "inputs": [e.numpy().tolist() for e in ex],
                         "output": ref.tolist()}
        except Exception as e:
            out[kind] = {"error": "%s: %s" % (type(e).__name__, str(e)[:300])}
    if check_frameworks and "path" in out.get("logprob", {}):
        try:
            import sphere_xf
            r = sphere_xf.check(out["logprob"]["path"], rows=8, exports=False, bench=False, report=report)
            out["frameworks"] = {e: v.get("outputs_eps", v.get("error")) for e, v in r["engines"].items()}
            out["gradients"] = {e: v.get("eps") for e, v in r.get("gradients", {}).items() if isinstance(v, dict)
                                and "eps" in v}
        except Exception as e:
            out["frameworks"] = {"error": "%s: %s" % (type(e).__name__, str(e)[:300])}
    return out


# ----------------------------------------------------------------------------------------------------------------
# neural likelihood estimation of the W mass, against the exact likelihood
# ----------------------------------------------------------------------------------------------------------------

def _wx(ev):
    return np.stack([ev["x"], ev["y"], ev["eta"]], 1)


def nle(samples=100000, epochs=10, layers=4, bins=8, hidden=64, experiments=60, events=2000, calibration=200000,
        seed=21, export_name="nle_wmass", frameworks=True, report=None, canceled=None):
    """A conditional flow p(pT_l, MET, eta | theta) trained on simulations at theta ~ the prior; judged by its KL
    divergence to the exact density, then on pseudo-experiments: its likelihood, raw and with its score calibrated,
    against the exact one and ALICES's."""
    import sphere_sbi as sbi
    report = report or (lambda text: None)
    t0 = time.perf_counter()
    rng = np.random.default_rng(seed)
    acc = sbi.Acceptance()
    th = rng.uniform(sbi.PRIOR[0], sbi.PRIOR[1], samples)
    ev = sbi.simulate(th, samples, rng)
    X, C = _wx(ev), th[:, None]
    flow = make_flow(3, 1, X, C, layers=layers, bins=bins, hidden=hidden, seed=seed)
    report("  the flow: %d couplings of %d bins, %d weights; trained on %d simulated events..." % (
        layers, bins, sum(p.numel() for p in flow.parameters()), samples))
    info = train(flow, X, C, epochs=epochs, seed=seed, report=report, canceled=canceled)
    out = {"model": sbi.MODEL, "flow": {"layers": layers, "bins": bins, "hidden": hidden, "samples": samples,
                                        "weights": int(sum(p.numel() for p in flow.parameters())),
                                        "seconds": info["seconds"], "val_nll": info["val_nll"]}}
    out["exactness"] = exactness(flow, X, C)
    report("  log|det J| against the Jacobian's determinant: %.1e; the inverse: %.1e" % (
        out["exactness"]["logdet_vs_jacobian"], out["exactness"]["inverse_roundtrip"]))
    # its distance to the truth: KL(p_exact || p_flow) = E[log p_exact - log p_flow], held-out events
    test_th = rng.uniform(sbi.PRIOR[0], sbi.PRIOR[1], 40000)
    test = sbi.simulate(test_th, 40000, rng)
    exact = sbi.log_likelihood_own(test, test_th, acc)
    lf = log_prob(flow, _wx(test), test_th[:, None])
    d = exact - lf
    out["kl_nats"] = float(d.mean())
    out["kl_err"] = float(d.std() / math.sqrt(len(d)))
    report("  KL divergence from the exact density: %.4f +- %.4f nats an event" % (out["kl_nats"], out["kl_err"]))
    # the measurement: log p(x | theta) on 9 masses, a quartic in theta per event (as the ratio networks are)
    nodes = np.linspace(sbi.PRIOR[0], sbi.PRIOR[1], 9)
    tn = (nodes - sbi.REF) / sbi.SCALE
    V = np.stack([tn, tn ** 2, tn ** 3, tn ** 4], 1)

    def coeffs(e):
        x = _wx(e)
        cols = [log_prob(flow, x, np.full((len(x), 1), t)) for t in nodes]
        L = np.stack(cols, 1)
        L = L - log_prob(flow, x, np.full((len(x), 1), sbi.REF))[:, None]
        A, *_ = np.linalg.lstsq(V, L.T, rcond=None)
        return A.T, float(np.sqrt(np.mean((V @ A - L.T) ** 2)))

    truths = rng.uniform(sbi.PRIOR[0] + 0.4, sbi.PRIOR[1] - 0.4, experiments)
    pe = sbi.simulate(np.repeat(truths, events), experiments * events, rng)
    grid = np.linspace(sbi.PRIOR[0], sbi.PRIOR[1], 41)
    report("  %d pseudo-experiments of %d events: the flow's likelihood, the exact one..." % (experiments, events))
    A, quartic = coeffs(pe)
    out["quartic_fit_rms"] = quartic
    exact_curves = -2.0 * sbi.log_likelihood(pe, grid, acc).reshape(experiments, events, -1).sum(1)
    fits = {"exact": [sbi.mle(grid, exact_curves[i], truths[i]) for i in range(experiments)],
            "nle_raw": sbi._network_fits(A, grid, truths, events)}
    # the score calibrated: E_theta[d log p^ / d theta] by reference events reweighted with the joint ratio
    report("  its score calibrated on %d simulated events..." % calibration)
    t = (grid - sbi.REF) / sbi.SCALE
    d1, _ = sbi._dbasis(t)
    s1, done = np.zeros(len(grid)), 0
    while done < calibration:
        k = min(50000, calibration - done)
        e = sbi.simulate(sbi.REF, k, rng)
        Ak, _ = coeffs(e)
        dd = Ak @ d1
        sig = ~np.isnan(e["m"])
        part = sbi.take(e, sig)
        w = np.ones((k, len(grid)))
        for j, g in enumerate(grid):
            w[sig, j] = np.exp(sbi.joint_log_ratio(part, g, acc))
        s1 += (w * dd).sum(0)
        done += k
    c = s1 / calibration
    out["score_calibration_worst"] = float(np.max(np.abs(c)))
    fits["nle"] = sbi._network_fits(A, grid, truths, events, c)
    out["inference"] = sbi._expect(fits, truths)
    if export_name:
        report("  the flow as portable graphs (its log-density, its sampler), held in every framework...")
        out["graphs"] = export(flow, export_name, X, C, check_frameworks=frameworks, report=report)
    out["seconds"] = time.perf_counter() - t0
    inf = out["inference"]
    out["verdict"] = ("the flow is within %.4f nats an event of the exact density; its likelihood measures the mass"
                      " %+.1f +- %.1f MeV from the exact likelihood's (%.1f MeV apart an experiment; uncalibrated %+.1f),"
                      " %.0f MeV resolution against the exact %.0f, its intervals covering %.0f %% (the exact's %.0f %%)" % (
                          out["kl_nats"], inf["nle"]["shift_vs_exact_mev"], inf["nle"]["shift_vs_exact_err_mev"],
                          inf["nle"]["rms_vs_exact_mev"], inf["nle_raw"]["shift_vs_exact_mev"], inf["nle"]["rms_mev"],
                          inf["exact"]["rms_mev"], 100 * inf["nle"]["coverage"], 100 * inf["exact"]["coverage"]))
    return out


# ----------------------------------------------------------------------------------------------------------------
# fast detector simulation of the jets
# ----------------------------------------------------------------------------------------------------------------

def fastsim(jets=40000, epochs=10, layers=6, bins=8, hidden=64, seed=31, export_name="fastsim_jets", frameworks=True,
            report=None, canceled=None):
    """A conditional flow p(detector | particle level) of sphere_unfold's six observables, trained on simulated
    pairs, sampled for new jets: each observable and their correlations against the full simulation's."""
    import sphere_unfold as un
    report = report or (lambda text: None)
    t0 = time.perf_counter()
    rng = np.random.default_rng(seed)
    gen, reco = un.sample(jets, "simulation", rng)
    G, R = _jet_features(gen, rng), _jet_features(reco, rng)
    flow = make_flow(6, 6, R, G, layers=layers, bins=bins, hidden=hidden, seed=seed)
    report("  the flow: %d couplings, %d weights; trained on %d jets..." % (layers, sum(p.numel() for p in flow.parameters()), jets))
    info = train(flow, R, G, epochs=epochs, seed=seed, report=report, canceled=canceled)
    out = {"flow": {"layers": layers, "bins": bins, "hidden": hidden, "jets": jets, "seconds": info["seconds"],
                    "weights": int(sum(p.numel() for p in flow.parameters())), "val_nll": info["val_nll"]},
           "exactness": exactness(flow, R, G)}
    # new jets: the full detector against the flow's
    gen2, reco2 = un.sample(jets, "simulation", rng)
    t = time.perf_counter()
    un.sample(jets, "simulation", np.random.default_rng(seed + 5))
    full_s = time.perf_counter() - t
    t = time.perf_counter()
    fake = _jet_unfeatures(sample(flow, _jet_features(gen2, rng), seed=seed + 1))
    flow_s = time.perf_counter() - t
    _, reco3 = un.sample(jets, "simulation", rng)   # a second full simulation: the floor
    rows = {}
    for j, name in enumerate(un.OBSERVABLES):
        e = un.edges_for(np.concatenate([reco2[:, j], fake[:, j]]), j)

        def h(v):
            x = np.bincount(un.bin_index(v, e), minlength=len(e) - 1).astype(float)
            return x / x.sum()

        rows[name] = {"flow": 1e3 * un.triangular(h(fake[:, j]), h(reco2[:, j])),
                      "floor": 1e3 * un.triangular(h(reco3[:, j]), h(reco2[:, j])),
                      "no_detector": 1e3 * un.triangular(h(gen2[:, j]), h(reco2[:, j])), "label": un.LABELS[name]}
    out["discrimination"] = rows
    corr = {}
    for a, b in (("mass", "multiplicity"), ("width", "ptd"), ("multiplicity", "zlead")):
        ia, ib = un.OBSERVABLES.index(a), un.OBSERVABLES.index(b)
        corr["%s-%s" % (a, b)] = {"full": float(np.corrcoef(reco2[:, ia], reco2[:, ib])[0, 1]),
                                  "flow": float(np.corrcoef(fake[:, ia], fake[:, ib])[0, 1])}
    # the response itself: the correlation of each observable's detector level with its particle level
    corr["response"] = {n: {"full": float(np.corrcoef(gen2[:, j], reco2[:, j])[0, 1]),
                            "flow": float(np.corrcoef(gen2[:, j], fake[:, j])[0, 1])} for j, n in enumerate(un.OBSERVABLES)}
    out["correlations"] = corr
    out["timing"] = {"full_us_per_jet": 1e6 * full_s / jets, "flow_us_per_jet": 1e6 * flow_s / jets}
    if export_name:
        report("  the flow as portable graphs, held in every framework...")
        out["graphs"] = export(flow, export_name, R, G, check_frameworks=frameworks, report=report)
    out["seconds"] = time.perf_counter() - t0
    worst = max(rows.items(), key=lambda kv: kv[1]["flow"] - kv[1]["floor"])
    out["verdict"] = ("the flow is the detector to a triangular discrimination (x1000) of %s (two full simulations"
                      " differ by %.2f to %.2f, no detector at all by %.0f to %.0f); the response's correlations, flow/full:"
                      " %s. It costs %.0f us a jet (this toy's detector, itself a parametrization, %.0f; a full Geant4"
                      " simulation, seconds)" % (
                          ", ".join("%s %.2f" % (k, v["flow"]) for k, v in rows.items()),
                          min(v["floor"] for v in rows.values()), max(v["floor"] for v in rows.values()),
                          min(v["no_detector"] for v in rows.values()), max(v["no_detector"] for v in rows.values()),
                          ", ".join("%s %.3f/%.3f" % (k, v["flow"], v["full"]) for k, v in corr["response"].items()),
                          out["timing"]["flow_us_per_jet"], out["timing"]["full_us_per_jet"]))
    return out


def _jet_features(O, rng):
    """The six observables as the flow sees them: logarithms of the mass and the multiplicity (the latter
    dequantized by a uniform), logits of the shares."""
    F = np.empty_like(O)
    F[:, 0] = np.log(np.maximum(O[:, 0], 1e-3))
    F[:, 1] = np.log(O[:, 1] + rng.uniform(size=len(O)))
    F[:, 2] = np.log(np.maximum(O[:, 2], 1e-6))
    F[:, 3] = np.log(np.maximum(O[:, 3], 1e-6))
    for j in (4, 5):
        p = np.clip(O[:, j], 1e-6, 1 - 1e-6)
        F[:, j] = np.log(p / (1 - p))
    return F


def _jet_unfeatures(F):
    O = np.empty_like(F)
    O[:, 0] = np.exp(F[:, 0])
    O[:, 1] = np.floor(np.exp(F[:, 1]))
    O[:, 2] = np.exp(F[:, 2])
    O[:, 3] = np.exp(F[:, 3])
    for j in (4, 5):
        O[:, j] = 1.0 / (1.0 + np.exp(-F[:, j]))
    return O


if __name__ == "__main__":
    import json
    import sys
    what = sys.argv[1] if len(sys.argv) > 1 else "nle"
    r = (nle(samples=60000, epochs=6, experiments=30, calibration=100000, report=print) if what == "nle"
         else fastsim(jets=30000, epochs=6, report=print))
    print(json.dumps({k: v for k, v in r.items() if k != "inference"}, indent=1, default=str))

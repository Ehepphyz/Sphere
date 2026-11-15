"""Unfolding: OmniFold, every observable at once and unbinned, held to iterative Bayesian unfolding and to the truth.

OmniFold (Andreassen, Komiske, Metodiev, Nachman, Thaler 2020) undoes a detector by reweighting a simulation twice
an iteration: step 1, its detector-level events toward the data, by a classifier of the two; step 2, those weights
made functions of the particle level, by a classifier of the particle-level events with the new weights against
the old. After n iterations the simulation's particle-level events, weighted, are the data unfolded -- in every
observable the classifiers see, and every function of them, at once, unbinned.

Its binned limit is D'Agostini's iterative Bayesian unfolding (IBU, 1995): classifiers replaced by the ratio of the
histograms in the observable's bins, OmniFold's steps are IBU's, to the rounding. That is held here first, on each
observable, by the same event weights the networks use; then the networks' OmniFold, on all six observables at
once, against IBU on each and against the truth.

The jets: particles generated one by one (a quark or gluon jet: a multiplicity, momentum fractions, angles that
widen as the particles soften, sometimes a hard splitting into two prongs), the detector applied to each (an
efficiency that falls for soft particles, a pT resolution, angles smeared, noise in the cone), six observables of
the jet's substructure computed from the particles at both levels: the mass, the multiplicity, the width (lambda_1),
the Les Houches angularity (lambda_0.5), pT_D and the leading particle's share. "Simulation" and "data" differ as two
generators do: more gluons in the data, more and softer particles, wider jets, more splittings. The data's own
particle level is the truth the unfolding is judged against, by the triangular discrimination of the OmniFold
paper, beside the floor two samples of the truth leave between them.
"""
import math
import time

import numpy as np

OBSERVABLES = ("mass", "multiplicity", "width", "lha", "ptd", "zlead")
LABELS = {"mass": "jet mass [GeV]", "multiplicity": "constituents", "width": "width (lambda_1)",
          "lha": "LHA (lambda_0.5)", "ptd": "pT_D", "zlead": "leading share"}
GENERATORS = {
    # gluon fraction, multiplicities (q, g), fragmentation exponent, angular spreads (q, g), splitting probability
    "simulation": {"gluons": 0.40, "mult": (11.0, 19.0), "gamma": 1.5, "spread": (0.045, 0.075), "split": 0.25},
    "data": {"gluons": 0.52, "mult": (12.0, 21.5), "gamma": 1.3, "spread": (0.050, 0.085), "split": 0.32},
}
PARTICLES = 48
PT_JET = 400.0
R = 0.8


def generate(n, kind, rng):
    """n jets of the generator: their particles' pT, eta, phi (n, 48) and mask."""
    g = GENERATORS[kind]
    glue = rng.uniform(size=n) < g["gluons"]
    lam = np.where(glue, g["mult"][1], g["mult"][0])
    mult = np.clip(rng.poisson(lam), 2, PARTICLES)
    mask = np.arange(PARTICLES)[None, :] < mult[:, None]
    z = rng.exponential(1.0, (n, PARTICLES)) ** g["gamma"] * mask
    z /= z.sum(1, keepdims=True)
    pt = PT_JET * (1.0 + 0.05 * rng.standard_normal(n))
    spread = np.where(glue, g["spread"][1], g["spread"][0])[:, None] * np.minimum((z * mult[:, None] + 1e-12) ** -0.35, 8.0)
    deta = spread * rng.standard_normal((n, PARTICLES))
    dphi = spread * rng.standard_normal((n, PARTICLES))
    # a hard splitting: the particles shared between two prongs, z_s ~ U(0.1, 0.5), Delta ~ log-uniform(0.08, 0.5)
    split = rng.uniform(size=n) < g["split"]
    zs = rng.uniform(0.1, 0.5, n)
    delta = np.exp(rng.uniform(math.log(0.08), math.log(0.5), n))
    ang = rng.uniform(0.0, 2.0 * math.pi, n)
    second = (rng.uniform(size=(n, PARTICLES)) < zs[:, None]) & split[:, None]
    ax, ay = delta * np.cos(ang), delta * np.sin(ang)
    deta += np.where(second, (1 - zs)[:, None] * ax[:, None], -(zs * split)[:, None] * ax[:, None])
    dphi += np.where(second, (1 - zs)[:, None] * ay[:, None], -(zs * split)[:, None] * ay[:, None])
    return {"pt": pt[:, None] * z, "eta": deta * mask, "phi": dphi * mask, "mask": mask}


def detector(jets, rng):
    """The detector, particle by particle: found with 95 % above 2 GeV and 70 % below, pT smeared by 3 % +
    0.05 % pT, angles by 0.01; noise: Poisson(2) soft particles in the cone."""
    pt, mask = jets["pt"], jets["mask"]
    n = len(pt)
    found = mask & (rng.uniform(size=pt.shape) < np.where(pt > 2.0, 0.95, 0.70))
    ptr = pt * (1.0 + (0.03 + 0.0005 * pt) * rng.standard_normal(pt.shape))
    ptr = np.where(found & (ptr > 0.1), ptr, 0.0)
    found &= ptr > 0
    eta = jets["eta"] + 0.01 * rng.standard_normal(pt.shape)
    phi = jets["phi"] + 0.01 * rng.standard_normal(pt.shape)
    k = 8
    noise = np.arange(k)[None, :] < np.minimum(rng.poisson(2.0, n), k)[:, None]
    r, a = R * np.sqrt(rng.uniform(size=(n, k))), rng.uniform(0, 2 * math.pi, (n, k))
    return {"pt": np.concatenate([ptr, np.where(noise, rng.exponential(1.0, (n, k)) + 0.5, 0.0)], 1),
            "eta": np.concatenate([eta, r * np.cos(a)], 1), "phi": np.concatenate([phi, r * np.sin(a)], 1),
            "mask": np.concatenate([found, noise], 1)}


def observables(jets):
    """The six observables of each jet, (n, 6), from its particles."""
    pt = np.where(jets["mask"], jets["pt"], 0.0)
    eta, phi = jets["eta"], jets["phi"]
    e, px, py, pz = pt * np.cosh(eta), pt * np.cos(phi), pt * np.sin(phi), pt * np.sinh(eta)
    E, PX, PY, PZ = e.sum(1), px.sum(1), py.sum(1), pz.sum(1)
    ptj = np.hypot(PX, PY)
    mass = np.sqrt(np.maximum(E * E - PX * PX - PY * PY - PZ * PZ, 0.0))
    ej = np.arcsinh(PZ / np.maximum(ptj, 1e-9))
    pj = np.arctan2(PY, PX)
    dphi = (phi - pj[:, None] + np.pi) % (2 * np.pi) - np.pi
    dr = np.sqrt((eta - ej[:, None]) ** 2 + dphi ** 2)
    tot = np.maximum(pt.sum(1), 1e-9)
    z = pt / tot[:, None]
    return np.stack([mass, jets["mask"].sum(1).astype(float), (z * dr).sum(1), (z * np.sqrt(dr / R)).sum(1),
                     np.sqrt((z * z).sum(1)), z.max(1)], 1)


def sample(n, kind, rng):
    """n jets of a generator through the detector: their observables at the particle and detector levels."""
    jets = generate(n, kind, rng)
    return observables(jets), observables(detector(jets, rng))


# ----------------------------------------------------------------------------------------------------------------
# binned: IBU, and OmniFold with histograms for classifiers
# ----------------------------------------------------------------------------------------------------------------

def edges_for(values, j, bins=20):
    lo, hi = np.quantile(values, [0.005, 0.995])
    if OBSERVABLES[j] == "multiplicity":
        lo, hi = math.floor(lo) - 0.5, math.ceil(hi) + 0.5
        step = max(1.0, math.ceil((hi - lo) / bins))
        return np.arange(lo, hi + step, step)
    return np.linspace(lo, hi, bins + 1)


def bin_index(x, edges):
    """The bin of each value, the outer bins taking what falls beyond them."""
    return np.clip(np.digitize(x, edges) - 1, 0, len(edges) - 2)


def ibu(gen_bin, reco_bin, data_bin, nbins, iterations, prior=None):
    """D'Agostini's iterations: t_j <- sum_i R_ij t_j / sum_k R_ik t_k d_i, R from the simulation's pairs, d the
    data's normalized histogram; normalized spectra of the particle level after each iteration."""
    resp = np.zeros((nbins, nbins))
    np.add.at(resp, (reco_bin, gen_bin), 1.0)
    resp /= np.maximum(resp.sum(0, keepdims=True), 1e-300)        # P(reco i | gen j)
    d = np.bincount(data_bin, minlength=nbins).astype(float)
    d /= d.sum()
    t = np.bincount(gen_bin, minlength=nbins).astype(float) if prior is None else prior.astype(float)
    t /= t.sum()
    out = []
    for _ in range(iterations):
        fold = resp @ t
        t = t * (resp.T @ np.where(fold > 0, d / np.maximum(fold, 1e-300), 0.0))
        t /= t.sum()
        out.append(t.copy())
    return out


def omnifold_binned(gen_bin, reco_bin, data_bin, nbins, iterations):
    """OmniFold's two steps with event weights, each classifier the exact ratio of the weighted histograms in the
    observable's bins: what the networks do, with the networks' errors removed."""
    nu = np.ones(len(gen_bin))
    d = np.bincount(data_bin, minlength=nbins).astype(float)
    d /= d.sum()
    out = []
    for _ in range(iterations):
        s = np.bincount(reco_bin, weights=nu, minlength=nbins)
        s /= s.sum()
        omega = nu * np.where(s[reco_bin] > 0, d[reco_bin] / np.maximum(s[reco_bin], 1e-300), 0.0)
        pull = np.bincount(gen_bin, weights=omega, minlength=nbins)
        push = np.bincount(gen_bin, weights=nu, minlength=nbins)
        ratio = np.where(push > 0, (pull / pull.sum()) / np.maximum(push / push.sum(), 1e-300), 0.0)
        nu = nu * ratio[gen_bin]
        h = np.bincount(gen_bin, weights=nu, minlength=nbins)
        out.append(h / h.sum())
    return out, nu


def triangular(p, q):
    """The triangular discrimination 1/2 sum (p - q)^2 / (p + q) of two normalized histograms (x 1000 when
    reported, as the OmniFold paper does)."""
    s = p + q
    return 0.5 * float(np.sum(np.where(s > 0, (p - q) ** 2 / np.maximum(s, 1e-300), 0.0)))


# ----------------------------------------------------------------------------------------------------------------
# the networks' OmniFold
# ----------------------------------------------------------------------------------------------------------------

def _standardize(X, ref):
    lo = ref.mean(0)
    sc = ref.std(0) + 1e-12
    return (X - lo) / sc


def classifier_ratio(X0, w0, X1, w1, epochs=10, width=64, depth=3, batch=256, lr=2e-3, seed=1, state=None,
                     canceled=None):
    """A classifier of the weighted samples X1 against X0 (their weights' totals balanced), its odds the ratio of
    their densities p1 / p0: the function returned, of a matrix of events (standardized as the training was), and
    the network's state. state starts it from a previous one (OmniFold's ratios move little from an iteration to
    the next). The best of the epochs on a held-out tenth is kept."""
    import torch
    canceled = canceled or (lambda: False)
    torch.manual_seed(seed)
    ref = np.concatenate([X0, X1])
    mu, sd = ref.mean(0), ref.std(0) + 1e-12
    X = torch.as_tensor((ref - mu) / sd, dtype=torch.float32)
    y = torch.cat([torch.zeros(len(X0)), torch.ones(len(X1))])
    w = np.concatenate([w0 / w0.sum(), w1 / w1.sum()]) * len(ref) / 2.0
    w = torch.as_tensor(w, dtype=torch.float32)
    layers, d = [], X.shape[1]
    for _ in range(depth):
        layers += [torch.nn.Linear(d, width), torch.nn.SiLU()]
        d = width
    layers.append(torch.nn.Linear(d, 1))
    net = torch.nn.Sequential(*layers)
    if state is not None:
        net.load_state_dict(state)
    gen = torch.Generator().manual_seed(seed)
    perm = torch.randperm(len(X), generator=gen)
    nval = len(X) // 10
    val, tr = perm[:nval], perm[nval:]
    opt = torch.optim.Adam(net.parameters(), lr=lr)
    steps = epochs * ((len(tr) + batch - 1) // batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=lr, total_steps=steps, pct_start=0.2)
    bce = torch.nn.functional.binary_cross_entropy_with_logits
    best, best_loss = None, float("inf")
    for _ in range(epochs):
        order = tr[torch.randperm(len(tr), generator=gen)]
        for a in range(0, len(order), batch):
            if canceled():
                raise RuntimeError("canceled")
            idx = order[a:a + batch]
            loss = (bce(net(X[idx]).squeeze(-1), y[idx], reduction="none") * w[idx]).sum() / w[idx].sum()
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            sched.step()
        with torch.no_grad():
            vl = float((bce(net(X[val]).squeeze(-1), y[val], reduction="none") * w[val]).sum() / w[val].sum())
        if vl < best_loss:
            best_loss, best = vl, {k: v.clone() for k, v in net.state_dict().items()}
    net.load_state_dict(best)
    net.eval()

    def ratio(Z):
        with torch.no_grad():
            f = net(torch.as_tensor((Z - mu) / sd, dtype=torch.float32)).squeeze(-1).double().numpy()
        return np.exp(np.clip(f, -6.0, 6.0))

    return ratio, best


def _features(O):
    """What the networks see of the six observables: the multiplicity's and the mass's logarithms."""
    F = O.copy()
    F[:, 0] = np.log(np.maximum(O[:, 0], 1e-3))
    F[:, 1] = np.log(O[:, 1] + 1.0)
    return F


def omnifold(sim_gen, sim_reco, data_reco, iterations=4, epochs=10, later=4, seed=3, report=None, canceled=None):
    """The networks' OmniFold over all the observables at once: the push weights nu of the simulation's
    particle-level events after each iteration. The first iteration's classifiers train `epochs` epochs, the
    next ones `later`, each from its predecessor."""
    report = report or (lambda text: None)
    G, Rs, Rd = _features(sim_gen), _features(sim_reco), _features(data_reco)
    nu = np.ones(len(G))
    out, s1, s2 = [], None, None
    for it in range(iterations):
        t = time.perf_counter()
        e = epochs if it == 0 else later
        r1, s1 = classifier_ratio(Rs, nu, Rd, np.ones(len(Rd)), epochs=e, seed=seed + 2 * it, state=s1,
                                  canceled=canceled)
        omega = nu * r1(Rs)
        r2, s2 = classifier_ratio(G, nu, G, omega, epochs=e, seed=seed + 2 * it + 1, state=s2, canceled=canceled)
        nu = nu * r2(G)
        nu *= len(nu) / nu.sum()
        out.append(nu.copy())
        report("  iteration %d: step 1 (detector level) and step 2 (particle level) in %.1f s; weights %.3f to %.3f"
               % (it + 1, time.perf_counter() - t, float(np.quantile(nu, 0.001)), float(np.quantile(nu, 0.999))))
    return out


def run(jets=40000, iterations=5, epochs=8, later=4, bins=20, closure=True, seed=7, report=None, canceled=None):
    """The whole study: the jets of both generators through the detector; binned OmniFold against IBU on each
    observable; the networks' OmniFold on all six against IBU and the truth, its closure on the simulation itself,
    the correlations and a derived observable it unfolds without being asked."""
    report = report or (lambda text: None)
    t0 = time.perf_counter()
    rng = np.random.default_rng(seed)
    report("  %d jets simulated, %d of the data, through the detector, particle by particle..." % (jets, jets))
    sim_gen, sim_reco = sample(jets, "simulation", rng)
    truth, data_reco = sample(jets, "data", rng)
    truth2, _ = sample(jets, "data", rng)
    edges = [edges_for(np.concatenate([sim_gen[:, j], truth[:, j]]), j, bins) for j in range(len(OBSERVABLES))]

    def hist(O, j, w=None):
        h = np.bincount(bin_index(O[:, j], edges[j]), weights=w, minlength=len(edges[j]) - 1)
        return h / h.sum()

    # binned: IBU against OmniFold with histograms for classifiers, observable by observable
    out = {"jets": jets, "iterations": iterations, "observables": list(OBSERVABLES), "binned": {}, "unfolded": {}}
    for j, name in enumerate(OBSERVABLES):
        nb = len(edges[j]) - 1
        gb, rb, db = (bin_index(sim_gen[:, j], edges[j]), bin_index(sim_reco[:, j], edges[j]),
                      bin_index(data_reco[:, j], edges[j]))
        a = ibu(gb, rb, db, nb, iterations)
        b, _ = omnifold_binned(gb, rb, db, nb, iterations)
        out["binned"][name] = {"bins": nb, "omnifold_vs_ibu": float(max(np.max(np.abs(x - y) / np.maximum(y, 1e-300))
                                                                         for x, y in zip(a, b)))}
        out["unfolded"][name] = {"ibu": a[-1]}
    # the networks: every observable at once
    report("  OmniFold, %d iterations of two classifiers on the six observables..." % iterations)
    weights = omnifold(sim_gen, sim_reco, data_reco, iterations, epochs, later, seed + 1, report, canceled)
    nu = weights[-1]
    if closure:
        report("  closure: a second simulation's detector level unfolded with the first...")
        g2, r2 = sample(jets, "simulation", rng)
        closure_w = omnifold(sim_gen, sim_reco, r2, 1, epochs, later, seed + 50, None, canceled)[-1]
    else:
        closure_w, g2 = None, None
    rows = {}
    for j, name in enumerate(OBSERVABLES):
        t = hist(truth, j)
        nb = len(edges[j]) - 1
        resp = np.zeros((nb, nb))
        np.add.at(resp, (bin_index(sim_reco[:, j], edges[j]), bin_index(sim_gen[:, j], edges[j])), 1.0)
        resp /= np.maximum(resp.sum(0, keepdims=True), 1e-300)
        rows[name] = {
            "refold_ibu": 1e3 * triangular(resp @ out["unfolded"][name]["ibu"], hist(data_reco, j)),
            "refold_omnifold": 1e3 * triangular(hist(sim_reco, j, nu), hist(data_reco, j)),
            "prior": 1e3 * triangular(hist(sim_gen, j), t),
            "ibu": 1e3 * triangular(out["unfolded"][name]["ibu"], t),
            "omnifold": 1e3 * triangular(hist(sim_gen, j, nu), t),
            "floor": 1e3 * triangular(hist(truth2, j), t),
            "closure": 1e3 * triangular(hist(sim_gen, j, closure_w), hist(g2, j)) if closure else float("nan"),
            "detector": 1e3 * triangular(hist(data_reco, j), t),
            "label": LABELS[name]}
        out["unfolded"][name] = {"edges": edges[j].tolist(), "truth": t.tolist(), "prior": hist(sim_gen, j).tolist(),
                                 "ibu": out["unfolded"][name]["ibu"].tolist(), "omnifold": hist(sim_gen, j, nu).tolist()}
    out["discrimination"] = rows

    # what IBU cannot give without unfolding again: functions of several observables
    def wcorr(a, b, w):
        w = w / w.sum()
        ma, mb = (w * a).sum(), (w * b).sum()
        return float((w * (a - ma) * (b - mb)).sum() / math.sqrt((w * (a - ma) ** 2).sum() * (w * (b - mb) ** 2).sum()))

    one = np.ones(len(sim_gen))
    pairs = [("mass", "multiplicity"), ("width", "ptd"), ("multiplicity", "zlead")]
    out["correlations"] = {"%s-%s" % p: {
        "truth": wcorr(truth[:, OBSERVABLES.index(p[0])], truth[:, OBSERVABLES.index(p[1])], np.ones(len(truth))),
        "prior": wcorr(sim_gen[:, OBSERVABLES.index(p[0])], sim_gen[:, OBSERVABLES.index(p[1])], one),
        "omnifold": wcorr(sim_gen[:, OBSERVABLES.index(p[0])], sim_gen[:, OBSERVABLES.index(p[1])], nu)} for p in pairs}
    ratio = lambda O: O[:, 2] / np.maximum(O[:, 3], 1e-9)   # width / LHA: an observable no classifier was given
    re = np.linspace(*np.quantile(ratio(truth), [0.005, 0.995]), bins + 1)
    h = lambda v, w=None: (lambda x: x / x.sum())(np.bincount(bin_index(v, re), weights=w, minlength=bins))
    out["derived"] = {"name": "width / LHA", "prior": 1e3 * triangular(h(ratio(sim_gen)), h(ratio(truth))),
                      "omnifold": 1e3 * triangular(h(ratio(sim_gen), nu), h(ratio(truth))),
                      "floor": 1e3 * triangular(h(ratio(truth2)), h(ratio(truth)))}
    out["seconds"] = time.perf_counter() - t0
    worst = max(v["omnifold_vs_ibu"] for v in out["binned"].values())
    better = sum(1 for r in rows.values() if r["omnifold"] < r["prior"])
    beats = [k for k, r in rows.items() if r["omnifold"] <= r["ibu"]]
    out["verdict"] = ("binned OmniFold is IBU to %.1e on every observable. The networks' OmniFold, the six at once and"
                      " unbinned, brings %d of %d nearer the truth than the simulation was (x1000, simulation -> OmniFold"
                      " [IBU, each alone]: %s), as good as IBU or better on %s; its closure %s; and what IBU cannot give"
                      " without unfolding again -- the correlations %s, %s %.2f -> %.2f" % (
                          worst, better, len(rows),
                          ", ".join("%s %.1f -> %.2f [%.2f]" % (k, r["prior"], r["omnifold"], r["ibu"])
                                    for k, r in rows.items()),
                          ", ".join(beats) if beats else "none",
                          "%.2f to %.2f (the floor %.2f to %.2f)" % (
                              min(r["closure"] for r in rows.values()), max(r["closure"] for r in rows.values()),
                              min(r["floor"] for r in rows.values()), max(r["floor"] for r in rows.values()))
                          if closure else "not run",
                          ", ".join("%s %.3f (truth %.3f, simulation %.3f)" % (k, c["omnifold"], c["truth"], c["prior"])
                                    for k, c in out["correlations"].items()),
                          out["derived"]["name"], out["derived"]["prior"], out["derived"]["omnifold"]))
    return out


if __name__ == "__main__":
    import json
    import sys
    r = run(jets=int(sys.argv[1]) if len(sys.argv) > 1 else 40000, report=print)
    r.pop("unfolded")
    print(json.dumps(r, indent=1))

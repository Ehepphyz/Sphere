"""Simulation-based inference: likelihood ratios learned from a simulator, held to the exact likelihood.

A mass measured the way the LHC measures the W's -- from the lepton's transverse momentum, the missing transverse
momentum, their transverse mass (ATLAS 2017 and 2024, LHCb 2022, CMS 2024) -- in a toy whose likelihood is known in
closed form, so that what a network learns is judged against the truth itself, not against another approximation:

  a W at rest, of mass m ~ the relativistic Breit-Wigner of mass theta and width 2.085 GeV; the lepton at
  cos(theta*) ~ (3/8)(1 + c^2), its pT = m / (2 cosh eta) measured with sigma_l(eta) = 0.6 GeV (1 + eta^2 / 2), the
  neutrino's -- the same pT -- measured as the missing pT with 3 GeV, eta exactly; selected 25 < pT_l < 60 GeV,
  |eta| < 2.4; a background of 10 % (pT and missing pT falling, eta flat) that knows nothing of theta.

The likelihood: over m, the Breit-Wigner times two Gaussians of the same pT -- one Gaussian in m -- so the integral
is the Faddeeva function's at the Breit-Wigner's two poles +-sqrt(theta^2 + i theta Gamma) (the relativistic Voigt
profile), exact to the double's precision; the selection's probability A(theta) by Gauss-Legendre in eta and
Gauss-Hermite in m (the Breit-Wigner's survival function against the threshold's Gaussian, by parts).

What is learned, of the event x and theta: log r(x | theta, theta_ref) = log p(x | theta) / p(x | theta_ref), by a
network that vanishes at theta_ref by construction -- a quartic in theta whose coefficients are functions of the
event, the exact log r being one to 2e-5 over the prior --

- CARL: a classifier of events at theta against events at theta_ref, its output the ratio by the likelihood-ratio
  trick (Cranmer, Pavez, Louppe 2015);
- ALICES: the same cross-entropy fed what the simulator knows of each event -- the joint likelihood ratio of the
  event and its latent mass, BW(m | theta) A(theta_ref) / BW(m | theta_ref) A(theta): the "gold" mined from the
  simulator (Stoye, Brehmer, Louppe, Pavez, Cranmer 2018; Brehmer, Louppe, Pavez, Cranmer 2020). Here one sample
  at theta_ref serves both classes, reweighted by the joint ratio at many masses a step; the same minimum, the
  ratio itself, with far less variance. alpha > 0 regresses the joint score as well (SCANDAL's term).

Calibrated: a score has mean zero under its own theta; what a network's does not, E_theta[d/dtheta log r^], is its
error, measured on the simulator (reference events reweighted by the joint ratio, exact in expectation) and
subtracted. The learned score is then an unbiased estimating equation: the mass it gives is consistent whatever the
network's errors -- only its precision is theirs -- and its interval is the sandwich (Huber 1967, White 1982),
sqrt(sum psi^2) / |sum dpsi/dtheta| over the events, honest where the network is not the likelihood and the
information identity fails. (CARL's own calibration, the ratio renormalized to integrate to one, is reported beside:
it corrects the normalization, not what biases the estimate.)

Judged: their log r against the exact on held-out events; then measured -- pseudo-experiments of n events at masses
drawn in the prior, the mass and its interval -- against the exact likelihood's on the same experiments (the shift,
free of their fluctuations), the Cramer-Rao bound, and the template fits the LHC uses, in pT_l and in mT (templates
made by reweighting one sample with the joint ratio): bias, resolution, the intervals' coverage.
"""
import math
import time

import numpy as np

GAMMA = 2.085          # GeV, the W's width
PRIOR = (79.4, 81.4)   # GeV, the masses the networks learn
REF = 80.4             # GeV, the reference of the ratio
SCALE = 1.0            # GeV, theta's unit inside the networks
DEGREE = 4             # log r's degree in theta
PT_LO, PT_HI = 25.0, 60.0
ETA_MAX = 2.4
SIG_L0 = 0.6           # GeV, the lepton's pT resolution at eta = 0
SIG_NU = 3.0           # GeV, the missing pT's
F_BKG = 0.10
BKG_PT = 10.0          # GeV, the background's pT falls as exp(-(pT - 25) / 10)
BKG_MET = 15.0         # GeV, its missing pT as exp(-MET / 15)
SQ2 = math.sqrt(2.0)

MODEL = ("W at rest, m ~ relativistic Breit-Wigner(theta, %.3f GeV); lepton cos(theta*) ~ 1 + c^2, pT = m / (2 cosh"
         " eta) smeared by %.1f GeV (1 + eta^2/2), missing pT by %.0f GeV; %g < pT < %g GeV, |eta| < %.1f; %d %% background"
         % (GAMMA, SIG_L0, SIG_NU, PT_LO, PT_HI, ETA_MAX, round(100 * F_BKG)))


def sig_l(eta):
    return SIG_L0 * (1.0 + 0.5 * eta * eta)


def log_f_eta(eta):
    """The lepton's eta at rest: c = tanh(eta) ~ (3/8)(1 + c^2), dc / deta = 1 / cosh^2 eta."""
    t = np.tanh(eta)
    return np.log(0.375 * (1.0 + t * t)) - 2.0 * np.log(np.cosh(eta))


def _cos_star(rng, n):
    """c ~ (3/8)(1 + c^2) by its inverse CDF: c^3 + 3c + 4 - 8u = 0, Cardano's root."""
    q = 4.0 * rng.uniform(size=n) - 2.0
    r = np.sqrt(q * q + 1.0)
    return np.cbrt(q + r) + np.cbrt(q - r)


def _bw_mass(theta, rng):
    """m ~ 2m / ((m^2 - theta^2)^2 + theta^2 Gamma^2) on m > 0: a Cauchy in m^2, by the tangent."""
    lo = -np.arctan(theta / GAMMA)
    phi = lo + (0.5 * np.pi - lo) * rng.uniform(size=np.shape(theta))
    return np.sqrt(np.maximum(theta * theta + theta * GAMMA * np.tan(phi), 0.0))


def simulate(theta, n, rng):
    """n selected events at the mass theta (a number, or one per event): pT_l x, missing pT y, eta, and the latent
    mass m (NaN for the background)."""
    theta = np.array(np.broadcast_to(np.asarray(theta, dtype=float), (n,)))
    x, y, eta, m = np.empty(n), np.empty(n), np.empty(n), np.full(n, np.nan)
    bkg = rng.uniform(size=n) < F_BKG
    nb = int(bkg.sum())
    span = PT_HI - PT_LO
    x[bkg] = PT_LO - BKG_PT * np.log1p(-rng.uniform(size=nb) * (1.0 - math.exp(-span / BKG_PT)))
    y[bkg] = rng.exponential(BKG_MET, nb)
    eta[bkg] = rng.uniform(-ETA_MAX, ETA_MAX, nb)
    need = np.flatnonzero(~bkg)
    with np.errstate(over="ignore", invalid="ignore"):
        while need.size:
            mm = _bw_mass(theta[need], rng)
            e = np.arctanh(_cos_star(rng, need.size))
            u = mm / (2.0 * np.cosh(e))
            xl = u + sig_l(e) * rng.standard_normal(need.size)
            yn = u + SIG_NU * rng.standard_normal(need.size)
            ok = (xl > PT_LO) & (xl < PT_HI) & (np.abs(e) < ETA_MAX)
            idx = need[ok]
            x[idx], y[idx], eta[idx], m[idx] = xl[ok], yn[ok], e[ok], mm[ok]
            need = need[~ok]
    return {"x": x, "y": y, "eta": eta, "m": m, "theta": theta}


def take(ev, idx):
    return {k: v[idx] for k, v in ev.items()}


def concat(*evs):
    return {k: np.concatenate([e[k] for e in evs]) for k in evs[0]}


# ----------------------------------------------------------------------------------------------------------------
# the exact likelihood
# ----------------------------------------------------------------------------------------------------------------

def _norm(theta):
    """The Breit-Wigner's normalization over m > 0, times theta Gamma: pi/2 + atan(theta / Gamma)."""
    return 0.5 * np.pi + np.arctan(theta / GAMMA)


def voigt(mu, s, theta):
    """The integral over m of the Breit-Wigner(theta) times the Gaussian N(m; mu, s), (n, g): with 2m / (m^2 - z^2) =
    1/(m - z) + 1/(m + z), z^2 = theta^2 + i theta Gamma, each a Faddeeva function --
    sqrt(pi/2) / s [Re w((z - mu) / (sqrt2 s)) - Re w((-conj z - mu) / (sqrt2 s))] / (pi/2 + atan(theta / Gamma)).
    Over the whole line: the Gaussian's weight at m < 0 is below 1e-100 for every event the selection keeps."""
    from scipy.special import wofz
    th = np.atleast_1d(np.asarray(theta, dtype=float))
    z = np.sqrt(th * th + 1j * th * GAMMA)
    d = SQ2 * s[:, None]
    w1 = wofz((z[None, :] - mu[:, None]) / d).real
    w2 = wofz((-np.conj(z)[None, :] - mu[:, None]) / d).real
    return math.sqrt(0.5 * math.pi) / s[:, None] * (w1 - w2) / _norm(th)[None, :]


def _signal_parts(ev):
    """What of the signal's density does not depend on theta, and the Gaussian in m it leaves: the two pT
    measurements of the same u = m / (2c) combine as N(x - y; 0, s_t) N(u; ubar, sbar)."""
    x, y, eta = ev["x"], ev["y"], ev["eta"]
    c, sl = np.cosh(eta), sig_l(eta)
    st2 = sl * sl + SIG_NU * SIG_NU
    ubar = (x * SIG_NU * SIG_NU + y * sl * sl) / st2
    sbar = sl * SIG_NU / np.sqrt(st2)
    pre = log_f_eta(eta) - 0.5 * (x - y) ** 2 / st2 - 0.5 * np.log(2.0 * np.pi * st2) + np.log(2.0 * c)
    return pre, 2.0 * c * ubar, 2.0 * c * sbar


def log_background(ev):
    span = PT_HI - PT_LO
    lx = -(ev["x"] - PT_LO) / BKG_PT - math.log(BKG_PT * (1.0 - math.exp(-span / BKG_PT)))
    with np.errstate(divide="ignore"):
        ly = np.where(ev["y"] >= 0, -ev["y"] / BKG_MET - math.log(BKG_MET), -np.inf)
    return lx + ly - math.log(2.0 * ETA_MAX)


def log_likelihood(ev, theta, acc, chunk=20000):
    """log p(x | theta) of each event, (n, g): (1 - f) p_W(x | theta) / A(theta) + f p_B(x)."""
    th = np.atleast_1d(np.asarray(theta, dtype=float))
    n = len(ev["x"])
    out = np.empty((n, len(th)))
    la = acc.log(th)
    lb = log_background(ev)
    for a in range(0, n, chunk):
        part = take(ev, slice(a, a + chunk))
        pre, mu, s = _signal_parts(part)
        with np.errstate(divide="ignore"):
            ls = pre[:, None] + np.log(voigt(mu, s, th)) - la[None, :]
        out[a:a + chunk] = np.logaddexp(math.log1p(-F_BKG) + ls, math.log(F_BKG) + lb[a:a + chunk, None])
    return out


def log_likelihood_own(ev, theta, acc):
    """log p(x_i | theta_i): each event at its own mass, (n,)."""
    from scipy.special import wofz
    th = np.broadcast_to(np.asarray(theta, dtype=float), ev["x"].shape)
    pre, mu, s = _signal_parts(ev)
    z = np.sqrt(th * th + 1j * th * GAMMA)
    d = SQ2 * s
    w = (wofz((z - mu) / d).real - wofz((-np.conj(z) - mu) / d).real)
    with np.errstate(divide="ignore"):
        ls = pre + np.log(math.sqrt(0.5 * math.pi) / s * w / _norm(th)) - acc.log(th)
    return np.logaddexp(math.log1p(-F_BKG) + ls, math.log(F_BKG) + log_background(ev))


def _survival(m, theta):
    """The Breit-Wigner's probability above m: atan2(theta Gamma, m^2 - theta^2) / (pi/2 + atan(theta / Gamma))."""
    s = np.arctan2(theta * GAMMA, m * m - theta * theta) / _norm(theta)
    return np.where(m > 0, s, 1.0)


def acceptance(theta, panels=24, nodes=24, hermite=96):
    """A(theta), the selection's probability for the signal: over eta (Gauss-Legendre by panels), for each
    threshold a of pT, P(pT > a | eta) = int BW(m) Phi((m/2c - a) / sigma_l) dm = int S(m) N(m; 2ca, 2c sigma_l) dm
    by parts (S the survival function), by Gauss-Hermite."""
    th = np.atleast_1d(np.asarray(theta, dtype=float))
    t, w = np.polynomial.legendre.leggauss(nodes)
    edges = np.linspace(0.0, ETA_MAX, panels + 1)
    half = 0.5 * (edges[1:] - edges[:-1])
    eta = (edges[:-1, None] + half[:, None] * (t[None, :] + 1.0)).ravel()
    we = (half[:, None] * w[None, :]).ravel()
    gt, gw = np.polynomial.hermite.hermgauss(hermite)
    c, sl, fe = np.cosh(eta), sig_l(eta), np.exp(log_f_eta(eta))
    out = np.empty(len(th))
    for k, x in enumerate(th):
        inner = np.zeros(len(eta))
        for a, sign in ((PT_LO, 1.0), (PT_HI, -1.0)):
            m = (2.0 * c * a)[:, None] + SQ2 * (2.0 * c * sl)[:, None] * gt[None, :]
            inner += sign * (_survival(m, x) @ gw) / math.sqrt(math.pi)
        out[k] = 2.0 * float(np.sum(we * fe * inner))
    return out


class Acceptance:
    """A(theta) tabulated past the prior, its logarithm and derivative by a cubic spline (0.025 GeV apart)."""

    def __init__(self, lo=PRIOR[0] - 1.0, hi=PRIOR[1] + 1.0, points=161):
        from scipy.interpolate import CubicSpline
        self.grid = np.linspace(lo, hi, points)
        self.values = acceptance(self.grid)
        self.spline = CubicSpline(self.grid, np.log(self.values))

    def log(self, theta):
        return self.spline(np.asarray(theta, dtype=float))

    def dlog(self, theta):
        return self.spline(np.asarray(theta, dtype=float), 1)


# ----------------------------------------------------------------------------------------------------------------
# what the simulator knows: the joint likelihood ratio and score
# ----------------------------------------------------------------------------------------------------------------

def log_bw(m, theta):
    return (np.log(2.0 * m) - np.log((m * m - theta * theta) ** 2 + (theta * GAMMA) ** 2)
            - np.log(_norm(theta)) + np.log(theta * GAMMA))


def dlog_bw(m, theta):
    d = (m * m - theta * theta) ** 2 + (theta * GAMMA) ** 2
    dd = -4.0 * theta * (m * m - theta * theta) + 2.0 * theta * GAMMA * GAMMA
    dlogz = GAMMA / (GAMMA * GAMMA + theta * theta) / _norm(theta) - 1.0 / theta
    return -dd / d - dlogz


def joint_log_ratio(ev, theta, acc, ref=REF):
    """log r(x, z | theta, ref) of each event at its own theta: the Breit-Wigners' ratio at its latent mass, the
    acceptances' -- 0 for the background, which knows nothing of theta."""
    m = ev["m"]
    sig = ~np.isnan(m)
    out = np.zeros(len(m))
    th = np.broadcast_to(np.asarray(theta, dtype=float), m.shape)[sig]
    out[sig] = log_bw(m[sig], th) - log_bw(m[sig], ref) - acc.log(th) + acc.log(ref)
    return out


def joint_score(ev, theta, acc):
    m = ev["m"]
    sig = ~np.isnan(m)
    out = np.zeros(len(m))
    th = np.broadcast_to(np.asarray(theta, dtype=float), m.shape)[sig]
    out[sig] = dlog_bw(m[sig], th) - acc.dlog(th)
    return out


# ----------------------------------------------------------------------------------------------------------------
# the networks
# ----------------------------------------------------------------------------------------------------------------

def mt(ev):
    return 2.0 * np.sqrt(np.maximum(ev["x"] * ev["y"], 0.0))


def _ratio_net(width, depth, mean, std):
    import torch

    class Ratio(torch.nn.Module):
        """log r(x | theta, theta_ref) = a1 t + a2 t^2 + a3 t^3 + a4 t^4 with t = (theta - theta_ref) / 1 GeV, the a_k
        functions of the event: zero at the reference by construction, a1 the score there. Over the prior the exact
        log r is such a quartic to 2e-5 of its 0.2 (measured): the network learns four functions of the event
        instead of one of the event and the mass, and is evaluated at every mass for the price of one."""

        def __init__(self):
            super().__init__()
            layers, d = [], len(mean)
            for _ in range(depth):
                layers += [torch.nn.Linear(d, width), torch.nn.SiLU()]
                d = width
            layers.append(torch.nn.Linear(d, DEGREE))
            self.a = torch.nn.Sequential(*layers)
            self.register_buffer("mean", torch.tensor(mean, dtype=torch.float32))
            self.register_buffer("std", torch.tensor(std, dtype=torch.float32))

        def coefficients(self, x, y, eta):
            m_t = 2.0 * torch.sqrt(torch.clamp(x * y, min=0.0))
            e_l = x * torch.cosh(eta)
            return self.a((torch.stack([x, y, eta, m_t, e_l], -1) - self.mean) / self.std)

        def forward(self, x, y, eta, theta):
            t = (theta - REF) / SCALE
            t2 = t * t
            return (self.coefficients(x, y, eta) * torch.stack([t, t2, t2 * t, t2 * t2], -1)).sum(-1)

    return Ratio()


def _basis(t):
    """t, t^2, t^3, t^4 along a last axis."""
    t2 = t * t
    import torch
    return torch.stack([t, t2, t2 * t, t2 * t2], -1)


def _tensors(ev, dtype=None):
    import torch
    dtype = dtype or torch.float32
    return [torch.as_tensor(ev[k], dtype=dtype) for k in ("x", "y", "eta")]


class _Joint:
    """The joint log ratio and score in torch, at any theta: the Breit-Wigner's in closed form, log A(theta) by a
    polynomial of degree 8 in t fitted to its table (within 1e-10 over the prior)."""

    def __init__(self, acc, degree=8, dtype=None):
        import torch
        dtype = dtype or torch.float64
        t = (acc.grid - REF) / SCALE
        keep = (acc.grid >= PRIOR[0] - 0.1) & (acc.grid <= PRIOR[1] + 0.1)
        self.c = np.polynomial.polynomial.polyfit(t[keep], np.log(acc.values[keep]), degree)
        self.fit_error = float(np.max(np.abs(np.polynomial.polynomial.polyval(t[keep], self.c)
                                             - np.log(acc.values[keep]))))
        self.ct = torch.tensor(self.c, dtype=dtype)
        self.dct = torch.tensor(np.polynomial.polynomial.polyder(self.c), dtype=dtype)

    @staticmethod
    def _horner(c, t):
        out = c[-1] * torch_ones_like(t)
        for k in range(len(c) - 2, -1, -1):
            out = out * t + c[k]
        return out

    def log_bw(self, m, theta):
        import torch
        return (torch.log(2.0 * m) - torch.log((m * m - theta * theta) ** 2 + (theta * GAMMA) ** 2)
                - torch.log(0.5 * math.pi + torch.atan(theta / GAMMA)) + torch.log(theta * GAMMA))

    def dlog_bw(self, m, theta):
        import torch
        d = (m * m - theta * theta) ** 2 + (theta * GAMMA) ** 2
        dd = -4.0 * theta * (m * m - theta * theta) + 2.0 * theta * GAMMA * GAMMA
        dlogz = GAMMA / (GAMMA * GAMMA + theta * theta) / (0.5 * math.pi + torch.atan(theta / GAMMA)) - 1.0 / theta
        return -dd / d - dlogz

    def ratio_and_score(self, m, sig, theta):
        """log r(x, z | theta, ref) and t(x, z | theta), (B, K) for m, sig of (B,) and theta of (B, K)."""
        mm = m.unsqueeze(-1).expand_as(theta)
        t = (theta - REF) / SCALE
        lr = self.log_bw(mm, theta) - self.log_bw(mm, torch_full_like(theta, REF)) - self._horner(self.ct, t) \
            + float(self.c[0])
        sc = self.dlog_bw(mm, theta) - self._horner(self.dct, t) / SCALE
        s = sig.unsqueeze(-1).to(theta.dtype)
        return lr * s, sc * s


def torch_ones_like(t):
    import torch
    return torch.ones_like(t)


def torch_full_like(t, v):
    import torch
    return torch.full_like(t, v)


def train(kind, acc, samples=200000, epochs=12, width=64, depth=3, batch=256, lr=3e-3, alpha=0.0, thetas=16,
          seed=11, precision="float32", report=None, canceled=None):
    """A ratio network trained on `samples` simulated events.

    CARL: half the events at theta ~ the prior (class 1, at their own theta), half at theta_ref (class 0, each
    given a theta drawn the same way): the classes' cross-entropy, the ratio by the likelihood-ratio trick.

    ALICES: every event at theta_ref, each at `thetas` masses of the prior a step -- class 1 the same events
    reweighted by the joint ratio, the cross-entropy -r(x, z) log s - log(1 - s) whose minimum is the ratio
    itself, E[r(x, z) | x] = r(x): what the simulator knows of each event used at every mass. alpha adds the
    joint score's regression at those masses, weighted the same way (SCANDAL's term).

    The best epoch on a held-out tenth is kept."""
    import torch
    report = report or (lambda text: None)
    canceled = canceled or (lambda: False)
    if kind not in ("carl", "alices"):
        raise ValueError("estimator %s: carl or alices" % kind)
    torch.manual_seed(seed)
    rng = np.random.default_rng(seed)
    if kind == "carl":
        half = samples // 2
        th_num = rng.uniform(PRIOR[0], PRIOR[1], half)
        ev = concat(simulate(th_num, half, rng), simulate(REF, half, rng))
        own = np.concatenate([th_num, np.full(half, np.nan)])
    else:
        ev = simulate(REF, samples, rng)
        own = np.full(samples, np.nan)
    n = len(ev["x"])
    feats = np.stack([ev["x"], ev["y"], ev["eta"], mt(ev), ev["x"] * np.cosh(ev["eta"])], 1)
    D = torch.float64 if precision == "float64" else torch.float32
    net = _ratio_net(width, depth, feats.mean(0), feats.std(0)).to(D)
    joint = _Joint(acc, dtype=D)
    T = {"x": torch.as_tensor(ev["x"], dtype=D), "y": torch.as_tensor(ev["y"], dtype=D),
         "eta": torch.as_tensor(ev["eta"], dtype=D), "own": torch.as_tensor(own, dtype=D),
         "m": torch.as_tensor(np.nan_to_num(ev["m"], nan=REF), dtype=D),
         "sig": torch.as_tensor(~np.isnan(ev["m"]))}
    perm = torch.as_tensor(rng.permutation(n))
    nval = n // 10
    val, tr = perm[:nval], perm[nval:]
    gen = torch.Generator().manual_seed(seed)
    k_draw = thetas if kind == "alices" else 1
    val_theta = torch.empty((nval, k_draw), dtype=D).uniform_(PRIOR[0], PRIOR[1], generator=gen)
    opt = torch.optim.Adam(net.parameters(), lr=lr)
    steps = epochs * ((len(tr) + batch - 1) // batch)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=lr, total_steps=steps, pct_start=0.15)
    sp = torch.nn.functional.softplus

    def loss_of(idx, theta):
        A = net.coefficients(T["x"][idx], T["y"][idx], T["eta"][idx])
        if kind == "carl":
            own_t = T["own"][idx]
            num = ~torch.isnan(own_t)
            th = torch.where(num, own_t, theta[:, 0])
            f = (A * _basis((th - REF) / SCALE)).sum(-1)
            return torch.where(num, sp(-f), sp(f)).mean()
        t = (theta - REF) / SCALE
        f = (A.unsqueeze(1) * _basis(t)).sum(-1)                       # (B, K)
        lj, sj = joint.ratio_and_score(T["m"][idx], T["sig"][idx], theta)
        r = torch.exp(lj)
        loss = (r * sp(-f) + sp(f)).mean()
        if alpha > 0:
            k = torch.arange(1, DEGREE + 1, dtype=D)
            dbasis = torch.stack([torch.ones_like(t), t, t * t, t * t * t], -1) * k   # d basis / dt
            g = (A.unsqueeze(1) * dbasis).sum(-1) / SCALE
            loss = loss + alpha * (r * (g - sj) ** 2).mean() * SCALE * SCALE
        return loss

    best, best_loss, history, t0 = None, float("inf"), [], time.perf_counter()
    for epoch in range(epochs):
        net.train()
        order = tr[torch.randperm(len(tr), generator=gen)]
        total = 0.0
        for a in range(0, len(order), batch):
            if canceled():
                raise RuntimeError("canceled")
            idx = order[a:a + batch]
            theta = torch.empty((len(idx), k_draw), dtype=D).uniform_(PRIOR[0], PRIOR[1], generator=gen)
            loss = loss_of(idx, theta)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            sched.step()
            total += loss.item() * len(idx)
        net.eval()
        with torch.no_grad():
            vl = float(loss_of(val, val_theta))
        history.append({"epoch": epoch + 1, "loss": total / len(tr), "val_loss": vl})
        if vl < best_loss:
            best_loss = vl
            best = {k: v.detach().clone() for k, v in net.state_dict().items()}
        report("  %s epoch %d: loss %.6f, held-out %.6f%s" % (kind.upper(), epoch + 1, total / len(tr), vl,
                                                              " (best)" if vl == best_loss else ""))
    net.load_state_dict(best)
    net.eval()
    return net, {"history": history, "seconds": time.perf_counter() - t0, "samples": int(samples),
                 "weights": int(sum(p.numel() for p in net.parameters())), "acceptance_fit": joint.fit_error}


def net_log_ratio(net, ev, theta, chunk=200000):
    """The network's log r of each event at each theta, (n, g): its four coefficients once, the quartic at every
    theta."""
    import torch
    th = np.atleast_1d(np.asarray(theta, dtype=float))
    t = (th - REF) / SCALE
    basis = np.stack([t, t ** 2, t ** 3, t ** 4], 0)
    n = len(ev["x"])
    out = np.empty((n, len(th)))
    dtype = next(net.parameters()).dtype
    with torch.no_grad():
        for a in range(0, n, chunk):
            x, y, eta = _tensors(take(ev, slice(a, a + chunk)), dtype)
            out[a:a + chunk] = net.coefficients(x, y, eta).double().numpy() @ basis
    return out


# ----------------------------------------------------------------------------------------------------------------
# the measurement
# ----------------------------------------------------------------------------------------------------------------

def mle(grid, curve, truth):
    """-2 log L on the grid, splined: its minimum, the interval where it rises by 1, whether the truth is in it."""
    from scipy.interpolate import CubicSpline
    cs = CubicSpline(grid, curve)
    fine = np.linspace(grid[0], grid[-1], 8001)
    v = cs(fine)
    k = int(np.argmin(v))
    d = v - v[k]
    lo = k
    while lo > 0 and d[lo] < 1.0:
        lo -= 1
    hi = k
    while hi < len(fine) - 1 and d[hi] < 1.0:
        hi += 1

    def cross(i, j):
        if d[i] < 1.0 or d[j] >= 1.0 or i == j:
            return fine[i]
        return fine[i] + (fine[j] - fine[i]) * (1.0 - d[i]) / (d[j] - d[i])

    return {"theta": float(fine[k]), "lo": float(cross(lo, lo + 1)) if lo < k else float(fine[0]),
            "hi": float(cross(hi, hi - 1)) if hi > k else float(fine[-1]),
            "covered": bool(cs(truth) - v[k] <= 1.0), "edge": bool(k == 0 or k == len(fine) - 1)}


def _summary(fits, truths):
    est = np.array([f["theta"] for f in fits])
    err = est - truths
    width = np.array([0.5 * (f["hi"] - f["lo"]) for f in fits])
    pulls = err / np.maximum(width, 1e-12)
    cov = np.array([f["covered"] for f in fits], dtype=float)
    k = len(fits)
    return {"bias_mev": 1e3 * float(err.mean()), "bias_err_mev": 1e3 * float(err.std(ddof=1) / math.sqrt(k)),
            "rms_mev": 1e3 * float(np.sqrt(np.mean(err ** 2))), "sigma_mev": 1e3 * float(width.mean()),
            "pull_mean": float(pulls.mean()), "pull_width": float(pulls.std(ddof=1)),
            "coverage": float(cov.mean()), "coverage_err": float(math.sqrt(0.683 * 0.317 / k)),
            "at_edge": int(sum(f["edge"] for f in fits))}


def templates(acc, grid, n, rng, edges):
    """The template of each observable at each theta of the grid, (bins, g): one sample at theta_ref, reweighted
    by the joint ratio -- exact in expectation, smooth in theta."""
    ev = simulate(REF, n, rng)
    sig = ~np.isnan(ev["m"])
    w = np.ones((n, len(grid)))
    for j, t in enumerate(grid):
        w[sig, j] = np.exp(joint_log_ratio(take(ev, sig), t, acc))
    out = {}
    for name, (values, e) in {"pt": (ev["x"], edges["pt"]), "mt": (mt(ev), edges["mt"])}.items():
        b = np.digitize(values, e)
        t = np.stack([np.bincount(b, weights=w[:, j], minlength=len(e) + 1) for j in range(len(grid))], 1)
        out[name] = t / t.sum(0, keepdims=True)
    return out


def _expect(fits_by, truths):
    """Each method's summary, and its estimates against the exact likelihood's on the same pseudo-experiments:
    the shift and its spread, free of the experiments' own fluctuations."""
    out = {k: _summary(v, truths) for k, v in fits_by.items()}
    if "exact" in fits_by:
        ex = np.array([f["theta"] for f in fits_by["exact"]])
        for k, v in fits_by.items():
            d = np.array([f["theta"] for f in v]) - ex
            out[k]["shift_vs_exact_mev"] = 1e3 * float(d.mean())
            out[k]["shift_vs_exact_err_mev"] = 1e3 * float(d.std(ddof=1) / math.sqrt(len(d)))
            out[k]["rms_vs_exact_mev"] = 1e3 * float(np.sqrt(np.mean(d ** 2)))
    return out


def check(acc=None, seed=5, rows=400000):
    """The exact likelihood held to brute force: the Faddeeva form against adaptive quadrature over m, the
    acceptance against the simulator's count, and the density's integral over the selection (by importance
    sampling, uniform in the selected box) against the acceptance."""
    from scipy.integrate import quad
    rng = np.random.default_rng(seed)
    acc = acc or Acceptance()
    ev = simulate(REF, 6, rng)
    pre, mu, s = _signal_parts(ev)
    worst = 0.0
    for t in (79.6, REF, 81.2):
        closed = voigt(mu, s, t)[:, 0]
        for i in range(len(mu)):
            f = lambda m: math.exp(log_bw(m, t)) * math.exp(-0.5 * ((m - mu[i]) / s[i]) ** 2) / (s[i] * math.sqrt(2 * math.pi))
            lo, hi = max(1e-9, mu[i] - 30 * s[i]), mu[i] + 30 * s[i]
            pts = [p for p in (t - 3 * GAMMA, t, t + 3 * GAMMA, mu[i]) if lo < p < hi]
            num = quad(f, lo, hi, points=sorted(pts), epsabs=0, epsrel=1e-13, limit=400)[0]
            worst = max(worst, abs(closed[i] / num - 1.0))
    # acceptance: quadrature against the raw simulator's count
    n_raw = rows * 5
    mm = _bw_mass(np.full(n_raw, REF), rng)
    with np.errstate(over="ignore", invalid="ignore"):
        e = np.arctanh(_cos_star(rng, n_raw))
        u = mm / (2.0 * np.cosh(e))
        xl = u + sig_l(e) * rng.standard_normal(n_raw)
    passed = float(np.mean((xl > PT_LO) & (xl < PT_HI) & (np.abs(e) < ETA_MAX)))
    a_ref = float(acceptance(REF)[0])
    a_err = math.sqrt(a_ref * (1 - a_ref) / n_raw)
    # the two pT measured as one: the density integrated over the missing pT (Gauss-Hermite around pT_l) is
    # the one measurement's closed form, f(eta) 2c int BW(m) N(m; 2c pT, 2c sigma_l)
    gt, gw = np.polynomial.hermite.hermgauss(80)
    few = simulate(REF, 8, rng)
    st = np.sqrt(sig_l(few["eta"]) ** 2 + SIG_NU ** 2)
    rep = {"x": np.repeat(few["x"], len(gt)), "eta": np.repeat(few["eta"], len(gt)),
           "y": (few["x"][:, None] + SQ2 * st[:, None] * gt[None, :]).ravel()}
    pre, mu, s = _signal_parts(rep)
    full = np.exp(pre + np.log(voigt(mu, s, REF)[:, 0]) + 0.5 * (rep["x"] - rep["y"]) ** 2 / np.repeat(st, len(gt)) ** 2)
    by_y = (full.reshape(len(few["x"]), -1) @ gw) * SQ2 * st
    c = np.cosh(few["eta"])
    one = np.exp(log_f_eta(few["eta"])) * 2.0 * c * voigt(2.0 * c * few["x"], 2.0 * c * sig_l(few["eta"]), REF)[:, 0]
    pair_gap = float(np.max(np.abs(by_y / one - 1.0)))
    # the density's integral over the selection, by Gauss-Legendre in pT and eta over the closed form, against A
    t, w = np.polynomial.legendre.leggauss(16)
    ex = np.linspace(PT_LO, PT_HI, 71)
    hx = 0.5 * np.diff(ex)
    xs, wx = (ex[:-1, None] + hx[:, None] * (t[None, :] + 1.0)).ravel(), (hx[:, None] * w[None, :]).ravel()
    ee = np.linspace(0.0, ETA_MAX, 25)
    he = 0.5 * np.diff(ee)
    es, we = (ee[:-1, None] + he[:, None] * (t[None, :] + 1.0)).ravel(), (he[:, None] * w[None, :]).ravel()
    X, E = np.meshgrid(xs, es, indexing="ij")
    c = np.cosh(E.ravel())
    dens = np.exp(log_f_eta(E.ravel())) * 2.0 * c * voigt(2.0 * c * X.ravel(), 2.0 * c * sig_l(E.ravel()), REF)[:, 0]
    integral = 2.0 * float(wx @ dens.reshape(X.shape) @ we)
    # the joint ratio's weights average to one over the reference's signal
    ev = simulate(REF, rows, rng)
    sig = ~np.isnan(ev["m"])
    wts = np.exp(joint_log_ratio(take(ev, sig), 80.9, acc))
    return {"faddeeva_vs_quadrature": worst, "missing_pt_integral_vs_closed_form": pair_gap, "acceptance": a_ref,
            "density_integral": integral, "density_integral_vs_acceptance": abs(integral / a_ref - 1.0),
            "acceptance_counted": passed, "acceptance_pull": (passed - a_ref) / a_err,
            "joint_weights_mean": float(wts.mean()), "joint_weights_pull": float((wts.mean() - 1.0) /
                                                                                  (wts.std() / math.sqrt(len(wts))))}


def coefficients(net, ev, chunk=200000):
    """The network's four coefficients of each event, (n, 4)."""
    import torch
    n = len(ev["x"])
    out = np.empty((n, DEGREE))
    dtype = next(net.parameters()).dtype
    with torch.no_grad():
        for a in range(0, n, chunk):
            x, y, eta = _tensors(take(ev, slice(a, a + chunk)), dtype)
            out[a:a + chunk] = net.coefficients(x, y, eta).double().numpy()
    return out


def _dbasis(t):
    """d/dtheta and d2/dtheta2 of t, t^2, t^3, t^4 (t in units of SCALE), each (4, g)."""
    one = np.ones_like(t)
    return (np.stack([one, 2 * t, 3 * t * t, 4 * t ** 3], 0) / SCALE,
            np.stack([0 * one, 2 * one, 6 * t, 12 * t * t], 0) / SCALE ** 2)


def score_calibration(net, acc, grid, n, rng, chunk=100000):
    """c(theta) = E_theta[d/dtheta log r^(x | theta)] on the grid -- zero for the true ratio (a score has mean zero
    under its own theta), whatever is not the network's error -- from reference events reweighted by the joint
    ratio (exact in expectation), and its statistical error. Subtracting it makes the learned score an unbiased
    estimating equation: the estimate consistent whatever the network's errors, only its precision theirs."""
    t = (grid - REF) / SCALE
    d1, _ = _dbasis(t)
    s1, s2, done = np.zeros(len(grid)), np.zeros(len(grid)), 0
    while done < n:
        k = min(chunk, n - done)
        ev = simulate(REF, k, rng)
        d = coefficients(net, ev) @ d1
        sig = ~np.isnan(ev["m"])
        part = take(ev, sig)
        w = np.ones((k, len(grid)))
        for j, th in enumerate(grid):
            w[sig, j] = np.exp(joint_log_ratio(part, th, acc))
        s1 += (w * d).sum(0)
        s2 += ((w * d) ** 2).sum(0)
        done += k
    c = s1 / n
    return c, np.sqrt(np.maximum(s2 / n - c * c, 0.0) / n)


def _network_fits(A, grid, truths, events, c=None):
    """The mass of each pseudo-experiment from the network's coefficients A (experiments * events, 4): the
    minimum of -2 sum (log r^ - C(theta)), C the integral of the score's calibration c from theta_ref (none: 0),
    and its sandwich interval -- sqrt(sum psi^2) / |sum dpsi/dtheta| with psi the calibrated score of each event
    at the estimate -- honest when the network is not the likelihood, the information identity then failing."""
    from scipy.interpolate import CubicSpline
    t = (grid - REF) / SCALE
    basis = np.stack([t, t ** 2, t ** 3, t ** 4], 0)
    k = len(truths)
    curves = -2.0 * (A @ basis).reshape(k, events, -1).sum(1)
    if c is not None:
        cs = CubicSpline(grid, c)
        big_c = cs.antiderivative()
        curves = curves + 2.0 * events * (big_c(grid) - big_c(REF))[None, :]
    fits = []
    for i in range(k):
        f = mle(grid, curves[i], truths[i])
        th = np.array([(f["theta"] - REF) / SCALE])
        d1, d2 = _dbasis(th)
        Ai = A[i * events:(i + 1) * events]
        psi = (Ai @ d1)[:, 0] - (cs(f["theta"]) if c is not None else 0.0)
        dpsi = (Ai @ d2)[:, 0] - (cs(f["theta"], 1) if c is not None else 0.0)
        s = math.sqrt(float(np.sum(psi * psi))) / max(abs(float(np.sum(dpsi))), 1e-300)
        f.update({"lo": f["theta"] - s, "hi": f["theta"] + s, "covered": bool(abs(f["theta"] - truths[i]) <= s),
                  "curvature_covered": f["covered"]})
        fits.append(f)
    return fits


class _Ensemble:
    """Networks trained from different seeds (different samples), their coefficients averaged."""

    def __init__(self, nets):
        self.nets = nets

    def coefficients(self, x, y, eta):
        out = self.nets[0].coefficients(x, y, eta)
        for n in self.nets[1:]:
            out = out + n.coefficients(x, y, eta)
        return out / len(self.nets)

    def parameters(self):
        return self.nets[0].parameters()

    def module(self):
        import torch

        class Mean(torch.nn.Module):
            def __init__(self, nets):
                super().__init__()
                self.members = torch.nn.ModuleList(nets)

            def forward(self, x, y, eta, theta):
                out = self.members[0](x, y, eta, theta)
                for m in self.members[1:]:
                    out = out + m(x, y, eta, theta)
                return out / len(self.members)

        return self.nets[0] if len(self.nets) == 1 else Mean(self.nets)


def normalization(net, ref_events, grid):
    """log E_ref[r^(x | theta)] on the grid: what the learned ratio misses of p(x | theta) integrating to one. The
    likelihood of n events is corrected by n times it (the calibration CARL prescribes)."""
    r = net_log_ratio(net, ref_events, grid)
    top = r.max(0, keepdims=True)
    return (top + np.log(np.mean(np.exp(r - top), 0, keepdims=True)))[0]


def run(estimators=("carl", "alices"), samples=100000, epochs=8, width=64, depth=3, alpha=0.0, ensemble=1,
        experiments=100, events=2000, template_events=400000, calibration=1000000, seed=11, export_name="sbi_wmass",
        report=None, canceled=None):
    """The whole study: the exact likelihood checked, each estimator trained (on `samples` simulated events,
    `ensemble` times from different seeds), held to the exact log r on held-out events, then measured on
    `experiments` pseudo-experiments of `events` events against the exact likelihood, the Cramer-Rao bound and
    the template fits in pT_l and mT. The last network is exported as a portable graph."""
    report = report or (lambda text: None)
    t0 = time.perf_counter()
    acc = Acceptance()
    report("  the exact likelihood checked: Faddeeva against quadrature, the missing pT integrated, the density's"
           " integral against the acceptance, the acceptance against the simulator...")
    chk = check(acc)
    out = {"model": MODEL, "prior": list(PRIOR), "ref": REF, "check": chk, "estimators": {}}
    grid = np.linspace(PRIOR[0], PRIOR[1], 41)
    rng = np.random.default_rng(seed + 1000)
    test = simulate(rng.uniform(PRIOR[0], PRIOR[1], 20000), 20000, rng)
    exact_test = log_likelihood(test, grid, acc) - log_likelihood(test, REF, acc)
    ref_events = simulate(REF, 200000, rng)
    nets, norms = {}, {}
    for kind in estimators:
        members, infos = [], []
        for e in range(max(1, ensemble)):
            report("  training %s%s (%d simulated events, %d epochs)..." % (
                kind.upper(), " %d/%d" % (e + 1, ensemble) if ensemble > 1 else "", samples, epochs))
            net, info = train(kind, acc, samples, epochs, width, depth, alpha=alpha if kind == "alices" else 0.0,
                              seed=seed + 101 * e, report=report, canceled=canceled)
            members.append(net)
            infos.append(info)
        net = _Ensemble(members)
        info = {"seconds": sum(i["seconds"] for i in infos), "samples": int(samples) * len(members),
                "weights": infos[0]["weights"] * len(members), "members": len(members),
                "val_loss": infos[-1]["history"][-1]["val_loss"], "epochs": epochs}
        diff = net_log_ratio(net, test, grid) - exact_test
        info["logr_rms"] = float(np.sqrt(np.mean(diff ** 2)))
        info["logr_scale"] = float(np.sqrt(np.mean(exact_test ** 2)))
        norms[kind] = normalization(net, ref_events, grid)
        info["normalization_worst"] = float(np.max(np.abs(norms[kind])))
        out["estimators"][kind] = info
        nets[kind] = net
    report("  %d pseudo-experiments of %d events: the exact likelihood, the networks, the templates..." % (
        experiments, events))
    truths = rng.uniform(PRIOR[0] + 0.4, PRIOR[1] - 0.4, experiments)
    pe = simulate(np.repeat(truths, events), experiments * events, rng)
    curves = {"exact": -2.0 * log_likelihood(pe, grid, acc).reshape(experiments, events, -1).sum(1)}
    edges = {"pt": np.linspace(PT_LO, PT_HI, 36), "mt": np.linspace(40.0, 130.0, 46)}
    tmpl = templates(acc, grid, template_events, rng, edges)
    for name, values in (("pt", pe["x"]), ("mt", mt(pe))):
        b = np.digitize(values, edges[name]).reshape(experiments, events)
        counts = np.stack([np.bincount(row, minlength=len(edges[name]) + 1) for row in b])
        curves["template_" + name] = -2.0 * counts @ np.log(np.maximum(tmpl[name], 1e-300))
    fits = {k: [mle(grid, c[i], truths[i]) for i in range(experiments)] for k, c in curves.items()}
    for kind, net in nets.items():
        report("  %s's score calibrated on %d simulated events..." % (kind.upper(), calibration))
        c, c_err = score_calibration(net, acc, grid, calibration, rng)
        out["estimators"][kind]["score_calibration_worst"] = float(np.max(np.abs(c)))
        out["estimators"][kind]["score_calibration_error"] = float(np.max(c_err))
        A = coefficients(net, pe)
        fits[kind + "_raw"] = _network_fits(A, grid, truths, events)
        fits[kind] = _network_fits(A, grid, truths, events, c)
        # CARL's own calibration, for comparison: the ratio renormalized to integrate to one
        t = (grid - REF) / SCALE
        raw = -2.0 * (A @ np.stack([t, t ** 2, t ** 3, t ** 4], 0)).reshape(experiments, events, -1).sum(1)
        norm = raw + 2.0 * events * norms[kind][None, :]
        fits[kind + "_normalized"] = [mle(grid, norm[i], truths[i]) for i in range(experiments)]
    out["inference"] = _expect(fits, truths)
    # the Cramer-Rao bound at theta_ref: the exact score's variance, by differences of the exact log-likelihood
    big = simulate(REF, 100000, rng)
    h = 1e-3
    ll = log_likelihood(big, [REF - h, REF + h], acc)
    fisher = float(np.mean(((ll[:, 1] - ll[:, 0]) / (2 * h)) ** 2))
    out["cramer_rao_mev"] = 1e3 / math.sqrt(events * fisher)
    out["fisher_per_event"] = fisher
    out.update({"experiments": experiments, "events": events, "template_events": template_events})
    if export_name and nets:
        kind = "alices" if "alices" in nets else next(iter(nets))
        out["graph"] = _export(nets[kind].module(), "%s_%s" % (export_name, kind), test)
    out["seconds"] = time.perf_counter() - t0
    out["verdict"] = _verdict(out)
    return out


def _share(f):
    """A share of the exact likelihood's information: all of it within the experiments' noise."""
    return "all" if f >= 0.98 else "%.0f %%" % (100.0 * f)


def _verdict(out):
    inf = out["inference"]
    ex = inf["exact"]
    nets = [k for k in inf if not k.startswith(("exact", "template")) and not k.endswith(("_raw", "_normalized"))]
    if not nets:
        return "no network trained"
    best = min(nets, key=lambda k: inf[k]["rms_vs_exact_mev"])
    b = inf[best]
    tp = min((inf[k]["rms_mev"], k) for k in inf if k.startswith("template"))
    raw = inf.get(best + "_raw", {})
    return ("%s measures the mass as the exact likelihood does: %+.1f +- %.1f MeV from its estimates, %.1f MeV apart"
            " per experiment (%.0f MeV resolution per %d events, the exact %.0f, the Cramer-Rao bound %.0f), %s of"
            " its information, %.1f times the best template fit's precision (%s, %.0f MeV); its sandwich intervals cover"
            " %.0f %% (the exact's %.0f %%). Uncalibrated, it was %+.1f MeV off" % (
                best.upper(), b["shift_vs_exact_mev"], b["shift_vs_exact_err_mev"], b["rms_vs_exact_mev"], b["rms_mev"],
                out["events"], ex["rms_mev"], out["cramer_rao_mev"], _share((ex["rms_mev"] / max(b["rms_mev"], 1e-9)) ** 2),
                tp[0] / b["rms_mev"], tp[1].replace("template_", "in "), tp[0], 100 * b["coverage"], 100 * ex["coverage"],
                raw.get("shift_vs_exact_mev", float("nan"))))


def _export(net, name, ev):
    """The ratio network as a portable graph (for Java's interpreter and its compiler), and its double-precision
    outputs on a few events to hold Java's to."""
    import copy
    import torch
    import sphere_graph
    d = copy.deepcopy(net).double().eval()
    few = take(ev, slice(0, 16))
    x, y, eta = _tensors(few, torch.float64)
    th = torch.as_tensor(few["theta"], dtype=torch.float64)
    try:
        path = sphere_graph.export(d, (x, y, eta, th), name, feeds=None)
        if isinstance(path, dict):
            path = path.get("path", "")
        with torch.no_grad():
            ref = d(x, y, eta, th).numpy()
        return {"name": name, "path": str(path), "x": few["x"].tolist(), "y": few["y"].tolist(),
                "eta": few["eta"].tolist(), "theta": few["theta"].tolist(), "logr": ref.tolist()}
    except Exception as e:   # the measurement stands without its graph
        return {"name": name, "error": "%s: %s" % (type(e).__name__, e)}


if __name__ == "__main__":
    import json
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == "check":
        print(json.dumps(check(), indent=1))
    else:
        print(json.dumps(run(samples=int(sys.argv[1]) if len(sys.argv) > 1 else 100000,
                             epochs=int(sys.argv[2]) if len(sys.argv) > 2 else 8,
                             experiments=int(sys.argv[3]) if len(sys.argv) > 3 else 100, report=print), indent=1))

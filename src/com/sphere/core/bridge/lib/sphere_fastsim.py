"""The conditional flow behind a learned Geant4 fast simulation: trained in PyTorch, exported as graphs both ways.

Sphere's Java prepares everything -- the showers in coordinates whose mean and width it already solved in closed form,
the size of the flow -- and calls this with a job file; it holds no physics of its own. It learns p(w | ln E) with
sphere_flows, then exports two portable graphs: the sampler (z, c -> w), which GraphCpp transcribes to C++, and the
encoder (w, c -> z), with which Java measures where real showers fall in the latent space.

    python sphere_fastsim.py job.json

Each line it prints is progress; the last is `RESULT {json}`.
"""
import json
import sys

import numpy as np


def main(path):
    with open(path, encoding="utf-8") as f:
        job = json.load(f)
    import torch
    import sphere_flows
    import sphere_graph

    torch.set_num_threads(job.get("threads", 4))
    dim, context = job["dim"], job["context"]
    raw = np.fromfile(job["data"], dtype="<f8")
    rows = raw.reshape(-1, dim + context)
    x, c = rows[:, :dim], rows[:, dim:]
    print("training on %d showers, %d features" % (len(x), dim), flush=True)
    flow = sphere_flows.make_flow(dim, context, x=x, c=c, layers=job["layers"], bins=job["bins"], hidden=job["hidden"], seed=job["seed"])
    every = max(1, job["epochs"] // 10)
    count = [0]

    def report(text):
        count[0] += 1
        if count[0] % every == 0:
            print(text.strip(), flush=True)

    out = sphere_flows.train(flow, x, c, epochs=job["epochs"], batch=job["batch"], lr=job["lr"], seed=job["seed"], report=report,
                             weight_decay=job.get("weight_decay", 0.0))
    best = min(out["history"], key=lambda h: h["val_nll"])
    print("overfitting: at the best epoch (%d), NLL %.4f on the training showers, %.4f on the held-out ones: a gap of %.4f"
          % (best["epoch"], best["nll"], best["val_nll"], best["val_nll"] - best["nll"]), flush=True)
    _, _, Sampler = sphere_flows._flow_class()

    class Encoder(torch.nn.Module):
        """The flow's forward map without its log-determinant: where a shower falls in the latent space."""

        def __init__(self, flow):
            super().__init__()
            self.flow = flow

        def forward(self, w, c):
            f = self.flow
            z = (w - f.x_mean) / f.x_std
            cs = (c - f.c_mean) / f.c_std
            for l, cp in enumerate(f.couplings):
                z, _ = cp(z, cs)
                z = torch.matmul(z, f.perms[l])
            return z

    framework = "torch " + torch.__version__
    cs = torch.as_tensor(c[:8], dtype=torch.float64)
    z = torch.as_tensor(np.random.default_rng(1).standard_normal((8, dim)), dtype=torch.float64)
    sphere_graph.export(Sampler(flow).eval(), (z, cs), job["name"], folder=job["folder"], framework=framework, trainable=False)
    ws = torch.as_tensor(x[:8], dtype=torch.float64)
    sphere_graph.export(Encoder(flow).eval(), (ws, cs), job["name"] + "_encoder", folder=job["folder"], framework=framework, trainable=False)
    print("RESULT " + json.dumps({"val_nll": out["val_nll"], "seconds": out["seconds"], "epochs": len(out["history"])}), flush=True)


if __name__ == "__main__":
    main(sys.argv[1])

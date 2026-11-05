// sphere_nn.hpp
//
// A network trained in Sphere (sphere_ml: PyTorch or Keras) run from C++: a ROOT macro, a Geant4 program (a
// tagger in an analysis, a fast simulation), any C++17 program, with nothing but this header and
// sphere_spx.hpp -- no LibTorch, no TensorFlow, no ONNX Runtime. The five networks of sphere_ml's zoo (an
// MLP, Deep Sets, an Energy Flow Network, ParticleNet, a Particle Transformer) are evaluated in double precision with the formulas
// and in the order of sphere_ml's numpy reference, which ':ml crosscheck' holds them to; the weights are read
// in place from the mapped export, not copied.
//
//     #include "sphere_nn.hpp"
//     sphere::nn::Network net("tagger");                  // $SPHERE_BRIDGE/models/tagger.snn.spx
//     std::vector<double> p = net.probabilities(x, mask, hl);
//
// x holds a jet's particles, points x features, row by row, raw as sphere_ml's datasets hold them (log pt,
// log E, log pt/ptJ, log E/EJ, delta eta, delta phi, delta R by default); mask is 1 for a particle, 0 for
// padding; hl the raw high-level features (the MLP's only input). The network standardizes them itself, and
// its probabilities go through the calibration ':ml calibrate' wrote into it (a temperature, or an isotonic
// map of P(signal)), as Python's and Java's do.

#ifndef SPHERE_NN_HPP
#define SPHERE_NN_HPP

#include "sphere_spx.hpp"

#include <algorithm>
#include <cmath>
#include <map>
#include <numeric>
#include <stdexcept>
#include <string>
#include <vector>

namespace sphere {
namespace nn {

using Vec = std::vector<double>;

class Network {
public:
    explicit Network(const std::string& nameOrPath) {
        std::string path = nameOrPath;
        if (path.find('/') == std::string::npos && path.find('\\') == std::string::npos
            && path.find(".spx") == std::string::npos)
            path = spx::bridge_folder() + "/models/" + path + ".snn.spx";
        file_.open(path);
        meta_ = file_.meta();
        if (meta_["Kind"] != "sphere-nn") throw std::runtime_error(path + " is not a network exported by sphere_ml");
        model_ = meta_["Model"];
        name_ = meta_["Name"];
        classes_ = std::stoi(meta_["NClasses"]);
        features_ = std::stoi(meta_["Features"]);
        points_ = std::stoi(meta_["Points"]);
        highLevel_ = std::stoi(meta_["HighLevel"]);
        ptIndex_ = std::stoi(meta_["PtIndex"]);
        coords_ = ints(meta_["Coords"]);
        if (model_ == "mlp") {
            widths_ = ints(meta_["Widths"]);
        } else if (model_ == "deepsets" || model_ == "efn") {
            phi_ = ints(meta_["Phi"]);
            rho_ = ints(meta_["Rho"]);
        } else if (model_ == "particlenet") {
            k_ = std::stoi(meta_["K"]);
            std::string bs = meta_["Blocks"];
            size_t at = 0;
            while (at <= bs.size()) {
                const size_t semi = bs.find(';', at);
                blocks_.push_back(ints(bs.substr(at, semi == std::string::npos ? std::string::npos : semi - at)));
                if (semi == std::string::npos) break;
                at = semi + 1;
            }
        } else if (model_ == "transformer") {
            dim_ = std::stoi(meta_["Dim"]);
            heads_ = std::stoi(meta_["Heads"]);
            layers_ = std::stoi(meta_["Layers"]);
        } else {
            throw std::runtime_error("no network " + model_ + " in this header (mlp, deepsets, efn, particlenet, transformer)");
        }
        for (const auto& s : file_.sections())
            if (s.second.type == spx::F64) w_[s.first] = file_.f64(s.first, &n_[s.first]);
        calibration_ = meta_.count("Calibration") ? meta_["Calibration"] : "";
        if (meta_.count("Temperature")) temperature_ = std::stod(meta_["Temperature"]);
        if (calibration_ == "isotonic" && (!w_.count("calib.x") || !w_.count("calib.y") || n_["calib.x"] != n_["calib.y"]))
            throw std::runtime_error(path + ": an isotonic calibration without its knots");
    }

    /** The calibration ':ml calibrate' wrote: "", "temperature" or "isotonic". */
    const std::string& calibration() const { return calibration_; }

    const std::string& model() const { return model_; }
    const std::string& name() const { return name_; }
    int classes() const { return classes_; }
    int points() const { return points_; }
    int features() const { return features_; }
    int highLevel() const { return highLevel_; }

    /** The logits of one jet: x is points x features (any number of points: padded or cut to the network's). */
    Vec logits(const Vec& x, const Vec& mask, const Vec& hl = Vec()) const {
        if (model_ == "mlp") {
            Vec h(highLevel_);
            const double* mean = at("norm.hl_mean");
            const double* sd = at("norm.hl_std");
            for (int f = 0; f < highLevel_; ++f) h[f] = (hl.at(f) - mean[f]) / sd[f];
            return mlp("mlp", h, static_cast<int>(widths_.size()) + 1, true);
        }
        const int n = points_, given = static_cast<int>(mask.size());
        const double* mean = at("norm.x_mean");
        const double* sd = at("norm.x_std");
        std::vector<Vec> xs(n, Vec(features_, 0.0)), pts(n, Vec(coords_.size(), 0.0));
        Vec m(n, 0.0), pt(n, 0.0);
        for (int i = 0; i < n && i < given; ++i) {
            m[i] = mask[i];
            for (int f = 0; f < features_; ++f) xs[i][f] = (x[i * features_ + f] - mean[f]) / sd[f] * m[i];
            for (size_t c = 0; c < coords_.size(); ++c) pts[i][c] = x[i * features_ + coords_[c]];
            pt[i] = std::exp(x[i * features_ + ptIndex_]) * m[i];
        }
        if (model_ == "deepsets") return deepsets(xs, m);
        if (model_ == "efn") return efn(pts, pt);
        if (model_ == "particlenet") return particleNet(xs, pts, m);
        return transformer(xs, pts, pt, m);
    }

    /** The classes' probabilities of one jet, through the network's calibration if it has one. */
    Vec probabilities(const Vec& x, const Vec& mask, const Vec& hl = Vec()) const {
        return calibrated(logits(x, mask, hl));
    }

    // Logits made probabilities as sphere_ml.calibrated does: softmax(logits / T) for a temperature; P(signal)
    // through the isotonic map (linear between its knots, its end levels beyond, np.interp's formula).
    Vec calibrated(Vec z) const {
        if (calibration_ == "temperature")
            for (double& v : z) v /= temperature_;
        const double mx = *std::max_element(z.begin(), z.end());
        double s = 0;
        for (double& v : z) s += (v = std::exp(v - mx));
        for (double& v : z) v /= s;
        if (calibration_ == "isotonic" && z.size() == 2) {
            const double p1 = std::min(1.0, std::max(0.0, interp(z[1], at("calib.x"), at("calib.y"),
                                                                 static_cast<int>(n_.at("calib.x")))));
            z = {1.0 - p1, p1};
        }
        return z;
    }

    static double interp(double v, const double* xs, const double* ys, int n) {
        if (v >= xs[n - 1]) return ys[n - 1];
        if (v < xs[0]) return ys[0];
        int lo = 0, hi = n - 1;   // xs[lo] <= v < xs[hi]
        while (hi - lo > 1) {
            const int mid = (lo + hi) / 2;
            if (xs[mid] <= v) lo = mid; else hi = mid;
        }
        if (xs[lo] == v) return ys[lo];
        const double slope = (ys[lo + 1] - ys[lo]) / (xs[lo + 1] - xs[lo]);
        return slope * (v - xs[lo]) + ys[lo];
    }

private:
    static constexpr double BIG = 1.0e9;

    static std::vector<int> ints(const std::string& s) {
        std::vector<int> out;
        size_t at = 0;
        while (at < s.size()) {
            size_t comma = s.find(',', at);
            if (comma == std::string::npos) comma = s.size();
            const std::string t = s.substr(at, comma - at);
            if (!t.empty()) out.push_back(std::stoi(t));
            at = comma + 1;
        }
        return out;
    }

    const double* at(const std::string& name) const {
        auto it = w_.find(name);
        if (it == w_.end()) throw std::runtime_error(name_ + ": no weight " + name);
        return it->second;
    }

    std::int64_t size(const std::string& name) const { return n_.at(name); }

    /** x W + b, W as numpy's (in, out) row-major. */
    Vec dense(const std::string& name, const Vec& x) const {
        const double* W = at(name + ".W");
        const double* b = at(name + ".b");
        const int out = static_cast<int>(size(name + ".b")), in = static_cast<int>(x.size());
        Vec y(b, b + out);
        for (int i = 0; i < in; ++i) {
            const double xi = x[i];
            if (xi == 0) continue;
            const double* row = W + static_cast<std::int64_t>(i) * out;
            for (int o = 0; o < out; ++o) y[o] += xi * row[o];
        }
        return y;
    }

    static Vec& relu(Vec& x) {
        for (double& v : x) v = std::max(v, 0.0);
        return x;
    }

    Vec mlp(const std::string& prefix, Vec x, int n, bool lastLinear) const {
        for (int i = 0; i < n; ++i) {
            x = dense(prefix + "." + std::to_string(i), x);
            if (i < n - 1 || !lastLinear) relu(x);
        }
        return x;
    }

    Vec norm(const std::string& name, const Vec& x) const {
        const double* g = at(name + ".g");
        const double* b = at(name + ".b");
        double m = 0, v = 0;
        for (double a : x) m += a;
        m /= x.size();
        for (double a : x) v += (a - m) * (a - m);
        v /= x.size();
        const double s = std::sqrt(v + 1e-5);
        Vec out(x.size());
        for (size_t i = 0; i < x.size(); ++i) out[i] = (x[i] - m) / s * g[i] + b[i];
        return out;
    }

    Vec deepsets(const std::vector<Vec>& xs, const Vec& m) const {
        Vec sum;
        for (size_t i = 0; i < xs.size(); ++i) {
            const Vec h = mlp("phi", xs[i], static_cast<int>(phi_.size()), false);
            if (sum.empty()) sum.assign(h.size(), 0.0);
            for (size_t c = 0; c < h.size(); ++c) sum[c] += h[c] * m[i];
        }
        return mlp("rho", sum, static_cast<int>(rho_.size()) + 1, true);
    }

    // F(sum_i z_i Phi(deta_i, dphi_i)), z_i the particle's share of the jet's pt: infrared and collinear safe.
    Vec efn(const std::vector<Vec>& pts, const Vec& pt) const {
        const double total = std::max(std::accumulate(pt.begin(), pt.end(), 0.0), 1e-30);
        Vec sum;
        for (size_t i = 0; i < pts.size(); ++i) {
            const Vec h = mlp("phi", pts[i], static_cast<int>(phi_.size()), false);
            if (sum.empty()) sum.assign(h.size(), 0.0);
            const double z = pt[i] / total;
            for (size_t c = 0; c < h.size(); ++c) sum[c] += h[c] * z;
        }
        return mlp("rho", sum, static_cast<int>(rho_.size()) + 1, true);
    }

    /** The k nearest of each particle, by distance then index (numpy's stable argsort); padding never chosen. */
    static std::vector<std::vector<int>> nearest(const std::vector<Vec>& p, const Vec& m, int k) {
        const int n = static_cast<int>(p.size());
        std::vector<std::vector<int>> out(n);
        std::vector<int> order(n);
        Vec d(n);
        for (int i = 0; i < n; ++i) {
            for (int j = 0; j < n; ++j) {
                double s = 0;
                for (size_t c = 0; c < p[i].size(); ++c) {
                    const double t = p[i][c] - p[j][c];
                    s += t * t;
                }
                d[j] = s + (1.0 - m[j]) * BIG + (i == j ? BIG : 0.0);
                order[j] = j;
            }
            std::stable_sort(order.begin(), order.end(), [&](int a, int b) { return d[a] < d[b]; });
            out[i].assign(order.begin(), order.begin() + k);
        }
        return out;
    }

    std::vector<Vec> edgeConv(int b, const std::vector<Vec>& pts, const std::vector<Vec>& x, const Vec& m, int k,
                              const std::vector<int>& widths) const {
        const int n = static_cast<int>(x.size()), c = static_cast<int>(x[0].size()), c0 = widths[0];
        const auto nb = nearest(pts, m, k);
        const std::string ec = "ec" + std::to_string(b);
        const double* W0 = at(ec + ".0.W");
        const double* b0 = at(ec + ".0.b");
        // the first layer split as sphere_ml splits it: x_i (W_i - W_j) + b, then x_j W_j
        std::vector<Vec> own(n, Vec(c0)), their(n, Vec(c0));
        for (int i = 0; i < n; ++i)
            for (int o = 0; o < c0; ++o) {
                double a = 0, t = 0;
                for (int f = 0; f < c; ++f) {
                    const double wi = W0[f * c0 + o], wj = W0[(c + f) * c0 + o];
                    a += x[i][f] * (wi - wj);
                    t += x[i][f] * wj;
                }
                own[i][o] = a + b0[o];
                their[i][o] = t;
            }
        std::vector<Vec> out(n);
        for (int i = 0; i < n; ++i) {
            Vec agg;
            for (int t = 0; t < k; ++t) {
                const int j = nb[i][t];
                Vec e(c0);
                for (int o = 0; o < c0; ++o) e[o] = std::max(their[j][o] + own[i][o], 0.0);
                for (size_t l = 1; l < widths.size(); ++l) {
                    e = dense(ec + "." + std::to_string(l), e);
                    relu(e);
                }
                if (agg.empty()) agg.assign(e.size(), 0.0);
                for (size_t o = 0; o < e.size(); ++o) agg[o] += e[o];
            }
            Vec sc = dense(ec + ".s", x[i]);
            for (size_t o = 0; o < sc.size(); ++o) sc[o] = std::max(agg[o] / k + sc[o], 0.0) * m[i];
            out[i] = sc;
        }
        return out;
    }

    Vec particleNet(const std::vector<Vec>& xs, const std::vector<Vec>& pts, const Vec& m) const {
        const int k = std::min(k_, points_ - 1);
        std::vector<Vec> h = xs;
        for (size_t b = 0; b < blocks_.size(); ++b) h = edgeConv(static_cast<int>(b), b == 0 ? pts : h, h, m, k, blocks_[b]);
        Vec g(h[0].size(), 0.0);
        double count = 0;
        for (size_t i = 0; i < h.size(); ++i) {
            count += m[i];
            for (size_t c = 0; c < g.size(); ++c) g[c] += h[i][c] * m[i];
        }
        for (double& v : g) v /= std::max(count, 1.0);
        Vec f = dense("fc.0", g);
        return dense("fc.1", relu(f));
    }

    static Vec pairFeatures(double pti, double ptj, double deta, double dphi, double mm) {
        const double eps = 1e-8;
        const double delta = std::sqrt(deta * deta + dphi * dphi + eps);
        const double ptmin = std::min(pti, ptj), kt = ptmin * delta, z = ptmin / (pti + ptj + eps);
        const double sh = std::sinh(0.5 * deta), sn = std::sin(0.5 * dphi);
        const double m2 = 4.0 * pti * ptj * (sh * sh + sn * sn);
        return {std::log(delta + eps) * mm, std::log(kt + eps) * mm, std::log(z + eps) * mm,
                std::log(std::max(m2, 0.0) + eps) * mm};
    }

    std::vector<Vec> attention(const std::string& name, const std::vector<Vec>& q, const std::vector<Vec>& kv,
                               const Vec& keyMask, const std::vector<std::vector<Vec>>* bias) const {
        const size_t nq = q.size(), nk = kv.size();
        const int dh = dim_ / heads_;
        std::vector<Vec> Q(nq), K(nk), V(nk);
        for (size_t i = 0; i < nq; ++i) Q[i] = dense(name + ".q", q[i]);
        for (size_t j = 0; j < nk; ++j) {
            K[j] = dense(name + ".k", kv[j]);
            V[j] = dense(name + ".v", kv[j]);
        }
        const double scale = std::sqrt(static_cast<double>(dh));
        std::vector<Vec> out(nq);
        Vec s(nk);
        for (size_t i = 0; i < nq; ++i) {
            Vec o(dim_, 0.0);
            for (int h = 0; h < heads_; ++h) {
                double mx = -std::numeric_limits<double>::infinity();
                for (size_t j = 0; j < nk; ++j) {
                    double dot = 0;
                    for (int t = h * dh; t < (h + 1) * dh; ++t) dot += Q[i][t] * K[j][t];
                    s[j] = dot / scale + (bias ? (*bias)[h][i][j] : 0.0) - (1.0 - keyMask[j]) * BIG;
                    mx = std::max(mx, s[j]);
                }
                double sum = 0;
                for (size_t j = 0; j < nk; ++j) sum += (s[j] = std::exp(s[j] - mx));
                for (size_t j = 0; j < nk; ++j) {
                    const double a = s[j] / sum;
                    for (int t = h * dh; t < (h + 1) * dh; ++t) o[t] += a * V[j][t];
                }
            }
            out[i] = dense(name + ".o", o);
        }
        return out;
    }

    std::vector<Vec> block(const std::string& name, const std::vector<Vec>& x, const std::vector<Vec>* kv,
                           const Vec& keyMask, const std::vector<std::vector<Vec>>* bias) const {
        std::vector<Vec> h(x.size());
        for (size_t i = 0; i < x.size(); ++i) h[i] = norm(name + ".n1", x[i]);
        std::vector<Vec> hk;
        if (kv) {
            hk.resize(kv->size());
            for (size_t j = 0; j < kv->size(); ++j) hk[j] = norm(name + ".n1", (*kv)[j]);
        }
        const auto a = attention(name, h, kv ? hk : h, keyMask, bias);
        std::vector<Vec> out(x.size());
        for (size_t i = 0; i < x.size(); ++i) {
            Vec r(dim_);
            for (int t = 0; t < dim_; ++t) r[t] = x[i][t] + a[i][t];
            Vec f0 = dense(name + ".f0", norm(name + ".n2", r));
            const Vec f = dense(name + ".f1", relu(f0));
            for (int t = 0; t < dim_; ++t) r[t] += f[t];
            out[i] = r;
        }
        return out;
    }

    Vec transformer(const std::vector<Vec>& xs, const std::vector<Vec>& pts, const Vec& pt, const Vec& m) const {
        const size_t n = xs.size();
        std::vector<Vec> h(n);
        for (size_t i = 0; i < n; ++i) {
            h[i] = mlp("emb", xs[i], 2, false);
            for (double& v : h[i]) v *= m[i];
        }
        std::vector<std::vector<Vec>> bias(heads_, std::vector<Vec>(n, Vec(n)));
        for (size_t i = 0; i < n; ++i)
            for (size_t j = 0; j < n; ++j) {
                const Vec b = mlp("pair", pairFeatures(pt[i], pt[j], pts[i][0] - pts[j][0], pts[i][1] - pts[j][1],
                                                       m[i] * m[j]), 2, true);
                for (int hh = 0; hh < heads_; ++hh) bias[hh][i][j] = b[hh];
            }
        for (int l = 0; l < layers_; ++l) {
            h = block("blk" + std::to_string(l), h, nullptr, m, &bias);
            for (size_t i = 0; i < n; ++i)
                for (double& v : h[i]) v *= m[i];
        }
        const double* token = at("cls.token");
        const Vec cls(token, token + dim_);
        std::vector<Vec> kv;
        kv.reserve(n + 1);
        kv.push_back(cls);
        kv.insert(kv.end(), h.begin(), h.end());
        Vec km(n + 1, 1.0);
        std::copy(m.begin(), m.end(), km.begin() + 1);
        const auto c = block("cls", std::vector<Vec>{cls}, &kv, km, nullptr);
        return dense("head", norm("head.n", c[0]));
    }

    spx::File file_;
    std::map<std::string, std::string> meta_;
    std::map<std::string, const double*> w_;
    std::map<std::string, std::int64_t> n_;
    std::string model_, name_, calibration_;
    double temperature_ = 1.0;
    int classes_ = 0, features_ = 0, points_ = 0, highLevel_ = 0, ptIndex_ = 0;
    std::vector<int> coords_, widths_, phi_, rho_;
    int k_ = 0, dim_ = 0, heads_ = 0, layers_ = 0;
    std::vector<std::vector<int>> blocks_;
};

}  // namespace nn
}  // namespace sphere

#endif

package com.sphere.core.rootbackend;

/**
 * The reader again, in C++, for code that cannot call Java.
 *
 * A pipeline compiles to a shared library the engine loads, and a recipe that
 * needs a parton distribution cannot reach RootPdfGrid from there. So the same
 * format reader, the same interpolation, the same strong coupling and the same
 * catalog are carried here as one header with no dependency beyond the standard
 * library and POSIX, written out beside the pipeline source when one asks.
 *
 * The two are kept identical on purpose and checked against each other: both
 * were compared against LHAPDF's own values on the same grid and against its
 * own alpha_s classes, and both agree to the last digit a double holds. That
 * agreement is for points inside the grid and for the coupling; the
 * extrapolation beyond the grid follows whichever rule was asked for.
 *
 * The text is held in several pieces because a single string constant in a
 * class file cannot exceed sixty-five thousand bytes and this one is larger.
 */
public final class RootPdfHeader {

    /** What the file is called when nothing else is said. */
    public static final String FILE_NAME = "sphere_pdf.h";

    private RootPdfHeader() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** The header, ready to be written next to a pipeline. */
    public static String source() {
        return SOURCE;
    }

    /** The include line a generated pipeline needs. */
    public static String include() {
        return "#include \"" + FILE_NAME + "\"";
    }

    private static final String PART_1 = """
        // Parton distributions without LHAPDF: the grids, the strong coupling, the four
        // interpolators, the extrapolators and the catalogue.
        //
        // The grid files are plain text and the interpolation is arithmetic, so a
        // pipeline that needs a PDF does not need the library: it needs this header
        // and the files. Inside the grid the formulas are LHAPDF's own and the answer
        // is the one LHAPDF would have given, to the last digit a double holds.
        //
        // Outside the grid it is the same too, unless another rule is asked for:
        // Continuation is LHAPDF's default and is what this defaults to. The strong
        // coupling is read from the set's own info file and computed the three ways
        // LHAPDF computes it, with one difference said plainly where it is made.
        //
        // The header needs nothing beyond the standard library and POSIX. Downloading
        // a set is the exception: it hands the transfer to curl or wget and the
        // unpacking to tar, after reading the archive's names and refusing any that
        // would be written outside the destination.
        //
        // Written by Sphere. Do not edit: ':root pdf header <file>' writes it again.
        #ifndef SPHERE_PDF_H
        #define SPHERE_PDF_H

        #include <algorithm>
        #include <cmath>
        #include <cstdlib>
        #include <fstream>
        #include <map>
        #include <sstream>
        #include <stdexcept>
        #include <string>
        #include <vector>
        #include <limits>
        #include <cctype>
        #include <cstdio>
        #include <dirent.h>
        #include <sys/stat.h>

        namespace sphere {

        // Reading an info file: the values are YAML, so a list is in brackets with
        // commas, though some sets write plain whitespace-separated numbers. Both read.
        inline std::string infoText(const std::map<std::string, std::string> &info,
                                    const std::string &key, const std::string &fallback) {
            std::map<std::string, std::string>::const_iterator at = info.find(key);
            return at == info.end() ? fallback : at->second;
        }

        inline double infoNumber(const std::map<std::string, std::string> &info,
                                 const std::string &key, double fallback) {
            std::map<std::string, std::string>::const_iterator at = info.find(key);
            if (at == info.end()) return fallback;
            char *end = 0;
            const double v = std::strtod(at->second.c_str(), &end);
            return (end == at->second.c_str()) ? fallback : v;
        }

        inline std::vector<double> infoList(const std::map<std::string, std::string> &info,
                                            const std::string &key) {
            std::vector<double> out;
            std::map<std::string, std::string>::const_iterator at = info.find(key);
            if (at == info.end()) return out;
            std::string bare = at->second;
            for (std::size_t i = 0; i < bare.size(); ++i)
                if (bare[i] == '[' || bare[i] == ']' || bare[i] == ',') bare[i] = ' ';
            std::istringstream words(bare);
            double v;
            while (words >> v) out.push_back(v);
            return out;
        }

        inline bool allPresent(const std::map<std::string, std::string> &info,
                               const std::string &prefix, const char *const named[6]) {
            for (int i = 0; i < 6; ++i)
                if (!info.count(prefix + std::string(named[i]))) return false;
            return true;
        }


        }  // namespace sphere

        namespace sphere {

        // The strong coupling, as the set that carries it defines it.
        //
        // A cross section is a power of alpha_s, so the number used has to be the one
        // the set was fitted with. A set says in its info file which of three ways its
        // alpha_s is computed, and they do not agree with one another. All three are
        // here, following LHAPDF's own code.
        class AlphaS {
        public:
            enum Type { Analytic, Ipol, Ode };
            enum Flavors { Variable, Fixed };

            // What a divergent coupling answers, as LHAPDF does.
            static double divergent() { return std::numeric_limits<double>::max(); }

            // The step the solver takes in the logarithm of the scale squared.
            static double LOG_STEP_value() { return 0.001; }

            AlphaS()
              : type_(Ipol), order_(5), mz_(91.1876), asmz_(0.118), scheme_(Variable),
                fixflav_(-1), mref_(0), asref_(0), customref_(false), nfmin_(0), nfmax_(6),
                solved_(false), match_(false), ceiling_(100000.0) { }

            // Builds the coupling an info file describes. Answers false when the file
            // says nothing about alpha_s, which is normal for a grid written by hand.
            static bool fromInfo(const std::map<std::string, std::string> &info, AlphaS &out);

            // Answer exactly what LHAPDF would, including where it is wrong: it holds
            // the coupling constant above the last scale in its table, and its own
            // default table stops at 1950 GeV.
            void matchLhapdf(bool yes) { match_ = yes; solved_ = false; pieces_.clear(); }
            bool matchesLhapdf() const { return match_; }

            // Tabulates out to this scale instead of a hundred TeV. Only for a set
            // that names no scales of its own.
            void tableTo(double maxQ) {
                if (maxQ > 2000.0) { ceiling_ = maxQ; solved_ = false; pieces_.clear(); }
            }
            double tableCeiling() const { return ceiling_; }

            double alphasQ2(double q2) {
                if (q2 < 0.0) return std::nan("");
                if (type_ == Analytic) return analyticAt(q2);
                if (type_ == Ode && !solved_) tabulate();
                return interpolatedAt(q2);
            }
            double alphasQ(double q) { return alphasQ2(q * q); }

            Type type() const { return type_; }
            int orderQCD() const { return order_; }
            double mZ() const { return mz_; }
            double alphaSmZ() const { return asmz_; }

            // The scale above which LHAPDF would hold the coupling constant.
            double frozenAbove() {
                if (type_ == Ode && !solved_) tabulate();
                return q2s_.empty() ? std::nan("") : std::sqrt(q2s_.back());
            }

            // How many quark flavors are active at a scale. A quark counts once the
            // scale passes its threshold, and the beta coefficients change with it.
            int numFlavorsQ2(double q2) const {
                if (scheme_ == Fixed) return fixflav_;
                const std::map<int, double> &where = thresholds_.empty() ? masses_ : thresholds_;
                const int from = (type_ == Analytic) ? nfmin_ : 1;
                const int to = (type_ == Analytic) ? nfmax_ : 6;
                int nf = (type_ == Analytic) ? nfmin_ : 0;
                for (int q = from; q <= to; ++q) {
                    std::map<int, double>::const_iterator at = where.find(q);
                    if (at != where.end() && at->second * at->second < q2) nf = q;
                }
                if (fixflav_ != -1 && nf > fixflav_) nf = fixflav_;
                return nf;
            }

            // The decimals are LHAPDF's, kept rather than recomputed from the exact
            // fractions, so the coupling matches the library everyone compares against.
            static double beta(int i, int nf) {
                const double n = nf;
                switch (i) {
                    case 0: return 0.875352187 - 0.053051647 * n;
                    case 1: return 0.6459225457 - 0.0802126037 * n;
                    case 2: return 0.719864327 - 0.140904490 * n + 0.00303291339 * n * n;
                    case 3: return 1.172686 - 0.2785458 * n + 0.01624467 * n * n
                                   + 0.0000601247 * n * n * n;
                    default: return 1.714138 - 0.5940794 * n + 0.05607482 * n * n
                                   - 0.0007380571 * n * n * n - 0.00000587968 * n * n * n * n;
                }
            }

        private:
            struct Piece {
                std::vector<double> q2s, logq2s, as;
            };

            Type type_;
            int order_;
            double mz_, asmz_;
            Flavors scheme_;
            int fixflav_;
            double mref_, asref_;
            bool customref_;
            std::map<int, double> masses_, thresholds_, lambdas_;
            int nfmin_, nfmax_;
            std::vector<double> q2s_, values_;
            std::map<double, Piece> pieces_;
            bool solved_, match_;
            double ceiling_;

            std::vector<double> betas(int nf) const {
                std::vector<double> b(5);
                for (int i = 0; i < 5; ++i) b[i] = beta(i, nf);
                return b;
            }

            void countFlavors() {
                nfmin_ = 0; nfmax_ = 6;
                for (int q = 0; q <= 6; ++q)
                    if (lambdas_.count(q)) { nfmin_ = q; break; }
                for (int q = 6; q >= 0; --q)
                    if (lambdas_.count(q)) { nfmax_ = q; break; }
            }

            double lambdaQCD(int nf) const {
                if (scheme_ == Fixed) {
                    std::map<int, double>::const_iterator one = lambdas_.find(fixflav_);
                    if (one == lambdas_.end())
                        throw std::runtime_error("A fixed flavor scheme needs its own Lambda.");
                    return one->second;
                }
                for (int q = nf; q >= 0; --q) {
                    std::map<int, double>::const_iterator one = lambdas_.find(q);
                    if (one != lambdas_.end()) return one->second;
                }
                throw std::runtime_error("No Lambda is defined at or below that flavor count.");
            }

            double analyticAt(double q2) const {
                if (lambdas_.empty()) return std::nan("");
                const int nf = numFlavorsQ2(q2);
                const double lambda = lambdaQCD(nf);
                if (q2 <= lambda * lambda) return divergent();
                if (order_ == 0) return asmz_;

                const std::vector<double> b = betas(nf);
                const double b02 = b[0] * b[0], b12 = b[1] * b[1];
                const double lnx = std::log(q2 / (lambda * lambda));
                const double lnlnx = std::log(lnx);
                const double lnlnx2 = lnlnx * lnlnx, lnlnx3 = lnlnx2 * lnlnx;
                const double y = 1.0 / lnx;

                double sum = 1.0;
                if (order_ > 1) sum -= (b[1] * lnlnx / b02) * y;
                if (order_ > 2) {
                    const double bb = b12 / (b02 * b02);
                    sum += bb * y * y * (lnlnx2 - lnlnx + b[2] * b[0] / b12 - 1.0);
                }
                if (order_ > 3) {
                    const double cc = 1.0 / (b02 * b02 * b02);
                    const double a30 = (b12 * b[1]) * (lnlnx3 - 2.5 * lnlnx2 - 2.0 * lnlnx + 0.5);
                    const double a31 = 3.0 * b[0] * b[1] * b[2] * lnlnx;
                    const double a32 = 0.5 * b02 * b[3];
                    sum -= cc * y * y * y * (a30 + a31 - a32);
                }
                return y * sum / b[0];
            }

            // A repeated scale is a threshold: the coupling steps there, and
            // interpolating across the step would smear it away.
            void buildPieces() {
                pieces_.clear();
                std::vector<double> q, a;
                double previous = q2s_.empty() ? 0.0 : q2s_[0];
                for (std::size_t i = 0; i <= q2s_.size(); ++i) {
                    const double here = (i != q2s_.size()) ? q2s_[i] : q2s_.back();
                    const double value = (i != q2s_.size()) ? values_[i] : -1.0;
                    if (std::fabs(here - previous) < std::numeric_limits<double>::epsilon()) {
                        if (i != 0 && !q.empty()) {
                            Piece one;
                            one.q2s = q; one.as = a;
                            one.logq2s.resize(q.size());
                            for (std::size_t k = 0; k < q.size(); ++k) one.logq2s[k] = std::log(q[k]);
                            pieces_[q.front()] = one;
                        }
                        q.clear(); a.clear();
                    }
                    q.push_back(here); a.push_back(value);
                    previous = here;
                }
            }

            static double cubicAs(double t, double vl, double vdl, double vh, double vdh) {
                const double t2 = t * t, t3 = t2 * t;
                const double out = (2.0 * t3 - 3.0 * t2 + 1.0) * vl + (t3 - 2.0 * t2 + t) * vdl
                                 + (-2.0 * t3 + 3.0 * t2) * vh + (t3 - t2) * vdh;
                return std::fabs(out) < 2.0 ? out : divergent();
            }

            static double fwd(const Piece &p, std::size_t i) {
                return (p.as[i + 1] - p.as[i]) / (p.logq2s[i + 1] - p.logq2s[i]);
            }
            static double bwd(const Piece &p, std::size_t i) {
                return (p.as[i] - p.as[i - 1]) / (p.logq2s[i] - p.logq2s[i - 1]);
            }
            static double ctr(const Piece &p, std::size_t i) {
                return 0.5 * (fwd(p, i) + bwd(p, i));
            }

            double interpolatedAt(double q2) {
                if (q2s_.empty()) return std::nan("");
                if (q2 < q2s_.front()) {
                    std::size_t next = 1;
                    while (next < q2s_.size() && q2s_[0] == q2s_[next]) ++next;
                    if (next >= q2s_.size() || values_[0] <= 0.0 || values_[next] <= 0.0)
                        return values_[0];
                    const double slope = std::log10(values_[next] / values_[0])
                                       / std::log10(q2s_[next] / q2s_[0]);
                    return values_[0] * std::pow(q2 / q2s_[0], slope);
                }
                if (q2 > q2s_.back()) {
                    const std::size_t n = q2s_.size();
                    if (match_ || n < 3) return values_.back();
                    // The table ends, the coupling does not.
                    const double a = values_[n - 2], b = values_[n - 1];
                    if (a <= 0.0 || b <= 0.0 || q2s_[n - 1] == q2s_[n - 2]) return b;
                    const double slope = std::log(b / a) / std::log(q2s_[n - 1] / q2s_[n - 2]);
                    return b * std::pow(q2 / q2s_[n - 1], slope);
                }
                if (pieces_.empty()) buildPieces();
                std::map<double, Piece>::const_iterator found = pieces_.upper_bound(q2);
                if (found == pieces_.begin()) return values_[0];
                --found;
                const Piece &one = found->second;
                const std::size_t i = below(q2, one.q2s);
                double sl, sh;
                if (i == 0) { sl = fwd(one, i); sh = ctr(one, i + 1); }
                else if (i == one.logq2s.size() - 2) { sl = ctr(one, i); sh = bwd(one, i + 1); }
                else { sl = ctr(one, i); sh = ctr(one, i + 1); }
                const double d = one.logq2s[i + 1] - one.logq2s[i];
                const double t = (std::log(q2) - one.logq2s[i]) / d;
                return cubicAs(t, one.as[i], sl * d, one.as[i + 1], sh * d);
            }

            // The renormalization group equation itself: each order adds one power of
            // the coupling to the right side.
            double derivative(double t, double y, const std::vector<double> &b) const {
                if (order_ == 0) return 0.0;
                double d = b[0] * y * y;
                if (order_ == 1) return -d / t;
                d += b[1] * y * y * y;
                if (order_ == 2) return -d / t;
                d += b[2] * y * y * y * y;
                if (order_ == 3) return -d / t;
                d += b[3] * y * y * y * y * y;
                if (order_ == 4) return -d / t;
                d += b[4] * y * y * y * y * y * y;
                return -d / t;
            }

            // The coupling is not continuous where a quark enters: the theory on one
            // side has one flavor more, and the two are matched by this series.
            double decouple(double y, double t, int from, int to) const {
                if (from == to || order_ == 0) return 1.0;
                const double as = y / M_PI;
                const int heavy = from > to ? from : to;
                std::map<int, double>::const_iterator mass = masses_.find(heavy);
                if (mass == masses_.end())
                    throw std::runtime_error("Quark masses are needed to step across a threshold.");
                const double l = std::log(t / (mass->second * mass->second));
                const double l2 = l * l, l3 = l2 * l, l4 = l3 * l;
                double a1, a2, a3, a4;
                if (from > to) {
                    const double n = to;
                    a1 = -0.166666 * l * as;
                    a2 = (0.152778 - 0.458333 * l + 0.0277778 * l2) * as * as;
                    a3 = (0.972057 - 0.0846515 * n + (-1.65799 + 0.116319 * n) * l
                          + (0.0920139 - 0.0277778 * n) * l2 - 0.00462963 * l3) * as * as * as;
                    a4 = (5.17035 - 1.00993 * n - 0.0219784 * n * n
                          + (-8.42914 + 1.30983 * n + 0.0367852 * n * n) * l
                          + (0.629919 - 0.143036 * n + 0.00371335 * n * n) * l2
                          + (-0.181617 - 0.0244985 * n + 0.00308642 * n * n) * l3
                          + 0.000771605 * l4) * as * as * as * as;
                } else {
                    const double n = from;
                    a1 = 0.166667 * l * as;
                    a2 = (-0.152778 + 0.458333 * l + 0.0277778 * l2) * as * as;
                    a3 = (-0.972057 + 0.0846515 * n + (1.53067 - 0.116319 * n) * l
                          + (0.289931 + 0.0277778 * n) * l2 + 0.00462963 * l3) * as * as * as;
                    a4 = (-5.10032 + 1.00993 * n + 0.0219784 * n * n
                          + (7.03696 - 1.22518 * n - 0.0367852 * n * n) * l
                          + (1.59462 + 0.0267168 * n + 0.00371335 * n * n) * l2
                          + (0.280575 + 0.0522762 * n - 0.00308642 * n * n) * l3
                          + 0.000771605 * l4) * as * as * as * as;
                }
                double out = 1.0 + a1;
                if (order_ == 1) return out;
                out += a2;
                if (order_ == 2) return out;
                out += a3;
                if (order_ == 3) return out;
                return out + a4;
            }

            struct Walk { double t, y, h; };

            // LHAPDF recurses here without a floor; a coupling heading for its own
            // divergence then ends the process rather than the integration.
            void step(Walk &w, double allowed, const std::vector<double> &b, int depth) const {
                const double k1 = w.h * derivative(w.t, w.y, b);
                const double k2 = w.h * derivative(w.t + w.h / 2.0, w.y + k1 / 2.0, b);
                const double k3 = w.h * derivative(w.t + w.h / 2.0, w.y + k2 / 2.0, b);
                const double k4 = w.h * derivative(w.t + w.h, w.y + k3, b);
                const double change = (k1 + 2.0 * k2 + 2.0 * k3 + k4) / 6.0;
                if (w.t > 1.0 && std::fabs(change) > allowed && depth < 40) {
                    const double keep = w.h;
                    w.h = w.h / 2.0;
                    step(w, allowed, b, depth + 1);
                    w.h = keep;
                    return;
                }
                w.y += change;
                w.t += w.h;
            }

            // The same equation per unit of log of the scale squared: a step of fixed
            // size in the logarithm covers the whole range a collider uses in a few
            // thousand steps, where a step of fixed size in the scale squared needs
            // eighty million of them to reach thirteen TeV.
            double running(double y, const std::vector<double> &b) const {
                if (order_ == 0) return 0.0;
                double d = b[0] * y * y;
                if (order_ == 1) return -d;
                d += b[1] * y * y * y;
                if (order_ == 2) return -d;
                d += b[2] * y * y * y * y;
                if (order_ == 3) return -d;
                d += b[3] * y * y * y * y * y;
                if (order_ == 4) return -d;
                d += b[4] * y * y * y * y * y * y;
                return -d;
            }

            // The flavor count is read at each step rather than once, because a walk
            // that crosses a threshold has a different equation on each side of it.
            void solveInLog(double q2, Walk &w) const {
                if (q2 <= 0.0 || w.t <= 0.0 || q2 == w.t) return;
                const double from = std::log(w.t), to = std::log(q2);
                const int steps = std::max(64, int(std::ceil(std::fabs(to - from) / LOG_STEP_value())));
                const double h = (to - from) / steps;
                double u = from, y = w.y;
                for (int n = 0; n < steps; ++n) {
                    const std::vector<double> b = betas(numFlavorsQ2(std::exp(u + 0.5 * h)));
                    const double k1 = h * running(y, b);
                    const double k2 = h * running(y + k1 / 2.0, b);
                    const double k3 = h * running(y + k2 / 2.0, b);
                    const double k4 = h * running(y + k3, b);
                    y += (k1 + 2.0 * k2 + 2.0 * k3 + k4) / 6.0;
                    u += h;
                    if (!(y > 0.0) || y > 2.0) { w.t = std::exp(u); w.y = divergent(); return; }
                }
                w.t = q2;
                w.y = y;
            }

            void solve(double q2, Walk &w, double allowed, double accuracy) const {
                if (q2 == w.t) return;
                if (!match_) { solveInLog(q2, w); return; }
                long guard = 0;
                while (std::fabs(q2 - w.t) > accuracy && guard++ < 2000000) {
                    if (std::fabs(w.h) > accuracy && std::fabs(q2 - w.t) / w.h < 10.0 && w.t > 1.0)
                        w.h = accuracy / 2.1;
                    if (std::fabs(w.h) > 0.01 && w.t < 1.0) { accuracy = 0.0051; w.h = 0.01; }
                    if ((q2 < w.t && w.h > 0) || (q2 > w.t && w.h < 0)) w.h *= -1.0;
                    step(w, allowed, betas(numFlavorsQ2(w.t)), 0);
                    if (w.y > 2.0) { w.y = divergent(); return; }
                }
            }

            std::vector<double> defaultScales() const {
                std::vector<double> out;
                for (int q = 1; q / 10.0 < 1.0; ++q) out.push_back((q / 10.0) * (q / 10.0));
                for (int q = 4; q / 4.0 < mz_; ++q) out.push_back((q / 4.0) * (q / 4.0));
                for (int q = (int) std::ceil(mz_ / 4.0); 4 * q < 1000; ++q)
                    out.push_back(double(4 * q) * double(4 * q));
                for (int q = 1000 / 50; 50 * q < 2000; ++q)
                    out.push_back(double(50 * q) * double(50 * q));
                // LHAPDF's own default stops here and holds the coupling constant above
                // it, which is inside the range an LHC analysis uses every day.
                if (!match_) {
                    for (double q = 2000.0; q < ceiling_; q *= 1.1) out.push_back(q * q);
                    out.push_back(ceiling_ * ceiling_);
                }
                const std::map<int, double> &where = thresholds_.empty() ? masses_ : thresholds_;
                for (int q = 4; q <= 6; ++q) {
                    std::map<int, double>::const_iterator at = where.find(q);
                    if (at != where.end()) {
                        out.push_back(at->second * at->second);
                        out.push_back(at->second * at->second);
                    }
                }
                std::sort(out.begin(), out.end());
                return out;
            }

            // Solves once over a table of scales, then reads it off between them:
            // integrating from the reference on every call would cost more than the
            // rest of an analysis.
            void tabulate() {
                solved_ = true;
                if (q2s_.empty()) q2s_ = defaultScales();
                const double reference = customref_ ? mref_ * mref_ : mz_ * mz_;
                const double start = customref_ ? asref_ : asmz_;
                if (q2s_.back() < mz_ * mz_) q2s_.push_back(mz_ * mz_);

                std::size_t belowReference = 0;
                while (belowReference + 1 < q2s_.size() && q2s_[belowReference + 1] < mz_ * mz_)
                    ++belowReference;

                std::vector<double> out(q2s_.size(), 0.0);
                const double allowed = 0.01, accuracy = 0.001;
                Walk w; w.t = reference; w.y = start; w.h = 2.0;
                double diverged = 0.0, last = -1.0;
                bool afterThreshold = false;

                for (int at = (int) belowReference; at >= 0; --at) {
                    const double q2 = q2s_[at];
                    if (at > 1 && q2 == q2s_[at - 1]) {
                        last = q2; afterThreshold = true; w.h = 2.0 / 5.0;
                        solve(q2, w, allowed / 5.0, accuracy / 5.0);
                        out[at] = w.y;
                        w.y *= decouple(w.y, w.t, numFlavorsQ2(q2s_[at + 1]),
                                        numFlavorsQ2(q2s_[at - 2]));
                        if (w.y > 2.0) diverged = q2;
                        continue;
                    }
                    if (q2 < diverged) { out[at] = divergent(); continue; }
                    if (q2 == last) { out[at] = w.y; continue; }
                    last = q2;
                    w.h = afterThreshold ? 2.0 / 5.0 : 2.0;
                    solve(q2, w, afterThreshold ? allowed / 5.0 : allowed,
                          afterThreshold ? accuracy / 5.0 : accuracy);
                    afterThreshold = false;
                    out[at] = w.y;
                    if (w.y > 2.0) diverged = q2;
                }

                w.t = reference; w.y = start; w.h = 2.0;
                for (std::size_t at = belowReference + 1; at < q2s_.size(); ++at) {
                    const double q2 = q2s_[at];
                    if (at + 2 < q2s_.size() && q2 == q2s_[at + 1]) {
                        last = q2; w.h = 2.0 / 5.0;
                        solve(q2, w, allowed / 5.0, accuracy / 5.0);
                        out[at] = w.y;
                        w.y *= decouple(w.y, w.t, numFlavorsQ2(q2s_[at - 1]),
                                        numFlavorsQ2(q2s_[at + 2]));
                        if (w.y > 2.0) diverged = q2;
                        continue;
                    }
                    if (q2 < diverged) { out[at] = divergent(); continue; }
                    if (q2 == last) { out[at] = w.y; continue; }
                    last = q2;
                    w.h = 2.0;
                    solve(q2, w, allowed, accuracy);
                    out[at] = w.y;
                    if (w.y > 2.0) diverged = q2;
                }
                values_ = out;
                pieces_.clear();
            }

            static std::size_t below(double v, const std::vector<double> &knots) {
                std::size_t at = std::upper_bound(knots.begin(), knots.end(), v) - knots.begin();
                if (at >= knots.size()) at = knots.size() - 1;
                return at == 0 ? 0 : at - 1;
            }

            friend class Pdf;
        };


        class Pdf {
        public:
            // How the slope at a knot is estimated. Lhapdf averages the two one-sided
            // slopes, which is what LHAPDF does; Weighted uses the three-point estimate
            // for an uneven mesh, which is more accurate where the spacing changes.
            enum Accuracy { Lhapdf, Weighted };

            // Which rule joins the knots. LHAPDF offers these four and defaults to the
            // cubic in the logarithms, which is what a published number used.
            enum Interpolation { LogBicubic, Bicubic, LogBilinear, Bilinear };

            // What happens outside the grid. Continuation is LHAPDF's default; Nearest
            // answers the closest edge point; Refuse throws; Own is this header's
            // simpler continuation.
            enum Extrapolation { Continuation, Nearest, Refuse, Own };

            static Pdf open(const std::string &file, Accuracy how = Lhapdf,
                            Interpolation rule = LogBicubic, Extrapolation beyond = Continuation) {
                Pdf pdf;
                pdf.accuracy_ = how;
                pdf.rule_ = rule;
                pdf.beyond_ = beyond;
                pdf.file_ = file;
                pdf.read(file);
                return pdf;
            }

            // Member zero of a set directory.
            static Pdf openSet(const std::string &folder, int member = 0, Accuracy how = Lhapdf,
                               Interpolation rule = LogBicubic, Extrapolation beyond = Continuation) {
                std::string name = folder;
                while (!name.empty() && (name.back() == '/' || name.back() == '\\\\')) {
                    name.pop_back();
                }
                const std::size_t cut = name.find_last_of("/\\\\");
                const std::string stem = (cut == std::string::npos) ? name : name.substr(cut + 1);
                char tail[32];
                std::snprintf(tail, sizeof(tail), "_%04d.dat", member);
                return open(name + "/" + stem + tail, how, rule, beyond);
            }

            double xfxQ2(int pid, double x, double q2) const {
                const int f = slot(pid);
                if (f < 0) {
                    return 0.0;
                }
                if (x < xs_.front() || x > xs_.back()
                    || q2 < q2s_.front() || q2 > q2s_.back()) {
                    return extrapolate(f, x, q2);
                }
                return interpolate(f, x, q2);
            }

            double xfxQ(int pid, double x, double q) const { return xfxQ2(pid, x, q * q); }

            bool carries(int pid) const { return slot(pid) >= 0; }
            bool inRange(double x, double q2) const {
                return x >= xs_.front() && x <= xs_.back()
                    && q2 >= q2s_.front() && q2 <= q2s_.back();
            }

            double xMin() const { return xs_.front(); }
            double xMax() const { return xs_.back(); }
            double q2Min() const { return q2s_.front(); }
            double q2Max() const { return q2s_.back(); }
            double qMin() const { return std::sqrt(q2s_.front()); }
            double qMax() const { return std::sqrt(q2s_.back()); }
            std::size_t xKnots() const { return xs_.size(); }
            std::size_t q2Knots() const { return q2s_.size(); }
            const std::vector<int> &flavors() const { return pids_; }

            std::string meta(const std::string &key, const std::string &fallback = "") const {
                std::map<std::string, std::string>::const_iterator at = meta_.find(key);
                return at == meta_.end() ? fallback : at->second;
            }

            // The Q values where two subgrids meet, which is where a heavy quark enters.
            std::vector<double> seams() const {
                std::vector<double> found;
                for (std::size_t i = 1; i < q2s_.size(); ++i) {
                    if (q2s_[i] == q2s_[i - 1]) {
                        found.push_back(std::sqrt(q2s_[i]));
                    }
                }
                return found;
            }

            // Momentum carried by all partons, which the fit constrains to one.
            double momentum(double q2) const {
                double total = 0.0;
                for (std::size_t f = 0; f < pids_.size(); ++f) {
                    if (pids_[f] != 22) {
                        total += integrate(static_cast<int>(f), q2, true);
                    }
                }
                return total;
            }

            // Net quarks of one flavor, two for up and one for down.
            double valence(int pid, double q2) const {
                const int a = slot(pid);
                const int b = slot(-pid);
                if (a < 0 || b < 0) {
                    return 0.0;
                }
                return integrate(a, q2, false) - integrate(b, q2, false);
            }

            // The differential parton luminosity in tau, the quantity a search needs.
            double luminosity(int first, int second, double tau, double q2) const {
                if (tau <= 0.0 || tau >= 1.0) {
                    return 0.0;
                }
                const double lowest = std::log(tau);
                const int slices = 200;
                const double step = -lowest / slices;
                double total = 0.0;
                for (int s = 0; s < slices; ++s) {
                    const double half = 0.5 * step;
                    const double mid = lowest + s * step + half;
                    for (int g = 0; g < 8; ++g) {
                        const double x = std::exp(mid + half * gaussX(g));
                        const double other = tau / x;
                        if (other >= 1.0) {
                            continue;
                        }
                        double value = xfxQ2(first, x, q2) * xfxQ2(second, other, q2);
                        if (first != second) {
                            value += xfxQ2(second, x, q2) * xfxQ2(first, other, q2);
                        }
                        total += gaussW(g) * half * value;
                    }
                }
                return total / (tau * tau);
            }

            double luminosityAtMass(int first, int second, double mass, double collider) const {
                const double s = collider * collider;
                return luminosity(first, second, mass * mass / s, mass * mass) * 2.0 * mass / s;
            }

            /**
             * The strong coupling this set was fitted with.
             *
             * Read from the set's own info file beside the grid, because a cross
             * section needs the set's coupling and not another. Answers false when the
             * file says nothing about it, which is normal for a grid written by hand.
             */
            bool alphaS(AlphaS &out) const {
                std::map<std::string, std::string> info = setInfo();
                return AlphaS::fromInfo(info, out);
            }

            /** What the set's info file says, key by key. */
            std::map<std::string, std::string> setInfo() const {
        """;

    private static final String PART_2 = """
                std::map<std::string, std::string> out = meta_;
                std::string folder = file_;
                const std::size_t cut = folder.find_last_of("/\\\\");
                if (cut == std::string::npos) return out;
                folder = folder.substr(0, cut);
                std::string stem = folder;
                const std::size_t cut2 = stem.find_last_of("/\\\\");
                if (cut2 != std::string::npos) stem = stem.substr(cut2 + 1);
                std::ifstream in((folder + "/" + stem + ".info").c_str());
                if (!in) return out;
                std::string line;
                while (std::getline(in, line)) {
                    const std::string bare = trim(line);
                    if (bare.empty() || bare[0] == '#' || bare == "---") continue;
                    const std::size_t colon = bare.find(':');
                    if (colon != std::string::npos && colon > 0)
                        out[trim(bare.substr(0, colon))] = trim(bare.substr(colon + 1));
                }
                return out;
            }

        private:
            std::vector<double> xs_, logxs_, q2s_, logq2s_;
            std::vector<int> pids_;
            std::vector<double> grid_;
            std::vector<double> coeffs_;
            std::map<std::string, std::string> meta_;
            std::string file_;
            Accuracy accuracy_;
            Interpolation rule_;
            Extrapolation beyond_;

            Pdf() : accuracy_(Lhapdf), rule_(LogBicubic), beyond_(Continuation) { }

            int slot(int pid) const {
                for (std::size_t i = 0; i < pids_.size(); ++i) {
                    if (pids_[i] == pid) {
                        return static_cast<int>(i);
                    }
                }
                return -1;
            }

            static double gaussX(int i) {
                static const double v[8] = {
                    -0.9602898564975363, -0.7966664774136267, -0.5255324099163290,
                    -0.1834346424956498,  0.1834346424956498,  0.5255324099163290,
                     0.7966664774136267,  0.9602898564975363};
                return v[i];
            }
            static double gaussW(int i) {
                static const double v[8] = {
                    0.1012285362903763, 0.2223810344533745, 0.3137066458778873,
                    0.3626837833783620, 0.3626837833783620, 0.3137066458778873,
                    0.2223810344533745, 0.1012285362903763};
                return v[i];
            }

            double xf(std::size_t ix, std::size_t iq, std::size_t f) const {
                return grid_[ix * q2s_.size() * pids_.size() + iq * pids_.size() + f];
            }

            void read(const std::string &file) {
                std::ifstream in(file.c_str());
                if (!in) {
                    throw std::runtime_error("No such grid file: " + file);
                }
                std::vector<std::vector<double> > blocks;
                std::vector<std::size_t> blockQ;
                std::vector<double> current;
                std::size_t qHere = 0;
                int block = 0, blockLine = 0, lineNumber = 0;
                std::string line;

                while (std::getline(in, line)) {
                    ++lineNumber;
                    const std::string bare = trim(line);
                    if (!bare.empty() && bare[0] == '#') {
                        continue;
                    }
                    if (bare == "---") {
                        if (block > 0) {
                            blocks.push_back(current);
                            blockQ.push_back(qHere);
                        }
                        ++block;
                        blockLine = 0;
                        current.clear();
                        qHere = 0;
                        continue;
                    }
                    ++blockLine;
                    if (block == 0) {
                        const std::size_t colon = bare.find(':');
                        if (colon != std::string::npos && colon > 0) {
                            meta_[trim(bare.substr(0, colon))] = trim(bare.substr(colon + 1));
                        }
                        continue;
                    }
                    std::istringstream words(bare);
                    if (blockLine == 1) {
                        if (block == 1) {
                            double v;
                            while (words >> v) {
                                xs_.push_back(v);
                            }
                        }
                    } else if (blockLine == 2) {
                        double q;
                        while (words >> q) {
                            q2s_.push_back(q * q);
                            ++qHere;
                        }
                    } else if (blockLine == 3) {
                        if (block == 1) {
                            int p;
                            while (words >> p) {
                                pids_.push_back(p);
                            }
                        }
                    } else {
                        std::string word;
                        while (words >> word) {
                            current.push_back(value(word, file, lineNumber));
                        }
                    }
                }
                if (block > 0 && !current.empty()) {
                    blocks.push_back(current);
                    blockQ.push_back(qHere);
                }
                if (xs_.empty() || q2s_.empty() || pids_.empty()) {
                    throw std::runtime_error(file + " holds no grid.");
                }
                assemble(blocks, blockQ, file);
                logs();
                coefficients();
            }

            static std::string trim(const std::string &s) {
                std::size_t a = 0, b = s.size();
                while (a < b && std::isspace(static_cast<unsigned char>(s[a]))) ++a;
                while (b > a && std::isspace(static_cast<unsigned char>(s[b - 1]))) --b;
                return s.substr(a, b - a);
            }

            // The words nan and inf are read rather than refused: a fit that failed to
            // converge writes them, and the file is otherwise intact.
            static double value(const std::string &word, const std::string &file, int line) {
                std::string bare;
                for (std::size_t i = 0; i < word.size(); ++i) {
                    bare += static_cast<char>(std::tolower(static_cast<unsigned char>(word[i])));
                }
                if (bare.size() >= 3 && bare.compare(bare.size() - 3, 3, "nan") == 0) {
                    return std::nan("");
                }
                if (bare == "inf" || bare == "+inf" || bare == "infinity") {
                    return HUGE_VAL;
                }
                if (bare == "-inf" || bare == "-infinity") {
                    return -HUGE_VAL;
                }
                char *end = 0;
                const double v = std::strtod(word.c_str(), &end);
                if (end == word.c_str() || *end != '\\0') {
                    std::ostringstream say;
                    say << file << " line " << line << ": \\"" << word << "\\" is not a number.";
                    throw std::runtime_error(say.str());
                }
                return v;
            }

            void assemble(const std::vector<std::vector<double> > &blocks,
                          const std::vector<std::size_t> &blockQ, const std::string &file) {
                const std::size_t nx = xs_.size(), nq = q2s_.size(), nf = pids_.size();
                grid_.assign(nx * nq * nf, 0.0);
                std::size_t qOffset = 0;
                for (std::size_t b = 0; b < blocks.size(); ++b) {
                    const std::size_t here = blockQ[b];
                    const std::size_t expected = nx * here * nf;
                    if (blocks[b].size() != expected) {
                        std::ostringstream say;
                        say << file << ": block " << (b + 1) << " holds " << blocks[b].size()
                            << " values where " << expected << " were expected.";
                        throw std::runtime_error(say.str());
                    }
                    std::size_t at = 0;
                    for (std::size_t ix = 0; ix < nx; ++ix) {
                        for (std::size_t iq = 0; iq < here; ++iq) {
                            for (std::size_t f = 0; f < nf; ++f) {
                                grid_[ix * nq * nf + (qOffset + iq) * nf + f] = blocks[b][at++];
                            }
                        }
                    }
                    qOffset += here;
                }
                if (qOffset != nq) {
                    throw std::runtime_error(file + ": the blocks do not cover the Q knots.");
                }
            }

            void logs() {
                logxs_.resize(xs_.size());
                for (std::size_t i = 0; i < xs_.size(); ++i) {
                    logxs_[i] = std::log(xs_[i]);
                }
                logq2s_.resize(q2s_.size());
                for (std::size_t i = 0; i < q2s_.size(); ++i) {
                    logq2s_[i] = std::log(q2s_[i]);
                }
            }

            bool logX() const { return rule_ == LogBicubic || rule_ == LogBilinear; }
            bool cubicRule() const { return rule_ == LogBicubic || rule_ == Bicubic; }
            const std::vector<double> &xAxis() const { return logX() ? logxs_ : xs_; }
            const std::vector<double> &qAxis() const { return logX() ? logq2s_ : q2s_; }

            double slope(std::size_t ix, std::size_t iq, std::size_t f) const {
                const std::size_t nx = xs_.size();
                const std::vector<double> &ax = xAxis();
                if (ix != 0 && ix != nx - 1) {
                    const double back = ax[ix] - ax[ix - 1];
                    const double ahead = ax[ix + 1] - ax[ix];
                    const double left = (xf(ix, iq, f) - xf(ix - 1, iq, f)) / back;
                    const double right = (xf(ix + 1, iq, f) - xf(ix, iq, f)) / ahead;
                    if (accuracy_ == Weighted) {
                        return (ahead * left + back * right) / (back + ahead);
                    }
                    return (left + right) / 2.0;
                }
                if (ix == 0) {
                    return (xf(1, iq, f) - xf(0, iq, f)) / (ax[1] - ax[0]);
                }
                return (xf(nx - 1, iq, f) - xf(nx - 2, iq, f)) / (ax[nx - 1] - ax[nx - 2]);
            }

            void coefficients() {
                const std::size_t nx = xs_.size(), nq = q2s_.size(), nf = pids_.size();
                coeffs_.assign((nx - 1) * nq * nf * 4, 0.0);
                for (std::size_t ix = 0; ix + 1 < nx; ++ix) {
                    const double dlogx = xAxis()[ix + 1] - xAxis()[ix];
                    for (std::size_t iq = 0; iq < nq; ++iq) {
                        for (std::size_t f = 0; f < nf; ++f) {
                            const double vl = xf(ix, iq, f);
                            const double vh = xf(ix + 1, iq, f);
                            const double vdl = slope(ix, iq, f) * dlogx;
                            const double vdh = slope(ix + 1, iq, f) * dlogx;
                            const std::size_t at = ix * nq * nf * 4 + iq * nf * 4 + f * 4;
                            coeffs_[at] = vdh + vdl - 2.0 * vh + 2.0 * vl;
                            coeffs_[at + 1] = 3.0 * vh - 3.0 * vl - 2.0 * vdl - vdh;
                            coeffs_[at + 2] = vdl;
                            coeffs_[at + 3] = vl;
                        }
                    }
                }
            }

            static std::size_t below(double v, const std::vector<double> &knots) {
                std::size_t at = static_cast<std::size_t>(
                    std::upper_bound(knots.begin(), knots.end(), v) - knots.begin());
                if (at >= knots.size()) {
                    at = knots.size() - 1;
                }
                return at == 0 ? 0 : at - 1;
            }

            double cubic(double t, std::size_t at) const {
                const double t2 = t * t;
                return coeffs_[at] * t2 * t + coeffs_[at + 1] * t2
                     + coeffs_[at + 2] * t + coeffs_[at + 3];
            }

            static double hermite(double t, double vl, double vdl, double vh, double vdh) {
                const double t2 = t * t, t3 = t * t2;
                return (2.0 * t3 - 3.0 * t2 + 1.0) * vl + (t3 - 2.0 * t2 + t) * vdl
                     + (-2.0 * t3 + 3.0 * t2) * vh + (t3 - t2) * vdh;
            }

            static double linear(double x, double xl, double xh, double yl, double yh) {
                return yl + (x - xl) / (xh - xl) * (yh - yl);
            }

            double interpolate(int f, double x, double q2) const {
                if (!cubicRule()) {
                    return straight(f, x, q2);
                }
                const std::size_t nq = q2s_.size(), nf = pids_.size();
                const std::size_t ix = below(x, xs_);
                const std::size_t iq = below(q2, q2s_);
                const std::vector<double> &ax = xAxis();
                const std::vector<double> &aq = qAxis();
                const double logx = logX() ? std::log(x) : x;
                const double logq2 = logX() ? std::log(q2) : q2;

                const bool atLow = (iq == 0) || (q2s_[iq] == q2s_[iq - 1]);
                const bool atHigh = (iq + 1 == nq - 1) || (q2s_[iq + 1] == q2s_[iq + 2]);

                if (atLow && atHigh) {
                    const double fl = linear(logx, ax[ix], ax[ix + 1],
                                             xf(ix, iq, f), xf(ix + 1, iq, f));
                    const double fh = linear(logx, ax[ix], ax[ix + 1],
                                             xf(ix, iq + 1, f), xf(ix + 1, iq + 1, f));
                    return linear(logq2, aq[iq], aq[iq + 1], fl, fh);
                }

                const double tlogx = (logx - ax[ix]) / (ax[ix + 1] - ax[ix]);
                const double dlogq = aq[iq + 1] - aq[iq];
                const double tlogq = (logq2 - aq[iq]) / dlogq;
                const std::size_t base = ix * nq * nf * 4 + f * 4;

                const double vl = cubic(tlogx, base + iq * nf * 4);
                const double vh = cubic(tlogx, base + (iq + 1) * nf * 4);
                double vdl, vdh;

                if (atLow) {
                    vdl = vh - vl;
                    const double vhh = cubic(tlogx, base + (iq + 2) * nf * 4);
                    vdh = (vdl + (vhh - vh) * dlogq / (aq[iq + 2] - aq[iq + 1])) * 0.5;
                } else if (atHigh) {
                    vdh = vh - vl;
                    const double vll = cubic(tlogx, base + (iq - 1) * nf * 4);
                    vdl = (vdh + (vl - vll) * dlogq / (aq[iq] - aq[iq - 1])) * 0.5;
                } else {
                    const double vll = cubic(tlogx, base + (iq - 1) * nf * 4);
                    vdl = ((vh - vl) + (vl - vll) * dlogq / (aq[iq] - aq[iq - 1])) * 0.5;
                    const double vhh = cubic(tlogx, base + (iq + 2) * nf * 4);
                    vdh = ((vh - vl) + (vhh - vh) * dlogq / (aq[iq + 2] - aq[iq + 1])) * 0.5;
                }
                return hermite(tlogq, vl, vdl, vh, vdh);
            }

            // The straight line between four knots, which is the two linear rules.
            // Interpolating in x first and in Q squared after is LHAPDF's order.
            double straight(int f, double x, double q2) const {
                const std::size_t ix = below(x, xs_);
                const std::size_t iq = below(q2, q2s_);
                const std::vector<double> &ax = xAxis();
                const std::vector<double> &aq = qAxis();
                const double atX = logX() ? std::log(x) : x;
                const double atQ = logX() ? std::log(q2) : q2;
                const double low = linear(atX, ax[ix], ax[ix + 1], xf(ix, iq, f), xf(ix + 1, iq, f));
                const double high = linear(atX, ax[ix], ax[ix + 1],
                                           xf(ix, iq + 1, f), xf(ix + 1, iq + 1, f));
                return linear(atQ, aq[iq], aq[iq + 1], low, high);
            }

            // Outside the grid the shape is continued rather than clamped: a value
            // frozen at the edge is wrong in a way that hides, a continued one is
            // wrong in a way that shows.
            double extrapolate(int f, double x, double q2) const {
                switch (beyond_) {
                    case Refuse: {
                        std::ostringstream say;
                        say << "x = " << x << ", Q2 = " << q2 << " is outside the grid.";
                        throw std::runtime_error(say.str());
                    }
                    case Nearest:
                        return interpolate(f, nearestKnot(x, xs_), nearestKnot(q2, q2s_));
                    case Continuation:
                        return continuation(f, x, q2);
                    default:
                        return ownContinuation(f, x, q2);
                }
            }

            // The knot nearest a value, in the value rather than in its logarithm, as
            // LHAPDF has it.
            static double nearestKnot(double value, const std::vector<double> &knots) {
                if (value >= knots.front() && value <= knots.back()) {
                    return value;
                }
                std::size_t at = 0;
                while (at < knots.size() && knots[at] < value) {
                    ++at;
                }
                const double above = knots[std::min(at, knots.size() - 1)];
                const double under = (at == 0) ? above : knots[at - 1];
                return std::fabs(value - above) < std::fabs(value - under) ? above : under;
            }

            // LHAPDF's own continuation, as the MSTW standalone code wrote it. Below
            // the smallest scale the rate of change is measured at the edge, bounded
            // below, and bent towards one so the distribution goes to zero at zero
            // scale instead of running away.
            double continuation(int f, double x, double q2) const {
                const std::size_t nx = xs_.size(), nq = q2s_.size();
                const double xMin = xs_[0], xMin1 = xs_[1], xMax = xs_[nx - 1];
                const double q2Min = q2s_[0], q2Max1 = q2s_[nq - 2], q2Max = q2s_[nq - 1];

                if (x > xMax) {
                    std::ostringstream say;
                    say << "x = " << x << " is above the last knot at " << xMax << ".";
                    throw std::runtime_error(say.str());
                }
                if (x < xMin && q2 >= q2Min && q2 <= q2Max) {
                    return alongX(f, x, xMin, xMin1, q2);
                }
                if (x >= xMin && q2 > q2Max) {
                    return alongLog(q2, q2Max, q2Max1,
                                    interpolate(f, x, q2Max), interpolate(f, x, q2Max1));
                }
                if (x < xMin && q2 > q2Max) {
                    const double atMin = alongLog(q2, q2Max, q2Max1,
                        interpolate(f, xMin, q2Max), interpolate(f, xMin, q2Max1));
                    const double atMin1 = alongLog(q2, q2Max, q2Max1,
                        interpolate(f, xMin1, q2Max), interpolate(f, xMin1, q2Max1));
                    return alongLog(x, xMin, xMin1, atMin, atMin1);
                }
                if (q2 < q2Min) {
                    double atEdge, justAbove;
                    if (x < xMin) {
                        atEdge = alongX(f, x, xMin, xMin1, q2Min);
                        justAbove = alongX(f, x, xMin, xMin1, 1.01 * q2Min);
                    } else {
                        atEdge = interpolate(f, x, q2Min);
                        justAbove = interpolate(f, x, 1.01 * q2Min);
                    }
                    const double anomalous = std::fabs(atEdge) >= 1e-5
                        ? std::max(-2.5, (justAbove - atEdge) / atEdge / 0.01)
                        : 1.0;
                    const double ratio = q2 / q2Min;
                    return atEdge * std::pow(ratio, anomalous * ratio + 1.0 - ratio);
                }
                return interpolate(f, x, q2);
            }

            double alongX(int f, double x, double xa, double xb, double q2) const {
                return alongLog(x, xa, xb, interpolate(f, xa, q2), interpolate(f, xb, q2));
            }

            static double alongLog(double x, double xa, double xb, double fa, double fb) {
                const double t = (std::log(x) - std::log(xa)) / (std::log(xb) - std::log(xa));
                if (fa > 1e-3 && fb > 1e-3) {
                    return std::exp(std::log(fa) + t * (std::log(fb) - std::log(fa)));
                }
                return fa + t * (fb - fa);
            }

            double ownContinuation(int f, double x, double q2) const {
                const double xc = std::min(std::max(x, xs_.front()), xs_.back());
                const double qc = std::min(std::max(q2, q2s_.front()), q2s_.back());
                double value = interpolate(f, xc, qc);

                if (x < xs_.front()) {
                    const double a = interpolate(f, xs_[0], qc);
                    const double b = interpolate(f, xs_[1], qc);
                    value = continued(std::log(x), logxs_[0], logxs_[1], a, b);
                } else if (x > xs_.back()) {
                    const std::size_t n = xs_.size();
                    const double a = interpolate(f, xs_[n - 2], qc);
                    const double b = interpolate(f, xs_[n - 1], qc);
                    value = continued(std::log(x), logxs_[n - 2], logxs_[n - 1], a, b);
                }
                if (q2 < q2s_.front() || q2 > q2s_.back()) {
                    const std::size_t n = q2s_.size();
                    const bool lowEnd = q2 < q2s_.front();
                    const std::size_t ia = lowEnd ? 0 : n - 2;
                    const std::size_t ib = lowEnd ? 1 : n - 1;
                    const double a = interpolate(f, xc, q2s_[ia] * (lowEnd ? 1.0 : 1.0));
                    const double b = interpolate(f, xc, q2s_[ib]);
                    value = continued(std::log(q2), logq2s_[ia], logq2s_[ib], a, b);
                }
                return value;
            }

            // Straight in the log of the value where both ends are positive, straight
            // in the value itself where they are not, so a zero crossing survives.
            static double continued(double x, double xa, double xb, double fa, double fb) {
                if (fa > 0.0 && fb > 0.0) {
                    const double slope = (std::log(fb) - std::log(fa)) / (xb - xa);
                    return std::exp(std::log(fa) + slope * (x - xa));
                }
                return fa + (fb - fa) * (x - xa) / (xb - xa);
            }

            double integrate(int f, double q2, bool momentumWeight) const {
                double total = 0.0;
                for (std::size_t i = 0; i + 1 < xs_.size(); ++i) {
                    const double a = logxs_[i], b = logxs_[i + 1];
                    if (b - a < 1e-12) {
                        continue;
                    }
                    const double half = 0.5 * (b - a), mid = 0.5 * (a + b);
                    for (int g = 0; g < 8; ++g) {
                        const double u = mid + half * gaussX(g);
                        const double x = std::exp(u);
                        const double v = interpolate(f, x, q2);
                        total += gaussW(g) * half * (momentumWeight ? v * x : v);
                    }
                }
                return total;
            }
        };


        // Finding a set by its name, and fetching one that is not there.
        //
        // A set is referred to by a name and not by a path, because that name is what a
        // paper quotes. Turning it into a directory means looking through the folders
        // the installation declares, which LHAPDF does through LHAPDF_DATA_PATH; the
        // same variable is read here, so a machine that already has LHAPDF installed
        // needs no further configuration.
        class Catalog {
        public:
            struct Entry {
                int id;
                std::string name;
                int version;   // the data version, which is what pdfsets.index carries
            };

            // Every folder searched, in the order they are searched. Folders named here
            // come first, so one added on purpose wins over an old installation.
            static std::vector<std::string> &own() {
                static std::vector<std::string> folders;
                return folders;
            }

            static void addPath(const std::string &folder) {
                std::vector<std::string> &o = own();
                if (std::find(o.begin(), o.end(), folder) == o.end()) o.push_back(folder);
            }

            static std::vector<std::string> paths() {
                std::vector<std::string> out = own();
                const char *said = std::getenv("LHAPDF_DATA_PATH");
                if (said == 0 || *said == 0) said = std::getenv("LHAPATH");
                if (said != 0) {
                    std::string all(said), one;
                    std::istringstream in(all);
                    while (std::getline(in, one, ':'))
                        if (!one.empty()) out.push_back(one);
                }
                static const char *guesses[4] = {"/usr/share/LHAPDF", "/usr/local/share/LHAPDF",
                                                 "/usr/share/lhapdf/PDFsets",
                                                 "/usr/local/share/lhapdf/PDFsets"};
                for (int i = 0; i < 4; ++i)
                    if (isDirectory(guesses[i])
                        && std::find(out.begin(), out.end(), guesses[i]) == out.end())
                        out.push_back(guesses[i]);
                return out;
            }

            // The folder a name stands for, or empty when no folder holds it. A folder
            // only counts when member zero is in it: one left behind by an interrupted
            // download has the right name and nothing to read.
            static std::string find(const std::string &name) {
                if (name.empty()) return "";
                if (holdsMemberZero(name)) return name;
                const std::vector<std::string> where = paths();
                for (std::size_t i = 0; i < where.size(); ++i) {
                    const std::string at = where[i] + "/" + name;
                    if (holdsMemberZero(at)) return at;
                }
                return "";
            }

            // Opens a set by name rather than by path.
            static Pdf open(const std::string &name, int member = 0,
                            Pdf::Accuracy how = Pdf::Lhapdf,
                            Pdf::Interpolation rule = Pdf::LogBicubic,
                            Pdf::Extrapolation beyond = Pdf::Continuation) {
                const std::string at = find(name);
                if (at.empty())
                    throw std::runtime_error("No set named " + name + " in any declared folder.");
                return Pdf::openSet(at, member, how, rule, beyond);
            }

            // Every set already on disk.
            static std::vector<std::string> installed() {
                std::vector<std::string> out;
                const std::vector<std::string> where = paths();
                for (std::size_t i = 0; i < where.size(); ++i)
                    for (const std::string &one : entriesOf(where[i]))
                        if (holdsMemberZero(where[i] + "/" + one)
                            && std::find(out.begin(), out.end(), one) == out.end())
                            out.push_back(one);
                std::sort(out.begin(), out.end());
                return out;
            }

            // The published sets, read from pdfsets.index: the number a set was given,
            // its name, and the version of its data files.
            static const std::map<std::string, Entry> &index() {
                static std::map<std::string, Entry> byName;
                static bool read = false;
                if (!read) {
                    read = true;
                    const std::string file = indexFile();
                    if (!file.empty()) {
                        std::ifstream in(file.c_str());
                        std::string line;
                        while (std::getline(in, line)) {
                            std::istringstream words(line);
                            Entry one;
                            one.version = 1;
                            if (words >> one.id >> one.name) {
                                words >> one.version;
                                byName[one.name] = one;
                            }
                        }
                    }
                }
                return byName;
            }

            static std::string indexFile() {
                const std::vector<std::string> where = paths();
                for (std::size_t i = 0; i < where.size(); ++i) {
                    const std::string at = where[i] + "/pdfsets.index";
                    std::ifstream in(at.c_str());
                    if (in) return at;
                }
                return "";
            }

            // The set an old numeric identifier stands for.
            static bool describe(int id, Entry &out) {
                const std::map<std::string, Entry> &all = index();
                for (std::map<std::string, Entry>::const_iterator it = all.begin();
                     it != all.end(); ++it)
                    if (it->second.id == id) { out = it->second; return true; }
                return false;
            }

            static std::vector<Entry> search(const std::string &text) {
                std::string wanted = lower(text);
                std::vector<Entry> out;
                const std::map<std::string, Entry> &all = index();
                for (std::map<std::string, Entry>::const_iterator it = all.begin();
                     it != all.end(); ++it)
                    if (lower(it->second.name).find(wanted) != std::string::npos)
                        out.push_back(it->second);
                return out;
            }

            // Where sets are fetched from. A laboratory usually keeps a mirror, and a
            // machine behind a proxy often cannot reach the one at CERN at all.
            static std::string &downloadBase() {
                static std::string base = "https://lhapdfsets.web.cern.ch/lhapdfsets/current/";
                return base;
            }

            /**
             * Fetches a set and unpacks it.
             *
             * The download itself is handed to curl or wget, because a header that
             * spoke HTTPS on its own would have to carry a TLS library with it, and
             * every machine that can reach the server already has one of the two. The
             * unpacking is done here, entry by entry, refusing any name that would be
             * written outside the destination.
             */
            static bool fetch(const std::string &name, const std::string &into,
                              std::string &saidWhat) {
                if (name.empty()) { saidWhat = "No set named."; return false; }
                if (holdsMemberZero(into + "/" + name)) {
                    saidWhat = name + " is already in " + into;
                    return true;
                }
                const std::string tool = downloader();
                if (tool.empty()) {
                    saidWhat = "Neither curl nor wget is on the path, so the set cannot be "
                               "downloaded from here. Fetch " + downloadBase() + name
                             + ".tar.gz by other means and unpack it into " + into + ".";
                    return false;
                }
                const std::string archive = into + "/." + name + ".tar.gz.part";
                std::ostringstream command;
                if (tool == "curl") {
                    command << "curl -fsSL -o '" << archive << "' '"
                            << downloadBase() << name << ".tar.gz'";
                } else {
                    command << "wget -q -O '" << archive << "' '"
                            << downloadBase() << name << ".tar.gz'";
                }
                if (std::system(command.str().c_str()) != 0) {
                    std::remove(archive.c_str());
                    saidWhat = "Could not download " + downloadBase() + name + ".tar.gz";
                    return false;
                }
                int written = 0;
                try {
                    written = untar(archive, into);
                } catch (const std::exception &bad) {
                    std::remove(archive.c_str());
                    saidWhat = bad.what();
                    return false;
                }
                std::remove(archive.c_str());
                if (!holdsMemberZero(into + "/" + name)) {
                    saidWhat = "The archive unpacked but " + name + "_0000.dat is not in it.";
                    return false;
                }
                std::ostringstream say;
                say << name << " unpacked into " << into << " (" << written << " files)";
                saidWhat = say.str();
                return true;
            }

            /** Unpacks a gzipped tar that is already on disk. */
            static int untar(const std::string &archive, const std::string &into);

        private:
            static std::string lower(const std::string &text) {
                std::string out;
                for (std::size_t i = 0; i < text.size(); ++i)
                    out += char(std::tolower((unsigned char) text[i]));
                return out;
            }

            static std::string downloader() {
                if (std::system("command -v curl >/dev/null 2>&1") == 0) return "curl";
                if (std::system("command -v wget >/dev/null 2>&1") == 0) return "wget";
                return "";
            }

            static bool holdsMemberZero(const std::string &folder) {
                std::string stem = folder;
                while (!stem.empty() && (stem[stem.size()-1] == '/' || stem[stem.size()-1] == '\\\\'))
                    stem.erase(stem.size()-1);
                const std::size_t cut = stem.find_last_of("/\\\\");
                const std::string name = (cut == std::string::npos) ? stem : stem.substr(cut+1);
                std::ifstream in((stem + "/" + name + "_0000.dat").c_str());
                return bool(in);
            }

            static bool isDirectory(const std::string &path);
            static std::vector<std::string> entriesOf(const std::string &folder);
        };


        // The three that need the filesystem and zlib, kept out of the class body so
        // the class reads as an interface.
        """;

    private static final String PART_3 = """
        inline bool Catalog::isDirectory(const std::string &path) {
            struct stat info;
            return stat(path.c_str(), &info) == 0 && S_ISDIR(info.st_mode);
        }

        inline std::vector<std::string> Catalog::entriesOf(const std::string &folder) {
            std::vector<std::string> out;
            DIR *dir = opendir(folder.c_str());
            if (dir == 0) return out;
            while (struct dirent *one = readdir(dir)) {
                const std::string name = one->d_name;
                if (name != "." && name != "..") out.push_back(name);
            }
            closedir(dir);
            return out;
        }

        /**
         * Unpacks a gzipped tar that is already on disk.
         *
         * The names are read first and the archive refused outright if any of them
         * would be written outside the destination, because an archive is data from
         * elsewhere and a path in it is not a permission. Only then is tar asked to do
         * the work: decompressing here would mean carrying a compression library into
         * every pipeline that includes this header, including the ones that never
         * download anything.
         */
        inline int Catalog::untar(const std::string &archive, const std::string &into) {
            const std::string listing = into + "/.sphere-listing";
            const std::string list = "tar tzf '" + archive + "' > '" + listing + "' 2>/dev/null";
            if (std::system(list.c_str()) != 0) {
                std::remove(listing.c_str());
                throw std::runtime_error("Cannot read the archive " + archive);
            }
            int count = 0;
            {
                std::ifstream in(listing.c_str());
                std::string name;
                while (std::getline(in, name)) {
                    if (name.empty()) continue;
                    if (name[0] == '/' || name[0] == '~'
                        || name.find("..") != std::string::npos) {
                        std::remove(listing.c_str());
                        throw std::runtime_error("The archive holds an entry that would be "
                                                 "written outside " + into + ": " + name);
                    }
                    if (name[name.size() - 1] != '/') ++count;
                }
            }
            std::remove(listing.c_str());
            const std::string unpack = "tar xzf '" + archive + "' -C '" + into + "'";
            if (std::system(unpack.c_str()) != 0)
                throw std::runtime_error("Could not unpack " + archive);
            return count;
        }

        // The keys an info file uses, read the way LHAPDF's factory reads them.
        inline bool AlphaS::fromInfo(const std::map<std::string, std::string> &info, AlphaS &out) {
            std::map<std::string, std::string>::const_iterator it = info.find("AlphaS_Type");
            if (it == info.end() || it->second.empty()) return false;
            std::string said;
            for (std::size_t i = 0; i < it->second.size(); ++i)
                said += char(std::tolower((unsigned char) it->second[i]));
            if (said == "analytic") out.type_ = Analytic;
            else if (said == "ode") out.type_ = Ode;
            else if (said == "ipol") out.type_ = Ipol;
            else return false;

            out.order_ = (int) infoNumber(info, "AlphaS_OrderQCD", 4);
            static const char *named[6] = {"Down", "Up", "Strange", "Charm", "Bottom", "Top"};
            if (allPresent(info, "AlphaS_Threshold", named))
                for (int q = 1; q <= 6; ++q)
                    out.thresholds_[q] = infoNumber(info, std::string("AlphaS_Threshold") + named[q-1], 0);
            else if (allPresent(info, "Threshold", named))
                for (int q = 1; q <= 6; ++q)
                    out.thresholds_[q] = infoNumber(info, std::string("Threshold") + named[q-1], 0);
            if (allPresent(info, "AlphaS_M", named))
                for (int q = 1; q <= 6; ++q)
                    out.masses_[q] = infoNumber(info, std::string("AlphaS_M") + named[q-1], 0);
            else if (allPresent(info, "M", named))
                for (int q = 1; q <= 6; ++q)
                    out.masses_[q] = infoNumber(info, std::string("M") + named[q-1], 0);

            std::string fscheme = infoText(info, "AlphaS_FlavorScheme",
                                           infoText(info, "FlavorScheme", "variable"));
            for (std::size_t i = 0; i < fscheme.size(); ++i)
                fscheme[i] = char(std::tolower((unsigned char) fscheme[i]));
            const int count = (int) infoNumber(info, "AlphaS_NumFlavors",
                                               infoNumber(info, "NumFlavors", 5));
            out.scheme_ = (fscheme == "fixed") ? Fixed : Variable;
            out.fixflav_ = count;

            out.mz_ = infoNumber(info, "MZ", 91.1876);
            out.asmz_ = infoNumber(info, "AlphaS_MZ", 0.118);
            if (info.count("AlphaS_Reference") && info.count("AlphaS_MassReference")) {
                out.asref_ = infoNumber(info, "AlphaS_Reference", 0);
                out.mref_ = infoNumber(info, "AlphaS_MassReference", 0);
                out.customref_ = true;
            }

            if (out.type_ == Analytic) {
                for (int nf = 3; nf <= 5; ++nf) {
                    std::ostringstream key; key << "AlphaS_Lambda" << nf;
                    if (info.count(key.str())) out.lambdas_[nf] = infoNumber(info, key.str(), 0);
                }
                if (out.lambdas_.empty()) return false;
                out.countFlavors();
            } else if (out.type_ == Ipol) {
                std::vector<double> qs = infoList(info, "AlphaS_Qs");
                std::vector<double> vs = infoList(info, "AlphaS_Vals");
                if (qs.empty() || qs.size() != vs.size()) return false;
                out.q2s_.resize(qs.size());
                for (std::size_t i = 0; i < qs.size(); ++i) out.q2s_[i] = qs[i] * qs[i];
                out.values_ = vs;
            } else {
                std::vector<double> qs = infoList(info, "AlphaS_Qs");
                if (!qs.empty()) {
                    out.q2s_.resize(qs.size());
                    for (std::size_t i = 0; i < qs.size(); ++i) out.q2s_[i] = qs[i] * qs[i];
                }
                if (out.masses_.empty()) return false;
            }
            return true;
        }

        }  // namespace sphere

        #endif

        """;

    /**
     * The pieces joined.
     *
     * Written as a call rather than as one expression: adding constants
     * together folds them back into a single constant at compile time, which
     * is the limit this was split to stay under.
     */
    private static String joined() {
        StringBuilder out = new StringBuilder(70000);
        out.append(PART_1);
        out.append(PART_2);
        out.append(PART_3);
        return out.toString().stripTrailing() + "\n";
    }

    private static final String SOURCE = joined();
}

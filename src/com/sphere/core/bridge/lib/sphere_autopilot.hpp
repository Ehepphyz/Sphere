// sphere_autopilot.hpp -- Sphere's Minuit2 autopilot on ROOT's own Minuit2, for ROOT macros,
// compiled C++ programs and PyROOT.
//
// This is not another Minuit2: ROOT's Minuit2 does every minimization. What this adds is what
// an expert does around it, decided from the cause of each failure and said in words, the same
// as Sphere's console (:minuit2 minimize --auto, :minuit2 fit):
//
//   - the call limit: Migrad again from where it stopped, with more calls;
//   - values that are not finite: the limits of the function's domain inferred from where it
//     failed (a log, a sqrt, a width), and Migrad inside them;
//   - an EDM above the maximum: the noise of the function measured (More and Wild, SIAM J. Sci.
//     Comput. 33, 2011), Minuit's precision set to it, then the tolerance raised to it;
//   - otherwise (as iminuit): strategy 2, Simplex then Migrad from the last point; Simplex from
//     the start;
//   - a covariance Hesse had to force positive-definite: the curvature measured along every
//     direction; a direction that goes down is a saddle point, left by a line search along it;
//   - several minima: starts chosen by multi-level single linkage (Rinnooy Kan and Timmer) on
//     quasi-random points, and a catalogue of the minima with how many starts reached each;
//   - Minos only where it is needed: a parabolicity test of two values per parameter along the
//     linearized profile tells which intervals are not Hesse's; at any confidence level;
//   - every value computed once (a cache on the 64 bits of the parameters);
//   - a diagnosis (limits, correlations, flat directions, noise budget, minima) and a verdict.
//
//   #include "sphere_autopilot.hpp"          // ROOT_INCLUDE_PATH and CPLUS_INCLUDE_PATH hold the bridge
//   ROOT::Minuit2::MnUserParameters p;
//   p.Add("a", 1., 0.1);  p.Add("b", 2., 0.2);
//   sphere::autopilot::Options o;  o.multistart = 10;  o.cl = 0.95;
//   auto r = sphere::autopilot::minimize(myFcn, p, o);     // myFcn: any ROOT::Minuit2::FCNBase
//   std::cout << r.text();
//   sphere::autopilot::publish(r, "myfit");                 // Sphere's console, and every engine
//
// Under ROOT (Cling, or SPHERE_AUTOPILOT_ROOT defined):
//   auto r = sphere::autopilot::fit(h, f);        // a TH1 and a TF1; f gets the parameters and errors
//   auto r = sphere::autopilot::fit(h, f, "L");   // Poisson likelihood (Baker-Cousins)
//
// Standard C++17 and ROOT's Minuit2 headers; nothing to link but Minuit2.

#ifndef SPHERE_AUTOPILOT_HPP
#define SPHERE_AUTOPILOT_HPP

#include "Minuit2/FCNBase.h"
#include "Minuit2/FunctionMinimum.h"
#include "Minuit2/MinosError.h"
#include "Minuit2/MnHesse.h"
#include "Minuit2/MnMachinePrecision.h"
#include "Minuit2/MnMigrad.h"
#include "Minuit2/MnMinos.h"
#include "Minuit2/MnSimplex.h"
#include "Minuit2/MnStrategy.h"
#include "Minuit2/MnUserCovariance.h"
#include "Minuit2/MnUserParameterState.h"
#include "Minuit2/MnUserParameters.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#if defined(__CLING__) || defined(SPHERE_AUTOPILOT_ROOT)
#  define SPHERE_AUTOPILOT_HAS_ROOT 1
#  include "TF1.h"
#  include "TFormula.h"
#  include "TH1.h"
#endif

#if defined(__has_include)
#  if __has_include("sphere_spx.hpp")
#    include "sphere_spx.hpp"
#    define SPHERE_AUTOPILOT_HAS_SPX 1
#  endif
#endif

namespace sphere {
namespace autopilot {

namespace M2 = ROOT::Minuit2;

constexpr double kPi = 3.14159265358979323846;

inline std::string fmt(const char *f, double a)
{
   char b[64];
   std::snprintf(b, sizeof b, f, a);
   return b;
}

// ---------------------------------------------------------------------------------------------
// Confidence levels: the chi-square quantiles (Up for Minos at 95%, a 2D contour at 68%...)
// ---------------------------------------------------------------------------------------------

namespace stats {

inline double lnGamma(double x)
{
   static const double c[] = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
                              -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
                              1.5056327351493116e-7};
   if (x < 0.5)
      return std::log(kPi / std::fabs(std::sin(kPi * x))) - lnGamma(1 - x);
   const double z = x - 1, t = z + 7.5;
   double a = c[0];
   for (int i = 1; i < 9; i++)
      a += c[i] / (z + i);
   return 0.5 * std::log(2 * kPi) + (z + 0.5) * std::log(t) - t + std::log(a);
}

/** {P(a, x), Q(a, x)}, the regularized incomplete gamma functions, each where it is accurate. */
inline std::pair<double, double> gammaPQ(double a, double x)
{
   if (x <= 0)
      return {0, 1};
   if (std::isinf(x))
      return {1, 0};
   const double front = std::exp(-x + a * std::log(x) - lnGamma(a));
   if (x < a + 1) {
      double ap = a, del = 1 / a, sum = del;
      for (int n = 0; n < 1000 && std::fabs(del) >= std::fabs(sum) * 1e-17; n++) {
         ap += 1;
         del *= x / ap;
         sum += del;
      }
      return {sum * front, 1 - sum * front};
   }
   const double tiny = 1e-300;
   double b = x + 1 - a, c = 1 / tiny, d = 1 / b, h = d;
   for (int i = 1; i < 1000; i++) {
      const double an = -i * (i - a);
      b += 2;
      d = an * d + b;
      if (std::fabs(d) < tiny)
         d = tiny;
      c = b + an / c;
      if (std::fabs(c) < tiny)
         c = tiny;
      d = 1 / d;
      const double del = d * c;
      h *= del;
      if (std::fabs(del - 1) < 1e-17)
         break;
   }
   return {1 - front * h, front * h};
}

inline double chi2Cdf(double x, int k) { return gammaPQ(0.5 * k, 0.5 * x).first; }

inline double chi2Pdf(double x, int k)
{
   if (x <= 0)
      return k == 2 ? 0.5 : k < 2 ? std::numeric_limits<double>::infinity() : 0;
   return std::exp((0.5 * k - 1) * std::log(x) - 0.5 * x - 0.5 * k * std::log(2.) - lnGamma(0.5 * k));
}

/** The x with P(chi2_k <= x) = p: exact for k = 2, Newton kept inside a bracket otherwise. */
inline double chi2Quantile(double p, int k)
{
   if (!(p > 0))
      return 0;
   if (p >= 1)
      return std::numeric_limits<double>::infinity();
   if (k == 2)
      return -2 * std::log1p(-p);
   double lo = 0, hi = std::max(1, k);
   while (chi2Cdf(hi, k) < p) {
      lo = hi;
      hi *= 2;
   }
   double x = 0.5 * (lo + hi);
   for (int it = 0; it < 200; it++) {
      const auto pq = gammaPQ(0.5 * k, 0.5 * x);
      const double diff = p < 0.5 ? pq.first - p : (1 - p) - pq.second;
      if (diff == 0)
         return x;
      (diff < 0 ? lo : hi) = x;
      double next = x - diff / chi2Pdf(x, k);
      if (!(next > lo && next < hi))
         next = 0.5 * (lo + hi);
      if (std::fabs(next - x) <= 1e-15 * x || hi - lo <= 1e-15 * hi)
         return next;
      x = next;
   }
   return x;
}

/** The probability inside +-n standard deviations of a Gaussian. */
inline double sigmaToProbability(double n) { return chi2Cdf(n * n, 1); }

/** The factor of Up for a level cl in ndim parameters: cl in (0, 1) a probability, cl >= 1 sigmas, 0 one sigma. */
inline double upFactor(double cl, int ndim)
{
   if (!(cl > 0))
      cl = 1;
   if (cl >= 1)
      return ndim == 1 ? cl * cl : chi2Quantile(sigmaToProbability(cl), ndim);
   return chi2Quantile(cl, ndim);
}

inline std::string describe(double cl)
{
   if (!(cl > 0))
      cl = 1;
   char b[64];
   if (cl >= 1)
      std::snprintf(b, sizeof b, "%.4g%% (%g sigma)", 100 * sigmaToProbability(cl), cl);
   else
      std::snprintf(b, sizeof b, "%.4g%%", 100 * cl);
   return b;
}

} // namespace stats

// ---------------------------------------------------------------------------------------------
// A value with its errors as the PDG rounds them
// ---------------------------------------------------------------------------------------------

namespace pdg {

/** The decimal place (10^place) of the last digit kept: 100-354 two digits, 355-949 one, 950-999 up to 1000. */
inline int place(double lead)
{
   int n = static_cast<int>(std::floor(std::log10(lead)));
   long three = std::lround(lead / std::pow(10., n - 2));
   if (three >= 1000) {
      n++;
      three = 100;
   } else if (three < 100) {
      n--;
      three = std::lround(lead / std::pow(10., n - 2));
   }
   return three <= 354 ? n - 1 : n;
}

inline std::string digits(double x, int d, int exp)
{
   const double units = std::nearbyint(x / std::pow(10., d)); // x in units of the last digit
   const int decimals = std::max(0, exp - d);
   char b[64];
   std::snprintf(b, sizeof b, "%.*f", decimals, units * std::pow(10., d - exp));
   std::string s = b;
   if (s[0] == '-' && s.find_first_not_of("-0.") == std::string::npos)
      s.erase(0, 1);
   return s;
}

inline std::string render(double value, const std::vector<double> &errors, bool asym)
{
   double lead = std::numeric_limits<double>::infinity();
   for (double e : errors)
      if (e > 0 && std::isfinite(e) && e < lead)
         lead = e;
   const char *sep[] = {" -", " +"};
   std::string s;
   if (!std::isfinite(value) || std::isinf(lead)) {
      s = fmt("%.6g", value);
      for (size_t i = 0; i < errors.size(); i++)
         s += (asym && i < 2 ? sep[i] : " +- ") + fmt("%.3g", errors[i]);
      return s;
   }
   const int d = place(lead);
   const int m = static_cast<int>(std::floor(std::log10(std::max(std::fabs(value), lead))));
   const int exp = m > 4 || m < -3 || d > 3 ? 3 * static_cast<int>(std::floor(m / 3.)) : 0;
   s = digits(value, d, exp);
   for (size_t i = 0; i < errors.size(); i++)
      s += (asym && i < 2 ? sep[i] : " +- ") + digits(errors[i], d, exp);
   if (exp != 0)
      s = "(" + s + ")e" + std::to_string(exp);
   return s;
}

inline std::string format(double value, double error) { return render(value, {error}, false); }
inline std::string asymmetric(double value, double lower, double upper)
{
   return render(value, {std::fabs(lower), std::fabs(upper)}, true);
}

} // namespace pdg

// ---------------------------------------------------------------------------------------------
// The function as the autopilot sees it
// ---------------------------------------------------------------------------------------------

/** A limit the function seems to need: below (lower) or above the bound it is not finite. */
struct DomainHint {
   unsigned int par;
   std::string name;
   bool lower;
   double bound;
   std::string text() const { return name + (lower ? " >= " : " <= ") + fmt("%.6g", bound); }
};

/**
 * Every value asked passes here: computed once per 64-bit point (Minuit2's numbers unchanged,
 * an expensive function not called again), the values that are not finite counted with where
 * they happened, the lowest value kept, Up changeable for Minos at another level.
 */
class SmartFCN : public M2::FCNBase {
public:
   explicit SmartFCN(const M2::FCNBase &f) : fFcn(f) {}

   double operator()(std::vector<double> const &v) const override
   {
      std::string key(reinterpret_cast<const char *>(v.data()), v.size() * sizeof(double));
      {
         std::lock_guard<std::mutex> lock(fMutex);
         ++fRequests;
         const auto it = fCache.find(key);
         if (it != fCache.end())
            return it->second;
      }
      const double f = fFcn(v);
      std::lock_guard<std::mutex> lock(fMutex);
      ++fEvaluations;
      if (fCache.size() > (1u << 16))
         fCache.clear();
      fCache.emplace(std::move(key), f);
      Record(v, f);
      return f;
   }

   double Up() const override { return std::isnan(fUp) ? fFcn.Up() : fUp; }
   void SetErrorDef(double up) override { fUp = up; }
   bool HasGradient() const override { return fFcn.HasGradient(); }
   std::vector<double> Gradient(std::vector<double> const &v) const override { return fFcn.Gradient(v); }
   M2::GradientParameterSpace gradParameterSpace() const override { return fFcn.gradParameterSpace(); }
   std::vector<double> G2(std::vector<double> const &v) const override { return fFcn.G2(v); }
   std::vector<double> Hessian(std::vector<double> const &v) const override { return fFcn.Hessian(v); }
   bool HasHessian() const override { return fFcn.HasHessian(); }
   bool HasG2() const override { return fFcn.HasG2(); }

   long Requests() const { return fRequests; }
   long Evaluations() const { return fEvaluations; }
   long NonFinite() const { return fNonFinite; }
   double Lowest() const { return fLowest; }

   /** Limits that separate, parameter by parameter, where the function was not finite from where it was. */
   std::vector<DomainHint> DomainHints(const M2::MnUserParameterState &st) const
   {
      std::lock_guard<std::mutex> lock(fMutex);
      std::vector<DomainHint> out;
      if (fNonFinite == 0)
         return out;
      for (unsigned int i = 0; i < std::min<size_t>(fFinLo.size(), st.MinuitParameters().size()); i++) {
         const auto &p = st.Parameter(i);
         if (p.IsFixed() || p.IsConst() || fBadLo[i] > fBadHi[i] || fFinLo[i] > fFinHi[i])
            continue;
         if (fBadHi[i] < fFinLo[i] && !(p.HasLowerLimit() && p.LowerLimit() >= fFinLo[i]))
            out.push_back({i, p.GetName(), true, fBadHi[i] < 0 && fFinLo[i] >= 0 ? 0. : fFinLo[i]});
         else if (fBadLo[i] > fFinHi[i] && !(p.HasUpperLimit() && p.UpperLimit() <= fFinHi[i]))
            out.push_back({i, p.GetName(), false, fBadLo[i] > 0 && fFinHi[i] <= 0 ? 0. : fFinHi[i]});
      }
      return out;
   }

private:
   void Record(std::vector<double> const &v, double f) const
   {
      const double inf = std::numeric_limits<double>::infinity();
      if (fFinLo.size() != v.size()) {
         fFinLo.assign(v.size(), inf);
         fBadLo.assign(v.size(), inf);
         fFinHi.assign(v.size(), -inf);
         fBadHi.assign(v.size(), -inf);
      }
      const bool finite = std::isfinite(f);
      if (!finite)
         ++fNonFinite;
      else if (f < fLowest)
         fLowest = f;
      auto &lo = finite ? fFinLo : fBadLo;
      auto &hi = finite ? fFinHi : fBadHi;
      for (size_t i = 0; i < v.size(); i++) {
         lo[i] = std::min(lo[i], v[i]);
         hi[i] = std::max(hi[i], v[i]);
      }
   }

   const M2::FCNBase &fFcn;
   double fUp = std::numeric_limits<double>::quiet_NaN();
   mutable std::mutex fMutex;
   mutable std::unordered_map<std::string, double> fCache;
   mutable long fRequests = 0, fEvaluations = 0, fNonFinite = 0;
   mutable double fLowest = std::numeric_limits<double>::infinity();
   mutable std::vector<double> fFinLo, fFinHi, fBadLo, fBadHi;
};

// ---------------------------------------------------------------------------------------------
// The noise of the function (More and Wild's ECnoise)
// ---------------------------------------------------------------------------------------------

struct Noise {
   bool found = false;
   double sigma = 0, f = 0, h = 0;
   int order = 0, calls = 0;

   /** The precision for Minuit: its steps then rise a hundred times above the noise near a minimum at fmin. */
   double PrecisionForMinuit(double up, double fmin) const
   {
      const double base = M2::MnMachinePrecision().Eps();
      if (!found || !(sigma > 0))
         return base;
      const double r = 6.25 * sigma / ((std::isfinite(fmin) ? std::fabs(fmin) : std::fabs(f)) + up);
      return std::min(1e-2, std::max(base, r * r));
   }

   std::string text() const
   {
      if (!found)
         return "no noise found above the last bits (the function is smooth to double precision)";
      char b[200];
      std::snprintf(b, sizeof b, "noise sigma = %.3g on F = %.10g (relative %.2g), differences of order %d", sigma, f,
                    sigma / std::max(std::fabs(f), 1e-300), order);
      return b;
   }
};

/** ECnoise on equally spaced values: 1 found (sigma, order), 2 the step is too small, 3 too large. */
inline int ecnoise(std::vector<double> fv, double &sigma, int &order)
{
   const size_t nf = fv.size();
   double fmin = fv[0], fmax = fv[0];
   for (double v : fv) {
      if (!std::isfinite(v))
         return 3;
      fmin = std::min(fmin, v);
      fmax = std::max(fmax, v);
   }
   if ((fmax - fmin) / std::max(std::max(std::fabs(fmax), std::fabs(fmin)), 1e-300) > 0.1)
      return 3;
   std::vector<double> level(nf - 1);
   std::vector<bool> change(nf - 1);
   double gamma = 1;
   for (size_t j = 1; j < nf; j++) {
      for (size_t i = 0; i < nf - j; i++)
         fv[i] = fv[i + 1] - fv[i];
      if (j == 1) {
         size_t zeros = 0;
         for (size_t i = 0; i < nf - 1; i++)
            zeros += fv[i] == 0;
         if (zeros >= nf / 2)
            return 2;
      }
      gamma *= 0.5 * j / (2. * j - 1.);
      double s = 0, emin = fv[0], emax = fv[0];
      for (size_t i = 0; i < nf - j; i++) {
         s += fv[i] * fv[i];
         emin = std::min(emin, fv[i]);
         emax = std::max(emax, fv[i]);
      }
      level[j - 1] = std::sqrt(gamma * s / (nf - j));
      change[j - 1] = emin * emax < 0;
   }
   for (size_t k = 0; k + 3 <= nf - 1; k++) {
      const double lo = std::min({level[k], level[k + 1], level[k + 2]});
      const double hi = std::max({level[k], level[k + 1], level[k + 2]});
      if (hi <= 4 * lo && change[k]) {
         sigma = level[k];
         order = static_cast<int>(k) + 1;
         return 1;
      }
   }
   return 3;
}

/** The noise at the values of st, along a fixed pseudo-random direction scaled by the errors. */
inline Noise EstimateNoise(const M2::FCNBase &fcn, const M2::MnUserParameterState &st)
{
   Noise out;
   const size_t n = st.MinuitParameters().size();
   const std::vector<double> x0 = st.Params();
   std::vector<double> dir(n, 0.);
   std::uint64_t s = 0x5EEDull;
   auto uniform = [&s]() {
      s = s * 6364136223846793005ull + 1442695040888963407ull;
      return static_cast<double>(s >> 11) * 0x1.0p-53;
   };
   double norm = 0;
   for (size_t i = 0; i < n; i++) {
      const auto &p = st.Parameter(i);
      if (p.IsFixed() || p.IsConst())
         continue;
      if ((p.HasLowerLimit() && x0[i] <= p.LowerLimit()) || (p.HasUpperLimit() && x0[i] >= p.UpperLimit()))
         continue;
      const double u = (uniform() < 0.5 ? -1 : 1) * (0.5 + 0.5 * uniform());
      const double scale = p.Error() > 0 && std::isfinite(p.Error()) ? p.Error() : 1e-3 * std::max(std::fabs(x0[i]), 1.);
      dir[i] = u * scale;
      norm += u * u;
   }
   if (norm == 0)
      return out;
   norm = std::sqrt(norm);
   double hmax = std::numeric_limits<double>::infinity();
   for (size_t i = 0; i < n; i++) {
      dir[i] /= norm;
      if (dir[i] == 0)
         continue;
      const auto &p = st.Parameter(i);
      if (p.HasUpperLimit())
         hmax = std::min(hmax, (p.UpperLimit() - x0[i]) / std::fabs(dir[i]) / 4);
      if (p.HasLowerLimit())
         hmax = std::min(hmax, (x0[i] - p.LowerLimit()) / std::fabs(dir[i]) / 4);
   }
   const double f0 = fcn(x0);
   out.f = f0;
   out.calls = 1;
   double h = 1e-4;
   for (int attempt = 0; attempt < 8; attempt++) {
      const double hh = std::min(h, 0.999 * hmax);
      std::vector<double> fv(9);
      for (int k = 0; k < 9; k++) {
         if (k == 4) {
            fv[k] = f0;
            continue;
         }
         std::vector<double> x = x0;
         for (size_t i = 0; i < n; i++)
            x[i] += (k - 4) * hh * dir[i];
         fv[k] = fcn(x);
         out.calls++;
      }
      double sigma = 0;
      int order = 0;
      const int r = ecnoise(fv, sigma, order);
      if (r == 1) {
         out.found = true;
         out.sigma = sigma;
         out.order = order;
         out.h = hh;
         return out;
      }
      if (r == 2) {
         if (hh < h)
            break;
         h = hh * 100;
      } else {
         h = hh / 100;
      }
   }
   return out;
}

// ---------------------------------------------------------------------------------------------
// Eigenvalues of a small symmetric matrix (cyclic Jacobi), increasing
// ---------------------------------------------------------------------------------------------

struct Eigen {
   std::vector<double> values;
   std::vector<std::vector<double>> vectors; // vectors[i][k]: component i of eigenvector k

   explicit Eigen(std::vector<std::vector<double>> a)
   {
      const size_t n = a.size();
      std::vector<std::vector<double>> v(n, std::vector<double>(n, 0.));
      for (size_t i = 0; i < n; i++)
         v[i][i] = 1;
      double scale = 0;
      for (auto &r : a)
         for (double x : r)
            scale = std::max(scale, std::fabs(x));
      for (int sweep = 0; sweep < 100; sweep++) {
         double off = 0;
         for (size_t i = 0; i < n; i++)
            for (size_t j = i + 1; j < n; j++)
               off += a[i][j] * a[i][j];
         if (off <= 1e-30 * scale * scale)
            break;
         for (size_t p = 0; p < n; p++)
            for (size_t q = p + 1; q < n; q++) {
               if (std::fabs(a[p][q]) < 1e-300)
                  continue;
               const double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
               const double t = (theta >= 0 ? 1 : -1) / (std::fabs(theta) + std::sqrt(theta * theta + 1));
               const double c = 1 / std::sqrt(t * t + 1), s = t * c;
               for (size_t k = 0; k < n; k++) {
                  const double akp = a[k][p], akq = a[k][q];
                  a[k][p] = c * akp - s * akq;
                  a[k][q] = s * akp + c * akq;
               }
               for (size_t k = 0; k < n; k++) {
                  const double apk = a[p][k], aqk = a[q][k];
                  a[p][k] = c * apk - s * aqk;
                  a[q][k] = s * apk + c * aqk;
               }
               for (size_t k = 0; k < n; k++) {
                  const double vkp = v[k][p], vkq = v[k][q];
                  v[k][p] = c * vkp - s * vkq;
                  v[k][q] = s * vkp + c * vkq;
               }
            }
      }
      std::vector<size_t> order(n);
      for (size_t i = 0; i < n; i++)
         order[i] = i;
      std::sort(order.begin(), order.end(), [&a](size_t x, size_t y) { return a[x][x] < a[y][y]; });
      values.resize(n);
      vectors.assign(n, std::vector<double>(n));
      for (size_t k = 0; k < n; k++) {
         values[k] = a[order[k]][order[k]];
         for (size_t i = 0; i < n; i++)
            vectors[i][k] = v[i][order[k]];
      }
   }
};

// ---------------------------------------------------------------------------------------------
// What to try, and what came out
// ---------------------------------------------------------------------------------------------

struct Options {
   int strategy = 1;
   double tolerance = 0.1;
   unsigned int maxfcn = 0;  ///< 0: 200 + 100 n + 5 n^2
   int multistart = 0;       ///< spread starts (0: only when no single start works)
   bool minos = false;       ///< Minos for every parameter
   bool autoMinos = true;    ///< Minos where the parabolicity test asks for it
   int iterate = 5;          ///< Migrad attempts on a failure
   double cl = 0;            ///< the level of Minos: a probability, or sigmas (0: 1 sigma)
};

enum class Grade { Reliable = 0, Caution = 1, Unreliable = 2 };

inline const char *words(Grade g)
{
   return g == Grade::Reliable ? "reliable" : g == Grade::Caution ? "usable with care" : "not reliable";
}

struct Finding {
   Grade severity;
   std::string text, reason;
};

struct Step {
   std::string what;
   long calls;
   std::string outcome;
};

struct Basin {
   M2::FunctionMinimum min;
   int hits;
};

/** The rise at -k and +k errors along the linearized profile, in units of a parabola's; atLimit: the interval reaches a limit. */
struct Parabolicity {
   unsigned int par;
   std::string name;
   double minus, plus;
   bool atLimit;
   /// the function barely rises across the interval: Hesse's error does not describe it, Minos would search in vain
   bool Flat() const { return !atLimit && minus < 0.01 && plus < 0.01; }
   bool NeedsMinos() const { return !Flat() && (atLimit || !(std::fabs(minus - 1) <= 0.1 && std::fabs(plus - 1) <= 0.1)); }
};

struct Report {
   M2::FunctionMinimum best;
   std::vector<Step> steps;
   std::vector<Basin> basins;
   std::vector<M2::MinosError> minos;
   std::vector<std::string> diagnosis;
   Grade grade = Grade::Reliable;
   std::vector<std::string> reasons;
   long calls = 0, evaluations = 0, nonFinite = 0;
   Noise noise;
   double cl = 0;

   std::string Verdict() const
   {
      std::string s = std::string("Verdict: ") + words(grade);
      if (!reasons.empty()) {
         s += " (";
         for (size_t i = 0; i < reasons.size(); i++)
            s += (i ? "; " : "") + reasons[i];
         s += ")";
      }
      return s + ".";
   }

   /** The parameters as the PDG rounds them, Minos's interval where it ran. */
   std::string Summary() const
   {
      std::ostringstream s;
      const auto &st = best.UserState();
      for (unsigned int i = 0; i < st.MinuitParameters().size(); i++) {
         const auto &p = st.Parameter(i);
         char name[64];
         std::snprintf(name, sizeof name, "  %-12s = ", p.Name());
         s << name;
         if (p.IsFixed() || p.IsConst()) {
            s << fmt("%.10g", p.Value()) << " (fixed)\n";
            continue;
         }
         const M2::MinosError *me = nullptr;
         for (const auto &m : minos)
            if (m.Parameter() == i && m.IsValid())
               me = &m;
         if (me)
            s << pdg::asymmetric(p.Value(), me->Lower(), me->Upper()) << "  (Minos, " << stats::describe(cl) << ")";
         else
            s << pdg::format(p.Value(), p.Error());
         s << '\n';
      }
      return s.str();
   }

   std::string text() const
   {
      std::ostringstream s;
      s << "Steps:\n";
      for (const auto &st : steps) {
         char b[160];
         std::snprintf(b, sizeof b, "  %-58s %6ld calls  ", st.what.c_str(), st.calls);
         s << b << st.outcome << '\n';
      }
      if (basins.size() > 1) {
         s << "Minima found:\n";
         for (const auto &b : basins) {
            char l[96];
            std::snprintf(l, sizeof l, "  %s  FCN - best %-12.6g  starts %-4d", b.min.Fval() == best.Fval() ? "*" : " ",
                          b.min.Fval() - best.Fval(), b.hits);
            s << l << (b.min.IsValid() ? "" : " (not valid)") << '\n';
         }
      }
      s << "Function: " << calls << " values asked, " << evaluations << " computed"
        << (nonFinite ? ", " + std::to_string(nonFinite) + " not finite" : std::string()) << '\n';
      s << "Result:\n" << Summary() << "Diagnosis:\n";
      for (const auto &d : diagnosis)
         s << "  - " << d << '\n';
      s << Verdict() << '\n';
      return s.str();
   }
};

inline std::string outcome(const M2::FunctionMinimum &m)
{
   char b[200];
   if (m.IsValid()) {
      std::snprintf(b, sizeof b, "valid, FCN = %.10g, EDM = %.3g%s", m.Fval(), m.Edm(),
                    m.HasAccurateCovar()     ? ""
                    : m.HasMadePosDefCovar() ? ", covariance made pos-def"
                                             : ", covariance approximate");
      return b;
   }
   std::string why;
   if (m.HasReachedCallLimit())
      why += "call limit reached, ";
   if (m.IsAboveMaxEdm())
      why += "EDM above maximum, ";
   if (m.HesseFailed())
      why += "Hesse failed, ";
   if (!why.empty())
      why.resize(why.size() - 2);
   std::snprintf(b, sizeof b, "NOT valid (%s), FCN = %.10g", why.c_str(), m.Fval());
   return b;
}

inline double fv(const M2::FunctionMinimum &m) { return std::isfinite(m.Fval()) ? m.Fval() : HUGE_VAL; }

/** The better of two minima: a valid one, then the lower value. */
inline const M2::FunctionMinimum &better(const M2::FunctionMinimum &a, const M2::FunctionMinimum &b)
{
   if (a.IsValid() != b.IsValid())
      return a.IsValid() ? a : b;
   return fv(b) < fv(a) ? b : a;
}

/** Every variable parameter within 3 errors (the larger of the two). */
inline bool sameBasin(const M2::FunctionMinimum &a, const M2::FunctionMinimum &b)
{
   const auto &sa = a.UserState(), &sb = b.UserState();
   for (unsigned int i = 0; i < sa.MinuitParameters().size(); i++) {
      if (sa.Parameter(i).IsFixed() || sa.Parameter(i).IsConst())
         continue;
      double e = std::max(sa.Error(i), sb.Error(i));
      if (!(e > 0) || !std::isfinite(e))
         e = 1e-4 * std::max(1., std::fabs(sa.Value(i)));
      if (std::fabs(sa.Value(i) - sb.Value(i)) > 3 * e)
         return false;
   }
   return true;
}

inline double halton(int index, int base)
{
   double f = 1, r = 0;
   for (int i = index; i > 0; i /= base) {
      f /= base;
      r += f * (i % base);
   }
   return r;
}

/** The parabolicity test of every variable parameter at level cl: 2 values per parameter. */
inline std::vector<Parabolicity> parabolicity(const M2::FCNBase &fcn, const M2::FunctionMinimum &m, double cl)
{
   std::vector<Parabolicity> out;
   const auto &st = m.UserState();
   if (!m.HasValidParameters() || !st.HasCovariance() || st.CovarianceStatus() < 1)
      return out;
   const auto &c = st.Covariance();
   const unsigned int n = st.VariableParameters();
   const std::vector<double> x0 = st.Params();
   const double k = std::sqrt(stats::upFactor(cl, 1));
   for (unsigned int i = 0; i < n; i++) {
      const double sigma = std::sqrt(c(i, i));
      if (!(sigma > 0) || !std::isfinite(sigma))
         continue;
      double r[2];
      bool atLimit = false;
      for (int s = 0; s < 2; s++) {
         std::vector<double> x = x0;
         for (unsigned int j = 0; j < n; j++) {
            const unsigned int e = st.ExtOfInt(j);
            x[e] += (s == 0 ? -1 : 1) * k * c(j, i) / sigma;
            const auto &p = st.Parameter(e);
            if ((p.HasLowerLimit() && x[e] < p.LowerLimit()) || (p.HasUpperLimit() && x[e] > p.UpperLimit()))
               atLimit = true;
         }
         r[s] = atLimit ? NAN : (fcn(x) - m.Fval()) / (k * k * fcn.Up());
      }
      out.push_back({st.ExtOfInt(i), st.GetName(st.ExtOfInt(i)), r[0], r[1], atLimit});
   }
   return out;
}

/** What a minimum is worth, in sentences with their severity. */
inline std::vector<Finding> findings(const M2::FunctionMinimum &min, const std::vector<M2::MinosError> &minos)
{
   std::vector<Finding> out;
   const auto &st = min.UserState();
   char b[400];
   if (min.IsValid()) {
      std::snprintf(b, sizeof b, "The minimum is valid: FCN = %.10g, EDM = %.3g (below the tolerance), %d calls.", min.Fval(),
                    min.Edm(), min.NFcn());
      out.push_back({Grade::Reliable, b, ""});
   } else {
      out.push_back({Grade::Unreliable, "The minimum is NOT valid: " + outcome(min) +
                                           ". The values are the best point reached; their errors are not to be trusted.",
                     "the minimum is not valid"});
   }
   switch (st.CovarianceStatus()) {
   case 3: out.push_back({Grade::Reliable, "The covariance is accurate (full Hesse, positive-definite).", ""}); break;
   case 2:
      out.push_back({Grade::Caution,
                     "The covariance was forced positive-definite: the function is not a parabola near the minimum, or two "
                     "parameters are degenerate; the errors are approximate.",
                     "covariance forced positive-definite"});
      break;
   case 1:
      out.push_back({Grade::Caution, "The covariance is approximate (from Migrad's updates, not from Hesse).",
                     "covariance approximate"});
      break;
   case 0:
      out.push_back({Grade::Unreliable, "The covariance is not positive-definite: the point may be a saddle, not a minimum.",
                     "covariance not positive-definite"});
      break;
   default: out.push_back({Grade::Unreliable, "There is no covariance.", "no covariance"});
   }
   for (unsigned int i = 0; i < st.MinuitParameters().size(); i++) {
      const auto &p = st.Parameter(i);
      if (p.IsFixed() || p.IsConst() || !p.HasLimits())
         continue;
      const double e = std::max(p.Error(), 1e-300);
      for (int side = 0; side < 2; side++) {
         const bool has = side == 0 ? p.HasLowerLimit() : p.HasUpperLimit();
         const double lim = side == 0 ? p.LowerLimit() : p.UpperLimit();
         if (has && std::fabs(p.Value() - lim) < e) {
            std::snprintf(b, sizeof b,
                          "%s = %.6g is within one error of its %s limit %.6g: its error is not Gaussian; prefer Minos, or move "
                          "the limit if it is not physical.",
                          p.Name(), p.Value(), side == 0 ? "lower" : "upper", lim);
            out.push_back({Grade::Caution, b, "a parameter at its limit"});
         }
      }
   }
   if (st.HasCovariance() && st.CovarianceStatus() >= 1) {
      const unsigned int n = st.VariableParameters();
      const auto &c = st.Covariance();
      std::vector<std::vector<double>> corr(n, std::vector<double>(n));
      for (unsigned int i = 0; i < n; i++)
         for (unsigned int j = 0; j < n; j++) {
            const double d = std::sqrt(std::fabs(c(i, i) * c(j, j)));
            corr[i][j] = d > 0 ? c(i, j) / d : (i == j ? 1 : 0);
         }
      for (unsigned int i = 0; i < n; i++)
         for (unsigned int j = i + 1; j < n; j++)
            if (std::fabs(corr[i][j]) > 0.95) {
               std::snprintf(b, sizeof b,
                             "%s and %s are %.1f%% correlated: the data fix mostly a combination of them; a "
                             "reparametrization (or fixing one) makes the fit more robust.",
                             st.Name(st.ExtOfInt(i)), st.Name(st.ExtOfInt(j)), 100 * corr[i][j]);
               out.push_back({Grade::Caution, b, "strongly correlated parameters"});
            }
      if (n > 1) {
         const Eigen eig(corr);
         for (unsigned int k = 0; k < n; k++) {
            if (eig.values[k] >= 1e-4 * n)
               continue;
            std::string combo;
            for (unsigned int i = 0; i < n; i++) {
               const double w = eig.vectors[i][k];
               if (std::fabs(w) < 0.2)
                  continue;
               combo += combo.empty() ? (w < 0 ? "-" : "") : (w > 0 ? " + " : " - ");
               combo += fmt("%.2f ", std::fabs(w)) + st.Name(st.ExtOfInt(i));
            }
            std::snprintf(b, sizeof b, "A flat direction (eigenvalue %.2g of the correlation matrix): %s is not constrained by the data.",
                          eig.values[k], combo.c_str());
            out.push_back({Grade::Caution, b, "a direction the data do not constrain"});
         }
      }
   }
   for (const auto &me : minos) {
      if (!me.IsValid()) {
         out.push_back({Grade::Caution,
                        std::string("Minos could not find both errors of ") + st.Name(me.Parameter()) +
                           (me.AtLowerLimit() || me.AtUpperLimit() ? " (a limit is reached)" : "") +
                           (me.LowerNewMin() || me.UpperNewMin()
                               ? " (a lower minimum was found on the way: the fit is not at the global minimum)"
                               : ""),
                        "Minos incomplete"});
         continue;
      }
      const double lo = -me.Lower(), up = me.Upper();
      if (lo > 0 && up > 0 && std::fabs(up - lo) / (up + lo) > 0.1) {
         std::snprintf(b, sizeof b, "%s: Minos errors -%.4g +%.4g differ by %.0f%%: the function is not parabolic there; quote the Minos errors.",
                       st.Name(me.Parameter()), lo, up, 100 * std::fabs(up - lo) / (0.5 * (up + lo)));
         out.push_back({Grade::Reliable, b, ""});
      }
   }
   return out;
}

// ---------------------------------------------------------------------------------------------
// The autopilot
// ---------------------------------------------------------------------------------------------

namespace detail {

class Run {
public:
   Run(const SmartFCN &fcn, const M2::MnUserParameterState &start, const Options &opt)
      : fcn(fcn), opt(opt), start(start), tolerance(opt.tolerance)
   {
      const unsigned int n = start.VariableParameters();
      maxfcn = opt.maxfcn > 0 ? opt.maxfcn : 200 + 100 * n + 5 * n * n;
   }

   const SmartFCN &fcn;
   Options opt;
   M2::MnUserParameterState start;
   unsigned int maxfcn;
   double tolerance;
   double precision = 0, precisionAt = 0;
   std::optional<Noise> noise;
   std::vector<DomainHint> limits;
   std::vector<Step> steps;
   std::vector<std::string> notes;     // what the run learned, for the diagnosis
   std::vector<Finding> extra;
   std::string flat;

   template <class F>
   std::optional<M2::FunctionMinimum> step(const std::string &what, F body)
   {
      const long r0 = fcn.Requests();
      try {
         M2::FunctionMinimum m = body();
         steps.push_back({what, fcn.Requests() - r0, outcome(m)});
         return m;
      } catch (const std::exception &e) {
         steps.push_back({what, fcn.Requests() - r0, std::string("failed: ") + e.what()});
         return std::nullopt;
      }
   }

   /** A copy with what was learned: values and errors made finite, the domain's limits, Minuit's precision. */
   M2::MnUserParameterState prepared(const M2::MnUserParameterState &s) const
   {
      bool sane = true;
      for (const auto &p : s.MinuitParameters())
         if (!p.IsConst())
            sane = sane && std::isfinite(p.Value()) && p.Error() > 0 && std::isfinite(p.Error());
      M2::MnUserParameterState st = sane ? s : M2::MnUserParameterState(s.Parameters());
      if (!sane)
         for (unsigned int i = 0; i < st.MinuitParameters().size(); i++) {
            const auto &p = st.Parameter(i);
            if (p.IsConst())
               continue;
            if (!std::isfinite(p.Value()))
               st.SetValue(i, start.Value(i));
            if (!(st.Parameter(i).Error() > 0) || !std::isfinite(st.Parameter(i).Error()))
               st.SetError(i, start.Error(i) > 0 ? start.Error(i) : 0.1 * std::max(1., std::fabs(st.Value(i))));
         }
      for (const auto &h : limits) {
         const auto p = st.Parameter(h.par);
         if (p.IsFixed() || p.IsConst())
            continue;
         const double stepv = std::max(p.Error() > 0 && std::isfinite(p.Error()) ? p.Error() : 0., 1e-8 * std::max(1., std::fabs(h.bound)));
         if (h.lower) {
            if (p.HasUpperLimit())
               st.SetLimits(h.par, h.bound, p.UpperLimit());
            else
               st.SetLowerLimit(h.par, h.bound);
            if (!(st.Value(h.par) > h.bound))
               st.SetValue(h.par, p.HasUpperLimit() ? std::min(h.bound + stepv, 0.5 * (h.bound + p.UpperLimit())) : h.bound + stepv);
         } else {
            if (p.HasLowerLimit())
               st.SetLimits(h.par, p.LowerLimit(), h.bound);
            else
               st.SetUpperLimit(h.par, h.bound);
            if (!(st.Value(h.par) < h.bound))
               st.SetValue(h.par, p.HasLowerLimit() ? std::max(h.bound - stepv, 0.5 * (h.bound + p.LowerLimit())) : h.bound - stepv);
         }
      }
      if (precision > 0)
         st.SetPrecision(precision);
      return st;
   }

   std::optional<M2::FunctionMinimum> migrad(const std::string &what, const M2::MnUserParameterState &from, int strategy, unsigned int calls)
   {
      return step(what, [&] { return M2::MnMigrad(fcn, prepared(from), M2::MnStrategy(strategy))(calls, tolerance); });
   }

   std::optional<M2::FunctionMinimum> simplex(const std::string &what, const M2::MnUserParameterState &from, int strategy, unsigned int calls)
   {
      return step(what, [&] { return M2::MnSimplex(fcn, prepared(from), M2::MnStrategy(strategy))(calls, tolerance); });
   }

   std::optional<M2::FunctionMinimum> simplexThenMigrad(const M2::MnUserParameterState &from)
   {
      auto sx = simplex("Simplex, strategy 2, from there", from, 2, maxfcn);
      if (!sx)
         return std::nullopt;
      return migrad("Migrad, strategy 2, from Simplex's point", sx->UserState(), 2, maxfcn);
   }

   /** The remedy for each cause of failure, until the minimum is valid, the attempts are spent or nothing improves. */
   M2::FunctionMinimum repair(M2::FunctionMinimum m)
   {
      bool noiseTried = false, domainTried = !limits.empty(), toleranceRaised = false;
      int recalibrations = 0, stalled = 0;
      const double eps0 = M2::MnMachinePrecision().Eps();
      for (int attempt = 1; !m.IsValid() && attempt < opt.iterate && stalled < 2; attempt++) {
         std::optional<M2::FunctionMinimum> n;
         const auto hints = domainTried ? std::vector<DomainHint>{} : fcn.DomainHints(m.UserState());
         if (m.HasReachedCallLimit()) {
            n = migrad("Migrad again from where it stopped, 4x the calls", m.UserState(), opt.strategy, 4 * maxfcn);
         } else if (!hints.empty()) {
            domainTried = true;
            limits = hints;
            std::string t;
            for (const auto &h : hints)
               t += (t.empty() ? "" : ", ") + h.text();
            n = migrad("Migrad, strategy 2, inside the domain " + t, m.UserState(), 2, maxfcn);
         } else if (m.IsAboveMaxEdm() && !noiseTried) {
            noiseTried = true;
            const long r0 = fcn.Requests();
            noise = EstimateNoise(fcn, m.UserState());
            steps.push_back({"Noise of the function (More-Wild)", fcn.Requests() - r0,
                             noise->found ? fmt("sigma = %.3g", noise->sigma) : "below detection"});
            precisionAt = std::min(fcn.Lowest(), m.Fval());
            const double eps = noise->PrecisionForMinuit(fcn.Up(), precisionAt);
            if (eps > 4 * eps0) {
               precision = eps;
               n = migrad("Migrad, strategy 2, precision " + fmt("%.2g", eps) + " (the noise)", m.UserState(), 2, maxfcn);
            } else {
               n = simplexThenMigrad(m.UserState());
            }
         } else if (m.IsAboveMaxEdm() && precision > 0 && recalibrations < 2 &&
                    std::fabs(m.Fval()) + fcn.Up() < 0.5 * (std::fabs(precisionAt) + fcn.Up())) {
            recalibrations++;
            precisionAt = m.Fval();
            precision = std::max(precision, noise->PrecisionForMinuit(fcn.Up(), precisionAt));
            n = migrad("Migrad, strategy 2, precision " + fmt("%.2g", precision) + " (the noise at the new minimum)",
                       m.UserState(), 2, maxfcn);
         } else if (m.IsAboveMaxEdm() && noise && noise->found && !toleranceRaised &&
                    noise->sigma > 0.25 * 0.002 * tolerance * fcn.Up()) {
            toleranceRaised = true;
            tolerance = std::min(1e3 * opt.tolerance, std::max(tolerance, 4 * noise->sigma / (0.002 * fcn.Up())));
            n = migrad("Migrad, strategy 2, tolerance " + fmt("%.3g", tolerance) + " (above the noise)", m.UserState(), 2, maxfcn);
         } else {
            n = simplexThenMigrad(m.UserState());
         }
         if (!n) {
            stalled++;
            continue;
         }
         const bool improved = &better(m, *n) == &*n && fv(*n) < fv(m) - 1e-6 * fcn.Up();
         const M2::FunctionMinimum b = better(m, *n);
         stalled = improved || b.IsValid() ? 0 : stalled + 1;
         m = b;
      }
      if (!m.IsValid() && opt.iterate > 1) {
         auto sx = simplex("Simplex from the start, to leave a bad region", start, 2, 10 * maxfcn);
         if (sx) {
            auto n = migrad("Migrad, strategy 2, from Simplex's point", sx->UserState(), 2, 4 * maxfcn);
            if (n)
               m = better(m, *n);
         }
      }
      return m;
   }

   void hesse(M2::FunctionMinimum &m)
   {
      if (!m.HasValidParameters() || m.HasAccurateCovar())
         return;
      const std::vector<double> before = m.HasCovariance() ? m.UserState().Errors() : std::vector<double>{};
      for (int s = 2; s <= 3 && !m.HasAccurateCovar(); s++)
         step("Hesse, strategy " + std::to_string(s) + (s == 3 ? " (central differences)" : ""), [&] {
            const M2::MnHesse hesse{M2::MnStrategy(s)};
            hesse(fcn, m, maxfcn);
            return m;
         });
      if (!before.empty() && m.HasAccurateCovar()) {
         const auto after = m.UserState().Errors();
         double ratio = 1;
         int worst = -1;
         for (size_t i = 0; i < std::min(before.size(), after.size()); i++) {
            if (!(before[i] > 0) || !(after[i] > 0) || m.UserState().Parameter(i).IsFixed())
               continue;
            const double q = std::max(after[i] / before[i], before[i] / after[i]);
            if (q > ratio) {
               ratio = q;
               worst = static_cast<int>(i);
            }
         }
         if (worst >= 0 && ratio > 1.3) {
            char b[300];
            std::snprintf(b, sizeof b, "Migrad's approximate error of %s was %.3g, Hesse's is %.3g: quote Hesse's.",
                          m.UserState().Name(worst), before[worst], after[worst]);
            notes.push_back(b);
         }
      }
   }

   /** The curvature in units of the errors; when a direction goes down, the lowest point of a line search along it. */
   std::optional<M2::MnUserParameterState> saddle(const M2::FunctionMinimum &m, double &curvature)
   {
      const auto &st = m.UserState();
      const std::vector<double> x0 = st.Params();
      std::vector<unsigned int> idx;
      std::vector<double> scale;
      const double h = 0.1;
      flat.clear();
      for (unsigned int i = 0; i < x0.size(); i++) {
         const auto &p = st.Parameter(i);
         if (p.IsFixed() || p.IsConst())
            continue;
         const double s = p.Error() > 0 && std::isfinite(p.Error()) ? p.Error() : 1e-3 * std::max(1., std::fabs(x0[i]));
         if ((p.HasLowerLimit() && x0[i] - 2 * h * s <= p.LowerLimit()) || (p.HasUpperLimit() && x0[i] + 2 * h * s >= p.UpperLimit()))
            continue;
         idx.push_back(i);
         scale.push_back(s);
      }
      const size_t d = idx.size();
      if (d == 0 || d > 40)
         return std::nullopt;
      const long r0 = fcn.Requests();
      auto at = [&](size_t a, double ha, long b, double hb) {
         std::vector<double> x = x0;
         x[idx[a]] += ha * scale[a];
         if (b >= 0)
            x[idx[b]] += hb * scale[b];
         return fcn(x);
      };
      const double f0 = fcn(x0);
      std::vector<std::vector<double>> hess(d, std::vector<double>(d));
      for (size_t a = 0; a < d; a++)
         hess[a][a] = (at(a, h, -1, 0) - 2 * f0 + at(a, -h, -1, 0)) / (h * h);
      for (size_t a = 0; a < d; a++)
         for (size_t b = a + 1; b < d; b++)
            hess[a][b] = hess[b][a] =
               (at(a, h, b, h) - at(a, h, b, -h) - at(a, -h, b, h) + at(a, -h, b, -h)) / (4 * h * h);
      const Eigen eig(hess);
      curvature = eig.values[0];
      const double big = std::max(std::fabs(eig.values[0]), std::fabs(eig.values[d - 1]));
      const bool down = eig.values[0] < -1e-6 * big;
      std::string what = "smallest curvature " + fmt("%.3g", eig.values[0]) + ": no direction goes down";
      std::optional<M2::MnUserParameterState> below;
      if (!down && std::fabs(eig.values[0]) <= 1e-4 * big) {
         double norm = 0;
         for (size_t a = 0; a < d; a++)
            norm += std::pow(eig.vectors[a][0] * scale[a], 2);
         norm = std::sqrt(norm);
         std::string combo;
         for (size_t a = 0; a < d; a++) {
            const double w = eig.vectors[a][0] * scale[a] / norm;
            if (std::fabs(w) < 0.2)
               continue;
            combo += combo.empty() ? (w < 0 ? "-" : "") : (w > 0 ? " + " : " - ");
            combo += fmt("%.2f ", std::fabs(w)) + st.Name(idx[a]);
         }
         flat = "A flat direction of the function (curvature " + fmt("%.2g", eig.values[0] / big) + " of the largest): " + combo +
                " changes nothing; the data do not fix it, and the errors along it are meaningless.";
         what = "smallest curvature " + fmt("%.3g", eig.values[0]) + ": a flat direction";
      }
      if (down) {
         const double up = fcn.Up();
         double fbest = f0 - 1e-4 * up;
         std::vector<double> xbest;
         for (int sign = -1; sign <= 1; sign += 2) {
            double t = std::min(5., std::sqrt(2 * up / -eig.values[0])), fprev = f0;
            for (int k = 0; k < 60; k++, t *= 2) {
               std::vector<double> x = x0;
               for (size_t a = 0; a < d; a++) {
                  const auto &p = st.Parameter(idx[a]);
                  double v = x0[idx[a]] + sign * t * eig.vectors[a][0] * scale[a];
                  if (p.HasLowerLimit())
                     v = std::max(v, p.LowerLimit() + 1e-3 * scale[a]);
                  if (p.HasUpperLimit())
                     v = std::min(v, p.UpperLimit() - 1e-3 * scale[a]);
                  x[idx[a]] = v;
               }
               const double f = fcn(x);
               if (!(f < fprev))
                  break;
               fprev = f;
               if (f < fbest) {
                  fbest = f;
                  xbest = x;
               }
            }
         }
         if (!xbest.empty()) {
            below = M2::MnUserParameterState(st.Parameters());
            for (size_t a = 0; a < d; a++)
               below->SetValue(idx[a], xbest[idx[a]]);
         }
         what = "curvature " + fmt("%.3g", eig.values[0]) + ": a saddle point, " +
                (below ? "FCN " + fmt("%.4g", f0 - fbest) + " lower along it" : std::string("but no lower point along it"));
      }
      steps.push_back({"Curvature check (" + std::to_string(d) + " parameters)", fcn.Requests() - r0, what});
      return below;
   }

   /** Hesse when the covariance is not accurate; a saddle point left for the minimum below it. */
   M2::FunctionMinimum polish(M2::FunctionMinimum m)
   {
      hesse(m);
      for (int e = 0; e < 3 && m.HasValidParameters(); e++) {
         if (m.HasAccurateCovar() && m.HasPosDefCovar() && !m.HasMadePosDefCovar())
            break;
         double curvature = 0;
         const auto below = saddle(m, curvature);
         if (!below)
            break;
         auto n = migrad("Migrad, strategy 2, from below the saddle point", *below, 2, maxfcn);
         if (!n)
            break;
         M2::FunctionMinimum r = repair(*n);
         hesse(r);
         if (!r.HasValidParameters() || !(fv(r) < fv(m) - 1e-6 * fcn.Up()))
            break;
         char b[300];
         std::snprintf(b, sizeof b, "Migrad had stopped on a saddle point (FCN = %.8g, curvature %.3g along one direction): the "
                       "autopilot stepped down along it to the minimum at FCN = %.8g.", m.Fval(), curvature, r.Fval());
         notes.push_back(b);
         m = r;
      }
      return m;
   }

   /** Multi-level single linkage: Migrad from the quasi-random points of the lowest third with no lower point near. */
   std::vector<Basin> multistart(const M2::FunctionMinimum &best, int k)
   {
      std::vector<unsigned int> var;
      for (unsigned int i = 0; i < start.MinuitParameters().size(); i++)
         if (!start.Parameter(i).IsFixed() && !start.Parameter(i).IsConst())
            var.push_back(i);
      std::vector<Basin> basins{{best, 1}};
      const size_t d = var.size();
      if (d == 0)
         return basins;
      static const int primes[] = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71};
      std::vector<std::pair<double, double>> box(d);
      for (size_t a = 0; a < d; a++) {
         const auto &p = start.Parameter(var[a]);
         if (p.HasLowerLimit() && p.HasUpperLimit()) {
            box[a] = {p.LowerLimit(), p.UpperLimit()};
         } else {
            const double e = 5 * std::max(p.Error(), 1e-8);
            double lo = p.Value() - e, hi = p.Value() + e;
            if (p.HasLowerLimit())
               lo = std::max(lo, p.LowerLimit());
            if (p.HasUpperLimit())
               hi = std::min(hi, p.UpperLimit());
            box[a] = {lo, hi};
         }
      }
      auto place = [&](size_t a, double u) {
         const double w = box[a].second - box[a].first, margin = 1e-3 * w;
         return box[a].first + margin + u * (w - 2 * margin);
      };
      const int n = std::min(4000, std::max(20 * static_cast<int>(d), 10 * k));
      std::vector<std::vector<double>> u(n, std::vector<double>(d));
      std::vector<double> f(n);
      const long r0 = fcn.Requests();
      std::vector<double> x = start.Params();
      for (int j = 0; j < n; j++) {
         for (size_t a = 0; a < d; a++) {
            u[j][a] = halton(j + 1, primes[a % 20]);
            x[var[a]] = place(a, u[j][a]);
         }
         const double v = fcn(x);
         f[j] = std::isfinite(v) ? v : HUGE_VAL;
      }
      const double r =
         std::pow(std::exp(stats::lnGamma(1 + 0.5 * d)) * 2 * std::log(static_cast<double>(n)) / n, 1. / d) / std::sqrt(kPi);
      std::vector<int> order(n);
      for (int j = 0; j < n; j++)
         order[j] = j;
      std::sort(order.begin(), order.end(), [&f](int a, int b) { return f[a] < f[b]; });
      std::vector<M2::MnUserParameterState> starts;
      for (int c = 0; c < std::max(1, n / 3) && static_cast<int>(starts.size()) < k; c++) {
         const int j = order[c];
         if (std::isinf(f[j]))
            break;
         bool lowerNear = false;
         for (int i = 0; i < n && !lowerNear; i++) {
            double s = 0;
            for (size_t a = 0; a < d; a++)
               s += (u[i][a] - u[j][a]) * (u[i][a] - u[j][a]);
            lowerNear = f[i] < f[j] && std::sqrt(s) < r;
         }
         if (lowerNear)
            continue;
         M2::MnUserParameterState st(start);
         for (size_t a = 0; a < d; a++)
            st.SetValue(var[a], place(a, u[j][a]));
         starts.push_back(st);
      }
      steps.push_back({"Multistart: " + std::to_string(n) + " quasi-random points, " + std::to_string(starts.size()) + " chosen",
                       fcn.Requests() - r0, "critical distance " + fmt("%.3g", r) + " of the box"});
      for (size_t s = 0; s < starts.size(); s++) {
         auto m = migrad("Migrad from start " + std::to_string(s + 1) + "/" + std::to_string(starts.size()), starts[s],
                         opt.strategy, maxfcn);
         if (!m || !m->HasValidParameters() || !std::isfinite(m->Fval()))
            continue;
         bool home = false;
         for (auto &b : basins)
            if (sameBasin(b.min, *m)) {
               b.hits++;
               b.min = better(b.min, *m);
               home = true;
               break;
            }
         if (!home)
            basins.push_back({*m, 1});
      }
      std::stable_sort(basins.begin(), basins.end(), [](const Basin &a, const Basin &b) {
         if (a.min.IsValid() != b.min.IsValid())
            return a.min.IsValid();
         return fv(a.min) < fv(b.min);
      });
      return basins;
   }
};

inline std::vector<std::string> describeVerdict(const std::vector<Finding> &f, Grade &g)
{
   g = Grade::Reliable;
   std::vector<std::string> reasons;
   for (const auto &x : f) {
      if (static_cast<int>(x.severity) > static_cast<int>(g))
         g = x.severity;
      if (!x.reason.empty() && std::find(reasons.begin(), reasons.end(), x.reason) == reasons.end())
         reasons.push_back(x.reason);
   }
   return reasons;
}

} // namespace detail

/** Minimizes with the remedy each failure calls for, then multistart and Minos as asked, a diagnosis and a verdict. */
inline Report minimize(const M2::FCNBase &userFcn, const M2::MnUserParameterState &start, const Options &opt = Options())
{
   SmartFCN fcn(userFcn);
   detail::Run run(fcn, start, opt);
   auto first = run.migrad("Migrad, strategy " + std::to_string(opt.strategy), start, opt.strategy, run.maxfcn);
   if (!first)
      throw std::runtime_error("Migrad cannot start: " + run.steps.back().outcome);
   M2::FunctionMinimum best = run.polish(run.repair(*first));

   std::vector<Basin> basins{{best, 1}};
   const int k = opt.multistart > 0 ? opt.multistart : best.IsValid() ? 0 : 8;
   if (k > 0) {
      basins = run.multistart(best, k);
      if (basins[0].min.Fval() != best.Fval() || basins[0].min.IsValid() != best.IsValid()) {
         best = run.polish(basins[0].min);
         basins[0].min = best;
      }
   }

   std::vector<Parabolicity> parab;
   if (best.IsValid() && (opt.minos || opt.autoMinos)) {
      const long r0 = fcn.Requests();
      parab = parabolicity(fcn, best, opt.cl);
      long need = 0;
      for (const auto &q : parab)
         need += q.NeedsMinos();
      if (!parab.empty())
         run.steps.push_back({"Parabolicity test at " + stats::describe(opt.cl), fcn.Requests() - r0,
                           std::to_string(need) + " of " + std::to_string(parab.size()) + " parameters not parabolic"});
   }
   std::vector<unsigned int> which;
   for (unsigned int i = 0; i < best.UserState().MinuitParameters().size(); i++) {
      const auto &p = best.UserState().Parameter(i);
      if (opt.minos && !p.IsFixed() && !p.IsConst())
         which.push_back(i);
   }
   if (!opt.minos && opt.autoMinos)
      for (const auto &q : parab)
         if (q.NeedsMinos())
            which.push_back(q.par);
   std::vector<M2::MinosError> minos;
   if (!which.empty() && best.IsValid()) {
      const double up0 = fcn.Up(), factor = stats::upFactor(opt.cl, 1);
      std::string names;
      for (unsigned int i : which)
         names += (names.empty() ? "" : ", ") + best.UserState().GetName(i);
      run.step("Minos at " + stats::describe(opt.cl) + " for " + names, [&] {
         fcn.SetErrorDef(up0 * factor);
         if (factor != 1)
            best.SetErrorDef(up0 * factor);
         try {
            M2::MnMinos mn(fcn, best, M2::MnStrategy(opt.strategy));
            for (unsigned int i : which)
               minos.push_back(mn.Minos(i));
         } catch (...) {
            fcn.SetErrorDef(up0);
            if (factor != 1)
               best.SetErrorDef(up0);
            throw;
         }
         fcn.SetErrorDef(up0);
         if (factor != 1)
            best.SetErrorDef(up0);
         return best;
      });
   }
   if (!run.noise && best.HasValidParameters()) {
      const long r0 = fcn.Requests();
      run.noise = EstimateNoise(fcn, best.UserState());
      run.steps.push_back({"Noise at the minimum (More-Wild)", fcn.Requests() - r0,
                           run.noise->found ? fmt("sigma = %.3g", run.noise->sigma) : "below detection"});
   }

   // the diagnosis
   std::vector<Finding> f = findings(best, minos);
   if (fcn.NonFinite() > 0) {
      std::string t, sug;
      for (const auto &h : run.limits)
         t += (t.empty() ? "" : ", ") + h.text();
      if (run.limits.empty())
         for (const auto &h : fcn.DomainHints(best.UserState()))
            sug += (sug.empty() ? "" : ", ") + h.text();
      f.push_back({run.limits.empty() ? Grade::Reliable : Grade::Caution,
                   "The function was not finite (NaN or infinite) at " + std::to_string(fcn.NonFinite()) +
                      " of the points asked: it is not defined everywhere Minuit looked" +
                      (!t.empty() ? "; the autopilot added the limits " + t + " that its values imply"
                                  : !sug.empty() ? "; limits " + sug + " would keep it inside" : std::string()) +
                      ".",
                   run.limits.empty() ? "" : "limits added to stay in the function's domain"});
   }
   if (run.noise) {
      f.push_back({Grade::Reliable, "Noise: " + run.noise->text() + ".", ""});
      if (run.precision > 0)
         f.push_back({Grade::Reliable, "Minuit's precision was set to " + fmt("%.2g", run.precision) +
                                          " (SET EPS) so that its numerical derivatives step above the noise.", ""});
      if (run.tolerance != opt.tolerance)
         f.push_back({Grade::Caution, "The tolerance was raised from " + fmt("%.3g", opt.tolerance) + " to " +
                                         fmt("%.3g", run.tolerance) + ": the minimum is known to the noise, not better.",
                      "converged only to the noise level"});
      if (run.noise->found && run.noise->sigma > 0) {
         const double shift = std::sqrt(run.noise->sigma / fcn.Up());
         if (shift < 0.1)
            f.push_back({Grade::Reliable, "The noise moves the minimum by about " + fmt("%.2g", shift) + " errors: negligible.", ""});
         else
            f.push_back({Grade::Caution, "The noise moves the minimum by about " + fmt("%.2g", shift) +
                                            " errors: add it to the errors, or compute the function more precisely.",
                         "the function's noise shifts the minimum"});
      }
   }
   if (!run.flat.empty())
      f.push_back({Grade::Caution, run.flat, "a direction the data do not constrain"});
   for (const auto &n : run.notes)
      f.push_back({Grade::Reliable, n, ""});
   if (basins.size() > 1) {
      int starts = 0, same = 0;
      for (const auto &b : basins) {
         starts += b.hits;
         if (&b != &basins[0] && b.min.IsValid() && std::fabs(b.min.Fval() - best.Fval()) < 1e-3 * best.Up())
            same++;
      }
      f.push_back({Grade::Reliable, std::to_string(basins.size()) + " minima from " + std::to_string(starts) +
                                       " starts; the lowest was reached from " + std::to_string(basins[0].hits) + " of them.", ""});
      if (same > 0)
         f.push_back({Grade::Caution, std::to_string(same) + " other minimum/minima as deep as the best at another place: the "
                                         "function has a symmetry; the errors describe only the minimum kept.",
                      "equally deep minima elsewhere"});
   }
   std::string bent;
   for (const auto &q : parab)
      if (q.NeedsMinos())
         bent += (bent.empty() ? "" : ", ") + q.name +
                 (q.atLimit ? " (its interval reaches a limit)"
                            : " (it rises " + fmt("%.2f", q.minus) + " and " + fmt("%.2f", q.plus) + " times a parabola's)");
   std::string flat;
   for (const auto &q : parab)
      if (q.Flat())
         flat += (flat.empty() ? "" : ", ") + q.name;
   if (!flat.empty())
      f.push_back({Grade::Caution, "The function barely rises across the Hesse interval of " + flat +
                                      " (less than 1% of a parabola's rise): the error does not describe it, and the function "
                                      "may not depend on it there; Minos was not run, it would search in vain.",
                   "a parameter the function barely depends on"});
   if (!parab.empty() && (flat.empty() || !bent.empty()))
      f.push_back({Grade::Reliable, bent.empty() ? std::string("Every parameter is a parabola to 10% across its interval: Hesse's "
                                                               "errors are the intervals, Minos is not needed.")
                                                 : "Not a parabola across the interval: " + bent + "; Minos gives the interval.",
                   ""});

   Report r{best, run.steps, basins, minos, {}, Grade::Reliable, {}, fcn.Requests(), fcn.Evaluations(), fcn.NonFinite(),
            run.noise ? *run.noise : Noise{}, opt.cl};
   for (const auto &x : f)
      r.diagnosis.push_back(x.text);
   r.reasons = detail::describeVerdict(f, r.grade);
   return r;
}

inline Report minimize(const M2::FCNBase &fcn, const M2::MnUserParameters &start, const Options &opt = Options())
{
   return minimize(fcn, M2::MnUserParameterState(start), opt);
}

// ---------------------------------------------------------------------------------------------
// To Sphere and every engine: the same table as ':minuit2 fit' publishes
// ---------------------------------------------------------------------------------------------

#ifdef SPHERE_AUTOPILOT_HAS_SPX
/**
 * Publishes a result in the bridge's outbox as name_fit.spx: Sphere's console says it, and C++,
 * Python, Julia and Fortran read it (f64 "parameters", "errors", "minos_lower", "minos_upper",
 * "covariance", "fcn", "valid"); with a histogram (edges, the model per bin, the data) the Plots
 * tab draws it too.
 */
inline std::string publish(const Report &r, const std::string &name, const std::string &what = "",
                           const std::vector<double> &edges = {}, const std::vector<double> &model = {},
                           const std::vector<double> &data = {})
{
   const auto &st = r.best.UserState();
   const size_t n = st.MinuitParameters().size();
   std::vector<double> lo(n, NAN), hi(n, NAN), cov(n * n, 0.);
   for (const auto &me : r.minos)
      if (me.IsValid()) {
         lo[me.Parameter()] = me.Lower();
         hi[me.Parameter()] = me.Upper();
      }
   if (st.HasCovariance())
      for (unsigned int i = 0; i < st.VariableParameters(); i++)
         for (unsigned int j = 0; j < st.VariableParameters(); j++)
            cov[st.ExtOfInt(i) * n + st.ExtOfInt(j)] = st.Covariance()(i, j);
   std::string names, summary = r.Summary();
   for (size_t i = 0; i < n; i++)
      names += (i ? "," : "") + st.GetName(i);
   std::replace(summary.begin(), summary.end(), '\n', ';');
   spx::Table t(name + " fit");
   t.meta("Engine", "C++ (ROOT Minuit2 + Sphere autopilot)")
      .meta("Fit", what.empty() ? name : what)
      .meta("Parameters", names)
      .meta("Level", stats::describe(r.cl))
      .meta("Verdict", r.Verdict())
      .meta("Summary", summary);
   if (!edges.empty() && model.size() + 1 == edges.size()) {
      t.histogram(edges, model);
      if (!data.empty())
         t.column("data", data);
   }
   t.column("parameters", st.Params()).column("errors", st.Errors()).column("minos_lower", lo).column("minos_upper", hi);
   t.column("covariance", cov).column("fcn", std::vector<double>{r.best.Fval()});
   t.column("valid", std::vector<double>{r.best.IsValid() ? 1. : 0.});
   const std::string path = spx::bridge_folder() + "/outbox/" + name + "_fit.spx";
   t.write(path);
   return path;
}
#endif

// ---------------------------------------------------------------------------------------------
// ROOT objects
// ---------------------------------------------------------------------------------------------

#ifdef SPHERE_AUTOPILOT_HAS_ROOT
namespace detail {
/** chi2 of a TF1 on the bins of a TH1 (empty bins skipped), or the Baker-Cousins Poisson likelihood ratio. */
class HistogramCost : public M2::FCNBase {
public:
   HistogramCost(TH1 *h, TF1 *f, bool poisson) : fH(h), fF(f), fPoisson(poisson) {}
   double operator()(std::vector<double> const &p) const override
   {
      double s = 0;
      for (int i = 1; i <= fH->GetNbinsX(); i++) {
         double x = fH->GetBinCenter(i);
         const double y = fH->GetBinContent(i);
         const double mu = fF->EvalPar(&x, p.data());
         if (fPoisson) {
            if (y > 0) {
               if (!(mu > 0))
                  return NAN;
               s += 2 * (mu - y + y * std::log(y / mu));
            } else {
               s += 2 * mu;
            }
         } else {
            const double e = fH->GetBinError(i);
            if (!(e > 0))
               continue;
            s += (y - mu) * (y - mu) / (e * e);
         }
      }
      return s;
   }
   double Up() const override { return 1; }

private:
   TH1 *fH;
   TF1 *fF;
   bool fPoisson;
};
} // namespace detail

/**
 * Fits f to h with the autopilot (option "L": Poisson likelihood ratio, else chi-square), and
 * gives f the parameters, errors, chi2 and ndf, as TH1::Fit does. f's parameter limits and
 * fixed parameters are kept.
 */
inline Report fit(TH1 *h, TF1 *f, const char *option = "", Options opt = Options())
{
   const std::string o = option ? option : "";
   const bool poisson = o.find('L') != std::string::npos || o.find('l') != std::string::npos;
   detail::HistogramCost cost(h, f, poisson);
   M2::MnUserParameters p;
   std::vector<std::string> names;
   for (int i = 0; i < f->GetNpar(); i++) {
      // Minuit wants different names: a name already used gets its index (Constant, Constant_2)
      std::string name = f->GetParName(i);
      if (std::find(names.begin(), names.end(), name) != names.end())
         name += "_" + std::to_string(i);
      names.push_back(name);
      const double v = f->GetParameter(i);
      double e = f->GetParError(i);
      if (!(e > 0))
         e = v != 0 ? 0.1 * std::fabs(v) : 0.1;
      double lo = 0, hi = 0;
      f->GetParLimits(i, lo, hi);
      if (lo < hi)
         p.Add(name, v, e, lo, hi);
      else
         p.Add(name, v, e);
      if (lo == hi && lo != 0)
         p.Fix(i);
   }
   if (o.find('E') != std::string::npos)
      opt.minos = true;
   Report r = minimize(cost, p, opt);
   const auto &st = r.best.UserState();
   int nfree = 0;
   for (int i = 0; i < f->GetNpar(); i++) {
      f->SetParameter(i, st.Value(i));
      f->SetParError(i, st.Error(i));
      nfree += !st.Parameter(i).IsFixed();
   }
   int bins = 0;
   for (int i = 1; i <= h->GetNbinsX(); i++)
      bins += poisson || h->GetBinError(i) > 0;
   f->SetChisquare(r.best.Fval());
   f->SetNDF(bins - nfree);
#ifdef SPHERE_AUTOPILOT_HAS_SPX
   if (o.find('P') != std::string::npos) {
      std::vector<double> edges, model, data;
      for (int i = 1; i <= h->GetNbinsX() + 1; i++)
         edges.push_back(h->GetXaxis()->GetBinLowEdge(i));
      for (int i = 1; i <= h->GetNbinsX(); i++) {
         model.push_back(f->Eval(h->GetBinCenter(i)));
         data.push_back(h->GetBinContent(i));
      }
      publish(r, h->GetName(), f->GetExpFormula().Data(), edges, model, data);
   }
#endif
   return r;
}

/** For ':root minuit2 autopilot': a TFormula of [0]..[n-1] minimized from start, the report as text. */
inline std::string formula(const char *expr, const std::vector<double> &start, int strategy = 1, double tolerance = 0.1,
                           int multistart = 0, bool minos = false, double cl = 0, double up = 1)
{
   struct FormulaFCN : M2::FCNBase {
      TFormula f;
      double up;
      std::vector<double> vars = std::vector<double>(4, 0.);
      FormulaFCN(const char *e, double u) : f("sphere_autopilot_formula", e), up(u) {}
      double operator()(std::vector<double> const &p) const override
      {
         return const_cast<TFormula &>(f).EvalPar(vars.data(), p.data());
      }
      double Up() const override { return up; }
   } fcn(expr, up);
   M2::MnUserParameters p;
   for (size_t i = 0; i < start.size(); i++)
      p.Add("p" + std::to_string(i), start[i], start[i] != 0 ? 0.1 * std::fabs(start[i]) : 0.1);
   Options o;
   o.strategy = strategy;
   o.tolerance = tolerance;
   o.multistart = multistart;
   o.minos = minos;
   o.cl = cl;
   return minimize(fcn, p, o).text();
}
#endif

} // namespace autopilot
} // namespace sphere

#endif

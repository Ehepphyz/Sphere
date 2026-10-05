// sphere_minuit2.hpp -- ROOT's own Minuit2, driven the way Sphere's Java Minuit2 is,
// so that the two can be compared number for number from Sphere's console
// (:root minuit2 crosscheck) and so that ROOT's fits use Minuit2 explicitly
// with Sphere's settings (:root minuit2 use).
//
// Every number crosses as its 64 bits (16 hex digits): nothing is lost to printing.
#pragma once

#include "TFormula.h"
#include "Math/Functor.h"
#include "Math/MinimizerOptions.h"
#include "Minuit2/Minuit2Minimizer.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

namespace sphere {
namespace minuit2 {

inline std::string bits(double d)
{
   std::uint64_t u;
   std::memcpy(&u, &d, 8);
   char b[20];
   std::snprintf(b, sizeof b, "%016llx", (unsigned long long)u);
   return b;
}

/** ROOT's fits (TH1::Fit, TGraph::Fit, RooFit's default) through Minuit2 with these settings. */
inline std::string use(const char *algo, int strategy, double tolerance)
{
   ROOT::Math::MinimizerOptions::SetDefaultMinimizer("Minuit2", algo);
   if (strategy >= 0)
      ROOT::Math::MinimizerOptions::SetDefaultStrategy(strategy);
   if (tolerance > 0)
      ROOT::Math::MinimizerOptions::SetDefaultTolerance(tolerance);
   std::ostringstream o;
   o << "ROOT now fits with " << ROOT::Math::MinimizerOptions::DefaultMinimizerType() << " / "
     << ROOT::Math::MinimizerOptions::DefaultMinimizerAlgo() << ", strategy "
     << ROOT::Math::MinimizerOptions::DefaultStrategy() << ", tolerance "
     << ROOT::Math::MinimizerOptions::DefaultTolerance();
   return o.str();
}

/**
 * Minimizes a formula of parameters [0]..[n-1] (TFormula) from the start
 * values, each with a step of a tenth of its value (0.1 for zero), as the
 * Java side does. The answer: "ok status fval edm ncalls x0.. err0.." with
 * the doubles as 64-bit hex words.
 */
inline std::string minimize(const char *expr, const std::vector<double> &start, const char *algo, int strategy,
                            double tolerance, double up)
{
   TFormula f("sphere_minuit2_formula", expr);
   const unsigned int n = start.size();
   std::vector<double> vars(4, 0.);
   auto fn = [&](const double *p) { return f.EvalPar(vars.data(), p); };
   ROOT::Math::Functor functor(fn, n);
   ROOT::Minuit2::Minuit2Minimizer m(algo);
   m.SetFunction(functor);
   m.SetStrategy(strategy);
   m.SetTolerance(tolerance);
   m.SetErrorDef(up);
   m.SetPrintLevel(0);
   for (unsigned int i = 0; i < n; i++)
      m.SetVariable(i, "p" + std::to_string(i), start[i], start[i] != 0 ? 0.1 * std::abs(start[i]) : 0.1);
   const bool ok = m.Minimize();
   std::ostringstream o;
   o << (ok ? 1 : 0) << ' ' << m.Status() << ' ' << bits(m.MinValue()) << ' ' << bits(m.Edm()) << ' ' << m.NCalls();
   for (unsigned int i = 0; i < n; i++)
      o << ' ' << bits(m.X()[i]);
   for (unsigned int i = 0; i < n; i++)
      o << ' ' << bits(m.Errors() ? m.Errors()[i] : 0.);
   return o.str();
}

} // namespace minuit2
} // namespace sphere

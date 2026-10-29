// CLHEP's RandomObjects and Fields: RandMultiGauss on several engines, dimensions (odd ones keep a deviate
// for the next vector) and covariances, with its default and explicit mu and S, fireArray; the flat vectors
// of HepRandomVector; XF's Pow (a transformation to a power that is a function), products and pre- and
// post-multiplications of transformation functions. Inputs from xorshift64, one draw per statement.
// Its twin: validation.RandomObjectsReference.
#include "CLHEP/RandomObjects/RandMultiGauss.h"
#include "CLHEP/RandomObjects/RandomVector.h"
#include "CLHEP/Random/TripleRand.h"
#include "CLHEP/Random/MixMaxRng.h"
#include "CLHEP/Random/JamesRandom.h"
#include "CLHEP/Matrix/SymMatrix.h"
#include "CLHEP/Fields/XF.h"
#include "CLHEP/GenericFunctions/Variable.hh"
#include "CLHEP/GenericFunctions/Sin.hh"
#include "CLHEP/Geometry/Vector3D.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <string>

using namespace CLHEP;

static uint64_t state = 88172645463325252ULL;
static double rnd() {
  state ^= state << 13; state ^= state >> 7; state ^= state << 17;
  return (double)(state >> 11) * (1.0 / 9007199254740992.0);
}
static double sym(double s) { return (2 * rnd() - 1) * s; }
static double pos(double s, double o) { return rnd() * s + o; }
static uint64_t bits(double x) { uint64_t b; std::memcpy(&b, &x, 8); return b; }
static void i(const std::string& n, long long v) { std::printf("%s %lld\n", n.c_str(), v); }
static std::string K(const std::string& n, int k) { return n + "." + std::to_string(k); }
static void pv(const std::string& n, const HepVector& v) {
  std::printf("%s %d", n.c_str(), v.num_row());
  for (int r = 1; r <= v.num_row(); r++) std::printf(" %016llx", (unsigned long long)bits(v(r)));
  std::printf("\n");
}
static void pt(const std::string& n, const HepGeom::Transform3D& t) {
  const double e[12] = {t.xx(), t.xy(), t.xz(), t.dx(), t.yx(), t.yy(), t.yz(), t.dy(), t.zx(), t.zy(), t.zz(), t.dz()};
  std::printf("%s", n.c_str());
  for (int k = 0; k < 12; k++) std::printf(" %016llx", (unsigned long long)bits(e[k]));
  std::printf("\n");
}
static HepVector genv(int n) {
  HepVector v(n);
  for (int a = 1; a <= n; a++) v(a) = sym(3.0);
  return v;
}
static HepSymMatrix genpd(int n) {
  HepMatrix a(n, n);
  for (int r = 1; r <= n; r++) for (int c = 1; c <= n; c++) a(r, c) = sym(2.0);
  HepSymMatrix s(n);
  for (int r = 1; r <= n; r++) for (int c = 1; c <= r; c++) {
    double t = 0;
    for (int k = 1; k <= n; k++) t += a(r, k) * a(c, k);
    s(r, c) = t + (r == c ? 0.5 : 0);
  }
  return s;
}
static void multi(const std::string& n, HepRandomEngine& eng, int dim, int dim2) {
  HepVector mu = genv(dim);
  HepSymMatrix S = genpd(dim);
  RandMultiGauss dist(eng, mu, S);
  for (int k = 0; k < 12; k++) pv(K(n, k), dist.fire());
  HepVector arr[5];
  dist.fireArray(5, arr);
  for (int k = 0; k < 5; k++) pv(K(n + ".arr", k), arr[k]);
  HepVector mu2 = genv(dim2);
  HepSymMatrix S2 = genpd(dim2);
  for (int k = 0; k < 6; k++) pv(K(n + ".mu", k), dist.fire(mu2, S2));
  HepVector arr2[4];
  dist.fireArray(4, arr2, mu2, S2);
  for (int k = 0; k < 4; k++) pv(K(n + ".arr2", k), arr2[k]);
  pv(n + ".op", dist());
  pv(n + ".op2", dist(mu2, S2));
}

int main() {
  { TripleRand eng(12345L); multi("mg.triple", eng, 3, 4); }
  { MixMaxRng eng(777L); multi("mg.mixmax", eng, 1, 5); }
  { HepJamesRandom eng(31415L); multi("mg.james", eng, 6, 2); }
  { HepJamesRandom eng(2718L); multi("mg.james2", eng, 2, 7); }
  {
    HepRandomVector rv(54321L);
    pv("rv.flat", rv.flat());
    HepVector a[3] = {HepVector(2), HepVector(3), HepVector(1)};
    rv.flatArray(3, a);
    for (int k = 0; k < 3; k++) pv(K("rv.arr", k), a[k]);
    MixMaxRng e2(99L);
    rv.flatArray(&e2, 3, a);
    for (int k = 0; k < 3; k++) pv(K("rv.arr2", k), a[k]);
    pv("rv.op", rv());
    pv("rv.flat2", rv.flat(&e2));
    HepRandomVector rv0;
    HepVector b[1] = {HepVector(4)};
    rv0.flatArray(1, b);
    pv("rv0.arr", b[0]);
  }
  {
    double angle = pos(2.0, 0.2);
    double ax = sym(1.0);
    double ay = sym(1.0);
    double az = pos(1.0, 0.5);
    double tx = sym(2.0);
    double ty = sym(2.0);
    double tz = sym(2.0);
    HepGeom::Transform3D T = HepGeom::Translate3D(tx, ty, tz) * HepGeom::Rotate3D(angle, HepGeom::Vector3D<double>(ax, ay, az));
    double angle2 = pos(1.0, 0.1);
    HepGeom::Transform3D T2 = HepGeom::RotateZ3D(angle2) * HepGeom::Translate3D(0.5, 0.25, -1.0);
    Genfun::Variable x;
    XF::Pow P(T, x);
    XF::Pow P2(T2, 2.0 * x * x - 1.0);
    XF::Pow P3(T, Genfun::Sin());
    XF::Product prod = P * P2;
    XF::Product prod3 = prod * P3;
    XF::PreMult pre = T2 * P;
    XF::PostMult post = P2 * T;
    XF::PostMult mixed = (T * P3) * T2;
    i("xf.dim", prod.dimensionality());
    i("xf.dim2", post.dimensionality());
    for (int k = 0; k < 6; k++) {
      double v = pos(3.0, -1.0);
      pt(K("xf.pow", k), P(v));
      pt(K("xf.pow2", k), P2(v));
      pt(K("xf.pow3", k), P3(v));
      pt(K("xf.prod", k), prod(v));
      pt(K("xf.prod3", k), prod3(v));
      pt(K("xf.pre", k), pre(v));
      pt(K("xf.post", k), post(v));
      pt(K("xf.mixed", k), mixed(v));
      Genfun::Argument A(1);
      A[0] = v;
      pt(K("xf.arg", k), prod(A));
    }
    XF::Pow copy(P);
    pt("xf.copy", copy(0.75));
  }
  return 0;
}

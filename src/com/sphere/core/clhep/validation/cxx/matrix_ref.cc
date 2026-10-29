// CLHEP's Matrix package: every operator of HepMatrix, HepSymMatrix, HepDiagMatrix and HepVector, the
// inversions of each size and kind (explicit, Haywood, Cholesky, the adaptive choice, dfact/dfinv,
// Bunch-Kaufman), determinants, the QR and Householder algorithms, diagonalization, the text out.
// Inputs from xorshift64, one draw per statement. Its twin: validation.MatrixReference.
#include "CLHEP/Matrix/Matrix.h"
#include "CLHEP/Matrix/SymMatrix.h"
#include "CLHEP/Matrix/DiagMatrix.h"
#include "CLHEP/Matrix/Vector.h"
#include "CLHEP/Random/MixMaxRng.h"
#include "CLHEP/Random/RandFlat.h"
#include "CLHEP/Random/RandGauss.h"
#include "CLHEP/Vector/Rotation.h"
#include "CLHEP/Vector/ThreeVector.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <iomanip>
#include <sstream>
#include <stdexcept>
#include <string>

using namespace CLHEP;

// defined in MatrixLinear.cc, declared in no header
namespace CLHEP { HepVector min_line_dist(const HepVector *const A, const HepVector *const B, int n); }

static uint64_t state = 88172645463325252ULL;
static double rnd() {
  state ^= state << 13; state ^= state >> 7; state ^= state << 17;
  return (double)(state >> 11) * (1.0 / 9007199254740992.0);
}
static double sym(double s) { return (2 * rnd() - 1) * s; }
static uint64_t bits(double x) { uint64_t b; std::memcpy(&b, &x, 8); return b; }
static void d(const std::string& n, double x) { std::printf("%s %016llx\n", n.c_str(), (unsigned long long)bits(x)); }
static void i(const std::string& n, long long v) { std::printf("%s %lld\n", n.c_str(), v); }
static void text(const std::string& n, const std::string& t) { std::printf("%s [%s]\n", n.c_str(), t.c_str()); }
static void pm(const std::string& n, const HepGenMatrix& m) {
  std::printf("%s %dx%d", n.c_str(), m.num_row(), m.num_col());
  for (int r = 1; r <= m.num_row(); r++)
    for (int c = 1; c <= m.num_col(); c++) std::printf(" %016llx", (unsigned long long)bits(m(r, c)));
  std::printf("\n");
}
static double fapply(double x, int r, int c) { return x * r - c + 0.5; }
static double fvec(double x, int r) { return x * x - r; }

static HepMatrix gen(int r, int c) {
  HepMatrix m(r, c);
  for (int a = 1; a <= r; a++) for (int b = 1; b <= c; b++) m(a, b) = sym(5.0);
  return m;
}
static HepSymMatrix gens(int n) {
  HepSymMatrix m(n);
  for (int a = 1; a <= n; a++) for (int b = 1; b <= a; b++) m(a, b) = sym(5.0);
  return m;
}
static HepDiagMatrix gend(int n) {
  HepDiagMatrix m(n);
  for (int a = 1; a <= n; a++) m(a, a) = sym(5.0);
  return m;
}
static HepVector genv(int n) {
  HepVector v(n);
  for (int a = 1; a <= n; a++) v(a) = sym(5.0);
  return v;
}
// a positive definite symmetric matrix: A AT + n I
static HepSymMatrix genpd(int n) {
  HepMatrix a = gen(n, n);
  HepSymMatrix s(n);
  for (int r = 1; r <= n; r++) for (int c = 1; c <= r; c++) {
    double t = 0;
    for (int k = 1; k <= n; k++) t += a(r, k) * a(c, k);
    s(r, c) = t + (r == c ? n : 0);
  }
  return s;
}

static void ops(int k) {
  const int n = 1 + k % 7;
  const int q = 1 + (k / 7) % 5;
  const std::string t = "ops";
  HepMatrix a = gen(n, n), b = gen(n, n), r = gen(n, q);
  HepSymMatrix s = gens(n), s2 = gens(n);
  HepDiagMatrix dg = gend(n), dg2 = gend(n);
  HepVector v = genv(n), w = genv(n);
  const double x = sym(3.0);
  pm(t + ".a", a); pm(t + ".s", s); pm(t + ".dg", dg); pm(t + ".v", v);
  pm(t + ".mm+", a + b); pm(t + ".mm-", a - b); pm(t + ".mm*", a * b); pm(t + ".mr*", a * r);
  pm(t + ".ms+", a + s); pm(t + ".sm+", s + a); pm(t + ".ms-", a - s); pm(t + ".sm-", s - a);
  pm(t + ".ms*", a * s); pm(t + ".sm*", s * a); pm(t + ".ss*", s * s2); pm(t + ".ss+", s + s2); pm(t + ".ss-", s - s2);
  pm(t + ".md+", a + dg); pm(t + ".dm+", dg + a); pm(t + ".md-", a - dg); pm(t + ".dm-", dg - a);
  pm(t + ".md*", a * dg); pm(t + ".dm*", dg * a); pm(t + ".dd*", dg * dg2); pm(t + ".dd+", dg + dg2); pm(t + ".dd-", dg - dg2);
  pm(t + ".sd+", s + dg); pm(t + ".ds+", dg + s); pm(t + ".sd-", s - dg); pm(t + ".ds-", dg - s);
  pm(t + ".mv*", a * v); pm(t + ".sv*", s * v); pm(t + ".dv*", dg * v); pm(t + ".vv+", v + w); pm(t + ".vv-", v - w);
  pm(t + ".vmT*", v * v.T()); pm(t + ".mv+", HepMatrix(v) + w); pm(t + ".vm+", w + HepMatrix(v));
  pm(t + ".mv-", HepMatrix(v) - w); pm(t + ".vm-", w - HepMatrix(v));
  pm(t + ".mx*", a * x); pm(t + ".xm*", x * a); pm(t + ".mx/", a / x); pm(t + ".sx*", s * x); pm(t + ".xs*", x * s);
  pm(t + ".sx/", s / x); pm(t + ".dx*", dg * x); pm(t + ".xd*", x * dg); pm(t + ".dx/", dg / x);
  pm(t + ".vx*", v * x); pm(t + ".xv*", x * v); pm(t + ".vx/", v / x);
  pm(t + ".mneg", -a); pm(t + ".sneg", -s); pm(t + ".dneg", -dg); pm(t + ".vneg", -v);
  { HepMatrix u(a); u += b; u -= s; u += dg; u *= x; u /= 1.5; pm(t + ".massign", u); }
  { HepSymMatrix u(s); u += s2; u -= dg; u *= x; u /= 2.5; u += dg2; pm(t + ".sassign", u); }
  { HepDiagMatrix u(dg); u += dg2; u -= dg; u *= x; u /= 0.5; pm(t + ".dassign", u); }
  { HepVector u(v); u += w; u -= HepMatrix(v); u *= x; u /= 3.0; u += HepMatrix(w); pm(t + ".vassign", u); }
  { HepMatrix u(n + 1, 2); u = s; pm(t + ".m=s", u); u = dg; pm(t + ".m=d", u); u = v; pm(t + ".m=v", u); u = b; pm(t + ".m=m", u); }
  { HepSymMatrix u(2); u = dg; pm(t + ".s=d", u); u.assign(a); pm(t + ".s.assign", u); u = s2; pm(t + ".s=s", u); }
  { HepDiagMatrix u(1); u.assign(a); pm(t + ".d.assignm", u); u.assign(s); pm(t + ".d.assigns", u); }
  { HepVector u; u = HepMatrix(w); pm(t + ".v=m", u); }
  pm(t + ".mT", a.T()); pm(t + ".rT", r.T()); pm(t + ".sT", s.T()); pm(t + ".dT", dg.T()); pm(t + ".vT", v.T());
  pm(t + ".mapply", a.apply(fapply)); pm(t + ".sapply", s.apply(fapply)); pm(t + ".dapply", dg.apply(fapply));
  pm(t + ".vapply", v.apply(fvec));
  pm(t + ".simm", s.similarity(r.T())); pm(t + ".sims", s.similarity(s2)); pm(t + ".simT", s.similarityT(r));
  d(t + ".simv", s.similarity(v)); pm(t + ".dsimm", dg.similarity(r.T())); pm(t + ".dsimT", dg.similarityT(r));
  d(t + ".dsimv", dg.similarity(v));
  d(t + ".dot", dot(v, w)); d(t + ".normsq", v.normsq()); d(t + ".norm", v.norm()); pm(t + ".vTv", vT_times_v(v));
  d(t + ".trm", a.trace()); d(t + ".trs", s.trace()); d(t + ".trd", dg.trace()); d(t + ".trr", r.trace());
  d(t + ".detm", a.determinant()); d(t + ".dets", s.determinant()); d(t + ".detd", dg.determinant());
  d(t + ".norm1", norm1(a)); d(t + ".norminf", norm_infinity(a)); d(t + ".normm", norm(a));
  i(t + ".eqmm", a == a); i(t + ".eqms", a == s); i(t + ".eqss", HepMatrix(s) == s); i(t + ".eqdd", dg == dg2);
  if (n >= 2) {
    pm(t + ".msub", a.sub(1, n - 1, 2, n)); pm(t + ".ssub", s.sub(2, n)); pm(t + ".dsub", dg.sub(1, n - 1));
    pm(t + ".vsub", v.sub(2, n));
    HepMatrix u(a); u.sub(2, 1, b.sub(1, n - 1, 1, 1)); pm(t + ".msubset", u);
    HepSymMatrix su(s); su.sub(2, s2.sub(1, n - 1)); pm(t + ".ssubset", su);
    HepDiagMatrix du(dg); du.sub(2, dg2.sub(1, n - 1)); pm(t + ".dsubset", du);
    HepVector vu(v); vu.sub(2, w.sub(1, n - 1)); pm(t + ".vsubset", vu);
  }
  pm(t + ".dsumm", dsum(a, r)); pm(t + ".dsums", dsum(s, s2)); pm(t + ".dsumd", dsum(dg, dg2)); pm(t + ".dsumv", dsum(v, w));
  { HepMatrix u(a), u2(b); swap(u, u2); pm(t + ".swap1", u); pm(t + ".swap2", u2); }
  { HepVector u(v), u2 = genv(n + 1); swap(u, u2); pm(t + ".vswap1", u); pm(t + ".vswap2", u2); }
  d(t + ".row", a[n - 1][0]); d(t + ".srow", s[0][n - 1]); d(t + ".drow", dg[n - 1][n - 1]);
}

static void inversions(int k) {
  const int n = 1 + k % 9;
  const std::string t = "inv";
  {
    HepMatrix a = gen(n, n);
    int ierr = 0;
    HepMatrix b = a.inverse(ierr);
    i(t + ".mierr", ierr); pm(t + ".minv", b); d(t + ".mdet", a.determinant());
    pm(t + ".mid", a * b);
    pm(t + ".solve", solve(a, genv(n)));
    pm(t + ".qrinv", qr_inverse(a));
    pm(t + ".qrsolve", qr_solve(a, genv(n)));
    pm(t + ".qrsolvem", qr_solve(a, gen(n, 2)));
  }
  {
    HepSymMatrix s = (k % 4 == 1) ? gens(n) : genpd(n);
    int ierr = 0;
    HepSymMatrix b = s.inverse(ierr);
    i(t + ".sierr", ierr); pm(t + ".sinv", b); d(t + ".sdet", s.determinant());
    HepSymMatrix c(s);
    c.invertBunchKaufman(ierr);
    i(t + ".bkierr", ierr); pm(t + ".bk", c);
    d(t + ".cond", condition(s));
    HepSymMatrix e(s);
    HepMatrix u = diagonalize(&e);
    pm(t + ".eigval", e); pm(t + ".eigvec", u);
    HepSymMatrix f(s);
    pm(t + ".tridiag", tridiagonal(&f)); pm(t + ".tridiagS", f);
    if (n == 5 || n == 6) {
      HepSymMatrix g(s), h(s);
      if (n == 5) { g.invertCholesky5(ierr); i(t + ".ch5", ierr); h.invertHaywood5(ierr); }
      else { g.invertCholesky6(ierr); i(t + ".ch6", ierr); h.invertHaywood6(ierr); }
      pm(t + ".chol", g); pm(t + ".hay", h);
    }
  }
  {
    HepDiagMatrix dg = gend(n);
    int ierr = 0;
    HepDiagMatrix b = dg.inverse(ierr);
    i(t + ".dierr", ierr); pm(t + ".dinv", b);
  }
}

int main() {
  for (int k = 0; k < 140; k++) ops(k);
  for (int k = 0; k < 120; k++) inversions(k);

  // singular matrices of each size
  for (int n = 1; n <= 8; n++) {
    HepMatrix z(n, n, 0);
    for (int c = 1; c <= n; c++) z(1, c) = c;
    if (n > 1) for (int c = 1; c <= n; c++) z(2, c) = 2 * c;
    int ierr = 0;
    HepMatrix zi = z.inverse(ierr);
    i("sing.mierr", ierr); pm("sing.m", zi); d("sing.mdet", z.determinant());
    pm("sing.solve", solve(z, HepVector(n, 1)));
    HepSymMatrix zs(n, 0);
    for (int c = 1; c <= n; c++) zs(1, c) = c;
    HepSymMatrix zsi = zs.inverse(ierr);
    i("sing.sierr", ierr); pm("sing.s", zsi);
    HepDiagMatrix zd(n, 1);
    zd(n, n) = 0;
    zd.invert(ierr);
    i("sing.dierr", ierr); pm("sing.d", zd);
  }
  // the exceptions of inverse()
  try { HepMatrix(2, 2, 0).inverse(); text("throw.m", "none"); } catch (std::runtime_error& e) { text("throw.m", e.what()); }
  try { HepSymMatrix(2, 0).inverse(); text("throw.s", "none"); } catch (std::runtime_error& e) { text("throw.s", e.what()); }
  try { HepDiagMatrix(2, 0).inverse(); text("throw.d", "none"); } catch (std::runtime_error& e) { text("throw.d", e.what()); }

  // the adaptive choice of 5x5 and 6x6 inversions, through a run of matrices not positive definite
  for (int k = 0; k < 60; k++) {
    const int n = 5 + k % 2;
    HepSymMatrix s = (k / 2) % 5 == 0 ? genpd(n) : gens(n);
    int ierr = 0;
    s.invert(ierr);
    i("adapt.ierr", ierr); pm("adapt.inv", s);
  }

  // the Householder and Givens pieces
  for (int k = 0; k < 40; k++) {
    const int n = 2 + k % 5;
    HepMatrix a = gen(n, n);
    HepSymMatrix s = gens(n);
    pm("house.m", house(a, 1 + k % n, 1)); pm("house.s", house(s, 1 + k % n, 1));
    { HepMatrix b(a); house_with_update(&b, 1, 1); pm("house.upd", b); }
    { HepMatrix b(a), v(n, n, 0); house_with_update(&b, &v, 1, 1); pm("house.upd2a", b); pm("house.upd2v", v); }
    { HepSymMatrix b(s); HepMatrix v(n, n, 0); house_with_update2(&b, &v, 2 <= n ? 2 : 1, 1); pm("house.upd3a", b); pm("house.upd3v", v); }
    { double c, sn; givens(a(1, 1), a(n, 1), &c, &sn); d("givens.c", c); d("givens.s", sn);
      HepMatrix b(a); row_givens(&b, c, sn, 1, n); pm("givens.row", b);
      HepMatrix e(a); col_givens(&e, c, sn, 1, n); pm("givens.col", e); }
    { HepMatrix b(a); HepMatrix q = qr_decomp(&b); pm("qr.q", q); pm("qr.r", b);
      HepVector y = genv(n); back_solve(b, &y); pm("qr.backv", y);
      HepMatrix ym = gen(n, 2); back_solve(b, &ym); pm("qr.backm", ym); }
    { HepSymMatrix b(s); diag_step(&b, 1, n); pm("diagstep", b); }
  }
  {
    HepVector A[3] = {genv(3), genv(3), HepVector(3, 0)};
    HepVector B[3] = {genv(3), genv(3), genv(3)};
    pm("minline", min_line_dist(A, B, 3));
  }

  // rotations and three-vectors
  {
    HepRotation r; r.rotateX(0.3); r.rotateY(-1.1); r.rotateZ(2.0);
    HepMatrix m(1, 1); m = r; pm("eq.rot", m);
    HepVector v(5, 1); v = Hep3Vector(1.5, -2.5, 3.5); pm("eq.3v", v);
  }

  // random filling
  {
    MixMaxRng e(77);
    RandGauss g(e, 1.0, 2.0);
    RandFlat f(e);
    pm("rand.m", HepMatrix(3, 4, g)); pm("rand.s", HepSymMatrix(4, f)); pm("rand.d", HepDiagMatrix(3, g));
    pm("rand.v", HepVector(5, f));
    pm("init.m", HepMatrix(3, 3, 1)); pm("init.s", HepSymMatrix(3, 1)); pm("init.d", HepDiagMatrix(3, 1));
    pm("init.v", HepVector(3, 1));
  }

  // the text out
  {
    HepMatrix a = gen(3, 2);
    HepSymMatrix s = gens(3);
    HepDiagMatrix dg = gend(2);
    HepVector v = genv(3);
    { std::ostringstream os; os << a << s << dg << v; text("out.default", os.str()); }
    { std::ostringstream os; os << std::fixed << std::setprecision(3) << a << s << dg << v; text("out.fixed", os.str()); }
    { std::ostringstream os; os << std::scientific << std::setprecision(10) << a << v; text("out.sci", os.str()); }
  }
  return 0;
}

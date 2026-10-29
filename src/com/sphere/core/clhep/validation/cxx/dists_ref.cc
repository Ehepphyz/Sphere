// The distributions of CLHEP's Random package and its statistical functions: their numbers, their
// states written and read back, the static states saved and restored. Its twin:
// validation.DistsReference. Every engine is seeded, so no number depends on how many engines a
// process made before.
#include "CLHEP/Random/MixMaxRng.h"
#include "CLHEP/Random/JamesRandom.h"
#include "CLHEP/Random/MTwistEngine.h"
#include "CLHEP/Random/RanecuEngine.h"
#include "CLHEP/Random/RanluxEngine.h"
#include "CLHEP/Random/Ranlux64Engine.h"
#include "CLHEP/Random/RanluxppEngine.h"
#include "CLHEP/Random/RanshiEngine.h"
#include "CLHEP/Random/DualRand.h"
#include "CLHEP/Random/TripleRand.h"
#include "CLHEP/Random/Hurd160Engine.h"
#include "CLHEP/Random/Hurd288Engine.h"
#include "CLHEP/Random/DRand48Engine.h"
#include "CLHEP/Random/RandEngine.h"
#include "CLHEP/Random/NonRandomEngine.h"
#include "CLHEP/Random/Random.h"
#include "CLHEP/Random/RandFlat.h"
#include "CLHEP/Random/RandBit.h"
#include "CLHEP/Random/RandGauss.h"
#include "CLHEP/Random/RandGaussQ.h"
#include "CLHEP/Random/RandGaussT.h"
#include "CLHEP/Random/RandGaussZiggurat.h"
#include "CLHEP/Random/RandExponential.h"
#include "CLHEP/Random/RandExpZiggurat.h"
#include "CLHEP/Random/RandBreitWigner.h"
#include "CLHEP/Random/RandChiSquare.h"
#include "CLHEP/Random/RandGamma.h"
#include "CLHEP/Random/RandStudentT.h"
#include "CLHEP/Random/RandPoisson.h"
#include "CLHEP/Random/RandPoissonQ.h"
#include "CLHEP/Random/RandPoissonT.h"
#include "CLHEP/Random/RandBinomial.h"
#include "CLHEP/Random/RandLandau.h"
#include "CLHEP/Random/RandGeneral.h"
#include "CLHEP/Random/RandSkewNormal.h"
#include "CLHEP/Random/Stat.h"
#include "CLHEP/Random/StaticRandomStates.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <sstream>
#include <string>

using namespace CLHEP;

static void d(const char* n, double x) {
  uint64_t b; std::memcpy(&b, &x, 8);
  std::printf("%s %016llx\n", n, (unsigned long long)b);
}
static void i(const char* n, long long v) { std::printf("%s %lld\n", n, v); }
static void text(const char* n, const std::string& t) { std::printf("%s [%s]\n", n, t.c_str()); }
template <class D> static std::string put(const D& x) { std::ostringstream os; x.put(os); return os.str(); }
static std::string replace(std::string s, const std::string& from, const std::string& to) {
  for (size_t p = s.find(from); p != std::string::npos; p = s.find(from, p + to.size())) s.replace(p, from.size(), to);
  return s;
}

int main() {
  // ---- HepStat
  {
    const double rs[] = {1e-15, 3e-14, 1.9e-13, 2e-13, 7.7e-13, 3.9e-11, 4e-11, 5.5e-10, 9.99e-9, 1e-8, 3.3e-7,
                         1.99e-6, 2e-6, 4.4e-5, 4.99e-4, 5e-4, 0.001, 0.0123, 0.1, 0.25, 0.3333, 0.49999, 0.5,
                         0.50001, 0.6, 0.75, 0.9, 0.99, 0.999, 0.9999, 0.99999, 0.999999, 1 - 1e-9, 1 - 1e-12};
    for (double r : rs) d("stat.flatToGaussian", HepStat::flatToGaussian(r));
    for (double r : rs) d("stat.inverseErf", HepStat::inverseErf(2 * r - 1));
    const double xs[] = {-3, -2.5, -1, -0.5, -0.1, 0, 0.01, 0.2, 0.7, 1, 1.5, 2, 2.9, 4};
    for (double x : xs) { d("stat.erfQ", HepStat::erfQ(x)); d("stat.erf", HepStat::erf(x)); }
    const double gs[] = {0.1, 0.5, 1, 1.5, 2, 3.7, 10, 33.3, 100, 171.5, 1000, 1e5};
    for (double g : gs) d("stat.gammln", HepStat::gammln(g));
  }

  MixMaxRng se(20250101);
  HepRandom::setTheEngine(&se);
  MixMaxRng e(4357);
  int k;

  // ---- RandFlat
  for (k = 0; k < 50; k++) d("flat.shoot", RandFlat::shoot());
  for (k = 0; k < 20; k++) d("flat.shootW", RandFlat::shoot(2.5));
  for (k = 0; k < 20; k++) d("flat.shootAB", RandFlat::shoot(-1.5, 3.25));
  for (k = 0; k < 20; k++) i("flat.shootInt", RandFlat::shootInt(1000L));
  for (k = 0; k < 20; k++) i("flat.shootInt2", RandFlat::shootInt(-50L, 50L));
  for (k = 0; k < 70; k++) i("flat.shootBit", RandFlat::shootBit());
  { double a[13]; RandFlat::shootArray(13, a); for (k = 0; k < 13; k++) d("flat.shootArray", a[k]); }
  { double a[13]; RandFlat::shootArray(13, a, 2.0, 5.0); for (k = 0; k < 13; k++) d("flat.shootArray2", a[k]); }
  for (k = 0; k < 20; k++) d("flat.shootE", RandFlat::shoot(&e));
  for (k = 0; k < 20; k++) d("flat.shootEW", RandFlat::shoot(&e, 0.5));
  for (k = 0; k < 20; k++) d("flat.shootEAB", RandFlat::shoot(&e, 10.0, 11.0));
  for (k = 0; k < 20; k++) i("flat.shootEInt", RandFlat::shootInt(&e, 77L));
  for (k = 0; k < 20; k++) i("flat.shootEInt2", RandFlat::shootInt(&e, 5L, 9L));
  for (k = 0; k < 40; k++) i("flat.shootEBit", RandFlat::shootBit(&e));
  { double a[9]; RandFlat::shootArray(&e, 9, a); for (k = 0; k < 9; k++) d("flat.shootEArray", a[k]); }
  { double a[9]; RandFlat::shootArray(&e, 9, a, -1.0, 1.0); for (k = 0; k < 9; k++) d("flat.shootEArray2", a[k]); }
  std::string flatText;
  {
    RandFlat f(e, -2.0, 6.0);
    for (k = 0; k < 20; k++) d("flat.fire", f.fire());
    for (k = 0; k < 5; k++) d("flat.fireW", f.fire(3.0));
    for (k = 0; k < 5; k++) d("flat.fireAB", f.fire(1.0, 2.0));
    for (k = 0; k < 5; k++) i("flat.fireInt", f.fireInt(10L));
    for (k = 0; k < 5; k++) i("flat.fireInt2", f.fireInt(100L, 200L));
    for (k = 0; k < 40; k++) i("flat.fireBit", f.fireBit());
    { double a[6]; f.fireArray(6, a); for (k = 0; k < 6; k++) d("flat.fireArray", a[k]); }
    { double a[6]; f.fireArray(6, a, 0.0, 0.5); for (k = 0; k < 6; k++) d("flat.fireArray2", a[k]); }
    for (k = 0; k < 5; k++) d("flat.op", f());
    for (k = 0; k < 5; k++) d("flat.opW", f(7.0));
    for (k = 0; k < 5; k++) d("flat.opAB", f(-7.0, -6.0));
    flatText = put(f);
    text("flat.put", flatText);
    RandFlat g(e);
    std::istringstream is(flatText);
    g.get(is);
    text("flat.get", put(g));
    for (k = 0; k < 10; k++) d("flat.restored", g.fire());
    for (k = 0; k < 10; k++) i("flat.restoredBit", g.fireBit());
  }
  {
    std::ostringstream os; RandFlat::saveDistState(os); text("flat.dist", os.str());
    for (k = 0; k < 5; k++) i("flat.distAfter", RandFlat::shootBit());
    std::istringstream is(os.str()); RandFlat::restoreDistState(is);
    for (k = 0; k < 5; k++) i("flat.distRestored", RandFlat::shootBit());
  }

  // ---- RandBit
  for (k = 0; k < 40; k++) i("bit.shootBit", RandBit::shootBit());
  for (k = 0; k < 40; k++) i("bit.shootBitE", RandBit::shootBit(&e));
  {
    RandBit b(e, 1.0, 3.0);
    for (k = 0; k < 40; k++) i("bit.fireBit", b.fireBit());
    for (k = 0; k < 5; k++) d("bit.fire", b.fire());
    RandBit b2(e);
    std::istringstream is(replace(" RandBit\n" + flatText, "RandFlat", "RandBit"));
    b2.get(is);
    text("bit.get", put(b2));
    for (k = 0; k < 5; k++) d("bit.restored", b2.fire());
  }

  // ---- RandGauss
  for (k = 0; k < 41; k++) d("gauss.shoot", RandGauss::shoot());
  for (k = 0; k < 20; k++) d("gauss.shootMS", RandGauss::shoot(10.0, 2.0));
  { double a[11]; RandGauss::shootArray(11, a, 1.0, 0.5); for (k = 0; k < 11; k++) d("gauss.shootArray", a[k]); }
  for (k = 0; k < 21; k++) d("gauss.shootE", RandGauss::shoot(&e));
  for (k = 0; k < 20; k++) d("gauss.shootEMS", RandGauss::shoot(&e, -3.0, 0.25));
  { double a[7]; RandGauss::shootArray(&e, 7, a); for (k = 0; k < 7; k++) d("gauss.shootEArray", a[k]); }
  i("gauss.flag", RandGauss::getFlag());
  {
    RandGauss g(e, 5.0, 3.0);
    for (k = 0; k < 21; k++) d("gauss.fire", g.fire());
    for (k = 0; k < 10; k++) d("gauss.fireMS", g.fire(0.0, 1.0));
    { double a[5]; g.fireArray(5, a); for (k = 0; k < 5; k++) d("gauss.fireArray", a[k]); }
    { double a[5]; g.fireArray(5, a, 2.0, 2.0); for (k = 0; k < 5; k++) d("gauss.fireArray2", a[k]); }
    for (k = 0; k < 5; k++) d("gauss.op", g());
    for (k = 0; k < 5; k++) d("gauss.opMS", g(1.0, 0.1));
    i("gauss.F", g.getF());
    const std::string t = put(g);
    text("gauss.put", t);
    RandGauss g2(e);
    std::istringstream is(t);
    g2.get(is);
    text("gauss.get", put(g2));
    for (k = 0; k < 6; k++) d("gauss.restored", g2.fire());
  }
  {
    std::ostringstream os; RandGauss::saveDistState(os); text("gauss.dist", os.str());
    d("gauss.distAfter", RandGauss::shoot());
    std::istringstream is(os.str()); RandGauss::restoreDistState(is);
    d("gauss.distRestored", RandGauss::shoot());
  }
  {
    std::ostringstream os; RandGauss::saveFullState(os); text("gauss.full", os.str());
    for (k = 0; k < 5; k++) d("gauss.fullAfter", RandGauss::shoot());
    std::istringstream is(os.str()); RandGauss::restoreFullState(is);
    for (k = 0; k < 5; k++) d("gauss.fullRestored", RandGauss::shoot());
  }

  // ---- the tables' ends, through a NonRandomEngine
  NonRandomEngine nre;
  {
    double seq[] = {1e-7, 1.5e-6, 2e-6, 2.5e-6, 4.9e-4, 5e-4, 5.1e-4, 0.0013, 0.1, 0.25, 0.4999, 0.5, 0.5001, 0.75,
                    0.9995, 0.99999, 0.9999999, 0.0005, 0.0069, 0.007, 0.0701, 0.069, 0.8, 0.8011, 0.9805,
                    0.981, 0.99, 0.9991, 0.99991, 0.999999};
    nre.setRandomSequence(seq, 30);
    for (k = 0; k < 30; k++) d("tables.gaussQ", RandGaussQ::shoot(&nre));
    nre.setRandomSequence(seq, 30);
    for (k = 0; k < 30; k++) d("tables.gaussT", RandGaussT::shoot(&nre));
    nre.setRandomSequence(seq, 30);
    for (k = 0; k < 30; k++) d("tables.landau", RandLandau::shoot(&nre));
  }

  // ---- RandGaussQ, RandGaussT
  for (k = 0; k < 30; k++) d("gaussQ.shoot", RandGaussQ::shoot());
  for (k = 0; k < 10; k++) d("gaussQ.shootMS", RandGaussQ::shoot(1.0, 4.0));
  { double a[7]; RandGaussQ::shootArray(7, a, 0.5, 0.5); for (k = 0; k < 7; k++) d("gaussQ.shootArray", a[k]); }
  for (k = 0; k < 10; k++) d("gaussQ.shootE", RandGaussQ::shoot(&e));
  { double a[7]; RandGaussQ::shootArray(&e, 7, a, -1.0, 3.0); for (k = 0; k < 7; k++) d("gaussQ.shootEArray", a[k]); }
  {
    RandGaussQ g(e, 100.0, 10.0);
    for (k = 0; k < 10; k++) d("gaussQ.fire", g.fire());
    for (k = 0; k < 5; k++) d("gaussQ.fireMS", g.fire(0.0, 2.0));
    { double a[5]; g.fireArray(5, a); for (k = 0; k < 5; k++) d("gaussQ.fireArray", a[k]); }
    for (k = 0; k < 5; k++) d("gaussQ.op", g());
    for (k = 0; k < 5; k++) d("gaussQ.opMS", g(-1.0, 1.0));
    const std::string t = put(g);
    text("gaussQ.put", t);
    RandGaussQ g2(e);
    std::istringstream is(t);
    g2.get(is);
    text("gaussQ.get", put(g2));
  }
  for (k = 0; k < 30; k++) d("gaussT.shoot", RandGaussT::shoot());
  for (k = 0; k < 10; k++) d("gaussT.shootMS", RandGaussT::shoot(1.0, 4.0));
  { double a[7]; RandGaussT::shootArray(7, a, 0.5, 0.5); for (k = 0; k < 7; k++) d("gaussT.shootArray", a[k]); }
  for (k = 0; k < 10; k++) d("gaussT.shootE", RandGaussT::shoot(&e));
  {
    RandGaussT g(e, -5.0, 0.5);
    for (k = 0; k < 10; k++) d("gaussT.fire", g.fire());
    for (k = 0; k < 5; k++) d("gaussT.fireMS", g.fire(0.0, 2.0));
    for (k = 0; k < 5; k++) d("gaussT.op", g());
    const std::string t = put(g);
    text("gaussT.put", t);
    RandGaussT g2(e);
    std::istringstream is(t);
    g2.get(is);
    text("gaussT.get", put(g2));
  }

  // ---- RandGaussZiggurat, RandExpZiggurat
  for (k = 0; k < 50; k++) d("zig.shoot", RandGaussZiggurat::shoot());
  for (k = 0; k < 20; k++) d("zig.shootMS", RandGaussZiggurat::shoot(1.5f, 0.3f));
  { float a[9]; RandGaussZiggurat::shootArray(9, a, 0.f, 2.f); for (k = 0; k < 9; k++) d("zig.shootArrayF", a[k]); }
  { double a[9]; RandGaussZiggurat::shootArray(9, a, 1.0, 3.0); for (k = 0; k < 9; k++) d("zig.shootArrayD", a[k]); }
  for (k = 0; k < 20; k++) d("zig.shootE", RandGaussZiggurat::shoot(&e));
  for (k = 0; k < 10; k++) d("zig.shootEMS", RandGaussZiggurat::shoot(&e, 0.1f, 10.f));
  {
    RandGaussZiggurat z(e, 2.0, 0.5);
    for (k = 0; k < 20; k++) d("zig.fire", z.fire());
    for (k = 0; k < 5; k++) d("zig.fireMS", z.fire(1.f, 2.f));
    for (k = 0; k < 5; k++) d("zig.op", z());
    for (k = 0; k < 5; k++) d("zig.opMS", z(1.0, 2.0));
    { float a[5]; z.fireArray(5, a); for (k = 0; k < 5; k++) d("zig.fireArrayF", a[k]); }
    { double a[5]; z.fireArray(5, a); for (k = 0; k < 5; k++) d("zig.fireArrayD", a[k]); }
    text("zig.put", put(z));
  }
  {
    MixMaxRng ze(777);
    double sum = 0, sum2 = 0; float mn = 0, mx = 0; long long tail = 0;
    for (k = 0; k < 200000; k++) {
      const float v = RandGaussZiggurat::shoot(&ze);
      sum += v; sum2 += (double)v * v;
      if (v < mn) mn = v;
      if (v > mx) mx = v;
      if (v > 3.442620f || v < -3.442620f) tail++;
    }
    d("zigStress.sum", sum); d("zigStress.sum2", sum2); d("zigStress.min", mn); d("zigStress.max", mx);
    i("zigStress.tail", tail);
  }
  for (k = 0; k < 50; k++) d("expzig.shoot", RandExpZiggurat::shoot());
  for (k = 0; k < 20; k++) d("expzig.shootM", RandExpZiggurat::shoot(2.5f));
  { float a[9]; RandExpZiggurat::shootArray(9, a, 3.f); for (k = 0; k < 9; k++) d("expzig.shootArrayF", a[k]); }
  { double a[9]; RandExpZiggurat::shootArray(9, a, 0.5); for (k = 0; k < 9; k++) d("expzig.shootArrayD", a[k]); }
  for (k = 0; k < 20; k++) d("expzig.shootE", RandExpZiggurat::shoot(&e));
  {
    RandExpZiggurat z(e, 4.0);
    for (k = 0; k < 20; k++) d("expzig.fire", z.fire());
    for (k = 0; k < 5; k++) d("expzig.fireM", z.fire(0.25f));
    for (k = 0; k < 5; k++) d("expzig.op", z());
    for (k = 0; k < 5; k++) d("expzig.opM", z(8.f));
    const std::string t = put(z);
    text("expzig.put", t);
    RandExpZiggurat z2(e);
    std::istringstream is(t);
    z2.get(is);
    text("expzig.get", put(z2));
    for (k = 0; k < 5; k++) d("expzig.restored", z2.fire());
  }
  {
    MixMaxRng ze(778);
    double sum = 0; float mx = 0; long long tail = 0;
    for (k = 0; k < 200000; k++) {
      const float v = RandExpZiggurat::shoot(&ze);
      sum += v;
      if (v > mx) mx = v;
      if (v > 7.69711f) tail++;
    }
    d("expzigStress.sum", sum); d("expzigStress.max", mx); i("expzigStress.tail", tail);
  }

  // ---- every engine under the distributions that read its 32-bit words
  {
    MixMaxRng e1(11); HepJamesRandom e2(12); MTwistEngine e3(13); RanecuEngine e4(14); RanluxEngine e5(15, 3);
    Ranlux64Engine e6(16, 1); RanluxppEngine e7(17); RanshiEngine e8(18); DualRand e9(19); TripleRand e10(20);
    Hurd160Engine e11(21); Hurd288Engine e12(22); DRand48Engine e13(23); RandEngine e14(24);
    HepRandomEngine* es[] = {&e1, &e2, &e3, &e4, &e5, &e6, &e7, &e8, &e9, &e10, &e11, &e12, &e13, &e14};
    for (HepRandomEngine* x : es) {
      const std::string n = "engines." + x->name();
      for (k = 0; k < 30; k++) d((n + ".zig").c_str(), RandGaussZiggurat::shoot(x));
      for (k = 0; k < 30; k++) d((n + ".expzig").c_str(), RandExpZiggurat::shoot(x));
      for (k = 0; k < 10; k++) d((n + ".gaussQ").c_str(), RandGaussQ::shoot(x));
      for (k = 0; k < 10; k++) i((n + ".poisson").c_str(), RandPoisson::shoot(x, 7.5));
    }
  }

  // ---- RandExponential
  for (k = 0; k < 20; k++) d("exp.shoot", RandExponential::shoot());
  for (k = 0; k < 10; k++) d("exp.shootM", RandExponential::shoot(3.0));
  { double a[7]; RandExponential::shootArray(7, a, 0.1); for (k = 0; k < 7; k++) d("exp.shootArray", a[k]); }
  for (k = 0; k < 10; k++) d("exp.shootE", RandExponential::shoot(&e));
  for (k = 0; k < 10; k++) d("exp.shootEM", RandExponential::shoot(&e, 7.0));
  {
    RandExponential x(e, 2.0);
    for (k = 0; k < 10; k++) d("exp.fire", x.fire());
    for (k = 0; k < 5; k++) d("exp.fireM", x.fire(0.5));
    for (k = 0; k < 5; k++) d("exp.op", x());
    { double a[5]; x.fireArray(5, a); for (k = 0; k < 5; k++) d("exp.fireArray", a[k]); }
    const std::string t = put(x);
    text("exp.put", t);
    RandExponential x2(e);
    std::istringstream is(t);
    x2.get(is);
    text("exp.get", put(x2));
  }

  // ---- RandBreitWigner
  for (k = 0; k < 10; k++) d("bw.shoot", RandBreitWigner::shoot());
  for (k = 0; k < 10; k++) d("bw.shootAB", RandBreitWigner::shoot(91.19, 2.5));
  for (k = 0; k < 10; k++) d("bw.shootABC", RandBreitWigner::shoot(91.19, 2.5, 10.0));
  d("bw.shootABC0", RandBreitWigner::shoot(91.19, 0.0, 10.0));
  for (k = 0; k < 10; k++) d("bw.shootM2", RandBreitWigner::shootM2(91.19, 2.5));
  for (k = 0; k < 10; k++) d("bw.shootM2C", RandBreitWigner::shootM2(91.19, 2.5, 10.0));
  for (k = 0; k < 10; k++) d("bw.shootM2Cbig", RandBreitWigner::shootM2(1.0, 0.5, 5.0));
  { double a[5]; RandBreitWigner::shootArray(5, a); for (k = 0; k < 5; k++) d("bw.shootArray", a[k]); }
  { double a[5]; RandBreitWigner::shootArray(5, a, 3.0, 0.1, 0.2); for (k = 0; k < 5; k++) d("bw.shootArray3", a[k]); }
  for (k = 0; k < 10; k++) d("bw.shootE", RandBreitWigner::shoot(&e, 0.775, 0.149));
  for (k = 0; k < 10; k++) d("bw.shootEC", RandBreitWigner::shoot(&e, 0.775, 0.149, 0.3));
  for (k = 0; k < 10; k++) d("bw.shootEM2", RandBreitWigner::shootM2(&e, 0.775, 0.149));
  for (k = 0; k < 10; k++) d("bw.shootEM2C", RandBreitWigner::shootM2(&e, 0.775, 0.149, 0.3));
  {
    RandBreitWigner b(e, 1.02, 0.0043);
    for (k = 0; k < 10; k++) d("bw.fire", b.fire());
    for (k = 0; k < 5; k++) d("bw.fireC", b.fire(1.02, 0.0043, 0.01));
    for (k = 0; k < 5; k++) d("bw.fireM2", b.fireM2());
    for (k = 0; k < 5; k++) d("bw.fireM2C", b.fireM2(1.02, 0.0043, 0.01));
    for (k = 0; k < 5; k++) d("bw.op", b());
    const std::string t = put(b);
    text("bw.put", t);
    RandBreitWigner b2(e);
    std::istringstream is(t);
    b2.get(is);
    text("bw.get", put(b2));
  }

  // ---- RandChiSquare, RandGamma, RandStudentT
  {
    const double as[] = {1.0, 1.5, 3.5, 20.0, 0.5};
    for (double a : as) for (k = 0; k < 10; k++) d("chi2.shoot", RandChiSquare::shoot(a));
    for (k = 0; k < 10; k++) d("chi2.shootDefault", RandChiSquare::shoot());
    for (double a : as) for (k = 0; k < 10; k++) d("chi2.shootE", RandChiSquare::shoot(&e, a));
    RandChiSquare c(e, 4.0);
    for (k = 0; k < 10; k++) d("chi2.fire", c.fire());
    for (k = 0; k < 5; k++) d("chi2.fireA", c.fire(1.0));
    { double a[5]; c.fireArray(5, a); for (k = 0; k < 5; k++) d("chi2.fireArray", a[k]); }
    const std::string t = put(c);
    text("chi2.put", t);
    RandChiSquare c2(e);
    std::istringstream is(t);
    c2.get(is);
    text("chi2.get", put(c2));
  }
  {
    const double ks[] = {0.3, 0.999, 1.0, 2.5, 3.0, 5.0, 13.5, 20.0, 0.0, -1.0};
    for (double kk : ks) for (k = 0; k < 10; k++) d("gamma.shoot", RandGamma::shoot(kk, 1.5));
    for (k = 0; k < 5; k++) d("gamma.shootLambda0", RandGamma::shoot(2.0, 0.0));
    for (k = 0; k < 10; k++) d("gamma.shootDefault", RandGamma::shoot());
    for (double kk : ks) for (k = 0; k < 10; k++) d("gamma.shootE", RandGamma::shoot(&e, kk, 0.25));
    RandGamma g(e, 7.0, 2.0);
    for (k = 0; k < 10; k++) d("gamma.fire", g.fire());
    for (k = 0; k < 5; k++) d("gamma.fireKL", g.fire(0.5, 1.0));
    for (k = 0; k < 5; k++) d("gamma.op", g());
    const std::string t = put(g);
    text("gamma.put", t);
    RandGamma g2(e);
    std::istringstream is(t);
    g2.get(is);
    text("gamma.get", put(g2));
  }
  {
    const double as[] = {1.0, 2.0, 3.5, 30.0, 0.5};
    for (double a : as) for (k = 0; k < 10; k++) d("studentT.shoot", RandStudentT::shoot(a));
    d("studentT.shootNeg", RandStudentT::shoot(-1.0));
    for (double a : as) for (k = 0; k < 10; k++) d("studentT.shootE", RandStudentT::shoot(&e, a));
    RandStudentT s(e, 5.0);
    for (k = 0; k < 10; k++) d("studentT.fire", s.fire());
    for (k = 0; k < 5; k++) d("studentT.op", s());
    const std::string t = put(s);
    text("studentT.put", t);
    RandStudentT s2(e);
    std::istringstream is(t);
    s2.get(is);
    text("studentT.get", put(s2));
  }

  // ---- RandPoisson, RandPoissonQ, RandPoissonT
  {
    const double ms[] = {0.5, 5.0, 11.9, 12.0, 25.0, 25.0, 300.0, 1e5, 2.05e9, 5.0, -1.0};
    for (double m : ms) for (k = 0; k < 10; k++) i("poisson.shoot", RandPoisson::shoot(m));
    for (double m : ms) for (k = 0; k < 10; k++) i("poisson.shootE", RandPoisson::shoot(&e, m));
    { long a[7]; RandPoisson::shootArray(7, a, 40.0); for (k = 0; k < 7; k++) i("poisson.shootArray", a[k]); }
    RandPoisson p(e, 33.0);
    for (k = 0; k < 10; k++) i("poisson.fire", p.fire());
    for (k = 0; k < 5; k++) i("poisson.fireM", p.fire(3.0));
    for (k = 0; k < 5; k++) d("poisson.op", p());
    { long a[5]; p.fireArray(5, a); for (k = 0; k < 5; k++) i("poisson.fireArray", a[k]); }
    const std::string t = put(p);
    text("poisson.put", t);
    RandPoisson p2(e);
    std::istringstream is(t);
    p2.get(is);
    text("poisson.get", put(p2));
    for (k = 0; k < 5; k++) i("poisson.restored", p2.fire());
  }
  {
    const double ms[] = {0.0, 3.0, 9.99, 10.0, 37.3, 94.9, 99.9, 100.0, 250.0, 1e4, 3e9};
    for (double m : ms) for (k = 0; k < 10; k++) i("poissonQ.shoot", RandPoissonQ::shoot(m));
    for (double m : ms) for (k = 0; k < 10; k++) i("poissonQ.shootE", RandPoissonQ::shoot(&e, m));
    RandPoissonQ q(e, 150.0);
    for (k = 0; k < 10; k++) i("poissonQ.fire", q.fire());
    for (k = 0; k < 5; k++) i("poissonQ.fireM", q.fire(55.5));
    { long a[5]; q.fireArray(5, a); for (k = 0; k < 5; k++) i("poissonQ.fireArray", a[k]); }
    for (k = 0; k < 5; k++) d("poissonQ.op", q());
    RandPoissonQ q2(e, 42.0);
    for (k = 0; k < 10; k++) i("poissonQ.fireSmall", q2.fire());
    const std::string t = put(q);
    text("poissonQ.put", t);
    RandPoissonQ q3(e);
    std::istringstream is(t);
    q3.get(is);
    text("poissonQ.get", put(q3));
    i("poissonQ.boundary", RandPoissonQ::tableBoundary());
  }
  {
    const double ms[] = {5.0, 50.0, 99.0, 100.0, 150.0, 1e4};
    for (double m : ms) for (k = 0; k < 10; k++) i("poissonT.shoot", RandPoissonT::shoot(m));
    for (double m : ms) for (k = 0; k < 10; k++) i("poissonT.shootE", RandPoissonT::shoot(&e, m));
    RandPoissonT p(e, 64.0);
    for (k = 0; k < 10; k++) i("poissonT.fire", p.fire());
    RandPoissonT p2(e, 640.0);
    for (k = 0; k < 10; k++) i("poissonT.fireLarge", p2.fire());
    for (k = 0; k < 5; k++) d("poissonT.op", p());
    const std::string t = put(p);
    text("poissonT.put", t);
    RandPoissonT p3(e);
    std::istringstream is(t);
    p3.get(is);
    text("poissonT.get", put(p3));
  }

  // ---- RandBinomial
  {
    const long ns[] = {10, 10, 100, 1000, 1000000, 50, 7};
    const double ps[] = {0.3, 0.9, 0.5, 0.02, 0.4, 0.0, 1.0};
    for (int j = 0; j < 7; j++) for (k = 0; k < 10; k++) d("binomial.shoot", RandBinomial::shoot(ns[j], ps[j]));
    for (k = 0; k < 10; k++) d("binomial.shootDefault", RandBinomial::shoot());
    for (int j = 0; j < 7; j++) for (k = 0; k < 10; k++) d("binomial.shootE", RandBinomial::shoot(&e, ns[j], ps[j]));
    RandBinomial b(e, 200, 0.75);
    for (k = 0; k < 10; k++) d("binomial.fire", b.fire());
    for (k = 0; k < 5; k++) d("binomial.fireNP", b.fire(30, 0.1));
    for (k = 0; k < 5; k++) d("binomial.op", b());
    const std::string t = put(b);
    text("binomial.put", t);
    RandBinomial b2(e);
    std::istringstream is(t);
    b2.get(is);
    text("binomial.get", put(b2));
  }

  // ---- RandLandau
  for (k = 0; k < 20; k++) d("landau.shoot", RandLandau::shoot());
  { double a[7]; RandLandau::shootArray(7, a); for (k = 0; k < 7; k++) d("landau.shootArray", a[k]); }
  for (k = 0; k < 10; k++) d("landau.shootE", RandLandau::shoot(&e));
  {
    RandLandau l(e);
    for (k = 0; k < 10; k++) d("landau.fire", l.fire());
    for (k = 0; k < 5; k++) d("landau.op", l());
    const std::string t = put(l);
    text("landau.put", t);
    RandLandau l2(e);
    std::istringstream is(t);
    l2.get(is);
    text("landau.get", put(l2));
  }

  // ---- RandGeneral
  {
    double pdf[10] = {1, 2, 4, 0, 3, 5.5, 0.25, 0, 7, 1};
    RandGeneral g0(e, pdf, 10, 0);
    for (k = 0; k < 20; k++) d("general.fire0", g0.fire());
    RandGeneral g1(e, pdf, 10, 1);
    for (k = 0; k < 20; k++) d("general.fire1", g1.fire());
    for (k = 0; k < 10; k++) d("general.shootE", g0.shoot(&e));
    for (k = 0; k < 10; k++) d("general.shoot", g0.shoot());
    { double a[5]; g1.fireArray(5, a); for (k = 0; k < 5; k++) d("general.fireArray", a[k]); }
    RandGeneral gs(pdf, 10);
    for (k = 0; k < 10; k++) d("general.static", gs.fire());
    double none[3] = {0, 0, 0};
    RandGeneral gf(e, none, 3);
    for (k = 0; k < 5; k++) d("general.flat", gf.fire());
    const std::string t = put(g0);
    text("general.put", t);
    RandGeneral g2(e, pdf, 1);
    std::istringstream is(t);
    g2.get(is);
    text("general.get", put(g2));
    for (k = 0; k < 5; k++) d("general.restored", g2.fire());
  }

  // ---- RandSkewNormal
  {
    const double ks[] = {0.0, 1.0, -3.0, 10.0};
    for (k = 0; k < 10; k++) d("skew.shoot", RandSkewNormal::shoot());
    for (double kk : ks) for (k = 0; k < 10; k++) d("skew.shootK", RandSkewNormal::shoot(kk));
    for (double kk : ks) for (k = 0; k < 10; k++) d("skew.shootE", RandSkewNormal::shoot(&e, kk));
    RandSkewNormal s(e, 2.0);
    for (k = 0; k < 10; k++) d("skew.fire", s.fire());
    for (k = 0; k < 5; k++) d("skew.op", s());
    const std::string t = put(s);
    text("skew.put", t);
    RandSkewNormal s2(e);
    std::istringstream is(t);
    s2.get(is);
    text("skew.get", put(s2));
  }

  // ---- StaticRandomStates: the same kind of engine, then another
  {
    std::ostringstream os; StaticRandomStates::save(os); text("static.save", os.str());
    for (k = 0; k < 5; k++) d("static.after", RandGauss::shoot());
    for (k = 0; k < 5; k++) i("static.afterBit", RandFlat::shootBit());
    std::istringstream is(os.str()); StaticRandomStates::restore(is);
    for (k = 0; k < 5; k++) d("static.restored", RandGauss::shoot());
    for (k = 0; k < 5; k++) i("static.restoredBit", RandFlat::shootBit());
    HepJamesRandom je(5);
    HepRandom::setTheEngine(&je);
    std::istringstream is2(os.str()); StaticRandomStates::restore(is2);
    text("static.engine", HepRandom::getTheEngine()->name());
    for (k = 0; k < 5; k++) d("static.restored2", RandGauss::shoot());
    HepRandom::setTheEngine(&se);
  }
  for (k = 0; k < 5; k++) d("end.flat", RandFlat::shoot());
  return 0;
}

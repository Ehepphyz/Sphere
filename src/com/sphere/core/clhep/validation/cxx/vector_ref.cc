// What CLHEP's Vector package computes, value by value, as raw bits: the reference the Java port
// is compared with. Its twin is com.sphere.core.clhep.validation.VectorReference.
#include "CLHEP/Vector/ThreeVector.h"
#include "CLHEP/Vector/TwoVector.h"
#include "CLHEP/Vector/LorentzVector.h"
#include "CLHEP/Vector/Rotation.h"
#include "CLHEP/Vector/RotationX.h"
#include "CLHEP/Vector/RotationY.h"
#include "CLHEP/Vector/RotationZ.h"
#include "CLHEP/Vector/Boost.h"
#include "CLHEP/Vector/BoostX.h"
#include "CLHEP/Vector/BoostY.h"
#include "CLHEP/Vector/BoostZ.h"
#include "CLHEP/Vector/LorentzRotation.h"
#include "CLHEP/Vector/AxisAngle.h"
#include "CLHEP/Vector/EulerAngles.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <sstream>
#include <iostream>

using namespace CLHEP;

static uint64_t state = 88172645463325252ULL;
// xorshift64: the same numbers in Java
static double rnd() {
  state ^= state << 13; state ^= state >> 7; state ^= state << 17;
  return (double)(state >> 11) * (1.0 / 9007199254740992.0);
}
static double sym(double s) { return (2 * rnd() - 1) * s; }

static Hep3Vector rv(double s) { double x = sym(s); double y = sym(s); double z = sym(s); return Hep3Vector(x, y, z); }
static double pos(double s, double o) { return rnd() * s + o; }

static void d(const char* n, double x) {
  uint64_t b; std::memcpy(&b, &x, 8);
  std::printf("%s %016llx\n", n, (unsigned long long)b);
}
static void i(const char* n, long v) { std::printf("%s %ld\n", n, v); }
static void v3(const char* n, const Hep3Vector& v) {
  std::string s(n); d((s + ".x").c_str(), v.x()); d((s + ".y").c_str(), v.y()); d((s + ".z").c_str(), v.z());
}
static void v4(const char* n, const HepLorentzVector& v) {
  std::string s(n); v3(n, v.vect()); d((s + ".t").c_str(), v.t());
}
static void r3(const char* n, const HepRotation& r) {
  std::string s(n);
  d((s + ".xx").c_str(), r.xx()); d((s + ".xy").c_str(), r.xy()); d((s + ".xz").c_str(), r.xz());
  d((s + ".yx").c_str(), r.yx()); d((s + ".yy").c_str(), r.yy()); d((s + ".yz").c_str(), r.yz());
  d((s + ".zx").c_str(), r.zx()); d((s + ".zy").c_str(), r.zy()); d((s + ".zz").c_str(), r.zz());
}
static void r4(const char* n, const HepLorentzRotation& r) {
  std::string s(n);
  for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) {
    char k[64]; std::snprintf(k, sizeof k, "%s.%d%d", n, a, b); d(k, r(a, b));
  }
}
static void text(const char* n, const std::string& t) { std::printf("%s [%s]\n", n, t.c_str()); }

int main() {
  for (int k = 0; k < 400; k++) {
    Hep3Vector a = rv(10); Hep3Vector b = rv(5);
    if (k % 50 == 7) { double zz = sym(3); a.set(0, 0, zz); }
    if (k % 50 == 9) b.set(0, 0, 0);
    v3("a", a);
    d("mag", a.mag()); d("theta", a.theta()); d("phi", a.phi()); d("eta", a.eta()); d("perp", a.perp());
    d("cosTheta", a.cosTheta()); d("cos2Theta", a.cos2Theta()); d("perpb", a.perp(b));
    d("angle", a.angle(b)); d("cosThetab", a.cosTheta(b)); d("cos2Thetab", a.cos2Theta(b));
    d("deltaR", a.deltaR(b)); d("deltaPhi", a.deltaPhi(b)); d("howNear", a.howNear(b));
    d("howParallel", a.howParallel(b)); d("howOrthogonal", a.howOrthogonal(b));
    i("isParallel", a.isParallel(b, 0.3)); i("isOrthogonal", a.isOrthogonal(b, 0.3)); i("compare", a.compare(b));
    d("polarAngle", a.polarAngle(b)); d("dot", a.dot(b));
    v3("cross", a.cross(b)); v3("unit", a.unit()); v3("orth", a.orthogonal());
    if (b.mag2() > 0) {
      v3("project", a.project(b)); v3("perpPart", a.perpPart(b)); d("etaRef", a.eta(b));
      d("azimRef", a.azimAngle(b, Hep3Vector(1, 2, 3))); d("polarRef", a.polarAngle(b, Hep3Vector(0.3, -1, 2)));
    }
    Hep3Vector c(a); { double t1 = sym(4); c.rotateX(t1); } { double t1 = sym(4); c.rotateY(t1); } { double t1 = sym(4); c.rotateZ(t1); } v3("rotXYZ", c);
    Hep3Vector u(b.mag2() > 0 ? b.unit() : Hep3Vector(0, 0, -1)); c = a; c.rotateUz(u); v3("rotateUz", c);
    c = a; { double t1 = sym(3); Hep3Vector t2 = rv(1); c.rotate(t1, t2); } v3("rotateAngleAxis", c);
    c = a; { Hep3Vector t2 = rv(1); double t1 = sym(3); c.rotate(t2, t1); } v3("rotateAxisDelta", c);
    c = a; { double t1 = sym(3); double t2 = sym(3); double t3 = sym(3); c.rotate(t1, t2, t3); } v3("rotateEuler", c);
    { double t1 = sym(4) + 5; double t2 = sym(3); double t3 = sym(3); c.setREtaPhi(t1, t2, t3); } v3("setREtaPhi", c);
    { double t1 = pos(5, 0); double t2 = pos(3, 0); double t3 = sym(3); c.setRThetaPhi(t1, t2, t3); } v3("setRThetaPhi", c);
    { double t1 = pos(5, 0); double t2 = sym(3); double t3 = sym(3); c.setRhoPhiZ(t1, t2, t3); } v3("setRhoPhiZ", c);
    { double t1 = pos(5, 0.1); double t2 = sym(3); double t3 = sym(3); c.setRhoPhiEta(t1, t2, t3); } v3("setRhoPhiEta", c);
    c = a; { double t1 = pos(3, 0); c.setTheta(t1); } v3("setTheta", c); { double t1 = sym(3); c.setPhi(t1); } v3("setPhi", c);
    { double t1 = pos(4, 0.5); c.setMag(t1); } v3("setMag", c); { double t1 = pos(3, 0); c.setPerp(t1); } v3("setPerp", c);
    { double t1 = sym(3); c.setEta(t1); } v3("setEta", c); { double t1 = sym(2); c.setCylEta(t1); } v3("setCylEta", c); { double t1 = pos(3, 0.01); c.setCylTheta(t1); } v3("setCylTheta", c);
    Hep3Vector small = rv(0.5);
    d("beta", small.beta()); d("gamma", small.gamma()); d("rapidity", small.rapidity()); d("coLinear", small.coLinearRapidity());
    if (b.mag2() > 0) d("rapidityRef", small.rapidity(b));
    { double t1 = pos(1, 0.5); v3("div", a / t1); }
    {
      std::ostringstream os; os << a; text("print3", os.str());
      std::istringstream is(os.str()); Hep3Vector back; is >> back; v3("read3", back);
    }

    // Two-vectors
    double p1 = sym(4); double p2y = sym(4); double q1 = sym(4); double q2 = sym(4); Hep2Vector p(p1, p2y), q(q1, q2);
    d("2.phi", p.phi()); d("2.mag", p.mag()); d("2.angle", p.angle(q)); d("2.howParallel", p.howParallel(q));
    i("2.isOrthogonal", p.isOrthogonal(q, 0.2)); Hep2Vector p2(p); { double t1 = sym(3); p2.rotate(t1); } d("2.rx", p2.x()); d("2.ry", p2.y());
    { double t1 = pos(3, 0); double t2 = sym(3); p2.setPolar(t1, t2); } d("2.px", p2.x()); d("2.py", p2.y()); Hep2Vector uu = q.unit(); d("2.ux", uu.x()); d("2.uy", uu.y());

    // Rotations
    double phi = sym(3); double theta = pos(3.1, 0); double psi = sym(3);
    HepRotation R(phi, theta, psi); r3("R", R);
    HepEulerAngles ea = R.eulerAngles(); d("ea.phi", ea.phi()); d("ea.theta", ea.theta()); d("ea.psi", ea.psi());
    d("R.phi", R.phi()); d("R.theta", R.theta()); d("R.psi", R.psi()); d("R.delta", R.delta()); v3("R.axis", R.axis());
    d("R.phiX", R.phiX()); d("R.thetaY", R.thetaY()); d("R.norm2", R.norm2());
    double ang; Hep3Vector ax; R.getAngleAxis(ang, ax); d("R.angle", ang); v3("R.angleAxis", ax);
    Hep3Vector sax = rv(1); double sang = sym(3); HepRotation S(sax, sang); r3("S", S);
    r3("RS", R * S); r3("Rinv", R.inverse()); d("RSdist", R.distance2(S)); i("RScompare", R.compare(S));
    HepRotation T(R); { double t1 = sym(2); T.rotateX(t1); } { double t1 = sym(2); T.rotateY(t1); } { double t1 = sym(2); T.rotateZ(t1); } { double t1 = sym(2); Hep3Vector t2 = rv(1); T.rotate(t1, t2); }
    r3("T", T);
    double wa = sym(1e-9); double wb = sym(1e-9); double wc = sym(1e-9); HepRotation W(Hep3Vector(1, 1e-9, wa), Hep3Vector(wb, 1, 0), Hep3Vector(0, wc, 1)); r3("Wcols", W);
    HepRotation Q(R); Q.rotateX(1e-7); { double t1 = sym(3); Q.setPhi(t1); } { double t1 = pos(3, 0); Q.setTheta(t1); } { double t1 = sym(3); Q.setPsi(t1); } r3("Qset", Q);
    { double t1 = sym(1); double t2 = sym(1); Q.setAxis(Hep3Vector(t1, t2, 1)); } r3("Qaxis", Q); { double t1 = sym(3); Q.setDelta(t1); } r3("Qdelta", Q);
    HepRotation E(R); E.rectify(); r3("Erect", E);
    double rxd = sym(7); double ryd = sym(7); double rzd = sym(7); HepRotationX RX(rxd); HepRotationY RY(ryd); HepRotationZ RZ(rzd);
    r3("RX", HepRotation(RX)); r3("RY", HepRotation(RY)); r3("RZ", HepRotation(RZ));
    d("RX.d", RX.delta()); d("RX.phi", RX.phi()); d("RY.psi", RY.psi()); d("RZ.phi", RZ.phi());
    r3("R*RX", R * RX); r3("R*RY", R * RY); r3("R*RZ", R * RZ); r3("RX*R", RX * R); r3("RY*R", RY * R); r3("RZ*R", RZ * R);
    double rx2d = sym(4); HepRotationX RX2 = RX * HepRotationX(rx2d); d("RX2.d", RX2.delta()); d("RX2.yz", RX2.yz());
    d("RX.dist", RX.distance2(R)); d("RY.dist", RY.distance2(R)); d("RZ.dist", RZ.distance2(R));
    v3("Ra", R * a); v3("RXa", RX * a); v3("RYa", RY * a); v3("RZa", RZ * a);
    Hep3Vector aax = rv(1); double aad = sym(3); HepAxisAngle aa(aax, aad); HepAxisAngle bb = R.axisAngle();
    d("aa.dist", aa.howNear(bb)); { double t1 = sym(3); double t2 = pos(1, 0); double t3 = sym(3); d("ea.dist", ea.howNear(HepEulerAngles(t1, t2, t3))); }
    {
      std::ostringstream os; os << R; text("printR", os.str());
      std::ostringstream os2; os2 << RY; text("printRY", os2.str());
      std::ostringstream os3; os3 << aa << " " << ea; text("printAA", os3.str());
    }

    // Four-vectors and boosts
    Hep3Vector wv = rv(5); double wt = pos(10, 9); Hep3Vector zv = rv(5); double zt = pos(10, 9); HepLorentzVector w(wv, wt), z(zv, zt);
    v4("w", w);
    d("m", w.m()); d("m2", w.m2()); d("mt", w.mt()); d("et", w.et()); d("rap", w.rapidity()); d("prap", w.pseudoRapidity());
    d("beta4", w.beta()); d("gamma4", w.gamma()); d("plus", w.plus()); d("minus", w.minus());
    d("invmass", w.invariantMass(z)); d("dot4", w.dot(z)); d("howNear4", w.howNear(z)); d("howNearCM", w.howNearCM(z));
    i("isNearCM", w.isNearCM(z, 0.5)); d("deltaR4", w.deltaR(z)); d("howParallel4", w.howParallel(z));
    d("coLin4", w.coLinearRapidity()); d("et2ref", w.et2(b.mag2() > 0 ? b : Hep3Vector(1, 0, 0)));
    v3("boostVector", w.boostVector()); v3("toCM", w.findBoostToCM(z));
    HepLorentzVector w2(w); { Hep3Vector t = rv(0.4); w2.boost(t.x(), t.y(), t.z()); } v4("boost", w2);
    { double t1 = sym(0.9); w2.boostX(t1); } { double t1 = sym(0.9); w2.boostY(t1); } { double t1 = sym(0.9); w2.boostZ(t1); } v4("boostXYZ", w2);
    { Hep3Vector t2 = rv(1); double t1 = sym(0.9); w2.boost(t2, t1); } v4("boostAxis", w2);
    { double t1 = sym(3); w2.rotateX(t1); } w2.rotateUz(u); v4("rot4", w2);
    v4("rest", w.rest4Vector()); { double t1 = pos(1, 1); v4("div4", w / t1); }
    {
      std::ostringstream os; os << w; text("print4", os.str());
      std::istringstream is(os.str()); HepLorentzVector back; is >> back; v4("read4", back);
    }
    Hep3Vector bv = rv(0.5); Hep3Vector cdir = rv(1); double cb = sym(0.9); HepBoost B(bv.x(), bv.y(), bv.z()), C(cdir, cb);
    v4("Bw", B * w); v3("B.bv", B.boostVector()); d("B.beta", B.beta()); v3("B.dir", B.direction());
    r4("BC", B * C); r4("BR", B * R); d("B.dist", B.distance2(C)); d("B.distR", B.distance2(R)); d("B.norm2", B.norm2());
    HepBoost Bi = B.inverse(); v4("Biw", Bi * w); HepBoost Br(B); Br.rectify(); v3("Brect", Br.boostVector());
    double bxb = sym(0.95); double byb = sym(0.95); double bzb = sym(0.95); HepBoostX BX(bxb); HepBoostY BY(byb); HepBoostZ BZ(bzb);
    v4("BXw", BX * w); v4("BYw", BY * w); v4("BZw", BZ * w); { double t1 = sym(0.9); d("BX2", (BX * HepBoostX(t1)).beta()); }
    r4("BX*R", BX * R); d("B.distBY", B.distance2(BY)); d("BY.distB", BY.distance2(B));
    HepLorentzRotation L(B, R); r4("L", L);
    HepBoost lb; HepRotation lr; L.decompose(lb, lr); v3("L.boost", lb.boostVector()); r3("L.rot", lr);
    HepRotation lr2; HepBoost lb2; L.decompose(lr2, lb2); v3("L.boost2", lb2.boostVector()); r3("L.rot2", lr2);
    HepLorentzRotation M(R, C); r4("M", M); r4("LM", L * M); r4("Linv", L.inverse()); d("L.dist", L.distance2(M));
    d("L.norm2", L.norm2()); d("L.distB", L.distance2(B)); d("L.distR", L.distance2(R));
    HepLorentzRotation N(L); { double t1 = sym(2); N.rotateX(t1); } { double t1 = sym(2); N.rotateY(t1); } { double t1 = sym(2); N.rotateZ(t1); } { double t1 = sym(0.5); N.boostX(t1); } { double t1 = sym(0.5); N.boostY(t1); }
    { double t1 = sym(0.5); N.boostZ(t1); } { double t1 = sym(2); N.rotate(t1, Hep3Vector(1, 2, 3)); } { Hep3Vector t = rv(0.3); N.boost(t.x(), t.y(), t.z()); } r4("N", N);
    HepLorentzRotation P(N); P.rectify(); r4("Prect", P);
    HepLorentzRotation Cols(L.col1(), L.col2(), L.col3(), L.col4()); r4("Cols", Cols);
    v4("Lw", L * w); HepLorentzVector w3(w); w3 *= L; v4("w*=L", w3); r4("R*L", R * L);
    {
      std::ostringstream os; os << L << B << BX; text("printL", os.str());
    }
  }
  return 0;
}

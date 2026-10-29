// CLHEP's Geometry in double and in float: the vectors' lengths, angles and rotations, points, normals,
// planes, every transformation and their algebra, and the text in and out. Inputs from xorshift64, one
// draw per statement. Its twin: validation.GeometryReference.
#include "CLHEP/Geometry/Point3D.h"
#include "CLHEP/Geometry/Vector3D.h"
#include "CLHEP/Geometry/Normal3D.h"
#include "CLHEP/Geometry/Plane3D.h"
#include "CLHEP/Geometry/Transform3D.h"
#include "CLHEP/Vector/Rotation.h"
#include "CLHEP/Vector/ThreeVector.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <iomanip>
#include <sstream>
#include <string>

using namespace HepGeom;

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
static void v3(const std::string& n, double x, double y, double z) {
  std::printf("%s %016llx %016llx %016llx\n", n.c_str(), (unsigned long long)bits(x), (unsigned long long)bits(y),
              (unsigned long long)bits(z));
}
template <class T> static void pv(const std::string& n, const BasicVector3D<T>& v) { v3(n, v.x(), v.y(), v.z()); }
template <class T> static void pl(const std::string& n, const Plane3D<T>& p) {
  std::printf("%s %016llx %016llx %016llx %016llx\n", n.c_str(), (unsigned long long)bits(p.a()),
              (unsigned long long)bits(p.b()), (unsigned long long)bits(p.c()), (unsigned long long)bits(p.d()));
}
static void pt(const std::string& n, const Transform3D& t) {
  std::printf("%s", n.c_str());
  for (int r = 0; r < 3; r++) for (int c = 0; c < 4; c++) std::printf(" %016llx", (unsigned long long)bits(t(r, c)));
  std::printf("\n");
}

template <class T> static void basics(const std::string& tag) {
  for (int k = 0; k < 300; k++) {
    double c[8];
    for (int j = 0; j < 8; j++) c[j] = sym(10.0);
    const double ang = sym(4.0);
    const double s = sym(3.0);
    Vector3D<T> v((T)c[0], (T)c[1], (T)c[2]);
    Vector3D<T> w((T)c[3], (T)c[4], (T)c[5]);
    if (k % 10 == 0) v.set(0, 0, (T)c[2]);
    if (k % 10 == 1) w.set(0, 0, 0);
    if (k % 17 == 3) v.set(0, 0, 0);
    if (k % 23 == 5) v.set((T)c[0], (T)c[1], 0);
    const std::string n = tag;
    d(n + ".perp2", v.perp2()); d(n + ".perp", v.perp()); d(n + ".mag2", v.mag2()); d(n + ".mag", v.mag());
    d(n + ".phi", v.phi()); d(n + ".theta", v.theta()); d(n + ".cosTheta", v.cosTheta());
    d(n + ".eta", v.pseudoRapidity()); d(n + ".dot", v.dot(w)); pv(n + ".cross", v.cross(w));
    d(n + ".perp2w", v.perp2(w)); d(n + ".perpw", v.perp(w)); d(n + ".angle", v.angle(w));
    pv(n + ".unit", v.unit()); pv(n + ".orthogonal", v.orthogonal());
    { Vector3D<T> u(v); u.setPerp((T)s); pv(n + ".setPerp", u); }
    { Vector3D<T> u(v); u.setMag((T)s); pv(n + ".setMag", u); }
    { Vector3D<T> u(v); u.setPhi((T)ang); pv(n + ".setPhi", u); }
    { Vector3D<T> u(v); u.setTheta((T)ang); pv(n + ".setTheta", u); }
    { Vector3D<T> u(v); u.setEta((T)s); pv(n + ".setEta", u); }
    { Vector3D<T> u(v); u.rotateX((T)ang); pv(n + ".rotateX", u); }
    { Vector3D<T> u(v); u.rotateY((T)ang); pv(n + ".rotateY", u); }
    { Vector3D<T> u(v); u.rotateZ((T)ang); pv(n + ".rotateZ", u); }
    { Vector3D<T> u(v); u.rotate((T)ang, w); pv(n + ".rotate", u); }
    { Vector3D<T> u(v); u.rotate((T)0, w); pv(n + ".rotate0", u); }
    pv(n + ".plus", v + w); pv(n + ".minus", v - w); pv(n + ".neg", -v); pv(n + ".times", v * s);
    pv(n + ".stimes", s * v); pv(n + ".div", v / s); d(n + ".vdot", v * w);
    { Vector3D<T> u(v); u += w; pv(n + ".pa", u); }
    { Vector3D<T> u(v); u -= w; pv(n + ".ma", u); }
    { Vector3D<T> u(v); u *= s; pv(n + ".ta", u); }
    { Vector3D<T> u(v); u /= s; pv(n + ".da", u); }
    i(n + ".eq", v == w); i(n + ".eqself", v == v); i(n + ".ne", v != w);
    d(n + ".get", v(1)); d(n + ".at", v[2]);

    Point3D<T> p((T)c[0], (T)c[1], (T)c[2]);
    Point3D<T> q((T)c[3], (T)c[4], (T)c[5]);
    Point3D<T> r((T)c[6], (T)c[7], (T)c[0]);
    d(n + ".dist2", p.distance2()); d(n + ".dist2q", p.distance2(q));
    d(n + ".dist", p.distance()); d(n + ".distq", p.distance(q));

    const Transform3D tr = Translate3D(c[0], c[1], c[2]) * RotateZ3D(ang) * Scale3D(1.5, 0.5, 2.0);
    Normal3D<T> nn((T)c[3], (T)c[4], (T)c[5]);
    pv(n + ".mp", tr * p); pv(n + ".mv", tr * v); pv(n + ".mn", tr * nn);
    { Point3D<T> u(p); u.transform(tr); pv(n + ".tp", u); }
    { Vector3D<T> u(v); u.transform(tr); pv(n + ".tv", u); }
    { Normal3D<T> u(nn); u.transform(tr); pv(n + ".tn", u); }

    Plane3D<T> pa((T)c[0], (T)c[1], (T)c[2], (T)c[6]);
    Plane3D<T> pn(nn, p);
    Plane3D<T> p3(p, q, r);
    pl(n + ".planeABCD", pa); pl(n + ".planeNP", pn); pl(n + ".plane3", p3);
    { Plane3D<T> u(pa); u.normalize(); pl(n + ".normalize", u); }
    d(n + ".pdist", pa.distance(q)); pv(n + ".ppoint", pa.point(q)); pv(n + ".ppoint0", pa.point());
    pv(n + ".pnormal", pa.normal()); i(n + ".peq", pa == pn); i(n + ".pne", pa != pn); i(n + ".peqself", pa == pa);
    { Plane3D<T> u(p3); u.transform(tr); pl(n + ".ptransform", u); }
  }
}

static void transforms() {
  for (int k = 0; k < 200; k++) {
    double c[9];
    for (int j = 0; j < 9; j++) c[j] = sym(5.0);
    const double a = sym(3.5);
    const double b = sym(3.5);
    const double g = sym(3.5);
    const std::string n = "tr";
    const Point3D<double> p1(c[0], c[1], c[2]), p2(c[3], c[4], c[5]), p3(c[6], c[7], c[8]);
    const Vector3D<double> ax(c[3], c[4], c[5]);
    pt(n + ".rot", Rotate3D(a, p1, p2));
    pt(n + ".rotv", Rotate3D(a, ax));
    pt(n + ".rot0", Rotate3D(0, p1, p2));
    pt(n + ".rotx", RotateX3D(a)); pt(n + ".roty", RotateY3D(b)); pt(n + ".rotz", RotateZ3D(g));
    pt(n + ".tra", Translate3D(c[0], c[1], c[2]));
    pt(n + ".trav", Translate3D(CLHEP::Hep3Vector(c[3], c[4], c[5])));
    pt(n + ".trx", TranslateX3D(c[6])); pt(n + ".try", TranslateY3D(c[7])); pt(n + ".trz", TranslateZ3D(c[8]));
    pt(n + ".ref", Reflect3D(c[0], c[1], c[2], c[3]));
    pt(n + ".refnp", Reflect3D(Normal3D<double>(c[4], c[5], c[6]), p1));
    pt(n + ".refx", ReflectX3D(c[7])); pt(n + ".refy", ReflectY3D(c[8])); pt(n + ".refz", ReflectZ3D());
    pt(n + ".sc", Scale3D(c[0], c[1], c[2])); pt(n + ".sc1", Scale3D(c[3]));
    pt(n + ".scx", ScaleX3D(c[4])); pt(n + ".scy", ScaleY3D(c[5])); pt(n + ".scz", ScaleZ3D(c[6]));
    const Transform3D t1 = Translate3D(c[0], c[1], c[2]) * Rotate3D(a, ax) * Scale3D(c[6], c[7], c[8]);
    const Transform3D t2 = RotateX3D(a) * RotateY3D(b) * RotateZ3D(g) * TranslateZ3D(c[8]);
    pt(n + ".t1", t1); pt(n + ".t2", t2); pt(n + ".t12", t1 * t2); pt(n + ".inv1", t1.inverse());
    pt(n + ".inv2", t2.inverse()); pt(n + ".invinv", t1.inverse().inverse());
    i(n + ".near", t1.isNear(t1.inverse().inverse())); i(n + ".neartol", t1.isNear(t2, 100.0));
    i(n + ".eq", t1 == t2); i(n + ".eqself", t1 == t1); i(n + ".ne", t1 != t2);
    {
      Scale3D sc; Rotate3D ro; Translate3D tl;
      t1.getDecomposition(sc, ro, tl);
      pt(n + ".dsc", sc); pt(n + ".dro", ro); pt(n + ".dtl", tl);
    }
    {
      const CLHEP::HepRotation rr = t2.getRotation();
      v3(n + ".grot1", rr.xx(), rr.xy(), rr.xz()); v3(n + ".grot2", rr.yx(), rr.yy(), rr.yz());
      v3(n + ".grot3", rr.zx(), rr.zy(), rr.zz());
      const CLHEP::Hep3Vector tv = t1.getTranslation();
      v3(n + ".gtra", tv.x(), tv.y(), tv.z());
    }
    {
      CLHEP::HepRotation hr;
      hr.rotateX(a); hr.rotateY(b); hr.rotateZ(g);
      pt(n + ".fromrot", Transform3D(hr, CLHEP::Hep3Vector(c[0], c[1], c[2])));
      pt(n + ".rotfromrot", Rotate3D(hr));
    }
    pt(n + ".six", Transform3D(p1, p2, p3, Point3D<double>(c[8], c[7], c[6]),
                               Point3D<double>(c[5], c[4], c[3]), Point3D<double>(c[2], c[1], c[0])));
    pt(n + ".four", Rotate3D(p1, p2, p3, Point3D<double>(c[0], c[2], c[1])));
    std::string row;
    for (int r = 0; r <= 4; r++) for (int cc = -1; cc <= 4; cc++) row += (t1(r, cc) == 0 ? "0" : "x");
    text(n + ".elements", row);
    d(n + ".bracket", t1[1][3]);
  }
  pt("tr.parallel", Transform3D(Point3D<double>(0, 0, 0), Point3D<double>(1, 0, 0), Point3D<double>(2, 0, 0),
                                Point3D<double>(0, 0, 0), Point3D<double>(0, 1, 0), Point3D<double>(0, 0, 1)));
  pt("tr.unequal", Transform3D(Point3D<double>(0, 0, 0), Point3D<double>(1, 0, 0), Point3D<double>(0, 1, 0),
                               Point3D<double>(0, 0, 0), Point3D<double>(1, 0, 0), Point3D<double>(1, 1, 0)));
  pt("tr.singular", Scale3D(0).inverse());
  pt("tr.reflect0", Reflect3D(0, 0, 0, 1));
  pt("tr.rotaxis0", Rotate3D(1.0, Point3D<double>(1, 2, 3), Point3D<double>(1, 2, 3)));
  pt("tr.identity", Transform3D::Identity);
  { Transform3D t = Translate3D(1, 2, 3); t.setIdentity(); pt("tr.setIdentity", t); }
}

template <class T> static void io(const std::string& tag) {
  Vector3D<T> v((T)(1.0 / 3), (T)-2.5e-7, (T)12345.678);
  { std::ostringstream os; os << v; text(tag + ".out6", os.str()); }
  { std::ostringstream os; os << std::setprecision(17) << v; text(tag + ".out17", os.str()); }
  { std::ostringstream os; os << std::scientific << std::setprecision(3) << v; text(tag + ".outsci", os.str()); }
  { std::ostringstream os; os << Plane3D<T>((T)1, (T)-2, (T)0.5, (T)3.25); text(tag + ".plane", os.str()); }
  { std::ostringstream os; os << Point3D<T>((T)0.1, (T)0.2, (T)0.3) << Normal3D<T>(); text(tag + ".points", os.str()); }
  const char* ins[] = {"(1.5,-2.25e3,7)", " ( 1 , 2 , 3 ) ", "1,2,3)", "(1 2 3)", "(1,2,3", "(a,b,c)",
                       "(0.1,0.2,0.3)", "(1e39,1,1)", "(1e309,1,1)", "(,1,1)", "(1,2,3)(4,5,6)",
                       "(3.4028235e38,1.17549435e-38,-0.0)"};
  for (const char* s : ins) {
    Vector3D<T> u((T)9, (T)9, (T)9);
    std::istringstream is(s);
    is >> u;
    i(tag + ".infail", is.fail() ? 1 : 0);
    pv(tag + ".in", u);
    if (std::string(s) == "(1,2,3)(4,5,6)") { is >> u; i(tag + ".in2fail", is.fail() ? 1 : 0); pv(tag + ".in2", u); }
  }
}

int main() {
  basics<double>("d");
  basics<float>("f");
  transforms();
  io<double>("iod");
  io<float>("iof");
  return 0;
}

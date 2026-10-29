// CLHEP's Evaluator: expressions written by hand (errors, their positions, the C library's errno) and
// random ones from xorshift64, the same in both programs. Its twin: validation.EvaluatorReference.
#include "CLHEP/Evaluator/Evaluator.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <limits>
#include <string>

static uint64_t state = 88172645463325252ULL;
static double rnd() {
  state ^= state << 13; state ^= state >> 7; state ^= state << 17;
  return (double)(state >> 11) * (1.0 / 9007199254740992.0);
}
static void d(const char* n, double x) {
  uint64_t b; std::memcpy(&b, &x, 8);
  std::printf("%s %016llx\n", n, (unsigned long long)b);
}

static double f0() { return 42.0; }
static double f1(double a) { return a * 2 + 1; }
static double f2(double a, double b) { return a - b; }
static double f3(double a, double b, double c) { return a * b - c; }
static double f4(double a, double b, double c, double d) { return a + b * c - d; }
static double f5(double a, double b, double c, double d, double e) { return a - b + c * d / e; }

static HepTool::Evaluator* ev;

static void show(const char* expr) {
  const double v = ev->evaluate(expr);
  uint64_t b; std::memcpy(&b, &v, 8);
  std::printf("[%s] status %d pos %d value %016llx name [%s]\n", expr, ev->status(), ev->error_position(),
              (unsigned long long)b, ev->error_name().c_str());
}

static const char* const NAMES[] = {"pi", "e", "mm", "cm", "GeV", "MeV", "deg", "x", "y", "zz", "tesla", "ns"};
static const char* const OPS[] = {"+", "-", "*", "/", "^", "**", "==", "!=", "<", "<=", ">", ">=", "&&", "||"};
static const char* const FUNS[] = {"sin", "cos", "sqrt", "exp", "log", "abs", "atan", "f1"};
static const char* const FUNS2[] = {"pow", "atan2", "min", "max", "f2"};

static std::string gen(int depth) {
  const double r = rnd();
  if (depth > 3 || r < 0.3) {
    const double t = rnd();
    if (t < 0.45) {
      const int n = (int)(rnd() * 100);
      const double frac = rnd();
      std::string s = std::to_string(n);
      if (frac < 0.3) s += "." + std::to_string((int)(rnd() * 10));
      else if (frac < 0.4) s += "e" + std::to_string((int)(rnd() * 5) - 2);
      return s;
    }
    if (t < 0.9) return NAMES[(int)(rnd() * 12)];
    const double g = rnd();
    if (g < 0.3) return "";
    if (g < 0.6) return "(";
    return ")";
  }
  const double k = rnd();
  if (k < 0.45) {
    const std::string a = gen(depth + 1);
    const std::string op = OPS[(int)(rnd() * 14)];
    const std::string sp = rnd() < 0.3 ? " " : "";
    const std::string b = gen(depth + 1);
    return a + sp + op + sp + b;
  }
  if (k < 0.6) {
    const std::string a = gen(depth + 1);
    return "(" + a + ")";
  }
  if (k < 0.7) {
    const std::string a = gen(depth + 1);
    return (rnd() < 0.5 ? "-" : "+") + a;
  }
  if (k < 0.85) {
    const std::string f = FUNS[(int)(rnd() * 8)];
    const std::string a = gen(depth + 1);
    return f + "(" + a + ")";
  }
  const std::string f = FUNS2[(int)(rnd() * 5)];
  const std::string a = gen(depth + 1);
  const std::string b = gen(depth + 1);
  return f + "(" + a + "," + b + ")";
}

int main() {
  HepTool::Evaluator e;
  ev = &e;
  // before anything is set
  show("1+2");
  show("pi");
  show("");
  show("   ");
  e.setStdMath();
  std::printf("std status %d\n", e.status());
  e.setSystemOfUnits();
  std::printf("units status %d\n", e.status());

  const char* hand[] = {
    "1+2*3", "(1+2)*3", "2^3^2", "2**3**2", "-2^2", "2^-2", "-(-3)", "+-+2", "2*-3", "2--3", "2- -3",
    "1<2<3", "3>2>1", "1==1==1", "1!=2", "0||0", "0||3", "2&&0", "2&&3", "1+1==2&&2*2==4", "1 + 2 * (3 - 4) / 5",
    "sin(pi/6)", "cos(0)", "tan(pi/4)", "asin(1)", "acos(-1)", "atan(1)", "atan2(1,1)", "atan2(0,0)",
    "sinh(1)", "cosh(1)", "tanh(1)", "exp(1)", "log(e)", "log10(1000)", "sqrt(2)", "pow(2,10)", "abs(-3)",
    "min(2,3)", "max(2,3)", "min(max(1,2),3)", "f0()", "f1(3)", "f2(10,4)", "f3(2,3,4)", "f4(1,2,3,4)",
    "f5(1,2,3,4,5)", "f5(1,2,f3(1,2,3),4,5)", "x", "y", "zz", "2*x+y", "x/y",
    // errors and their positions
    "(1+2", "1+2)", "((1)", "f2(1,)", "f2(,1)", "f2(1,,2)", "sin()", "f0( )", "sin(1,2)", "unknown(1)",
    "abc", "1 2", "1+*2", "1+", "*1", "()", "(", ")", "1|2", "1&2", "1=2", "!1", "1!2", "1#2", "a b", "sin(1",
    "sin 1", "1.5e", "1e+", "1.2.3", ".", "..", "x.", "1/0", "0/0", "1/(1-1)",
    // the C library's errors
    "sqrt(-1)", "sqrt(-0)", "log(0)", "log(-1)", "log10(0)", "log10(-2)", "asin(2)", "acos(-2)",
    "pow(-8,1/3)", "pow(-8,3)", "pow(0,-1)", "pow(0,0)", "pow(10,400)", "pow(10,-400)", "pow(10,-320)",
    "(-8)^(1/3)", "0^-1", "10^400", "10^-400", "exp(1000)", "exp(-1000)", "exp(-740)", "exp(709)",
    "sinh(1000)", "cosh(-1000)", "sinh(-1000)", "tanh(1000)", "sin(inf)", "cos(-inf)", "tan(inf)",
    "atan(inf)", "exp(inf)", "exp(-inf)", "log(inf)", "sqrt(inf)", "sqrt(-inf)", "pow(inf,2)", "pow(-inf,0.5)",
    "pow(2,inf)", "sqrt(nan)", "nan==nan", "nan!=nan", "nan||0", "nan&&1", "-nan", "sin(nan)", "asin(nan)",
    // numbers as strtod reads them
    "1e-310", "4.9e-324", "2.2250738585072014e-308", "1e308", "1e309", "1e400", "1e-400", "0x1p-1074", "0x1p-1030", "0x1p-1080", "2.2250738585072009e-308", "0x1p3",
    "0x1.8p1", "0x", "0xg", ".5", "5.", "00012", "0.000", "0e5", "1e5", "1E5", "123456789012345678901234567890",
    "0.1+0.2", "3.14159265358979323846", "1e-5*mm",
    // units
    "1*GeV", "3*tesla", "2*kGs", "1*mCi", "1*parsec", "1*eV", "1*barn", "1*atm", "60*minute", "1*year",
    "1*L", "1*cc", "1*mrad", "1*deg", "1*sr", "1*ohm", "1*farad", "1*henry", "1*lux", "1*Gy", "1*Sv",
    "1*kilogray", "1*angstrom", "1*fermi", "1*picobarn", "1*dpt", "1*mg", "1*ps", "1*kHz", "1*kbar"
  };
  e.setVariable("x", 3.5);
  e.setVariable("y", "x*2+1");
  e.setVariable("zz", "y-x");
  e.setVariable("inf", std::numeric_limits<double>::infinity());
  e.setVariable("nan", std::numeric_limits<double>::quiet_NaN());
  e.setFunction("f0", f0);
  e.setFunction("f1", f1);
  e.setFunction("f2", f2);
  e.setFunction("f3", f3);
  e.setFunction("f4", f4);
  e.setFunction("f5", f5);
  for (const char* h : hand) show(h);

  // the dictionary
  e.setVariable("x", 4.0); std::printf("set x again %d\n", e.status());
  e.setVariable(" x ", 5.0); std::printf("set ' x ' %d\n", e.status());
  e.setVariable("2x", 1.0); std::printf("set 2x %d\n", e.status());
  e.setVariable("a-b", 1.0); std::printf("set a-b %d\n", e.status());
  e.setVariable("", 1.0); std::printf("set empty %d\n", e.status());
  e.setVariable("  ", 1.0); std::printf("set blank %d\n", e.status());
  e.setFunction("sin", f1); std::printf("set sin %d\n", e.status());
  e.setFunction("sin", f2); std::printf("set sin/2 %d\n", e.status());
  show("sin(1)");
  show("sin(1,2)");
  show("x");
  std::printf("find %d %d %d %d %d %d\n", e.findVariable("x"), e.findVariable(" x"), e.findVariable("q"),
              e.findVariable("1sin"), e.findFunction("sin", 1), e.findFunction("sin", 3));
  e.removeVariable("x"); show("x");
  e.removeFunction("sin", 2); show("sin(1,2)");
  e.setVariable("bad", "1+"); show("bad");
  e.setVariable("1sin", 2.0); show("sin(3)");

  // random expressions
  for (int k = 0; k < 3000; k++) {
    const std::string s = gen(0);
    show(s.c_str());
  }

  // Geant4's units (GDML's): mm, MeV, ns, the positron charge
  e.clear();
  std::printf("cleared %d\n", e.status());
  show("pi");
  e.setStdMath();
  e.setSystemOfUnits(1.e+3, 1. / 1.60217733e-25, 1.e+9, 1. / 1.60217733e-10, 1.0, 1.0, 1.0);
  const char* g4[] = {"m", "kg", "s", "A", "K", "mol", "cd", "GeV", "MeV", "keV", "eV", "tesla", "gauss",
                      "kilogauss", "volt", "kV", "ns", "ps", "us", "ms", "minute", "year", "cm", "um", "nm", "km",
                      "barn", "mbarn", "g", "mg", "liter", "joule", "watt", "newton", "pascal", "atmosphere",
                      "bar", "coulomb", "ampere", "ohm", "farad", "henry", "weber", "becquerel", "curie",
                      "gray", "lumen", "lux", "parsec", "angstrom", "fermi", "deg", "mrad", "sr", "hertz",
                      "MHz", "1.5*cm+2*mm", "10*GeV/(2*mm)"};
  for (const char* n : g4) show(n);
  return 0;
}

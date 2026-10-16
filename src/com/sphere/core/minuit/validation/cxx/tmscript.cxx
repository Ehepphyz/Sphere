// The twin of TMinuitScript.java: runs a scenario file line by line against
// ROOT's TMinuit and prints what TMinuit prints, plus each result as the 64
// bits of the double. Usage: tmscript scenario.txt < /dev/null
#include "TMinuit.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

static TMinuit *m = nullptr;
static int nGrad, nFlag1, nFlag3;

static void H(const char *label, double d)
{
   unsigned long long u;
   memcpy(&u, &d, 8);
   printf("%s=%016llx\n", label, u);
}

static void dump(const char *tag)
{
   double fmin, fedm, errdef;
   int npari, nparx, istat;
   m->mnstat(fmin, fedm, errdef, npari, nparx, istat);
   printf("== %s: npari=%d nparx=%d istat=%d status=%d\n", tag, npari, nparx, istat, m->GetStatus());
   H("  fmin", fmin);
   H("  fedm", fedm);
   H("  errdef", errdef);
   char l[64];
   for (int i = 0; i < nparx; i++) {
      double v = 0, e = 0;
      m->GetParameter(i, v, e);
      double ep = 0, en = 0, eparab = 0, gcc = 0;
      m->mnerrs(i, ep, en, eparab, gcc);
      snprintf(l, sizeof l, "  p%d", i); H(l, v);
      snprintf(l, sizeof l, "  e%d", i); H(l, e);
      snprintf(l, sizeof l, "  eplus%d", i); H(l, ep);
      snprintf(l, sizeof l, "  eminus%d", i); H(l, en);
      snprintf(l, sizeof l, "  eparab%d", i); H(l, eparab);
      snprintf(l, sizeof l, "  gcc%d", i); H(l, gcc);
   }
   fflush(stdout);
}

/* ---- the functions ---------------------------------------------------------------------------- */

static void count(int flag)
{
   if (flag == 1) nFlag1++;
   if (flag == 3) nFlag3++;
}

static double gx[60], gy[60], gv[60];

static void makeData()
{
   for (int i = 0; i < 60; i++) {
      const double x = -4. + 0.15 * i;
      const double y = 12. * exp(-0.5 * (x - 0.4) * (x - 0.4) / (1.1 * 1.1)) + 0.3 * sin(3.7 * i) + 0.5;
      gx[i] = x;
      gy[i] = y;
      gv[i] = 0.04 + 0.01 * y;
   }
}

static void rosen(int &, double *, double &f, double *x, int flag)
{
   count(flag);
   const double a = x[1] - x[0] * x[0];
   const double b = 1. - x[0];
   f = 100. * a * a + b * b;
}

static void gauss(int &, double *, double &f, double *p, int flag)
{
   count(flag);
   double c = 0.;
   for (int i = 0; i < 60; i++) {
      const double t = (gx[i] - p[0]) / p[1];
      const double v = p[2] * exp(-0.5 * t * t) + p[3];
      const double r = v - gy[i];
      c += r * r / gv[i];
   }
   f = c;
}

static void quartic(int &, double *gin, double &f, double *p, int iflag)
{
   count(iflag);
   const double x = p[0], y = p[1], z = p[2];
   const double t = x - 1.;
   f = t * t * t * t + 2. * (y + 0.5) * (y + 0.5) + cos(z) + 0.1 * x * y + z * z * 0.05;
   if (iflag == 2) {
      nGrad++;
      gin[0] = 4. * t * t * t + 0.1 * y;
      gin[1] = 4. * (y + 0.5) + 0.1 * x;
      gin[2] = -sin(z) + 0.1 * z;
   }
}

static void badgrad(int &, double *gin, double &f, double *p, int iflag)
{
   count(iflag);
   const double x = p[0], y = p[1];
   f = (x - 2.) * (x - 2.) + 3. * (y + 1.) * (y + 1.) + 0.5 * x * y;
   if (iflag == 2) {
      gin[0] = 2. * (x - 2.) + 0.5 * y;
      gin[1] = 6. * (y + 1.) + 0.5 * x + 1.;
   }
}

static void doubleWell(int &, double *, double &f, double *p, int flag)
{
   count(flag);
   const double x = p[0], y = p[1];
   f = (x * x - 1.) * (x * x - 1.) + (y - 0.3 * x) * (y - 0.3 * x) + 0.05 * x;
}

static void bowl3(int &, double *, double &f, double *p, int flag)
{
   count(flag);
   const double x = p[0] - 1., y = p[1] + 0.5, z = p[2];
   f = x * x + 2. * y * y + 3. * z * z + 1.8 * x * y + 0.5 * x * z + 0.1 * x * x * x * x + 0.05 * y * y * y;
}

static void saddle(int &, double *, double &f, double *p, int flag)
{
   count(flag);
   const double x = p[0], y = p[1];
   f = x * x - y * y + 0.01 * y * y * y * y + 0.2 * x * y;
}

static void flat(int &, double *, double &f, double *p, int flag)
{
   count(flag);
   f = (p[0] - 0.5) * (p[0] - 0.5) + 1e-30 * p[1];
}

static void nanfcn(int &, double *, double &f, double *p, int flag)
{
   count(flag);
   f = sqrt(p[0]) + (p[1] - 1.) * (p[1] - 1.) - p[0] * 0.3;
}

typedef void (*Fcn)(int &, double *, double &, double *, int);

static Fcn byName(const std::string &n)
{
   if (n == "rosen") return rosen;
   if (n == "gauss") return gauss;
   if (n == "quartic") return quartic;
   if (n == "badgrad") return badgrad;
   if (n == "doubleWell") return doubleWell;
   if (n == "bowl3") return bowl3;
   if (n == "saddle") return saddle;
   if (n == "flat") return flat;
   if (n == "nanfcn") return nanfcn;
   fprintf(stderr, "unknown fcn %s\n", n.c_str());
   exit(2);
}

/* ---- the runner -------------------------------------------------------------------------------- */

// the words of a line, separated by blanks and tabs
struct Words {
   std::vector<std::string> w;
   size_t i = 0;
   explicit Words(const std::string &s)
   {
      size_t p = 0;
      while (p < s.size()) {
         while (p < s.size() && (s[p] == ' ' || s[p] == '\t')) p++;
         size_t q = p;
         while (q < s.size() && s[q] != ' ' && s[q] != '\t') q++;
         if (q > p) w.push_back(s.substr(p, q - p));
         p = q;
      }
   }
   std::string next() { return i < w.size() ? w[i++] : std::string(); }
   int nextInt()
   {
      std::string s = next();
      return s.empty() ? 0 : atoi(s.c_str());
   }
   double nextDouble()
   {
      std::string s = next();
      return s.empty() ? 0 : strtod(s.c_str(), nullptr);
   }
};

int main(int argc, char **argv)
{
   if (argc < 2) {
      fprintf(stderr, "usage: tmscript scenario\n");
      return 2;
   }
   makeData();
   std::ifstream in(argv[1], std::ios::binary);
   std::string line;
   while (std::getline(in, line)) {
      if (!line.empty() && line.back() == '\r') line.pop_back();
      if (line.empty() || line[0] == '#') continue;
      printf("@ %s\n", line.c_str());
      fflush(stdout);
      const size_t sp = line.find(' ');
      const std::string op = sp == std::string::npos ? line : line.substr(0, sp);
      const std::string rest = sp == std::string::npos ? std::string() : line.substr(sp + 1);
      Words ss(rest);
      if (op == "new") {
         const std::string n = ss.next();
         const std::string f = ss.next();
         m = n == "default" ? new TMinuit() : new TMinuit(atoi(n.c_str()));
         m->SetFCN(byName(f));
         nGrad = nFlag1 = nFlag3 = 0;
      } else if (op == "graphics") {
         m->SetGraphicsMode(ss.nextInt() != 0);
      } else if (op == "parm" || op == "define") {
         const int k = ss.nextInt();
         std::string name = ss.next();
         const double v = ss.nextDouble(), s = ss.nextDouble(), lo = ss.nextDouble(), hi = ss.nextDouble();
         if (name == "-") name = "";
         int ierflg;
         if (op == "parm") {
            m->mnparm(k, name.c_str(), v, s, lo, hi, ierflg);
         } else {
            ierflg = m->DefineParameter(k, name.c_str(), v, s, lo, hi);
         }
         printf("ierflg %d\n", ierflg);
      } else if (op == "cmd") {
         const int st = m->Command(rest.c_str());
         printf("<< status %d\n", st);
      } else if (op == "excm") {
         const std::string c = ss.next();
         const int n = ss.nextInt();
         double a[40] = {0};
         for (int i = 0; i < n; i++) a[i] = ss.nextDouble();
         int ierflg = 0;
         m->mnexcm(c.c_str(), a, n, ierflg);
         printf("ierflg %d\n", ierflg);
      } else if (op == "dump") {
         dump(rest.c_str());
      } else if (op == "pout") {
         const int k = ss.nextInt();
         TString name;
         double val = 0, er = 0, lo = 0, hi = 0;
         int iuint = 0;
         m->mnpout(k, name, val, er, lo, hi, iuint);
         printf("pout '%s' iuint %d\n", name.Data(), iuint);
         H("  val", val);
         H("  err", er);
         H("  lo", lo);
         H("  hi", hi);
      } else if (op == "emat") {
         const int nd = ss.nextInt();
         double e[400] = {0};
         m->mnemat(e, nd);
         char l[32];
         for (int i = 0; i < nd * nd; i++) {
            snprintf(l, sizeof l, "  emat%d", i);
            H(l, e[i]);
         }
      } else if (op == "nums") {
         printf("pars %d fixed %d free %d\n", m->GetNumPars(), m->GetNumFixedPars(), m->GetNumFreePars());
      } else if (op == "fix") {
         printf("fix -> %d\n", m->FixParameter(ss.nextInt()));
      } else if (op == "release") {
         printf("release -> %d\n", m->Release(ss.nextInt()));
      } else if (op == "errdef") {
         printf("errdef -> %d\n", m->SetErrorDef(ss.nextDouble()));
      } else if (op == "printlevel") {
         printf("printlevel -> %d\n", m->SetPrintLevel(ss.nextInt()));
      } else if (op == "migrad") {
         printf("migrad -> %d\n", m->Migrad());
      } else if (op == "contour") {
         const int n = ss.nextInt(), a = ss.nextInt(), b = ss.nextInt();
         TObject *g = m->Contour(n, a, b);
         printf("contour %s status %d\n", g ? "graph" : "null", m->GetStatus());
      } else if (op == "mncont") {
         const int a = ss.nextInt(), b = ss.nextInt(), n = ss.nextInt();
         double x[120] = {0}, y[120] = {0};
         int ierrf = 0;
         m->mncont(a, b, n, x, y, ierrf);
         printf("mncont ierrf %d\n", ierrf);
         for (int i = 0; i < n; i++) {
            H("  x", x[i]);
            H("  y", y[i]);
         }
      } else if (op == "help") {
         m->mnhelp(rest.c_str());
      } else if (op == "unpt") {
         TString t = rest.c_str();
         printf("unpt %d\n", m->mnunpt(t) ? 1 : 0);
      } else if (op == "pars") {
         TString t = rest.c_str();
         int icondn = -1;
         m->mnpars(t, icondn);
         printf("pars icondn %d\n", icondn);
      } else if (op == "counts") {
         printf("grad %d flag1 %d flag3 %d\n", nGrad, nFlag1, nFlag3);
      } else {
         fprintf(stderr, "unknown op %s\n", op.c_str());
         return 2;
      }
      fflush(stdout);
   }
   fflush(stdout);
   fflush(stderr);
   return 0;
}

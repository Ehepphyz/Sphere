// The twin of TMinuitProbe.java: drives ROOT's TMinuit through its commands
// and its API on a Rosenbrock function, a Gaussian fit with limits, a quartic
// with its gradient and a double well; TMinuit prints what it prints and each
// result is written as the 64 bits of the double.
#include "TMinuit.h"

#include <cmath>
#include <cstdio>
#include <cstring>

static void H(const char *label, double d)
{
   unsigned long long u;
   memcpy(&u, &d, 8);
   printf("%s=%016llx\n", label, u);
}

static void dump(TMinuit *m, const char *tag)
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
   if (npari > 0) {
      double emat[400] = {0};
      m->mnemat(emat, npari);
      for (int i = 0; i < npari * npari; i++) {
         snprintf(l, sizeof l, "  emat%d", i);
         H(l, emat[i]);
      }
   }
   fflush(stdout);
}

static void rosen(int &, double *, double &f, double *x, int)
{
   const double a = x[1] - x[0] * x[0];
   const double b = 1. - x[0];
   f = 100. * a * a + b * b;
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

static void gauss(int &, double *, double &f, double *p, int)
{
   double c = 0.;
   for (int i = 0; i < 60; i++) {
      const double t = (gx[i] - p[0]) / p[1];
      const double v = p[2] * exp(-0.5 * t * t) + p[3];
      const double r = v - gy[i];
      c += r * r / gv[i];
   }
   f = c;
}

static int gradCalls = 0;

static void quartic(int &, double *gin, double &f, double *p, int iflag)
{
   const double x = p[0], y = p[1], z = p[2];
   const double t = x - 1.;
   f = t * t * t * t + 2. * (y + 0.5) * (y + 0.5) + cos(z) + 0.1 * x * y + z * z * 0.05;
   if (iflag == 2) {
      gradCalls++;
      gin[0] = 4. * t * t * t + 0.1 * y;
      gin[1] = 4. * (y + 0.5) + 0.1 * x;
      gin[2] = -sin(z) + 0.1 * z;
   }
}

static void doubleWell(int &, double *, double &f, double *p, int)
{
   const double x = p[0], y = p[1];
   f = (x * x - 1.) * (x * x - 1.) + (y - 0.3 * x) * (y - 0.3 * x) + 0.05 * x;
}

static void cmd(TMinuit *m, const char *c)
{
   printf(">> %s\n", c);
   fflush(stdout);
   const int st = m->Command(c);
   printf("<< status %d\n", st);
   fflush(stdout);
}

typedef void (*Fcn)(int &, double *, double &, double *, int);

static TMinuit *make(int n, Fcn fcn)
{
   TMinuit *m = new TMinuit(n);
   m->SetFCN(fcn);
   return m;
}

int main()
{
   makeData();
   {
      printf("######## Rosenbrock, print level 1\n");
      TMinuit *m = make(2, rosen);
      int ierflg = 0;
      double arglist[10] = {0};
      m->mnparm(0, "x", -1.2, 0.1, 0, 0, ierflg);
      m->mnparm(1, "y", 1.0, 0.1, 0, 0, ierflg);
      cmd(m, "SET PRINT 1");
      cmd(m, "MIGRAD");
      dump(m, "rosen migrad");
      cmd(m, "HESSE");
      cmd(m, "MINOS");
      dump(m, "rosen minos");
      cmd(m, "SHOW COV");
      cmd(m, "SHOW COR");
      cmd(m, "SHOW EIG");
      cmd(m, "SHOW FCN");
      cmd(m, "SHOW EDM");
      cmd(m, "SHOW PAR");
      cmd(m, "SHOW STR");
      cmd(m, "SHOW ERR");
      cmd(m, "SHOW EPS");
      cmd(m, "SHOW LIM");
      arglist[0] = 500;
      m->mnexcm("SIMPLEX", arglist, 1, ierflg);
      printf("simplex ierflg %d\n", ierflg);
      dump(m, "rosen simplex");
   }
   {
      printf("######## Rosenbrock, strategies and errors\n");
      for (int s = 0; s <= 2; s++) {
         TMinuit *m = make(2, rosen);
         m->SetPrintLevel(-1);
         m->DefineParameter(0, "x", -1.2, 0.1, 0, 0);
         m->DefineParameter(1, "y", 1.0, 0.1, 0, 0);
         char c[32];
         snprintf(c, sizeof c, "SET STR %d", s);
         cmd(m, c);
         const int st = m->Migrad();
         printf("migrad returns %d\n", st);
         snprintf(c, sizeof c, "rosen strategy %d", s);
         dump(m, c);
      }
   }
   {
      printf("######## Gaussian fit with limits, print level 0\n");
      TMinuit *m = make(4, gauss);
      int ierflg = 0;
      m->mnparm(0, "mean", 0., 0.2, -1., 2., ierflg);
      m->mnparm(1, "sigma", 1.5, 0.1, 0.2, 5., ierflg);
      m->mnparm(2, "area", 10., 1., 0., 50., ierflg);
      m->mnparm(3, "bkg", 0.3, 0.1, 0, 0, ierflg);
      cmd(m, "SET PRINT 0");
      cmd(m, "MIGRAD");
      dump(m, "gauss migrad");
      cmd(m, "HESSE");
      cmd(m, "MINOS 1000 1 2");
      dump(m, "gauss minos");
      cmd(m, "SET ERR 4");
      cmd(m, "MINOS");
      dump(m, "gauss minos up4");
      cmd(m, "SET ERR 1");
      cmd(m, "FIX 4");
      cmd(m, "MIGRAD");
      dump(m, "gauss fixed bkg");
      cmd(m, "RELEASE 4");
      cmd(m, "SET LIM 1 -0.5 0.8");
      cmd(m, "MIGRAD");
      dump(m, "gauss new limits");
      cmd(m, "SET PAR 1 0.45");
      cmd(m, "SET LIM 1");
      cmd(m, "IMPROVE");
      dump(m, "gauss improve");
      cmd(m, "SCAN 2 11 0.9 1.4");
      cmd(m, "SEEK 30 2");
      dump(m, "gauss seek");
      cmd(m, "MNCONTOUR 1 2 12");
      double xc[20] = {0}, yc[20] = {0};
      int ierrf = 0;
      m->mncont(0, 1, 8, xc, yc, ierrf);
      printf("mncont ierrf %d\n", ierrf);
      for (int i = 0; i < 8; i++) {
         H("  xc", xc[i]);
         H("  yc", yc[i]);
      }
      cmd(m, "SHOW COR");
      cmd(m, "SHOW COV");
      cmd(m, "CALL FCN 3");
      cmd(m, "SET TITLE");
      cmd(m, "SHOW TITLE");
      cmd(m, "SHOW VERSION");
      cmd(m, "SHOW RAN");
      cmd(m, "SET EPS 1e-12");
      cmd(m, "MIGRAD");
      dump(m, "gauss eps");
   }
   {
      printf("######## Quartic with its gradient\n");
      TMinuit *m = make(3, quartic);
      int ierflg = 0;
      m->mnparm(0, "x", 3., 0.5, 0, 0, ierflg);
      m->mnparm(1, "y", 1., 0.5, 0, 0, ierflg);
      m->mnparm(2, "z", 1., 0.3, 0, 0, ierflg);
      cmd(m, "SET GRAD");
      cmd(m, "MIGRAD");
      printf("gradient calls %d\n", gradCalls);
      dump(m, "quartic grad");
      cmd(m, "SET NOGRAD");
      cmd(m, "SET STR 2");
      cmd(m, "MIGRAD");
      dump(m, "quartic nograd s2");
      cmd(m, "HESSE");
      dump(m, "quartic hesse");
   }
   {
      printf("######## Double well: SEEK, MINIMIZE, IMPROVE\n");
      TMinuit *m = make(2, doubleWell);
      int ierflg = 0;
      m->mnparm(0, "x", 0.02, 0.1, 0, 0, ierflg);
      m->mnparm(1, "y", 0.0, 0.1, 0, 0, ierflg);
      cmd(m, "SET PRINT 1");
      cmd(m, "MINIMIZE");
      dump(m, "dw minimize");
      cmd(m, "IMPROVE 30");
      dump(m, "dw improve");
      cmd(m, "SEEK 50 3");
      dump(m, "dw seek");
      cmd(m, "MINOS 500 1");
      dump(m, "dw minos");
   }
   {
      printf("######## Help and errors\n");
      TMinuit *m = make(2, rosen);
      int ierflg = 0;
      m->mnparm(0, "x", -1.2, 0.1, 0, 0, ierflg);
      m->mnparm(1, "y", 1.0, 0.1, 0, 0, ierflg);
      cmd(m, "HELP");
      cmd(m, "HELP MIGRAD");
      cmd(m, "HELP SET");
      cmd(m, "HELP SHOW");
      cmd(m, "BOGUS COMMAND");
      cmd(m, "SET BOGUS");
      cmd(m, "MIGRAD 10 1");
      dump(m, "rosen limited calls");
      cmd(m, "SET PRINT 3");
      cmd(m, "MIGRAD");
      dump(m, "rosen print 3");
   }
   fflush(stdout);
   return 0;
}

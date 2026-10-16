package com.sphere.core.minuit;

import com.sphere.core.hepmc3.cxx.CFormat;
import com.sphere.core.mathcore.TMath;
import com.sphere.core.rootbase.TError;
import com.sphere.core.rootbase.TStr;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Arrays;

/**
 * ROOT's TMinuit (math/minuit): the MINUIT of F. James translated to C++ by
 * R. Brun, and from root-master to Java statement for statement: its
 * commands (MIGRAD, SIMPLEX, MINIMIZE, SEEK, SCAN, HESSE, MINOS, IMPROVE,
 * CONTOUR, MNCONTOUR, SET and SHOW, HELP...), its printouts as printf writes
 * them, its warnings, its random numbers, its line-printer plots. The
 * arithmetic is done in the same order on doubles, the C library functions
 * are the machine's (TMath through NativeMath), and the numbers it prints
 * and returns are those of the C++, which TMinuitTests checks against
 * ROOT's TMinuit compiled with g++.
 *
 * <p>The goto's of the Fortran heritage are loops and switches here; a
 * reference argument of the C++ ({@code Double_t &}) is a one-element array.
 * What ROOT prints on its standard output goes to {@link #setOutput}, its
 * warnings ("Warning in &lt;TMinuit::Contour&gt;: ...") to
 * {@link #setErrorOutput} (ROOT's error handler when not set), and the
 * scanf of MNPARS reads {@link #setInput}.
 */
public class TMinuit {

    /** The minimization function: FCN(npar, gin, f, u, iflag), npar and f by reference. */
    @FunctionalInterface
    public interface FCN {
        void fcn(int[] npar, double[] gin, double[] f, double[] u, int flag);
    }

    /** The last TMinuit made (ROOT's gMinuit). */
    public static TMinuit gMinuit;

    public static final int kMAXWARN = 100;

    private static final String charal = " .ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    public int fNpfix;
    public int fEmpty;
    public int fMaxpar;
    public int fMaxint;
    public int fNpar;
    public int fMaxext;
    public int fMaxIterations;
    public int fMaxpar5;
    public int fMaxcpt;
    public int fMaxpar2;
    public int fMaxpar1;
    public double fAmin;
    public double fUp;
    public double fEDM;
    public double fFval3;
    public double fEpsi;
    public double fApsi;
    public double fDcovar;
    public double fEpsmac;
    public double fEpsma2;
    public double fVlimlo;
    public double fVlimhi;
    public double fUndefi;
    public double fBigedm;
    public double fUpdflt;
    public double fXmidcr;
    public double fYmidcr;
    public double fXdircr;
    public double fYdircr;
    public double[] fU;
    public double[] fAlim;
    public double[] fBlim;
    public double[] fErp;
    public double[] fErn;
    public double[] fWerr;
    public double[] fGlobcc;
    public double[] fX;
    public double[] fXt;
    public double[] fDirin;
    public double[] fXs;
    public double[] fXts;
    public double[] fDirins;
    public double[] fGrd;
    public double[] fG2;
    public double[] fGstep;
    public double[] fGin;
    public double[] fDgrd;
    public double[] fGrds;
    public double[] fG2s;
    public double[] fGsteps;
    public double[] fVhmat;
    public double[] fVthmat;
    public double[] fP;
    public double[] fPstar;
    public double[] fPstst;
    public double[] fPbar;
    public double[] fPrho;
    public double[] fWord7;
    public double[] fXpt;
    public double[] fYpt;
    public double[] fCONTgcc;
    public double[] fCONTw;
    public double[] fFIXPyy;
    public double[] fGRADgf;
    public double[] fHESSyy;
    public double[] fIMPRdsav;
    public double[] fIMPRy;
    public double[] fMATUvline;
    public double[] fMIGRflnu;
    public double[] fMIGRstep;
    public double[] fMIGRgs;
    public double[] fMIGRvg;
    public double[] fMIGRxxs;
    public double[] fMNOTxdev;
    public double[] fMNOTw;
    public double[] fMNOTgcc;
    public double[] fPSDFs;
    public double[] fSEEKxmid;
    public double[] fSEEKxbest;
    public double[] fSIMPy;
    public double[] fVERTq;
    public double[] fVERTs;
    public double[] fVERTpp;
    public double[] fCOMDplist;
    public double[] fPARSplist;
    public int[] fNvarl;
    public int[] fNiofex;
    public int[] fNexofi;
    public int[] fIpfix;
    public int fNu;
    public int fIsysrd;
    public int fIsyswr;
    public int fIsyssa;
    public int fNpagwd;
    public int fNpagln;
    public int fNewpag;
    public final int[] fIstkrd = new int[10];
    public int fNstkrd;
    public final int[] fIstkwr = new int[10];
    public int fNstkwr;
    public final int[] fISW = new int[7];
    public final int[] fIdbg = new int[11];
    public int fNblock;
    public int fIcomnd;
    public int fNfcn;
    public int fNfcnmx;
    public int fNfcnlc;
    public int fNfcnfr;
    public int fItaur;
    public int fIstrat;
    public final int[] fNwrmes = new int[2];
    public final int[] fNfcwar = new int[20];
    public final int[] fIcirc = new int[2];
    public int fStatus;
    public int fKe1cr;
    public int fKe2cr;
    public boolean fLwarn;
    public boolean fLrepor;
    public boolean fLimset;
    public boolean fLnolim;
    public boolean fLnewmn;
    public boolean fLphead;
    public boolean fGraphicsMode;
    public char[] fChpt;
    public String[] fCpnam;
    public String fCfrom = "";
    public String fCstatu = "";
    public String fCtitl = "";
    public String fCword = "";
    public String fCundef = "";
    public String fCvrsn = "";
    public final String[] fCovmes = TStr.array(4);
    public final String[] fOrigin = TStr.array(kMAXWARN);
    public final String[] fWarmes = TStr.array(kMAXWARN);
    public Object fObjectFit;
    public Object fPlot;
    public FCN fFCN;

    /** Where ROOT's standard output goes, its warnings, and what scanf reads. */
    private PrintStream fOut = System.out;
    private PrintStream fErr;
    private InputStream fIn = System.in;

    public void setOutput(PrintStream out) {
        fOut = out == null ? System.out : out;
    }

    /** Where the warnings go; null: ROOT's error handler (TError). */
    public void setErrorOutput(PrintStream err) {
        fErr = err;
    }

    public void setInput(InputStream in) {
        final InputStream s = in == null ? InputStream.nullInputStream() : in;
        fIn = s.markSupported() ? s : new java.io.BufferedInputStream(s);
    }

    /* ---- what ROOT supplies ---------------------------------------------------------- */

    /** ROOT's Printf: printf of the format, a new line, flushed. */
    protected void Printf(String fmt, Object... args) {
        fOut.print(args.length == 0 ? CFormat.sprintf(fmt) : CFormat.sprintf(fmt, args));
        fOut.print('\n');
        fOut.flush();
    }

    /** TObject::Warning and Error: "Warning in &lt;TMinuit::method&gt;: message" on the error output. */
    private void message(int level, String method, String fmt, Object... args) {
        final String msg = args.length == 0 ? CFormat.sprintf(fmt) : CFormat.sprintf(fmt, args);
        final String where = "TMinuit::" + method;
        if (fErr == null) {
            TError.ErrorHandler(level, where, "%s", msg);
            return;
        }
        final int ignore = TError.gErrorIgnoreLevel == TError.kUnset ? TError.kPrint : TError.gErrorIgnoreLevel;
        if (level < ignore) return;
        fOut.flush();
        fErr.print((level >= TError.kError ? "Error" : "Warning") + " in <" + where + ">: " + msg + "\n");
        fErr.flush();
    }

    protected void Warning(String method, String fmt, Object... args) {
        message(TError.kWarning, method, fmt, args);
    }

    protected void Error(String method, String fmt, Object... args) {
        message(TError.kError, method, fmt, args);
    }

    /** Eval(npar, fGin, f, fU, flag) with f a local holding prior before the call (the FCN may leave it). */
    private double eval(int npar, double prior, int flag) {
        final double[] f = {prior};
        Eval(npar, fGin, f, fU, flag);
        return f[0];
    }

    /** Reads what scanf(format, ...) reads from the input: whitespace skipped, literals matched, %lf / %s converted. */
    private int scanf(String format, Object[] args) {
        int assigned = 0;
        int a = 0;
        int i = 0;
        try {
            while (i < format.length()) {
                final char c = format.charAt(i);
                if (Character.isWhitespace(c)) {
                    int ch;
                    do {
                        fIn.mark(1);
                        ch = fIn.read();
                    } while (ch >= 0 && Character.isWhitespace(ch));
                    if (ch >= 0) {
                        fIn.reset();
                    }
                    i++;
                    continue;
                }
                if (c != '%') {
                    final int ch = fIn.read();
                    if (ch < 0) return assigned == 0 ? -1 : assigned;
                    if (ch != c) return assigned;
                    i++;
                    continue;
                }
                // a conversion: skip its flags and length, then read a word
                int j = i + 1;
                while (j < format.length() && "lhLqjzt0123456789".indexOf(format.charAt(j)) >= 0) j++;
                if (j >= format.length()) return assigned;
                final char conv = format.charAt(j);
                i = j + 1;
                int ch;
                do {
                    ch = fIn.read();
                } while (ch >= 0 && Character.isWhitespace(ch));
                if (ch < 0) return assigned == 0 ? -1 : assigned;
                final StringBuilder w = new StringBuilder();
                while (ch >= 0 && !Character.isWhitespace(ch)) {
                    w.append((char) ch);
                    fIn.mark(1);
                    ch = fIn.read();
                }
                if (ch >= 0) fIn.reset();
                if (a >= args.length) return assigned;
                final Object target = args[a++];
                if (conv == 's' && target instanceof String[] s) {
                    s[0] = w.toString();
                } else if (target instanceof double[] d) {
                    d[0] = com.sphere.core.hepmc3.cxx.CStr.strtod(w, 0);
                } else {
                    return assigned;
                }
                assigned++;
            }
        } catch (IOException e) {
            return assigned == 0 ? -1 : assigned;
        }
        return assigned;
    }

    /** sscanf(text, "%lf", &x): x when the text starts with a number, unchanged otherwise. */
    private static double sscanfDouble(String text, double prior) {
        final double v = com.sphere.core.hepmc3.cxx.CStr.strtod(text, 0);
        return com.sphere.core.hepmc3.cxx.CStr.end() > 0 ? v : prior;
    }

    /* ---- construction ---------------------------------------------------------------- */

    /** Minuit normal constructor: arrays for 25 parameters, initialized. */
    public TMinuit() {
        BuildArrays(25);
        fUp = 0;
        fEpsi = 0;
        fApsi = 0;
        fXmidcr = 0;
        fYmidcr = 0;
        fXdircr = 0;
        fYdircr = 0;
        fStatus = 0;
        fEmpty = 0;
        fObjectFit = null;
        fPlot = null;
        fGraphicsMode = true;
        SetMaxIterations();
        mninit(5, 6, 7);
        fFCN = null;
        gMinuit = this;
    }

    /** Minuit normal constructor: maxpar is the maximum number of parameters used with this TMinuit object. */
    public TMinuit(int maxpar) {
        fFCN = null;
        BuildArrays(maxpar);
        fStatus = 0;
        fEmpty = 0;
        fObjectFit = null;
        fPlot = null;
        fGraphicsMode = true;
        SetMaxIterations();
        mninit(5, 6, 7);
        gMinuit = this;
    }

    /** Create internal Minuit arrays for the maxpar parameters. */
    public void BuildArrays(int maxpar) {
        fMaxpar = 25;
        if (maxpar >= fMaxpar) fMaxpar = maxpar + 1;
        fMaxpar1 = fMaxpar * (fMaxpar + 1);
        fMaxpar2 = 2 * fMaxpar;
        fMaxpar5 = fMaxpar1 / 2;
        fMaxcpt = 101;
        fCpnam = TStr.array(fMaxpar2);
        fU = new double[fMaxpar2];
        fAlim = new double[fMaxpar2];
        fBlim = new double[fMaxpar2];
        fPstar = new double[fMaxpar2];
        fGin = new double[fMaxpar2];
        fNvarl = new int[fMaxpar2];
        fNiofex = new int[fMaxpar2];

        fNexofi = new int[fMaxpar];
        fIpfix = new int[fMaxpar];
        fErp = new double[fMaxpar];
        fErn = new double[fMaxpar];
        fWerr = new double[fMaxpar];
        fGlobcc = new double[fMaxpar];
        fX = new double[fMaxpar];
        fXt = new double[fMaxpar];
        fDirin = new double[fMaxpar];
        fXs = new double[fMaxpar];
        fXts = new double[fMaxpar];
        fDirins = new double[fMaxpar];
        fGrd = new double[fMaxpar];
        fG2 = new double[fMaxpar];
        fGstep = new double[fMaxpar];
        fDgrd = new double[fMaxpar];
        fGrds = new double[fMaxpar];
        fG2s = new double[fMaxpar];
        fGsteps = new double[fMaxpar];
        fPstst = new double[fMaxpar];
        fPbar = new double[fMaxpar];
        fPrho = new double[fMaxpar];
        fWord7 = new double[fMaxpar];
        fVhmat = new double[fMaxpar5];
        fVthmat = new double[fMaxpar5];
        fP = new double[fMaxpar1];
        fXpt = new double[fMaxcpt];
        fYpt = new double[fMaxcpt];
        fChpt = new char[fMaxcpt + 1];
        fCONTgcc = new double[fMaxpar];
        fCONTw = new double[fMaxpar];
        fFIXPyy = new double[fMaxpar];
        fGRADgf = new double[fMaxpar];
        fHESSyy = new double[fMaxpar];
        fIMPRdsav = new double[fMaxpar];
        fIMPRy = new double[fMaxpar];
        fMATUvline = new double[fMaxpar];
        fMIGRflnu = new double[fMaxpar];
        fMIGRstep = new double[fMaxpar];
        fMIGRgs = new double[fMaxpar];
        fMIGRvg = new double[fMaxpar];
        fMIGRxxs = new double[fMaxpar];
        fMNOTxdev = new double[fMaxpar];
        fMNOTw = new double[fMaxpar];
        fMNOTgcc = new double[fMaxpar];
        fPSDFs = new double[fMaxpar];
        fSEEKxmid = new double[fMaxpar];
        fSEEKxbest = new double[fMaxpar];
        fSIMPy = new double[fMaxpar];
        fVERTq = new double[fMaxpar];
        fVERTs = new double[fMaxpar];
        fVERTpp = new double[fMaxpar];
        fCOMDplist = new double[fMaxpar];
        fPARSplist = new double[fMaxpar];

        for (int i = 0; i < fMaxpar; i++) {
            fErp[i] = 0;
            fErn[i] = 0;
        }
    }

    /** Execute a Minuit command; returns the status of MNCOMD (0 normal, 1 blank, 2 unreadable, 3 unknown, 4 abnormal...). */
    public int Command(String command) {
        final int[] status = {0};
        mncomd(command, status);
        return status[0];
    }

    /**
     * The n-sigma contour of parameters pa1 and pa2 in npoints (&gt;= 4) points, as a graph of npoints+1
     * points (the first repeated to close it), or null; GetStatus() says how it went.
     */
    public Object Contour(int npoints, int pa1, int pa2) {
        if (npoints < 4) {
            fStatus = 2;
            return null;
        }
        final int[] npfound = {0};
        final double[] xcoor = new double[npoints + 1];
        final double[] ycoor = new double[npoints + 1];
        mncont(pa1, pa2, npoints, xcoor, ycoor, npfound);
        if (npfound[0] < 4) {
            Warning("Contour", "Cannot find more than 4 points, no TGraph returned");
            fStatus = (npfound[0] == 0 ? 1 : npfound[0]);
            return null;
        }
        if (npfound[0] != npoints) {
            Warning("Contour", "Returning a TGraph with %d points only", npfound[0]);
            npoints = npfound[0];
        }
        fStatus = 0;
        xcoor[npoints] = xcoor[0];
        ycoor[npoints] = ycoor[0];
        return new TMinuitGraph(npoints + 1, xcoor, ycoor);
    }

    public int DefineParameter(int parNo, String name, double initVal, double initErr, double lowerLimit, double upperLimit) {
        final int[] err = {0};
        mnparm(parNo, name, initVal, initErr, lowerLimit, upperLimit, err);
        return err[0];
    }

    /** The minimisation function: the FCN set by SetFCN, if any. */
    public int Eval(int npar, double[] grad, double[] fval, double[] par, int flag) {
        if (fFCN != null) fFCN.fcn(new int[] {npar}, grad, fval, par, flag);
        return 0;
    }

    public int FixParameter(int parNo) {
        final int[] err = {0};
        mnexcm("FIX", new double[] {parNo + 1}, 1, err);
        return err[0];
    }

    public int GetParameter(int parNo, double[] currentValue, double[] currentError) {
        final int[] err = {0};
        mnpout(parNo, new String[1], currentValue, currentError, new double[1], new double[1], err);
        return err[0];
    }

    public int GetNumFixedPars() {
        return fNpfix;
    }

    public int GetNumFreePars() {
        return fNpar;
    }

    public int GetNumPars() {
        return fNpar + fNpfix;
    }

    public Object GetPlot() {
        return fPlot;
    }

    public int GetStatus() {
        return fStatus;
    }

    public Object GetObjectFit() {
        return fObjectFit;
    }

    public void SetObjectFit(Object obj) {
        fObjectFit = obj;
    }

    public int GetMaxIterations() {
        return fMaxIterations;
    }

    public void SetMaxIterations(int maxiter) {
        fMaxIterations = maxiter;
    }

    public void SetMaxIterations() {
        SetMaxIterations(500);
    }

    public void SetGraphicsMode(boolean mode) {
        fGraphicsMode = mode;
    }

    public int Migrad() {
        final int[] err = {0};
        mnexcm("MIGRAD", new double[] {0}, 0, err);
        return err[0];
    }

    public int Release(int parNo) {
        final int[] err = {0};
        mnexcm("RELEASE", new double[] {parNo + 1}, 1, err);
        return err[0];
    }

    public int SetErrorDef(double up) {
        final int[] err = {0};
        mnexcm("SET ERRDEF", new double[] {up}, 1, err);
        return err[0];
    }

    public void SetFCN(FCN fcn) {
        fFCN = fcn;
    }

    /** -1 quiet (warnings suppressed too), 0 normal, 1 verbose. */
    public int SetPrintLevel(int printLevel) {
        final int[] err = {0};
        final double[] tmp = {printLevel};
        mnexcm("SET PRINT", tmp, 1, err);
        if (printLevel <= -1) mnexcm("SET NOWarnings", tmp, 0, err);
        return err[0];
    }

    public int SetPrintLevel() {
        return SetPrintLevel(0);
    }

    /* ---- MINUIT ---------------------------------------------------------------------- */

    /** Initialize AMIN: calls the user function at the current point. */
    public void mnamin() {
        final int nparx = fNpar;
        if (fISW[4] >= 1) {
            Printf(" FIRST CALL TO USER FUNCTION AT NEW START POINT, WITH IFLAG=4.");
        }
        mnexin(fX);
        final double fnew = eval(nparx, 0, 4);
        ++fNfcn;
        fAmin = fnew;
        fEDM = fBigedm;
    }

    /** Reasonable histogram intervals from bounds a1, a2 and at most naa bins (bl, bh, nb, bwid by reference). */
    public void mnbins(double a1, double a2, int naa, double[] bl, double[] bh, int[] nb, double[] bwid) {
        double awid, ah, al, sigfig, sigrnd = 0, alb;
        int kwid, lwid, na = 0, log_ = 0;

        al = TMath.min(a1, a2);
        ah = TMath.max(a1, a2);
        if (al == ah) ah = al + 1;

        int L = naa == -1 ? 150 : 10;
        while (true) {
            switch (L) {
                case 10:
                    na = naa - 1;
                    if (na < 1) na = 1;
                    // fall through
                case 20:
                    awid = (ah - al) / (double) na;
                    log_ = (int) TMath.log10(awid);
                    if (awid <= 1) --log_;
                    sigfig = awid * TMath.power(10, -log_);
                    if (sigfig > 2) {
                        if (sigfig > 2.5) {
                            if (sigfig > 5) {
                                sigrnd = 1;
                                ++log_;
                            } else {
                                sigrnd = 5;
                            }
                        } else {
                            sigrnd = 2.5;
                        }
                    } else {
                        sigrnd = 2;
                    }
                    bwid[0] = sigrnd * TMath.power(10, log_);
                    L = 200;
                    continue;
                case 150:
                    if (bwid[0] <= 0) {
                        L = 10;
                        continue;
                    }
                    // fall through
                case 200:
                    alb = al / bwid[0];
                    lwid = (int) alb;
                    if (alb < 0) --lwid;
                    bl[0] = bwid[0] * (double) lwid;
                    alb = ah / bwid[0] + 1;
                    kwid = (int) alb;
                    if (alb < 0) --kwid;
                    bh[0] = bwid[0] * (double) kwid;
                    nb[0] = kwid - lwid;
                    if (naa > 5) {
                        if ((nb[0] << 1) != naa) return;
                        ++na;
                        L = 20;
                        continue;
                    }
                    if (naa == -1) return;
                    if (naa > 1 || nb[0] == 1) return;
                    bwid[0] *= 2;
                    nb[0] = 1;
                    return;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    /** Called only from MNIMPR: ycalf = (f-fmin)/(x-xmin)*v*(x-xmin). */
    public void mncalf(double[] pvec, double[] ycalf) {
        int ndex, i, j, m, n, nparx;
        double denom, f;

        nparx = fNpar;
        mninex(pvec);
        f = eval(nparx, 0, 4);
        ++fNfcn;
        for (i = 1; i <= fNpar; ++i) {
            fGrd[i - 1] = 0;
            for (j = 1; j <= fNpar; ++j) {
                m = TMath.max(i, j);
                n = TMath.min(i, j);
                ndex = m * (m - 1) / 2 + n;
                fGrd[i - 1] += fVthmat[ndex - 1] * (fXt[j - 1] - pvec[j - 1]);
            }
        }
        denom = 0;
        for (i = 1; i <= fNpar; ++i) {
            denom += fGrd[i - 1] * (fXt[i - 1] - pvec[i - 1]);
        }
        if (denom <= 0) {
            fDcovar = 1;
            fISW[1] = 0;
            denom = 1;
        }
        ycalf[0] = (f - fApsi) / denom;
    }

    /** Resets the parameter list to UNDEFINED. */
    public void mncler() {
        fNpfix = 0;
        fNu = 0;
        fNpar = 0;
        fNfcn = 0;
        fNwrmes[0] = 0;
        fNwrmes[1] = 0;
        for (int i = 1; i <= fMaxext; ++i) {
            fU[i - 1] = 0;
            fCpnam[i - 1] = fCundef;
            fNvarl[i - 1] = -1;
            fNiofex[i - 1] = 0;
        }
        mnrset(1);
        fCfrom = "CLEAR   ";
        fNfcnfr = fNfcn;
        fCstatu = "UNDEFINED ";
        fLnolim = true;
        fLphead = true;
    }

    /** Prints function contours in two variables, on line printer. */
    public void mncntr(int ike1, int ike2, int[] ierrf) {
        final String clabel = "0123456789ABCDEFGHIJ";
        double d__1, d__2;
        final double[] fcna = new double[115];
        final double[] fcnb = new double[115];
        final double[] contur = new double[20];
        double ylabel, fmn, fmx, xlo, ylo, xup, yup;
        double devs, xsav, ysav, bwidx, bwidy, unext, ff, xb4;
        int i, ngrid, ixmid, nparx, ix, nx, ny, ki1, ki2, ixzero, iy, ics;
        String chmid = "", chln = "", chzero = "";

        final int ke1 = ike1 + 1;
        final int ke2 = ike2 + 1;
        if (ke1 <= 0 || ke2 <= 0 || ke1 > fNu || ke2 > fNu) {
            Printf(" INVALID PARAMETER NUMBER(S) REQUESTED.  IGNORED.");
            ierrf[0] = 1;
            return;
        }
        ki1 = fNiofex[ke1 - 1];
        ki2 = fNiofex[ke2 - 1];
        if (ki1 <= 0 || ki2 <= 0 || ki1 == ki2) {
            Printf(" INVALID PARAMETER NUMBER(S) REQUESTED.  IGNORED.");
            ierrf[0] = 1;
            return;
        }

        if (fISW[1] < 1) {
            mnhess();
            mnwerr();
        }
        nparx = fNpar;
        xsav = fU[ke1 - 1];
        ysav = fU[ke2 - 1];
        devs = fWord7[2];
        if (devs <= 0) devs = 2;
        xlo = fU[ke1 - 1] - devs * fWerr[ki1 - 1];
        xup = fU[ke1 - 1] + devs * fWerr[ki1 - 1];
        ylo = fU[ke2 - 1] - devs * fWerr[ki2 - 1];
        yup = fU[ke2 - 1] + devs * fWerr[ki2 - 1];
        ngrid = (int) fWord7[3];
        if (ngrid <= 0) {
            ngrid = 25;
            nx = TMath.min(fNpagwd - 15, ngrid);
            ny = TMath.min(fNpagln - 7, ngrid);
        } else {
            nx = ngrid;
            ny = ngrid;
        }
        if (nx < 11) nx = 11;
        if (ny < 11) ny = 11;
        if (nx >= 115) nx = 114;

        if (fNvarl[ke1 - 1] > 1) {
            if (xlo < fAlim[ke1 - 1]) xlo = fAlim[ke1 - 1];
            if (xup > fBlim[ke1 - 1]) xup = fBlim[ke1 - 1];
        }
        if (fNvarl[ke2 - 1] > 1) {
            if (ylo < fAlim[ke2 - 1]) ylo = fAlim[ke2 - 1];
            if (yup > fBlim[ke2 - 1]) yup = fBlim[ke2 - 1];
        }
        bwidx = (xup - xlo) / (double) nx;
        bwidy = (yup - ylo) / (double) ny;
        ixmid = (int) (((xsav - xlo) * (double) nx / (xup - xlo)) + 1);
        if (ixmid < 1) ixmid = 1;
        if (fAmin == fUndefi) mnamin();

        for (i = 1; i <= 20; ++i) {
            contur[i - 1] = fAmin + fUp * (i - 1) * (i - 1);
        }
        contur[0] += fUp * .01;
        fU[ke2 - 1] = yup;
        ixzero = 0;
        xb4 = 1;
        chmid = TStr.resize(chmid, nx + 1);
        chzero = TStr.resize(chzero, nx + 1);
        chln = TStr.resize(chln, nx + 1);
        for (ix = 1; ix <= nx + 1; ++ix) {
            fU[ke1 - 1] = xlo + (double) (ix - 1) * bwidx;
            ff = eval(nparx, 0, 4);
            fcnb[ix - 1] = ff;
            if (xb4 < 0 && fU[ke1 - 1] > 0) ixzero = ix - 1;
            xb4 = fU[ke1 - 1];
            chmid = TStr.set(chmid, ix - 1, '*');
            chzero = TStr.set(chzero, ix - 1, '-');
        }
        Printf(" Y-AXIS: PARAMETER %3d: %s", ke2, fCpnam[ke2 - 1]);
        if (ixzero > 0) {
            chzero = TStr.set(chzero, ixzero - 1, '+');
            chln = " ";
            Printf("             X=0");
        }
        for (iy = 1; iy <= ny; ++iy) {
            unext = fU[ke2 - 1] - bwidy;
            chln = " ";
            chln = TStr.resize(chln, nx + 1);
            chln = TStr.set(chln, ixmid - 1, '*');
            if (ixzero != 0) chln = TStr.set(chln, ixzero - 1, ':');
            if (fU[ke2 - 1] > ysav && unext < ysav) chln = chmid;
            if (fU[ke2 - 1] > 0 && unext < 0) chln = chzero;
            fU[ke2 - 1] = unext;
            ylabel = fU[ke2 - 1] + bwidy * .5;
            for (ix = 1; ix <= nx + 1; ++ix) {
                fcna[ix - 1] = fcnb[ix - 1];
                fU[ke1 - 1] = xlo + (double) (ix - 1) * bwidx;
                ff = eval(nparx, 0, 4);
                fcnb[ix - 1] = ff;
            }
            for (ix = 1; ix <= nx; ++ix) {
                d__1 = TMath.max(fcna[ix - 1], fcnb[ix - 1]);
                d__2 = TMath.max(fcna[ix], fcnb[ix]);
                fmx = TMath.max(d__1, d__2);
                d__1 = TMath.min(fcna[ix - 1], fcnb[ix - 1]);
                d__2 = TMath.min(fcna[ix], fcnb[ix]);
                fmn = TMath.min(d__1, d__2);
                for (ics = 1; ics <= 20; ++ics) {
                    if (contur[ics - 1] > fmn) break;
                }
                if (ics > 20) continue;
                if (contur[ics - 1] < fmx) chln = TStr.set(chln, ix - 1, clabel.charAt(ics - 1));
            }
            Printf(" %12.4g %s", ylabel, chln);
        }
        chln = " ";
        chln = TStr.replace(chln, 0, 1, "I");
        chln = TStr.replace(chln, ixmid - 1, 1, "I");
        chln = TStr.replace(chln, nx - 1, 1, "I");
        Printf("              %s", chln);

        chln = " ";
        if (nx <= 26) {
            Printf("        %12.4g%s%12.4g", xlo, chln, xup);
            Printf("              %s%12.4g", chln, xsav);
        } else {
            Printf("        %12.4g%s%12.4g%s%12.4g", xlo, chln, xsav, chln, xup);
        }
        Printf("       X-AXIS: PARAMETER %3d %s  ONE COLUMN=%12.4g", ke1, fCpnam[ke1 - 1], bwidx);
        Printf(" FUNCTION VALUES: F(I)=%12.4g +%12.4g *I**2", fAmin, fUp);
        fU[ke1 - 1] = xsav;
        fU[ke2 - 1] = ysav;
        ierrf[0] = 0;
    }

    /** Reads a command string and executes; icondn as Command returns it. */
    public void mncomd(String crdbin, int[] icondn) {
        int ipos, i, lenbuf;
        boolean leader;
        String crdbuf, ctemp;

        crdbuf = crdbin;
        crdbuf = TStr.upper(crdbuf);
        lenbuf = crdbuf.length();
        icondn[0] = 0;
        leader = true;
        ipos = 1;
        for (i = 1; i <= TMath.min(20, lenbuf); ++i) {
            if (TStr.at(crdbuf, i - 1) == '\'') break;
            if (TStr.at(crdbuf, i - 1) == ' ') {
                if (leader) ++ipos;
                continue;
            }
            leader = false;
        }

        if (ipos > lenbuf) {
            Printf(" BLANK COMMAND IGNORED.");
            icondn[0] = 1;
            return;
        }
        if (TStr.sub(crdbuf, ipos - 1, 3).equals("PAR")) {
            icondn[0] = 5;
            fLphead = true;
            return;
        }
        if (TStr.sub(crdbuf, ipos - 1, 3).equals("SET INP")) {
            icondn[0] = 6;
            fLphead = true;
            return;
        }
        if (TStr.sub(crdbuf, ipos - 1, 7).equals("SET TIT")) {
            icondn[0] = 7;
            fLphead = true;
            return;
        }
        if (TStr.sub(crdbuf, ipos - 1, 7).equals("SET COV")) {
            icondn[0] = 8;
            fLphead = true;
            return;
        }
        ctemp = TStr.sub(crdbuf, ipos - 1, lenbuf - ipos + 1);
        final String[] comand = {""};
        final int[] lnc = {0};
        final int[] llist = {0};
        final int[] ierr = {0};
        mncrck(ctemp, 20, comand, lnc, fMaxpar, fCOMDplist, llist, ierr, fIsyswr);
        if (ierr[0] > 0) {
            Printf(" COMMAND CANNOT BE INTERPRETED");
            icondn[0] = 2;
            return;
        }
        mnexcm(comand[0], fCOMDplist, llist[0], ierr);
        icondn[0] = ierr[0];
    }

    /** Find points along a contour where FCN is minimum; ierrf: the number of points found, -1 or 0 on errors. */
    public void mncont(int ike1, int ike2, int nptu, double[] xptu, double[] yptu, int[] ierrf) {
        int i__1;
        double d__1, d__2;
        double dist, xdir, ydir, u1min = 0, u2min = 0;
        double abest = 0, scalx = 0, scaly;
        double a1, a2, dc = 0, sclfac, bigdis, sigsav = 0;
        int nall, iold, line, mpar = 0, inew, move, next, i, j, nfcol;
        int idist = 0, npcol, kints, i2, i1, lr, nfcnco = 0, ki1, ki2, ki3, ke3;
        int nowpts = 0, istrav = 0, nfmxin = 0, isw2 = 0, isw4 = 0;
        boolean ldebug;
        final double[] val2pl = {0};
        final double[] val2mi = {0};
        final double[] aopt = {0};
        final int[] iercr = {0};
        final int[] ierr = {0};

        final int ke1 = ike1 + 1;
        final int ke2 = ike2 + 1;
        ldebug = fIdbg[6] >= 1;
        int L;
        if (ke1 <= 0 || ke2 <= 0 || ke1 > fNu || ke2 > fNu) {
            L = 1350;
        } else {
            ki1 = fNiofex[ke1 - 1];
            ki2 = fNiofex[ke2 - 1];
            if (ki1 <= 0 || ki2 <= 0 || ki1 == ki2) L = 1350;
            else if (nptu < 4) L = 1400;
            else L = 0;
        }
        flow:
        while (true) {
            switch (L) {
                case 0: {
                    ki1 = fNiofex[ke1 - 1];
                    ki2 = fNiofex[ke2 - 1];
                    nfcnco = fNfcn;
                    fNfcnmx = (nptu + 5) * 100 * (fNpar + 1);
                    mncuve();
                    u1min = fU[ke1 - 1];
                    u2min = fU[ke2 - 1];
                    ierrf[0] = 0;
                    fCfrom = "MNContour ";
                    fNfcnfr = nfcnco;
                    if (fISW[4] >= 0) {
                        Printf(" START MNCONTOUR CALCULATION OF %4d POINTS ON CONTOUR.", nptu);
                        if (fNpar > 2) {
                            if (fNpar == 3) {
                                ki3 = 6 - ki1 - ki2;
                                ke3 = fNexofi[ki3 - 1];
                                Printf(" EACH POINT IS A MINIMUM WITH RESPECT TO PARAMETER %3d  %s", ke3, fCpnam[ke3 - 1]);
                            } else {
                                Printf(" EACH POINT IS A MINIMUM WITH RESPECT TO THE OTHER %3d VARIABLE PARAMETERS.", fNpar - 2);
                            }
                        }
                    }
                    mnmnot(ke1, ke2, val2pl, val2mi);
                    if (fErn[ki1 - 1] == fUndefi) {
                        xptu[0] = fAlim[ke1 - 1];
                        mnwarn("W", "MNContour ", "Contour squeezed by parameter limits.");
                    } else {
                        if (fErn[ki1 - 1] >= 0) {
                            L = 1500;
                            continue flow;
                        }
                        xptu[0] = u1min + fErn[ki1 - 1];
                    }
                    yptu[0] = val2mi[0];

                    if (fErp[ki1 - 1] == fUndefi) {
                        xptu[2] = fBlim[ke1 - 1];
                        mnwarn("W", "MNContour ", "Contour squeezed by parameter limits.");
                    } else {
                        if (fErp[ki1 - 1] <= 0) {
                            L = 1500;
                            continue flow;
                        }
                        xptu[2] = u1min + fErp[ki1 - 1];
                    }
                    yptu[2] = val2pl[0];
                    scalx = 1 / (xptu[2] - xptu[0]);
                    mnmnot(ke2, ke1, val2pl, val2mi);
                    if (fErn[ki2 - 1] == fUndefi) {
                        yptu[1] = fAlim[ke2 - 1];
                        mnwarn("W", "MNContour ", "Contour squeezed by parameter limits.");
                    } else {
                        if (fErn[ki2 - 1] >= 0) {
                            L = 1500;
                            continue flow;
                        }
                        yptu[1] = u2min + fErn[ki2 - 1];
                    }
                    xptu[1] = val2mi[0];
                    if (fErp[ki2 - 1] == fUndefi) {
                        yptu[3] = fBlim[ke2 - 1];
                        mnwarn("W", "MNContour ", "Contour squeezed by parameter limits.");
                    } else {
                        if (fErp[ki2 - 1] <= 0) {
                            L = 1500;
                            continue flow;
                        }
                        yptu[3] = u2min + fErp[ki2 - 1];
                    }
                    xptu[3] = val2pl[0];
                    scaly = 1 / (yptu[3] - yptu[1]);
                    nowpts = 4;
                    next = 5;
                    if (ldebug) {
                        Printf(" Plot of four points found by MINOS");
                        fXpt[0] = u1min;
                        fYpt[0] = u2min;
                        fChpt[0] = ' ';
                        nall = TMath.min(nowpts + 1, 101);
                        for (i = 2; i <= nall; ++i) {
                            fXpt[i - 1] = xptu[i - 2];
                            fYpt[i - 1] = yptu[i - 2];
                        }
                        TStr.snprintf(fChpt, fMaxcpt + 1, " ABCD");
                        mnplot(fXpt, fYpt, fChpt, nall, fNpagwd, fNpagln);
                    }

                    isw2 = fISW[1];
                    isw4 = fISW[3];
                    sigsav = fEDM;
                    istrav = fIstrat;
                    dc = fDcovar;
                    fApsi = fEpsi * .5;
                    abest = fAmin;
                    mpar = fNpar;
                    nfmxin = fNfcnmx;
                    for (i = 1; i <= mpar; ++i) {
                        fXt[i - 1] = fX[i - 1];
                    }
                    i__1 = mpar * (mpar + 1) / 2;
                    for (j = 1; j <= i__1; ++j) {
                        fVthmat[j - 1] = fVhmat[j - 1];
                    }
                    for (i = 1; i <= mpar; ++i) {
                        fCONTgcc[i - 1] = fGlobcc[i - 1];
                        fCONTw[i - 1] = fWerr[i - 1];
                    }
                    kints = fNiofex[ke1 - 1];
                    mnfixp(kints - 1, ierr);
                    kints = fNiofex[ke2 - 1];
                    mnfixp(kints - 1, ierr);
                    inewLoop:
                    for (inew = next; inew <= nptu; ++inew) {
                        bigdis = 0;
                        for (iold = 1; iold <= inew - 1; ++iold) {
                            i2 = iold + 1;
                            if (i2 == inew) i2 = 1;
                            d__1 = scalx * (xptu[iold - 1] - xptu[i2 - 1]);
                            d__2 = scaly * (yptu[iold - 1] - yptu[i2 - 1]);
                            dist = d__1 * d__1 + d__2 * d__2;
                            if (dist > bigdis) {
                                bigdis = dist;
                                idist = iold;
                            }
                        }
                        i1 = idist;
                        i2 = i1 + 1;
                        if (i2 == inew) i2 = 1;
                        a1 = .5;
                        a2 = .5;
                        while (true) {
                            fXmidcr = a1 * xptu[i1 - 1] + a2 * xptu[i2 - 1];
                            fYmidcr = a1 * yptu[i1 - 1] + a2 * yptu[i2 - 1];
                            xdir = yptu[i2 - 1] - yptu[i1 - 1];
                            ydir = xptu[i1 - 1] - xptu[i2 - 1];
                            sclfac = TMath.max(Math.abs(xdir * scalx), Math.abs(ydir * scaly));
                            fXdircr = xdir / sclfac;
                            fYdircr = ydir / sclfac;
                            fKe1cr = ke1;
                            fKe2cr = ke2;
                            fAmin = abest;
                            mncros(aopt, iercr);
                            if (iercr[0] > 1) {
                                if (a1 > .5) {
                                    if (fISW[4] >= 0) {
                                        Printf(" MNCONT CANNOT FIND NEXT POINT ON CONTOUR.  ONLY %3d POINTS FOUND.", nowpts);
                                    }
                                    break inewLoop;
                                }
                                mnwarn("W", "MNContour ", "Cannot find midpoint, try closer.");
                                a1 = .75;
                                a2 = .25;
                                continue;
                            }
                            break;
                        }
                        for (move = nowpts; move >= i1 + 1; --move) {
                            xptu[move] = xptu[move - 1];
                            yptu[move] = yptu[move - 1];
                        }
                        ++nowpts;
                        xptu[i1] = fXmidcr + fXdircr * aopt[0];
                        yptu[i1] = fYmidcr + fYdircr * aopt[0];
                    }
                    // L950
                    ierrf[0] = nowpts;
                    fCstatu = "SUCCESSFUL";
                    if (nowpts < nptu) fCstatu = "INCOMPLETE";

                    if (fISW[4] >= 0) {
                        fXpt[0] = u1min;
                        fYpt[0] = u2min;
                        fChpt[0] = ' ';
                        nall = TMath.min(nowpts + 1, 101);
                        for (i = 2; i <= nall; ++i) {
                            fXpt[i - 1] = xptu[i - 2];
                            fYpt[i - 1] = yptu[i - 2];
                            fChpt[i - 1] = 'X';
                        }
                        fChpt[nall] = 0;
                        Printf(" Y-AXIS: PARAMETER %3d  %s", ke2, fCpnam[ke2 - 1]);
                        mnplot(fXpt, fYpt, fChpt, nall, fNpagwd, fNpagln);
                        Printf("                         X-AXIS: PARAMETER %3d  %s", ke1, fCpnam[ke1 - 1]);
                    }
                    if (fISW[4] >= 1) {
                        npcol = (nowpts + 1) / 2;
                        nfcol = nowpts / 2;
                        Printf("%5d POINTS ON CONTOUR.   FMIN=%13.5e   ERRDEF=%11.3g", nowpts, abest, fUp);
                        Printf("         %s%s%s%s", fCpnam[ke1 - 1], fCpnam[ke2 - 1], fCpnam[ke1 - 1], fCpnam[ke2 - 1]);
                        for (line = 1; line <= nfcol; ++line) {
                            lr = line + npcol;
                            Printf(" %5d%13.5e%13.5e          %5d%13.5e%13.5e", line, xptu[line - 1], yptu[line - 1], lr,
                                xptu[lr - 1], yptu[lr - 1]);
                        }
                        if (nfcol < npcol) {
                            Printf(" %5d%13.5e%13.5e", npcol, xptu[npcol - 1], yptu[npcol - 1]);
                        }
                    }
                    fItaur = 1;
                    mnfree(1);
                    mnfree(1);
                    i__1 = mpar * (mpar + 1) / 2;
                    for (j = 1; j <= i__1; ++j) {
                        fVhmat[j - 1] = fVthmat[j - 1];
                    }
                    for (i = 1; i <= mpar; ++i) {
                        fGlobcc[i - 1] = fCONTgcc[i - 1];
                        fWerr[i - 1] = fCONTw[i - 1];
                        fX[i - 1] = fXt[i - 1];
                    }
                    mninex(fX);
                    fEDM = sigsav;
                    fAmin = abest;
                    fISW[1] = isw2;
                    fISW[3] = isw4;
                    fDcovar = dc;
                    fItaur = 0;
                    fNfcnmx = nfmxin;
                    fIstrat = istrav;
                    fU[ke1 - 1] = u1min;
                    fU[ke2 - 1] = u2min;
                    L = 2000;
                    continue flow;
                }
                case 1350:
                    Printf(" INVALID PARAMETER NUMBERS.");
                    ierrf[0] = -1;
                    fCstatu = "USER ERROR";
                    L = 2000;
                    continue flow;
                case 1400:
                    Printf(" LESS THAN FOUR POINTS REQUESTED.");
                    ierrf[0] = -1;
                    fCstatu = "USER ERROR";
                    L = 2000;
                    continue flow;
                case 1500:
                    Printf(" MNCONT UNABLE TO FIND FOUR POINTS.");
                    fU[ke1 - 1] = u1min;
                    fU[ke2 - 1] = u2min;
                    ierrf[0] = 0;
                    fCstatu = "FAILED";
                    // fall through
                case 2000:
                    fCfrom = "MNContour ";
                    fNfcnfr = nfcnco;
                    return;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    /**
     * Cracks the free-format input: alphanumeric fields joined into comand (lnc characters), then the
     * numeric fields into plist (llist of them, at most mxp); ierr 0 if no errors.
     */
    public void mncrck(String cardbuf, int maxcwd, String[] comand, int[] lnc, int mxp, double[] plist, int[] llist,
                       int[] ierr, int isyswr) {
        final String cnumer = "123456789-.0+";
        int ifld, iend = 0, lend, left, nreq, ipos, kcmnd, nextb, ic, ibegin = 0, ltoadd;
        int ielmnt, nelmnt;
        final int[] lelmnt = new int[25];
        final int[] celmnt = new int[25];   // start of each word in crdbuf, -1 for a null word
        final char[] command = new char[25];
        String ctemp;

        final String crdbuf = cardbuf;
        lend = cardbuf.length();
        ielmnt = 0;
        nextb = 1;
        ierr[0] = 0;
        words:
        while (true) {
            // L10: loop over words
            boolean found = false;
            boolean comma = false;
            for (ipos = nextb; ipos <= lend; ++ipos) {
                ibegin = ipos;
                if (crdbuf.charAt(ipos - 1) == ' ') continue;
                if (crdbuf.charAt(ipos - 1) == ',') {
                    comma = true;
                    break;
                }
                found = true;
                break;
            }
            if (!found && !comma) break;
            if (found) {
                // L150: found beginning of word, look for end
                for (ipos = ibegin + 1; ipos <= lend; ++ipos) {
                    if (crdbuf.charAt(ipos - 1) == ' ') break;
                    if (crdbuf.charAt(ipos - 1) == ',') break;
                }
            }
            // L250
            iend = ipos - 1;
            ++ielmnt;
            if (iend >= ibegin) celmnt[ielmnt - 1] = ibegin - 1;
            else celmnt[ielmnt - 1] = -1;
            lelmnt[ielmnt - 1] = iend - ibegin + 1;
            if (lelmnt[ielmnt - 1] > 19) {
                Printf(" MINUIT WARNING: INPUT DATA WORD TOO LONG.");
                ctemp = TStr.sub(cardbuf, ibegin - 1, iend - ibegin + 1);
                Printf("     ORIGINAL:%s", ctemp);
                Printf(" TRUNCATED TO:%s", crdbuf.substring(celmnt[ielmnt - 1]));
                lelmnt[ielmnt - 1] = 19;
            }
            if (ipos >= lend) break;
            if (ielmnt >= 25) break;
            for (ipos = iend + 1; ipos <= lend; ++ipos) {
                if (crdbuf.charAt(ipos - 1) == ' ') continue;
                nextb = ipos;
                if (crdbuf.charAt(ipos - 1) == ',') nextb = ipos + 1;
                continue words;
            }
            break;
        }
        // L300
        nelmnt = ielmnt;
        command[0] = ' ';
        command[1] = 0;
        lnc[0] = 1;
        plist[0] = 0;
        llist[0] = 0;
        if (ielmnt != 0) {
            kcmnd = 0;
            boolean numeric = false;
            for (ielmnt = 1; ielmnt <= nelmnt; ++ielmnt) {
                if (celmnt[ielmnt - 1] < 0) {
                    numeric = true;
                    break;
                }
                final char first = crdbuf.charAt(celmnt[ielmnt - 1]);
                boolean isNum = false;
                for (ic = 1; ic <= 13; ++ic) {
                    if (first == cnumer.charAt(ic - 1)) {
                        isNum = true;
                        break;
                    }
                }
                if (isNum) {
                    numeric = true;
                    break;
                }
                if (kcmnd >= maxcwd) continue;
                left = maxcwd - kcmnd;
                ltoadd = lelmnt[ielmnt - 1];
                if (ltoadd > left) ltoadd = left;
                // strncpy(&command[kcmnd], celmnt, ltoadd): the characters, stopping (and padding) at a '\0'
                for (int q = 0; q < ltoadd; q++) {
                    final int at = celmnt[ielmnt - 1] + q;
                    command[kcmnd + q] = at < crdbuf.length() ? crdbuf.charAt(at) : 0;
                }
                kcmnd += ltoadd;
                if (kcmnd == maxcwd) continue;
                command[kcmnd] = ' ';
                ++kcmnd;
                command[kcmnd] = 0;
            }
            lnc[0] = kcmnd;
            if (numeric) {
                // L450: we have come to a numeric field
                llist[0] = 0;
                for (ifld = ielmnt; ifld <= nelmnt; ++ifld) {
                    ++llist[0];
                    if (llist[0] > mxp) {
                        nreq = nelmnt - ielmnt + 1;
                        Printf(" MINUIT WARNING IN MNCRCK: ");
                        Printf(" COMMAND HAS INPUT %5d NUMERIC FIELDS, BUT MINUIT CAN ACCEPT ONLY%3d", nreq, mxp);
                        break;
                    }
                    if (celmnt[ifld - 1] < 0) plist[llist[0] - 1] = 0;
                    else plist[llist[0] - 1] = sscanfDouble(crdbuf.substring(celmnt[ifld - 1]), plist[llist[0] - 1]);
                }
            }
        }
        // L900
        if (lnc[0] <= 0) lnc[0] = 1;
        comand[0] = TStr.cstr(command);
    }

    /**
     * Find point where MNEVAL=AMIN+UP along the line through XMIDCR,YMIDCR with direction XDIRCR,YDIRCR
     * (only KE1CR varied when KE2CR=0, from MINOS); crossing at (XMID,YMID) + AOPT*(XDIR,YDIR).
     */
    public void mncros(double[] aopt, int[] iercr) {
        final double[] alsb = new double[3];
        final double[] flsb = new double[3];
        final double[] coeff = new double[3];
        final double[] sdev = {0};
        double bmin, bmax, zmid, zdir, zlim;
        double aleft, aulim, fdist, adist, aminsv;
        double anext, slope = 0, s1, s2, x1, x2, ecarmn, ecarmx;
        double determ, rt, smalla, aright, aim, tla, tlf, dfda = 0, ecart;
        int iout = 0, i, ileft, maxlk, ibest = 0, ik, it;
        int noless, iworst = 0, iright, itoohi, kex, ipt;
        boolean ldebug;
        String chsign;
        final double[] fnext = {0};
        final int[] ierev = {0};
        x2 = 0;

        ldebug = fIdbg[6] >= 1;
        aminsv = fAmin;
        aim = fAmin + fUp;
        tlf = fUp * .01;
        tla = .01;
        fXpt[0] = 0;
        fYpt[0] = aim;
        fChpt[0] = ' ';
        ipt = 1;
        if (fKe2cr == 0) {
            fXpt[1] = -1;
            fYpt[1] = fAmin;
            fChpt[1] = '.';
            ipt = 2;
        }
        aulim = 100;
        for (ik = 1; ik <= 2; ++ik) {
            if (ik == 1) {
                kex = fKe1cr;
                zmid = fXmidcr;
                zdir = fXdircr;
            } else {
                if (fKe2cr == 0) continue;
                kex = fKe2cr;
                zmid = fYmidcr;
                zdir = fYdircr;
            }
            if (fNvarl[kex - 1] <= 1) continue;
            if (zdir == 0) continue;
            zlim = fAlim[kex - 1];
            if (zdir > 0) zlim = fBlim[kex - 1];
            aulim = TMath.min(aulim, (zlim - zmid) / zdir);
        }
        anext = 0;
        aopt[0] = anext;
        fLimset = false;
        if (aulim < aopt[0] + tla) fLimset = true;
        mneval(anext, fnext, ierev);
        if (ldebug) {
            Printf(" MNCROS: calls=%8d   AIM=%10.5f  F,A=%10.5f%10.5f", fNfcn, aim, fnext[0], aopt[0]);
        }
        int L;
        if (ierev[0] > 0) {
            L = 900;
        } else if (fLimset && fnext[0] <= aim) {
            L = 930;
        } else {
            ++ipt;
            fXpt[ipt - 1] = anext;
            fYpt[ipt - 1] = fnext[0];
            fChpt[ipt - 1] = charal.charAt(ipt - 1);
            alsb[0] = anext;
            flsb[0] = fnext[0];
            fnext[0] = TMath.max(fnext[0], aminsv + fUp * .1);
            aopt[0] = TMath.sqrt(fUp / (fnext[0] - aminsv)) - 1;
            if (Math.abs(fnext[0] - aim) < tlf) {
                L = 800;
            } else {
                if (aopt[0] < -.5) aopt[0] = -.5;
                if (aopt[0] > 1) aopt[0] = 1;
                fLimset = false;
                if (aopt[0] > aulim) {
                    aopt[0] = aulim;
                    fLimset = true;
                }
                mneval(aopt[0], fnext, ierev);
                if (ldebug) {
                    Printf(" MNCROS: calls=%8d   AIM=%10.5f  F,A=%10.5f%10.5f", fNfcn, aim, fnext[0], aopt[0]);
                }
                if (ierev[0] > 0) {
                    L = 900;
                } else if (fLimset && fnext[0] <= aim) {
                    L = 930;
                } else {
                    alsb[1] = aopt[0];
                    ++ipt;
                    fXpt[ipt - 1] = alsb[1];
                    fYpt[ipt - 1] = fnext[0];
                    fChpt[ipt - 1] = charal.charAt(ipt - 1);
                    flsb[1] = fnext[0];
                    dfda = (flsb[1] - flsb[0]) / (alsb[1] - alsb[0]);
                    L = dfda > 0 ? 460 : 300;
                }
            }
        }
        flow:
        while (true) {
            switch (L) {
                case 300: {
                    mnwarn("D", "MNCROS    ", "Looking for slope of the right sign");
                    maxlk = 15 - ipt;
                    boolean right = false;
                    for (it = 1; it <= maxlk; ++it) {
                        alsb[0] = alsb[1];
                        flsb[0] = flsb[1];
                        aopt[0] = alsb[0] + (double) it * .2;
                        fLimset = false;
                        if (aopt[0] > aulim) {
                            aopt[0] = aulim;
                            fLimset = true;
                        }
                        mneval(aopt[0], fnext, ierev);
                        if (ldebug) {
                            Printf(" MNCROS: calls=%8d   AIM=%10.5f  F,A=%10.5f%10.5f", fNfcn, aim, fnext[0], aopt[0]);
                        }
                        if (ierev[0] > 0) {
                            L = 900;
                            continue flow;
                        }
                        if (fLimset && fnext[0] <= aim) {
                            L = 930;
                            continue flow;
                        }
                        alsb[1] = aopt[0];
                        ++ipt;
                        fXpt[ipt - 1] = alsb[1];
                        fYpt[ipt - 1] = fnext[0];
                        fChpt[ipt - 1] = charal.charAt(ipt - 1);
                        flsb[1] = fnext[0];
                        dfda = (flsb[1] - flsb[0]) / (alsb[1] - alsb[0]);
                        if (dfda > 0) {
                            right = true;
                            break;
                        }
                    }
                    if (!right) {
                        mnwarn("W", "MNCROS    ", "Cannot find slope of the right sign");
                        L = 950;
                        continue flow;
                    }
                }
                // L450, L460: we have two points with the right slope
                // fall through
                case 460: {
                    aopt[0] = alsb[1] + (aim - flsb[1]) / dfda;
                    fdist = TMath.min(Math.abs(aim - flsb[0]), Math.abs(aim - flsb[1]));
                    adist = TMath.min(Math.abs(aopt[0] - alsb[0]), Math.abs(aopt[0] - alsb[1]));
                    tla = .01;
                    if (Math.abs(aopt[0]) > 1) tla = Math.abs(aopt[0]) * .01;
                    if (adist < tla && fdist < tlf) {
                        L = 800;
                        continue flow;
                    }
                    if (ipt >= 15) {
                        L = 950;
                        continue flow;
                    }
                    bmin = TMath.min(alsb[0], alsb[1]) - 1;
                    if (aopt[0] < bmin) aopt[0] = bmin;
                    bmax = TMath.max(alsb[0], alsb[1]) + 1;
                    if (aopt[0] > bmax) aopt[0] = bmax;
                    fLimset = false;
                    if (aopt[0] > aulim) {
                        aopt[0] = aulim;
                        fLimset = true;
                    }
                    mneval(aopt[0], fnext, ierev);
                    if (ldebug) {
                        Printf(" MNCROS: calls=%8d   AIM=%10.5f  F,A=%10.5f%10.5f", fNfcn, aim, fnext[0], aopt[0]);
                    }
                    if (ierev[0] > 0) {
                        L = 900;
                        continue flow;
                    }
                    if (fLimset && fnext[0] <= aim) {
                        L = 930;
                        continue flow;
                    }
                    alsb[2] = aopt[0];
                    ++ipt;
                    fXpt[ipt - 1] = alsb[2];
                    fYpt[ipt - 1] = fnext[0];
                    fChpt[ipt - 1] = charal.charAt(ipt - 1);
                    flsb[2] = fnext[0];
                    ecarmn = Math.abs(fnext[0] - aim);
                    ibest = 3;
                    ecarmx = 0;
                    noless = 0;
                    for (i = 1; i <= 3; ++i) {
                        ecart = Math.abs(flsb[i - 1] - aim);
                        if (ecart > ecarmx) {
                            ecarmx = ecart;
                            iworst = i;
                        }
                        if (ecart < ecarmn) {
                            ecarmn = ecart;
                            ibest = i;
                        }
                        if (flsb[i - 1] < aim) ++noless;
                    }
                    if (noless == 1 || noless == 2) {
                        L = 500;
                        continue flow;
                    }
                    if (noless == 0 && ibest != 3) {
                        L = 950;
                        continue flow;
                    }
                    if (noless == 3 && ibest != 3) {
                        alsb[1] = alsb[2];
                        flsb[1] = flsb[2];
                        L = 300;
                        continue flow;
                    }
                    alsb[iworst - 1] = alsb[2];
                    flsb[iworst - 1] = flsb[2];
                    dfda = (flsb[1] - flsb[0]) / (alsb[1] - alsb[0]);
                    L = 460;
                    continue flow;
                }
                case 500: {
                    mnpfit(alsb, flsb, 3, coeff, sdev);
                    if (coeff[2] <= 0) {
                        mnwarn("D", "MNCROS    ", "Curvature is negative near contour line.");
                    }
                    determ = coeff[1] * coeff[1] - coeff[2] * 4 * (coeff[0] - aim);
                    if (determ <= 0) {
                        mnwarn("D", "MNCROS    ", "Problem 2, impossible determinant");
                        L = 950;
                        continue flow;
                    }
                    rt = TMath.sqrt(determ);
                    x1 = (-coeff[1] + rt) / (coeff[2] * 2);
                    x2 = (-coeff[1] - rt) / (coeff[2] * 2);
                    s1 = coeff[1] + x1 * 2 * coeff[2];
                    s2 = coeff[1] + x2 * 2 * coeff[2];
                    if (s1 * s2 > 0) {
                        Printf(" MNCONTour problem 1");
                    }
                    aopt[0] = x1;
                    slope = s1;
                    if (s2 > 0) {
                        aopt[0] = x2;
                        slope = s2;
                    }
                    tla = .01;
                    if (Math.abs(aopt[0]) > 1) tla = Math.abs(aopt[0]) * .01;
                    if (Math.abs(aopt[0] - alsb[ibest - 1]) < tla && Math.abs(flsb[ibest - 1] - aim) < tlf) {
                        L = 800;
                        continue flow;
                    }
                    if (ipt >= 15) {
                        L = 950;
                        continue flow;
                    }
                    ileft = 0;
                    iright = 0;
                    ibest = 1;
                    ecarmx = 0;
                    ecarmn = Math.abs(aim - flsb[0]);
                    for (i = 1; i <= 3; ++i) {
                        ecart = Math.abs(flsb[i - 1] - aim);
                        if (ecart < ecarmn) {
                            ecarmn = ecart;
                            ibest = i;
                        }
                        if (ecart > ecarmx) {
                            ecarmx = ecart;
                        }
                        if (flsb[i - 1] > aim) {
                            if (iright == 0) iright = i;
                            else if (flsb[i - 1] > flsb[iright - 1]) iout = i;
                            else {
                                iout = iright;
                                iright = i;
                            }
                        } else if (ileft == 0) {
                            ileft = i;
                        } else if (flsb[i - 1] < flsb[ileft - 1]) {
                            iout = i;
                        } else {
                            iout = ileft;
                            ileft = i;
                        }
                    }
                    if (ecarmx > Math.abs(flsb[iout - 1] - aim) * 10) {
                        aopt[0] = aopt[0] * .5 + (alsb[iright - 1] + alsb[ileft - 1]) * .25;
                    }
                    smalla = tla * .1;
                    if (slope * smalla > tlf) smalla = tlf / slope;
                    aleft = alsb[ileft - 1] + smalla;
                    aright = alsb[iright - 1] - smalla;
                    if (aopt[0] < aleft) aopt[0] = aleft;
                    if (aopt[0] > aright) aopt[0] = aright;
                    if (aleft > aright) aopt[0] = (aleft + aright) * .5;
                    fLimset = false;
                    if (aopt[0] > aulim) {
                        aopt[0] = aulim;
                        fLimset = true;
                    }
                    mneval(aopt[0], fnext, ierev);
                    if (ldebug) {
                        Printf(" MNCROS: calls=%8d   AIM=%10.5f  F,A=%10.5f%10.5f", fNfcn, aim, fnext[0], aopt[0]);
                    }
                    if (ierev[0] > 0) {
                        L = 900;
                        continue flow;
                    }
                    if (fLimset && fnext[0] <= aim) {
                        L = 930;
                        continue flow;
                    }
                    ++ipt;
                    fXpt[ipt - 1] = aopt[0];
                    fYpt[ipt - 1] = fnext[0];
                    fChpt[ipt - 1] = charal.charAt(ipt - 1);
                    alsb[iout - 1] = aopt[0];
                    flsb[iout - 1] = fnext[0];
                    ibest = iout;
                    L = 500;
                    continue flow;
                }
                case 800:
                    iercr[0] = 0;
                    L = 1000;
                    continue flow;
                case 900:
                    L = ierev[0] == 1 ? 940 : 950;
                    continue flow;
                case 930:
                    iercr[0] = 1;
                    L = 1000;
                    continue flow;
                case 940:
                    iercr[0] = 2;
                    L = 1000;
                    continue flow;
                case 950:
                    iercr[0] = 3;
                    // fall through
                case 1000:
                    if (ldebug) {
                        itoohi = 0;
                        for (i = 1; i <= ipt; ++i) {
                            if (fYpt[i - 1] > aim + fUp) {
                                fYpt[i - 1] = aim + fUp;
                                fChpt[i - 1] = '+';
                                itoohi = 1;
                            }
                        }
                        fChpt[ipt] = 0;
                        chsign = "POSI";
                        if (fXdircr < 0) chsign = "NEGA";
                        if (fKe2cr == 0) {
                            Printf("  %sTIVE MINOS ERROR, PARAMETER %3d", chsign, fKe1cr);
                        }
                        if (itoohi == 1) {
                            Printf("POINTS LABELLED '+' WERE TOO HIGH TO PLOT.");
                        }
                        if (iercr[0] == 1) {
                            Printf("RIGHTMOST POINT IS UP AGAINST LIMIT.");
                        }
                        mnplot(fXpt, fYpt, fChpt, ipt, fNpagwd, fNpagln);
                    }
                    return;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    /** Makes sure that the current point is a local minimum and that the error matrix exists, or something good enough. */
    public void mncuve() {
        double wint;
        int ndex, iext, i, j;
        final double[] dxdi = {0};

        if (fISW[3] < 1) {
            Printf(" FUNCTION MUST BE MINIMIZED BEFORE CALLING %s", fCfrom);
            fApsi = fEpsi;
            mnmigr();
        }
        if (fISW[1] < 3) {
            mnhess();
            if (fISW[1] < 1) {
                mnwarn("W", fCfrom, "NO ERROR MATRIX.  WILL IMPROVISE.");
                for (i = 1; i <= fNpar; ++i) {
                    ndex = i * (i - 1) / 2;
                    for (j = 1; j <= i - 1; ++j) {
                        ++ndex;
                        fVhmat[ndex - 1] = 0;
                    }
                    ++ndex;
                    if (fG2[i - 1] <= 0) {
                        wint = fWerr[i - 1];
                        iext = fNexofi[i - 1];
                        if (fNvarl[iext - 1] > 1) {
                            mndxdi(fX[i - 1], i - 1, dxdi);
                            if (Math.abs(dxdi[0]) < .001) wint = .01;
                            else wint /= Math.abs(dxdi[0]);
                        }
                        fG2[i - 1] = fUp / (wint * wint);
                    }
                    fVhmat[ndex - 1] = 2 / fG2[i - 1];
                }
                fISW[1] = 1;
                fDcovar = 1;
            } else {
                mnwerr();
            }
        }
    }

    /** First derivatives of FCN (GRD): finite differences, or the user's transformed to internal coordinates (fISW[2]=1). */
    public void mnderi() {
        double step, dfmin, stepb4, dd, df, fs1 = 0;
        double tlrstp, tlrgrd, epspri, optstp, stpmax, stpmin, fs2 = 0, grbfor = 0, d1d2, xtf;
        int icyc, ncyc, iint, iext, i, nparx;
        boolean ldebug;

        nparx = fNpar;
        ldebug = fIdbg[2] >= 1;
        if (fAmin == fUndefi) mnamin();
        if (fISW[2] == 1) {
            // L100: derivatives calc by fcn
            for (iint = 1; iint <= fNpar; ++iint) {
                iext = fNexofi[iint - 1];
                if (fNvarl[iext - 1] <= 1) {
                    fGrd[iint - 1] = fGin[iext - 1];
                } else {
                    dd = (fBlim[iext - 1] - fAlim[iext - 1]) * .5 * TMath.cos(fX[iint - 1]);
                    fGrd[iint - 1] = fGin[iext - 1] * dd;
                }
            }
            return;
        }

        if (ldebug) {
            mninex(fX);
            nparx = fNpar;
            fs1 = eval(nparx, fs1, 4);
            ++fNfcn;
            if (fs1 != fAmin) {
                df = fAmin - fs1;
                mnwarn("D", "MNDERI", CFormat.sprintf("function value differs from AMIN by %12.3g", df));
                fAmin = fs1;
            }
            Printf("  FIRST DERIVATIVE DEBUG PRINTOUT.  MNDERI");
            Printf(" PAR    DERIV     STEP      MINSTEP   OPTSTEP  D1-D2    2ND DRV");
        }
        dfmin = fEpsma2 * 8 * (Math.abs(fAmin) + fUp);
        if (fIstrat <= 0) {
            ncyc = 2;
            tlrstp = .5;
            tlrgrd = .1;
        } else if (fIstrat == 1) {
            ncyc = 3;
            tlrstp = .3;
            tlrgrd = .05;
        } else {
            ncyc = 5;
            tlrstp = .1;
            tlrgrd = .02;
        }
        for (i = 1; i <= fNpar; ++i) {
            epspri = fEpsma2 + Math.abs(fGrd[i - 1] * fEpsma2);
            xtf = fX[i - 1];
            stepb4 = 0;
            boolean done = false;
            for (icyc = 1; icyc <= ncyc; ++icyc) {
                optstp = TMath.sqrt(dfmin / (Math.abs(fG2[i - 1]) + epspri));
                step = TMath.max(optstp, Math.abs(fGstep[i - 1] * .1));
                if (fGstep[i - 1] < 0 && step > .5) step = .5;
                stpmax = Math.abs(fGstep[i - 1]) * 10;
                if (step > stpmax) step = stpmax;
                stpmin = Math.abs(fEpsma2 * fX[i - 1]) * 8;
                if (step < stpmin) step = stpmin;
                if (Math.abs((step - stepb4) / step) < tlrstp) {
                    done = true;
                    break;
                }
                stepb4 = step;
                if (fGstep[i - 1] > 0) fGstep[i - 1] = Math.abs(step);
                else fGstep[i - 1] = -Math.abs(step);
                stepb4 = step;
                fX[i - 1] = xtf + step;
                mninex(fX);
                fs1 = eval(nparx, fs1, 4);
                ++fNfcn;
                fX[i - 1] = xtf - step;
                mninex(fX);
                fs2 = eval(nparx, fs2, 4);
                ++fNfcn;
                grbfor = fGrd[i - 1];
                fGrd[i - 1] = (fs1 - fs2) / (step * 2);
                fG2[i - 1] = (fs1 + fs2 - fAmin * 2) / (step * step);
                fX[i - 1] = xtf;
                if (ldebug) {
                    d1d2 = (fs1 + fs2 - fAmin * 2) / step;
                    Printf("%4d%11.3g%11.3g%10.2g%10.2g%10.2g%10.2g", i, fGrd[i - 1], step, stpmin, optstp, d1d2, fG2[i - 1]);
                }
                if (Math.abs(grbfor - fGrd[i - 1]) / (Math.abs(fGrd[i - 1]) + dfmin / step) < tlrgrd) {
                    done = true;
                    break;
                }
            }
            if (done) continue;
            if (ncyc == 1) continue;
            mnwarn("D", "MNDERI", CFormat.sprintf("First derivative not converged. %g%g", fGrd[i - 1], grbfor));
        }
        mninex(fX);
    }

    /** The factor between external and internal values of parameter ipar (one without limits). */
    public void mndxdi(double pint, int ipar, double[] dxdi) {
        final int i = fNexofi[ipar];
        dxdi[0] = 1;
        if (fNvarl[i - 1] > 1) {
            dxdi[0] = Math.abs((fBlim[i - 1] - fAlim[i - 1]) * TMath.cos(pint)) * .5;
        }
    }

    /** Eigenvalues (into work[0..n-1], increasing) and eigenvectors (into a) of a symmetric matrix a of leading dimension ndima. */
    public void mneig(double[] a, int ndima, int n, int mits, double[] work, double precis, int[] ifault) {
        double d__1;
        double b, c, f, h, r, s, hh, gl, pr, pt;
        int i, j, k, l, m = 0, i0, i1, j1, m1, n1;
        // a[i + j*ndima] of the Fortran is A[i + j*ndima - ao]; work[k] is W[k - 1]
        final int ao = ndima + 1;
        final double[] A = a;
        final double[] W = work;

        ifault[0] = 1;

        i = n;
        for (i1 = 2; i1 <= n; ++i1) {
            l = i - 2;
            f = A[i + (i - 1) * ndima - ao];
            gl = 0;
            if (l >= 1) {
                for (k = 1; k <= l; ++k) {
                    d__1 = A[i + k * ndima - ao];
                    gl += d__1 * d__1;
                }
            }
            h = gl + f * f;
            if (gl > 1e-35) {
                ++l;
                gl = TMath.sqrt(h);
                if (f >= 0) gl = -gl;
                W[n + i - 1] = gl;
                h -= f * gl;
                A[i + (i - 1) * ndima - ao] = f - gl;
                f = 0;
                for (j = 1; j <= l; ++j) {
                    A[j + i * ndima - ao] = A[i + j * ndima - ao] / h;
                    gl = 0;
                    for (k = 1; k <= j; ++k) {
                        gl += A[j + k * ndima - ao] * A[i + k * ndima - ao];
                    }
                    if (j < l) {
                        j1 = j + 1;
                        for (k = j1; k <= l; ++k) {
                            gl += A[k + j * ndima - ao] * A[i + k * ndima - ao];
                        }
                    }
                    W[n + j - 1] = gl / h;
                    f += gl * A[j + i * ndima - ao];
                }
                hh = f / (h + h);
                for (j = 1; j <= l; ++j) {
                    f = A[i + j * ndima - ao];
                    gl = W[n + j - 1] - hh * f;
                    W[n + j - 1] = gl;
                    for (k = 1; k <= j; ++k) {
                        A[j + k * ndima - ao] = A[j + k * ndima - ao] - f * W[n + k - 1] - gl * A[i + k * ndima - ao];
                    }
                }
                W[i - 1] = h;
            } else {
                W[i - 1] = 0;
                W[n + i - 1] = f;
            }
            --i;
        }
        W[0] = 0;
        W[n] = 0;
        for (i = 1; i <= n; ++i) {
            l = i - 1;
            if (!(W[i - 1] == 0 || l == 0)) {
                for (j = 1; j <= l; ++j) {
                    gl = 0;
                    for (k = 1; k <= l; ++k) {
                        gl += A[i + k * ndima - ao] * A[k + j * ndima - ao];
                    }
                    for (k = 1; k <= l; ++k) {
                        A[k + j * ndima - ao] -= gl * A[k + i * ndima - ao];
                    }
                }
            }
            W[i - 1] = A[i + i * ndima - ao];
            A[i + i * ndima - ao] = 1;
            if (l == 0) continue;
            for (j = 1; j <= l; ++j) {
                A[i + j * ndima - ao] = 0;
                A[j + i * ndima - ao] = 0;
            }
        }

        n1 = n - 1;
        for (i = 2; i <= n; ++i) {
            i0 = n + i - 1;
            W[i0 - 1] = W[i0];
        }
        // with n = 0 the C++ writes the element before the array: nothing that shows
        if (n > 0) W[n + n - 1] = 0;
        b = 0;
        f = 0;
        for (l = 1; l <= n; ++l) {
            j = 0;
            h = precis * (Math.abs(W[l - 1]) + Math.abs(W[n + l - 1]));
            if (b < h) b = h;
            for (m1 = l; m1 <= n; ++m1) {
                m = m1;
                if (Math.abs(W[n + m - 1]) <= b) break;
            }
            if (m != l) {
                do {
                    // L160
                    if (j == mits) return;
                    ++j;
                    pt = (W[l] - W[l - 1]) / (W[n + l - 1] * 2);
                    r = TMath.sqrt(pt * pt + 1);
                    pr = pt + r;
                    if (pt < 0) pr = pt - r;

                    h = W[l - 1] - W[n + l - 1] / pr;
                    for (i = l; i <= n; ++i) {
                        W[i - 1] -= h;
                    }
                    f += h;
                    pt = W[m - 1];
                    c = 1;
                    s = 0;
                    m1 = m - 1;
                    i = m;
                    for (i1 = l; i1 <= m1; ++i1) {
                        j = i;
                        --i;
                        gl = c * W[n + i - 1];
                        h = c * pt;
                        if (Math.abs(pt) >= Math.abs(W[n + i - 1])) {
                            c = W[n + i - 1] / pt;
                            r = TMath.sqrt(c * c + 1);
                            W[n + j - 1] = s * pt * r;
                            s = c / r;
                            c = 1 / r;
                        } else {
                            c = pt / W[n + i - 1];
                            r = TMath.sqrt(c * c + 1);
                            W[n + j - 1] = s * W[n + i - 1] * r;
                            s = 1 / r;
                            c /= r;
                        }
                        pt = c * W[i - 1] - s * gl;
                        W[j - 1] = h + s * (c * gl + s * W[i - 1]);
                        for (k = 1; k <= n; ++k) {
                            h = A[k + j * ndima - ao];
                            A[k + j * ndima - ao] = s * A[k + i * ndima - ao] + c * h;
                            A[k + i * ndima - ao] = c * A[k + i * ndima - ao] - s * h;
                        }
                    }
                    W[n + l - 1] = s * pt;
                    W[l - 1] = c * pt;
                } while (Math.abs(W[n + l - 1]) > b);
            }
            // L205
            W[l - 1] += f;
        }
        for (i = 1; i <= n1; ++i) {
            k = i;
            pt = W[i - 1];
            i1 = i + 1;
            for (j = i1; j <= n; ++j) {
                if (W[j - 1] >= pt) continue;
                k = j;
                pt = W[j - 1];
            }
            if (k == i) continue;
            W[k - 1] = W[i - 1];
            W[i - 1] = pt;
            for (j = 1; j <= n; ++j) {
                pt = A[j + i * ndima - ao];
                A[j + i * ndima - ao] = A[j + k * ndima - ao];
                A[j + k * ndima - ao] = pt;
            }
        }
        ifault[0] = 0;
    }

    /** The external error matrix (ndim x ndim, row by row) from the internal one. */
    public void mnemat(double[] emat, int ndim) {
        int i, j, k, npard, k2, kk, iz, nperln, kga, kgb;
        String ctemp;
        final double[] dxdi = {0};
        final double[] dxdj = {0};
        final int emat_dim1 = ndim;
        final int eo = emat_dim1 + 1;

        if (fISW[1] < 1) return;
        if (fISW[4] >= 2) {
            Printf(" EXTERNAL ERROR MATRIX.    NDIM=%4d    NPAR=%3d    ERR DEF=%g", ndim, fNpar, fUp);
        }
        npard = fNpar;
        if (ndim < fNpar) {
            npard = ndim;
            if (fISW[4] >= 0) {
                Printf(" USER-DIMENSIONED  ARRAY EMAT NOT BIG ENOUGH. REDUCED MATRIX CALCULATED.");
            }
        }
        nperln = (fNpagwd - 5) / 10;
        nperln = TMath.min(nperln, 13);
        if (fISW[4] >= 1 && npard > nperln) {
            Printf(" ELEMENTS ABOVE DIAGONAL ARE NOT PRINTED.");
        }
        for (i = 1; i <= npard; ++i) {
            mndxdi(fX[i - 1], i - 1, dxdi);
            kga = i * (i - 1) / 2;
            for (j = 1; j <= i; ++j) {
                mndxdi(fX[j - 1], j - 1, dxdj);
                kgb = kga + j;
                emat[i + j * emat_dim1 - eo] = dxdi[0] * fVhmat[kgb - 1] * dxdj[0] * fUp;
                emat[j + i * emat_dim1 - eo] = emat[i + j * emat_dim1 - eo];
            }
        }
        if (fISW[4] >= 2) {
            for (i = 1; i <= npard; ++i) {
                iz = npard;
                if (npard >= nperln) iz = i;
                ctemp = " ";
                for (k = 1; nperln < 0 ? k >= iz : k <= iz; k += nperln) {
                    k2 = k + nperln - 1;
                    if (k2 > iz) k2 = iz;
                    for (kk = k; kk <= k2; ++kk) {
                        ctemp += CFormat.sprintf("%10.3e ", emat[i + kk * emat_dim1 - eo]);
                    }
                    Printf("%s", ctemp);
                }
            }
        }
    }

    /** MINOS errors of parameter number, its parabolic error and global correlation (0 when not calculated). */
    public void mnerrs(int number, double[] eplus, double[] eminus, double[] eparab, double[] gcc) {
        int ndiag, iin, iex;
        final double[] dxdi = {0};

        iex = number + 1;
        if (iex > fNu || iex <= 0 || (iin = fNiofex[iex - 1]) <= 0) {
            eplus[0] = 0;
            eminus[0] = 0;
            eparab[0] = 0;
            gcc[0] = 0;
            return;
        }
        eplus[0] = fErp[iin - 1];
        if (eplus[0] == fUndefi) eplus[0] = 0;
        eminus[0] = fErn[iin - 1];
        if (eminus[0] == fUndefi) eminus[0] = 0;
        mndxdi(fX[iin - 1], iin - 1, dxdi);
        ndiag = iin * (iin + 1) / 2;
        eparab[0] = Math.abs(dxdi[0] * TMath.sqrt(Math.abs(fUp * fVhmat[ndiag - 1])));
        gcc[0] = 0;
        if (fISW[1] < 2) return;
        gcc[0] = fGlobcc[iin - 1];
    }

    /** The function analysed by MNCROS: the minimum of FCN over the other parameters at U(KE1CR) = XMIDCR + ANEXT*XDIRCR... */
    public void mneval(double anext, double[] fnext, int[] ierev) {
        int nparx;

        fU[fKe1cr - 1] = fXmidcr + anext * fXdircr;
        if (fKe2cr != 0) fU[fKe2cr - 1] = fYmidcr + anext * fYdircr;
        mninex(fX);
        nparx = fNpar;
        fnext[0] = eval(nparx, fnext[0], 4);
        ++fNfcn;
        ierev[0] = 0;
        if (fNpar > 0) {
            fItaur = 1;
            fAmin = fnext[0];
            fISW[0] = 0;
            mnmigr();
            fItaur = 0;
            fnext[0] = fAmin;
            if (fISW[0] >= 1) ierev[0] = 1;
            if (fISW[3] < 1) ierev[0] = 2;
        }
    }

    private static final String[] EXCM_NAMES = {
        "MINImize  ",
        "SEEk      ",
        "SIMplex   ",
        "MIGrad    ",
        "MINOs     ",
        "SET xxx   ",
        "SHOw xxx  ",
        "TOP of pag",
        "FIX       ",
        "REStore   ",
        "RELease   ",
        "SCAn      ",
        "CONtour   ",
        "HESse     ",
        "SAVe      ",
        "IMProve   ",
        "CALl fcn  ",
        "STAndard  ",
        "END       ",
        "EXIt      ",
        "RETurn    ",
        "CLEar     ",
        "HELP      ",
        "MNContour ",
        "STOp      ",
        "JUMp      ",
        "          ",
        "          ",
        "          ",
        "          ",
        "          ",
        "          ",
        "          ",
        "COVARIANCE",
        "PRINTOUT  ",
        "GRADIENT  ",
        "MATOUT    ",
        "ERROR DEF ",
        "LIMITS    ",
        "PUNCH     "};

    /** Interprets a command and takes appropriate action; ierflg as MNCOMD's ICONDN. */
    public void mnexcm(String command, double[] plist, int llist, int[] ierflg) {
        final String comand = command;
        final String[] cname = EXCM_NAMES;
        final int nntot = 40;

        double step, f = 0;
        final double[] xptu = new double[101];
        final double[] yptu = new double[101];
        int icol, kcol, iint, iext, lnow, nptu, i, iflag, ilist, nparx, nf = 0, lk, it, iw, inonde, nsuper = 0;
        int it2, ke1, ke2, nowprt, kll, krl;
        String chwhy, cneway = "", comd, ctemp;
        boolean lfreed, ltofix = false, lfixed;
        final int[] ierr = {0};
        final int[] ierrf = {0};

        lk = comand.length();
        if (lk > 20) lk = 20;
        fCword = comand;
        fCword = TStr.upper(fCword);
        for (iw = 1; iw <= fMaxpar; ++iw) {
            fWord7[iw - 1] = 0;
            if (iw <= llist) fWord7[iw - 1] = plist[iw - 1];
        }
        ++fIcomnd;
        fNfcnlc = fNfcn;
        if (!TStr.sub(fCword, 0, 7).equals("SET PRI") || fWord7[0] >= 0) {
            if (fISW[4] >= 0) {
                lnow = llist;
                if (lnow > 4) lnow = 4;
                Printf(" **********");
                ctemp = CFormat.sprintf(" **%5d **%s", fIcomnd, fCword);
                for (i = 1; i <= lnow; ++i) {
                    ctemp += CFormat.sprintf("%12.4g", plist[i - 1]);
                }
                Printf("%s", ctemp);
                inonde = 0;
                if (llist > lnow) {
                    kll = llist;
                    if (llist > fMaxpar) {
                        inonde = 1;
                        kll = fMaxpar;
                    }
                    Printf(" ***********");
                    for (i = lnow + 1; i <= kll; ++i) {
                        Printf("%12.4g", plist[i - 1]);
                    }
                }
                Printf(" **********");
                if (inonde > 0) {
                    Printf("  ERROR: ABOVE CALL TO MNEXCM TRIED TO PASS MORE THAN %d PARAMETERS.", fMaxpar);
                }
            }
        }
        fNfcnmx = (int) fWord7[0];
        if (fNfcnmx <= 0) {
            fNfcnmx = fNpar * 100 + 200 + fNpar * fNpar * 5;
        }
        fEpsi = fWord7[1];
        if (fEpsi <= 0) {
            fEpsi = fUp * .1;
        }
        fLnewmn = false;
        fLphead = true;
        fISW[0] = 0;
        ierflg[0] = 0;
        ctemp = TStr.sub(fCword, 0, 3);
        for (i = 1; i <= nntot; ++i) {
            if (TStr.strncmp(ctemp, cname[i - 1], 3) == 0) break;
        }
        if (i > nntot) {
            Printf("UNKNOWN COMMAND IGNORED:%s", comand);
            ierflg[0] = 3;
            return;
        }
        // L90: normal case, recognized MINUIT command
        if (TStr.sub(fCword, 0, 4).equals("MINO")) i = 5;
        if (i != 6 && i != 7 && i != 8 && i != 23) {
            fCfrom = cname[i - 1];
            fNfcnfr = fNfcn;
        }
        int L = switch (i) {
            case 1, 4 -> 400;
            case 2 -> 200;
            case 3 -> 300;
            case 5 -> 500;
            case 6, 7 -> 700;
            case 8 -> 800;
            case 9 -> 900;
            case 10 -> 1000;
            case 11 -> 1100;
            case 12 -> 1200;
            case 13 -> 1300;
            case 14 -> 1400;
            case 15 -> 1500;
            case 16 -> 1600;
            case 17 -> 1700;
            case 18 -> 1800;
            case 19, 20, 21, 25 -> 1900;
            case 22 -> 2200;
            case 23 -> 2300;
            case 24 -> 2400;
            case 26 -> 2600;
            case 34 -> 3400;
            case 35 -> 3500;
            case 36 -> 3600;
            case 37 -> 3700;
            case 38 -> 3800;
            case 39 -> 3900;
            case 40 -> 4000;
            default -> 3300;
        };
        flow:
        while (true) {
            switch (L) {
                case 200: // seek
                    mnseek();
                    return;
                case 300: // simplex
                    mnsimp();
                    if (fISW[3] < 1) ierflg[0] = 4;
                    return;
                case 400: // migrad, minimize
                    nf = fNfcn;
                    fApsi = fEpsi;
                    mnmigr();
                    mnwerr();
                    if (fISW[3] >= 1) return;
                    ierflg[0] = 4;
                    if (fISW[0] == 1) return;
                    if (TStr.sub(fCword, 0, 3).equals("MIG")) return;

                    fNfcnmx = fNfcnmx + nf - fNfcn;
                    nf = fNfcn;
                    mnsimp();
                    if (fISW[0] == 1) return;
                    fNfcnmx = fNfcnmx + nf - fNfcn;
                    mnmigr();
                    if (fISW[3] >= 1) ierflg[0] = 0;
                    mnwerr();
                    return;
                case 500: // minos
                    nsuper = fNfcn + ((fNpar + 1) << 1) * fNfcnmx;
                    fEpsi = fUp * .1;
                    while (true) {
                        // L510
                        fCfrom = cname[i - 1];
                        mncuve();
                        mnmnos();
                        if (!fLnewmn) return;
                        mnrset(0);
                        mnmigr();
                        mnwerr();
                        if (fNfcn < nsuper) continue;
                        break;
                    }
                    Printf(" TOO MANY FUNCTION CALLS. MINOS GIVES UP");
                    ierflg[0] = 4;
                    return;
                case 700: // set, show
                    mnset();
                    return;
                case 800: // top of page
                    Printf("1");
                    return;
                case 900: // fix
                    ltofix = true;
                    // fall through
                case 901: { // (also release)
                    lfreed = false;
                    lfixed = false;
                    if (llist == 0) {
                        Printf("%s:  NO PARAMETERS REQUESTED ", fCword);
                        return;
                    }
                    for (ilist = 1; ilist <= llist; ++ilist) {
                        iext = (int) plist[ilist - 1];
                        chwhy = " IS UNDEFINED.";
                        boolean ignored;
                        if (iext <= 0 || iext > fNu || fNvarl[iext - 1] < 0) {
                            ignored = true;
                        } else {
                            chwhy = " IS CONSTANT.  ";
                            if (fNvarl[iext - 1] == 0) {
                                ignored = true;
                            } else {
                                ignored = false;
                                iint = fNiofex[iext - 1];
                                if (ltofix) {
                                    chwhy = " ALREADY FIXED.";
                                    if (iint == 0) {
                                        ignored = true;
                                    } else {
                                        mnfixp(iint - 1, ierr);
                                        if (ierr[0] == 0) lfixed = true;
                                        else ierflg[0] = 4;
                                    }
                                } else {
                                    chwhy = " ALREADY VARIABLE.";
                                    if (iint > 0) {
                                        ignored = true;
                                    } else {
                                        krl = -Math.abs(iext);
                                        mnfree(krl);
                                        lfreed = true;
                                    }
                                }
                            }
                        }
                        if (ignored && fISW[4] >= 0) Printf(" PARAMETER %4d %s IGNORED.", iext, chwhy);
                    }
                    if (lfreed || lfixed) mnrset(0);
                    if (lfreed) {
                        fISW[1] = 0;
                        fDcovar = 1;
                        fEDM = fBigedm;
                        fISW[3] = 0;
                    }
                    mnwerr();
                    if (fISW[4] > 1) mnprin(5, fAmin);
                    return;
                }
                case 1000: // restore
                    it = (int) fWord7[0];
                    if (it > 1 || it < 0) {
                        Printf(" IGNORED.  UNKNOWN ARGUMENT:%4d", it);
                        ierflg[0] = 3;
                        return;
                    }
                    lfreed = fNpfix > 0;
                    mnfree(it);
                    if (lfreed) {
                        mnrset(0);
                        fISW[1] = 0;
                        fDcovar = 1;
                        fEDM = fBigedm;
                    }
                    return;
                case 1100: // release
                    ltofix = false;
                    L = 901;
                    continue flow;
                case 1200: // scan
                    iext = (int) fWord7[0];
                    if (iext > 0) {
                        it2 = 0;
                        if (iext <= fNu) it2 = fNiofex[iext - 1];
                        if (it2 <= 0) {
                            Printf(" PARAMETER %4d NOT VARIABLE.", iext);
                            ierflg[0] = 3;
                            return;
                        }
                    }
                    mnscan();
                    return;
                case 1300: // contour
                    ke1 = (int) fWord7[0];
                    ke2 = (int) fWord7[1];
                    if (ke1 == 0) {
                        if (fNpar == 2) {
                            ke1 = fNexofi[0];
                            ke2 = fNexofi[1];
                        } else {
                            Printf("%s:  NO PARAMETERS REQUESTED ", fCword);
                            ierflg[0] = 3;
                            return;
                        }
                    }
                    fNfcnmx = 1000;
                    mncntr(ke1 - 1, ke2 - 1, ierrf);
                    if (ierrf[0] > 0) ierflg[0] = 3;
                    return;
                case 1400: // hesse
                    mnhess();
                    mnwerr();
                    if (fISW[4] >= 0) mnprin(2, fAmin);
                    if (fISW[4] >= 1) mnmatu(1);
                    return;
                case 1500: // save
                    mnsave();
                    return;
                case 1600: // improve
                    mncuve();
                    mnimpr();
                    if (fLnewmn) {
                        L = 400;
                        continue flow;
                    }
                    ierflg[0] = 4;
                    return;
                case 1700: { // call fcn
                    iflag = (int) fWord7[0];
                    nparx = fNpar;
                    f = fUndefi;
                    f = eval(nparx, f, iflag);
                    ++fNfcn;
                    nowprt = 0;
                    if (f != fUndefi) {
                        if (fAmin == fUndefi) {
                            fAmin = f;
                            nowprt = 1;
                        } else if (f < fAmin) {
                            fAmin = f;
                            nowprt = 1;
                        }
                        if (fISW[4] >= 0 && iflag <= 5 && nowprt == 1) {
                            mnprin(5, fAmin);
                        }
                        if (iflag == 3) fFval3 = f;
                    }
                    if (iflag > 5) mnrset(1);
                    return;
                }
                case 1800: // standard
                    return;
                case 1900: // return, stop, end, exit
                    it = (int) fWord7[0];
                    if (fFval3 != fAmin && it == 0) {
                        iflag = 3;
                        if (fISW[4] >= 0) Printf(" CALL TO USER FUNCTION WITH IFLAG = 3");
                        nparx = fNpar;
                        f = eval(nparx, f, iflag);
                        ++fNfcn;
                    }
                    ierflg[0] = 11;
                    if (TStr.sub(fCword, 0, 3).equals("END")) ierflg[0] = 10;
                    if (TStr.sub(fCword, 0, 3).equals("RET")) ierflg[0] = 12;
                    return;
                case 2200: // clear
                    mncler();
                    if (fISW[4] >= 1) {
                        Printf(" MINUIT MEMORY CLEARED. NO PARAMETERS NOW DEFINED.");
                    }
                    return;
                case 2300: // help
                    kcol = 0;
                    for (icol = 5; icol <= lk; ++icol) {
                        if (TStr.at(fCword, icol - 1) == ' ') continue;
                        kcol = icol;
                        break;
                    }
                    if (kcol == 0) comd = "*   ";
                    else comd = TStr.sub(fCword, kcol - 1, lk - kcol + 1);
                    mnhelp(comd);
                    return;
                case 2400: // MNContour
                    fEpsi = fUp * .05;
                    ke1 = (int) fWord7[0];
                    ke2 = (int) fWord7[1];
                    if (ke1 == 0 && fNpar == 2) {
                        ke1 = fNexofi[0];
                        ke2 = fNexofi[1];
                    }
                    nptu = (int) fWord7[2];
                    if (nptu <= 0) nptu = 20;
                    if (nptu > 101) nptu = 101;
                    fNfcnmx = (nptu + 5) * 100 * (fNpar + 1);
                    mncont(ke1 - 1, ke2 - 1, nptu, xptu, yptu, ierrf);
                    if (ierrf[0] < nptu) ierflg[0] = 4;
                    if (ierrf[0] == -1) ierflg[0] = 3;
                    return;
                case 2600: { // jump
                    step = fWord7[0];
                    if (step <= 0) step = 2;
                    final double[] rno = {0};
                    final int[] izero = {0};
                    for (i = 1; i <= fNpar; ++i) {
                        mnrn15(rno, izero);
                        rno[0] = rno[0] * 2 - 1;
                        fX[i - 1] += rno[0] * step * fWerr[i - 1];
                    }
                    mninex(fX);
                    mnamin();
                    mnrset(0);
                    return;
                }
                case 3300: // blank line
                    Printf(" BLANK COMMAND IGNORED.");
                    ierflg[0] = 1;
                    return;
                case 3400: // covariance (obsolete)
                    Printf(" THE *COVARIANCE* COMMAND IS OSBSOLETE. THE COVARIANCE MATRIX IS NOW SAVED IN A DIFFERENT FORMAT WITH THE *SAVE* COMMAND AND READ IN WITH:*SET COVARIANCE*");
                    ierflg[0] = 3;
                    return;
                case 3500: // printout
                    cneway = "SET PRInt ";
                    L = 3100;
                    continue flow;
                case 3600: // gradient
                    cneway = "SET GRAd  ";
                    L = 3100;
                    continue flow;
                case 3700: // matout
                    cneway = "SHOW COVar";
                    L = 3100;
                    continue flow;
                case 3800: // error def
                    cneway = "SET ERRdef";
                    L = 3100;
                    continue flow;
                case 3900: // limits
                    cneway = "SET LIMits";
                    L = 3100;
                    continue flow;
                case 4000: // punch
                    cneway = "SAVE      ";
                    // fall through
                case 3100: // come from obsolete commands
                    Printf(" OBSOLETE COMMAND:%s   PLEASE USE:%s", fCword, cneway);
                    fCword = cneway;
                    if (fCword.equals("SAVE      ")) {
                        L = 1500;
                        continue flow;
                    }
                    L = 700;
                    continue flow;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    /** Transforms the external parameter values U to internal values in the dense array pint. */
    public void mnexin(double[] pint) {
        int iint, iext;
        final double[] pexti = {0};
        final double[] pinti = {0};

        fLimset = false;
        for (iint = 1; iint <= fNpar; ++iint) {
            iext = fNexofi[iint - 1];
            pexti[0] = fU[iext - 1];
            mnpint(pexti, iext - 1, pinti);
            fU[iext - 1] = pexti[0];
            pint[iint - 1] = pinti[0];
        }
    }

    /** Removes parameter iint from the internal parameter list and fills the hole. */
    public void mnfixp(int iint1, int[] ierr) {
        double yyover;
        int kold, nold, ndex, knew, iext, i, j, m, n, lc, ik;

        ierr[0] = 0;
        final int iint = iint1 + 1;
        if (iint > fNpar || iint <= 0) {
            ierr[0] = 1;
            Printf(" MINUIT ERROR.  ARGUMENT TO MNFIXP=%4d", iint);
            return;
        }
        iext = fNexofi[iint - 1];
        if (fNpfix >= fMaxpar) {
            ierr[0] = 1;
            Printf(" MINUIT CANNOT FIX PARAMETER %4d MAXIMUM NUMBER THAT CAN BE FIXED IS %d", iext, fMaxpar);
            return;
        }
        fNiofex[iext - 1] = 0;
        nold = fNpar;
        --fNpar;
        ++fNpfix;
        fIpfix[fNpfix - 1] = iext;
        lc = iint;
        fXs[fNpfix - 1] = fX[lc - 1];
        fXts[fNpfix - 1] = fXt[lc - 1];
        fDirins[fNpfix - 1] = fWerr[lc - 1];
        fGrds[fNpfix - 1] = fGrd[lc - 1];
        fG2s[fNpfix - 1] = fG2[lc - 1];
        fGsteps[fNpfix - 1] = fGstep[lc - 1];
        for (ik = iext + 1; ik <= fNu; ++ik) {
            if (fNiofex[ik - 1] > 0) {
                lc = fNiofex[ik - 1] - 1;
                fNiofex[ik - 1] = lc;
                fNexofi[lc - 1] = ik;
                fX[lc - 1] = fX[lc];
                fXt[lc - 1] = fXt[lc];
                fDirin[lc - 1] = fDirin[lc];
                fWerr[lc - 1] = fWerr[lc];
                fGrd[lc - 1] = fGrd[lc];
                fG2[lc - 1] = fG2[lc];
                fGstep[lc - 1] = fGstep[lc];
            }
        }
        if (fISW[1] <= 0) return;
        if (fNpar <= 0) return;
        for (i = 1; i <= nold; ++i) {
            m = TMath.max(i, iint);
            n = TMath.min(i, iint);
            ndex = m * (m - 1) / 2 + n;
            fFIXPyy[i - 1] = fVhmat[ndex - 1];
        }
        yyover = 1 / fFIXPyy[iint - 1];
        knew = 0;
        kold = 0;
        for (i = 1; i <= nold; ++i) {
            for (j = 1; j <= i; ++j) {
                ++kold;
                if (j == iint || i == iint) continue;
                ++knew;
                fVhmat[knew - 1] = fVhmat[kold - 1] - fFIXPyy[j - 1] * fFIXPyy[i - 1] * yyover;
            }
        }
    }

    /** Restores fixed parameter(s) to variable status: k=0 all, k=1 the last fixed, k=-i external parameter i. */
    public void mnfree(int k) {
        double grdv, xv, dirinv, g2v, gstepv, xtv;
        int i, ipsav, ka, lc, ik = 0, iq, ir, is;

        if (k > 1) {
            Printf(" CALL TO MNFREE IGNORED.  ARGUMENT GREATER THAN ONE");
        }
        if (fNpfix < 1) {
            Printf(" CALL TO MNFREE IGNORED.  THERE ARE NO FIXED PARAMETERS");
        }
        if (!(k == 1 || k == 0)) {
            ka = Math.abs(k);
            if (fNiofex[ka - 1] != 0) {
                Printf(" IGNORED.  PARAMETER SPECIFIED IS ALREADY VARIABLE.");
                return;
            }
            boolean found = false;
            if (fNpfix >= 1) {
                for (ik = 1; ik <= fNpfix; ++ik) {
                    if (fIpfix[ik - 1] == ka) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                Printf(" PARAMETER %4d NOT FIXED.  CANNOT BE RELEASED.", ka);
                return;
            }
            if (ik != fNpfix) {
                ipsav = ka;
                xv = fXs[ik - 1];
                xtv = fXts[ik - 1];
                dirinv = fDirins[ik - 1];
                grdv = fGrds[ik - 1];
                g2v = fG2s[ik - 1];
                gstepv = fGsteps[ik - 1];
                for (i = ik + 1; i <= fNpfix; ++i) {
                    fIpfix[i - 2] = fIpfix[i - 1];
                    fXs[i - 2] = fXs[i - 1];
                    fXts[i - 2] = fXts[i - 1];
                    fDirins[i - 2] = fDirins[i - 1];
                    fGrds[i - 2] = fGrds[i - 1];
                    fG2s[i - 2] = fG2s[i - 1];
                    fGsteps[i - 2] = fGsteps[i - 1];
                }
                fIpfix[fNpfix - 1] = ipsav;
                fXs[fNpfix - 1] = xv;
                fXts[fNpfix - 1] = xtv;
                fDirins[fNpfix - 1] = dirinv;
                fGrds[fNpfix - 1] = grdv;
                fG2s[fNpfix - 1] = g2v;
                fGsteps[fNpfix - 1] = gstepv;
            }
        }
        // L40: restore last parameter in fixed list
        while (fNpfix >= 1) {
            ir = fIpfix[fNpfix - 1];
            is = 0;
            for (ik = fNu; ik >= ir; --ik) {
                if (fNiofex[ik - 1] > 0) {
                    lc = fNiofex[ik - 1] + 1;
                    is = lc - 1;
                    fNiofex[ik - 1] = lc;
                    fNexofi[lc - 1] = ik;
                    fX[lc - 1] = fX[lc - 2];
                    fXt[lc - 1] = fXt[lc - 2];
                    fDirin[lc - 1] = fDirin[lc - 2];
                    fWerr[lc - 1] = fWerr[lc - 2];
                    fGrd[lc - 1] = fGrd[lc - 2];
                    fG2[lc - 1] = fG2[lc - 2];
                    fGstep[lc - 1] = fGstep[lc - 2];
                }
            }
            ++fNpar;
            if (is == 0) is = fNpar;
            fNiofex[ir - 1] = is;
            fNexofi[is - 1] = ir;
            iq = fNpfix;
            fX[is - 1] = fXs[iq - 1];
            fXt[is - 1] = fXts[iq - 1];
            fDirin[is - 1] = fDirins[iq - 1];
            fWerr[is - 1] = fDirins[iq - 1];
            fGrd[is - 1] = fGrds[iq - 1];
            fG2[is - 1] = fG2s[iq - 1];
            fGstep[is - 1] = fGsteps[iq - 1];
            --fNpfix;
            fISW[1] = 0;
            fDcovar = 1;
            if (fISW[4] - fItaur >= 1) {
                Printf("                   PARAMETER %4d  %s RESTORED TO VARIABLE.", ir, fCpnam[ir - 1]);
            }
            if (k != 0) break;
        }
        // L300
        mnexin(fX);
    }

    /** Interprets SET GRAD: the user's first derivatives, checked against MINUIT's unless forced. */
    public void mngrad() {
        double err;
        int i, nparx, lc, istsav;
        boolean lnone;

        fISW[2] = 1;
        nparx = fNpar;
        if (fWord7[0] > 0) return;

        for (i = 1; i <= fNu; ++i) {
            fGin[i - 1] = fUndefi;
        }
        mninex(fX);
        eval(nparx, 0, 2);
        ++fNfcn;
        mnderi();
        for (i = 1; i <= fNpar; ++i) {
            fGRADgf[i - 1] = fGrd[i - 1];
        }
        fISW[2] = 0;
        istsav = fIstrat;
        fIstrat = 2;
        mnhes1();
        fIstrat = istsav;
        Printf(" CHECK OF GRADIENT CALCULATION IN FCN");
        Printf("            PARAMETER      G(IN FCN)   G(MINUIT)  DG(MINUIT)   AGREEMENT");
        fISW[2] = 1;
        lnone = false;
        for (lc = 1; lc <= fNpar; ++lc) {
            i = fNexofi[lc - 1];
            String cwd = "GOOD";
            err = fDgrd[lc - 1];
            if (Math.abs(fGRADgf[lc - 1] - fGrd[lc - 1]) > err) {
                cwd = " BAD";
                fISW[2] = 0;
            }
            if (fGin[i - 1] == fUndefi) {
                cwd = "NONE";
                lnone = true;
                fGRADgf[lc - 1] = 0;
                fISW[2] = 0;
            }
            Printf("       %5d  %10s%12.4e%12.4e%12.4e    %s", i, fCpnam[i - 1], fGRADgf[lc - 1], fGrd[lc - 1], err, cwd);
        }
        if (lnone) {
            Printf("  AGREEMENT=NONE  MEANS FCN DID NOT CALCULATE THE DERIVATIVE");
        }
        if (fISW[2] == 0) {
            Printf(" MINUIT DOES NOT ACCEPT DERIVATIVE CALCULATIONS BY FCN");
            Printf(" TO FORCE ACCEPTANCE, ENTER *SET GRAD    1*");
        }
    }

    /** HELP for the MINUIT interactive commands: "*" or "" the list of all of them, else the one named (at least 3 characters). */
    public void mnhelp(String command) {
        String comd = command == null ? "" : command;
        comd = TStr.upper(comd);
        if (comd.length() == 0 || comd.charAt(0) == '*' || comd.charAt(0) == '?' || comd.charAt(0) == 0 || comd.equals("HELP")) {
            Printf("   ==>List of MINUIT Interactive commands:");
            Printf(" CLEar     Reset all parameter names and values undefined");
            Printf(" CONtour   Make contour map of the user function");
            Printf(" EXIT      Exit from Interactive Minuit");
            Printf(" FIX       Cause parameter(s) to remain constant");
            Printf(" HESse     Calculate the Hessian or error matrix.");
            Printf(" IMPROVE   Search for a new minimum around current minimum");
            Printf(" MIGrad    Minimize by the method of Migrad");
            Printf(" MINImize  MIGRAD + SIMPLEX method if Migrad fails");
            Printf(" MINOs     Exact (non-linear) parameter error analysis");
            Printf(" MNContour Calculate one MINOS function contour");
            Printf(" PARameter Define or redefine new parameters and values");
            Printf(" RELease   Make previously FIXed parameters variable again");
            Printf(" REStore   Release last parameter fixed");
            Printf(" SAVe      Save current parameter values on a file");
            Printf(" SCAn      Scan the user function by varying parameters");
            Printf(" SEEk      Minimize by the method of Monte Carlo");
            Printf(" SET       Set various MINUIT constants or conditions");
            Printf(" SHOw      Show values of current constants or conditions");
            Printf(" SIMplex   Minimize by the method of Simplex");
            return;
        }
        if (TStr.strncmp(comd, "CLE", 3) == 0) {
            Printf(" ***>CLEAR");
            Printf(" Resets all parameter names and values to undefined.");
            Printf(" Must normally be followed by a PARameters command or ");
            Printf(" equivalent, in order to define parameter values.");
            return;
        }
        if (TStr.strncmp(comd, "CON", 3) == 0) {
            Printf(" ***>CONTOUR <par1>  <par2>  [devs]  [ngrid]");
            Printf(" Instructs Minuit to trace contour lines of the user function");
            Printf(" with respect to the two parameters whose external numbers");
            Printf(" are <par1> and <par2>.");
            Printf(" Other variable parameters of the function, if any, will have");
            Printf(" their values fixed at the current values during the contour");
            Printf(" tracing. The optional parameter [devs] (default value 2.)");
            Printf(" gives the number of standard deviations in each parameter");
            Printf(" which should lie entirely within the plotting area.");
            Printf(" Optional parameter [ngrid] (default value 25 unless page");
            Printf(" size is too small) determines the resolution of the plot,");
            Printf(" i.e. the number of rows and columns of the grid at which the");
            Printf(" function will be evaluated. [See also MNContour.]");
            return;
        }
        if (TStr.strncmp(comd, "END", 3) == 0) {
            Printf(" ***>END");
            Printf(" Signals the end of a data block (i.e., the end of a fit),");
            Printf(" and implies that execution should continue, because another");
            Printf(" Data Block follows. A Data Block is a set of Minuit data");
            Printf(" consisting of");
            Printf("     (1) A Title,");
            Printf("     (2) One or more Parameter Definitions,");
            Printf("     (3) A blank line, and");
            Printf("     (4) A set of Minuit Commands.");
            Printf(" The END command is used when more than one Data Block is to");
            Printf(" be used with the same FCN function. It first causes Minuit");
            Printf(" to issue a CALL FCN with IFLAG=3, in order to allow FCN to");
            Printf(" perform any calculations associated with the final fitted");
            Printf(" parameter values, unless a CALL FCN 3 command has already");
            Printf(" been executed at the current FCN value.");
            return;
        }
        if (TStr.strncmp(comd, "EXI", 3) == 0) {
            Printf(" ***>EXIT");
            Printf(" Signals the end of execution.");
            Printf(" The EXIT command first causes Minuit to issue a CALL FCN");
            Printf(" with IFLAG=3, to allow FCN to perform any calculations");
            Printf(" associated with the final fitted parameter values, unless a");
            Printf(" CALL FCN 3 command has already been executed.");
            return;
        }
        if (TStr.strncmp(comd, "FIX", 3) == 0) {
            Printf(" ***>FIX} <parno> [parno] ... [parno]");
            Printf(" Causes parameter(s) <parno> to be removed from the list of");
            Printf(" variable parameters, and their value(s) will remain constant");
            Printf(" during subsequent minimizations, etc., until another command");
            Printf(" changes their value(s) or status.");
            return;
        }
        if (TStr.strncmp(comd, "HES", 3) == 0) {
            Printf(" ***>HESse  [maxcalls]");
            Printf(" Calculate, by finite differences, the Hessian or error matrix.");
            Printf("  That is, it calculates the full matrix of second derivatives");
            Printf(" of the function with respect to the currently variable");
            Printf(" parameters, and inverts it, printing out the resulting error");
            Printf(" matrix. The optional argument [maxcalls] specifies the");
            Printf(" (approximate) maximum number of function calls after which");
            Printf(" the calculation will be stopped.");
            return;
        }
        if (TStr.strncmp(comd, "IMP", 3) == 0) {
            Printf(" ***>IMPROVE  [maxcalls]");
            Printf(" If a previous minimization has converged, and the current");
            Printf(" values of the parameters therefore correspond to a local");
            Printf(" minimum of the function, this command requests a search for");
            Printf(" additional distinct local minima.");
            Printf(" The optional argument [maxcalls] specifies the (approximate");
            Printf(" maximum number of function calls after which the calculation");
            Printf(" will be stopped.");
            return;
        }
        if (TStr.strncmp(comd, "MIG", 3) == 0) {
            Printf(" ***>MIGrad  [maxcalls]  [tolerance]");
            Printf(" Causes minimization of the function by the method of Migrad,");
            Printf(" the most efficient and complete single method, recommended");
            Printf(" for general functions (see also MINImize).");
            Printf(" The minimization produces as a by-product the error matrix");
            Printf(" of the parameters, which is usually reliable unless warning");
            Printf(" messages are produced.");
            Printf(" The optional argument [maxcalls] specifies the (approximate)");
            Printf(" maximum number of function calls after which the calculation");
            Printf(" will be stopped even if it has not yet converged.");
            Printf(" The optional argument [tolerance] specifies required tolerance");
            Printf(" on the function value at the minimum.");
            Printf(" The default tolerance is 0.1, and the minimization will stop");
            Printf(" when the estimated vertical distance to the minimum (EDM) is");
            Printf(" less than 0.001*[tolerance]*UP (see [SET ERRordef]).");
            return;
        }
        if (TStr.strncmp(comd, "MINI", 4) == 0) {
            Printf(" ***>MINImize  [maxcalls] [tolerance]");
            Printf(" Causes minimization of the function by the method of Migrad,");
            Printf(" as does the MIGrad command, but switches to the SIMplex method");
            Printf(" if Migrad fails to converge. Arguments are as for MIGrad.");
            Printf(" Note that command requires four characters to be unambiguous.");
            return;
        }
        if (TStr.strncmp(comd, "MIN0", 4) == 0) {
            Printf(" ***>MINOs  [maxcalls]  [parno] [parno] ...");
            Printf(" Causes a Minos error analysis to be performed on the parameters");
            Printf(" whose numbers [parno] are specified. If none are specified,");
            Printf(" Minos errors are calculated for all variable parameters.");
            Printf(" Minos errors may be expensive to calculate, but are very");
            Printf(" reliable since they take account of non-linearities in the");
            Printf(" problem as well as parameter correlations, and are in general");
            Printf(" asymmetric.");
            Printf(" The optional argument [maxcalls] specifies the (approximate)");
            Printf(" maximum number of function calls per parameter requested,");
            Printf(" after which the calculation will stop for that parameter.");
            return;
        }
        if (TStr.strncmp(comd, "MNC", 3) == 0) {
            Printf(" ***>MNContour  <par1> <par2> [npts]");
            Printf(" Calculates one function contour of FCN with respect to");
            Printf(" parameters par1 and par2, with FCN minimized always with");
            Printf(" respect to all other NPAR-2 variable parameters (if any).");
            Printf(" Minuit will try to find npts points on the contour (default 20)");
            Printf(" If only two parameters are variable at the time, it is not");
            Printf(" necessary to specify their numbers. To calculate more than");
            Printf(" one contour, it is necessary to SET ERRordef to the appropriate");
            Printf(" value and issue the MNContour command for each contour.");
            return;
        }
        if (TStr.strncmp(comd, "PAR", 3) == 0) {
            Printf(" ***>PARameters");
            Printf(" followed by one or more parameter definitions.");
            Printf(" Parameter definitions are of the form:");
            Printf("   <number>  ''name''  <value>  <step>  [lolim] [uplim] ");
            Printf(" for example:");
            Printf("  3  ''K width''  1.2   0.1");
            Printf(" the last definition is followed by a blank line or a zero.");
            return;
        }
        if (TStr.strncmp(comd, "REL", 3) == 0) {
            Printf(" ***>RELease  <parno> [parno] ... [parno]");
            Printf(" If <parno> is the number of a previously variable parameter");
            Printf(" which has been fixed by a command: FIX <parno>, then that");
            Printf(" parameter will return to variable status.  Otherwise a warning");
            Printf(" message is printed and the command is ignored.");
            Printf(" Note that this command operates only on parameters which were");
            Printf(" at one time variable and have been FIXed. It cannot make");
            Printf(" constant parameters variable; that must be done by redefining");
            Printf(" the parameter with a PARameters command.");
            return;
        }
        if (TStr.strncmp(comd, "RES", 3) == 0) {
            Printf(" ***>REStore  [code]");
            Printf(" If no [code] is specified, this command restores all previously");
            Printf(" FIXed parameters to variable status. If [code]=1, then only");
            Printf(" the last parameter FIXed is restored to variable status.");
            Printf(" If code is neither zero nor one, the command is ignored.");
            return;
        }
        if (TStr.strncmp(comd, "RET", 3) == 0) {
            Printf(" ***>RETURN");
            Printf(" Signals the end of a data block, and instructs Minuit to return");
            Printf(" to the program which called it. The RETurn command first");
            Printf(" causes Minuit to CALL FCN with IFLAG=3, in order to allow FCN");
            Printf(" to perform any calculations associated with the final fitted");
            Printf(" parameter values, unless a CALL FCN 3 command has already been");
            Printf(" executed at the current FCN value.");
            return;
        }
        if (TStr.strncmp(comd, "SAV", 3) == 0) {
            Printf(" ***>SAVe");
            Printf(" Causes the current parameter values to be saved on a file in");
            Printf(" such a format that they can be read in again as Minuit");
            Printf(" parameter definitions. If the covariance matrix exists, it is");
            Printf(" also output in such a format. The unit number is by default 7,");
            Printf(" or that specified by the user in their call to MINTIO or");
            Printf(" MNINIT. The user is responsible for opening the file previous");
            Printf(" to issuing the [SAVe] command (except where this can be done");
            Printf(" interactively).");
            return;
        }
        if (TStr.strncmp(comd, "SCA", 3) == 0) {
            Printf(" ***>SCAn  [parno]  [numpts] [from]  [to]");
            Printf(" Scans the value of the user function by varying parameter");
            Printf(" number [parno], leaving all other parameters fixed at the");
            Printf(" current value. If [parno] is not specified, all variable");
            Printf(" parameters are scanned in sequence.");
            Printf(" The number of points [numpts] in the scan is 40 by default,");
            Printf(" and cannot exceed 100. The range of the scan is by default");
            Printf(" 2 standard deviations on each side of the current best value,");
            Printf(" but can be specified as from [from] to [to].");
            Printf(" After each scan, if a new minimum is found, the best parameter");
            Printf(" values are retained as start values for future scans or");
            Printf(" minimizations. The curve resulting from each scan is plotted");
            Printf(" on the output unit in order to show the approximate behaviour");
            Printf(" of the function.");
            Printf(" This command is not intended for minimization, but is sometimes");
            Printf(" useful for debugging the user function or finding a");
            Printf(" reasonable starting point.");
            return;
        }
        if (TStr.strncmp(comd, "SEE", 3) == 0) {
            Printf(" ***>SEEk  [maxcalls]  [devs]");
            Printf(" Causes a Monte Carlo minimization of the function, by choosing");
            Printf(" random values of the variable parameters, chosen uniformly");
            Printf(" over a hypercube centered at the current best value.");
            Printf(" The region size is by default 3 standard deviations on each");
            Printf(" side, but can be changed by specifying the value of [devs].");
            return;
        }
        if (TStr.strncmp(comd, "SET", 3) == 0) {
            Printf(" ***>SET <option_name>");
            Printf("  SET BATch");
            Printf("    Informs Minuit that it is running in batch mode.");
            Printf(" ");
            Printf("  SET EPSmachine  <accuracy>");
            Printf("    Informs Minuit that the relative floating point arithmetic");
            Printf("    precision is <accuracy>. Minuit determines the nominal");
            Printf("    precision itself, but the SET EPSmachine command can be");
            Printf("    used to override Minuit own determination, when the user");
            Printf("    knows that the FCN function value is not calculated to");
            Printf("    the nominal machine accuracy. Typical values of <accuracy>");
            Printf("    are between 10**-5 and 10**-14.");
            Printf(" ");
            Printf("  SET ERRordef  <up>");
            Printf("    Sets the value of UP (default value= 1.), defining parameter");
            Printf("    errors. Minuit defines parameter errors as the change");
            Printf("    in parameter value required to change the function value");
            Printf("    by UP. Normally, for chisquared fits UP=1, and for negative");
            Printf("    log likelihood, UP=0.5.");
            Printf(" ");
            Printf("   SET GRAdient  [force]");
            Printf("    Informs Minuit that the user function is prepared to");
            Printf("    calculate its own first derivatives and return their values");
            Printf("    in the array GRAD when IFLAG=2 (see specs of FCN).");
            Printf("    If [force] is not specified, Minuit will calculate");
            Printf("    the FCN derivatives by finite differences at the current");
            Printf("    point and compare with the user calculation at that point,");
            Printf("    accepting the user values only if they agree.");
            Printf("    If [force]=1, Minuit does not do its own derivative");
            Printf("    calculation, and uses the derivatives calculated in FCN.");
            Printf(" ");
            Printf("   SET INPut  [unitno]  [filename]");
            Printf("    Causes Minuit, in data-driven mode only, to read subsequent");
            Printf("    commands (or parameter definitions) from a different input");
            Printf("    file. If no [unitno] is specified, reading reverts to the");
            Printf("    previous input file, assuming that there was one.");
            Printf("    If [unitno] is specified, and that unit has not been opened,");
            Printf("    then Minuit attempts to open the file [filename]} if a");
            Printf("    name is specified. If running in interactive mode and");
            Printf("    [filename] is not specified and [unitno] is not opened,");
            Printf("    Minuit prompts the user to enter a file name.");
            Printf("    If the word REWIND is added to the command (note:no blanks");
            Printf("    between INPUT and REWIND), the file is rewound before");
            Printf("    reading. Note that this command is implemented in standard");
            Printf("    Fortran 77 and the results may depend on the  system;");
            Printf("    for example, if a filename is given under VM/CMS, it must");
            Printf("    be preceded by a slash.");
            Printf(" ");
            Printf("   SET INTeractive");
            Printf("    Informs Minuit that it is running interactively.");
            Printf(" ");
            Printf("   SET LIMits  [parno]  [lolim]  [uplim]");
            Printf("    Allows the user to change the limits on one or all");
            Printf("    parameters. If no arguments are specified, all limits are");
            Printf("    removed from all parameters. If [parno] alone is specified,");
            Printf("    limits are removed from parameter [parno].");
            Printf("    If all arguments are specified, then parameter [parno] will");
            Printf("    be bounded between [lolim] and [uplim].");
            Printf("    Limits can be specified in either order, Minuit will take");
            Printf("    the smaller as [lolim] and the larger as [uplim].");
            Printf("    However, if [lolim] is equal to [uplim], an error condition");
            Printf("    results.");
            Printf(" ");
            Printf("   SET LINesperpage");
            Printf("     Sets the number of lines for one page of output.");
            Printf("     Default value is 24 for interactive mode");
            Printf(" ");
            Printf("   SET NOGradient");
            Printf("    The inverse of SET GRAdient, instructs Minuit not to");
            Printf("    use the first derivatives calculated by the user in FCN.");
            Printf(" ");
            Printf("   SET NOWarnings");
            Printf("    Suppresses Minuit warning messages.");
            Printf(" ");
            Printf("   SET OUTputfile  <unitno>");
            Printf("    Instructs Minuit to write further output to unit <unitno>.");
            Printf(" ");
            Printf("   SET PAGethrow  <integer>");
            Printf("    Sets the carriage control character for ``new page'' to");
            Printf("    <integer>. Thus the value 1 produces a new page, and 0");
            Printf("    produces a blank line, on some devices (see TOPofpage)");
            Printf(" ");
            Printf("   SET PARameter  <parno>  <value>");
            Printf("    Sets the value of parameter <parno> to <value>.");
            Printf("    The parameter in question may be variable, fixed, or");
            Printf("    constant, but must be defined.");
            Printf(" ");
            Printf("   SET PRIntout  <level>");
            Printf("    Sets the print level, determining how much output will be");
            Printf("    produced. Allowed values and their meanings are displayed");
            Printf("    after a SHOw PRInt command, and are currently <level>=:");
            Printf("      [-1]  no output except from SHOW commands");
            Printf("       [0]  minimum output");
            Printf("       [1]  default value, normal output");
            Printf("       [2]  additional output giving intermediate results.");
            Printf("       [3]  maximum output, showing progress of minimizations.");
            Printf("    Note: See also the SET WARnings command.");
            Printf(" ");
            Printf("   SET RANdomgenerator  <seed>");
            Printf("    Sets the seed of the random number generator used in SEEk.");
            Printf("    This can be any integer between 10000 and 900000000, for");
            Printf("    example one which was output from a SHOw RANdom command of");
            Printf("    a previous run.");
            Printf(" ");
            Printf("   SET STRategy  <level>");
            Printf("    Sets the strategy to be used in calculating first and second");
            Printf("    derivatives and in certain minimization methods.");
            Printf("    In general, low values of <level> mean fewer function calls");
            Printf("    and high values mean more reliable minimization.");
            Printf("    Currently allowed values are 0, 1 (default), and 2.");
            Printf(" ");
            Printf("   SET TITle");
            Printf("    Informs Minuit that the next input line is to be considered");
            Printf("    the (new) title for this task or sub-task.  This is for");
            Printf("    the convenience of the user in reading their output.");
            Printf(" ");
            Printf("   SET WARnings");
            Printf("    Instructs Minuit to output warning messages when suspicious");
            Printf("    conditions arise which may indicate unreliable results.");
            Printf("    This is the default.");
            Printf(" ");
            Printf("    SET WIDthpage");
            Printf("    Informs Minuit of the output page width.");
            Printf("    Default values are 80 for interactive jobs");
            return;
        }
        if (TStr.strncmp(comd, "SHO", 3) == 0) {
            Printf(" ***>SHOw  <option_name>");
            Printf("  All SET XXXX commands have a corresponding SHOw XXXX command.");
            Printf("  In addition, the SHOw commands listed starting here have no");
            Printf("  corresponding SET command for obvious reasons.");
            Printf(" ");
            Printf("   SHOw CORrelations");
            Printf("    Calculates and prints the parameter correlations from the");
            Printf("    error matrix.");
            Printf(" ");
            Printf("   SHOw COVariance");
            Printf("    Prints the (external) covariance (error) matrix.");
            Printf(" ");
            Printf("   SHOw EIGenvalues");
            Printf("    Calculates and prints the eigenvalues of the covariance");
            Printf("    matrix.");
            Printf(" ");
            Printf("   SHOw FCNvalue");
            Printf("    Prints the current value of FCN.");
            return;
        }
        if (TStr.strncmp(comd, "SIM", 3) == 0) {
            Printf(" ***>SIMplex  [maxcalls]  [tolerance]");
            Printf(" Performs a function minimization using the simplex method of");
            Printf(" Nelder and Mead. Minimization terminates either when the");
            Printf(" function has been called (approximately) [maxcalls] times,");
            Printf(" or when the estimated vertical distance to minimum (EDM) is");
            Printf(" less than [tolerance].");
            Printf(" The default value of [tolerance] is 0.1*UP(see SET ERRordef).");
            return;
        }
        if (TStr.strncmp(comd, "STA", 3) == 0) {
            Printf(" ***>STAndard");
            return;
        }
        if (TStr.strncmp(comd, "STO", 3) == 0) {
            Printf(" ***>STOP");
            Printf(" Same as EXIT.");
            return;
        }
        if (TStr.strncmp(comd, "TOP", 3) == 0) {
            Printf(" ***>TOPofpage");
            Printf(" Causes Minuit to write the character specified in a");
            Printf(" SET PAGethrow command (default = 1) to column 1 of the output");
            Printf(" file, which may or may not position your output medium to");
            Printf(" the top of a page depending on the device and system.");
            return;
        }
        Printf(" Unknown MINUIT command. Type HELP for list of commands.");
    }

    /** The full second-derivative matrix of FCN by finite differences, inverted into the error matrix. */
    public void mnhess() {
        double dmin_, wint, tlrg2, d, dlast, ztemp, g2bfor;
        double df, aimsag, fs1 = 0, tlrstp, fs2 = 0, stpinm, g2i, sag = 0, xtf, xti, xtj, elem;
        int icyc, ncyc, ndex, idrv, iext, npar2, i, j, npard, nparx, id, multpy;
        final double[] dxdi = {0};
        final int[] ifail = {0};
        boolean ldebug;

        ldebug = fIdbg[3] >= 1;
        if (fAmin == fUndefi) {
            mnamin();
        }
        if (fIstrat <= 0) {
            ncyc = 3;
            tlrstp = .5;
            tlrg2 = .1;
        } else if (fIstrat == 1) {
            ncyc = 5;
            tlrstp = .3;
            tlrg2 = .05;
        } else {
            ncyc = 7;
            tlrstp = .1;
            tlrg2 = .02;
        }
        if (fISW[4] >= 2 || ldebug) {
            Printf("   START COVARIANCE MATRIX CALCULATION.");
        }
        fCfrom = "HESSE   ";
        fNfcnfr = fNfcn;
        fCstatu = "OK        ";
        npard = fNpar;
        mninex(fX);
        nparx = fNpar;
        fs1 = eval(nparx, fs1, 4);
        ++fNfcn;
        if (fs1 != fAmin) {
            df = fAmin - fs1;
            mnwarn("D", "MNHESS", CFormat.sprintf("function value differs from AMIN by %g", df));
        }
        fAmin = fs1;
        if (ldebug) {
            Printf(" PAR D   GSTEP           D          G2         GRD         SAG    ");
        }
        // fISW[1] = 1 if approx, 2 if not posdef, 3 if ok; AIMSAG is the sagitta aimed at
        aimsag = TMath.sqrt(fEpsma2) * (Math.abs(fAmin) + fUp);
        npar2 = fNpar * (fNpar + 1) / 2;
        for (i = 1; i <= npar2; ++i) {
            fVhmat[i - 1] = 0;
        }

        boolean failed = false;
        idrv = 2;
        diagonal:
        for (id = 1; id <= npard; ++id) {
            i = id + fNpar - npard;
            iext = fNexofi[i - 1];
            if (fG2[i - 1] == 0) {
                mnwarn("W", "HESSE", CFormat.sprintf("Second derivative enters zero, param %d", iext));
                wint = fWerr[i - 1];
                if (fNvarl[iext - 1] > 1) {
                    mndxdi(fX[i - 1], i - 1, dxdi);
                    if (Math.abs(dxdi[0]) < .001) wint = .01;
                    else wint /= Math.abs(dxdi[0]);
                }
                fG2[i - 1] = fUp / (wint * wint);
            }
            xtf = fX[i - 1];
            dmin_ = fEpsma2 * 8 * Math.abs(xtf);

            // find step which gives sagitta = AIMSAG
            d = Math.abs(fGstep[i - 1]);
            boolean skip50 = false;
            for (icyc = 1; icyc <= ncyc; ++icyc) {
                boolean nonzero = false;
                for (multpy = 1; multpy <= 5; ++multpy) {
                    fX[i - 1] = xtf + d;
                    mninex(fX);
                    nparx = fNpar;
                    fs1 = eval(nparx, fs1, 4);
                    ++fNfcn;
                    fX[i - 1] = xtf - d;
                    mninex(fX);
                    fs2 = eval(nparx, fs2, 4);
                    ++fNfcn;
                    fX[i - 1] = xtf;
                    sag = (fs1 + fs2 - fAmin * 2) * .5;
                    if (sag != 0) {
                        nonzero = true;
                        break;
                    }
                    if (fGstep[i - 1] < 0) {
                        if (d >= .5) break;
                        d *= 10;
                        if (d > .5) d = .51;
                        continue;
                    }
                    d *= 10;
                }
                if (!nonzero) {
                    // L26
                    mnwarn("W", "HESSE", CFormat.sprintf("Second derivative zero for parameter%d", iext));
                    failed = true;
                    break diagonal;
                }
                // L30: SAG is not zero
                g2bfor = fG2[i - 1];
                fG2[i - 1] = sag * 2 / (d * d);
                fGrd[i - 1] = (fs1 - fs2) / (d * 2);
                if (ldebug) {
                    Printf("%4d%2d%12.5g%12.5g%12.5g%12.5g%12.5g", i, idrv, fGstep[i - 1], d, fG2[i - 1], fGrd[i - 1], sag);
                }
                if (fGstep[i - 1] > 0) fGstep[i - 1] = Math.abs(d);
                else fGstep[i - 1] = -Math.abs(d);
                fDirin[i - 1] = d;
                fHESSyy[i - 1] = fs1;
                dlast = d;
                d = TMath.sqrt(aimsag * 2 / Math.abs(fG2[i - 1]));
                // if parameter has limits, max int step size = 0.5
                stpinm = .5;
                if (fGstep[i - 1] < 0) d = TMath.min(d, stpinm);
                if (d < dmin_) d = dmin_;
                // see if converged
                if (Math.abs((d - dlast) / d) < tlrstp || Math.abs((fG2[i - 1] - g2bfor) / fG2[i - 1]) < tlrg2) {
                    skip50 = true;
                    break;
                }
                d = TMath.min(d, dlast * 102);
                d = TMath.max(d, dlast * .1);
            }
            if (!skip50) {
                mnwarn("D", "MNHESS", CFormat.sprintf("Second Deriv. SAG,AIM= %d%g%g", iext, sag, aimsag));
            }
            ndex = i * (i + 1) / 2;
            fVhmat[ndex - 1] = fG2[i - 1];
        }
        if (!failed) {
            mninex(fX);
            // refine the first derivatives
            if (fIstrat > 0) mnhes1();
            fISW[1] = 3;
            fDcovar = 0;
            // off-diagonal elements
            if (fNpar != 1) {
                for (i = 1; i <= fNpar; ++i) {
                    for (j = 1; j <= i - 1; ++j) {
                        xti = fX[i - 1];
                        xtj = fX[j - 1];
                        fX[i - 1] = xti + fDirin[i - 1];
                        fX[j - 1] = xtj + fDirin[j - 1];
                        mninex(fX);
                        fs1 = eval(nparx, fs1, 4);
                        ++fNfcn;
                        fX[i - 1] = xti;
                        fX[j - 1] = xtj;
                        elem = (fs1 + fAmin - fHESSyy[i - 1] - fHESSyy[j - 1]) / (fDirin[i - 1] * fDirin[j - 1]);
                        ndex = i * (i - 1) / 2 + j;
                        fVhmat[ndex - 1] = elem;
                    }
                }
            }
            // L214
            mninex(fX);
            // verify matrix positive-definite
            mnpsdf();
            for (i = 1; i <= fNpar; ++i) {
                for (j = 1; j <= i; ++j) {
                    ndex = i * (i - 1) / 2 + j;
                    fP[i + j * fMaxpar - fMaxpar - 1] = fVhmat[ndex - 1];
                    fP[j + i * fMaxpar - fMaxpar - 1] = fP[i + j * fMaxpar - fMaxpar - 1];
                }
            }
            mnvert(fP, fMaxint, fMaxint, fNpar, ifail);
            if (ifail[0] > 0) {
                mnwarn("W", "HESSE", "Matrix inversion fails.");
                failed = true;
            } else {
                // calculate e d m
                fEDM = 0;
                for (i = 1; i <= fNpar; ++i) {
                    ndex = i * (i - 1) / 2;
                    for (j = 1; j <= i - 1; ++j) {
                        ++ndex;
                        ztemp = fP[i + j * fMaxpar - fMaxpar - 1] * 2;
                        fEDM += fGrd[i - 1] * ztemp * fGrd[j - 1];
                        fVhmat[ndex - 1] = ztemp;
                    }
                    ++ndex;
                    fVhmat[ndex - 1] = fP[i + i * fMaxpar - fMaxpar - 1] * 2;
                    fEDM += fP[i + i * fMaxpar - fMaxpar - 1] * (fGrd[i - 1] * fGrd[i - 1]);
                }
                if (fISW[4] >= 1 && fISW[1] == 3 && fItaur == 0) {
                    Printf(" COVARIANCE MATRIX CALCULATED SUCCESSFULLY");
                }
                return;
            }
        }
        // L390: failure to invert 2nd deriv matrix
        fISW[1] = 1;
        fDcovar = 1;
        fCstatu = "FAILED    ";
        if (fISW[4] >= 0) {
            Printf("  MNHESS FAILS AND WILL RETURN DIAGONAL MATRIX. ");
        }
        for (i = 1; i <= fNpar; ++i) {
            ndex = i * (i - 1) / 2;
            for (j = 1; j <= i - 1; ++j) {
                ++ndex;
                fVhmat[ndex - 1] = 0;
            }
            ++ndex;
            g2i = fG2[i - 1];
            if (g2i <= 0) g2i = 1;
            fVhmat[ndex - 1] = 2 / g2i;
        }
    }

    /** First derivatives (GRD), their uncertainties (DGRD) and appropriate step sizes GSTEP; called from MNHESS and MNGRAD. */
    public void mnhes1() {
        double dmin_, d, dfmin, dgmin = 0, change, chgold, grdold = 0, epspri;
        double fs1 = 0, optstp, fs2 = 0, grdnew = 0, sag, xtf;
        int icyc, ncyc = 0, idrv, i, nparx;
        boolean ldebug;

        ldebug = fIdbg[5] >= 1;
        if (fIstrat <= 0) ncyc = 1;
        if (fIstrat == 1) ncyc = 2;
        if (fIstrat > 1) ncyc = 6;
        idrv = 1;
        nparx = fNpar;
        dfmin = fEpsma2 * 4 * (Math.abs(fAmin) + fUp);
        for (i = 1; i <= fNpar; ++i) {
            xtf = fX[i - 1];
            dmin_ = fEpsma2 * 4 * Math.abs(xtf);
            epspri = fEpsma2 + Math.abs(fGrd[i - 1] * fEpsma2);
            optstp = TMath.sqrt(dfmin / (Math.abs(fG2[i - 1]) + epspri));
            d = Math.abs(fGstep[i - 1]) * .2;
            if (d > optstp) d = optstp;
            if (d < dmin_) d = dmin_;
            chgold = 1e4;
            boolean l60 = false;
            for (icyc = 1; icyc <= ncyc; ++icyc) {
                fX[i - 1] = xtf + d;
                mninex(fX);
                fs1 = eval(nparx, fs1, 4);
                ++fNfcn;
                fX[i - 1] = xtf - d;
                mninex(fX);
                fs2 = eval(nparx, fs2, 4);
                ++fNfcn;
                fX[i - 1] = xtf;
                // check if step sizes appropriate
                sag = (fs1 + fs2 - fAmin * 2) * .5;
                grdold = fGrd[i - 1];
                grdnew = (fs1 - fs2) / (d * 2);
                dgmin = fEpsmac * (Math.abs(fs1) + Math.abs(fs2)) / d;
                if (ldebug) {
                    Printf("%4d%2d%12.5g%12.5g%12.5g%12.5g%12.5g", i, idrv, fGstep[i - 1], d, fG2[i - 1], grdnew, sag);
                }
                if (grdnew == 0) {
                    l60 = true;
                    break;
                }
                change = Math.abs((grdold - grdnew) / grdnew);
                if (change > chgold && icyc > 1) {
                    l60 = true;
                    break;
                }
                chgold = change;
                fGrd[i - 1] = grdnew;
                if (fGstep[i - 1] > 0) fGstep[i - 1] = Math.abs(d);
                else fGstep[i - 1] = -Math.abs(d);
                // decrease step until first derivative changes by <5%
                if (change < .05) {
                    l60 = true;
                    break;
                }
                if (Math.abs(grdold - grdnew) < dgmin) {
                    l60 = true;
                    break;
                }
                if (d < dmin_) {
                    mnwarn("D", "MNHES1", "Step size too small for 1st drv.");
                    l60 = true;
                    break;
                }
                d *= .2;
            }
            if (!l60) {
                mnwarn("D", "MNHES1", CFormat.sprintf("Too many iterations on D1.%g%g", grdold, grdnew));
            }
            fDgrd[i - 1] = TMath.max(dgmin, Math.abs(grdold - grdnew));
        }
        mninex(fX);
    }

    /**
     * Attempts to improve on a good local minimum by finding a better one: the quadratic part of FCN removed by
     * MNCALF, the rest minimised by the simplex method from several random starting points (Goldstein and Price).
     */
    public void mnimpr() {
        final double[] rnum = {0};
        final double[] ycalf = {0};
        double amax, ystar = 0, ystst = 0;
        double pb, ep, wg, xi, sigsav, reg = 0, sig2 = 0;
        int npfn, ndex, loop = 0, i, j;
        int jhold, nloop, nparx, nparp1, iswtr;
        final int[] ifail = {0};
        final int[] iseed = {0};
        final int[] jh = {0};
        final int[] jl = {0};

        if (fNpar <= 0) return;
        if (fAmin == fUndefi) mnamin();
        fCstatu = "UNCHANGED ";
        fItaur = 1;
        fEpsi = fUp * .1;
        npfn = fNfcn;
        nloop = (int) fWord7[1];
        if (nloop <= 0) nloop = fNpar + 4;
        nparx = fNpar;
        nparp1 = fNpar + 1;
        wg = 1 / (double) fNpar;
        sigsav = fEDM;
        fApsi = fAmin;
        iswtr = fISW[4] - 2 * fItaur;
        for (i = 1; i <= fNpar; ++i) {
            fXt[i - 1] = fX[i - 1];
            fIMPRdsav[i - 1] = fWerr[i - 1];
            for (j = 1; j <= i; ++j) {
                ndex = i * (i - 1) / 2 + j;
                fP[i + j * fMaxpar - fMaxpar - 1] = fVhmat[ndex - 1];
                fP[j + i * fMaxpar - fMaxpar - 1] = fP[i + j * fMaxpar - fMaxpar - 1];
            }
        }
        mnvert(fP, fMaxint, fMaxint, fNpar, ifail);
        int L;
        if (ifail[0] >= 1) {
            L = 280;
        } else {
            // save inverted matrix in VT
            for (i = 1; i <= fNpar; ++i) {
                ndex = i * (i - 1) / 2;
                for (j = 1; j <= i; ++j) {
                    ++ndex;
                    fVthmat[ndex - 1] = fP[i + j * fMaxpar - fMaxpar - 1];
                }
            }
            loop = 0;
            L = 20;
        }
        while (true) {
            switch (L) {
                case 20:
                    for (i = 1; i <= fNpar; ++i) {
                        fDirin[i - 1] = fIMPRdsav[i - 1] * 2;
                        mnrn15(rnum, iseed);
                        fX[i - 1] = fXt[i - 1] + fDirin[i - 1] * 2 * (rnum[0] - .5);
                    }
                    ++loop;
                    reg = 2;
                    if (fISW[4] >= 0) {
                        Printf("START ATTEMPT NO.%2d TO FIND NEW MINIMUM", loop);
                    }
                    // fall through
                case 30:
                    mncalf(fX, ycalf);
                    fAmin = ycalf[0];
                    // set up random simplex
                    jl[0] = nparp1;
                    jh[0] = nparp1;
                    fIMPRy[nparp1 - 1] = fAmin;
                    amax = fAmin;
                    for (i = 1; i <= fNpar; ++i) {
                        xi = fX[i - 1];
                        mnrn15(rnum, iseed);
                        fX[i - 1] = xi - fDirin[i - 1] * (rnum[0] - .5);
                        mncalf(fX, ycalf);
                        fIMPRy[i - 1] = ycalf[0];
                        if (fIMPRy[i - 1] < fAmin) {
                            fAmin = fIMPRy[i - 1];
                            jl[0] = i;
                        } else if (fIMPRy[i - 1] > amax) {
                            amax = fIMPRy[i - 1];
                            jh[0] = i;
                        }
                        for (j = 1; j <= fNpar; ++j) {
                            fP[j + i * fMaxpar - fMaxpar - 1] = fX[j - 1];
                        }
                        fP[i + nparp1 * fMaxpar - fMaxpar - 1] = xi;
                        fX[i - 1] = xi;
                    }
                    fEDM = fAmin;
                    sig2 = fEDM;
                    // fall through
                case 50: // start main loop
                    if (fAmin < 0) {
                        L = 95;
                        continue;
                    }
                    if (fISW[1] <= 2) {
                        L = 280;
                        continue;
                    }
                    ep = fAmin * .1;
                    if (sig2 < ep && fEDM < ep) {
                        L = 100;
                        continue;
                    }
                    sig2 = fEDM;
                    if (fNfcn - npfn > fNfcnmx) {
                        L = 300;
                        continue;
                    }
                    // calculate new point * by reflection
                    for (i = 1; i <= fNpar; ++i) {
                        pb = 0;
                        for (j = 1; j <= nparp1; ++j) {
                            pb += wg * fP[i + j * fMaxpar - fMaxpar - 1];
                        }
                        fPbar[i - 1] = pb - wg * fP[i + jh[0] * fMaxpar - fMaxpar - 1];
                        fPstar[i - 1] = fPbar[i - 1] * 2 - fP[i + jh[0] * fMaxpar - fMaxpar - 1] * 1;
                    }
                    mncalf(fPstar, ycalf);
                    ystar = ycalf[0];
                    if (ystar >= fAmin) {
                        L = 70;
                        continue;
                    }
                    // point * better than jl, calculate new point **
                    for (i = 1; i <= fNpar; ++i) {
                        fPstst[i - 1] = fPstar[i - 1] * 2 + fPbar[i - 1] * -1;
                    }
                    mncalf(fPstst, ycalf);
                    ystst = ycalf[0];
                    if (ystst < fIMPRy[jl[0] - 1]) {
                        L = 67;
                        continue;
                    }
                    mnrazz(ystar, fPstar, fIMPRy, jh, jl);
                    L = 50;
                    continue;
                case 67:
                    mnrazz(ystst, fPstst, fIMPRy, jh, jl);
                    L = 50;
                    continue;
                case 70: // point * is not as good as jl
                    if (ystar >= fIMPRy[jh[0] - 1]) {
                        L = 73;
                        continue;
                    }
                    jhold = jh[0];
                    mnrazz(ystar, fPstar, fIMPRy, jh, jl);
                    if (jhold != jh[0]) {
                        L = 50;
                        continue;
                    }
                    // fall through
                case 73: // calculate new point **
                    for (i = 1; i <= fNpar; ++i) {
                        fPstst[i - 1] = fP[i + jh[0] * fMaxpar - fMaxpar - 1] * .5 + fPbar[i - 1] * .5;
                    }
                    mncalf(fPstst, ycalf);
                    ystst = ycalf[0];
                    if (ystst > fIMPRy[jh[0] - 1]) {
                        L = 30;
                        continue;
                    }
                    // point ** is better than jh
                    if (ystst < fAmin) {
                        L = 67;
                        continue;
                    }
                    mnrazz(ystst, fPstst, fIMPRy, jh, jl);
                    L = 50;
                    continue;
                case 95: // end main loop
                    if (fISW[4] >= 0) {
                        Printf(" AN IMPROVEMENT ON THE PREVIOUS MINIMUM HAS BEEN FOUND");
                    }
                    reg = .1;
                    // fall through
                case 100: // ask if point is new
                    mninex(fX);
                    fAmin = eval(nparx, fAmin, 4);
                    ++fNfcn;
                    L = 230;
                    for (i = 1; i <= fNpar; ++i) {
                        fDirin[i - 1] = reg * fIMPRdsav[i - 1];
                        if (Math.abs(fX[i - 1] - fXt[i - 1]) > fDirin[i - 1]) {
                            L = 150;
                            break;
                        }
                    }
                    continue;
                case 150:
                    fNfcnmx = fNfcnmx + npfn - fNfcn;
                    npfn = fNfcn;
                    mnsimp();
                    if (fAmin >= fApsi) {
                        L = 325;
                        continue;
                    }
                    L = 230;
                    for (i = 1; i <= fNpar; ++i) {
                        fDirin[i - 1] = fIMPRdsav[i - 1] * .1;
                        if (Math.abs(fX[i - 1] - fXt[i - 1]) > fDirin[i - 1]) {
                            L = 250;
                            break;
                        }
                    }
                    continue;
                case 230:
                    L = fAmin < fApsi ? 350 : 325;
                    continue;
                case 250: // truly new minimum
                    fLnewmn = true;
                    if (fISW[1] >= 1) {
                        fISW[1] = 1;
                        fDcovar = TMath.max(fDcovar, .5);
                    } else fDcovar = 1;
                    fItaur = 0;
                    fNfcnmx = fNfcnmx + npfn - fNfcn;
                    fCstatu = "NEW MINIMU";
                    if (fISW[4] >= 0) {
                        Printf(" IMPROVE HAS FOUND A TRULY NEW MINIMUM");
                        Printf(" *************************************");
                    }
                    return;
                case 280: // return to previous region
                    if (fISW[4] > 0) {
                        Printf(" COVARIANCE MATRIX WAS NOT POSITIVE-DEFINITE");
                    }
                    L = 325;
                    continue;
                case 300:
                    fISW[0] = 1;
                    // fall through
                case 325:
                    for (i = 1; i <= fNpar; ++i) {
                        fDirin[i - 1] = fIMPRdsav[i - 1] * .01;
                        fX[i - 1] = fXt[i - 1];
                    }
                    fAmin = fApsi;
                    fEDM = sigsav;
                    // fall through
                case 350:
                    mninex(fX);
                    if (fISW[4] > 0) {
                        Printf(" IMPROVE HAS RETURNED TO REGION OF ORIGINAL MINIMUM");
                    }
                    fCstatu = "UNCHANGED ";
                    mnrset(0);
                    if (fISW[1] >= 2 && loop < nloop && fISW[0] < 1) {
                        L = 20;
                        continue;
                    }
                    // L380
                    if (iswtr >= 0) mnprin(5, fAmin);
                    fItaur = 0;
                    return;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    /** Transforms the internal coordinates pint to the external ones (U); called before FCN. */
    public void mninex(double[] pint) {
        int i, j;

        for (j = 0; j < fNpar; ++j) {
            i = fNexofi[j] - 1;
            if (fNvarl[i] == 1) {
                fU[i] = pint[j];
            } else {
                fU[i] = fAlim[i] + (TMath.sin(pint[j]) + 1) * .5 * (fBlim[i] - fAlim[i]);
            }
        }
    }

    /** Main initialization: some constants (the logical I/O unit numbers among them) and the machine precision. */
    public void mninit(int i1, int i2, int i3) {
        double epsp1, piby2, epstry, distnn;
        final double[] epsbak = {0};
        int i, idb;

        fIsysrd = i1;
        fIsyswr = i2;
        fIstkwr[0] = fIsyswr;
        fNstkwr = 1;
        fIsyssa = i3;
        fNstkrd = 0;
        fCvrsn = "95.03++ ";
        fMaxint = fMaxpar;
        fMaxext = 2 * fMaxpar;
        fUndefi = -54321;
        fBigedm = 123456;
        fCundef = ")UNDEFINED";
        fCovmes[0] = "NO ERROR MATRIX       ";
        fCovmes[1] = "ERR MATRIX APPROXIMATE";
        fCovmes[2] = "ERR MATRIX NOT POS-DEF";
        fCovmes[3] = "ERROR MATRIX ACCURATE ";
        fNblock = 0;
        fIcomnd = 0;
        fCtitl = fCundef;
        fCfrom = "INPUT   ";
        fNfcn = 0;
        fNfcnfr = fNfcn;
        fCstatu = "INITIALIZE";
        fISW[2] = 0;
        fISW[3] = 0;
        fISW[4] = 1;
        // fISW[5]=0 for batch jobs, =1 for interactive jobs, =-1 for originally interactive temporarily batch
        fISW[5] = 0;
        for (idb = 0; idb <= 10; ++idb) {
            fIdbg[idb] = 0;
        }
        fLrepor = false;
        fLwarn = true;
        fLimset = false;
        fLnewmn = false;
        fIstrat = 1;
        fItaur = 0;
        fNpagwd = 120;
        fNpagln = 56;
        fNewpag = 1;
        if (fISW[5] > 0) {
            fNpagwd = 80;
            fNpagln = 30;
            fNewpag = 0;
        }
        fUp = 1;
        fUpdflt = fUp;
        // determine machine accuracy epsmac
        epstry = .5;
        boolean found = false;
        for (i = 1; i <= 100; ++i) {
            epstry *= .5;
            epsp1 = epstry + 1;
            mntiny(epsp1, epsbak);
            if (epsbak[0] < epstry) {
                found = true;
                break;
            }
        }
        if (!found) {
            epstry = 1e-7;
            fEpsmac = epstry * 4;
            Printf(" MNINIT UNABLE TO DETERMINE ARITHMETIC PRECISION. WILL ASSUME:%g", fEpsmac);
        }
        // L35
        fEpsmac = epstry * 8;
        fEpsma2 = TMath.sqrt(fEpsmac) * 2;
        // the vlims are a non-negligible distance from pi/2, used by MNPINT to set variables "near" the physical limits
        piby2 = TMath.atan(1) * 2;
        distnn = TMath.sqrt(fEpsma2) * 8;
        fVlimhi = piby2 - distnn;
        fVlimlo = -piby2 + distnn;
        mncler();
    }

    /** Interprets the SET LIM command, to reset the parameter limits; called from MNSET. */
    public void mnlims() {
        double snew;
        final double[] dxdi = {0};
        int kint, i2, newcod, ifx = 0, inu;

        fCfrom = "SET LIM ";
        fNfcnfr = fNfcn;
        fCstatu = "NO CHANGE ";
        i2 = (int) fWord7[0];
        if (i2 > fMaxext || i2 < 0) {
            // L900
        } else if (i2 <= 0) {
            // set limits on all parameters
            newcod = 4;
            if (fWord7[1] == fWord7[2]) newcod = 1;
            for (inu = 1; inu <= fNu; ++inu) {
                if (fNvarl[inu - 1] <= 0) continue;
                if (fNvarl[inu - 1] == 1 && newcod == 1) continue;
                kint = fNiofex[inu - 1];
                // see if parameter has been fixed
                if (kint <= 0) {
                    if (fISW[4] >= 0) {
                        Printf("           LIMITS NOT CHANGED FOR FIXED PARAMETER:%4d", inu);
                    }
                    continue;
                }
                if (newcod == 1) {
                    // remove limits from parameter
                    if (fISW[4] > 0) {
                        Printf(" LIMITS REMOVED FROM PARAMETER  :%3d", inu);
                    }
                    fCstatu = "NEW LIMITS";
                    mndxdi(fX[kint - 1], kint - 1, dxdi);
                    snew = fGstep[kint - 1] * dxdi[0];
                    fGstep[kint - 1] = Math.abs(snew);
                    fNvarl[inu - 1] = 1;
                } else {
                    // put limits on parameter
                    fAlim[inu - 1] = TMath.min(fWord7[1], fWord7[2]);
                    fBlim[inu - 1] = TMath.max(fWord7[1], fWord7[2]);
                    if (fISW[4] > 0) {
                        Printf(" PARAMETER %3d LIMITS SET TO  %15.5g%15.5g", inu, fAlim[inu - 1], fBlim[inu - 1]);
                    }
                    fNvarl[inu - 1] = 4;
                    fCstatu = "NEW LIMITS";
                    fGstep[kint - 1] = -.1;
                }
            }
        } else if (fNvarl[i2 - 1] <= 0) {
            // L30: set limits on one parameter
            Printf(" PARAMETER %3d IS NOT VARIABLE.", i2);
        } else {
            kint = fNiofex[i2 - 1];
            // see if parameter was fixed
            if (kint == 0) {
                Printf(" REQUEST TO CHANGE LIMITS ON FIXED PARAMETER:%3d", i2);
                boolean found = false;
                for (ifx = 1; ifx <= fNpfix; ++ifx) {
                    if (i2 == fIpfix[ifx - 1]) {
                        found = true;
                        break;
                    }
                }
                if (!found) Printf(" MINUIT BUG IN MNLIMS. SEE F. JAMES");
            }
            if (fWord7[1] == fWord7[2]) {
                // remove limits
                if (fNvarl[i2 - 1] != 1) {
                    if (fISW[4] > 0) {
                        Printf(" LIMITS REMOVED FROM PARAMETER  %2d", i2);
                    }
                    fCstatu = "NEW LIMITS";
                    if (kint <= 0) {
                        fGsteps[ifx - 1] = Math.abs(fGsteps[ifx - 1]);
                    } else {
                        mndxdi(fX[kint - 1], kint - 1, dxdi);
                        if (Math.abs(dxdi[0]) < .01) dxdi[0] = .01;
                        fGstep[kint - 1] = Math.abs(fGstep[kint - 1] * dxdi[0]);
                        fGrd[kint - 1] *= dxdi[0];
                    }
                    fNvarl[i2 - 1] = 1;
                } else {
                    Printf(" NO LIMITS SPECIFIED.  PARAMETER %3d IS ALREADY UNLIMITED.  NO CHANGE.", i2);
                }
            } else {
                // L235: put on limits
                fAlim[i2 - 1] = TMath.min(fWord7[1], fWord7[2]);
                fBlim[i2 - 1] = TMath.max(fWord7[1], fWord7[2]);
                fNvarl[i2 - 1] = 4;
                if (fISW[4] > 0) {
                    Printf(" PARAMETER %3d LIMITS SET TO  %15.5g%15.5g", i2, fAlim[i2 - 1], fBlim[i2 - 1]);
                }
                fCstatu = "NEW LIMITS";
                if (kint <= 0) fGsteps[ifx - 1] = -.1;
                else fGstep[kint - 1] = -.1;
            }
        }
        // L900
        if (!fCstatu.equals("NO CHANGE ")) {
            mnexin(fX);
            mnrset(1);
        }
    }

    /**
     * Line search from start along step, whose length gives the expected position of the minimum: fstart is FCN at
     * start, slope (if non-zero) df/dx along step at start, toler the initial tolerance of the minimum along step.
     */
    public void mnline(double[] start, double fstart, double[] step, double slope, double toler) {
        final double[] xpq = new double[12];
        final double[] ypq = new double[12];
        final double[] coeff = new double[3];
        final double[] sdev = {0};
        final double[] fvals = new double[3];
        final double[] xvals = new double[3];
        double slam, denom, flast;
        double f1 = 0, fvmin, xvmin, ratio, f2 = 0, f3 = 0., fvmax = 0;
        double toler8, toler9, overal, undral, slamin, slamax, slopem;
        int i, nparx = 0, nvmax = 0, nxypt, kk, ipt;
        boolean ldebug;
        String cmess = "";
        final char[] chpq = new char[13];
        int l65, l70, l80;

        l65 = 0;
        l70 = 0;
        l80 = 0;
        ldebug = fIdbg[1] >= 1;
        // starting values for overall limits on total step SLAM
        overal = 1e3;
        undral = -100;
        // debug check if start is ok
        if (ldebug) {
            mninex(start);
            f1 = eval(nparx, f1, 4);
            ++fNfcn;
            if (f1 != fstart) {
                Printf(" MNLINE start point not consistent, F values, parameters=");
                for (kk = 1; kk <= fNpar; ++kk) {
                    Printf("  %14.5e", fX[kk - 1]);
                }
            }
        }
        // set up linear search along STEP
        fvmin = fstart;
        xvmin = 0;
        nxypt = 1;
        chpq[0] = charal.charAt(0);
        xpq[0] = 0;
        ypq[0] = fstart;
        // SLAMIN = smallest possible value of ABS(SLAM)
        slamin = 0;
        for (i = 1; i <= fNpar; ++i) {
            if (step[i - 1] != 0) {
                ratio = Math.abs(start[i - 1] / step[i - 1]);
                if (slamin == 0) slamin = ratio;
                if (ratio < slamin) slamin = ratio;
            }
            fX[i - 1] = start[i - 1] + step[i - 1];
        }
        if (slamin == 0) slamin = fEpsmac;
        slamin *= fEpsma2;
        nparx = fNpar;

        mninex(fX);
        f1 = eval(nparx, f1, 4);
        ++fNfcn;
        ++nxypt;
        chpq[nxypt - 1] = charal.charAt(nxypt - 1);
        xpq[nxypt - 1] = 1;
        ypq[nxypt - 1] = f1;
        if (f1 < fstart) {
            fvmin = f1;
            xvmin = 1;
        }
        // quadr interp using slope GDEL and two points
        slam = 1;
        toler8 = toler;
        slamax = 5;
        flast = f1;
        // can iterate on two-points (cut) if no imprvmnt
        do {
            denom = (flast - fstart - slope * slam) * 2 / (slam * slam);
            slam = 1;
            if (denom != 0) slam = -slope / denom;
            if (slam < 0) slam = slamax;
            if (slam > slamax) slam = slamax;
            if (slam < toler8) slam = toler8;
            if (slam < slamin) {
                l80 = 1;
                break;
            }
            if (Math.abs(slam - 1) < toler8 && f1 < fstart) {
                l70 = 1;
                break;
            }
            if (Math.abs(slam - 1) < toler8) slam = toler8 + 1;
            if (nxypt >= 12) {
                l65 = 1;
                break;
            }
            for (i = 1; i <= fNpar; ++i) {
                fX[i - 1] = start[i - 1] + slam * step[i - 1];
            }
            mninex(fX);
            nparx = fNpar;
            f2 = eval(nparx, f2, 4);
            ++fNfcn;
            ++nxypt;
            chpq[nxypt - 1] = charal.charAt(nxypt - 1);
            xpq[nxypt - 1] = slam;
            ypq[nxypt - 1] = f2;
            if (f2 < fvmin) {
                fvmin = f2;
                xvmin = slam;
            }
            if (fstart == fvmin) {
                flast = f2;
                toler8 = toler * slam;
                overal = slam - toler8;
                slamax = overal;
            }
        } while (fstart == fvmin);

        if (l65 == 0 && l70 == 0 && l80 == 0) {
            // quadr interp using 3 points
            xvals[0] = xpq[0];
            fvals[0] = ypq[0];
            xvals[1] = xpq[nxypt - 2];
            fvals[1] = ypq[nxypt - 2];
            xvals[2] = xpq[nxypt - 1];
            fvals[2] = ypq[nxypt - 1];
            // begin iteration, calculate desired step
            do {
                slamax = TMath.max(slamax, Math.abs(xvmin) * 2);
                mnpfit(xvals, fvals, 3, coeff, sdev);
                if (coeff[2] <= 0) {
                    slopem = coeff[2] * 2 * xvmin + coeff[1];
                    if (slopem <= 0) slam = xvmin + slamax;
                    else slam = xvmin - slamax;
                } else {
                    slam = -coeff[1] / (coeff[2] * 2);
                    if (slam > xvmin + slamax) slam = xvmin + slamax;
                    if (slam < xvmin - slamax) slam = xvmin - slamax;
                }
                if (slam > 0) {
                    if (slam > overal) slam = overal;
                    else if (slam < undral) slam = undral;
                }
                // come here if step was cut below
                do {
                    toler9 = TMath.max(toler8, Math.abs(toler8 * slam));
                    for (ipt = 1; ipt <= 3; ++ipt) {
                        if (Math.abs(slam - xvals[ipt - 1]) < toler9) {
                            l70 = 1;
                            break;
                        }
                    }
                    if (l70 != 0) break;
                    // take the step
                    if (nxypt >= 12) {
                        l65 = 1;
                        break;
                    }
                    for (i = 1; i <= fNpar; ++i) {
                        fX[i - 1] = start[i - 1] + slam * step[i - 1];
                    }
                    mninex(fX);
                    f3 = eval(nparx, f3, 4);
                    ++fNfcn;
                    ++nxypt;
                    chpq[nxypt - 1] = charal.charAt(nxypt - 1);
                    xpq[nxypt - 1] = slam;
                    ypq[nxypt - 1] = f3;
                    // find worst previous point out of three
                    fvmax = fvals[0];
                    nvmax = 1;
                    if (fvals[1] > fvmax) {
                        fvmax = fvals[1];
                        nvmax = 2;
                    }
                    if (fvals[2] > fvmax) {
                        fvmax = fvals[2];
                        nvmax = 3;
                    }
                    // if latest point worse than all three previous, cut step
                    if (f3 >= fvmax) {
                        if (nxypt >= 12) {
                            l65 = 1;
                            break;
                        }
                        if (slam > xvmin) overal = TMath.min(overal, slam - toler8);
                        if (slam < xvmin) undral = TMath.max(undral, slam + toler8);
                        slam = (slam + xvmin) * .5;
                    }
                } while (f3 >= fvmax);

                // prepare another iteration, replace worst previous point
                if (l65 != 0 || l70 != 0) break;

                xvals[nvmax - 1] = slam;
                fvals[nvmax - 1] = f3;
                if (f3 < fvmin) {
                    fvmin = f3;
                    xvmin = slam;
                } else {
                    if (slam > xvmin) overal = TMath.min(overal, slam - toler8);
                    if (slam < xvmin) undral = TMath.max(undral, slam + toler8);
                }
            } while (nxypt < 12);
        }

        // end of iteration; stop because too many iterations
        if (l70 == 0 && l80 == 0 && ldebug) {
            cmess = " LINE SEARCH HAS EXHAUSTED THE LIMIT OF FUNCTION CALLS ";
            Printf(" MNLINE DEBUG: steps=");
            for (kk = 1; kk <= fNpar; ++kk) {
                Printf("  %12.4g", step[kk - 1]);
            }
        }
        // stop because within tolerance
        if (l70 != 0 && ldebug) cmess = " LINE SEARCH HAS ATTAINED TOLERANCE ";
        if (l80 != 0 && ldebug) cmess = " STEP SIZE AT ARITHMETICALLY ALLOWED MINIMUM";

        fAmin = fvmin;
        for (i = 1; i <= fNpar; ++i) {
            fDirin[i - 1] = step[i - 1] * xvmin;
            fX[i - 1] = start[i - 1] + fDirin[i - 1];
        }
        mninex(fX);
        if (xvmin < 0) {
            mnwarn("D", "MNLINE", " LINE MINIMUM IN BACKWARDS DIRECTION");
        }
        if (fvmin == fstart) {
            mnwarn("D", "MNLINE", " LINE SEARCH FINDS NO IMPROVEMENT ");
        }
        if (ldebug) {
            Printf(" AFTER %3d POINTS,%s", nxypt, cmess);
            mnplot(xpq, ypq, chpq, nxypt, fNpagwd, fNpagln);
        }
    }

    /** Prints the covariance matrix when kode=1, always the global correlations, and the correlation coefficients. */
    public void mnmatu(int kode) {
        int ndex, i, j, m, n, ncoef, nparm, id, it, ix;
        int nsofar, ndi, ndj, iso, isw2, isw5;
        String ctemp;

        isw2 = fISW[1];
        if (isw2 < 1) {
            Printf("%s", fCovmes[isw2]);
            return;
        }
        if (fNpar == 0) {
            Printf(" MNMATU: NPAR=0");
            return;
        }
        // external error matrix
        if (kode == 1) {
            isw5 = fISW[4];
            fISW[4] = 2;
            mnemat(fP, fMaxint);
            if (isw2 < 3) {
                Printf("%s", fCovmes[isw2]);
            }
            fISW[4] = isw5;
        }
        // correlation coeffs
        if (fNpar <= 1) return;
        mnwerr();
        // NCOEF is number of coeff. that fit on one line, not to exceed 20
        ncoef = (fNpagwd - 19) / 6;
        ncoef = TMath.min(ncoef, 20);
        nparm = TMath.min(fNpar, ncoef);
        Printf(" PARAMETER  CORRELATION COEFFICIENTS  ");
        ctemp = "       NO.  GLOBAL";
        for (id = 1; id <= nparm; ++id) {
            ctemp += CFormat.sprintf(" %6d", fNexofi[id - 1]);
        }
        Printf("%s", ctemp);
        for (i = 1; i <= fNpar; ++i) {
            ix = fNexofi[i - 1];
            ndi = i * (i + 1) / 2;
            for (j = 1; j <= fNpar; ++j) {
                m = TMath.max(i, j);
                n = TMath.min(i, j);
                ndex = m * (m - 1) / 2 + n;
                ndj = j * (j + 1) / 2;
                fMATUvline[j - 1] = fVhmat[ndex - 1] / TMath.sqrt(Math.abs(fVhmat[ndi - 1] * fVhmat[ndj - 1]));
            }
            nparm = TMath.min(fNpar, ncoef);
            ctemp = CFormat.sprintf("      %3d  %7.5f ", ix, fGlobcc[i - 1]);
            for (it = 1; it <= nparm; ++it) {
                ctemp += CFormat.sprintf(" %6.3f", fMATUvline[it - 1]);
            }
            Printf("%s", ctemp);
            if (i <= nparm) continue;
            ctemp = "                   ";
            for (iso = 1; iso <= 10; ++iso) {
                nsofar = nparm;
                nparm = TMath.min(fNpar, nsofar + ncoef);
                for (it = nsofar + 1; it <= nparm; ++it) {
                    ctemp = ctemp + CFormat.sprintf(" %6.3f", fMATUvline[it - 1]);
                }
                Printf("%s", ctemp);
                if (i <= nparm) break;
            }
        }
        if (isw2 < 3) {
            Printf(" %s", fCovmes[isw2]);
        }
    }

    /** Local minimization by the method of Davidon-Fletcher-Powell as modified by Fletcher ("switching method"). */
    public void mnmigr() {
        double gdel, gami, vlen, dsum, gssq, vsum, d;
        double fzero = 0, fs = 0, ri, delgam = 0, rhotol;
        double gdgssq, gvg = 0, vgi;
        int npfn, ndex, iext, i, j, m, n, npsdf, nparx;
        int iswtr, lined2, kk, nfcnmg, nrstrt, iter = 0;
        boolean ldebug;
        final double toler = 0.05;

        if (fNpar <= 0) return;
        if (fAmin == fUndefi) mnamin();
        ldebug = fIdbg[4] >= 1;
        fCfrom = "MIGRAD  ";
        fNfcnfr = fNfcn;
        nfcnmg = fNfcn;
        fCstatu = "INITIATE  ";
        iswtr = fISW[4] - 2 * fItaur;
        npfn = fNfcn;
        nparx = fNpar;
        vlen = (double) (fNpar * (fNpar + 1) / 2);
        nrstrt = 0;
        npsdf = 0;
        lined2 = 0;
        fISW[3] = -1;
        rhotol = fApsi * .001;
        if (iswtr >= 1) {
            Printf(" START MIGRAD MINIMIZATION.  STRATEGY %2d.  CONVERGENCE WHEN EDM .LT.%9.2e", fIstrat, rhotol);
        }
        // initialization strategy
        int L = (fIstrat < 2 || fISW[1] >= 3) ? 2 : 1;
        while (true) {
            switch (L) {
                case 1: // come (back) here to restart completely
                    if (nrstrt > fIstrat) {
                        fCstatu = "FAILED    ";
                        fISW[3] = -1;
                        L = 230;
                        continue;
                    }
                    // get full covariance and gradient
                    mnhess();
                    mnwerr();
                    npsdf = 0;
                    if (fISW[1] >= 1) {
                        L = 10;
                        continue;
                    }
                    // fall through
                case 2: { // get gradient at start point
                    mninex(fX);
                    if (fISW[2] == 1) {
                        fzero = eval(nparx, fzero, 2);
                        ++fNfcn;
                    }
                    mnderi();
                    if (fISW[1] >= 1) {
                        L = 10;
                        continue;
                    }
                    // sometimes start with diagonal matrix
                    for (i = 1; i <= fNpar; ++i) {
                        fMIGRxxs[i - 1] = fX[i - 1];
                        fMIGRstep[i - 1] = 0;
                    }
                    // do line search if second derivative negative
                    ++lined2;
                    if (lined2 < (fIstrat + 1) * fNpar) {
                        boolean searched = false;
                        for (i = 1; i <= fNpar; ++i) {
                            if (fG2[i - 1] > 0) continue;
                            if (fGrd[i - 1] > 0) fMIGRstep[i - 1] = -Math.abs(fGstep[i - 1]);
                            else fMIGRstep[i - 1] = Math.abs(fGstep[i - 1]);
                            gdel = fMIGRstep[i - 1] * fGrd[i - 1];
                            fs = fAmin;
                            mnline(fMIGRxxs, fs, fMIGRstep, gdel, toler);
                            mnwarn("D", "MNMIGR", "Negative G2 line search");
                            iext = fNexofi[i - 1];
                            if (ldebug) {
                                Printf(" Negative G2 line search, param %3d %13.3g%13.3g", iext, fs, fAmin);
                            }
                            searched = true;
                            break;
                        }
                        if (searched) {
                            L = 2;
                            continue;
                        }
                    }
                    // make diagonal error matrix
                    for (i = 1; i <= fNpar; ++i) {
                        ndex = i * (i - 1) / 2;
                        for (j = 1; j <= i - 1; ++j) {
                            ++ndex;
                            fVhmat[ndex - 1] = 0;
                        }
                        ++ndex;
                        if (fG2[i - 1] <= 0) fG2[i - 1] = 1;
                        fVhmat[ndex - 1] = 2 / fG2[i - 1];
                    }
                    fDcovar = 1;
                    if (ldebug) {
                        Printf(" DEBUG MNMIGR, STARTING MATRIX DIAGONAL,  VHMAT=");
                        for (kk = 1; kk <= (int) vlen; ++kk) {
                            Printf(" %10.2g", fVhmat[kk - 1]);
                        }
                    }
                }
                // fall through
                case 10: // ready to start first iteration
                    ++nrstrt;
                    if (nrstrt > fIstrat + 1) {
                        fCstatu = "FAILED    ";
                        L = 230;
                        continue;
                    }
                    fs = fAmin;
                    // get EDM and set up loop
                    fEDM = 0;
                    for (i = 1; i <= fNpar; ++i) {
                        fMIGRgs[i - 1] = fGrd[i - 1];
                        fMIGRxxs[i - 1] = fX[i - 1];
                        ndex = i * (i - 1) / 2;
                        for (j = 1; j <= i - 1; ++j) {
                            ++ndex;
                            fEDM += fMIGRgs[i - 1] * fVhmat[ndex - 1] * fMIGRgs[j - 1];
                        }
                        ++ndex;
                        fEDM += fMIGRgs[i - 1] * fMIGRgs[i - 1] * .5 * fVhmat[ndex - 1];
                    }
                    fEDM = fEDM * .5 * (fDcovar * 3 + 1);
                    if (fEDM < 0) {
                        mnwarn("W", "MIGRAD", "STARTING MATRIX NOT POS-DEFINITE.");
                        fISW[1] = 0;
                        fDcovar = 1;
                        L = 2;
                        continue;
                    }
                    if (fISW[1] == 0) fEDM = fBigedm;
                    iter = 0;
                    mninex(fX);
                    mnwerr();
                    if (iswtr >= 1) mnprin(3, fAmin);
                    if (iswtr >= 2) mnmatu(0);
                    // fall through
                case 24: // start main loop
                    if (fNfcn - npfn >= fNfcnmx) {
                        L = 190;
                        continue;
                    }
                    gdel = 0;
                    gssq = 0;
                    for (i = 1; i <= fNpar; ++i) {
                        ri = 0;
                        gssq += fMIGRgs[i - 1] * fMIGRgs[i - 1];
                        for (j = 1; j <= fNpar; ++j) {
                            m = TMath.max(i, j);
                            n = TMath.min(i, j);
                            ndex = m * (m - 1) / 2 + n;
                            ri += fVhmat[ndex - 1] * fMIGRgs[j - 1];
                        }
                        fMIGRstep[i - 1] = ri * -.5;
                        gdel += fMIGRstep[i - 1] * fMIGRgs[i - 1];
                    }
                    if (gssq == 0) {
                        mnwarn("D", "MIGRAD", " FIRST DERIVATIVES OF FCN ARE ALL ZERO");
                        L = 300;
                        continue;
                    }
                    // if gdel positive, V not posdef
                    if (gdel >= 0) {
                        mnwarn("D", "MIGRAD", " NEWTON STEP NOT DESCENT.");
                        if (npsdf == 1) {
                            L = 1;
                            continue;
                        }
                        mnpsdf();
                        npsdf = 1;
                        L = 24;
                        continue;
                    }
                    // do line search
                    mnline(fMIGRxxs, fs, fMIGRstep, gdel, toler);
                    if (fAmin == fs) {
                        L = 200;
                        continue;
                    }
                    fCfrom = "MIGRAD  ";
                    fNfcnfr = nfcnmg;
                    fCstatu = "PROGRESS  ";
                    // get gradient at new point
                    mninex(fX);
                    if (fISW[2] == 1) {
                        fzero = eval(nparx, fzero, 2);
                        ++fNfcn;
                    }
                    mnderi();
                    // calculate new EDM
                    npsdf = 0;
                    // fall through
                case 81:
                    fEDM = 0;
                    gvg = 0;
                    delgam = 0;
                    gdgssq = 0;
                    for (i = 1; i <= fNpar; ++i) {
                        ri = 0;
                        vgi = 0;
                        for (j = 1; j <= fNpar; ++j) {
                            m = TMath.max(i, j);
                            n = TMath.min(i, j);
                            ndex = m * (m - 1) / 2 + n;
                            vgi += fVhmat[ndex - 1] * (fGrd[j - 1] - fMIGRgs[j - 1]);
                            ri += fVhmat[ndex - 1] * fGrd[j - 1];
                        }
                        fMIGRvg[i - 1] = vgi * .5;
                        gami = fGrd[i - 1] - fMIGRgs[i - 1];
                        gdgssq += gami * gami;
                        gvg += gami * fMIGRvg[i - 1];
                        delgam += fDirin[i - 1] * gami;
                        fEDM += fGrd[i - 1] * ri * .5;
                    }
                    fEDM = fEDM * .5 * (fDcovar * 3 + 1);
                    // if EDM negative, not positive-definite
                    if (fEDM < 0 || gvg <= 0) {
                        mnwarn("D", "MIGRAD", "NOT POS-DEF. EDM OR GVG NEGATIVE.");
                        fCstatu = "NOT POSDEF";
                        if (npsdf == 1) {
                            L = 230;
                            continue;
                        }
                        mnpsdf();
                        npsdf = 1;
                        L = 81;
                        continue;
                    }
                    // print information about this iteration
                    ++iter;
                    if (iswtr >= 3 || (iswtr == 2 && iter % 10 == 1)) {
                        mnwerr();
                        mnprin(3, fAmin);
                    }
                    if (gdgssq == 0) {
                        mnwarn("D", "MIGRAD", "NO CHANGE IN FIRST DERIVATIVES OVER LAST STEP");
                    }
                    if (delgam < 0) {
                        mnwarn("D", "MIGRAD", "FIRST DERIVATIVES INCREASING ALONG SEARCH LINE");
                    }
                    // update covariance matrix
                    fCstatu = "IMPROVEMENT";
                    if (ldebug) {
                        Printf(" VHMAT 1 =");
                        for (kk = 1; kk <= 10; ++kk) {
                            Printf(" %10.2g", fVhmat[kk - 1]);
                        }
                    }
                    dsum = 0;
                    vsum = 0;
                    for (i = 1; i <= fNpar; ++i) {
                        for (j = 1; j <= i; ++j) {
                            if (delgam == 0 || gvg == 0) d = 0;
                            else d = fDirin[i - 1] * fDirin[j - 1] / delgam - fMIGRvg[i - 1] * fMIGRvg[j - 1] / gvg;
                            dsum += Math.abs(d);
                            ndex = i * (i - 1) / 2 + j;
                            fVhmat[ndex - 1] += d * 2;
                            vsum += Math.abs(fVhmat[ndex - 1]);
                        }
                    }
                    // smooth local fluctuations by averaging DCOVAR
                    fDcovar = (fDcovar + dsum / vsum) * .5;
                    if (iswtr >= 3 || ldebug) {
                        Printf(" RELATIVE CHANGE IN COV. MATRIX=%5.1f per cent", fDcovar * 100);
                    }
                    if (ldebug) {
                        Printf(" VHMAT 2 =");
                        for (kk = 1; kk <= 10; ++kk) {
                            Printf(" %10.3g", fVhmat[kk - 1]);
                        }
                    }
                    if (!(delgam <= gvg)) {
                        for (i = 1; i <= fNpar; ++i) {
                            fMIGRflnu[i - 1] = fDirin[i - 1] / delgam - fMIGRvg[i - 1] / gvg;
                        }
                        for (i = 1; i <= fNpar; ++i) {
                            for (j = 1; j <= i; ++j) {
                                ndex = i * (i - 1) / 2 + j;
                                fVhmat[ndex - 1] += gvg * 2 * fMIGRflnu[i - 1] * fMIGRflnu[j - 1];
                            }
                        }
                    }
                    // L135: and see if converged
                    if (fEDM < rhotol * .1) {
                        L = 300;
                        continue;
                    }
                    // if not, prepare next iteration
                    for (i = 1; i <= fNpar; ++i) {
                        fMIGRxxs[i - 1] = fX[i - 1];
                        fMIGRgs[i - 1] = fGrd[i - 1];
                    }
                    fs = fAmin;
                    if (fISW[1] == 0 && fDcovar < .5) fISW[1] = 1;
                    if (fISW[1] == 3 && fDcovar > .1) fISW[1] = 1;
                    if (fISW[1] == 1 && fDcovar < .05) fISW[1] = 3;
                    L = 24;
                    continue;
                case 190: // call limit in MNMIGR
                    fISW[0] = 1;
                    if (fISW[4] >= 0) {
                        Printf(" CALL LIMIT EXCEEDED IN MIGRAD.");
                    }
                    fCstatu = "CALL LIMIT";
                    L = 230;
                    continue;
                case 200: // fails to improve
                    if (iswtr >= 1) {
                        Printf(" MIGRAD FAILS TO FIND IMPROVEMENT");
                    }
                    for (i = 1; i <= fNpar; ++i) {
                        fX[i - 1] = fMIGRxxs[i - 1];
                    }
                    if (fEDM < rhotol) {
                        L = 300;
                        continue;
                    }
                    if (fEDM < Math.abs(fEpsma2 * fAmin)) {
                        if (iswtr >= 0) {
                            Printf(" MACHINE ACCURACY LIMITS FURTHER IMPROVEMENT.");
                        }
                        L = 300;
                        continue;
                    }
                    if (fIstrat < 1) {
                        if (fISW[4] >= 0) {
                            Printf(" MIGRAD FAILS WITH STRATEGY=0.   WILL TRY WITH STRATEGY=1.");
                        }
                        fIstrat = 1;
                    }
                    L = 1;
                    continue;
                case 230: // fails to converge
                    if (iswtr >= 0) {
                        Printf(" MIGRAD TERMINATED WITHOUT CONVERGENCE.");
                    }
                    if (fISW[1] == 3) fISW[1] = 1;
                    fISW[3] = -1;
                    L = 400;
                    continue;
                case 300: // apparent convergence
                    if (iswtr >= 0) {
                        Printf(" MIGRAD MINIMIZATION HAS CONVERGED.");
                    }
                    if (fItaur == 0) {
                        if (fIstrat >= 2 || (fIstrat == 1 && fISW[1] < 3)) {
                            if (fISW[4] >= 0) {
                                Printf(" MIGRAD WILL VERIFY CONVERGENCE AND ERROR MATRIX.");
                            }
                            mnhess();
                            mnwerr();
                            npsdf = 0;
                            if (fEDM > rhotol) {
                                L = 10;
                                continue;
                            }
                        }
                    }
                    fCstatu = "CONVERGED ";
                    fISW[3] = 1;
                    // fall through
                case 400: // come here in any case
                    fCfrom = "MIGRAD  ";
                    fNfcnfr = nfcnmg;
                    mninex(fX);
                    mnwerr();
                    if (iswtr >= 0) mnprin(3, fAmin);
                    if (iswtr >= 1) mnmatu(1);
                    return;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    /** MINOS error analysis of the parameters requested by the MINOS command (MNMNOT for each). */
    public void mnmnos() {
        final double[] val2mi = {0};
        final double[] val2pl = {0};
        int nbad, ilax, ilax2, ngood, nfcnmi, iin, knt;

        if (fNpar <= 0) {
            Printf(" THERE ARE NO MINOS ERRORS TO CALCULATE.");
            return;
        }
        ngood = 0;
        nbad = 0;
        nfcnmi = fNfcn;
        // loop over parameters requested
        for (knt = 1; knt <= fNpar; ++knt) {
            if ((int) fWord7[1] == 0) {
                ilax = fNexofi[knt - 1];
            } else {
                if (knt >= 7) break;
                ilax = (int) fWord7[knt];
                if (ilax == 0) break;
                if (!(ilax > 0 && ilax <= fNu && fNiofex[ilax - 1] > 0)) {
                    Printf(" PARAMETER NUMBER %3d NOT A VARIABLE. IGNORED.", ilax);
                    continue;
                }
            }
            // L565: calculate one pair of M E s
            ilax2 = 0;
            mnmnot(ilax, ilax2, val2pl, val2mi);
            if (fLnewmn) {
                // L650: new minimum found
                fCfrom = "MINOS   ";
                fNfcnfr = nfcnmi;
                fCstatu = "NEW MINIMU";
                if (fISW[4] >= 0) mnprin(4, fAmin);
                Printf(" NEW MINIMUM FOUND.  GO BACK TO MINIMIZATION STEP.");
                Printf(" =================================================");
                Printf("                                                  V");
                Printf("                                                  V");
                Printf("                                                  V");
                Printf("                                               VVVVVVV");
                Printf("                                                VVVVV");
                Printf("                                                 VVV");
                Printf("                                                  V\n");
                return;
            }
            // update NGOOD and NBAD
            iin = fNiofex[ilax - 1];
            if (fErp[iin - 1] > 0) ++ngood;
            else ++nbad;
            if (fErn[iin - 1] < 0) ++ngood;
            else ++nbad;
        }
        // printout final values
        fCfrom = "MINOS   ";
        fNfcnfr = nfcnmi;
        fCstatu = "UNCHANGED ";
        if (ngood == 0 && nbad == 0) {
            Printf(" THERE ARE NO MINOS ERRORS TO CALCULATE.");
            return;
        }
        if (ngood > 0 && nbad == 0) fCstatu = "SUCCESSFUL";
        if (ngood == 0 && nbad > 0) fCstatu = "FAILURE   ";
        if (ngood > 0 && nbad > 0) fCstatu = "PROBLEMS  ";
        if (fISW[4] >= 0) mnprin(4, fAmin);
        if (fISW[4] >= 2) mnmatu(0);
    }

    /**
     * MINOS error analysis on one parameter: ilax is varied, the minimum of the function with respect to the other
     * parameters followed until it crosses FMIN+UP.
     */
    public void mnmnot(int ilax, int ilax2, double[] val2pl, double[] val2mi) {
        int i__1;
        double delu, eros = 0;
        double abest, xunit, dc, ut, sigsav, du1;
        double fac, sig, sav;
        int marc, isig, mpar, ndex, imax, indx, i, j;
        int it, istrav, nfmxin, nlimit, isw2, isw4;
        final double[] aopt = {0};
        final int[] iercr = {0};
        final int[] ierr = {0};
        String csig;

        // save and prepare start vals
        isw2 = fISW[1];
        isw4 = fISW[3];
        sigsav = fEDM;
        istrav = fIstrat;
        dc = fDcovar;
        fLnewmn = false;
        fApsi = fEpsi * .5;
        abest = fAmin;
        mpar = fNpar;
        nfmxin = fNfcnmx;
        for (i = 1; i <= mpar; ++i) {
            fXt[i - 1] = fX[i - 1];
        }
        i__1 = mpar * (mpar + 1) / 2;
        for (j = 1; j <= i__1; ++j) {
            fVthmat[j - 1] = fVhmat[j - 1];
        }
        for (i = 1; i <= mpar; ++i) {
            fMNOTgcc[i - 1] = fGlobcc[i - 1];
            fMNOTw[i - 1] = fWerr[i - 1];
        }
        it = fNiofex[ilax - 1];
        fErp[it - 1] = 0;
        fErn[it - 1] = 0;
        mninex(fXt);
        ut = fU[ilax - 1];
        if (fNvarl[ilax - 1] == 1) {
            fAlim[ilax - 1] = ut - fMNOTw[it - 1] * 100;
            fBlim[ilax - 1] = ut + fMNOTw[it - 1] * 100;
        }
        ndex = it * (it + 1) / 2;
        xunit = TMath.sqrt(fUp / fVthmat[ndex - 1]);
        marc = 0;
        for (i = 1; i <= mpar; ++i) {
            if (i == it) continue;
            ++marc;
            imax = TMath.max(it, i);
            indx = imax * (imax - 1) / 2 + TMath.min(it, i);
            fMNOTxdev[marc - 1] = xunit * fVthmat[indx - 1];
        }
        // fix the parameter in question
        mnfixp(it - 1, ierr);
        if (ierr[0] > 0) {
            Printf(" MINUIT ERROR. CANNOT FIX PARAMETER %4d   INTERNAL %3d", ilax, it);
            // L700
            fItaur = 0;
            fNfcnmx = nfmxin;
            fIstrat = istrav;
            return;
        }
        // Nota Bene: from here on, NPAR=MPAR-1; MNFIXP squeezes IT out of X, XT, WERR and VHMAT, not W, VTHMAT
        boolean newmin = false;
        for (isig = 1; isig <= 2; ++isig) {
            if (isig == 1) {
                sig = 1;
                csig = "POSI";
            } else {
                sig = -1;
                csig = "NEGA";
            }
            // sig=sign of error being calcd
            if (fISW[4] > 1) {
                Printf(" DETERMINATION OF %sTIVE MINOS ERROR FOR PARAMETER %d %s", csig, ilax, fCpnam[ilax - 1]);
            }
            if (fISW[1] <= 0) {
                mnwarn("D", "MINOS", "NO COVARIANCE MATRIX.");
            }
            nlimit = fNfcn + nfmxin;
            fIstrat = TMath.max(istrav - 1, 0);
            du1 = fMNOTw[it - 1];
            fU[ilax - 1] = ut + sig * du1;
            fU[ilax - 1] = TMath.min(fU[ilax - 1], fBlim[ilax - 1]);
            fU[ilax - 1] = TMath.max(fU[ilax - 1], fAlim[ilax - 1]);
            delu = fU[ilax - 1] - ut;
            // stop if already at limit with negligible step size (and both numerator and denominator zero, ROOT-10835)
            int failure;
            if ((delu == 0 && ut == 0) || (Math.abs(delu) / (Math.abs(ut) + Math.abs(fU[ilax - 1])) < fEpsmac)) {
                failure = 440;
            } else {
                fac = delu / fMNOTw[it - 1];
                for (i = 1; i <= fNpar; ++i) {
                    fX[i - 1] = fXt[i - 1] + fac * fMNOTxdev[i - 1];
                }
                if (fISW[4] > 1) {
                    Printf(" PARAMETER %4d SET TO%11.3e + %10.3e = %12.3e", ilax, ut, delu, fU[ilax - 1]);
                }
                // loop to hit AMIN+UP
                fKe1cr = ilax;
                fKe2cr = 0;
                fXmidcr = fU[ilax - 1];
                fXdircr = delu;

                fAmin = abest;
                fNfcnmx = nlimit - fNfcn;
                mncros(aopt, iercr);
                if (abest - fAmin > fUp * .01) {
                    newmin = true;
                    break;
                }
                failure = iercr[0] == 1 ? 440 : iercr[0] == 2 ? 450 : iercr[0] == 3 ? 460 : 0;
                if (failure == 0) {
                    // error successfully calculated
                    eros = fXmidcr - ut + aopt[0] * fXdircr;
                    if (fISW[4] > 1) {
                        Printf("        THE %4sTIVE MINOS ERROR OF PARAMETER %3d  %10s, IS %12.4e", csig, ilax, fCpnam[ilax - 1], eros);
                    }
                }
            }
            switch (failure) {
                case 440:
                    if (fISW[4] >= 1) {
                        Printf("    THE %4sTIVE MINOS ERROR OF PARAMETER %3d, %s EXCEEDS ITS LIMIT.", csig, ilax, fCpnam[ilax - 1]);
                    }
                    eros = fUndefi;
                    break;
                case 450:
                    if (fISW[4] >= 1) {
                        Printf("       THE %4sTIVE MINOS ERROR %4d REQUIRES MORE THAN %5d FUNCTION CALLS.", csig, ilax, nfmxin);
                    }
                    eros = 0;
                    break;
                case 460:
                    if (fISW[4] >= 1) {
                        Printf("                         %4sTIVE MINOS ERROR NOT CALCULATED FOR PARAMETER %d", csig, ilax);
                    }
                    eros = 0;
                    break;
                default:
                    break;
            }
            // L480
            if (fISW[4] > 1) {
                Printf("     **************************************************************************");
            }
            if (sig < 0) {
                fErn[it - 1] = eros;
                if (ilax2 > 0 && ilax2 <= fNu) val2mi[0] = fU[ilax2 - 1];
            } else {
                fErp[it - 1] = eros;
                if (ilax2 > 0 && ilax2 <= fNu) val2pl[0] = fU[ilax2 - 1];
            }
        }
        if (!newmin) {
            // parameter finished. reset v; normal termination
            fItaur = 1;
            mnfree(1);
            i__1 = mpar * (mpar + 1) / 2;
            for (j = 1; j <= i__1; ++j) {
                fVhmat[j - 1] = fVthmat[j - 1];
            }
            for (i = 1; i <= mpar; ++i) {
                fWerr[i - 1] = fMNOTw[i - 1];
                fGlobcc[i - 1] = fMNOTgcc[i - 1];
                fX[i - 1] = fXt[i - 1];
            }
            mninex(fX);
            fEDM = sigsav;
            fAmin = abest;
            fISW[1] = isw2;
            fISW[3] = isw4;
            fDcovar = dc;
        } else {
            // L650: new minimum
            fLnewmn = true;
            fISW[1] = 0;
            fDcovar = 1;
            fISW[3] = 0;
            sav = fU[ilax - 1];
            fItaur = 1;
            mnfree(1);
            fU[ilax - 1] = sav;
            mnexin(fX);
            fEDM = fBigedm;
        }
        // L700: in any case
        fItaur = 0;
        fNfcnmx = nfmxin;
        fIstrat = istrav;
    }

    /** One parameter definition (external number k1+1, name, value, step, limits a and b); ierflg 0 when done. */
    public void mnparm(int k1, String cnamj, double uk, double wk, double a, double b, int[] ierflg) {
        double vplu, a_small, gsmin, vminu, danger, sav;
        int kint, in, ix, ktofix, lastin, kinfix, nvl = 0;
        final int[] ierr = {0};
        final double[] pinti = {0};
        final double[] psav = {0};
        String cnamk;

        final int k = k1 + 1;
        cnamk = cnamj;
        kint = fNpar;
        if (k < 1 || k > fMaxext) {
            Printf(" MINUIT USER ERROR.  PARAMETER NUMBER IS %3d  ALLOWED RANGE IS ONE TO %4d", k, fMaxext);
            ierflg[0] = 1;
            return;
        }
        ktofix = 0;
        if (fNvarl[k - 1] >= 0) {
            // previously defined parameter is being redefined: find if parameter was fixed
            for (ix = 1; ix <= fNpfix; ++ix) {
                if (fIpfix[ix - 1] == k) ktofix = k;
            }
            if (ktofix > 0) {
                mnwarn("W", "PARAM DEF", "REDEFINING A FIXED PARAMETER.");
                if (kint >= fMaxint) {
                    Printf(" CANNOT RELEASE. MAX NPAR EXCEEDED.");
                    ierflg[0] = 1;
                    return;
                }
                mnfree(-k);
            }
            if (fNiofex[k - 1] > 0) kint = fNpar - 1;
        }
        // L50: print heading
        if (fLphead && fISW[4] >= 0) {
            Printf(" PARAMETER DEFINITIONS:");
            Printf("    NO.   NAME         VALUE      STEP SIZE      LIMITS");
            fLphead = false;
        }
        if (!(wk > 0)) {
            // constant parameter
            if (fISW[4] >= 0) {
                Printf(" %5d %-10s %13.5e  constant", k, cnamk, uk);
            }
            nvl = 0;
        } else {
            // L122
            if (a == 0 && b == 0) {
                nvl = 1;
                if (fISW[4] >= 0) {
                    Printf(" %5d %-10s %13.5e%13.5e     no limits", k, cnamk, uk, wk);
                }
            } else {
                nvl = 4;
                fLnolim = false;
                if (fISW[4] >= 0) {
                    Printf(" %5d %-10s %13.5e%13.5e  %13.5e%13.5e", k, cnamk, uk, wk, a, b);
                }
            }
            // request for another variable parameter
            ++kint;
            if (kint > fMaxint) {
                Printf(" MINUIT USER ERROR.   TOO MANY VARIABLE PARAMETERS.");
                ierflg[0] = 1;
                return;
            }
            if (nvl != 1) {
                if (a == b) {
                    Printf(" USER ERROR IN MINUIT PARAMETER");
                    Printf(" DEFINITION");
                    Printf(" UPPER AND LOWER LIMITS EQUAL.");
                    ierflg[0] = 1;
                    return;
                }
                if (b < a) {
                    sav = b;
                    b = a;
                    a = sav;
                    mnwarn("W", "PARAM DEF", "PARAMETER LIMITS WERE REVERSED.");
                    if (fLwarn) fLphead = true;
                }
                if (b - a > 1e7) {
                    mnwarn("W", "PARAM DEF", CFormat.sprintf("LIMITS ON PARAM%d TOO FAR APART.", k));
                    if (fLwarn) fLphead = true;
                }
                danger = (b - uk) * (uk - a);
                if (danger < 0) {
                    mnwarn("W", "PARAM DEF", "STARTING VALUE OUTSIDE LIMITS.");
                }
                if (danger == 0) {
                    mnwarn("W", "PARAM DEF", "STARTING VALUE IS AT LIMIT.");
                }
            }
        }
        // L200: input OK, set values, arrange lists, calculate step sizes GSTEP, DIRIN
        fCfrom = "PARAMETR";
        fNfcnfr = fNfcn;
        fCstatu = "NEW VALUES";
        fNu = TMath.max(fNu, k);
        fCpnam[k - 1] = cnamk;
        fU[k - 1] = uk;
        fAlim[k - 1] = a;
        fBlim[k - 1] = b;
        fNvarl[k - 1] = nvl;
        mnrset(1);
        // LASTIN is the number of var. params with ext. param. no. < K
        lastin = 0;
        for (ix = 1; ix <= k - 1; ++ix) {
            if (fNiofex[ix - 1] > 0) ++lastin;
        }
        // KINT is new number of variable params, NPAR is old
        if (kint != fNpar) {
            if (kint > fNpar) {
                // insert new variable parameter in list
                for (in = fNpar; in >= lastin + 1; --in) {
                    ix = fNexofi[in - 1];
                    fNiofex[ix - 1] = in + 1;
                    fNexofi[in] = ix;
                    fX[in] = fX[in - 1];
                    fXt[in] = fXt[in - 1];
                    fDirin[in] = fDirin[in - 1];
                    fG2[in] = fG2[in - 1];
                    fGstep[in] = fGstep[in - 1];
                    fWerr[in] = fWerr[in - 1];
                    fGrd[in] = fGrd[in - 1];
                }
            } else {
                // remove variable parameter from list
                for (in = lastin + 1; in <= kint; ++in) {
                    ix = fNexofi[in];
                    fNiofex[ix - 1] = in;
                    fNexofi[in - 1] = ix;
                    fX[in - 1] = fX[in];
                    fXt[in - 1] = fXt[in];
                    fDirin[in - 1] = fDirin[in];
                    fG2[in - 1] = fG2[in];
                    fGstep[in - 1] = fGstep[in];
                    fWerr[in - 1] = fWerr[in];
                    fGrd[in - 1] = fGrd[in];
                }
            }
        }
        // L280
        ix = k;
        fNiofex[ix - 1] = 0;
        fNpar = kint;
        // lists are now arranged
        if (nvl > 0) {
            in = lastin + 1;
            fNexofi[in - 1] = ix;
            fNiofex[ix - 1] = in;
            psav[0] = fU[ix - 1];
            mnpint(psav, ix - 1, pinti);
            final double savv = psav[0];
            fX[in - 1] = pinti[0];
            fXt[in - 1] = fX[in - 1];
            fWerr[in - 1] = wk;
            psav[0] = savv + wk;
            mnpint(psav, ix - 1, pinti);
            vplu = pinti[0] - fX[in - 1];
            psav[0] = savv - wk;
            mnpint(psav, ix - 1, pinti);
            vminu = pinti[0] - fX[in - 1];
            fDirin[in - 1] = (Math.abs(vplu) + Math.abs(vminu)) * .5;
            fG2[in - 1] = fUp * 2 / (fDirin[in - 1] * fDirin[in - 1]);
            gsmin = fEpsma2 * 8 * Math.abs(fX[in - 1]);
            fGstep[in - 1] = TMath.max(gsmin, fDirin[in - 1] * .1);
            if (fAmin != fUndefi) {
                a_small = TMath.sqrt(fEpsma2 * (fAmin + fUp) / fUp);
                fGstep[in - 1] = TMath.max(gsmin, a_small * fDirin[in - 1]);
            }
            fGrd[in - 1] = fG2[in - 1] * fDirin[in - 1];
            // if parameter has limits
            if (fNvarl[k - 1] > 1) {
                if (fGstep[in - 1] > .5) fGstep[in - 1] = .5;
                fGstep[in - 1] = -fGstep[in - 1];
            }
        }
        if (ktofix > 0) {
            ierr[0] = 0;
            kinfix = fNiofex[ktofix - 1];
            if (kinfix > 0) mnfixp(kinfix - 1, ierr);
            if (ierr[0] > 0) {
                ierflg[0] = 1;
                return;
            }
        }
        ierflg[0] = 0;
    }

    /**
     * One parameter definition parsed from crdbuf (free-field 'name' format, or the fixed-field one, whose
     * conversion is ROOT's scanf with crdbuf as the format: it reads the input); icondn 0 OK, 1 error, 2 end.
     */
    public void mnpars(String crdbuf, int[] icondn) {
        double a = 0, b = 0, uk = 0, wk = 0;
        final double[] fk = {0};
        final double[] xk = {0};
        int kapo1, kapo2, k = 0, ibegin, lenbuf, istart = 0, icy;
        final int[] ierr = {0};
        final int[] llist = {0};
        final int[] lnc = {0};
        final String[] comand = {""};
        final String[] stmp = {""};
        final double[] ukA = {0};
        final double[] wkA = {0};
        final double[] aA = {0};
        final double[] bA = {0};
        String cnamk = "", celmnt, ctemp;

        lenbuf = TStr.strlen(crdbuf);
        int L = 150;
        kapo1 = TStr.strspn(crdbuf, "'");
        if (kapo1 != 0) {
            kapo2 = TStr.strspn(crdbuf.substring(Math.min(kapo1, crdbuf.length())), "'");
            if (kapo2 != 0) {
                // new (free-field) format
                kapo2 += kapo1;
                L = 210;
                for (istart = 1; istart <= kapo1 - 1; ++istart) {
                    if (!TStr.sub(crdbuf, istart - 1, 1).equals(" ")) {
                        L = 120;
                        break;
                    }
                }
                if (L == 120) {
                    // parameter number integer
                    celmnt = TStr.sub(crdbuf, istart - 1, kapo1 - istart);
                    scanf(celmnt, new Object[] {fk});
                    k = (int) fk[0];
                    if (k <= 0) {
                        L = 210;
                    } else {
                        cnamk = "PARAM " + celmnt;
                        if (kapo2 - kapo1 > 1) {
                            cnamk = TStr.sub(crdbuf, kapo1, kapo2 - 1 - kapo1);
                        }
                        // special handling if comma or blanks and a comma follow 'name'
                        int next = 0;
                        for (icy = kapo2 + 1; icy <= lenbuf; ++icy) {
                            if (TStr.sub(crdbuf, icy - 1, 1).equals(",")) {
                                next = 139;
                                break;
                            }
                            if (!TStr.sub(crdbuf, icy - 1, 1).equals(" ")) {
                                next = 140;
                                break;
                            }
                        }
                        if (next == 0) {
                            uk = 0;
                            wk = 0;
                            a = 0;
                            b = 0;
                            L = 170;
                        } else {
                            if (next == 139) ++icy;
                            ibegin = icy;
                            ctemp = TStr.sub(crdbuf, ibegin - 1, lenbuf - ibegin);
                            mncrck(ctemp, 20, comand, lnc, fMaxpar, fPARSplist, llist, ierr, fIsyswr);
                            if (ierr[0] > 0) {
                                L = 180;
                            } else {
                                uk = fPARSplist[0];
                                wk = 0;
                                if (llist[0] >= 2) wk = fPARSplist[1];
                                a = 0;
                                if (llist[0] >= 3) a = fPARSplist[2];
                                b = 0;
                                if (llist[0] >= 4) b = fPARSplist[3];
                                L = 170;
                            }
                        }
                    }
                }
            }
        }
        if (L == 150) {
            // old (fixed-field) format
            scanf(crdbuf, new Object[] {xk, stmp, ukA, wkA, aA, bA});
            uk = ukA[0];
            wk = wkA[0];
            a = aA[0];
            b = bA[0];
            cnamk = stmp[0];
            k = (int) xk[0];
            L = k == 0 ? 210 : 170;
        }
        switch (L) {
            case 170: // parameter format cracked, implement parameter definition
                mnparm(k - 1, cnamk, uk, wk, a, b, ierr);
                icondn[0] = ierr[0];
                return;
            case 180: // format or other error
                icondn[0] = 1;
                return;
            default: // end of data
                icondn[0] = 2;
        }
    }

    /** Parabola y = c0 + c1 x + c2 x^2 fitted to npar2p points; sdev2p its variance. */
    public void mnpfit(double[] parx2p, double[] pary2p, int npar2p, double[] coef2p, double[] sdev2p) {
        double a, f, s, t, y, s2, x2, x3, x4, y2, xm, xy, x2y;
        final double[] cz = new double[3];
        int i;

        for (i = 1; i <= 3; ++i) {
            cz[i - 1] = 0;
        }
        sdev2p[0] = 0;
        if (npar2p >= 3) {
            f = (double) npar2p;
            // center x values for reasons of machine precision
            xm = 0;
            for (i = 1; i <= npar2p; ++i) {
                xm += parx2p[i - 1];
            }
            xm /= f;
            x2 = 0;
            x3 = 0;
            x4 = 0;
            y = 0;
            y2 = 0;
            xy = 0;
            x2y = 0;
            for (i = 1; i <= npar2p; ++i) {
                s = parx2p[i - 1] - xm;
                t = pary2p[i - 1];
                s2 = s * s;
                x2 += s2;
                x3 += s * s2;
                x4 += s2 * s2;
                y += t;
                y2 += t * t;
                xy += s * t;
                x2y += s2 * t;
            }
            a = (f * x4 - x2 * x2) * x2 - f * (x3 * x3);
            if (a != 0) {
                cz[2] = (x2 * (f * x2y - x2 * y) - f * x3 * xy) / a;
                cz[1] = (xy - x3 * cz[2]) / x2;
                cz[0] = (y - x2 * cz[2]) / f;
                if (npar2p != 3) {
                    sdev2p[0] = y2 - (cz[0] * y + cz[1] * xy + cz[2] * x2y);
                    if (sdev2p[0] < 0) sdev2p[0] = 0;
                    sdev2p[0] /= f - 3;
                }
                // L6
                cz[0] += xm * (xm * cz[2] - cz[1]);
                cz[1] -= xm * 2 * cz[2];
            }
        }
        // L10
        for (i = 1; i <= 3; ++i) {
            coef2p[i - 1] = cz[i - 1];
        }
    }

    /** The internal value pinti of the external value pexti of parameter i1 (pexti brought back inside its limits). */
    public void mnpint(double[] pexti, int i1, double[] pinti) {
        double a, alimi, blimi, yy, yy2;
        int igo;
        String chbuf2;

        final int i = i1 + 1;
        pinti[0] = pexti[0];
        igo = fNvarl[i - 1];
        if (igo == 4) {
            // there are two limits
            alimi = fAlim[i - 1];
            blimi = fBlim[i - 1];
            yy = (pexti[0] - alimi) * 2 / (blimi - alimi) - 1;
            yy2 = yy * yy;
            if (yy2 >= 1 - fEpsma2) {
                if (yy < 0) {
                    a = fVlimlo;
                    chbuf2 = " IS AT ITS LOWER ALLOWED LIMIT.";
                } else {
                    a = fVlimhi;
                    chbuf2 = " IS AT ITS UPPER ALLOWED LIMIT.";
                }
                pinti[0] = a;
                pexti[0] = alimi + (blimi - alimi) * .5 * (TMath.sin(a) + 1);
                fLimset = true;
                if (yy2 > 1) chbuf2 = " BROUGHT BACK INSIDE LIMITS.";
                mnwarn("W", fCfrom, CFormat.sprintf("VARIABLE%d%s", i, chbuf2));
            } else {
                pinti[0] = TMath.asin(yy);
            }
        }
    }

    /**
     * Name, value, error, limits and internal number of parameter iuext1 (or, negative, of internal parameter
     * -(iuext1+1), iuint then being the external number); iuint -1 when undefined.
     */
    public void mnpout(int iuext1, String[] chnam, double[] val, double[] err, double[] xlolim, double[] xuplim, int[] iuint) {
        int iint, iext, nvl;

        final int iuext = iuext1 + 1;
        xlolim[0] = 0;
        xuplim[0] = 0;
        err[0] = 0;
        undefined:
        {
            if (iuext == 0) break undefined;
            if (iuext < 0) {
                // internal parameter number specified
                iint = -(iuext);
                if (iint > fNpar) break undefined;
                iext = fNexofi[iint - 1];
                iuint[0] = iext;
            } else {
                // external parameter number specified
                iext = iuext;
                if (iext > fNu) break undefined;
                iint = fNiofex[iext - 1];
                iuint[0] = iint;
            }
            // in both cases
            nvl = fNvarl[iext - 1];
            if (nvl < 0) break undefined;
            chnam[0] = fCpnam[iext - 1];
            val[0] = fU[iext - 1];
            if (iint > 0) err[0] = fWerr[iint - 1];
            if (nvl == 4) {
                xlolim[0] = fAlim[iext - 1];
                xuplim[0] = fBlim[iext - 1];
            }
            return;
        }
        // L100: parameter is undefined
        iuint[0] = -1;
        chnam[0] = "undefined";
        val[0] = 0;
    }

    /** Eigenvalues of the second-derivative matrix: made positive-definite by adding to the diagonal if needed. */
    public void mnpsdf() {
        double dgmin, pAdd, pmin, pmax, dg, epspdf, epsmin;
        int ndex, i, j, ndexd, ip;
        final int[] ifault = {0};
        String ctemp;

        epsmin = 1e-6;
        epspdf = TMath.max(epsmin, fEpsma2);
        dgmin = fVhmat[0];
        // check if negative or zero on diagonal
        for (i = 1; i <= fNpar; ++i) {
            ndex = i * (i + 1) / 2;
            if (fVhmat[ndex - 1] <= 0) {
                mnwarn("W", fCfrom, CFormat.sprintf("Negative diagonal element %d in Error Matrix", i));
            }
            if (fVhmat[ndex - 1] < dgmin) dgmin = fVhmat[ndex - 1];
        }
        if (dgmin <= 0) {
            dg = epspdf + 1 - dgmin;
            mnwarn("W", fCfrom, CFormat.sprintf("%g added to diagonal of error matrix", dg));
        } else {
            dg = 0;
        }
        // store VHMAT in P, make sure diagonal pos.
        for (i = 1; i <= fNpar; ++i) {
            ndex = i * (i - 1) / 2;
            ndexd = ndex + i;
            fVhmat[ndexd - 1] += dg;
            if (fVhmat[ndexd - 1] == 0) {
                fPSDFs[i - 1] = 1 / 1e-19; // a totally arbitrary silly small value
            } else {
                fPSDFs[i - 1] = 1 / TMath.sqrt(fVhmat[ndexd - 1]);
            }
            for (j = 1; j <= i; ++j) {
                ++ndex;
                fP[i + j * fMaxpar - fMaxpar - 1] = fVhmat[ndex - 1] * fPSDFs[i - 1] * fPSDFs[j - 1];
            }
        }
        mneig(fP, fMaxint, fNpar, fMaxint, fPstar, epspdf, ifault);
        pmin = fPstar[0];
        pmax = fPstar[0];
        for (ip = 2; ip <= fNpar; ++ip) {
            if (fPstar[ip - 1] < pmin) pmin = fPstar[ip - 1];
            if (fPstar[ip - 1] > pmax) pmax = fPstar[ip - 1];
        }
        pmax = TMath.max(Math.abs(pmax), 1.0);
        if ((pmin <= 0 && fLwarn) || fISW[4] >= 2) {
            Printf(" EIGENVALUES OF SECOND-DERIVATIVE MATRIX:");
            ctemp = "       ";
            for (ip = 1; ip <= fNpar; ++ip) {
                ctemp += CFormat.sprintf(" %11.4e", fPstar[ip - 1]);
            }
            Printf("%s", ctemp);
        }
        if (pmin > epspdf * pmax) return;
        if (fISW[1] == 3) fISW[1] = 2;
        pAdd = pmax * .001 - pmin;
        for (ip = 1; ip <= fNpar; ++ip) {
            ndex = ip * (ip + 1) / 2;
            fVhmat[ndex - 1] *= pAdd + 1;
        }
        fCstatu = "NOT POSDEF";
        mnwarn("W", fCfrom, CFormat.sprintf("MATRIX FORCED POS-DEF BY ADDING %f TO DIAGONAL.", pAdd));
    }

    /** Called by MNSIMP and MNIMPR: a new point replaces an old one of the simplex, and the EDM is estimated. */
    public void mnrazz(double ynew, double[] pnew, double[] y, int[] jh, int[] jl) {
        double pbig, plit;
        int i, j, nparp1;

        for (i = 1; i <= fNpar; ++i) {
            fP[i + jh[0] * fMaxpar - fMaxpar - 1] = pnew[i - 1];
        }
        y[jh[0] - 1] = ynew;
        if (ynew < fAmin) {
            for (i = 1; i <= fNpar; ++i) {
                fX[i - 1] = pnew[i - 1];
            }
            mninex(fX);
            fAmin = ynew;
            fCstatu = "PROGRESS  ";
            jl[0] = jh[0];
        }
        jh[0] = 1;
        nparp1 = fNpar + 1;
        for (j = 2; j <= nparp1; ++j) {
            if (y[j - 1] > y[jh[0] - 1]) jh[0] = j;
        }
        fEDM = y[jh[0] - 1] - y[jl[0] - 1];
        if (fEDM <= 0) {
            // L45
            if (fISW[4] >= 0) {
                Printf("  FUNCTION VALUE DOES NOT SEEM TO DEPEND ON ANY OF THE %d VARIABLE PARAMETERS.", fNpar);
                Printf("          VERIFY THAT STEP SIZES ARE BIG ENOUGH AND CHECK FCN LOGIC.");
                Printf(" *******************************************************************************");
                Printf(" *******************************************************************************");
            }
            return;
        }
        for (i = 1; i <= fNpar; ++i) {
            pbig = fP[i - 1];
            plit = pbig;
            for (j = 2; j <= nparp1; ++j) {
                if (fP[i + j * fMaxpar - fMaxpar - 1] > pbig) pbig = fP[i + j * fMaxpar - fMaxpar - 1];
                if (fP[i + j * fMaxpar - fMaxpar - 1] < plit) plit = fP[i + j * fMaxpar - fMaxpar - 1];
            }
            fDirin[i - 1] = pbig - plit;
        }
    }

    /** The seed of MNRN15, shared by all TMinuit like the C++ static. */
    private static int gIseed = 12345;

    /** Puts the random generator back to its starting seed (what a fresh process has). */
    public static synchronized void resetRandomSeed() {
        gIseed = 12345;
    }

    /** Super-portable random number generator (cycle ~10**9): val=3 sets the seed to inseed, else val is the next number and inseed the seed used. */
    public void mnrn15(double[] val, int[] inseed) {
        synchronized (TMinuit.class) {
            if (val[0] == 3) {
                gIseed = inseed[0];
            } else {
                int nextSeed = inseed[0] = gIseed;
                final int k = nextSeed / 53668;
                nextSeed = (nextSeed - k * 53668) * 40014 - k * 12211;
                if (nextSeed < 0) nextSeed += 2147483563;
                val[0] = (double) (nextSeed * 4.656613e-10);
                gIseed = nextSeed;
            }
        }
    }

    /** Resets function value and errors to UNDEFINED (iopt=1), or only the MINOS errors (iopt=0). */
    public void mnrset(int iopt) {
        int iext, i;

        fCstatu = "RESET     ";
        if (iopt >= 1) {
            fAmin = fUndefi;
            fFval3 = Math.abs(fAmin) * 2 + 1;
            fEDM = fBigedm;
            fISW[3] = 0;
            fISW[1] = 0;
            fDcovar = 1;
            fISW[0] = 0;
        }
        fLnolim = true;
        for (i = 1; i <= fNpar; ++i) {
            iext = fNexofi[i - 1];
            if (fNvarl[iext - 1] >= 4) fLnolim = false;
            fErp[i - 1] = 0;
            fErn[i - 1] = 0;
            fGlobcc[i - 1] = 0;
        }
        if (fISW[1] >= 1) {
            fISW[1] = 1;
            fDcovar = TMath.max(fDcovar, .5);
        }
    }

    /** As in ROOT: the SAVE of MINUIT is not implemented. */
    public void mnsave() {
        Printf("mnsave is dummy in TMinuit");
    }

    /** Best FCN value, EDM, UP, number of variable parameters, highest external number, covariance status. */
    public void mnstat(double[] fmin, double[] fedm, double[] errdef, int[] npari, int[] nparx, int[] istat) {
        fmin[0] = fAmin;
        fedm[0] = fEDM;
        errdef[0] = fUp;
        npari[0] = fNpar;
        nparx[0] = fNu;
        istat[0] = fISW[1];
        if (fEDM == fBigedm) fedm[0] = fUp;
        if (fAmin == fUndefi) {
            fmin[0] = 0;
            fedm[0] = fUp;
            istat[0] = 0;
        }
    }

    /** epsbak = epsp1 - 1, to find the machine precision. */
    public void mntiny(double epsp1, double[] epsbak) {
        epsbak[0] = epsp1 - 1;
    }

    private static final String cpt = " ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890./;:[]$%*_!@#&+()";

    /** True if cfname contains unprintable characters. */
    public boolean mnunpt(String cfname) {
        final int l = TStr.strlen(cfname);
        for (int i = 1; i <= l; ++i) {
            if (cpt.indexOf(cfname.charAt(i - 1)) < 0) return true;
        }
        return false;
    }

    /** Inverts the symmetric positive-definite n x n matrix a (leading dimension l), scaled to unit diagonal; ifail 1 on failure. */
    public void mnvert(double[] a, int l, int m, int n, int[] ifail) {
        double si;
        int i, j, k, kp1, km1;
        // a[i + j*l] of the Fortran is a[i + j*l - ao]
        final int ao = l + 1;

        ifail[0] = 0;
        fail:
        {
            if (n < 1) break fail;
            if (n > fMaxint) break fail;
            // scale matrix by sqrt of diag elements
            for (i = 1; i <= n; ++i) {
                si = a[i + i * l - ao];
                if (si <= 0) break fail;
                fVERTs[i - 1] = 1 / TMath.sqrt(si);
            }
            for (i = 1; i <= n; ++i) {
                for (j = 1; j <= n; ++j) {
                    a[i + j * l - ao] = a[i + j * l - ao] * fVERTs[i - 1] * fVERTs[j - 1];
                }
            }
            // start main loop
            for (i = 1; i <= n; ++i) {
                k = i;
                // preparation for elimination step1
                if (a[k + k * l - ao] != 0) fVERTq[k - 1] = 1 / a[k + k * l - ao];
                else break fail;
                fVERTpp[k - 1] = 1;
                a[k + k * l - ao] = 0;
                kp1 = k + 1;
                km1 = k - 1;
                if (km1 < 0) break fail;
                if (km1 > 0) {
                    // L40
                    for (j = 1; j <= km1; ++j) {
                        fVERTpp[j - 1] = a[j + k * l - ao];
                        fVERTq[j - 1] = a[j + k * l - ao] * fVERTq[k - 1];
                        a[j + k * l - ao] = 0;
                    }
                }
                // L50
                if (k - n > 0) break fail;
                if (k - n < 0) {
                    // L51
                    for (j = kp1; j <= n; ++j) {
                        fVERTpp[j - 1] = a[k + j * l - ao];
                        fVERTq[j - 1] = -a[k + j * l - ao] * fVERTq[k - 1];
                        a[k + j * l - ao] = 0;
                    }
                }
                // L60: elimination proper
                for (j = 1; j <= n; ++j) {
                    for (k = j; k <= n; ++k) {
                        a[j + k * l - ao] += fVERTpp[j - 1] * fVERTq[k - 1];
                    }
                }
            }
            // elements of left diagonal and unscaling
            for (j = 1; j <= n; ++j) {
                for (k = 1; k <= j; ++k) {
                    a[k + j * l - ao] = a[k + j * l - ao] * fVERTs[k - 1] * fVERTs[j - 1];
                    a[j + k * l - ao] = a[k + j * l - ao];
                }
            }
            return;
        }
        // L100: failure return
        ifail[0] = 1;
    }

    /**
     * Warning (copt "W") or debug (copt "D") message cmes from corg: printed when SET WARnings (or debug) is on,
     * else kept in a circular buffer, which corg=cmes="SHO" prints and empties.
     */
    public void mnwarn(String copt, String corg, String cmes) {
        final int kMAXMES = 10;
        int ityp, i, ic, nm;
        String englsh, ctyp;

        if (!TStr.sub(corg, 0, 3).equals("SHO") || !TStr.sub(cmes, 0, 3).equals("SHO")) {
            // either print warning or put in buffer
            if (copt.equals("W")) {
                ityp = 1;
                if (fLwarn) {
                    Printf(" MINUIT WARNING IN %s", corg);
                    Printf(" ============== %s", cmes);
                    return;
                }
            } else {
                ityp = 2;
                if (fLrepor) {
                    Printf(" MINUIT DEBUG FOR %s", corg);
                    Printf(" =============== %s ", cmes);
                    return;
                }
            }
            // if appropriate flag is off, fill circular buffer
            if (fNwrmes[ityp - 1] == 0) fIcirc[ityp - 1] = 0;
            ++fNwrmes[ityp - 1];
            ++fIcirc[ityp - 1];
            if (fIcirc[ityp - 1] > 10) fIcirc[ityp - 1] = 1;
            ic = fIcirc[ityp - 1];
            fOrigin[ic] = corg;
            fWarmes[ic] = cmes;
            fNfcwar[ic] = fNfcn;
            return;
        }
        // 'SHO WARnings', ask if any suppressed mess in buffer
        if (copt.equals("W")) {
            ityp = 1;
            ctyp = "WARNING";
        } else {
            ityp = 2;
            ctyp = "*DEBUG*";
        }
        if (fNwrmes[ityp - 1] > 0) {
            englsh = " WAS SUPPRESSED.  ";
            if (fNwrmes[ityp - 1] > 1) englsh = "S WERE SUPPRESSED.";
            Printf(" %5d MINUIT %s MESSAGE%s", fNwrmes[ityp - 1], ctyp, englsh);
            nm = fNwrmes[ityp - 1];
            ic = 0;
            if (nm > kMAXMES) {
                Printf(" ONLY THE MOST RECENT 10 WILL BE LISTED BELOW.");
                nm = kMAXMES;
                ic = fIcirc[ityp - 1];
            }
            Printf("  CALLS  ORIGIN         MESSAGE");
            for (i = 1; i <= nm; ++i) {
                ++ic;
                if (ic > kMAXMES) ic = 1;
                Printf(" %6d  %s  %s", fNfcwar[ic], fOrigin[ic], fWarmes[ic]);
            }
            fNwrmes[ityp - 1] = 0;
            Printf(" ");
        }
    }

    /** The external parameter errors WERR and the global correlation coefficients, from a new covariance matrix. */
    public void mnwerr() {
        double denom, ba, al, dx, du1, du2;
        int ndex, i, j, k, l, ndiag, k1, iin;
        final int[] ierr = {0};

        // calculate external error if v exists
        if (fISW[1] >= 1) {
            for (l = 1; l <= fNpar; ++l) {
                ndex = l * (l + 1) / 2;
                dx = TMath.sqrt(Math.abs(fVhmat[ndex - 1] * fUp));
                i = fNexofi[l - 1];
                if (fNvarl[i - 1] > 1) {
                    al = fAlim[i - 1];
                    ba = fBlim[i - 1] - al;
                    du1 = al + 0.5 * (TMath.sin(fX[l - 1] + dx) + 1) * ba - fU[i - 1];
                    du2 = al + 0.5 * (TMath.sin(fX[l - 1] - dx) + 1) * ba - fU[i - 1];
                    if (dx > 1) du1 = ba;
                    dx = 0.5 * (Math.abs(du1) + Math.abs(du2));
                }
                fWerr[l - 1] = dx;
            }
        }
        // global correlation coefficients
        if (fISW[1] >= 1) {
            for (i = 1; i <= fNpar; ++i) {
                fGlobcc[i - 1] = 0;
                k1 = i * (i - 1) / 2;
                for (j = 1; j <= i; ++j) {
                    k = k1 + j;
                    fP[i + j * fMaxpar - fMaxpar - 1] = fVhmat[k - 1];
                    fP[j + i * fMaxpar - fMaxpar - 1] = fP[i + j * fMaxpar - fMaxpar - 1];
                }
            }
            mnvert(fP, fMaxint, fMaxint, fNpar, ierr);
            if (ierr[0] == 0) {
                for (iin = 1; iin <= fNpar; ++iin) {
                    ndiag = iin * (iin + 1) / 2;
                    denom = fP[iin + iin * fMaxpar - fMaxpar - 1] * fVhmat[ndiag - 1];
                    if (denom <= 1 && denom >= 0) fGlobcc[iin - 1] = 0;
                    else fGlobcc[iin - 1] = TMath.sqrt(1 - 1 / denom);
                }
            }
        }
    }

    /**
     * Plots the points (xpt, ypt) with the characters chpt on one page with labelled axes (the arrays are
     * destroyed); in graphics mode (the default) a TMinuitGraph of the points but the first two instead, for GetPlot().
     */
    public void mnplot(double[] xpt, double[] ypt, char[] chpt, int nxypt, int npagwd, int npagln) {
        if (fGraphicsMode) {
            // ROOT's plugin "TMinuitGraph": remove the first two points
            fPlot = new TMinuitGraph(nxypt - 2, Arrays.copyOfRange(xpt, 2, xpt.length), Arrays.copyOfRange(ypt, 2, ypt.length));
            return;
        }
        double xmin, ymin, xmax, ymax, savx, savy, yprt;
        double bwidx, bwidy, xbest, ybest, ax, ay, bx, by;
        final double[] xvalus = new double[12];
        double any, dxx, dyy;
        int iten, i, j, k, maxnx, maxny, iquit, ni, linodd;
        int nxbest, nybest, km1, ibk, isp1, nx, ny, ks, ix;
        String chmess, ctemp;
        boolean overpr;
        final char[] cline = new char[144];
        char chsav, chbest;
        final double[] bl = {0};
        final double[] bh = {0};
        final double[] bw = {0};
        final int[] nb = {0};

        maxnx = TMath.min(npagwd - 20, 100);
        if (maxnx < 10) maxnx = 10;
        maxny = npagln;
        if (maxny < 10) maxny = 10;
        if (nxypt <= 1) return;
        xbest = xpt[0];
        ybest = ypt[0];
        chbest = chpt[0];
        // order the points by decreasing y
        km1 = nxypt - 1;
        for (i = 1; i <= km1; ++i) {
            iquit = 0;
            ni = nxypt - i;
            for (j = 1; j <= ni; ++j) {
                if (ypt[j - 1] > ypt[j]) continue;
                savx = xpt[j - 1];
                xpt[j - 1] = xpt[j];
                xpt[j] = savx;
                savy = ypt[j - 1];
                ypt[j - 1] = ypt[j];
                ypt[j] = savy;
                chsav = chpt[j - 1];
                chpt[j - 1] = chpt[j];
                chpt[j] = chsav;
                iquit = 1;
            }
            if (iquit == 0) break;
        }
        // find extreme values
        xmax = xpt[0];
        xmin = xmax;
        for (i = 1; i <= nxypt; ++i) {
            if (xpt[i - 1] > xmax) xmax = xpt[i - 1];
            if (xpt[i - 1] < xmin) xmin = xpt[i - 1];
        }
        dxx = (xmax - xmin) * .001;
        xmax += dxx;
        xmin -= dxx;
        mnbins(xmin, xmax, maxnx, bl, bh, nb, bw);
        xmin = bl[0];
        xmax = bh[0];
        nx = nb[0];
        bwidx = bw[0];
        ymax = ypt[0];
        ymin = ypt[nxypt - 1];
        if (ymax == ymin) ymax = ymin + 1;
        dyy = (ymax - ymin) * .001;
        ymax += dyy;
        ymin -= dyy;
        mnbins(ymin, ymax, maxny, bl, bh, nb, bw);
        ymin = bl[0];
        ymax = bh[0];
        ny = nb[0];
        bwidy = bw[0];
        any = (double) ny;
        // if first point is blank, it is an 'origin'
        if (chbest != ' ') {
            xbest = (xmax + xmin) * .5;
            ybest = (ymax + ymin) * .5;
        }
        // L50: find scale constants
        ax = 1 / bwidx;
        ay = 1 / bwidy;
        bx = -ax * xmin + 2;
        by = -ay * ymin - 2;
        // convert points to grid positions
        for (i = 1; i <= nxypt; ++i) {
            xpt[i - 1] = ax * xpt[i - 1] + bx;
            ypt[i - 1] = any - ay * ypt[i - 1] - by;
        }
        nxbest = (int) (ax * xbest + bx);
        nybest = (int) (any - ay * ybest - by);
        // print the points
        ny += 2;
        nx += 2;
        isp1 = 1;
        linodd = 1;
        overpr = false;
        for (i = 1; i <= ny; ++i) {
            for (ibk = 1; ibk <= nx; ++ibk) {
                cline[ibk - 1] = ' ';
            }
            cline[nx] = '\0';
            cline[nx + 1] = '\0';
            cline[0] = '.';
            if (nx > 0) cline[nx - 1] = '.';
            // out of the line, the C++ writes beside its buffer: nothing shows
            if (nxbest >= 1 && nxbest <= cline.length) cline[nxbest - 1] = '.';
            if (i == 1 || i == nybest || i == ny) {
                for (j = 1; j <= nx; ++j) {
                    cline[j - 1] = '.';
                }
            }
            // L320
            yprt = ymax - (double) (i - 1) * bwidy;
            if (isp1 <= nxypt) {
                // find the points to be plotted on this line
                boolean below = false;
                for (k = isp1; k <= nxypt; ++k) {
                    ks = (int) ypt[k - 1];
                    if (ks > i) {
                        below = true;
                        break;
                    }
                    ix = (int) xpt[k - 1];
                    if (cline[ix - 1] == '.' || cline[ix - 1] == ' ') {
                        // L340
                        cline[ix - 1] = chpt[k - 1];
                        continue;
                    }
                    if (cline[ix - 1] == chpt[k - 1]) continue;
                    // OVERPR is true if one or more positions contains more than one point
                    overpr = true;
                    cline[ix - 1] = '&';
                }
                isp1 = below ? k : nxypt + 1;
            }
            // L350
            ctemp = TStr.cstr(cline);
            if (linodd == 1 || i == ny) {
                // L380
                Printf(" %14.7g ..%s", yprt, ctemp);
                linodd = 0;
            } else {
                linodd = 1;
                Printf("                  %s", ctemp);
            }
        }
        // print labels on x-axis every ten columns
        for (ibk = 1; ibk <= nx; ++ibk) {
            cline[ibk - 1] = ' ';
            if (ibk % 10 == 1) cline[ibk - 1] = '/';
        }
        Printf("                  %s", TStr.cstr(cline));

        for (ibk = 1; ibk <= 12; ++ibk) {
            xvalus[ibk - 1] = xmin + (double) (ibk - 1) * 10 * bwidx;
        }
        iten = (nx + 9) / 10;
        Printf("           ");
        for (ibk = 1; ibk <= iten; ++ibk) {
            Printf("%# 8.3g ", xvalus[ibk - 1]);
        }
        Printf("\n");
        chmess = " ";
        if (overpr) chmess = "   Overprint character is &";
        Printf("                         ONE COLUMN=%13.7g%s", bwidx, chmess);
    }

    /**
     * Prints the parameters and the function value, EDM, errors, step sizes: inkode 0 function value only, 1 values,
     * errors, limits, 2 with step sizes and internal values, 3 with first derivatives, 4 with MINOS errors, 5 as fISW[1] says.
     */
    public void mnprin(int inkode, double fval) {
        final String cblank = "           ";
        String cnambf;
        double dcmax, x1, x2 = 0, x3 = 0, dc;
        int nadd, i, k, l, m, ikode, ic, nc, ntrail, lbl;
        String chedm, cx2, cx3, cheval;
        final String[] colhdl = new String[6];
        final String[] colhdu = new String[6];

        if (fNu == 0) {
            Printf(" THERE ARE CURRENTLY NO PARAMETERS DEFINED");
            return;
        }
        // get value of IKODE based in INKODE, fISW[1]
        ikode = inkode;
        if (inkode == 5) {
            ikode = fISW[1] + 1;
            if (ikode > 3) ikode = 3;
        }
        // set 'default' column headings
        for (k = 1; k <= 6; ++k) {
            colhdu[k - 1] = "UNDEFINED";
            colhdl[k - 1] = "COLUMN HEAD";
        }
        // print title if Minos errors, and title exists.
        if (ikode == 4 && !fCtitl.equals(fCundef)) {
            Printf(" MINUIT TASK: %s", fCtitl);
        }
        // report function value and status
        if (fval == fUndefi) cheval = " unknown       ";
        else cheval = CFormat.sprintf("%g", fval);

        if (fEDM == fBigedm) chedm = " unknown  ";
        else chedm = CFormat.sprintf("%g", fEDM);

        nc = fNfcn - fNfcnfr;
        Printf(" FCN=%s FROM %8s  STATUS=%10s %6d CALLS   %9d TOTAL", cheval, fCfrom, fCstatu, nc, fNfcn);
        m = fISW[1];
        if (m == 0 || m == 2 || fDcovar == 0) {
            Printf("                     EDM=%s    STRATEGY=%2d      %s", chedm, fIstrat, fCovmes[m]);
        } else {
            dcmax = 1;
            dc = TMath.min(fDcovar, dcmax) * 100;
            Printf("                     EDM=%s    STRATEGY=%2d  ERROR MATRIX UNCERTAINTY %5.1f per cent", chedm, fIstrat, dc);
        }

        if (ikode == 0) return;
        // find longest name (for Rene!)
        ntrail = 10;
        for (i = 1; i <= fNu; ++i) {
            if (fNvarl[i - 1] < 0) continue;
            for (ic = 10; ic >= 1; --ic) {
                if (!TStr.sub(fCpnam[i - 1], ic - 1, 1).equals(" ")) break;
            }
            if (ic < 1) ic = 1;
            // L16
            lbl = 10 - ic;
            if (lbl < ntrail) ntrail = lbl;
        }
        nadd = ntrail / 2 + 1;
        if (ikode == 1) {
            colhdu[0] = "              ";
            colhdl[0] = "      ERROR   ";
            colhdu[1] = "      PHYSICAL";
            colhdu[2] = " LIMITS       ";
            colhdl[1] = "    NEGATIVE  ";
            colhdl[2] = "    POSITIVE  ";
        }
        if (ikode == 2) {
            colhdu[0] = "              ";
            colhdl[0] = "      ERROR   ";
            colhdu[1] = "    INTERNAL  ";
            colhdl[1] = "    STEP SIZE ";
            colhdu[2] = "    INTERNAL  ";
            colhdl[2] = "      VALUE   ";
        }
        if (ikode == 3) {
            colhdu[0] = "              ";
            colhdl[0] = "      ERROR   ";
            colhdu[1] = "       STEP   ";
            colhdl[1] = "       SIZE   ";
            colhdu[2] = "      FIRST   ";
            colhdl[2] = "   DERIVATIVE ";
        }
        if (ikode == 4) {
            colhdu[0] = "    PARABOLIC ";
            colhdl[0] = "      ERROR   ";
            colhdu[1] = "        MINOS ";
            colhdu[2] = "ERRORS        ";
            colhdl[1] = "   NEGATIVE   ";
            colhdl[2] = "   POSITIVE   ";
        }

        if (ikode != 4) {
            if (fISW[1] < 3) colhdu[0] = "  APPROXIMATE ";
            if (fISW[1] < 1) colhdu[0] = " CURRENT GUESS";
        }
        Printf("  EXT PARAMETER              %-14s%-14s%-14s", colhdu[0], colhdu[1], colhdu[2]);
        Printf("  NO.   NAME      VALUE      %-14s%-14s%-14s", colhdl[0], colhdl[1], colhdl[2]);
        // loop over parameters
        for (i = 1; i <= fNu; ++i) {
            if (fNvarl[i - 1] < 0) continue;
            l = fNiofex[i - 1];
            cnambf = TStr.sub(cblank, 0, nadd) + fCpnam[i - 1];
            if (l == 0) {
                // L55: print constant or fixed parameter.
                colhdu[0] = "   constant   ";
                if (fNvarl[i - 1] > 0) colhdu[0] = "     fixed    ";
                if (fNvarl[i - 1] == 4 && ikode == 1) {
                    Printf("%4d %-11s%14.5e%-14s%14.5e%14.5e", i, cnambf, fU[i - 1], colhdu[0], fAlim[i - 1], fBlim[i - 1]);
                } else {
                    Printf("%4d %-11s%14.5e%s", i, cnambf, fU[i - 1], colhdu[0]);
                }
                continue;
            }
            // variable parameter.
            x1 = fWerr[l - 1];
            cx2 = "PLEASE GET X..";
            cx3 = "PLEASE GET X..";
            if (ikode == 1) {
                if (fNvarl[i - 1] <= 1) {
                    Printf("%4d %-11s%14.5e%14.5e", i, cnambf, fU[i - 1], x1);
                    continue;
                } else {
                    x2 = fAlim[i - 1];
                    x3 = fBlim[i - 1];
                }
            }
            if (ikode == 2) {
                x2 = fDirin[l - 1];
                x3 = fX[l - 1];
            }
            if (ikode == 3) {
                x2 = fDirin[l - 1];
                x3 = fGrd[l - 1];
                if (fNvarl[i - 1] > 1 && Math.abs(TMath.cos(fX[l - 1])) < .001) {
                    cx3 = "** at limit **";
                }
            }
            if (ikode == 4) {
                x2 = fErn[l - 1];
                if (x2 == 0) cx2 = " ";
                if (x2 == fUndefi) cx2 = "   at limit   ";
                x3 = fErp[l - 1];
                if (x3 == 0) cx3 = " ";
                if (x3 == fUndefi) cx3 = "   at limit   ";
            }
            if (cx2.equals("PLEASE GET X..")) cx2 = CFormat.sprintf("%14.5e", x2);
            if (cx3.equals("PLEASE GET X..")) cx3 = CFormat.sprintf("%14.5e", x3);
            Printf("%4d %-11s%14.5e%14.5e%-14s%-14s", i, cnambf, fU[i - 1], x1, cx2, cx3);

            // check if parameter is at limit
            if (fNvarl[i - 1] <= 1 || ikode == 3) continue;
            if (Math.abs(TMath.cos(fX[l - 1])) < .001) {
                Printf("                                 WARNING -   - ABOVE PARAMETER IS AT LIMIT.");
            }
        }

        if (fUp != fUpdflt) {
            Printf("                               ERR DEF= %g", fUp);
        }
    }

    /** Scans FCN along one parameter (or all of them) and plots it with MNPLOT, keeping the best value found. */
    public void mnscan() {
        double step = 0, xhreq, xlreq, ubest = 0, fnext = 0, unext = 0, xh, xl;
        int ipar, iint, icall, ncall, nbins, nparx;
        int nxypt, nccall, iparwd;
        final double[] bl = {0};
        final double[] bh = {0};
        final double[] bw = {0};
        final int[] nb = {0};

        xlreq = TMath.min(fWord7[2], fWord7[3]);
        xhreq = TMath.max(fWord7[2], fWord7[3]);
        ncall = (int) (fWord7[1] + .01);
        if (ncall <= 1) ncall = 41;
        if (ncall > 98) ncall = 98;
        nccall = ncall;
        if (fAmin == fUndefi) mnamin();
        iparwd = (int) (fWord7[0] + .1);
        ipar = TMath.max(iparwd, 0);
        fCstatu = "NO CHANGE";
        int L = iparwd > 0 ? 200 : 100;
        while (true) {
            switch (L) {
                case 100: // equivalent to a loop over parameters requested
                    ++ipar;
                    if (ipar > fNu) {
                        L = 900;
                        continue;
                    }
                    iint = fNiofex[ipar - 1];
                    if (iint <= 0) {
                        L = 100;
                        continue;
                    }
                    // fall through
                case 200: // set up range for parameter IPAR
                    iint = fNiofex[ipar - 1];
                    ubest = fU[ipar - 1];
                    fXpt[0] = ubest;
                    fYpt[0] = fAmin;
                    fChpt[0] = ' ';
                    fXpt[1] = ubest;
                    fYpt[1] = fAmin;
                    fChpt[1] = 'X';
                    nxypt = 2;
                    if (fNvarl[ipar - 1] > 1) {
                        // L300: limits on parameter
                        if (xlreq == xhreq) {
                            // L350
                            unext = fAlim[ipar - 1];
                            step = (fBlim[ipar - 1] - fAlim[ipar - 1]) / (double) (ncall - 1);
                        } else {
                            xl = TMath.max(xlreq, fAlim[ipar - 1]);
                            xh = TMath.min(xhreq, fBlim[ipar - 1]);
                            if (xl >= xh) {
                                L = 700;
                                continue;
                            }
                            unext = xl;
                            step = (xh - xl) / (double) (ncall - 1);
                        }
                    } else if (xlreq == xhreq) {
                        // L250: no limits on parameter, no range requested
                        xl = ubest - fWerr[iint - 1];
                        xh = ubest + fWerr[iint - 1];
                        bw[0] = step;
                        mnbins(xl, xh, ncall, bl, bh, nb, bw);
                        unext = bl[0];
                        nbins = nb[0];
                        step = bw[0];
                        nccall = nbins + 1;
                    } else {
                        unext = xlreq;
                        step = (xhreq - xlreq) / (double) (ncall - 1);
                    }
                    // L500: main scanning loop over parameter IPAR
                    for (icall = 1; icall <= nccall; ++icall) {
                        fU[ipar - 1] = unext;
                        nparx = fNpar;
                        fnext = eval(nparx, fnext, 4);
                        ++fNfcn;
                        ++nxypt;
                        fXpt[nxypt - 1] = unext;
                        fYpt[nxypt - 1] = fnext;
                        fChpt[nxypt - 1] = '*';
                        if (fnext < fAmin) {
                            fAmin = fnext;
                            ubest = unext;
                            fCstatu = "IMPROVED  ";
                        }
                        unext += step;
                    }
                    fChpt[nccall] = 0;

                    // finished with scan of parameter IPAR
                    fU[ipar - 1] = ubest;
                    mnexin(fX);
                    if (fISW[4] >= 1) {
                        Printf("%dSCAN OF PARAMETER NO. %d,  %s", fNewpag, ipar, fCpnam[ipar - 1]);
                    }
                    mnplot(fXpt, fYpt, fChpt, nxypt, fNpagwd, fNpagln);
                    L = 800;
                    continue;
                case 700:
                    Printf(" REQUESTED RANGE OUTSIDE LIMITS FOR PARAMETER  %d", ipar);
                    // fall through
                case 800:
                    if (iparwd <= 0) {
                        L = 100;
                        continue;
                    }
                    // fall through
                case 900: // finished with all parameters
                    if (fISW[4] >= 0) mnprin(5, fAmin);
                    return;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    /**
     * A rough (but global) Monte Carlo minimization: random points over a hypercube centered at the best value,
     * a worse one accepted with probability exp(-d/UP) (Metropolis).
     */
    public void mnseek() {
        final double[] dxdi = {0};
        final double[] rnum = {0};
        final double[] rnum1 = {0};
        final double[] rnum2 = {0};
        final int[] iseed = {0};
        double ftry = 0, alpha, flast, bar;
        int ipar, iext, j, ifail, nparx, istep, ib, mxfail, mxstep;

        mxfail = (int) fWord7[0];
        if (mxfail <= 0) mxfail = fNpar * 20 + 100;
        mxstep = mxfail * 10;
        if (fAmin == fUndefi) mnamin();
        alpha = fWord7[1];
        if (alpha <= 0) alpha = 3;
        if (fISW[4] >= 1) {
            Printf(" MNSEEK: MONTE CARLO MINIMIZATION USING METROPOLIS ALGORITHM");
            Printf(" TO STOP AFTER %6d SUCCESSIVE FAILURES, OR %7d STEPS", mxfail, mxstep);
            Printf(" MAXIMUM STEP SIZE IS %9.3f ERROR BARS.", alpha);
        }
        fCstatu = "INITIAL  ";
        if (fISW[4] >= 2) mnprin(2, fAmin);
        fCstatu = "UNCHANGED ";
        ifail = 0;
        rnum[0] = 0;
        rnum1[0] = 0;
        rnum2[0] = 0;
        nparx = fNpar;
        flast = fAmin;
        // set up step sizes, starting values
        for (ipar = 1; ipar <= fNpar; ++ipar) {
            iext = fNexofi[ipar - 1];
            fDirin[ipar - 1] = alpha * 2 * fWerr[ipar - 1];
            if (fNvarl[iext - 1] > 1) {
                // parameter with limits
                mndxdi(fX[ipar - 1], ipar - 1, dxdi);
                if (dxdi[0] == 0) dxdi[0] = 1;
                fDirin[ipar - 1] = alpha * 2 * fWerr[ipar - 1] / dxdi[0];
                if (Math.abs(fDirin[ipar - 1]) > 6.2831859999999997) {
                    fDirin[ipar - 1] = 6.2831859999999997;
                }
            }
            fSEEKxmid[ipar - 1] = fX[ipar - 1];
            fSEEKxbest[ipar - 1] = fX[ipar - 1];
        }
        // search loop
        for (istep = 1; istep <= mxstep; ++istep) {
            if (ifail >= mxfail) break;
            for (ipar = 1; ipar <= fNpar; ++ipar) {
                mnrn15(rnum1, iseed);
                mnrn15(rnum2, iseed);
                fX[ipar - 1] = fSEEKxmid[ipar - 1] + (rnum1[0] + rnum2[0] - 1) * .5 * fDirin[ipar - 1];
            }
            mninex(fX);
            ftry = eval(nparx, ftry, 4);
            ++fNfcn;
            if (ftry < flast) {
                if (ftry < fAmin) {
                    fCstatu = "IMPROVEMENT";
                    fAmin = ftry;
                    for (ib = 1; ib <= fNpar; ++ib) {
                        fSEEKxbest[ib - 1] = fX[ib - 1];
                    }
                    ifail = 0;
                    if (fISW[4] >= 2) mnprin(2, fAmin);
                }
            } else {
                ++ifail;
                // Metropolis algorithm
                bar = (fAmin - ftry) / fUp;
                mnrn15(rnum, iseed);
                if (bar < TMath.log(rnum[0])) continue;
            }
            // L300: accept new point, move there
            for (j = 1; j <= fNpar; ++j) {
                fSEEKxmid[j - 1] = fX[j - 1];
            }
            flast = ftry;
        }
        // end search loop
        if (fISW[4] > 1) {
            Printf(" MNSEEK: %5d SUCCESSIVE UNSUCCESSFUL TRIALS.", ifail);
        }
        for (ib = 1; ib <= fNpar; ++ib) {
            fX[ib - 1] = fSEEKxbest[ib - 1];
        }
        mninex(fX);
        if (fISW[4] >= 1) mnprin(2, fAmin);
        if (fISW[4] == 0) mnprin(0, fAmin);
    }

    private static final String[] SET_NAMES = {
        "FCN value ",
        "PARameters",
        "LIMits    ",
        "COVariance",
        "CORrelatio",
        "PRInt levl",
        "NOGradient",
        "GRAdient  ",
        "ERRor def ",
        "INPut file",
        "WIDth page",
        "LINes page",
        "NOWarnings",
        "WARnings  ",
        "RANdom gen",
        "TITle     ",
        "STRategy  ",
        "EIGenvalue",
        "PAGe throw",
        "MINos errs",
        "EPSmachine",
        "OUTputfile",
        "BATch     ",
        "INTeractiv",
        "VERsion   ",
        "reserve   ",
        "NODebug   ",
        "DEBug     ",
        "SHOw      ",
        "SET       "};

    private static final String[] SET_CPRLEV = {
        "-1: NO OUTPUT EXCEPT FROM SHOW    ",
        " 0: REDUCED OUTPUT                ",
        " 1: NORMAL OUTPUT                 ",
        " 2: EXTRA OUTPUT FOR PROBLEM CASES",
        " 3: MAXIMUM OUTPUT                "};

    private static final String[] SET_CSTRAT = {
        " 0: MINIMIZE THE NUMBER OF CALLS TO FUNCTION",
        " 1: TRY TO BALANCE SPEED AGAINST RELIABILITY",
        " 2: MAKE SURE MINIMUM TRUE, ERRORS CORRECT  "};

    private static final String[] SET_CDBOPT = {
        "REPORT ALL EXCEPTIONAL CONDITIONS      ",
        "MNLINE: LINE SEARCH MINIMIZATION       ",
        "MNDERI: FIRST DERIVATIVE CALCULATIONS  ",
        "MNHESS: SECOND DERIVATIVE CALCULATIONS ",
        "MNMIGR: COVARIANCE MATRIX UPDATES      ",
        "MNHES1: FIRST DERIVATIVE UNCERTAINTIES ",
        "MNCONT: MNCONTOUR PLOT (MNCROS SEARCH) "};

    /** Interprets the commands that start with SET and SHOW; called from MNEXCM. */
    public void mnset() {
        final String[] cname = SET_NAMES;
        final int nname = 25;
        final int nntot = cname.length;
        final double[] val = {0};
        int iset = 0, iprm, i, kname, iunit, id, ii, kk;
        int ikseed, idbopt = 0, iswsav, isw2;
        final int[] jseed = {0};
        final int[] iseed = {0};
        final int[] igrain = {0};
        String cfname = "", cmode, ckind = "", cwarn, copt, ctemp, ctemp2;
        final boolean lname = false;

        for (i = 1; i <= nntot; ++i) {
            ctemp = cname[i - 1];
            ckind = TStr.sub(ctemp, 0, 3);
            ctemp2 = TStr.sub(fCword, 4, 6);
            if (TStr.strstr(ctemp2, ckind)) break;
        }
        if (i > nntot) i = 0;
        // L5
        kname = i;

        // command could be SET xxx, SHOW xxx, HELP SET or HELP SHOW
        int L;
        ctemp2 = TStr.sub(fCword, 0, 3);
        if (ctemp2.contains("HEL")) {
            L = 2000;
        } else if (ctemp2.contains("SHO")) {
            L = 1000;
        } else if (!ctemp2.contains("SET")) {
            L = 1900;
        } else {
            ckind = "SET ";
            // set unknown, set known
            L = kname <= 0 ? 1900 : switch (kname) {
                case 2 -> 20;
                case 3 -> 30;
                case 4 -> 40;
                case 6 -> 60;
                case 7 -> 70;
                case 8 -> 80;
                case 9 -> 90;
                case 10 -> 100;
                case 11 -> 110;
                case 12 -> 120;
                case 13 -> 130;
                case 14 -> 140;
                case 15 -> 150;
                case 16 -> 160;
                case 17 -> 170;
                case 19 -> 190;
                case 21 -> 210;
                case 22 -> 220;
                case 23 -> 230;
                case 24 -> 240;
                case 26 -> 1900;
                case 27 -> 270;
                case 28 -> 280;
                case 29 -> 290;
                case 30 -> 300;
                default -> 3000; // 1, 5, 18, 20, 25
            };
        }
        while (true) {
            switch (L) {
                case 20: // set param
                    iprm = (int) fWord7[0];
                    if (iprm > fNu || iprm <= 0 || fNvarl[iprm - 1] < 0) {
                        // L25
                        Printf(" UNDEFINED PARAMETER NUMBER.  IGNORED.");
                        return;
                    }
                    fU[iprm - 1] = fWord7[1];
                    mnexin(fX);
                    isw2 = fISW[1];
                    mnrset(1);
                    // keep approximate covariance matrix, even if new param value
                    fISW[1] = TMath.min(isw2, 1);
                    fCfrom = "SET PARM";
                    fNfcnfr = fNfcn;
                    fCstatu = "NEW VALUES";
                    return;
                case 30: // set limits
                    mnlims();
                    return;
                case 40: // set covar: must be handled by MNREAD, and is not Fortran-callable
                    L = 3000;
                    continue;
                case 60: // set print
                    fISW[4] = (int) fWord7[0];
                    return;
                case 70: // set nograd
                    fISW[2] = 0;
                    return;
                case 80: // set grad
                    mngrad();
                    return;
                case 90: // set errdef
                    if (fWord7[0] == fUp) return;
                    if (fWord7[0] <= 0) {
                        if (fUp == fUpdflt) return;
                        fUp = fUpdflt;
                    } else {
                        fUp = fWord7[0];
                    }
                    for (i = 1; i <= fNpar; ++i) {
                        fErn[i - 1] = 0;
                        fErp[i - 1] = 0;
                    }
                    mnwerr();
                    return;
                case 100: // set input: must be handled by MNREAD; if it gets this far, it is illegal
                    L = 3000;
                    continue;
                case 110: // set width
                    fNpagwd = (int) fWord7[0];
                    fNpagwd = TMath.max(fNpagwd, 50);
                    return;
                case 120:
                    fNpagln = (int) fWord7[0];
                    return;
                case 130: // set nowarn
                    fLwarn = false;
                    return;
                case 140: // set warn
                    fLwarn = true;
                    mnwarn("W", "SHO", "SHO");
                    return;
                case 150: // set random
                    jseed[0] = (int) fWord7[0];
                    val[0] = 3;
                    mnrn15(val, jseed);
                    if (fISW[4] > 0) {
                        Printf(" MINUIT RANDOM NUMBER SEED SET TO %d", jseed[0]);
                    }
                    return;
                case 160: // set title: must be handled by MNREAD, and is not Fortran-callable
                    L = 3000;
                    continue;
                case 170: // set strategy
                    fIstrat = (int) fWord7[0];
                    fIstrat = TMath.max(fIstrat, 0);
                    fIstrat = TMath.min(fIstrat, 2);
                    if (fISW[4] > 0) {
                        L = 1172;
                        continue;
                    }
                    return;
                case 190: // set page throw
                    fNewpag = (int) fWord7[0];
                    L = 1190;
                    continue;
                case 210: // set epsmac
                    if (fWord7[0] > 0 && fWord7[0] < .1) {
                        fEpsmac = fWord7[0];
                    }
                    fEpsma2 = TMath.sqrt(fEpsmac);
                    L = 1210;
                    continue;
                case 220: // set outputfile
                    iunit = (int) fWord7[0];
                    fIsyswr = iunit;
                    fIstkwr[0] = iunit;
                    if (fISW[4] >= 0) {
                        L = 1220;
                        continue;
                    }
                    return;
                case 230: // set batch
                    fISW[5] = 0;
                    if (fISW[4] >= 0) {
                        L = 1100;
                        continue;
                    }
                    return;
                case 240: // set interactive
                    fISW[5] = 1;
                    if (fISW[4] >= 0) {
                        L = 1100;
                        continue;
                    }
                    return;
                case 270: // set nodebug
                    iset = 0;
                    L = 281;
                    continue;
                case 280: // set debug
                    iset = 1;
                    // fall through
                case 281:
                    idbopt = (int) fWord7[0];
                    if (idbopt > 6) {
                        // L288
                        Printf(" UNKNOWN DEBUG OPTION %d REQUESTED. IGNORED", idbopt);
                        return;
                    }
                    if (idbopt >= 0) {
                        fIdbg[idbopt] = iset;
                        if (iset == 1) fIdbg[0] = 1;
                    } else {
                        // SET DEBUG -1 sets all debug options
                        for (id = 0; id <= 6; ++id) {
                            fIdbg[id] = iset;
                        }
                    }
                    fLrepor = fIdbg[0] >= 1;
                    mnwarn("D", "SHO", "SHO");
                    return;
                case 290: // set show
                case 300: // set set
                    L = 3000;
                    continue;
                case 1000: // at this point, CWORD must be 'SHOW'
                    ckind = "SHOW";
                    L = kname <= 0 ? 1900 : switch (kname) {
                        case 1 -> 1010;
                        case 2 -> 1020;
                        case 3 -> 1030;
                        case 4 -> 1040;
                        case 5 -> 1050;
                        case 6 -> 1060;
                        case 7, 8 -> 1070;
                        case 9 -> 1090;
                        case 10, 23, 24 -> 1100;
                        case 11 -> 1110;
                        case 12 -> 1120;
                        case 13, 14 -> 1130;
                        case 15 -> 1150;
                        case 16 -> 1160;
                        case 17 -> 1170;
                        case 18 -> 1180;
                        case 19 -> 1190;
                        case 20 -> 1200;
                        case 21 -> 1210;
                        case 22 -> 1220;
                        case 25 -> 1250;
                        case 27, 28 -> 1270;
                        case 29 -> 1290;
                        case 30 -> 1300;
                        default -> 1900; // 26
                    };
                    continue;
                case 1010: // show fcn
                    if (fAmin == fUndefi) mnamin();
                    mnprin(0, fAmin);
                    return;
                case 1020: // show param
                    if (fAmin == fUndefi) mnamin();
                    mnprin(5, fAmin);
                    return;
                case 1030: // show limits
                    if (fAmin == fUndefi) mnamin();
                    mnprin(1, fAmin);
                    return;
                case 1040: // show covar
                    mnmatu(1);
                    return;
                case 1050: // show corre
                    mnmatu(0);
                    return;
                case 1060: // show print
                    if (fISW[4] < -1) fISW[4] = -1;
                    if (fISW[4] > 3) fISW[4] = 3;
                    Printf(" ALLOWED PRINT LEVELS ARE:");
                    Printf("                           %s", SET_CPRLEV[0]);
                    Printf("                           %s", SET_CPRLEV[1]);
                    Printf("                           %s", SET_CPRLEV[2]);
                    Printf("                           %s", SET_CPRLEV[3]);
                    Printf("                           %s", SET_CPRLEV[4]);
                    Printf(" CURRENT PRINTOUT LEVEL IS %s", SET_CPRLEV[fISW[4] + 1]);
                    return;
                case 1070: // show nograd, grad
                    if (fISW[2] <= 0) {
                        Printf(" NOGRAD IS SET.  DERIVATIVES NOT COMPUTED IN FCN.");
                    } else {
                        Printf("   GRAD IS SET.  USER COMPUTES DERIVATIVES IN FCN.");
                    }
                    return;
                case 1090: // show errdef
                    Printf(" ERRORS CORRESPOND TO FUNCTION CHANGE OF %g", fUp);
                    return;
                case 1100: // show input, batch, or interactive
                    cmode = "BATCH MODE      ";
                    if (fISW[5] == 1) cmode = "INTERACTIVE MODE";
                    if (!lname) cfname = "unknown";
                    Printf(" INPUT NOW BEING READ IN %s FROM UNIT NO. %d FILENAME: %s", cmode, fIsysrd, cfname);
                    return;
                case 1110: // show width
                    Printf("          PAGE WIDTH IS SET TO %d COLUMNS", fNpagwd);
                    return;
                case 1120: // show lines
                    Printf("          PAGE LENGTH IS SET TO %d LINES", fNpagln);
                    return;
                case 1130: // show nowarn, warn
                    cwarn = "SUPPRESSED";
                    if (fLwarn) cwarn = "REPORTED  ";
                    Printf("%s", cwarn);
                    if (!fLwarn) mnwarn("W", "SHO", "SHO");
                    return;
                case 1150: // show random
                    val[0] = 0;
                    mnrn15(val, igrain);
                    ikseed = igrain[0];
                    Printf(" MINUIT RNDM SEED IS CURRENTLY=%d", ikseed);
                    val[0] = 3;
                    iseed[0] = ikseed;
                    mnrn15(val, iseed);
                    return;
                case 1160: // show title
                    Printf(" TITLE OF CURRENT TASK IS:%s", fCtitl);
                    return;
                case 1170: // show strategy
                    Printf(" ALLOWED STRATEGIES ARE:");
                    Printf("                    %s", SET_CSTRAT[0]);
                    Printf("                    %s", SET_CSTRAT[1]);
                    Printf("                    %s", SET_CSTRAT[2]);
                    // fall through
                case 1172:
                    Printf(" NOW USING STRATEGY %s", SET_CSTRAT[fIstrat]);
                    return;
                case 1180: // show eigenvalues
                    iswsav = fISW[4];
                    fISW[4] = 3;
                    if (fISW[1] < 1) {
                        Printf("%s", fCovmes[0]);
                    } else {
                        mnpsdf();
                    }
                    fISW[4] = iswsav;
                    return;
                case 1190: // show page throw
                    Printf(" PAGE THROW CARRIAGE CONTROL = %d", fNewpag);
                    if (fNewpag == 0) {
                        Printf(" NO PAGE THROWS IN MINUIT OUTPUT");
                    }
                    return;
                case 1200: // show minos errors
                    for (ii = 1; ii <= fNpar; ++ii) {
                        if (fErp[ii - 1] > 0 || fErn[ii - 1] < 0) {
                            // L1204
                            mnprin(4, fAmin);
                            return;
                        }
                    }
                    Printf("       THERE ARE NO MINOS ERRORS CURRENTLY VALID.");
                    return;
                case 1210: // show epsmac
                    Printf(" FLOATING-POINT NUMBERS ASSUMED ACCURATE TO %g", fEpsmac);
                    return;
                case 1220: // show outputfiles
                    Printf("  MINUIT PRIMARY OUTPUT TO UNIT %d", fIsyswr);
                    return;
                case 1250: // show version
                    Printf(" THIS IS MINUIT VERSION:%s", fCvrsn);
                    return;
                case 1270: // show nodebug, debug
                    for (id = 0; id <= 6; ++id) {
                        copt = "OFF";
                        if (fIdbg[id] >= 1) copt = "ON ";
                        Printf("          DEBUG OPTION %3d IS %3s :%s", id, copt, SET_CDBOPT[id]);
                    }
                    if (!fLrepor) mnwarn("D", "SHO", "SHO");
                    return;
                case 1290: // show show
                    ckind = "SHOW";
                    L = 2100;
                    continue;
                case 1300: // show set
                    ckind = "SET ";
                    L = 2100;
                    continue;
                case 1900: // UNKNOWN COMMAND
                    Printf(" THE COMMAND:%10s IS UNKNOWN.", fCword);
                    L = 2100;
                    continue;
                case 2000: // HELP SHOW, HELP SET, SHOW SET, or SHOW SHOW
                    ckind = "SET ";
                    ctemp2 = TStr.sub(fCword, 3, 7);
                    if (TStr.strcmp(ctemp2, "SHO") != 0) ckind = "SHOW";
                    // fall through
                case 2100:
                    Printf(" THE FORMAT OF THE %4s COMMAND IS:", ckind);
                    Printf(" %s xxx    [numerical arguments if any]", ckind);
                    Printf(" WHERE xxx MAY BE ONE OF THE FOLLOWING:");
                    for (kk = 1; kk <= nname; ++kk) {
                        Printf(" %s", cname[kk - 1]);
                    }
                    return;
                case 3000: // ILLEGAL COMMAND
                    Printf(" ABOVE COMMAND IS ILLEGAL.   IGNORED");
                    return;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    /** Minimization by the simplex method of Nelder and Mead (Comp. J. 7, 308 (1965)). */
    public void mnsimp() {
        final double alpha = 1;
        final double beta = .5;
        final double gamma = 2;
        final double rhomin = 4;
        final double rhomax = 8;
        final double[] dxdi = {0};
        double dmin_, yrho = 0, f = 0, ynpp1, aming, ypbar = 0;
        double bestx, ystar = 0, y1, y2, ystst = 0, pb, wg;
        double absmin, rho, sig2 = 0, rho1, rho2;
        int npfn, i, j, k, jhold, ncycl = 0, nparx;
        int nparp1, kg, nf, ns;
        final int[] jh = {0};
        final int[] jl = {0};

        if (fNpar <= 0) return;
        if (fAmin == fUndefi) mnamin();
        fCfrom = "SIMPLEX ";
        fNfcnfr = fNfcn;
        fCstatu = "UNCHANGED ";
        npfn = fNfcn;
        nparp1 = fNpar + 1;
        nparx = fNpar;
        rho1 = alpha + 1;
        rho2 = rho1 + alpha * gamma;
        wg = 1 / (double) fNpar;
        if (fISW[4] >= 0) {
            Printf(" START SIMPLEX MINIMIZATION.    CONVERGENCE WHEN EDM .LT. %g", fEpsi);
        }
        for (i = 1; i <= fNpar; ++i) {
            fDirin[i - 1] = fWerr[i - 1];
            mndxdi(fX[i - 1], i - 1, dxdi);
            if (dxdi[0] != 0) fDirin[i - 1] = fWerr[i - 1] / dxdi[0];
            dmin_ = fEpsma2 * Math.abs(fX[i - 1]);
            if (fDirin[i - 1] < dmin_) fDirin[i - 1] = dmin_;
        }
        int L = 1;
        while (true) {
            switch (L) {
                case 1: // choose the initial simplex using single-parameter searches
                    ynpp1 = fAmin;
                    jl[0] = nparp1;
                    fSIMPy[nparp1 - 1] = fAmin;
                    absmin = fAmin;
                    for (i = 1; i <= fNpar; ++i) {
                        aming = fAmin;
                        fPbar[i - 1] = fX[i - 1];
                        bestx = fX[i - 1];
                        kg = 0;
                        ns = 0;
                        nf = 0;
                        while (true) {
                            // L4
                            fX[i - 1] = bestx + fDirin[i - 1];
                            mninex(fX);
                            f = eval(nparx, f, 4);
                            ++fNfcn;
                            if (!(f <= aming)) {
                                // failure
                                if (kg == 1) break;
                                kg = -1;
                                ++nf;
                                fDirin[i - 1] *= -.4;
                                if (nf < 3) continue;
                                ns = 6;
                            }
                            // L6: success
                            bestx = fX[i - 1];
                            fDirin[i - 1] *= 3;
                            aming = f;
                            fCstatu = "PROGRESS  ";
                            kg = 1;
                            ++ns;
                            if (ns < 6) continue;
                            break;
                        }
                        // L8: local minimum found in ith direction
                        fSIMPy[i - 1] = aming;
                        if (aming < absmin) jl[0] = i;
                        if (aming < absmin) absmin = aming;
                        fX[i - 1] = bestx;
                        for (k = 1; k <= fNpar; ++k) {
                            fP[k + i * fMaxpar - fMaxpar - 1] = fX[k - 1];
                        }
                    }
                    jh[0] = nparp1;
                    fAmin = fSIMPy[jl[0] - 1];
                    mnrazz(ynpp1, fPbar, fSIMPy, jh, jl);
                    for (i = 1; i <= fNpar; ++i) {
                        fX[i - 1] = fP[i + jl[0] * fMaxpar - fMaxpar - 1];
                    }
                    mninex(fX);
                    fCstatu = "PROGRESS  ";
                    if (fISW[4] >= 1) mnprin(5, fAmin);
                    fEDM = fBigedm;
                    sig2 = fEDM;
                    ncycl = 0;
                    // fall through
                case 50: // start main loop
                    if (sig2 < fEpsi && fEDM < fEpsi) {
                        L = 76;
                        continue;
                    }
                    sig2 = fEDM;
                    if (fNfcn - npfn > fNfcnmx) {
                        L = 78;
                        continue;
                    }
                    // calculate new point * by reflection
                    for (i = 1; i <= fNpar; ++i) {
                        pb = 0;
                        for (j = 1; j <= nparp1; ++j) {
                            pb += wg * fP[i + j * fMaxpar - fMaxpar - 1];
                        }
                        fPbar[i - 1] = pb - wg * fP[i + jh[0] * fMaxpar - fMaxpar - 1];
                        fPstar[i - 1] = (alpha + 1) * fPbar[i - 1] - alpha * fP[i + jh[0] * fMaxpar - fMaxpar - 1];
                    }
                    mninex(fPstar);
                    ystar = eval(nparx, ystar, 4);
                    ++fNfcn;
                    if (ystar >= fAmin) {
                        L = 70;
                        continue;
                    }
                    // point * better than jl, calculate new point **
                    for (i = 1; i <= fNpar; ++i) {
                        fPstst[i - 1] = gamma * fPstar[i - 1] + (1 - gamma) * fPbar[i - 1];
                    }
                    mninex(fPstst);
                    ystst = eval(nparx, ystst, 4);
                    ++fNfcn;
                    // try a parabola through ph, pstar, pstst.  min = prho
                    y1 = (ystar - fSIMPy[jh[0] - 1]) * rho2;
                    y2 = (ystst - fSIMPy[jh[0] - 1]) * rho1;
                    rho = (rho2 * y1 - rho1 * y2) * .5 / (y1 - y2);
                    if (rho < rhomin) {
                        L = 66;
                        continue;
                    }
                    if (rho > rhomax) rho = rhomax;
                    for (i = 1; i <= fNpar; ++i) {
                        fPrho[i - 1] = rho * fPbar[i - 1] + (1 - rho) * fP[i + jh[0] * fMaxpar - fMaxpar - 1];
                    }
                    mninex(fPrho);
                    yrho = eval(nparx, yrho, 4);
                    ++fNfcn;
                    if (yrho < fSIMPy[jl[0] - 1] && yrho < ystst) {
                        L = 65;
                        continue;
                    }
                    if (ystst < fSIMPy[jl[0] - 1]) {
                        L = 67;
                        continue;
                    }
                    if (yrho > fSIMPy[jl[0] - 1]) {
                        L = 66;
                        continue;
                    }
                    // fall through
                case 65: // accept minimum point of parabola, PRHO
                    mnrazz(yrho, fPrho, fSIMPy, jh, jl);
                    L = 68;
                    continue;
                case 66:
                    if (ystst < fSIMPy[jl[0] - 1]) {
                        L = 67;
                        continue;
                    }
                    mnrazz(ystar, fPstar, fSIMPy, jh, jl);
                    L = 68;
                    continue;
                case 67:
                    mnrazz(ystst, fPstst, fSIMPy, jh, jl);
                    // fall through
                case 68:
                    ++ncycl;
                    if (fISW[4] < 2) {
                        L = 50;
                        continue;
                    }
                    if (fISW[4] >= 3 || ncycl % 10 == 0) {
                        mnprin(5, fAmin);
                    }
                    L = 50;
                    continue;
                case 70: // point * is not as good as jl
                    if (ystar >= fSIMPy[jh[0] - 1]) {
                        L = 73;
                        continue;
                    }
                    jhold = jh[0];
                    mnrazz(ystar, fPstar, fSIMPy, jh, jl);
                    if (jhold != jh[0]) {
                        L = 50;
                        continue;
                    }
                    // fall through
                case 73: // calculate new point **
                    for (i = 1; i <= fNpar; ++i) {
                        fPstst[i - 1] = beta * fP[i + jh[0] * fMaxpar - fMaxpar - 1] + (1 - beta) * fPbar[i - 1];
                    }
                    mninex(fPstst);
                    ystst = eval(nparx, ystst, 4);
                    ++fNfcn;
                    if (ystst > fSIMPy[jh[0] - 1]) {
                        L = 1;
                        continue;
                    }
                    // point ** is better than jh
                    if (ystst < fAmin) {
                        L = 67;
                        continue;
                    }
                    mnrazz(ystst, fPstst, fSIMPy, jh, jl);
                    L = 50;
                    continue;
                case 76: // end main loop
                    if (fISW[4] >= 0) {
                        Printf(" SIMPLEX MINIMIZATION HAS CONVERGED.");
                    }
                    fISW[3] = 1;
                    L = 80;
                    continue;
                case 78:
                    if (fISW[4] >= 0) {
                        Printf(" SIMPLEX TERMINATES WITHOUT CONVERGENCE.");
                    }
                    fCstatu = "CALL LIMIT";
                    fISW[3] = -1;
                    fISW[0] = 1;
                    // fall through
                case 80:
                    for (i = 1; i <= fNpar; ++i) {
                        pb = 0;
                        for (j = 1; j <= nparp1; ++j) {
                            pb += wg * fP[i + j * fMaxpar - fMaxpar - 1];
                        }
                        fPbar[i - 1] = pb - wg * fP[i + jh[0] * fMaxpar - fMaxpar - 1];
                    }
                    mninex(fPbar);
                    ypbar = eval(nparx, ypbar, 4);
                    ++fNfcn;
                    if (ypbar < fAmin) mnrazz(ypbar, fPbar, fSIMPy, jh, jl);
                    mninex(fX);
                    if (fNfcnmx + npfn - fNfcn < fNpar * 3) {
                        L = 90;
                        continue;
                    }
                    if (fEDM > fEpsi * 2) {
                        L = 1;
                        continue;
                    }
                    // fall through
                case 90:
                    if (fISW[4] >= 0) mnprin(5, fAmin);
                    return;
                default:
                    throw new IllegalStateException();
            }
        }
    }
}

// The part of ROOT that math/minuit/src/TMinuit.cxx uses, with ROOT's own
// definitions (core/base TString, TMathBase, TError; core/base TROOT and
// TPluginManager reduced to what TMinuit calls), so that root-master's
// TMinuit.cxx compiles unchanged without ROOT. Cross-checked against ROOT
// 6.36 itself: the probes print the same text with this TMinuit and ROOT's.
#pragma once

#include <cctype>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

typedef int Int_t;
typedef unsigned int UInt_t;
typedef float Float_t;
typedef double Double_t;
typedef long long Long64_t;
typedef unsigned long long ULong64_t;
typedef bool Bool_t;
typedef char Char_t;
typedef short Short_t;
typedef int Ssiz_t;
typedef intptr_t Longptr_t;
typedef const char Option_t;
constexpr Bool_t kTRUE = true;
constexpr Bool_t kFALSE = false;
constexpr Ssiz_t kNPOS = ~(Ssiz_t)0;

#define R__EXTERN extern
#define R__LOCKGUARD(m) do { } while (0)
#define ClassImp(name)

/* ---- printf-like formatting: vsnprintf of the whole text -------------------------------------- */

inline std::string tmroot_vformat(const char *fmt, va_list ap)
{
   va_list ap2;
   va_copy(ap2, ap);
   char small[2048];
   int n = vsnprintf(small, sizeof small, fmt, ap2);
   va_end(ap2);
   if (n < 0) return std::string();
   if (n < (int)sizeof small) return std::string(small, n);
   std::vector<char> big(n + 1);
   vsnprintf(big.data(), big.size(), fmt, ap);
   return std::string(big.data(), n);
}

/* ---- TString ---------------------------------------------------------------------------------- */

class TString;

class TSubString {
   friend class TString;
   TString &fStr;
   Ssiz_t fBegin;
   Ssiz_t fExtent;
   TSubString(const TString &s, Ssiz_t start, Ssiz_t len) : fStr((TString &)s), fBegin(start), fExtent(len) {}

public:
   Bool_t IsNull() const { return fBegin == kNPOS; }
   Ssiz_t Length() const { return fExtent; }
   Ssiz_t Start() const { return fBegin; }
   const char *Data() const;
   const TString &String() const { return fStr; }
   // TSubString::operator=(const TString &): replaces the characters, nothing when null
   TSubString &operator=(const TString &s);
};

class TString {
   std::string fS;

public:
   TString() {}
   TString(const char *c) : fS(c ? c : "") {}
   TString(const std::string &s) : fS(s) {}
   TString(char c) : fS(1, c) {}
   TString(const TSubString &ss) : fS(ss.IsNull() ? std::string() : std::string(ss.Data(), ss.Length())) {}
   TString &operator=(const char *c) { fS = c ? c : ""; return *this; }
   TString &operator=(const TSubString &ss)
   {
      fS = ss.IsNull() ? std::string() : std::string(ss.Data(), ss.Length());
      return *this;
   }

   const char *Data() const { return fS.c_str(); }
   Ssiz_t Length() const { return (Ssiz_t)fS.size(); }
   Bool_t IsNull() const { return fS.empty(); }
   operator const char *() const { return fS.c_str(); }
   char &operator[](Ssiz_t i) { return fS[i]; }
   char operator[](Ssiz_t i) const { return fS.c_str()[i]; }

   // TString::operator()(Ssiz_t start, Ssiz_t len)
   TSubString operator()(Ssiz_t start, Ssiz_t len) const
   {
      if (start < Length() && len > 0) {
         if (start + len > Length()) len = Length() - start;
      } else {
         start = kNPOS;
         len = 0;
      }
      return TSubString(*this, start, len);
   }

   void ToUpper()
   {
      for (auto &c : fS) c = (char)toupper((unsigned char)c);
   }
   // Resize: truncate or add blanks as necessary
   void Resize(Ssiz_t n)
   {
      if (n < Length()) fS.resize(n);
      else fS.append(n - Length(), ' ');
   }
   Bool_t Contains(const char *pat) const { return fS.find(pat) != std::string::npos; }
   TString &Replace(Ssiz_t pos, Ssiz_t n, const char *s, Ssiz_t ns)
   {
      fS.replace(pos, n, s, ns);
      return *this;
   }

   TString &operator+=(const char *c) { fS += c; return *this; }
   TString &operator+=(const TString &t) { fS += t.fS; return *this; }

   void Form(const char *fmt, ...)
   {
      va_list ap;
      va_start(ap, fmt);
      fS = tmroot_vformat(fmt, ap);
      va_end(ap);
   }
   static TString Format(const char *fmt, ...)
   {
      va_list ap;
      va_start(ap, fmt);
      TString t(tmroot_vformat(fmt, ap));
      va_end(ap);
      return t;
   }
};

inline const char *TSubString::Data() const { return fStr.Data() + fBegin; }

inline TSubString &TSubString::operator=(const TString &s)
{
   if (!IsNull()) fStr.Replace(fBegin, fExtent, s.Data(), s.Length());
   return *this;
}

inline TString operator+(const TString &a, const TString &b) { TString t(a); t += b; return t; }
inline TString operator+(const TString &a, const char *b) { TString t(a); t += b; return t; }
inline TString operator+(const char *a, const TString &b) { TString t(a); t += b; return t; }

inline Bool_t operator==(const TString &s1, const TString &s2)
{
   return s1.Length() == s2.Length() && !memcmp(s1.Data(), s2.Data(), s1.Length());
}
inline Bool_t operator==(const TString &s1, const char *s2)
{
   if (!s2) return kFALSE;
   return s1.Length() == (Ssiz_t)strlen(s2) && !memcmp(s1.Data(), s2, s1.Length());
}
inline Bool_t operator!=(const TString &s1, const TString &s2) { return !(s1 == s2); }
inline Bool_t operator!=(const TString &s1, const char *s2) { return !(s1 == s2); }

// TSubString comparisons, as core/base/src/TString.cxx writes them
inline Bool_t operator==(const TSubString &ss, const char *cs)
{
   if (ss.IsNull()) return *cs == '\0'; // two null strings compare equal
   const char *data = ss.Data();
   Ssiz_t i;
   for (i = 0; cs[i]; ++i)
      if (cs[i] != data[i] || i == ss.Length()) return kFALSE;
   return (i == ss.Length());
}
inline Bool_t operator==(const TSubString &ss, const TString &s)
{
   if (ss.IsNull()) return s.IsNull(); // two null strings compare equal
   if (ss.Length() != s.Length()) return kFALSE;
   return !memcmp(ss.Data(), s.Data(), ss.Length());
}
inline Bool_t operator!=(const TSubString &ss, const char *cs) { return !(ss == cs); }
inline Bool_t operator!=(const TSubString &ss, const TString &s) { return !(ss == s); }

/* ---- Printf, Form ------------------------------------------------------------------------------ */

// Printf: the text, a new line, stdout flushed (gPrintViaErrorHandler false)
inline void Printf(const char *fmt, ...)
{
   va_list ap;
   va_start(ap, fmt);
   std::string b = tmroot_vformat(fmt, ap);
   va_end(ap);
   printf("%s\n", b.c_str());
   fflush(stdout);
}

// Form: a formatted text in a circular set of buffers
inline char *Form(const char *fmt, ...)
{
   static std::string bufs[16];
   static int next = 0;
   va_list ap;
   va_start(ap, fmt);
   std::string &b = bufs[next];
   next = (next + 1) % 16;
   b = tmroot_vformat(fmt, ap);
   va_end(ap);
   return &b[0];
}

/* ---- TMath (TMathBase.h, TMath.h) -------------------------------------------------------------- */

namespace TMath {
constexpr Double_t Pi() { return 3.14159265358979323846; }
inline Short_t Abs(Short_t d) { return (d >= 0) ? d : Short_t(-d); }
inline Int_t Abs(Int_t d) { return std::abs(d); }
inline Double_t Abs(Double_t d) { return std::abs(d); }
inline Int_t Min(Int_t a, Int_t b) { return a <= b ? a : b; }
inline Double_t Min(Double_t a, Double_t b) { return a <= b ? a : b; }
inline Int_t Max(Int_t a, Int_t b) { return a >= b ? a : b; }
inline Double_t Max(Double_t a, Double_t b) { return a >= b ? a : b; }
inline Double_t Sqrt(Double_t x) { return sqrt(x); }
inline Double_t Sin(Double_t x) { return sin(x); }
inline Double_t Cos(Double_t x) { return cos(x); }
inline Double_t ATan(Double_t x) { return atan(x); }
inline Double_t ASin(Double_t x)
{
   if (x < -1.) return -TMath::Pi() / 2;
   if (x > 1.) return TMath::Pi() / 2;
   return asin(x);
}
inline Double_t Log(Double_t x) { return log(x); }
inline Double_t Log10(Double_t x) { return log10(x); }
inline Double_t Power(Double_t x, Int_t y) { return std::pow(x, y); }
inline Double_t Power(Double_t x, Double_t y) { return pow(x, y); }
} // namespace TMath

/* ---- TClass, TObject, TNamed ------------------------------------------------------------------- */

class TClass {
public:
   enum ENewType { kRealNew = 0, kClassNew, kDummyNew };
   ENewType IsCallingNew() const { return kRealNew; }
};

#define ClassDefOverride(name, id)                                  \
public:                                                             \
   static TClass *Class()                                          \
   {                                                                \
      static TClass c;                                              \
      return &c;                                                    \
   }                                                                \
   const char *ClassName() const override { return #name; }        \
                                                                    \
private:

class TObject {
   void DoError(const char *type, const char *location, const char *fmt, va_list ap) const
   {
      std::string msg = tmroot_vformat(fmt, ap);
      fflush(stdout);
      fprintf(stderr, "%s in <%s::%s>: %s\n", type, ClassName(), location, msg.c_str());
      fflush(stderr);
   }

public:
   virtual ~TObject() {}
   virtual const char *ClassName() const { return "TObject"; }
   virtual TObject *Clone(const char * = "") const { return nullptr; }
   void Warning(const char *location, const char *fmt, ...) const
   {
      va_list ap;
      va_start(ap, fmt);
      DoError("Warning", location, fmt, ap);
      va_end(ap);
   }
   void Error(const char *location, const char *fmt, ...) const
   {
      va_list ap;
      va_start(ap, fmt);
      DoError("Error", location, fmt, ap);
      va_end(ap);
   }
};

class TNamed : public TObject {
   TString fName, fTitle;

public:
   TNamed(const char *name, const char *title) : fName(name), fTitle(title) {}
   TNamed(const TNamed &o) : TObject(o), fName(o.fName), fTitle(o.fTitle) {}
   const char *GetName() const { return fName.Data(); }
};

/* ---- TMethodCall: interpreted FCNs do not exist here ------------------------------------------- */

class TMethodCall {
public:
   void SetParamPtrs(void *, Int_t = -1) {}
   void Execute(Double_t &) {}
};

/* ---- the "TMinuitGraph" plugin: a TGraph(n, x, y) ---------------------------------------------- */

class TMinuitGraph : public TObject {
public:
   std::vector<Double_t> fX, fY;
   TMinuitGraph(Int_t n, const Double_t *x, const Double_t *y)
   {
      if (n > 0 && x && y) {
         fX.assign(x, x + n);
         fY.assign(y, y + n);
      }
   }
   Int_t GetN() const { return (Int_t)fX.size(); }
   const char *ClassName() const override { return "TMinuitGraph"; }
};

class TPluginHandler {
public:
   Int_t LoadPlugin() { return 0; }
   Longptr_t ExecPlugin(int, Int_t n, Double_t *x, Double_t *y) { return (Longptr_t) new TMinuitGraph(n, x, y); }
};

class TPluginManager {
   TPluginHandler fGraph;

public:
   TPluginHandler *FindHandler(const char *base) { return strcmp(base, "TMinuitGraph") == 0 ? &fGraph : nullptr; }
};

class TList {
public:
   void Add(TObject *) {}
   void Remove(TObject *) {}
};

class TROOT {
   TList fSpecials;
   TPluginManager fPlugins;

public:
   TList *GetListOfSpecials() { return &fSpecials; }
   TPluginManager *GetPluginManager() { return &fPlugins; }
};

inline TROOT *tmroot_groot()
{
   static TROOT r;
   return &r;
}
#define gROOT (tmroot_groot())

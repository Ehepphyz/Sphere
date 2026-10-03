// sphere_view.hpp -- what Sphere's ROOT browser asks the engine.
//
// An inventory of what the session holds (canvases, objects in memory, open
// files and their keys), and any object or canvas as the numbers a viewer
// draws from, in plain JSON. Built with ROOT's own accessors rather than by
// serializing the classes: a profile gives its means, a function the curve
// it paints, a stack its members, a canvas its pads with the options each
// object was drawn with. Sphere declares this file in its engine; a macro can
// include it too.
#pragma once

#include "TROOT.h"
#include "TCanvas.h"
#include "TColor.h"
#include "TDirectory.h"
#include "TFile.h"
#include "TF1.h"
#include "TF2.h"
#include "TF3.h"
#include "TGraph.h"
#include "TGraph2D.h"
#include "TGraphAsymmErrors.h"
#include "TGraphErrors.h"
#include "TH1.h"
#include "THStack.h"
#include "TKey.h"
#include "TLatex.h"
#include "TLeaf.h"
#include "TBranch.h"
#include "TLegend.h"
#include "TLegendEntry.h"
#include "TLine.h"
#include "TList.h"
#include "TMultiGraph.h"
#include "TPave.h"
#include "TPaveText.h"
#include "TPaveLabel.h"
#include "TPaveStats.h"
#include "TStyle.h"
#include "TText.h"
#include "TTree.h"
#include "TVirtualPad.h"

#include <cmath>
#include <cstdio>
#include <string>

namespace SphereView {

/** Past this many cells a histogram is refused rather than sent. */
constexpr long kMaxCells = 4000000;

inline std::string Q(const char *text) {
  std::string out = "\"";
  for (const char *p = text != nullptr ? text : ""; *p != '\0'; ++p) {
    const unsigned char c = static_cast<unsigned char>(*p);
    switch (c) {
    case '"': out += "\\\""; break;
    case '\\': out += "\\\\"; break;
    case '\n': out += "\\n"; break;
    case '\r': out += "\\r"; break;
    case '\t': out += "\\t"; break;
    default:
      if (c < 0x20) {
        char buffer[8];
        std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
        out += buffer;
      } else {
        out += static_cast<char>(c);
      }
    }
  }
  return out + "\"";
}

inline std::string Q(const std::string &text) { return Q(text.c_str()); }

inline std::string N(double value) {
  if (!std::isfinite(value)) return "null";
  char buffer[32];
  std::snprintf(buffer, sizeof(buffer), "%.9g", value);
  return buffer;
}

inline std::string B(bool value) { return value ? "true" : "false"; }

/** A ROOT colour index as #rrggbb, which is what the viewer paints with. */
inline std::string Color(Int_t index) {
  TColor *color = gROOT->GetColor(index);
  if (color == nullptr) return "null";
  return Q(color->AsHexString());
}

/** The palette COLZ, LEGO2 and SURF1 take their colours from. */
inline std::string Palette() {
  std::string out = "[";
  const Int_t n = gStyle->GetNumberOfColors();
  for (Int_t i = 0; i < n; ++i) {
    if (i > 0) out += ",";
    out += Color(gStyle->GetColorPalette(i));
  }
  return out + "]";
}

inline std::string Attributes(TObject *object) {
  std::string out;
  if (auto *line = dynamic_cast<TAttLine *>(object)) {
    out += ",\"lc\":" + Color(line->GetLineColor()) + ",\"lw\":" + N(line->GetLineWidth()) +
           ",\"ls\":" + N(line->GetLineStyle());
  }
  if (auto *fill = dynamic_cast<TAttFill *>(object)) {
    out += ",\"fc\":" + Color(fill->GetFillColor()) + ",\"fs\":" + N(fill->GetFillStyle());
  }
  if (auto *marker = dynamic_cast<TAttMarker *>(object)) {
    out += ",\"mc\":" + Color(marker->GetMarkerColor()) + ",\"mst\":" + N(marker->GetMarkerStyle()) +
           ",\"msz\":" + N(marker->GetMarkerSize());
  }
  return out;
}

inline std::string Head(const char *kind, TObject *object, const char *option) {
  return std::string("{\"k\":") + Q(kind) + ",\"c\":" + Q(object->ClassName()) + ",\"n\":" +
         Q(object->GetName()) + ",\"t\":" + Q(object->GetTitle()) + ",\"o\":" + Q(option) +
         Attributes(object);
}

inline std::string Axis(TAxis *axis) {
  std::string out = "{\"n\":" + std::to_string(axis->GetNbins()) + ",\"lo\":" + N(axis->GetXmin()) +
                    ",\"hi\":" + N(axis->GetXmax()) + ",\"t\":" + Q(axis->GetTitle());
  if (axis->GetXbins() != nullptr && axis->GetXbins()->GetSize() > 0) {
    out += ",\"e\":[";
    for (Int_t i = 1; i <= axis->GetNbins() + 1; ++i) {
      if (i > 1) out += ",";
      out += N(axis->GetBinLowEdge(i));
    }
    out += "]";
  }
  if (axis->TestBit(TAxis::kAxisRange)) {
    out += ",\"first\":" + std::to_string(axis->GetFirst()) + ",\"last\":" + std::to_string(axis->GetLast());
  }
  if (axis->GetLabels() != nullptr) {
    out += ",\"labels\":[";
    for (Int_t i = 1; i <= axis->GetNbins(); ++i) {
      if (i > 1) out += ",";
      out += Q(axis->GetBinLabel(i));
    }
    out += "]";
  }
  return out + "}";
}

inline std::string Item(TObject *object, const char *option);

/** Any histogram, bins in the order x fastest; a function as the histogram it paints. */
inline std::string Hist(TH1 *h, const char *option, const char *formula = nullptr) {
  const int dim = h->GetDimension();
  const int nx = h->GetNbinsX();
  const int ny = dim > 1 ? h->GetNbinsY() : 1;
  const int nz = dim > 2 ? h->GetNbinsZ() : 1;
  if (static_cast<long>(nx) * ny * nz > kMaxCells) {
    return Head("big", h, option) + ",\"cells\":" + N(static_cast<double>(nx) * ny * nz) + "}";
  }
  std::string out = Head(dim == 1 ? "h1" : dim == 2 ? "h2" : "h3", h, option);
  out += ",\"x\":" + Axis(h->GetXaxis());
  if (dim > 1) out += ",\"y\":" + Axis(h->GetYaxis());
  if (dim > 2) out += ",\"z\":" + Axis(h->GetZaxis());
  else out += ",\"zt\":" + Q(h->GetZaxis()->GetTitle());
  if (dim == 1) out += ",\"yt\":" + Q(h->GetYaxis()->GetTitle());
  out += ",\"v\":[";
  bool first = true;
  for (int iz = 1; iz <= nz; ++iz) {
    for (int iy = 1; iy <= ny; ++iy) {
      for (int ix = 1; ix <= nx; ++ix) {
        if (!first) out += ",";
        first = false;
        out += N(dim == 1 ? h->GetBinContent(ix) : dim == 2 ? h->GetBinContent(ix, iy) : h->GetBinContent(ix, iy, iz));
      }
    }
  }
  out += "]";
  if (dim == 1) {
    out += ",\"err\":[";
    for (int ix = 1; ix <= nx; ++ix) {
      if (ix > 1) out += ",";
      out += N(h->GetBinError(ix));
    }
    out += "]";
  }
  if (h->GetMinimumStored() != -1111) out += ",\"min\":" + N(h->GetMinimumStored());
  if (h->GetMaximumStored() != -1111) out += ",\"max\":" + N(h->GetMaximumStored());
  out += ",\"stats\":" + B(!h->TestBit(TH1::kNoStats) && formula == nullptr);
  out += ",\"entries\":" + N(h->GetEntries()) + ",\"mean\":" + N(h->GetMean(1)) + ",\"std\":" + N(h->GetStdDev(1));
  if (dim > 1) out += ",\"meany\":" + N(h->GetMean(2)) + ",\"stdy\":" + N(h->GetStdDev(2));
  if (formula != nullptr) out += ",\"fn\":" + Q(formula);
  // A fit lives in the histogram's own list of functions, not in the pad.
  if (dim == 1 && formula == nullptr && h->GetListOfFunctions() != nullptr) {
    std::string fits;
    TIter next(h->GetListOfFunctions());
    while (TObject *o = next()) {
      auto *f = dynamic_cast<TF1 *>(o);
      if (f == nullptr || dynamic_cast<TF2 *>(o) != nullptr || f->TestBit(TF1::kNotDraw)) continue;
      fits += (fits.empty() ? "" : ",") + Item(f, "C");
    }
    if (!fits.empty()) out += ",\"fits\":[" + fits + "]";
  }
  return out + "}";
}

inline std::string Graph(TGraph *g, const char *option) {
  std::string out = Head("g", g, option);
  const Int_t n = g->GetN();
  auto series = [&](const char *key, const Double_t *values) {
    if (values == nullptr) return;
    out += std::string(",\"") + key + "\":[";
    for (Int_t i = 0; i < n; ++i) {
      if (i > 0) out += ",";
      out += N(values[i]);
    }
    out += "]";
  };
  series("x", g->GetX());
  series("y", g->GetY());
  if (auto *asym = dynamic_cast<TGraphAsymmErrors *>(g)) {
    series("exl", asym->GetEXlow());
    series("exh", asym->GetEXhigh());
    series("eyl", asym->GetEYlow());
    series("eyh", asym->GetEYhigh());
  } else if (auto *errors = dynamic_cast<TGraphErrors *>(g)) {
    series("exl", errors->GetEX());
    series("exh", errors->GetEX());
    series("eyl", errors->GetEY());
    series("eyh", errors->GetEY());
  }
  if (TH1 *frame = g->GetHistogram()) {
    out += ",\"xt\":" + Q(frame->GetXaxis()->GetTitle()) + ",\"yt\":" + Q(frame->GetYaxis()->GetTitle());
  }
  if (g->GetMinimum() != -1111) out += ",\"min\":" + N(g->GetMinimum());
  if (g->GetMaximum() != -1111) out += ",\"max\":" + N(g->GetMaximum());
  return out + "}";
}

inline std::string Graph2D(TGraph2D *g, const char *option) {
  std::string out = Head("g2", g, option);
  const Int_t n = g->GetN();
  auto series = [&](const char *key, const Double_t *values) {
    out += std::string(",\"") + key + "\":[";
    for (Int_t i = 0; i < n; ++i) {
      if (i > 0) out += ",";
      out += N(values[i]);
    }
    out += "]";
  };
  series("x", g->GetX());
  series("y", g->GetY());
  series("z", g->GetZ());
  out += ",\"xt\":" + Q(g->GetXaxis() ? g->GetXaxis()->GetTitle() : "") +
         ",\"yt\":" + Q(g->GetYaxis() ? g->GetYaxis()->GetTitle() : "") +
         ",\"zt\":" + Q(g->GetZaxis() ? g->GetZaxis()->GetTitle() : "");
  return out + "}";
}

inline std::string Text(TText *text, const char *option) {
  return Head("text", text, option) + ",\"x\":" + N(text->GetX()) + ",\"y\":" + N(text->GetY()) +
         ",\"ndc\":" + B(text->TestBit(TText::kTextNDC)) + ",\"s\":" + Q(text->GetTitle()) +
         ",\"sz\":" + N(text->GetTextSize()) + ",\"tc\":" + Color(text->GetTextColor()) +
         ",\"al\":" + N(text->GetTextAlign()) + ",\"an\":" + N(text->GetTextAngle()) + "}";
}

inline std::string Pave(TPave *pave, const char *option) {
  std::string out = Head(dynamic_cast<TLegend *>(pave) ? "legend" : "pave", pave, option);
  out += ",\"x1\":" + N(pave->GetX1NDC()) + ",\"y1\":" + N(pave->GetY1NDC()) + ",\"x2\":" + N(pave->GetX2NDC()) +
         ",\"y2\":" + N(pave->GetY2NDC()) + ",\"bs\":" + N(pave->GetBorderSize());
  out += ",\"lines\":[";
  bool first = true;
  if (auto *label = dynamic_cast<TPaveLabel *>(pave)) {
    out += "{\"s\":" + Q(label->GetLabel()) + ",\"tc\":" + Color(label->GetTextColor()) + "}";
  } else if (auto *legend = dynamic_cast<TLegend *>(pave)) {
    TIter next(legend->GetListOfPrimitives());
    while (auto *entry = dynamic_cast<TLegendEntry *>(next())) {
      if (!first) out += ",";
      first = false;
      out += "{\"s\":" + Q(entry->GetLabel()) + ",\"o\":" + Q(entry->GetOption()) + Attributes(entry) + "}";
    }
  } else if (auto *paveText = dynamic_cast<TPaveText *>(pave)) {
    TIter next(paveText->GetListOfLines());
    while (TObject *line = next()) {
      auto *text = dynamic_cast<TText *>(line);
      if (text == nullptr) continue;
      if (!first) out += ",";
      first = false;
      out += "{\"s\":" + Q(text->GetTitle()) + ",\"tc\":" + Color(text->GetTextColor()) + "}";
    }
  }
  return out + "]}";
}

inline std::string Group(const char *kind, TObject *owner, TList *members, const char *option) {
  std::string out = Head(kind, owner, option) + ",\"items\":[";
  bool first = true;
  if (members != nullptr) {
    for (TObjLink *link = members->FirstLink(); link != nullptr; link = link->Next()) {
      if (!first) out += ",";
      first = false;
      out += Item(link->GetObject(), link->GetOption());
    }
  }
  return out + "]}";
}

/** One object of a pad, by what it is; what the viewer cannot draw is named. */
inline std::string Item(TObject *object, const char *option) {
  if (object == nullptr) return "{\"k\":\"none\"}";
  if (auto *f3 = dynamic_cast<TF3 *>(object)) return Head("other", f3, option) + "}";
  if (auto *f = dynamic_cast<TF1 *>(object)) {
    TH1 *painted = f->GetHistogram();
    if (painted == nullptr) return Head("other", f, option) + "}";
    std::string json = Hist(painted, option, f->GetExpFormula().Data());
    // Named after the function, not after the histogram it fills.
    const std::string was = std::string("\"n\":") + Q(painted->GetName());
    const std::size_t at = json.find(was);
    if (at != std::string::npos) json.replace(at, was.size(), std::string("\"n\":") + Q(f->GetName()));
    return json;
  }
  if (auto *h = dynamic_cast<TH1 *>(object)) return Hist(h, option);
  if (auto *g2 = dynamic_cast<TGraph2D *>(object)) return Graph2D(g2, option);
  if (auto *g = dynamic_cast<TGraph *>(object)) return Graph(g, option);
  if (auto *stack = dynamic_cast<THStack *>(object)) return Group("stack", stack, stack->GetHists(), option);
  if (auto *multi = dynamic_cast<TMultiGraph *>(object)) return Group("mgraph", multi, multi->GetListOfGraphs(), option);
  if (dynamic_cast<TPaveStats *>(object) != nullptr) return Head("stats", object, option) + "}";
  if (auto *pave = dynamic_cast<TPave *>(object)) return Pave(pave, option);
  if (auto *text = dynamic_cast<TText *>(object)) return Text(text, option);
  if (auto *line = dynamic_cast<TLine *>(object)) {
    return Head("line", line, option) + ",\"x1\":" + N(line->GetX1()) + ",\"y1\":" + N(line->GetY1()) +
           ",\"x2\":" + N(line->GetX2()) + ",\"y2\":" + N(line->GetY2()) +
           ",\"ndc\":" + B(line->TestBit(TLine::kLineNDC)) + "}";
  }
  return Head("other", object, option) + "}";
}

/** A pad and what it holds, sub-pads placed in their mother's fractions. */
inline std::string Pad(TVirtualPad *pad) {
  std::string out = "{\"n\":" + Q(pad->GetName()) + ",\"t\":" + Q(pad->GetTitle()) +
                    ",\"px\":" + N(pad->GetXlowNDC()) + ",\"py\":" + N(pad->GetYlowNDC()) +
                    ",\"pw\":" + N(pad->GetWNDC()) + ",\"ph\":" + N(pad->GetHNDC()) +
                    ",\"logx\":" + B(pad->GetLogx()) + ",\"logy\":" + B(pad->GetLogy()) +
                    ",\"logz\":" + B(pad->GetLogz()) + ",\"gridx\":" + B(pad->GetGridx()) +
                    ",\"gridy\":" + B(pad->GetGridy()) + ",\"theta\":" + N(pad->GetTheta()) +
                    ",\"phi\":" + N(pad->GetPhi()) + ",\"fc\":" + Color(pad->GetFillColor()) +
                    ",\"lm\":" + N(pad->GetLeftMargin()) + ",\"rm\":" + N(pad->GetRightMargin()) +
                    ",\"bm\":" + N(pad->GetBottomMargin()) + ",\"tm\":" + N(pad->GetTopMargin());
  std::string items;
  std::string pads;
  if (TList *primitives = pad->GetListOfPrimitives()) {
    for (TObjLink *link = primitives->FirstLink(); link != nullptr; link = link->Next()) {
      TObject *object = link->GetObject();
      if (auto *sub = dynamic_cast<TVirtualPad *>(object)) {
        pads += (pads.empty() ? "" : ",") + Pad(sub);
      } else if (object != nullptr && !object->InheritsFrom("TFrame")) {
        items += (items.empty() ? "" : ",") + Item(object, link->GetOption());
      }
    }
  }
  return out + ",\"items\":[" + items + "],\"pads\":[" + pads + "]}";
}

inline std::string Canvas(TCanvas *canvas) {
  return "{\"k\":\"canvas\",\"n\":" + Q(canvas->GetName()) + ",\"t\":" + Q(canvas->GetTitle()) +
         ",\"w\":" + N(canvas->GetWw()) + ",\"h\":" + N(canvas->GetWh()) +
         ",\"optstat\":" + N(gStyle->GetOptStat()) + ",\"opttitle\":" + N(gStyle->GetOptTitle()) +
         ",\"palette\":" + Palette() + ",\"pad\":" + Pad(canvas) + "}";
}

/** A single object, as a canvas of one pad would hold it. */
inline std::string Single(TObject *object, const char *option) {
  return "{\"k\":\"single\",\"optstat\":" + N(gStyle->GetOptStat()) + ",\"opttitle\":" +
         N(gStyle->GetOptTitle()) + ",\"palette\":" + Palette() + ",\"item\":" + Item(object, option) + "}";
}

/* ------------------------------------------------------------------------ */
/* The session                                                              */
/* ------------------------------------------------------------------------ */

inline std::string Keys(TDirectory *directory, int depth) {
  std::string out = "[";
  bool first = true;
  if (directory != nullptr && directory->GetListOfKeys() != nullptr) {
    TIter next(directory->GetListOfKeys());
    while (auto *key = dynamic_cast<TKey *>(next())) {
      if (!first) out += ",";
      first = false;
      out += "{\"n\":" + Q(key->GetName()) + ",\"c\":" + Q(key->GetClassName()) + ",\"t\":" + Q(key->GetTitle()) +
             ",\"cy\":" + N(key->GetCycle());
      TClass *type = TClass::GetClass(key->GetClassName());
      if (type != nullptr && type->InheritsFrom(TDirectory::Class()) && depth < 8) {
        out += ",\"d\":" + Keys(directory->GetDirectory(key->GetName()), depth + 1);
      }
      out += "}";
    }
  }
  return out + "]";
}

/** What the session holds: canvases, objects in memory, open files and their keys. */
inline std::string Inventory() {
  std::string out = "{\"canvases\":[";
  bool first = true;
  TIter canvases(gROOT->GetListOfCanvases());
  while (TObject *c = canvases()) {
    if (!first) out += ",";
    first = false;
    out += "{\"n\":" + Q(c->GetName()) + ",\"t\":" + Q(c->GetTitle()) + ",\"c\":" + Q(c->ClassName()) + "}";
  }
  out += "],\"memory\":[";
  first = true;
  TIter memory(gROOT->GetList());
  while (TObject *o = memory()) {
    if (!first) out += ",";
    first = false;
    out += "{\"n\":" + Q(o->GetName()) + ",\"t\":" + Q(o->GetTitle()) + ",\"c\":" + Q(o->ClassName()) + "}";
  }
  out += "],\"files\":[";
  first = true;
  TIter files(gROOT->GetListOfFiles());
  while (auto *f = dynamic_cast<TFile *>(files())) {
    if (!first) out += ",";
    first = false;
    out += "{\"n\":" + Q(f->GetName()) + ",\"t\":" + Q(f->GetTitle()) + ",\"keys\":" + Keys(f, 0) + "}";
  }
  return out + "]}";
}

/** A file, opened read-only if the session does not hold it yet; gDirectory is left as it was. */
inline TFile *File(const char *path) {
  if (auto *open = dynamic_cast<TFile *>(gROOT->GetListOfFiles()->FindObject(path))) return open;
  TDirectory::TContext keep;
  return TFile::Open(path, "READ");
}

/** Where an object lives: "canvas", "memory", or "file:<path>"; the path inside a file may name directories. */
inline TObject *Find(const std::string &where, const std::string &path) {
  if (where == "canvas") return gROOT->GetListOfCanvases()->FindObject(path.c_str());
  if (where == "memory") {
    TObject *o = gROOT->GetList()->FindObject(path.c_str());
    return o != nullptr ? o : gROOT->FindObject(path.c_str());
  }
  if (where.rfind("file:", 0) == 0) {
    TFile *f = File(where.substr(5).c_str());
    if (f == nullptr || f->IsZombie()) return nullptr;
    TDirectory::TContext keep;
    return f->Get(path.c_str());
  }
  return nullptr;
}

inline std::string Error(const std::string &what) { return "{\"error\":" + Q(what) + "}"; }

/** Opens a file in the session and answers with its keys. */
inline std::string Open(const char *path) {
  TFile *f = File(path);
  if (f == nullptr || f->IsZombie()) return Error(std::string("cannot open ") + path);
  return "{\"n\":" + Q(f->GetName()) + ",\"t\":" + Q(f->GetTitle()) + ",\"keys\":" + Keys(f, 0) + "}";
}

/** A canvas as its pads, anything else as one item. */
inline std::string Object(const char *where, const char *path, const char *option) {
  TObject *object = Find(where, path);
  if (object == nullptr) return Error(std::string("no object ") + path);
  if (auto *canvas = dynamic_cast<TCanvas *>(object)) return Canvas(canvas);
  return Single(object, option);
}

/** The leaves of a tree, which are what TTree::Draw takes. */
inline std::string Leaves(const char *where, const char *path) {
  auto *tree = dynamic_cast<TTree *>(Find(where, path));
  if (tree == nullptr) return Error(std::string("no tree ") + path);
  std::string out = "{\"entries\":" + N(static_cast<double>(tree->GetEntries())) + ",\"leaves\":[";
  bool first = true;
  TIter next(tree->GetListOfLeaves());
  while (auto *leaf = dynamic_cast<TLeaf *>(next())) {
    if (!first) out += ",";
    first = false;
    TBranch *branch = leaf->GetBranch();
    const bool alone = branch != nullptr && branch->GetListOfLeaves()->GetEntries() == 1;
    const std::string name = alone ? branch->GetName() : leaf->GetFullName().Data();
    out += "{\"n\":" + Q(name) + ",\"c\":" + Q(leaf->GetTypeName()) + ",\"t\":" + Q(leaf->GetTitle()) + "}";
  }
  return out + "]}";
}

/** TTree::Draw without graphics: the histogram it fills, drawn with the option given. */
inline std::string Draw(const char *where, const char *path, const char *expression, const char *cut,
                        const char *option) {
  auto *tree = dynamic_cast<TTree *>(Find(where, path));
  if (tree == nullptr) return Error(std::string("no tree ") + path);
  TDirectory::TContext keep;
  const std::string goff = std::string(option) + " goff";
  const Long64_t selected = tree->Draw(expression, cut, goff.c_str());
  if (selected < 0) return Error(std::string("TTree::Draw refused ") + expression);
  TH1 *h = tree->GetHistogram();
  if (h == nullptr) return Error("TTree::Draw made no histogram");
  return Single(h, option);
}

} // namespace SphereView

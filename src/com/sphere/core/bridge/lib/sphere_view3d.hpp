// sphere_view3d.hpp -- what sphere_view.hpp leaves out, for Sphere's TBrowser.
//
// sphere_view.hpp describes the objects a pad draws in two dimensions and the
// histograms and graphs it paints in three. What ROOT draws through a TView
// (TPolyMarker3D, TPolyLine3D), the shapes of a slide (TBox, TEllipse, TArrow,
// TPolyLine, TMarker) and a TGeo geometry are added here, per pad, under the
// key "x3d" of the canvas: Sphere's browser merges them into the pads by
// their depth-first index and draws them in its own 3D space, where they turn,
// zoom and stretch -- what a ROOT in batch, without OpenGL, cannot show.
//
// The geometry is sent as meshes: each visible volume's TBuffer3D, its
// polygons rebuilt from their segments, placed by the iterator's global
// matrix. Sphere.Demo writes one file per canvas:
//
//    SphereView3D::Save(canvas, "demo_x_c1.sphere.json");
#pragma once

#include "sphere_view.hpp"

#include "TArrow.h"
#include "TBox.h"
#include "TBuffer3D.h"
#include "TEllipse.h"
#include "TGeoManager.h"
#include "TGeoMatrix.h"
#include "TGeoNode.h"
#include "TGeoShape.h"
#include "TGeoVolume.h"
#include "TMarker.h"
#include "TPolyLine.h"
#include "TPolyLine3D.h"
#include "TPolyMarker3D.h"
#include "TView.h"
#include "TWbox.h"

#include <cstdio>
#include <string>
#include <vector>

namespace SphereView3D {

using SphereView::B;
using SphereView::Color;
using SphereView::N;
using SphereView::Q;

/** Past these, a cloud or a geometry is cut rather than sent whole. */
constexpr long kMaxPoints = 1500000;
constexpr int kMaxNodes = 25000;
constexpr long kMaxGeoPoints = 1200000;

inline std::string F(double value) {
  if (!std::isfinite(value)) return "0";
  char buffer[24];
  std::snprintf(buffer, sizeof(buffer), "%.6g", value);
  return buffer;
}

inline std::string Points3(const Float_t *p, long n) {
  std::string out = "[";
  if (p != nullptr) {
    for (long i = 0; i < 3 * n; ++i) {
      if (i > 0) out += ",";
      out += F(p[i]);
    }
  }
  return out + "]";
}

/** A TGeo volume tree as meshes in the frame of the top volume. */
class GeoWriter {
public:
  long points = 0;
  int nodes = 0;
  bool cut = false;
  std::string meshes;

  void Volume(TGeoVolume *volume, const TGeoMatrix *matrix) {
    if (volume == nullptr || volume->GetShape() == nullptr) return;
    if (nodes >= kMaxNodes || points >= kMaxGeoPoints) {
      cut = true;
      return;
    }
    const TBuffer3D &buffer = volume->GetShape()->GetBuffer3D(
        TBuffer3D::kCore | TBuffer3D::kRawSizes | TBuffer3D::kRaw, kTRUE);
    if (!buffer.SectionsValid(TBuffer3D::kRaw) || buffer.NbPnts() == 0 || buffer.fPnts == nullptr) return;
    ++nodes;
    std::string p = "[";
    for (UInt_t i = 0; i < buffer.NbPnts(); ++i) {
      Double_t local[3] = {buffer.fPnts[3 * i], buffer.fPnts[3 * i + 1], buffer.fPnts[3 * i + 2]};
      Double_t master[3] = {local[0], local[1], local[2]};
      if (matrix != nullptr) matrix->LocalToMaster(local, master);
      if (i > 0) p += ",";
      p += F(master[0]) + "," + F(master[1]) + "," + F(master[2]);
    }
    p += "]";
    points += buffer.NbPnts();
    // Each polygon is a list of segments; walking them gives its corners in order.
    std::string pol = "[";
    bool first = true;
    const Int_t *pols = buffer.fPols;
    const Int_t *segs = buffer.fSegs;
    UInt_t j = 0;
    for (UInt_t ip = 0; pols != nullptr && segs != nullptr && ip < buffer.NbPols(); ++ip) {
      const Int_t nseg = pols[j + 1];
      const Int_t *s = pols + j + 2;
      j += 2 + nseg;
      if (nseg < 2) continue;
      std::vector<int> loop;
      const int a0 = segs[3 * s[0] + 1], b0 = segs[3 * s[0] + 2];
      const int a1 = segs[3 * s[1] + 1], b1 = segs[3 * s[1] + 2];
      int current;
      if (b0 == a1 || b0 == b1) {
        loop.push_back(a0);
        loop.push_back(b0);
        current = b0;
      } else {
        loop.push_back(b0);
        loop.push_back(a0);
        current = a0;
      }
      for (int k = 1; k < nseg - 1; ++k) {
        const int a = segs[3 * s[k] + 1], b = segs[3 * s[k] + 2];
        const int next = a == current ? b : a;
        loop.push_back(next);
        current = next;
      }
      if (!first) pol += ",";
      first = false;
      pol += std::to_string(loop.size());
      for (int v : loop) pol += "," + std::to_string(v);
    }
    pol += "]";
    // Segments too: a shape without polygons (a wire) is still seen.
    std::string seg = "[";
    if (buffer.NbPols() == 0 && segs != nullptr) {
      for (UInt_t k = 0; k < buffer.NbSegs(); ++k) {
        if (k > 0) seg += ",";
        seg += std::to_string(segs[3 * k + 1]) + "," + std::to_string(segs[3 * k + 2]);
      }
    }
    seg += "]";
    if (!meshes.empty()) meshes += ",";
    meshes += "{\"n\":" + Q(volume->GetName()) + ",\"sh\":" + Q(volume->GetShape()->ClassName()) +
              ",\"c\":" + Color(volume->GetLineColor()) + ",\"a\":" + N(volume->GetTransparency()) +
              ",\"p\":" + p + ",\"pol\":" + pol + ",\"seg\":" + seg + "}";
  }

  void Tree(TGeoVolume *top, int maxLevel) {
    if (top == nullptr) return;
    if (top->IsVisible() && !top->IsAssembly()) Volume(top, nullptr);
    TGeoIterator next(top);
    while (TGeoNode *node = next()) {
      if (next.GetLevel() > maxLevel) {
        next.Skip();
        continue;
      }
      TGeoVolume *volume = node->GetVolume();
      if (volume == nullptr || volume->IsAssembly() || !volume->IsVisible()) continue;
      Volume(volume, next.GetCurrentMatrix());
      if (cut) break;
    }
  }
};

inline std::string Geometry(TGeoVolume *top) {
  GeoWriter writer;
  int level = gGeoManager != nullptr ? gGeoManager->GetVisLevel() : 3;
  if (level <= 0) level = 3;
  writer.Tree(top, level);
  return "{\"k\":\"geo\",\"n\":" + Q(top->GetName()) + ",\"t\":" + Q(top->GetTitle()) +
         ",\"nodes\":" + N(writer.nodes) + ",\"cut\":" + B(writer.cut) + ",\"meshes\":[" + writer.meshes + "]}";
}

/** An object sphere_view.hpp names "other", when it is one of those drawn here; "" otherwise. */
inline std::string Extra(TObject *object, const char *option) {
  if (object == nullptr) return "";
  if (auto *pm = dynamic_cast<TPolyMarker3D *>(object)) {
    long n = pm->Size();
    if (n > kMaxPoints) n = kMaxPoints;
    return SphereView::Head("pm3", pm, option) + ",\"p\":" + Points3(pm->GetP(), n) + "}";
  }
  if (auto *pl = dynamic_cast<TPolyLine3D *>(object)) {
    long n = pl->Size();
    if (n > kMaxPoints) n = kMaxPoints;
    return SphereView::Head("pl3", pl, option) + ",\"p\":" + Points3(pl->GetP(), n) + "}";
  }
  if (auto *volume = dynamic_cast<TGeoVolume *>(object)) return Geometry(volume);
  if (auto *arrow = dynamic_cast<TArrow *>(object)) {
    return SphereView::Head("arrow", arrow, option) + ",\"x1\":" + N(arrow->GetX1()) + ",\"y1\":" +
           N(arrow->GetY1()) + ",\"x2\":" + N(arrow->GetX2()) + ",\"y2\":" + N(arrow->GetY2()) +
           ",\"as\":" + N(arrow->GetArrowSize()) + ",\"ao\":" + Q(arrow->GetOption()) +
           ",\"aa\":" + N(arrow->GetAngle()) +
           ",\"ndc\":" + B(arrow->TestBit(TLine::kLineNDC)) + "}";
  }
  if (dynamic_cast<TPave *>(object) != nullptr) return "";
  if (auto *box = dynamic_cast<TBox *>(object)) {
    std::string wbox;
    if (auto *w = dynamic_cast<TWbox *>(box)) {
      wbox = ",\"bmode\":" + N(w->GetBorderMode()) + ",\"bsize\":" + N(w->GetBorderSize());
    }
    return SphereView::Head("box", box, option) + ",\"x1\":" + N(box->GetX1()) + ",\"y1\":" + N(box->GetY1()) +
           ",\"x2\":" + N(box->GetX2()) + ",\"y2\":" + N(box->GetY2()) + wbox + "}";
  }
  if (auto *ellipse = dynamic_cast<TEllipse *>(object)) {
    return SphereView::Head("ellipse", ellipse, option) + ",\"x1\":" + N(ellipse->GetX1()) + ",\"y1\":" +
           N(ellipse->GetY1()) + ",\"r1\":" + N(ellipse->GetR1()) + ",\"r2\":" + N(ellipse->GetR2()) +
           ",\"phimin\":" + N(ellipse->GetPhimin()) + ",\"phimax\":" + N(ellipse->GetPhimax()) +
           ",\"theta\":" + N(ellipse->GetTheta()) + ",\"noedges\":" + B(ellipse->GetNoEdges()) + "}";
  }
  if (auto *poly = dynamic_cast<TPolyLine *>(object)) {
    std::string out = SphereView::Head("polyline", poly, option) + ",\"ndc\":" +
                      B(poly->TestBit(TPolyLine::kPolyLineNDC)) + ",\"x\":[";
    const Int_t n = poly->Size();
    for (Int_t i = 0; i < n && poly->GetX() != nullptr; ++i) out += (i > 0 ? "," : "") + N(poly->GetX()[i]);
    out += "],\"y\":[";
    for (Int_t i = 0; i < n && poly->GetY() != nullptr; ++i) out += (i > 0 ? "," : "") + N(poly->GetY()[i]);
    return out + "]}";
  }
  if (auto *marker = dynamic_cast<TMarker *>(object)) {
    return SphereView::Head("marker", marker, option) + ",\"x\":" + N(marker->GetX()) + ",\"y\":" +
           N(marker->GetY()) + ",\"ndc\":" + B(marker->TestBit(TMarker::kMarkerNDC)) + "}";
  }
  return "";
}

/** The extras of every pad, depth first in the order sphere_view.hpp writes the pads. */
inline void Pads(TVirtualPad *pad, int &index, std::string &out) {
  const int mine = index++;
  std::string items;
  std::string subs;
  if (TList *primitives = pad->GetListOfPrimitives()) {
    for (TObjLink *link = primitives->FirstLink(); link != nullptr; link = link->Next()) {
      TObject *object = link->GetObject();
      if (dynamic_cast<TVirtualPad *>(object) != nullptr) continue;
      // The frame is a TWbox, but the viewer draws it as the pad's; a box of it would cover the histogram.
      if (object == nullptr || object->InheritsFrom("TFrame")) continue;
      const std::string extra = Extra(object, link->GetOption());
      if (!extra.empty()) items += (items.empty() ? "" : ",") + extra;
    }
  }
  std::string entry = "{\"idx\":" + std::to_string(mine) + ",\"ux1\":" + N(pad->GetX1()) +
                      ",\"uy1\":" + N(pad->GetY1()) + ",\"ux2\":" + N(pad->GetX2()) + ",\"uy2\":" + N(pad->GetY2());
  if (TView *view = pad->GetView()) {
    const Double_t *lo = view->GetRmin();
    const Double_t *hi = view->GetRmax();
    entry += ",\"view\":{\"min\":[" + N(lo[0]) + "," + N(lo[1]) + "," + N(lo[2]) + "],\"max\":[" + N(hi[0]) + "," +
             N(hi[1]) + "," + N(hi[2]) + "],\"lat\":" + N(view->GetLatitude()) +
             ",\"lon\":" + N(view->GetLongitude()) + ",\"psi\":" + N(view->GetPsi()) + "}";
  }
  entry += ",\"items\":[" + items + "]}";
  out += (out.empty() ? "" : ",") + entry;
  if (TList *primitives = pad->GetListOfPrimitives()) {
    for (TObjLink *link = primitives->FirstLink(); link != nullptr; link = link->Next()) {
      if (auto *sub = dynamic_cast<TVirtualPad *>(link->GetObject())) Pads(sub, index, out);
    }
  }
}

/** A canvas as sphere_view.hpp writes it, with the extras of its pads under "x3d". */
inline std::string Canvas(TCanvas *canvas) {
  std::string json = SphereView::Canvas(canvas);
  std::string extras;
  int index = 0;
  Pads(canvas, index, extras);
  json.pop_back();
  return json + ",\"x3d\":[" + extras + "]}";
}

/** The geometry in memory as a scene of its own: what a ROOT in batch never draws. */
inline std::string GeometryScene() {
  if (gGeoManager == nullptr || gGeoManager->GetTopVolume() == nullptr) return "";
  TGeoVolume *top = gGeoManager->GetTopVolume();
  return "{\"k\":\"canvas\",\"n\":\"geometry\",\"t\":" + Q(gGeoManager->GetTitle()) +
         ",\"w\":800,\"h\":800,\"optstat\":0,\"opttitle\":1,\"palette\":" + SphereView::Palette() +
         ",\"pad\":{\"n\":\"geometry\",\"t\":" + Q(top->GetName()) +
         ",\"px\":0,\"py\":0,\"pw\":1,\"ph\":1,\"items\":[],\"pads\":[]},\"x3d\":[{\"idx\":0,\"items\":[" +
         Geometry(top) + "]}]}";
}

inline bool Write(const std::string &json, const char *path) {
  if (json.empty()) return false;
  FILE *file = std::fopen(path, "wb");
  if (file == nullptr) return false;
  std::fwrite(json.data(), 1, json.size(), file);
  std::fputc('\n', file);
  std::fclose(file);
  return true;
}

inline bool Save(TCanvas *canvas, const char *path) { return Write(Canvas(canvas), path); }

/** Whether a canvas already shows a geometry, which then needs no scene of its own. */
inline bool ShowsGeometry(TVirtualPad *pad) {
  if (TList *primitives = pad->GetListOfPrimitives()) {
    for (TObjLink *link = primitives->FirstLink(); link != nullptr; link = link->Next()) {
      TObject *object = link->GetObject();
      if (dynamic_cast<TGeoVolume *>(object) != nullptr) return true;
      if (auto *sub = dynamic_cast<TVirtualPad *>(object)) {
        if (ShowsGeometry(sub)) return true;
      }
    }
  }
  return false;
}

} // namespace SphereView3D

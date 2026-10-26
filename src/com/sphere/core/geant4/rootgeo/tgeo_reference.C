// The geometry whose class descriptions G4editor writes ROOT files with (TGeoStreamers.bin).
//
// It uses every TGeo class G4editor reads or writes, so the file ROOT writes for it describes them
// all. To follow a newer ROOT, run this macro with it, then extract the descriptions:
//   root -l -b -q tgeo_reference.C+
//   java -cp Sphere.jar com.sphere.core.geant4.TGeoWriter extract tgeo_reference.root TGeoStreamers.bin

#include "TGeoManager.h"
#include "TGeoMaterial.h"
#include "TGeoMedium.h"
#include "TGeoElement.h"
#include "TGeoBBox.h"
#include "TGeoTube.h"
#include "TGeoCone.h"
#include "TGeoSphere.h"
#include "TGeoTorus.h"
#include "TGeoTrd1.h"
#include "TGeoTrd2.h"
#include "TGeoArb8.h"
#include "TGeoPara.h"
#include "TGeoPcon.h"
#include "TGeoPgon.h"
#include "TGeoXtru.h"
#include "TGeoEltu.h"
#include "TGeoParaboloid.h"
#include "TGeoHype.h"
#include "TGeoScaledShape.h"
#include "TGeoCompositeShape.h"
#include "TGeoBoolNode.h"
#include "TGeoHalfSpace.h"
#include "TGeoTessellated.h"
#include "TGeoMatrix.h"
#include "TFile.h"

void tgeo_reference(const char *out = "tgeo_reference.root")
{
   auto geom = new TGeoManager("RefGeo", "every TGeo class");
   auto table = geom->GetElementTable();
   auto elPb = table->GetElement(82);
   auto elH = table->GetElement(1);
   auto elO = table->GetElement(8);
   auto iso1 = new TGeoIsotope("U235", 92, 235, 235.044);
   auto iso2 = new TGeoIsotope("U238", 92, 238, 238.051);
   auto elU = new TGeoElement("enriched_U", "U", 2);
   elU->AddIsotope(iso1, 0.9);
   elU->AddIsotope(iso2, 0.1);

   auto vac = new TGeoMaterial("Vacuum", 0, 0, 0);
   auto pb = new TGeoMaterial("Lead", elPb, 11.35);
   auto water = new TGeoMixture("Water", 2, 1.0);
   water->AddElement(elH, 2);
   water->AddElement(elO, 1);
   auto air = new TGeoMixture("Air", 2, 0.00120479);
   air->AddElement(14.007, 7, 0.7);
   air->AddElement(15.999, 8, 0.3);
   air->SetState(TGeoMaterial::kMatStateGas);
   air->SetTemperature(293.15);
   auto fuel = new TGeoMixture("Fuel", 2, 10.5);
   fuel->AddElement(elU, 0.88);
   fuel->AddElement(elO, 0.12);

   auto mVac = new TGeoMedium("Vacuum", 1, vac);
   auto mPb = new TGeoMedium("Lead", 2, pb);
   auto mWater = new TGeoMedium("Water", 3, water);
   auto mAir = new TGeoMedium("Air", 4, air);
   auto mFuel = new TGeoMedium("Fuel", 5, fuel);

   auto top = geom->MakeBox("TOP", mAir, 500, 500, 500);
   geom->SetTopVolume(top);

   int k = 0;
   auto place = [&](TGeoVolume *v) {
      double x = -400 + 80 * (k % 10), y = -300 + 120 * (k / 10);
      k++;
      top->AddNode(v, 1, new TGeoTranslation(x, y, 0));
   };

   place(new TGeoVolume("vBox", new TGeoBBox("sBox", 10, 20, 30), mPb));
   place(new TGeoVolume("vTube", new TGeoTube("sTube", 5, 10, 20), mPb));
   place(new TGeoVolume("vTubeSeg", new TGeoTubeSeg("sTubeSeg", 5, 10, 20, 30, 300), mPb));
   place(new TGeoVolume("vCone", new TGeoCone("sCone", 20, 2, 5, 4, 10), mPb));
   place(new TGeoVolume("vConeSeg", new TGeoConeSeg("sConeSeg", 20, 2, 5, 4, 10, 10, 200), mPb));
   place(new TGeoVolume("vSphere", new TGeoSphere("sSphere", 5, 15, 10, 170, 0, 270), mPb));
   place(new TGeoVolume("vOrb", new TGeoSphere("sOrb", 0, 15), mWater));
   place(new TGeoVolume("vTorus", new TGeoTorus("sTorus", 20, 2, 5, 0, 360), mPb));
   place(new TGeoVolume("vTrd1", new TGeoTrd1("sTrd1", 10, 5, 8, 20), mPb));
   place(new TGeoVolume("vTrd2", new TGeoTrd2("sTrd2", 10, 5, 8, 4, 20), mPb));
   place(new TGeoVolume("vTrap", new TGeoTrap("sTrap", 20, 15, 30, 8, 5, 7, 10, 10, 6, 8, 10), mPb));
   place(new TGeoVolume("vGtra", new TGeoGtra("sGtra", 20, 15, 30, 25, 8, 5, 7, 10, 10, 6, 8, 10), mPb));
   auto arb = new TGeoArb8("sArb8", 20);
   double v[16] = {-10, -10, -10, 10, 10, 10, 10, -10, -5, -5, -5, 5, 5, 5, 5, -5};
   for (int i = 0; i < 8; i++)
      arb->SetVertex(i, v[2 * i], v[2 * i + 1]);
   place(new TGeoVolume("vArb8", arb, mPb));
   place(new TGeoVolume("vPara", new TGeoPara("sPara", 10, 15, 20, 10, 20, 30), mPb));
   place(new TGeoVolume("vCtub", new TGeoCtub("sCtub", 5, 10, 20, 0, 360, 0, -0.6, -0.8, 0.6, 0, 0.8), mPb));
   auto pcon = new TGeoPcon("sPcon", 0, 360, 3);
   pcon->DefineSection(0, -20, 0, 10);
   pcon->DefineSection(1, 0, 5, 15);
   pcon->DefineSection(2, 20, 0, 8);
   place(new TGeoVolume("vPcon", pcon, mPb));
   auto pgon = new TGeoPgon("sPgon", 0, 360, 6, 2);
   pgon->DefineSection(0, -20, 5, 15);
   pgon->DefineSection(1, 20, 5, 10);
   place(new TGeoVolume("vPgon", pgon, mPb));
   auto xtru = new TGeoXtru(2);
   xtru->SetName("sXtru");
   double xv[5] = {-10, -10, 10, 10, 0};
   double yv[5] = {-10, 10, 10, -10, -15};
   xtru->DefinePolygon(5, xv, yv);
   xtru->DefineSection(0, -20, 0, 0, 1.0);
   xtru->DefineSection(1, 20, 2, 1, 0.5);
   place(new TGeoVolume("vXtru", xtru, mPb));
   place(new TGeoVolume("vEltu", new TGeoEltu("sEltu", 10, 5, 20), mPb));
   place(new TGeoVolume("vParab", new TGeoParaboloid("sParab", 5, 15, 20), mPb));
   place(new TGeoVolume("vHype", new TGeoHype("sHype", 5, 10, 10, 20, 20), mPb));
   auto scl = new TGeoScaledShape("sScaled", new TGeoSphere("sScaledSph", 0, 10), new TGeoScale("sScale", 1.0, 0.5, 2.0));
   place(new TGeoVolume("vScaled", scl, mPb));

   auto bA = new TGeoBBox("bA", 10, 10, 10);
   auto bB = new TGeoTube("bB", 0, 5, 15);
   auto rot = new TGeoRotation("rBool", 30, 40, 50);
   auto ct = new TGeoCombiTrans("ctBool", 3, 2, 1, rot);
   auto tr = new TGeoTranslation("tBool", 5, 0, 0);
   place(new TGeoVolume("vUnion", new TGeoCompositeShape("sUnion", new TGeoUnion(bA, bB, nullptr, ct)), mPb));
   place(new TGeoVolume("vSub", new TGeoCompositeShape("sSub", new TGeoSubtraction(bA, bB, tr, ct)), mPb));
   place(new TGeoVolume("vInter", new TGeoCompositeShape("sInter", new TGeoIntersection(bA, bB, nullptr, tr)), mPb));
   double hp[3] = {0, 0, 0}, hn[3] = {0, 0, 1};
   place(new TGeoVolume("vHalf", new TGeoCompositeShape("sHalf", new TGeoIntersection(bA, new TGeoHalfSpace("sHS", hp, hn), nullptr, nullptr)), mPb));

   auto tsl = new TGeoTessellated("sTess", 4);
   tsl->AddFacet(ROOT::Geom::Vertex_t(0, 0, 0), ROOT::Geom::Vertex_t(0, 10, 0), ROOT::Geom::Vertex_t(10, 0, 0));
   tsl->AddFacet(ROOT::Geom::Vertex_t(0, 0, 0), ROOT::Geom::Vertex_t(10, 0, 0), ROOT::Geom::Vertex_t(0, 0, 10));
   tsl->AddFacet(ROOT::Geom::Vertex_t(0, 0, 0), ROOT::Geom::Vertex_t(0, 0, 10), ROOT::Geom::Vertex_t(0, 10, 0));
   tsl->AddFacet(ROOT::Geom::Vertex_t(10, 0, 0), ROOT::Geom::Vertex_t(0, 10, 0), ROOT::Geom::Vertex_t(0, 0, 10));
   tsl->CloseShape();
   place(new TGeoVolume("vTess", tsl, mPb));

   // rotations and transforms of nodes
   auto vRot = new TGeoVolume("vRotBox", new TGeoBBox("sRotBox", 3, 4, 5), mWater);
   top->AddNode(vRot, 1, new TGeoCombiTrans(100, 200, 300, new TGeoRotation("r1", 10, 20, 30)));
   top->AddNode(vRot, 2, new TGeoRotation("r2", 90, 0, 0));
   auto hm = new TGeoHMatrix("hm");
   hm->SetDx(-100);
   hm->ReflectZ(kTRUE);
   top->AddNode(vRot, 3, hm);

   // divisions
   auto slab = geom->MakeBox("SLAB", mPb, 40, 20, 10);
   top->AddNode(slab, 1, new TGeoTranslation(0, 300, 300));
   slab->Divide("SLABX", 1, 8, -40, 10);
   auto tub = geom->MakeTube("TUB", mWater, 10, 30, 20);
   top->AddNode(tub, 1, new TGeoTranslation(0, -300, 300));
   tub->Divide("TUBPHI", 2, 6, 0, 0);
   auto zdiv = geom->MakeBox("ZBOX", mPb, 10, 10, 40);
   top->AddNode(zdiv, 1, new TGeoTranslation(300, 0, 300));
   zdiv->Divide("ZDIV", 3, 4, -40, 20);
   auto rtub = geom->MakeTube("RTUB", mWater, 10, 30, 20);
   top->AddNode(rtub, 1, new TGeoTranslation(-300, 0, 300));
   rtub->Divide("RDIV", 1, 4, 10, 5);
   auto ybox = geom->MakeBox("YBOX", mPb, 10, 30, 10);
   top->AddNode(ybox, 1, new TGeoTranslation(300, 300, -300));
   ybox->Divide("YDIV", 2, 3, -30, 20);

   // an assembly
   auto asmb = new TGeoVolumeAssembly("ASSEMBLY");
   auto part = new TGeoVolume("vPart", new TGeoBBox("sPart", 5, 5, 5), mFuel);
   asmb->AddNode(part, 1, new TGeoTranslation(-10, 0, 0));
   asmb->AddNode(part, 2, new TGeoTranslation(10, 0, 0));
   top->AddNode(asmb, 1, new TGeoTranslation(0, 0, -300));
   top->AddNode(asmb, 2, new TGeoCombiTrans(0, 100, -300, new TGeoRotation("ra", 45, 0, 0)));

   top->SetLineColor(kGray);
   slab->SetLineColor(kRed);
   slab->SetTransparency(40);
   geom->CloseGeometry();
   geom->Export(out);
   TString g(out);
   g.ReplaceAll(".root", ".gdml");
   geom->Export(g);
}

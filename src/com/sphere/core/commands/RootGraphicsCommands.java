package com.sphere.core.commands;

import com.sphere.utils.AppLogger;

/**
 * Canvases, drawing primitives, palettes, GUI and geometry.
 *
 * Split out of Handlers, which had grown to hold every backend at once. The
 * helpers these handlers share -- the argument readers, the interpreter call,
 * the named-handle builders -- stay in Handlers and are called through it.
 */
public final class RootGraphicsCommands {

    private RootGraphicsCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static void rootCanvasCd(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root canvas cd");
        if (a.isEmpty()) {
            Handlers.usage(":root canvas cd <pad>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gPad->cd(" + a0 + ")");
    }

    public static void rootCanvasClear(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gPad->Clear()");
    }

    public static void rootCanvasList(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gROOT->GetListOfCanvases()->Print()");
    }

    public static void rootCanvasNew(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root canvas new");
        if (a.isEmpty()) {
            Handlers.usage(":root canvas new <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "new TCanvas(\"" + a0 + "\",\"" + a0 + "\",800,600)");
    }

    public static void rootCanvasSave(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root canvas save");
        if (a.isEmpty()) {
            Handlers.usage(":root canvas save <file>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gPad->SaveAs(\"" + a0 + "\")");
    }

    public static void rootCanvasUpdate(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gPad->Update()");
    }

    public static void rootDrawArrow(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root draw arrow"));
        if (w.length < 4) {
            Handlers.usage(":root draw arrow <x1> <y1> <x2> <y2>".trim());
            return;
        }
        Handlers.cling(c, "((new TArrow(" + Handlers.csv(Handlers.join(w, 0)) + "))->Draw(), std::string(\"drawn\"))");
    }

    public static void rootDrawBox(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root draw box"));
        if (w.length < 4) {
            Handlers.usage(":root draw box <x1> <y1> <x2> <y2>".trim());
            return;
        }
        Handlers.cling(c, "((new TBox(" + Handlers.csv(Handlers.join(w, 0)) + "))->Draw(), std::string(\"drawn\"))");
    }

    public static void rootDrawEllipse(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root draw ellipse"));
        if (w.length < 4) {
            Handlers.usage(":root draw ellipse <x> <y> <r1> <r2>".trim());
            return;
        }
        Handlers.cling(c, "((new TEllipse(" + Handlers.csv(Handlers.join(w, 0)) + "))->Draw(), std::string(\"drawn\"))");
    }

    public static void rootDrawEntry(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root draw entry"));
        if (w.length < 3) {
            Handlers.usage(":root draw entry <legend> <object> <label>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TLegend") + "->AddEntry(" + Handlers.obj("TObject", w[1]) + ", \"" + Handlers.join(w, 2) + "\"), std::string(\"added\"))");
    }

    public static void rootDrawLatex(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root draw latex"));
        if (w.length < 3) {
            Handlers.usage(":root draw latex <x> <y> <text>".trim());
            return;
        }
        Handlers.cling(c, "((new TLatex(" + Handlers.csv(w[0] + ' ' + w[1]) + ", \"" + Handlers.join(w, 2) + "\"))->Draw(), std::string(\"drawn\"))");
    }

    public static void rootDrawLegend(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root draw legend"));
        if (w.length < 5) {
            Handlers.usage(":root draw legend <name> <x1> <y1> <x2> <y2>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TLegend", "new TLegend(" + Handlers.csv(Handlers.join(w, 1)) + ")"));
    }

    public static void rootDrawLine(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root draw line"));
        if (w.length < 4) {
            Handlers.usage(":root draw line <x1> <y1> <x2> <y2>".trim());
            return;
        }
        Handlers.cling(c, "((new TLine(" + Handlers.csv(Handlers.join(w, 0)) + "))->Draw(), std::string(\"drawn\"))");
    }

    public static void rootDrawMarker(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root draw marker"));
        if (w.length < 3) {
            Handlers.usage(":root draw marker <x> <y> <style>".trim());
            return;
        }
        Handlers.cling(c, "((new TMarker(" + Handlers.csv(Handlers.join(w, 0)) + "))->Draw(), std::string(\"drawn\"))");
    }

    public static void rootDrawText(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root draw text"));
        if (w.length < 3) {
            Handlers.usage(":root draw text <x> <y> <text>".trim());
            return;
        }
        Handlers.cling(c, "((new TText(" + Handlers.csv(w[0] + ' ' + w[1]) + ", \"" + Handlers.join(w, 2) + "\"))->Draw(), std::string(\"drawn\"))");
    }

    public static void rootGeomDraw(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gGeoManager->GetTopVolume()->Draw()");
    }

    public static void rootGeomExport(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root geom export");
        if (a.isEmpty()) {
            Handlers.usage(":root geom export <file>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gGeoManager->Export(\"" + a0 + "\")");
    }

    public static void rootGeomLoad(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root geom load");
        if (a.isEmpty()) {
            Handlers.usage(":root geom load <file>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "TGeoManager::Import(\"" + a0 + "\")");
    }

    public static void rootGuiClose(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root gui close"));
        if (name.isEmpty()) {
            Handlers.usage(":root gui close <name>");
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(name, "TGMainFrame")
               + "->UnmapWindow(), SphereBridge::HandleDrop(\"" + name + "\"))");
    }

    public static void rootGuiNew(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root gui new"));
        if (name.isEmpty()) {
            Handlers.usage(":root gui new <name>");
            return;
        }
        Handlers.cling(c, Handlers.keep(name, "TGMainFrame",
            "new TGMainFrame(gClient->GetRoot(), 400, 300)"));
    }

    public static void rootGuiShow(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root gui show"));
        if (name.isEmpty()) {
            Handlers.usage(":root gui show <name>");
            return;
        }
        Handlers.cling(c, Handlers.held(name, "TGMainFrame") + "->MapWindow()");
    }

    public static void rootPaletteContours(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root palette contours"));
        if (w.length < 1) {
            Handlers.usage(":root palette contours <n>".trim());
            return;
        }
        Handlers.cling(c, "(gStyle->SetNumberContours(" + Handlers.asInt(w[0], 20) + "), std::string(\"set\"))");
    }

    public static void rootPaletteGrayscale(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root palette grayscale"));
        if (w.length < 1) {
            Handlers.usage(":root palette grayscale <0|1>".trim());
            return;
        }
        Handlers.cling(c, "(TColor::SetGrayscale(" + (Handlers.asInt(w[0], 1) != 0) + "), std::string(\"set\"))");
    }

    public static void rootPaletteInvert(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root palette invert"));
        Handlers.cling(c, "(TColor::InvertPalette(), std::string(\"inverted\"))");
    }

    public static void rootPaletteSet(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root palette set"));
        if (w.length < 1) {
            Handlers.usage(":root palette set <n>".trim());
            return;
        }
        Handlers.cling(c, "(gStyle->SetPalette(" + Handlers.asInt(w[0], 1) + "), std::string(\"palette set\"))");
    }

    public static void rootStyleSet(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root style set");
        if (a.isEmpty()) {
            Handlers.usage(":root style set <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gROOT->SetStyle(\"" + a0 + "\")");
    }

    // --- reading fits back, TF1 parameters, applying a TMVA model, canvas layout and output, geometry, RooFit datasets and results, tree caches and indices, the rest of RDataFrame, the host system, regular expressions, file internals and more of TMath ---

    public static void rootCanvasDivide(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas divide"));
        if (w.length < 2) {
            Handlers.usage(":root canvas divide <columns> <rows>".trim());
            return;
        }
        Handlers.cling(c, "(gPad->Divide(" + Handlers.csv(Handlers.join(w, 0)) + "), std::string(\"divided\"))");
    }

    public static void rootCanvasLogX(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas logx"));
        if (w.length < 1) {
            Handlers.usage(":root canvas logx <0|1>".trim());
            return;
        }
        Handlers.cling(c, "(gPad->SetLogx(" + Handlers.asInt(w[0], 1) + "), std::string(\"set\"))");
    }

    public static void rootCanvasLogY(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas logy"));
        if (w.length < 1) {
            Handlers.usage(":root canvas logy <0|1>".trim());
            return;
        }
        Handlers.cling(c, "(gPad->SetLogy(" + Handlers.asInt(w[0], 1) + "), std::string(\"set\"))");
    }

    public static void rootCanvasLogZ(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas logz"));
        if (w.length < 1) {
            Handlers.usage(":root canvas logz <0|1>".trim());
            return;
        }
        Handlers.cling(c, "(gPad->SetLogz(" + Handlers.asInt(w[0], 1) + "), std::string(\"set\"))");
    }

    public static void rootCanvasGrid(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas grid"));
        if (w.length < 1) {
            Handlers.usage(":root canvas grid <0|1>".trim());
            return;
        }
        Handlers.cling(c, "(gPad->SetGrid(" + Handlers.asInt(w[0], 1) + ", " + Handlers.asInt(w[0], 1) + "), std::string(\"set\"))");
    }

    public static void rootCanvasMargins(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas margins"));
        if (w.length < 4) {
            Handlers.usage(":root canvas margins <left> <right> <bottom> <top>".trim());
            return;
        }
        Handlers.cling(c, "(gPad->SetMargin(" + Handlers.csv(Handlers.join(w, 0)) + "), std::string(\"set\"))");
    }

    public static void rootCanvasRange(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas range"));
        if (w.length < 4) {
            Handlers.usage(":root canvas range <x1> <y1> <x2> <y2>".trim());
            return;
        }
        Handlers.cling(c, "(gPad->Range(" + Handlers.csv(Handlers.join(w, 0)) + "), std::string(\"set\"))");
    }

    public static void rootCanvasSize(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas size"));
        if (w.length < 2) {
            Handlers.usage(":root canvas size <width> <height>".trim());
            return;
        }
        Handlers.cling(c, "(gPad->GetCanvas()->SetCanvasSize(" + Handlers.csv(Handlers.join(w, 0)) + "), std::string(\"resized\"))");
    }

    public static void rootCanvasPrint(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas print"));
        if (w.length < 1) {
            Handlers.usage(":root canvas print <file>".trim());
            return;
        }
        Handlers.cling(c, "(gPad->Print(\"" + w[0] + "\"), std::string(\"written to " + w[0] + "\"))");
    }

    public static void rootCanvasPdfOpen(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas pdf-open"));
        if (w.length < 1) {
            Handlers.usage(":root canvas pdf-open <file>".trim());
            return;
        }
        Handlers.cling(c, "(gPad->Print(\"" + w[0] + "[\"), std::string(\"" + w[0] + " open\"))");
    }

    public static void rootCanvasPdfPage(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas pdf-page"));
        if (w.length < 1) {
            Handlers.usage(":root canvas pdf-page <file>".trim());
            return;
        }
        Handlers.cling(c, "(gPad->Print(\"" + w[0] + "\"), std::string(\"page added\"))");
    }

    public static void rootCanvasPdfClose(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas pdf-close"));
        if (w.length < 1) {
            Handlers.usage(":root canvas pdf-close <file>".trim());
            return;
        }
        Handlers.cling(c, "(gPad->Print(\"" + w[0] + "]\"), std::string(\"" + w[0] + " closed\"))");
    }

    public static void rootCanvasModified(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root canvas modified"));
        Handlers.cling(c, "(gPad->Modified(), gPad->Update(), std::string(\"redrawn\"))");
    }

    public static void rootGeomGdml(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root geom gdml"));
        if (w.length < 1) {
            Handlers.usage(":root geom gdml <file>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TGeoManager *g = TGeoManager::Import(\"" + w[0] + "\"); if (g == nullptr) { return std::string(\"ERROR: cannot read " + w[0] + "\"); } return std::string(g->GetName()); }()");
    }

    public static void rootGeomTop(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root geom top"));
        Handlers.cling(c, "gGeoManager->GetTopVolume()->GetName()");
    }

    public static void rootGeomVolumes(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root geom volumes"));
        Handlers.cling(c, "(gGeoManager->GetListOfVolumes()->Print(), std::string(\"\"))");
    }

    public static void rootGeomMaterials(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root geom materials"));
        Handlers.cling(c, "(gGeoManager->GetListOfMaterials()->Print(), std::string(\"\"))");
    }

    public static void rootGeomMedia(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root geom media"));
        Handlers.cling(c, "(gGeoManager->GetListOfMedia()->Print(), std::string(\"\"))");
    }

    public static void rootGeomNodes(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root geom nodes"));
        if (w.length < 1) {
            Handlers.usage(":root geom nodes <volume>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TGeoVolume *v = gGeoManager->GetVolume(\"" + w[0] + "\"); if (v == nullptr) { return std::string(\"ERROR: no volume called " + w[0] + "\"); } if (v->GetNodes() == nullptr) { return std::string(\"" + w[0] + " holds nothing\"); } v->GetNodes()->Print(); return std::string(\"\"); }()");
    }

    public static void rootGeomShape(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root geom shape"));
        if (w.length < 1) {
            Handlers.usage(":root geom shape <volume>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TGeoVolume *v = gGeoManager->GetVolume(\"" + w[0] + "\"); if (v == nullptr) { return std::string(\"ERROR: no volume called " + w[0] + "\"); } v->InspectShape(); return std::string(\"\"); }()");
    }

    public static void rootGeomOverlaps(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root geom overlaps"));
        Handlers.cling(c, "(gGeoManager->CheckOverlaps(" + (w.length > 0 ? w[0] : "0.0001") + "), gGeoManager->PrintOverlaps(), std::string(\"\"))");
    }

    public static void rootGeomFind(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root geom find"));
        if (w.length < 3) {
            Handlers.usage(":root geom find <x> <y> <z>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TGeoNode *n = gGeoManager->FindNode(" + Handlers.csv(Handlers.join(w, 0)) + "); if (n == nullptr) { return std::string(\"outside the geometry\"); } return std::string(gGeoManager->GetPath()); }()");
    }

    public static void rootGeomWeight(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root geom weight"));
        Handlers.cling(c, "gGeoManager->Weight(" + (w.length > 0 ? w[0] : "0.01") + ")");
    }

    public static void rootGeomClose(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root geom close"));
        Handlers.cling(c, "(gGeoManager->CloseGeometry(), std::string(\"closed\"))");
    }

}

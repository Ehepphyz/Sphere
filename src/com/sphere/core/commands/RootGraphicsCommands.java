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
            Handlers.usage(":root canvas save <file.png|tiff|svg|pdf|root|C>");
            return;
        }
        final java.io.File target = Handlers.resolve(a);
        Handlers.cling(c, "gPad->SaveAs(\"" + forCling(target) + "\")");
        showSaved(target);
    }

    /**
     * A path as the engine must be given it. The engine runs in its own folder,
     * so a name alone landed there rather than in the one the console is in,
     * where the Plots tab looks and the user expected it.
     */
    static String forCling(java.io.File file) {
        return file.getAbsolutePath().replace('\\', '/').replace("\"", "\\\"");
    }

    /** A picture ROOT just wrote goes to the Plots tab, where it can be edited. */
    static void showSaved(java.io.File file) {
        if (com.sphere.components.imaging.ImageFileIO.isImage(file)) {
            com.sphere.components.rootview.RootPlotsPanel.instance().showImage(file);
        } else if (file.isFile()) {
            AppLogger.result(file.getName() + " written; the Plots tab shows png, jpg, tiff, gif, bmp and svg.");
        }
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

    /** <name> <expression> */
    public static void rootCutNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root cut new"));
        if (w.length < 2) {
            Handlers.usage(":root cut new <name> <expression>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TCut>(\"" + w[0] + "\", new TCut(\"" + w[0] + "\", \"" + Handlers.join(w, 1) + "\"), \"TCut\")");
    }

    /** <out> <a> <b> */
    public static void rootCutAnd(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root cut and"));
        if (w.length < 3) {
            Handlers.usage(":root cut and <out> <a> <b>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TCut>(\"" + w[0] + "\", new TCut(*SphereBridge::Held<TCut>(\"" + w[1] + "\") && *SphereBridge::Held<TCut>(\"" + w[2] + "\")), \"TCut\")");
    }

    /** <out> <a> <b> */
    public static void rootCutOr(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root cut or"));
        if (w.length < 3) {
            Handlers.usage(":root cut or <out> <a> <b>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TCut>(\"" + w[0] + "\", new TCut(*SphereBridge::Held<TCut>(\"" + w[1] + "\") || *SphereBridge::Held<TCut>(\"" + w[2] + "\")), \"TCut\")");
    }

    /** <out> <a> */
    public static void rootCutNot(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root cut not"));
        if (w.length < 2) {
            Handlers.usage(":root cut not <out> <a>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TCut>(\"" + w[0] + "\", new TCut(!(*SphereBridge::Held<TCut>(\"" + w[1] + "\"))), \"TCut\")");
    }

    /** <name> */
    public static void rootCutShow(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root cut show"));
        if (w.length < 1) {
            Handlers.usage(":root cut show <name>");
            return;
        }
        Handlers.cling(c, "std::string(SphereBridge::Held<TCut>(\"" + w[0] + "\")->GetTitle())");
    }

    /** <tree> <cut> <expr> */
    public static void rootCutApply(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root cut apply"));
        if (w.length < 3) {
            Handlers.usage(":root cut apply <tree> <cut> <expr>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TTree>(\"" + w[0] + "\", \"TTree\")->Draw(\"" + Handlers.join(w, 2) + "\", SphereBridge::Held<TCut>(\"" + w[1] + "\")->GetTitle())");
    }

    /** <tree> <cut> */
    public static void rootCutCount(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root cut count"));
        if (w.length < 2) {
            Handlers.usage(":root cut count <tree> <cut>");
            return;
        }
        Handlers.cling(c, "(long) SphereBridge::Need<TTree>(\"" + w[0] + "\", \"TTree\")->GetEntries(SphereBridge::Held<TCut>(\"" + w[1] + "\")->GetTitle())");
    }

    /** <name> <points> */
    public static void rootGraph2dNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root graph2d new"));
        if (w.length < 2) {
            Handlers.usage(":root graph2d new <name> <points>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TGraph2D>(\"" + w[0] + "\", new TGraph2D(" + w[1] + "), \"TGraph2D\")");
    }

    /** <name> <i> <x> <y> <z> */
    public static void rootGraph2dSet(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root graph2d set"));
        if (w.length < 5) {
            Handlers.usage(":root graph2d set <name> <i> <x> <y> <z>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TGraph2D>(\"" + w[0] + "\")->SetPoint(" + w[1] + ", " + w[2] + ", " + w[3] + ", " + w[4] + ")");
    }

    /** <name> [surf1|tri|colz|p0] */
    public static void rootGraph2dDraw(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root graph2d draw"));
        if (w.length < 1) {
            Handlers.usage(":root graph2d draw <name> [surf1|tri|colz|p0]");
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TGraph2D>(\"" + w[0] + "\")->Draw(\"" + (w.length > 1 ? Handlers.join(w, 1) : "surf1") + "\")");
    }

    /** <name> <x> <y> */
    public static void rootGraph2dInterp(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root graph2d interpolate"));
        if (w.length < 3) {
            Handlers.usage(":root graph2d interpolate <name> <x> <y>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TGraph2D>(\"" + w[0] + "\")->Interpolate(" + w[1] + ", " + w[2] + ")");
    }

    /** <name> */
    public static void rootGraph2dPoints(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root graph2d points"));
        if (w.length < 1) {
            Handlers.usage(":root graph2d points <name>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TGraph2D>(\"" + w[0] + "\")->GetN()");
    }

    /** <name> */
    public static void rootGraph2dHist(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root graph2d hist"));
        if (w.length < 1) {
            Handlers.usage(":root graph2d hist <name>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TGraph2D>(\"" + w[0] + "\")->GetHistogram()->GetName()");
    }

    /** <hist> <x|y> [format] */
    public static void rootAxisTime(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root axis time"));
        if (w.length < 2) {
            Handlers.usage(":root axis time <hist> <x|y> [format]");
            return;
        }
        Handlers.cling(c, "[]{ TH1 *h = SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\"); TAxis *a = std::string(\"" + w[1] + "\") == \"y\" ? h->GetYaxis() : h->GetXaxis(); a->SetTimeDisplay(1); a->SetTimeFormat(\"" + (w.length > 2 ? Handlers.join(w, 2) : "%d/%m/%y") + "\"); return std::string(a->GetTimeFormat()); }()");
    }

    /** <hist> <bin> <text> */
    public static void rootAxisLabel(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root axis label"));
        if (w.length < 3) {
            Handlers.usage(":root axis label <hist> <bin> <text>");
            return;
        }
        Handlers.cling(c, "[]{ TH1 *h = SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\"); h->GetXaxis()->SetBinLabel(" + w[1] + ", \"" + Handlers.join(w, 2) + "\"); return std::string(h->GetXaxis()->GetBinLabel(" + w[1] + ")); }()");
    }

    /** <hist> */
    public static void rootAxisDeflate(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root axis deflate"));
        if (w.length < 1) {
            Handlers.usage(":root axis deflate <hist>");
            return;
        }
        Handlers.cling(c, "[]{ SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->LabelsDeflate(\"X\"); return std::string(\"deflated\"); }()");
    }

    /** <hist> <x|y> <n> */
    public static void rootAxisDivisions(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root axis divisions"));
        if (w.length < 3) {
            Handlers.usage(":root axis divisions <hist> <x|y> <n>");
            return;
        }
        Handlers.cling(c, "[]{ TH1 *h = SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\"); TAxis *a = std::string(\"" + w[1] + "\") == \"y\" ? h->GetYaxis() : h->GetXaxis(); a->SetNdivisions(" + w[2] + "); return a->GetNdivisions(); }()");
    }

    /** <hist> <x|y> */
    public static void rootAxisMoreLog(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root axis moreloglabels"));
        if (w.length < 2) {
            Handlers.usage(":root axis moreloglabels <hist> <x|y>");
            return;
        }
        Handlers.cling(c, "[]{ TH1 *h = SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\"); TAxis *a = std::string(\"" + w[1] + "\") == \"y\" ? h->GetYaxis() : h->GetXaxis(); a->SetMoreLogLabels(); a->SetNoExponent(); return std::string(\"more labels on the log axis\"); }()");
    }

    /** <hist> <x|y> <value> */
    public static void rootAxisTitleOffset(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root axis titleoffset"));
        if (w.length < 3) {
            Handlers.usage(":root axis titleoffset <hist> <x|y> <value>");
            return;
        }
        Handlers.cling(c, "[]{ TH1 *h = SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\"); TAxis *a = std::string(\"" + w[1] + "\") == \"y\" ? h->GetYaxis() : h->GetXaxis(); a->SetTitleOffset(" + w[2] + "); return a->GetTitleOffset(); }()");
    }

    /** <x1> <y1> <x2> <y2> <wmin> <wmax> <ndiv> [options] */
    public static void rootAxisNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root axis new"));
        if (w.length < 7) {
            Handlers.usage(":root axis new <x1> <y1> <x2> <y2> <wmin> <wmax> <ndiv> [options]");
            return;
        }
        Handlers.cling(c, "[]{ TGaxis *a = new TGaxis(" + w[0] + ", " + w[1] + ", " + w[2] + ", " + w[3] + ", " + w[4] + ", " + w[5] + ", " + w[6] + ", \"" + (w.length > 7 ? Handlers.join(w, 7) : "") + "\"); a->Draw(); return std::string(\"axis drawn\"); }()");
    }

    /** <index> <r> <g> <b> */
    public static void rootColorNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root color new"));
        if (w.length < 4) {
            Handlers.usage(":root color new <index> <r> <g> <b>");
            return;
        }
        Handlers.cling(c, "[]{ new TColor(" + w[0] + ", " + w[1] + ", " + w[2] + ", " + w[3] + "); return std::string(\"color " + w[0] + " defined\"); }()");
    }

    /** <r> <g> <b> */
    public static void rootColorFind(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root color find"));
        if (w.length < 3) {
            Handlers.usage(":root color find <r> <g> <b>");
            return;
        }
        Handlers.cling(c, "TColor::GetColor((Float_t) " + w[0] + ", (Float_t) " + w[1] + ", (Float_t) " + w[2] + ")");
    }

    /** <index> */
    public static void rootColorShow(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root color show"));
        if (w.length < 1) {
            Handlers.usage(":root color show <index>");
            return;
        }
        Handlers.cling(c, "[]{ TColor *c = gROOT->GetColor(" + w[0] + "); if (c == nullptr) { return std::string(\"no color " + w[0] + "\"); } return std::string(c->GetName()) + \"  r \" + std::to_string(c->GetRed()) + \"  g \" + std::to_string(c->GetGreen()) + \"  b \" + std::to_string(c->GetBlue()); }()");
    }

    /** <index> <alpha> */
    public static void rootColorTransparent(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root color transparent"));
        if (w.length < 2) {
            Handlers.usage(":root color transparent <index> <alpha>");
            return;
        }
        Handlers.cling(c, "TColor::GetColorTransparent(" + w[0] + ", " + w[1] + ")");
    }

    /** <index> */
    public static void rootColorBright(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root color bright"));
        if (w.length < 1) {
            Handlers.usage(":root color bright <index>");
            return;
        }
        Handlers.cling(c, "TColor::GetColorBright(" + w[0] + ")");
    }

    /** <index> */
    public static void rootColorDark(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root color dark"));
        if (w.length < 1) {
            Handlers.usage(":root color dark <index>");
            return;
        }
        Handlers.cling(c, "TColor::GetColorDark(" + w[0] + ")");
    }

    /** <h> <l> <s> */
    public static void rootColorHls(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root color hls"));
        if (w.length < 3) {
            Handlers.usage(":root color hls <h> <l> <s>");
            return;
        }
        Handlers.cling(c, "[]{ Float_t r = 0, g = 0, b = 0; TColor::HLS2RGB((Float_t) " + w[0] + ", (Float_t) " + w[1] + ", (Float_t) " + w[2] + ", r, g, b); return TColor::GetColor(r, g, b); }()");
    }

    /** <name> <top> <bottom> */
    public static void rootRatioNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root ratio new"));
        if (w.length < 3) {
            Handlers.usage(":root ratio new <name> <top> <bottom>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TRatioPlot>(\"" + w[0] + "\", new TRatioPlot(SphereBridge::Need<TH1>(\"" + w[1] + "\", \"TH1\"), SphereBridge::Need<TH1>(\"" + w[2] + "\", \"TH1\")), \"TRatioPlot\")");
    }

    /** <name> [divsym|errprop|confint] */
    public static void rootRatioDraw(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root ratio draw"));
        if (w.length < 1) {
            Handlers.usage(":root ratio draw <name> [divsym|errprop|confint]");
            return;
        }
        Handlers.cling(c, "[]{ SphereBridge::Held<TRatioPlot>(\"" + w[0] + "\")->Draw(\"" + (w.length > 1 ? Handlers.join(w, 1) : "") + "\"); if (gPad != nullptr) { gPad->Update(); } return std::string(\"drawn\"); }()");
    }

    /** <name> <low> <high> */
    public static void rootRatioRange(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root ratio range"));
        if (w.length < 3) {
            Handlers.usage(":root ratio range <name> <low> <high>");
            return;
        }
        Handlers.cling(c, "[]{ TRatioPlot *r = SphereBridge::Held<TRatioPlot>(\"" + w[0] + "\"); r->GetLowerRefGraph()->SetMinimum(" + w[1] + "); r->GetLowerRefGraph()->SetMaximum(" + w[2] + "); return std::string(\"lower panel set\"); }()");
    }

    /** <name> <values...> */
    public static void rootRatioGrid(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root ratio grid"));
        if (w.length < 2) {
            Handlers.usage(":root ratio grid <name> <values...>");
            return;
        }
        Handlers.cling(c, "[]{ std::vector<double> at = { " + Handlers.csv(Handlers.join(w, 1)) + " }; SphereBridge::Held<TRatioPlot>(\"" + w[0] + "\")->SetGridlines(at); return std::string(\"gridlines set\"); }()");
    }

    /** <name> <fraction> */
    public static void rootRatioMargin(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root ratio margin"));
        if (w.length < 2) {
            Handlers.usage(":root ratio margin <name> <fraction>");
            return;
        }
        Handlers.cling(c, "[]{ SphereBridge::Held<TRatioPlot>(\"" + w[0] + "\")->SetSeparationMargin(" + w[1] + "); return std::string(\"margin set\"); }()");
    }

}

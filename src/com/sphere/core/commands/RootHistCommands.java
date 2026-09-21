package com.sphere.core.commands;

import com.sphere.utils.AppLogger;

/**
 * Histograms, graphs, functions, fits and maths.
 *
 * Split out of Handlers, which had grown to hold every backend at once. The
 * helpers these handlers share -- the argument readers, the interpreter call,
 * the named-handle builders -- stay in Handlers and are called through it.
 */
public final class RootHistCommands {

    private RootHistCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static void rootDumpHistBins(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist bins");
        if (a.isEmpty()) {
            Handlers.usage(":root hist bins <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->Print(\"all\")");
    }

    public static void rootEffDraw(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root eff draw"));
        if (w.length < 1) {
            Handlers.usage(":root eff draw <name> [option]".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TEfficiency") + "->Draw(\"" + (w.length > 1 ? w[1] : "AP") + "\"), std::string(\"drawn\"))");
    }

    public static void rootEffErrLow(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root eff errlow"));
        if (w.length < 2) {
            Handlers.usage(":root eff errlow <name> <bin>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TEfficiency") + "->GetEfficiencyErrorLow(" + Handlers.asInt(w[1], 1) + ")");
    }

    public static void rootEffErrUp(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root eff errup"));
        if (w.length < 2) {
            Handlers.usage(":root eff errup <name> <bin>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TEfficiency") + "->GetEfficiencyErrorUp(" + Handlers.asInt(w[1], 1) + ")");
    }

    public static void rootEffFill(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root eff fill"));
        if (w.length < 3) {
            Handlers.usage(":root eff fill <name> <passed> <x>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TEfficiency") + "->Fill(" + Handlers.csv(Handlers.join(w, 1)) + "), std::string(\"filled\"))");
    }

    public static void rootEffNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root eff new"));
        if (w.length < 4) {
            Handlers.usage(":root eff new <name> <bins> <low> <high>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TEfficiency", "new TEfficiency(\"" + w[0] + "\",\"" + w[0] + "\"," + Handlers.csv(Handlers.join(w, 1)) + ")"));
    }

    public static void rootEffValue(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root eff value"));
        if (w.length < 2) {
            Handlers.usage(":root eff value <name> <bin>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TEfficiency") + "->GetEfficiency(" + Handlers.asInt(w[1], 1) + ")");
    }

    public static void rootFitExpr(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root fit expr");
        if (a.isEmpty()) {
            Handlers.usage(":root fit expr <obj> <f>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->Fit(\"" + a1 + "\")");
    }

    public static void rootFitFunction(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root fit function");
        if (a.isEmpty()) {
            Handlers.usage(":root fit function <obj> <f>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->Fit(\"" + a1 + "\")");
    }

    public static void rootFitParams(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root fit params");
        if (a.isEmpty()) {
            Handlers.usage(":root fit params <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "" + Handlers.obj("TF1", a0) + "->Print()");
    }

    public static void rootFitReset(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root fit reset"));
        if (name.isEmpty()) {
            Handlers.usage(":root fit reset <hist>");
            return;
        }
        // gMinuit stays null on a ROOT 6 build, whose default fitter is Minuit2.
        // What a reset means here is dropping the fits drawn on the histogram.
        Handlers.cling(c, "(" + Handlers.obj("TH1", name)
               + "->GetListOfFunctions()->Clear(), std::string(\"fits cleared\"))");
    }

    public static void rootFormulaEval(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root formula eval"));
        if (w.length < 2) {
            Handlers.usage(":root formula eval <name> <x>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TFormula") + "->Eval(" + w[1] + ")");
    }

    public static void rootFormulaF2(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root formula f2"));
        if (w.length < 6) {
            Handlers.usage(":root formula f2 <name> <expr> <xmin> <xmax> <ymin> <ymax>".trim());
            return;
        }
        Handlers.cling(c, "(new TF2(\"" + w[0] + "\", \"" + w[1] + "\", " + Handlers.csv(Handlers.join(w, 2)) + "), std::string(\"" + w[0] + " created\"))");
    }

    public static void rootFormulaF3(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root formula f3"));
        if (w.length < 8) {
            Handlers.usage(":root formula f3 <name> <expr> <xmin> <xmax> <ymin> <ymax> <zmin> <zmax>".trim());
            return;
        }
        Handlers.cling(c, "(new TF3(\"" + w[0] + "\", \"" + w[1] + "\", " + Handlers.csv(Handlers.join(w, 2)) + "), std::string(\"" + w[0] + " created\"))");
    }

    public static void rootFormulaNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root formula new"));
        if (w.length < 2) {
            Handlers.usage(":root formula new <name> <expression>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TFormula", "new TFormula(\"" + w[0] + "\", \"" + Handlers.join(w, 1) + "\")"));
    }

    public static void rootFormulaParams(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root formula params"));
        if (w.length < 1) {
            Handlers.usage(":root formula params <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TFormula") + "->GetNpar()");
    }

    public static void rootFuncNew(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root func new");
        if (a.isEmpty()) {
            Handlers.usage(":root func new <name> <f>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "new TF1(\"" + a0 + "\",\"" + a1 + "\",0,1)");
    }

    public static void rootGerrDraw(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gerr draw"));
        if (w.length < 1) {
            Handlers.usage(":root gerr draw <name> [option]".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TGraphErrors") + "->Draw(\"" + (w.length > 1 ? w[1] : "AP") + "\"), std::string(\"drawn\"))");
    }

    public static void rootGerrError(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gerr error"));
        if (w.length < 4) {
            Handlers.usage(":root gerr error <name> <point> <ex> <ey>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TGraphErrors") + "->SetPointError(" + Handlers.csv(Handlers.join(w, 1)) + "), std::string(\"set\"))");
    }

    public static void rootGerrFit(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gerr fit"));
        if (w.length < 2) {
            Handlers.usage(":root gerr fit <name> <function>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TGraphErrors") + "->Fit(\"" + w[1] + "\")");
    }

    public static void rootGerrNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gerr new"));
        if (w.length < 2) {
            Handlers.usage(":root gerr new <name> <points>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TGraphErrors", "new TGraphErrors(" + Handlers.asInt(w[1], 1) + ")"));
    }

    public static void rootGerrSet(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root gerr set"));
        if (w.length < 4) {
            Handlers.usage(":root gerr set <name> <point> <x> <y>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TGraphErrors") + "->SetPoint(" + Handlers.csv(Handlers.join(w, 1)) + "), std::string(\"set\"))");
    }

    public static void rootGetHist(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist get");
        if (a.isEmpty()) {
            Handlers.usage(":root hist get <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->ClassName()");
    }

    public static void rootGraphAdd(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root graph add");
        final String name = Handlers.head(a);
        final String rest = Handlers.csv(Handlers.tail(a));
        if (name.isEmpty() || rest.isEmpty()) {
            Handlers.usage(":root graph add <name> <point> <x> <y>");
            return;
        }
        Handlers.cling(c, Handlers.obj("TGraph", name) + "->SetPoint(" + rest + ")");
    }

    public static void rootGraphDraw(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root graph draw");
        if (a.isEmpty()) {
            Handlers.usage(":root graph draw <name> [opt]");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TGraph", a0) + "->Draw(\"" + a1 + "\")");
    }

    public static void rootGraphFit(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root graph fit");
        if (a.isEmpty()) {
            Handlers.usage(":root graph fit <name> <f>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TGraph", a0) + "->Fit(\"" + a1 + "\")");
    }

    public static void rootGraphPoints(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root graph points");
        if (a.isEmpty()) {
            Handlers.usage(":root graph points <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "" + Handlers.obj("TGraph", a0) + "->Print()");
    }

    public static void rootHistAdd(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist add"));
        if (w.length < 2) {
            Handlers.usage(":root hist add <into> <from> [factor]".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TH1", w[0]) + "->Add(" + Handlers.obj("TH1", w[1]) + ", " + (w.length > 2 ? w[2] : "1") + "), std::string(\"added\"))");
    }

    public static void rootHistAxis(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist axis"));
        if (w.length < 3) {
            Handlers.usage(":root hist axis <name> <x|y|z> <text>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TH1", w[0]) + "->Get" + w[1].toUpperCase(java.util.Locale.ROOT) + "axis()->SetTitle(\"" + Handlers.join(w, 2) + "\"), std::string(\"set\"))");
    }

    public static void rootHistClone(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist clone");
        if (a.isEmpty()) {
            Handlers.usage(":root hist clone <name> <new>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->Clone(\"" + a1 + "\")");
    }

    public static void rootHistColor(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist color"));
        if (w.length < 2) {
            Handlers.usage(":root hist color <name> <n>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TH1", w[0]) + "->SetLineColor(" + Handlers.asInt(w[1], 1) + "), std::string(\"set\"))");
    }

    public static void rootHistContent(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist content"));
        if (w.length < 2) {
            Handlers.usage(":root hist content <name> <bin>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TH1", w[0]) + "->GetBinContent(" + Handlers.asInt(w[1], 1) + ")");
    }

    public static void rootHistDivide(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist divide"));
        if (w.length < 2) {
            Handlers.usage(":root hist divide <into> <by>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TH1", w[0]) + "->Divide(" + Handlers.obj("TH1", w[1]) + "), std::string(\"divided\"))");
    }

    public static void rootHistDraw(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist draw");
        if (a.isEmpty()) {
            Handlers.usage(":root hist draw <name> [opt]");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->Draw(\"" + a1 + "\")");
    }

    public static void rootHistEdge(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist edge"));
        if (w.length < 2) {
            Handlers.usage(":root hist edge <name> <bin>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TH1", w[0]) + "->GetBinLowEdge(" + Handlers.asInt(w[1], 1) + ")");
    }

    public static void rootHistEntries(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist entries"));
        if (w.length < 1) {
            Handlers.usage(":root hist entries <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TH1", w[0]) + "->GetEntries()");
    }

    public static void rootHistError(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist error"));
        if (w.length < 2) {
            Handlers.usage(":root hist error <name> <bin>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TH1", w[0]) + "->GetBinError(" + Handlers.asInt(w[1], 1) + ")");
    }

    public static void rootHistFill(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root hist fill");
        final String name = Handlers.head(a);
        final String rest = Handlers.csv(Handlers.tail(a));
        if (name.isEmpty() || rest.isEmpty()) {
            Handlers.usage(":root hist fill <name> <value> [weight]");
            return;
        }
        Handlers.cling(c, Handlers.obj("TH1", name) + "->Fill(" + rest + ")");
    }

    public static void rootHistFill2(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist fill2"));
        if (w.length < 3) {
            Handlers.usage(":root hist fill2 <name> <x> <y> [weight]".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TH2", w[0]) + "->Fill(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootHistFindBin(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist findbin"));
        if (w.length < 2) {
            Handlers.usage(":root hist findbin <name> <x>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TH1", w[0]) + "->FindBin(" + w[1] + ")");
    }

    public static void rootHistFit(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist fit");
        if (a.isEmpty()) {
            Handlers.usage(":root hist fit <name> <f>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->Fit(\"" + a1 + "\")");
    }

    public static void rootHistIntegral(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist integral");
        if (a.isEmpty()) {
            Handlers.usage(":root hist integral <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->Integral()");
    }

    public static void rootHistKs(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist ks"));
        if (w.length < 2) {
            Handlers.usage(":root hist ks <a> <b>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TH1", w[0]) + "->KolmogorovTest(" + Handlers.obj("TH1", w[1]) + ")");
    }

    public static void rootHistList(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gDirectory->GetList()->Print()");
    }

    public static void rootHistMax(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist max");
        if (a.isEmpty()) {
            Handlers.usage(":root hist max <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->GetMaximum()");
    }

    public static void rootHistMean(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist mean"));
        if (w.length < 1) {
            Handlers.usage(":root hist mean <name> [axis]".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TH1", w[0]) + "->GetMean(" + Handlers.asInt((w.length > 1 ? w[1] : "1"), 1) + ")");
    }

    public static void rootHistMin(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist min");
        if (a.isEmpty()) {
            Handlers.usage(":root hist min <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->GetMinimum()");
    }

    public static void rootHistMultiply(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist multiply"));
        if (w.length < 2) {
            Handlers.usage(":root hist multiply <into> <by>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TH1", w[0]) + "->Multiply(" + Handlers.obj("TH1", w[1]) + "), std::string(\"multiplied\"))");
    }

    public static void rootHistNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist new"));
        if (w.length < 4) {
            Handlers.usage(":root hist new <name> <bins> <low> <high>".trim());
            return;
        }
        Handlers.cling(c, "(new TH1D(\"" + w[0] + "\",\"" + w[0] + "\"," + Handlers.csv(Handlers.join(w, 1)) + "), std::string(\"" + w[0] + " created\"))");
    }

    public static void rootHistNew2(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist new2"));
        if (w.length < 7) {
            Handlers.usage(":root hist new2 <name> <xbins> <xlow> <xhigh> <ybins> <ylow> <yhigh>".trim());
            return;
        }
        Handlers.cling(c, "(new TH2D(\"" + w[0] + "\",\"" + w[0] + "\"," + Handlers.csv(Handlers.join(w, 1)) + "), std::string(\"" + w[0] + " created\"))");
    }

    public static void rootHistNew3(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist new3"));
        if (w.length < 10) {
            Handlers.usage(":root hist new3 <name> <xbins> <xlow> <xhigh> <ybins> <ylow> <yhigh> <zbins> <zlow> <zhigh>".trim());
            return;
        }
        Handlers.cling(c, "(new TH3D(\"" + w[0] + "\",\"" + w[0] + "\"," + Handlers.csv(Handlers.join(w, 1)) + "), std::string(\"" + w[0] + " created\"))");
    }

    public static void rootHistNormalize(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist normalize"));
        if (w.length < 1) {
            Handlers.usage(":root hist normalize <name>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TH1", w[0]) + "->Scale(1.0 / " + Handlers.obj("TH1", w[0]) + "->Integral()), std::string(\"normalized\"))");
    }

    public static void rootHistProject(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist project");
        if (a.isEmpty()) {
            Handlers.usage(":root hist project <name> <axis>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TH2", a0) + "->ProjectionX(\"" + a1 + "\")");
    }

    public static void rootHistRange(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist range"));
        if (w.length < 4) {
            Handlers.usage(":root hist range <name> <x|y|z> <low> <high>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TH1", w[0]) + "->Get" + w[1].toUpperCase(java.util.Locale.ROOT) + "axis()->SetRangeUser(" + Handlers.csv(Handlers.join(w, 2)) + "), std::string(\"set\"))");
    }

    public static void rootHistRebin(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist rebin");
        if (a.isEmpty()) {
            Handlers.usage(":root hist rebin <name> <n>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->Rebin(" + a1 + ")");
    }

    public static void rootHistReset(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist reset");
        if (a.isEmpty()) {
            Handlers.usage(":root hist reset <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->Reset()");
    }

    public static void rootHistRms(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist rms"));
        if (w.length < 1) {
            Handlers.usage(":root hist rms <name> [axis]".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TH1", w[0]) + "->GetStdDev(" + Handlers.asInt((w.length > 1 ? w[1] : "1"), 1) + ")");
    }

    public static void rootHistSave(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist save"));
        if (w.length < 2) {
            Handlers.usage(":root hist save <name> <file>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TH1", w[0]) + "->SaveAs(\"" + w[1] + "\"), std::string(\"saved\"))");
    }

    public static void rootHistScale(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist scale");
        if (a.isEmpty()) {
            Handlers.usage(":root hist scale <name> <f>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->Scale(" + a1 + ")");
    }

    public static void rootHistSetbin(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root hist setbin");
        final String name = Handlers.head(a);
        final String rest = Handlers.csv(Handlers.tail(a));
        if (name.isEmpty() || rest.isEmpty()) {
            Handlers.usage(":root hist setbin <name> <bin> <value>");
            return;
        }
        Handlers.cling(c, Handlers.obj("TH1", name) + "->SetBinContent(" + rest + ")");
    }

    public static void rootHistSmooth(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist smooth");
        if (a.isEmpty()) {
            Handlers.usage(":root hist smooth <name>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "" + Handlers.obj("TH1", a0) + "->Smooth()");
    }

    public static void rootHistStatbox(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root hist statbox");
        if (a.isEmpty()) {
            Handlers.usage(":root hist statbox <0|1>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gStyle->SetOptStat(" + a0 + ")");
    }

    public static void rootHistTitle(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist title"));
        if (w.length < 2) {
            Handlers.usage(":root hist title <name> <text>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TH1", w[0]) + "->SetTitle(\"" + Handlers.join(w, 1) + "\"), std::string(\"set\"))");
    }

    public static void rootMathBinomial(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math binomial"));
        if (w.length < 2) {
            Handlers.usage(":root math binomial <n> <k>".trim());
            return;
        }
        Handlers.cling(c, "TMath::Binomial(" + Handlers.csv(Handlers.join(w, 0)) + ")");
    }

    public static void rootMathDeriv(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root math deriv");
        if (a.isEmpty()) {
            Handlers.usage(":root math deriv <name> <x>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TF1", a0) + "->Derivative(" + a1 + ")");
    }

    public static void rootMathErf(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math erf"));
        if (w.length < 1) {
            Handlers.usage(":root math erf <x>".trim());
            return;
        }
        Handlers.cling(c, "TMath::Erf(" + w[0] + ")");
    }

    public static void rootMathEval(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root math eval");
        if (a.isEmpty()) {
            Handlers.usage(":root math eval <name> <x>");
            return;
        }
        String a0 = Handlers.head(a);
        String a1 = Handlers.tail(a);
        Handlers.cling(c, "" + Handlers.obj("TF1", a0) + "->Eval(" + a1 + ")");
    }

    public static void rootMathGaus(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math gaus"));
        if (w.length < 3) {
            Handlers.usage(":root math gaus <x> <mean> <sigma>".trim());
            return;
        }
        Handlers.cling(c, "TMath::Gaus(" + Handlers.csv(Handlers.join(w, 0)) + ")");
    }

    public static void rootMathIntegral(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root math integral");
        final String name = Handlers.head(a);
        final String rest = Handlers.csv(Handlers.tail(a));
        if (name.isEmpty() || rest.isEmpty()) {
            Handlers.usage(":root math integral <name> <from> <to>");
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", name) + "->Integral(" + rest + ")");
    }

    public static void rootMathLandau(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math landau"));
        if (w.length < 3) {
            Handlers.usage(":root math landau <x> <mpv> <sigma>".trim());
            return;
        }
        Handlers.cling(c, "TMath::Landau(" + Handlers.csv(Handlers.join(w, 0)) + ")");
    }

    public static void rootMathList(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math list"));
        if (w.length < 2) {
            Handlers.usage(":root math list <v> <v> ...".trim());
            return;
        }
        Handlers.cling(c, "[]{ double v[] = {" + Handlers.csv(Handlers.join(w, 0)) + "}; const int n = " + w.length + "; return std::string(\"mean \") + std::to_string(TMath::Mean(n, v)) + \"  rms \" + std::to_string(TMath::RMS(n, v)) + \"  median \" + std::to_string(TMath::Median(n, v)); }()");
    }

    public static void rootMathPoisson(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math poisson"));
        if (w.length < 2) {
            Handlers.usage(":root math poisson <x> <mean>".trim());
            return;
        }
        Handlers.cling(c, "TMath::Poisson(" + Handlers.csv(Handlers.join(w, 0)) + ")");
    }

    public static void rootMathProb(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math prob"));
        if (w.length < 2) {
            Handlers.usage(":root math prob <chi2> <ndf>".trim());
            return;
        }
        Handlers.cling(c, "TMath::Prob(" + Handlers.csv(Handlers.join(w, 0)) + ")");
    }

    public static void rootMathQuantile(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root math quantile"));
        if (w.length < 1) {
            Handlers.usage(":root math quantile <p>".trim());
            return;
        }
        Handlers.cling(c, "TMath::NormQuantile(" + w[0] + ")");
    }

    public static void rootMgraphAdd(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root mgraph add"));
        if (w.length < 2) {
            Handlers.usage(":root mgraph add <multigraph> <graph>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TMultiGraph") + "->Add(" + Handlers.obj("TGraph", w[1]) + "), std::string(\"added\"))");
    }

    public static void rootMgraphDraw(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root mgraph draw"));
        if (w.length < 1) {
            Handlers.usage(":root mgraph draw <name> [option]".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TMultiGraph") + "->Draw(\"" + (w.length > 1 ? w[1] : "A") + "\"), std::string(\"drawn\"))");
    }

    public static void rootMgraphFit(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root mgraph fit"));
        if (w.length < 2) {
            Handlers.usage(":root mgraph fit <name> <function>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TMultiGraph") + "->Fit(\"" + w[1] + "\")");
    }

    public static void rootMgraphNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root mgraph new"));
        if (w.length < 1) {
            Handlers.usage(":root mgraph new <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TMultiGraph", "new TMultiGraph(\"" + w[0] + "\",\"" + w[0] + "\")"));
    }

    public static void rootPeaksAt(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root peaks at"));
        if (w.length < 2) {
            Handlers.usage(":root peaks at <name> <index>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TSpectrum") + "->GetPositionX()[" + Handlers.asInt(w[1], 0) + "]");
    }

    public static void rootPeaksBackground(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root peaks background"));
        if (w.length < 2) {
            Handlers.usage(":root peaks background <name> <hist> [iterations]".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TSpectrum") + "->Background(" + Handlers.obj("TH1", w[1]) + ", " + Handlers.asInt((w.length > 2 ? w[2] : "20"), 20) + ")->GetName()");
    }

    public static void rootPeaksCount(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root peaks count"));
        if (w.length < 1) {
            Handlers.usage(":root peaks count <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TSpectrum") + "->GetNPeaks()");
    }

    public static void rootPeaksNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root peaks new"));
        if (w.length < 1) {
            Handlers.usage(":root peaks new <name> [max]".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TSpectrum", "new TSpectrum(" + Handlers.asInt((w.length > 1 ? w[1] : "100"), 100) + ")"));
    }

    public static void rootPeaksSearch(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root peaks search"));
        if (w.length < 2) {
            Handlers.usage(":root peaks search <name> <hist> [sigma] [threshold]".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "TSpectrum") + "->Search(" + Handlers.obj("TH1", w[1]) + ", " + (w.length > 2 ? w[2] : "2") + ", \"\", " + (w.length > 3 ? w[3] : "0.05") + ")");
    }

    public static void rootProfDraw(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root prof draw"));
        if (w.length < 1) {
            Handlers.usage(":root prof draw <name> [option]".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TProfile", w[0]) + "->Draw(\"" + (w.length > 1 ? w[1] : "") + "\"), std::string(\"drawn\"))");
    }

    public static void rootProfError(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root prof error"));
        if (w.length < 2) {
            Handlers.usage(":root prof error <name> <bin>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TProfile", w[0]) + "->GetBinError(" + Handlers.asInt(w[1], 1) + ")");
    }

    public static void rootProfFill(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root prof fill"));
        if (w.length < 3) {
            Handlers.usage(":root prof fill <name> <x> <y>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TProfile", w[0]) + "->Fill(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootProfMean(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root prof mean"));
        if (w.length < 2) {
            Handlers.usage(":root prof mean <name> <bin>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TProfile", w[0]) + "->GetBinContent(" + Handlers.asInt(w[1], 1) + ")");
    }

    public static void rootProfNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root prof new"));
        if (w.length < 4) {
            Handlers.usage(":root prof new <name> <bins> <low> <high>".trim());
            return;
        }
        Handlers.cling(c, "(new TProfile(\"" + w[0] + "\",\"" + w[0] + "\"," + Handlers.csv(Handlers.join(w, 1)) + "), std::string(\"" + w[0] + " created\"))");
    }

    public static void rootProfReset(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root prof reset"));
        if (w.length < 1) {
            Handlers.usage(":root prof reset <name>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TProfile", w[0]) + "->Reset(), std::string(\"reset\"))");
    }

    public static void rootStackAdd(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root stack add"));
        if (w.length < 2) {
            Handlers.usage(":root stack add <stack> <hist>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "THStack") + "->Add(" + Handlers.obj("TH1", w[1]) + "), std::string(\"added\"))");
    }

    public static void rootStackCount(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root stack count"));
        if (w.length < 1) {
            Handlers.usage(":root stack count <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "THStack") + "->GetNhists()");
    }

    public static void rootStackDraw(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root stack draw"));
        if (w.length < 1) {
            Handlers.usage(":root stack draw <name> [option]".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "THStack") + "->Draw(\"" + (w.length > 1 ? w[1] : "") + "\"), std::string(\"drawn\"))");
    }

    public static void rootStackMax(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root stack max"));
        if (w.length < 1) {
            Handlers.usage(":root stack max <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.held(w[0], "THStack") + "->GetMaximum()");
    }

    public static void rootStackNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root stack new"));
        if (w.length < 1) {
            Handlers.usage(":root stack new <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "THStack", "new THStack(\"" + w[0] + "\",\"" + w[0] + "\")"));
    }

    // --- RooStats, sparse histograms, splines, density estimation, principal components, unfolding, decompositions, integrators, interpolators, FFT, XML and compression ---

    public static void rootSparseNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sparse new"));
        if (w.length < 5) {
            Handlers.usage(":root sparse new <name> <dimensions> <bins> <low> <high>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<THnSparseD>(\"" + w[0] + "\", " + "[]{ const int n = " + Handlers.asInt(w[1], 1) + "; std::vector<int> bins(n, " + Handlers.asInt(w[2], 1) + "); std::vector<double> lo(n, " + w[3] + "), hi(n, " + w[4] + "); return new THnSparseD(\"" + w[0] + "\", \"" + w[0] + "\", n, bins.data(), lo.data(), hi.data()); }()" + ", \"THnSparseD\")");
    }

    public static void rootSparseFill(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sparse fill"));
        if (w.length < 2) {
            Handlers.usage(":root sparse fill <name> <x> <x> ...".trim());
            return;
        }
        Handlers.cling(c, "[]{ double point[] = {" + Handlers.csv(Handlers.join(w, 1)) + "}; return " + "SphereBridge::Held<THnSparseD>(\"" + w[0] + "\")" + "->Fill(point); }()");
    }

    public static void rootSparseBins(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sparse bins"));
        if (w.length < 1) {
            Handlers.usage(":root sparse bins <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<THnSparseD>(\"" + w[0] + "\")" + "->GetNbins()");
    }

    public static void rootSparseEntries(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sparse entries"));
        if (w.length < 1) {
            Handlers.usage(":root sparse entries <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<THnSparseD>(\"" + w[0] + "\")" + "->GetEntries()");
    }

    public static void rootSparseProject(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root sparse project"));
        if (w.length < 3) {
            Handlers.usage(":root sparse project <name> <axis> <hist>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TH1D *h = " + "SphereBridge::Held<THnSparseD>(\"" + w[0] + "\")" + "->Projection(" + Handlers.asInt(w[1], 0) + "); h->SetName(\"" + w[2] + "\"); h->SetDirectory(gDirectory); return std::string(\"" + w[2] + " created\"); }()");
    }

    public static void rootSplineNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root spline new"));
        if (w.length < 2) {
            Handlers.usage(":root spline new <name> <hist>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TSpline3>(\"" + w[0] + "\", " + "new TSpline3(\"" + w[0] + "\", " + Handlers.obj("TH1", w[1]) + ")" + ", \"TSpline3\")");
    }

    public static void rootSplineEval(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root spline eval"));
        if (w.length < 2) {
            Handlers.usage(":root spline eval <name> <x>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TSpline3>(\"" + w[0] + "\")" + "->Eval(" + w[1] + ")");
    }

    public static void rootSplineDerivative(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root spline derivative"));
        if (w.length < 2) {
            Handlers.usage(":root spline derivative <name> <x>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TSpline3>(\"" + w[0] + "\")" + "->Derivative(" + w[1] + ")");
    }

    public static void rootSplineDraw(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root spline draw"));
        if (w.length < 1) {
            Handlers.usage(":root spline draw <name> [option]".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<TSpline3>(\"" + w[0] + "\")" + "->Draw(\"" + (w.length > 1 ? w[1] : "") + "\"), std::string(\"drawn\"))");
    }

    public static void rootKdeNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root kde new"));
        if (w.length < 3) {
            Handlers.usage(":root kde new <name> <value> <value> ...".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TKDE>(\"" + w[0] + "\", " + "[]{ std::vector<double> v = {" + Handlers.csv(Handlers.join(w, 1)) + "}; double lo = *std::min_element(v.begin(), v.end()); double hi = *std::max_element(v.begin(), v.end()); return new TKDE(v.size(), v.data(), lo, hi); }()" + ", \"TKDE\")");
    }

    public static void rootKdeEval(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root kde eval"));
        if (w.length < 2) {
            Handlers.usage(":root kde eval <name> <x>".trim());
            return;
        }
        Handlers.cling(c, "(*" + "SphereBridge::Held<TKDE>(\"" + w[0] + "\")" + ")(" + w[1] + ")");
    }

    public static void rootKdeIntegral(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root kde integral"));
        if (w.length < 3) {
            Handlers.usage(":root kde integral <name> <from> <to>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TKDE>(\"" + w[0] + "\")" + "->ComputeKernelIntegral(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootKdeDraw(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root kde draw"));
        if (w.length < 1) {
            Handlers.usage(":root kde draw <name> [option]".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<TKDE>(\"" + w[0] + "\")" + "->Draw(\"" + (w.length > 1 ? w[1] : "") + "\"), std::string(\"drawn\"))");
    }

    public static void rootUnfoldNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root unfold new"));
        if (w.length < 5) {
            Handlers.usage(":root unfold new <name> <measured> <reconstructed> <truth> <response>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TSVDUnfold>(\"" + w[0] + "\", " + "new TSVDUnfold((TH1D *)" + Handlers.obj("TH1", w[1]) + ", (TH1D *)" + Handlers.obj("TH1", w[2]) + ", (TH1D *)" + Handlers.obj("TH1", w[3]) + ", (TH2D *)" + Handlers.obj("TH2", w[4]) + ")" + ", \"TSVDUnfold\")");
    }

    public static void rootUnfoldRun(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root unfold run"));
        if (w.length < 3) {
            Handlers.usage(":root unfold run <name> <k> <hist>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TH1D *h = " + "SphereBridge::Held<TSVDUnfold>(\"" + w[0] + "\")" + "->Unfold(" + Handlers.asInt(w[1], 2) + "); h->SetName(\"" + w[2] + "\"); h->SetDirectory(gDirectory); return std::string(\"" + w[2] + " created\"); }()");
    }

    public static void rootUnfoldDVector(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root unfold dvector"));
        if (w.length < 1) {
            Handlers.usage(":root unfold dvector <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TSVDUnfold>(\"" + w[0] + "\")" + "->GetD()->Print()");
    }

    public static void rootUnfoldSingular(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root unfold singular"));
        if (w.length < 1) {
            Handlers.usage(":root unfold singular <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TSVDUnfold>(\"" + w[0] + "\")" + "->GetSV()->Print()");
    }

    public static void rootFftMagnitude(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fft magnitude"));
        if (w.length < 2) {
            Handlers.usage(":root fft magnitude <hist> <output>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TH1 *out = nullptr; out = " + Handlers.obj("TH1", w[0]) + "->FFT(out, \"MAG\"); if (out == nullptr) { return std::string(\"ERROR: this build has no FFT\"); } out->SetName(\"" + w[1] + "\"); out->SetDirectory(gDirectory); return std::string(\"" + w[1] + " created\"); }()");
    }

    public static void rootFftPhase(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fft phase"));
        if (w.length < 2) {
            Handlers.usage(":root fft phase <hist> <output>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TH1 *out = nullptr; out = " + Handlers.obj("TH1", w[0]) + "->FFT(out, \"PH\"); if (out == nullptr) { return std::string(\"ERROR: this build has no FFT\"); } out->SetName(\"" + w[1] + "\"); out->SetDirectory(gDirectory); return std::string(\"" + w[1] + " created\"); }()");
    }

    public static void rootFftReal(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fft real"));
        if (w.length < 2) {
            Handlers.usage(":root fft real <hist> <output>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TH1 *out = nullptr; out = " + Handlers.obj("TH1", w[0]) + "->FFT(out, \"RE\"); if (out == nullptr) { return std::string(\"ERROR: this build has no FFT\"); } out->SetName(\"" + w[1] + "\"); out->SetDirectory(gDirectory); return std::string(\"" + w[1] + " created\"); }()");
    }

    public static void rootFftImaginary(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fft imaginary"));
        if (w.length < 2) {
            Handlers.usage(":root fft imaginary <hist> <output>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TH1 *out = nullptr; out = " + Handlers.obj("TH1", w[0]) + "->FFT(out, \"IM\"); if (out == nullptr) { return std::string(\"ERROR: this build has no FFT\"); } out->SetName(\"" + w[1] + "\"); out->SetDirectory(gDirectory); return std::string(\"" + w[1] + " created\"); }()");
    }

    // --- reading fits back, TF1 parameters, applying a TMVA model, canvas layout and output, geometry, RooFit datasets and results, tree caches and indices, the rest of RDataFrame, the host system, regular expressions, file internals and more of TMath ---

    public static void rootFitresRun(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fitres run"));
        if (w.length < 3) {
            Handlers.usage(":root fitres run <name> <hist> <function> [options]".trim());
            return;
        }
        Handlers.cling(c, "[]{ auto pointer = " + Handlers.obj("TH1", w[1]) + "->Fit(\"" + w[2] + "\", \"S" + (w.length > 3 ? w[3] : "") + "\"); TFitResult *result = pointer.Get(); if (result == nullptr) { return std::string(\"ERROR: the fit produced no result\"); } SphereBridge::Keep<TFitResult>(\"" + w[0] + "\", new TFitResult(*result), \"TFitResult\"); return std::string(\"" + w[0] + " bound, chi2/ndf = \") + std::to_string(result->Chi2()) + \"/\" + std::to_string(result->Ndf()); }()");
    }

    public static void rootFitresChi2(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fitres chi2"));
        if (w.length < 1) {
            Handlers.usage(":root fitres chi2 <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TFitResult>(\"" + w[0] + "\")" + "->Chi2()");
    }

    public static void rootFitresNdf(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fitres ndf"));
        if (w.length < 1) {
            Handlers.usage(":root fitres ndf <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TFitResult>(\"" + w[0] + "\")" + "->Ndf()");
    }

    public static void rootFitresProb(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fitres prob"));
        if (w.length < 1) {
            Handlers.usage(":root fitres prob <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TFitResult>(\"" + w[0] + "\")" + "->Prob()");
    }

    public static void rootFitresParam(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fitres param"));
        if (w.length < 2) {
            Handlers.usage(":root fitres param <name> <index>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TFitResult>(\"" + w[0] + "\")" + "->Parameter(" + Handlers.asInt(w[1], 0) + ")");
    }

    public static void rootFitresError(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fitres error"));
        if (w.length < 2) {
            Handlers.usage(":root fitres error <name> <index>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TFitResult>(\"" + w[0] + "\")" + "->ParError(" + Handlers.asInt(w[1], 0) + ")");
    }

    public static void rootFitresPname(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fitres pname"));
        if (w.length < 2) {
            Handlers.usage(":root fitres pname <name> <index>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TFitResult>(\"" + w[0] + "\")" + "->ParName(" + Handlers.asInt(w[1], 0) + ")");
    }

    public static void rootFitresCorrelation(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fitres correlation"));
        if (w.length < 3) {
            Handlers.usage(":root fitres correlation <name> <i> <j>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TFitResult>(\"" + w[0] + "\")" + "->Correlation(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootFitresCovariance(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fitres covariance"));
        if (w.length < 1) {
            Handlers.usage(":root fitres covariance <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TFitResult>(\"" + w[0] + "\")" + "->GetCovarianceMatrix().Print()");
    }

    public static void rootFitresStatus(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fitres status"));
        if (w.length < 1) {
            Handlers.usage(":root fitres status <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TFitResult>(\"" + w[0] + "\")" + "->Status()");
    }

    public static void rootFitresValid(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fitres valid"));
        if (w.length < 1) {
            Handlers.usage(":root fitres valid <name>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TFitResult>(\"" + w[0] + "\")" + "->IsValid()");
    }

    public static void rootFitresPrint(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fitres print"));
        if (w.length < 1) {
            Handlers.usage(":root fitres print <name>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<TFitResult>(\"" + w[0] + "\")" + "->Print(\"V\"), std::string(\"\"))");
    }

    public static void rootFuncSet(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func set"));
        if (w.length < 3) {
            Handlers.usage(":root func set <function> <index> <value>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TF1", w[0]) + "->SetParameter(" + Handlers.asInt(w[1], 0) + ", " + w[2] + "), std::string(\"set\"))");
    }

    public static void rootFuncGet(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func get"));
        if (w.length < 2) {
            Handlers.usage(":root func get <function> <index>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->GetParameter(" + Handlers.asInt(w[1], 0) + ")");
    }

    public static void rootFuncPerror(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func perror"));
        if (w.length < 2) {
            Handlers.usage(":root func perror <function> <index>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->GetParError(" + Handlers.asInt(w[1], 0) + ")");
    }

    public static void rootFuncPname(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func pname"));
        if (w.length < 2) {
            Handlers.usage(":root func pname <function> <index>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->GetParName(" + Handlers.asInt(w[1], 0) + ")");
    }

    public static void rootFuncRename(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func rename"));
        if (w.length < 3) {
            Handlers.usage(":root func rename <function> <index> <text>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TF1", w[0]) + "->SetParName(" + Handlers.asInt(w[1], 0) + ", \"" + Handlers.join(w, 2) + "\"), std::string(\"named\"))");
    }

    public static void rootFuncLimits(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func limits"));
        if (w.length < 4) {
            Handlers.usage(":root func limits <function> <index> <low> <high>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TF1", w[0]) + "->SetParLimits(" + Handlers.csv(Handlers.join(w, 1)) + "), std::string(\"bounded\"))");
    }

    public static void rootFuncFix(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func fix"));
        if (w.length < 3) {
            Handlers.usage(":root func fix <function> <index> <value>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TF1", w[0]) + "->FixParameter(" + Handlers.csv(Handlers.join(w, 1)) + "), std::string(\"fixed\"))");
    }

    public static void rootFuncRelease(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func release"));
        if (w.length < 2) {
            Handlers.usage(":root func release <function> <index>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TF1", w[0]) + "->ReleaseParameter(" + Handlers.asInt(w[1], 0) + "), std::string(\"released\"))");
    }

    public static void rootFuncNpar(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func npar"));
        if (w.length < 1) {
            Handlers.usage(":root func npar <function>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->GetNpar()");
    }

    public static void rootFuncChi2(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func chi2"));
        if (w.length < 1) {
            Handlers.usage(":root func chi2 <function>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->GetChisquare()");
    }

    public static void rootFuncNdf(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func ndf"));
        if (w.length < 1) {
            Handlers.usage(":root func ndf <function>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->GetNDF()");
    }

    public static void rootFuncMaximum(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func maximum"));
        if (w.length < 3) {
            Handlers.usage(":root func maximum <function> <from> <to>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->GetMaximum(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootFuncMaxX(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func maxx"));
        if (w.length < 3) {
            Handlers.usage(":root func maxx <function> <from> <to>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->GetMaximumX(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootFuncMinimum(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func minimum"));
        if (w.length < 3) {
            Handlers.usage(":root func minimum <function> <from> <to>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->GetMinimum(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootFuncSolve(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func solve"));
        if (w.length < 4) {
            Handlers.usage(":root func solve <function> <y> <from> <to>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->GetX(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootFuncMean(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func mean"));
        if (w.length < 3) {
            Handlers.usage(":root func mean <function> <from> <to>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->Mean(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootFuncVariance(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func variance"));
        if (w.length < 3) {
            Handlers.usage(":root func variance <function> <from> <to>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->Variance(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootFuncMoment(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func moment"));
        if (w.length < 4) {
            Handlers.usage(":root func moment <function> <order> <from> <to>".trim());
            return;
        }
        Handlers.cling(c, Handlers.obj("TF1", w[0]) + "->Moment(" + Handlers.csv(Handlers.join(w, 1)) + ")");
    }

    public static void rootFuncRange(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func range"));
        if (w.length < 3) {
            Handlers.usage(":root func range <function> <from> <to>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TF1", w[0]) + "->SetRange(" + Handlers.csv(Handlers.join(w, 1)) + "), std::string(\"set\"))");
    }

    public static void rootFuncPoints(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func points"));
        if (w.length < 2) {
            Handlers.usage(":root func points <function> <n>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TF1", w[0]) + "->SetNpx(" + Handlers.asInt(w[1], 100) + "), std::string(\"set\"))");
    }

    public static void rootFuncDraw(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func draw"));
        if (w.length < 1) {
            Handlers.usage(":root func draw <function> [option]".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.obj("TF1", w[0]) + "->Draw(\"" + (w.length > 1 ? w[1] : "") + "\"), std::string(\"drawn\"))");
    }

    /** <a> <b> [options] */
    public static void rootHistChi2Test(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist chi2test"));
        if (w.length < 2) {
            Handlers.usage(":root hist chi2test <a> <b> [options]");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->Chi2Test(SphereBridge::Need<TH1>(\"" + w[1] + "\", \"TH1\"), \"" + (w.length > 2 ? Handlers.join(w, 2) : "UU") + "\")");
    }

    /** <name> */
    public static void rootHistSumw2(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist sumw2"));
        if (w.length < 1) {
            Handlers.usage(":root hist sumw2 <name>");
            return;
        }
        Handlers.cling(c, "[]{ SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->Sumw2(); return std::string(\"weights are now tracked\"); }()");
    }

    /** <name> <fraction> */
    public static void rootHistQuantiles(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist quantiles"));
        if (w.length < 2) {
            Handlers.usage(":root hist quantiles <name> <fraction>");
            return;
        }
        Handlers.cling(c, "[]{ Double_t p[1] = { " + w[1] + " }; Double_t q[1] = { 0.0 }; SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->GetQuantiles(1, q, p); return q[0]; }()");
    }

    /** <name> <x> */
    public static void rootHistInterpolate(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist interpolate"));
        if (w.length < 2) {
            Handlers.usage(":root hist interpolate <name> <x>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->Interpolate(" + w[1] + ")");
    }

    /** <name> <out> */
    public static void rootHistCumulative(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist cumulative"));
        if (w.length < 2) {
            Handlers.usage(":root hist cumulative <name> <out>");
            return;
        }
        Handlers.cling(c, "[]{ TH1 *c = SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->GetCumulative(); c->SetName(\"" + w[1] + "\"); c->SetDirectory(gDirectory); return std::string(c->GetName()); }()");
    }

    /** <name> */
    public static void rootHistGetRandom(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist getrandom"));
        if (w.length < 1) {
            Handlers.usage(":root hist getrandom <name>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->GetRandom()");
    }

    /** <name> <function> <entries> */
    public static void rootHistFillRandom(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist fillrandom"));
        if (w.length < 3) {
            Handlers.usage(":root hist fillrandom <name> <function> <entries>");
            return;
        }
        Handlers.cling(c, "[]{ SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->FillRandom(\"" + w[1] + "\", " + w[2] + "); return SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->GetEntries(); }()");
    }

    /** <out> <passed> <total> */
    public static void rootHistDivideBinomial(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist dividebinomial"));
        if (w.length < 3) {
            Handlers.usage(":root hist dividebinomial <out> <passed> <total>");
            return;
        }
        Handlers.cling(c, "[]{ TH1 *o = SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\"); o->Divide(SphereBridge::Need<TH1>(\"" + w[1] + "\", \"TH1\"), SphereBridge::Need<TH1>(\"" + w[2] + "\", \"TH1\"), 1.0, 1.0, \"B\"); return std::string(\"divided with binomial errors\"); }()");
    }

    /** <hist> <function> <out> */
    public static void rootHistResiduals(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist residuals"));
        if (w.length < 3) {
            Handlers.usage(":root hist residuals <hist> <function> <out>");
            return;
        }
        Handlers.cling(c, "[]{ TH1 *h = SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\"); TF1 *f = SphereBridge::Need<TF1>(\"" + w[1] + "\", \"TF1\"); TH1D *r = new TH1D(\"" + w[2] + "\", \"residuals\", h->GetNbinsX(), h->GetXaxis()->GetXmin(), h->GetXaxis()->GetXmax()); for (int i = 1; i <= h->GetNbinsX(); ++i) { const double x = h->GetBinCenter(i); r->SetBinContent(i, h->GetBinContent(i) - f->Eval(x)); r->SetBinError(i, h->GetBinError(i)); } r->SetDirectory(gDirectory); return std::string(r->GetName()); }()");
    }

    /** <hist> <function> <out> */
    public static void rootHistPulls(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist pulls"));
        if (w.length < 3) {
            Handlers.usage(":root hist pulls <hist> <function> <out>");
            return;
        }
        Handlers.cling(c, "[]{ TH1 *h = SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\"); TF1 *f = SphereBridge::Need<TF1>(\"" + w[1] + "\", \"TF1\"); TH1D *r = new TH1D(\"" + w[2] + "\", \"pulls\", h->GetNbinsX(), h->GetXaxis()->GetXmin(), h->GetXaxis()->GetXmax()); for (int i = 1; i <= h->GetNbinsX(); ++i) { const double e = h->GetBinError(i); const double d = h->GetBinContent(i) - f->Eval(h->GetBinCenter(i)); r->SetBinContent(i, e > 0.0 ? d / e : 0.0); } r->SetDirectory(gDirectory); return std::string(r->GetName()); }()");
    }

    /** <name> */
    public static void rootHistUnderflow(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist underflow"));
        if (w.length < 1) {
            Handlers.usage(":root hist underflow <name>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->GetBinContent(0)");
    }

    /** <name> */
    public static void rootHistOverflow(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist overflow"));
        if (w.length < 1) {
            Handlers.usage(":root hist overflow <name>");
            return;
        }
        Handlers.cling(c, "[]{ TH1 *h = SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\"); return h->GetBinContent(h->GetNbinsX() + 1); }()");
    }

    /** <name> */
    public static void rootHistMaximumBin(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist maxbin"));
        if (w.length < 1) {
            Handlers.usage(":root hist maxbin <name>");
            return;
        }
        Handlers.cling(c, "[]{ TH1 *h = SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\"); const int b = h->GetMaximumBin(); return std::string(\"bin \") + std::to_string(b) + \"  at \" + std::to_string(h->GetBinCenter(b)) + \"  holding \" + std::to_string(h->GetBinContent(b)); }()");
    }

    /** <name> <bin> */
    public static void rootHistBinWidth(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist binwidth"));
        if (w.length < 2) {
            Handlers.usage(":root hist binwidth <name> <bin>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->GetBinWidth(" + w[1] + ")");
    }

    /** <name> */
    public static void rootHistEffectiveEntries(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root hist effective"));
        if (w.length < 1) {
            Handlers.usage(":root hist effective <name>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->GetEffectiveEntries()");
    }

    /** <name> <x> */
    public static void rootFuncDerivative(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func derivative"));
        if (w.length < 2) {
            Handlers.usage(":root func derivative <name> <x>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TF1>(\"" + w[0] + "\", \"TF1\")->Derivative(" + w[1] + ")");
    }

    /** <name> <x> */
    public static void rootFuncSecond(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func second"));
        if (w.length < 2) {
            Handlers.usage(":root func second <name> <x>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TF1>(\"" + w[0] + "\", \"TF1\")->Derivative2(" + w[1] + ")");
    }

    /** <name> <low> <high> */
    public static void rootFuncIntegralError(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func integralerror"));
        if (w.length < 3) {
            Handlers.usage(":root func integralerror <name> <low> <high>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TF1>(\"" + w[0] + "\", \"TF1\")->IntegralError(" + w[1] + ", " + w[2] + ")");
    }

    /** <name> */
    public static void rootFuncRandom(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func random"));
        if (w.length < 1) {
            Handlers.usage(":root func random <name>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TF1>(\"" + w[0] + "\", \"TF1\")->GetRandom()");
    }

    /** <name> <0|1> */
    public static void rootFuncNormalized(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func normalized"));
        if (w.length < 2) {
            Handlers.usage(":root func normalized <name> <0|1>");
            return;
        }
        Handlers.cling(c, "[]{ SphereBridge::Need<TF1>(\"" + w[0] + "\", \"TF1\")->SetNormalized(" + w[1] + " != 0); return std::string(\"" + w[1] + "\") == \"0\" ? std::string(\"left as it is\") : std::string(\"normalized\"); }()");
    }

    /** <name> <low> <high> */
    public static void rootFuncFwhm(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root func fwhm"));
        if (w.length < 3) {
            Handlers.usage(":root func fwhm <name> <low> <high>");
            return;
        }
        Handlers.cling(c, "[]{ TF1 *f = SphereBridge::Need<TF1>(\"" + w[0] + "\", \"TF1\"); const double top = f->GetMaximum(" + w[1] + ", " + w[2] + "); const double at = f->GetMaximumX(" + w[1] + ", " + w[2] + "); const double left = f->GetX(top / 2.0, " + w[1] + ", at); const double right = f->GetX(top / 2.0, at, " + w[2] + "); return right - left; }()");
    }

}

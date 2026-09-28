package com.sphere.core.commands;

import com.sphere.utils.AppLogger;

/**
 * TMVA, RooFit, PROOF and the Python bridge.
 *
 * Split out of Handlers, which had grown to hold every backend at once. The
 * helpers these handlers share -- the argument readers, the interpreter call,
 * the named-handle builders -- stay in Handlers and are called through it.
 */
public final class RootAnalysisCommands {

    private RootAnalysisCommands() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    public static void rootDataloaderBackground(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root dataloader background"));
        if (w.length < 2) {
            Handlers.usage(":root dataloader background <loader> <tree>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TMVA::DataLoader") + "->AddBackgroundTree(" + Handlers.obj("TTree", w[1]) + "), std::string(\"added\"))");
    }

    public static void rootDataloaderBook(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root dataloader book"));
        if (w.length < 4) {
            Handlers.usage(":root dataloader book <factory> <loader> <type> <name> [options]".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TMVA::Factory") + "->BookMethod(" + Handlers.held(w[1], "TMVA::DataLoader") + ", TMVA::Types::k" + w[2] + ", \"" + w[3] + "\", \"" + Handlers.join(w, 4) + "\"), std::string(\"booked\"))");
    }

    public static void rootDataloaderNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root dataloader new"));
        if (w.length < 1) {
            Handlers.usage(":root dataloader new <name>".trim());
            return;
        }
        Handlers.cling(c, Handlers.keep(w[0], "TMVA::DataLoader", "new TMVA::DataLoader(\"" + w[0] + "\")"));
    }

    public static void rootDataloaderPrepare(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root dataloader prepare"));
        if (w.length < 1) {
            Handlers.usage(":root dataloader prepare <loader> [options]".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TMVA::DataLoader") + "->PrepareTrainingAndTestTree(\"\", \"" + Handlers.join(w, 1) + "\"), std::string(\"prepared\"))");
    }

    public static void rootDataloaderSignal(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root dataloader signal"));
        if (w.length < 2) {
            Handlers.usage(":root dataloader signal <loader> <tree>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TMVA::DataLoader") + "->AddSignalTree(" + Handlers.obj("TTree", w[1]) + "), std::string(\"added\"))");
    }

    public static void rootDataloaderVariable(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root dataloader variable"));
        if (w.length < 2) {
            Handlers.usage(":root dataloader variable <loader> <expression>".trim());
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(w[0], "TMVA::DataLoader") + "->AddVariable(\"" + Handlers.join(w, 1) + "\"), std::string(\"added\"))");
    }

    public static void rootProofOpen(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root proof open");
        if (a.isEmpty()) {
            Handlers.usage(":root proof open <url>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "TProof::Open(\"" + a0 + "\")");
    }

    public static void rootProofProcess(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root proof process");
        if (a.isEmpty()) {
            Handlers.usage(":root proof process <sel>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "gProof->Process(\"" + a0 + "\")");
    }

    public static void rootProofStatus(String i, CommandExecutionContext c) {
        Handlers.cling(c, "gProof->Print()");
    }

    public static void rootPyEval(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root py eval");
        if (a.isEmpty()) {
            Handlers.usage(":root py eval <expr>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "TPython::Eval(\"" + a0 + "\")");
    }

    public static void rootPyExec(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root py exec");
        if (a.isEmpty()) {
            Handlers.usage(":root py exec <code>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "TPython::Exec(\"" + a0 + "\")");
    }

    public static void rootPyImport(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root py import");
        if (a.isEmpty()) {
            Handlers.usage(":root py import <mod>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "TPython::Exec(\"import " + a0 + "\")");
    }

    public static void rootRoofitFit(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit fit"));
        if (w.length < 3) {
            Handlers.usage(":root roofit fit <workspace> <pdf> <data>");
            return;
        }
        final String workspace = Handlers.held(w[0], "RooWorkspace");
        Handlers.cling(c, "(" + workspace + "->pdf(\"" + w[1] + "\")->fitTo(*" + workspace
               + "->data(\"" + w[2] + "\")), std::string(\"fit done\"))");
    }

    public static void rootRoofitPdf(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root roofit pdf");
        final String name = Handlers.head(a);
        final String expr = Handlers.tail(a);
        if (name.isEmpty() || expr.isEmpty()) {
            Handlers.usage(":root roofit pdf <workspace> <expr>");
            return;
        }
        Handlers.cling(c, "(" + Handlers.held(name, "RooWorkspace") + "->factory(\"" + expr
               + "\") != nullptr) ? \"built\" : \"ERROR: RooFit refused that expression\"");
    }

    public static void rootRoofitPlot(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root roofit plot");
        final String name = Handlers.head(a);
        final String var = Handlers.head(Handlers.tail(a));
        if (name.isEmpty() || var.isEmpty()) {
            Handlers.usage(":root roofit plot <workspace> <var>");
            return;
        }
        Handlers.cling(c, Handlers.held(name, "RooWorkspace") + "->var(\"" + var + "\")->frame()->Draw()");
    }

    public static void rootRoofitWorkspace(String i, CommandExecutionContext c) {
        final String name = Handlers.head(Handlers.args(i, ":root roofit workspace"));
        if (name.isEmpty()) {
            Handlers.usage(":root roofit workspace <name>");
            return;
        }
        Handlers.cling(c, Handlers.keep(name, "RooWorkspace", "new RooWorkspace(\"" + name + "\")"));
    }

    public static void rootTmvaEvaluate(String i, CommandExecutionContext c) {
        final String factory = Handlers.head(Handlers.args(i, ":root tmva evaluate"));
        if (factory.isEmpty()) {
            Handlers.usage(":root tmva evaluate <factory>");
            return;
        }
        Handlers.cling(c, Handlers.held(factory, "TMVA::Factory") + "->EvaluateAllMethods()");
    }

    public static void rootTmvaFactory(String i, CommandExecutionContext c) {
        final String a = Handlers.args(i, ":root tmva factory");
        final String name = Handlers.head(a);
        if (name.isEmpty()) {
            Handlers.usage(":root tmva factory <name> [options]");
            return;
        }
        Handlers.cling(c, Handlers.keep(name, "TMVA::Factory",
            "new TMVA::Factory(\"" + name + "\", \"" + Handlers.tail(a) + "\")"));
    }

    public static void rootTmvaGui(String i, CommandExecutionContext c) {
        String a = Handlers.args(i, ":root tmva gui");
        if (a.isEmpty()) {
            Handlers.usage(":root tmva gui <file>");
            return;
        }
        String a0 = a;
        Handlers.cling(c, "TMVA::TMVAGui(\"" + a0 + "\")");
    }

    public static void rootTmvaTest(String i, CommandExecutionContext c) {
        final String factory = Handlers.head(Handlers.args(i, ":root tmva test"));
        if (factory.isEmpty()) {
            Handlers.usage(":root tmva test <factory>");
            return;
        }
        Handlers.cling(c, Handlers.held(factory, "TMVA::Factory") + "->TestAllMethods()");
    }

    public static void rootTmvaTrain(String i, CommandExecutionContext c) {
        final String factory = Handlers.head(Handlers.args(i, ":root tmva train"));
        if (factory.isEmpty()) {
            Handlers.usage(":root tmva train <factory>");
            return;
        }
        Handlers.cling(c, Handlers.held(factory, "TMVA::Factory") + "->TrainAllMethods()");
    }

    // --- RooStats, sparse histograms, splines, density estimation, principal components, unfolding, decompositions, integrators, interpolators, FFT, XML and compression ---

    public static void rootRoostatsModel(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roostats model"));
        if (w.length < 2) {
            Handlers.usage(":root roostats model <name> <workspace>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<RooStats::ModelConfig>(\"" + w[0] + "\", " + "new RooStats::ModelConfig(\"" + w[0] + "\", " + "SphereBridge::Held<RooWorkspace>(\"" + w[1] + "\")" + ")" + ", \"RooStats::ModelConfig\")");
    }

    public static void rootRoostatsPdf(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roostats pdf"));
        if (w.length < 3) {
            Handlers.usage(":root roostats pdf <model> <workspace> <pdf>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<RooStats::ModelConfig>(\"" + w[0] + "\")" + "->SetPdf(*" + "SphereBridge::Held<RooWorkspace>(\"" + w[1] + "\")" + "->pdf(\"" + w[2] + "\")), std::string(\"set\"))");
    }

    public static void rootRoostatsPoi(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roostats poi"));
        if (w.length < 3) {
            Handlers.usage(":root roostats poi <model> <workspace> <variable>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<RooStats::ModelConfig>(\"" + w[0] + "\")" + "->SetParametersOfInterest(RooArgSet(*" + "SphereBridge::Held<RooWorkspace>(\"" + w[1] + "\")" + "->var(\"" + w[2] + "\"))), std::string(\"set\"))");
    }

    public static void rootRoostatsObservables(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roostats observables"));
        if (w.length < 3) {
            Handlers.usage(":root roostats observables <model> <workspace> <variable>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<RooStats::ModelConfig>(\"" + w[0] + "\")" + "->SetObservables(RooArgSet(*" + "SphereBridge::Held<RooWorkspace>(\"" + w[1] + "\")" + "->var(\"" + w[2] + "\"))), std::string(\"set\"))");
    }

    public static void rootRoostatsNuisance(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roostats nuisance"));
        if (w.length < 3) {
            Handlers.usage(":root roostats nuisance <model> <workspace> <variable>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<RooStats::ModelConfig>(\"" + w[0] + "\")" + "->SetNuisanceParameters(RooArgSet(*" + "SphereBridge::Held<RooWorkspace>(\"" + w[1] + "\")" + "->var(\"" + w[2] + "\"))), std::string(\"set\"))");
    }

    public static void rootRoostatsInterval(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roostats interval"));
        if (w.length < 3) {
            Handlers.usage(":root roostats interval <model> <workspace> <data> [confidence]".trim());
            return;
        }
        Handlers.cling(c, "[]{ RooStats::ProfileLikelihoodCalculator calc(*" + "SphereBridge::Held<RooWorkspace>(\"" + w[1] + "\")" + "->data(\"" + w[2] + "\"), *" + "SphereBridge::Held<RooStats::ModelConfig>(\"" + w[0] + "\")" + "); calc.SetConfidenceLevel(" + (w.length > 3 ? w[3] : "0.95") + "); RooStats::LikelihoodInterval *interval = calc.GetInterval(); if (interval == nullptr) { return std::string(\"ERROR: no interval\"); } RooRealVar *poi = (RooRealVar *)" + "SphereBridge::Held<RooStats::ModelConfig>(\"" + w[0] + "\")" + "->GetParametersOfInterest()->first(); return std::to_string(interval->LowerLimit(*poi)) + \" .. \" + std::to_string(interval->UpperLimit(*poi)); }()");
    }

    public static void rootRoostatsSignificance(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roostats significance"));
        if (w.length < 3) {
            Handlers.usage(":root roostats significance <model> <workspace> <data>".trim());
            return;
        }
        Handlers.cling(c, "[]{ RooStats::ProfileLikelihoodCalculator calc(*" + "SphereBridge::Held<RooWorkspace>(\"" + w[1] + "\")" + "->data(\"" + w[2] + "\"), *" + "SphereBridge::Held<RooStats::ModelConfig>(\"" + w[0] + "\")" + "); RooStats::HypoTestResult *result = calc.GetHypoTest(); if (result == nullptr) { return std::string(\"ERROR: no result\"); } return std::to_string(result->Significance()) + \" sigma, p = \" + std::to_string(result->NullPValue()); }()");
    }

    public static void rootRoostatsAsymptotic(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roostats asymptotic"));
        if (w.length < 4) {
            Handlers.usage(":root roostats asymptotic <alternate> <null> <workspace> <data>".trim());
            return;
        }
        Handlers.cling(c, "[]{ RooStats::AsymptoticCalculator calc(*" + "SphereBridge::Held<RooWorkspace>(\"" + w[2] + "\")" + "->data(\"" + w[3] + "\"), *" + "SphereBridge::Held<RooStats::ModelConfig>(\"" + w[0] + "\")" + ", *" + "SphereBridge::Held<RooStats::ModelConfig>(\"" + w[1] + "\")" + "); RooStats::HypoTestResult *result = calc.GetHypoTest(); if (result == nullptr) { return std::string(\"ERROR: no result\"); } return std::to_string(result->Significance()) + \" sigma, p = \" + std::to_string(result->NullPValue()); }()");
    }

    // --- reading fits back, TF1 parameters, applying a TMVA model, canvas layout and output, geometry, RooFit datasets and results, tree caches and indices, the rest of RDataFrame, the host system, regular expressions, file internals and more of TMath ---

    public static void rootTmvaReader(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tmva reader"));
        if (w.length < 1) {
            Handlers.usage(":root tmva reader <name> [options]".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TMVA::Reader>(\"" + w[0] + "\", " + "new TMVA::Reader(\"" + (w.length > 1 ? Handlers.join(w, 1) : "!Color:Silent") + "\")" + ", \"TMVA::Reader\")");
    }

    public static void rootTmvaInput(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tmva input"));
        if (w.length < 3) {
            Handlers.usage(":root tmva input <reader> <slot> <expression>".trim());
            return;
        }
        Handlers.cling(c, "[]{ float *slot = new float(0.0f); SphereBridge::Keep<float>(\"" + w[1] + "\", slot, \"float\"); " + "SphereBridge::Held<TMVA::Reader>(\"" + w[0] + "\")" + "->AddVariable(\"" + Handlers.join(w, 2) + "\", slot); return std::string(\"" + w[1] + " ready\"); }()");
    }

    public static void rootTmvaFeed(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tmva feed"));
        if (w.length < 2) {
            Handlers.usage(":root tmva feed <slot> <value>".trim());
            return;
        }
        Handlers.cling(c, "(*" + "SphereBridge::Held<float>(\"" + w[0] + "\")" + " = " + w[1] + ", std::string(\"fed\"))");
    }

    public static void rootTmvaLoad(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tmva load"));
        if (w.length < 3) {
            Handlers.usage(":root tmva load <reader> <method> <weight file>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<TMVA::Reader>(\"" + w[0] + "\")" + "->BookMVA(\"" + w[1] + "\", \"" + w[2] + "\"), std::string(\"loaded\"))");
    }

    public static void rootTmvaApply(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root tmva apply"));
        if (w.length < 2) {
            Handlers.usage(":root tmva apply <reader> <method>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TMVA::Reader>(\"" + w[0] + "\")" + "->EvaluateMVA(\"" + w[1] + "\")");
    }

    public static void rootRoofitVar(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit var"));
        if (w.length < 4) {
            Handlers.usage(":root roofit var <workspace> <name> <low> <high>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<RooWorkspace>(\"" + w[0] + "\")" + "->factory(\"" + w[1] + "[" + w[2] + "," + w[3] + "]\")->GetName()");
    }

    public static void rootRoofitGenerate(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit generate"));
        if (w.length < 5) {
            Handlers.usage(":root roofit generate <workspace> <pdf> <observable> <events> <name>".trim());
            return;
        }
        Handlers.cling(c, "[]{ RooDataSet *data = " + "SphereBridge::Held<RooWorkspace>(\"" + w[0] + "\")" + "->pdf(\"" + w[1] + "\")->generate(RooArgSet(*" + "SphereBridge::Held<RooWorkspace>(\"" + w[0] + "\")" + "->var(\"" + w[2] + "\")), " + Handlers.asInt(w[3], 1000) + "); if (data == nullptr) { return std::string(\"ERROR: nothing generated\"); } data->SetName(\"" + w[4] + "\"); " + "SphereBridge::Held<RooWorkspace>(\"" + w[0] + "\")" + "->import(*data); return std::string(\"" + w[4] + " generated\"); }()");
    }

    public static void rootRoofitFitSave(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit fitsave"));
        if (w.length < 4) {
            Handlers.usage(":root roofit fitsave <name> <workspace> <pdf> <data>".trim());
            return;
        }
        Handlers.cling(c, "[]{ RooFitResult *result = " + "SphereBridge::Held<RooWorkspace>(\"" + w[1] + "\")" + "->pdf(\"" + w[2] + "\")->fitTo(*" + "SphereBridge::Held<RooWorkspace>(\"" + w[1] + "\")" + "->data(\"" + w[3] + "\"), RooFit::Save(), RooFit::PrintLevel(-1)); if (result == nullptr) { return std::string(\"ERROR: the fit produced no result\"); } SphereBridge::Keep<RooFitResult>(\"" + w[0] + "\", result, \"RooFitResult\"); return std::string(\"" + w[0] + " bound\"); }()");
    }

    public static void rootRoofitStatus(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit status"));
        if (w.length < 1) {
            Handlers.usage(":root roofit status <result>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<RooFitResult>(\"" + w[0] + "\")" + "->status()");
    }

    public static void rootRoofitEdm(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit edm"));
        if (w.length < 1) {
            Handlers.usage(":root roofit edm <result>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<RooFitResult>(\"" + w[0] + "\")" + "->edm()");
    }

    public static void rootRoofitMinNll(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit minnll"));
        if (w.length < 1) {
            Handlers.usage(":root roofit minnll <result>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<RooFitResult>(\"" + w[0] + "\")" + "->minNll()");
    }

    public static void rootRoofitCorrelation(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit correlation"));
        if (w.length < 3) {
            Handlers.usage(":root roofit correlation <result> <a> <b>".trim());
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<RooFitResult>(\"" + w[0] + "\")" + "->correlation(\"" + w[1] + "\", \"" + w[2] + "\")");
    }

    public static void rootRoofitPrintFit(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit printfit"));
        if (w.length < 1) {
            Handlers.usage(":root roofit printfit <result>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<RooFitResult>(\"" + w[0] + "\")" + "->Print(\"v\"), std::string(\"\"))");
    }

    public static void rootRoofitVars(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit vars"));
        if (w.length < 1) {
            Handlers.usage(":root roofit vars <workspace>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<RooWorkspace>(\"" + w[0] + "\")" + "->allVars().Print(), std::string(\"\"))");
    }

    public static void rootRoofitPdfs(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit pdfs"));
        if (w.length < 1) {
            Handlers.usage(":root roofit pdfs <workspace>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<RooWorkspace>(\"" + w[0] + "\")" + "->allPdfs().Print(), std::string(\"\"))");
    }

    public static void rootRoofitContents(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit contents"));
        if (w.length < 1) {
            Handlers.usage(":root roofit contents <workspace>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<RooWorkspace>(\"" + w[0] + "\")" + "->Print(\"v\"), std::string(\"\"))");
    }

    public static void rootRoofitSave(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit save"));
        if (w.length < 2) {
            Handlers.usage(":root roofit save <workspace> <file>".trim());
            return;
        }
        Handlers.cling(c, "(" + "SphereBridge::Held<RooWorkspace>(\"" + w[0] + "\")" + "->writeToFile(\"" + w[1] + "\"), std::string(\"written to " + w[1] + "\"))");
    }

    public static void rootRoofitLoad(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root roofit load"));
        if (w.length < 3) {
            Handlers.usage(":root roofit load <name> <file> <workspace>".trim());
            return;
        }
        Handlers.cling(c, "[]{ TFile *f = TFile::Open(\"" + w[1] + "\"); if (f == nullptr || f->IsZombie()) { return std::string(\"ERROR: cannot read " + w[1] + "\"); } RooWorkspace *ws = (RooWorkspace *)f->Get(\"" + w[2] + "\"); if (ws == nullptr) { return std::string(\"ERROR: no workspace called " + w[2] + "\"); } SphereBridge::Keep<RooWorkspace>(\"" + w[0] + "\", ws, \"RooWorkspace\"); return std::string(\"" + w[0] + " bound\"); }()");
    }

    /** <name> <class> <size> */
    public static void rootClonesNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root clones new"));
        if (w.length < 3) {
            Handlers.usage(":root clones new <name> <class> <size>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TClonesArray>(\"" + w[0] + "\", new TClonesArray(\"" + w[1] + "\", " + w[2] + "), \"TClonesArray\")");
    }

    /** <name> */
    public static void rootClonesSize(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root clones size"));
        if (w.length < 1) {
            Handlers.usage(":root clones size <name>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TClonesArray>(\"" + w[0] + "\")->GetEntriesFast()");
    }

    /** <name> <index> */
    public static void rootClonesAt(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root clones at"));
        if (w.length < 2) {
            Handlers.usage(":root clones at <name> <index>");
            return;
        }
        Handlers.cling(c, "[]{ TObject *o = SphereBridge::Held<TClonesArray>(\"" + w[0] + "\")->At(" + w[1] + "); if (o == nullptr) { return std::string(\"nothing at " + w[1] + "\"); } return std::string(o->ClassName()) + \"  \" + o->GetName(); }()");
    }

    /** <name> */
    public static void rootClonesClear(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root clones clear"));
        if (w.length < 1) {
            Handlers.usage(":root clones clear <name>");
            return;
        }
        Handlers.cling(c, "[]{ SphereBridge::Held<TClonesArray>(\"" + w[0] + "\")->Clear(\"C\"); return std::string(\"cleared\"); }()");
    }

    /** <name> <size> */
    public static void rootClonesExpand(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root clones expand"));
        if (w.length < 2) {
            Handlers.usage(":root clones expand <name> <size>");
            return;
        }
        Handlers.cling(c, "[]{ SphereBridge::Held<TClonesArray>(\"" + w[0] + "\")->ExpandCreate(" + w[1] + "); return SphereBridge::Held<TClonesArray>(\"" + w[0] + "\")->GetSize(); }()");
    }

    /** <name> */
    public static void rootStatNew(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root stat new"));
        if (w.length < 1) {
            Handlers.usage(":root stat new <name>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TStatistic>(\"" + w[0] + "\", new TStatistic(\"" + w[0] + "\"), \"TStatistic\")");
    }

    /** <name> <value> [weight] */
    public static void rootStatFill(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root stat fill"));
        if (w.length < 2) {
            Handlers.usage(":root stat fill <name> <value> [weight]");
            return;
        }
        Handlers.cling(c, "[]{ SphereBridge::Held<TStatistic>(\"" + w[0] + "\")->Fill(" + w[1] + ", " + (w.length > 2 ? w[2] : "1.0") + "); return SphereBridge::Held<TStatistic>(\"" + w[0] + "\")->GetN(); }()");
    }

    /** <name> */
    public static void rootStatMean(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root stat mean"));
        if (w.length < 1) {
            Handlers.usage(":root stat mean <name>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TStatistic>(\"" + w[0] + "\")->GetMean()");
    }

    /** <name> */
    public static void rootStatRms(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root stat rms"));
        if (w.length < 1) {
            Handlers.usage(":root stat rms <name>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TStatistic>(\"" + w[0] + "\")->GetRMS()");
    }

    /** <name> */
    public static void rootStatSum(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root stat sum"));
        if (w.length < 1) {
            Handlers.usage(":root stat sum <name>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Held<TStatistic>(\"" + w[0] + "\")->GetW()");
    }

    /** <name> */
    public static void rootStatCount(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root stat count"));
        if (w.length < 1) {
            Handlers.usage(":root stat count <name>");
            return;
        }
        Handlers.cling(c, "(long) SphereBridge::Held<TStatistic>(\"" + w[0] + "\")->GetN()");
    }

    /** <name> */
    public static void rootStatPrint(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root stat print"));
        if (w.length < 1) {
            Handlers.usage(":root stat print <name>");
            return;
        }
        Handlers.cling(c, "[]{ TStatistic *s = SphereBridge::Held<TStatistic>(\"" + w[0] + "\"); return std::string(\"n \") + std::to_string((long long) s->GetN()) + \"  mean \" + std::to_string(s->GetMean()) + \" +- \" + std::to_string(s->GetMeanErr()) + \"  rms \" + std::to_string(s->GetRMS()) + \"  min \" + std::to_string(s->GetMin()) + \"  max \" + std::to_string(s->GetMax()); }()");
    }

    /** <data> <model1> <model2> [model3] */
    public static void rootFitFractions(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fit fractions"));
        if (w.length < 3) {
            Handlers.usage(":root fit fractions <data> <model1> <model2> [model3]");
            return;
        }
        Handlers.cling(c, "[]{ TObjArray *models = new TObjArray(); models->Add(SphereBridge::Need<TH1>(\"" + w[1] + "\", \"TH1\")); models->Add(SphereBridge::Need<TH1>(\"" + w[2] + "\", \"TH1\")); TFractionFitter *fit = new TFractionFitter(SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\"), models); const int status = fit->Fit(); if (status != 0) { return std::string(\"the fit did not converge, status \") + std::to_string(status); } std::string out; for (int i = 0; i < models->GetEntries(); ++i) { Double_t v = 0, e = 0; fit->GetResult(i, v, e); out += \"model \" + std::to_string(i) + \"  \" + std::to_string(v) + \" +- \" + std::to_string(e) + \"\\n\"; } return out; }()");
    }

    /** <hist> <function> <low> <high> */
    public static void rootFitRange(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fit range"));
        if (w.length < 4) {
            Handlers.usage(":root fit range <hist> <function> <low> <high>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->Fit(SphereBridge::Need<TF1>(\"" + w[1] + "\", \"TF1\"), \"R\", \"\", " + w[2] + ", " + w[3] + ")");
    }

    /** <hist> <function> */
    public static void rootFitLikelihood(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fit likelihood"));
        if (w.length < 2) {
            Handlers.usage(":root fit likelihood <hist> <function>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->Fit(SphereBridge::Need<TF1>(\"" + w[1] + "\", \"TF1\"), \"L\")");
    }

    /** <hist> <function> */
    public static void rootFitWeighted(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fit weighted"));
        if (w.length < 2) {
            Handlers.usage(":root fit weighted <hist> <function>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\")->Fit(SphereBridge::Need<TF1>(\"" + w[1] + "\", \"TF1\"), \"WL\")");
    }

    /** <function> */
    public static void rootFitQuality(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fit quality"));
        if (w.length < 1) {
            Handlers.usage(":root fit quality <function>");
            return;
        }
        Handlers.cling(c, "[]{ TF1 *f = SphereBridge::Need<TF1>(\"" + w[0] + "\", \"TF1\"); const double n = f->GetNDF(); const double c = f->GetChisquare(); return std::string(\"chi2 \") + std::to_string(c) + \"  ndf \" + std::to_string((long long) n) + \"  chi2/ndf \" + std::to_string(n > 0 ? c / n : 0.0) + \"  probability \" + std::to_string(f->GetProb()); }()");
    }

    /** <hist> <out> [level] */
    public static void rootFitConfBand(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root fit confband"));
        if (w.length < 2) {
            Handlers.usage(":root fit confband <hist> <out> [level]");
            return;
        }
        Handlers.cling(c, "[]{ TH1 *h = SphereBridge::Need<TH1>(\"" + w[0] + "\", \"TH1\"); TH1D *band = new TH1D(\"" + w[1] + "\", \"confidence band\", h->GetNbinsX(), h->GetXaxis()->GetXmin(), h->GetXaxis()->GetXmax()); TVirtualFitter *fitter = TVirtualFitter::GetFitter(); if (fitter == nullptr) { return std::string(\"fit something first\"); } fitter->GetConfidenceIntervals(band, " + (w.length > 2 ? w[2] : "0.683") + "); band->SetDirectory(gDirectory); return std::string(band->GetName()); }()");
    }

    /** <passed> <total> [level] */
    public static void rootEffWilson(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root eff wilson"));
        if (w.length < 2) {
            Handlers.usage(":root eff wilson <passed> <total> [level]");
            return;
        }
        Handlers.cling(c, "[]{ const double k = " + w[0] + ", n = " + w[1] + ", cl = " + (w.length > 2 ? w[2] : "0.683") + "; if (n <= 0.0) { return std::string(\"nothing was tried\"); } const double z = TMath::Sqrt(2.0) * TMath::ErfInverse(cl); const double p = k / n; const double d = 1.0 + z * z / n; const double center = (p + z * z / (2.0 * n)) / d; const double half = z * TMath::Sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n)) / d; return std::to_string(p) + \"  [\" + std::to_string(center - half) + \", \" + std::to_string(center + half) + \"]\"; }()");
    }

    /** <passed> <total> [level] */
    public static void rootEffAgresti(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root eff agresti"));
        if (w.length < 2) {
            Handlers.usage(":root eff agresti <passed> <total> [level]");
            return;
        }
        Handlers.cling(c, "[]{ const double k = " + w[0] + ", n = " + w[1] + ", cl = " + (w.length > 2 ? w[2] : "0.683") + "; if (n <= 0.0) { return std::string(\"nothing was tried\"); } const double z = TMath::Sqrt(2.0) * TMath::ErfInverse(cl); const double nt = n + z * z; const double pt = (k + z * z / 2.0) / nt; const double half = z * TMath::Sqrt(pt * (1.0 - pt) / nt); const double lo = pt - half, hi = pt + half; return std::to_string(k / n) + \"  [\" + std::to_string(lo < 0.0 ? 0.0 : lo) + \", \" + std::to_string(hi > 1.0 ? 1.0 : hi) + \"]\"; }()");
    }

    /** <passed> <total> [level] */
    public static void rootEffNormal(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root eff normal"));
        if (w.length < 2) {
            Handlers.usage(":root eff normal <passed> <total> [level]");
            return;
        }
        Handlers.cling(c, "[]{ const double k = " + w[0] + ", n = " + w[1] + ", cl = " + (w.length > 2 ? w[2] : "0.683") + "; if (n <= 0.0) { return std::string(\"nothing was tried\"); } const double z = TMath::Sqrt(2.0) * TMath::ErfInverse(cl); const double p = k / n; const double half = z * TMath::Sqrt(p * (1.0 - p) / n); return std::to_string(p) + \"  [\" + std::to_string(p - half) + \", \" + std::to_string(p + half) + \"]\"; }()");
    }

    /** <observed> [level] */
    public static void rootEffPoisson(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root eff poisson"));
        if (w.length < 1) {
            Handlers.usage(":root eff poisson <observed> [level]");
            return;
        }
        Handlers.cling(c, "[]{ const double n = " + w[0] + ", cl = " + (w.length > 1 ? w[1] : "0.683") + "; const double z = TMath::Sqrt(2.0) * TMath::ErfInverse(cl); const double lo = n <= 0.0 ? 0.0 : n - z * TMath::Sqrt(n); return std::to_string(n) + \"  [\" + std::to_string(lo < 0.0 ? 0.0 : lo) + \", \" + std::to_string(n + z * TMath::Sqrt(n > 0.0 ? n : 1.0)) + \"]\"; }()");
    }

    /** <out> <a> <b> */
    public static void rootMatrixMultiply(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix multiply"));
        if (w.length < 3) {
            Handlers.usage(":root matrix multiply <out> <a> <b>");
            return;
        }
        Handlers.cling(c, "SphereBridge::Keep<TMatrixD>(\"" + w[0] + "\", new TMatrixD(*SphereBridge::Held<TMatrixD>(\"" + w[1] + "\"), TMatrixD::kMult, *SphereBridge::Held<TMatrixD>(\"" + w[2] + "\")), \"TMatrixD\")");
    }

    /** <out> <a> <b> */
    public static void rootMatrixAdd(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix add"));
        if (w.length < 3) {
            Handlers.usage(":root matrix add <out> <a> <b>");
            return;
        }
        Handlers.cling(c, "[]{ TMatrixD *sum = new TMatrixD(*SphereBridge::Held<TMatrixD>(\"" + w[1] + "\")); *sum += *SphereBridge::Held<TMatrixD>(\"" + w[2] + "\"); return SphereBridge::Keep<TMatrixD>(\"" + w[0] + "\", sum, \"TMatrixD\"); }()");
    }

    /** <name> */
    public static void rootMatrixNorm(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix norm"));
        if (w.length < 1) {
            Handlers.usage(":root matrix norm <name>");
            return;
        }
        Handlers.cling(c, "[]{ TMatrixD *m = SphereBridge::Held<TMatrixD>(\"" + w[0] + "\"); return std::string(\"one \") + std::to_string(m->Norm1()) + \"  infinity \" + std::to_string(m->NormInf()) + \"  euclidean \" + std::to_string(TMath::Sqrt(m->E2Norm())); }()");
    }

    /** <name> */
    public static void rootMatrixEigen(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix eigen"));
        if (w.length < 1) {
            Handlers.usage(":root matrix eigen <name>");
            return;
        }
        Handlers.cling(c, "[]{ TMatrixDSym s(*SphereBridge::Held<TMatrixD>(\"" + w[0] + "\")); TMatrixDSymEigen e(s); const TVectorD &v = e.GetEigenValues(); std::string out; for (int i = 0; i < v.GetNrows(); ++i) { out += std::to_string(v(i)); out += '\\n'; } return out; }()");
    }

    /** <out> <cov> <jacobian> */
    public static void rootMatrixSimilarity(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix similarity"));
        if (w.length < 3) {
            Handlers.usage(":root matrix similarity <out> <cov> <jacobian>");
            return;
        }
        Handlers.cling(c, "[]{ TMatrixDSym c(*SphereBridge::Held<TMatrixD>(\"" + w[1] + "\")); c.Similarity(*SphereBridge::Held<TMatrixD>(\"" + w[2] + "\")); return SphereBridge::Keep<TMatrixD>(\"" + w[0] + "\", new TMatrixD(c), \"TMatrixD\"); }()");
    }

    /** <name> */
    public static void rootMatrixCondition(String i, CommandExecutionContext c) {
        final String[] w = Handlers.words(Handlers.args(i, ":root matrix condition"));
        if (w.length < 1) {
            Handlers.usage(":root matrix condition <name>");
            return;
        }
        Handlers.cling(c, "[]{ TMatrixD copy(*SphereBridge::Held<TMatrixD>(\"" + w[0] + "\")); TDecompSVD svd(copy); if (!svd.Decompose()) { return std::string(\"it would not decompose\"); } return std::string(\"condition \") + std::to_string(svd.Condition()); }()");
    }

}

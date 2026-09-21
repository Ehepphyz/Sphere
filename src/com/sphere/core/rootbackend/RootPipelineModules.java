package com.sphere.core.rootbackend;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The parts of ROOT a pipeline says it needs.
 *
 * A pipeline used to declare one boolean, "ROOT headers", which wrote TMath.h
 * and nothing else. Anything beyond arithmetic then failed at the compiler, or
 * worse compiled and failed at load on a missing symbol, because the headers
 * were included but the library was never linked. A module names both at once:
 * what to include and what to link. Ticking GenVector is therefore enough to
 * write four-vectors, and nothing else has to be remembered.
 */
public enum RootPipelineModules {

    MATH("math", "TMath and the constants",
         new String[]{"TMath.h"},
         new String[]{}),

    VECTORS("rvec", "Per-event collections (RVec)",
            new String[]{"ROOT/RVec.hxx"},
            new String[]{"ROOTVecOps"}),

    LORENTZ("genvector", "Four-vectors (GenVector)",
            new String[]{"Math/Vector4D.h", "Math/Vector3D.h"},
            new String[]{"GenVector"}),

    TREE("tree", "Trees and TTreeReader",
         new String[]{"TTree.h", "TTreeReader.h", "TTreeReaderValue.h", "TTreeReaderArray.h"},
         new String[]{"Tree", "TreePlayer"}),

    HIST("hist", "Histograms and profiles",
         new String[]{"TH1.h", "TH2.h", "TProfile.h"},
         new String[]{"Hist"}),

    FRAME("rdf", "RDataFrame",
          new String[]{"ROOT/RDataFrame.hxx"},
          new String[]{"ROOTDataFrame"}),

    FIT("fit", "Functions and fitting",
        new String[]{"TF1.h", "Fit/Fitter.h"},
        new String[]{"Hist", "MathCore"}),

    ROOFIT("roofit", "RooFit",
           new String[]{"RooRealVar.h", "RooDataSet.h", "RooAbsPdf.h"},
           new String[]{"RooFit", "RooFitCore"}),

    TMVA("tmva", "TMVA readers",
         new String[]{"TMVA/Reader.h"},
         new String[]{"TMVA"}),

    RANDOM("random", "Random numbers",
           new String[]{"TRandom3.h"},
           new String[]{"MathCore"}),

    FILES("io", "Files and directories",
          new String[]{"TFile.h", "TDirectory.h"},
          new String[]{"RIO"}),

    /**
     * Parton distributions, which ROOT does not carry.
     *
     * The header is Sphere's own and is written beside the pipeline source when
     * the module is asked for, so a pipeline that reads a PDF builds on a
     * machine where LHAPDF was never installed. The quotes rather than angle
     * brackets are what makes the compiler look there first.
     */
    PDF("pdf", "Parton distributions (Sphere's own reader, nothing to install)",
        new String[]{"\"" + RootPdfHeader.FILE_NAME + "\""},
        new String[]{}),

    /**
     * The same, through LHAPDF itself.
     *
     * Only for a pipeline that has to call LHAPDF rather than reproduce it: a
     * set with its own extrapolator, or a comparison against the library. It
     * needs the library installed, and says so rather than failing at the
     * linker over a name nobody mentioned.
     */
    LHAPDF("lhapdf", "Parton distributions (LHAPDF, installed separately)",
           new String[]{"LHAPDF/LHAPDF.h"},
           new String[]{"LHAPDF"});

    /** The word that travels in the manifest line. */
    public final String token;

    /** What the window puts beside the box. */
    public final String label;

    private final String[] headers;
    private final String[] libraries;

    RootPipelineModules(String token, String label, String[] headers, String[] libraries) {
        this.token = token;
        this.label = label;
        this.headers = headers;
        this.libraries = libraries;
    }

    public List<String> headers() {
        return List.of(headers);
    }

    /** The -l flags this module needs, which the compiler may already carry. */
    public List<String> linkFlags() {
        List<String> out = new ArrayList<>(libraries.length);
        for (String one : libraries) {
            out.add("-l" + one);
        }
        return out;
    }

    public static RootPipelineModules of(String token) {
        if (token == null) {
            return null;
        }
        final String wanted = token.trim().toLowerCase(Locale.ROOT);
        for (RootPipelineModules one : values()) {
            if (one.token.equals(wanted)) {
                return one;
            }
        }
        return null;
    }

    /** The modules a comma-separated list names, unknown words ignored. */
    public static List<RootPipelineModules> decode(String list) {
        List<RootPipelineModules> out = new ArrayList<>();
        if (list == null || list.isBlank() || "-".equals(list.trim())) {
            return out;
        }
        for (String word : list.split(",")) {
            RootPipelineModules one = of(word);
            if (one != null && !out.contains(one)) {
                out.add(one);
            }
        }
        return out;
    }

    /** The same list, as the manifest line writes it. */
    public static String encode(List<RootPipelineModules> modules) {
        if (modules == null || modules.isEmpty()) {
            return "-";
        }
        StringBuilder out = new StringBuilder();
        for (RootPipelineModules one : modules) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(one.token);
        }
        return out.toString();
    }

    /**
     * Every header these modules ask for, in a fixed order and without repeats.
     *
     * Two modules can name the same header, and a source that includes it twice
     * is legal but reads as an oversight, so the list is made unique here.
     */
    public static List<String> headersOf(List<RootPipelineModules> modules) {
        List<String> out = new ArrayList<>();
        if (modules == null) {
            return out;
        }
        for (RootPipelineModules one : values()) {
            if (!modules.contains(one)) {
                continue;
            }
            for (String header : one.headers) {
                if (!out.contains(header)) {
                    out.add(header);
                }
            }
        }
        return out;
    }

    /** Every -l flag these modules ask for, without repeats. */
    public static List<String> linkFlagsOf(List<RootPipelineModules> modules) {
        List<String> out = new ArrayList<>();
        if (modules == null) {
            return out;
        }
        for (RootPipelineModules one : values()) {
            if (!modules.contains(one)) {
                continue;
            }
            for (String flag : one.linkFlags()) {
                if (!out.contains(flag)) {
                    out.add(flag);
                }
            }
        }
        return out;
    }
}

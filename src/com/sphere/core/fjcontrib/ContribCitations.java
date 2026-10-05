package com.sphere.core.fjcontrib;

import com.sphere.core.fastjet.Citations;
import com.sphere.core.fastjet.Citations.Reference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The references the fjcontrib packages ask to be cited. Nothing is printed
 * into the output: Sphere shows them from Citations in the console's context
 * menu ("Citation fjcontrib"), listing first the contribs this session used,
 * each noted by the class that implements it the first time it runs, then
 * every contrib of the release, so that a reference can be found before the
 * tool is run. ":fjco bib" writes the BibTeX to a file.
 */
public final class ContribCitations {

    /** The fjcontrib release this translation follows (kept in Sphere.java with every other version). */
    public static final String RELEASE = com.sphere.Sphere.FJCONTRIB_VERSION;

    private static Reference ref(String key, String authors, String title, String journal, String volume,
                                 String year, String pages, String eprint) {
        return new Reference(key, authors, title, journal, volume, year, pages, eprint, "");
    }

    private static Reference ref(String key, String authors, String title, String journal, String volume,
                                 String year, String pages, String eprint, String doi) {
        return new Reference(key, authors, title, journal, volume, year, pages, eprint, doi);
    }

    /* ------------------------------------------------------------------ */
    /* The references                                                      */
    /* ------------------------------------------------------------------ */

    static final Reference CENTAURO = ref("Arratia:2020ssx",
        "M. Arratia, Y. Makris, D. Neill, F. Ringer and N. Sato", "Asymmetric jet clustering in deep-inelastic scattering",
        "Phys. Rev. D", "104", "2021", "034005", "2006.10751");
    static final Reference MASS_JUMP = ref("Stoll:2014hsa", "M. Stoll",
        "Vetoed jet clustering: The mass-jump algorithm", "JHEP", "04", "2015", "111", "1410.4637");
    static final Reference CS = ref("Berta:2014eza", "P. Berta, M. Spousta, D.W. Miller and R. Leitner",
        "Particle-level pileup subtraction for jets and jet shapes", "JHEP", "06", "2014", "092", "1403.3108");
    static final Reference ICS = ref("Berta:2019hnj", "P. Berta, L. Masetti, D.W. Miller and M. Spousta",
        "Pileup and Underlying Event Mitigation with Iterative Constituent Subtraction", "JHEP", "08", "2019", "175",
        "1905.03470");
    static final Reference DIS_GENKT = ref("vanBeekveld:DISgenkt",
        "M. van Beekveld, S. Ferrario Ravasio, A. Karlberg and D. Peake",
        "A generalised-kt jet algorithm for Deep Inelastic Scattering", "", "", "", "", "");
    static final Reference PANSCALES_DIS = ref("vanBeekveld:2023lsa", "M. van Beekveld and S. Ferrario Ravasio",
        "Next-to-leading-logarithmic PanScales showers for Deep Inelastic Scattering and Vector Boson Fusion",
        "", "", "2023", "", "2305.08645");
    static final Reference ECF = ref("Larkoski:2013eya", "A.J. Larkoski, G.P. Salam and J. Thaler",
        "Energy Correlation Functions for Jet Substructure", "JHEP", "06", "2013", "108", "1305.0007");
    static final Reference D2 = ref("Larkoski:2014gra", "A.J. Larkoski, I. Moult and D. Neill",
        "Power Counting to Better Jet Observables", "JHEP", "12", "2014", "009", "1409.6298");
    static final Reference ECFG = ref("Moult:2016cvt", "I. Moult, L. Necib and J. Thaler",
        "New Angles on Energy Correlation Functions", "JHEP", "12", "2016", "153", "1609.07483");
    static final Reference FLAVOR_CONE = ref("Ilten:2017rbd", "P. Ilten, N.L. Rodd, J. Thaler and M. Williams",
        "Disentangling Heavy Flavor at Colliders", "Phys. Rev. D", "96", "2017", "054019", "1702.02947");
    static final Reference GENERIC_SUB = ref("Soyez:2012hv", "G. Soyez, G.P. Salam, J. Kim, S. Dutta and M. Cacciari",
        "Pileup subtraction for jet shapes", "Phys. Rev. Lett.", "110", "2013", "162001", "1211.2811");
    static final Reference CLEANSING = ref("Krohn:2013lba", "D. Krohn, M.D. Schwartz, M. Low and L.-T. Wang",
        "Jet Cleansing: Pileup Removal at High Luminosity", "Phys. Rev. D", "90", "2014", "065020", "1309.4777");
    static final Reference FF_MOMENTS = ref("Cacciari:2012mu",
        "M. Cacciari, P. Quiroga-Arias, G.P. Salam and G. Soyez",
        "Jet Fragmentation Function Moments in Heavy Ion Collisions", "Eur. Phys. J. C", "73", "2013", "2319",
        "1209.6086");
    static final Reference JWJ = ref("Bertolini:2013iqa", "D. Bertolini, T. Chan and J. Thaler",
        "Jet Observables Without Jet Algorithms", "JHEP", "04", "2014", "013", "1310.7584");
    static final Reference KT_CDSW = ref("Catani:1993hr", "S. Catani, Y.L. Dokshitzer, M.H. Seymour and B.R. Webber",
        "Longitudinally invariant $K_t$ clustering algorithms for hadron hadron collisions", "Nucl. Phys. B", "406",
        "1993", "187", "");
    static final Reference KT_CDW = ref("Catani:1992zp", "S. Catani, Y.L. Dokshitzer and B.R. Webber",
        "The $k_\\perp$-clustering algorithm for jets in deep inelastic scattering and hadron collisions",
        "Phys. Lett. B", "285", "1992", "291", "");
    static final Reference KT_ES = ref("Ellis:1993tq", "S.D. Ellis and D.E. Soper",
        "Successive combination jet algorithm for hadron collisions", "Phys. Rev. D", "48", "1993", "3160",
        "hep-ph/9305266");
    static final Reference LUND = ref("Dreyer:2018nbf", "F.A. Dreyer, G.P. Salam and G. Soyez",
        "The Lund Jet Plane", "JHEP", "12", "2018", "064", "1807.04758");
    static final Reference LUND_SPIN = ref("Karlberg:2021kwr", "A. Karlberg, G.P. Salam, L. Scyboz and R. Verheyen",
        "Spin correlations in final-state parton showers and jet observables", "Eur. Phys. J. C", "81", "2021", "681",
        "2103.16526");
    static final Reference LUND_SOFT_SPIN = ref("Hamilton:2021dyz",
        "K. Hamilton, A. Karlberg, G.P. Salam, L. Scyboz and R. Verheyen",
        "Soft spin correlations in final-state parton showers", "", "", "2021", "", "2111.01161");
    static final Reference NSUB = ref("Thaler:2010tr", "J. Thaler and K. Van Tilburg",
        "Identifying Boosted Objects with N-subjettiness", "JHEP", "03", "2011", "015", "1011.2268");
    static final Reference NSUB_AXES = ref("Thaler:2011gf", "J. Thaler and K. Van Tilburg",
        "Maximizing Boosted Top Identification by Minimizing N-subjettiness", "JHEP", "02", "2012", "093", "1108.2701");
    static final Reference XCONE = ref("Stewart:2015waa",
        "I.W. Stewart, F.J. Tackmann, J. Thaler, C.K. Vermilion and T.F. Wilkason",
        "XCone: N-jettiness as an Exclusive Cone Jet Algorithm", "JHEP", "11", "2015", "072", "1508.01516");
    static final Reference XCONE_BOOSTED = ref("Thaler:2015xaa", "J. Thaler and T.F. Wilkason",
        "Resolving Boosted Jets with XCone", "JHEP", "12", "2015", "051", "1508.01518");
    static final Reference QCD_AWARE = ref("Buckley:2015gua", "A. Buckley and C. Pollard",
        "QCD-aware partonic jet clustering for truth-jet flavour labelling", "Eur. Phys. J. C", "76", "2016", "71",
        "1507.00508");
    static final Reference MMDT = ref("Dasgupta:2013ihk", "M. Dasgupta, A. Fregoso, S. Marzani and G.P. Salam",
        "Towards an understanding of jet substructure", "JHEP", "09", "2013", "029", "1307.0007");
    static final Reference SOFT_DROP = ref("Larkoski:2014wba", "A.J. Larkoski, S. Marzani, G. Soyez and J. Thaler",
        "Soft Drop", "JHEP", "05", "2014", "146", "1402.2657");
    static final Reference RSD = ref("Dreyer:2018tjj", "F.A. Dreyer, L. Necib, G. Soyez and J. Thaler",
        "Recursive Soft Drop", "JHEP", "06", "2018", "093", "1804.03657");
    static final Reference ISD = ref("Frye:2017yrw", "C. Frye, A.J. Larkoski, J. Thaler and K. Zhou",
        "Casimir Meets Poisson: Improved Quark/Gluon Discrimination with Counting Observables", "JHEP", "09", "2017",
        "083", "1704.06266");
    static final Reference SCJET = ref("Tseng:2013dva", "J. Tseng and H. Evans",
        "Semi-classical approach to sequential recombination algorithms for jet clustering", "", "", "2013", "",
        "1304.1025");
    static final Reference SIGNAL_FREE = ref("Berta:2023bkw", "P. Berta, J. Smiesko and M. Spousta",
        "Pileup density estimate independent on jet multiplicity", "", "", "2023", "", "2304.08383");
    static final Reference SOFT_KILLER = ref("Cacciari:2014gra", "M. Cacciari, G.P. Salam and G. Soyez",
        "SoftKiller, a particle-level pileup removal method", "Eur. Phys. J. C", "75", "2015", "59", "1407.0408");
    static final Reference SUBJET_COUNTING = ref("ElHedri:2013lfv", "S. El Hedri, A. Hook, M. Jankowiak and J.G. Wacker",
        "Learning How to Count: A High Multiplicity Search for the LHC", "JHEP", "08", "2013", "136", "1302.1870");
    static final Reference VALENCIA = ref("Boronat:2014hva", "M. Boronat, I. Garcia and M. Vos",
        "A robust jet reconstruction algorithm for high-energy lepton colliders", "Phys. Lett. B", "750", "2015", "95",
        "1404.4294");
    static final Reference VLC = ref("Boronat:2016tgd",
        "M. Boronat, J. Fuster, I. Garcia, P. Roloff, R. Simoniello and M. Vos",
        "Jet reconstruction at high-energy electron-positron colliders", "Eur. Phys. J. C", "78", "2018", "144",
        "1607.05039");
    static final Reference VARIABLE_R = ref("Krohn:2009zg", "D. Krohn, J. Thaler and L.-T. Wang",
        "Jets with Variable R", "JHEP", "06", "2009", "059", "0903.0392");
    static final Reference IFN = ref("Caola:2023wpj",
        "F. Caola, R. Grabarczyk, M.L. Hutt, G.P. Salam, L. Scyboz and J. Thaler",
        "Flavoured jets with exact anti-$k_t$ kinematics and tests of infrared and collinear safety",
        "Phys. Rev. D", "108", "2023", "094010", "2306.07314");
    static final Reference CMP = ref("Czakon:2022wam", "M. Czakon, A. Mitov and R. Poncelet",
        "Infrared-safe flavoured anti-$k_T$ jets", "JHEP", "04", "2023", "138", "2205.11879");
    static final Reference GHS = ref("Gauld:2022lem", "R. Gauld, A. Huss and G. Stagnitto",
        "Flavor Identification of Reconstructed Hadronic Jets", "Phys. Rev. Lett.", "130", "2023", "161901",
        "2208.11138");
    static final Reference GHS_ERRATUM = ref("Gauld:2022lem-erratum", "R. Gauld, A. Huss and G. Stagnitto",
        "Erratum: Flavor Identification of Reconstructed Hadronic Jets", "Phys. Rev. Lett.", "132", "2024", "159901", "");
    static final Reference SDF = ref("Caletti:2022glq", "S. Caletti, A.J. Larkoski, S. Marzani and D. Reichelt",
        "Practical jet flavour through NNLO", "Eur. Phys. J. C", "82", "2022", "632", "2205.01109");
    static final Reference DYNAMIC_R = ref("Mukhopadhyaya:2023rsb", "B. Mukhopadhyaya, T. Samui and R.K. Singh",
        "Dynamic Radius Jet Clustering Algorithm", "JHEP", "04", "2023", "019", "2301.13074", "10.1007/JHEP04(2023)019");

    /* ------------------------------------------------------------------ */
    /* The components                                                      */
    /* ------------------------------------------------------------------ */

    /** A component: the key its implementation notes itself under, its contrib, what it is, what to cite. */
    public record Component(String key, String contrib, String label, List<Reference> references) {
    }

    private static final Map<String, Component> COMPONENTS = new LinkedHashMap<>();

    private static void component(String key, String contrib, String label, Reference... refs) {
        COMPONENTS.put(key, new Component(key, contrib, label, List.of(refs)));
    }

    static {
        component("centauro", "Centauro", "Centauro jet algorithm for DIS", CENTAURO);
        component("clusteringveto", "ClusteringVetoPlugin", "mass-jump clustering veto", MASS_JUMP);
        component("cs", "ConstituentSubtractor", "constituent subtraction", CS);
        component("ics", "ConstituentSubtractor", "iterative constituent subtraction", CS, ICS);
        component("disgenkt", "DISGenkt", "generalised-kt algorithm for DIS", DIS_GENKT, PANSCALES_DIS);
        component("dynamicr", "DynamicR", "Dynamic Radius jet algorithm", DYNAMIC_R);
        component("ecf", "EnergyCorrelator", "energy correlation functions, C and D series", ECF, D2);
        component("ecfg", "EnergyCorrelator", "generalised energy correlators, N, M and U series", ECF, ECFG);
        component("flavorcone", "FlavorCone", "FlavorCone jets", FLAVOR_CONE);
        component("genericsubtractor", "GenericSubtractor", "generic pileup subtraction of jet shapes", GENERIC_SUB);
        component("jetcleanser", "JetCleanser", "jet cleansing", CLEANSING);
        component("jetffmoments", "JetFFMoments", "jet fragmentation function moments", FF_MOMENTS);
        component("jetswithoutjets", "JetsWithoutJets", "jet observables without jet algorithms", JWJ);
        component("ktcluscxx", "KTClusCXX", "KTCLUS algorithms", KT_CDSW, KT_CDW, KT_ES);
        component("lund", "LundPlane", "Lund jet plane", LUND, LUND_SPIN, LUND_SOFT_SPIN);
        component("nsubjettiness", "Nsubjettiness", "N-subjettiness", NSUB, NSUB_AXES);
        component("xcone", "Nsubjettiness", "XCone", XCONE, XCONE_BOOSTED);
        component("qcdaware", "QCDAwarePlugin", "QCD-aware partonic clustering", QCD_AWARE);
        component("mmdt", "RecursiveTools", "modified Mass Drop Tagger", MMDT);
        component("softdrop", "RecursiveTools", "Soft Drop", SOFT_DROP);
        component("rsd", "RecursiveTools", "Recursive and Bottom-up Soft Drop", RSD);
        component("isd", "RecursiveTools", "Iterated Soft Drop", ISD);
        component("scjet", "ScJet", "semi-classical jet algorithm", SCJET);
        component("signalfree", "SignalFreeBackgroundEstimator", "signal-free pileup density", SIGNAL_FREE);
        component("softkiller", "SoftKiller", "SoftKiller", SOFT_KILLER);
        component("subjetcounting", "SubjetCounting", "subjet counting", SUBJET_COUNTING);
        component("valencia", "ValenciaPlugin", "Valencia jet algorithm", VALENCIA, VLC);
        component("variabler", "VariableR", "variable-R jets", VARIABLE_R);
        component("ifn", "IFNPlugin", "Interleaved Flavour Neutralisation", IFN);
        component("cmp", "CMPPlugin", "CMP flavoured anti-kt", CMP, IFN);
        component("ghs", "GHSAlgo", "GHS flavour dressing", GHS, GHS_ERRATUM, IFN);
        component("sdf", "SDFPlugin", "Soft Drop Flavour", SDF);
    }

    private static final Map<String, Boolean> USED = new LinkedHashMap<>();

    private ContribCitations() {
    }

    /** Notes that a component ran. Cheap after the first call. */
    public static void use(String key) {
        synchronized (USED) {
            USED.putIfAbsent(key, Boolean.TRUE);
        }
    }

    /** The keys noted so far, in the order they were. */
    public static List<String> used() {
        synchronized (USED) {
            return new ArrayList<>(USED.keySet());
        }
    }

    /** Every component, by key. */
    public static Map<String, Component> components() {
        return java.util.Collections.unmodifiableMap(COMPONENTS);
    }

    /** The components of a contrib, by its directory name ("RecursiveTools"). */
    public static List<Component> ofContrib(String contrib) {
        final List<Component> out = new ArrayList<>();
        for (Component c : COMPONENTS.values()) {
            if (c.contrib().equalsIgnoreCase(contrib)) out.add(c);
        }
        return out;
    }

    /** The distinct references of a contrib. */
    public static List<Reference> referencesOf(String contrib) {
        final List<Reference> out = new ArrayList<>();
        for (Component c : ofContrib(contrib)) {
            for (Reference r : c.references()) if (!out.contains(r)) out.add(r);
        }
        return out;
    }

    /** The references of the components used (or of all when {@code all}), FastJet's manual first. */
    public static List<Reference> bibliography(boolean all) {
        final List<Reference> bib = new ArrayList<>();
        bib.add(Citations.FASTJET_MANUAL);
        for (Component c : all ? COMPONENTS.values() : usedComponents()) {
            for (Reference r : c.references()) if (!bib.contains(r)) bib.add(r);
        }
        return bib;
    }

    private static List<Component> usedComponents() {
        final List<Component> out = new ArrayList<>();
        for (String key : used()) {
            final Component c = COMPONENTS.get(key);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** BibTeX for a .bib file. */
    public static String bibtex(boolean all) {
        final StringBuilder b = new StringBuilder("% fjcontrib " + RELEASE + " (Java translation in Sphere): references "
            + (all ? "of every contrib" : "of the contribs used") + "\n");
        for (Reference r : bibliography(all)) b.append('\n').append(r.bibtex()).append('\n');
        return b.toString();
    }

    /** The text of the Citation fjcontrib window. */
    public static String text() {
        final StringBuilder t = new StringBuilder();
        t.append("fjcontrib ").append(RELEASE).append(", Java translation in Sphere (https://fastjet.hepforge.org/contrib/).\n");
        t.append("Each contrib is by its own authors and distributed under the GNU GPL v2 or later;\n");
        t.append("FastJet itself is to be cited as well:\n  ").append(Citations.FASTJET_MANUAL.text()).append("\n\n");

        final List<Component> used = usedComponents();
        t.append("Used in this session (':fjco validate' and ':fjco selftest' count: they run the contribs):\n");
        if (used.isEmpty()) t.append("  nothing yet: the contribs appear here once they have run.\n");
        for (Component c : used) {
            t.append("  ").append(c.label()).append(" (").append(c.contrib()).append("):\n");
            for (Reference r : c.references()) t.append("      ").append(r.text()).append('\n');
        }
        if (!used.isEmpty()) {
            t.append("\nBibTeX of what was used (':fjco bib <file>' writes it):\n");
            for (Reference r : bibliography(false)) t.append('\n').append(r.bibtex()).append('\n');
        }

        t.append("\nEvery contrib of the release:\n");
        String last = "";
        for (Component c : COMPONENTS.values()) {
            if (!c.contrib().equals(last)) {
                t.append("  ").append(c.contrib()).append('\n');
                last = c.contrib();
            }
            t.append("    ").append(c.label()).append(":\n");
            for (Reference r : c.references()) t.append("        ").append(r.text()).append('\n');
        }
        return t.toString();
    }
}

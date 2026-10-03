package com.sphere.core.fjcontrib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The contribs of fjcontrib 1.104 as Sphere offers them: for each, its
 * version, what kind of tool it is, what it does in one line, and how it is
 * reached from the console. ":fjco list" and ":fjco info" read it, and so
 * does the help; the references are in {@link ContribCitations}, under the
 * keys given here.
 */
public final class ContribCatalog {

    /** What a contrib is for, in the order an analysis meets them. */
    public enum Category {
        CLUSTERING("jet algorithms"),
        FLAVOUR("jet flavour"),
        GROOMING("grooming and tagging"),
        SUBSTRUCTURE("substructure observables"),
        PILEUP("pileup and underlying event"),
        EVENT_SHAPE("event shapes");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One contrib.
     *
     * @param name       the directory name in fjcontrib ("RecursiveTools")
     * @param version    the version translated
     * @param category   what it is for
     * @param summary    what it does, one line
     * @param details    a few lines more: the idea, the parameters that matter
     * @param usage      how Sphere reaches it, one command per line
     * @param specs      the names it has in ':fjet def' specs, if it is a jet algorithm
     * @param citations  its keys in {@link ContribCitations}
     */
    public record Contrib(String name, String version, Category category, String summary, String details,
                         List<String> usage, List<String> specs, List<String> citations) {
    }

    private static final Map<String, Contrib> CONTRIBS = new LinkedHashMap<>();

    private static void add(String name, String version, Category category, String summary, String details,
                            String[] usage, String[] specs, String... citations) {
        CONTRIBS.put(name.toLowerCase(Locale.ROOT), new Contrib(name, version, category, summary, details,
            List.of(usage), List.of(specs), List.of(citations)));
    }

    private static String[] u(String... lines) {
        return lines;
    }

    static {
        // --- jet algorithms ------------------------------------------------
        add("VariableR", "1.2.1", Category.CLUSTERING,
            "Jets whose radius shrinks with pt, R_eff = rho/pt, between Rmin and Rmax",
            "Krohn, Thaler and Wang: a boosted object keeps all its decay products and no more. "
                + "rho (GeV) sets the scale, type the kt/C-A/anti-kt family.",
            u(":fjet def vr variabler:rho=600,rmin=0.02,rmax=1.5,type=akt", ":fjco cluster variabler:rho=300,rmax=1.0"),
            new String[]{"variabler"}, "variabler");
        add("ValenciaPlugin", "2.0.2", Category.CLUSTERING,
            "The Valencia (VLC) algorithm for lepton colliders with beam background",
            "An e+e- algorithm with a beam distance that removes forward background: R, beta (energy exponent), "
                + "gamma (angular exponent of the beam distance).",
            u(":fjet def vlc valencia:1.0,beta=1,gamma=1"), new String[]{"valencia"}, "valencia");
        add("Centauro", "1.0.0", Category.CLUSTERING,
            "Asymmetric jet clustering for deep-inelastic scattering, in the Breit frame",
            "Distances in etabar = 2pT/(E-pz); give gammaE and gammaPz to boost to the Breit frame on the fly.",
            u(":fjet def cen centauro:1.0"), new String[]{"centauro"}, "centauro");
        add("DISGenkt", "1.1.0", Category.CLUSTERING,
            "A spherically invariant generalised-kt family for DIS, in the Breit frame",
            "d_ij = 2 min(E_i^2p, E_j^2p)(1 - cos theta_ij); p >= 0 selects the member, beam = +1 or -1 the "
                + "direction of the struck quark.",
            u(":fjet def dis disgenkt:1.0,p=1,beam=1"), new String[]{"disgenkt"}, "disgenkt");
        add("KTClusCXX", "1.0.1", Category.CLUSTERING,
            "Seymour's KTCLUS, the kt algorithms of the Fortran era, in every mode",
            "The four-digit mode chooses the collision type, the angular measure, inclusive or exclusive "
                + "and the recombination (e.g. 4111 for pp, Delta R, inclusive, E scheme).",
            u(":fjet def ktclus ktcluscxx:1.0,mode=4111"), new String[]{"ktcluscxx"}, "ktcluscxx");
        add("ScJet", "1.1.0", Category.CLUSTERING,
            "Semi-classical jets: a distance from a simple model of how a jet radiates",
            "Tseng and Evans: kt-like with an energy (mt, pt or et) and an angular exponent rexp.",
            u(":fjet def sc scjet:0.7,mode=mt,rexp=3"), new String[]{"scjet"}, "scjet");
        add("ClusteringVetoPlugin", "1.0.0", Category.CLUSTERING,
            "The mass-jump algorithm: clustering stops where the mass jumps",
            "A merging whose mass exceeds mu and theta times the heavier parent's is vetoed and both turn "
                + "passive, which keeps two nearby boosted objects apart.",
            u(":fjet def mj massjump:1.0,mu=30,theta=0.7,type=ca"), new String[]{"massjump", "clusteringveto"},
            "clusteringveto");
        add("DynamicR", "1.0.2", Category.CLUSTERING,
            "Jets whose radius grows with the spread of their constituents, R_d = R0 + sigma",
            "Mukhopadhyaya, Samui and Singh: a jet with two separated prongs keeps growing, a narrow one "
                + "stops at R0. The final radius of each jet is shown by ':fjco cluster'.",
            u(":fjet def drak dynamicr:0.5,type=ak", ":fjco cluster dynamicr:0.5"), new String[]{"dynamicr"},
            "dynamicr");
        add("Nsubjettiness", "2.3.2", Category.SUBSTRUCTURE,
            "N-subjettiness tau_N and its ratios, and the XCone exclusive cone algorithm",
            "tau_N measures how N-pronged a jet is; tau21 tags W, Z, H, tau32 tops. Axes: wta_kt (default), "
                + "kt, ca, onepass_*, min. XCone finds exactly N cone jets.",
            u(":fjco obs tau21 tau32", ":fjet def x3 xcone:0.4,n=3,beta=2"), new String[]{"xcone"},
            "nsubjettiness", "xcone");
        add("QCDAwarePlugin", "2.0.0", Category.FLAVOUR,
            "Partonic clustering through Standard Model vertices only, for truth flavour labels",
            "q g -> q, g g -> g, q qbar -> g, q/l gamma -> q/l, l+ l- -> gamma; the user index carries the "
                + "PDG code (from LHE or HepMC events). Couplings and colour factors optional.",
            u(":fjet def qa qcdaware:0.4,dm=akt", ":fjco cluster qcdaware:0.4,dm=kt,couplings"),
            new String[]{"qcdaware"}, "qcdaware");
        add("FlavorCone", "1.0.0", Category.FLAVOUR,
            "Jets built around heavy-flavour seeds, with no overlap between them",
            "Ilten, Rodd, Thaler and Williams: each particle within rcut of a seed goes to the nearest seed.",
            u(":fjco flavour cone [--rcut 0.5] [--flav 5]"), new String[0], "flavorcone");

        // --- jet flavour ---------------------------------------------------
        add("IFNPlugin", "1.0.4", Category.FLAVOUR,
            "Interleaved Flavour Neutralisation: anti-kt jets with infrared and collinear safe flavour",
            "The kinematics are exactly anti-kt's; before a soft flavoured object enters a jet it may be "
                + "neutralised by a nearby opposite flavour. alpha = 2 (or 1), omega = 3 - alpha.",
            u(":fjet def ifn ifn:0.4,alpha=2", ":fjco flavour", ":fjco flavour irc"), new String[]{"ifn"}, "ifn");
        add("CMPPlugin", "1.0.0", Category.FLAVOUR,
            "The Czakon-Mitov-Poncelet flavoured anti-kt algorithm",
            "Opposite flavours are brought together first by a factor S_ij on their distance; a sets its "
                + "reach. With the sqrt-coshy correction (default) it is infrared and collinear safe.",
            u(":fjet def cmp cmp:0.4,a=0.1", ":fjco flavour"), new String[]{"cmp"}, "cmp");
        add("GHSAlgo", "1.0.0", Category.FLAVOUR,
            "Gauld-Huss-Stagnitto flavour dressing of any jets",
            "Flavour is attached after the jets are found: flavoured particles cluster among themselves "
                + "and to the hard jets with a flavour-kt distance (alpha, omega).",
            u(":fjco flavour --ptmin 20", ":fjco flavour irc"), new String[0], "ghs");
        add("SDFPlugin", "1.0.1", Category.FLAVOUR,
            "Soft Drop Flavour: the net flavour of what survives Soft Drop after JADE reclustering",
            "Infrared and collinear safe through NNLO; beta = 2, zcut = 0.1 by default.",
            u(":fjco flavour"), new String[0], "sdf");

        // --- grooming ------------------------------------------------------
        add("RecursiveTools", "2.0.4", Category.GROOMING,
            "Soft Drop, mMDT, Recursive and Bottom-up Soft Drop, Iterated Soft Drop, Recluster",
            "Undo the C/A clustering and drop soft wide-angle branches: z > zcut (R/R0)^beta. ISD counts the "
                + "emissions that pass (n_SD, a quark/gluon discriminant).",
            u(":fjco groom sd 0 0.1", ":fjco groom rsd 1 0.1 -1", ":fjco groom isd -1 0.005",
                ":fjco obs sdmass zg rg nsd"), new String[0], "softdrop", "mmdt", "rsd", "isd");

        // --- substructure --------------------------------------------------
        add("EnergyCorrelator", "1.3.2", Category.SUBSTRUCTURE,
            "Energy correlation functions and the C, D, N, M and U series",
            "C2, D2 for two-prong tagging, N2 and M2 from generalised correlators, U for quark/gluon.",
            u(":fjco obs c2 d2 n2 m2 u1"), new String[0], "ecf", "ecfg");
        add("LundPlane", "2.1.2", Category.SUBSTRUCTURE,
            "The Lund jet plane: (ln 1/Delta, ln kt) of every declustering",
            "Primary and secondary planes, the e+e- declustering with azimuths; ':fjco lundmap' fills the "
                + "average plane over all events.",
            u(":fjco lund 0 --secondary", ":fjco lundmap --bins 20"), new String[0], "lund");
        add("SubjetCounting", "1.0.1", Category.SUBSTRUCTURE,
            "Counting subjets, n_Kt and n_CA, for high-multiplicity searches",
            "n_Kt: exclusive kt subjets above f_Kt x pt; n_CA: C/A declustering with mass and ycut conditions.",
            u(":fjco obs nkt:fkt=0.06,ptcut=40 nca:mcut=30,ycut=0.1"), new String[0], "subjetcounting");
        add("JetFFMoments", "1.0.0", Category.SUBSTRUCTURE,
            "Moments of the jet fragmentation function, pileup subtracted",
            "M_N = sum z_i^N; N from -0.5 upwards, rho-subtracted with the jet area.",
            u(":fjco obs ffm:n=2"), new String[0], "jetffmoments");

        // --- pileup --------------------------------------------------------
        add("ConstituentSubtractor", "1.4.7", Category.PILEUP,
            "Particle-level pileup subtraction: ghosts carry rho and eat nearby constituents",
            "Event-wide or jet by jet; the iterative version (ICS) runs several passes of max distance.",
            u(":fjco subtract cs", ":fjco subtract ics", ":fjco arena"), new String[0], "cs", "ics");
        add("SoftKiller", "1.0.0", Category.PILEUP,
            "Removes every particle below the pt cut that leaves half the grid cells empty",
            "Fast and effective at high pileup; the grid size (0.4) is the only parameter.",
            u(":fjco subtract softkiller 0.4", ":fjco arena"), new String[0], "softkiller");
        add("GenericSubtractor", "1.3.1", Category.PILEUP,
            "Pileup subtraction of any jet shape from its derivatives in ghost pt",
            "Extrapolates the shape to zero pileup; gives each derivative and a truncation error.",
            u(":fjco subtract generic mass", ":fjco arena"), new String[0], "genericsubtractor");
        add("JetCleanser", "1.0.1", Category.PILEUP,
            "Jet cleansing: rescales subjets with the charged fractions of the leading vertex and pileup",
            "Linear, Gaussian or jet-vertex-fraction modes; needs charged tracks split by vertex.",
            u(":fjco arena --charged 0.6"), new String[0], "jetcleanser");
        add("SignalFreeBackgroundEstimator", "1.0.1", Category.PILEUP,
            "A pileup density that does not grow with the number of signal jets",
            "Areas around signal seeds are cut out of the grid before the weighted median is taken.",
            u(":fjco rho", ":fjco arena"), new String[0], "signalfree");

        // --- event shapes --------------------------------------------------
        add("JetsWithoutJets", "1.0.0", Category.EVENT_SHAPE,
            "Jet-like event shapes computed without finding jets: multiplicity, HT, missing HT",
            "Each particle is weighted by whether a cone of Rjet around it passes ptcut; smooth in R and pt.",
            u(":fjco shapes --rjet 0.4 --ptcut 25"), new String[0], "jetswithoutjets");
    }

    private ContribCatalog() {
    }

    public static List<Contrib> all() {
        return Collections.unmodifiableList(new ArrayList<>(CONTRIBS.values()));
    }

    /** A contrib by name, case and spelling tolerant ("softdrop" finds RecursiveTools). */
    public static Contrib find(String name) {
        if (name == null) return null;
        final String n = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        final Contrib exact = CONTRIBS.get(n);
        if (exact != null) return exact;
        for (Contrib c : CONTRIBS.values()) {
            if (c.name().toLowerCase(Locale.ROOT).startsWith(n)) return c;
        }
        // a component or spec named exactly, before any looser match
        for (Contrib c : CONTRIBS.values()) {
            if (c.citations().contains(n) || c.specs().contains(n)) return c;
        }
        for (Contrib c : CONTRIBS.values()) {
            for (String key : c.citations()) {
                final ContribCitations.Component comp = ContribCitations.components().get(key);
                if (comp != null && comp.label().toLowerCase(Locale.ROOT).replace(" ", "").contains(n)) return c;
            }
        }
        return null;
    }

    public static List<Contrib> of(Category category) {
        final List<Contrib> out = new ArrayList<>();
        for (Contrib c : CONTRIBS.values()) if (c.category() == category) out.add(c);
        return out;
    }

    public static int size() {
        return CONTRIBS.size();
    }
}

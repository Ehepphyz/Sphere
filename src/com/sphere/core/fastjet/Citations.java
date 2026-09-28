package com.sphere.core.fastjet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The references FastJet, its plugins and the algorithms built on it ask to be
 * cited. They are not printed into the output: Sphere shows them on demand,
 * from Citations in the console's context menu. Each part of the library notes
 * itself here when it first runs, so that the list names what this session
 * actually used.
 */
public final class Citations {

    /** A published reference, printable as text and as BibTeX. */
    public record Reference(String key, String authors, String title, String journal,
                            String volume, String year, String pages, String eprint, String doi) {

        public String text() {
            final String plain = title.replace("$", "").replace("{", "").replace("}", "");
            final StringBuilder s = new StringBuilder(authors).append(", \"").append(plain).append("\"");
            if (!journal.isEmpty()) {
                s.append(", ").append(journal).append(' ').append(volume)
                    .append(" (").append(year).append(") ").append(pages);
            }
            if (!eprint.isEmpty()) {
                s.append(journal.isEmpty() ? ", " : " [").append(eprint.contains("/") ? eprint : "arXiv:" + eprint)
                    .append(journal.isEmpty() ? "" : "]");
            }
            return s.append('.').toString();
        }

        public String bibtex() {
            final StringBuilder b = new StringBuilder("@article{").append(key).append(",\n");
            b.append("    author = \"").append(bibAuthors(authors)).append("\",\n");
            b.append("    title = \"{").append(title).append("}\",\n");
            if (!eprint.isEmpty()) {
                b.append("    eprint = \"").append(eprint).append("\",\n");
                if (!eprint.contains("/")) b.append("    archivePrefix = \"arXiv\",\n");
            }
            if (!doi.isEmpty()) b.append("    doi = \"").append(doi).append("\",\n");
            if (!journal.isEmpty()) {
                b.append("    journal = \"").append(journal).append("\",\n");
                b.append("    volume = \"").append(volume).append("\",\n");
                b.append("    pages = \"").append(pages).append("\",\n");
            }
            b.append("    year = \"").append(year).append("\"\n}");
            return b.toString();
        }

        /** "M. Cacciari, G.P. Salam and G. Soyez" as "Cacciari, M. and Salam, G.P. and Soyez, G.". */
        private static String bibAuthors(String authors) {
            final List<String> out = new ArrayList<>();
            for (String one : authors.replace(" and ", ", ").split(",\\s*")) {
                final int cut = one.lastIndexOf(". ");
                out.add(cut < 0 ? one.trim() : one.substring(cut + 2).trim() + ", " + one.substring(0, cut + 1).trim());
            }
            return String.join(" and ", out);
        }
    }

    private static Reference ref(String key, String authors, String title, String journal, String volume,
                                 String year, String pages, String eprint) {
        return new Reference(key, authors, title, journal, volume, year, pages, eprint, "");
    }

    /** FastJet itself: always to be cited when it is used. */
    public static final Reference FASTJET_MANUAL = new Reference("Cacciari:2011ma",
        "M. Cacciari, G.P. Salam and G. Soyez", "FastJet User Manual",
        "Eur. Phys. J. C", "72", "2012", "1896", "1111.6097", "10.1140/epjc/s10052-012-1896-2");
    public static final Reference FASTJET_N3 = ref("Cacciari:2005hq",
        "M. Cacciari and G.P. Salam", "Dispelling the $N^{3}$ myth for the $k_t$ jet-finder",
        "Phys. Lett. B", "641", "2006", "57", "hep-ph/0512210");

    private static final Reference ANTIKT = ref("Cacciari:2008gp", "M. Cacciari, G.P. Salam and G. Soyez",
        "The anti-$k_t$ jet clustering algorithm", "JHEP", "04", "2008", "063", "0802.1189");
    private static final Reference KT_CATANI = ref("Catani:1993hr",
        "S. Catani, Y.L. Dokshitzer, M.H. Seymour and B.R. Webber",
        "Longitudinally invariant $K_t$ clustering algorithms for hadron hadron collisions",
        "Nucl. Phys. B", "406", "1993", "187", "");
    private static final Reference KT_ELLIS = ref("Ellis:1993tq", "S.D. Ellis and D.E. Soper",
        "Successive combination jet algorithm for hadron collisions", "Phys. Rev. D", "48", "1993", "3160",
        "hep-ph/9305266");
    private static final Reference CA_DOKSHITZER = ref("Dokshitzer:1997in",
        "Y.L. Dokshitzer, G.D. Leder, S. Moretti and B.R. Webber", "Better jet clustering algorithms",
        "JHEP", "08", "1997", "001", "hep-ph/9707323");
    private static final Reference CA_WOBISCH = ref("Wobisch:1998wt", "M. Wobisch and T. Wengler",
        "Hadronization corrections to jet cross-sections in deep inelastic scattering", "", "", "1999", "",
        "hep-ph/9907280");
    private static final Reference DURHAM = ref("Catani:1991hj",
        "S. Catani, Y.L. Dokshitzer, M. Olsson, G. Turnock and B.R. Webber",
        "New clustering algorithm for multi-jet cross-sections in e+ e- annihilation",
        "Phys. Lett. B", "269", "1991", "432", "");
    private static final Reference AREAS = ref("Cacciari:2008gn", "M. Cacciari, G.P. Salam and G. Soyez",
        "The Catchment Area of Jets", "JHEP", "04", "2008", "005", "0802.1188");
    private static final Reference PILEUP = ref("Cacciari:2007fd", "M. Cacciari and G.P. Salam",
        "Pileup subtraction using jet areas", "Phys. Lett. B", "659", "2008", "119", "0707.1378");
    private static final Reference BDRS = ref("Butterworth:2008iy",
        "J.M. Butterworth, A.R. Davison, M. Rubin and G.P. Salam",
        "Jet substructure as a new Higgs search channel at the LHC", "Phys. Rev. Lett.", "100", "2008",
        "242001", "0802.2470");
    private static final Reference PRUNING = ref("Ellis:2009me", "S.D. Ellis, C.K. Vermilion and J.R. Walsh",
        "Techniques for improved heavy particle searches with jet substructure", "Phys. Rev. D", "80", "2009",
        "051501", "0903.5081");
    private static final Reference TRIMMING = ref("Krohn:2009th", "D. Krohn, J. Thaler and L.-T. Wang",
        "Jet Trimming", "JHEP", "02", "2010", "084", "0912.1342");
    private static final Reference JH_TOP = ref("Kaplan:2008ie",
        "D.E. Kaplan, K. Rehermann, M.D. Schwartz and B. Tweedie",
        "Top Tagging: A Method for Identifying Boosted Hadronically Decaying Top Quarks",
        "Phys. Rev. Lett.", "101", "2008", "142001", "0806.0848");
    private static final Reference SOFT_DROP = ref("Larkoski:2014wba",
        "A.J. Larkoski, S. Marzani, G. Soyez and J. Thaler", "Soft Drop", "JHEP", "05", "2014", "146",
        "1402.2657");
    private static final Reference MMDT = ref("Dasgupta:2013ihk",
        "M. Dasgupta, A. Fregoso, S. Marzani and G.P. Salam", "Towards an understanding of jet substructure",
        "JHEP", "09", "2013", "029", "1307.0007");
    private static final Reference NSUB = ref("Thaler:2010tr", "J. Thaler and K. Van Tilburg",
        "Identifying Boosted Objects with N-subjettiness", "JHEP", "03", "2011", "015", "1011.2268");
    private static final Reference NSUB_AXES = ref("Thaler:2011gf", "J. Thaler and K. Van Tilburg",
        "Maximizing Boosted Top Identification by Minimizing N-subjettiness", "JHEP", "02", "2012", "093",
        "1108.2701");
    private static final Reference ECF = ref("Larkoski:2013eya", "A.J. Larkoski, G.P. Salam and J. Thaler",
        "Energy Correlation Functions for Jet Substructure", "JHEP", "06", "2013", "108", "1305.0007");
    private static final Reference D2 = ref("Larkoski:2014gra", "A.J. Larkoski, I. Moult and D. Neill",
        "Power Counting to Better Jet Observables", "JHEP", "12", "2014", "009", "1409.6298");
    private static final Reference N2_M2 = ref("Moult:2016cvt", "I. Moult, L. Necib and J. Thaler",
        "New Angles on Energy Correlation Functions", "JHEP", "12", "2016", "153", "1609.07483");
    private static final Reference LUND = ref("Dreyer:2018nbf", "F.A. Dreyer, G.P. Salam and G. Soyez",
        "The Lund Jet Plane", "JHEP", "12", "2018", "064", "1807.04758");
    private static final Reference PULL = ref("Gallicchio:2010sw", "J. Gallicchio and M.D. Schwartz",
        "Seeing in Color: Jet Superstructure", "Phys. Rev. Lett.", "105", "2010", "022001", "1001.5027");
    private static final Reference SISCONE = ref("Salam:2007xv", "G.P. Salam and G. Soyez",
        "A practical seedless infrared-safe cone jet algorithm", "JHEP", "05", "2007", "086", "0704.0292");

    /** What each component cites, by the key it notes itself under. */
    private static final Map<String, Object[]> COMPONENTS = new LinkedHashMap<>();

    static {
        component("kt", "kt algorithm", KT_CATANI, KT_ELLIS);
        component("cambridge", "Cambridge/Aachen algorithm", CA_DOKSHITZER, CA_WOBISCH);
        component("antikt", "anti-kt algorithm", ANTIKT);
        component("genkt", "generalised kt algorithm", ANTIKT);
        component("eekt", "e+e- kt (Durham) algorithm", DURHAM);
        component("eegenkt", "e+e- generalised kt algorithm", DURHAM, ANTIKT);
        component("areas", "jet areas", AREAS);
        component("rho", "background density rho and area subtraction", PILEUP);
        component("filter", "filtering, mass-drop tagging", BDRS);
        component("trimming", "trimming", TRIMMING);
        component("pruner", "pruning", PRUNING);
        component("jhtop", "Johns Hopkins top tagger", JH_TOP);
        component("softdrop", "Soft Drop", SOFT_DROP);
        component("mmdt", "modified Mass Drop Tagger", MMDT);
        component("nsubjettiness", "N-subjettiness", NSUB, NSUB_AXES);
        component("ecf", "energy correlation functions (e2, e3, C2)", ECF);
        component("d2", "D2", D2);
        component("n2m2", "N2 and M2", N2_M2);
        component("lund", "Lund jet plane", LUND);
        component("pull", "jet pull", PULL);
        component("irc", "infrared and collinear safety tests (canonical configurations)", SISCONE);
    }

    private static void component(String key, String label, Reference... refs) {
        COMPONENTS.put(key, new Object[]{label, refs});
    }

    private static final Map<String, Boolean> USED = new LinkedHashMap<>();
    private static final Map<String, String> PLUGINS = new LinkedHashMap<>();

    private Citations() {
    }

    /** Notes that a component ran (a key of the table above). Cheap after the first call. */
    public static void use(String key) {
        synchronized (USED) {
            USED.putIfAbsent(key, Boolean.TRUE);
        }
    }

    /**
     * Keeps the credit a plugin asks for, the banner its C++ version prints
     * (framing removed), instead of printing it.
     */
    public static void plugin(String name, String banner) {
        synchronized (PLUGINS) {
            PLUGINS.putIfAbsent(name, clean(banner));
        }
    }

    private static String clean(String banner) {
        final StringBuilder out = new StringBuilder();
        for (String line : banner.split("\\R")) {
            String s = line.strip();
            if (s.startsWith("#")) s = s.substring(1);
            s = s.replaceAll("\\s+o$", "").replaceAll("^o+$", "").replaceAll("^[-*o\\s]+$", "").strip();
            if (s.isEmpty() || s.startsWith("!!!")) continue;
            if (out.length() > 0) out.append('\n');
            out.append("      ").append(s);
        }
        return out.toString();
    }

    /** The text of the Citation FastJet window. */
    public static String fastjetText() {
        final StringBuilder t = new StringBuilder();
        final List<Reference> bib = new ArrayList<>();
        t.append(FastJet.VERSION_LINE).append('\n');
        t.append("Java translation of FastJet ").append(FastJet.FASTJET_VERSION)
            .append(" (M. Cacciari, G.P. Salam and G. Soyez, https://fastjet.fr),\n")
            .append("distributed under the GNU GPL v2 or later.\n\n");
        t.append("Please cite, if you use it for scientific work:\n");
        t.append("  ").append(FASTJET_MANUAL.text()).append('\n');
        t.append("and optionally:\n");
        t.append("  ").append(FASTJET_N3.text()).append('\n');
        bib.add(FASTJET_MANUAL);
        bib.add(FASTJET_N3);

        final List<String> used;
        synchronized (USED) {
            used = new ArrayList<>(USED.keySet());
        }
        final Map<String, String> plugins;
        synchronized (PLUGINS) {
            plugins = new LinkedHashMap<>(PLUGINS);
        }
        t.append("\nUsed in this session:\n");
        if (used.isEmpty() && plugins.isEmpty()) {
            t.append("  nothing yet: the algorithms, plugins and tools appear here once they have run.\n");
        }
        for (String key : used) {
            final Object[] c = COMPONENTS.get(key);
            if (c == null) continue;
            t.append("  ").append(c[0]).append(":\n");
            for (Reference r : (Reference[]) c[1]) {
                t.append("      ").append(r.text()).append('\n');
                if (!bib.contains(r)) bib.add(r);
            }
        }
        for (Map.Entry<String, String> p : plugins.entrySet()) {
            t.append("  ").append(p.getKey()).append(" plugin:\n").append(p.getValue()).append('\n');
            if (p.getKey().startsWith("SISCone") && !bib.contains(SISCONE)) bib.add(SISCONE);
        }

        t.append("\nBibTeX:\n");
        for (Reference r : bib) t.append('\n').append(r.bibtex()).append('\n');
        return t.toString();
    }
}

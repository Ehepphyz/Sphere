package com.sphere.core.hepmc3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Version and references of Sphere's Java HepMC3. Nothing of this is printed
 * into the output but the version line: the references are shown from
 * Citations in the console's context menu, with the formats this session
 * read or wrote, since the Les Houches event file has a publication of its
 * own; {@code :hepmc bib} writes them as BibTeX.
 */
public final class HepMC3Citations {

    /** A publication, as the window shows it and as BibTeX. */
    public record Reference(String key, String text, String bibtex) {
    }

    public static final Reference HEPMC3 = new Reference("Buckley:2019xhk",
        "A. Buckley, P. Ilten, D. Konstantinov, L. Lönnblad, J. Monk, W. Pokorski, T. Przedzinski and A. Verbytskyi,\n"
            + "  \"The HepMC3 event record library for Monte Carlo event generators\",\n"
            + "  Comput. Phys. Commun. 260 (2021) 107310 [arXiv:1912.08005].",
        String.join("\n",
            "@article{Buckley:2019xhk,",
            "    author = \"Buckley, Andy and Ilten, Philip and Konstantinov, Dmitri and L{\\\"o}nnblad, Leif",
            "              and Monk, James and Pokorski, Witold and Przedzinski, Tomasz and Verbytskyi, Andrii\",",
            "    title = \"{The HepMC3 event record library for Monte Carlo event generators}\",",
            "    eprint = \"1912.08005\",",
            "    archivePrefix = \"arXiv\",",
            "    primaryClass = \"hep-ph\",",
            "    doi = \"10.1016/j.cpc.2020.107310\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"260\",",
            "    pages = \"107310\",",
            "    year = \"2021\"",
            "}"));

    public static final Reference HEPMC2 = new Reference("Dobbs:2001ck",
        "M. Dobbs and J. B. Hansen, \"The HepMC C++ Monte Carlo event record for High Energy Physics\",\n"
            + "  Comput. Phys. Commun. 134 (2001) 41.",
        String.join("\n",
            "@article{Dobbs:2001ck,",
            "    author = \"Dobbs, Matt and Hansen, Jorgen Beck\",",
            "    title = \"{The HepMC C++ Monte Carlo event record for High Energy Physics}\",",
            "    doi = \"10.1016/S0010-4655(00)00189-2\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"134\",",
            "    pages = \"41--46\",",
            "    year = \"2001\"",
            "}"));

    public static final Reference LHEF = new Reference("Alwall:2006yp",
        "J. Alwall et al., \"A Standard format for Les Houches event files\",\n"
            + "  Comput. Phys. Commun. 176 (2007) 300 [arXiv:hep-ph/0609017].",
        String.join("\n",
            "@article{Alwall:2006yp,",
            "    author = \"Alwall, Johan and others\",",
            "    title = \"{A Standard format for Les Houches event files}\",",
            "    eprint = \"hep-ph/0609017\",",
            "    doi = \"10.1016/j.cpc.2006.11.010\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"176\",",
            "    pages = \"300--304\",",
            "    year = \"2007\"",
            "}"));

    /** The formats this session used ("Asciiv3", "HepMC2", "LHEF"...), in the order first used. */
    private static final Set<String> USED = new LinkedHashSet<>();

    private HepMC3Citations() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated.");
    }

    /** Notes a format read or written, for the Citation window. */
    public static void used(String format) {
        synchronized (USED) {
            USED.add(format);
        }
    }

    public static List<String> usedFormats() {
        synchronized (USED) {
            return new ArrayList<>(USED);
        }
    }

    /** What to cite: HepMC3 always, HepMC2 when its format was used, the LHEF paper when LHE files were. */
    public static List<Reference> bibliography(boolean all) {
        final List<String> used = usedFormats();
        final List<Reference> out = new ArrayList<>();
        out.add(HEPMC3);
        if (all || used.contains("HepMC2") || used.contains("HEPEVT")) out.add(HEPMC2);
        if (all || used.contains("LHEF")) out.add(LHEF);
        return out;
    }

    public static String bibtex(boolean all) {
        final StringBuilder b = new StringBuilder();
        for (Reference r : bibliography(all)) b.append(r.bibtex()).append("\n\n");
        return b.toString();
    }

    /** The text of the Citation HepMC3 window. */
    public static String text() {
        final StringBuilder t = new StringBuilder(HepMC3.VERSION_LINE).append('\n');
        t.append("Java translation of HepMC3 ").append(HepMC3.VERSION)
            .append(" (A. Buckley, P. Ilten, D. Konstantinov, L. Lönnblad, J. Monk, W. Pokorski,\n")
            .append("T. Przedzinski, A. Verbytskyi; https://gitlab.cern.ch/hepmc/HepMC3),\n")
            .append("distributed under the GNU LGPL v3 or later.\n\n");
        t.append("Please cite, if you use it for scientific work:\n  ").append(HEPMC3.text()).append('\n');
        final List<String> used = usedFormats();
        t.append("\nFormats used in this session: ")
            .append(used.isEmpty() ? "none yet (they appear here once read or written)" : String.join(", ", used))
            .append('\n');
        final List<Reference> bib = bibliography(false);
        if (bib.size() > 1) {
            t.append("and for them:\n");
            for (Reference r : bib.subList(1, bib.size())) t.append("  ").append(r.text()).append('\n');
        }
        t.append("\nBibTeX:\n");
        for (Reference r : bib) t.append('\n').append(r.bibtex()).append('\n');
        return t.toString();
    }
}

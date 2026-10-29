package com.sphere.core.clhep;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The references of Sphere's Java CLHEP. Nothing of this is printed into the output: the window "Citation
 * CLHEP" of the console's Citations menu shows them, with the generators and libraries this session used,
 * since MIXMAX (CLHEP's default engine), RANLUX, RANLUX++ and the Mersenne Twister have publications of
 * their own and GenericFunctions' special functions are GSL's; {@code :clhep bib} writes them as BibTeX.
 * The BibTeX is INSPIRE's.
 */
public final class ClhepCitations {

    /** A publication, as the window shows it and as BibTeX. */
    public record Reference(String key, String text, String bibtex) {
    }

    public static final Reference CLHEP = new Reference("Lonnblad:1994np",
        "L. Lönnblad, \"CLHEP: A project for designing a C++ class library for high-energy physics\",\n"
            + "  Comput. Phys. Commun. 84 (1994) 307.",
        String.join("\n",
            "@article{Lonnblad:1994np,",
            "    author = \"Lonnblad, Leif\",",
            "    title = \"{CLHEP: A project for designing a C++ class library for high-energy physics}\",",
            "    reportNumber = \"CERN-TH-7175-94\",",
            "    doi = \"10.1016/0010-4655(94)90217-8\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"84\",",
            "    pages = \"307--316\",",
            "    year = \"1994\"",
            "}"));

    public static final Reference MIXMAX = new Reference("Savvidy:2014ana",
        "K. G. Savvidy, \"The MIXMAX random number generator\",\n"
            + "  Comput. Phys. Commun. 196 (2015) 161 [arXiv:1403.5355].",
        String.join("\n",
            "@article{Savvidy:2014ana,",
            "    author = \"Savvidy, Konstantin G.\",",
            "    title = \"{The MIXMAX random number generator}\",",
            "    eprint = \"1403.5355\",",
            "    archivePrefix = \"arXiv\",",
            "    primaryClass = \"hep-lat\",",
            "    reportNumber = \"NITS-PHY-2014, NITS-PHY-2014003\",",
            "    doi = \"10.1016/j.cpc.2015.06.003\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"196\",",
            "    pages = \"161--165\",",
            "    year = \"2015\"",
            "}"));

    public static final Reference RANLUX_LUSCHER = new Reference("Luscher:1993dy",
        "M. Lüscher, \"A Portable high quality random number generator for lattice field theory simulations\",\n"
            + "  Comput. Phys. Commun. 79 (1994) 100 [arXiv:hep-lat/9309020].",
        String.join("\n",
            "@article{Luscher:1993dy,",
            "    author = \"Luscher, Martin\",",
            "    title = \"{A Portable high quality random number generator for lattice field theory simulations}\",",
            "    eprint = \"hep-lat/9309020\",",
            "    archivePrefix = \"arXiv\",",
            "    reportNumber = \"DESY-93-133\",",
            "    doi = \"10.1016/0010-4655(94)90232-1\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"79\",",
            "    pages = \"100--110\",",
            "    year = \"1994\"",
            "}"));

    public static final Reference RANLUX_JAMES = new Reference("James:1993np",
        "F. James, \"RANLUX: A FORTRAN implementation of the high quality pseudorandom number generator of\n"
            + "  Luscher\", Comput. Phys. Commun. 79 (1994) 111 [Erratum: Comput. Phys. Commun. 97 (1996) 357].",
        String.join("\n",
            "@article{James:1993np,",
            "    author = \"James, F.\",",
            "    title = \"{RANLUX: A FORTRAN implementation of the high quality pseudorandom number generator of Luscher}\",",
            "    reportNumber = \"CERN-CN-93-13\",",
            "    doi = \"10.1016/0010-4655(94)90233-X\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"79\",",
            "    pages = \"111--114\",",
            "    year = \"1994\",",
            "    note = \"[Erratum: Comput.Phys.Commun. 97, 357 (1996)]\"",
            "}"));

    public static final Reference RANLUXPP_SIBIDANOV = new Reference("Sibidanov:2017xza",
        "A. Sibidanov, \"A revision of the subtract-with-borrow random number generators\",\n"
            + "  Comput. Phys. Commun. 221 (2017) 299 [arXiv:1705.03123].",
        String.join("\n",
            "@article{Sibidanov:2017xza,",
            "    author = \"Sibidanov, Alexei\",",
            "    title = \"{A revision of the subtract-with-borrow random number generators}\",",
            "    eprint = \"1705.03123\",",
            "    archivePrefix = \"arXiv\",",
            "    primaryClass = \"physics.comp-ph\",",
            "    doi = \"10.1016/j.cpc.2017.09.005\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"221\",",
            "    pages = \"299--303\",",
            "    year = \"2017\"",
            "}"));

    public static final Reference RANLUXPP_PORTABLE = new Reference("Hahnfeld:2021vpx",
        "J. Hahnfeld and L. Moneta, \"A Portable Implementation of RANLUX++\",\n"
            + "  EPJ Web Conf. 251 (2021) 03008 [arXiv:2106.02504].",
        String.join("\n",
            "@article{Hahnfeld:2021vpx,",
            "    author = \"Hahnfeld, Jonas and Moneta, Lorenzo\",",
            "    title = \"{A Portable Implementation of RANLUX++}\",",
            "    eprint = \"2106.02504\",",
            "    archivePrefix = \"arXiv\",",
            "    primaryClass = \"physics.comp-ph\",",
            "    doi = \"10.1051/epjconf/202125103008\",",
            "    journal = \"EPJ Web Conf.\",",
            "    volume = \"251\",",
            "    pages = \"03008\",",
            "    year = \"2021\"",
            "}"));

    public static final Reference MERSENNE_TWISTER = new Reference("Matsumoto:1998mt",
        "M. Matsumoto and T. Nishimura, \"Mersenne twister: a 623-dimensionally equidistributed uniform\n"
            + "  pseudo-random number generator\", ACM Trans. Model. Comput. Simul. 8 (1998) 3.",
        String.join("\n",
            "@article{Matsumoto:1998mt,",
            "    author = \"Matsumoto, Makoto and Nishimura, Takuji\",",
            "    title = \"{Mersenne twister: a 623-dimensionally equidistributed uniform pseudo-random number generator}\",",
            "    doi = \"10.1145/272991.272995\",",
            "    journal = \"ACM Trans. Model. Comput. Simul.\",",
            "    volume = \"8\",",
            "    pages = \"3--30\",",
            "    year = \"1998\"",
            "}"));

    public static final Reference GSL = new Reference("Galassi:2009gsl",
        "M. Galassi et al., \"GNU Scientific Library Reference Manual\", 3rd edition,\n"
            + "  Network Theory Ltd. (2009), ISBN 0954612078.",
        String.join("\n",
            "@book{Galassi:2009gsl,",
            "    author = \"Galassi, M. and others\",",
            "    title = \"{GNU Scientific Library Reference Manual}\",",
            "    edition = \"3rd\",",
            "    publisher = \"Network Theory Ltd.\",",
            "    isbn = \"0954612078\",",
            "    year = \"2009\"",
            "}"));

    /** The parts this session used ("MixMax", "Ranlux", "GSL"...), in the order first used. */
    private static final Set<String> USED = new LinkedHashSet<>();

    private ClhepCitations() {
    }

    /** Notes a generator or library used, for the Citation window. */
    public static void used(String what) {
        synchronized (USED) {
            USED.add(what);
        }
    }

    public static List<String> usedParts() {
        synchronized (USED) {
            return new ArrayList<>(USED);
        }
    }

    /** What to cite: CLHEP always, and the papers of the generators and libraries used (all with all). */
    public static List<Reference> bibliography(boolean all) {
        final List<String> used = usedParts();
        final List<Reference> out = new ArrayList<>();
        out.add(CLHEP);
        if (all || used.contains("MixMax")) out.add(MIXMAX);
        if (all || used.contains("Ranlux") || used.contains("Ranlux64")) {
            out.add(RANLUX_LUSCHER);
            out.add(RANLUX_JAMES);
        }
        if (all || used.contains("Ranlux++")) {
            out.add(RANLUXPP_SIBIDANOV);
            out.add(RANLUXPP_PORTABLE);
        }
        if (all || used.contains("MTwist")) out.add(MERSENNE_TWISTER);
        if (all || used.contains("GSL")) out.add(GSL);
        return out;
    }

    public static String bibtex(boolean all) {
        final StringBuilder b = new StringBuilder();
        for (Reference r : bibliography(all)) b.append(r.bibtex()).append("\n\n");
        return b.toString();
    }

    /** The text of the Citation CLHEP window. */
    public static String text() {
        final StringBuilder t = new StringBuilder(Clhep.VERSION_LINE).append('\n');
        t.append("Java translation of CLHEP ").append(Clhep.VERSION)
            .append(", the Class Library for High Energy Physics (the CLHEP project,\n")
            .append("https://proj-clhep.web.cern.ch), distributed under the GNU LGPL v3 or later: units and constants,\n")
            .append("vectors, rotations and boosts, geometry, matrices, random engines and distributions, random\n")
            .append("vectors, the Evaluator, GenericFunctions and Fields, each giving the C++'s bits.\n\n");
        t.append("Please cite, if you use it for scientific work:\n  ").append(CLHEP.text()).append('\n');
        final List<String> used = usedParts();
        t.append("\nGenerators and libraries used in this session: ")
            .append(used.isEmpty() ? "none yet (they appear here once used)" : String.join(", ", used))
            .append('\n');
        final List<Reference> bib = bibliography(false);
        if (bib.size() > 1) {
            t.append("and for them:\n");
            for (Reference r : bib.subList(1, bib.size())) t.append("  ").append(r.text()).append('\n');
        }
        t.append("\nGeant4 is built on CLHEP: see also Citations > Citation Geant4.\n");
        t.append("BibTeX of all of them: ':clhep bib <file.bib>' (and --all for every paper of the library).");
        return t.toString();
    }
}

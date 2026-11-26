package com.sphere.core.golem95;

import java.util.List;

/**
 * The references of Golem95 and OneLOop. Nothing of this is printed into the output: the window
 * "Citation Golem95" of the console's Citations menu shows them, and {@code :golem bib} writes them as
 * BibTeX. OneLOop asks for its two papers itself (its README, and the greeting it prints once).
 */
public final class Golem95Citations {

    /** A publication, as the window shows it and as BibTeX. */
    public record Reference(String key, String text, String bibtex) {
    }

    public static final Reference GOLEM95 = new Reference("Binoth:2008uq",
        "T. Binoth, J.-Ph. Guillet, G. Heinrich, E. Pilon, T. Reiter, \"Golem95: a numerical program to calculate\n"
            + "  one-loop tensor integrals with up to six external legs\", Comput. Phys. Commun. 180 (2009) 2317\n"
            + "  [arXiv:0810.0992].",
        String.join("\n",
            "@article{Binoth:2008uq,",
            "    author = \"Binoth, T. and Guillet, J.-Ph. and Heinrich, G. and Pilon, E. and Reiter, T.\",",
            "    title = \"{Golem95: A Numerical program to calculate one-loop tensor integrals with up to six external legs}\",",
            "    eprint = \"0810.0992\",",
            "    archivePrefix = \"arXiv\",",
            "    primaryClass = \"hep-ph\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"180\",",
            "    pages = \"2317--2330\",",
            "    year = \"2009\"",
            "}"));

    public static final Reference GOLEM95C = new Reference("Cullen:2011kv",
        "G. Cullen, J.-Ph. Guillet, G. Heinrich, T. Kleinschmidt, E. Pilon, T. Reiter, M. Rodgers, \"Golem95C: a library\n"
            + "  for one-loop integrals with complex masses\", Comput. Phys. Commun. 182 (2011) 2276 [arXiv:1101.5595].",
        String.join("\n",
            "@article{Cullen:2011kv,",
            "    author = \"Cullen, G. and Guillet, J. Ph. and Heinrich, G. and Kleinschmidt, T. and Pilon, E. and Reiter, T. and Rodgers, M.\",",
            "    title = \"{Golem95C: A library for one-loop integrals with complex masses}\",",
            "    eprint = \"1101.5595\",",
            "    archivePrefix = \"arXiv\",",
            "    primaryClass = \"hep-ph\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"182\",",
            "    pages = \"2276--2284\",",
            "    year = \"2011\"",
            "}"));

    public static final Reference HIGHER_RANKS = new Reference("Guillet:2013msa",
        "J.-Ph. Guillet, G. Heinrich, J.F. von Soden-Fraunhofen, \"Tools for NLO automation: extension of the golem95C\n"
            + "  integral library\", Comput. Phys. Commun. 185 (2014) 1828 [arXiv:1312.3887].",
        String.join("\n",
            "@article{Guillet:2013msa,",
            "    author = \"Guillet, J. Ph. and Heinrich, G. and von Soden-Fraunhofen, J. F.\",",
            "    title = \"{Tools for NLO automation: extension of the golem95C integral library}\",",
            "    eprint = \"1312.3887\",",
            "    archivePrefix = \"arXiv\",",
            "    primaryClass = \"hep-ph\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"185\",",
            "    pages = \"1828--1834\",",
            "    year = \"2014\"",
            "}"));

    public static final Reference FORMALISM = new Reference("Binoth:2005ff",
        "T. Binoth, J.-Ph. Guillet, G. Heinrich, E. Pilon, C. Schubert, \"An algebraic/numerical formalism for one-loop\n"
            + "  multi-leg amplitudes\", JHEP 10 (2005) 015 [arXiv:hep-ph/0504267].",
        String.join("\n",
            "@article{Binoth:2005ff,",
            "    author = \"Binoth, T. and Guillet, J. Ph. and Heinrich, G. and Pilon, E. and Schubert, C.\",",
            "    title = \"{An algebraic/numerical formalism for one-loop multi-leg amplitudes}\",",
            "    eprint = \"hep-ph/0504267\",",
            "    archivePrefix = \"arXiv\",",
            "    journal = \"JHEP\",",
            "    volume = \"10\",",
            "    pages = \"015\",",
            "    year = \"2005\"",
            "}"));

    public static final Reference ONELOOP = new Reference("vanHameren:2010cp",
        "A. van Hameren, \"OneLOop: for the evaluation of one-loop scalar functions\", Comput. Phys. Commun. 182 (2011)\n"
            + "  2427 [arXiv:1007.4716].",
        String.join("\n",
            "@article{vanHameren:2010cp,",
            "    author = \"van Hameren, A.\",",
            "    title = \"{OneLOop: For the evaluation of one-loop scalar functions}\",",
            "    eprint = \"1007.4716\",",
            "    archivePrefix = \"arXiv\",",
            "    primaryClass = \"hep-ph\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"182\",",
            "    pages = \"2427--2438\",",
            "    year = \"2011\"",
            "}"));

    public static final Reference ONELOOP_HELAC = new Reference("vanHameren:2009dr",
        "A. van Hameren, C.G. Papadopoulos, R. Pittau, \"Automated one-loop calculations: a proof of concept\",\n"
            + "  JHEP 09 (2009) 106 [arXiv:0903.4665].",
        String.join("\n",
            "@article{vanHameren:2009dr,",
            "    author = \"van Hameren, A. and Papadopoulos, C. G. and Pittau, R.\",",
            "    title = \"{Automated one-loop calculations: A Proof of concept}\",",
            "    eprint = \"0903.4665\",",
            "    archivePrefix = \"arXiv\",",
            "    primaryClass = \"hep-ph\",",
            "    journal = \"JHEP\",",
            "    volume = \"09\",",
            "    pages = \"106\",",
            "    year = \"2009\"",
            "}"));

    private Golem95Citations() {
    }

    /** Golem95 and its complex masses always; the higher ranks, the formalism and OneLOop's two papers. */
    public static List<Reference> bibliography(boolean all) {
        return all ? List.of(GOLEM95, GOLEM95C, HIGHER_RANKS, FORMALISM, ONELOOP, ONELOOP_HELAC)
            : List.of(GOLEM95, GOLEM95C, HIGHER_RANKS, ONELOOP, ONELOOP_HELAC);
    }

    public static String bibtex(boolean all) {
        final StringBuilder b = new StringBuilder();
        for (Reference r : bibliography(all)) {
            b.append(r.bibtex()).append("\n\n");
        }
        return b.toString();
    }

    /** The text of the Citation Golem95 window. */
    public static String text() {
        final StringBuilder t = new StringBuilder(Golem95.versionLine()).append('\n');
        t.append("Java translation of Golem95 ").append(Golem95.VERSION).append(" (GPL v3) and of the OneLOop ")
            .append(Golem95.ONELOOP_VERSION).append(" it carries, by Sphere's Fortran\n")
            .append("translator, checked against the Fortran compiled by gfortran: one-loop N-point tensor integrals\n")
            .append("(N up to 6) as form factors, with complex masses and ranks above N; the scalar integrals by OneLOop.\n\n");
        t.append("Please cite, if you use it for scientific work:\n");
        for (Reference r : bibliography(false)) {
            t.append("  ").append(r.text()).append('\n');
        }
        t.append("and for the form factor formalism itself:\n  ").append(FORMALISM.text()).append('\n');
        t.append("\nSphere's reliability report (':golem check') and rescue are Sphere's own, not Golem95's.\n");
        t.append("BibTeX of all of them: ':golem bib <file.bib> --all'.");
        return t.toString();
    }
}

package com.sphere.core.geant4;

import java.util.List;

/**
 * The references Geant4 asks its users to cite (geant4.org, "Citing Geant4"): the toolkit paper and the two
 * that describe its developments. Shown by the window "Citation Geant4" of the console's Citations menu;
 * {@code :g4 bib} writes them as BibTeX, which is INSPIRE's.
 */
public final class Geant4Citations {

    /** A publication, as the window shows it and as BibTeX. */
    public record Reference(String key, String text, String bibtex) {
    }

    public static final Reference TOOLKIT = new Reference("GEANT4:2002zbu",
        "S. Agostinelli et al. [GEANT4], \"GEANT4 - A Simulation Toolkit\",\n"
            + "  Nucl. Instrum. Meth. A 506 (2003) 250.",
        String.join("\n",
            "@article{GEANT4:2002zbu,",
            "    author = \"Agostinelli, S. and others\",",
            "    collaboration = \"GEANT4\",",
            "    title = \"{GEANT4 - A Simulation Toolkit}\",",
            "    reportNumber = \"SLAC-PUB-9350, FERMILAB-PUB-03-339, CERN-IT-2002-003\",",
            "    doi = \"10.1016/S0168-9002(03)01368-8\",",
            "    journal = \"Nucl. Instrum. Meth. A\",",
            "    volume = \"506\",",
            "    pages = \"250--303\",",
            "    year = \"2003\"",
            "}"));

    public static final Reference DEVELOPMENTS = new Reference("Allison:2006ve",
        "J. Allison et al., \"Geant4 developments and applications\",\n"
            + "  IEEE Trans. Nucl. Sci. 53 (2006) 270.",
        String.join("\n",
            "@article{Allison:2006ve,",
            "    author = \"Allison, John and others\",",
            "    title = \"{Geant4 developments and applications}\",",
            "    reportNumber = \"SLAC-PUB-11870\",",
            "    doi = \"10.1109/TNS.2006.869826\",",
            "    journal = \"IEEE Trans. Nucl. Sci.\",",
            "    volume = \"53\",",
            "    pages = \"270\",",
            "    year = \"2006\"",
            "}"));

    public static final Reference RECENT = new Reference("Allison:2016lfl",
        "J. Allison et al., \"Recent developments in Geant4\",\n"
            + "  Nucl. Instrum. Meth. A 835 (2016) 186.",
        String.join("\n",
            "@article{Allison:2016lfl,",
            "    author = \"Allison, J. and others\",",
            "    title = \"{Recent developments in Geant4}\",",
            "    reportNumber = \"FERMILAB-PUB-16-447-CD\",",
            "    doi = \"10.1016/j.nima.2016.06.125\",",
            "    journal = \"Nucl. Instrum. Meth. A\",",
            "    volume = \"835\",",
            "    pages = \"186--225\",",
            "    year = \"2016\"",
            "}"));

    private Geant4Citations() {
    }

    public static List<Reference> bibliography() {
        return List.of(TOOLKIT, DEVELOPMENTS, RECENT);
    }

    public static String bibtex() {
        final StringBuilder b = new StringBuilder();
        for (Reference r : bibliography()) b.append(r.bibtex()).append("\n\n");
        return b.toString();
    }

    /** The text of the Citation Geant4 window. */
    public static String text() {
        final StringBuilder t = new StringBuilder("Geant4, the toolkit for the simulation of the passage of particles through matter\n");
        t.append("(the Geant4 Collaboration, https://geant4.web.cern.ch), distributed under the Geant4 Software License.\n")
            .append("Sphere writes, reads and draws Geant4 applications and geometries (:g4code, :g4editor, GDML) and runs\n")
            .append("them in a Geant4 installation of this machine (:g4 build, :g4 sim, the geant4-bridge engine);\n")
            .append("Geant4's units and constants, vectors and random engines are CLHEP's (:clhep).\n\n");
        t.append("Geant4 asks that work using it cite:\n");
        for (Reference r : bibliography()) t.append("  ").append(r.text()).append('\n');
        t.append("\nand, for CLHEP, Citations > Citation CLHEP.\n");
        t.append("BibTeX: ':g4 bib <file.bib>'.");
        return t.toString();
    }
}

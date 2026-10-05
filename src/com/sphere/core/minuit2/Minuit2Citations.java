package com.sphere.core.minuit2;

import com.sphere.Sphere;
import com.sphere.core.hepmc3.cxx.NativeMath;

/**
 * Version and references of Sphere's Java Minuit2, for the Citation window
 * of the console and {@code :minuit2 bib}.
 */
public final class Minuit2Citations {

    private Minuit2Citations() {
    }

    /** A publication, as the window shows it and as BibTeX. */
    public record Reference(String key, String text, String bibtex) {
    }

    public static final Reference MINUIT = new Reference("James:1975dr",
        "F. James and M. Roos, \"Minuit: A System for Function Minimization and Analysis of the Parameter Errors\n"
            + "  and Correlations\", Comput. Phys. Commun. 10 (1975) 343-367.",
        String.join("\n",
            "@article{James:1975dr,",
            "    author = \"James, F. and Roos, M.\",",
            "    title = \"{Minuit: A System for Function Minimization and Analysis of the Parameter Errors and Correlations}\",",
            "    doi = \"10.1016/0010-4655(75)90039-9\",",
            "    journal = \"Comput. Phys. Commun.\",",
            "    volume = \"10\",",
            "    pages = \"343--367\",",
            "    year = \"1975\"",
            "}"));

    public static final Reference MINUIT2 = new Reference("James:2004xla",
        "F. James and M. Winkler, \"MINUIT User's Guide\" (Minuit2, the C++ Minuit of M. Winkler, F. James, L. Moneta\n"
            + "  and A. Zsenei), CERN, Geneva (2004).",
        String.join("\n",
            "@manual{James:2004xla,",
            "    author = \"James, F. and Winkler, M.\",",
            "    title = \"{MINUIT User's Guide}\",",
            "    organization = \"CERN\",",
            "    address = \"Geneva\",",
            "    year = \"2004\"",
            "}"));

    public static final Reference ROOT = new Reference("Brun:1997pa",
        "R. Brun and F. Rademakers, \"ROOT: An object oriented data analysis framework\",\n"
            + "  Nucl. Instrum. Meth. A 389 (1997) 81-86.",
        String.join("\n",
            "@article{Brun:1997pa,",
            "    author = \"Brun, R. and Rademakers, F.\",",
            "    title = \"{ROOT: An object oriented data analysis framework}\",",
            "    doi = \"10.1016/S0168-9002(97)00048-X\",",
            "    journal = \"Nucl. Instrum. Meth. A\",",
            "    volume = \"389\",",
            "    pages = \"81--86\",",
            "    year = \"1997\"",
            "}"));

    /** The version line. */
    public static String versionLine() {
        return "Minuit2 Java " + Sphere.MINUIT2_JAVA_PORT_VERSION + " (Sphere " + Sphere.VERSION + "), a translation of ROOT "
            + Sphere.MINUIT2_SOURCES_VERSION + " math/minuit2";
    }

    public static String bibtex() {
        return MINUIT.bibtex() + "\n\n" + MINUIT2.bibtex() + "\n\n" + ROOT.bibtex() + "\n";
    }

    /** The text of the Citation Minuit2 window. */
    public static String text() {
        return versionLine() + "\n"
            + "Java translation of Minuit2 (M. Winkler, F. James, L. Moneta, A. Zsenei; ROOT, https://root.cern),\n"
            + "operation for operation: its results are bit for bit those of the C++ compiled with g++ on Windows (MinGW)\n"
            + "and on Linux, as ':minuit2 validate' checks against the C++ outputs kept in the jar.\n"
            + "Distributed, as ROOT, under the GNU LGPL v2.1 or later.\n"
            + "C library functions: " + NativeMath.describe() + "; long double: "
            + MnParameterTransformation.longDouble() + ".\n\n"
            + "Please cite, if you use it for scientific work:\n  " + MINUIT.text() + "\n  " + MINUIT2.text() + "\n  "
            + ROOT.text() + "\n\nBibTeX:\n\n" + bibtex();
    }
}

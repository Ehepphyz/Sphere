package com.sphere.core.rootbackend;

/**
 * The reference the ROOT team asks for, shown from Citations in the console's
 * context menu.
 */
public final class RootCitation {

    private RootCitation() {
    }

    /** The text of the Citation ROOT window; the release is root-config's, or null when unknown. */
    public static String text(String release) {
        final boolean known = release != null && !release.isBlank();
        final StringBuilder t = new StringBuilder();
        t.append("ROOT").append(known ? " " + release.strip() : "").append(", https://root.cern\n\n");
        t.append("Please cite:\n");
        t.append("  R. Brun and F. Rademakers, \"ROOT - An Object Oriented Data Analysis Framework\",\n");
        t.append("  Proceedings AIHENP'96 Workshop, Lausanne, Sep. 1996,\n");
        t.append("  Nucl. Inst. & Meth. in Phys. Res. A 389 (1997) 81-86.\n");
        t.append("and the release used:\n");
        t.append("  ROOT").append(known ? " " + release.strip() : " (release: see :root env show)")
            .append(", https://doi.org/10.5281/zenodo.848818\n");
        t.append("\nBibTeX:\n\n");
        t.append(String.join("\n",
            "@article{Brun:1997pa,",
            "    author = \"Brun, R. and Rademakers, F.\",",
            "    title = \"{ROOT: An object oriented data analysis framework}\",",
            "    doi = \"10.1016/S0168-9002(97)00048-X\",",
            "    journal = \"Nucl. Instrum. Meth. A\",",
            "    volume = \"389\",",
            "    pages = \"81--86\",",
            "    year = \"1997\"",
            "}",
            "",
            "@software{ROOT,",
            "    author = \"{The ROOT Team}\",",
            "    title = \"{ROOT}\",",
            known ? "    version = \"" + release.strip() + "\"," : "    version = \"\",",
            "    doi = \"10.5281/zenodo.848818\",",
            "    url = \"https://root.cern\"",
            "}"));
        return t.append('\n').toString();
    }
}

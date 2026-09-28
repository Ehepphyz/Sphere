package com.sphere.core.rootbackend;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Version and references of Sphere's LHAPDF reader. Nothing of this is printed
 * into the output but the version line: the references are shown from
 * Citations in the console's context menu, with the PDF sets this session
 * opened, since each set has its own publication to cite.
 */
public final class LhapdfCitation {

    public static final String VERSION = "1.0.0.0";
    public static final String VERSION_LINE = "LHAPDF Java Sphere v" + VERSION;

    private static final String LHAPDF6 = "A. Buckley, J. Ferrando, S. Lloyd, K. Nordstr\u00f6m, B. Page, M. R\u00fcfenacht,"
        + " M. Sch\u00f6nherr and G. Watt,\n  \"LHAPDF6: parton density access in the LHC precision era\","
        + " Eur. Phys. J. C 75 (2015) 132 [arXiv:1412.7420].";

    private static final String LHAPDF6_BIBTEX = String.join("\n",
        "@article{Buckley:2014ana,",
        "    author = \"Buckley, A. and Ferrando, J. and Lloyd, S. and Nordstr{\\\"o}m, K. and Page, B.",
        "              and R{\\\"u}fenacht, M. and Sch{\\\"o}nherr, M. and Watt, G.\",",
        "    title = \"{LHAPDF6: parton density access in the LHC precision era}\",",
        "    eprint = \"1412.7420\",",
        "    archivePrefix = \"arXiv\",",
        "    doi = \"10.1140/epjc/s10052-015-3318-8\",",
        "    journal = \"Eur. Phys. J. C\",",
        "    volume = \"75\",",
        "    pages = \"132\",",
        "    year = \"2015\"",
        "}");

    /** Set name to its description, authors and reference, as its .info file gives them. */
    private static final Map<String, String[]> SETS = new LinkedHashMap<>();
    private static boolean announced;

    private LhapdfCitation() {
    }

    /** Notes a set when it is opened (RootPdfSet.open). */
    static void used(String name, Map<String, String> info) {
        synchronized (SETS) {
            SETS.putIfAbsent(name, new String[]{
                unquote(info.get("SetDesc")), unquote(info.get("Authors")), unquote(info.get("Reference"))});
        }
    }

    /** True the first time only: the version line is printed once per session. */
    public static synchronized boolean firstUse() {
        if (announced) return false;
        announced = true;
        return true;
    }

    private static String unquote(String s) {
        if (s == null || s.isBlank()) return null;
        final String t = s.strip();
        return t.length() > 1 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1) : t;
    }

    /** The text of the Citation LHAPDF window. */
    public static String text() {
        final StringBuilder t = new StringBuilder(VERSION_LINE).append('\n');
        t.append("Sphere's Java reader of LHAPDF 6 grids (lhagrid1), following LHAPDF 6,\n")
            .append("https://lhapdf.hepforge.org.\n\n");
        t.append("Please cite:\n  ").append(LHAPDF6).append("\n\n");
        t.append("PDF sets used in this session (cite each set's own publication too):\n");
        final Map<String, String[]> sets;
        synchronized (SETS) {
            sets = new LinkedHashMap<>(SETS);
        }
        if (sets.isEmpty()) {
            t.append("  none yet: the sets opened with :lpdf or :root pdf appear here.\n");
        }
        for (Map.Entry<String, String[]> e : sets.entrySet()) {
            final String[] v = e.getValue();
            t.append("  ").append(e.getKey());
            if (v[0] != null) t.append(": ").append(v[0]);
            t.append('\n');
            if (v[1] != null) t.append("      Authors: ").append(v[1]).append('\n');
            t.append("      Reference: ").append(v[2] != null ? v[2]
                : "none in its .info file; cite the publication of this set").append('\n');
        }
        t.append("\nBibTeX:\n\n").append(LHAPDF6_BIBTEX).append('\n');
        return t.toString();
    }
}

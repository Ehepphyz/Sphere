package com.sphere.components.editor;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The file that goes with another, which a split shows beside it: the header of
 * a C++ source and the source of a header (next to it, or across an
 * include/ and src/ pair as ROOT and Geant4 lay them out), a test and the code
 * it tests, a LaTeX document and its bibliography.
 */
public final class CompanionFiles {

    private CompanionFiles() {
    }

    private static final String[] HEADERS = { "h", "hh", "hpp", "hxx", "h++", "inl", "cuh" };
    private static final String[] SOURCES = { "cpp", "cc", "cxx", "c++", "c", "C", "cu" };
    private static final String[] HEADER_DIRS = { "include", "inc", "interface" };
    private static final String[] SOURCE_DIRS = { "src", "source", "lib" };

    /** The companion that exists on disk, or null. */
    public static File find(File file) {
        if (file == null) {
            return null;
        }
        for (File c : candidates(file.getAbsoluteFile())) {
            if (c.isFile() && !c.getAbsoluteFile().equals(file.getAbsoluteFile())) {
                return c;
            }
        }
        return null;
    }

    /** Where a companion would be, most likely first. */
    static List<File> candidates(File file) {
        Set<File> out = new LinkedHashSet<>();
        File dir = file.getParentFile();
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot + 1) : "";
        String lower = ext.toLowerCase(Locale.ROOT);

        if (contains(HEADERS, lower)) {
            pair(out, dir, base, SOURCES, HEADER_DIRS, SOURCE_DIRS);
        } else if (contains(SOURCES, ext) || contains(SOURCES, lower)) {
            pair(out, dir, base, HEADERS, SOURCE_DIRS, HEADER_DIRS);
        } else if (lower.equals("java") || lower.equals("kt") || lower.equals("scala")) {
            tests(out, dir, base, ext, base.endsWith("Test") ? base.substring(0, base.length() - 4) : base + "Test",
                  "/test/", "/main/");
        } else if (lower.equals("py")) {
            if (base.startsWith("test_")) {
                String code = base.substring(5);
                out.add(new File(dir, code + ".py"));
                if (dir != null && dir.getParentFile() != null) {
                    out.add(new File(dir.getParentFile(), code + ".py"));
                    out.add(new File(new File(dir.getParentFile(), "src"), code + ".py"));
                }
            } else if (base.endsWith("_test")) {
                out.add(new File(dir, base.substring(0, base.length() - 5) + ".py"));
            } else {
                out.add(new File(dir, "test_" + base + ".py"));
                out.add(new File(dir, base + "_test.py"));
                out.add(new File(new File(dir, "tests"), "test_" + base + ".py"));
                if (dir != null && dir.getParentFile() != null) {
                    out.add(new File(new File(dir.getParentFile(), "tests"), "test_" + base + ".py"));
                }
            }
        } else if (lower.equals("tex")) {
            out.add(new File(dir, base + ".bib"));
            out.add(new File(dir, "references.bib"));
            out.add(new File(dir, "bibliography.bib"));
        } else if (lower.equals("bib")) {
            out.add(new File(dir, base + ".tex"));
            out.add(new File(dir, "main.tex"));
        } else if (lower.equals("f90") || lower.equals("f") || lower.equals("f95") || lower.equals("f03")) {
            out.add(new File(dir, base + ".inc"));
        } else if (lower.equals("jl")) {
            out.add(new File(new File(dir == null ? null : dir.getParentFile(), "test"), base + ".jl"));
            out.add(new File(new File(dir == null ? null : dir.getParentFile(), "src"), base + ".jl"));
        }
        return new ArrayList<>(out);
    }

    private static void pair(Set<File> out, File dir, String base, String[] exts, String[] fromDirs, String[] toDirs) {
        for (String e : exts) {
            out.add(new File(dir, base + "." + e));
        }
        // include/Foo.h <-> src/Foo.cxx, one level up and across.
        if (dir != null && dir.getParentFile() != null) {
            File up = dir.getParentFile();
            String here = dir.getName().toLowerCase(Locale.ROOT);
            boolean known = contains(fromDirs, here);
            for (String d : toDirs) {
                for (String e : exts) {
                    out.add(new File(new File(up, d), base + "." + e));
                }
            }
            if (!known) {
                // a flat project: also look beside the folder
                for (String e : exts) {
                    out.add(new File(up, base + "." + e));
                }
            }
        }
    }

    private static void tests(Set<File> out, File dir, String base, String ext, String other, String test, String main) {
        out.add(new File(dir, other + "." + ext));
        if (dir == null) {
            return;
        }
        String path = dir.getAbsolutePath().replace('\\', '/');
        String mirrored = base.endsWith("Test") ? path.replace(test, main) : path.replace(main, test);
        if (!mirrored.equals(path)) {
            out.add(new File(mirrored, other + "." + ext));
        }
    }

    private static boolean contains(String[] values, String v) {
        for (String s : values) {
            if (s.equals(v)) {
                return true;
            }
        }
        return false;
    }
}

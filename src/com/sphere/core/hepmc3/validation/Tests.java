package com.sphere.core.hepmc3.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every test program of HepMC3's test directory that Sphere ports (those
 * ctest runs, the ROOT ones included, protobuf-only tests aside), and a command-line entry
 * to run and compare them outside the console.
 */
public final class Tests {

    private Tests() {
    }

    public static List<TestProgram> all() {
        final List<TestProgram> l = new ArrayList<>();
        l.addAll(CoreTests.all());
        l.addAll(IOTests.all());
        l.addAll(RootTests.all());
        return l;
    }

    /** The runs whose id contains the text (all when empty). */
    public static List<TestProgram> matching(String text) {
        final String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        final List<TestProgram> out = new ArrayList<>();
        for (TestProgram p : all()) {
            if (t.isEmpty() || p.id().toLowerCase(Locale.ROOT).contains(t)) out.add(p);
        }
        return out;
    }

    /** One line of report for an outcome. */
    public static String line(Validator.Outcome r) {
        final String status = r.error() != null ? "ERROR    " : r.passed() ? "IDENTICAL" : "DIFFERS  ";
        final StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.ROOT, "%s %-26s main=%-3d%s stdout: %s, stderr: %s, %d file%s compared%s  (%.0f ms)",
            status, r.test().id(), r.exitJava(), r.exitCpp() == null ? "      " : " (C++ " + r.exitCpp() + ")",
            firstLine(r.stdout()), firstLine(r.stderr()), r.filesCompared(), r.filesCompared() == 1 ? "" : "s",
            r.differingFiles().isEmpty() ? "" : ", differing: " + String.join(" ", r.differingFiles()), r.millis()));
        if (!r.test().rules().note().isEmpty()) s.append("\n      note: ").append(r.test().rules().note());
        if (r.error() != null) s.append("\n      ").append(r.error());
        if (!r.passed() && r.firstDifference() != null) s.append("\n      first: ").append(r.firstDifference());
        return s.toString();
    }

    private static String firstLine(String verdict) {
        final int nl = verdict.indexOf('\n');
        return nl < 0 ? verdict : "differs";
    }

    /** java ... Tests [filter]: runs and compares, a line per run. */
    public static void main(String[] args) {
        final String filter = args.length > 0 ? args[0] : "";
        int ok = 0;
        int n = 0;
        for (TestProgram t : matching(filter)) {
            n++;
            final Validator.Outcome r = Validator.run(t);
            if (r.passed()) ok++;
            System.out.println(line(r));
        }
        System.out.println(ok + " of " + n + " agree with the C++");
    }
}

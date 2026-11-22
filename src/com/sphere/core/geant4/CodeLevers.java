package com.sphere.core.geant4;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The constants of a detector's C++ that a goal may turn: a variable set once to a plain value -- G4int nofLayers =
 * 10; G4double absoThickness = 10.*mm; const G4double cal_size = 10*cm; -- its literal rewritten in a copy of the
 * sources, the code read again by CppGeometry, and the detector it then builds measured for the beam. Applied, the
 * answer is one edit of that literal, which the editor's undo takes back.
 */
public final class CodeLevers {

    private CodeLevers() {
    }

    /**
     * A constant: its name, where its literal stands in its file (characters start to end, the text there), its value
     * in internal units, whether it counts things, and the CLHEP unit it is written with (null for none).
     */
    public record Constant(String name, Path file, int line, int start, int end, String text, double value, boolean count,
                           String unit) {

        /** A value written as the code writes this constant: 26, 12.5*mm. */
        public String write(double v) {
            if (count) {
                // the code's own style: 26 for an int, 26. for a double
                return Long.toString(Math.round(v)) + (text.strip().endsWith(".") ? "." : "");
            }
            if (unit != null) {
                return cpp(v / unitValue(unit)) + "*" + unit;
            }
            return cpp(v);
        }

        /** The edit writing a value in place of the literal. */
        public CodeAdvisor.Edit edit(String newText) {
            return new CodeAdvisor.Edit(file, start, end, text, newText);
        }
    }

    private static final Pattern LITERAL = Pattern.compile(
        "\\s*\\(?\\s*([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)\\s*\\)?\\s*(?:\\*\\s*([A-Za-z_]\\w*))?\\s*");
    /** Names that count things: nofLayers, nCells, num_planes, cal_nLayers, sectors... */
    private static final Pattern COUNT_WORD = Pattern.compile("(?i)^(nof|num|n_).*|.*(count|layers|copies|cells|planes|sectors|staves|modules|towers)$");
    private static final Pattern COUNT_CAMEL = Pattern.compile("(^|_)n[A-Z].*");

    /** The constants a reading saw set once to a plain value, in the order of the code. */
    public static List<Constant> find(CppGeometry.Result r, List<CppGeometry.Source> sources) {
        final List<Constant> out = new ArrayList<>();
        if (r == null || r.insights() == null) {
            return out;
        }
        final Map<Path, String> texts = new LinkedHashMap<>();
        for (CppGeometry.Source s : sources) {
            texts.put(s.path().toAbsolutePath().normalize(), s.text());
        }
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (CppGeometry.Computed c : r.insights().computed()) {
            if (c.runs() != 1 || c.values().size() != 1 || c.values().get(0).length != 1 || c.text() == null
                || c.file() == null) {
                continue;
            }
            final Matcher lit = LITERAL.matcher(c.text());
            if (!lit.matches()) {
                continue;
            }
            final String unit = lit.group(2);
            if (unit != null && !Units.isReserved(unit)) {
                continue; // a number times a variable: computed, not a plain value
            }
            final Path file = c.file().toAbsolutePath().normalize();
            final String text = texts.get(file);
            if (text == null || !seen.add(c.name())) {
                continue;
            }
            // the literal on its line, after "name ="
            final int[] span = lineSpan(text, c.line());
            if (span == null) {
                continue;
            }
            final Matcher at = Pattern.compile("\\b" + Pattern.quote(c.name()) + "\\s*=\\s*(" + Pattern.quote(c.text().strip())
                + ")").matcher(text);
            at.region(span[0], span[1]);
            if (!at.find()) {
                continue;
            }
            final double v = c.values().get(0)[0];
            final boolean whole = unit == null && v >= 1 && v == Math.rint(v)
                && !lit.group(1).toLowerCase(Locale.ROOT).contains("e");
            final boolean count = whole && (c.integer() || COUNT_WORD.matcher(c.name()).matches() || COUNT_CAMEL.matcher(c.name()).find());
            out.add(new Constant(c.name(), file, c.line(), at.start(1), at.end(1), at.group(1), v, count, unit));
        }
        return out;
    }

    /** The characters of a line (1-based) of a text, or null past its end. */
    private static int[] lineSpan(String text, int line) {
        int start = 0;
        for (int l = 1; l < line; l++) {
            final int nl = text.indexOf('\n', start);
            if (nl < 0) {
                return null;
            }
            start = nl + 1;
        }
        int end = text.indexOf('\n', start);
        if (end < 0) {
            end = text.length();
        }
        return new int[] {start, end};
    }

    /**
     * A knob turning a constant: each value written into a copy of the sources, the copy read, its detector measured
     * for the beam (the gun's settings given; a beam with no position set enters each reading's own world).
     */
    public static GoalSeek.Knob knob(Constant c, List<CppGeometry.Source> sources, Path base, Map<String, String> gun,
                                     GoalSeek.Metric metric) {
        return new GoalSeek.Knob() {
            private int trials;

            public String name() {
                return c.name();
            }

            public String text() {
                return c.text();
            }

            public double value() {
                return c.value();
            }

            public boolean count() {
                return c.count();
            }

            public String express(double v) {
                return c.write(v);
            }

            public double measure(String text) {
                trials++;
                final List<CppGeometry.Source> copy = new ArrayList<>(sources.size());
                for (CppGeometry.Source s : sources) {
                    if (s.path().toAbsolutePath().normalize().equals(c.file())) {
                        copy.add(new CppGeometry.Source(s.path(), s.text().substring(0, c.start()) + text
                            + s.text().substring(c.end())));
                    } else {
                        copy.add(s);
                    }
                }
                final CppGeometry.Result r;
                try {
                    r = CppGeometry.read(copy, base);
                } catch (RuntimeException e) {
                    return Double.NaN;
                }
                if (!r.ok()) {
                    return Double.NaN;
                }
                final Geometry g = r.geometry();
                g.settings().putAll(gun);
                return metric.of(BeamPhysics.analyze(g, BeamPhysics.Beam.of(g)), g);
            }

            public int trials() {
                return trials;
            }
        };
    }

    /** The constants that move a metric, the strongest first, each tried one more or 5% more. */
    public static List<GoalSeek.Lever> rank(List<Constant> constants, List<CppGeometry.Source> sources, Path base,
                                            Map<String, String> gun, GoalSeek.Metric metric) {
        final List<GoalSeek.Lever> out = new ArrayList<>();
        if (constants.isEmpty()) {
            return out;
        }
        final double m0 = knob(constants.get(0), sources, base, gun, metric).measure();
        for (Constant c : constants) {
            final GoalSeek.Lever l = GoalSeek.lever(knob(c, sources, base, gun, metric), m0);
            if (l != null) {
                out.add(l);
            }
        }
        out.sort((a, b) -> Double.compare(Math.abs(b.effect()), Math.abs(a.effect())));
        return out;
    }

    /** A C++ double literal: 12.5, 2e-05, 20. */
    static String cpp(double v) {
        final String s = Units.num(v);
        return s.contains(".") || s.contains("e") ? s : s + ".";
    }

    private static double unitValue(String unit) {
        try {
            return Expr.eval("1*" + unit, Expr.NO_VARIABLES);
        } catch (GeometryException e) {
            return 1;
        }
    }
}

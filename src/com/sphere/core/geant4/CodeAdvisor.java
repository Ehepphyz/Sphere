package com.sphere.core.geant4;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Geant4 expert reading the C++ of a detector would point at: the mistakes that compile, run and give
 * the wrong physics without a word. Each finding sits on its line, and most come with the text that fixes
 * them, checked against the code as it was read before it is written.
 *
 * <ul>
 * <li>an angle written without its unit: rotateX(90) turns by 90 radians, G4Tubs(..., 0, 90) is a whole tube
 *     (Geant4 takes any span past a full turn as the full turn);</li>
 * <li>a length written without one: 10 is 10 mm, Geant4's internal unit, whatever the author thought in;</li>
 * <li>a NIST name Geant4 does not have: FindOrBuildMaterial("G4_LEAD") returns nullptr and the run fails at
 *     /run/initialize; the nearest NIST name is offered;</li>
 * <li>the copies of a loop sharing one copy number: a sensitive detector, a scorer or the readout cannot tell
 *     the layers apart; the loop's index is offered;</li>
 * <li>a magnetic field or a sensitive detector made in Construct(): in a multi-threaded run Construct() runs
 *     on the master only, and the worker threads that track the particles have neither.</li>
 * </ul>
 */
public final class CodeAdvisor {

    private CodeAdvisor() {
    }

    /** One replacement in a file: the text expected between two offsets, and what replaces it. */
    public record Edit(Path file, int start, int end, String expected, String text) {
    }

    public record Fix(String label, List<Edit> edits) {
    }

    /** A finding on a line ({@code column} 1-based, {@code length} characters); {@code fix} may be null. */
    public record Finding(Geometry.Severity severity, Path file, int line, int column, int length, String title,
                          String detail, Fix fix) {
    }

    /**
     * The findings on the code a reading read, the texts being those it read (the offsets it gives are
     * theirs), errors first.
     */
    public static List<Finding> review(CppGeometry.Result r, Map<Path, String> texts) {
        final List<Finding> out = new ArrayList<>();
        if (r == null || r.links() == null) {
            return out;
        }
        final Map<Path, String> byPath = new LinkedHashMap<>();
        texts.forEach((p, t) -> byPath.put(p.toAbsolutePath().normalize(), t));
        bare(r, byPath, out);
        materials(r, byPath, out);
        copies(r, byPath, out);
        threads(r, byPath, out);
        out.sort((a, b) -> a.severity().ordinal() != b.severity().ordinal() ? a.severity().ordinal() - b.severity().ordinal()
            : a.file() == null || b.file() == null || a.file().equals(b.file()) ? a.line() - b.line() : a.file().compareTo(b.file()));
        return out;
    }

    private static String text(Map<Path, String> texts, Path p) {
        return p == null ? null : texts.get(p.toAbsolutePath().normalize());
    }

    private static Finding at(Geometry.Severity s, Path file, String text, int start, int end, String title, String detail,
                              Fix fix) {
        int line = 1, lineStart = 0;
        for (int i = 0; i < Math.min(start, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Finding(s, file, line, start - lineStart + 1, Math.max(1, end - start), title, detail, fix);
    }

    // ------------------------------------------------------------------
    // Numbers without units
    // ------------------------------------------------------------------

    private static void bare(CppGeometry.Result r, Map<Path, String> texts, List<Finding> out) {
        for (CppGeometry.Bare b : r.insights().bare()) {
            final String text = text(texts, b.file());
            if (text == null || b.end() > text.length() || !text.substring(b.start(), b.end()).equals(b.text())) {
                continue;
            }
            if (b.kind() == 'A') {
                final double turns = Math.abs(b.value()) / (2 * Math.PI);
                // a span past a full turn is clamped to the full turn: right by luck when a full one was meant
                final boolean span = b.where().contains(" delta");
                final String deg = String.format(Locale.ROOT, "%.0f", Math.toDegrees(b.value()));
                out.add(at(span ? Geometry.Severity.WARNING : Geometry.Severity.ERROR, b.file(), text, b.start(), b.end(),
                    b.text() + " is read in radians (" + String.format(Locale.ROOT, "%.3g", turns) + " turns)",
                    b.where() + " takes radians, CLHEP's unit of angle: " + b.text() + " without a unit is " + b.text()
                        + " rad, " + deg + " deg." + (span ? " Geant4 makes a span of more than a full turn the full"
                        + " turn, so the solid is whole whatever was meant (90 for a quarter included)."
                        : " Write " + b.text() + "*deg for degrees."), fix("Write " + b.text() + "*deg", b, "*deg")));
            } else {
                out.add(at(Geometry.Severity.INFO, b.file(), text, b.start(), b.end(),
                    b.text() + " is read as " + b.text() + " mm",
                    b.where() + " takes a length in Geant4's internal unit, the millimeter: " + b.text()
                        + " without a unit is " + b.text() + " mm. Saying it makes the code read as it means.",
                    fix("Write " + b.text() + "*mm", b, "*mm")));
            }
        }
    }

    private static Fix fix(String label, CppGeometry.Bare b, String unit) {
        return new Fix(label, List.of(new Edit(b.file(), b.start(), b.end(), b.text(), b.text() + unit)));
    }

    // ------------------------------------------------------------------
    // NIST names
    // ------------------------------------------------------------------

    private static final Pattern NIST_CALL = Pattern.compile(
        "\\b(?:FindOrBuildMaterial|FindSimpleMaterial|BuildMaterialWithNewDensity)\\s*\\(\\s*\"([^\"\\\\\\n]*)\"");

    private static void materials(CppGeometry.Result r, Map<Path, String> texts, List<Finding> out) {
        final Set<String> own = r.links().codeMaterials();
        for (Map.Entry<Path, String> e : texts.entrySet()) {
            final String text = e.getValue();
            if (!isCpp(e.getKey())) {
                continue;
            }
            final String code = blank(text, true);
            final Matcher m = NIST_CALL.matcher(text);
            while (m.find()) {
                if (code.charAt(m.start()) == ' ') {
                    continue;   // in a comment
                }
                final String name = m.group(1);
                if (name.isEmpty() || NistDB.get().material(name) != null || own.contains(name)) {
                    continue;
                }
                final String near = nearestNist(name);
                final String detail = name + " is not among Geant4's NIST materials, nor one this code defines:"
                    + " the call returns nullptr, the volume has no material and the run fails at /run/initialize."
                    + (near == null ? " ':g4 material' or G4editor's material list give the names." : "");
                out.add(at(Geometry.Severity.ERROR, e.getKey(), text, m.start(1), m.end(1), "No NIST material " + name
                    + (near == null ? "" : ": " + near + "?"), detail, near == null ? null : new Fix("Write " + near,
                    List.of(new Edit(e.getKey(), m.start(1), m.end(1), name, near)))));
            }
        }
    }

    /** The NIST name meant: a common name ("LEAD", "silicium"), else the nearest by its letters; null if none is near. */
    static String nearestNist(String name) {
        final String bare = name.toUpperCase(Locale.ROOT).startsWith("G4_") ? name.substring(3) : name;
        final String common = Intent.material(bare.replace('_', ' '));
        if (common != null) {
            return common;
        }
        for (Material m : NistDB.get().materials()) {
            if (m.name().equalsIgnoreCase(name) || m.name().equalsIgnoreCase("G4_" + bare)) {
                return m.name();   // the case: Geant4's names are case-sensitive
            }
        }
        String best = null;
        int bestD = Integer.MAX_VALUE;
        final String target = ("G4_" + bare).toUpperCase(Locale.ROOT);
        for (Material m : NistDB.get().materials()) {
            final int d = distance(target, m.name().toUpperCase(Locale.ROOT));
            if (d < bestD) {
                bestD = d;
                best = m.name();
            }
        }
        return best != null && bestD <= Math.max(2, target.length() / 5) ? best : null;
    }

    private static int distance(String a, String b) {
        final int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                final int c = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            System.arraycopy(cur, 0, prev, 0, cur.length);
        }
        return prev[b.length()];
    }

    // ------------------------------------------------------------------
    // Copy numbers
    // ------------------------------------------------------------------

    /** A for loop: its counter, where it starts, its test, and its body's span. */
    record Loop(String var, String start, String op, String bound, int bodyStart, int bodyEnd) {
    }

    private static void copies(CppGeometry.Result r, Map<Path, String> texts, List<Finding> out) {
        final Map<Integer, List<PhysicalVolume>> groups = new LinkedHashMap<>();
        for (Map.Entry<PhysicalVolume, CppGeometry.PlacementText> e : r.placementTexts().entrySet()) {
            if (e.getValue().shared()) {
                groups.computeIfAbsent(e.getValue().group(), k -> new ArrayList<>()).add(e.getKey());
            }
        }
        for (List<PhysicalVolume> g : groups.values()) {
            final PhysicalVolume first = g.get(0);
            // copies in different mothers are told apart by their mothers' copy numbers
            final boolean alike = g.size() > 1 && g.stream().allMatch(pv -> pv.mother() == first.mother()
                && pv.copyNumber() == first.copyNumber());
            final CppGeometry.CodeText call = r.links().statements().calls().get(first);
            final CppGeometry.PlacementText pt = r.placementTexts().get(first);
            final String text = call == null ? null : text(texts, call.file());
            if (!alike || text == null || pt.copyText() == null) {
                continue;
            }
            final List<Loop> around = enclosing(loops(blank(text, false)), call.start());
            final String index = index(around);
            final int cs = pt.copyStart();
            final boolean writable = index != null && cs >= 0 && pt.copyEnd() <= text.length()
                && text.substring(cs, pt.copyEnd()).equals(pt.copyText());
            final String title = g.size() + " copies of " + first.logical().name() + " share copy number "
                + first.copyNumber();
            final String detail = "This G4PVPlacement runs " + g.size() + " times in " + first.mother().name()
                + " and gives every copy the number " + pt.copyText() + ": a sensitive detector, a scorer or the readout"
                + " (GetCopyNumber) cannot tell them apart, nor can G4code move one of them alone."
                + (index != null ? " The loop's index numbers them." : around.isEmpty()
                ? " It is not in a loop here: give each run its own number." : " Its loops are not ones G4code can number.");
            out.add(at(Geometry.Severity.WARNING, call.file(), text, cs >= 0 ? cs : call.start(), cs >= 0 ? cs
                + pt.copyText().length() : call.end(), title, detail, writable ? new Fix("Number them " + index,
                List.of(new Edit(call.file(), cs, cs + pt.copyText().length(), pt.copyText(), index))) : null));
        }
    }

    /** The copy number a nest of loops gives each run: the counter, or outer * count + inner for two. */
    static String index(List<Loop> loops) {
        if (loops.size() == 1) {
            return loops.get(0).var();
        }
        if (loops.size() == 2) {
            final Loop outer = loops.get(0), inner = loops.get(1);
            if (!inner.start().equals("0") || !(inner.op().equals("<") || inner.op().equals("<="))) {
                return null;
            }
            String n = inner.op().equals("<") ? inner.bound() : inner.bound() + " + 1";
            if (!n.matches("[A-Za-z_][\\w.]*|\\d+")) {
                n = "(" + n + ")";
            }
            return outer.var() + " * " + n + " + " + inner.var();
        }
        return null;
    }

    /** The loops whose body holds an offset, the outermost first. */
    static List<Loop> enclosing(List<Loop> loops, int at) {
        final List<Loop> out = new ArrayList<>();
        for (Loop l : loops) {
            if (l.bodyStart() <= at && at < l.bodyEnd()) {
                out.add(l);
            }
        }
        out.sort((a, b) -> a.bodyStart() - b.bodyStart());
        return out;
    }

    private static final Pattern FOR = Pattern.compile("\\bfor\\s*\\(");
    private static final Pattern KEYWORD = Pattern.compile("(for|while|if)\\s*\\(");
    private static final Pattern INIT = Pattern.compile("\\s*(?:const\\s+)?(?:(?:unsigned\\s+|signed\\s+|long\\s+)*"
        + "[A-Za-z_][\\w:]*(?:<[^>]*>)?\\s+)?([A-Za-z_]\\w*)\\s*(?:=\\s*(.+?)|\\{\\s*(.*?)\\s*})\\s*");
    private static final Pattern TEST = Pattern.compile("\\s*([A-Za-z_]\\w*)\\s*(<=|<|!=)\\s*(.+?)\\s*");

    /** The for loops of a text whose comments and strings are blanked, each with its body's span. */
    static List<Loop> loops(String code) {
        final List<Loop> out = new ArrayList<>();
        final Matcher m = FOR.matcher(code);
        while (m.find()) {
            final int open = m.end() - 1, close = match(code, open);
            if (close < 0) {
                continue;
            }
            final List<String> parts = split(code.substring(open + 1, close));
            if (parts.size() != 3) {
                continue;   // a range for, or no for loop G4code counts
            }
            final Matcher i = INIT.matcher(parts.get(0)), t = TEST.matcher(parts.get(1));
            if (!i.matches() || !t.matches() || !t.group(1).equals(i.group(1))) {
                continue;
            }
            final int body = skip(code, close + 1), end = statementEnd(code, body);
            if (end > body) {
                final String start = (i.group(2) != null ? i.group(2) : i.group(3) == null ? "" : i.group(3)).strip();
                out.add(new Loop(i.group(1), start, t.group(2), t.group(3).strip(), body, end));
            }
        }
        return out;
    }

    /** The parts of a for header, split at its ';' outside parentheses. */
    private static List<String> split(String header) {
        final List<String> out = new ArrayList<>();
        int depth = 0, from = 0;
        for (int k = 0; k < header.length(); k++) {
            final char c = header.charAt(k);
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                depth--;
            } else if (c == ';' && depth == 0) {
                out.add(header.substring(from, k));
                from = k + 1;
            }
        }
        out.add(header.substring(from));
        return out;
    }

    /** The end (exclusive) of the statement starting at i: a block, a loop or an if with its body, or up to its ';'. */
    static int statementEnd(String code, int i) {
        if (i < 0 || i >= code.length()) {
            return -1;
        }
        if (code.charAt(i) == '{') {
            final int c = match(code, i);
            return c < 0 ? -1 : c + 1;
        }
        final Matcher k = KEYWORD.matcher(code).region(i, code.length());
        if (k.lookingAt() && (i == 0 || !Character.isJavaIdentifierPart(code.charAt(i - 1)))) {
            final int close = match(code, k.end() - 1);
            if (close < 0) {
                return -1;
            }
            int end = statementEnd(code, skip(code, close + 1));
            if (k.group(1).equals("if") && end > 0) {
                final int e = skip(code, end);
                if (code.startsWith("else", e) && (e + 4 >= code.length() || !Character.isJavaIdentifierPart(code.charAt(e + 4)))) {
                    end = statementEnd(code, skip(code, e + 4));
                }
            }
            return end;
        }
        int depth = 0;
        for (int j = i; j < code.length(); j++) {
            final char c = code.charAt(j);
            if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                if (depth-- == 0) {
                    return -1;
                }
            } else if (c == ';' && depth == 0) {
                return j + 1;
            }
        }
        return -1;
    }

    private static int skip(String code, int i) {
        while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The bracket closing the one at {@code open}, or -1. */
    private static int match(String code, int open) {
        final char o = code.charAt(open), c = o == '(' ? ')' : o == '{' ? '}' : ']';
        int depth = 0;
        for (int j = open; j < code.length(); j++) {
            if (code.charAt(j) == o) {
                depth++;
            } else if (code.charAt(j) == c && --depth == 0) {
                return j;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Threads
    // ------------------------------------------------------------------

    private static final Pattern THREAD_LOCAL = Pattern.compile("\\bnew\\s+(G4UniformMagField|G4UniformElectricField"
        + "|G4GlobalMagFieldMessenger|G4QuadrupoleMagField)\\b|\\b(AddNewDetector)\\s*\\(|->\\s*(SetSensitiveDetector)\\s*\\(");

    private static void threads(CppGeometry.Result r, Map<Path, String> texts, List<Finding> out) {
        final CppGeometry.InsertSite c = r.links().construct();
        final String text = c == null ? null : text(texts, c.file());
        if (text == null || c.bodyEnd() > text.length() || c.bodyStart() < 0) {
            return;
        }
        final String code = blank(text, false);
        final Matcher m = THREAD_LOCAL.matcher(code).region(c.bodyStart(), c.bodyEnd());
        while (m.find()) {
            final String what = m.group(1) != null ? m.group(1) : m.group(2) != null ? "G4SDManager::AddNewDetector"
                : "SetSensitiveDetector";
            final boolean field = m.group(1) != null;
            out.add(at(Geometry.Severity.WARNING, c.file(), text, m.start(), m.end(), what + " in "
                + (r.links().detectorClass() == null ? "" : r.links().detectorClass() + "::") + "Construct()",
                "Construct() runs once, on the master thread; in a multi-threaded run the worker threads that track the"
                    + " particles each need their own " + (field ? "field (and field manager)" : "sensitive detectors")
                    + ", made in ConstructSDandField(), which every worker calls. Here they have none: "
                    + (field ? "the particles fly straight." : "nothing is recorded.") + " Geant4's examples B2 and B4 show how.",
                null));
        }
    }

    // ------------------------------------------------------------------

    private static boolean isCpp(Path p) {
        final String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".cc") || n.endsWith(".cpp") || n.endsWith(".hh") || n.endsWith(".hpp") || n.endsWith(".h")
            || n.endsWith(".icc") || n.endsWith(".cxx");
    }

    /** The text with its comments turned to spaces (and its strings, unless kept), every offset and line kept. */
    static String blank(String text, boolean keepStrings) {
        final char[] c = text.toCharArray();
        int i = 0;
        while (i < c.length) {
            if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '/') {
                while (i < c.length && c[i] != '\n') {
                    c[i++] = ' ';
                }
            } else if (c[i] == '/' && i + 1 < c.length && c[i + 1] == '*') {
                while (i < c.length && !(c[i] == '*' && i + 1 < c.length && c[i + 1] == '/')) {
                    if (c[i] != '\n') {
                        c[i] = ' ';
                    }
                    i++;
                }
                for (int k = 0; k < 2 && i < c.length; k++) {
                    c[i++] = ' ';
                }
            } else if (c[i] == '"' || c[i] == '\'') {
                final char q = c[i];
                if (!keepStrings) {
                    c[i] = ' ';
                }
                i++;
                while (i < c.length && c[i] != q && c[i] != '\n') {
                    if (c[i] == '\\' && i + 1 < c.length) {
                        if (!keepStrings) {
                            c[i] = ' ';
                        }
                        i++;
                    }
                    if (!keepStrings) {
                        c[i] = ' ';
                    }
                    i++;
                }
                if (i < c.length && c[i] == q) {
                    if (!keepStrings) {
                        c[i] = ' ';
                    }
                    i++;
                }
            } else {
                i++;
            }
        }
        return new String(c);
    }

    // ------------------------------------------------------------------
    // The beam the application shoots
    // ------------------------------------------------------------------

    private static final Pattern GUN_PARTICLE = Pattern.compile("FindParticle\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern GUN_ENERGY = Pattern.compile("SetParticleEnergy\\s*\\(([^;]*)\\)\\s*;");
    private static final Pattern MAC_PARTICLE = Pattern.compile("(?m)^\\s*/gun/particle\\s+(\\S+)");
    private static final Pattern MAC_ENERGY = Pattern.compile("(?m)^\\s*/gun/energy\\s+([-+0-9.eE]+)\\s*([A-Za-z]+)?");

    /**
     * The particle and energy an application shoots, as G4editor's settings name them (gun.particle,
     * gun.energy): its primary generator's defaults, overridden by its first run macro's /gun commands.
     * The design assistant judges the detector against that beam.
     */
    public static Map<String, String> gun(Map<Path, String> texts, Path project) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<Path, String> e : texts.entrySet()) {
            final String code = blank(e.getValue(), true);
            if (!isCpp(e.getKey()) || !code.contains("GeneratePrimaries") && !code.contains("G4ParticleGun")) {
                continue;
            }
            final Matcher p = GUN_PARTICLE.matcher(code);
            if (p.find()) {
                out.putIfAbsent("gun.particle", p.group(1));
            }
            final Matcher en = GUN_ENERGY.matcher(code);
            while (en.find()) {
                try {
                    final String expr = en.group(1).strip();
                    Expr.eval(expr, Expr.NO_VARIABLES);
                    out.putIfAbsent("gun.energy", expr.replace(" ", ""));
                } catch (GeometryException | RuntimeException ignored) {
                    // a variable of the application: its value is the run's
                }
            }
        }
        if (project != null) {
            for (String name : new String[] {"run1.mac", "run.mac", "exampleB4a.in", "run2.mac"}) {
                final Path mac = project.resolve(name);
                if (!Files.isRegularFile(mac)) {
                    continue;
                }
                try {
                    final String m = Files.readString(mac);
                    String particle = null, energy = null;
                    for (Matcher x = MAC_PARTICLE.matcher(m); x.find(); ) {
                        particle = x.group(1);
                    }
                    for (Matcher x = MAC_ENERGY.matcher(m); x.find(); ) {
                        energy = x.group(1) + "*" + (x.group(2) == null ? "GeV" : x.group(2));
                    }
                    if (particle != null) {
                        out.put("gun.particle", particle);
                    }
                    if (energy != null) {
                        out.put("gun.energy", energy);
                    }
                } catch (Exception ignored) {
                    // unreadable: the generator's defaults stand
                }
                break;
            }
        }
        return out;
    }
}

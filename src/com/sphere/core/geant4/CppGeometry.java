package com.sphere.core.geant4;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A Geant4 detector read from its C++: DetectorConstruction::Construct() run
 * by a small interpreter of the C++ such code is written in, into the same
 * Geometry G4editor draws -- without a compiler and without Geant4.
 *
 * It reads what detector code is made of: variables with CLHEP units, the
 * arithmetic and std:: functions on them, for, while and if, the calls to
 * the class's own methods (DefineMaterials, ConstructCalorimeter...), the
 * solids, G4LogicalVolume, G4PVPlacement with a rotation matrix or a
 * G4Transform3D, G4PVReplica, NIST and hand-made materials, colours, and a
 * G4GDMLParser reading a GDML file. What it cannot know -- a value set by a
 * messenger at run time, a class it does not model -- is carried as unknown:
 * the volumes that do not depend on it are still drawn, and the lines that
 * do are reported, so the 3D view follows the code as it is typed.
 *
 * Every solid, volume and placement remembers the line that made it, and a
 * position written once as G4ThreeVector(...) remembers its text, so the
 * editor can go from the drawing to the code and write a moved volume back.
 */
public final class CppGeometry {

    public record Source(Path path, String text) {
    }

    public record Note(Path file, int line, Geometry.Severity severity, String message) {
        @Override
        public String toString() {
            return (file == null ? "" : file.getFileName() + ":" + line + ": ") + message;
        }
    }

    /** Where something was made: the file, the lines and the characters of the expression. */
    public record Origin(Path file, int line, int lastLine, int start, int end) {

        public boolean covers(Path f, int l) {
            return file != null && file.equals(f) && l >= line && l <= lastLine;
        }
    }

    /** The text of a G4ThreeVector(...) evaluated once, holding a placement's position: rewritable. */
    public record PositionText(Path file, int start, int end, String text) {
    }

    /**
     * The arguments of a G4PVPlacement as written: the rotation (or the whole G4Transform3D) and the position,
     * so a placement turned or moved in 3D is written back in place of them. A placement run several times (in
     * a loop) is shared by all the copies it made, told apart by their copy number: the text of the copy
     * number argument, and the group of the placements made by the same text.
     */
    public record PlacementText(Path file, boolean transform, int rotStart, int rotEnd, String rotText,
                                int posStart, int posEnd, String posText, boolean shared, String copyText, int group) {
    }

    /** A piece of the code as written, run once: what a change made in 3D or in the properties replaces. */
    public record CodeText(Path file, int start, int end, String text, String name) {
    }

    /**
     * Where code for a volume's new daughters goes: the variable holding the volume, in the function that
     * made it, written before that function's final return (or its closing brace) at its indentation.
     * {@code midLine}: the return shares its line with other code, the inserted lines start on a new one.
     * {@code topLevel} is false when the variable lives in a block or a loop, out of reach at the end.
     */
    public record InsertSite(String variable, Path file, int offset, String indent, boolean midLine, int declEnd,
                             int bodyStart, int bodyEnd, boolean topLevel) {
    }

    /**
     * One argument of a solid's constructor and the parameter it gives: the parameter is the argument
     * times {@code factor} (2 for G4's half-lengths), of {@code kind} 'L' length, 'A' angle or 'N' number.
     */
    public record ParamText(CodeText text, int factor, char kind) {
    }

    /**
     * Where a boolean's second solid is placed in its constructor: the rotation and position arguments, or
     * the G4Transform3D one; with neither (three arguments), {@code close} is the offset of its closing ')'.
     */
    public record BooleanText(Path file, CodeText rot, CodeText pos, CodeText transform, int close) {
    }

    /**
     * The arrays a solid's table (planes, corners) was read from: each written as {...} once, in the order
     * of the constructor's arguments, {@code columnOf} the table column each holds; and the count argument.
     */
    public record TableText(String table, List<CodeText> arrays, int[] columnOf, CodeText count) {
    }

    /**
     * The statements that make things: of each placement (run once or in a loop), of each volume, solid and
     * SetVisAttributes run once. {@link CodeText#name()} is the variable the statement gives its result to.
     */
    public record Statements(Map<Object, CodeText> made, Map<PhysicalVolume, CodeText> calls,
                             Map<LogicalVolume, List<CodeText>> vis) {
    }

    /** The code behind the drawing: what new volumes and changed characteristics are written into. */
    public record Links(Map<LogicalVolume, InsertSite> sites, InsertSite construct, Map<Solid, CodeText> solids,
                        Map<Solid, Map<String, ParamText>> params, Map<LogicalVolume, CodeText> materials,
                        Map<LogicalVolume, CodeText> vis, Set<String> codeMaterials, InsertSite sensitive,
                        Map<LogicalVolume, List<CodeText>> sensitiveTexts, String detectorClass, Statements statements,
                        Map<Solid, BooleanText> booleans, Map<Solid, TableText> tables) {

        public static Links none() {
            return new Links(Map.of(), null, Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), null, Map.of(), null,
                new Statements(Map.of(), Map.of(), Map.of()), Map.of(), Map.of());
        }
    }

    public record Result(Geometry geometry, List<Note> notes, Map<Object, Origin> origins,
                         Map<PhysicalVolume, PositionText> positions, Map<PhysicalVolume, PlacementText> placementTexts,
                         String entry, int steps, long micros, Path gdml, Links links) {

        public Origin origin(Object o) {
            return origins.get(o);
        }

        /** The placements made by the code on a line: directly, or through the volume or solid made there. */
        public List<PhysicalVolume> placementsAt(Path file, int line) {
            final List<PhysicalVolume> out = new ArrayList<>();
            for (Map.Entry<Object, Origin> e : origins.entrySet()) {
                if (!e.getValue().covers(file, line)) {
                    continue;
                }
                if (e.getKey() instanceof PhysicalVolume pv) {
                    out.add(pv);
                } else if (e.getKey() instanceof LogicalVolume lv) {
                    out.addAll(geometry.placementsOf(lv));
                } else if (e.getKey() instanceof Solid s) {
                    for (LogicalVolume lv : geometry.users(s)) {
                        out.addAll(geometry.placementsOf(lv));
                    }
                }
            }
            return out;
        }

        public boolean ok() {
            return geometry != null && geometry.world() != null;
        }
    }

    /** Statements run at most, so that a loop the interpreter cannot end does not hold the editor. */
    private static final int MAX_STEPS = 2_000_000;
    private static final int MAX_PLACEMENTS = 200_000;
    private static final int MAX_NOTES = 200;

    // ==================================================================
    // Entry
    // ==================================================================

    /**
     * Reads the detector of a project's sources (its .cc and .hh files).
     *
     * @param baseDir where relative file names (a GDML file) are looked for
     */
    public static Result read(List<Source> sources, Path baseDir) {
        return new CppGeometry(sources, baseDir).run();
    }

    /** Reads every .cc/.hh/.cpp/.h of a folder (src/ and include/ included), as files on disk. */
    public static List<Source> sourcesOf(Path folder) {
        final List<Source> out = new ArrayList<>();
        try (var s = Files.walk(folder, 3)) {
            for (Path p : (Iterable<Path>) s.sorted()::iterator) {
                final String n = p.getFileName().toString();
                if (Files.isRegularFile(p) && (n.endsWith(".cc") || n.endsWith(".hh") || n.endsWith(".cpp")
                    || n.endsWith(".hpp") || n.endsWith(".h") || n.endsWith(".icc")) && !inBuild(folder, p)) {
                    out.add(new Source(p, Files.readString(p)));
                }
            }
        } catch (Exception e) {
            // what was read is what there is
        }
        return out;
    }

    /** A file of a build folder (build/, build-release/...) of the project: CMake's copies, not the sources. */
    private static boolean inBuild(Path folder, Path p) {
        for (Path part : folder.relativize(p)) {
            if (part.toString().startsWith("build")) {
                return true;
            }
        }
        return false;
    }

    private final List<Source> sources;
    private final Path baseDir;
    private final List<List<Tok>> tokens = new ArrayList<>();
    private final Map<String, List<Function>> functions = new HashMap<>();
    private final Map<String, List<Node>> memberInits = new LinkedHashMap<>();
    /** constexpr and const declarations at namespace scope (Constants.hh), in reading order. */
    private final List<Node> globals = new ArrayList<>();
    /** Methods as the class body declares them: their default arguments live there. */
    private final Map<String, Function> declarations = new HashMap<>();
    private final Map<String, List<String>> classBases = new HashMap<>();
    private final List<Note> notes = new ArrayList<>();
    private final Map<Object, Origin> origins = new IdentityHashMap<>();
    private final Map<PhysicalVolume, Node> positionNodes = new IdentityHashMap<>();
    /** The G4PVPlacement expression of each placement, with its rotation and position arguments. */
    private record PlacementNodes(Node call, Node rot, Node pos, Node copy, boolean transform) {
    }

    private final Map<PhysicalVolume, PlacementNodes> placementNodes = new IdentityHashMap<>();
    /** Each solid's constructor expression and the name it was given there. */
    private final Map<Solid, Object[]> solidNodes = new IdentityHashMap<>();
    /** Each solid's parameters given by one argument of its constructor: {argument, factor, kind}. */
    private final Map<Solid, Map<String, Object[]>> paramNodes = new IdentityHashMap<>();
    /** The arguments of the solid being made, and the values read from them (set() records what it takes from them). */
    private List<Node> solidArgs;
    private List<Double> solidValues;
    private List<Object> solidValuesArgs;
    /** Each volume's G4LogicalVolume expression and its material argument. */
    private final Map<LogicalVolume, Node[]> materialNodes = new IdentityHashMap<>();
    /** Each volume's last SetVisAttributes call and its argument. */
    private final Map<LogicalVolume, Node[]> visNodes = new IdentityHashMap<>();
    /** The statement that made each placement, volume and solid: {the construction, the statement}. */
    private final Map<Object, Node[]> statementNodes = new IdentityHashMap<>();
    /** The SetVisAttributes statements of each volume: {the call, the statement}. */
    private final Map<LogicalVolume, List<Node[]>> visStatementNodes = new IdentityHashMap<>();
    /** Each boolean's constructor and its rotation, position and transform arguments: {call, rot, pos, transform}. */
    private final Map<Solid, Node[]> booleanNodes = new IdentityHashMap<>();
    /** Each table's arrays: {table name, the {...} of each array argument, columnOf, count argument, constructor}. */
    private final Map<Solid, Object[]> tableNodes = new IdentityHashMap<>();
    /** The sensitive detectors made (by the name given them), those G4SDManager holds, and where volumes got them. */
    private final Map<Object, String> detectorNames = new IdentityHashMap<>();
    private final Map<String, Object> detectors = new HashMap<>();
    private final Map<LogicalVolume, List<Node[]>> sensitiveNodes = new IdentityHashMap<>();
    /** ConstructSDandField(), read after Construct() for the volumes it makes sensitive. */
    private Function sdFunction;
    /** True while reading what is not the geometry (ConstructSDandField): what it does beyond is not noted. */
    private boolean quiet;
    /** Each volume's variable and where code for new daughters goes. */
    private final Map<LogicalVolume, InsertSite> sites = new IdentityHashMap<>();
    /** The functions being run, and the block depth of each one's body: a declaration there is in reach at its end. */
    private final java.util.ArrayDeque<Function> running = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Integer> bodyDepths = new java.util.ArrayDeque<>();
    private int blockDepth;
    private Node statement;
    private final List<Parameterised> parameterised = new ArrayList<>();
    private final List<Division> divisions = new ArrayList<>();
    private Env globalEnv;
    private final Geometry g = new Geometry();
    private final Map<String, Material> materials = new LinkedHashMap<>();
    private final List<LogicalVolume> roots = new ArrayList<>();
    private Geometry gdmlWorld;
    private Path gdmlFile;
    private int steps;
    private int placements;
    private String entry = "";

    private CppGeometry(List<Source> sources, Path baseDir) {
        this.sources = sources;
        this.baseDir = baseDir;
    }

    private Result run() {
        final long t0 = System.nanoTime();
        for (int i = 0; i < sources.size(); i++) {
            if (isGdml(sources.get(i).path())) {
                tokens.add(List.of());   // read when a G4GDMLParser asks for it
                continue;
            }
            final List<Tok> ts = new Lexer(sources.get(i).text(), i, includes, Set.of()).lex();
            tokens.add(ts);
            index(ts, i);
        }
        final Function construct = findConstruct();
        Object world = null;
        if (construct == null) {
            note(null, Geometry.Severity.ERROR, "no G4VPhysicalVolume* ...::Construct() in the sources read: the detector"
                + " class (G4VUserDetectorConstruction) defines it");
        } else {
            entry = construct.cls + "::Construct()";
            final Env global = new Env(null);
            globalEnv = global;
            final Env fields = new Env(global);
            fields.isFields = true;
            fields.cls = construct.cls;
            try {
                for (Node decl : globals) {
                    execQuiet(decl, global);
                }
                initMembers(construct.cls, fields, 0);
                final Function ctor = resolve(construct.cls, construct.cls, 0, null, 0);
                if (ctor != null && ctor.cls.equals(construct.cls)) {
                    call(ctor, List.of(), fields, null);
                }
                world = call(construct, List.of(), fields, null);
                for (Parameterised j : parameterised) {
                    expand(j);
                }
                for (Division d : divisions) {
                    expand(d);
                }
                // the sensitive detectors: ConstructSDandField() says which volumes are
                sdFunction = resolve(construct.cls, "ConstructSDandField", 0, null, 0);
                if (sdFunction != null && sdFunction.body != null) {
                    quiet = true;
                    try {
                        call(sdFunction, List.of(), fields, null);
                    } catch (RuntimeException | StackOverflowError e) {
                        // fields, messengers: the run's business, not the drawing's
                    } finally {
                        quiet = false;
                    }
                }
            } catch (Budget b) {
                note(null, Geometry.Severity.WARNING, "stopped after " + MAX_STEPS + " steps: a loop the code reading"
                    + " cannot end (its bound is unknown?)");
            } catch (StackOverflowError e) {
                note(null, Geometry.Severity.ERROR, "calls nested too deep while reading the code");
            } catch (RuntimeException e) {
                // a fault of the reader, not of the code: what was built so far is still drawn
                final StackTraceElement top = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
                note(null, Geometry.Severity.ERROR, "the code reading stopped on an internal fault (" + e
                    + (top == null ? "" : " at line " + top.getLineNumber() + " of " + top.getFileName()) + ")");
            }
        }
        final Geometry out = finish(world);
        // a G4ThreeVector shared by two placements moves both: it is not rewritten for one
        final Map<Node, Integer> uses = new IdentityHashMap<>();
        for (Node n : positionNodes.values()) {
            uses.merge(n, 1, Integer::sum);
        }
        final Map<PhysicalVolume, PositionText> positions = new IdentityHashMap<>();
        for (Map.Entry<PhysicalVolume, Node> e : positionNodes.entrySet()) {
            final Node n = e.getValue();
            if (n.evaluations == 1 && uses.get(n) == 1 && n.file >= 0) {
                final Source s = sources.get(n.file);
                positions.put(e.getKey(), new PositionText(s.path(), n.start, n.end, s.text().substring(n.start, n.end)));
            }
        }
        final Map<PhysicalVolume, PlacementText> texts = new IdentityHashMap<>();
        final Map<Node, Integer> groups = new IdentityHashMap<>();
        for (Map.Entry<PhysicalVolume, PlacementNodes> e : placementNodes.entrySet()) {
            final PlacementNodes pn = e.getValue();
            if (pn.rot().file < 0) {
                continue;
            }
            final String text = sources.get(pn.rot().file).text();
            // run in a loop or by a method called twice: one text, many placements, told by their copy numbers
            final boolean shared = pn.call().evaluations != 1;
            String copyText = null;
            if (pn.copy() != null) {
                final int cs = spanStart(pn.copy()), ce = pn.copy().end;
                copyText = cs >= 0 && ce <= text.length() && cs < ce ? text.substring(cs, ce) : null;
            }
            final int group = groups.computeIfAbsent(pn.call(), k -> groups.size());
            final int rs = spanStart(pn.rot()), re = pn.rot().end;
            int ps = -1, pe = -1;
            String pt = null;
            if (pn.pos() != null) {
                ps = spanStart(pn.pos());
                pe = pn.pos().end;
                pt = ps >= 0 && pe <= text.length() && ps < pe ? text.substring(ps, pe) : null;
            }
            if (rs >= 0 && re <= text.length() && rs < re && (pn.pos() == null || pt != null)) {
                texts.put(e.getKey(), new PlacementText(sources.get(pn.rot().file).path(), pn.transform(), rs, re,
                    text.substring(rs, re), ps, pe, pt, shared, copyText, group));
            }
        }
        return new Result(out, List.copyOf(notes), origins, positions, texts, entry, steps,
            (System.nanoTime() - t0) / 1000, gdmlFile, links(construct));
    }

    /** The texts and places gathered while reading, kept for the ones run once. */
    private Links links(Function construct) {
        final Map<Solid, CodeText> solids = new IdentityHashMap<>();
        for (Map.Entry<Solid, Object[]> e : solidNodes.entrySet()) {
            final CodeText t = text((Node) e.getValue()[0], (Node) e.getValue()[0], (String) e.getValue()[1]);
            if (t != null) {
                solids.put(e.getKey(), t);
            }
        }
        final Map<Solid, Map<String, ParamText>> params = new IdentityHashMap<>();
        for (Map.Entry<Solid, Map<String, Object[]>> e : paramNodes.entrySet()) {
            if (!solidNodes.containsKey(e.getKey())) {
                continue;   // not made after all (a parameter not known)
            }
            final Node run = (Node) solidNodes.get(e.getKey())[0];
            final Map<String, ParamText> one = new LinkedHashMap<>();
            for (Map.Entry<String, Object[]> k : e.getValue().entrySet()) {
                final CodeText t = text(run, (Node) k.getValue()[0], k.getKey());
                if (t != null) {
                    one.put(k.getKey(), new ParamText(t, (Integer) k.getValue()[1], (Character) k.getValue()[2]));
                }
            }
            if (!one.isEmpty()) {
                params.put(e.getKey(), one);
            }
        }
        final Map<LogicalVolume, CodeText> mats = new IdentityHashMap<>(), vis = new IdentityHashMap<>();
        for (Map.Entry<LogicalVolume, Node[]> e : materialNodes.entrySet()) {
            final CodeText t = text(e.getValue()[0], e.getValue()[1], null);
            if (t != null) {
                mats.put(e.getKey(), t);
            }
        }
        for (Map.Entry<LogicalVolume, Node[]> e : visNodes.entrySet()) {
            final CodeText t = text(e.getValue()[0], e.getValue()[1], null);
            if (t != null) {
                vis.put(e.getKey(), t);
            }
        }
        // the statements making volumes sensitive, each run once: removed when the volume no longer is
        final Map<LogicalVolume, List<CodeText>> sens = new IdentityHashMap<>();
        for (Map.Entry<LogicalVolume, List<Node[]>> e : sensitiveNodes.entrySet()) {
            for (Node[] n : e.getValue()) {
                final CodeText t = text(n[0], n[1], null);
                if (t != null) {
                    sens.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(t);
                }
            }
        }
        final InsertSite sd = construct != null && sdFunction != null && sdFunction.body != null
            && sdFunction.cls.equals(construct.cls) ? site(sdFunction, null, -1, true) : null;
        // the statements: a placement's always (a loop's copies are told apart by their copy number), others run once
        final Map<Object, CodeText> made = new IdentityHashMap<>();
        for (Map.Entry<Object, Node[]> e : statementNodes.entrySet()) {
            final Node run = e.getValue()[0], st = e.getValue()[1];
            final CodeText t = e.getKey() instanceof PhysicalVolume ? span(st, variableOf(st)) : text(run, st, variableOf(st));
            if (t != null) {
                made.put(e.getKey(), t);
            }
        }
        final Map<PhysicalVolume, CodeText> calls = new IdentityHashMap<>();
        for (Map.Entry<PhysicalVolume, PlacementNodes> e : placementNodes.entrySet()) {
            final CodeText t = span(e.getValue().call(), null);
            if (t != null) {
                calls.put(e.getKey(), t);
            }
        }
        final Map<LogicalVolume, List<CodeText>> visStatements = new IdentityHashMap<>();
        for (Map.Entry<LogicalVolume, List<Node[]>> e : visStatementNodes.entrySet()) {
            for (Node[] n : e.getValue()) {
                final CodeText t = text(n[0], n[1], null);
                if (t != null) {
                    visStatements.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(t);
                }
            }
        }
        final Map<Solid, BooleanText> booleans = new IdentityHashMap<>();
        for (Map.Entry<Solid, Node[]> e : booleanNodes.entrySet()) {
            final Node[] n = e.getValue();
            if (n[0].evaluations != 1 || n[0].file < 0) {
                continue;
            }
            final Path file = sources.get(n[0].file).path();
            if (n[1] != null && n[2] != null) {
                final CodeText r = text(n[0], n[1], null), q = text(n[0], n[2], null);
                if (r != null && q != null) {
                    booleans.put(e.getKey(), new BooleanText(file, r, q, null, -1));
                }
            } else if (n[3] != null) {
                final CodeText t = text(n[0], n[3], null);
                if (t != null) {
                    booleans.put(e.getKey(), new BooleanText(file, null, null, t, -1));
                }
            } else if (sources.get(n[0].file).text().charAt(n[0].end - 1) == ')') {
                booleans.put(e.getKey(), new BooleanText(file, null, null, null, n[0].end - 1));
            }
        }
        final Map<Solid, TableText> tables = new IdentityHashMap<>();
        for (Map.Entry<Solid, Object[]> e : tableNodes.entrySet()) {
            final Object[] v = e.getValue();
            @SuppressWarnings("unchecked")
            final List<Node> arrays = (List<Node>) v[1];
            final List<CodeText> texts = new ArrayList<>();
            for (Node b : arrays) {
                final CodeText t = b == null ? null : text(b, b, null);
                if (t == null) {
                    break;
                }
                texts.add(t);
            }
            if (texts.size() == arrays.size()) {
                final Node run = (Node) v[4];
                tables.put(e.getKey(), new TableText((String) v[0], texts, (int[]) v[2],
                    v[3] == null ? null : text(run, (Node) v[3], null)));
            }
        }
        return new Links(sites, construct == null || construct.body == null ? null : site(construct, null, -1, true),
            solids, params, mats, vis, Set.copyOf(materials.keySet()), sd, sens, construct == null ? null : construct.cls,
            new Statements(made, calls, visStatements), booleans, tables);
    }

    /** The text of a node, run once or not. */
    private CodeText span(Node piece, String name) {
        if (piece == null || piece.file < 0) {
            return null;
        }
        final String src = sources.get(piece.file).text();
        final int a = spanStart(piece), b = piece.end;
        return a >= 0 && b <= src.length() && a < b ? new CodeText(sources.get(piece.file).path(), a, b, src.substring(a, b),
            name) : null;
    }

    /** The variable a statement gives its value to: T* x = ...; or x = ...; null for an expression alone. */
    private static String variableOf(Node st) {
        if (st instanceof Decl d && d.vars.size() == 1) {
            return d.vars.get(0).name;
        }
        if (st instanceof ExprStmt e && e.e instanceof Assign a) {
            final Node t = a.target instanceof Group gr ? gr.inner : a.target;
            if (t instanceof Ident id) {
                return id.name;
            }
            if (t instanceof Member m && m.obj instanceof Ident th && th.name.equals("this")) {
                return m.name;
            }
        }
        return null;
    }

    /** The text of a node, when the statement around it ran once. */
    private CodeText text(Node run, Node piece, String name) {
        if (run.evaluations != 1 || piece.file < 0) {
            return null;
        }
        final String src = sources.get(piece.file).text();
        final int a = spanStart(piece), b = piece.end;
        return a >= 0 && b <= src.length() && a < b ? new CodeText(sources.get(piece.file).path(), a, b, src.substring(a, b),
            name) : null;
    }

    /** Where code goes at the end of a function: before its final return, else before its closing brace. */
    private InsertSite site(Function f, String variable, int declEnd, boolean topLevel) {
        final Source src = sources.get(f.body.file);
        final String text = src.text();
        final List<Node> body = f.body.body;
        final Node lastStmt = body.isEmpty() ? null : body.get(body.size() - 1);
        final int anchor = lastStmt instanceof Return r && r.file == f.body.file ? r.start
            : Math.max(f.body.start, f.body.end - 1);
        int lineStart = text.lastIndexOf('\n', Math.max(0, anchor - 1)) + 1;
        // the anchor alone on its line: the code goes on lines of its own before it
        final boolean own = text.substring(lineStart, anchor).isBlank();
        // and before the comment that tells of the return ("// always return the physical World")
        while (own && lineStart > 0) {
            final int prev = text.lastIndexOf('\n', lineStart - 2) + 1;
            if (prev < f.body.start || !text.substring(prev, lineStart).strip().startsWith("//")) {
                break;
            }
            lineStart = prev;
        }
        String indent = null;
        for (Node n : body) {
            if (n.file == f.body.file) {
                final int ls = text.lastIndexOf('\n', Math.max(0, n.start - 1)) + 1;
                if (text.substring(ls, n.start).isBlank()) {
                    indent = text.substring(ls, n.start);
                    break;
                }
            }
        }
        if (indent == null) {
            final int bs = text.lastIndexOf('\n', Math.max(0, f.body.start - 1)) + 1;
            final String brace = text.substring(bs, f.body.start);
            indent = (brace.isBlank() ? brace : "") + "  ";
        }
        return new InsertSite(variable, src.path(), own ? lineStart : anchor, indent, !own, declEnd, f.body.start,
            f.body.end, topLevel);
    }

    /** Where the text of an expression starts: at its leftmost operand. */
    private static int spanStart(Node n) {
        if (n instanceof Binary b) {
            return spanStart(b.a);
        }
        if (n instanceof Member m) {
            return Math.min(m.start, spanStart(m.obj));
        }
        if (n instanceof Index ix) {
            return spanStart(ix.obj);
        }
        if (n instanceof Call c) {
            return spanStart(c.callee);
        }
        if (n instanceof Cond c) {
            return spanStart(c.c);
        }
        if (n instanceof Assign a) {
            return spanStart(a.target);
        }
        if (n instanceof Unary u && u.postfix) {
            return spanStart(u.e);
        }
        return n.start;
    }

    /** #include "name": the source of that file name, the including file's folder first. */
    private final Includes includes = new Includes() {
        public int index(String name) {
            final String file = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
            for (int k = 0; k < sources.size(); k++) {
                final Path sp = sources.get(k).path();
                if (sp != null && sp.getFileName().toString().equals(file)) {
                    return k;
                }
            }
            return -1;
        }

        public String text(int index) {
            return sources.get(index).text();
        }
    };

    private static boolean isGdml(Path p) {
        return p != null && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gdml");
    }

    /** A GDML file among the sources (an editor's unsaved text), or null. */
    private Source sourceFor(Path p) {
        for (Source s : sources) {
            if (s.path() != null && isGdml(s.path()) && s.path().toAbsolutePath().normalize()
                .equals(p.toAbsolutePath().normalize())) {
                return s;
            }
        }
        return null;
    }

    private Geometry finish(Object world) {
        if (gdmlWorld != null && (world instanceof GdmlWorld || roots.isEmpty())) {
            return gdmlWorld;
        }
        for (Material m : materials.values()) {
            try {
                if (!m.isNist() && !g.materials().isDefined(m.name())) {
                    g.materials().define(m);
                }
            } catch (GeometryException e) {
                note(null, Geometry.Severity.WARNING, "material " + m.name() + ": " + e.getMessage());
            }
        }
        LogicalVolume w = null;
        if (deref(world) instanceof PhysicalVolume pv) {
            w = pv.logical();
        } else if (deref(world) instanceof WorldPv wp) {
            w = wp.lv;
        }
        if (w == null && !roots.isEmpty()) {
            w = roots.get(0);
        }
        if (w == null) {
            // a volume holding others that nothing holds
            for (LogicalVolume lv : g.volumes()) {
                if (!lv.daughters().isEmpty() && g.placementsOf(lv).isEmpty()) {
                    w = lv;
                    break;
                }
            }
        }
        if (w != null) {
            g.setWorld(w);
        } else if (!g.volumes().isEmpty()) {
            note(null, Geometry.Severity.ERROR, "no world volume: place one with a null mother and return it");
        } else if (!entry.isEmpty()) {
            final Object r = deref(world);
            note(null, Geometry.Severity.WARNING, entry + " built no volume the reading knows; it returns "
                + (r == null ? "nothing" : r instanceof Unknown u ? u.why : r instanceof Opaque o ? "a " + o.type
                : r.getClass().getSimpleName()) + ": a geometry read from a file named at run time, or made by a"
                + " library the sources do not hold, is drawn when the program runs");
        }
        if (w != null) {
            g.rebuild();
            for (Geometry.Issue i : g.issues()) {
                final Origin o = i.target() == null ? null : origins.get(i.target());
                notes.add(new Note(o == null ? null : o.file(), o == null ? 0 : o.line(), i.severity(), i.message()));
            }
        }
        return g;
    }

    // ==================================================================
    // Notes
    // ==================================================================

    private void note(Node at, Geometry.Severity s, String message) {
        if (quiet || notes.size() >= MAX_NOTES) {
            return;
        }
        if (at == null || at.file < 0) {
            notes.add(new Note(null, 0, s, message));
            return;
        }
        for (Note n : notes) {
            if (n.line() == at.line && n.message().equals(message)) {
                return;
            }
        }
        notes.add(new Note(sources.get(at.file).path(), at.line, s, message));
    }

    private void origin(Object o, Node at) {
        if (at != null && at.file >= 0 && !origins.containsKey(o)) {
            final String text = sources.get(at.file).text();
            int last = at.line;
            for (int i = at.start; i < Math.min(at.end, text.length()); i++) {
                if (text.charAt(i) == '\n') {
                    last++;
                }
            }
            origins.put(o, new Origin(sources.get(at.file).path(), at.line, last, at.start, at.end));
        }
    }

    // ==================================================================
    // Lexer
    // ==================================================================

    static final class Tok {
        final char kind;   // i identifier, n number, s string, c char, o operator, e end
        final String text;
        final Object value;
        final int start, end, line;
        /** The source the token was read from (-1 when made by the reader). */
        final int file;

        Tok(char kind, String text, Object value, int start, int end, int line) {
            this(-1, kind, text, value, start, end, line);
        }

        Tok(int file, char kind, String text, Object value, int start, int end, int line) {
            this.file = file;
            this.kind = kind;
            this.text = text;
            this.value = value;
            this.start = start;
            this.end = end;
            this.line = line;
        }

        boolean is(String s) {
            return (kind == 'o' || kind == 'i') && text.equals(s);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private static final String[] OPS = {"<<=", ">>=", "...", "->*", "::", "->", "++", "--", "<<", ">>", "<=", ">=",
        "==", "!=", "&&", "||", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", ".*"};

    /** The project's files an #include names, for the lexer. */
    interface Includes {
        int index(String name);

        String text(int index);
    }

    private static final java.util.regex.Pattern INCLUDE = java.util.regex.Pattern.compile("#\\s*include\\s*\"([^\"]+)\"");

    static final class Lexer {
        private final String s;
        private final int file;
        private final Includes includes;
        private final Set<Integer> active;
        private int i, line = 1, depth;

        Lexer(String s, int file) {
            this(s, file, null, Set.of());
        }

        Lexer(String s, int file, Includes includes, Set<Integer> active) {
            this.s = s;
            this.file = file;
            this.includes = includes;
            this.active = active;
        }

        List<Tok> lex() {
            final List<Tok> out = new ArrayList<>();
            final int n = s.length();
            boolean lineStart = true;
            while (i < n) {
                final char c = s.charAt(i);
                if (c == '\n') {
                    line++;
                    i++;
                    lineStart = true;
                    continue;
                }
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (lineStart && c == '#') {
                    // a preprocessor line, with its continuations; an #include "x.icc" inside a body is unfolded
                    final int lineEnd = s.indexOf('\n', i);
                    final java.util.regex.Matcher inc = INCLUDE.matcher(s.substring(i, lineEnd < 0 ? n : lineEnd));
                    if (depth > 0 && includes != null && inc.lookingAt()) {
                        final int idx = includes.index(inc.group(1));
                        if (idx >= 0 && !active.contains(idx)) {
                            final Set<Integer> chain = new java.util.HashSet<>(active);
                            chain.add(file);
                            chain.add(idx);
                            final List<Tok> sub = new Lexer(includes.text(idx), idx, includes, chain).lex();
                            out.addAll(sub.subList(0, sub.size() - 1));
                        }
                    }
                    while (i < n) {
                        final int e = s.indexOf('\n', i);
                        final int end = e < 0 ? n : e;
                        final boolean more = end > 0 && s.charAt(end - 1) == '\\';
                        i = end;
                        if (!more || i >= n) {
                            break;
                        }
                        i++;
                        line++;
                    }
                    continue;
                }
                lineStart = false;
                if (s.startsWith("//", i)) {
                    final int e = s.indexOf('\n', i);
                    i = e < 0 ? n : e;
                    continue;
                }
                if (s.startsWith("/*", i)) {
                    final int e = s.indexOf("*/", i + 2);
                    final int end = e < 0 ? n : e + 2;
                    for (int k = i; k < end; k++) {
                        if (s.charAt(k) == '\n') {
                            line++;
                        }
                    }
                    i = end;
                    continue;
                }
                final int start = i;
                if (c == 'R' && i + 1 < n && s.charAt(i + 1) == '"') {
                    final int open = s.indexOf('(', i + 2);
                    if (open > 0) {
                        final String delim = ")" + s.substring(i + 2, open) + "\"";
                        final int close = s.indexOf(delim, open);
                        final int end = close < 0 ? n : close + delim.length();
                        final String body = s.substring(open + 1, close < 0 ? n : close);
                        line += (int) body.chars().filter(ch -> ch == '\n').count();
                        i = end;
                        out.add(new Tok(file, 's', body, body, start, end, line));
                        continue;
                    }
                }
                if (Character.isJavaIdentifierStart(c)) {
                    while (i < n && Character.isJavaIdentifierPart(s.charAt(i))) {
                        i++;
                    }
                    final String id = s.substring(start, i);
                    if (i < n && (s.charAt(i) == '"' || s.charAt(i) == '\'') && (id.equals("L") || id.equals("u8")
                        || id.equals("u") || id.equals("U"))) {
                        continue;   // a prefixed literal: the literal follows
                    }
                    out.add(new Tok(file, 'i', id, null, start, i, line));
                    continue;
                }
                if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
                    out.add(number(start));
                    continue;
                }
                if (c == '"' || c == '\'') {
                    final StringBuilder b = new StringBuilder();
                    i++;
                    while (i < n && s.charAt(i) != c && s.charAt(i) != '\n') {
                        char ch = s.charAt(i);
                        if (ch == '\\' && i + 1 < n) {
                            final char e = s.charAt(++i);
                            ch = switch (e) {
                                case 'n' -> '\n';
                                case 't' -> '\t';
                                case '0' -> '\0';
                                default -> e;
                            };
                        }
                        b.append(ch);
                        i++;
                    }
                    i = Math.min(n, i + 1);
                    out.add(new Tok(file, c == '"' ? 's' : 'c', s.substring(start, i), b.toString(), start, i, line));
                    continue;
                }
                String op = null;
                for (String o : OPS) {
                    if (s.startsWith(o, i)) {
                        op = o;
                        break;
                    }
                }
                if (op == null) {
                    op = String.valueOf(c);
                }
                i += op.length();
                if (op.equals("{")) {
                    depth++;
                } else if (op.equals("}")) {
                    depth--;
                }
                out.add(new Tok(file, 'o', op, null, start, i, line));
            }
            out.add(new Tok(file, 'e', "<end>", null, n, n, line));
            return out;
        }

        private Tok number(int start) {
            final int n = s.length();
            boolean real = false;
            if (s.startsWith("0x", i) || s.startsWith("0X", i)) {
                i += 2;
                while (i < n && (Character.digit(s.charAt(i), 16) >= 0 || s.charAt(i) == '\'')) {
                    i++;
                }
                final String hex = s.substring(start + 2, i).replace("'", "");
                while (i < n && "uUlL".indexOf(s.charAt(i)) >= 0) {
                    i++;
                }
                return new Tok(file, 'n', s.substring(start, i), Long.parseLong(hex.isEmpty() ? "0" : hex, 16), start, i, line);
            }
            while (i < n) {
                final char c = s.charAt(i);
                if (Character.isDigit(c) || c == '\'') {
                    i++;
                } else if (c == '.') {
                    real = true;
                    i++;
                } else if ((c == 'e' || c == 'E') && i + 1 < n && (Character.isDigit(s.charAt(i + 1))
                    || ((s.charAt(i + 1) == '+' || s.charAt(i + 1) == '-') && i + 2 < n && Character.isDigit(s.charAt(i + 2))))) {
                    real = true;
                    i += 2;
                } else {
                    break;
                }
            }
            final String digits = s.substring(start, i).replace("'", "");
            while (i < n && "uUlLfF".indexOf(s.charAt(i)) >= 0) {
                if (s.charAt(i) == 'f' || s.charAt(i) == 'F') {
                    real = true;
                }
                i++;
            }
            Object v;
            try {
                v = real ? (Object) Double.parseDouble(digits) : (Object) Long.parseLong(digits);
            } catch (NumberFormatException e) {
                v = Double.parseDouble(digits);
            }
            return new Tok(file, 'n', s.substring(start, i), v, start, i, line);
        }
    }

    // ==================================================================
    // Index: the methods defined in the sources, the member initializers
    // ==================================================================

    static final class Function {
        final String cls, name;
        final List<String> params = new ArrayList<>();
        /** The written type of each parameter ("G4Tubs&", "const G4int"). */
        final List<String> paramTypes = new ArrayList<>();
        final List<Node> defaults = new ArrayList<>();
        final List<Node[]> inits = new ArrayList<>();   // constructor initializer list: {member, value}
        final String returns;
        Block body;
        final int file, open;

        Function(String cls, String name, String returns, int file, int open) {
            this.cls = cls;
            this.name = name;
            this.returns = returns;
            this.file = file;
            this.open = open;
        }
    }

    private void index(List<Tok> ts, int file) {
        // braces of namespaces and extern "C" are transparent; others hide what they hold
        final List<Character> stack = new ArrayList<>();
        int depth = 0;
        for (int i = 0; i < ts.size(); i++) {
            final Tok t = ts.get(i);
            if (t.is("{")) {
                final boolean ns = (i >= 2 && ts.get(i - 2).is("namespace")) || (i >= 1 && ts.get(i - 1).is("namespace"))
                    || (i >= 2 && ts.get(i - 2).is("extern") && ts.get(i - 1).kind == 's');
                stack.add(ns ? 'n' : 'b');
                if (!ns) {
                    depth++;
                }
                continue;
            }
            if (t.is("}")) {
                if (!stack.isEmpty() && stack.remove(stack.size() - 1) == 'b') {
                    depth--;
                }
                continue;
            }
            if (depth != 0) {
                continue;
            }
            // Type Class::member = value; (a static member's definition): a global, read before the constructor
            if (t.kind == 'i' && i + 3 < ts.size() && ts.get(i + 1).is("::") && ts.get(i + 2).kind == 'i'
                && (ts.get(i + 3).is("=") || ts.get(i + 3).is(";") || ts.get(i + 3).is("{") || ts.get(i + 3).is("["))) {
                final int end = staticMember(ts, file, i);
                if (end > i) {
                    i = end;
                    continue;
                }
            }
            // constexpr G4int kNofLayers = 10; at namespace scope
            if (t.is("constexpr") || t.is("const") || (t.is("static") && i + 1 < ts.size()
                && (ts.get(i + 1).is("const") || ts.get(i + 1).is("constexpr")))) {
                final Parser ps = new Parser(ts, file, i, ts.size());
                if (ps.isDeclaration()) {
                    try {
                        final Node d = ps.declaration(true);
                        globals.add(d);
                        i = ps.position() - 1;
                        continue;
                    } catch (ParseError e) {
                        // a function returning const: read below
                    }
                }
            }
            // class X [final] [: bases] { ... }
            if ((t.is("class") || t.is("struct")) && i + 1 < ts.size() && ts.get(i + 1).kind == 'i') {
                int k = i + 2;
                final String name = ts.get(i + 1).text;
                final List<String> bases = new ArrayList<>();
                while (k < ts.size() && !ts.get(k).is("{") && !ts.get(k).is(";") && !ts.get(k).is("(")) {
                    final Tok b = ts.get(k);
                    if (b.kind == 'i' && !b.is("public") && !b.is("protected") && !b.is("private") && !b.is("virtual")
                        && !b.is("final")) {
                        bases.add(b.text);
                    }
                    k++;
                }
                if (k < ts.size() && ts.get(k).is("{")) {
                    final int close = match(ts, k, "{", "}");
                    classBases.put(name, bases);
                    classBody(ts, file, name, k + 1, close);
                    i = close;
                }
                continue;
            }
            // Ret Class::Name(params) [const] [override] [: inits] { body }
            if (t.kind == 'i' && i + 3 < ts.size() && ts.get(i + 1).is("::") && (ts.get(i + 2).kind == 'i'
                || ts.get(i + 2).is("~")) && !ts.get(i + 2).is("~")) {
                final Tok nameTok = ts.get(i + 2);
                if (!ts.get(i + 3).is("(")) {
                    continue;
                }
                final int close = match(ts, i + 3, "(", ")");
                int k = close + 1;
                while (k < ts.size() && (ts.get(k).is("const") || ts.get(k).is("override") || ts.get(k).is("noexcept")
                    || ts.get(k).is("final"))) {
                    k++;
                }
                final int initStart = k < ts.size() && ts.get(k).is(":") ? k : -1;
                int brace = k;
                while (brace < ts.size() && !ts.get(brace).is("{") && !ts.get(brace).is(";")) {
                    if (ts.get(brace).is("(")) {
                        brace = match(ts, brace, "(", ")");
                    }
                    brace++;
                }
                if (brace >= ts.size() || !ts.get(brace).is("{")) {
                    continue;
                }
                // with an initializer list, the body's brace follows the last initializer
                if (initStart >= 0) {
                    brace = initStart + 1;
                    while (brace < ts.size()) {
                        final Tok x = ts.get(brace);
                        if (x.is("(")) {
                            brace = match(ts, brace, "(", ")") + 1;
                        } else if (x.is("{") && brace > 0 && ts.get(brace - 1).kind == 'i' && !ts.get(brace - 1).is("const")
                            && brace - 2 > initStart && (ts.get(brace - 2).is(",") || ts.get(brace - 2).is(":"))) {
                            brace = match(ts, brace, "{", "}") + 1;   // member{value}
                        } else if (x.is("{")) {
                            break;
                        } else {
                            brace++;
                        }
                    }
                }
                final StringBuilder ret = new StringBuilder();
                for (int r = i - 1; r >= 0 && r >= i - 4; r--) {
                    final Tok x = ts.get(r);
                    if (x.is(";") || x.is("}") || x.is("{")) {
                        break;
                    }
                    ret.insert(0, x.text);
                }
                final Function f = new Function(t.text, nameTok.text, ret.toString(), file, brace);
                params(ts, i + 3, close, file, f);
                if (initStart >= 0) {
                    initializers(ts, initStart + 1, brace, file, f);
                }
                final int end = match(ts, brace, "{", "}");
                try {
                    f.body = new Parser(ts, file, brace, end + 1).block();
                } catch (ParseError e) {
                    f.body = null;
                }
                functions.computeIfAbsent(f.cls, x -> new ArrayList<>()).add(f);
                functions.computeIfAbsent("::" + f.name, x -> new ArrayList<>()).add(f);
                i = end;
            }
        }
    }

    private static int match(List<Tok> ts, int open, String o, String c) {
        int depth = 0;
        for (int k = open; k < ts.size(); k++) {
            if (ts.get(k).is(o)) {
                depth++;
            } else if (ts.get(k).is(c)) {
                depth--;
                if (depth == 0) {
                    return k;
                }
            }
        }
        return ts.size() - 1;
    }

    private void params(List<Tok> ts, int open, int close, int file, Function f) {
        int start = open + 1;
        int depth = 0;
        for (int k = open + 1; k <= close; k++) {
            final Tok t = ts.get(k);
            if (t.is("(") || t.is("<") || t.is("{") || t.is("[")) {
                depth++;
            } else if ((t.is(")") || t.is(">") || t.is("}") || t.is("]")) && k < close) {
                depth--;
            }
            if ((t.is(",") && depth == 0) || k == close) {
                if (k > start) {
                    int eq = -1;
                    for (int q = start; q < k; q++) {
                        if (ts.get(q).is("=")) {
                            eq = q;
                            break;
                        }
                    }
                    final int nameEnd = eq < 0 ? k : eq;
                    // "const G4Tubs& chamber" is named; "G4Box&" or "const G4int" is not
                    final Tok last = ts.get(nameEnd - 1);
                    boolean typed = false;
                    for (int q = start; q < nameEnd - 1; q++) {
                        final Tok x = ts.get(q);
                        if ((x.kind == 'i' && !x.is("const") && !x.is("volatile")) || x.is("*") || x.is("&")
                            || x.is("&&") || x.is(">")) {
                            typed = true;
                        }
                    }
                    final boolean named = last.kind == 'i' && typed && !ts.get(nameEnd - 2).is("::");
                    final String name = named ? last.text : "_" + f.params.size();
                    final StringBuilder type = new StringBuilder();
                    for (int q = start; q < (named ? nameEnd - 1 : nameEnd); q++) {
                        type.append(type.length() > 0 && ts.get(q).kind == 'i' ? " " : "").append(ts.get(q).text);
                    }
                    if (!(nameEnd - start == 1 && ts.get(start).is("void"))) {
                        f.params.add(name);
                        f.paramTypes.add(type.toString());
                        Node def = null;
                        if (eq >= 0) {
                            try {
                                def = new Parser(ts, file, eq + 1, k).expression();
                            } catch (ParseError e) {
                                def = null;
                            }
                        }
                        f.defaults.add(def);
                    }
                }
                start = k + 1;
            }
        }
    }

    private void initializers(List<Tok> ts, int from, int to, int file, Function f) {
        int k = from;
        while (k < to) {
            final Tok name = ts.get(k);
            if (name.kind == 'i' && k + 1 < to && (ts.get(k + 1).is("(") || ts.get(k + 1).is("{"))) {
                final boolean paren = ts.get(k + 1).is("(");
                final int close = match(ts, k + 1, paren ? "(" : "{", paren ? ")" : "}");
                try {
                    final List<Node> init = new ArrayList<>();
                    init.add(new Ident(name.text, file, name));
                    final Parser ps = new Parser(ts, file, k + 2, close);
                    while (ps.position() < close) {
                        init.add(ps.at("{") ? ps.braces() : ps.expression());
                        if (!ps.accept(",")) {
                            break;
                        }
                    }
                    f.inits.add(init.toArray(new Node[0]));
                } catch (ParseError e) {
                    // left unknown
                }
                k = close + 1;
            } else {
                k++;
            }
        }
    }

    /**
     * A class body: its members, run as declarations before the constructor (initialised as written, the
     * others made as C++ default-makes them), the defaults its method declarations give, and the methods
     * it defines inline.
     */
    private void classBody(List<Tok> ts, int file, String cls, int from, int to) {
        int start = from;
        int k = from;
        while (k < to) {
            final Tok t = ts.get(k);
            if (t.is("(")) {
                final int close = match(ts, k, "(", ")");
                final int brace = k > start && ts.get(k - 1).kind == 'i' ? bodyBrace(ts, close, to) : -1;
                if (brace >= 0) {
                    final int end = match(ts, brace, "{", "}");
                    if (!(k >= 2 && ts.get(k - 2).is("~"))) {
                        inlineMethod(ts, file, cls, start, k, close, brace, end);
                    }
                    k = end + 1;
                    if (k < to && ts.get(k).is(";")) {
                        k++;
                    }
                    start = k;
                    continue;
                }
                k = close + 1;
                continue;
            }
            if (t.is("{")) {
                k = match(ts, k, "{", "}") + 1;   // a nested type, an enum, a brace-initialised member
                continue;
            }
            if (t.is(":") && k == start + 1 && (ts.get(start).is("public") || ts.get(start).is("private")
                || ts.get(start).is("protected"))) {
                start = k + 1;
            } else if (t.is(";")) {
                member(ts, file, cls, start, k);
                start = k + 1;
            }
            k++;
        }
    }

    /** The '{' of a method's body after its parameter list, past qualifiers and initializers; -1 if declared only. */
    private static int bodyBrace(List<Tok> ts, int close, int to) {
        int k = close + 1;
        while (k < to && (ts.get(k).is("const") || ts.get(k).is("override") || ts.get(k).is("noexcept")
            || ts.get(k).is("final") || ts.get(k).is("volatile") || ts.get(k).is("&"))) {
            k++;
        }
        if (k < to && ts.get(k).is("->")) {
            while (k < to && !ts.get(k).is("{") && !ts.get(k).is(";")) {
                k++;
            }
        }
        if (k < to && ts.get(k).is(":")) {
            k++;
            while (k < to) {
                final Tok x = ts.get(k);
                if (x.is("(")) {
                    k = match(ts, k, "(", ")") + 1;
                } else if (x.is("{") && (ts.get(k - 1).kind == 'i' || ts.get(k - 1).is(">"))) {
                    k = match(ts, k, "{", "}") + 1;
                } else if (x.is("{")) {
                    return k;
                } else if (x.is(";")) {
                    return -1;
                } else {
                    k++;
                }
            }
            return -1;
        }
        return k < to && ts.get(k).is("{") ? k : -1;
    }

    private void inlineMethod(List<Tok> ts, int file, String cls, int start, int open, int close, int brace, int end) {
        final String name = ts.get(open - 1).text;
        final StringBuilder ret = new StringBuilder();
        for (int q = start; q < open - 1; q++) {
            if (!SPECIFIERS.contains(ts.get(q).text) && !ts.get(q).is("virtual") && !ts.get(q).is("explicit")) {
                ret.append(ts.get(q).text);
            }
        }
        final Function f = new Function(cls, name, ret.toString(), file, brace);
        params(ts, open, close, file, f);
        for (int q = close + 1; q < brace; q++) {
            if (ts.get(q).is(":")) {
                initializers(ts, q + 1, brace, file, f);
                break;
            }
        }
        try {
            f.body = new Parser(ts, file, brace, end + 1).block();
        } catch (ParseError e) {
            f.body = null;
        }
        functions.computeIfAbsent(cls, x -> new ArrayList<>()).add(f);
        functions.computeIfAbsent("::" + name, x -> new ArrayList<>()).add(f);
    }

    /** One member statement of a class body: a method declaration (its defaults) or a data member. */
    private void member(List<Tok> ts, int file, String cls, int start, int end) {
        if (end <= start) {
            return;
        }
        final Tok first = ts.get(start);
        if (first.is("using") || first.is("typedef") || first.is("friend") || first.is("template") || first.is("class")
            || first.is("struct") || first.is("enum") || first.is("union")) {
            return;
        }
        if (first.is("static")) {
            boolean initialised = false;
            for (int q = start; q < end; q++) {
                initialised |= ts.get(q).is("=") || ts.get(q).is("{");
            }
            if (!initialised) {
                return;
            }
        }
        for (int q = start; q < end; q++) {
            if (ts.get(q).is("=") || ts.get(q).is("{")) {
                break;
            }
            if (ts.get(q).is("(")) {
                declaration(ts, file, cls, start, end);
                return;
            }
        }
        try {
            final Parser ps = new Parser(ts, file, start, end + 1);
            if (ps.isDeclaration()) {
                final Node n = ps.statement();
                if (n instanceof Decl) {
                    memberInits.computeIfAbsent(cls, x -> new ArrayList<>()).add(n);
                }
            }
        } catch (ParseError e) {
            // not a member this reader understands
        }
    }

    /** The definition of a static member: a global named after the member; the index of its ';', or -1. */
    private int staticMember(List<Tok> ts, int file, int at) {
        int start = at;
        while (start > 0 && !ts.get(start - 1).is(";") && !ts.get(start - 1).is("}") && !ts.get(start - 1).is("{")) {
            start--;
        }
        if (ts.get(start).is("using") || ts.get(start).is("typedef") || ts.get(start).is("return")) {
            return -1;
        }
        for (int q = start; q < at; q++) {
            if (ts.get(q).is("=")) {
                return -1;   // using X = A::B; and the like
            }
        }
        final StringBuilder type = new StringBuilder();
        boolean pointer = false;
        for (int q = start; q < at; q++) {
            final Tok x = ts.get(q);
            if (x.is("*")) {
                pointer = true;
            } else if (!SPECIFIERS.contains(x.text) && !x.is("&")) {
                type.append(type.length() > 0 && x.kind == 'i' && !type.toString().endsWith("::") && !x.is("::") ? " " : "")
                    .append(x.text);
            }
        }
        if (type.length() == 0) {
            return -1;
        }
        int end = at + 3;
        while (end < ts.size() && !ts.get(end).is(";")) {
            if (ts.get(end).is("{") || ts.get(end).is("(")) {
                end = match(ts, end, ts.get(end).text, ts.get(end).is("{") ? "}" : ")");
            }
            end++;
        }
        final Tok name = ts.get(at + 2);
        Node init = null;
        try {
            if (ts.get(at + 3).is("=")) {
                final Parser ps = new Parser(ts, file, at + 4, end);
                init = ps.at("{") ? ps.braces() : ps.expression();
            } else if (ts.get(at + 3).is("{")) {
                init = new Parser(ts, file, at + 3, end).braces();
            }
        } catch (ParseError e) {
            init = null;
        }
        final boolean array = ts.get(at + 3).is("[");
        globals.add(new Decl(type.toString().replace(" ::", "::"), List.of(new Declarator(name.text, pointer, false, array,
            init, null)), file, name));
        return end;
    }

    /** "explicit DetectorConstruction(const G4String& gdml = "");": the defaults a definition does not repeat. */
    private void declaration(List<Tok> ts, int file, String cls, int from, int to) {
        int k = from;
        while (k < to && (ts.get(k).is("explicit") || ts.get(k).is("virtual") || ts.get(k).is("inline"))) {
            k++;
        }
        int open = -1;
        for (int q = k; q < to; q++) {
            if (ts.get(q).is("(")) {
                open = q;
                break;
            }
        }
        if (open < 1 || ts.get(open - 1).kind != 'i' || (open >= 2 && ts.get(open - 2).is("~"))) {
            return;
        }
        final String name = ts.get(open - 1).text;
        final int close = match(ts, open, "(", ")");
        final Function f = new Function(cls, name, "", file, open);
        params(ts, open, close, file, f);
        declarations.putIfAbsent(cls + "::" + name + "/" + f.params.size(), f);
    }

    private Function findConstruct() {
        Function best = null;
        int bestScore = -1;
        for (Map.Entry<String, List<Function>> e : functions.entrySet()) {
            if (e.getKey().startsWith("::")) {
                continue;
            }
            for (Function f : e.getValue()) {
                if (!f.name.equals("Construct") || f.body == null) {
                    continue;
                }
                int score = 0;
                if (f.returns.contains("G4VPhysicalVolume")) {
                    score += 4;
                }
                if (classBases.getOrDefault(f.cls, List.of()).contains("G4VUserDetectorConstruction")) {
                    score += 4;
                }
                if (sources.get(f.file).path().getFileName().toString().contains("Detector")) {
                    score += 2;
                }
                if (f.returns.contains("G4VPhysicalVolume") || score >= 4) {
                    if (score > bestScore) {
                        best = f;
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    // ==================================================================
    // Syntax tree
    // ==================================================================

    abstract static class Node {
        final int file, line, start;
        int end;
        /** How many times the node was evaluated: a position evaluated once can be written back. */
        int evaluations;

        Node(int file, Tok at) {
            this.file = at.file >= 0 ? at.file : file;
            this.line = at.line;
            this.start = at.start;
            this.end = at.end;
        }
    }

    // Expressions
    static final class Literal extends Node {
        final Object value;

        Literal(Object v, int file, Tok at) {
            super(file, at);
            value = v;
        }
    }

    static final class Ident extends Node {
        final String name;

        Ident(String name, int file, Tok at) {
            super(file, at);
            this.name = name;
        }
    }

    static final class Unary extends Node {
        final String op;
        final Node e;
        final boolean postfix;

        Unary(String op, Node e, boolean postfix, int file, Tok at) {
            super(file, at);
            this.op = op;
            this.e = e;
            this.postfix = postfix;
        }
    }

    static final class Binary extends Node {
        final String op;
        final Node a, b;

        Binary(String op, Node a, Node b, int file, Tok at) {
            super(file, at);
            this.op = op;
            this.a = a;
            this.b = b;
        }
    }

    static final class Cond extends Node {
        final Node c, a, b;

        Cond(Node c, Node a, Node b, int file, Tok at) {
            super(file, at);
            this.c = c;
            this.a = a;
            this.b = b;
        }
    }

    static final class Assign extends Node {
        final String op;
        final Node target, value;

        Assign(String op, Node target, Node value, int file, Tok at) {
            super(file, at);
            this.op = op;
            this.target = target;
            this.value = value;
        }
    }

    static final class Call extends Node {
        final Node callee;
        final List<Node> args;

        Call(Node callee, List<Node> args, int file, Tok at) {
            super(file, at);
            this.callee = callee;
            this.args = args;
        }
    }

    static final class Member extends Node {
        final Node obj;
        final String name;
        final boolean arrow;

        Member(Node obj, String name, boolean arrow, int file, Tok at) {
            super(file, at);
            this.obj = obj;
            this.name = name;
            this.arrow = arrow;
        }
    }

    static final class Index extends Node {
        final Node obj, index;

        Index(Node obj, Node index, int file, Tok at) {
            super(file, at);
            this.obj = obj;
            this.index = index;
        }
    }

    /** new T(args), T(args) and T{args}: a construction of a named type. */
    static final class Construct extends Node {
        final String type;
        final List<Node> args;
        final boolean heap;

        Construct(String type, List<Node> args, boolean heap, int file, Tok at) {
            super(file, at);
            this.type = type;
            this.args = args;
            this.heap = heap;
        }
    }

    static final class Cast extends Node {
        final String type;
        final Node e;

        Cast(String type, Node e, int file, Tok at) {
            super(file, at);
            this.type = type;
            this.e = e;
        }
    }

    /** (expr): kept, so that the text of an expression holds its parentheses (a rewrite replaces all of it). */
    static final class Group extends Node {
        final Node inner;

        Group(Node inner, int file, Tok open) {
            super(file, open);
            this.inner = inner;
        }
    }

    static final class Braces extends Node {
        final List<Node> items;

        Braces(List<Node> items, int file, Tok at) {
            super(file, at);
            this.items = items;
        }
    }

    static final class LambdaExpr extends Node {
        final List<String> params;
        final Block body;

        LambdaExpr(List<String> params, Block body, int file, Tok at) {
            super(file, at);
            this.params = params;
            this.body = body;
        }
    }

    // Statements
    static final class Block extends Node {
        final List<Node> body;

        Block(List<Node> body, int file, Tok at) {
            super(file, at);
            this.body = body;
        }
    }

    static final class Declarator {
        final String name;
        final boolean pointer, reference, array;
        final Node init;
        final List<Node> ctorArgs;   // T x(args) / T x{args}

        Declarator(String name, boolean pointer, boolean reference, boolean array, Node init, List<Node> ctorArgs) {
            this.name = name;
            this.pointer = pointer;
            this.reference = reference;
            this.array = array;
            this.init = init;
            this.ctorArgs = ctorArgs;
        }
    }

    static final class Decl extends Node {
        final String type;
        final List<Declarator> vars;

        Decl(String type, List<Declarator> vars, int file, Tok at) {
            super(file, at);
            this.type = type;
            this.vars = vars;
        }
    }

    static final class ExprStmt extends Node {
        final Node e;

        ExprStmt(Node e, int file, Tok at) {
            super(file, at);
            this.e = e;
        }
    }

    static final class If extends Node {
        final Node c, a, b;

        If(Node c, Node a, Node b, int file, Tok at) {
            super(file, at);
            this.c = c;
            this.a = a;
            this.b = b;
        }
    }

    static final class For extends Node {
        final Node init, cond, step, body;

        For(Node init, Node cond, Node step, Node body, int file, Tok at) {
            super(file, at);
            this.init = init;
            this.cond = cond;
            this.step = step;
            this.body = body;
        }
    }

    static final class ForEach extends Node {
        final String var;
        final boolean reference;
        final Node range, body;

        ForEach(String var, boolean reference, Node range, Node body, int file, Tok at) {
            super(file, at);
            this.var = var;
            this.reference = reference;
            this.range = range;
            this.body = body;
        }
    }

    static final class While extends Node {
        final Node c, body;
        final boolean doWhile;

        While(Node c, Node body, boolean doWhile, int file, Tok at) {
            super(file, at);
            this.c = c;
            this.body = body;
            this.doWhile = doWhile;
        }
    }

    static final class Return extends Node {
        final Node e;

        Return(Node e, int file, Tok at) {
            super(file, at);
            this.e = e;
        }
    }

    static final class Jump extends Node {
        final boolean isBreak;

        Jump(boolean isBreak, int file, Tok at) {
            super(file, at);
            this.isBreak = isBreak;
        }
    }

    /** A statement this reader does not understand, reported when it runs. */
    static final class Skip extends Node {
        final String text;

        Skip(String text, int file, Tok at) {
            super(file, at);
            this.text = text;
        }
    }

    static final class ParseError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ParseError(String m) {
            super(m, null, false, false);
        }
    }

    // ==================================================================
    // Parser
    // ==================================================================

    private static final Set<String> BUILTIN_TYPES = Set.of("int", "double", "float", "bool", "char", "long", "short",
        "unsigned", "signed", "void", "size_t", "G4int", "G4double", "G4float", "G4bool", "G4long", "G4String",
        "G4ThreeVector", "G4RotationMatrix", "G4Transform3D", "G4TwoVector", "G4complex", "G4Colour", "G4Color",
        "G4VisAttributes", "G4Material", "G4Element", "G4LogicalVolume", "G4VPhysicalVolume", "G4VSolid",
        "G4NistManager", "G4GDMLParser", "G4Box", "G4Tubs", "G4Cons", "G4Sphere", "G4Orb", "G4Trd", "G4Trap",
        "G4Torus", "G4Polycone", "G4Polyhedra", "G4PVPlacement", "G4PVReplica", "G4Region", "G4UserLimits",
        "G4Translate3D", "G4Rotate3D", "G4RotateX3D", "G4RotateY3D", "G4RotateZ3D", "G4Scale3D", "G4ReflectZ3D",
        "G4Point3D", "G4Vector3D", "G4Normal3D", "G4Isotope", "G4UnionSolid", "G4SubtractionSolid",
        "G4IntersectionSolid", "G4ExtrudedSolid", "G4SDManager", "G4MultiFunctionalDetector");
    private static final Set<String> SPECIFIERS = Set.of("const", "constexpr", "static", "inline", "thread_local",
        "G4ThreadLocal", "volatile", "mutable", "register", "extern", "typename", "struct", "class");
    private static final Set<String> KEYWORDS = Set.of("return", "if", "else", "for", "while", "do", "new", "delete",
        "break", "continue", "switch", "case", "default", "throw", "try", "catch", "using", "typedef", "goto",
        "sizeof", "this", "true", "false", "nullptr", "operator", "namespace", "static_assert", "co_return");

    final class Parser {
        private final List<Tok> t;
        private final int file;
        private int p;
        private final int end;

        Parser(List<Tok> t, int file, int from, int to) {
            this.t = t;
            this.file = file;
            this.p = from;
            this.end = Math.min(to, t.size() - 1);
        }

        int position() {
            return p;
        }

        private Tok peek() {
            return p < end ? t.get(p) : t.get(t.size() - 1);
        }

        private Tok peek(int k) {
            return p + k < end ? t.get(p + k) : t.get(t.size() - 1);
        }

        private Tok next() {
            final Tok x = peek();
            if (p < end) {
                p++;
            }
            return x;
        }

        private boolean at(String s) {
            return peek().is(s);
        }

        private boolean accept(String s) {
            if (at(s)) {
                p++;
                return true;
            }
            return false;
        }

        private Tok expect(String s) {
            if (!at(s)) {
                throw new ParseError("expected " + s + " at '" + peek().text + "'");
            }
            return next();
        }

        private <N extends Node> N close(N n) {
            n.end = p > 0 ? t.get(Math.min(p, t.size()) - 1).end : n.end;
            return n;
        }

        Block block() {
            final Tok open = expect("{");
            final List<Node> body = new ArrayList<>();
            while (!at("}") && peek().kind != 'e' && p < end) {
                body.add(statement());
            }
            expect("}");
            return close(new Block(body, file, open));
        }

        /** One statement; one the reader does not understand becomes a Skip up to its end. */
        Node statement() {
            final int from = p;
            try {
                return statementOrThrow();
            } catch (ParseError e) {
                p = from;
                return skip();
            }
        }

        private Node skip() {
            final Tok first = peek();
            int depth = 0;
            final StringBuilder text = new StringBuilder();
            while (p < end && peek().kind != 'e') {
                final Tok x = next();
                if (text.length() < 60) {
                    text.append(text.length() > 0 ? " " : "").append(x.text);
                }
                if (x.is("{") || x.is("(")) {
                    depth++;
                } else if (x.is("}") || x.is(")")) {
                    depth--;
                    if (depth <= 0 && x.is("}") && !at(";")) {
                        break;
                    }
                } else if (x.is(";") && depth <= 0) {
                    break;
                }
            }
            return close(new Skip(text.toString(), file, first));
        }

        private Node statementOrThrow() {
            final Tok first = peek();
            if (at("{")) {
                return block();
            }
            if (accept(";")) {
                return new Block(List.of(), file, first);
            }
            if (accept("if")) {
                accept("constexpr");
                expect("(");
                final Node c = conditionOrDecl();
                expect(")");
                final Node a = statement();
                final Node b = accept("else") ? statement() : null;
                return close(new If(c, a, b, file, first));
            }
            if (accept("for")) {
                expect("(");
                // range for: for (const auto& x : v)
                final int save = p;
                final Node rangeFor = rangeFor(first);
                if (rangeFor != null) {
                    return rangeFor;
                }
                p = save;
                Node init = null, cond = null, step = null;
                if (!at(";")) {
                    init = isDeclaration() ? declaration(false) : new ExprStmt(commaExpression(), file, first);
                }
                expect(";");
                if (!at(";")) {
                    cond = expression();
                }
                expect(";");
                if (!at(")")) {
                    step = commaExpression();
                }
                expect(")");
                final Node body = statement();
                return close(new For(init, cond, step, body, file, first));
            }
            if (accept("while")) {
                expect("(");
                final Node c = expression();
                expect(")");
                return close(new While(c, statement(), false, file, first));
            }
            if (accept("do")) {
                final Node body = statement();
                expect("while");
                expect("(");
                final Node c = expression();
                expect(")");
                accept(";");
                return close(new While(c, body, true, file, first));
            }
            if (accept("return")) {
                final Node e = at(";") ? null : expression();
                expect(";");
                return close(new Return(e, file, first));
            }
            if (at("break") || at("continue")) {
                final boolean b = next().is("break");
                expect(";");
                return new Jump(b, file, first);
            }
            if (accept("try")) {
                final Block b = block();
                while (accept("catch")) {
                    expect("(");
                    int depth = 1;
                    while (depth > 0 && p < end) {
                        final Tok x = next();
                        if (x.is("(")) {
                            depth++;
                        } else if (x.is(")")) {
                            depth--;
                        }
                    }
                    block();
                }
                return b;
            }
            if (at("using") || at("typedef") || at("static_assert") || at("delete") || at("goto") || at("throw")) {
                // nothing a geometry depends on: passed over in silence
                while (p < end && !at(";")) {
                    if (at("{")) {
                        p = match(t, p, "{", "}");
                    }
                    p++;
                }
                accept(";");
                return new Block(List.of(), file, first);
            }
            if (at("switch") || at("case") || at("default")) {
                throw new ParseError("not read");
            }
            if ((at("class") || at("struct") || at("enum")) && peek(1).kind == 'i' && peek(2).is(";")) {
                p += 3;
                return new Block(List.of(), file, first);
            }
            if (isDeclaration()) {
                final Node d = declaration(true);
                return d;
            }
            final Node e = commaExpression();
            expect(";");
            return close(new ExprStmt(e, file, first));
        }

        private Node conditionOrDecl() {
            if (isDeclaration()) {
                return declaration(false);
            }
            return expression();
        }

        private Node rangeFor(Tok first) {
            final int save = p;
            while (SPECIFIERS.contains(peek().text)) {
                p++;
            }
            if (!(at("auto") || peek().kind == 'i')) {
                p = save;
                return null;
            }
            try {
                type();
            } catch (ParseError e) {
                p = save;
                return null;
            }
            boolean ref = false;
            while (at("&") || at("&&") || at("*") || at("const")) {
                ref |= !at("const");
                p++;
            }
            if (peek().kind != 'i' || !peek(1).is(":")) {
                p = save;
                return null;
            }
            final String var = next().text;
            expect(":");
            final Node range = expression();
            expect(")");
            final Node body = statement();
            return close(new ForEach(var, ref, range, body, file, first));
        }

        /** Looks ahead: does a declaration start here? */
        boolean isDeclaration() {
            int k = p;
            while (k < end && SPECIFIERS.contains(t.get(k).text)) {
                k++;
            }
            if (k >= end) {
                return false;
            }
            final Tok a = t.get(k);
            if (a.kind != 'i' || KEYWORDS.contains(a.text)) {
                return false;
            }
            if (a.is("auto") || (BUILTIN_TYPES.contains(a.text) && !t.get(k + 1).is("(") && !t.get(k + 1).is("::")
                && !t.get(k + 1).is("{"))) {
                return true;
            }
            // Qualified name, template arguments
            k++;
            while (k + 1 < end && t.get(k).is("::") && t.get(k + 1).kind == 'i') {
                k += 2;
            }
            if (k < end && t.get(k).is("<")) {
                final int close = templateEnd(k);
                if (close < 0) {
                    return false;
                }
                k = close + 1;
                while (k + 1 < end && t.get(k).is("::") && t.get(k + 1).kind == 'i') {
                    k += 2;
                }
            }
            int stars = 0;
            while (k < end && (t.get(k).is("*") || t.get(k).is("&") || t.get(k).is("&&") || t.get(k).is("const"))) {
                stars++;
                k++;
            }
            if (k >= end || t.get(k).kind != 'i' || KEYWORDS.contains(t.get(k).text)) {
                return false;
            }
            final Tok after = t.get(k + 1);
            if (stars == 0) {
                return after.is("=") || after.is(";") || after.is(",") || after.is("(") || after.is("[") || after.is("{")
                    || after.is(":");
            }
            return after.is("=") || after.is(";") || after.is(",") || after.is("(") || after.is("[") || after.is("{");
        }

        /** The index of the '>' closing template arguments at k, or -1 when '<' is a comparison. */
        private int templateEnd(int k) {
            int depth = 0;
            for (int q = k; q < end && q < k + 40; q++) {
                final Tok x = t.get(q);
                if (x.is("<")) {
                    depth++;
                } else if (x.is(">")) {
                    depth--;
                    if (depth == 0) {
                        return q;
                    }
                } else if (x.is(">>")) {
                    depth -= 2;
                    if (depth <= 0) {
                        return q;
                    }
                } else if (!(x.kind == 'i' || x.kind == 'n' || x.is("::") || x.is("*") || x.is("&") || x.is(","))) {
                    return -1;
                }
            }
            return -1;
        }

        private String type() {
            final StringBuilder b = new StringBuilder();
            while (SPECIFIERS.contains(peek().text) && !peek().is("const")) {
                p++;
            }
            accept("const");
            if (peek().kind != 'i') {
                throw new ParseError("type expected");
            }
            b.append(next().text);
            // unsigned int, long long, long double
            while ((b.toString().equals("unsigned") || b.toString().equals("long") || b.toString().equals("signed")
                || b.toString().endsWith(" long")) && (at("int") || at("long") || at("double") || at("char")
                || at("short"))) {
                b.append(' ').append(next().text);
            }
            while (at("::") && peek(1).kind == 'i') {
                p++;
                b.append("::").append(next().text);
            }
            if (at("<")) {
                final int close = templateEnd(p);
                if (close < 0) {
                    throw new ParseError("template");
                }
                b.append('<');
                p++;
                while (p < close) {
                    b.append(next().text);
                }
                p = close + 1;
                b.append('>');
                if (t.get(close).is(">>")) {
                    b.append('>');
                }
                while (at("::") && peek(1).kind == 'i') {
                    p++;
                    b.append("::").append(next().text);
                }
            }
            accept("const");
            return b.toString();
        }

        Node declaration(boolean needSemicolon) {
            final Tok first = peek();
            final String type = type();
            final List<Declarator> vars = new ArrayList<>();
            do {
                boolean pointer = false, reference = false;
                while (at("*") || at("&") || at("&&") || at("const")) {
                    final Tok x = next();
                    pointer |= x.is("*");
                    reference |= x.is("&") || x.is("&&");
                }
                if (peek().kind != 'i') {
                    throw new ParseError("name expected");
                }
                final String name = next().text;
                boolean array = false;
                while (accept("[")) {
                    array = true;
                    if (!at("]")) {
                        conditional();
                    }
                    expect("]");
                }
                Node init = null;
                List<Node> ctor = null;
                if (accept("=")) {
                    init = at("{") ? braces() : assignment();
                } else if (at("(")) {
                    p++;
                    ctor = arguments(")");
                } else if (at("{")) {
                    p++;
                    ctor = arguments("}");
                }
                vars.add(new Declarator(name, pointer, reference, array, init, ctor));
            } while (accept(","));
            if (needSemicolon) {
                expect(";");
            }
            return close(new Decl(type, vars, file, first));
        }

        private List<Node> arguments(String closer) {
            final List<Node> out = new ArrayList<>();
            while (!at(closer)) {
                out.add(at("{") ? braces() : assignment());
                if (!accept(",")) {
                    break;
                }
            }
            expect(closer);
            return out;
        }

        private Node braces() {
            final Tok open = expect("{");
            final List<Node> items = new ArrayList<>();
            while (!at("}")) {
                items.add(at("{") ? braces() : assignment());
                if (!accept(",")) {
                    break;
                }
            }
            expect("}");
            return close(new Braces(items, file, open));
        }

        Node commaExpression() {
            Node e = expression();
            while (at(",")) {
                final Tok op = next();
                e = close(new Binary(",", e, expression(), file, op));
            }
            return e;
        }

        Node expression() {
            return assignment();
        }

        private Node assignment() {
            final Node left = conditional();
            final Tok op = peek();
            if (op.kind == 'o' && (op.is("=") || op.is("+=") || op.is("-=") || op.is("*=") || op.is("/=")
                || op.is("%=") || op.is("|=") || op.is("&=") || op.is("<<=") || op.is(">>="))) {
                p++;
                final Node right = at("{") ? braces() : assignment();
                return close(new Assign(op.text, left, right, file, op));
            }
            return left;
        }

        private Node conditional() {
            final Node c = binary(0);
            if (at("?")) {
                final Tok q = next();
                final Node a = assignment();
                expect(":");
                final Node b = assignment();
                return close(new Cond(c, a, b, file, q));
            }
            return c;
        }

        private static final String[][] LEVELS = {{"||"}, {"&&"}, {"|"}, {"^"}, {"&"}, {"==", "!="},
            {"<", ">", "<=", ">="}, {"<<", ">>"}, {"+", "-"}, {"*", "/", "%"}};

        private Node binary(int level) {
            if (level >= LEVELS.length) {
                return unary();
            }
            Node left = binary(level + 1);
            while (true) {
                final Tok op = peek();
                boolean found = false;
                if (op.kind == 'o') {
                    for (String s : LEVELS[level]) {
                        if (op.text.equals(s)) {
                            found = true;
                            break;
                        }
                    }
                }
                if (!found) {
                    return left;
                }
                p++;
                final Node right = binary(level + 1);
                left = new Binary(op.text, left, right, file, op);
                left.end = right.end;
            }
        }

        private Node unary() {
            final Tok op = peek();
            if (op.kind == 'o' && (op.is("-") || op.is("+") || op.is("!") || op.is("~") || op.is("*") || op.is("&")
                || op.is("++") || op.is("--"))) {
                p++;
                return close(new Unary(op.text, unary(), false, file, op));
            }
            if (op.is("new")) {
                p++;
                final String type = type();
                while (at("*")) {
                    p++;
                }
                List<Node> args = List.of();
                if (at("(")) {
                    p++;
                    args = arguments(")");
                } else if (at("{")) {
                    p++;
                    args = arguments("}");
                } else if (at("[")) {
                    // new T*[n]: an array of n
                    p++;
                    final Node size = conditional();
                    expect("]");
                    return close(new Construct("new[]", List.of(size), true, file, op));
                }
                return close(new Construct(type, args, true, file, op));
            }
            if (op.is("sizeof")) {
                p++;
                if (at("(")) {
                    final int close = match(t, p, "(", ")");
                    p = close + 1;
                } else {
                    unary();
                }
                return new Literal(UNKNOWN, file, op);
            }
            // C-style cast: (G4int) x, (G4VSolid*) s, (const G4double&) v
            final int castEnd = cStyleCast();
            if (castEnd > 0) {
                p++;
                final StringBuilder type = new StringBuilder();
                while (p < castEnd) {
                    type.append(next().text);
                }
                expect(")");
                return close(new Cast(type.toString(), unary(), file, op));
            }
            return postfix(primary());
        }

        /**
         * At '(': the index of the ')' closing a cast to a type (a builtin, a Geant4 class, std::, with const,
         * '*' and '&'), when an operand follows it; -1 for a parenthesised expression.
         */
        private int cStyleCast() {
            if (!at("(")) {
                return -1;
            }
            int k = 1;
            if (peek(k).is("const")) {
                k++;
            }
            final Tok type = peek(k);
            if (type.kind != 'i' || !(BUILTIN_TYPES.contains(type.text) || type.text.startsWith("G4")
                || type.text.equals("std"))) {
                return -1;
            }
            k++;
            while (peek(k).is("::") && peek(k + 1).kind == 'i') {
                k += 2;
            }
            while (peek(k).is("*") || peek(k).is("&") || peek(k).is("const")) {
                k++;
            }
            if (!peek(k).is(")")) {
                return -1;
            }
            final Tok after = peek(k + 1);
            final boolean operand = after.kind == 'i' || after.kind == 'n' || after.kind == 's' || after.is("(")
                || after.is("*") && peek(k + 2).kind == 'i' || after.is("!");
            if (!operand || KEYWORDS.contains(after.text) && !after.is("this") && !after.is("new")) {
                return -1;
            }
            // (G4double) and (G4int): builtin types; a G4 name alone must look like a type, not a variable
            final boolean pointerish = k > 2 && (peek(k - 1).is("*") || peek(k - 1).is("&"));
            return BUILTIN_TYPES.contains(type.text) || pointerish || type.text.equals("std")
                || type.text.length() > 2 && Character.isUpperCase(type.text.charAt(2)) ? p + k : -1;
        }

        private Node postfix(Node e) {
            while (true) {
                final Tok op = peek();
                if (op.is("(")) {
                    p++;
                    final List<Node> args = arguments(")");
                    e = new Call(e, args, file, op);
                    e = spanFrom(e, ((Call) e).callee);
                } else if (op.is("[")) {
                    p++;
                    final Node idx = expression();
                    expect("]");
                    e = spanFrom(new Index(e, idx, file, op), e);
                } else if (op.is(".") || op.is("->")) {
                    p++;
                    accept("template");
                    final Tok name = next();
                    if (name.kind != 'i') {
                        throw new ParseError("member name");
                    }
                    e = spanFrom(new Member(e, name.text, op.is("->"), file, name), e);
                } else if (op.is("++") || op.is("--")) {
                    p++;
                    e = spanFrom(new Unary(op.text, e, true, file, op), e);
                } else {
                    return e;
                }
            }
        }

        /** The node's text starts where its first operand starts. */
        private Node spanFrom(Node n, Node first) {
            n.end = t.get(Math.max(0, p - 1)).end;
            return n instanceof Call c ? new SpannedCall(c, first.start) : n;
        }

        private Node primary() {
            final Tok x = next();
            switch (x.kind) {
                case 'n':
                    return new Literal(x.value, file, x);
                case 's': {
                    final StringBuilder b = new StringBuilder((String) x.value);
                    Tok last = x;
                    while (peek().kind == 's') {
                        last = next();
                        b.append((String) last.value);
                    }
                    final Literal l = new Literal(b.toString(), file, x);
                    l.end = last.end;
                    return l;
                }
                case 'c':
                    return new Literal(((String) x.value).isEmpty() ? 0L : (long) ((String) x.value).charAt(0), file, x);
                case 'e':
                    throw new ParseError("end of input");
                default:
                    break;
            }
            if (x.is("(")) {
                final Node e = commaExpression();
                expect(")");
                return close(new Group(e, file, x));
            }
            if (x.is("{")) {
                p--;
                return braces();
            }
            if (x.is("[")) {
                // a lambda: [captures](params) mutable -> T { body }
                int depth = 1;
                while (depth > 0 && p < end) {
                    final Tok c = next();
                    if (c.is("[")) {
                        depth++;
                    } else if (c.is("]")) {
                        depth--;
                    }
                }
                final List<String> params = new ArrayList<>();
                if (at("(")) {
                    final int close = match(t, p, "(", ")");
                    final Function f = new Function("", "", "", file, p);
                    params(t, p, close, file, f);
                    params.addAll(f.params);
                    p = close + 1;
                }
                while (!at("{") && p < end) {
                    p++;
                }
                final Block body = block();
                return close(new LambdaExpr(params, body, file, x));
            }
            if (x.is("true") || x.is("false")) {
                return new Literal(x.is("true"), file, x);
            }
            if (x.is("nullptr") || x.is("NULL")) {
                return new Literal(NULLPTR, file, x);
            }
            if (x.is("this")) {
                return new Ident("this", file, x);
            }
            if (x.is("::") && peek().kind == 'i') {
                return primary();
            }
            if (x.kind != 'i' || KEYWORDS.contains(x.text)) {
                throw new ParseError("unexpected '" + x.text + "'");
            }
            final StringBuilder name = new StringBuilder(x.text);
            while (at("::") && (peek(1).kind == 'i' || peek(1).is("~"))) {
                p++;
                name.append("::").append(next().text);
            }
            // static_cast<T>(e), std::vector<T>{...}, std::min<double>(a, b)
            if (at("<")) {
                final int close = templateEnd(p);
                if (close >= 0 && close + 1 < end && (t.get(close + 1).is("(") || t.get(close + 1).is("{")
                    || t.get(close + 1).is("::"))) {
                    final StringBuilder targs = new StringBuilder();
                    for (int q = p + 1; q < close; q++) {
                        targs.append(t.get(q).text);
                    }
                    p = close + 1;
                    final String n = name.toString();
                    if (n.endsWith("_cast")) {
                        expect("(");
                        final Node e = expression();
                        expect(")");
                        return close(new Cast(targs.toString(), e, file, x));
                    }
                    name.append('<').append(targs).append('>');
                    while (at("::") && peek(1).kind == 'i') {
                        p++;
                        name.append("::").append(next().text);
                    }
                }
            }
            final String n = name.toString();
            // T{...}: a construction with braces
            if (at("{") && looksLikeType(n)) {
                p++;
                final List<Node> args = arguments("}");
                return close(new Construct(n, args, false, file, x));
            }
            final Ident id = new Ident(n, file, x);
            id.end = t.get(p - 1).end;
            return id;
        }
    }

    /** A call whose source text starts at its callee (for a position written back as text). */
    static final class SpannedCall extends Node {
        final Call call;

        SpannedCall(Call c, int start) {
            super(c.file, new Tok('o', "", null, start, c.end, c.line));
            this.call = c;
            this.end = c.end;
        }
    }

    private static boolean looksLikeType(String n) {
        final String last = n.contains("::") ? n.substring(n.lastIndexOf("::") + 2) : n;
        return !last.isEmpty() && (Character.isUpperCase(last.charAt(0)) || n.startsWith("std::") || last.startsWith("G4")
            || BUILTIN_TYPES.contains(last));
    }

    // ==================================================================
    // Values
    // ==================================================================

    /** A value the code reading cannot know: what a messenger sets, a class it does not model. */
    static final class Unknown {
        final String why;

        Unknown(String why) {
            this.why = why;
        }

        @Override
        public String toString() {
            return why;
        }
    }

    static final Unknown UNKNOWN = new Unknown("unknown");
    static final Object NULLPTR = new Object() {
        @Override
        public String toString() {
            return "nullptr";
        }
    };

    /** A pointer to a value-type object (new G4RotationMatrix, &v): shares, never copies. */
    static final class Ref {
        final Object target;

        Ref(Object target) {
            this.target = target;
        }
    }

    /** G4ThreeVector: mutable, copied on declaration and assignment as in C++. */
    static final class V3 {
        double x, y, z;
        /** The G4ThreeVector(...) expression that made it, while it is not modified. */
        Node origin;

        V3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        V3 copy() {
            final V3 v = new V3(x, y, z);
            v.origin = origin;
            return v;
        }
    }

    /** G4RotationMatrix (CLHEP HepRotation): the matrix R, rotateX(a) making R = Rx(a) R. */
    static final class Rot {
        double[] m = {1, 0, 0, 0, 1, 0, 0, 0, 1};

        Rot copy() {
            final Rot r = new Rot();
            r.m = m.clone();
            return r;
        }

        void pre(double[] a) {
            m = Xform.mul(a, m);
        }
    }

    /** G4Transform3D: the object rotation and the translation. */
    static final class Tr {
        double[] r = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        double x, y, z;
        boolean reflected;
        /** A G4Scale3D with factors other than 1 and -1: a G4ScaledSolid's, never a placement's. */
        boolean scaled;
    }

    /** A G4MultiUnion while its nodes are added: made a solid (nested unions) when a volume or a boolean takes it. */
    static final class MultiUnion {
        final String name;
        final Node at;
        final List<Solid> solids = new ArrayList<>();
        final List<Tr> transforms = new ArrayList<>();
        Solid made;

        MultiUnion(String name, Node at) {
            this.name = name;
            this.at = at;
        }
    }

    static final class Vis {
        int color = -1;
        boolean visible = true;
        Boolean wireframe;
    }

    static final class Arr {
        final List<Object> items = new ArrayList<>();
        /** The {...} it was written as, when it was: a table read from it is written back there. */
        Node origin;
    }

    /** An object the reader does not model, by its class: calls on it answer unknown. */
    static final class Opaque {
        final String type;
        Path gdml;

        Opaque(String type) {
            this.type = type;
        }
    }

    record MatRef(String name) {
    }

    /** An iterator into a container: begin(), end(), and what std::accumulate and the like walk. */
    record Iter(Arr arr, int index) {
    }

    record ElemRef(String symbol) {
    }

    record EnumV(String name) {
    }

    /** The world a G4GDMLParser read. */
    record GdmlWorld(Geometry g) {
    }

    /** The world placement (mother null): Geant4's world physical volume, not a placement in the model. */
    static final class WorldPv {
        final LogicalVolume lv;
        final String name;

        WorldPv(LogicalVolume lv, String name) {
            this.lv = lv;
            this.name = name;
        }
    }

    static final class Lambda {
        final LambdaExpr expr;
        final Env env;

        Lambda(LambdaExpr expr, Env env) {
            this.expr = expr;
            this.env = env;
        }
    }

    static Object deref(Object v) {
        return v instanceof Ref r ? r.target : v;
    }

    private static Object copyValue(Object v) {
        if (v instanceof V3 x) {
            return x.copy();
        }
        if (v instanceof Rot r) {
            return r.copy();
        }
        if (v instanceof Arr a) {
            final Arr c = new Arr();
            c.items.addAll(a.items);
            c.origin = a.origin;
            return c;
        }
        return v;
    }

    // ==================================================================
    // Environment
    // ==================================================================

    static final class Env {
        final Env parent;
        final Map<String, Object> vars = new HashMap<>();
        final Map<String, String> types = new HashMap<>();
        boolean isFields;
        /** For the fields of an object: its class, whose methods its own calls reach first. */
        String cls;

        Env(Env parent) {
            this.parent = parent;
        }

        Env find(String name) {
            for (Env e = this; e != null; e = e.parent) {
                if (e.vars.containsKey(name)) {
                    return e;
                }
            }
            return null;
        }

        Env fields() {
            Env e = this;
            while (e.parent != null && !e.isFields) {
                e = e.parent;
            }
            return e;
        }
    }

    /** An object of a class of the sources (a parameterisation, a helper): its fields, its methods run. */
    static final class Instance {
        final String cls;
        final Env fields;

        Instance(String cls, Env fields) {
            this.cls = cls;
            this.fields = fields;
        }
    }

    /** The physical volume a parameterisation is handed: it keeps what ComputeTransformation sets. */
    static final class PvStub {
        final int copy;
        V3 t = new V3(0, 0, 0);
        double[] frame;

        PvStub(int copy) {
            this.copy = copy;
        }
    }

    /** G4ExtrudedSolid::ZSection(z, offset, scale). */
    record ZSection(double z, double ox, double oy, double scale) {
    }

    private final Map<String, Env> statics = new HashMap<>();

    /** The scope a static method runs in: no object, the globals (statics included) around. */
    private Env staticEnv(String cls) {
        return statics.computeIfAbsent(cls, k -> {
            final Env e = new Env(globalEnv);
            e.isFields = true;
            e.cls = k;
            return e;
        });
    }

    private boolean derives(String cls, String base, int depth) {
        if (cls == null || depth > 8) {
            return false;
        }
        if (cls.equals(base)) {
            return true;
        }
        for (String b : classBases.getOrDefault(cls, List.of())) {
            if (derives(b, base, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private int instantiating;

    private boolean isUserClass(String name) {
        return functions.containsKey(name) || classBases.containsKey(name);
    }

    private Object instantiate(String cls, List<Object> args, Node at) {
        if (instantiating > 16) {
            return new Opaque(cls);   // objects making objects of their own class
        }
        instantiating++;
        try {
            return newInstance(cls, args, at);
        } finally {
            instantiating--;
        }
    }

    private Object newInstance(String cls, List<Object> args, Node at) {
        final Env fields = new Env(globalEnv);
        fields.isFields = true;
        fields.cls = cls;
        initMembers(cls, fields, 0);
        final Function ctor = resolve(cls, cls, args.size(), args.isEmpty() ? null : deref(args.get(0)), 0);
        if (ctor != null && ctor.cls.equals(cls)) {
            call(ctor, args, fields, at);
        }
        return new Instance(cls, fields);
    }

    /** The members of a class and of its bases from the sources, bases first, as C++ builds them. */
    private void initMembers(String cls, Env fields, int depth) {
        if (depth > 8) {
            return;
        }
        for (String b : classBases.getOrDefault(cls, List.of())) {
            initMembers(b, fields, depth + 1);
        }
        for (Node init : memberInits.getOrDefault(cls, List.of())) {
            execQuiet(init, fields);
        }
    }

    /**
     * A method of a class of the sources or of its bases: by name and number of arguments; with a solid as
     * first argument, the overload for that solid's class (ComputeDimensions(G4Tubs&, ...)).
     */
    private Function resolve(String cls, String name, int arity, Object first, int depth) {
        if (cls == null || depth > 8) {
            return null;
        }
        Function best = null;
        int bestScore = -1;
        for (Function f : functions.getOrDefault(cls, List.of())) {
            if (!f.name.equals(name) || f.body == null || arity > f.params.size() || arity < required(f)) {
                continue;
            }
            int score = f.params.size() == arity ? 2 : 1;
            if (first instanceof Solid solid && !f.paramTypes.isEmpty()) {
                final String t = f.paramTypes.get(0).replace("const", "").replaceAll("[&*\\s]", "");
                if (t.equals(solid.g4Class())) {
                    score += 4;
                } else if (t.startsWith("G4") && !t.equals("G4VSolid")) {
                    continue;   // the overload for another solid
                }
            }
            if (score > bestScore) {
                best = f;
                bestScore = score;
            }
        }
        if (best != null) {
            return best;
        }
        for (String b : classBases.getOrDefault(cls, List.of())) {
            final Function f = resolve(b, name, arity, first, depth + 1);
            if (f != null) {
                return f;
            }
        }
        return null;
    }

    /** Parameters without a default, here or in the class body's declaration. */
    private int required(Function f) {
        final Function declared = declarations.get(f.cls + "::" + f.name + "/" + f.params.size());
        int n = 0;
        for (int i = 0; i < f.params.size(); i++) {
            if (f.defaults.get(i) == null && (declared == null || declared.defaults.get(i) == null)) {
                n = i + 1;
            }
        }
        return n;
    }

    private static final class ReturnSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Object value;

        ReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    private static final class JumpSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final boolean isBreak;

        JumpSignal(boolean isBreak) {
            super(null, null, false, false);
            this.isBreak = isBreak;
        }
    }

    private static final class Budget extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Budget() {
            super(null, null, false, false);
        }
    }

    // ==================================================================
    // Statements
    // ==================================================================

    private int depth;

    private Object call(Function f, List<Object> args, Env fields, Node at) {
        if (f.body == null) {
            note(at, Geometry.Severity.INFO, f.cls + "::" + f.name + "() not read");
            return UNKNOWN;
        }
        if (depth > 40) {
            note(at, Geometry.Severity.WARNING, "calls nested too deep: " + f.name);
            return UNKNOWN;
        }
        final Env env = new Env(fields.fields());
        final Function declared = declarations.get(f.cls + "::" + f.name + "/" + f.params.size());
        for (int i = 0; i < f.params.size(); i++) {
            Object v = i < args.size() ? args.get(i) : null;
            if (v == null) {
                Node def = f.defaults.get(i);
                if (def == null && declared != null) {
                    def = declared.defaults.get(i);
                }
                v = def == null ? new Unknown(f.params.get(i) + ", an argument given at run time") : eval(def, env);
            }
            env.vars.put(f.params.get(i), copyValue(v));
        }
        for (Node[] init : f.inits) {
            final String member = ((Ident) init[0]).name;
            final Env self = fields.fields();
            final String type = self.types.get(member);
            Object value;
            if (init.length == 2 && !(type != null && init[1] instanceof Braces)) {
                value = copyValue(eval(init[1], env));
            } else if (type != null && !type.endsWith("*")) {
                // member(a, b, c) or member{a, b, c}: the member's type made of them
                final List<Node> argNodes = init.length == 2 && init[1] instanceof Braces b ? b.items
                    : List.of(init).subList(1, init.length);
                value = construct(type, evalArgs(argNodes, env), false, init[0]);
            } else {
                value = init.length == 1 ? new Unknown(member) : copyValue(eval(init[1], env));
            }
            self.vars.put(member, type == null ? value : convert(type.replace("*", ""), type.endsWith("*"), value));
        }
        depth++;
        running.push(f);
        bodyDepths.push(blockDepth + 1);
        final Node caller = statement;
        try {
            exec(f.body, env);
        } catch (ReturnSignal r) {
            return r.value;
        } catch (JumpSignal j) {
            // a stray break
        } finally {
            depth--;
            running.pop();
            bodyDepths.pop();
            statement = caller;
        }
        return null;
    }

    /** A variable now holds a volume: where daughters of it can be written (in reach at the function's end first). */
    private void bound(Object value, String name, boolean member) {
        if (!(deref(value) instanceof LogicalVolume lv) || running.isEmpty() || running.peek().body == null
            || statement == null) {
            return;
        }
        final boolean top = member || bodyDepths.peek() != null && blockDepth == bodyDepths.peek();
        final InsertSite known = sites.get(lv);
        if (known == null || !known.topLevel() && top) {
            sites.put(lv, site(running.peek(), name, statement.end, top));
        }
    }

    private void execQuiet(Node n, Env env) {
        try {
            exec(n, env);
        } catch (RuntimeException e) {
            if (e instanceof Budget) {
                throw e;
            }
        }
    }

    private void exec(Node n, Env env) {
        if (++steps > MAX_STEPS) {
            throw new Budget();
        }
        if (n instanceof Block b) {
            final Env inner = new Env(env);
            blockDepth++;
            try {
                for (Node s : b.body) {
                    exec(s, inner);
                }
            } finally {
                blockDepth--;
            }
        } else if (n instanceof ExprStmt s) {
            statement = s;
            eval(s.e, env);
        } else if (n instanceof Decl d) {
            statement = d;
            declare(d, env);
        } else if (n instanceof If i) {
            final Env inner = new Env(env);
            final Object c = i.c instanceof Decl d ? declare(d, inner) : eval(i.c, inner);
            final Boolean b = truth(c);
            if (b == null) {
                note(i, Geometry.Severity.INFO, "condition not known while reading ("
                    + (c instanceof Unknown u ? u.why : "?") + "): " + (i.b != null ? "the else branch is shown"
                    : "the branch is skipped"));
                if (i.b != null) {
                    exec(i.b, inner);
                }
            } else if (b) {
                exec(i.a, inner);
            } else if (i.b != null) {
                exec(i.b, inner);
            }
        } else if (n instanceof For f) {
            final Env inner = new Env(env);
            if (f.init != null) {
                exec(f.init, inner);
            }
            while (true) {
                if (f.cond != null) {
                    final Boolean b = truth(eval(f.cond, inner));
                    if (b == null) {
                        note(f, Geometry.Severity.WARNING, "loop bound not known while reading: the loop is not run");
                        break;
                    }
                    if (!b) {
                        break;
                    }
                }
                try {
                    exec(f.body, new Env(inner));
                } catch (JumpSignal j) {
                    if (j.isBreak) {
                        break;
                    }
                }
                if (f.step != null) {
                    eval(f.step instanceof ExprStmt s ? s.e : f.step, inner);
                }
                if (++steps > MAX_STEPS) {
                    throw new Budget();
                }
            }
        } else if (n instanceof ForEach f) {
            final Object range = deref(eval(f.range, env));
            if (!(range instanceof Arr a)) {
                note(f, Geometry.Severity.INFO, "range of the loop not known while reading: the loop is not run");
                return;
            }
            for (Object item : new ArrayList<>(a.items)) {
                final Env inner = new Env(env);
                inner.vars.put(f.var, f.reference ? item : copyValue(item));
                try {
                    exec(f.body, inner);
                } catch (JumpSignal j) {
                    if (j.isBreak) {
                        break;
                    }
                }
            }
        } else if (n instanceof While w) {
            boolean first = w.doWhile;
            while (true) {
                if (!first) {
                    final Boolean b = truth(eval(w.c, env));
                    if (b == null) {
                        note(w, Geometry.Severity.WARNING, "loop condition not known while reading: the loop is not run");
                        break;
                    }
                    if (!b) {
                        break;
                    }
                }
                first = false;
                try {
                    exec(w.body, new Env(env));
                } catch (JumpSignal j) {
                    if (j.isBreak) {
                        break;
                    }
                }
                if (++steps > MAX_STEPS) {
                    throw new Budget();
                }
            }
        } else if (n instanceof Return r) {
            throw new ReturnSignal(r.e == null ? null : eval(r.e, env));
        } else if (n instanceof Jump j) {
            throw new JumpSignal(j.isBreak);
        } else if (n instanceof Skip s) {
            note(s, Geometry.Severity.INFO, "not read: " + s.text + (s.text.length() >= 60 ? "..." : ""));
        }
    }

    private Object declare(Decl d, Env env) {
        Object last = null;
        for (Declarator v : d.vars) {
            Object value;
            if (v.ctorArgs != null) {
                value = construct(d.type, evalArgs(v.ctorArgs, env), false, d);
            } else if (v.init != null) {
                value = v.init instanceof Braces b && !v.array && !isContainer(d.type)
                    ? construct(d.type, evalArgs(b.items, env), false, d) : eval(v.init, env);
                if (v.init instanceof Braces && (v.array || isContainer(d.type)) && value instanceof Arr) {
                    // as written
                } else if (!v.pointer && !v.reference) {
                    value = copyValue(value);
                }
            } else if (v.array || isContainer(d.type)) {
                value = new Arr();
            } else if (!v.pointer) {
                value = construct(d.type, List.of(), false, d);
                if (value instanceof Unknown) {
                    value = new Unknown(v.name + ", not initialised");
                }
            } else {
                value = new Unknown(v.name + ", not initialised");
            }
            value = convert(d.type, v.pointer, value);
            env.vars.put(v.name, value);
            env.types.put(v.name, d.type + (v.pointer ? "*" : ""));
            if (statement == d) {
                bound(value, v.name, false);
            }
            last = value;
        }
        return last;
    }

    private static boolean isContainer(String type) {
        return type.startsWith("std::vector") || type.startsWith("vector<") || type.startsWith("std::array")
            || type.startsWith("G4TwoVectorList") || type.startsWith("std::list");
    }

    private static final Set<String> INT_TYPES = Set.of("int", "G4int", "long", "G4long", "short", "size_t",
        "std::size_t", "unsigned", "unsigned int", "unsigned long", "long long", "char", "std::int32_t", "std::int64_t");
    private static final Set<String> REAL_TYPES = Set.of("double", "G4double", "float", "G4float", "long double");

    private static Object convert(String type, boolean pointer, Object v) {
        if (pointer) {
            return v instanceof Long l && l == 0 ? NULLPTR : v;
        }
        if (INT_TYPES.contains(type)) {
            if (v instanceof Double d) {
                return (long) d.doubleValue();
            }
            if (v instanceof Boolean b) {
                return b ? 1L : 0L;
            }
        } else if (REAL_TYPES.contains(type)) {
            if (v instanceof Long l) {
                return l.doubleValue();
            }
            if (v instanceof Boolean b) {
                return b ? 1.0 : 0.0;
            }
        } else if (type.equals("bool") || type.equals("G4bool")) {
            if (v instanceof Number num) {
                return num.doubleValue() != 0;
            }
        }
        return v;
    }

    private static Boolean truth(Object v) {
        v = deref(v);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (v == NULLPTR) {
            return false;
        }
        if (v instanceof Unknown) {
            return null;
        }
        return v != null;
    }

    // ==================================================================
    // Expressions
    // ==================================================================

    private List<Object> evalArgs(List<Node> args, Env env) {
        final List<Object> out = new ArrayList<>(args.size());
        for (Node a : args) {
            out.add(eval(a, env));
        }
        return out;
    }

    private Object eval(Node n, Env env) {
        n.evaluations++;
        if (n instanceof Group gr) {
            return eval(gr.inner, env);
        }
        if (n instanceof Literal l) {
            return l.value;
        }
        if (n instanceof Ident id) {
            return lookup(id, env);
        }
        if (n instanceof Binary b) {
            if (b.op.equals("&&") || b.op.equals("||")) {
                final Boolean a = truth(eval(b.a, env));
                if (a != null && (b.op.equals("&&") ? !a : a)) {
                    return a;
                }
                final Boolean c = truth(eval(b.b, env));
                if (a == null || c == null) {
                    return a == null && c != null && (b.op.equals("&&") ? !c : c) ? c : UNKNOWN;
                }
                return c;
            }
            if (b.op.equals(",")) {
                eval(b.a, env);
                return eval(b.b, env);
            }
            return binary(b.op, eval(b.a, env), eval(b.b, env), b);
        }
        if (n instanceof Unary u) {
            return unary(u, env);
        }
        if (n instanceof Cond c) {
            final Boolean b = truth(eval(c.c, env));
            return b == null ? UNKNOWN : eval(b ? c.a : c.b, env);
        }
        if (n instanceof Assign a) {
            return assign(a, env);
        }
        if (n instanceof SpannedCall sc) {
            final Object v = eval(sc.call, env);
            if (v instanceof V3 vec && vec.origin == sc.call) {
                vec.origin = sc;
                sc.evaluations = sc.call.evaluations;
            }
            return v;
        }
        if (n instanceof Call c) {
            return callExpr(c, env);
        }
        if (n instanceof Member m) {
            final Object obj = deref(eval(m.obj, env));
            if (obj instanceof Unknown) {
                return obj;
            }
            if (m.obj instanceof Ident id && id.name.equals("this")) {
                return lookup(new Ident(m.name, m.file, new Tok('i', m.name, null, m.start, m.end, m.line)), env);
            }
            return field(obj, m.name);
        }
        if (n instanceof Index ix) {
            final Object obj = deref(eval(ix.obj, env));
            final Object i = eval(ix.index, env);
            if (obj instanceof Arr a && i instanceof Number k) {
                final int idx = k.intValue();
                return idx >= 0 && idx < a.items.size() ? a.items.get(idx) : new Unknown("index out of range");
            }
            if (obj instanceof V3 v && i instanceof Number k) {
                return k.intValue() == 0 ? v.x : k.intValue() == 1 ? v.y : v.z;
            }
            return UNKNOWN;
        }
        if (n instanceof Construct c) {
            final Object v = construct(c.type, evalArgs(c.args, env), c.heap, c);
            return c.heap && (v instanceof V3 || v instanceof Rot || v instanceof Tr || v instanceof Vis || v instanceof Arr)
                ? new Ref(v) : v;
        }
        if (n instanceof Cast c) {
            return convert(c.type.replace("const", "").strip(), c.type.contains("*"), eval(c.e, env));
        }
        if (n instanceof Braces b) {
            final Arr a = new Arr();
            for (Node item : b.items) {
                a.items.add(eval(item, env));
            }
            a.origin = b;
            return a;
        }
        if (n instanceof LambdaExpr l) {
            return new Lambda(l, env);
        }
        return UNKNOWN;
    }

    private Object lookup(Ident id, Env env) {
        String name = id.name;
        if (name.equals("this")) {
            return env.fields();
        }
        final Env e = env.find(name);
        if (e != null) {
            return e.vars.get(name);
        }
        if (name.contains("::")) {
            final String member = name.substring(name.lastIndexOf("::") + 2);
            final Env q = env.find(member);
            if (q != null) {
                return q.vars.get(member);
            }
        }
        if (name.startsWith("CLHEP::")) {
            name = name.substring(7);
        } else if (name.startsWith("std::")) {
            name = name.substring(5);
        }
        final Double c = constant(name);
        if (c != null) {
            return c;
        }
        if (name.equals("G4endl") || name.equals("endl") || name.equals("G4cout") || name.equals("G4cerr")
            || name.equals("cout") || name.equals("cerr")) {
            return new Opaque("stream");
        }
        if (name.length() > 1 && name.charAt(0) == 'k' && Character.isUpperCase(name.charAt(1))) {
            return new EnumV(name);
        }
        if (name.endsWith("::IDENTITY") && (name.contains("Rotation") || name.contains("HepRotation"))) {
            return new Rot();
        }
        if (name.contains("::")) {
            final String last = name.substring(name.lastIndexOf("::") + 2);
            if (name.startsWith("G4VisAttributes::") && last.equals("Invisible")) {
                final Vis v = new Vis();
                v.visible = false;
                return v;
            }
            if (last.length() > 1 && last.charAt(0) == 'k' && Character.isUpperCase(last.charAt(1))) {
                return new EnumV(last);
            }
        }
        return new Unknown(id.name + " (not set in the code read)");
    }

    private static final Map<String, Double> CLHEP = new HashMap<>();

    static {
        final double pi = Units.PI;
        CLHEP.put("twopi", 2 * pi);
        CLHEP.put("halfpi", pi / 2);
        CLHEP.put("pi2", pi * pi);
        CLHEP.put("M_PI", pi);
        CLHEP.put("perCent", 0.01);
        CLHEP.put("perThousand", 0.001);
        CLHEP.put("perMillion", 1e-6);
        CLHEP.put("eplus", 1.0);
        CLHEP.put("e_SI", Units.E_SI);
        CLHEP.put("universe_mean_density", 1.e-25 * Units.G_PER_CM3);
        CLHEP.put("DBL_MAX", Double.MAX_VALUE);
        CLHEP.put("DBL_MIN", Double.MIN_NORMAL);
        CLHEP.put("FLT_MAX", (double) Float.MAX_VALUE);
        CLHEP.put("kInfinity", 9.0E99);
        CLHEP.put("STP_Temperature", 273.15);
        CLHEP.put("STP_Pressure", 101325. * Units.symbol("pascal"));
        CLHEP.put("kGasThreshold", 10. * Units.G_PER_CM3 / 1000);
        CLHEP.put("Avogadro", Units.AVOGADRO);
        CLHEP.put("hep_pascal", Units.symbol("pascal"));
        CLHEP.put("electron_mass_c2", 0.510998950);
        CLHEP.put("proton_mass_c2", 938.27208816);
        CLHEP.put("c_light", 299.792458);
        CLHEP.put("pc", 3.0856775807e+16 * 1000);
    }

    private static Double constant(String name) {
        final Double c = CLHEP.get(name);
        if (c != null) {
            return c;
        }
        // CLHEP units: the GDML table (built from CLHEP's) less its one-letter SI names C++ code never uses
        if (name.length() == 1 && !name.equals("m") && !name.equals("s") && !name.equals("g") && !name.equals("L")) {
            return null;
        }
        return Units.symbol(name);
    }

    private Object unary(Unary u, Env env) {
        if (u.op.equals("++") || u.op.equals("--")) {
            final Object old = eval(u.e, env);
            final Object now = binary(u.op.equals("++") ? "+" : "-", old, 1L, u);
            store(u.e, now, env);
            return u.postfix ? old : now;
        }
        if (u.op.equals("&") && u.e instanceof Index ix) {
            // &v[0] handed to a constructor taking a C array
            final Object arr = deref(eval(ix.obj, env));
            final Object i = eval(ix.index, env);
            if (arr instanceof Arr a && i instanceof Number k && k.intValue() >= 0 && k.intValue() <= a.items.size()) {
                if (k.intValue() == 0) {
                    return a;
                }
                final Arr view = new Arr();
                view.items.addAll(a.items.subList(k.intValue(), a.items.size()));
                return view;
            }
        }
        final Object v = eval(u.e, env);
        switch (u.op) {
            case "-":
                return binary("*", -1L, v, u);
            case "+":
                return v;
            case "!": {
                final Boolean b = truth(v);
                return b == null ? UNKNOWN : !b;
            }
            case "*":
                if (v instanceof Iter it) {
                    return it.index() >= 0 && it.index() < it.arr().items.size() ? it.arr().items.get(it.index()) : UNKNOWN;
                }
                return deref(v);
            case "&":
                return v instanceof V3 || v instanceof Rot || v instanceof Tr || v instanceof Vis || v instanceof Arr
                    ? new Ref(v) : v;
            default:
                return UNKNOWN;
        }
    }

    private Object assign(Assign a, Env env) {
        Object v = eval(a.value, env);
        if (!a.op.equals("=")) {
            v = binary(a.op.substring(0, a.op.length() - 1), eval(a.target, env), v, a);
        }
        store(a.target, v, env);
        return v;
    }

    private void store(Node target, Object v, Env env) {
        if (target instanceof Group gr) {
            store(gr.inner, v, env);
            return;
        }
        if (target instanceof Ident id) {
            final Env e = env.find(id.name);
            final Env into = e != null ? e : env.fields();
            final String type = into.types.get(id.name);
            Object value = v instanceof Ref ? v : copyValue(v);
            if (type != null) {
                value = convert(type.replace("*", ""), type.endsWith("*"), value);
            }
            into.vars.put(id.name, value);
            bound(value, id.name, into.isFields);
        } else if (target instanceof Member m) {
            final Object obj = deref(eval(m.obj, env));
            if (m.obj instanceof Ident id && id.name.equals("this")) {
                env.fields().vars.put(m.name, copyValue(v));
            } else if (obj instanceof V3 vec && v instanceof Number num) {
                switch (m.name) {
                    case "x" -> vec.x = num.doubleValue();
                    case "y" -> vec.y = num.doubleValue();
                    case "z" -> vec.z = num.doubleValue();
                    default -> {
                    }
                }
                vec.origin = null;
            }
        } else if (target instanceof Index ix) {
            final Object obj = deref(eval(ix.obj, env));
            final Object i = eval(ix.index, env);
            if (obj instanceof Arr a && i instanceof Number k) {
                final int idx = k.intValue();
                while (a.items.size() <= idx && idx < 1_000_000) {
                    a.items.add(UNKNOWN);
                }
                if (idx >= 0) {
                    a.items.set(idx, copyValue(v));
                }
            }
        } else if (target instanceof Unary u && u.op.equals("*")) {
            store(u.e, v, env);
        }
    }

    private Object binary(String op, Object a, Object b, Node at) {
        a = deref(a);
        b = deref(b);
        if (a instanceof Iter ia) {
            if (b instanceof Number k && (op.equals("+") || op.equals("-"))) {
                return new Iter(ia.arr(), ia.index() + (op.equals("+") ? 1 : -1) * k.intValue());
            }
            if (b instanceof Iter ib) {
                return switch (op) {
                    case "-" -> (long) (ia.index() - ib.index());
                    case "==" -> ia.index() == ib.index();
                    case "!=" -> ia.index() != ib.index();
                    case "<" -> ia.index() < ib.index();
                    default -> UNKNOWN;
                };
            }
        }
        if (op.equals("<<") || op.equals(">>")) {
            if (a instanceof Opaque || b instanceof Opaque || a instanceof String) {
                return a;   // a stream
            }
        }
        if (a instanceof Unknown) {
            return a;
        }
        if (b instanceof Unknown) {
            return b;
        }
        if (a instanceof String || b instanceof String) {
            if (op.equals("+")) {
                return str(a) + str(b);
            }
            if (op.equals("==")) {
                return str(a).equals(str(b));
            }
            if (op.equals("!=")) {
                return !str(a).equals(str(b));
            }
            return UNKNOWN;
        }
        if (a instanceof V3 || b instanceof V3) {
            return vectorOp(op, a, b);
        }
        if (a instanceof Rot r && b instanceof Rot s) {
            if (op.equals("*")) {
                final Rot out = new Rot();
                out.m = Xform.mul(r.m, s.m);
                return out;
            }
            return UNKNOWN;
        }
        if (a instanceof Tr t1 && b instanceof Tr t2 && op.equals("*")) {
            final Tr out = new Tr();
            out.r = Xform.mul(t1.r, t2.r);
            out.x = t1.r[0] * t2.x + t1.r[1] * t2.y + t1.r[2] * t2.z + t1.x;
            out.y = t1.r[3] * t2.x + t1.r[4] * t2.y + t1.r[5] * t2.z + t1.y;
            out.z = t1.r[6] * t2.x + t1.r[7] * t2.y + t1.r[8] * t2.z + t1.z;
            out.reflected = t1.reflected ^ t2.reflected;
            return out;
        }
        if (a == NULLPTR || b == NULLPTR) {
            if (op.equals("==")) {
                return a == b;
            }
            if (op.equals("!=")) {
                return a != b;
            }
        }
        if (a instanceof Boolean x) {
            a = x ? 1L : 0L;
        }
        if (b instanceof Boolean x) {
            b = x ? 1L : 0L;
        }
        if (!(a instanceof Number) || !(b instanceof Number)) {
            if (op.equals("==")) {
                return a == b;
            }
            if (op.equals("!=")) {
                return a != b;
            }
            return UNKNOWN;
        }
        if (a instanceof Long x && b instanceof Long y) {
            final long p = x, q = y;
            return switch (op) {
                case "+" -> p + q;
                case "-" -> p - q;
                case "*" -> p * q;
                case "/" -> q == 0 ? (Object) new Unknown("division by zero") : (Object) (p / q);
                case "%" -> q == 0 ? (Object) new Unknown("division by zero") : (Object) (p % q);
                case "<" -> p < q;
                case ">" -> p > q;
                case "<=" -> p <= q;
                case ">=" -> p >= q;
                case "==" -> p == q;
                case "!=" -> p != q;
                case "&" -> p & q;
                case "|" -> p | q;
                case "^" -> p ^ q;
                case "<<" -> p << q;
                case ">>" -> p >> q;
                default -> UNKNOWN;
            };
        }
        final double p = ((Number) a).doubleValue(), q = ((Number) b).doubleValue();
        return switch (op) {
            case "+" -> p + q;
            case "-" -> p - q;
            case "*" -> p * q;
            case "/" -> p / q;
            case "%" -> p % q;
            case "<" -> p < q;
            case ">" -> p > q;
            case "<=" -> p <= q;
            case ">=" -> p >= q;
            case "==" -> p == q;
            case "!=" -> p != q;
            default -> UNKNOWN;
        };
    }

    private static Object vectorOp(String op, Object a, Object b) {
        if (a instanceof V3 u && b instanceof V3 v) {
            return switch (op) {
                case "+" -> new V3(u.x + v.x, u.y + v.y, u.z + v.z);
                case "-" -> new V3(u.x - v.x, u.y - v.y, u.z - v.z);
                case "*" -> u.x * v.x + u.y * v.y + u.z * v.z;
                case "==" -> u.x == v.x && u.y == v.y && u.z == v.z;
                case "!=" -> !(u.x == v.x && u.y == v.y && u.z == v.z);
                default -> UNKNOWN;
            };
        }
        if (a instanceof V3 u && b instanceof Number s) {
            final double k = s.doubleValue();
            return switch (op) {
                case "*" -> new V3(u.x * k, u.y * k, u.z * k);
                case "/" -> new V3(u.x / k, u.y / k, u.z / k);
                default -> UNKNOWN;
            };
        }
        if (a instanceof Number s && b instanceof V3 v && op.equals("*")) {
            final double k = s.doubleValue();
            return new V3(v.x * k, v.y * k, v.z * k);
        }
        if (a instanceof Rot r && b instanceof V3 v && op.equals("*")) {
            return apply(r.m, v);
        }
        return UNKNOWN;
    }

    private static V3 apply(double[] m, V3 v) {
        return new V3(m[0] * v.x + m[1] * v.y + m[2] * v.z, m[3] * v.x + m[4] * v.y + m[5] * v.z,
            m[6] * v.x + m[7] * v.y + m[8] * v.z);
    }

    private static String str(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.format(Locale.ROOT, "%.6f", d);   // std::to_string's format
        }
        return String.valueOf(v);
    }

    private static Double num(Object v) {
        v = deref(v);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Boolean b) {
            return b ? 1.0 : 0.0;
        }
        return null;
    }

    // ==================================================================
    // Calls
    // ==================================================================

    private Object callExpr(Call c, Env env) {
        if (c.callee instanceof Member m) {
            if (m.obj instanceof Ident id && id.name.equals("this")) {
                return callNamed(m.name, c, env);
            }
            final Object obj = eval(m.obj, env);
            final List<Object> args = evalArgs(c.args, env);
            return method(deref(obj), m.name, args, c, m.obj, env);
        }
        if (c.callee instanceof Ident id) {
            return callNamed(id.name, c, env);
        }
        final Object f = eval(c.callee, env);
        if (f instanceof Lambda l) {
            return invoke(l, evalArgs(c.args, env), c);
        }
        evalArgs(c.args, env);
        return UNKNOWN;
    }

    private Object invoke(Lambda l, List<Object> args, Node at) {
        final Env env = new Env(l.env);
        for (int i = 0; i < l.expr.params.size(); i++) {
            env.vars.put(l.expr.params.get(i), i < args.size() ? copyValue(args.get(i)) : UNKNOWN);
        }
        try {
            exec(l.expr.body, env);
        } catch (ReturnSignal r) {
            return r.value;
        }
        return null;
    }

    private Object callNamed(String name, Call c, Env env) {
        // a variable holding a lambda
        final Env holder = env.find(name);
        if (holder != null && holder.vars.get(name) instanceof Lambda l) {
            return invoke(l, evalArgs(c.args, env), c);
        }
        // the class's own methods: DefineMaterials(), ConstructCalorimeter()...
        final Env self = env.fields();
        if (self.cls != null && !name.contains("::")) {
            final Function own = resolve(self.cls, name, c.args.size(), null, 0);
            if (own != null) {
                return call(own, evalArgs(c.args, env), self, c);
            }
        }
        final String simple = name.contains("::") ? name.substring(name.lastIndexOf("::") + 2) : name;
        if (name.contains("::") && !name.startsWith("std::") && !name.startsWith("CLHEP::")) {
            final String q = name.substring(0, name.lastIndexOf("::"));
            final String cls = q.contains("::") ? q.substring(q.lastIndexOf("::") + 2) : q;
            if (isUserClass(cls)) {
                final Function f = resolve(cls, simple, c.args.size(), null, 0);
                if (f != null) {
                    final Env on = derives(self.cls, cls, 0) ? self : staticEnv(cls);
                    return call(f, evalArgs(c.args, env), on, c);
                }
            }
        }
        final List<Function> fs = functions.get("::" + simple);
        if (fs != null && !name.startsWith("std::") && !name.startsWith("G4")) {
            Function best = null;
            for (Function f : fs) {
                if (f.params.size() >= c.args.size() && (best == null || f.params.size() == c.args.size())) {
                    best = f;
                }
            }
            if (best != null) {
                return call(best, evalArgs(c.args, env), env, c);
            }
        }
        if (simple.equals("SetSensitiveDetector") && c.args.size() >= 2) {
            // G4VUserDetectorConstruction::SetSensitiveDetector(volume name or volume, detector[, multi])
            final List<Object> a = evalArgs(c.args, env);
            sensitive(deref(a.get(0)), deref(a.get(1)), c);
            return null;
        }
        final List<Object> args = evalArgs(c.args, env);
        String n = name.startsWith("std::") ? name.substring(5) : name.startsWith("CLHEP::") ? name.substring(7) : name;
        final Object math = math(n, args);
        if (math != null) {
            return math;
        }
        final Object stat = staticCall(name, args, c);
        if (stat != null) {
            return stat;
        }
        if (looksLikeType(name)) {
            return construct(name, args, false, c);
        }
        return new Unknown(name + "() (not read)");
    }

    private static Object math(String n, List<Object> args) {
        final Double a = args.isEmpty() ? null : num(args.get(0));
        final Double b = args.size() < 2 ? null : num(args.get(1));
        switch (n) {
            case "sqrt", "cos", "sin", "tan", "acos", "asin", "atan", "exp", "log", "log10", "fabs", "abs", "floor",
                 "ceil", "round", "cbrt", "sinh", "cosh", "tanh", "trunc": {
                if (args.isEmpty()) {
                    return UNKNOWN;
                }
                if (a == null) {
                    return deref(args.get(0)) instanceof Unknown u ? u : UNKNOWN;
                }
                final double x = a;
                final double r = switch (n) {
                    case "sqrt" -> Math.sqrt(x);
                    case "cos" -> Math.cos(x);
                    case "sin" -> Math.sin(x);
                    case "tan" -> Math.tan(x);
                    case "acos" -> Math.acos(x);
                    case "asin" -> Math.asin(x);
                    case "atan" -> Math.atan(x);
                    case "exp" -> Math.exp(x);
                    case "log" -> Math.log(x);
                    case "log10" -> Math.log10(x);
                    case "floor" -> Math.floor(x);
                    case "ceil" -> Math.ceil(x);
                    case "round" -> Math.rint(x);
                    case "cbrt" -> Math.cbrt(x);
                    case "sinh" -> Math.sinh(x);
                    case "cosh" -> Math.cosh(x);
                    case "tanh" -> Math.tanh(x);
                    case "trunc" -> x < 0 ? Math.ceil(x) : Math.floor(x);
                    default -> Math.abs(x);
                };
                return n.equals("abs") && deref(args.get(0)) instanceof Long l ? (Object) Math.abs(l) : (Object) r;
            }
            case "atan2", "pow", "hypot", "fmod", "min", "max", "fmin", "fmax": {
                if (a == null || b == null) {
                    return UNKNOWN;
                }
                if ((n.equals("min") || n.equals("max")) && deref(args.get(0)) instanceof Long x
                    && deref(args.get(1)) instanceof Long y) {
                    return n.equals("min") ? Math.min(x, y) : Math.max(x, y);
                }
                return switch (n) {
                    case "atan2" -> Math.atan2(a, b);
                    case "pow" -> Math.pow(a, b);
                    case "hypot" -> Math.hypot(a, b);
                    case "fmod" -> a % b;
                    case "min", "fmin" -> Math.min(a, b);
                    default -> Math.max(a, b);
                };
            }
            case "sqr":
                return a == null ? UNKNOWN : a * a;
            case "to_string":
                return args.isEmpty() || deref(args.get(0)) instanceof Unknown ? UNKNOWN
                    : deref(args.get(0)) instanceof Long l ? String.valueOf(l) : str(deref(args.get(0)));
            case "stoi", "atoi":
                try {
                    return Long.parseLong(String.valueOf(deref(args.get(0))).strip());
                } catch (Exception e) {
                    return UNKNOWN;
                }
            case "stod", "atof":
                try {
                    return Double.parseDouble(String.valueOf(deref(args.get(0))).strip());
                } catch (Exception e) {
                    return UNKNOWN;
                }
            case "accumulate": {
                // std::accumulate(first, last, init): a sum, an integer one when every term is
                if (args.size() < 3 || !(deref(args.get(0)) instanceof Iter f) || !(deref(args.get(1)) instanceof Iter l)) {
                    return UNKNOWN;
                }
                final Object init = deref(args.get(2));
                boolean whole = init instanceof Long;
                double sum = num(init) == null ? Double.NaN : num(init);
                for (int k = f.index(); k < l.index() && k < f.arr().items.size(); k++) {
                    final Object item = deref(f.arr().items.get(k));
                    final Double x = num(item);
                    if (x == null) {
                        return item instanceof Unknown u ? u : UNKNOWN;
                    }
                    whole &= item instanceof Long;
                    sum += x;
                }
                return Double.isNaN(sum) ? UNKNOWN : whole ? (Object) (long) sum : (Object) sum;
            }
            case "max_element", "min_element": {
                if (args.size() < 2 || !(deref(args.get(0)) instanceof Iter f) || !(deref(args.get(1)) instanceof Iter l)) {
                    return UNKNOWN;
                }
                int best = -1;
                double bv = 0;
                for (int k = f.index(); k < l.index() && k < f.arr().items.size(); k++) {
                    final Double x = num(f.arr().items.get(k));
                    if (x == null) {
                        return UNKNOWN;
                    }
                    if (best < 0 || (n.equals("max_element") ? x > bv : x < bv)) {
                        best = k;
                        bv = x;
                    }
                }
                return best < 0 ? l : new Iter(f.arr(), best);
            }
            case "distance":
                return args.size() == 2 && deref(args.get(0)) instanceof Iter f && deref(args.get(1)) instanceof Iter l
                    ? (Object) (long) (l.index() - f.index()) : UNKNOWN;
            case "size":
                return args.size() == 1 && deref(args.get(0)) instanceof Arr arr ? (Object) (long) arr.items.size() : null;
            default:
                return null;
        }
    }

    private Object staticCall(String name, List<Object> args, Node at) {
        switch (name) {
            case "G4NistManager::Instance", "G4NistManager::GetPointer":
                return new Opaque("G4NistManager");
            case "G4Material::GetMaterial":
                return args.isEmpty() || !(deref(args.get(0)) instanceof String s) ? UNKNOWN : new MatRef(s);
            case "G4Element::GetElement":
                return args.isEmpty() || !(deref(args.get(0)) instanceof String s) ? UNKNOWN : new ElemRef(s);
            case "G4VisAttributes::GetInvisible": {
                final Vis v = new Vis();
                v.visible = false;
                return v;
            }
            case "G4LogicalVolumeStore::GetInstance", "G4PhysicalVolumeStore::GetInstance",
                 "G4SolidStore::GetInstance", "G4SDManager::GetSDMpointer", "G4RunManager::GetRunManager",
                 "G4GeometryManager::GetInstance", "G4ReflectionFactory::Instance", "G4RegionStore::GetInstance":
                return new Opaque(name.substring(0, name.indexOf("::")));
            case "G4Exception":
                note(at, Geometry.Severity.WARNING, "G4Exception: " + (args.size() > 3 ? str(deref(args.get(3))) : ""));
                return null;
            default:
                break;
        }
        if (name.startsWith("G4Colour::") || name.startsWith("G4Color::")) {
            return colour(name.substring(name.indexOf("::") + 2));
        }
        return null;
    }

    private static int[] colour(String name) {
        return switch (name) {
            case "White" -> new int[] {255, 255, 255, 255};
            case "Gray", "Grey" -> new int[] {128, 128, 128, 255};
            case "Black" -> new int[] {0, 0, 0, 255};
            case "Brown" -> new int[] {115, 77, 26, 255};
            case "Red" -> new int[] {255, 0, 0, 255};
            case "Green" -> new int[] {0, 255, 0, 255};
            case "Blue" -> new int[] {0, 0, 255, 255};
            case "Cyan" -> new int[] {0, 255, 255, 255};
            case "Magenta" -> new int[] {255, 0, 255, 255};
            case "Yellow" -> new int[] {255, 255, 0, 255};
            default -> new int[] {154, 167, 184, 255};
        };
    }

    // ==================================================================
    // Methods on values
    // ==================================================================

    private Object method(Object obj, String name, List<Object> args, Node at, Node receiver, Env env) {
        if (obj instanceof Unknown) {
            return obj;
        }
        final Object a0 = args.isEmpty() ? null : deref(args.get(0));
        if (obj instanceof Opaque o) {
            return opaqueMethod(o, name, args, at);
        }
        if (obj instanceof V3 v) {
            return vectorMethod(v, name, args);
        }
        if (obj instanceof Rot r) {
            final Double a = num(a0);
            switch (name) {
                case "rotateX", "rotateY", "rotateZ" -> {
                    if (a == null) {
                        note(at, Geometry.Severity.WARNING, "rotation angle not known while reading");
                        r.m = null;
                        return r;
                    }
                    if (r.m != null) {
                        r.pre(axisRotation(name.charAt(6), a));
                    }
                    return r;
                }
                case "rotate" -> {
                    if (a != null && args.size() > 1 && deref(args.get(1)) instanceof V3 axis && r.m != null) {
                        r.pre(Xform.axisAngle(new Vec3(axis.x, axis.y, axis.z), a));
                    }
                    return r;
                }
                case "inverse" -> {
                    final Rot out = new Rot();
                    out.m = r.m == null ? null : transpose(r.m);
                    return out;
                }
                case "set" -> {
                    if (args.size() == 3 && a != null && num(args.get(1)) != null && num(args.get(2)) != null) {
                        r.m = euler(a, num(args.get(1)), num(args.get(2)));
                    } else if (args.size() == 2 && a0 instanceof V3 axis && num(args.get(1)) != null) {
                        r.m = Xform.axisAngle(new Vec3(axis.x, axis.y, axis.z), num(args.get(1)));
                    }
                    return r;
                }
                case "rotateAxes" -> {
                    if (args.size() == 3 && a0 instanceof V3 c1 && deref(args.get(1)) instanceof V3 c2
                        && deref(args.get(2)) instanceof V3 c3 && r.m != null) {
                        r.pre(new double[] {c1.x, c2.x, c3.x, c1.y, c2.y, c3.y, c1.z, c2.z, c3.z});
                    }
                    return r;
                }
                case "invert" -> {
                    r.m = r.m == null ? null : transpose(r.m);
                    return r;
                }
                case "xx", "xy", "xz", "yx", "yy", "yz", "zx", "zy", "zz" -> {
                    return r.m == null ? UNKNOWN : r.m[(name.charAt(0) - 'x') * 3 + (name.charAt(1) - 'x')];
                }
                case "isIdentity" -> {
                    return r.m != null && isIdentity(r.m);
                }
                default -> {
                    return UNKNOWN;
                }
            }
        }
        if (obj instanceof Tr t) {
            return switch (name) {
                case "getRotation" -> {
                    final Rot r = new Rot();
                    r.m = t.r.clone();
                    yield r;
                }
                case "getTranslation" -> new V3(t.x, t.y, t.z);
                case "inverse" -> {
                    final Tr out = new Tr();
                    out.r = transpose(t.r);
                    final V3 p = apply(out.r, new V3(-t.x, -t.y, -t.z));
                    out.x = p.x;
                    out.y = p.y;
                    out.z = p.z;
                    yield out;
                }
                default -> UNKNOWN;
            };
        }
        if (obj instanceof Vis v) {
            switch (name) {
                case "SetColour", "SetColor" -> {
                    if (a0 instanceof int[] c) {
                        v.color = argb(c);
                    } else if (args.size() >= 3) {
                        final int[] c = colourOf(args);
                        if (c != null) {
                            v.color = argb(c);
                        }
                    }
                }
                case "SetVisibility" -> v.visible = !Boolean.FALSE.equals(truth(a0));
                case "SetForceWireframe" -> v.wireframe = !Boolean.FALSE.equals(truth(a0));
                case "SetForceSolid" -> v.wireframe = Boolean.FALSE.equals(truth(a0));
                default -> {
                }
            }
            return null;
        }
        if (obj instanceof LogicalVolume lv) {
            switch (name) {
                case "SetVisAttributes" -> {
                    if (at instanceof Call call && !call.args.isEmpty()) {
                        visNodes.put(lv, new Node[] {call, call.args.get(0)});
                        if (statement != null) {
                            visStatementNodes.computeIfAbsent(lv, k -> new ArrayList<>()).add(new Node[] {call, statement});
                        }
                    }
                    if (a0 instanceof Vis v) {
                        if (v.color != -1) {
                            lv.setColor(v.color);
                        }
                        lv.setVisible(v.visible);
                        if (v.wireframe != null) {
                            lv.setWireframe(v.wireframe);
                        }
                    }
                    return null;
                }
                case "GetName" -> {
                    return lv.name();
                }
                case "GetSolid" -> {
                    return lv.solid();
                }
                case "GetMaterial" -> {
                    return new MatRef(lv.material());
                }
                case "SetMaterial" -> {
                    if (a0 instanceof MatRef m) {
                        lv.setMaterial(m.name());
                    }
                    return null;
                }
                case "SetSensitiveDetector" -> {
                    sensitive(lv, a0, at);
                    return null;
                }
                case "GetNoDaughters" -> {
                    return (long) lv.daughters().size();
                }
                case "GetDaughter" -> {
                    return a0 instanceof Number k && k.intValue() < lv.daughters().size()
                        ? lv.daughters().get(k.intValue()) : UNKNOWN;
                }
                default -> {
                    return UNKNOWN;
                }
            }
        }
        if (obj instanceof PhysicalVolume pv) {
            return switch (name) {
                case "GetLogicalVolume" -> pv.logical();
                case "GetName" -> pv.name();
                case "GetCopyNo" -> (long) pv.copyNumber();
                case "GetMotherLogical" -> pv.mother();
                default -> UNKNOWN;
            };
        }
        if (obj instanceof WorldPv w) {
            return switch (name) {
                case "GetLogicalVolume" -> w.lv;
                case "GetName" -> w.name;
                default -> UNKNOWN;
            };
        }
        if (obj instanceof MultiUnion mu) {
            switch (name) {
                case "AddNode" -> {
                    if (args.size() >= 2 && asSolid(args.get(0)) instanceof Solid s && deref(args.get(1)) instanceof Tr tr
                        && !tr.scaled && det(tr.r) > 0) {
                        mu.solids.add(s);
                        mu.transforms.add(tr);
                    } else {
                        note(at, Geometry.Severity.WARNING, "G4MultiUnion \"" + mu.name + "\": a node not known while"
                            + " reading is left out");
                    }
                    return null;
                }
                case "GetNumberOfSolids" -> {
                    return (long) mu.solids.size();
                }
                default -> {
                    return null;   // Voxelize() and the like: nothing to draw
                }
            }
        }
        if (obj instanceof Instance inst) {
            final Function f = resolve(inst.cls, name, args.size(), a0, 0);
            return f == null ? UNKNOWN : call(f, args, inst.fields, at);
        }
        if (obj instanceof PvStub st) {
            switch (name) {
                case "SetTranslation" -> {
                    if (a0 instanceof V3 v) {
                        st.t = v.copy();
                    }
                    return null;
                }
                case "SetRotation" -> {
                    st.frame = a0 instanceof Rot r && r.m != null ? r.m.clone() : null;
                    return null;
                }
                case "GetTranslation" -> {
                    return st.t.copy();
                }
                case "GetRotation" -> {
                    if (st.frame == null) {
                        return NULLPTR;
                    }
                    final Rot r = new Rot();
                    r.m = st.frame.clone();
                    return r;
                }
                case "GetCopyNo" -> {
                    return (long) st.copy;
                }
                default -> {
                    return UNKNOWN;
                }
            }
        }
        if (obj instanceof Solid s) {
            if (name.equals("SetAllParameters") && s.g4Class().equals("G4Torus")) {
                final List<Double> v = new ArrayList<>();
                for (Object o : args) {
                    v.add(num(o));
                }
                try {
                    set(s, v, at, "rmin", 1, 'L', "rmax", 1, 'L', "rtor", 1, 'L', "startphi", 1, 'A', "deltaphi", 1, 'A');
                } catch (MissingValue e) {
                    // noted
                }
                return null;
            }
            final Object[] acc = SOLID_ACCESSORS.get(s.g4Class() + "." + name);
            if (acc != null) {
                final String key = (String) acc[0];
                final int factor = (Integer) acc[1];
                final char kind = (Character) acc[2];
                if (name.startsWith("Set")) {
                    final Double x = num(a0);
                    if (x == null) {
                        note(at, Geometry.Severity.WARNING, s.g4Class() + "::" + name + ": value not known while reading");
                        return null;
                    }
                    s.set(key, kind == 'L' ? len(factor * x) : kind == 'A' ? ang(x) : Units.exact(x));
                    return null;
                }
                try {
                    return Expr.eval(s.param(key).expr(), Expr.NO_VARIABLES) / factor;
                } catch (GeometryException e) {
                    return UNKNOWN;
                }
            }
            return switch (name) {
                case "GetName" -> s.name();
                case "GetCubicVolume" -> s.cubicVolume();
                default -> UNKNOWN;
            };
        }
        if (obj instanceof MatRef m) {
            final Material mat = materials.containsKey(m.name()) ? materials.get(m.name()) : g.materials().material(m.name());
            switch (name) {
                case "GetName" -> {
                    return m.name();
                }
                case "GetDensity" -> {
                    return mat == null ? UNKNOWN : (Object) mat.density();
                }
                case "GetRadlen", "GetNuclearInterLength", "GetElectronDensity" -> {
                    if (mat == null) {
                        return UNKNOWN;
                    }
                    if (!mat.isNist()) {
                        try {
                            mat.resolve(g.materials());
                        } catch (GeometryException e) {
                            return new Unknown(m.name() + ": " + e.getMessage());
                        }
                    }
                    return name.equals("GetRadlen") ? mat.radiationLength() : name.equals("GetElectronDensity")
                        ? mat.electronDensity() : mat.nuclearInteractionLength();
                }
                case "AddElement", "AddMaterial" -> {
                    if (mat == null || mat.isNist() || args.size() < 2) {
                        return null;
                    }
                    final Object part = a0;
                    final Object amount = deref(args.get(1));
                    final String ref = part instanceof ElemRef e ? e.symbol() : part instanceof MatRef pm ? pm.name() : null;
                    if (ref == null || !(amount instanceof Number)) {
                        note(at, Geometry.Severity.WARNING, "component of " + m.name() + " not known while reading");
                        return null;
                    }
                    mat.addPart(ref, ((Number) amount).doubleValue(), amount instanceof Long && part instanceof ElemRef);
                    return null;
                }
                default -> {
                    return null;
                }
            }
        }
        if (obj instanceof String s) {
            return switch (name) {
                case "c_str", "data", "str" -> s;
                case "empty" -> s.isEmpty();
                case "size", "length" -> (long) s.length();
                default -> UNKNOWN;
            };
        }
        if (obj instanceof Arr a) {
            switch (name) {
                case "push_back", "emplace_back" -> {
                    if (args.size() == 1) {
                        a.items.add(copyValue(args.get(0)));
                    } else {
                        // emplace_back(x, y): the element's constructor arguments
                        final Arr item = new Arr();
                        for (Object o : args) {
                            item.items.add(copyValue(o));
                        }
                        a.items.add(item);
                    }
                    return null;
                }
                case "data" -> {
                    return a;
                }
                case "begin", "cbegin" -> {
                    return new Iter(a, 0);
                }
                case "end", "cend" -> {
                    return new Iter(a, a.items.size());
                }
                case "size" -> {
                    return (long) a.items.size();
                }
                case "empty" -> {
                    return a.items.isEmpty();
                }
                case "clear" -> {
                    a.items.clear();
                    return null;
                }
                case "at" -> {
                    return a0 instanceof Number k && k.intValue() < a.items.size() ? a.items.get(k.intValue()) : UNKNOWN;
                }
                case "back" -> {
                    return a.items.isEmpty() ? UNKNOWN : a.items.get(a.items.size() - 1);
                }
                case "front" -> {
                    return a.items.isEmpty() ? UNKNOWN : a.items.get(0);
                }
                case "resize" -> {
                    if (a0 instanceof Number k) {
                        while (a.items.size() < k.intValue() && a.items.size() < 1_000_000) {
                            a.items.add(args.size() > 1 ? copyValue(args.get(1)) : 0.0);
                        }
                    }
                    return null;
                }
                default -> {
                    return null;
                }
            }
        }
        if (obj instanceof Env fields) {
            // this->Method()
            final List<Function> fs = functions.get("::" + name);
            if (fs != null) {
                return call(fs.get(0), args, fields, at);
            }
        }
        return UNKNOWN;
    }

    /** G4 solids' setters and getters: the GDML parameter each one reaches, by a factor (2: a half-length). */
    private static final Map<String, Object[]> SOLID_ACCESSORS = new HashMap<>();

    static {
        for (String c : List.of("G4Box", "G4Para")) {
            accessor(c, "XHalfLength", "x", 2, 'L');
            accessor(c, "YHalfLength", "y", 2, 'L');
            accessor(c, "ZHalfLength", "z", 2, 'L');
        }
        accessor("G4Para", "Alpha", "alpha", 1, 'A');
        for (String c : List.of("G4Tubs", "G4CutTubs")) {
            accessor(c, "InnerRadius", "rmin", 1, 'L');
            accessor(c, "OuterRadius", "rmax", 1, 'L');
            accessor(c, "ZHalfLength", "z", 2, 'L');
            accessor(c, "StartPhiAngle", "startphi", 1, 'A');
            accessor(c, "DeltaPhiAngle", "deltaphi", 1, 'A');
        }
        accessor("G4Cons", "InnerRadiusMinusZ", "rmin1", 1, 'L');
        accessor("G4Cons", "OuterRadiusMinusZ", "rmax1", 1, 'L');
        accessor("G4Cons", "InnerRadiusPlusZ", "rmin2", 1, 'L');
        accessor("G4Cons", "OuterRadiusPlusZ", "rmax2", 1, 'L');
        accessor("G4Cons", "ZHalfLength", "z", 2, 'L');
        accessor("G4Cons", "StartPhiAngle", "startphi", 1, 'A');
        accessor("G4Cons", "DeltaPhiAngle", "deltaphi", 1, 'A');
        accessor("G4Sphere", "InnerRadius", "rmin", 1, 'L');
        accessor("G4Sphere", "OuterRadius", "rmax", 1, 'L');
        accessor("G4Sphere", "StartPhiAngle", "startphi", 1, 'A');
        accessor("G4Sphere", "DeltaPhiAngle", "deltaphi", 1, 'A');
        accessor("G4Sphere", "StartThetaAngle", "starttheta", 1, 'A');
        accessor("G4Sphere", "DeltaThetaAngle", "deltatheta", 1, 'A');
        accessor("G4Orb", "Radius", "r", 1, 'L');
        accessor("G4Trd", "XHalfLength1", "x1", 2, 'L');
        accessor("G4Trd", "XHalfLength2", "x2", 2, 'L');
        accessor("G4Trd", "YHalfLength1", "y1", 2, 'L');
        accessor("G4Trd", "YHalfLength2", "y2", 2, 'L');
        accessor("G4Trd", "ZHalfLength", "z", 2, 'L');
        accessor("G4Torus", "Rmin", "rmin", 1, 'L');
        accessor("G4Torus", "Rmax", "rmax", 1, 'L');
        accessor("G4Torus", "Rtor", "rtor", 1, 'L');
        accessor("G4EllipticalTube", "Dx", "dx", 1, 'L');
        accessor("G4EllipticalTube", "Dy", "dy", 1, 'L');
        accessor("G4EllipticalTube", "Dz", "dz", 1, 'L');
        accessor("G4Hype", "InnerRadius", "rmin", 1, 'L');
        accessor("G4Hype", "OuterRadius", "rmax", 1, 'L');
        accessor("G4Hype", "ZHalfLength", "z", 2, 'L');
        accessor("G4Hype", "InnerStereo", "inst", 1, 'A');
        accessor("G4Hype", "OuterStereo", "outst", 1, 'A');
        accessor("G4Paraboloid", "ZHalfLength", "dz", 1, 'L');
        accessor("G4Paraboloid", "RadiusMinusZ", "rlo", 1, 'L');
        accessor("G4Paraboloid", "RadiusPlusZ", "rhi", 1, 'L');
    }

    private static void accessor(String cls, String what, String key, int factor, char kind) {
        SOLID_ACCESSORS.put(cls + ".Set" + what, new Object[] {key, factor, kind});
        SOLID_ACCESSORS.put(cls + ".Get" + what, new Object[] {key, factor, kind});
    }

    private Object vectorMethod(V3 v, String name, List<Object> args) {
        final Double a = args.isEmpty() ? null : num(args.get(0));
        switch (name) {
            case "x", "getX" -> {
                return v.x;
            }
            case "y", "getY" -> {
                return v.y;
            }
            case "z", "getZ" -> {
                return v.z;
            }
            case "mag", "r", "getR" -> {
                return Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
            }
            case "mag2" -> {
                return v.x * v.x + v.y * v.y + v.z * v.z;
            }
            case "perp", "rho" -> {
                return Math.hypot(v.x, v.y);
            }
            case "phi" -> {
                return v.x == 0 && v.y == 0 ? 0.0 : Math.atan2(v.y, v.x);
            }
            case "theta" -> {
                return v.x == 0 && v.y == 0 && v.z == 0 ? 0.0 : Math.atan2(Math.hypot(v.x, v.y), v.z);
            }
            case "unit" -> {
                final double m = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
                return m == 0 ? new V3(0, 0, 0) : new V3(v.x / m, v.y / m, v.z / m);
            }
            case "dot" -> {
                return args.isEmpty() || !(deref(args.get(0)) instanceof V3 w) ? UNKNOWN : v.x * w.x + v.y * w.y + v.z * w.z;
            }
            case "cross" -> {
                if (args.isEmpty() || !(deref(args.get(0)) instanceof V3 w)) {
                    return UNKNOWN;
                }
                return new V3(v.y * w.z - v.z * w.y, v.z * w.x - v.x * w.z, v.x * w.y - v.y * w.x);
            }
            case "setX", "setY", "setZ" -> {
                if (a == null) {
                    return UNKNOWN;
                }
                switch (name.charAt(3)) {
                    case 'X' -> v.x = a;
                    case 'Y' -> v.y = a;
                    default -> v.z = a;
                }
                v.origin = null;
                return null;
            }
            case "set" -> {
                if (args.size() == 2 || args.size() == 3) {
                    final Double y = num(args.get(1)), z = args.size() == 3 ? num(args.get(2)) : Double.valueOf(v.z);
                    if (a != null && y != null && z != null) {
                        v.x = a;
                        v.y = y;
                        v.z = z;
                        v.origin = null;
                    }
                }
                return null;
            }
            case "rotateX", "rotateY", "rotateZ" -> {
                if (a == null) {
                    return UNKNOWN;
                }
                final V3 r = apply(axisRotation(name.charAt(6), a), v);
                v.x = r.x;
                v.y = r.y;
                v.z = r.z;
                v.origin = null;
                return v;
            }
            case "setMag" -> {
                final double m = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z);
                if (a != null && m > 0) {
                    v.x *= a / m;
                    v.y *= a / m;
                    v.z *= a / m;
                    v.origin = null;
                }
                return null;
            }
            default -> {
                return UNKNOWN;
            }
        }
    }

    private Object opaqueMethod(Opaque o, String name, List<Object> args, Node at) {
        final Object a0 = args.isEmpty() ? null : deref(args.get(0));
        switch (o.type) {
            case "G4NistManager" -> {
                switch (name) {
                    case "FindOrBuildMaterial", "FindMaterial", "FindSimpleMaterial" -> {
                        if (!(a0 instanceof String s)) {
                            return new Unknown("material name not known while reading");
                        }
                        if (!materials.containsKey(s) && g.materials().material(s) == null) {
                            note(at, Geometry.Severity.WARNING, "no NIST material " + s + ", nor one defined before");
                        }
                        return new MatRef(s);
                    }
                    case "FindOrBuildElement", "FindElement" -> {
                        if (a0 instanceof String s) {
                            final Element e = g.materials().nist().element(s);
                            return new ElemRef(e == null ? s : e.symbol);
                        }
                        if (a0 instanceof Number z) {
                            final Element e = g.materials().nist().element(z.intValue());
                            return e == null ? UNKNOWN : new ElemRef(e.symbol);
                        }
                        return UNKNOWN;
                    }
                    case "BuildMaterialWithNewDensity" -> {
                        if (args.size() >= 3 && a0 instanceof String newName && deref(args.get(1)) instanceof String base
                            && num(args.get(2)) != null) {
                            final Material m = Material.define(newName, num(args.get(2)) / Units.G_PER_CM3);
                            m.addPart(base, 1.0, false);
                            materials.put(newName, m);
                            return new MatRef(newName);
                        }
                        return UNKNOWN;
                    }
                    case "ConstructNewMaterial" -> {
                        return materialFromLists(args, at);
                    }
                    default -> {
                        return UNKNOWN;
                    }
                }
            }
            case "G4GDMLParser" -> {
                switch (name) {
                    case "Read", "ReadModule" -> {
                        Path p = a0 instanceof String s ? baseDir.resolve(s) : null;
                        if (p != null && !Files.isRegularFile(p)) {
                            p = findInProject(p.getFileName().toString(), p);
                        }
                        if (p == null) {
                            p = onlyGdml();
                            if (p == null) {
                                note(at, Geometry.Severity.WARNING, "the GDML file read is not known while reading "
                                    + "(given at run time): put a single .gdml in the project to see it");
                                return null;
                            }
                            note(at, Geometry.Severity.INFO, "the GDML file is given at run time: showing "
                                + baseDir.relativize(p));
                        }
                        try {
                            final Source text = sourceFor(p);
                            final List<String> warnings;
                            if (text != null) {
                                final Gdml.Result l = Gdml.read(text.text(), p.getParent());
                                gdmlWorld = l.geometry();
                                gdmlWorld.rebuild();
                                warnings = l.warnings();
                            } else {
                                final GeometryFiles.Loaded l = GeometryFiles.read(p, null);
                                gdmlWorld = l.geometry();
                                warnings = l.warnings();
                            }
                            gdmlFile = p;
                            o.gdml = p;
                            for (String w : warnings) {
                                note(at, Geometry.Severity.WARNING, w);
                            }
                        } catch (Exception e) {
                            note(at, Geometry.Severity.ERROR, "cannot read " + p.getFileName() + ": " + e.getMessage());
                        }
                        return null;
                    }
                    case "GetWorldVolume" -> {
                        return gdmlWorld == null ? UNKNOWN : new GdmlWorld(gdmlWorld);
                    }
                    case "GetVolume" -> {
                        return gdmlWorld != null && a0 instanceof String s && gdmlWorld.volume(s) != null
                            ? gdmlWorld.volume(s) : UNKNOWN;
                    }
                    default -> {
                        return UNKNOWN;
                    }
                }
            }
            case "G4ReflectionFactory" -> {
                switch (name) {
                    case "Place" -> {
                        // Place(transform, name, LV, motherLV, isMany, copyNo, check): G4PVPlacement's second order
                        return args.isEmpty() || !(a0 instanceof Tr) ? UNKNOWN : placement(args, at, true);
                    }
                    case "Replicate", "Divide" -> {
                        note(at, Geometry.Severity.WARNING, "G4ReflectionFactory::" + name + " is not drawn by the code"
                            + " reading: place the copies with G4PVReplica or G4PVDivision");
                        return UNKNOWN;
                    }
                    default -> {
                        return UNKNOWN;
                    }
                }
            }
            case "G4LogicalVolumeStore" -> {
                if (name.equals("GetVolume") && a0 instanceof String s) {
                    final LogicalVolume lv = g.volume(s);
                    return lv == null ? UNKNOWN : lv;
                }
                return UNKNOWN;
            }
            case "G4SDManager" -> {
                if (name.equals("AddNewDetector") && a0 != null && detectorNames.containsKey(a0)) {
                    detectors.put(detectorNames.get(a0), a0);
                    return null;
                }
                if (name.equals("FindSensitiveDetector") && a0 instanceof String n) {
                    final Object d = detectors.get(n);
                    return d == null ? NULLPTR : d;
                }
                return UNKNOWN;
            }
            case "stream" -> {
                return o;
            }
            default -> {
                return UNKNOWN;
            }
        }
    }

    /** A file of the project by its name (the program reads it from its build folder, where CMake copied it). */
    private Path findInProject(String fileName, Path otherwise) {
        if (baseDir == null) {
            return otherwise;
        }
        try (var s = Files.walk(baseDir, 4)) {
            return s.filter(q -> q.getFileName() != null && q.getFileName().toString().equals(fileName))
                .filter(q -> !inBuild(baseDir, q)).findFirst().orElse(otherwise);
        } catch (Exception e) {
            return otherwise;
        }
    }

    private Path onlyGdml() {
        if (baseDir == null) {
            return null;
        }
        try (var s = Files.walk(baseDir, 3)) {
            final List<Path> all = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gdml"))
                .filter(p -> !inBuild(baseDir, p)).toList();
            return all.size() == 1 ? all.get(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Object materialFromLists(List<Object> args, Node at) {
        // ConstructNewMaterial(name, elements, nbAtoms|fractions, density, isotopes, state, T, p)
        if (args.size() < 4 || !(deref(args.get(0)) instanceof String name) || !(deref(args.get(1)) instanceof Arr els)
            || !(deref(args.get(2)) instanceof Arr counts) || num(args.get(3)) == null) {
            return UNKNOWN;
        }
        final Material m = Material.define(name, num(args.get(3)) / Units.G_PER_CM3);
        for (int i = 0; i < els.items.size() && i < counts.items.size(); i++) {
            final Object c = deref(counts.items.get(i));
            if (deref(els.items.get(i)) instanceof String sym && c instanceof Number k) {
                m.addPart(sym, k.doubleValue(), c instanceof Long);
            }
        }
        materials.put(name, m);
        return new MatRef(name);
    }

    private Object field(Object obj, String name) {
        if (obj instanceof Instance inst) {
            final Object v = inst.fields.vars.get(name);
            return v == null ? new Unknown(name) : v;
        }
        if (obj instanceof V3 v) {
            return switch (name) {
                case "x" -> v.x;
                case "y" -> v.y;
                case "z" -> v.z;
                default -> UNKNOWN;
            };
        }
        if (obj instanceof Env e) {
            final Object v = e.vars.get(name);
            return v == null ? new Unknown(name) : v;
        }
        return UNKNOWN;
    }

    // ==================================================================
    // Constructions: the Geant4 classes the reader models
    // ==================================================================

    private Object construct(String type, List<Object> args, boolean heap, Node at) {
        String t = type.strip();
        if (t.startsWith("CLHEP::")) {
            t = t.substring(7);
        }
        final int lt = t.indexOf('<');
        final String base = lt < 0 ? t : t.substring(0, lt);
        final Object a0 = args.isEmpty() ? null : deref(args.get(0));
        switch (base) {
            case "G4ThreeVector", "Hep3Vector", "G4Point3D", "G4Vector3D", "G4Normal3D", "HepGeom::Point3D",
                 "HepGeom::Vector3D" -> {
                if (args.isEmpty()) {
                    final V3 v = new V3(0, 0, 0);
                    v.origin = at instanceof Call ? at : null;
                    return v;
                }
                if (args.size() == 1 && a0 instanceof V3 v) {
                    return v.copy();
                }
                final Double x = num(a0), y = args.size() > 1 ? num(args.get(1)) : Double.valueOf(0),
                    z = args.size() > 2 ? num(args.get(2)) : Double.valueOf(0);
                if (x == null || y == null || z == null) {
                    return firstUnknown(args, "a coordinate");
                }
                final V3 v = new V3(x, y, z);
                v.origin = at instanceof Call ? at : null;   // only G4ThreeVector(...) as written is rewritable
                return v;
            }
            case "G4TwoVector", "Hep2Vector" -> {
                final Double x = num(a0), y = args.size() > 1 ? num(args.get(1)) : Double.valueOf(0);
                return x == null || y == null ? UNKNOWN : new V3(x, y, 0);
            }
            case "G4RotationMatrix", "HepRotation" -> {
                final Rot r = new Rot();
                if (args.size() == 1 && a0 instanceof Rot o) {
                    r.m = o.m == null ? null : o.m.clone();
                } else if (args.size() == 3 && a0 instanceof V3 c1 && deref(args.get(1)) instanceof V3 c2
                    && deref(args.get(2)) instanceof V3 c3) {
                    r.m = new double[] {c1.x, c2.x, c3.x, c1.y, c2.y, c3.y, c1.z, c2.z, c3.z};
                } else if (args.size() == 3 && num(args.get(0)) != null && num(args.get(1)) != null
                    && num(args.get(2)) != null) {
                    r.m = euler(num(args.get(0)), num(args.get(1)), num(args.get(2)));
                } else if (args.size() == 2 && a0 instanceof V3 axis && num(args.get(1)) != null) {
                    r.m = Xform.axisAngle(new Vec3(axis.x, axis.y, axis.z), num(args.get(1)));
                } else if (!args.isEmpty() && firstUnknownOrNull(args) != null) {
                    note(at, Geometry.Severity.WARNING, "rotation angle not known while reading ("
                        + firstUnknownOrNull(args).why + ")");
                    r.m = null;
                } else if (!args.isEmpty()) {
                    note(at, Geometry.Severity.WARNING, "this G4RotationMatrix constructor is not read: use rotateX/Y/Z");
                    r.m = null;
                }
                return r;
            }
            case "HepRotationX", "HepRotationY", "HepRotationZ" -> {
                final Rot r = new Rot();
                final Double a = num(a0);
                if (a == null) {
                    r.m = null;
                } else {
                    r.m = axisRotation(base.charAt(11), a);
                }
                return r;
            }
            case "G4ExtrudedSolid::ZSection", "ZSection" -> {
                final Double z = num(a0), scale = args.size() > 2 ? num(args.get(2)) : Double.valueOf(1);
                final double[] off = args.size() > 1 ? twoVector(deref(args.get(1))) : new double[] {0, 0};
                return z == null || scale == null || off == null ? firstUnknown(args, "a z section")
                    : new ZSection(z, off[0], off[1], scale);
            }
            case "G4PVParameterised" -> {
                return parameterised(args, at);
            }
            case "G4Transform3D", "HepGeom::Transform3D" -> {
                final Tr tr = new Tr();
                if (args.size() >= 2 && a0 instanceof Rot r && deref(args.get(1)) instanceof V3 p) {
                    if (r.m == null) {
                        return new Unknown("a rotation not known");
                    }
                    tr.r = r.m.clone();
                    tr.x = p.x;
                    tr.y = p.y;
                    tr.z = p.z;
                } else if (!args.isEmpty()) {
                    return firstUnknown(args, "the transformation");
                }
                return tr;
            }
            case "G4Translate3D", "G4TranslateX3D", "G4TranslateY3D", "G4TranslateZ3D" -> {
                final Tr tr = new Tr();
                if (a0 instanceof V3 p) {
                    tr.x = p.x;
                    tr.y = p.y;
                    tr.z = p.z;
                    return tr;
                }
                final Double x = num(a0);
                if (x == null) {
                    return firstUnknown(args, "the translation");
                }
                switch (base) {
                    case "G4TranslateX3D" -> tr.x = x;
                    case "G4TranslateY3D" -> tr.y = x;
                    case "G4TranslateZ3D" -> tr.z = x;
                    default -> {
                        final Double y = args.size() > 1 ? num(args.get(1)) : null, z = args.size() > 2 ? num(args.get(2)) : null;
                        if (y == null || z == null) {
                            return firstUnknown(args, "the translation");
                        }
                        tr.x = x;
                        tr.y = y;
                        tr.z = z;
                    }
                }
                return tr;
            }
            case "G4RotateX3D", "G4RotateY3D", "G4RotateZ3D", "G4Rotate3D" -> {
                final Tr tr = new Tr();
                if (a0 instanceof Rot r && r.m != null) {
                    tr.r = r.m.clone();
                    return tr;
                }
                final Double a = num(a0);
                if (a == null) {
                    return firstUnknown(args, "the rotation angle");
                }
                if (base.equals("G4Rotate3D")) {
                    if (args.size() > 1 && deref(args.get(1)) instanceof V3 axis) {
                        tr.r = Xform.axisAngle(new Vec3(axis.x, axis.y, axis.z), a);
                    }
                } else {
                    tr.r = axisRotation(base.charAt(8), a);
                }
                return tr;
            }
            case "G4ReflectX3D", "G4ReflectY3D", "G4ReflectZ3D" -> {
                // the mirror through the plane x (y, z) = d: d defaults to 0
                final Tr tr = new Tr();
                tr.reflected = true;
                final int k = base.charAt(9) - 'X';
                tr.r[k * 4] = -1;
                final Double d = args.isEmpty() ? Double.valueOf(0) : num(a0);
                if (d == null) {
                    return firstUnknown(args, "the mirror plane");
                }
                switch (k) {
                    case 0 -> tr.x = 2 * d;
                    case 1 -> tr.y = 2 * d;
                    default -> tr.z = 2 * d;
                }
                return tr;
            }
            case "G4Scale3D", "G4ScaleX3D", "G4ScaleY3D", "G4ScaleZ3D" -> {
                final double[] f = {1, 1, 1};
                if (base.equals("G4Scale3D")) {
                    for (int i = 0; i < 3; i++) {
                        final Double v = num(args.size() == 1 ? a0 : i < args.size() ? args.get(i) : null);
                        if (v == null) {
                            return firstUnknown(args, "a scale factor");
                        }
                        f[i] = v;
                    }
                } else {
                    final Double v = num(a0);
                    if (v == null) {
                        return firstUnknown(args, "the scale factor");
                    }
                    f[base.charAt(7) - 'X'] = v;
                }
                final Tr tr = new Tr();
                tr.r = new double[] {f[0], 0, 0, 0, f[1], 0, 0, 0, f[2]};
                tr.reflected = f[0] * f[1] * f[2] < 0;
                tr.scaled = Math.abs(Math.abs(f[0]) - 1) > 1e-12 || Math.abs(Math.abs(f[1]) - 1) > 1e-12
                    || Math.abs(Math.abs(f[2]) - 1) > 1e-12;
                return tr;
            }
            case "G4Colour", "G4Color" -> {
                final int[] c = colourOf(args);
                return c == null ? new int[] {154, 167, 184, 255} : c;
            }
            case "G4VisAttributes" -> {
                final Vis v = new Vis();
                for (Object a : args) {
                    final Object x = deref(a);
                    if (x instanceof int[] c) {
                        v.color = argb(c);
                    } else if (x instanceof Boolean b) {
                        v.visible = b;
                    }
                }
                return v;
            }
            case "G4GDMLParser" -> {
                return new Opaque("G4GDMLParser");
            }
            case "G4String", "std::string", "string" -> {
                return a0 == null ? "" : a0 instanceof String s ? s : UNKNOWN;
            }
            case "G4Material" -> {
                return material(args, at);
            }
            case "G4Element" -> {
                // G4Element(name, symbol, z, a): the element of that Z; (name, symbol, nIsotopes): by its symbol,
                // a mass number written after it ("N14", "H1") taken off
                if (args.size() >= 4 && num(args.get(2)) != null) {
                    final Element e = g.materials().nist().element((int) Math.round(num(args.get(2))));
                    if (e != null) {
                        return new ElemRef(e.symbol);
                    }
                }
                if (args.size() >= 2 && deref(args.get(1)) instanceof String sym) {
                    final String bare = sym.replaceAll("[^A-Za-z]", "");
                    return new ElemRef(g.materials().nist().element(sym) == null && !bare.isEmpty()
                        && g.materials().nist().element(bare) != null ? bare : sym);
                }
                return UNKNOWN;
            }
            case "G4LogicalVolume" -> {
                return logical(args, at);
            }
            case "G4PVPlacement" -> {
                return placement(args, at, false);
            }
            case "G4PVDivision" -> {
                return division(args, at);
            }
            case "G4PVReplica" -> {
                return replica(args, at);
            }
            case "G4UnionSolid", "G4SubtractionSolid", "G4IntersectionSolid" -> {
                return booleanSolid(base, args, at);
            }
            case "new[]" -> {
                final Arr a = new Arr();
                if (a0 instanceof Long n && n >= 0 && n < 1_000_000) {
                    for (int i = 0; i < n; i++) {
                        a.items.add(NULLPTR);
                    }
                }
                return a;
            }
            case "std::vector", "vector", "std::array", "array" -> {
                final Arr a = new Arr();
                if (args.size() == 2 && a0 instanceof Long n && n >= 0 && n < 1_000_000) {
                    for (int i = 0; i < n; i++) {
                        a.items.add(copyValue(deref(args.get(1))));
                    }
                } else if (args.size() == 1 && a0 instanceof Long n && n >= 0 && n < 1_000_000) {
                    for (int i = 0; i < n; i++) {
                        a.items.add(0.0);
                    }
                } else if (args.size() == 1 && a0 instanceof Arr init) {
                    a.items.addAll(init.items);
                } else {
                    a.items.addAll(args);
                }
                return a;
            }
            default -> {
                break;
            }
        }
        if (INT_TYPES.contains(base) || REAL_TYPES.contains(base) || base.equals("bool") || base.equals("G4bool")) {
            return args.isEmpty() ? convert(base, false, 0L) : convert(base, false, a0);
        }
        final Solids.Type st = base.startsWith("G4") ? Solids.byClass(base) : null;
        if (st != null) {
            return solid(st, args, at);
        }
        switch (base) {
            case "G4MultiUnion" -> {
                return new MultiUnion(a0 instanceof String n ? n : "MultiUnion", at);
            }
            case "G4ScaledSolid", "G4ReflectedSolid" -> {
                return scaledSolid(base, args, at);
            }
            case "G4DisplacedSolid" -> {
                return displacedSolid(args, at);
            }
            default -> {
                break;
            }
        }
        if (base.startsWith("G4") && base.endsWith("Solid") || base.equals("G4TwistedTubs") || base.equals("G4TwistedBox")) {
            note(at, Geometry.Severity.WARNING, base + " is not drawn by the code reading");
            return new Unknown(base + ", not modelled");
        }
        final Object made = isUserClass(base) && globalEnv != null ? instantiate(base, args, at) : new Opaque(base);
        if (a0 instanceof String n && (base.equals("G4MultiFunctionalDetector") || derives(base, "G4VSensitiveDetector", 0)
            || derives(base, "G4MultiFunctionalDetector", 0) || base.endsWith("SD") || base.endsWith("SensitiveDetector"))) {
            detectorNames.put(made, n);   // a sensitive detector, known by its name
        }
        return made;
    }

    /**
     * A volume made sensitive: by the volume, or by its name (every volume of that name, as Geant4 does with
     * multi; the reading renames a name made twice name_1, name_2...). The detector is known by its name.
     */
    private void sensitive(Object target, Object sd, Node at) {
        String name = sd == null ? null : detectorNames.get(sd);
        if (name == null) {
            name = sd instanceof Instance i ? i.cls : sd instanceof Opaque o ? o.type : "SD";
        }
        final List<LogicalVolume> lvs = new ArrayList<>();
        if (target instanceof LogicalVolume lv) {
            lvs.add(lv);
        } else if (target instanceof String s) {
            for (LogicalVolume lv : g.volumes()) {
                final String n = lv.name();
                if (n.equals(s) || n.length() > s.length() + 1 && n.startsWith(s + "_")
                    && n.substring(s.length() + 1).chars().allMatch(Character::isDigit)) {
                    lvs.add(lv);
                }
            }
        }
        for (LogicalVolume lv : lvs) {
            lv.setSensitive(name);
            if (at != null && statement != null) {
                sensitiveNodes.computeIfAbsent(lv, k -> new ArrayList<>()).add(new Node[] {at, statement});
            }
        }
    }

    private static Unknown firstUnknownOrNull(List<Object> args) {
        for (Object a : args) {
            if (deref(a) instanceof Unknown u) {
                return u;
            }
        }
        return null;
    }

    /** CLHEP's HepRotation(phi, theta, psi): Euler angles as HepRotation::set writes them, element by element. */
    static double[] euler(double phi, double theta, double psi) {
        final double sinPhi = Math.sin(phi), cosPhi = Math.cos(phi);
        final double sinTheta = Math.sin(theta), cosTheta = Math.cos(theta);
        final double sinPsi = Math.sin(psi), cosPsi = Math.cos(psi);
        return new double[] {
            cosPsi * cosPhi - cosTheta * sinPhi * sinPsi,
            cosPsi * sinPhi + cosTheta * cosPhi * sinPsi,
            sinPsi * sinTheta,
            -sinPsi * cosPhi - cosTheta * sinPhi * cosPsi,
            -sinPsi * sinPhi + cosTheta * cosPhi * cosPsi,
            cosPsi * sinTheta,
            sinTheta * sinPhi,
            -sinTheta * cosPhi,
            cosTheta};
    }

    private static Unknown firstUnknown(List<Object> args, String what) {
        for (Object a : args) {
            if (deref(a) instanceof Unknown u) {
                return u;
            }
        }
        return new Unknown(what + " not known");
    }

    private static int[] colourOf(List<Object> args) {
        if (args.size() < 3) {
            return null;
        }
        final Double r = num(args.get(0)), gr = num(args.get(1)), b = num(args.get(2));
        final Double a = args.size() > 3 ? num(args.get(3)) : Double.valueOf(1);
        if (r == null || gr == null || b == null || a == null) {
            return null;
        }
        return new int[] {c255(r), c255(gr), c255(b), c255(a)};
    }

    private static int c255(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    private static int argb(int[] c) {
        return (c[3] << 24) | (c[0] << 16) | (c[1] << 8) | c[2];
    }

    static double[] axisRotation(char axis, double a) {
        final double c = Math.cos(a), s = Math.sin(a);
        return switch (axis) {
            case 'X', 'x' -> new double[] {1, 0, 0, 0, c, -s, 0, s, c};
            case 'Y', 'y' -> new double[] {c, 0, s, 0, 1, 0, -s, 0, c};
            default -> new double[] {c, -s, 0, s, c, 0, 0, 0, 1};
        };
    }

    private static double[] transpose(double[] m) {
        return new double[] {m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]};
    }

    private static String len(double mm) {
        return fmt(mm) + "*mm";
    }

    private static String ang(double rad) {
        return fmt(rad / Units.DEG) + "*deg";
    }

    /** 14 significant digits: 30*deg rather than 29.999999999999996*deg, nothing a navigator could tell apart. */
    public static String fmt(double v) {
        if (v == 0 || !Double.isFinite(v)) {
            return v == 0 ? "0" : Units.exact(v);
        }
        final java.math.BigDecimal b = new java.math.BigDecimal(v).round(new java.math.MathContext(14)).stripTrailingZeros();
        return Math.abs(b.scale()) > 12 ? b.toString() : b.toPlainString();
    }

    private String uniqueSolid(String name) {
        String n = name == null || name.isBlank() ? "solid" : name;
        if (g.solid(n) == null) {
            return n;
        }
        for (int i = 1; ; i++) {
            if (g.solid(n + "_" + i) == null) {
                return n + "_" + i;
            }
        }
    }

    private String uniqueVolume(String name) {
        String n = name == null || name.isBlank() ? "volume" : name;
        if (g.volume(n) == null) {
            return n;
        }
        for (int i = 1; ; i++) {
            if (g.volume(n + "_" + i) == null) {
                return n + "_" + i;
            }
        }
    }

    /** The solids the editor models: the C++ constructor's arguments mapped on GDML's parameters. */
    private Object solid(Solids.Type type, List<Object> args, Node at) {
        if (args.isEmpty() || !(deref(args.get(0)) instanceof String name)) {
            if (!args.isEmpty() && deref(args.get(0)) instanceof Unknown u) {
                return u;
            }
            return new Unknown(type.g4Class() + ": name not known");
        }
        final List<Double> v = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            v.add(num(args.get(i)));
        }
        int arrays = 0;
        for (Object o : args) {
            if (deref(o) instanceof Arr) {
                arrays++;
            }
        }
        // G4Polycone given (r, z) corners is G4GenericPolycone's shape
        final Solids.Type made = arrays != 2 ? type : type.g4Class().equals("G4Polycone") ? Solids.type("genericPolycone")
            : type.g4Class().equals("G4Polyhedra") ? Solids.type("genericPolyhedra") : type;
        final Solid s = Solids.create(made.tag(), uniqueSolid(name));
        final boolean direct = at instanceof Construct c && c.heap && c.args.size() == args.size();
        solidArgs = direct ? ((Construct) at).args : null;
        solidValues = direct ? v : null;
        solidValuesArgs = direct ? args : null;
        try {
            switch (type.g4Class()) {
                case "G4Box" -> set(s, v, at, "x", 2, 'L', "y", 2, 'L', "z", 2, 'L');
                case "G4Tubs" -> set(s, v, at, "rmin", 1, 'L', "rmax", 1, 'L', "z", 2, 'L', "startphi", 1, 'A',
                    "deltaphi", 1, 'A');
                case "G4Cons" -> set(s, v, at, "rmin1", 1, 'L', "rmax1", 1, 'L', "rmin2", 1, 'L', "rmax2", 1, 'L',
                    "z", 2, 'L', "startphi", 1, 'A', "deltaphi", 1, 'A');
                case "G4Sphere" -> set(s, v, at, "rmin", 1, 'L', "rmax", 1, 'L', "startphi", 1, 'A', "deltaphi", 1, 'A',
                    "starttheta", 1, 'A', "deltatheta", 1, 'A');
                case "G4Orb" -> set(s, v, at, "r", 1, 'L');
                case "G4Torus" -> set(s, v, at, "rmin", 1, 'L', "rmax", 1, 'L', "rtor", 1, 'L', "startphi", 1, 'A',
                    "deltaphi", 1, 'A');
                case "G4Trd" -> set(s, v, at, "x1", 2, 'L', "x2", 2, 'L', "y1", 2, 'L', "y2", 2, 'L', "z", 2, 'L');
                case "G4Para" -> set(s, v, at, "x", 2, 'L', "y", 2, 'L', "z", 2, 'L', "alpha", 1, 'A', "theta", 1, 'A',
                    "phi", 1, 'A');
                case "G4Trap" -> {
                    final List<Double> eleven = trapParameters(args, v, at);
                    if (eleven == null) {
                        return new Unknown("G4Trap");
                    }
                    set(s, eleven, at, "z", 2, 'L', "theta", 1, 'A', "phi", 1, 'A', "y1", 2, 'L', "x1", 2, 'L', "x2", 2, 'L',
                        "alpha1", 1, 'A', "y2", 2, 'L', "x3", 2, 'L', "x4", 2, 'L', "alpha2", 1, 'A');
                }
                case "G4CutTubs" -> {
                    set(s, v.subList(0, Math.min(5, v.size())), at, "rmin", 1, 'L', "rmax", 1, 'L', "z", 2, 'L',
                        "startphi", 1, 'A', "deltaphi", 1, 'A');
                    if (args.size() >= 8 && deref(args.get(6)) instanceof V3 lo && deref(args.get(7)) instanceof V3 hi) {
                        s.set("lowX", Units.exact(lo.x));
                        s.set("lowY", Units.exact(lo.y));
                        s.set("lowZ", Units.exact(lo.z));
                        s.set("highX", Units.exact(hi.x));
                        s.set("highY", Units.exact(hi.y));
                        s.set("highZ", Units.exact(hi.z));
                    }
                }
                case "G4EllipticalTube" -> set(s, v, at, "dx", 1, 'L', "dy", 1, 'L', "dz", 1, 'L');
                case "G4Ellipsoid" -> set(s, v, at, "ax", 1, 'L', "by", 1, 'L', "cz", 1, 'L', "zcut1", 1, 'L',
                    "zcut2", 1, 'L');
                case "G4EllipticalCone" -> set(s, v, at, "dx", 1, 'N', "dy", 1, 'N', "zmax", 1, 'L', "zcut", 1, 'L');
                case "G4Paraboloid" -> set(s, v, at, "dz", 1, 'L', "rlo", 1, 'L', "rhi", 1, 'L');
                case "G4Hype" -> set(s, v, at, "rmin", 1, 'L', "rmax", 1, 'L', "inst", 1, 'A', "outst", 1, 'A',
                    "z", 2, 'L');
                case "G4Tet" -> {
                    for (int k = 0; k < 4; k++) {
                        if (!(k + 1 < args.size() && deref(args.get(k + 1)) instanceof V3 p)) {
                            return firstUnknown(args, "a vertex");
                        }
                        s.set("v" + (k + 1) + "x", len(p.x));
                        s.set("v" + (k + 1) + "y", len(p.y));
                        s.set("v" + (k + 1) + "z", len(p.z));
                    }
                }
                case "G4Polycone", "G4Polyhedra", "G4GenericPolycone" -> {
                    if (!planes(s, type.g4Class().equals("G4Polyhedra"), args, at)) {
                        return new Unknown(type.g4Class() + " planes");
                    }
                }
                case "G4ExtrudedSolid" -> {
                    if (!extrusion(s, args, at)) {
                        return new Unknown("G4ExtrudedSolid");
                    }
                }
                default -> {
                    note(at, Geometry.Severity.WARNING, type.g4Class() + " is not read from C++ yet: draw it in G4editor");
                    return new Unknown(type.g4Class());
                }
            }
        } catch (MissingValue e) {
            return new Unknown(e.getMessage());
        }
        g.addSolid(s);
        origin(s, at);
        if (at instanceof Construct c && c.heap) {
            solidNodes.put(s, new Object[] {at, name});   // new G4Box(...): rewritten whole when its parameters change
        }
        made(s, at);
        return s;
    }

    /**
     * G4Trap's constructors as G4Trap.cc reads them, each brought to (pDz, pTheta, pPhi, pDy1, pDx1, pDx2,
     * pAlp1, pDy2, pDx3, pDx4, pAlp2): the eleven themselves, eight corners, a right-angular wedge (pZ, pY,
     * pX, pLTX), a trd-like one (pDx1, pDx2, pDy1, pDy2, pDz) and a para-like one (pDx, pDy, pDz, pAlpha,
     * pTheta, pPhi). Null when a value is not known.
     */
    private List<Double> trapParameters(List<Object> args, List<Double> v, Node at) {
        if (v.size() == 1 && deref(args.get(1)) instanceof Arr corners && corners.items.size() == 8) {
            final V3[] pt = new V3[8];
            for (int k = 0; k < 8; k++) {
                if (!(deref(corners.items.get(k)) instanceof V3 c)) {
                    note(at, Geometry.Severity.WARNING, "G4Trap: corner " + k + " not known while reading");
                    return null;
                }
                pt[k] = c;
            }
            final double dz = pt[7].z, dy1 = (pt[2].y - pt[1].y) / 2, dx1 = (pt[1].x - pt[0].x) / 2;
            final double dx2 = (pt[3].x - pt[2].x) / 2, ta1 = (pt[2].x + pt[3].x - pt[1].x - pt[0].x) * 0.25 / dy1;
            final double dy2 = (pt[6].y - pt[5].y) / 2, dx3 = (pt[5].x - pt[4].x) / 2, dx4 = (pt[7].x - pt[6].x) / 2;
            final double ta2 = (pt[6].x + pt[7].x - pt[5].x - pt[4].x) * 0.25 / dy2;
            final double tc = (pt[4].x + dy2 * ta2 + dx3) / dz, ts = (pt[4].y + dy2) / dz;
            return List.of(dz, Math.atan(Math.hypot(tc, ts)), Math.atan2(ts, tc), dy1, dx1, dx2, Math.atan(ta1), dy2, dx3,
                dx4, Math.atan(ta2));
        }
        for (Double d : v) {
            if (d == null) {
                note(at, Geometry.Severity.WARNING, "G4Trap: a parameter not known while reading, so it is not drawn");
                return null;
            }
        }
        return switch (v.size()) {
            case 11 -> v;
            case 4 -> {
                final double pz = v.get(0), py = v.get(1), px = v.get(2), ltx = v.get(3);
                final double a = Math.atan(0.5 * (ltx - px) / py);
                yield List.of(pz / 2, 0.0, 0.0, py / 2, px / 2, ltx / 2, a, py / 2, px / 2, ltx / 2, a);
            }
            case 5 -> List.of(v.get(4), 0.0, 0.0, v.get(2), v.get(0), v.get(0), 0.0, v.get(3), v.get(1), v.get(1), 0.0);
            case 6 -> List.of(v.get(2), v.get(4), v.get(5), v.get(1), v.get(0), v.get(0), v.get(3), v.get(1), v.get(0),
                v.get(0), v.get(3));
            default -> {
                note(at, Geometry.Severity.WARNING, "G4Trap with " + v.size() + " parameters: no such constructor");
                yield null;
            }
        };
    }

    private static final class MissingValue extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MissingValue(String m) {
            super(m, null, false, false);
        }
    }

    /** Sets parameters from values: key, factor (2 for a half-length), kind (L length, A angle, N number). */
    private void set(Solid s, List<Double> v, Node at, Object... spec) {
        for (int k = 0, i = 0; k < spec.length; k += 3, i++) {
            final String key = (String) spec[k];
            final int factor = (Integer) spec[k + 1];
            final char kind = (Character) spec[k + 2];
            if (i >= v.size()) {
                break;   // defaulted arguments keep the solid's defaults
            }
            final Double x = v.get(i);
            if (x == null) {
                note(at, Geometry.Severity.WARNING, s.g4Class() + " \"" + s.name() + "\": " + key
                    + " not known while reading, so it is not drawn");
                throw new MissingValue(key);
            }
            s.set(key, kind == 'L' ? len(factor * x) : kind == 'A' ? ang(x) : Units.exact(x));
            if (v == solidValues && solidArgs != null && i + 1 < solidArgs.size()) {
                // the parameter is this argument, as written: a change made to it is written there
                paramNodes.computeIfAbsent(s, k2 -> new LinkedHashMap<>()).put(key,
                    new Object[] {solidArgs.get(i + 1), factor, kind});
            }
        }
    }

    /** G4Polycone(name, phi0, dphi, n, z[], rmin[], rmax[]) or (name, phi0, dphi, n, r[], z[]); G4Polyhedra with sides. */
    private boolean planes(Solid s, boolean polyhedra, List<Object> args, Node at) {
        int k = 1;
        final Double phi0 = num(args.get(k++)), dphi = num(args.get(k++));
        Double sides = null;
        if (polyhedra) {
            sides = args.size() > k ? num(args.get(k++)) : null;
        }
        final Double n = args.size() > k ? num(args.get(k)) : null;
        k++;
        if (phi0 == null || dphi == null || n == null || (polyhedra && sides == null)) {
            note(at, Geometry.Severity.WARNING, s.g4Class() + " \"" + s.name() + "\": a parameter not known while reading");
            return false;
        }
        s.set("startphi", ang(phi0));
        s.set("deltaphi", ang(dphi));
        if (polyhedra) {
            s.set("numsides", String.valueOf(sides.intValue()));
        }
        final List<Node> argNodes = args == solidValuesArgs ? solidArgs : null;
        if (argNodes != null) {
            final Map<String, Object[]> one = paramNodes.computeIfAbsent(s, k2 -> new LinkedHashMap<>());
            one.put("startphi", new Object[] {argNodes.get(1), 1, 'A'});
            one.put("deltaphi", new Object[] {argNodes.get(2), 1, 'A'});
            if (polyhedra) {
                one.put("numsides", new Object[] {argNodes.get(3), 1, 'N'});
            }
        }
        final int countArg = k - 1;
        final List<Arr> arrays = new ArrayList<>();
        for (int i = k; i < args.size(); i++) {
            if (deref(args.get(i)) instanceof Arr a) {
                arrays.add(a);
            }
        }
        final int count = n.intValue();
        if (arrays.size() == 3) {
            final Solid.Table t = s.table("zplane");
            t.clear();
            for (int i = 0; i < count; i++) {
                final Double z = item(arrays.get(0), i), rmin = item(arrays.get(1), i), rmax = item(arrays.get(2), i);
                if (z == null || rmin == null || rmax == null) {
                    note(at, Geometry.Severity.WARNING, s.g4Class() + " \"" + s.name() + "\": plane " + i + " not known");
                    return false;
                }
                t.add(len(rmin), len(rmax), len(z));
            }
            tableRead(s, "zplane", arrays, new int[] {2, 0, 1}, argNodes, countArg, count, at);
            return true;
        }
        final Solid.Table rz = s.table("rzpoint");
        if (arrays.size() == 2 && rz != null) {
            rz.clear();
            for (int i = 0; i < count; i++) {
                final Double r = item(arrays.get(0), i), z = item(arrays.get(1), i);
                if (r == null || z == null) {
                    note(at, Geometry.Severity.WARNING, s.g4Class() + " \"" + s.name() + "\": corner " + i + " not known");
                    return false;
                }
                rz.add(len(r), len(z));
            }
            tableRead(s, "rzpoint", arrays, new int[] {0, 1}, argNodes, countArg, count, at);
            return true;
        }
        note(at, Geometry.Severity.WARNING, s.g4Class() + " \"" + s.name() + "\": "
            + (arrays.size() == 2 ? "the (r, z) corner form of G4Polyhedra is not drawn" : "its planes are not known"));
        return false;
    }

    /** A table read from arrays each written {...} with as many items as rows: written back there when it changes. */
    private void tableRead(Solid s, String table, List<Arr> arrays, int[] columnOf, List<Node> argNodes, int countArg,
                           int count, Node at) {
        if (argNodes == null) {
            return;
        }
        final List<Node> braces = new ArrayList<>();
        for (Arr a : arrays) {
            braces.add(a.origin instanceof Braces b && b.items.size() == count && a.items.size() == count ? b : null);
        }
        tableNodes.put(s, new Object[] {table, braces, columnOf, countArg < argNodes.size() ? argNodes.get(countArg) : null,
            at});
    }

    /** G4ExtrudedSolid(name, polygon, halfZ, off1, scale1, off2, scale2) or (name, polygon, zsections). */
    private boolean extrusion(Solid s, List<Object> args, Node at) {
        if (args.size() < 3 || !(deref(args.get(1)) instanceof Arr polygon)) {
            note(at, Geometry.Severity.WARNING, "G4ExtrudedSolid \"" + s.name() + "\": its polygon is not known while reading");
            return false;
        }
        final List<String[]> vertices = new ArrayList<>();
        for (Object o : polygon.items) {
            final double[] xy = twoVector(deref(o));
            if (xy == null) {
                note(at, Geometry.Severity.WARNING, "G4ExtrudedSolid \"" + s.name() + "\": a vertex is not known while reading");
                return false;
            }
            vertices.add(new String[] {len(xy[0]), len(xy[1])});
        }
        final List<String[]> sections = new ArrayList<>();
        final Object third = deref(args.get(2));
        if (third instanceof Arr zs) {
            for (Object o : zs.items) {
                final double[] z = zSection(deref(o));
                if (z == null) {
                    note(at, Geometry.Severity.WARNING, "G4ExtrudedSolid \"" + s.name() + "\": a z section is not known");
                    return false;
                }
                sections.add(new String[] {len(z[0]), len(z[1]), len(z[2]), fmt(z[3])});
            }
        } else {
            final Double hz = num(third);
            final double[] o1 = args.size() > 3 ? twoVector(deref(args.get(3))) : new double[] {0, 0};
            final Double s1 = args.size() > 4 ? num(args.get(4)) : Double.valueOf(1);
            final double[] o2 = args.size() > 5 ? twoVector(deref(args.get(5))) : new double[] {0, 0};
            final Double s2 = args.size() > 6 ? num(args.get(6)) : Double.valueOf(1);
            if (hz == null || o1 == null || s1 == null || o2 == null || s2 == null) {
                note(at, Geometry.Severity.WARNING, "G4ExtrudedSolid \"" + s.name() + "\": a parameter is not known while reading");
                return false;
            }
            sections.add(new String[] {len(-hz), len(o1[0]), len(o1[1]), fmt(s1)});
            sections.add(new String[] {len(hz), len(o2[0]), len(o2[1]), fmt(s2)});
        }
        if (vertices.size() < 3 || sections.size() < 2) {
            note(at, Geometry.Severity.WARNING, "G4ExtrudedSolid \"" + s.name() + "\": needs 3 vertices and 2 sections");
            return false;
        }
        final Solid.Table v = s.table("twoDimVertex"), sec = s.table("section");
        v.clear();
        for (String[] r : vertices) {
            v.add(r);
        }
        sec.clear();
        for (String[] r : sections) {
            sec.add(r);
        }
        return true;
    }

    /** G4TwoVector(x, y), or {x, y}. */
    private static double[] twoVector(Object o) {
        if (o instanceof V3 v) {
            return new double[] {v.x, v.y};
        }
        if (o instanceof Arr a && a.items.size() >= 2) {
            final Double x = num(a.items.get(0)), y = num(a.items.get(1));
            return x == null || y == null ? null : new double[] {x, y};
        }
        return null;
    }

    /** ZSection(z, offset, scale), or {z, {x, y}, scale}. */
    private static double[] zSection(Object o) {
        if (o instanceof ZSection z) {
            return new double[] {z.z(), z.ox(), z.oy(), z.scale()};
        }
        if (o instanceof Arr a && a.items.size() >= 3) {
            final Double z = num(a.items.get(0)), scale = num(a.items.get(2));
            final double[] off = twoVector(deref(a.items.get(1)));
            return z == null || scale == null || off == null ? null : new double[] {z, off[0], off[1], scale};
        }
        return null;
    }

    private static Double item(Arr a, int i) {
        return i < a.items.size() ? num(a.items.get(i)) : null;
    }

    /** A G4MultiUnion becomes a solid where a volume or a boolean takes it; others pass as they are. */
    private Object asSolid(Object o) {
        o = deref(o);
        return o instanceof MultiUnion mu ? multiUnion(mu) : o;
    }

    /**
     * G4MultiUnion: its nodes, each moved by its G4Transform3D, joined as nested G4UnionSolids (the first
     * union moves its first operand too, as GDML's firstposition does), which draw and weigh the same.
     */
    private Object multiUnion(MultiUnion mu) {
        if (mu.made != null) {
            return mu.made;
        }
        if (mu.solids.isEmpty()) {
            note(mu.at, Geometry.Severity.WARNING, "G4MultiUnion \"" + mu.name + "\" has no node");
            return new Unknown("G4MultiUnion " + mu.name);
        }
        Solid acc = mu.solids.get(0);
        final Tr first = mu.transforms.get(0);
        final int n = mu.solids.size();
        for (int k = n == 1 ? 0 : 1; k < n; k++) {
            final String name = k == n - 1 ? uniqueSolid(mu.name) : uniqueSolid(mu.name + "_" + k);
            final BooleanSolid u = new BooleanSolid(name, BooleanSolid.Op.ofTag("union"), acc, mu.solids.get(k));
            if (acc == mu.solids.get(0)) {
                move(u, "f", first);
            }
            move(u, "", mu.transforms.get(k));
            g.addSolid(u);
            origin(u, mu.at);
            acc = u;
        }
        mu.made = acc;
        return acc;
    }

    /** A boolean's operand moved by a transform: its position and GDML angles ("f" for the first operand). */
    private static void move(BooleanSolid s, String prefix, Tr tr) {
        final double[] angles = Xform.gdmlAngles(transpose(tr.r));
        s.set(prefix + "x", len(tr.x));
        s.set(prefix + "y", len(tr.y));
        s.set(prefix + "z", len(tr.z));
        s.set(prefix.isEmpty() ? "rx" : "frx", ang(angles[0]));
        s.set(prefix.isEmpty() ? "ry" : "fry", ang(angles[1]));
        s.set(prefix.isEmpty() ? "rz" : "frz", ang(angles[2]));
    }

    /** G4DisplacedSolid(name, solid, rot, pos) or (name, solid, transform): the solid joined with itself, moved. */
    private Object displacedSolid(List<Object> args, Node at) {
        if (args.size() < 3 || !(deref(args.get(0)) instanceof String name) || !(asSolid(args.get(1)) instanceof Solid s)) {
            return firstUnknown(args, "G4DisplacedSolid");
        }
        Tr tr = deref(args.get(2)) instanceof Tr t ? t : null;
        if (tr == null && args.size() >= 4 && deref(args.get(3)) instanceof V3 p) {
            tr = new Tr();
            final Object r = deref(args.get(2));
            if (r instanceof Rot rot && rot.m != null) {
                tr.r = transpose(rot.m);   // the frame rotation G4DisplacedSolid takes, as G4PVPlacement does
            }
            tr.x = p.x;
            tr.y = p.y;
            tr.z = p.z;
        }
        if (tr == null || tr.scaled || det(tr.r) < 0) {
            note(at, Geometry.Severity.WARNING, "G4DisplacedSolid \"" + name + "\": its displacement is not known while reading");
            return new Unknown("G4DisplacedSolid");
        }
        final MultiUnion one = new MultiUnion(name, at);
        one.solids.add(s);
        one.transforms.add(tr);
        return multiUnion(one);
    }

    /**
     * G4ScaledSolid(name, solid, G4Scale3D) and G4ReflectedSolid(name, solid, transform) when the transform is a
     * mirror alone: GDML's scaledSolid, its factors' signs the mirror.
     */
    private Object scaledSolid(String cls, List<Object> args, Node at) {
        if (args.size() < 3 || !(deref(args.get(0)) instanceof String name) || !(asSolid(args.get(1)) instanceof Solid s)) {
            return firstUnknown(args, cls);
        }
        if (!(deref(args.get(2)) instanceof Tr tr) || tr.r[1] != 0 || tr.r[2] != 0 || tr.r[3] != 0 || tr.r[5] != 0
            || tr.r[6] != 0 || tr.r[7] != 0 || Math.abs(tr.x) + Math.abs(tr.y) + Math.abs(tr.z) > 1e-12) {
            note(at, Geometry.Severity.WARNING, cls + " \"" + name + "\": only a scale or a mirror alone is drawn"
                + " (a turned or moved one is not)");
            return new Unknown(cls);
        }
        final ScaledSolid sc = new ScaledSolid(uniqueSolid(name), s);
        sc.set("sx", fmt(tr.r[0]));
        sc.set("sy", fmt(tr.r[4]));
        sc.set("sz", fmt(tr.r[8]));
        g.addSolid(sc);
        origin(sc, at);
        return sc;
    }

    private Object booleanSolid(String cls, List<Object> args, Node at) {
        if (args.size() < 3 || !(deref(args.get(0)) instanceof String name)) {
            return firstUnknown(args, cls);
        }
        if (!(asSolid(args.get(1)) instanceof Solid a) || !(asSolid(args.get(2)) instanceof Solid b)) {
            note(at, Geometry.Severity.WARNING, cls + " \"" + name + "\": an operand is not drawn");
            return new Unknown(cls + " operand");
        }
        final BooleanSolid.Op op = switch (cls) {
            case "G4UnionSolid" -> BooleanSolid.Op.ofTag("union");
            case "G4SubtractionSolid" -> BooleanSolid.Op.ofTag("subtraction");
            default -> BooleanSolid.Op.ofTag("intersection");
        };
        final BooleanSolid s = new BooleanSolid(uniqueSolid(name), op, a, b);
        double[] frame = null;
        V3 pos = null;
        if (args.size() >= 5) {
            final Object r = deref(args.get(3));
            pos = deref(args.get(4)) instanceof V3 p ? p : null;
            if (r instanceof Rot rot) {
                frame = rot.m;
            } else if (r == NULLPTR || (r instanceof Long l && l == 0)) {
                frame = Xform.IDENTITY.rotation();
            }
            if (frame == null || pos == null) {
                note(at, Geometry.Severity.WARNING, cls + " \"" + name + "\": the placement of the second solid is not known");
                return new Unknown(cls);
            }
        } else if (args.size() == 4 && deref(args.get(3)) instanceof Tr tr) {
            frame = transpose(tr.r);
            pos = new V3(tr.x, tr.y, tr.z);
        }
        if (frame != null) {
            final double[] angles = Xform.gdmlAngles(frame);
            s.set("x", len(pos.x));
            s.set("y", len(pos.y));
            s.set("z", len(pos.z));
            s.set("rx", ang(angles[0]));
            s.set("ry", ang(angles[1]));
            s.set("rz", ang(angles[2]));
        }
        g.addSolid(s);
        origin(s, at);
        made(s, at);
        if (at instanceof Construct c && c.heap && c.args.size() == args.size()) {
            booleanNodes.put(s, new Node[] {at, args.size() >= 5 ? c.args.get(3) : null,
                args.size() >= 5 ? c.args.get(4) : null, args.size() == 4 ? c.args.get(3) : null});
        }
        return s;
    }

    /** The statement running now made this: removed with it when it is deleted in 3D. */
    private void made(Object o, Node at) {
        if (statement != null && at != null) {
            statementNodes.put(o, new Node[] {at, statement});
        }
    }

    private Object material(List<Object> args, Node at) {
        if (args.isEmpty() || !(deref(args.get(0)) instanceof String name)) {
            return firstUnknown(args, "material name");
        }
        // (name, z, a, density, ...) or (name, density, ncomponents, ...)
        final Double d1 = args.size() > 1 ? num(args.get(1)) : null;
        final Double d2 = args.size() > 2 ? num(args.get(2)) : null;
        final Double d3 = args.size() > 3 ? num(args.get(3)) : null;
        Material m;
        if (d1 != null && d2 != null && d3 != null && !(deref(args.get(2)) instanceof Long)) {
            m = Material.define(name, d3 / Units.G_PER_CM3);
            final Element e = g.materials().nist().element((int) Math.round(d1));
            if (e != null) {
                m.addPart(e.symbol, 1, true);
            }
        } else if (d1 != null) {
            m = Material.define(name, d1 / Units.G_PER_CM3);
        } else {
            note(at, Geometry.Severity.WARNING, "material " + name + ": density not known while reading");
            return new Unknown("material " + name);
        }
        materials.put(name, m);
        return new MatRef(name);
    }

    private Object logical(List<Object> args, Node at) {
        if (args.size() < 3) {
            return new Unknown("G4LogicalVolume");
        }
        final Object solid = asSolid(args.get(0)), mat = deref(args.get(1)), name = deref(args.get(2));
        final String n = name instanceof String s ? s : "volume";
        if (!(solid instanceof Solid s)) {
            note(at, Geometry.Severity.WARNING, "volume \"" + n + "\" is not drawn: its solid is not known ("
                + (solid instanceof Unknown u ? u.why : "?") + ")");
            return new Unknown("volume " + n);
        }
        String material = mat instanceof MatRef m ? m.name() : null;
        if (material == null) {
            note(at, Geometry.Severity.WARNING, "volume \"" + n + "\": material not known while reading ("
                + (mat instanceof Unknown u ? u.why : "?") + "), drawn as G4_Galactic");
            material = "G4_Galactic";
        }
        final LogicalVolume lv = new LogicalVolume(uniqueVolume(n), s, material);
        lv.setColor(MaterialColors.colorFor(material));
        g.addVolume(lv);
        origin(lv, at);
        final List<Node> argNodes = at instanceof Construct c ? c.args : at instanceof Call c ? c.args : null;
        if (argNodes != null && argNodes.size() >= 2) {
            materialNodes.put(lv, new Node[] {at, argNodes.get(1)});
        }
        made(lv, at);
        return lv;
    }

    /** @param factory made by G4ReflectionFactory::Place, the one way Geant4 takes a reflection */
    private Object placement(List<Object> args, Node at, boolean factory) {
        if (++placements > MAX_PLACEMENTS) {
            if (placements == MAX_PLACEMENTS + 1) {
                note(at, Geometry.Severity.WARNING, "more than " + MAX_PLACEMENTS + " placements: the rest is not drawn");
            }
            return UNKNOWN;
        }
        if (args.isEmpty()) {
            return UNKNOWN;
        }
        double[] frame;
        V3 pos;
        int k;
        boolean reflected = false;
        final Object a0 = deref(args.get(0));
        Node posNode = null;
        if (a0 instanceof Tr tr && tr.scaled) {
            note(at, Geometry.Severity.WARNING, "a placement is not scaled in Geant4: scale the solid (G4ScaledSolid)");
            return UNKNOWN;
        }
        if (a0 instanceof Tr tr) {
            double[] r = tr.r;
            if (det(r) < 0) {
                // a mirror: the volume reflected through its own z = 0 plane, then turned (GDML's scale (1, 1, -1)),
                // as G4ReflectionFactory decomposes it; a mirror in x or y is one in z turned half a turn
                r = Xform.mul(r, new double[] {1, 0, 0, 0, 1, 0, 0, 0, -1});
                reflected = true;
                if (!factory) {
                    note(at, Geometry.Severity.WARNING, "G4PVPlacement is given a reflection: Geant4 takes reflections"
                        + " through G4ReflectionFactory::Instance()->Place(transform, name, logical, mother, false, copy)");
                }
            }
            frame = transpose(r);
            pos = new V3(tr.x, tr.y, tr.z);
            k = 1;
        } else {
            if (a0 == NULLPTR || (a0 instanceof Long l && l == 0)) {
                frame = null;
            } else if (a0 instanceof Rot r) {
                if (r.m == null) {
                    note(at, Geometry.Severity.WARNING, "placement rotation not known while reading: not drawn");
                    return UNKNOWN;
                }
                frame = r.m;
            } else {
                note(at, Geometry.Severity.WARNING, "placement rotation not known while reading ("
                    + (a0 instanceof Unknown u ? u.why : "?") + "): not drawn");
                return UNKNOWN;
            }
            if (args.size() < 2 || !(deref(args.get(1)) instanceof V3 p)) {
                note(at, Geometry.Severity.WARNING, "placement position not known while reading ("
                    + (args.size() > 1 && deref(args.get(1)) instanceof Unknown u ? u.why : "?") + "): not drawn");
                return UNKNOWN;
            }
            pos = p;
            posNode = p.origin;
            k = 2;
        }
        if (args.size() < k + 3) {
            return UNKNOWN;
        }
        LogicalVolume lv, mother;
        String name;
        final Object x = deref(args.get(k)), y = deref(args.get(k + 1)), z = deref(args.get(k + 2));
        if (x instanceof LogicalVolume l) {
            // (rot, pos, logical, name, motherLogical, many, copy, check)
            lv = l;
            name = y instanceof String s ? s : lv.name();
            mother = z instanceof LogicalVolume m ? m : z instanceof PhysicalVolume pm ? pm.logical()
                : z instanceof WorldPv w ? w.lv : null;
            if (mother == null && !(z == NULLPTR || (z instanceof Long q && q == 0))) {
                note(at, Geometry.Severity.WARNING, "\"" + name + "\": its mother volume is not known while reading");
                return UNKNOWN;
            }
        } else if (y instanceof LogicalVolume l) {
            // (rot, pos, name, logical, motherPhysical, many, copy, check)
            lv = l;
            name = x instanceof String s ? s : lv.name();
            mother = z instanceof PhysicalVolume pm ? pm.logical() : z instanceof WorldPv w ? w.lv
                : z instanceof LogicalVolume m ? m : null;
            if (mother == null && !(z == NULLPTR || (z instanceof Long q && q == 0))) {
                note(at, Geometry.Severity.WARNING, "\"" + name + "\": its mother volume is not known while reading");
                return UNKNOWN;
            }
        } else {
            note(at, Geometry.Severity.WARNING, "placement of a volume not known while reading ("
                + (x instanceof Unknown u ? u.why : y instanceof Unknown u ? u.why : "?") + "): not drawn");
            return UNKNOWN;
        }
        final int copy = args.size() > k + 4 && deref(args.get(k + 4)) instanceof Number c ? c.intValue() : 0;
        if (mother == null) {
            if (!roots.contains(lv)) {
                roots.add(lv);
            }
            final WorldPv w = new WorldPv(lv, name);
            origin(lv, at);
            return w;
        }
        if (mother == lv || lv.contains(mother)) {
            note(at, Geometry.Severity.ERROR, "\"" + name + "\" placed inside itself");
            return UNKNOWN;
        }
        final PhysicalVolume pv = g.place(lv, mother, name);
        pv.setCopyNumber(copy);
        pv.setPosition(len(pos.x), len(pos.y), len(pos.z));
        if (frame != null && !isIdentity(frame)) {
            final double[] angles = Xform.gdmlAngles(frame);
            pv.setRotation(ang(angles[0]), ang(angles[1]), ang(angles[2]));
        }
        if (reflected) {
            pv.setReflected(true);
        }
        origin(pv, at);
        if (posNode != null) {
            positionNodes.put(pv, posNode);
        }
        final List<Node> argNodes = at instanceof Construct c ? c.args : at instanceof Call c ? c.args : null;
        if (argNodes != null && argNodes.size() >= 2) {
            placementNodes.put(pv, new PlacementNodes(at, argNodes.get(0), a0 instanceof Tr ? null : argNodes.get(1),
                argNodes.size() > k + 4 ? argNodes.get(k + 4) : null, a0 instanceof Tr));
        }
        made(pv, at);
        return pv;
    }

    private static double det(double[] m) {
        return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
    }

    // ==================================================================
    // G4PVDivision: the copies Geant4's division parameterisations make
    // ==================================================================

    private record Division(String name, LogicalVolume lv, LogicalVolume mother, String axis, int n, double width,
                            double offset, Divisions.Kind kind, Node at) {
    }

    /** (name, logical, mother, axis, nDivs, width, offset), (..., nDivs, offset) or (..., width, offset). */
    private Object division(List<Object> args, Node at) {
        if (args.size() < 6) {
            return UNKNOWN;
        }
        final Object n = deref(args.get(0)), l = deref(args.get(1)), m = deref(args.get(2)), axis = deref(args.get(3));
        final LogicalVolume mother = m instanceof LogicalVolume ml ? ml : m instanceof PhysicalVolume pm ? pm.logical()
            : m instanceof WorldPv w ? w.lv : null;
        final String name = n instanceof String str ? str : "division";
        final Object a4 = deref(args.get(4));
        Divisions.Kind kind;
        Double count = 0.0, width = 0.0, offset;
        if (args.size() >= 7) {
            kind = Divisions.Kind.NDIV_AND_WIDTH;
            count = num(a4);
            width = num(args.get(5));
            offset = num(args.get(6));
        } else if (a4 instanceof Long c) {
            // an int: the (nDivs, offset) constructor, as C++ picks it
            kind = Divisions.Kind.NDIV;
            count = c.doubleValue();
            offset = num(args.get(5));
        } else {
            kind = Divisions.Kind.WIDTH;
            width = num(a4);
            offset = num(args.get(5));
        }
        if (!(l instanceof LogicalVolume lv) || mother == null || !(axis instanceof EnumV ax) || count == null
            || width == null || offset == null) {
            note(at, Geometry.Severity.WARNING, "G4PVDivision \"" + name + "\": a parameter not known while reading: not drawn");
            return UNKNOWN;
        }
        divisions.add(new Division(name, lv, mother, ax.name(), count.intValue(), width, offset, kind, at));
        return new Opaque("G4PVDivision");
    }

    private void expand(Division d) {
        final Solid ms = d.mother().solid(), ds = d.lv().solid();
        if (ms == null || ds == null) {
            return;
        }
        final Divisions.Result r = Divisions.divide(ms, ds, d.axis(), d.n(), d.width(), d.offset(), d.kind(), ds.name());
        if (r.error() != null) {
            note(d.at(), Geometry.Severity.WARNING, "G4PVDivision \"" + d.name() + "\": " + r.error());
            return;
        }
        final Map<String, LogicalVolume> variants = new HashMap<>();
        for (int i = 0; i < r.copies().size(); i++) {
            if (++placements > MAX_PLACEMENTS) {
                note(d.at(), Geometry.Severity.WARNING, "more than " + MAX_PLACEMENTS + " placements: the rest is not drawn");
                return;
            }
            final Divisions.Copy c = r.copies().get(i);
            final int copy = i;
            final LogicalVolume target = signature(c.solid()).equals(signature(ds)) ? d.lv()
                : variants.computeIfAbsent(signature(c.solid()), k -> variant(d.lv(), d.at(), c.solid(), d.lv().material(), copy));
            final PhysicalVolume pv = g.place(target, d.mother(), d.name());
            pv.setCopyNumber(i);
            pv.setPosition(len(c.x()), len(c.y()), len(c.z()));
            if (Math.abs(c.frameRotZ()) > 1e-15) {
                final double[] angles = Xform.gdmlAngles(axisRotation('Z', c.frameRotZ()));
                pv.setRotation(ang(angles[0]), ang(angles[1]), ang(angles[2]));
            }
            origin(pv, d.at());
        }
    }

    // ==================================================================
    // G4PVParameterised: the parameterisation run for every copy
    // ==================================================================

    private record Parameterised(String name, LogicalVolume lv, LogicalVolume mother, int count, Instance param,
                                 Node at) {
    }

    private Object parameterised(List<Object> args, Node at) {
        // (name, logical, mother (logical or physical), axis, n, parameterisation, check = false)
        if (args.size() < 6) {
            return UNKNOWN;
        }
        final Object n = deref(args.get(0)), l = deref(args.get(1)), m = deref(args.get(2)), p = deref(args.get(5));
        final Double count = num(args.get(4));
        final LogicalVolume mother = m instanceof LogicalVolume ml ? ml : m instanceof PhysicalVolume pm ? pm.logical()
            : m instanceof WorldPv w ? w.lv : null;
        final String name = n instanceof String str ? str : "parameterised";
        if (!(l instanceof LogicalVolume lv) || mother == null || count == null) {
            note(at, Geometry.Severity.WARNING, "G4PVParameterised \"" + name + "\": a parameter not known while reading: not drawn");
            return UNKNOWN;
        }
        if (!(p instanceof Instance inst)) {
            note(at, Geometry.Severity.WARNING, "G4PVParameterised \"" + name + "\": its parameterisation ("
                + (p instanceof Unknown u ? u.why : p instanceof Opaque o ? o.type + ", not in the sources read" : "?")
                + ") is not read: not drawn");
            return UNKNOWN;
        }
        parameterised.add(new Parameterised(name, lv, mother, count.intValue(), inst, at));
        return new Opaque("G4PVParameterised");
    }

    /**
     * The copies of a G4PVParameterised, as Geant4 makes them when it navigates: for each copy number the
     * parameterisation's ComputeTransformation, ComputeSolid and the ComputeDimensions overload of the solid,
     * ComputeMaterial. Copies whose solid or material differ get a volume of their own, daughters included.
     */
    private void expand(Parameterised j) {
        final String cls = j.param().cls;
        final Function transform = resolve(cls, "ComputeTransformation", 2, null, 0);
        if (transform == null) {
            note(j.at(), Geometry.Severity.WARNING, "G4PVParameterised \"" + j.name() + "\": " + cls
                + "::ComputeTransformation is not in the sources read: not drawn");
            return;
        }
        final Function solidOf = resolve(cls, "ComputeSolid", 2, null, 0);
        final Function materialOf = resolve(cls, "ComputeMaterial", 3, null, 0);
        final Map<String, LogicalVolume> variants = new HashMap<>();
        final LogicalVolume lv = j.lv();
        for (int i = 0; i < j.count(); i++) {
            if (++placements > MAX_PLACEMENTS) {
                note(j.at(), Geometry.Severity.WARNING, "more than " + MAX_PLACEMENTS + " placements: the rest is not drawn");
                return;
            }
            final PvStub stub = new PvStub(i);
            call(transform, List.of((long) i, stub), j.param().fields, j.at());
            Solid solid = lv.solid();
            if (solidOf != null && deref(call(solidOf, List.of((long) i, stub), j.param().fields, j.at())) instanceof Solid cs) {
                solid = cs;
            }
            final Function dims = solid == null ? null : resolve(cls, "ComputeDimensions", 3, solid, 0);
            if (dims != null) {
                Solid sized;
                try {
                    sized = solid.copy(solid.name());
                } catch (RuntimeException e) {
                    sized = null;   // a solid the catalogue cannot copy (a boolean): drawn as it is
                }
                if (sized != null) {
                    call(dims, List.of(sized, (long) i, stub), j.param().fields, j.at());
                    if (!signature(sized).equals(signature(solid))) {
                        solid = sized;
                    }
                }
            }
            String material = lv.material();
            if (materialOf != null && deref(call(materialOf, List.of((long) i, stub, UNKNOWN), j.param().fields, j.at()))
                instanceof MatRef mr) {
                material = mr.name();
            }
            LogicalVolume target = lv;
            if (solid != lv.solid() || !material.equals(lv.material())) {
                final Solid fs = solid;
                final String fm = material;
                final int copy = i;
                target = variants.computeIfAbsent(signature(solid) + "|" + material,
                    k -> variant(j.lv(), j.at(), fs, fm, copy));
            }
            final PhysicalVolume pv = g.place(target, j.mother(), j.name());
            pv.setCopyNumber(i);
            pv.setPosition(len(stub.t.x), len(stub.t.y), len(stub.t.z));
            if (stub.frame != null && !isIdentity(stub.frame)) {
                final double[] angles = Xform.gdmlAngles(stub.frame);
                pv.setRotation(ang(angles[0]), ang(angles[1]), ang(angles[2]));
            }
            origin(pv, j.at());
        }
    }

    private static String signature(Solid s) {
        final StringBuilder b = new StringBuilder(s.tag());
        for (Solid.Param p : s.params()) {
            b.append('|').append(p.key).append('=').append(p.expr());
        }
        for (Solid.Table t : s.tables()) {
            for (String[] r : t.rows()) {
                b.append('|').append(String.join(",", r));
            }
        }
        return b.toString();
    }

    /** A copy of a parameterised or divided volume with its own solid or material: its daughters are placed in it too. */
    private LogicalVolume variant(LogicalVolume lv, Node at, Solid s, String material, int copy) {
        Solid solid = s;
        if (g.solid(s.name()) == null) {
            g.addSolid(s);
            origin(s, at);
        } else if (g.solid(s.name()) != s) {
            // the sized copy, under a name of its own (copy() keeps the parameters and tables)
            solid = s.copy(uniqueSolid(s.name() + "_" + copy));
            g.addSolid(solid);
            origin(solid, at);
        }
        final LogicalVolume v = new LogicalVolume(uniqueVolume(lv.name() + "_" + copy), solid, material);
        v.setColor(material.equals(lv.material()) ? lv.color() : MaterialColors.colorFor(material));
        v.setVisible(lv.visible());
        v.setWireframe(lv.wireframe());
        if (lv.sensitive() != null) {
            v.setSensitive(lv.sensitive());
        }
        g.addVolume(v);
        for (PhysicalVolume d : lv.daughters()) {
            final PhysicalVolume c = g.place(d.logical(), v, d.name());
            c.setCopyNumber(d.copyNumber());
            final String[] p = d.positionExprs(), r = d.rotationExprs();
            c.setPosition(p[0], p[1], p[2]);
            c.setRotation(r[0], r[1], r[2]);
            c.setReflected(d.reflected());
            if (d.isReplica()) {
                c.setReplica(d.replica());
            }
            final Origin o = origins.get(d);
            if (o != null) {
                origins.put(c, o);
            }
        }
        origin(v, at);
        return v;
    }

    private Object replica(List<Object> args, Node at) {
        // (name, logical, mother (logical or physical), axis, n, width, offset = 0)
        if (args.size() < 6) {
            return UNKNOWN;
        }
        final Object n = deref(args.get(0)), l = deref(args.get(1)), m = deref(args.get(2)), axis = deref(args.get(3));
        final Double count = num(args.get(4)), width = num(args.get(5));
        final Double offset = args.size() > 6 ? num(args.get(6)) : Double.valueOf(0);
        final LogicalVolume mother = m instanceof LogicalVolume ml ? ml : m instanceof PhysicalVolume pm ? pm.logical()
            : m instanceof WorldPv w ? w.lv : null;
        if (!(l instanceof LogicalVolume lv) || mother == null || !(axis instanceof EnumV ax) || count == null
            || width == null || offset == null) {
            note(at, Geometry.Severity.WARNING, "G4PVReplica: a parameter not known while reading: not drawn");
            return UNKNOWN;
        }
        final PhysicalVolume.Replica.Axis a;
        try {
            a = PhysicalVolume.Replica.Axis.valueOf(ax.name());
        } catch (IllegalArgumentException e) {
            note(at, Geometry.Severity.WARNING, "G4PVReplica: axis " + ax.name() + " not read");
            return UNKNOWN;
        }
        final PhysicalVolume pv = g.place(lv, mother, n instanceof String s ? s : lv.name());
        final PhysicalVolume.Replica r = new PhysicalVolume.Replica();
        r.setAxis(a);
        r.setNumber(String.valueOf(count.intValue()));
        r.setWidth(a == PhysicalVolume.Replica.Axis.kPhi ? ang(width) : len(width));
        r.setOffset(a == PhysicalVolume.Replica.Axis.kPhi ? ang(offset) : len(offset));
        pv.setReplica(r);
        origin(pv, at);
        return pv;
    }

    private static boolean isIdentity(double[] m) {
        return Math.abs(m[0] - 1) < 1e-14 && Math.abs(m[4] - 1) < 1e-14 && Math.abs(m[8] - 1) < 1e-14;
    }
}

package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * G4editor's console language: everything the mouse does, typed. One command
 * per line (or several separated by ';'), '#' starts a comment:
 *
 * <pre>
 *   var T = 300um
 *   box Layer 20cm 20cm T G4_Si at 0 0 10cm
 *   tube Pipe 3cm 1.6m rmin=2.9cm G4_Be
 *   copy Layer_p 4 0 0 5cm
 *   set Layer.material = G4_Ge
 *   subtract Block Hole
 * </pre>
 *
 * Commands that need the window (view, check, export...) go to the host.
 * A line no command matches is handed to the natural-language reader.
 */
public final class Script {

    /** What the console needs from the editor around it. */
    public interface Host {
        default void print(String text) {
        }

        default void error(String text) {
        }

        default String lengthUnit() {
            return "mm";
        }

        default String material() {
            return "G4_Al";
        }

        /** Runs a change as one undo step. */
        default void edit(String label, Runnable change) {
            change.run();
        }

        default void select(List<PhysicalVolume> pvs) {
        }

        /** Window-level commands: view, fit, section, check, budget, export, run... */
        default boolean command(String name, List<String> args) {
            return false;
        }
    }

    private final Geometry g;
    private final Host host;
    private final Intent intent;

    public Script(Geometry g, Host host) {
        this.g = g;
        this.host = host;
        this.intent = new Intent(g);
    }

    private static final Map<String, String> HELP = new LinkedHashMap<>();

    static {
        HELP.put("box", "box [name] <x> <y> <z> [material] [at x y z] [rot rx ry rz] [in mother]   full lengths, as GDML");
        HELP.put("tube", "tube [name] <rmax> <length> [rmin=] [startphi=] [deltaphi=] [material] [at ...]");
        HELP.put("cone", "cone [name] <rmax1> <rmax2> <length> [rmin1=] [rmin2=] [material] [at ...]");
        HELP.put("sphere", "sphere [name] <r> [rmin=] [starttheta=] [deltatheta=] [material] [at ...]");
        HELP.put("torus", "torus [name] <rtor> <rmax> [rmin=] [material] [at ...]");
        HELP.put("trd", "trd [name] <x1> <x2> <y1> <y2> <z> [material] [at ...]");
        HELP.put("solid", "solid <type> [name] key=value ... [material] [at ...]   any of: " + typeList());
        HELP.put("var", "var <name> = <expression>   a parametric value (GDML constant); 'vars' lists them");
        HELP.put("set", "set <object>.<property> = <value>   x y z rx ry rz position rotation material color visible sensitive, or a solid parameter");
        HELP.put("move", "move <placement> <dx> <dy> <dz>   in the world frame");
        HELP.put("moveto", "moveto <placement> <x> <y> <z>   world position");
        HELP.put("rotate", "rotate <placement> <rx> <ry> <rz>   about world axes through its origin");
        HELP.put("copy", "copy <placement> [n] [dx dy dz]   n more copies, each shifted by (dx dy dz)");
        HELP.put("polar", "polar <placement> <n> [total angle]   n copies around the mother's z axis");
        HELP.put("place", "place <volume> [as name] [in mother] [at x y z] [rot rx ry rz]   another placement of a volume");
        HELP.put("replica", "replica <volume> in <mother> along x|y|z|phi <n> <width> [offset]   a G4PVReplica");
        HELP.put("union", "union <A> <B> [as name]   fuse two placed volumes (FreeCAD's Fuse)");
        HELP.put("subtract", "subtract <A> <B> [as name]   cut B out of A");
        HELP.put("intersect", "intersect <A> <B> [as name]   keep the common part");
        HELP.put("delete", "delete <name> ...   a placement, a volume, a solid or a variable");
        HELP.put("rename", "rename <old> <new>   expressions follow a renamed variable");
        HELP.put("material", "material <name> <density> atoms|fractions <El:n> ... [state=gas] [T=300K] [P=1atm]");
        HELP.put("world", "world <x> <y> <z> [material] | world fit [margin] | world material <name>");
        HELP.put("sensitive", "sensitive <volume> [detector name]   marks a sensitive detector (GDML SensDet)");
        HELP.put("color", "color <volume> <#rrggbb[aa]|red|...>");
        HELP.put("hide", "hide <volume> ...  /  show <volume> ...");
        HELP.put("template", "template calorimeter|tracker|telescope|shield|phantom|tpc key=value ...   parametric detectors");
        HELP.put("x0", "x0 <material> [thickness]   radiation and interaction lengths, and the fraction a thickness makes");
        HELP.put("info", "info <name>   what an object is, its volume, mass and material budget");
        HELP.put("list", "list   the placement tree");
        HELP.put("materials", "materials [filter]   NIST and document materials");
        HELP.put("view", "view iso|front|top|right|rear|bottom|left|side|beam; fit; section x|y|z <offset>|off");
        HELP.put("colorby", "colorby document|material|density|x0|depth|sensitive   what the view colors volumes by");
        HELP.put("isolate", "isolate [placement|volume ...|off]   only these (or the selection), the rest as outlines;"
            + " xray off hides the rest");
        HELP.put("check", "check [points] [tolerance]   overlaps, as G4PVPlacement::CheckOverlaps");
        HELP.put("budget", "budget [eta|theta]   material budget scan from the origin");
        HELP.put("mass", "mass   masses of every volume");
        HELP.put("advise", "advise   the design assistant's findings");
        HELP.put("resolve", "resolve   moves the selected volume out of what it overlaps, by the shortest move that clears it (F6)");
        HELP.put("clashes", "clashes on|off   the live check of the selection for overlaps as it is edited");
        HELP.put("export", "export gdml <file> [portable] | export root <file> | export cpp <folder>   Geant4 and ROOT files");
        HELP.put("open", "open <file.gdml|file.root> [geometry]   a GDML design or the TGeoManager of a ROOT file");
        HELP.put("save", "save [file.gdml|file.root]   the design, in the format its extension names");
        HELP.put("convert", "convert <in> <out> [portable]   GDML <-> ROOT geometry, without opening the file");
        HELP.put("nist", "nist [apply]   the defined materials that are NIST ones; apply uses NIST's");
        HELP.put("run", "run <events> [particle] [energy]   in Geant4 through geant4-bridge");
        HELP.put("undo", "undo / redo");
    }

    private static String typeList() {
        final StringBuilder b = new StringBuilder();
        for (Solids.Type t : Solids.types()) {
            b.append(b.length() > 0 ? ", " : "").append(t.tag());
        }
        return b.toString();
    }

    public static Map<String, String> help() {
        return HELP;
    }

    /** Names the console completes: commands, objects, materials, variables. */
    public List<String> completions(String prefix) {
        final TreeSet<String> all = new TreeSet<>(HELP.keySet());
        all.addAll(List.of("show", "vars", "fit", "section", "redo", "save", "open", "new", "help", "clear", "convert",
            "nist"));
        for (LogicalVolume lv : g.volumes()) {
            all.add(lv.name());
        }
        for (PhysicalVolume pv : g.placements()) {
            all.add(pv.name());
        }
        for (Define d : g.defines()) {
            all.add(d.name());
        }
        for (Solid s : g.solids()) {
            all.add(s.name());
        }
        final List<String> out = new ArrayList<>();
        final String p = prefix.toLowerCase(Locale.ROOT);
        for (String s : all) {
            if (s.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(s);
            }
        }
        if (prefix.startsWith("G4_") || prefix.startsWith("g4_")) {
            for (Material m : NistDB.get().search(prefix.substring(3))) {
                if (m.name().toLowerCase(Locale.ROOT).startsWith(p)) {
                    out.add(m.name());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------

    /** Runs a line: commands separated by ';', comments after '#'. */
    public void run(String line) {
        if (line == null) {
            return;
        }
        final int hash = line.indexOf('#');
        final String code = hash >= 0 ? line.substring(0, hash) : line;
        for (String cmd : code.split(";")) {
            if (!cmd.isBlank()) {
                try {
                    one(cmd.trim());
                } catch (GeometryException | RuntimeException e) {
                    host.error(e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        }
    }

    private void one(String line) throws GeometryException {
        final List<String> t = tokens(line);
        if (t.isEmpty()) {
            return;
        }
        final String cmd = t.get(0).toLowerCase(Locale.ROOT);
        final List<String> a = t.subList(1, t.size());
        switch (cmd) {
            case "help", "?" -> help(a);
            case "box", "tube", "tubs", "cylinder", "cone", "cons", "sphere", "orb", "torus", "trd" -> primitive(cmd, a);
            case "solid" -> {
                need(a, 1, "solid <type> ...");
                primitive(a.get(0), a.subList(1, a.size()));
            }
            case "var", "const", "let" -> var(line.substring(t.get(0).length()).trim());
            case "vars" -> vars();
            case "set" -> set(line.substring(t.get(0).length()).trim());
            case "move" -> move(a, false);
            case "moveto" -> move(a, true);
            case "rotate" -> rotate(a);
            case "copy", "array" -> copy(a);
            case "polar" -> polar(a);
            case "place" -> place(a);
            case "replica" -> replica(a);
            case "union", "fuse" -> bool(BooleanSolid.Op.UNION, a);
            case "subtract", "cut", "difference" -> bool(BooleanSolid.Op.SUBTRACTION, a);
            case "intersect", "common", "intersection" -> bool(BooleanSolid.Op.INTERSECTION, a);
            case "delete", "del", "rm" -> delete(a);
            case "rename" -> rename(a);
            case "material", "mat" -> material(a);
            case "world" -> world(a);
            case "sensitive", "sd" -> sensitive(a);
            case "color", "colour" -> color(a);
            case "hide" -> visibility(a, false);
            case "show" -> visibility(a, true);
            case "template" -> template(a);
            case "x0" -> x0(a);
            case "info" -> info(a);
            case "list", "ls", "tree" -> list();
            case "materials" -> materials(a);
            case "select" -> select(a);
            default -> {
                if (host.command(cmd, new ArrayList<>(a))) {
                    return;
                }
                final List<String> plan = intent.interpret(line, host.lengthUnit(), host.material());
                if (plan.isEmpty()) {
                    throw new GeometryException("unknown command '" + t.get(0) + "'. Type help, or describe what you want ("
                        + "e.g. \"add a 300 um silicon layer at z = 10 cm\").");
                }
                host.print("# understood: " + String.join(" ; ", plan));
                for (String p : plan) {
                    one(p);
                }
            }
        }
    }

    private void help(List<String> a) {
        if (!a.isEmpty() && HELP.containsKey(a.get(0))) {
            host.print(HELP.get(a.get(0)));
            return;
        }
        host.print("G4editor commands (values take units: 10cm, 300um, 45deg; expressions and variables: 2*R+5mm):");
        for (String v : HELP.values()) {
            host.print("  " + v);
        }
        host.print("Anything else is read as a sentence: \"add a 2 mm lead layer at z=50 cm\", \"a 5 layer silicon tracker\".");
    }

    // ------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------

    /** Positional parameters of the shorthand commands, in order. */
    private static final Map<String, String[]> POSITIONAL = Map.of(
        "box", new String[] {"x", "y", "z"},
        "tube", new String[] {"rmax", "z"},
        "cone", new String[] {"rmax1", "rmax2", "z"},
        "orb", new String[] {"r"},
        "sphere", new String[] {"rmax"},
        "torus", new String[] {"rtor", "rmax"},
        "trd", new String[] {"x1", "x2", "y1", "y2", "z"});

    private void primitive(String cmd, List<String> args) throws GeometryException {
        String tag = switch (cmd) {
            case "tubs", "cylinder" -> "tube";
            case "cons" -> "cone";
            default -> cmd;
        };
        final Solids.Type type = Solids.type(tag) != null ? Solids.type(tag) : Solids.byClass(tag);
        if (type == null) {
            throw new GeometryException("unknown solid type '" + cmd + "'. Types: " + typeList());
        }
        tag = type.tag();
        final Parsed p = parse(args);
        // A sphere with only a radius is a G4Orb, the faster solid
        if (tag.equals("sphere") && !p.keys.containsKey("rmin") && !p.keys.containsKey("starttheta")
            && !p.keys.containsKey("deltatheta") && !p.keys.containsKey("deltaphi")) {
            tag = "orb";
        }
        final String base = p.name != null ? p.name : type.label().replaceAll("[^A-Za-z]", "");
        if (p.name != null && g.nameTaken(p.name)) {
            throw new GeometryException("the name " + p.name + " is already used");
        }
        final Solid s = Solids.create(tag, base);
        final String[] order = POSITIONAL.getOrDefault(tag, new String[0]);
        if (p.values.size() > order.length) {
            throw new GeometryException(tag + " takes " + order.length + " positional values: " + HELP.getOrDefault(cmd, ""));
        }
        for (int i = 0; i < p.values.size(); i++) {
            s.set(order[i], value(p.values.get(i), s.param(order[i]).kind));
        }
        for (Map.Entry<String, String> kv : p.keys.entrySet()) {
            final Solid.Param prm = s.param(kv.getKey());
            if (prm == null) {
                if (List.of("mat", "material").contains(kv.getKey())) {
                    continue;
                }
                throw new GeometryException(s.g4Class() + " has no parameter " + kv.getKey() + " (has "
                    + String.join(", ", s.params().stream().map(x -> x.key).toList()) + ")");
            }
            s.set(kv.getKey(), value(kv.getValue(), prm.kind));
        }
        final String mat = p.material != null ? p.material : p.keys.getOrDefault("material", p.keys.getOrDefault("mat", host.material()));
        checkMaterial(mat);
        final LogicalVolume mother = p.in == null ? null : volume(p.in);
        final Xform world = p.worldTransform(g);
        final PhysicalVolume[] made = new PhysicalVolume[1];
        host.edit("Create " + base, () -> made[0] = Placer.create(g, base, s, mat, world, mother, this::len));
        if (made[0] != null) {
            if (made[0].logical().solid().error() != null) {
                host.error(made[0].logical().solid().error());
            }
            host.print("created " + made[0].logical().name() + " (" + s.g4Class() + ", " + mat + ") in "
                + made[0].mother().name());
            host.select(List.of(made[0]));
        }
    }

    String len(double mm) {
        final double u = Units.Category.LENGTH.unitValue(host.lengthUnit());
        return Units.num(mm / u) + "*" + host.lengthUnit();
    }

    private String value(String typed, Solid.Kind kind) {
        return Inputs.normalize(typed, kind.category, host.lengthUnit());
    }

    private void checkMaterial(String m) throws GeometryException {
        if (g.materials().material(m) == null) {
            final List<Material> near = NistDB.get().search(m.replace("G4_", ""));
            throw new GeometryException("unknown material " + m + (near.isEmpty() ? "" : "; did you mean "
                + near.subList(0, Math.min(5, near.size())) + "?"));
        }
    }

    /** Arguments split into a name, positional values, key=value pairs, a material and placement clauses. */
    private final class Parsed {
        String name, material, in, as;
        final List<String> values = new ArrayList<>();
        final Map<String, String> keys = new LinkedHashMap<>();
        String[] at, rot;

        Xform worldTransform(Geometry geo) throws GeometryException {
            final double[] pos = new double[3], ang = new double[3];
            if (at != null) {
                for (int i = 0; i < 3; i++) {
                    pos[i] = Expr.eval(Inputs.normalize(at[i], Units.Category.LENGTH, host.lengthUnit()), geo.scope());
                }
            }
            if (rot != null) {
                for (int i = 0; i < 3; i++) {
                    ang[i] = Expr.eval(Inputs.normalize(rot[i], Units.Category.ANGLE, host.lengthUnit()), geo.scope());
                }
            }
            // "at" is in the mother's frame when "in" names one; in the world otherwise
            Xform local = Xform.placement(pos[0], pos[1], pos[2], ang[0], ang[1], ang[2]);
            if (in != null) {
                final LogicalVolume m = geo.volume(in);
                if (m != null) {
                    final Geometry.Instance mi = Placer.instanceOf(geo, m, null);
                    if (mi != null) {
                        local = mi.world().compose(local);
                    }
                }
            }
            return local;
        }
    }

    private Parsed parse(List<String> args) throws GeometryException {
        final Parsed p = new Parsed();
        int i = 0;
        while (i < args.size()) {
            final String a = args.get(i);
            final String low = a.toLowerCase(Locale.ROOT);
            if (low.equals("at") || low.equals("pos")) {
                need(args.subList(i + 1, args.size()), 3, "at <x> <y> <z>");
                p.at = new String[] {args.get(i + 1), args.get(i + 2), args.get(i + 3)};
                i += 4;
            } else if (low.equals("rot")) {
                need(args.subList(i + 1, args.size()), 3, "rot <rx> <ry> <rz>");
                p.rot = new String[] {args.get(i + 1), args.get(i + 2), args.get(i + 3)};
                i += 4;
            } else if (low.equals("in")) {
                need(args.subList(i + 1, args.size()), 1, "in <mother>");
                p.in = args.get(i + 1);
                i += 2;
            } else if (low.equals("as")) {
                need(args.subList(i + 1, args.size()), 1, "as <name>");
                p.as = args.get(i + 1);
                i += 2;
            } else if (a.contains("=") && !a.startsWith("=")) {
                final int k = a.indexOf('=');
                p.keys.put(a.substring(0, k).trim(), a.substring(k + 1).trim());
                i++;
            } else if (Inputs.looksNumeric(a) || g.define(a) != null || isExpression(a)) {
                p.values.add(a);
                i++;
            } else if (g.materials().material(a) != null) {
                p.material = a;
                i++;
            } else if (p.name == null && p.values.isEmpty() && a.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                p.name = a;
                i++;
            } else {
                throw new GeometryException("cannot read '" + a + "' (a value, a material, key=value, at/rot/in?)");
            }
        }
        return p;
    }

    private boolean isExpression(String a) {
        if (!a.matches(".*[*/+^()-].*")) {
            return false;
        }
        try {
            Expr.parse(a);
            return true;
        } catch (GeometryException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Variables and properties
    // ------------------------------------------------------------------

    private void var(String rest) throws GeometryException {
        final int eq = rest.indexOf('=');
        String name, expr;
        if (eq < 0) {
            final String[] f = rest.trim().split("\\s+", 2);
            if (f.length < 2) {
                throw new GeometryException("var <name> = <expression>");
            }
            name = f[0];
            expr = f[1];
        } else {
            name = rest.substring(0, eq).trim();
            expr = rest.substring(eq + 1).trim();
        }
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new GeometryException("'" + name + "' is not a valid name");
        }
        if (Units.isReserved(name)) {
            throw new GeometryException(name + " is a unit or constant of Geant4's evaluator; pick another name");
        }
        final String norm = Inputs.normalize(expr, Units.Category.NONE, host.lengthUnit());
        Expr.parse(norm);
        final Define existing = g.define(name);
        if (existing == null && g.nameTaken(name)) {
            throw new GeometryException("the name " + name + " is already used by an object");
        }
        host.edit("Variable " + name, () -> {
            if (existing != null) {
                existing.setExpr(norm);
            } else {
                g.addDefine(new Define(name, Define.Kind.CONSTANT, norm));
            }
        });
        final Define d = g.define(name);
        host.print(name + " = " + norm + (d.error() == null ? "  (" + Units.num(d.value()) + ")" : "  error: " + d.error()));
    }

    private void vars() {
        if (g.defines().isEmpty()) {
            host.print("no variables; 'var name = value' makes one");
        }
        for (Define d : g.defines()) {
            host.print("  " + d.name() + " = " + d.effectiveExpr() + "   -> " + (d.error() == null ? Units.num(d.value()) : d.error()));
        }
    }

    private void set(String rest) throws GeometryException {
        String target, prop, value;
        final int eq = rest.indexOf('=');
        if (eq > 0) {
            final String lhs = rest.substring(0, eq).trim();
            value = rest.substring(eq + 1).trim();
            final int dot = lhs.lastIndexOf('.');
            if (dot < 0) {
                throw new GeometryException("set <object>.<property> = <value>");
            }
            target = lhs.substring(0, dot);
            prop = lhs.substring(dot + 1);
        } else {
            final String[] f = rest.split("\\s+", 3);
            if (f.length < 3) {
                throw new GeometryException("set <object>.<property> = <value>");
            }
            target = f[0];
            prop = f[1];
            value = f[2];
        }
        final String tg = target, pr = prop, val = value;
        host.edit("Set " + target + "." + prop, () -> {
            try {
                setProperty(tg, pr, val);
            } catch (GeometryException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        });
        for (Geometry.Issue i : g.issues()) {
            if (i.severity() == Geometry.Severity.ERROR && i.message().contains(target)) {
                host.error(i.message());
            }
        }
    }

    /** Sets one property of a placement, a volume, a solid or a variable. */
    public void setProperty(String target, String prop, String value) throws GeometryException {
        final PhysicalVolume pv = g.placement(target);
        LogicalVolume lv = g.volume(target);
        final Solid sol = g.solid(target);
        final Define def = g.define(target);
        final String unit = host.lengthUnit();
        if (def != null && (prop.equals("value") || prop.equals("expr"))) {
            def.setExpr(Inputs.normalize(value, Units.Category.NONE, unit));
            return;
        }
        if (pv != null) {
            final String[] p = pv.positionExprs(), r = pv.rotationExprs();
            switch (prop) {
                case "x" -> {
                    pv.setPosition(len(value), p[1], p[2]);
                    return;
                }
                case "y" -> {
                    pv.setPosition(p[0], len(value), p[2]);
                    return;
                }
                case "z" -> {
                    pv.setPosition(p[0], p[1], len(value));
                    return;
                }
                case "rx" -> {
                    pv.setRotation(ang(value), r[1], r[2]);
                    return;
                }
                case "ry" -> {
                    pv.setRotation(r[0], ang(value), r[2]);
                    return;
                }
                case "rz" -> {
                    pv.setRotation(r[0], r[1], ang(value));
                    return;
                }
                case "position", "pos" -> {
                    final String[] v = value.trim().split("\\s+");
                    if (v.length != 3) {
                        throw new GeometryException("position takes three values");
                    }
                    pv.setPosition(len(v[0]), len(v[1]), len(v[2]));
                    return;
                }
                case "rotation", "rot" -> {
                    final String[] v = value.trim().split("\\s+");
                    if (v.length != 3) {
                        throw new GeometryException("rotation takes three values");
                    }
                    pv.setRotation(ang(v[0]), ang(v[1]), ang(v[2]));
                    return;
                }
                case "copy", "copynumber" -> {
                    pv.setCopyNumber((int) Math.round(Expr.eval(value, g.scope())));
                    return;
                }
                default -> lv = pv.logical();
            }
        }
        if (lv != null) {
            switch (prop) {
                case "material", "mat" -> {
                    checkMaterial(value.trim());
                    final boolean recolor = lv.color() == MaterialColors.colorFor(lv.material());
                    lv.setMaterial(value.trim());
                    if (recolor) {
                        lv.setColor(MaterialColors.colorFor(value.trim()));
                    }
                    return;
                }
                case "color", "colour" -> {
                    lv.setColor(Gdml.parseColor(value.trim(), lv.color()));
                    return;
                }
                case "alpha", "opacity" -> {
                    final double a = Math.max(0, Math.min(1, Expr.eval(value, g.scope())));
                    lv.setColor(((int) Math.round(a * 255) << 24) | (lv.color() & 0xFFFFFF));
                    return;
                }
                case "visible" -> {
                    lv.setVisible(truth(value));
                    return;
                }
                case "wireframe" -> {
                    lv.setWireframe(truth(value));
                    return;
                }
                case "sensitive", "sd" -> {
                    lv.setSensitive(value.equalsIgnoreCase("none") || value.equalsIgnoreCase("false") ? null : value.trim());
                    return;
                }
                default -> {
                    if (lv.solid() != null && lv.solid().param(prop) != null) {
                        lv.solid().set(prop, value(value, lv.solid().param(prop).kind));
                        return;
                    }
                }
            }
        }
        if (sol != null && sol.param(prop) != null) {
            sol.set(prop, value(value, sol.param(prop).kind));
            return;
        }
        throw new GeometryException("no property '" + prop + "' on " + target);
    }

    private String len(String v) {
        return Inputs.normalize(v, Units.Category.LENGTH, host.lengthUnit());
    }

    private String ang(String v) {
        return Inputs.normalize(v, Units.Category.ANGLE, host.lengthUnit());
    }

    private static boolean truth(String v) {
        return List.of("true", "yes", "on", "1", "oui").contains(v.trim().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------
    // Placement edits
    // ------------------------------------------------------------------

    private PhysicalVolume placement(String name) throws GeometryException {
        PhysicalVolume pv = g.placement(name);
        if (pv == null) {
            final LogicalVolume lv = g.volume(name);
            if (lv != null) {
                final List<PhysicalVolume> ps = g.placementsOf(lv);
                if (ps.size() == 1) {
                    pv = ps.get(0);
                } else if (ps.size() > 1) {
                    throw new GeometryException(name + " is placed " + ps.size() + " times; name the placement");
                }
            }
        }
        if (pv == null) {
            throw new GeometryException("no placement " + name);
        }
        return pv;
    }

    private LogicalVolume volume(String name) throws GeometryException {
        LogicalVolume lv = g.volume(name);
        if (lv == null && g.placement(name) != null) {
            lv = g.placement(name).logical();
        }
        if (lv == null) {
            throw new GeometryException("no volume " + name);
        }
        return lv;
    }

    private Vec3 vec(List<String> a, int from, Units.Category c) throws GeometryException {
        need(a.subList(from, a.size()), 3, "three values");
        return new Vec3(
            Expr.eval(Inputs.normalize(a.get(from), c, host.lengthUnit()), g.scope()),
            Expr.eval(Inputs.normalize(a.get(from + 1), c, host.lengthUnit()), g.scope()),
            Expr.eval(Inputs.normalize(a.get(from + 2), c, host.lengthUnit()), g.scope()));
    }

    private void move(List<String> a, boolean absolute) throws GeometryException {
        need(a, 4, absolute ? "moveto <placement> x y z" : "move <placement> dx dy dz");
        final PhysicalVolume pv = placement(a.get(0));
        final Vec3 v = vec(a, 1, Units.Category.LENGTH);
        host.edit("Move " + pv.name(), () -> {
            final Geometry.Instance mi = Placer.instanceOf(g, pv.mother(), null);
            final Xform mw = mi == null ? Xform.IDENTITY : mi.world();
            final Xform now = mw.compose(pv.transform());
            final Vec3 target = absolute ? v : now.translation().add(v);
            final double[] r = now.rotation();
            Placer.setWorldTransform(pv, mi, new Xform(r, target.x(), target.y(), target.z()), this::len);
        });
    }

    private void rotate(List<String> a) throws GeometryException {
        need(a, 4, "rotate <placement> rx ry rz");
        final PhysicalVolume pv = placement(a.get(0));
        final Vec3 r = vec(a, 1, Units.Category.ANGLE);
        host.edit("Rotate " + pv.name(), () -> {
            final Geometry.Instance mi = Placer.instanceOf(g, pv.mother(), null);
            final Xform mw = mi == null ? Xform.IDENTITY : mi.world();
            final Xform now = mw.compose(pv.transform());
            final double[] m = Xform.mul(Xform.booleanPlacement(0, 0, 0, r.x(), r.y(), r.z()).rotation(), now.rotation());
            final Vec3 t = now.translation();
            Placer.setWorldTransform(pv, mi, new Xform(m, t.x(), t.y(), t.z()), this::len);
        });
    }

    private void copy(List<String> a) throws GeometryException {
        need(a, 1, "copy <placement> [n] [dx dy dz]");
        final PhysicalVolume pv = placement(a.get(0));
        final int n = a.size() > 1 ? (int) Math.round(Expr.eval(a.get(1), g.scope())) : 1;
        final Vec3 d = a.size() >= 5 ? vec(a, 2, Units.Category.LENGTH) : Vec3.ZERO;
        final List<PhysicalVolume> made = new ArrayList<>();
        host.edit("Copy " + pv.name(), () -> {
            final Geometry.Instance mi = Placer.instanceOf(g, pv.mother(), null);
            final Vec3 dl = mi == null ? d : mi.world().applyInverseDir(d);
            for (int i = 1; i <= n; i++) {
                final PhysicalVolume c = g.place(pv.logical(), pv.mother(), g.uniqueName(pv.logical().name() + "_p"));
                final Vec3 p = pv.transform().translation().add(dl.mul(i));
                c.setPosition(shifted(pv.positionExprs()[0], dl.x() * i, p.x()), shifted(pv.positionExprs()[1], dl.y() * i, p.y()),
                    shifted(pv.positionExprs()[2], dl.z() * i, p.z()));
                final String[] r = pv.rotationExprs();
                c.setRotation(r[0], r[1], r[2]);
                c.setReflected(pv.reflected());
                made.add(c);
            }
        });
        host.print(n + " cop" + (n == 1 ? "y" : "ies") + " of " + pv.logical().name()
            + (n >= 3 && d.length() > 0 ? "; a G4PVReplica could replace them if they fill their mother (see advise)" : ""));
        host.select(made);
    }

    /** Keeps an expression parametric when shifting it, or writes the plain value. */
    private String shifted(String expr, double delta, double value) {
        if (Math.abs(delta) < 1e-12) {
            return expr;
        }
        try {
            if (Expr.parse(expr).variables().isEmpty()) {
                return len(value);
            }
        } catch (GeometryException ignored) {
            // keep the text
        }
        return expr + (delta >= 0 ? " + " : " - ") + len(Math.abs(delta));
    }

    private void polar(List<String> a) throws GeometryException {
        need(a, 2, "polar <placement> <n> [total angle]");
        final PhysicalVolume pv = placement(a.get(0));
        final int n = (int) Math.round(Expr.eval(a.get(1), g.scope()));
        final double total = a.size() > 2 ? Expr.eval(Inputs.normalize(a.get(2), Units.Category.ANGLE, "mm"), g.scope()) : Units.TWOPI;
        if (n < 2) {
            throw new GeometryException("polar needs at least 2 copies");
        }
        final boolean full = Math.abs(total - Units.TWOPI) < 1e-9;
        final double step = full ? total / n : total / (n - 1);
        final List<PhysicalVolume> made = new ArrayList<>();
        host.edit("Polar pattern " + pv.name(), () -> {
            for (int i = 1; i < n; i++) {
                final double ang = step * i;
                final PhysicalVolume c = g.place(pv.logical(), pv.mother(), g.uniqueName(pv.logical().name() + "_p"));
                // Rotate the placement about the mother's z axis: position and orientation
                final Xform rz = new Xform(Xform.axisAngle(Vec3.Z, ang), 0, 0, 0);
                final Xform t = rz.compose(pv.transform());
                c.setReflected(pv.reflected());
                Placer.setLocalTransform(c, t, this::len);
                made.add(c);
            }
        });
        made.add(0, pv);
        host.print(n + " copies of " + pv.logical().name() + " around z, every " + Units.num(Math.toDegrees(step)) + " deg");
        host.select(made);
    }

    private void place(List<String> a) throws GeometryException {
        need(a, 1, "place <volume> ...");
        final LogicalVolume lv = volume(a.get(0));
        final Parsed p = parse(a.subList(1, a.size()));
        final LogicalVolume mother = p.in != null ? volume(p.in) : null;
        final PhysicalVolume[] made = new PhysicalVolume[1];
        host.edit("Place " + lv.name(), () -> {
            try {
                final Xform w = p.worldTransform(g);
                final Geometry.Instance mi = mother != null ? Placer.instanceOf(g, mother, null)
                    : Placer.smartMother(g, w.apply(lv.solid().extent()), lv);
                final LogicalVolume m = mi == null ? g.world() : mi.lv();
                if (lv.contains(m)) {
                    throw new IllegalArgumentException("cannot place " + lv.name() + " inside itself");
                }
                made[0] = g.place(lv, m, p.as != null ? p.as : g.uniqueName(lv.name() + "_p"));
                Placer.setWorldTransform(made[0], mi, w, this::len);
            } catch (GeometryException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        });
        if (made[0] != null) {
            host.select(List.of(made[0]));
        }
    }

    private void replica(List<String> a) throws GeometryException {
        // replica <volume> in <mother> along <axis> <n> <width> [offset]
        if (a.size() < 7 || !a.get(1).equalsIgnoreCase("in") || !a.get(3).equalsIgnoreCase("along")) {
            throw new GeometryException(HELP.get("replica"));
        }
        final LogicalVolume lv = volume(a.get(0)), mother = volume(a.get(2));
        final PhysicalVolume.Replica r = new PhysicalVolume.Replica();
        r.setAxis(switch (a.get(4).toLowerCase(Locale.ROOT)) {
            case "x" -> PhysicalVolume.Replica.Axis.kXAxis;
            case "y" -> PhysicalVolume.Replica.Axis.kYAxis;
            case "z" -> PhysicalVolume.Replica.Axis.kZAxis;
            case "phi" -> PhysicalVolume.Replica.Axis.kPhi;
            case "rho", "r" -> PhysicalVolume.Replica.Axis.kRho;
            default -> throw new GeometryException("axis must be x, y, z, rho or phi");
        });
        final boolean angular = r.axis() == PhysicalVolume.Replica.Axis.kPhi;
        r.setNumber(a.get(5));
        r.setWidth(Inputs.normalize(a.get(6), angular ? Units.Category.ANGLE : Units.Category.LENGTH, host.lengthUnit()));
        if (a.size() > 7) {
            r.setOffset(Inputs.normalize(a.get(7), angular ? Units.Category.ANGLE : Units.Category.LENGTH, host.lengthUnit()));
        }
        host.edit("Replica " + lv.name(), () -> g.place(lv, mother, g.uniqueName(lv.name() + "_rep")).setReplica(r));
    }

    // ------------------------------------------------------------------
    // Booleans: two placed volumes become one solid
    // ------------------------------------------------------------------

    private void bool(BooleanSolid.Op op, List<String> a) throws GeometryException {
        need(a, 2, op.tag + " <A> <B> [as name]");
        final PhysicalVolume pa = placement(a.get(0)), pb = placement(a.get(1));
        if (pa == pb) {
            throw new GeometryException("a volume with itself");
        }
        final String as = a.size() >= 4 && a.get(2).equalsIgnoreCase("as") ? a.get(3) : null;
        host.edit(op.g4.substring(2), () -> combine(op, pa, pb, as));
        host.print(op.g4 + " of " + pa.logical().name() + " and " + pb.logical().name() + " -> " + pa.logical().name()
            + " (its solid is now " + pa.logical().solid().name() + ")");
        host.select(List.of(pa));
    }

    /**
     * FreeCAD's Part booleans on Geant4 volumes: A's volume takes a boolean
     * solid of A and B, B placed relative to A; B's placement goes away. The
     * relative transform is computed from both world placements, so A and B
     * may sit in different mothers.
     */
    public void combine(BooleanSolid.Op op, PhysicalVolume pa, PhysicalVolume pb, String as) {
        final Geometry.Instance ia = g.instancesOf(pa).get(0), ib = g.instancesOf(pb).get(0);
        // p_A = T_A^-1 T_B p_B : the second solid's placement in the first's frame (active)
        final Xform rel = ia.world().inverse().compose(ib.world());
        final double[] m = rel.rotation();
        // booleanPlacement(R) is R itself: angles of R directly
        final double[] ang = Xform.gdmlAngles(m);
        final Solid sa = pa.logical().solid(), sb = pb.logical().solid();
        final String name = as != null && !g.nameTaken(as) ? as : g.uniqueName(pa.logical().name() + "_" + op.tag);
        final BooleanSolid b = new BooleanSolid(name, op, sa, sb);
        final Vec3 t = rel.translation();
        b.set("x", len(t.x()));
        b.set("y", len(t.y()));
        b.set("z", len(t.z()));
        b.set("rx", Placer.deg(ang[0]));
        b.set("ry", Placer.deg(ang[1]));
        b.set("rz", Placer.deg(ang[2]));
        g.addSolid(b);
        pa.logical().setSolid(b);
        g.unplace(pb);
        if (g.placementsOf(pb.logical()).isEmpty()) {
            // The volume of B is gone from the tree; its solid lives on inside the boolean
            final LogicalVolume lb = pb.logical();
            for (PhysicalVolume d : new ArrayList<>(lb.daughters())) {
                g.unplace(d);
            }
            g.volumesMap().remove(lb.name());
        }
    }

    // ------------------------------------------------------------------

    private void delete(List<String> a) throws GeometryException {
        need(a, 1, "delete <name> ...");
        host.edit("Delete", () -> {
            for (String n : a) {
                final PhysicalVolume pv = g.placement(n);
                if (pv != null) {
                    g.unplace(pv);
                    if (g.placementsOf(pv.logical()).isEmpty()) {
                        g.deleteVolume(pv.logical());
                    }
                    continue;
                }
                final LogicalVolume lv = g.volume(n);
                if (lv != null) {
                    if (lv == g.world()) {
                        throw new IllegalArgumentException("the world cannot be deleted");
                    }
                    g.deleteVolume(lv);
                    continue;
                }
                if (g.define(n) != null) {
                    g.removeDefine(n);
                    continue;
                }
                final Solid s = g.solid(n);
                if (s != null) {
                    if (!g.users(s).isEmpty()) {
                        throw new IllegalArgumentException("solid " + n + " is used by " + g.users(s));
                    }
                    g.removeSolid(s);
                    continue;
                }
                throw new IllegalArgumentException("nothing named " + n);
            }
        });
    }

    private void rename(List<String> a) throws GeometryException {
        need(a, 2, "rename <old> <new>");
        final String from = a.get(0), to = a.get(1);
        Object target = g.placement(from);
        if (target == null) {
            target = g.volume(from);
        }
        if (target == null) {
            target = g.define(from);
        }
        if (target == null) {
            target = g.solid(from);
        }
        if (target == null && g.materials().isDefined(from)) {
            target = g.materials().material(from);
        }
        if (target == null) {
            throw new GeometryException("nothing named " + from);
        }
        final Object tg = target;
        final boolean[] ok = {false};
        host.edit("Rename " + from, () -> ok[0] = g.rename(tg, to));
        if (!ok[0]) {
            throw new GeometryException("cannot rename to " + to + " (taken or invalid)");
        }
    }

    // ------------------------------------------------------------------
    // Materials
    // ------------------------------------------------------------------

    private void material(List<String> a) throws GeometryException {
        // material <name> <density> atoms|fractions El:n ...
        need(a, 4, HELP.get("material"));
        final String name = a.get(0);
        if (name.startsWith("G4_")) {
            throw new GeometryException("G4_ names belong to Geant4's NIST database; choose another name");
        }
        final double density = Expr.eval(Inputs.normalize(a.get(1), Units.Category.DENSITY, "mm"), g.scope());
        final String mode = a.get(2).toLowerCase(Locale.ROOT);
        final boolean atoms = mode.startsWith("atom") || mode.startsWith("compo");
        if (!atoms && !mode.startsWith("frac") && !mode.startsWith("mass")) {
            throw new GeometryException("third argument: atoms or fractions");
        }
        final Material m = Material.define(name, density / Units.G_PER_CM3);
        for (String part : a.subList(3, a.size())) {
            if (part.contains("=")) {
                final String k = part.substring(0, part.indexOf('=')).toLowerCase(Locale.ROOT);
                final String v = part.substring(part.indexOf('=') + 1);
                switch (k) {
                    case "state" -> m.setState(Material.State.of(v));
                    case "t", "temperature" -> m.setTemperature(Expr.eval(Inputs.normalize(v, Units.Category.NONE, "mm"), g.scope()));
                    case "p", "pressure" -> m.setPressure(Expr.eval(Inputs.normalize(v, Units.Category.NONE, "mm"), g.scope())
                        / (v.matches(".*[a-zA-Z].*") ? Units.ATMOSPHERE : 1));
                    case "i", "mee" -> m.setMeanExcitation(Expr.eval(v, g.scope()));
                    default -> throw new GeometryException("unknown material option " + k);
                }
                continue;
            }
            final int c = part.lastIndexOf(':');
            if (c < 0) {
                throw new GeometryException("components are written Element:amount, e.g. C:8 or G4_WATER:0.3");
            }
            m.addPart(part.substring(0, c), Expr.eval(part.substring(c + 1), g.scope()), atoms);
        }
        host.edit("Material " + name, () -> {
            try {
                g.materials().define(m);
            } catch (GeometryException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        });
        host.print(m.summary());
    }

    private void materials(List<String> a) {
        final String f = a.isEmpty() ? "" : String.join(" ", a);
        for (Material m : g.materials().defined()) {
            host.print("  " + m.summary() + "  (document)");
        }
        final List<Material> list = NistDB.get().search(f);
        host.print(list.size() + " NIST materials" + (f.isEmpty() ? "" : " matching " + f) + ":");
        int k = 0;
        for (Material m : list) {
            if (k++ > 60) {
                host.print("  ... narrow with 'materials <word>'");
                break;
            }
            host.print("  " + m.summary());
        }
    }

    private void x0(List<String> a) throws GeometryException {
        need(a, 1, "x0 <material> [thickness]");
        final Material m = g.materials().material(a.get(0));
        if (m == null) {
            checkMaterial(a.get(0));
            return;
        }
        host.print(m.summary());
        if (a.size() > 1) {
            final double t = Expr.eval(Inputs.normalize(a.get(1), Units.Category.LENGTH, host.lengthUnit()), g.scope());
            host.print(String.format(Locale.ROOT, "  %s of %s = %.4g X0 = %.4g %% X0, %.4g lambda_I",
                Units.best(t, Units.Category.LENGTH), m.name(), t / m.radiationLength(), 100 * t / m.radiationLength(),
                t / m.nuclearInteractionLength()));
        }
    }

    // ------------------------------------------------------------------
    // World, visual attributes, sensitive detectors
    // ------------------------------------------------------------------

    private void world(List<String> a) throws GeometryException {
        need(a, 1, HELP.get("world"));
        final LogicalVolume w = g.world();
        if (a.get(0).equalsIgnoreCase("fit")) {
            final double margin = a.size() > 1 ? Expr.eval(Inputs.normalize(a.get(1), Units.Category.LENGTH, host.lengthUnit()), g.scope()) : -1;
            fitWorld(margin);
            return;
        }
        if (a.get(0).equalsIgnoreCase("material")) {
            need(a, 2, "world material <name>");
            checkMaterial(a.get(1));
            host.edit("World material", () -> w.setMaterial(a.get(1)));
            return;
        }
        need(a, 3, HELP.get("world"));
        if (!"box".equals(w.solid().tag())) {
            throw new GeometryException("the world is not a box; set its parameters with set " + w.solid().name() + ".<param>");
        }
        host.edit("World size", () -> {
            w.solid().set("x", len(a.get(0)));
            w.solid().set("y", len(a.get(1)));
            w.solid().set("z", len(a.get(2)));
            if (a.size() > 3) {
                w.setMaterial(a.get(3));
            }
        });
    }

    /** Shrinks or grows a box world around its content, with a margin (10 % by default). */
    public void fitWorld(double margin) {
        BBox b = BBox.EMPTY;
        for (Geometry.Instance i : g.instances()) {
            if (i.pv() != null) {
                b = b.union(Geometry.worldBox(i));
            }
        }
        if (b.isEmpty()) {
            host.print("the world is empty");
            return;
        }
        final double m = margin >= 0 ? margin : 0.1 * b.diagonal();
        // A world centred on the origin, as Geant4 expects primaries from there
        final double hx = Math.max(Math.abs(b.minX()), Math.abs(b.maxX())) + m;
        final double hy = Math.max(Math.abs(b.minY()), Math.abs(b.maxY())) + m;
        final double hz = Math.max(Math.abs(b.minZ()), Math.abs(b.maxZ())) + m;
        host.edit("Fit world", () -> {
            final Solid s = g.world().solid();
            if (!"box".equals(s.tag())) {
                throw new IllegalArgumentException("the world is not a box");
            }
            s.set("x", len(2 * hx));
            s.set("y", len(2 * hy));
            s.set("z", len(2 * hz));
        });
        host.print("world is now " + len(2 * hx) + " x " + len(2 * hy) + " x " + len(2 * hz));
    }

    private void sensitive(List<String> a) throws GeometryException {
        need(a, 1, HELP.get("sensitive"));
        final LogicalVolume lv = volume(a.get(0));
        final String sd = a.size() > 1 ? a.get(1) : lv.name() + "SD";
        host.edit("Sensitive " + lv.name(), () -> lv.setSensitive(sd.equalsIgnoreCase("none") ? null : sd));
        host.print(lv.name() + (lv.sensitive() == null ? " is no longer sensitive" : " is the sensitive detector " + lv.sensitive()));
    }

    private void color(List<String> a) throws GeometryException {
        need(a, 2, HELP.get("color"));
        final LogicalVolume lv = volume(a.get(0));
        host.edit("Color " + lv.name(), () -> lv.setColor(Gdml.parseColor(a.get(1), lv.color())));
    }

    private void visibility(List<String> a, boolean show) throws GeometryException {
        need(a, 1, show ? "show <volume>" : "hide <volume>");
        final List<LogicalVolume> lvs = new ArrayList<>();
        for (String n : a) {
            lvs.add(volume(n));
        }
        host.edit(show ? "Show" : "Hide", () -> lvs.forEach(lv -> lv.setVisible(show)));
    }

    private void select(List<String> a) throws GeometryException {
        final List<PhysicalVolume> out = new ArrayList<>();
        for (String n : a) {
            final PhysicalVolume pv = g.placement(n);
            if (pv != null) {
                out.add(pv);
            } else if (g.volume(n) != null) {
                out.addAll(g.placementsOf(g.volume(n)));
            } else {
                throw new GeometryException("nothing named " + n);
            }
        }
        host.select(out);
    }

    // ------------------------------------------------------------------
    // Templates, inspection
    // ------------------------------------------------------------------

    private void template(List<String> a) throws GeometryException {
        need(a, 1, HELP.get("template"));
        final Map<String, String> opts = new LinkedHashMap<>();
        for (String s : a.subList(1, a.size())) {
            final int k = s.indexOf('=');
            if (k > 0) {
                opts.put(s.substring(0, k).toLowerCase(Locale.ROOT), s.substring(k + 1));
            }
        }
        final String kind = a.get(0).toLowerCase(Locale.ROOT);
        final List<String> made = new ArrayList<>();
        host.edit("Template " + kind, () -> {
            try {
                made.addAll(Templates.build(g, kind, opts, host.lengthUnit()));
            } catch (GeometryException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        });
        host.print("template " + kind + ": " + made.size() + " volumes; its sizes are variables (vars) you can change");
        final List<PhysicalVolume> sel = new ArrayList<>();
        for (String n : made) {
            if (g.placement(n) != null) {
                sel.add(g.placement(n));
            }
        }
        host.select(sel);
    }

    private void info(List<String> a) throws GeometryException {
        need(a, 1, "info <name>");
        final String n = a.get(0);
        final PhysicalVolume pv = g.placement(n);
        final LogicalVolume lv = pv != null ? pv.logical() : g.volume(n);
        if (lv != null) {
            final Solid s = lv.solid();
            final Material m = g.materials().material(lv.material());
            if (pv != null) {
                host.print("placement " + pv.name() + " of " + lv.name() + " in " + pv.mother().name() + ", copy " + pv.copyNumber()
                    + ", at (" + String.join(", ", pv.positionExprs()) + "), rotation (" + String.join(", ", pv.rotationExprs()) + ")"
                    + (pv.reflected() ? ", mirrored through its z = 0 plane (G4ReflectionFactory)" : ""));
            }
            host.print("volume " + lv.name() + ": " + (s == null ? "no solid" : s.g4Class() + " " + s.name()) + ", " + lv.material()
                + (lv.sensitive() != null ? ", sensitive detector " + lv.sensitive() : ""));
            if (s != null && s.error() == null) {
                final double v = s.cubicVolume();
                host.print("  volume " + Units.best(v, Units.Category.VOLUME) + ", surface " + Units.best(s.surfaceArea(),
                    Units.Category.AREA) + ", mass with daughters " + Units.best(Mass.of(g, lv), Units.Category.MASS));
                final BBox e = s.extent();
                host.print("  extent " + Units.best(e.size().x(), Units.Category.LENGTH) + " x " + Units.best(e.size().y(),
                    Units.Category.LENGTH) + " x " + Units.best(e.size().z(), Units.Category.LENGTH));
                for (Solid.Param p : s.params()) {
                    host.print("  " + p.key + " = " + p.expr() + "   (" + Units.best(p.value(), p.kind.category) + ")");
                }
            }
            if (m != null) {
                host.print("  " + m.summary());
            }
            return;
        }
        final Solid s = g.solid(n);
        if (s != null) {
            host.print(s.g4Class() + " " + s.name() + (s.error() != null ? " ERROR " + s.error() : ", volume "
                + Units.best(s.cubicVolume(), Units.Category.VOLUME)) + ", used by " + g.users(s));
            return;
        }
        final Define d = g.define(n);
        if (d != null) {
            host.print("variable " + d.name() + " = " + d.effectiveExpr() + " = " + Units.num(d.value()));
            return;
        }
        final Material m = g.materials().material(n);
        if (m != null) {
            host.print(m.summary());
            return;
        }
        throw new GeometryException("nothing named " + n);
    }

    private void list() {
        if (g.world() == null) {
            return;
        }
        listRec(g.world(), "", null);
    }

    private void listRec(LogicalVolume lv, String indent, PhysicalVolume via) {
        host.print(indent + (via == null ? "" : via.name() + " -> ") + lv.name() + " [" + (lv.solid() == null ? "?" : lv.solid().g4Class())
            + ", " + lv.material() + "]" + (via != null && via.isReplica() ? " x" + via.copies() : ""));
        if (indent.length() > 60) {
            return;
        }
        for (PhysicalVolume pv : lv.daughters()) {
            listRec(pv.logical(), indent + "  ", pv);
        }
    }

    // ------------------------------------------------------------------

    private static void need(List<String> a, int n, String usage) throws GeometryException {
        if (a.size() < n) {
            throw new GeometryException("usage: " + usage);
        }
    }

    /** Splits on spaces, keeping quoted strings and keeping "a = b" as one assignment where it matters. */
    static List<String> tokens(String line) {
        final List<String> out = new ArrayList<>();
        final StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        int depth = 0;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            if (Character.isWhitespace(c) && !quoted && depth <= 0) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        // "10 cm" -> "10cm", "rmin = 5" -> "rmin=5"
        final List<String> merged = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            final String s = out.get(i);
            if (i + 1 < out.size() && Inputs.looksNumeric(s) && !s.matches(".*[a-zA-Z].*")
                && isUnitWord(out.get(i + 1))) {
                merged.add(s + out.get(i + 1));
                i++;
            } else if (i + 2 < out.size() && out.get(i + 1).equals("=") && s.matches("[A-Za-z_][A-Za-z0-9_]*")
                && i > 0) {
                merged.add(s + "=" + out.get(i + 2));
                i += 2;
            } else {
                merged.add(s);
            }
        }
        return merged;
    }

    private static boolean isUnitWord(String w) {
        for (String p : w.split("/")) {
            if (!Units.isReserved(p) || p.equals("e") || p.equals("pi")) {
                return false;
            }
        }
        return true;
    }
}

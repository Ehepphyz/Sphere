package com.sphere.core.geant4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C++ for volumes drawn in 3D or brought from a G4editor design, written into code that already
 * exists: each volume with what was given to it (solid and its expressions, material, colour,
 * visibility, wireframe, sensitivity, position, rotation, mirror, copy number, replicas) and the
 * volumes it holds. The design's variables become {@code const G4double}; every name is new to
 * the code it goes into, a variable already taken there renamed in the expressions that read it.
 * What the code reading draws from the result is what was drawn.
 */
public final class CppInsert {

    /**
     * The lines to write, the headers they need, the volumes made, and those to make sensitive (volume name
     * to detector name), written apart in ConstructSDandField() by {@link #sensitiveCode}.
     */
    public record Code(String text, Set<String> headers, List<String> volumes, Map<String, String> sensitive) {
    }

    private final Geometry design;
    private final Set<String> used = new HashSet<>();
    /** The identifiers of the code written into. */
    private final Set<String> code;
    private final Set<String> codeMaterials;
    /** A volume the code already makes: the C++ that reaches it; null for a volume to write. */
    private final Function<LogicalVolume, String> known;
    private final Map<Object, String> ids = new IdentityHashMap<>();
    private final Map<String, String> matIds = new HashMap<>();
    private final List<String> made = new ArrayList<>();
    private final Set<PhysicalVolume> placed = new HashSet<>();
    private final Map<String, String> sensitive = new LinkedHashMap<>();

    /**
     * @param design        the geometry the volumes come from (its variables, materials and elements)
     * @param taken         the identifiers of the code written into: none is reused
     * @param codeMaterials materials the code defines itself, reached by G4Material::GetMaterial
     * @param known         volumes the code already makes, as C++ (a variable, a store lookup); null for new ones
     */
    public CppInsert(Geometry design, Set<String> taken, Set<String> codeMaterials, Function<LogicalVolume, String> known) {
        this.design = design;
        this.code = Set.copyOf(taken);
        this.used.addAll(taken);
        this.codeMaterials = codeMaterials;
        this.known = known == null ? lv -> null : known;
        for (Define d : design.defines()) {
            used.add(d.name());
        }
    }

    // ------------------------------------------------------------------
    // The code
    // ------------------------------------------------------------------

    /** The code placing these volumes, and all they hold, in the mother reached by {@code mother}. */
    public Code place(List<PhysicalVolume> pvs, String mother, String indent, String comment) {
        final StringBuilder body = new StringBuilder();
        final Set<LogicalVolume> written = new HashSet<>();
        for (PhysicalVolume pv : pvs) {
            volume(body, pv.logical(), written);
            placement(body, pv, mother);
        }
        // the design's variables read by the code, those they read first
        final Map<String, Define> all = new LinkedHashMap<>();
        for (Define d : design.defines()) {
            all.put(d.name(), d);
        }
        final Set<String> needed = new LinkedHashSet<>();
        for (String id : identifiers(body.toString())) {
            order(id, all, needed, new HashSet<>());
        }
        // a variable named as one of the code's is renamed; the others keep their names
        final Map<String, String> renames = new HashMap<>();
        final StringBuilder defs = new StringBuilder();
        for (String n : needed) {
            final String id = CppExport.cppId(n);
            final String to = code.contains(id) ? fresh(id) : id;
            if (!to.equals(n)) {
                renames.put(n, to);
            }
        }
        for (String n : needed) {
            final Define d = all.get(n);
            if (d.comment() != null && !d.comment().isBlank()) {
                defs.append("// ").append(d.comment().replace('\n', ' ')).append('\n');
            }
            defs.append("const G4double ").append(renames.getOrDefault(n, n)).append(" = ")
                .append(rename(cpp(d.effectiveExpr()), renames)).append(";\n");
        }
        final String text = (comment == null ? "" : "// " + comment + "\n") + defs + rename(body.toString(), renames);
        final StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!line.isEmpty()) {
                out.append(indent).append(line).append('\n');
            }
        }
        return new Code(out.toString(), headers(out.toString()), List.copyOf(made), Map.copyOf(sensitive));
    }

    private void order(String n, Map<String, Define> all, Set<String> done, Set<String> visiting) {
        if (done.contains(n) || !all.containsKey(n) || !visiting.add(n)) {
            return;
        }
        try {
            for (String v : Expr.parse(all.get(n).effectiveExpr()).variables()) {
                order(v, all, done, visiting);
            }
        } catch (GeometryException ignored) {
            // written anyway; the compiler and the reading will say
        }
        done.add(n);
    }

    private void volume(StringBuilder b, LogicalVolume lv, Set<LogicalVolume> written) {
        if (known.apply(lv) != null || !written.add(lv)) {
            return;
        }
        final String mat = material(b, lv.material(), new HashSet<>());
        solid(b, lv.solid(), new HashSet<>());
        final String v = id(lv, "lv", lv.name());
        made.add(lv.name());
        b.append("auto* ").append(v).append(" = new G4LogicalVolume(").append(id(lv.solid(), "s", lv.solid().name()))
            .append(", ").append(mat).append(", ").append(string(lv.name())).append(");\n");
        b.append(v).append("->SetVisAttributes(").append(vis(lv)).append(");\n");
        if (lv.sensitive() != null && !lv.sensitive().isBlank()) {
            sensitive.put(lv.name(), lv.sensitive());   // Geant4 wants it in ConstructSDandField(), per thread
        }
        // what it holds
        for (PhysicalVolume d : design.placements()) {
            if (d.mother() == lv) {
                volume(b, d.logical(), written);
                placement(b, d, v);
            }
        }
    }

    private void solid(StringBuilder b, Solid s, Set<Solid> done) {
        if (s == null || ids.containsKey(s) || !done.add(s)) {
            return;
        }
        for (Solid op : s.operands()) {
            solid(b, op, done);
        }
        final String v = id(s, "s", s.name());
        b.append("auto* ").append(v).append(" = ").append(s.cpp(names)).append(";\n");
    }

    private final Solid.CppNames names = new Solid.CppNames() {
        public String solid(Solid s) {
            return id(s, "s", s.name());
        }

        public String string(String s) {
            return CppInsert.string(s);
        }
    };

    /** The variable holding a material, written first when the code has no such material. */
    private String material(StringBuilder b, String name, Set<String> visiting) {
        final String have = matIds.get(name);
        if (have != null) {
            return have;
        }
        if (codeMaterials.contains(name)) {
            return materialExpr(name, codeMaterials);
        }
        final String v = fresh("mat_" + CppExport.cppId(name));
        if (!design.materials().isDefined(name) || !visiting.add(name)) {
            b.append("auto* ").append(v).append(" = ").append(materialExpr(name, codeMaterials)).append(";\n");
            matIds.put(name, v);
            return v;
        }
        final Material m = design.materials().material(name);
        // materials made of other materials of the design: those first
        final Map<String, String> parts = new HashMap<>();
        for (Material.Part p : m.parts()) {
            if (design.materials().element(p.ref()) == null) {
                parts.put(p.ref(), material(b, p.ref(), visiting));
            }
        }
        final String state = switch (m.state()) {
            case GAS -> "kStateGas";
            case LIQUID -> "kStateLiquid";
            case SOLID -> "kStateSolid";
            default -> "kStateUndefined";
        };
        b.append("auto* ").append(v).append(" = new G4Material(").append(string(name)).append(", ")
            .append(Units.exact(m.densityGcm3())).append(" * g/cm3, ").append(m.parts().size()).append(", ").append(state)
            .append(", ").append(Units.exact(m.temperature())).append(" * kelvin, ").append(Units.exact(m.pressure()))
            .append(" * atmosphere);\n");
        for (Material.Part p : m.parts()) {
            final Element e = design.materials().element(p.ref());
            if (e != null) {
                final String el = e.nist ? "G4NistManager::Instance()->FindOrBuildElement(" + string(e.symbol) + ")"
                    : "new G4Element(" + string(e.name) + ", " + string(e.symbol) + ", " + e.z + ", "
                    + Units.exact(e.a) + " * g/mole)";
                b.append(v).append("->AddElement(").append(el).append(", ")
                    .append(p.byAtoms() ? String.valueOf(Math.round(p.amount())) : Units.exact(p.amount())).append(");\n");
            } else {
                b.append(v).append("->AddMaterial(").append(parts.get(p.ref())).append(", ").append(Units.exact(p.amount()))
                    .append(");\n");
            }
        }
        if (m.meanExcitation() > 0) {
            b.append(v).append("->GetIonisation()->SetMeanExcitationEnergy(").append(Units.exact(m.meanExcitation()))
                .append(" * eV);\n");
        }
        matIds.put(name, v);
        return v;
    }

    private void placement(StringBuilder b, PhysicalVolume pv, String mother) {
        if (!placed.add(pv)) {
            return;   // written with its mother
        }
        final String lvKnown = known.apply(pv.logical());
        final String lv = lvKnown != null ? lvKnown : id(pv.logical(), "lv", pv.logical().name());
        if (pv.isReplica()) {
            final PhysicalVolume.Replica r = pv.replica();
            b.append("new G4PVReplica(").append(string(pv.name())).append(", ").append(lv).append(", ").append(mother)
                .append(", ").append(r.axis().name()).append(", static_cast<G4int>(").append(cpp(r.number())).append("), ")
                .append(cpp(r.width())).append(", ").append(cpp(r.offset())).append(");\n");
            return;
        }
        final String pos = position(pv.positionExprs());
        if (pv.reflected()) {
            // mirrored: G4ReflectionFactory makes the reflected volumes, as G4GDMLReadStructure does
            b.append("G4ReflectionFactory::Instance()->Place(G4Transform3D(").append(objectRotation(pv.rotationExprs()))
                .append(", ").append(pos).append(") * G4ReflectZ3D(), ").append(string(pv.name())).append(", ").append(lv)
                .append(", ").append(mother).append(", false, ").append(pv.copyNumber()).append(", true);\n");
            return;
        }
        b.append("new G4PVPlacement(").append(rotation(pv.rotationExprs())).append(", ").append(pos).append(", ").append(lv)
            .append(", ").append(string(pv.name())).append(", ").append(mother).append(", false, ").append(pv.copyNumber())
            .append(", true);\n");
    }

    // ------------------------------------------------------------------
    // Expressions, shared with the rewrites of characteristics
    // ------------------------------------------------------------------

    /** C++ of a design expression. */
    public static String cpp(String expr) {
        try {
            return Expr.parse(expr).toCpp();
        } catch (GeometryException e) {
            return "0. /* invalid: " + expr.replace("*/", "") + " */";
        }
    }

    /** "G4ThreeVector(x, y, z)" of position expressions. */
    public static String position(String[] p) {
        if (Gdml.isZero(p[0]) && Gdml.isZero(p[1]) && Gdml.isZero(p[2])) {
            return "G4ThreeVector()";
        }
        return "G4ThreeVector(" + cpp(p[0]) + ", " + cpp(p[1]) + ", " + cpp(p[2]) + ")";
    }

    /**
     * The frame rotation G4PVPlacement takes, GDML's angles (Rz Ry Rx): rotateX, then Y, then Z, each
     * premultiplying as CLHEP does; nullptr when there is none. One expression, so that a turn made in
     * 3D later rewrites it whole.
     */
    public static String rotation(String[] r) {
        final StringBuilder b = new StringBuilder();
        final char[] axes = {'X', 'Y', 'Z'};
        for (int i = 0; i < 3; i++) {
            if (!Gdml.isZero(r[i])) {
                b.append(".rotate").append(axes[i]).append('(').append(cpp(r[i])).append(')');
            }
        }
        return b.length() == 0 ? "nullptr" : "new G4RotationMatrix(G4RotationMatrix()" + b + ")";
    }

    /** The volume's own rotation, the frame's inverse: Rz(-z) first, then Y, then X. */
    public static String objectRotation(String[] r) {
        final StringBuilder b = new StringBuilder("G4RotationMatrix()");
        final char[] axes = {'X', 'Y', 'Z'};
        for (int i = 2; i >= 0; i--) {
            if (!Gdml.isZero(r[i])) {
                b.append(".rotate").append(axes[i]).append("(-(").append(cpp(r[i])).append("))");
            }
        }
        return b.toString();
    }

    /** The material named, as the code reaches it: its own by G4Material::GetMaterial, NIST's by the manager. */
    public static String materialExpr(String name, Set<String> codeMaterials) {
        return codeMaterials.contains(name) ? "G4Material::GetMaterial(" + string(name) + ")"
            : "G4NistManager::Instance()->FindOrBuildMaterial(" + string(name) + ")";
    }

    /**
     * How a volume is drawn, as one expression: its colour, hidden or not, and its wireframe (set in an
     * immediately called lambda, G4VisAttributes having no constructor for it).
     */
    public static String vis(LogicalVolume lv) {
        final int c = lv.color();
        final String colour = "G4Colour(" + f(((c >> 16) & 0xFF) / 255.0) + ", " + f(((c >> 8) & 0xFF) / 255.0) + ", "
            + f((c & 0xFF) / 255.0) + (((c >>> 24) & 0xFF) == 255 ? "" : ", " + f(((c >>> 24) & 0xFF) / 255.0)) + ")";
        if (!lv.wireframe()) {
            return "new G4VisAttributes(" + (lv.visible() ? "" : "false, ") + colour + ")";
        }
        return "[] { auto* va = new G4VisAttributes(" + (lv.visible() ? "" : "false, ") + colour
            + "); va->SetForceWireframe(true); return va; }()";
    }

    /** Three decimals, enough for a colour read back to the same byte: 0.500 is 0.5, 1.000 is 1. */
    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v).replaceAll("0+$", "");
    }

    /**
     * One argument of a solid's constructor, given the parameter it makes now: the parameter divided by
     * {@code factor} (G4's half-lengths are half of GDML's lengths).
     */
    public static String argument(String expr, int factor) {
        final String c = cpp(expr);
        return factor == 1 ? c : factor == 2 ? "0.5*(" + c + ")" : "(" + c + ") / " + factor;
    }

    private static final Pattern NEW = Pattern.compile("\\s*new\\s+(\\w+)\\s*\\(");

    /**
     * A solid's constructor written whole with its parameters now, under the name the code gives it; null
     * for a boolean, or when the class would change (a G4Polycone of (r, z) corners is a G4GenericPolycone
     * here: the variable holding it would no longer compile).
     */
    public static String constructor(Solid s, String written, String codeName) {
        if (!s.operands().isEmpty()) {
            return null;
        }
        final String now = s.cpp(new Solid.CppNames() {
            public String solid(Solid x) {
                return null;
            }

            public String string(String x) {
                return CppInsert.string(x.equals(s.name()) && codeName != null ? codeName : x);
            }
        });
        final Matcher a = NEW.matcher(written), b = NEW.matcher(now);
        return a.lookingAt() && b.lookingAt() && a.group(1).equals(b.group(1)) ? now : null;
    }

    /** A material by name, as code: the code's own, NIST's; null for a material neither knows. */
    public static String materialExpr(String name, Set<String> codeMaterials, Geometry g) {
        if (codeMaterials.contains(name) || g.materials().nist().material(name) != null || name.startsWith("G4_")) {
            return materialExpr(name, codeMaterials);
        }
        return null;
    }

    /** A volume the code makes, reached by its name in Geant4's store of logical volumes. */
    public static String storeLookup(LogicalVolume lv) {
        return "G4LogicalVolumeStore::GetInstance()->GetVolume(" + string(lv.name()) + ")";
    }

    // ------------------------------------------------------------------
    // Sensitive detectors
    // ------------------------------------------------------------------

    /**
     * Volumes made sensitive, as ConstructSDandField() code: per detector name, the detector G4SDManager
     * already holds, else a new G4MultiFunctionalDetector scoring the energy deposited ("Edep"); then
     * SetSensitiveDetector(volume name, detector, true) -- every volume of that name, as the reading does.
     */
    public static String sensitiveCode(Map<String, String> volumes, Set<String> taken) {
        final Map<String, List<String>> byDetector = new LinkedHashMap<>();
        volumes.forEach((lv, sd) -> byDetector.computeIfAbsent(sd, k -> new ArrayList<>()).add(lv));
        final Set<String> used = new HashSet<>(taken);
        final StringBuilder b = new StringBuilder();
        for (Map.Entry<String, List<String>> e : byDetector.entrySet()) {
            final String sd = e.getKey();
            final String v = fresh(used, "sd_" + CppExport.cppId(sd)), m = fresh(used, "mfd_" + CppExport.cppId(sd));
            b.append("// ").append(String.join(", ", e.getValue())).append(": sensitive, the energy deposited scored as")
                .append(" \"Edep\" by ").append(sd).append('\n');
            b.append("auto* ").append(v).append(" = G4SDManager::GetSDMpointer()->FindSensitiveDetector(").append(string(sd))
                .append(", false);\n");
            b.append("if (!").append(v).append(") {\n");
            b.append("  auto* ").append(m).append(" = new G4MultiFunctionalDetector(").append(string(sd)).append(");\n");
            b.append("  ").append(m).append("->RegisterPrimitive(new G4PSEnergyDeposit(\"Edep\"));\n");
            b.append("  G4SDManager::GetSDMpointer()->AddNewDetector(").append(m).append(");\n");
            b.append("  ").append(v).append(" = ").append(m).append(";\n");
            b.append("}\n");
            for (String lv : e.getValue()) {
                b.append("SetSensitiveDetector(").append(string(lv)).append(", ").append(v).append(", true);\n");
            }
        }
        return b.toString();
    }

    /** Each line of a piece of code indented. */
    public static String indent(String code, String indent) {
        final StringBuilder out = new StringBuilder();
        for (String line : code.split("\n", -1)) {
            if (!line.isEmpty()) {
                out.append(indent).append(line).append('\n');
            }
        }
        return out.toString();
    }

    private static final Pattern DEFINITION = Pattern.compile("([A-Za-z_][\\w:]*)::Construct\\s*\\(\\s*\\)");

    /**
     * Where a ConstructSDandField() of its own goes when the class has none: its definition right after
     * Construct()'s (same file, same namespace), and its declaration after Construct()'s in the class.
     * Returns {offset, text} for the definition, or null when Construct()'s definition is not found.
     */
    public static Object[] sdDefinition(String source, int constructBodyStart, int constructBodyEnd, String code) {
        String qualifier = null;
        final Matcher m = DEFINITION.matcher(source.substring(0, Math.max(0, constructBodyStart)));
        while (m.find()) {
            qualifier = m.group(1);
        }
        if (qualifier == null) {
            return null;
        }
        return new Object[] {constructBodyEnd, "\n\nvoid " + qualifier + "::ConstructSDandField()\n{\n"
            + indent(code, "  ") + "}"};
    }

    /**
     * Where the declaration goes in a class's text: {offset, text} after the line declaring Construct();
     * null when the class or that line is not there, or when the class already declares ConstructSDandField.
     */
    public static Object[] sdDeclaration(String header, String cls) {
        final Matcher c = Pattern.compile("\\bclass\\s+" + Pattern.quote(cls) + "\\b[^;{]*\\{").matcher(header);
        if (!c.find()) {
            return null;
        }
        final int end = header.indexOf("};", c.end());
        final String body = header.substring(c.end(), end < 0 ? header.length() : end);
        if (body.contains("ConstructSDandField")) {
            return null;
        }
        final Matcher d = Pattern.compile("(?m)^([ \\t]*)[^\\n]*\\bConstruct\\s*\\(\\s*\\)[^\\n]*;[^\\n]*$").matcher(body);
        if (!d.find()) {
            return null;
        }
        final int eol = c.end() + d.end();
        return new Object[] {eol, "\n" + d.group(1) + "void ConstructSDandField() override;"};
    }

    private static String fresh(Set<String> used, String base) {
        String n = base;
        for (int i = 2; used.contains(n); i++) {
            n = base + "_" + i;
        }
        used.add(n);
        return n;
    }

    /** A C++ string literal. */
    public static String string(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    private String id(Object o, String prefix, String name) {
        return ids.computeIfAbsent(o, k -> fresh(prefix + "_" + CppExport.cppId(name)));
    }

    private String fresh(String base) {
        String n = base;
        for (int i = 2; used.contains(n); i++) {
            n = base + (base.endsWith("_") ? "" : "_") + i;
        }
        used.add(n);
        return n;
    }

    private static final Pattern WORD = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"|[A-Za-z_][A-Za-z0-9_]*");

    /** The identifiers of a text, string literals left out. */
    public static Set<String> identifiers(String text) {
        final Set<String> out = new LinkedHashSet<>();
        final Matcher m = WORD.matcher(text);
        while (m.find()) {
            if (m.group().charAt(0) != '"') {
                out.add(m.group());
            }
        }
        return out;
    }

    /** The identifiers of code, comments and strings left out. */
    public static Set<String> codeIdentifiers(String code) {
        return identifiers(code.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " "));
    }

    private static String rename(String text, Map<String, String> renames) {
        if (renames.isEmpty()) {
            return text;
        }
        final Matcher m = WORD.matcher(text);
        final StringBuilder b = new StringBuilder();
        while (m.find()) {
            final String w = m.group();
            m.appendReplacement(b, Matcher.quoteReplacement(w.charAt(0) == '"' ? w : renames.getOrDefault(w, w)));
        }
        m.appendTail(b);
        return b.toString();
    }

    // ------------------------------------------------------------------
    // Headers
    // ------------------------------------------------------------------

    private static final Pattern CLASS = Pattern.compile("\\b(G4[A-Z]\\w*)\\s*(?:\\(|::|\\{)");
    private static final Set<String> IN_TRANSFORM = Set.of("G4ReflectX3D", "G4ReflectY3D", "G4ReflectZ3D", "G4Translate3D",
        "G4Rotate3D", "G4RotateX3D", "G4RotateY3D", "G4RotateZ3D", "G4Scale3D", "G4ScaleX3D", "G4ScaleY3D", "G4ScaleZ3D");

    /** The Geant4 headers a piece of code needs, by the classes it names, and the units. */
    public static Set<String> headers(String code) {
        final Set<String> out = new TreeSet<>();
        final Matcher m = CLASS.matcher(code);
        while (m.find()) {
            final String c = m.group(1);
            out.add(IN_TRANSFORM.contains(c) ? "G4Transform3D.hh" : c.equals("G4Color") ? "G4Colour.hh" : c + ".hh");
        }
        out.add("G4SystemOfUnits.hh");
        return out;
    }

    private static final Pattern INCLUDE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*include[ \\t]*[\"<]([^\">]+)[\">].*$");

    /**
     * The include lines a file lacks, and where they go: after its last #include (or at its top).
     * Returns null when nothing is missing.
     */
    public static Object[] includes(String fileText, Set<String> headers) {
        final Set<String> have = new HashSet<>();
        int after = 0;
        final Matcher m = INCLUDE.matcher(fileText);
        while (m.find()) {
            final String h = m.group(1);
            have.add(h.contains("/") ? h.substring(h.lastIndexOf('/') + 1) : h);
            after = m.end();
        }
        final StringBuilder b = new StringBuilder();
        for (String h : headers) {
            if (!have.contains(h)) {
                b.append("#include \"").append(h).append("\"\n");
            }
        }
        if (b.length() == 0) {
            return null;
        }
        if (after > 0) {
            // after the last include line, its line break kept
            final int eol = fileText.indexOf('\n', after);
            return new Object[] {eol < 0 ? fileText.length() : eol + 1, eol < 0 ? "\n" + b : b.toString()};
        }
        return new Object[] {0, b + "\n"};
    }
}

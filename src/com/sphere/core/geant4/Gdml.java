package com.sphere.core.geant4;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GDML, Geant4's geometry description: what G4GDMLParser reads and writes.
 *
 * The editor's documents are GDML files. Expressions are written as they were
 * typed, with CLHEP's unit names, so the file stays parametric for Geant4 too:
 * G4GDMLEvaluator evaluates "2*halfX" or "10*cm" exactly as the editor does.
 * Colours, sensitive detectors and editor settings travel as auxiliary tags,
 * which Geant4 keeps and hands to the application.
 */
public final class Gdml {

    private Gdml() {
    }

    public static final String SCHEMA =
        "http://service-spi.web.cern.ch/service-spi/app/releases/GDML/schema/gdml.xsd";

    // ==================================================================
    // Writing
    // ==================================================================

    public static void write(Geometry g, Path file) throws IOException {
        Files.writeString(file, write(g), StandardCharsets.UTF_8);
    }

    public static String write(Geometry g) {
        final StringBuilder b = new StringBuilder(8192);
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<!-- ").append(esc(g.title)).append(": written by Sphere G4editor -->\n");
        b.append("<gdml xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"")
         .append(SCHEMA).append("\">\n\n");

        // define
        b.append("  <define>\n");
        for (Define d : g.defines.values()) {
            if (d.comment != null && !d.comment.isBlank()) {
                b.append("    <!-- ").append(esc(d.comment)).append(" -->\n");
            }
            switch (d.kind) {
                case CONSTANT -> b.append("    <constant name=\"").append(esc(d.name)).append("\" value=\"")
                                 .append(esc(gdmlExpr(d.expr))).append("\"/>\n");
                case VARIABLE -> b.append("    <variable name=\"").append(esc(d.name)).append("\" value=\"")
                                 .append(esc(gdmlExpr(d.expr))).append("\"/>\n");
                case QUANTITY -> b.append("    <quantity name=\"").append(esc(d.name)).append("\" value=\"")
                                 .append(esc(gdmlExpr(d.expr))).append("\" unit=\"")
                                 .append(esc(d.unit == null ? "mm" : d.unit)).append("\" type=\"")
                                 .append(esc(d.type == null ? "length" : d.type)).append("\"/>\n");
                case EXPRESSION -> b.append("    <expression name=\"").append(esc(d.name)).append("\">")
                                   .append(esc(gdmlExpr(d.expr))).append("</expression>\n");
            }
        }
        for (Geometry.Matrix m : g.matrices.values()) {
            b.append("    <matrix name=\"").append(esc(m.name())).append("\" coldim=\"").append(m.coldim())
             .append("\" values=\"").append(esc(m.values())).append("\"/>\n");
        }
        for (Solid s : g.solids.values()) {
            if (s instanceof Convex.Tet) {
                for (int v = 1; v <= 4; v++) {
                    b.append("    <position name=\"").append(esc(s.name())).append("_v").append(v)
                     .append("\" unit=\"mm\" x=\"").append(esc(gdmlExpr(s.param("v" + v + "x").expr())))
                     .append("\" y=\"").append(esc(gdmlExpr(s.param("v" + v + "y").expr())))
                     .append("\" z=\"").append(esc(gdmlExpr(s.param("v" + v + "z").expr()))).append("\"/>\n");
                }
            } else if (s instanceof Tessellated) {
                // a tessellated solid's facets name their corners, which are positions
                final Solid.Table t = s.table("vertex");
                for (int v = 0; v < t.size(); v++) {
                    final String[] row = t.rows().get(v);
                    b.append("    <position name=\"").append(esc(s.name())).append("_v").append(v)
                     .append("\" unit=\"mm\" x=\"").append(esc(gdmlExpr(row[0]))).append("\" y=\"")
                     .append(esc(gdmlExpr(row[1]))).append("\" z=\"").append(esc(gdmlExpr(row[2]))).append("\"/>\n");
                }
            }
        }
        b.append("  </define>\n\n");

        // materials
        b.append("  <materials>\n");
        final Set<Element> written = new HashSet<>();
        for (Element e : g.materials.definedElements()) {
            writeElement(b, e);
            written.add(e);
        }
        for (Material m : g.materials.defined()) {
            b.append("    <material name=\"").append(esc(m.name()));
            if (!m.formula().isEmpty()) {
                b.append("\" formula=\"").append(esc(m.formula()));
            }
            b.append("\" state=\"").append(m.state().gdml)
             .append("\">\n");
            if (m.state() == Material.State.GAS || m.temperature() != 293.15) {
                b.append("      <T value=\"").append(Units.exact(m.temperature())).append("\" unit=\"K\"/>\n");
            }
            if (m.pressure() != 1.0) {
                b.append("      <P value=\"").append(Units.exact(m.pressure())).append("\" unit=\"atmosphere\"/>\n");
            }
            if (m.meanExcitation() > 0) {
                b.append("      <MEE value=\"").append(Units.exact(m.meanExcitation())).append("\" unit=\"eV\"/>\n");
            }
            b.append("      <D value=\"").append(Units.exact(m.densityGcm3())).append("\" unit=\"g/cm3\"/>\n");
            for (Material.Part p : m.parts()) {
                b.append("      <").append(p.byAtoms() ? "composite" : "fraction").append(" n=\"")
                 .append(p.byAtoms() ? Units.exact(Math.rint(p.amount())) : Units.exact(p.amount()))
                 .append("\" ref=\"").append(esc(p.ref())).append("\"/>\n");
            }
            for (Map.Entry<String, String> e : m.properties.entrySet()) {
                b.append("      <property name=\"").append(esc(e.getKey())).append("\" ref=\"").append(esc(e.getValue()))
                 .append("\"/>\n");
            }
            b.append("    </material>\n");
        }
        b.append("  </materials>\n\n");

        // solids, operands first
        b.append("  <solids>\n");
        final Set<Solid> done = new HashSet<>();
        for (Solid s : g.solids.values()) {
            writeSolid(b, s, done);
        }
        for (String s : g.surfaces) {
            verbatim(b, s, "    ");
        }
        b.append("  </solids>\n\n");

        // structure, daughters before mothers
        b.append("  <structure>\n");
        final Set<LogicalVolume> vdone = new LinkedHashSet<>();
        for (LogicalVolume lv : g.volumes.values()) {
            if (lv != g.world) {
                writeVolume(b, lv, vdone);
            }
        }
        if (g.world != null) {
            writeVolume(b, g.world, vdone);
        }
        for (String s : g.surfaceLinks) {
            verbatim(b, s, "    ");
        }
        b.append("  </structure>\n\n");

        b.append("  <userinfo>\n");
        // the document's name, which GDML has no place for (Geant4 skips auxiliaries it does not know)
        b.append("    <auxiliary auxtype=\"G4editorTitle\" auxvalue=\"").append(esc(g.title)).append("\"/>\n");
        for (Map.Entry<String, String> e : g.settings.entrySet()) {
            b.append("    <auxiliary auxtype=\"G4editor\" auxvalue=\"").append(esc(e.getKey())).append("\" auxunit=\"")
             .append(esc(e.getValue())).append("\"/>\n");
        }
        b.append("  </userinfo>\n\n");

        b.append("  <setup name=\"Default\" version=\"1.0\">\n");
        b.append("    <world ref=\"").append(esc(g.world == null ? "World" : g.world.name)).append("\"/>\n");
        b.append("  </setup>\n");
        b.append("</gdml>\n");
        return b.toString();
    }

    // ==================================================================
    // Portable GDML
    // ==================================================================

    /**
     * GDML every reader takes, ROOT's TGDMLParse as well as Geant4's: the
     * document as numbers (mm and rad) rather than expressions with units,
     * every material and element written out -- NIST's too, which only Geant4
     * builds from a name -- and G4Tet and G4GenericPolycone, which TGDMLParse
     * does not know, as a tessellated solid and a polycone. What is lost is
     * the parametric form: the defines are left out.
     */
    public static String writePortable(Geometry g, List<String> notes) {
        return write(portable(g, notes));
    }

    public static void writePortable(Geometry g, Path file, List<String> notes) throws IOException {
        Files.writeString(file, writePortable(g, notes), StandardCharsets.UTF_8);
    }

    /** The portable copy of a document: what writePortable writes. */
    static Geometry portable(Geometry g, List<String> notes) {
        final Geometry p = new Geometry();
        p.title = g.title;
        p.settings.putAll(g.settings);
        p.matrices.putAll(g.matrices);
        p.surfaces.addAll(g.surfaces);
        p.surfaceLinks.addAll(g.surfaceLinks);
        // materials: those the volumes name, and those they are made of
        final Set<String> done = new HashSet<>();
        for (LogicalVolume lv : g.volumes.values()) {
            portableMaterial(g, p, lv.material, done, notes);
        }
        final Map<Solid, Solid> solids = new java.util.IdentityHashMap<>();
        for (Solid s : g.solids.values()) {
            portableSolid(p, s, solids, notes);
        }
        final Map<LogicalVolume, LogicalVolume> volumes = new java.util.IdentityHashMap<>();
        for (LogicalVolume lv : g.volumes.values()) {
            final LogicalVolume c = new LogicalVolume(lv.name, lv.solid == null ? null : solids.get(lv.solid), lv.material);
            c.color = lv.color;
            c.visible = lv.visible;
            c.wireframe = lv.wireframe;
            c.sensitive = lv.sensitive;
            c.aux.putAll(lv.aux);
            c.auxUnits.putAll(lv.auxUnits);
            c.extras.addAll(lv.extras);
            volumes.put(lv, c);
            p.addVolume(c);
        }
        for (LogicalVolume lv : g.volumes.values()) {
            for (PhysicalVolume pv : lv.daughters) {
                final PhysicalVolume c = new PhysicalVolume(pv.name, volumes.get(pv.logical), volumes.get(lv));
                c.copyNumber = pv.copyNumber;
                c.reflected = pv.reflected;
                c.setPosition(Units.exact(pv.vx), Units.exact(pv.vy), Units.exact(pv.vz));
                c.setRotation(Units.exact(pv.vrx), Units.exact(pv.vry), Units.exact(pv.vrz));
                if (pv.replica != null) {
                    final PhysicalVolume.Replica r = new PhysicalVolume.Replica();
                    r.axis = pv.replica.axis;
                    r.number = String.valueOf(pv.replica.n);
                    r.width = Units.exact(pv.replica.w);
                    r.offset = Units.exact(pv.replica.off);
                    c.replica = r;
                }
                volumes.get(lv).daughters.add(c);
            }
        }
        p.world = volumes.get(g.world);
        p.rebuild();
        return p;
    }

    private static void portableMaterial(Geometry g, Geometry p, String name, Set<String> done, List<String> notes) {
        if (name == null || !done.add(name)) {
            return;
        }
        final Material m = g.materials.material(name);
        if (m == null) {
            return;
        }
        final Material c = Material.define(name, m.densityGcm3());
        c.state = m.state;
        c.temperature = m.temperature;
        c.pressure = m.pressure;
        c.meanExcitation = m.meanExcitation;
        c.properties.putAll(m.properties);
        c.formula = m.formula;
        if (m.isNist()) {
            // as G4NistManager builds it: its elements by mass
            final List<Element> els = m.elements();
            final double[] w = m.massFractions();
            for (int i = 0; i < els.size(); i++) {
                c.addPart(portableElement(g, p, els.get(i).symbol), w[i], false);
            }
        } else {
            for (Material.Part part : m.parts()) {
                if (g.materials.element(part.ref()) != null) {
                    c.addPart(portableElement(g, p, part.ref()), part.amount(), part.byAtoms());
                } else {
                    portableMaterial(g, p, part.ref(), done, notes);
                    c.addPart(part.ref(), part.amount(), part.byAtoms());
                }
            }
        }
        try {
            p.materials.define(c);
        } catch (GeometryException e) {
            notes.add("material " + name + " could not be written out: " + e.getMessage());
        }
    }

    /** An element written out under its name (NIST's under its symbol), defined once. */
    private static String portableElement(Geometry g, Geometry p, String ref) {
        final Element e = g.materials.element(ref);
        final String name = e.nist ? e.symbol : e.name;
        if (!p.materials.definedElements().stream().anyMatch(x -> x.name.equals(name))) {
            p.materials.defineElement(new Element(name, e.symbol, e.z, e.a, e.n, false));
        }
        return name;
    }

    private static Solid portableSolid(Geometry p, Solid s, Map<Solid, Solid> map, List<String> notes) {
        final Solid known = map.get(s);
        if (known != null) {
            return known;
        }
        final Solid c;
        if (s instanceof BooleanSolid b) {
            c = new BooleanSolid(s.name(), b.op(), portableSolid(p, b.first(), map, notes),
                portableSolid(p, b.second(), map, notes));
            numbers(s, c);
        } else if (s instanceof ScaledSolid sc) {
            c = new ScaledSolid(s.name(), portableSolid(p, sc.base(), map, notes));
            numbers(s, c);
        } else if (s instanceof Convex.Tet && s.error() == null) {
            final SolidConversions.Surface t = SolidConversions.tet(s);
            final Tessellated ts = new Tessellated(s.name());
            ts.setSurface(t.vertices(), t.facets());
            exactTable(ts.table("vertex"), t.vertices());
            c = ts;
            notes.add("tet " + s.name() + " written as a tessellated solid (TGDMLParse has no tet)");
        } else if (s.tag().equals("genericPolycone") && s.error() == null) {
            final List<double[]> planes = SolidConversions.polyconePlanes(s);
            if (planes != null) {
                c = Solids.create("polycone", s.name());
                c.set("startphi", Units.exact(s.v("startphi")));
                c.set("deltaphi", Units.exact(s.v("deltaphi")));
                final Solid.Table t = c.table("zplane");
                t.clear();
                for (double[] pl : planes) {
                    t.add(Units.exact(pl[1]), Units.exact(pl[2]), Units.exact(pl[0]));
                }
                notes.add("generic polycone " + s.name() + " written as the polycone of the same profile");
            } else {
                final SolidConversions.Surface t = SolidConversions.surface(s, 2);
                final Tessellated ts = new Tessellated(s.name());
                ts.setSurface(t.vertices(), t.facets());
                exactTable(ts.table("vertex"), t.vertices());
                c = ts;
                notes.add("generic polycone " + s.name() + " written as its tessellated surface (no polycone follows it)");
            }
        } else if (s.tag().equals("genericPolyhedra") && s.error() == null) {
            final List<double[]> planes = SolidConversions.polyconePlanes(s);
            if (planes != null) {
                // z planes take the distance to the sides, the corners scaled by cos(step / 2)
                final double toSide = Math.cos(s.v("deltaphi") / Math.round(s.v("numsides")) / 2);
                c = Solids.create("polyhedra", s.name());
                c.set("startphi", Units.exact(s.v("startphi")));
                c.set("deltaphi", Units.exact(s.v("deltaphi")));
                c.set("numsides", String.valueOf(Math.round(s.v("numsides"))));
                final Solid.Table t = c.table("zplane");
                t.clear();
                for (double[] pl : planes) {
                    t.add(Units.exact(pl[1] * toSide), Units.exact(pl[2] * toSide), Units.exact(pl[0]));
                }
                notes.add("polyhedra " + s.name() + " given by its corners written as the polyhedra of the same planes");
            } else {
                final SolidConversions.Surface t = SolidConversions.surface(s, 2);
                final Tessellated ts = new Tessellated(s.name());
                ts.setSurface(t.vertices(), t.facets());
                exactTable(ts.table("vertex"), t.vertices());
                c = ts;
                notes.add("polyhedra " + s.name() + " written as its tessellated surface (no planes follow its corners)");
            }
        } else {
            c = s.copy(s.name());
            numbers(s, c);
            if (s.tag().equals("ellipsoid") && s.v("zcut1") == 0 && s.v("zcut2") == 0) {
                // Geant4 reads two zero cuts as none; TGDMLParse cuts at zero: say it plainly
                c.set("zcut1", Units.exact(-s.v("cz")));
                c.set("zcut2", Units.exact(s.v("cz")));
            }
        }
        map.put(s, c);
        p.addSolid(c);
        return c;
    }

    /** The parameters and tables of one solid copied into another as the numbers they evaluate to. */
    private static void numbers(Solid from, Solid to) {
        for (Solid.Param prm : from.params()) {
            if (to.param(prm.key) != null) {
                to.set(prm.key, Units.exact(prm.value()));
            }
        }
        for (Solid.Table t : from.tables()) {
            final Solid.Table d = to.table(t.element);
            if (d == null || t.values.size() != t.rows.size()) {
                continue;
            }
            d.clear();
            for (double[] row : t.values) {
                final String[] cells = new String[row.length];
                for (int i = 0; i < row.length; i++) {
                    cells[i] = Units.exact(row[i]);
                }
                d.add(cells);
            }
        }
    }

    private static void exactTable(Solid.Table t, List<double[]> rows) {
        t.clear();
        for (double[] r : rows) {
            t.add(Units.exact(r[0]), Units.exact(r[1]), Units.exact(r[2]));
        }
    }

    private static void writeElement(StringBuilder b, Element e) {
        b.append("    <element name=\"").append(esc(e.name)).append("\" formula=\"").append(esc(e.symbol))
         .append("\" Z=\"").append(e.z).append("\">\n");
        b.append("      <atom value=\"").append(Units.exact(e.a)).append("\"/>\n");
        b.append("    </element>\n");
    }

    private static void writeSolid(StringBuilder b, Solid s, Set<Solid> done) {
        if (!done.add(s)) {
            return;
        }
        for (Solid o : s.operands()) {
            writeSolid(b, o, done);
        }
        if (s instanceof BooleanSolid bs) {
            b.append("    <").append(bs.tag()).append(" name=\"").append(esc(s.name())).append("\">\n");
            b.append("      <first ref=\"").append(esc(bs.first().name())).append("\"/>\n");
            b.append("      <second ref=\"").append(esc(bs.second().name())).append("\"/>\n");
            vector(b, "position", s.name() + "_pos", "mm", s, "x", "y", "z");
            vector(b, "rotation", s.name() + "_rot", "rad", s, "rx", "ry", "rz");
            if (bs.firstMoved()) {
                vector(b, "firstposition", s.name() + "_fpos", "mm", s, "fx", "fy", "fz");
                vector(b, "firstrotation", s.name() + "_frot", "rad", s, "frx", "fry", "frz");
            }
            b.append("    </").append(bs.tag()).append(">\n");
            return;
        }
        if (s instanceof ScaledSolid sc) {
            b.append("    <scaledSolid name=\"").append(esc(s.name())).append("\">\n");
            b.append("      <solidref ref=\"").append(esc(sc.base().name())).append("\"/>\n");
            b.append("      <scale name=\"").append(esc(s.name())).append("_scl\" x=\"")
             .append(esc(gdmlExpr(s.param("sx").expr()))).append("\" y=\"").append(esc(gdmlExpr(s.param("sy").expr())))
             .append("\" z=\"").append(esc(gdmlExpr(s.param("sz").expr()))).append("\"/>\n");
            b.append("    </scaledSolid>\n");
            return;
        }
        if (s instanceof Tessellated) {
            b.append("    <tessellated name=\"").append(esc(s.name())).append("\">\n");
            final Solid.Table f = s.table("facet");
            for (int r = 0; r < f.size(); r++) {
                final boolean quad = facetIndex(f, r, 3) >= 0;
                b.append("      <").append(quad ? "quadrangular" : "triangular");
                for (int k = 0; k < (quad ? 4 : 3); k++) {
                    b.append(" vertex").append(k + 1).append("=\"").append(esc(s.name())).append("_v")
                     .append(facetIndex(f, r, k)).append('"');
                }
                b.append(" type=\"ABSOLUTE\"/>\n");
            }
            b.append("    </tessellated>\n");
            return;
        }
        b.append("    <").append(s.tag()).append(" name=\"").append(esc(s.name())).append("\"");
        if (s instanceof Convex.Tet) {
            for (int v = 1; v <= 4; v++) {
                b.append(" vertex").append(v).append("=\"").append(esc(s.name())).append("_v").append(v).append("\"");
            }
            b.append(" lunit=\"mm\"/>\n");
            return;
        }
        boolean hasLength = false, hasAngle = false;
        for (Solid.Param p : s.params()) {
            b.append(' ').append(p.key).append("=\"").append(esc(gdmlExpr(p.expr()))).append('"');
            hasLength |= p.kind == Solid.Kind.LENGTH;
            hasAngle |= p.kind == Solid.Kind.ANGLE;
        }
        for (Solid.Table t : s.tables()) {
            for (Solid.Kind k : t.kinds) {
                hasLength |= k == Solid.Kind.LENGTH;
            }
        }
        if (hasAngle) {
            b.append(" aunit=\"rad\"");
        }
        if (hasLength) {
            b.append(" lunit=\"mm\"");
        }
        if (s.tables().isEmpty()) {
            b.append("/>\n");
            return;
        }
        b.append(">\n");
        for (Solid.Table t : s.tables()) {
            for (int r = 0; r < t.size(); r++) {
                b.append("      <").append(t.element);
                if (t.element.equals("section")) {
                    b.append(" zOrder=\"").append(r).append('"');
                }
                for (int c = 0; c < t.columns.length; c++) {
                    b.append(' ').append(t.columns[c]).append("=\"").append(esc(gdmlExpr(t.rows().get(r)[c]))).append('"');
                }
                b.append("/>\n");
            }
        }
        b.append("    </").append(s.tag()).append(">\n");
    }

    /** A facet's corner index: the cell as typed when it is a number, else its last value. */
    private static int facetIndex(Solid.Table f, int row, int col) {
        try {
            return Integer.parseInt(f.rows().get(row)[col].trim());
        } catch (NumberFormatException expression) {
            return (int) Math.round(f.value(row, col));
        }
    }

    private static void vector(StringBuilder b, String tag, String name, String unit, Solid s, String kx, String ky,
                               String kz) {
        final String x = s.param(kx).expr(), y = s.param(ky).expr(), z = s.param(kz).expr();
        if (isZero(x) && isZero(y) && isZero(z) && !tag.startsWith("first")) {
            return;
        }
        b.append("      <").append(tag).append(" name=\"").append(esc(name)).append("\" unit=\"").append(unit)
         .append("\" x=\"").append(esc(gdmlExpr(x))).append("\" y=\"").append(esc(gdmlExpr(y)))
         .append("\" z=\"").append(esc(gdmlExpr(z))).append("\"/>\n");
    }

    static boolean isZero(String e) {
        try {
            final Expr x = Expr.parse(e);
            return x.variables().isEmpty() && x.eval(null) == 0;
        } catch (GeometryException ex) {
            return false;
        }
    }

    private static void writeVolume(StringBuilder b, LogicalVolume lv, Set<LogicalVolume> done) {
        if (done.contains(lv)) {
            return;
        }
        done.add(lv);
        for (PhysicalVolume pv : lv.daughters) {
            writeVolume(b, pv.logical, done);
        }
        b.append("    <volume name=\"").append(esc(lv.name)).append("\">\n");
        b.append("      <materialref ref=\"").append(esc(lv.material)).append("\"/>\n");
        b.append("      <solidref ref=\"").append(esc(lv.solid == null ? "" : lv.solid.name())).append("\"/>\n");
        for (PhysicalVolume pv : lv.daughters) {
            if (pv.replica != null) {
                final PhysicalVolume.Replica r = pv.replica;
                b.append("      <replicavol number=\"").append(esc(gdmlExpr(r.number))).append("\">\n");
                b.append("        <volumeref ref=\"").append(esc(pv.logical.name)).append("\"/>\n");
                b.append("        <replicate_along_axis>\n");
                final String dir = switch (r.axis) {
                    case kXAxis -> "x=\"1\"";
                    case kYAxis -> "y=\"1\"";
                    case kZAxis -> "z=\"1\"";
                    case kRho -> "rho=\"1\"";
                    case kPhi -> "phi=\"1\"";
                };
                b.append("          <direction ").append(dir).append("/>\n");
                final String unit = r.axis == PhysicalVolume.Replica.Axis.kPhi ? "rad" : "mm";
                b.append("          <width value=\"").append(esc(gdmlExpr(r.width))).append("\" unit=\"").append(unit).append("\"/>\n");
                b.append("          <offset value=\"").append(esc(gdmlExpr(r.offset))).append("\" unit=\"").append(unit).append("\"/>\n");
                b.append("        </replicate_along_axis>\n");
                b.append("      </replicavol>\n");
                continue;
            }
            b.append("      <physvol name=\"").append(esc(pv.name)).append("\"");
            if (pv.copyNumber != 0) {
                b.append(" copynumber=\"").append(pv.copyNumber).append('"');
            }
            b.append(">\n");
            b.append("        <volumeref ref=\"").append(esc(pv.logical.name)).append("\"/>\n");
            if (!(isZero(pv.x) && isZero(pv.y) && isZero(pv.z))) {
                b.append("        <position name=\"").append(esc(pv.name)).append("_pos\" unit=\"mm\" x=\"")
                 .append(esc(gdmlExpr(pv.x))).append("\" y=\"").append(esc(gdmlExpr(pv.y))).append("\" z=\"")
                 .append(esc(gdmlExpr(pv.z))).append("\"/>\n");
            }
            if (!(isZero(pv.rx) && isZero(pv.ry) && isZero(pv.rz))) {
                b.append("        <rotation name=\"").append(esc(pv.name)).append("_rot\" unit=\"rad\" x=\"")
                 .append(esc(gdmlExpr(pv.rx))).append("\" y=\"").append(esc(gdmlExpr(pv.ry))).append("\" z=\"")
                 .append(esc(gdmlExpr(pv.rz))).append("\"/>\n");
            }
            if (pv.reflected) {
                // G4GDMLReadStructure places it through G4ReflectionFactory
                b.append("        <scale name=\"").append(esc(pv.name)).append("_scl\" x=\"1\" y=\"1\" z=\"-1\"/>\n");
            }
            b.append("      </physvol>\n");
        }
        b.append("      <auxiliary auxtype=\"Color\" auxvalue=\"").append(colorHex(lv.color)).append("\"/>\n");
        if (!lv.visible) {
            b.append("      <auxiliary auxtype=\"Visible\" auxvalue=\"false\"/>\n");
        }
        if (lv.wireframe) {
            b.append("      <auxiliary auxtype=\"Wireframe\" auxvalue=\"true\"/>\n");
        }
        if (lv.sensitive != null) {
            b.append("      <auxiliary auxtype=\"SensDet\" auxvalue=\"").append(esc(lv.sensitive)).append("\"/>\n");
        }
        for (Map.Entry<String, String> e : lv.aux.entrySet()) {
            b.append("      <auxiliary auxtype=\"").append(esc(e.getKey())).append("\" auxvalue=\"")
             .append(esc(e.getValue())).append('"');
            if (lv.auxUnits.containsKey(e.getKey())) {
                b.append(" auxunit=\"").append(esc(lv.auxUnits.get(e.getKey()))).append('"');
            }
            b.append("/>\n");
        }
        for (String x : lv.extras) {
            verbatim(b, x, "      ");
        }
        b.append("    </volume>\n");
    }

    /** #RRGGBBAA */
    public static String colorHex(int argb) {
        return String.format(Locale.ROOT, "#%02x%02x%02x%02x", (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF,
            (argb >>> 24) & 0xFF);
    }

    static int parseColor(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        s = s.trim();
        try {
            if (s.startsWith("#")) {
                final String h = s.substring(1);
                if (h.length() == 6) {
                    return 0xFF000000 | Integer.parseInt(h, 16);
                }
                if (h.length() == 8) {
                    final long v = Long.parseLong(h, 16);
                    final int rgb = (int) (v >> 8) & 0xFFFFFF;
                    final int a = (int) (v & 0xFF);
                    return (a << 24) | rgb;
                }
            }
            // "r g b [a]" in 0..1, as G4Colour
            final String[] f = s.split("[\\s,]+");
            if (f.length >= 3) {
                final int r = (int) Math.round(Double.parseDouble(f[0]) * 255);
                final int g = (int) Math.round(Double.parseDouble(f[1]) * 255);
                final int bl = (int) Math.round(Double.parseDouble(f[2]) * 255);
                final int a = f.length > 3 ? (int) Math.round(Double.parseDouble(f[3]) * 255) : 255;
                return (a << 24) | (r << 16) | (g << 8) | bl;
            }
            return switch (s.toLowerCase(Locale.ROOT)) {
                case "red" -> 0xFFE04040;
                case "green" -> 0xFF40B040;
                case "blue" -> 0xFF4060E0;
                case "yellow" -> 0xFFE0D040;
                case "cyan" -> 0xFF40C0D0;
                case "magenta" -> 0xFFC040C0;
                case "white" -> 0xFFF0F0F0;
                case "black" -> 0xFF202020;
                case "grey", "gray" -> 0xFF909090;
                case "brown" -> 0xFF8B5A2B;
                default -> fallback;
            };
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** CLHEP's syntax: "**" is written "^". */
    static String gdmlExpr(String e) {
        return e == null ? "0" : e.replace("**", "^");
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        final StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    // ==================================================================
    // Reading
    // ==================================================================

    /** What a read found: the geometry, and what it could not carry over. */
    public record Result(Geometry geometry, List<String> warnings) {
    }

    public static Result read(Path file) throws IOException, GeometryException {
        final String text = Files.readString(file, StandardCharsets.UTF_8);
        return read(text, file.toAbsolutePath().getParent());
    }

    public static Result read(String text, Path baseDir) throws GeometryException {
        final Document doc;
        try {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setValidating(false);
            f.setExpandEntityReferences(true);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            final DocumentBuilder db = f.newDocumentBuilder();
            // GDML files pull shared materials in through external entities: only
            // local files next to the document are resolved, nothing from a network.
            db.setEntityResolver((publicId, systemId) -> {
                if (systemId != null && baseDir != null && !systemId.contains("://") || (systemId != null
                        && systemId.startsWith("file:"))) {
                    String p = systemId.startsWith("file:") ? systemId.replaceFirst("^file:/*", "/") : systemId;
                    Path target = Path.of(p);
                    if (!target.isAbsolute() && baseDir != null) {
                        target = baseDir.resolve(p);
                    }
                    if (Files.isRegularFile(target)) {
                        return new InputSource(new StringReader(Files.readString(target, StandardCharsets.UTF_8)));
                    }
                }
                return new InputSource(new StringReader(""));
            });
            doc = db.parse(new InputSource(new StringReader(text)));
        } catch (Exception e) {
            throw new GeometryException("not a readable GDML file: " + e.getMessage(), e);
        }
        return new Reader().read(doc);
    }

    private static final class Reader {
        final Geometry g = new Geometry();
        final List<String> warnings = new ArrayList<>();
        final Map<String, String[]> positions = new HashMap<>();   // name -> x y z exprs (mm)
        final Map<String, String[]> rotations = new HashMap<>();   // name -> x y z exprs (rad)
        final Map<String, String[]> scales = new HashMap<>();      // name -> x y z factors
        final Map<String, Node> volumeNodes = new java.util.LinkedHashMap<>();
        final Map<String, Node> solidNodes = new java.util.LinkedHashMap<>();
        final Map<String, Element> isotopes = new HashMap<>();
        String worldRef;
        // the placement names given so far: Geometry.placement walks every volume, too slow for a file of 100 000 physvols
        final java.util.Set<String> placed = new java.util.HashSet<>();

        Result read(Document doc) throws GeometryException {
            final Node root = doc.getDocumentElement();
            // G4GDMLRead reads the sections of whatever the root element is: <gdml>, or the
            // element of a reader's own extension (Geant4's example gdml_simple_extension...)
            if (root == null || !hasChild(root, "structure")) {
                throw new GeometryException(root == null ? "the document is empty"
                    : "<" + root.getNodeName() + "> holds no GDML <structure>: not a GDML document");
            }
            for (Node n : children(root)) {
                switch (n.getNodeName()) {
                    case "define" -> defines(n);
                    case "materials" -> materials(n);
                    case "solids" -> {
                        for (Node s : content(n)) {
                            final String name = attr(s, "name");
                            if (s.getNodeName().equals("opticalsurface")) {
                                g.surfaces.add(xml(s));
                            } else if (name != null) {
                                solidNodes.put(name, s);
                            }
                        }
                    }
                    case "structure" -> {
                        for (Node v : content(n)) {
                            switch (v.getNodeName()) {
                                case "volume", "assembly" -> volumeNodes.put(attr(v, "name"), v);
                                case "skinsurface", "bordersurface" -> g.surfaceLinks.add(xml(v));
                                default -> warnings.add("structure element <" + v.getNodeName() + "> is not supported (skipped)");
                            }
                        }
                    }
                    case "extension" -> {
                        // the colours of Geant4's extension example, which its volumes name by colorref
                        for (Node c : children(n)) {
                            if (c.getNodeName().equals("color") && attr(c, "name") != null) {
                                colors.put(attr(c, "name"), rgba(c));
                            }
                        }
                    }
                    case "setup" -> {
                        if (worldRef == null) {
                            for (Node w : children(n)) {
                                if (w.getNodeName().equals("world")) {
                                    worldRef = attr(w, "ref");
                                }
                            }
                        }
                    }
                    case "userinfo" -> {
                        for (Node a : children(n)) {
                            if ("G4editor".equals(attr(a, "auxtype"))) {
                                g.settings.put(attr(a, "auxvalue"), nz(attr(a, "auxunit")));
                            } else if ("G4editorTitle".equals(attr(a, "auxtype")) && attr(a, "auxvalue") != null
                                && !attr(a, "auxvalue").isBlank()) {
                                g.title = attr(a, "auxvalue");
                            }
                        }
                    }
                    default -> {
                    }
                }
            }
            for (String name : solidNodes.keySet()) {
                solid(name);
            }
            for (String name : volumeNodes.keySet()) {
                volume(name);
            }
            for (String name : volumeNodes.keySet()) {
                link(name);
            }
            if (worldRef == null || g.volumes.get(worldRef) == null) {
                throw new GeometryException("no world volume: <setup><world ref=\"" + worldRef + "\"/> is missing");
            }
            g.world = g.volumes.get(worldRef);
            g.rebuild();
            return new Result(g, warnings);
        }

        // --- define -------------------------------------------------------

        void defines(Node n) {
            for (Node d : children(n)) {
                if (!d.getNodeName().equals("loop")) {
                    define(d);
                    continue;
                }
                // a loop here may count up to a constant defined just before it: unrolled in turn
                try {
                    for (Node e : unroll(d)) {
                        define(e);
                    }
                } catch (GeometryException e) {
                    warnings.add("define: " + e.getMessage());
                }
            }
        }

        void define(Node d) {
            final String name = attr(d, "name");
            switch (d.getNodeName()) {
                case "constant" -> g.addDefine(new Define(name, Define.Kind.CONSTANT, nz(attr(d, "value"))));
                case "variable" -> g.addDefine(new Define(name, Define.Kind.VARIABLE, nz(attr(d, "value"))));
                case "quantity" -> {
                    final Define q = new Define(name, Define.Kind.QUANTITY, nz(attr(d, "value")));
                    q.unit = attr(d, "unit");
                    q.type = attr(d, "type");
                    g.addDefine(q);
                }
                case "expression" -> g.addDefine(new Define(name, Define.Kind.EXPRESSION, d.getTextContent().trim()));
                case "position" -> positions.put(name, vec(d, "mm"));
                case "rotation" -> rotations.put(name, vec(d, "rad"));
                case "scale" -> scales.put(name, factors(d));
                case "matrix" -> {
                    // kept as written, for the materials' properties: Geant4 evaluates each value with its units
                    final String values = nz0(attr(d, "values")).trim().replaceAll("\\s+", " ");
                    int coldim = 1;
                    try {
                        coldim = (int) Math.round(num(nz(attr(d, "coldim"))));
                    } catch (GeometryException e) {
                        warnings.add("matrix " + name + ": coldim " + attr(d, "coldim") + " is not a number (1 taken)");
                    }
                    g.matrices.put(name, new Geometry.Matrix(name, Math.max(1, coldim), values));
                }
                default -> warnings.add("define <" + d.getNodeName() + "> " + name + " is not supported (skipped)");
            }
        }

        // --- loops --------------------------------------------------------

        /** A section's elements with every loop among them unrolled. */
        List<Node> content(Node parent) {
            final List<Node> out = new ArrayList<>();
            for (Node c : children(parent)) {
                if (!c.getNodeName().equals("loop")) {
                    out.add(c);
                    continue;
                }
                try {
                    out.addAll(unroll(c));
                } catch (GeometryException e) {
                    warnings.add(parent.getNodeName() + " " + nz(attr(parent, "name")) + ": " + e.getMessage());
                }
            }
            return out;
        }

        private int loopDepth;
        private int loopCopies;

        /**
         * What a loop stands for, as G4GDMLRead::LoopRead runs it: its content
         * once for each value of its variable (a GDML variable) from "from" up
         * to "to", "to" included, by "step"; in each copy the variable's name
         * becomes that value, and in names and references a bracketed index
         * becomes a suffix (box[i] is box_0 for i = 1), as G4GDMLEvaluator's
         * SolveBrackets has it. Loops within loops unroll the same way.
         */
        List<Node> unroll(Node loop) throws GeometryException {
            final String var = attr(loop, "for");
            if (var == null || var.isBlank() || g.define(var) == null) {
                throw new GeometryException("loop over \"" + var + "\", which is no defined variable");
            }
            final long from = integer(attr(loop, "from"), 0), to = integer(attr(loop, "to"), 0);
            final long step = integer(attr(loop, "step"), 1);
            if ((from < to && step <= 0) || (from > to && step >= 0)) {
                throw new GeometryException("loop over " + var + " from " + from + " to " + to + " by " + step + " never ends");
            }
            final List<Node> out = new ArrayList<>();
            loopDepth++;
            try {
                for (long v = from; v <= to; v += step) {
                    if (++loopCopies > 1_000_000) {
                        throw new GeometryException("loops unrolled to more than a million elements: stopped");
                    }
                    final Node copy = loop.cloneNode(true);
                    substitute(copy, var, v);
                    out.addAll(content(copy));
                }
            } finally {
                loopDepth--;
            }
            if (loopDepth == 0) {
                for (Node n : out) {
                    solveBrackets(n);
                }
            }
            return out;
        }

        private long integer(String expr, long fallback) throws GeometryException {
            if (expr == null || expr.isBlank()) {
                return fallback;
            }
            final double v = num(expr);
            if (Math.abs(v - Math.rint(v)) > 1e-9) {
                throw new GeometryException("loop bound " + expr + " is not a whole number (" + v + ")");
            }
            return Math.round(v);
        }

        private static final java.util.regex.Pattern BRACKETS = java.util.regex.Pattern.compile("\\[([^\\]]*)\\]");

        /** The loop variable's value put in for its name: in expressions everywhere, in names only within brackets. */
        private static void substitute(Node n, String var, long value) {
            final java.util.regex.Pattern word = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])"
                + java.util.regex.Pattern.quote(var) + "(?![A-Za-z0-9_])");
            final String put = java.util.regex.Matcher.quoteReplacement("(" + value + ")");
            if (n.getAttributes() != null) {
                for (int i = 0; i < n.getAttributes().getLength(); i++) {
                    final Node a = n.getAttributes().item(i);
                    final String key = a.getNodeName();
                    if (key.equals("for")) {
                        continue;
                    }
                    if (key.equals("name") || key.equals("ref")) {
                        final java.util.regex.Matcher m = BRACKETS.matcher(a.getNodeValue());
                        final StringBuilder b = new StringBuilder();
                        while (m.find()) {
                            m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(
                                "[" + word.matcher(m.group(1)).replaceAll(put) + "]"));
                        }
                        m.appendTail(b);
                        a.setNodeValue(b.toString());
                    } else {
                        a.setNodeValue(word.matcher(a.getNodeValue()).replaceAll(put));
                    }
                }
            }
            final NodeList l = n.getChildNodes();
            for (int i = 0; i < l.getLength(); i++) {
                final Node c = l.item(i);
                if (c.getNodeType() == Node.ELEMENT_NODE) {
                    substitute(c, var, value);
                } else if (c.getNodeType() == Node.TEXT_NODE && n.getNodeName().equals("expression")) {
                    c.setNodeValue(word.matcher(c.getNodeValue()).replaceAll(put));
                }
            }
        }

        /** name[i, j] as name_(i-1)_(j-1), in the names and references of an unrolled element. */
        private void solveBrackets(Node n) throws GeometryException {
            if (n.getAttributes() != null) {
                for (String key : new String[] {"name", "ref"}) {
                    final Node a = n.getAttributes().getNamedItem(key);
                    if (a == null || a.getNodeValue().indexOf('[') < 0) {
                        continue;
                    }
                    final java.util.regex.Matcher m = BRACKETS.matcher(a.getNodeValue());
                    final StringBuilder b = new StringBuilder();
                    while (m.find()) {
                        final StringBuilder idx = new StringBuilder();
                        for (String part : m.group(1).split(",")) {
                            idx.append('_').append(integer(part.trim(), 0) - 1);
                        }
                        m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(idx.toString()));
                    }
                    m.appendTail(b);
                    a.setNodeValue(b.toString());
                }
            }
            for (Node c : children(n)) {
                solveBrackets(c);
            }
        }

        /** x y z as expressions in internal units, with the element's unit applied. */
        String[] vec(Node d, String defUnit) {
            final String unit = attr(d, "unit") != null ? attr(d, "unit") : defUnit;
            return new String[] {
                withUnit(nz0(attr(d, "x")), unit, defUnit),
                withUnit(nz0(attr(d, "y")), unit, defUnit),
                withUnit(nz0(attr(d, "z")), unit, defUnit)};
        }

        /** A scale's x y z, plain factors (1 where one is missing). */
        String[] factors(Node d) {
            return new String[] {
                attr(d, "x") == null || attr(d, "x").isBlank() ? "1" : attr(d, "x"),
                attr(d, "y") == null || attr(d, "y").isBlank() ? "1" : attr(d, "y"),
                attr(d, "z") == null || attr(d, "z").isBlank() ? "1" : attr(d, "z")};
        }

        /** scaledSolid: a solidref and a scale (or scaleref). */
        Solid scaled(Node n, String name) throws GeometryException {
            String ref = null;
            String[] f = {"1", "1", "1"};
            for (Node c : children(n)) {
                switch (c.getNodeName()) {
                    case "solidref" -> ref = attr(c, "ref");
                    case "scale" -> f = factors(c);
                    case "scaleref" -> {
                        if (scales.containsKey(attr(c, "ref"))) {
                            f = scales.get(attr(c, "ref"));
                        }
                    }
                    default -> {
                    }
                }
            }
            if (ref == null) {
                throw new GeometryException("scaledSolid " + name + " has no solidref");
            }
            final ScaledSolid s = new ScaledSolid(name, solid(ref));
            s.set("sx", f[0]);
            s.set("sy", f[1]);
            s.set("sz", f[2]);
            return s;
        }

        /**
         * tessellated: facets naming positions, as G4GDMLReadSolids reads them
         * (the unit is the facet's lunit, the position's own unit applied first;
         * RELATIVE corners are offsets from the first).
         */
        Solid tessellated(Node n, String name) throws GeometryException {
            final Tessellated t = new Tessellated(name);
            final Map<String, Integer> index = new java.util.LinkedHashMap<>();
            final List<String[]> vertices = new ArrayList<>();
            final Solid.Table facets = t.table("facet");
            facets.clear();
            for (Node c : children(n)) {
                final boolean quad = c.getNodeName().equals("quadrangular");
                if (!quad && !c.getNodeName().equals("triangular")) {
                    continue;
                }
                final String lunit = attr(c, "lunit");
                final boolean relative = "RELATIVE".equalsIgnoreCase(attr(c, "type"));
                final String[] row = {"0", "0", "0", "-1"};
                String[] first = null;
                for (int k = 0; k < (quad ? 4 : 3); k++) {
                    final String ref = attr(c, "vertex" + (k + 1));
                    String[] p = positions.get(ref);
                    if (p == null) {
                        throw new GeometryException("tessellated " + name + ": unknown vertex position " + ref);
                    }
                    if (lunit != null && !lunit.equals("mm")) {
                        p = new String[] {"(" + p[0] + ")*" + lunit, "(" + p[1] + ")*" + lunit, "(" + p[2] + ")*" + lunit};
                    }
                    String key = ref + "|" + lunit;
                    if (relative && k > 0) {
                        p = new String[] {first[0] + "+(" + p[0] + ")", first[1] + "+(" + p[1] + ")", first[2] + "+(" + p[2] + ")"};
                        key = key + "|rel|" + attr(c, "vertex1");
                    }
                    if (k == 0) {
                        first = p;
                    }
                    Integer i = index.get(key);
                    if (i == null) {
                        i = vertices.size();
                        index.put(key, i);
                        vertices.add(p);
                    }
                    row[k] = String.valueOf(i);
                }
                facets.add(row);
            }
            final Solid.Table v = t.table("vertex");
            v.clear();
            for (String[] p : vertices) {
                v.add(p);
            }
            return t;
        }

        // --- materials ----------------------------------------------------

        void materials(Node n) {
            for (Node m : content(n)) {
                final String name = attr(m, "name");
                try {
                    switch (m.getNodeName()) {
                        case "isotope" -> {
                            final double a = atomValue(m);
                            isotopes.put(name, new Element(name, name, (int) Math.round(num(attr(m, "Z"))), a,
                                num(attr(m, "N")), false));
                        }
                        case "element" -> element(m, name);
                        case "material" -> material(m, name);
                        case "#comment", "#text" -> {
                        }
                        default -> warnings.add("material element <" + m.getNodeName() + "> skipped");
                    }
                } catch (GeometryException e) {
                    warnings.add(e.getMessage());
                }
            }
        }

        void element(Node m, String name) throws GeometryException {
            final String zs = attr(m, "Z");
            final String formula = attr(m, "formula") != null ? attr(m, "formula") : name;
            if (zs != null) {
                final int z = (int) Math.round(num(zs));
                final Element nist = g.materials.nist().element(z);
                final double a = hasChild(m, "atom") ? atomValue(m) : nist != null ? nist.a : 2.0 * z;
                g.materials.defineElement(new Element(name, formula, z, a, nist != null ? nist.n : a, false));
                return;
            }
            // From isotope fractions
            double a = 0, nn = 0, w = 0;
            int z = 0;
            for (Node f : children(m)) {
                if (f.getNodeName().equals("fraction")) {
                    final Element iso = isotopes.get(attr(f, "ref"));
                    if (iso == null) {
                        throw new GeometryException("element " + name + ": unknown isotope " + attr(f, "ref"));
                    }
                    final double fr = num(attr(f, "n"));
                    a += fr * iso.a;
                    nn += fr * iso.n;
                    w += fr;
                    z = iso.z;
                }
            }
            if (w <= 0) {
                throw new GeometryException("element " + name + " has neither Z nor isotopes");
            }
            g.materials.defineElement(new Element(name, formula, z, a / w, nn / w, false));
        }

        double atomValue(Node m) throws GeometryException {
            for (Node c : children(m)) {
                if (c.getNodeName().equals("atom")) {
                    double v = num(attr(c, "value"));
                    final String unit = attr(c, "unit");
                    if (unit != null && !unit.equals("g/mole") && !unit.equals("g/mol")) {
                        v = v * Expr.eval(unit, null) / Expr.eval("g/mole", null);
                    }
                    return v;
                }
            }
            throw new GeometryException("<atom> missing");
        }

        void material(Node m, String name) throws GeometryException {
            final Material mat = Material.define(name, 1.0);
            if (attr(m, "formula") != null) {
                mat.formula = attr(m, "formula");
            }
            mat.setState(Material.State.of(attr(m, "state")));
            if (attr(m, "state") == null) {
                mat.setState(Material.State.SOLID);
            }
            for (Node c : children(m)) {
                final String unit = attr(c, "unit");
                switch (c.getNodeName()) {
                    case "D" -> mat.setDensity(num(attr(c, "value")) * Expr.eval(unit == null ? "g/cm3" : unit, null));
                    case "T" -> mat.setTemperature(num(attr(c, "value")) * Expr.eval(unit == null ? "K" : unit, null));
                    case "P" -> mat.setPressure(num(attr(c, "value")) * Expr.eval(unit == null ? "pascal" : unit, null)
                        / Units.ATMOSPHERE);
                    case "MEE" -> mat.setMeanExcitation(num(attr(c, "value"))
                        * Expr.eval(unit == null ? "eV" : unit, null) / Expr.eval("eV", null));
                    case "fraction" -> mat.addPart(attr(c, "ref"), num(attr(c, "n")), false);
                    case "composite" -> mat.addPart(attr(c, "ref"), num(attr(c, "n")), true);
                    case "property" -> {
                        if (attr(c, "name") != null && attr(c, "ref") != null) {
                            mat.properties.put(attr(c, "name"), attr(c, "ref"));
                        }
                    }
                    default -> {
                    }
                }
            }
            if (attr(m, "Z") != null) {
                // A simple material: one element of that Z and the given molar mass
                final int z = (int) Math.round(num(attr(m, "Z")));
                final String el = name + "_element";
                final Element nist = g.materials.nist().element(z);
                final double a = hasChild(m, "atom") ? atomValue(m) : nist.a;
                g.materials.defineElement(new Element(el, nist != null ? nist.symbol : el, z, a,
                    nist != null ? nist.n : a, false));
                mat.addPart(el, 1, true);
            }
            g.materials.define(mat);
        }

        // --- solids -------------------------------------------------------

        Solid solid(String name) throws GeometryException {
            final Solid existing = g.solids.get(name);
            if (existing != null) {
                return existing;
            }
            final Node n = solidNodes.get(name);
            if (n == null) {
                throw new GeometryException("unknown solid " + name);
            }
            final String tag = n.getNodeName();
            final String lunit = attr(n, "lunit") != null ? attr(n, "lunit") : "mm";
            final String aunit = attr(n, "aunit") != null ? attr(n, "aunit") : "rad";
            final BooleanSolid.Op op = BooleanSolid.Op.ofTag(tag);
            Solid s;
            if (op != null) {
                String first = null, second = null;
                for (Node c : children(n)) {
                    if (c.getNodeName().equals("first")) {
                        first = attr(c, "ref");
                    } else if (c.getNodeName().equals("second")) {
                        second = attr(c, "ref");
                    }
                }
                final BooleanSolid b = new BooleanSolid(name, op, solid(first), solid(second));
                for (Node c : children(n)) {
                    String[] v = null;
                    String prefix = null;
                    switch (c.getNodeName()) {
                        case "position" -> {
                            v = vec(c, "mm");
                            prefix = "";
                        }
                        case "positionref" -> {
                            v = positions.get(attr(c, "ref"));
                            prefix = "";
                        }
                        case "rotation" -> {
                            v = vec(c, "rad");
                            prefix = "r";
                        }
                        case "rotationref" -> {
                            v = rotations.get(attr(c, "ref"));
                            prefix = "r";
                        }
                        case "firstposition" -> {
                            v = vec(c, "mm");
                            prefix = "f";
                        }
                        case "firstpositionref" -> {
                            v = positions.get(attr(c, "ref"));
                            prefix = "f";
                        }
                        case "firstrotation" -> {
                            v = vec(c, "rad");
                            prefix = "fr";
                        }
                        case "firstrotationref" -> {
                            v = rotations.get(attr(c, "ref"));
                            prefix = "fr";
                        }
                        default -> {
                        }
                    }
                    if (v != null) {
                        b.set(prefix + "x", v[0]);
                        b.set(prefix + "y", v[1]);
                        b.set(prefix + "z", v[2]);
                    }
                }
                s = b;
            } else if (tag.equals(Solids.SCALED)) {
                s = scaled(n, name);
            } else if (tag.equals("tessellated")) {
                s = tessellated(n, name);
            } else if (Solids.type(tag) != null) {
                s = Solids.create(tag, name);
                if (s instanceof Convex.Tet) {
                    for (int v = 1; v <= 4; v++) {
                        final String[] p = positions.get(attr(n, "vertex" + v));
                        if (p == null) {
                            throw new GeometryException("tet " + name + ": unknown vertex position " + attr(n, "vertex" + v));
                        }
                        final String scale = lunit.equals("mm") ? "" : "*" + lunit;
                        s.set("v" + v + "x", scale.isEmpty() ? p[0] : "(" + p[0] + ")" + scale);
                        s.set("v" + v + "y", scale.isEmpty() ? p[1] : "(" + p[1] + ")" + scale);
                        s.set("v" + v + "z", scale.isEmpty() ? p[2] : "(" + p[2] + ")" + scale);
                    }
                } else {
                    for (Solid.Param p : s.params()) {
                        final String raw = attr(n, p.key);
                        final String value = raw != null ? raw : "0";
                        if (raw == null && p.kind == Solid.Kind.NUMBER && p.key.equals("scalingFactor")) {
                            continue;
                        }
                        s.set(p.key, switch (p.kind) {
                            case LENGTH -> withUnit(value, lunit, "mm");
                            case ANGLE -> withUnit(value, aunit, "rad");
                            default -> value;
                        });
                    }
                    for (Solid.Table t : s.tables()) {
                        t.clear();
                        for (Node c : children(n)) {
                            if (!c.getNodeName().equals(t.element)) {
                                continue;
                            }
                            final String[] row = new String[t.columns.length];
                            for (int k = 0; k < row.length; k++) {
                                String raw = attr(c, t.columns[k]);
                                if (raw == null) {
                                    raw = t.columns[k].equals("scalingFactor") ? "1" : "0";
                                }
                                row[k] = t.kinds[k] == Solid.Kind.LENGTH ? withUnit(raw, lunit, "mm") : raw;
                            }
                            t.add(row);
                        }
                    }
                }
            } else {
                warnings.add("solid <" + tag + "> " + name + " is not supported: replaced by a 1 cm box");
                s = Solids.create("box", name);
                s.set("x", "10*mm");
                s.set("y", "10*mm");
                s.set("z", "10*mm");
            }
            g.addSolid(s);
            return s;
        }

        // --- structure ----------------------------------------------------

        void volume(String name) {
            final Node n = volumeNodes.get(name);
            if (n.getNodeName().equals("assembly")) {
                warnings.add("assembly " + name + " is read as an air box around its parts");
            }
            String mat = null, sol = null;
            for (Node c : children(n)) {
                if (c.getNodeName().equals("materialref")) {
                    mat = attr(c, "ref");
                } else if (c.getNodeName().equals("solidref")) {
                    sol = attr(c, "ref");
                }
            }
            Solid s = sol == null ? null : g.solids.get(sol);
            if (s == null) {
                warnings.add("volume " + name + ": solid " + sol + " not found");
            }
            final LogicalVolume lv = new LogicalVolume(name, s, mat == null ? "G4_AIR" : mat);
            lv.color = MaterialColors.colorFor(lv.material);
            for (Node c : children(n)) {
                switch (c.getNodeName()) {
                    case "auxiliary" -> {
                        final String type = attr(c, "auxtype"), value = attr(c, "auxvalue");
                        if (type == null) {
                            continue;
                        }
                        switch (type) {
                            case "Color", "Colour", "colour", "color" -> lv.color = parseColor(value, lv.color);
                            case "Visible", "visible" -> lv.visible = !"false".equalsIgnoreCase(value) && !"0".equals(value);
                            case "Invisible" -> lv.visible = false;
                            case "Wireframe" -> lv.wireframe = "true".equalsIgnoreCase(value) || "1".equals(value);
                            case "SensDet", "SensitiveDetector" -> lv.sensitive = value;
                            default -> {
                                lv.aux.put(type, value);
                                if (attr(c, "auxunit") != null) {
                                    lv.auxUnits.put(type, attr(c, "auxunit"));
                                }
                            }
                        }
                    }
                    case "colorref" -> {
                        final Integer argb = colors.get(attr(c, "ref"));
                        if (argb != null) {
                            lv.color = argb;
                        } else {
                            warnings.add("volume " + name + ": color " + attr(c, "ref") + " is not defined in <extension>");
                        }
                    }
                    case "materialref", "solidref", "physvol", "replicavol", "divisionvol", "paramvol", "loop" -> {
                    }
                    // what Geant4 itself treats as a reader's extension (an opticalsurface here...): kept for writing
                    default -> lv.extras.add(xml(c));
                }
            }
            g.addVolume(lv);
        }

        final Map<String, Integer> colors = new HashMap<>();

        /** An extension colour's R G B A (0 to 1) as ARGB. */
        int rgba(Node c) {
            final double[] v = new double[4];
            final String[] keys = {"R", "G", "B", "A"};
            for (int i = 0; i < 4; i++) {
                try {
                    v[i] = attr(c, keys[i]) == null ? 1 : num(attr(c, keys[i]));
                } catch (GeometryException e) {
                    v[i] = 1;
                }
            }
            final int r = channel(v[0]), gr = channel(v[1]), bl = channel(v[2]), a = channel(v[3]);
            return a << 24 | r << 16 | gr << 8 | bl;
        }

        private static int channel(double x) {
            return (int) Math.round(Math.max(0, Math.min(1, x)) * 255);
        }

        void link(String name) {
            final Node n = volumeNodes.get(name);
            final LogicalVolume lv = g.volumes.get(name);
            for (Node c : content(n)) {
                try {
                    if (c.getNodeName().equals("physvol")) {
                        physvol(lv, c);
                    } else if (c.getNodeName().equals("replicavol")) {
                        replicavol(lv, c);
                    } else if (c.getNodeName().equals("divisionvol") || c.getNodeName().equals("paramvol")) {
                        warnings.add("volume " + name + ": <" + c.getNodeName() + "> is not supported (skipped)");
                    }
                } catch (GeometryException e) {
                    warnings.add("volume " + name + ": " + e.getMessage());
                }
            }
        }

        /** Geometry.uniqueName, with the placements looked up in {@link #placed}. */
        String placementName(String base) {
            String b = base.replaceAll("[^A-Za-z0-9_]", "_");
            if (Character.isDigit(b.charAt(0))) {
                b = "_" + b;
            }
            if (!taken(b)) {
                return b;
            }
            final String stem = b.replaceAll("_?\\d+$", "");
            for (int i = nextSuffix.getOrDefault(stem, 1); ; i++) {
                final String n = stem + "_" + i;
                if (!taken(n)) {
                    nextSuffix.put(stem, i + 1);
                    return n;
                }
            }
        }

        final Map<String, Integer> nextSuffix = new HashMap<>();

        boolean taken(String n) {
            return placed.contains(n) || g.solids.containsKey(n) || g.volumes.containsKey(n) || g.defines.containsKey(n)
                || Units.isReserved(n);
        }

        void physvol(LogicalVolume mother, Node c) throws GeometryException {
            String ref = null;
            String[] pos = null, rot = null, scale = null;
            for (Node k : children(c)) {
                switch (k.getNodeName()) {
                    case "volumeref" -> ref = attr(k, "ref");
                    case "file" -> throw new GeometryException("physvol from an external <file> is not supported");
                    case "position" -> pos = vec(k, "mm");
                    case "positionref" -> pos = positions.get(attr(k, "ref"));
                    case "rotation" -> rot = vec(k, "rad");
                    case "rotationref" -> rot = rotations.get(attr(k, "ref"));
                    case "scale" -> scale = factors(k);
                    case "scaleref" -> scale = scales.get(attr(k, "ref"));
                    default -> {
                    }
                }
            }
            final LogicalVolume lv = g.volumes.get(ref);
            if (lv == null) {
                throw new GeometryException("physvol refers to unknown volume " + ref);
            }
            String name = attr(c, "name");
            if (name == null || name.isBlank()) {
                name = placementName(ref + "_pv");
            } else if (placed.contains(name)) {
                name = placementName(name);     // Geant4 takes the same name twice (a loop places pvolPMT ten times)
            }
            placed.add(name);
            final PhysicalVolume pv = g.place(lv, mother, name);
            // no copynumber is copy 0 to G4GDMLReadStructure, whatever else the volume is placed with
            pv.copyNumber = attr(c, "copynumber") != null ? (int) Math.round(num(attr(c, "copynumber"))) : 0;
            if (pos != null) {
                pv.setPosition(pos[0], pos[1], pos[2]);
            }
            if (rot != null) {
                pv.setRotation(rot[0], rot[1], rot[2]);
            }
            if (scale != null) {
                mirror(pv, mother, scale);
            }
        }

        /**
         * A physvol's scale, which G4GDMLReadStructure applies before the
         * rotation (G4Transform3D(R^-1, t) * G4Scale3D): (1, 1, -1) is a
         * mirrored placement as is; another mirror (an odd number of -1) is
         * that one turned, the turn going into the rotation; factors that are
         * not 1 or -1 stretch, which no placement does in Geant4.
         */
        void mirror(PhysicalVolume pv, LogicalVolume mother, String[] scale) throws GeometryException {
            final double sx = num(scale[0]), sy = num(scale[1]), sz = num(scale[2]);
            if (Math.abs(Math.abs(sx) - 1) > 1e-9 || Math.abs(Math.abs(sy) - 1) > 1e-9 || Math.abs(Math.abs(sz) - 1) > 1e-9) {
                warnings.add("physvol " + pv.name + " in " + mother.name + " is stretched (" + scale[0] + ", " + scale[1] + ", "
                    + scale[2] + "): only the signs are kept");
            }
            final double fx = Math.signum(sx), fy = Math.signum(sy), fz = Math.signum(sz);
            if (fx == 1 && fy == 1 && fz == 1) {
                return;
            }
            pv.reflected = fx * fy * fz < 0;
            if (fx == 1 && fy == 1) {
                return;     // (1, 1, -1): the mirror as GDML writes it
            }
            // R^-1 S = R'^-1 Z, with Z the mirror through z = 0 when reflected: R'^-1 = R^-1 S Z
            final double[] rinv = Xform.transpose(Xform.gdmlRotation(num(pv.rx), num(pv.ry), num(pv.rz)));
            final double[] s = {fx, 0, 0, 0, fy, 0, 0, 0, pv.reflected ? -fz : fz};
            final double[] a = Xform.gdmlAngles(Xform.transpose(Xform.mul(rinv, s)));
            pv.setRotation(Units.exact(a[0]), Units.exact(a[1]), Units.exact(a[2]));
        }

        void replicavol(LogicalVolume mother, Node c) throws GeometryException {
            final PhysicalVolume.Replica r = new PhysicalVolume.Replica();
            r.number = nz(attr(c, "number"));
            String ref = null;
            for (Node k : children(c)) {
                if (k.getNodeName().equals("volumeref")) {
                    ref = attr(k, "ref");
                } else if (k.getNodeName().equals("replicate_along_axis")) {
                    for (Node a : children(k)) {
                        switch (a.getNodeName()) {
                            case "direction" -> {
                                if (attr(a, "x") != null && num(attr(a, "x")) != 0) {
                                    r.axis = PhysicalVolume.Replica.Axis.kXAxis;
                                } else if (attr(a, "y") != null && num(attr(a, "y")) != 0) {
                                    r.axis = PhysicalVolume.Replica.Axis.kYAxis;
                                } else if (attr(a, "z") != null && num(attr(a, "z")) != 0) {
                                    r.axis = PhysicalVolume.Replica.Axis.kZAxis;
                                } else if (attr(a, "rho") != null) {
                                    r.axis = PhysicalVolume.Replica.Axis.kRho;
                                } else if (attr(a, "phi") != null) {
                                    r.axis = PhysicalVolume.Replica.Axis.kPhi;
                                }
                            }
                            case "width" -> r.width = withUnit(nz(attr(a, "value")),
                                attr(a, "unit") != null ? attr(a, "unit") : "mm", internalUnit(attr(a, "unit")));
                            case "offset" -> r.offset = withUnit(nz(attr(a, "value")),
                                attr(a, "unit") != null ? attr(a, "unit") : "mm", internalUnit(attr(a, "unit")));
                            default -> {
                            }
                        }
                    }
                }
            }
            final LogicalVolume lv = g.volumes.get(ref);
            if (lv == null) {
                throw new GeometryException("replicavol refers to unknown volume " + ref);
            }
            final String name = placementName(ref + "_rep");
            placed.add(name);
            final PhysicalVolume pv = g.place(lv, mother, name);
            pv.replica = r;
        }

        double num(String s) throws GeometryException {
            if (s == null) {
                throw new GeometryException("missing numeric attribute");
            }
            return Expr.eval(s, n -> {
                final Define d = g.define(n);
                if (d == null) {
                    return null;
                }
                try {
                    return Expr.eval(d.effectiveExpr(), x -> {
                        final Define dd = g.define(x);
                        return dd == null ? null : Expr.evalOr(dd.effectiveExpr(), null, 0);
                    });
                } catch (GeometryException e) {
                    return null;
                }
            });
        }
    }

    /** The internal unit of a unit's kind: rad for an angle, mm for a length (a phi replica's width is an angle). */
    static String internalUnit(String unit) {
        return unit != null && Set.of("rad", "radian", "deg", "degree", "mrad", "milliradian", "urad", "microradian")
            .contains(unit) ? "rad" : "mm";
    }

    /** expr in `unit`, as an expression in internal units; a plain expression in the default unit stays as is. */
    static String withUnit(String expr, String unit, String defaultUnit) {
        expr = expr.trim();
        if (unit == null || unit.equals(defaultUnit) || unit.isBlank()) {
            return expr;
        }
        if (expr.matches("[-+]?[0-9.]+([eE][-+]?[0-9]+)?")) {
            return expr + "*" + unit;
        }
        return "(" + expr + ")*" + unit;
    }

    /** An element as XML text, for what is kept as the file wrote it. */
    static String xml(Node n) {
        try {
            final javax.xml.transform.Transformer t = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes");
            final java.io.StringWriter w = new java.io.StringWriter();
            t.transform(new javax.xml.transform.dom.DOMSource(n), new javax.xml.transform.stream.StreamResult(w));
            return w.toString().strip();
        } catch (javax.xml.transform.TransformerException e) {
            return "<!-- " + n.getNodeName() + " could not be kept: " + e.getMessage().replace("--", "- -") + " -->";
        }
    }

    /** Kept XML at an indentation: its own lines keep their places relative to each other. */
    private static void verbatim(StringBuilder b, String xml, String indent) {
        final String[] lines = xml.split("\\R");
        int common = Integer.MAX_VALUE;
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                int k = 0;
                while (k < lines[i].length() && lines[i].charAt(k) == ' ') {
                    k++;
                }
                common = Math.min(common, k);
            }
        }
        b.append(indent).append(lines[0].strip()).append('\n');
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                b.append(indent).append(lines[i].substring(Math.min(common, lines[i].length())).stripTrailing()).append('\n');
            }
        }
    }

    private static boolean hasChild(Node n, String tag) {
        for (Node c : children(n)) {
            if (c.getNodeName().equals(tag)) {
                return true;
            }
        }
        return false;
    }

    static List<Node> children(Node n) {
        final List<Node> out = new ArrayList<>();
        final NodeList l = n.getChildNodes();
        for (int i = 0; i < l.getLength(); i++) {
            if (l.item(i).getNodeType() == Node.ELEMENT_NODE) {
                out.add(l.item(i));
            }
        }
        return out;
    }

    static String attr(Node n, String name) {
        if (n.getAttributes() == null) {
            return null;
        }
        final Node a = n.getAttributes().getNamedItem(name);
        return a == null ? null : a.getNodeValue();
    }

    private static String nz(String s) {
        return s == null ? "0" : s;
    }

    private static String nz0(String s) {
        return s == null || s.isBlank() ? "0" : s;
    }
}

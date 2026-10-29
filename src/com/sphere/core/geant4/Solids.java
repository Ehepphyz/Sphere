package com.sphere.core.geant4;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The solids the editor knows, by GDML element name. */
public final class Solids {

    private Solids() {
    }

    /** One entry of the catalogue. */
    public record Type(String tag, String g4Class, String label, String group,
                       Function<String, Solid> maker) {
    }

    private static final Map<String, Type> TYPES = new LinkedHashMap<>();

    static {
        add("box", "G4Box", "Box", "CSG", Shapes.Box::new);
        add("tube", "G4Tubs", "Tube", "CSG", Shapes.Tubs::new);
        add("cone", "G4Cons", "Cone", "CSG", Shapes.Cons::new);
        add("sphere", "G4Sphere", "Sphere", "CSG", Shapes.Sphere::new);
        add("orb", "G4Orb", "Orb", "CSG", Shapes.Orb::new);
        add("torus", "G4Torus", "Torus", "CSG", Shapes.Torus::new);
        add("trd", "G4Trd", "Trapezoid (trd)", "CSG", Convex.Trd::new);
        add("trap", "G4Trap", "General trapezoid", "CSG", Convex.Trap::new);
        add("para", "G4Para", "Parallelepiped", "CSG", Convex.Para::new);
        add("cutTube", "G4CutTubs", "Cut tube", "CSG", Shapes.CutTubs::new);
        add("polycone", "G4Polycone", "Polycone", "Specific", Shapes.Polycone::new);
        add("genericPolycone", "G4GenericPolycone", "Revolved profile", "Specific", Shapes.GenericPolycone::new);
        add("polyhedra", "G4Polyhedra", "Polyhedra", "Specific", Shapes.Polyhedra::new);
        add("genericPolyhedra", "G4Polyhedra", "Polyhedra by its corners", "Specific", Shapes.GenericPolyhedra::new);
        add("xtru", "G4ExtrudedSolid", "Extrusion", "Specific", Extruded::new);
        add("eltube", "G4EllipticalTube", "Elliptical tube", "Specific", Shapes.EllipticalTube::new);
        add("ellipsoid", "G4Ellipsoid", "Ellipsoid", "Specific", Shapes.Ellipsoid::new);
        add("elcone", "G4EllipticalCone", "Elliptical cone", "Specific", Shapes.EllipticalCone::new);
        add("paraboloid", "G4Paraboloid", "Paraboloid", "Specific", Shapes.Paraboloid::new);
        add("hype", "G4Hype", "Hyperbolic tube", "Specific", Shapes.Hype::new);
        add("tet", "G4Tet", "Tetrahedron", "Specific", Convex.Tet::new);
        add("arb8", "G4GenericTrap", "Generic trapezoid (arb8)", "Specific", GenericTrap::new);
        add("tessellated", "G4TessellatedSolid", "Tessellated surface", "Specific", Tessellated::new);
    }

    /** Solids built on another one, created with their operand like the booleans. */
    public static final String SCALED = "scaledSolid";

    private static void add(String tag, String g4, String label, String group, Function<String, Solid> maker) {
        TYPES.put(tag, new Type(tag, g4, label, group, maker));
    }

    public static List<Type> types() {
        return List.copyOf(TYPES.values());
    }

    public static Type type(String tag) {
        return TYPES.get(tag);
    }

    public static boolean known(String tag) {
        return TYPES.containsKey(tag) || BooleanSolid.Op.ofTag(tag) != null || SCALED.equals(tag);
    }

    /** A new primitive with Geant4-sized defaults; booleans are built with their operands. */
    public static Solid create(String tag, String name) {
        final Type t = TYPES.get(tag);
        if (t == null) {
            throw new IllegalArgumentException("unknown solid " + tag);
        }
        return t.maker.apply(name);
    }

    /** By Geant4 class name, as the console accepts "G4Tubs" as well as "tube". */
    public static Type byClass(String g4) {
        for (Type t : TYPES.values()) {
            if (t.g4Class.equalsIgnoreCase(g4) || t.tag.equalsIgnoreCase(g4) || t.label.equalsIgnoreCase(g4)) {
                return t;
            }
        }
        return null;
    }
}

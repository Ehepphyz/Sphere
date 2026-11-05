package com.sphere.core.geant4;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an English sentence into console commands. It knows the words of
 * detector building (layer, plate, wall, cylinder, sphere, calorimeter,
 * tracker...), materials by their common names (lead, silicon, water,
 * scintillator...), dimensions by what they measure (thickness, radius,
 * length, diameter...) and positions ("at z = 10 cm").
 *
 * It is deliberately a reader, not a guesser: what it understood is printed
 * as the exact commands before they run, so the console teaches its own
 * language as it goes.
 */
public final class Intent {

    private final Geometry g;

    public Intent(Geometry g) {
        this.g = g;
    }

    // ------------------------------------------------------------------
    // Vocabulary
    // ------------------------------------------------------------------

    private static final Map<String, String> MATERIALS = new LinkedHashMap<>();

    static {
        m("G4_Pb", "lead", "pb");
        m("G4_W", "tungsten", "wolfram");
        m("G4_Fe", "iron");
        m("G4_STAINLESS-STEEL", "steel", "stainless", "stainless steel");
        m("G4_Cu", "copper");
        m("G4_Al", "aluminum");
        m("G4_Si", "silicon");
        m("G4_Ge", "germanium");
        m("G4_Au", "gold");
        m("G4_Ag", "silver");
        m("G4_Ti", "titanium");
        m("G4_Be", "beryllium");
        m("G4_C", "carbon");
        m("G4_GRAPHITE", "graphite");
        m("G4_U", "uranium");
        m("G4_WATER", "water");
        m("G4_AIR", "air");
        m("G4_Galactic", "vacuum", "galactic");
        m("G4_PLASTIC_SC_VINYLTOLUENE", "scintillator", "plastic scintillator");
        m("G4_PbWO4", "pbwo4", "lead tungstate");
        m("G4_BGO", "bgo");
        m("G4_CESIUM_IODIDE", "csi", "cesium iodide");
        m("G4_SODIUM_IODIDE", "nai", "sodium iodide");
        m("G4_GLASS_LEAD", "lead glass");
        m("G4_GLASS_PLATE", "glass");
        m("G4_lAr", "liquid argon", "lar");
        m("G4_lXe", "liquid xenon", "lxe");
        m("G4_Ar", "argon");
        m("G4_Xe", "xenon");
        m("G4_He", "helium");
        m("G4_KAPTON", "kapton", "polyimide");
        m("G4_MYLAR", "mylar");
        m("G4_POLYETHYLENE", "polyethylene", "pe");
        m("G4_PLEXIGLASS", "plexiglass", "pmma", "acrylic");
        m("G4_CONCRETE", "concrete");
        m("G4_TEFLON", "teflon", "ptfe");
        m("G4_NYLON-6-6", "nylon");
        m("G4_BONE_COMPACT_ICRU", "bone");
        m("G4_TISSUE_SOFT_ICRP", "tissue", "soft tissue");
        m("G4_MUSCLE_SKELETAL_ICRP", "muscle");
        m("G4_LITHIUM_FLUORIDE", "lif", "lithium fluoride");
    }

    private static void m(String nist, String... words) {
        for (String w : words) {
            MATERIALS.put(w.trim(), nist);
        }
    }

    /** The NIST material for a common name ("lead", "G4_Pb", "Pb"), or null. */
    public static String material(String word) {
        if (word == null) {
            return null;
        }
        final String w = fold(word).trim();
        if (NistDB.get().material(word) != null) {
            return word;
        }
        if (NistDB.get().material("G4_" + word) != null) {
            return "G4_" + word;
        }
        return MATERIALS.get(w);
    }

    private static final String NUM = "([-+]?[0-9]+(?:[.,][0-9]+)?(?:[eE][-+]?[0-9]+)?)";
    private static final String UNIT = "(mm|cm|dm|m|km|um|µm|μm|microns?|micrometers?|nm|deg|degrees?|rad|mrad)";
    private static final Pattern QUANTITY = Pattern.compile(NUM + "\\s*" + UNIT + "\\b");
    private static final Pattern DIMS = Pattern.compile(NUM + "\\s*" + UNIT + "?\\s*[x×*]\\s*" + NUM + "\\s*" + UNIT
        + "?\\s*(?:[x×*]\\s*" + NUM + "\\s*" + UNIT + "?)?");
    private static final Pattern COORD = Pattern.compile("\\b([xyz])\\s*=\\s*" + NUM + "\\s*" + UNIT + "?");
    /** "10 layers", "6 silicon planes", "3 thin lead plates": a count, up to two words (not units) before the noun. */
    private static final Pattern COUNT = Pattern.compile("\\b([0-9]+)\\s+(?:(?!(?:mm|cm|dm|m|km|um|nm|microns?|"
        + "micrometers?|deg|degrees?|rad|mrad|x)\\b)[a-z]+\\s+){0,2}?(layers?|planes?|plates?|copies|cylinders?|tubes?|"
        + "crystals?|boxes?|modules?|disks?|slabs?|sheets?|foils?|blocks?|bricks?|walls?)\\b");

    static String fold(String s) {
        final String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replace('’', '\'');
    }

    private static String unit(String u) {
        if (u == null) {
            return null;
        }
        return switch (u) {
            case "µm", "μm", "micron", "microns", "micrometer", "micrometers" -> "um";
            case "degree", "degrees" -> "deg";
            case "dm" -> "decimeter";
            default -> u;
        };
    }

    private static String q(String num, String u, String fallback) {
        return num.replace(',', '.') + "*" + (u == null ? fallback : unit(u));
    }

    // ------------------------------------------------------------------

    /** Commands for a sentence, empty when it says nothing the reader knows. */
    public List<String> interpret(String sentence, String lengthUnit, String defaultMaterial) {
        final String s = " " + fold(sentence) + " ";
        final List<String> out = new ArrayList<>();

        // Actions on the document first
        if (has(s, "overlap", "check")) {
            out.add("check");
            return out;
        }
        if (has(s, "budget", "x/x0", "radiation length")) {
            out.add("budget");
            return out;
        }
        if (has(s, "mass", "weight")) {
            out.add("mass");
            return out;
        }
        if (has(s, "advice", "advise", "analyze", "analysis", "review", "what do you think")) {
            out.add("advise");
            return out;
        }
        if (has(s, "fit the world", "world fit", "resize the world", "adjust the world", "grow the world",
            "shrink the world")) {
            out.add("world fit");
            return out;
        }
        if (has(s, "undo")) {
            out.add("undo");
            return out;
        }
        final String named = namedObject(s);
        if (named != null) {
            if (has(s, "delete", "remove", "erase")) {
                out.add("delete " + named);
                return out;
            }
            if (has(s, "sensitive", "active detector")) {
                out.add("sensitive " + named);
                return out;
            }
            if (has(s, "hide")) {
                out.add("hide " + named);
                return out;
            }
            if (has(s, "show", "display")) {
                out.add("show " + named);
                return out;
            }
            if (has(s, "move", "translate", "shift")) {
                final String[] d = axisAmount(s, lengthUnit, "mm");
                if (d != null) {
                    out.add("move " + named + " " + d[0] + " " + d[1] + " " + d[2]);
                    return out;
                }
            }
            if (has(s, "rotate", "turn")) {
                final String[] d = axisAmount(s, "deg", "deg");
                if (d != null) {
                    out.add("rotate " + named + " " + d[0] + " " + d[1] + " " + d[2]);
                    return out;
                }
            }
            final String mat = findMaterial(s);
            if (mat != null && has(s, "material", "make", "change", "set", "made of", " into ", " to ", " in ")) {
                out.add("set " + named + ".material = " + mat);
                return out;
            }
            if (has(s, "info", "describe", "what is")) {
                out.add("info " + named);
                return out;
            }
        }

        // Templates
        final String material = findMaterial(s);
        final Matcher cm = COUNT.matcher(s);
        final Integer count = cm.find() ? Integer.parseInt(cm.group(1)) : null;
        if (has(s, "calorimet")) {
            final StringBuilder b = new StringBuilder("template calorimeter");
            if (count != null) {
                b.append(" layers=").append(count);
            }
            final List<String[]> mats = materialsInOrder(s);
            final List<String> qs = quantities(s, lengthUnit);
            if (!mats.isEmpty()) {
                b.append(" absorber=").append(mats.get(0)[0]);
            }
            if (mats.size() > 1) {
                b.append(" active=").append(mats.get(1)[0]);
            }
            // "lead 2 mm, scintillator 5 mm": the quantity after each material
            if (mats.size() >= 1) {
                final String a = quantityAfter(s, mats.get(0)[1], lengthUnit);
                if (a != null) {
                    b.append(" abs=").append(a);
                }
            }
            if (mats.size() >= 2) {
                final String a = quantityAfter(s, mats.get(1)[1], lengthUnit);
                if (a != null) {
                    b.append(" act=").append(a);
                }
            }
            final String size = measured(s, lengthUnit, "size", " side", "width");
            if (size != null) {
                b.append(" size=").append(size);
            } else if (mats.isEmpty() && !qs.isEmpty()) {
                b.append(" size=").append(qs.get(0));
            }
            coordZ(s, lengthUnit).ifPresent(z -> b.append(" z=").append(z));
            out.add(b.toString());
            return out;
        }
        if (has(s, "tracker", "barrel")) {
            final StringBuilder b = new StringBuilder("template tracker");
            if (count != null) {
                b.append(" layers=").append(count);
            }
            final List<String> qs = quantities(s, lengthUnit);
            final Matcher range = Pattern.compile("(?:from|between)\\s+" + NUM + "\\s*" + UNIT + "?\\s+(?:to|and)\\s+" + NUM
                + "\\s*" + UNIT).matcher(s);
            if (range.find()) {
                b.append(" r0=").append(q(range.group(1), range.group(2) != null ? range.group(2) : range.group(4), lengthUnit));
                b.append(" rmax=").append(q(range.group(3), range.group(4), lengthUnit));
            }
            final String t = measured(s, lengthUnit, "thickness", "thick");
            if (t != null) {
                b.append(" thickness=").append(t);
            }
            final String l = measured(s, lengthUnit, "length", " long");
            if (l != null) {
                b.append(" length=").append(l);
            }
            if (material != null) {
                b.append(" material=").append(material);
            }
            if (!range.find(0) && qs.size() == 1 && t == null && l == null) {
                b.append(" r0=").append(qs.get(0));
            }
            out.add(b.toString());
            return out;
        }
        if (has(s, "telescope") || (count != null && has(s, "planes") && material != null)) {
            final StringBuilder b = new StringBuilder("template telescope");
            if (count != null) {
                b.append(" planes=").append(count);
            }
            final String p = measured(s, lengthUnit, "spaced by", "spaced", "pitch", "every", "apart");
            if (p != null) {
                b.append(" pitch=").append(p);
            }
            final String t = measured(s, lengthUnit, "thickness", "thick");
            if (t != null) {
                b.append(" thickness=").append(t);
            }
            if (material != null) {
                b.append(" material=").append(material);
            }
            out.add(b.toString());
            return out;
        }
        if (has(s, "shield")) {
            final StringBuilder b = new StringBuilder("template shield");
            final String t = measured(s, lengthUnit, "thickness", "thick");
            if (t != null) {
                b.append(" thickness=").append(t);
            }
            if (material != null) {
                b.append(" material=").append(material);
            }
            out.add(b.toString());
            return out;
        }
        if (has(s, "phantom")) {
            out.add("template phantom");
            return out;
        }
        if (has(s, " tpc", "time projection")) {
            out.add("template tpc" + (material != null ? " gas=" + material : ""));
            return out;
        }

        // Primitives
        final String mat = material != null ? material : defaultMaterial;
        final String at = position(s, lengthUnit);
        String shape = null;
        if (has(s, "layer", "plate", "slab", "wall", "foil", "sheet", "box", "block", "cube", "brick", "slice",
            "absorber")) {
            shape = "box";
        } else if (has(s, "cylinder", "tube", "pipe", "disk", " ring", " rod")) {
            shape = "tube";
        } else if (has(s, "sphere", "ball", "bead", " orb")) {
            shape = "sphere";
        } else if (has(s, " cone")) {
            shape = "cone";
        } else if (has(s, "torus", "donut")) {
            shape = "torus";
        }
        if (shape == null) {
            return out;
        }
        final String thick = measured(s, lengthUnit, "thickness", "thick", "deep", "depth");
        final String radius = measured(s, lengthUnit, "outer radius", "radius");
        final String diameter = measured(s, lengthUnit, "diameter");
        final String length = measured(s, lengthUnit, "length", " long", "height", " high", " tall");
        final String width = measured(s, lengthUnit, "width", " wide", " side");
        final String inner = measured(s, lengthUnit, "inner radius", " bore", " hole");
        final List<String> qs = quantities(s, lengthUnit);
        final Matcher dims = DIMS.matcher(s);
        final String name = nameFor(shape, s);
        // the depth along z of one, which a stack of them is spaced by when no spacing is said
        String depth = null;
        switch (shape) {
            case "box" -> {
                String x, y, z;
                if (dims.find()) {
                    final String u = lastNonNull(dims.group(2), dims.group(4), dims.group(6), lengthUnit);
                    x = q(dims.group(1), dims.group(2) != null ? dims.group(2) : u, lengthUnit);
                    y = q(dims.group(3), dims.group(4) != null ? dims.group(4) : u, lengthUnit);
                    z = dims.group(5) != null ? q(dims.group(5), dims.group(6) != null ? dims.group(6) : u, lengthUnit)
                        : thick != null ? thick : "1*cm";
                } else {
                    // A layer: thin along z, as wide as what it is put in front of
                    final String side = width != null ? width : defaultSide(lengthUnit);
                    x = side;
                    y = side;
                    // "a 2 mm lead layer": the one length given is the thickness
                    z = thick != null ? thick : !qs.isEmpty() && width == null ? qs.get(0)
                        : qs.size() > 1 ? qs.get(1) : "1*cm";
                }
                out.add("box " + name + " " + x + " " + y + " " + z + " " + mat + at);
                depth = z;
            }
            case "tube" -> {
                String r = radius != null ? radius : diameter != null ? "(" + diameter + ")/2" : null;
                String l = length != null ? length : thick;
                if (r == null && !qs.isEmpty()) {
                    r = qs.get(0);
                }
                if (l == null && qs.size() > 1) {
                    l = qs.get(1);
                }
                out.add("tube " + name + " " + (r == null ? "5*cm" : r) + " " + (l == null ? "10*cm" : l)
                    + (inner != null ? " rmin=" + inner : "") + " " + mat + at);
                depth = l == null ? "10*cm" : l;
            }
            case "sphere" -> {
                String r = radius != null ? radius : diameter != null ? "(" + diameter + ")/2" : qs.isEmpty() ? "5*cm" : qs.get(0);
                out.add("sphere " + name + " " + r + (inner != null ? " rmin=" + inner : "") + " " + mat + at);
            }
            case "cone" -> {
                final String r1 = qs.size() > 0 ? qs.get(0) : "5*cm", r2 = qs.size() > 2 ? qs.get(1) : "0*mm";
                final String l = length != null ? length : qs.size() > 1 ? qs.get(qs.size() - 1) : "10*cm";
                out.add("cone " + name + " " + r1 + " " + r2 + " " + l + " " + mat + at);
            }
            default -> {
                final String rt = qs.size() > 0 ? qs.get(0) : "20*cm", rm = qs.size() > 1 ? qs.get(1) : "2*cm";
                out.add("torus " + name + " " + rt + " " + rm + " " + mat + at);
            }
        }
        // "5 layers ... spaced by 10 cm": a row of copies; "10 layers of 5 mm lead": a stack, each against the last
        if (count != null && count > 1) {
            final String spacing = measured(s, lengthUnit, "spaced by", "spaced", "spacing", "every", "pitch", "apart");
            final String pitch = spacing != null ? spacing : depth;
            if (pitch != null) {
                final char ax = s.contains(" along x") || s.contains(" in x ") ? 'x'
                    : s.contains(" along y") || s.contains(" in y ") ? 'y' : 'z';
                out.add("copy " + name + " " + (count - 1) + " " + (ax == 'x' ? pitch : "0") + " " + (ax == 'y' ? pitch : "0")
                    + " " + (ax == 'z' ? pitch : "0"));
            }
        }
        return out;
    }

    private String nameFor(String shape, String s) {
        final Matcher m = Pattern.compile("(?:named|called)\\s+([a-z_][a-z0-9_]*)").matcher(s);
        if (m.find()) {
            final String n = m.group(1);
            return Character.toUpperCase(n.charAt(0)) + n.substring(1);
        }
        final String base = switch (shape) {
            case "box" -> s.contains("layer") ? "Layer" : s.contains("wall") ? "Wall" : s.contains("plate") ? "Plate"
                : s.contains("absorber") ? "Absorber" : "Block";
            case "tube" -> s.contains("disk") ? "Disk" : s.contains("pipe") ? "Pipe" : "Cylinder";
            case "sphere" -> "Sphere";
            case "cone" -> "Cone";
            default -> "Torus";
        };
        return g.uniqueName(base);
    }

    /** A sensible transverse size: the biggest daughter of the world, else 10 cm. */
    private String defaultSide(String unit) {
        double best = 0;
        for (Geometry.Instance i : g.instances()) {
            if (i.pv() != null && i.depth() == 1) {
                final BBox b = Geometry.worldBox(i);
                best = Math.max(best, Math.max(b.size().x(), b.size().y()));
            }
        }
        if (best <= 0) {
            return "10*cm";
        }
        final double u = Units.Category.LENGTH.unitValue(unit);
        return Units.num(best / u) + "*" + unit;
    }

    private static String lastNonNull(String a, String b, String c, String fallback) {
        return c != null ? c : b != null ? b : a != null ? a : fallback;
    }

    private static boolean has(String s, String... words) {
        for (String w : words) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private String namedObject(String s) {
        String best = null;
        for (LogicalVolume lv : g.volumes()) {
            if (lv == g.world()) {
                continue;
            }
            final String n = fold(lv.name());
            if (Pattern.compile("\\b" + Pattern.quote(n) + "\\b").matcher(s).find() && (best == null || n.length() > best.length())) {
                best = lv.name();
            }
        }
        for (PhysicalVolume pv : g.placements()) {
            final String n = fold(pv.name());
            if (Pattern.compile("\\b" + Pattern.quote(n) + "\\b").matcher(s).find() && (best == null || n.length() > best.length())) {
                best = pv.name();
            }
        }
        return best;
    }

    private static String findMaterial(String s) {
        String best = null;
        int bestLen = 0;
        for (Map.Entry<String, String> e : MATERIALS.entrySet()) {
            final String w = e.getKey();
            if (w.length() > bestLen && Pattern.compile("(?<![a-z])" + Pattern.quote(w) + "(?![a-z])").matcher(s).find()) {
                best = e.getValue();
                bestLen = w.length();
            }
        }
        final Matcher g4 = Pattern.compile("\\bg4_[a-z0-9_\\-]+").matcher(s);
        if (g4.find()) {
            for (Material m : NistDB.get().materials()) {
                if (m.name().equalsIgnoreCase(g4.group())) {
                    return m.name();
                }
            }
        }
        return best;
    }

    /** Materials in the order they appear, with their position in the sentence. */
    private static List<String[]> materialsInOrder(String s) {
        final List<String[]> found = new ArrayList<>();
        final List<Integer> pos = new ArrayList<>();
        for (Map.Entry<String, String> e : MATERIALS.entrySet()) {
            final Matcher m = Pattern.compile("(?<![a-z])" + Pattern.quote(e.getKey()) + "(?![a-z])").matcher(s);
            while (m.find()) {
                boolean dup = false;
                for (int i = 0; i < found.size(); i++) {
                    final int p = pos.get(i);
                    if (m.start() >= p && m.start() < p + found.get(i)[2].length() || p >= m.start() && p < m.end()) {
                        dup = true;
                        if (e.getKey().length() > found.get(i)[2].length()) {
                            found.set(i, new String[] {e.getValue(), String.valueOf(m.end()), e.getKey()});
                            pos.set(i, m.start());
                        }
                    }
                }
                if (!dup) {
                    found.add(new String[] {e.getValue(), String.valueOf(m.end()), e.getKey()});
                    pos.add(m.start());
                }
            }
        }
        final List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            idx.add(i);
        }
        idx.sort((a, b) -> Integer.compare(pos.get(a), pos.get(b)));
        final List<String[]> out = new ArrayList<>();
        for (int i : idx) {
            out.add(found.get(i));
        }
        return out;
    }

    private static String quantityAfter(String s, String endIndex, String unit) {
        final int from = Integer.parseInt(endIndex);
        final Matcher m = QUANTITY.matcher(s);
        if (m.find(from) && m.start() - from < 20) {
            return q(m.group(1), m.group(2), unit);
        }
        // "2 mm of lead": the quantity just before
        final String before = s.substring(Math.max(0, from - 40), from);
        final Matcher b = QUANTITY.matcher(before);
        String last = null;
        while (b.find()) {
            last = q(b.group(1), b.group(2), unit);
        }
        return last;
    }

    private static List<String> quantities(String s, String unit) {
        final List<String> out = new ArrayList<>();
        final String noCoords = COORD.matcher(s).replaceAll(" ");
        final Matcher m = QUANTITY.matcher(noCoords);
        while (m.find()) {
            if (!"deg".equals(unit(m.group(2)))) {
                out.add(q(m.group(1), m.group(2), unit));
            }
        }
        return out;
    }

    /**
     * The quantity next to a keyword: "thickness of 2 mm", "2 mm thick", "radius 5 cm". With one on each
     * side ("1 cm thick, 10 cm apart"), the nearer one.
     */
    private static String measured(String s, String unit, String... keys) {
        final int none = Integer.MAX_VALUE;
        for (String k : keys) {
            int i = s.indexOf(k);
            while (i >= 0) {
                final int from = i + k.length();
                final Matcher m = QUANTITY.matcher(s.substring(from, Math.min(s.length(), from + 24)));
                final int gapAfter = m.find() && m.start() < 10 ? m.start() : none;
                final String before = s.substring(Math.max(0, i - 22), i);
                final Matcher b = QUANTITY.matcher(before);
                String last = null;
                int end = -1;
                while (b.find()) {
                    last = q(b.group(1), b.group(2), unit);
                    end = b.end();
                }
                final int gapBefore = last != null && before.length() - end < 8 ? before.length() - end : none;
                if (gapAfter != none || gapBefore != none) {
                    return gapAfter <= gapBefore ? q(m.group(1), m.group(2), unit) : last;
                }
                i = s.indexOf(k, i + 1);
            }
        }
        return null;
    }

    private static java.util.Optional<String> coordZ(String s, String unit) {
        final Matcher m = COORD.matcher(s);
        while (m.find()) {
            if (m.group(1).equals("z")) {
                return java.util.Optional.of(q(m.group(2), m.group(3), unit));
            }
        }
        return java.util.Optional.empty();
    }

    /** " at x y z" from "x = .., z = ..", or empty. */
    private static String position(String s, String unit) {
        final Matcher m = COORD.matcher(s);
        final String[] p = {"0", "0", "0"};
        boolean any = false;
        while (m.find()) {
            p["xyz".indexOf(m.group(1))] = q(m.group(2), m.group(3), unit);
            any = true;
        }
        return any ? " at " + p[0] + " " + p[1] + " " + p[2] : "";
    }

    /** A signed amount along an axis: "by 5 cm in z", "by 30 deg about y", "x+2cm". */
    private static String[] axisAmount(String s, String unit, String angleOrLength) {
        final Matcher m = QUANTITY.matcher(s);
        if (!m.find()) {
            return null;
        }
        final String amount = (s.contains("-" + m.group(1)) ? "-" : "") + q(m.group(1), m.group(2), unit);
        int axis = 2;
        final Matcher ax = Pattern.compile("\\b(?:in|along|about|around|on|axis)\\s+(?:the\\s+)?([xyz])\\b"
            + "|\\b([xyz])\\s*[-+]\\s*[0-9.]").matcher(s);
        if (ax.find()) {
            axis = "xyz".indexOf(ax.group(1) != null ? ax.group(1) : ax.group(2));
        }
        final String zero = "0";
        final String[] out = {zero, zero, zero};
        out[axis] = amount;
        return out;
    }
}

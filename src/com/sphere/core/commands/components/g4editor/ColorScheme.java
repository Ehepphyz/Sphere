package com.sphere.components.g4editor;

import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.Material;
import com.sphere.core.geant4.Units;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the view colours volumes by: their own colours, or a property of theirs. A geometry read from a
 * file comes with the colours its author gave it (ROOT's are often all alike); coloured by material,
 * density or radiation length it shows what it is made of at a glance, by depth how it nests, and by
 * sensitivity what it measures. Each scheme carries its legend.
 */
final class ColorScheme {

    enum Mode {
        DOCUMENT("Document colors", "document"),
        MATERIAL("Material", "material"),
        DENSITY("Density", "density"),
        X0("Radiation length X0", "x0"),
        DEPTH("Depth in the tree", "depth"),
        SENSITIVE("Sensitive detectors", "sensitive");

        final String label, word;

        Mode(String label, String word) {
            this.label = label;
            this.word = word;
        }

        static Mode of(String word) {
            for (Mode m : values()) {
                if (m.word.equalsIgnoreCase(word) || m.name().equalsIgnoreCase(word)) {
                    return m;
                }
            }
            return null;
        }
    }

    /** A line of a categorical legend: a colour, what it stands for, how many drawn copies have it. */
    record Entry(int rgb, String label, int copies) {
    }

    // Qualitative, for materials: distinct hues, the most used materials getting the first ones
    private static final int[] QUALITATIVE = {0x4E79A7, 0xF28E2B, 0xE15759, 0x76B7B2, 0x59A14F, 0xEDC948, 0xB07AA1,
        0xFF9DA7, 0x9C755F, 0x86BCB6, 0xD37295, 0x8CD17D};
    private static final int OTHERS = 0xA9B0BA, UNKNOWN = 0x6E7681;
    // Viridis: perceptually even, readable in grey, more (denser, shorter X0, deeper) to the yellow end
    private static final int[] VIRIDIS = {0x440154, 0x46327E, 0x365C8D, 0x277F8E, 0x1FA187, 0x4AC16D, 0xA0DA39, 0xFDE725};
    private static final int SENSITIVE_RGB = 0xFF7F0E, PASSIVE_RGB = 0xB8C0CC;

    final Mode mode;
    /** The colour of each volume (RGB), for the modes that colour volumes, not copies. */
    private final Map<LogicalVolume, Integer> rgb = new IdentityHashMap<>();
    final List<Entry> legend = new ArrayList<>();
    /** For a scale: its ends, as text, and whether more is to the right. */
    final String low, high;
    final boolean continuous;
    private final int maxDepth;

    private ColorScheme(Mode mode, String low, String high, boolean continuous, int maxDepth) {
        this.mode = mode;
        this.low = low;
        this.high = high;
        this.continuous = continuous;
        this.maxDepth = maxDepth;
    }

    /** The scheme for a document, or null for its own colours. Runs over every drawn copy once. */
    static ColorScheme of(Mode mode, Geometry g) {
        if (mode == null || mode == Mode.DOCUMENT) {
            return null;
        }
        final Map<LogicalVolume, Integer> copies = new IdentityHashMap<>();
        int deepest = 0;
        for (Geometry.Instance in : g.instances()) {
            if (in.pv() != null) {
                copies.merge(in.lv(), 1, Integer::sum);
                deepest = Math.max(deepest, in.depth());
            }
        }
        return switch (mode) {
            case MATERIAL -> materials(g, copies);
            case DENSITY -> scale(g, copies, mode, true);
            case X0 -> scale(g, copies, mode, false);
            case DEPTH -> new ColorScheme(mode, "level 1", "level " + deepest, true, Math.max(1, deepest));
            case SENSITIVE -> sensitive(copies);
            default -> null;
        };
    }

    private static ColorScheme materials(Geometry g, Map<LogicalVolume, Integer> copies) {
        final Map<String, Integer> byMaterial = new HashMap<>();
        copies.forEach((lv, n) -> byMaterial.merge(name(lv), n, Integer::sum));
        final List<Map.Entry<String, Integer>> order = new ArrayList<>(byMaterial.entrySet());
        order.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        final ColorScheme s = new ColorScheme(Mode.MATERIAL, null, null, false, 0);
        final Map<String, Integer> colourOf = new HashMap<>();
        int others = 0, otherCopies = 0;
        for (int i = 0; i < order.size(); i++) {
            final String m = order.get(i).getKey();
            if (i < QUALITATIVE.length) {
                colourOf.put(m, QUALITATIVE[i]);
                s.legend.add(new Entry(QUALITATIVE[i], m.replace("G4_", ""), order.get(i).getValue()));
            } else {
                colourOf.put(m, OTHERS);
                others++;
                otherCopies += order.get(i).getValue();
            }
        }
        if (others > 0) {
            s.legend.add(new Entry(OTHERS, others + " other materials", otherCopies));
        }
        for (LogicalVolume lv : copies.keySet()) {
            s.rgb.put(lv, colourOf.get(name(lv)));
        }
        return s;
    }

    private static String name(LogicalVolume lv) {
        return lv.material() == null || lv.material().isBlank() ? "(none)" : lv.material();
    }

    /** Density (more to the right) or radiation length (shorter to the right), on a log scale. */
    private static ColorScheme scale(Geometry g, Map<LogicalVolume, Integer> copies, Mode mode, boolean density) {
        final Map<LogicalVolume, Double> value = new IdentityHashMap<>();
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (LogicalVolume lv : copies.keySet()) {
            final Material m = lv.material() == null ? null : g.materials().material(lv.material());
            if (m == null) {
                continue;
            }
            final double v = density ? m.density() : m.radiationLength();
            if (!(v > 0) || Double.isInfinite(v)) {
                continue;
            }
            final double l = Math.log10(v);
            value.put(lv, l);
            lo = Math.min(lo, l);
            hi = Math.max(hi, l);
        }
        final Units.Category cat = density ? Units.Category.DENSITY : Units.Category.LENGTH;
        final ColorScheme s = value.isEmpty() ? new ColorScheme(mode, "-", "-", true, 0)
            : density ? new ColorScheme(mode, Units.best(Math.pow(10, lo), cat), Units.best(Math.pow(10, hi), cat), true, 0)
            : new ColorScheme(mode, Units.best(Math.pow(10, hi), cat), Units.best(Math.pow(10, lo), cat), true, 0);
        final double span = hi - lo;
        for (LogicalVolume lv : copies.keySet()) {
            final Double l = value.get(lv);
            if (l == null) {
                s.rgb.put(lv, UNKNOWN);
                continue;
            }
            double t = span > 1e-12 ? (l - lo) / span : 1;
            if (!density) {
                t = 1 - t; // a short radiation length is a lot of material
            }
            s.rgb.put(lv, viridis(t));
        }
        return s;
    }

    private static ColorScheme sensitive(Map<LogicalVolume, Integer> copies) {
        final ColorScheme s = new ColorScheme(Mode.SENSITIVE, null, null, false, 0);
        final Map<String, Integer> detectors = new HashMap<>();
        int passive = 0;
        for (Map.Entry<LogicalVolume, Integer> e : copies.entrySet()) {
            final String sd = e.getKey().sensitive();
            s.rgb.put(e.getKey(), sd != null ? SENSITIVE_RGB : PASSIVE_RGB);
            if (sd != null) {
                detectors.merge(sd, e.getValue(), Integer::sum);
            } else {
                passive += e.getValue();
            }
        }
        detectors.forEach((name, n) -> s.legend.add(new Entry(SENSITIVE_RGB, "sensitive: " + name, n)));
        if (detectors.isEmpty()) {
            s.legend.add(new Entry(SENSITIVE_RGB, "no sensitive detector yet", 0));
        }
        s.legend.add(new Entry(PASSIVE_RGB, "passive", passive));
        return s;
    }

    /** The colour of a copy: the scheme's, with the volume's own transparency (air stays see-through). */
    int color(LogicalVolume lv, int depth, int own) {
        final int c;
        if (mode == Mode.DEPTH) {
            c = viridis(maxDepth <= 1 ? 1 : (depth - 1) / (double) (maxDepth - 1));
        } else {
            final Integer v = rgb.get(lv);
            c = v == null ? UNKNOWN : v;
        }
        return (own & 0xFF000000) | c;
    }

    /** The scale's colour at t in [0, 1], for the legend's bar. */
    static int viridis(double t) {
        final double x = Math.max(0, Math.min(1, t)) * (VIRIDIS.length - 1);
        final int i = Math.min(VIRIDIS.length - 2, (int) x);
        final double f = x - i;
        final int a = VIRIDIS[i], b = VIRIDIS[i + 1];
        final int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f);
        final int gg = (int) Math.round(((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f);
        final int bl = (int) Math.round((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return (r << 16) | (gg << 8) | bl;
    }

    String title() {
        return switch (mode) {
            case DENSITY -> "Density (log scale)";
            case X0 -> "Radiation length X0 (log scale, shorter to the right)";
            default -> mode.label;
        };
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s: %d volumes", mode.label, rgb.size());
    }
}

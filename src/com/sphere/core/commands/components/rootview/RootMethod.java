package com.sphere.components.rootview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One function of ROOT's context menus, read from RootMenuTable: its class,
 * its name, whether it is a toggle (and the getter that tells its state),
 * and its parameters with their types and defaults.
 */
public final class RootMethod {

    /** What a parameter's editor should be. */
    public enum Kind { BOOL, INT, REAL, TEXT, OPTION, COLOR, FONT, OBJECT, ARRAY }

    /** One parameter: "Double_t xmin=0" gives type Double_t, name xmin, default 0. */
    public record Param(String type, String name, String defaultValue) {

        public boolean hasDefault() {
            return defaultValue != null;
        }

        public Kind kind() {
            final String t = type.replace("const", "").strip();
            final String n = name.toLowerCase(Locale.ROOT);
            if (t.startsWith("Bool_t") || t.equals("bool")) return Kind.BOOL;
            if (t.contains("*") && (t.startsWith("Double_t") || t.startsWith("Float_t"))) return Kind.ARRAY;
            if (t.startsWith("Color_t") || (t.matches("(Int_t|Short_t)") && n.contains("color"))) return Kind.COLOR;
            if (n.contains("font")) return Kind.FONT;
            if (t.startsWith("Option_t")) return Kind.OPTION;
            if (t.contains("char")) return Kind.TEXT;
            // Any other pointer or reference (Int_t *error, TF1 *f): an object, or nullptr.
            if (t.contains("*") || t.contains("&")) return Kind.OBJECT;
            if (t.matches("(Double_t|Float_t|Axis_t|Coord_t|Size_t|double|float|Stat_t).*")) return Kind.REAL;
            if (t.matches("(Int_t|UInt_t|Long64_t|Long_t|Short_t|UShort_t|Width_t|Style_t|Ssiz_t|int|long|short).*")) {
                return Kind.INT;
            }
            if (t.contains("*") || t.contains("&")) return Kind.OBJECT;
            return Kind.TEXT;
        }

        /** The default as text, quotes removed: what the dialog starts with. */
        public String defaultText() {
            if (defaultValue == null) return "";
            String d = defaultValue.strip();
            if (d.startsWith("\"") && d.endsWith("\"") && d.length() >= 2) d = d.substring(1, d.length() - 1);
            if (d.equals("kTRUE") || d.equals("true")) return "true";
            if (d.equals("kFALSE") || d.equals("false")) return "false";
            if (d.equals("nullptr") || d.equals("0x0") || d.equals("NULL")) return "";
            if (d.equals("kMaxEntries")) return "1000000000000000000";
            if (d.matches("[-+]?\\d+\\.?\\d*[fF]")) d = d.substring(0, d.length() - 1);
            if (d.endsWith(".")) d = d + "0";
            return d;
        }
    }

    public final String owner;
    public final boolean toggle;
    public final String name;
    public final String getter;
    public final List<Param> params;
    public final String rawParams;

    private RootMethod(String owner, boolean toggle, String name, String getter, String rawParams) {
        this.owner = owner;
        this.toggle = toggle;
        this.name = name;
        this.getter = getter == null || getter.isEmpty() ? null : getter;
        this.rawParams = rawParams;
        this.params = parse(rawParams);
    }

    /** "Rebin(Int_t ngroup=2, const char* newname=\"\")": the line a menu shows. */
    public String signature() {
        return name + "(" + rawParams.replaceAll("\\s+", " ").replace(" ,", ",").strip() + ")";
    }

    /** The function as ROOT's menu labels it: its name, and a toggle shows no parameters. */
    public String label() {
        return name;
    }

    public String key() {
        return owner + "::" + name;
    }

    @Override
    public String toString() {
        return owner + "::" + signature();
    }

    /* ------------------------------------------------------------------ */
    /* Parameters                                                          */
    /* ------------------------------------------------------------------ */

    static List<Param> parse(String raw) {
        final List<Param> out = new ArrayList<>();
        if (raw == null || raw.isBlank() || raw.strip().equals("void")) return out;
        for (String part : split(raw)) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            String def = null;
            final int eq = topLevelEquals(p);
            if (eq >= 0) {
                def = p.substring(eq + 1).strip();
                p = p.substring(0, eq).strip();
            }
            final java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)([A-Za-z_]\\w*)\\s*(\\[\\s*\\])?$").matcher(p);
            if (m.matches() && !m.group(1).isBlank()) {
                out.add(new Param(m.group(1).strip() + (m.group(3) != null ? "*" : ""), m.group(2), def));
            } else {
                out.add(new Param(p, "arg" + out.size(), def));
            }
        }
        return out;
    }

    /** Commas outside quotes and brackets. */
    private static List<String> split(String raw) {
        final List<String> out = new ArrayList<>();
        int depth = 0;
        boolean quote = false;
        final StringBuilder cur = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == '"' && (i == 0 || raw.charAt(i - 1) != '\\')) quote = !quote;
            if (!quote && (c == '(' || c == '<' || c == '[')) depth++;
            if (!quote && (c == ')' || c == '>' || c == ']')) depth--;
            if (c == ',' && depth == 0 && !quote) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static int topLevelEquals(String p) {
        boolean quote = false;
        for (int i = 0; i < p.length(); i++) {
            final char c = p.charAt(i);
            if (c == '"') quote = !quote;
            if (c == '=' && !quote) return i;
        }
        return -1;
    }

    /* ------------------------------------------------------------------ */
    /* The table                                                           */
    /* ------------------------------------------------------------------ */

    private static Map<String, List<RootMethod>> byClass;
    private static Map<String, List<String>> bases;

    private static synchronized void load() {
        if (byClass != null) return;
        final Map<String, List<RootMethod>> m = new HashMap<>();
        for (String line : RootMenuTable.menus().split("\n")) {
            final String[] f = line.split("\\|", -1);
            if (f.length < 5) continue;
            m.computeIfAbsent(f[0], k -> new ArrayList<>()).add(new RootMethod(f[0], "T".equals(f[1]), f[2], f[3], f[4]));
        }
        final Map<String, List<String>> b = new HashMap<>();
        for (String line : RootMenuTable.bases().split("\n")) {
            final String[] f = line.split("\\|");
            if (f.length < 2) continue;
            final List<String> list = new ArrayList<>();
            for (int i = 1; i < f.length; i++) list.add(f[i]);
            b.put(f[0], list);
        }
        byClass = m;
        bases = b;
    }

    /** The class and all it derives from, the class first, then each base depth first, as TClass walks them. */
    public static List<String> lineage(String className) {
        load();
        final Set<String> out = new LinkedHashSet<>();
        walk(className, out);
        if (!out.contains("TObject")) out.add("TObject");
        return new ArrayList<>(out);
    }

    private static void walk(String c, Set<String> out) {
        if (c == null || !out.add(c)) return;
        for (String b : bases.getOrDefault(c, List.of())) walk(b, out);
    }

    public static boolean inherits(String className, String base) {
        return lineage(className).contains(base);
    }

    /** The functions a class declares itself. */
    public static List<RootMethod> declared(String className) {
        load();
        return Collections.unmodifiableList(byClass.getOrDefault(className, List.of()));
    }

    /**
     * An object's context menu, as ROOT builds it: the functions of its class,
     * then those of each base not already given by a derived class.
     */
    public static List<RootMethod> menuOf(String className) {
        final List<RootMethod> out = new ArrayList<>();
        final Set<String> names = new java.util.HashSet<>();
        for (String c : lineage(className)) {
            for (RootMethod m : declared(c)) {
                final String key = m.name + "/" + m.params.size();
                if (names.add(key)) out.add(m);
            }
        }
        return out;
    }

    /** Every function of the table, for the coverage report. */
    public static List<RootMethod> all() {
        load();
        final List<RootMethod> out = new ArrayList<>();
        for (List<RootMethod> l : byClass.values()) out.addAll(l);
        out.sort((a, b) -> a.toString().compareTo(b.toString()));
        return out;
    }
}

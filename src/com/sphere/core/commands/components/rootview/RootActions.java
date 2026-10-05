package com.sphere.components.rootview;

import com.sphere.components.rootview.RootPadPainter.AxisRef;
import com.sphere.components.rootview.RootPadPainter.PaletteRef;
import com.sphere.components.rootview.RootPadPainter.StatsRef;
import com.sphere.components.rootview.RootPadPainter.TitleRef;
import com.sphere.components.rootview.RootPadPainter.View;
import com.sphere.components.rootview.RootScene.AxisStyle;
import com.sphere.components.rootview.RootScene.Entry;
import com.sphere.components.rootview.RootScene.Graph;
import com.sphere.components.rootview.RootScene.Group;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;
import com.sphere.components.rootview.RootScene.Pave;
import com.sphere.components.rootview.RootScene.Segment;
import com.sphere.components.rootview.RootScene.Shape;
import com.sphere.components.rootview.RootScene.Text;

import javax.swing.JOptionPane;
import java.awt.Color;
import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The functions of ROOT's context menus, done by Sphere on what its canvas
 * holds. Each is registered under "Class::Function" and found as ROOT finds
 * a virtual function: from the object's class up through its bases. A
 * function Sphere does not do itself goes to the ROOT engine when one runs,
 * as the C++ call ROOT would make.
 *
 * This file holds the call itself and the functions of the graphics classes
 * (TObject, TNamed, the TAtt classes, axes, pads, canvases, paves, legends,
 * the statistics box, texts, lines, arrows, ellipses, markers, polylines,
 * boxes and the 3D view); RootDataActions holds those of the histograms,
 * graphs, functions and geometries.
 */
public final class RootActions {

    /** A function: what it does to its target with the arguments of the dialog; a message for the status line. */
    @FunctionalInterface
    interface Action {
        String run(RootTarget t, Object[] a) throws Exception;
    }

    /** How a function is done, and what the dialog starts from. */
    static final class Def {
        final Action action;
        final boolean modifies;
        Function<RootTarget, Object[]> current;
        Predicate<RootTarget> state;
        /** What the function can act on; another object of the class goes on down the lineage, then to ROOT. */
        Predicate<RootTarget> accepts;
        final Map<String, String[]> suggestions = new LinkedHashMap<>();

        Def(Action action, boolean modifies) {
            this.action = action;
            this.modifies = modifies;
        }
    }

    private static final Map<String, Def> DEFS = new HashMap<>();

    static {
        registerGraphics();
        RootDataActions.register();
        guard();
    }

    /**
     * Each function told what it acts on: a TPaveText function on a pave,
     * a TAxis function on an axis, a TH1 function on a histogram. The
     * statistics box is a TPaveStats, a TPaveText, but Sphere draws it from
     * the histogram: its TPaveText functions go to ROOT.
     */
    private static void guard() {
        for (Map.Entry<String, Def> e : DEFS.entrySet()) {
            final String cls = e.getKey().substring(0, e.getKey().indexOf("::"));
            final Predicate<RootTarget> p = switch (cls) {
                case "TPave", "TPaveText", "TLegend", "TPaveLabel" -> t -> t.object instanceof Pave;
                case "TPaveStats" -> t -> t.object instanceof StatsRef || t.object instanceof Pave;
                case "TText" -> t -> t.object instanceof Text;
                case "TLine" -> t -> t.object instanceof Segment || t.object instanceof Shape;
                case "TArrow", "TEllipse", "TMarker", "TPolyLine", "TWbox" -> t -> t.object instanceof Shape;
                case "TAttAxis", "TAxis", "TPaletteAxis" -> t -> t.object instanceof AxisRef || t.object instanceof PaletteRef;
                case "TH1", "TH2", "TH3", "TProfile", "TProfile2D", "TProfile3D", "TF1", "TF2" -> t -> t.item() instanceof Hist;
                case "TGraph", "TGraphErrors", "TGraphAsymmErrors", "TGraphBentErrors", "TGraphMultiErrors" ->
                    t -> t.item() instanceof Graph;
                case "TGraph2D", "TGraph2DErrors", "TGraph2DAsymmErrors" -> t -> t.item() instanceof RootScene.Graph2D;
                case "THStack" -> t -> t.item() instanceof Group;
                default -> null;
            };
            if (p != null && e.getValue().accepts == null) e.getValue().accepts = p;
        }
    }

    private RootActions() {
    }

    /** Registers a function that changes its target. */
    static Def def(String key, Action action) {
        final Def d = new Def(action, true);
        DEFS.put(key, d);
        return d;
    }

    /** Registers a function that only reads its target (Dump, Print, SaveAs...). */
    static Def look(String key, Action action) {
        final Def d = new Def(action, false);
        DEFS.put(key, d);
        return d;
    }

    static Def def(String key) {
        return DEFS.get(key);
    }

    /** The function as the object's class gives it: its own, or the nearest base's. */
    static Def find(String className, String name) {
        for (String c : RootMethod.lineage(className)) {
            final Def d = DEFS.get(c + "::" + name);
            if (d != null) return d;
        }
        return DEFS.get("*::" + name);
    }

    /** The function for this very object: the nearest in its lineage that can act on it. */
    static Def find(RootTarget t, String name) {
        for (String c : RootMethod.lineage(t.className)) {
            final Def d = DEFS.get(c + "::" + name);
            if (d != null && (d.accepts == null || accepts(d, t))) return d;
        }
        final Def any = DEFS.get("*::" + name);
        return any != null && (any.accepts == null || accepts(any, t)) ? any : null;
    }

    private static boolean accepts(Def d, RootTarget t) {
        try {
            return d.accepts.test(t);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean implemented(RootTarget t, RootMethod m) {
        return find(t, m.name) != null;
    }

    /** Whether Sphere does a function of a class itself, for the coverage report. */
    public static boolean implemented(String className, String method) {
        return find(className, method) != null;
    }

    /** The state of a toggle, as its getter answers; null when unknown. */
    static Boolean state(RootTarget t, RootMethod m) {
        final Def d = find(t, m.name);
        return d == null || d.state == null ? null : d.state.test(t);
    }

    /* ------------------------------------------------------------------ */
    /* The call                                                            */
    /* ------------------------------------------------------------------ */

    /** Calls a function of the menu on its target, as TContextMenu::Action does. */
    public static void invoke(RootTarget t, RootMethod m, Component parent, RootHost host) {
        final Def d = find(t, m.name);
        if (d == null) {
            RootEngineCall.offer(t, m, parent, host);
            return;
        }
        Object[] args;
        if (m.params.isEmpty()) {
            args = new Object[0];
        } else if (m.toggle) {
            final Boolean on = d.state == null ? null : d.state.test(t);
            final boolean next = on == null || !on;
            args = new Object[]{m.params.get(0).kind() == RootMethod.Kind.BOOL ? (Object) next : (Object) (next ? 1L : 0L)};
        } else {
            final Object[] start = d.current == null ? null : d.current.apply(t);
            final List<RootArgsDialog.Choice> objects = new ArrayList<>();
            for (RootMethod.Param p : m.params) {
                if (p.kind() == RootMethod.Kind.OBJECT) {
                    final String base = p.type().replace("const", "").replace("*", "").replace("&", "").strip();
                    objects.addAll(t.canvas.objectsOf(base));
                    if (host != null) {
                        for (Map.Entry<String, Object> e : host.objects(base).entrySet()) {
                            if (objects.stream().noneMatch(c -> c.value() == e.getValue())) {
                                objects.add(new RootArgsDialog.Choice(e.getKey(), e.getValue()));
                            }
                        }
                    }
                    break;
                }
            }
            args = RootArgsDialog.ask(parent, t, m, start, d.suggestions, objects, a -> run(t, m, d, a, host, parent));
            if (args == null) return;
        }
        run(t, m, d, args, host, parent);
    }

    private static void run(RootTarget t, RootMethod m, Def d, Object[] args, RootHost host, Component parent) {
        if (d.modifies && t.pad != null && !t.pad.editable && !m.name.equals("SetEditable")) {
            status(host, t.title() + ": the pad is not editable (TPad::SetEditable)");
            return;
        }
        if (d.modifies) t.canvas.checkpoint();
        try {
            final String said = d.action.run(t, args);
            if (said != null) status(host, said);
            t.canvas.changed();
        } catch (Exception e) {
            final String why = e.getMessage() == null ? e.toString() : e.getMessage();
            status(host, m.owner + "::" + m.name + ": " + why);
            JOptionPane.showMessageDialog(parent, why, t.title() + " — " + m.name, JOptionPane.WARNING_MESSAGE);
        }
    }

    private static void status(RootHost host, String s) {
        if (host != null) host.status(s);
    }

    /* ------------------------------------------------------------------ */
    /* Reading the arguments                                               */
    /* ------------------------------------------------------------------ */

    static double d(Object[] a, int i, double fallback) {
        if (a == null || i >= a.length || a[i] == null) return fallback;
        if (a[i] instanceof Number n) return n.doubleValue();
        if (a[i] instanceof Boolean b) return b ? 1 : 0;
        try {
            return Double.parseDouble(String.valueOf(a[i]).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static int i(Object[] a, int k, int fallback) {
        return (int) Math.round(d(a, k, fallback));
    }

    static boolean b(Object[] a, int k, boolean fallback) {
        if (a == null || k >= a.length || a[k] == null) return fallback;
        if (a[k] instanceof Boolean v) return v;
        return d(a, k, fallback ? 1 : 0) != 0;
    }

    static String s(Object[] a, int k, String fallback) {
        if (a == null || k >= a.length || a[k] == null) return fallback;
        return String.valueOf(a[k]);
    }

    static Color c(Object[] a, int k, Color fallback) {
        if (a == null || k >= a.length || a[k] == null) return fallback;
        if (a[k] instanceof Color col) return col;
        if (a[k] instanceof Number n) return RootColors.color(n.intValue());
        final Color parsed = RootColors.parse(String.valueOf(a[k]));
        return parsed == null ? fallback : parsed;
    }

    static Color withAlpha(Color c, double alpha) {
        if (c == null || alpha >= 1 || alpha < 0) return c;
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * alpha));
    }

    /* ------------------------------------------------------------------ */
    /* Targets                                                             */
    /* ------------------------------------------------------------------ */

    static View view(RootTarget t) {
        return t.canvas.view(t.pad);
    }

    /** The axis a target stands for: 0 x, 1 y, 2 z. */
    static int axisOf(RootTarget t) {
        if (t.object instanceof AxisRef a) return a.axis();
        if (t.object instanceof PaletteRef) return 2;
        return 0;
    }

    static AxisStyle style(RootTarget t) {
        return t.pad.axes[axisOf(t)];
    }

    static Hist hist(RootTarget t) {
        final Item i = t.item();
        if (i instanceof Hist h) return h;
        throw new IllegalStateException("not a histogram");
    }

    static Pave pave(RootTarget t) {
        if (t.object instanceof Pave p) return p;
        throw new IllegalStateException("not a pave");
    }

    static Item item(RootTarget t) {
        if (t.object instanceof Item i) return i;
        throw new IllegalStateException(t.className + " has no attributes of its own here");
    }

    /** The pad's frame range on an axis, as shown. */
    static double[] range(RootTarget t, int axis) {
        final View v = view(t);
        return switch (axis) {
            case 0 -> new double[]{v.x0, v.x1};
            case 1 -> new double[]{v.y0, v.y1};
            default -> v.zr != null ? v.zr.clone() : t.item() instanceof Hist h ? RootPadPainter.zRange(h, false)
                : new double[]{0, 1};
        };
    }

    /** Where the mouse was, in the pad's NDC, for the functions that act there. */
    static double[] ndcAt(RootTarget t) {
        final java.awt.Rectangle area = t.canvas.areaOf(t.pad);
        if (area == null || t.at == null) return new double[]{0.5, 0.5};
        return new double[]{(t.at.x - area.x) / (double) Math.max(1, area.width),
            1 - (t.at.y - area.y) / (double) Math.max(1, area.height)};
    }

    /* ------------------------------------------------------------------ */
    /* The graphics classes                                                */
    /* ------------------------------------------------------------------ */

    private static void registerGraphics() {
        registerObject();
        registerAttributes();
        registerAxes();
        registerPad();
        registerPaves();
        registerShapes();
        registerView3D();
    }

    /* ---- TObject, TNamed --------------------------------------------- */

    private static void registerObject() {
        def("TObject::Delete", (t, a) -> {
            if (t.object instanceof StatsRef s) {
                s.hist().stats = false;
                return "statistics box removed";
            }
            if (t.object instanceof TitleRef r) {
                r.owner().title = "";
                return "title removed";
            }
            if (t.object instanceof AxisRef || t.object instanceof PaletteRef) {
                throw new IllegalStateException("an axis belongs to its histogram and cannot be deleted alone");
            }
            if (t.object instanceof Pad p && p != t.scene.pad) {
                if (!removePad(t.scene.pad, p)) throw new IllegalStateException("pad not found");
                return "pad " + p.name + " deleted";
            }
            if (t.object instanceof Item it) {
                if (!removeItem(t.scene.pad, it)) throw new IllegalStateException("object not found");
                return it.className + " " + it.name + " deleted";
            }
            throw new IllegalStateException("the canvas itself is closed, not deleted");
        });
        look("TObject::DrawClass", (t, a) -> {
            t.canvas.host().show("Class " + t.className, classReport(t.className));
            return null;
        });
        look("TObject::DrawClone", (t, a) -> {
            t.canvas.host().open(clone(t, s(a, 0, "")), t.name() + " (clone)");
            return "clone of " + t.title() + " drawn";
        });
        look("TObject::Dump", (t, a) -> {
            t.canvas.host().show("Dump " + t.title(), dump(t.object, t.className));
            return null;
        });
        look("TObject::Inspect", (t, a) -> {
            t.canvas.host().show("Inspect " + t.title(), inspect(t.object, 0, new java.util.IdentityHashMap<>()));
            return null;
        });
        look("TObject::SaveAs", (t, a) -> RootSaver.save(t, s(a, 0, ""), s(a, 1, "")));
        def("TObject::SetDrawOption", (t, a) -> {
            final String o = s(a, 0, "");
            if (t.item() == t.pad.main() || t.object instanceof Pad) {
                t.canvas.setOption(t.pad, o);
            } else {
                item(t).option = o;
            }
            return "draw option " + (o.isBlank() ? "reset" : o);
        }).current = t -> new Object[]{t.item() == null ? "" : t.canvas.optionOf(t.pad)};
        find("TObject", "SetDrawOption").suggestions.put("option", new String[]{"", "HIST", "E", "E1", "E2", "P", "L",
            "C", "BAR", "COLZ", "COL", "CONT", "CONT1", "LEGO", "LEGO2", "SURF", "SURF1", "SURF2", "BOX", "SCAT", "TEXT",
            "AP", "ALP", "AC", "TRI1", "P0"});

        def("TNamed::SetName", (t, a) -> {
            final String n = s(a, 0, "");
            if (t.object instanceof Item i) i.name = n;
            else if (t.object instanceof Pad p) p.name = n;
            else if (t.object instanceof RootScene s) s.name = n;
            else throw new IllegalStateException("no name to set here");
            return "renamed " + n;
        }).current = t -> new Object[]{t.name()};
        def("TNamed::SetTitle", (t, a) -> {
            final String s = s(a, 0, "");
            if (t.object instanceof AxisRef r) setAxisTitle(t, r.axis(), s);
            else if (t.object instanceof PaletteRef r) r.hist().zTitle = s;
            else if (t.object instanceof TitleRef r) r.owner().title = s;
            else if (t.object instanceof Item i) i.title = s;
            else if (t.object instanceof Pad p) p.title = s;
            else if (t.object instanceof RootScene sc) sc.title = s;
            return "title set";
        }).current = t -> new Object[]{t.object instanceof AxisRef r ? axisTitle(t, r.axis())
            : t.object instanceof TitleRef r ? r.owner().title : t.object instanceof Item i ? i.title
            : t.object instanceof Pad p ? p.title : t.scene.title};
    }

    static String axisTitle(RootTarget t, int axis) {
        final Item main = t.pad.main();
        if (main instanceof Hist h) {
            return axis == 0 ? h.x.title : axis == 1 ? (h.dim == 1 ? h.yTitle : h.y.title) : (h.dim == 3 ? h.z.title : h.zTitle);
        }
        if (main instanceof Graph g) return axis == 0 ? g.xTitle : g.yTitle;
        return "";
    }

    static void setAxisTitle(RootTarget t, int axis, String s) {
        final Item main = t.pad.main();
        if (main instanceof Hist h) {
            if (axis == 0) h.x.title = s;
            else if (axis == 1 && h.dim == 1) h.yTitle = s;
            else if (axis == 1) h.y.title = s;
            else if (h.dim == 3) h.z.title = s;
            else h.zTitle = s;
        } else if (main instanceof Graph g) {
            if (axis == 0) g.xTitle = s;
            else g.yTitle = s;
        }
        final View v = view(t);
        if (axis == 0) v.xTitle = null;
        if (axis == 1) v.yTitle = null;
        if (axis == 2) v.zTitle = null;
    }

    static boolean removeItem(Pad pad, Item it) {
        if (pad.items.remove(it)) return true;
        for (Item i : pad.items) {
            if (i instanceof Group g && g.items.remove(it)) return true;
            if (i instanceof Hist h && h.fits.remove(it)) return true;
        }
        for (Pad p : pad.pads) if (removeItem(p, it)) return true;
        return false;
    }

    static boolean removePad(Pad parent, Pad target) {
        if (parent.pads.remove(target)) return true;
        for (Pad p : parent.pads) if (removePad(p, target)) return true;
        return false;
    }

    /** A copy of what the target is, as a canvas of its own. */
    static RootScene clone(RootTarget t, String option) {
        if (t.object instanceof RootScene s) return RootSceneJson.copy(s);
        if (t.object instanceof Pad p) {
            final RootScene s = RootSceneJson.copy(t.scene);
            s.pad = RootSceneJson.copy(p);
            s.pad.px = 0;
            s.pad.py = 0;
            s.pad.pw = 1;
            s.pad.ph = 1;
            s.name = p.name;
            return s;
        }
        final Item i = t.item();
        if (i == null) return RootSceneJson.copy(t.scene);
        final Item copy = RootSceneJson.copy(i);
        if (option != null && !option.isBlank()) copy.option = option;
        final RootScene s = new RootScene();
        s.name = copy.name;
        s.title = copy.title;
        s.palette = t.scene.palette;
        s.optStat = t.scene.optStat;
        s.pad.name = copy.name;
        s.pad.lm = 0.12;
        s.pad.rm = copy instanceof Hist h && h.dim == 2 ? 0.14 : 0.06;
        s.pad.items.add(copy);
        for (int a = 0; a < 3; a++) s.pad.axes[a] = t.pad.axes[a].copy();
        return s;
    }

    /** TClass::Draw: the class, what it derives from, and the functions of its menu. */
    static String classReport(String className) {
        final StringBuilder b = new StringBuilder("class ").append(className).append('\n');
        final List<String> lineage = RootMethod.lineage(className);
        b.append("  inherits from: ").append(String.join(" → ", lineage.subList(1, lineage.size()))).append("\n\n");
        for (String c : lineage) {
            final List<RootMethod> ms = RootMethod.declared(c);
            if (ms.isEmpty()) continue;
            b.append(c).append('\n');
            for (RootMethod m : ms) {
                b.append(String.format(Locale.ROOT, "   %s %-58s %s%n", m.toggle ? "[T]" : "   ", m.signature(),
                    implemented(className, m.name) ? "" : "(ROOT engine)"));
            }
            b.append('\n');
        }
        return b.toString();
    }

    /** TObject::Dump: every data member and its value, as ROOT prints them. */
    static String dump(Object o, String className) {
        final StringBuilder b = new StringBuilder("==> Dumping object at: ").append(Integer.toHexString(System.identityHashCode(o)))
            .append(", name=").append(o instanceof Item i ? i.name : String.valueOf(o)).append(", class=").append(className)
            .append("\n\n");
        Class<?> c = o.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    b.append(String.format(Locale.ROOT, "%-22s %s%n", f.getName(), value(f.get(o))));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    b.append(String.format(Locale.ROOT, "%-22s ?%n", f.getName()));
                }
            }
            c = c.getSuperclass();
        }
        return b.toString();
    }

    static String value(Object v) {
        if (v == null) return "0";
        if (v instanceof double[] a) return a.length + " values" + (a.length > 0 ? ": " + preview(a) : "");
        if (v instanceof float[] a) return a.length / 3 + " points";
        if (v instanceof int[] a) return a.length + " integers";
        if (v instanceof String[] a) return a.length + " labels";
        if (v instanceof Color c) return String.format("#%06x (ROOT colour %d)", c.getRGB() & 0xFFFFFF, RootColors.index(c));
        if (v instanceof List<?> l) return l.size() + " objects";
        if (v instanceof Double d) return String.format(Locale.ROOT, "%.10g", d);
        if (v instanceof String s) return "\"" + s + "\"";
        if (v.getClass().getName().startsWith("com.sphere")) return v.getClass().getSimpleName();
        return String.valueOf(v);
    }

    private static String preview(double[] a) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(8, a.length); i++) b.append(i == 0 ? "" : ", ").append(String.format(Locale.ROOT, "%.6g", a[i]));
        return b + (a.length > 8 ? ", ..." : "");
    }

    /** TObject::Inspect: the members, and the members of the objects they hold, indented. */
    static String inspect(Object o, int depth, java.util.IdentityHashMap<Object, Boolean> seen) {
        if (o == null || depth > 4 || seen.put(o, Boolean.TRUE) != null) return "";
        final StringBuilder b = new StringBuilder();
        final String pad = "    ".repeat(depth);
        Class<?> c = o.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    final Object v = f.get(o);
                    b.append(pad).append(String.format(Locale.ROOT, "%-20s %s%n", f.getName(), value(v)));
                    if (v != null && v.getClass().getName().startsWith("com.sphere") && !(v instanceof Enum<?>)) {
                        b.append(inspect(v, depth + 1, seen));
                    } else if (v instanceof List<?> l) {
                        int k = 0;
                        for (Object e : l) {
                            if (k++ >= 20) break;
                            if (e != null && e.getClass().getName().startsWith("com.sphere")) {
                                b.append(pad).append("  [").append(k - 1).append("] ").append(e.getClass().getSimpleName()).append('\n');
                                b.append(inspect(e, depth + 1, seen));
                            }
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    b.append(pad).append(f.getName()).append(" ?\n");
                }
            }
            c = c.getSuperclass();
        }
        return b.toString();
    }

    /* ---- TAttLine, TAttFill, TAttMarker, TAttText --------------------- */

    private static void registerAttributes() {
        def("TAttLine::SetLineAttributes", (t, a) -> attributes(t, RootAttEditor.Kind.LINE));
        def("TAttFill::SetFillAttributes", (t, a) -> attributes(t, RootAttEditor.Kind.FILL));
        def("TAttMarker::SetMarkerAttributes", (t, a) -> attributes(t, RootAttEditor.Kind.MARKER));
        def("TAttText::SetTextAttributes", (t, a) -> attributes(t, RootAttEditor.Kind.TEXT));
    }

    private static String attributes(RootTarget t, RootAttEditor.Kind kind) {
        final Item i = t.object instanceof Item it ? it : t.item();
        if (i == null) throw new IllegalStateException("no attributes here");
        RootAttEditor.edit(t.canvas, t.title() + " — " + kind.name().toLowerCase(Locale.ROOT) + " attributes", kind,
            RootAttEditor.read(kind, i), att -> {
                RootAttEditor.write(kind, i, att);
                t.canvas.changed();
            });
        return null;
    }

    /* ---- TAttAxis, TAxis, TPaletteAxis, TAxis3D ------------------------ */

    private static void registerAxes() {
        def("TAttAxis::SetNdivisions", (t, a) -> {
            final AxisStyle st = style(t);
            st.ndivisions = i(a, 0, 510);
            st.optimize = b(a, 1, true);
            return "divisions " + st.ndivisions + (st.optimize ? "" : " (not optimised)");
        }).current = t -> new Object[]{(long) style(t).ndivisions, style(t).optimize};
        def("TAttAxis::SetAxisColor", (t, a) -> {
            style(t).axisColor = withAlpha(c(a, 0, Color.BLACK), d(a, 1, 1));
            return "axis colour set";
        }).current = t -> new Object[]{style(t).axisColor == null ? Color.BLACK : style(t).axisColor, 1.0};
        def("TAttAxis::SetLabelColor", (t, a) -> {
            style(t).labelColor = withAlpha(c(a, 0, Color.BLACK), d(a, 1, 1));
            return "label colour set";
        }).current = t -> new Object[]{style(t).labelColor == null ? Color.BLACK : style(t).labelColor, 1.0};
        def("TAttAxis::SetLabelFont", (t, a) -> {
            style(t).labelFont = i(a, 0, 42);
            return "label font " + style(t).labelFont;
        }).current = t -> new Object[]{style(t).labelFont};
        def("TAttAxis::SetLabelOffset", (t, a) -> {
            style(t).labelOffset = d(a, 0, 0.005);
            return "label offset set";
        }).current = t -> new Object[]{style(t).labelOffset};
        def("TAttAxis::SetLabelSize", (t, a) -> {
            style(t).labelSize = d(a, 0, 0.035);
            return "label size set";
        }).current = t -> new Object[]{style(t).labelSize};
        def("TAttAxis::SetMaxDigits", (t, a) -> {
            style(t).maxDigits = Math.max(1, i(a, 0, 5));
            return "at most " + style(t).maxDigits + " digits";
        }).current = t -> new Object[]{(long) style(t).maxDigits};
        def("TAttAxis::SetTickLength", (t, a) -> {
            style(t).tickLength = d(a, 0, 0.03);
            return "tick length set";
        }).current = t -> new Object[]{style(t).tickLength};
        def("TAttAxis::SetTitleOffset", (t, a) -> {
            style(t).titleOffset = d(a, 0, 1);
            return "title offset set";
        }).current = t -> new Object[]{style(t).titleOffset};
        def("TAttAxis::SetTitleSize", (t, a) -> {
            style(t).titleSize = d(a, 0, 0.035);
            return "title size set";
        }).current = t -> new Object[]{style(t).titleSize};
        def("TAttAxis::SetTitleColor", (t, a) -> {
            style(t).titleColor = c(a, 0, Color.BLACK);
            return "title colour set";
        }).current = t -> new Object[]{style(t).titleColor == null ? Color.BLACK : style(t).titleColor};
        def("TAttAxis::SetTitleFont", (t, a) -> {
            style(t).titleFont = i(a, 0, 42);
            return "title font " + style(t).titleFont;
        }).current = t -> new Object[]{style(t).titleFont};

        def("TAxis::LabelsOption", (t, a) -> {
            final String o = s(a, 0, "h");
            style(t).labelsOption = o;
            final String sorted = RootDataActions.sortLabels(t, o);
            return "labels " + o + (sorted == null ? "" : ", " + sorted);
        }).current = t -> new Object[]{style(t).labelsOption};
        find("TAxis", "LabelsOption").suggestions.put("option", new String[]{"h", "v", "u", "d", "a", ">", "<"});
        def("TAxis::RotateTitle", (t, a) -> {
            style(t).rotateTitle = b(a, 0, true);
            return "title " + (style(t).rotateTitle ? "rotated" : "upright");
        }).state = t -> style(t).rotateTitle;
        def("TAxis::SetDecimals", (t, a) -> {
            style(t).decimals = b(a, 0, true);
            return style(t).decimals ? "same decimals on every label" : "trailing zeros dropped";
        }).state = t -> style(t).decimals;
        def("TAxis::SetLimits", (t, a) -> RootDataActions.setLimits(t, d(a, 0, 0), d(a, 1, 1)))
            .current = t -> {
                final double[] r = RootDataActions.limits(t);
                return new Object[]{r[0], r[1]};
            };
        def("TAxis::SetMoreLogLabels", (t, a) -> {
            style(t).moreLogLabels = b(a, 0, true);
            return style(t).moreLogLabels ? "labels between the decades" : "decades only";
        }).state = t -> style(t).moreLogLabels;
        def("TAxis::SetNoExponent", (t, a) -> {
            style(t).noExponent = b(a, 0, true);
            return style(t).noExponent ? "labels written in full" : "powers of ten factored out";
        }).state = t -> style(t).noExponent;
        def("TAxis::SetRange", (t, a) -> RootDataActions.setBinRange(t, i(a, 0, 0), i(a, 1, 0)))
            .current = t -> RootDataActions.binRange(t);
        def("TAxis::SetRangeUser", (t, a) -> setRangeUser(t, d(a, 0, 0), d(a, 1, 1)))
            .current = t -> {
                final double[] r = range(t, axisOf(t));
                return new Object[]{r[0], r[1]};
            };
        def("TAxis::SetTicks", (t, a) -> {
            final String o = s(a, 0, "+").strip();
            style(t).ticks = o.isEmpty() ? "+" : o;
            return "ticks " + style(t).ticks;
        }).current = t -> new Object[]{style(t).ticks};
        find("TAxis", "SetTicks").suggestions.put("option", new String[]{"+", "-", "+-"});
        def("TAxis::SetTimeDisplay", (t, a) -> {
            style(t).timeDisplay = i(a, 0, 1) != 0;
            return style(t).timeDisplay ? "time axis" : "numbers";
        }).state = t -> style(t).timeDisplay;
        def("TAxis::SetTimeFormat", (t, a) -> {
            style(t).timeFormat = s(a, 0, "");
            style(t).timeDisplay = true;
            return "time format " + style(t).timeFormat;
        }).current = t -> new Object[]{style(t).timeFormat};
        find("TAxis", "SetTimeFormat").suggestions.put("format", new String[]{"%H:%M:%S", "%d/%m/%y", "%d/%m/%Y %H:%M",
            "%b %Y", "%H:%M%F1970-01-01 00:00:00", "%d/%m%F1995-01-01 00:00:00", "%Y-%m-%d"});
        def("TAxis::UnZoom", (t, a) -> unZoom(t, axisOf(t)));
        def("TAxis::ZoomOut", (t, a) -> {
            double factor = d(a, 0, 0);
            final double offset = d(a, 1, 0);
            if (factor <= 0) factor = 2;
            final double[] r = range(t, axisOf(t));
            final double c = 0.5 * (r[0] + r[1]) + offset * (r[1] - r[0]);
            final double h = 0.5 * (r[1] - r[0]) * factor;
            return setRangeUser(t, c - h, c + h);
        });

        def("TPaletteAxis::SetTitle", (t, a) -> {
            if (t.object instanceof PaletteRef r) r.hist().zTitle = s(a, 0, "");
            view(t).zTitle = null;
            return "palette title set";
        }).current = t -> new Object[]{t.object instanceof PaletteRef r ? r.hist().zTitle : ""};
        def("TPaletteAxis::SetLineWidth", (t, a) -> {
            t.pad.axes[2].lineWidth = Math.max(1, d(a, 0, 1));
            return "palette line width set";
        }).current = t -> new Object[]{t.pad.axes[2].lineWidth};
        def("TPaletteAxis::UnZoom", (t, a) -> unZoom(t, 2));
        for (String f : new String[]{"SetNdivisions", "SetLabelColor", "SetLabelFont", "SetLabelOffset", "SetLabelSize",
            "SetMaxDigits", "SetTickLength", "SetTitleOffset", "SetTitleSize", "SetTitleColor", "SetTitleFont",
            "SetAxisColor"}) {
            final Def base = def("TAttAxis::" + f);
            final Def palette = new Def((t, a) -> {
                if ("SetNdivisions".equals(f)) {
                    t.pad.axes[2].ndivisions = i(a, 0, 10);
                    return "palette divisions " + t.pad.axes[2].ndivisions;
                }
                return base.action.run(t, a);
            }, true);
            palette.current = base.current;
            DEFS.put("TPaletteAxis::" + f, palette);
        }

        // TAxis3D: the same on one axis, or all three with "*".
        for (String f : new String[]{"SetAxisColor", "SetLabelColor", "SetLabelFont", "SetLabelOffset", "SetLabelSize",
            "SetNdivisions", "SetTickLength", "SetTitleOffset"}) {
            final Def base = def("TAttAxis::" + f);
            DEFS.put("TAxis3D::" + f, new Def((t, a) -> {
                final String which = s(a, 1, "*").toLowerCase(Locale.ROOT);
                for (int k = 0; k < 3; k++) {
                    if (!which.contains("*") && !which.contains("xyz".substring(k, k + 1))) continue;
                    final RootTarget one = new RootTarget("TAxis", new AxisRef(t.pad, t.pad.main(), k), t.pad, t.scene,
                        t.canvas, t.at, t.x, t.y);
                    base.action.run(one, a);
                }
                return f + " on " + which;
            }, true));
            DEFS.get("TAxis3D::" + f).suggestions.put("axis", new String[]{"*", "x", "y", "z", "xy"});
        }
        def("TAxis3D::SetXTitle", (t, a) -> {
            setAxisTitle(t, 0, s(a, 0, ""));
            return "x title set";
        });
        def("TAxis3D::SetYTitle", (t, a) -> {
            setAxisTitle(t, 1, s(a, 0, ""));
            return "y title set";
        });
        def("TAxis3D::SetZTitle", (t, a) -> {
            setAxisTitle(t, 2, s(a, 0, ""));
            return "z title set";
        });
    }

    static String setRangeUser(RootTarget t, double lo, double hi) {
        if (!(hi > lo)) throw new IllegalArgumentException("the range must go up");
        final View v = view(t);
        switch (axisOf(t)) {
            case 0 -> v.xr = new double[]{lo, hi};
            case 1 -> v.yr = new double[]{lo, hi};
            default -> v.zr = new double[]{lo, hi};
        }
        return String.format(Locale.ROOT, "range %.6g .. %.6g", lo, hi);
    }

    static String unZoom(RootTarget t, int axis) {
        final View v = view(t);
        if (axis == 0) v.xr = null;
        else if (axis == 1) v.yr = null;
        else v.zr = null;
        if (t.item() instanceof Hist h) {
            if (axis == 0) {
                h.x.first = 0;
                h.x.last = 0;
            } else if (axis == 1 && h.y != null) {
                h.y.first = 0;
                h.y.last = 0;
            }
            if (axis == 1 && h.dim == 1) {
                h.min = Double.NaN;
                h.max = Double.NaN;
            }
        }
        return "unzoomed";
    }

    /* ---- TPad, TCanvas, TFrame ---------------------------------------- */

    private static Pad padOf(RootTarget t) {
        return t.object instanceof Pad p ? p : t.object instanceof RootScene s ? s.pad : t.pad;
    }

    private static void registerPad() {
        def("TPad::BuildLegend", (t, a) -> {
            final Pad p = padOf(t);
            final Pave legend = new Pave();
            legend.kind = "legend";
            legend.className = "TLegend";
            legend.name = "TPave";
            double x1 = d(a, 0, 0.3);
            double y1 = d(a, 1, 0.21);
            double x2 = d(a, 2, 0.3);
            double y2 = d(a, 3, 0.21);
            final boolean place = x1 == x2 && y1 == y2;
            legend.x1 = x1;
            legend.y1 = y1;
            legend.x2 = x2;
            legend.y2 = y2;
            legend.border = 1;
            legend.fill = Color.WHITE;
            legend.fillStyle = 1001;
            legend.header = s(a, 4, "").isBlank() ? null : s(a, 4, "");
            final String opt = s(a, 5, "");
            for (Item i : p.items) {
                final List<Item> members = new ArrayList<>();
                if (i instanceof Group g) members.addAll(g.items);
                else members.add(i);
                for (Item m : members) {
                    if (!(m instanceof Hist || m instanceof Graph || m instanceof RootScene.Graph2D)) continue;
                    final Entry e = new Entry();
                    e.text = m.title == null || m.title.isBlank() ? m.name : m.title;
                    e.option = !opt.isBlank() ? opt : m instanceof Graph ? "lp" : m.fillStyle > 0 && m.fill != null ? "f" : "l";
                    e.line = m.line;
                    e.fill = m.fill;
                    e.fillStyle = m.fillStyle;
                    e.marker = m.marker;
                    e.markerStyle = m.markerStyle;
                    legend.lines.add(e);
                }
            }
            if (legend.lines.isEmpty()) throw new IllegalStateException("nothing in the pad to put in a legend");
            if (place) {
                final double[] box = placeBox(t, p, 0.3, Math.min(0.5, 0.06 * (legend.lines.size() + (legend.header == null ? 0 : 1)) + 0.02));
                legend.x1 = box[0];
                legend.y1 = box[1];
                legend.x2 = box[2];
                legend.y2 = box[3];
            }
            p.items.add(legend);
            return "legend of " + legend.lines.size() + " entries";
        }).current = t -> new Object[]{0.3, 0.21, 0.3, 0.21, "", ""};
        look("TPad::cd", (t, a) -> {
            final Pad p = padOf(t);
            final int n = i(a, 0, 0);
            final Pad target = n <= 0 ? p : n <= p.pads.size() ? p.pads.get(n - 1) : null;
            if (target == null) throw new IllegalArgumentException("pad " + p.name + " has " + p.pads.size() + " sub-pads");
            t.canvas.setCurrentPad(target);
            return "current pad: " + target.name;
        });
        def("TPad::Divide", (t, a) -> {
            final Pad p = padOf(t);
            final int nx = Math.max(1, i(a, 0, 1));
            final int ny = Math.max(1, i(a, 1, 1));
            final double xm = d(a, 2, 0.01);
            final double ym = d(a, 3, 0.01);
            final Color col = a != null && a.length > 4 && i(a, 4, 0) != 0 ? c(a, 4, null) : null;
            p.pads.clear();
            for (int iy = 0; iy < ny; iy++) {
                for (int ix = 0; ix < nx; ix++) {
                    final Pad sub = new Pad();
                    sub.name = p.name + "_" + (iy * nx + ix + 1);
                    sub.title = sub.name;
                    final double w = 1.0 / nx;
                    final double h = 1.0 / ny;
                    sub.px = ix * w + xm;
                    sub.py = 1 - (iy + 1) * h + ym;
                    sub.pw = Math.max(0.01, w - 2 * xm);
                    sub.ph = Math.max(0.01, h - 2 * ym);
                    if (col != null) sub.fill = col;
                    p.pads.add(sub);
                }
            }
            return "divided into " + nx + " x " + ny;
        }).current = t -> new Object[]{1L, 1L, 0.01, 0.01, 0L};
        def("TPad::UseCurrentStyle", (t, a) -> {
            useStyle(padOf(t), t.canvas);
            RootStyle.apply(t.scene);
            return "style applied";
        });
        def("TPad::Pop", (t, a) -> {
            final Pad p = padOf(t);
            final Pad mother = parentOf(t.scene.pad, p);
            if (mother == null) return "the canvas is already on top";
            mother.pads.remove(p);
            mother.pads.add(p);
            return p.name + " brought to the front";
        });
        def("TPad::Range", (t, a) -> {
            final Pad p = padOf(t);
            p.ux1 = d(a, 0, 0);
            p.uy1 = d(a, 1, 0);
            p.ux2 = d(a, 2, 1);
            p.uy2 = d(a, 3, 1);
            if (p.drawsSomething()) {
                final View v = t.canvas.view(p);
                final double dx = p.ux2 - p.ux1;
                final double dy = p.uy2 - p.uy1;
                v.xr = new double[]{p.ux1 + p.lm * dx, p.ux2 - p.rm * dx};
                v.yr = new double[]{p.uy1 + p.bm * dy, p.uy2 - p.tm * dy};
            }
            return "range set";
        }).current = t -> {
            final Pad p = padOf(t);
            if (p.hasUserRange()) return new Object[]{p.ux1, p.uy1, p.ux2, p.uy2};
            final View v = t.canvas.view(p);
            final double dx = (v.x1 - v.x0) / Math.max(1e-9, 1 - p.lm - p.rm);
            final double dy = (v.y1 - v.y0) / Math.max(1e-9, 1 - p.tm - p.bm);
            return new Object[]{v.x0 - p.lm * dx, v.y0 - p.bm * dy, v.x1 + p.rm * dx, v.y1 + p.tm * dy};
        };
        def("TPad::SetBorderMode", (t, a) -> {
            padOf(t).borderMode = i(a, 0, 0);
            return "border mode " + padOf(t).borderMode;
        }).current = t -> new Object[]{(long) padOf(t).borderMode};
        find("TPad", "SetBorderMode").suggestions.put("bordermode", new String[]{"-1", "0", "1"});
        def("TPad::SetBorderSize", (t, a) -> {
            padOf(t).borderSize = i(a, 0, 2);
            return "border size " + padOf(t).borderSize;
        }).current = t -> new Object[]{(long) padOf(t).borderSize};
        def("TPad::SetCrosshair", (t, a) -> {
            padOf(t).crosshair = i(a, 0, 1) != 0;
            return padOf(t).crosshair ? "crosshair on" : "crosshair off";
        }).state = t -> padOf(t).crosshair;
        def("TPad::SetEditable", (t, a) -> {
            padOf(t).editable = b(a, 0, true);
            return padOf(t).editable ? "the pad can be changed" : "the pad is locked";
        }).state = t -> padOf(t).editable;
        def("TPad::SetFixedAspectRatio", (t, a) -> {
            padOf(t).fixedAspect = b(a, 0, true);
            return padOf(t).fixedAspect ? "aspect ratio fixed" : "aspect ratio free";
        }).state = t -> padOf(t).fixedAspect;
        def("TPad::SetGridx", (t, a) -> {
            t.canvas.view(padOf(t)).gridx = i(a, 0, 1) != 0;
            return "grid x " + (i(a, 0, 1) != 0 ? "on" : "off");
        }).state = t -> RootPadPainter.log(t.canvas.view(padOf(t)).gridx, padOf(t).gridx);
        def("TPad::SetGridy", (t, a) -> {
            t.canvas.view(padOf(t)).gridy = i(a, 0, 1) != 0;
            return "grid y " + (i(a, 0, 1) != 0 ? "on" : "off");
        }).state = t -> RootPadPainter.log(t.canvas.view(padOf(t)).gridy, padOf(t).gridy);
        def("TPad::SetLogx", (t, a) -> {
            t.canvas.view(padOf(t)).logx = i(a, 0, 1) != 0;
            return "log x " + (i(a, 0, 1) != 0 ? "on" : "off");
        }).state = t -> RootPadPainter.log(t.canvas.view(padOf(t)).logx, padOf(t).logx);
        def("TPad::SetLogy", (t, a) -> {
            t.canvas.view(padOf(t)).logy = i(a, 0, 1) != 0;
            return "log y " + (i(a, 0, 1) != 0 ? "on" : "off");
        }).state = t -> RootPadPainter.log(t.canvas.view(padOf(t)).logy, padOf(t).logy);
        def("TPad::SetLogz", (t, a) -> {
            t.canvas.view(padOf(t)).logz = i(a, 0, 1) != 0;
            return "log z " + (i(a, 0, 1) != 0 ? "on" : "off");
        }).state = t -> RootPadPainter.log(t.canvas.view(padOf(t)).logz, padOf(t).logz);
        def("TPad::SetName", (t, a) -> {
            padOf(t).name = s(a, 0, "");
            return "pad renamed " + padOf(t).name;
        }).current = t -> new Object[]{padOf(t).name};
        def("TPad::SetTickx", (t, a) -> {
            padOf(t).tickx = i(a, 0, 1);
            return "ticks on the top " + (padOf(t).tickx != 0 ? "on" : "off");
        }).state = t -> padOf(t).tickx != 0;
        def("TPad::SetTicky", (t, a) -> {
            padOf(t).ticky = i(a, 0, 1);
            return "ticks on the right " + (padOf(t).ticky != 0 ? "on" : "off");
        }).state = t -> padOf(t).ticky != 0;
        look("TPad::SaveAs", (t, a) -> RootSaver.save(new RootTarget("TPad", padOf(t), padOf(t), t.scene, t.canvas, t.at,
            t.x, t.y), s(a, 0, ""), s(a, 1, "")));

        look("TCanvas::DrawClone", (t, a) -> {
            t.canvas.host().open(RootSceneJson.copy(t.scene), (t.scene.name == null ? "c1" : t.scene.name) + " (clone)");
            return "canvas cloned";
        });
        look("TCanvas::DrawClonePad", (t, a) -> {
            final Pad p = t.canvas.currentPad() == null ? t.pad : t.canvas.currentPad();
            t.canvas.host().open(clone(new RootTarget("TPad", p, p, t.scene, t.canvas, t.at, t.x, t.y), ""),
                p.name + " (clone)");
            return "pad " + p.name + " cloned";
        });
        def("TCanvas::UseCurrentStyle", (t, a) -> {
            for (Pad p : t.scene.pad.flatten()) useStyle(p, t.canvas);
            RootStyle.apply(t.scene);
            return "style applied to every pad";
        });
        def("TCanvas::SetFixedAspectRatio", (t, a) -> {
            t.scene.fixedAspect = b(a, 0, true);
            return t.scene.fixedAspect ? "aspect ratio fixed" : "aspect ratio free";
        }).state = t -> t.scene.fixedAspect;
        def("TCanvas::SetGrayscale", (t, a) -> {
            t.scene.grayscale = b(a, 0, true);
            return t.scene.grayscale ? "greyscale" : "colours";
        }).state = t -> t.scene.grayscale;
        def("TCanvas::SetCanvasSize", (t, a) -> {
            t.scene.width = Math.max(10, i(a, 0, 700));
            t.scene.height = Math.max(10, i(a, 1, 500));
            return "canvas " + t.scene.width + " x " + t.scene.height;
        }).current = t -> new Object[]{(long) t.scene.width, (long) t.scene.height};
        def("TCanvas::SetRealAspectRatio", (t, a) -> {
            final Pad p = t.canvas.currentPad() != null ? t.canvas.currentPad() : t.scene.pad.pads.isEmpty() ? t.scene.pad
                : t.scene.pad.pads.get(0);
            final View v = t.canvas.view(p);
            final double dx = v.x1 - v.x0;
            final double dy = v.y1 - v.y0;
            if (!(dx > 0) || !(dy > 0)) throw new IllegalStateException("no frame to measure");
            final double fw = (1 - p.lm - p.rm) * p.pw;
            final double fh = (1 - p.tm - p.bm) * p.ph;
            if (i(a, 0, 1) == 2) {
                t.scene.height = (int) Math.round(t.scene.width * fw / fh * dy / dx);
            } else {
                t.scene.width = (int) Math.round(t.scene.height * fh / fw * dx / dy);
            }
            return "one unit of x as long as one of y: " + t.scene.width + " x " + t.scene.height;
        }).current = t -> new Object[]{1L};
        def("TFrame::UseCurrentStyle", (t, a) -> {
            for (int k = 0; k < 3; k++) t.pad.axes[k] = RootStyle.axis(k);
            return "frame style applied";
        });
    }

    /**
     * Where a box of w by h (NDC) covers the fewest of the points the pad
     * draws, inside the frame and away from the statistics box and the
     * title, as TPad::PlaceBox looks for a free place for BuildLegend.
     */
    static double[] placeBox(RootTarget t, Pad p, double w, double h) {
        final View v = t.canvas.view(p);
        final double fx0 = p.lm;
        final double fx1 = 1 - p.rm;
        final double fy0 = p.bm;
        final double fy1 = 1 - p.tm;
        final List<double[]> points = new ArrayList<>();
        final java.util.function.BiConsumer<Double, Double> add = (x, y) -> {
            if (!(v.x1 > v.x0) || !(v.y1 > v.y0)) return;
            final double fx = v.lx && x > 0 ? (Math.log10(x) - Math.log10(v.x0)) / (Math.log10(v.x1) - Math.log10(v.x0))
                : (x - v.x0) / (v.x1 - v.x0);
            final double fy = v.ly && y > 0 ? (Math.log10(y) - Math.log10(v.y0)) / (Math.log10(v.y1) - Math.log10(v.y0))
                : (y - v.y0) / (v.y1 - v.y0);
            points.add(new double[]{fx0 + fx * (fx1 - fx0), fy0 + fy * (fy1 - fy0)});
        };
        for (Item i : p.items) {
            final List<Item> all = new ArrayList<>();
            if (i instanceof Group g) all.addAll(g.items);
            else all.add(i);
            for (Item m : all) {
                if (m instanceof Hist hh && hh.dim == 1) {
                    for (int k = 0; k < hh.nx(); k++) {
                        // The whole column under a bin is covered by a histogram drawn with a fill; its top otherwise.
                        add.accept(hh.x.center(k), hh.at(k, 0));
                        if (hh.hasFill()) add.accept(hh.x.center(k), 0.5 * hh.at(k, 0));
                    }
                } else if (m instanceof Graph g) {
                    for (int k = 0; k < g.x.length; k++) add.accept(g.x[k], g.y[k]);
                }
            }
        }
        final boolean stats = p.main() instanceof Hist hm && hm.stats && t.scene.optStat != 0 && !v.statsOff;
        double best = Double.POSITIVE_INFINITY;
        double[] box = {fx1 - w - 0.02, fy1 - h - 0.02, fx1 - 0.02, fy1 - 0.02};
        for (int ix = 0; ix <= 10; ix++) {
            for (int iy = 0; iy <= 10; iy++) {
                final double x1 = fx0 + 0.02 + (fx1 - fx0 - w - 0.04) * ix / 10.0;
                final double y1 = fy0 + 0.02 + (fy1 - fy0 - h - 0.04) * iy / 10.0;
                final double x2 = x1 + w;
                final double y2 = y1 + h;
                double cost = 0;
                for (double[] q : points) if (q[0] >= x1 && q[0] <= x2 && q[1] >= y1 && q[1] <= y2) cost += 1;
                if (stats && x2 > 0.76 && y2 > 0.76) cost += 1e6;
                // Ties go to the top and to the right, where ROOT's legends sit.
                cost += 0.01 * ((1 - y2) + 0.5 * (1 - x2));
                if (cost < best) {
                    best = cost;
                    box = new double[]{x1, y1, x2, y2};
                }
            }
        }
        return box;
    }

    static Pad parentOf(Pad root, Pad target) {
        for (Pad p : root.pads) {
            if (p == target) return root;
            final Pad found = parentOf(p, target);
            if (found != null) return found;
        }
        return null;
    }

    /** TPad::UseCurrentStyle: the style's axes, ticks, grid and log scales, and the user's changes forgotten. */
    static void useStyle(Pad p, RootCanvasView canvas) {
        for (int k = 0; k < 3; k++) p.axes[k] = RootStyle.axis(k);
        p.tickx = RootStyle.get().tickx;
        p.ticky = RootStyle.get().ticky;
        p.gridx = RootStyle.get().gridx;
        p.gridy = RootStyle.get().gridy;
        p.logx = RootStyle.get().logx;
        p.logy = RootStyle.get().logy;
        p.logz = RootStyle.get().logz;
        final View v = canvas.view(p);
        v.gridx = null;
        v.gridy = null;
        v.logx = null;
        v.logy = null;
        v.logz = null;
    }

    /* ---- TPave, TPaveText, TPaveLabel, TLegend, TPaveStats ------------- */

    private static void registerPaves() {
        def("TPave::SetBorderSize", (t, a) -> {
            pave(t).border = i(a, 0, 4);
            return "border size " + pave(t).border;
        }).current = t -> new Object[]{(long) pave(t).border};
        def("TPave::SetCornerRadius", (t, a) -> {
            pave(t).cornerRadius = d(a, 0, 0.2);
            return "corner radius " + pave(t).cornerRadius;
        }).current = t -> new Object[]{pave(t).cornerRadius == 0 ? 0.2 : pave(t).cornerRadius};
        def("TPave::SetName", (t, a) -> {
            item(t).name = s(a, 0, "");
            return "renamed";
        }).current = t -> new Object[]{item(t).name};
        def("TPave::SetShadowColor", (t, a) -> {
            pave(t).shadowColor = c(a, 0, Color.BLACK);
            return "shadow colour set";
        }).current = t -> new Object[]{pave(t).shadowColor == null ? Color.BLACK : pave(t).shadowColor};

        def("TPaveText::Clear", (t, a) -> {
            pave(t).lines.clear();
            return "pave cleared";
        });
        def("TPaveText::DeleteText", (t, a) -> {
            final int k = lineAt(t);
            if (k < 0) throw new IllegalStateException("no line under the mouse");
            pave(t).lines.remove(k);
            return "line " + (k + 1) + " deleted";
        });
        def("TPaveText::EditText", (t, a) -> {
            final int k = lineAt(t);
            final Pave p = pave(t);
            if (k < 0) throw new IllegalStateException("no line under the mouse");
            final String s = JOptionPane.showInputDialog(t.canvas, "Line " + (k + 1), p.lines.get(k).text);
            if (s == null) return "unchanged";
            p.lines.get(k).text = s;
            return "line " + (k + 1) + " edited";
        });
        def("TPaveText::InsertLine", (t, a) -> {
            final Pave p = pave(t);
            final Entry e = new Entry();
            e.separator = true;
            p.lines.add(Math.max(0, lineAt(t) + 1), e);
            return "line inserted";
        });
        def("TPaveText::InsertText", (t, a) -> {
            final Pave p = pave(t);
            final Entry e = new Entry();
            e.text = s(a, 0, "");
            e.color = p.lines.isEmpty() ? Color.BLACK : p.lines.get(p.lines.size() - 1).color;
            final int k = lineAt(t);
            p.lines.add(k < 0 ? p.lines.size() : k + 1, e);
            return "text inserted";
        });
        def("TPaveText::ReadFile", (t, a) -> {
            final Path file = Path.of(s(a, 0, ""));
            final int nlines = Math.max(1, i(a, 2, 50));
            final int from = Math.max(0, i(a, 3, 0));
            final List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            final Pave p = pave(t);
            int added = 0;
            for (int k = from; k < all.size() && added < nlines; k++, added++) {
                final Entry e = new Entry();
                e.text = all.get(k);
                e.color = Color.BLACK;
                p.lines.add(e);
            }
            return added + " lines read from " + file.getFileName();
        });
        def("TPaveText::SetAllWith", (t, a) -> {
            final String text = s(a, 0, "");
            final String option = s(a, 1, "").toLowerCase(Locale.ROOT);
            final double value = d(a, 2, 1);
            int n = 0;
            for (Entry e : pave(t).lines) {
                if (!e.text.contains(text)) continue;
                n++;
                if (option.contains("color")) e.color = RootColors.color((int) value);
                else if (option.contains("size")) e.size = value;
                else if (option.contains("font")) e.font = (int) value;
                else throw new IllegalArgumentException("option is color, size or font");
            }
            return n + " lines changed";
        });
        find("TPaveText", "SetAllWith").suggestions.put("option", new String[]{"color", "size", "font"});
        def("TPaveText::SetLabel", (t, a) -> {
            pave(t).label = s(a, 0, "");
            return "label set";
        }).current = t -> new Object[]{pave(t).label};
        def("TPaveText::SetMargin", (t, a) -> {
            pave(t).margin = d(a, 0, 0.05);
            return "margin set";
        }).current = t -> new Object[]{pave(t).margin};
        def("TPaveLabel::SetLabel", (t, a) -> {
            final Pave p = pave(t);
            if (p.lines.isEmpty()) p.lines.add(new Entry());
            p.lines.get(0).text = s(a, 0, "");
            return "label set";
        }).current = t -> new Object[]{pave(t).lines.isEmpty() ? "" : pave(t).lines.get(0).text};

        def("TLegend::Clear", (t, a) -> {
            pave(t).lines.clear();
            return "legend cleared";
        });
        def("TLegend::DeleteEntry", (t, a) -> {
            final int k = entryAt(t);
            if (k < 0) throw new IllegalStateException("no entry under the mouse");
            pave(t).lines.remove(k);
            return "entry deleted";
        });
        def("TLegend::SetEntryLabel", (t, a) -> {
            final int k = entryAt(t);
            if (k < 0) throw new IllegalStateException("no entry under the mouse");
            pave(t).lines.get(k).text = s(a, 0, "");
            return "entry relabelled";
        }).current = t -> {
            final int k = entryAt(t);
            return new Object[]{k < 0 ? "" : pave(t).lines.get(k).text};
        };
        def("TLegend::SetEntryOption", (t, a) -> {
            final int k = entryAt(t);
            if (k < 0) throw new IllegalStateException("no entry under the mouse");
            pave(t).lines.get(k).option = s(a, 0, "lpf");
            return "entry option " + pave(t).lines.get(k).option;
        }).current = t -> {
            final int k = entryAt(t);
            return new Object[]{k < 0 ? "lpf" : pave(t).lines.get(k).option};
        };
        find("TLegend", "SetEntryOption").suggestions.put("option", new String[]{"l", "p", "f", "lp", "lpf", "le", "lep", ""});
        def("TLegend::SetHeader", (t, a) -> {
            pave(t).header = s(a, 0, "");
            pave(t).headerOption = s(a, 1, "");
            return "header set";
        }).current = t -> new Object[]{pave(t).header == null ? "" : pave(t).header, pave(t).headerOption};
        find("TLegend", "SetHeader").suggestions.put("option", new String[]{"", "C", "R"});
        def("TLegend::SetMargin", (t, a) -> {
            pave(t).margin = d(a, 0, 0.25);
            return "margin set";
        }).current = t -> new Object[]{pave(t).margin == 0.05 ? 0.25 : pave(t).margin};
        def("TLegend::SetNColumns", (t, a) -> {
            pave(t).nColumns = Math.max(1, i(a, 0, 1));
            return pave(t).nColumns + " columns";
        }).current = t -> new Object[]{(long) pave(t).nColumns};

        def("TPaveStats::SaveStyle", (t, a) -> {
            RootStyle.get().optStat = t.scene.optStat;
            RootStyle.get().optFit = t.scene.optFit;
            RootStyle.get().statFormat = t.scene.statFormat;
            RootStyle.get().fitFormat = t.scene.fitFormat;
            RootStyle.get().statOption = t.scene.statOption;
            return "statistics box style saved in the style";
        });
        def("TPaveStats::SetFitFormat", (t, a) -> {
            t.scene.fitFormat = s(a, 0, "5.4g");
            return "fit format " + t.scene.fitFormat;
        }).current = t -> new Object[]{t.scene.fitFormat};
        def("TPaveStats::SetStatFormat", (t, a) -> {
            t.scene.statFormat = s(a, 0, "6.4g");
            return "statistics format " + t.scene.statFormat;
        }).current = t -> new Object[]{t.scene.statFormat};
        def("TPaveStats::SetOptFit", (t, a) -> {
            int f = i(a, 0, 1);
            if (f == 1) f = 111;
            t.scene.optFit = f;
            return "OptFit " + f;
        }).current = t -> new Object[]{(long) t.scene.optFit};
        def("TPaveStats::SetOptStat", (t, a) -> {
            int s = i(a, 0, 1);
            if (s == 1) s = 1111;
            t.scene.optStat = s;
            if (t.object instanceof StatsRef r) r.hist().stats = s != 0;
            return "OptStat " + s;
        }).current = t -> new Object[]{(long) t.scene.optStat};
        def("TPaveStats::SetOption", (t, a) -> {
            t.scene.statOption = s(a, 0, "br");
            return "option " + t.scene.statOption;
        }).current = t -> new Object[]{t.scene.statOption};
        find("TPaveStats", "SetOption").suggestions.put("option", new String[]{"br", "tr", "tl", "bl", ""});
        // The statistics box is no item of its own: its pave functions act on the scene's box.
        def("TPaveStats::SetBorderSize", (t, a) -> "the statistics box keeps ROOT's border");
    }

    /** The line of a pave under the mouse, -1 when none. */
    static int lineAt(RootTarget t) {
        final Pave p = pave(t);
        if (p.lines.isEmpty()) return -1;
        final double[] at = ndcAt(t);
        final double top = Math.max(p.y1, p.y2);
        final double bottom = Math.min(p.y1, p.y2);
        if (top <= bottom) return p.lines.size() - 1;
        final int k = (int) ((top - at[1]) / (top - bottom) * p.lines.size());
        return Math.max(0, Math.min(p.lines.size() - 1, k));
    }

    /** The legend entry under the mouse, with its header and columns. */
    static int entryAt(RootTarget t) {
        final Pave p = pave(t);
        if (p.lines.isEmpty()) return -1;
        final boolean header = p.header != null && !p.header.isBlank();
        final int cols = Math.max(1, p.nColumns);
        final int rows = (int) Math.ceil(p.lines.size() / (double) cols) + (header ? 1 : 0);
        final double[] at = ndcAt(t);
        final double top = Math.max(p.y1, p.y2);
        final double bottom = Math.min(p.y1, p.y2);
        final double left = Math.min(p.x1, p.x2);
        final double right = Math.max(p.x1, p.x2);
        int row = (int) ((top - at[1]) / Math.max(1e-9, top - bottom) * rows) - (header ? 1 : 0);
        final int col = (int) ((at[0] - left) / Math.max(1e-9, right - left) * cols);
        if (row < 0) return -1;
        final int k = row * cols + Math.max(0, Math.min(cols - 1, col));
        return k < p.lines.size() ? k : -1;
    }

    /* ---- TText, TLine, TArrow, TEllipse, TMarker, TPolyLine, TWbox ---- */

    private static void registerShapes() {
        def("TText::SetText", (t, a) -> {
            final Text x = (Text) item(t);
            x.x = d(a, 0, x.x);
            x.y = d(a, 1, x.y);
            x.text = s(a, 2, x.text);
            return "text set";
        }).current = t -> {
            final Text x = (Text) item(t);
            return new Object[]{x.x, x.y, x.text};
        };
        def("TText::SetX", (t, a) -> {
            ((Text) item(t)).x = d(a, 0, 0);
            return "x set";
        }).current = t -> new Object[]{((Text) item(t)).x};
        def("TText::SetY", (t, a) -> {
            ((Text) item(t)).y = d(a, 0, 0);
            return "y set";
        }).current = t -> new Object[]{((Text) item(t)).y};

        def("TLine::SetHorizontal", (t, a) -> {
            final double[] l = line(t);
            if (b(a, 0, true)) setLine(t, l[0], l[1], l[2], l[1]);
            return "horizontal";
        }).state = t -> {
            final double[] l = line(t);
            return l[1] == l[3];
        };
        def("TLine::SetVertical", (t, a) -> {
            final double[] l = line(t);
            if (b(a, 0, true)) setLine(t, l[0], l[1], l[0], l[3]);
            return "vertical";
        }).state = t -> {
            final double[] l = line(t);
            return l[0] == l[2];
        };
        def("TArrow::SetAngle", (t, a) -> {
            ((Shape) item(t)).arrowAngle = d(a, 0, 60);
            return "arrow angle set";
        }).current = t -> new Object[]{((Shape) item(t)).arrowAngle};
        def("TArrow::SetArrowSize", (t, a) -> {
            ((Shape) item(t)).arrowSize = d(a, 0, 0.05);
            return "arrow size set";
        }).current = t -> new Object[]{((Shape) item(t)).arrowSize};

        def("TEllipse::SetNoEdges", (t, a) -> {
            ((Shape) item(t)).noEdges = b(a, 0, true);
            return ((Shape) item(t)).noEdges ? "arc without edges" : "arc with edges";
        }).state = t -> ((Shape) item(t)).noEdges;
        def("TEllipse::SetPhimin", (t, a) -> {
            ((Shape) item(t)).phimin = d(a, 0, 0);
            return "phimin set";
        }).current = t -> new Object[]{((Shape) item(t)).phimin};
        def("TEllipse::SetPhimax", (t, a) -> {
            ((Shape) item(t)).phimax = d(a, 0, 360);
            return "phimax set";
        }).current = t -> new Object[]{((Shape) item(t)).phimax};
        def("TEllipse::SetR1", (t, a) -> {
            ((Shape) item(t)).r1 = d(a, 0, 1);
            return "r1 set";
        }).current = t -> new Object[]{((Shape) item(t)).r1};
        def("TEllipse::SetR2", (t, a) -> {
            ((Shape) item(t)).r2 = d(a, 0, 1);
            return "r2 set";
        }).current = t -> new Object[]{((Shape) item(t)).r2};
        def("TEllipse::SetTheta", (t, a) -> {
            ((Shape) item(t)).theta = d(a, 0, 0);
            return "theta set";
        }).current = t -> new Object[]{((Shape) item(t)).theta};
        def("TEllipse::SetX1", (t, a) -> {
            ((Shape) item(t)).x1 = d(a, 0, 0);
            return "x1 set";
        }).current = t -> new Object[]{((Shape) item(t)).x1};
        def("TEllipse::SetY1", (t, a) -> {
            ((Shape) item(t)).y1 = d(a, 0, 0);
            return "y1 set";
        }).current = t -> new Object[]{((Shape) item(t)).y1};

        def("TMarker::SetX", (t, a) -> {
            ((Shape) item(t)).x1 = d(a, 0, 0);
            return "x set";
        }).current = t -> new Object[]{((Shape) item(t)).x1};
        def("TMarker::SetY", (t, a) -> {
            ((Shape) item(t)).y1 = d(a, 0, 0);
            return "y set";
        }).current = t -> new Object[]{((Shape) item(t)).y1};

        def("TPolyLine::SetNextPoint", (t, a) -> {
            final Shape s = (Shape) item(t);
            s.xs = java.util.Arrays.copyOf(s.xs, s.xs.length + 1);
            s.ys = java.util.Arrays.copyOf(s.ys, s.ys.length + 1);
            s.xs[s.xs.length - 1] = d(a, 0, 0);
            s.ys[s.ys.length - 1] = d(a, 1, 0);
            return "point " + s.xs.length + " added";
        }).current = t -> new Object[]{t.x, t.y};
        def("TPolyLine::SetPoint", (t, a) -> {
            final Shape s = (Shape) item(t);
            final int k = i(a, 0, 0);
            if (k < 0) throw new IllegalArgumentException("point numbers start at 0");
            if (k >= s.xs.length) {
                s.xs = java.util.Arrays.copyOf(s.xs, k + 1);
                s.ys = java.util.Arrays.copyOf(s.ys, k + 1);
            }
            s.xs[k] = d(a, 1, 0);
            s.ys[k] = d(a, 2, 0);
            return "point " + k + " set";
        });

        def("TWbox::SetBorderMode", (t, a) -> {
            ((Shape) item(t)).borderMode = i(a, 0, 0);
            return "border mode set";
        }).current = t -> new Object[]{(long) ((Shape) item(t)).borderMode};
        def("TWbox::SetBorderSize", (t, a) -> {
            ((Shape) item(t)).borderSize = i(a, 0, 1);
            return "border size set";
        }).current = t -> new Object[]{(long) ((Shape) item(t)).borderSize};
    }

    /** A line's ends, whether a TLine (a segment) or a TArrow (a shape). */
    static double[] line(RootTarget t) {
        if (t.object instanceof Segment s) return new double[]{s.x1, s.y1, s.x2, s.y2};
        if (t.object instanceof Shape s) return new double[]{s.x1, s.y1, s.x2, s.y2};
        throw new IllegalStateException("not a line");
    }

    static void setLine(RootTarget t, double x1, double y1, double x2, double y2) {
        if (t.object instanceof Segment s) {
            s.x1 = x1;
            s.y1 = y1;
            s.x2 = x2;
            s.y2 = y2;
        } else if (t.object instanceof Shape s) {
            s.x1 = x1;
            s.y1 = y1;
            s.x2 = x2;
            s.y2 = y2;
        }
    }

    /* ---- TView3D -------------------------------------------------------- */

    private static void registerView3D() {
        def("TView3D::SetParallel", (t, a) -> {
            view(t).perspective = false;
            return "parallel projection";
        });
        def("TView3D::SetPerspective", (t, a) -> {
            view(t).perspective = true;
            return "perspective";
        });
        def("TView3D::Centered", (t, a) -> {
            view(t).zoom = 1;
            return "centred";
        });
        def("TView3D::Front", (t, a) -> angles(t, 0, 270, "front"));
        def("TView3D::Side", (t, a) -> angles(t, 0, 0, "side"));
        def("TView3D::Top", (t, a) -> angles(t, 90, 270, "top"));
        def("TView3D::ZoomIn", (t, a) -> {
            view(t).zoom = Math.min(8, view(t).zoom * 1.25);
            return "zoomed in";
        });
        def("TView3D::ZoomOut", (t, a) -> {
            view(t).zoom = Math.max(0.2, view(t).zoom / 1.25);
            return "zoomed out";
        });
        def("TView3D::Zoom", (t, a) -> {
            view(t).zoom = Math.min(8, view(t).zoom * 1.25);
            return "zoomed in";
        });
        def("TView3D::UnZoom", (t, a) -> {
            view(t).zoom = 1;
            return "unzoomed";
        });
        def("TView3D::ZoomMove", (t, a) -> "drag the view with the mouse to turn it, the wheel to come closer");
        def("TView3D::ShowAxis", (t, a) -> {
            view(t).hideAxes3D = !view(t).hideAxes3D;
            return view(t).hideAxes3D ? "axes hidden" : "axes shown";
        });
    }

    private static String angles(RootTarget t, double theta, double phi, String what) {
        view(t).theta = theta;
        view(t).phi = phi;
        return what + " view";
    }

    /** Every function of a class's menu and whether Sphere does it, for the coverage report. */
    public static Map<String, Boolean> coverage(String className) {
        final Map<String, Boolean> out = new LinkedHashMap<>();
        for (RootMethod m : RootMethod.declared(className)) out.put(m.name, implemented(className, m.name));
        return out;
    }
}

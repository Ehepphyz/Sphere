package com.sphere.components.rootview;

import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;

import java.awt.Color;
import java.awt.Graphics2D;
import java.nio.file.Path;

/**
 * What the windows around a canvas (the TBrowser's TCanvas menus) ask of
 * it: save it by extension, edit an object's attributes, open the fit
 * panel, draw a ROOT marker. The canvas's own package does the work.
 */
public final class RootCanvasActions {

    private RootCanvasActions() {
    }

    /** File > Save: the canvas written by the extension of the path, as TCanvas::SaveAs does. */
    public static String save(RootCanvasView c, Path path) throws Exception {
        final RootScene s = c.getScene();
        final RootTarget t = new RootTarget("TCanvas", s, s.pad, s, c, null, Double.NaN, Double.NaN);
        final String name = path.getFileName().toString();
        if (name.endsWith(".sphere.json")) {
            java.nio.file.Files.writeString(path, RootSceneJson.write(s), java.nio.charset.StandardCharsets.UTF_8);
            return "saved " + path;
        }
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
            final Pad p = c.focusedPad();
            if (p == null || p.main() == null) throw new IllegalArgumentException("click the pad whose numbers to save first");
            final RootTarget one = new RootTarget(p.main().className, p.main(), p, s, c, null, Double.NaN, Double.NaN);
            return RootSaver.save(one, path.toString(), "");
        }
        return RootSaver.save(t, path.toString(), "");
    }

    /** View > Editor: the attributes of an object, line, fill, marker and text, applied as they change. */
    public static void editor(RootCanvasView c, Item item) {
        final RootAttEditor.Kind kind = item instanceof RootScene.Text ? RootAttEditor.Kind.TEXT
            : item instanceof RootScene.Graph || item instanceof RootScene.Hist h && h.dim == 1 && h.opt().contains("P")
            ? RootAttEditor.Kind.MARKER : item.hasFill() ? RootAttEditor.Kind.FILL : RootAttEditor.Kind.LINE;
        c.checkpoint();
        RootAttEditor.edit(c, item.className + "::" + item.name, kind, RootAttEditor.read(kind, item), att -> {
            RootAttEditor.write(kind, item, att);
            c.changed();
        });
    }

    /** Tools > Fit Panel, on the main object of a pad. */
    public static void fitPanel(RootCanvasView c, Pad pad) {
        final Item main = pad.main();
        RootFitPanel.open(new RootTarget(main.className, main, pad, c.getScene(), c, null, Double.NaN, Double.NaN));
    }

    /**
     * ROOT's context menu of an object the engine reaches by an expression
     * (an object of a file: a TTree, a TMacro, a histogram...): every
     * function of its class and of its bases, its arguments asked as
     * TContextMenu asks them, the call given to run for the engine.
     */
    public static javax.swing.JMenu engineMenu(java.awt.Component parent, String className, String object, String title,
                                               java.util.function.Consumer<String> run) {
        final javax.swing.JMenu menu = new javax.swing.JMenu("ROOT: " + className);
        String last = null;
        for (RootMethod m : RootMethod.menuOf(className)) {
            if (last != null && !last.equals(m.owner)) menu.addSeparator();
            last = m.owner;
            final javax.swing.JMenuItem item = new javax.swing.JMenuItem(m.label() + (m.params.isEmpty() || m.toggle ? "" : "..."));
            item.setToolTipText(m.owner + "::" + m.signature());
            item.addActionListener(e -> {
                final Object[] args = m.params.isEmpty() ? new Object[0] : m.toggle ? new Object[]{Boolean.TRUE}
                    : RootArgsDialog.ask(parent, title, m, null, suggestions(m), java.util.List.of(), null);
                if (args != null) run.accept(RootEngineCall.callOn(object, m, args));
            });
            menu.add(item);
        }
        return menu;
    }

    /** What a TTree's functions are usually given, to choose from. */
    private static java.util.Map<String, String[]> suggestions(RootMethod m) {
        final java.util.Map<String, String[]> s = new java.util.HashMap<>();
        if (m.owner.equals("TTree") || m.owner.equals("TChain")) {
            s.put("option", new String[]{"", "colz", "goff", "lego2", "prof", "same", "hist", "e"});
            s.put("varexp", new String[]{"", "*"});
        }
        return s;
    }

    /** A ROOT marker of a style, as the canvas draws it. */
    public static void marker(Graphics2D g, int style, double size, Color color, double x, double y) {
        RootPadPainter.marker(g, style, size, color, x, y);
    }
}

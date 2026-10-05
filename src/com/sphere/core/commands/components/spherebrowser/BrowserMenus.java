package com.sphere.components.spherebrowser;

import com.sphere.components.imaging.ImagingTheme;
import com.sphere.components.rootview.RootCanvasView;
import com.sphere.components.rootview.RootColors;
import com.sphere.components.rootview.RootFile;
import com.sphere.components.rootview.RootFonts;
import com.sphere.components.rootview.RootKey;
import com.sphere.components.rootview.RootNode;
import com.sphere.components.rootview.RootScene;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;
import com.sphere.components.rootview.RootSceneJson;
import com.sphere.components.rootview.RootStyle;
import com.sphere.components.rootview.RootWriter;
import com.sphere.core.rootbackend.RootBackend;

import javax.imageio.ImageIO;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The menus of ROOT's windows, in Sphere's: the menu bar of a TCanvas (File,
 * Edit, View, Options, Tools, Help), the menu bar of the TBrowser, and the
 * context menus of the browser's tree: a TFile (Map, DrawMap, Close), a file
 * of the disk as a TSystemFile (Rename, Copy, Move, Delete, Edit) and an
 * object of a file (Draw, Inspect, SaveAs into a .root file of its own).
 */
final class BrowserMenus {

    private BrowserMenus() {
    }

    /**
     * A menu bar in the theme's colours: the look and feel leaves the labels
     * of a bar's menus in a dark ink whatever the theme, unreadable on a dark
     * panel; each is given the ink that reads on the bar.
     */
    static void style(JMenuBar bar) {
        if (bar == null) return;
        final Color ground = ImagingTheme.panel();
        final Color ink = SphereBrowser.readableOn(ground);
        bar.setOpaque(true);
        bar.setBackground(ground);
        bar.setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 1, 0, ImagingTheme.border()));
        for (int i = 0; i < bar.getMenuCount(); i++) {
            final JMenu m = bar.getMenu(i);
            if (m == null) continue;
            m.setForeground(new Color(ink.getRGB()));
            m.setOpaque(false);
        }
        bar.repaint();
    }

    private static JMenuItem item(String label, Runnable run) {
        final JMenuItem i = new JMenuItem(label);
        i.addActionListener(e -> run.run());
        return i;
    }

    private static JMenuItem item(String label, int key, Runnable run) {
        final JMenuItem i = item(label, run);
        i.setAccelerator(KeyStroke.getKeyStroke(key, java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        return i;
    }

    /* ------------------------------------------------------------------ */
    /* TCanvas                                                             */
    /* ------------------------------------------------------------------ */

    /** What a canvas's menus act on. */
    interface CanvasDoc {
        RootCanvasView canvas();

        String title();

        void close();

        void showView(String name);
    }

    static JMenuBar canvasBar(SphereBrowser b, CanvasDoc doc) {
        final RootCanvasView c = doc.canvas();
        final JMenuBar bar = new JMenuBar();

        final JMenu file = new JMenu("File");
        file.add(item("New Canvas", () -> b.newCanvas()));
        file.add(item("Open...", b::chooseFiles));
        file.add(item("Close Canvas", doc::close));
        file.addSeparator();
        final JMenu save = new JMenu("Save");
        final String base = doc.title().replaceAll("[^\\w.-]", "_");
        for (String ext : new String[]{"png", "jpg", "gif", "C", "sphere.json", "csv", "pdf", "eps", "svg", "root", "tex"}) {
            save.add(item(base + "." + ext, () -> saveCanvas(b, c, Path.of(com.sphere.core.fs.WorkingDirectory.get().toString(),
                base + "." + ext))));
        }
        file.add(save);
        file.add(item("Save As...", () -> {
            final JFileChooser chooser = new JFileChooser(com.sphere.core.fs.WorkingDirectory.get().toFile());
            chooser.setSelectedFile(new java.io.File(base + ".png"));
            if (chooser.showSaveDialog(c) == JFileChooser.APPROVE_OPTION) saveCanvas(b, c, chooser.getSelectedFile().toPath());
        }));
        file.addSeparator();
        file.add(item("Print...", () -> print(b, c)));
        file.addSeparator();
        file.add(item("Close Browser", b::dispose));
        bar.add(file);

        final JMenu edit = new JMenu("Edit");
        edit.add(item("Style...", () -> {
            if (RootStyle.edit(c) && c.getScene() != null) {
                c.checkpoint();
                RootStyle.apply(c.getScene());
                c.changed();
                b.status("style " + RootStyle.get().name + " is gStyle; TCanvas::UseCurrentStyle applies it to the axes");
            }
        }));
        edit.add(item("Save style from this canvas", () -> {
            if (c.getScene() != null) RootStyle.capture(c.getScene(), c.focusedPad());
            b.status("the canvas's statistics, fit box and axes are now the style");
        }));
        edit.addSeparator();
        final JMenu clear = new JMenu("Clear");
        clear.add(item("Pad", () -> {
            final Pad p = c.currentPad() != null ? c.currentPad() : c.focusedPad();
            if (p == null) return;
            c.checkpoint();
            p.items.clear();
            c.changed();
        }));
        clear.add(item("Canvas", () -> {
            if (c.getScene() == null) return;
            c.checkpoint();
            c.getScene().pad.items.clear();
            c.getScene().pad.pads.clear();
            c.changed();
        }));
        edit.add(clear);
        edit.addSeparator();
        // Cut, Copy, Paste: the object of the pad clicked last, into the pad clicked next, in any canvas.
        edit.add(item("Cut", () -> copyObject(b, c, true)));
        edit.add(item("Copy", () -> copyObject(b, c, false)));
        edit.add(item("Paste", () -> {
            final Pad p = c.currentPad() != null ? c.currentPad() : c.focusedPad() != null ? c.focusedPad()
                : c.getScene() == null ? null : c.getScene().pad;
            if (p == null || clipboard == null) {
                b.status(clipboard == null ? "nothing copied yet" : "no pad to paste into");
                return;
            }
            c.checkpoint();
            final Item copy = RootSceneJson.copy(clipboard);
            if (p.main() != null && (copy instanceof RootScene.Hist || copy instanceof RootScene.Graph)
                && !copy.opt().contains("SAME")) {
                copy.option = (copy.option == null ? "" : copy.option + " ") + "SAME";
            }
            p.items.add(copy);
            c.changed();
            b.status(copy.className + " " + copy.name + " pasted into " + p.name);
        }));
        edit.addSeparator();
        edit.add(item("Undo", c::undo));
        edit.add(item("Redo", c::redo));
        bar.add(edit);

        final JMenu view = new JMenu("View");
        view.add(item("Editor", () -> editor(b, c)));
        view.add(item("Toolbar: the canvas's views", () -> doc.showView("Canvas")));
        final JCheckBoxMenuItem paper = new JCheckBoxMenuItem("Theme colours (off: ROOT's white paper)", c.isThemePaper());
        paper.addActionListener(e -> c.setThemePaper(paper.isSelected()));
        view.add(paper);
        view.addSeparator();
        view.add(item("Colors", () -> colors(c)));
        view.add(item("Fonts", () -> fonts(c)));
        view.add(item("Markers", () -> markers(c)));
        view.addSeparator();
        final JMenu with = new JMenu("View With");
        with.add(item("Sphere 3D space (OpenGL in ROOT)", () -> doc.showView("3D Space")));
        with.add(item("Data table", () -> doc.showView("Data")));
        view.add(with);
        bar.add(view);

        final JMenu options = new JMenu("Options");
        options.add(toggle("Statistics", () -> c.getScene() != null && c.getScene().optStat != 0, on -> {
            c.getScene().optStat = on ? Math.max(1111, RootStyle.get().optStat) : 0;
        }, c));
        options.add(toggle("Histogram Title", () -> c.getScene() != null && c.getScene().optTitle != 0, on -> {
            c.getScene().optTitle = on ? 1 : 0;
        }, c));
        options.add(toggle("Fit Parameters", () -> c.getScene() != null && c.getScene().optFit != 0, on -> {
            c.getScene().optFit = on ? 111 : 0;
        }, c));
        options.add(toggle("Can Edit Histograms", () -> c.focusedPad() != null && c.focusedPad().editable, on -> {
            for (Pad p : c.getScene().pad.flatten()) p.editable = on;
        }, c));
        options.add(toggle("Grayscale", () -> c.getScene() != null && c.getScene().grayscale, on -> {
            c.getScene().grayscale = on;
        }, c));
        options.addSeparator();
        options.add(item("Refresh", c::repaint));
        bar.add(options);

        final JMenu tools = new JMenu("Tools");
        tools.add(item("Inspect ROOT", () -> {
            if (c.getScene() != null) b.host().show("Inspect " + doc.title(), inspect(c.getScene()));
        }));
        tools.add(item("Classes", () -> {
            if (c.getScene() != null) b.host().show("Classes of " + doc.title(), classes(c.getScene()));
        }));
        tools.add(item("Fit Panel", () -> fitPanel(b, c)));
        tools.add(item("Start Browser", () -> b.toFront()));
        bar.add(tools);

        bar.add(help(b));
        style(bar);
        return bar;
    }

    /** What Edit > Copy and Cut took, for Paste into any canvas of the browser. */
    private static Item clipboard;

    private static void copyObject(SphereBrowser b, RootCanvasView c, boolean cut) {
        final Pad p = c.focusedPad();
        final Item main = p == null ? null : p.main();
        if (main == null) {
            b.status("click the pad of the object to " + (cut ? "cut" : "copy") + " first");
            return;
        }
        clipboard = RootSceneJson.copy(main);
        if (cut) {
            c.checkpoint();
            p.items.remove(main);
            c.changed();
        }
        b.status(main.className + " " + main.name + (cut ? " cut" : " copied") + ": Edit > Paste puts it in the pad clicked next");
    }

    private static JCheckBoxMenuItem toggle(String label, java.util.function.BooleanSupplier state,
                                            java.util.function.Consumer<Boolean> set, RootCanvasView c) {
        final JCheckBoxMenuItem i = new JCheckBoxMenuItem(label);
        i.addActionListener(e -> {
            if (c.getScene() == null) return;
            c.checkpoint();
            set.accept(i.isSelected());
            c.changed();
        });
        i.addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                i.setSelected(state.getAsBoolean());
            }

            @Override
            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
            }

            @Override
            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }
        });
        return i;
    }

    private static JMenu help(SphereBrowser b) {
        final JMenu help = new JMenu("Help");
        final String[][] pages = {
            {"Help On Browser...", "https://root.cern/manual/root_browser/"},
            {"Help On Canvas...", "https://root.cern/manual/graphics/#canvas"},
            {"Help On Menus...", "https://root.cern/manual/graphics/#context-menus"},
            {"Help On Graphics Editor...", "https://root.cern/manual/graphics/#graphics-editor"},
            {"Help On Objects...", "https://root.cern/doc/master/classTObject.html"},
            {"Help On PostScript...", "https://root.cern/doc/master/classTPostScript.html"},
            {"Help On Remote Session...", "https://root.cern/manual/root_browser/#remote-session"},
            {"Histograms", "https://root.cern/manual/histograms/"},
            {"Fitting", "https://root.cern/manual/fitting/"}};
        for (String[] p : pages) help.add(item(p[0], () -> com.sphere.utils.WebLinks.open(p[1])));
        help.addSeparator();
        help.add(item("About Sphere TBrowser", () -> JOptionPane.showMessageDialog(b,
            "Sphere TBrowser\nROOT's canvases from their numbers, ROOT's context menus on every object:\n"
                + "the functions Sphere does itself run at once, the others go to ROOT's engine.\n"
                + "Right-click any histogram, axis, statistics box, legend, pad or canvas.",
            "About", JOptionPane.INFORMATION_MESSAGE)));
        return help;
    }

    static void saveCanvas(SphereBrowser b, RootCanvasView c, Path path) {
        if (c.getScene() == null) return;
        final RootScene s = c.getScene();
        try {
            final String said = com.sphere.components.rootview.RootCanvasActions.save(c, path);
            b.status(said);
        } catch (Exception e) {
            b.status("not saved: " + e.getMessage());
            JOptionPane.showMessageDialog(c, e.getMessage(), "Save " + s.name, JOptionPane.WARNING_MESSAGE);
        }
    }

    private static void print(SphereBrowser b, RootCanvasView c) {
        final PrinterJob job = PrinterJob.getPrinterJob();
        final RootScene s = c.getScene();
        if (s == null) return;
        job.setPrintable((graphics, format, page) -> {
            if (page > 0) return Printable.NO_SUCH_PAGE;
            final Graphics2D g = (Graphics2D) graphics;
            final double scale = Math.min(format.getImageableWidth() / s.width, format.getImageableHeight() / s.height);
            final java.awt.image.BufferedImage img = c.snapshot(s.width * 2, s.height * 2);
            g.translate(format.getImageableX(), format.getImageableY());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(img, 0, 0, (int) (s.width * scale), (int) (s.height * scale), null);
            return Printable.PAGE_EXISTS;
        });
        if (job.printDialog()) {
            try {
                job.print();
                b.status("printed " + s.name);
            } catch (PrinterException e) {
                b.status("not printed: " + e.getMessage());
            }
        }
    }

    /** View > Editor: the line, fill, marker and text attributes of the object of the pad, live. */
    private static void editor(SphereBrowser b, RootCanvasView c) {
        final Pad p = c.focusedPad();
        if (p == null || p.main() == null) {
            b.status("click a pad that draws something first");
            return;
        }
        com.sphere.components.rootview.RootCanvasActions.editor(c, p.main());
    }

    private static void fitPanel(SphereBrowser b, RootCanvasView c) {
        final Pad p = c.focusedPad();
        if (p == null || p.main() == null) {
            b.status("click the pad of the histogram or the graph to fit first");
            return;
        }
        com.sphere.components.rootview.RootCanvasActions.fitPanel(c, p);
    }

    /** Tools > Inspect ROOT: the canvas, its pads and their objects, as ROOT's TInspector lists them. */
    static String inspect(RootScene s) {
        final StringBuilder b = new StringBuilder();
        b.append("TCanvas ").append(s.name).append(" \"").append(s.title).append("\" ").append(s.width).append(" x ")
            .append(s.height).append("\n  OptStat ").append(s.optStat).append("  OptFit ").append(s.optFit).append("  OptTitle ")
            .append(s.optTitle).append('\n');
        inspect(b, s.pad, 1);
        return b.toString();
    }

    private static void inspect(StringBuilder b, Pad p, int depth) {
        final String in = "  ".repeat(depth);
        b.append(in).append("TPad ").append(p.name).append(String.format(Locale.ROOT, "  [%.3f, %.3f] %.3f x %.3f", p.px, p.py,
            p.pw, p.ph)).append(p.logx ? " logx" : "").append(p.logy ? " logy" : "").append(p.logz ? " logz" : "")
            .append(p.gridx ? " gridx" : "").append(p.gridy ? " gridy" : "").append('\n');
        for (Item i : p.items) {
            b.append(in).append("  ").append(i.className).append(' ').append(i.name);
            if (i.title != null && !i.title.isBlank()) b.append(" \"").append(i.title).append('"');
            if (i.option != null && !i.option.isBlank()) b.append("  option ").append(i.option);
            if (i instanceof RootScene.Hist h) {
                b.append(String.format(Locale.ROOT, "  %dD, %d x %d x %d bins, %g entries", h.dim, h.nx(), h.ny(), h.nz(), h.entries));
                if (!h.fits.isEmpty()) b.append(", ").append(h.fits.size()).append(" function(s)");
            } else if (i instanceof RootScene.Graph g) {
                b.append("  ").append(g.x.length).append(" points");
            } else if (i instanceof RootScene.Group g) {
                b.append("  ").append(g.items.size()).append(" members");
            }
            b.append('\n');
        }
        for (Pad sub : p.pads) inspect(b, sub, depth + 1);
    }

    /** Tools > Classes: every class the canvas holds, and what it derives from. */
    static String classes(RootScene s) {
        final Map<String, Integer> count = new LinkedHashMap<>();
        for (Pad p : s.pad.flatten()) {
            count.merge(p == s.pad ? "TCanvas" : "TPad", 1, Integer::sum);
            for (Item i : p.items) {
                count.merge(i.className == null || i.className.isBlank() ? "TObject" : i.className, 1, Integer::sum);
                if (i instanceof RootScene.Group g) for (Item m : g.items) count.merge(m.className, 1, Integer::sum);
            }
        }
        final StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : count.entrySet()) {
            final List<String> lineage = com.sphere.components.rootview.RootMethod.lineage(e.getKey());
            b.append(String.format(Locale.ROOT, "%-20s x%-3d %s%n", e.getKey(), e.getValue(), String.join(" : ",
                lineage.subList(1, lineage.size()))));
        }
        return b.toString();
    }

    /* ------------------------------------------------------------------ */
    /* View > Colors, Fonts, Markers                                       */
    /* ------------------------------------------------------------------ */

    private static void window(JComponent parent, String title, JComponent body) {
        final JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), title, java.awt.Dialog.ModalityType.MODELESS);
        d.getContentPane().add(body);
        d.pack();
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
    }

    /** ROOT's colour table: the basic colours, the wheel, with their indices and names. */
    static void colors(JComponent parent) {
        final List<Map.Entry<Integer, Color>> all = new ArrayList<>(RootColors.all().entrySet());
        final int cols = 20;
        final int cell = 30;
        final JComponent grid = new JComponent() {
            {
                setPreferredSize(new Dimension(cols * cell + 8, (all.size() / cols + 1) * cell + 8));
                setToolTipText("");
            }

            @Override
            protected void paintComponent(Graphics g0) {
                final Graphics2D g = (Graphics2D) g0;
                g.setColor(ImagingTheme.panel());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
                for (int k = 0; k < all.size(); k++) {
                    final int x = 4 + (k % cols) * cell;
                    final int y = 4 + (k / cols) * cell;
                    g.setColor(all.get(k).getValue());
                    g.fillRect(x, y, cell - 2, cell - 2);
                    final Color c = all.get(k).getValue();
                    g.setColor((c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000 > 128 ? Color.BLACK : Color.WHITE);
                    g.drawString(Integer.toString(all.get(k).getKey()), x + 2, y + cell - 6);
                }
            }

            @Override
            public String getToolTipText(java.awt.event.MouseEvent e) {
                final int k = (e.getY() - 4) / cell * cols + (e.getX() - 4) / cell;
                if (k < 0 || k >= all.size()) return null;
                final Color c = all.get(k).getValue();
                return all.get(k).getKey() + "  " + RootColors.name(all.get(k).getKey())
                    + String.format("  #%06x", c.getRGB() & 0xFFFFFF);
            }
        };
        window(parent, "ROOT colours", new javax.swing.JScrollPane(grid));
    }

    /** ROOT's fonts: each code, its family, a sample. */
    static void fonts(JComponent parent) {
        final JComponent list = new JComponent() {
            {
                setPreferredSize(new Dimension(560, 15 * 30 + 10));
            }

            @Override
            protected void paintComponent(Graphics g0) {
                final Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(ImagingTheme.panel());
                g.fillRect(0, 0, getWidth(), getHeight());
                for (int f = 1; f <= 15; f++) {
                    final int code = f * 10 + 2;
                    g.setColor(ImagingTheme.text());
                    g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                    g.drawString(code + "  " + RootFonts.describe(code), 8, f * 30);
                    g.setFont(RootFonts.font(code, 18f));
                    g.drawString("The quick brown fox  αβγ 0123", 250, f * 30);
                }
            }
        };
        window(parent, "ROOT fonts", list);
    }

    /** ROOT's marker styles, 1 to 49, drawn as the canvas draws them. */
    static void markers(JComponent parent) {
        final JComponent list = new JComponent() {
            {
                setPreferredSize(new Dimension(10 * 56 + 10, 5 * 56 + 10));
            }

            @Override
            protected void paintComponent(Graphics g0) {
                final Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(ImagingTheme.panel());
                g.fillRect(0, 0, getWidth(), getHeight());
                for (int m = 1; m <= 49; m++) {
                    final int x = 10 + ((m - 1) % 10) * 56;
                    final int y = 10 + ((m - 1) / 10) * 56;
                    g.setColor(ImagingTheme.surface());
                    g.fillRect(x, y, 50, 50);
                    com.sphere.components.rootview.RootCanvasActions.marker(g, m, 2.2, ImagingTheme.text(), x + 25, y + 22);
                    g.setColor(ImagingTheme.subduedText());
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
                    g.drawString(Integer.toString(m), x + 3, y + 47);
                }
                g.setStroke(new BasicStroke(1f));
            }
        };
        window(parent, "ROOT markers", list);
    }

    /* ------------------------------------------------------------------ */
    /* TBrowser                                                            */
    /* ------------------------------------------------------------------ */

    static JMenuBar browserBar(SphereBrowser b) {
        final JMenuBar bar = new JMenuBar();
        final JMenu browser = new JMenu("Browser");
        browser.add(item("Browse...", KeyEvent.VK_B, () -> {
            final JFileChooser chooser = new JFileChooser(com.sphere.core.fs.WorkingDirectory.get().toFile());
            chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
            if (chooser.showOpenDialog(b) == JFileChooser.APPROVE_OPTION) b.openPath(chooser.getSelectedFile().toPath());
        }));
        browser.add(item("Open...", KeyEvent.VK_O, b::chooseFiles));
        browser.add(item("Clone", KeyEvent.VK_N, b::cloneSelectedTab));
        browser.add(item("New Editor", KeyEvent.VK_E, b::newEditor));
        browser.add(item("New Canvas", b::newCanvas));
        browser.add(item("Execute Macro...", () -> macro(b)));
        browser.add(item("Command...", () -> command(b)));
        browser.addSeparator();
        browser.add(item("Refresh", KeyEvent.VK_R, b::refresh));
        browser.add(item("Close Tab", KeyEvent.VK_W, b::closeSelectedTab));
        browser.add(item("Close Window", b::dispose));
        browser.add(item("Quit Root", KeyEvent.VK_Q, b::dispose));
        bar.add(browser);
        final JMenu view = new JMenu("View");
        final JCheckBoxMenuItem status = new JCheckBoxMenuItem("Status Bar", true);
        status.addActionListener(e -> b.showStatusBar(status.isSelected()));
        view.add(status);
        view.add(item("ROOT colours", () -> colors(b.getRootPane())));
        view.add(item("ROOT fonts", () -> fonts(b.getRootPane())));
        view.add(item("ROOT markers", () -> markers(b.getRootPane())));
        bar.add(view);
        bar.add(help(b));
        style(bar);
        return bar;
    }

    /** Browser > Execute Macro: a .C run by ROOT's engine; its canvases come back live. */
    private static void macro(SphereBrowser b) {
        final JFileChooser chooser = new JFileChooser(com.sphere.core.fs.WorkingDirectory.get().toFile());
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("ROOT macro", "C", "cxx", "cpp", "cc"));
        if (chooser.showOpenDialog(b) != JFileChooser.APPROVE_OPTION) return;
        final Path macro = chooser.getSelectedFile().toPath();
        runInEngine(b, ".x " + macro.toString().replace('\\', '/'), "macro " + macro.getFileName());
    }

    /** Browser > Command: a line for ROOT's interpreter; the canvases it draws come back live. */
    private static void command(SphereBrowser b) {
        final String line = JOptionPane.showInputDialog(b, "ROOT command", "Command", JOptionPane.PLAIN_MESSAGE);
        if (line == null || line.isBlank()) return;
        runInEngine(b, line.strip(), line.strip());
    }

    /** Runs a line in the engine, then asks it for every canvas it holds, each opened live. */
    static void runInEngine(SphereBrowser b, String line, String what) {
        final RootBackend engine = RootBackend.getInstance();
        if (engine == null || !engine.isAvailable()) {
            JOptionPane.showMessageDialog(b, "ROOT's engine is not running: start Sphere's ROOT mode first.", what,
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        b.status("ROOT: " + what + " ...");
        new SwingWorker<String[], Void>() {
            @Override
            protected String[] doInBackground() throws Exception {
                final Path header = com.sphere.core.bridge.Bridge.libraries().resolve("sphere_view.hpp");
                engine.executeClingAwait("#include \"" + header.toString().replace('\\', '/') + "\"", 30_000L);
                final String printed = engine.executeClingAwait(line, 300_000L);
                final String canvases = engine.executeClingAwait("{ TIter next(gROOT->GetListOfCanvases());"
                    + " while (auto *c = dynamic_cast<TCanvas *>(next())) { c->Update(); std::cout << \"@@SPHERE-CANVAS@@\""
                    + " << SphereView::Canvas(c) << \"@@SPHERE-END@@\" << std::endl; } }", 120_000L);
                return new String[]{printed, canvases};
            }

            @Override
            protected void done() {
                try {
                    final String[] r = get();
                    int n = 0;
                    final String all = r[1] == null ? "" : r[1];
                    int at = all.indexOf("@@SPHERE-CANVAS@@");
                    while (at >= 0) {
                        final int end = all.indexOf("@@SPHERE-END@@", at);
                        if (end < 0) break;
                        final RootScene s = RootScene.parse(all.substring(at + "@@SPHERE-CANVAS@@".length(), end));
                        if (s.error == null) {
                            b.host().open(s, s.name == null || s.name.isBlank() ? "canvas" : s.name);
                            n++;
                        }
                        at = all.indexOf("@@SPHERE-CANVAS@@", end);
                    }
                    if (r[0] != null && !r[0].isBlank()) b.host().show(what, r[0]);
                    b.status(what + ": " + n + " canvas" + (n == 1 ? "" : "es") + " from ROOT");
                } catch (Exception e) {
                    b.status(what + " failed: " + e.getMessage());
                }
            }
        }.execute();
    }

    /* ------------------------------------------------------------------ */
    /* The tree's context menus                                            */
    /* ------------------------------------------------------------------ */

    static JPopupMenu treeMenu(SphereBrowser b, SphereBrowser.Entry e) {
        final JPopupMenu menu = new JPopupMenu();
        final javax.swing.JLabel title = new javax.swing.JLabel(switch (e.kind) {
            case ROOTFILE -> "TFile::" + e.label;
            case ROOTOBJECT -> e.node.className + "::" + e.node.name;
            case ROOTDIR -> "TDirectoryFile::" + e.node.name;
            case DIR -> "TSystemDirectory::" + e.label;
            default -> "TSystemFile::" + e.label;
        }, javax.swing.SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 10, 4, 10));
        menu.add(title);
        menu.addSeparator();
        switch (e.kind) {
            case ROOTFILE -> {
                menu.add(item("Browse", () -> b.expandEntry(e)));
                menu.add(item("Map", () -> {
                    try {
                        b.host().show("TFile::Map " + e.label, map(b.rootFileOf(e)));
                    } catch (IOException ex) {
                        b.status("cannot read " + e.label + ": " + ex.getMessage());
                    }
                }));
                menu.add(item("DrawMap", () -> {
                    try {
                        b.host().open(drawMap(b.rootFileOf(e)), "DrawMap " + e.label);
                    } catch (IOException ex) {
                        b.status("cannot read " + e.label + ": " + ex.getMessage());
                    }
                }));
                menu.add(item("Close", () -> b.closeRootFile(e)));
                menu.addSeparator();
                systemFile(b, e, menu);
            }
            case ROOTOBJECT -> {
                menu.add(item("Draw", () -> b.openEntry(e)));
                menu.add(item("Inspect", () -> b.host().show(e.node.className + "::" + e.node.name, inspect(e))));
                menu.add(item("SaveAs...", () -> saveObject(b, e)));
                // Every function of the object's class, done by ROOT's engine on the object of the file itself.
                try {
                    final RootNode top = e.file.tree();
                    String inner = e.node.pathFrom(top);
                    if (inner.startsWith(top.name + "/")) inner = inner.substring(top.name.length() + 1);
                    final String file = e.file.getPath().toAbsolutePath().toString().replace('\\', '/');
                    final String object = "SphereView::Find(" + cpp("file:" + file) + ", " + cpp(inner) + ")";
                    final String what = e.node.className + "::" + e.node.name;
                    menu.addSeparator();
                    menu.add(com.sphere.components.rootview.RootCanvasActions.engineMenu(b, e.node.className, object, what,
                        code -> runInEngine(b, code, what)));
                } catch (IOException ignored) {
                    // the file's tree is unreadable: no engine menu
                }
            }
            case ROOTDIR -> menu.add(item("Browse", () -> b.expandEntry(e)));
            case SCENE, PICTURE, FILE, CANVAS -> {
                menu.add(item("Draw", () -> b.openEntry(e)));
                menu.addSeparator();
                systemFile(b, e, menu);
            }
            case DIR -> menu.add(item("Browse", () -> b.expandEntry(e)));
            default -> {
                return null;
            }
        }
        return menu;
    }

    /** A C++ string literal. */
    static String cpp(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** TSystemFile's menu: Rename, Copy, Move, Delete, Edit. */
    private static void systemFile(SphereBrowser b, SphereBrowser.Entry e, JPopupMenu menu) {
        final Path p = e.path != null ? e.path : e.scene;
        if (p == null || !Files.isRegularFile(p)) return;
        menu.add(item("Rename...", () -> {
            final String name = JOptionPane.showInputDialog(b, "New name", p.getFileName().toString());
            if (name == null || name.isBlank() || name.equals(p.getFileName().toString())) return;
            try {
                Files.move(p, p.resolveSibling(name.strip()));
                b.status("renamed " + p.getFileName() + " to " + name.strip());
                b.refresh();
            } catch (IOException ex) {
                b.status("not renamed: " + ex.getMessage());
            }
        }));
        menu.add(item("Copy...", () -> copyOrMove(b, p, false)));
        menu.add(item("Move...", () -> copyOrMove(b, p, true)));
        menu.add(item("Delete...", () -> {
            if (JOptionPane.showConfirmDialog(b, "Delete " + p + " from the disk?", "TSystemFile::Delete",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }
            try {
                Files.delete(p);
                b.status("deleted " + p);
                b.refresh();
            } catch (IOException ex) {
                b.status("not deleted: " + ex.getMessage());
            }
        }));
        menu.add(item("Edit", () -> {
            final String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (n.endsWith(".root") || SphereBrowser.isPicture(p)) {
                b.status("Edit opens text files; " + p.getFileName() + " is not one");
                return;
            }
            try {
                b.host().show(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                b.status("cannot read " + p + ": " + ex.getMessage());
            }
        }));
    }

    private static void copyOrMove(SphereBrowser b, Path p, boolean move) {
        final JFileChooser chooser = new JFileChooser(p.getParent().toFile());
        chooser.setSelectedFile(p.toFile());
        chooser.setDialogTitle((move ? "Move " : "Copy ") + p.getFileName() + " to");
        if (chooser.showSaveDialog(b) != JFileChooser.APPROVE_OPTION) return;
        final Path to = chooser.getSelectedFile().toPath();
        if (to.equals(p)) return;
        if (Files.exists(to) && JOptionPane.showConfirmDialog(b, to + " exists: replace it?", move ? "Move" : "Copy",
            JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            if (move) Files.move(p, to, StandardCopyOption.REPLACE_EXISTING);
            else Files.copy(p, to, StandardCopyOption.REPLACE_EXISTING);
            b.status((move ? "moved to " : "copied to ") + to);
            b.refresh();
        } catch (IOException ex) {
            b.status((move ? "not moved: " : "not copied: ") + ex.getMessage());
        }
    }

    private static String inspect(SphereBrowser.Entry e) {
        final RootKey k = e.node.key;
        final StringBuilder s = new StringBuilder();
        s.append(e.node.className).append(' ').append(e.node.name).append("  \"").append(e.node.title).append("\"\n");
        if (k != null) {
            s.append(String.format(Locale.ROOT, "  cycle %d, at byte %d, %d bytes on disk for %d in memory (x%.2f), key %d bytes%n",
                k.cycle, k.seekKey, k.nbytes, k.objlen, k.compressionRatio(), k.keylen));
            if (k.written() != null) s.append("  written ").append(k.written()).append('\n');
        }
        s.append("  inherits from ").append(String.join(" : ", com.sphere.components.rootview.RootMethod.lineage(e.node.className)
            .subList(1, com.sphere.components.rootview.RootMethod.lineage(e.node.className).size()))).append('\n');
        return s.toString();
    }

    /** TObject::SaveAs of an object of a file: its record copied, as it is, into a .root file of its own. */
    private static void saveObject(SphereBrowser b, SphereBrowser.Entry e) {
        final JFileChooser chooser = new JFileChooser(com.sphere.core.fs.WorkingDirectory.get().toFile());
        chooser.setSelectedFile(new java.io.File(e.node.name + ".root"));
        if (chooser.showSaveDialog(b) != JFileChooser.APPROVE_OPTION) return;
        final Path to = chooser.getSelectedFile().toPath();
        try {
            final RootFile f = e.file;
            final RootKey k = e.node.key;
            final RootWriter.Dir top = new RootWriter.Dir(to.getFileName().toString(), "");
            top.objects.add(new RootWriter.Entry(k.name, k.title, k.className, f.storedPayload(k), k.objlen, k.datime));
            RootWriter.write(to, top, f.getFormatVersion(), f.getCompressionAlgorithm() * 100 + f.getCompressionLevel(),
                f.streamerInfoRecord(), f.streamerInfoObjectLength());
            b.status(e.node.name + " saved in " + to);
        } catch (IOException | RuntimeException ex) {
            b.status("not saved: " + ex.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* TFile::Map and TFile::DrawMap                                       */
    /* ------------------------------------------------------------------ */

    static List<RootKey> keys(RootFile f) throws IOException {
        final List<RootKey> out = new ArrayList<>();
        collectKeys(f.tree(), out);
        out.sort(Comparator.comparingLong(k -> k.seekKey));
        return out;
    }

    private static void collectKeys(RootNode n, List<RootKey> out) {
        if (n.key != null && !out.contains(n.key)) out.add(n.key);
        for (RootNode c : n.children) collectKeys(c, out);
    }

    /** As TFile::Map prints it: each record, where it is, how big, its class and compression. */
    static String map(RootFile f) throws IOException {
        final StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.ROOT, "%-18s At:%-10d N=%-10d %-16s%n", "", 0, 100, "TFile header"));
        for (RootKey k : keys(f)) {
            final java.time.LocalDateTime w = k.written();
            final String date = w == null ? "" : String.format(Locale.ROOT, "%04d%02d%02d/%02d%02d%02d", w.getYear(),
                w.getMonthValue(), w.getDayOfMonth(), w.getHour(), w.getMinute(), w.getSecond());
            b.append(String.format(Locale.ROOT, "%-18s At:%-10d N=%-10d %-16s", date, k.seekKey, k.nbytes, k.className));
            if (k.compressed()) b.append(String.format(Locale.ROOT, " CX = %5.2f", k.compressionRatio()));
            b.append("  ").append(k.name).append(';').append(k.cycle).append('\n');
        }
        b.append(String.format(Locale.ROOT, "%-18s At:%-10d N=%-10d %-16s%n", "", f.getStreamerInfoSeek(),
            f.getStreamerInfoBytes(), "StreamerInfo"));
        b.append(String.format(Locale.ROOT, "%-18s At:%-10d N=%-10d %-16s%n", "", f.getFreeSeek(), 0, "FreeSegments"));
        b.append(String.format(Locale.ROOT, "%-18s At:%-10d %s%n", "", f.getEnd(), "END"));
        b.append(String.format(Locale.ROOT, "%n%s: %d bytes, format %d, %s level %d%n", f.getPath().getFileName(), f.getSize(),
            f.getFormatVersion(), RootFile.compressionName(f.getCompressionAlgorithm()), f.getCompressionLevel()));
        return b.toString();
    }

    /**
     * TFile::DrawMap: the file as rows of bytes, each record a box of its
     * class's colour where it sits; the legend names the classes and how
     * much of the file each takes.
     */
    static RootScene drawMap(RootFile f) throws IOException {
        final List<RootKey> keys = keys(f);
        final long size = Math.max(1, f.getSize());
        final int rows = 40;
        final long row = Math.max(1000, (long) Math.ceil(size / (double) rows / 1000) * 1000);
        final RootScene s = new RootScene();
        s.name = "DrawMap";
        s.title = f.getPath().getFileName() + ": " + size + " bytes, " + keys.size() + " records";
        s.width = 1000;
        s.height = 700;
        final Pad p = s.pad;
        p.lm = 0.08;
        p.rm = 0.28;
        p.bm = 0.08;
        p.tm = 0.08;
        p.ux1 = -0.08 * row / (1 - 0.08 - 0.28);
        p.ux2 = row + 0.28 * row / (1 - 0.08 - 0.28);
        final double nrows = Math.ceil(size / (double) row);
        p.uy1 = -0.08 * nrows / 0.84;
        p.uy2 = nrows + 0.08 * nrows / 0.84;
        final Map<String, Long> bytesByClass = new LinkedHashMap<>();
        final Map<String, Color> colourOf = new LinkedHashMap<>();
        for (RootKey k : keys) {
            final String cls = k.className;
            colourOf.computeIfAbsent(cls, c -> RootColors.color(new int[]{2, 3, 4, 6, 7, 8, 9, 28, 30, 38, 41, 42, 46, 49}
                [colourOf.size() % 14]));
            bytesByClass.merge(cls, (long) k.nbytes, Long::sum);
            long from = k.seekKey;
            final long to = k.seekKey + k.nbytes;
            while (from < to) {
                final long r = from / row;
                final long end = Math.min(to, (r + 1) * row);
                final RootScene.Shape box = new RootScene.Shape();
                box.kind = "box";
                box.className = "TBox";
                box.name = k.name;
                box.title = cls + " " + k.name + ";" + k.cycle + " at " + k.seekKey + ", " + k.nbytes + " bytes";
                box.x1 = from - r * row;
                box.x2 = end - r * row;
                box.y1 = nrows - r - 0.9;
                box.y2 = nrows - r - 0.1;
                box.fill = colourOf.get(cls);
                box.fillStyle = 1001;
                box.line = colourOf.get(cls).darker();
                p.items.add(box);
                from = end;
            }
        }
        final RootScene.Pave legend = new RootScene.Pave();
        legend.kind = "legend";
        legend.className = "TLegend";
        legend.name = "classes";
        legend.x1 = 0.74;
        legend.x2 = 0.99;
        legend.y2 = 0.92;
        legend.y1 = Math.max(0.08, 0.92 - 0.045 * (colourOf.size() + 1));
        legend.header = "bytes by class";
        legend.border = 1;
        for (Map.Entry<String, Color> c : colourOf.entrySet()) {
            final RootScene.Entry e = new RootScene.Entry();
            e.text = String.format(Locale.ROOT, "%s  %.1f%%", c.getKey(), 100.0 * bytesByClass.get(c.getKey()) / size);
            e.option = "f";
            e.fill = c.getValue();
            e.fillStyle = 1001;
            legend.lines.add(e);
        }
        p.items.add(legend);
        final RootScene.Text caption = new RootScene.Text();
        caption.kind = "text";
        caption.className = "TLatex";
        caption.ndc = true;
        caption.x = 0.08;
        caption.y = 0.95;
        caption.size = 0.03;
        caption.text = "one row = " + row + " bytes; left to right, top to bottom, as the file is laid out";
        p.items.add(caption);
        return s;
    }
}

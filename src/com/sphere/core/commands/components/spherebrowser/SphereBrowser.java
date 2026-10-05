package com.sphere.components.spherebrowser;

import com.sphere.components.imaging.ImagingTheme;
import com.sphere.components.rootview.RootCanvasView;
import com.sphere.components.rootview.RootFile;
import com.sphere.components.rootview.RootGraph;
import com.sphere.components.rootview.RootGraph2D;
import com.sphere.components.rootview.RootHistogram;
import com.sphere.components.rootview.RootHost;
import com.sphere.components.rootview.RootNode;
import com.sphere.components.rootview.RootPlotsPanel;
import com.sphere.components.rootview.RootScene;
import com.sphere.components.rootview.RootScene.Hist;
import com.sphere.components.rootview.RootScene.Item;
import com.sphere.components.rootview.RootScene.Pad;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Sphere's TBrowser: ROOT's browser rebuilt for Sphere, without ROOT's
 * windowing and further than it.
 *
 * On the left, what there is to look at: the canvases of the ROOT demos run
 * this session (live scenes and their pictures, run by run), the pictures of
 * the Plots folder, the .root files opened (read by Sphere's own reader, no
 * ROOT needed) and the disk. On the right, a tab per object, each with the
 * views that fit it:
 *
 *   Canvas    the canvas redrawn from its numbers: zoom on a range, change the
 *             draw option, log scales, as in ROOT;
 *   3D Space  Sphere's own 3D engine: turn, zoom, stretch X Y Z, cut the box
 *             open, iso-surfaces, a detector exploded, stereo, GIF, OBJ;
 *   Analysis  fits, model ranking, peaks, Bayesian Blocks, comparisons;
 *   Picture   the PNG at any zoom and angle, a loupe, a pixel probe;
 *   Relief    a picture as a landscape of its ink, turned in 3D;
 *   Data      the bins themselves.
 *
 * What a demo draws arrives as a scene (demo_x_c1.sphere.json, written beside
 * its PNG by sphere_view3d.hpp) and opens live: the PNG is only the fallback.
 */
public final class SphereBrowser extends JFrame {

    private static SphereBrowser instance;
    /** What the toolbar's "ROOT demos" button opens; set by the command layer. */
    public static volatile Runnable demoLauncher;

    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Sphere");
    private final DefaultMutableTreeNode demos = new DefaultMutableTreeNode(new Entry(Kind.FOLDER, "ROOT demos (this session)", null));
    private final DefaultMutableTreeNode plots = new DefaultMutableTreeNode(new Entry(Kind.FOLDER, "Plots folder", null));
    private final DefaultMutableTreeNode files = new DefaultMutableTreeNode(new Entry(Kind.FOLDER, "ROOT files", null));
    private final DefaultMutableTreeNode disk = new DefaultMutableTreeNode(new Entry(Kind.FOLDER, "Working directory", null));
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model);
    private final JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
    private final JLabel status = new JLabel(" ");
    private final JTextField filter = new JTextField(14);
    private final Map<Path, RootFile> openFiles = new LinkedHashMap<>();
    private final Map<Path, ImageIcon> thumbs = new ConcurrentHashMap<>();
    private final Timer watch;
    private long plotsStamp;
    private final BrowserHost host = new BrowserHost();

    /* ------------------------------------------------------------------ */
    /* Entry points                                                        */
    /* ------------------------------------------------------------------ */

    /** Opens the browser, or brings it to the front. */
    public static void open() {
        open(List.of());
    }

    /** Opens the browser with files: .root, .sphere.json, pictures. */
    public static void open(List<Path> paths) {
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            final SphereBrowser b = window();
            for (Path p : paths) b.openPath(p);
        });
    }

    /**
     * A demo has run: its scenes open live, its pictures when it left no
     * scene, and the tree shows the new run.
     */
    public static void showDemo(String label, List<Path> scenes, List<Path> pictures) {
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            final SphereBrowser b = window();
            b.refresh();
            if (!scenes.isEmpty()) {
                for (Path s : scenes) b.openPath(s);
            } else {
                for (Path p : pictures) {
                    if (p.toString().toLowerCase(Locale.ROOT).endsWith(".png")) b.openPath(p);
                }
            }
            b.status("demo " + label + ": " + scenes.size() + " live scene" + (scenes.size() == 1 ? "" : "s") + ", "
                + pictures.size() + " picture" + (pictures.size() == 1 ? "" : "s"));
        });
    }

    /** A histogram Sphere decoded itself (the Plots tab, the :root reader), opened live. */
    public static void openHistogram(RootHistogram h, String title) {
        openItem(() -> Scenes.hist(h), title);
    }

    public static void openGraph(RootGraph g, String title) {
        openItem(() -> Scenes.graph(g), title);
    }

    public static void openGraph2D(RootGraph2D g, String title) {
        openItem(() -> Scenes.graph2d(g), title);
    }

    private static void openItem(java.util.function.Supplier<Item> make, String title) {
        if (GraphicsEnvironment.isHeadless()) return;
        SwingUtilities.invokeLater(() -> {
            final SphereBrowser b = window();
            final Item item = make.get();
            final String t = title == null || title.isBlank() ? item.name : title;
            if (!b.select(t)) b.openMade(Scenes.single(item, t), t);
        });
    }

    private static SphereBrowser window() {
        if (instance == null || !instance.isDisplayable()) instance = new SphereBrowser();
        instance.setVisible(true);
        instance.toFront();
        return instance;
    }

    private SphereBrowser() {
        super("Sphere TBrowser");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        final Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1560, (int) (screen.width * 0.9)), Math.min(980, (int) (screen.height * 0.88)));
        setLocationRelativeTo(null);
        getContentPane().setBackground(ImagingTheme.panel());

        root.add(demos);
        root.add(plots);
        root.add(files);
        root.add(disk);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0);
        tree.setCellRenderer(new Renderer());
        tree.setBackground(ImagingTheme.surface());
        tree.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) openSelected();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                final TreePath at = tree.getPathForLocation(e.getX(), e.getY());
                if (at == null) return;
                tree.setSelectionPath(at);
                final Entry en = selectedEntry();
                if (en == null) return;
                final javax.swing.JPopupMenu menu = BrowserMenus.treeMenu(SphereBrowser.this, en);
                if (menu != null) menu.show(tree, e.getX(), e.getY());
            }
        });
        tree.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "sphere-open");
        tree.getActionMap().put("sphere-open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected();
            }
        });
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                expand((DefaultMutableTreeNode) event.getPath().getLastPathComponent());
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        tree.addTreeSelectionListener(e -> {
            final Entry en = selectedEntry();
            if (en != null) status(en.describe());
        });

        final JPanel left = ImagingTheme.panelOf(new BorderLayout());
        final JPanel search = ImagingTheme.panelOf(new BorderLayout(4, 0));
        search.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        final JLabel fl = new JLabel("Filter");
        fl.setForeground(ImagingTheme.subduedText());
        search.add(fl, BorderLayout.WEST);
        search.add(filter, BorderLayout.CENTER);
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh();
            }
        });
        left.add(search, BorderLayout.NORTH);
        final JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        left.add(treeScroll, BorderLayout.CENTER);
        left.setPreferredSize(new Dimension(330, 100));

        tabs.setBackground(ImagingTheme.panel());
        final JPanel welcome = welcome();
        tabs.addTab("Welcome", welcome);
        tabs.setTabComponentAt(0, tabHeader("Welcome", welcome, false));
        tabs.addChangeListener(e -> restyleTabs());
        restyleTabs();
        readable(filter);

        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, tabs);
        split.setDividerLocation(330);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());

        status.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        status.setForeground(ImagingTheme.subduedText());
        status.setFont(ImagingTheme.uiFont(Font.PLAIN, 12f));

        setContentPane(new JPanel() {
            @Override
            public void updateUI() {
                super.updateUI();
                // ThemeManager walks every window when the theme changes: the browser follows.
                SwingUtilities.invokeLater(SphereBrowser.this::retheme);
            }
        });
        getContentPane().setLayout(new BorderLayout());
        setJMenuBar(BrowserMenus.browserBar(this));
        getContentPane().add(toolbar(), BorderLayout.NORTH);
        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(status, BorderLayout.SOUTH);

        setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                try {
                    final List<File> dropped = (List<File>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                    for (File f : dropped) openPath(f.toPath());
                    return true;
                } catch (Exception e) {
                    status("could not open what was dropped: " + e.getMessage());
                    return false;
                }
            }
        });

        refresh();
        tree.expandPath(new TreePath(new Object[]{root, demos}));
        watch = new Timer(2500, e -> {
            final long stamp = folderStamp(RootPlotsPanel.plotsFolder());
            if (stamp != plotsStamp) refresh();
        });
        watch.start();
    }

    @Override
    public void dispose() {
        watch.stop();
        for (RootFile f : openFiles.values()) {
            try {
                f.close();
            } catch (IOException ignored) {
                // closing on the way out
            }
        }
        openFiles.clear();
        super.dispose();
    }

    private JPanel toolbar() {
        final JPanel bar = ImagingTheme.panelOf(new FlowLayout(FlowLayout.LEFT, 6, 5));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ImagingTheme.border()));
        final JLabel logo = new JLabel("◉ SPHERE TBrowser");
        logo.setFont(ImagingTheme.uiFont(Font.BOLD, 14f));
        logo.setForeground(ImagingTheme.accent());
        bar.add(logo);
        bar.add(button("Open…", "A .root file, a scene (.sphere.json) or a picture", this::chooseFiles));
        bar.add(button("Refresh", "Read the folders again", this::refresh));
        bar.add(button("ROOT demos…", "ROOT's .demo bar: each demo's canvases open here, live", () -> {
            final Runnable r = demoLauncher;
            if (r != null) r.run();
            else status("the demos are started from the console: .demo");
        }));
        bar.add(button("Close tab", "Close the tab shown", () -> {
            final int i = tabs.getSelectedIndex();
            if (i >= 0) closeTab(i);
        }));
        bar.add(button("Close all", "Close every tab", () -> {
            while (tabs.getTabCount() > 0) closeTab(0);
        }));
        return bar;
    }

    private static JButton button(String label, String tip, Runnable run) {
        final JButton b = ImagingTheme.textButton(label, tip);
        b.addActionListener(e -> run.run());
        return b;
    }

    private JPanel welcome() {
        final JPanel p = ImagingTheme.panelOf(new BorderLayout());
        final JLabel text = new JLabel("<html><div style='padding:24px;font-family:sans-serif;width:640px'>"
            + "<h2 style='margin:0'>Sphere TBrowser</h2>"
            + "<p>Double-click on the left: a demo's canvas, a picture, an object of a .root file. "
            + "Drop files on this window to open them.</p>"
            + "<p><b>Canvas</b> &nbsp;ROOT's canvas from its numbers: drag a range to zoom, right-click for the "
            + "draw options, log scales, axes.<br>"
            + "<b>3D Space</b> &nbsp;Sphere's own 3D engine: drag to turn, wheel to zoom, Ctrl/Shift/Alt + wheel to "
            + "stretch Z/X/Y, cut the box open, iso-surfaces of a TH3, a geometry exploded, stereo, a turntable GIF, "
            + "OBJ.<br>"
            + "<b>Analysis</b> &nbsp;fits (χ² or Poisson), every model ranked by AICc, peaks, Bayesian Blocks, "
            + "KS and χ² tests, projections and profiles.<br>"
            + "<b>Picture</b> &nbsp;a PNG at any zoom and angle, loupe, pixel probe.<br>"
            + "<b>Relief</b> &nbsp;any picture as a landscape of its ink, in 3D.</p>"
            + "<p>Run <code>.demo</code> in ROOT mode: every canvas a demo draws opens here live, "
            + "not as a picture.</p></div></html>");
        text.setForeground(ImagingTheme.text());
        text.setVerticalAlignment(JLabel.TOP);
        p.add(text, BorderLayout.CENTER);
        return p;
    }

    void status(String s) {
        status.setText(s);
    }

    void showStatusBar(boolean on) {
        status.setVisible(on);
        revalidate();
    }

    /** The window as ROOT's canvases see it: where new canvases, printouts and messages go. */
    RootHost host() {
        return host;
    }

    /** File > New Canvas: an empty c1, as new TCanvas makes it. */
    void newCanvas() {
        final RootScene s = new RootScene();
        int n = 1;
        while (hasTab("c" + n)) n++;
        s.name = "c" + n;
        s.title = s.name;
        s.pad.name = s.name;
        openMade(s, s.name);
    }

    private boolean hasTab(String title) {
        for (int i = 0; i < tabs.getTabCount(); i++) if (title.equals(tabs.getTitleAt(i))) return true;
        return false;
    }

    void closeSelectedTab() {
        final int i = tabs.getSelectedIndex();
        if (i >= 0) closeTab(i);
    }

    /** Browser > Clone: the canvas of the tab shown, as it is now, in a tab of its own. */
    void cloneSelectedTab() {
        if (tabs.getSelectedComponent() instanceof SceneDoc d && d.canvas.getScene() != null) {
            host.open(com.sphere.components.rootview.RootSceneJson.copy(d.canvas.getScene()), d.title + " (clone)");
        } else {
            status("Clone copies a canvas: show one first");
        }
    }

    /** Browser > New Editor: a macro editor whose macro ROOT's engine runs. */
    void newEditor() {
        int n = 1;
        while (hasTab("Editor " + n)) n++;
        addTab("Editor " + n, new com.sphere.components.rootview.RootMacroPane(), "a macro for ROOT's engine");
    }

    void expandEntry(Entry e) {
        final TreePath p = tree.getSelectionPath();
        if (p != null) tree.expandPath(p);
    }

    void openEntry(Entry e) {
        openSelected();
    }

    RootFile rootFileOf(Entry e) throws IOException {
        return e.file != null ? e.file : rootFile(e.path);
    }

    /** TFile::Close: the file let go, its node gone from the tree. */
    void closeRootFile(Entry e) {
        final Path key = e.path.toAbsolutePath().normalize();
        final RootFile f = openFiles.remove(key);
        if (f != null) {
            try {
                f.close();
            } catch (IOException ignored) {
                // closed anyway
            }
        }
        final TreePath p = tree.getSelectionPath();
        if (p != null && p.getLastPathComponent() instanceof DefaultMutableTreeNode n && n.getParent() == files) {
            files.remove(n);
            model.nodeStructureChanged(files);
        }
        status("closed " + e.path.getFileName());
    }

    /** What the canvases ask of the browser: a tab for a new canvas, a tab for a printout, the status line. */
    private final class BrowserHost implements RootHost {
        @Override
        public void open(RootScene scene, String title) {
            String t = title == null || title.isBlank() ? "canvas" : title;
            if (hasTab(t)) {
                int n = 2;
                while (hasTab(t + " (" + n + ")")) n++;
                t = t + " (" + n + ")";
            }
            openMade(scene, t);
        }

        @Override
        public void show(String title, String text) {
            addTab(title, new TextDoc(title, text), title);
        }

        @Override
        public void status(String text) {
            SphereBrowser.this.status(text);
        }

        /** The histograms and graphs of the other tabs, for TProfile::Add(h1, h2) and the like. */
        @Override
        public Map<String, Object> objects(String baseClass) {
            final Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (!(tabs.getComponentAt(i) instanceof SceneDoc d)) continue;
                for (Pad p : d.canvas.getScene().pad.flatten()) {
                    for (Item it : p.items) {
                        final String cls = it.className == null || it.className.isBlank() ? "TObject" : it.className;
                        if (com.sphere.components.rootview.RootMethod.inherits(cls, baseClass)) {
                            out.put(cls + "::" + it.name + "  [" + tabs.getTitleAt(i) + "]", it);
                        }
                    }
                }
            }
            return out;
        }
    }

    /* ------------------------------------------------------------------ */
    /* The tree                                                            */
    /* ------------------------------------------------------------------ */

    enum Kind { FOLDER, DEMO, CANVAS, SCENE, PICTURE, ROOTFILE, ROOTDIR, ROOTOBJECT, DIR, FILE }

    /** One node of the tree: what it is and where it lives. */
    static final class Entry {
        final Kind kind;
        final String label;
        final Path path;
        /** For a canvas of a demo: its scene, when one was written; and its pictures. */
        Path scene;
        final List<Path> pictures = new ArrayList<>();
        RootFile file;
        RootNode node;
        boolean loaded;

        Entry(Kind kind, String label, Path path) {
            this.kind = kind;
            this.label = label;
            this.path = path;
        }

        String describe() {
            return switch (kind) {
                case CANVAS -> label + (scene != null ? "  — live scene" : "") + (pictures.isEmpty() ? ""
                    : "  — " + pictures.size() + " picture" + (pictures.size() == 1 ? "" : "s"));
                case ROOTOBJECT -> node.className + "  " + node.name + (node.title.isBlank() ? "" : "  \"" + node.title + "\"")
                    + (node.isReadableHere() ? "" : "  (not drawn by Sphere's reader)");
                default -> path == null ? label : path.toString();
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private Entry selectedEntry() {
        final TreePath p = tree.getSelectionPath();
        if (p == null) return null;
        final Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        return o instanceof Entry e ? e : null;
    }

    private static long folderStamp(Path folder) {
        long s = 0;
        try (Stream<Path> list = Files.walk(folder, 3)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                s = s * 31 + p.toString().hashCode();
                s += Files.getLastModifiedTime(p).toMillis();
            }
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        return s;
    }

    /** Reads the demos and the plots folder again; the files and the disk keep their state. */
    void refresh() {
        final Path folder = RootPlotsPanel.plotsFolder();
        plotsStamp = folderStamp(folder);
        final String word = filter.getText().strip().toLowerCase(Locale.ROOT);
        demos.removeAllChildren();
        plots.removeAllChildren();
        // Demos: the last runs of single demos, then each run of all of them.
        addDemos(demos, folder, word);
        final Path runs = folder.resolve("root-demos");
        if (Files.isDirectory(runs)) {
            try (Stream<Path> list = Files.list(runs)) {
                final List<Path> dirs = list.filter(Files::isDirectory).sorted(Collections.reverseOrder()).toList();
                for (Path d : dirs) {
                    final DefaultMutableTreeNode run = new DefaultMutableTreeNode(new Entry(Kind.FOLDER,
                        "run " + d.getFileName(), d));
                    addDemos(run, d, word);
                    if (run.getChildCount() > 0) demos.add(run);
                }
            } catch (IOException ignored) {
                // no runs
            }
        }
        // Other pictures and scenes of the plots folder.
        try (Stream<Path> list = Files.list(folder)) {
            list.filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().startsWith("demo_"))
                .filter(p -> isPicture(p) || isScene(p) || isRootFile(p))
                .filter(p -> word.isEmpty() || p.getFileName().toString().toLowerCase(Locale.ROOT).contains(word))
                .sorted((a, b) -> Long.compare(modified(b), modified(a)))
                .forEach(p -> plots.add(fileNode(p)));
        } catch (IOException ignored) {
            // an empty folder
        }
        ((Entry) demos.getUserObject()).loaded = true;
        // The disk: one level now, the rest when expanded.
        final Entry de = (Entry) disk.getUserObject();
        if (!de.loaded) {
            disk.setUserObject(new Entry(Kind.DIR, "Working directory", com.sphere.core.fs.WorkingDirectory.get()));
            disk.add(new DefaultMutableTreeNode("…"));
        }
        final List<TreePath> expanded = expandedPaths();
        model.reload();
        for (TreePath p : expanded) tree.expandPath(rebind(p));
        tree.expandPath(new TreePath(new Object[]{root, demos}));
    }

    private List<TreePath> expandedPaths() {
        final List<TreePath> out = new ArrayList<>();
        final java.util.Enumeration<TreePath> e = tree.getExpandedDescendants(new TreePath(root));
        if (e != null) while (e.hasMoreElements()) out.add(e.nextElement());
        return out;
    }

    /** The same path by labels, in the tree as it is now. */
    private TreePath rebind(TreePath old) {
        DefaultMutableTreeNode at = root;
        final List<Object> path = new ArrayList<>();
        path.add(root);
        for (int i = 1; i < old.getPathCount(); i++) {
            final String label = String.valueOf(((DefaultMutableTreeNode) old.getPathComponent(i)).getUserObject());
            DefaultMutableTreeNode next = null;
            for (int k = 0; k < at.getChildCount(); k++) {
                final DefaultMutableTreeNode c = (DefaultMutableTreeNode) at.getChildAt(k);
                if (String.valueOf(c.getUserObject()).equals(label)) {
                    next = c;
                    break;
                }
            }
            if (next == null) break;
            path.add(next);
            at = next;
        }
        return new TreePath(path.toArray());
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** demo_label_canvas.ext files grouped: demo, then canvas, then its scene and pictures. */
    private void addDemos(DefaultMutableTreeNode parent, Path folder, String word) {
        final Map<String, Map<String, Entry>> byDemo = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (Stream<Path> list = Files.list(folder)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                final String n = p.getFileName().toString();
                if (!n.startsWith("demo_") || !(isPicture(p) || isScene(p))) continue;
                final String stem = isScene(p) ? n.substring(0, n.length() - ".sphere.json".length())
                    : n.substring(0, n.lastIndexOf('.'));
                final String rest = stem.substring(5);
                final int cut = rest.lastIndexOf('_');
                final String demo = cut > 0 ? rest.substring(0, cut) : rest;
                final String canvas = cut > 0 ? rest.substring(cut + 1) : "canvas";
                if (!word.isEmpty() && !rest.toLowerCase(Locale.ROOT).contains(word)) continue;
                final Entry e = byDemo.computeIfAbsent(demo, d -> new TreeMap<>())
                    .computeIfAbsent(canvas, c -> new Entry(Kind.CANVAS, c, folder.resolve(stem)));
                if (isScene(p)) e.scene = p;
                else e.pictures.add(p);
            }
        } catch (IOException ignored) {
            return;
        }
        for (Map.Entry<String, Map<String, Entry>> d : byDemo.entrySet()) {
            final DefaultMutableTreeNode demo = new DefaultMutableTreeNode(new Entry(Kind.DEMO, d.getKey(), null));
            for (Entry c : d.getValue().values()) {
                Collections.sort(c.pictures);
                demo.add(new DefaultMutableTreeNode(c));
            }
            parent.add(demo);
        }
    }

    private DefaultMutableTreeNode fileNode(Path p) {
        if (isRootFile(p)) {
            final DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Entry(Kind.ROOTFILE,
                p.getFileName().toString(), p));
            n.add(new DefaultMutableTreeNode("…"));
            return n;
        }
        return new DefaultMutableTreeNode(new Entry(isScene(p) ? Kind.SCENE : isPicture(p) ? Kind.PICTURE : Kind.FILE,
            p.getFileName().toString(), p));
    }

    /** Fills a node the first time it opens: a directory of the disk, a .root file's keys. */
    private void expand(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof Entry e) || e.loaded) return;
        e.loaded = true;
        node.removeAllChildren();
        try {
            if (e.kind == Kind.DIR && e.path != null) {
                try (Stream<Path> list = Files.list(e.path)) {
                    final List<Path> all = list.sorted().toList();
                    for (Path p : all) {
                        if (p.getFileName().toString().startsWith(".")) continue;
                        if (Files.isDirectory(p)) {
                            final DefaultMutableTreeNode d = new DefaultMutableTreeNode(new Entry(Kind.DIR,
                                p.getFileName().toString(), p));
                            d.add(new DefaultMutableTreeNode("…"));
                            node.add(d);
                        }
                    }
                    for (Path p : all) {
                        if (Files.isRegularFile(p) && (isPicture(p) || isScene(p) || isRootFile(p))) node.add(fileNode(p));
                    }
                }
            } else if (e.kind == Kind.ROOTFILE) {
                final RootFile f = rootFile(e.path);
                e.file = f;
                addRootNodes(node, f, f.tree());
            } else if (e.kind == Kind.ROOTDIR) {
                addRootNodes(node, e.file, e.node);
            }
        } catch (IOException | RuntimeException failed) {
            node.add(new DefaultMutableTreeNode("unreadable: " + failed.getMessage()));
        }
        model.nodeStructureChanged(node);
    }

    private void addRootNodes(DefaultMutableTreeNode parent, RootFile f, RootNode dir) {
        for (RootNode c : dir.children) {
            final Entry e = new Entry(c.directory ? Kind.ROOTDIR : Kind.ROOTOBJECT,
                c.name + (c.directory ? "" : "  ·  " + c.className), null);
            e.file = f;
            e.node = c;
            final DefaultMutableTreeNode n = new DefaultMutableTreeNode(e);
            if (c.directory) n.add(new DefaultMutableTreeNode("…"));
            parent.add(n);
        }
    }

    private RootFile rootFile(Path p) throws IOException {
        final Path key = p.toAbsolutePath().normalize();
        RootFile f = openFiles.get(key);
        if (f == null) {
            f = new RootFile(key);
            openFiles.put(key, f);
        }
        return f;
    }

    static boolean isPicture(Path p) {
        final String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif")
            || n.endsWith(".bmp") || n.endsWith(".svg") || n.endsWith(".tif") || n.endsWith(".tiff");
    }

    static boolean isScene(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sphere.json");
    }

    static boolean isRootFile(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".root");
    }

    /** Icons drawn, and pictures shown as their own thumbnails. */
    private final class Renderer extends DefaultTreeCellRenderer {
        Renderer() {
            setBorderSelectionColor(null);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            // The theme's colours, read at each paint so that a change of theme reaches the tree.
            setBackgroundNonSelectionColor(ImagingTheme.surface());
            setTextNonSelectionColor(ImagingTheme.text());
            setBackgroundSelectionColor(ImagingTheme.accent());
            setTextSelectionColor(readableOn(ImagingTheme.accent()));
            super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, focus);
            setFont(ImagingTheme.uiFont(Font.PLAIN, 12.5f));
            final Object o = ((DefaultMutableTreeNode) value).getUserObject();
            if (!(o instanceof Entry e)) {
                setIcon(null);
                return this;
            }
            final Color c = sel ? readableOn(ImagingTheme.accent()) : ImagingTheme.subduedText();
            switch (e.kind) {
                case FOLDER, DIR, ROOTDIR -> setIcon(glyph("folder", c));
                case DEMO -> {
                    setIcon(glyph("play", c));
                    setFont(ImagingTheme.uiFont(Font.BOLD, 12.5f));
                }
                case CANVAS -> {
                    final Path pic = e.pictures.stream().filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                        .findFirst().orElse(e.pictures.isEmpty() ? null : e.pictures.get(0));
                    final ImageIcon th = pic == null ? null : thumb(pic);
                    setIcon(th != null ? th : glyph("image", c));
                    setText(e.label + (e.scene != null ? "   ◆ live 3D" : "   (picture)"));
                }
                case PICTURE -> {
                    final ImageIcon th = thumb(e.path);
                    setIcon(th != null ? th : glyph("image", c));
                }
                case SCENE -> setIcon(glyph("cube", c));
                case ROOTFILE -> setIcon(glyph("file", c));
                case ROOTOBJECT -> {
                    setIcon(glyph(e.node.isHistogram() ? "chart" : "dot", c));
                    if (!e.node.isReadableHere()) setForeground(sel ? readableOn(ImagingTheme.accent()) : ImagingTheme.subduedText());
                }
                default -> setIcon(glyph("file", c));
            }
            return this;
        }
    }

    private static final Map<String, javax.swing.Icon> GLYPHS = new ConcurrentHashMap<>();

    /** The tree's small icons, drawn so they follow the theme at any scale. */
    static javax.swing.Icon glyph(String name, Color color) {
        return GLYPHS.computeIfAbsent(name + color.getRGB(), k -> {
            final int s = 16;
            final BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.setStroke(new java.awt.BasicStroke(1.3f));
            switch (name) {
                case "folder" -> {
                    g.drawRoundRect(1, 4, 13, 9, 2, 2);
                    g.drawLine(1, 4, 5, 2);
                    g.drawLine(5, 2, 8, 4);
                }
                case "play" -> {
                    final java.awt.geom.Path2D.Double t = new java.awt.geom.Path2D.Double();
                    t.moveTo(4, 2);
                    t.lineTo(13, 8);
                    t.lineTo(4, 14);
                    t.closePath();
                    g.fill(t);
                }
                case "cube" -> {
                    g.drawPolygon(new int[]{8, 14, 14, 8, 2, 2}, new int[]{1, 4, 11, 14, 11, 4}, 6);
                    g.drawLine(2, 4, 8, 7);
                    g.drawLine(14, 4, 8, 7);
                    g.drawLine(8, 7, 8, 14);
                }
                case "image" -> {
                    g.drawRect(1, 2, 13, 11);
                    g.drawPolyline(new int[]{2, 6, 9, 11, 14}, new int[]{12, 7, 10, 8, 11}, 5);
                    g.fillOval(10, 4, 3, 3);
                }
                case "chart" -> {
                    g.fillRect(2, 9, 3, 5);
                    g.fillRect(6, 4, 3, 10);
                    g.fillRect(10, 7, 3, 7);
                }
                case "dot" -> g.fillOval(5, 5, 6, 6);
                default -> {
                    g.drawRect(3, 1, 10, 14);
                    g.drawLine(5, 5, 11, 5);
                    g.drawLine(5, 8, 11, 8);
                    g.drawLine(5, 11, 9, 11);
                }
            }
            g.dispose();
            return new ImageIcon(img);
        });
    }

    /** A thumbnail of a picture, made once, off the event thread. */
    private ImageIcon thumb(Path p) {
        final ImageIcon have = thumbs.get(p);
        if (have != null) return have.getIconWidth() > 1 ? have : null;
        thumbs.put(p, new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)));
        new SwingWorker<ImageIcon, Void>() {
            @Override
            protected ImageIcon doInBackground() throws Exception {
                if (p.toString().toLowerCase(Locale.ROOT).endsWith(".svg")) return null;
                final BufferedImage img = ImageIO.read(p.toFile());
                if (img == null) return null;
                final int h = 30;
                final int w = Math.max(1, Math.min(48, img.getWidth() * h / Math.max(1, img.getHeight())));
                final BufferedImage t = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                final Graphics2D g = t.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(img.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null);
                g.dispose();
                return new ImageIcon(t);
            }

            @Override
            protected void done() {
                try {
                    final ImageIcon i = get();
                    if (i != null) {
                        thumbs.put(p, i);
                        tree.repaint();
                    }
                } catch (Exception ignored) {
                    // the plain icon stays
                }
            }
        }.execute();
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* Opening                                                             */
    /* ------------------------------------------------------------------ */

    void chooseFiles() {
        final JFileChooser chooser = new JFileChooser(com.sphere.core.fs.WorkingDirectory.get().toFile());
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        for (File f : chooser.getSelectedFiles()) openPath(f.toPath());
    }

    private void openSelected() {
        final Entry e = selectedEntry();
        if (e == null) return;
        switch (e.kind) {
            case CANVAS -> {
                if (e.scene != null) openScene(e.scene, firstPng(e.pictures));
                else if (!e.pictures.isEmpty()) openPicture(firstPng(e.pictures));
            }
            case SCENE, PICTURE, FILE -> openPath(e.path);
            case ROOTOBJECT -> openRootObject(e);
            default -> {
            }
        }
    }

    private static Path firstPng(List<Path> pictures) {
        for (Path p : pictures) if (p.toString().toLowerCase(Locale.ROOT).endsWith(".png")) return p;
        return pictures.isEmpty() ? null : pictures.get(0);
    }

    /** Opens a file by what it is. */
    void openPath(Path p) {
        if (p == null) return;
        if (isScene(p)) {
            final String name = p.getFileName().toString();
            final Path png = p.resolveSibling(name.substring(0, name.length() - ".sphere.json".length()) + ".png");
            openScene(p, Files.isRegularFile(png) ? png : null);
        } else if (isRootFile(p)) {
            try {
                rootFile(p);
                final DefaultMutableTreeNode n = fileNode(p.toAbsolutePath().normalize());
                files.add(n);
                model.nodeStructureChanged(files);
                final TreePath tp = new TreePath(n.getPath());
                tree.expandPath(new TreePath(files.getPath()));
                tree.expandPath(tp);
                tree.setSelectionPath(tp);
                tree.scrollPathToVisible(tp);
                status("opened " + p + " — double-click an object to draw it");
            } catch (IOException | RuntimeException e) {
                status("cannot read " + p + ": " + e.getMessage());
            }
        } else if (isPicture(p)) {
            final String n = p.getFileName().toString();
            final int dot = n.lastIndexOf('.');
            final Path scene = p.resolveSibling(n.substring(0, dot) + ".sphere.json");
            if (Files.isRegularFile(scene)) openScene(scene, p);
            else openPicture(p);
        } else {
            status("not something the browser draws: " + p.getFileName());
        }
    }

    private void openScene(Path scene, Path picture) {
        final String title = shortName(scene);
        if (select(title)) return;
        status("reading " + scene.getFileName() + " ...");
        new SwingWorker<RootScene, Void>() {
            @Override
            protected RootScene doInBackground() throws Exception {
                return RootScene.read(scene);
            }

            @Override
            protected void done() {
                try {
                    final RootScene s = get();
                    addTab(title, new SceneDoc(s, title, picture), scene.toString());
                    status(scene + (s.error != null ? "  — " + s.error : ""));
                } catch (Exception e) {
                    status("cannot read " + scene + ": " + e.getMessage());
                    if (picture != null) openPicture(picture);
                }
            }
        }.execute();
    }

    private void openPicture(Path p) {
        if (p == null) return;
        final String title = p.getFileName().toString();
        if (select(title)) return;
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                if (p.toString().toLowerCase(Locale.ROOT).endsWith(".svg")) {
                    final com.sphere.components.imaging.svg.SvgDocument svg =
                        com.sphere.components.imaging.svg.SvgDocument.load(p.toFile());
                    return new Object[]{svg.rasterize(1.0), svg};
                }
                return new Object[]{ImageIO.read(p.toFile()), null};
            }

            @Override
            protected void done() {
                try {
                    final Object[] r = get();
                    if (r[0] == null) {
                        status("not a picture Java can read: " + p);
                        return;
                    }
                    final com.sphere.components.imaging.svg.SvgDocument svg =
                        (com.sphere.components.imaging.svg.SvgDocument) r[1];
                    addTab(title, new PictureDoc((BufferedImage) r[0], svg == null ? null : svg::rasterize, title),
                        p.toString());
                } catch (Exception e) {
                    status("cannot read " + p + ": " + e.getMessage());
                }
            }
        }.execute();
    }

    private void openRootObject(Entry e) {
        final RootNode n = e.node;
        if (!n.isReadableHere() || n.isTree()) {
            status(n.className + " " + n.name + ": " + (n.isTree()
                ? "trees are drawn through the ROOT engine (:root draw) — the browser shows histograms and graphs"
                : "Sphere's reader does not decode this class"));
            return;
        }
        final String title = n.name;
        if (select(title)) return;
        new SwingWorker<Item, Void>() {
            @Override
            protected Item doInBackground() throws Exception {
                final byte[] payload = e.file.payload(n.key);
                if (n.isHistogram()) return Scenes.hist(RootHistogram.decode(payload, n.className));
                if (n.isGraph2D()) return Scenes.graph2d(RootGraph2D.decode(payload, n.className));
                return Scenes.graph(RootGraph.decode(payload, n.className));
            }

            @Override
            protected void done() {
                try {
                    final Item item = get();
                    addTab(title, new SceneDoc(Scenes.single(item, item.title), title, null),
                        n.className + " " + n.name);
                } catch (Exception ex) {
                    status("cannot decode " + n.name + ": " + (ex.getCause() == null ? ex.getMessage()
                        : ex.getCause().getMessage()));
                }
            }
        }.execute();
    }

    /** Opens what the analysis made, in a tab of its own. */
    void openMade(RootScene s, String title) {
        addTab(title, new SceneDoc(s, title, null), "made by the analysis");
    }

    private static String shortName(Path p) {
        String n = p.getFileName().toString();
        if (n.endsWith(".sphere.json")) n = n.substring(0, n.length() - ".sphere.json".length());
        if (n.startsWith("demo_")) n = n.substring(5);
        return n;
    }

    private boolean select(String title) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (title.equals(tabs.getTitleAt(i))) {
                tabs.setSelectedIndex(i);
                return true;
            }
        }
        return false;
    }

    private void addTab(String title, JComponent doc, String tip) {
        if (tabs.getTabCount() == 1 && "Welcome".equals(tabs.getTitleAt(0))) tabs.removeTabAt(0);
        tabs.addTab(title, null, doc, tip);
        final int i = tabs.getTabCount() - 1;
        tabs.setTabComponentAt(i, tabHeader(title, doc, true));
        tabs.setSelectedIndex(i);
        restyleTabs();
    }

    /**
     * A tab's header, opaque and painted here: the look and feel draws the tab
     * strip light while the theme's labels are light too, and a title drawn in
     * the theme's colour vanished into its own tab.
     */
    private JPanel tabHeader(String title, JComponent doc, boolean closable) {
        final JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        head.setOpaque(true);
        head.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, closable ? 2 : 6));
        final JLabel l = new JLabel(title);
        l.setFont(ImagingTheme.uiFont(Font.BOLD, 12f));
        head.add(l);
        if (closable) {
            final JButton x = new JButton("×");
            x.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 2));
            x.setContentAreaFilled(false);
            x.setOpaque(false);
            x.setFocusable(false);
            x.setToolTipText("Close " + title);
            x.addActionListener(e -> {
                final int k = tabs.indexOfComponent(doc);
                if (k >= 0) closeTab(k);
            });
            head.add(x);
        }
        return head;
    }

    /** The tab shown on the accent, the others on the surface, every title in the colour that reads on its ground. */
    private void restyleTabs() {
        final int selected = tabs.getSelectedIndex();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            final Color ground = i == selected ? ImagingTheme.accent() : ImagingTheme.surface();
            final Color ink = readableOn(ground);
            tabs.setBackgroundAt(i, ground);
            tabs.setForegroundAt(i, ink);
            if (tabs.getTabComponentAt(i) instanceof JPanel head) {
                head.setBackground(ground);
                for (Component c : head.getComponents()) c.setForeground(ink);
            }
        }
        tabs.repaint();
    }

    /** Everything whose colours were chosen here, chosen again from the current theme. */
    private void retheme() {
        if (tabs == null || filter == null) return;
        getContentPane().setBackground(ImagingTheme.panel());
        tree.setBackground(ImagingTheme.surface());
        readable(filter);
        restyleTabs();
        status.setForeground(ImagingTheme.subduedText());
        BrowserMenus.style(getJMenuBar());
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof SceneDoc d) BrowserMenus.style(d.menuBar);
        }
        repaint();
    }

    /** Near-black on a light ground, near-white on a dark one. */
    static Color readableOn(Color ground) {
        final double l = (0.2126 * ground.getRed() + 0.7152 * ground.getGreen() + 0.0722 * ground.getBlue()) / 255.0;
        return l > 0.55 ? new Color(0x16181C) : new Color(0xF2F4F8);
    }

    /** A text field whose text, caret and ground always contrast, whatever the theme gave it. */
    static void readable(JTextField f) {
        final Color ground = ImagingTheme.surface();
        final Color ink = readableOn(ground);
        f.setOpaque(true);
        f.setBackground(ground);
        f.setForeground(ink);
        f.setCaretColor(ink);
        f.setSelectionColor(ImagingTheme.accent());
        f.setSelectedTextColor(readableOn(ImagingTheme.accent()));
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ImagingTheme.border()),
            BorderFactory.createEmptyBorder(2, 4, 2, 4)));
    }

    private void closeTab(int i) {
        tabs.removeTabAt(i);
    }

    /** Every 1D histogram open in a tab, for comparisons. */
    private List<Hist> openHistograms() {
        final List<Hist> out = new ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof SceneDoc d) collect(d.scene.pad, out);
        }
        return out;
    }

    private static void collect(Pad pad, List<Hist> out) {
        for (Item i : pad.items) {
            if (i instanceof Hist h && !h.isFunction() && !out.contains(h)) out.add(h);
            if (i instanceof RootScene.Group g) {
                for (Item m : g.items) if (m instanceof Hist h && !h.isFunction() && !out.contains(h)) out.add(h);
            }
        }
        for (Pad p : pad.pads) collect(p, out);
    }

    /* ------------------------------------------------------------------ */
    /* The documents                                                       */
    /* ------------------------------------------------------------------ */

    /** A strip of view buttons over a card layout. */
    private abstract class Doc extends JPanel {
        final CardLayout cards = new CardLayout();
        final JPanel deck = ImagingTheme.panelOf(cards);
        final JPanel strip = ImagingTheme.panelOf(new FlowLayout(FlowLayout.LEFT, 4, 4));
        final javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        final Map<String, java.util.function.Supplier<JComponent>> lazy = new LinkedHashMap<>();
        final Map<String, JComponent> made = new LinkedHashMap<>();
        final Map<String, JToggleButton> buttons = new LinkedHashMap<>();

        Doc() {
            super(new BorderLayout());
            setBackground(ImagingTheme.panel());
            strip.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, ImagingTheme.border()));
            add(strip, BorderLayout.NORTH);
            add(deck, BorderLayout.CENTER);
        }

        void view(String name, String tip, java.util.function.Supplier<JComponent> make) {
            lazy.put(name, make);
            final JToggleButton b = new JToggleButton(name);
            ImagingTheme.styleButton(b);
            b.setToolTipText(tip);
            b.addActionListener(e -> show(name));
            group.add(b);
            strip.add(b);
            buttons.put(name, b);
        }

        void show(String name) {
            JComponent c = made.get(name);
            if (c == null) {
                c = lazy.get(name).get();
                made.put(name, c);
                deck.add(c, name);
            }
            buttons.get(name).setSelected(true);
            cards.show(deck, name);
            c.requestFocusInWindow();
        }

        /** Forgets a view so that it is made again from the current state. */
        void forget(String name) {
            final JComponent c = made.remove(name);
            if (c != null) deck.remove(c);
        }
    }

    /** A canvas: live, in 3D, analysed, as its picture, as its bins. */
    private final class SceneDoc extends Doc implements BrowserMenus.CanvasDoc {
        RootScene scene;
        final RootCanvasView canvas = new RootCanvasView();
        final String title;
        javax.swing.JMenuBar menuBar;
        Pad spacePad;

        SceneDoc(RootScene scene, String title, Path picture) {
            this.scene = scene;
            this.title = title;
            canvas.setScene(scene);
            canvas.setStatusListener(SphereBrowser.this::status);
            canvas.setHost(host);
            canvas.setChangeListener(this::changed);
            final JPanel top = ImagingTheme.panelOf(new BorderLayout());
            remove(strip);
            menuBar = BrowserMenus.canvasBar(SphereBrowser.this, this);
            top.add(menuBar, BorderLayout.NORTH);
            top.add(strip, BorderLayout.CENTER);
            add(top, BorderLayout.NORTH);
            // In a dark theme the pads are drawn on the theme's paper; one click shows ROOT's white.
            canvas.setThemePaper(true);
            final List<Pad> drawn = new ArrayList<>();
            for (Pad p : scene.pad.flatten()) if (p.drawsSomething() || p.holdsSpace()) drawn.add(p);
            spacePad = drawn.isEmpty() ? scene.pad : drawn.get(0);
            final boolean space = drawn.stream().anyMatch(Pad::holdsSpace);

            view("Canvas", "The canvas from its numbers, as ROOT draws it, live to the mouse", () -> canvas);
            view("3D Space", "Sphere's 3D engine: turn, zoom, stretch X Y Z, cut, iso-surfaces, stereo", this::space);
            view("Analysis", "Fits, ranking of models, peaks, Bayesian Blocks, comparisons", () -> new AnalysisPanel(
                analysed(), canvas::repaint, SphereBrowser.this::openMade, SphereBrowser.this::openHistograms));
            if (picture != null) {
                view("Picture", "The picture ROOT wrote: " + picture.getFileName(), () -> picture(picture));
            }
            view("Data", "The bins and points themselves", this::data);
            if (drawn.size() > 1) {
                final javax.swing.JComboBox<String> pads = new javax.swing.JComboBox<>(
                    drawn.stream().map(p -> p.name + (p.main() == null ? "" : " · " + p.main().name)).toArray(String[]::new));
                pads.setToolTipText("The pad the 3D Space and the Analysis look at");
                pads.addActionListener(e -> {
                    spacePad = drawn.get(pads.getSelectedIndex());
                    forget("3D Space");
                    forget("Analysis");
                    forget("Data");
                    final String shown = buttons.entrySet().stream().filter(b -> b.getValue().isSelected())
                        .map(Map.Entry::getKey).findFirst().orElse("Canvas");
                    show(shown);
                });
                final JLabel l = new JLabel("   pad");
                l.setForeground(ImagingTheme.subduedText());
                strip.add(l);
                strip.add(pads);
            }
            final JToggleButton white = new JToggleButton("White paper");
            ImagingTheme.styleButton(white);
            white.setToolTipText("The canvas on ROOT's white paper instead of the theme's colours");
            white.addActionListener(e -> canvas.setThemePaper(!white.isSelected()));
            white.setVisible(ImagingTheme.isDark());
            strip.add(javax.swing.Box.createHorizontalStrut(12));
            strip.add(white);
            show(space ? "3D Space" : "Canvas");
        }

        private Hist analysed() {
            final Hist h = Scenes.firstHist(spacePad);
            return h != null ? h : Scenes.firstHist(scene.pad);
        }

        /** After a function of ROOT's menus, an undo or ROOT's engine changed the canvas: the other views follow. */
        private void changed() {
            final RootScene now = canvas.getScene();
            if (now != scene) {
                final int k = scene.pad.flatten().indexOf(spacePad);
                scene = now;
                final List<Pad> pads = now.pad.flatten();
                spacePad = k >= 0 && k < pads.size() ? pads.get(k) : now.pad;
            }
            final String shown = buttons.entrySet().stream().filter(b -> b.getValue().isSelected())
                .map(Map.Entry::getKey).findFirst().orElse("Canvas");
            for (String v : new String[]{"3D Space", "Analysis", "Data"}) if (!v.equals(shown)) forget(v);
        }

        @Override
        public RootCanvasView canvas() {
            return canvas;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public void close() {
            final int k = tabs.indexOfComponent(this);
            if (k >= 0) closeTab(k);
        }

        @Override
        public void showView(String name) {
            if (lazy.containsKey(name)) show(name);
        }

        private JComponent space() {
            final Pad pad = spacePad;
            final double theta = pad.viewLat == pad.viewLat ? pad.viewLat : pad.theta;
            final double phi = pad.viewLon == pad.viewLon ? pad.viewLon : pad.phi;
            final SceneBuilder3D.Options o = new SceneBuilder3D.Options();
            final Item main = pad.main();
            if (main instanceof Hist h && h.dim == 2 && h.opt().contains("LEGO")) o.style = SceneBuilder3D.Style.LEGO;
            return new Space3DPanel(opt -> SceneBuilder3D.pad(pad, scene, opt), o, theta, phi, SphereBrowser.this::status);
        }

        private JComponent picture(Path p) {
            try {
                final BufferedImage img = ImageIO.read(p.toFile());
                if (img == null) return new JLabel("unreadable picture");
                return new PictureView(img, null, SphereBrowser.this::status);
            } catch (IOException e) {
                return new JLabel("cannot read " + p + ": " + e.getMessage());
            }
        }

        private JComponent data() {
            final Item main = spacePad.main() != null ? spacePad.main() : analysed();
            final JTable table = new JTable(new ItemTable(main));
            table.setAutoCreateRowSorter(true);
            table.setFillsViewportHeight(true);
            return new JScrollPane(table);
        }
    }

    /** What a function printed (Dump, Print, Map, a fit's result), as ROOT prints it in its terminal. */
    private final class TextDoc extends JPanel {
        TextDoc(String title, String text) {
            super(new BorderLayout());
            final javax.swing.JTextArea area = new javax.swing.JTextArea(text);
            area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            area.setEditable(false);
            area.setBackground(ImagingTheme.surface());
            area.setForeground(readableOn(ImagingTheme.surface()));
            area.setCaretPosition(0);
            final JPanel bar = ImagingTheme.panelOf(new FlowLayout(FlowLayout.LEFT, 4, 4));
            bar.add(button("Copy", "Copy the text", () -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text), null)));
            bar.add(button("Save…", "Save the text in a file", () -> {
                final JFileChooser chooser = new JFileChooser(com.sphere.core.fs.WorkingDirectory.get().toFile());
                chooser.setSelectedFile(new File(title.replaceAll("[^\\w.-]", "_") + ".txt"));
                if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    try {
                        Files.writeString(chooser.getSelectedFile().toPath(), text);
                    } catch (IOException e) {
                        status("not saved: " + e.getMessage());
                    }
                }
            }));
            add(bar, BorderLayout.NORTH);
            add(new JScrollPane(area), BorderLayout.CENTER);
        }
    }

    /** A picture: zoom, turn, loupe; and the same picture as a relief in 3D. */
    private final class PictureDoc extends Doc {
        PictureDoc(BufferedImage img, java.util.function.DoubleFunction<BufferedImage> vector, String title) {
            view("Picture", "Zoom, turn, mirror, loupe, pixel probe", () -> new PictureView(img, vector,
                SphereBrowser.this::status));
            view("Relief 3D", "The picture as a landscape of its ink: turn it, stretch its height", () -> {
                final SceneBuilder3D.Options o = new SceneBuilder3D.Options();
                return new Space3DPanel(opt -> SceneBuilder3D.image(img, title, opt), o, 55, -20,
                    SphereBrowser.this::status);
            });
            show("Picture");
        }
    }

    /** The bins of a histogram or the points of a graph, as a table. */
    private static final class ItemTable extends AbstractTableModel {
        private final String[] columns;
        private final List<Object[]> rows = new ArrayList<>();

        ItemTable(Item item) {
            if (item instanceof Hist h && h.dim == 1) {
                columns = new String[]{"bin", "low edge", "centre", "content", "error"};
                for (int i = 0; i < h.nx(); i++) {
                    rows.add(new Object[]{i + 1, h.x.edge(i), h.x.center(i), h.at(i, 0), h.error(i)});
                }
            } else if (item instanceof Hist h && h.dim == 2) {
                columns = new String[]{"bin x", "bin y", "x", "y", "content"};
                for (int j = 0; j < h.ny(); j++) {
                    for (int i = 0; i < h.nx(); i++) {
                        final double v = h.at(i, j);
                        if (v != 0) rows.add(new Object[]{i + 1, j + 1, h.x.center(i), h.y.center(j), v});
                    }
                }
            } else if (item instanceof Hist h && h.dim == 3) {
                columns = new String[]{"x", "y", "z", "content"};
                for (int k = 0; k < h.nz(); k++) {
                    for (int j = 0; j < h.ny(); j++) {
                        for (int i = 0; i < h.nx(); i++) {
                            final double v = h.at(i, j, k);
                            if (v != 0 && rows.size() < 200000) {
                                rows.add(new Object[]{h.x.center(i), h.y.center(j), h.z.center(k), v});
                            }
                        }
                    }
                }
            } else if (item instanceof RootScene.Graph g) {
                columns = new String[]{"point", "x", "y", "ex", "ey"};
                for (int i = 0; i < g.x.length; i++) {
                    rows.add(new Object[]{i, g.x[i], g.y[i], g.exh == null ? 0.0 : g.exh[i], g.eyh == null ? 0.0 : g.eyh[i]});
                }
            } else if (item instanceof RootScene.Graph2D g) {
                columns = new String[]{"point", "x", "y", "z"};
                for (int i = 0; i < g.x.length; i++) rows.add(new Object[]{i, g.x[i], g.y[i], g.z[i]});
            } else {
                columns = new String[]{"nothing tabular here"};
            }
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return rows.isEmpty() ? Object.class : rows.get(0)[c].getClass();
        }

        @Override
        public Object getValueAt(int r, int c) {
            return rows.get(r)[c];
        }
    }
}

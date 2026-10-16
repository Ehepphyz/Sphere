package com.sphere.components.rootlab;

import com.sphere.components.imaging.ImagingTheme;
import com.sphere.components.rootview.RootFile;
import com.sphere.components.rootview.RootKey;
import com.sphere.components.rootview.RootNode;
import com.sphere.components.rootview.RootPlotsPanel;
import com.sphere.core.rootio.RObject;
import com.sphere.core.rootio.RTree;
import com.sphere.core.rootio.RootIO;
import com.sphere.core.rootio.RootLocation;
import com.sphere.core.rootlab.ColumnStats;
import com.sphere.core.rootlab.DataFormats;
import com.sphere.core.rootlab.FileCompare;
import com.sphere.core.rootlab.RootFsck;
import com.sphere.core.rootlab.hist.LabHist;
import com.sphere.utils.AppLogger;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Data side of the ROOT Lab: CERN data files opened -- from disk, from a
 * URL (CERN Open Data, root.cern), from inside a zip archive -- shown as what
 * they hold, with an inspector that reads, checks and summarises the selected
 * thing in the background, so the window never waits on the disk or the network.
 */
final class LabDataView extends LabSurface {

    private static final String LOADING = "Loading...";
    private static final long AUTO_STATS_ENTRIES = 200_000;

    // ---- what the tree holds

    /** An opened file, of whatever format. */
    static final class Source {
        final String location;
        final String name;
        final boolean remote;
        Path local;
        RootIO io;
        DataFormats.Sniff sniff;
        String error;
        RootFsck.Report report;
        /** What the compressed records really use, read off the first one. */
        String blockAlgorithm;

        Source(String location, String name, boolean remote) {
            this.location = location;
            this.name = name;
            this.remote = remote;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A key (an object or a directory) of a ROOT file. */
    record Item(Source src, RootNode node) {
        @Override
        public String toString() {
            return node.name + (node.key != null && node.key.cycle > 1 ? ";" + node.key.cycle : "");
        }
    }

    /** A branch of a tree. */
    record Branch(Source src, RTree tree, RTree.RBranch branch) {
        @Override
        public String toString() {
            return branch.name();
        }
    }

    private final DefaultMutableTreeNode top = new DefaultMutableTreeNode("files");
    private final DefaultTreeModel model = new DefaultTreeModel(top);
    private final JTree tree = new JTree(model);
    private final MiniPlot mini = new MiniPlot();
    private final JEditorPane details = LabViews.htmlPane();
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JButton stopButton = ImagingTheme.textButton("Stop", null);
    private final AtomicInteger counter = new AtomicInteger();
    private final ExecutorService worker = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "sphere-rootlab-data-" + counter.incrementAndGet());
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean cancel = new AtomicBoolean();
    /** What the inspector shows, so a late answer for another selection is dropped. */
    private volatile Object showing;

    LabDataView() {
        super(new BorderLayout(), true);
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildStatus(), BorderLayout.SOUTH);
        welcome();
        installDrop();
    }

    // ---- layout ------------------------------------------------------------------

    private JComponent buildToolbar() {
        final ImagingTheme.Surface bar = ImagingTheme.strip(true);
        bar.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        bar.add(button("Open", "Open data files (ROOT, HepMC, LHE, YODA, SLHA, CSV...) or a folder of them",
                       this::chooseFiles));
        bar.add(button("URL", "Open a ROOT file over HTTPS without downloading it (CERN Open Data, root.cern)",
                       this::chooseUrl));
        bar.add(button("Close", "Close the selected file", this::closeSelected));
        bar.add(Box.createHorizontalGlue());
        ImagingTheme.framed(bar);
        return bar;
    }

    private static JButton button(String text, String tip, Runnable action) {
        final JButton b = ImagingTheme.textButton(text, tip);
        b.addActionListener(e -> action.run());
        return b;
    }

    private JComponent buildBody() {
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setBackground(ImagingTheme.surface());
        tree.setCellRenderer(new Renderer());
        tree.setRowHeight(20);
        tree.addTreeSelectionListener(e -> {
            final TreePath p = e.getNewLeadSelectionPath();
            if (p != null) {
                inspect((DefaultMutableTreeNode) p.getLastPathComponent());
            }
        });
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                expanding((DefaultMutableTreeNode) event.getPath().getLastPathComponent());
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    final TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                    if (p != null) {
                        doubleClicked((DefaultMutableTreeNode) p.getLastPathComponent());
                    }
                }
            }
        });
        final JScrollPane treeScroll = new JScrollPane(tree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        treeScroll.getViewport().setBackground(ImagingTheme.surface());

        final JPanel inspector = new ImagingTheme.Surface(new BorderLayout(), true);
        final JPanel north = new ImagingTheme.Surface(new BorderLayout(), true);
        mini.setVisible(false);
        mini.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        north.add(mini, BorderLayout.CENTER);
        actions.setOpaque(false);
        north.add(actions, BorderLayout.SOUTH);
        inspector.add(north, BorderLayout.NORTH);
        final JScrollPane detailScroll = new JScrollPane(details);
        detailScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, ImagingTheme.border()));
        inspector.add(detailScroll, BorderLayout.CENTER);

        final JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, inspector);
        split.setResizeWeight(0.42);
        split.setBorder(null);
        split.setDividerSize(5);
        split.setContinuousLayout(true);
        return split;
    }

    private JComponent buildStatus() {
        final JPanel bar = new ImagingTheme.Surface(new BorderLayout(4, 0), true);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        status.setFont(ImagingTheme.uiFont(Font.PLAIN, 10f));
        status.setForeground(ImagingTheme.subduedText());
        progress.setPreferredSize(new Dimension(70, 8));
        progress.setVisible(false);
        // A deep check of a large file, or statistics over millions of entries,
        // can take a while: they look at this flag between records and entries.
        stopButton.setFont(ImagingTheme.uiFont(Font.PLAIN, 10f));
        stopButton.setToolTipText("Stop the check or the reading in progress");
        stopButton.addActionListener(e -> cancel.set(true));
        stopButton.setVisible(false);
        final JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        east.setOpaque(false);
        east.add(progress);
        east.add(stopButton);
        bar.add(status, BorderLayout.CENTER);
        bar.add(east, BorderLayout.EAST);
        return bar;
    }

    private void welcome() {
        showing = null;
        mini.show(null, null);
        actions.removeAll();
        actions.add(small("Open...", this::chooseFiles));
        actions.add(small("Try a CERN Open Data file", () -> openLocation(OPEN_DATA_EXAMPLE)));
        actions.revalidate();
        details.setText("<html><body><h1>ROOT Lab: CERN data files</h1>"
            + "<p><b>Open/drop</b> files here: <b>ROOT</b> files, "
            + "zlib, LZMA, LZ4, Zstandard, HepMC, Les Houches, "
            + "YODA, SLHA, LHAPDF, HEPData, CSV files, plain or gzip/xz compressed.</p>"
            + "<p><b>URL:</b> reads a remote ROOT file by HTTP ranges</p></body></html>");
        details.setCaretPosition(0);
    }

    private static final String OPEN_DATA_EXAMPLE =
        "root://eospublic.cern.ch//eos/opendata/cms/derived-data/AOD2NanoAODOutreachTool/Run2012BC_DoubleMuParked_Muons.root";

    private JButton small(String text, Runnable action) {
        final JButton b = ImagingTheme.textButton(text, null);
        b.setFont(ImagingTheme.uiFont(Font.PLAIN, 10f));
        b.addActionListener(e -> action.run());
        return b;
    }

    // ---- opening ---------------------------------------------------------------

    private void chooseFiles() {
        final JFileChooser chooser = new JFileChooser(com.sphere.core.fs.WorkingDirectory.get().toFile());
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("Open CERN data files");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : chooser.getSelectedFiles()) {
                openPath(f.toPath());
            }
        }
    }

    private void chooseUrl() {
        final Object answer = JOptionPane.showInputDialog(this,
            "A ROOT file to read remotely (https://..., root://eospublic.cern.ch//eos/opendata/...),\n"
            + "optionally with /tree, ?query, #member as ROOT accepts them:",
            "Open a remote ROOT file", JOptionPane.QUESTION_MESSAGE, null, null, OPEN_DATA_EXAMPLE);
        if (answer != null && !answer.toString().isBlank()) {
            openLocation(answer.toString().strip());
        }
    }

    /** Opens a file or every data file of a folder (one level deep). */
    void openPath(Path path) {
        if (Files.isDirectory(path)) {
            try (var list = Files.list(path)) {
                list.filter(Files::isRegularFile).sorted().limit(500).forEach(this::openPath);
            } catch (IOException unreadable) {
                setStatus("Cannot list " + path + ": " + unreadable.getMessage());
            }
            return;
        }
        final Source s = new Source(path.toAbsolutePath().toString(), path.getFileName().toString(), false);
        s.local = path;
        add(s);
    }

    /** Opens a location as TFile::Open takes it (URL, archive member, local path). */
    void openLocation(String spec) {
        final RootLocation loc = RootLocation.parse(spec);
        final Source s = new Source(spec, loc.displayName(), loc.remote);
        if (!loc.remote && !loc.isZip()) {
            s.local = Path.of(loc.file);
        }
        add(s);
    }

    private void add(Source s) {
        for (int i = 0; i < top.getChildCount(); i++) {
            final Object u = ((DefaultMutableTreeNode) top.getChildAt(i)).getUserObject();
            if (u instanceof Source existing && existing.location.equals(s.location)) {
                select((DefaultMutableTreeNode) top.getChildAt(i));
                return;
            }
        }
        final DefaultMutableTreeNode node = new DefaultMutableTreeNode(s);
        node.add(new DefaultMutableTreeNode(LOADING));
        model.insertNodeInto(node, top, top.getChildCount());
        select(node);
        busy("Opening " + s.name + "...");
        worker.execute(() -> load(s, node));
    }

    /** Off the event thread: what the file is, and for ROOT its directories. */
    private void load(Source s, DefaultMutableTreeNode node) {
        try {
            if (s.local != null) {
                s.sniff = DataFormats.sniff(s.local);
            }
            final boolean root = s.local == null || (s.sniff != null && s.sniff.kind() == DataFormats.Kind.ROOT
                                                     && s.sniff.compression() == null);
            if (root) {
                s.io = s.local != null ? RootIO.open(s.local) : RootIO.open(s.location);
                if (s.sniff == null) {
                    s.sniff = new DataFormats.Sniff(DataFormats.Kind.ROOT, null, "read by HTTP ranges");
                }
                final RootNode tree = s.io.file().tree();
                try {
                    s.blockAlgorithm = s.io.file().firstBlockAlgorithm();
                } catch (IOException unreadable) {
                    s.blockAlgorithm = null;
                }
                SwingUtilities.invokeLater(() -> {
                    node.removeAllChildren();
                    addChildren(node, s, tree);
                    model.nodeStructureChanged(node);
                    this.tree.expandPath(new TreePath(node.getPath()));
                    done(s.name + ": " + tree.countObjects() + " objects");
                    refreshIfShowing(node);
                });
            } else {
                SwingUtilities.invokeLater(() -> {
                    node.removeAllChildren();
                    model.nodeStructureChanged(node);
                    done(s.name + ": " + s.sniff.describe());
                    refreshIfShowing(node);
                });
            }
        } catch (IOException | RuntimeException failed) {
            s.error = failed.getMessage() == null ? failed.toString() : failed.getMessage();
            SwingUtilities.invokeLater(() -> {
                node.removeAllChildren();
                model.nodeStructureChanged(node);
                done("Could not open " + s.name + ": " + s.error);
                refreshIfShowing(node);
            });
        }
    }

    private void addChildren(DefaultMutableTreeNode parent, Source s, RootNode dir) {
        for (RootNode n : dir.children) {
            final DefaultMutableTreeNode child = new DefaultMutableTreeNode(new Item(s, n));
            if (n.directory) {
                addChildren(child, s, n);
            } else if (n.isTree()) {
                child.add(new DefaultMutableTreeNode(LOADING));
            }
            parent.add(child);
        }
    }

    /** A tree's branches are read when it is opened, not with the file. */
    private void expanding(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof Item item) || !item.node().isTree()) {
            return;
        }
        if (node.getChildCount() != 1 || !LOADING.equals(((DefaultMutableTreeNode) node.getChildAt(0)).getUserObject())) {
            return;
        }
        busy("Reading the branches of " + item.node().name + "...");
        worker.execute(() -> {
            try {
                final RTree t = treeOf(item);
                SwingUtilities.invokeLater(() -> {
                    node.removeAllChildren();
                    for (RTree.RBranch b : t.branches()) {
                        node.add(branchNode(item.src(), t, b));
                    }
                    model.nodeStructureChanged(node);
                    tree.expandPath(new TreePath(node.getPath()));
                    done(item.node().name + ": " + t.allBranches().size() + " branches");
                });
            } catch (IOException | RuntimeException failed) {
                SwingUtilities.invokeLater(() -> {
                    node.removeAllChildren();
                    model.nodeStructureChanged(node);
                    done("Could not read the tree: " + failed.getMessage());
                });
            }
        });
    }

    private DefaultMutableTreeNode branchNode(Source s, RTree t, RTree.RBranch b) {
        final DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Branch(s, t, b));
        for (RTree.RBranch c : b.children()) {
            n.add(branchNode(s, t, c));
        }
        return n;
    }

    private static RTree treeOf(Item item) throws IOException {
        return item.src().io.tree(item.node().name + ";" + item.node().key.cycle);
    }

    private void closeSelected() {
        final TreePath p = tree.getSelectionPath();
        if (p == null || p.getPathCount() < 2) {
            return;
        }
        final DefaultMutableTreeNode node = (DefaultMutableTreeNode) p.getPathComponent(1);
        if (node.getUserObject() instanceof Source s && s.io != null) {
            try {
                s.io.close();
            } catch (IOException ignored) {
                // closing a remote file only drops its cache
            }
        }
        model.removeNodeFromParent(node);
        welcome();
    }

    // ---- drag and drop ---------------------------------------------------------

    private void installDrop() {
        final TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                    || support.isDataFlavorSupported(DataFlavor.stringFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                try {
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        final List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                        for (File f : files) {
                            openPath(f.toPath());
                        }
                        return true;
                    }
                    final String text = ((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor)).strip();
                    if (text.startsWith("http") || text.startsWith("root://") || text.startsWith("file:")) {
                        openLocation(text);
                        return true;
                    }
                    if (Files.exists(Path.of(text))) {
                        openPath(Path.of(text));
                        return true;
                    }
                } catch (Exception refused) {
                    setStatus("Could not open what was dropped: " + refused.getMessage());
                }
                return false;
            }
        };
        tree.setTransferHandler(handler);
        details.setTransferHandler(handler);
        setTransferHandler(handler);
    }

    // ---- the inspector ------------------------------------------------------------

    private void select(DefaultMutableTreeNode node) {
        final TreePath p = new TreePath(node.getPath());
        tree.setSelectionPath(p);
        tree.scrollPathToVisible(p);
    }

    private void refreshIfShowing(DefaultMutableTreeNode node) {
        final TreePath p = tree.getSelectionPath();
        if (p != null && p.getLastPathComponent() == node) {
            inspect(node);
        }
    }

    private void doubleClicked(DefaultMutableTreeNode node) {
        final Object u = node.getUserObject();
        if (u instanceof Item item && item.node().isHistogram()) {
            plotItem(item);
        } else if (u instanceof Branch b) {
            stats(b, true);
        } else if (u instanceof Source s && s.local != null && s.io == null && s.error == null) {
            openInEditor(s);
        }
    }

    private void inspect(DefaultMutableTreeNode node) {
        final Object u = node.getUserObject();
        showing = u;
        mini.show(null, null);
        actions.removeAll();
        if (u instanceof Source s) {
            inspectSource(s);
        } else if (u instanceof Item item) {
            inspectItem(item);
        } else if (u instanceof Branch b) {
            inspectBranch(b);
        } else {
            details.setText("<html><body class=sub>" + LabViews.esc(String.valueOf(u)) + "</body></html>");
        }
        actions.revalidate();
        actions.repaint();
    }

    private void inspectSource(Source s) {
        final StringBuilder h = new StringBuilder("<html><body><h1>").append(LabViews.esc(s.name)).append("</h1>");
        if (s.error != null) {
            h.append("<p class=bad>").append(LabViews.esc(s.error)).append("</p>");
        }
        if (s.io == null && s.sniff == null && s.error == null) {
            h.append("<p class=sub>opening...</p>");
        }
        if (s.sniff != null) {
            h.append("<p>").append(LabViews.esc(s.sniff.describe())).append("</p>");
        }
        if (s.io != null) {
            final RootFile f = s.io.file();
            h.append(LabViews.table(
                "where", LabViews.esc(f.getLocation()),
                "size", LabViews.bytes(f.getSize()) + (s.remote ? " <span class=sub>(remote: read by ranges)</span>" : ""),
                "format", "ROOT " + f.getFormatVersion() + (f.isBigFile() ? ", 64-bit pointers" : ""),
                "compression", compressionText(f, s.blockAlgorithm),
                "objects", Integer.toString(safeCount(f))));
            actions.add(small("Verify", () -> verify(s, !s.remote)));
            if (s.remote) {
                actions.add(small("Deep verify", () -> verify(s, true)));
            }
            actions.add(small("Compare with...", () -> compare(s)));
            if (s.local != null) {
                actions.add(small("Open in viewer", () -> com.sphere.ui.RootViewerFrame.show(s.local.toFile())));
            }
            if (s.report != null) {
                h.append(reportHtml(s.report));
            }
        } else if (s.local != null && s.sniff != null && s.error == null) {
            h.append(LabViews.table("where", LabViews.esc(s.local.toString()),
                "size", LabViews.bytes(sizeOf(s.local))));
            if (s.sniff.kind() != DataFormats.Kind.BINARY && s.sniff.kind() != DataFormats.Kind.ROOT) {
                actions.add(small("Open in editor", () -> openInEditor(s)));
            }
            if (s.sniff.kind() == DataFormats.Kind.HEPMC3 || s.sniff.kind() == DataFormats.Kind.HEPMC2
                || s.sniff.kind() == DataFormats.Kind.LHEF) {
                actions.add(small("Count events", () -> countEvents(s)));
            }
        }
        details.setText(h.append("</body></html>").toString());
        details.setCaretPosition(0);
    }

    /** fCompress, and when it names no algorithm (0: the writer's default), the one the records use. */
    private static String compressionText(RootFile f, String used) {
        if (f.getCompressionLevel() == 0 && used == null) {
            return "none";
        }
        final String declared = f.getCompressionAlgorithm() == 0 ? null
            : RootFile.compressionName(f.getCompressionAlgorithm());
        final String algo = declared != null ? declared : used != null ? used : "the writing ROOT's default";
        return LabViews.esc(algo) + ", level " + f.getCompressionLevel()
            + (declared == null && used != null ? " <span class=sub>(the writing ROOT's default)</span>" : "");
    }

    private static int safeCount(RootFile f) {
        try {
            return f.tree().countObjects();
        } catch (IOException unreadable) {
            return 0;
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException gone) {
            return 0;
        }
    }

    private void inspectItem(Item item) {
        final RootNode n = item.node();
        final RootKey k = n.key;
        final StringBuilder h = new StringBuilder("<html><body><h1>").append(LabViews.esc(n.name))
            .append("</h1><p class=sub>").append(LabViews.esc(n.className)).append(n.title.isEmpty() ? "" : " &mdash; "
                + LabViews.esc(n.title)).append("</p>");
        if (k != null) {
            final var written = k.written();
            h.append(LabViews.table(
                "cycle", Integer.toString(k.cycle),
                "written", written == null ? "?" : written.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                "on disk", LabViews.bytes(k.storedBytes()) + (k.compressed()
                    ? String.format(Locale.ROOT, " (%s, ratio %.2f)", LabViews.bytes(k.objlen), k.compressionRatio()) : ""),
                "at byte", String.format(Locale.ROOT, "%,d", k.offset)));
        }
        details.setText(h.append("<p class=sub>reading...</p></body></html>").toString());
        if (n.directory) {
            details.setText(h.toString().replace("<p class=sub>reading...</p>", "")
                + "<p>" + n.countObjects() + " objects</p></body></html>");
            return;
        }
        final String head = h.toString();
        if (n.isHistogram()) {
            actions.add(small("Plot", () -> plotItem(item)));
            actions.add(small("Export CSV", () -> exportCsv(item)));
        }
        worker.execute(() -> {
            String body;
            LabHist hist = null;
            try {
                if (n.isHistogram()) {
                    hist = FileCompare.readHistogram(item.src().io, n);
                    body = histogramHtml(hist);
                } else if (n.isTree()) {
                    body = treeHtml(treeOf(item));
                } else {
                    final Object o = item.src().io.read(k);
                    body = objectHtml(o);
                }
            } catch (Exception failed) {
                body = "<p class=bad>" + LabViews.esc(failed.getClass().getSimpleName() + ": " + failed.getMessage()) + "</p>";
            }
            final String text = head.replace("<p class=sub>reading...</p>", "") + body + "</body></html>";
            final LabHist shown = hist;
            SwingUtilities.invokeLater(() -> {
                if (showing != item) {
                    return;
                }
                details.setText(text);
                details.setCaretPosition(0);
                if (shown != null) {
                    mini.show(LabViews.projectionX(shown), shown.dimension > 1 ? "projection on x" : "");
                }
            });
        });
    }

    private static String histogramHtml(LabHist h) {
        final StringBuilder b = new StringBuilder("<h2>Contents</h2>");
        final String axes = h.xaxis.nbins + (h.dimension > 1 ? " x " + h.yaxis.nbins : "")
            + (h.dimension > 2 ? " x " + h.zaxis.nbins : "") + " bins";
        b.append(LabViews.table(
            "binning", axes + String.format(Locale.ROOT, ", x in [%.6g, %.6g]", h.xaxis.xmin, h.xaxis.xmax),
            "entries", LabViews.num(h.entries),
            "sum of weights", LabViews.num(h.sumOfWeights()) + " <span class=sub>(in range)</span>",
            "effective entries", LabViews.num(h.effectiveEntries()),
            "mean x", LabViews.num(h.mean(1)),
            "std dev x", LabViews.num(h.stdDev(1)),
            h.dimension > 1 ? "mean y" : "", h.dimension > 1 ? LabViews.num(h.mean(2)) : "",
            "underflow / overflow", LabViews.num(h.content(0)) + " / " + LabViews.num(h.content(h.dimension == 1 ? h.xaxis.nbins + 1 : 0))
                + (h.dimension > 1 ? " <span class=sub>(along x, first row)</span>" : ""),
            "errors", h.isProfile() ? "profile (error of the mean)" : h.sumw2.length > 0 ? "sum of w&sup2; kept" : "&radic;content"));
        return b.toString();
    }

    private static String treeHtml(RTree t) {
        final StringBuilder b = new StringBuilder("<h2>Tree</h2>");
        final RObject o = t.object();
        b.append(LabViews.table(
            "entries", LabViews.num(t.entries()),
            "branches", t.allBranches().size() + " (" + t.branches().size() + " at the top)",
            "data", LabViews.bytes(o.number("fTotBytes")) + " uncompressed, " + LabViews.bytes(o.number("fZipBytes"))
                + " on disk" + (o.number("fZipBytes") > 0 ? String.format(Locale.ROOT, " (ratio %.2f)",
                (double) o.number("fTotBytes") / o.number("fZipBytes")) : "")));
        b.append("<h2>Branches</h2><table cellspacing=0 cellpadding=0>");
        int shown = 0;
        for (RTree.RBranch br : t.allBranches()) {
            if (++shown > 60) {
                b.append("<tr><td class=k>...</td><td>").append(t.allBranches().size() - 60).append(" more</td></tr>");
                break;
            }
            b.append("<tr><td>").append(LabViews.esc(br.name())).append("</td><td class=k>")
             .append(LabViews.esc(br.typeName())).append("</td></tr>");
        }
        b.append("</table><p class=sub>Open the tree and select a branch for its statistics.</p>");
        return b.toString();
    }

    private static String objectHtml(Object o) {
        if (o instanceof RObject r) {
            if (r.has("@unread")) {
                return "<p class=warn>" + LabViews.esc(String.valueOf(r.get("@unread"))) + "</p>";
            }
            final StringBuilder b = new StringBuilder("<h2>Members</h2><table cellspacing=0 cellpadding=0>");
            int shown = 0;
            for (var e : r.members.entrySet()) {
                if (++shown > 40) {
                    b.append("<tr><td class=k>...</td><td>").append(r.members.size() - 40).append(" more</td></tr>");
                    break;
                }
                b.append("<tr><td class=k>").append(LabViews.esc(e.getKey())).append("</td><td>")
                 .append(LabViews.esc(describe(e.getValue()))).append("</td></tr>");
            }
            return b.append("</table>").toString();
        }
        return "<pre>" + LabViews.esc(describe(o)) + "</pre>";
    }

    private static String describe(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof double[] d) {
            return "double[" + d.length + "] " + preview(java.util.Arrays.toString(d));
        }
        if (v instanceof float[] d) {
            return "float[" + d.length + "] " + preview(java.util.Arrays.toString(d));
        }
        if (v instanceof int[] d) {
            return "int[" + d.length + "] " + preview(java.util.Arrays.toString(d));
        }
        if (v instanceof long[] d) {
            return "long[" + d.length + "] " + preview(java.util.Arrays.toString(d));
        }
        if (v instanceof byte[] d) {
            return "byte[" + d.length + "]";
        }
        if (v instanceof List<?> l) {
            return l.size() + " item(s)" + (l.isEmpty() ? "" : ": " + preview(String.valueOf(l.get(0))));
        }
        if (v instanceof RObject r) {
            return r.className + (r.has("fName") ? " " + r.string("fName") : "");
        }
        return preview(String.valueOf(v));
    }

    private static String preview(String s) {
        return s.length() > 90 ? s.substring(0, 87) + "..." : s;
    }

    private void inspectBranch(Branch b) {
        final RTree.RBranch br = b.branch();
        final StringBuilder h = new StringBuilder("<html><body><h1>").append(LabViews.esc(br.name())).append("</h1>");
        String baskets;
        try {
            baskets = Integer.toString(br.basketCount());
        } catch (IOException unreadable) {
            baskets = "?";
        }
        h.append(LabViews.table(
            "type", LabViews.esc(br.typeName()),
            "class", LabViews.esc(br.object().className),
            "entries", LabViews.num(br.entries()),
            "baskets", baskets,
            "data", br.hasData() ? "yes" : "only sub-branches"));
        final String head = h.toString();
        details.setText(head + "</body></html>");
        if (br.hasData()) {
            actions.add(small("Statistics", () -> stats(b, true)));
            actions.add(small("Plot", () -> stats(b, true)));
            // a small local tree is described at once; a large or remote one on request
            if (!b.src().remote && br.entries() <= AUTO_STATS_ENTRIES) {
                stats(b, false);
            }
        }
    }

    // ---- what the buttons do ---------------------------------------------------------

    private void stats(Branch b, boolean plotToo) {
        final RTree.RBranch br = b.branch();
        final long limit = b.src().remote ? 200_000 : 5_000_000;
        busy("Reading " + br.name() + "...");
        cancel.set(false);
        worker.execute(() -> {
            try {
                final ColumnStats s = ColumnStats.of(br, limit, f -> progressTo(f), cancel::get);
                final LabHist h = s.histogram();
                final StringBuilder body = new StringBuilder("<h2>Statistics</h2>");
                body.append(LabViews.table(
                    "values", LabViews.num(s.values) + " in " + LabViews.num(s.entries) + " entries"
                        + (s.entries < br.entries() ? " <span class=warn>(the first " + LabViews.num(s.entries) + ")</span>" : ""),
                    "mean", LabViews.num(s.mean),
                    "std dev", LabViews.num(s.stdDev()),
                    "min / max", LabViews.num(s.min) + " / " + LabViews.num(s.max),
                    "median", LabViews.num(s.quantile(0.5)),
                    "quartiles", LabViews.num(s.quantile(0.25)) + " / " + LabViews.num(s.quantile(0.75)),
                    "skewness / kurtosis", String.format(Locale.ROOT, "%.3g / %.3g", s.skewness(), s.kurtosis()),
                    "read in", String.format(Locale.ROOT, "%.2f s", s.nanos / 1e9)));
                final List<String> remarks = s.remarks();
                if (!remarks.isEmpty()) {
                    body.append("<h2>Worth knowing</h2><ul>");
                    for (String r : remarks) {
                        body.append("<li>").append(LabViews.esc(r)).append("</li>");
                    }
                    body.append("</ul>");
                }
                body.append("<p class=sub>Binning: Freedman-Diaconis over the central 99.8% (").append(h.xaxis.nbins)
                    .append(" bins), from a uniform sample of up to 50,000 values.</p>");
                SwingUtilities.invokeLater(() -> {
                    done(br.name() + ": " + LabViews.num(s.values) + " values");
                    if (showing instanceof Branch shown && shown.branch() == br) {
                        final String text = details.getText();
                        final int end = text.lastIndexOf("</body>");
                        details.setText((end > 0 ? text.substring(0, end) : text) + body + "</body></html>");
                        details.setCaretPosition(0);
                        mini.show(h, "distribution");
                    }
                    if (plotToo) {
                        RootPlotsPanel.instance().showHistogram(LabViews.toRootHistogram(h), br.name());
                    }
                });
            } catch (IOException | RuntimeException failed) {
                SwingUtilities.invokeLater(() -> done("Could not read " + br.name() + ": " + failed.getMessage()));
            }
        });
    }

    private void plotItem(Item item) {
        busy("Reading " + item.node().name + "...");
        worker.execute(() -> {
            try {
                final LabHist h = FileCompare.readHistogram(item.src().io, item.node());
                SwingUtilities.invokeLater(() -> {
                    RootPlotsPanel.instance().showHistogram(LabViews.toRootHistogram(h), item.node().name);
                    done("Plotted " + item.node().name);
                });
            } catch (IOException | RuntimeException failed) {
                SwingUtilities.invokeLater(() -> done("Could not plot: " + failed.getMessage()));
            }
        });
    }

    private void verify(Source s, boolean deep) {
        busy((deep ? "Verifying every record of " : "Checking the structure of ") + s.name + "...");
        cancel.set(false);
        worker.execute(() -> {
            try {
                final RootFsck.Report r = RootFsck.check(s.io.file(), deep ? RootFsck.Options.full() : RootFsck.Options.quick(),
                    this::progressTo, cancel::get);
                s.report = r;
                AppLogger.info("[ROOT Lab] " + s.name + ": health " + r.score() + "/100 (" + r.verdict() + ")");
                SwingUtilities.invokeLater(() -> {
                    done(s.name + ": health " + r.score() + "/100, " + r.verdict());
                    if (showing == s) {
                        inspect(sourceNode(s));
                    }
                });
            } catch (IOException | RuntimeException failed) {
                SwingUtilities.invokeLater(() -> done("Check failed: " + failed.getMessage()));
            }
        });
    }

    private static String reportHtml(RootFsck.Report r) {
        final String cls = r.count(RootFsck.Severity.ERROR) > 0 ? "bad" : r.count(RootFsck.Severity.WARNING) > 0 ? "warn" : "ok";
        final StringBuilder b = new StringBuilder("<h2>Check</h2>");
        b.append("<p class=").append(cls).append("><b>health ").append(r.score()).append("/100</b> &mdash; ")
         .append(LabViews.esc(r.verdict())).append("</p>");
        b.append(LabViews.table(
            "records", r.records + " (" + LabViews.bytes(r.recordBytes) + "), " + r.gaps + " free gaps",
            "blocks verified", r.blocksVerified + (r.algorithms.isEmpty() ? "" : " " + LabViews.esc(r.algorithms.keySet().toString())),
            "compression factor", String.format(Locale.ROOT, "%.3f", r.compressionFactor),
            "objects read", r.objectsRead + (r.objectsFailed > 0 ? ", " + r.objectsFailed + " not" : ""),
            "baskets checked", r.basketsChecked + (r.basketsFailed > 0 ? ", <span class=bad>" + r.basketsFailed + " bad</span>" : ""),
            "time", String.format(Locale.ROOT, "%.2f s", r.nanos / 1e9)));
        if (!r.findings.isEmpty()) {
            b.append("<ul>");
            int n = 0;
            for (RootFsck.Finding f : r.findings) {
                if (++n > 30) {
                    b.append("<li>...</li>");
                    break;
                }
                final String c = f.severity() == RootFsck.Severity.ERROR ? "bad"
                    : f.severity() == RootFsck.Severity.WARNING ? "warn" : "sub";
                b.append("<li class=").append(c).append('>').append(LabViews.esc(f.where())).append(": ")
                 .append(LabViews.esc(f.what())).append("</li>");
            }
            b.append("</ul>");
        }
        if (r.keyListMissing && !r.recovered.isEmpty()) {
            b.append("<p>").append(r.recovered.size()).append(" objects can still be read from their records.</p>");
        }
        return b.toString();
    }

    private DefaultMutableTreeNode sourceNode(Source s) {
        for (int i = 0; i < top.getChildCount(); i++) {
            final DefaultMutableTreeNode n = (DefaultMutableTreeNode) top.getChildAt(i);
            if (n.getUserObject() == s) {
                return n;
            }
        }
        return top;
    }

    private void compare(Source a) {
        final List<Source> others = new ArrayList<>();
        for (int i = 0; i < top.getChildCount(); i++) {
            final Object u = ((DefaultMutableTreeNode) top.getChildAt(i)).getUserObject();
            if (u instanceof Source s && s != a && s.io != null) {
                others.add(s);
            }
        }
        if (others.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Open a second ROOT file to compare this one with.",
                "Compare", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final Source b = (Source) JOptionPane.showInputDialog(this, "Compare " + a.name + " with:", "Compare",
            JOptionPane.QUESTION_MESSAGE, null, others.toArray(), others.get(0));
        if (b == null) {
            return;
        }
        busy("Comparing " + a.name + " with " + b.name + "...");
        worker.execute(() -> {
            try {
                final FileCompare.Result r = FileCompare.compare(a.io, b.io, cancel::get);
                final String text = r.text(a.name, b.name);
                AppLogger.info("[ROOT Lab] compare\n" + text);
                SwingUtilities.invokeLater(() -> {
                    done(r.rows.size() + " histograms compared, " + r.flagged() + " differ");
                    final StringBuilder h = new StringBuilder("<html><body><h1>").append(LabViews.esc(a.name))
                        .append(" vs ").append(LabViews.esc(b.name)).append("</h1><p>")
                        .append(r.rows.size()).append(" histograms compared, <b>").append(r.flagged())
                        .append("</b> with p &lt; ").append(r.threshold).append(" (Chi2Test, Kolmogorov).</p>")
                        .append("<table cellspacing=0 cellpadding=0><tr><td class=k>histogram</td><td class=k>chi2 p</td><td class=k>KS p</td></tr>");
                    for (FileCompare.Row row : r.rows) {
                        final String c = row.flagged(r.threshold) ? "bad" : "ok";
                        h.append("<tr><td>").append(LabViews.esc(row.path())).append("</td><td class=").append(c).append('>')
                         .append(String.format(Locale.ROOT, "%.3g", row.chi2Prob())).append("</td><td class=").append(c).append('>')
                         .append(String.format(Locale.ROOT, "%.3g", row.ksProb())).append("</td></tr>");
                    }
                    h.append("</table>");
                    if (!r.onlyInA.isEmpty() || !r.onlyInB.isEmpty()) {
                        h.append("<p class=warn>").append(r.onlyInA.size()).append(" only in ").append(LabViews.esc(a.name))
                         .append(", ").append(r.onlyInB.size()).append(" only in ").append(LabViews.esc(b.name)).append("</p>");
                    }
                    showing = r;
                    mini.show(null, null);
                    details.setText(h.append("</body></html>").toString());
                    details.setCaretPosition(0);
                });
            } catch (IOException | RuntimeException failed) {
                SwingUtilities.invokeLater(() -> done("Comparison failed: " + failed.getMessage()));
            }
        });
    }

    private void exportCsv(Item item) {
        final JFileChooser chooser = new JFileChooser(com.sphere.core.fs.WorkingDirectory.get().toFile());
        chooser.setSelectedFile(new File(item.node().name.replaceAll("[^A-Za-z0-9_.-]", "_") + ".csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        final Path target = chooser.getSelectedFile().toPath();
        worker.execute(() -> {
            try {
                final LabHist h = FileCompare.readHistogram(item.src().io, item.node());
                final StringBuilder csv = new StringBuilder("bin,x_low,x_high,content,error\n");
                final LabHist one = LabViews.projectionX(h);
                for (int i = 0; i <= h.xaxis.nbins + 1; i++) {
                    csv.append(i).append(',').append(i == 0 ? "-inf" : Double.toString(one.xaxis.lowEdge(i))).append(',')
                       .append(i == h.xaxis.nbins + 1 ? "inf" : Double.toString(one.xaxis.upEdge(i))).append(',')
                       .append(one.content(i)).append(',').append(one.error(i)).append('\n');
                }
                Files.writeString(target, csv);
                SwingUtilities.invokeLater(() -> done("Written " + target.getFileName()));
            } catch (IOException | RuntimeException failed) {
                SwingUtilities.invokeLater(() -> done("Could not write: " + failed.getMessage()));
            }
        });
    }

    private void openInEditor(Source s) {
        final var editor = com.sphere.Sphere.editorWindow();
        if (editor != null && s.local != null) {
            editor.openFileInternally(s.local.toFile());
        }
    }

    /** Counts the events of an event file by its markers, through gzip or xz when compressed. */
    private void countEvents(Source s) {
        busy("Counting the events of " + s.name + "...");
        worker.execute(() -> {
            final long t0 = System.nanoTime();
            long events = 0;
            try (InputStream raw = Files.newInputStream(s.local);
                 InputStream in = wrap(raw, s.sniff.compression());
                 var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))) {
                final boolean lhe = s.sniff.kind() == DataFormats.Kind.LHEF;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lhe ? line.stripLeading().startsWith("<event") : line.startsWith("E ")) {
                        events++;
                    }
                }
                final long n = events;
                SwingUtilities.invokeLater(() -> done(String.format(Locale.ROOT, "%s: %,d events (%.1f s)", s.name, n,
                    (System.nanoTime() - t0) / 1e9)));
            } catch (IOException | RuntimeException failed) {
                SwingUtilities.invokeLater(() -> done("Could not count: " + failed.getMessage()));
            }
        });
    }

    private static InputStream wrap(InputStream raw, String compression) throws IOException {
        if ("gzip".equals(compression)) {
            return new java.util.zip.GZIPInputStream(raw, 1 << 16);
        }
        if ("xz".equals(compression)) {
            final byte[] all = raw.readAllBytes();
            try {
                return new java.io.ByteArrayInputStream(
                    com.sphere.components.rootview.XzBlock.decodeAll(all, 0, all.length, Integer.MAX_VALUE - 16, null));
            } catch (java.util.zip.DataFormatException bad) {
                throw new IOException(bad.getMessage());
            }
        }
        return raw;
    }

    // ---- status ------------------------------------------------------------------------

    private void busy(String text) {
        cancel.set(false);
        status.setText(text);
        progress.setIndeterminate(true);
        progress.setVisible(true);
        stopButton.setVisible(true);
    }

    private void done(String text) {
        status.setText(cancel.get() ? text + " (stopped)" : text);
        progress.setIndeterminate(false);
        progress.setVisible(false);
        stopButton.setVisible(false);
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }

    private void progressTo(double fraction) {
        SwingUtilities.invokeLater(() -> {
            progress.setIndeterminate(false);
            progress.setValue((int) Math.round(1000 * Math.max(0, Math.min(1, fraction))));
        });
    }

    // ---- the rows ------------------------------------------------------------------------

    private static final class Renderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            setBackgroundNonSelectionColor(ImagingTheme.surface());
            setBackgroundSelectionColor(ImagingTheme.palette().getTerminalSelection());
            setBorderSelectionColor(null);
            setForeground(ImagingTheme.text());
            setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
            final Object u = ((DefaultMutableTreeNode) value).getUserObject();
            if (u instanceof Source s) {
                setIcon(LabIcons.of(s.remote ? LabIcons.Glyph.REMOTE_FILE
                    : s.io != null || s.sniff == null ? LabIcons.Glyph.ROOT_FILE : LabIcons.Glyph.DATA_FILE));
                final String what = s.error != null ? "  failed" : s.sniff != null ? "  " + s.sniff.kind().label : "";
                setText("<html>" + LabViews.esc(s.name) + "<font color='" + LabViews.hex(ImagingTheme.subduedText())
                    + "'>" + LabViews.esc(what) + "</font></html>");
                if (s.error != null) {
                    setIcon(LabIcons.of(LabIcons.Glyph.ERROR));
                }
            } else if (u instanceof Item item) {
                final RootNode n = item.node();
                setIcon(LabIcons.of(n.directory ? LabIcons.Glyph.FOLDER : LabIcons.forClass(n.className)));
                setText("<html>" + LabViews.esc(item.toString()) + " <font color='"
                    + LabViews.hex(ImagingTheme.subduedText()) + "'>" + LabViews.esc(n.className) + "</font></html>");
            } else if (u instanceof Branch b) {
                setIcon(LabIcons.of(LabIcons.Glyph.BRANCH));
                setText("<html>" + LabViews.esc(b.branch().name()) + " <font color='"
                    + LabViews.hex(ImagingTheme.subduedText()) + "'>" + LabViews.esc(b.branch().typeName()) + "</font></html>");
            } else {
                setIcon(LabIcons.of(LabIcons.Glyph.PENDING));
            }
            return this;
        }
    }
}

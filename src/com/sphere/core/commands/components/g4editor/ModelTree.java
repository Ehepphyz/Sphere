package com.sphere.components.g4editor;

import com.sphere.core.geant4.Geometry;
import com.sphere.core.geant4.LogicalVolume;
import com.sphere.core.geant4.PhysicalVolume;
import com.sphere.core.geant4.Placer;
import com.sphere.core.geant4.Xform;

import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The volume tree, FreeCAD's model view: the world, its placements and theirs.
 * Selecting here selects in the 3D view and back. Dragging a placement onto
 * a volume moves it into that volume without moving it in space.
 */
final class ModelTree extends JPanel {

    private static final long serialVersionUID = 1L;

    /** A node: a placement, or the world (pv == null). */
    record Node(PhysicalVolume pv, LogicalVolume lv) {
        @Override
        public String toString() {
            return pv == null ? lv.name() : pv.name();
        }
    }

    private final EditorModel model;
    private final JTree tree;
    private final DefaultTreeModel treeModel;
    private boolean syncing;
    private BiConsumer<MouseEvent, Node> menu = (e, n) -> { };

    ModelTree(EditorModel model) {
        super(new BorderLayout());
        this.model = model;
        treeModel = new DefaultTreeModel(new DefaultMutableTreeNode());
        tree = new JTree(treeModel);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(22);
        // Rows are measured as they scroll into view, not all at once: a volume with thousands of
        // placements expanded would otherwise lay out (and parse the HTML of) every one on each rebuild
        tree.setLargeModel(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setCellRenderer(new Renderer());
        tree.addTreeSelectionListener(e -> {
            if (syncing) {
                return;
            }
            final List<PhysicalVolume> sel = new ArrayList<>();
            Object focus = null;
            final TreePath[] paths = tree.getSelectionPaths();
            if (paths != null) {
                for (TreePath p : paths) {
                    final Object u = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
                    if (u instanceof Node n) {
                        if (n.pv != null) {
                            sel.add(n.pv);
                        } else {
                            focus = n.lv;
                        }
                    }
                }
            }
            syncing = true;
            try {
                if (sel.isEmpty() && focus != null) {
                    model.focus(focus);
                } else {
                    model.selectAll(sel);
                }
            } finally {
                syncing = false;
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                final TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (p == null) {
                    return;
                }
                if (!tree.isPathSelected(p)) {
                    tree.setSelectionPath(p);
                }
                final Object u = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
                if (u instanceof Node n) {
                    menu.accept(e, n);
                }
            }
        });
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON);
        tree.setTransferHandler(new Dnd());
        add(new JScrollPane(tree), BorderLayout.CENTER);
        model.addListener(new EditorModel.Listener() {
            public void selectionChanged() {
                syncSelection();
            }

            public void documentChanged(Geometry.Change c) {
                if (c == Geometry.Change.PROPERTIES) {
                    // a live drag moves placements, it never adds or removes one: the rows stay
                    tree.repaint();
                } else {
                    rebuild();
                }
            }
        });
        rebuild();
    }

    void setMenu(BiConsumer<MouseEvent, Node> m) {
        this.menu = m;
    }

    JTree tree() {
        return tree;
    }

    /** Rebuilds the nodes, keeping what was expanded. */
    void rebuild() {
        final Set<String> expanded = new HashSet<>();
        final DefaultMutableTreeNode oldRoot = (DefaultMutableTreeNode) treeModel.getRoot();
        if (oldRoot.getUserObject() != null) {
            final var en = tree.getExpandedDescendants(new TreePath(oldRoot.getPath()));
            if (en != null) {
                while (en.hasMoreElements()) {
                    expanded.add(key(en.nextElement()));
                }
            }
        }
        firstPlacement = null;
        motherOf = null;
        final Geometry g = model.geometry;
        if (g.world() == null) {
            treeModel.setRoot(new DefaultMutableTreeNode("(no world)"));
            return;
        }
        final LazyNode root = new LazyNode(new Node(null, g.world()));
        syncing = true;
        try {
            treeModel.setRoot(root);
            tree.expandPath(new TreePath(root.getPath()));
            expand(root, expanded);
        } finally {
            syncing = false;
        }
        syncSelection();
    }

    /**
     * A row whose children are made the first time the tree asks for them. The
     * tree is the placement hierarchy unrolled: a volume placed n times shows
     * its content n times, so a detector built from shared volumes unrolls to
     * millions of rows (as many as Geant4 has touchables), far more than the
     * heap holds. Only the rows of expanded volumes are ever made.
     */
    private static final class LazyNode extends DefaultMutableTreeNode {
        private static final long serialVersionUID = 1L;
        private static final int MAX_DEPTH = 40;
        private boolean loaded;

        LazyNode(Node n) {
            super(n);
        }

        Node node() {
            return (Node) getUserObject();
        }

        boolean loaded() {
            return loaded;
        }

        private void load() {
            if (loaded) {
                return;
            }
            loaded = true;
            if (getLevel() >= MAX_DEPTH) {
                return;
            }
            for (PhysicalVolume pv : node().lv.daughters()) {
                add(new LazyNode(new Node(pv, pv.logical())));
            }
        }

        @Override
        public boolean isLeaf() {
            return node().lv.daughters().isEmpty() || getLevel() >= MAX_DEPTH;
        }

        @Override
        public int getChildCount() {
            load();
            return super.getChildCount();
        }

        @Override
        public javax.swing.tree.TreeNode getChildAt(int index) {
            load();
            return super.getChildAt(index);
        }

        @Override
        public int getIndex(javax.swing.tree.TreeNode child) {
            load();
            return super.getIndex(child);
        }

        @Override
        public java.util.Enumeration<javax.swing.tree.TreeNode> children() {
            load();
            return super.children();
        }
    }

    private void expand(DefaultMutableTreeNode n, Set<String> keys) {
        for (int i = 0; i < n.getChildCount(); i++) {
            final DefaultMutableTreeNode c = (DefaultMutableTreeNode) n.getChildAt(i);
            final TreePath p = new TreePath(c.getPath());
            if (keys.contains(key(p))) {
                tree.expandPath(p);
                expand(c, keys);
            }
        }
    }

    private static String key(TreePath p) {
        final StringBuilder b = new StringBuilder();
        for (Object o : p.getPath()) {
            b.append('/').append(((DefaultMutableTreeNode) o).getUserObject());
        }
        return b.toString();
    }

    /** Placements revealed when the selection names ones whose rows are not made yet. */
    private static final int MAX_REVEALED = 64;
    /** For each volume, the placement through which the tree first reaches it; for each placement, its mother. */
    private Map<LogicalVolume, PhysicalVolume> firstPlacement;
    private Map<PhysicalVolume, LogicalVolume> motherOf;

    private void syncSelection() {
        if (syncing) {
            return;
        }
        syncing = true;
        try {
            final List<TreePath> paths = new ArrayList<>();
            if (treeModel.getRoot() instanceof LazyNode root) {
                final Set<PhysicalVolume> sel = model.selection();
                if (sel.isEmpty()) {
                    if (model.focus() == root.node().lv) {
                        paths.add(new TreePath(root.getPath()));
                    }
                } else {
                    // Every made row of a selected placement, then the first copy of those not made yet
                    final Set<PhysicalVolume> shown = new HashSet<>();
                    collect(root, sel, paths, shown);
                    int revealed = 0;
                    for (PhysicalVolume pv : sel) {
                        if (shown.contains(pv) || revealed >= MAX_REVEALED) {
                            continue;
                        }
                        final LazyNode n = reveal(root, pv);
                        if (n != null) {
                            paths.add(new TreePath(n.getPath()));
                            revealed++;
                        }
                    }
                }
            }
            tree.setSelectionPaths(paths.toArray(new TreePath[0]));
            if (!paths.isEmpty()) {
                tree.scrollPathToVisible(paths.get(0));
            }
        } finally {
            syncing = false;
        }
    }

    /** The made rows of selected placements: rows not made yet are not looked into. */
    private static void collect(LazyNode n, Set<PhysicalVolume> sel, List<TreePath> out, Set<PhysicalVolume> shown) {
        final PhysicalVolume pv = n.node().pv;
        if (pv != null && sel.contains(pv)) {
            out.add(new TreePath(n.getPath()));
            shown.add(pv);
        }
        if (n.loaded()) {
            for (int i = 0; i < n.getChildCount(); i++) {
                collect((LazyNode) n.getChildAt(i), sel, out, shown);
            }
        }
    }

    /** The row of a placement's first copy, making the rows on the way down to it. */
    private LazyNode reveal(LazyNode root, PhysicalVolume pv) {
        if (firstPlacement == null) {
            // Breadth first from the world, as Geometry.placements(): each volume's shortest way in
            firstPlacement = new HashMap<>();
            motherOf = new HashMap<>();
            final Set<LogicalVolume> seen = new HashSet<>();
            final java.util.ArrayDeque<LogicalVolume> todo = new java.util.ArrayDeque<>();
            todo.add(root.node().lv);
            seen.add(root.node().lv);
            while (!todo.isEmpty()) {
                final LogicalVolume lv = todo.poll();
                for (PhysicalVolume p : lv.daughters()) {
                    motherOf.putIfAbsent(p, lv);
                    firstPlacement.putIfAbsent(p.logical(), p);
                    if (seen.add(p.logical())) {
                        todo.add(p.logical());
                    }
                }
            }
        }
        final List<PhysicalVolume> chain = new ArrayList<>();
        PhysicalVolume p = pv;
        while (true) {
            final LogicalVolume m = motherOf.get(p);
            if (m == null || chain.size() >= LazyNode.MAX_DEPTH) {
                return null; // not under the world
            }
            chain.add(0, p);
            if (m == root.node().lv) {
                break;
            }
            p = firstPlacement.get(m);
        }
        LazyNode n = root;
        for (PhysicalVolume step : chain) {
            LazyNode next = null;
            for (int i = 0; i < n.getChildCount(); i++) {
                final LazyNode c = (LazyNode) n.getChildAt(i);
                if (c.node().pv == step) {
                    next = c;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            n = next;
        }
        return n;
    }

    // ------------------------------------------------------------------

    /**
     * A row: the volume's icon, then runs of text in their own colours, drawn directly. The rows were HTML
     * labels, which Swing parses and lays out each time it measures or paints one: a few milliseconds a row,
     * a quarter of a second to repaint the visible part of a big tree as the mouse moved over it.
     */
    private final class Renderer extends DefaultTreeCellRenderer {
        private static final long serialVersionUID = 1L;
        private static final Color LV = new Color(0x7a8494), REPLICA = new Color(0x2a8a5a), MATERIAL = new Color(0x8a93a3),
            SENSITIVE = new Color(0xd07020), HIDDEN = new Color(0xb0b0b0), ERROR = new Color(0xd03030);
        private final List<String> runs = new ArrayList<>();
        private final List<Color> colours = new ArrayList<>();   // null: the label's own (selected or not)
        private boolean bold;

        private void run(String text, Color c) {
            runs.add(text);
            colours.add(c);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean expanded, boolean leaf,
                                                      int row, boolean hasFocus) {
            final JLabel l = (JLabel) super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, hasFocus);
            runs.clear();
            colours.clear();
            bold = false;
            final Object u = ((DefaultMutableTreeNode) value).getUserObject();
            if (u instanceof Node n) {
                final LogicalVolume lv = n.lv;
                final String tag = lv.solid() == null ? "box" : lv.solid().tag();
                final int c = lv.color();
                l.setIcon(Icons.volume(16, tag, new Color((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF)));
                if (n.pv == null) {
                    bold = true;
                    run(lv.name(), null);
                } else {
                    run(n.pv.name(), null);
                    if (!n.pv.name().equals(lv.name()) && !n.pv.name().startsWith(lv.name())) {
                        run(" : " + lv.name(), LV);
                    }
                    if (n.pv.isReplica()) {
                        run(" x" + n.pv.copies(), REPLICA);
                    }
                }
                run(" " + lv.material().replace("G4_", ""), MATERIAL);
                if (lv.sensitive() != null) {
                    run(" ◉", SENSITIVE);
                }
                if (!lv.visible()) {
                    run(" (hidden)", HIDDEN);
                }
                final String err = lv.solid() == null ? "no solid" : lv.solid().error();
                if (err != null || (n.pv != null && n.pv.error() != null)) {
                    run(" ⚠", ERROR);
                    l.setToolTipText(err != null ? err : n.pv.error());
                } else {
                    l.setToolTipText(lv.solid() == null ? null : lv.solid().g4Class() + " " + lv.solid().name());
                }
                l.setText(String.join("", runs)); // plain: measured by the font alone, for accessibility as well
            }
            return l;
        }

        private Font runFont() {
            final Font f = getFont();
            return bold && f != null ? f.deriveFont(Font.BOLD) : f;
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            final java.awt.Dimension d = super.getPreferredSize();
            if (runs.isEmpty() || getFont() == null) {
                return d;
            }
            final java.awt.FontMetrics fm = getFontMetrics(runFont());
            int w = 0;
            for (String r : runs) {
                w += fm.stringWidth(r);
            }
            final javax.swing.Icon ic = getIcon();
            final java.awt.Insets in = getInsets();
            d.width = in.left + in.right + w + (ic == null ? 0 : ic.getIconWidth() + getIconTextGap()) + 2;
            return d;
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            if (runs.isEmpty()) {
                super.paintComponent(g);
                return;
            }
            final java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            final Object hints = java.awt.Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            if (hints instanceof java.util.Map<?, ?> m) {
                g2.addRenderingHints(m);
            }
            final java.awt.Insets in = getInsets();
            int x = in.left;
            final javax.swing.Icon ic = getIcon();
            if (ic != null) {
                ic.paintIcon(this, g2, x, (getHeight() - ic.getIconHeight()) / 2);
                x += ic.getIconWidth() + getIconTextGap();
            }
            g2.setFont(runFont());
            final java.awt.FontMetrics fm = g2.getFontMetrics();
            final int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            for (int i = 0; i < runs.size(); i++) {
                g2.setColor(colours.get(i) == null ? getForeground() : colours.get(i));
                g2.drawString(runs.get(i), x, y);
                x += fm.stringWidth(runs.get(i));
            }
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------
    // Drag a placement onto a volume: reparent, keeping its world position
    // ------------------------------------------------------------------

    private final class Dnd extends TransferHandler {
        private static final long serialVersionUID = 1L;

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            final TreePath p = tree.getSelectionPath();
            if (p == null) {
                return null;
            }
            final Object u = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            return u instanceof Node n && n.pv != null ? new StringSelection(n.pv.name()) : null;
        }

        @Override
        public boolean canImport(TransferSupport s) {
            if (!s.isDrop() || !s.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return false;
            }
            final JTree.DropLocation dl = (JTree.DropLocation) s.getDropLocation();
            return dl.getPath() != null;
        }

        @Override
        public boolean importData(TransferSupport s) {
            try {
                final String name = (String) s.getTransferable().getTransferData(DataFlavor.stringFlavor);
                final JTree.DropLocation dl = (JTree.DropLocation) s.getDropLocation();
                final Object u = ((DefaultMutableTreeNode) dl.getPath().getLastPathComponent()).getUserObject();
                if (!(u instanceof Node target)) {
                    return false;
                }
                final Geometry g = model.geometry;
                final PhysicalVolume pv = g.placement(name);
                if (pv == null || pv.mother() == target.lv || pv.logical().contains(target.lv)) {
                    return false;
                }
                final List<Geometry.Instance> before = g.instancesOf(pv);
                if (before.isEmpty()) {
                    return false;
                }
                final Xform world = before.get(0).world();
                model.edit("Move into " + target.lv.name(), () -> {
                    g.reparent(pv, target.lv);
                    g.rebuild();
                    final Geometry.Instance mi = Placer.instanceOf(g, target.lv, world.translation());
                    Placer.setWorldTransform(pv, mi, world, model::len);
                });
                model.log("# " + pv.name() + " moved into " + target.lv.name());
                SwingUtilities.invokeLater(() -> model.select(pv, false));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}

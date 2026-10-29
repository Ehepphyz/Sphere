package com.sphere.components.g4editor;

import javax.swing.DropMode;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
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
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The files of a Geant4 application, as a tree: double-click opens; the
 * right button opens, makes, renames, moves, duplicates and deletes (to the
 * recycle bin, asked first), shows in the file manager; files and folders
 * are dragged onto a folder to move them there.
 */
final class ProjectTree extends JPanel {

    private static final long serialVersionUID = 1L;

    /** What the window does when files change under it. */
    interface Host {
        void open(Path file);

        /** A file or folder moved or renamed: the tabs showing what was in it follow. */
        void moved(Path from, Path to);

        /** A file or folder deleted: the tabs showing what was in it close. */
        void deleted(Path path);

        void say(String message);
    }

    private static final Set<String> SHOWN = Set.of(".cc", ".hh", ".cpp", ".hpp", ".h", ".icc", ".mac", ".in", ".gdml",
        ".txt", ".md", ".cmake", ".json", ".py", ".C", ".out", ".root", ".csv");

    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("no project")));
    private final Host host;
    private Path root;

    ProjectTree(Host host) {
        super(new BorderLayout());
        this.host = host;
        tree.setRootVisible(true);
        tree.setCellRenderer(new Renderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.setDragEnabled(true);
        tree.setDropMode(DropMode.ON);
        tree.setTransferHandler(new Move());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    final Path p = pathAt(e);
                    if (p != null && Files.isRegularFile(p)) {
                        host.open(p);
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "g4code-delete", () -> delete(selected()));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "g4code-rename", () -> {
            final List<Path> s = selected();
            if (s.size() == 1) {
                rename(s.get(0));
            }
        });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "g4code-open", () -> {
            for (Path p : selected()) {
                if (Files.isRegularFile(p)) {
                    host.open(p);
                }
            }
        });
        add(new JScrollPane(tree), BorderLayout.CENTER);
    }

    private void bind(KeyStroke ks, String name, Runnable r) {
        tree.getInputMap(JComponent.WHEN_FOCUSED).put(ks, name);
        tree.getActionMap().put(name, new javax.swing.AbstractAction() {
            private static final long serialVersionUID = 1L;

            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        });
    }

    JTree tree() {
        return tree;
    }

    void setRoot(Path project) {
        root = project;
        refresh();
        for (int i = 0; i < tree.getRowCount() && i < 40; i++) {
            tree.expandRow(i);
        }
    }

    /** Reads the folder again, keeping what was expanded and selected. */
    void refresh() {
        if (root == null) {
            return;
        }
        final Set<Path> expanded = new HashSet<>(), chosen = new HashSet<>(selected());
        final Enumeration<TreePath> open = tree.getExpandedDescendants(new TreePath(tree.getModel().getRoot()));
        while (open != null && open.hasMoreElements()) {
            if (((DefaultMutableTreeNode) open.nextElement().getLastPathComponent()).getUserObject() instanceof Path p) {
                expanded.add(p);
            }
        }
        final DefaultMutableTreeNode top = new DefaultMutableTreeNode(root);
        addChildren(top, root, 0);
        ((DefaultTreeModel) tree.getModel()).setRoot(top);
        final List<TreePath> select = new ArrayList<>();
        for (Enumeration<javax.swing.tree.TreeNode> e = top.breadthFirstEnumeration(); e.hasMoreElements(); ) {
            final DefaultMutableTreeNode n = (DefaultMutableTreeNode) e.nextElement();
            if (n.getUserObject() instanceof Path p) {
                final TreePath tp = new TreePath(n.getPath());
                if (expanded.contains(p) || n == top) {
                    tree.expandPath(tp);
                }
                if (chosen.contains(p)) {
                    select.add(tp);
                }
            }
        }
        tree.setSelectionPaths(select.toArray(TreePath[]::new));
    }

    private void addChildren(DefaultMutableTreeNode node, Path dir, int depth) {
        if (depth > 5) {
            return;
        }
        try (Stream<Path> s = Files.list(dir)) {
            final List<Path> all = s.sorted((a, b) -> {
                final boolean da = Files.isDirectory(a), db = Files.isDirectory(b);
                return da != db ? (da ? -1 : 1) : a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            }).toList();
            for (Path p : all) {
                final String n = p.getFileName().toString();
                if (n.startsWith(".") || n.startsWith("build") || n.equals("CMakeFiles")) {
                    continue;
                }
                if (Files.isDirectory(p)) {
                    final DefaultMutableTreeNode d = new DefaultMutableTreeNode(p);
                    addChildren(d, p, depth + 1);
                    node.add(d);
                } else {
                    final int dot = n.lastIndexOf('.');
                    if (dot >= 0 && SHOWN.contains(n.substring(dot)) || n.equals("CMakeLists.txt") || n.equals("GNUmakefile")) {
                        node.add(new DefaultMutableTreeNode(p));
                    }
                }
            }
        } catch (IOException ignored) {
            // an unreadable folder shows empty
        }
    }

    private Path pathAt(MouseEvent e) {
        final TreePath tp = tree.getPathForLocation(e.getX(), e.getY());
        return tp != null && ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject() instanceof Path p ? p : null;
    }

    private List<Path> selected() {
        final List<Path> out = new ArrayList<>();
        final TreePath[] tps = tree.getSelectionPaths();
        if (tps != null) {
            for (TreePath tp : tps) {
                if (((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject() instanceof Path p) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** The folder a new file goes in: the clicked folder, or the folder of the clicked file, or the project. */
    private Path folderFor(Path clicked) {
        if (clicked == null) {
            return root;
        }
        return Files.isDirectory(clicked) ? clicked : clicked.getParent();
    }

    // ------------------------------------------------------------------
    // The right button
    // ------------------------------------------------------------------

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger() || root == null) {
            return;
        }
        final TreePath tp = tree.getPathForLocation(e.getX(), e.getY());
        if (tp != null && !tree.isPathSelected(tp)) {
            tree.setSelectionPath(tp);
        }
        final List<Path> sel = tp == null ? List.of() : selected();
        final Path one = sel.size() == 1 ? sel.get(0) : null;
        final JPopupMenu m = new JPopupMenu();
        if (one != null && Files.isRegularFile(one)) {
            m.add(item("Open", "ENTER", () -> host.open(one)));
            m.addSeparator();
        }
        final Path folder = folderFor(one);
        m.add(item("New file...", null, () -> newFile(folder)));
        m.add(item("New folder...", null, () -> newFolder(folder)));
        if (!sel.isEmpty() && !sel.contains(root)) {
            m.addSeparator();
            if (one != null) {
                m.add(item("Rename...", "F2", () -> rename(one)));
                if (Files.isRegularFile(one)) {
                    m.add(item("Duplicate", null, () -> duplicate(one)));
                }
            }
            m.add(item("Move to...", null, () -> moveTo(sel)));
            m.add(item(sel.size() == 1 ? "Delete" : "Delete " + sel.size() + " items", "DELETE", () -> delete(sel)));
        }
        m.addSeparator();
        final Path shown = one != null ? one : root;
        m.add(item(System.getProperty("os.name", "").startsWith("Windows") ? "Show in Explorer" : "Show in the file manager",
            null, () -> reveal(shown)));
        m.add(item("Copy path", null, () -> Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(shown.toString()), null)));
        m.add(item("Refresh", null, this::refresh));
        m.show(tree, e.getX(), e.getY());
    }

    private static JMenuItem item(String label, String key, Runnable r) {
        final JMenuItem i = new JMenuItem(label);
        if (key != null) {
            i.setAccelerator(KeyStroke.getKeyStroke(key));
        }
        i.addActionListener(e -> r.run());
        return i;
    }

    private String ask(String question, String value) {
        final Object answer = JOptionPane.showInputDialog(this, question, "G4code", JOptionPane.PLAIN_MESSAGE, null, null,
            value);
        if (answer == null) {
            return null;
        }
        final String name = answer.toString().strip();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            host.say("Not a file name: \"" + name + "\"");
            return null;
        }
        return name;
    }

    private void newFile(Path folder) {
        final String name = ask("New file in " + root.relativize(folder) + ":", "NewFile.cc");
        if (name == null) {
            return;
        }
        try {
            final Path p = Files.createFile(folder.resolve(name));
            refresh();
            host.open(p);
        } catch (FileAlreadyExistsException e) {
            host.say(name + " already exists in " + folder.getFileName());
        } catch (IOException e) {
            host.say("Cannot make " + name + ": " + e.getMessage());
        }
    }

    private void newFolder(Path folder) {
        final String name = ask("New folder in " + root.relativize(folder) + ":", "macros");
        if (name == null) {
            return;
        }
        try {
            Files.createDirectories(folder.resolve(name));
            refresh();
        } catch (IOException e) {
            host.say("Cannot make " + name + ": " + e.getMessage());
        }
    }

    private void rename(Path p) {
        final String name = ask("New name of " + p.getFileName() + ":", p.getFileName().toString());
        if (name == null || name.equals(p.getFileName().toString())) {
            return;
        }
        move(p, p.resolveSibling(name));
        refresh();
    }

    private void duplicate(Path p) {
        final String n = p.getFileName().toString();
        final int dot = n.lastIndexOf('.');
        final String stem = dot > 0 ? n.substring(0, dot) : n, ext = dot > 0 ? n.substring(dot) : "";
        Path copy = p.resolveSibling(stem + "_copy" + ext);
        for (int i = 2; Files.exists(copy); i++) {
            copy = p.resolveSibling(stem + "_copy" + i + ext);
        }
        try {
            Files.copy(p, copy);
            refresh();
            host.say("Duplicated as " + copy.getFileName());
        } catch (IOException e) {
            host.say("Cannot duplicate " + n + ": " + e.getMessage());
        }
    }

    private void moveTo(List<Path> paths) {
        final JFileChooser c = new JFileChooser(root.toFile());
        c.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        c.setDialogTitle("Move " + (paths.size() == 1 ? paths.get(0).getFileName() : paths.size() + " items") + " to");
        if (c.showDialog(this, "Move here") != JFileChooser.APPROVE_OPTION) {
            return;
        }
        moveInto(paths, c.getSelectedFile().toPath());
    }

    /** Moves files and folders into a folder, refusing a folder into itself. */
    boolean moveInto(List<Path> paths, Path folder) {
        boolean any = false;
        for (Path p : paths) {
            if (folder.startsWith(p) || p.getParent() != null && p.getParent().equals(folder)) {
                continue;
            }
            any |= move(p, folder.resolve(p.getFileName()));
        }
        refresh();
        return any;
    }

    private boolean move(Path from, Path to) {
        if (Files.exists(to)) {
            host.say(to.getFileName() + " already exists in " + to.getParent().getFileName() + ": nothing moved");
            return false;
        }
        try {
            Files.move(from, to);
            host.moved(from, to);
            host.say("Moved " + root.relativize(from) + " to " + root.relativize(to));
            return true;
        } catch (IOException e) {
            host.say("Cannot move " + from.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    /** To the recycle bin when the system has one, asked first; deleted for good only when it has none, asked again. */
    private void delete(List<Path> paths) {
        final List<Path> sel = new ArrayList<>(paths);
        sel.remove(root);
        if (sel.isEmpty()) {
            return;
        }
        final boolean trash = Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        final String what = sel.size() == 1 ? sel.get(0).getFileName().toString() : sel.size() + " items";
        final String question = trash ? "Move " + what + " to the recycle bin?"
            : "Delete " + what + " for good? (this system has no recycle bin for Java)";
        if (JOptionPane.showConfirmDialog(this, question, "G4code", JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        for (Path p : sel) {
            try {
                if (trash) {
                    if (!Desktop.getDesktop().moveToTrash(p.toFile())) {
                        host.say("The recycle bin refused " + p.getFileName());
                        continue;
                    }
                } else {
                    try (Stream<Path> s = Files.walk(p)) {
                        for (Path q : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) {
                            Files.delete(q);
                        }
                    }
                }
                host.deleted(p);
            } catch (IOException | RuntimeException e) {
                host.say("Cannot delete " + p.getFileName() + ": " + e.getMessage());
            }
        }
        refresh();
        host.say(trash ? what + " moved to the recycle bin" : what + " deleted");
    }

    private void reveal(Path p) {
        try {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                new ProcessBuilder("explorer.exe", "/select," + p.toAbsolutePath()).start();
            } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                Desktop.getDesktop().browseFileDirectory(p.toFile());
            } else if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open((Files.isDirectory(p) ? p : p.getParent()).toFile());
            }
        } catch (IOException | RuntimeException e) {
            host.say("Cannot show " + p.getFileName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Dragging onto a folder moves
    // ------------------------------------------------------------------

    private final class Move extends TransferHandler {
        private static final long serialVersionUID = 1L;

        @Override
        public int getSourceActions(JComponent c) {
            return MOVE;
        }

        @Override
        protected Transferable createTransferable(JComponent c) {
            final List<java.io.File> out = new ArrayList<>();
            for (Path p : selected()) {
                if (!p.equals(root)) {
                    out.add(p.toFile());
                }
            }
            return out.isEmpty() ? null : new Transferable() {
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[] {DataFlavor.javaFileListFlavor};
                }

                public boolean isDataFlavorSupported(DataFlavor f) {
                    return DataFlavor.javaFileListFlavor.equals(f);
                }

                public Object getTransferData(DataFlavor f) {
                    return out;
                }
            };
        }

        @Override
        public boolean canImport(TransferSupport s) {
            if (!s.isDrop() || !s.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                return false;
            }
            final JTree.DropLocation dl = (JTree.DropLocation) s.getDropLocation();
            return dl.getPath() != null
                && ((DefaultMutableTreeNode) dl.getPath().getLastPathComponent()).getUserObject() instanceof Path;
        }

        @Override
        @SuppressWarnings("unchecked")
        public boolean importData(TransferSupport s) {
            if (!canImport(s)) {
                return false;
            }
            final Path target = (Path) ((DefaultMutableTreeNode) ((JTree.DropLocation) s.getDropLocation()).getPath()
                .getLastPathComponent()).getUserObject();
            try {
                final List<java.io.File> files = (List<java.io.File>) s.getTransferable()
                    .getTransferData(DataFlavor.javaFileListFlavor);
                final List<Path> paths = new ArrayList<>();
                for (java.io.File f : files) {
                    paths.add(f.toPath());
                }
                return moveInto(paths, Files.isDirectory(target) ? target : target.getParent());
            } catch (Exception e) {
                host.say("Cannot move: " + e.getMessage());
                return false;
            }
        }
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    /**
     * The tree's UI paints the selected row itself (a light fill in the light theme, a dark one in the dark):
     * the name keeps the tree's own text colour, where the default renderer turned it white.
     */
    private static final class Renderer extends DefaultTreeCellRenderer {
        private static final long serialVersionUID = 1L;

        Renderer() {
            setBackgroundSelectionColor(new Color(0, 0, 0, 0));
            setBorderSelectionColor(new Color(0, 0, 0, 0));
        }

        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel, boolean exp, boolean leaf,
                                                      int row, boolean focus) {
            super.getTreeCellRendererComponent(t, value, sel, exp, leaf, row, focus);
            setForeground(t.getForeground());
            if (((DefaultMutableTreeNode) value).getUserObject() instanceof Path p) {
                final String n = p.getFileName() == null ? p.toString() : p.getFileName().toString();
                setText(n);
                setFont(t.getFont().deriveFont(n.startsWith("DetectorConstruction") ? Font.BOLD : Font.PLAIN));
                if (Files.isDirectory(p)) {
                    setIcon(exp ? getOpenIcon() : getClosedIcon());
                }
            }
            return this;
        }
    }
}

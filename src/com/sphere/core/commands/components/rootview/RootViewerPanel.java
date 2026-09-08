package com.sphere.components.rootview;

import com.sphere.components.imaging.ImagingTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The .root workbench: the file's tree on the left, the plot above the
 * inspector on the right.
 *
 * Edits to the file itself are collected here and only take effect when they
 * are written out, always to a file this panel produces rather than over the
 * one being read.
 */
public final class RootViewerPanel extends ViewSurface {

    /** Told when the open file changes, so a window can retitle itself. */
    public interface TitleListener {
        void titleChanged();
    }

    private final RootPlot plot = new RootPlot();
    private final RootInspector inspector = new RootInspector();
    private final RootMacroPane macro = new RootMacroPane();
    private final JTree tree = new JTree();
    private final JTextField filter = new JTextField();
    private final JLabel status = new JLabel(" ");

    private final JToggleButton bars = toggle("Bars");
    private final JToggleButton steps = toggle("Steps");
    private final JToggleButton points = toggle("Points");
    private final JToggleButton line = toggle("Line");
    private final JToggleButton logX = toggle("Log X");
    private final JToggleButton logY = toggle("Log Y");
    private final JToggleButton grid = toggle("Grid");
    private final JToggleButton errors = toggle("Errors");
    private final JLabel rebinLabel = new JLabel("1");

    private RootFile file;
    private RootNode root;
    private RootNode current;
    private TitleListener titleListener;

    /** Names and titles the user has changed, and the objects dropped. */
    private final Map<RootNode, String[]> renamed = new HashMap<>();
    private final Set<RootNode> dropped = new HashSet<>();

    public RootViewerPanel() {
        super(new BorderLayout(), false);
        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildStatus(), BorderLayout.SOUTH);

        inspector.setBinListener((bin, value) -> {
            plot.repaint();
            setStatus("bin " + bin + " set to " + value
                      + "; the file on disk is unchanged");
        });
        grid.setSelected(true);
        errors.setSelected(true);
        bars.setSelected(true);
        plot.showMessage("Open a .root file");
    }

    public void setTitleListener(TitleListener listener) {
        this.titleListener = listener;
    }

    public String documentTitle() {
        if (file == null) {
            return "no file";
        }
        final String name = file.getPath().getFileName().toString();
        return hasPendingEdits() ? name + " *" : name;
    }

    public boolean hasPendingEdits() {
        return !renamed.isEmpty() || !dropped.isEmpty();
    }

    public RootPlot getPlot() {
        return plot;
    }

    // ---- layout ------------------------------------------------------------

    private JComponent buildToolbar() {
        ImagingTheme.Surface bar = ImagingTheme.strip(true);
        bar.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));

        bar.add(button("Open...", "Open another .root file", this::openDialog));
        bar.add(Box.createHorizontalStrut(4));
        bar.add(button("Reload", "Read the file again from disk", this::reload));
        bar.add(Box.createHorizontalStrut(10));
        bar.add(ImagingTheme.separator());
        bar.add(Box.createHorizontalStrut(10));

        ButtonGroup styles = new ButtonGroup();
        for (JToggleButton b : new JToggleButton[] {bars, steps, points, line}) {
            styles.add(b);
            bar.add(b);
            bar.add(Box.createHorizontalStrut(2));
        }
        bars.addActionListener(e -> plot.setStyle(RootPlot.Style.BARS));
        steps.addActionListener(e -> plot.setStyle(RootPlot.Style.STEPS));
        points.addActionListener(e -> plot.setStyle(RootPlot.Style.POINTS));
        line.addActionListener(e -> plot.setStyle(RootPlot.Style.LINE));

        bar.add(Box.createHorizontalStrut(10));
        bar.add(ImagingTheme.separator());
        bar.add(Box.createHorizontalStrut(10));

        logX.addActionListener(e -> plot.setLogX(logX.isSelected()));
        logY.addActionListener(e -> plot.setLogY(logY.isSelected()));
        grid.addActionListener(e -> plot.setShowGrid(grid.isSelected()));
        errors.addActionListener(e -> plot.setShowErrors(errors.isSelected()));
        for (JToggleButton b : new JToggleButton[] {logX, logY, grid, errors}) {
            bar.add(b);
            bar.add(Box.createHorizontalStrut(2));
        }

        bar.add(Box.createHorizontalStrut(10));
        bar.add(ImagingTheme.separator());
        bar.add(Box.createHorizontalStrut(10));

        JLabel caption = new JLabel("Group");
        caption.setForeground(ImagingTheme.subduedText());
        caption.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        bar.add(caption);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(button("-", "Group fewer bins together", () -> setRebin(plot.getRebin() / 2)));
        rebinLabel.setForeground(ImagingTheme.text());
        rebinLabel.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        rebinLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        bar.add(rebinLabel);
        bar.add(button("+", "Group more bins together", () -> setRebin(plot.getRebin() * 2)));

        bar.add(Box.createHorizontalStrut(10));
        bar.add(ImagingTheme.separator());
        bar.add(Box.createHorizontalStrut(10));
        bar.add(button("Export...", "Write the plot or its numbers to a file",
                       this::exportMenu));
        bar.add(button("Edit file...", "Rename, drop or extract objects",
                       this::editMenu));
        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    private JComponent buildBody() {
        plot.setPreferredSize(new Dimension(760, 470));
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                                          plot, buildLowerRight());
        right.setResizeWeight(0.66);
        right.setDividerSize(4);
        right.setBorder(BorderFactory.createEmptyBorder());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                                          buildTreeSide(), right);
        split.setResizeWeight(0.24);
        split.setDividerSize(4);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
    }

    private JComponent buildLowerRight() {
        ImagingTheme.Tabs tabs = new ImagingTheme.Tabs();
        tabs.addTab("Inspector", inspector);
        tabs.addTab("Macro", macro);
        tabs.setSelectedIndex(0);
        tabs.setPreferredSize(new Dimension(400, 230));
        return tabs;
    }

    private JComponent buildTreeSide() {
        ViewSurface side = new ViewSurface(new BorderLayout(), false);

        filter.setBackground(ImagingTheme.surface());
        filter.setForeground(ImagingTheme.text());
        filter.setCaretColor(ImagingTheme.accent());
        filter.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ImagingTheme.border()),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        filter.setFont(ImagingTheme.uiFont(Font.PLAIN, 12f));
        filter.setToolTipText("Show only what matches");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        tree.setBackground(ImagingTheme.panel());
        tree.setForeground(ImagingTheme.text());
        tree.setRowHeight(20);
        tree.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        tree.setCellRenderer(new NodeRenderer());
        tree.setModel(new RootTreeModel(new RootNode("", "", "", null)));
        tree.addTreeSelectionListener(e -> selected());
        tree.setComponentPopupMenu(nodeMenu());

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(ImagingTheme.panel());
        scroll.setPreferredSize(new Dimension(260, 400));

        side.add(filter, BorderLayout.NORTH);
        side.add(scroll, BorderLayout.CENTER);
        side.setPreferredSize(new Dimension(260, 400));
        side.setMinimumSize(new Dimension(160, 200));
        return side;
    }

    private JComponent buildStatus() {
        ImagingTheme.Surface bar = ImagingTheme.strip(true);
        bar.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        status.setForeground(ImagingTheme.subduedText());
        status.setFont(ImagingTheme.uiFont(Font.PLAIN, 11f));
        bar.add(status);
        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    private JButton button(String label, String tip, Runnable action) {
        JButton b = ImagingTheme.textButton(label, tip);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JToggleButton toggle(String label) {
        JToggleButton b = new JToggleButton(label);
        ImagingTheme.styleButton(b);
        return b;
    }

    // ---- opening -----------------------------------------------------------

    public void open(File target) {
        if (target == null || !target.isFile()) {
            return;
        }
        close();
        try {
            file = new RootFile(target.toPath());
            root = file.tree();
            renamed.clear();
            dropped.clear();
            tree.setModel(new RootTreeModel(root));
            expandTop();
            inspector.showFile(file, root);
            inspector.showNothing();
            plot.showMessage("Select an object on the left");
            setStatus(String.format(Locale.ROOT, "%s -- %,d bytes, %d objects, %s",
                      target.getName(), file.getSize(), root.countObjects(),
                      RootFile.compressionName(file.getCompressionAlgorithm())));
            selectFirstDrawable();
        } catch (RootFile.NotRootFile notRoot) {
            plot.showMessage(notRoot.getMessage());
            setStatus(notRoot.getMessage());
        } catch (IOException unreadable) {
            plot.showMessage("This file could not be read: " + unreadable.getMessage());
            setStatus(unreadable.getMessage());
        }
        retitle();
    }

    public void reload() {
        if (file != null) {
            open(file.getPath().toFile());
        }
    }

    public void close() {
        if (file != null) {
            try {
                file.close();
            } catch (IOException ignored) {
                // the file is being replaced anyway
            }
            file = null;
        }
    }

    private void openDialog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("ROOT files", "root"));
        if (chooser.showOpenDialog(SwingUtilities.getWindowAncestor(this))
            == JFileChooser.APPROVE_OPTION) {
            open(chooser.getSelectedFile());
        }
    }

    private void expandTop() {
        for (int row = 0; row < tree.getRowCount() && row < 200; row++) {
            tree.expandRow(row);
        }
    }

    private void selectFirstDrawable() {
        RootNode first = firstDrawable(root);
        if (first == null) {
            return;
        }
        TreePath path = pathTo(first);
        if (path != null) {
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    private static RootNode firstDrawable(RootNode from) {
        if (from == null) {
            return null;
        }
        for (RootNode child : from.children) {
            if (child.isHistogram() || child.isGraph()) {
                return child;
            }
        }
        for (RootNode child : from.children) {
            RootNode deeper = firstDrawable(child);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private TreePath pathTo(RootNode target) {
        List<Object> parts = new ArrayList<>();
        if (!buildPath(root, target, parts)) {
            return null;
        }
        return new TreePath(parts.toArray());
    }

    private static boolean buildPath(RootNode from, RootNode target, List<Object> into) {
        if (from == target) {
            into.add(from);
            return true;
        }
        for (RootNode child : from.children) {
            if (buildPath(child, target, into)) {
                into.add(0, from);
                return true;
            }
        }
        return false;
    }

    private void applyFilter() {
        if (root == null) {
            return;
        }
        RootTreeModel model = new RootTreeModel(root);
        model.setFilter(filter.getText());
        tree.setModel(model);
        expandTop();
    }

    // ---- showing an object -------------------------------------------------

    private void selected() {
        TreePath path = tree.getSelectionPath();
        if (path == null || file == null) {
            return;
        }
        Object last = path.getLastPathComponent();
        if (!(last instanceof RootNode node)) {
            return;
        }
        current = node;
        inspector.showKey(node.key, node.pathFrom(root));

        if (node.directory) {
            plot.showMessage(node.name + " holds " + node.countObjects() + " objects");
            inspector.showMessage(node.name + " is a directory.");
            return;
        }
        if (dropped.contains(node)) {
            plot.showMessage(node.name + " is marked to be dropped");
        }

        try {
            byte[] payload = file.payload(node.key);
            if (node.isHistogram()) {
                RootHistogram h = RootHistogram.decode(payload, node.className);
                plot.showHistogram(h);
                inspector.showHistogram(h);
                plot.setStyle(currentStyle());
            } else if (node.isGraph()) {
                RootGraph g = RootGraph.decode(payload, node.className);
                plot.showGraph(g);
                inspector.showGraph(g);
                points.setSelected(true);
            } else {
                final String message = node.isTree()
                    ? node.name + " is a TTree. Its metadata is here; reading its "
                      + "branches needs the ROOT backend."
                    : node.className + " is not drawn by this viewer.";
                plot.showMessage(message);
                inspector.showMessage(message + "\n\n"
                    + String.format(Locale.ROOT, "%,d bytes on disk, %,d once read.",
                                    node.key.storedBytes(), node.key.objlen));
            }
            macro.setMacro(RootExport.macroFor(file.getPath(), node.pathFrom(root),
                                               node.className, currentStyle(),
                                               plot.isLogX(), plot.isLogY(),
                                               plot.getRebin()));
            setStatus(node.pathFrom(root) + "  --  " + node.className);
        } catch (RootFile.UnsupportedPayload unsupported) {
            final String message = node.name + " is compressed with "
                + unsupported.algorithm + ", which this viewer does not unpack.";
            plot.showMessage(message);
            inspector.showMessage(message);
            setStatus(message);
        } catch (IOException unreadable) {
            plot.showMessage(unreadable.getMessage());
            inspector.showMessage(String.valueOf(unreadable.getMessage()));
            setStatus(String.valueOf(unreadable.getMessage()));
        } catch (RuntimeException malformed) {
            final String message = node.name + " could not be decoded.";
            plot.showMessage(message);
            inspector.showMessage(message);
            setStatus(message);
        }
    }

    private RootPlot.Style currentStyle() {
        if (steps.isSelected()) {
            return RootPlot.Style.STEPS;
        }
        if (points.isSelected()) {
            return RootPlot.Style.POINTS;
        }
        if (line.isSelected()) {
            return RootPlot.Style.LINE;
        }
        return RootPlot.Style.BARS;
    }

    private void setRebin(int factor) {
        final int value = Math.max(1, Math.min(1024, factor));
        plot.setRebin(value);
        rebinLabel.setText(String.valueOf(value));
        RootHistogram effective = plot.effective();
        if (effective != null) {
            inspector.showHistogram(effective);
        }
    }

    private void setStatus(String text) {
        status.setText(text == null ? " " : text);
    }

    private void retitle() {
        if (titleListener != null) {
            titleListener.titleChanged();
        }
    }

    // ---- export ------------------------------------------------------------

    private void exportMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Plot as PNG...", () -> exportTo("png", "plot.png")));
        menu.add(item("Numbers as CSV...", () -> exportTo("csv", "values.csv")));
        menu.addSeparator();
        menu.add(item("Python script...", () -> exportTo("py", "plot.py")));
        menu.add(item("ROOT macro...", () -> exportTo("C", "macro.C")));
        menu.addSeparator();
        menu.add(item("Contents of the file...", () -> exportTo("txt", "contents.txt")));
        showUnderToolbar(menu);
    }

    private void exportTo(String kind, String suggested) {
        if (file == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(suggested));
        if (chooser.showSaveDialog(SwingUtilities.getWindowAncestor(this))
            != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        try {
            switch (kind) {
                case "png" -> RootExport.savePng(target, plot,
                                                 Math.max(640, plot.getWidth()),
                                                 Math.max(480, plot.getHeight()));
                case "csv" -> RootExport.saveText(target, plot.toCsv());
                case "py" -> RootExport.saveText(target, current == null
                    ? plot.toPython()
                    : RootExport.pythonFor(file.getPath(), current.pathFrom(root),
                                           current.className, plot.isLogY(),
                                           plot.getRebin()));
                case "C" -> RootExport.saveText(target, macro.getMacro());
                default -> RootExport.saveText(target, RootExport.report(file, root));
            }
            setStatus("written to " + target);
        } catch (IOException failed) {
            setStatus("could not write " + target + ": " + failed.getMessage());
        }
    }

    // ---- editing the file --------------------------------------------------

    private JPopupMenu nodeMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(item("Rename...", this::renameSelected));
        menu.add(item("Change the title...", this::retitleSelected));
        menu.add(item("Drop from the file", this::dropSelected));
        menu.add(item("Keep it after all", this::keepSelected));
        menu.addSeparator();
        menu.add(item("Save the file as...", this::saveAs));
        return menu;
    }

    private void editMenu() {
        JPopupMenu menu = nodeMenu();
        showUnderToolbar(menu);
    }

    private void showUnderToolbar(JPopupMenu menu) {
        menu.show(this, 40, 40);
    }

    private javax.swing.JMenuItem item(String label, Runnable action) {
        javax.swing.JMenuItem entry = new javax.swing.JMenuItem(label);
        entry.addActionListener(e -> action.run());
        return entry;
    }

    private void renameSelected() {
        if (current == null || current.key == null) {
            return;
        }
        String[] edit = renamed.getOrDefault(current,
            new String[] {current.name, current.title});
        String value = JOptionPane.showInputDialog(this, "New name for "
            + current.name, edit[0]);
        if (value == null || value.isBlank()) {
            return;
        }
        renamed.put(current, new String[] {value.trim(), edit[1]});
        tree.repaint();
        retitle();
        setStatus(current.name + " will be written as " + value.trim());
    }

    private void retitleSelected() {
        if (current == null || current.key == null) {
            return;
        }
        String[] edit = renamed.getOrDefault(current,
            new String[] {current.name, current.title});
        String value = JOptionPane.showInputDialog(this, "New title for "
            + current.name, edit[1]);
        if (value == null) {
            return;
        }
        renamed.put(current, new String[] {edit[0], value});
        tree.repaint();
        retitle();
    }

    private void dropSelected() {
        if (current == null || current.key == null) {
            return;
        }
        dropped.add(current);
        tree.repaint();
        retitle();
        setStatus(current.name + " will not be in the file that gets written");
    }

    private void keepSelected() {
        if (current == null) {
            return;
        }
        dropped.remove(current);
        renamed.remove(current);
        tree.repaint();
        retitle();
    }

    private void saveAs() {
        if (file == null || root == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("ROOT files", "root"));
        chooser.setSelectedFile(new File(
            file.getPath().getFileName().toString().replace(".root", "-edited.root")));
        if (chooser.showSaveDialog(SwingUtilities.getWindowAncestor(this))
            != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (target.equals(file.getPath())) {
            JOptionPane.showMessageDialog(this,
                "Choose another name: the file being read is not overwritten.",
                "Sphere ROOT", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            RootWriter.Dir top = new RootWriter.Dir(
                target.getFileName().toString().replace(".root", ""),
                "written by Sphere from " + file.getPath().getFileName());
            collect(root, top);
            if (top.total() == 0) {
                setStatus("nothing left to write");
                return;
            }
            RootWriter.write(target, top, file.getFormatVersion(),
                             file.getCompressionAlgorithm() * 100
                             + file.getCompressionLevel(),
                             file.streamerInfoRecord(),
                             file.streamerInfoObjectLength());
            renamed.clear();
            dropped.clear();
            retitle();
            setStatus(top.total() + " objects written to " + target);
        } catch (IOException failed) {
            setStatus("could not write " + target + ": " + failed.getMessage());
            JOptionPane.showMessageDialog(this, String.valueOf(failed.getMessage()),
                                          "Sphere ROOT", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void collect(RootNode from, RootWriter.Dir into) throws IOException {
        for (RootNode child : from.children) {
            if (dropped.contains(child)) {
                continue;
            }
            if (child.directory) {
                RootWriter.Dir sub = new RootWriter.Dir(child.name, child.title);
                collect(child, sub);
                if (sub.total() > 0) {
                    into.directories.add(sub);
                }
                continue;
            }
            if (child.key == null) {
                continue;
            }
            String[] edit = renamed.getOrDefault(child,
                new String[] {child.name, child.title});
            into.objects.add(new RootWriter.Entry(
                edit[0], edit[1], child.className,
                file.storedPayload(child.key), child.key.objlen, child.key.datime));
        }
    }

    // ---- the tree's look ---------------------------------------------------

    private final class NodeRenderer extends DefaultTreeCellRenderer {

        private static final Color DIRECTORY = new Color(0xFBBF24);
        private static final Color DRAWABLE = new Color(0x60A5FA);
        private static final Color OTHER = new Color(0x94A3B8);
        private static final Color GONE = new Color(0xF87171);

        @Override
        public Component getTreeCellRendererComponent(JTree source, Object value,
                boolean selected, boolean expanded, boolean leaf, int row,
                boolean focus) {
            super.getTreeCellRendererComponent(source, value, selected, expanded,
                                               leaf, row, focus);
            setIcon(null);
            setBackgroundNonSelectionColor(ImagingTheme.panel());
            setBackgroundSelectionColor(ImagingTheme.accent());
            setBorderSelectionColor(ImagingTheme.accent());
            setFont(ImagingTheme.uiFont(Font.PLAIN, 12f));

            if (!(value instanceof RootNode node)) {
                return this;
            }
            String[] edit = renamed.get(node);
            final String name = edit == null ? node.name : edit[0];
            final boolean out = dropped.contains(node);

            setText(node.directory || node.key == null
                    ? name
                    : name + "   " + node.className);
            setForeground(selected ? ImagingTheme.palette().getTextWhite()
                          : out ? GONE
                          : node.directory ? DIRECTORY
                          : (node.isHistogram() || node.isGraph()) ? DRAWABLE : OTHER);
            setToolTipText(node.title.isEmpty() ? null : node.title);
            return this;
        }
    }
}

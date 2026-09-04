package com.sphere.utils.settingsmanager;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.components.fileexplorerincludes.edSettingsFchooser;
import com.sphere.utils.SettingsManager;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class SettingsEditorPanel extends JPanel {

    private final SettingsFile model;
    private final JTree tree;
    private final JPanel detailPanel;
    private final Consumer<SettingsFile> snapshotConsumer;

    // Instance-managed renderer to fully eliminate previous static global states
    private final SettingsTreeCellRenderer treeRenderer = new SettingsTreeCellRenderer();

    // Undo / Redo stacks
    private final Deque<SettingsFile> undoStack = new ArrayDeque<>();
    private final Deque<SettingsFile> redoStack = new ArrayDeque<>();

    // Search Engine
    private final JTextField searchField;
    private final JLabel searchStatus;
    private final JButton prevBtn;
    private final JButton nextBtn;
    private final List<TreePath> searchResults = new ArrayList<>();
    private int searchIndex = -1;
    private String rawQuery = null;
    private Pattern searchPattern = null;
    private final boolean searchInValues = true;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    public SettingsEditorPanel(SettingsFile model, Consumer<SettingsFile> snapshotConsumer) {
        super(new BorderLayout());
        this.model = model;
        this.snapshotConsumer = snapshotConsumer;

        // Initial snapshot for undo
        SettingsFile snapshot = model.cloneSnapshot();
        undoStack.push(snapshot);

        this.tree = new JTree(new SettingsTreeModel(model));
        tree.setCellRenderer(treeRenderer);
        tree.setRowHeight(22);

        this.detailPanel = new JPanel(new CardLayout());
        if (palette != null) {
            detailPanel.setBackground(palette.getBackgroundSurface());
        }

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(tree),
                detailPanel
        );
        split.setDividerLocation(220);
        split.setBorder(null);

        // Search interface
        searchField = new JTextField();
        searchStatus = new JLabel(" ");
        prevBtn = new JButton("<");
        nextBtn = new JButton(">");

        if (palette != null) {
            searchField.setBackground(palette.getBackgroundSurface());
            searchField.setForeground(palette.getTextPrimary());
            searchField.setCaretColor(palette.getTextPrimary());
            searchField.setSelectionColor(palette.getAccent());
            searchField.setSelectedTextColor(palette.getTextWhite());
            searchField.setBorder(BorderFactory.createLineBorder(palette.getBorder()));
            searchStatus.setOpaque(true);
            searchStatus.setBackground(palette.getBackgroundTrack());
            searchStatus.setForeground(palette.getTextPrimary());
            searchStatus.setBorder(BorderFactory.createLineBorder(palette.getBorder()));
        }
        searchField.setToolTipText("Search (supports *, regex:, starts:, ends:). Live query processing.");

        styleButton(prevBtn);
        styleButton(nextBtn);

        prevBtn.addActionListener(e -> navigateSearch(-1));
        nextBtn.addActionListener(e -> navigateSearch(+1));

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { performSearch(); }
            @Override public void removeUpdate(DocumentEvent e) { performSearch(); }
            @Override public void changedUpdate(DocumentEvent e) { /* not used for plain text */ }
        });

        JPanel top = new JPanel(new BorderLayout());
        if (palette != null) top.setBackground(palette.getBackgroundSurface());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        if (palette != null) right.setBackground(palette.getBackgroundSurface());
        right.add(prevBtn);
        right.add(nextBtn);
        right.add(searchStatus);

        JLabel searchLabel = new JLabel(" Search: ");
        if (palette != null) searchLabel.setForeground(palette.getTextPrimary());
        top.add(searchLabel, BorderLayout.WEST);
        top.add(searchField, BorderLayout.CENTER);
        top.add(right, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        hookSelection();
        installContextMenu();
    }

    // ---------- Undo / Redo ----------

    private void pushSnapshot() {
        SettingsFile snapshot = model.cloneSnapshot();
        undoStack.push(snapshot);
        redoStack.clear();
        if (snapshotConsumer != null) {
            snapshotConsumer.accept(snapshot.cloneSnapshot());
        }
    }

    public boolean canUndo() {
        return undoStack.size() > 1;
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public void undo() {
        if (!canUndo()) return;
        SettingsFile current = undoStack.pop();
        redoStack.push(current.cloneSnapshot());
        SettingsFile previous = undoStack.peek();
        if (previous != null) {
            model.copyFrom(previous);
            refreshFromModel();
        }
    }

    public void redo() {
        if (!canRedo()) return;
        SettingsFile next = redoStack.pop();
        undoStack.push(next.cloneSnapshot());
        model.copyFrom(next);
        refreshFromModel();
    }

    // ---------- Search ----------

    private void parseQuery(String q) {
        rawQuery = q;
        searchPattern = null;

        if (q == null || q.isBlank()) {
            treeRenderer.setSearchContext(null);
            return;
        }

        String s = q.trim();
        String queryForHighlight;

        // Wildcard: safer handling of '*' segments
        if (s.contains("*") && !s.startsWith("regex:")) {
            String[] tokens = s.split("\\*", -1);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokens.length; i++) {
                sb.append(Pattern.quote(tokens[i]));
                if (i < tokens.length - 1) sb.append(".*");
            }
            searchPattern = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
            queryForHighlight = s.replace("*", "");
        } 
        // Regex
        else if (s.startsWith("regex:")) {
            String body = s.substring("regex:".length());
            try {
                searchPattern = Pattern.compile(body, Pattern.CASE_INSENSITIVE);
            } catch (Exception ignored) {
                searchPattern = null;
            }
            queryForHighlight = body;
        } 
        // Starts
        else if (s.startsWith("starts:")) {
            String body = s.substring("starts:".length());
            String regex = "^" + Pattern.quote(body);
            searchPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            queryForHighlight = body;
        } 
        // Ends
        else if (s.startsWith("ends:")) {
            String body = s.substring("ends:".length());
            String regex = Pattern.quote(body) + "$";
            searchPattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            queryForHighlight = body;
        } 
        // Substring
        else {
            searchPattern = Pattern.compile(Pattern.quote(s), Pattern.CASE_INSENSITIVE);
            queryForHighlight = s;
        }

        treeRenderer.setSearchContext(new SearchContext(queryForHighlight, null));
    }

    private boolean matchesNode(SettingsTreeNode node) {
        if (searchPattern == null) return false;
        String text = node.toString();
        if (searchPattern.matcher(text).find()) return true;

        if (searchInValues && node.getType() == SettingsNodeType.KEY) {
            Object uo = node.getUserObject();
            if (uo instanceof KeyValue kv) {
                String v = kv.value();
                if (v != null && searchPattern.matcher(v).find()) return true;
            }
        }
        return false;
    }

    private void performSearch() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::performSearch);
            return;
        }

        String q = searchField.getText();
        searchResults.clear();
        searchIndex = -1;

        parseQuery(q);

        if (q == null || q.isBlank() || searchPattern == null) {
            searchStatus.setText(" ");
            tree.repaint();
            return;
        }

        DefaultTreeModel modelTree = (DefaultTreeModel) tree.getModel();
        SettingsTreeNode root = (SettingsTreeNode) modelTree.getRoot();

        collectMatches(new TreePath(root));

        if (searchResults.isEmpty()) {
            searchStatus.setText(" 0 matches ");
            expandCollapseForSearch(false);
            tree.repaint();
            return;
        }

        searchIndex = 0;
        showSearchResult();
        searchStatus.setText(" " + (searchIndex + 1) + " / " + searchResults.size() + " ");
        expandCollapseForSearch(true);
        tree.repaint();
    }

    private void collectMatches(TreePath parent) {
        SettingsTreeNode node = (SettingsTreeNode) parent.getLastPathComponent();

        if (matchesNode(node)) {
            searchResults.add(parent);
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            SettingsTreeNode child = (SettingsTreeNode) node.getChildAt(i);
            collectMatches(parent.pathByAddingChild(child));
        }
    }

    private void navigateSearch(int direction) {
        if (searchResults.isEmpty()) return;

        searchIndex += direction;

        if (searchIndex < 0) searchIndex = searchResults.size() - 1;
        if (searchIndex >= searchResults.size()) searchIndex = 0;

        TreePath path = searchResults.get(searchIndex);
        String highlightQuery = treeRenderer.getSearchContext() != null ? treeRenderer.getSearchContext().query() : "";
        treeRenderer.setSearchContext(new SearchContext(highlightQuery, path));

        tree.setSelectionPath(path);
        tree.scrollPathToVisible(path);

        searchStatus.setText(" " + (searchIndex + 1) + " / " + searchResults.size() + " ");
        tree.repaint();
    }

    private void showSearchResult() {
        if (searchIndex >= 0 && searchIndex < searchResults.size()) {
            TreePath path = searchResults.get(searchIndex);
            String highlightQuery = treeRenderer.getSearchContext() != null ? treeRenderer.getSearchContext().query() : "";
            treeRenderer.setSearchContext(new SearchContext(highlightQuery, path));
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    private void expandCollapseForSearch(boolean hasMatches) {
        if (!hasMatches) {
            for (int i = 0; i < tree.getRowCount(); i++) {
                tree.expandRow(i);
            }
            return;
        }
        for (TreePath match : searchResults) {
            TreePath current = match;
            while (current != null) {
                tree.expandPath(current);
                current = current.getParentPath();
            }
        }
    }

    // ---------- Selection / Detail Panel ----------

    private void hookSelection() {
        tree.addTreeSelectionListener(e -> {
            Object nodeObj = tree.getLastSelectedPathComponent();
            if (!(nodeObj instanceof SettingsTreeNode node)) return;

            detailPanel.removeAll();
            CardLayout cl = (CardLayout) detailPanel.getLayout();

            switch (node.getType()) {
                case ROOT     -> detailPanel.add(buildRootPanel(), "ROOT");
                case CATEGORY -> detailPanel.add(buildCategoryPanel(node), "CATEGORY");
                case KEY      -> detailPanel.add(buildKeyPanel(node), "KEY");
            }

            cl.show(detailPanel, node.getType().name());
            detailPanel.revalidate();
            detailPanel.repaint();
        });
    }

    private Component buildRootPanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        if (palette != null) p.setBackground(palette.getBackgroundSurface());
        JLabel label = new JLabel("Configuration target file: " + SettingsManager.CONFIG_FILENAME);
        if (palette != null) label.setForeground(palette.getTextPrimary());
        p.add(label);
        return p;
    }

    private Component buildCategoryPanel(SettingsTreeNode node) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        if (palette != null) p.setBackground(palette.getBackgroundSurface());
        JLabel label = new JLabel("Category: [" + node.getUserObject() + "]");
        if (palette != null) label.setForeground(palette.getTextPrimary());
        p.add(label);
        return p;
    }

    private Component buildKeyPanel(SettingsTreeNode node) {
        KeyValue kv = (KeyValue) node.getUserObject();
        JPanel panel = new JPanel(new GridBagLayout());
        if (palette != null) panel.setBackground(palette.getBackgroundSurface());

        JTextField keyField = new JTextField(kv.key());
        JTextField valueField = new JTextField(kv.value());

        if (palette != null) {
            for (JTextField f : new JTextField[]{keyField, valueField}) {
                f.setBackground(palette.getBackgroundSurface());
                f.setForeground(palette.getTextPrimary());
                f.setCaretColor(palette.getTextPrimary());
                f.setSelectionColor(palette.getAccent());
                f.setSelectedTextColor(palette.getTextWhite());
                f.setBorder(BorderFactory.createLineBorder(palette.getBorder()));
                f.setOpaque(true);
            }
        }

        JLabel previewLabel = new JLabel();
        if (palette != null) {
            previewLabel.setForeground(palette.getTextSecondary());
        }
        updatePreview(previewLabel, kv.value(), node);

        // Safely boxed local timer instance decoupled from panel bounds scope
        final Timer[] localTimer = new Timer[1];

        valueField.getDocument().addDocumentListener(new DocumentListener() {
            private void trigger() {
                if (localTimer[0] != null) {
                    localTimer[0].stop();
                }
                localTimer[0] = new Timer(250, ev -> updatePreview(previewLabel, valueField.getText(), node));
                localTimer[0].setRepeats(false);
                localTimer[0].start();
            }
            @Override public void insertUpdate(DocumentEvent e) { trigger(); }
            @Override public void removeUpdate(DocumentEvent e) { trigger(); }
            @Override public void changedUpdate(DocumentEvent e) { /* not used */ }
        });

        JButton browseButton = new JButton("Browse...");
        styleButton(browseButton);

        browseButton.addActionListener(ae -> {
            Window parentWindow = SwingUtilities.getWindowAncestor(panel);
            javax.swing.tree.TreeNode parentNode = node.getParent();
            if (parentNode == null) return;

            String category = parentNode.toString();
            String keyName = kv.key();
            
            boolean expectsPath = isPathKey(category, keyName);
            boolean expectsExecutable = expectsPath && isExecutableKey(category, keyName);

            File selected = null;
            boolean approved = false;

            // Use our dedicated directory chooser for directory path settings
            if (expectsPath && !expectsExecutable) {
                edSettingsFchooser dirChooser = new edSettingsFchooser(parentWindow, new File("."));
                int result = dirChooser.showDialog();
                if (result == edSettingsFchooser.APPROVE_OPTION) {
                    selected = dirChooser.getSelectedDirectory();
                    approved = true;
                }
            } else {
                // Fall back to a standard system file chooser to select specific executable files
                JFileChooser fileChooser = new JFileChooser(new File("."));
                fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                fileChooser.setDialogTitle("Select Executable File");
                
                int result = fileChooser.showOpenDialog(parentWindow);
                if (result == JFileChooser.APPROVE_OPTION) {
                    selected = fileChooser.getSelectedFile();
                    approved = true;
                }
            }

            if (approved && selected != null && selected.exists()) {
                if (expectsPath) {
                    if (expectsExecutable && selected.isDirectory()) {
                        JOptionPane.showMessageDialog(panel,
                                "This configuration variable expects an executable file, not a directory.",
                                "Invalid Selection",
                                JOptionPane.WARNING_MESSAGE);
                        return;
                    } else if (!expectsExecutable && !selected.isDirectory()) {
                        JOptionPane.showMessageDialog(panel,
                                "This configuration variable expects a directory path.",
                                "Invalid Selection",
                                JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                }
                valueField.setText(selected.getAbsolutePath());
            }
        });

        JButton saveButton = new JButton("Apply");
        styleButton(saveButton);
        saveButton.setPreferredSize(new Dimension(80, 26));

        saveButton.addActionListener(ae -> {
            javax.swing.tree.TreeNode parentNode = node.getParent();
            if (parentNode == null) return;

            String newKey = sanitizeIdentifier(keyField.getText());
            String newVal = valueField.getText().trim();
            String category = parentNode.toString();

            keyField.setText(newKey);

            if (!SettingsValidator.validate(category, newKey, newVal)) {
                JOptionPane.showMessageDialog(panel,
                        "Validation constraints rejected the modification under category " + category,
                        "Validation failure",
                        JOptionPane.ERROR_MESSAGE);
                return;
            }

            pushSnapshot();

            var entries = model.entries(category);
            if (kv.index() >= 0 && kv.index() < entries.size()) {
                entries.get(kv.index()).setKey(newKey);
                entries.get(kv.index()).setValue(newVal);
            }

            node.setUserObject(new KeyValue(category, newKey, newVal, kv.index()));
            ((DefaultTreeModel) tree.getModel()).nodeChanged(node);
            updatePreview(previewLabel, newVal, node);
        });

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        JLabel labelKey = new JLabel("Key:");
        if (palette != null) labelKey.setForeground(palette.getTextPrimary());
        c.gridx = 0; c.gridy = 0;
        panel.add(labelKey, c);
        c.gridx = 1; c.weightx = 1.0;
        panel.add(keyField, c);

        JLabel labelVal = new JLabel("Value:");
        if (palette != null) labelVal.setForeground(palette.getTextPrimary());
        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        panel.add(labelVal, c);
        c.gridx = 1; c.weightx = 1.0;
        panel.add(valueField, c);
        c.gridx = 2; c.weightx = 0;
        panel.add(browseButton, c);

        c.gridx = 1; c.gridy = 2; c.gridwidth = 2; c.weightx = 0;
        panel.add(previewLabel, c);

        c.gridx = 2; c.gridy = 3; c.gridwidth = 1; c.weightx = 0;
        c.anchor = GridBagConstraints.EAST;
        c.fill = GridBagConstraints.NONE;
        panel.add(saveButton, c);

        return panel;
    }

    private boolean isExecutableKey(String category, String key) {
        String k = key.toUpperCase();
        return k.contains("EXEC") || k.contains("COMPILER") || k.contains("RUNTIME") || k.contains("BIN");
    }

    private boolean isPathKey(String category, String key) {
        String k = key.toUpperCase();
        String c = category.toUpperCase();
        return k.contains("PATH") || c.contains("PATH") || k.contains("DIR");
    }

    /**
     * Says what the value will mean when Sphere reads it. The check used to test
     * the raw text against the local filesystem, so a Windows path always read as
     * broken under Linux and a /mnt/c path always read as broken under Windows,
     * on a file meant to travel between both.
     */
    private void updatePreview(JLabel label, String value, SettingsTreeNode node) {
        String category = "";
        if (node != null) {
            javax.swing.tree.TreeNode parentNode = node.getParent();
            if (parentNode != null) {
                category = parentNode.toString();
            }
        }

        if (value == null || value.isBlank()) {
            label.setText(SettingsFile.isSingleValued(category)
                ? "Empty: this backend is disabled"
                : "Empty value");
            if (palette != null) label.setForeground(palette.getTextSecondary());
            return;
        }

        boolean pathCategory = "SYSTEM_PATH".equals(category)
                            || "TERMINAL_CONFIG".equals(category)
                            || "SYSTEM".equals(category);
        if (!pathCategory) {
            label.setText("Value literal assignment active");
            if (palette != null) label.setForeground(palette.getTextSecondary());
            return;
        }

        final String finalValue = value.trim();
        if (finalValue.matches(".*\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?.*")) {
            label.setText("Resolved when read, against the file and the environment");
            if (palette != null) label.setForeground(palette.getTextSecondary());
            return;
        }

        new Thread(() -> {
            String localText;
            Color localColor;
            try {
                // Translated into this platform's notation first, the same way the
                // rest of Sphere reads it.
                String here = com.sphere.utils.SettingsManager.toNativePath(finalValue);
                if (here == null) {
                    localText = "Valid elsewhere, no meaning on "
                              + com.sphere.utils.OSValidator.current();
                    localColor = palette != null ? palette.getLogWarnPrefix() : Color.ORANGE;
                } else {
                    String expanded = here.startsWith("~")
                        ? System.getProperty("user.home") + here.substring(1) : here;
                    localText = Files.exists(Path.of(expanded))
                        ? "Found here: " + expanded
                        : "Not found here: " + expanded;
                    localColor = Files.exists(Path.of(expanded))
                        ? (palette != null ? palette.getSuccess() : Color.GREEN)
                        : (palette != null ? palette.getLogWarnPrefix() : Color.ORANGE);
                }
            } catch (InvalidPathException ex) {
                localText = "Malformed path syntax";
                localColor = palette != null ? palette.getError() : Color.RED;
            } catch (Exception ex) {
                localText = "Path verification failed";
                localColor = palette != null ? palette.getError() : Color.RED;
            }

            final String uiText = localText;
            final Color uiColor = localColor;
            SwingUtilities.invokeLater(() -> {
                label.setText(uiText);
                label.setForeground(uiColor);
            });
        }).start();
    }

    private void styleButton(JButton b) {
        if (palette == null) return;

        b.setBackground(palette.getButtonBase());
        b.setForeground(palette.getTextPrimary());
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createLineBorder(palette.getBorder()));
        b.setContentAreaFilled(true);

        b.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseEntered(java.awt.event.MouseEvent e) { b.setBackground(palette.getButtonHover()); }
            @Override public void mouseExited(java.awt.event.MouseEvent e) { b.setBackground(palette.getButtonBase()); }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { b.setBackground(palette.getButtonPressed()); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { b.setBackground(palette.getButtonHover()); }
        });
    }

    // ---------- CRUD API ----------

    public void addCategory() {
        String name = JOptionPane.showInputDialog(this, "Define configuration category name:");
        if (name == null || name.isBlank()) return;

        name = sanitizeIdentifier(name);

        if (model.hasCategory(name)) {
            JOptionPane.showMessageDialog(this, "Category already exists.", "Collision error", JOptionPane.WARNING_MESSAGE);
            return;
        }

        pushSnapshot();
        model.addCategory(name);
        reloadTree();
    }

    public void addKey(SettingsTreeNode categoryNode) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;

        JTextField keyField = new JTextField();
        JTextField valueField = new JTextField();

        // --- KEY ---
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 0;
        panel.add(new JLabel("Key:"), c);

        c.gridx = 1;
        c.weightx = 1.0;
        panel.add(keyField, c);

        // --- VALUE ---
        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        panel.add(new JLabel("Value:"), c);

        c.gridx = 1;
        c.weightx = 1.0;
        panel.add(valueField, c);

        // --- Button BROWSE ---
        JButton browseButton = new JButton("Browse…");
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        panel.add(browseButton, c);

        // Button Browse Action
        browseButton.addActionListener(ae -> {
            Window parentWindow = SwingUtilities.getWindowAncestor(panel);
            String category = categoryNode.getUserObject().toString();
            String keyName = keyField.getText().trim();

            boolean expectsPath = isPathKey(category, keyName);
            boolean expectsExecutable = expectsPath && isExecutableKey(category, keyName);

            File selected = null;
            boolean approved = false;

            // Use our dedicated directory chooser for directory path settings
            if (expectsPath && !expectsExecutable) {
                edSettingsFchooser dirChooser = new edSettingsFchooser(parentWindow, new File("."));
                int result = dirChooser.showDialog();
                if (result == edSettingsFchooser.APPROVE_OPTION) {
                    selected = dirChooser.getSelectedDirectory();
                    approved = true;
                }
            } else {
                // Fall back to a standard system file chooser to select specific executable files
                JFileChooser fileChooser = new JFileChooser(new File("."));
                fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                fileChooser.setDialogTitle("Select Executable File");
                
                int result = fileChooser.showOpenDialog(parentWindow);
                if (result == JFileChooser.APPROVE_OPTION) {
                    selected = fileChooser.getSelectedFile();
                    approved = true;
                }
            }

            if (approved && selected != null && selected.exists()) {
                if (expectsPath) {
                    if (expectsExecutable && selected.isDirectory()) {
                        JOptionPane.showMessageDialog(panel,
                                "This configuration variable expects an executable file, not a directory.",
                                "Invalid Selection",
                                JOptionPane.WARNING_MESSAGE);
                        return;
                    } else if (!expectsExecutable && !selected.isDirectory()) {
                        JOptionPane.showMessageDialog(panel,
                                "This configuration variable expects a directory path.",
                                "Invalid Selection",
                                JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                }
                valueField.setText(selected.getAbsolutePath());
            }
        });

        // --- Creation Key/Value Size ---
        panel.setPreferredSize(new Dimension(520, 150));

        int result = JOptionPane.showConfirmDialog(
                this,
                panel,
                "Create new configuration key",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );

        if (result != JOptionPane.OK_OPTION) return;

        String key = sanitizeIdentifier(keyField.getText());
        String value = valueField.getText().trim();

        if (key.isBlank()) return;

        // Validation
        String category = categoryNode.getUserObject().toString();
        if (!SettingsValidator.validate(category, key, value)) {
            JOptionPane.showMessageDialog(panel,
                    "Validation constraints rejected the new key/value pair.",
                    "Validation failure",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        pushSnapshot();
        model.addEntry(category, key, value);

        reloadTree();
    }



    public void renameCategory(SettingsTreeNode node) {
        String oldName = node.getUserObject().toString();
        String newName = JOptionPane.showInputDialog(this, "Modify category name:", oldName);
        if (newName == null || newName.isBlank()) return;

        newName = sanitizeIdentifier(newName);

        pushSnapshot();
        model.renameCategory(oldName, newName);
        reloadTree();
    }

    public void deleteCategory(SettingsTreeNode node) {
        String name = node.getUserObject().toString();

        pushSnapshot();
        model.removeCategory(name);
        reloadTree();
    }

    public void renameKey(SettingsTreeNode node) {
        javax.swing.tree.TreeNode parentNode = node.getParent();
        if (parentNode == null) return;

        KeyValue kv = (KeyValue) node.getUserObject();
        String newKey = JOptionPane.showInputDialog(this, "Modify key name:", kv.key());
        if (newKey == null || newKey.isBlank()) return;

        newKey = sanitizeIdentifier(newKey);

        pushSnapshot();
        String category = parentNode.toString();
        var entries = model.entries(category);
        if (kv.index() >= 0 && kv.index() < entries.size()) {
            entries.get(kv.index()).setKey(newKey);
        }
        reloadTree();
    }

    public void deleteKey(SettingsTreeNode node) {
        javax.swing.tree.TreeNode parentNode = node.getParent();
        if (parentNode == null) return;

        KeyValue kv = (KeyValue) node.getUserObject();
        String category = parentNode.toString();

        pushSnapshot();
        model.removeEntry(category, kv.index());
        reloadTree();
    }

    private void reloadTree() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reloadTree);
            return;
        }

        tree.setModel(new SettingsTreeModel(model));
        tree.setCellRenderer(treeRenderer);
        tree.clearSelection();
        tree.expandRow(0);

        performSearch();
    }

    public void refreshFromModel() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refreshFromModel);
            return;
        }
        reloadTree();
        detailPanel.removeAll();
        detailPanel.revalidate();
        detailPanel.repaint();
    }

    private void installContextMenu() {
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (!SwingUtilities.isRightMouseButton(e)) return;

                TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                if (path == null) return;

                tree.setSelectionPath(path);
                SettingsTreeNode node = (SettingsTreeNode) path.getLastPathComponent();

                JPopupMenu menu = ContextMenuBuilderSettings.create(SettingsEditorPanel.this, node);
                menu.show(tree, e.getX(), e.getY());
            }
        });
    }

    private String sanitizeIdentifier(String input) {
        if (input == null) return "";
        return input.trim()
                .replaceAll("\\s+", "_");
    }
}

package com.sphere.components.fileexplorerincludes;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.ui.SPButtonUI;
import com.sphere.utils.IconManager;

import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.io.File;
import java.util.Enumeration;

/**
 * Main panel for the custom FlatFileChooser.
 * Enhanced with an intuitive directory tree navigation and fluent column file layout.
 */
class FlatFileChooserPanel extends JPanel {

    private final FileSystemView fsv = FileSystemView.getFileSystemView();
    private File currentDir;

    private final FlatPathBar pathBar;
    private final FlatFileListModel listModel;
    private final JList<File> fileList;
    private final JTree directoryTree;

    private final JTextField addressField;
    private final JButton goAddressButton;

    private final JButton createFolderButton;
    private final JButton renameButton;
    private final JButton deleteButton;
    private final JButton openButton;
    private final JButton cancelButton;
    private final FlatFileChooser parentDialog;

    private final JSplitPane splitPane;
    private final JPanel actionRowPanel;

    private File selectedFile;
    private FlatFileFilter filter = null;

    // Flag to prevent selection loop updates between tree listener and manual navigation
    private boolean isAdjustingTreeSelection = false;

    FlatFileChooserPanel(File initialDir, FlatFileChooser parentDialog) {
        this.parentDialog = parentDialog;
        ThemePalette palette = ThemeManager.getCurrentPalette();

        if (initialDir == null || !initialDir.isDirectory()) {
            initialDir = fsv.getHomeDirectory();
        }
        this.currentDir = initialDir;

        setLayout(new BorderLayout());
        setBackground(palette.getBackgroundSurface());

        /* 1. Top Section: Unified Path Bar Container with Folder Actions */
        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.setOpaque(false);
        
        pathBar = new FlatPathBar(this);
        pathBar.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 8));
        topContainer.add(pathBar, BorderLayout.CENTER);
        
        JPanel topActionWrapper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        topActionWrapper.setOpaque(false);
        topActionWrapper.setBorder(BorderFactory.createEmptyBorder(12, 0, 12, 16));

        createFolderButton = new JButton();
        createFolderButton.setUI((SPButtonUI) SPButtonUI.createUI(createFolderButton));
        createFolderButton.setIcon(IconManager.getIcon("createfolder.png"));
        createFolderButton.setToolTipText("Create New Folder");
        createFolderButton.setPreferredSize(new Dimension(32, 32));
        createFolderButton.addActionListener(e -> performCreateFolderAction());
        topActionWrapper.add(createFolderButton);

        renameButton = new JButton();
        renameButton.setUI((SPButtonUI) SPButtonUI.createUI(renameButton));
        renameButton.setIcon(IconManager.getIcon("renamefolder.png"));
        renameButton.setToolTipText("Rename Selected Item");
        renameButton.setPreferredSize(new Dimension(32, 32));
        renameButton.addActionListener(e -> performRenameAction());
        topActionWrapper.add(renameButton);

        deleteButton = new JButton();
        deleteButton.setUI((SPButtonUI) SPButtonUI.createUI(deleteButton));
        deleteButton.setIcon(IconManager.getIcon("deletefolder.png"));
        deleteButton.setToolTipText("Delete Selected Item");
        deleteButton.setPreferredSize(new Dimension(32, 32));
        deleteButton.addActionListener(e -> performDeleteAction());
        topActionWrapper.add(deleteButton);

        topContainer.add(topActionWrapper, BorderLayout.EAST);
        topContainer.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, palette.getBorder()));

        /* 1b. Address Input Row: Fast Manual Path Navigation (WSL & UNC Compatible) */
        addressField = new JTextField();
        addressField.setFont(com.sphere.fonts.FontLoader.getGlobalFont(Font.PLAIN, 12));
        addressField.setForeground(palette.getTextPrimary());
        addressField.setBackground(palette.getBackgroundSurface());
        addressField.setCaretColor(palette.getTextPrimary());
        
        // FIX: Configure accessible selection contrast colors
        addressField.setSelectionColor(palette.getAccent());
        addressField.setSelectedTextColor(palette.getTextWhite());
        
        addressField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(palette.getBorder(), 1, true),
            BorderFactory.createEmptyBorder(5, 8, 5, 8)
        ));
        addressField.addActionListener(e -> triggerAddressBarNavigation());

        goAddressButton = new JButton();
        goAddressButton.setUI((SPButtonUI) SPButtonUI.createUI(goAddressButton));
        goAddressButton.setIcon(IconManager.getIcon("arrow_right.png"));
        goAddressButton.setToolTipText("Navigate to Path");
        goAddressButton.setPreferredSize(new Dimension(32, 28));
        goAddressButton.addActionListener(e -> triggerAddressBarNavigation());

        JPanel addressBarWrapper = new JPanel(new BorderLayout(8, 0));
        addressBarWrapper.setOpaque(false);
        addressBarWrapper.setBorder(BorderFactory.createEmptyBorder(0, 16, 10, 16));
        addressBarWrapper.add(addressField, BorderLayout.CENTER);
        addressBarWrapper.add(goAddressButton, BorderLayout.EAST);

        // Header compilation layout assembly
        JPanel headerStackPanel = new JPanel(new BorderLayout());
        headerStackPanel.setOpaque(false);
        headerStackPanel.add(topContainer, BorderLayout.NORTH);
        headerStackPanel.add(addressBarWrapper, BorderLayout.SOUTH);
        add(headerStackPanel, BorderLayout.NORTH);

        /* 2. Central Section: Main Workspace File Browser (COLUMN GRID MODE) */
        listModel = new FlatFileListModel(currentDir);
        fileList = new JList<>(listModel);
        fileList.setCellRenderer(new FlatFileRenderer());
        fileList.setOpaque(false);
        fileList.setForeground(palette.getTextPrimary());
        fileList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        fileList.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        fileList.setFocusable(true);

        fileList.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        fileList.setVisibleRowCount(-1);

        UIManager.put("List.focusCellHighlightBorder", BorderFactory.createEmptyBorder());
        UIManager.put("List.focusSelectedCellHighlightBorder", BorderFactory.createEmptyBorder());
        UIManager.put("List.noFocusBorder", BorderFactory.createEmptyBorder());

        // Mouse Motion Listening
        fileList.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                int index = fileList.locationToIndex(e.getPoint());
                if (index >= 0) {
                    Rectangle bounds = fileList.getCellBounds(index, index);
                    if (bounds != null && bounds.contains(e.getPoint())) {
                        fileList.putClientProperty("hoverIndex", index);
                    } else {
                        fileList.putClientProperty("hoverIndex", -1);
                    }
                } else {
                    fileList.putClientProperty("hoverIndex", -1);
                }
                fileList.repaint();
            }
        });

        fileList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                fileList.putClientProperty("hoverIndex", -1);
                fileList.repaint();
            }
        });

        fileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selectedFile = fileList.getSelectedValue();
            }
        });

        fileList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    File selected = fileList.getSelectedValue();
                    if (selected != null && selected.isDirectory()) {
                        setDirectory(selected);
                    }
                }
            }
        });

        /* 3. Left Side Navigation: Directory Tree (Cross-Platform Safe) */
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Computer");
        initTreeRoots(rootNode);
        
        directoryTree = new JTree(rootNode);
        directoryTree.setOpaque(false);
        directoryTree.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        directoryTree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        
        directoryTree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            {
                Icon pFolder = com.sphere.utils.IconManager.getIcon("pfolder.png");
                if (pFolder != null) {
                    setLeafIcon(pFolder);
                    setOpenIcon(pFolder);
                    setClosedIcon(pFolder);
                }
            }

            @Override
            public Component getTreeCellRendererComponent(
                    JTree tree, Object value, boolean sel, boolean expanded,
                    boolean leaf, int row, boolean hasFocus) {
                
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                
                com.sphere.theme.ThemePalette palette = com.sphere.theme.ThemeManager.getCurrentPalette();
                if (palette != null) {
                    setForeground(sel ? palette.getTextWhite() : palette.getTextPrimary());
                    setBackgroundSelectionColor(palette.getButtonPressed());
                    setBackgroundNonSelectionColor(null);
                }
                
                if (value instanceof DefaultMutableTreeNode) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                    Object userObj = node.getUserObject();
                    
                    if (userObj instanceof java.io.File || "Computer".equals(userObj)) {
                        setIcon(com.sphere.utils.IconManager.getIcon("pfolder.png"));
                    }
                }
                
                return this;
            }
        });
        
        directoryTree.addTreeSelectionListener(e -> {
            if (isAdjustingTreeSelection) return;
            
            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) directoryTree.getLastSelectedPathComponent();
            if (selectedNode != null && selectedNode.getUserObject() instanceof File) {
                File targetDir = (File) selectedNode.getUserObject();
                if (targetDir.isDirectory()) {
                    setDirectory(targetDir);
                }
            }
        });

        /* 4. Central Layout Integration via JSplitPane */
        JScrollPane listScrollPane = new JScrollPane(fileList);
        listScrollPane.getViewport().setOpaque(false);
        listScrollPane.setOpaque(false);
        listScrollPane.setBorder(BorderFactory.createEmptyBorder());
        listScrollPane.setViewportBorder(BorderFactory.createEmptyBorder());

        JScrollPane treeScrollPane = new JScrollPane(directoryTree);
        treeScrollPane.getViewport().setOpaque(false);
        treeScrollPane.setOpaque(false);
        treeScrollPane.setBorder(BorderFactory.createEmptyBorder());
        treeScrollPane.setViewportBorder(BorderFactory.createEmptyBorder());

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScrollPane, listScrollPane);
        splitPane.setDividerLocation(220); 
        splitPane.setDividerSize(1);       
        splitPane.setOpaque(false);
        splitPane.setBorder(BorderFactory.createEmptyBorder());

        add(splitPane, BorderLayout.CENTER);

        /* 5. Bottom Section: Action Controls Layout Row */
        actionRowPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actionRowPanel.setBackground(palette.getBackgroundSurface());
        actionRowPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, palette.getBorder()),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)
        ));

        openButton = new JButton("Open");
        openButton.setUI((SPButtonUI) SPButtonUI.createUI(openButton));
        openButton.setPreferredSize(new Dimension(100, 32));

        cancelButton = new JButton("Cancel");
        cancelButton.setUI((SPButtonUI) SPButtonUI.createUI(cancelButton));
        cancelButton.setPreferredSize(new Dimension(100, 32));

        openButton.addActionListener(e -> performOpenAction());
        cancelButton.addActionListener(e -> {
            this.selectedFile = null;
            if (fileList != null) {
                fileList.clearSelection();
            }
            if (parentDialog != null) {
                parentDialog.cancelSelection();
            }
        });

        actionRowPanel.add(openButton);
        actionRowPanel.add(cancelButton);
        add(actionRowPanel, BorderLayout.SOUTH);

        refresh();
    }

    /**
     * Platform-safe root file indexing system initialization.
     * U.S. English fallback comments maintained.
     */
    private void initTreeRoots(DefaultMutableTreeNode rootNode) {
        File[] roots = File.listRoots();
        if (roots == null) return;

        boolean isUnix = !System.getProperty("os.name").toLowerCase().contains("win");

        if (isUnix) {
            // Linux, macOS, and WSL share a unified singular '/' root system
            for (File root : roots) {
                DefaultMutableTreeNode systemRootNode = new DefaultMutableTreeNode(root) {
                    @Override
                    public String toString() {
                        return "/"; // Explicitly visual mapping for the root mount
                    }
                };
                rootNode.add(systemRootNode);
                
                // Pre-populate level 1 directories (e.g., /home, /var, /etc, /Users)
                File[] subFiles = root.listFiles(File::isDirectory);
                if (subFiles != null) {
                    for (File sub : subFiles) {
                        if (!sub.isHidden()) {
                            systemRootNode.add(new DefaultMutableTreeNode(sub) {
                                @Override
                                public String toString() { return ((File)getUserObject()).getName(); }
                            });
                        }
                    }
                }
            }
        } else {
            // Standard Windows processing stack logic (C:\, D:\, etc.)
            for (File root : roots) {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(root) {
                    @Override
                    public String toString() {
                        String name = fsv.getSystemDisplayName((File) getUserObject());
                        return (name != null && !name.isEmpty()) ? name : ((File) getUserObject()).getAbsolutePath();
                    }
                };
                rootNode.add(node);
                File[] subFiles = root.listFiles(File::isDirectory);
                if (subFiles != null) {
                    for (File sub : subFiles) {
                        if (!sub.isHidden()) {
                            node.add(new DefaultMutableTreeNode(sub) {
                                @Override
                                public String toString() { return ((File)getUserObject()).getName(); }
                            });
                        }
                    }
                }
            }
        }
    }

    /**
     * Processes programmatic manual string inputs typed or pasted directly into the address text field.
     */
    private void triggerAddressBarNavigation() {
        String inputPath = addressField.getText();
        if (inputPath == null || inputPath.isBlank()) {
            return;
        }

        inputPath = inputPath.replace("\"", "").trim();
        File targetDir = new File(inputPath);

        if (targetDir.exists() && targetDir.isDirectory()) {
            setDirectory(targetDir);
        } else {
            JOptionPane.showMessageDialog(this,
                "The system cannot find the path specified:\n" + inputPath,
                "Navigation Error",
                JOptionPane.ERROR_MESSAGE);
            
            if (currentDir != null) {
                addressField.setText(currentDir.getAbsolutePath());
            }
        }
    }

    public void performOpenAction() {
        java.util.List<File> selectedFiles = getSelectedFiles();
        if (selectedFiles != null && !selectedFiles.isEmpty()) {
            if (parentDialog != null) {
                parentDialog.approveSelection();
            }
        }
    }

    private void performCreateFolderAction() {
        if (currentDir == null || !currentDir.exists() || !currentDir.isDirectory()) return;

        String folderName = JOptionPane.showInputDialog(this, "Enter new folder name:", "Create New Folder", JOptionPane.PLAIN_MESSAGE);
        if (folderName != null && !folderName.strip().isEmpty()) {
            File newFolder = new File(currentDir, folderName.trim());
            if (newFolder.exists()) {
                JOptionPane.showMessageDialog(this, "A directory item with this exact name already exists.", "Directory Target Conflict", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                if (newFolder.mkdir()) {
                    refresh();
                    selectFileInList(newFolder);
                }
            } catch (SecurityException ex) {
                JOptionPane.showMessageDialog(this, "Access Violation Denied", "Security Exception", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void performRenameAction() {
        if (selectedFile == null || !selectedFile.exists()) return;

        ThemePalette palette = ThemeManager.getCurrentPalette();
        String currentName = selectedFile.getName();

        JTextField nameInputField = new JTextField(currentName);
        nameInputField.setFont(com.sphere.fonts.FontLoader.getGlobalFont(Font.PLAIN, 12));
        nameInputField.setPreferredSize(new Dimension(280, 26));

        if (palette != null) {
            nameInputField.setForeground(palette.getTextPrimary());
            nameInputField.setBackground(palette.getBackgroundSurface());
            nameInputField.setSelectionColor(palette.getAccent());
            nameInputField.setSelectedTextColor(palette.getTextWhite());
            nameInputField.setBorder(BorderFactory.createLineBorder(palette.getBorder(), 1));
        }

        nameInputField.addAncestorListener(new javax.swing.event.AncestorListener() {
            @Override
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                SwingUtilities.invokeLater(() -> {
                    nameInputField.requestFocusInWindow();
                    nameInputField.selectAll();
                });
            }
            @Override public void ancestorRemoved(javax.swing.event.AncestorEvent event) {}
            @Override public void ancestorMoved(javax.swing.event.AncestorEvent event) {}
        });

        JPanel dialogMessagePanel = new JPanel(new BorderLayout(0, 8));
        dialogMessagePanel.setOpaque(false);
        JLabel inputPromptLabel = new JLabel("Enter new name:");
        if (palette != null) inputPromptLabel.setForeground(palette.getTextPrimary());
        
        dialogMessagePanel.add(inputPromptLabel, BorderLayout.NORTH);
        dialogMessagePanel.add(nameInputField, BorderLayout.CENTER);

        int userChoiceResult = JOptionPane.showConfirmDialog(this, dialogMessagePanel, "Rename Item", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (userChoiceResult == JOptionPane.OK_OPTION) {
            String newName = nameInputField.getText();
            if (newName != null && !newName.strip().isEmpty() && !newName.trim().equals(currentName)) {
                File destinationFile = new File(selectedFile.getParentFile(), newName.trim());
                if (destinationFile.exists()) return;
                try {
                    if (selectedFile.renameTo(destinationFile)) {
                        refresh();
                        selectFileInList(destinationFile);
                    }
                } catch (SecurityException ex) {}
            }
        }
    }

    private void performDeleteAction() {
        if (selectedFile == null || !selectedFile.exists()) return;

        int userConfirmationChoice = JOptionPane.showConfirmDialog(this, "Permanently delete \"" + selectedFile.getName() + "\"?", "Confirm Destruction", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (userConfirmationChoice == JOptionPane.YES_OPTION) {
            try {
                if (selectedFile.delete()) {
                    refresh();
                }
            } catch (SecurityException ex) {}
        }
    }

    private void selectFileInList(File targetFile) {
        if (fileList != null && listModel != null) {
            int targetIndex = -1;
            int modelSize = listModel.getSize();
            for (int i = 0; i < modelSize; i++) {
                File item = listModel.getElementAt(i);
                if (item != null && item.equals(targetFile)) {
                    targetIndex = i;
                    break;
                }
            }
            if (targetIndex >= 0) {
                fileList.setSelectedIndex(targetIndex);
                fileList.ensureIndexIsVisible(targetIndex);
            }
        }
    }

    /**
     * Synchronizes the active directory view. Maps the new file location to both 
     * the path trackers, text field and the programmatic navigation tree panel.
     * Generates intermediate lazy nodes on the fly for deep Unix/WSL compatibility.
     */
    void setDirectory(File dir) {
        if (dir != null && dir.isDirectory()) {
            this.currentDir = dir;
            refresh();
            synchronizeTreeSelection(dir);
        }
    }

    /**
     * Traverses and dynamically expands the tree structure to match the target folder path.
     * Reconstructs missing lazy-loaded structural nodes safely on Unix, macOS, and WSL.
     */
    private void synchronizeTreeSelection(File targetDir) {
        if (directoryTree == null || targetDir == null) return;

        DefaultTreeModel model = (DefaultTreeModel) directoryTree.getModel();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
        
        isAdjustingTreeSelection = true;
        try {
            // Step 1: Compute the absolute chain path from the system root down to the target folder
            java.util.List<File> pathChain = new java.util.ArrayList<>();
            File current = targetDir;
            while (current != null) {
                pathChain.add(0, current);
                current = current.getParentFile();
            }

            DefaultMutableTreeNode currentNode = root;

            // Step 2: Navigate through the chain, locating or creating nodes dynamically
            for (File stepFile : pathChain) {
                DefaultMutableTreeNode matchedChild = null;
                String stepPath = stepFile.getAbsolutePath();

                // Look for an existing node matching the current step path
                Enumeration<?> children = currentNode.children();
                while (children.hasMoreElements()) {
                    DefaultMutableTreeNode child = (DefaultMutableTreeNode) children.nextElement();
                    Object userObj = child.getUserObject();
                    if (userObj instanceof File && ((File) userObj).getAbsolutePath().equals(stepPath)) {
                        matchedChild = child;
                        break;
                    }
                }

                // If the node does not exist yet (due to lazy loading), create it dynamically
                if (matchedChild == null && stepFile.exists() && stepFile.isDirectory()) {
                    final File finalStepFile = stepFile;
                    matchedChild = new DefaultMutableTreeNode(stepFile) {
                        @Override
                        public String toString() {
                            // Safe fallbacks for roots versus subfolders names
                            if (finalStepFile.getParent() == null) {
                                return finalStepFile.getAbsolutePath();
                            }
                            return finalStepFile.getName();
                        }
                    };
                    model.insertNodeInto(matchedChild, currentNode, currentNode.getChildCount());
                }

                if (matchedChild != null) {
                    currentNode = matchedChild;
                    
                    // Lazy load the next immediate level to keep the tree expander icons accurate (+ button)
                    File[] subDirs = stepFile.listFiles(File::isDirectory);
                    if (subDirs != null && currentNode.getChildCount() == 0) {
                        for (File sub : subDirs) {
                            if (!sub.isHidden()) {
                                final File finalSub = sub;
                                currentNode.add(new DefaultMutableTreeNode(sub) {
                                    @Override
                                    public String toString() { return finalSub.getName(); }
                                });
                            }
                        }
                        model.nodeStructureChanged(currentNode);
                    }
                } else {
                    // Break path matching loop if a structural segment becomes completely inaccessible
                    break;
                }
            }

            // Step 3: Trigger the visual selection and scroll UI animations if a valid node was resolved
            if (currentNode != root) {
                TreePath path = new TreePath(currentNode.getPath());
                directoryTree.setSelectionPath(path);
                directoryTree.scrollPathToVisible(path);
            } else {
                directoryTree.clearSelection();
            }
        } catch (Exception ex) {
            // Silent safety guard fallback for virtual or restricted system scopes
            directoryTree.clearSelection();
        } finally {
            isAdjustingTreeSelection = false;
        }
    }

    /**
     * Helper lookup crawler using normalized path comparison.
     */
    private DefaultMutableTreeNode findTreeNodeForFile(DefaultMutableTreeNode node, File targetFile) {
        Object userObj = node.getUserObject();
        if (userObj instanceof File) {
            try {
                String nodePath = ((File) userObj).getCanonicalPath();
                String targetPath = targetFile.getCanonicalPath();
                if (nodePath.equals(targetPath)) {
                    return node;
                }
            } catch (Exception ex) {
                // Fallback to absolute paths if canonical resolution drops
                if (((File) userObj).getAbsolutePath().equals(targetFile.getAbsolutePath())) {
                    return node;
                }
            }
        }

        Enumeration<?> children = node.children();
        while (children.hasMoreElements()) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) children.nextElement();
            DefaultMutableTreeNode result = findTreeNodeForFile(child, targetFile);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    File getSelectedFile() {
        return selectedFile;
    }

    java.util.List<File> getSelectedFiles() {
        if (selectedFile == null) {
            return java.util.Collections.emptyList();
        }
        return fileList.getSelectedValuesList();
    }

    void refresh() {
        if (fileList != null) {
            fileList.clearSelection();
        }
        this.selectedFile = null;
        listModel.setDirectory(currentDir);
        pathBar.updatePath(currentDir);
        
        if (currentDir != null && addressField != null) {
            addressField.setText(currentDir.getAbsolutePath());
        }
    }

    void setFilter(FlatFileFilter filter) {
        this.filter = filter;
        listModel.setFilter(filter);
        refresh();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        ThemePalette palette = ThemeManager.getCurrentPalette();
        if (palette != null) {
            setBackground(palette.getBackgroundSurface());
            if (fileList != null) fileList.setForeground(palette.getTextPrimary());
            if (directoryTree != null) directoryTree.setForeground(palette.getTextPrimary());
            if (addressField != null) {
                addressField.setForeground(palette.getTextPrimary());
                addressField.setBackground(palette.getBackgroundSurface());
                
                // FIX: Maintain selection color integration on UI updates
                addressField.setSelectionColor(palette.getAccent());
                addressField.setSelectedTextColor(palette.getTextWhite());
                
                addressField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(palette.getBorder(), 1, true),
                    BorderFactory.createEmptyBorder(5, 8, 5, 8)
                ));
            }
            if (actionRowPanel != null) {
                actionRowPanel.setBackground(palette.getBackgroundSurface());
                actionRowPanel.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createMatteBorder(1, 0, 0, 0, palette.getBorder()),
                        BorderFactory.createEmptyBorder(14, 16, 14, 16)
                ));
            }
        }
    }
}
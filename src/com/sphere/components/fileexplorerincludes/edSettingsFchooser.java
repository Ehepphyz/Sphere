package com.sphere.components.fileexplorerincludes;

import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.ui.SPButtonUI;
import com.sphere.utils.IconManager;
import com.sphere.utils.OSValidator;

import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.event.TreeExpansionEvent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.Enumeration;

import com.sphere.components.fileexplorerincludes.FlatFileListModel;

public class edSettingsFchooser extends JDialog {

    public static final int CANCEL_OPTION = 1;
    public static final int APPROVE_OPTION = 0;

    private final DirectoryChooserPanel panel;
    private int resultStatus = CANCEL_OPTION;

    public edSettingsFchooser(Window parent, File initialDir) {
        super(parent, "Select Directory Path", ModalityType.APPLICATION_MODAL);

        setLayout(new BorderLayout());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(true);

        this.panel = new DirectoryChooserPanel(initialDir, this);
        add(this.panel, BorderLayout.CENTER);

        setSize(850, 450);
        setMinimumSize(new Dimension(640, 400));

        if (parent != null) {
            setLocationRelativeTo(parent);
        } else {
            setLocationRelativeTo(null);
        }

        setupKeyboardAccelerators();
    }

    public void approveSelection() {
        this.resultStatus = APPROVE_OPTION;
        dispose();
    }

    public void cancelSelection() {
        this.resultStatus = CANCEL_OPTION;
        dispose();
    }

    public int showDialog() {
        this.resultStatus = CANCEL_OPTION;
        setVisible(true);
        return resultStatus;
    }

    public File getSelectedDirectory() {
        return panel.getSelectedDirectory();
    }

    private void setupKeyboardAccelerators() {
        JRootPane root = getRootPane();
        InputMap inputMap = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = root.getActionMap();

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeChooser");
        actionMap.put("closeChooser", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancelSelection();
            }
        });

        int platformModifier = OSValidator.getPlatformModifier();
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, platformModifier), "shortcutClose");
        actionMap.put("shortcutClose", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cancelSelection();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "approveChooser");
        actionMap.put("approveChooser", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Let the panel decide based on which component currently holds focus
                panel.handleEnterKeyPressed();
            }
        });
    }

    @Override
    public void setVisible(boolean b) {
        if (b && getContentPane() != null) {
            SwingUtilities.updateComponentTreeUI(this);
        }
        super.setVisible(b);
    }

    private static class DirectoryChooserPanel extends JPanel {
        private final FileSystemView fsv = FileSystemView.getFileSystemView();
        private File currentDir;
        private File selectedDir;

        private final JLabel pathLabel;
        private final JPanel breadcrumbPanel;
        private final FlatFileListModel listModel;
        private final JList<File> fileList;
        private final JTree directoryTree;

        private final JTextField addressField;
        private final JButton goAddressButton;

        private final JButton createFolderButton;
        private final JButton openButton;
        private final JButton cancelButton;
        private final edSettingsFchooser parentDialog;

        private final JSplitPane splitPane;
        private final JPanel actionRowPanel;
        private boolean isAdjustingTreeSelection = false;

        DirectoryChooserPanel(File initialDir, edSettingsFchooser parentDialog) {
            this.parentDialog = parentDialog;
            ThemePalette palette = ThemeManager.getCurrentPalette();

            if (initialDir == null || !initialDir.isDirectory()) {
                initialDir = fsv.getHomeDirectory();
            }
            this.currentDir = initialDir;
            this.selectedDir = initialDir;

            setLayout(new BorderLayout());
            setBackground(palette.getBackgroundSurface());

            JPanel topContainer = new JPanel(new BorderLayout());
            topContainer.setOpaque(false);

            this.pathLabel = new JLabel(currentDir.getAbsolutePath());
            this.pathLabel.setFont(com.sphere.fonts.FontLoader.getGlobalFont(Font.BOLD, 12));
            this.pathLabel.setForeground(palette.getTextPrimary());
            this.pathLabel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 8));

            breadcrumbPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            breadcrumbPanel.setOpaque(false);

            JPanel pathWrapper = new JPanel(new BorderLayout());
            pathWrapper.setOpaque(false);
            pathWrapper.add(this.pathLabel, BorderLayout.NORTH);
            pathWrapper.add(breadcrumbPanel, BorderLayout.SOUTH);

            topContainer.add(pathWrapper, BorderLayout.CENTER);

            JPanel topActionWrapper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            topActionWrapper.setOpaque(false);
            topActionWrapper.setBorder(BorderFactory.createEmptyBorder(12, 0, 12, 16));

            JButton upButton = new JButton();
            upButton.setUI((SPButtonUI) SPButtonUI.createUI(upButton));
            upButton.setIcon(IconManager.getIcon("upsnippets.png"));
            upButton.setPreferredSize(new Dimension(32, 32));
            upButton.addActionListener(e -> {
                if (currentDir != null) {
                    File parent = currentDir.getParentFile();
                    if (parent != null && parent.isDirectory()) {
                        setDirectory(parent);
                    }
                }
            });
            topActionWrapper.add(upButton);

            createFolderButton = new JButton();
            createFolderButton.setUI((SPButtonUI) SPButtonUI.createUI(createFolderButton));
            createFolderButton.setIcon(IconManager.getIcon("createfolder.png"));
            createFolderButton.setToolTipText("Create New Folder");
            createFolderButton.setPreferredSize(new Dimension(32, 32));
            createFolderButton.addActionListener(e -> performCreateFolderAction());
            topActionWrapper.add(createFolderButton);

            topContainer.add(topActionWrapper, BorderLayout.EAST);
            topContainer.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, palette.getBorder()));

            addressField = new JTextField();
            addressField.setFont(com.sphere.fonts.FontLoader.getGlobalFont(Font.PLAIN, 12));
            addressField.setForeground(palette.getTextPrimary());
            addressField.setBackground(palette.getBackgroundSurface());
            addressField.setCaretColor(palette.getTextPrimary());
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

            JPanel headerStackPanel = new JPanel(new BorderLayout());
            headerStackPanel.setOpaque(false);
            headerStackPanel.add(topContainer, BorderLayout.NORTH);
            headerStackPanel.add(addressBarWrapper, BorderLayout.SOUTH);
            add(headerStackPanel, BorderLayout.NORTH);

            listModel = new FlatFileListModel(currentDir);
            listModel.setFilter(File::isDirectory);

            fileList = new JList<>(listModel);
            fileList.setCellRenderer(new FlatFileRenderer());
            fileList.setOpaque(false);
            fileList.setForeground(palette.getTextPrimary());
            fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            fileList.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            fileList.setFocusable(true);
            fileList.setLayoutOrientation(JList.HORIZONTAL_WRAP);
            fileList.setVisibleRowCount(-1);

            fileList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    File selected = fileList.getSelectedValue();
                    if (selected != null && selected.isDirectory()) {
                        this.selectedDir = selected;
                    }
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

            DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Computer");
            initTreeRoots(rootNode);

            directoryTree = new JTree(rootNode);
            directoryTree.setOpaque(false);
            directoryTree.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            directoryTree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);

            directoryTree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
                {
                    Icon pFolder = IconManager.getIcon("pfolder.png");
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
                    ThemePalette palette = ThemeManager.getCurrentPalette();
                    if (palette != null) {
                        setForeground(sel ? palette.getTextWhite() : palette.getTextPrimary());
                        setBackgroundSelectionColor(palette.getButtonPressed());
                        setBackgroundNonSelectionColor(null);
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

            directoryTree.addTreeWillExpandListener(new TreeWillExpandListener() {
                @Override
                public void treeWillExpand(TreeExpansionEvent event) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                    Object obj = node.getUserObject();
                    if (!(obj instanceof File)) return;

                    File dir = (File) obj;
                    File[] actualSubs = dir.listFiles(File::isDirectory);
                    if (actualSubs == null) actualSubs = new File[0];

                    DefaultTreeModel model = (DefaultTreeModel) directoryTree.getModel();

                    // 1. Remove nodes of directories that no longer exist on disk
                    for (int i = node.getChildCount() - 1; i >= 0; i--) {
                        DefaultMutableTreeNode childNode = (DefaultMutableTreeNode) node.getChildAt(i);
                        Object childObj = childNode.getUserObject();
                        if (childObj instanceof File) {
                            File childFile = (File) childObj;
                            boolean stillExists = false;
                            for (File actualSub : actualSubs) {
                                if (actualSub.getAbsolutePath().equals(childFile.getAbsolutePath())) {
                                    stillExists = true;
                                    break;
                                }
                            }
                            if (!stillExists) {
                                model.removeNodeFromParent(childNode);
                            }
                        }
                    }

                    // 2. Add new directories that are not yet in the tree
                    for (File subDir : actualSubs) {
                        boolean alreadyPresent = false;
                        for (int i = 0; i < node.getChildCount(); i++) {
                            DefaultMutableTreeNode childNode = (DefaultMutableTreeNode) node.getChildAt(i);
                            Object childObj = childNode.getUserObject();
                            if (childObj instanceof File && ((File) childObj).getAbsolutePath().equals(subDir.getAbsolutePath())) {
                                alreadyPresent = true;
                                break;
                            }
                        }

                        if (!alreadyPresent) {
                            DefaultMutableTreeNode newChild = new DefaultMutableTreeNode(subDir) {
                                @Override
                                public String toString() {
                                    return (getUserObject() instanceof File) ? ((File) getUserObject()).getName() : "";
                                }
                            };
                            model.insertNodeInto(newChild, node, node.getChildCount());
                        }
                    }
                }

                @Override
                public void treeWillCollapse(TreeExpansionEvent event) {
                }
            });

            JScrollPane listScrollPane = new JScrollPane(fileList);
            listScrollPane.getViewport().setOpaque(false);
            listScrollPane.setOpaque(false);
            listScrollPane.setBorder(BorderFactory.createEmptyBorder());

            JScrollPane treeScrollPane = new JScrollPane(directoryTree);
            treeScrollPane.getViewport().setOpaque(false);
            treeScrollPane.setOpaque(false);
            treeScrollPane.setBorder(BorderFactory.createEmptyBorder());

            splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScrollPane, listScrollPane);
            splitPane.setDividerLocation(220);
            splitPane.setDividerSize(1);
            splitPane.setOpaque(false);
            splitPane.setBorder(BorderFactory.createEmptyBorder());
            add(splitPane, BorderLayout.CENTER);

            actionRowPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            actionRowPanel.setBackground(palette.getBackgroundSurface());
            actionRowPanel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, palette.getBorder()),
                    BorderFactory.createEmptyBorder(14, 16, 14, 16)
            ));

            openButton = new JButton("Select Folder");
            openButton.setUI((SPButtonUI) SPButtonUI.createUI(openButton));
            openButton.setPreferredSize(new Dimension(130, 32));
            openButton.addActionListener(e -> performOpenAction());

            cancelButton = new JButton("Cancel");
            cancelButton.setUI((SPButtonUI) SPButtonUI.createUI(cancelButton));
            cancelButton.setPreferredSize(new Dimension(100, 32));
            cancelButton.addActionListener(e -> parentDialog.cancelSelection());

            actionRowPanel.add(openButton);
            actionRowPanel.add(cancelButton);
            add(actionRowPanel, BorderLayout.SOUTH);

            refresh();
        }

        /**
         * Normalizes file paths, with specialized safe processing for Windows UNC paths.
         * Ensures incomplete UNC paths (missing host or share name) fall back safely
         * to the user's home directory instead of crashing or generating broken paths.
         *
         * @param pathStr The raw input path string
         * @return A normalized File object
         */
        public static File normalizePath(String pathStr) {
            if (pathStr == null || pathStr.trim().isEmpty()) {
                return new File(System.getProperty("user.home"));
            }

            String trimmed = pathStr.trim();
            
            // Check for Windows UNC path (e.g., \\server\share or //server/share)
            boolean isUNC = trimmed.startsWith("\\\\") || trimmed.startsWith("//");
            
            if (isUNC) {
                // Normalize slashes to backslashes for Windows UNC
                String normalizedSlashes = trimmed.replace('/', '\\');
                
                // Clean up relative path components safely without hitting the network filesystem
                String[] segments = normalizedSlashes.split("\\\\");
                java.util.Stack<String> stack = new java.util.Stack<>();
                
                for (String segment : segments) {
                    if (segment.isEmpty() || segment.equals(".")) {
                        continue;
                    }
                    if (segment.equals("..")) {
                        // Avoid popping the server name and share name (the first two non-empty segments)
                        if (stack.size() > 2) {
                            stack.pop();
                        }
                    } else {
                        stack.push(segment);
                    }
                }

                if (stack.size() < 2) {
                    return new File(System.getProperty("user.home"));
                }
                
                // Reconstruct UNC path: \\server\share\folder...
                StringBuilder sb = new StringBuilder("\\\\");
                for (int i = 0; i < stack.size(); i++) {
                    sb.append(stack.get(i));
                    if (i < stack.size() - 1) {
                        sb.append("\\");
                    }
                }
                return new File(sb.toString());
            }

            // Standard fallback for local paths (safe to use canonicalization)
            try {
                return new File(trimmed).getCanonicalFile();
            } catch (Exception e) {
                return new File(trimmed).getAbsoluteFile();
            }
        }

        public void handleEnterKeyPressed() {
            if (addressField.hasFocus()) {
                triggerAddressBarNavigation();
            } else if (directoryTree.hasFocus()) {
                // Toggle expansion of the selected JTree node
                TreePath selectionPath = directoryTree.getSelectionPath();
                if (selectionPath != null) {
                    if (directoryTree.isExpanded(selectionPath)) {
                        directoryTree.collapsePath(selectionPath);
                    } else {
                        directoryTree.expandPath(selectionPath);
                    }
                }
            } else if (fileList.hasFocus()) {
                File selected = fileList.getSelectedValue();
                if (selected != null && selected.isDirectory()) {
                    // Navigate into the double-clicked/entered folder
                    setDirectory(selected);
                } else {
                    // Otherwise, confirm selection of the current folder
                    performOpenAction();
                }
            } else {
                // Default fallback for safe confirmation
                performOpenAction();
            }
        }

        public void performOpenAction() {
            if (this.selectedDir == null) {
                this.selectedDir = this.currentDir;
            }

            if (this.selectedDir != null && this.selectedDir.isDirectory()) {
                parentDialog.approveSelection();
            } else {
                JOptionPane.showMessageDialog(this,
                        "The configuration variable expects a directory path.\nPlease select a valid directory.",
                        "Directory Required",
                        JOptionPane.WARNING_MESSAGE);
            }
        }

        private void initTreeRoots(DefaultMutableTreeNode rootNode) {
            File[] roots = File.listRoots();
            if (roots == null) return;
            boolean isUnix = !System.getProperty("os.name").toLowerCase().contains("win");
            boolean isWSL = System.getenv("WSL_DISTRO_NAME") != null;

            if (isUnix) {
                // 1. User Home Directory (convenient for quick access)
                File userHome = new File(System.getProperty("user.home"));
                if (userHome.exists()) {
                    DefaultMutableTreeNode homeNode = new DefaultMutableTreeNode(userHome) {
                        @Override
                        public String toString() {
                            return "Home (" + userHome.getName() + ")";
                        }
                    };
                    rootNode.add(homeNode);
                }

                // 2. /Volumes directory (macOS specific for external drives)
                File volumes = new File("/Volumes");
                if (volumes.exists() && volumes.isDirectory()) {
                    DefaultMutableTreeNode volNode = new DefaultMutableTreeNode(volumes) {
                        @Override
                        public String toString() {
                            return "Volumes";
                        }
                    };
                    rootNode.add(volNode);
                }

                // 3. WSL /mnt Subfolders (automatically lists mounted Windows drives)
                if (isWSL) {
                    File mnt = new File("/mnt");
                    if (mnt.exists() && mnt.isDirectory()) {
                        File[] mntSubdirs = mnt.listFiles(File::isDirectory);
                        if (mntSubdirs != null && mntSubdirs.length > 0) {
                            for (File sub : mntSubdirs) {
                                DefaultMutableTreeNode mntSubNode = new DefaultMutableTreeNode(sub) {
                                    @Override
                                    public String toString() {
                                        File f = (File) getUserObject();
                                        String name = f.getName();
                                        // Format mount letters nicely (e.g., /mnt/c -> Local Drive (C:))
                                        if (name.length() == 1) {
                                            return "Local Drive (" + name.toUpperCase() + ":)";
                                        }
                                        return "/mnt/" + name;
                                    }
                                };
                                rootNode.add(mntSubNode);
                            }
                        } else {
                            // Fallback if /mnt exists but contains no active mounts
                            DefaultMutableTreeNode mntNode = new DefaultMutableTreeNode(mnt) {
                                @Override
                                public String toString() {
                                    return "/mnt";
                                }
                            };
                            rootNode.add(mntNode);
                        }
                    }
                } else {
                    // 4. Unique System Root (only added for standard Unix/Linux to avoid logical duplication on WSL)
                    File rootDir = new File("/");
                    if (rootDir.exists()) {
                        DefaultMutableTreeNode systemRootNode = new DefaultMutableTreeNode(rootDir) {
                            @Override
                            public String toString() {
                                return "File System (/)";
                            }
                        };
                        rootNode.add(systemRootNode);
                    }
                }
            } else {
                // Windows section (lists local drives like C:\, D:\, etc.)
                for (File root : roots) {
                    DefaultMutableTreeNode node = new DefaultMutableTreeNode(root) {
                        @Override
                        public String toString() {
                            String name = fsv.getSystemDisplayName((File) getUserObject());
                            return (name != null && !name.isEmpty()) ? name : ((File) getUserObject()).getAbsolutePath();
                        }
                    };
                    rootNode.add(node);
                }
            }
        }

        private void triggerAddressBarNavigation() {
            String inputPath = addressField.getText();
            if (inputPath == null || inputPath.isBlank()) return;

            File targetDir = normalizePath(inputPath);

            if (targetDir.exists() && targetDir.isDirectory()) {
                setDirectory(targetDir);
            } else {
                JOptionPane.showMessageDialog(this,
                        "The system cannot find the directory specified:\n" + inputPath,
                        "Navigation Error",
                        JOptionPane.ERROR_MESSAGE);
                if (currentDir != null) {
                    addressField.setText(currentDir.getAbsolutePath());
                }
            }
        }

        private void performCreateFolderAction() {
            if (currentDir == null || !currentDir.exists() || !currentDir.isDirectory()) return;

            String folderName = JOptionPane.showInputDialog(this, "Enter new folder name:", "Create New Folder", JOptionPane.PLAIN_MESSAGE);
            if (folderName != null && !folderName.strip().isEmpty()) {
                File newFolder = new File(currentDir, folderName.trim());
                if (newFolder.exists()) {
                    JOptionPane.showMessageDialog(this, "A directory with this name already exists.", "Conflict", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                try {
                    if (newFolder.mkdir()) {
                        refresh();
                        this.selectedDir = newFolder;
                    }
                } catch (SecurityException ex) {
                    JOptionPane.showMessageDialog(this, "Access Violation Denied", "Security Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }

        /**
         * Navigates the file chooser to the specified directory.
         * Updates the current selection, triggers an asynchronous reload of the 
         * file list, and synchronizes the folder tree and breadcrumbs.
         *
         * @param dir The destination directory
         */
        public void setDirectory(File dir) {
            if (dir != null && dir.isDirectory()) {
                this.currentDir = dir;
                this.selectedDir = dir;
                
                // 1. Update the asynchronous file list model (reloads in background thread)
                if (listModel != null) {
                    listModel.setDirectory(dir);
                }
                
                // 2. Refresh other UI components (like address text bar and breadcrumbs)
                refresh();
                
                // 3. Keep the visual JTree selection in sync with the current active path
                synchronizeTreeSelection(dir);
            }
        }

        /**
         * Synchronizes the JTree selection with the target directory.
         * Dynamically resolves missing intermediate nodes along the path chain
         * while ensuring no duplicate nodes are created.
         *
         * @param targetDir The destination directory to synchronize and select in the tree
         */
        private void synchronizeTreeSelection(File targetDir) {
            if (directoryTree == null || targetDir == null) return;
            DefaultTreeModel model = (DefaultTreeModel) directoryTree.getModel();
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) model.getRoot();
            if (root == null) return;

            isAdjustingTreeSelection = true;
            try {
                // 1. Build the logical path chain from the root down to the target directory
                java.util.List<File> pathChain = new java.util.ArrayList<>();
                File current = targetDir;
                while (current != null) {
                    pathChain.add(0, current);
                    current = current.getParentFile();
                }

                DefaultMutableTreeNode currentNode = root;
                for (File stepFile : pathChain) {
                    DefaultMutableTreeNode matchedChild = null;
                    String stepPath = stepFile.getAbsolutePath();

                    // 2. Search existing children to prevent duplicate node creation
                    java.util.Enumeration<?> children = currentNode.children();
                    while (children.hasMoreElements()) {
                        DefaultMutableTreeNode child = (DefaultMutableTreeNode) children.nextElement();
                        Object userObj = child.getUserObject();
                        if (userObj instanceof File && ((File) userObj).getAbsolutePath().equals(stepPath)) {
                            matchedChild = child;
                            break;
                        }
                    }

                    // 3. Only insert a new node dynamically if it doesn't already exist
                    if (matchedChild == null && stepFile.exists() && stepFile.isDirectory()) {
                        matchedChild = new DefaultMutableTreeNode(stepFile) {
                            @Override
                            public String toString() {
                                return (getUserObject() instanceof File) ? ((File) getUserObject()).getName() : "";
                            }
                        };
                        model.insertNodeInto(matchedChild, currentNode, currentNode.getChildCount());
                    }

                    // 4. Advance down the tree hierarchy
                    if (matchedChild != null) {
                        currentNode = matchedChild;
                    } else {
                        break;
                    }
                }

                // 5. Apply the synchronized selection and scroll to make it visible
                if (currentNode != root) {
                    TreePath path = new TreePath(currentNode.getPath());
                    directoryTree.setSelectionPath(path);
                    directoryTree.scrollPathToVisible(path);
                }
            } catch (Exception ex) {
                // Clear selection gracefully in case of any security or access issues
                directoryTree.clearSelection();
            } finally {
                isAdjustingTreeSelection = false;
            }
        }

        public File getSelectedDirectory() {
            return selectedDir;
        }

        private void rebuildBreadcrumb() {
            breadcrumbPanel.removeAll();
            File dir = currentDir;
            java.util.List<File> chain = new java.util.ArrayList<>();
            while (dir != null) {
                chain.add(0, dir);
                dir = dir.getParentFile();
            }
            
            for (int i = 0; i < chain.size(); i++) {
                File f = chain.get(i);
                JButton b = new JButton(f.getName().isEmpty() ? f.getAbsolutePath() : f.getName());
                b.setUI((SPButtonUI) SPButtonUI.createUI(b));
                
                // FIX: Do not force preferred size dimensions. 
                // Let the button's layout UI handle internal margins/paddings dynamically.
                b.addActionListener(e -> setDirectory(f));
                breadcrumbPanel.add(b);
                
                if (i < chain.size() - 1) {
                    breadcrumbPanel.add(new JLabel(">"));
                }
            }
            
            breadcrumbPanel.revalidate();
            breadcrumbPanel.repaint();
        }

        void refresh() {
            if (fileList != null) {
                fileList.clearSelection();
            }
            listModel.setDirectory(currentDir);
            if (pathLabel != null && currentDir != null) {
                pathLabel.setText(currentDir.getAbsolutePath());
            }
            if (currentDir != null && addressField != null) {
                addressField.setText(currentDir.getAbsolutePath());
            }
            rebuildBreadcrumb();
        }
    }
}
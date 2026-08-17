package com.sphere.components.workspace;

import com.sphere.utils.AppLogger;
import com.sphere.utils.IconManager;
import com.sphere.components.QuickCodeEditor;
import com.sphere.ui.QuickCodeEditorFrame;
import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;


import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

public class WorkspaceFileExplorerPanel extends JPanel {

    private Path projectDirectory;
    private File settingsFile;

    private JTree projectTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private JScrollPane scrollPane;

    private QuickCodeEditorFrame editorFrame;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    // Map from relative path to tree node
    private final Map<String, DefaultMutableTreeNode> pathNodeMap = new HashMap<>();
    // Last known set of paths
    private final Set<String> lastKnownPaths = new HashSet<>();
    // JSON cache
    private long lastSettingsReadTime = 0;
    private List<String> cachedJsonPaths = new ArrayList<>();

    // Clipboard
    private static File clipboardSourceFile = null;
    private static boolean isCutOperation = false;

    // Context menu guard
    private boolean contextMenuOpen = false;
    // User interaction protection
    private volatile long lastUserInteractionTime = 0;
    private static final long USER_INTERACTION_PROTECTION_MS = 350;
    // Polling watcher
    private Thread pollingWatcherThread;
    private volatile boolean pollingWatcherRunning = false;
    private long lastDirectorySnapshot = 0;
    // Linux WatchService
    private WatchService watchService;
    private Thread watchServiceThread;
    private volatile boolean watchServiceRunning = false;
    // Scheduled executor
    private final ScheduledExecutorService scheduledExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private volatile boolean pendingRefresh = false;
    private static final long PENDING_REFRESH_DELAY_MS = 400;
    // Sync queue
    private final LinkedBlockingQueue<String> syncQueue = new LinkedBlockingQueue<>();
    // OS detection
    private final boolean isWindows;
    private final boolean isMac;
    private final boolean isLinux;

    public WorkspaceFileExplorerPanel() {
        String os = System.getProperty("os.name").toLowerCase();
        isWindows = os.contains("win");
        isMac = os.contains("mac");
        isLinux = os.contains("nux");

        setLayout(new BorderLayout());
        setOpaque(true);
        setBackground(palette.getBackgroundSurface());

        initComponents();
        setDoubleBuffered(true); // Optimized native Swing double buffering
        startSyncWorker();
    }

    /**
     * Connects the shared workspace frame to this explorer context for centralized tab routing.
     * @param editorFrame The active workspace frame instance.
     */
    public void setEditorFrame(QuickCodeEditorFrame editorFrame) {
        this.editorFrame = editorFrame;
    }

    @Override
    public boolean isDoubleBuffered() {
        return true;
    }

    @Override
    protected void paintComponent(Graphics g) {
        // Fallback to standard rendering pipeline to fix tree component selection highlight clipping
        super.paintComponent(g);
    }

    private void initComponents() {
        rootNode = new DefaultMutableTreeNode("No Project Target");
        treeModel = new DefaultTreeModel(rootNode);
        projectTree = new JTree(treeModel);

        projectTree.setRootVisible(true);
        projectTree.setShowsRootHandles(false);
        
        // Force the JTree itself to not paint standard full-row selection backgrounds if overlapping
        projectTree.setOpaque(false);

        projectTree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            private boolean isRowSelected = false;

            @Override
            public Component getTreeCellRendererComponent(
                    JTree tree,
                    Object value,
                    boolean selected,
                    boolean expanded,
                    boolean leaf,
                    int row,
                    boolean hasFocus
            ) {
                // Pass configuration to the super class
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
                
                this.isRowSelected = selected;
                
                // Absolute override: Make cell background transparent so our custom paintComponent handles it
                setOpaque(false);

                // Fetch current theme palette
                ThemePalette palette = ThemeManager.getCurrentPalette();
                if (palette != null) {
                    // Text colors management
                    if (selected) {
                        setTextSelectionColor(palette.getTextPrimary());
                    } else {
                        setTextNonSelectionColor(palette.getTextPrimary());
                    }
                }

                // Preserve existing icon resolution logic
                if (value instanceof DefaultMutableTreeNode) {
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                    if (node.isRoot()) {
                        setIcon(IconManager.getIcon("pfolder.png"));
                    } else {
                        StringBuilder pathBuilder = new StringBuilder();
                        Object[] nodes = node.getPath();
                        for (int i = 1; i < nodes.length; i++) {
                            pathBuilder.append(nodes[i].toString());
                            if (i < nodes.length - 1) pathBuilder.append("/");
                        }
                        String relativeNodePath = pathBuilder.toString();
                        String nodeName = node.getUserObject().toString();

                        File f = (projectDirectory != null)
                                ? projectDirectory.resolve(relativeNodePath).toFile()
                                : null;
                        if (f != null && f.isDirectory()) {
                            setIcon(IconManager.getIcon("folder.png"));
                        } else {
                            setIcon(IconManager.getIconForFile(nodeName));
                        }
                    }
                }
                return this;
            }

            @Override
            protected void paintComponent(Graphics g) {
                // Completely bypass Look & Feel defaults to draw our explicit theme colors
                if (isRowSelected) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    
                    ThemePalette palette = ThemeManager.getCurrentPalette();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(palette.getAccent());
                    
                    // Fill a clean rounded or flat rectangle behind the node text/icon area
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
                    g2.dispose();
                }
                
                // Paint the text and icon over our custom background
                super.paintComponent(g);
            }
        });

        scrollPane = new JScrollPane(projectTree);
        // Ensure scrollpane container lets the transparency bypass correctly
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setOpaque(false);

        setupTreeContextMenu();
        setupInteractionListeners();

        add(scrollPane, BorderLayout.CENTER);
    }

    /* ---------------------------------------------------------------------
    * Context menu and selections
    */
    private void setupTreeContextMenu() {
        // Themed popup menu
        JPopupMenu popup = new JPopupMenu() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                ThemePalette palette = ThemeManager.getCurrentPalette();
                if (palette != null) {
                    g2.setColor(palette.getBackgroundSurface().brighter());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);

                    g2.setColor(palette.getPopupBorder());
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                }
                g2.dispose();
            }
        };
        popup.setOpaque(false);
        popup.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Themed menu item factory
        java.util.function.Function<String, JMenuItem> themedItem = (String text) -> new JMenuItem(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                ThemePalette palette = ThemeManager.getCurrentPalette();

                if (getModel().isArmed() || getModel().isSelected()) {
                    g2.setColor(palette.getButtonPressed());
                    g2.fillRoundRect(4, 2, getWidth() - 8, getHeight() - 4, 6, 6);
                    g2.setColor(palette.getTextPrimary());
                } else {
                    g2.setColor(palette.getTextPrimary());
                }

                g2.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                FontMetrics fm = g2.getFontMetrics();
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(getText(), 12, y);

                g2.dispose();
            }
        };

        // Instantiating all needed menu items
        JMenuItem editItem = themedItem.apply("Edit");
        JMenuItem renameItem = themedItem.apply("Rename");
        JMenuItem deleteItem = themedItem.apply("Delete");

        JMenuItem createFileItem = themedItem.apply("Create File");
        JMenuItem createFolderItem = themedItem.apply("Create Folder");
        
        JMenuItem cutItem = themedItem.apply("Cut");
        JMenuItem copyItem = themedItem.apply("Copy");
        JMenuItem pasteItem = themedItem.apply("Paste");
        
        JMenuItem refreshItem = themedItem.apply("Refresh");

        // Binding implementation listeners
        editItem.addActionListener(e -> executeEditAction());
        renameItem.addActionListener(e -> executeRenameAction());
        deleteItem.addActionListener(e -> executeDeleteAction());

        createFileItem.addActionListener(e -> executeCreateFileAction());
        createFolderItem.addActionListener(e -> executeCreateFolderAction());
        
        // Connected clipboard functions
        cutItem.addActionListener(e -> handleClipboardAction(true));
        copyItem.addActionListener(e -> handleClipboardAction(false));
        pasteItem.addActionListener(e -> handlePasteExecution());
        
        refreshItem.addActionListener(e -> requestSync());

        // Assembly of the popup structure
        popup.add(editItem);
        popup.add(renameItem);
        popup.add(deleteItem);
        popup.addSeparator();
        popup.add(createFileItem);
        popup.add(createFolderItem);
        popup.addSeparator();
        popup.add(cutItem);
        popup.add(copyItem);
        popup.add(pasteItem);
        popup.addSeparator();
        popup.add(refreshItem);

        // Unified mouse event interceptor for robust right-click selections
        projectTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleContextMenuTrigger(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleContextMenuTrigger(e);
            }

            private void handleContextMenuTrigger(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    int row = projectTree.getClosestRowForLocation(e.getX(), e.getY());
                    if (row != -1) {
                        projectTree.setSelectionRow(row);
                    }
                    popup.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });
    }

    /* ---------------------------------------------------------------------
    * Sync request / worker
    */
    public void requestSync() {
        if (syncQueue.isEmpty()) {
            syncQueue.offer("sync");
        }
    }

    private void startSyncWorker() {
        scheduledExecutor.execute(() -> {
            while (true) {
                try {
                    syncQueue.take();
                    Thread.sleep(50);
                    syncQueue.clear();
                    SwingUtilities.invokeLater(this::reloadTreeIncremental);
                } catch (InterruptedException ignored) {}
            }
        });
    }

    /* ---------------------------------------------------------------------
    * Project target
    */
    public void setProjectTarget(File projectDirectoryFile) {
        stopAllWatchers();
        this.projectDirectory = (projectDirectoryFile != null) ? projectDirectoryFile.toPath() : null;
        this.settingsFile = (this.projectDirectory != null) ? this.projectDirectory.resolve(".projectsettings").toFile() : null;
        
        SwingUtilities.invokeLater(() -> {
            pathNodeMap.clear();
            lastKnownPaths.clear();
            rootNode.removeAllChildren();
            rootNode.setUserObject(
                    projectDirectoryFile != null ? projectDirectoryFile.getName() : "No Project Target"
            );
            treeModel.reload();
        });
        if (projectDirectoryFile != null) {
            buildInitialTree();
            startAllWatchers();
        }
    }

    /* ---------------------------------------------------------------------
    * Interaction listeners
    */
    private void setupInteractionListeners() {
        projectTree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                lastUserInteractionTime = System.currentTimeMillis();
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                lastUserInteractionTime = System.currentTimeMillis();
            }
        });

        projectTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    lastUserInteractionTime = System.currentTimeMillis();
                }
            }
        });

        projectTree.addTreeSelectionListener(e ->
                lastUserInteractionTime = System.currentTimeMillis()
        );
    }

    /* ---------------------------------------------------------------------
    * Initial tree build
    */
    private void buildInitialTree() {
        if (projectDirectory == null || !Files.exists(projectDirectory)) return;
        List<String> combinedPaths = collectPathsFromJsonAndDisk();
        combinedPaths.sort(String::compareToIgnoreCase);

        SwingUtilities.invokeLater(() -> {
            rootNode.removeAllChildren();
            pathNodeMap.clear();
            lastKnownPaths.clear();

            pathNodeMap.put("", rootNode);

            for (String relativePath : combinedPaths) {
                insertNodeForRelativePath(relativePath);
                lastKnownPaths.add(relativePath);
            }

            treeModel.reload();
        });
    }

    /* ---------------------------------------------------------------------
    * JSON + Disk scan
    */
    private List<String> collectPathsFromJsonAndDisk() {
        List<String> combinedPaths = new ArrayList<>();
        if (settingsFile != null && settingsFile.exists()) {
            if (settingsFile.lastModified() > lastSettingsReadTime) {
                try {
                    String content = Files.readString(settingsFile.toPath(), StandardCharsets.UTF_8);
                    cachedJsonPaths = extractPathsFromJson(content);
                    lastSettingsReadTime = settingsFile.lastModified();
                } catch (IOException e) {
                    AppLogger.error("Failed to read settings: " + e.getMessage());
                }
            }

            for (String p : cachedJsonPaths) {
                if (projectDirectory.resolve(p).toFile().exists()) {
                    combinedPaths.add(p);
                }
            }
        }

        if (projectDirectory != null) {
            try {
                scanDirectory(projectDirectory, combinedPaths);
            } catch (IOException e) {
                AppLogger.error("Failed to perform disk scan: " + e.getMessage());
            }
        }

        return combinedPaths;
    }

    /* ---------------------------------------------------------------------
    * Disk scan
    */
    public void scanDirectory(Path rootDir, List<String> pathsList) throws IOException {
        if (!Files.exists(rootDir)) return;
        Files.walkFileTree(rootDir, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() != null ? dir.getFileName().toString() : "";

                if (name.startsWith(".") || name.equals(".projectsettings")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                if (!dir.equals(rootDir)) {
                    String relative = rootDir.relativize(dir).toString().replace("\\", "/");
                    if (!pathsList.contains(relative)) {
                        pathsList.add(relative);
                    }
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();

                if (name.equals(".projectsettings")) {
                    return FileVisitResult.CONTINUE;
                }

                String relative = rootDir.relativize(file).toString().replace("\\", "/");
                if (!pathsList.contains(relative)) {
                    pathsList.add(relative);
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                AppLogger.error("Failed to access file: " + file + " - " + exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /* ---------------------------------------------------------------------
    * JSON parser
    */
    private List<String> extractPathsFromJson(String json) {
        List<String> paths = new ArrayList<>();
        var dirMatcher = Pattern.compile("\"directories\"\\s*:\\s*\\[([^]]*)\\]").matcher(json);
        if (dirMatcher.find()) {
            String section = dirMatcher.group(1);
            var pathMatcher = Pattern.compile("\"([^\"]+)\"").matcher(section);
            while (pathMatcher.find()) {
                paths.add(pathMatcher.group(1).replace("\\\\", "/"));
            }
        }

        var fileMatcher = Pattern.compile("\"files\"\\s*:\\s*\\[([^]]*)\\]").matcher(json);
        if (fileMatcher.find()) {
            String section = fileMatcher.group(1);
            var pathMatcher = Pattern.compile("\"([^\"]+)\"").matcher(section);
            while (pathMatcher.find()) {
                paths.add(pathMatcher.group(1).replace("\\\\", "/"));
            }
        }

        return paths;
    }

    /* ---------------------------------------------------------------------
    * Incremental reload + JSON sync
    */
    public void reloadTreeIncremental() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::reloadTreeIncremental);
            return;
        }

        if (projectDirectory == null || !Files.exists(projectDirectory)) return;
        if (contextMenuOpen) return;
        long now = System.currentTimeMillis();
        if (now - lastUserInteractionTime < USER_INTERACTION_PROTECTION_MS) {
            return;
        }

        List<String> savedSelection = saveSelectionAsRelativePaths();
        List<String> savedExpanded = saveExpandedPaths();
        List<String> combinedPaths = collectPathsFromJsonAndDisk();
        LinkedHashSet<String> newPaths = new LinkedHashSet<>(combinedPaths);
        Set<String> added = new LinkedHashSet<>(newPaths);
        added.removeAll(lastKnownPaths);
        Set<String> removed = new LinkedHashSet<>(lastKnownPaths);
        removed.removeAll(newPaths);

        if (added.isEmpty() && removed.isEmpty()) {
            restoreSelectionFromRelativePaths(savedSelection);
            restoreExpandedPaths(savedExpanded);
            return;
        }
        Map<String, String> renameMap = new HashMap<>();
        for (String oldRel : removed) {
            Path oldPath = projectDirectory.resolve(oldRel);
            File oldFile = oldPath.toFile();

            if (oldFile.exists()) continue;
            String oldParent = "";
            int idx = oldRel.lastIndexOf('/');
            if (idx != -1) oldParent = oldRel.substring(0, idx);

            boolean matched = false;
            for (String newRel : added) {
                String newParent = "";
                int j = newRel.lastIndexOf('/');
                if (j != -1) newParent = newRel.substring(0, j);

                if (!Objects.equals(oldParent, newParent)) continue;

                File newFile = projectDirectory.resolve(newRel).toFile();
                if (oldFile.isDirectory() == newFile.isDirectory()) {
                    renameMap.put(oldRel, newRel);
                    matched = true;
                    break;
                }
            }
        }
        for (String rem : removed) {
            if (renameMap.containsKey(rem)) continue;
            removeNodeForRelativePath(rem);
            lastKnownPaths.remove(rem);
        }

        for (Map.Entry<String, String> entry : renameMap.entrySet()) {
            String oldRel = entry.getKey();
            String newRel = entry.getValue();

            renameNode(oldRel, newRel);
            lastKnownPaths.remove(oldRel);
            lastKnownPaths.add(newRel);
            added.remove(newRel);
        }

        List<String> toInsert = new ArrayList<>(added);
        toInsert.sort(String::compareToIgnoreCase);

        for (String add : toInsert) {
            insertNodeForRelativePath(add);
            lastKnownPaths.add(add);
        }

        if (settingsFile != null && projectDirectory != null) {
            List<String> dirs = new ArrayList<>();
            List<String> files = new ArrayList<>();

            for (String p : newPaths) {
                File f = projectDirectory.resolve(p).toFile();
                if (f.isDirectory()) dirs.add(p);
                else files.add(p);
            }
            saveProjectSettings(dirs, files);
        }
        restoreSelectionFromRelativePaths(savedSelection);
        restoreExpandedPaths(savedExpanded);
    }

    /* ---------------------------------------------------------------------
    * Save .projectsettings
    */
    private void saveProjectSettings(List<String> directories, List<String> files) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"projectName\": \"").append(projectDirectory.getFileName()).append("\",\n");
            sb.append("  \"projectRoot\": \"").append(projectDirectory.toAbsolutePath().toString().replace("\\", "\\\\")).append("\",\n");
            sb.append("  \"directories\": [\n");
            for (int i = 0; i < directories.size(); i++) {
                sb.append("    \"").append(directories.get(i)).append("\"");
                if (i < directories.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ],\n");
            sb.append("  \"files\": [\n");
            for (int i = 0; i < files.size(); i++) {
                sb.append("    \"").append(files.get(i)).append("\"");
                if (i < files.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ]\n");
            sb.append("}\n");

            Files.writeString(settingsFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AppLogger.error("Failed to save project settings: " + e.getMessage());
        }
    }

    /* ---------------------------------------------------------------------
    * Node operations
    */
    private DefaultMutableTreeNode findNodeByRelativePath(String relativePath) {
        return pathNodeMap.get(relativePath);
    }

    private void insertNodeForRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) return;
        if (pathNodeMap.containsKey(relativePath)) return;

        String[] tokens = relativePath.split("/");
        StringBuilder currentPath = new StringBuilder();
        DefaultMutableTreeNode parent = rootNode;
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (currentPath.length() > 0) currentPath.append("/");
            currentPath.append(token);
            String rel = currentPath.toString();

            DefaultMutableTreeNode node = pathNodeMap.get(rel);
            if (node == null) {
                node = new DefaultMutableTreeNode(token);
                treeModel.insertNodeInto(node, parent, parent.getChildCount());
                sortChildren(parent);
                pathNodeMap.put(rel, node);
            }
            parent = node;
        }
    }

    private void removeNodeForRelativePath(String relativePath) {
        DefaultMutableTreeNode node = pathNodeMap.get(relativePath);
        if (node == null) return;
        DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
        if (parent != null) {
            treeModel.removeNodeFromParent(node);
        }
        List<String> toRemove = new ArrayList<>();
        for (String key : pathNodeMap.keySet()) {
            if (key.equals(relativePath) || key.startsWith(relativePath + "/")) {
                toRemove.add(key);
            }
        }
        for (String k : toRemove) {
            pathNodeMap.remove(k);
        }
    }

    private void renameNode(String oldRelative, String newRelative) {
        DefaultMutableTreeNode node = pathNodeMap.get(oldRelative);
        if (node == null) {
            insertNodeForRelativePath(newRelative);
            return;
        }

        String[] tokens = newRelative.split("/");
        String newName = tokens[tokens.length - 1];
        node.setUserObject(newName);
        treeModel.nodeChanged(node);
        String oldParentRel = "";
        int idx = oldRelative.lastIndexOf('/');
        if (idx != -1) oldParentRel = oldRelative.substring(0, idx);

        String newParentRel = "";
        idx = newRelative.lastIndexOf('/');
        if (idx != -1) newParentRel = newRelative.substring(0, idx);
        if (!Objects.equals(oldParentRel, newParentRel)) {
            DefaultMutableTreeNode newParent = pathNodeMap.getOrDefault(newParentRel, rootNode);
            treeModel.removeNodeFromParent(node);
            treeModel.insertNodeInto(node, newParent, newParent.getChildCount());
        }

        Map<String, DefaultMutableTreeNode> updates = new HashMap<>();
        for (Map.Entry<String, DefaultMutableTreeNode> e : pathNodeMap.entrySet()) {
            String key = e.getKey();
            if (key.equals(oldRelative) || key.startsWith(oldRelative + "/")) {
                String suffix = key.length() == oldRelative.length() ? "" : key.substring(oldRelative.length());
                String newKey = newRelative + suffix;
                updates.put(newKey, e.getValue());
            }
        }
        List<String> toRemove = new ArrayList<>();
        for (String k : pathNodeMap.keySet()) {
            if (k.equals(oldRelative) || k.startsWith(oldRelative + "/")) toRemove.add(k);
        }
        for (String k : toRemove) {
            pathNodeMap.remove(k);
        }
        pathNodeMap.putAll(updates);
    }

    /* ---------------------------------------------------------------------
    * Selection save/restore
    */
    private List<String> saveSelectionAsRelativePaths() {
        List<String> rels = new ArrayList<>();
        TreePath[] sel = projectTree.getSelectionPaths();
        if (sel == null) return rels;
        for (TreePath tp : sel) {
            String rel = getRelativePathStr(tp);
            rels.add(rel);
        }
        return rels;
    }

    private void restoreSelectionFromRelativePaths(List<String> rels) {
        if (rels == null || rels.isEmpty()) return;
        List<TreePath> toSelect = new ArrayList<>();
        for (String rel : rels) {
            DefaultMutableTreeNode node = pathNodeMap.get(rel);
            if (node != null) {
                toSelect.add(new TreePath(node.getPath()));
            }
        }
        if (!toSelect.isEmpty()) {
            TreePath[] arr = toSelect.toArray(new TreePath[0]);
            projectTree.setSelectionPaths(arr);
        }
    }

    private String getRelativePathStr(TreePath tp) {
        Object[] nodes = tp.getPath();
        if (nodes.length <= 1) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < nodes.length; i++) {
            sb.append(nodes[i].toString());
            if (i < nodes.length - 1) sb.append("/");
        }
        return sb.toString();
    }

    /* ---------------------------------------------------------------------
    * Edit / Rename / Delete
    */


    /**
     * Dispatches the selected file resource from the project tree straight into the 
     * shared workspace editor layout, avoiding window duplication and focusing tabs.
     */
    private void executeEditAction() {
        File targetFile = getSelectedFileFromTree();
        if (targetFile == null || !targetFile.isFile()) {
            JOptionPane.showMessageDialog(this, 
                "The selected item is not a valid file.", 
                "Invalid Resource", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Instantiate the frame if it is missing or has been disposed
        if (editorFrame == null || !editorFrame.isDisplayable()) {
            editorFrame = new com.sphere.ui.QuickCodeEditorFrame(null);
        }

        // Route the file internal load stream directly via the shared editor frame layout
        editorFrame.openFileInternally(targetFile);
        
        // Ensure the exact same editor window instance is brought forward to user focus layer
        if (!editorFrame.isVisible()) {
            editorFrame.setVisible(true);
        }
        editorFrame.toFront();
    }

    private void executeRenameAction() {
        File target = getSelectedFileFromTree();
        if (target == null) return;

        String currentName = target.getName();

        // Retrieve the top-level Window ancestor instead of casting to Frame to prevent ClassCastExceptions
        Window parentWindow = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parentWindow, "Rename Component", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(10, 10));
        
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JLabel label = new JLabel("Enter new name (no spaces allowed):");
        
        // Fix: Explicitly pass the text inside the constructor so it is native to the component peer before layout calculations
        JTextField textField = new JTextField(currentName, 20);
        
        mainPanel.add(label, BorderLayout.NORTH);
        mainPanel.add(textField, BorderLayout.CENTER);

        // Control buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        dialog.add(mainPanel);
        
        // Fix: Force immediate layout validation and graphics tree synchronization before geometry sizing
        mainPanel.revalidate();
        mainPanel.repaint();
        
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        // State holder array to pass final string from inside listeners
        final String[] result = {null};

        // Action when validation occurs
        Runnable onConfirm = () -> {
            result[0] = textField.getText();
            dialog.dispose();
        };

        okButton.addActionListener(e -> onConfirm.run());
        cancelButton.addActionListener(e -> dialog.dispose());

        // Bind Enter key to validate inside the text field
        textField.addActionListener(e -> onConfirm.run());

        // Bind Escape key to cancel the dialog safely
        mainPanel.registerKeyboardAction(e -> dialog.dispose(), 
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), 
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Fix: Use an early asynchronous invokeLater block to guarantee immediate text field selection overlays
        SwingUtilities.invokeLater(() -> {
            textField.requestFocusInWindow();
            textField.setCaretPosition(textField.getText().length());
            //textField.selectAll();
        });

        // This blocks execution until the dialog is closed or disposed
        dialog.setVisible(true); 

        // Process final result string if user confirmed
        String newName = result[0];
        if (newName == null) return; // Action cancelled

        // Validate that the input is not empty and contains only allowed characters
        if (newName.trim().isEmpty() || !newName.matches("^[a-zA-Z0-9._-]+$")) {
            JOptionPane.showMessageDialog(
                    this,
                    "Invalid name format.",
                    "Validation Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        File dest = new File(target.getParentFile(), newName);
        if (dest.exists()) {
            AppLogger.error("Rename rejected: target already exists.");
            return;
        }

        String rootRel = projectDirectory.toAbsolutePath().toString() + File.separator;
        String oldRel = target.getAbsolutePath().substring(rootRel.length()).replace("\\", "/");
        if (target.renameTo(dest)) {
            lastDirectorySnapshot = computeDirectorySnapshot(projectDirectory);
            String newRel = dest.getAbsolutePath().substring(rootRel.length()).replace("\\", "/");
            SwingUtilities.invokeLater(() -> {
                renameNode(oldRel, newRel);
                requestSync();
            });
        } else {
            AppLogger.error("OS failed to rename the file.");
        }
    }

    private void executeDeleteAction() {
        File target = getSelectedFileFromTree();
        if (target == null) return;

        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to permanently delete '" + target.getName() + "'?",
                "Delete Confirmation",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (confirm == JOptionPane.YES_OPTION) {
            String rootRel = projectDirectory.toAbsolutePath().toString() + File.separator;
            String rel = target.getAbsolutePath().substring(rootRel.length()).replace("\\", "/");
            if (silentDeletion(target)) {
                lastDirectorySnapshot = computeDirectorySnapshot(projectDirectory);
                SwingUtilities.invokeLater(() -> {
                    removeNodeForRelativePath(rel);
                    requestSync();
                });
            } else {
                AppLogger.error("Failed to delete file or directory.");
            }
        }
    }

    private void executeCreateFileAction() {
        if (projectDirectory == null) return;

        File baseDir = getSelectedFileFromTree();
        if (baseDir != null && baseDir.isFile()) {
            baseDir = baseDir.getParentFile();
        }
        if (baseDir == null) {
            baseDir = projectDirectory.toFile();
        }

        String name = showInputDialog("Create New File", "Enter file name:");
        if (name == null || name.trim().isEmpty() || !name.matches("^[a-zA-Z0-9._-]+$")) {
            if (name != null) {
                JOptionPane.showMessageDialog(
                        this,
                        "Invalid name format.",
                        "Validation Error",
                        JOptionPane.ERROR_MESSAGE
                );
            }
            return;
        }

        File newFile = new File(baseDir, name);
        if (newFile.exists()) {
            JOptionPane.showMessageDialog(
                    this,
                    "A file or folder with this name already exists.",
                    "Creation Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        try {
            if (newFile.createNewFile()) {
                lastDirectorySnapshot = computeDirectorySnapshot(projectDirectory);
                String rootRel = projectDirectory.toAbsolutePath().toString() + File.separator;
                String rel = newFile.getAbsolutePath().substring(rootRel.length()).replace("\\", "/");
                
                SwingUtilities.invokeLater(() -> {
                    insertNodeForRelativePath(rel);
                    reloadTreeIncremental(); // Triggers structural sync and saves project settings JSON
                });
            } else {
                AppLogger.error("OS failed to create the file.");
            }
        } catch (IOException ex) {
            AppLogger.error("Failed to create file resource: " + ex.getMessage());
        }
    }

    private void executeCreateFolderAction() {
        if (projectDirectory == null) return;

        File baseDir = getSelectedFileFromTree();
        if (baseDir != null && baseDir.isFile()) {
            baseDir = baseDir.getParentFile();
        }
        if (baseDir == null) {
            baseDir = projectDirectory.toFile();
        }

        String name = showInputDialog("Create New Folder", "Enter folder name:");
        if (name == null || name.trim().isEmpty() || !name.matches("^[a-zA-Z0-9._-]+$")) {
            if (name != null) {
                JOptionPane.showMessageDialog(
                        this,
                        "Invalid name format.",
                        "Validation Error",
                        JOptionPane.ERROR_MESSAGE
                );
            }
            return;
        }

        File newDir = new File(baseDir, name);
        if (newDir.exists()) {
            JOptionPane.showMessageDialog(
                    this,
                    "A file or folder with this name already exists.",
                    "Creation Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        if (newDir.mkdirs()) {
            lastDirectorySnapshot = computeDirectorySnapshot(projectDirectory);
            String rootRel = projectDirectory.toAbsolutePath().toString() + File.separator;
            String rel = newDir.getAbsolutePath().substring(rootRel.length()).replace("\\", "/");
            
            SwingUtilities.invokeLater(() -> {
                insertNodeForRelativePath(rel);
                reloadTreeIncremental(); // Triggers structural sync and saves project settings JSON
            });
        } else {
            AppLogger.error("OS failed to create the directory.");
        }
    }

    private String showInputDialog(String title, String labelText) {
        // Retrieve the top-level Window ancestor to prevent ClassCastExceptions
        Window parentWindow = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(parentWindow, title, Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(10, 10));
        
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JLabel label = new JLabel(labelText);
        JTextField textField = new JTextField(20);
        mainPanel.add(label, BorderLayout.NORTH);
        mainPanel.add(textField, BorderLayout.CENTER);

        // Control buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton okButton = new JButton("OK");
        JButton cancelButton = new JButton("Cancel");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        dialog.add(mainPanel);
        
        // Force immediate layout validation and graphics tree synchronization
        mainPanel.revalidate();
        mainPanel.repaint();
        
        dialog.pack();
        dialog.setLocationRelativeTo(this);

        // State holder array to pass final string from inside listeners
        final String[] result = {null};
        
        // Action when validation occurs
        Runnable onConfirm = () -> {
            result[0] = textField.getText();
            dialog.dispose();
        };

        okButton.addActionListener(e -> onConfirm.run());
        cancelButton.addActionListener(e -> dialog.dispose());

        // Bind Enter key to validate inside the text field
        textField.addActionListener(e -> onConfirm.run());
        
        // Bind Escape key to cancel the dialog safely
        mainPanel.registerKeyboardAction(e -> dialog.dispose(), 
                KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), 
                JComponent.WHEN_IN_FOCUSED_WINDOW);
                
        // Use an asynchronous invokeLater block to guarantee immediate text field focus
        SwingUtilities.invokeLater(() -> textField.requestFocusInWindow());

        // This blocks execution until the dialog is closed or disposed
        dialog.setVisible(true);
        return result[0];
    }

    /* ---------------------------------------------------------------------
    * Clipboard / Paste
    */
    private void handleClipboardAction(boolean cutMode) {
        File target = getSelectedFileFromTree();
        if (target != null) {
            clipboardSourceFile = target;
            isCutOperation = cutMode;
        }
    }

    private void handlePasteExecution() {
        if (clipboardSourceFile == null || !clipboardSourceFile.exists()) return;
        TreePath destinationPath = projectTree.getSelectionPath();
        if (destinationPath == null) return;

        File destinationBase = getSelectedFileFromTree();
        if (destinationBase != null && destinationBase.isFile()) {
            destinationBase = destinationBase.getParentFile();
        }
        if (destinationBase == null && projectDirectory != null) {
            destinationBase = projectDirectory.toFile();
        }
        if (destinationBase == null) return;

        File targetDestination = new File(destinationBase, clipboardSourceFile.getName());
        if (targetDestination.exists()) {
            AppLogger.error("Paste aborted: target already exists.");
            return;
        }

        try {
            if (clipboardSourceFile.isDirectory()) {
                copyDirectoryRecursive(clipboardSourceFile, targetDestination);
            } else {
                copyFileChannel(clipboardSourceFile, targetDestination);
            }

            if (isCutOperation) {
                silentDeletion(clipboardSourceFile);
                clipboardSourceFile = null;
                isCutOperation = false;
            }

            String rootRel = projectDirectory.toAbsolutePath().toString() + File.separator;
            String rel = targetDestination.getAbsolutePath().substring(rootRel.length()).replace("\\", "/");

            lastDirectorySnapshot = computeDirectorySnapshot(projectDirectory);
            SwingUtilities.invokeLater(() -> {
                insertNodeForRelativePath(rel);
                requestSync();
            });
        } catch (Exception ex) {
            AppLogger.error("Paste operation failed: " + ex.getMessage());
        }
    }

    private boolean silentDeletion(File fileOrDir) {
        File[] children = fileOrDir.listFiles();
        if (children != null) {
            for (File child : children) {
                silentDeletion(child);
            }
        }
        return fileOrDir.delete();
    }

    private void copyDirectoryRecursive(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            if (!dest.exists() && !dest.mkdirs()) {
                throw new IOException("Failed to create directory: " + dest);
            }
            String[] list = src.list();
            if (list != null) {
                for (String child : list) {
                    copyDirectoryRecursive(new File(src, child), new File(dest, child));
                }
            }
        } else {
            copyFileChannel(src, dest);
        }
    }

    private void copyFileChannel(File src, File dest) throws IOException {
        try (FileChannel in = new FileInputStream(src).getChannel();
             FileChannel out = new FileOutputStream(dest).getChannel()) {
            long size = in.size();
            long pos = 0;
            while (pos < size) {
                pos += in.transferTo(pos, size - pos, out);
            }
        }
    }

    private File getSelectedFileFromTree() {
        TreePath tp = projectTree.getSelectionPath();
        if (tp == null || projectDirectory == null) return null;
        String rel = getRelativePathStr(tp);
        if (rel.isEmpty()) return projectDirectory.toFile();
        return projectDirectory.resolve(rel).toFile();
    }

    /* ---------------------------------------------------------------------
    * Stabilized directory snapshot (no timestamp noise)
    */
    private long computeDirectorySnapshot(Path dir) {
        if (dir == null || !Files.exists(dir)) return 0L;
        CRC32 crc = new CRC32();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.equals(".projectsettings")) {
                        return FileVisitResult.CONTINUE;
                    }

                    crc.update(file.toString().getBytes(StandardCharsets.UTF_8));
                    crc.update(Long.toString(attrs.size()).getBytes(StandardCharsets.UTF_8));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    String name = d.getFileName() != null ? d.getFileName().toString() : "";
                    if (name.equals(".projectsettings")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    crc.update(d.toString().getBytes(StandardCharsets.UTF_8));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            AppLogger.error("Failed to compute directory snapshot: " + e.getMessage());
        }
        return crc.getValue();
    }

    /* ---------------------------------------------------------------------
    * Watchers
    */
    private void startAllWatchers() {
        if (projectDirectory == null) return;
        lastDirectorySnapshot = computeDirectorySnapshot(projectDirectory);
        
        // Fixed: Actively launch the appropriate file watchers 
        if (isLinux) {
            startWatchService();
        } else {
            startPollingWatcher();
        }
    }

    private void stopAllWatchers() {
        pollingWatcherRunning = false;
        if (pollingWatcherThread != null) {
            pollingWatcherThread.interrupt();
            pollingWatcherThread = null;
        }

        watchServiceRunning = false;
        if (watchServiceThread != null) {
            watchServiceThread.interrupt();
            watchServiceThread = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {}
            watchService = null;
        }
    }

    private void startPollingWatcher() {
        if (pollingWatcherRunning || projectDirectory == null) return;
        pollingWatcherRunning = true;
        pollingWatcherThread = new Thread(() -> {
            while (pollingWatcherRunning) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {}
                if (!pollingWatcherRunning) break;

                long snapshot = computeDirectorySnapshot(projectDirectory);
                if (snapshot != lastDirectorySnapshot) {
                    lastDirectorySnapshot = snapshot;
                    if (!pendingRefresh) {
                        pendingRefresh = true;
                        scheduledExecutor.schedule(() -> {
                            pendingRefresh = false;
                            requestSync();
                        }, PENDING_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
                    }
                }
            }
        }, "WorkspacePollingWatcher");
        pollingWatcherThread.setDaemon(true);
        pollingWatcherThread.start();
    }

    private void startWatchService() {
        if (watchServiceRunning || projectDirectory == null) return;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerAll(projectDirectory);
        } catch (IOException e) {
            AppLogger.error("Failed to start WatchService: " + e.getMessage());
            return;
        }

        watchServiceRunning = true;
        watchServiceThread = new Thread(() -> {
            while (watchServiceRunning) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException | ClosedWatchServiceException e) {
                    break;
                }
                boolean changed = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;
                    changed = true;
                }
                boolean valid = key.reset();
                if (!valid) {
                    break;
                }
                if (changed) {
                    if (!pendingRefresh) {
                        pendingRefresh = true;
                        scheduledExecutor.schedule(() -> {
                            pendingRefresh = false;
                            requestSync();
                        }, PENDING_REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
                    }
                }
            }
        }, "WorkspaceWatchService");
        watchServiceThread.setDaemon(true);
        watchServiceThread.start();
    }

    private void registerAll(final Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY
                );
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private List<String> saveExpandedPaths() {
        List<String> expanded = new ArrayList<>();
        Enumeration<TreePath> e = projectTree.getExpandedDescendants(new TreePath(rootNode.getPath()));
        if (e != null) {
            while (e.hasMoreElements()) {
                TreePath tp = e.nextElement();
                String rel = getRelativePathStr(tp);
                if (!rel.isEmpty()) expanded.add(rel);
            }
        }
        return expanded;
    }

    private void restoreExpandedPaths(List<String> expanded) {
        if (expanded == null) return;
        for (String rel : expanded) {
            DefaultMutableTreeNode node = pathNodeMap.get(rel);
            if (node != null) {
                projectTree.expandPath(new TreePath(node.getPath()));
            }
        }
    }

    private void sortChildren(DefaultMutableTreeNode parent) {
        int count = parent.getChildCount();
        if (count <= 1) return;

        List<DefaultMutableTreeNode> children = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            children.add((DefaultMutableTreeNode) parent.getChildAt(i));
        }

        children.sort((a, b) -> {
            String nameA = a.getUserObject().toString();
            String nameB = b.getUserObject().toString();

            boolean isDirA = isDirectoryNode(parent, a);
            boolean isDirB = isDirectoryNode(parent, b);

            if (isDirA && !isDirB) return -1;
            if (!isDirA && isDirB) return 1;

            return nameA.compareToIgnoreCase(nameB);
        });

        parent.removeAllChildren();
        for (DefaultMutableTreeNode n : children) {
            parent.add(n);
        }

        treeModel.nodeStructureChanged(parent);
    }

    private boolean isDirectoryNode(DefaultMutableTreeNode parent, DefaultMutableTreeNode node) {
        StringBuilder pathBuilder = new StringBuilder();
        Object[] nodes = node.getPath();
        for (int i = 1; i < nodes.length; i++) {
            pathBuilder.append(nodes[i].toString());
            if (i < nodes.length - 1) pathBuilder.append("/");
        }
        String rel = pathBuilder.toString();
        File f = projectDirectory.resolve(rel).toFile();
        return f.isDirectory();
    }

    public void refreshVisualTree() { 
        requestSync(); 
    }
    
    public void triggerStructuralAnalysisSync() { 
        requestSync(); 
    }
    
    public void disposePanel() { 
        stopAllWatchers();
        scheduledExecutor.shutdownNow(); 
    }
}

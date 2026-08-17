package com.sphere.components;

import com.sphere.components.fileexplorerincludes.ContextMenuBuilder;
import com.sphere.components.fileexplorerincludes.FileTreeCellRenderer;
import com.sphere.theme.ThemeManager;
import com.sphere.fonts.FontLoader;
import com.sphere.ui.QuickCodeEditorFrame;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

public class FileExplorer extends JTree {

    // Drag state (public so renderer can read them)
    public boolean isDragging = false;
    public TreePath hoveredPath = null;

    // Drag start point
    private Point pressPoint = null;

    // Ghost preview (macOS-style)
    private Image ghostImage = null;
    private String ghostText = null;
    private Point ghostLocation = null;
    private int ghostCount = 1;
    private final Font ghostFont = FontLoader.getGlobalFont(Font.PLAIN, 12);

    // Node index for refresh
    private final Map<File, DefaultMutableTreeNode> nodeIndex = new HashMap<>();

    // Active filter (null = no filter)
    private String activeFilter = null;

    // File system watcher
    private WatchService watchService;
    private Thread watchThread;

    // Watch key tracking for cleanup
    private final Map<WatchKey, Path> watchKeys = new HashMap<>();
    private final Map<Path, WatchKey> pathToKey = new HashMap<>();

    // Debounce for refresh (cross‑platform safe)
    private final java.util.concurrent.atomic.AtomicLong lastRefreshTime = new java.util.concurrent.atomic.AtomicLong(0L);

    private static final int SCROLL_MARGIN = 20;
    private static final int SCROLL_STEP = 16;

    private QuickCodeEditorFrame editorFrame;

    /**
     * Constructs a new File Explorer sidebar component linked to the shared workspace editor.
     * @param editorFrame The persistent workbench frame used to open code and text files.
     */
    public FileExplorer(QuickCodeEditorFrame editorFrame) {

        super(createTreeModel());
        this.editorFrame = editorFrame;
        
        setCellRenderer(new FileTreeCellRenderer());
        setRootVisible(false);
        setShowsRootHandles(true);

        installExpansionBehavior();
        installMouseBehavior();
        startWatcher();
    }

    /* -------------------------------------------------------------------------
    *  Model creation
    */
    private static TreeModel createTreeModel() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Computer");

        File[] roots = File.listRoots();
        if (roots != null) {
            for (File f : roots) {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(f);
                node.add(new DefaultMutableTreeNode("Loading..."));
                root.add(node);
            }
        }

        return new DefaultTreeModel(root);
    }

    private void assertEDT() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Must be called on EDT");
        }
    }

    /* -------------------------------------------------------------------------
    *   Lazy loading of children
    */
    private void installExpansionBehavior() {
        addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) event.getPath().getLastPathComponent();
                loadChildren(node);
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
                DefaultMutableTreeNode node =
                        (DefaultMutableTreeNode) event.getPath().getLastPathComponent();

                Object obj = node.getUserObject();
                if (obj instanceof File folder) {
                    unwatchFolder(folder);
                    removeFromIndexRecursively(node);
                }
            }
        });
    }

    private void loadChildren(DefaultMutableTreeNode parent) {
        assertEDT();

        Object obj = parent.getUserObject();
        if (!(obj instanceof File)) return;

        File folder = (File) obj;

        // Avoid reloading if already loaded
        if (parent.getChildCount() > 0) {
            DefaultMutableTreeNode first =
                    (DefaultMutableTreeNode) parent.getChildAt(0);

            if (!(first.getUserObject() instanceof String)) {
                return;
            }
        }

        parent.removeAllChildren();
        nodeIndex.put(folder.getAbsoluteFile(), parent);

        // Register folder for file system watching
        watchFolder(folder);

        File[] files = folder.listFiles();
        if (files == null) {
            ((DefaultTreeModel) getModel()).nodeStructureChanged(parent);
            return;
        }

        java.util.Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (File f : files) {
            if (f.isHidden()) continue;

            DefaultMutableTreeNode node = new DefaultMutableTreeNode(f);
            nodeIndex.put(f.getAbsoluteFile(), node);

            if (f.isDirectory()) {
                node.add(new DefaultMutableTreeNode("Loading..."));
            }

            parent.add(node);
        }

        ((DefaultTreeModel) getModel()).nodeStructureChanged(parent);
    }

    private void removeFromIndexRecursively(DefaultMutableTreeNode node) {
        Object obj = node.getUserObject();
        if (obj instanceof File f) {
            nodeIndex.remove(f.getAbsoluteFile());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            removeFromIndexRecursively((DefaultMutableTreeNode) node.getChildAt(i));
        }
    }

    /* -------------------------------------------------------------------------
     * Mouse behavior (selection, drag, context menu + autoscroll)
     * ------------------------------------------------------------------------- */
    private void installMouseBehavior() {

        addMouseListener(new MouseAdapter() {

            @Override
            public void mousePressed(MouseEvent e) {
                pressPoint = e.getPoint();
                isDragging = false;

                // Prepare ghost preview if pressed on a valid node
                TreePath[] selection = getSelectionPaths();
                ghostCount = (selection != null) ? selection.length : 1;

                TreePath path = getPathForLocation(e.getX(), e.getY());
                if (path != null) {
                    DefaultMutableTreeNode node =
                            (DefaultMutableTreeNode) path.getLastPathComponent();

                    File f = getFileFromNode(node);

                    if (f != null) {
                        ghostText = (ghostCount == 1)
                                ? f.getName()
                                : f.getName() + " + " + (ghostCount - 1) + " more";

                        Icon icon = ((FileTreeCellRenderer) getCellRenderer()).getIconForFile(f);
                        ghostImage = iconToImage(icon);
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                setCursor(Cursor.getDefaultCursor());

                if (isDragging) {
                    TreePath sourcePath = getPathForLocation(pressPoint.x, pressPoint.y);
                    TreePath targetPath = getPathForEvent(e);

                    if (sourcePath != null && targetPath != null) {
                        DefaultMutableTreeNode srcNode =
                                (DefaultMutableTreeNode) sourcePath.getLastPathComponent();
                        DefaultMutableTreeNode tgtNode =
                                (DefaultMutableTreeNode) targetPath.getLastPathComponent();

                        File srcFile = getFileFromNode(srcNode);
                        File tgtFolder = getFileFromNode(tgtNode);

                        if (srcFile != null && tgtFolder != null && tgtFolder.isDirectory()) {
                            setSelectionPath(targetPath);

                            // FIXED: Injected the required 'editorFrame' reference into the argument list
                            JPopupMenu menu = ContextMenuBuilder.createMenu(
                                    srcFile,
                                    "RESTRICTED",
                                    tgtFolder,
                                    FileExplorer.this,
                                    FileExplorer.this.editorFrame
                            );

                            menu.show(e.getComponent(), e.getX(), e.getY());
                        }
                    }
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    // Normal context menu on right-click without drag
                    TreePath path = getPathForEvent(e);
                    if (path != null) {
                        setSelectionPath(path);

                        DefaultMutableTreeNode node =
                                (DefaultMutableTreeNode) path.getLastPathComponent();

                        File file = getFileFromNode(node);
                        if (file != null) {
                            // FIXED: Injected the required 'editorFrame' reference into the argument list
                            JPopupMenu menu = ContextMenuBuilder.createMenu(
                                    file,
                                    "FULL",
                                    FileExplorer.this,
                                    FileExplorer.this.editorFrame
                            );
                            menu.show(e.getComponent(), e.getX(), e.getY());
                        }
                    }
                }

                // Clear drag visuals
                ghostImage = null;
                ghostText = null;
                ghostLocation = null;
                ghostCount = 1;
                hoveredPath = null;
                isDragging = false;

                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    TreePath path = getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        File file = getFileFromNode((DefaultMutableTreeNode) path.getLastPathComponent());

                        if (file != null && file.isFile()) {
                            if (editorFrame != null) {
                                editorFrame.openFileInternally(file);
                            }
                        }
                    }
                }
            }
        });

        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                // Start drag only if mouse moved enough
                if (!isDragging) {
                    if (pressPoint == null || pressPoint.distance(e.getPoint()) < 5) {
                        return;
                    }
                    isDragging = true;
                }

                // Ghost follows the mouse with a small offset
                ghostLocation = new Point(e.getX() + 14, e.getY() + 14);

                // Update hovered path for visual highlight
                TreePath path = getPathForEvent(e);
                hoveredPath = path;

                // Optional: change cursor depending on target
                if (path != null) {
                    DefaultMutableTreeNode node =
                            (DefaultMutableTreeNode) path.getLastPathComponent();
                    File f = getFileFromNode(node);
                    if (f != null && f.isDirectory()) {
                        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    } else {
                        setCursor(Cursor.getDefaultCursor());
                    }
                } else {
                    setCursor(Cursor.getDefaultCursor());
                }

                // Manual autoscroll (top / bottom)
                Rectangle visible = getVisibleRect();
                if (e.getY() < visible.y + SCROLL_MARGIN) {
                    visible.y = Math.max(0, visible.y - SCROLL_MARGIN);
                    scrollRectToVisible(visible);
                } else if (e.getY() > visible.y + visible.height - SCROLL_MARGIN) {
                    visible.y = visible.y + SCROLL_MARGIN;
                    scrollRectToVisible(visible);
                }

                repaint();
            }
        });
    }

    private TreePath getPathForEvent(MouseEvent e) {
        int row = getClosestRowForLocation(e.getX(), e.getY());
        if (row == -1) return null;

        Rectangle bounds = getRowBounds(row);
        if (bounds != null && bounds.contains(e.getPoint())) {
            return getPathForRow(row);
        }

        return null;
    }

    /* -------------------------------------------------------------------------
    *   Paint: file move displayer logic
    */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (isDragging && ghostImage != null && ghostLocation != null) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int x = ghostLocation.x;
            int y = ghostLocation.y;

            int iconW = ghostImage.getWidth(null);
            int iconH = ghostImage.getHeight(null);

            g2.setFont(ghostFont);
            FontMetrics fm = g2.getFontMetrics();
            int textW = (ghostText != null) ? fm.stringWidth(ghostText) : 0;
            int textH = fm.getHeight();

            int padding = 10;
            int boxW = iconW + textW + padding * 3;
            int boxH = Math.max(iconH, textH) + padding * 2;

            // Drop shadow
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
            g2.setColor(Color.BLACK);
            g2.fillRoundRect(x + 3, y + 3, boxW, boxH, 14, 14);

            // Translucent macOS-style background
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            g2.setColor(new Color(255, 255, 255, 200));
            g2.fillRoundRect(x, y, boxW, boxH, 14, 14);

            // Light border
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            g2.setColor(new Color(255, 255, 255, 180));
            g2.drawRoundRect(x, y, boxW, boxH, 14, 14);

            // Icon
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.95f));
            g2.drawImage(ghostImage, x + padding, y + padding, this);

            // Text
            if (ghostText != null) {
                g2.setColor(Color.BLACK);
                g2.drawString(ghostText, x + iconW + padding * 2, y + padding + fm.getAscent());
            }

            // Group badge if multiple items
            if (ghostCount > 1) {
                String badge = ghostCount + " items";

                int bw = fm.stringWidth(badge) + 12;
                int bh = fm.getHeight() + 6;

                int bx = x + boxW - bw - 8;
                int by = y + boxH - bh - 8;

                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
                g2.setColor(new Color(0, 0, 0, 180));
                g2.fillRoundRect(bx, by, bw, bh, 10, 10);

                g2.setColor(Color.WHITE);
                g2.drawString(badge, bx + 6, by + fm.getAscent() + 2);
            }

            g2.dispose();
        }
    }

    private Image iconToImage(Icon icon) {
        if (icon == null) return null;

        if (icon instanceof ImageIcon) {
            return ((ImageIcon) icon).getImage();
        }

        BufferedImage img = new BufferedImage(
                icon.getIconWidth(),
                icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics g = img.getGraphics();
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        return img;
    }

    /* -------------------------------------------------------------------------
    *  Refresh API
    */
    public void refreshNode(File folder) {
        if (folder == null) return;

        File key = folder.getAbsoluteFile();
        DefaultMutableTreeNode node = nodeIndex.get(key);

        if (node != null) {
            reloadNodeIncrementally(node, folder);
            return;
        }

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) getModel().getRoot();
        DefaultMutableTreeNode found = findNode(root, key);

        if (found != null) {
            nodeIndex.put(key, found);
            reloadNodeIncrementally(found, folder);
        }
    }

    private void reloadNodeIncrementally(DefaultMutableTreeNode parent, File folder) {
        // If the folder has never been expanded (it still contains "Loading..."), do nothing
        if (parent.getChildCount() == 1) {
            DefaultMutableTreeNode first = (DefaultMutableTreeNode) parent.getChildAt(0);
            if (first.getUserObject() instanceof String) {
                return; 
            }
        }

        File[] files = folder.listFiles();
        if (files == null) files = new File[0];

        // Filter out hidden files to match the loadChildren behavior
        java.util.List<File> currentFiles = new java.util.ArrayList<>();
        for (File f : files) {
            if (!f.isHidden()) {
                currentFiles.add(f);
            }
        }

        // Sort: Directories first, then alphabetical order (case-insensitive)
        currentFiles.sort((a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        DefaultTreeModel model = (DefaultTreeModel) getModel();

        // 1. Remove nodes that no longer exist on the local disk
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            File childFile = getFileFromNode(child);
            if (childFile != null && !currentFiles.contains(childFile)) {
                nodeIndex.remove(childFile.getAbsoluteFile());
                model.removeNodeFromParent(child);
            }
        }

        // 2. Insert new elements or reposition existing ones at the correct sort index
        for (int i = 0; i < currentFiles.size(); i++) {
            File f = currentFiles.get(i);
            DefaultMutableTreeNode existingNode = nodeIndex.get(f.getAbsoluteFile());

            if (existingNode != null && existingNode.getParent() == parent) {
                // The node already exists. If its index changed due to renaming or sorting, move it
                int actualIndex = parent.getIndex(existingNode);
                if (actualIndex != i) {
                    model.removeNodeFromParent(existingNode);
                    model.insertNodeInto(existingNode, parent, i);
                }
            } else {
                // New file or folder detected!
                DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(f);
                nodeIndex.put(f.getAbsoluteFile(), newNode);

                if (f.isDirectory()) {
                    newNode.add(new DefaultMutableTreeNode("Loading..."));
                }

                model.insertNodeInto(newNode, parent, i);
            }
        }
    }

    private DefaultMutableTreeNode findNode(DefaultMutableTreeNode parent, File target) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child =
                    (DefaultMutableTreeNode) parent.getChildAt(i);

            Object obj = child.getUserObject();
            if (obj instanceof File) {
                File f = ((File) obj).getAbsoluteFile();
                if (f.equals(target)) {
                    return child;
                }
            }

            DefaultMutableTreeNode result = findNode(child, target);
            if (result != null) return result;
        }
        return null;
    }

    private File getFileFromNode(DefaultMutableTreeNode node) {
        Object obj = node.getUserObject();
        return (obj instanceof File) ? (File) obj : null;
    }

    /* -------------------------------------------------------------------------
    *   Filtering (live, non-destructive)
    */
    public void clearFilter() {
        assertEDT();
        activeFilter = null;
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) getModel().getRoot();
        restoreAllNodesFromDiskIndex(root);
        ((DefaultTreeModel) getModel()).nodeStructureChanged(root);
    }

    private void restoreAllNodesFromDiskIndex(DefaultMutableTreeNode node) {
        Object obj = node.getUserObject();
        if (obj instanceof File folder && folder.isDirectory()) {
            // Check if we need to reconstruct the visual children from our current disk files
            // instead of blindly querying folder.listFiles() again on the main thread.
            File[] files = folder.listFiles();
            if (files != null) {
                node.removeAllChildren();
                for (File f : files) {
                    if (f.isHidden()) continue;
                    DefaultMutableTreeNode childNode = nodeIndex.get(f.getAbsoluteFile());
                    if (childNode == null) {
                        childNode = new DefaultMutableTreeNode(f);
                        if (f.isDirectory()) {
                            childNode.add(new DefaultMutableTreeNode("Loading..."));
                        }
                    }
                    node.add(childNode);
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            TreeNode child = node.getChildAt(i);
            if (child instanceof DefaultMutableTreeNode mutableChild) {
                restoreAllNodesFromDiskIndex(mutableChild);
            }
        }
    }

    public void applyFilter(String query) {
        assertEDT();

        if (query == null || query.isEmpty()) {
            clearFilter();
            return;
        }

        activeFilter = query.toLowerCase();

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) getModel().getRoot();
        filterNode(root);

        ((DefaultTreeModel) getModel()).nodeStructureChanged(root);
    }

    private void reloadAllNodes(DefaultMutableTreeNode node) {
        Object obj = node.getUserObject();
        if (obj instanceof File folder && folder.isDirectory()) {
            node.removeAllChildren();
            node.add(new DefaultMutableTreeNode("Loading..."));
            loadChildren(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            reloadAllNodes((DefaultMutableTreeNode) node.getChildAt(i));
        }
    }

    private boolean filterNode(DefaultMutableTreeNode node) {
        boolean keep = false;

        for (int i = node.getChildCount() - 1; i >= 0; i--) {
            DefaultMutableTreeNode child =
                    (DefaultMutableTreeNode) node.getChildAt(i);

            if (!filterNode(child)) {
                node.remove(i);
            } else {
                keep = true;
            }
        }

        Object obj = node.getUserObject();
        if (obj instanceof File file) {
            if (activeFilter == null || file.getName().toLowerCase().contains(activeFilter)) {
                keep = true;
            }
        }

        return keep;
    }

    /* -------------------------------------------------------------------------
    *  File system watcher (cross‑platform, debounced, EDT‑safe)
    */
    private void startWatcher() {
        try {
            watchService = FileSystems.getDefault().newWatchService();

            watchThread = new Thread(() -> {
                while (true) {
                    try {
                        WatchKey key = watchService.take();
                        Path dir = (Path) key.watchable();

                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();
                            if (kind == OVERFLOW) continue;

                            Path changed = (Path) event.context();
                            Path fullPath = dir.resolve(changed);

                            File changedFile = fullPath.toFile();
                            File parentFolder = changedFile.getParentFile();

                            if (parentFolder != null) {
                                long now = System.currentTimeMillis();
                                // Thread-safe atomic check and update for the debounce timer
                                if (now - lastRefreshTime.get() > 200) {
                                    lastRefreshTime.set(now);
                                    SwingUtilities.invokeLater(() -> refreshNode(parentFolder));
                                }
                            }

                            if (kind == ENTRY_DELETE) {
                                File deleted = fullPath.toFile();
                                // Note: checking isDirectory() on a deleted local path might return false, 
                                // but if it is tracked in our active watcher mappings, we unwatch it safely.
                                SwingUtilities.invokeLater(() -> {
                                    unwatchFolder(deleted);
                                    // Note: nodeIndex cleanup will automatically happen during the next refresh cycle
                                });
                            }
                        }

                        key.reset();

                    } catch (Exception ignored) {}
                }
            });

            watchThread.setDaemon(true);
            watchThread.start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void watchFolder(File folder) {
        assertEDT();
        try {
            if (watchService == null) return;
            Path path = folder.toPath();

            if (pathToKey.containsKey(path)) return; // already watched

            WatchKey key = path.register(watchService,
                    ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);

            watchKeys.put(key, path);
            pathToKey.put(path, key);

        } catch (Exception ignored) {}
    }

    private void unwatchFolder(File folder) {
        assertEDT();
        if (folder == null) return;
        Path path = folder.toPath();
        WatchKey key = pathToKey.remove(path);
        if (key != null) {
            watchKeys.remove(key);
            key.cancel();
        }
    }
}


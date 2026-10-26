package com.sphere.components.fileexplorerincludes;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.io.*;
import java.nio.file.*;
import java.awt.*;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;

import com.sphere.components.SphereCoder;
import com.sphere.components.MdTexEditor;
import com.sphere.components.UndoRedoUtility;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;
import com.sphere.utils.IconManager;
import com.sphere.ui.SphereCoderFrame;
import com.sphere.core.python.jupyterlab.JupylabXeditor;

public class ContextMenuBuilder {

    /** Thin 1px separator line matching UI style guidelines */
    private static final class ThinSeparator extends JSeparator {
        @Override
        public void paint(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(new Color(255, 255, 255, 30));
            g2.drawLine(6, 0, getWidth() - 6, 0);
            g2.dispose();
        }
    }

    /** The menu's separator, for items another explorer adds after the built ones. */
    public static JSeparator thinSeparator() {
        return new ThinSeparator();
    }

    /* FULL mode (right-click contextual menu execution) */
    public static JPopupMenu createMenu(File selectedFile, String mode, FileTreeView explorer, SphereCoderFrame editorFrame) {
        return createMenu(selectedFile, mode, null, explorer, editorFrame);
    }

    /* Main context construction entry point */
    public static JPopupMenu createMenu(File sourceFile, String mode, File targetFolder, FileTreeView explorer, SphereCoderFrame editorFrame) {
        JPopupMenu menu = new JPopupMenu() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                ThemePalette palette = ThemeManager.getCurrentPalette();
                if (palette != null) {
                    g2.setColor(palette.getBackgroundSurface().brighter());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    
                    // Razor-thin line-weight bounding frame border (1px)
                    g2.setColor(new Color(255, 255, 255, 30));
                    g2.setStroke(new BasicStroke(1.0f));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                }
                g2.dispose();
            }
        };
        menu.setOpaque(false);
        menu.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        /* -------------------------------------------------------------
         * NEW FILE / NEW FOLDER INTERACTIONS
         * -------------------------------------------------------------*/
        if ("FULL".equals(mode)) {

            JMenuItem newFile = createModernMenuItem("New File");
            newFile.addActionListener(e -> {
                File target = sourceFile.isDirectory() ? sourceFile : sourceFile.getParentFile();
                createNewFile(target, explorer);
            });
            menu.add(newFile);

            JMenuItem newFolder = createModernMenuItem("New Folder");
            newFolder.addActionListener(e -> {
                File target = sourceFile.isDirectory() ? sourceFile : sourceFile.getParentFile();
                createNewFolder(target, explorer);
            });
            menu.add(newFolder);

            menu.add(new ThinSeparator());

            JMenuItem editItem = createModernMenuItem("Edit");
            // Routed directly into the persistent shared UI layout frame
            editItem.addActionListener(e -> handleEdit(sourceFile, editorFrame));
            menu.add(editItem);

            if (sourceFile.isFile() && editorFrame != null
                    && !com.sphere.components.imaging.ImageFileIO.isImage(sourceFile)
                    && !com.sphere.components.rootview.RootFile.isRootFile(sourceFile)
                    && !sourceFile.getName().toLowerCase().endsWith(".ipynb")) {
                // SphereCoder beside what is already open: a split group on the right.
                JMenuItem sideItem = createModernMenuItem("Open to the Side");
                sideItem.addActionListener(e -> editorFrame.openFileToSide(sourceFile));
                menu.add(sideItem);
            }

            if (com.sphere.components.rootlab.RootLabPanel.exists()) {
                // The ROOT Lab reads any CERN data file, and every one in a folder.
                JMenuItem lab = createModernMenuItem(sourceFile.isDirectory()
                        ? "Open Folder in ROOT Lab" : "Open in ROOT Lab");
                lab.addActionListener(e -> com.sphere.components.rootlab.RootLabPanel.instance()
                        .open(sourceFile.toPath()));
                menu.add(lab);
            }

            if (sourceFile.isFile() && sourceFile.getName().toLowerCase().endsWith(".gdml")) {
                // A Geant4 geometry opens in the geometry editor; Edit keeps the XML
                JMenuItem g4 = createModernMenuItem("Open in G4editor");
                g4.addActionListener(e -> SwingUtilities.invokeLater(() ->
                        com.sphere.core.commands.G4Commands.openEditor(sourceFile.toPath())));
                menu.add(g4);
            }

            if (sourceFile.isFile() && sourceFile.getName().toLowerCase().endsWith(".root")
                    && holdsGeometry(sourceFile)) {
                // A ROOT file holding a TGeoManager is a geometry G4editor reads and edits
                JMenuItem g4 = createModernMenuItem("Open geometry in G4editor");
                g4.addActionListener(e -> SwingUtilities.invokeLater(() ->
                        com.sphere.core.commands.G4Commands.openEditor(sourceFile.toPath())));
                menu.add(g4);
            }

            JMenuItem duplicate = createModernMenuItem("Duplicate");
            duplicate.addActionListener(e -> duplicateFile(sourceFile, explorer));
            menu.add(duplicate);

            JMenuItem renameItem = createModernMenuItem("Rename");
            renameItem.addActionListener(e -> handleRename(sourceFile, explorer));
            menu.add(renameItem);

            JMenuItem deleteItem = createModernMenuItem("Delete");
            deleteItem.addActionListener(e -> handleDelete(sourceFile, explorer));
            menu.add(deleteItem);

            menu.add(new ThinSeparator());

            JMenuItem properties = createModernMenuItem("Properties");
            properties.addActionListener(e -> showProperties(sourceFile));
            menu.add(properties);

            // --- COPY PATH ITEMS ---
            JMenuItem copyAbs = createModernMenuItem("Copy Absolute Path");
            copyAbs.addActionListener(e -> copyToClipboard(sourceFile.getAbsolutePath()));
            menu.add(copyAbs);

            JMenuItem copyName = createModernMenuItem("Copy Name");
            copyName.addActionListener(e -> copyToClipboard(sourceFile.getName()));
            menu.add(copyName);

            // ------------------------

            JMenuItem openSystem = createModernMenuItem("Open in System Explorer");
            openSystem.addActionListener(e -> openInSystemExplorer(sourceFile));
            menu.add(openSystem);

            menu.add(new ThinSeparator());

            JMenuItem addFav = createModernMenuItem("Add to Favorites");
            addFav.addActionListener(e -> FavoritesManager.addFavorite(sourceFile));
            menu.add(addFav);

            JMenuItem removeFav = createModernMenuItem("Remove from Favorites");
            removeFav.addActionListener(e -> FavoritesManager.removeFavorite(sourceFile));
            menu.add(removeFav);

            if (explorer != null) {
                menu.add(new ThinSeparator());

                JCheckBoxMenuItem hiddenItem =
                        createModernCheckItem("Display Hiddens", explorer.isShowHidden());
                hiddenItem.addActionListener(e -> explorer.setShowHidden(hiddenItem.isSelected()));
                menu.add(hiddenItem);
            }
        }

        /* -------------------------------------------------------------
         * RESTRICTED OPERATIONS MODE (drag and drop handlers)
         * -------------------------------------------------------------*/
        if (targetFolder != null && explorer != null) {

            JMenuItem copyItem = createModernMenuItem("Copy Here");
            copyItem.addActionListener(e -> performCopyMove(sourceFile, targetFolder, false, explorer));
            menu.add(copyItem);

            JMenuItem moveItem = createModernMenuItem("Move Here");
            moveItem.addActionListener(e -> performCopyMove(sourceFile, targetFolder, true, explorer));
            menu.add(moveItem);
        }

        /* -------------------------------------------------------------
         * Dynamic text metric layout sizing adaptation listeners
         * -------------------------------------------------------------*/
        menu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    int maxTextWidth = 100;
                    Font font = FontLoader.getGlobalFont(Font.PLAIN, 12);
                    FontMetrics fm = menu.getFontMetrics(font);
                    
                    for (Component comp : menu.getComponents()) {
                        if (comp instanceof JMenuItem && comp.isVisible()) {
                            int textWidth = fm.stringWidth(((JMenuItem) comp).getText());
                            if (textWidth > maxTextWidth) {
                                maxTextWidth = textWidth;
                            }
                        }
                    }

                    int targetWidth = maxTextWidth + 32;
                    menu.setPreferredSize(new Dimension(targetWidth, menu.getPreferredSize().height));
                    menu.revalidate();
                });
            }

            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        return menu;
    }

    private static void copyToClipboard(String text) {
        com.sphere.components.ClipboardBridge.write(text);
    }


    /*--------------------------------------------------------------
     * Helper to build fully custom flat menu items with manually
     * drawn background selections and aligned fonts.
     *--------------------------------------------------------------*/
    /** Same look as createModernMenuItem, with a tick drawn when selected. */
    private static JCheckBoxMenuItem createModernCheckItem(String text, boolean selected) {
        JCheckBoxMenuItem item = new JCheckBoxMenuItem(text, selected) {
            @Override
            protected void paintComponent(Graphics g) {
                ButtonModel model = getModel();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                ThemePalette palette = ThemeManager.getCurrentPalette();

                if (model.isArmed()) {
                    g2.setColor(palette != null ? palette.getButtonPressed() : new Color(0x3A3A3A));
                    g2.fillRoundRect(4, 2, getWidth() - 8, getHeight() - 4, 6, 6);
                    g2.setColor(Color.WHITE);
                } else {
                    g2.setColor(palette != null ? palette.getTextPrimary() : Color.LIGHT_GRAY);
                }

                if (isSelected()) {
                    g2.setStroke(new BasicStroke(1.6f));
                    int cy = getHeight() / 2;
                    g2.drawLine(12, cy, 15, cy + 4);
                    g2.drawLine(15, cy + 4, 21, cy - 4);
                }

                g2.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                FontMetrics fm = g2.getFontMetrics();
                int textY = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();

                g2.drawString(getText(), 28, textY);
                g2.dispose();
            }
        };

        item.setOpaque(false);
        item.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        return item;
    }

    public static JMenuItem createModernMenuItem(String text) {
        JMenuItem item = new JMenuItem(text) {
            @Override
            protected void paintComponent(Graphics g) {
                ButtonModel model = getModel();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                
                ThemePalette palette = ThemeManager.getCurrentPalette();
                
                // Unified hover state selection fill
                if (model.isArmed() || model.isSelected()) {
                    g2.setColor(palette != null ? palette.getButtonPressed() : new Color(0x3A3A3A));
                    g2.fillRoundRect(4, 2, getWidth() - 8, getHeight() - 4, 6, 6);
                    g2.setColor(Color.WHITE);
                } else {
                    g2.setColor(palette != null ? palette.getTextPrimary() : Color.LIGHT_GRAY);
                }
                
                g2.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
                FontMetrics fm = g2.getFontMetrics();
                int textY = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                
                g2.drawString(getText(), 12, textY);
                g2.dispose();
            }
        };
        
        item.setOpaque(false);
        item.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        return item;
    }

    /* -------------------------------------------------------------
     * FILE CREATION INTERACTION
     *-------------------------------------------------------------*/
    private static void createNewFile(File folder, FileTreeView explorer) {
        String name = JOptionPane.showInputDialog("Enter file name:");
        if (name == null || name.trim().isEmpty()) return;

        File newFile = new File(folder, name);
        try {
            if (newFile.createNewFile()) {
                explorer.refreshNode(folder);
            } else {
                JOptionPane.showMessageDialog(null, "Failed to create file.");
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(null, "Error: " + ex.getMessage());
        }
    }

    /* -------------------------------------------------------------
     * DIRECTORY CREATION INTERACTION
     *-------------------------------------------------------------*/
    private static void createNewFolder(File folder, FileTreeView explorer) {
        String name = JOptionPane.showInputDialog("Enter folder name:");
        if (name == null || name.trim().isEmpty()) return;

        File newFolder = new File(folder, name);
        if (newFolder.mkdir()) {
            explorer.refreshNode(folder);
        } else {
            JOptionPane.showMessageDialog(null, "Failed to create folder.");
        }
    }

    /* -------------------------------------------------------------
     * RESOURCE DUPLICATION
     *-------------------------------------------------------------*/
    private static void duplicateFile(File file, FileTreeView explorer) {
        File parent = file.getParentFile();
        String base = file.getName();
        String copyName = base + "_copy";

        File dest = new File(parent, copyName);
        int counter = 1;

        while (dest.exists()) {
            dest = new File(parent, copyName + "_" + counter);
            counter++;
        }

        try {
            if (file.isDirectory()) {
                copyDirectoryRecursive(file.toPath(), dest.toPath());
            } else {
                Files.copy(file.toPath(), dest.toPath());
            }
            explorer.refreshNode(parent);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Error duplicating: " + ex.getMessage());
        }
    }

    /* -------------------------------------------------------------
     * METADATA PROPERTIES VIEW
     */
    private static void showProperties(File file) {
        PropertiesDialog.show(null, file);
    }

    /* -------------------------------------------------------------
     * SYSTEM-NATIVE FILE EXPLORER LAUNCHER (cross-platform)
     *-------------------------------------------------------------*/
    private static void openInSystemExplorer(File file) {
        try {
            Desktop.getDesktop().open(file.isDirectory() ? file : file.getParentFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Cannot open system explorer.");
        }
    }

    /* -------------------------------------------------------------
     * SYSTEM MUTATION ROUTINES (RENAME, DELETE, COPY, MOVE)
     *-------------------------------------------------------------*/
    private static void handleRename(File file, FileTreeView explorer) {
        // FIXED: Re-added the default text input value containing the file's current name
        String newName = JOptionPane.showInputDialog("Enter new name:", file.getName());
        if (newName == null || newName.trim().isEmpty()) return;

        File dest = new File(file.getParentFile(), newName);

        if (dest.exists()) {
            JOptionPane.showMessageDialog(null, "A file with that name already exists.");
            return;
        }

        if (file.renameTo(dest)) {
            explorer.fileMoved(file, dest);
            explorer.refreshNode(file.getParentFile());
        } else {
            JOptionPane.showMessageDialog(null, "Failed to rename file.");
        }
    }

    private static void handleDelete(File file, FileTreeView explorer) {
        int confirm = JOptionPane.showConfirmDialog(
                null,
                "Delete " + file.getName() + "?",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION
        );

        if (confirm != JOptionPane.YES_OPTION) return;

        boolean ok = file.isDirectory()
                ? deleteDirectoryRecursive(file)
                : file.delete();

        if (!ok) {
            JOptionPane.showMessageDialog(null, "Failed to delete file.");
            return;
        }

        explorer.refreshNode(file.getParentFile());
    }

    /**
     * Deletes a folder and what it holds, without ever going through a link.
     *
     * File.isDirectory() follows symbolic links and Windows junctions, so the
     * previous walk went into a linked folder and emptied it: deleting a project
     * that held a link to a data area deleted the data area. A link, or a
     * reparse point that is not a plain folder, is now removed as the link it
     * is, and what it points to is left alone.
     */
    public static boolean deleteDirectoryRecursive(File dir) {
        final Path root = dir.toPath().toAbsolutePath().normalize();
        try {
            if (pointsElsewhere(root)) {
                Files.delete(root);                             // the link itself, not its target
                return true;
            }
            Files.walkFileTree(root, java.util.EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes attrs)
                            throws IOException {
                        if (!d.equals(root) && (attrs.isSymbolicLink() || pointsElsewhere(d))) {
                            Files.delete(d);                    // the junction itself, not its target
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes attrs)
                            throws IOException {
                        Files.delete(f);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path d, IOException failed) throws IOException {
                        if (failed != null) {
                            throw failed;
                        }
                        Files.delete(d);
                        return FileVisitResult.CONTINUE;
                    }
                });
            return true;
        } catch (IOException couldNot) {
            return false;
        }
    }

    /**
     * True when a folder entry leads somewhere else than where it sits: a
     * symbolic link, or a Windows junction (which Java does not report as a
     * link). Its real path is then not its parent's real path plus its name.
     */
    /**
     * True when a ROOT file holds a geometry (a TGeoManager key). Only the key
     * list is read, which costs a few small reads whatever the file's size.
     */
    private static boolean holdsGeometry(File file) {
        try {
            return !com.sphere.core.geant4.TGeoReader.geometries(file.toPath()).isEmpty();
        } catch (IOException | RuntimeException notReadable) {
            return false;
        }
    }

    private static boolean pointsElsewhere(Path entry) throws IOException {
        return com.sphere.core.fs.FsSupport.pointsElsewhere(entry);
    }

    private static void performCopyMove(File source, File targetDir, boolean isMove, FileTreeView explorer) {
        if (source.equals(targetDir)) {
            JOptionPane.showMessageDialog(null, "Cannot move/copy into itself.");
            return;
        }

        if (source.isDirectory() && targetDir.toPath().startsWith(source.toPath())) {
            JOptionPane.showMessageDialog(null, "Cannot move a folder into its own subfolder.");
            return;
        }

        Path sourcePath = source.toPath();
        Path targetPath = targetDir.toPath().resolve(source.getName());

        // Moving or copying onto an existing entry replaced it without a word.
        if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) && !targetPath.equals(sourcePath)) {
            int answer = JOptionPane.showConfirmDialog(
                    null,
                    targetDir.getName() + " already holds " + source.getName() + ".\nReplace it?",
                    isMove ? "Confirm Move" : "Confirm Copy",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }

        try {
            if (isMove) {
                Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                if (source.isDirectory()) {
                    copyDirectoryRecursive(sourcePath, targetPath);
                } else {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            if (isMove) explorer.fileMoved(source, targetPath.toFile());
            explorer.refreshNode(targetDir);
            if (isMove) explorer.refreshNode(source.getParentFile());

        } catch (IOException ex) {
            JOptionPane.showMessageDialog(null, "Error: " + ex.getMessage());
        }
    }

    private static void copyDirectoryRecursive(Path src, Path dest) throws IOException {
        // Files.walk holds a directory handle open per level until closed.
        try (java.util.stream.Stream<Path> tree = Files.walk(src)) {
            tree.forEach(path -> {
                try {
                    Path relative = src.relativize(path);
                    Path target = dest.resolve(relative);

                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /* -------------------------------------------------------------
     * WORKSPACE EDITOR INITIALIZATION
     * -------------------------------------------------------------*/
    private static void handleEdit(File file, SphereCoderFrame editorFrame) {
        if (file == null || !file.isFile()) {
            JOptionPane.showMessageDialog(null, "Cannot edit a directory.");
            return;
        }

        String fileNameLower = file.getName().toLowerCase();

        // PNG, JPG and SVG go to the image editor; the code editor would only
        // show their bytes.
        if (com.sphere.components.imaging.ImageFileIO.isImage(file)) {
            SwingUtilities.invokeLater(() -> com.sphere.ui.ImageEditorFrame.show(file));
            return;
        }

        // A .root file goes to the ROOT viewer for the same reason.
        if (com.sphere.components.rootview.RootFile.isRootFile(file)) {
            SwingUtilities.invokeLater(() -> com.sphere.ui.RootViewerFrame.show(file));
            return;
        }

        // Intercept and load Jupyter Notebook format targets via standalone view instance
        if (fileNameLower.endsWith(".ipynb")) {
            SwingUtilities.invokeLater(() -> {
                try {
                    com.sphere.core.python.jupyterlab.JupylabXeditor editor = 
                        new com.sphere.core.python.jupyterlab.JupylabXeditor(file.toPath());
                    
                    // Let the internal window handler manage validation via dispose()
                    editor.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    editor.setVisible(true);
                    editor.toFront();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(null, "Error opening Jupyter Notebook editor: " + ex.getMessage());
                }
            });
        }
        // Keep the dedicated standalone window setup specifically for Markdown files
        else if (fileNameLower.endsWith(".md")) {
            try {
                JFrame frame = new JFrame("Editing: " + file.getName());
                frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

                MdTexEditor mdEditor = new MdTexEditor();
                mdEditor.setCurrentFile(file); 
                
                String content = Files.readString(file.toPath());
                
                UndoRedoUtility.isAutomatedUpdate = true;
                mdEditor.editor.setText(content);
                UndoRedoUtility.isAutomatedUpdate = false;
                
                mdEditor.isModified = false; 

                frame.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosing(java.awt.event.WindowEvent e) {
                        if (mdEditor.checkUnsavedChanges()) {
                            frame.dispose();
                        }
                    }
                });

                frame.add(mdEditor);
                frame.setSize(1300, 650);
                frame.setLocationRelativeTo(null);

                SwingUtilities.invokeLater(() -> {
                    frame.setVisible(true);
                    frame.toFront();
                });
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(null, "Error opening Markdown editor: " + ex.getMessage());
            }
        } 
        // Route All Other Code/Text Snippets Into The Shared Persistent Tabs
        else {
            if (editorFrame != null) {
                editorFrame.openFileInternally(file);
            } else {
                JOptionPane.showMessageDialog(null, "Editor reference missing active window context.");
            }
        }
    }

}

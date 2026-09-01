package com.sphere.components.fileexplorerincludes;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.io.*;
import java.nio.file.*;
import java.awt.*;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Clipboard;

import com.sphere.components.FileExplorer;
import com.sphere.components.QuickCodeEditor;
import com.sphere.components.MdTexEditor;
import com.sphere.components.UndoRedoUtility;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;
import com.sphere.utils.IconManager;
import com.sphere.ui.QuickCodeEditorFrame;
import com.sphere.core.python.jupyterlab.JupylabXeditor;

public class ContextMenuBuilder {

    /* FULL mode (right-click contextual menu execution) */
    public static JPopupMenu createMenu(File selectedFile, String mode, FileExplorer explorer, QuickCodeEditorFrame editorFrame) {
        return createMenu(selectedFile, mode, null, explorer, editorFrame);
    }

    /* Main context construction entry point */
    public static JPopupMenu createMenu(File sourceFile, String mode, File targetFolder, FileExplorer explorer, QuickCodeEditorFrame editorFrame) {
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

        // Thin 1px separator line matching UI style guidelines
        class ThinSeparator extends JSeparator {
            @Override
            public void paint(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(255, 255, 255, 30));
                g2.drawLine(6, 0, getWidth() - 6, 0);
                g2.dispose();
            }
        }

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
        StringSelection sel = new StringSelection(text);
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(sel, null);
    }


    /*--------------------------------------------------------------
     * Helper to build fully custom flat menu items with manually
     * drawn background selections and aligned fonts.
     *--------------------------------------------------------------*/
    private static JMenuItem createModernMenuItem(String text) {
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
    private static void createNewFile(File folder, FileExplorer explorer) {
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
    private static void createNewFolder(File folder, FileExplorer explorer) {
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
    private static void duplicateFile(File file, FileExplorer explorer) {
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
    private static void handleRename(File file, FileExplorer explorer) {
        // FIXED: Re-added the default text input value containing the file's current name
        String newName = JOptionPane.showInputDialog("Enter new name:", file.getName());
        if (newName == null || newName.trim().isEmpty()) return;

        File dest = new File(file.getParentFile(), newName);

        if (dest.exists()) {
            JOptionPane.showMessageDialog(null, "A file with that name already exists.");
            return;
        }

        if (file.renameTo(dest)) {
            explorer.refreshNode(file.getParentFile());
        } else {
            JOptionPane.showMessageDialog(null, "Failed to rename file.");
        }
    }

    private static void handleDelete(File file, FileExplorer explorer) {
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

    private static boolean deleteDirectoryRecursive(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectoryRecursive(f);
                else f.delete();
            }
        }
        return dir.delete();
    }

    private static void performCopyMove(File source, File targetDir, boolean isMove, FileExplorer explorer) {
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

            explorer.refreshNode(targetDir);
            if (isMove) explorer.refreshNode(source.getParentFile());

        } catch (IOException ex) {
            JOptionPane.showMessageDialog(null, "Error: " + ex.getMessage());
        }
    }

    private static void copyDirectoryRecursive(Path src, Path dest) throws IOException {
        Files.walk(src).forEach(path -> {
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

    /* -------------------------------------------------------------
     * WORKSPACE EDITOR INITIALIZATION
     * -------------------------------------------------------------*/
    private static void handleEdit(File file, QuickCodeEditorFrame editorFrame) {
        if (file == null || !file.isFile()) {
            JOptionPane.showMessageDialog(null, "Cannot edit a directory.");
            return;
        }

        String fileNameLower = file.getName().toLowerCase();

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

package com.sphere.components;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.*;
import java.util.regex.Pattern;

import static java.nio.file.StandardWatchEventKinds.*;

import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.utils.AppLogger;
import com.sphere.utils.IconManager;
import com.sphere.ui.QuickCodeEditorFrame;

public class SnippetsPanel extends JPanel {

    private JList<String> snippetList;
    private DefaultListModel<String> listModel;
    private JLabel statusLabel;
    private JTextField filterField;
    private JTextField targetField;
    private QuickCodeEditorFrame editorFrame;

    private final ThemePalette palette = ThemeManager.getCurrentPalette();
    private final Path rootPath = Paths.get("snippets").toAbsolutePath().normalize();

    private WatchService watchService;
    private Thread watchThread;
    private long lastRefreshTime = 0L;
    private final long REFRESH_DEBOUNCE_MS = 150L;

    private SnippetNode rootNode;
    private SnippetNode currentNode;

    /**
     * Represents a virtual file system node for snippets hierarchy.
     */
    private static class SnippetNode {
        String name;
        boolean isDirectory;
        SnippetNode parent;
        List<SnippetNode> children = new ArrayList<>();
        boolean loaded = false;

        SnippetNode(String name, boolean isDirectory, SnippetNode parent) {
            this.name = name;
            this.isDirectory = isDirectory;
            this.parent = parent;
        }

        String getFullPath() {
            if (parent == null) return "";
            String p = parent.getFullPath();
            return p.isEmpty() ? name : p + name;
        }
    }

    /**
     * Custom renderer to display appropriate icons and styles for files and folders.
     */
    private static class SnippetCellRenderer extends DefaultListCellRenderer {
        static int hoverIndex = -1;

        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {

            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            ThemePalette palette = ThemeManager.getCurrentPalette();

            label.setBorder(new EmptyBorder(4, 10, 4, 10));
            label.setOpaque(true);

            String text = String.valueOf(value);
            label.setText(text);

            // Check if it's the parent directory link
            if ("../".equals(text)) {
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
                row.setOpaque(true);
                row.setBackground(palette.getBackgroundSurface());
                row.setBorder(new EmptyBorder(4, 10, 4, 10));

                // Up arrow icon
                JLabel upIcon = new JLabel(IconManager.getIcon("upsnippets.png"));
                upIcon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                upIcon.setToolTipText("Go to parent folder");

                // Click action on the icon
                upIcon.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        SnippetsPanel panelRef = (SnippetsPanel) SwingUtilities.getAncestorOfClass(
                                SnippetsPanel.class, list);
                        if (panelRef != null &&
                            panelRef.currentNode != null &&
                            panelRef.currentNode.parent != null) {

                            panelRef.currentNode = panelRef.currentNode.parent;
                            panelRef.filterList();
                        }
                    }
                });

                // "../" text label
                JLabel txt = new JLabel("../");
                txt.setFont(FontLoader.getGlobalFont(Font.BOLD, 14));
                txt.setForeground(palette.getTextSecondary());

                row.add(upIcon);
                row.add(txt);
                return row;
            }else if (text.endsWith("/")) {
                label.setIcon(IconManager.getIcon("folder.png"));
                label.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
            } else {
                label.setIcon(IconManager.getIconForFile(text));
                label.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
            }

            if ("../".equals(text)) {
                label.setBackground(palette.getBackgroundSurface());
                label.setForeground(palette.getTextSecondary());
                return label;
            }

            if (isSelected) {
                label.setBackground(palette.getButtonPressed());
                label.setForeground(palette.getTextWhite());
            } else if (index == hoverIndex) {
                label.setBackground(palette.getPopupHoverFallback());
                label.setForeground(palette.getTextWhite());
            } else {
                label.setBackground(palette.getBackgroundSurface());
                label.setForeground(palette.getTextLightGray());
            }

            return label;
        }
    }

    // Append inside the SnippetsPanel class hierarchy

    public SnippetsPanel(JTextField targetField, QuickCodeEditorFrame editorFrame) {
        this.targetField = targetField;
        this.editorFrame = editorFrame;

        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setOpaque(true);
        setBackground(palette.getBackgroundSurface());

        initFilterPanel();
        initSnippetList();
        initStatusBar();
        initContextMenu();

        refreshSnippets();
        startSnippetsWatcher();
    }

    private void initFilterPanel() {
        JPanel topPanel = new JPanel(new BorderLayout(8, 0));
        topPanel.setOpaque(false);

        filterField = new JTextField();
        // Ensure the text field background is properly filled to avoid rendering artifacts
        filterField.setOpaque(true); 
        filterField.setBackground(palette.getBackgroundSurface().darker());
        filterField.setForeground(palette.getTextPrimary());
        filterField.setCaretColor(palette.getAccent());
        filterField.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

        // Fix for the invisible white-on-white text selection
        filterField.setSelectionColor(palette.getButtonPressed());
        filterField.setSelectedTextColor(palette.getTextWhite());

        JLabel filterLabel = new JLabel("Filter:");
        filterLabel.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
        filterLabel.setForeground(palette.getTextSecondary());
        
        topPanel.add(filterLabel, BorderLayout.WEST);
        topPanel.add(filterField, BorderLayout.CENTER);
        add(topPanel, BorderLayout.NORTH);

        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filterList(); }
            @Override public void removeUpdate(DocumentEvent e) { filterList(); }
            @Override public void changedUpdate(DocumentEvent e) { filterList(); }
        });
    }

    private void initSnippetList() {
        listModel = new DefaultListModel<>();
        snippetList = new JList<>(listModel);

        snippetList.setCellRenderer(new SnippetCellRenderer());
        snippetList.setOpaque(false);
        snippetList.setBackground(new Color(0, 0, 0, 0));
        snippetList.setFocusable(true);
        snippetList.setSelectionBackground(palette.getButtonHover());
        snippetList.setSelectionForeground(palette.getTextWhite());
        snippetList.putClientProperty("List.isFileList", Boolean.FALSE); 

        installHoverListener();
        installRightClickListener();
        installDoubleClickListener();

        JScrollPane scroll = new JScrollPane(snippetList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getViewport().setBackground(new Color(0,0,0,0));
        add(scroll, BorderLayout.CENTER);

        // Keyboard accessibility bindings
        snippetList.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override
            public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER) {
                    String selected = snippetList.getSelectedValue();
                    if (selected == null) return;
                    
                    if (selected.equals("../")) {
                        if (currentNode.parent != null) {
                            currentNode = currentNode.parent;
                            filterList();
                        }
                    } else {
                        SnippetNode target = findChildByName(selected);
                        if (target != null) {
                            if (target.isDirectory) {
                                currentNode = target;
                                filterList();
                            } else {
                                insertSnippetReference("[@ " + target.getFullPath() + "]");
                            }
                        }
                    }
                } else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_BACK_SPACE) {
                    if (currentNode != null && currentNode.parent != null) {
                        currentNode = currentNode.parent;
                        filterList();
                    }
                } else if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) {
                    filterField.setText("");
                }
            }
        });
    }

    private void initStatusBar() {
        statusLabel = new JLabel("0 snippets");
        statusLabel.setFont(FontLoader.getGlobalFont(Font.PLAIN, 11));
        statusLabel.setForeground(palette.getTextSecondary());
        statusLabel.setOpaque(false);
        add(statusLabel, BorderLayout.SOUTH);
    }

    private JMenuItem createModernMenuItem(String text) {
        JMenuItem item = new JMenuItem(text) {
            @Override
            protected void paintComponent(Graphics g) {
                ButtonModel model = getModel();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                
                if (model.isArmed() || model.isSelected()) {
                    g2.setColor(palette.getButtonPressed());
                    g2.fillRoundRect(4, 2, getWidth() - 8, getHeight() - 4, 6, 6);
                    g2.setColor(palette.getTextWhite());
                } else {
                    g2.setColor(palette.getTextPrimary());
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

    private void initContextMenu() {
        JPopupMenu popup = new JPopupMenu() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (palette != null) {
                    g2.setColor(palette.getBackgroundSurface().brighter());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    g2.setColor(palette.getPopupBorder());
                    g2.setStroke(new BasicStroke(1.0f));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                }
                g2.dispose();
            }
        };
        popup.setOpaque(false);
        popup.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JMenuItem editItem         = createModernMenuItem("Edit");
        JMenuItem renameItem       = createModernMenuItem("Rename");
        JMenuItem deleteItem       = createModernMenuItem("Delete");
        JMenuItem newFileItem      = createModernMenuItem("New Snippet");
        JMenuItem newFolderItem    = createModernMenuItem("New Folder");
        JMenuItem renameFolderItem = createModernMenuItem("Rename Folder");
        JMenuItem deleteFolderItem = createModernMenuItem("Delete Folder");
        JMenuItem importItem       = createModernMenuItem("Import");
        JMenuItem openInFolderItem = createModernMenuItem("Open in Explorer");

        JSeparator sep = new JSeparator() {
            @Override
            public void paint(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(palette.getPopupBorder());
                g2.drawLine(6, 0, getWidth() - 6, 0);
                g2.dispose();
            }
        };

        popup.add(editItem); popup.add(renameItem); popup.add(deleteItem); popup.add(sep);
        popup.add(newFileItem); popup.add(newFolderItem); popup.add(renameFolderItem); popup.add(deleteFolderItem);
        popup.add(sep); popup.add(importItem); popup.add(openInFolderItem);

        snippetList.setComponentPopupMenu(popup);

        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(() -> {
                    String selected = snippetList.getSelectedValue();
                    boolean isDir = selected != null && selected.endsWith("/");

                    if (selected == null || selected.equals("../")) {
                        editItem.setVisible(false); renameItem.setVisible(false); deleteItem.setVisible(false);
                        renameFolderItem.setVisible(false); deleteFolderItem.setVisible(false);
                        importItem.setVisible(!"../".equals(selected));
                    } else {
                        editItem.setVisible(!isDir); renameItem.setVisible(!isDir); deleteItem.setVisible(!isDir);
                        renameFolderItem.setVisible(isDir); deleteFolderItem.setVisible(isDir);
                        importItem.setVisible(true);
                    }

                    Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                    int maxHeight = (int)(screen.height * 0.6);
                    if (popup.getPreferredSize().height > maxHeight) {
                        popup.setPreferredSize(new Dimension(popup.getPreferredSize().width, maxHeight));
                    }
                    snippetList.repaint();
                    snippetList.revalidate();
                });
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {}
            @Override public void popupMenuCanceled(PopupMenuEvent e) {}
        });

        newFileItem.addActionListener(e -> createNewSnippet());
        newFolderItem.addActionListener(e -> createFolderInSelected());
        renameFolderItem.addActionListener(e -> renameSelectedFolder());
        deleteFolderItem.addActionListener(e -> deleteSelectedFolder());
        importItem.addActionListener(e -> importResources());
        openInFolderItem.addActionListener(e -> openInExplorer());
        editItem.addActionListener(e -> editSelectedSnippetFromList());
        renameItem.addActionListener(e -> renameSelectedSnippet());
        deleteItem.addActionListener(e -> deleteSelectedSnippet());
    }

    // Append inside the SnippetsPanel class hierarchy

    private void installHoverListener() {
        snippetList.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int index = snippetList.locationToIndex(e.getPoint());
                int newHover = -1;
                if (index != -1) {
                    Rectangle r = snippetList.getCellBounds(index, index);
                    if (r != null && r.contains(e.getPoint())) newHover = index;
                }
                if (SnippetCellRenderer.hoverIndex != newHover) {
                    SnippetCellRenderer.hoverIndex = newHover;
                    snippetList.repaint();
                }
            }
        });
        snippetList.addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent e) {
                SnippetCellRenderer.hoverIndex = -1;
                snippetList.repaint();
            }
        });
    }

    private void installRightClickListener() {
        snippetList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isRightMouseButton(e)) return;
                int index = snippetList.locationToIndex(e.getPoint());
                boolean clicked = false;

                if (index != -1) {
                    Rectangle r = snippetList.getCellBounds(index, index);
                    if (r != null && r.contains(e.getPoint())) {
                        snippetList.setSelectedIndex(index);
                        SnippetCellRenderer.hoverIndex = index;
                        clicked = true;
                    }
                }
                if (!clicked) {
                    snippetList.clearSelection();
                    SnippetCellRenderer.hoverIndex = -1;
                }
                snippetList.repaint();
                JPopupMenu popup = snippetList.getComponentPopupMenu();
                if (popup != null) popup.show(snippetList, e.getX(), e.getY());
            }
        });
    }

    private void installDoubleClickListener() {
        snippetList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 2) return;
                String selected = snippetList.getSelectedValue();
                if (selected == null) return;

                if (selected.equals("../")) {
                    if (currentNode.parent != null) {
                        currentNode = currentNode.parent;
                        filterList();
                    }
                    return;
                }

                SnippetNode target = findChildByName(selected);
                if (target == null) return;

                if (target.isDirectory) {
                    currentNode = target;
                    filterList();
                } else {
                    insertSnippetReference("[@ " + target.getFullPath() + "]");
                }
            }
        });
    }

    private void editSelectedSnippetFromList() {
        String selected = snippetList.getSelectedValue();
        if (selected == null || selected.endsWith("/")) return;
        SnippetNode target = findChildByName(selected);
        if (target == null) return;
        
        try {
            Path filePath = safeResolve(target.getFullPath());
            editSelectedSnippet(filePath.toFile());
        } catch (SecurityException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Security Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void editSelectedSnippet(File file) {
        if (!file.isFile()) {
            JOptionPane.showMessageDialog(this, "Invalid file location.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (editorFrame != null) {
            editorFrame.openFileInternally(file);
        } else {
            JOptionPane.showMessageDialog(this, "Workspace window layout is unavailable.", "Context Missing Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteSelectedSnippet() {
        String selected = snippetList.getSelectedValue();
        if (selected == null || selected.endsWith("/")) return;
        SnippetNode target = findChildByName(selected);
        if (target == null) return;

        try {
            Path fileToDelete = safeResolve(target.getFullPath());
            if (Files.exists(fileToDelete) && Files.deleteIfExists(fileToDelete)) {
                currentNode.loaded = false;
                refreshSnippets();
            } else {
                JOptionPane.showMessageDialog(this, "Failed to delete the selected file asset.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error: " + ex.getMessage(), "IO Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void renameSelectedSnippet() {
        String selected = snippetList.getSelectedValue();
        if (selected == null || selected.equals("../") || selected.endsWith("/")) return;
        SnippetNode target = findChildByName(selected);
        if (target == null) return;

        try {
            Path oldPath = safeResolve(target.getFullPath());
            if (!Files.isRegularFile(oldPath)) {
                JOptionPane.showMessageDialog(this, "Target file asset not found on disk.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Rename Snippet", true);
            dialog.setLayout(new BorderLayout(10, 10));
            JTextField nameField = new JTextField(selected, 20);
            SwingUtilities.invokeLater(nameField::requestFocusInWindow);

            JPanel center = new JPanel(); center.add(new JLabel("New name:")); center.add(nameField);
            JPanel bottom = new JPanel(); JButton ok = new JButton("OK"); JButton cancel = new JButton("Cancel");
            bottom.add(ok); bottom.add(cancel);
            dialog.add(center, BorderLayout.CENTER); dialog.add(bottom, BorderLayout.SOUTH);

            Runnable renameAction = () -> {
                String newName = nameField.getText().trim();
                if (newName.isEmpty()) return;
                try {
                    Path newPath = safeResolve(currentNode.getFullPath() + newName);
                    if (Files.exists(newPath)) {
                        JOptionPane.showMessageDialog(dialog, "A file structure with that name already exists.", "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    Files.move(oldPath, newPath);
                    dialog.dispose();
                    currentNode.loaded = false;
                    refreshSnippets();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "Failed to rename file asset: " + ex.getMessage(), "Operational Failure", JOptionPane.ERROR_MESSAGE);
                }
            };

            ok.addActionListener(e -> renameAction.run());
            nameField.addActionListener(e -> renameAction.run());
            cancel.addActionListener(e -> dialog.dispose());
            dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
        } catch (SecurityException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Security Violation", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void createNewSnippet() {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Create New Snippet", true);
        dialog.setLayout(new BorderLayout(10, 10));
        JPanel inputPanel = new JPanel(new FlowLayout());
        JTextField nameField = new JTextField(20);
        inputPanel.add(new JLabel("Filename (e.g., test.py):")); inputPanel.add(nameField);

        JPanel buttonPanel = new JPanel(); JButton btnCreate = new JButton("Create"); JButton btnCancel = new JButton("Cancel");
        buttonPanel.add(btnCreate); buttonPanel.add(btnCancel);
        dialog.add(inputPanel, BorderLayout.CENTER); dialog.add(buttonPanel, BorderLayout.SOUTH);

        Runnable createAction = () -> {
            String fileName = nameField.getText().trim();
            if (fileName.isEmpty()) return;
            try {
                Path newPath = safeResolve(currentNode.getFullPath() + fileName);
                if (!Files.exists(newPath)) {
                    Files.createFile(newPath);
                    dialog.dispose();
                    currentNode.loaded = false;
                    refreshSnippets();
                    editSelectedSnippet(newPath.toFile());
                } else {
                    JOptionPane.showMessageDialog(dialog, "Target file layout already exists.", "Conflict Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Error: " + ex.getMessage(), "IO Error", JOptionPane.ERROR_MESSAGE);
            }
        };

        btnCreate.addActionListener(e -> createAction.run());
        nameField.addActionListener(e -> createAction.run());
        btnCancel.addActionListener(e -> dialog.dispose());
        dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    // Append inside the SnippetsPanel class hierarchy to complete the implementation

    private void createFolderInSelected() {
        String selected = snippetList.getSelectedValue();
        SnippetNode baseNode = (selected != null && selected.endsWith("/")) ? findChildByName(selected) : currentNode;
        if (baseNode == null) baseNode = currentNode;

        final SnippetNode finalBase = baseNode;
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Create Folder", true);
        dialog.setLayout(new BorderLayout(10, 10));
        JTextField nameField = new JTextField(20);
        JPanel center = new JPanel(); center.add(new JLabel("Folder name:")); center.add(nameField);
        JPanel bottom = new JPanel(); JButton ok = new JButton("Create"); JButton cancel = new JButton("Cancel");
        bottom.add(ok); bottom.add(cancel);
        dialog.add(center, BorderLayout.CENTER); dialog.add(bottom, BorderLayout.SOUTH);

        Runnable createAction = () -> {
            String folderName = nameField.getText().trim();
            if (folderName.isEmpty()) return;
            try {
                Path newFolderPath = safeResolve(finalBase.getFullPath() + folderName);
                if (Files.exists(newFolderPath)) {
                    JOptionPane.showMessageDialog(dialog, "Folder layout already exists.", "Conflict Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                Files.createDirectory(newFolderPath);
                dialog.dispose();
                finalBase.loaded = false;
                refreshSnippets();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Failed to create directory node: " + ex.getMessage(), "IO Operational Failure", JOptionPane.ERROR_MESSAGE);
            }
        };

        ok.addActionListener(e -> createAction.run());
        nameField.addActionListener(e -> createAction.run());
        cancel.addActionListener(e -> dialog.dispose());
        dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
    }

    private void renameSelectedFolder() {
        String selected = snippetList.getSelectedValue();
        if (selected == null || !selected.endsWith("/")) return;
        SnippetNode target = findChildByName(selected);
        if (target == null) return;

        try {
            Path oldFolderPath = safeResolve(target.getFullPath());
            if (!Files.isDirectory(oldFolderPath)) {
                JOptionPane.showMessageDialog(this, "Target directory node not found.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "Rename Folder", true);
            dialog.setLayout(new BorderLayout(10, 10));
            JTextField nameField = new JTextField(selected.substring(0, selected.length() - 1), 20);
            JPanel center = new JPanel(); center.add(new JLabel("New folder name:")); center.add(nameField);
            JPanel bottom = new JPanel(); JButton ok = new JButton("OK"); JButton cancel = new JButton("Cancel");
            bottom.add(ok); bottom.add(cancel);
            dialog.add(center, BorderLayout.CENTER); dialog.add(bottom, BorderLayout.SOUTH);

            Runnable renameAction = () -> {
                String newName = nameField.getText().trim();
                if (newName.isEmpty()) return;
                try {
                    Path newFolderPath = safeResolve(currentNode.getFullPath() + newName);
                    if (Files.exists(newFolderPath)) {
                        JOptionPane.showMessageDialog(dialog, "A directory wrapper with that name already exists.", "Error", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                    Files.move(oldFolderPath, newFolderPath);
                    dialog.dispose();
                    currentNode.loaded = false;
                    refreshSnippets();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "Failed to rename folder element: " + ex.getMessage(), "Operational Failure", JOptionPane.ERROR_MESSAGE);
                }
            };

            ok.addActionListener(e -> renameAction.run());
            nameField.addActionListener(e -> renameAction.run());
            cancel.addActionListener(e -> dialog.dispose());
            dialog.pack(); dialog.setLocationRelativeTo(this); dialog.setVisible(true);
        } catch (SecurityException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Security Violation", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteSelectedFolder() {
        String selected = snippetList.getSelectedValue();
        if (selected == null || !selected.endsWith("/")) return;
        SnippetNode targetFolderNode = findChildByName(selected);
        if (targetFolderNode == null) return;

        try {
            Path diskPath = safeResolve(targetFolderNode.getFullPath());
            if (!Files.exists(diskPath)) {
                JOptionPane.showMessageDialog(this, "Folder layout layout not found on disk storage.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            Object[] options = {"Delete", "Cancel"};
            int confirm = JOptionPane.showOptionDialog(
                    this, "Are you sure you want to delete this folder and ALL its contents?\nThis action cannot be undone.",
                    "Warning: Delete Folder", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]
            );
            if (confirm == JOptionPane.YES_OPTION) {
                Files.walkFileTree(diskPath, new SimpleFileVisitor<Path>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        if (exc != null) throw exc;
                        Files.delete(dir); return FileVisitResult.CONTINUE;
                    }
                });
                currentNode.loaded = false;
                refreshSnippets();
            }
        } catch (Exception ex) {
            AppLogger.error("Failed to delete directory recursively: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, "Error deleting folder layout contents: " + ex.getMessage(), "IO Operational Failure", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importResources() {
        String selected = snippetList.getSelectedValue();
        // Determine destination folder: either the selected folder or the active current folder context
        SnippetNode targetFolderNode = (selected != null && selected.endsWith("/")) ? findChildByName(selected) : currentNode;
        if (targetFolderNode == null) targetFolderNode = currentNode;

        try {
            Path destDir = safeResolve(targetFolderNode.getFullPath());
            
            // Instantiate your custom modern file chooser component
            Window parentWindow = SwingUtilities.getWindowAncestor(this);
            com.sphere.components.fileexplorerincludes.FlatFileChooser chooser = 
                new com.sphere.components.fileexplorerincludes.FlatFileChooser(parentWindow, new File("."));
            
            // Display the blocking modal context window
            int result = chooser.showDialog();
            if (result == com.sphere.components.fileexplorerincludes.FlatFileChooser.APPROVE_OPTION) {
                // Safely handle single or multiple selected file/folder entities
                java.util.List<File> selectedFiles = chooser.getSelectedFiles();
                
                if (selectedFiles != null && !selectedFiles.isEmpty()) {
                    for (File file : selectedFiles) {
                        importRecursive(file, destDir);
                    }
                    // Invalidate node loaded status state to trigger VFS UI delta re-indexing
                    targetFolderNode.loaded = false;
                    refreshSnippets();
                }
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Import operation failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importRecursive(File source, Path destDir) throws IOException {
        Path targetPath = destDir.resolve(source.getName()).normalize();
        if (!targetPath.startsWith(rootPath)) {
            throw new SecurityException("Unauthorized import target path detected.");
        }

        if (source.isDirectory()) {
            Files.createDirectories(targetPath);
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    importRecursive(child, targetPath);
                }
            }
        } else {
            Files.copy(source.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void openInExplorer() {
        String selected = snippetList.getSelectedValue();
        try {
            Path targetPath = (selected == null || selected.equals("../")) ? safeResolve(currentNode.getFullPath()) : safeResolve(currentNode.getFullPath() + selected);
            if (Files.isRegularFile(targetPath)) {
                targetPath = targetPath.getParent();
            }
            if (targetPath != null) Desktop.getDesktop().open(targetPath.toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Unable to access requested native file link layer: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void filterList() {
        SnippetCellRenderer.hoverIndex = -1;
        listModel.clear();
        if (currentNode == null) return;

        ensureChildrenLoaded(currentNode);
        String filterInput = filterField.getText().trim();
        if (currentNode.parent != null) listModel.addElement("../");

        List<String> display = new ArrayList<>();
        for (SnippetNode child : currentNode.children) {
            if (fuzzyMatch(child.name, filterInput)) {
                display.add(child.name);
            }
        }

        display.sort((a, b) -> {
            boolean isADir = a.endsWith("/"); boolean isBDir = b.endsWith("/");
            if (isADir && !isBDir) return -1;
            if (!isADir && isBDir) return 1;
            return a.compareToIgnoreCase(b);
        });

        int fileCount = 0;
        for (String s : display) {
            listModel.addElement(s);
            if (!s.endsWith("/")) fileCount++;
        }
        statusLabel.setText(fileCount + " snippets displayed");
        snippetList.repaint();
    }

    private boolean fuzzyMatch(String text, String pattern) {
        if (pattern.isEmpty()) return true;
        try {
            String regex = "^" + pattern.replace(".", "\\.")
                                       .replace("?", ".")
                                       .replace("*", ".*") + "$";
            if (!pattern.contains("*") && !pattern.contains("?")) {
                return text.toLowerCase().contains(pattern.toLowerCase());
            }
            return Pattern.compile("(?i)" + regex).matcher(text).matches();
        } catch (Exception e) {
            return text.toLowerCase().contains(pattern.toLowerCase());
        }
    }

    private SnippetNode findChildByName(String name) {
        if (name == null || currentNode == null) return null;
        ensureChildrenLoaded(currentNode);
        return currentNode.children.stream().filter(n -> n.name.equals(name)).findFirst().orElse(null);
    }

    private Path safeResolve(String childPathStr) {
        Path resolved = rootPath.resolve(childPathStr).normalize();
        if (!resolved.startsWith(rootPath)) {
            throw new SecurityException("Unauthorized folder navigation attempt outside VFS root location.");
        }
        return resolved;
    }

    private void ensureChildrenLoaded(SnippetNode node) {
        if (node == null || !node.isDirectory) return;
        if (!node.loaded) {
            try {
                Path dirPath = safeResolve(node.getFullPath());
                if (!Files.exists(dirPath)) Files.createDirectories(dirPath);
                
                File[] diskFiles = dirPath.toFile().listFiles();
                if (diskFiles == null) {
                    node.children.clear(); node.loaded = true; return;
                }

                java.util.Map<String, SnippetNode> existing = new java.util.HashMap<>();
                for (SnippetNode child : node.children) existing.put(child.name, child);

                List<SnippetNode> updated = new ArrayList<>();
                for (File f : diskFiles) {
                    String name = f.getName() + (f.isDirectory() ? "/" : "");
                    if (existing.containsKey(name)) {
                        updated.add(existing.get(name));
                    } else {
                        updated.add(new SnippetNode(name, f.isDirectory(), node));
                    }
                }
                node.children = updated; node.loaded = true;
            } catch (Exception ex) {
                AppLogger.error("VFS Differential Sync error: " + ex.getMessage());
            }
        }
    }

    private void insertSnippetReference(String reference) {
        if (targetField != null) {
            int caretPosition = targetField.getCaretPosition();
            String currentText = targetField.getText();
            if (currentText == null) currentText = "";
            
            String before = currentText.substring(0, caretPosition);
            String after = currentText.substring(caretPosition);
            
            targetField.setText(before + reference + after);
            targetField.setCaretPosition(caretPosition + reference.length());
            targetField.requestFocusInWindow();
        }
    }

    public void refreshSnippets() {
        SnippetCellRenderer.hoverIndex = -1;
        if (rootNode == null) {
            rootNode = new SnippetNode("", true, null);
            currentNode = rootNode;
        }
        ensureChildrenLoaded(currentNode);
        filterList();
        snippetList.revalidate(); snippetList.repaint();
    }

    private void startSnippetsWatcher() {
        try {
            if (!Files.exists(rootPath)) Files.createDirectories(rootPath);
            watchService = FileSystems.getDefault().newWatchService();
            rootPath.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            
            watchThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        WatchKey key = watchService.take();
                        key.pollEvents(); key.reset();

                        long now = System.currentTimeMillis();
                        if (now - lastRefreshTime < REFRESH_DEBOUNCE_MS) continue;
                        lastRefreshTime = now;

                        SwingUtilities.invokeLater(() -> {
                            SnippetCellRenderer.hoverIndex = -1;
                            if (currentNode != null) currentNode.loaded = false;
                            refreshSnippets();
                        });
                    } catch (Exception ignored) {}
                }
            });
            watchThread.setDaemon(true); watchThread.start();
        } catch (Exception ex) {
            AppLogger.error("Failed to start snippets system layer watcher: " + ex.getMessage());
        }
    }
}

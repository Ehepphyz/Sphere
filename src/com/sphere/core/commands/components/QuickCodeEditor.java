package com.sphere.components;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.undo.UndoManager;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.sphere.components.editor.CodeTab;
import com.sphere.components.FindReplaceDialog;
import com.sphere.components.fileexplorerincludes.FlatFileChooser;
import com.sphere.components.fileexplorerincludes.FlatFileFilter;
import com.sphere.components.TextLineNumber;
import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.components.editor.EditorTheme;
import com.sphere.utils.AppLogger;
import com.sphere.utils.IconManager;

/**
 * Main editor component providing a multi-tab interface for code editing and live previews.
 * Uses CodeTab for each tab, with per-tab text mode (Plain Text / Markdown / LaTeX).
 * Public API and menu structure preserved for compatibility.
 */
public class QuickCodeEditor extends JPanel {

    /* Global editor mode enum (used by menu, applied to active tab only) */
    public enum EditorMode { PLAIN_TEXT, MARKDOWN, LATEX }
    private EditorMode currentMode = EditorMode.PLAIN_TEXT;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();

    // The former "legacy" editor, preview pane and undo manager lived here and
    // were built for every instance without ever being added to the layout.
    // Editing state belongs to CodeTab, one per tab.

    // Transformed to follow strict Open-only isolation scope architecture
    private FlatFileChooser openFileChooser;

    /* UI optional items */
    private JLabel statusBar;
    private JCheckBoxMenuItem lineNumbersItem;
    private JCheckBoxMenuItem alwaysOnTopItem;
    private JCheckBoxMenuItem syncTextItem;

    /* Multi-tab support */
    private final JTabbedPane tabbedPane;
    private File currentFile;

    /* Open Recent support (in-memory list) */
    private final List<File> recentFiles = new ArrayList<>();
    private JMenu recentMenu;

    public QuickCodeEditor() {
        
        setLayout(new BorderLayout());

        setBackground(EditorTheme.background());

        /* Main UI: tabbed pane with CodeTab instances */
        tabbedPane = new JTabbedPane();
        styleTabbedPaneVSCode(tabbedPane);

        tabbedPane.addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                updateStatusBar();
                for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                    Component header = tabbedPane.getTabComponentAt(i);
                    if (header instanceof TabHeader th) {
                        th.repaint();
                    }
                }
            }
        });

        add(tabbedPane, BorderLayout.CENTER);

        /* Create initial empty tab */
        addNewTab(null);

        /* Status bar setup */
        statusBar = new JLabel(" Line: 1 | Column: 1");
        statusBar.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        statusBar.setBackground(new Color(57, 58, 59)); // VS Code styling status blue background tint
        statusBar.setForeground(Color.WHITE);
        statusBar.setOpaque(true);
        add(statusBar, BorderLayout.SOUTH);

        /* Window close handling and custom application branding icon initialization */
        SwingUtilities.invokeLater(() -> {
            Window parentWindow = SwingUtilities.getWindowAncestor(QuickCodeEditor.this);
            if (parentWindow instanceof JFrame frame) {
                
                // 1. Fetch the application branding asset using the unified caching engine
                Icon logoIcon = IconManager.getIcon("cta_logo.png");
                
                // 2. Extract the underlying image instance to bypass standard Swing wrapper types
                if (logoIcon instanceof ImageIcon) {
                    Image appIconImage = ((ImageIcon) logoIcon).getImage();
                    frame.setIconImage(appIconImage);
                }
                
                frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        handleExit();
                    }
                });
            }
        });
    }

    /* Applies a Visual Studio Code-like style to the tabbed pane. */
    private void styleTabbedPaneVSCode(JTabbedPane tabs) {
        tabs.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {

            @Override
            protected void installDefaults() {
                super.installDefaults();
                tabAreaInsets = new Insets(5, 5, 0, 5);
                contentBorderInsets = new Insets(0, 0, 0, 0);
            }

            @Override
            protected void paintTabBackground(Graphics g, int tabPlacement,
                                              int tabIndex, int x, int y, int w, int h, boolean isSelected) {
                if (isSelected) {
                    g.setColor(palette.getTabsEditorActive()); // Active tab canvas space
                } else {
                    g.setColor(palette.getTabEditorHidden()); // Hidden inactive tracking
                }
                g.fillRect(x, y, w, h);
            }

            @Override
            protected void paintTabBorder(Graphics g, int tabPlacement,
                                          int tabIndex, int x, int y, int w, int h, boolean isSelected) {
                // Custom border borders are dynamically assigned by TabHeader paintComponent.
            }

            @Override
            protected void paintFocusIndicator(Graphics g, int tabPlacement,
                                               Rectangle[] rects, int tabIndex,
                                               Rectangle iconRect, Rectangle textRect, boolean isSelected) {
                // Eliminates standard ugly focus indicator rings to enforce flat look
            }
        });

        tabs.setBackground(palette.getBackgroundSurface());
        tabs.setForeground(palette.getTextPrimary());
        tabs.setBorder(BorderFactory.createEmptyBorder());
    }

    /**
     * Pushes findings onto the tab showing that file, so a compilation underlines
     * its errors in the source rather than only listing them in the console.
     */
    public void showDiagnostics(File file, java.util.List<com.sphere.components.editor.EditorDiagnostic> diagnostics) {
        if (file == null) {
            return;
        }
        CodeTab target = findTabForFile(file);
        if (target != null) {
            target.setDiagnostics(diagnostics);
        }
    }

    /** The tab holding that file, or null when it is not open. */
    public CodeTab findTabForFile(File file) {
        if (file == null) {
            return null;
        }
        String wanted = file.getAbsolutePath();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof CodeTab tab && tab.getFile() != null
                    && tab.getFile().getAbsolutePath().equals(wanted)) {
                return tab;
            }
        }
        return null;
    }

    /* Returns the active CodeTab, or null if none. */
    private CodeTab getActiveTab() {
        int index = tabbedPane.getSelectedIndex();
        if (index < 0) return null;
        Component c = tabbedPane.getComponentAt(index);
        return (c instanceof CodeTab) ? (CodeTab) c : null;
    }

    /* Returns the border accent highlights matching individual text formatting modes. */
    public Color getBorderColorForMode(CodeTab.EditorMode mode) {
        return switch (mode) {
            case MARKDOWN -> palette.getEdBorderMarkd();   // Dark Accent Soft Orange
            case LATEX -> palette.getEdBorderLatex();      // Forest Green
            case PLAIN_TEXT -> palette.getEdBorderPtext(); // Muted Golden Rod
            default -> palette.getEdBorderDefault();          // Neutral Medium Gray
        };
    }

    /* Adds a new tab, optionally bound to an existing file. */
    private void addNewTab(File file) {
        CodeTab tab = new CodeTab(file);
        String tabTitle = file != null ? file.getName() : "Untitled";
        tabbedPane.addTab(tabTitle, tab);

        int index = tabbedPane.indexOfComponent(tab);
        tabbedPane.setTabComponentAt(
                index,
                new TabHeader(tabbedPane, tab, tabTitle)
        );
        tabbedPane.setSelectedComponent(tab);

        // Update title when modified
        tab.getEditorArea().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateTabTitle(tab); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateTabTitle(tab); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateTabTitle(tab); }
        });

        // Update status bar when caret moves
        tab.getEditorArea().addCaretListener(e -> updateStatusBar());

        updateTabTitle(tab);
    }

    /* Updates the tab title based on file name and modified state. */
    private void updateTabTitle(CodeTab tab) {
        int index = tabbedPane.indexOfComponent(tab);
        if (index < 0) return;

        File f = tab.getFile();
        String base = (f != null) ? f.getName() : "Untitled";
        String title = tab.isModified() ? "*" + base : base;

        Component header = tabbedPane.getTabComponentAt(index);
        if (header instanceof TabHeader th) {
            th.setTitle(title);
        } else {
            tabbedPane.setTitleAt(index, title);
        }
    }

    /* Adds a file to the recent files list (in-memory). */
    private void addToRecentFiles(File file) {
        if (file == null) return;
        recentFiles.remove(file);
        recentFiles.add(0, file);
        while (recentFiles.size() > 10) {
            recentFiles.remove(recentFiles.size() - 1);
        }
        rebuildRecentMenu();
    }

    /* Rebuilds the "Open Recent" submenu. */
    private void rebuildRecentMenu() {
        if (recentMenu == null) return;
        recentMenu.removeAll();
        if (recentFiles.isEmpty()) {
            JMenuItem empty = new JMenuItem("(Empty)");
            empty.setEnabled(false);
            recentMenu.add(empty);
            return;
        }
        for (File f : recentFiles) {
            JMenuItem item = new JMenuItem(f.getAbsolutePath());
            item.addActionListener(e -> loadFile(f));
            recentMenu.add(item);
        }
    }

    private void newFile() {
        // Instantiates a completely virtual unsaved document context layout pointer
        // Passing null forces the tab to treat it as an unsaved buffer target
        addNewTab(null);
    }

    /* Updates the status bar with caret position of the active tab. */
    private void updateStatusBar() {
        CodeTab tab = getActiveTab();
        if (tab == null || statusBar == null) return;

        com.sphere.components.editor.CodeTextPane area = tab.getEditorArea();
        try {
            // A styled document has no getLineOfOffset, so the root element gives
            // the line and its start offset gives the column.
            int caretPos = area.getCaretPosition();
            javax.swing.text.Element root = area.getDocument().getDefaultRootElement();
            int line = root.getElementIndex(caretPos);
            int column = caretPos - root.getElement(line).getStartOffset();
            String language = tab.getLanguage().name;
            statusBar.setText(" Line: " + (line + 1) + " | Column: " + (column + 1)
                              + "   •   " + language);
        } catch (Exception ignored) {
            statusBar.setText(" Line: 1 | Column: 1");
        }
    }

    /* Legacy method kept for compatibility. No longer used as core logic. */
    private void updateEditorViewLayout() {
        // Preview tracking is natively decentralized within individual CodeTabs.
    }

    /* Legacy method kept for compatibility. No longer used as core logic. */
    private void handleDocumentUpdate() {
        // Live monitoring metrics pipeline is encapsulated per CodeTab instances.
    }

    /* Legacy method kept for compatibility. No longer used as core logic. */
    private void renderPreviewContent() {
        // Markup processing workflows are handled internally per CodeTab.
    }

    /* Sets the text mode for the active tab only (per-tab mode). */
    public void setEditorMode(EditorMode mode) {
        this.currentMode = mode;
        CodeTab tab = getActiveTab();
        if (tab == null) return;

        switch (mode) {
            case MARKDOWN -> tab.setEditorMode(CodeTab.EditorMode.MARKDOWN);
            case LATEX -> tab.setEditorMode(CodeTab.EditorMode.LATEX);
            default -> tab.setEditorMode(CodeTab.EditorMode.PLAIN_TEXT);
        }

        int index = tabbedPane.indexOfComponent(tab);
        Component header = tabbedPane.getTabComponentAt(index);
        if (header instanceof TabHeader th) {
            th.repaint();
        }
    }

    /* Handles application exit, prompting to save modified tabs. */
    private void handleExit() {
        Window parentWindow = SwingUtilities.getWindowAncestor(this);

        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof CodeTab tab && tab.isModified()) {
                tabbedPane.setSelectedIndex(i);
                int choice = JOptionPane.showConfirmDialog(
                        this,
                        "The file \"" + (tab.getFile() != null ? tab.getFile().getName() : "Untitled") +
                                "\" has been modified. Do you want to save before quitting?",
                        "Unsaved Changes",
                        JOptionPane.YES_NO_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );
                if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
                    return;
                }
                if (choice == JOptionPane.YES_OPTION) {
                    tab.saveFile();
                    if (tab.isModified()) {
                        return;
                    }
                }
            }
        }

        if (parentWindow != null) parentWindow.dispose();
    }

    /* Auto-indents the current line in the active tab. */

    /* Sets the code of the active tab. */
    public void setCode(String code) {
        CodeTab tab = getActiveTab();
        if (tab != null) {
            tab.getEditorArea().setText(code);
            tab.getEditorArea().setCaretPosition(0);
        }
    }

    /* Returns the code of the active tab. */
    public String getCode() {
        CodeTab tab = getActiveTab();
        return (tab != null) ? tab.getEditorArea().getText() : "";
    }

    /* Returns the editor area of the active tab (for compatibility). */
    public javax.swing.text.JTextComponent getEditorArea() {
        CodeTab tab = getActiveTab();
        return (tab != null) ? tab.getEditorArea() : null;
    }

    /**
     * Loads a file resource into the workspace view.
     * If the file is already open in an existing tab, that tab is selected and focused.
     * Otherwise, the content is loaded into an empty unmodified tab or appended as a new tab.
     * * @param file The target file to load into the tabbed layout.
     */
    public void loadFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        String targetPath = file.getAbsolutePath();

        // STEP 1: Dynamically look up the internal tab container context from the active tabs layout
        CodeTab activeTab = getActiveTab();
        JTabbedPane tabPane = null;
        if (activeTab != null && activeTab.getParent() instanceof JTabbedPane) {
            tabPane = (JTabbedPane) activeTab.getParent();
        }

        // STEP 2: Loop through all open tabs to check for an existing file path match
        if (tabPane != null) {
            int tabCount = tabPane.getTabCount();
            for (int i = 0; i < tabCount; i++) {
                Component comp = tabPane.getComponentAt(i);
                if (comp instanceof CodeTab) {
                    CodeTab tab = (CodeTab) comp;
                    if (tab.getFile() != null && tab.getFile().getAbsolutePath().equals(targetPath)) {
                        // 🎉 File is already open! Switch focus, update pointer, and abort duplication
                        tabPane.setSelectedIndex(i);
                        tab.getEditorArea().requestFocusInWindow();
                        this.currentFile = file;
                        return;
                    }
                }
            }
        }

        // STEP 3: File is not open yet. Proceed with standard placement logic
        this.currentFile = file;
        CodeTab tab = activeTab;

        if (tab != null && !tab.isModified() && tab.getFile() == null &&
                tab.getEditorArea().getText().isEmpty()) {
            tab.setFile(file);
            tab.loadFileFromExternal(file);
            updateTabTitle(tab);
        } else {
            addNewTab(file);
        }
        
        addToRecentFiles(file);
    }

    /* Saves the active tab's file. */
    public void saveFile() {
        CodeTab tab = getActiveTab();
        if (tab == null) return;

        if (tab.getFile() == null) {
            saveFileAs();
        } else {
            tab.saveFile();
            updateTabTitle(tab);
        }
    }

    private void saveFileAs() {
        CodeTab tab = getActiveTab();
        if (tab == null) return;

        // Use standard Swing component equipped with built-in "File Name" bottom input field
        JFileChooser nativeSaver = new JFileChooser();
        nativeSaver.setDialogTitle("Save As...");
        
        // Pre-select the current folder context if available
        if (tab.getFile() != null && tab.getFile().getParentFile() != null) {
            nativeSaver.setCurrentDirectory(tab.getFile().getParentFile());
            nativeSaver.setSelectedFile(new File(tab.getFile().getName()));
        } else {
            nativeSaver.setSelectedFile(new File("Untitled.txt"));
        }

        int result = nativeSaver.showSaveDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = nativeSaver.getSelectedFile();
            if (selectedFile != null) {
                tab.setFile(selectedFile);
                tab.saveFile();
                updateTabTitle(tab);
                addToRecentFiles(selectedFile);
            }
        }
    }

    /* Closes a specific tab, prompting to save if modified. */
    private void closeTab(CodeTab tab) {
        if (tab.isModified()) {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    "The file \"" + (tab.getFile() != null ? tab.getFile().getName() : "Untitled") +
                            "\" has been modified. Do you want to save before closing?",
                    "Unsaved Changes",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
                return;
            }
            if (choice == JOptionPane.YES_OPTION) {
                tab.saveFile();
                if (tab.isModified()) {
                    return;
                }
            }
        }

        int index = tabbedPane.indexOfComponent(tab);
        if (index >= 0) {
            tabbedPane.removeTabAt(index);
        }
        if (tabbedPane.getTabCount() == 0) {
            addNewTab(null);
        }
    }

    /* Closes all tabs except the given one. */
    private void closeOthers(CodeTab keep) {
        List<CodeTab> toClose = new ArrayList<>();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof CodeTab tab && tab != keep) {
                toClose.add(tab);
            }
        }
        for (CodeTab tab : toClose) {
            closeTab(tab);
        }
    }

    /* Closes all open tabs. */
    private void closeAllTabs() {
        List<CodeTab> toClose = new ArrayList<>();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof CodeTab tab) {
                toClose.add(tab);
            }
        }
        for (CodeTab tab : toClose) {
            closeTab(tab);
        }
    }

    /* Sets up the menu bar for the given frame. */
    public void setupMenu(JFrame frame) {
        JMenuBar menuBar = new JMenuBar();
        menuBar.setBackground(palette.getBackgroundSurface());
        menuBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, palette.getBackgroundMain()));

        // File Menu configuration
        JMenu fileMenu = new JMenu("File");
        fileMenu.setForeground(palette.getTextPrimary());
        fileMenu.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));

        fileMenu.add(createMenuItem("New", KeyEvent.VK_N, e -> newFile()));
        fileMenu.add(createMenuItem("Open", KeyEvent.VK_O, e -> openFile()));

        // Open Recent submenu configuration
        recentMenu = new JMenu("Open Recent");
        recentMenu.setForeground(palette.getTextPrimary());
        recentMenu.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        fileMenu.add(recentMenu);
        rebuildRecentMenu();

        fileMenu.add(createMenuItem("Save", KeyEvent.VK_S, e -> saveFile()));
        fileMenu.add(createMenuItem("Save As...", KeyEvent.VK_A, e -> saveFileAs()));

        fileMenu.addSeparator();

        fileMenu.add(createMenuItem("Close Tab", KeyEvent.VK_W, e -> {
            CodeTab tab = getActiveTab();
            if (tab != null) closeTab(tab);
        }));
        fileMenu.add(createMenuItem("Close Others", KeyEvent.VK_E, e -> {
            CodeTab tab = getActiveTab();
            if (tab != null) closeOthers(tab);
        }));
        fileMenu.add(createMenuItem("Close All", KeyEvent.VK_L, e -> closeAllTabs()));

        fileMenu.addSeparator();
        fileMenu.add(createMenuItem("Quit", KeyEvent.VK_Q, e -> handleExit()));

        // Edit Menu configuration
        JMenu editMenu = new JMenu("Edit");
        editMenu.setForeground(palette.getTextPrimary());
        editMenu.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));

        editMenu.add(createMenuItem("Undo", KeyEvent.VK_Z, e -> {
            CodeTab tab = getActiveTab();
            if (tab != null) {
                tab.safeUndo();
            }
        }));
        editMenu.add(createMenuItem("Redo", KeyEvent.VK_Y, e -> {
            CodeTab tab = getActiveTab();
            if (tab != null && tab.getUndoManager().canRedo()) tab.getUndoManager().redo();
        }));
        editMenu.addSeparator();
        editMenu.add(createMenuItem("Cut", KeyEvent.VK_X, e -> {
            CodeTab tab = getActiveTab();
            if (tab != null) tab.getEditorArea().cut();
        }));
        editMenu.add(createMenuItem("Copy", KeyEvent.VK_C, e -> {
            CodeTab tab = getActiveTab();
            if (tab != null) tab.getEditorArea().copy();
        }));
        editMenu.add(createMenuItem("Paste", KeyEvent.VK_V, e -> {
            CodeTab tab = getActiveTab();
            if (tab != null) tab.getEditorArea().paste();
        }));
        editMenu.add(createMenuItem("Select All", KeyEvent.VK_A, e -> {
            CodeTab tab = getActiveTab();
            if (tab != null) tab.getEditorArea().selectAll();
        }));
        editMenu.addSeparator();
        editMenu.add(createMenuItem("Find and Replace", KeyEvent.VK_F, e -> {
            CodeTab tab = getActiveTab();
            if (tab != null) {
                FindReplaceDialog dialog = new FindReplaceDialog(
                        (Frame) SwingUtilities.getWindowAncestor(this),
                        tab.getEditorArea()
                );
                dialog.setVisible(true);
            }
        }));

        // View Menu configuration
        JMenu viewMenu = new JMenu("View");
        viewMenu.setForeground(palette.getTextPrimary());
        viewMenu.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));

        lineNumbersItem = new JCheckBoxMenuItem("Show Line Numbers", true);
        lineNumbersItem.setForeground(palette.getTextPrimary());
        lineNumbersItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        lineNumbersItem.addActionListener(e -> {
            CodeTab tab = getActiveTab();
            if (tab != null) {
                tab.setLineNumbersVisible(lineNumbersItem.isSelected());
            }
        });
        viewMenu.add(lineNumbersItem);

        alwaysOnTopItem = new JCheckBoxMenuItem("Always on Top", false);
        alwaysOnTopItem.setForeground(palette.getTextPrimary());
        alwaysOnTopItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        alwaysOnTopItem.addActionListener(e -> {
            if (frame != null) frame.setAlwaysOnTop(alwaysOnTopItem.isSelected());
        });
        viewMenu.add(alwaysOnTopItem);

        syncTextItem = new JCheckBoxMenuItem("Sync Text", true);
        syncTextItem.setForeground(palette.getTextPrimary());
        syncTextItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        syncTextItem.addActionListener(e -> {
            CodeTab tab = getActiveTab();
            if (tab != null) {
                tab.setSyncScroll(syncTextItem.isSelected());
            }
        });
        viewMenu.add(syncTextItem);

        // Text mode choices configured inside Edit Submenu
        editMenu.addSeparator();
        JMenu modeSubMenu = new JMenu("Text Mode");
        modeSubMenu.setForeground(palette.getTextPrimary());
        modeSubMenu.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));

        ButtonGroup modeGroup = new ButtonGroup();

        JRadioButtonMenuItem plainTextMode = new JRadioButtonMenuItem("Plain Text Mode", currentMode == EditorMode.PLAIN_TEXT);
        plainTextMode.addActionListener(e -> setEditorMode(EditorMode.PLAIN_TEXT));

        JRadioButtonMenuItem markdownMode = new JRadioButtonMenuItem("Markdown Mode", currentMode == EditorMode.MARKDOWN);
        markdownMode.addActionListener(e -> setEditorMode(EditorMode.MARKDOWN));

        JRadioButtonMenuItem latexMode = new JRadioButtonMenuItem("LaTeX Mode", currentMode == EditorMode.LATEX);
        latexMode.addActionListener(e -> setEditorMode(EditorMode.LATEX));

        modeGroup.add(plainTextMode);
        modeGroup.add(markdownMode);
        modeGroup.add(latexMode);

        modeSubMenu.add(plainTextMode);
        modeSubMenu.add(markdownMode);
        modeSubMenu.add(latexMode);

        editMenu.add(modeSubMenu);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(viewMenu);

        frame.setJMenuBar(menuBar);
    }

    /* Legacy undo/redo pipeline fallback hook preserved for signature layout compliance. */
    private void setupUndoRedo() {
        // No-op execution. Real undo actions map dynamically directly to individual CodeTab managers.
    }

    /* 
    *  Opens a file dialog sequence.
     */
    private void openFile() {
        Window parentWindow = SwingUtilities.getWindowAncestor(this);
        
        openFileChooser = new FlatFileChooser(parentWindow, null);
        openFileChooser.setLocationRelativeTo(this);
        openFileChooser.setVisible(true);

        File f = openFileChooser.getSelectedFile();
        if (f != null && f.isFile()) {
            loadFile(f);
        }
    }

    /* Localizes specified text targets across active document text fields. */
    public void findText(String text) {
        CodeTab tab = getActiveTab();
        if (tab == null) return;

        com.sphere.components.editor.CodeTextPane area = tab.getEditorArea();
        int index = area.getText().indexOf(text, area.getCaretPosition());
        if (index >= 0) {
            area.setCaretPosition(index);
            area.select(index, index + text.length());
        }
    }

    /* Shared macro helper assembling application standard uniform JMenuItems. */
    private JMenuItem createMenuItem(String label, int keyEvent, ActionListener action) {
        JMenuItem item = new JMenuItem(label);
        item.setAccelerator(
                KeyStroke.getKeyStroke(keyEvent, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx())
        );
        item.addActionListener(action);
        return item;
    }

    /**
     * Custom tab header with title and close button, styled like Visual Studio Code
     * with mode-colored border and rounded top corners.
     */
    private class TabHeader extends JPanel {
        private final JLabel titleLabel;
        private final JTabbedPane parent;
        private final CodeTab tab;
        private final QuickCodeEditor owner;

        TabHeader(JTabbedPane parent, CodeTab tab, String title) {
            this.parent = parent;
            this.tab = tab;
            this.owner = QuickCodeEditor.this;

            setOpaque(false);
            setLayout(new FlowLayout(FlowLayout.LEFT, 6, 2));

            titleLabel = new JLabel(title);
            titleLabel.setForeground(palette.getTextPrimary());
            titleLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 5));

            Icon closeIcon = IconManager.getIcon("close.png");
            JButton closeButton = new JButton(closeIcon);
            closeButton.setBorder(BorderFactory.createEmptyBorder());
            closeButton.setContentAreaFilled(false);
            closeButton.setFocusable(false);
            closeButton.setOpaque(false);
            closeButton.setRolloverEnabled(true);
            closeButton.setToolTipText("Close Tab");
            closeButton.addActionListener(e -> closeTab(tab));

            add(titleLabel);
            add(closeButton);
        }

        void setTitle(String title) {
            titleLabel.setText(title);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            int index = parent.indexOfTabComponent(this);
            if (index < 0) return;

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON
                );

                int w = getWidth();
                int h = getHeight();
                int arc = 10;

                Color modeColor = owner.getBorderColorForMode(tab.getCurrentMode());
                g2.setColor(modeColor);
                g2.setStroke(new BasicStroke(1.5f));

                // Formulate top corner soft curvature coordinates
                Shape shape = new RoundRectangle2D.Float(
                        1, 1,
                        w - 2, h + 10,
                        arc, arc
                );

                g2.setClip(0, 0, w, h - 2);
                g2.draw(shape);
                g2.setClip(null);

                // Selected Tab styling (VS Code dynamic accent underscore line)
                if (parent.getSelectedComponent() == tab) {
                    g2.setColor(palette.getTabEditorSelectBg()); 
                    g2.fillRect(0, h - 3, w, 3);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * Gets the active file resource being handled by this editor viewport.
     * @return The open File reference context, or null if unassigned.
     */
    public File getCurrentFile() {
        return this.currentFile;
    }
}

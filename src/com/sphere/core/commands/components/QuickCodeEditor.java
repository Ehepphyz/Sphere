package com.sphere.components;

import javax.swing.*;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.sphere.components.editor.CodeInsight;
import com.sphere.components.editor.CodeTab;
import com.sphere.components.editor.CommandPalette;
import com.sphere.components.editor.DocSymbol;
import com.sphere.components.editor.EditorActions;
import com.sphere.components.editor.EditorDiagnostic;
import com.sphere.components.editor.EditorIcons;
import com.sphere.components.editor.EditorState;
import com.sphere.components.editor.EditorStatusBar;
import com.sphere.components.editor.EditorWidgets;
import com.sphere.components.editor.LanguageSpec;
import com.sphere.components.editor.ProblemsPanel;
import com.sphere.components.fileexplorerincludes.FlatFileChooser;
import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;
import com.sphere.components.editor.EditorTheme;
import com.sphere.components.editor.BreakpointModel;
import com.sphere.components.editor.debug.DebugPanel;
import com.sphere.utils.AppLogger;
import com.sphere.utils.IconManager;

/**
 * Main editor component providing a multi-tab interface for code editing and live previews.
 * Each tab is a CodeTab, colored by its language. Around the tabs sit the command
 * palette (Ctrl+Shift+P, Ctrl+P, Ctrl+Shift+O, Ctrl+G), the status bar, the
 * Problems and Debug panels, and notifications. Public API kept for compatibility.
 */
public class QuickCodeEditor extends JPanel implements CodeTab.Host, CodeTab.ZoomHost {

    /* Global editor mode enum (used by menu, applied to active tab only) */
    public enum EditorMode { PLAIN_TEXT, MARKDOWN, LATEX }
    private EditorMode currentMode = EditorMode.PLAIN_TEXT;

    // Transformed to follow strict Open-only isolation scope architecture
    private FlatFileChooser openFileChooser;

    /* UI optional items */
    private final EditorStatusBar status = new EditorStatusBar();
    private final Map<String, JCheckBoxMenuItem> toggles = new HashMap<>();
    private JCheckBoxMenuItem alwaysOnTopItem;

    /* Multi-tab support */
    private final JTabbedPane tabbedPane;
    private File currentFile;
    private final Deque<File> closedFiles = new ArrayDeque<>();
    private int untitledCounter = 0;

    /* Open Recent support */
    private JMenu recentMenu;

    /* Debugging: breakpoints already existed, nothing consumed them. */
    private final com.sphere.utils.SettingsManager settings = new com.sphere.utils.SettingsManager();
    private DebugPanel debugPanel;
    private final ProblemsPanel problemsPanel = new ProblemsPanel();
    private final JPanel bottomPanel = new JPanel(new BorderLayout());
    private final CardLayout bottomCards = new CardLayout();
    private final JPanel bottomContent = new JPanel(bottomCards);
    private final Map<String, EditorWidgets.FlatToggle> bottomTabs = new LinkedHashMap<>();
    private String bottomShown;
    private JSplitPane debugSplit;
    private JCheckBoxMenuItem debugPanelItem;

    /* Palette, notifications, commands */
    private final CommandPalette palette = new CommandPalette();
    private final Toast toast = new Toast();
    private final EditorWidgets.OverlayPane overlay;
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final Timer diskWatch;
    private final Timer problemsRefresh;

    public QuickCodeEditor() {

        setLayout(new BorderLayout());
        setBackground(EditorTheme.background());
        problemsRefresh = new Timer(250, e -> problemsPanel.update(allTabs(), getActiveTab()));
        problemsRefresh.setRepeats(false);

        CodeTab.setWatermark(List.of(
                new String[] { "Show All Commands", "Ctrl+Shift+P" },
                new String[] { "Go to File", "Ctrl+P" },
                new String[] { "Go to Symbol", "Ctrl+Shift+O" },
                new String[] { "Open File", "Ctrl+O" },
                new String[] { "Find in File", "Ctrl+F" },
                new String[] { "Run in Sphere", "F5" }));

        /* Main UI: tabbed pane with CodeTab instances */
        tabbedPane = new JTabbedPane() {
            // A theme switch reinstalls every UI; the language-colored tabs must survive it.
            @Override
            public void updateUI() {
                setUI(new EditorTabsUI());
                setBackground(EditorTheme.chrome());
            }
        };
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        styleTabbedPaneVSCode(tabbedPane);

        tabbedPane.addChangeListener(e -> {
            CodeTab active = getActiveTab();
            currentFile = active == null ? null : active.getFile();
            updateStatusBar();
            refreshTabHeaders();
            if (active != null) {
                active.checkDisk();
            }
            problemsRefresh.restart();
        });
        tabbedPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                // A double click on the empty strip opens a new file, as in VS Code.
                if (e.getClickCount() == 2 && tabbedPane.indexAtLocation(e.getX(), e.getY()) < 0
                        && e.getY() < 40) {
                    newFile();
                }
            }
        });

        debugPanel = new DebugPanel(new EditorDebugHost(), settings);
        buildBottomPanel();
        debugSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabbedPane, bottomPanel);
        debugSplit.setResizeWeight(1.0);
        debugSplit.setBorder(null);
        debugSplit.setDividerSize(4);
        debugSplit.setContinuousLayout(true);

        overlay = new EditorWidgets.OverlayPane(debugSplit);
        overlay.addOverlay(palette, EditorWidgets.Anchor.TOP_CENTER, 20, 34);
        overlay.addOverlay(toast, EditorWidgets.Anchor.BOTTOM_RIGHT, 18, 14);
        add(overlay, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        problemsPanel.setNavigator(this::navigateTo);

        buildCommands();
        installCommandKeys();
        installPalette();
        installStatusBar();
        hideBottom();

        /* Create initial empty tab */
        addNewTab(null);

        // A file changed by a formatter or a git checkout shows up without a restart.
        diskWatch = new Timer(2000, e -> {
            CodeTab t = getActiveTab();
            if (t != null && isShowing()) {
                t.checkDisk();
            }
        });
        diskWatch.start();

        /* Window close handling and custom application branding icon initialization */
        SwingUtilities.invokeLater(() -> {
            Window parentWindow = SwingUtilities.getWindowAncestor(QuickCodeEditor.this);
            if (parentWindow instanceof JFrame frame) {

                IconManager.applyAppIcon(frame);

                frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) {
                        handleExit();
                    }

                    @Override
                    public void windowActivated(WindowEvent e) {
                        for (CodeTab t : allTabs()) {
                            t.checkDisk();
                        }
                    }
                });
            }
        });
    }

    // =======================================================================
    // Tabs
    // =======================================================================

    /* Applies a Visual Studio Code-like style to the tabbed pane, colored per language. */
    private void styleTabbedPaneVSCode(JTabbedPane tabs) {
        tabs.setUI(new EditorTabsUI());
        tabs.setBackground(EditorTheme.chrome());
        tabs.setForeground(EditorTheme.foreground());
        tabs.setBorder(BorderFactory.createEmptyBorder());
        tabs.setFocusable(false);
    }

    /** Tab strip: rounded tabs outlined in their language's color, the active one joined to the code. */
    private final class EditorTabsUI extends BasicTabbedPaneUI {

        @Override
        protected void installDefaults() {
            super.installDefaults();
            tabAreaInsets = new Insets(6, 6, 0, 6);
            contentBorderInsets = new Insets(0, 0, 0, 0);
            tabInsets = new Insets(0, 0, 0, 0);
            selectedTabPadInsets = new Insets(0, 0, 0, 0);
        }

        @Override
        protected int calculateTabHeight(int placement, int index, int fontHeight) {
            return 33;
        }

        @Override
        protected int calculateTabWidth(int placement, int index, FontMetrics fm) {
            Component c = tabPane.getTabComponentAt(index);
            return c != null ? c.getPreferredSize().width + 4 : super.calculateTabWidth(placement, index, fm);
        }

        @Override
        protected Insets getTabAreaInsets(int placement) {
            return tabAreaInsets;
        }

        @Override
        protected void paintTabArea(Graphics g, int placement, int selectedIndex) {
            Rectangle clip = g.getClipBounds();
            g.setColor(EditorTheme.chrome());
            if (clip != null) {
                g.fillRect(clip.x, clip.y, clip.width, clip.height);
            }
            super.paintTabArea(g, placement, selectedIndex);
        }

        private Color colorOf(int index) {
            Component c = tabPane.getComponentAt(index);
            if (c instanceof CodeTab t) {
                return EditorTheme.languageColor(t.getLanguage());
            }
            return EditorTheme.textMuted();
        }

        @Override
        protected void paintTabBackground(Graphics g, int placement, int index, int x, int y, int w, int h,
                                          boolean selected) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = selected ? EditorTheme.background()
                       : getRolloverTab() == index ? EditorTheme.tabHover() : EditorTheme.tabInactive();
            g2.setColor(fill);
            g2.fill(new RoundRectangle2D.Float(x + 1, y + 1, w - 2, h + 8, 9, 9));
            if (selected) {
                // The active tab flows into the code below it.
                g2.fillRect(x + 1, y + h - 4, w - 2, 6);
            }
            g2.dispose();
        }

        @Override
        protected void paintTabBorder(Graphics g, int placement, int index, int x, int y, int w, int h,
                                      boolean selected) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color lang = colorOf(index);
            g2.setClip(x, y, w, h);
            g2.setStroke(new BasicStroke(selected ? 1.4f : 1f));
            g2.setColor(selected ? lang : EditorTheme.alpha(lang, 95));
            g2.draw(new RoundRectangle2D.Float(x + 1.5f, y + 1.5f, w - 3, h + 10, 9, 9));
            if (selected) {
                // A bar of the language's color on top, the active tab's mark.
                g2.setClip(null);
                g2.setColor(lang);
                g2.fill(new RoundRectangle2D.Float(x + 2, y + 1, w - 4, 3, 3, 3));
            }
            g2.dispose();
        }

        @Override
        protected void paintContentBorder(Graphics g, int placement, int selectedIndex) {
            // the code surface meets the strip directly
        }

        @Override
        protected void paintFocusIndicator(Graphics g, int placement, Rectangle[] rects, int index,
                                           Rectangle iconRect, Rectangle textRect, boolean selected) {
            // no dotted ring: the selected tab is already marked
        }

        @Override
        protected JButton createScrollButton(int direction) {
            boolean east = direction == EAST;
            EditorWidgets.FlatButton b = new EditorWidgets.FlatButton(
                    east ? EditorIcons.chevron(14, EditorTheme::foreground, false)
                         : new FlippedIcon(EditorIcons.chevron(14, EditorTheme::foreground, false)),
                    east ? "Scroll tabs right" : "Scroll tabs left");
            b.setPreferredSize(new Dimension(22, 22));
            return b;
        }
    }

    /** A chevron pointing the other way. */
    private static final class FlippedIcon implements Icon {
        private final Icon icon;

        FlippedIcon(Icon icon) {
            this.icon = icon;
        }

        @Override public int getIconWidth() { return icon.getIconWidth(); }
        @Override public int getIconHeight() { return icon.getIconHeight(); }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.translate(x + icon.getIconWidth(), y);
            g2.scale(-1, 1);
            icon.paintIcon(c, g2, 0, 0);
            g2.dispose();
        }
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

    /** The palette in force now; read each time so a theme switch is followed. */
    private static ThemePalette theme() {
        return ThemeManager.getCurrentPalette();
    }

    /* Returns the border accent highlights matching individual text formatting modes. */
    public Color getBorderColorForMode(CodeTab.EditorMode mode) {
        return switch (mode) {
            case MARKDOWN -> theme().getEdBorderMarkd();   // Dark Accent Soft Orange
            case LATEX -> theme().getEdBorderLatex();      // Forest Green
            case PLAIN_TEXT -> theme().getEdBorderPtext(); // Muted Golden Rod
            default -> theme().getEdBorderDefault();          // Neutral Medium Gray
        };
    }

    /* Adds a new tab, optionally bound to an existing file. */
    private void addNewTab(File file) {
        CodeTab tab = new CodeTab(file);
        tab.setHost(this);
        watchBreakpoints(tab);
        String tabTitle = file != null ? file.getName() : "Untitled-" + (++untitledCounter);
        tab.putClientProperty("sphere.untitled", tabTitle);
        tab.setUntitledName(tabTitle);
        tabbedPane.addTab(tabTitle, tab);

        int index = tabbedPane.indexOfComponent(tab);
        tabbedPane.setTabComponentAt(index, new TabHeader(tab));
        tabbedPane.setSelectedComponent(tab);

        // Update title when modified
        tab.getEditorArea().getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateTabTitle(tab); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateTabTitle(tab); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        });

        // Update status bar when caret moves
        tab.getEditorArea().addCaretListener(e -> updateStatusBar());

        refreshTabHeaders();
        SwingUtilities.invokeLater(() -> tab.getEditorArea().requestFocusInWindow());
    }

    /* Updates the tab title based on file name and modified state. */
    private void updateTabTitle(CodeTab tab) {
        int index = tabbedPane.indexOfComponent(tab);
        if (index < 0) return;
        Component header = tabbedPane.getTabComponentAt(index);
        if (header instanceof TabHeader th) {
            th.refresh();
        } else {
            File f = tab.getFile();
            String base = (f != null) ? f.getName() : "Untitled";
            tabbedPane.setTitleAt(index, tab.isModified() ? "*" + base : base);
        }
        if (tab == getActiveTab()) {
            updateFrameTitle();
        }
    }

    private void refreshTabHeaders() {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component header = tabbedPane.getTabComponentAt(i);
            if (header instanceof TabHeader th) {
                th.refresh();
            }
        }
        tabbedPane.repaint();
    }

    private String nameOf(CodeTab tab) {
        if (tab.getFile() != null) {
            return tab.getFile().getName();
        }
        Object n = tab.getClientProperty("sphere.untitled");
        return n == null ? "Untitled" : n.toString();
    }

    private void moveTab(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= tabbedPane.getTabCount() || to >= tabbedPane.getTabCount()) {
            return;
        }
        Component comp = tabbedPane.getComponentAt(from);
        Component header = tabbedPane.getTabComponentAt(from);
        String title = tabbedPane.getTitleAt(from);
        String tip = tabbedPane.getToolTipTextAt(from);
        tabbedPane.removeTabAt(from);
        tabbedPane.insertTab(title, null, comp, tip, to);
        tabbedPane.setTabComponentAt(to, header);
        tabbedPane.setSelectedIndex(to);
    }

    /**
     * Header of one tab: the language badge, the name (with its folder when two
     * open files share it), and a close button that shows a dot while the file
     * has unsaved changes.
     */
    private final class TabHeader extends JPanel {
        private final CodeTab tab;
        private final JLabel badge = new JLabel();
        private final JLabel title = new JLabel();
        private final CloseButton close;
        private Point pressAt;
        private boolean dragging;

        TabHeader(CodeTab tab) {
            this.tab = tab;
            setOpaque(false);
            setLayout(new BorderLayout(7, 0));
            setBorder(BorderFactory.createEmptyBorder(3, 10, 0, 6));
            title.setFont(FontLoader.getGlobalFont(Font.PLAIN, 13));
            close = new CloseButton(tab);
            add(badge, BorderLayout.WEST);
            add(title, BorderLayout.CENTER);
            add(close, BorderLayout.EAST);

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    pressAt = e.getPoint();
                    dragging = false;
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        tabbedPane.setSelectedComponent(tab);
                        tab.getEditorArea().requestFocusInWindow();
                    }
                    if (e.isPopupTrigger()) {
                        showTabMenu(e);
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (SwingUtilities.isMiddleMouseButton(e)) {
                        closeTab(tab);
                        return;
                    }
                    if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                        showTabMenu(e);
                    }
                    dragging = false;
                    setCursor(Cursor.getDefaultCursor());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (pressAt == null || !SwingUtilities.isLeftMouseButton(e)) {
                        return;
                    }
                    if (!dragging && Math.abs(e.getX() - pressAt.x) > 8) {
                        dragging = true;
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    }
                    if (dragging) {
                        Point p = SwingUtilities.convertPoint(TabHeader.this, e.getPoint(), tabbedPane);
                        int target = tabbedPane.indexAtLocation(p.x, Math.min(p.y, 20));
                        int from = tabbedPane.indexOfComponent(tab);
                        if (target >= 0 && target != from) {
                            moveTab(from, target);
                        }
                    }
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    close.setHeaderHover(true);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    close.setHeaderHover(false);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            refresh();
        }

        void refresh() {
            badge.setIcon(EditorIcons.LanguageBadge.of(tab.getLanguage(), tab.getFile()));
            String name = nameOf(tab);
            String suffix = "";
            if (tab.getFile() != null) {
                int same = 0;
                for (CodeTab t : allTabs()) {
                    if (t.getFile() != null && t.getFile().getName().equals(name)) {
                        same++;
                    }
                }
                File parent = tab.getFile().getAbsoluteFile().getParentFile();
                if (same > 1 && parent != null) {
                    suffix = parent.getName();
                }
            }
            boolean active = tabbedPane.getSelectedComponent() == tab;
            Color fg = active ? EditorTheme.foreground() : EditorTheme.textMuted();
            if (suffix.isEmpty()) {
                title.setText(name);
            } else {
                title.setText("<html>" + escape(name) + " <font color='" + hex(EditorTheme.textFaint()) + "'>"
                              + escape(suffix) + "</font></html>");
            }
            title.setFont(FontLoader.getGlobalFont(tab.getFile() == null ? Font.ITALIC : Font.PLAIN, 13));
            title.setForeground(fg);
            setToolTipText(tab.getFile() != null ? tab.getFile().getAbsolutePath() : name + " (not saved yet)");
            close.repaint();
            revalidate();
            repaint();
        }

        private void showTabMenu(MouseEvent e) {
            JPopupMenu menu = new JPopupMenu();
            menu.add(item("Close", () -> closeTab(tab), "Ctrl+W"));
            menu.add(item("Close Others", () -> closeOthers(tab), null));
            menu.add(item("Close to the Right", () -> closeToTheRight(tab), null));
            menu.add(item("Close Saved", this::closeSaved, null));
            menu.add(item("Close All", QuickCodeEditor.this::closeAllTabs, "Ctrl+Shift+W"));
            menu.addSeparator();
            JMenuItem copy = item("Copy Path", () -> copyPath(tab), null);
            copy.setEnabled(tab.getFile() != null);
            menu.add(copy);
            JMenuItem reveal = item("Reveal in File Explorer", () -> revealInExplorer(tab), null);
            reveal.setEnabled(tab.getFile() != null);
            menu.add(reveal);
            if (tab.isRunnable()) {
                menu.addSeparator();
                menu.add(item("Run in Sphere Console", () -> runFile(tab), "F5"));
            }
            menu.show(this, e.getX(), e.getY());
        }

        private void closeSaved() {
            for (CodeTab t : allTabs()) {
                if (!t.isModified()) {
                    closeTab(t);
                }
            }
        }
    }

    private static JMenuItem item(String label, Runnable r, String key) {
        JMenuItem it = new JMenuItem(label);
        it.addActionListener(e -> r.run());
        if (key != null) {
            // "Ctrl+Shift+W" reads as KeyStroke's "ctrl shift W".
            String[] parts = key.split("[+]");
            StringBuilder spec = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                spec.append(parts[i].toLowerCase()).append(' ');
            }
            spec.append(parts[parts.length - 1].toUpperCase());
            it.setAccelerator(KeyStroke.getKeyStroke(spec.toString()));
        }
        return it;
    }

    /** The close cross of a tab; a dot instead while the file has unsaved changes. */
    private final class CloseButton extends JComponent {
        private final CodeTab tab;
        private boolean hover;
        private boolean headerHover;

        CloseButton(CodeTab tab) {
            this.tab = tab;
            setPreferredSize(new Dimension(18, 18));
            setToolTipText("Close (Ctrl+W)");
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                @Override public void mouseReleased(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e) && contains(e.getPoint())) {
                        closeTab(tab);
                    }
                }
            });
        }

        void setHeaderHover(boolean value) {
            headerHover = value;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            boolean active = tabbedPane.getSelectedComponent() == tab;
            if (hover) {
                g2.setColor(EditorTheme.alpha(EditorTheme.foreground(), 34));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, 6, 6));
            }
            boolean showCross = hover || ((active || headerHover) && !tab.isModified());
            if (tab.isModified() && !hover) {
                g2.setColor(active ? EditorTheme.foreground() : EditorTheme.textMuted());
                g2.fillOval(w / 2 - 4, h / 2 - 4, 8, 8);
            } else if (showCross) {
                g2.setColor(active || hover ? EditorTheme.foreground() : EditorTheme.textMuted());
                g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int a = 5;
                g2.drawLine(a, a, w - a, h - a);
                g2.drawLine(a, h - a, w - a, a);
            }
            g2.dispose();
        }
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // =======================================================================
    // Recent files
    // =======================================================================

    /* Adds a file to the recent files list (remembered across sessions). */
    private void addToRecentFiles(File file) {
        if (file == null) return;
        EditorState.addRecent(file);
        rebuildRecentMenu();
    }

    /* Rebuilds the "Open Recent" submenu. */
    private void rebuildRecentMenu() {
        if (recentMenu == null) return;
        recentMenu.removeAll();
        List<File> recent = EditorState.recentFiles();
        if (recent.isEmpty()) {
            JMenuItem empty = new JMenuItem("(Empty)");
            empty.setEnabled(false);
            recentMenu.add(empty);
            return;
        }
        for (File f : recent) {
            JMenuItem item = new JMenuItem(f.getName() + "   " + f.getParent());
            item.setIcon(EditorIcons.LanguageBadge.of(LanguageSpec.forFile(f), f));
            item.addActionListener(e -> openFile(f));
            recentMenu.add(item);
        }
        recentMenu.addSeparator();
        JMenuItem clear = new JMenuItem("Clear Recently Opened");
        clear.addActionListener(e -> {
            EditorState.clearRecent();
            rebuildRecentMenu();
        });
        recentMenu.add(clear);
    }

    private void newFile() {
        // Instantiates a completely virtual unsaved document context layout pointer
        // Passing null forces the tab to treat it as an unsaved buffer target
        addNewTab(null);
    }

    // =======================================================================
    // Status bar
    // =======================================================================

    private void installStatusBar() {
        status.problems.onClick(s -> toggleBottom("problems"));
        status.cursor.onClick(s -> showPalette(":"));
        status.language.onClick(s -> pickLanguage());
        status.eol.onClick(s -> pickLineEnding());
        status.encoding.onClick(s -> pickEncoding());
        status.indentation.onClick(s -> pickIndentation());
        status.insight.onClick(s -> showPalette("@"));
    }

    /* Updates the status bar with caret position of the active tab. */
    private void updateStatusBar() {
        CodeTab tab = getActiveTab();
        int errors = 0;
        int warnings = 0;
        for (CodeTab t : allTabs()) {
            for (EditorDiagnostic d : t.getAllDiagnostics()) {
                if (d.getSeverity() == EditorDiagnostic.Severity.ERROR) {
                    errors++;
                } else if (d.getSeverity() == EditorDiagnostic.Severity.WARNING) {
                    warnings++;
                }
            }
        }
        status.problems.set(EditorIcons.error(14), String.valueOf(errors), EditorIcons.warning(14),
                            String.valueOf(warnings));
        if (tab == null) {
            return;
        }
        com.sphere.components.editor.CodeTextPane area = tab.getEditorArea();
        try {
            int caretPos = area.getCaretPosition();
            javax.swing.text.Element root = area.getDocument().getDefaultRootElement();
            int line = root.getElementIndex(caretPos);
            int column = caretPos - root.getElement(line).getStartOffset();
            int selected = Math.abs(area.getSelectionEnd() - area.getSelectionStart());
            status.cursor.set(null, "Ln " + (line + 1) + ", Col " + (column + 1)
                                    + (selected > 0 ? "  (" + selected + " selected)" : ""));
        } catch (Exception ignored) {
            status.cursor.set(null, "Ln 1, Col 1");
        }
        boolean tabs = tab.getIndenter().usesTabs();
        status.indentation.set(null, (tabs ? "Tab Size: " : "Spaces: ") + tab.getIndenter().indentSize());
        status.encoding.set(null, displayCharset(tab.getCharset()));
        status.eol.set(null, tab.getLineSeparator().equals("\r\n") ? "CRLF" : "LF");
        LanguageSpec lang = tab.getLanguage();
        status.language.setDot(EditorTheme.languageColor(lang));
        status.language.set(null, lang.name);
        CodeInsight.Result r = tab.getInsight().result();
        int symbols = r.symbols.size();
        status.insight.set(EditorIcons.bolt(13, () -> EditorTheme.readable(new Color(250, 200, 60))),
                           symbols == 0 ? "Insight" : symbols + " symbol" + (symbols == 1 ? "" : "s"));
        status.insight.setToolTipText("Sphere Insight — " + symbols + " symbols, " + r.diagnostics.size()
                                      + " findings in this file. Click: Go to Symbol (Ctrl+Shift+O)");
        updateFrameTitle();
    }

    private static String displayCharset(Charset cs) {
        if (cs == null || cs.equals(StandardCharsets.UTF_8)) {
            return "UTF-8";
        }
        return cs.name();
    }

    private void updateFrameTitle() {
        Window w = SwingUtilities.getWindowAncestor(this);
        CodeTab tab = getActiveTab();
        if (w instanceof JFrame frame && tab != null) {
            frame.setTitle((tab.isModified() ? "● " : "") + nameOf(tab) + " — Sphere Editor");
        }
    }

    // =======================================================================
    // CodeTab.Host
    // =======================================================================

    @Override
    public void openFile(File file) {
        if (file == null) {
            return;
        }
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w instanceof com.sphere.ui.QuickCodeEditorFrame frame) {
            frame.openFileInternally(file);
        } else {
            loadFile(file);
        }
    }

    @Override
    public void runFile(CodeTab tab) {
        if (tab == null) {
            return;
        }
        if (tab.getFile() == null) {
            saveFileAs();
            if (tab.getFile() == null) {
                return;
            }
        }
        String prefix = LanguageSpec.runCommandFor(tab.getLanguage(), tab.getFile());
        if (prefix == null) {
            toast("Sphere does not run " + tab.getLanguage().name + " files");
            return;
        }
        if (tab.isModified()) {
            tab.saveFile();
            updateTabTitle(tab);
        }
        com.sphere.core.CommandRouter router = com.sphere.core.CommandRouter.active();
        if (router == null) {
            toast("The Sphere console is not running");
            return;
        }
        String path = tab.getFile().getAbsolutePath();
        if (path.indexOf(' ') >= 0) {
            toast("The path contains a space; the console may not read it");
        }
        final String command = prefix + " [@ " + path + "]";
        AppLogger.recall(command);
        Thread runner = new Thread(() -> {
            try {
                router.processInput(command);
            } catch (Exception ex) {
                AppLogger.error("Run failed: " + ex.getMessage());
            }
        }, "sphere-editor-run");
        runner.setDaemon(true);
        runner.start();
        toast("Running " + tab.getFile().getName() + " in the Sphere console");
    }

    @Override
    public void showPalette(String prefix) {
        palette.open(prefix == null ? "" : prefix);
    }

    @Override
    public void toast(String message) {
        toast.show(message);
    }

    @Override
    public List<CodeTab> allTabs() {
        List<CodeTab> out = new ArrayList<>();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            if (tabbedPane.getComponentAt(i) instanceof CodeTab t) {
                out.add(t);
            }
        }
        return out;
    }

    @Override
    public void selectTab(CodeTab tab) {
        tabbedPane.setSelectedComponent(tab);
    }

    @Override
    public void tabStateChanged(CodeTab tab) {
        if (tab == getActiveTab()) {
            updateStatusBar();
        }
        updateTabTitle(tab);
        problemsRefresh.restart();
    }

    @Override
    public void zoom(int steps) {
        int size = steps == 0 ? 13 : EditorState.fontSize() + steps;
        EditorState.setFontSize(size);
        for (CodeTab t : allTabs()) {
            t.applyFontSize(EditorState.fontSize());
        }
        toast("Font size " + EditorState.fontSize());
    }

    private void navigateTo(CodeTab tab, EditorDiagnostic d) {
        selectTab(tab);
        tab.goToLine(d.getLine(), d.getColumn());
    }

    // =======================================================================
    // Modes, exit
    // =======================================================================

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
        refreshTabHeaders();
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
                        "The file \"" + nameOf(tab) +
                                "\" has been modified. Do you want to save before quitting?",
                        "Unsaved Changes",
                        JOptionPane.YES_NO_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );
                if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
                    return;
                }
                if (choice == JOptionPane.YES_OPTION) {
                    if (tab.getFile() == null) {
                        saveFileAs();
                    } else {
                        tab.saveFile();
                    }
                    if (tab.isModified()) {
                        return;
                    }
                }
            }
        }
        EditorState.saveNow();
        if (parentWindow != null) parentWindow.dispose();
    }

    /* Sets the code of the active tab. */
    public void setCode(String code) {
        CodeTab tab = getActiveTab();
        if (tab != null) {
            tab.getEditorArea().setText(code == null ? "" : code.replace("\r\n", "\n"));
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
     * If the file is already open in an existing tab, that tab is selected and focused,
     * and reloaded when it changed on disk (a formatter, a refactoring) without edits here.
     * Otherwise, the content is loaded into an empty unmodified tab or appended as a new tab.
     * @param file The target file to load into the tabbed layout.
     */
    public void loadFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        CodeTab open = findTabForFile(file);
        if (open != null) {
            tabbedPane.setSelectedComponent(open);
            open.checkDisk();
            open.getEditorArea().requestFocusInWindow();
            this.currentFile = file;
            addToRecentFiles(file);
            return;
        }

        this.currentFile = file;
        CodeTab tab = getActiveTab();

        if (tab != null && !tab.isModified() && tab.getFile() == null &&
                tab.getEditorArea().getText().isEmpty()) {
            tab.setFile(file);
            tab.loadFileFromExternal(file);
            updateTabTitle(tab);
            refreshTabHeaders();
            updateStatusBar();
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
            updateStatusBar();
        }
    }

    private void saveAll() {
        int saved = 0;
        for (CodeTab t : allTabs()) {
            if (t.isModified() && t.getFile() != null) {
                t.saveFile();
                updateTabTitle(t);
                saved++;
            }
        }
        toast(saved == 0 ? "Nothing to save" : "Saved " + saved + " file" + (saved == 1 ? "" : "s"));
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
                refreshTabHeaders();
                addToRecentFiles(selectedFile);
                updateStatusBar();
            }
        }
    }

    /* Closes a specific tab, prompting to save if modified. */
    private void closeTab(CodeTab tab) {
        if (tab.isModified()) {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    "The file \"" + nameOf(tab) +
                            "\" has been modified. Do you want to save before closing?",
                    "Unsaved Changes",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
                return;
            }
            if (choice == JOptionPane.YES_OPTION) {
                if (tab.getFile() == null) {
                    tabbedPane.setSelectedComponent(tab);
                    saveFileAs();
                } else {
                    tab.saveFile();
                }
                if (tab.isModified()) {
                    return;
                }
            }
        }

        if (tab.getFile() != null) {
            closedFiles.push(tab.getFile());
            while (closedFiles.size() > 20) {
                closedFiles.removeLast();
            }
        }
        int index = tabbedPane.indexOfComponent(tab);
        if (index >= 0) {
            tabbedPane.removeTabAt(index);
        }
        if (tabbedPane.getTabCount() == 0) {
            addNewTab(null);
        }

        // currentFile used to keep naming the file whose tab had just closed, and
        // openFileInternally treats a match there as "already open" and returns.
        // The same file could then never be reopened without restarting Sphere.
        CodeTab active = getActiveTab();
        this.currentFile = active == null ? null : active.getFile();
        refreshTabHeaders();
        problemsRefresh.restart();
    }

    private void reopenClosed() {
        while (!closedFiles.isEmpty()) {
            File f = closedFiles.pop();
            if (f.exists()) {
                loadFile(f);
                return;
            }
        }
        toast("No closed editor to reopen");
    }

    /* Closes all tabs except the given one. */
    private void closeOthers(CodeTab keep) {
        for (CodeTab tab : allTabs()) {
            if (tab != keep) {
                closeTab(tab);
            }
        }
    }

    private void closeToTheRight(CodeTab tab) {
        int index = tabbedPane.indexOfComponent(tab);
        List<CodeTab> all = allTabs();
        for (int i = all.size() - 1; i > index; i--) {
            closeTab(all.get(i));
        }
    }

    /* Closes all open tabs. */
    private void closeAllTabs() {
        for (CodeTab tab : allTabs()) {
            closeTab(tab);
        }
    }

    private void copyPath(CodeTab tab) {
        if (tab != null && tab.getFile() != null) {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                   .setContents(new StringSelection(tab.getFile().getAbsolutePath()), null);
            toast("Path copied");
        }
    }

    private void revealInExplorer(CodeTab tab) {
        if (tab == null || tab.getFile() == null) {
            return;
        }
        File dir = tab.getFile().getAbsoluteFile().getParentFile();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
                Desktop.getDesktop().browseFileDirectory(tab.getFile());
            } else if (Desktop.isDesktopSupported() && dir != null) {
                Desktop.getDesktop().open(dir);
            }
        } catch (Exception ex) {
            toast("Could not open the folder: " + ex.getMessage());
        }
    }

    // =======================================================================
    // Commands
    // =======================================================================

    /** One thing the editor can do, reachable from a menu, a key and the palette. */
    private static final class Command {
        final String id;
        final String category;
        final String title;
        final KeyStroke key;
        final Runnable action;
        final BooleanSupplier checked;

        Command(String id, String category, String title, KeyStroke key, Runnable action, BooleanSupplier checked) {
            this.id = id;
            this.category = category;
            this.title = title;
            this.key = key;
            this.action = action;
            this.checked = checked;
        }

        String label() {
            return category.isEmpty() ? title : category + ": " + title;
        }
    }

    private static KeyStroke key(int code, int mods) {
        return KeyStroke.getKeyStroke(code, mods);
    }

    private void cmd(String id, String category, String title, KeyStroke key, Runnable action) {
        commands.put(id, new Command(id, category, title, key, action, null));
    }

    private void toggle(String id, String category, String title, KeyStroke key, BooleanSupplier checked,
                        Runnable action) {
        commands.put(id, new Command(id, category, title, key, action, checked));
    }

    private void withTab(java.util.function.Consumer<CodeTab> action) {
        CodeTab t = getActiveTab();
        if (t != null) {
            action.accept(t);
        }
    }

    private void buildCommands() {
        final int ctrl = InputEvent.CTRL_DOWN_MASK;
        final int shift = InputEvent.SHIFT_DOWN_MASK;
        final int alt = InputEvent.ALT_DOWN_MASK;

        // ---- File
        cmd("file.new", "File", "New File", key(KeyEvent.VK_N, ctrl), this::newFile);
        cmd("file.open", "File", "Open File...", key(KeyEvent.VK_O, ctrl), this::openFile);
        cmd("file.quickOpen", "Go", "Go to File...", key(KeyEvent.VK_P, ctrl), () -> showPalette(""));
        cmd("file.save", "File", "Save", key(KeyEvent.VK_S, ctrl), this::saveFile);
        cmd("file.saveAs", "File", "Save As...", key(KeyEvent.VK_S, ctrl | shift), this::saveFileAs);
        cmd("file.saveAll", "File", "Save All", key(KeyEvent.VK_S, ctrl | alt), this::saveAll);
        cmd("file.close", "File", "Close Editor", key(KeyEvent.VK_W, ctrl), () -> withTab(this::closeTab));
        cmd("file.closeOthers", "File", "Close Other Editors", null, () -> withTab(this::closeOthers));
        cmd("file.closeAll", "File", "Close All Editors", key(KeyEvent.VK_W, ctrl | shift), this::closeAllTabs);
        cmd("file.reopen", "File", "Reopen Closed Editor", key(KeyEvent.VK_T, ctrl | shift), this::reopenClosed);
        cmd("file.copyPath", "File", "Copy Path of Active File", null, () -> withTab(this::copyPath));
        cmd("file.reveal", "File", "Reveal Active File in File Explorer", null, () -> withTab(this::revealInExplorer));
        cmd("file.quit", "File", "Close Window", key(KeyEvent.VK_Q, ctrl), this::handleExit);

        // ---- Edit
        cmd("edit.undo", "Edit", "Undo", key(KeyEvent.VK_Z, ctrl), () -> withTab(CodeTab::undo));
        cmd("edit.redo", "Edit", "Redo", key(KeyEvent.VK_Y, ctrl), () -> withTab(CodeTab::redo));
        cmd("edit.cut", "Edit", "Cut", key(KeyEvent.VK_X, ctrl), () -> withTab(t -> t.getEditorArea().cut()));
        cmd("edit.copy", "Edit", "Copy", key(KeyEvent.VK_C, ctrl), () -> withTab(t -> t.getEditorArea().copy()));
        cmd("edit.paste", "Edit", "Paste", key(KeyEvent.VK_V, ctrl), () -> withTab(t -> t.getEditorArea().paste()));
        cmd("edit.selectAll", "Edit", "Select All", key(KeyEvent.VK_A, ctrl), () -> withTab(t -> t.getEditorArea().selectAll()));
        cmd("edit.find", "Edit", "Find", key(KeyEvent.VK_F, ctrl), () -> withTab(t -> t.openFind(false)));
        cmd("edit.replace", "Edit", "Replace", key(KeyEvent.VK_H, ctrl), () -> withTab(t -> t.openFind(true)));
        cmd("edit.findNext", "Edit", "Find Next", key(KeyEvent.VK_F3, 0), () -> withTab(t -> t.findNext(1)));
        cmd("edit.findPrev", "Edit", "Find Previous", key(KeyEvent.VK_F3, shift), () -> withTab(t -> t.findNext(-1)));
        cmd("edit.comment", "Edit", "Toggle Line Comment", key(KeyEvent.VK_SLASH, ctrl),
            () -> withTab(t -> EditorActions.toggleLineComment(t.getEditorArea())));
        cmd("edit.blockComment", "Edit", "Toggle Block Comment", key(KeyEvent.VK_A, shift | alt),
            () -> withTab(t -> EditorActions.toggleBlockComment(t.getEditorArea(), false)));
        cmd("edit.duplicate", "Edit", "Duplicate Line or Selection", key(KeyEvent.VK_D, ctrl),
            () -> withTab(t -> EditorActions.duplicate(t.getEditorArea(), true)));
        cmd("edit.moveUp", "Edit", "Move Line Up", key(KeyEvent.VK_UP, alt),
            () -> withTab(t -> EditorActions.moveLines(t.getEditorArea(), true)));
        cmd("edit.moveDown", "Edit", "Move Line Down", key(KeyEvent.VK_DOWN, alt),
            () -> withTab(t -> EditorActions.moveLines(t.getEditorArea(), false)));
        cmd("edit.deleteLine", "Edit", "Delete Line", key(KeyEvent.VK_K, ctrl | shift),
            () -> withTab(t -> EditorActions.deleteLines(t.getEditorArea())));
        cmd("edit.joinLines", "Edit", "Join Lines", key(KeyEvent.VK_J, ctrl),
            () -> withTab(t -> EditorActions.joinLines(t.getEditorArea())));
        cmd("edit.indent", "Edit", "Indent Line", key(KeyEvent.VK_CLOSE_BRACKET, ctrl),
            () -> withTab(t -> EditorActions.indentLines(t.getEditorArea(), t.getIndenter().unit(),
                                                         t.getIndenter().indentSize(), false)));
        cmd("edit.outdent", "Edit", "Outdent Line", key(KeyEvent.VK_OPEN_BRACKET, ctrl),
            () -> withTab(t -> EditorActions.indentLines(t.getEditorArea(), t.getIndenter().unit(),
                                                         t.getIndenter().indentSize(), true)));
        cmd("edit.sortAsc", "Edit", "Sort Lines Ascending", null,
            () -> withTab(t -> EditorActions.sortLines(t.getEditorArea(), false)));
        cmd("edit.sortDesc", "Edit", "Sort Lines Descending", null,
            () -> withTab(t -> EditorActions.sortLines(t.getEditorArea(), true)));
        cmd("edit.upper", "Edit", "Transform to Uppercase", null,
            () -> withTab(t -> EditorActions.transformCase(t.getEditorArea(), true)));
        cmd("edit.lower", "Edit", "Transform to Lowercase", null,
            () -> withTab(t -> EditorActions.transformCase(t.getEditorArea(), false)));
        cmd("edit.trim", "Edit", "Trim Trailing Whitespace", null, () -> withTab(t -> {
            int n = EditorActions.trimTrailingWhitespace(t.getEditorArea());
            toast(n == 0 ? "No trailing whitespace" : "Trimmed " + n + " line" + (n == 1 ? "" : "s"));
        }));
        cmd("edit.suggest", "Edit", "Trigger Suggest", key(KeyEvent.VK_SPACE, ctrl), () -> withTab(t -> {
            t.getEditorArea().requestFocusInWindow();
            Action a = t.getEditorArea().getActionMap().get("sphere-complete");
            if (a != null) {
                a.actionPerformed(new ActionEvent(t.getEditorArea(), 0, ""));
            }
        }));

        // ---- Selection
        cmd("sel.expand", "Selection", "Expand Selection", key(KeyEvent.VK_RIGHT, shift | alt),
            () -> withTab(t -> EditorActions.expandSelection(t.getEditorArea())));
        cmd("sel.shrink", "Selection", "Shrink Selection", key(KeyEvent.VK_LEFT, shift | alt),
            () -> withTab(t -> EditorActions.shrinkSelection(t.getEditorArea())));
        cmd("sel.line", "Selection", "Select Line", key(KeyEvent.VK_L, ctrl),
            () -> withTab(t -> EditorActions.selectLine(t.getEditorArea())));
        cmd("sel.copyDown", "Selection", "Copy Line Down", key(KeyEvent.VK_DOWN, shift | alt),
            () -> withTab(t -> EditorActions.duplicate(t.getEditorArea(), true)));
        cmd("sel.copyUp", "Selection", "Copy Line Up", key(KeyEvent.VK_UP, shift | alt),
            () -> withTab(t -> EditorActions.duplicate(t.getEditorArea(), false)));

        // ---- View
        cmd("view.palette", "View", "Command Palette...", key(KeyEvent.VK_P, ctrl | shift), () -> showPalette(">"));
        cmd("view.paletteF1", "View", "Show All Commands", key(KeyEvent.VK_F1, 0), () -> showPalette(">"));
        toggle("view.minimap", "View", "Toggle Minimap", null, () -> EditorState.flag(EditorState.MINIMAP, true), () -> {
            boolean on = !EditorState.flag(EditorState.MINIMAP, true);
            EditorState.setFlag(EditorState.MINIMAP, on);
            for (CodeTab t : allTabs()) {
                t.setMinimapVisible(on);
            }
        });
        toggle("view.wordWrap", "View", "Toggle Word Wrap", key(KeyEvent.VK_Z, alt),
               () -> EditorState.flag(EditorState.WORD_WRAP, false), () -> {
            boolean on = !EditorState.flag(EditorState.WORD_WRAP, false);
            EditorState.setFlag(EditorState.WORD_WRAP, on);
            for (CodeTab t : allTabs()) {
                t.setWordWrap(on);
            }
        });
        toggle("view.guides", "View", "Toggle Indent Guides", null,
               () -> EditorState.flag(EditorState.INDENT_GUIDES, true), () -> {
            boolean on = !EditorState.flag(EditorState.INDENT_GUIDES, true);
            EditorState.setFlag(EditorState.INDENT_GUIDES, on);
            for (CodeTab t : allTabs()) {
                t.getEditorArea().setIndentGuidesVisible(on);
            }
        });
        toggle("view.occurrences", "View", "Toggle Highlight of Occurrences", null,
               () -> EditorState.flag(EditorState.OCCURRENCES, true), () -> {
            boolean on = !EditorState.flag(EditorState.OCCURRENCES, true);
            EditorState.setFlag(EditorState.OCCURRENCES, on);
            for (CodeTab t : allTabs()) {
                t.getEditorArea().setOccurrencesVisible(on);
            }
        });
        boolean[] lineNumbers = { true };
        toggle("view.lineNumbers", "View", "Toggle Line Numbers", null, () -> lineNumbers[0], () -> {
            lineNumbers[0] = !lineNumbers[0];
            for (CodeTab t : allTabs()) {
                t.setLineNumbersVisible(lineNumbers[0]);
            }
        });
        cmd("view.zoomIn", "View", "Zoom In", key(KeyEvent.VK_EQUALS, ctrl), () -> zoom(1));
        cmd("view.zoomInPlus", "View", "Zoom In (numpad)", key(KeyEvent.VK_ADD, ctrl), () -> zoom(1));
        cmd("view.zoomOut", "View", "Zoom Out", key(KeyEvent.VK_MINUS, ctrl), () -> zoom(-1));
        cmd("view.zoomOutMinus", "View", "Zoom Out (numpad)", key(KeyEvent.VK_SUBTRACT, ctrl), () -> zoom(-1));
        cmd("view.zoomReset", "View", "Reset Zoom", key(KeyEvent.VK_0, ctrl), () -> zoom(0));
        cmd("view.problems", "View", "Toggle Problems Panel", key(KeyEvent.VK_M, ctrl | shift), () -> toggleBottom("problems"));
        cmd("view.preview", "View", "Toggle Markdown / LaTeX Preview", key(KeyEvent.VK_V, ctrl | shift),
            () -> withTab(CodeTab::togglePreview));

        // ---- Go
        cmd("go.symbol", "Go", "Go to Symbol in File...", key(KeyEvent.VK_O, ctrl | shift), () -> showPalette("@"));
        cmd("go.allSymbols", "Go", "Go to Symbol in Open Files...", key(KeyEvent.VK_T, ctrl), () -> showPalette("#"));
        cmd("go.line", "Go", "Go to Line/Column...", key(KeyEvent.VK_G, ctrl), () -> showPalette(":"));
        cmd("go.definition", "Go", "Go to Definition", key(KeyEvent.VK_F12, 0), () -> withTab(CodeTab::goToDefinitionAtCaret));
        cmd("go.nextProblem", "Go", "Next Problem", key(KeyEvent.VK_F8, 0), () -> nextProblem(1));
        cmd("go.prevProblem", "Go", "Previous Problem", key(KeyEvent.VK_F8, shift), () -> nextProblem(-1));
        cmd("go.nextTab", "Go", "Next Editor", key(KeyEvent.VK_TAB, ctrl), () -> cycleTab(1));
        cmd("go.prevTab", "Go", "Previous Editor", key(KeyEvent.VK_TAB, ctrl | shift), () -> cycleTab(-1));

        // ---- Run
        cmd("run.file", "Run", "Run File in Sphere Console", key(KeyEvent.VK_F5, 0), () -> withTab(this::runFile));
        cmd("run.breakpoint", "Run", "Toggle Breakpoint", key(KeyEvent.VK_F9, 0), () -> withTab(t -> {
            int line = t.getEditorArea().lineAt(t.getEditorArea().getCaretPosition()) + 1;
            t.getBreakpoints().toggle(line);
        }));
        cmd("run.debug", "Run", "Toggle Debug Panel", key(KeyEvent.VK_D, ctrl | shift), () -> toggleBottom("debug"));

        // ---- Preferences and language
        toggle("pref.autoSave", "Preferences", "Auto Save", null, () -> EditorState.flag(EditorState.AUTO_SAVE, false),
               () -> EditorState.setFlag(EditorState.AUTO_SAVE, !EditorState.flag(EditorState.AUTO_SAVE, false)));
        toggle("pref.autoComplete", "Preferences", "Suggestions While Typing", null,
               () -> EditorState.flag(EditorState.AUTO_COMPLETE, true), () -> {
            boolean on = !EditorState.flag(EditorState.AUTO_COMPLETE, true);
            EditorState.setFlag(EditorState.AUTO_COMPLETE, on);
            for (CodeTab t : allTabs()) {
                t.setAutoComplete(on);
            }
        });
        toggle("pref.autoClose", "Preferences", "Auto Close Brackets and Quotes", null,
               () -> EditorState.flag(EditorState.AUTO_CLOSE, true), () -> {
            boolean on = !EditorState.flag(EditorState.AUTO_CLOSE, true);
            EditorState.setFlag(EditorState.AUTO_CLOSE, on);
            for (CodeTab t : allTabs()) {
                t.setAutoClose(on);
            }
        });
        toggle("pref.trimOnSave", "Preferences", "Trim Trailing Whitespace on Save", null,
               () -> EditorState.flag(EditorState.TRIM_ON_SAVE, false),
               () -> EditorState.setFlag(EditorState.TRIM_ON_SAVE, !EditorState.flag(EditorState.TRIM_ON_SAVE, false)));
        toggle("pref.liveLint", "Preferences", "Live Analysis (Sphere Insight)", null,
               () -> EditorState.flag(EditorState.LIVE_LINT, true), () -> {
            boolean on = !EditorState.flag(EditorState.LIVE_LINT, true);
            EditorState.setFlag(EditorState.LIVE_LINT, on);
            for (CodeTab t : allTabs()) {
                t.setLiveLint(on);
            }
        });
        cmd("lang.change", "Preferences", "Change Language Mode...", null, this::pickLanguage);
        cmd("lang.eol", "Preferences", "Change End of Line Sequence...", null, this::pickLineEnding);
        cmd("lang.encoding", "Preferences", "Change File Encoding...", null, this::pickEncoding);
        cmd("lang.indent", "Preferences", "Change Indentation...", null, this::pickIndentation);
        cmd("help.keys", "Help", "Keyboard Shortcuts", null, () -> showPalette(">"));
    }

    /** Every command with a key is bound on the editor itself, so it works with or without menus. */
    private void installCommandKeys() {
        InputMap in = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = getActionMap();
        for (Command c : commands.values()) {
            if (c.key == null) {
                continue;
            }
            // Keys the code surface owns (typing, clipboard) stay with it.
            String id = c.id;
            if (id.equals("edit.cut") || id.equals("edit.copy") || id.equals("edit.paste")
                    || id.equals("edit.selectAll")) {
                continue;
            }
            in.put(c.key, "cmd:" + id);
            am.put("cmd:" + id, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    runCommand(c);
                }
            });
        }
    }

    private void runCommand(Command c) {
        try {
            c.action.run();
        } catch (RuntimeException ex) {
            AppLogger.error(c.label() + " failed: " + ex.getMessage());
        }
        if (c.checked != null) {
            JCheckBoxMenuItem item = toggles.get(c.id);
            if (item != null) {
                item.setSelected(c.checked.getAsBoolean());
            }
        }
    }

    private void cycleTab(int direction) {
        int n = tabbedPane.getTabCount();
        if (n > 1) {
            tabbedPane.setSelectedIndex(Math.floorMod(tabbedPane.getSelectedIndex() + direction, n));
            CodeTab t = getActiveTab();
            if (t != null) {
                t.getEditorArea().requestFocusInWindow();
            }
        }
    }

    private void nextProblem(int direction) {
        CodeTab t = getActiveTab();
        if (t == null) {
            return;
        }
        List<EditorDiagnostic> list = new ArrayList<>();
        for (EditorDiagnostic d : t.getAllDiagnostics()) {
            if (d.getSeverity() != EditorDiagnostic.Severity.INFO) {
                list.add(d);
            }
        }
        if (list.isEmpty()) {
            toast("No problems in this file");
            return;
        }
        list.sort((a, b) -> a.getLine() != b.getLine() ? Integer.compare(a.getLine(), b.getLine())
                                                        : Integer.compare(a.getColumn(), b.getColumn()));
        int line = t.getEditorArea().lineAt(t.getEditorArea().getCaretPosition()) + 1;
        EditorDiagnostic pick = null;
        if (direction > 0) {
            for (EditorDiagnostic d : list) {
                if (d.getLine() > line) {
                    pick = d;
                    break;
                }
            }
            if (pick == null) {
                pick = list.get(0);
            }
        } else {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).getLine() < line) {
                    pick = list.get(i);
                    break;
                }
            }
            if (pick == null) {
                pick = list.get(list.size() - 1);
            }
        }
        t.goToLine(pick.getLine(), pick.getColumn());
        toast(pick.getMessage());
    }

    // =======================================================================
    // Palette
    // =======================================================================

    private int[] savedCaret;

    private void installPalette() {
        palette.setOnClose(() -> {
            CodeTab t = getActiveTab();
            if (t != null) {
                t.getEditorArea().requestFocusInWindow();
            }
        });

        // Files: open editors, recent files, files next to the active one.
        palette.addMode("", new CommandPalette.Source() {
            @Override
            public List<CommandPalette.Item> items(String query) {
                List<CommandPalette.Item> out = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (CodeTab t : allTabs()) {
                    File f = t.getFile();
                    String key = f == null ? "tab:" + System.identityHashCode(t) : f.getAbsolutePath();
                    seen.add(key);
                    out.add(new CommandPalette.Item(nameOf(t), f == null ? "unsaved" : relative(f.getParentFile()),
                                                    t.isModified() ? "● open" : "open",
                                                    EditorIcons.LanguageBadge.of(t.getLanguage(), f), t, 60)
                                .group("open"));
                }
                for (File f : EditorState.recentFiles()) {
                    if (seen.add(f.getAbsolutePath())) {
                        out.add(fileItem(f, "recent", 30).group("recent"));
                    }
                }
                if (!query.isEmpty()) {
                    for (File f : nearbyFiles()) {
                        if (seen.add(f.getAbsolutePath())) {
                            out.add(fileItem(f, "", 0).group("files"));
                        }
                    }
                }
                return out;
            }

            @Override
            public void accept(CommandPalette.Item item, String query) {
                if (item == null) {
                    return;
                }
                if (item.payload instanceof CodeTab t) {
                    selectTab(t);
                    t.getEditorArea().requestFocusInWindow();
                } else if (item.payload instanceof File f) {
                    openFile(f);
                }
            }

            @Override
            public String empty(String query) {
                return query.isEmpty() ? "Open editors and recent files appear here" : "No file matches '" + query + "'";
            }
        });

        // Commands.
        palette.addMode(">", new CommandPalette.Source() {
            @Override
            public List<CommandPalette.Item> items(String query) {
                List<CommandPalette.Item> out = new ArrayList<>();
                List<String> recent = EditorState.recentCommands();
                for (Command c : commands.values()) {
                    if (c.id.equals("view.zoomInPlus") || c.id.equals("view.zoomOutMinus") || c.id.equals("view.paletteF1")
                            || c.id.equals("help.keys")) {
                        continue;
                    }
                    int r = recent.indexOf(c.id);
                    String hint = c.key == null ? "" : "key:" + keyText(c.key);
                    String label = c.label() + (c.checked != null ? (c.checked.getAsBoolean() ? "  ✓" : "") : "");
                    out.add(new CommandPalette.Item(label, "", hint, null, c, r < 0 ? 0 : 80 - r * 4)
                                .group(r >= 0 ? "recent" : "all"));
                }
                if (query.isEmpty()) {
                    out.sort((a, b) -> a.bias != b.bias ? Integer.compare(b.bias, a.bias)
                                                         : a.label.compareToIgnoreCase(b.label));
                }
                return out;
            }

            @Override
            public void accept(CommandPalette.Item item, String query) {
                if (item != null && item.payload instanceof Command c) {
                    EditorState.addRecentCommand(c.id);
                    SwingUtilities.invokeLater(() -> runCommand(c));
                }
            }

            @Override
            public String empty(String query) {
                return "No command matches '" + query + "'";
            }
        });

        // Symbols of the active file, with a live preview.
        palette.addMode("@", new CommandPalette.Source() {
            @Override
            public List<CommandPalette.Item> items(String query) {
                CodeTab t = getActiveTab();
                List<CommandPalette.Item> out = new ArrayList<>();
                if (t == null) {
                    return out;
                }
                if (savedCaret == null) {
                    savedCaret = new int[] { t.getEditorArea().getCaretPosition(),
                                             t.getEditorArea().getVisibleRect().y };
                }
                for (DocSymbol s : t.getInsight().result().symbols) {
                    String indent = query.isEmpty() ? "  ".repeat(Math.min(4, s.depth)) : "";
                    out.add(new CommandPalette.Item(indent + s.describe(),
                                                    s.kind.label + (s.container != null ? " in " + s.container : ""),
                                                    "Ln " + (s.line + 1),
                                                    new EditorIcons.KindIcon(EditorIcons.Kind.of(s.kind), 15), s, 0));
                }
                return out;
            }

            @Override
            public void preview(CommandPalette.Item item) {
                CodeTab t = getActiveTab();
                if (t != null && item.payload instanceof DocSymbol s) {
                    t.goToLine(s.line + 1, 0);
                }
            }

            @Override
            public void cancel() {
                CodeTab t = getActiveTab();
                if (t != null && savedCaret != null) {
                    t.getEditorArea().setCaretPosition(Math.min(savedCaret[0], t.getEditorArea().getDocument().getLength()));
                    Rectangle vis = t.getEditorArea().getVisibleRect();
                    t.getEditorArea().scrollRectToVisible(new Rectangle(vis.x, savedCaret[1], 1, vis.height));
                }
                savedCaret = null;
            }

            @Override
            public void accept(CommandPalette.Item item, String query) {
                savedCaret = null;
                CodeTab t = getActiveTab();
                if (t != null && item != null && item.payload instanceof DocSymbol s) {
                    t.reveal(s);
                }
            }

            @Override
            public boolean ordered() {
                return false;
            }

            @Override
            public String empty(String query) {
                return "No symbols found in this file";
            }
        });

        // Symbols of every open file.
        palette.addMode("#", new CommandPalette.Source() {
            @Override
            public List<CommandPalette.Item> items(String query) {
                List<CommandPalette.Item> out = new ArrayList<>();
                for (CodeTab t : allTabs()) {
                    for (DocSymbol s : t.getInsight().result().symbols) {
                        if (s.kind == DocSymbol.Kind.VARIABLE || s.kind == DocSymbol.Kind.PROPERTY) {
                            continue;
                        }
                        out.add(new CommandPalette.Item(s.describe(), nameOf(t), "Ln " + (s.line + 1),
                                                        new EditorIcons.KindIcon(EditorIcons.Kind.of(s.kind), 15),
                                                        new Object[] { t, s }, 0));
                    }
                }
                return out;
            }

            @Override
            public void accept(CommandPalette.Item item, String query) {
                if (item != null && item.payload instanceof Object[] pair) {
                    CodeTab t = (CodeTab) pair[0];
                    selectTab(t);
                    t.reveal((DocSymbol) pair[1]);
                }
            }

            @Override
            public String empty(String query) {
                return "No symbol matches '" + query + "' in the open files";
            }
        });

        // Go to line[:column].
        palette.addMode(":", new CommandPalette.Source() {
            @Override
            public List<CommandPalette.Item> items(String query) {
                CodeTab t = getActiveTab();
                List<CommandPalette.Item> out = new ArrayList<>();
                if (t == null) {
                    return out;
                }
                int lines = t.getEditorArea().lineCountTotal();
                int[] target = parseLine(query);
                if (target == null) {
                    int line = t.getEditorArea().lineAt(t.getEditorArea().getCaretPosition()) + 1;
                    out.add(new CommandPalette.Item("Current line: " + line + ". Type a line number between 1 and "
                                                    + lines + " (line:column also works)", "", "", null, null, 0));
                } else {
                    int line = Math.max(1, Math.min(lines, target[0]));
                    out.add(new CommandPalette.Item("Go to line " + line + (target[1] > 0 ? ", column " + target[1] : ""),
                                                    "", "Enter", null, target, 0));
                    t.goToLine(line, target[1]);
                }
                return out;
            }

            @Override
            public void accept(CommandPalette.Item item, String query) {
                CodeTab t = getActiveTab();
                int[] target = parseLine(query);
                if (t != null && target != null) {
                    t.goToLine(target[0], target[1]);
                }
            }

            @Override
            public boolean ordered() {
                return true;
            }
        });

        // Help.
        palette.addMode("?", CommandPalette.of(q -> List.of(
                new CommandPalette.Item("…", "Go to a file by name", "Ctrl+P", EditorIcons.file(15, EditorTheme::textMuted), "", 0),
                new CommandPalette.Item(">", "Run a command", "Ctrl+Shift+P", EditorIcons.bolt(15, EditorTheme::textMuted), ">", 0),
                new CommandPalette.Item("@", "Go to a symbol of this file", "Ctrl+Shift+O",
                                        new EditorIcons.KindIcon(EditorIcons.Kind.FUNCTION, 15), "@", 0),
                new CommandPalette.Item("#", "Go to a symbol of any open file", "Ctrl+T",
                                        new EditorIcons.KindIcon(EditorIcons.Kind.CLASS, 15), "#", 0),
                new CommandPalette.Item(":", "Go to a line", "Ctrl+G", null, ":", 0)),
                item -> SwingUtilities.invokeLater(() -> showPalette((String) item.payload)), true));
    }

    private static int[] parseLine(String query) {
        String q = query.trim();
        if (q.isEmpty()) {
            return null;
        }
        String[] parts = q.split("[:,\\s]+");
        try {
            int line = Integer.parseInt(parts[0]);
            int col = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return new int[] { line, col };
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private CommandPalette.Item fileItem(File f, String hint, int bias) {
        LanguageSpec spec = LanguageSpec.forFile(f);
        return new CommandPalette.Item(f.getName(), relative(f.getParentFile()), hint,
                                       EditorIcons.LanguageBadge.of(spec, f), f, bias);
    }

    private static String relative(File dir) {
        if (dir == null) {
            return "";
        }
        File root = new File(".").getAbsoluteFile().getParentFile();
        String p = dir.getAbsolutePath();
        if (root != null && p.startsWith(root.getAbsolutePath())) {
            String r = p.substring(root.getAbsolutePath().length());
            return r.startsWith(File.separator) ? r.substring(1) : (r.isEmpty() ? root.getName() : r);
        }
        return p;
    }

    /** Files near the active one and in the workspace, bounded so the box stays instant. */
    private List<File> nearbyFiles() {
        List<File> out = new ArrayList<>();
        java.util.Set<File> roots = new java.util.LinkedHashSet<>();
        CodeTab t = getActiveTab();
        if (t != null && t.getFile() != null) {
            File dir = t.getFile().getAbsoluteFile().getParentFile();
            if (dir != null) {
                roots.add(dir);
                if (dir.getParentFile() != null) {
                    roots.add(dir.getParentFile());
                }
            }
        }
        roots.add(new File("WorkSpace").getAbsoluteFile());
        roots.add(new File("snippets").getAbsoluteFile());
        com.sphere.core.CommandRouter router = com.sphere.core.CommandRouter.active();
        if (router != null && router.getCurrentDirectory() != null) {
            roots.add(router.getCurrentDirectory().toFile().getAbsoluteFile());
        }
        for (File root : roots) {
            collect(root, 0, out);
            if (out.size() > 3000) {
                break;
            }
        }
        return out;
    }

    private static void collect(File dir, int depth, List<File> out) {
        if (dir == null || !dir.isDirectory() || depth > 3 || out.size() > 3000) {
            return;
        }
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File f : entries) {
            String n = f.getName();
            if (n.startsWith(".") || n.equals("bin") || n.equals("build") || n.equals("node_modules")
                    || n.equals("__pycache__") || n.equals("target")) {
                continue;
            }
            if (f.isDirectory()) {
                collect(f, depth + 1, out);
            } else if (f.length() < EditorFileIOLimit.MAX) {
                out.add(f);
            }
            if (out.size() > 3000) {
                return;
            }
        }
    }

    /** Files above this size are not offered by Go to File. */
    private static final class EditorFileIOLimit {
        static final long MAX = 64L * 1024 * 1024;
    }

    private static String keyText(KeyStroke ks) {
        StringBuilder sb = new StringBuilder();
        int m = ks.getModifiers();
        if ((m & InputEvent.CTRL_DOWN_MASK) != 0) sb.append("Ctrl+");
        if ((m & InputEvent.SHIFT_DOWN_MASK) != 0) sb.append("Shift+");
        if ((m & InputEvent.ALT_DOWN_MASK) != 0) sb.append("Alt+");
        String k = KeyEvent.getKeyText(ks.getKeyCode());
        switch (ks.getKeyCode()) {
            case KeyEvent.VK_SLASH: k = "/"; break;
            case KeyEvent.VK_OPEN_BRACKET: k = "["; break;
            case KeyEvent.VK_CLOSE_BRACKET: k = "]"; break;
            case KeyEvent.VK_EQUALS: k = "="; break;
            case KeyEvent.VK_MINUS: k = "-"; break;
            case KeyEvent.VK_UP: k = "Up"; break;
            case KeyEvent.VK_DOWN: k = "Down"; break;
            case KeyEvent.VK_LEFT: k = "Left"; break;
            case KeyEvent.VK_RIGHT: k = "Right"; break;
            default: break;
        }
        return sb.append(k).toString();
    }

    // ---- Pickers -----------------------------------------------------------

    private void pickLanguage() {
        CodeTab t = getActiveTab();
        if (t == null) {
            return;
        }
        palette.pick("Select Language Mode", CommandPalette.of(q -> {
            List<CommandPalette.Item> out = new ArrayList<>();
            for (LanguageSpec spec : LanguageSpec.all()) {
                boolean current = spec == t.getLanguage();
                out.add(new CommandPalette.Item(spec.name, current ? "current" : "", spec.tag,
                                                EditorIcons.LanguageBadge.of(spec, null), spec, current ? 50 : 0));
            }
            return out;
        }, item -> {
            t.setLanguage((LanguageSpec) item.payload);
            refreshTabHeaders();
            updateStatusBar();
        }, false));
    }

    private void pickLineEnding() {
        CodeTab t = getActiveTab();
        if (t == null) {
            return;
        }
        palette.pick("Select End of Line Sequence", CommandPalette.of(q -> List.of(
                new CommandPalette.Item("LF", "Unix, macOS, WSL", t.getLineSeparator().equals("\n") ? "current" : "",
                                        null, "\n", 0),
                new CommandPalette.Item("CRLF", "Windows", t.getLineSeparator().equals("\r\n") ? "current" : "",
                                        null, "\r\n", 0)),
                item -> { t.setLineSeparator((String) item.payload); updateStatusBar(); }, true));
    }

    private void pickEncoding() {
        CodeTab t = getActiveTab();
        if (t == null) {
            return;
        }
        String[] names = { "UTF-8", "ISO-8859-1", "windows-1252", "UTF-16", "US-ASCII" };
        palette.pick("Reopen or save with an encoding", CommandPalette.of(q -> {
            List<CommandPalette.Item> out = new ArrayList<>();
            for (String n : names) {
                out.add(new CommandPalette.Item("Reopen with " + n, "reads the file again", "", null,
                                                new Object[] { "reopen", n }, 0).group("reopen"));
            }
            for (String n : names) {
                out.add(new CommandPalette.Item("Save with " + n, "writes it on the next save", "", null,
                                                new Object[] { "save", n }, 0).group("save"));
            }
            return out;
        }, item -> {
            Object[] p = (Object[]) item.payload;
            Charset cs = Charset.forName((String) p[1]);
            if ("reopen".equals(p[0])) {
                if (t.isModified()) {
                    toast("Save or undo your changes before reopening");
                    return;
                }
                t.reopenWithCharset(cs);
            } else {
                t.setCharset(cs);
            }
            updateStatusBar();
        }, true));
    }

    private void pickIndentation() {
        CodeTab t = getActiveTab();
        if (t == null) {
            return;
        }
        palette.pick("Select Indentation", CommandPalette.of(q -> List.of(
                new CommandPalette.Item("Indent Using Spaces: 2", "", "", null, new Object[] { false, 2, false }, 0),
                new CommandPalette.Item("Indent Using Spaces: 4", "", "", null, new Object[] { false, 4, false }, 0),
                new CommandPalette.Item("Indent Using Spaces: 8", "", "", null, new Object[] { false, 8, false }, 0),
                new CommandPalette.Item("Indent Using Tabs", "", "", null, new Object[] { true, 4, false }, 0),
                new CommandPalette.Item("Convert Indentation to Spaces", "rewrites the file's indentation", "",
                                        null, new Object[] { false, t.getIndenter().indentSize(), true }, 0),
                new CommandPalette.Item("Convert Indentation to Tabs", "rewrites the file's indentation", "",
                                        null, new Object[] { true, t.getIndenter().indentSize(), true }, 0)),
                item -> {
                    Object[] p = (Object[]) item.payload;
                    t.setIndentation((Boolean) p[0], (Integer) p[1], (Boolean) p[2]);
                    updateStatusBar();
                }, true));
    }

    // =======================================================================
    // Bottom panel: problems and debug
    // =======================================================================

    private void buildBottomPanel() {
        JPanel header = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(EditorTheme.chrome());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(EditorTheme.chromeBorder());
                g.fillRect(0, 0, getWidth(), 1);
            }
        };
        header.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 6));
        JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tabs.setOpaque(false);
        for (String[] t : new String[][] { { "problems", "PROBLEMS" }, { "debug", "DEBUG" } }) {
            EditorWidgets.FlatToggle b = new EditorWidgets.FlatToggle(t[1], null);
            b.setFont(FontLoader.getGlobalFont(Font.BOLD, 11));
            b.addActionListener(e -> showBottom(t[0]));
            bottomTabs.put(t[0], b);
            tabs.add(b);
        }
        EditorWidgets.FlatButton close = new EditorWidgets.FlatButton(EditorIcons.close(14, EditorTheme::foreground),
                                                                      "Close Panel");
        close.addActionListener(e -> hideBottom());
        header.add(tabs, BorderLayout.WEST);
        header.add(close, BorderLayout.EAST);

        bottomContent.add(problemsPanel, "problems");
        bottomContent.add(debugPanel, "debug");
        bottomPanel.add(header, BorderLayout.NORTH);
        bottomPanel.add(bottomContent, BorderLayout.CENTER);
    }

    private void showBottom(String card) {
        bottomShown = card;
        bottomCards.show(bottomContent, card);
        for (Map.Entry<String, EditorWidgets.FlatToggle> e : bottomTabs.entrySet()) {
            e.getValue().setSelected(e.getKey().equals(card));
        }
        if (!bottomPanel.isVisible()) {
            bottomPanel.setVisible(true);
            debugSplit.setDividerSize(4);
            int h = getHeight() > 0 ? getHeight() : 600;
            debugSplit.setDividerLocation(Math.max(120, h - 250));
        }
        if (card.equals("problems")) {
            problemsPanel.update(allTabs(), getActiveTab());
        }
        if (debugPanelItem != null) {
            debugPanelItem.setSelected(card.equals("debug"));
        }
        debugSplit.revalidate();
    }

    private void hideBottom() {
        bottomPanel.setVisible(false);
        bottomShown = null;
        if (debugSplit != null) {
            debugSplit.setDividerSize(0);
            debugSplit.setDividerLocation(1.0);
            debugSplit.revalidate();
        }
        if (debugPanelItem != null) {
            debugPanelItem.setSelected(false);
        }
    }

    private void toggleBottom(String card) {
        if (bottomPanel.isVisible() && card.equals(bottomShown)) {
            hideBottom();
        } else {
            showBottom(card);
        }
    }

    // =======================================================================
    // Menus
    // =======================================================================

    private JMenuItem menuItem(String id) {
        Command c = commands.get(id);
        if (c == null) {
            return new JMenuItem(id);
        }
        JMenuItem item;
        if (c.checked != null) {
            JCheckBoxMenuItem box = new JCheckBoxMenuItem(c.title, c.checked.getAsBoolean());
            toggles.put(id, box);
            item = box;
        } else {
            item = new JMenuItem(c.title);
        }
        item.setForeground(theme().getTextPrimary());
        item.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        if (c.key != null) {
            item.setAccelerator(c.key);
        }
        item.addActionListener(e -> runCommand(c));
        return item;
    }

    private JMenu menu(String title, String... ids) {
        JMenu m = new JMenu(title);
        m.setForeground(theme().getTextPrimary());
        m.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        for (String id : ids) {
            if (id.equals("-")) {
                m.addSeparator();
            } else {
                m.add(menuItem(id));
            }
        }
        return m;
    }

    /* Sets up the menu bar for the given frame. */
    public void setupMenu(JFrame frame) {
        JMenuBar menuBar = new JMenuBar();
        menuBar.setBackground(theme().getBackgroundSurface());
        menuBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, theme().getBackgroundMain()));

        JMenu fileMenu = menu("File", "file.new", "file.open", "file.quickOpen");
        recentMenu = new JMenu("Open Recent");
        recentMenu.setForeground(theme().getTextPrimary());
        recentMenu.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        fileMenu.add(recentMenu);
        rebuildRecentMenu();
        fileMenu.addSeparator();
        for (String id : new String[] { "file.save", "file.saveAs", "file.saveAll" }) {
            fileMenu.add(menuItem(id));
        }
        fileMenu.add(menuItem("pref.autoSave"));
        fileMenu.addSeparator();
        for (String id : new String[] { "file.close", "file.closeOthers", "file.closeAll", "file.reopen" }) {
            fileMenu.add(menuItem(id));
        }
        fileMenu.addSeparator();
        fileMenu.add(menuItem("file.copyPath"));
        fileMenu.add(menuItem("file.reveal"));
        fileMenu.addSeparator();
        fileMenu.add(menuItem("file.quit"));

        JMenu editMenu = menu("Edit", "edit.undo", "edit.redo", "-", "edit.cut", "edit.copy", "edit.paste",
                              "-", "edit.find", "edit.replace", "edit.findNext", "edit.findPrev",
                              "-", "edit.comment", "edit.blockComment", "-", "edit.suggest");
        editMenu.addSeparator();
        JMenu modeSubMenu = new JMenu("Text Mode");
        modeSubMenu.setForeground(theme().getTextPrimary());
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
        editMenu.add(menuItem("lang.change"));

        JMenu selectionMenu = menu("Selection", "edit.selectAll", "sel.expand", "sel.shrink", "sel.line",
                                   "-", "sel.copyUp", "sel.copyDown", "edit.moveUp", "edit.moveDown", "edit.duplicate",
                                   "-", "edit.deleteLine", "edit.joinLines", "edit.indent", "edit.outdent",
                                   "-", "edit.sortAsc", "edit.sortDesc", "edit.upper", "edit.lower", "edit.trim");

        JMenu viewMenu = menu("View", "view.palette", "-", "view.minimap", "view.wordWrap", "view.guides",
                              "view.occurrences", "view.lineNumbers", "-", "view.zoomIn", "view.zoomOut",
                              "view.zoomReset", "-", "view.problems", "view.preview");
        debugPanelItem = new JCheckBoxMenuItem("Show Debug Panel", false);
        debugPanelItem.setForeground(theme().getTextPrimary());
        debugPanelItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        debugPanelItem.addActionListener(e -> setDebugPanelVisible(debugPanelItem.isSelected()));
        viewMenu.add(debugPanelItem);

        alwaysOnTopItem = new JCheckBoxMenuItem("Always on Top", false);
        alwaysOnTopItem.setForeground(theme().getTextPrimary());
        alwaysOnTopItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        alwaysOnTopItem.addActionListener(e -> {
            if (frame != null) frame.setAlwaysOnTop(alwaysOnTopItem.isSelected());
        });
        viewMenu.add(alwaysOnTopItem);

        JCheckBoxMenuItem syncTextItem = new JCheckBoxMenuItem("Sync Preview Scrolling", true);
        syncTextItem.setForeground(theme().getTextPrimary());
        syncTextItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        syncTextItem.addActionListener(e -> {
            for (CodeTab t : allTabs()) {
                t.setSyncScroll(syncTextItem.isSelected());
            }
        });
        viewMenu.add(syncTextItem);

        JMenu goMenu = menu("Go", "file.quickOpen", "go.symbol", "go.allSymbols", "go.line", "-",
                            "go.definition", "go.nextProblem", "go.prevProblem", "-", "go.nextTab", "go.prevTab");
        JMenu runMenu = menu("Run", "run.file", "run.breakpoint", "run.debug");
        JMenu prefMenu = menu("Preferences", "pref.autoComplete", "pref.autoClose", "pref.trimOnSave",
                              "pref.liveLint", "-", "lang.change", "lang.indent", "lang.encoding", "lang.eol");

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(selectionMenu);
        menuBar.add(viewMenu);
        menuBar.add(goMenu);
        menuBar.add(runMenu);
        menuBar.add(prefMenu);

        frame.setJMenuBar(menuBar);
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
            openFile(f);
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

    /**
     * Gets the active file resource being handled by this editor viewport.
     * @return The open File reference context, or null if unassigned.
     */
    public File getCurrentFile() {
        return this.currentFile;
    }

    // =======================================================================
    // Notifications
    // =======================================================================

    /** A short message in the bottom right corner that fades away by itself. */
    private static final class Toast extends JComponent {
        private String text = "";
        private float alpha;
        private final Timer hold;
        private final Timer fade;

        Toast() {
            setVisible(false);
            setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
            hold = new Timer(2600, e -> startFade());
            hold.setRepeats(false);
            fade = new Timer(30, e -> {
                alpha -= 0.08f;
                if (alpha <= 0f) {
                    alpha = 0f;
                    ((Timer) e.getSource()).stop();
                    setVisible(false);
                }
                repaint();
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    startFade();
                }
            });
        }

        void show(String message) {
            text = message == null ? "" : message;
            alpha = 1f;
            fade.stop();
            setVisible(true);
            if (getParent() != null) {
                getParent().revalidate();
            }
            repaint();
            hold.restart();
        }

        private void startFade() {
            hold.stop();
            fade.start();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(Math.min(520, fm.stringWidth(text) + 54), 38);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
            int w = getWidth();
            int h = getHeight();
            g2.setColor(EditorTheme.shadow());
            g2.fill(new RoundRectangle2D.Float(2, 3, w - 3, h - 3, 10, 10));
            g2.setColor(EditorTheme.widgetBackground());
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 3, h - 4, 10, 10));
            g2.setColor(EditorTheme.widgetBorder());
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 4, h - 5, 10, 10));
            g2.setColor(EditorTheme.focus());
            g2.fill(new RoundRectangle2D.Float(10, (h - 4) / 2f - 4, 8, 8, 8, 8));
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(EditorTheme.foreground());
            String shown = text;
            while (shown.length() > 3 && fm.stringWidth(shown) > w - 44) {
                shown = shown.substring(0, shown.length() - 2) + "…";
            }
            g2.drawString(shown, 28, (h - 4 + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    // =======================================================================
    // Debugging
    // =======================================================================

    /** Shows or hides the debug panel without disturbing the editor's height. */
    public void setDebugPanelVisible(boolean visible) {
        if (debugSplit == null) {
            return;
        }
        if (visible) {
            showBottom("debug");
        } else if ("debug".equals(bottomShown)) {
            hideBottom();
        }
        if (debugPanelItem != null) {
            debugPanelItem.setSelected(visible);
        }
    }

    /** Ends any live session, for when the editor window closes. */
    public void shutdownDebugger() {
        if (debugPanel != null) {
            debugPanel.shutdown();
        }
        diskWatch.stop();
    }

    /** Follows the breakpoints of a tab so a live session stays in step with them. */
    void watchBreakpoints(CodeTab tab) {
        if (tab == null || debugPanel == null) {
            return;
        }
        tab.getBreakpoints().addListener(model -> {
            if (debugPanel.isSessionLive()) {
                for (int line : model.lines()) {
                    debugPanel.breakpointChanged(line, model.isEnabled(line));
                }
            }
        });
    }

    private final class EditorDebugHost implements DebugPanel.Host {

        @Override
        public File activeFile() {
            CodeTab tab = getActiveTab();
            return tab == null ? null : tab.getFile();
        }

        @Override
        public BreakpointModel activeBreakpoints() {
            CodeTab tab = getActiveTab();
            return tab == null ? new BreakpointModel() : tab.getBreakpoints();
        }

        @Override
        public void showExecutionLine(File file, int line) {
            for (int i = 0; i < tabbedPane.getTabCount(); i++) {
                Component c = tabbedPane.getComponentAt(i);
                if (c instanceof CodeTab tab) {
                    File tabFile = tab.getFile();
                    boolean same = file != null && tabFile != null
                        && tabFile.getAbsolutePath().equals(file.getAbsolutePath());
                    tab.setExecutionLine(same ? line : -1);
                    if (same && line > 0) {
                        tabbedPane.setSelectedIndex(i);
                    }
                }
            }
        }
    }
}

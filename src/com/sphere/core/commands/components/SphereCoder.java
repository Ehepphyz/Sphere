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
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.sphere.components.editor.CodeInsight;
import com.sphere.components.editor.CoderConfig;
import com.sphere.components.editor.CodeTab;
import com.sphere.components.editor.CommandPalette;
import com.sphere.components.editor.CompanionFiles;
import com.sphere.components.editor.DocSymbol;
import com.sphere.components.editor.EditorActions;
import com.sphere.components.editor.EditorDiagnostic;
import com.sphere.components.editor.EditorGrid;
import com.sphere.components.editor.EditorIcons;
import com.sphere.components.editor.EditorState;
import com.sphere.components.editor.EditorStatusBar;
import com.sphere.components.editor.EditorWidgets;
import com.sphere.components.editor.ExplorerPanel;
import com.sphere.components.editor.LanguageSpec;
import com.sphere.components.editor.ProblemsPanel;
import com.sphere.components.editor.TextBuffer;
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
 * SphereCoder, Sphere's code editor. Files open as tabs (CodeTab), colored by
 * their language, in split groups laid out on a grid: split right or down from
 * the icons at the end of every tab strip (Ctrl+\, Ctrl+K V / Ctrl+K H), drag a
 * tab onto the edge of a group to split there or onto another strip to move it,
 * keep the same file open in two groups to read one place while writing
 * another. The layout and the open files come back with the next session.
 * A group can also move into a window of its own, for a second screen: that
 * window is a SphereCoder too, sharing the main one's buffers, and closing it
 * docks its editors back.
 *
 * Around the groups sit the command palette (Ctrl+Shift+P, Ctrl+P,
 * Ctrl+Shift+O, Ctrl+G), the status bar, the Problems and Debug panels, and
 * notifications. At the far left, the explorer (Ctrl+B) shows the folder
 * worked in, WorkSpace/ or another, kept in config/config.spherecoder.
 * Public API kept for compatibility.
 */
public class SphereCoder extends JPanel implements CodeTab.Host, CodeTab.ZoomHost {

    /* Global editor mode enum (used by menu, applied to active tab only) */
    public enum EditorMode { PLAIN_TEXT, MARKDOWN, LATEX }
    private EditorMode currentMode = EditorMode.PLAIN_TEXT;

    // Transformed to follow strict Open-only isolation scope architecture
    private FlatFileChooser openFileChooser;

    /* UI optional items */
    private final EditorStatusBar status = new EditorStatusBar();
    private final Map<String, JCheckBoxMenuItem> toggles = new HashMap<>();
    private JCheckBoxMenuItem alwaysOnTopItem;

    /* Split groups: the grid lays them out, one of them has the keyboard. */
    private static final int STRIP_HEIGHT = 39;
    private final EditorGrid grid;
    private EditorGroup activeGroup;
    private final Deque<EditorGroup> groupHistory = new ArrayDeque<>();
    private final DropIndicator dropIndicator = new DropIndicator();
    private boolean lockedScrolling;
    private boolean syncingScroll;
    private final Set<TextBuffer> watchedBuffers = Collections.newSetFromMap(new WeakHashMap<>());

    /*
     * Windows: a group moved out gets a window of its own, run by a SphereCoder
     * of its own (its keys, palette, status bar) whose tabs share the main
     * editor's buffers. The main editor knows its windows; each window knows
     * its main editor and gives its tabs back to it when it closes.
     */
    private final SphereCoder main;
    private final List<SphereCoder> windows = new ArrayList<>();
    private SphereCoder lastUsedWindow;
    private boolean docking;

    /* Tabs */
    private File currentFile;
    private final Deque<File> closedFiles = new ArrayDeque<>();
    private int untitledCounter = 0;

    /* Session: the layout and its files, brought back on the next start. */
    private boolean persistSession;
    private boolean restorePending;
    private final Timer sessionSaver;

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

    /* Explorer: WorkSpace/ and the folders beside it, at the far left of the main editor; a group's window uses the main one's. */
    private final ExplorerPanel explorer;
    private JSplitPane explorerSplit;

    /* Palette, notifications, commands */
    private final CommandPalette palette = new CommandPalette();
    private final Toast toast = new Toast();
    private final EditorWidgets.OverlayPane overlay;
    private final Map<String, Command> commands = new LinkedHashMap<>();
    private final ChordKeys chords = new ChordKeys();
    private final Timer diskWatch;
    private final Timer problemsRefresh;

    public SphereCoder() {
        this(null);
    }

    /**
     * @param main null for an editor of its own; else the editor whose group
     *             this one shows in a window of its own. It starts without a
     *             tab: the group's tabs move in.
     */
    private SphereCoder(SphereCoder main) {
        this.main = main;

        setLayout(new BorderLayout());
        setBackground(EditorTheme.background());
        problemsRefresh = new Timer(250, e -> problemsPanel.update(distinctTabs(), getActiveTab()));
        problemsRefresh.setRepeats(false);
        sessionSaver = new Timer(1200, e -> saveSession());
        sessionSaver.setRepeats(false);

        CodeTab.setWatermark(List.of(
                new String[] { "Show All Commands", "Ctrl+Shift+P" },
                new String[] { "Go to File", "Ctrl+P" },
                new String[] { "Go to Symbol", "Ctrl+Shift+O" },
                new String[] { "Split Editor Right", "Ctrl+\\" },
                new String[] { "Open File", "Ctrl+O" },
                new String[] { "Run in Sphere", "F5" }));

        /* Main UI: a grid of split groups, starting with one */
        EditorGroup first = new EditorGroup();
        activeGroup = first;
        groupHistory.push(first);
        grid = new EditorGrid(first);
        grid.setOnLayoutChanged(this::layoutChanged);

        // One debugger per editor: a window of a group asks the main editor's.
        debugPanel = main == null ? new DebugPanel(new EditorDebugHost(), settings) : null;
        buildBottomPanel();
        debugSplit = new EditorWidgets.QuietSplitPane(JSplitPane.VERTICAL_SPLIT, grid, bottomPanel);
        debugSplit.setResizeWeight(1.0);
        debugSplit.setBorder(null);
        debugSplit.setDividerSize(4);
        debugSplit.setContinuousLayout(true);

        overlay = new EditorWidgets.OverlayPane(debugSplit);
        overlay.addOverlay(palette, EditorWidgets.Anchor.TOP_CENTER, 20, 34);
        overlay.addOverlay(toast, EditorWidgets.Anchor.BOTTOM_RIGHT, 18, 14);
        overlay.add(dropIndicator, JLayeredPane.DRAG_LAYER);
        if (main == null) {
            explorer = new ExplorerPanel(new EditorExplorerHost());
            // The editors keep room to work in however wide the explorer is dragged.
            JPanel editors = new JPanel(new BorderLayout()) {
                @Override
                public Dimension getMinimumSize() {
                    return new Dimension(240, 0);
                }
            };
            editors.setOpaque(false);
            editors.add(overlay, BorderLayout.CENTER);
            explorerSplit = new EditorWidgets.FlatSplitPane(JSplitPane.HORIZONTAL_SPLIT, explorer, editors);
            explorerSplit.setResizeWeight(0.0);
            explorerSplit.setBorder(null);
            explorerSplit.setContinuousLayout(true);
            explorerSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                // Only a width the user dragged to is written; the one set at startup is already there.
                int width = explorerSplit.getDividerLocation();
                if (explorer.isVisible() && width >= 120 && width != explorerWidth()) {
                    CoderConfig.put("explorer", "width", width);
                }
            });
            applyExplorerVisible(CoderConfig.flag("explorer", "visible", true));
            add(explorerSplit, BorderLayout.CENTER);
        } else {
            explorer = null;
            add(overlay, BorderLayout.CENTER);
        }
        add(status, BorderLayout.SOUTH);

        problemsPanel.setNavigator(this::navigateTo);

        buildCommands();
        installCommandKeys();
        installPalette();
        installStatusBar();
        hideBottom();

        /* Create initial empty tab */
        if (main == null) {
            addNewTab(first, null);
        }

        // A file changed by a formatter or a git checkout shows up without a restart.
        diskWatch = new Timer(2000, e -> {
            if (!isShowing()) {
                return;
            }
            // Every group's visible file, not only the focused one.
            for (EditorGroup g : groups()) {
                CodeTab t = g.selected();
                if (t != null) {
                    t.checkDisk();
                }
            }
        });
        diskWatch.start();

        /* Window close handling and custom application branding icon initialization */
        SwingUtilities.invokeLater(() -> {
            Window parentWindow = SwingUtilities.getWindowAncestor(SphereCoder.this);
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
                        root().lastUsedWindow = SphereCoder.this;
                        for (CodeTab t : distinctTabs()) {
                            t.checkDisk();
                        }
                    }
                });
            }
        });
    }

    // =======================================================================
    // Split groups
    // =======================================================================

    /**
     * One split group: a strip of tabs over the code of the selected one, and
     * at the end of the strip the group's own actions: split right, split
     * down, maximize (with two groups or more), and the group menu.
     */
    private final class EditorGroup extends JPanel {
        final JTabbedPane tabs;
        private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 1, 0));
        private final EditorWidgets.FlatButton maximizeButton;

        EditorGroup() {
            super(new BorderLayout());
            setOpaque(true);
            setBackground(EditorTheme.chrome());

            tabs = new JTabbedPane() {
                // A theme switch reinstalls every UI; the language-colored tabs must survive it.
                @Override
                public void updateUI() {
                    setUI(new EditorTabsUI(EditorGroup.this));
                    setBackground(EditorTheme.chrome());
                }

                // The strip leaves room on its right for the group's actions
                // (the border's right inset); the code below takes the full width.
                @Override
                public void doLayout() {
                    super.doLayout();
                    int r = getInsets().right;
                    if (r <= 0) {
                        return;
                    }
                    for (Component c : getComponents()) {
                        if (c instanceof CodeTab) {
                            c.setBounds(c.getX(), c.getY(), c.getWidth() + r, c.getHeight());
                        } else if (c instanceof JComponent jc && jc.getClientProperty("sphere.scroll") != null
                                   && c.isVisible()) {
                            c.setLocation(c.getX() - r, c.getY());
                        }
                    }
                }
            };
            tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            tabs.setForeground(EditorTheme.foreground());
            tabs.setFocusable(false);
            tabs.addChangeListener(e -> selectionChanged(this));
            tabs.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    setActiveGroup(EditorGroup.this);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    // A double click on the empty strip opens a new file, as in VS Code.
                    if (e.getClickCount() == 2 && tabs.indexAtLocation(e.getX(), e.getY()) < 0
                            && e.getY() < STRIP_HEIGHT) {
                        addNewTab(EditorGroup.this, null);
                    }
                }
            });

            EditorWidgets.FlatButton right = new EditorWidgets.FlatButton(
                    EditorIcons.splitRight(16, EditorTheme::textMuted),
                    "<html>Split Editor Right <font color='" + hex(EditorTheme.textFaint()) + "'>(vertical split)</font>"
                    + "<br>Ctrl+\\  ·  Ctrl+K V  ·  Alt+click: split down</html>");
            right.addActionListener(e -> {
                setActiveGroup(this);
                splitGroup(this, (e.getModifiers() & ActionEvent.ALT_MASK) != 0
                                 ? EditorGrid.Side.BOTTOM : EditorGrid.Side.RIGHT);
            });
            EditorWidgets.FlatButton down = new EditorWidgets.FlatButton(
                    EditorIcons.splitDown(16, EditorTheme::textMuted),
                    "<html>Split Editor Down <font color='" + hex(EditorTheme.textFaint()) + "'>(horizontal split)</font>"
                    + "<br>Ctrl+K H  ·  Ctrl+K Ctrl+\\</html>");
            down.addActionListener(e -> {
                setActiveGroup(this);
                splitGroup(this, EditorGrid.Side.BOTTOM);
            });
            maximizeButton = new EditorWidgets.FlatButton(EditorIcons.maximize(16, EditorTheme::textMuted, false),
                                                          "Maximize Group (Ctrl+K M)");
            maximizeButton.addActionListener(e -> {
                setActiveGroup(this);
                toggleMaximize(this);
            });
            EditorWidgets.FlatButton more = new EditorWidgets.FlatButton(EditorIcons.more(16, EditorTheme::textMuted),
                                                                         "Group Actions and Layouts");
            more.addActionListener(e -> showGroupMenu(this, more));
            actions.setOpaque(false);
            actions.add(right);
            actions.add(down);
            actions.add(maximizeButton);
            actions.add(more);

            EditorWidgets.OverlayPane layer = new EditorWidgets.OverlayPane(tabs);
            layer.addOverlay(actions, EditorWidgets.Anchor.TOP_RIGHT, 6, 10);
            add(layer, BorderLayout.CENTER);
            refreshActions();
        }

        /** The editor this group belongs to: the main one, or a window of its own. */
        SphereCoder coder() {
            return SphereCoder.this;
        }

        List<CodeTab> views() {
            List<CodeTab> out = new ArrayList<>();
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (tabs.getComponentAt(i) instanceof CodeTab t) {
                    out.add(t);
                }
            }
            return out;
        }

        CodeTab selected() {
            return tabs.getSelectedComponent() instanceof CodeTab t ? t : null;
        }

        /** This group's tab on a buffer, or null. */
        CodeTab viewOf(TextBuffer buffer) {
            for (CodeTab t : views()) {
                if (t.getBuffer() == buffer) {
                    return t;
                }
            }
            return null;
        }

        /** This group's tab on a file, or null. */
        CodeTab viewOf(File file) {
            if (file == null) {
                return null;
            }
            String wanted = file.getAbsolutePath();
            for (CodeTab t : views()) {
                if (t.getFile() != null && t.getFile().getAbsolutePath().equals(wanted)) {
                    return t;
                }
            }
            return null;
        }

        /** The maximize button shows only when there is something to fold away. */
        void refreshActions() {
            boolean several = grid != null && grid.groupCount() > 1;
            boolean max = grid != null && grid.isMaximized();
            maximizeButton.setVisible(several);
            maximizeButton.setIcon(EditorIcons.maximize(16, EditorTheme::textMuted, max));
            maximizeButton.setToolTipText(max ? "Restore Groups (Ctrl+K M)" : "Maximize Group (Ctrl+K M)");
            int room = actions.getPreferredSize().width + 10;
            Insets in = tabs.getInsets();
            if (in.right != room) {
                tabs.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, room));
            }
            actions.revalidate();
            tabs.revalidate();
            tabs.repaint();
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, 0);
        }
    }

    /** The groups in reading order. */
    private List<EditorGroup> groups() {
        List<EditorGroup> out = new ArrayList<>();
        for (JComponent c : grid.leaves()) {
            out.add((EditorGroup) c);
        }
        return out;
    }

    /** The groups, the most recently active first. */
    private List<EditorGroup> groupsByRecency() {
        List<EditorGroup> out = new ArrayList<>();
        for (EditorGroup g : groupHistory) {
            if (grid.hasGroup(g)) {
                out.add(g);
            }
        }
        for (EditorGroup g : groups()) {
            if (!out.contains(g)) {
                out.add(g);
            }
        }
        return out;
    }

    /** The group showing a tab, in this window or another one ({@link EditorGroup#coder()} says which). */
    private static EditorGroup groupOf(CodeTab tab) {
        return tab == null ? null : (EditorGroup) SwingUtilities.getAncestorOfClass(EditorGroup.class, tab);
    }

    private boolean isShownInGroup(CodeTab tab) {
        EditorGroup g = groupOf(tab);
        return g != null && g.tabs.getSelectedComponent() == tab;
    }

    /** The group the keyboard works in: its tab drives the status bar, the palette and the commands. */
    private void setActiveGroup(EditorGroup g) {
        if (g == null || g == activeGroup || !grid.hasGroup(g)) {
            return;
        }
        EditorGroup old = activeGroup;
        activeGroup = g;
        groupHistory.remove(g);
        groupHistory.push(g);
        CodeTab t = g.selected();
        currentFile = t == null ? null : t.getFile();
        if (old != null) {
            refreshTabHeaders(old);
        }
        refreshTabHeaders(g);
        updateStatusBar();
        followInExplorer();
        problemsRefresh.restart();
    }

    private void selectionChanged(EditorGroup g) {
        CodeTab active = g.selected();
        if (g == activeGroup) {
            currentFile = active == null ? null : active.getFile();
            updateStatusBar();
            followInExplorer();
        }
        refreshTabHeaders(g);
        if (active != null) {
            active.checkDisk();
        }
        problemsRefresh.restart();
        scheduleSessionSave();
    }

    private void focus(CodeTab tab) {
        if (tab != null) {
            SwingUtilities.invokeLater(() -> tab.getEditorArea().requestFocusInWindow());
        }
    }

    /** An empty group put on one side of another. The caller gives it its first tab. */
    private EditorGroup newGroupBeside(EditorGroup g, EditorGrid.Side side) {
        EditorGroup fresh = new EditorGroup();
        grid.split(g, fresh, side);
        return fresh;
    }

    /**
     * Splits a group: a new one beside it shows the same file, at the same
     * place, sharing its buffer, so both sides edit one text.
     */
    private void splitGroup(EditorGroup g, EditorGrid.Side side) {
        if (g == null) {
            return;
        }
        splitFrom(g, side, g.selected());
    }

    /** A new group on one side of {@code at}, showing a second view of {@code source}. */
    private void splitFrom(EditorGroup at, EditorGrid.Side side, CodeTab source) {
        EditorGroup fresh = newGroupBeside(at, side);
        CodeTab view;
        if (source != null) {
            view = twinOf(source);
            addTab(fresh, view, -1, true);
            view.matchScrollOf(source);
        } else {
            view = addNewTab(fresh, null);
        }
        setActiveGroup(fresh);
        focus(view);
    }

    /** The split on the side with the most room: columns while there is width for them. */
    private void splitSmart() {
        splitGroup(activeGroup, grid.roomiestSide(activeGroup));
    }

    /**
     * The group beside this one for "to the side": the one on its right, else
     * any neighbour, else a new group on the right (when {@code create}).
     */
    private EditorGroup sideGroup(EditorGroup from, boolean create) {
        for (EditorGrid.Side s : new EditorGrid.Side[] { EditorGrid.Side.RIGHT, EditorGrid.Side.LEFT,
                                                         EditorGrid.Side.BOTTOM, EditorGrid.Side.TOP }) {
            JComponent n = grid.neighbor(from, s);
            if (n instanceof EditorGroup g) {
                return g;
            }
        }
        for (EditorGroup g : groupsByRecency()) {
            if (g != from) {
                return g;
            }
        }
        return create ? newGroupBeside(from, EditorGrid.Side.RIGHT) : null;
    }

    private void toggleMaximize(EditorGroup g) {
        if (grid.groupCount() < 2) {
            toast("Split the editor first: there is only one group");
            return;
        }
        grid.toggleMaximized(g);
        refreshAllGroupActions();
        focus(g.selected());
    }

    /** Ctrl+1..9: group N, or a new group on the right when N is one past the last. */
    private void focusGroup(int number) {
        List<EditorGroup> gs = groups();
        if (number <= gs.size()) {
            EditorGroup g = gs.get(number - 1);
            if (grid.isMaximized() && grid.maximizedLeaf() != g) {
                grid.restore();
                refreshAllGroupActions();
            }
            setActiveGroup(g);
            focus(g.selected());
        } else if (number == gs.size() + 1) {
            splitFrom(gs.get(gs.size() - 1), EditorGrid.Side.RIGHT, getActiveTab());
        }
    }

    private void focusDirection(EditorGrid.Side side) {
        JComponent n = grid.neighbor(activeGroup, side);
        if (n instanceof EditorGroup g) {
            setActiveGroup(g);
            focus(g.selected());
        } else {
            toast("No group " + side.name().toLowerCase() + " of this one");
        }
    }

    private void focusCycle(int direction) {
        List<EditorGroup> gs = groups();
        if (gs.size() > 1) {
            EditorGroup g = gs.get(Math.floorMod(gs.indexOf(activeGroup) + direction, gs.size()));
            setActiveGroup(g);
            focus(g.selected());
        }
    }

    /** Ctrl+Alt+Right / Left: the active editor into the next group, a new one if there is none. */
    private void moveToAdjacentGroup(CodeTab tab, int direction) {
        EditorGroup from = groupOf(tab);
        if (from == null) {
            return;
        }
        List<EditorGroup> gs = groups();
        int i = gs.indexOf(from) + direction;
        EditorGroup target;
        if (i >= 0 && i < gs.size()) {
            target = gs.get(i);
        } else if (from.tabs.getTabCount() > 1) {
            target = newGroupBeside(from, direction > 0 ? EditorGrid.Side.RIGHT : EditorGrid.Side.LEFT);
        } else {
            toast("This is the group's only editor; split instead (Ctrl+\\)");
            return;
        }
        moveTab(tab, target, -1, false);
    }

    /**
     * Moves a tab into another group (or a copy of it, sharing the buffer, when
     * {@code copy}), of this window or of another one. A group that already
     * shows the file just brings it forward. A group left without tabs closes.
     */
    private void moveTab(CodeTab tab, EditorGroup target, int index, boolean copy) {
        EditorGroup source = groupOf(tab);
        if (source == null || target == null) {
            return;
        }
        if (source.coder() != this) {
            source.coder().moveTab(tab, target, index, copy);
            return;
        }
        SphereCoder dest = target.coder();
        if (source == target) {
            int from = source.tabs.indexOfComponent(tab);
            int to = index < 0 ? source.tabs.getTabCount() - 1 : Math.min(index, source.tabs.getTabCount() - 1);
            reorder(source, from, to);
            return;
        }
        CodeTab existing = target.viewOf(tab.getBuffer());
        CodeTab shown;
        if (copy) {
            shown = existing != null ? existing : twinOf(tab);
            if (existing == null) {
                dest.addTab(target, shown, index, true);
                shown.matchScrollOf(tab);
            }
        } else {
            int at = source.tabs.indexOfComponent(tab);
            source.tabs.removeTabAt(at);
            if (existing != null) {
                tab.dispose();
                shown = existing;
            } else {
                // The tab itself goes, caret, scroll and preview with it.
                dest.addTab(target, tab, index, true);
                shown = tab;
            }
            if (source.tabs.getTabCount() == 0) {
                if (grid.groupCount() > 1) {
                    closeGroupNow(source);
                } else if (main == null) {
                    addNewTab(source, null);
                } else if (!docking) {
                    // A window's last editor left: the window goes (docking closes it itself).
                    closeWindow();
                }
            }
        }
        target.tabs.setSelectedComponent(shown);
        dest.setActiveGroup(target);
        refreshTabHeaders();
        if (dest != this) {
            dest.refreshTabHeaders();
        }
        focus(shown);
        scheduleSessionSave();
    }

    /** Takes an empty group off the grid; the most recently used one takes the keyboard. */
    private void closeGroupNow(EditorGroup g) {
        if (!grid.hasGroup(g) || grid.groupCount() < 2) {
            return;
        }
        for (CodeTab t : g.views()) {
            g.tabs.remove(t);
            t.dispose();
        }
        grid.removeGroup(g);
        groupHistory.remove(g);
        if (activeGroup == g) {
            activeGroup = null;
            EditorGroup next = groupsByRecency().get(0);
            setActiveGroup(next);
            focus(next.selected());
        }
        refreshAllGroupActions();
        scheduleSessionSave();
    }

    /** Closes the editors of a group, asking about unsaved ones; the group goes with its last tab. */
    private void closeGroup(EditorGroup g) {
        if (g == null) {
            return;
        }
        for (CodeTab t : g.views()) {
            if (!closeTab(t)) {
                return;
            }
        }
    }

    /** Every group's editors into one group, the active one; second views of a file fold into one. */
    private void joinAllGroups() {
        EditorGroup target = activeGroup;
        CodeTab keep = target.selected();
        for (EditorGroup g : groups()) {
            if (g == target) {
                continue;
            }
            for (CodeTab t : g.views()) {
                if (grid.hasGroup(g)) {
                    moveTab(t, target, -1, false);
                }
            }
        }
        if (keep != null && !keep.isDisposed()) {
            selectTab(keep);
            focus(keep);
        }
    }

    /** The active group's editors into the next group in reading order. */
    private void joinWithNext() {
        List<EditorGroup> gs = groups();
        if (gs.size() < 2) {
            return;
        }
        EditorGroup from = activeGroup;
        EditorGroup into = gs.get((gs.indexOf(from) + 1) % gs.size());
        for (CodeTab t : from.views()) {
            if (grid.hasGroup(from)) {
                moveTab(t, into, -1, false);
            }
        }
    }

    /**
     * A layout from the menu: the open files are dealt across the new groups
     * (the active one first, in the first group); a group left without a file
     * gets a second view of the active one. Second views fold into one first.
     */
    private void applyLayout(String spec) {
        CodeTab active = getActiveTab();
        List<CodeTab> keep = new ArrayList<>();
        List<CodeTab> drop = new ArrayList<>();
        Set<TextBuffer> seen = new HashSet<>();
        if (active != null) {
            keep.add(active);
            seen.add(active.getBuffer());
        }
        for (CodeTab t : allTabs()) {
            if (t == active) {
                continue;
            }
            if (seen.add(t.getBuffer())) {
                keep.add(t);
            } else {
                drop.add(t);
            }
        }
        for (EditorGroup g : groups()) {
            g.tabs.removeAll();
        }
        for (CodeTab t : drop) {
            t.dispose();
        }
        List<JComponent> made = grid.rebuild(spec, EditorGroup::new);
        if (made == null) {
            made = grid.rebuild("G", EditorGroup::new);
        }
        List<EditorGroup> fresh = new ArrayList<>();
        for (JComponent c : made) {
            fresh.add((EditorGroup) c);
        }
        groupHistory.clear();
        activeGroup = fresh.get(0);
        groupHistory.push(activeGroup);
        for (int i = 0; i < keep.size(); i++) {
            addTab(fresh.get(i % fresh.size()), keep.get(i), -1, i < fresh.size());
        }
        CodeTab model = active != null ? active : keep.isEmpty() ? null : keep.get(0);
        for (int g = keep.size(); g < fresh.size(); g++) {
            if (model != null) {
                CodeTab twin = twinOf(model);
                addTab(fresh.get(g), twin, -1, true);
                twin.matchScrollOf(model);
            } else {
                addNewTab(fresh.get(g), null);
            }
        }
        if (fresh.get(0).tabs.getTabCount() == 0) {
            addNewTab(fresh.get(0), null);
        }
        if (active != null) {
            fresh.get(0).tabs.setSelectedComponent(active);
        }
        refreshAllGroupActions();
        refreshTabHeaders();
        updateStatusBar();
        focus(getActiveTab());
        scheduleSessionSave();
    }

    private void refreshAllGroupActions() {
        for (EditorGroup g : groups()) {
            g.refreshActions();
        }
        updateStatusBar();
    }

    /** The grid told of a split, a close, a dragged divider. */
    private void layoutChanged() {
        if (grid == null) {
            return;
        }
        refreshAllGroupActions();
        scheduleSessionSave();
    }

    private void showGroupMenu(EditorGroup g, JComponent anchor) {
        setActiveGroup(g);
        JPopupMenu menu = new JPopupMenu();
        menu.add(popupItem("view.splitRight"));
        menu.add(popupItem("view.splitDown"));
        if (grid.groupCount() > 1) {
            JMenuItem max = popupItem("view.maximizeGroup");
            max.setText(grid.isMaximized() ? "Restore Groups" : "Maximize Group");
            menu.add(max);
            menu.add(popupItem("view.evenGroups"));
            menu.add(popupItem("view.flipLayout"));
            menu.add(popupItem("view.lockScroll"));
        }
        menu.addSeparator();
        menu.add(layoutMenu());
        addWindowItems(menu);
        menu.addSeparator();
        File companion = g.selected() == null ? null : CompanionFiles.find(g.selected().getFile());
        if (companion != null) {
            menu.add(item("Open " + companion.getName() + " to the Side", () -> openFileToSide(companion), null));
            menu.addSeparator();
        }
        if (grid.groupCount() > 1) {
            menu.add(popupItem("view.joinNext"));
            menu.add(popupItem("view.joinAll"));
            menu.addSeparator();
        }
        menu.add(item("Close Saved in Group", () -> closeSaved(g), null));
        menu.add(popupItem("view.closeGroup"));
        menu.show(anchor, 0, anchor.getHeight());
    }

    private JMenu layoutMenu() {
        JMenu layouts = new JMenu("Layout");
        layouts.setIcon(EditorIcons.grid(14, EditorTheme::textMuted));
        for (String id : new String[] { "layout.single", "layout.twoColumns", "layout.threeColumns",
                                        "layout.twoRows", "layout.grid", "layout.mainSide" }) {
            layouts.add(popupItem(id));
        }
        return layouts;
    }

    private void showLayoutMenu(JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(popupItem("view.splitRight"));
        menu.add(popupItem("view.splitDown"));
        menu.add(popupItem("view.splitSmart"));
        if (grid.groupCount() > 1) {
            menu.addSeparator();
            for (int i = 1; i <= Math.min(9, grid.groupCount()); i++) {
                JMenuItem it = popupItem("view.focusGroup" + i);
                if (groups().get(i - 1) == activeGroup) {
                    it.setFont(it.getFont().deriveFont(Font.BOLD));
                }
                menu.add(it);
            }
            menu.addSeparator();
            menu.add(popupItem("view.maximizeGroup"));
            menu.add(popupItem("view.evenGroups"));
            menu.add(popupItem("view.lockScroll"));
            menu.add(popupItem("view.joinAll"));
        }
        menu.addSeparator();
        addWindowItems(menu);
        menu.add(layoutMenu());
        Dimension d = menu.getPreferredSize();
        menu.show(anchor, 0, -d.height);
    }

    /** Move Group into New Window, and Dock Back when there is a window to dock. */
    private void addWindowItems(JPopupMenu menu) {
        if (main == null || grid.groupCount() > 1) {
            menu.add(popupItem("view.moveGroupToWindow"));
        }
        if (main != null || !windows.isEmpty()) {
            menu.add(popupItem("view.dockWindow"));
        }
    }

    // =======================================================================
    // Windows: a group of its own, on another screen
    // =======================================================================

    /** The main editor: this one, or the one whose group this window shows. */
    private SphereCoder root() {
        return main == null ? this : main;
    }

    /** The main editor and its windows, the main one first. */
    private List<SphereCoder> family() {
        SphereCoder r = root();
        List<SphereCoder> out = new ArrayList<>();
        out.add(r);
        out.addAll(r.windows);
        return out;
    }

    /** Every tab of every window. */
    private List<CodeTab> familyTabs() {
        List<CodeTab> out = new ArrayList<>();
        for (SphereCoder c : family()) {
            out.addAll(c.allTabs());
        }
        return out;
    }

    /** One tab per open file across the windows, this window's own choices first. */
    private List<CodeTab> familyDistinctTabs() {
        List<CodeTab> out = new ArrayList<>(distinctTabs());
        Set<TextBuffer> seen = new HashSet<>();
        for (CodeTab t : out) {
            seen.add(t.getBuffer());
        }
        for (SphereCoder c : family()) {
            if (c != this) {
                for (CodeTab t : c.distinctTabs()) {
                    if (seen.add(t.getBuffer())) {
                        out.add(t);
                    }
                }
            }
        }
        return out;
    }

    /** A tab on that file in this window (the most recently used group first), else in another one. */
    private CodeTab viewAnywhere(File file) {
        CodeTab t = findTabForFile(file);
        if (t != null || file == null) {
            return t;
        }
        for (SphereCoder c : family()) {
            if (c != this && (t = c.findTabForFile(file)) != null) {
                return t;
            }
        }
        return null;
    }

    /** The window worked in last: the one the public accessors speak of ("the editor's file"). */
    private SphereCoder lastUsed() {
        if (main != null) {
            return this;
        }
        return lastUsedWindow != null && windows.contains(lastUsedWindow) ? lastUsedWindow : this;
    }

    /**
     * Moves a group into a window of its own, to put it on another screen. Its
     * tabs go as they are (caret, scroll, history); a file also open here stays
     * one text in both windows. Closing the window docks the tabs back. The
     * main window's only group leaves an empty editor behind.
     */
    private void moveGroupToNewWindow(EditorGroup g) {
        if (g == null || !grid.hasGroup(g)) {
            return;
        }
        if (main != null && grid.groupCount() < 2) {
            toast("This group already has a window of its own");
            return;
        }
        CodeTab shown = g.selected();
        SphereCoder window = root().openWindow(windowBoundsFor(g));
        EditorGroup into = window.activeGroup;
        for (CodeTab t : g.views()) {
            moveTab(t, into, -1, false);
        }
        if (shown != null && !shown.isDisposed()) {
            into.tabs.setSelectedComponent(shown);
        }
        SwingUtilities.getWindowAncestor(window).setVisible(true);
        window.bringToFront();
        window.focus(shown);
    }

    /** Over the group, a little down and right, on the screen it is on. */
    private Rectangle windowBoundsFor(EditorGroup g) {
        Window own = SwingUtilities.getWindowAncestor(this);
        GraphicsConfiguration gc = own != null ? own.getGraphicsConfiguration()
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
        Rectangle screen = gc.getBounds();
        Insets in = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        screen = new Rectangle(screen.x + in.left, screen.y + in.top,
                               screen.width - in.left - in.right, screen.height - in.top - in.bottom);
        Rectangle r;
        if (g.isShowing()) {
            Point p = g.getLocationOnScreen();
            // Room around the group for the window's title, menus and status bar.
            r = new Rectangle(p.x + 32, p.y + 32, g.getWidth() + 16, g.getHeight() + 110);
        } else {
            r = new Rectangle(screen.x + (screen.width - 900) / 2, screen.y + (screen.height - 640) / 2, 900, 640);
        }
        r.width = Math.min(screen.width, Math.max(640, r.width));
        r.height = Math.min(screen.height, Math.max(420, r.height));
        r.x = Math.max(screen.x, Math.min(r.x, screen.x + screen.width - r.width));
        r.y = Math.max(screen.y, Math.min(r.y, screen.y + screen.height - r.height));
        return r;
    }

    /** A new window of this (main) editor, still empty: the group's tabs move in next. */
    private SphereCoder openWindow(Rectangle bounds) {
        SphereCoder window = new SphereCoder(this);
        JFrame frame = new JFrame("SphereCoder");
        // Closing it docks its editors back (handleExit), it never just goes.
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(window, BorderLayout.CENTER);
        window.setupMenu(frame);
        frame.setMinimumSize(new Dimension(480, 320));
        frame.setBounds(bounds);
        windows.add(window);
        return window;
    }

    /**
     * Closes this window by handing its editors back to the main one, into its
     * active group, where a file it already shows folds into that view. No
     * editor closes, so nothing is asked and no text can be lost.
     */
    private void dockBack() {
        if (main == null || docking) {
            return;
        }
        docking = true;
        EditorGroup into = main.activeGroup;
        CodeTab shown = getActiveTab();
        TextBuffer shownBuffer = shown == null ? null : shown.getBuffer();
        for (EditorGroup g : groups()) {
            for (CodeTab t : g.views()) {
                boolean pristine = t.getFile() == null && !t.isModified() && !t.getBuffer().isShared()
                                   && t.getEditorArea().getDocument().getLength() == 0;
                if (pristine) {
                    g.tabs.remove(t);
                    t.dispose();
                } else {
                    moveTab(t, into, -1, false);
                }
            }
        }
        closeWindow();
        CodeTab back = shownBuffer == null ? null : into.viewOf(shownBuffer);
        if (back != null) {
            main.selectTab(back);
        }
        main.bringToFront();
        main.focus(back != null ? back : main.getActiveTab());
    }

    /** Every window of the main editor docked back, on request or when it closes. */
    private void dockAllWindows() {
        for (SphereCoder w : new ArrayList<>(root().windows)) {
            w.dockBack();
        }
    }

    /** Takes this window away once its editors are gone: its timers, the frame, its place in the main editor. */
    private void closeWindow() {
        for (CodeTab t : allTabs()) {
            t.dispose();
        }
        diskWatch.stop();
        problemsRefresh.stop();
        sessionSaver.stop();
        chords.stop("");
        palette.close(true);
        main.windows.remove(this);
        if (main.lastUsedWindow == this) {
            main.lastUsedWindow = null;
        }
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w != null) {
            w.dispose();
        }
        main.scheduleSessionSave();
    }

    /** Raises this editor's window, out of the taskbar if need be; a closed one stays closed. */
    private void bringToFront() {
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w == null || !w.isVisible()) {
            return;
        }
        if (w instanceof Frame f && (f.getExtendedState() & Frame.ICONIFIED) != 0) {
            f.setExtendedState(f.getExtendedState() & ~Frame.ICONIFIED);
        }
        w.toFront();
    }

    // =======================================================================
    // Tabs
    // =======================================================================

    /** Tab strip: rounded tabs outlined in their language's color, the active one joined to the code. */
    private final class EditorTabsUI extends BasicTabbedPaneUI {
        private final EditorGroup group;

        EditorTabsUI(EditorGroup group) {
            this.group = group;
        }

        /** The strip of the group with the keyboard marks its tab fully; the others are quieter. */
        private boolean groupActive() {
            return group == activeGroup || grid == null || grid.groupCount() < 2;
        }

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
            boolean strong = selected && groupActive();
            g2.setClip(x, y, w, h);
            g2.setStroke(new BasicStroke(strong ? 1.4f : 1f));
            g2.setColor(strong ? lang : EditorTheme.alpha(lang, selected ? 150 : 95));
            g2.draw(new RoundRectangle2D.Float(x + 1.5f, y + 1.5f, w - 3, h + 10, 9, 9));
            if (selected) {
                // A bar of the language's color on top, the active tab's mark;
                // faint in a group that does not have the keyboard.
                g2.setClip(null);
                g2.setColor(strong ? lang : EditorTheme.alpha(lang, 110));
                g2.fill(new RoundRectangle2D.Float(x + 2, y + 1, w - 4, strong ? 3 : 2, 3, 3));
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
            StripScrollButton b = new StripScrollButton(
                    east ? EditorIcons.chevron(14, EditorTheme::foreground, false)
                         : new FlippedIcon(EditorIcons.chevron(14, EditorTheme::foreground, false)),
                    east ? "Scroll tabs right" : "Scroll tabs left");
            b.setPreferredSize(new Dimension(22, 22));
            // Moved left of the group's actions when the strip lays out.
            b.putClientProperty("sphere.scroll", Boolean.TRUE);
            return b;
        }
    }

    /**
     * A scroll arrow of a tab strip. It has to be a UIResource: JTabbedPane.add
     * turns anything else into a tab, and the arrows used to become two empty
     * tabs that never scrolled anything.
     */
    private static final class StripScrollButton extends EditorWidgets.FlatButton
            implements javax.swing.plaf.UIResource {
        StripScrollButton(Icon icon, String tooltip) {
            super(icon, tooltip);
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
     * Pushes findings onto every tab showing that file, so a compilation
     * underlines its errors in the source rather than only listing them in the
     * console, in each split showing it, whichever window it is in.
     */
    public void showDiagnostics(File file, java.util.List<com.sphere.components.editor.EditorDiagnostic> diagnostics) {
        if (file == null) {
            return;
        }
        String wanted = file.getAbsolutePath();
        for (CodeTab t : familyTabs()) {
            if (t.getFile() != null && t.getFile().getAbsolutePath().equals(wanted)) {
                t.setDiagnostics(diagnostics);
            }
        }
    }

    /** A tab holding that file (in the most recently used group first), or null when it is not open. */
    public CodeTab findTabForFile(File file) {
        if (file == null) {
            return null;
        }
        for (EditorGroup g : groupsByRecency()) {
            CodeTab t = g.viewOf(file);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /* Returns the active CodeTab (the selected tab of the active group), or null if none. */
    private CodeTab getActiveTab() {
        return activeGroup == null ? null : activeGroup.selected();
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

    /* Adds a new tab to the active group, optionally bound to an existing file. */
    private void addNewTab(File file) {
        addNewTab(activeGroup, file);
    }

    private CodeTab addNewTab(EditorGroup group, File file) {
        CodeTab tab = newTabFor(file);
        addTab(group, tab, -1, true);
        focus(tab);
        return tab;
    }

    private CodeTab newTabFor(File file) {
        // A file open in another window is not read again: one text, one history.
        CodeTab open = file == null ? null : viewAnywhere(file);
        if (open != null) {
            return twinOf(open);
        }
        CodeTab tab = new CodeTab(file);
        String tabTitle = file != null ? file.getName() : "Untitled-" + (++root().untitledCounter);
        tab.putClientProperty("sphere.untitled", tabTitle);
        tab.setUntitledName(tabTitle);
        return tab;
    }

    /** A second view of a tab's buffer, for another group. */
    private CodeTab twinOf(CodeTab source) {
        CodeTab twin = new CodeTab(source);
        Object name = source.getClientProperty("sphere.untitled");
        twin.putClientProperty("sphere.untitled", name);
        twin.setUntitledName(name == null ? "Untitled" : name.toString());
        return twin;
    }

    /** Puts a tab in a group's strip, at an index (or the end), wired to this editor. */
    private void addTab(EditorGroup group, CodeTab tab, int index, boolean select) {
        tab.setHost(this);
        watchBreakpoints(tab);
        wire(tab);
        int at = index < 0 || index > group.tabs.getTabCount() ? group.tabs.getTabCount() : index;
        group.tabs.insertTab(nameOf(tab), null, tab, null, at);
        group.tabs.setTabComponentAt(at, new TabHeader(tab));
        if (select || group.tabs.getTabCount() == 1) {
            group.tabs.setSelectedComponent(tab);
        }
        refreshTabHeaders();
        scheduleSessionSave();
    }

    /**
     * What a tab needs once whichever group it lands in: focus picks its group,
     * scrolling may be locked. Static, the listeners asking the tab's group for
     * the editor it is in now: a tab moves between windows, and must neither
     * report to nor keep alive the one it came from.
     */
    private static void wire(CodeTab tab) {
        if (tab.getClientProperty("sphere.wired") != null) {
            return;
        }
        tab.putClientProperty("sphere.wired", Boolean.TRUE);
        tab.getEditorArea().addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                EditorGroup g = groupOf(tab);
                if (g != null) {
                    g.coder().setActiveGroup(g);
                }
            }
        });
        JScrollBar bar = tab.getScrollPane().getVerticalScrollBar();
        bar.addAdjustmentListener(new AdjustmentListener() {
            private int last = bar.getValue();

            @Override
            public void adjustmentValueChanged(AdjustmentEvent e) {
                int now = e.getValue();
                int delta = now - last;
                last = now;
                EditorGroup g = groupOf(tab);
                SphereCoder owner = g == null ? null : g.coder();
                if (owner == null || !owner.lockedScrolling || owner.syncingScroll || delta == 0) {
                    return;
                }
                if (g != owner.activeGroup || g.selected() != tab) {
                    return;
                }
                owner.syncingScroll = true;
                try {
                    for (EditorGroup other : owner.groups()) {
                        CodeTab t = other.selected();
                        if (other != g && t != null) {
                            JScrollBar b = t.getScrollPane().getVerticalScrollBar();
                            b.setValue(b.getValue() + delta);
                        }
                    }
                } finally {
                    owner.syncingScroll = false;
                }
            }
        });
    }

    /* Updates the tab title based on file name and modified state. */
    private void updateTabTitle(CodeTab tab) {
        EditorGroup g = groupOf(tab);
        if (g == null) return;
        int index = g.tabs.indexOfComponent(tab);
        if (index < 0) return;
        Component header = g.tabs.getTabComponentAt(index);
        if (header instanceof TabHeader th) {
            th.refresh();
        } else {
            File f = tab.getFile();
            String base = (f != null) ? f.getName() : "Untitled";
            g.tabs.setTitleAt(index, tab.isModified() ? "*" + base : base);
        }
        if (tab == getActiveTab()) {
            updateFrameTitle();
        }
    }

    private void refreshTabHeaders() {
        for (EditorGroup g : groups()) {
            refreshTabHeaders(g);
        }
    }

    private void refreshTabHeaders(EditorGroup g) {
        for (int i = 0; i < g.tabs.getTabCount(); i++) {
            Component header = g.tabs.getTabComponentAt(i);
            if (header instanceof TabHeader th) {
                th.refresh();
            }
        }
        g.tabs.repaint();
    }

    private String nameOf(CodeTab tab) {
        if (tab.getFile() != null) {
            return tab.getFile().getName();
        }
        Object n = tab.getClientProperty("sphere.untitled");
        return n == null ? "Untitled" : n.toString();
    }

    private void reorder(EditorGroup g, int from, int to) {
        JTabbedPane tabs = g.tabs;
        if (from == to || from < 0 || to < 0 || from >= tabs.getTabCount() || to >= tabs.getTabCount()) {
            return;
        }
        Component comp = tabs.getComponentAt(from);
        Component header = tabs.getTabComponentAt(from);
        String title = tabs.getTitleAt(from);
        String tip = tabs.getToolTipTextAt(from);
        tabs.removeTabAt(from);
        tabs.insertTab(title, null, comp, tip, to);
        tabs.setTabComponentAt(to, header);
        tabs.setSelectedIndex(to);
        scheduleSessionSave();
    }

    /**
     * Header of one tab: the language badge, the name (with its folder when two
     * open files share it), and a close button that shows a dot while the file
     * has unsaved changes. Dragged along its strip it reorders; dragged off it,
     * onto another strip or the edge of a group, it moves there or splits.
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
                        selectTab(tab);
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
                    if (dragging && dropIndicator.isActive()) {
                        DropIndicator.Target t = dropIndicator.target();
                        dropIndicator.clear();
                        dragging = false;
                        setCursor(Cursor.getDefaultCursor());
                        drop(tab, t, e.isControlDown());
                        return;
                    }
                    dropIndicator.clear();
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
                    if (!dragging && pressAt.distance(e.getPoint()) > 8) {
                        dragging = true;
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    }
                    if (!dragging) {
                        return;
                    }
                    EditorGroup own = groupOf(tab);
                    if (own == null) {
                        return;
                    }
                    Point p = SwingUtilities.convertPoint(TabHeader.this, e.getPoint(), own.tabs);
                    boolean onOwnStrip = p.y >= -4 && p.y < STRIP_HEIGHT && p.x >= 0 && p.x < own.tabs.getWidth();
                    if (onOwnStrip) {
                        // Along its own strip a tab only changes place.
                        dropIndicator.clear();
                        int target = own.tabs.indexAtLocation(p.x, Math.max(8, Math.min(p.y, 20)));
                        int from = own.tabs.indexOfComponent(tab);
                        if (target >= 0 && target != from) {
                            reorder(own, from, target);
                        }
                    } else {
                        dropIndicator.track(e.getLocationOnScreen(), tab, e.isControlDown());
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
                // Another file of that name somewhere else (not a second view of this one).
                Set<String> folders = new HashSet<>();
                for (CodeTab t : allTabs()) {
                    if (t.getFile() != null && t.getFile().getName().equals(name)) {
                        File p = t.getFile().getAbsoluteFile().getParentFile();
                        folders.add(p == null ? "" : p.getAbsolutePath());
                    }
                }
                File parent = tab.getFile().getAbsoluteFile().getParentFile();
                if (folders.size() > 1 && parent != null) {
                    suffix = parent.getName();
                }
            }
            boolean shown = isShownInGroup(tab);
            boolean groupActive = groupOf(tab) == activeGroup || grid.groupCount() < 2;
            Color fg = shown ? (groupActive ? EditorTheme.foreground()
                                            : EditorTheme.mix(EditorTheme.foreground(), EditorTheme.textMuted(), 0.5f))
                             : EditorTheme.textMuted();
            if (suffix.isEmpty()) {
                title.setText(name);
            } else {
                title.setText("<html>" + escape(name) + " <font color='" + hex(EditorTheme.textFaint()) + "'>"
                              + escape(suffix) + "</font></html>");
            }
            title.setFont(FontLoader.getGlobalFont(tab.getFile() == null ? Font.ITALIC : Font.PLAIN, 13));
            title.setForeground(fg);
            String tip = tab.getFile() != null ? tab.getFile().getAbsolutePath() : name + " (not saved yet)";
            int views = tab.getBuffer().views().size();
            setToolTipText(views > 1 ? tip + "  ·  open in " + views + " groups" : tip);
            close.repaint();
            revalidate();
            repaint();
        }

        private void showTabMenu(MouseEvent e) {
            EditorGroup g = groupOf(tab);
            JPopupMenu menu = new JPopupMenu();
            menu.add(item("Close", () -> closeTab(tab), "Ctrl+W"));
            menu.add(item("Close Others", () -> closeOthers(tab), null));
            menu.add(item("Close to the Right", () -> closeToTheRight(tab), null));
            menu.add(item("Close Saved", () -> closeSaved(groupOf(tab)), null));
            menu.add(item("Close All", SphereCoder.this::closeAllTabs, "Ctrl+Shift+W"));
            menu.addSeparator();
            JMenuItem right = item("Split Right", () -> { selectTab(tab); splitGroup(groupOf(tab), EditorGrid.Side.RIGHT); }, null);
            right.setIcon(EditorIcons.splitRight(14, EditorTheme::textMuted));
            menu.add(right);
            JMenuItem down = item("Split Down", () -> { selectTab(tab); splitGroup(groupOf(tab), EditorGrid.Side.BOTTOM); }, null);
            down.setIcon(EditorIcons.splitDown(14, EditorTheme::textMuted));
            menu.add(down);
            boolean several = grid.groupCount() > 1;
            if (several || (g != null && g.tabs.getTabCount() > 1)) {
                menu.add(item(several ? "Move to Next Group" : "Move into a New Group to the Right",
                              () -> moveToAdjacentGroup(tab, 1), "Ctrl+Alt+Right"));
            }
            if (several) {
                menu.add(item("Copy to Next Group", () -> {
                    List<EditorGroup> gs = groups();
                    moveTab(tab, gs.get((gs.indexOf(groupOf(tab)) + 1) % gs.size()), -1, true);
                }, null));
            }
            File companion = CompanionFiles.find(tab.getFile());
            if (companion != null) {
                menu.add(item("Open " + companion.getName() + " to the Side", () -> openFileToSide(companion), null));
            }
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
            boolean active = isShownInGroup(tab);
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
    // Dragging tabs between groups
    // =======================================================================

    /**
     * Where a dragged tab would land, drawn over the editor while it is
     * dragged: a strip (move into that group), the middle of a group (the
     * same), or a quarter at its edge (split there with the tab). Ctrl while
     * dropping copies the tab instead, as a second view of the file.
     */
    private final class DropIndicator extends JComponent {

        /** A place to drop: a group, and the side to split it on (null: into it). */
        final class Target {
            final EditorGroup group;
            final EditorGrid.Side side;
            final int index;

            Target(EditorGroup group, EditorGrid.Side side, int index) {
                this.group = group;
                this.side = side;
                this.index = index;
            }
        }

        private Target target;
        private Rectangle highlight;
        private String label = "";

        DropIndicator() {
            setVisible(false);
            setOpaque(false);
        }

        /** Never takes the mouse: the drag stays with the tab. */
        @Override
        public boolean contains(int x, int y) {
            return false;
        }

        boolean isActive() {
            return target != null;
        }

        Target target() {
            return target;
        }

        void clear() {
            if (target != null || isVisible()) {
                target = null;
                highlight = null;
                setVisible(false);
            }
        }

        void track(Point screen, CodeTab dragged, boolean copy) {
            Point p = new Point(screen);
            SwingUtilities.convertPointFromScreen(p, grid);
            EditorGroup over = null;
            Rectangle r = null;
            for (EditorGroup g : groups()) {
                Rectangle b = grid.boundsOf(g);
                if (b.width > 0 && b.height > 0 && b.contains(p)) {
                    over = g;
                    r = b;
                    break;
                }
            }
            if (over == null) {
                clear();
                return;
            }
            EditorGroup source = groupOf(dragged);
            boolean alone = source == over && source.tabs.getTabCount() == 1;
            Rectangle area;
            EditorGrid.Side side = null;
            int index = -1;
            if (p.y < r.y + STRIP_HEIGHT) {
                Point inStrip = SwingUtilities.convertPoint(grid, p, over.tabs);
                index = over.tabs.indexAtLocation(inStrip.x, Math.max(8, Math.min(inStrip.y, 20)));
                area = new Rectangle(r.x, r.y, r.width, STRIP_HEIGHT);
            } else {
                Rectangle content = new Rectangle(r.x, r.y + STRIP_HEIGHT, r.width, r.height - STRIP_HEIGHT);
                double fx = (p.x - content.x) / (double) Math.max(1, content.width);
                double fy = (p.y - content.y) / (double) Math.max(1, content.height);
                // The nearest edge, when within its quarter.
                double best = 0.25;
                if (fx < best) { best = fx; side = EditorGrid.Side.LEFT; }
                if (1 - fx < best) { best = 1 - fx; side = EditorGrid.Side.RIGHT; }
                if (fy < best) { best = fy; side = EditorGrid.Side.TOP; }
                if (1 - fy < best) { side = EditorGrid.Side.BOTTOM; }
                area = content;
                if (side != null) {
                    switch (side) {
                        case LEFT -> area = new Rectangle(content.x, content.y, content.width / 2, content.height);
                        case RIGHT -> area = new Rectangle(content.x + content.width / 2, content.y,
                                                           content.width - content.width / 2, content.height);
                        case TOP -> area = new Rectangle(content.x, content.y, content.width, content.height / 2);
                        case BOTTOM -> area = new Rectangle(content.x, content.y + content.height / 2,
                                                            content.width, content.height - content.height / 2);
                    }
                }
            }
            if (source == over && side == null) {
                // Into its own group: nothing to do (reordering happens on the strip).
                clear();
                return;
            }
            if (alone && !copy) {
                // A group's only tab cannot leave it to split that same group.
                clear();
                return;
            }
            target = new Target(over, side, index);
            highlight = SwingUtilities.convertRectangle(grid, area, overlay);
            String verb = copy ? "Copy" : "Move";
            int number = groups().indexOf(over) + 1;
            String where = switch (side == null ? EditorGrid.Side.RIGHT : side) {
                case LEFT -> "Left";
                case RIGHT -> "Right";
                case TOP -> "Up";
                case BOTTOM -> "Down";
            };
            label = side == null ? verb + " to Group " + number
                  : (copy ? "Copy and Split " : "Split ") + where;
            setBounds(0, 0, overlay.getWidth(), overlay.getHeight());
            setVisible(true);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (highlight == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            RoundRectangle2D box = new RoundRectangle2D.Float(highlight.x + 3, highlight.y + 3,
                                                              highlight.width - 6, highlight.height - 6, 10, 10);
            g2.setColor(EditorTheme.alpha(EditorTheme.focus(), 48));
            g2.fill(box);
            g2.setColor(EditorTheme.alpha(EditorTheme.focus(), 210));
            g2.setStroke(new BasicStroke(2f));
            g2.draw(box);
            g2.setFont(FontLoader.getGlobalFont(Font.BOLD, 12));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(label) + 20;
            int th = 24;
            int cx = highlight.x + highlight.width / 2;
            int cy = highlight.y + highlight.height / 2;
            g2.setColor(EditorTheme.widgetBackground());
            g2.fill(new RoundRectangle2D.Float(cx - tw / 2f, cy - th / 2f, tw, th, 8, 8));
            g2.setColor(EditorTheme.alpha(EditorTheme.focus(), 210));
            g2.draw(new RoundRectangle2D.Float(cx - tw / 2f, cy - th / 2f, tw, th, 8, 8));
            g2.setColor(EditorTheme.foreground());
            g2.drawString(label, cx - fm.stringWidth(label) / 2, cy + (fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    private void drop(CodeTab tab, DropIndicator.Target t, boolean copy) {
        if (t == null || tab.isDisposed()) {
            return;
        }
        if (t.side == null) {
            moveTab(tab, t.group, t.index, copy);
            return;
        }
        EditorGroup fresh = newGroupBeside(t.group, t.side);
        moveTab(tab, fresh, -1, copy);
    }

    // =======================================================================
    // Recent files
    // =======================================================================

    /* Adds a file to the recent files list (remembered across sessions). */
    private void addToRecentFiles(File file) {
        if (file == null) return;
        EditorState.addRecent(file);
        for (SphereCoder w : family()) {
            w.rebuildRecentMenu();
        }
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
        addNewTab(activeGroup, null);
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
        status.layout.onClick(this::showLayoutMenu);
    }

    /* Updates the status bar with caret position of the active tab. */
    private void updateStatusBar() {
        CodeTab tab = getActiveTab();
        int errors = 0;
        int warnings = 0;
        for (CodeTab t : distinctTabs()) {
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
        int n = grid == null ? 1 : grid.groupCount();
        if (n > 1) {
            status.layout.set(EditorIcons.grid(13, EditorTheme::chromeForeground),
                              "Group " + (groups().indexOf(activeGroup) + 1) + "/" + n
                              + (grid.isMaximized() ? " · maximized" : "") + (lockedScrolling ? " · locked" : ""));
        } else {
            status.layout.set(EditorIcons.splitRight(13, EditorTheme::chromeForeground), "");
        }
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
            frame.setTitle((tab.isModified() ? "● " : "") + nameOf(tab) + " — SphereCoder");
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
        if (w instanceof com.sphere.ui.SphereCoderFrame frame) {
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
            selectTab(tab);
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

    /** Every tab of every group, in reading order; a file open in two groups is there twice. */
    @Override
    public List<CodeTab> allTabs() {
        List<CodeTab> out = new ArrayList<>();
        if (grid == null) {
            return out;
        }
        for (EditorGroup g : groups()) {
            out.addAll(g.views());
        }
        return out;
    }

    /** Definitions and completions are looked up in every window. */
    @Override
    public List<CodeTab> openTabs() {
        return familyTabs();
    }

    /** One tab per open file: the active one for its file, else the first in reading order. */
    private List<CodeTab> distinctTabs() {
        List<CodeTab> out = new ArrayList<>();
        Set<TextBuffer> seen = new HashSet<>();
        CodeTab active = getActiveTab();
        TextBuffer activeBuffer = active == null ? null : active.getBuffer();
        for (CodeTab t : allTabs()) {
            if (t.getBuffer() == activeBuffer) {
                if (t == active) {
                    out.add(t);
                }
            } else if (seen.add(t.getBuffer())) {
                out.add(t);
            }
        }
        return out;
    }

    @Override
    public void selectTab(CodeTab tab) {
        EditorGroup g = groupOf(tab);
        if (g == null) {
            return;
        }
        if (g.coder() != this) {
            // A tab of another window (a definition found there): that window shows it.
            g.coder().selectTab(tab);
            g.coder().bringToFront();
            return;
        }
        if (grid.isMaximized() && grid.maximizedLeaf() != g) {
            grid.restore();
            refreshAllGroupActions();
        }
        g.tabs.setSelectedComponent(tab);
        setActiveGroup(g);
    }

    @Override
    public void tabStateChanged(CodeTab tab) {
        if (tab == getActiveTab()) {
            updateStatusBar();
        }
        updateTabTitle(tab);
        problemsRefresh.restart();
    }

    /** Opens a definition (or any symbol) in the group beside the asking tab, keeping that one in place. */
    @Override
    public void revealToSide(CodeTab from, CodeTab target, DocSymbol symbol) {
        EditorGroup origin = groupOf(from);
        EditorGroup side = sideGroup(origin == null ? activeGroup : origin, true);
        CodeTab view = side.viewOf(target.getBuffer());
        if (view == null) {
            view = twinOf(target);
            addTab(side, view, -1, true);
        }
        selectTab(view);
        final CodeTab shown = view;
        // After the new group is laid out, so the symbol is centered in it.
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> shown.reveal(symbol)));
    }

    @Override
    public void zoom(int steps) {
        int size = steps == 0 ? 13 : EditorState.fontSize() + steps;
        EditorState.setFontSize(size);
        for (CodeTab t : familyTabs()) {
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

    /*
     * Handles application exit, prompting to save modified files (once each,
     * however many groups and windows show them). A group's window closing
     * docks its editors back instead: nothing closes, nothing is asked.
     */
    private void handleExit() {
        if (main != null) {
            dockBack();
            return;
        }
        Window parentWindow = SwingUtilities.getWindowAncestor(this);
        if (!prepareToQuit()) {
            return;
        }
        if (parentWindow != null) parentWindow.dispose();
    }

    /**
     * Asks about every modified file, then keeps the session, as closing the
     * editor window does. Sphere calls it before it quits: ":quit", the main
     * window's close box and a restart asked by the settings editor used to
     * end the program at once and lose whatever had not been saved.
     * Runs on the event thread. False when the user cancelled.
     */
    public boolean prepareToQuit() {
        if (main != null) {
            return root().prepareToQuit();
        }
        for (CodeTab tab : familyDistinctTabs()) {
            if (tab.isModified()) {
                SphereCoder owner = groupOf(tab) == null ? this : groupOf(tab).coder();
                selectTab(tab);
                int choice = JOptionPane.showConfirmDialog(
                        owner,
                        "The file \"" + nameOf(tab) +
                                "\" has been modified. Do you want to save before quitting?",
                        "Unsaved Changes",
                        JOptionPane.YES_NO_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );
                if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
                    return false;
                }
                if (choice == JOptionPane.YES_OPTION) {
                    if (tab.getFile() == null) {
                        owner.saveFileAs();
                    } else {
                        tab.saveFile();
                    }
                    if (tab.isModified()) {
                        return false;
                    }
                }
            }
        }
        // The windows' editors come back here, so the session keeps them.
        dockAllWindows();
        saveSession();
        EditorState.saveNow();
        return true;
    }

    /* Sets the code of the active tab (of the window worked in last, when a group has one of its own). */
    public void setCode(String code) {
        CodeTab tab = lastUsed().getActiveTab();
        if (tab != null) {
            tab.getEditorArea().setText(code == null ? "" : code.replace("\r\n", "\n"));
            tab.getEditorArea().setCaretPosition(0);
        }
    }

    /* Returns the code of the active tab (of the window worked in last). */
    public String getCode() {
        CodeTab tab = lastUsed().getActiveTab();
        return (tab != null) ? tab.getEditorArea().getText() : "";
    }

    /* Returns the editor area of the active tab of the window worked in last (for compatibility). */
    public javax.swing.text.JTextComponent getEditorArea() {
        CodeTab tab = lastUsed().getActiveTab();
        return (tab != null) ? tab.getEditorArea() : null;
    }

    /**
     * Loads a file resource into the workspace view.
     * If the file is already open, in this group or another, that tab is
     * selected and focused, and reloaded when it changed on disk (a formatter,
     * a refactoring) without edits here. Otherwise, the content is loaded into
     * an empty unmodified tab or appended as a new tab of the active group; a
     * file open only in another window gets a view here sharing its buffer.
     * @param file The target file to load into the tabbed layout.
     */
    public void loadFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (tooLargeToEdit(file)) {
            return;
        }

        CodeTab open = activeGroup.viewOf(file);
        if (open == null) {
            open = findTabForFile(file);
        }
        if (open != null) {
            selectTab(open);
            open.checkDisk();
            open.getEditorArea().requestFocusInWindow();
            this.currentFile = file;
            addToRecentFiles(file);
            return;
        }

        this.currentFile = file;
        CodeTab tab = getActiveTab();
        boolean pristine = tab != null && !tab.isModified() && tab.getFile() == null && !tab.getBuffer().isShared()
                           && tab.getEditorArea().getDocument().getLength() == 0;

        if (pristine && viewAnywhere(file) == null) {
            tab.setFile(file);
            tab.loadFileFromExternal(file);
            updateTabTitle(tab);
            refreshTabHeaders();
            updateStatusBar();
            scheduleSessionSave();
        } else {
            // Open in another window, it gets a view here sharing that text.
            addNewTab(activeGroup, file);
            if (pristine) {
                closeTab(tab);
            }
        }

        addToRecentFiles(file);
    }

    /**
     * A file over the editor's limit goes to the read-only viewer, which reads
     * it as it scrolls. A tab was opened for it before, left empty with the
     * file's name on it: one save would have overwritten the file with nothing.
     */
    private boolean tooLargeToEdit(File file) {
        if (file.length() <= com.sphere.components.editor.EditorFileIO.MAX_OPEN_BYTES) {
            return false;
        }
        com.sphere.components.editor.LargeFileViewer.show(file);
        addToRecentFiles(file);
        return true;
    }

    /**
     * Opens a file in the group beside the active one (a new group on the
     * right when there is only one), the active editor staying where it is.
     * A file already open elsewhere gets a second view sharing its buffer.
     */
    public void openFileToSide(File file) {
        if (file == null || !file.isFile() || tooLargeToEdit(file)) {
            return;
        }
        EditorGroup side = sideGroup(activeGroup, true);
        CodeTab view = side.viewOf(file);
        if (view == null) {
            CodeTab elsewhere = viewAnywhere(file);
            if (elsewhere != null) {
                view = twinOf(elsewhere);
                addTab(side, view, -1, true);
                view.matchScrollOf(elsewhere);
            } else {
                view = newTabFor(file);
                addTab(side, view, -1, true);
            }
        }
        selectTab(view);
        focus(view);
        this.currentFile = file;
        addToRecentFiles(file);
    }

    /** Splits the active group to the right (vertical split) or below (horizontal split). */
    public void splitEditor(boolean vertical) {
        splitGroup(activeGroup, vertical ? EditorGrid.Side.RIGHT : EditorGrid.Side.BOTTOM);
    }

    /** C++ header and source, a test and its code: switches to the companion, or opens it beside. */
    private void openCompanion(boolean toSide) {
        CodeTab t = getActiveTab();
        File companion = t == null ? null : CompanionFiles.find(t.getFile());
        if (companion == null) {
            toast(t == null || t.getFile() == null ? "Save the file first" : "No companion file found for "
                  + t.getFile().getName());
            return;
        }
        if (toSide) {
            openFileToSide(companion);
        } else {
            openFile(companion);
        }
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
        for (CodeTab t : familyDistinctTabs()) {
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
                scheduleSessionSave();
            }
        }
    }

    /**
     * Closes a tab. Only the last view of a file asks about unsaved changes: a
     * second view closing leaves the text open in the other group. Returns
     * false when the user cancelled.
     */
    private boolean closeTab(CodeTab tab) {
        if (tab.isDisposed()) {
            return true;
        }
        boolean lastView = tab.getBuffer().views().size() <= 1;
        if (lastView && tab.isModified()) {
            selectTab(tab);
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    "The file \"" + nameOf(tab) +
                            "\" has been modified. Do you want to save before closing?",
                    "Unsaved Changes",
                    JOptionPane.YES_NO_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (choice == JOptionPane.CANCEL_OPTION || choice == JOptionPane.CLOSED_OPTION) {
                return false;
            }
            if (choice == JOptionPane.YES_OPTION) {
                if (tab.getFile() == null) {
                    saveFileAs();
                } else {
                    tab.saveFile();
                }
                if (tab.isModified()) {
                    return false;
                }
            }
        }

        if (lastView && tab.getFile() != null) {
            closedFiles.push(tab.getFile());
            while (closedFiles.size() > 20) {
                closedFiles.removeLast();
            }
        }
        EditorGroup group = groupOf(tab);
        if (group != null) {
            int index = group.tabs.indexOfComponent(tab);
            if (index >= 0) {
                group.tabs.removeTabAt(index);
            }
        }
        tab.dispose();
        if (group != null && group.tabs.getTabCount() == 0) {
            if (grid.groupCount() > 1) {
                closeGroupNow(group);
            } else if (main != null) {
                // A window's last editor closed: the window goes with it.
                closeWindow();
                return true;
            } else {
                addNewTab(group, null);
            }
        }

        // currentFile used to keep naming the file whose tab had just closed, and
        // openFileInternally treats a match there as "already open" and returns.
        // The same file could then never be reopened without restarting Sphere.
        CodeTab active = getActiveTab();
        this.currentFile = active == null ? null : active.getFile();
        refreshTabHeaders();
        updateStatusBar();
        problemsRefresh.restart();
        scheduleSessionSave();
        return true;
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

    /* Closes all tabs of the group except the given one. */
    private void closeOthers(CodeTab keep) {
        EditorGroup g = groupOf(keep);
        if (g == null) {
            return;
        }
        for (CodeTab tab : g.views()) {
            if (tab != keep) {
                closeTab(tab);
            }
        }
    }

    private void closeToTheRight(CodeTab tab) {
        EditorGroup g = groupOf(tab);
        if (g == null) {
            return;
        }
        List<CodeTab> all = g.views();
        int index = all.indexOf(tab);
        for (int i = all.size() - 1; i > index; i--) {
            closeTab(all.get(i));
        }
    }

    private void closeSaved(EditorGroup g) {
        if (g == null) {
            return;
        }
        for (CodeTab t : g.views()) {
            if (!t.isModified()) {
                closeTab(t);
            }
        }
    }

    /* Closes all open tabs, in every group. */
    private void closeAllTabs() {
        for (CodeTab tab : allTabs()) {
            if (!closeTab(tab)) {
                return;
            }
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
    // Session
    // =======================================================================

    /**
     * Makes this editor remember its layout and open files. Those of the last
     * session come back when the window first shows (now, if it already
     * does): a hidden editor holds no file, so console commands acting on
     * "the editor's file" never touch one the user has not seen this session.
     * Only Sphere's main editor window does this.
     */
    public void enableSessionRestore() {
        persistSession = true;
        restorePending = EditorState.flag(EditorState.RESTORE_SESSION, true);
        if (isShowing()) {
            restoreSessionIfPending();
        }
    }

    /** Brings back the last session, once; files opened before the window showed join it. */
    public void restoreSessionIfPending() {
        if (!restorePending) {
            return;
        }
        restorePending = false;
        restoreSession();
        scheduleSessionSave();
    }

    /** The main editor keeps the session, its windows' files included. */
    private void scheduleSessionSave() {
        SphereCoder r = root();
        if (r.persistSession && r.sessionSaver != null) {
            r.sessionSaver.restart();
        }
    }

    /**
     * The grid's layout, each group's files with the one shown, and the active
     * group. The files of the groups in windows of their own are listed in the
     * active group, where they would dock: the windows are not remembered.
     */
    public void saveSession() {
        // Until the last session is back, what is open is not a session to keep.
        if (!persistSession || restorePending || grid == null) {
            return;
        }
        List<EditorGroup> gs = groups();
        int before = EditorState.number("session.groups", 0);
        EditorState.setText("session.layout", grid.describe());
        EditorState.setNumber("session.groups", gs.size());
        EditorState.setNumber("session.active", Math.max(0, gs.indexOf(activeGroup)));
        for (int i = 0; i < gs.size(); i++) {
            StringBuilder sb = new StringBuilder();
            List<CodeTab> views = gs.get(i).views();
            int shown = 0;
            int n = 0;
            StringBuilder paths = new StringBuilder();
            Set<String> listed = new HashSet<>();
            for (CodeTab t : views) {
                if (t.getFile() == null) {
                    continue;
                }
                if (t == gs.get(i).selected()) {
                    shown = n;
                }
                listed.add(t.getFile().getAbsolutePath());
                paths.append('|').append(t.getFile().getAbsolutePath());
                n++;
            }
            if (gs.get(i) == activeGroup) {
                for (SphereCoder w : windows) {
                    for (CodeTab t : w.allTabs()) {
                        if (t.getFile() != null && listed.add(t.getFile().getAbsolutePath())) {
                            paths.append('|').append(t.getFile().getAbsolutePath());
                        }
                    }
                }
            }
            sb.append(shown).append(paths);
            EditorState.setText("session.group." + i, sb.toString());
        }
        for (int i = gs.size(); i < before; i++) {
            EditorState.setText("session.group." + i, null);
        }
    }

    private void restoreSession() {
        String spec = EditorState.text("session.layout", null);
        int n = EditorState.number("session.groups", 0);
        if (spec == null || n <= 0) {
            return;
        }
        List<List<File>> files = new ArrayList<>();
        List<Integer> shown = new ArrayList<>();
        int total = 0;
        for (int i = 0; i < n; i++) {
            String line = EditorState.text("session.group." + i, "");
            String[] parts = line.split("\\|");
            List<File> list = new ArrayList<>();
            int sel = 0;
            try {
                sel = parts.length > 0 && !parts[0].isBlank() ? Integer.parseInt(parts[0].trim()) : 0;
            } catch (NumberFormatException ignored) {
                // first file
            }
            for (int k = 1; k < parts.length; k++) {
                File f = new File(parts[k]);
                // a file grown past the editor's limit since is not brought back as an empty tab
                if (f.isFile() && f.length() <= com.sphere.components.editor.EditorFileIO.MAX_OPEN_BYTES) {
                    list.add(f);
                } else if (k - 1 < sel) {
                    sel--;
                }
            }
            total += list.size();
            files.add(list);
            shown.add(sel);
        }
        if (total == 0) {
            return;
        }
        // What is open already (a file opened before the window showed) joins
        // the restored layout: in its own slot when the session had it, else
        // in the active group; it keeps the keyboard. Empty Untitled tabs go.
        CodeTab wasActive = getActiveTab();
        Map<String, CodeTab> current = new LinkedHashMap<>();
        List<CodeTab> unsaved = new ArrayList<>();
        for (EditorGroup g : groups()) {
            for (CodeTab t : g.views()) {
                g.tabs.remove(t);
                boolean pristine = t.getFile() == null && !t.isModified()
                                   && t.getEditorArea().getDocument().getLength() == 0;
                if (pristine) {
                    t.dispose();
                } else if (t.getFile() == null) {
                    unsaved.add(t);
                } else if (current.putIfAbsent(t.getFile().getAbsolutePath(), t) != null) {
                    t.dispose(); // a second view: the session decides where views go
                }
            }
        }
        if (wasActive != null && wasActive.isDisposed()) {
            wasActive = null;
        }
        List<JComponent> made = grid.rebuild(spec, EditorGroup::new);
        if (made == null || made.size() != n) {
            // A layout that no longer reads: every file in one group.
            made = grid.rebuild("G", EditorGroup::new);
            List<File> all = new ArrayList<>();
            for (List<File> l : files) {
                all.addAll(l);
            }
            files = List.of(all);
            shown = List.of(0);
        }
        groupHistory.clear();
        List<EditorGroup> fresh = new ArrayList<>();
        for (JComponent c : made) {
            fresh.add((EditorGroup) c);
        }
        Map<String, CodeTab> opened = new HashMap<>();
        for (int i = 0; i < fresh.size(); i++) {
            EditorGroup g = fresh.get(i);
            for (File f : files.get(i)) {
                String key = f.getAbsolutePath();
                CodeTab twinSource = opened.get(key);
                CodeTab t;
                if (twinSource != null) {
                    t = twinOf(twinSource);
                } else if (current.containsKey(key)) {
                    t = current.remove(key);
                } else {
                    t = newTabFor(f);
                }
                opened.putIfAbsent(key, t);
                addTab(g, t, -1, false);
            }
            int sel = shown.get(i);
            if (sel >= 0 && sel < g.tabs.getTabCount()) {
                g.tabs.setSelectedIndex(sel);
            }
        }
        int active = Math.max(0, Math.min(fresh.size() - 1, EditorState.number("session.active", 0)));
        activeGroup = fresh.get(active);
        groupHistory.push(activeGroup);
        for (CodeTab t : current.values()) {
            addTab(activeGroup, t, -1, false);
        }
        for (CodeTab t : unsaved) {
            addTab(activeGroup, t, -1, false);
        }
        if (wasActive != null && !wasActive.isDisposed() && groupOf(wasActive) != null) {
            EditorGroup g = groupOf(wasActive);
            g.tabs.setSelectedComponent(wasActive);
            activeGroup = g;
            groupHistory.remove(g);
            groupHistory.push(g);
            focus(wasActive);
        }
        for (EditorGroup g : fresh) {
            if (g.tabs.getTabCount() == 0) {
                if (grid.groupCount() > 1) {
                    closeGroupNow(g);
                } else {
                    addNewTab(g, null);
                }
            }
        }
        currentFile = getActiveTab() == null ? null : getActiveTab().getFile();
        refreshAllGroupActions();
        refreshTabHeaders();
        updateStatusBar();
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
        /** The second key after Ctrl+K: a key code, and the characters that also count for it. */
        int chordCode;
        String chordChars = "";
        String chordText;
        boolean hidden;

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

        /** The shortcut as shown: the key, else the Ctrl+K chord. */
        String keyLabel() {
            if (key != null) {
                return keyText(key);
            }
            return chordText == null ? null : "Ctrl+K " + chordText;
        }

        boolean matchesChord(KeyEvent e) {
            if (chordCode != 0 && e.getKeyCode() == chordCode) {
                return true;
            }
            // Ctrl+\ types a control character: the key code says it was the backslash.
            if (e.getKeyCode() == KeyEvent.VK_BACK_SLASH && chordChars.indexOf('\\') >= 0) {
                return true;
            }
            char c = e.getKeyChar();
            return c != KeyEvent.CHAR_UNDEFINED && c >= ' ' && chordChars.indexOf(Character.toLowerCase(c)) >= 0;
        }
    }

    private static KeyStroke key(int code, int mods) {
        return KeyStroke.getKeyStroke(code, mods);
    }

    private Command cmd(String id, String category, String title, KeyStroke key, Runnable action) {
        Command c = new Command(id, category, title, key, action, null);
        commands.put(id, c);
        return c;
    }

    private Command toggle(String id, String category, String title, KeyStroke key, BooleanSupplier checked,
                           Runnable action) {
        Command c = new Command(id, category, title, key, action, checked);
        commands.put(id, c);
        return c;
    }

    /** Gives a command a Ctrl+K chord: the key code, the characters that also count, how it reads. */
    private static Command chord(Command c, int code, String chars, String text) {
        c.chordCode = code;
        c.chordChars = chars == null ? "" : chars;
        c.chordText = text;
        return c;
    }

    /**
     * Ctrl+K, then a second key, as in VS Code: the split commands live there,
     * where they fit any keyboard layout. The second key is matched on its
     * code or on the character it types, so AltGr+8 for a backslash on an
     * AZERTY keyboard counts as the backslash it is.
     */
    private final class ChordKeys implements KeyEventDispatcher {
        private boolean waiting;
        private boolean swallow;
        private final Timer expiry = new Timer(3000, e -> stop(""));
        private final Timer clearMessage = new Timer(2500, e -> status.message.set(null, ""));

        ChordKeys() {
            expiry.setRepeats(false);
            clearMessage.setRepeats(false);
        }

        void start() {
            if (waiting) {
                return;
            }
            if (swallow) {
                detach();
            }
            waiting = true;
            swallow = false;
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
            clearMessage.stop();
            status.message.set(null, "(Ctrl+K) was pressed. Waiting for a second key...");
            expiry.restart();
        }

        void stop(String message) {
            if (waiting || swallow) {
                detach();
            }
            waiting = false;
            swallow = false;
            expiry.stop();
            status.message.set(null, message == null ? "" : message);
        }

        private void detach() {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (swallow) {
                // The character the second key types, and its release, are the chord's.
                if (e.getID() == KeyEvent.KEY_RELEASED) {
                    swallow = false;
                    detach();
                    clearMessage.restart();
                }
                e.consume();
                return true;
            }
            if (!waiting || e.getID() != KeyEvent.KEY_PRESSED) {
                return waiting && e.getID() == KeyEvent.KEY_TYPED && consumeTyped(e);
            }
            int code = e.getKeyCode();
            boolean modifierOnly = code == KeyEvent.VK_CONTROL || code == KeyEvent.VK_SHIFT
                    || code == KeyEvent.VK_ALT || code == KeyEvent.VK_ALT_GRAPH || code == KeyEvent.VK_META;
            if (modifierOnly || (code == KeyEvent.VK_UNDEFINED && e.getKeyChar() == KeyEvent.CHAR_UNDEFINED)) {
                return false;
            }
            if (code == KeyEvent.VK_ESCAPE) {
                stop("");
                e.consume();
                return true;
            }
            Command hit = null;
            for (Command c : commands.values()) {
                if ((c.chordCode != 0 || !c.chordChars.isEmpty()) && c.matchesChord(e)) {
                    hit = c;
                    break;
                }
            }
            waiting = false;
            swallow = true;
            expiry.stop();
            status.message.set(null, hit != null ? "" : "(Ctrl+K, " + KeyEvent.getKeyText(code) + ") is not a command");
            if (hit != null) {
                final Command run = hit;
                SwingUtilities.invokeLater(() -> runCommand(run));
            }
            e.consume();
            return true;
        }

        private boolean consumeTyped(KeyEvent e) {
            // Ctrl+K itself types a control character no one wants in the code.
            if (e.getKeyChar() < ' ') {
                e.consume();
                return true;
            }
            return false;
        }
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
            for (CodeTab t : familyTabs()) {
                t.setMinimapVisible(on);
            }
        });
        toggle("view.wordWrap", "View", "Toggle Word Wrap", key(KeyEvent.VK_Z, alt),
               () -> EditorState.flag(EditorState.WORD_WRAP, false), () -> {
            boolean on = !EditorState.flag(EditorState.WORD_WRAP, false);
            EditorState.setFlag(EditorState.WORD_WRAP, on);
            for (CodeTab t : familyTabs()) {
                t.setWordWrap(on);
            }
        });
        toggle("view.guides", "View", "Toggle Indent Guides", null,
               () -> EditorState.flag(EditorState.INDENT_GUIDES, true), () -> {
            boolean on = !EditorState.flag(EditorState.INDENT_GUIDES, true);
            EditorState.setFlag(EditorState.INDENT_GUIDES, on);
            for (CodeTab t : familyTabs()) {
                t.getEditorArea().setIndentGuidesVisible(on);
            }
        });
        toggle("view.occurrences", "View", "Toggle Highlight of Occurrences", null,
               () -> EditorState.flag(EditorState.OCCURRENCES, true), () -> {
            boolean on = !EditorState.flag(EditorState.OCCURRENCES, true);
            EditorState.setFlag(EditorState.OCCURRENCES, on);
            for (CodeTab t : familyTabs()) {
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

        // ---- Explorer: WorkSpace/ and the folders beside it, at the far left of the main editor
        toggle("view.explorer", "View", "Toggle Explorer", key(KeyEvent.VK_B, ctrl),
               () -> root().explorer.isVisible(), () -> root().showExplorer(!root().explorer.isVisible()));
        cmd("view.focusExplorer", "View", "Show Explorer", key(KeyEvent.VK_E, ctrl | shift),
            () -> root().withExplorer(ExplorerPanel::focusTree));
        cmd("file.openWorkspace", "Explorer", "Show WorkSpace/ in Explorer", null,
            () -> root().withExplorer(ExplorerPanel::openWorkspace));
        cmd("file.openFolder", "Explorer", "Add Folder to Explorer...", null,
            () -> root().withExplorer(ExplorerPanel::chooseOtherFolder));
        cmd("explorer.reveal", "Explorer", "Reveal Active File in Explorer", null,
            () -> root().withExplorer(ExplorerPanel::revealActive));
        cmd("explorer.collapse", "Explorer", "Collapse Folders in Explorer", null,
            () -> root().withExplorer(ExplorerPanel::collapseAll));
        cmd("explorer.refresh", "Explorer", "Refresh Explorer", null,
            () -> root().withExplorer(ExplorerPanel::refreshAll));

        // ---- Split groups: work on several files, or two places of one, side by side
        chord(cmd("view.splitRight", "View", "Split Editor Right (vertical split)", key(KeyEvent.VK_BACK_SLASH, ctrl),
                  () -> splitGroup(activeGroup, EditorGrid.Side.RIGHT)), KeyEvent.VK_V, "v", "V");
        chord(cmd("view.splitDown", "View", "Split Editor Down (horizontal split)", null,
                  () -> splitGroup(activeGroup, EditorGrid.Side.BOTTOM)), KeyEvent.VK_H, "h\\|", "H");
        cmd("view.splitLeft", "View", "Split Editor Left", null, () -> splitGroup(activeGroup, EditorGrid.Side.LEFT));
        cmd("view.splitUp", "View", "Split Editor Up", null, () -> splitGroup(activeGroup, EditorGrid.Side.TOP));
        chord(cmd("view.splitSmart", "View", "Split Editor Where There Is Room", null, this::splitSmart),
              KeyEvent.VK_S, "s", "S");
        for (int i = 1; i <= 9; i++) {
            final int n = i;
            Command c = cmd("view.focusGroup" + i, "View", "Focus Group " + i,
                            key(KeyEvent.VK_1 + i - 1, ctrl), () -> focusGroup(n));
            c.hidden = i > 4;
        }
        chord(cmd("view.focusLeft", "View", "Focus Left Group", null, () -> focusDirection(EditorGrid.Side.LEFT)),
              KeyEvent.VK_LEFT, "", "Left");
        chord(cmd("view.focusRight", "View", "Focus Right Group", null, () -> focusDirection(EditorGrid.Side.RIGHT)),
              KeyEvent.VK_RIGHT, "", "Right");
        chord(cmd("view.focusUp", "View", "Focus Group Above", null, () -> focusDirection(EditorGrid.Side.TOP)),
              KeyEvent.VK_UP, "", "Up");
        chord(cmd("view.focusDown", "View", "Focus Group Below", null, () -> focusDirection(EditorGrid.Side.BOTTOM)),
              KeyEvent.VK_DOWN, "", "Down");
        cmd("view.focusNextGroup", "View", "Focus Next Group", key(KeyEvent.VK_F6, 0), () -> focusCycle(1));
        cmd("view.focusPrevGroup", "View", "Focus Previous Group", key(KeyEvent.VK_F6, shift), () -> focusCycle(-1));
        cmd("view.moveRight", "View", "Move Editor into Next Group", key(KeyEvent.VK_RIGHT, ctrl | alt),
            () -> withTab(t -> moveToAdjacentGroup(t, 1)));
        cmd("view.moveLeft", "View", "Move Editor into Previous Group", key(KeyEvent.VK_LEFT, ctrl | alt),
            () -> withTab(t -> moveToAdjacentGroup(t, -1)));
        chord(cmd("view.maximizeGroup", "View", "Toggle Maximized Group", null, () -> toggleMaximize(activeGroup)),
              KeyEvent.VK_M, "m", "M");
        chord(cmd("view.evenGroups", "View", "Even Out Group Sizes", null, () -> {
            grid.evenOut();
            refreshAllGroupActions();
        }), KeyEvent.VK_E, "e=", "E");
        cmd("view.flipLayout", "View", "Flip Layout (rows ↔ columns)", key(KeyEvent.VK_0, shift | alt), () -> {
            grid.flipOrientation();
            refreshAllGroupActions();
        });
        chord(toggle("view.lockScroll", "View", "Lock Scrolling Across Groups", null, () -> lockedScrolling, () -> {
            lockedScrolling = !lockedScrolling;
            toast(lockedScrolling ? "The groups now scroll together" : "Each group scrolls on its own");
            updateStatusBar();
        }), KeyEvent.VK_L, "l", "L");
        chord(cmd("view.joinAll", "View", "Join All Groups", null, this::joinAllGroups), KeyEvent.VK_J, "j", "J");
        cmd("view.joinNext", "View", "Join Group with Next Group", null, this::joinWithNext);
        chord(cmd("view.closeGroup", "View", "Close Group", null, () -> closeGroup(activeGroup)),
              KeyEvent.VK_W, "w", "W");
        // ---- Windows: a group on another screen, docked back when its window closes
        cmd("view.moveGroupToWindow", "View", "Move Group into New Window", null,
            () -> moveGroupToNewWindow(activeGroup));
        cmd("view.dockWindow", "View", main == null ? "Dock All Windows Back" : "Dock Window Back", null, () -> {
            if (main != null) {
                dockBack();
            } else if (windows.isEmpty()) {
                toast("No group has a window of its own");
            } else {
                dockAllWindows();
            }
        });
        cmd("layout.single", "Layout", "Single", null, () -> applyLayout("G"));
        cmd("layout.twoColumns", "Layout", "Two Columns", null, () -> applyLayout("H(0.500,G,G)"));
        cmd("layout.threeColumns", "Layout", "Three Columns", null, () -> applyLayout("H(0.333,G,H(0.500,G,G))"));
        cmd("layout.twoRows", "Layout", "Two Rows", null, () -> applyLayout("V(0.500,G,G)"));
        cmd("layout.grid", "Layout", "Grid (2×2)", null,
            () -> applyLayout("H(0.500,V(0.500,G,G),V(0.500,G,G))"));
        cmd("layout.mainSide", "Layout", "Main Column and Two Stacked", null,
            () -> applyLayout("H(0.600,G,V(0.500,G,G))"));
        chord(cmd("file.companionToSide", "File", "Open Companion File to the Side (header/source, test)", null,
                  () -> openCompanion(true)), KeyEvent.VK_O, "o", "O");
        cmd("file.companion", "File", "Switch to Companion File (header/source, test)", key(KeyEvent.VK_O, alt),
            () -> openCompanion(false));

        // ---- Go
        cmd("go.symbol", "Go", "Go to Symbol in File...", key(KeyEvent.VK_O, ctrl | shift), () -> showPalette("@"));
        cmd("go.allSymbols", "Go", "Go to Symbol in Open Files...", key(KeyEvent.VK_T, ctrl), () -> showPalette("#"));
        cmd("go.line", "Go", "Go to Line/Column...", key(KeyEvent.VK_G, ctrl), () -> showPalette(":"));
        cmd("go.definition", "Go", "Go to Definition", key(KeyEvent.VK_F12, 0), () -> withTab(CodeTab::goToDefinitionAtCaret));
        chord(cmd("go.definitionToSide", "Go", "Open Definition to the Side", key(KeyEvent.VK_F12, ctrl | alt),
                  () -> withTab(t -> t.goToDefinitionAtCaret(true))), KeyEvent.VK_F12, "", "F12");
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
            for (CodeTab t : familyTabs()) {
                t.setAutoComplete(on);
            }
        });
        toggle("pref.autoClose", "Preferences", "Auto Close Brackets and Quotes", null,
               () -> EditorState.flag(EditorState.AUTO_CLOSE, true), () -> {
            boolean on = !EditorState.flag(EditorState.AUTO_CLOSE, true);
            EditorState.setFlag(EditorState.AUTO_CLOSE, on);
            for (CodeTab t : familyTabs()) {
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
            for (CodeTab t : familyTabs()) {
                t.setLiveLint(on);
            }
        });
        toggle("pref.restoreSession", "Preferences", "Restore Open Editors and Layout at Startup", null,
               () -> EditorState.flag(EditorState.RESTORE_SESSION, true),
               () -> EditorState.setFlag(EditorState.RESTORE_SESSION, !EditorState.flag(EditorState.RESTORE_SESSION, true)));
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
        // Ctrl+K opens a chord: the next key picks a split or group command.
        in.put(key(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK), "sphere-chord");
        am.put("sphere-chord", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                chords.start();
            }
        });
    }

    private void runCommand(Command c) {
        try {
            c.action.run();
        } catch (RuntimeException ex) {
            AppLogger.error(c.label() + " failed: " + ex.getMessage());
        }
        if (c.checked != null) {
            // Every window's menu, each reading its own state: a preference is
            // shared by all of them, locked scrolling belongs to one.
            for (SphereCoder w : family()) {
                Command own = w.commands.get(c.id);
                JCheckBoxMenuItem item = w.toggles.get(c.id);
                if (own != null && own.checked != null && item != null) {
                    item.setSelected(own.checked.getAsBoolean());
                }
            }
        }
    }

    /** Ctrl+Tab: the next tab of the active group. */
    private void cycleTab(int direction) {
        JTabbedPane tabs = activeGroup.tabs;
        int n = tabs.getTabCount();
        if (n > 1) {
            tabs.setSelectedIndex(Math.floorMod(tabs.getSelectedIndex() + direction, n));
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
                List<EditorGroup> all = groups();
                // Each open file once, from the group used last; the group is named when there are several.
                for (EditorGroup g : groupsByRecency()) {
                    for (CodeTab t : g.views()) {
                        File f = t.getFile();
                        String key = f == null ? "buffer:" + System.identityHashCode(t.getBuffer()) : f.getAbsolutePath();
                        if (!seen.add(key)) {
                            continue;
                        }
                        String where = all.size() > 1 ? "group " + (all.indexOf(g) + 1) : "open";
                        out.add(new CommandPalette.Item(nameOf(t), f == null ? "unsaved" : relative(f.getParentFile()),
                                                        t.isModified() ? "● " + where : where,
                                                        EditorIcons.LanguageBadge.of(t.getLanguage(), f), t, 60)
                                    .group("open"));
                    }
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
                    if (c.hidden || c.id.equals("view.zoomInPlus") || c.id.equals("view.zoomOutMinus")
                            || c.id.equals("view.paletteF1") || c.id.equals("help.keys")) {
                        continue;
                    }
                    int r = recent.indexOf(c.id);
                    String shortcut = c.keyLabel();
                    String hint = shortcut == null ? "" : "key:" + shortcut;
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
                for (CodeTab t : distinctTabs()) {
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
                new CommandPalette.Item(":", "Go to a line", "Ctrl+G", null, ":", 0),
                new CommandPalette.Item("Ctrl+K", "then V split right · H split down · M maximize · arrows: focus a group",
                                        "", EditorIcons.splitRight(15, EditorTheme::textMuted), ">Split", 0)),
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
    // Explorer: WorkSpace/ and the folders beside it, at the far left
    // =======================================================================

    private static int explorerWidth() {
        return Math.max(160, CoderConfig.number("explorer", "width", 260));
    }

    /** Shows or hides the explorer without writing it down: what the constructor uses at startup. */
    private void applyExplorerVisible(boolean visible) {
        explorer.setVisible(visible);
        explorerSplit.setDividerSize(visible ? 4 : 0);
        if (visible) {
            explorerSplit.setDividerLocation(explorerWidth());
        }
        explorerSplit.revalidate();
    }

    /** Shows or hides the explorer; the next launch finds it the same. */
    private void showExplorer(boolean visible) {
        if (explorer == null) {
            root().showExplorer(visible);
            return;
        }
        applyExplorerVisible(visible);
        CoderConfig.put("explorer", "visible", visible);
    }

    /** Runs an explorer action, showing the explorer first; from a group's window, the main editor's. */
    private void withExplorer(Consumer<ExplorerPanel> action) {
        if (explorer == null) {
            root().bringToFront();
            root().withExplorer(action);
            return;
        }
        if (!explorer.isVisible()) {
            showExplorer(true);
        }
        action.accept(explorer);
    }

    /** The explorer selects the file being edited, when it lies in WorkSpace/ or a folder of the explorer. */
    private void followInExplorer() {
        ExplorerPanel e = root().explorer;
        if (e != null && e.isVisible() && currentFile != null) {
            e.reveal(currentFile);
        }
    }

    /**
     * A file or a folder moved on disk, from an explorer: every editor showing
     * it, or a file inside it, names its new place, in every window.
     */
    public void fileMoved(File from, File to) {
        if (from == null || to == null) {
            return;
        }
        SphereCoder r = root();
        Path old = from.getAbsoluteFile().toPath();
        Path now = to.getAbsoluteFile().toPath();
        Set<TextBuffer> done = new HashSet<>();
        boolean changed = false;
        for (CodeTab t : r.familyTabs()) {
            File f = t.getFile();
            if (f == null || !done.add(t.getBuffer())) {
                continue;
            }
            Path p = f.getAbsoluteFile().toPath();
            if (p.startsWith(old)) {
                // setFile renames the buffer: every view of it follows
                t.setFile(now.resolve(old.relativize(p)).toFile());
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        for (SphereCoder c : r.family()) {
            CodeTab active = c.getActiveTab();
            c.currentFile = active == null ? null : active.getFile();
            c.refreshTabHeaders();
            c.updateStatusBar();
            c.updateFrameTitle();
        }
        r.scheduleSessionSave();
    }

    /** What the explorer asks of the editor: open a file, say something, follow a move. */
    private final class EditorExplorerHost implements ExplorerPanel.Host {
        @Override
        public void openFile(File file) {
            SphereCoder.this.openFile(file);
        }

        @Override
        public void openFileToSide(File file) {
            // Through the frame, which sends an image or a .root file to its own viewer.
            Window w = SwingUtilities.getWindowAncestor(SphereCoder.this);
            if (w instanceof com.sphere.ui.SphereCoderFrame frame) {
                frame.openFileToSide(file);
            } else {
                SphereCoder.this.openFileToSide(file);
            }
        }

        @Override
        public void toast(String message) {
            SphereCoder.this.toast(message);
        }

        @Override
        public File activeFile() {
            return getCurrentFile();
        }

        @Override
        public void fileMoved(File from, File to) {
            SphereCoder.this.fileMoved(from, to);
        }
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
            if (t[0].equals("debug") && debugPanel == null) {
                continue;
            }
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
        if (debugPanel != null) {
            bottomContent.add(debugPanel, "debug");
        }
        bottomPanel.add(header, BorderLayout.NORTH);
        bottomPanel.add(bottomContent, BorderLayout.CENTER);
    }

    private void showBottom(String card) {
        if (card.equals("debug") && debugPanel == null) {
            // A group's window debugs in the main one.
            root().showBottom(card);
            root().bringToFront();
            return;
        }
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
        if (card.equals("debug") && debugPanel == null) {
            root().toggleBottom(card);
            if ("debug".equals(root().bottomShown)) {
                root().bringToFront();
            }
            return;
        }
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
            JCheckBoxMenuItem box = new JCheckBoxMenuItem(menuLabel(c), c.checked.getAsBoolean());
            toggles.put(id, box);
            item = box;
        } else {
            item = new JMenuItem(menuLabel(c));
        }
        item.setForeground(theme().getTextPrimary());
        item.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        if (c.key != null) {
            item.setAccelerator(c.key);
        }
        item.addActionListener(e -> runCommand(c));
        return item;
    }

    /** A command's menu text; a Ctrl+K chord, which no accelerator can show, follows it faintly. */
    private static String menuLabel(Command c) {
        if (c.key != null || c.chordText == null) {
            return c.title;
        }
        return "<html>" + escape(c.title) + "&nbsp;&nbsp;&nbsp;<font color='" + hex(EditorTheme.textFaint())
               + "'>Ctrl+K " + escape(c.chordText) + "</font></html>";
    }

    /** A command as an item of a popup menu: no accelerator registered, state read now. */
    private JMenuItem popupItem(String id) {
        Command c = commands.get(id);
        if (c == null) {
            return new JMenuItem(id);
        }
        JMenuItem item = c.checked != null ? new JCheckBoxMenuItem(menuLabel(c), c.checked.getAsBoolean())
                                           : new JMenuItem(menuLabel(c));
        if (c.key != null) {
            item.setAccelerator(c.key);
        }
        if (id.equals("view.splitRight")) {
            item.setIcon(EditorIcons.splitRight(14, EditorTheme::textMuted));
        } else if (id.equals("view.splitDown")) {
            item.setIcon(EditorIcons.splitDown(14, EditorTheme::textMuted));
        } else if (id.equals("view.maximizeGroup")) {
            item.setIcon(EditorIcons.maximize(14, EditorTheme::textMuted, grid.isMaximized()));
        } else if (id.equals("view.lockScroll")) {
            item.setIcon(null);
        } else if (id.equals("layout.grid")) {
            item.setIcon(EditorIcons.grid(14, EditorTheme::textMuted));
        } else if (id.equals("view.moveGroupToWindow")) {
            item.setIcon(EditorIcons.window(14, EditorTheme::textMuted, false));
        } else if (id.equals("view.dockWindow")) {
            item.setIcon(EditorIcons.window(14, EditorTheme::textMuted, true));
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
        fileMenu.add(menuItem("file.openWorkspace"));
        fileMenu.add(menuItem("file.openFolder"));
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

        JMenu viewMenu = menu("View", "view.palette", "-", "view.explorer", "view.focusExplorer", "explorer.reveal",
                              "explorer.collapse", "explorer.refresh", "-", "view.minimap", "view.wordWrap", "view.guides",
                              "view.occurrences", "view.lineNumbers", "-", "view.zoomIn", "view.zoomOut",
                              "view.zoomReset", "-", "view.problems", "view.preview");
        viewMenu.addSeparator();
        JMenu layoutSub = menu("Editor Layout", "view.splitRight", "view.splitDown", "view.splitLeft", "view.splitUp",
                               "view.splitSmart", "-", "view.maximizeGroup", "view.evenGroups", "view.flipLayout",
                               "view.lockScroll", "-", "view.joinNext", "view.joinAll", "view.closeGroup", "-",
                               "view.moveGroupToWindow", "view.dockWindow", "-",
                               "layout.single", "layout.twoColumns", "layout.threeColumns", "layout.twoRows",
                               "layout.grid", "layout.mainSide");
        layoutSub.setIcon(EditorIcons.splitRight(14, EditorTheme::textMuted));
        viewMenu.add(layoutSub);
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
                            "go.definition", "go.definitionToSide", "file.companion", "file.companionToSide", "-",
                            "go.nextProblem", "go.prevProblem", "-", "go.nextTab", "go.prevTab", "-",
                            "view.focusGroup1", "view.focusGroup2", "view.focusGroup3", "view.focusNextGroup",
                            "view.focusPrevGroup", "view.focusLeft", "view.focusRight", "view.focusUp", "view.focusDown",
                            "-", "view.moveRight", "view.moveLeft");
        JMenu runMenu = menu("Run", "run.file", "run.breakpoint", "run.debug");
        JMenu prefMenu = menu("Preferences", "pref.autoComplete", "pref.autoClose", "pref.trimOnSave",
                              "pref.liveLint", "pref.restoreSession", "-", "lang.change", "lang.indent",
                              "lang.encoding", "lang.eol");

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
        CodeTab tab = lastUsed().getActiveTab();
        if (tab == null) return;

        com.sphere.components.editor.CodeTextPane area = tab.getEditorArea();
        int index = area.getText().indexOf(text, area.getCaretPosition());
        if (index >= 0) {
            area.setCaretPosition(index);
            area.select(index, index + text.length());
        }
    }

    /**
     * Gets the active file resource being handled by this editor viewport:
     * that of the window worked in last, when a group has a window of its own.
     * @return The open File reference context, or null if unassigned.
     */
    public File getCurrentFile() {
        return lastUsed().currentFile;
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
        if (debugPanel == null) {
            root().setDebugPanelVisible(visible);
            if (visible) {
                root().bringToFront();
            }
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

    /**
     * Ends any live session, for when the editor window closes; the groups'
     * windows dock back and the layout is written down first.
     */
    public void shutdownDebugger() {
        if (main == null) {
            dockAllWindows();
        }
        if (debugPanel != null) {
            debugPanel.shutdown();
        }
        diskWatch.stop();
        chords.stop("");
        if (main == null) {
            CoderConfig.flush();
        }
        if (persistSession) {
            sessionSaver.stop();
            saveSession();
            EditorState.saveNow();
        }
    }

    /**
     * Follows the breakpoints of a file so a live session stays in step with
     * them; once per buffer, however many groups and windows show the file:
     * the main editor, which has the debugger, does it for its windows.
     */
    void watchBreakpoints(CodeTab tab) {
        if (main != null) {
            main.watchBreakpoints(tab);
            return;
        }
        if (tab == null || debugPanel == null || !watchedBuffers.add(tab.getBuffer())) {
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
            // Every view of the file, in every window, marks the line; the one in
            // the active group (or the first) comes forward.
            CodeTab forward = null;
            for (CodeTab tab : familyTabs()) {
                File tabFile = tab.getFile();
                boolean same = file != null && tabFile != null
                    && tabFile.getAbsolutePath().equals(file.getAbsolutePath());
                tab.setExecutionLine(same ? line : -1);
                if (same && line > 0 && (forward == null || groupOf(tab) == activeGroup)) {
                    forward = tab;
                }
            }
            if (forward != null && !isShownInGroup(forward)) {
                EditorGroup g = groupOf(forward);
                if (g != null) {
                    g.tabs.setSelectedComponent(forward);
                }
            }
        }
    }
}

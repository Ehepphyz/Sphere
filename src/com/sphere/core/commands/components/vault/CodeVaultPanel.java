package com.sphere.components.vault;

import com.sphere.components.vault.VaultQuery.Hit;
import com.sphere.components.vault.VaultQuery.Lens;
import com.sphere.components.vault.VaultQuery.Sort;
import com.sphere.components.vault.VaultRunner.Request;
import com.sphere.ui.SphereCoderFrame;
import com.sphere.utils.AppLogger;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Code Vault: snippets/ and user_scripts/ in one tab.
 *
 * One tab rather than two, because both hold code that is run from the console
 * and the questions asked of them are the same: where did I use TH1F, which
 * one draws, which of these is a copy of that one. The lens above the list
 * narrows to one folder when that is all that matters, and each kind keeps
 * what is its own: a ROOT macro runs in the engine or in root.exe, can be
 * checked by ACLiC, and its form is its function's parameters.
 *
 * The list follows the disk by itself. The search reads words and filters;
 * the order, when nothing is typed, is the user's own habits.
 *
 * The tab holds the list alone: a side panel has no room for more. A button
 * opens the Code Vault window, where the list sits beside everything about
 * the chosen item: its form, whose line is shown as it is written so nothing
 * runs that has not been read first, its code, its checks and its history.
 */
public final class CodeVaultPanel extends JPanel implements VaultIndex.Listener, VaultDetail.Actions, VaultList.Host {

    private final JTextField console;
    private final SphereCoderFrame editor;
    private final VaultIndex index = VaultIndex.shared();

    private final VaultLook.Field search = new VaultLook.Field("Search · #tag lang:py uses:TH1F is:twin");
    private final LensBar lensBar = new LensBar();
    private final JPanel chipBar = VaultLook.transparent(new JPanel(new VaultLook.WrapLayout(FlowLayout.LEFT, 4, 3)));
    private final DefaultListModel<Hit> model = new DefaultListModel<>();
    private final VaultList list = new VaultList(model, this);
    private final VaultDetail detail = new VaultDetail(this);
    private final JLabel status = new JLabel(" ");
    private final VaultLook.FlatButton undo = VaultLook.button("Undo", null, "Bring the file back", false);
    private final VaultLook.FlatButton sortButton = VaultLook.button("Smart", null, "Order of the list", false);
    private final JScrollPane listScroll;

    /**
     * The tab is COMPACT: the search and the list, nothing else, since a side
     * panel has no room for more. The manager, a window of its own opened from
     * the tab, is FULL: the list on the left and everything about the chosen
     * item on the right.
     */
    public enum Mode { COMPACT, FULL }

    private final Mode mode;
    /** The tab's panel, which the console reaches to open the manager. */
    private static volatile CodeVaultPanel docked;
    /** COMPACT only: the manager window, made the first time it is asked for. */
    private javax.swing.JDialog manager;
    private CodeVaultPanel managerPanel;
    private VaultLook.FlatButton managerButton;

    private Lens lens = Lens.ALL;
    private Sort sort = Sort.SMART;
    private final Set<String> families = new LinkedHashSet<>();
    private boolean onlyPinned;
    private boolean onlyIssues;
    private boolean onlyTwins;
    private boolean inProject;
    private VaultQuery query = VaultQuery.parse("");
    private Path pendingSelection;
    private VaultFiles.Trashed trashed;
    private final Timer undoTimer = new Timer(12_000, e -> hideUndo());
    private String chipSignature = "";

    /** The tab's panel: the search and the list, with a button for the manager. */
    public CodeVaultPanel(JTextField console, SphereCoderFrame editor) {
        this(console, editor, Mode.COMPACT);
        docked = this;
    }

    private CodeVaultPanel(JTextField console, SphereCoderFrame editor, Mode mode) {
        super(new BorderLayout());
        this.console = console;
        this.editor = editor;
        this.mode = mode;
        setOpaque(true);
        setBackground(VaultLook.panel());

        add(buildHeader(), BorderLayout.NORTH);

        listScroll = new VaultLook.ClearScroll(list,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.getVerticalScrollBar().setUnitIncrement(VaultList.ROW / 2);
        listScroll.setMinimumSize(new Dimension(0, VaultList.ROW));

        final java.util.prefs.Preferences prefs =
            java.util.prefs.Preferences.userNodeForPackage(CodeVaultPanel.class);
        // What the first versions wrote for a detail under the list, which is gone.
        prefs.remove("vault.split");
        prefs.remove("vault.list.px");
        if (mode == Mode.FULL) {
            // Only a hand on the divider moves it for good (PersistentSplitPane).
            final com.sphere.components.PersistentSplitPane split = new com.sphere.components.PersistentSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, listScroll, detail, prefs, "vault.manager", 0.36);
            split.setBorder(BorderFactory.createEmptyBorder());
            split.setDividerSize(5);
            split.setResizeWeight(0.3);
            split.setContinuousLayout(true);
            split.setOpaque(false);
            add(split, BorderLayout.CENTER);
        } else {
            add(listScroll, BorderLayout.CENTER);
        }

        add(buildStatus(), BorderLayout.SOUTH);

        installList();
        installKeys();
        installDrops();

        index.addListener(this);
        index.start();
        refresh();
    }

    // ---- the header -------------------------------------------------------------------

    private JComponent buildHeader() {
        final JPanel header = VaultLook.transparent(new JPanel());
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 2, 8));

        search.setGlyph("search");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { queryChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { queryChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { queryChanged(); }
        });
        final VaultLook.FlatButton create = VaultLook.button("", "plus", "New snippet or macro", false);
        create.addActionListener(e -> showCreateMenu(create));
        final VaultLook.FlatButton more = VaultLook.button("", "more", "Vault", false);
        more.addActionListener(e -> showVaultMenu(more));
        final JPanel row = VaultLook.transparent(new JPanel(new BorderLayout(4, 0)));
        row.add(search, BorderLayout.CENTER);
        final JPanel buttons = VaultLook.transparent(new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)));
        buttons.add(create);
        if (mode == Mode.COMPACT) {
            managerButton = VaultLook.button("", "window", "", false);
            managerButton.addActionListener(e -> toggleManager());
            updateManagerButton();
            buttons.add(managerButton);
        }
        buttons.add(more);
        row.add(buttons, BorderLayout.EAST);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        header.add(row);
        header.add(Box.createVerticalStrut(6));

        final JPanel lensRow = VaultLook.transparent(new JPanel(new BorderLayout(6, 0)));
        lensRow.add(lensBar, BorderLayout.CENTER);
        sortButton.setFont(VaultLook.ui(Font.PLAIN, 10.5f));
        sortButton.addActionListener(e -> showSortMenu(sortButton));
        lensRow.add(sortButton, BorderLayout.EAST);
        lensRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        lensRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        header.add(lensRow);

        chipBar.setBorder(BorderFactory.createEmptyBorder(3, -4, 0, -4));
        chipBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(chipBar);
        return header;
    }

    private JComponent buildStatus() {
        final JPanel bar = VaultLook.transparent(new JPanel(new BorderLayout()));
        bar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, VaultLook.mix(VaultLook.text(), VaultLook.panel(), 0.08f)),
            BorderFactory.createEmptyBorder(3, 10, 3, 6)));
        status.setFont(VaultLook.ui(Font.PLAIN, 10.5f));
        status.setForeground(VaultLook.faint());
        bar.add(status, BorderLayout.CENTER);
        undo.setFont(VaultLook.ui(Font.BOLD, 10.5f));
        undo.setForeground(VaultLook.accent());
        undo.setVisible(false);
        undo.addActionListener(e -> restoreTrashed());
        bar.add(undo, BorderLayout.EAST);
        undoTimer.setRepeats(false);
        return bar;
    }

    // ---- the manager window ------------------------------------------------------------

    /** Opens the manager, or closes it when it is open. */
    private void toggleManager() {
        if (manager != null && manager.isVisible()) {
            manager.setVisible(false);
        } else {
            openManager(selectedItem());
        }
    }

    /**
     * Opens the manager, a window of its own beside Sphere's, on an item when
     * one is given. It is made once and then hidden and shown again, so what
     * was typed in it is still there; its place and size are kept between
     * sessions.
     */
    void openManager(VaultItem item) {
        if (mode != Mode.COMPACT) return;
        if (manager == null) {
            final java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
            manager = new javax.swing.JDialog(owner, "Code Vault", java.awt.Dialog.ModalityType.MODELESS);
            managerPanel = new CodeVaultPanel(console, editor, Mode.FULL);
            manager.setContentPane(managerPanel);
            manager.setDefaultCloseOperation(javax.swing.WindowConstants.HIDE_ON_CLOSE);
            com.sphere.utils.IconManager.applyAppIcon(manager);
            placeManager(owner);
            manager.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentHidden(java.awt.event.ComponentEvent e) {
                    updateManagerButton();
                }

                @Override
                public void componentShown(java.awt.event.ComponentEvent e) {
                    updateManagerButton();
                }

                @Override
                public void componentMoved(java.awt.event.ComponentEvent e) {
                    keepManagerBounds();
                }

                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    keepManagerBounds();
                }
            });
        }
        manager.setVisible(true);
        manager.toFront();
        if (item != null) managerPanel.select(item);
        updateManagerButton();
    }

    /** Where the manager was left, or beside the main window the first time. */
    private void placeManager(java.awt.Window owner) {
        final java.util.prefs.Preferences prefs =
            java.util.prefs.Preferences.userNodeForPackage(CodeVaultPanel.class);
        final int w = prefs.getInt("vault.manager.w", 980);
        final int h = prefs.getInt("vault.manager.h", 640);
        manager.setSize(Math.max(520, w), Math.max(360, h));
        final int x = prefs.getInt("vault.manager.x", Integer.MIN_VALUE);
        final int y = prefs.getInt("vault.manager.y", Integer.MIN_VALUE);
        final java.awt.Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getMaximumWindowBounds();
        if (x != Integer.MIN_VALUE && screen.intersects(new java.awt.Rectangle(x, y, manager.getWidth(), 40))) {
            manager.setLocation(x, y);
        } else {
            manager.setLocationRelativeTo(owner);
        }
    }

    private void keepManagerBounds() {
        if (manager == null || !manager.isShowing()) return;
        final java.util.prefs.Preferences prefs =
            java.util.prefs.Preferences.userNodeForPackage(CodeVaultPanel.class);
        prefs.putInt("vault.manager.x", manager.getX());
        prefs.putInt("vault.manager.y", manager.getY());
        prefs.putInt("vault.manager.w", manager.getWidth());
        prefs.putInt("vault.manager.h", manager.getHeight());
    }

    private void updateManagerButton() {
        if (managerButton == null) return;
        final boolean open = manager != null && manager.isVisible();
        managerButton.setIcon(VaultLook.glyph(open ? "window-open" : "window", 13,
            open ? VaultLook.accent() : VaultLook.muted()));
        managerButton.setToolTipText(open ? "Close the Code Vault window"
            : "Open the Code Vault window: arguments, code, checks and history");
    }

    /** The tab's panel opens its manager, for the console's :vault open. */
    static void openDocked(VaultItem item) {
        final CodeVaultPanel tab = docked;
        if (tab == null) {
            AppLogger.error("The Code Vault tab is not there to open its window.");
            return;
        }
        SwingUtilities.invokeLater(() -> tab.openManager(item));
    }

    // ---- refreshing ---------------------------------------------------------------------

    @Override
    public void vaultChanged() {
        refresh();
    }

    private void queryChanged() {
        query = VaultQuery.parse(effectiveQuery());
        // Nothing is chosen for the user while they type: the detail would open
        // and take the results' room. Enter runs the first one all the same.
        refresh();
    }

    private String effectiveQuery() {
        final StringBuilder q = new StringBuilder(search.getText());
        if (onlyPinned) q.append(" is:pinned");
        if (onlyIssues) q.append(" is:issue");
        if (onlyTwins) q.append(" is:twin");
        return q.toString();
    }

    private void refresh() {
        final VaultItem selected = selectedItem();
        final Path keep = pendingSelection != null ? pendingSelection : selected == null ? null : selected.path;

        final List<VaultItem> items = index.items();
        // A family that no longer has anything in this lens stops filtering.
        families.retainAll(VaultQuery.families(items, lens));
        final List<Hit> hits = query.run(items, lens, families, sort, index);

        model.clear();
        int reselect = -1;
        for (int i = 0; i < hits.size(); i++) {
            model.addElement(hits.get(i));
            if (keep != null && hits.get(i).item().path.equals(keep)) reselect = i;
        }
        if (reselect >= 0) {
            list.setSelectedIndex(reselect);
            list.ensureIndexIsVisible(reselect);
            if (pendingSelection != null && pendingSelection.equals(keep)) pendingSelection = null;
        } else if (mode == Mode.FULL && !model.isEmpty() && pendingSelection == null) {
            // The window has the room: its detail always shows something.
            list.setSelectedIndex(0);
        } else {
            list.clearSelection();
            detail.show(null);
        }
        lensBar.repaint();
        rebuildChips(items);
        updateStatus(hits.size());
    }

    private void rebuildChips(List<VaultItem> items) {
        final List<String> present = VaultQuery.families(items, lens);
        final VaultIndex.Stats stats = index.stats();
        int pinned = 0;
        for (VaultItem item : items) if (index.state().pinned(item.key())) pinned++;
        final StringBuilder signature = new StringBuilder(lens.name());
        for (String f : present) signature.append(f).append(VaultQuery.count(items, lens, f)).append(families.contains(f));
        signature.append(pinned).append(onlyPinned).append(stats.problems()).append(onlyIssues)
            .append(stats.twins()).append(onlyTwins);
        if (signature.toString().equals(chipSignature)) return;
        chipSignature = signature.toString();

        chipBar.removeAll();
        for (String family : present) {
            final VaultLook.Chip chip = new VaultLook.Chip(family + " " + VaultQuery.count(items, lens, family),
                VaultLook.family(family), null, "Show only " + family);
            chip.setSelected(families.contains(family));
            chip.addActionListener(e -> {
                if (chip.isSelected()) families.add(family);
                else families.remove(family);
                chipSignature = "";
                refresh();
            });
            chipBar.add(chip);
        }
        if (pinned > 0 || onlyPinned) chipBar.add(flagChip(String.valueOf(pinned), "star-filled",
            VaultLook.warning(), "Pinned only", onlyPinned, on -> onlyPinned = on));
        if (stats.problems() > 0 || onlyIssues) chipBar.add(flagChip(String.valueOf(stats.problems()), "warning",
            VaultLook.warning(), "Only what has a warning or an error", onlyIssues, on -> onlyIssues = on));
        if (stats.twins() > 0 || onlyTwins) chipBar.add(flagChip(String.valueOf(stats.twins()), "twin",
            VaultLook.info(), "Only files whose content another file repeats", onlyTwins, on -> onlyTwins = on));
        chipBar.revalidate();
        chipBar.repaint();
    }

    private VaultLook.Chip flagChip(String text, String glyph, java.awt.Color color, String tip, boolean on,
                                    java.util.function.Consumer<Boolean> set) {
        final VaultLook.Chip chip = new VaultLook.Chip(text, color, glyph, tip);
        chip.setSelected(on);
        chip.addActionListener(e -> {
            set.accept(chip.isSelected());
            chipSignature = "";
            queryChanged();
        });
        return chip;
    }

    private void updateStatus(int shown) {
        final VaultIndex.Stats s = index.stats();
        final StringBuilder text = new StringBuilder();
        if (shown != countIn(lens)) text.append(shown).append(" of ");
        text.append(s.snippets()).append(" snippet").append(s.snippets() == 1 ? "" : "s")
            .append(" · ").append(s.macros()).append(" macro").append(s.macros() == 1 ? "" : "s");
        if (index.project() != null) text.append(" · project ").append(index.project());
        status.setText(text.toString());
        status.setToolTipText("Indexed in " + s.millis() + " ms. Folders: "
            + String.join(", ", index.roots().stream().map(r -> r.dir().toString()).toList()));
    }

    private int countIn(Lens which) {
        final VaultIndex.Stats s = index.stats();
        return switch (which) {
            case ALL -> s.items();
            case SNIPPETS -> s.snippets();
            case MACROS -> s.macros();
        };
    }

    // ---- the list ----------------------------------------------------------------------------

    private VaultItem selectedItem() {
        final Hit hit = list.getSelectedValue();
        return hit == null ? null : hit.item();
    }

    private void installList() {
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            final VaultItem chosen = selectedItem();
            if (mode == Mode.FULL) {
                detail.show(chosen);
            } else if (chosen != null && manager != null && manager.isVisible()) {
                // The open window follows what is chosen in the tab.
                managerPanel.select(chosen);
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                final int i = list.locationToIndex(e.getPoint());
                final boolean onRow = i >= 0 && list.getCellBounds(i, i) != null
                    && list.getCellBounds(i, i).contains(e.getPoint());
                if (SwingUtilities.isRightMouseButton(e)) {
                    if (onRow) {
                        list.setSelectedIndex(i);
                        more(model.get(i).item(), list, e.getX(), e.getY());
                    } else {
                        showCreateMenuAt(list, e.getX(), e.getY());
                    }
                } else if (!onRow) {
                    list.clearSelection();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                final VaultItem item = selectedItem();
                if (item == null) return;
                if (e.getClickCount() == 1 && list.onPlay(e.getPoint())) {
                    run(item, detail.request(item));
                } else if (e.getClickCount() == 2 && !list.onPlay(e.getPoint())) {
                    // As in the old Snippets tab: the tag goes into the console at
                    // the caret, and the console resolves it to the file's path.
                    insertReference(item);
                }
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                // Typing in the list goes on in the search.
                final char c = e.getKeyChar();
                if (c >= ' ' && c != 127 && (e.getModifiersEx() & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) == 0) {
                    search.requestFocusInWindow();
                    search.setText(search.getText() + c);
                    e.consume();
                }
            }
        });
    }

    private void installKeys() {
        bind(list, "ENTER", "run", () -> withSelected(item -> run(item, detail.request(item))));
        bind(list, "ctrl ENTER", "compose", () -> withSelected(item -> compose(item, detail.request(item))));
        bind(list, "F2", "rename", () -> withSelected(this::rename));
        bind(list, "DELETE", "trash", () -> withSelected(this::trash));
        bind(list, "ctrl E", "edit", () -> withSelected(this::edit));
        bind(list, "ctrl D", "duplicate", () -> withSelected(this::duplicate));
        // Esc closes the detail first, then goes back to the search.
        bind(list, "ESCAPE", "back", () -> {
            if (list.getSelectedIndex() >= 0) list.clearSelection();
            else search.requestFocusInWindow();
        });

        bind(search, "DOWN", "down", () -> moveSelection(1));
        bind(search, "UP", "up", () -> moveSelection(-1));
        bind(search, "ENTER", "run", () -> {
            final VaultItem item = selectedItem() != null ? selectedItem() : model.isEmpty() ? null : model.get(0).item();
            if (item != null) run(item, detail.request(item));
            else if (query.hasWords()) createFromSearch();
        });
        bind(search, "ctrl ENTER", "compose", () -> withSelected(item -> compose(item, detail.request(item))));
        bind(search, "ESCAPE", "clear", () -> {
            if (!search.getText().isEmpty()) {
                search.setText("");
            } else if (!families.isEmpty() || onlyIssues || onlyPinned || onlyTwins) {
                families.clear();
                onlyIssues = onlyPinned = onlyTwins = false;
                chipSignature = "";
                queryChanged();
            }
        });

        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ctrl F"), "vault-search");
        getActionMap().put("vault-search", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                search.requestFocusInWindow();
                search.selectAll();
            }
        });
    }

    private void moveSelection(int step) {
        if (model.isEmpty()) return;
        final int next = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + step));
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    private static void bind(JComponent target, String key, String name, Runnable action) {
        target.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "vault-" + name);
        target.getActionMap().put("vault-" + name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void withSelected(java.util.function.Consumer<VaultItem> action) {
        final VaultItem item = selectedItem();
        if (item != null) action.accept(item);
    }

    // ---- drag and drop -----------------------------------------------------------------------

    private void installDrops() {
        list.setDragEnabled(true);
        final TransferHandler handler = new TransferHandler() {
            @Override
            public int getSourceActions(JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(JComponent c) {
                final VaultItem item = selectedItem();
                if (item == null) return null;
                return new ItemTransfer(item, VaultRunner.tag(item, project()));
            }

            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                    && !support.isDataFlavorSupported(ItemTransfer.LOCAL);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    @SuppressWarnings("unchecked")
                    final List<File> files = (List<File>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                    importFiles(files);
                    return true;
                } catch (UnsupportedFlavorException | IOException | ClassCastException failed) {
                    flash("Could not import: " + failed.getMessage());
                    return false;
                }
            }
        };
        list.setTransferHandler(handler);
        setTransferHandler(handler);
    }

    /** An item carried by a drag: its bracket as text, its file as a file. */
    private static final class ItemTransfer implements Transferable {
        static final DataFlavor LOCAL = new DataFlavor(VaultItem.class, "Sphere vault item");
        private final VaultItem item;
        private final String text;

        ItemTransfer(VaultItem item, String text) {
            this.item = item;
            this.text = text;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{LOCAL, DataFlavor.stringFlavor, DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return flavor.equals(LOCAL) || flavor.equals(DataFlavor.stringFlavor)
                || flavor.equals(DataFlavor.javaFileListFlavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (flavor.equals(LOCAL)) return item;
            if (flavor.equals(DataFlavor.stringFlavor)) return text;
            if (flavor.equals(DataFlavor.javaFileListFlavor)) return List.of(item.path.toFile());
            throw new UnsupportedFlavorException(flavor);
        }
    }

    private void importFiles(List<File> files) {
        final List<File> macros = new ArrayList<>();
        final List<File> snippets = new ArrayList<>();
        for (File f : files) {
            final boolean macro = lens == Lens.MACROS
                || (lens == Lens.ALL && f.isFile() && f.getName().matches(".*\\.(C|h|hpp|hxx)$"));
            (macro ? macros : snippets).add(f);
        }
        final VaultItem.Layer layer = inProject && index.project() != null ? VaultItem.Layer.PROJECT : VaultItem.Layer.GLOBAL;
        int done = 0;
        try {
            if (!snippets.isEmpty()) done += VaultFiles.importFiles(snippets, index.rootFor(VaultItem.Kind.SNIPPET, layer)).size();
            if (!macros.isEmpty()) done += VaultFiles.importFiles(macros, index.rootFor(VaultItem.Kind.MACRO, layer)).size();
            flash("Imported " + done + " file" + (done == 1 ? "" : "s") + ".");
            if (files.size() == 1 && files.get(0).isFile()) {
                pendingSelection = (macros.isEmpty() ? index.rootFor(VaultItem.Kind.SNIPPET, layer)
                    : index.rootFor(VaultItem.Kind.MACRO, layer)).resolve(files.get(0).getName()).normalize();
            }
        } catch (IOException failed) {
            flash("Import stopped: " + failed.getMessage());
        }
        index.rescan();
    }

    // ---- what the detail asks for (VaultDetail.Actions) -----------------------------------

    @Override
    public void run(VaultItem item, Request request) {
        if (!VaultRunner.runnable(item)) {
            compose(item, request);
            return;
        }
        final String why = VaultRunner.problem(item, request);
        if (why != null) {
            flash(why + ".");
            return;
        }
        final String command = VaultRunner.command(item, request, project());
        if (!VaultRunner.submit(command)) {
            flash("The Sphere console is not running.");
            return;
        }
        detail.remember(item, request);
        index.state().used(item.key());
        flash("Ran " + item.name());
        list.repaint();
    }

    /**
     * Puts the line in the console, to be read or changed before Enter.
     *
     * A line already started with a prefix ("::py -O ") is completed with the
     * bracket alone; an empty one gets the whole line; one holding something
     * else gets the bracket where the caret is, as the old panel did.
     */
    @Override
    public void compose(VaultItem item, Request request) {
        if (console == null) {
            flash("No console to write into.");
            return;
        }
        final String current = console.getText() == null ? "" : console.getText();
        final String trimmed = current.strip();
        final String full = VaultRunner.command(item, request, project());
        final String block = VaultRunner.usesEngine(item, request) || full == null
            ? null : VaultRunner.block(item, request, project());
        if (trimmed.isEmpty()) {
            console.setText(full != null ? full : VaultRunner.block(item, request, project()));
            console.setCaretPosition(console.getText().length());
        } else if (block != null && trimmed.startsWith("::") && !trimmed.contains("[@")) {
            console.setText(current.stripTrailing() + " " + block);
            console.setCaretPosition(console.getText().length());
        } else {
            final String insert = block != null ? block : full;
            final int caret = Math.min(console.getCaretPosition(), current.length());
            final String before = current.substring(0, caret);
            final String spaced = (before.isEmpty() || before.endsWith(" ") ? "" : " ") + insert;
            console.setText(before + spaced + current.substring(caret));
            console.setCaretPosition(caret + spaced.length());
        }
        detail.remember(item, request);
        focusConsole();
    }

    /**
     * Puts the caret in the console, so Enter runs the line. From the Code Vault
     * window the console is in another window, which focusing in place would
     * not reach: Sphere's window is brought forward, the vault's stays above it.
     */
    private void focusConsole() {
        final java.awt.Window window = SwingUtilities.getWindowAncestor(console);
        if (window != null && !window.isFocused()) {
            console.requestFocus();
        } else {
            console.requestFocusInWindow();
        }
    }

    @Override
    public void edit(VaultItem item) {
        open(item.path);
    }

    private void open(Path file) {
        final SphereCoderFrame window = editor != null ? editor : com.sphere.Sphere.editorWindow();
        if (window != null) window.openFileInternally(file.toFile());
        else flash("The editor is not available.");
    }

    @Override
    public void fix(VaultItem item, VaultAnalysis.Fix fix) {
        try {
            final Path after = VaultFiles.apply(item, fix);
            if (!after.equals(item.path)) {
                final VaultIndex.Root root = index.rootOf(after);
                if (root != null) {
                    final String key = root.kind().word + ":" + root.layer().name().toLowerCase(Locale.ROOT) + ":"
                        + root.dir().relativize(after).toString().replace('\\', '/');
                    index.state().rename(item.key(), key);
                }
            }
            pendingSelection = after;
            flash(fix.label() + ": done.");
            index.rescan();
        } catch (IOException failed) {
            flash(fix.label() + " failed: " + failed.getMessage());
        }
    }

    @Override
    public void filter(String token) {
        final String text = search.getText().strip();
        if ((" " + text + " ").contains(" " + token + " ")) return;
        search.setText((text + " " + token).strip() + " ");
        search.requestFocusInWindow();
    }

    @Override
    public void select(VaultItem item) {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).item().path.equals(item.path)) {
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                return;
            }
        }
        // Hidden by the filters: they go, the item shows.
        families.clear();
        onlyIssues = onlyPinned = onlyTwins = false;
        lens = Lens.ALL;
        pendingSelection = item.path;
        chipSignature = "";
        search.setText("");
        queryChanged();
    }

    @Override
    public void togglePin(VaultItem item) {
        final VaultState state = index.state();
        state.setPinned(item.key(), !state.pinned(item.key()));
        chipSignature = "";
        refresh();
        if (mode == Mode.FULL) detail.show(item);
    }

    @Override
    public void send(VaultItem item, String command) {
        if (!VaultRunner.submit(command)) flash("The Sphere console is not running.");
        else flash("Sent to the engine: " + item.name());
    }

    @Override
    public String project() {
        return index.project();
    }

    @Override
    public boolean engineRunning() {
        try {
            final com.sphere.core.CommandRouter router = com.sphere.core.CommandRouter.active();
            return router != null && router.getRootBackend() != null && router.getRootBackend().isAvailable();
        } catch (RuntimeException notThere) {
            return false;
        }
    }

    @Override
    public VaultIndex index() {
        return index;
    }

    // ---- VaultList.Host --------------------------------------------------------------------

    @Override
    public String emptyTitle() {
        if (index.stats().items() == 0) return "The vault is empty";
        return query.isEmpty() && families.isEmpty() ? "Nothing in " + lensWord() : "Nothing matches";
    }

    @Override
    public String emptyHint() {
        if (index.stats().items() == 0) {
            return "Press + for a snippet or a ROOT macro,\nor drop files here.";
        }
        if (query.hasWords()) {
            return "Enter makes " + suggestedName() + "\nEsc clears the search.";
        }
        return "Esc clears the filters. + makes a new one.";
    }

    private String lensWord() {
        return switch (lens) {
            case ALL -> "the vault";
            case SNIPPETS -> "snippets/";
            case MACROS -> "user_scripts/";
        };
    }

    // ---- the context menu -------------------------------------------------------------------

    @Override
    public void more(VaultItem item, Component anchor, int x, int y) {
        final JPopupMenu menu = new JPopupMenu();
        final Request r = detail.request(item);
        if (VaultRunner.runnable(item)) menu.add(entry("Run", "ENTER", () -> run(item, r)));
        if (mode == Mode.COMPACT) {
            menu.add(entry("Open in the Code Vault window", null, () -> openManager(item)));
        }
        menu.add(entry("Insert [@ " + item.name() + "] in the console", null, () -> insertReference(item)));
        menu.add(entry("Write the whole line in the console", "ctrl ENTER", () -> compose(item, r)));
        if (item.isRootMacro()) {
            menu.addSeparator();
            menu.add(entry("Check with ACLiC (.L +)", null, () -> send(item, VaultRunner.compile(item, r))));
            menu.add(entry("Load in the engine (.L)", null, () -> send(item, VaultRunner.load(item, r))));
        }
        menu.addSeparator();
        menu.add(entry("Open in the editor", "ctrl E", () -> edit(item)));
        menu.add(entry("Show in its folder", null, () -> reveal(item.path)));
        menu.add(entry("Copy the path", null, () -> com.sphere.components.ClipboardBridge.write(item.path.toString())));
        final String command = VaultRunner.command(item, r, project());
        if (command != null) menu.add(entry("Copy the line", null, () -> com.sphere.components.ClipboardBridge.write(command)));
        menu.addSeparator();
        final boolean pinned = index.state().pinned(item.key());
        menu.add(entry(pinned ? "Unpin" : "Pin to the top", null, () -> togglePin(item)));
        menu.add(entry("Duplicate", "ctrl D", () -> duplicate(item)));
        menu.add(entry("Rename...", "F2", () -> rename(item)));
        final VaultItem.Kind other = item.kind == VaultItem.Kind.SNIPPET ? VaultItem.Kind.MACRO : VaultItem.Kind.SNIPPET;
        menu.add(entry("Move to " + other.folder + "/", null, () -> moveTo(item, other)));
        boolean fixes = false;
        for (VaultAnalysis.Issue issue : item.info.issues) {
            if (issue.fix() == null) continue;
            if (!fixes) menu.addSeparator();
            fixes = true;
            menu.add(entry("Fix: " + issue.fix().label(), null, () -> fix(item, issue.fix())));
        }
        menu.addSeparator();
        menu.add(entry("Move to the trash", "DELETE", () -> trash(item)));
        menu.show(anchor, x, y);
    }

    private static JMenuItem entry(String text, String key, Runnable action) {
        final JMenuItem item = new JMenuItem(text);
        if (key != null) item.setAccelerator(KeyStroke.getKeyStroke(key));
        item.addActionListener(e -> action.run());
        return item;
    }

    /**
     * Puts [@ name.ext] into the console where the caret is: a pointer the
     * console turns into the file's path, so "::py -O " followed by a
     * double-click reads "::py -O [@ gen.py]". A space is put before it when
     * it would otherwise stick to the word in front.
     */
    private void insertReference(VaultItem item) {
        if (console == null) {
            flash("No console to write into.");
            return;
        }
        final String tag = VaultRunner.tag(item, project());
        final String current = console.getText() == null ? "" : console.getText();
        final int caret = Math.max(0, Math.min(console.getCaretPosition(), current.length()));
        final String before = current.substring(0, caret);
        final String after = current.substring(caret);
        final String insert = (before.isEmpty() || Character.isWhitespace(before.charAt(before.length() - 1)) ? "" : " ")
            + tag + (after.isEmpty() || Character.isWhitespace(after.charAt(0)) ? "" : " ");
        console.setText(before + insert + after);
        console.setCaretPosition(caret + insert.length());
        focusConsole();
        index.state().used(item.key());
    }

    // ---- creating ------------------------------------------------------------------------------

    private void showCreateMenu(Component anchor) {
        showCreateMenuAt(anchor, 0, anchor.getHeight());
    }

    private void showCreateMenuAt(Component anchor, int x, int y) {
        final JPopupMenu menu = new JPopupMenu();
        for (VaultFiles.Template template : VaultFiles.Template.values()) {
            final VaultItem.Kind kind = kindFor(template);
            menu.add(entry(template.label + "   in " + kind.folder + "/", null, () -> create(template, kind, null)));
        }
        if (index.project() != null) {
            menu.addSeparator();
            final JCheckBoxMenuItem here = new JCheckBoxMenuItem("In project " + index.project(), inProject);
            here.addActionListener(e -> inProject = here.isSelected());
            menu.add(here);
        }
        menu.show(anchor, x, y);
    }

    private VaultItem.Kind kindFor(VaultFiles.Template template) {
        return switch (lens) {
            case SNIPPETS -> VaultItem.Kind.SNIPPET;
            case MACROS -> VaultItem.Kind.MACRO;
            case ALL -> template.kind;
        };
    }

    private void create(VaultFiles.Template template, VaultItem.Kind kind, String suggested) {
        final String initial = suggested != null ? suggested
            : template == VaultFiles.Template.ROOT_MACRO ? "myMacro" : "new_snippet" + template.extension;
        final String name = ask("New " + template.label.toLowerCase(Locale.ROOT) + " in " + kind.folder + "/",
            "Name (a folder/name makes the folder too):", initial);
        if (name == null || name.isBlank()) return;
        final VaultItem.Layer layer = inProject && index.project() != null ? VaultItem.Layer.PROJECT : VaultItem.Layer.GLOBAL;
        try {
            final Path made = VaultFiles.create(index.rootFor(kind, layer), name, template);
            pendingSelection = made;
            if (!search.getText().isBlank()) search.setText("");
            index.rescan();
            flash("Made " + made.getFileName());
            open(made);
        } catch (IOException failed) {
            flash("Could not make it: " + failed.getMessage());
        }
    }

    /** Enter on a search that found nothing makes a file of that name. */
    private void createFromSearch() {
        final VaultFiles.Template template = lens == Lens.MACROS ? VaultFiles.Template.ROOT_MACRO
            : guessTemplate(query.firstWord());
        create(template, kindFor(template), suggestedName());
    }

    private String suggestedName() {
        final String word = query.firstWord().replaceAll("[^\\w./-]", "_");
        if (word.isEmpty()) return "new_snippet.py";
        if (word.contains(".")) return word;
        final VaultFiles.Template t = lens == Lens.MACROS ? VaultFiles.Template.ROOT_MACRO : guessTemplate(word);
        return (t == VaultFiles.Template.ROOT_MACRO ? VaultFiles.identifier(word) : word) + t.extension;
    }

    private VaultFiles.Template guessTemplate(String word) {
        final String w = word.toLowerCase(Locale.ROOT);
        if (w.endsWith(".c")) return word.endsWith(".C") ? VaultFiles.Template.ROOT_MACRO : VaultFiles.Template.CPP;
        if (w.endsWith(".cpp") || w.endsWith(".cc") || w.endsWith(".cxx")) return VaultFiles.Template.CPP;
        if (w.endsWith(".jl")) return VaultFiles.Template.JULIA;
        if (w.endsWith(".f90") || w.endsWith(".f")) return VaultFiles.Template.FORTRAN;
        if (w.endsWith(".js")) return VaultFiles.Template.JS;
        if (families.size() == 1) {
            switch (families.iterator().next()) {
                case "ROOT" -> { return VaultFiles.Template.ROOT_MACRO; }
                case "C++" -> { return VaultFiles.Template.CPP; }
                case "JL" -> { return VaultFiles.Template.JULIA; }
                case "F90" -> { return VaultFiles.Template.FORTRAN; }
                case "JS" -> { return VaultFiles.Template.JS; }
                default -> { }
            }
        }
        return VaultFiles.Template.PYTHON;
    }

    // ---- changing files ---------------------------------------------------------------------------

    private void rename(VaultItem item) {
        final String name = ask("Rename " + item.name(), "New name:", item.name());
        if (name == null || name.isBlank() || name.equals(item.name())) return;
        try {
            final Path after = VaultFiles.rename(item, name);
            index.state().rename(item.key(), item.kind.word + ":" + item.layer.name().toLowerCase(Locale.ROOT) + ":"
                + item.root.relativize(after).toString().replace('\\', '/'));
            pendingSelection = after;
            index.rescan();
            if (item.isRootMacro() && item.info.entry != null && !after.getFileName().toString().startsWith(item.info.entry + ".")) {
                flash("Renamed. .x calls the function named as the file: rename " + item.info.entry + "() too.");
            } else {
                flash("Renamed to " + after.getFileName());
            }
        } catch (IOException failed) {
            flash("Could not rename: " + failed.getMessage());
        }
    }

    private void duplicate(VaultItem item) {
        try {
            final Path copy = VaultFiles.duplicate(item);
            pendingSelection = copy;
            index.rescan();
            flash("Copied to " + copy.getFileName());
        } catch (IOException failed) {
            flash("Could not copy: " + failed.getMessage());
        }
    }

    private void moveTo(VaultItem item, VaultItem.Kind kind) {
        try {
            final Path after = VaultFiles.moveTo(item, index.rootFor(kind, item.layer));
            index.state().rename(item.key(), kind.word + ":" + item.layer.name().toLowerCase(Locale.ROOT) + ":"
                + after.getFileName());
            pendingSelection = after;
            if (lens != Lens.ALL) lens = kind == VaultItem.Kind.MACRO ? Lens.MACROS : Lens.SNIPPETS;
            chipSignature = "";
            index.rescan();
            flash("Moved to " + kind.folder + "/");
        } catch (IOException failed) {
            flash("Could not move: " + failed.getMessage());
        }
    }

    private void trash(VaultItem item) {
        try {
            trashed = VaultFiles.trash(item);
            index.rescan();
            status.setText("Moved " + item.name() + " to the trash.");
            undo.setVisible(true);
            undoTimer.restart();
        } catch (IOException failed) {
            flash("Could not move it: " + failed.getMessage());
        }
    }

    private void restoreTrashed() {
        if (trashed == null) return;
        try {
            VaultFiles.restore(trashed);
            pendingSelection = trashed.from();
            flash("Back: " + trashed.from().getFileName());
            index.rescan();
        } catch (IOException failed) {
            flash("Could not bring it back: " + failed.getMessage());
        }
        trashed = null;
        hideUndo();
    }

    private void hideUndo() {
        undo.setVisible(false);
        updateStatus(model.size());
    }

    // ---- the other menus ------------------------------------------------------------------------

    private void showSortMenu(Component anchor) {
        final JPopupMenu menu = new JPopupMenu();
        for (Sort s : Sort.values()) {
            final JCheckBoxMenuItem item = new JCheckBoxMenuItem(s.label, s == sort);
            item.addActionListener(e -> {
                sort = s;
                sortButton.setText(s.label);
                refresh();
            });
            menu.add(item);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void showVaultMenu(Component anchor) {
        final JPopupMenu menu = new JPopupMenu();
        menu.add(entry("Read the folders again", null, () -> {
            index.rescan();
            flash("Reindexing...");
        }));
        final JMenu folders = new JMenu("Open a folder");
        for (VaultIndex.Root root : index.roots()) {
            folders.add(entry((root.layer() == VaultItem.Layer.PROJECT ? "project " : "global ") + root.kind().folder + "/",
                null, () -> reveal(root.dir())));
        }
        menu.add(folders);
        menu.addSeparator();
        menu.add(entry("Show the twins", null, () -> {
            onlyTwins = true;
            chipSignature = "";
            queryChanged();
        }));
        menu.add(entry("Show what has an issue", null, () -> {
            onlyIssues = true;
            chipSignature = "";
            queryChanged();
        }));
        menu.addSeparator();
        menu.add(entry("Search syntax...", null, this::showHelp));
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void showHelp() {
        final String text = """
            Words are matched against the name first, then the path, the description,
            the tags, the functions, the imports and includes, and the content.

            Filters (a leading - excludes):
              #plot  tag:mg5        tagged so (@tags in the first comment block)
              lang:py  lang:root    the language; root is a ROOT macro
              uses:TH1F  uses:numpy what it includes, imports or uses
              fn:analyse            a function it defines
              in:project  in:global in:MG5Workflow
              is:macro  is:snippet  is:twin  is:issue  is:pinned  is:args  is:plot

            Metadata a file carries in its first comment block:
              a first sentence      the description shown in the list
              @tags a, b            @desc one line      @opts -u -O
              @arg n:int=10 help    @arg --mode:fast|full=fast
              @runner py            run with ::py whatever the extension

            In the console: a double-click puts [@ name.ext] at the caret, which the
            console resolves to the file's path, so "::py -O " then a double-click
            reads "::py -O [@ gen.py]". Ctrl+Enter writes the whole line instead.

            Running: the options field is outside [@ ... ], for the interpreter;
            the form and More are inside, for the script. A ROOT macro runs in the
            engine (.x macro.C(args), its objects stay) or in root.exe (::root).
            """;
        final javax.swing.JTextArea area = new javax.swing.JTextArea(text);
        area.setEditable(false);
        area.setFont(VaultLook.mono(11.5f));
        JOptionPane.showMessageDialog(this, area, "Code Vault", JOptionPane.PLAIN_MESSAGE);
    }

    // ---- small things -----------------------------------------------------------------------------

    private String ask(String title, String prompt, String initial) {
        final Object answer = JOptionPane.showInputDialog(this, prompt, title, JOptionPane.PLAIN_MESSAGE, null, null, initial);
        return answer == null ? null : answer.toString().strip();
    }

    private void reveal(Path path) {
        try {
            final Path target = Files.isRegularFile(path) ? path.getParent() : path;
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(target.toFile());
        } catch (IOException | RuntimeException failed) {
            flash("Could not open the folder: " + failed.getMessage());
        }
    }

    private void flash(String message) {
        status.setText(message);
        status.setForeground(VaultLook.muted());
        final Timer back = new Timer(5000, e -> {
            status.setForeground(VaultLook.faint());
            if (!undo.isVisible()) updateStatus(model.size());
        });
        back.setRepeats(false);
        back.start();
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(VaultLook.panel());
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    // ---- the lens -----------------------------------------------------------------------------------

    /** All, Snippets, Macros, each with its count, as one segmented control. */
    private final class LensBar extends JComponent {
        LensBar() {
            setPreferredSize(new Dimension(100, 24));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("All · snippets/ · user_scripts/");
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    final int i = Math.max(0, Math.min(2, e.getX() * 3 / Math.max(1, getWidth())));
                    final Lens next = Lens.values()[i];
                    if (next != lens) {
                        lens = next;
                        chipSignature = "";
                        refresh();
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                VaultLook.antialias(g);
                final int w = getWidth();
                final int h = getHeight();
                g.setColor(VaultLook.field());
                g.fill(new RoundRectangle2D.Float(0, 0, w, h, 9, 9));
                final String[] names = {"All", "Snippets", "Macros"};
                final Lens[] lenses = Lens.values();
                final float cell = w / 3f;
                for (int i = 0; i < 3; i++) {
                    final boolean on = lenses[i] == lens;
                    if (on) {
                        g.setColor(VaultLook.surface());
                        g.fill(new RoundRectangle2D.Float(i * cell + 2, 2, cell - 4, h - 4, 7, 7));
                        g.setColor(VaultLook.mix(VaultLook.accent(), VaultLook.surface(), 0.55f));
                        g.draw(new RoundRectangle2D.Float(i * cell + 2.5f, 2.5f, cell - 5, h - 5, 7, 7));
                    }
                    final String label = names[i];
                    final String count = String.valueOf(countIn(lenses[i]));
                    g.setFont(VaultLook.ui(on ? Font.BOLD : Font.PLAIN, 11f));
                    final FontMetrics fm = g.getFontMetrics();
                    final Font small = VaultLook.ui(Font.PLAIN, 9.5f);
                    final FontMetrics sfm = g.getFontMetrics(small);
                    final int total = fm.stringWidth(label) + 5 + sfm.stringWidth(count);
                    final float x = i * cell + (cell - total) / 2f;
                    final float y = (h + fm.getAscent() - fm.getDescent()) / 2f;
                    g.setColor(on ? VaultLook.text() : VaultLook.muted());
                    g.drawString(label, x, y);
                    g.setFont(small);
                    g.setColor(on ? VaultLook.accent() : VaultLook.faint());
                    g.drawString(count, x + fm.stringWidth(label) + 5, y);
                }
            } finally {
                g.dispose();
            }
        }
    }
}

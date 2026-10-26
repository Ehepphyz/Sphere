package com.sphere.components.editor;

import com.sphere.components.ClipboardBridge;
import com.sphere.components.fileexplorerincludes.ContextMenuBuilder;
import com.sphere.components.fileexplorerincludes.FileTreeView;
import com.sphere.fonts.FontLoader;
import com.sphere.ui.SphereCoderFrame;
import com.sphere.utils.IconManager;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.plaf.LayerUI;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DragSource;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The explorer at the far left of SphereCoder, in two sections one above the
 * other. WORKSPACE is Sphere's own WorkSpace/, always there; FOLDERS holds the
 * other folders the user adds, from anywhere on the computer, each a row of
 * its own with its place written after it. Each section scrolls on its own and
 * folds under its header; the header of FOLDERS slides to share the height.
 * config/config.spherecoder keeps the folders, what was unfolded and the share
 * for the next launch.
 *
 * Files go from one section to the other as in the Finder: drag them onto a
 * folder of either. Within one place they move (Alt or Ctrl copies); from
 * WorkSpace/ to another folder, or back, or to another drive, they are copied
 * and the original stays (Shift moves them instead). The item following the
 * pointer says which, and where. Ctrl+Shift+C copies the selection to the
 * folder selected in the other section, Ctrl+Shift+X moves it there, as in a
 * two-pane file manager, and Tab goes to the other section. What arrives
 * glows a moment where it landed.
 *
 * Linger on a closed folder, or a folded section, and it springs open; Esc
 * gives up. Ctrl+Z puts the last move back, and takes the last copy back to
 * the bin. Ctrl+X, Ctrl+C and Ctrl+V go through the system clipboard, F2
 * renames, Delete sends to the bin. The right-click menu is the main window
 * explorer's.
 */
public final class ExplorerPanel extends JPanel implements FileTreeView {

    /** What the explorer asks of the editor beside it. */
    public interface Host {
        void openFile(File file);

        void openFileToSide(File file);

        void toast(String message);

        /** The file of the editor in use, or null. */
        File activeFile();

        /** A file or a folder now lives elsewhere: the editors showing it, or a file in it, follow. */
        void fileMoved(File from, File to);
    }

    /** The section of config.spherecoder the explorer keeps its state in. */
    private static final String SECTION = "explorer";
    private static final String WORKSPACE = "WorkSpace";
    private static final String LOADING = "Loading…";
    private static final int MAX_RECENT = 6;
    private static final int MAX_TARGETS = 5;
    private static final int MAX_UNDO = 20;
    private static final int ROW_HEIGHT = 22;
    private static final int HEADER_HEIGHT = 24;
    private static final int MIN_BODY = 44;
    private static final int DRAG_THRESHOLD = 5;
    private static final int SCROLL_MARGIN = 20;
    private static final int GLOW_MS = 1400;

    /** One row: a file, and what the disk said of it when listed, so painting never asks again. */
    private record Entry(File file, boolean dir, boolean hidden) {
        @Override
        public String toString() {
            // what JTree's type-to-select reads
            return file.getName().isEmpty() ? file.getPath() : file.getName();
        }
    }

    /** What Ctrl+Z takes back: moves go back where they came from, copies go to the bin. */
    private record Batch(boolean copy, List<File[]> pairs) {
    }

    private final Host host;
    /** The folders of FOLDERS, in the order they were added. */
    private final List<File> roots = new ArrayList<>();
    /** Those of them no longer on the disk: shown faint, kept until removed. */
    private final Set<File> missing = new HashSet<>();
    private final Pane workspace;
    private final Pane folders;
    private final List<Pane> panes;
    private final Column column;
    private final JLayer<JComponent> layer;
    private final Timer diskPoll;
    private boolean showHidden;
    /** The share of the height WORKSPACE gets when both sections are open, in thousandths. */
    private int split;
    /** The section worked in last: the header's buttons and Ctrl+V go there. */
    private Pane active;
    /** Set while a section is listed afresh: what it unfolds then is not a choice to write down. */
    private boolean restoring;

    /* Dragging, as in the Finder, within a section or from one to the other */
    private Pane pressPane;
    private Point pressPoint;
    private TreePath pressPath;
    private TreePath[] pressSelection;
    private boolean selectionKept;
    private boolean dragging;
    private List<File> dragFiles = List.of();
    /** The pointer, in the column of sections. */
    private Point dragPoint;
    private Pane dropPane;
    private boolean dropOnHeader;
    private TreePath dragOver;
    private File dropFolder;
    private boolean dropCopies;
    private Image ghostImage;
    private String ghostText;
    private Pane springPane;
    private TreePath springPath;
    private final Timer springTimer;

    /* What Ctrl+Z takes back, the last one first; the files cut for a paste; what just arrived */
    private final Deque<Batch> undo = new ArrayDeque<>();
    private Set<File> cutFiles = Set.of();
    private final Map<File, Long> arrivals = new HashMap<>();
    private final Timer glowTimer;

    public ExplorerPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        setOpaque(true);
        setMinimumSize(new Dimension(150, 0));
        showHidden = CoderConfig.flag(SECTION, "showHidden", false);
        split = Math.max(100, Math.min(900, CoderConfig.number(SECTION, "split", 550)));

        workspace = new Pane(true);
        folders = new Pane(false);
        panes = List.of(workspace, folders);
        active = workspace;
        column = new Column();
        layer = new JLayer<>(column, new GhostLayer());
        add(buildHeader(), BorderLayout.NORTH);
        add(layer, BorderLayout.CENTER);

        springTimer = new Timer(650, e -> springNow());
        springTimer.setRepeats(false);
        glowTimer = new Timer(40, e -> fadeArrivals());
        for (Pane p : panes) {
            installTreeBehavior(p);
            installKeys(p);
        }

        // A file made by a build or a terminal shows up without a refresh.
        diskPoll = new Timer(1500, e -> pollDisk());

        restore();
    }

    /** The disk is watched while the explorer is in a window: a closed one is not held by its timer. */
    @Override
    public void addNotify() {
        super.addNotify();
        diskPoll.start();
    }

    @Override
    public void removeNotify() {
        diskPoll.stop();
        glowTimer.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(sidebar());
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    /** A step below the code surface, like the tab strip but lighter. */
    private static Color sidebar() {
        return EditorTheme.mix(EditorTheme.background(), EditorTheme.chrome(), 0.55f);
    }

    // =======================================================================
    // The places shown: WorkSpace/, and the folders beside it
    // =======================================================================

    /**
     * The folders of the last session and what was unfolded in them. The
     * explorer of one folder at a time wrote "folder": that folder joins
     * FOLDERS, unless it was WorkSpace/, which has its own section now.
     */
    private void restore() {
        List<String> kept = CoderConfig.texts(SECTION, "folders");
        List<String> expanded = CoderConfig.texts(SECTION, "expandedPaths");
        String old = CoderConfig.text(SECTION, "folder", null);
        if (old != null) {
            File was = WORKSPACE.equals(old) ? workspaceDir() : new File(old).getAbsoluteFile();
            if (!isInside(was, workspaceDir()) && was.isDirectory() && kept.isEmpty()) {
                kept.add(was.getPath());
            }
            for (String rel : CoderConfig.texts(SECTION, "expanded")) {
                expanded.add(keep(new File(was, rel)));
            }
            CoderConfig.put(SECTION, "folder", null);
            CoderConfig.put(SECTION, "expanded", null);
            CoderConfig.put(SECTION, "folders", kept);
            CoderConfig.put(SECTION, "expandedPaths", expanded);
        }
        for (String p : kept) {
            File f = new File(p).getAbsoluteFile();
            if (!roots.contains(f)) {
                roots.add(f);
            }
            if (!f.isDirectory()) {
                missing.add(f);
            }
        }
        List<File> open = new ArrayList<>();
        for (String s : expanded) {
            open.add(unkeep(s));
        }
        workspace.rebuild(open);
        folders.rebuild(open);
    }

    /** Shows WorkSpace/, Sphere's own work folder, made first if it is not there yet. */
    public void openWorkspace() {
        File ws = workspaceDir();
        if (!ws.isDirectory() && !ws.mkdirs()) {
            host.toast("WorkSpace/ could not be created");
            return;
        }
        checkPlaces();
        workspace.focus();
    }

    /** Asks for any folder and adds it to FOLDERS, beside WorkSpace/. */
    public void chooseOtherFolder() {
        File dir = chooseFolder("Add a Folder to the Explorer", "Add Folder");
        if (dir != null) {
            addFolder(dir);
        }
    }

    /**
     * Adds a folder to FOLDERS and writes it down for the next launch. One
     * already shown, in WorkSpace/ or in a folder added, is only revealed
     * there; folders added before and lying inside it are shown in it now.
     */
    public void addFolder(File dir) {
        File abs = dir.getAbsoluteFile().toPath().normalize().toFile();
        if (isInside(abs, workspaceDir())) {
            workspace.setOpen(true);
            workspace.reveal(abs);
            host.toast(displayName(abs) + " is in WorkSpace/ already");
            return;
        }
        for (File r : roots) {
            if (isInside(abs, r)) {
                folders.setOpen(true);
                folders.reveal(abs);
                host.toast(displayName(abs) + " is in the explorer already"
                           + (abs.equals(r) ? "" : ", inside " + displayName(r)));
                return;
            }
        }
        List<File> next = new ArrayList<>(roots);
        next.add(abs);
        int before = roots.size();
        List<String> recent = CoderConfig.texts(SECTION, "recentFolders");
        recent.removeIf(p -> new File(p).getAbsoluteFile().equals(abs));
        recent.add(0, abs.getPath());
        while (recent.size() > MAX_RECENT) {
            recent.remove(recent.size() - 1);
        }
        CoderConfig.put(SECTION, "recentFolders", recent);

        List<File> open = expandedKept();
        open.add(abs);
        settleRoots(next, open);
        saveExpanded();
        folders.setOpen(true);
        folders.reveal(abs);
        glow(List.of(abs));
        int held = before + 1 - roots.size();
        host.toast("FOLDERS shows " + displayName(abs)
                   + (held > 0 ? ", with the " + (held == 1 ? "folder" : held + " folders") + " it holds" : "")
                   + " · drag between it and WorkSpace/ to copy");
    }

    /** A folder leaves FOLDERS; it stays on the disk. */
    private void removeFolders(List<File> gone) {
        List<File> next = new ArrayList<>(roots);
        next.removeAll(gone);
        settleRoots(next, expandedKept());
        host.toast((gone.size() == 1 ? displayName(gone.get(0)) : gone.size() + " folders")
                   + " left the explorer · still on the disk");
    }

    /**
     * The folders of FOLDERS once one came, went or moved: each once, and none
     * inside WorkSpace/ or inside another, where it shows already.
     */
    private void settleRoots(List<File> next, List<File> open) {
        List<File> unique = new ArrayList<>();
        for (File r : next) {
            if (!unique.contains(r)) {
                unique.add(r);
            }
        }
        List<File> all = List.copyOf(unique);
        unique.removeIf(r -> isInside(r, workspaceDir())
                             || all.stream().anyMatch(o -> !o.equals(r) && isInside(r, o)));
        roots.clear();
        roots.addAll(unique);
        missing.retainAll(roots);
        for (File r : roots) {
            if (!r.isDirectory()) {
                missing.add(r);
            }
        }
        List<String> kept = new ArrayList<>();
        for (File r : roots) {
            kept.add(r.getPath());
        }
        CoderConfig.put(SECTION, "folders", kept);
        folders.rebuild(open);
    }

    /** A folder of FOLDERS renamed or moved, or a folder holding it: FOLDERS follows it. */
    private void followRoots(File from, File to) {
        Path old = from.getAbsoluteFile().toPath();
        Path now = to.getAbsoluteFile().toPath();
        List<File> next = new ArrayList<>();
        boolean changed = false;
        for (File r : roots) {
            Path p = r.toPath();
            if (p.startsWith(old)) {
                next.add(now.resolve(old.relativize(p)).toFile());
                changed = true;
            } else {
                next.add(r);
            }
        }
        if (changed) {
            settleRoots(next, expandedKept());
        }
    }

    private static File workspaceDir() {
        return new File(WORKSPACE).getAbsoluteFile();
    }

    private static boolean isWorkspace(File dir) {
        return dir != null && dir.getAbsoluteFile().equals(workspaceDir());
    }

    private static String displayName(File dir) {
        if (isWorkspace(dir)) {
            return "WorkSpace/";
        }
        String n = dir.getName();
        return n.isEmpty() ? dir.getPath() : n + "/";
    }

    /** A folder as config.spherecoder keeps it: inside WorkSpace/ by name, so it is found wherever Sphere is installed. */
    private static String keep(File f) {
        Path ws = workspaceDir().toPath();
        Path p = f.getAbsoluteFile().toPath();
        if (p.startsWith(ws)) {
            String rel = ws.relativize(p).toString().replace(File.separatorChar, '/');
            return rel.isEmpty() ? WORKSPACE : WORKSPACE + "/" + rel;
        }
        return p.toString();
    }

    private static File unkeep(String s) {
        if (s.equals(WORKSPACE)) {
            return workspaceDir();
        }
        if (s.startsWith(WORKSPACE + "/")) {
            return new File(workspaceDir(), s.substring(WORKSPACE.length() + 1).replace('/', File.separatorChar));
        }
        return new File(s).getAbsoluteFile();
    }

    private static List<File> expandedKept() {
        List<File> out = new ArrayList<>();
        for (String s : CoderConfig.texts(SECTION, "expandedPaths")) {
            out.add(unkeep(s));
        }
        return out;
    }

    /** The place a file lies in: WorkSpace/, or the folder of FOLDERS holding it; null elsewhere. */
    private File placeOf(File f) {
        if (isInside(f, workspaceDir())) {
            return workspaceDir();
        }
        for (File r : roots) {
            if (isInside(f, r)) {
                return r;
            }
        }
        return null;
    }

    /** The section showing a file, WORKSPACE first; null when neither does. */
    private Pane paneOf(File f) {
        return workspace.holds(f) ? workspace : folders.holds(f) ? folders : null;
    }

    /** A folder named from its place: "WorkSpace/SingleTest/src/", "data/runs/", shortened in the middle when deep. */
    private String placeName(File dir) {
        File place = placeOf(dir);
        if (place == null) {
            return displayName(dir);
        }
        String top = isWorkspace(place) ? WORKSPACE
                   : place.getName().isEmpty() ? place.getPath().replaceAll("[\\\\/]+$", "") : place.getName();
        List<String> parts = new ArrayList<>();
        parts.add(top);
        Path rel = place.toPath().relativize(dir.getAbsoluteFile().toPath());
        if (!rel.toString().isEmpty()) {
            for (Path q : rel) {
                parts.add(q.toString());
            }
        }
        if (parts.size() > 4) {
            parts = List.of(parts.get(0), "…", parts.get(parts.size() - 2), parts.get(parts.size() - 1));
        }
        return String.join("/", parts) + "/";
    }

    /** Where a folder of FOLDERS is, after its name: its parent, only the last name of it when long, so the row stays narrow. */
    private static String shortParent(File dir) {
        File p = dir.getAbsoluteFile().getParentFile();
        if (p == null) {
            return "";
        }
        String s = p.getPath();
        if (s.length() <= 24) {
            return s;
        }
        String name = p.getName();
        return "…" + File.separator + (name.length() <= 20 ? name : name.substring(0, 19) + "…");
    }

    // =======================================================================
    // The trees: listed when unfolded, kept in step with the disk
    // =======================================================================

    private static Entry entryOf(TreePath path) {
        return path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode n
               && n.getUserObject() instanceof Entry e ? e : null;
    }

    private static boolean isLoaded(DefaultMutableTreeNode node) {
        return !(node.getChildCount() == 1
                 && ((DefaultMutableTreeNode) node.getFirstChild()).getUserObject() == LOADING);
    }

    /** Folders first, then by name as people read it, whatever the case. */
    private List<Entry> list(File dir) {
        List<Entry> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        for (File f : files) {
            boolean hidden = f.isHidden();
            if (showHidden || !hidden) {
                out.add(new Entry(f, f.isDirectory(), hidden));
            }
        }
        out.sort((a, b) -> a.dir() != b.dir() ? (a.dir() ? -1 : 1)
                           : String.CASE_INSENSITIVE_ORDER.compare(a.file().getName(), b.file().getName()));
        return out;
    }

    /**
     * Reads a listed folder again, in whichever section lists it: what went is
     * removed, what came is put in its place, and what stayed keeps its node.
     */
    @Override
    public void refreshNode(File folder) {
        if (folder == null) {
            return;
        }
        for (Pane p : panes) {
            p.refreshNode(folder.getAbsoluteFile());
        }
    }

    /** Every listed folder, read again; WorkSpace/ and the folders added, looked for again. */
    public void refreshAll() {
        checkPlaces();
        for (Pane p : panes) {
            for (File dir : new ArrayList<>(p.stamps.keySet())) {
                p.refreshNode(dir);
            }
        }
    }

    /** A folder's date moves when something in it is made, removed or renamed: only those are read again. */
    private void pollDisk() {
        if (dragging || !isShowing()) {
            return;
        }
        checkPlaces();
        for (Pane p : panes) {
            for (Map.Entry<File, Long> s : new ArrayList<>(p.stamps.entrySet())) {
                if (s.getKey().lastModified() != s.getValue()) {
                    p.refreshNode(s.getKey());
                }
            }
        }
    }

    /** WorkSpace/ gone or back, a folder of FOLDERS gone or back: the sections say so. */
    private void checkPlaces() {
        if (workspaceDir().isDirectory() == workspace.empty) {
            workspace.rebuild(expandedKept());
        }
        Set<File> gone = new HashSet<>();
        for (File r : roots) {
            if (!r.isDirectory()) {
                gone.add(r);
            }
        }
        if (gone.equals(missing)) {
            return;
        }
        Set<File> changed = new HashSet<>(gone);
        changed.addAll(missing);
        changed.removeIf(f -> gone.contains(f) && missing.contains(f));
        missing.clear();
        missing.addAll(gone);
        for (File r : changed) {
            DefaultMutableTreeNode n = folders.nodes.get(r);
            if (n != null) {
                // "not found" after its name, or no longer: the row's width changes
                folders.model.nodeChanged(n);
            }
            folders.refreshNode(r);
        }
    }

    /** Selects a file in the section showing it, unfolding the folders down to it; nothing when it lies elsewhere. */
    public void reveal(File file) {
        if (file == null || dragging) {
            return;
        }
        Pane p = paneOf(file);
        if (p != null) {
            p.reveal(file);
        }
    }

    public void revealActive() {
        File f = host.activeFile();
        Pane p = f == null ? null : paneOf(f);
        if (f == null) {
            host.toast("No file is open");
        } else if (p == null) {
            host.toast(f.getName() + " is outside WorkSpace/ and the folders of the explorer · Add Folder... shows it");
        } else {
            p.setOpen(true);
            p.reveal(f);
            p.tree.requestFocusInWindow();
        }
    }

    public void collapseAll() {
        for (Pane p : panes) {
            p.collapseAll();
        }
    }

    /** The keyboard to the section worked in last, or to the first that has something to show. */
    public void focusTree() {
        Pane p = !active.empty ? active : !workspace.empty ? workspace : folders;
        p.focus();
    }

    /** Tab: the other section, opened if it was folded. */
    private void focusPane(Pane p) {
        if (p.empty) {
            host.toast(p.ws ? "WorkSpace/ is not there yet" : "FOLDERS has no folder yet · + adds one");
            return;
        }
        p.focus();
    }

    @Override
    public boolean isShowHidden() {
        return showHidden;
    }

    @Override
    public void setShowHidden(boolean visible) {
        if (showHidden == visible) {
            return;
        }
        showHidden = visible;
        CoderConfig.put(SECTION, "showHidden", visible);
        refreshAll();
    }

    /** From the right-click menu's Rename: the editors follow, and FOLDERS when it was one of its folders. */
    @Override
    public void fileMoved(File from, File to) {
        moved(from, to);
    }

    private void moved(File from, File to) {
        host.fileMoved(from, to);
        followRoots(from, to);
    }

    /** What is unfolded in both sections, for the next launch. */
    private void saveExpanded() {
        if (restoring) {
            return;
        }
        Set<String> out = new TreeSet<>();
        for (Pane p : panes) {
            Enumeration<TreePath> open = p.tree.getExpandedDescendants(new TreePath(p.model.getRoot()));
            if (open == null) {
                continue;
            }
            while (open.hasMoreElements()) {
                Entry e = entryOf(open.nextElement());
                if (e != null && !isWorkspace(e.file())) {
                    out.add(keep(e.file()));
                }
            }
        }
        CoderConfig.put(SECTION, "expandedPaths", new ArrayList<>(out));
    }

    // =======================================================================
    // A section: its header, its tree, and what it keeps of the disk
    // =======================================================================

    /**
     * One section of the explorer. WORKSPACE lists WorkSpace/ itself; FOLDERS
     * lists each folder added as a row of its own, under an invisible root.
     */
    private final class Pane {
        final boolean ws;
        final DefaultTreeModel model = new DefaultTreeModel(new DefaultMutableTreeNode(), true);
        final Tree tree;
        final Map<File, DefaultMutableTreeNode> nodes = new HashMap<>();
        /** When each listed folder last changed, so the poll reads again only those that did. */
        final Map<File, Long> stamps = new HashMap<>();
        final PaneHeader header;
        final JScrollPane scroll;
        final CardLayout cards = new CardLayout();
        final JPanel body = new JPanel(cards);
        boolean open;
        /** True while the section says why it has no tree: WorkSpace/ not made, no folder added. */
        boolean empty;
        int hoverRow = -1;

        Pane(boolean ws) {
            this.ws = ws;
            open = CoderConfig.flag(SECTION, ws ? "workspaceOpen" : "foldersOpen", true);
            tree = new Tree(this);
            header = new PaneHeader(this);
            scroll = new JScrollPane(tree);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            body.setOpaque(false);
            body.add(scroll, "tree");
            body.add(ws ? buildNoWorkspace() : buildNoFolders(), "empty");
            body.setVisible(open);
        }

        String title() {
            return ws ? "WORKSPACE" : "FOLDERS";
        }

        Pane other() {
            return this == workspace ? folders : workspace;
        }

        void setOpen(boolean value) {
            if (open == value) {
                return;
            }
            open = value;
            body.setVisible(value);
            CoderConfig.put(SECTION, ws ? "workspaceOpen" : "foldersOpen", value);
            column.revalidate();
            column.repaint();
        }

        /** Lists afresh, WorkSpace/ or each folder added, unfolding again what was. */
        void rebuild(List<File> expanded) {
            restoring = true;
            try {
                nodes.clear();
                stamps.clear();
                DefaultMutableTreeNode top;
                if (ws) {
                    top = nodeFor(new Entry(workspaceDir(), true, false));
                } else {
                    top = new DefaultMutableTreeNode(null, true);
                    for (File r : roots) {
                        top.add(nodeFor(new Entry(r, true, false)));
                    }
                }
                model.setRoot(top);
                load(top);
                tree.expandPath(new TreePath(top));
                for (File f : expanded) {
                    TreePath p = holds(f) ? pathTo(f) : null;
                    Entry e = entryOf(p);
                    if (e != null && e.dir()) {
                        tree.expandPath(p);
                    }
                }
            } finally {
                restoring = false;
            }
            empty = ws ? !workspaceDir().isDirectory() : roots.isEmpty();
            cards.show(body, empty ? "empty" : "tree");
            header.repaint();
        }

        boolean holds(File f) {
            if (ws) {
                return isInside(f, workspaceDir());
            }
            for (File r : roots) {
                if (isInside(f, r)) {
                    return true;
                }
            }
            return false;
        }

        private DefaultMutableTreeNode nodeFor(Entry e) {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(e, e.dir());
            if (e.dir()) {
                node.add(new DefaultMutableTreeNode(LOADING, false));
            }
            nodes.put(e.file(), node);
            return node;
        }

        /** Lists a folder the first time it is needed. */
        void load(DefaultMutableTreeNode node) {
            if (!(node.getUserObject() instanceof Entry e) || !e.dir() || isLoaded(node)) {
                return;
            }
            node.removeAllChildren();
            for (Entry child : list(e.file())) {
                node.add(nodeFor(child));
            }
            stamps.put(e.file(), e.file().lastModified());
            model.nodeStructureChanged(node);
        }

        /** Forgets a node and everything under it. */
        private void forget(DefaultMutableTreeNode node) {
            if (node.getUserObject() instanceof Entry e) {
                nodes.remove(e.file(), node);
                stamps.remove(e.file());
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                forget((DefaultMutableTreeNode) node.getChildAt(i));
            }
        }

        /** Reads a listed folder again; what stayed keeps its node, so what was unfolded stays so. */
        void refreshNode(File dir) {
            DefaultMutableTreeNode node = nodes.get(dir);
            if (node == null || !isLoaded(node)) {
                return;
            }
            List<Entry> now = list(dir);
            // By exact name: a rename that only changes the case is a new name to show.
            Map<String, Entry> wanted = new HashMap<>();
            for (Entry e : now) {
                wanted.put(e.file().getName(), e);
            }
            Map<String, DefaultMutableTreeNode> kept = new HashMap<>();
            for (int i = node.getChildCount() - 1; i >= 0; i--) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                if (child.getUserObject() instanceof Entry e && e.equals(wanted.get(e.file().getName()))) {
                    kept.put(e.file().getName(), child);
                } else {
                    forget(child);
                    model.removeNodeFromParent(child);
                }
            }
            for (int i = 0; i < now.size(); i++) {
                Entry e = now.get(i);
                DefaultMutableTreeNode child = kept.get(e.file().getName());
                if (child == null) {
                    model.insertNodeInto(nodeFor(e), node, i);
                } else if (node.getIndex(child) != i) {
                    model.removeNodeFromParent(child);
                    model.insertNodeInto(child, node, i);
                }
            }
            stamps.put(dir, dir.lastModified());
        }

        /** The path of a file in this section, listing the folders down to it; null when it lies elsewhere. */
        TreePath pathTo(File target) {
            if (target == null) {
                return null;
            }
            File abs = target.getAbsoluteFile();
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) model.getRoot();
            TreePath path = new TreePath(node);
            if (!(node.getUserObject() instanceof Entry)) {
                // FOLDERS: first the folder added that holds it
                DefaultMutableTreeNode top = null;
                for (int i = 0; i < node.getChildCount() && top == null; i++) {
                    DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                    if (child.getUserObject() instanceof Entry e && isInside(abs, e.file())) {
                        top = child;
                    }
                }
                if (top == null) {
                    return null;
                }
                node = top;
                path = path.pathByAddingChild(top);
            }
            Path rel;
            try {
                rel = ((Entry) node.getUserObject()).file().toPath().relativize(abs.toPath().normalize());
            } catch (IllegalArgumentException otherDrive) {
                return null;
            }
            if (rel.toString().isEmpty()) {
                return path;
            }
            for (Path part : rel) {
                if (part.toString().equals("..")) {
                    return null;
                }
                load(node);
                File wanted = new File(((Entry) node.getUserObject()).file(), part.toString());
                DefaultMutableTreeNode next = null;
                for (int i = 0; i < node.getChildCount(); i++) {
                    DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                    if (child.getUserObject() instanceof Entry e && e.file().equals(wanted)) {
                        next = child;
                        break;
                    }
                }
                if (next == null) {
                    return null;
                }
                path = path.pathByAddingChild(next);
                node = next;
            }
            return path;
        }

        /** Selects a file, unfolding the folders down to it. */
        void reveal(File file) {
            TreePath path = holds(file) ? pathTo(file) : null;
            if (path == null || path.getPathCount() < 2) {
                return;
            }
            if (path.getParentPath() != null) {
                tree.expandPath(path.getParentPath());
            }
            tree.setSelectionPath(path);
            scrollTo(path);
        }

        /** Scrolls a row into view without scrolling sideways. */
        void scrollTo(TreePath path) {
            Rectangle b = tree.getPathBounds(path);
            if (b != null) {
                tree.scrollRectToVisible(new Rectangle(0, b.y, 1, b.height));
            }
        }

        void collapseAll() {
            for (int row = tree.getRowCount() - 1; row >= 0; row--) {
                tree.collapseRow(row);
            }
        }

        void focus() {
            setOpen(true);
            if (empty) {
                return;
            }
            active = this;
            tree.requestFocusInWindow();
            if (tree.getSelectionCount() == 0 && tree.getRowCount() > 0) {
                tree.setSelectionRow(0);
            }
        }

        /** The row under a height, anywhere across its width; -1 below the last one. */
        int rowAt(int y) {
            int row = tree.getClosestRowForLocation(0, y);
            if (row < 0) {
                return -1;
            }
            Rectangle b = tree.getRowBounds(row);
            return b != null && y >= b.y && y < b.y + b.height ? row : -1;
        }

        TreePath pathAt(int y) {
            int row = rowAt(y);
            return row < 0 ? null : tree.getPathForRow(row);
        }

        /** The selected files, without those inside a selected folder: the folder carries them. */
        List<File> selectedFiles() {
            List<File> out = new ArrayList<>();
            TreePath[] paths = tree.getSelectionPaths();
            if (paths == null) {
                return out;
            }
            for (TreePath p : paths) {
                Entry e = entryOf(p);
                if (e != null) {
                    out.add(e.file());
                }
            }
            List<File> all = new ArrayList<>(out);
            out.removeIf(f -> all.stream().anyMatch(o -> o != f && isInside(f, o)));
            return out;
        }

        /** The selected folder, or that of the selected file; null when nothing is selected. */
        File selectedFolder() {
            Entry e = entryOf(tree.getSelectionPath());
            return e == null ? null : e.dir() ? e.file() : e.file().getParentFile();
        }

        /** Where a drop below the rows, or on the header, goes: WorkSpace/, or the one folder of FOLDERS. */
        File home() {
            if (ws) {
                return workspaceDir();
            }
            return roots.size() == 1 ? roots.get(0) : null;
        }

        /** Where new and pasted files go: the selected folder, or this section's own place. */
        File targetFolder() {
            File f = selectedFolder();
            if (f != null) {
                return f;
            }
            return ws ? workspaceDir() : roots.isEmpty() ? null : roots.get(0);
        }

        /** Where the other section sends things: the folder selected here, else this section's one place. */
        File obviousTarget() {
            File f = selectedFolder();
            return f != null ? f : home();
        }
    }

    // =======================================================================
    // Header, section headers, and what a section shows without a tree
    // =======================================================================

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(EditorTheme.chrome());
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        header.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 6));
        JLabel title = new JLabel("EXPLORER") {
            @Override
            protected void paintComponent(Graphics g) {
                setForeground(EditorTheme.chromeForeground());
                super.paintComponent(g);
            }
        };
        title.setFont(EditorWidgets.uiFont(Font.BOLD, 11));

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(action(EditorIcons.newFile(15, EditorTheme::foreground), "New File...", () -> create(false)));
        actions.add(action(EditorIcons.newFolder(15, EditorTheme::foreground), "New Folder...", () -> create(true)));
        actions.add(action(EditorIcons.refresh(15, EditorTheme::foreground), "Refresh", this::refreshAll));
        actions.add(action(EditorIcons.collapseAll(15, EditorTheme::foreground), "Collapse Folders", this::collapseAll));
        EditorWidgets.FlatButton more = new EditorWidgets.FlatButton(EditorIcons.more(15, EditorTheme::foreground),
                                                                     "Folders and View");
        more.addActionListener(e -> showFolderMenu(more, 0, more.getHeight()));
        actions.add(more);

        header.add(title, BorderLayout.WEST);
        header.add(actions, BorderLayout.EAST);
        return header;
    }

    private static EditorWidgets.FlatButton action(Icon icon, String tooltip, Runnable run) {
        EditorWidgets.FlatButton b = new EditorWidgets.FlatButton(icon, tooltip);
        b.addActionListener(e -> run.run());
        return b;
    }

    /** Adding a folder, the folders added lately and gone since; then what the trees show. */
    private void showFolderMenu(Component invoker, int x, int y) {
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Add Folder...", this::chooseOtherFolder));
        for (String p : CoderConfig.texts(SECTION, "recentFolders")) {
            File d = new File(p).getAbsoluteFile();
            if (!d.isDirectory() || isInside(d, workspaceDir()) || roots.contains(d)) {
                continue;
            }
            String parent = d.getParent();
            JMenuItem item = menuItem("<html>Add " + escape(displayName(d))
                                      + (parent == null ? "" : "&nbsp;&nbsp;<font color='" + hex(EditorTheme.textFaint())
                                                               + "'>" + escape(parent) + "</font>")
                                      + "</html>", () -> addFolder(d));
            item.setToolTipText(d.getPath());
            menu.add(item);
        }
        menu.addSeparator();
        menu.add(menuItem("Show WorkSpace/", this::openWorkspace));
        JCheckBoxMenuItem hidden = new JCheckBoxMenuItem("Show Hidden Files", showHidden);
        hidden.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        hidden.addActionListener(e -> setShowHidden(hidden.isSelected()));
        menu.add(hidden);
        menu.add(menuItem("Reveal Active File", this::revealActive));
        menu.add(menuItem("Collapse Folders", this::collapseAll));
        menu.add(menuItem("Refresh", this::refreshAll));
        menu.show(invoker, x, y);
    }

    private static JMenuItem menuItem(String text, Runnable run) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        item.addActionListener(e -> run.run());
        return item;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * A section's header: a click folds or opens it. FOLDERS' slides up and
     * down to share the height with WORKSPACE, counts its folders, and adds one.
     */
    private final class PaneHeader extends JPanel {
        private final Pane pane;
        private boolean hovered;
        private Point pressAt;
        private int pressTop;
        private boolean sliding;

        PaneHeader(Pane pane) {
            super(new BorderLayout());
            this.pane = pane;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 6));
            if (!pane.ws) {
                JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                actions.setOpaque(false);
                actions.add(ExplorerPanel.action(EditorIcons.newFolder(14, EditorTheme::foreground), "Add Folder...",
                                   ExplorerPanel.this::chooseOtherFolder));
                add(actions, BorderLayout.EAST);
            }
            setToolTipText("");
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    pressAt = SwingUtilities.isLeftMouseButton(e) ? e.getLocationOnScreen() : null;
                    pressTop = workspace.body.getHeight();
                    sliding = false;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (pressAt == null || pane.ws || !workspace.open || !folders.open) {
                        return;
                    }
                    // On the screen: the header moves under the pointer as it slides.
                    int dy = e.getYOnScreen() - pressAt.y;
                    if (!sliding && Math.abs(dy) < DRAG_THRESHOLD) {
                        return;
                    }
                    sliding = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
                    int room = column.getHeight() - panes.size() * HEADER_HEIGHT;
                    if (room > 0) {
                        split = Math.max(100, Math.min(900, (pressTop + dy) * 1000 / room));
                        column.revalidate();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (sliding) {
                        sliding = false;
                        setCursor(null);
                        CoderConfig.put(SECTION, "split", split);
                    } else if (pressAt != null && contains(e.getPoint())) {
                        pane.setOpen(!pane.open);
                        if (pane.open) {
                            pane.focus();
                        }
                    }
                    pressAt = null;
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            if (pane.ws) {
                return workspaceDir().getPath();
            }
            return "The folders beside WorkSpace/" + (workspace.open && folders.open ? " · drag to share the height" : "");
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = EditorWidgets.smooth(g);
            int w = getWidth();
            int h = getHeight();
            boolean target = dragging && dropPane == pane && dropOnHeader && dropFolder != null;
            g2.setColor(target ? EditorTheme.alpha(EditorTheme.focus(), 80)
                        : hovered ? EditorTheme.mix(sidebar(), EditorTheme.foreground(), 0.05f) : sidebar());
            g2.fillRect(0, 0, w, h);
            if (!pane.ws) {
                g2.setColor(EditorTheme.chromeBorder());
                g2.fillRect(0, 0, w, 1);
            }
            EditorIcons.chevron(12, EditorTheme::textMuted, pane.open).paintIcon(this, g2, 6, (h - 12) / 2);
            g2.setFont(EditorWidgets.uiFont(Font.BOLD, 11));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(active == pane ? EditorTheme.foreground() : EditorTheme.chromeForeground());
            g2.drawString(pane.title(), 22, (h + fm.getAscent() - fm.getDescent()) / 2);
            if (!pane.ws && !roots.isEmpty()) {
                String count = String.valueOf(roots.size());
                int x = 22 + fm.stringWidth(pane.title()) + 7;
                g2.setFont(EditorWidgets.uiFont(Font.BOLD, 10));
                FontMetrics cm = g2.getFontMetrics();
                int cw = Math.max(cm.stringWidth(count) + 10, 16);
                int ch = cm.getHeight() + 1;
                g2.setColor(EditorTheme.alpha(EditorTheme.foreground(), 30));
                g2.fillRoundRect(x, (h - ch) / 2, cw, ch, ch, ch);
                g2.setColor(EditorTheme.textMuted());
                g2.drawString(count, x + (cw - cm.stringWidth(count)) / 2, (h + cm.getAscent() - cm.getDescent()) / 2);
            }
            if (target) {
                g2.setColor(EditorTheme.alpha(EditorTheme.focus(), 210));
                g2.drawRect(1, 0, w - 3, h - 1);
            }
            g2.dispose();
        }
    }

    /** WORKSPACE above FOLDERS: each header, and its tree under it when open; the open ones share the height. */
    private final class Column extends JPanel {
        Column() {
            super(null);
            setOpaque(false);
            for (Pane p : panes) {
                add(p.header);
                add(p.body);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(220, 400);
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            int room = Math.max(0, h - panes.size() * HEADER_HEIGHT);
            int top = workspace.open ? room : 0;
            if (workspace.open && folders.open) {
                top = room * split / 1000;
                if (room >= 2 * MIN_BODY) {
                    top = Math.max(MIN_BODY, Math.min(room - MIN_BODY, top));
                }
            }
            int y = 0;
            for (Pane p : panes) {
                p.header.setBounds(0, y, w, HEADER_HEIGHT);
                y += HEADER_HEIGHT;
                int height = !p.open ? 0 : p == workspace ? top : Math.max(0, h - y);
                p.body.setBounds(0, y, w, height);
                y += height;
            }
        }
    }

    /** Over both sections: the items following the pointer, from one to the other. */
    private final class GhostLayer extends LayerUI<JComponent> {
        @Override
        public void paint(Graphics g, JComponent c) {
            super.paint(g, c);
            if (dragging && ghostImage != null && dragPoint != null) {
                Graphics2D g2 = EditorWidgets.smooth(g);
                paintGhost(g2, dragPoint.x + 14, dragPoint.y + 14);
                g2.dispose();
            }
        }
    }

    /** WORKSPACE when WorkSpace/ is not there: why, and the button that makes it. */
    private JComponent buildNoWorkspace() {
        Words text = new Words("WorkSpace/, Sphere's own work folder, is not there yet.",
                               EditorWidgets.uiFont(Font.PLAIN, 12), EditorTheme::textMuted);
        ChoiceButton make = new ChoiceButton("Create WorkSpace/", IconManager.getIcon("folder_closed.png"), true);
        make.setToolTipText(workspaceDir().getPath());
        make.addActionListener(e -> openWorkspace());
        return card(text, make);
    }

    /** FOLDERS before any folder is added: what it is for, and the button that adds one. */
    private JComponent buildNoFolders() {
        Words text = new Words("Any folder of this computer, beside WorkSpace/.",
                               EditorWidgets.uiFont(Font.PLAIN, 12), EditorTheme::textMuted);
        ChoiceButton add = new ChoiceButton("Add Folder...", EditorIcons.folder(16, EditorTheme::foreground), true);
        add.setToolTipText("Any folder on this computer");
        add.addActionListener(e -> chooseOtherFolder());
        Words tip = new Words("Drag files from one section to the other: they are copied, Shift moves them. "
                              + "Ctrl+Shift+C and Ctrl+Shift+X send the selection across.",
                              EditorWidgets.uiFont(Font.PLAIN, 11), EditorTheme::textFaint);
        return card(text, add, tip);
    }

    private static JComponent card(JComponent... parts) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        for (int i = 0; i < parts.length; i++) {
            parts[i].setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(parts[i]);
            if (i < parts.length - 1) {
                p.add(Box.createVerticalStrut(10));
            }
        }
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(p, BorderLayout.NORTH);
        return holder;
    }

    /** A wide button: filled for the one suggested, outlined for another. */
    private static final class ChoiceButton extends JButton {
        private final boolean primary;
        private boolean hovered;

        ChoiceButton(String text, Icon icon, boolean primary) {
            super(text, icon);
            this.primary = primary;
            setUI(new BasicButtonUI());
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setHorizontalAlignment(SwingConstants.LEFT);
            setIconTextGap(8);
            setFont(EditorWidgets.uiFont(primary ? Font.BOLD : Font.PLAIN, 12));
            setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
            });
        }

        @Override
        public void updateUI() {
            setUI(new BasicButtonUI());
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = EditorWidgets.smooth(g);
            RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 7, 7);
            if (primary) {
                Color fill = EditorTheme.focus();
                g2.setColor(hovered ? EditorTheme.shift(fill, 0.10f) : fill);
                g2.fill(shape);
                setForeground(EditorTheme.onColor(fill));
            } else {
                g2.setColor(hovered ? EditorTheme.widgetHover() : EditorTheme.widgetInput());
                g2.fill(shape);
                g2.setColor(EditorTheme.widgetBorder());
                g2.draw(shape);
                setForeground(EditorTheme.foreground());
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** A few words that wrap to the width they are given, in a color read at paint time. */
    private static final class Words extends JComponent {
        private final String text;
        private final Supplier<Color> color;

        Words(String text, Font font, Supplier<Color> color) {
            this.text = text;
            this.color = color;
            setFont(font);
        }

        private List<String> lines(int width) {
            FontMetrics fm = getFontMetrics(getFont());
            List<String> out = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String tried = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && fm.stringWidth(tried) > width) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(tried);
                }
            }
            if (line.length() > 0) {
                out.add(line.toString());
            }
            return out;
        }

        @Override
        public Dimension getPreferredSize() {
            int w = getWidth() > 0 ? getWidth() : 220;
            return new Dimension(w, lines(w).size() * getFontMetrics(getFont()).getHeight());
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            boolean resized = width != getWidth();
            super.setBounds(x, y, width, height);
            if (resized) {
                // the height follows the new width on the next layout
                SwingUtilities.invokeLater(this::revalidate);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = EditorWidgets.smooth(g);
            g2.setFont(getFont());
            g2.setColor(color.get());
            FontMetrics fm = g2.getFontMetrics();
            int y = fm.getAscent();
            for (String line : lines(getWidth())) {
                g2.drawString(line, 0, y);
                y += fm.getHeight();
            }
            g2.dispose();
        }
    }

    // =======================================================================
    // Trees, rows and painting
    // =======================================================================

    private final class Tree extends JTree {
        private final Pane pane;

        Tree(Pane pane) {
            super(pane.model);
            this.pane = pane;
            setRootVisible(false);
            setShowsRootHandles(true);
            setRowHeight(ROW_HEIGHT);
            setOpaque(false);
            // A double-click opens a folder anywhere on its row, not only on its name.
            setToggleClickCount(0);
            setCellRenderer(new Row());
            setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
            getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
            // Tab goes to the other section, as in a two-pane file manager.
            setFocusTraversalKeysEnabled(false);
            ToolTipManager.sharedInstance().registerComponent(this);
        }

        @Override
        public void updateUI() {
            setUI(new FlatTreeUI());
        }

        /** The selection before the press changed it: pressing one of several selected items drags them all. */
        @Override
        protected void processMouseEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED) {
                pressSelection = getSelectionPaths();
            }
            super.processMouseEvent(e);
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            Entry en = dragging ? null : entryOf(pane.pathAt(e.getY()));
            if (en == null) {
                return null;
            }
            return missing.contains(en.file()) ? en.file().getPath() + " — not found" : en.file().getPath();
        }

        @Override
        protected void paintComponent(Graphics g) {
            paintRowFills(g);
            super.paintComponent(g);
            if (dragging && dropPane == pane && !dropOnHeader && pane.ws && isWorkspace(dropFolder)) {
                paintDropFrame(g);
            }
            if (pane.ws && !pane.empty && getRowCount() == 0) {
                paintEmptyHint(g);
            }
        }

        /** Selection, hover, drop target and what just arrived, across the whole row, under the chevrons. */
        private void paintRowFills(Graphics g) {
            Rectangle clip = g.getClipBounds() != null ? g.getClipBounds() : getVisibleRect();
            int first = getClosestRowForLocation(0, clip.y);
            int last = getClosestRowForLocation(0, clip.y + clip.height);
            if (first < 0) {
                return;
            }
            long now = System.currentTimeMillis();
            Graphics2D g2 = (Graphics2D) g.create();
            for (int row = first; row <= last; row++) {
                Rectangle b = getRowBounds(row);
                Entry e = entryOf(getPathForRow(row));
                if (b == null) {
                    continue;
                }
                boolean target = dragging && dropPane == pane && !dropOnHeader && e != null
                                 && e.file().equals(dropFolder);
                Color fill = target ? EditorTheme.alpha(EditorTheme.focus(), 80)
                           : isRowSelected(row) ? (hasFocus() ? EditorTheme.widgetSelection()
                                                              : EditorTheme.alpha(EditorTheme.foreground(), 30))
                           : row == pane.hoverRow && !dragging ? EditorTheme.alpha(EditorTheme.foreground(), 14)
                           : null;
                if (fill != null) {
                    g2.setColor(fill);
                    g2.fillRect(0, b.y, getWidth(), b.height);
                }
                if (target) {
                    g2.setColor(EditorTheme.alpha(EditorTheme.focus(), 210));
                    g2.drawRect(1, b.y, getWidth() - 3, b.height - 1);
                }
                float glow = e == null ? 0f : glowOf(e.file(), now);
                if (glow > 0f) {
                    g2.setColor(EditorTheme.alpha(EditorTheme.focus(), Math.round(110 * glow)));
                    g2.fillRect(0, b.y, getWidth(), b.height);
                    g2.setColor(EditorTheme.alpha(EditorTheme.focus(), Math.round(255 * glow)));
                    g2.fillRect(0, b.y, 3, b.height);
                }
            }
            g2.dispose();
        }

        /** WorkSpace/ itself as the target: a frame around all of it. */
        private void paintDropFrame(Graphics g) {
            Graphics2D g2 = EditorWidgets.smooth(g);
            Rectangle v = getVisibleRect();
            g2.setColor(EditorTheme.alpha(EditorTheme.focus(), 210));
            g2.setStroke(new BasicStroke(2f));
            g2.draw(new RoundRectangle2D.Float(v.x + 2, v.y + 2, v.width - 4, v.height - 4, 8, 8));
            g2.dispose();
        }

        private void paintEmptyHint(Graphics g) {
            Graphics2D g2 = EditorWidgets.smooth(g);
            g2.setFont(EditorWidgets.uiFont(Font.PLAIN, 12));
            g2.setColor(EditorTheme.textFaint());
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString("WorkSpace/ is empty.", 14, 10 + fm.getAscent());
            g2.drawString("New File, New Folder, or drop files here.", 14, 10 + fm.getHeight() + fm.getAscent());
            g2.dispose();
        }
    }

    /** Chevrons for the folders and no connecting lines, as in the rest of the editor. */
    private static final class FlatTreeUI extends BasicTreeUI {
        @Override
        protected void installDefaults() {
            super.installDefaults();
            setExpandedIcon(EditorIcons.chevron(12, EditorTheme::textMuted, true));
            setCollapsedIcon(EditorIcons.chevron(12, EditorTheme::textMuted, false));
            setLeftChildIndent(8);
            setRightChildIndent(12);
        }

        @Override
        protected void paintHorizontalPartOfLeg(Graphics g, Rectangle clip, Insets insets, Rectangle bounds,
                                                TreePath path, int row, boolean expanded, boolean beenExpanded,
                                                boolean leaf) {
        }

        @Override
        protected void paintVerticalPartOfLeg(Graphics g, Rectangle clip, Insets insets, TreePath path) {
        }
    }

    private static Icon iconFor(Entry e, boolean expanded) {
        if (!e.dir()) {
            return IconManager.getIconForFile(e.file().getName());
        }
        return IconManager.getIcon(expanded ? "folder_open.png" : "folder_closed.png");
    }

    /** The main window explorer's ghost, with the Finder's green plus when the drop copies, and where it goes. */
    private void paintGhost(Graphics2D g2, int x, int y) {
        int iconW = ghostImage.getWidth(null);
        int iconH = ghostImage.getHeight(null);
        Font nameFont = EditorWidgets.uiFont(Font.PLAIN, 12);
        Font hintFont = EditorWidgets.uiFont(Font.BOLD, 11);
        FontMetrics fm = g2.getFontMetrics(nameFont);
        FontMetrics hm = g2.getFontMetrics(hintFont);
        String hint = dropFolder == null ? null : (dropCopies ? "Copy to " : "Move to ") + placeName(dropFolder);
        int textW = Math.max(ghostText != null ? fm.stringWidth(ghostText) : 0, hint != null ? hm.stringWidth(hint) : 0);
        int textH = fm.getHeight() + (hint != null ? hm.getHeight() : 0);
        int padding = 10;
        int boxW = iconW + textW + padding * 3;
        int boxH = Math.max(iconH, textH) + padding * 2;

        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
        g2.setColor(Color.BLACK);
        g2.fillRoundRect(x + 3, y + 3, boxW, boxH, 14, 14);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g2.setColor(new Color(255, 255, 255, 200));
        g2.fillRoundRect(x, y, boxW, boxH, 14, 14);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
        g2.setColor(new Color(255, 255, 255, 180));
        g2.drawRoundRect(x, y, boxW, boxH, 14, 14);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.95f));
        g2.drawImage(ghostImage, x + padding, y + padding, layer);
        int tx = x + iconW + padding * 2;
        if (ghostText != null) {
            g2.setFont(nameFont);
            g2.setColor(Color.BLACK);
            g2.drawString(ghostText, tx, y + padding + fm.getAscent());
        }
        if (hint != null) {
            g2.setFont(hintFont);
            g2.setColor(dropCopies ? new Color(0, 92, 34) : new Color(14, 44, 132));
            g2.drawString(hint, tx, y + padding + fm.getHeight() + hm.getAscent());
        }
        g2.setFont(nameFont);
        if (dragFiles.size() > 1) {
            String badge = dragFiles.size() + " items";
            int bw = fm.stringWidth(badge) + 12;
            int bh = fm.getHeight() + 6;
            int bx = x + boxW - bw - 8;
            int by = y - bh / 2;
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f));
            g2.setColor(new Color(0, 0, 0, 180));
            g2.fillRoundRect(bx, by, bw, bh, 10, 10);
            g2.setColor(Color.WHITE);
            g2.drawString(badge, bx + 6, by + fm.getAscent() + 2);
        }
        if (dropFolder != null && dropCopies) {
            g2.setComposite(AlphaComposite.SrcOver);
            int r = 8;
            int cx = x + boxW - 2;
            int cy = y + boxH - 2;
            g2.setColor(new Color(52, 199, 89));
            g2.fillOval(cx - r, cy - r, 2 * r, 2 * r);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(cx - 4, cy, cx + 4, cy);
            g2.drawLine(cx, cy - 4, cx, cy + 4);
        }
    }

    /** One row: the file's icon and its name, faint when hidden or cut; a folder of FOLDERS says where it is. */
    private final class Row extends JLabel implements TreeCellRenderer {
        private String place;
        private boolean gone;

        Row() {
            setOpaque(false);
            setIconTextGap(6);
            setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 8));
        }

        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            Object o = value instanceof DefaultMutableTreeNode n ? n.getUserObject() : value;
            boolean top = value instanceof DefaultMutableTreeNode node
                          && node.getParent() instanceof DefaultMutableTreeNode parent && parent.getUserObject() == null;
            setFont(EditorWidgets.uiFont(top ? Font.BOLD : Font.PLAIN, 12));
            place = null;
            gone = false;
            if (o instanceof Entry e) {
                setIcon(iconFor(e, expanded));
                setText(e.toString());
                gone = top && missing.contains(e.file());
                boolean faint = e.hidden() || cutFiles.contains(e.file()) || gone;
                setForeground(faint ? EditorTheme.textMuted() : EditorTheme.foreground());
                if (top) {
                    place = gone ? "not found" : shortParent(e.file());
                }
            } else {
                setIcon(null);
                setText(String.valueOf(o));
                setForeground(EditorTheme.textFaint());
            }
            return this;
        }

        private Font placeFont() {
            return EditorWidgets.uiFont(Font.PLAIN, 11);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension d = super.getPreferredSize();
            if (place != null && !place.isEmpty()) {
                d.width += 8 + getFontMetrics(placeFont()).stringWidth(place);
            }
            return d;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (place == null || place.isEmpty()) {
                return;
            }
            Graphics2D g2 = EditorWidgets.smooth(g);
            Icon icon = getIcon();
            int x = getInsets().left + (icon == null ? 0 : icon.getIconWidth() + getIconTextGap())
                    + g2.getFontMetrics(getFont()).stringWidth(getText()) + 8;
            g2.setFont(placeFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(gone ? EditorTheme.warningSquiggle() : EditorTheme.textFaint());
            g2.drawString(place, x, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    // =======================================================================
    // Mouse: select, open, drag as in the Finder, from one section to the other
    // =======================================================================

    private void installTreeBehavior(Pane p) {
        JTree tree = p.tree;
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent e) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) e.getPath().getLastPathComponent();
                if (isLoaded(node) && node.getUserObject() instanceof Entry en) {
                    p.refreshNode(en.file());
                } else {
                    p.load(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent e) {
            }
        });
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent e) {
                saveExpanded();
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent e) {
                saveExpanded();
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pressed(p, e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                released(p, e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                clicked(p, e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                dragged(p, e);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                hover(p, p.rowAt(e.getY()));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover(p, -1);
            }
        };
        tree.addMouseListener(mouse);
        tree.addMouseMotionListener(mouse);
        // Alt, Ctrl or Shift pressed or let go while holding the items still turns the move into a copy, or back.
        tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                modifiersChanged(e);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                modifiersChanged(e);
            }
        });
        tree.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                active = p;
                repaintSections();
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaintSections();
            }
        });
    }

    private void repaintSections() {
        for (Pane p : panes) {
            p.header.repaint();
            p.tree.repaint();
        }
    }

    private void hover(Pane p, int row) {
        if (row != p.hoverRow) {
            p.hoverRow = row;
            p.tree.repaint();
        }
    }

    private void pressed(Pane p, MouseEvent e) {
        JTree tree = p.tree;
        active = p;
        pressPane = p;
        tree.requestFocusInWindow();
        pressPoint = e.getPoint();
        selectionKept = false;
        int row = p.rowAt(e.getY());
        pressPath = row < 0 ? null : tree.getPathForRow(row);
        boolean plain = !e.isControlDown() && !e.isShiftDown() && !e.isMetaDown();
        if (pressPath == null) {
            if (plain) {
                tree.clearSelection();
            }
        } else {
            // The tree selects on its name only: the rest of the row counts too.
            Rectangle b = tree.getRowBounds(row);
            boolean onName = b != null && e.getX() >= b.x && e.getX() < b.x + b.width;
            if (!onName || SwingUtilities.isRightMouseButton(e)) {
                if (SwingUtilities.isLeftMouseButton(e) && e.isControlDown()) {
                    if (tree.isPathSelected(pressPath)) {
                        tree.removeSelectionPath(pressPath);
                    } else {
                        tree.addSelectionPath(pressPath);
                    }
                } else if (SwingUtilities.isLeftMouseButton(e) && e.isShiftDown() && tree.getLeadSelectionRow() >= 0) {
                    tree.setSelectionInterval(tree.getLeadSelectionRow(), row);
                } else if (!tree.isPathSelected(pressPath)) {
                    tree.setSelectionPath(pressPath);
                }
            }
            // One of several selected items pressed: they all go if it is dragged.
            if (plain && SwingUtilities.isLeftMouseButton(e) && pressSelection != null && pressSelection.length > 1
                    && List.of(pressSelection).contains(pressPath)) {
                tree.setSelectionPaths(pressSelection);
                selectionKept = true;
            }
        }
        maybePopup(p, e);
    }

    private void released(Pane p, MouseEvent e) {
        if (dragging) {
            finishDrag();
            return;
        }
        // Clicked, not dragged: as in the Finder, only that item stays selected.
        if (selectionKept && pressPath != null && SwingUtilities.isLeftMouseButton(e)) {
            p.tree.setSelectionPath(pressPath);
        }
        selectionKept = false;
        maybePopup(p, e);
    }

    private void clicked(Pane p, MouseEvent e) {
        if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) {
            return;
        }
        TreePath path = p.pathAt(e.getY());
        Entry en = entryOf(path);
        if (en == null) {
            return;
        }
        if (en.dir()) {
            if (p.tree.isExpanded(path)) {
                p.tree.collapsePath(path);
            } else {
                p.tree.expandPath(path);
            }
        } else {
            host.openFile(en.file());
        }
    }

    /** The pointer is followed in the column of both sections: the press grabbed it, wherever it goes. */
    private void dragged(Pane p, MouseEvent e) {
        if (!SwingUtilities.isLeftMouseButton(e) || p != pressPane || pressPath == null || pressPoint == null) {
            return;
        }
        if (!dragging) {
            if (pressPoint.distance(e.getPoint()) < DRAG_THRESHOLD) {
                return;
            }
            startDrag(p);
            if (!dragging) {
                return;
            }
        }
        dragPoint = SwingUtilities.convertPoint(p.tree, e.getPoint(), column);
        aim(e);
        autoScroll();
        layer.repaint();
    }

    private void startDrag(Pane p) {
        Entry pressed = entryOf(pressPath);
        if (pressed == null) {
            return;
        }
        List<File> files = p.selectedFiles();
        if (!files.contains(pressed.file())) {
            files = List.of(pressed.file());
        }
        dragFiles = files;
        dragging = true;
        selectionKept = false;
        ghostText = files.size() == 1 ? files.get(0).getName()
                                      : files.get(0).getName() + " + " + (files.size() - 1) + " more";
        ghostImage = imageOf(iconFor(pressed, false));
    }

    /**
     * The folder a drop would go to, in either section: the one under the
     * pointer, the folder of the file under it, the section's own place below
     * its last row or on its header. Within one place it moves, as in the
     * Finder, unless Alt or Ctrl is held; between WorkSpace/ and another
     * folder, or to another drive, it copies, unless Shift is held.
     */
    private void aim(InputEvent e) {
        Pane over = null;
        TreePath path = null;
        File folder = null;
        boolean onHeader = false;
        for (Pane p : panes) {
            if (p.header.getBounds().contains(dragPoint)) {
                over = p;
                onHeader = true;
                folder = p.empty ? null : p.home();
                break;
            }
            if (p.open && p.body.getBounds().contains(dragPoint)) {
                over = p;
                if (!p.empty) {
                    path = p.pathAt(SwingUtilities.convertPoint(column, dragPoint, p.tree).y);
                    Entry en = entryOf(path);
                    folder = path == null ? p.home() : en == null ? null : en.dir() ? en.file() : en.file().getParentFile();
                }
                break;
            }
        }
        dropPane = over;
        dropOnHeader = onHeader;
        dragOver = path;
        boolean far = folder != null && !dragFiles.isEmpty() && across(dragFiles.get(0), folder);
        dropCopies = !e.isShiftDown() && (e.isAltDown() || e.isControlDown() || far);
        dropFolder = folder != null && accepts(dragFiles, folder, dropCopies) ? folder : null;
        Cursor c = dropFolder == null ? DragSource.DefaultMoveNoDrop
                 : dropCopies ? DragSource.DefaultCopyDrop : DragSource.DefaultMoveDrop;
        layer.setCursor(c != null ? c : Cursor.getDefaultCursor());
        springOpen(over, path, onHeader);
    }

    /** From one place to another: WorkSpace/ and a folder of FOLDERS, two folders of FOLDERS, two drives. */
    private boolean across(File from, File folder) {
        return !sameDrive(from, folder) || !Objects.equals(placeOf(from), placeOf(folder));
    }

    /** Not onto itself, not into a folder of its own, and a move to where it already is moves nothing. */
    private static boolean accepts(List<File> files, File folder, boolean copy) {
        boolean allHome = true;
        for (File f : files) {
            if (isInside(folder, f)) {
                return false;
            }
            if (!folder.equals(f.getParentFile())) {
                allHome = false;
            }
        }
        return copy || !allHome;
    }

    private static boolean isInside(File f, File dir) {
        return f.getAbsoluteFile().toPath().startsWith(dir.getAbsoluteFile().toPath());
    }

    private static boolean sameDrive(File a, File b) {
        Path ra = a.getAbsoluteFile().toPath().getRoot();
        Path rb = b.getAbsoluteFile().toPath().getRoot();
        return ra != null && ra.equals(rb);
    }

    /** Near the top or the bottom of a section, it scrolls to bring more folders under the pointer. */
    private void autoScroll() {
        if (dropPane == null || dropOnHeader || dropPane.empty) {
            return;
        }
        JViewport view = dropPane.scroll.getViewport();
        int y = SwingUtilities.convertPoint(column, dragPoint, view).y;
        Rectangle visible = dropPane.tree.getVisibleRect();
        if (y < SCROLL_MARGIN) {
            visible.y = Math.max(0, visible.y - SCROLL_MARGIN);
            dropPane.tree.scrollRectToVisible(visible);
        } else if (y > view.getHeight() - SCROLL_MARGIN) {
            visible.y += SCROLL_MARGIN;
            dropPane.tree.scrollRectToVisible(visible);
        }
    }

    /** A closed folder, or a folded section, the items linger on opens by itself, as the Finder's spring-loaded folders do. */
    private void springOpen(Pane over, TreePath path, boolean onHeader) {
        Entry e = entryOf(path);
        boolean closedFolder = over != null && e != null && e.dir() && !over.tree.isExpanded(path);
        boolean closedPane = over != null && onHeader && !over.open;
        if (closedFolder || closedPane) {
            TreePath wanted = closedFolder ? path : null;
            if (over != springPane || !Objects.equals(wanted, springPath)) {
                springPane = over;
                springPath = wanted;
                springTimer.restart();
            }
        } else {
            springPane = null;
            springPath = null;
            springTimer.stop();
        }
    }

    private void springNow() {
        if (!dragging || springPane == null) {
            return;
        }
        if (springPath != null) {
            springPane.tree.expandPath(springPath);
        } else {
            springPane.setOpen(true);
        }
        layer.repaint();
    }

    private void modifiersChanged(KeyEvent e) {
        if (dragging && dragPoint != null) {
            aim(e);
            layer.repaint();
        }
    }

    private void finishDrag() {
        File folder = dropFolder;
        boolean copy = dropCopies;
        List<File> files = dragFiles;
        endDrag();
        if (folder != null) {
            transfer(files, folder, copy);
        }
    }

    private void endDrag() {
        dragging = false;
        dragFiles = List.of();
        dragPoint = null;
        dragOver = null;
        dropPane = null;
        dropOnHeader = false;
        dropFolder = null;
        ghostImage = null;
        ghostText = null;
        springPane = null;
        springPath = null;
        springTimer.stop();
        layer.setCursor(null);
        layer.repaint();
    }

    private static Image imageOf(Icon icon) {
        if (icon instanceof ImageIcon image) {
            return image.getImage();
        }
        BufferedImage img = new BufferedImage(Math.max(1, icon.getIconWidth()), Math.max(1, icon.getIconHeight()),
                                              BufferedImage.TYPE_INT_ARGB);
        Graphics g = img.getGraphics();
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        return img;
    }

    /**
     * The main window explorer's menu, then what moves files here: cut, copy,
     * paste, and sending the selection to the other section.
     */
    private void maybePopup(Pane p, MouseEvent e) {
        if (!e.isPopupTrigger() || dragging || p.empty) {
            return;
        }
        Entry en = entryOf(p.pathAt(e.getY()));
        File target = en == null ? p.home() : en.file();
        if (target == null) {
            // beside several folders of FOLDERS: what adds another
            showFolderMenu(p.tree, e.getX(), e.getY());
            return;
        }
        Window w = SwingUtilities.getWindowAncestor(this);
        JPopupMenu menu = ContextMenuBuilder.createMenu(target, "FULL", this,
                                                        w instanceof SphereCoderFrame frame ? frame : null);
        menu.add(ContextMenuBuilder.thinSeparator());
        if (en != null) {
            menu.add(modernItem("Cut", () -> toClipboard(p, true)));
            menu.add(modernItem("Copy", () -> toClipboard(p, false)));
        }
        if (!clipboardFiles().isEmpty()) {
            menu.add(modernItem("Paste", () -> paste(p, false)));
        }
        if (en != null) {
            File dest = p.other().obviousTarget();
            String where = dest != null && dest.isDirectory() ? " " + placeName(dest) : "...";
            menu.add(modernItem("Copy to" + where + "  (Ctrl+Shift+C)", () -> sendAcross(p, true)));
            menu.add(modernItem("Move to" + where + "  (Ctrl+Shift+X)", () -> sendAcross(p, false)));
        }
        File place = placeOf(target);
        menu.add(modernItem("Copy Relative Path", () -> ClipboardBridge.write(
                en == null || place == null ? "." : place.toPath().relativize(en.file().toPath()).toString())));
        if (en != null && p == folders && roots.contains(en.file())) {
            menu.add(ContextMenuBuilder.thinSeparator());
            menu.add(modernItem("Remove Folder from Explorer", () -> removeFolders(List.of(en.file()))));
        }
        menu.show(p.tree, e.getX(), e.getY());
    }

    private static JMenuItem modernItem(String text, Runnable run) {
        JMenuItem item = ContextMenuBuilder.createModernMenuItem(text);
        item.addActionListener(e -> run.run());
        return item;
    }

    // =======================================================================
    // Keys
    // =======================================================================

    private void installKeys(Pane p) {
        bind(p, "ENTER", "open", () -> openSelection(p, false));
        bind(p, "ctrl ENTER", "openToSide", () -> openSelection(p, true));
        bind(p, "F2", "rename", () -> renameSelection(p));
        bind(p, "DELETE", "trash", () -> trashSelection(p));
        bind(p, "ctrl X", "cut", () -> toClipboard(p, true));
        bind(p, "ctrl C", "copy", () -> toClipboard(p, false));
        bind(p, "ctrl V", "paste", () -> paste(p, false));
        // The Finder's Option+Cmd+V: what was copied is moved here.
        bind(p, "ctrl alt V", "pasteMove", () -> paste(p, true));
        // Shift sends across: to the folder selected in the other section.
        bind(p, "ctrl shift C", "copyAcross", () -> sendAcross(p, true));
        bind(p, "ctrl shift X", "moveAcross", () -> sendAcross(p, false));
        bind(p, "TAB", "otherSection", () -> focusPane(p.other()));
        bind(p, "shift TAB", "otherSectionBack", () -> focusPane(p.other()));
        bind(p, "ctrl Z", "undo", this::undoLast);
        bind(p, "ESCAPE", "escape", this::escape);
    }

    private static void bind(Pane p, String key, String name, Runnable action) {
        p.tree.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), "explorer-" + name);
        p.tree.getActionMap().put("explorer-" + name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void escape() {
        if (dragging) {
            pressPath = null;
            endDrag();
        } else if (!cutFiles.isEmpty()) {
            cutFiles = Set.of();
            repaintSections();
        }
    }

    private void openSelection(Pane p, boolean toSide) {
        TreePath[] paths = p.tree.getSelectionPaths();
        if (paths == null) {
            return;
        }
        if (paths.length == 1 && entryOf(paths[0]) != null && entryOf(paths[0]).dir()) {
            if (p.tree.isExpanded(paths[0])) {
                p.tree.collapsePath(paths[0]);
            } else {
                p.tree.expandPath(paths[0]);
            }
            return;
        }
        for (TreePath path : paths) {
            Entry e = entryOf(path);
            if (e != null && !e.dir()) {
                if (toSide) {
                    host.openFileToSide(e.file());
                } else {
                    host.openFile(e.file());
                }
            }
        }
    }

    // =======================================================================
    // Changing the disk
    // =======================================================================

    private static String reason(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static boolean hasBin() {
        return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
    }

    private static String binName() {
        return File.separatorChar == '\\' ? "the Recycle Bin" : "the Trash";
    }

    private File chooseFolder(String title, String approve) {
        File start = !roots.isEmpty() ? roots.get(roots.size() - 1) : workspaceDir();
        JFileChooser chooser = new JFileChooser(start.isDirectory() ? start : null);
        chooser.setDialogTitle(title);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setApproveButtonText(approve);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        // Opened from inside the folder, with nothing selected, the choice is the folder itself.
        File dir = chooser.getSelectedFile() != null ? chooser.getSelectedFile() : chooser.getCurrentDirectory();
        return dir != null && dir.isDirectory() ? dir.getAbsoluteFile() : null;
    }

    private void create(boolean folder) {
        Pane p = active;
        File parent = p.targetFolder();
        if (parent == null) {
            host.toast("FOLDERS has no folder yet · + adds one");
            return;
        }
        String name = JOptionPane.showInputDialog(this,
                "Name of the new " + (folder ? "folder" : "file") + " in " + placeName(parent) + ":",
                folder ? "New Folder" : "New File", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        File made = new File(parent, name.trim());
        if (made.exists()) {
            host.toast(made.getName() + " already exists");
            reveal(made);
            return;
        }
        try {
            if (folder) {
                Files.createDirectories(made.toPath());
            } else {
                // "src/main.py" makes the folder too
                Files.createDirectories(made.getParentFile().toPath());
                Files.createFile(made.toPath());
            }
        } catch (IOException | InvalidPathException ex) {
            host.toast("Could not create " + name.trim() + ": " + reason(ex));
            return;
        }
        checkPlaces();
        refreshNode(parent);
        reveal(made);
        if (folder) {
            Pane at = paneOf(made);
            TreePath path = at == null ? null : at.pathTo(made);
            if (path != null) {
                at.tree.expandPath(path);
            }
        } else {
            host.openFile(made);
        }
    }

    private void renameSelection(Pane p) {
        List<File> files = p.selectedFiles();
        if (files.size() != 1) {
            return;
        }
        File f = files.get(0);
        Object answer = JOptionPane.showInputDialog(this, "New name for “" + f.getName() + "”:", "Rename",
                                                    JOptionPane.PLAIN_MESSAGE, null, null, f.getName());
        if (!(answer instanceof String s) || s.isBlank() || s.trim().equals(f.getName())) {
            return;
        }
        String name = s.trim();
        File dest = new File(f.getParentFile(), name);
        boolean caseOnly = name.equalsIgnoreCase(f.getName());
        if (dest.exists() && !caseOnly) {
            host.toast("“" + name + "” already exists here");
            return;
        }
        try {
            if (caseOnly) {
                // Windows sees the same file in both names: through a third one.
                File step = new File(f.getParentFile(), name + ".renaming");
                Files.move(f.toPath(), step.toPath());
                Files.move(step.toPath(), dest.toPath());
            } else {
                Files.move(f.toPath(), dest.toPath());
            }
        } catch (IOException | InvalidPathException ex) {
            host.toast("Could not rename " + f.getName() + ": " + reason(ex));
            return;
        }
        moved(f, dest);
        remember(new Batch(false, List.<File[]>of(new File[] { f, dest })));
        refreshNode(f.getParentFile());
        p.reveal(dest);
    }

    /**
     * Delete sends to the Recycle Bin (the Trash); where there is none, it asks
     * before deleting for good. A folder of FOLDERS is first offered to leave
     * the explorer only, which is most often what is meant.
     */
    private void trashSelection(Pane p) {
        List<File> files = p.selectedFiles();
        if (files.isEmpty()) {
            return;
        }
        boolean bin = hasBin();
        String what = files.size() == 1 ? "“" + files.get(0).getName() + "”" : files.size() + " items";
        if (p == folders && roots.containsAll(files)) {
            String[] options = { "Remove from Explorer", bin ? "Move to " + binName().substring(4) : "Delete for Good",
                                 "Cancel" };
            int answer = JOptionPane.showOptionDialog(this,
                    what + " can leave the explorer and stay on the disk.", "Remove Folder",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (answer == 0) {
                removeFolders(files);
                return;
            }
            if (answer != 1) {
                return;
            }
        } else {
            int answer = JOptionPane.showConfirmDialog(this,
                    bin ? "Move " + what + " to " + binName() + "?" : "Delete " + what + " for good? This cannot be undone.",
                    bin ? "Move to " + binName().substring(4) : "Delete", JOptionPane.OK_CANCEL_OPTION,
                    bin ? JOptionPane.QUESTION_MESSAGE : JOptionPane.WARNING_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                return;
            }
        }
        Set<File> touched = new LinkedHashSet<>();
        List<File> goneRoots = new ArrayList<>();
        int gone = 0;
        for (File f : files) {
            boolean ok = bin ? Desktop.getDesktop().moveToTrash(f)
                       : f.isDirectory() ? ContextMenuBuilder.deleteDirectoryRecursive(f) : f.delete();
            if (ok) {
                gone++;
                touched.add(f.getParentFile());
                if (roots.contains(f)) {
                    goneRoots.add(f);
                }
            } else {
                host.toast("Could not remove " + f.getName());
            }
        }
        for (File d : touched) {
            refreshNode(d);
        }
        if (!goneRoots.isEmpty()) {
            List<File> next = new ArrayList<>(roots);
            next.removeAll(goneRoots);
            settleRoots(next, expandedKept());
        }
        if (gone > 0) {
            host.toast((bin ? "Moved to " + binName() + ": " : "Deleted: ")
                       + (gone == 1 ? files.get(0).getName() : gone + " items"));
        }
    }

    /**
     * Copies or moves files into a folder of either section, as the Finder
     * does: a name taken there asks whether to keep both, stop or replace, for
     * all the items at once if wished; a copy into its own folder is named
     * "… copy". The editors follow what moved, and what arrived glows.
     */
    private void transfer(List<File> files, File folder, boolean copy) {
        if (folder == null || !folder.isDirectory()) {
            host.toast((folder == null ? "That folder" : placeName(folder)) + " is not there");
            return;
        }
        List<File[]> done = new ArrayList<>();
        Set<File> touched = new LinkedHashSet<>();
        touched.add(folder);
        JCheckBox all = files.size() > 1 ? new JCheckBox("Do the same for the other items") : null;
        int always = -1;
        for (File src : files) {
            if (!src.exists() || isInside(folder, src)) {
                continue;
            }
            boolean home = folder.equals(src.getParentFile());
            if (home && !copy) {
                continue;
            }
            File dest = new File(folder, src.getName());
            if (home) {
                dest = freeName(folder, src, true);
            } else if (dest.exists()) {
                if (isInside(src, dest)) {
                    host.toast(dest.getName() + " holds " + src.getName() + " and cannot be replaced by it");
                    continue;
                }
                int choice = always >= 0 ? always : askReplace(dest, copy, all);
                if (all != null && all.isSelected()) {
                    always = choice;
                }
                if (choice == 0) {
                    dest = freeName(folder, src, false);
                } else if (choice == 2) {
                    if (!(dest.isDirectory() ? ContextMenuBuilder.deleteDirectoryRecursive(dest) : dest.delete())) {
                        host.toast("Could not replace " + dest.getName());
                        break;
                    }
                } else {
                    break;
                }
            }
            try {
                if (copy) {
                    copyTree(src.toPath(), dest.toPath());
                } else {
                    move(src, dest);
                    moved(src, dest);
                }
                done.add(new File[] { src, dest.getAbsoluteFile() });
                touched.add(src.getParentFile());
            } catch (IOException ex) {
                host.toast("Could not " + (copy ? "copy " : "move ") + src.getName() + ": " + reason(ex));
                break;
            }
        }
        for (File d : touched) {
            refreshNode(d);
        }
        if (done.isEmpty()) {
            return;
        }
        boolean undoable = !copy || hasBin();
        if (undoable) {
            remember(new Batch(copy, done));
        }
        rememberTarget(folder);
        List<File> arrived = new ArrayList<>();
        for (File[] m : done) {
            arrived.add(m[1]);
        }
        selectMoved(done, false);
        glow(arrived);
        String what = done.size() == 1 ? "“" + done.get(0)[1].getName() + "”" : done.size() + " items";
        host.toast((copy ? "Copied " : "Moved ") + what + " to " + placeName(folder)
                   + (!undoable ? "" : copy ? " · Ctrl+Z takes it back" : " · Ctrl+Z puts it back"));
    }

    /** Keep Both (0), Stop (1) or Replace (2), the Finder's question; with more to come, for them all at once. */
    private int askReplace(File dest, boolean copy, JCheckBox all) {
        String[] options = { "Keep Both", "Stop", "Replace" };
        String text = "An item named “" + dest.getName() + "” already exists in " + placeName(dest.getParentFile())
                      + ".\nReplace it with the one you are " + (copy ? "copying" : "moving") + "?";
        return JOptionPane.showOptionDialog(this, all == null ? text : new Object[] { text, all },
                "Item Already Exists", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                options, options[0]);
    }

    /** The Finder's names: "notes copy.txt", "notes copy 2.txt"; "notes 2.txt" to keep both. */
    private static File freeName(File folder, File src, boolean asCopy) {
        String name = src.getName();
        int dot = src.isDirectory() ? -1 : name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = asCopy ? 1 : 2; ; n++) {
            String candidate = asCopy ? base + " copy" + (n == 1 ? "" : " " + n) + ext : base + " " + n + ext;
            File f = new File(folder, candidate);
            if (!f.exists()) {
                return f;
            }
        }
    }

    /** A rename where it can be; a folder going to another drive is copied there, then removed here. */
    private static void move(File src, File dest) throws IOException {
        try {
            Files.move(src.toPath(), dest.toPath());
        } catch (DirectoryNotEmptyException | AtomicMoveNotSupportedException otherDrive) {
            copyTree(src.toPath(), dest.toPath());
            if (!ContextMenuBuilder.deleteDirectoryRecursive(src)) {
                throw new IOException(src.getName() + " was copied but could not be removed from where it was");
            }
        }
    }

    private static void copyTree(Path src, Path dest) throws IOException {
        if (!Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(src, dest, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
            return;
        }
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dest.resolve(src.relativize(p));
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                }
            }
        }
    }

    private void remember(Batch batch) {
        undo.push(batch);
        while (undo.size() > MAX_UNDO) {
            undo.removeLast();
        }
    }

    /** The last folders things were sent to, offered when the other section does not say where. */
    private static void rememberTarget(File folder) {
        String k = keep(folder);
        List<String> recent = CoderConfig.texts(SECTION, "recentTargets");
        recent.remove(k);
        recent.add(0, k);
        while (recent.size() > MAX_TARGETS) {
            recent.remove(recent.size() - 1);
        }
        CoderConfig.put(SECTION, "recentTargets", recent);
    }

    /**
     * Selects where things went (or came back to), in whichever section shows
     * them, unfolding their folders; the section they landed in opens if folded.
     */
    private void selectMoved(List<File[]> batch, boolean back) {
        Pane landing = paneOf(back ? batch.get(0)[0] : batch.get(0)[1]);
        for (Pane p : panes) {
            List<TreePath> paths = new ArrayList<>();
            for (File[] m : batch) {
                File f = back ? m[0] : m[1];
                TreePath path = p.holds(f) ? p.pathTo(f) : null;
                if (path != null && path.getPathCount() > 1) {
                    if (path.getParentPath() != null) {
                        p.tree.expandPath(path.getParentPath());
                    }
                    paths.add(path);
                }
            }
            if (paths.isEmpty()) {
                continue;
            }
            if (p == landing) {
                p.setOpen(true);
            }
            p.tree.setSelectionPaths(paths.toArray(new TreePath[0]));
            TreePath first = paths.get(0);
            // after the layout, when the section just opened
            SwingUtilities.invokeLater(() -> p.scrollTo(first));
        }
    }

    /** Ctrl+Z: the last move or rename goes back, as the Finder's Undo Move; the last copy goes to the bin. */
    private void undoLast() {
        if (undo.isEmpty()) {
            host.toast("Nothing to undo in the explorer");
            return;
        }
        Batch last = undo.pop();
        Set<File> touched = new LinkedHashSet<>();
        int back = 0;
        if (last.copy()) {
            for (File[] m : last.pairs()) {
                if (m[1].exists() && Desktop.getDesktop().moveToTrash(m[1])) {
                    touched.add(m[1].getParentFile());
                    back++;
                }
            }
            for (File d : touched) {
                refreshNode(d);
            }
            host.toast(back == 0 ? "Nothing to take back"
                       : "Took back the copy of " + (back == 1 ? last.pairs().get(0)[0].getName() : back + " items")
                         + " · it is in " + binName());
            return;
        }
        List<File[]> pairs = last.pairs();
        for (int i = pairs.size() - 1; i >= 0; i--) {
            File from = pairs.get(i)[0];
            File to = pairs.get(i)[1];
            if (!to.exists() || from.exists()) {
                continue;
            }
            try {
                move(to, from);
                moved(to, from);
                touched.add(from.getParentFile());
                touched.add(to.getParentFile());
                back++;
            } catch (IOException ex) {
                host.toast("Could not put back " + to.getName() + ": " + reason(ex));
            }
        }
        for (File d : touched) {
            refreshNode(d);
        }
        if (back > 0) {
            selectMoved(pairs, true);
        }
        host.toast(back == 0 ? "Nothing to put back" : "Put back " + (back == 1 ? pairs.get(0)[0].getName() : back + " items"));
    }

    // ---- What just arrived glows, then fades

    private void glow(List<File> files) {
        long now = System.currentTimeMillis();
        for (File f : files) {
            arrivals.put(f.getAbsoluteFile(), now);
        }
        glowTimer.start();
    }

    private void fadeArrivals() {
        long now = System.currentTimeMillis();
        arrivals.values().removeIf(t -> now - t > GLOW_MS);
        for (Pane p : panes) {
            p.tree.repaint();
        }
        if (arrivals.isEmpty()) {
            glowTimer.stop();
        }
    }

    private float glowOf(File f, long now) {
        Long t = arrivals.get(f);
        if (t == null) {
            return 0f;
        }
        float left = Math.max(0f, 1f - (now - t) / (float) GLOW_MS);
        return left * left;
    }

    // ---- To the other section, as in a two-pane file manager

    /**
     * Ctrl+Shift+C copies the selection, Ctrl+Shift+X moves it, to the folder
     * selected in the other section, or to its one place; when it does not say
     * where, the places it could be are offered.
     */
    private void sendAcross(Pane from, boolean copy) {
        List<File> files = from.selectedFiles();
        if (files.isEmpty()) {
            host.toast("Select what to " + (copy ? "copy" : "move") + " to the other section first");
            return;
        }
        File dest = from.other().obviousTarget();
        if (dest != null && dest.isDirectory() && accepts(files, dest, copy)) {
            transfer(files, dest, copy);
        } else {
            showDestinations(from, files, copy);
        }
    }

    /** The other section's folders, then the folders things were sent to lately, then any other. */
    private void showDestinations(Pane from, List<File> files, boolean copy) {
        Set<File> offered = new LinkedHashSet<>();
        if (from.other().ws) {
            offered.add(workspaceDir());
        } else {
            offered.addAll(roots);
        }
        for (String k : CoderConfig.texts(SECTION, "recentTargets")) {
            offered.add(unkeep(k));
        }
        offered.removeIf(d -> !d.isDirectory() || !accepts(files, d, copy));
        String verb = copy ? "Copy to " : "Move to ";
        JPopupMenu menu = ContextMenuBuilder.createMenu(files.get(0), "PICK", this, null);
        for (File d : offered) {
            menu.add(modernItem(verb + placeName(d), () -> transfer(files, d, copy)));
        }
        if (!offered.isEmpty()) {
            menu.add(ContextMenuBuilder.thinSeparator());
        }
        menu.add(modernItem(verb + "Another Folder...", () -> {
            File d = chooseFolder((copy ? "Copy" : "Move") + " to a Folder", copy ? "Copy Here" : "Move Here");
            if (d != null) {
                transfer(files, d, copy);
            }
        }));
        TreePath lead = from.tree.getLeadSelectionPath();
        Rectangle b = lead == null ? null : from.tree.getPathBounds(lead);
        menu.show(from.tree, b == null ? 0 : b.x + 16, b == null ? 0 : b.y + b.height);
    }

    // ---- Clipboard: the system's, so Windows Explorer or the Finder can paste too

    /** Files on the clipboard, for this explorer and any other program. */
    private static final class FileSelection implements Transferable {
        private final List<File> files;

        FileSelection(List<File> files) {
            this.files = List.copyOf(files);
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] { DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor };
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor) || DataFlavor.stringFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (DataFlavor.javaFileListFlavor.equals(flavor)) {
                return files;
            }
            if (DataFlavor.stringFlavor.equals(flavor)) {
                List<String> paths = new ArrayList<>();
                for (File f : files) {
                    paths.add(f.getAbsolutePath());
                }
                return String.join(System.lineSeparator(), paths);
            }
            throw new UnsupportedFlavorException(flavor);
        }
    }

    private void toClipboard(Pane p, boolean cut) {
        List<File> files = p.selectedFiles();
        if (files.isEmpty()) {
            return;
        }
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new FileSelection(files), null);
        } catch (IllegalStateException busy) {
            host.toast("The clipboard is busy, try again");
            return;
        }
        cutFiles = cut ? new HashSet<>(files) : Set.of();
        repaintSections();
        String what = files.size() == 1 ? files.get(0).getName() : files.size() + " items";
        host.toast((cut ? "Cut " : "Copied ") + what + (cut ? " · Ctrl+V moves it" : " · Ctrl+V pastes it"));
    }

    private static List<File> clipboardFiles() {
        try {
            Transferable t = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                List<File> out = new ArrayList<>();
                for (Object o : (List<?>) t.getTransferData(DataFlavor.javaFileListFlavor)) {
                    if (o instanceof File f && f.exists()) {
                        out.add(f.getAbsoluteFile());
                    }
                }
                return out;
            }
        } catch (UnsupportedFlavorException | IOException | IllegalStateException unreadable) {
            // nothing to paste
        }
        return List.of();
    }

    /** Pastes into the selected folder of a section: what was cut here moves, the rest is copied (Ctrl+Alt+V moves it). */
    private void paste(Pane p, boolean forceMove) {
        File target = p.targetFolder();
        if (target == null) {
            host.toast("FOLDERS has no folder yet · + adds one");
            return;
        }
        List<File> files = clipboardFiles();
        if (files.isEmpty()) {
            host.toast("Nothing to paste");
            return;
        }
        boolean move = forceMove || (!cutFiles.isEmpty() && cutFiles.containsAll(files));
        transfer(files, target, !move);
        if (move) {
            cutFiles = Set.of();
        }
    }
}

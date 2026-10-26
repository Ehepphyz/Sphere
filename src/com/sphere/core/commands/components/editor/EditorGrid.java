package com.sphere.components.editor;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The layout of the editor groups: a binary tree of split panes whose leaves
 * are the groups. Splitting a group puts a new one beside or below it, closing
 * one hands its room to its neighbour. Each split keeps its share as a
 * fraction, so the grid survives a resize, evens out, maximizes one group and
 * comes back, and is written to a line of text and rebuilt from it.
 */
public final class EditorGrid extends JPanel {

    /** Where a new group goes relative to an existing one. */
    public enum Side {
        LEFT, RIGHT, TOP, BOTTOM;

        public boolean horizontal() {
            return this == LEFT || this == RIGHT;
        }

        boolean before() {
            return this == LEFT || this == TOP;
        }
    }

    private static final int DIVIDER_SIZE = 5;

    private Component root;
    private final Map<JComponent, Cell> cells = new IdentityHashMap<>();
    private JComponent maximized;
    private Map<GridSplit, Double> beforeMaximize;
    private Runnable onLayoutChanged = () -> { };

    public EditorGrid(JComponent first) {
        super(new BorderLayout());
        setOpaque(false);
        setRoot(cellOf(first));
    }

    /** Told when a split is added, removed, dragged or evened out (for saving the layout). */
    public void setOnLayoutChanged(Runnable r) {
        onLayoutChanged = r == null ? () -> { } : r;
    }

    // ---- Structure ---------------------------------------------------------

    /** Puts {@code added} on one side of {@code existing}, sharing its room half and half. */
    public void split(JComponent existing, JComponent added, Side side) {
        split(existing, added, side, 0.5);
    }

    /** As {@link #split(JComponent, JComponent, Side)}, {@code share} being the new group's part. */
    public void split(JComponent existing, JComponent added, Side side, double share) {
        restore();
        Cell target = cells.get(existing);
        if (target == null) {
            throw new IllegalArgumentException("not a group of this grid");
        }
        Cell fresh = cellOf(added);
        GridSplit split = new GridSplit(side.horizontal());
        replace(target, split);
        if (side.before()) {
            split.setLeftComponent(fresh);
            split.setRightComponent(target);
        } else {
            split.setLeftComponent(target);
            split.setRightComponent(fresh);
        }
        split.setFraction(side.before() ? share : 1 - share);
        changed();
    }

    /** Takes a group out; the split holding it gives all its room to the other side. */
    public void removeGroup(JComponent leaf) {
        Cell cell = cells.get(leaf);
        if (cell == null || cell == root) {
            return;
        }
        restore();
        GridSplit parent = (GridSplit) cell.getParent();
        Component sibling = parent.getLeftComponent() == cell ? parent.getRightComponent() : parent.getLeftComponent();
        parent.remove(cell);
        parent.remove(sibling);
        replace(parent, sibling);
        cells.remove(leaf);
        cell.remove(leaf);
        changed();
    }

    /** Puts {@code replacement} where {@code old} is, in its parent split or at the root. */
    private void replace(Component old, Component replacement) {
        Container parent = old.getParent();
        if (old == root || parent == this) {
            remove(old);
            setRoot(replacement);
            return;
        }
        GridSplit split = (GridSplit) parent;
        int location = split.getDividerLocation();
        if (split.getLeftComponent() == old) {
            split.setLeftComponent(replacement);
        } else {
            split.setRightComponent(replacement);
        }
        split.setDividerLocation(location);
    }

    private void setRoot(Component c) {
        removeAll();
        root = c;
        add(c, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private Cell cellOf(JComponent leaf) {
        Cell c = new Cell(leaf);
        cells.put(leaf, c);
        return c;
    }

    private void changed() {
        revalidate();
        repaint();
        onLayoutChanged.run();
    }

    // ---- Queries -----------------------------------------------------------

    /** The groups in reading order: left to right, then top to bottom within a column. */
    public List<JComponent> leaves() {
        List<JComponent> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Component c, List<JComponent> out) {
        if (c instanceof Cell cell) {
            out.add(cell.leaf);
        } else if (c instanceof GridSplit s) {
            collect(s.getLeftComponent(), out);
            collect(s.getRightComponent(), out);
        }
    }

    public int groupCount() {
        return cells.size();
    }

    public boolean hasGroup(JComponent leaf) {
        return cells.containsKey(leaf);
    }

    /** Where a group sits, in this grid's coordinates. */
    public Rectangle boundsOf(JComponent leaf) {
        Cell c = cells.get(leaf);
        if (c == null) {
            return new Rectangle();
        }
        return SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), this);
    }

    /**
     * The group next to this one on a side, by where they are on screen rather
     * than in the tree: of those across that edge, the one facing it most.
     */
    public JComponent neighbor(JComponent leaf, Side side) {
        Rectangle from = boundsOf(leaf);
        JComponent best = null;
        long bestScore = Long.MAX_VALUE;
        for (JComponent other : leaves()) {
            if (other == leaf) {
                continue;
            }
            Rectangle r = boundsOf(other);
            int gap;
            int overlap;
            int centerDistance;
            switch (side) {
                case LEFT:
                    gap = from.x - (r.x + r.width);
                    overlap = overlap(from.y, from.height, r.y, r.height);
                    centerDistance = Math.abs((r.y + r.height / 2) - (from.y + from.height / 2));
                    break;
                case RIGHT:
                    gap = r.x - (from.x + from.width);
                    overlap = overlap(from.y, from.height, r.y, r.height);
                    centerDistance = Math.abs((r.y + r.height / 2) - (from.y + from.height / 2));
                    break;
                case TOP:
                    gap = from.y - (r.y + r.height);
                    overlap = overlap(from.x, from.width, r.x, r.width);
                    centerDistance = Math.abs((r.x + r.width / 2) - (from.x + from.width / 2));
                    break;
                case BOTTOM:
                default:
                    gap = r.y - (from.y + from.height);
                    overlap = overlap(from.x, from.width, r.x, r.width);
                    centerDistance = Math.abs((r.x + r.width / 2) - (from.x + from.width / 2));
                    break;
            }
            if (gap < -DIVIDER_SIZE || overlap <= 0) {
                continue;
            }
            long score = (long) Math.max(0, gap) * 100_000L + centerDistance;
            if (score < bestScore) {
                bestScore = score;
                best = other;
            }
        }
        return best;
    }

    private static int overlap(int a, int la, int b, int lb) {
        return Math.min(a + la, b + lb) - Math.max(a, b);
    }

    /** The side of {@code leaf} on which a fresh split has the most room. */
    public Side roomiestSide(JComponent leaf) {
        Rectangle r = boundsOf(leaf);
        // Code reads in lines: columns are worth more than rows, so a group
        // is cut across only when it is clearly taller than it is wide.
        return r.height > r.width * 1.25 ? Side.BOTTOM : Side.RIGHT;
    }

    // ---- Proportions -------------------------------------------------------

    /** Gives every group the same share of the room along each direction. */
    public void evenOut() {
        restore();
        even(root);
        changed();
    }

    private static void even(Component c) {
        if (c instanceof GridSplit s) {
            boolean h = s.getOrientation() == JSplitPane.HORIZONTAL_SPLIT;
            int a = span(s.getLeftComponent(), h);
            int b = span(s.getRightComponent(), h);
            s.setFraction(a / (double) (a + b));
            even(s.getLeftComponent());
            even(s.getRightComponent());
        }
    }

    /** How many groups a subtree lines up along one direction. */
    private static int span(Component c, boolean horizontal) {
        if (c instanceof GridSplit s && (s.getOrientation() == JSplitPane.HORIZONTAL_SPLIT) == horizontal) {
            return span(s.getLeftComponent(), horizontal) + span(s.getRightComponent(), horizontal);
        }
        if (c instanceof GridSplit s) {
            return Math.max(span(s.getLeftComponent(), horizontal), span(s.getRightComponent(), horizontal));
        }
        return 1;
    }

    /** Turns rows into columns and columns into rows, keeping every share. */
    public void flipOrientation() {
        restore();
        flip(root);
        changed();
    }

    private static void flip(Component c) {
        if (c instanceof GridSplit s) {
            double f = s.currentFraction();
            s.setOrientation(s.getOrientation() == JSplitPane.HORIZONTAL_SPLIT
                             ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT);
            s.setFraction(f);
            flip(s.getLeftComponent());
            flip(s.getRightComponent());
        }
    }

    /** Lets one group take the whole grid while the others fold away; again to bring them back. */
    public void toggleMaximized(JComponent leaf) {
        if (maximized != null) {
            boolean same = maximized == leaf;
            restore();
            if (same) {
                return;
            }
        }
        Cell cell = cells.get(leaf);
        if (cell == null || cell == root) {
            return;
        }
        beforeMaximize = new IdentityHashMap<>();
        remember(root);
        maximized = leaf;
        Component child = cell;
        Container parent = cell.getParent();
        while (parent instanceof GridSplit s) {
            s.collapseToward(s.getLeftComponent() == child);
            child = s;
            parent = s.getParent();
        }
        revalidate();
        repaint();
    }

    private void remember(Component c) {
        if (c instanceof GridSplit s) {
            beforeMaximize.put(s, s.currentFraction());
            remember(s.getLeftComponent());
            remember(s.getRightComponent());
        }
    }

    public boolean isMaximized() {
        return maximized != null;
    }

    public JComponent maximizedLeaf() {
        return maximized;
    }

    /** Brings back the layout a maximize folded away. */
    public void restore() {
        if (maximized == null) {
            return;
        }
        maximized = null;
        if (beforeMaximize != null) {
            for (Map.Entry<GridSplit, Double> e : beforeMaximize.entrySet()) {
                e.getKey().unfold(e.getValue());
            }
        }
        beforeMaximize = null;
        revalidate();
        repaint();
    }

    // ---- Text form ---------------------------------------------------------

    /**
     * The layout as one line: {@code G} for a group, {@code H(0.500,a,b)} for a
     * row of two, {@code V(0.500,a,b)} for a column.
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        describe(root, sb);
        return sb.toString();
    }

    private void describe(Component c, StringBuilder sb) {
        if (c instanceof GridSplit s) {
            double f = beforeMaximize != null && beforeMaximize.containsKey(s) ? beforeMaximize.get(s) : s.currentFraction();
            sb.append(s.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? 'H' : 'V').append('(')
              .append(String.format(Locale.ROOT, "%.3f", f)).append(',');
            describe(s.getLeftComponent(), sb);
            sb.append(',');
            describe(s.getRightComponent(), sb);
            sb.append(')');
        } else {
            sb.append('G');
        }
    }

    /**
     * Replaces the layout with one read by {@link #describe()}, asking the
     * factory for each group. Returns the groups in reading order, or null when
     * the text is not a layout (the grid is left as it was).
     */
    public List<JComponent> rebuild(String spec, Supplier<JComponent> factory) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        List<JComponent> made = new ArrayList<>();
        Map<JComponent, Cell> fresh = new IdentityHashMap<>();
        Component tree;
        try {
            int[] pos = { 0 };
            tree = parse(spec.trim(), pos, factory, made, fresh);
            if (pos[0] != spec.trim().length()) {
                return null;
            }
        } catch (RuntimeException ex) {
            return null;
        }
        maximized = null;
        beforeMaximize = null;
        cells.clear();
        cells.putAll(fresh);
        setRoot(tree);
        changed();
        return made;
    }

    private Component parse(String s, int[] pos, Supplier<JComponent> factory, List<JComponent> made,
                            Map<JComponent, Cell> fresh) {
        char c = s.charAt(pos[0]);
        if (c == 'G') {
            pos[0]++;
            JComponent leaf = factory.get();
            made.add(leaf);
            Cell cell = new Cell(leaf);
            fresh.put(leaf, cell);
            return cell;
        }
        if (c != 'H' && c != 'V') {
            throw new IllegalArgumentException("layout: " + c);
        }
        pos[0]++;
        expect(s, pos, '(');
        int comma = s.indexOf(',', pos[0]);
        double f = Double.parseDouble(s.substring(pos[0], comma));
        pos[0] = comma + 1;
        Component a = parse(s, pos, factory, made, fresh);
        expect(s, pos, ',');
        Component b = parse(s, pos, factory, made, fresh);
        expect(s, pos, ')');
        GridSplit split = new GridSplit(c == 'H');
        split.setLeftComponent(a);
        split.setRightComponent(b);
        split.setFraction(Math.max(0.05, Math.min(0.95, f)));
        return split;
    }

    private static void expect(String s, int[] pos, char c) {
        if (s.charAt(pos[0]) != c) {
            throw new IllegalArgumentException("layout: expected " + c);
        }
        pos[0]++;
    }

    // ---- Pieces ------------------------------------------------------------

    /** Holds one group; small enough to fold away when another is maximized. */
    private final class Cell extends JPanel {
        final JComponent leaf;

        Cell(JComponent leaf) {
            super(new BorderLayout());
            this.leaf = leaf;
            setOpaque(false);
            add(leaf, BorderLayout.CENTER);
        }

        @Override
        public Dimension getMinimumSize() {
            return maximized != null ? new Dimension(0, 0) : new Dimension(90, 70);
        }
    }

    /** A split whose share is a fraction applied once it has a size, and kept through resizes. */
    private final class GridSplit extends JSplitPane {
        private double pending = -1;
        private boolean folded;

        GridSplit(boolean horizontal) {
            super(horizontal ? HORIZONTAL_SPLIT : VERTICAL_SPLIT);
            setBorder(BorderFactory.createEmptyBorder());
            setContinuousLayout(true);
            setDividerSize(DIVIDER_SIZE);
            setOpaque(false);
            addPropertyChangeListener(DIVIDER_LOCATION_PROPERTY, e -> {
                // A dragged divider sets the share kept when the window resizes.
                int extent = extent();
                if (!folded && pending < 0 && extent > 0) {
                    setResizeWeight(Math.max(0, Math.min(1, getDividerLocation() / (double) extent)));
                }
            });
        }

        @Override
        public void updateUI() {
            setUI(new GridSplitUI());
        }

        /** F6, F8 and Ctrl+Tab belong to the editor, not to this divider. */
        @Override
        protected boolean processKeyBinding(javax.swing.KeyStroke ks, java.awt.event.KeyEvent e, int condition,
                                            boolean pressed) {
            if (condition == WHEN_ANCESTOR_OF_FOCUSED_COMPONENT) {
                return false;
            }
            return super.processKeyBinding(ks, e, condition, pressed);
        }

        int extent() {
            int size = getOrientation() == HORIZONTAL_SPLIT ? getWidth() : getHeight();
            return Math.max(0, size - getDividerSize());
        }

        void setFraction(double f) {
            pending = Math.max(0, Math.min(1, f));
            setResizeWeight(pending);
            revalidate();
        }

        double currentFraction() {
            if (pending >= 0) {
                return pending;
            }
            int extent = extent();
            return extent <= 0 ? getResizeWeight() : Math.max(0, Math.min(1, getDividerLocation() / (double) extent));
        }

        /** Hands all the room to one side (true: the left or top one). */
        void collapseToward(boolean first) {
            folded = true;
            pending = -1;
            setDividerSize(0);
            setResizeWeight(first ? 1 : 0);
            pending = first ? 1 : 0;
            revalidate();
        }

        void unfold(double fraction) {
            folded = false;
            setDividerSize(DIVIDER_SIZE);
            setFraction(fraction);
        }

        @Override
        public void doLayout() {
            if (pending >= 0 && extent() > 0) {
                double f = pending;
                pending = -1;
                setDividerLocation((int) Math.round(extent() * f));
                if (!folded) {
                    setResizeWeight(f);
                }
            }
            super.doLayout();
        }
    }

    /** A thin divider in the chrome's border color, brighter while hovered or dragged. */
    private final class GridSplitUI extends BasicSplitPaneUI {
        @Override
        public BasicSplitPaneDivider createDefaultDivider() {
            BasicSplitPaneDivider d = new BasicSplitPaneDivider(this) {
                private boolean hover;

                {
                    setBorder(null);
                    MouseAdapter m = new MouseAdapter() {
                        @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                        @Override public void mouseExited(MouseEvent e) { hover = false; repaint(); }
                        @Override public void mouseReleased(MouseEvent e) { onLayoutChanged.run(); }
                        @Override public void mouseClicked(MouseEvent e) {
                            // A double click on a divider evens the grid out.
                            if (e.getClickCount() == 2) {
                                evenOut();
                            }
                        }
                    };
                    addMouseListener(m);
                    setCursor(Cursor.getPredefinedCursor(splitPane.getOrientation() == JSplitPane.HORIZONTAL_SPLIT
                                                         ? Cursor.E_RESIZE_CURSOR : Cursor.N_RESIZE_CURSOR));
                }

                @Override
                public void paint(Graphics g) {
                    g.setColor(EditorTheme.chrome());
                    g.fillRect(0, 0, getWidth(), getHeight());
                    boolean horizontal = splitPane.getOrientation() == JSplitPane.HORIZONTAL_SPLIT;
                    g.setColor(hover || isMouseDragging() ? EditorTheme.focus() : EditorTheme.chromeBorder());
                    if (horizontal) {
                        int w = hover || isMouseDragging() ? 3 : 1;
                        g.fillRect((getWidth() - w) / 2, 0, w, getHeight());
                    } else {
                        int h = hover || isMouseDragging() ? 3 : 1;
                        g.fillRect(0, (getHeight() - h) / 2, getWidth(), h);
                    }
                }

                private boolean isMouseDragging() {
                    return dragger != null;
                }
            };
            return d;
        }
    }
}

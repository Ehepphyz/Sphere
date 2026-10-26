package com.sphere.components.vault;

import com.sphere.components.vault.VaultQuery.Hit;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * The results, two lines a row: the badge, the name with the letters the search
 * matched lit, what marks the item (pinned, twin, an issue, arguments, a plot)
 * and how long ago it changed; under it, the description or, failing one, what
 * the file uses. A row under the mouse shows a run button where the age was.
 */
final class VaultList extends JList<Hit> {

    static final int ROW = 46;
    private static final int PLAY = 26;

    interface Host {
        VaultIndex index();

        String emptyTitle();

        String emptyHint();
    }

    private final Host host;
    private int hover = -1;
    private boolean hoverPlay;

    VaultList(DefaultListModel<Hit> model, Host host) {
        super(model);
        this.host = host;
        setOpaque(false);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        setFixedCellHeight(ROW);
        setCellRenderer(new Cell());
        setLayoutOrientation(JList.VERTICAL);
        final MouseAdapter tracker = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                track(e.getPoint());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = -1;
                hoverPlay = false;
                repaint();
            }
        };
        addMouseMotionListener(tracker);
        addMouseListener(tracker);
    }

    private void track(Point p) {
        int index = locationToIndex(p);
        if (index >= 0) {
            final Rectangle r = getCellBounds(index, index);
            if (r == null || !r.contains(p)) index = -1;
        }
        final boolean play = index >= 0 && p.x >= getWidth() - PLAY - 6
            && VaultRunner.runnable(getModel().getElementAt(index).item());
        if (index != hover || play != hoverPlay) {
            hover = index;
            hoverPlay = play;
            repaint();
        }
    }

    /** Whether a click at this point lands on a row's run button. */
    boolean onPlay(Point p) {
        final int index = locationToIndex(p);
        if (index < 0) return false;
        final Rectangle r = getCellBounds(index, index);
        return r != null && r.contains(p) && p.x >= getWidth() - PLAY - 6
            && VaultRunner.runnable(getModel().getElementAt(index).item());
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        // Filling the viewport makes the space under the rows a drop target too.
        return getParent() != null && getParent().getHeight() > getPreferredSize().height;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        // The panel's own ground, then the rows: Nimbus's list painter would
        // keep the colors of the theme it was installed with.
        graphics.setColor(VaultLook.panel());
        graphics.fillRect(0, 0, getWidth(), getHeight());
        getUI().paint(graphics, this);
        if (getModel().getSize() > 0) return;
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            VaultLook.antialias(g);
            final int w = getWidth();
            final int top = Math.max(30, getHeight() / 2 - 40);
            final Graphics2D gg = (Graphics2D) g.create();
            gg.translate(w / 2 - 14, top - 34);
            gg.setColor(VaultLook.faint());
            VaultLook.draw(gg, "layer", 28);
            gg.dispose();
            g.setFont(VaultLook.ui(Font.BOLD, 12.5f));
            FontMetrics fm = g.getFontMetrics();
            final String title = VaultLook.fit(host.emptyTitle(), fm, w - 24);
            g.setColor(VaultLook.text());
            g.drawString(title, (w - fm.stringWidth(title)) / 2, top + 6);
            g.setFont(VaultLook.ui(Font.PLAIN, 11f));
            fm = g.getFontMetrics();
            g.setColor(VaultLook.muted());
            int y = top + 26;
            for (String line : wrap(host.emptyHint(), fm, w - 32)) {
                g.drawString(line, (w - fm.stringWidth(line)) / 2, y);
                y += fm.getHeight();
            }
        } finally {
            g.dispose();
        }
    }

    static java.util.List<String> wrap(String text, FontMetrics fm, int width) {
        final java.util.List<String> lines = new java.util.ArrayList<>();
        for (String paragraph : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                final String probe = line.length() == 0 ? word : line + " " + word;
                if (fm.stringWidth(probe) > width && line.length() > 0) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(probe);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    // ---- one row ------------------------------------------------------------------

    private final class Cell extends JComponent implements ListCellRenderer<Hit> {
        private Hit hit;
        private boolean selected;
        private boolean hovered;
        private boolean focused;

        Cell() {
            setOpaque(false);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Hit> list, Hit value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            this.hit = value;
            this.selected = isSelected;
            this.hovered = index == hover;
            this.focused = list.isFocusOwner();
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(100, ROW);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (hit == null) return;
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                VaultLook.antialias(g);
                final VaultItem item = hit.item();
                final VaultAnalysis info = item.info;
                final VaultIndex index = host.index();
                final VaultState state = index.state();
                final int w = getWidth();
                final int h = getHeight();

                if (selected || hovered) {
                    g.setColor(selected ? (focused ? VaultLook.selection()
                        : VaultLook.mix(VaultLook.accent(), VaultLook.panel(), 0.12f)) : VaultLook.hover());
                    g.fill(new RoundRectangle2D.Float(4, 2, w - 8, h - 4, 9, 9));
                }
                if (selected) {
                    g.setColor(VaultLook.accent());
                    g.fill(new RoundRectangle2D.Float(4, 10, 3, h - 20, 3, 3));
                }

                // ---- badge ----
                final String family = VaultQuery.family(item);
                final int badgeW = VaultLook.badge(g, family, 12, 8, 16);
                final int x = 12 + Math.max(badgeW, 34) + 8;

                // ---- marks on the right ----
                int right = w - 10;
                final boolean showPlay = hovered && VaultRunner.runnable(item);
                if (showPlay) {
                    final int size = 20;
                    final int px = w - PLAY - 4;
                    final int py = (h - size) / 2 - 6;
                    g.setColor(hoverPlay ? VaultLook.accent() : VaultLook.mix(VaultLook.accent(), VaultLook.panel(), 0.3f));
                    g.fill(new java.awt.geom.Ellipse2D.Float(px, py, size, size));
                    final Graphics2D gp = (Graphics2D) g.create();
                    gp.translate(px + 5, py + 5);
                    gp.setColor(VaultLook.onColor(VaultLook.accent()));
                    VaultLook.draw(gp, "play", 10);
                    gp.dispose();
                    right = px - 6;
                } else {
                    g.setFont(VaultLook.ui(Font.PLAIN, 10f));
                    final FontMetrics fm = g.getFontMetrics();
                    final String age = VaultLook.ago(item.modified);
                    g.setColor(VaultLook.faint());
                    g.drawString(age, right - fm.stringWidth(age), 20);
                    right -= fm.stringWidth(age) + 6;
                }
                right = mark(g, right, state.pinned(item.key()), "star-filled", VaultLook.warning());
                final VaultAnalysis.Severity worst = info.worst();
                if (worst == VaultAnalysis.Severity.ERROR) right = mark(g, right, true, "error", VaultLook.error());
                else if (worst == VaultAnalysis.Severity.WARNING) right = mark(g, right, true, "warning", VaultLook.warning());
                right = mark(g, right, !index.twinsOf(item).isEmpty(), "twin", VaultLook.info());
                if (item.layer == VaultItem.Layer.PROJECT) right = mark(g, right, true, "layer", VaultLook.accent());

                // ---- name ----
                g.setFont(VaultLook.ui(Font.PLAIN, 12.5f));
                final FontMetrics nameFm = g.getFontMetrics();
                final String folder = item.folder();
                int nx = x;
                if (!folder.isEmpty()) {
                    g.setFont(VaultLook.ui(Font.PLAIN, 11f));
                    final FontMetrics ffm = g.getFontMetrics();
                    final String shown = VaultLook.fit(folder, ffm, Math.max(20, (right - x) / 3));
                    g.setColor(VaultLook.faint());
                    g.drawString(shown, nx, 20);
                    nx += ffm.stringWidth(shown);
                    g.setFont(VaultLook.ui(Font.PLAIN, 12.5f));
                }
                drawName(g, item.name(), hit.positions(), nx, 20, right - nx - 4, nameFm);

                // ---- second line ----
                g.setFont(VaultLook.ui(Font.PLAIN, 10.5f));
                final FontMetrics fm = g.getFontMetrics();
                String second = info.title;
                if (second.isEmpty()) second = info.description;
                if (second.isEmpty()) {
                    final StringBuilder sb = new StringBuilder();
                    if (!info.params.isEmpty()) {
                        sb.append('(');
                        for (int i = 0; i < Math.min(4, info.params.size()); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(info.params.get(i).label());
                        }
                        if (info.params.size() > 4) sb.append(", ...");
                        sb.append(")  ");
                    }
                    if (!info.uses.isEmpty()) sb.append(String.join(" · ", info.uses.subList(0, Math.min(4, info.uses.size()))));
                    second = sb.length() == 0 ? info.lines + " lines" : sb.toString().strip();
                }
                int sx = x;
                final int limit = (showPlay ? w - PLAY - 10 : w - 10);
                for (String tag : info.tags.subList(0, Math.min(2, info.tags.size()))) {
                    final String t = "#" + tag;
                    if (sx + fm.stringWidth(t) > limit - 40) break;
                    g.setColor(VaultLook.mix(VaultLook.accent(), VaultLook.muted(), 0.75f));
                    g.drawString(t, sx, 36);
                    sx += fm.stringWidth(t) + 6;
                }
                g.setColor(VaultLook.muted());
                g.drawString(VaultLook.fit(second, fm, limit - sx), sx, 36);
            } finally {
                g.dispose();
            }
        }

        private int mark(Graphics2D g, int right, boolean on, String glyph, Color color) {
            if (!on) return right;
            final Graphics2D gg = (Graphics2D) g.create();
            gg.translate(right - 11, 10);
            gg.setColor(color);
            VaultLook.draw(gg, glyph, 11);
            gg.dispose();
            return right - 15;
        }

        private void drawName(Graphics2D g, String name, int[] positions, int x, int y, int width,
                              FontMetrics fm) {
            final String shown = VaultLook.fit(name, fm, width);
            if (positions == null || positions.length == 0) {
                g.setColor(VaultLook.text());
                g.drawString(shown, x, y);
                return;
            }
            final java.util.Set<Integer> lit = new java.util.HashSet<>();
            for (int p : positions) lit.add(p);
            int cx = x;
            final Font plain = g.getFont();
            final Font bold = plain.deriveFont(Font.BOLD);
            for (int i = 0; i < shown.length(); i++) {
                final String ch = String.valueOf(shown.charAt(i));
                final boolean on = lit.contains(i) && i < name.length();
                g.setFont(on ? bold : plain);
                g.setColor(on ? VaultLook.accent() : VaultLook.text());
                g.drawString(ch, cx, y);
                cx += g.getFontMetrics().stringWidth(ch);
            }
            g.setFont(plain);
        }
    }
}

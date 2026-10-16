package com.sphere.components.editor;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * The path of the file and of the symbol holding the caret, above the code:
 * "src › analysis › Selector.cxx › Selector › Process()". A folder opens its
 * files, the file name its outline, a symbol its siblings. Actions sit on the
 * right: run the file in the Sphere console, preview, find, more.
 */
public final class BreadcrumbBar extends JPanel {

    public interface Host {
        void openFile(File file);

        void goTo(DocSymbol symbol);

        List<DocSymbol> symbols();
    }

    private final Host host;
    private final JPanel crumbs = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
    private File file;
    private LanguageSpec language = LanguageSpec.NONE;
    private List<DocSymbol> path = List.of();
    private String untitled = "Untitled";

    public BreadcrumbBar(Host host) {
        super(new BorderLayout());
        this.host = host;
        setOpaque(true);
        crumbs.setOpaque(false);
        actions.setOpaque(false);
        add(crumbs, BorderLayout.CENTER);
        add(actions, BorderLayout.EAST);
        setBorder(BorderFactory.createEmptyBorder(1, 8, 1, 6));
        rebuild();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(super.getPreferredSize().width, 26);
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(EditorTheme.background());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(EditorTheme.alpha(EditorTheme.foreground(), 14));
        g.fillRect(0, getHeight() - 1, getWidth(), 1);
    }

    public void addAction(JComponent action) {
        actions.add(action);
    }

    public void setFile(File file, LanguageSpec language) {
        this.file = file;
        this.language = language == null ? LanguageSpec.NONE : language;
        rebuild();
    }

    /** The name an unsaved buffer goes by ("Untitled-2"). */
    public void setUntitledName(String name) {
        this.untitled = name == null ? "Untitled" : name;
        rebuild();
    }

    /** The symbols around the caret, outermost first. */
    public void setPath(List<DocSymbol> symbols) {
        List<DocSymbol> next = symbols == null ? List.of() : symbols;
        if (sameNames(next, path)) {
            return;
        }
        this.path = next;
        rebuild();
    }

    private static boolean sameNames(List<DocSymbol> a, List<DocSymbol> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).name.equals(b.get(i).name) || a.get(i).line != b.get(i).line) {
                return false;
            }
        }
        return true;
    }

    private void rebuild() {
        crumbs.removeAll();
        if (file == null) {
            crumbs.add(new Crumb(EditorIcons.LanguageBadge.of(language, null), untitled, null, true));
        } else {
            List<File> dirs = new ArrayList<>();
            File parent = file.getAbsoluteFile().getParentFile();
            File root = new File(".").getAbsoluteFile().getParentFile();
            while (parent != null && dirs.size() < 4) {
                dirs.add(0, parent);
                if (root != null && parent.equals(root)) {
                    break;
                }
                parent = parent.getParentFile();
            }
            for (File dir : dirs) {
                Crumb c = new Crumb(null, dir.getName().isEmpty() ? dir.getPath() : dir.getName(),
                                    e -> folderMenu(dir, (JComponent) e.getSource()), false);
                c.setToolTipText(dir.getAbsolutePath());
                crumbs.add(c);
                crumbs.add(new Separator());
            }
            Crumb name = new Crumb(EditorIcons.LanguageBadge.of(language, file), file.getName(),
                                   e -> outlineMenu((JComponent) e.getSource(), null), true);
            name.setToolTipText(file.getAbsolutePath());
            crumbs.add(name);
        }
        for (DocSymbol s : path) {
            crumbs.add(new Separator());
            Crumb c = new Crumb(new EditorIcons.KindIcon(EditorIcons.Kind.of(s.kind), 13), s.describe(),
                                e -> outlineMenu((JComponent) e.getSource(), s), false);
            c.setToolTipText(s.kind.label + " " + s.describe() + " — line " + (s.line + 1));
            crumbs.add(c);
        }
        crumbs.revalidate();
        crumbs.repaint();
    }

    // ---- Menus -------------------------------------------------------------

    private void folderMenu(File dir, JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        File[] entries = dir.listFiles();
        if (entries == null || entries.length == 0) {
            JMenuItem empty = new JMenuItem("(empty)");
            empty.setEnabled(false);
            menu.add(empty);
        } else {
            Arrays.sort(entries, Comparator.comparing((File f) -> !f.isDirectory())
                                           .thenComparing(f -> f.getName().toLowerCase()));
            int shown = 0;
            for (File f : entries) {
                if (f.isHidden() || shown++ > 60) {
                    continue;
                }
                if (f.isDirectory()) {
                    JMenuItem item = new JMenuItem(f.getName() + "/");
                    item.setIcon(EditorIcons.folder(14, EditorTheme::textMuted));
                    item.addActionListener(a -> folderMenu(f, anchor));
                    menu.add(item);
                } else {
                    LanguageSpec spec = LanguageSpec.forFile(f);
                    JMenuItem item = new JMenuItem(f.getName());
                    item.setIcon(EditorIcons.LanguageBadge.of(spec, f));
                    item.setFont(item.getFont().deriveFont(f.equals(file) ? Font.BOLD : Font.PLAIN));
                    item.addActionListener(a -> host.openFile(f));
                    menu.add(item);
                }
            }
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    /** The file's outline, or a symbol's siblings, as a menu that jumps. */
    private void outlineMenu(JComponent anchor, DocSymbol around) {
        JPopupMenu menu = new JPopupMenu();
        List<DocSymbol> all = host.symbols();
        int shown = 0;
        for (DocSymbol s : all) {
            if (around != null && !java.util.Objects.equals(s.container, around.container)) {
                continue;
            }
            if (s.kind == DocSymbol.Kind.VARIABLE && around == null && all.size() > 40) {
                continue;
            }
            if (shown++ > 120) {
                break;
            }
            JMenuItem item = new JMenuItem("    ".repeat(around == null ? Math.min(4, s.depth) : 0) + s.describe());
            item.setIcon(new EditorIcons.KindIcon(EditorIcons.Kind.of(s.kind), 14));
            if (around != null && s.name.equals(around.name) && s.line == around.line) {
                item.setFont(item.getFont().deriveFont(Font.BOLD));
            }
            item.addActionListener(a -> host.goTo(s));
            menu.add(item);
        }
        if (shown == 0) {
            JMenuItem empty = new JMenuItem("No symbols found");
            empty.setEnabled(false);
            menu.add(empty);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    // ---- Parts -------------------------------------------------------------

    private final class Crumb extends JComponent {
        private final Icon icon;
        private final String text;
        private final boolean strong;
        private boolean hovered;

        Crumb(Icon icon, String text, Consumer<MouseEvent> onClick, boolean strong) {
            this.icon = icon;
            this.text = text;
            this.strong = strong;
            setFont(EditorWidgets.uiFont(strong ? Font.BOLD : Font.PLAIN, 12));
            if (onClick != null) {
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e) { hovered = false; repaint(); }
                    @Override public void mousePressed(MouseEvent e) { onClick.accept(e); }
                });
            }
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = fm.stringWidth(text) + 10 + (icon == null ? 0 : icon.getIconWidth() + 5);
            return new Dimension(Math.min(w, 260), 24);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = EditorWidgets.smooth(g);
            if (hovered) {
                g2.setColor(EditorTheme.alpha(EditorTheme.foreground(), 20));
                g2.fill(new RoundRectangle2D.Float(0, 2, getWidth(), getHeight() - 4, 6, 6));
            }
            int x = 5;
            if (icon != null) {
                icon.paintIcon(this, g2, x, (getHeight() - icon.getIconHeight()) / 2);
                x += icon.getIconWidth() + 5;
            }
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            Color c = strong || hovered ? EditorTheme.foreground() : EditorTheme.textMuted();
            g2.setColor(c);
            String shown = text;
            int room = getWidth() - x - 4;
            while (shown.length() > 2 && fm.stringWidth(shown) > room) {
                shown = shown.substring(0, shown.length() - 2) + "…";
            }
            g2.drawString(shown, x, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    private static final class Separator extends JComponent {
        private final Icon chevron = EditorIcons.chevron(12, EditorTheme::textFaint, false);

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(12, 24);
        }

        @Override
        protected void paintComponent(Graphics g) {
            chevron.paintIcon(this, g, 0, (getHeight() - 12) / 2);
        }
    }
}

package com.sphere.components.g4editor;

import com.sphere.core.geant4.Advisor;
import com.sphere.core.geant4.CodeAdvisor;
import com.sphere.core.geant4.Geometry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * G4code's assistant: what an expert would say of the code and of the detector it builds, each finding with
 * where it is and what fixes it. The code's findings (CodeAdvisor) are fixed by rewriting their text; the
 * detector's (Advisor, G4editor's assistant) by the console command G4editor would run, applied to the detector
 * the code builds: G4code then writes that change into the code as it writes a drag or a property.
 */
final class CodeAssistant extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int MAX_CARDS = 60;

    /** What the cards ask of the window. */
    interface Host {
        void reveal(Path file, int line);

        /** Rewrites the code; false when it changed since it was read. */
        boolean apply(String label, List<CodeAdvisor.Edit> edits);

        /** A G4editor console command on the detector, its change written into the code. */
        void command(String command);

        /** Selects a volume (or a placement) by name in 3D. */
        void show(String name);
    }

    /**
     * The Advisor's fixes G4code writes into the code: the world's size and material, a volume made sensitive,
     * one placed. A renaming, a removal or a renumbering is the code's own business.
     */
    private static final Set<String> WRITABLE = Set.of("world", "sensitive", "place", "check");

    private final Host host;
    private final JPanel list = new Column();
    private final JLabel summary = new JLabel(" ");
    private final JButton fixAll = new JButton("Fix all in the code");
    private List<CodeAdvisor.Finding> code = List.of();

    CodeAssistant(Host host) {
        super(new BorderLayout());
        this.host = host;
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        final JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 6));
        summary.setFont(summary.getFont().deriveFont(Font.BOLD, 11f));
        top.add(summary, BorderLayout.CENTER);
        fixAll.setToolTipText("Every rewrite the findings below offer, in one go (each checked against the code as read)");
        fixAll.setFocusable(false);
        fixAll.addActionListener(e -> {
            final List<CodeAdvisor.Edit> all = new ArrayList<>();
            for (CodeAdvisor.Finding f : code) {
                if (f.fix() != null) {
                    all.addAll(f.fix().edits());
                }
            }
            host.apply(all.size() + " fixes of the assistant", all);
        });
        top.add(fixAll, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);
        final JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        show(List.of(), List.of(), "Open a Geant4 application: its code and its detector are reviewed as they are read.");
    }

    /** The findings of the last reading; {@code note} says what the detector was judged against. */
    void show(List<CodeAdvisor.Finding> findings, List<Advisor.Advice> design, String note) {
        code = findings;
        list.removeAll();
        int errors = 0, warnings = 0, fixes = 0;
        for (CodeAdvisor.Finding f : findings) {
            errors += f.severity() == Geometry.Severity.ERROR ? 1 : 0;
            warnings += f.severity() == Geometry.Severity.WARNING ? 1 : 0;
            fixes += f.fix() != null ? 1 : 0;
        }
        for (Advisor.Advice a : design) {
            errors += a.level() == Advisor.Level.ERROR ? 1 : 0;
            warnings += a.level() == Advisor.Level.WARNING ? 1 : 0;
        }
        int shown = 0;
        for (CodeAdvisor.Finding f : findings) {
            if (shown++ < MAX_CARDS) {
                list.add(card(f));
            }
        }
        for (Advisor.Advice a : design) {
            if (shown++ < MAX_CARDS) {
                list.add(card(a));
            }
        }
        if (shown > MAX_CARDS) {
            list.add(label("  ... and " + (shown - MAX_CARDS) + " more of the kinds above"));
        }
        if (findings.isEmpty() && design.isEmpty()) {
            list.add(label("  Nothing to say: the code and the detector it builds look sound."));
        }
        if (note != null && !note.isEmpty()) {
            final JLabel n = label("  " + note);
            n.setFont(n.getFont().deriveFont(Font.ITALIC, 11f));
            list.add(n);
        }
        list.add(Box.createVerticalGlue());
        summary.setText(findings.isEmpty() && design.isEmpty() ? "Nothing to fix" : (errors > 0 ? errors + " error"
            + (errors > 1 ? "s" : "") + ", " : "") + (warnings > 0 ? warnings + " warning" + (warnings > 1 ? "s" : "")
            + ", " : "") + (findings.size() + design.size()) + " findings");
        fixAll.setEnabled(fixes > 1);
        list.revalidate();
        list.repaint();
    }

    /** Errors and warnings, for the tab's title. */
    int serious() {
        int n = 0;
        for (CodeAdvisor.Finding f : code) {
            n += f.severity() != Geometry.Severity.INFO ? 1 : 0;
        }
        return n;
    }

    private static JLabel label(String text) {
        final JLabel l = new JLabel(text);
        l.setForeground(new Color(0x7D8796));
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private JComponent card(CodeAdvisor.Finding f) {
        final JPanel buttons = buttons();
        if (f.file() != null) {
            buttons.add(button(f.file().getFileName() + ":" + f.line(), "Go to the line", () -> host.reveal(f.file(),
                f.line())));
        }
        if (f.fix() != null) {
            final JButton b = button(f.fix().label(), "Rewrite the code: " + describe(f.fix()), () -> {
                if (!host.apply(f.fix().label(), f.fix().edits())) {
                    host.reveal(f.file(), f.line());
                }
            });
            b.setFont(b.getFont().deriveFont(Font.BOLD));
            buttons.add(b);
        }
        return frame(color(f.severity()), f.title(), f.detail(), buttons);
    }

    private JComponent card(Advisor.Advice a) {
        final JPanel buttons = buttons();
        if (a.target() != null) {
            buttons.add(button("Show", "Select it in 3D, and its line in the code", () -> host.show(a.target())));
        }
        for (Advisor.Fix fx : a.fixes()) {
            if (WRITABLE.contains(fx.command().split("\\s+")[0])) {
                buttons.add(button(fx.label(), fx.command() + " (written into the code)", () -> host.command(fx.command())));
            }
        }
        final Color c = switch (a.level()) {
            case ERROR -> new Color(0xD64545);
            case WARNING -> new Color(0xE0912F);
            case IDEA -> new Color(0x3C8DDC);
            default -> new Color(0x7D8796);
        };
        return frame(c, a.title(), a.detail(), buttons);
    }

    private static String describe(CodeAdvisor.Fix f) {
        final StringBuilder b = new StringBuilder();
        for (CodeAdvisor.Edit e : f.edits()) {
            b.append(b.length() > 0 ? "; " : "").append(e.expected()).append(" -> ").append(e.text());
        }
        return b.toString();
    }

    private static Color color(Geometry.Severity s) {
        return switch (s) {
            case ERROR -> new Color(0xD64545);
            case WARNING -> new Color(0xE0912F);
            default -> new Color(0x3C8DDC);
        };
    }

    private static JPanel buttons() {
        final JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        p.setOpaque(false);
        return p;
    }

    private static JButton button(String label, String tip, Runnable r) {
        final JButton b = new JButton(label);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.addActionListener(e -> r.run());
        return b;
    }

    /**
     * A card as G4editor's assistant draws them: a colored edge, the title, the detail wrapped to the column's
     * width (a plain text area: no HTML to lay out again on every resize), the buttons.
     */
    private static JComponent frame(Color c, String titleText, String detailText, JPanel buttons) {
        final JPanel p = new JPanel(new BorderLayout(6, 2)) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4),
            BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 4, 0, 0, c),
                BorderFactory.createEmptyBorder(4, 8, 4, 6))));
        final JLabel title = new JLabel(titleText);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setAlignmentX(LEFT_ALIGNMENT);
        final Detail detail = new Detail(detailText == null ? "" : detailText);
        detail.setFont(title.getFont().deriveFont(Font.PLAIN));
        detail.setForeground(new Color(0x7D8796));
        final JPanel text = new JPanel();
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.setOpaque(false);
        text.add(title);
        text.add(detail);
        p.add(text, BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    /**
     * A finding's detail, wrapped: as tall as the lines its text makes at the width it has, or at the column's
     * before it has one (the first layout asks before it gives widths).
     */
    private static final class Detail extends javax.swing.JTextArea {
        private static final long serialVersionUID = 1L;

        Detail(String text) {
            super(text);
            setLineWrap(true);
            setWrapStyleWord(true);
            setEditable(false);
            setFocusable(false);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
            setAlignmentX(LEFT_ALIGNMENT);
        }

        @Override
        public Dimension getPreferredSize() {
            int w = getWidth();
            final java.awt.Container column = javax.swing.SwingUtilities.getAncestorOfClass(Column.class, this);
            if (w <= 0 && column != null) {
                // the column's width less the edges and margins of what holds the detail in it
                w = column.getWidth();
                for (java.awt.Container c = getParent(); c != null; c = c.getParent()) {
                    w -= c.getInsets().left + c.getInsets().right;
                    if (c == column) {
                        break;
                    }
                }
            }
            if (w <= 0 || getUI() == null) {
                return super.getPreferredSize();
            }
            final java.awt.Insets in = getInsets();
            final javax.swing.text.View v = getUI().getRootView(this);
            v.setSize(w - in.left - in.right, Integer.MAX_VALUE);
            return new Dimension(w, (int) Math.ceil(v.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + in.top + in.bottom);
        }
    }

    /** The cards' column: as wide as the view, so that details wrap and the buttons stay in sight. */
    private static final class Column extends JPanel implements javax.swing.Scrollable {
        private static final long serialVersionUID = 1L;
        private int laidOut = -1;

        /** A detail wraps to the width it was just given: the cards are laid out again at their wrapped heights. */
        @Override
        public void doLayout() {
            super.doLayout();
            if (getWidth() != laidOut) {
                laidOut = getWidth();
                javax.swing.SwingUtilities.invokeLater(this::revalidate);
            }
        }

        @Override
        public void removeAll() {
            super.removeAll();
            laidOut = -1;   // new cards: their heights are known once laid out at the width
        }

        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(java.awt.Rectangle r, int orientation, int direction) {
            return 16;
        }

        public int getScrollableBlockIncrement(java.awt.Rectangle r, int orientation, int direction) {
            return Math.max(16, r.height - 32);
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}

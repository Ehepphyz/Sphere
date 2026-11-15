package com.sphere.core.python.jupyterlab;

import com.sphere.core.python.jupyterlab.kernel.KernelSpecs;
import com.sphere.fonts.FontLoader;
import com.sphere.theme.ThemeManager;
import com.sphere.theme.ThemePalette;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.BorderFactory;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

/**
 * The kernel the notebook runs on, at the top right, readable whole: a status
 * dot, the kernel's name, what it is (language version, implementation) and,
 * while it runs, its memory and CPU. Sized to its text, never cut. A click
 * opens the list of every kernel Sphere can start and the kernel's actions.
 */
final class KernelChip extends JButton {

    private static final long serialVersionUID = 1L;

    enum State { OFF, STARTING, IDLE, BUSY, DEAD }

    /** What the chip does: implemented by the notebook editor. */
    interface Host {
        List<KernelSpecs.Spec> kernels();

        KernelSpecs.Spec selected();

        void select(KernelSpecs.Spec spec);

        void interrupt();

        void restart(boolean runAll);

        void shutdownKernel();
    }

    private final transient Host host;
    private State state = State.OFF;
    private String status = "";
    private String usage = "";
    /** 0: name, version and memory; 1: name and memory; 2: the name alone. The tooltip and the menu say it all. */
    private int density;

    KernelChip(Host host) {
        this.host = host;
        setFocusable(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        addActionListener(e -> showMenu());
    }

    void setState(State state, String status) {
        this.state = state == null ? State.OFF : state;
        this.status = status == null ? "" : status;
        if (this.state != State.IDLE && this.state != State.BUSY) {
            usage = "";
        }
        refresh();
    }

    State state() {
        return state;
    }

    /** Busy while a cell runs, idle after: the status text stays. */
    void setBusy(boolean busy) {
        if (state == State.IDLE || state == State.BUSY) {
            state = busy ? State.BUSY : State.IDLE;
            repaint();
        }
    }

    /** "186 MB · 2% CPU" while the kernel runs; empty when it cannot say. */
    void setUsage(String usage) {
        String now = usage == null ? "" : usage;
        if (!now.equals(this.usage)) {
            this.usage = now;
            refresh();
        }
    }

    /** How much the chip says, as the window has room for: 0 most, 2 the name alone. */
    void setDensity(int density) {
        int d = Math.max(0, Math.min(2, density));
        if (d != this.density) {
            this.density = d;
            revalidate();
            repaint();
        }
    }

    void refresh() {
        KernelSpecs.Spec s = host.selected();
        setToolTipText("<html><b>" + escape(s == null ? "No kernel" : s.label()) + "</b>"
                       + (status.isEmpty() ? "" : "<br>" + escape(status))
                       + (usage.isEmpty() ? "" : "<br>" + escape(usage))
                       + "<br><i>Click: switch kernel, interrupt, restart</i></html>");
        revalidate();
        repaint();
    }

    /** The name a chip has room for; the menu and the tooltip give the whole label. */
    static String shortName(KernelSpecs.Spec s) {
        if (s == null) {
            return "No kernel";
        }
        if (s.isHomemade()) {
            return "Sphere HEP";
        }
        String d = s.displayName();
        int p = d.indexOf(" (");
        String base = p > 0 ? d.substring(0, p) : d;
        if (base.length() <= 8) {
            base = d; // "Python (ipykernel, ...)": the parenthesis is what tells it apart
        }
        return base.length() > 26 ? base.substring(0, 25) + "…" : base;
    }

    /** "Python 3.14.3 · sphere_kernel 1.0.0" says "Py 3.14"; a message keeps its first words. */
    static String compactStatus(String status) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^Python (\\d+\\.\\d+)").matcher(status);
        if (m.find()) {
            return "Py " + m.group(1);
        }
        String first = status.split(" · ", 2)[0];
        int colon = first.indexOf(": ");
        if (colon > 0 && first.length() > 24) {
            first = first.substring(0, colon); // "could not start: <the whole error>"
        }
        return first.length() > 24 ? first.substring(0, 23) + "…" : first;
    }

    /** "250 MB · 0% CPU" says "250 MB"; a busy kernel keeps its CPU: "250 MB · 37%". */
    static String compactUsage(String usage) {
        String[] parts = usage.split(" · ");
        java.util.regex.Matcher cpu = java.util.regex.Pattern.compile("(\\d+)% CPU").matcher(usage);
        int load = cpu.find() ? Integer.parseInt(cpu.group(1)) : 0;
        String memory = parts[0].contains("CPU") ? "" : parts[0];
        return load >= 5 ? (memory.isEmpty() ? "" : memory + " · ") + load + "%" : memory;
    }

    private String text() {
        StringBuilder sb = new StringBuilder();
        if (!status.isEmpty() && density == 0) {
            sb.append(compactStatus(status));
        }
        String u = usage.isEmpty() || density >= 2 ? "" : compactUsage(usage);
        if (!u.isEmpty()) {
            sb.append(sb.length() > 0 ? " · " : "").append(u);
        }
        if (sb.length() == 0 && density < 2 && !status.isEmpty() && state != State.IDLE && state != State.BUSY) {
            sb.append(compactStatus(status)); // "starting...", "could not start": said even when short of room
        }
        return sb.toString();
    }

    private Font bold() {
        return getFont().deriveFont(Font.BOLD);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics b = getFontMetrics(bold());
        FontMetrics f = getFontMetrics(getFont());
        String rest = text();
        int w = 12 + 10 + 8 + b.stringWidth(shortName(host.selected()))
                + (rest.isEmpty() ? 0 : 10 + f.stringWidth(rest)) + 8 + f.stringWidth("▾") + 12;
        return new Dimension(w, Math.max(26, f.getHeight() + 10));
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            ThemePalette p = ThemeManager.getCurrentPalette();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            boolean hot = getModel().isRollover() || getModel().isPressed();
            Color surface = p.getBackgroundSurface();
            Color edge = hot ? p.getAccent() : p.getBorder();
            g.setColor(hot ? blend(surface, p.getAccent(), 0.12f) : surface);
            g.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, h, h));
            g.setColor(edge);
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, h, h));

            Color dot = switch (state) {
                case IDLE -> p.getLogSuccessPrefix();
                case BUSY -> p.getLogWarnPrefix();
                case STARTING -> p.getTabEditorSelectBg();
                case DEAD -> p.getError();
                default -> p.getTextSecondary();
            };
            float cy = h / 2f;
            g.setColor(dot);
            g.fill(new Ellipse2D.Float(12, cy - 4.5f, 9, 9));

            FontMetrics bm = g.getFontMetrics(bold());
            FontMetrics fm = g.getFontMetrics(getFont());
            int baseline = Math.round(cy + (fm.getAscent() - fm.getDescent()) / 2f);
            int x = 12 + 10 + 8;
            g.setFont(bold());
            g.setColor(p.getTextPrimary());
            String name = shortName(host.selected());
            g.drawString(name, x, baseline);
            x += bm.stringWidth(name);
            String rest = text();
            g.setFont(getFont());
            if (!rest.isEmpty()) {
                x += 10;
                g.setColor(p.getTextSecondary());
                g.drawString(rest, x, baseline);
                x += fm.stringWidth(rest);
            }
            g.setColor(p.getTextSecondary());
            g.drawString("▾", x + 8, baseline);
        } finally {
            g.dispose();
        }
    }

    private void showMenu() {
        JPopupMenu menu = new JPopupMenu();
        KernelSpecs.Spec selected = host.selected();
        JLabel head = new JLabel("<html><b>" + escape(selected == null ? "No kernel" : selected.label()) + "</b>"
            + (status.isEmpty() ? "" : "<br>" + escape(status)) + (usage.isEmpty() ? "" : "<br>" + escape(usage))
            + (selected == null || selected.argv().isEmpty() ? ""
               : "<br><span style='color:gray'>" + escape(selected.argv().get(0)) + "</span>") + "</html>");
        head.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        menu.add(head);
        menu.addSeparator();
        ButtonGroup group = new ButtonGroup();
        for (KernelSpecs.Spec s : host.kernels()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(s.label() + (s.isHomemade() ? "   (Sphere's own)" : ""),
                                                                 s.equals(selected));
            item.addActionListener(e -> host.select(s));
            group.add(item);
            menu.add(item);
        }
        JRadioButtonMenuItem none = new JRadioButtonMenuItem("No kernel", selected == null);
        none.addActionListener(e -> host.select(null));
        group.add(none);
        menu.add(none);
        menu.addSeparator();
        JMenuItem interrupt = new JMenuItem("Interrupt the running cell");
        interrupt.addActionListener(e -> host.interrupt());
        interrupt.setEnabled(state == State.BUSY);
        menu.add(interrupt);
        JMenuItem restart = new JMenuItem("Restart (forget every variable)");
        restart.addActionListener(e -> host.restart(false));
        menu.add(restart);
        JMenuItem restartAll = new JMenuItem("Restart and Run All");
        restartAll.addActionListener(e -> host.restart(true));
        menu.add(restartAll);
        JMenuItem shutdown = new JMenuItem("Shut the kernel down");
        shutdown.addActionListener(e -> host.shutdownKernel());
        shutdown.setEnabled(state == State.IDLE || state == State.BUSY);
        menu.add(shutdown);
        menu.show(this, Math.max(0, getWidth() - menu.getPreferredSize().width), getHeight() + 2);
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() * (1 - t) + b.getRed() * t),
                         Math.round(a.getGreen() * (1 - t) + b.getGreen() * t),
                         Math.round(a.getBlue() * (1 - t) + b.getBlue() * t));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    protected void paintBorder(Graphics g) {
        // the pill is its own border
    }
}

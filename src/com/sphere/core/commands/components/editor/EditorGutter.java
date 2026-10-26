package com.sphere.components.editor;

import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Utilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Row header carrying breakpoints, diagnostic glyphs, line numbers and the bars
 * that mark lines changed since the last save. Clicking the glyph strip toggles
 * a breakpoint on that line; right-clicking offers the rest.
 */
public final class EditorGutter extends JPanel implements DocumentListener, CaretListener {

    private static final int GLYPH_STRIP = 18;
    private static final int RIGHT_PAD = 10;
    private static final int CHANGE_BAR = 3;
    private static final int MIN_DIGITS = 3;

    private final JTextComponent component;
    private final BreakpointModel breakpoints;
    private final Map<Integer, EditorDiagnostic.Severity> markers = new HashMap<>();
    private final Map<Integer, List<EditorDiagnostic>> byLine = new HashMap<>();

    private int currentLine = 1;
    private int executionLine = -1;
    private int hoverLine = -1;
    private int lastDigits = 0;
    private int lastWidth = 0;
    private byte[] changes = new byte[0];
    private final BreakpointModel.Listener breakpointRepaint = m -> repaint();

    /** Stops following breakpoints that outlive this gutter (a closed split). */
    public void dispose() {
        if (breakpoints != null) {
            breakpoints.removeListener(breakpointRepaint);
        }
        component.getDocument().removeDocumentListener(this);
    }

    public EditorGutter(JTextComponent component, BreakpointModel breakpoints) {
        this.component = component;
        this.breakpoints = breakpoints;

        setOpaque(true);
        applyTheme();
        ToolTipManager.sharedInstance().registerComponent(this);

        component.getDocument().addDocumentListener(this);
        component.addCaretListener(this);
        if (breakpoints != null) {
            breakpoints.addListener(breakpointRepaint);
        }

        // The strip is sized from the editor's font, so a font change has to be
        // followed rather than measured once at construction.
        component.addPropertyChangeListener("font", evt -> {
            setFont(component.getFont());
            updateWidth();
            repaint();
        });

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int line = lineAt(e.getY());
                if (line <= 0) {
                    return;
                }
                if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger()) {
                    showMenu(e, line);
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                if (e.getX() < GLYPH_STRIP + 2 && breakpoints != null) {
                    breakpoints.toggle(line);
                } else {
                    selectLine(line, e.isShiftDown());
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int line = e.getX() < GLYPH_STRIP + 2 ? lineAt(e.getY()) : -1;
                setCursor(Cursor.getPredefinedCursor(line > 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                if (line != hoverLine) {
                    hoverLine = line;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (hoverLine != -1) {
                    hoverLine = -1;
                    repaint();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);

        updateWidth();
    }

    /** Re-measures once the component is live, when metrics are finally real. */
    @Override
    public void addNotify() {
        super.addNotify();
        updateWidth();
    }

    public void applyTheme() {
        setBackground(EditorTheme.gutterBackground());
        setForeground(EditorTheme.gutterForeground());
        setFont(component.getFont());
        repaint();
    }

    /** Replaces the marker set for this buffer. */
    public void setDiagnostics(List<EditorDiagnostic> diagnostics) {
        markers.clear();
        byLine.clear();
        if (diagnostics != null) {
            for (EditorDiagnostic d : diagnostics) {
                byLine.computeIfAbsent(d.getLine(), k -> new ArrayList<>()).add(d);
                EditorDiagnostic.Severity present = markers.get(d.getLine());
                // An error on a line outranks a warning already recorded there.
                if (present == null || d.getSeverity().ordinal() < present.ordinal()) {
                    markers.put(d.getLine(), d.getSeverity());
                }
            }
        }
        repaint();
    }

    /** Line the debugger is stopped on, or -1. */
    public void setExecutionLine(int line) {
        this.executionLine = line;
        repaint();
    }

    /** LineDiff marks, one per line. */
    public void setChanges(byte[] marks) {
        this.changes = marks == null ? new byte[0] : marks;
        repaint();
    }

    // ---- Interaction -------------------------------------------------------

    private void selectLine(int line, boolean extend) {
        Element root = component.getDocument().getDefaultRootElement();
        if (line < 1 || line > root.getElementCount()) {
            return;
        }
        Element el = root.getElement(line - 1);
        int start = el.getStartOffset();
        int end = Math.min(el.getEndOffset(), component.getDocument().getLength());
        if (extend) {
            component.moveCaretPosition(end);
        } else {
            component.setCaretPosition(start);
            component.moveCaretPosition(end);
        }
        component.requestFocusInWindow();
    }

    private void showMenu(MouseEvent e, int line) {
        JPopupMenu menu = new JPopupMenu();
        if (breakpoints != null) {
            boolean has = breakpoints.has(line);
            JMenuItem toggle = new JMenuItem(has ? "Remove Breakpoint" : "Add Breakpoint");
            toggle.addActionListener(a -> breakpoints.toggle(line));
            menu.add(toggle);
            if (has) {
                boolean enabled = breakpoints.isEnabled(line);
                JMenuItem enable = new JMenuItem(enabled ? "Disable Breakpoint" : "Enable Breakpoint");
                enable.addActionListener(a -> breakpoints.setEnabled(line, !enabled));
                menu.add(enable);
            }
            if (!breakpoints.lines().isEmpty()) {
                menu.addSeparator();
                JMenuItem clear = new JMenuItem("Remove All Breakpoints");
                clear.addActionListener(a -> breakpoints.clear());
                menu.add(clear);
            }
        }
        menu.show(this, e.getX(), e.getY());
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        int line = lineAt(event.getY());
        if (line <= 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<EditorDiagnostic> list = byLine.get(line);
        if (list != null && event.getX() < GLYPH_STRIP + 2) {
            for (EditorDiagnostic d : list) {
                if (sb.length() > 0) {
                    sb.append("<br>");
                }
                sb.append("<b>").append(d.getSeverity().name().charAt(0))
                  .append(d.getSeverity().name().substring(1).toLowerCase()).append("</b> ")
                  .append(CodeTextPane.escape(d.getMessage()))
                  .append(" <font color='#888888'>").append(CodeTextPane.escape(d.getSource())).append("</font>");
            }
        }
        if (breakpoints != null && breakpoints.has(line) && event.getX() < GLYPH_STRIP + 2) {
            if (sb.length() > 0) {
                sb.append("<br>");
            }
            sb.append(breakpoints.isEnabled(line) ? "Breakpoint" : "Breakpoint (disabled)")
              .append(" — click to remove, right-click for more");
        }
        if (sb.length() == 0 && line - 1 < changes.length && event.getX() >= getWidth() - RIGHT_PAD) {
            int mark = changes[line - 1] & 3;
            if (mark == LineDiff.ADDED) {
                sb.append("Added since the last save");
            } else if (mark == LineDiff.MODIFIED) {
                sb.append("Modified since the last save");
            }
        }
        return sb.length() == 0 ? null : "<html>" + sb + "</html>";
    }

    // ---- Geometry ----------------------------------------------------------

    private int lineAt(int y) {
        try {
            int offset = component.viewToModel2D(new Point2D.Double(0, y));
            return component.getDocument().getDefaultRootElement().getElementIndex(offset) + 1;
        } catch (Exception ex) {
            return -1;
        }
    }

    private int lineCount() {
        return component.getDocument().getDefaultRootElement().getElementCount();
    }

    /**
     * Measured on demand rather than stored. A width computed once in the
     * constructor was taken before the component was realized and never revised,
     * so the strip could end up narrower than the numbers it had to hold and the
     * text started on top of them until a scroll forced a fresh layout.
     */
    @Override
    public Dimension getPreferredSize() {
        // Height follows the text component. A sentinel height left the row header
        // viewport with a view far taller than anything it could repaint.
        int height = Math.max(component.getHeight(), 1);
        return new Dimension(measureWidth(), height);
    }

    private int measureWidth() {
        int digits = Math.max(MIN_DIGITS, String.valueOf(lineCount()).length());
        Font font = component.getFont();
        FontMetrics fm = getFontMetrics(font != null ? font : getFont());
        int digitWidth = fm != null ? fm.charWidth('0') : 8;
        return GLYPH_STRIP + digitWidth * digits + RIGHT_PAD;
    }

    /**
     * Asks the scroll pane to lay out again when the strip needs a new width.
     * Revalidating only this component left the row header viewport on its old
     * bounds, which is why scrolling appeared to repair the display.
     */
    private void updateWidth() {
        int digits = Math.max(MIN_DIGITS, String.valueOf(lineCount()).length());
        int width = measureWidth();
        if (digits == lastDigits && width == lastWidth) {
            return;
        }
        lastDigits = digits;
        lastWidth = width;

        revalidate();
        Container ancestor = SwingUtilities.getAncestorOfClass(JScrollPane.class, this);
        if (ancestor != null) {
            ancestor.revalidate();
            ancestor.repaint();
        }
    }

    // ---- Painting ----------------------------------------------------------

    @Override
    public void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();

        // Clear the damaged area ourselves. Relying on the panel UI left whatever
        // the text component had previously painted here still on screen, which is
        // why source characters showed through under the line numbers until a
        // scroll forced a full repaint.
        Rectangle damaged = g2.getClipBounds();
        if (damaged == null) {
            damaged = new Rectangle(0, 0, getWidth(), getHeight());
        }
        g2.setColor(EditorTheme.gutterBackground());
        g2.fillRect(damaged.x, damaged.y, damaged.width, damaged.height);

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        Object aa = component.getClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            aa != null ? aa : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Rectangle clip = damaged;
        FontMetrics fm = component.getFontMetrics(component.getFont());
        Element root = component.getDocument().getDefaultRootElement();

        int startOffset = component.viewToModel2D(new Point2D.Double(0, clip.y));
        int endOffset = component.viewToModel2D(new Point2D.Double(0, clip.y + clip.height));
        int firstLine = root.getElementIndex(Math.max(0, startOffset));
        int lastLine = root.getElementIndex(Math.max(0, endOffset));

        g2.setFont(component.getFont());
        int w = getWidth();

        for (int i = firstLine; i <= lastLine && i < root.getElementCount(); i++) {
            int lineNumber = i + 1;
            Rectangle rect = lineRect(root.getElement(i).getStartOffset());
            if (rect == null) {
                continue;
            }
            int baseline = rect.y + rect.height - fm.getDescent();
            int cy = rect.y + rect.height / 2;

            if (lineNumber == executionLine) {
                g2.setColor(EditorTheme.executionLine());
                g2.fillRect(0, rect.y, w, rect.height);
            }

            boolean hasBreakpoint = breakpoints != null && breakpoints.has(lineNumber);
            EditorDiagnostic.Severity severity = markers.get(lineNumber);
            if (hasBreakpoint) {
                paintBreakpoint(g2, cy, rect.height, breakpoints.isEnabled(lineNumber), false);
            } else if (severity != null) {
                paintSeverity(g2, severity, cy, rect.height);
            } else if (lineNumber == hoverLine && breakpoints != null) {
                paintBreakpoint(g2, cy, rect.height, true, true);
            }
            if (lineNumber == executionLine) {
                paintExecutionArrow(g2, cy, rect.height);
            }

            String text = String.valueOf(lineNumber);
            g2.setColor(lineNumber == currentLine ? EditorTheme.gutterCurrentForeground()
                                                  : EditorTheme.gutterForeground());
            int x = w - RIGHT_PAD - fm.stringWidth(text);
            g2.drawString(text, x, baseline);

            if (i < changes.length) {
                paintChange(g2, changes[i], rect, w);
            }
        }
        g2.dispose();
    }

    private void paintBreakpoint(Graphics2D g2, int cy, int lineHeight, boolean enabled, boolean preview) {
        float d = Math.min(11f, lineHeight - 4f);
        float x = (GLYPH_STRIP - d) / 2f + 1;
        float y = cy - d / 2f;
        Ellipse2D dot = new Ellipse2D.Float(x, y, d, d);
        Color red = EditorTheme.breakpoint();
        if (preview) {
            g2.setColor(EditorTheme.alpha(red, 70));
            g2.fill(dot);
            return;
        }
        if (enabled) {
            g2.setColor(red);
            g2.fill(dot);
            g2.setColor(EditorTheme.alpha(Color.WHITE, 46));
            g2.fill(new Ellipse2D.Float(x + d * 0.22f, y + d * 0.16f, d * 0.38f, d * 0.30f));
        } else {
            g2.setColor(EditorTheme.breakpointDisabled());
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(new Ellipse2D.Float(x + 0.8f, y + 0.8f, d - 1.6f, d - 1.6f));
        }
    }

    private void paintSeverity(Graphics2D g2, EditorDiagnostic.Severity severity, int cy, int lineHeight) {
        float d = Math.min(10f, lineHeight - 5f);
        float x = (GLYPH_STRIP - d) / 2f + 1;
        float y = cy - d / 2f;
        Color c = CodeTextPane.severityColor(severity);
        switch (severity) {
            case ERROR: {
                g2.setColor(c);
                g2.fill(new Ellipse2D.Float(x, y, d, d));
                g2.setColor(EditorTheme.background());
                g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                float k = d * 0.28f;
                float mx = x + d / 2f;
                float my = y + d / 2f;
                g2.draw(new Line2D.Float(mx - k, my - k, mx + k, my + k));
                g2.draw(new Line2D.Float(mx - k, my + k, mx + k, my - k));
                break;
            }
            case WARNING: {
                GeneralPath tri = new GeneralPath();
                tri.moveTo(x + d / 2f, y);
                tri.lineTo(x + d, y + d);
                tri.lineTo(x, y + d);
                tri.closePath();
                g2.setColor(c);
                g2.fill(tri);
                g2.setColor(EditorTheme.background());
                g2.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new Line2D.Float(x + d / 2f, y + d * 0.38f, x + d / 2f, y + d * 0.66f));
                g2.fill(new Ellipse2D.Float(x + d / 2f - 0.8f, y + d * 0.76f, 1.6f, 1.6f));
                break;
            }
            default: {
                float s = d * 0.55f;
                g2.setColor(c);
                g2.fill(new Ellipse2D.Float(x + (d - s) / 2f, y + (d - s) / 2f, s, s));
            }
        }
    }

    private void paintExecutionArrow(Graphics2D g2, int cy, int lineHeight) {
        float h = Math.min(12f, lineHeight - 3f);
        float x = 2f;
        float y = cy - h / 2f;
        GeneralPath p = new GeneralPath();
        p.moveTo(x, y + h * 0.25f);
        p.lineTo(x + h * 0.55f, y + h * 0.25f);
        p.lineTo(x + h * 0.55f, y);
        p.lineTo(x + h * 1.15f, y + h / 2f);
        p.lineTo(x + h * 0.55f, y + h);
        p.lineTo(x + h * 0.55f, y + h * 0.75f);
        p.lineTo(x, y + h * 0.75f);
        p.closePath();
        g2.setColor(EditorTheme.executionArrow());
        g2.fill(p);
    }

    private void paintChange(Graphics2D g2, byte mark, Rectangle rect, int w) {
        int kind = mark & 3;
        int x = w - CHANGE_BAR - 2;
        if (kind == LineDiff.ADDED) {
            g2.setColor(EditorTheme.added());
            g2.fillRect(x, rect.y, CHANGE_BAR, rect.height);
        } else if (kind == LineDiff.MODIFIED) {
            g2.setColor(EditorTheme.modified());
            g2.fillRect(x, rect.y, CHANGE_BAR, rect.height);
        }
        if ((mark & LineDiff.DELETED_ABOVE) != 0) {
            GeneralPath tri = new GeneralPath();
            tri.moveTo(x - 1, rect.y - 4);
            tri.lineTo(x + 5, rect.y);
            tri.lineTo(x - 1, rect.y + 4);
            tri.closePath();
            g2.setColor(EditorTheme.deleted());
            g2.fill(tri);
        }
    }

    private Rectangle lineRect(int offset) {
        try {
            java.awt.geom.Rectangle2D r = component.modelToView2D(offset);
            return r == null ? null : r.getBounds();
        } catch (BadLocationException ex) {
            return null;
        }
    }

    // ---- Listeners ---------------------------------------------------------

    @Override
    public void caretUpdate(javax.swing.event.CaretEvent e) {
        int line = component.getDocument().getDefaultRootElement()
                            .getElementIndex(e.getDot()) + 1;
        if (line != currentLine) {
            currentLine = line;
            repaint();
        }
    }

    @Override public void insertUpdate(DocumentEvent e) { documentChanged(); }
    @Override public void removeUpdate(DocumentEvent e) { documentChanged(); }
    @Override public void changedUpdate(DocumentEvent e) { documentChanged(); }

    private boolean refreshQueued;

    private void documentChanged() {
        if (refreshQueued) {
            return;
        }
        refreshQueued = true;
        SwingUtilities.invokeLater(() -> {
            refreshQueued = false;
            updateWidth();
            repaint();
        });
    }

    /** Offset of the first non-blank character on a line, for the caret helpers. */
    static int firstNonBlank(JTextComponent c, int line) {
        try {
            Element root = c.getDocument().getDefaultRootElement();
            if (line < 0 || line >= root.getElementCount()) {
                return -1;
            }
            int start = root.getElement(line).getStartOffset();
            return Utilities.getRowStart(c, start);
        } catch (BadLocationException ex) {
            return -1;
        }
    }
}

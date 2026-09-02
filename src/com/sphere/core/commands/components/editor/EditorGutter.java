package com.sphere.components.editor;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.CaretListener;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import javax.swing.text.Utilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Container;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Row header carrying line numbers, breakpoints and diagnostic markers. Clicking
 * the strip toggles a breakpoint on that line.
 */
public final class EditorGutter extends JPanel implements DocumentListener, CaretListener {

    private static final int MARKER_STRIP = 14;
    private static final int RIGHT_PAD = 8;
    private static final int LEFT_PAD = 6;
    private static final int MIN_DIGITS = 3;

    private final JTextComponent component;
    private final BreakpointModel breakpoints;
    private final Map<Integer, EditorDiagnostic.Severity> markers = new HashMap<>();

    private int currentLine = 1;
    private int executionLine = -1;
    private int lastDigits = 0;
    private int lastWidth = 0;

    public EditorGutter(JTextComponent component, BreakpointModel breakpoints) {
        this.component = component;
        this.breakpoints = breakpoints;

        setOpaque(true);
        applyTheme();

        component.getDocument().addDocumentListener(this);
        component.addCaretListener(this);
        if (breakpoints != null) {
            breakpoints.addListener(m -> repaint());
        }

        // The strip is sized from the editor's font, so a font change has to be
        // followed rather than measured once at construction.
        component.addPropertyChangeListener("font", evt -> {
            setFont(component.getFont());
            updateWidth();
            repaint();
        });

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (breakpoints == null || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int line = lineAt(e.getY());
                if (line > 0) {
                    breakpoints.toggle(line);
                }
            }
        });

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
        if (diagnostics != null) {
            for (EditorDiagnostic d : diagnostics) {
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

    // ---- Geometry ----------------------------------------------------------

    private int lineAt(int y) {
        try {
            int offset = component.viewToModel2D(new java.awt.geom.Point2D.Double(0, y));
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
        return MARKER_STRIP + LEFT_PAD + digitWidth * digits + RIGHT_PAD;
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
        g2.setColor(getBackground());
        g2.fillRect(damaged.x, damaged.y, damaged.width, damaged.height);

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Rectangle clip = g2.getClipBounds();
        FontMetrics fm = component.getFontMetrics(component.getFont());
        Element root = component.getDocument().getDefaultRootElement();

        int startOffset = component.viewToModel2D(new java.awt.geom.Point2D.Double(0, clip.y));
        int endOffset = component.viewToModel2D(
                new java.awt.geom.Point2D.Double(0, clip.y + clip.height));
        int firstLine = root.getElementIndex(startOffset);
        int lastLine = root.getElementIndex(endOffset);

        g2.setFont(component.getFont());

        for (int i = firstLine; i <= lastLine && i < root.getElementCount(); i++) {
            int lineNumber = i + 1;
            Rectangle rect = lineRect(root.getElement(i).getStartOffset());
            if (rect == null) {
                continue;
            }
            int baseline = rect.y + rect.height - fm.getDescent();

            if (lineNumber == executionLine) {
                g2.setColor(EditorTheme.executionLine());
                g2.fillRect(0, rect.y, getWidth(), rect.height);
            }

            EditorDiagnostic.Severity severity = markers.get(lineNumber);
            if (severity != null) {
                g2.setColor(severityColor(severity));
                int d = Math.min(7, rect.height - 4);
                g2.fillOval(3, rect.y + (rect.height - d) / 2, d, d);
            }

            if (breakpoints != null && breakpoints.has(lineNumber)) {
                g2.setColor(breakpoints.isEnabled(lineNumber)
                            ? EditorTheme.breakpoint() : EditorTheme.breakpointDisabled());
                int d = Math.min(10, rect.height - 2);
                g2.fillOval(2, rect.y + (rect.height - d) / 2, d, d);
            }

            String text = String.valueOf(lineNumber);
            g2.setColor(lineNumber == currentLine
                        ? EditorTheme.gutterCurrentForeground() : getForeground());
            int x = getWidth() - RIGHT_PAD - fm.stringWidth(text);
            g2.drawString(text, x, baseline);
        }

        g2.setColor(EditorTheme.gutterBorder());
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(getWidth() - 1, clip.y, getWidth() - 1, clip.y + clip.height);
        g2.dispose();
    }

    private static Color severityColor(EditorDiagnostic.Severity severity) {
        switch (severity) {
            case ERROR:   return EditorTheme.errorSquiggle();
            case WARNING: return EditorTheme.warningSquiggle();
            default:      return EditorTheme.infoSquiggle();
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

    private void documentChanged() {
        SwingUtilities.invokeLater(() -> {
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

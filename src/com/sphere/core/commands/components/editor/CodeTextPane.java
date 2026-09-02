package com.sphere.components.editor;

import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.TabSet;
import javax.swing.text.TabStop;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The code surface: a styled pane that also paints the current line, squiggles
 * under diagnostics and the bracket facing the caret.
 */
public final class CodeTextPane extends JTextPane {

    private final List<EditorDiagnostic> diagnostics = new ArrayList<>();
    private int bracketA = -1;
    private int bracketB = -1;
    private int executionLine = -1;
    private boolean highlightCurrentLine = true;

    public CodeTextPane() {
        setBackground(EditorTheme.background());
        setForeground(EditorTheme.foreground());
        setCaretColor(EditorTheme.caret());
        setSelectionColor(EditorTheme.selection());
        setSelectedTextColor(EditorTheme.foreground());
        setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 6, 4, 0));
        setMargin(new java.awt.Insets(0, 0, 0, 0));
    }

    /**
     * A JTextPane wraps by default and ignores setTabSize, so both are set here:
     * wrapping off through the viewport contract, tabs through a TabSet.
     */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        java.awt.Container parent = getParent();
        if (parent == null) {
            return true;
        }
        return getUI().getPreferredSize(this).width <= parent.getSize().width;
    }

    @Override
    public void setSize(Dimension d) {
        java.awt.Container parent = getParent();
        if (parent != null && d.width < parent.getSize().width) {
            d.width = parent.getSize().width;
        }
        super.setSize(d);
    }

    public void setTabWidth(int characters) {
        FontMetrics fm = getFontMetrics(getFont());
        int width = fm.charWidth('m') * Math.max(1, characters);
        TabStop[] stops = new TabStop[64];
        for (int i = 0; i < stops.length; i++) {
            stops[i] = new TabStop((i + 1) * width);
        }
        SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setTabSet(set, new TabSet(stops));
        StyledDocument doc = getStyledDocument();
        doc.setParagraphAttributes(0, doc.getLength() + 1, set, false);
    }

    public void applyTheme() {
        setBackground(EditorTheme.background());
        setForeground(EditorTheme.foreground());
        setCaretColor(EditorTheme.caret());
        setSelectionColor(EditorTheme.selection());
        setSelectedTextColor(EditorTheme.foreground());
        repaint();
    }

    public void setDiagnostics(List<EditorDiagnostic> list) {
        diagnostics.clear();
        if (list != null) {
            diagnostics.addAll(list);
        }
        repaint();
    }

    public List<EditorDiagnostic> getDiagnostics() {
        return diagnostics;
    }

    public void setBracketPair(int a, int b) {
        this.bracketA = a;
        this.bracketB = b;
        repaint();
    }

    public void setExecutionLine(int line) {
        this.executionLine = line;
        repaint();
    }

    public void setHighlightCurrentLine(boolean value) {
        this.highlightCurrentLine = value;
        repaint();
    }

    // ---- Painting ----------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        // Own copy: the decorations below change hints and colours, and mutating
        // the caller's Graphics leaks that state into whatever paints next.
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paintLineBands(g2);
            paintBrackets(g2);
        } finally {
            g2.dispose();
        }

        super.paintComponent(g);

        Graphics2D g3 = (Graphics2D) g.create();
        try {
            g3.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paintSquiggles(g3);
        } finally {
            g3.dispose();
        }
    }

    private void paintLineBands(Graphics2D g2) {
        if (executionLine > 0) {
            Rectangle r = rectOfLine(executionLine);
            if (r != null) {
                g2.setColor(EditorTheme.executionLine());
                g2.fillRect(0, r.y, getWidth(), r.height);
            }
        }
        if (!highlightCurrentLine || getSelectionStart() != getSelectionEnd()) {
            return;
        }
        try {
            Rectangle2D r = modelToView2D(getCaretPosition());
            if (r != null) {
                g2.setColor(EditorTheme.currentLine());
                g2.fillRect(0, (int) r.getY(), getWidth(), (int) r.getHeight());
            }
        } catch (BadLocationException ignored) {
            // caret momentarily out of the document during a reload
        }
    }

    private void paintBrackets(Graphics2D g2) {
        if (bracketA < 0 || bracketB < 0) {
            return;
        }
        g2.setColor(EditorTheme.bracketMatch());
        for (int offset : new int[] { bracketA, bracketB }) {
            try {
                Rectangle2D r = modelToView2D(offset);
                if (r != null) {
                    FontMetrics fm = getFontMetrics(getFont());
                    g2.fillRect((int) r.getX(), (int) r.getY(),
                                Math.max(2, fm.charWidth('m')), (int) r.getHeight());
                }
            } catch (BadLocationException ignored) {
                // stale offset after an edit; the next caret move recomputes it
            }
        }
    }

    private void paintSquiggles(Graphics2D g2) {
        if (diagnostics.isEmpty()) {
            return;
        }
        g2.setStroke(new BasicStroke(1f));
        for (EditorDiagnostic d : diagnostics) {
            Rectangle r = rectOfLine(d.getLine());
            if (r == null) {
                continue;
            }
            int x0 = r.x;
            int x1 = r.x + Math.max(12, r.width);
            if (d.getColumn() > 0) {
                Rectangle at = rectOfColumn(d.getLine(), d.getColumn());
                if (at != null) {
                    x0 = at.x;
                    x1 = at.x + Math.max(12, at.width * 4);
                }
            }
            int y = r.y + r.height - 2;
            g2.setColor(severityColor(d.getSeverity()));
            drawWave(g2, x0, Math.min(x1, getWidth()), y);
        }
    }

    private static void drawWave(Graphics2D g2, int x0, int x1, int y) {
        final int step = 2;
        boolean up = true;
        for (int x = x0; x < x1 - step; x += step) {
            g2.drawLine(x, up ? y : y - 2, x + step, up ? y - 2 : y);
            up = !up;
        }
    }

    private static Color severityColor(EditorDiagnostic.Severity severity) {
        switch (severity) {
            case ERROR:   return EditorTheme.errorSquiggle();
            case WARNING: return EditorTheme.warningSquiggle();
            default:      return EditorTheme.infoSquiggle();
        }
    }

    private Rectangle rectOfLine(int line) {
        try {
            Element root = getDocument().getDefaultRootElement();
            if (line < 1 || line > root.getElementCount()) {
                return null;
            }
            Element el = root.getElement(line - 1);
            Rectangle2D start = modelToView2D(el.getStartOffset());
            Rectangle2D end = modelToView2D(Math.max(el.getStartOffset(),
                                                     el.getEndOffset() - 1));
            if (start == null) {
                return null;
            }
            int width = end == null ? 0 : (int) (end.getMaxX() - start.getX());
            return new Rectangle((int) start.getX(), (int) start.getY(),
                                 width, (int) start.getHeight());
        } catch (BadLocationException ex) {
            return null;
        }
    }

    private Rectangle rectOfColumn(int line, int column) {
        try {
            Element root = getDocument().getDefaultRootElement();
            if (line < 1 || line > root.getElementCount()) {
                return null;
            }
            Element el = root.getElement(line - 1);
            int offset = Math.min(el.getStartOffset() + column - 1, el.getEndOffset() - 1);
            Rectangle2D r = modelToView2D(Math.max(el.getStartOffset(), offset));
            return r == null ? null : r.getBounds();
        } catch (BadLocationException ex) {
            return null;
        }
    }

    // ---- Tooltip -----------------------------------------------------------

    @Override
    public String getToolTipText(MouseEvent event) {
        if (diagnostics.isEmpty()) {
            return null;
        }
        int offset = viewToModel2D(event.getPoint());
        int line = getDocument().getDefaultRootElement().getElementIndex(offset) + 1;
        StringBuilder sb = new StringBuilder();
        for (EditorDiagnostic d : diagnostics) {
            if (d.getLine() == line) {
                if (sb.length() > 0) {
                    sb.append("<br>");
                }
                sb.append(d.getSeverity()).append(": ");
                if (!d.getCode().isEmpty()) {
                    sb.append('[').append(d.getCode()).append("] ");
                }
                sb.append(escape(d.getMessage()));
            }
        }
        return sb.length() == 0 ? null : "<html>" + sb + "</html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

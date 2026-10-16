package com.sphere.components.editor;

import javax.swing.BorderFactory;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The code surface. A plain-text area whose view paints tokens in color, and
 * which paints around them: the current line, indent guides, the occurrences of
 * the word under the caret, search matches, squiggles under diagnostics, the
 * bracket facing the caret and snippet placeholders.
 */
public final class CodeTextPane extends JTextArea {

    private final List<EditorDiagnostic> diagnostics = new ArrayList<>();
    private int bracketA = -1;
    private int bracketB = -1;
    private int executionLine = -1;
    private boolean highlightCurrentLine = true;
    private boolean indentGuides = true;
    private boolean occurrences = true;
    private boolean scrollBeyondLastLine = true;

    private SyntaxHighlighter syntax;
    private LanguageSpec language = LanguageSpec.NONE;
    /** Rebuilt by CodeTextUI.Styles whenever the font, theme or language moves. */
    CodeTextUI.Styles styleCache;

    private int[] findStarts = new int[0];
    private int[] findEnds = new int[0];
    private int findCount;
    private int findCurrent = -1;

    private int linkStart = -1;
    private int linkEnd = -1;

    private List<int[]> placeholders = List.of();
    private int activePlaceholder = -1;

    private int rightReserve;
    private List<String[]> watermark = List.of();
    private Function<MouseEvent, String> tooltipProvider;
    private Consumer<Runnable> atomicRunner = Runnable::run;

    public CodeTextPane() {
        super();
        setOpaque(true);
        setTabSize(4);
        setLineWrap(false);
        setWrapStyleWord(true);
        setDragEnabled(true);
        putClientProperty("caretWidth", 2);
        applyInsets();
        applyTheme();
    }

    /** The code surface keeps its own UI under every look and feel. */
    @Override
    public void updateUI() {
        setUI(new CodeTextUI());
        styleCache = null;
    }

    @Override
    public void setFont(Font f) {
        super.setFont(f);
        styleCache = null;
    }

    public void applyTheme() {
        setBackground(EditorTheme.background());
        setForeground(EditorTheme.foreground());
        setCaretColor(EditorTheme.caret());
        setSelectionColor(EditorTheme.selection());
        setSelectedTextColor(EditorTheme.foreground());
        styleCache = null;
        repaint();
    }

    private void applyInsets() {
        setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 8 + rightReserve));
    }

    /** Room kept free on the right, under the minimap. */
    public void setRightReserve(int pixels) {
        if (pixels != rightReserve) {
            rightReserve = pixels;
            applyInsets();
            revalidate();
        }
    }

    /** Kept for callers of the former styled pane. */
    public void setTabWidth(int characters) {
        setTabSize(Math.max(1, characters));
    }

    public void setWordWrap(boolean wrap) {
        setLineWrap(wrap);
    }

    public boolean isWordWrap() {
        return getLineWrap();
    }

    public void setSyntax(SyntaxHighlighter syntax) {
        this.syntax = syntax;
    }

    public SyntaxHighlighter getSyntax() {
        return syntax;
    }

    public void setLanguage(LanguageSpec spec) {
        this.language = spec == null ? LanguageSpec.NONE : spec;
        styleCache = null;
        repaint();
    }

    public LanguageSpec getLanguage() {
        return language;
    }

    /** Runs a multi-step edit as one undo step. */
    public void atomic(Runnable edit) {
        atomicRunner.accept(edit);
    }

    public void setAtomicRunner(Consumer<Runnable> runner) {
        this.atomicRunner = runner == null ? Runnable::run : runner;
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
        if (a != bracketA || b != bracketB) {
            this.bracketA = a;
            this.bracketB = b;
            repaint();
        }
    }

    public void setExecutionLine(int line) {
        this.executionLine = line;
        repaint();
    }

    public void setHighlightCurrentLine(boolean value) {
        this.highlightCurrentLine = value;
        repaint();
    }

    public void setIndentGuidesVisible(boolean value) {
        this.indentGuides = value;
        repaint();
    }

    public boolean isIndentGuidesVisible() {
        return indentGuides;
    }

    public void setOccurrencesVisible(boolean value) {
        this.occurrences = value;
        repaint();
    }

    public void setScrollBeyondLastLine(boolean value) {
        this.scrollBeyondLastLine = value;
        revalidate();
    }

    public void setFindMatches(int[] starts, int[] ends, int count, int current) {
        this.findStarts = starts == null ? new int[0] : starts;
        this.findEnds = ends == null ? new int[0] : ends;
        this.findCount = Math.min(count, Math.min(this.findStarts.length, this.findEnds.length));
        this.findCurrent = current;
        repaint();
    }

    public void clearFindMatches() {
        setFindMatches(null, null, 0, -1);
    }

    public int[] findStarts() {
        return findStarts;
    }

    public int findCount() {
        return findCount;
    }

    public void setLink(int start, int end) {
        if (start != linkStart || end != linkEnd) {
            linkStart = start;
            linkEnd = end;
            repaint();
        }
    }

    public void setPlaceholders(List<int[]> ranges, int active) {
        this.placeholders = ranges == null ? List.of() : ranges;
        this.activePlaceholder = active;
        repaint();
    }

    public void setWatermark(List<String[]> lines) {
        this.watermark = lines == null ? List.of() : lines;
        repaint();
    }

    public void setTooltipProvider(Function<MouseEvent, String> provider) {
        this.tooltipProvider = provider;
    }

    /** VS Code lets the last line scroll up to the top; so does this. */
    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        if (scrollBeyondLastLine && getParent() instanceof JViewport vp) {
            int extra = Math.max(0, vp.getHeight() - getRowHeight() * 3);
            d = new Dimension(d.width, d.height + extra);
        }
        return d;
    }

    /** A short file still fills the view, so no strip of the viewport shows under it. */
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport vp && vp.getHeight() > getPreferredSize().height;
    }

    // ---- Geometry helpers --------------------------------------------------

    /** Height of one line of text, in pixels. */
    public int lineHeight() {
        return getRowHeight();
    }

    public int lineCountTotal() {
        return getDocument().getDefaultRootElement().getElementCount();
    }

    /** 0-based line of an offset. */
    public int lineAt(int offset) {
        Element root = getDocument().getDefaultRootElement();
        return root.getElementIndex(Math.max(0, Math.min(offset, getDocument().getLength())));
    }

    public int lineStartOffset(int line) {
        Element root = getDocument().getDefaultRootElement();
        if (line < 0) {
            return 0;
        }
        if (line >= root.getElementCount()) {
            return getDocument().getLength();
        }
        return root.getElement(line).getStartOffset();
    }

    /** Offset of the line's end, before its newline. */
    public int lineEndOffset(int line) {
        Element root = getDocument().getDefaultRootElement();
        if (line < 0) {
            return 0;
        }
        if (line >= root.getElementCount()) {
            return getDocument().getLength();
        }
        Element el = root.getElement(line);
        return Math.max(el.getStartOffset(), Math.min(el.getEndOffset() - 1, getDocument().getLength()));
    }

    public String lineText(int line) {
        try {
            int s = lineStartOffset(line);
            int e = lineEndOffset(line);
            return getDocument().getText(s, e - s);
        } catch (BadLocationException ex) {
            return "";
        }
    }

    /** [start, end) of the identifier touching offset, or null. */
    public int[] wordAt(int offset) {
        Document doc = getDocument();
        int line = lineAt(offset);
        int ls = lineStartOffset(line);
        String text = lineText(line);
        int rel = offset - ls;
        if (rel < 0 || rel > text.length()) {
            return null;
        }
        int s = rel;
        while (s > 0 && isWordChar(text.charAt(s - 1))) {
            s--;
        }
        int e = rel;
        while (e < text.length() && isWordChar(text.charAt(e))) {
            e++;
        }
        if (e <= s || doc == null) {
            return null;
        }
        if (Character.isDigit(text.charAt(s))) {
            return null;
        }
        return new int[] { ls + s, ls + e };
    }

    static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private Rectangle2D view(int offset) {
        try {
            return modelToView2D(Math.max(0, Math.min(offset, getDocument().getLength())));
        } catch (BadLocationException ex) {
            return null;
        }
    }

    /** Top and bottom of a (possibly wrapped) line, full width. */
    private Rectangle lineBounds(int line) {
        Rectangle2D a = view(lineStartOffset(line));
        if (a == null) {
            return null;
        }
        int top = (int) a.getY();
        int bottom = (int) (a.getY() + a.getHeight());
        if (getLineWrap()) {
            Rectangle2D b = view(lineEndOffset(line));
            if (b != null) {
                bottom = (int) (b.getY() + b.getHeight());
            }
        }
        return new Rectangle(0, top, getWidth(), bottom - top);
    }

    // ---- Painting ----------------------------------------------------------

    private static Map<?, ?> desktopHints;

    @Override
    protected void paintComponent(Graphics g) {
        Rectangle clip = g.getClipBounds();
        if (clip == null) {
            clip = new Rectangle(0, 0, getWidth(), getHeight());
        }
        Graphics2D g2 = (Graphics2D) g.create();
        int[] lines = visibleLines(clip);
        try {
            g2.setColor(getBackground());
            g2.fillRect(clip.x, clip.y, clip.width, clip.height);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            paintLineBands(g2);
            paintFindMatches(g2, lines);
            paintOccurrences(g2, lines);
            if (indentGuides) {
                paintIndentGuides(g2, lines);
            }
            paintPlaceholders(g2);
            paintBracketFill(g2);
        } finally {
            g2.dispose();
        }

        applyTextHints((Graphics2D) g);
        super.paintComponent(g);

        Graphics2D g3 = (Graphics2D) g.create();
        try {
            g3.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g3.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            paintBracketBoxes(g3);
            paintSquiggles(g3, lines);
            paintLink(g3);
            paintWatermark(g3);
        } finally {
            g3.dispose();
        }
    }

    /** Views draw through a path that carries no component, so the hints are set here. */
    private void applyTextHints(Graphics2D g) {
        Object aa = getClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING);
        Object lcd = getClientProperty(RenderingHints.KEY_TEXT_LCD_CONTRAST);
        if (aa != null) {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
            if (lcd != null) {
                g.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, lcd);
            }
            return;
        }
        if (desktopHints == null) {
            Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
            desktopHints = hints instanceof Map<?, ?> m ? m : Map.of(
                    RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        }
        g.addRenderingHints(desktopHints);
    }

    /** {first, last} 0-based lines intersecting the clip. */
    private int[] visibleLines(Rectangle clip) {
        Element root = getDocument().getDefaultRootElement();
        int a = viewToModel2D(new Point2D.Double(0, clip.y));
        int b = viewToModel2D(new Point2D.Double(Math.max(0, getWidth() - 1), clip.y + clip.height));
        int first = root.getElementIndex(Math.max(0, a));
        int last = root.getElementIndex(Math.max(0, b));
        return new int[] { Math.max(0, first), Math.min(root.getElementCount() - 1, last) };
    }

    private void paintLineBands(Graphics2D g2) {
        if (executionLine > 0) {
            Rectangle r = lineBounds(executionLine - 1);
            if (r != null) {
                g2.setColor(EditorTheme.executionLine());
                g2.fillRect(0, r.y, getWidth(), r.height);
            }
        }
        if (!highlightCurrentLine || !isFocusOwnerOrNearby()) {
            return;
        }
        int line = lineAt(getCaretPosition());
        Rectangle r = lineBounds(line);
        if (r == null) {
            return;
        }
        if (getSelectionStart() == getSelectionEnd()) {
            g2.setColor(EditorTheme.currentLine());
            g2.fillRect(0, r.y, getWidth(), r.height);
        } else {
            g2.setColor(EditorTheme.currentLineBorder());
            g2.drawRect(0, r.y, getWidth() - 1, r.height - 1);
        }
    }

    private boolean isFocusOwnerOrNearby() {
        // The band stays while a popup of the editor (completion, find) has focus.
        return true;
    }

    private void paintFindMatches(Graphics2D g2, int[] lines) {
        if (findCount == 0) {
            return;
        }
        int from = lineStartOffset(lines[0]);
        int to = lineEndOffset(lines[1]) + 1;
        int lo = lowerBound(findStarts, findCount, from - 1);
        for (int i = Math.max(0, lo - 1); i < findCount; i++) {
            int s = findStarts[i];
            int e = findEnds[i];
            if (s > to) {
                break;
            }
            if (e < from) {
                continue;
            }
            boolean current = i == findCurrent;
            paintRange(g2, s, Math.max(e, s + 1),
                       current ? EditorTheme.findMatchCurrent() : EditorTheme.findMatch(),
                       current ? EditorTheme.findMatchBorder() : null);
        }
    }

    private static int lowerBound(int[] a, int n, int key) {
        int lo = 0;
        int hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] <= key) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private void paintOccurrences(Graphics2D g2, int[] lines) {
        if (!occurrences) {
            return;
        }
        Document doc = getDocument();
        int from = lineStartOffset(lines[0]);
        int to = lineEndOffset(lines[1]);
        String visible;
        try {
            visible = doc.getText(from, Math.max(0, to - from));
        } catch (BadLocationException ex) {
            return;
        }
        int selStart = getSelectionStart();
        int selEnd = getSelectionEnd();
        String needle;
        boolean wholeWord;
        int exclude = -1;
        if (selStart != selEnd) {
            if (selEnd - selStart < 2 || selEnd - selStart > 200) {
                return;
            }
            try {
                needle = doc.getText(selStart, selEnd - selStart);
            } catch (BadLocationException ex) {
                return;
            }
            if (needle.indexOf('\n') >= 0 || needle.isBlank()) {
                return;
            }
            wholeWord = false;
            exclude = selStart;
        } else {
            int[] w = wordAt(getCaretPosition());
            if (w == null || w[1] - w[0] < 2) {
                return;
            }
            if (syntax != null) {
                TokenKind k = syntax.liveKindAt(w[0]);
                if (k == TokenKind.COMMENT || k == TokenKind.STRING || k == TokenKind.KEYWORD) {
                    return;
                }
            }
            try {
                needle = doc.getText(w[0], w[1] - w[0]);
            } catch (BadLocationException ex) {
                return;
            }
            wholeWord = true;
        }
        Color fill = EditorTheme.occurrence();
        int idx = visible.indexOf(needle);
        int painted = 0;
        while (idx >= 0 && painted < 500) {
            int s = from + idx;
            int e = s + needle.length();
            boolean ok = !wholeWord
                    || ((idx == 0 || !isWordChar(visible.charAt(idx - 1)))
                        && (idx + needle.length() >= visible.length()
                            || !isWordChar(visible.charAt(idx + needle.length()))));
            if (ok && s != exclude) {
                paintRange(g2, s, e, fill, null);
                painted++;
            }
            idx = visible.indexOf(needle, idx + Math.max(1, needle.length()));
        }
    }

    private void paintPlaceholders(Graphics2D g2) {
        for (int i = 0; i < placeholders.size(); i++) {
            int[] r = placeholders.get(i);
            boolean active = i == activePlaceholder || (r.length > 2 && r[2] == 1);
            paintRange(g2, r[0], Math.max(r[1], r[0] + 1),
                       active ? EditorTheme.placeholder() : EditorTheme.alpha(EditorTheme.placeholder(), 24),
                       active ? EditorTheme.placeholderBorder() : null);
        }
    }

    private void paintBracketFill(Graphics2D g2) {
        if (bracketA < 0 || bracketB < 0) {
            return;
        }
        g2.setColor(EditorTheme.bracketMatch());
        for (int offset : new int[] { bracketA, bracketB }) {
            Rectangle r = charBox(offset);
            if (r != null) {
                g2.fill(new RoundRectangle2D.Float(r.x, r.y, r.width, r.height, 3, 3));
            }
        }
    }

    private void paintBracketBoxes(Graphics2D g2) {
        if (bracketA < 0 || bracketB < 0) {
            return;
        }
        g2.setColor(EditorTheme.bracketMatchBorder());
        g2.setStroke(new BasicStroke(1f));
        for (int offset : new int[] { bracketA, bracketB }) {
            Rectangle r = charBox(offset);
            if (r != null) {
                g2.draw(new RoundRectangle2D.Float(r.x + 0.5f, r.y + 0.5f, r.width - 1, r.height - 1, 3, 3));
            }
        }
    }

    private Rectangle charBox(int offset) {
        Rectangle2D a = view(offset);
        Rectangle2D b = view(offset + 1);
        if (a == null) {
            return null;
        }
        int w = b != null && b.getY() == a.getY() ? (int) Math.round(b.getX() - a.getX())
                                                  : getFontMetrics(getFont()).charWidth('m');
        return new Rectangle((int) a.getX(), (int) a.getY(), Math.max(2, w), (int) a.getHeight());
    }

    /** Fills [s, e) line by line, rounding each piece. */
    private void paintRange(Graphics2D g2, int s, int e, Color fill, Color border) {
        int lineS = lineAt(s);
        int lineE = lineAt(Math.max(s, e - 1));
        for (int line = lineS; line <= lineE; line++) {
            int a = line == lineS ? s : lineStartOffset(line);
            int b = line == lineE ? e : lineEndOffset(line) + 1;
            Rectangle2D ra = view(a);
            Rectangle2D rb = view(Math.min(b, lineEndOffset(line)));
            if (ra == null || rb == null) {
                continue;
            }
            float x0 = (float) ra.getX();
            float x1 = (float) rb.getX();
            if (b > lineEndOffset(line)) {
                x1 += getFontMetrics(getFont()).charWidth(' ');
            }
            if (rb.getY() != ra.getY()) {
                x1 = getWidth() - 4;
            }
            float w = Math.max(2f, x1 - x0);
            RoundRectangle2D shape = new RoundRectangle2D.Float(x0, (float) ra.getY(), w,
                                                                (float) ra.getHeight(), 4, 4);
            if (fill != null) {
                g2.setColor(fill);
                g2.fill(shape);
            }
            if (border != null) {
                g2.setColor(border);
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(x0 + 0.5f, (float) ra.getY() + 0.5f, w - 1,
                                                   (float) ra.getHeight() - 1, 4, 4));
            }
        }
    }

    // ---- Indent guides -----------------------------------------------------

    private void paintIndentGuides(Graphics2D g2, int[] lines) {
        int tab = Math.max(1, getTabSize());
        FontMetrics fm = getFontMetrics(getFont());
        int space = fm.charWidth(' ');
        Insets in = getInsets();
        int first = lines[0];
        int last = lines[1];
        int total = lineCountTotal();
        if (total > 200_000) {
            return;
        }

        // Indents of the visible lines and a margin around them, -1 for blank.
        int marginTop = Math.max(0, first - 120);
        int marginBottom = Math.min(total - 1, last + 120);
        int[] indent = new int[marginBottom - marginTop + 1];
        for (int line = marginTop; line <= marginBottom; line++) {
            indent[line - marginTop] = indentColumns(lineText(line), tab);
        }
        // Blank lines take the smaller indent of their neighbours.
        int[] level = new int[indent.length];
        for (int k = 0; k < indent.length; k++) {
            if (indent[k] >= 0) {
                level[k] = indent[k] / tab;
                continue;
            }
            int up = -1;
            for (int u = k - 1; u >= 0; u--) {
                if (indent[u] >= 0) {
                    up = indent[u];
                    break;
                }
            }
            int down = -1;
            for (int d = k + 1; d < indent.length; d++) {
                if (indent[d] >= 0) {
                    down = indent[d];
                    break;
                }
            }
            int v = up < 0 || down < 0 ? 0 : Math.min(up, down);
            // A block opener above a blank line keeps the inner guide going.
            if (up >= 0 && down > up) {
                v = up + tab;
            }
            level[k] = v / tab;
        }

        // The block holding the caret gets the brighter guide.
        int caretLine = lineAt(getCaretPosition());
        int activeLevel = -1;
        int activeFrom = -1;
        int activeTo = -1;
        if (caretLine >= marginTop && caretLine <= marginBottom) {
            int k = caretLine - marginTop;
            int lv = level[k];
            int nextLevel = -1;
            for (int d = k + 1; d < level.length; d++) {
                if (indent[d] >= 0) {
                    nextLevel = level[d];
                    break;
                }
            }
            if (nextLevel > lv && indent[k] >= 0) {
                // The caret sits on a block opener: its body's guide lights up.
                activeLevel = lv;
                activeFrom = k + 1;
            } else {
                activeLevel = lv - 1;
                int u = k;
                while (u > 0 && level[u - 1] > activeLevel) {
                    u--;
                }
                activeFrom = u;
            }
            if (activeLevel >= 0) {
                int d = Math.max(activeFrom, k);
                while (d + 1 < level.length && level[d + 1] > activeLevel) {
                    d++;
                }
                activeTo = d;
            }
        }

        Color normal = EditorTheme.indentGuide();
        Color active = EditorTheme.indentGuideActive();
        g2.setStroke(new BasicStroke(1f));
        for (int line = first; line <= last; line++) {
            int k = line - marginTop;
            int lv = level[k];
            if (lv <= 0) {
                continue;
            }
            Rectangle r = lineBounds(line);
            if (r == null) {
                continue;
            }
            // A line's own text sits on its last level, so guides stop before it.
            int ownColumn = indent[k] >= 0 ? indent[k] : Integer.MAX_VALUE;
            for (int j = 0; j < lv; j++) {
                int col = j * tab;
                if (col >= ownColumn) {
                    break;
                }
                boolean isActive = j == activeLevel && k >= activeFrom && k <= activeTo;
                int x = in.left + col * space;
                g2.setColor(isActive ? active : normal);
                g2.drawLine(x, r.y, x, r.y + r.height - 1);
            }
        }
    }

    /** Columns of leading whitespace, tabs expanded; -1 for a blank line. */
    static int indentColumns(String line, int tab) {
        int col = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                col++;
            } else if (c == '\t') {
                col += tab - (col % tab);
            } else if (c == '\r') {
                continue;
            } else {
                return col;
            }
        }
        return -1;
    }

    // ---- Squiggles, links, watermark ---------------------------------------

    private void paintSquiggles(Graphics2D g2, int[] lines) {
        if (diagnostics.isEmpty()) {
            return;
        }
        g2.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (EditorDiagnostic d : diagnostics) {
            int line = d.getLine() - 1;
            if (line < lines[0] - 1 || line > lines[1] + 1 || line >= lineCountTotal()) {
                continue;
            }
            if ("TODO".equals(d.getCode())) {
                continue;
            }
            int ls = lineStartOffset(line);
            int le = lineEndOffset(line);
            String text = lineText(line);
            int s;
            int e;
            if (d.getColumn() > 0) {
                s = Math.min(le, ls + d.getColumn() - 1);
                int[] w = wordAt(s);
                if (d.getLength() > 0) {
                    e = Math.min(le, s + d.getLength());
                } else if (w != null && w[0] <= s) {
                    e = w[1];
                } else {
                    e = Math.min(le, s + 1);
                }
            } else {
                int lead = 0;
                while (lead < text.length() && Character.isWhitespace(text.charAt(lead))) {
                    lead++;
                }
                s = ls + lead;
                e = le;
            }
            Rectangle2D a = view(s);
            Rectangle2D b = view(Math.max(s, e));
            if (a == null || b == null) {
                continue;
            }
            float x0 = (float) a.getX();
            float x1 = (float) Math.max(b.getX(), x0 + 8);
            float y = (float) (a.getY() + a.getHeight() - 2);
            g2.setColor(severityColor(d.getSeverity()));
            if (d.getSeverity() == EditorDiagnostic.Severity.INFO) {
                for (float x = x0; x < x1; x += 3f) {
                    g2.fill(new Rectangle2D.Float(x, y, 1.2f, 1.2f));
                }
            } else {
                g2.draw(wave(x0, x1, y));
            }
        }
    }

    private static GeneralPath wave(float x0, float x1, float y) {
        GeneralPath p = new GeneralPath();
        final float step = 2f;
        p.moveTo(x0, y);
        boolean up = true;
        for (float x = x0; x < x1; x += step) {
            float nx = Math.min(x1, x + step);
            p.quadTo(x + step / 2f, up ? y - 2.2f : y + 0.2f, nx, up ? y - 1f : y);
            up = !up;
        }
        return p;
    }

    static Color severityColor(EditorDiagnostic.Severity severity) {
        switch (severity) {
            case ERROR:   return EditorTheme.errorSquiggle();
            case WARNING: return EditorTheme.warningSquiggle();
            default:      return EditorTheme.infoSquiggle();
        }
    }

    private void paintLink(Graphics2D g2) {
        if (linkStart < 0 || linkEnd <= linkStart) {
            return;
        }
        Rectangle2D a = view(linkStart);
        Rectangle2D b = view(linkEnd);
        if (a == null || b == null) {
            return;
        }
        g2.setColor(EditorTheme.link());
        int y = (int) (a.getY() + a.getHeight() - 2);
        g2.fillRect((int) a.getX(), y, (int) Math.max(2, b.getX() - a.getX()), 1);
    }

    private void paintWatermark(Graphics2D g2) {
        if (watermark.isEmpty() || getDocument().getLength() > 0) {
            return;
        }
        Rectangle vis = getVisibleRect();
        Font label = getFont().deriveFont(Font.PLAIN, getFont().getSize2D() * 0.95f);
        Font key = getFont().deriveFont(Font.BOLD, getFont().getSize2D() * 0.85f);
        FontMetrics fl = g2.getFontMetrics(label);
        FontMetrics fk = g2.getFontMetrics(key);
        int rowH = Math.max(fl.getHeight(), fk.getHeight()) + 12;
        int labelW = 0;
        int keyW = 0;
        for (String[] row : watermark) {
            labelW = Math.max(labelW, fl.stringWidth(row[0]));
            keyW = Math.max(keyW, keyWidth(fk, row.length > 1 ? row[1] : ""));
        }
        int gap = 28;
        int totalW = labelW + gap + keyW;
        int totalH = rowH * watermark.size();
        int x0 = vis.x + (vis.width - totalW) / 2;
        int y0 = vis.y + Math.max(24, (vis.height - totalH) / 2);
        applyTextHints(g2);
        Color text = EditorTheme.watermark();
        for (int i = 0; i < watermark.size(); i++) {
            String[] row = watermark.get(i);
            int y = y0 + i * rowH;
            g2.setFont(label);
            g2.setColor(text);
            g2.drawString(row[0], x0 + labelW - fl.stringWidth(row[0]), y + fl.getAscent());
            if (row.length > 1) {
                paintKeys(g2, fk, row[1], x0 + labelW + gap, y, rowH - 12);
            }
        }
    }

    private static int keyWidth(FontMetrics fk, String keys) {
        int w = 0;
        for (String part : keys.split("\\+")) {
            w += fk.stringWidth(part) + 12 + 4;
        }
        return w;
    }

    /** Draws "Ctrl+Shift+P" as three keycaps. */
    static void paintKeys(Graphics2D g2, FontMetrics fk, String keys, int x, int y, int h) {
        paintKeys(g2, fk, keys, x, y, h, EditorTheme.watermark());
    }

    static void paintKeys(Graphics2D g2, FontMetrics fk, String keys, int x, int y, int h, Color text) {
        g2.setFont(fk.getFont());
        int cx = x;
        for (String part : keys.split("\\+")) {
            int w = fk.stringWidth(part) + 12;
            g2.setColor(EditorTheme.alpha(EditorTheme.foreground(), 18));
            g2.fill(new RoundRectangle2D.Float(cx, y, w, h, 5, 5));
            g2.setColor(EditorTheme.alpha(EditorTheme.foreground(), 40));
            g2.draw(new RoundRectangle2D.Float(cx + 0.5f, y + 0.5f, w - 1, h - 1, 5, 5));
            g2.setColor(text);
            g2.drawString(part, cx + 6, y + (h + fk.getAscent() - fk.getDescent()) / 2);
            cx += w + 4;
        }
    }

    // ---- Tooltip -----------------------------------------------------------

    @Override
    public String getToolTipText(MouseEvent event) {
        if (tooltipProvider != null) {
            String t = tooltipProvider.apply(event);
            if (t != null) {
                return t;
            }
        }
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

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

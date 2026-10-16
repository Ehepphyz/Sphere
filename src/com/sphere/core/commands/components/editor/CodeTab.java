package com.sphere.components.editor;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import javax.swing.undo.UndoManager;
import java.awt.AWTKeyStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.sphere.utils.AppLogger;
import com.sphere.fonts.FontLoader;

/**
 * One editor tab: the code surface with its gutter and minimap, the breadcrumb
 * bar above, the find bar floating over it, and the markdown or LaTeX preview
 * beside it. It owns the language services of its buffer: highlighting, the
 * analysis behind outline and problems, completion, indentation and undo.
 */
public class CodeTab extends JPanel {

    public enum EditorMode { PLAIN_TEXT, MARKDOWN, LATEX }

    /** What a tab asks of the editor around it. */
    public interface Host {
        void openFile(File file);

        void runFile(CodeTab tab);

        void showPalette(String prefix);

        void toast(String message);

        List<CodeTab> allTabs();

        void selectTab(CodeTab tab);

        void tabStateChanged(CodeTab tab);
    }

    private EditorMode currentMode = EditorMode.PLAIN_TEXT;

    private final CodeTextPane editorArea;
    private final JScrollPane editorScrollPane;
    private final EditorGutter gutter;
    private final Minimap minimap;
    private final JSplitPane splitPane;
    private final JEditorPane previewArea;
    private final JScrollPane previewScrollPane;
    private final BreadcrumbBar breadcrumbs;
    private final FindWidget findWidget;
    private final EditorWidgets.OverlayPane overlay;
    private final JPanel infoBar = new JPanel(new BorderLayout());
    private final JLabel infoText = new JLabel();
    private final JPanel infoActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

    private final SmartUndoManager undoManager;
    private final BreakpointModel breakpoints = new BreakpointModel();
    private final SyntaxHighlighter highlighter;
    private final CodeInsight insight;
    private final AutoIndenter indenter;
    private final CompletionPopup completion;

    private LanguageSpec language = LanguageSpec.NONE;
    private boolean languagePinned;
    private Charset charset = StandardCharsets.UTF_8;
    private String lineSeparator = System.lineSeparator().equals("\r\n") ? "\r\n" : "\n";

    private File currentFile;
    private boolean isModified = false;
    private boolean isLoading = false;
    private boolean syncScroll = true;
    private long knownModified = -1;
    private long knownSize = -1;
    private boolean diskConflictShown;

    private List<EditorDiagnostic> externalDiagnostics = List.of();
    private List<EditorDiagnostic> insightDiagnostics = List.of();
    private List<EditorDiagnostic> combined = List.of();
    private SnippetSession snippet;
    private Host host;
    private int lastLineCount = 1;

    private final Timer previewDebounceTimer;
    private final Timer autoSaveTimer;
    private final EditorWidgets.FlatButton runButton;
    private final EditorWidgets.FlatButton previewButton;

    public CodeTab(File file) {
        this.currentFile = file;
        setLayout(new BorderLayout());
        setBackground(EditorTheme.background());

        /* --- 1. Code surface --- */
        editorArea = new CodeTextPane();
        editorArea.setFont(FontLoader.getTerminalFont(Font.PLAIN, EditorState.fontSize()));
        editorArea.setTabWidth(4);
        editorArea.setLineWrap(EditorState.flag(EditorState.WORD_WRAP, false));
        editorArea.setIndentGuidesVisible(EditorState.flag(EditorState.INDENT_GUIDES, true));
        editorArea.setOccurrencesVisible(EditorState.flag(EditorState.OCCURRENCES, true));
        ToolTipManager.sharedInstance().registerComponent(editorArea);
        // Ctrl+Tab switches tabs; it must not move the focus out of the code.
        editorArea.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, Collections.<AWTKeyStroke>emptySet());
        editorArea.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, Collections.<AWTKeyStroke>emptySet());

        /* --- 2. Undo/Redo --- */
        undoManager = new SmartUndoManager(editorArea.getDocument());
        editorArea.getDocument().addUndoableEditListener(undoManager);
        editorArea.setAtomicRunner(undoManager::group);

        /* --- 3. Language services --- */
        highlighter = new SyntaxHighlighter(editorArea, language);
        editorArea.setSyntax(highlighter);
        editorArea.setLanguage(language);
        insight = new CodeInsight(highlighter);
        insight.setEnabled(EditorState.flag(EditorState.LIVE_LINT, true));
        insight.addListener(this::onInsight);
        indenter = new AutoIndenter(editorArea, language, 4);
        indenter.setAutoClose(EditorState.flag(EditorState.AUTO_CLOSE, true));
        installEscapeChain();
        completion = new CompletionPopup(editorArea, new SmartCompletion(() -> language, insight::result,
                                                                          this::otherResults));
        completion.setAutoTrigger(EditorState.flag(EditorState.AUTO_COMPLETE, true));
        completion.setSnippetHandler(this::insertSnippet);
        indenter.setTabInterceptor(this::onTab, this::onShiftTab);
        new BracketMatcher(editorArea);

        /* --- 4. Timers --- */
        previewDebounceTimer = new Timer(300, e -> updatePreview());
        previewDebounceTimer.setRepeats(false);
        autoSaveTimer = new Timer(1500, e -> autoSave());
        autoSaveTimer.setRepeats(false);

        /* --- 5. Modification tracking --- */
        editorArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onEdit(e); }
            public void removeUpdate(DocumentEvent e) { onEdit(e); }
            public void changedUpdate(DocumentEvent e) { /* attribute-only */ }
        });

        /* --- 6. Scroll pane, gutter, minimap --- */
        editorScrollPane = new JScrollPane(editorArea);
        editorScrollPane.getViewport().setBackground(EditorTheme.background());
        editorScrollPane.setBorder(BorderFactory.createEmptyBorder());
        editorScrollPane.setViewportBorder(null);
        editorScrollPane.getVerticalScrollBar().setUnitIncrement(18);
        editorScrollPane.getHorizontalScrollBar().setUnitIncrement(18);

        gutter = new EditorGutter(editorArea, breakpoints);
        editorScrollPane.setRowHeaderView(gutter);

        minimap = new Minimap(editorArea, editorScrollPane);
        editorScrollPane.setLayout(new Minimap.ScrollLayout(minimap));
        editorScrollPane.add(minimap);
        setMinimapVisible(EditorState.flag(EditorState.MINIMAP, true));

        // Blit scrolling copies pixels instead of repainting them, so anything left
        // in the row header from an earlier layout survived there. Both viewports
        // repaint in full, which costs nothing at this size and leaves no residue.
        editorScrollPane.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        if (editorScrollPane.getRowHeader() != null) {
            editorScrollPane.getRowHeader().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
            editorScrollPane.getRowHeader().setOpaque(true);
            editorScrollPane.getRowHeader().setBackground(EditorTheme.gutterBackground());
        }
        JPanel corner = new JPanel();
        corner.setBackground(EditorTheme.background());
        editorScrollPane.setCorner(JScrollPane.LOWER_LEFT_CORNER, corner);
        editorScrollPane.setCorner(JScrollPane.LOWER_RIGHT_CORNER, new JPanel() {
            { setBackground(EditorTheme.background()); }
        });

        /* --- 7. Preview --- */
        previewArea = new JEditorPane();
        previewArea.setEditable(false);
        HTMLEditorKit htmlKit = new HTMLEditorKit();
        StyleSheet customStyle = new StyleSheet();
        customStyle.addRule("body { margin: 0px; padding: 8px; background-color: "
                            + hex(EditorTheme.background()) + "; color: "
                            + hex(EditorTheme.foreground()) + "; }");
        customStyle.addRule("p, div, h1, h2 { margin: 0px; padding: 0px; }");
        htmlKit.setStyleSheet(customStyle);
        previewArea.setEditorKit(htmlKit);
        previewArea.setContentType("text/html");
        previewArea.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        previewArea.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        previewArea.setBorder(BorderFactory.createEmptyBorder());

        previewScrollPane = new JScrollPane(previewArea);
        previewScrollPane.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, EditorTheme.chromeBorder()));
        previewScrollPane.setVisible(false);

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorScrollPane, previewScrollPane);
        splitPane.setResizeWeight(0.6);
        splitPane.setDividerSize(0);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());

        /* --- 8. Find bar floating over the code --- */
        findWidget = new FindWidget(editorArea, highlighter);
        overlay = new EditorWidgets.OverlayPane(splitPane);
        overlay.addOverlay(findWidget, EditorWidgets.Anchor.TOP_RIGHT, 20, -4);

        /* --- 9. Breadcrumbs and their actions --- */
        breadcrumbs = new BreadcrumbBar(new BreadcrumbBar.Host() {
            @Override public void openFile(File f) { if (host != null) host.openFile(f); }
            @Override public void goTo(DocSymbol s) { reveal(s); }
            @Override public List<DocSymbol> symbols() { return insight.result().symbols; }
        });
        runButton = new EditorWidgets.FlatButton(EditorIcons.play(16, () -> EditorTheme.readable(new Color(89, 196, 112))),
                                                 "Run in the Sphere console (F5)");
        runButton.addActionListener(e -> { if (host != null) host.runFile(this); });
        previewButton = new EditorWidgets.FlatButton(EditorIcons.preview(16, EditorTheme::textMuted),
                                                     "Toggle Preview (Ctrl+Shift+V)");
        previewButton.addActionListener(e -> togglePreview());
        EditorWidgets.FlatButton findButton = new EditorWidgets.FlatButton(EditorIcons.search(16, EditorTheme::textMuted),
                                                                           "Find (Ctrl+F)");
        findButton.addActionListener(e -> findWidget.open(false));
        EditorWidgets.FlatButton moreButton = new EditorWidgets.FlatButton(EditorIcons.more(16, EditorTheme::textMuted),
                                                                           "More Actions");
        moreButton.addActionListener(e -> showMoreMenu(moreButton));
        breadcrumbs.addAction(runButton);
        breadcrumbs.addAction(previewButton);
        breadcrumbs.addAction(findButton);
        breadcrumbs.addAction(moreButton);

        /* --- 10. Disk change banner --- */
        infoBar.setVisible(false);
        infoBar.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 8));
        infoText.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        infoActions.setOpaque(false);
        infoBar.add(infoText, BorderLayout.CENTER);
        infoBar.add(infoActions, BorderLayout.EAST);

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(breadcrumbs, BorderLayout.NORTH);
        north.add(infoBar, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(overlay, BorderLayout.CENTER);

        setupScrollSynchronization();
        installKeys();
        installHover();
        editorArea.addCaretListener(e -> {
            breadcrumbs.setPath(insight.result().path(editorArea.lineAt(e.getDot())));
            if (host != null) {
                host.tabStateChanged(this);
            }
        });

        if (file != null) {
            loadFile(file);
        }
        refreshChrome();
    }

    public void setHost(Host host) {
        this.host = host;
    }

    /** The name the tab shows while the buffer has no file. */
    public void setUntitledName(String name) {
        breadcrumbs.setUntitledName(name);
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ---- Edits -------------------------------------------------------------

    private void onEdit(DocumentEvent e) {
        int lines = editorArea.lineCountTotal();
        int delta = lines - lastLineCount;
        lastLineCount = lines;
        if (delta != 0 && !isLoading) {
            int line = editorArea.lineAt(e.getOffset());
            boolean atLineStart = editorArea.lineStartOffset(line) == e.getOffset();
            breakpoints.shift(line + (atLineStart ? 1 : 2), delta);
        }
        if (!isLoading) {
            this.isModified = true;
            if (currentMode != EditorMode.PLAIN_TEXT) {
                previewDebounceTimer.restart();
            }
            if (EditorState.flag(EditorState.AUTO_SAVE, false) && currentFile != null) {
                autoSaveTimer.restart();
            }
        }
    }

    private void autoSave() {
        if (isModified && currentFile != null && !isLoading) {
            saveFile();
            if (host != null) {
                host.tabStateChanged(this);
            }
        }
    }

    private List<CodeInsight.Result> otherResults() {
        if (host == null) {
            return List.of();
        }
        List<CodeInsight.Result> out = new ArrayList<>();
        for (CodeTab t : host.allTabs()) {
            if (t != this) {
                out.add(t.insight.result());
            }
        }
        return out;
    }

    // ---- Keys --------------------------------------------------------------

    private void bind(KeyStroke ks, String name, Runnable r) {
        editorArea.getInputMap(JComponent.WHEN_FOCUSED).put(ks, name);
        editorArea.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private static KeyStroke ks(int key, int mods) {
        return KeyStroke.getKeyStroke(key, mods);
    }

    private void installEscapeChain() {
        bind(ks(KeyEvent.VK_ESCAPE, 0), "sphere-escape", () -> {
            if (snippet != null && snippet.isActive()) {
                snippet.finish(false);
                snippet = null;
            } else if (findWidget != null && findWidget.isOpen()) {
                findWidget.close();
            } else if (infoBar.isVisible()) {
                infoBar.setVisible(false);
            } else if (editorArea.getSelectionStart() != editorArea.getSelectionEnd()) {
                editorArea.setCaretPosition(editorArea.getCaretPosition());
            }
        });
    }

    private void installKeys() {
        final int ctrl = InputEvent.CTRL_DOWN_MASK;
        final int shift = InputEvent.SHIFT_DOWN_MASK;
        final int alt = InputEvent.ALT_DOWN_MASK;

        bind(ks(KeyEvent.VK_SLASH, ctrl), "sphere-comment", () -> EditorActions.toggleLineComment(editorArea));
        bind(ks(KeyEvent.VK_DIVIDE, ctrl), "sphere-comment", () -> EditorActions.toggleLineComment(editorArea));
        bind(ks(KeyEvent.VK_A, shift | alt), "sphere-block-comment",
             () -> EditorActions.toggleBlockComment(editorArea, false));
        bind(ks(KeyEvent.VK_D, ctrl), "sphere-duplicate", () -> EditorActions.duplicate(editorArea, true));
        bind(ks(KeyEvent.VK_DOWN, shift | alt), "sphere-copy-down", () -> EditorActions.duplicate(editorArea, true));
        bind(ks(KeyEvent.VK_UP, shift | alt), "sphere-copy-up", () -> EditorActions.duplicate(editorArea, false));
        bind(ks(KeyEvent.VK_UP, alt), "sphere-move-up", () -> EditorActions.moveLines(editorArea, true));
        bind(ks(KeyEvent.VK_DOWN, alt), "sphere-move-down", () -> EditorActions.moveLines(editorArea, false));
        bind(ks(KeyEvent.VK_K, ctrl | shift), "sphere-delete-line", () -> EditorActions.deleteLines(editorArea));
        bind(ks(KeyEvent.VK_ENTER, ctrl), "sphere-line-below", () -> EditorActions.insertLine(editorArea, false));
        bind(ks(KeyEvent.VK_ENTER, ctrl | shift), "sphere-line-above", () -> EditorActions.insertLine(editorArea, true));
        bind(ks(KeyEvent.VK_L, ctrl), "sphere-select-line", () -> EditorActions.selectLine(editorArea));
        bind(ks(KeyEvent.VK_J, ctrl), "sphere-join", () -> EditorActions.joinLines(editorArea));
        bind(ks(KeyEvent.VK_RIGHT, shift | alt), "sphere-expand", () -> EditorActions.expandSelection(editorArea));
        bind(ks(KeyEvent.VK_LEFT, shift | alt), "sphere-shrink", () -> EditorActions.shrinkSelection(editorArea));
        bind(ks(KeyEvent.VK_OPEN_BRACKET, ctrl), "sphere-outdent",
             () -> EditorActions.indentLines(editorArea, indenter.unit(), indenter.indentSize(), true));
        bind(ks(KeyEvent.VK_CLOSE_BRACKET, ctrl), "sphere-indent",
             () -> EditorActions.indentLines(editorArea, indenter.unit(), indenter.indentSize(), false));

        bind(ks(KeyEvent.VK_F, ctrl), "sphere-find", () -> findWidget.open(false));
        bind(ks(KeyEvent.VK_H, ctrl), "sphere-replace", () -> findWidget.open(true));
        bind(ks(KeyEvent.VK_F3, 0), "sphere-find-next", () -> findWidget.step(1));
        bind(ks(KeyEvent.VK_F3, shift), "sphere-find-prev", () -> findWidget.step(-1));
        bind(ks(KeyEvent.VK_F12, 0), "sphere-definition", this::goToDefinitionAtCaret);
        bind(ks(KeyEvent.VK_F9, 0), "sphere-breakpoint",
             () -> breakpoints.toggle(editorArea.lineAt(editorArea.getCaretPosition()) + 1));
        bind(ks(KeyEvent.VK_Z, ctrl), "sphere-undo", this::undo);
        bind(ks(KeyEvent.VK_Y, ctrl), "sphere-redo", this::redo);
        bind(ks(KeyEvent.VK_Z, ctrl | shift), "sphere-redo", this::redo);
        bind(ks(KeyEvent.VK_Z, alt), "sphere-wrap", () -> setWordWrap(!editorArea.getLineWrap()));
        bind(ks(KeyEvent.VK_V, ctrl | shift), "sphere-preview", this::togglePreview);
    }

    private boolean onTab() {
        if (completion.acceptIfVisible()) {
            return true;
        }
        if (snippet != null && snippet.isActive()) {
            return snippet.next();
        }
        return false;
    }

    private boolean onShiftTab() {
        if (snippet != null && snippet.isActive()) {
            return snippet.previous();
        }
        return false;
    }

    private void insertSnippet(CompletionProvider.Item item) {
        if (snippet != null) {
            snippet.finish(false);
        }
        int anchor = completion.anchorOffset();
        int caret = editorArea.getCaretPosition();
        if (anchor < 0 || caret < anchor) {
            return;
        }
        snippet = SnippetSession.insert(editorArea, item.snippet, anchor, caret, indenter.unit(),
                                        () -> snippet = null);
    }

    // ---- Undo with the caret where the change was ---------------------------

    public void undo() {
        if (undoManager.canUndo()) {
            undoManager.undo();
            placeCaretAfterHistory();
        }
    }

    public void redo() {
        if (undoManager.canRedo()) {
            undoManager.redo();
            placeCaretAfterHistory();
        }
    }

    private void placeCaretAfterHistory() {
        int off = undoManager.lastChangeOffset();
        if (off >= 0) {
            int at = Math.min(off, editorArea.getDocument().getLength());
            if (Math.abs(editorArea.getCaretPosition() - at) > 0) {
                int caret = editorArea.getCaretPosition();
                // Keep a caret Swing already put right after the change.
                if (caret < at || caret > at + 400) {
                    editorArea.setCaretPosition(at);
                }
            }
        }
    }

    // ---- Hover and go to definition ----------------------------------------

    private void installHover() {
        editorArea.setTooltipProvider(this::tooltipAt);
        MouseAdapter links = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) == 0) {
                    clearLink();
                    return;
                }
                int offset = editorArea.viewToModel2D(e.getPoint());
                int[] w = editorArea.wordAt(offset);
                if (w != null && definitionOf(word(w)) != null) {
                    editorArea.setLink(w[0], w[1]);
                    editorArea.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                } else {
                    clearLink();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
                    int offset = editorArea.viewToModel2D(e.getPoint());
                    int[] w = editorArea.wordAt(offset);
                    if (w != null) {
                        goToDefinition(word(w));
                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                clearLink();
            }
        };
        editorArea.addMouseMotionListener(links);
        editorArea.addMouseListener(links);
        editorArea.addMouseWheelListener(e -> {
            if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0 && host instanceof ZoomHost z) {
                z.zoom(e.getWheelRotation() < 0 ? 1 : -1);
                e.consume();
            } else {
                editorScrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(editorArea, e, editorScrollPane));
            }
        });
    }

    /** Implemented by a host that can change the font size of every tab at once. */
    public interface ZoomHost {
        void zoom(int steps);
    }

    private void clearLink() {
        editorArea.setLink(-1, -1);
        editorArea.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
    }

    private String word(int[] w) {
        try {
            return editorArea.getDocument().getText(w[0], w[1] - w[0]);
        } catch (BadLocationException ex) {
            return "";
        }
    }

    /** {tab, symbol} defining a name, in this file first, then in the open ones. */
    private Object[] definitionOf(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        DocSymbol s = insight.result().find(name);
        if (s != null) {
            return new Object[] { this, s };
        }
        if (host != null) {
            for (CodeTab t : host.allTabs()) {
                if (t == this) {
                    continue;
                }
                DocSymbol o = t.insight.result().find(name);
                if (o != null && (o.kind.isType() || o.kind.isCallable() || o.kind == DocSymbol.Kind.TARGET)) {
                    return new Object[] { t, o };
                }
            }
        }
        return null;
    }

    public void goToDefinitionAtCaret() {
        int[] w = editorArea.wordAt(editorArea.getCaretPosition());
        if (w == null) {
            if (host != null) {
                host.toast("Place the caret on a name to go to its definition");
            }
            return;
        }
        goToDefinition(word(w));
    }

    public void goToDefinition(String name) {
        Object[] def = definitionOf(name);
        if (def == null) {
            if (host != null) {
                host.toast("No definition of '" + name + "' in the open files");
            }
            return;
        }
        CodeTab tab = (CodeTab) def[0];
        DocSymbol s = (DocSymbol) def[1];
        if (tab != this && host != null) {
            host.selectTab(tab);
        }
        tab.reveal(s);
    }

    /** Moves to a symbol's name and selects it. */
    public void reveal(DocSymbol s) {
        int off = Math.min(s.offset, editorArea.getDocument().getLength());
        int end = Math.min(off + s.name.length(), editorArea.getDocument().getLength());
        try {
            String at = editorArea.getDocument().getText(off, end - off);
            if (!at.equals(s.name)) {
                end = off;
            }
        } catch (BadLocationException ex) {
            end = off;
        }
        centerOn(off);
        editorArea.setCaretPosition(off);
        editorArea.moveCaretPosition(end);
        editorArea.requestFocusInWindow();
    }

    private void centerOn(int offset) {
        try {
            Rectangle2D r = editorArea.modelToView2D(offset);
            if (r != null) {
                Rectangle vis = editorArea.getVisibleRect();
                int y = Math.max(0, (int) r.getY() - vis.height / 3);
                editorArea.scrollRectToVisible(new Rectangle(vis.x, y, 1, vis.height));
            }
        } catch (BadLocationException ignored) {
            // nothing to scroll to
        }
    }

    private String tooltipAt(MouseEvent e) {
        int offset = editorArea.viewToModel2D(e.getPoint());
        int line = editorArea.lineAt(offset) + 1;
        StringBuilder sb = new StringBuilder();
        String fg = hex(EditorTheme.foreground());
        String muted = hex(EditorTheme.textMuted());
        String mono = editorArea.getFont().getFamily();
        for (EditorDiagnostic d : combined) {
            if (d.getLine() != line) {
                continue;
            }
            String color = hex(CodeTextPane.severityColor(d.getSeverity()));
            sb.append("<div style='margin-bottom:3px'><b style='color:").append(color).append("'>")
              .append(d.getSeverity() == EditorDiagnostic.Severity.ERROR ? "Error"
                      : d.getSeverity() == EditorDiagnostic.Severity.WARNING ? "Warning" : "Note")
              .append("</b> ").append(CodeTextPane.escape(d.getMessage()))
              .append(" <span style='color:").append(muted).append("'>").append(CodeTextPane.escape(d.getSource()))
              .append(d.getCode().isEmpty() || d.getCode().equals("TODO") ? "" : " " + CodeTextPane.escape(d.getCode()))
              .append("</span></div>");
        }
        int[] w = editorArea.wordAt(offset);
        if (w != null) {
            String name = word(w);
            String info = describe(name, w[0]);
            if (info != null) {
                if (sb.length() > 0) {
                    sb.append("<hr>");
                }
                sb.append(info.replace("{mono}", mono).replace("{fg}", fg).replace("{muted}", muted));
            }
        }
        if (sb.length() == 0) {
            return null;
        }
        return "<html><div style='width:420px;padding:2px'>" + sb + "</div></html>";
    }

    /** What the editor knows of a name: its definition here, or the library it comes from. */
    private String describe(String name, int at) {
        Object[] def = definitionOf(name);
        if (def != null) {
            DocSymbol s = (DocSymbol) def[1];
            CodeTab where = (CodeTab) def[0];
            String file = where == this ? "line " + (s.line + 1)
                        : (where.getFile() != null ? where.getFile().getName() : "Untitled") + ":" + (s.line + 1);
            StringBuilder sb = new StringBuilder();
            sb.append("<span style='font-family:\"{mono}\";color:{fg}'>").append(s.kind.label).append(" <b>")
              .append(CodeTextPane.escape(s.name)).append("</b>")
              .append(CodeTextPane.escape(s.kind.isCallable() ? s.detail : "")).append("</span>")
              .append(" <span style='color:{muted}'>").append(CodeTextPane.escape(file))
              .append(s.container != null ? " · in " + CodeTextPane.escape(s.container) : "").append("</span>");
            if (s.doc != null) {
                sb.append("<br>").append(CodeTextPane.escape(s.doc).replace("\n", "<br>"));
            }
            return sb.toString();
        }
        // A member of a known type: h->Fill, np.linspace
        try {
            String text = editorArea.getDocument().getText(0, editorArea.getDocument().getLength());
            SmartCompletion.Receiver r = SmartCompletion.receiverAt(text, at);
            String type = r == null ? null : SmartCompletion.typeOf(r, insight.result(), 0);
            KnowledgeBase.Member m = type == null ? null : KnowledgeBase.member(type, name);
            if (m == null && KnowledgeBase.knows(name)) {
                KnowledgeBase.Member ctor = KnowledgeBase.member("ROOT", name);
                return "<span style='font-family:\"{mono}\";color:{fg}'>class <b>" + CodeTextPane.escape(name)
                       + "</b></span>" + (ctor != null ? "<br>" + CodeTextPane.escape(ctor.doc) : "")
                       + " <span style='color:{muted}'>· " + KnowledgeBase.members(name).size() + " members known</span>";
            }
            if (m != null) {
                return "<span style='font-family:\"{mono}\";color:{fg}'>" + CodeTextPane.escape(m.owner) + "::<b>"
                       + CodeTextPane.escape(m.name) + "</b>" + CodeTextPane.escape(m.params)
                       + (m.returns.isEmpty() ? "" : " → " + CodeTextPane.escape(m.returns)) + "</span>"
                       + (m.doc.isEmpty() ? "" : "<br>" + CodeTextPane.escape(m.doc));
            }
        } catch (BadLocationException ignored) {
            // no description
        }
        return null;
    }

    // ---- Preview -----------------------------------------------------------

    private void setupScrollSynchronization() {
        editorScrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!syncScroll || currentMode == EditorMode.PLAIN_TEXT || isLoading) return;

            javax.swing.JScrollBar editorBar = editorScrollPane.getVerticalScrollBar();
            javax.swing.JScrollBar previewBar = previewScrollPane.getVerticalScrollBar();

            int editorMax = editorBar.getMaximum() - editorBar.getVisibleAmount();
            int previewMax = previewBar.getMaximum() - previewBar.getVisibleAmount();
            if (editorMax <= 0 || previewMax <= 0) return;

            float ratio = (float) editorBar.getValue() / (float) editorMax;
            previewBar.setValue((int) (ratio * previewMax));
        });
    }

    private void updatePreview() {
        if (currentMode == EditorMode.PLAIN_TEXT) return;

        String text = editorArea.getText();
        String renderedHtml;

        if (currentMode == EditorMode.MARKDOWN) {
            renderedHtml = MarkdownRenderer.render(text);
        } else if (currentMode == EditorMode.LATEX) {
            renderedHtml = "<html><body style='background-color:" + hex(EditorTheme.background())
                    + ";color:" + hex(EditorTheme.foreground()) + ";margin:0;padding:8px;'>"
                    + "<h1>LaTeX Mode</h1><p>" + CodeTextPane.escape(text).replace("\n", "<br>") + "</p></body></html>";
        } else {
            return;
        }
        final String html = renderedHtml;

        SwingUtilities.invokeLater(() -> {
            previewArea.putClientProperty("AsynchronousLoadPriority", Integer.valueOf(-1));
            HTMLDocument doc = (HTMLDocument) previewArea.getEditorKit().createDefaultDocument();
            previewArea.setDocument(doc);
            previewArea.setText(html);
            previewArea.setCaretPosition(0);
        });
    }

    public void setEditorMode(EditorMode mode) {
        this.currentMode = mode;
        boolean showPreview = (mode != EditorMode.PLAIN_TEXT);

        previewScrollPane.setVisible(showPreview);
        splitPane.setDividerSize(showPreview ? 5 : 0);

        if (showPreview) {
            SwingUtilities.invokeLater(() -> {
                splitPane.setDividerLocation(0.6);
                updatePreview();
            });
        }
        refreshChrome();
        revalidate();
        repaint();
    }

    public void togglePreview() {
        if (currentMode != EditorMode.PLAIN_TEXT) {
            setEditorMode(EditorMode.PLAIN_TEXT);
        } else if (language == LanguageSpec.LATEX) {
            setEditorMode(EditorMode.LATEX);
        } else {
            setEditorMode(EditorMode.MARKDOWN);
        }
    }

    // ---- Language ----------------------------------------------------------

    /** Chooses the language from the file name and rewires the services to it. */
    private void applyLanguage(File file) {
        if (!languagePinned) {
            setLanguageInternal(LanguageSpec.forFile(file));
        }
    }

    /** Picks a language by hand, overriding what the file name suggests. */
    public void setLanguage(LanguageSpec spec) {
        languagePinned = true;
        setLanguageInternal(spec);
    }

    private void setLanguageInternal(LanguageSpec spec) {
        language = spec == null ? LanguageSpec.NONE : spec;
        editorArea.setLanguage(language);
        highlighter.setLanguage(language);
        indenter.setLanguage(language);
        insight.setFile(currentFile);
        refreshChrome();
        if (host != null) {
            host.tabStateChanged(this);
        }
    }

    public LanguageSpec getLanguage() {
        return language;
    }

    private void refreshChrome() {
        breadcrumbs.setFile(currentFile, language);
        runButton.setVisible(LanguageSpec.runCommandFor(language, currentFile) != null);
        previewButton.setVisible(language == LanguageSpec.MARKDOWN || language == LanguageSpec.LATEX
                                 || currentMode != EditorMode.PLAIN_TEXT);
        editorArea.setWatermark(currentFile == null ? watermark : List.of());
    }

    private static List<String[]> watermark = List.of();

    /** The shortcuts shown on an empty, untitled tab. */
    public static void setWatermark(List<String[]> lines) {
        watermark = lines == null ? List.of() : lines;
    }

    /** Rebuilds colors after a theme switch. */
    public void refreshTheme() {
        setBackground(EditorTheme.background());
        editorArea.applyTheme();
        gutter.applyTheme();
        editorScrollPane.getViewport().setBackground(EditorTheme.background());
        if (editorScrollPane.getRowHeader() != null) {
            editorScrollPane.getRowHeader().setBackground(EditorTheme.gutterBackground());
        }
        highlighter.refreshTheme();
        findWidget.refreshTheme();
        repaint();
    }

    // ---- View switches -----------------------------------------------------

    public void setMinimapVisible(boolean visible) {
        minimap.setVisible(visible);
        editorArea.setRightReserve(visible && !editorArea.getLineWrap() ? Minimap.WIDTH : 0);
        editorScrollPane.revalidate();
        editorScrollPane.repaint();
    }

    public boolean isMinimapVisible() {
        return minimap.isVisible();
    }

    public void setWordWrap(boolean wrap) {
        editorArea.setLineWrap(wrap);
        editorArea.setRightReserve(minimap.isVisible() && !wrap ? Minimap.WIDTH : 0);
        editorScrollPane.revalidate();
    }

    public boolean isWordWrap() {
        return editorArea.getLineWrap();
    }

    public void applyFontSize(int size) {
        Font f = FontLoader.getTerminalFont(Font.PLAIN, size);
        int caret = editorArea.getCaretPosition();
        editorArea.setFont(f);
        editorArea.setTabSize(editorArea.getTabSize());
        editorArea.setCaretPosition(Math.min(caret, editorArea.getDocument().getLength()));
        editorScrollPane.revalidate();
        repaint();
    }

    public void setAutoComplete(boolean on) {
        completion.setAutoTrigger(on);
    }

    public void setAutoClose(boolean on) {
        indenter.setAutoClose(on);
    }

    public void setLiveLint(boolean on) {
        insight.setEnabled(on);
    }

    private void showMoreMenu(JComponent anchor) {
        JPopupMenu menu = new JPopupMenu();
        JCheckBoxMenuItem wrap = new JCheckBoxMenuItem("Word Wrap", isWordWrap());
        wrap.setAccelerator(ks(KeyEvent.VK_Z, InputEvent.ALT_DOWN_MASK));
        wrap.addActionListener(e -> setWordWrap(wrap.isSelected()));
        JCheckBoxMenuItem map = new JCheckBoxMenuItem("Minimap", isMinimapVisible());
        map.addActionListener(e -> {
            EditorState.setFlag(EditorState.MINIMAP, map.isSelected());
            if (host != null) {
                for (CodeTab t : host.allTabs()) {
                    t.setMinimapVisible(map.isSelected());
                }
            } else {
                setMinimapVisible(map.isSelected());
            }
        });
        JCheckBoxMenuItem guides = new JCheckBoxMenuItem("Indent Guides", editorArea.isIndentGuidesVisible());
        guides.addActionListener(e -> {
            EditorState.setFlag(EditorState.INDENT_GUIDES, guides.isSelected());
            editorArea.setIndentGuidesVisible(guides.isSelected());
        });
        JMenuItem comment = new JMenuItem("Toggle Line Comment");
        comment.setAccelerator(ks(KeyEvent.VK_SLASH, InputEvent.CTRL_DOWN_MASK));
        comment.addActionListener(e -> EditorActions.toggleLineComment(editorArea));
        JMenuItem trim = new JMenuItem("Trim Trailing Whitespace");
        trim.addActionListener(e -> {
            int n = EditorActions.trimTrailingWhitespace(editorArea);
            if (host != null) {
                host.toast(n == 0 ? "No trailing whitespace" : "Trimmed " + n + " line" + (n == 1 ? "" : "s"));
            }
        });
        JMenuItem palette = new JMenuItem("Command Palette...");
        palette.setAccelerator(ks(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        palette.addActionListener(e -> { if (host != null) host.showPalette(">"); });
        JMenuItem symbols = new JMenuItem("Go to Symbol in File...");
        symbols.setAccelerator(ks(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        symbols.addActionListener(e -> { if (host != null) host.showPalette("@"); });
        menu.add(palette);
        menu.add(symbols);
        menu.addSeparator();
        menu.add(wrap);
        menu.add(map);
        menu.add(guides);
        menu.addSeparator();
        menu.add(comment);
        menu.add(trim);
        menu.show(anchor, 0, anchor.getHeight());
    }

    // ---- Insight -----------------------------------------------------------

    private void onInsight(CodeInsight.Result r) {
        insightDiagnostics = r.diagnostics;
        mergeDiagnostics();
        gutter.setChanges(r.changes);
        minimap.setChanges(r.changes);
        breadcrumbs.setPath(r.path(editorArea.lineAt(editorArea.getCaretPosition())));
        if (host != null) {
            host.tabStateChanged(this);
        }
    }

    private void mergeDiagnostics() {
        List<EditorDiagnostic> all = new ArrayList<>(externalDiagnostics);
        Set<String> seen = new HashSet<>();
        for (EditorDiagnostic d : externalDiagnostics) {
            seen.add(d.getLine() + ":" + d.getMessage());
        }
        for (EditorDiagnostic d : insightDiagnostics) {
            if (seen.add(d.getLine() + ":" + d.getMessage())) {
                all.add(d);
            }
        }
        combined = Collections.unmodifiableList(all);
        editorArea.setDiagnostics(combined);
        gutter.setDiagnostics(combined);
        minimap.setDiagnostics(combined);
    }

    public CodeInsight getInsight() {
        return insight;
    }

    /** Compiler findings and the editor's own, together. */
    public List<EditorDiagnostic> getAllDiagnostics() {
        return combined;
    }

    // ---- Diagnostics and debugging ----------------------------------------

    public void setDiagnostics(List<EditorDiagnostic> diagnostics) {
        externalDiagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        mergeDiagnostics();
        if (host != null) {
            host.tabStateChanged(this);
        }
    }

    public void clearDiagnostics() {
        setDiagnostics(List.of());
    }

    public BreakpointModel getBreakpoints() {
        return breakpoints;
    }

    /** Marks the line a debug session is stopped on, or -1 to clear it. */
    public void setExecutionLine(int line) {
        editorArea.setExecutionLine(line);
        gutter.setExecutionLine(line);
        if (line > 0) {
            scrollToLine(line);
        }
    }

    public void scrollToLine(int line) {
        goToLine(line, 0);
    }

    /** 1-based line, 1-based column (0 for the line's first character). */
    public void goToLine(int line, int column) {
        try {
            Element root = editorArea.getDocument().getDefaultRootElement();
            if (root.getElementCount() == 0) {
                return;
            }
            int l = Math.max(1, Math.min(line, root.getElementCount()));
            Element el = root.getElement(l - 1);
            int start = el.getStartOffset();
            int end = Math.max(start, el.getEndOffset() - 1);
            int offset;
            if (column > 0) {
                offset = Math.min(start + column - 1, end);
            } else {
                String text = editorArea.getDocument().getText(start, end - start);
                offset = start + AutoIndenter.leadingWhitespace(text).length();
            }
            centerOn(offset);
            editorArea.setCaretPosition(offset);
            editorArea.requestFocusInWindow();
        } catch (Exception ignored) {
            // the buffer changed under the requested line
        }
    }

    public void openFind(boolean replace) {
        findWidget.open(replace);
    }

    public void findNext(int direction) {
        findWidget.step(direction);
    }

    // ---- File --------------------------------------------------------------

    private void loadFile(File file) {
        isLoading = true;
        previewDebounceTimer.stop();
        try {
            editorArea.getDocument().removeUndoableEditListener(undoManager);

            EditorFileIO.Loaded loaded = EditorFileIO.read(file);
            charset = loaded.charset;
            String text = loaded.text;
            lineSeparator = text.contains("\r\n") ? "\r\n" : "\n";
            String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
            editorArea.setText(normalized);
            editorArea.setCaretPosition(0);
            detectIndentation(normalized);
            knownModified = file.lastModified();
            knownSize = file.length();
            diskConflictShown = false;
            infoBar.setVisible(false);

            if (loaded.fellBack) {
                AppLogger.warn("Not valid UTF-8, opened as ISO-8859-1: " + file.getName());
            }

            undoManager.discardAllEdits();
            isModified = false;
            lastLineCount = editorArea.lineCountTotal();
            applyLanguage(file);
            insight.setFile(file);
            insight.setBaseline(normalized);
            autoDetectModeFromFile();
            highlighter.rehighlightAll();
            updatePreview();

        } catch (EditorFileIO.TooLargeException tooBig) {
            AppLogger.error(tooBig.getMessage());
        } catch (IOException e) {
            AppLogger.error("Failed to load file: " + e.getMessage());
        } finally {
            editorArea.getDocument().addUndoableEditListener(undoManager);
            isLoading = false;
        }
        refreshChrome();
    }

    /** Tabs or spaces, and how many, as the file is already indented. */
    private void detectIndentation(String text) {
        int tabs = 0;
        int spaces = 0;
        int[] widths = new int[9];
        int previous = 0;
        String[] lines = text.split("\n", 2000);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            char c = line.charAt(0);
            if (c == '\t') {
                tabs++;
                previous = -1;
                continue;
            }
            int n = 0;
            while (n < line.length() && line.charAt(n) == ' ') {
                n++;
            }
            if (n > 0) {
                spaces++;
            }
            if (previous >= 0) {
                int d = Math.abs(n - previous);
                if (d >= 2 && d <= 8) {
                    widths[d]++;
                }
            }
            previous = n;
        }
        if (tabs > spaces) {
            indenter.setIndentation(true, 4);
            editorArea.setTabSize(4);
            return;
        }
        int best = 4;
        int bestCount = 0;
        for (int w : new int[] { 4, 2, 8, 3 }) {
            if (widths[w] > bestCount * 1.2) {
                best = w;
                bestCount = widths[w];
            }
        }
        indenter.setIndentation(false, spaces == 0 ? 4 : best);
        editorArea.setTabSize(spaces == 0 ? 4 : best);
    }

    public void saveFile() {
        if (currentFile == null) return;
        try {
            if (EditorState.flag(EditorState.TRIM_ON_SAVE, false)) {
                EditorActions.trimTrailingWhitespace(editorArea);
            }
            String text = editorArea.getText();
            String out = lineSeparator.equals("\n") ? text : text.replace("\n", lineSeparator);
            EditorFileIO.write(currentFile, out, charset);
            isModified = false;
            knownModified = currentFile.lastModified();
            knownSize = currentFile.length();
            diskConflictShown = false;
            infoBar.setVisible(false);
            undoManager.closeTyping();
            insight.setBaseline(text);
            refreshChrome();
        } catch (IOException e) {
            AppLogger.error("Failed to save: " + e.getMessage());
        }
    }

    /**
     * Looks at the file on disk. Unedited, a changed file is reloaded where the
     * caret was; edited, the user chooses. Formatting a file in the console now
     * shows up here instead of being overwritten by the next save.
     */
    public void checkDisk() {
        if (currentFile == null || isLoading) {
            return;
        }
        if (!currentFile.exists()) {
            if (!diskConflictShown) {
                showInfo("This file was deleted or moved on disk.", "Save Here", this::saveFile, null, null);
                diskConflictShown = true;
            }
            return;
        }
        long lm = currentFile.lastModified();
        long size = currentFile.length();
        if (lm == knownModified && size == knownSize) {
            return;
        }
        if (!isModified) {
            reloadKeepingPosition();
            if (host != null) {
                host.toast("Reloaded " + currentFile.getName() + " from disk");
            }
        } else if (!diskConflictShown) {
            diskConflictShown = true;
            showInfo("This file changed on disk while you were editing it.",
                     "Reload", this::reloadKeepingPosition, "Keep Mine", () -> {
                         knownModified = currentFile.lastModified();
                         knownSize = currentFile.length();
                         infoBar.setVisible(false);
                     });
        }
    }

    private void reloadKeepingPosition() {
        int caret = editorArea.getCaretPosition();
        int scroll = editorScrollPane.getVerticalScrollBar().getValue();
        loadFile(currentFile);
        editorArea.setCaretPosition(Math.min(caret, editorArea.getDocument().getLength()));
        SwingUtilities.invokeLater(() -> editorScrollPane.getVerticalScrollBar().setValue(scroll));
        if (host != null) {
            host.tabStateChanged(this);
        }
    }

    private void showInfo(String text, String a, Runnable actionA, String b, Runnable actionB) {
        infoBar.setBackground(EditorTheme.mix(EditorTheme.warningSquiggle(), EditorTheme.background(), 0.16f));
        infoText.setForeground(EditorTheme.foreground());
        infoText.setIcon(EditorIcons.warning(15));
        infoText.setIconTextGap(8);
        infoText.setText(text);
        infoActions.removeAll();
        EditorWidgets.FlatButton first = new EditorWidgets.FlatButton(a, null);
        first.addActionListener(e -> { infoBar.setVisible(false); actionA.run(); });
        infoActions.add(first);
        if (b != null) {
            EditorWidgets.FlatButton second = new EditorWidgets.FlatButton(b, null);
            second.addActionListener(e -> { infoBar.setVisible(false); if (actionB != null) actionB.run(); });
            infoActions.add(second);
        }
        infoBar.setVisible(true);
        revalidate();
        repaint();
    }

    private void autoDetectModeFromFile() {
        if (currentFile == null) return;
        String name = currentFile.getName().toLowerCase();

        if (name.endsWith(".md") || name.endsWith(".markdown")) {
            setEditorMode(EditorMode.MARKDOWN);
        } else if (name.endsWith(".tex")) {
            setEditorMode(EditorMode.LATEX);
        } else {
            setEditorMode(EditorMode.PLAIN_TEXT);
        }
    }

    public void safeUndo() {
        undo();
    }

    public void setLineNumbersVisible(boolean visible) {
        editorScrollPane.setRowHeaderView(visible ? gutter : null);
        editorScrollPane.revalidate();
        editorScrollPane.repaint();
    }

    // ---- Encoding and line endings -----------------------------------------

    public String getLineSeparator() {
        return lineSeparator;
    }

    public void setLineSeparator(String separator) {
        if (!separator.equals(lineSeparator)) {
            lineSeparator = separator;
            isModified = true;
            if (host != null) {
                host.tabStateChanged(this);
            }
        }
    }

    public Charset getCharset() {
        return charset;
    }

    public void setCharset(Charset cs) {
        if (cs != null && !cs.equals(charset)) {
            charset = cs;
            isModified = true;
            if (host != null) {
                host.tabStateChanged(this);
            }
        }
    }

    /** Reads the file again in another encoding. */
    public void reopenWithCharset(Charset cs) {
        if (currentFile == null || cs == null) {
            return;
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(currentFile.toPath());
            String text = new String(bytes, cs).replace("\r\n", "\n").replace('\r', '\n');
            isLoading = true;
            editorArea.getDocument().removeUndoableEditListener(undoManager);
            editorArea.setText(text);
            editorArea.setCaretPosition(0);
            undoManager.discardAllEdits();
            charset = cs;
            isModified = false;
            insight.setBaseline(text);
        } catch (IOException e) {
            AppLogger.error("Failed to reopen " + currentFile.getName() + ": " + e.getMessage());
        } finally {
            editorArea.getDocument().addUndoableEditListener(undoManager);
            isLoading = false;
        }
        if (host != null) {
            host.tabStateChanged(this);
        }
    }

    public AutoIndenter getIndenter() {
        return indenter;
    }

    /** Re-indents with tabs or spaces of a given width. */
    public void setIndentation(boolean tabs, int size, boolean convert) {
        indenter.setIndentation(tabs, size);
        editorArea.setTabSize(size);
        if (convert) {
            EditorActions.convertIndentation(editorArea, tabs, size);
        }
        if (host != null) {
            host.tabStateChanged(this);
        }
    }

    // Getters & Setters
    public boolean isModified() { return isModified; }
    public File getFile() { return currentFile; }
    public void setFile(File file) {
        this.currentFile = file;
        applyLanguage(file);
        insight.setFile(file);
        autoDetectModeFromFile();
        refreshChrome();
    }
    public EditorMode getCurrentMode() { return currentMode; }
    public void setSyncScroll(boolean enabled) { this.syncScroll = enabled; }
    public UndoManager getUndoManager() { return undoManager; }
    public CodeTextPane getEditorArea() { return editorArea; }
    public void loadFileFromExternal(File file) { this.currentFile = file; loadFile(file); }
    public boolean isRunnable() { return LanguageSpec.runCommandFor(language, currentFile) != null; }
}

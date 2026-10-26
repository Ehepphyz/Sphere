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
import javax.swing.text.AbstractDocument;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import javax.swing.text.PlainDocument;
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
import java.awt.Point;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.sphere.utils.AppLogger;
import com.sphere.fonts.FontLoader;

/**
 * One editor tab: the code surface with its gutter and minimap, the breadcrumb
 * bar above, the find bar floating over it, and the markdown or LaTeX preview
 * beside it. It runs the language services of its view: highlighting, the
 * analysis behind outline and problems, completion and indentation.
 *
 * The text itself, its undo history, its breakpoints and its file live in a
 * {@link TextBuffer}, which two tabs share when the same file is open in two
 * split groups: typing in one shows in the other, undo walks one history.
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

        /**
         * The tabs definitions and completions are looked up in: those of every
         * window of the editor when a group was moved into one of its own.
         */
        default List<CodeTab> openTabs() {
            return allTabs();
        }

        void selectTab(CodeTab tab);

        void tabStateChanged(CodeTab tab);

        /** Shows a symbol of a tab in the split group beside the asking one. */
        default void revealToSide(CodeTab from, CodeTab target, DocSymbol symbol) {
            selectTab(target);
            target.reveal(symbol);
        }
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

    private final TextBuffer buffer;
    private final SmartUndoManager undoManager;
    private final BreakpointModel breakpoints;
    private final SyntaxHighlighter highlighter;
    private final CodeInsight insight;
    private final AutoIndenter indenter;
    private final CompletionPopup completion;
    /** What this view hung on the shared document, taken off again when it closes. */
    private final List<DocumentListener> ownListeners = new ArrayList<>();
    private boolean disposed;

    private LanguageSpec language = LanguageSpec.NONE;
    private boolean languagePinned;
    private boolean syncScroll = true;

    private List<EditorDiagnostic> externalDiagnostics = List.of();
    private List<EditorDiagnostic> insightDiagnostics = List.of();
    private List<EditorDiagnostic> combined = List.of();
    private SnippetSession snippet;
    private Host host;

    private final Timer previewDebounceTimer;
    private final Timer autoSaveTimer;
    private final EditorWidgets.FlatButton runButton;
    private final EditorWidgets.FlatButton previewButton;

    public CodeTab(File file) {
        this(file, null);
    }

    /**
     * A second view of the buffer another tab shows, for a split: the same
     * text, history and breakpoints, its own caret, scroll and preview.
     */
    public CodeTab(CodeTab twin) {
        this(twin.getFile(), twin);
    }

    private CodeTab(File file, CodeTab twin) {
        setLayout(new BorderLayout());
        setBackground(EditorTheme.background());

        /* --- 1. Code surface, on its own buffer or on the twin's --- */
        editorArea = new CodeTextPane();
        if (twin != null) {
            editorArea.setDocument(twin.buffer.document);
        }
        buffer = twin != null ? twin.buffer : new TextBuffer(editorArea.getDocument(), file);
        buffer.attach(this);
        Set<DocumentListener> before = new HashSet<>(documentListeners());
        editorArea.setFont(FontLoader.getTerminalFont(Font.PLAIN, EditorState.fontSize()));
        editorArea.setTabWidth(4);
        editorArea.setLineWrap(EditorState.flag(EditorState.WORD_WRAP, false));
        editorArea.setIndentGuidesVisible(EditorState.flag(EditorState.INDENT_GUIDES, true));
        editorArea.setOccurrencesVisible(EditorState.flag(EditorState.OCCURRENCES, true));
        ToolTipManager.sharedInstance().registerComponent(editorArea);
        // Ctrl+Tab switches tabs; it must not move the focus out of the code.
        editorArea.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, Collections.<AWTKeyStroke>emptySet());
        editorArea.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, Collections.<AWTKeyStroke>emptySet());

        /* --- 2. Undo/Redo, one history per buffer --- */
        undoManager = buffer.undo;
        breakpoints = buffer.breakpoints;
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

        /* --- 5. Modification tracking (the dirty flag lives in the buffer) --- */
        editorArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onEdit(); }
            public void removeUpdate(DocumentEvent e) { onEdit(); }
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

        splitPane = new EditorWidgets.QuietSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorScrollPane, previewScrollPane);
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

        for (DocumentListener l : documentListeners()) {
            if (!before.contains(l)) {
                ownListeners.add(l);
            }
        }

        if (twin != null) {
            adoptFrom(twin);
        } else if (file != null) {
            loadFile(file);
        }
        refreshChrome();
    }

    private List<DocumentListener> documentListeners() {
        if (editorArea.getDocument() instanceof AbstractDocument ad) {
            return Arrays.asList(ad.getDocumentListeners());
        }
        return List.of();
    }

    /** A new view takes on what its twin already worked out about the buffer. */
    private void adoptFrom(CodeTab twin) {
        languagePinned = twin.languagePinned;
        setLanguageInternal(twin.language);
        applyIndentation(twin.indenter.usesTabs(), twin.indenter.indentSize());
        insight.setFile(buffer.file);
        insight.setBaseline(buffer.savedText);
        externalDiagnostics = twin.externalDiagnostics;
        mergeDiagnostics();
        editorArea.setCaretPosition(Math.min(twin.editorArea.getCaret().getMark(), buffer.document.getLength()));
        editorArea.moveCaretPosition(Math.min(twin.editorArea.getCaret().getDot(), buffer.document.getLength()));
    }

    /** Scrolls to where another view of the buffer stands, once this one is laid out. */
    public void matchScrollOf(CodeTab other) {
        if (other == null) {
            return;
        }
        Point p = other.editorScrollPane.getViewport().getViewPosition();
        SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(() -> {
            JViewport vp = editorScrollPane.getViewport();
            int maxY = Math.max(0, editorArea.getHeight() - vp.getExtentSize().height);
            vp.setViewPosition(new Point(0, Math.max(0, Math.min(p.y, maxY))));
        }));
    }

    public void setHost(Host host) {
        this.host = host;
    }

    /** The shared state of the file this tab shows. */
    public TextBuffer getBuffer() {
        return buffer;
    }

    /** True when both tabs show the same buffer, in two groups. */
    public boolean sharesBufferWith(CodeTab other) {
        return other != null && other.buffer == buffer;
    }

    /** Called by the buffer when its dirty flag or file changed. */
    void bufferStateChanged() {
        if (host != null) {
            host.tabStateChanged(this);
        }
    }

    /**
     * Takes this view off the shared document. The buffer lives on in the other
     * views of the file; without this a closed split would keep listening to
     * every keystroke made in the other one.
     */
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        host = null;
        previewDebounceTimer.stop();
        autoSaveTimer.stop();
        completion.hide();
        if (snippet != null) {
            snippet.finish(false);
            snippet = null;
        }
        highlighter.dispose();
        for (DocumentListener l : ownListeners) {
            buffer.document.removeDocumentListener(l);
        }
        ownListeners.clear();
        gutter.dispose();
        ToolTipManager.sharedInstance().unregisterComponent(editorArea);
        buffer.detach(this);
        if (!buffer.views().isEmpty()) {
            // The text UI and the caret listen to the document they show.
            editorArea.setDocument(new PlainDocument());
        }
    }

    public boolean isDisposed() {
        return disposed;
    }

    /** The name the tab shows while the buffer has no file. */
    public void setUntitledName(String name) {
        breadcrumbs.setUntitledName(name);
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ---- Edits -------------------------------------------------------------

    /** What this view does after any edit, made here or in another view of the buffer. */
    private void onEdit() {
        if (buffer.loading) {
            return;
        }
        if (currentMode != EditorMode.PLAIN_TEXT) {
            previewDebounceTimer.restart();
        }
        if (EditorState.flag(EditorState.AUTO_SAVE, false) && buffer.file != null) {
            autoSaveTimer.restart();
        }
    }

    private void autoSave() {
        if (buffer.modified && buffer.file != null && !buffer.loading) {
            saveFile();
        }
    }

    /** The analyses of the other open files; a second view of this one adds nothing. */
    private List<CodeInsight.Result> otherResults() {
        if (host == null) {
            return List.of();
        }
        List<CodeInsight.Result> out = new ArrayList<>();
        for (CodeTab t : host.openTabs()) {
            if (t.buffer != buffer) {
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
        // The text area's own Ctrl+\ (unselect) would hide Split Editor Right from the editor around it.
        editorArea.getInputMap(JComponent.WHEN_FOCUSED).put(ks(KeyEvent.VK_BACK_SLASH, ctrl), "none");
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
            buffer.keepOtherViews(this, undoManager::undo);
            placeCaretAfterHistory();
            buffer.recheckSaved();
        }
    }

    public void redo() {
        if (undoManager.canRedo()) {
            buffer.keepOtherViews(this, undoManager::redo);
            placeCaretAfterHistory();
            buffer.recheckSaved();
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
                        // Ctrl+Alt+click keeps this view and opens the definition beside it.
                        goToDefinition(word(w), (e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0);
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
            for (CodeTab t : host.openTabs()) {
                if (t.buffer == buffer) {
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
        goToDefinitionAtCaret(false);
    }

    /** F12, or Ctrl+K F12 to open the definition in the group beside this one. */
    public void goToDefinitionAtCaret(boolean toSide) {
        int[] w = editorArea.wordAt(editorArea.getCaretPosition());
        if (w == null) {
            if (host != null) {
                host.toast("Place the caret on a name to go to its definition");
            }
            return;
        }
        goToDefinition(word(w), toSide);
    }

    public void goToDefinition(String name) {
        goToDefinition(name, false);
    }

    public void goToDefinition(String name, boolean toSide) {
        Object[] def = definitionOf(name);
        if (def == null) {
            if (host != null) {
                host.toast("No definition of '" + name + "' in the open files");
            }
            return;
        }
        CodeTab tab = (CodeTab) def[0];
        DocSymbol s = (DocSymbol) def[1];
        if (toSide && host != null) {
            host.revealToSide(this, tab, s);
            return;
        }
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
            if (!syncScroll || currentMode == EditorMode.PLAIN_TEXT || buffer.loading) return;

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

    /** Picks a language by hand, overriding what the file name suggests, in every view of the file. */
    public void setLanguage(LanguageSpec spec) {
        for (CodeTab v : buffer.views()) {
            v.languagePinned = true;
            v.setLanguageInternal(spec);
        }
    }

    private void setLanguageInternal(LanguageSpec spec) {
        language = spec == null ? LanguageSpec.NONE : spec;
        editorArea.setLanguage(language);
        highlighter.setLanguage(language);
        indenter.setLanguage(language);
        insight.setFile(buffer.file);
        refreshChrome();
        if (host != null) {
            host.tabStateChanged(this);
        }
    }

    public LanguageSpec getLanguage() {
        return language;
    }

    private void refreshChrome() {
        breadcrumbs.setFile(buffer.file, language);
        runButton.setVisible(LanguageSpec.runCommandFor(language, buffer.file) != null);
        previewButton.setVisible(language == LanguageSpec.MARKDOWN || language == LanguageSpec.LATEX
                                 || currentMode != EditorMode.PLAIN_TEXT);
        editorArea.setWatermark(buffer.file == null ? watermark : List.of());
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
                for (CodeTab t : host.openTabs()) {
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

    /**
     * Reads the file into the buffer. The other views of the buffer keep their
     * place, then learn what the reading found (encoding, indentation).
     */
    private void loadFile(File file) {
        previewDebounceTimer.stop();
        final String[] loadedText = { null };
        buffer.keepOtherViews(this, () -> {
            buffer.beginLoading();
            try {
                EditorFileIO.Loaded loaded = EditorFileIO.read(file);
                String text = loaded.text;
                String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
                editorArea.setText(normalized);
                editorArea.setCaretPosition(0);
                buffer.charset = loaded.charset;
                buffer.bom = loaded.bom;
                buffer.lineSeparator = text.contains("\r\n") ? "\r\n" : "\n";
                buffer.knownModified = file.lastModified();
                buffer.knownSize = file.length();
                buffer.diskConflictShown = false;
                buffer.savedText = normalized;
                buffer.formatDirty = false;
                if (loaded.fellBack) {
                    AppLogger.warn("Not valid UTF-8, opened as ISO-8859-1: " + file.getName());
                }
                loadedText[0] = normalized;
            } catch (EditorFileIO.TooLargeException tooBig) {
                // grown past the limit since it was opened (a reload): what the disk holds is shown read-only
                AppLogger.error(tooBig.getMessage() + " Shown read-only in the large file viewer.");
                LargeFileViewer.show(file);
            } catch (IOException e) {
                AppLogger.error("Failed to load file: " + e.getMessage());
            } finally {
                buffer.endLoading(loadedText[0] != null);
            }
        });
        if (loadedText[0] != null) {
            int[] indent = detectIndentation(loadedText[0]);
            for (CodeTab v : buffer.views()) {
                v.afterLoad(indent[0] == 1, indent[1]);
            }
            buffer.setModified(false);
        }
        refreshChrome();
    }

    private void afterLoad(boolean tabs, int size) {
        infoBar.setVisible(false);
        applyIndentation(tabs, size);
        applyLanguage(buffer.file);
        insight.setFile(buffer.file);
        insight.setBaseline(buffer.savedText);
        autoDetectModeFromFile();
        highlighter.rehighlightAll();
        updatePreview();
        refreshChrome();
    }

    /** Tabs or spaces, and how many, as the file is already indented: {1 for tabs, width}. */
    private static int[] detectIndentation(String text) {
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
            return new int[] { 1, 4 };
        }
        int best = 4;
        int bestCount = 0;
        for (int w : new int[] { 4, 2, 8, 3 }) {
            if (widths[w] > bestCount * 1.2) {
                best = w;
                bestCount = widths[w];
            }
        }
        return new int[] { 0, spaces == 0 ? 4 : best };
    }

    private void applyIndentation(boolean tabs, int size) {
        indenter.setIndentation(tabs, size);
        editorArea.setTabSize(size);
    }

    public void saveFile() {
        File file = buffer.file;
        if (file == null) return;
        try {
            if (EditorState.flag(EditorState.TRIM_ON_SAVE, false)) {
                EditorActions.trimTrailingWhitespace(editorArea);
            }
            String text = editorArea.getText();
            String out = buffer.lineSeparator.equals("\n") ? text : text.replace("\n", buffer.lineSeparator);
            EditorFileIO.write(file, out, buffer.charset, buffer.bom);
            buffer.knownModified = file.lastModified();
            buffer.knownSize = file.length();
            buffer.diskConflictShown = false;
            buffer.savedText = text;
            buffer.formatDirty = false;
            undoManager.closeTyping();
            for (CodeTab v : buffer.views()) {
                v.afterSave();
            }
            buffer.setModified(false);
        } catch (IOException e) {
            AppLogger.error("Failed to save: " + e.getMessage());
        }
    }

    private void afterSave() {
        infoBar.setVisible(false);
        insight.setBaseline(buffer.savedText);
        refreshChrome();
    }

    /**
     * Looks at the file on disk. Unedited, a changed file is reloaded where the
     * caret was; edited, the user chooses. Formatting a file in the console now
     * shows up here instead of being overwritten by the next save.
     */
    public void checkDisk() {
        File file = buffer.file;
        if (file == null || buffer.loading) {
            return;
        }
        if (!file.exists()) {
            if (!buffer.diskConflictShown) {
                buffer.diskConflictShown = true;
                showInfoAll("This file was deleted or moved on disk.", "Save Here", this::saveFile, null, null);
            }
            return;
        }
        long lm = file.lastModified();
        long size = file.length();
        if (lm == buffer.knownModified && size == buffer.knownSize) {
            return;
        }
        if (!buffer.modified) {
            reloadKeepingPosition();
            if (host != null) {
                host.toast("Reloaded " + file.getName() + " from disk");
            }
        } else if (!buffer.diskConflictShown) {
            buffer.diskConflictShown = true;
            showInfoAll("This file changed on disk while you were editing it.",
                        "Reload", this::reloadKeepingPosition, "Keep Mine", () -> {
                            buffer.knownModified = file.lastModified();
                            buffer.knownSize = file.length();
                        });
        }
    }

    private void reloadKeepingPosition() {
        int caret = editorArea.getCaretPosition();
        int scroll = editorScrollPane.getVerticalScrollBar().getValue();
        loadFile(buffer.file);
        editorArea.setCaretPosition(Math.min(caret, editorArea.getDocument().getLength()));
        SwingUtilities.invokeLater(() -> editorScrollPane.getVerticalScrollBar().setValue(scroll));
        if (host != null) {
            host.tabStateChanged(this);
        }
    }

    /** The banner above the code, in every view of the file: answering it in one answers it in all. */
    private void showInfoAll(String text, String a, Runnable actionA, String b, Runnable actionB) {
        for (CodeTab v : buffer.views()) {
            v.showInfo(text, a, actionA, b, actionB);
        }
    }

    private void hideInfoAll() {
        for (CodeTab v : buffer.views()) {
            v.infoBar.setVisible(false);
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
        first.addActionListener(e -> { hideInfoAll(); actionA.run(); });
        infoActions.add(first);
        if (b != null) {
            EditorWidgets.FlatButton second = new EditorWidgets.FlatButton(b, null);
            second.addActionListener(e -> { hideInfoAll(); if (actionB != null) actionB.run(); });
            infoActions.add(second);
        }
        infoBar.setVisible(true);
        revalidate();
        repaint();
    }

    private void autoDetectModeFromFile() {
        if (buffer.file == null) return;
        String name = buffer.file.getName().toLowerCase();

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
        return buffer.lineSeparator;
    }

    public void setLineSeparator(String separator) {
        if (!separator.equals(buffer.lineSeparator)) {
            buffer.lineSeparator = separator;
            buffer.formatDirty = true;
            buffer.setModified(true);
            bufferStateChanged();
        }
    }

    public Charset getCharset() {
        return buffer.charset;
    }

    public void setCharset(Charset cs) {
        if (cs != null && !cs.equals(buffer.charset)) {
            buffer.charset = cs;
            buffer.formatDirty = true;
            buffer.setModified(true);
            bufferStateChanged();
        }
    }

    /** Reads the file again in another encoding. */
    public void reopenWithCharset(Charset cs) {
        File file = buffer.file;
        if (file == null || cs == null) {
            return;
        }
        final String text;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            text = new String(bytes, cs).replace("\r\n", "\n").replace('\r', '\n');
        } catch (IOException e) {
            AppLogger.error("Failed to reopen " + file.getName() + ": " + e.getMessage());
            return;
        }
        buffer.keepOtherViews(this, () -> {
            buffer.beginLoading();
            try {
                editorArea.setText(text);
                editorArea.setCaretPosition(0);
                buffer.charset = cs;
                buffer.savedText = text;
                buffer.formatDirty = false;
            } finally {
                buffer.endLoading(true);
            }
        });
        for (CodeTab v : buffer.views()) {
            v.insight.setBaseline(text);
        }
        buffer.setModified(false);
        bufferStateChanged();
    }

    public AutoIndenter getIndenter() {
        return indenter;
    }

    /** Re-indents with tabs or spaces of a given width, in every view of the file. */
    public void setIndentation(boolean tabs, int size, boolean convert) {
        for (CodeTab v : buffer.views()) {
            v.applyIndentation(tabs, size);
        }
        if (convert) {
            EditorActions.convertIndentation(editorArea, tabs, size);
        }
        for (CodeTab v : buffer.views()) {
            v.bufferStateChanged();
        }
    }

    // Getters & Setters
    public boolean isModified() { return buffer.modified; }
    public File getFile() { return buffer.file; }

    /** Names the buffer's file (Save As, or a file loaded into an empty tab); every view follows. */
    public void setFile(File file) {
        buffer.file = file;
        for (CodeTab v : buffer.views()) {
            v.applyLanguage(file);
            v.insight.setFile(file);
            v.autoDetectModeFromFile();
            v.refreshChrome();
            v.bufferStateChanged();
        }
    }
    public EditorMode getCurrentMode() { return currentMode; }
    public void setSyncScroll(boolean enabled) { this.syncScroll = enabled; }
    public UndoManager getUndoManager() { return undoManager; }
    public CodeTextPane getEditorArea() { return editorArea; }
    public JScrollPane getScrollPane() { return editorScrollPane; }
    public void loadFileFromExternal(File file) { buffer.file = file; loadFile(file); }
    public boolean isRunnable() { return LanguageSpec.runCommandFor(language, buffer.file) != null; }
}

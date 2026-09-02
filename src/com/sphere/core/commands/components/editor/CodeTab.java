package com.sphere.components.editor;

import javax.swing.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import javax.swing.undo.UndoManager;
import javax.swing.event.UndoableEditListener;
import javax.swing.event.DocumentListener;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sphere.utils.AppLogger;
import com.sphere.fonts.FontLoader;

/**
 * One editor tab: a styled code surface with syntax colouring, a gutter carrying
 * breakpoints and diagnostics, completion, and the markdown or LaTeX preview.
 */
public class CodeTab extends JPanel {

    public enum EditorMode { PLAIN_TEXT, MARKDOWN, LATEX }

    private EditorMode currentMode = EditorMode.PLAIN_TEXT;

    private final CodeTextPane editorArea;
    private final JScrollPane editorScrollPane;
    private final EditorGutter gutter;
    private final JSplitPane splitPane;
    private final JEditorPane previewArea;
    private final JScrollPane previewScrollPane;

    private final UndoManager undoManager = new UndoManager();
    private final UndoableEditListener undoListener;

    private final BreakpointModel breakpoints = new BreakpointModel();
    private final SyntaxHighlighter highlighter;
    private final AutoIndenter indenter;
    private final CompletionPopup completion;

    private LanguageSpec language = LanguageSpec.NONE;
    private Charset charset = StandardCharsets.UTF_8;

    private File currentFile;
    private boolean isModified = false;
    private boolean isLoading = false;
    private boolean syncScroll = true;

    private final Timer previewDebounceTimer;

    public CodeTab(File file) {
        this.currentFile = file;
        setLayout(new BorderLayout());
        setBackground(EditorTheme.background());

        /* --- 1. Code surface --- */
        editorArea = new CodeTextPane();
        editorArea.setFont(FontLoader.getTerminalFont(Font.PLAIN, 13));
        editorArea.setTabWidth(4);
        ToolTipManager.sharedInstance().registerComponent(editorArea);

        /* --- 2. Undo/Redo --- */
        undoListener = e -> undoManager.addEdit(e.getEdit());
        editorArea.getDocument().addUndoableEditListener(undoListener);

        /* --- 3. Language services --- */
        highlighter = new SyntaxHighlighter(editorArea, language);
        indenter = new AutoIndenter(editorArea, language, 4);
        completion = new CompletionPopup(editorArea, new CompletionProvider.Buffer(language));
        new BracketMatcher(editorArea);

        /* --- 4. Preview debounce --- */
        previewDebounceTimer = new Timer(300, e -> updatePreview());
        previewDebounceTimer.setRepeats(false);

        /* --- 5. Modification tracking --- */
        editorArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { triggerModification(); }
            public void removeUpdate(DocumentEvent e) { triggerModification(); }
            public void changedUpdate(DocumentEvent e) { /* attribute-only */ }
        });

        /* --- 6. Scroll panes and gutter --- */
        editorScrollPane = new JScrollPane(editorArea);
        editorScrollPane.getViewport().setBackground(EditorTheme.background());
        editorScrollPane.setBorder(BorderFactory.createEmptyBorder());
        editorScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        gutter = new EditorGutter(editorArea, breakpoints);
        editorScrollPane.setRowHeaderView(gutter);

        // Blit scrolling copies pixels instead of repainting them, so anything left
        // in the row header from an earlier layout survived there. Both viewports
        // repaint in full, which costs nothing at this size and leaves no residue.
        editorScrollPane.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        if (editorScrollPane.getRowHeader() != null) {
            editorScrollPane.getRowHeader().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
            editorScrollPane.getRowHeader().setOpaque(true);
            editorScrollPane.getRowHeader().setBackground(EditorTheme.gutterBackground());
        }

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
        previewScrollPane.setBorder(BorderFactory.createEmptyBorder());
        previewScrollPane.setVisible(false);

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorScrollPane, previewScrollPane);
        splitPane.setResizeWeight(0.7);
        splitPane.setDividerSize(0);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());

        add(splitPane, BorderLayout.CENTER);

        setupScrollSynchronization();

        if (file != null) {
            loadFile(file);
        }
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void triggerModification() {
        if (!isLoading) {
            this.isModified = true;
            if (currentMode != EditorMode.PLAIN_TEXT) {
                previewDebounceTimer.restart();
            }
        }
    }

    private void setupScrollSynchronization() {
        editorScrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!syncScroll || currentMode == EditorMode.PLAIN_TEXT || isLoading) return;

            JScrollBar editorBar = editorScrollPane.getVerticalScrollBar();
            JScrollBar previewBar = previewScrollPane.getVerticalScrollBar();

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
                    + "<h1>LaTeX Mode</h1><p>" + text.replace("\n", "<br>") + "</p></body></html>";
        } else {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            previewArea.putClientProperty("AsynchronousLoadPriority", Integer.valueOf(-1));
            HTMLDocument doc = (HTMLDocument) previewArea.getEditorKit().createDefaultDocument();
            previewArea.setDocument(doc);
            previewArea.setText(renderedHtml);
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
                splitPane.setDividerLocation(0.7);
                updatePreview();
            });
        }
        revalidate();
        repaint();
    }

    // ---- Language ----------------------------------------------------------

    /** Chooses the language from the file name and rewires the services to it. */
    private void applyLanguage(File file) {
        language = LanguageSpec.forFile(file);
        highlighter.setLanguage(language);
        indenter.setLanguage(language);
        completion.setProvider(new CompletionProvider.Buffer(language));
    }

    public LanguageSpec getLanguage() {
        return language;
    }

    /** Rebuilds colours after a theme switch. */
    public void refreshTheme() {
        setBackground(EditorTheme.background());
        editorArea.applyTheme();
        gutter.applyTheme();
        editorScrollPane.getViewport().setBackground(EditorTheme.background());
        highlighter.refreshTheme();
        repaint();
    }

    // ---- Diagnostics and debugging ----------------------------------------

    public void setDiagnostics(List<EditorDiagnostic> diagnostics) {
        editorArea.setDiagnostics(diagnostics);
        gutter.setDiagnostics(diagnostics);
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
        try {
            javax.swing.text.Element root = editorArea.getDocument().getDefaultRootElement();
            if (line < 1 || line > root.getElementCount()) {
                return;
            }
            int offset = root.getElement(line - 1).getStartOffset();
            editorArea.setCaretPosition(offset);
            java.awt.geom.Rectangle2D r = editorArea.modelToView2D(offset);
            if (r != null) {
                editorArea.scrollRectToVisible(r.getBounds());
            }
        } catch (Exception ignored) {
            // the buffer changed under the requested line
        }
    }

    // ---- File --------------------------------------------------------------

    private void loadFile(File file) {
        isLoading = true;
        previewDebounceTimer.stop();
        try {
            editorArea.getDocument().removeUndoableEditListener(undoListener);

            EditorFileIO.Loaded loaded = EditorFileIO.read(file);
            charset = loaded.charset;
            editorArea.setText(loaded.text);
            editorArea.setCaretPosition(0);
            editorArea.setTabWidth(4);

            if (loaded.fellBack) {
                AppLogger.warn("Not valid UTF-8, opened as ISO-8859-1: " + file.getName());
            }

            undoManager.discardAllEdits();
            isModified = false;
            applyLanguage(file);
            autoDetectModeFromFile();
            highlighter.rehighlightAll();
            updatePreview();

        } catch (EditorFileIO.TooLargeException tooBig) {
            AppLogger.error(tooBig.getMessage());
        } catch (IOException e) {
            AppLogger.error("Failed to load file: " + e.getMessage());
        } finally {
            editorArea.getDocument().addUndoableEditListener(undoListener);
            isLoading = false;
        }
    }

    public void saveFile() {
        if (currentFile == null) return;
        try {
            EditorFileIO.write(currentFile, editorArea.getText(), charset);
            isModified = false;
        } catch (IOException e) {
            AppLogger.error("Failed to save: " + e.getMessage());
        }
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
        if (undoManager.canUndo()) undoManager.undo();
    }

    public void setLineNumbersVisible(boolean visible) {
        editorScrollPane.setRowHeaderView(visible ? gutter : null);
        editorScrollPane.revalidate();
        editorScrollPane.repaint();
    }

    // Getters & Setters
    public boolean isModified() { return isModified; }
    public File getFile() { return currentFile; }
    public void setFile(File file) { this.currentFile = file; applyLanguage(file); autoDetectModeFromFile(); }
    public EditorMode getCurrentMode() { return currentMode; }
    public void setSyncScroll(boolean enabled) { this.syncScroll = enabled; }
    public UndoManager getUndoManager() { return undoManager; }
    public CodeTextPane getEditorArea() { return editorArea; }
    public void loadFileFromExternal(File file) { this.currentFile = file; loadFile(file); }
}

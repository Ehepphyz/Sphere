package com.sphere.components.qeditorincludes;

import javax.swing.*;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import javax.swing.undo.UndoManager;
import javax.swing.event.UndoableEditListener;
import javax.swing.event.DocumentListener;
import javax.swing.event.DocumentEvent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.sphere.components.TextLineNumber;
import com.sphere.utils.AppLogger;
import com.sphere.fonts.FontLoader;

/**
 * Encapsulates an individual editor tab, including the text area,
 * preview pane, undo management, line numbers, scroll sync, and file state.
 */
public class CodeTab extends JPanel {

    public enum EditorMode { PLAIN_TEXT, MARKDOWN, LATEX }

    private EditorMode currentMode = EditorMode.PLAIN_TEXT;

    private final JTextArea editorArea;
    private final JScrollPane editorScrollPane;
    private final TextLineNumber lineNumbers;
    private final JSplitPane splitPane;
    private final JEditorPane previewArea;
    private final JScrollPane previewScrollPane;

    private final UndoManager undoManager = new UndoManager();
    private final UndoableEditListener undoListener;

    private File currentFile;
    private boolean isModified = false;
    private boolean isLoading = false;
    private boolean syncScroll = true;

    // Anti-lag debouncer
    private final Timer previewDebounceTimer;

    public CodeTab(File file) {
        this.currentFile = file;
        setLayout(new BorderLayout());
        setBackground(new Color(30, 30, 30));

        /* --- 1. Edit Handler --- */
        editorArea = new JTextArea();
        editorArea.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        editorArea.setTabSize(4);
        editorArea.setLineWrap(false);
        
        // DYNAMIC ALIGNMENT: Zero out initial text margins to match plain text layouts perfectly
        editorArea.setMargin(new Insets(0, 0, 0, 0));

        /* --- 2. Undo/Redo Handler --- */
        undoListener = e -> undoManager.addEdit(e.getEdit());
        editorArea.getDocument().addUndoableEditListener(undoListener);

        /* --- 3. Auto-Indent with KeyBindings --- */
        setupAutoIndentAction();

        /* --- 4. Preview Timer Debounce (300 ms) --- */
        previewDebounceTimer = new Timer(300, e -> updatePreview());
        previewDebounceTimer.setRepeats(false);

        /* --- 5. Modifications --- */
        editorArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { triggerModification(); }
            public void removeUpdate(DocumentEvent e) { triggerModification(); }
            public void changedUpdate(DocumentEvent e) { triggerModification(); }
        });

        /* --- 6. ScrollPanes & Graph Components --- */
        editorScrollPane = new JScrollPane(editorArea);
        editorScrollPane.getViewport().setBackground(new Color(30, 30, 30));
        editorScrollPane.setBorder(BorderFactory.createEmptyBorder());

        lineNumbers = new TextLineNumber(editorArea);
        lineNumbers.setForeground(new Color(120, 120, 120));
        lineNumbers.setBackground(new Color(45, 45, 45));
        editorScrollPane.setRowHeaderView(lineNumbers);

        previewArea = new JEditorPane();
        previewArea.setEditable(false);
        
        // ENHANCED LOOK AND FEEL DETACHMENT: Build an isolated stylesheet to control padding at pixel 0
        HTMLEditorKit htmlKit = new HTMLEditorKit();
        StyleSheet customStyle = new StyleSheet();
        customStyle.addRule("body { margin: 0px; padding: 0px; background-color: #303030; }");
        customStyle.addRule("p, div, h1, h2 { margin: 0px; padding: 0px; }");
        htmlKit.setStyleSheet(customStyle);
        
        previewArea.setEditorKit(htmlKit);
        previewArea.setContentType("text/html");
        previewArea.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        previewArea.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        previewArea.setBorder(BorderFactory.createEmptyBorder());
        previewArea.setMargin(new Insets(0, 0, 0, 0));

        previewScrollPane = new JScrollPane(previewArea);
        previewScrollPane.setBorder(BorderFactory.createEmptyBorder());
        previewScrollPane.setVisible(false);

        splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorScrollPane, previewScrollPane);
        splitPane.setResizeWeight(0.7);
        splitPane.setDividerSize(0);
        splitPane.setContinuousLayout(true);
        splitPane.setBorder(BorderFactory.createEmptyBorder());

        add(splitPane, BorderLayout.CENTER);

        /* --- 7. Scroll Sync --- */
        setupScrollSynchronization();

        /* --- 8. Init Load --- */
        if (file != null) {
            loadFile(file);
        }
    }

    private void triggerModification() {
        if (!isLoading) {
            this.isModified = true;
            if (currentMode != EditorMode.PLAIN_TEXT) {
                previewDebounceTimer.restart();
            }
        }
    }

    private void setupAutoIndentAction() {
        InputMap inputMap = editorArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap actionMap = editorArea.getActionMap();
        
        Object enterKeyStroke = inputMap.get(KeyStroke.getKeyStroke("ENTER"));
        final Action defaultEnterAction = actionMap.get(enterKeyStroke);

        actionMap.put(enterKeyStroke, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // First execute the standard line break action
                if (defaultEnterAction != null) {
                    defaultEnterAction.actionPerformed(e);
                }
                autoIndent();
            }
        });
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

    private void autoIndent() {
        try {
            int pos = editorArea.getCaretPosition();
            int line = editorArea.getLineOfOffset(pos);
            if (line > 0) {
                int start = editorArea.getLineStartOffset(line - 1);
                int end = editorArea.getLineEndOffset(line - 1);
                String prev = editorArea.getText(start, end - start);

                StringBuilder indent = new StringBuilder();
                for (char c : prev.toCharArray()) {
                    if (c == ' ' || c == '\t') indent.append(c);
                    else break;
                }
                if (indent.length() > 0) {
                    editorArea.insert(indent.toString(), pos);
                }
            }
        } catch (Exception ignored) {}
    }

    private void updatePreview() {
        if (currentMode == EditorMode.PLAIN_TEXT) return;

        String text = editorArea.getText();
        String renderedHtml;

        if (currentMode == EditorMode.MARKDOWN) {
            renderedHtml = MarkdownRenderer.render(text);
        } else if (currentMode == EditorMode.LATEX) {
            renderedHtml = "<html><body style='background-color:#1e1e1e;color:#d4d4d4;margin:0;padding:0;'>" +
                    "<h1>LaTeX Mode</h1><p>" +
                    text.replace("\n", "<br>") +
                    "</p></body></html>";
        } else {
            return;
        }

        // UI safe update
        SwingUtilities.invokeLater(() -> {
            // FORCE RENDERING LAYOUT: Bypasses native asynchronous paragraph injections
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

        // BALANCING ADJUSTMENT: Safely pad down text area to force alignment match with hidden LookAndFeel headers
        if (showPreview) {
            editorArea.setMargin(new Insets(10, 0, 0, 0));
            SwingUtilities.invokeLater(() -> {
                splitPane.setDividerLocation(0.7);
                updatePreview();
            });
        } else {
            editorArea.setMargin(new Insets(0, 0, 0, 0));
        }
        revalidate();
        repaint();
    }

    private void loadFile(File file) {
        isLoading = true;
        previewDebounceTimer.stop();
        try {
            editorArea.getDocument().removeUndoableEditListener(undoListener);

            // UTF_8 Reader
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            editorArea.setText(content);
            editorArea.setCaretPosition(0);

            undoManager.discardAllEdits();
            isModified = false;
            autoDetectModeFromFile();
            updatePreview();

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
            Files.writeString(currentFile.toPath(), editorArea.getText(), StandardCharsets.UTF_8);
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
        editorScrollPane.setRowHeaderView(visible ? lineNumbers : null);
        editorScrollPane.revalidate();
        editorScrollPane.repaint();
    }

    // Getters & Setters
    public boolean isModified() { return isModified; }
    public File getFile() { return currentFile; }
    public void setFile(File file) { this.currentFile = file; autoDetectModeFromFile(); }
    public EditorMode getCurrentMode() { return currentMode; }
    public void setSyncScroll(boolean enabled) { this.syncScroll = enabled; }
    public UndoManager getUndoManager() { return undoManager; }
    public JTextArea getEditorArea() { return editorArea; }
    public void loadFileFromExternal(File file) { this.currentFile = file; loadFile(file); }
}
package com.sphere.components;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
import com.sphere.theme.ThemeManager;
import com.sphere.utils.IconManager;
import com.sphere.theme.ThemePalette;
import com.sphere.fonts.FontLoader;
import com.sphere.components.FindReplaceDialog;
import com.sphere.components.UndoRedoUtility;

// --- Icons Displayers ---
import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.event.HierarchyEvent;


/**
 * Self-contained, zero-dependency Markdown Editor for Sphere.
 * Uses native Java Regex parsing to maintain compilation portability.
 */
public class MdTexEditor extends JPanel {

    public final JTextPane editor;
    private final ThemePalette palette = ThemeManager.getCurrentPalette();
    private final JEditorPane preview;
    private final JLabel statusLabel;
    private final UndoManager undo;
    private final javax.swing.Timer debounceTimer;
    private File currentFile;
    private boolean syncScrollActive = true; // True by default
    private boolean isSyncing = false;       // Prevents infinite recursive loop feedback
    public boolean isModified = false;

    public MdTexEditor() {        
        super(new BorderLayout());

        // Initialize editor with Word Wrap disabled for coding comfort
        editor = new JTextPane() {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                Component parent = getParent();
                if (parent != null) {
                    return getUI().getPreferredSize(this).width <= parent.getSize().width;
                }
                return true;
            }
        };
        
        // Editor layout custom typography adjustments
        editor.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        editor.setMargin(new Insets(10, 15, 10, 15));
        editor.setBackground(new Color(0x1E1E1E));
        editor.setForeground(new Color(0xD4D4D4));
        editor.setCaretColor(new Color(0x569CD6));

        preview = new JEditorPane();
        preview.setContentType("text/html");
        preview.setEditable(false);

        // --- ENHANCED SCROLL INTERACTION BINDINGS ---
        // Explicitly instantiate individual viewports so we can attach adjustment tracking mechanics
        JScrollPane editorScrollPane = new JScrollPane(editor);
        JScrollPane previewScrollPane = new JScrollPane(preview);

        // Optional hook: If TextLineNumber class is present, uncomment the lines below
        // TextLineNumber lineNumberView = new TextLineNumber(editor);
        // editorScrollPane.setRowHeaderView(lineNumberView);

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                editorScrollPane,
                previewScrollPane
        );
        split.setResizeWeight(0.5);

        // Extract vertical scrollbars from both structural subcomponents
        JScrollBar editorBar = editorScrollPane.getVerticalScrollBar();
        JScrollBar previewBar = previewScrollPane.getVerticalScrollBar();

        // Bind Editor pane shifts over to update Preview scrolling ratios
        editorBar.addAdjustmentListener(e -> {
            if (!syncScrollActive || isSyncing) return;
            isSyncing = true;
            try {
                double extRatio = (double) e.getValue() / (editorBar.getMaximum() - editorBar.getVisibleAmount());
                int targetVal = (int) (extRatio * (previewBar.getMaximum() - previewBar.getVisibleAmount()));
                previewBar.setValue(targetVal);
            } catch (Exception ignored) {
            } finally {
                isSyncing = false;
            }
        });

        // Bind Preview pane interactions back to keep Editor viewports perfectly paired
        previewBar.addAdjustmentListener(e -> {
            if (!syncScrollActive || isSyncing) return;
            isSyncing = true;
            try {
                double extRatio = (double) e.getValue() / (previewBar.getMaximum() - previewBar.getVisibleAmount());
                int targetVal = (int) (extRatio * (editorBar.getMaximum() - editorBar.getVisibleAmount()));
                editorBar.setValue(targetVal);
            } catch (Exception ignored) {
            } finally {
                isSyncing = false;
            }
        });

        // Status metadata bar configuration
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        statusLabel = new JLabel("Lines: 0 | Words: 0 | Characters: 0");
        statusLabel.setFont(FontLoader.getTerminalFont(Font.PLAIN, 12));
        statusPanel.add(statusLabel, BorderLayout.WEST);

        undo = new UndoManager();
        editor.getDocument().addUndoableEditListener(undo);

        // 300ms Debounce compilation framework initialization
        debounceTimer = new javax.swing.Timer(300, e -> {
            updatePreview();
            updateMetadataMetrics();
        });
        debounceTimer.setRepeats(false);

        editor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { isModified = true; debounceTimer.restart(); }
            public void removeUpdate(DocumentEvent e) { isModified = true; debounceTimer.restart(); }
            public void changedUpdate(DocumentEvent e) { isModified = true; debounceTimer.restart(); }
        });

        setupKeyBindings();
        setupPopupMenu();
        setupActionMapShortcuts();

        this.add(createMenuBar(), BorderLayout.NORTH);
        this.add(split, BorderLayout.CENTER);
        this.add(statusPanel, BorderLayout.SOUTH);

        // Initialize display state configurations
        updateMetadataMetrics();
        UndoRedoUtility.setupUndoRedo(this.editor);

        Icon appIcon = IconManager.getIcon("cta_logo.png");

        addHierarchyListener(e -> {
            if (e.getID() == HierarchyEvent.HIERARCHY_CHANGED) {
                Window w = SwingUtilities.getWindowAncestor(this);
                if (w instanceof Frame f && appIcon instanceof ImageIcon img) {
                    f.setIconImage(img.getImage());
                }
            }
        });
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // 1. File Menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        fileMenu.setForeground(palette.getTextWhite());

        JMenuItem saveItem = new JMenuItem("Save");
        saveItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        saveItem.setForeground(palette.getTextWhite());

        JMenuItem saveAsItem = new JMenuItem("Save As...");
        saveAsItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        saveAsItem.setForeground(palette.getTextWhite());

        JMenuItem quitItem = new JMenuItem("Quit");
        quitItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        quitItem.setForeground(palette.getTextWhite());

        saveItem.addActionListener(e -> {
            if (currentFile != null) {
                saveFile(currentFile, editor.getText());
            } else {
                saveAs();
            }
        });

        saveAsItem.addActionListener(e -> saveAs());
        quitItem.addActionListener(e -> {
            if (checkUnsavedChanges()) {
                Window parentWindow = SwingUtilities.getWindowAncestor(this);
                if (parentWindow != null) {
                    parentWindow.dispose();
                }
            }
        });

        fileMenu.add(saveItem);
        fileMenu.add(saveAsItem);
        fileMenu.addSeparator();
        fileMenu.add(quitItem);


        // 2. Edit Menu
        JMenu editMenu = new JMenu("Edit");
        editMenu.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        editMenu.setForeground(palette.getTextWhite());

        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        copyItem.setForeground(palette.getTextWhite());

        JMenuItem pasteItem = new JMenuItem("Paste");
        pasteItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        pasteItem.setForeground(palette.getTextWhite());

        JMenuItem deleteItem = new JMenuItem("Delete");
        deleteItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        deleteItem.setForeground(palette.getTextWhite());

        JMenuItem undoItem = new JMenuItem("Undo");
        undoItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        undoItem.setForeground(palette.getTextWhite());

        JMenuItem redoItem = new JMenuItem("Redo");
        redoItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        redoItem.setForeground(palette.getTextWhite());

        JMenuItem findReplaceItem = new JMenuItem("Find & Replace");
        findReplaceItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        findReplaceItem.setForeground(palette.getTextWhite());
        
        
        // 3. Add Sync Scroll Toggle Option
        JCheckBoxMenuItem syncScrollItem = new JCheckBoxMenuItem("Synchronized Scrolling", true);
        syncScrollItem.setFont(FontLoader.getGlobalFont(Font.PLAIN, 12));
        syncScrollItem.setForeground(palette.getTextWhite());
        syncScrollItem.addActionListener(e -> syncScrollActive = syncScrollItem.isSelected());

        copyItem.addActionListener(e -> editor.copy());
        pasteItem.addActionListener(e -> editor.paste());
        deleteItem.addActionListener(e -> editor.replaceSelection(""));

        undoItem.addActionListener(e -> { if (undo.canUndo()) undo.undo(); });
        redoItem.addActionListener(e -> { if (undo.canRedo()) undo.redo(); });

        findReplaceItem.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK));
        findReplaceItem.addActionListener(e -> {
            // Retrieve the parent window frame container 
            Frame parentFrame = (Frame) SwingUtilities.getWindowAncestor(this);
            
            // Instanciate the dialog passing the JTextPane/JEditorPane reference
            FindReplaceDialog dialog = new FindReplaceDialog(parentFrame, this.editor);
            dialog.setVisible(true);
        });

        editMenu.add(copyItem);
        editMenu.add(pasteItem);
        editMenu.add(deleteItem);
        editMenu.addSeparator();
        editMenu.add(undoItem);
        editMenu.add(redoItem);
        editMenu.addSeparator();
        editMenu.add(syncScrollItem); // Inserted toggle option
        editMenu.add(findReplaceItem);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);

        return menuBar;
    }

    private boolean saveAs() {
        JFileChooser fc = new JFileChooser();
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            currentFile = fc.getSelectedFile();
            return saveFile(currentFile, editor.getText());
        }
        return false;
    }

    public void setCurrentFile(File file) {
        this.currentFile = file;
    }

    private boolean saveFile(File file, String content) {
        try {
            Files.writeString(file.toPath(), content);
            isModified = false;
            return true;
        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error saving file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /**
     * Checks for unsaved changes and prompts the user.
     * @return true if the window can proceed to close, false if aborted.
     */
    public boolean checkUnsavedChanges() {
        if (!isModified) {
            return true; // No changes to save
        }

        int option = JOptionPane.showConfirmDialog(
                this,
                "You have unsaved changes. Do you want to save them before leaving?",
                "Unsaved Changes",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );

        if (option == JOptionPane.YES_OPTION) {
            if (currentFile != null) {
                return saveFile(currentFile, editor.getText()); // Direct save to existing file path
            } else {
                return saveAs(); // Fallback prompt if file target is undefined
            }
        } else if (option == JOptionPane.NO_OPTION) {
            return true; // Discard changes and allow close
        }

        return false; // Cancel action completely
    }

    /**
     * NATIVE PARSER: Converts Markdown rules to clean HTML on the fly without dependencies.
     */
    private void updatePreview() {
        SwingUtilities.invokeLater(() -> {
            try {
                String text = editor.getText();
                String htmlOutput = parseMarkdownToHtml(text);

                Font termFont = FontLoader.getTerminalFont(Font.PLAIN, 12);
                String fontName = termFont.getFamily();

                preview.setFont(termFont);
                preview.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);

                String htmlTemplate = "<html><head><style>"
                        + "body { font-family: '" + fontName + "', sans-serif; padding: 12px; color: #D4D4D4; background-color: #1E1E1E; line-height: 1.4; }"
                        + "h1 { font-size: 140%; color: #007acc; border-bottom: 1px solid #333; padding-bottom: 3px; margin-top: 12px; }"
                        + "h2 { font-size: 120%; color: #569CD6; margin-top: 12px; }"
                        + "h3 { font-size: 110%; color: #4EC9B0; margin-top: 12px; }"
                        + "code { background-color: #2D2D2D; padding: 1px 3px; font-family: '" + fontName + "', monospace; font-size: 95%; border-radius: 3px; color: #CE9178; }"
                        + "pre { background-color: #2D2D2D; padding: 10px; border-left: 4px solid #007acc; font-family: '" + fontName + "', monospace; font-size: 95%; display: block; margin: 10px 0; color: #9CDCFE; }"
                        + "p { margin: 0 0 8px 0; }"
                        + "ul, ol { padding-left: 18px; margin: 0 0 8px 0; }"
                        + "blockquote { border-left: 3px solid #555; margin: 8px 0; padding-left: 12px; color: #888; font-style: italic; }"
                        + "</style>"
                        + "<script src=\"https://polyfill.io/v3/polyfill.min.js?features=es6\"></script>"
                        + "<script id=\"MathJax-script\" async src=\"https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js\"></script>"
                        + "</head><body>" 
                        + htmlOutput 
                        + "</body></html>";

                JScrollPane previewScrollPane = (JScrollPane) preview.getParent().getParent();
                JScrollBar previewBar = previewScrollPane.getVerticalScrollBar();
                final int currentScrollValue = previewBar.getValue();

                isSyncing = true; 

                preview.setText(htmlTemplate);
                preview.setCaretPosition(0);
                SwingUtilities.invokeLater(() -> {
                    previewBar.setValue(currentScrollValue);
                    isSyncing = false;
                });
                // -------------------------------------------------

            } catch (Exception e) {
                preview.setText("<html><body><pre>Error rendering markdown component layout</pre></body></html>");
            }
        });
    }

    /**
     * Extracts operational metadata layout values from the core workspace pane.
     */
    private void updateMetadataMetrics() {
        String text = editor.getText();
        if (text == null || text.isEmpty()) {
            statusLabel.setText("Lines: 0 | Words: 0 | Characters: 0");
            return;
        }

        int characters = text.length();
        int lines = text.split("\\R", -1).length;

        // Clean out spaces to evaluate structural tokens accurately
        String trimmedText = text.trim();
        int words = trimmedText.isEmpty() ? 0 : trimmedText.split("\\s+").length;

        statusLabel.setText(String.format("Lines: %d | Words: %d | Characters: %d", lines, words, characters));
    }

    /**
     * Native regex pipeline converter processing markdown blocks down to standard HTML.
     */
    private String parseMarkdownToHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";

        // Safe HTML characters basic pre-filtering
        String escaped = markdown.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String[] lines = escaped.split("\\R");
        StringBuilder sb = new StringBuilder();

        boolean inCodeBlock = false;
        boolean inList = false;

        for (String line : lines) {
            // 1. Normalize line: Replace non-breaking spaces (\u00A0) with normal spaces, then trim
            String normalizedLine = line.replace("\u00A0", " ").trim();

            // 2. Code Blocks Handling supporting tags like ```bash, ```java, or even trailing spaces
            if (normalizedLine.matches("^\\s*```.*")) {
                if (inCodeBlock) {
                    sb.append("</pre>");
                    inCodeBlock = false;
                } else {
                    sb.append("<pre>");
                    inCodeBlock = true;
                }
                continue; // Hard skip
            }

            if (inCodeBlock) {
                sb.append(line).append("\n");
                continue;
            }

            // 3. Unordered Bullet Lists Handling (- or * or +)
            if (normalizedLine.matches("^[-*+]\\s+.*")) {
                if (!inList) {
                    sb.append("<ul>");
                    inList = true;
                }
                String content = normalizedLine.replaceFirst("^[-*+]\\s+", "");
                sb.append("<li>").append(applyInlineFormatting(content)).append("</li>");
                continue;
            } else if (inList && !normalizedLine.startsWith("-") && !normalizedLine.startsWith("*") && !normalizedLine.startsWith("+")) {
                sb.append("</ul>");
                inList = false;
            }

            // 4. Headers Processing (#)
            if (line.startsWith("# ")) {
                sb.append("<h1>").append(applyInlineFormatting(line.substring(2))).append("</h1>");
            } else if (line.startsWith("## ")) {
                sb.append("<h2>").append(applyInlineFormatting(line.substring(3))).append("</h2>");
            } else if (line.startsWith("### ")) {
                sb.append("<h3>").append(applyInlineFormatting(line.substring(4))).append("</h3>");
            } else if (line.startsWith("> ")) {
                sb.append("<blockquote>").append(applyInlineFormatting(line.substring(2))).append("</blockquote>");
            } else if (normalizedLine.isEmpty()) {
                sb.append("<br/>");
            } else {
                // Regular Paragraph line matching
                sb.append("<p>").append(applyInlineFormatting(line)).append("</p>");
            }
        }

        if (inList) sb.append("</ul>");
        if (inCodeBlock) sb.append("</pre>");

        return sb.toString();
    }

    /**
     * Regex inline rules substitution engine for Bold, Italics and Backticks.
     */
    private String applyInlineFormatting(String text) {
        // Bold (**text**)
        text = text.replaceAll("\\*\\*(.*?)\\*\\*", "<strong>$1</strong>");
        // Italics (*text*)
        text = text.replaceAll("\\*(.*?)\\*", "<em>$1</em>");
        // Inline backtick code (`code`)
        text = text.replaceAll("`(.*?)`", "<code>$1</code>");
        return text;
    }

    private void setupActionMapShortcuts() {
        InputMap inputMapFocused = editor.getInputMap(JComponent.WHEN_FOCUSED);
        InputMap inputMapWindow = editor.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = editor.getActionMap();

        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        inputMapFocused.put(KeyStroke.getKeyStroke(KeyEvent.VK_B, menuMask), "wrapBold");
        actionMap.put("wrapBold", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { wrap("**", "**"); }
        });

        inputMapFocused.put(KeyStroke.getKeyStroke(KeyEvent.VK_I, menuMask), "wrapItalic");
        actionMap.put("wrapItalic", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { wrap("*", "*"); }
        });

        // Mapping mapping for Save keyboard shortcuts (Ctrl+S / Cmd+S)
        KeyStroke saveKey = KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask);
        inputMapFocused.put(saveKey, "SaveWorkspaceFile");
        inputMapWindow.put(saveKey, "SaveWorkspaceFile");
        actionMap.put("SaveWorkspaceFile", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (currentFile != null) {
                    saveFile(currentFile, editor.getText());
                } else {
                    saveAs();
                }
            }
        });
    }

    private void setupKeyBindings() {
        editor.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_TAB) {
                    e.consume();
                    if (e.isShiftDown()) {
                        unindentSelection(editor);
                    } else {
                        indentSelection(editor);
                    }
                }

                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    try {
                        int pos = editor.getCaretPosition();
                        int lineStart = Utilities.getRowStart(editor, pos);
                        String line = editor.getText(lineStart, pos - lineStart);

                        if (isInsideCodeBlock()) {
                            SwingUtilities.invokeLater(() -> editor.replaceSelection("\n    "));
                            e.consume();
                            return;
                        }

                        String indent = line.replaceAll("^(\\s*).*", "$1");
                        String trimmed = line.trim();

                        // Clear current line prefix if enter is triggered on an empty bullet/number node structural item
                        if (trimmed.equals("-") || trimmed.equals("*") || trimmed.equals("+") || trimmed.matches("^\\d+\\.$")) {
                            editor.getDocument().remove(lineStart, pos - lineStart);
                            editor.replaceSelection("\n");
                            e.consume();
                            return;
                        }

                        // Seamless structured list type auto-generation
                        if (trimmed.matches("[-+*] .*")) {
                            SwingUtilities.invokeLater(() -> editor.replaceSelection("\n" + indent + "- "));
                            e.consume();
                        } else if (trimmed.matches("\\d+\\. .*")) {
                            int n = Integer.parseInt(trimmed.split("\\.")[0]) + 1;
                            SwingUtilities.invokeLater(() -> editor.replaceSelection("\n" + indent + n + ". "));
                            e.consume();
                        }

                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() == '`') {
                    SwingUtilities.invokeLater(() -> {
                        int pos = editor.getCaretPosition();
                        String text = editor.getText();
                        if (pos >= 3 && text.substring(pos - 3, pos).equals("```")) {
                            editor.replaceSelection("\n\n```");
                            editor.setCaretPosition(editor.getCaretPosition() - 3);
                        }
                    });
                }
            }
        });
    }

    private void setupPopupMenu() {
        JPopupMenu popup = new JPopupMenu();

        popup.add(menuItem("Bold (**...**)", () -> wrap("**", "**")));
        popup.add(menuItem("Italic (*...*)", () -> wrap("*", "*")));
        popup.add(menuItem("Inline code (`...`)", () -> wrap("`", "`")));
        popup.add(menuItem("Code block (```)", this::blockCode));
        popup.addSeparator();
        popup.add(menuItem("Title (H1)", () -> prefix("# ")));
        popup.add(menuItem("Subtitle (H2)", () -> prefix("## ")));
        popup.addSeparator();
        popup.add(menuItem("Bullet list (-)", () -> prefixLines("- ")));
        popup.add(menuItem("Numbered list (1.)", this::numberedList));

        editor.setComponentPopupMenu(popup);
    }

    private JMenuItem menuItem(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }

    private void wrap(String left, String right) {
        String sel = getSmartSelection();
        if (sel == null || sel.isEmpty()) return;

        int start = editor.getSelectionStart();
        editor.replaceSelection(left + sel + right);
        editor.select(start, start + left.length() + sel.length() + right.length());
    }

    private void blockCode() {
        String sel = getSmartSelection();
        if (sel == null || sel.isEmpty()) return;

        String block = "```\n" + sel + "\n```";
        int start = editor.getSelectionStart();
        editor.replaceSelection(block);
        editor.select(start, start + block.length());
    }

    private void prefix(String prefix) {
        String sel = getSmartSelection();
        if (sel == null || sel.isEmpty()) return;

        int start = editor.getSelectionStart();
        editor.replaceSelection(prefix + sel);
        editor.select(start, start + prefix.length() + sel.length());
    }

    private void prefixLines(String prefix) {
        String sel = getSmartSelection();
        if (sel == null || sel.isBlank()) return;

        String[] lines = sel.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(prefix).append(line).append("\n");
        }
        int start = editor.getSelectionStart();
        editor.replaceSelection(sb.toString());
        editor.select(start, start + sb.length());
    }

    private void numberedList() {
        String sel = getSmartSelection();
        if (sel == null || sel.isBlank()) return;

        String[] lines = sel.split("\\R");
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (String line : lines) {
            sb.append(i++).append(". ").append(line).append("\n");
        }
        int start = editor.getSelectionStart();
        editor.replaceSelection(sb.toString());
        editor.select(start, start + sb.length());
    }

    private String getSmartSelection() {
        String sel = editor.getSelectedText();
        if (sel != null && !sel.isBlank()) return sel;

        try {
            int pos = editor.getCaretPosition();
            String text = editor.getText();
            if (text.isEmpty()) return "";

            int start = pos;
            int end = pos;

            while (start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) start--;
            while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) end++;

            if (start != end) {
                editor.select(start, end);
                return editor.getSelectedText();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private boolean isInsideCodeBlock() {
        String text = editor.getText();
        int pos = editor.getCaretPosition();

        int count = 0;
        int idx = text.indexOf("```");
        while (idx != -1 && idx < pos) {
            count++;
            idx = text.indexOf("```", idx + 3);
        }
        return (count % 2) == 1;
    }

    private void indentSelection(JTextPane editor) {
        String sel = editor.getSelectedText();
        if (sel == null || sel.isBlank()) {
            editor.replaceSelection("    ");
            return;
        }

        String[] lines = sel.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("    ").append(line).append("\n");
        }
        editor.replaceSelection(sb.toString());
    }

    private void unindentSelection(JTextPane editor) {
        String sel = editor.getSelectedText();
        if (sel == null || sel.isBlank()) return;

        String[] lines = sel.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("    ")) {
                sb.append(line.substring(4));
            } else {
                sb.append(line);
            }
            sb.append("\n");
        }
        editor.replaceSelection(sb.toString());
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Sphere - Zero-Dependency Markdown Sandbox");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            MdTexEditor editorPanel = new MdTexEditor();
            
            Dimension layoutSize = new Dimension(1600, 850);
            editorPanel.setPreferredSize(layoutSize);
            editorPanel.setMinimumSize(layoutSize);
            
            frame.add(editorPanel);

            frame.setSize(layoutSize);
            frame.setPreferredSize(layoutSize);
            frame.setMinimumSize(layoutSize);
            
            frame.setLocationRelativeTo(null);

            String testingTemplate = "# Native Markdown Validation\n\n"
                    + "This is native text conversion parsing. **This is bold text** and *this is italics*.\n\n"
                    + "```bash\n"
                    + "echo \"Hello from Sphere workspace terminal script\"\n"
                    + "ls -la\n"
                    + "```\n\n"
                    + "## Mathematical LaTeX Verification\n"
                    + "Inline equations work perfectly: $E = mc^2$.\n"
                    + "Block display formats:\n"
                    + "$$\\psi(x, t) = A e^{i(kx - \\omega t)}$$\n";

            editorPanel.editor.setText(testingTemplate);
            frame.setVisible(true);
        });
    }
    
}

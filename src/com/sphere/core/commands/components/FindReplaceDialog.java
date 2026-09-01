package com.sphere.components;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.ArrayList;

public class FindReplaceDialog extends JDialog {
    private JTextField findField = new JTextField(15);
    private JLabel countLabel = new JLabel("0/0");
    private JTextComponent editor; // Changed from JTextArea to JTextComponent for universal support
    
    // Painter for all matches (Light Blue)
    private final Highlighter.HighlightPainter painter = 
        new DefaultHighlighter.DefaultHighlightPainter(new Color(153, 204, 255));
    // Painter for the currently selected focus match (Orange)
    private final Highlighter.HighlightPainter focusPainter = 
        new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 165, 0, 200));
    private JCheckBox caseCheck = new JCheckBox("Aa"); 
    private JCheckBox wholeWordCheck = new JCheckBox("[ab]");

    private JPanel replacePanel = new JPanel();
    private JTextField replaceField = new JTextField(15);
    private JCheckBox replaceAllCheck = new JCheckBox("All");

    public FindReplaceDialog(Frame parent, JTextComponent editor) { // Accepts any JTextComponent
        super(parent, "Find and Replace", false);
        this.editor = editor;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // Layout configuration
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.anchor = GridBagConstraints.CENTER;

        // 1. Chevron Toggle Button
        JButton toggleBtn = new JButton("\u276F");
        toggleBtn.setFont(new Font("Monospaced", Font.BOLD, 18));
        toggleBtn.setOpaque(true);
        toggleBtn.setForeground(Color.BLACK);
        toggleBtn.setFocusPainted(false);
        toggleBtn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.WHITE, 1),
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        ));
        toggleBtn.setPreferredSize(new Dimension(30, 25));

        // Action to toggle the replace panel
        toggleBtn.addActionListener(e -> {
            boolean visible = !replacePanel.isVisible();
            replacePanel.setVisible(visible);
            toggleBtn.setText(visible ? "\u2771" : "\u276F"); // Switch to downward chevron
            pack();
        });

        gbc.gridx = 0; gbc.gridy = 0;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        add(toggleBtn, gbc);

        // 2. Search Field
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        findField.setPreferredSize(new Dimension(150, 25));
        add(findField, gbc);

        // 3. Count Label
        gbc.gridx = 2;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        countLabel.setPreferredSize(new Dimension(60, 25));
        countLabel.setHorizontalAlignment(SwingConstants.CENTER);
        add(countLabel, gbc);

        // 4. Options
        gbc.gridx = 3;
        caseCheck.setToolTipText("Case Insensitive (Ignore case)");
        caseCheck.addActionListener(e -> performFind(false));
        add(caseCheck, gbc);

        gbc.gridx = 4;
        wholeWordCheck.setToolTipText("Whole Word Only");
        wholeWordCheck.addActionListener(e -> performFind(false));
        add(wholeWordCheck, gbc);

        // 5. Navigation Buttons
        JButton prevButton = new JButton("\u25B2");
        JButton nextButton = new JButton("\u25BC");
        prevButton.addActionListener(e -> navigate(-1));
        nextButton.addActionListener(e -> navigate(1));

        gbc.gridx = 5; add(prevButton, gbc);
        gbc.gridx = 6; add(nextButton, gbc);

        // 6. Replace Panel (Hidden by default)
        replacePanel.setLayout(new FlowLayout(FlowLayout.LEFT));
        replacePanel.setVisible(false);

        replacePanel.add(new JLabel("Replace:"));
        replaceField.setPreferredSize(new Dimension(150, 25));
        replacePanel.add(replaceField);

        JButton replaceAllBtn = new JButton("Replace All");
        replaceAllBtn.setFocusPainted(false);
        replaceAllBtn.addActionListener(e -> performReplaceAll());
        replacePanel.add(replaceAllBtn);

        // GridBag constraints for the panel
        gbc.gridx = 0; 
        gbc.gridy = 1; 
        gbc.gridwidth = 7; 
        gbc.fill = GridBagConstraints.HORIZONTAL;
        add(replacePanel, gbc);

        // Document listener
        findField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { performFind(true); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { performFind(true); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { performFind(true); }
        });

        pack();
        setMinimumSize(new Dimension(450, 25));
        setLocationRelativeTo(parent);
    }

    private void performFind(boolean jumpToFirst) {
        String query = findField.getText();
        
        if (query.isEmpty()) {
            editor.getHighlighter().removeAllHighlights();
            countLabel.setText("0/0");
            return;
        }

        ArrayList<Integer> matches = getMatchList(query);
        
        int startPos = -1;
        if (jumpToFirst && !matches.isEmpty()) {
            startPos = matches.get(0);
        }
        
        countLabel.setText((matches.isEmpty() ? "0" : "1") + "/" + matches.size());
        refreshHighlights(matches, query.length(), startPos);
    }

    private void refreshHighlights(ArrayList<Integer> matches, int len, int focusIndex) {
        editor.getHighlighter().removeAllHighlights();
        
        for (int index : matches) {
            try {
                if (index == focusIndex) {
                    editor.getHighlighter().addHighlight(index, index + len, focusPainter);
                } else {
                    editor.getHighlighter().addHighlight(index, index + len, painter);
                }
            } catch (BadLocationException e) { e.printStackTrace(); }
        }
        
        if (focusIndex != -1) {
            int current = matches.indexOf(focusIndex) + 1;
            countLabel.setText(current + "/" + matches.size());
            editor.setCaretPosition(focusIndex + len);
        } else {
            countLabel.setText(matches.isEmpty() ? "0/0" : "0/" + matches.size());
        }
    }

    private ArrayList<Integer> getMatchList(String query) {
        ArrayList<Integer> matches = new ArrayList<>();
        String content = "";
        
        try {
            // --- JEDITORPANE COMPATIBILITY FIX ---
            // Extract ONLY the visible plain text content, stripping away hidden HTML/CSS source tags
            Document doc = editor.getDocument();
            content = doc.getText(0, doc.getLength());
            // --------------------------------------
        } catch (BadLocationException e) {
            content = editor.getText(); // Fallback to standard text extraction if document indexing fails
        }
        
        // Search evaluation constraints options
        boolean ignoreCase = caseCheck.isSelected();
        boolean wholeWord = wholeWordCheck.isSelected();
        
        String textToSearch = ignoreCase ? content.toLowerCase() : content;
        String queryToSearch = ignoreCase ? query.toLowerCase() : query;
        
        int index = textToSearch.indexOf(queryToSearch);
        while (index >= 0) {
            boolean isWholeWord = true;
            if (wholeWord) {
                boolean startOk = (index == 0 || !Character.isLetterOrDigit(textToSearch.charAt(index - 1)));
                boolean endOk = (index + queryToSearch.length() == textToSearch.length() || 
                                !Character.isLetterOrDigit(textToSearch.charAt(index + queryToSearch.length())));
                isWholeWord = (startOk && endOk);
            }

            if (isWholeWord) {
                matches.add(index);
            }
            index = textToSearch.indexOf(queryToSearch, index + queryToSearch.length());
        }
        return matches;
    }

    private void navigate(int direction) {
        String query = findField.getText();
        if (query.isEmpty()) return;

        ArrayList<Integer> matches = getMatchList(query);
        if (matches.isEmpty()) return;

        int caret = editor.getCaretPosition();
        int targetIndex = -1;

        if (direction > 0) {
            for (int match : matches) {
                if (match >= caret) { targetIndex = match; break; }
            }
            if (targetIndex == -1) targetIndex = matches.get(0);
        } else {
            for (int i = matches.size() - 1; i >= 0; i--) {
                if (matches.get(i) < caret - query.length()) { targetIndex = matches.get(i); break; }
            }
            if (targetIndex == -1) targetIndex = matches.get(matches.size() - 1);
        }

        refreshHighlights(matches, query.length(), targetIndex);
        editor.requestFocusInWindow();
        try {
            editor.scrollRectToVisible(editor.modelToView2D(targetIndex).getBounds());
        } catch (Exception e) {}
    }

    private void performReplaceAll() {
        String textToFind = findField.getText();
        String textToReplace = replaceField.getText();
        
        if (textToFind.isEmpty()) {
            return;
        }

        // Get the accurate positions of plain text matches
        ArrayList<Integer> matches = getMatchList(textToFind);
        if (matches.isEmpty()) {
            return; // Nothing to replace
        }

        try {
            Document doc = editor.getDocument();
            int findLength = textToFind.length();
            int replaceLength = textToReplace.length();
            
            // Track index translation offset drift as document layout expands/shrinks
            int positionOffsetShift = 0; 

            // Iterate forward through the valid target offsets
            for (int originalMatchIndex : matches) {
                int adjustedIndex = originalMatchIndex + positionOffsetShift;
                
                // Remove old string slice and inject the updated replacement sequence
                doc.remove(adjustedIndex, findLength);
                doc.insertString(adjustedIndex, textToReplace, null);
                
                // Adjust translation offset matrix based on text length mutation differences
                positionOffsetShift += (replaceLength - findLength);
            }
        } catch (BadLocationException e) {
            e.printStackTrace();
        }

        performFind(true); // Re-index counts and clear remaining highlight artifacts
    }

    @Override
    public void dispose() { 
        editor.getHighlighter().removeAllHighlights(); 
        super.dispose(); 
    }
}
